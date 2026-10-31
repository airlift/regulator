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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestJavaRegexp
{
    // Every text of up to four bytes over an ASCII word byte, an ASCII non-word byte, LF, both
    // bytes of ², and both bytes of a combining acute accent.
    private static final List<byte[]> WORD_BOUNDARY_TEXTS = TestingEngineAgreement.shortTexts(
            (byte) 'a', (byte) ' ', (byte) '\n', (byte) 0xC2, (byte) 0xB2, (byte) 0xCC, (byte) 0x81);

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
    public void testParse()
    {
        Slice pattern = utf8Slice("(?<word>\\w+)(b)?(?<never>[a&&b])");
        PatternInfo info = JavaRegexp.parse(pattern);
        assertThat(info.capturingGroupCount()).isEqualTo(3);
        assertThat(info.namedCapturingGroups()).isEqualTo(Map.of("word", 1, "never", 3));
        assertThat(info.capturingGroupNames()).isEqualTo(Map.of(1, "word", 3, "never"));

        JavaRegexp compiled = JavaRegexp.compile(pattern);
        assertThat(info.capturingGroupCount()).isEqualTo(compiled.capturingGroupCount());
        assertThat(info.namedCapturingGroups()).isEqualTo(compiled.namedCapturingGroups());
        assertThat(info.capturingGroupNames()).isEqualTo(compiled.capturingGroupNames());

        PatternInfo comments = JavaRegexp.parse(utf8Slice("#(ignored)\n(a)"), JavaRegexp.Options.defaults().setComments(true));
        assertThat(comments.capturingGroupCount()).isEqualTo(1);
        assertThat(comments.namedCapturingGroups()).isEmpty();
    }

    @Test
    public void testCompiledPatternDoesNotRetainMutablePatternStorage()
    {
        Slice pattern = utf8Slice("(?<name>a)");
        JavaRegexp regexp = JavaRegexp.compile(pattern);

        pattern.setByte(3, 'X');

        assertThat(regexp.namedCapturingGroups()).containsOnlyKeys("name");
        assertThat(regexp.pattern()).isEqualTo(utf8Slice("(?<name>a)"));
    }

    @Test
    public void testNamedGroupThatCannotMatch()
    {
        // Simplification removes the branch that can never match, but its group keeps its name.
        JavaRegexp regexp = JavaRegexp.compile(utf8Slice("(?<x>a)[a&&b]|b"));
        assertThat(regexp.capturingGroupCount()).isEqualTo(1);
        assertThat(regexp.namedCapturingGroups()).isEqualTo(Map.of("x", 1));

        MatchResult result = regexp.matchesResult(utf8Slice("b"));
        assertThat(result).isNotNull();
        assertThat(result.groupUtf8("x")).isNull();
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

        RegexpMatcher matcher = regexp.matcher(input);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group("value").toStringUtf8()).isEqualTo("a");

        assertThatThrownBy(() -> regexp.find(input, -1, input.length()))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    // Properties, word boundaries, and character names hold Unicode 18.0 data on every JVM. Unicode
    // 18.0 added U+0558 MODIFIER LETTER ARMENIAN SMALL EH, a letter, and the Seal script, which
    // starts at U+3D000.
    @Test
    public void testUnicode18DataOnEveryJvm()
    {
        assertThat(JavaRegexp.compile(utf8Slice("\\p{L}")).matches(utf8Slice("\u0558"))).isTrue();
        assertThat(JavaRegexp.compile(utf8Slice("(?U)\\b\u0558\\b")).find(utf8Slice("\u0558"))).isTrue();
        assertThat(JavaRegexp.compile(utf8Slice("\\N{MODIFIER LETTER ARMENIAN SMALL EH}")).matches(utf8Slice("\u0558"))).isTrue();
        assertThat(JavaRegexp.compile(utf8Slice("\\p{IsSeal}")).matches(utf8Slice("\uD8B4\uDC00"))).isTrue();
    }

    // A case-insensitive range also matches a character whose upper case, or the lower case of its
    // upper case, is one of the range's closing characters. Regulator matches these as JDK 26 does,
    // whichever JVM runs it. JDK 25 does not match them, so the differential tests leave these
    // ranges out.
    @Test
    public void testRangeClosingCharactersOnEveryJvm()
    {
        assertRangeMatches("ς-ς", "σ", "Σ");
        assertRangeMatches("\\x{212A}-\\x{212A}", "K", "k");
        assertRangeMatches("\\x{1FD2}-\\x{1FE4}", "\u0390", "\u03B0");
        assertRangeMatches("\\x{FB05}-\\x{FB05}", "\uFB06");
        assertRangeMatches("\\x{130}-\\x{130}", "i", "I", "ı");
        assertRangeMatches("\\x{131}-\\x{131}", "I", "i");
        assertThat(JavaRegexp.compile(utf8Slice("(?iu)[\\x{131}-\\x{131}]")).matches(utf8Slice("İ")))
                .as("(?iu)[\\x{131}-\\x{131}] against İ")
                .isFalse();
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

    @Test
    public void testWordBoundariesRunOnDfaAndOnePass()
    {
        // Java's \b and \B decode the character on each side, and a nonspacing mark after a
        // letter or digit counts as a word character. The forward and reverse DFAs evaluate them
        // between ASCII bytes and quit next to any other byte, and OnePass evaluates them as
        // BitState does, over text with ², a combining acute accent, and their bytes alone.
        Slice text = utf8Slice("x foo bar");
        Re2 words = JavaRegexp.compile(utf8Slice("\\bfoo\\b")).re2();
        assertThat(words.findInto(text, new int[2])).isTrue();
        assertThat(words.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNotNull();

        for (String pattern : List.of("\\ba\\b", "\\B", "a\\b", "\\b", "(?U)\\b", "(?U)\\B")) {
            Prog forward = compileJavaProgram(pattern, false);
            assertThat(forward.hasDfaUnsupportedAssertions()).as(pattern).isFalse();
            TestingEngineAgreement.assertDfaAgreesWithNfa(pattern, forward, compileJavaProgram(pattern, true), WORD_BOUNDARY_TEXTS, "Java");
        }
        for (Map.Entry<String, Integer> entry : Map.of("(a+)\\b( *)", 6, "\\B(.)", 4, "(?U)\\b(.)\\b", 4).entrySet()) {
            Prog program = compileJavaProgram(entry.getKey(), false);
            assertThat(program.isOnePass()).as(entry.getKey()).isTrue();
            TestingEngineAgreement.assertOnePassAgreesWithBitState(entry.getKey(), program, entry.getValue(), WORD_BOUNDARY_TEXTS, "Java");
        }
    }

    private static Prog compileJavaProgram(String pattern, boolean reversed)
    {
        ParseResult parsed = JavaRegexpParser.parse(utf8Slice(pattern), JavaRegexp.Options.defaults().parseFlags());
        Regexp regexp = Simplifier.simplify(parsed.regexp(), parsed.caseFolding());
        return Compiler.compileNormalized(regexp, reversed, 1 << 20, Compiler.Dialect.RE2, parsed.caseFolding());
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
                // A final terminator is CR LF as a whole, so neither matches between its two bytes.
                new MatchCase("a\\r$", "a\r\n", Operation.FIND, false),
                new MatchCase("a\\r\\Z", "a\r\n", Operation.FIND, false),
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
                // The | after a directive still splits the whole pattern, so c stands alone;
                // Trino's Joni reads a(?i)b|c as a(?i:b|c), which requires the a.
                new MatchCase("a(?i)b|c", "C", Operation.MATCHES, true),
                // Alternation preserves explicit and ASCII-only folded pairs, existing class
                // ranges, and the full Unicode orbit of Unicode-folded literals.
                new MatchCase("[Kk]|x", "K", Operation.MATCHES, false),
                new MatchCase("[Ss]|x", "ſ", Operation.MATCHES, false),
                new MatchCase("[Ii]|x", "İ", Operation.MATCHES, false),
                new MatchCase("[Ii]|x", "ı", Operation.MATCHES, false),
                new MatchCase("(?i)k|x", "K", Operation.MATCHES, false),
                new MatchCase("(?iu)k|x", "K", Operation.MATCHES, true),
                new MatchCase("(?iu:(?-i:[Kk])|x{1})", "K", Operation.MATCHES, false),
                new MatchCase("(?:(?iu)(?:(?-i:[ab])|x{1}))y", "Ay", Operation.FIND, false),
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
                new MatchCase("\\cé", "©", Operation.MATCHES, true),
                new MatchCase("\\c😀", "🙀", Operation.MATCHES, true),
                new MatchCase("[\\c😀]", "🙀", Operation.MATCHES, true),
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
                new MatchCase("(?x)a # comment\u2028b", "a\u2028b", Operation.MATCHES, true),
                new MatchCase("(?x)a # comment\u0000b", "a\u0000b", Operation.MATCHES, true),
                new MatchCase("(?dx)a# comment\u2028b", "a", Operation.MATCHES, true),
                new MatchCase("(?x)[a b]", " ", Operation.MATCHES, false),
                new MatchCase("(?x)[a b]", "b", Operation.MATCHES, true),
                new MatchCase("(?x)a{2, 3}", "aaa", Operation.MATCHES, true),
                new MatchCase("(?x)a\\ b", "a b", Operation.MATCHES, true),
                new MatchCase("(?x)a\\#b", "a#b", Operation.MATCHES, true),
                // The JDK skips whitespace and comments inside an escape wherever it reads past them.
                new MatchCase("(?x)\\c A", "\u0001", Operation.MATCHES, true),
                new MatchCase("(?x)\\0 1 # comment\n 2", "\n", Operation.MATCHES, true),
                new MatchCase("(?x)\\x4 1", "A", Operation.MATCHES, true),
                new MatchCase("(?x)\\x {4 1}", "A", Operation.MATCHES, true),
                new MatchCase("(?x)\\u00 41", "A", Operation.MATCHES, true),
                new MatchCase("(?x)\\uD83D \\ uDCB0", "💰", Operation.MATCHES, true),
                new MatchCase("(?x)\\N {WHITE SMILING FACE}", "☺", Operation.MATCHES, true),
                new MatchCase("(?x)\\p { Lu}", "A", Operation.MATCHES, true),
                new MatchCase("(?x)[\\p L]", "a", Operation.MATCHES, true),
                // The JDK deletes an empty \Q\E before parsing, so it does not end an octal escape; a non-empty quote does.
                new MatchCase("\\0\\Q\\E1\\Q\\E\\Q\\E2", "\n", Operation.MATCHES, true),
                new MatchCase("(?x)\\0 1\\Q\\E 2", "\n", Operation.MATCHES, true),
                new MatchCase("\\01\\Q2\\E", "\u00012", Operation.MATCHES, true),
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

    private static void assertRangeMatches(String range, String... inputs)
    {
        JavaRegexp regexp = JavaRegexp.compile(utf8Slice("(?iu)[" + range + "]"));
        for (String input : inputs) {
            assertThat(regexp.matches(utf8Slice(input))).as("(?iu)[%s] against %s", range, input).isTrue();
        }
    }
}
