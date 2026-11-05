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

import java.nio.charset.StandardCharsets;
import java.util.function.IntFunction;

import static io.airlift.slice.SizeOf.sizeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// Ported from upstream RE2: re2/testing/compile_test.cc.
public class TestUpstreamCompileMemoryBudget
{
    // Each one-pass action retains the action and its next-state offset.
    private static final int ONEPASS_ACTION_BYTES = 2 * Integer.BYTES;

    @Test
    public void testInsufficientMemoryBudgetOneByteFails()
    {
        // From upstream re2/testing/compile_test.cc TestRegexpCompileToProg.Simple.
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer("a".getBytes(StandardCharsets.UTF_8)), Regexp.PERL_EXTENSIONS | Regexp.LATIN1);

        assertThatThrownBy(() -> Compiler.compile(parsed.regexp(), false, 1))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    @Test
    public void testBudgetWithNoRoomForInstructionsIsMemoryLimit()
    {
        // Budgets above the fixed overhead but below one instruction leave zero instructions.
        long oneInstruction = 512 + Compiler.UNFLATTENED_INSTRUCTION_BYTES;
        for (long maxMemory = 513; maxMemory < oneInstruction; maxMemory++) {
            long budget = maxMemory;
            assertThatThrownBy(() -> Compiler.compile(parse("a"), false, budget))
                    .as("budget %s", budget)
                    .isInstanceOf(RegexpCompileMemoryLimitException.class);
        }
        // The public option keeps two thirds of the budget for the forward program, so 768 reaches
        // the overhead-only window and 769 through 946 the zero-instruction window.
        for (long maxMemory = 768; maxMemory - (maxMemory / 3) < oneInstruction; maxMemory++) {
            long budget = maxMemory;
            assertThatThrownBy(() -> Re2.compile(Slices.utf8Slice("a"), Re2.Options.defaults().setMaxMemory(budget)))
                    .as("public budget %s", budget)
                    .isInstanceOf(RegexpCompileMemoryLimitException.class);
        }
    }

    @Test
    public void testInstructionLimitScalesWithBudget()
    {
        // The instruction limit is what the budget leaves past the fixed overhead, divided by the
        // charge per instruction, so the longest literal that compiles grows by one instruction for
        // every instruction's worth of budget added.
        long smallBudget = 1 << 20;
        long largeBudget = 4 << 20;
        int smallLiteral = longestCompiledLiteral(smallBudget);
        int largeLiteral = longestCompiledLiteral(largeBudget);
        assertThat((long) (largeLiteral - smallLiteral) * Compiler.UNFLATTENED_INSTRUCTION_BYTES)
                .isCloseTo(largeBudget - smallBudget, within((long) Compiler.UNFLATTENED_INSTRUCTION_BYTES));
    }

    @Test
    public void testVisitLimitAppliesBeforeInstructionBudget()
    {
        // A budget for one instruction leaves a visit limit of two nodes, which four nested
        // captures exceed before emitting anything. A budget for three instructions reaches the
        // instruction limit instead, so the walk limit is checked first. Both are budget failures.
        long oneInstruction = 512 + Compiler.UNFLATTENED_INSTRUCTION_BYTES;
        long threeInstructions = 512 + 3 * Compiler.UNFLATTENED_INSTRUCTION_BYTES;
        assertThatThrownBy(() -> Compiler.compile(parse("((((a))))"), false, oneInstruction))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
        assertThatThrownBy(() -> Compiler.compile(parse("((((a))))"), false, threeInstructions))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
        assertThatThrownBy(() -> Compiler.compile(parse("ab"), false, oneInstruction))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);

        // Without a budget the visit limit is the upstream walk limit, not a memory limit.
        Regexp deeplyNested = Regexp.literal(Regexp.LIKE_PERL, 'a');
        for (int depth = 0; depth <= 200_000; depth++) {
            deeplyNested = Regexp.capture(Regexp.LIKE_PERL, deeplyNested, depth + 1, null);
        }
        Regexp unbounded = deeplyNested;
        assertThatThrownBy(() -> Compiler.compile(unbounded, false, 0))
                .isInstanceOf(RegexpCompileException.class)
                .isNotInstanceOf(RegexpCompileMemoryLimitException.class)
                .hasMessage("regexp compilation exceeded walkExponential limit");
    }

    @Test
    public void testInsufficientMemoryFailsInsteadOfNoMatch()
    {
        // From upstream re2/testing/compile_test.cc TestCompile.InsufficientMemory, whose 850 bytes
        // leave 42 instructions at upstream's 8 bytes each.
        String pattern = "^(?P<name1>[^\\s]+)\\s+(?P<name2>[^\\s]+)\\s+(?P<name3>.+)$";
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(StandardCharsets.UTF_8)), Regexp.LIKE_PERL);

        assertThatThrownBy(() -> Compiler.compile(parsed.regexp(), false, 512 + 42 * Compiler.UNFLATTENED_INSTRUCTION_BYTES))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    @Test
    public void testRemainingMemoryAssignedToDfa()
    {
        assertRemainingMemoryAssignedToDfa("a", 0);
        // A prefix whose first byte recurs retains a private KMP table, one entry per byte plus one.
        assertRemainingMemoryAssignedToDfa("abcabd", sizeOf(new int[7]));
        // A short prefix whose first byte does not recur shares one table and is charged nothing.
        assertRemainingMemoryAssignedToDfa("abcdef", 0);

        // Compiling an exact literal configures its prefix again, which returns the first shift
        // table's charge before charging the second. A third of the budget goes to the reverse
        // program.
        long forwardMemory = 4096;
        Prog forwardProgram = Re2.compile(Slices.utf8Slice("abcabd"), Re2.Options.defaults().setMaxMemory(forwardMemory * 3 / 2))
                .forwardProgramForDiagnostics();
        assertThat(Compiler.estimatedProgramMemory(forwardProgram) + forwardProgram.dfaMemory()).isEqualTo(forwardMemory);

        // A program over upstream's 512 instructions keeps list heads for BitState, which upstream
        // gives none, and the flatten charge counts them as it does for every program.
        int largeMaxMemory = 1 << 20;
        Prog large = Compiler.compile(parse("(\\pL+)(\\pL*) ([0-9]+)"), false, largeMaxMemory);
        assertThat(large.size()).isGreaterThan(512);
        assertThat(large.canBitState()).isTrue();
        assertThat(large.isOnePass()).isFalse();
        assertThat(large.dfaMemory()).isEqualTo(largeMaxMemory - 512L - ((long) large.size() * Compiler.FLATTENED_INSTRUCTION_BYTES));
    }

    @Test
    public void testPrefixShiftsStayWithinForwardShare()
    {
        // A long leading literal whose first byte recurs keeps a shift table, configured after
        // flattening from what the flatten charge left. At 419,093 bytes the flattened program
        // fits its share but leaves too little for the table, so the compile fails rather than
        // retain more than the share. At 421,049 bytes the share holds both.
        String literal = "a".repeat(320);
        Slice pattern = Slices.utf8Slice(literal + "(?:(?:(?:(?:b?)*?){32})*?)+");
        assertThatThrownBy(() -> Re2.compile(pattern, Re2.Options.defaults().setMaxMemory(419_093)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
        long maxMemory = 421_049;
        Re2 compiled = Re2.compile(pattern, Re2.Options.defaults().setMaxMemory(maxMemory));
        Prog program = compiled.forwardProgramForDiagnostics();
        assertThat(program.prefixShiftsMemory()).isPositive();
        assertThat(Compiler.estimatedProgramMemory(program) + program.dfaMemory()).isLessThanOrEqualTo(maxMemory - (maxMemory / 3));
        // The lazy loops prefer to match nothing after the literal.
        RegexpMatcher matcher = compiled.matcher(Slices.utf8Slice("x" + literal + "bb"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(321);
        assertThat(compiled.find(Slices.utf8Slice("x" + literal.substring(1) + "b"))).isFalse();
    }

    @Test
    public void testReverseProgramOverFlattenedShareIsDropped()
    {
        // The reverse program is compiled from a third of the budget on the first search that needs
        // it. These lazy loops fit that third before flattening but not after, while the forward
        // program and its DFA fit their two thirds. After the forward DFA finds the match end, the
        // search drops the reverse program instead of failing, and finds the match start and the
        // groups without it.
        String pattern = "(a?)(?:(?:(?:(?:a?)*?){16})*?)+(b)";
        long maxMemory = 180_000;
        Re2 compiled = Re2.compile(Slices.utf8Slice(pattern), Re2.Options.defaults().setMaxMemory(maxMemory));
        RegexpMatcher matcher = compiled.matcher(Slices.utf8Slice("xaab"));
        assertThat(matcher.find()).isTrue();
        assertThat(compiled.isReverseProgramComputed()).isTrue();
        assertThat(compiled.reverseProgramIfComputedForDiagnostics()).isNull();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(4);
        assertThat(matcher.start(1)).isEqualTo(1);
        assertThat(matcher.end(1)).isEqualTo(2);
        assertThat(matcher.start(2)).isEqualTo(3);
        assertThat(matcher.end(2)).isEqualTo(4);

        // The instruction limit admits the reverse program, so flattening is what does not fit.
        Regexp regexp = parse(pattern);
        long reverseMemory = maxMemory / 3;
        assertThatCode(() -> Compiler.compileForBenchmark(regexp, true, reverseMemory, Compiler.CompileStage.RAW))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> Compiler.compile(regexp, true, reverseMemory))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    @Test
    public void testOnePassRespectsDfaMemoryBudget()
    {
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer("a".getBytes(StandardCharsets.UTF_8)), Regexp.LIKE_PERL);
        Prog reference = Compiler.compile(parsed.regexp(), false, 4096);

        long programMemory = 512L + ((long) reference.size() * Compiler.FLATTENED_INSTRUCTION_BYTES);
        // Upstream bounds the states by two plus the byte-range instruction count, including the
        // unanchored prefix's instructions.
        int maximumOnePassStates = 2 + reference.getInstCount(InstOp.BYTE_RANGE);
        long upstreamStateSize = Integer.BYTES + ((long) reference.bytemapRange() * Integer.BYTES);
        long sufficientDfaMemory = 4L * maximumOnePassStates * upstreamStateSize;

        // Upstream's limit, a quarter of the DFA budget for tables with four-byte actions, still
        // decides which programs are one-pass. The tables are charged at their real size, which
        // stays within the budget.
        Prog program = Compiler.compile(parsed.regexp(), false, programMemory + sufficientDfaMemory);
        assertThat(program.isOnePass()).isTrue();
        long onePassMemory = (long) program.onePassStateCount() * (Integer.BYTES + ((long) program.bytemapRange() * ONEPASS_ACTION_BYTES));
        assertThat(program.onePassMemory()).isEqualTo(onePassMemory);
        assertThat(program.dfaMemory()).isEqualTo(sufficientDfaMemory - onePassMemory);

        program = Compiler.compile(parsed.regexp(), false, programMemory + sufficientDfaMemory - 1);
        assertThat(program.isOnePass()).isFalse();
        assertThat(program.dfaMemory()).isEqualTo(sufficientDfaMemory - 1);
    }

    @Test
    public void testUnboundedCompileUsesUpstreamDefaultDfaBudget()
    {
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer("a".getBytes(StandardCharsets.UTF_8)), Regexp.LIKE_PERL);
        Prog program = Compiler.compile(parsed.regexp(), false, 0);

        assertThat(program.dfaMemory()).isEqualTo((1 << 20) - program.onePassMemory());
    }

    @Test
    public void testCaptureSearchPlan()
    {
        // A search whose capture threads fit the forward share keeps the ordinary single pass.
        Prog small = Re2.compile(Slices.utf8Slice("(a+)(b)?")).forwardProgramForDiagnostics();
        assertThat(small.singlePassCaptureSlots()).isEqualTo(Integer.MAX_VALUE);
        assertThat(small.boundedPassCaptureSlots()).isEqualTo(Integer.MAX_VALUE);

        // (a?) repeated ten times may hold 33 threads, so a single pass doubles its thread tables
        // from 16 entries to 64, and a matcher's workspace keeps the 16-entry tables until the
        // search ends. Counting those beside the 32- and 64-entry tables leaves this budget's
        // forward share short of a single pass of all 22 slots.
        Prog doubledTwice = Re2.compile(Slices.utf8Slice("(a?)".repeat(10)), Re2.Options.defaults().setMaxMemory(13_150)).forwardProgramForDiagnostics();
        assertThat(doubledTwice.singlePassCaptureSlots()).isLessThan(22);
        assertThat(doubledTwice.boundedPassCaptureSlots()).isEqualTo(22);
    }

    @Test
    public void testLargestCaptureProgramsPlanBoundedPasses()
    {
        // Nested empty groups make every instruction but FAIL and MATCH a CAPTURE, the most capture
        // threads and the deepest capture stack a program of their size can have. The most of them
        // a budget admits still plan bounded passes rather than fail to compile. At the default
        // budget that is 559,234 instructions, of the 593,879 the flattened charge allows.
        assertLargestGroupsPlanBoundedPasses(Re2.Options.DEFAULT_MAX_MEMORY, 279_616, groups -> "^" + "(".repeat(groups) + ")".repeat(groups));
        // The smallest budget that admits a CAPTURE, one group around a start anchor.
        assertLargestGroupsPlanBoundedPasses(1_667, 1, groups -> "(".repeat(groups) + "^" + ")".repeat(groups));

        // The smallest budget that admits any pattern with groups leaves no CAPTURE in the program.
        // Its searches track only group zero, but even those two slots do not fit a single pass
        // beside its doubling thread tables and group-zero queues, so they run one bounded pass. It
        // fits the share with 8 bytes to spare, the tightest fit in a sweep of over 100,000
        // patterns.
        assertThatThrownBy(() -> Re2.compile(Slices.utf8Slice("(()){0}^"), Re2.Options.defaults().setMaxMemory(1_306)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
        Prog empty = Re2.compile(Slices.utf8Slice("(()){0}^"), Re2.Options.defaults().setMaxMemory(1_307)).forwardProgramForDiagnostics();
        assertThat(empty.captureSlotCount()).isEqualTo(2);
        assertThat(empty.singlePassCaptureSlots()).isEqualTo(0);
        assertThat(empty.boundedPassCaptureSlots()).isEqualTo(2);

        // Below 2,651 bytes the passes are not proven to fit what the flattened charge admits.
        // Of the programs in that sweep that track more than group zero, this one's pass fits its
        // share with the least to spare, 40 bytes. Its removed first group leaves the second group
        // in slots 4 and 5, and those six slots, too many for a single pass, run in one bounded
        // pass.
        Prog tightest = Re2.compile(Slices.utf8Slice("(b){0}(^)"), Re2.Options.defaults().setMaxMemory(1_667)).forwardProgramForDiagnostics();
        assertThat(tightest.captureSlotCount()).isEqualTo(6);
        assertThat(tightest.singlePassCaptureSlots()).isEqualTo(0);
        assertThat(tightest.boundedPassCaptureSlots()).isEqualTo(6);
    }

    private static void assertRemainingMemoryAssignedToDfa(String pattern, long prefixShiftsMemory)
    {
        int maxMemory = 4096;
        Prog program = Compiler.compile(parse(pattern), false, maxMemory);

        long programMemory = 512L + ((long) program.size() * Compiler.FLATTENED_INSTRUCTION_BYTES);
        assertThat(program.isOnePass()).isTrue();
        assertThat(program.dfaMemory()).isEqualTo(maxMemory - programMemory - program.onePassMemory() - prefixShiftsMemory);
        assertThat(Compiler.estimatedProgramMemory(program) + program.dfaMemory()).isEqualTo(maxMemory);
    }

    private static int longestCompiledLiteral(long maxMemory)
    {
        // A literal of one byte per eight bytes of budget is rejected, so it bounds the search.
        int upstreamLimit = (int) (maxMemory / 8);
        assertThatThrownBy(() -> Compiler.compile(parse("a".repeat(upstreamLimit)), false, maxMemory))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
        int rejected = upstreamLimit;
        int compiled = 1;
        while (rejected - compiled > 1) {
            int length = (compiled + rejected) >>> 1;
            try {
                Compiler.compile(parse("a".repeat(length)), false, maxMemory);
                compiled = length;
            }
            catch (RegexpCompileMemoryLimitException ignored) {
                rejected = length;
            }
        }
        return compiled;
    }

    private static void assertLargestGroupsPlanBoundedPasses(long maxMemory, int groups, IntFunction<String> pattern)
    {
        Prog program = Re2.compile(Slices.utf8Slice(pattern.apply(groups)), Re2.Options.defaults().setMaxMemory(maxMemory)).forwardProgramForDiagnostics();
        assertThat(program.getInstCount(InstOp.CAPTURE)).isEqualTo(program.size() - 2);
        assertThat(program.singlePassCaptureSlots()).isLessThan(2 * groups + 2);
        assertThat(program.boundedPassCaptureSlots()).isGreaterThanOrEqualTo(4);
        // One more group is past what the forward share admits.
        assertThatThrownBy(() -> Compiler.compile(parse(pattern.apply(groups + 1)), false, maxMemory - (maxMemory / 3)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    private static Regexp parse(String pattern)
    {
        return RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(StandardCharsets.UTF_8)), Regexp.LIKE_PERL).regexp();
    }
}
