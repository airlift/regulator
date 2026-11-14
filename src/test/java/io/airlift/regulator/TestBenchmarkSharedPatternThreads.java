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

import static io.airlift.regulator.BenchmarkSharedPatternThreads.COLD_PATTERN;
import static io.airlift.regulator.BenchmarkSharedPatternThreads.THRASHING_COUNT;
import static io.airlift.regulator.BenchmarkSharedPatternThreads.stateGrowthInput;
import static io.airlift.regulator.BenchmarkSharedPatternThreads.wordText;
import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkSharedPatternThreads
{
    @Test
    public void testWarmParameters()
    {
        BenchmarkSharedPatternThreads benchmark = new BenchmarkSharedPatternThreads();
        Slice firstShortRow = Slices.wrappedBuffer(wordText(48, 1));
        Slice longRow = Slices.wrappedBuffer(wordText(65_536, 2));
        for (String pattern : new String[] {"[A-Z][a-z]+ [A-Z][a-z]+", "(?i)sherlock\\s+\\w+", "(\\w+)\\s+(\\w+)", "\\b\\w+n\\b", "Sherlock|Watson|London"}) {
            Re2 expected = Re2.compile(Slices.utf8Slice(pattern));
            boolean expectedFind = expected.matcher(firstShortRow).find();
            long expectedCount = expected.count(longRow);
            assertThat(expectedCount).as(pattern).isPositive();

            BenchmarkSharedPatternThreads.SharedWarmPattern shared = new BenchmarkSharedPatternThreads.SharedWarmPattern();
            shared.pattern = pattern;
            shared.setup();
            BenchmarkSharedPatternThreads.SharedWarmMatcher sharedMatcher = new BenchmarkSharedPatternThreads.SharedWarmMatcher();
            sharedMatcher.setup(shared);
            assertThat(benchmark.findShortShared(sharedMatcher)).as(pattern).isEqualTo(expectedFind);
            assertThat(benchmark.countLongShared(shared)).as(pattern).isEqualTo(expectedCount);

            BenchmarkSharedPatternThreads.PerThreadWarmPattern perThread = new BenchmarkSharedPatternThreads.PerThreadWarmPattern();
            perThread.pattern = pattern;
            perThread.setup();
            BenchmarkSharedPatternThreads.PerThreadWarmMatcher perThreadMatcher = new BenchmarkSharedPatternThreads.PerThreadWarmMatcher();
            perThreadMatcher.setup(perThread);
            assertThat(benchmark.findShortPerThread(perThreadMatcher)).as(pattern).isEqualTo(expectedFind);
            assertThat(benchmark.countLongPerThread(perThread)).as(pattern).isEqualTo(expectedCount);
        }
    }

    @Test
    public void testColdWaveParameters()
            throws InterruptedException
    {
        BenchmarkSharedPatternThreads benchmark = new BenchmarkSharedPatternThreads();
        Re2 expected = Re2.compile(Slices.utf8Slice(COLD_PATTERN));
        for (int workerCount : new int[] {1, 8}) {
            for (BenchmarkSharedPatternThreads.ColdRows rows : BenchmarkSharedPatternThreads.ColdRows.values()) {
                for (BenchmarkSharedPatternThreads.PatternSharing sharing : BenchmarkSharedPatternThreads.PatternSharing.values()) {
                    BenchmarkSharedPatternThreads.ColdWaveState state = new BenchmarkSharedPatternThreads.ColdWaveState();
                    state.workerCount = workerCount;
                    state.rows = rows;
                    state.sharing = sharing;
                    state.setup();
                    try {
                        long expectedChecksum = 0;
                        for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
                            long seed = rows == BenchmarkSharedPatternThreads.ColdRows.IDENTICAL ? 42 : 42 + workerIndex;
                            expectedChecksum += expected.count(Slices.wrappedBuffer(stateGrowthInput(16_384, seed)));
                        }
                        state.compilePatterns();
                        assertThat(benchmark.countColdWave(state))
                                .as("workerCount %s, rows %s, sharing %s", workerCount, rows, sharing)
                                .isEqualTo(expectedChecksum);
                        // One row's states fit the default budget, so a pattern that counts one row,
                        // or identical rows, finishes the wave without a reset. Distinct rows share
                        // few states, and on one shared pattern their states together outgrow it.
                        if (workerCount == 1 || rows == BenchmarkSharedPatternThreads.ColdRows.IDENTICAL || sharing == BenchmarkSharedPatternThreads.PatternSharing.PER_WORKER) {
                            for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
                                Dfa.DfaInstance dfa = state.workerPattern(workerIndex).forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH);
                                assertThat(dfa).isNotNull();
                                assertThat(dfa.resetCount())
                                        .as("workerCount %s, rows %s, sharing %s, worker %s", workerCount, rows, sharing, workerIndex)
                                        .isZero();
                            }
                        }
                    }
                    finally {
                        state.tearDown();
                    }
                }
            }
        }
    }

    @Test
    public void testThrashingParameters()
    {
        BenchmarkSharedPatternThreads benchmark = new BenchmarkSharedPatternThreads();
        // Thrashing must not change results: they equal counts on a budget too large to reset.
        Re2 expected = Re2.compile(Slices.utf8Slice(THRASHING_COUNT), Re2.Options.defaults().setMaxMemory(1L << 30));
        byte[] input = stateGrowthInput(1 << 20, 42);
        long expectedThrashCount = expected.count(Slices.wrappedBuffer(input));
        long expectedHealthyCount = expected.count(Slices.wrappedBuffer(input, 0, 1024));

        BenchmarkSharedPatternThreads.SharedThrashingPattern shared = new BenchmarkSharedPatternThreads.SharedThrashingPattern();
        shared.setup();
        // The setup's count of the thrashing row has already reset the DFA.
        assertThat(shared.pattern().forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).resetCount()).isPositive();
        assertThat(benchmark.countThrashRowShared(shared)).isEqualTo(expectedThrashCount);
        assertThat(benchmark.countHealthyRowShared(shared)).isEqualTo(expectedHealthyCount);
        assertThat(benchmark.countThrashRowInMixedShared(shared)).isEqualTo(expectedThrashCount);
        assertThat(benchmark.countHealthyRowInMixedShared(shared)).isEqualTo(expectedHealthyCount);

        BenchmarkSharedPatternThreads.PerThreadThrashingPattern perThread = new BenchmarkSharedPatternThreads.PerThreadThrashingPattern();
        perThread.setup();
        assertThat(perThread.pattern().forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).resetCount()).isPositive();
        assertThat(benchmark.countThrashRowPerThread(perThread)).isEqualTo(expectedThrashCount);
        assertThat(benchmark.countHealthyRowPerThread(perThread)).isEqualTo(expectedHealthyCount);
        assertThat(benchmark.countThrashRowInMixedPerThread(perThread)).isEqualTo(expectedThrashCount);
        assertThat(benchmark.countHealthyRowInMixedPerThread(perThread)).isEqualTo(expectedHealthyCount);
    }
}
