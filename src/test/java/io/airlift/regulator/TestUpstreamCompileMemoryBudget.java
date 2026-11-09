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

import static io.airlift.slice.SizeOf.sizeOf;
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
        // program, and the position automaton and the short-window required bytes take theirs
        // from the forward DFA's.
        long forwardMemory = 4096;
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

    @Test
    public void testRejectionReportsLowerBoundOnRequiredBudget()
    {
        String anchoredPrefix = "^" + "a".repeat(5000) + "(?:b?c?)*d";
        String nestedOptional = "(?:(?:(?:(?:b?)*?){64})*?)+";
        String fixedWidth = "(?:[é中][a-z]){127}[0-9]{2}";
        String fruits = "apple|banana|cherry|durian|elderberry";
        String words = randomWords(65, 7);
        String dictionary = randomWords(3000, 11);
        // Each pattern reports the same figure at every rejected budget listed, a figure whose
        // predecessor fails, and compiles first at the least budget. The literal sets and
        // matchers, the compact counter, and the sets' DFAs are charged for the objects they
        // retain, so their figures assume the default object layout CI runs, with compressed
        // references and class pointers and without compact object headers.
        List<RejectedPattern> rejections = List.of(
                // The instruction limit, estimated from the program the pattern compiles to: past
                // a required prefix and the anchor that drops the search loop, through branches
                // that collapse to nothing, and through repetitions the simplifier squashes.
                new RejectedPattern("anchored prefix", Language.RE2, anchoredPrefix, 2_747, 2_971, 1, 2_000),
                new RejectedPattern("anchored prefix", Language.JAVA, anchoredPrefix, 2_927, 3_287, 1, 2_000),
                new RejectedPattern("anchored prefix", Language.TRINO, anchoredPrefix, 2_927, 3_287, 1, 2_000),
                new RejectedPattern("empty repetitions", Language.RE2, "(?:" + "a{0}b{0}".repeat(9000) + "){1000}", 1_667, 1_667, 1, 1_600),
                new RejectedPattern(Language.JAVA, "(?:a{0}){1000}", 1_667, 1_667, 1, 1_000),
                new RejectedPattern(Language.JAVA, "(?:a|)*", 2_567, 2_567, 1, 2_000),
                new RejectedPattern(Language.JAVA, "^(?:a|)*$", 2_567, 2_567, 1, 2_000),
                new RejectedPattern(Language.RE2, "(?:ab[ab]+)?", 2_387, 2_462, 1, 2_000),
                new RejectedPattern(Language.RUST, "(?:a+){2,4}", 3_107, 3_479, 1, 2_000),
                new RejectedPattern(Language.RE2, "(?:abc){1000}", 541_487, 541_487, 1, 65_536),
                new RejectedPattern(Language.RE2, "((((a))))|(b)(c)", 4_367, 4_367, 1, 2_000),
                // Java compiles a repetition of a body that can match empty with more than its
                // copies.
                new RejectedPattern(Language.JAVA, "(?:(?:a?){10}b){100}", 577_487, 577_487, 2_000, 65_536),
                // A larger budget gets further and learns more: the instruction limit, then the
                // flattened program, which measures exactly what it needs.
                new RejectedPattern(Language.RE2, nestedOptional, 48_107, 1_422_533, 2_000),
                new RejectedPattern(Language.RE2, nestedOptional, 787_247, 1_422_533, 50_000),
                new RejectedPattern(Language.RE2, nestedOptional, 1_422_533, 1_422_533, 800_000),
                // The flattened program fits but leaves too little for a long prefix's shift table.
                new RejectedPattern("long prefix", Language.RE2, "a".repeat(320) + "(?:(?:(?:(?:b?)*?){32})*?)+", 421_049, 421_049, 100_000, 419_093),
                // A compact counter in place of a program measures exactly what it needs.
                new RejectedPattern(Language.RE2, "\\p{L}{100,200}", 50_987, 50_987, 1, 8_000),
                // The figure covers every route: the literal set that compiles without a program,
                // and Trino's literal alternation, literal set and fixed-width matcher.
                new RejectedPattern(Language.RE2, fruits, 8_147, 8_147, 1, 2_000),
                new RejectedPattern("65 words", Language.RE2, words, 10_249, 10_249, 1, 2_000),
                new RejectedPattern("65 words", Language.JAVA, words, 10_249, 10_249, 1, 2_000),
                new RejectedPattern("65 words", Language.RUST, words, 10_249, 10_249, 1, 2_000),
                new RejectedPattern("3,000 words", Language.RUST, dictionary, 271_981, 271_981, 1_000, 200_000),
                // Trino's literal alternation builds its start filter only with vector support.
                new RejectedPattern(Language.TRINO, fruits, VectorSupport.isAvailable() ? 4_151 : 1_487, VectorSupport.isAvailable() ? 4_151 : 1_487, 1, 1_000),
                new RejectedPattern("65 words", Language.TRINO, words, 9_227, 9_227, 1, 2_000),
                new RejectedPattern("3,000 words", Language.TRINO, dictionary, 269_843, 269_843, 1_000, 200_000),
                new RejectedPattern(Language.TRINO, fixedWidth, 9_613, 9_613, 1, 2_000),
                new RejectedPattern(Language.RE2, fixedWidth, 161_867, 161_867, 1, 2_000),
                // 255 copies of a class of supplementary code points need more instructions than
                // any program holds, but Trino's fixed-width matcher compiles them.
                new RejectedPattern("supplementary class run", Language.TRINO, supplementaryClassRun(), 793_525, 793_525, 2_000),
                // A set reports its many-match DFA's start only after its program compiled.
                new RejectedPattern(Language.SET, List.of("foo"), 1_352, 4_846, 1),
                new RejectedPattern(Language.SET, List.of("foo"), 4_846, 4_846, 2_000),
                new RejectedPattern(Language.SET, List.of("foo", "ba[rz]+"), 2_312, 6_412, 1, 2_000),
                new RejectedPattern(Language.SET, List.of("[a-z]{5,500}"), 120_392, 184_318, 2_000, 50_000),
                new RejectedPattern(Language.SET, List.of("(a?)(?:(?:(?:(?:a?)*?){16})*?)+(b)"), 64_470, 85_086, 20_000));
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
    public void testRequiredBudgetRoundsDown()
    {
        assertThat(RegexpCompileMemoryLimitException.formatBudget(1)).isEqualTo("1 byte");
        assertThat(RegexpCompileMemoryLimitException.formatBudget(2)).isEqualTo("2 bytes");
        assertThat(RegexpCompileMemoryLimitException.formatBudget(1023)).isEqualTo("1023 bytes");
        assertThat(RegexpCompileMemoryLimitException.formatBudget(1024)).isEqualTo("1 KiB");
        assertThat(RegexpCompileMemoryLimitException.formatBudget(2047)).isEqualTo("1 KiB");
        assertThat(RegexpCompileMemoryLimitException.formatBudget((1 << 20) - 1)).isEqualTo("1023 KiB");
        assertThat(RegexpCompileMemoryLimitException.formatBudget(1 << 20)).isEqualTo("1 MiB");
        assertThat(RegexpCompileMemoryLimitException.formatBudget((2 << 20) - 1)).isEqualTo("1 MiB");
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
