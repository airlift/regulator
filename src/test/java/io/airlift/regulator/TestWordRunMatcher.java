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

public class TestWordRunMatcher
{
    @Test
    public void testEligibility()
    {
        for (String pattern : List.of("\\b\\w+\\b", "\\b[a-z]+\\b", "(?U)\\b[a-z]+\\b", "(?i)\\b[a-z]+\\b")) {
            assertThat(analyze(pattern)).as(pattern).isNotNull();
        }
        for (String pattern : List.of("(?U)\\b\\w+\\b", "\\b\\w+?\\b", "\\B\\w+\\b", "\\b(\\w+)\\b", "\\b[a-z!]+\\b", "\\b\\w*\\b", "\\b\\w+")) {
            assertThat(analyze(pattern)).as(pattern).isNull();
        }
    }

    @Test
    public void testRetainedPlanUsesForwardBudget()
    {
        Slice pattern = utf8Slice("\\b\\w+\\b");
        int flags = JavaRegexp.Options.defaults().parseFlags();
        ParseResult parsed = JavaRegexpParser.parse(pattern, flags);
        long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
        Prog control = Compiler.compile(parsed.regexp(), false, maxMemory - maxMemory / 3);
        WordRunMatcher plan = WordRunMatcher.analyze(parsed.regexp(), control);
        Re2 compiled = Re2.compileParsed(pattern, parsed, flags, maxMemory);
        assertThat(compiled.forwardProgramForDiagnostics().dfaMemory())
                .isEqualTo(control.dfaMemory() - plan.estimatedRetainedSize());
    }

    @Test
    public void testWindowsAgainstSemanticNfa()
    {
        for (String pattern : List.of("\\b\\w+\\b", "\\b[a-z]+\\b", "(?U)\\b[a-z]+\\b")) {
            ParseResult parsed = JavaRegexpParser.parse(utf8Slice(pattern), JavaRegexp.Options.defaults().parseFlags());
            Prog program = Compiler.compile(parsed.regexp(), false, Re2.Options.DEFAULT_MAX_MEMORY);
            WordRunMatcher scanner = WordRunMatcher.analyze(parsed.regexp(), program);
            for (String value : List.of("éabc def💰ghi", "foo\u0301 bar", "ab123cd", "\u0301abc", "")) {
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
                            long span = scanner.search(text, 0, text.length(), start, end, anchor);
                            assertThat(span != Dfa.SEARCH_NO_MATCH).as("%s %s %s:%s %s", pattern, value, start, end, anchor).isEqualTo(matched);
                            if (matched) {
                                // The direct NFA returns offsets relative to its search start.
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
    public void testRotatingUnicodeInputsAgainstJdk()
    {
        Random random = new Random(39217);
        String[] alphabet = {"a", "z", "A", "9", "_", "é", "δ", "💰", "\u0301", " ", "\n", "!"};
        for (String expression : List.of("\\b\\w+\\b", "\\b[a-z]+\\b", "(?U)\\b[a-z]+\\b", "(?i)\\b[a-z]+\\b")) {
            Pattern comparator = Pattern.compile(expression);
            JavaRegexp pattern = JavaRegexp.compile(utf8Slice(expression));
            for (int trial = 0; trial < 300; trial++) {
                StringBuilder value = new StringBuilder();
                for (int index = 0; index < 20; index++) {
                    value.append(alphabet[random.nextInt(alphabet.length)]);
                }
                String text = value.toString();
                Slice padded = utf8Slice("!!" + text + "??");
                Re2Matcher actual = pattern.matcher(padded).reset(padded, 2, padded.length() - 2);
                Matcher expected = comparator.matcher(text);
                while (expected.find()) {
                    assertThat(actual.find()).isTrue();
                    assertThat(actual.start()).isEqualTo(2 + utf8Slice(text.substring(0, expected.start())).length());
                    assertThat(actual.end()).isEqualTo(2 + utf8Slice(text.substring(0, expected.end())).length());
                }
                assertThat(actual.find()).isFalse();
                assertThat(pattern.matches(utf8Slice(text))).isEqualTo(expected.reset().matches());
                assertThat(pattern.lookingAt(utf8Slice(text))).isEqualTo(expected.reset().lookingAt());
            }
        }
    }

    private static WordRunMatcher analyze(String pattern)
    {
        ParseResult parsed = JavaRegexpParser.parse(utf8Slice(pattern), JavaRegexp.Options.defaults().parseFlags());
        Prog program = Compiler.compile(parsed.regexp(), false, Re2.Options.DEFAULT_MAX_MEMORY);
        return WordRunMatcher.analyze(parsed.regexp(), program);
    }
}
