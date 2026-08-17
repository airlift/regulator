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

public final class DfaCacheQualificationProbe
{
    private DfaCacheQualificationProbe() {}

    public static void main(String[] args)
    {
        BenchmarkDfaCache benchmark = new BenchmarkDfaCache();
        System.out.println("scenario\tstate_count\tcache_entries\tpaired_rows\tpaired_bytes\tavailable_state_bytes\tfixed_candidate_bytes\ttotal_bytes\ttransition_bytes\tstate_metadata_bytes\tcache_bytes\tcache_capacity\treset_count");

        for (int textSize : new int[] {1_538, 16_384}) {
            BenchmarkDfaCache.PopulatedResetState state = new BenchmarkDfaCache.PopulatedResetState();
            state.textSize = textSize;
            state.setup();
            state.populateCache();
            print("populated-" + textSize, state.cacheSnapshot(), state.memorySnapshot());
            benchmark.resetPopulatedCache(state);
            print("reset-" + textSize, state.cacheSnapshot(), state.memorySnapshot());
        }

        BenchmarkDfaCache.NextInsertGrowthState growthState = new BenchmarkDfaCache.NextInsertGrowthState();
        growthState.setup();
        growthState.prepareNextInsertGrowth();
        print("before-next-insert-growth", growthState.cacheSnapshot(), growthState.memorySnapshot());
        benchmark.growStateCacheOnNextInsert(growthState);
        print("after-next-insert-growth", growthState.cacheSnapshot(), growthState.memorySnapshot());
    }

    private static void print(String scenario, Dfa.CacheSnapshot cacheSnapshot, Dfa.StateMemorySnapshot memorySnapshot)
    {
        System.out.printf(
                "%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d%n",
                scenario,
                cacheSnapshot.stateCount(),
                cacheSnapshot.cacheEntries(),
                cacheSnapshot.pairedRows(),
                cacheSnapshot.pairedTransitionMemory(),
                cacheSnapshot.availableStateMemory(),
                cacheSnapshot.fixedDistanceByteCandidateMemory(),
                memorySnapshot.totalBytes(),
                memorySnapshot.transitionBytes(),
                memorySnapshot.stateMetadataBytes(),
                memorySnapshot.cacheBytes(),
                memorySnapshot.cacheTableCapacity(),
                cacheSnapshot.resetCount());
    }
}
