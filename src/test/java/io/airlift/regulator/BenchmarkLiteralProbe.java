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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
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

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Unanchored searches led by a literal over synthetic application or access log lines, where
 * common letters make a first-byte scan stop every few bytes. Covers the RE2 prefix-acceleration
 * scan, directly and through public operations, and the Trino scan-plan leading-literal scan.
 * {@code SERVICE} and {@code TOKEN} literals probe a rarer letter than their first byte and are
 * expected wins. The rest keep the first byte and are protected controls: {@code USER} and
 * {@code ERROR} have rarer punctuation that is never probed, and {@code HTTPS} searches access
 * log lines dense with slashes, the byte a punctuation probe would choose.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 7, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkLiteralProbe
{
    @Param({"SERVICE", "TOKEN", "USER", "ERROR", "GET", "URL", "HTTPS"})
    Workload workload;

    @Param({"LATE_MATCH", "EVERY_LINE"})
    InputShape inputShape;

    @Param("65536")
    int sourceLength;

    private Re2 re2;
    private Prog program;
    private TrinoRegexp trino;
    private Slice input;
    private int lineCount;

    @Setup(Level.Trial)
    public void setup()
    {
        Slice expression = Slices.utf8Slice(workload.expression());
        re2 = Re2.compile(expression);
        program = re2.forwardProgramForDiagnostics();
        trino = TrinoRegexp.compile(expression);
        input = logLines(workload, inputShape, sourceLength);
        lineCount = 0;
        for (int index = 0; index < input.length(); index++) {
            if (input.getByte(index) == '\n') {
                lineCount++;
            }
        }
        if (!program.canPrefixAccel() || program.prefixAccelStrategy(input.length()) != Prog.PrefixAccelStrategy.REPEATED_BYTE) {
            throw new IllegalStateException("Benchmark did not select the repeated-byte prefix scan");
        }
        if (!trino.pattern().usesTrinoScanPlanForDiagnostics()) {
            throw new IllegalStateException("Benchmark did not select the Trino scan plan");
        }
        if (re2Count() != expectedCount() || trinoCount() != expectedCount() || dfaSearch() < 0) {
            throw new IllegalStateException("Benchmark input produced an unexpected result");
        }
    }

    /**
     * Searches the whole input with the forward DFA, which scans with prefix acceleration.
     */
    @Benchmark
    public long dfaSearch()
    {
        return Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true);
    }

    @Benchmark
    public boolean re2Find()
    {
        return re2.find(input);
    }

    @Benchmark
    public long re2Count()
    {
        return re2.count(input);
    }

    @Benchmark
    public boolean trinoContains()
    {
        return trino.contains(input);
    }

    @Benchmark
    public long trinoCount()
    {
        return trino.count(input);
    }

    Prog program()
    {
        return program;
    }

    long expectedCount()
    {
        return inputShape == InputShape.LATE_MATCH ? 1 : lineCount;
    }

    /**
     * Returns whole log lines of the workload's format totalling at least {@code length} bytes.
     * The workload's literal occurs once, in the last line ({@code LATE_MATCH}), or once per line
     * ({@code EVERY_LINE}), and the rest of the text contains no workload's literal.
     */
    static Slice logLines(Workload workload, InputShape inputShape, int length)
    {
        StringBuilder text = new StringBuilder(length + 256);
        for (int line = 0; text.length() < length; line++) {
            String plain = workload.line(line, "");
            boolean last = text.length() + plain.length() >= length;
            text.append(inputShape == InputShape.EVERY_LINE || last ? workload.line(line, " " + workload.occurrence(line)) : plain);
        }
        return Slices.wrappedBuffer(text.toString().getBytes(UTF_8));
    }

    private static String logLine(int line, String target)
    {
        String level = switch (line % 3) {
            case 0 -> "INFO ";
            case 1 -> "WARN ";
            default -> "DEBUG";
        };
        String message = switch (line % 4) {
            case 0 -> "session started for customer " + (line * 7919 % 100_000) + " in region us-east-" + (line % 4);
            case 1 -> "retrying request " + line + ", server responded slowly after " + (line % 900) + " ms";
            case 2 -> "cache miss for key order-" + (line * 31 % 10_000) + ", fetching from store replica " + (line % 3);
            default -> "processed " + (line % 50) + " messages, queue depth " + (line % 200) + ", errors=0";
        };
        return String.format(Locale.ROOT, "2026-10-05 12:%02d:%02d.%03d %s [worker-%d] %s%s\n", line / 60 % 60, line % 60, line * 37 % 1000, level, line % 16, message, target);
    }

    /**
     * Returns an access log line with about a dozen slashes and no lowercase {@code h}.
     */
    private static String accessLine(int line, String target)
    {
        String method = switch (line % 3) {
            case 0 -> "POST";
            case 1 -> "PUT";
            default -> "DELETE";
        };
        return String.format(
                Locale.ROOT,
                "10.%d.%d.%d - - [05/Oct/2026:12:%02d:%02d +0000] \"%s /api/v2/accounts/%d/orders/%d/items?page=%d HTTP/1.1\" %d %d \"-\" \"curl/8.%d.0\"%s\n",
                line % 4,
                line * 7 % 256,
                line * 13 % 256,
                line / 60 % 60,
                line % 60,
                method,
                line * 7919 % 100_000,
                line * 31 % 10_000,
                line % 9,
                line % 5 == 0 ? 201 : 200,
                512 + line * 37 % 4096,
                line % 7,
                target);
    }

    public enum Workload
    {
        SERVICE("svc1://([^/ ]+)/", "svc1://", 1),
        TOKEN("token=(\\w+)", "token=", 2),
        USER("user=(\\w+)", "user=", 0),
        ERROR("error: (\\d+)", "error: ", 0),
        GET("GET /(\\S+)", "GET /", 0),
        URL("https?://([^/ ]+)/", "http", 0),
        HTTPS("https://([^/ ]+)/", "https://", 0);

        private final String expression;
        private final String literal;
        private final int probeOffset;

        Workload(String expression, String literal, int probeOffset)
        {
            this.expression = expression;
            this.literal = literal;
            this.probeOffset = probeOffset;
        }

        String expression()
        {
            return expression;
        }

        String literal()
        {
            return literal;
        }

        int probeOffset()
        {
            return probeOffset;
        }

        String line(int line, String target)
        {
            return this == HTTPS ? accessLine(line, target) : logLine(line, target);
        }

        String occurrence(int line)
        {
            return switch (this) {
                case SERVICE -> "svc1://shard-" + (line % 8) + ".internal/orders";
                case TOKEN -> "token=tk" + line;
                case USER -> "user=account" + line;
                case ERROR -> "error: " + (500 + line % 100);
                case GET -> "GET /orders/" + line + ".json";
                case URL -> "https://host" + (line % 10) + ".example/path";
                case HTTPS -> "https://cdn" + (line % 10) + ".example/assets/app.js";
            };
        }
    }

    public enum InputShape
    {
        LATE_MATCH,
        EVERY_LINE,
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkLiteralProbe.class, args);
        new Runner(options).run();
    }
}
