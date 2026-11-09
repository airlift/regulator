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

import static io.airlift.regulator.TestingAllocations.MAXIMUM_WINDOW_RUNS;
import static io.airlift.regulator.TestingAllocations.assertAllocatesAtMostOnceCompiled;

/// Counts rebar's bounded-strings-ending-z pattern over a 1 MiB row in a fresh JVM for
/// [TestRe2Allocations#testSuffixBoundCountAllocatesOnlyFallbackPredicate], and fails unless two
/// runs of counts in a row each allocate at most the inner literal count's fallback predicate per
/// count. It first prints whether this JVM takes the SVE_128 candidate test.
public final class SuffixBoundCountAllocationProbe
{
    private static final int COUNTS_PER_RUN = 100;

    private SuffixBoundCountAllocationProbe() {}

    public static void main(String[] arguments)
    {
        System.out.println("SVE_128 " + VectorSupport.SVE_128);
        StringBuilder row = new StringBuilder();
        while (row.length() < 600) {
            row.append("lorem ipsum, dolor sit amet\n");
        }
        row.append("a,zeal\n");
        while (row.length() < 1 << 20) {
            row.append("lorem ipsum, dolor sit amet\n");
        }
        Slice source = Slices.utf8Slice(row.toString());
        Re2 pattern = Re2.compile(Slices.utf8Slice("(.*?,){13}z"));
        check(pattern.requiredBytesForDiagnostics().boundsSearchEnd(), "the pattern takes no search-end bound");

        for (int iteration = 0; iteration < 200; iteration++) {
            check(pattern.count(source) == 0, "expected no matches during warm-up");
        }

        assertAllocatesAtMostOnceCompiled("testSuffixBoundCountAllocatesOnlyFallbackPredicate", MAXIMUM_WINDOW_RUNS, 0, COUNTS_PER_RUN * 24L, () -> {
            long matchCount = 0;
            for (int iteration = 0; iteration < COUNTS_PER_RUN; iteration++) {
                matchCount += pattern.count(source);
            }
            return matchCount;
        });
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
