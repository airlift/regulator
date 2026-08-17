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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkDfaCache
{
    @Test
    public void testSharedWarmParameters()
    {
        BenchmarkDfaCache benchmark = new BenchmarkDfaCache();
        for (int textSize : new int[] {8, 262_144}) {
            BenchmarkDfaCache.SharedWarmState state = new BenchmarkDfaCache.SharedWarmState();
            state.textSize = textSize;
            state.setup();
            assertThat(benchmark.searchSharedWarm(state)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        }
    }

    @Test
    public void testColdStartParameters()
    {
        BenchmarkDfaCache benchmark = new BenchmarkDfaCache();
        for (int textSize : new int[] {8, 262_144}) {
            BenchmarkDfaCache.ColdStartState state = new BenchmarkDfaCache.ColdStartState();
            state.textSize = textSize;
            state.setup();
            state.resetCache();
            assertThat(benchmark.searchColdStart(state)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        }
    }

    @Test
    public void testSharedColdParameters()
            throws InterruptedException
    {
        BenchmarkDfaCache benchmark = new BenchmarkDfaCache();
        for (int textSize : new int[] {8, 262_144}) {
            for (int workerCount : new int[] {1, 2, 4, 8, 16}) {
                BenchmarkDfaCache.SharedColdState state = new BenchmarkDfaCache.SharedColdState();
                state.textSize = textSize;
                state.workerCount = workerCount;
                state.setup();
                try {
                    state.resetCache();
                    assertThat(benchmark.searchSharedColdWave(state)).isEqualTo(-workerCount);
                }
                finally {
                    state.tearDown();
                }
            }
        }
    }

    @Test
    public void testLateTransitionParameters()
    {
        BenchmarkDfaCache benchmark = new BenchmarkDfaCache();
        for (int textSize : new int[] {8, 4_096, 262_144}) {
            BenchmarkDfaCache.LateTransitionState state = new BenchmarkDfaCache.LateTransitionState();
            state.textSize = textSize;
            state.setup();
            state.prepareKnownPath();
            assertThat(benchmark.searchNewTransitionAfterLongScan(state)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        }
    }

    @Test
    public void testPublicColdDfaParameters()
    {
        BenchmarkDfaCache benchmark = new BenchmarkDfaCache();
        for (int textSize : new int[] {1_538, 16_384}) {
            int expectedStateCount = textSize == 1_538 ? 1_536 : 3_980;
            int[] expectedMatchBounds = textSize == 1_538 ? new int[] {-1, -1} : new int[] {3_906, 3_988};
            BenchmarkDfaCache.PublicColdDfaState state = new BenchmarkDfaCache.PublicColdDfaState();
            state.textSize = textSize;
            state.setup();
            assertThat(state.matchBounds()).containsExactly(expectedMatchBounds);
            state.resetCache();
            int resetCount = state.resetCount();
            assertThat(benchmark.searchPublicColdDfa(state))
                    .as("textSize %s", textSize)
                    .isEqualTo(textSize == 16_384);
            assertThat(state.resetCount())
                    .as("textSize %s must complete without DFA cache thrashing", textSize)
                    .isEqualTo(resetCount);
            Dfa.CacheSnapshot cache = state.cacheSnapshot();
            assertThat(cache.stateCount()).as("textSize %s state count", textSize).isEqualTo(expectedStateCount);
            assertThat(cache.cacheEntries()).as("textSize %s cache entries", textSize).isEqualTo(expectedStateCount);
            assertThat(cache.populatedStateData()).as("textSize %s state data", textSize).isEqualTo(expectedStateCount);
            assertThat(cache.populatedStateReferences()).as("textSize %s state references", textSize).isEqualTo(expectedStateCount);
            assertThat(cache.pairedRows()).as("textSize %s paired rows", textSize).isZero();
        }
    }

    @Test
    public void testPopulatedResetParameters()
    {
        BenchmarkDfaCache benchmark = new BenchmarkDfaCache();
        for (int textSize : new int[] {1_538, 16_384}) {
            BenchmarkDfaCache.PopulatedResetState state = new BenchmarkDfaCache.PopulatedResetState();
            state.textSize = textSize;
            state.setup();
            state.populateCache();
            Dfa.CacheSnapshot before = state.cacheSnapshot();
            int resetCount = state.resetCount();
            assertThat(benchmark.resetPopulatedCache(state)).isPositive();
            Dfa.CacheSnapshot after = state.cacheSnapshot();
            assertThat(before.cacheEntries()).isEqualTo(textSize == 1_538 ? 1_536 : 3_980);
            assertThat(after.stateCount()).isZero();
            assertThat(after.cacheEntries()).isZero();
            assertThat(after.populatedStateData()).isZero();
            assertThat(after.populatedStateReferences()).isZero();
            assertThat(after.populatedStartStates()).isZero();
            assertThat(after.resetCount()).isEqualTo(resetCount + 1);
        }
    }

    @Test
    public void testNextInsertGrowthParameters()
    {
        BenchmarkDfaCache benchmark = new BenchmarkDfaCache();
        BenchmarkDfaCache.NextInsertGrowthState state = new BenchmarkDfaCache.NextInsertGrowthState();
        state.setup();
        state.prepareNextInsertGrowth();
        Dfa.CacheSnapshot beforeCache = state.cacheSnapshot();
        Dfa.StateMemorySnapshot beforeMemory = state.memorySnapshot();
        Dfa.FailureAtomicitySnapshot beforeAtomicity = state.dfaForTesting().failureAtomicitySnapshot();
        assertThat(benchmark.growStateCacheOnNextInsert(state)).isFalse();
        Dfa.CacheSnapshot afterCache = state.cacheSnapshot();
        Dfa.StateMemorySnapshot afterMemory = state.memorySnapshot();
        Dfa.FailureAtomicitySnapshot afterAtomicity = state.dfaForTesting().failureAtomicitySnapshot();

        assertThat(beforeCache.cacheEntries()).isEqualTo(1_536);
        assertThat(beforeMemory.cacheTableCapacity()).isEqualTo(2_048);
        assertThat(afterCache.stateCount()).isEqualTo(beforeCache.stateCount() + 1);
        assertThat(afterCache.cacheEntries()).isEqualTo(beforeCache.cacheEntries() + 1);
        assertThat(afterMemory.cacheTableCapacity()).isEqualTo(beforeMemory.cacheTableCapacity() * 2);
        assertThat(afterCache.resetCount()).isEqualTo(beforeCache.resetCount());
        assertThat(afterAtomicity.cacheStorage()).isNotSameAs(beforeAtomicity.cacheStorage());
        assertThat(afterMemory.totalBytes() - beforeMemory.totalBytes())
                .isEqualTo(beforeCache.availableStateMemory() - afterCache.availableStateMemory());
        assertThat(state.retainedStateMemory()).isEqualTo(afterMemory.totalBytes());
        assertThat(afterCache.availableStateMemory() +
                state.retainedStateMemory() +
                afterCache.pairedTransitionMemory() +
                state.absolutePointerTransitionMemory())
                .isEqualTo(afterCache.stateBudget());

        state.prepareNextInsertGrowth();
        assertThat(state.cacheSnapshot().cacheEntries()).isEqualTo(1_536);
        assertThat(state.memorySnapshot().cacheTableCapacity()).isEqualTo(2_048);
        assertThat(benchmark.growStateCacheOnNextInsert(state)).isFalse();
        assertThat(state.cacheSnapshot().cacheEntries()).isEqualTo(1_537);
        assertThat(state.memorySnapshot().cacheTableCapacity()).isEqualTo(4_096);
    }
}
