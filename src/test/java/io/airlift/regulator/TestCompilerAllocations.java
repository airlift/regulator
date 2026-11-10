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
import java.lang.management.MemoryMXBean;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static io.airlift.regulator.Prefilter.PrefilterOp.OR;
import static io.airlift.regulator.TestingAllocations.MAXIMUM_WINDOW_RUNS;
import static io.airlift.regulator.TestingAllocations.assertAllocatesAtMostOnceCompiled;
import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TestCompilerAllocations
{
    @Test
    public void testExpressionAnalysisAvoidsPerNodeStreamAllocation()
    {
        Regexp regexp = RegexpParser.parse(utf8Slice("(.*)-(\\d+)-of-(\\d+)"), Regexp.LIKE_PERL).regexp();
        Regexp normalized = Simplifier.simplify(regexp, CaseFolding.RE2);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            ExpressionAnalysis.analyzeNormalized(normalized, CaseFolding.RE2);
        }

        int iterations = 10_000;
        assertAllocatesAtMostOnceCompiled("testExpressionAnalysisAvoidsPerNodeStreamAllocation", MAXIMUM_WINDOW_RUNS, iterations, 4_000L * iterations - 1, () -> {
            long analysisCount = 0;
            for (int iteration = 0; iteration < iterations; iteration++) {
                analysisCount += ExpressionAnalysis.analyzeNormalized(normalized, CaseFolding.RE2) != null ? 1 : 0;
            }
            return analysisCount;
        });
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
    public void testAlternationsNestedInSingleCountsFlattenOnce()
    {
        // Parsing keeps x{1} in every language but Rust, so it cannot flatten an alternation
        // nested in one. Simplification drops the count, and it flattened such a chain by copying
        // every level below at each level, and merged a class growing level after level again at
        // each. Rust's parser factors the first pattern below into alternations of empty matches
        // nested as deep, which simplification flattened the same way. At 4,000 levels, every
        // compile but Rust's of the flags and code-point patterns, which Rust's parser already
        // flattens, allocated 3.5 to 13 KB per pattern byte; flattened once, about 200 to 600
        // bytes per pattern byte.
        record Chain(String pattern, String deepest) {}

        record Language(String name, Function<Slice, CompiledPattern> compile) {}

        int depth = 4_000;
        StringBuilder flagLevels = new StringBuilder();
        StringBuilder runeLevels = new StringBuilder();
        for (int level = 0; level < depth; level++) {
            String flags = (level % 2 == 0) ? "i" : "-i";
            flagLevels.append("zz|(?").append(flags).append(":a){1}|(?").append(flags).append(":b){1}|zz|(?").append(flags).append(":");
            runeLevels.append("\\x{").append(Integer.toHexString(0x100 + 2 * level)).append("}|(?:");
        }
        String closes = "){1}".repeat(depth);
        List<Chain> chains = List.of(
                // Alternatives that stay apart.
                new Chain("aa|(?:".repeat(depth) + "bb" + closes, "bb"),
                // Literals whose flags differ from their own alternation's but match the enclosing
                // one's, which merges them.
                new Chain(flagLevels + "bb" + closes, "bb"),
                // A class that takes in one more code point at each level.
                new Chain(runeLevels + "x" + closes, "x"));
        List<Language> languages = List.of(
                new Language("RE2", Re2::compile),
                new Language("Java", JavaRegexp::compile),
                new Language("Trino", pattern -> TrinoRegexp.compile(pattern).re2()),
                new Language("Rust", pattern -> RustRegexp.compile(pattern, RustRegexp.Options.defaults().setNestLimit(10 * depth))));
        ThreadMXBean threadBean = allocatedMemoryBean();
        long threadId = Thread.currentThread().threadId();
        for (Language language : languages) {
            // A first compile loads and initializes the classes the language's compile uses, so
            // that only the compile itself is measured.
            assertThat(language.compile().apply(utf8Slice(chains.getFirst().pattern()))).isNotNull();
            for (Chain chain : chains) {
                Slice slice = utf8Slice(chain.pattern());
                long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
                CompiledPattern compiled = language.compile().apply(slice);
                long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
                String description = language.name() + " " + chain.pattern().substring(0, 20);
                assertThat(allocatedBytes).as(description).isLessThan(1_000L * slice.length());
                assertThat(compiled.matches(utf8Slice(chain.deepest()))).as(description).isTrue();
            }
        }
    }

    @Test
    public void testRequiredSuffixStaysLinearInConcatenatedCaptures()
    {
        ThreadMXBean threadBean = allocatedMemoryBean();
        // Putting each capture's bytes before the bytes collected after it, by copying them all,
        // allocated about 100 MB for these 10,000 captures, of which the suffix keeps only the
        // last 64 bytes.
        Regexp regexp = RegexpParser.parse(utf8Slice("(ab)".repeat(10_000)), Regexp.LIKE_PERL).regexp();
        Regexp normalized = Simplifier.simplify(regexp, CaseFolding.RE2);

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        byte[] suffix = RequiredBytes.requiredSuffix(normalized);
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(suffix).isEqualTo("ab".repeat(32).getBytes(UTF_8));
        assertThat(allocatedBytes).isLessThan(1_000_000);
    }

    @Test
    public void testPrefilterBuildStaysLinear()
    {
        // Joining a literal's runes one at a time, or a concatenation's one-string children one at
        // a time, copies the whole string at each step: about 210 MB for this 20,000-rune literal
        // and 110 MB for these 10,000 captures. Merging an alternation's sets into a new set at
        // each alternative copies the whole set: about 320 MB for these 4,000 alternatives.
        // Merging each nested OR into its parent copies the whole growing child list: about 30 MB
        // for these 1,600 levels.
        Prefilter literal = prefilterWithinAllocation("ab".repeat(10_000), 16_000_000);
        assertThat(literal.atom()).isEqualTo(utf8Slice("ab".repeat(10_000)));

        Prefilter captures = prefilterWithinAllocation("(ab)".repeat(10_000), 16_000_000);
        assertThat(captures.atom()).isEqualTo(utf8Slice("ab".repeat(10_000)));

        // Distinct first characters keep the parser from factoring the alternatives, and the
        // trailing q keeps the single-rune alternatives from merging into one character class.
        String alternation = IntStream.range(0, 4_000)
                .mapToObj(index -> Character.toString(0x4E00 + index) + "q")
                .collect(joining("|"));
        Prefilter alternatives = prefilterWithinAllocation(alternation, 16_000_000);
        assertThat(alternatives.op()).isEqualTo(OR);
        assertThat(alternatives.children()).hasSize(4_000);

        Prefilter guards = prefilterWithinAllocation("aaa.*|bbb.*|(".repeat(1_600) + "ccc.*" + ")".repeat(1_600), 24_000_000);
        assertThat(guards.op()).isEqualTo(OR);
        assertThat(guards.children()).hasSize(3_201);
        assertThat(guards.children().getFirst().atom()).isEqualTo(utf8Slice("aaa"));
        assertThat(guards.children().getLast().atom()).isEqualTo(utf8Slice("ccc"));
    }

    @Test
    public void testFlatteningReusesHintScratchSpace()
    {
        Regexp regexp = RegexpParser.parse(utf8Slice("ABCDEFGHIJKLMNOPQRSTUVWXYZ$"), Regexp.LIKE_PERL).regexp();
        for (int iteration = 0; iteration < 10_000; iteration++) {
            Compiler.compile(regexp);
        }

        int iterations = 10_000;
        assertAllocatesAtMostOnceCompiled("testFlatteningReusesHintScratchSpace", MAXIMUM_WINDOW_RUNS, 300_000, 40_000L * iterations - 1, () -> {
            long instructionCount = 0;
            for (int iteration = 0; iteration < iterations; iteration++) {
                instructionCount += Compiler.compile(regexp).size();
            }
            return instructionCount;
        });
    }

    @Test
    public void testFlattenedInstructionLimitFitsPackedOutputs()
    {
        // Packed capture instruction words hold an output in 28 bits, so no budget may let a
        // flattened program index past that.
        assertThat(Compiler.maxFlattenedInstructions(Long.MAX_VALUE)).isEqualTo((1 << 28) - 1);
        assertThat(Compiler.maxFlattenedInstructions(0)).isEqualTo((1 << 28) - 1);
        assertThat(Compiler.maxFlattenedInstructions(96L << 20)).isEqualTo(((96L << 20) - Compiler.PROGRAM_OVERHEAD_BYTES) / Compiler.FLATTENED_INSTRUCTION_BYTES);
    }

    @Test
    public void testInstructionChargesCoverWhatAProgramHolds()
            throws ReflectiveOperationException
    {
        // Before flattening, an instruction is charged for its word in the old array and its
        // doubled replacement, three words per instruction, and for the flattening pass's scratch.
        // Once flattened, it is charged for its word in the program and in the block flattening
        // emits it into, its output list id and the tables the program keeps for searches. The
        // allocations of flattening and of building those tables below check them.

        ThreadMXBean threadBean = allocatedMemoryBean();
        long threadId = Thread.currentThread().threadId();
        // A literal flattens to one list per instruction, and the alternations give instructions
        // two predecessors. The nests compile to little more than alternations, each adding two
        // predecessor edges, and where a nullable loop keeps its priority they make nearly every
        // instruction a root.
        String optionalsAndLazyLoops = "a";
        String optionalsOfAlternatingGreed = "a";
        for (int depth = 0; depth < 1025; depth++) {
            optionalsAndLazyLoops = "(?:" + optionalsAndLazyLoops + ")" + (depth % 2 == 0 ? "?" : "+?");
            optionalsOfAlternatingGreed = "(?:" + optionalsOfAlternatingGreed + ")" + (depth % 2 == 0 ? "?" : "??");
        }
        Slice nested = utf8Slice(optionalsAndLazyLoops);

        record Case(String name, ParseResult parsed, Compiler.Dialect dialect) {}

        List<Case> cases = List.of(
                new Case("literal", RegexpParser.parse(utf8Slice("a".repeat(20_000)), Regexp.LIKE_PERL), Compiler.Dialect.RE2),
                new Case("alternations", RegexpParser.parse(utf8Slice("(?:ab|cd)".repeat(10_000)), Regexp.LIKE_PERL), Compiler.Dialect.RE2),
                new Case("RE2 optionals and lazy loops", RegexpParser.parse(nested, Regexp.LIKE_PERL), Compiler.Dialect.RE2),
                new Case("Java optionals and lazy loops", JavaRegexpParser.parse(nested, JavaRegexp.Options.defaults().parseFlags()), Compiler.Dialect.RE2),
                new Case("Trino optionals and lazy loops", TrinoRegexpParser.parse(nested, Regexp.LIKE_PERL), Compiler.Dialect.TRINO),
                new Case("Rust optionals and lazy loops", RustRegexpParser.parse(nested, RustRegexp.Options.defaults().syntaxFlags(), 10_000), Compiler.Dialect.RUST),
                new Case("Rust lazy loop of optionals of alternating greed", RustRegexpParser.parse(utf8Slice("(?:" + optionalsOfAlternatingGreed + ")+?"), RustRegexp.Options.defaults().syntaxFlags(), 10_000), Compiler.Dialect.RUST));
        for (Case scratchCase : cases) {
            CaseFolding caseFolding = scratchCase.parsed().caseFolding();
            Regexp regexp = Simplifier.simplify(scratchCase.parsed().regexp(), caseFolding);
            long scratchBytes = Long.MAX_VALUE;
            long tablesBytes = Long.MAX_VALUE;
            long wordsBytes = Long.MAX_VALUE;
            for (int run = 0; run < 5; run++) {
                Prog program = Compiler.compileNormalizedForBenchmark(regexp, false, 64 << 20, Compiler.CompileStage.OPTIMIZED, scratchCase.dialect(), caseFolding);
                int unflattenedSize = program.size();

                long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
                program.flatten();
                long flattenBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
                int size = program.size();
                assertThat(program.outputLists()).hasSize(size);
                // Flattening allocates the copies' words in their blocks and in the program's array, their output
                // list ids and its scratch.
                long copiesBytes = size * (2L * Long.BYTES + Short.BYTES);
                scratchBytes = Math.min(scratchBytes, (flattenBytes - copiesBytes) / unflattenedSize);

                allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
                program.getOrCreateNoSubmatchTables();
                tablesBytes = Math.min(tablesBytes, threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore - (31L * size));

                allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
                program.getOrCreateCaptureInstructionWords();
                wordsBytes = Math.min(wordsBytes, threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore);
            }
            assertThat(scratchBytes).as("scratch bytes per instruction for %s", scratchCase.name()).isLessThanOrEqualTo(48);
            // What remains past the per-instruction bytes is array headers.
            assertThat(tablesBytes).isLessThan(1024);
            // The capture engines read the program's own words, which flattening built.
            assertThat(wordsBytes).isLessThan(64);
        }

        // Trino splits each greedy nullable loop's empty paths off its body for the empty-iteration
        // check, so nested empty loops compile to 67 instructions that flatten to 42. The program
        // keeps an instruction array of exactly its flattened size.
        Prog shrunk = TrinoRegexp.compile(utf8Slice("(".repeat(13) + ")" + ")*".repeat(12))).re2().forwardProgramForDiagnostics();
        Field instructions = Prog.class.getDeclaredField("words");
        instructions.setAccessible(true);
        assertThat(((long[]) instructions.get(shrunk)).length).isEqualTo(shrunk.size());
    }

    @Test
    public void testFlattenedProgramsAreChargedAgainstTheBudget()
    {
        // Flattening copies each list's epsilon closure, and a lazy loop over a nullable body puts
        // the loops inside it in many closures, so the flattened program grows with the square of
        // the program the instruction limit bounds. Unchecked, the a? pattern allocated 309 MB to
        // compile. Oversized flattened programs stop at the forward program's share of the
        // default budget; RE2 reaches it here.
        String nullableLoops = "(?:(?:(?:(?:a?)*?){1000})*?)+";
        assertCompileRejectedWithinBudget(() -> Re2.compile(utf8Slice(nullableLoops)));
        // Rust gives each alternation on a loop's cycle a list of its own, so no closure runs
        // through the loops inside it and the program flattens to about 6,009 instructions.
        assertThat(RustRegexp.compile(utf8Slice(nullableLoops)).forwardProgramForDiagnostics().size()).isLessThan(7_000);
        // Java loops and counts end on an iteration that consumes nothing, so the closures stay
        // apart, and the pattern compiles within the budget, written out too.
        assertThat(JavaRegexp.compile(utf8Slice(nullableLoops)).matcher(utf8Slice("aaa")).matches()).isTrue();
        String writtenOutNullableLoops = "(?:(?:" + "(?:a?)*?".repeat(1000) + ")*?)+";
        assertThat(JavaRegexp.compile(utf8Slice(writtenOutNullableLoops)).matcher(utf8Slice("aaa")).matches()).isTrue();
        // Trino loops end the same way, so a lazy +? that Joni's quantifier reduction keeps under
        // a greedy * compiles within the budget too.
        assertThatCode(() -> TrinoRegexp.compile(utf8Slice("(?:(?:(?:(?:a|b?)*?){1000})+?)*"))).doesNotThrowAnyException();
    }

    @Test
    public void testRepetitionIsRejectedBeforeItExpands()
    {
        // Simplification expanded counted repetition before the compiler checked its limits, so
        // this 36 KB pattern, far past them, exhausted the heap before it could be rejected. Each
        // language now counts the expansion as it builds it, and rejects the pattern before
        // expanding past the limits of its budget.
        String optionalRuns = optionalRuns(4_000);
        long maxMemory = 8 << 20;
        long maxAllocatedBytes = 32_000_000;
        assertCompileRejectedWithin(() -> Re2.compile(utf8Slice(optionalRuns), Re2.Options.defaults().setMaxMemory(maxMemory)), maxAllocatedBytes);
        assertCompileRejectedWithin(() -> JavaRegexp.compile(utf8Slice(optionalRuns), JavaRegexp.Options.defaults().setMaxMemory(maxMemory)), maxAllocatedBytes);
        assertCompileRejectedWithin(() -> RustRegexp.compile(utf8Slice(optionalRuns), RustRegexp.Options.defaults().setMaxMemory(maxMemory)), maxAllocatedBytes);
        assertCompileRejectedWithin(() -> TrinoRegexp.compile(utf8Slice(optionalRuns), TrinoRegexp.Options.defaults().setMaxMemory(maxMemory)), maxAllocatedBytes);
        // A required literal prefix splits the runs off as the suffix, and the runs are still
        // rejected before the entire expression is simplified.
        assertCompileRejectedWithin(() -> Re2.compile(utf8Slice("^abc" + optionalRuns), Re2.Options.defaults().setMaxMemory(maxMemory)), maxAllocatedBytes);
        // Capturing groups nested past the simplifier's recursion limit reach the runs through its
        // iterative walk. Non-capturing groups would leave no node to nest.
        String nestedRuns = "(".repeat(300) + optionalRuns + ")".repeat(300);
        assertCompileRejectedWithin(() -> Re2.compile(utf8Slice(nestedRuns), Re2.Options.defaults().setMaxMemory(maxMemory)), maxAllocatedBytes);
        // A case-folded literal still compiles each rune to at least one instruction, so its
        // copies count as other literal runes do.
        String foldedRuns = "(?i:" + "f".repeat(10_000) + "){1000}";
        assertCompileRejectedWithin(() -> TrinoRegexp.compile(utf8Slice(foldedRuns), TrinoRegexp.Options.defaults().setMaxMemory(maxMemory)), maxAllocatedBytes);
        // Adjacent repetitions coalesce into one, here a{0,700000}. That count is past the RE2,
        // Java, and Trino parsers' repetition limit, and the repetition fails before any copy of it
        // is built.
        String coalescedRuns = "a{0,1000}".repeat(700);
        assertCompileRejectedWithin(() -> Re2.compile(utf8Slice(coalescedRuns)), maxAllocatedBytes);
        assertCompileRejectedWithin(() -> JavaRegexp.compile(utf8Slice(coalescedRuns)), maxAllocatedBytes);
        assertCompileRejectedWithin(() -> RustRegexp.compile(utf8Slice(coalescedRuns)), maxAllocatedBytes);
        assertCompileRejectedWithin(() -> TrinoRegexp.compile(utf8Slice(coalescedRuns)), maxAllocatedBytes);
        // Rust's parser takes any count, as the crate does, so the budget alone bounds one. This
        // count is under the instruction limit, and the question mark and concatenation around
        // each optional copy decide it.
        assertCompileRejectedWithin(() -> RustRegexp.compile(utf8Slice("a{0,500000}")), maxAllocatedBytes);
        // Nested counts multiply, here into a billion copies of a.
        assertCompileRejectedWithin(() -> RustRegexp.compile(utf8Slice("(?:(?:a{1000}){1000}){1000}")), maxAllocatedBytes);
        // A small budget rejects a short pattern that coalesces into one long repeat.
        assertCompileRejectedWithin(() -> Re2.compile(utf8Slice("a{0,1000}".repeat(256)), Re2.Options.defaults().setMaxMemory(4_096)), maxAllocatedBytes);
        // Copies of a large repeated expression count at its full size. Here ten groups of 200
        // two-rune branches and a letter, (?:一q|丁q|...|a){0,1000}(?:...|b){0,1000}...
        String alternationRuns = optionalGroups(10, group -> IntStream.range(0, 200)
                .mapToObj(branch -> Character.toString(0x4E00 + group * 200 + branch) + "q")
                .collect(joining("|", "", "|" + (char) ('a' + group))));
        assertCompileRejectedWithin(() -> Re2.compile(utf8Slice(alternationRuns)), maxAllocatedBytes);

        // Repetition that simplification discards is never expanded, so it neither counts nor
        // fills the heap: under x{0}, or in a branch that never matches. The classes never match,
        // so their branch discards the runs in front of them. Rust's parser drops the branch.
        String discardedRuns = "(?:" + optionalRuns + "(?:[^\\x00-\\x{10FFFF}]|[^\\x00-\\x{10FFFF}]){1})|z";
        ThreadMXBean threadBean = allocatedMemoryBean();
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        assertThat(Re2.compile(utf8Slice("(?:" + optionalRuns + "){0}x")).matches(utf8Slice("x"))).isTrue();
        Re2 re2 = Re2.compile(utf8Slice(discardedRuns), Re2.Options.defaults().setMaxMemory(maxMemory));
        assertThat(re2.find(utf8Slice("xzx"))).isTrue();
        assertThat(re2.find(utf8Slice("abc"))).isFalse();
        JavaRegexp java = JavaRegexp.compile(utf8Slice(discardedRuns), JavaRegexp.Options.defaults().setMaxMemory(maxMemory));
        assertThat(java.find(utf8Slice("xzx"))).isTrue();
        assertThat(java.find(utf8Slice("abc"))).isFalse();
        TrinoRegexp trino = TrinoRegexp.compile(utf8Slice(discardedRuns), TrinoRegexp.Options.defaults().setMaxMemory(maxMemory));
        assertThat(trino.contains(utf8Slice("xzx"))).isTrue();
        assertThat(trino.contains(utf8Slice("abc"))).isFalse();
        // A required literal prefix splits the branch off as the suffix, and the entire
        // expression, simplified without a budget, does not expand it either.
        Re2 prefixed = Re2.compile(utf8Slice("^abc(?:" + discardedRuns + ")"), Re2.Options.defaults().setMaxMemory(maxMemory));
        assertThat(prefixed.find(utf8Slice("abcz"))).isTrue();
        // A small budget holds the alternative beside a branch that discards a{0,700000}.
        Re2 small = Re2.compile(utf8Slice("(?:" + coalescedRuns + "(?:[^\\x00-\\x{10FFFF}]){1}|abc)"), Re2.Options.defaults().setMaxMemory(5_120));
        assertThat(small.matches(utf8Slice("abc"))).isTrue();
        assertThat(small.matches(utf8Slice("a"))).isFalse();
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(allocatedBytes).isLessThan(maxAllocatedBytes);
    }

    @Test
    public void testCompiledPatternsRetainAboutTheirBudget()
    {
        // Each \PL{0} keeps a fresh 695-range complement in the parse tree, though simplification
        // drops it, so 60 KB of pattern held 197 MB of tree at a 64 KB budget. A compiled pattern
        // keeps no parse tree: its 60 KB text and a program within the 43 KB forward share retain
        // about 61 KB, and 1 MB leaves room for what a full collection leaves uncounted.
        String negatedClasses = "\\PL{0}".repeat(10_000);
        assertThat(retainedHeapBytes(() -> Re2.compile(utf8Slice(negatedClasses), Re2.Options.defaults().setMaxMemory(65_536)))).isLessThan(1 << 20);
        assertThat(retainedHeapBytes(() -> JavaRegexp.compile(utf8Slice("\\P{L}{0}".repeat(10_000)), JavaRegexp.Options.defaults().setMaxMemory(65_536)))).isLessThan(1 << 20);
        assertThat(retainedHeapBytes(() -> TrinoRegexp.compile(utf8Slice(negatedClasses), TrinoRegexp.Options.defaults().setMaxMemory(65_536)))).isLessThan(1 << 20);

        // The reverse program's input, a 7.2 MB simplified expression, is charged to the 5.6 MB
        // reverse share, so it is not kept beside the 6.1 MB forward program and 6.3 MB tree that
        // held 19.7 MB in all at a 16 MiB budget.
        assertThat(retainedHeapBytes(() -> Re2.compile(utf8Slice("a?".repeat(45_000)), Re2.Options.defaults().setMaxMemory(16 << 20)))).isLessThan(16 << 20);
    }

    @Test
    public void testReverseProgramInputIsKeptOnlyWhileItFitsTheReverseShare()
    {
        // A 7.2 MB simplified expression does not fit the 5.6 MB reverse share at 16 MiB, so the
        // pattern does not keep it.
        Re2 oversized = Re2.compile(utf8Slice("a?".repeat(45_000)), Re2.Options.defaults().setMaxMemory(16 << 20));
        assertThat(oversized.hasReverseProgramInputForDiagnostics()).isFalse();
        assertThat(oversized.reverseProgramSize()).isEqualTo(-1);

        // At a 96 MiB budget a 14 MB input and the reverse program compiled from it each fit the
        // 32 MiB reverse share, though not together. The program replaces its input, so it needs
        // the share only for itself.
        Re2 admitted = Re2.compile(utf8Slice("a?".repeat(90_000)), Re2.Options.defaults().setMaxMemory(96L << 20));
        assertThat(admitted.hasReverseProgramInputForDiagnostics()).isTrue();
        assertThat(admitted.reverseProgramSize()).isPositive();
        assertThat(admitted.hasReverseProgramInputForDiagnostics()).isFalse();
    }

    @Test
    public void testJavaCountedCopiesAreChargedAgainstTheInstructionBudget()
    {
        // A Java count over a nullable body compiles a copy of the body per iteration, so a wide
        // body stops at the instruction budget.
        String wideNullableBody = "a?|b?|c?|d?|e?|f?|g?|h?|i?|j?|k?|l?|m?|n?|o?|p?|q?|r?|s?|t?|u?|v?|w?|x?|y?|z?|".repeat(8) + "A?";
        assertCompileRejectedWithinBudget(() -> JavaRegexp.compile(utf8Slice("(?:" + wideNullableBody + "){1000}")));

        // Each copy splits off its paths that consume nothing. Cloning every instruction before
        // the first byte left originals no path reaches, and gave each copy its own exit, so the
        // nested counts needed a 1.31 MB budget, four times what RE2 needs for them; they now
        // fit in 0.8 MB.
        JavaRegexp nestedCounts = JavaRegexp.compile(utf8Slice("(?:(?:(?:a?){2,}){30,49}){1,8}"), JavaRegexp.Options.defaults().setMaxMemory(800_000));
        assertThat(nestedCounts.matcher(utf8Slice("a".repeat(60))).matches()).isTrue();

        // A body that matches only the empty string ends the repetition on its first iteration,
        // so its count compiles one copy. A thousand copies of \b+, which is not a lone assertion,
        // needed a 0.9 MB budget; one fits in 5 KB.
        JavaRegexp zeroWidthBody = JavaRegexp.compile(utf8Slice("a(?:\\b+){1000}"), JavaRegexp.Options.defaults().setMaxMemory(5_120));
        assertThat(zeroWidthBody.matcher(utf8Slice("a")).matches()).isTrue();
    }

    @Test
    public void testScanPlanContinuationAnalysisUsesCompactStorage()
    {
        ParseResult parsed = TrinoRegexpParser.parse(
                utf8Slice("^((a)?(b)?(c)?(d)?)abcd([^/]+)/$"),
                Regexp.LIKE_PERL);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            assertThat(ScanPlan.analyze(parsed.regexp(), parsed.capturingGroupCount(), ScanPlan.Language.TRINO, false, parsed.caseFolding(), ByteFrequencies.uniform())).isNotNull();
        }

        int iterations = 10_000;
        assertAllocatesAtMostOnceCompiled("testScanPlanContinuationAnalysisUsesCompactStorage", MAXIMUM_WINDOW_RUNS, iterations, 8_000L * iterations - 1, () -> {
            long planCount = 0;
            for (int iteration = 0; iteration < iterations; iteration++) {
                planCount += ScanPlan.analyze(parsed.regexp(), parsed.capturingGroupCount(), ScanPlan.Language.TRINO, false, parsed.caseFolding(), ByteFrequencies.uniform()) != null ? 1 : 0;
            }
            return planCount;
        });
    }

    @Test
    public void testScanPlanRejectsUnsupportedUnanchoredStartBeforeAllocation()
    {
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

        long analyses = 10_000L * expressions.size();
        assertAllocatesAtMostOnceCompiled("testScanPlanRejectsUnsupportedUnanchoredStartBeforeAllocation", MAXIMUM_WINDOW_RUNS, analyses, 128 * analyses - 1, () -> {
            long rejectedCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                for (ParseResult expression : expressions) {
                    rejectedCount += ScanPlan.analyze(expression.regexp(), expression.capturingGroupCount(), ScanPlan.Language.TRINO, false, expression.caseFolding(), ByteFrequencies.uniform()) == null ? 1 : 0;
                }
            }
            return rejectedCount;
        });
    }

    @Test
    public void testSimpleLikePatternsCopyOnlyTheirLiteral()
    {
        // A simple pattern copies only its literal, and a contains kernel holds that copy and, for
        // a literal whose first byte does not recur, the shared shift table.
        List<Slice> patterns = List.of(utf8Slice("https://%"), utf8Slice("%.example.com"), utf8Slice("%coolfunctionname%"));
        for (int iteration = 0; iteration < 10_000; iteration++) {
            for (Slice pattern : patterns) {
                assertThat(TrinoLikePattern.compile(pattern).pattern()).isEqualTo(pattern);
            }
        }

        long compiles = 10_000L * patterns.size();
        assertAllocatesAtMostOnceCompiled("testSimpleLikePatternsCopyOnlyTheirLiteral", MAXIMUM_WINDOW_RUNS, compiles, 232 * compiles - 1, () -> {
            long compiledCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                for (Slice pattern : patterns) {
                    compiledCount += TrinoLikePattern.compile(pattern).matches(pattern) ? 1 : 0;
                }
            }
            return compiledCount;
        });
    }

    @Test
    public void testTrinoSiblingNullableLoopsAllocateWithTheirProgram()
    {
        // The program grows linearly with the loops, and the clone table and walk set must not
        // grow with the square of the loop count.
        assertTrinoCompileAllocation("(?:a|)*".repeat(4_000), 8_003, 32_000_000);
    }

    @Test
    public void testTrinoNestedNullableLoopsAllocateWithTheirProgram()
    {
        // Each loop, greedy or lazy, clones the epsilon-reachable part of the loops inside it, so
        // the program grows with the square of the depth, and the clone table and walk set must
        // not grow with the depth cubed.
        String pattern = "(?:^|a?)";
        for (int depth = 0; depth < 320; depth++) {
            pattern = "(?:a?|" + pattern + ")*" + (depth % 2 == 0 ? "" : "?");
        }
        assertTrinoCompileAllocation(pattern, 52_324, 128_000_000);
    }

    @Test
    public void testTrinoLiteralExpansionStaysWithinBudget()
    {
        // Each of the 120 levels prefixes ab to every word below it, so the literal set's
        // extraction copied about 11 MB of literals before its result was checked against a
        // 4 KiB budget, and now gives up once what it builds passes 8 times the forward share.
        String words = IntStream.range(0, 500).mapToObj(word -> "w" + (1_000 + word)).collect(joining("|"));
        Slice pattern = utf8Slice("ab(?:".repeat(120) + words + "|q)".repeat(120));
        TrinoRegexp.Options options = TrinoRegexp.Options.defaults().setMaxMemory(4_096);

        ThreadMXBean threadBean = allocatedMemoryBean();
        long threadId = Thread.currentThread().threadId();
        long allocatedBytes = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
            assertThatThrownBy(() -> TrinoRegexp.compile(pattern, options)).isInstanceOf(RegexpCompileMemoryLimitException.class);
            allocatedBytes = Math.min(allocatedBytes, threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore);
        }

        // The parse and the rejected program allocate about 300 bytes per pattern byte, and the
        // extraction a few times the budget, so 1 KB per pattern byte holds the compile.
        assertThat(allocatedBytes).isLessThan(1_024L * pattern.length());
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
    public void testLatin1FoldedClassesAreRejectedWithinBudget()
    {
        // Case folding in a Latin-1 pattern added each of the 158,172 letters of \p{L} one at a
        // time before the class was clipped to Latin-1, about 3.8 MB per class, so these 100
        // classes allocated 381 MB before the memory limit applied. Latin-1 folding now adds each
        // range once, a few tens of KB per class. One class compiles first so loading the shared
        // Unicode tables is not counted.
        ThreadMXBean threadBean = allocatedMemoryBean();
        Re2.compile(utf8Slice("(?i)\\p{L}"), Re2.Options.latin1());
        Slice pattern = utf8Slice("(?i)" + "\\p{L}".repeat(100));

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        assertThatThrownBy(() -> Re2.compile(pattern, Re2.Options.latin1().setMaxMemory(64 << 10))).isInstanceOf(RegexpCompileMemoryLimitException.class);
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(allocatedBytes).isLessThan(8_000_000);
    }

    @Test
    public void testLargeUnicodeClassCompileAllocation()
    {
        // Unicode \w compiles to hundreds of byte ranges. The ceiling guards the combined savings of
        // the byte-map, member-table and one-pass changes: a compile allocates about 1.56 MB, and
        // about 1.67 MB when C2 has not applied escape analysis, so it leaves about 10% over the
        // larger. About 300 KB of that is the one-pass step table, 8 bytes for each of the
        // program's 37,500 actions. C1 code allocates about 1.755 MB, also within the ceiling.
        assumeTrue(((ThreadMXBean) ManagementFactory.getThreadMXBean()).isThreadAllocatedMemorySupported(), "requires thread allocation counting");
        Slice pattern = utf8Slice("^(\\w+)-(\\d+)/");
        for (int iteration = 0; iteration < 1_000; iteration++) {
            assertThat(RustRegexp.compile(pattern).pattern()).isEqualTo(pattern);
        }

        int iterations = 100;
        assertAllocatesAtMostOnceCompiled("testLargeUnicodeClassCompileAllocation", MAXIMUM_WINDOW_RUNS, iterations, 1_840_000L * iterations - 1, () -> {
            long compiledCount = 0;
            for (int iteration = 0; iteration < iterations; iteration++) {
                compiledCount += RustRegexp.compile(pattern).pattern().equals(pattern) ? 1 : 0;
            }
            return compiledCount;
        });
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

    private static Prefilter prefilterWithinAllocation(String pattern, long maxAllocatedBytes)
    {
        ThreadMXBean threadBean = allocatedMemoryBean();
        Regexp parsed = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        Prefilter prefilter = Prefilter.fromRegexp(parsed);
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(allocatedBytes).as(pattern.substring(0, 8)).isLessThan(maxAllocatedBytes);
        return prefilter;
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
        // The rejected compile builds at most the forward share of the budget, plus its scratch.
        assertCompileRejectedWithin(compile, 2L * Re2.Options.DEFAULT_MAX_MEMORY);
    }

    private static void assertCompileRejectedWithin(ThrowingCallable compile, long maxAllocatedBytes)
    {
        ThreadMXBean threadBean = allocatedMemoryBean();
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        assertThatThrownBy(compile).isInstanceOf(RegexpCompileMemoryLimitException.class);
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(allocatedBytes).isLessThan(maxAllocatedBytes);
    }

    // Optional runs of distinct letters, which do not coalesce into one repeat.
    private static String optionalRuns(int count)
    {
        StringBuilder pattern = new StringBuilder();
        for (int run = 0; run < count; run++) {
            pattern.append((char) ('a' + run % 26)).append("{0,1000}");
        }
        return pattern.toString();
    }

    private static String optionalGroups(int count, IntFunction<String> group)
    {
        StringBuilder pattern = new StringBuilder();
        for (int index = 0; index < count; index++) {
            pattern.append("(?:").append(group.apply(index)).append("){0,1000}");
        }
        return pattern.toString();
    }

    static boolean compressedClassPointers()
    {
        try {
            return Boolean.parseBoolean(ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption("UseCompressedClassPointers").getValue());
        }
        catch (IllegalArgumentException e) {
            // JDK 27 removes the option and always compresses class pointers.
            return true;
        }
    }

    /// Returns the heap a second compile retains across full collections; the first loads the
    /// tables its patterns share.
    private static long retainedHeapBytes(Supplier<?> compile)
    {
        assertThat(compile.get()).isNotNull();
        long before = usedHeapAfterCollection();
        Object compiled = compile.get();
        try {
            return usedHeapAfterCollection() - before;
        }
        finally {
            Reference.reachabilityFence(compiled);
        }
    }

    private static long usedHeapAfterCollection()
    {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        memoryBean.gc();
        return memoryBean.getHeapMemoryUsage().getUsed();
    }

    private static ThreadMXBean allocatedMemoryBean()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);
        return threadBean;
    }
}
