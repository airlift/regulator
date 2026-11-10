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
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestSimplifier
{
    private static final List<String> GENERATED_ATOMS = List.of(
            "a",
            "ab",
            "é",
            "中",
            "😀",
            "ß",
            "ss",
            "sS",
            "[ab]",
            "[a-z]",
            "[é中]",
            "[\\x{10000}-\\x{10003}]",
            "[^\\s\\S]",
            "[\\s\\S]",
            ".",
            "",
            "(?:a{0})",
            "(?:a|)",
            "(?:a?)",
            "(?:a*)");
    private static final List<String> GENERATED_ASSERTIONS = List.of("\\b", "\\B", "^", "$", "\\A", "\\z");
    private static final List<String> GENERATED_QUANTIFIERS = List.of(
            "*", "+", "?", "*?", "+?", "??", "{0}", "{1}", "{2}", "{3}", "{0,1}", "{0,3}", "{1,3}", "{2,4}", "{0,}", "{1,}", "{3,}");

    @Test
    public void testFullClassUsesAnyCharWithoutChangingDotFlags()
    {
        for (int flags : List.of(Regexp.LIKE_PERL, Regexp.LIKE_PERL | Regexp.DOT_MATCHES_NEWLINE)) {
            Regexp parsed = RegexpParser.parse(utf8Slice("[\\s\\S]"), flags).regexp();
            assertThat(parsed.op()).isEqualTo(RegexpOp.CHAR_CLASS);
            Regexp simplified = Simplifier.simplify(parsed, CaseFolding.RE2);
            assertThat(simplified.op()).isEqualTo(RegexpOp.ANY_CHAR);
            assertThat(simplified.parseFlags()).isEqualTo(flags);
            Prog compiled = Compiler.compile(simplified);
            assertThat(Nfa.search(compiled, utf8Slice("\n"), 0, 1, true, Prog.MatchKind.FIRST_MATCH, new int[2])).isTrue();
        }
    }

    @Test
    public void testFlattenConcatAndRemoveEmpty()
    {
        Regexp re = Regexp.concat(0, List.of(
                Regexp.emptyMatch(0),
                Regexp.concat(0, List.of(Regexp.literal(0, 'a'), Regexp.literal(0, 'b'))),
                Regexp.emptyMatch(0)));

        Regexp simplified = Simplifier.simplify(re, CaseFolding.RE2);
        assertThat(simplified.op()).isEqualTo(RegexpOp.LITERAL_STRING);
        assertThat(simplified.runes()).containsExactly('a', 'b');
    }

    @Test
    public void testConcatWithNoMatchIsNoMatch()
    {
        Regexp re = Regexp.concat(0, List.of(Regexp.literal(0, 'a'), Regexp.noMatch(0)));
        assertThat(Simplifier.simplify(re, CaseFolding.RE2).op()).isEqualTo(RegexpOp.NO_MATCH);
    }

    @Test
    public void testAlternateDropsNoMatchAndFlattens()
    {
        Regexp re = Regexp.alternate(0, List.of(
                Regexp.noMatch(0),
                Regexp.alternate(0, List.of(Regexp.literal(0, 'a'), Regexp.literal(0, 'b')))));

        Regexp simplified = Simplifier.simplify(re, CaseFolding.RE2);
        assertThat(simplified.op()).isEqualTo(RegexpOp.CHAR_CLASS);
        assertThat(simplified.charClass().contains('a')).isTrue();
        assertThat(simplified.charClass().contains('b')).isTrue();
    }

    @Test
    public void testStarNoMatchIsEmpty()
    {
        Regexp re = Regexp.star(0, Regexp.noMatch(0));
        assertThat(Simplifier.simplify(re, CaseFolding.RE2).op()).isEqualTo(RegexpOp.EMPTY_MATCH);
    }

    @Test
    public void testCoalesceReturnsInputWithoutCoalescingPair()
    {
        for (String pattern : List.of("abc", "(a|b)*c", "[a-z]+-[0-9]+", "(?:x+y)+z", "a*b*", "a+(a)")) {
            Regexp parsed = parse(pattern);
            assertThat(Simplifier.Coalescer.coalesce(parsed, CaseFolding.RE2)).as(pattern).isSameAs(parsed);
        }
        for (String pattern : List.of("a*a+", "x(?:a+a)y", "(b|c*c)", "[0-9]+[0-9]{2}")) {
            Regexp parsed = parse(pattern);
            assertThat(Simplifier.Coalescer.coalesce(parsed, CaseFolding.RE2)).as(pattern).isNotSameAs(parsed);
        }
    }

    @Test
    public void testCoalesceFindsPairsBeyondScanDepthBound()
    {
        // Below the recursion bound the scan finds the pair itself; beyond it the walk does. Either
        // way an expression without a coalescing pair is returned unchanged.
        for (int depth : new int[] {200, 300, 2_000}) {
            String coalescing = "(".repeat(depth) + "a*a+" + ")".repeat(depth);
            Regexp parsed = parse(coalescing);
            Regexp coalesced = Simplifier.Coalescer.coalesce(parsed, CaseFolding.RE2);
            assertThat(coalesced).as("depth %s", depth).isNotSameAs(parsed);
            Regexp expected = parse("(".repeat(depth) + "a+" + ")".repeat(depth));
            assertThat(Simplifier.simplify(coalesced, CaseFolding.RE2)).as("depth %s", depth).isEqualTo(Simplifier.simplify(expected, CaseFolding.RE2));

            String unchanged = "(".repeat(depth) + "a*b+" + ")".repeat(depth);
            parsed = parse(unchanged);
            assertThat(Simplifier.Coalescer.coalesce(parsed, CaseFolding.RE2)).as("depth %s", depth).isSameAs(parsed);
        }
    }

    @Test
    public void testCoalescedRepeatCountsSaturate()
    {
        // Coalescing summed repeat counts in an int, so two repeats of a billion overflowed into
        // a negative count. The sum now saturates, and the budget rejects it before expansion.
        Regexp half = Regexp.repeat(Regexp.LIKE_PERL, Regexp.literal(Regexp.LIKE_PERL, 'a'), 1 << 30, 1 << 30);
        Regexp halves = Regexp.concat(Regexp.LIKE_PERL, List.of(half, half));
        Regexp coalesced = Simplifier.Coalescer.coalesce(halves, CaseFolding.RE2);
        assertThat(coalesced.op()).isEqualTo(RegexpOp.REPEAT);
        assertThat(coalesced.min()).isEqualTo(Integer.MAX_VALUE);
        assertThat(coalesced.max()).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> Simplifier.simplify(halves, CaseFolding.RE2, Re2.Options.DEFAULT_MAX_MEMORY))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    @Test
    public void testExpansionBudgetAcceptsWhatTheCompilerAccepts()
    {
        // The expansion count is a lower bound on what the compiler counts for the same budget, so
        // a repetition whose expansion compiles is never rejected for its size while it expands.
        List<String> patterns = List.of(
                "a{40}",
                "(?:abc){20,30}",
                "(?i:abc){25}",
                "(?:[a-c]x){10,}",
                "(?:(a)|b\\b){15}",
                "(?:x*){12}",
                "(?:a+){3,9}?",
                "(?:(?:ab){2,4}){6}",
                "^(?:\\w\\d?){1,20}$",
                "(?:é|Ā){18}",
                "(?:\\p{L}a){12}",
                "(?:ß|ss){10,20}",
                "(?:a{0,3}b){8}",
                // Bound by the visit limit: the never-matching branch is visited but emits nothing.
                "(?:x|([^\\s\\S])){100}",
                // The never-matching branch discards its copies of a, which count nothing.
                "(?:a{0,1000}(?:[^\\s\\S]|[^\\s\\S]){1})|z",
                // Every rune folded, and ß folded in full to ss.
                "(?i:straße){12}",
                // Java keeps the inner repetitions of a body that can match empty, and compiles
                // their bodies once per copy inside each copy of the outer one.
                "(?:(?:a?){10}b){100}",
                "(?:x(?:a?|b?){3,7}){20}",
                "(?:(?:a?){4,}b){30}",
                "(?:(?:a?){2,6}?c){25}",
                "(?:(?:a?){10}){100}");
        for (String pattern : patterns) {
            assertExpansionFitsWhereCompileFits(pattern, Compiler.Dialect.RE2, RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL));
            assertExpansionFitsWhereCompileFits(pattern, Compiler.Dialect.RE2, RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL | Regexp.LATIN1));
            assertExpansionFitsWhereCompileFits(pattern, Compiler.Dialect.TRINO, TrinoRegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL | Regexp.FOLD_CASE));
            assertExpansionFitsWhereCompileFits(pattern, Compiler.Dialect.JAVA, JavaRegexpParser.parse(utf8Slice(pattern), JavaRegexp.Options.defaults().parseFlags()));
        }
    }

    @Test
    public void testInstructionEstimateMatchesCompiledProgram()
    {
        // The estimate bounds how much memory a rejected pattern needs, so it counts no more than
        // the compiler emits, and for these exactly what it emits once the program's own
        // instructions are added: no search loop where the expression may anchor its start, and
        // nothing for a captured branch that never matches or a repetition that collapses.
        List<String> patterns = List.of(
                "(?:abc){20,30}",
                "(?i:abc){25}",
                "(?:(a)|b\\b){15}",
                "(?:x*){12}",
                "(?:a{0,3}b){8}",
                "(?:é|Ā){18}",
                "(?:\\p{L}a){12}",
                "[\\p{Greek}\\d]{3,7}x",
                "(?:ß|ss){10,20}",
                "(?i:straße){12}",
                "(?:x|([^\\s\\S])){100}",
                "(?:ab|)*c",
                "(?:a?b?)*c",
                "^(?:\\w\\d?){1,20}$",
                "(?:\\b+){1000}",
                "(?:ab[ab]+)?",
                "(?:a+){2,4}");
        for (String pattern : patterns) {
            for (boolean latin1 : new boolean[] {false, true}) {
                Regexp regexp = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL | (latin1 ? Regexp.LATIN1 : 0)).regexp();
                long instructions = Compiler.compileForBenchmark(regexp, false, 0, Compiler.CompileStage.RAW).size();
                Simplifier.EstimatedInstructions estimate = Simplifier.estimateInstructions(regexp, CaseFolding.RE2, Compiler.Dialect.RE2, Compiler.MAX_INSTRUCTIONS).orElseThrow();
                assertThat(estimate.count() + programInstructions(estimate)).as("%s%s", pattern, latin1 ? " LATIN1" : "").isEqualTo(instructions);
            }
        }
    }

    @Test
    public void testInstructionEstimateStopsAtLimit()
    {
        assertThat(Simplifier.estimateInstructions(parse("\\p{L}{1000}"), CaseFolding.RE2, Compiler.Dialect.RE2, 1000).orElseThrow().count()).isEqualTo(1001);
    }

    @Test
    public void testInstructionEstimateOfNestedAlternationsStaysLinear()
    {
        // x{1} simplifies to x, so each level's alternation adds the alternatives of the one
        // below in place, and copying them at every level is quadratic in the depth: 2,480 bytes
        // per pattern byte at 4,000 levels of the first shape. In the second, the literals of
        // each level carry the flags of the level above, which merges them inside the
        // alternation below. In the third, each level merges its code point with the class the
        // level below merged, so the classes built and compiled grow at every level. The fourth
        // does so with classes, after classes written in the pattern that are large but cheap to
        // estimate. Past a linear amount of that work the estimate counts nothing.
        List<String> patterns = new ArrayList<>();
        for (int depth : new int[] {1_000, 4_000, 16_000}) {
            patterns.add("aa|(?:".repeat(depth) + "bb" + "){1}".repeat(depth));
            patterns.add(alternatingFlagAlternations(depth));
            patterns.add(nestedCodePointAlternations(depth));
        }
        // Its code points pass U+10FFFF beyond 4,351 levels.
        for (int depth : new int[] {1_000, 2_000, 4_000}) {
            patterns.add(nestedClassAlternationsBeforeLetters(depth));
        }
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        for (String pattern : patterns) {
            Regexp regexp = parse(pattern);
            long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
            Optional<Simplifier.EstimatedInstructions> estimate = Simplifier.estimateInstructions(regexp, CaseFolding.RE2, Compiler.Dialect.RE2, Compiler.MAX_INSTRUCTIONS);
            long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
            String description = pattern.length() + " characters of " + pattern.substring(0, 40);
            assertThat(estimate).as(description).isEmpty();
            assertThat(allocatedBytes).as(description).isLessThan(1_000L * pattern.length());
        }
    }

    @Test
    public void testInstructionEstimateBoundsGeneratedPrograms()
    {
        // The estimate mirrors the simplifier's rules, and a rejection may report it as a lower
        // bound on what the pattern needs, so for every pattern of a seeded grammar it counts at
        // most what the compiler emits after simplification, in every dialect, also in LATIN1
        // and for the suffix after a required literal prefix, which the program compiles in its
        // place. A simplifier rule the estimate does not follow overcounts here. Java and Trino
        // compile a loop of a body that can match empty with a copy of the body's start, and
        // Java a repetition of one with more than its copies, so the estimate can count fewer.
        List<String> patterns = new ArrayList<>(List.of("(?:a?b?)*c", "(?:(?:a?){10}b){100}", "(?:x*){12}", "(?:a{0}){1000}", "(?:a|)*", "^(?:\\b+){100}$"));
        Random random = new Random(20_261_108);
        while (patterns.size() < 3000) {
            // A quarter start with a required literal prefix.
            patterns.add((random.nextInt(4) == 0 ? "^ab" : "") + generatedPattern(random, 1 + random.nextInt(4)));
        }
        Map<String, Integer> comparisons = new TreeMap<>();
        for (String pattern : patterns) {
            Slice slice = utf8Slice(pattern);
            for (Compiler.Dialect dialect : Compiler.Dialect.values()) {
                boolean[] encodings = dialect == Compiler.Dialect.RE2 ? new boolean[] {false, true} : new boolean[] {false};
                for (boolean latin1 : encodings) {
                    ParseResult parsed;
                    try {
                        parsed = switch (dialect) {
                            case RE2 -> RegexpParser.parse(slice, Re2.Options.defaults().setEncoding(latin1 ? Re2.Options.Encoding.LATIN1 : Re2.Options.Encoding.UTF8).parseFlags());
                            case JAVA -> JavaRegexpParser.parse(slice, JavaRegexp.Options.defaults().parseFlags());
                            case RUST -> RustRegexpParser.parse(slice, RustRegexp.Options.defaults().syntaxFlags(), RustRegexp.Options.defaults().nestLimit());
                            case TRINO -> TrinoRegexpParser.parse(slice, Regexp.LIKE_PERL);
                        };
                    }
                    catch (RegexpParseException ignored) {
                        // The grammar is shared, so some patterns are not valid in every dialect.
                        continue;
                    }
                    String variant = dialect + (latin1 ? " LATIN1" : "");
                    assertEstimateBoundsProgram(variant + " " + pattern, parsed.regexp(), parsed.caseFolding(), dialect);
                    comparisons.merge(variant, 1, Integer::sum);
                    Regexp.RequiredPrefixResult prefix = parsed.regexp().requiredPrefix(parsed.caseFolding());
                    if (prefix != null) {
                        assertEstimateBoundsProgram(variant + " suffix of " + pattern, prefix.suffix(), parsed.caseFolding(), dialect);
                        comparisons.merge("suffixes", 1, Integer::sum);
                    }
                }
            }
        }
        assertThat(comparisons).hasSize(6)
                .allSatisfy((variant, count) -> assertThat(count).as(variant).isGreaterThan(variant.equals("suffixes") ? 500 : 2000));
    }

    @Test
    public void testJavaExpansionBudgetCountsKeptRepetitionCopies()
    {
        // Java's simplification keeps the inner repetition, whose body can match empty, and the
        // compiler compiles that body ten times in each of the hundred copies of the outer one.
        // The count charged that body once in each outer copy instead of ten times, so
        // simplification accepted a budget the compiler rejects only later.
        String pattern = "(?:(?:a?){10}b){100}";
        ParseResult parsed = JavaRegexpParser.parse(utf8Slice(pattern), JavaRegexp.Options.defaults().parseFlags());
        assertThatThrownBy(() -> Simplifier.simplify(parsed.regexp(), parsed.caseFolding(), Compiler.Dialect.JAVA, 65_536))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    @Test
    public void testSimplifyReturnsUnchangedNodes()
    {
        for (String pattern : List.of("(a+)x*[b-d]", "(?:ab)?[0-9]+z", "(x[a-f]+)+")) {
            Regexp parsed = parse(pattern);
            assertThat(Simplifier.simplify(parsed, CaseFolding.RE2)).as(pattern).isSameAs(parsed);
        }
        Regexp repeated = parse("(a{2})b");
        assertThat(Simplifier.simplify(repeated, CaseFolding.RE2)).isNotSameAs(repeated);
    }

    private static void assertEstimateBoundsProgram(String description, Regexp regexp, CaseFolding caseFolding, Compiler.Dialect dialect)
    {
        Regexp simplified = Simplifier.simplify(regexp, caseFolding, dialect, 0);
        long instructions = Compiler.compileNormalizedForBenchmark(simplified, false, 0, Compiler.CompileStage.RAW, dialect, caseFolding).size();
        Simplifier.EstimatedInstructions estimate = Simplifier.estimateInstructions(regexp, caseFolding, dialect, Compiler.MAX_INSTRUCTIONS).orElseThrow();
        assertThat(estimate.count() + programInstructions(estimate)).as(description).isLessThanOrEqualTo(instructions);
    }

    // A pattern of nested repetitions, alternations, captures, case folding, and concatenations
    // over literals, classes, assertions, and expressions that match empty or never match.
    private static String generatedPattern(Random random, int depth)
    {
        if (depth == 0 || random.nextInt(6) == 0) {
            return random.nextInt(3) == 0
                    ? GENERATED_ASSERTIONS.get(random.nextInt(GENERATED_ASSERTIONS.size()))
                    : GENERATED_ATOMS.get(random.nextInt(GENERATED_ATOMS.size()));
        }
        return switch (random.nextInt(7)) {
            case 0, 1 -> "(?:" + generatedPattern(random, depth - 1) + ")" + GENERATED_QUANTIFIERS.get(random.nextInt(GENERATED_QUANTIFIERS.size()));
            case 2 -> "(?:" + generatedPattern(random, depth - 1) + "|" + generatedPattern(random, depth - 1) + ")";
            case 3 -> "(?:" + generatedPattern(random, depth - 1) + "|" + generatedPattern(random, depth - 1) + "|" + generatedPattern(random, depth - 1) + ")";
            case 4 -> "(?:" + generatedPattern(random, depth - 1) + generatedPattern(random, depth - 1) + ")";
            case 5 -> "(" + generatedPattern(random, depth - 1) + ")";
            default -> "(?i:" + generatedPattern(random, depth - 1) + ")";
        };
    }

    // Each level is zz|(?F:a){1}|(?F:b){1}|zz|(?F:...){1}, with F alternating between i and -i.
    private static String alternatingFlagAlternations(int depth)
    {
        StringBuilder open = new StringBuilder();
        for (int level = 0; level < depth; level++) {
            String flags = level % 2 == 0 ? "i" : "-i";
            open.append("zz|(?").append(flags).append(":a){1}|(?").append(flags).append(":b){1}|zz|(?").append(flags).append(':');
        }
        return open + "bb" + "){1}".repeat(depth);
    }

    // Each level is \x{N}|(?:...){1}, with N a code point two above the level above's.
    private static String nestedCodePointAlternations(int depth)
    {
        StringBuilder open = new StringBuilder();
        for (int level = 0; level < depth; level++) {
            open.append("\\x{").append(Integer.toHexString(0x1000 + 2 * level)).append("}|(?:");
        }
        return open + "x" + "){1}".repeat(depth);
    }

    // Each level is [N-M]|(?:...){1}, a class of 254 supplementary code points starting 256 above
    // the level above's, and the alternation is followed by one \pL, of 694 ranges, per level.
    private static String nestedClassAlternationsBeforeLetters(int depth)
    {
        StringBuilder open = new StringBuilder();
        for (int level = 0; level < depth; level++) {
            int low = 0x10001 + 256 * level;
            open.append('[').appendCodePoint(low).append('-').appendCodePoint(low + 253).append("]|(?:");
        }
        return open + "x" + "){1}".repeat(depth) + "\\pL".repeat(depth);
    }

    private static long programInstructions(Simplifier.EstimatedInstructions estimate)
    {
        return estimate.mayAnchorStart() ? Compiler.PROGRAM_INSTRUCTIONS - 2 : Compiler.PROGRAM_INSTRUCTIONS;
    }

    private static Regexp parse(String pattern)
    {
        return RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();
    }

    private static void assertExpansionFitsWhereCompileFits(String pattern, Compiler.Dialect dialect, ParseResult parsed)
    {
        Regexp simplified = Simplifier.simplify(parsed.regexp(), parsed.caseFolding(), dialect, 0);
        // No program fits in the compiler's fixed overhead.
        long rejected = Compiler.PROGRAM_OVERHEAD_BYTES;
        long compiled = 1 << 24;
        assertThat(compiles(simplified, compiled, dialect, parsed.caseFolding())).as(pattern).isTrue();
        while (compiled - rejected > 1) {
            long budget = (rejected + compiled) >>> 1;
            if (compiles(simplified, budget, dialect, parsed.caseFolding())) {
                compiled = budget;
            }
            else {
                rejected = budget;
            }
        }
        Simplifier.simplify(parsed.regexp(), parsed.caseFolding(), dialect, compiled);
    }

    private static boolean compiles(Regexp simplified, long maxMemory, Compiler.Dialect dialect, CaseFolding caseFolding)
    {
        try {
            Compiler.compileNormalized(simplified, false, maxMemory, dialect, caseFolding);
            return true;
        }
        catch (RegexpCompileMemoryLimitException ignored) {
            return false;
        }
    }
}
