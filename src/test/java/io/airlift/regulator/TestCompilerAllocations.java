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
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestCompilerAllocations
{
    @Test
    public void testExpressionAnalysisAvoidsPerNodeStreamAllocation()
    {
        ThreadMXBean threadBean = allocatedMemoryBean();
        Regexp regexp = RegexpParser.parse(utf8Slice("(.*)-(\\d+)-of-(\\d+)"), Regexp.LIKE_PERL).regexp();
        Regexp normalized = Simplifier.simplify(regexp);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            ExpressionAnalysis.analyzeNormalized(normalized);
        }

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int iterations = 10_000;
        for (int iteration = 0; iteration < iterations; iteration++) {
            ExpressionAnalysis.analyzeNormalized(normalized);
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(allocatedBytes / iterations).isLessThan(4_000);
    }

    @Test
    public void testFlatteningReusesHintScratchSpace()
    {
        ThreadMXBean threadBean = allocatedMemoryBean();

        Regexp regexp = RegexpParser.parse(utf8Slice("ABCDEFGHIJKLMNOPQRSTUVWXYZ$"), Regexp.LIKE_PERL).regexp();
        for (int iteration = 0; iteration < 10_000; iteration++) {
            Compiler.compile(regexp);
        }

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int instructionCount = 0;
        int iterations = 10_000;
        for (int iteration = 0; iteration < iterations; iteration++) {
            instructionCount += Compiler.compile(regexp).size();
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(instructionCount).isEqualTo(300_000);
        assertThat(allocatedBytes / iterations).isLessThan(40_000);
    }

    private static ThreadMXBean allocatedMemoryBean()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);
        return threadBean;
    }
}
