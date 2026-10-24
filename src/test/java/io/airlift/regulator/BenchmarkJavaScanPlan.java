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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;

/**
 * Public Java operations over the workloads and rows of {@link BenchmarkRe2ScanPlan}, with each
 * pattern written in Java syntax for the same match: {@code $} becomes {@code \z}, since Java's
 * {@code $} also matches before a final line terminator; {@code (?i)} becomes {@code (?iu)}, since
 * Java's {@code (?i)} folds only ASCII letters and RE2's folds {@code K} with the Kelvin sign; and
 * without dotAll the pattern sets UNIX_LINES, so that its dot excludes only {@code \n}, as RE2's
 * does. {@link JavaRegexp} has no replacement, so there is no replace method. No compilation cache
 * is involved.
 */
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
        })
        public String workload;

        @Param({"true", "false"})
        public boolean dotAll;

        Slice[] expressions;
        private JavaRegexp.Options options;
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

            options = JavaRegexp.Options.defaults();
            int count = source.expressions.length;
            expressions = new Slice[count];
            patterns = new JavaRegexp[count];
            matchers = new Re2Matcher[count];
            bounds = new int[2];
            extractionGroups = new int[count];
            for (int index = 0; index < count; index++) {
                expressions[index] = utf8Slice(javaExpression(source.expressions[index].toStringUtf8(), dotAll));
                patterns[index] = JavaRegexp.compile(expressions[index], options);
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
        return JavaRegexp.compile(data.expressions[data.patternIndex], data.options);
    }

    /**
     * Returns {@code expression}, a {@link BenchmarkRe2ScanPlan} pattern, in Java syntax. Only
     * {@code $}, {@code (?i)}, and the dot differ in these patterns: each {@code $} outside a class
     * becomes {@code \z}, {@code (?i)} adds Unicode case, and without dotAll, UNIX_LINES limits
     * the dot to excluding {@code \n}.
     */
    static String javaExpression(String expression, boolean dotAll)
    {
        StringBuilder java = new StringBuilder(dotAll ? "" : "(?d)");
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
            java.append(character == '$' && !inClass ? "\\z" : String.valueOf(character));
        }
        return java.toString();
    }

    /**
     * Returns the {@code workload} parameter values in declaration order.
     */
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
