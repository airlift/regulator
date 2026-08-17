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

import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.airlift.slice.Slices.utf8Slice;
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
        String pattern = "[a-q][^u-z]{3}x";
        ParseResult parsed = parse(pattern);
        Prog program = Compiler.compile(parsed.regexp(), false, Re2.Options.DEFAULT_MAX_MEMORY);
        DisjointSuffixRepeatMatcher scanner = DisjointSuffixRepeatMatcher.analyze(parsed.regexp());
        for (String value : List.of("abcδx", "a💰δ💰xabcδx", "xxxxxxxx", "abababab", "a\uFFFDδbx", "aδδδyxx", "a\r\nδx")) {
            Slice backing = utf8Slice("!" + value + "?");
            Slice text = backing.slice(1, backing.length() - 2);
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
                        assertThat(span != Dfa.SEARCH_NO_MATCH).as("%s %s:%s %s", value, start, end, anchor).isEqualTo(matched);
                        if (matched) {
                            assertThat((int) (span >>> 32)).isEqualTo(start + offsets[0]);
                            assertThat((int) span).isEqualTo(start + offsets[1]);
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
                Re2Matcher actual = pattern.matcher(padded).reset(padded, 1, padded.length() - 1);
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
