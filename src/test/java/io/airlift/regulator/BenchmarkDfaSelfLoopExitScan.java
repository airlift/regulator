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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Dfa.SEARCH_NO_MATCH;
import static io.airlift.regulator.Re2BenchmarkRunner.compileProg;

/// Measures complete DFA searches through a stable self-loop with one to three exit bytes. After
/// the first exit byte, the dense shapes loop on the bytes they stay on, which the stay-range scan
/// skips.
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 7, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkDfaSelfLoopExitScan
{
    private static final String LARGE_ALTERNATIVE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".repeat(10);

    @Param({"1", "2", "3"})
    int exitByteCount;

    @Param({"ABSENT", "LATE_MATCH", "SPARSE_FALSE_POSITIVE", "DENSE_FALSE_POSITIVE", "DENSE_MATCH"})
    InputShape inputShape;

    @Param("131072")
    int sourceLength;

    private Prog program;
    private Slice input;
    private Dfa.DfaInstance dfa;
    private long expectedResult;

    @Setup(Level.Trial)
    public void setup()
    {
        String exitBytes = "xyz".substring(0, exitByteCount);
        String exitExpression = exitByteCount == 1 ? exitBytes : "[" + exitBytes + "]";
        program = compileProg("^(?:\\C*" + exitExpression + "|" + LARGE_ALTERNATIVE + ")$");

        byte[] bytes = new byte[sourceLength];
        Arrays.fill(bytes, inputShape.dense() ? (byte) 'x' : (byte) 'a');
        expectedResult = SEARCH_NO_MATCH;
        if (inputShape.sparseFalsePositive()) {
            bytes[(bytes.length * 3) / 4] = 'x';
        }
        if (inputShape.match()) {
            bytes[bytes.length - 1] = 'x';
            expectedResult = bytes.length;
        }
        else if (inputShape.dense()) {
            bytes[bytes.length - 1] = 'a';
        }
        input = Slices.wrappedBuffer(bytes);

        byte[] warmupBytes = new byte[1536];
        Arrays.fill(warmupBytes, (byte) 'a');
        warmupBytes[warmupBytes.length - 1] = 'x';
        if (Dfa.search(program, Slices.wrappedBuffer(warmupBytes), false, Prog.MatchKind.FIRST_MATCH, true) != warmupBytes.length) {
            throw new IllegalStateException("DFA warmup input unexpectedly failed");
        }
        if (search() != expectedResult) {
            throw new IllegalStateException("Benchmark input produced an unexpected result");
        }

        dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH);
        if (dfa.estimatedPairedTransitionMemory() <= Dfa.MAX_PAIRED_TRANSITION_MEMORY ||
                dfa.pairedTransitionRowCount() != 0 ||
                dfa.selfLoopTransitionCount() == 0 ||
                dfa.canFixedDistanceByteAcceleration()) {
            throw new IllegalStateException("Benchmark did not select the unpaired self-loop DFA route");
        }
    }

    @Benchmark
    public long search()
    {
        return Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true);
    }

    long expectedResult()
    {
        return expectedResult;
    }

    int selfLoopExitByteScanCount()
    {
        return dfa.selfLoopExitByteScanCount();
    }

    int selfLoopStayRangeScanCount()
    {
        return dfa.selfLoopStayRangeScanCount();
    }

    boolean hasExpectedExitByteSet()
    {
        for (long exitByteSet : dfa.cacheTablesForTesting().selfLoopExitByteSets) {
            if (exitByteSet >>> 24 == exitByteCount) {
                return true;
            }
        }
        return false;
    }

    void resetDfa()
    {
        dfa.resetCacheExternal();
    }

    public enum InputShape
    {
        ABSENT(false, false, false),
        LATE_MATCH(false, true, false),
        SPARSE_FALSE_POSITIVE(false, false, true),
        DENSE_FALSE_POSITIVE(true, false, false),
        DENSE_MATCH(true, true, false);

        private final boolean dense;
        private final boolean match;
        private final boolean sparseFalsePositive;

        InputShape(boolean dense, boolean match, boolean sparseFalsePositive)
        {
            this.dense = dense;
            this.match = match;
            this.sparseFalsePositive = sparseFalsePositive;
        }

        boolean dense()
        {
            return dense;
        }

        boolean match()
        {
            return match;
        }

        boolean sparseFalsePositive()
        {
            return sparseFalsePositive;
        }
    }
}
