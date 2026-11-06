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

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.ThreadMXBean;
import io.airlift.slice.Slice;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.List;

import static io.airlift.slice.SizeOf.instanceSize;
import static io.airlift.slice.SizeOf.sizeOf;
import static io.airlift.slice.SizeOf.sizeOfObjectArray;
import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    public void testFlattenedInstructionLimitFitsPackedOutputs()
    {
        // Packed capture instruction words hold an output in 28 bits, so no budget may let a
        // flattened program index past that.
        assertThat(Compiler.maxFlattenedInstructions(Long.MAX_VALUE)).isEqualTo((1 << 28) - 1);
        assertThat(Compiler.maxFlattenedInstructions(0)).isEqualTo((1 << 28) - 1);
        assertThat(Compiler.maxFlattenedInstructions(96L << 20)).isEqualTo(((96L << 20) - 512) / Compiler.FLATTENED_INSTRUCTION_BYTES);
    }

    @Test
    public void testInstructionChargesCoverWhatAProgramHolds()
            throws ReflectiveOperationException
    {
        // Before flattening, an instruction is charged for its object, its array slot and the
        // flattening pass's scratch. Once flattened, it is charged for its object, its array slot,
        // its list-head entry and the tables the program keeps for searches. The instance size
        // checks the object, and the allocations of flattening and of building those tables below
        // check the rest.
        // The 56-byte charge assumes compressed class pointers. Turning them off, which JDK 25 and
        // 26 deprecate, together with compressed references makes an instruction 64 bytes.
        assertThat(instanceSize(Prog.Inst.class)).isLessThanOrEqualTo(compressedClassPointers() ? 56 : 64);
        long referenceBytes = (sizeOfObjectArray(1024) - sizeOfObjectArray(0)) / 1024;

        ThreadMXBean threadBean = allocatedMemoryBean();
        long threadId = Thread.currentThread().threadId();
        // A literal flattens to one list per instruction, and the alternations give instructions
        // two predecessors.
        for (String pattern : List.of("a".repeat(20_000), "(?:ab|cd)".repeat(10_000))) {
            Regexp regexp = Simplifier.simplify(RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp(), CaseFolding.RE2);
            long scratchBytes = Long.MAX_VALUE;
            long tablesBytes = Long.MAX_VALUE;
            long wordsBytes = Long.MAX_VALUE;
            for (int run = 0; run < 5; run++) {
                Prog program = Compiler.compileNormalizedForBenchmark(regexp, false, 64 << 20, Compiler.CompileStage.OPTIMIZED);
                int unflattenedSize = program.size();

                long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
                program.flatten();
                long flattenBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
                int size = program.size();
                assertThat(size).isLessThanOrEqualTo(unflattenedSize);
                assertThat(sizeOf(program.listHeads()) - sizeOf(new short[0])).isEqualTo(2L * size);
                // Flattening allocates the copies, their array, their list heads and its scratch.
                long copiesBytes = size * (instanceSize(Prog.Inst.class) + (2 * referenceBytes) + Short.BYTES);
                scratchBytes = Math.min(scratchBytes, (flattenBytes - copiesBytes) / unflattenedSize);

                allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
                program.getOrCreateNoSubmatchTables();
                tablesBytes = Math.min(tablesBytes, threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore - (31L * size));

                allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
                program.getOrCreateCaptureInstructionWords();
                wordsBytes = Math.min(wordsBytes, threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore - (8L * size));
            }
            assertThat(scratchBytes).as("scratch bytes per instruction for %s", pattern.substring(0, 9)).isLessThanOrEqualTo(48);
            // What remains past the per-instruction bytes is array headers.
            assertThat(tablesBytes).isLessThan(1024);
            assertThat(wordsBytes).isLessThan(64);
        }

        // Trino compiles a greedy nullable loop with a clone of its body for the empty-iteration
        // check, so nested empty loops compile to 391 instructions that flatten to 42. The program
        // must still keep an instruction array within the flattened charge's doubling allowance.
        Prog shrunk = TrinoRegexp.compile(utf8Slice("(".repeat(13) + ")" + ")*".repeat(12))).re2().forwardProgramForDiagnostics();
        Field instructions = Prog.class.getDeclaredField("insts");
        instructions.setAccessible(true);
        assertThat(((Prog.Inst[]) instructions.get(shrunk)).length).isLessThanOrEqualTo(2 * shrunk.size());
    }

    @Test
    public void testFlattenedProgramsAreChargedAgainstTheBudget()
    {
        // Flattening copies each list's epsilon closure, and a lazy loop over a nullable body puts
        // the loops inside it in many closures, so the flattened program grows with the square of
        // the program the instruction limit bounds. Unchecked, the a? pattern allocated 309 MB to
        // compile. Each language now stops at the forward program's share of the default budget.
        String nullableLoops = "(?:(?:(?:(?:a?)*?){1000})*?)+";
        assertCompileRejectedWithinBudget(() -> Re2.compile(utf8Slice(nullableLoops)));
        assertCompileRejectedWithinBudget(() -> JavaRegexp.compile(utf8Slice(nullableLoops)));
        assertCompileRejectedWithinBudget(() -> RustRegexp.compile(utf8Slice(nullableLoops)));
        // Joni's quantifier reduction keeps a lazy +? under a greedy *, so the growth reaches Trino.
        assertCompileRejectedWithinBudget(() -> TrinoRegexp.compile(utf8Slice("(?:(?:(?:(?:a|b?)*?){1000})+?)*")));
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
    public void testSimpleLikePatternsCopyOnlyTheirLiteral()
    {
        ThreadMXBean threadBean = allocatedMemoryBean();
        // A simple pattern copies only its literal, and a contains kernel holds that copy and, for
        // a literal whose first byte does not recur, the shared shift table.
        List<Slice> patterns = List.of(utf8Slice("https://%"), utf8Slice("%.example.com"), utf8Slice("%coolfunctionname%"));
        for (int iteration = 0; iteration < 10_000; iteration++) {
            for (Slice pattern : patterns) {
                assertThat(TrinoLikePattern.compile(pattern).pattern()).isEqualTo(pattern);
            }
        }

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int iterations = 10_000;
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (Slice pattern : patterns) {
                TrinoLikePattern.compile(pattern);
            }
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(allocatedBytes / (iterations * (long) patterns.size())).isLessThan(232);
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

    @Test
    public void testLargeUnicodeClassCompileAllocation()
    {
        // Unicode \w compiles to hundreds of byte ranges. The ceiling guards the combined savings of
        // the byte-map, member-table and one-pass changes: a compile allocates about 1.56 MB, and
        // about 1.67 MB when the JIT has not applied escape analysis, so it leaves about 10% over
        // the larger. About 300 KB of that is the one-pass step table, 8 bytes for each of the
        // program's 37,500 actions.
        assumeTrue(((ThreadMXBean) ManagementFactory.getThreadMXBean()).isThreadAllocatedMemorySupported(), "requires thread allocation counting");
        ThreadMXBean threadBean = allocatedMemoryBean();
        Slice pattern = utf8Slice("^(\\w+)-(\\d+)/");
        for (int iteration = 0; iteration < 1_000; iteration++) {
            assertThat(RustRegexp.compile(pattern).pattern()).isEqualTo(pattern);
        }

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int iterations = 100;
        for (int iteration = 0; iteration < iterations; iteration++) {
            RustRegexp.compile(pattern);
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(allocatedBytes / iterations).isLessThan(1_840_000);
    }

    @Test
    public void testOnePassRejectedLateAllocatesForVisitedNodes()
    {
        // The literals use 63 byte classes, so node storage starts at 130 nodes, and the x|xy branch
        // stops the analysis just after the literals before it, so storage doubles as nodes are
        // visited, capped at the bound, rather than jumping to the bound. With 3 alphabets before the
        // branch and 60 after, the bound is 3,910 nodes, a 985 KB action table, and the analysis stops
        // near node 190, at about 180 KB in all. With 5 and 11, the bound is 996 nodes and the
        // analysis stops near node 310, after storage doubled to 260 and then 520 nodes, at about
        // 250 KB; jumping to the bound at the second overflow would cost 375 KB.
        assumeTrue(((ThreadMXBean) ManagementFactory.getThreadMXBean()).isThreadAllocatedMemorySupported(), "requires thread allocation counting");
        ThreadMXBean threadBean = allocatedMemoryBean();
        String alphanumeric = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        List<String> patterns = List.of(
                "^" + alphanumeric.repeat(3) + "(?:x|xy)y" + alphanumeric.repeat(60) + "$",
                "^" + alphanumeric.repeat(5) + "(?:x|xy)y" + alphanumeric.repeat(11) + "$");
        for (String pattern : patterns) {
            Regexp regexp = Simplifier.simplify(RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp(), CaseFolding.RE2);
            Prog program = Compiler.compileNormalizedForBenchmark(regexp, false, 8 << 20, Compiler.CompileStage.BYTEMAP);

            long threadId = Thread.currentThread().threadId();
            long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
            program.prepareOnePass();
            long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

            assertThat(program.bytemapRange()).isEqualTo(63);
            assertThat(program.isOnePass()).isFalse();
            assertThat(allocatedBytes).isLessThan(300_000);
        }
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

    private static void assertCompileRejectedWithinBudget(ThrowingCallable compile)
    {
        ThreadMXBean threadBean = allocatedMemoryBean();
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        assertThatThrownBy(compile).isInstanceOf(RegexpCompileMemoryLimitException.class);
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        // The rejected compile builds at most the forward share of the budget, plus its scratch.
        assertThat(allocatedBytes).isLessThan(2L * Re2.Options.DEFAULT_MAX_MEMORY);
    }

    private static boolean compressedClassPointers()
    {
        try {
            return Boolean.parseBoolean(ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption("UseCompressedClassPointers").getValue());
        }
        catch (IllegalArgumentException e) {
            // JDK 27 removes the option and always compresses class pointers.
            return true;
        }
    }

    private static ThreadMXBean allocatedMemoryBean()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);
        return threadBean;
    }
}
