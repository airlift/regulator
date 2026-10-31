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

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDisjointSuffixRepeatMatcher
{
    @Test
    public void testEligibility()
    {
        for (String pattern : List.of("[a-q][^u-z]{80}x", "[a-q][^u-z]{23}x", "[ab][cd]{3}x")) {
            assertThat(analyze(pattern)).as(pattern).isNotNull();
        }
        for (String pattern : List.of("[a-q][^u-z]{80}[xÀ-ÿ]", "[a-q][^u-w]{80}x", "[a-q][^u-z]{1,80}x", "[a-q]([^u-z]{80})x", "(?i)[a-q][^u-z]{80}x", "[aδ][^u-z]{80}x", "^[a-q][^u-z]{80}x")) {
            assertThat(analyze(pattern)).as(pattern).isNull();
        }
    }

    @Test
    public void testWindowsAgainstSemanticNfa()
    {
        List<byte[]> values = new ArrayList<>();
        for (String value : List.of("abcδx", "a💰δ💰xabcδx", "xxxxxxxx", "abababab", "a\uFFFDδbx", "aδδδyxx", "a\r\nδx")) {
            values.add(value.getBytes(UTF_8));
        }
        // An encoded surrogate, overlong E0 80 80, F4 90 80 80 above U+10FFFF, and a bare continuation
        // byte. Pinned native RE2 confirmed the matches over a surrogate for each class holding one, and
        // over the overlong and too-large forms for the first two, lenient, classes. The test compares
        // with the semantic NFA.
        values.add(new byte[] {'a', (byte) 0xED, (byte) 0xA0, (byte) 0x80, 'b', 'x'});
        values.add(new byte[] {'a', (byte) 0xE0, (byte) 0x80, (byte) 0x80, 'b', 'x'});
        values.add(new byte[] {'a', (byte) 0xE0, (byte) 0x80, (byte) 0x80, (byte) 0xC3, (byte) 0xA9, 'b', 'x'});
        values.add(new byte[] {'a', (byte) 0xED, (byte) 0xA0, (byte) 0x80, (byte) 0xC3, (byte) 0xA9, 'b', 'x'});
        values.add(new byte[] {'a', (byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80, 'b', 'b', 'x'});
        values.add(new byte[] {'a', (byte) 0xC3, (byte) 0xA9, (byte) 0x80, 'b', 'x'});
        // Classes of every non-ASCII character, and of some that include or exclude the surrogates,
        // then Latin-1 classes, which read each byte as one character.
        List<Map.Entry<String, ParseResult>> expressions = new ArrayList<>();
        for (String pattern : List.of("[a-q][^u-z]{3}x", "[ab][^x]{2}x", "[ab][^\\x{100}x]{2}x", "[ab][^\\p{Cs}x]{2}x")) {
            expressions.add(entry(pattern, parse(pattern)));
        }
        for (String pattern : List.of("[a-q][^u-z]{3}x", "[ab][^\\x{e9}x]{2}x", "[ab][^\\x{80}-\\x{ff}x]{2}x")) {
            expressions.add(entry("Latin-1 " + pattern, RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL | Regexp.LATIN1)));
        }
        for (Map.Entry<String, ParseResult> expression : expressions) {
            String pattern = expression.getKey();
            ParseResult parsed = expression.getValue();
            Prog program = Compiler.compile(parsed.regexp(), false, Re2.Options.DEFAULT_MAX_MEMORY);
            DisjointSuffixRepeatMatcher scanner = DisjointSuffixRepeatMatcher.analyze(parsed.regexp());
            assertThat(scanner).as(pattern).isNotNull();
            for (byte[] value : values) {
                byte[] backing = new byte[value.length + 2];
                backing[0] = '!';
                System.arraycopy(value, 0, backing, 1, value.length);
                backing[backing.length - 1] = '?';
                Slice text = Slices.wrappedBuffer(backing, 1, value.length);
                String hex = HexFormat.of().formatHex(value);
                for (int start = 0; start <= text.length(); start++) {
                    for (int end = start; end <= text.length(); end++) {
                        for (Re2.Anchor anchor : Re2.Anchor.values()) {
                            int[] offsets = new int[2];
                            boolean matched = Nfa.search(
                                    program,
                                    text,
                                    start,
                                    end,
                                    anchor != Re2.Anchor.UNANCHORED,
                                    anchor == Re2.Anchor.ANCHOR_BOTH ? Prog.MatchKind.FULL_MATCH : Prog.MatchKind.FIRST_MATCH,
                                    offsets);
                            long span = scanner.search(text, start, end, anchor);
                            assertThat(span != Dfa.SEARCH_NO_MATCH)
                                    .as("%s %s %s:%s %s", pattern, hex, start, end, anchor)
                                    .isEqualTo(matched);
                            if (matched) {
                                assertThat((int) (span >>> 32)).isEqualTo(start + offsets[0]);
                                assertThat((int) span).isEqualTo(start + offsets[1]);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void testRotatingUnicodeCountsAndSpansAgainstJdk()
    {
        Random random = new Random(98271);
        String[] alphabet = {"a", "b", "q", "u", "x", "z", "δ", "💰", "\uFFFD", "\n"};
        for (int repeat : List.of(3, 13, 23, 80)) {
            String expression = "[a-q][^u-z]{" + repeat + "}x";
            JavaRegexp pattern = JavaRegexp.compile(utf8Slice(expression));
            Pattern comparator = Pattern.compile(expression);
            for (int trial = 0; trial < 100; trial++) {
                StringBuilder value = new StringBuilder();
                for (int index = 0; index < 60; index++) {
                    value.append(alphabet[random.nextInt(alphabet.length)]);
                }
                value.append("a").append("δ".repeat(repeat)).append("x");
                value.append("a").append("b".repeat(repeat)).append("x");
                String text = value.toString();
                Slice padded = utf8Slice("!" + text + "?");
                RegexpMatcher actual = pattern.matcher(padded).reset(padded, 1, padded.length() - 1);
                Matcher expected = comparator.matcher(text);
                int count = 0;
                while (expected.find()) {
                    count++;
                    assertThat(actual.find()).isTrue();
                    assertThat(actual.start()).isEqualTo(1 + utf8Slice(text.substring(0, expected.start())).length());
                    assertThat(actual.end()).isEqualTo(1 + utf8Slice(text.substring(0, expected.end())).length());
                }
                assertThat(actual.find()).isFalse();
                assertThat(pattern.count(utf8Slice(text))).isEqualTo(count);
                assertThat(pattern.find(utf8Slice(text))).isEqualTo(count > 0);
                assertThat(pattern.matches(utf8Slice(text))).isEqualTo(expected.reset().matches());
                assertThat(pattern.lookingAt(utf8Slice(text))).isEqualTo(expected.reset().lookingAt());
            }
        }
    }

    @Test
    public void testRetainedPlanUsesForwardBudget()
    {
        String expression = "[a-q][^u-z]{80}x";
        ParseResult parsed = parse(expression);
        long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
        Prog control = Compiler.compile(parsed.regexp(), false, maxMemory - maxMemory / 3);
        DisjointSuffixRepeatMatcher plan = DisjointSuffixRepeatMatcher.analyze(parsed.regexp());
        Re2 compiled = Re2.compileParsed(utf8Slice(expression), parsed, JavaRegexp.Options.defaults().parseFlags(), maxMemory);
        assertThat(compiled.forwardProgramForDiagnostics().dfaMemory()).isEqualTo(control.dfaMemory() - plan.estimatedRetainedSize());
    }

    private static DisjointSuffixRepeatMatcher analyze(String expression)
    {
        return DisjointSuffixRepeatMatcher.analyze(parse(expression).regexp());
    }

    private static ParseResult parse(String expression)
    {
        return JavaRegexpParser.parse(utf8Slice(expression), JavaRegexp.Options.defaults().parseFlags());
    }
}
