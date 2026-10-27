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
import java.util.List;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestCompilerAllocations
{
    @Test
    public void testExpressionAnalysisAvoidsPerNodeStreamAllocation()
    {
        ThreadMXBean threadBean = allocatedMemoryBean();
        Regexp regexp = RegexpParser.parse(utf8Slice("(.*)-(\\d+)-of-(\\d+)"), Regexp.LIKE_PERL).regexp();
        Regexp normalized = Simplifier.simplify(regexp, CaseFolding.RE2);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            ExpressionAnalysis.analyzeNormalized(normalized, CaseFolding.RE2);
        }

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int iterations = 10_000;
        for (int iteration = 0; iteration < iterations; iteration++) {
            ExpressionAnalysis.analyzeNormalized(normalized, CaseFolding.RE2);
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(allocatedBytes / iterations).isLessThan(4_000);
    }

    @Test
    public void testExpressionAnalysisStaysLinearInNestedConcatenations()
    {
        ThreadMXBean threadBean = allocatedMemoryBean();
        // Simplification expands each x{1000} into single-byte literals, and each y( adds a
        // concatenation level above all of them.
        String pattern = "y(".repeat(500) + "(?:x{1000})".repeat(50) + ")".repeat(500);
        Regexp regexp = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();
        Regexp normalized = Simplifier.simplify(regexp, CaseFolding.RE2);

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        ExpressionAnalysis analysis = ExpressionAnalysis.analyzeNormalized(normalized, CaseFolding.RE2);
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(analysis.literalSequence().exactLiteral()).isEqualTo(utf8Slice("y".repeat(500) + "x".repeat(50_000)));
        assertThat(allocatedBytes).isLessThan(64_000_000);
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

    @Test
    public void testScanPlanContinuationAnalysisUsesCompactStorage()
    {
        ThreadMXBean threadBean = allocatedMemoryBean();
        ParseResult parsed = TrinoRegexpParser.parse(
                utf8Slice("^((a)?(b)?(c)?(d)?)abcd([^/]+)/$"),
                Regexp.LIKE_PERL);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            assertThat(ScanPlan.analyze(parsed.regexp(), parsed.capturingGroupCount(), ScanPlan.Language.TRINO, false, parsed.caseFolding(), ByteFrequencies.uniform())).isNotNull();
        }

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int iterations = 10_000;
        for (int iteration = 0; iteration < iterations; iteration++) {
            ScanPlan.analyze(parsed.regexp(), parsed.capturingGroupCount(), ScanPlan.Language.TRINO, false, parsed.caseFolding(), ByteFrequencies.uniform());
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(allocatedBytes / iterations).isLessThan(8_000);
    }

    @Test
    public void testScanPlanRejectsUnsupportedUnanchoredStartBeforeAllocation()
    {
        ThreadMXBean threadBean = allocatedMemoryBean();
        List<ParseResult> expressions = List.of(
                TrinoRegexpParser.parse(utf8Slice("(?:www\\.)?([^/]+)/"), Regexp.LIKE_PERL),
                TrinoRegexpParser.parse(utf8Slice("([a-z]+)$"), Regexp.LIKE_PERL));
        for (int iteration = 0; iteration < 10_000; iteration++) {
            for (ParseResult expression : expressions) {
                assertThat(ScanPlan.analyze(expression.regexp(), expression.capturingGroupCount(), ScanPlan.Language.TRINO, false, expression.caseFolding(), ByteFrequencies.uniform()))
                        .as(RegexpToString.toString(expression.regexp()))
                        .isNull();
            }
        }

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int iterations = 10_000;
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (ParseResult expression : expressions) {
                ScanPlan.analyze(expression.regexp(), expression.capturingGroupCount(), ScanPlan.Language.TRINO, false, expression.caseFolding(), ByteFrequencies.uniform());
            }
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(allocatedBytes / (iterations * (long) expressions.size())).isLessThan(128);
    }

    @Test
    public void testTrinoSiblingNullableLoopsAllocateWithTheirProgram()
    {
        // The program grows linearly with the loops, and the clone table must not grow with the
        // square of the loop count.
        assertTrinoCompileAllocation("(?:a|)*".repeat(4_000), 8_003, 32_000_000);
    }

    @Test
    public void testTrinoNestedNullableLoopsAllocateWithTheirProgram()
    {
        // Each loop clones the epsilon-reachable part of the loops inside it, so the program grows
        // with the square of the depth, and the clone table must not grow with the depth cubed.
        String pattern = "(?:^|a?)";
        for (int depth = 0; depth < 320; depth++) {
            pattern = "(?:a?|" + pattern + ")*";
        }
        assertTrinoCompileAllocation(pattern, 52_324, 128_000_000);
    }

    @Test
    public void testMemberTableOfManyPiecesStaysBounded()
    {
        ThreadMXBean threadBean = allocatedMemoryBean();
        // A set of every other code point above ASCII splits into 556,992 one-character pieces.
        // Splitting allocates about 27 MB for their encodings, and the table's own build must
        // not grow with them.
        int runeCount = (Regexp.RUNEMAX + 1 - 0x80) / 2;
        RuneRange[] ranges = new RuneRange[runeCount];
        for (int index = 0; index < runeCount; index++) {
            int rune = 0x80 + 2 * index;
            ranges[index] = new RuneRange(rune, rune);
        }
        CharClass set = new CharClass(false, runeCount, ranges);

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        Utf8MemberTable table = Utf8MemberTable.of(set);
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        byte[] member = Character.toString(0x10FFFE).getBytes(UTF_8);
        byte[] nonMember = Character.toString(0x10FFFF).getBytes(UTF_8);
        assertThat(table.memberWidth(member, 0, member.length)).isEqualTo(4);
        assertThat(table.memberWidth(nonMember, 0, nonMember.length)).isZero();
        assertThat(allocatedBytes).isLessThan(32_000_000);
    }

    private static void assertTrinoCompileAllocation(String pattern, int instructionCount, long maxAllocatedBytes)
    {
        ThreadMXBean threadBean = allocatedMemoryBean();
        ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL);
        Regexp regexp = Simplifier.simplify(parsed.regexp(), parsed.caseFolding());

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        Prog program = Compiler.compileNormalized(regexp, false, 64 << 20, Compiler.Dialect.TRINO, parsed.caseFolding());
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        // The count proves the pattern still reaches the nullable-loop lowering.
        assertThat(program.size()).isEqualTo(instructionCount);
        assertThat(allocatedBytes).isLessThan(maxAllocatedBytes);
    }

    private static ThreadMXBean allocatedMemoryBean()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);
        return threadBean;
    }
}
