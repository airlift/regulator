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

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.airlift.regulator.Dfa.DfaInstance.Kind.FIRST_MATCH;
import static io.airlift.regulator.Dfa.DfaInstance.Kind.LONGEST_MATCH;
import static io.airlift.regulator.Dfa.DfaInstance.Kind.MANY_MATCH;
import static io.airlift.slice.Slices.utf8Slice;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestDfaStartStateFailure
{
    @Test
    public void testSingleStartStateFailureResetsAndRetriesEveryContext()
    {
        for (boolean latin1 : new boolean[] {false, true}) {
            assertForwardContextRetries(latin1, utf8Slice("a"), 0, 1);
            assertForwardContextRetries(latin1, utf8Slice("\na"), 1, 2);
            assertForwardContextRetries(latin1, utf8Slice("xa"), 1, 2);
            assertForwardContextRetries(latin1, utf8Slice(".a"), 1, 2);

            assertReverseContextRetries(latin1, utf8Slice("a"), 0, 1);
            assertReverseContextRetries(latin1, utf8Slice("a\n"), 0, 1);
            assertReverseContextRetries(latin1, utf8Slice("ax"), 0, 1);
            assertReverseContextRetries(latin1, utf8Slice("a."), 0, 1);
        }
    }

    @Test
    public void testSingleUnanchoredStartStateFailureResetsAndRetriesEveryContext()
    {
        for (boolean latin1 : new boolean[] {false, true}) {
            assertForwardUnanchoredContextRetries(latin1, utf8Slice("a"), 0, 1);
            assertForwardUnanchoredContextRetries(latin1, utf8Slice("\na"), 1, 2);
            assertForwardUnanchoredContextRetries(latin1, utf8Slice("xa"), 1, 2);
            assertForwardUnanchoredContextRetries(latin1, utf8Slice(".a"), 1, 2);

            assertReverseUnanchoredContextRetries(latin1, utf8Slice("a"), 0, 1);
            assertReverseUnanchoredContextRetries(latin1, utf8Slice("a\n"), 0, 1);
            assertReverseUnanchoredContextRetries(latin1, utf8Slice("ax"), 0, 1);
            assertReverseUnanchoredContextRetries(latin1, utf8Slice("a."), 0, 1);
        }
    }

    @Test
    public void testSecondStartStateFailureUsesGeneralFallback()
    {
        Prog program = compile("a", false, false);
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        dfa.failStateAllocationsForTesting(2);

        assertThat(Dfa.search(program, utf8Slice("a"), true, Prog.MatchKind.FIRST_MATCH, true))
                .isEqualTo(Dfa.SEARCH_FAILED);
        assertDfaReusable(program, dfa, utf8Slice("a"));
    }

    @Test
    public void testSecondStartStateFailureUsesCountFallback()
    {
        Prog program = compile("[a-z]+", false, false);
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        dfa.failStateAllocationsForTesting(2);

        assertThat(Dfa.countMatches(program, utf8Slice("abc"), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(Dfa.COUNT_UNSUPPORTED);
        assertDfaReusable(program, dfa, utf8Slice("abc"));
    }

    @Test
    public void testSecondStartStateFailureUsesGroupZeroFallback()
    {
        Re2 pattern = Re2.compile(utf8Slice("[a-z]+"));
        Dfa.GroupZeroForwardCursor cursor = pattern.createGroupZeroForwardCursor();
        Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics().getCachedDfa(FIRST_MATCH);
        dfa.failStateAllocationsForTesting(2);

        assertThat(Dfa.searchGroupZeroForward(cursor, utf8Slice("abc"), 0, 3, 0))
                .isEqualTo(Dfa.SEARCH_FAILED);
        assertThat(pattern.matcher(utf8Slice("abc")).find()).isTrue();
    }

    @Test
    public void testSecondStartStateFailureUsesCandidateFallback()
    {
        Re2 pattern = Re2.compile(utf8Slice("a+z|b+"));
        Dfa.CandidateStartCursor cursor = pattern.createCandidateStartCursor();
        Slice input = utf8Slice("xxaaaz");
        cursor.reset(input.length());
        Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics().getCachedDfa(FIRST_MATCH);
        dfa.failStateAllocationsForTesting(2);

        assertThat(Dfa.searchCandidateBoundary(cursor, input, 0, input.length(), 0))
                .isEqualTo(Dfa.CANDIDATE_SEARCH_FALLBACK);
        assertThat(cursor.enabled()).isFalse();
        assertThat(pattern.matcher(input).find()).isTrue();
    }

    @Test
    public void testSecondStartStateFailureUsesManyMatchFallback()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);
        builder.add(utf8Slice("a"));
        builder.add(utf8Slice("b"));
        Re2Set set = builder.build();
        Dfa.DfaInstance dfa = set.dfaForDiagnostics();
        dfa.failStateAllocationsForTesting(2);

        assertThatThrownBy(() -> set.matchesAny(utf8Slice("a")))
                .isInstanceOf(RegexpMatchMemoryLimitException.class);
        assertThat(dfa.cacheMutationLockedForTesting()).isFalse();
        assertThat(set.matchesAny(utf8Slice("a"))).isTrue();
    }

    @Test
    public void testSecondStartStateFailureUsesResultCollectingManyMatchFallback()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);
        builder.add(utf8Slice("a"));
        builder.add(utf8Slice("b"));
        Re2Set set = builder.build();
        Dfa.DfaInstance dfa = set.dfaForDiagnostics();
        dfa.failStateAllocationsForTesting(2);

        assertThatThrownBy(() -> set.matchingPatternIds(utf8Slice("ab")))
                .isInstanceOf(RegexpMatchMemoryLimitException.class);
        assertThat(dfa.cacheMutationLockedForTesting()).isFalse();
        assertThat(set.matchingPatternIds(utf8Slice("ab"))).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    public void testResetWaitsForStartStateRetry()
            throws Exception
    {
        Prog program = compile("a", false, false);
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        Slice input = utf8Slice("a");
        CountDownLatch resetStarted = new CountDownLatch(1);
        FutureTask<Void> reset = new FutureTask<>(() -> {
            resetStarted.countDown();
            dfa.resetCacheExternal();
            return null;
        });
        dfa.beginSearch(true);
        try {
            Thread.ofPlatform().daemon().start(reset);
            assertThat(resetStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reset.isDone()).isFalse();

            dfa.failStateAllocationsForTesting(1);
            Dfa.StateData start = dfa.analyzeStart(input, 0, input.length(), true, true);
            assertThat(start.offset()).isPositive();
            assertThat(reset.isDone()).isFalse();
        }
        finally {
            // Release before waiting for the reset worker, including on an assertion failure.
            dfa.endSearch(true, null);
        }
        reset.get(10, TimeUnit.SECONDS);

        assertThat(Dfa.search(program, input, true, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(1);
    }

    @Test
    public void testSecondStartStateFailureUsesTaggedAlternationFallback()
    {
        Re2 pattern = Re2.compile(utf8Slice(largeAlternation("a", "ab", ".")));
        Prog taggedProgram = pattern.taggedAlternationProgramForDiagnostics();
        Dfa.DfaInstance dfa = taggedProgram.cachedDfaIfPresent(MANY_MATCH);
        dfa.failStateAllocationsForTesting(2);

        assertThat(Dfa.searchManyFull(taggedProgram, utf8Slice("a"), 0, 1)).isEqualTo(-1);
        Re2Matcher matcher = pattern.matcher(utf8Slice("a"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isEqualTo(1);
    }

    private static void assertForwardContextRetries(boolean latin1, Slice context, int start, int end)
    {
        Prog program = compile("a", latin1, false);
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        dfa.failStateAllocationsForTesting(1);
        int resetCount = dfa.resetCount();

        assertThat(Dfa.search(program, context, 0, context.length(), start, end, true, Prog.MatchKind.FIRST_MATCH, true))
                .isEqualTo(1);
        assertThat(dfa.resetCount()).isEqualTo(resetCount + 1);
    }

    private static void assertReverseContextRetries(boolean latin1, Slice context, int start, int end)
    {
        Prog program = compile("a", latin1, true);
        Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);
        dfa.failStateAllocationsForTesting(1);
        int resetCount = dfa.resetCount();

        assertThat(Dfa.search(program, context, 0, context.length(), start, end, true, Prog.MatchKind.LONGEST_MATCH, true))
                .isZero();
        assertThat(dfa.resetCount()).isEqualTo(resetCount + 1);
    }

    private static void assertForwardUnanchoredContextRetries(boolean latin1, Slice context, int start, int end)
    {
        Prog program = compile("a", latin1, false);
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        dfa.failStateAllocationsForTesting(1);
        int resetCount = dfa.resetCount();

        assertThat(Dfa.search(program, context, 0, context.length(), start, end, false, Prog.MatchKind.FIRST_MATCH, true))
                .isEqualTo(1);
        assertThat(dfa.resetCount()).isEqualTo(resetCount + 1);
    }

    private static void assertReverseUnanchoredContextRetries(boolean latin1, Slice context, int start, int end)
    {
        Prog program = compile("a", latin1, true);
        Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);
        dfa.failStateAllocationsForTesting(1);
        int resetCount = dfa.resetCount();

        assertThat(Dfa.search(program, context, 0, context.length(), start, end, false, Prog.MatchKind.LONGEST_MATCH, true))
                .isZero();
        assertThat(dfa.resetCount()).isEqualTo(resetCount + 1);
    }

    private static void assertDfaReusable(Prog program, Dfa.DfaInstance dfa, Slice input)
    {
        assertThat(Dfa.search(program, input, true, Prog.MatchKind.FIRST_MATCH, true)).isPositive();
        dfa.resetCacheExternal();
        assertThat(Dfa.search(program, input, true, Prog.MatchKind.FIRST_MATCH, true)).isPositive();
    }

    private static Prog compile(String pattern, boolean latin1, boolean reversed)
    {
        int flags = Regexp.LIKE_PERL | (latin1 ? Regexp.LATIN1 : 0);
        ParseResult parsed = RegexpParser.parse(utf8Slice(pattern), flags);
        Prog program = Compiler.compile(parsed.regexp(), reversed, 0);
        program.setDfaMemory(64L << 20);
        return program;
    }

    private static String largeAlternation(String... leadingBranches)
    {
        return Stream.concat(
                        Arrays.stream(leadingBranches),
                        IntStream.range(0, 90).mapToObj(index -> "unreachable" + index + "z"))
                .map(branch -> "(" + branch + ")")
                .collect(joining("|"));
    }
}
