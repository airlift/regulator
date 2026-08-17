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
import io.airlift.joni.Matcher;
import io.airlift.joni.Option;
import io.airlift.joni.Regex;
import io.airlift.joni.Region;
import io.airlift.joni.Syntax;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestTrinoNullableRepetition
{
    @Test
    public void testChallengedOperationsUseJoniBoundaries()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("(?:a?|b)*"));
        Slice source = utf8Slice("aab");

        assertThat(regexp.count(source)).isEqualTo(3);
        assertThat(regexp.position(source)).isEqualTo(1);
        assertThat(regexp.extract(source)).isEqualTo(utf8Slice("aa"));
        assertThat(regexp.extractAll(source)).containsExactly(utf8Slice("aa"), Slices.EMPTY_SLICE, Slices.EMPTY_SLICE);
        assertThat(regexp.split(source)).containsExactly(Slices.EMPTY_SLICE, Slices.EMPTY_SLICE, utf8Slice("b"), Slices.EMPTY_SLICE);
        assertThat(regexp.replace(source, utf8Slice("_"))).isEqualTo(utf8Slice("__b_"));
    }

    @Test
    public void testGreedyNullableRepetitionMatchesPinnedJoni()
    {
        for (String pattern : new String[] {
                "(?:a?|b)*",
                "(?:a?|b)+",
                "(?:a?|b){0,}",
                "(?:a?|b){1,}",
                "(?:a?|b){2,}",
                "((?:a?|b)*)",
                "(?:(a?)|(b))*",
                "(?:a?|b)*b",
                "(?:(?:a?|b)*)*",
                "(?:a?|b)*?",
                "(?:a?|b)+?",
                "(?:a?|b){1,}?",
                "(?:a?|b){0,3}",
                "(?:a?|\\b)*",
                "a(?:^)*b",
                "a(?:^)+b",
                "a((?:^)*)b",
                "(?:a|^)+b",
                "((?:a|^)+)b",
                "(?:(a)|(^))+b",
        }) {
            for (Slice input : new Slice[] {
                    utf8Slice(""),
                    utf8Slice("a"),
                    utf8Slice("ab"),
                    utf8Slice("aab"),
                    utf8Slice("b"),
                    utf8Slice("x💰aab"),
            }) {
                assertThat(regulatorMatches(pattern, input))
                        .as("%s against %s", pattern, input)
                        .isEqualTo(joniMatches(pattern, input));
            }
        }
    }

    @Test
    public void testCaptureSensitiveNullableLoopUsesBoundedProgressSemantics()
    {
        String pattern = "(?:(a??)|b)*a";
        Slice input = utf8Slice("baa");

        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(pattern));
        assertThat(regexp.extractAll(input)).containsExactly(utf8Slice("ba"), utf8Slice("a"));
        assertThat(regexp.extractAll(input, 1)).containsExactly(Slices.EMPTY_SLICE, Slices.EMPTY_SLICE);

        List<MatchSnapshot> joniMatches = joniMatches(pattern, input);
        assertThat(joniMatches).hasSize(1);
        assertThat(joniMatches.get(0).groups[0]).isEqualTo(0);
        assertThat(joniMatches.get(0).groups[1]).isEqualTo(3);
        assertThat(joniMatches.get(0).groups[2]).isEqualTo(2);
        assertThat(joniMatches.get(0).groups[3]).isEqualTo(2);
    }

    @Test
    public void testBoundedNullableRepetitionMatrixMatchesPinnedJoni()
    {
        for (String body : new String[] {
                "a?",
                "a?|b",
                "a?|ab?",
                "a?|b?",
                "a?|b?c?",
                "a?|(?:b?|c)",
                "a?|\\b",
                "a|^",
                "a|\\b",
                "(a?)|(b)",
        }) {
            for (String quantifier : new String[] {"*", "+", "{0,}", "{1,}", "{2,}"}) {
                for (String suffix : new String[] {"", "b", "c?"}) {
                    String pattern = "(?:" + body + ")" + quantifier + suffix;
                    for (Slice input : new Slice[] {
                            utf8Slice(""),
                            utf8Slice("a"),
                            utf8Slice("aa"),
                            utf8Slice("aab"),
                            utf8Slice("abb"),
                            utf8Slice("bc"),
                            utf8Slice("x"),
                            utf8Slice("éaab"),
                            Slices.wrappedBuffer(new byte[] {'a', 'a', (byte) 0xFF, 'b'}),
                    }) {
                        assertThat(regulatorMatches(pattern, input))
                                .as("%s against %s", pattern, input)
                                .isEqualTo(joniMatches(pattern, input));
                    }
                }
            }
        }
    }

    @Test
    public void testNestedPopularRepetitionsMatchPinnedJoni()
    {
        String[] quantifiers = {"?", "*", "+", "??", "*?", "+?", "{0,1}", "{0,}", "{1,}"};
        for (String innerQuantifier : quantifiers) {
            for (String outerQuantifier : quantifiers) {
                for (String pattern : new String[] {
                        "(?:(?:a?|b)" + innerQuantifier + ")" + outerQuantifier,
                        "((?:(a?|b)" + innerQuantifier + ")" + outerQuantifier + ")",
                }) {
                    for (Slice input : new Slice[] {utf8Slice(""), utf8Slice("aa"), utf8Slice("aab")}) {
                        assertThat(regulatorMatches(pattern, input))
                                .as("%s against %s", pattern, input)
                                .isEqualTo(joniMatches(pattern, input));
                    }
                }
            }
        }
    }

    @Test
    public void testNestedRepetitionReductionUsesSourceQuantifiers()
    {
        Regexp reducedReluctantQuestion = parse("(?:a+?)??");
        assertThat(reducedReluctantQuestion.op()).isEqualTo(RegexpOp.STAR);
        assertThat(reducedReluctantQuestion.parseFlags() & Regexp.NON_GREEDY).isNotZero();

        Regexp reducedPopularRepeat = parse("(?:a+?){1,}");
        assertThat(reducedPopularRepeat.op()).isEqualTo(RegexpOp.PLUS);
        assertThat(reducedPopularRepeat.parseFlags() & Regexp.NON_GREEDY).isNotZero();

        Regexp preservedCountedRepeat = parse("(?:a+?){2,}");
        assertThat(preservedCountedRepeat.op()).isEqualTo(RegexpOp.REPEAT);
        assertThat(preservedCountedRepeat.min()).isEqualTo(2);
        assertThat(preservedCountedRepeat.max()).isEqualTo(-1);
        assertThat(preservedCountedRepeat.child(0).op()).isEqualTo(RegexpOp.PLUS);

        Regexp reducedPopularChild = parse("(?:a{1,}?)+");
        assertThat(reducedPopularChild.op()).isEqualTo(RegexpOp.REPEAT);
        assertThat(reducedPopularChild.min()).isEqualTo(1);
        assertThat(reducedPopularChild.max()).isEqualTo(-1);
        assertThat(reducedPopularChild.parseFlags() & Regexp.NON_GREEDY).isNotZero();

        Regexp captureBarrier = parse("(?:(a+?))+");
        assertThat(captureBarrier.op()).isEqualTo(RegexpOp.PLUS);
        assertThat(captureBarrier.child(0).op()).isEqualTo(RegexpOp.CAPTURE);
    }

    @Test
    public void testCountedUnboundedNestedRepetitionMatchesPinnedJoni()
    {
        for (String pattern : new String[] {
                "(?:a+?){1,}",
                "(?:a+?){2,}",
                "(?:a+?){3,}",
                "(?:a+?){2}",
                "(?:a+?){2,3}",
                "(?:a+?){2,}?",
                "(?:[ab]+?){2,}",
                "(?:é+?){2,}",
        }) {
            for (Slice input : new Slice[] {utf8Slice("aaa"), utf8Slice("aaaa"), utf8Slice("aba"), utf8Slice("ééé")}) {
                assertThat(regulatorMatches(pattern, input))
                        .as("%s against %s", pattern, input)
                        .isEqualTo(joniMatches(pattern, input));
            }
        }

        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("(?:a+?){2,}"));
        Slice source = utf8Slice("aaa");
        assertThat(regexp.contains(source)).isTrue();
        assertThat(regexp.count(source)).isEqualTo(1);
        assertThat(regexp.position(source)).isEqualTo(1);
        assertThat(regexp.extract(source)).isEqualTo(utf8Slice("aaa"));
        assertThat(regexp.extractAll(source)).containsExactly(utf8Slice("aaa"));
        assertThat(regexp.split(source)).containsExactly(Slices.EMPTY_SLICE, Slices.EMPTY_SLICE);
        assertThat(regexp.replace(source, utf8Slice("_"))).isEqualTo(utf8Slice("_"));
        assertThat(regexp.replace(source, groups -> {
            assertThat(groups).isEmpty();
            return utf8Slice("_");
        })).isEqualTo(utf8Slice("_"));

        TrinoRegexp finalLineRegexp = TrinoRegexp.compile(utf8Slice("(?:a+?){2,}$"));
        assertThat(finalLineRegexp.pattern().findPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.LOWERED_PROGRAM);
        assertThat(finalLineRegexp.contains(source)).isTrue();
        assertThat(finalLineRegexp.extract(source)).isEqualTo(utf8Slice("aaa"));

        assertThat(regulatorMatches("^x(?:a+?){2,}", utf8Slice("xaaa")))
                .isEqualTo(joniMatches("^x(?:a+?){2,}", utf8Slice("xaaa")));
    }

    @Test
    public void testNullableRepetitionPreservesSliceOffsets()
    {
        Slice backing = utf8Slice("!aab?");
        Slice input = Slices.wrappedBuffer(backing.byteArray(), backing.byteArrayOffset() + 1, 3);
        assertThat(regulatorMatches("(?:(a?)|(b))*", input))
                .isEqualTo(joniMatches("(?:(a?)|(b))*", input));

        Slice regionInput = utf8Slice("!éaab?");
        assertThat(regulatorMatches("(?:(a?)|(b))*", regionInput, 3, 6))
                .isEqualTo(joniMatches("(?:(a?)|(b))*", regionInput, 3, 6));

        Slice assertionRegion = utf8Slice("!ab?");
        assertThat(regulatorMatches("a(?:\\b)*b", assertionRegion, 1, 3))
                .isEqualTo(joniMatches("a(?:\\b)*b", assertionRegion, 1, 3));
    }

    @Test
    public void testLongInputUsesLazyReverseBoundaryRecovery()
    {
        String inputText = "x".repeat(512) + "a".repeat(65_536) + "b";
        Slice input = utf8Slice(inputText);
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("((?:a?|b)*)"));
        Re2 pattern = regexp.pattern();
        assertThat(pattern.isReverseProgramComputed()).isFalse();

        Re2Matcher matcher = pattern.matcher(input);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(0);
        assertThat(matcher.end()).isEqualTo(0);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(1);

        matcher.reset(input, 512, input.length());
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(512);
        assertThat(matcher.end()).isEqualTo(512 + 65_536);
        assertThat(matcher.start(1)).isEqualTo(512);
        assertThat(matcher.end(1)).isEqualTo(512 + 65_536);
        assertThat(pattern.reverseProgramSize()).isPositive();
        assertThat(pattern.isReverseProgramComputed()).isTrue();
        assertThat(Dfa.search(
                pattern.reverseProgramIfComputedForDiagnostics(),
                input,
                512,
                input.length(),
                512,
                512 + 65_536,
                true,
                Prog.MatchKind.LONGEST_MATCH,
                true))
                .isZero();
        assertThat(regulatorMatches("((?:a?|b)*)", input, 512, input.length()))
                .isEqualTo(joniMatches("((?:a?|b)*)", input, 512, input.length()));
    }

    @Test
    public void testNestedNullableLoopGrowthIsBounded()
    {
        int previousSize = 0;
        String pattern = "(?:a?|b)";
        for (int depth = 0; depth < 24; depth++) {
            // The optional branch prevents simplification from collapsing the nested loops. Each
            // loop may clone its epsilon-reachable prefix, but never the post-consumption graph.
            pattern = "(?:(?:" + pattern + ")*|c?)";
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(pattern));
            int size = regexp.pattern().programSize();
            assertThat(size).isLessThan((depth + 8) * (depth + 8));
            assertThat(size).isGreaterThanOrEqualTo(previousSize);
            previousSize = size;
        }
    }

    private static List<MatchSnapshot> regulatorMatches(String pattern, Slice input)
    {
        return regulatorMatches(pattern, input, 0, input.length());
    }

    private static Regexp parse(String pattern)
    {
        return TrinoRegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();
    }

    private static List<MatchSnapshot> regulatorMatches(String pattern, Slice input, int start, int end)
    {
        Re2Matcher matcher = TrinoRegexp.compile(utf8Slice(pattern)).pattern().matcher(input);
        matcher.reset(input, start, end);
        List<MatchSnapshot> matches = new ArrayList<>();
        while (matcher.find()) {
            int[] groups = new int[(matcher.groupCount() + 1) * 2];
            for (int group = 0; group <= matcher.groupCount(); group++) {
                groups[group * 2] = matcher.start(group);
                groups[group * 2 + 1] = matcher.end(group);
            }
            matches.add(new MatchSnapshot(groups));
        }
        return matches;
    }

    private static List<MatchSnapshot> joniMatches(String pattern, Slice input)
    {
        return joniMatches(pattern, input, 0, input.length());
    }

    private static List<MatchSnapshot> joniMatches(String pattern, Slice input, int regionStart, int regionEnd)
    {
        byte[] patternBytes = utf8Slice(pattern).getBytes();
        Regex regex = new Regex(
                patternBytes,
                0,
                patternBytes.length,
                Option.DEFAULT,
                NonStrictUTF8Encoding.INSTANCE,
                Syntax.Java,
                _ -> {});
        byte[] inputBytes = input.getBytes();
        Matcher matcher = regex.matcher(inputBytes);
        List<MatchSnapshot> matches = new ArrayList<>();
        int start = regionStart;
        while (start <= regionEnd && matcher.search(start, regionEnd, Option.DEFAULT) >= 0) {
            Region region = matcher.getRegion();
            int[] groups;
            if (region == null) {
                groups = new int[] {matcher.getBegin(), matcher.getEnd()};
            }
            else {
                groups = new int[region.numRegs * 2];
                for (int group = 0; group < region.numRegs; group++) {
                    groups[group * 2] = region.beg[group];
                    groups[group * 2 + 1] = region.end[group];
                }
            }
            matches.add(new MatchSnapshot(groups));
            int matchStart = matcher.getBegin();
            int matchEnd = matcher.getEnd();
            if (matchStart != matchEnd) {
                start = matchEnd;
            }
            else if (matchEnd == regionEnd) {
                break;
            }
            else {
                start = matchEnd + Utf8.decodedWidth(Utf8.decode(inputBytes, matchEnd, inputBytes.length));
            }
        }
        return matches;
    }

    private record MatchSnapshot(int[] groups)
    {
        private MatchSnapshot
        {
            groups = groups.clone();
        }

        @Override
        public boolean equals(Object object)
        {
            return object instanceof MatchSnapshot other && Arrays.equals(groups, other.groups);
        }

        @Override
        public int hashCode()
        {
            return Arrays.hashCode(groups);
        }

        @Override
        public String toString()
        {
            return Arrays.toString(groups);
        }
    }
}
