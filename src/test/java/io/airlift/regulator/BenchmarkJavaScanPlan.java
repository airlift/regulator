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

/// Public Java operations over the workloads and rows of [BenchmarkRe2ScanPlan], with each
/// pattern written in Java syntax for the same match: by default `$` becomes `\z`,
/// since Java's `$` also matches before a final line terminator; `(?i)` becomes
/// `(?iu)`, since Java's `(?i)` folds only ASCII letters and RE2's folds `K` with
/// the Kelvin sign; and without dotAll the pattern sets UNIX_LINES, so that its dot excludes only
/// `\n`, as RE2's does. [JavaRegexp] has no replacement, so there is no replace method.
/// No compilation cache is involved.
///
/// With `javaDollar=true`, each `$` stays Java's `$`, so those patterns follow
/// Java's end rather than RE2's. With dotAll, `$` also matches before one final line
/// terminator, so those patterns time the plan's Java end; without dotAll, under UNIX_LINES, it
/// matches only before a final `\n`, which the plan's end-before-LF step checks. No row ends
/// in a terminator other than `\n`, so the Java end's CR, CR LF, U+0085, U+2028, and U+2029
/// checks are not timed.
///
/// With `javaLineTerminators=true`, a pattern without dotAll does not set UNIX_LINES, so its
/// dot is Java's default dot, which also excludes CR, U+0085, U+2028, and U+2029, and with
/// `javaDollar=true` its `$` is Java's default end. No row holds CR, U+0085, U+2028,
/// or U+2029, so the default dot's extra exclusions are not timed. With dotAll the option changes
/// nothing.
///
/// With `scanPlan=false`, every pattern compiles without its scan plan, so the methods time the
/// ordinary engine on the same rows. The without-plan compile is resolved reflectively, so with
/// `-p scanPlan=true` this source also times a build without Java scan plans. With
/// `generateBytecode=true`, each pattern with a case-sensitive plan runs a class generated for
/// it; that option is also set reflectively, and only then.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkJavaScanPlan
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
                "FOLDED_HOST",
                "FOLDED_KEY",
                "PERL_ANCHORED",
                "PERL_UNANCHORED",
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

        // Keep each $ as Java's $ rather than \z.
        @Param({"true", "false"})
        public boolean javaDollar;

        // Without dotAll, keep Java's line terminators rather than setting UNIX_LINES.
        @Param({"true", "false"})
        public boolean javaLineTerminators;

        Slice[] expressions;
        private JavaRegexp.Options options;
        private MethodHandle compiler;
        JavaRegexp[] patterns;
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

            options = generateBytecode ? generatingOptions() : JavaRegexp.Options.defaults();
            compiler = compiler(scanPlan);
            int count = source.expressions.length;
            expressions = new Slice[count];
            patterns = new JavaRegexp[count];
            matchers = new Re2Matcher[count];
            bounds = new int[2];
            extractionGroups = new int[count];
            for (int index = 0; index < count; index++) {
                expressions[index] = utf8Slice(javaExpression(source.expressions[index].toStringUtf8(), dotAll, javaDollar, javaLineTerminators));
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
    public JavaRegexp compile(BenchmarkData data)
    {
        data.next();
        return compile(data.compiler, data.expressions[data.patternIndex], data.options);
    }

    /// Returns `expression`, a [BenchmarkRe2ScanPlan] pattern, in Java syntax. Only
    /// `$`, `(?i)`, and the dot differ in these patterns: each `$` outside a class
    /// becomes `\z` unless `javaDollar` keeps it, `(?i)` adds Unicode case, and
    /// without dotAll, UNIX_LINES limits the dot to excluding `\n` unless
    /// `javaLineTerminators` keeps Java's line terminators.
    static String javaExpression(String expression, boolean dotAll, boolean javaDollar, boolean javaLineTerminators)
    {
        StringBuilder java = new StringBuilder(dotAll || javaLineTerminators ? "" : "(?d)");
        boolean inClass = false;
        for (int index = 0; index < expression.length(); index++) {
            char character = expression.charAt(index);
            if (expression.startsWith("(?i)", index)) {
                java.append("(?iu)");
                index += "(?i)".length() - 1;
                continue;
            }
            if (character == '\\') {
                java.append(character).append(expression.charAt(++index));
                continue;
            }
            if (character == '[') {
                inClass = true;
            }
            else if (character == ']') {
                inClass = false;
            }
            java.append(character == '$' && !inClass && !javaDollar ? "\\z" : String.valueOf(character));
        }
        return java.toString();
    }

    /// Returns the handle that compiles a pattern through the public API, or through the compiler
    /// that disables scan plans.
    private static MethodHandle compiler(boolean scanPlan)
    {
        MethodType type = MethodType.methodType(JavaRegexp.class, Slice.class, JavaRegexp.Options.class);
        try {
            return scanPlan
                    ? MethodHandles.publicLookup().findStatic(JavaRegexp.class, "compile", type)
                    : MethodHandles.lookup().findStatic(JavaRegexp.class, "compileWithoutScanPlanForTesting", type);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot find JavaRegexp." + (scanPlan ? "compile" : "compileWithoutScanPlanForTesting"), e);
        }
    }

    /// Returns default options that generate bytecode, resolved reflectively so that this source
    /// also runs against a build without the option when it is not requested.
    private static JavaRegexp.Options generatingOptions()
    {
        JavaRegexp.Options options = JavaRegexp.Options.defaults();
        try {
            JavaRegexp.Options.class.getMethod("setGenerateBytecode", boolean.class).invoke(options, true);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot find JavaRegexp.Options.setGenerateBytecode", e);
        }
        return options;
    }

    private static JavaRegexp compile(MethodHandle compiler, Slice expression, JavaRegexp.Options options)
    {
        try {
            return (JavaRegexp) compiler.invokeExact(expression, options);
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
        Options options = buildOptions(BenchmarkJavaScanPlan.class, args);
        new Runner(options).run();
    }
}
