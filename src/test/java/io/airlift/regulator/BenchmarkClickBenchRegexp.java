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

import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static io.airlift.slice.Slices.allocate;
import static io.airlift.slice.Slices.utf8Slice;

/**
 * ClickBench q29's URL pattern under RE2 semantics. See docs/benchmarks/CLICKBENCH_REGEXP.md.
 * These synthetic inputs isolate hostname scanning from path scanning; they are not ClickBench data.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkClickBenchRegexp
{
    static final Slice PATTERN = utf8Slice("^https?://(?:www\\.)?([^/]+)/.*$");
    static final Slice REWRITE = utf8Slice("\\1");

    public enum Workload
    {
        HTTP("http://example.com/path?q=1", "example.com", 7, false),
        HTTPS("https://example.com/path?q=1", "example.com", 8, false),
        WWW("https://www.example.com/path?q=1", "example.com", 12, false),
        OPTIONAL_FALLBACK("https://www./path", "www.", 8, false),
        MISSING_SLASH("https://example.com", null, -1, false),
        EMPTY_HOST("https:///path", null, -1, false),
        BAD_SCHEME("ftp://example.com/path", null, -1, false),
        UNICODE("https://www.例え.テスト/道/😀", "例え.テスト", 12, false),
        NEWLINE_HOST("https://exa\nmple.com/path", "exa\nmple.com", 8, false),
        NEWLINE_PATH("https://example.com/a\nb", "example.com", 8, true),
        FINAL_NEWLINE("https://example.com/path\n", "example.com", 8, true),
        LONG_HOST("https://" + "a".repeat(4096) + ".com/path", "a".repeat(4096) + ".com", 8, false),
        LONG_PATH("https://example.com/" + "a".repeat(4096), "example.com", 8, false);

        private final String source;
        private final String host;
        private final int hostStart;
        private final boolean requiresDotAll;

        Workload(String source, String host, int hostStart, boolean requiresDotAll)
        {
            this.source = source;
            this.host = host;
            this.hostStart = hostStart;
            this.requiresDotAll = requiresDotAll;
        }

        Slice source()
        {
            Slice bytes = utf8Slice(source);
            // Exercise Slice-relative offsets, not just arrays starting at zero.
            Slice backing = allocate(bytes.length() + 14);
            backing.setBytes(7, bytes);
            return backing.slice(7, bytes.length());
        }

        Slice expectedHost(boolean dotAll)
        {
            return host == null || (requiresDotAll && !dotAll) ? null : utf8Slice(host);
        }

        int hostStart()
        {
            return hostStart;
        }
    }

    @State(Scope.Thread)
    public static class CompileData
    {
        // ClickHouse enables dot-newline. False is a separate RE2 semantic control, not Trino.
        @Param({"true", "false"})
        boolean dotAll;

        private Re2.Options options;

        @Setup
        public void setup()
        {
            options = Re2.Options.defaults().setDotMatchesNewline(dotAll);
        }
    }

    @State(Scope.Thread)
    public static class MatchData
    {
        @Param
        Workload workload;

        @Param({"true", "false"})
        boolean dotAll;

        private Re2 pattern;
        private Slice source;
        private Re2Matcher matcher;
        private int[] bounds;

        @Setup
        public void setup()
        {
            pattern = Re2.compile(PATTERN, Re2.Options.defaults().setDotMatchesNewline(dotAll));
            source = workload.source();
            matcher = pattern.matcher(source);
            bounds = new int[2];
        }
    }

    @Benchmark
    public Re2 compile(CompileData data)
    {
        return Re2.compile(PATTERN, data.options);
    }

    @Benchmark
    public boolean contains(MatchData data)
    {
        return data.pattern.find(data.source);
    }

    @Benchmark
    public long matchBoundaries(MatchData data)
    {
        if (!data.pattern.findInto(data.source, data.bounds)) {
            return -1;
        }
        return packBounds(data.bounds[0], data.bounds[1]);
    }

    @Benchmark
    public long captureWithReusedMatcher(MatchData data)
    {
        if (!data.matcher.reset(data.source).find()) {
            return -1;
        }
        return packBounds(data.matcher.start(1), data.matcher.end(1));
    }

    @Benchmark
    public Slice extract(MatchData data)
    {
        return data.pattern.extract(data.source, REWRITE);
    }

    @Benchmark
    public Slice replaceFirst(MatchData data)
    {
        return data.pattern.replaceFirst(data.source, REWRITE);
    }

    @Benchmark
    public Slice replaceAll(MatchData data)
    {
        return data.pattern.replaceAll(data.source, REWRITE).result();
    }

    private static long packBounds(int start, int end)
    {
        return ((long) start << 32) | (end & 0xFFFF_FFFFL);
    }

    public static void main(String[] args)
            throws Exception
    {
        new Runner(buildOptions(BenchmarkClickBenchRegexp.class, args)).run();
    }
}
