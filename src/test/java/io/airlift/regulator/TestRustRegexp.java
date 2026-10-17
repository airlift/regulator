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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestRustRegexp
{
    @Test
    public void testUnicodeDefaults()
    {
        assertThat(RustRegexp.compile(utf8Slice("\\w+")).matches(utf8Slice("é\u200C"))).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("\\d+")).matches(utf8Slice("١٢"))).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("(?-u:\\w+)")).find(utf8Slice("é"))).isFalse();
        assertThat(RustRegexp.compile(utf8Slice("(?i)k")).matches(utf8Slice("K"))).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("(?i-u)k")).matches(utf8Slice("K"))).isFalse();
    }

    @Test
    public void testPropertyAliasNormalization()
    {
        for (String property : List.of("Lé", "İſL", "ıſL")) {
            assertThat(RustRegexp.compile(utf8Slice("\\p{" + property + "}")).matches(utf8Slice("a"))).isTrue();
        }
        assertThat(RustRegexp.compile(utf8Slice("\\p{IsL}")).matches(utf8Slice("a"))).isTrue();
        for (String property : List.of("İsL", "ıSL", "iſL", "IſL", "İsgc=Lu", "gc=İsLu")) {
            assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("\\p{" + property + "}")))
                    .isInstanceOf(RegexpParseException.class);
        }
    }

    @Test
    public void testHexDigitProperty()
    {
        RustRegexp regexp = RustRegexp.compile(utf8Slice("\\p{Hex_Digit}+"));
        assertThat(regexp.matches(utf8Slice("0123456789ABCDEFabcdef０１２３４５６７８９ＡＢＣＤＥＦａｂｃｄｅｆ"))).isTrue();
        assertThat(regexp.find(utf8Slice("١९𝟙GgＧｇ"))).isFalse();
        assertThat(RustRegexp.compile(utf8Slice("\\P{Hex_Digit}+")).matches(utf8Slice("١९𝟙"))).isTrue();
    }

    @Test
    public void testRangeAndMatcherAssertionContext()
    {
        Slice input = utf8Slice("ba");
        for (String pattern : List.of("^a", "\\ba")) {
            RustRegexp regexp = RustRegexp.compile(utf8Slice(pattern));
            assertThat(regexp.find(input, 1, 2)).isFalse();
            assertThat(regexp.matcher(input).reset(input, 1, 2).find()).isTrue();
        }
    }

    @Test
    public void testClassSetOperations()
    {
        assertThat(RustRegexp.compile(utf8Slice("[a-g~~b-h]+")).matches(utf8Slice("ah"))).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("[0-9--4]+")).matches(utf8Slice("123567890"))).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("[0-9--4]+")).find(utf8Slice("4"))).isFalse();
        assertThat(RustRegexp.compile(utf8Slice("[a&&b]")).find(utf8Slice("ab"))).isFalse();
    }

    @Test
    public void testRustAnchors()
    {
        assertThat(RustRegexp.compile(utf8Slice("a$")).find(utf8Slice("a\n"))).isFalse();
        assertThat(RustRegexp.compile(utf8Slice("(?m)^$")).find(utf8Slice(""))).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("(?mR)^foo$")).find(utf8Slice("\r\nfoo\r\n"))).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("(?mR)^\\n")).find(utf8Slice("\r\n"))).isFalse();
        assertThat(RustRegexp.compile(utf8Slice("\\b{start}é\\b{end}")).matches(utf8Slice("é"))).isTrue();
    }

    @Test
    public void testEmptyMatchIteration()
    {
        assertThat(RustRegexp.compile(utf8Slice("a*")).count(utf8Slice("a"))).isEqualTo(1);
        assertThat(RustRegexp.compile(utf8Slice("")).count(utf8Slice("💰"))).isEqualTo(2);
    }

    @Test
    public void testCaptures()
    {
        RustRegexp regexp = RustRegexp.compile(utf8Slice("(?P<value.part>é)(?<other>💰)?"));
        MatchResult result = regexp.findResult(utf8Slice("xé💰"));
        assertThat(result.start()).isEqualTo(1);
        assertThat(result.end()).isEqualTo(7);
        assertThat(result.groupUtf8("value.part")).isEqualTo("é");
        assertThat(regexp.capturingGroupCount()).isEqualTo(2);
    }

    @Test
    public void testRustOnlyEscapesAndFlags()
    {
        assertThat(RustRegexp.compile(utf8Slice("\\u{1F4B0}\\U000000E9")).matches(utf8Slice("💰é"))).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("(?x)[ a # ignored\n b ]+")).matches(utf8Slice("abba"))).isTrue();
        assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("\\Qabc\\E"))).isInstanceOf(RegexpParseException.class);
        assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("(?-u:.)"))).isInstanceOf(RegexpParseException.class);
        assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("(?i-i)a"))).isInstanceOf(RegexpParseException.class);
        assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("a{"))).isInstanceOf(RegexpParseException.class);
    }

    @Test
    public void testDeferredPropertiesAreExplicitErrors()
    {
        for (String property : List.of(
                "Age=3.2",
                "age=V6_0",
                "Script_Extensions=Katakana",
                "scx=Hira",
                "scx:Hira",
                "Grapheme_Cluster_Break=Extend",
                "gcb=Extend",
                "Word_Break=Numeric",
                "wb=Numeric",
                "Sentence_Break=STerm",
                "sb=STerm",
                "Math",
                "Default_Ignorable_Code_Point")) {
            assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("é\\p{" + property + "}")))
                    .isInstanceOfSatisfying(RegexpParseException.class, exception -> {
                        assertThat(exception.errorCode()).isEqualTo(RegexpParseErrorCode.UNSUPPORTED_CONSTRUCT);
                        assertThat(exception.byteOffset()).isGreaterThan(1);
                        assertThat(exception).hasMessageContaining(property);
                    });
        }
        assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("\\p{Not_A_Property}")))
                .isInstanceOfSatisfying(RegexpParseException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(RegexpParseErrorCode.BAD_CHAR_CLASS));
    }

    @Test
    public void testOptionsAndSnapshots()
    {
        RustRegexp.Options options = RustRegexp.Options.defaults()
                .setCaseInsensitive(true)
                .setMultiline(true)
                .setCrlf(true)
                .setIgnoreWhitespace(true);
        Slice source = utf8Slice(" ^ (?<name> é ) $ ");
        RustRegexp regexp = RustRegexp.compile(source, options);
        options.setCaseInsensitive(false).setMultiline(false).setCrlf(false).setIgnoreWhitespace(false);
        source.fill((byte) 'x');
        assertThat(regexp.findResult(utf8Slice("x\r\nÉ\r\nx")).groupUtf8("name")).isEqualTo("É");
        assertThat(RustRegexp.compile(utf8Slice("a+"), RustRegexp.Options.defaults().setSwapGreed(true))
                .findResult(utf8Slice("aaa")).end()).isEqualTo(1);
        assertThat(RustRegexp.compile(utf8Slice("."), RustRegexp.Options.defaults().setDotMatchesNewline(true))
                .matches(utf8Slice("\n"))).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("\\141"), RustRegexp.Options.defaults().setOctal(true))
                .matches(utf8Slice("a"))).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("\\w"), RustRegexp.Options.defaults().setUnicode(false))
                .find(utf8Slice("é"))).isFalse();
    }

    @Test
    public void testResourceLimits()
    {
        assertThatThrownBy(() -> RustRegexp.Options.defaults().setMaxMemory(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("abc"), RustRegexp.Options.defaults().setMaxMemory(1)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
        assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("a{1001}")))
                .isInstanceOfSatisfying(RegexpParseException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(RegexpParseErrorCode.REPEAT_SIZE));
        assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("a{100}{100}"))).isInstanceOf(RegexpParseException.class);
        assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("((a))"), RustRegexp.Options.defaults().setNestLimit(1)))
                .isInstanceOfSatisfying(RegexpParseException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(RegexpParseErrorCode.PATTERN_TOO_LARGE));
        assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("(".repeat(251) + "a" + ")".repeat(251))))
                .isInstanceOf(RegexpParseException.class);
    }

    @Test
    public void testRegionsBuffersAndResults()
    {
        Slice input = utf8Slice("--xxé--").slice(2, 4);
        RustRegexp regexp = RustRegexp.compile(utf8Slice("(?<value>é)(b)?"));
        assertThat(regexp.find(input, 2, 4)).isTrue();
        assertThat(regexp.lookingAt(input, 2, 4)).isTrue();
        assertThat(regexp.matches(input, 2, 4)).isTrue();
        int[] groups = new int[6];
        assertThat(regexp.findInto(input, 2, 4, groups)).isTrue();
        assertThat(groups).containsExactly(2, 4, 2, 4, -1, -1);
        assertThat(regexp.lookingAtInto(input, 2, 4, groups)).isTrue();
        assertThat(regexp.matchesInto(input, 2, 4, groups)).isTrue();
        assertThat(regexp.findResult(input, 2, 4).groupUtf8("value")).isEqualTo("é");
        assertThat(regexp.lookingAtResult(input, 2, 4).start()).isEqualTo(2);
        assertThat(regexp.matchesResult(input, 2, 4).end()).isEqualTo(4);
        RustRegexpMatcher matcher = regexp.matcher(input, 1);
        assertThat(matcher.groupCount()).isEqualTo(1);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group("value").byteArray()).isSameAs(input.byteArray());
        assertThat(matcher.toMatchResult().end()).isEqualTo(4);
        assertThat(matcher.reset(input, 2, 4).matches()).isTrue();
        assertThat(matcher.reset(input).lookingAt()).isFalse();
        assertThat(matcher.find(2)).isTrue();
        assertThat(matcher.find()).isFalse();
        assertThatThrownBy(matcher::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testUtf8AndLogicalSliceErrors()
    {
        RustRegexp regexp = RustRegexp.compile(utf8Slice(""));
        Slice input = utf8Slice("é💰");
        RustRegexpMatcher matcher = regexp.matcher(input);
        assertThat(matcher.find(1)).isTrue();
        assertThat(matcher.start()).isEqualTo(2);
        assertThat(matcher.find(3)).isTrue();
        assertThat(matcher.start()).isEqualTo(6);
        assertThatThrownBy(() -> regexp.find(input, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regexp.find(input, 1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> matcher.reset(input, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regexp.find(input, -1, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> regexp.find(wrappedBuffer(new byte[] {(byte) 0xFF}))).isInstanceOf(IllegalArgumentException.class);
        Slice pattern = wrappedBuffer(new byte[] {'x', 'x', 'a', (byte) 0xFF, 'x'}).slice(2, 2);
        assertThatThrownBy(() -> RustRegexp.compile(pattern))
                .isInstanceOfSatisfying(RegexpParseException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(RegexpParseErrorCode.BAD_UTF8);
                    assertThat(exception.byteOffset()).isEqualTo(1);
                });
    }

    @Test
    public void testTextAssertionsUseBoundedEngines()
    {
        for (String pattern : List.of("(?mR)^é$", "\\b{start}é\\b{end}", "(?-u)\\b{start}a\\b{end}")) {
            ParseResult parsed = RustRegexpParser.parse(utf8Slice(pattern), RustRegexp.Options.defaults());
            Prog program = Compiler.compile(parsed.regexp(), false, Re2.Options.DEFAULT_MAX_MEMORY);
            assertThat(program.hasDfaUnsupportedAssertions()).isTrue();
            Slice input = pattern.contains("-u") ? utf8Slice("!a!") : utf8Slice("\r\né\r\n");
            int[] nfa = new int[2];
            int[] bitState = new int[2];
            assertThat(Nfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, nfa)).isTrue();
            assertThat(BitState.search(program, input, false, Prog.MatchKind.FIRST_MATCH, bitState)).isTrue();
            assertThat(bitState).containsExactly(nfa);
        }
    }

    @Test
    public void testConcurrentCompiledPattern()
            throws Exception
    {
        RustRegexp regexp = RustRegexp.compile(utf8Slice("\\b(?<name>é+)\\b"));
        try (var executor = Executors.newFixedThreadPool(4)) {
            var results = new ArrayList<Future<String>>();
            for (int index = 0; index < 32; index++) {
                results.add(executor.submit(() -> regexp.findResult(utf8Slice("!éé!")).groupUtf8("name")));
            }
            for (var result : results) {
                assertThat(result.get()).isEqualTo("éé");
            }
        }
    }
}
