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
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;

/// Public Rust operations over the workloads and rows of [BenchmarkRe2ScanPlan], with each
/// pattern written in Rust syntax for the same match: `\d` and `\w` become
/// `(?-u:\d)` and `(?-u:\w)`, since Rust's Perl classes are Unicode and RE2's are
/// ASCII. The rest keeps its RE2 text: without multi-line mode Rust's `$` is the strict end,
/// as RE2's is; Rust's `(?i)` folds with simple case folding, as RE2's does, so `K` also
/// matches the Kelvin sign; and without dotAll Rust's dot excludes only `\n`, as RE2's does.
/// [RustRegexp] has no replacement, so there is no replace method. No compilation cache is
/// involved.
///
/// With `scanPlan=false`, every pattern compiles without its scan plan, so the methods time the
/// ordinary engine on the same rows. The without-plan compile is resolved reflectively, so with
/// `-p scanPlan=true` this source also times a build without Rust scan plans. With
/// `generateBytecode=true`, each pattern with a case-sensitive plan runs a class generated for
/// it; that option is also set reflectively, and only then.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkRustScanPlan
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        // The BenchmarkRe2ScanPlan workloads.
        @Param({
                "URL",
                "URL_SHORT",
                "URL_128",
                "URL_4096",
                "ANCHORED_PREFIX",
                "ANCHORED_QUERY",
                "ANCHORED_HOST",
                "ANCHORED_HOST_4096",
                "ANCHORED_WWW_HOST",
                "UNANCHORED_URL",
                "UNANCHORED_END",
                "UNANCHORED_RUN",
                "UNANCHORED_RUN_LEADING",
                "UNANCHORED_SET",
                "UNANCHORED_MIXED",
                "UNANCHORED_TAIL_4096",
                "LONG_UNANCHORED_RUN",
                "FOLDED_ANCHORED",
                "FOLDED_OPTIONAL",
                "FOLDED_UNANCHORED",
                "FOLDED_RUN",
                "FOLDED_MIXED",
                "LITERAL_REPEATS",
                "LITERAL_CONTROLS",
                "DEEP_REJECTIONS",
                "MIXED",
                "LONG_PATH",
                "LONG_HOST",
                "FAMILY",
                "DIVERSE",
                "FALLBACK",
                "SETS",
                "BOUNDED",
                "LONG_SET",
                "LONG_ASCII",
                "SET_MIXED",
                "OPTIONALS",
                "OPTIONAL_RETRY",
                "OPTIONAL_MIXED",
                "LONG_OPTIONAL",
                "RETRY_64",
                "RETRY_127",
                "RETRY_128",
                "RETRY_256",
                "LONG_RETRY",
                "LOWERED_FALLBACK",
                "URL_NON_ASCII",
                "URL_MALFORMED",
                "UNANCHORED_URL_NON_ASCII",
                "UNANCHORED_URL_MALFORMED",
                "LONG_HOST_NON_ASCII",
                "LONG_HOST_MALFORMED",
                "UNANCHORED_TAIL_4096_NON_ASCII",
                "UNANCHORED_TAIL_4096_MALFORMED",
        })
        public String workload;

        @Param({"true", "false"})
        public boolean dotAll;

        @Param({"true", "false"})
        public boolean scanPlan;

        // Experimental per-pattern scan kernels; false compiles every pattern as it would without
        // the option.
        @Param("false")
        public boolean generateBytecode;

        Slice[] expressions;
        private RustRegexp.Options options;
        private MethodHandle compiler;
        RustRegexp[] patterns;
        private Re2Matcher[] matchers;
        private int[] bounds;
        private int[] extractionGroups;
        Slice[] inputs;
        private int cursor;
        private int patternIndex;

        @Setup
        public void setup()
        {
            BenchmarkRe2ScanPlan.BenchmarkData source = new BenchmarkRe2ScanPlan.BenchmarkData();
            source.workload = workload;
            source.dotAll = dotAll;
            source.scanPlan = true;
            source.setup();

            options = generateBytecode ? generatingOptions() : RustRegexp.Options.defaults();
            compiler = compiler(scanPlan);
            int count = source.expressions.length;
            expressions = new Slice[count];
            patterns = new RustRegexp[count];
            matchers = new Re2Matcher[count];
            bounds = new int[2];
            extractionGroups = new int[count];
            for (int index = 0; index < count; index++) {
                expressions[index] = utf8Slice(rustExpression(source.expressions[index].toStringUtf8()));
                patterns[index] = compile(compiler, expressions[index], options);
                matchers[index] = patterns[index].matcher(EMPTY_SLICE);
                extractionGroups[index] = patterns[index].capturingGroupCount() == 0 ? 0 : 1;
            }
            inputs = source.inputs;
        }

        private Slice next()
        {
            int row = cursor++;
            if (cursor == inputs.length) {
                cursor = 0;
            }
            patternIndex = row % patterns.length;
            return inputs[row];
        }
    }

    @Benchmark
    public boolean contains(BenchmarkData data)
    {
        Slice input = data.next();
        return data.patterns[data.patternIndex].find(input);
    }

    @Benchmark
    public long count(BenchmarkData data)
    {
        Slice input = data.next();
        return data.patterns[data.patternIndex].count(input);
    }

    @Benchmark
    public long boundaries(BenchmarkData data)
    {
        Slice input = data.next();
        int[] bounds = data.bounds;
        return data.patterns[data.patternIndex].findInto(input, bounds) ? ((long) bounds[0] << 32) | bounds[1] : -1;
    }

    @Benchmark
    public Slice extract(BenchmarkData data)
    {
        Slice input = data.next();
        Re2Matcher matcher = data.matchers[data.patternIndex];
        return matcher.reset(input).find() ? matcher.group(data.extractionGroups[data.patternIndex]) : null;
    }

    @Benchmark
    public List<Slice> extractAll(BenchmarkData data)
    {
        Slice input = data.next();
        Re2Matcher matcher = data.matchers[data.patternIndex].reset(input);
        int group = data.extractionGroups[data.patternIndex];
        List<Slice> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(matcher.group(group));
        }
        return matches;
    }

    @Benchmark
    public RustRegexp compile(BenchmarkData data)
    {
        data.next();
        return compile(data.compiler, data.expressions[data.patternIndex], data.options);
    }

    /// Returns `expression`, a [BenchmarkRe2ScanPlan] pattern, in Rust syntax. Only the
    /// Perl classes `\d` and `\w` differ in these patterns, and each turns off Unicode
    /// mode for itself. Any other Perl class, or one inside a bracketed class, has no such rewrite,
    /// so it throws [IllegalArgumentException].
    static String rustExpression(String expression)
    {
        StringBuilder rust = new StringBuilder();
        boolean inClass = false;
        for (int index = 0; index < expression.length(); index++) {
            char character = expression.charAt(index);
            if (character == '\\') {
                char escaped = expression.charAt(++index);
                if ("DsSW".indexOf(escaped) >= 0 || (inClass && (escaped == 'd' || escaped == 'w'))) {
                    throw new IllegalArgumentException("Perl class without a Rust rewrite: " + expression);
                }
                if (escaped == 'd' || escaped == 'w') {
                    rust.append("(?-u:\\").append(escaped).append(')');
                }
                else {
                    rust.append(character).append(escaped);
                }
                continue;
            }
            if (character == '[') {
                inClass = true;
            }
            else if (character == ']') {
                inClass = false;
            }
            rust.append(character);
        }
        return rust.toString();
    }

    /// Returns the handle that compiles a pattern through the public API, or through the compiler
    /// that disables scan plans.
    private static MethodHandle compiler(boolean scanPlan)
    {
        MethodType type = MethodType.methodType(RustRegexp.class, Slice.class, RustRegexp.Options.class);
        try {
            return scanPlan
                    ? MethodHandles.publicLookup().findStatic(RustRegexp.class, "compile", type)
                    : MethodHandles.lookup().findStatic(RustRegexp.class, "compileWithoutScanPlanForTesting", type);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot find RustRegexp." + (scanPlan ? "compile" : "compileWithoutScanPlanForTesting"), e);
        }
    }

    /// Returns default options that generate bytecode, resolved reflectively so that this source
    /// also runs against a build without the option when it is not requested.
    private static RustRegexp.Options generatingOptions()
    {
        RustRegexp.Options options = RustRegexp.Options.defaults();
        try {
            RustRegexp.Options.class.getMethod("setGenerateBytecode", boolean.class).invoke(options, true);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot find RustRegexp.Options.setGenerateBytecode", e);
        }
        return options;
    }

    private static RustRegexp compile(MethodHandle compiler, Slice expression, RustRegexp.Options options)
    {
        try {
            return (RustRegexp) compiler.invokeExact(expression, options);
        }
        catch (Throwable throwable) {
            throw new IllegalStateException("Cannot compile " + expression.toStringUtf8(), throwable);
        }
    }

    /// Returns the `workload` parameter values in declaration order.
    static List<String> workloads()
            throws NoSuchFieldException
    {
        return List.of(BenchmarkData.class.getDeclaredField("workload").getAnnotation(Param.class).value());
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkRustScanPlan.class, args);
        new Runner(options).run();
    }
}
