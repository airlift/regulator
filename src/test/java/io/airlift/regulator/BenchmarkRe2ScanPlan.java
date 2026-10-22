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

import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Public RE2 operations over the patterns and rows of {@link BenchmarkTrinoScanPlan}, plus non-ASCII
 * and malformed UTF-8 rows for its URL, UNANCHORED_URL, LONG_HOST, and UNANCHORED_TAIL_4096
 * workloads, whose delimiter runs and tails cross that text. The patterns keep their Trino text, so
 * {@code $} is RE2's strict end. No compilation cache is involved.
 * <p>
 * With {@code scanPlan=false}, every pattern compiles without its scan plan, so the methods time the
 * ordinary engine on the same rows. The without-plan compile is resolved reflectively, so with
 * {@code -p scanPlan=true} this source also times a build without RE2 scan plans. With
 * {@code generateBytecode=true}, each pattern with a case-sensitive plan runs a class generated for
 * it; that option is also set reflectively, and only then.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkRe2ScanPlan
{
    private static final String NON_ASCII = "_NON_ASCII";
    private static final String MALFORMED = "_MALFORMED";

    // Accepted by RE2's UTF-8 classes even though they are not valid UTF-8: an overlong
    // three-byte form, an encoded surrogate, and a four-byte form above U+10FFFF.
    private static final byte[][] ACCEPTED_MALFORMED = {
            {(byte) 0xE0, (byte) 0x80, (byte) 0x80},
            {(byte) 0xED, (byte) 0xA0, (byte) 0x80},
            {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
    };
    // Rejected by every RE2 class: a bare continuation, an invalid lead, and truncated two- and
    // four-byte sequences.
    private static final byte[][] REJECTED_MALFORMED = {
            {(byte) 0x80},
            {(byte) 0xFF},
            {(byte) 0xC2},
            {(byte) 0xF1, (byte) 0x80, (byte) 0x80},
    };

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        // The BenchmarkTrinoScanPlan workloads, then the same patterns over non-ASCII and malformed rows.
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
        private Re2.Options options;
        private MethodHandle compiler;
        Re2[] patterns;
        private Re2Matcher[] matchers;
        private int[] bounds;
        private int[] extractionGroups;
        private Slice[] rewrites;
        Slice[] inputs;
        private int cursor;
        private int patternIndex;

        @Setup
        public void setup()
        {
            BenchmarkTrinoScanPlan.BenchmarkData source = new BenchmarkTrinoScanPlan.BenchmarkData();
            source.workload = trinoWorkload(workload);
            source.dotAll = dotAll;
            source.setup();

            options = generateBytecode ? generatingOptions() : Re2.Options.defaults();
            compiler = compiler(scanPlan);
            expressions = source.expressions;
            int count = expressions.length;
            patterns = new Re2[count];
            matchers = new Re2Matcher[count];
            bounds = new int[2];
            extractionGroups = new int[count];
            rewrites = new Slice[count];
            for (int index = 0; index < count; index++) {
                patterns[index] = compile(compiler, expressions[index], options);
                matchers[index] = patterns[index].matcher(EMPTY_SLICE);
                extractionGroups[index] = patterns[index].capturingGroupCount() == 0 ? 0 : 1;
                rewrites[index] = utf8Slice("\\" + extractionGroups[index]);
            }

            if (workload.equals(source.workload)) {
                inputs = source.inputs;
                return;
            }
            inputs = new Slice[source.inputs.length];
            for (int row = 0; row < inputs.length; row++) {
                byte[] bytes = workload.endsWith(NON_ASCII) ? nonAsciiRow(source.workload, row).getBytes(UTF_8) : malformedRow(source.workload, row);
                inputs[row] = offsetInput(bytes);
            }
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
    public Slice replace(BenchmarkData data)
    {
        Slice input = data.next();
        return data.patterns[data.patternIndex].replaceAll(input, data.rewrites[data.patternIndex]).result();
    }

    @Benchmark
    public Re2 compile(BenchmarkData data)
    {
        data.next();
        return compile(data.compiler, data.expressions[data.patternIndex], data.options);
    }

    /**
     * Returns the handle that compiles a pattern through the public API, or through the compiler
     * that disables scan plans.
     */
    private static MethodHandle compiler(boolean scanPlan)
    {
        MethodType type = MethodType.methodType(Re2.class, Slice.class, Re2.Options.class);
        try {
            return scanPlan
                    ? MethodHandles.publicLookup().findStatic(Re2.class, "compile", type)
                    : MethodHandles.lookup().findStatic(Re2.class, "compileWithoutScanPlanForTesting", type);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot find Re2." + (scanPlan ? "compile" : "compileWithoutScanPlanForTesting"), e);
        }
    }

    /**
     * Returns default options that generate bytecode, resolved reflectively so that this source
     * also runs against a build without the option when it is not requested.
     */
    private static Re2.Options generatingOptions()
    {
        Re2.Options options = Re2.Options.defaults();
        try {
            Re2.Options.class.getMethod("setGenerateBytecode", boolean.class).invoke(options, true);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot find Re2.Options.setGenerateBytecode", e);
        }
        return options;
    }

    private static Re2 compile(MethodHandle compiler, Slice expression, Re2.Options options)
    {
        try {
            return (Re2) compiler.invokeExact(expression, options);
        }
        catch (Throwable throwable) {
            throw new IllegalStateException("Cannot compile " + expression.toStringUtf8(), throwable);
        }
    }

    /**
     * Returns the {@code workload} parameter values in declaration order.
     */
    static List<String> workloads()
            throws NoSuchFieldException
    {
        return List.of(BenchmarkData.class.getDeclaredField("workload").getAnnotation(Param.class).value());
    }

    /**
     * Returns the {@link BenchmarkTrinoScanPlan} workload whose patterns a workload uses.
     */
    static String trinoWorkload(String workload)
    {
        for (String suffix : new String[] {NON_ASCII, MALFORMED}) {
            if (workload.endsWith(suffix)) {
                return workload.substring(0, workload.length() - suffix.length());
            }
        }
        return workload;
    }

    // Every row is valid UTF-8 and the runs and tails are mostly multibyte.
    private static String nonAsciiRow(String trinoWorkload, int row)
    {
        String host = row % 2 == 0 ? "хост" + row + ".пример.рф" : "主机" + row + ".例え.テスト";
        String scheme = (row % 2 == 0 ? "https://" : "http://") + (row % 3 == 0 ? "www." : "");
        return switch (trinoWorkload) {
            case "URL" -> scheme + host + "/путь/路径?запрос=" + row;
            case "UNANCHORED_URL" -> switch (row % 4) {
                case 0 -> "";
                case 1 -> "префикс ";
                case 2 -> "例え".repeat(5) + " ";
                default -> "й".repeat(64) + " ";
            } + ((row / 4) % 4 == 3 ? scheme + host : scheme + host + "/путь");
            case "LONG_HOST" -> "https://" + "й".repeat(2048) + host + "/путь";
            case "UNANCHORED_TAIL_4096" -> {
                String prefix = "ж".repeat(row % 32) + "abc" + host;
                int remaining = 4096 - prefix.getBytes(UTF_8).length;
                yield prefix + "路".repeat(remaining / 3) + "y".repeat(remaining % 3);
            }
            default -> throw new IllegalArgumentException("no non-ASCII rows for workload: " + trinoWorkload);
        };
    }

    // RE2's classes accept some malformed sequences and stop at others, so rows mix both.
    private static byte[] malformedRow(String trinoWorkload, int row)
    {
        byte[] accepted = ACCEPTED_MALFORMED[row % ACCEPTED_MALFORMED.length];
        byte[] rejected = REJECTED_MALFORMED[(row / 4) % REJECTED_MALFORMED.length];
        String host = "host" + row + ".example.com";
        String scheme = (row % 2 == 0 ? "https://" : "http://") + (row % 3 == 0 ? "www." : "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (trinoWorkload) {
            case "URL" -> {
                // Rows cycle an accepted or a rejected sequence through the host run and the path tail.
                out.writeBytes(scheme.getBytes(UTF_8));
                out.writeBytes(("host" + row).getBytes(UTF_8));
                out.writeBytes(row % 4 == 0 ? accepted : row % 4 == 2 ? rejected : new byte[0]);
                out.writeBytes(".example.com/pa".getBytes(UTF_8));
                out.writeBytes(row % 4 == 1 ? accepted : row % 4 == 3 ? rejected : new byte[0]);
                out.writeBytes(("th?q=" + row).getBytes(UTF_8));
            }
            case "UNANCHORED_URL" -> {
                // Rejected bytes precede the URL; the host run holds an accepted or rejected sequence.
                out.writeBytes("x".repeat((row % 4) * 16).getBytes(UTF_8));
                out.writeBytes(rejected);
                out.writeBytes(("prefix " + scheme + "host" + row).getBytes(UTF_8));
                out.writeBytes((row / 4) % 2 == 0 ? accepted : rejected);
                out.writeBytes(".example.com/path".getBytes(UTF_8));
            }
            case "LONG_HOST" -> {
                // A host of at least 4096 bytes of accepted sequences; odd rows stop the run before the
                // path separator.
                out.writeBytes("https://".getBytes(UTF_8));
                while (out.size() < "https://".length() + 4096) {
                    out.writeBytes(accepted);
                }
                out.writeBytes(host.getBytes(UTF_8));
                if (row % 2 != 0) {
                    out.writeBytes(rejected);
                }
                out.writeBytes("/path".getBytes(UTF_8));
            }
            case "UNANCHORED_TAIL_4096" -> {
                // A tail of accepted sequences that fills the row to 4096 bytes; odd rows stop it at a
                // rejected sequence at a varying depth.
                out.writeBytes("x".repeat(row % 32).getBytes(UTF_8));
                out.writeBytes(rejected);
                out.writeBytes(("abc" + host).getBytes(UTF_8));
                int stop = row % 2 == 0 ? -1 : 64 + (row % 61) * 64;
                while (out.size() + 4 <= 4096) {
                    if (stop >= 0 && out.size() >= stop) {
                        out.writeBytes(rejected);
                        stop = -1;
                    }
                    else {
                        out.writeBytes(accepted);
                    }
                }
                out.writeBytes("y".repeat(4096 - out.size()).getBytes(UTF_8));
            }
            default -> throw new IllegalArgumentException("no malformed rows for workload: " + trinoWorkload);
        }
        return out.toByteArray();
    }

    // Each input is a view at a nonzero array offset, as in BenchmarkTrinoScanPlan.
    private static Slice offsetInput(byte[] bytes)
    {
        byte[] padded = new byte[bytes.length + 14];
        System.arraycopy(bytes, 0, padded, 7, bytes.length);
        return wrappedBuffer(padded, 7, bytes.length);
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkRe2ScanPlan.class, args);
        new Runner(options).run();
    }
}
