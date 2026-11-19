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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static io.airlift.slice.Slices.utf8Slice;

/// Times Trino's all-match functions, `regexp_extract_all`, `regexp_split`, and `regexp_replace`
/// with a constant replacement, once over each whole haystack of the rebar 03-date pattern's
/// dense-match-start workloads, through the public group-zero calls Trino's functions make.
/// The workloads range from dense dates through natural text to a dense prefix before a sparse
/// tail. Run with `-p dataDir=` naming the directory that holds the workloads' `.klv` files.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(5)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
public class BenchmarkDenseTrinoAllMatch
{
    private static final Slice REPLACEMENT = utf8Slice("<date>");

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"extractAll", "split", "replace"})
        String op;

        @Param({
                "curated--03-date--ascii",
                "diagnostic--03-date--sherlock-en",
                "diagnostic--03-date--log",
                "diagnostic--03-date--mixed-zh-sparse",
                "diagnostic--03-date--zh",
                "diagnostic--03-date--mixed-zh",
        })
        String workload;

        @Param("")
        String dataDir;

        private TrinoRegexp pattern;
        private Slice haystack;

        @Setup
        public void setup()
                throws IOException
        {
            if (dataDir.isEmpty()) {
                throw new IllegalArgumentException("run with -p dataDir=<directory holding the workload .klv files>");
            }
            RebarRunner.Benchmark input = RebarRunner.Benchmark.read(Files.readAllBytes(Path.of(dataDir, workload + ".klv")));
            // As the language bulk benchmark compiles its trino rows.
            Slice patternBytes = input.pattern();
            if (input.caseInsensitive()) {
                patternBytes = utf8Slice("(?i)" + patternBytes.toStringUtf8());
            }
            TrinoRegexp.Options options = TrinoRegexp.Options.defaults().setByteFrequencies(input.byteFrequencies());
            long maxMemory = input.maxMemory();
            pattern = TrinoRegexp.compile(patternBytes, maxMemory == 0 ? options : options.setMaxMemory(maxMemory));
            haystack = input.haystack();
            verify();
        }

        // Checks each operation's element count and the timed result against the workload's
        // recorded match count and total match length.
        private void verify()
        {
            Expected expected = Expected.of(workload);
            long matches = pattern.extractAll(haystack).size();
            long parts = pattern.split(haystack).size();
            if (matches != expected.matches() || parts != expected.matches() + 1) {
                throw new IllegalStateException("%s: %s matches and %s split parts, not %s and %s".formatted(workload, matches, parts, expected.matches(), expected.matches() + 1));
            }
            long expectedResult = switch (op) {
                case "extractAll" -> expected.matchBytes();
                case "split" -> haystack.length() - expected.matchBytes();
                case "replace" -> haystack.length() - expected.matchBytes() + expected.matches() * REPLACEMENT.length();
                default -> throw new IllegalArgumentException("unknown op: " + op);
            };
            long result = execute();
            if (result != expectedResult) {
                throw new IllegalStateException("%s %s returned %s, not %s".formatted(workload, op, result, expectedResult));
            }
        }

        // Chooses the operation once per invocation, so no op check runs per match.
        long execute()
        {
            return switch (op) {
                case "extractAll" -> totalLength(pattern.extractAll(haystack));
                case "split" -> totalLength(pattern.split(haystack));
                case "replace" -> pattern.replace(haystack, REPLACEMENT).length();
                default -> throw new IllegalArgumentException("unknown op: " + op);
            };
        }
    }

    /// A workload's Trino matches and their total length in bytes; the length is the rebar
    /// count-spans result the workload manifest records for RE2 and Rust.
    private record Expected(long matches, long matchBytes)
    {
        static Expected of(String workload)
        {
            return switch (workload) {
                case "curated--03-date--ascii" -> new Expected(42_916, 111_817);
                case "diagnostic--03-date--sherlock-en" -> new Expected(130_279, 184_030);
                case "diagnostic--03-date--log" -> new Expected(5_921, 12_326);
                case "diagnostic--03-date--mixed-zh-sparse" -> new Expected(10_075, 27_933);
                case "diagnostic--03-date--zh" -> new Expected(33_323, 44_907);
                case "diagnostic--03-date--mixed-zh" -> new Expected(39_191, 67_182);
                default -> throw new IllegalArgumentException("unknown workload: " + workload);
            };
        }
    }

    @Benchmark
    public long execute(BenchmarkData data)
    {
        return data.execute();
    }

    private static long totalLength(List<Slice> slices)
    {
        long result = 0;
        for (Slice slice : slices) {
            result += slice.length();
        }
        return result;
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkDenseTrinoAllMatch.class, args);
        new Runner(options).run();
    }
}
