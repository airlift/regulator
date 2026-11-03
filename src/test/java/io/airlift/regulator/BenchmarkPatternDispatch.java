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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.openjdk.jmh.annotations.CompilerControl.Mode.DONT_INLINE;

/// Public `find`, `count`, and `matches` of RE2, Java, and Rust patterns over a batch of rows
/// on one target pattern, called the way an application calls a pattern it holds: one call site
/// that only ever sees that pattern. It measures profile pollution, what other patterns cost the
/// target when they have run first through the library code every pattern shares.
///
/// The targets are the three scan-plan targets of [BenchmarkRe2ScanPlan] workloads, the
/// start-anchored ANCHORED_WWW_HOST and the unanchored UNANCHORED_URL and UNANCHORED_SET, and one
/// target per common route of full-Slice `find` without a plan: EXACT_LITERAL searches for an
/// exact literal; CONTAINS for a literal between optional gaps; CHARACTER_CLASS runs a class
/// repeat on the class counter; LITERAL_ALTERNATION runs a small literal alternation on the span
/// matcher; and GENERAL has no plan and no literal or class shortcut, so it runs the ordinary DFA
/// search. TestBenchmarkPatternDispatch checks each route in every language. SHORT rows are 16 to
/// 64 bytes and LONG rows 1024 bytes, padded in the host run of the anchored pattern and before
/// the text of the others. LONG inputs contain padding, but anchored rows 4–6 reject before
/// reaching it. Each target's rows mix matching and non-matching log lines or URLs.
///
/// Each language compiles through its own public `compile(Slice, Options)`, and an adapter of
/// that language, [Re2Patterns], [JavaPatterns], or [RustPatterns], holds its patterns as the
/// language's public type and writes each measured loop out for that type. A benchmark method
/// calls the adapter once per invocation, outside the loop, and a fork creates adapters of one
/// language only, so that call sees one receiver. Inside the loop every call is of the language's
/// own type, as in an application written against that language: a JAVA run's call sites are
/// `JavaRegexp` sites. One loop calling the three languages through a shared interface would
/// add the many-receiver call site that this benchmark measures.
///
/// With `polluted=false`, setup compiles only the target, so no other pattern has run or loaded
/// a generated class: the run is the one-pattern baseline. With `polluted=true`, setup also
/// compiles a pollution set with the target's language and `generated` setting, every other
/// target and the six patterns of [#POLLUTION], and calls `find`, `count`, and `matches` on the
/// target and the pollution set, interleaved, through one non-inlined training method, with one
/// site per operation, for enough rounds to encourage C2 to compile the shared methods with the
/// profile of all of them. The measured methods then call only the target, so the difference from
/// `polluted=false` is the cost of reaching the target through shared methods whose profile holds
/// many patterns, with the other patterns' generated classes loaded. The `sharedSite` method calls
/// `find` of the target and then of every pollution pattern on each row, from one call site: the
/// loop of an application that holds many patterns. It reports the time per call. Its state always
/// compiles the pollution set and has no `polluted` parameter, since its own loop trains the shared
/// methods with every pattern.
///
/// With `generated=true`, every pattern compiles with generated bytecode requested. Setup
/// requires the target to have a generated class, which only the scan-plan targets get;
/// pollution patterns without a generated class stay interpreted. Setup does not run a
/// compilation without generated bytecode next to the target, which would add a second class to
/// the shared methods' profile; TestBenchmarkPatternDispatch checks every method's results
/// against one. Setup also requires each target's rows to mix matching and non-matching `find`
/// results. It does not require full matches: some targets' rows never match whole, and
/// `matches` then times the route that rejects them.
///
/// The benchmark uses only the public API of `Re2`, `JavaRegexp`, and `RustRegexp`, so this
/// source also compares builds that change how a pattern reaches its generated class.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkPatternDispatch
{
    private static final int ROWS = 1024;
    private static final int LONG_ROW_BYTES = 1024;
    // Calls of find, count, and matches per pattern before measurement, to encourage C2 to compile
    // the shared methods with the polluted profile.
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
    // The target, the seven other targets, and the POLLUTION patterns.
    private static final int SHARED_SITE_PATTERNS = 14;

    private static volatile long trainingSink;

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"RE2", "JAVA", "RUST"})
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
        })
        public String target;

        // SHORT rows are 16 to 64 bytes; LONG rows are 1024 bytes.
        @Param({"SHORT", "LONG"})
        public String rows;

        @Param({"false", "true"})
        public boolean polluted;

        // `true` is valid only for the scan-plan targets, the ones with a generated class.
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
                Slice[] trainingInputs = trainingInputs();
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
        @Param({"RE2", "JAVA", "RUST"})
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
        })
        public String target;

        // SHORT rows are 16 to 64 bytes; LONG rows are 1024 bytes.
        @Param({"SHORT", "LONG"})
        public String rows;

        // `true` is valid only for the scan-plan targets, the ones with a generated class.
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
                    RustPatterns
    {
        abstract boolean targetUsesGeneratedBytecode();

        abstract int find(Slice[] inputs);

        abstract long count(Slice[] inputs);

        abstract int matches(Slice[] inputs);

        abstract int sharedSite(Slice[] inputs);

        /// Runs `find`, `count`, and `matches` of shared-site pattern `patternIndex` on `input`.
        /// Each implementation is setup's one non-inlined training method, with one site per
        /// operation shared by every pattern, so its own profile never narrows what the shared
        /// library methods see.
        abstract long train(int patternIndex, Slice input);
    }

    static final class Re2Patterns
            extends LanguagePatterns
    {
        final Re2 target;
        final Re2[] patterns;

        private Re2Patterns(List<String> expressions, boolean generated)
        {
            Re2.Options options = Re2.Options.defaults().setGenerateBytecode(generated);
            patterns = expressions.stream().map(expression -> Re2.compile(utf8Slice(expression), options)).toArray(Re2[]::new);
            target = patterns[0];
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
            return (compiled.find(input) ? 1 : 0) + compiled.count(input) + (compiled.matches(input) ? 1 : 0);
        }
    }

    static final class JavaPatterns
            extends LanguagePatterns
    {
        final JavaRegexp target;
        final JavaRegexp[] patterns;

        private JavaPatterns(List<String> expressions, boolean generated)
        {
            JavaRegexp.Options options = JavaRegexp.Options.defaults().setGenerateBytecode(generated);
            patterns = expressions.stream().map(expression -> JavaRegexp.compile(utf8Slice(expression), options)).toArray(JavaRegexp[]::new);
            target = patterns[0];
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
            return (compiled.find(input) ? 1 : 0) + compiled.count(input) + (compiled.matches(input) ? 1 : 0);
        }
    }

    static final class RustPatterns
            extends LanguagePatterns
    {
        final RustRegexp target;
        final RustRegexp[] patterns;

        private RustPatterns(List<String> expressions, boolean generated)
        {
            RustRegexp.Options options = RustRegexp.Options.defaults().setGenerateBytecode(generated);
            patterns = expressions.stream().map(expression -> RustRegexp.compile(utf8Slice(expression), options)).toArray(RustRegexp[]::new);
            target = patterns[0];
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
            return (compiled.find(input) ? 1 : 0) + compiled.count(input) + (compiled.matches(input) ? 1 : 0);
        }
    }

    /// Returns row `row` of a target. A LONG row fills the template's padding up to
    /// [#LONG_ROW_BYTES] with spaces for CHARACTER_CLASS, whose class holds `x`, and with `x`
    /// for every other target; a SHORT row leaves it empty.
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
            default -> throw new IllegalArgumentException("unknown target: " + target);
        };
        String padding = target.equals("CHARACTER_CLASS") ? " " : "x";
        int length = template.replace(PAD, "").getBytes(UTF_8).length;
        return template.replace(PAD, longRow ? padding.repeat(LONG_ROW_BYTES - length) : "").getBytes(UTF_8);
    }

    // Short rows of every target and rows that the pollution patterns match, so each pattern's
    // find sees rows it matches and rows it does not.
    private static Slice[] trainingInputs()
    {
        List<Slice> inputs = new ArrayList<>();
        for (int row = 0; row < 64; row++) {
            for (String target : targets()) {
                inputs.add(offsetInput(row(target, row, false)));
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
