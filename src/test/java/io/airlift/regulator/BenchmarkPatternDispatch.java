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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.openjdk.jmh.annotations.CompilerControl.Mode.DONT_INLINE;

/// Public `find`, `count`, `matches`, `lookingAt`, and matcher iteration of RE2, Java, Rust, and
/// Trino patterns over a batch of rows on one target pattern, called the way an application calls a
/// pattern it holds: one call site that only ever sees that pattern. It measures profile pollution,
/// what other patterns cost the target when they have run first through the library code every
/// pattern shares.
///
/// The targets are the three case-sensitive scan-plan targets of [BenchmarkRe2ScanPlan] workloads,
/// the start-anchored ANCHORED_WWW_HOST and the unanchored UNANCHORED_URL and UNANCHORED_SET, and
/// one target per common route of full-Slice `find` without a plan: EXACT_LITERAL searches for an
/// exact literal; CONTAINS for a literal between optional gaps; CHARACTER_CLASS runs a class repeat
/// on the class counter; LITERAL_ALTERNATION runs a small literal alternation on the
/// literal-alternation span matcher, as a matcher's `find` does with fewer than 64 bytes left,
/// while one with at least 64, on LONG rows, runs the DFA's candidate cursor; GENERAL has no plan
/// and no literal or class shortcut, so it runs the ordinary DFA search; FIXED_WIDTH runs a
/// fixed-width class sequence on the fixed-width byte span matcher, as a matcher's `find` does;
/// BYTE_TABLE runs a one-byte member run on the single-byte table for SHORT rows in RE2 and Java,
/// and on the ordinary search for LONG rows and in Rust, whose `\s` is Unicode; and
/// ANCHORED_LITERAL compares a literal prefix. FOLDED_PLAN is an ASCII-folded scan plan, which has
/// no generated class. FOLDED_PAIR_SEQUENCE is a control: a class sequence whose first class
/// simplifies to a case-folded literal, so it takes no fixed-width route in any language, Trino
/// included. INNER_LITERAL_FIXED_WIDTH is a fixed-width class sequence with a five-byte inner
/// literal, `[a-z]shing`: RE2, Java, and Rust keep the DFA's inner literal search, while Trino's
/// fixed-width subtype runs the fixed-width matcher, which checks every `s`. ORDERED_LITERALS finds
/// three literals in order with any text between them and runs the ordinary search today.
/// LITERAL_SET alternates 100 words, over the limit of 64 literals of the literal-alternation span
/// matcher: full-Slice `find` and `count` run the forward DFA, and RE2, Java, and Rust matchers
/// find it with the literal set's automaton, skipping the bytes that lead no word with a vector
/// scan on LONG rows. SINGLE_BYTE is the one-byte exact literal `,`, which a matcher's `find` reads
/// with the single-byte table on SHORT rows and with the group-zero forward cursor's vector scan on
/// LONG rows. DOT_STAR_LITERAL is `.*timeout.*`, whose dots stop at a line feed, unlike CONTAINS's
/// `(?s)`; RE2 and Rust search it as a contained literal, while Java, whose dot stops at more line
/// terminators, runs the ordinary search. TestBenchmarkPatternDispatch checks each route in RE2,
/// Java, and Rust, and in Trino only that FOLDED_PAIR_SEQUENCE takes no fixed-width route. SHORT
/// rows are 16 to 64 bytes and LONG rows 1024 bytes, padded in the host run of the anchored URL
/// pattern, after the text of ANCHORED_LITERAL, whose search reads only its start, and before the
/// text of the others. LONG inputs contain padding, but anchored URL rows 4–6 reject before
/// reaching it. The padding of INNER_LITERAL_FIXED_WIDTH is English text rich in `s` without its
/// literal, so its SHORT rows find the literal within their first 64 bytes and its LONG rows only
/// after them, the two sides of the inner literal search's 64-byte minimum skip. Each target's rows
/// mix matching and non-matching log lines, requests, URLs, or prose.
///
/// Where a route above names no language, it is the route of RE2, Java, and Rust. Trino compiles
/// most targets to its own subtypes instead, such as its ordered-literal matcher for
/// ORDERED_LITERALS and its literal-set matcher for LITERAL_SET.
///
/// Each language compiles through its own public `compile(Slice, Options)`, and an adapter of that
/// language, [Re2Patterns], [JavaPatterns], [RustPatterns], or [TrinoPatterns], holds its patterns
/// as the language's public type and writes each measured loop out for that type. A benchmark
/// method calls the adapter once per invocation, outside the loop, and a fork creates adapters of
/// one language only, so that call sees one receiver. Inside the loop every call is of the
/// language's own type, as in an application written against that language: a JAVA run's call sites
/// are `JavaRegexp` sites. One loop calling the languages through a shared interface would add the
/// many-receiver call site that this benchmark measures. `iterate` counts the matches of each row
/// with a find loop of the language's public matcher, which retains only group zero; each pattern
/// has one matcher, created at setup and reset to each row, so the loop allocates nothing once a
/// literal alternation's first long search has created the matcher's DFA candidate cursor, which
/// only literals that differ in length have. Trino's `find` is its `contains`. Trino has no
/// whole-input or anchored-prefix match, so its `matches` and `lookingAt` throw
/// `UnsupportedOperationException`, and its training calls only `contains`, `count`, and its
/// matcher.
///
/// With `polluted=false`, setup compiles only the target, so no other pattern has run or loaded a
/// generated class: the run is the one-pattern baseline. With `polluted=true`, setup also compiles
/// a pollution set with the target's language and `generated` setting, every other target and the
/// six patterns of [#POLLUTION], and calls `find`, `count`, `matches`, `lookingAt`, and a matcher's
/// find loop on the target and the pollution set, interleaved, through one non-inlined training
/// method, with one site per operation, for enough rounds to encourage C2 to compile the shared
/// methods with the profile of all of them. Training reads every target's rows at the run's `rows`
/// length, so a LONG run's shared methods also learn long inputs. The measured methods then call
/// only the target, so the difference from `polluted=false` is the cost of reaching the target
/// through shared methods whose profile holds many patterns, with the other patterns' generated
/// classes loaded. The `sharedSite` method calls `find` of the target and then of every pollution
/// pattern on each row, from one call site: the loop of an application that holds many patterns.
/// It reports the time per call. Its state always compiles the pollution set and has no `polluted`
/// parameter, since its own loop trains the shared methods with every pattern.
///
/// With `generated=true`, every pattern compiles with generated bytecode requested. Setup
/// requires the target to have a generated class, which only the case-sensitive scan-plan targets
/// get; pollution patterns without a generated class stay interpreted. Setup does not run a
/// compilation without generated bytecode next to the target, which would add a second class to
/// the shared methods' profile; TestBenchmarkPatternDispatch checks every method's results
/// against one. Setup also requires each target's rows to mix matching and non-matching `find`
/// results. It does not require full matches: some targets' rows never match whole, and
/// `matches` then times the route that rejects them.
///
/// The benchmark uses only the public API of `Re2`, `JavaRegexp`, `RustRegexp`, and `TrinoRegexp`,
/// so this source also compares builds that change how a pattern reaches its generated class.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkPatternDispatch
{
    private static final int ROWS = 1024;
    private static final int LONG_ROW_BYTES = 1024;
    // Calls of every operation per pattern before measurement, to encourage C2 to compile the
    // shared methods with the polluted profile.
    private static final int TRAINING_ROUNDS = 30_000;
    // Where each row template puts the padding of a LONG row.
    private static final String PAD = "{pad}";

    // Patterns of other BenchmarkRe2ScanPlan workloads, three start-anchored and three unanchored,
    // each with a scan plan of its own.
    private static final List<String> POLLUTION = List.of(
            "^https?://[^/]+/",
            "^https?://[^/]+/[^?]*\\?",
            "^(\\w+)-(\\d+)/",
            "id=(\\d+)&",
            "svc1://(?:www\\.)?([^/]+)/",
            "https?://(?:www\\.)?([^/]+)/.*$");
    // The words of LITERAL_SET: 100 distinct lowercase English words, over the literal-alternation
    // span matcher's limit of 64 literals.
    private static final List<String> LITERAL_SET_WORDS = List.of(
            """
            about above across after again against almost along already always
            among another answer around because become before behind believe better
            between beyond bottom branch bridge bright broken budget button camera
            castle center change church circle closed coffee column common corner
            county credit damage danger decade decide degree design detail dinner
            doctor double dragon driver during eleven energy engine enough entire
            escape evening family father figure finger flight flower follow forest
            forget friend future garden gather golden ground growth guitar health
            hidden island jacket kitchen ladder letter listen market member method
            middle minute mirror modern moment mother number office orange window
            """.strip().split("\\s+"));
    // The target, the seventeen other targets, and the POLLUTION patterns.
    private static final int SHARED_SITE_PATTERNS = 24;

    private static volatile long trainingSink;

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"RE2", "JAVA", "RUST", "TRINO"})
        public String language;

        @Param({
                "ANCHORED_WWW_HOST",
                "UNANCHORED_URL",
                "UNANCHORED_SET",
                "EXACT_LITERAL",
                "CONTAINS",
                "CHARACTER_CLASS",
                "LITERAL_ALTERNATION",
                "GENERAL",
                "FIXED_WIDTH",
                "BYTE_TABLE",
                "ANCHORED_LITERAL",
                "FOLDED_PLAN",
                "FOLDED_PAIR_SEQUENCE",
                "INNER_LITERAL_FIXED_WIDTH",
                "ORDERED_LITERALS",
                "LITERAL_SET",
                "SINGLE_BYTE",
                "DOT_STAR_LITERAL",
        })
        public String target;

        // SHORT rows are 16 to 64 bytes; LONG rows are 1024 bytes.
        @Param({"SHORT", "LONG"})
        public String rows;

        @Param({"false", "true"})
        public boolean polluted;

        // `true` is valid only for case-sensitive scan-plan targets, which have generated classes.
        @Param({"false", "true"})
        public boolean generated;

        LanguagePatterns patterns;
        Slice[] inputs;

        @Setup
        public void setup()
        {
            inputs = inputs(target, rows);
            List<String> expressions = polluted ? sharedSiteExpressions(target) : List.of(expression(target));
            patterns = compile(language, expressions, generated, inputs);

            if (polluted) {
                Slice[] trainingInputs = trainingInputs(rows);
                long checksum = 0;
                for (int round = 0; round < TRAINING_ROUNDS; round++) {
                    Slice input = trainingInputs[round % trainingInputs.length];
                    for (int patternIndex = 0; patternIndex < SHARED_SITE_PATTERNS; patternIndex++) {
                        checksum += patterns.train(patternIndex, input);
                    }
                }
                trainingSink = checksum;
            }
        }
    }

    /// The state of `sharedSite`, which always compiles every pattern of
    /// [#sharedSiteExpressions(String)].
    @State(Scope.Thread)
    public static class SharedSiteData
    {
        @Param({"RE2", "JAVA", "RUST", "TRINO"})
        public String language;

        // The targets of BenchmarkData.
        @Param({
                "ANCHORED_WWW_HOST",
                "UNANCHORED_URL",
                "UNANCHORED_SET",
                "EXACT_LITERAL",
                "CONTAINS",
                "CHARACTER_CLASS",
                "LITERAL_ALTERNATION",
                "GENERAL",
                "FIXED_WIDTH",
                "BYTE_TABLE",
                "ANCHORED_LITERAL",
                "FOLDED_PLAN",
                "FOLDED_PAIR_SEQUENCE",
                "INNER_LITERAL_FIXED_WIDTH",
                "ORDERED_LITERALS",
                "LITERAL_SET",
                "SINGLE_BYTE",
                "DOT_STAR_LITERAL",
        })
        public String target;

        // SHORT rows are 16 to 64 bytes; LONG rows are 1024 bytes.
        @Param({"SHORT", "LONG"})
        public String rows;

        // `true` is valid only for case-sensitive scan-plan targets, which have generated classes.
        @Param({"false", "true"})
        public boolean generated;

        LanguagePatterns patterns;
        Slice[] inputs;

        @Setup
        public void setup()
        {
            inputs = inputs(target, rows);
            patterns = compile(language, sharedSiteExpressions(target), generated, inputs);
        }
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public int find(BenchmarkData data)
    {
        return data.patterns.find(data.inputs);
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public long count(BenchmarkData data)
    {
        return data.patterns.count(data.inputs);
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public int matches(BenchmarkData data)
    {
        return data.patterns.matches(data.inputs);
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public int lookingAt(BenchmarkData data)
    {
        return data.patterns.lookingAt(data.inputs);
    }

    /// Counts every row's matches with a find loop of the target's reused matcher.
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public long iterate(BenchmarkData data)
    {
        return data.patterns.iterate(data.inputs);
    }

    /// Calls `find` of every pattern of [#sharedSiteExpressions(String)] on each row from one
    /// call site.
    @Benchmark
    @OperationsPerInvocation(ROWS * SHARED_SITE_PATTERNS)
    public int sharedSite(SharedSiteData data)
    {
        return data.patterns.sharedSite(data.inputs);
    }

    /// Compiles `expressions` of `language`, the target first, and requires the target to have a
    /// generated class when `generated` is set and to find some of `inputs` but not all.
    private static LanguagePatterns compile(String language, List<String> expressions, boolean generated, Slice[] inputs)
    {
        LanguagePatterns patterns = switch (language) {
            case "RE2" -> new Re2Patterns(expressions, generated);
            case "JAVA" -> new JavaPatterns(expressions, generated);
            case "RUST" -> new RustPatterns(expressions, generated);
            case "TRINO" -> new TrinoPatterns(expressions, generated);
            default -> throw new IllegalArgumentException("unknown language: " + language);
        };
        if (generated && !patterns.targetUsesGeneratedBytecode()) {
            throw new IllegalStateException("Pattern has no generated class: " + expressions.getFirst());
        }
        int hits = patterns.find(inputs);
        if (hits == 0 || hits == inputs.length) {
            throw new IllegalStateException("Rows of " + expressions.getFirst() + " do not mix find hits and misses: " + hits + " of " + inputs.length);
        }
        return patterns;
    }

    private static Slice[] inputs(String target, String rows)
    {
        Slice[] inputs = new Slice[ROWS];
        for (int row = 0; row < ROWS; row++) {
            inputs[row] = offsetInput(row(target, row, rows.equals("LONG")));
        }
        return inputs;
    }

    static List<String> targets()
    {
        try {
            return List.of(BenchmarkData.class.getDeclaredField("target").getAnnotation(Param.class).value());
        }
        catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    static String expression(String target)
    {
        return switch (target) {
            case "ANCHORED_WWW_HOST" -> "^https?://(?:www\\.)?([^/]+)/";
            case "UNANCHORED_URL" -> "https?://(?:www\\.)?([^/]+)/";
            case "UNANCHORED_SET" -> "[?&]([^=]+)=";
            case "EXACT_LITERAL" -> "Connection refused";
            case "CONTAINS" -> "(?s).*timeout.*";
            case "CHARACTER_CLASS" -> "\\p{L}+";
            case "LITERAL_ALTERNATION" -> "ERROR|WARN|FATAL";
            case "GENERAL" -> "[A-Z]\\w+Exception";
            case "FIXED_WIDTH" -> "[a-z]{8}-[0-9]{4}";
            case "BYTE_TABLE" -> "\\s+";
            case "ANCHORED_LITERAL" -> "^GET /";
            case "FOLDED_PLAN" -> "(?i)content-type:([^;]+);";
            case "FOLDED_PAIR_SEQUENCE" -> "[Kk][a-z]{7}";
            case "INNER_LITERAL_FIXED_WIDTH" -> "[a-z]shing";
            case "ORDERED_LITERALS" -> "a(?s:.*)b(?s:.*)c";
            case "LITERAL_SET" -> String.join("|", LITERAL_SET_WORDS);
            case "SINGLE_BYTE" -> ",";
            case "DOT_STAR_LITERAL" -> ".*timeout.*";
            default -> throw new IllegalArgumentException("unknown target: " + target);
        };
    }

    /// Returns the target's expression, then those of every other target, then [#POLLUTION]:
    /// the patterns of setup's training and of `sharedSite`, in their order.
    private static List<String> sharedSiteExpressions(String target)
    {
        List<String> expressions = new ArrayList<>();
        expressions.add(expression(target));
        for (String other : targets()) {
            if (!other.equals(target)) {
                expressions.add(expression(other));
            }
        }
        expressions.addAll(POLLUTION);
        if (expressions.size() != SHARED_SITE_PATTERNS) {
            throw new IllegalStateException("Expected " + SHARED_SITE_PATTERNS + " patterns: " + expressions);
        }
        return expressions;
    }

    /// The compiled patterns of one language, one for each expression it was given, the target
    /// first: the target alone, or every pattern of [#sharedSiteExpressions(String)], which
    /// `sharedSite` and `train` require. Each implementation writes its loops out for its
    /// language's public type, so that their call sites use that language's public type.
    abstract static sealed class LanguagePatterns
            permits JavaPatterns,
                    Re2Patterns,
                    RustPatterns,
                    TrinoPatterns
    {
        abstract boolean targetUsesGeneratedBytecode();

        abstract int find(Slice[] inputs);

        abstract long count(Slice[] inputs);

        abstract int matches(Slice[] inputs);

        abstract int lookingAt(Slice[] inputs);

        abstract long iterate(Slice[] inputs);

        abstract int sharedSite(Slice[] inputs);

        /// Runs `find`, `count`, `matches`, `lookingAt`, and a find loop of the pattern's matcher
        /// for shared-site pattern `patternIndex` on `input`, or only `find`, `count`, and the find
        /// loop in Trino, which has no `matches` or `lookingAt`. Each implementation is setup's
        /// one non-inlined training method, with one site per operation shared by every pattern,
        /// so its own profile never narrows what the shared library methods see.
        abstract long train(int patternIndex, Slice input);
    }

    static final class Re2Patterns
            extends LanguagePatterns
    {
        final Re2 target;
        final Re2[] patterns;
        final RegexpMatcher targetMatcher;
        // One group-zero matcher per pattern, reset to each row.
        final RegexpMatcher[] matchers;

        private Re2Patterns(List<String> expressions, boolean generated)
        {
            Re2.Options options = Re2.Options.defaults().setGenerateBytecode(generated);
            patterns = expressions.stream().map(expression -> Re2.compile(utf8Slice(expression), options)).toArray(Re2[]::new);
            target = patterns[0];
            matchers = Arrays.stream(patterns).map(pattern -> pattern.matcher(EMPTY_SLICE, 0)).toArray(RegexpMatcher[]::new);
            targetMatcher = matchers[0];
        }

        @Override
        boolean targetUsesGeneratedBytecode()
        {
            return target.usesGeneratedBytecode();
        }

        @Override
        int find(Slice[] inputs)
        {
            Re2 pattern = target;
            int matches = 0;
            for (Slice input : inputs) {
                if (pattern.find(input)) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        long count(Slice[] inputs)
        {
            Re2 pattern = target;
            long matches = 0;
            for (Slice input : inputs) {
                matches += pattern.count(input);
            }
            return matches;
        }

        @Override
        int matches(Slice[] inputs)
        {
            Re2 pattern = target;
            int matches = 0;
            for (Slice input : inputs) {
                if (pattern.matches(input)) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        int lookingAt(Slice[] inputs)
        {
            Re2 pattern = target;
            int matches = 0;
            for (Slice input : inputs) {
                if (pattern.lookingAt(input)) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        long iterate(Slice[] inputs)
        {
            RegexpMatcher matcher = targetMatcher;
            long matches = 0;
            for (Slice input : inputs) {
                matcher.reset(input);
                while (matcher.find()) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        int sharedSite(Slice[] inputs)
        {
            Re2[] patterns = this.patterns;
            int matches = 0;
            for (Slice input : inputs) {
                for (Re2 pattern : patterns) {
                    if (pattern.find(input)) {
                        matches++;
                    }
                }
            }
            return matches;
        }

        @Override
        @CompilerControl(DONT_INLINE)
        long train(int patternIndex, Slice input)
        {
            Re2 compiled = patterns[patternIndex];
            RegexpMatcher matcher = matchers[patternIndex].reset(input);
            long matches = 0;
            while (matcher.find()) {
                matches++;
            }
            return (compiled.find(input) ? 1 : 0) + compiled.count(input) + (compiled.matches(input) ? 1 : 0) + (compiled.lookingAt(input) ? 1 : 0) + matches;
        }
    }

    static final class JavaPatterns
            extends LanguagePatterns
    {
        final JavaRegexp target;
        final JavaRegexp[] patterns;
        final RegexpMatcher targetMatcher;
        // One group-zero matcher per pattern, reset to each row.
        final RegexpMatcher[] matchers;

        private JavaPatterns(List<String> expressions, boolean generated)
        {
            JavaRegexp.Options options = JavaRegexp.Options.defaults().setGenerateBytecode(generated);
            patterns = expressions.stream().map(expression -> JavaRegexp.compile(utf8Slice(expression), options)).toArray(JavaRegexp[]::new);
            target = patterns[0];
            matchers = Arrays.stream(patterns).map(pattern -> pattern.matcher(EMPTY_SLICE, 0)).toArray(RegexpMatcher[]::new);
            targetMatcher = matchers[0];
        }

        @Override
        boolean targetUsesGeneratedBytecode()
        {
            return target.usesGeneratedBytecode();
        }

        @Override
        int find(Slice[] inputs)
        {
            JavaRegexp pattern = target;
            int matches = 0;
            for (Slice input : inputs) {
                if (pattern.find(input)) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        long count(Slice[] inputs)
        {
            JavaRegexp pattern = target;
            long matches = 0;
            for (Slice input : inputs) {
                matches += pattern.count(input);
            }
            return matches;
        }

        @Override
        int matches(Slice[] inputs)
        {
            JavaRegexp pattern = target;
            int matches = 0;
            for (Slice input : inputs) {
                if (pattern.matches(input)) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        int lookingAt(Slice[] inputs)
        {
            JavaRegexp pattern = target;
            int matches = 0;
            for (Slice input : inputs) {
                if (pattern.lookingAt(input)) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        long iterate(Slice[] inputs)
        {
            RegexpMatcher matcher = targetMatcher;
            long matches = 0;
            for (Slice input : inputs) {
                matcher.reset(input);
                while (matcher.find()) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        int sharedSite(Slice[] inputs)
        {
            JavaRegexp[] patterns = this.patterns;
            int matches = 0;
            for (Slice input : inputs) {
                for (JavaRegexp pattern : patterns) {
                    if (pattern.find(input)) {
                        matches++;
                    }
                }
            }
            return matches;
        }

        @Override
        @CompilerControl(DONT_INLINE)
        long train(int patternIndex, Slice input)
        {
            JavaRegexp compiled = patterns[patternIndex];
            RegexpMatcher matcher = matchers[patternIndex].reset(input);
            long matches = 0;
            while (matcher.find()) {
                matches++;
            }
            return (compiled.find(input) ? 1 : 0) + compiled.count(input) + (compiled.matches(input) ? 1 : 0) + (compiled.lookingAt(input) ? 1 : 0) + matches;
        }
    }

    static final class RustPatterns
            extends LanguagePatterns
    {
        final RustRegexp target;
        final RustRegexp[] patterns;
        final RegexpMatcher targetMatcher;
        // One group-zero matcher per pattern, reset to each row.
        final RegexpMatcher[] matchers;

        private RustPatterns(List<String> expressions, boolean generated)
        {
            RustRegexp.Options options = RustRegexp.Options.defaults().setGenerateBytecode(generated);
            patterns = expressions.stream().map(expression -> RustRegexp.compile(utf8Slice(expression), options)).toArray(RustRegexp[]::new);
            target = patterns[0];
            matchers = Arrays.stream(patterns).map(pattern -> pattern.matcher(EMPTY_SLICE, 0)).toArray(RegexpMatcher[]::new);
            targetMatcher = matchers[0];
        }

        @Override
        boolean targetUsesGeneratedBytecode()
        {
            return target.usesGeneratedBytecode();
        }

        @Override
        int find(Slice[] inputs)
        {
            RustRegexp pattern = target;
            int matches = 0;
            for (Slice input : inputs) {
                if (pattern.find(input)) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        long count(Slice[] inputs)
        {
            RustRegexp pattern = target;
            long matches = 0;
            for (Slice input : inputs) {
                matches += pattern.count(input);
            }
            return matches;
        }

        @Override
        int matches(Slice[] inputs)
        {
            RustRegexp pattern = target;
            int matches = 0;
            for (Slice input : inputs) {
                if (pattern.matches(input)) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        int lookingAt(Slice[] inputs)
        {
            RustRegexp pattern = target;
            int matches = 0;
            for (Slice input : inputs) {
                if (pattern.lookingAt(input)) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        long iterate(Slice[] inputs)
        {
            RegexpMatcher matcher = targetMatcher;
            long matches = 0;
            for (Slice input : inputs) {
                matcher.reset(input);
                while (matcher.find()) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        int sharedSite(Slice[] inputs)
        {
            RustRegexp[] patterns = this.patterns;
            int matches = 0;
            for (Slice input : inputs) {
                for (RustRegexp pattern : patterns) {
                    if (pattern.find(input)) {
                        matches++;
                    }
                }
            }
            return matches;
        }

        @Override
        @CompilerControl(DONT_INLINE)
        long train(int patternIndex, Slice input)
        {
            RustRegexp compiled = patterns[patternIndex];
            RegexpMatcher matcher = matchers[patternIndex].reset(input);
            long matches = 0;
            while (matcher.find()) {
                matches++;
            }
            return (compiled.find(input) ? 1 : 0) + compiled.count(input) + (compiled.matches(input) ? 1 : 0) + (compiled.lookingAt(input) ? 1 : 0) + matches;
        }
    }

    /// Trino patterns, whose `find` is `contains`. Trino has no whole-input or anchored-prefix
    /// match, so `matches` and `lookingAt` throw.
    static final class TrinoPatterns
            extends LanguagePatterns
    {
        final TrinoRegexp target;
        final TrinoRegexp[] patterns;
        final TrinoRegexpMatcher targetMatcher;
        // One group-zero matcher per pattern, reset to each row.
        final TrinoRegexpMatcher[] matchers;

        private TrinoPatterns(List<String> expressions, boolean generated)
        {
            TrinoRegexp.Options options = TrinoRegexp.Options.defaults().setGenerateBytecode(generated);
            patterns = expressions.stream().map(expression -> TrinoRegexp.compile(utf8Slice(expression), options)).toArray(TrinoRegexp[]::new);
            target = patterns[0];
            matchers = Arrays.stream(patterns).map(pattern -> pattern.matcher(EMPTY_SLICE, 0)).toArray(TrinoRegexpMatcher[]::new);
            targetMatcher = matchers[0];
        }

        @Override
        boolean targetUsesGeneratedBytecode()
        {
            return target.usesGeneratedBytecode();
        }

        @Override
        int find(Slice[] inputs)
        {
            TrinoRegexp pattern = target;
            int matches = 0;
            for (Slice input : inputs) {
                if (pattern.contains(input)) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        long count(Slice[] inputs)
        {
            TrinoRegexp pattern = target;
            long matches = 0;
            for (Slice input : inputs) {
                matches += pattern.count(input);
            }
            return matches;
        }

        @Override
        int matches(Slice[] inputs)
        {
            throw new UnsupportedOperationException("Trino has no whole-input match operation");
        }

        @Override
        int lookingAt(Slice[] inputs)
        {
            throw new UnsupportedOperationException("Trino has no anchored-prefix match operation");
        }

        @Override
        long iterate(Slice[] inputs)
        {
            TrinoRegexpMatcher matcher = targetMatcher;
            long matches = 0;
            for (Slice input : inputs) {
                matcher.reset(input);
                while (matcher.find()) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        int sharedSite(Slice[] inputs)
        {
            TrinoRegexp[] patterns = this.patterns;
            int matches = 0;
            for (Slice input : inputs) {
                for (TrinoRegexp pattern : patterns) {
                    if (pattern.contains(input)) {
                        matches++;
                    }
                }
            }
            return matches;
        }

        @Override
        @CompilerControl(DONT_INLINE)
        long train(int patternIndex, Slice input)
        {
            TrinoRegexp compiled = patterns[patternIndex];
            TrinoRegexpMatcher matcher = matchers[patternIndex].reset(input);
            long matches = 0;
            while (matcher.find()) {
                matches++;
            }
            return (compiled.contains(input) ? 1 : 0) + compiled.count(input) + matches;
        }
    }

    /// Returns row `row` of a target. A LONG row fills the template's padding up to
    /// [#LONG_ROW_BYTES] with the target's [#filler(String)], repeated and cut to length; a SHORT
    /// row leaves it empty.
    private static byte[] row(String target, int row, boolean longRow)
    {
        String host = "host" + row + ".example.com";
        String template = switch (target) {
            case "ANCHORED_WWW_HOST" -> switch (row % 8) {
                case 0 -> "http://" + PAD + host + "/path";
                case 1 -> "https://www." + PAD + host + "/";
                case 2 -> "https://例え.テスト" + PAD + "/道";
                case 3 -> "https://" + PAD + host;
                case 4 -> "ftp://" + PAD + host + "/path";
                case 5 -> "https:///path/to/" + PAD + "page";
                case 6 -> "/search?q=" + PAD + host;
                default -> "https://" + PAD + host + "/?key=value";
            };
            case "UNANCHORED_URL" -> PAD + switch (row % 8) {
                case 0 -> "see https://" + host + "/path";
                case 1 -> "visit http://www." + host + "/";
                case 2 -> "no link in row " + row;
                case 3 -> "https://" + host;
                case 4 -> "ftp://" + host + "/path";
                case 5 -> "mirror at http:///path " + row;
                case 6 -> "hello world, row " + row + " has text";
                default -> "https://例え.テスト/道";
            };
            case "UNANCHORED_SET" -> PAD + switch (row % 8) {
                case 0 -> "/search?q=" + row + "&page=2";
                case 1 -> "/path/without/query/" + row;
                case 2 -> "/a?flag&key=value" + row;
                case 3 -> "/index.html#section-" + row;
                case 4 -> "/catalog/items?=empty" + row;
                case 5 -> "/list?sort&order" + row;
                case 6 -> "/profile?名前=値" + row;
                default -> "/reports/daily?id=" + row;
            };
            case "EXACT_LITERAL" -> PAD + switch (row % 8) {
                case 0 -> "WARN db-" + row + ": Connection refused";
                case 1 -> "INFO request " + row + " completed in 12ms";
                case 2 -> "ERROR upstream " + row + ": Connection reset by peer";
                case 3 -> "Connection refused";
                case 4 -> "connection refused by host" + row;
                case 5 -> "WARN 接続 " + row + " Connection refused";
                case 6 -> "DEBUG cache hit for key " + row;
                default -> "ERROR Connection refused (port " + row + ")";
            };
            case "CONTAINS" -> PAD + switch (row % 8) {
                case 0 -> "ERROR read timeout after 30s on " + row;
                case 1 -> "INFO GET /health 200 in " + row + "ms";
                case 2 -> "WARN connect timeout to db-" + row;
                case 3 -> "DEBUG retry " + row + " scheduled\nnext";
                case 4 -> "ERROR time out on socket " + row;
                case 5 -> "WARN 接続 timeout " + row;
                case 6 -> "INFO request " + row + " ok, timeouts=0";
                default -> "INFO session " + row + " expired";
            };
            case "CHARACTER_CLASS" -> PAD + switch (row % 8) {
                case 0 -> "2026-11-03 12:00:" + (10 + row % 50);
                case 1 -> "GET /api/v1/items/" + row;
                case 2 -> "127.0.0.1:" + (8000 + row % 1000) + " - - 200";
                case 3 -> "例えばテスト";
                case 4 -> "+1 (555) 010-" + (1000 + row);
                case 5 -> "user héloïse logged in " + row;
                case 6 -> "(" + row + ") 42.5% of 1024 #";
                default -> "internationalization";
            };
            case "LITERAL_ALTERNATION" -> PAD + switch (row % 8) {
                case 0 -> "2026-11-03T12:00:00Z ERROR db-" + row;
                case 1 -> "2026-11-03T12:00:00Z INFO ok " + row;
                case 2 -> "2026-11-03T12:00:00Z WARN slow " + row;
                case 3 -> "2026-11-03T12:00:00Z DEBUG cache " + row;
                case 4 -> "FATAL out of memory in worker " + row;
                case 5 -> "2026-11-03T12:00:00Z Error: " + row;
                case 6 -> "INFO 警告 queue depth " + row;
                default -> "2026-11-03 W " + row + " ERRO";
            };
            case "GENERAL" -> PAD + switch (row % 8) {
                case 0 -> "java.io.IOException: closed " + row;
                case 1 -> "INFO request " + row + " completed";
                case 2 -> "Caused by: NullPointerException at " + row;
                case 3 -> "ERROR exception in thread " + row;
                case 4 -> "WARN Exception " + row + " ignored";
                case 5 -> "TimeoutException after " + row + "ms";
                case 6 -> "例外 Exception " + row;
                default -> "IllegalStateException";
            };
            case "FIXED_WIDTH" -> PAD + switch (row % 8) {
                case 0 -> "order abcdefgh-1234 shipped " + row;
                case 1 -> "INFO request " + row + " completed";
                case 2 -> "id ABCDEFGH-1234 is upper case";
                case 3 -> "ref qwertyui-0042 row " + row;
                case 4 -> "short abc-1234 row " + row;
                case 5 -> "token zyxwvuts-98765";
                case 6 -> "INFO 名前 " + row + " has no code";
                default -> "INFO trace-" + row + " closed";
            };
            case "BYTE_TABLE" -> PAD + switch (row % 8) {
                case 0 -> "GET /api/v1/items/" + row;
                case 1 -> "key=value&id=" + row + "&page=2";
                case 2 -> "2026-11-03T12:00:00Z\tINFO";
                case 3 -> "internationalization" + row;
                case 4 -> "line one\nline two " + row;
                case 5 -> "https://例え.テスト/道/" + row;
                case 6 -> "a_b-c.d:e;f/" + row + "/g_h-i";
                default -> "WARN slow query " + row;
            };
            case "ANCHORED_LITERAL" -> switch (row % 8) {
                case 0 -> "GET /index.html HTTP/1.1 " + row;
                case 1 -> "POST /api/items HTTP/1.1 " + row;
                case 2 -> "GET /api/v1/items/" + row + "?page=2";
                case 3 -> "get /lowercase/" + row + " HTTP/1.0";
                case 4 -> " GET /leading/space/" + row;
                case 5 -> "GET /health?probe=" + row;
                case 6 -> "HEAD /index.html HTTP/1.1 " + row;
                default -> "GET /例え/テスト/" + row;
            } + PAD;
            case "FOLDED_PLAN" -> PAD + switch (row % 8) {
                case 0 -> "Content-Type:text/html; charset=utf-8";
                case 1 -> "content-type: application/json;" + row;
                case 2 -> "Content-Length: " + row + "; keep-alive";
                case 3 -> "CONTENT-TYPE:image/png" + row;
                case 4 -> "X-Content-Type:text/plain;" + row;
                case 5 -> "Accept: text/html; q=0." + row;
                case 6 -> "content-type:;" + row + " empty";
                default -> "Content-Type:テキスト/プレーン;" + row;
            };
            case "FOLDED_PAIR_SEQUENCE" -> PAD + switch (row % 8) {
                case 0 -> "INFO kubernetes pod " + row + " ready";
                case 1 -> "WARN Kafka lag " + row + " on topic";
                case 2 -> "ERROR Keepalive timeout on conn " + row;
                case 3 -> "DEBUG cache key " + row + " evicted";
                case 4 -> "INFO KAFKA-BROKER " + row + " online";
                case 5 -> "WARN 鍵 keystore " + row + " expires";
                case 6 -> "INFO worker " + row + " parked";
                default -> "ERROR checkpointing " + row + " failed";
            };
            case "INNER_LITERAL_FIXED_WIDTH" -> PAD + switch (row % 8) {
                case 0 -> "INFO publishing release notes for build " + row;
                case 1 -> "status is stale since sessions closed at " + row;
                case 2 -> "WARN washing cycle " + row + " paused";
                case 3 -> "the session list is sorted by user, page " + row;
                case 4 -> "finishing sync of " + row + " assets to storage";
                case 5 -> "WARN shins and sashes pass as usual " + row;
                case 6 -> "café sessions stay open, so status is " + row;
                default -> "phishing test mail " + row + " was flagged";
            };
            case "ORDERED_LITERALS" -> PAD + switch (row % 8) {
                case 0 -> "GET /about/contact " + row;
                case 1 -> "INFO request " + row + " done";
                case 2 -> "WARN cache miss for job " + row;
                case 3 -> "ERROR stale lab " + row + "\ncode 7";
                case 4 -> "DEBUG backlog " + row + " is empty";
                case 5 -> "INFO 計算 abacus " + row + " ok";
                case 6 -> "WARN slow query on db-" + row;
                default -> "INFO subscriber " + row + " attached";
            };
            case "LITERAL_SET" -> PAD + switch (row % 8) {
                case 0 -> "we met again after the storm, row " + row;
                case 1 -> "INFO request " + row + " completed in 12ms";
                case 2 -> "kitchen window left open, row " + row;
                case 3 -> "GET /api/v1/items/" + row + "?page=2";
                case 4 -> "ERROR disk full on node-" + row;
                case 5 -> "日本 garden party at " + row;
                case 6 -> "Garden " + row + ": Kitchen Window";
                default -> "warn: slow query on db-" + row;
            };
            case "SINGLE_BYTE" -> PAD + switch (row % 8) {
                case 0 -> "id,name,email,row " + row;
                case 1 -> "plain text row " + row + " without separators";
                case 2 -> "row " + row + ",alice,42,true";
                case 3 -> "key=value;other=" + row;
                case 4 -> "\"quoted, field\"," + row;
                case 5 -> "名前,値,行 " + row;
                case 6 -> "tab\tseparated\trow " + row;
                default -> ", leading comma on row " + row;
            };
            case "DOT_STAR_LITERAL" -> PAD + switch (row % 8) {
                case 0 -> "ERROR read timeout after 30s on " + row;
                case 1 -> "INFO GET /health 200 in " + row + "ms";
                case 2 -> "WARN connect timeout\nretry timeout " + row;
                case 3 -> "DEBUG retry " + row + " scheduled\nnext";
                case 4 -> "ERROR time out on socket " + row;
                case 5 -> "WARN 接続 timeout " + row;
                case 6 -> "INFO request " + row + " ok, timeouts=0";
                default -> "INFO session " + row + " expired";
            };
            default -> throw new IllegalArgumentException("unknown target: " + target);
        };
        int length = LONG_ROW_BYTES - template.replace(PAD, "").getBytes(UTF_8).length;
        String filler = filler(target);
        String padding = filler.repeat(length / filler.length() + 1).substring(0, length);
        return template.replace(PAD, longRow ? padding : "").getBytes(UTF_8);
    }

    /// Returns the ASCII text that a LONG row of `target` repeats as its padding, so cutting it
    /// to length never splits a character: spaces for CHARACTER_CLASS, FIXED_WIDTH, and
    /// FOLDED_PAIR_SEQUENCE, whose classes hold `x`; English rich in `s` without `shing` for
    /// INNER_LITERAL_FIXED_WIDTH; and `x` for every other target.
    private static String filler(String target)
    {
        return switch (target) {
            case "CHARACTER_CLASS", "FIXED_WIDTH", "FOLDED_PAIR_SEQUENCE" -> " ";
            case "INNER_LITERAL_FIXED_WIDTH" -> "the status of these sessions is still unsettled, so we must assess its uses. ";
            default -> "x";
        };
    }

    // Rows of every target in the run's `rows` length, and rows that the pollution patterns match,
    // so each pattern's find sees rows it matches and rows it does not, and the shared methods
    // train on the row length the run measures.
    private static Slice[] trainingInputs(String rows)
    {
        List<Slice> inputs = new ArrayList<>();
        for (int row = 0; row < 64; row++) {
            for (String target : targets()) {
                inputs.add(offsetInput(row(target, row, rows.equals("LONG"))));
            }
            inputs.add(offsetInput(("item_" + row + "-" + row + "/").getBytes(UTF_8)));
            inputs.add(offsetInput(("/search?id=" + row + "&page=2").getBytes(UTF_8)));
            inputs.add(offsetInput(("svc1://host" + row + ".example.com/").getBytes(UTF_8)));
        }
        return inputs.toArray(Slice[]::new);
    }

    // Each input is a view at a nonzero array offset, as in BenchmarkRe2ScanPlan.
    private static Slice offsetInput(byte[] bytes)
    {
        byte[] padded = new byte[bytes.length + 14];
        System.arraycopy(bytes, 0, padded, 7, bytes.length);
        return wrappedBuffer(padded, 7, bytes.length);
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkPatternDispatch.class, args);
        new Runner(options).run();
    }
}
