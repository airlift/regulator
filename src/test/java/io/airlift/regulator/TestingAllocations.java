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

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

final class TestingAllocations
{
    /// The most runs of thousands of calls each that a test waits for its bound. In CI, runs
    /// reached the bound within a dozen, and a cap of 1,000 would let a genuine failure of the
    /// flattening test allocate 400 GB before it stops.
    static final int MAXIMUM_WINDOW_RUNS = 50;

    private static final int MAXIMUM_ALLOCATION_RUNS = 1_000;

    private TestingAllocations() {}

    static void assertAllocatesNothingOnceCompiled(String name, int maximumRuns, long expectedResult, LongSupplier operations)
    {
        assertAllocatesAtMostOnceCompiled(name, maximumRuns, expectedResult, 0, operations);
    }

    static void assertAllocatesAtMostOnceCompiled(String name, long expectedResult, long maximumBytes, LongSupplier operations)
    {
        assertAllocatesAtMostOnceCompiled(name, MAXIMUM_ALLOCATION_RUNS, expectedResult, maximumBytes, operations);
    }

    /// Runs `operations` until two consecutive runs each allocate at most `maximumBytes` on the
    /// current thread, checking each result, and fails if no two do within `maximumRuns`. A fixed
    /// warm-up can end before C2 compiles the measured code, and how far the JIT has got depends
    /// on the test classes that ran before in the same JVM, which Surefire orders at random. Until
    /// C2 compiles it, the code runs in C1 or the interpreter, where escape analysis removes no
    /// temporary object and every Vector API value is an object, so the first runs can allocate
    /// more: the scan-plan continuation analysis allocates exactly 8,488 bytes a call in C1 and,
    /// measured locally, 5,304 in C2. C1 runs repeat exactly, so equal runs over the bound do not
    /// end the wait. An allocation over the bound on every call recurs in every run and still
    /// fails, and requiring two runs in a row also fails one that recurs in every other run.
    /// Prints the bytes of every run, so a build log shows how many runs it took to reach the
    /// bound.
    static void assertAllocatesAtMostOnceCompiled(String name, int maximumRuns, long expectedResult, long maximumBytes, LongSupplier operations)
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        List<Long> runBytes = new ArrayList<>();
        int consecutiveRunsWithinBound = 0;
        while (runBytes.size() < maximumRuns && consecutiveRunsWithinBound < 2) {
            long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
            long result = operations.getAsLong();
            long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
            assertThat(result).isEqualTo(expectedResult);
            runBytes.add(allocatedBytes);
            consecutiveRunsWithinBound = allocatedBytes <= maximumBytes ? consecutiveRunsWithinBound + 1 : 0;
        }

        // Consecutive equal counts print once with their count, as in "[768 x3, 0 x2]".
        StringBuilder report = new StringBuilder(name).append(": bytes allocated per run [");
        int start = 0;
        while (start < runBytes.size()) {
            int end = start + 1;
            while (end < runBytes.size() && runBytes.get(end).equals(runBytes.get(start))) {
                end++;
            }
            if (start > 0) {
                report.append(", ");
            }
            report.append(runBytes.get(start));
            if (end - start > 1) {
                report.append(" x").append(end - start);
            }
            start = end;
        }
        report.append(']');
        System.out.println(report);
        assertThat(consecutiveRunsWithinBound).as("%s; runs in a row within %s bytes", report, maximumBytes).isEqualTo(2);
    }
}
