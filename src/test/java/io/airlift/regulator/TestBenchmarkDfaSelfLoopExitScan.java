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

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkDfaSelfLoopExitScan
{
    private static final String LARGE_ALTERNATIVE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".repeat(10);

    @Test
    public void testSearchResultsAndVectorRoute()
    {
        for (int exitByteCount = 1; exitByteCount <= 3; exitByteCount++) {
            for (BenchmarkDfaSelfLoopExitScan.InputShape inputShape : BenchmarkDfaSelfLoopExitScan.InputShape.values()) {
                BenchmarkDfaSelfLoopExitScan benchmark = new BenchmarkDfaSelfLoopExitScan();
                benchmark.exitByteCount = exitByteCount;
                benchmark.inputShape = inputShape;
                benchmark.sourceLength = 128 * 1024;
                benchmark.setup();

                assertThat(benchmark.search())
                        .as("exitByteCount=%s inputShape=%s", exitByteCount, inputShape)
                        .isEqualTo(benchmark.expectedResult());
                assertThat(benchmark.selfLoopExitByteScanCount())
                        .as("exitByteCount=%s inputShape=%s", exitByteCount, inputShape)
                        .matches(count -> (count > 0) == inputShape.vectorRoute());
                if (inputShape.vectorRoute()) {
                    assertThat(benchmark.hasExpectedExitByteSet())
                            .as("exitByteCount=%s inputShape=%s", exitByteCount, inputShape)
                            .isTrue();
                }
            }
        }
    }

    @Test
    public void testCacheResetRebuildsExitPlan()
    {
        BenchmarkDfaSelfLoopExitScan benchmark = new BenchmarkDfaSelfLoopExitScan();
        benchmark.exitByteCount = 3;
        benchmark.inputShape = BenchmarkDfaSelfLoopExitScan.InputShape.LATE_MATCH;
        benchmark.sourceLength = 128 * 1024;
        benchmark.setup();

        int scanCount = benchmark.selfLoopExitByteScanCount();
        benchmark.resetDfa();

        assertThat(benchmark.search()).isEqualTo(benchmark.expectedResult());
        assertThat(benchmark.selfLoopExitByteScanCount()).isEqualTo(scanCount);
        assertThat(benchmark.search()).isEqualTo(benchmark.expectedResult());
        assertThat(benchmark.selfLoopExitByteScanCount()).isGreaterThan(scanCount);
    }

    @Test
    public void testMalformedUtf8AndSliceOffset()
    {
        for (int exitByteCount = 1; exitByteCount <= 3; exitByteCount++) {
            String exitBytes = "xyz".substring(0, exitByteCount);
            String exitExpression = exitByteCount == 1 ? exitBytes : "[" + exitBytes + "]";
            Prog program = Re2BenchmarkRunner.compileProg("^(?:\\C*" + exitExpression + "|" + LARGE_ALTERNATIVE + ")$");

            byte[] warmup = new byte[1536];
            Arrays.fill(warmup, (byte) 0x80);
            warmup[warmup.length - 1] = 'x';
            assertThat(Dfa.search(program, Slices.wrappedBuffer(warmup), false, Prog.MatchKind.FIRST_MATCH, true))
                    .isEqualTo(warmup.length);

            byte[] backing = new byte[(128 * 1024) + 30];
            Arrays.fill(backing, (byte) 0x80);
            Slice input = Slices.wrappedBuffer(backing, 17, 128 * 1024);
            for (boolean match : new boolean[] {false, true}) {
                backing[input.byteArrayOffset() + input.length() - 1] = match ? (byte) 'x' : (byte) 0x80;
                long expected = match ? input.length() : Dfa.SEARCH_NO_MATCH;
                assertThat(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(expected);
                assertThat(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(expected);
            }
            assertThat(program.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH).selfLoopExitByteScanCount()).isPositive();
        }
    }
}
