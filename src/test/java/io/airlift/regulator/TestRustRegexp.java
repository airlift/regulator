/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.regulator;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestRustRegexp
{
    @Test
    public void testPublicApiMatchesJavaRegexp()
    {
        assertThat(instanceMethods(RustRegexp.class)).isEqualTo(instanceMethods(JavaRegexp.class));
    }

    @Test
    public void testCompiledPatternDoesNotRetainMutablePatternStorage()
    {
        Slice pattern = utf8Slice("(?<name>a)");
        RustRegexp regexp = RustRegexp.compile(pattern);

        pattern.setByte(3, 'X');

        assertThat(regexp.namedCapturingGroups()).containsOnlyKeys("name");
    }

    @Test
    public void testNullArguments()
    {
        assertThatThrownBy(() -> RustRegexp.compile(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("pattern is null");
        assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("a"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("options is null");
        RustRegexp regexp = RustRegexp.compile(utf8Slice("a*"));
        assertThatThrownBy(() -> regexp.find(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("input is null");
        assertThatThrownBy(() -> regexp.findResult(null, 0, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("input is null");
        assertThatThrownBy(() -> regexp.matchesInto(null, new int[2]))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("input is null");
    }

    @Test
    public void testMatcherAndRangeApis()
    {
        RustRegexp regexp = RustRegexp.compile(utf8Slice("(?<value>a)(b)?"));
        Slice input = utf8Slice("xxa");

        assertThat(regexp.find(input, 2, 3)).isTrue();
        assertThat(regexp.lookingAt(input, 2, 3)).isTrue();
        assertThat(regexp.matches(input, 2, 3)).isTrue();

        int[] groups = new int[6];
        assertThat(regexp.findInto(input, 2, 3, groups)).isTrue();
        assertThat(groups).containsExactly(2, 3, 2, 3, -1, -1);

        MatchResult result = regexp.findResult(input, 2, 3);
        assertThat(result.groupUtf8("value")).isEqualTo("a");
        assertThat(regexp.lookingAtResult(input, 2, 3)).isNotNull();
        assertThat(regexp.matchesResult(input, 2, 3)).isNotNull();

        Re2Matcher matcher = regexp.matcher(input);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group("value").toStringUtf8()).isEqualTo("a");

        assertThatThrownBy(() -> regexp.find(input, -1, input.length()))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    public void testRangeStartingInsideCharacter()
    {
        // The crate's Regex never reports an empty match that splits a character, and an
        // anchored search cannot move past one.
        RustRegexp regexp = RustRegexp.compile(utf8Slice(""));
        Slice input = utf8Slice("𝛃");
        int[] groups = new int[2];

        assertThat(regexp.find(input, 1, 3)).isFalse();
        assertThat(regexp.findInto(input, 1, 3, groups)).isFalse();
        assertThat(groups).containsExactly(-1, -1);
        assertThat(regexp.findInto(input, 1, 4, groups)).isTrue();
        assertThat(groups).containsExactly(4, 4);

        assertThat(regexp.lookingAt(input, 1, 4)).isFalse();
        assertThat(regexp.lookingAtInto(input, 1, 4, groups)).isFalse();
        assertThat(groups).containsExactly(-1, -1);
        assertThat(regexp.matches(input, 1, 1)).isFalse();
        assertThat(regexp.matchesInto(input, 1, 1, groups)).isFalse();

        Re2Matcher matcher = regexp.matcher(input).reset(input, 1, 4);
        assertThat(matcher.lookingAt()).isFalse();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(4);
        assertThat(matcher.find()).isFalse();
    }

    @Test
    public void testBytesRangeStartingInsideCharacter()
    {
        // The crate's bytes::Regex reports an empty match that splits a character, so an anchored
        // search succeeds there.
        RustRegexp regexp = RustRegexp.compile(utf8Slice(""), RustRegexp.Options.bytes());
        Slice input = utf8Slice("𝛃");
        int[] groups = new int[2];

        assertThat(regexp.find(input, 1, 4)).isTrue();
        assertThat(regexp.findInto(input, 1, 4, groups)).isTrue();
        assertThat(groups).containsExactly(1, 1);

        assertThat(regexp.lookingAt(input, 1, 4)).isTrue();
        assertThat(regexp.lookingAtInto(input, 1, 4, groups)).isTrue();
        assertThat(groups).containsExactly(1, 1);
        assertThat(regexp.matches(input, 1, 1)).isTrue();
        assertThat(regexp.matchesInto(input, 1, 1, groups)).isTrue();
        assertThat(groups).containsExactly(1, 1);

        Re2Matcher matcher = regexp.matcher(input).reset(input, 1, 4);
        assertThat(matcher.lookingAt()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.reset(input, 1, 1).matches()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
    }

    @Test
    public void testPatternMustBeUtf8()
    {
        // Even the crate's bytes API, which matches bytes that are not UTF-8, takes its patterns
        // as strings.
        Slice pattern = Slices.wrappedBuffer(new byte[] {'a', (byte) 0xFF});
        for (RustRegexp.Options options : List.of(RustRegexp.Options.defaults(), RustRegexp.Options.bytes())) {
            assertThatThrownBy(() -> RustRegexp.compile(pattern, options))
                    .isInstanceOfSatisfying(RegexpParseException.class, exception -> {
                        assertThat(exception.errorCode()).isEqualTo(RegexpParseErrorCode.BAD_UTF8);
                        assertThat(exception.byteOffset()).isEqualTo(1);
                    });
        }
    }

    // Property names come from the Unicode 18.0 database, so they include versions and scripts
    // newer than the crate's Unicode 16.0 tables, such as Jurchen, added in 18.0.
    @Test
    public void testUnicodePropertiesFollowDatabase()
    {
        Slice jurchen = utf8Slice(Character.toString(0x18E00));
        assertThat(RustRegexp.compile(utf8Slice("\\p{Age=18.0}")).matches(jurchen)).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("\\p{Age=17.0}")).matches(jurchen)).isFalse();
        assertThat(RustRegexp.compile(utf8Slice("\\p{Jurchen}")).matches(jurchen)).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("\\p{scx=Jurc}")).matches(jurchen)).isTrue();

        // Age holds every character assigned in its version or earlier, and Script_Extensions
        // reads a character's extension list: U+0640 ARABIC TATWEEL is Common, and its list
        // names Arabic.
        assertThat(RustRegexp.compile(utf8Slice("\\p{Age=6.0}")).matches(utf8Slice("A"))).isTrue();
        Slice tatweel = utf8Slice(Character.toString(0x640));
        assertThat(RustRegexp.compile(utf8Slice("\\p{scx=Arab}")).matches(tatweel)).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("\\p{scx=Common}")).matches(tatweel)).isFalse();
    }

    @Test
    public void testRustAssertionsAgreeInBoundedEngines()
    {
        // These assertions keep a program off the DFA and OnePass engines, so both bounded
        // engines must evaluate them.
        assertBoundedEngines("(?mR)^é$", "\r\né\r\n", 2, 4);
        assertBoundedEngines("\\b{start}é\\b{end}", "!é!", 1, 3);
        assertBoundedEngines("(?-u)\\b{start}a\\b{end}", "!a!", 1, 2);
        assertBoundedEngines("(?-u)\\b{start}é", "é", -1, -1);
        assertBoundedEngines("\\b{end-half}", "ab", 2, 2);
        assertBoundedEngines("\\<é", "aé é", 4, 6);
        // Different assertions in one program stay distinct.
        assertBoundedEngines("\\b{start}a|\\b{end}b", "b", -1, -1);
        assertBoundedEngines("\\b\\b{start}", "a b", 0, 0);

        Re2Matcher matcher = RustRegexp.compile(utf8Slice("\\b\\b{start}")).matcher(utf8Slice("a b"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(0);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(2);
        assertThat(matcher.find()).isFalse();
    }

    private static void assertBoundedEngines(String pattern, String text, int start, int end)
    {
        RustRegexp.Options options = RustRegexp.Options.defaults();
        Prog program = Compiler.compile(RustRegexpParser.parse(utf8Slice(pattern), options.syntaxFlags(), options.nestLimit()).regexp());
        assertThat(program.hasTextDependentAssertions()).isTrue();
        int[] nfa = new int[2];
        int[] bitState = new int[2];
        assertThat(Nfa.search(program, utf8Slice(text), false, Prog.MatchKind.FIRST_MATCH, nfa)).isEqualTo(start >= 0);
        assertThat(BitState.search(program, utf8Slice(text), false, Prog.MatchKind.FIRST_MATCH, bitState)).isEqualTo(start >= 0);
        if (start >= 0) {
            assertThat(nfa).containsExactly(start, end);
            assertThat(bitState).containsExactly(start, end);
        }
    }

    private static List<String> instanceMethods(Class<?> type)
    {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers()))
                .map(TestRustRegexp::signature)
                .sorted()
                .toList();
    }

    private static String signature(Method method)
    {
        return method.getReturnType().getSimpleName() + " " + method.getName() + Arrays.toString(method.getParameterTypes());
    }
}
