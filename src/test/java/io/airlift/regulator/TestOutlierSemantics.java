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

import io.airlift.jcodings.specific.NonStrictUTF8Encoding;
import io.airlift.joni.Option;
import io.airlift.joni.Regex;
import io.airlift.joni.Syntax;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceUtf8;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

public class TestOutlierSemantics
{
    @Test
    public void testDisjointSuffixCountAvoidsDfaConstruction()
    {
        Slice expression = utf8Slice("[a-q][^u-z]{80}x");
        int flags = JavaRegexp.Options.defaults().parseFlags();
        ParseResult parsed = JavaRegexpParser.parse(expression, flags);
        Re2 pattern = Re2.compileParsed(expression, parsed, flags);
        assertThat(pattern.count(utf8Slice("b".repeat(100_000) + "x"))).isEqualTo(1);
        assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
    }

    @Test
    public void testWholeInputCaptureAvoidsAutomatonWorkspace()
    {
        String value = "abcδ💰\n".repeat(100_000);
        Re2Matcher matcher = JavaRegexp.compile(utf8Slice("(?s)^((.*)()()($))")).matcher(utf8Slice(value));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start(2)).isZero();
        assertThat(matcher.end(2)).isEqualTo(utf8Slice(value).length());
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isFalse();
    }

    @Test
    public void testWholeInputCaptureEndpoints()
    {
        for (String pattern : List.of(
                "(?s)^((.*)()()($))",
                "(?s)\\A(()(.*)())\\z",
                "(?s)^((.*?)(()))$",
                "^((.*)()()($))",
                "(?sm)^((.*)()()($))",
                "(?sd)^((.*)()()($))")) {
            for (String text : List.of("", "abc", "\n", "abc\r\n", "a\nb", "δ💰\u0085", "a\u2028b\u2029")) {
                assertJavaMatches(pattern, text);
            }
        }
    }

    @Test
    public void testWordRunEndpointContext()
    {
        for (String pattern : List.of("\\b\\w+\\b", "\\b[a-z]+\\b", "(?U)\\b\\w+\\b", "\\B\\w+\\b")) {
            for (String text : List.of("", "foo bar", "123_foo", "éfooδ", "foo\u0301 bar", "\u0301abc", "a💰b", "a\r\nb", "abc123def")) {
                assertJavaMatches(pattern, text);
            }
        }
    }

    @Test
    public void testWordRunAvoidsAutomatonWorkspace()
    {
        // JDK finds the first complete ASCII word at 0:4, even with later Unicode input.
        Re2Matcher matcher = JavaRegexp.compile(utf8Slice("\\b\\w+\\b"))
                .matcher(utf8Slice("word ".repeat(200_000) + "é"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isEqualTo(4);
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isFalse();
    }

    @Test
    public void testTaggedBranchPriorityAndCaptures()
    {
        String padding = IntStream.range(0, 90).mapToObj(index -> "(long_keyword_" + index + ")").collect(joining("|"));
        for (String branches : List.of("(\\bfoo\\b)|(foo)|(.+)", "(\\bfoo\\b)|(foobar)|(.)", "(\\bfoo\\b)|()|(foo)")) {
            for (String text : List.of("", "foo", "foobar", "éfoo", "foo\u0301", "!foo!", "💰foo\n", "long_keyword_89")) {
                assertJavaMatches(branches + "|" + padding, text);
            }
        }
    }

    @Test
    public void testTrinoTaggedCapturesMatchPinnedJoni()
    {
        String padding = IntStream.range(0, 90).mapToObj(index -> "(long_keyword_" + index + ")").collect(joining("|"));
        for (String branches : List.of("(\\bfoo\\b)|(foo)|(.+)", "(\\bfoo\\b)|(foobar)|(.)", "(\\bfoo\\b)|()|(foo)", "(\\bfoo\\b)|((?s).*)")) {
            Slice expression = utf8Slice(branches + "|" + padding);
            byte[] patternBytes = expression.getBytes();
            Regex comparator = new Regex(patternBytes, 0, patternBytes.length, Option.DEFAULT, NonStrictUTF8Encoding.INSTANCE, Syntax.Java);
            TrinoRegexp pattern = TrinoRegexp.compile(expression);
            for (String text : List.of("", "foo", "foobar", "éfoo", "foo\u0301", "!foo!", "💰foo\n", "long_keyword_89")) {
                Slice padded = utf8Slice("!" + text + "?");
                Slice input = padded.slice(1, padded.length() - 2);
                int base = input.byteArrayOffset();
                io.airlift.joni.Matcher expected = comparator.matcher(input.byteArray(), base, base + input.length());
                TrinoRegexpMatcher actual = pattern.matcher(input);
                int next = 0;
                while (next <= input.length() && expected.search(base + next, base + input.length(), Option.DEFAULT) >= 0) {
                    assertThat(actual.find()).as("%s on %s", expression, text).isTrue();
                    var region = expected.getEagerRegion();
                    for (int group = 0; group <= actual.groupCount(); group++) {
                        assertThat(actual.start(group)).isEqualTo(region.beg[group]);
                        assertThat(actual.end(group)).isEqualTo(region.end[group]);
                    }
                    next = expected.getEnd();
                    if (expected.getBegin() == next) {
                        next += next == input.length() ? 1 : SliceUtf8.lengthOfCodePointSafe(input.byteArray(), base, input.length(), next);
                    }
                }
                assertThat(actual.find()).isFalse();
            }
        }
    }

    @Test
    public void testDisjointSuffixRepeatSearch()
    {
        for (int repeats : List.of(1, 3, 23, 80)) {
            String pattern = "[a-q][^u-z]{" + repeats + "}x";
            for (String text : List.of(
                    "",
                    "xxxx",
                    "a" + "b".repeat(repeats) + "x",
                    "a" + "δ".repeat(repeats) + "x",
                    "a" + "💰".repeat(repeats) + "x",
                    "a" + "b".repeat(repeats + 3) + "xx",
                    "a" + "b".repeat(repeats) + "yxx",
                    ("a" + "b".repeat(repeats) + "x").repeat(3))) {
                assertJavaMatches(pattern, text);
            }
        }
    }

    @Test
    public void testMalformedInputRetainsSemanticEngineBehavior()
    {
        for (String expression : List.of("(?s)^((.*)()()($))", "\\b\\w+\\b", "[a-q][^u-z]{3}x")) {
            int flags = JavaRegexp.Options.defaults().parseFlags();
            ParseResult parsed = JavaRegexpParser.parse(utf8Slice(expression), flags);
            Prog semanticProgram = Compiler.compile(parsed.regexp(), false, Re2.Options.DEFAULT_MAX_MEMORY);
            JavaRegexp pattern = JavaRegexp.compile(utf8Slice(expression));
            // Malformed input has no JDK String oracle. Compare the existing strict UTF-8 NFA,
            // including a valid encoded replacement character versus an invalid byte.
            for (byte[] bytes : List.of(
                    new byte[] {(byte) 0xFF},
                    new byte[] {'a', 'b', (byte) 0xC0, (byte) 0xAF, 'x'},
                    new byte[] {'a', 'b', (byte) 0xE2, (byte) 0x82, 'x'},
                    new byte[] {'a', (byte) 0xED, (byte) 0xA0, (byte) 0x80, 'x'},
                    new byte[] {'a', 'b', (byte) 0xEF, (byte) 0xBF, (byte) 0xBD, 'c', 'x'},
                    new byte[] {(byte) 0x80, 'a', 'b', 'c', 'd', 'x', (byte) 0xFF})) {
                Slice input = wrappedBuffer(bytes);
                int[] expected = new int[(parsed.capturingGroupCount() + 1) * 2];
                boolean found = Nfa.search(semanticProgram, input, false, Prog.MatchKind.FIRST_MATCH, expected);
                Re2Matcher actual = pattern.matcher(input);
                assertThat(actual.find()).as("%s on malformed input", expression).isEqualTo(found);
                if (found) {
                    for (int group = 0; group <= actual.groupCount(); group++) {
                        assertThat(actual.start(group)).isEqualTo(expected[group * 2]);
                        assertThat(actual.end(group)).isEqualTo(expected[group * 2 + 1]);
                    }
                }
                assertThat(pattern.matches(input)).isEqualTo(Nfa.fullMatch(semanticProgram, input));
                assertThat(pattern.lookingAt(input)).isEqualTo(Nfa.search(semanticProgram, input, true, Prog.MatchKind.FIRST_MATCH, null));
            }
        }
    }

    private static void assertJavaMatches(String expression, String text)
    {
        Matcher expected = Pattern.compile(expression).matcher(text);
        JavaRegexp pattern = JavaRegexp.compile(utf8Slice(expression));
        Slice padded = utf8Slice("!" + text + "?");
        Slice input = padded.slice(1, padded.length() - 2);
        Re2Matcher actual = pattern.matcher(input);
        long matches = 0;
        int next = 0;
        // Compare Java matching at the cursor positions required by our UTF-8 matcher contract.
        // JDK's implicit empty-match advancement can instead split a surrogate pair.
        while (next <= text.length() && expected.find(next)) {
            assertThat(actual.find()).as("%s on %s", expression, text).isTrue();
            assertThat(actual.groupCount()).isEqualTo(expected.groupCount());
            for (int group = 0; group <= expected.groupCount(); group++) {
                assertThat(actual.start(group)).isEqualTo(byteOffset(text, expected.start(group)));
                assertThat(actual.end(group)).isEqualTo(byteOffset(text, expected.end(group)));
            }
            matches++;
            if (expected.start() != expected.end()) {
                next = expected.end();
            }
            else {
                next = expected.end() == text.length() ? text.length() + 1 : text.offsetByCodePoints(expected.end(), 1);
            }
        }
        assertThat(actual.find()).isFalse();
        assertThat(pattern.count(input)).isEqualTo(matches);
        assertThat(pattern.matches(input)).isEqualTo(expected.reset().matches());
        assertThat(pattern.lookingAt(input)).isEqualTo(expected.reset().lookingAt());
    }

    private static int byteOffset(String text, int position)
    {
        if (position > 0 && position < text.length() &&
                Character.isHighSurrogate(text.charAt(position - 1)) && Character.isLowSurrogate(text.charAt(position))) {
            throw new IllegalArgumentException("oracle offset splits a code point");
        }
        return position < 0 ? -1 : utf8Slice(text.substring(0, position)).length();
    }
}
