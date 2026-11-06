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

import static io.airlift.slice.SizeOf.sizeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// Ported from upstream RE2: re2/testing/compile_test.cc.
public class TestUpstreamCompileMemoryBudget
{
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
        int maximumOnePassStates = 2 + 1;
        long onePassStateSize = Integer.BYTES + ((long) reference.bytemapRange() * Integer.BYTES);
        long insufficientDfaMemory = (4L * maximumOnePassStates * onePassStateSize) - 1;

        Prog program = Compiler.compile(parsed.regexp(), false, programMemory + insufficientDfaMemory);

        assertThat(program.isOnePass()).isFalse();
        assertThat(program.dfaMemory()).isEqualTo(insufficientDfaMemory);
    }

    @Test
    public void testUnboundedCompileUsesUpstreamDefaultDfaBudget()
    {
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer("a".getBytes(StandardCharsets.UTF_8)), Regexp.LIKE_PERL);
        Prog program = Compiler.compile(parsed.regexp(), false, 0);

        long onePassMemory = (long) program.onePassStateCount() *
                (Integer.BYTES + ((long) program.bytemapRange() * Integer.BYTES));
        assertThat(program.dfaMemory()).isEqualTo((1 << 20) - onePassMemory);
    }

    private static void assertRemainingMemoryAssignedToDfa(String pattern, long prefixShiftsMemory)
    {
        int maxMemory = 4096;
        Prog program = Compiler.compile(parse(pattern), false, maxMemory);

        long programMemory = 512L + ((long) program.size() * Compiler.FLATTENED_INSTRUCTION_BYTES);
        long onePassMemory = (long) program.onePassStateCount() *
                (Integer.BYTES + ((long) program.bytemapRange() * Integer.BYTES));
        assertThat(program.isOnePass()).isTrue();
        assertThat(program.dfaMemory()).isEqualTo(maxMemory - programMemory - onePassMemory - prefixShiftsMemory);
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

    private static Regexp parse(String pattern)
    {
        return RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(StandardCharsets.UTF_8)), Regexp.LIKE_PERL).regexp();
    }
}
