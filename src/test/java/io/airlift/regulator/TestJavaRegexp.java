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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestJavaRegexp
{
    @Test
    public void testCaptureResultsUseUtf8ByteOffsets()
    {
        String patternText = "(?<money>💰)(é)?";
        String inputText = "a💰éb";

        JavaRegexp regexp = JavaRegexp.compile(utf8Slice(patternText));
        MatchResult result = regexp.findResult(utf8Slice(inputText));

        Matcher jdkMatcher = Pattern.compile(patternText).matcher(inputText);
        assertThat(jdkMatcher.find()).isTrue();
        assertThat(result).isNotNull();
        assertThat(result.groupCount()).isEqualTo(jdkMatcher.groupCount());
        assertThat(regexp.capturingGroupCount()).isEqualTo(jdkMatcher.groupCount());
        assertThat(regexp.namedCapturingGroups()).containsEntry("money", 1);

        for (int group = 0; group <= jdkMatcher.groupCount(); group++) {
            assertThat(result.start(group)).isEqualTo(utf8Offset(inputText, jdkMatcher.start(group)));
            assertThat(result.end(group)).isEqualTo(utf8Offset(inputText, jdkMatcher.end(group)));
            assertThat(result.groupUtf8(group)).isEqualTo(jdkMatcher.group(group));
        }
        assertThat(result.groupUtf8("money")).isEqualTo("💰");
    }

    @Test
    public void testCompiledPatternDoesNotRetainMutablePatternStorage()
    {
        Slice pattern = utf8Slice("(?<name>a)");
        JavaRegexp regexp = JavaRegexp.compile(pattern);

        pattern.setByte(3, 'X');

        assertThat(regexp.namedCapturingGroups()).containsOnlyKeys("name");
    }

    @Test
    public void testFinalTerminatorMatchBounds()
    {
        String inputText = "abc\r\n";
        MatchResult result = JavaRegexp.compile(utf8Slice("abc$")).findResult(utf8Slice(inputText));

        Matcher jdkMatcher = Pattern.compile("abc$").matcher(inputText);
        assertThat(jdkMatcher.find()).isTrue();
        assertThat(result).isNotNull();
        assertThat(result.start()).isEqualTo(utf8Offset(inputText, jdkMatcher.start()));
        assertThat(result.end()).isEqualTo(utf8Offset(inputText, jdkMatcher.end()));
    }

    @Test
    public void testMatcherAndRangeApis()
    {
        JavaRegexp regexp = JavaRegexp.compile(utf8Slice("(?<value>a)(b)?"));
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("matchCases")
    public void testSupportedSubsetMatchesJdk(MatchCase matchCase)
    {
        JavaRegexp regexp = JavaRegexp.compile(utf8Slice(matchCase.pattern()));
        Pattern jdkPattern = Pattern.compile(matchCase.pattern());

        boolean actual = switch (matchCase.operation()) {
            case FIND -> regexp.find(utf8Slice(matchCase.input()));
            case LOOKING_AT -> regexp.lookingAt(utf8Slice(matchCase.input()));
            case MATCHES -> regexp.matches(utf8Slice(matchCase.input()));
        };
        boolean expected = switch (matchCase.operation()) {
            case FIND -> jdkPattern.matcher(matchCase.input()).find();
            case LOOKING_AT -> jdkPattern.matcher(matchCase.input()).lookingAt();
            case MATCHES -> jdkPattern.matcher(matchCase.input()).matches();
        };

        assertThat(actual).isEqualTo(expected).isEqualTo(matchCase.expected());
    }

    private static Stream<MatchCase> matchCases()
    {
        return Stream.of(
                new MatchCase("literal", "a literal value", Operation.FIND, true),
                new MatchCase("a|bc", "bc", Operation.MATCHES, true),
                new MatchCase("(?<name>a)(?:b)", "ab", Operation.MATCHES, true),
                new MatchCase("[a-z&&[^bc]]+", "ade", Operation.MATCHES, true),
                new MatchCase("[a-d[m-p]]+", "acmp", Operation.MATCHES, true),
                new MatchCase(".", "\r", Operation.FIND, false),
                new MatchCase(".", "\u2028", Operation.FIND, false),
                new MatchCase("(?s:.)", "\u2028", Operation.MATCHES, true),
                new MatchCase("^abc", "abc", Operation.LOOKING_AT, true),
                new MatchCase("abc$", "abc", Operation.FIND, true),
                new MatchCase("abc$", "abc\n", Operation.FIND, true),
                new MatchCase("abc$", "abc\r", Operation.FIND, true),
                new MatchCase("abc$", "abc\r\n", Operation.FIND, true),
                new MatchCase("abc$", "abc\u0085", Operation.FIND, true),
                new MatchCase("abc$", "abc\u2028", Operation.FIND, true),
                new MatchCase("abc$", "abc\u2029", Operation.FIND, true),
                new MatchCase("abc$", "abc\nx", Operation.FIND, false),
                new MatchCase("abc\\Z", "abc\r\n", Operation.FIND, true),
                new MatchCase("abc\\Z", "abc\nx", Operation.FIND, false),
                new MatchCase("(?m)^b$", "a\u2028b\u2029c", Operation.FIND, true),
                new MatchCase("(?m)^b$", "a\r\nb\r\nc", Operation.FIND, true),
                new MatchCase("(?m)^", "", Operation.FIND, false),
                new MatchCase("(?m)$", "", Operation.FIND, true),
                new MatchCase("(?m)^\n", "\r\n", Operation.FIND, false),
                new MatchCase("(?dm)^b$", "a\rb\rc", Operation.FIND, false),
                new MatchCase("(?dm)^b$", "a\nb\nc", Operation.FIND, true),
                new MatchCase("(?d).", "\r", Operation.MATCHES, true),
                new MatchCase("(?i)k", "K", Operation.MATCHES, true),
                new MatchCase("(?i)k", "K", Operation.MATCHES, false),
                new MatchCase("(?iu)k", "K", Operation.MATCHES, true),
                new MatchCase("(?iu)ß", "SS", Operation.MATCHES, false),
                new MatchCase("(?iU)é", "É", Operation.MATCHES, true),
                new MatchCase("\\p{Lower}+", "abc", Operation.MATCHES, true),
                new MatchCase("\\p{Lower}+", "é", Operation.MATCHES, false),
                new MatchCase("(?U)\\p{Lower}+", "é", Operation.MATCHES, true),
                new MatchCase("(?i)[a-z]+", "ABC", Operation.MATCHES, true),
                new MatchCase("(?U)\\d+", "١٢", Operation.MATCHES, true),
                new MatchCase("(?U)\\w+", "élan", Operation.MATCHES, true),
                new MatchCase("(?U)\\s+", "\u0085\u2007", Operation.MATCHES, true),
                new MatchCase("\\bélan\\b", "élan", Operation.FIND, false),
                new MatchCase("(?U)\\bélan\\b", "élan", Operation.FIND, true),
                new MatchCase("a\\b\u0301", "a\u0301", Operation.FIND, false),
                new MatchCase("a\\B\u0301", "a\u0301", Operation.FIND, true),
                new MatchCase("(?U)\\b\u0301", "\u0301", Operation.FIND, true),
                new MatchCase("a+?", "aaa", Operation.MATCHES, true),
                new MatchCase("a{01,003}", "aa", Operation.MATCHES, true),
                new MatchCase("\\Aabc\\z", "abc", Operation.MATCHES, true),
                new MatchCase("\\h+", "\t\u2007", Operation.MATCHES, true),
                new MatchCase("\\H+", "abc", Operation.MATCHES, true),
                new MatchCase("\\v+", "\n\u2029", Operation.MATCHES, true),
                new MatchCase("\\V+", "abc", Operation.MATCHES, true),
                new MatchCase("\\e", "\u001B", Operation.MATCHES, true),
                new MatchCase("\\cA", "\u0001", Operation.MATCHES, true),
                new MatchCase("\\N{WHITE SMILING FACE}", "☺", Operation.MATCHES, true),
                new MatchCase("\\u0041+", "AAA", Operation.MATCHES, true),
                new MatchCase("\\0123", "S", Operation.MATCHES, true),
                new MatchCase("\\0377", "ÿ", Operation.MATCHES, true),
                new MatchCase("\\uD83D\\uDCB0", "💰", Operation.MATCHES, true),
                new MatchCase("\\x{1F4B0}", "💰", Operation.MATCHES, true),
                new MatchCase("\\p{IsLatin}+", "abc", Operation.MATCHES, true),
                new MatchCase("\\p{script=Latin}+", "abc", Operation.MATCHES, true),
                new MatchCase("\\p{InBasic_Latin}+", "abc", Operation.MATCHES, true),
                new MatchCase("\\p{javaLowerCase}+", "abc", Operation.MATCHES, true),
                new MatchCase("\\Q[a-z]+\\E", "[a-z]+", Operation.MATCHES, true),
                new MatchCase("[\\Qabc\\E]", "b", Operation.MATCHES, true),
                new MatchCase("[ab&&]", "a", Operation.MATCHES, true),
                new MatchCase("[&&ab]", "b", Operation.MATCHES, true),
                new MatchCase("[a&&b]", "a", Operation.MATCHES, false),
                new MatchCase("[a&&&&b]", "a", Operation.MATCHES, false),
                new MatchCase("[ab&&cd&&de]", "d", Operation.MATCHES, false),
                new MatchCase("[x&&[a&&b]]", "x", Operation.MATCHES, false),
                new MatchCase("[[:alpha:]]", ":", Operation.MATCHES, true),
                new MatchCase("(?x)a b # comment\n c", "abc", Operation.MATCHES, true),
                new MatchCase("(?x)[a b]", " ", Operation.MATCHES, false),
                new MatchCase("(?x)[a b]", "b", Operation.MATCHES, true),
                new MatchCase("(?x)a{2, 3}", "aaa", Operation.MATCHES, true),
                new MatchCase("(?x)a\\ b", "a b", Operation.MATCHES, true),
                new MatchCase("(?x)a\\#b", "a#b", Operation.MATCHES, true),
                new MatchCase("(?U-u)\\w+", "élan", Operation.MATCHES, true),
                new MatchCase("(?U-U)\\w+", "élan", Operation.MATCHES, false),
                new MatchCase("[\\v-z]", "a", Operation.MATCHES, true),
                new MatchCase("\\d+", "123", Operation.LOOKING_AT, true),
                new MatchCase("\\w+", "abc_123", Operation.MATCHES, true));
    }

    private enum Operation
    {
        FIND,
        LOOKING_AT,
        MATCHES,
    }

    private record MatchCase(String pattern, String input, Operation operation, boolean expected) {}

    private static int utf8Offset(String input, int utf16Offset)
    {
        if (utf16Offset < 0) {
            return -1;
        }
        return input.substring(0, utf16Offset).getBytes(StandardCharsets.UTF_8).length;
    }
}
