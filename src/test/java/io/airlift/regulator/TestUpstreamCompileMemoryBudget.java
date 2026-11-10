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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.LongFunction;

import static io.airlift.slice.SizeOf.sizeOf;
import static io.airlift.slice.SizeOf.sizeOfObjectArray;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// Ported from upstream RE2: re2/testing/compile_test.cc.
public class TestUpstreamCompileMemoryBudget
{
    // Each one-pass action retains one step: the action packed with its next-state offset.
    private static final int ONEPASS_ACTION_BYTES = Long.BYTES;

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
        long oneInstruction = Compiler.PROGRAM_OVERHEAD_BYTES + Compiler.UNFLATTENED_INSTRUCTION_BYTES;
        for (long maxMemory = Compiler.PROGRAM_OVERHEAD_BYTES + 1; maxMemory < oneInstruction; maxMemory++) {
            long budget = maxMemory;
            assertThatThrownBy(() -> Compiler.compile(parse("a"), false, budget))
                    .as("budget %s", budget)
                    .isInstanceOf(RegexpCompileMemoryLimitException.class);
        }
        // The public option keeps two thirds of the budget for the forward program, so 4,032 reaches
        // the overhead-only window and 4,033 through 4,138 the zero-instruction window.
        for (long maxMemory = 4032; maxMemory - (maxMemory / 3) < oneInstruction; maxMemory++) {
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
        long oneInstruction = Compiler.PROGRAM_OVERHEAD_BYTES + Compiler.UNFLATTENED_INSTRUCTION_BYTES;
        long threeInstructions = Compiler.PROGRAM_OVERHEAD_BYTES + 3 * Compiler.UNFLATTENED_INSTRUCTION_BYTES;
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

        assertThatThrownBy(() -> Compiler.compile(parsed.regexp(), false, Compiler.PROGRAM_OVERHEAD_BYTES + 42 * Compiler.UNFLATTENED_INSTRUCTION_BYTES))
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
        // With the Vector API, alternatives past such a prefix keep their literal set where the
        // budget leaves the forward DFAs their minimum beside it, charged as the array of its two
        // literals.
        assertRemainingMemoryAssignedToDfa("fn is_|fn as_", 1 << 20, VectorSupport.isAvailable() ? sizeOfObjectArray(2) + 2 * sizeOf(new byte[6]) : 0);

        // Compiling an exact literal configures its prefix again, which returns the first shift
        // table's charge before charging the second. A third of the budget goes to the reverse
        // program, and the position automaton and the short-window required bytes take theirs
        // from the forward DFA's.
        long forwardMemory = 8192;
        Re2 literal = Re2.compile(Slices.utf8Slice("abcabd"), Re2.Options.defaults().setMaxMemory(forwardMemory * 3 / 2));
        Prog forwardProgram = literal.forwardProgramForDiagnostics();
        assertThat(forwardProgram.positionNfa()).isNotNull();
        assertThat(literal.requiredBytesRetainedSizeForDiagnostics()).isPositive();
        assertThat(Compiler.estimatedProgramMemory(forwardProgram) + forwardProgram.positionNfa().estimatedRetainedSize() + forwardProgram.dfaMemory() + literal.requiredBytesRetainedSizeForDiagnostics())
                .isEqualTo(forwardMemory);

        // A program over upstream's 512 instructions keeps output list ids for BitState, where
        // upstream gives it no list heads, and the flatten charge counts them as it does for every
        // program.
        int largeMaxMemory = 1 << 20;
        Prog large = Compiler.compile(parse("(\\pL+)(\\pL*) ([0-9]+)"), false, largeMaxMemory);
        assertThat(large.size()).isGreaterThan(512);
        assertThat(large.canBitState()).isTrue();
        assertThat(large.isOnePass()).isFalse();
        assertThat(large.dfaMemory()).isEqualTo(largeMaxMemory - Compiler.PROGRAM_OVERHEAD_BYTES - ((long) large.size() * Compiler.FLATTENED_INSTRUCTION_BYTES));
    }

    @Test
    public void testPrefixShiftsStayWithinForwardShare()
    {
        // A long leading literal whose first byte recurs keeps a shift table, configured after
        // flattening from what the flatten charge left. At 187,384 bytes the flattened program
        // fits its share but leaves too little for the table, so the compile fails rather than
        // retain more than the share. At 187,385 bytes the share holds both.
        String literal = "a".repeat(320);
        Slice pattern = Slices.utf8Slice(literal + "(?:(?:(?:(?:b?)*?){32})*?)+");
        assertThatThrownBy(() -> Re2.compile(pattern, Re2.Options.defaults().setMaxMemory(187_384)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
        long maxMemory = 187_385;
        Re2 compiled = Re2.compile(pattern, Re2.Options.defaults().setMaxMemory(maxMemory));
        Prog program = compiled.forwardProgramForDiagnostics();
        assertThat(program.prefixScanMemory()).isPositive();
        assertThat(Compiler.estimatedProgramMemory(program) + program.dfaMemory()).isLessThanOrEqualTo(maxMemory - (maxMemory / 3));
        // At its exact limit the program still compiles and keeps its BitState output lists,
        // which take the list heads' place inside the instruction charge.
        assertThat(program.canBitState()).isTrue();
        // The lazy loops prefer to match nothing after the literal.
        RegexpMatcher matcher = compiled.matcher(Slices.utf8Slice("x" + literal + "bb"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(321);
        assertThat(compiled.find(Slices.utf8Slice("x" + literal.substring(1) + "b"))).isFalse();
    }

    @Test
    public void testPrefixLiteralSetYieldsToForwardShare()
    {
        // The prefix scan's literal set, `abx_` and `aby_` paired on `a` and `_`, is built only
        // with the Vector API and only saves work. At the least budget at which the lazy loops
        // compile, the forward share has no room left for it, so the prefix keeps its pair scan
        // and the least budget is the same with the Vector API as without it, as is the budget a
        // rejection reports. A comfortable budget keeps the set.
        String loops = "(?:(?:(?:(?:b?)*?){8})*?)+";
        Slice pattern = Slices.utf8Slice("abx_" + loops + "|aby_" + loops);
        assertPrefixLiteralSetYields(maxMemory -> Re2.compile(pattern, Re2.Options.defaults().setMaxMemory(maxMemory)), 27_257);
        assertPrefixLiteralSetYields(maxMemory -> RustRegexp.compile(pattern, RustRegexp.Options.defaults().setMaxMemory(maxMemory)), 12_779);

        // The set is decided after OnePass and every route, so at the least budget at which the
        // forward DFAs keep their minimum it takes none of their memory and is dropped. Charged
        // first, it left the DFAs short at budgets that hold their minimum without it: beside
        // OnePass in the first pattern, and beside the literal alternation route in the second.
        assertPrefixLiteralSetYieldsToDfaMinimum("fn is_(\\w+)|fn as_(\\w+)", false);
        assertPrefixLiteralSetYieldsToDfaMinimum("fn is_x|fn as_y", false);
        // The bounded repeats' position automaton is such a route: at the least budget at which
        // it is kept beside the forward DFAs' minimum, the set is dropped.
        assertPrefixLiteralSetYieldsToDfaMinimum("fn is_\\w{1,8}|fn as_\\w{1,8}", true);
    }

    @Test
    public void testReverseProgramOverFlattenedShareIsDropped()
    {
        // The reverse program is compiled from a third of the budget on the first search that needs
        // it. A match that must end with the text needs it first, as the search runs the reverse
        // DFA back from the end. These lazy loops fit that third before flattening but not after,
        // while the forward program fits its two thirds. The search drops the reverse program
        // instead of failing, and finds the match and the groups without it.
        String pattern = "(a?)(?:(?:(?:(?:a?)*?){16})*?)+(b)$";
        long maxMemory = 60_000;
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

        long programMemory = Compiler.PROGRAM_OVERHEAD_BYTES + ((long) reference.size() * Compiler.FLATTENED_INSTRUCTION_BYTES);
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
    public void testLongestMatchDfaGateCountsRealWorstCase()
    {
        // A UTF-8 letter class compiles to about three instructions per list. A gate that gives a
        // longest-match state one mark per instruction, and a reversed program the stack of the
        // forward start-byte walks, needs about 600,000 bytes for either DFA, and the stack alone
        // takes the reversed DFA past 330,000. Counting one mark per list and sizing the reversed
        // stack as upstream sizes it admits both at these budgets. Each then finds its boundary of
        // the three letters in `12 éàü 34`, the end forward and the start reversed, a forward
        // longest-match DFA taking half its program's DFA memory.
        Regexp letters = parse("\\pL{3}");
        Prog forward = Compiler.compile(letters, false, 1 << 20);
        forward.setDfaMemory(2 * 400_000);
        Prog reversed = Compiler.compile(letters, true, 1 << 20);
        reversed.setDfaMemory(330_000);
        Slice text = Slices.utf8Slice("12 éàü 34");
        assertThat(Dfa.search(forward, text, false, Prog.MatchKind.LONGEST_MATCH, true)).isEqualTo(9);
        assertThat(Dfa.search(reversed, text, 0, 9, true, Prog.MatchKind.LONGEST_MATCH, true)).isEqualTo(3);
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
        // a budget admits plan bounded passes, and one more is rejected at compile time. At a
        // 96 MiB budget that is 699,042 instructions, of the 1,369,513 the flattened charge allows:
        // the capture passes' tables, not the instruction limit, bound these programs.
        assertLargestGroupsPlanBoundedPasses(96L << 20, 349_520, groups -> "^" + "(".repeat(groups) + ")".repeat(groups));
        // At the least budget that admits them, sixteen groups plan bounded passes, and seventeen
        // are rejected.
        assertLargestGroupsPlanBoundedPasses(7_919, 16, groups -> "^" + "(".repeat(groups) + ")".repeat(groups));

        // The smallest budget that admits any pattern with groups leaves no CAPTURE in the program,
        // and the program's fixed charge holds a single pass of group zero's two slots.
        assertThatThrownBy(() -> Re2.compile(Slices.utf8Slice("(()){0}^"), Re2.Options.defaults().setMaxMemory(4_354)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
        Prog empty = Re2.compile(Slices.utf8Slice("(()){0}^"), Re2.Options.defaults().setMaxMemory(4_355)).forwardProgramForDiagnostics();
        assertThat(empty.captureSlotCount()).isEqualTo(2);
        assertThat(empty.singlePassCaptureSlots()).isEqualTo(Integer.MAX_VALUE);

        // At the least budget that compiles it, this program's removed first group leaves the
        // second group in slots 4 and 5, and the single pass tracks all six slots.
        Prog tightest = Re2.compile(Slices.utf8Slice("(b){0}(^)"), Re2.Options.defaults().setMaxMemory(4_571)).forwardProgramForDiagnostics();
        assertThat(tightest.captureSlotCount()).isEqualTo(6);
        assertThat(tightest.singlePassCaptureSlots()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    public void testRejectionReportsLowerBoundOnRequiredBudget()
    {
        String anchoredPrefix = "^" + "a".repeat(5000) + "(?:b?c?)*d";
        String nestedOptional = "(?:(?:(?:(?:b?)*?){64})*?)+";
        String fixedWidth = "(?:[é中][a-z]){127}[0-9]{2}";
        String fruits = "apple|banana|cherry|durian|elderberry";
        String words = randomWords(65, 7);
        String dictionary = randomWords(3000, 11);
        String sparseHighClass = "[13579ACEGIKMOQSUWYacegikmoqsuwy\\x{100000}]{1,2}";
        // Each pattern reports the same figure at every rejected budget listed, a figure whose
        // predecessor fails, and compiles first at the least budget. The literal sets and
        // matchers, the compact counter, and the sets' DFAs are charged for the objects they
        // retain, so their figures assume the default object layout CI runs, with compressed
        // references and class pointers and without compact object headers.
        List<RejectedPattern> rejections = List.of(
                // The instruction limit, estimated from the program the pattern compiles to: past
                // a required prefix and the anchor that drops the search loop, through branches
                // that collapse to nothing, and through repetitions the simplifier squashes.
                new RejectedPattern("anchored prefix", Language.RE2, anchoredPrefix, 5_219, 5_219, 1, 1_500),
                new RejectedPattern("anchored prefix", Language.JAVA, anchoredPrefix, 5_327, 5_543, 1, 1_500),
                new RejectedPattern("anchored prefix", Language.TRINO, anchoredPrefix, 5_327, 5_543, 1, 1_500),
                new RejectedPattern("empty repetitions", Language.RE2, "(?:" + "a{0}b{0}".repeat(9000) + "){1000}", 4_571, 4_571, 1, 1_200, 4_000),
                new RejectedPattern(Language.JAVA, "(?:a{0}){1000}", 4_571, 4_571, 1, 1_000),
                new RejectedPattern(Language.JAVA, "(?:a|)*", 5_111, 5_111, 1, 1_500),
                new RejectedPattern(Language.JAVA, "^(?:a|)*$", 5_111, 5_111, 1, 1_500),
                new RejectedPattern(Language.RE2, "(?:ab[ab]+)?", 5_003, 5_003, 1, 1_600),
                new RejectedPattern(Language.RUST, "(?:a+){2,4}", 5_435, 5_435, 1, 2_000),
                new RejectedPattern(Language.RE2, "(?:abc){1000}", 328_463, 328_463, 1, 65_536),
                new RejectedPattern(Language.RE2, "((((a))))|(b)(c)", 6_191, 6_191, 1, 2_000),
                // Java compiles a repetition of a body that can match empty with more than its
                // copies.
                new RejectedPattern(Language.JAVA, "(?:(?:a?){10}b){100}", 350_063, 350_063, 2_000, 65_536),
                // A larger budget gets further and learns more: the instruction limit, then the
                // flattened program, which measures exactly what it needs.
                new RejectedPattern(Language.RE2, nestedOptional, 32_435, 620_549, 2_000),
                new RejectedPattern(Language.RE2, nestedOptional, 467_375, 620_549, 33_000),
                new RejectedPattern(Language.RE2, nestedOptional, 620_549, 620_549, 100_000),
                // The flattened program fits but leaves too little for a long prefix's shift table.
                new RejectedPattern("long prefix", Language.RE2, "a".repeat(320) + "(?:(?:(?:(?:b?)*?){32})*?)+", 187_385, 187_385, 185_429, 187_384),
                // A compact counter in place of a program measures exactly what it needs. A
                // budget too small for the counter compiles the program, so a sparse class whose
                // counter's bitset reaches U+100000 reports what its smaller program needs.
                new RejectedPattern(Language.RE2, "\\p{L}{100,200}", 53_975, 53_975, 1, 8_000),
                new RejectedPattern("sparse high class", Language.RE2, sparseHighClass, 13_211, 13_211, 1, 2_000),
                new RejectedPattern("sparse high class", Language.JAVA, sparseHighClass, 13_211, 13_211, 1, 2_000),
                new RejectedPattern("sparse high class", Language.RUST, sparseHighClass, 13_211, 13_211, 1, 2_000),
                new RejectedPattern("sparse high class", Language.TRINO, sparseHighClass, 13_211, 13_211, 1, 2_000),
                // The figure covers every route: the literal set that compiles without a program,
                // and Trino's literal alternation, literal set and fixed-width matcher.
                new RejectedPattern(Language.RE2, fruits, 8_459, 8_459, 1, 2_000),
                new RejectedPattern("65 words", Language.RE2, words, 13_417, 13_417, 1, 2_000),
                new RejectedPattern("65 words", Language.JAVA, words, 13_417, 13_417, 1, 2_000),
                new RejectedPattern("65 words", Language.RUST, words, 13_417, 13_417, 1, 2_000),
                new RejectedPattern("3,000 words", Language.RUST, dictionary, 275_149, 275_149, 1_000, 200_000),
                // Trino's literal alternation builds its start filter only with vector support.
                new RejectedPattern(Language.TRINO, fruits, VectorSupport.isAvailable() ? 4_151 : 1_487, VectorSupport.isAvailable() ? 4_151 : 1_487, 1, 1_000),
                new RejectedPattern("65 words", Language.TRINO, words, 9_227, 9_227, 1, 2_000),
                new RejectedPattern("3,000 words", Language.TRINO, dictionary, 269_843, 269_843, 1_000, 200_000),
                new RejectedPattern(Language.TRINO, fixedWidth, 12_793, 12_793, 1, 2_000),
                new RejectedPattern(Language.RE2, fixedWidth, 100_691, 100_691, 1, 2_000),
                // 255 copies of a class of supplementary code points need more instructions than
                // any program holds, but Trino's fixed-width matcher compiles them.
                new RejectedPattern("supplementary class run", Language.TRINO, supplementaryClassRun(), 796_705, 796_705, 2_000),
                // A set reports its many-match DFA's start only after its program compiled.
                new RejectedPattern(Language.SET, List.of("foo"), 3_192, 6_646, 1),
                new RejectedPattern(Language.SET, List.of("foo"), 6_646, 6_646, 4_000),
                new RejectedPattern(Language.SET, List.of("foo", "ba[rz]+"), 3_768, 7_828, 1),
                new RejectedPattern(Language.SET, List.of("foo", "ba[rz]+"), 7_828, 7_828, 5_000),
                // The many-match initialization gate omits match IDs; allocated states still charge
                // them.
                new RejectedPattern("100 words", Language.SET, List.of(randomWords(100, 7).split("\\|")), 123_299, 123_299, 110_000),
                new RejectedPattern(Language.SET, List.of("[a-z]{5,500}"), 74_616, 122_630, 2_000, 50_000),
                new RejectedPattern(Language.SET, List.of("(a?)(?:(?:(?:(?:a?)*?){16})*?)+(b)"), 30_422, 51_046, 20_000));
        for (RejectedPattern rejection : rejections) {
            for (long maxMemory : rejection.maxMemories()) {
                assertThatThrownBy(() -> rejection.compile(maxMemory))
                        .as("%s at %s", rejection, maxMemory)
                        .isInstanceOfSatisfying(RegexpCompileMemoryLimitException.class, exception -> {
                            assertThat(exception.maxMemoryBytes()).isEqualTo(maxMemory);
                            assertThat(exception.requiredMaxMemoryBytes()).hasValue(rejection.required());
                            assertThat(exception).hasMessage("regular expression compilation exceeded the " + maxMemory + " byte memory limit; " +
                                    "the pattern needs a larger memory budget, at least " + RegexpCompileMemoryLimitException.formatBudget(rejection.required()));
                        });
            }
            if (rejection.required() != rejection.least()) {
                assertThatThrownBy(() -> rejection.compile(rejection.required() - 1))
                        .as("%s below its reported budget", rejection)
                        .isInstanceOf(RegexpCompileMemoryLimitException.class);
            }
            assertThatThrownBy(() -> rejection.compile(rejection.least() - 1))
                    .as("%s below its least budget", rejection)
                    .isInstanceOf(RegexpCompileMemoryLimitException.class);
            assertThatCode(() -> rejection.compile(rejection.least()))
                    .as("%s at its least budget", rejection)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    public void testRejectionReportsWhenNoBudgetCanCompile()
    {
        // Eleven runs of a thousand letters need more instructions than any program holds, and no
        // other route takes them.
        for (Language language : Language.values()) {
            RejectedPattern tooLarge = new RejectedPattern(language, List.of("\\p{L}{1000}".repeat(11)), 0, 0);
            assertThatThrownBy(() -> tooLarge.compile(Re2.Options.DEFAULT_MAX_MEMORY))
                    .as("%s", language)
                    .isInstanceOfSatisfying(RegexpCompileMemoryLimitException.class, exception -> {
                        assertThat(exception.requiredMaxMemoryBytes()).isEmpty();
                        assertThat(exception).hasMessage("regular expression compilation exceeded the " + Re2.Options.DEFAULT_MAX_MEMORY + " byte memory limit; " +
                                "the pattern is too large to compile at any memory budget");
                    });
        }
    }

    @Test
    public void testRejectionReportsNoBudgetPastEstimateCap()
    {
        // A thousand alternations nested through x{1}, which simplifies to x, copy too many
        // alternatives for the instruction estimate to count, so the program's route proves no
        // bound, and neither does the least over every route. Rust's nest limit rejects so deep a
        // nesting when it parses.
        String nested = "aa|(?:".repeat(1000) + "bb" + "){1}".repeat(1000);
        for (Language language : List.of(Language.RE2, Language.JAVA, Language.TRINO, Language.SET)) {
            RejectedPattern uncounted = new RejectedPattern("nested alternations", language, nested, 0, 0);
            assertThatThrownBy(() -> uncounted.compile(2_000))
                    .as("%s", language)
                    .isInstanceOfSatisfying(RegexpCompileMemoryLimitException.class, exception -> {
                        assertThat(exception.requiredMaxMemoryBytes()).isEmpty();
                        assertThat(exception).hasMessage("regular expression compilation exceeded the 2000 byte memory limit");
                    });
        }
    }

    @Test
    public void testSmallBudgetsCompileOrRejectForMemory()
    {
        // A capture beside a literal and a dot-star takes reservations from the forward DFA
        // allowance, such as a leading-literal scan plan or a literal search, that at small budgets
        // do not all fit. Each that does not fit is left out, so every budget either compiles or
        // rejects for memory, and every budget from the least that compiles compiles.
        List<String> patterns = List.of("(aa).*", "aa(.*)", "(error).*", "(foo)(.*)", "(?s)(abc).*", "(abcdefghijabcdefghij).*", "(abcdefghij)(klmnopqrst).*");
        for (Language language : List.of(Language.RE2, Language.JAVA, Language.RUST, Language.TRINO)) {
            for (String pattern : patterns) {
                RejectedPattern compiled = new RejectedPattern(language, pattern, 0, 0);
                long least = 0;
                for (long maxMemory = 1; maxMemory <= 12 * 1024; maxMemory++) {
                    try {
                        compiled.compile(maxMemory);
                        if (least == 0) {
                            least = maxMemory;
                        }
                    }
                    catch (RegexpCompileMemoryLimitException rejected) {
                        assertThat(least).as("%s rejected at %s after compiling at %s", compiled, maxMemory, least).isZero();
                    }
                }
                assertThat(least).as("%s", compiled).isPositive();
            }
        }
    }

    @Test
    public void testRequiredBudgetRoundsUp()
    {
        assertThat(RegexpCompileMemoryLimitException.formatBudget(1)).isEqualTo("1 byte");
        assertThat(RegexpCompileMemoryLimitException.formatBudget(2)).isEqualTo("2 bytes");
        assertThat(RegexpCompileMemoryLimitException.formatBudget(1023)).isEqualTo("1023 bytes");
        assertThat(RegexpCompileMemoryLimitException.formatBudget(1024)).isEqualTo("1 KiB");
        assertThat(RegexpCompileMemoryLimitException.formatBudget(1025)).isEqualTo("2 KiB");
        assertThat(RegexpCompileMemoryLimitException.formatBudget((1 << 20) - 1024)).isEqualTo("1023 KiB");
        assertThat(RegexpCompileMemoryLimitException.formatBudget((1 << 20) - 1023)).isEqualTo("1 MiB");
        assertThat(RegexpCompileMemoryLimitException.formatBudget(1 << 20)).isEqualTo("1 MiB");
        assertThat(RegexpCompileMemoryLimitException.formatBudget((1 << 20) + 1)).isEqualTo("2 MiB");
    }

    @Test
    public void testBudgetForForwardMemory()
    {
        // The least budget whose forward share, what it keeps past a third for the reverse
        // program, holds the memory.
        long budget = 1;
        for (long forwardMemory = 1; forwardMemory <= 10_000; forwardMemory++) {
            while (Math.max(1, budget - Math.max(1, budget / 3)) < forwardMemory) {
                budget++;
            }
            assertThat(CompiledPattern.budgetForForwardMemory(forwardMemory)).as("%s", forwardMemory).isEqualTo(budget);
        }
        long largestForwardMemory = Long.MAX_VALUE - Long.MAX_VALUE / 3;
        assertThat(CompiledPattern.budgetForForwardMemory(largestForwardMemory)).isEqualTo(Long.MAX_VALUE);
        assertThat(CompiledPattern.budgetForForwardMemory(largestForwardMemory + 1)).isEqualTo(Compiler.NO_BUDGET);
        assertThat(CompiledPattern.budgetForForwardMemory(Long.MAX_VALUE)).isEqualTo(Compiler.NO_BUDGET);
    }

    private static void assertRemainingMemoryAssignedToDfa(String pattern, long prefixScanMemory)
    {
        assertRemainingMemoryAssignedToDfa(pattern, 8192, prefixScanMemory);
    }

    private static void assertRemainingMemoryAssignedToDfa(String pattern, int maxMemory, long prefixScanMemory)
    {
        Prog program = Compiler.compile(parse(pattern), false, maxMemory);

        long programMemory = Compiler.PROGRAM_OVERHEAD_BYTES + ((long) program.size() * Compiler.FLATTENED_INSTRUCTION_BYTES);
        assertThat(program.isOnePass()).isTrue();
        assertThat(program.dfaMemory()).isEqualTo(maxMemory - programMemory - program.onePassMemory() - prefixScanMemory);
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

    private static void assertPrefixLiteralSetYields(LongFunction<CompiledPattern> compile, long leastMaxMemory)
    {
        assertThatThrownBy(() -> compile.apply(leastMaxMemory - 1))
                .isInstanceOfSatisfying(RegexpCompileMemoryLimitException.class, exception -> assertThat(exception.requiredMaxMemoryBytes()).hasValue(leastMaxMemory));
        assertThat(compile.apply(leastMaxMemory).forwardProgramForDiagnostics().prefixAccelStrategy(1_024))
                .isEqualTo(VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_VECTOR : Prog.PrefixAccelStrategy.REPEATED_BYTE);
        assertThat(compile.apply(1 << 20).forwardProgramForDiagnostics().prefixAccelStrategy(1_024))
                .isEqualTo(VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_LITERAL_SET : Prog.PrefixAccelStrategy.REPEATED_BYTE);
    }

    private static void assertPrefixLiteralSetYieldsToDfaMinimum(String pattern, boolean keepsPositionNfa)
    {
        LongFunction<Prog> forwardProgram = maxMemory -> Re2.compile(Slices.utf8Slice(pattern), Re2.Options.defaults().setMaxMemory(maxMemory)).forwardProgramForDiagnostics();
        long low = 1;
        long high = 1 << 20;
        while (low < high) {
            long middle = (low + high) >>> 1;
            if (leavesDfaMinimum(forwardProgram, middle)) {
                high = middle;
            }
            else {
                low = middle + 1;
            }
        }
        Prog program = forwardProgram.apply(low);
        for (long limit = low + (1 << 12); keepsPositionNfa && program.positionNfa() == null && low < limit; ) {
            program = forwardProgram.apply(++low);
        }
        assertThat(program.leavesDfaMinimum(0)).isTrue();
        if (keepsPositionNfa) {
            assertThat(program.positionNfa()).isNotNull();
        }
        assertThat(program.isOnePass()).isTrue();
        assertThat(program.prefixAccelStrategy(1_024))
                .isEqualTo(VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_VECTOR : Prog.PrefixAccelStrategy.REPEATED_BYTE);
        assertThat(forwardProgram.apply(1 << 20).prefixAccelStrategy(1_024))
                .isEqualTo(VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_LITERAL_SET : Prog.PrefixAccelStrategy.REPEATED_BYTE);
    }

    private static boolean leavesDfaMinimum(LongFunction<Prog> forwardProgram, long maxMemory)
    {
        try {
            return forwardProgram.apply(maxMemory).leavesDfaMinimum(0);
        }
        catch (RegexpCompileMemoryLimitException e) {
            return false;
        }
    }

    private static void assertLargestGroupsPlanBoundedPasses(long maxMemory, int groups, IntFunction<String> pattern)
    {
        Prog program = Re2.compile(Slices.utf8Slice(pattern.apply(groups)), Re2.Options.defaults().setMaxMemory(maxMemory)).forwardProgramForDiagnostics();
        assertThat(program.getInstCount(InstOp.CAPTURE)).isEqualTo(program.size() - 2);
        assertThat(program.singlePassCaptureSlots()).isLessThan(2 * groups + 2);
        assertThat(program.boundedPassCaptureSlots()).isGreaterThanOrEqualTo(4);
        // One more group is past what the budget admits.
        assertThatThrownBy(() -> Re2.compile(Slices.utf8Slice(pattern.apply(groups + 1)), Re2.Options.defaults().setMaxMemory(maxMemory)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    private static Regexp parse(String pattern)
    {
        return RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(StandardCharsets.UTF_8)), Regexp.LIKE_PERL).regexp();
    }

    // Every code point from U+10000 through U+4FFFF that a seeded coin keeps, as one class
    // repeated 255 times and followed by a digit.
    private static String supplementaryClassRun()
    {
        Random random = new Random(184_623);
        StringBuilder pattern = new StringBuilder("[");
        for (int codePoint = 0x10000; codePoint <= 0x4FFFF; codePoint++) {
            if (random.nextBoolean()) {
                pattern.appendCodePoint(codePoint);
            }
        }
        return pattern.append("]{255}[0-9]").toString();
    }

    // Distinct lowercase words of three to ten letters, as alternatives.
    private static String randomWords(int count, long seed)
    {
        Random random = new Random(seed);
        Set<String> words = new LinkedHashSet<>();
        while (words.size() < count) {
            int length = 3 + random.nextInt(8);
            StringBuilder word = new StringBuilder();
            for (int i = 0; i < length; i++) {
                word.append((char) ('a' + random.nextInt(26)));
            }
            words.add(word.toString());
        }
        return String.join("|", words);
    }

    private enum Language
    {
        RE2, JAVA, RUST, TRINO, SET
    }

    // A pattern, or a set's patterns, rejected at each of `maxMemories`, reporting `required` and
    // compiling first at `least`. `name` identifies it in a failure.
    private record RejectedPattern(String name, Language language, List<String> patterns, long required, long least, long... maxMemories)
    {
        RejectedPattern(Language language, String pattern, long required, long least, long... maxMemories)
        {
            this(pattern, language, pattern, required, least, maxMemories);
        }

        RejectedPattern(String name, Language language, String pattern, long required, long least, long... maxMemories)
        {
            this(name, language, List.of(pattern), required, least, maxMemories);
        }

        RejectedPattern(Language language, List<String> patterns, long required, long least, long... maxMemories)
        {
            this(String.join(", ", patterns), language, patterns, required, least, maxMemories);
        }

        void compile(long maxMemory)
        {
            Slice pattern = Slices.utf8Slice(patterns.getFirst());
            switch (language) {
                case RE2 -> Re2.compile(pattern, Re2.Options.defaults().setMaxMemory(maxMemory));
                case JAVA -> JavaRegexp.compile(pattern, JavaRegexp.Options.defaults().setMaxMemory(maxMemory));
                case RUST -> RustRegexp.compile(pattern, RustRegexp.Options.defaults().setMaxMemory(maxMemory));
                case TRINO -> TrinoRegexp.compile(pattern, TrinoRegexp.Options.defaults().setMaxMemory(maxMemory));
                case SET -> {
                    Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults().setMaxMemory(maxMemory), Re2Set.MatchMode.FIND);
                    for (String member : patterns) {
                        builder.add(Slices.utf8Slice(member));
                    }
                    builder.build();
                }
            }
        }

        @Override
        public String toString()
        {
            return language + " " + name;
        }
    }
}
