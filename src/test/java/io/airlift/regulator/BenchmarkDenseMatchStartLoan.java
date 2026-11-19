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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;

/// Times one-match finds over unseen lines on a Rust pattern whose earlier dense all-match
/// operation may have switched to a match start set, against the same finds on a pattern that never
/// ran one, using only the public API. The switch builds a many-match reverse DFA that borrows half
/// of the reverse longest-match DFA's free memory for the pattern's life, so later finds may reset
/// that DFA where the fresh pattern does not. Run with `-p dataDir=` naming the directory that
/// holds `03-date.pattern`, `03-date.haystack`, and `lines.txt`.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkDenseMatchStartLoan
{
    // The smallest budgets measured at which a dense operation that switches takes the loan and
    // the later finds reset the reverse longest-match DFA where the fresh pattern's do not.
    private static final long STRESS_BUDGET_ASCII = 2560L * 1024;
    private static final long STRESS_BUDGET_UNICODE = 16L * 1024 * 1024;

    // The dense operation's matches over the 03-date haystack, and the lines with a match.
    private static final long DENSE_MATCHES_ASCII = 42_916;
    private static final long DENSE_MATCHES_UNICODE = 42_918;
    private static final long LINE_MATCHES = 4_847;

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param("")
        String dataDir;

        @Param({"false", "true"})
        boolean unicode;

        @Param({"default", "stress"})
        String budget;

        private RustRegexp afterDenseCount;
        private RustRegexp fresh;
        private Slice[] lines;
        private final int[] groups = new int[2];

        @Setup
        public void setup()
        {
            if (dataDir.isEmpty()) {
                throw new IllegalArgumentException("run with -p dataDir=<directory holding 03-date.pattern, 03-date.haystack, and lines.txt>");
            }
            Path directory = Path.of(dataDir);
            Slice pattern = read(directory.resolve("03-date.pattern"));
            Slice haystack = read(directory.resolve("03-date.haystack"));
            lines = lines(read(directory.resolve("lines.txt")));

            afterDenseCount = compile(pattern);
            fresh = compile(pattern);
            RegexpMatcher matcher = afterDenseCount.matcher(haystack, 0);
            long denseMatches = 0;
            while (matcher.find()) {
                denseMatches++;
            }
            if (denseMatches != (unicode ? DENSE_MATCHES_UNICODE : DENSE_MATCHES_ASCII)) {
                throw new IllegalStateException("dense operation found %s matches".formatted(denseMatches));
            }
            verify();
        }

        private RustRegexp compile(Slice pattern)
        {
            // As the language benchmarks do, byte mode runs the pattern when Unicode is off.
            RustRegexp.Options options = RustRegexp.Options.defaults()
                    .setCaseInsensitive(true)
                    .setUnicode(unicode)
                    .setBytes(!unicode);
            switch (budget) {
                case "default" -> {}
                case "stress" -> options.setMaxMemory(unicode ? STRESS_BUDGET_UNICODE : STRESS_BUDGET_ASCII);
                default -> throw new IllegalArgumentException("unknown budget: " + budget);
            }
            return RustRegexp.compile(pattern, options);
        }

        // Checks that both patterns find the same match in each line, and the expected lines match.
        private void verify()
        {
            int[] freshGroups = new int[2];
            long matches = 0;
            for (int index = 0; index < lines.length; index++) {
                boolean found = afterDenseCount.findInto(lines[index], groups);
                boolean freshFound = fresh.findInto(lines[index], freshGroups);
                if (found != freshFound || (found && (groups[0] != freshGroups[0] || groups[1] != freshGroups[1]))) {
                    throw new IllegalStateException("benchmark outcome mismatch on line " + index);
                }
                if (found) {
                    matches++;
                }
            }
            if (matches != LINE_MATCHES) {
                throw new IllegalStateException("%s lines matched, not %s".formatted(matches, LINE_MATCHES));
            }
        }
    }

    @Benchmark
    public long findAfterDenseCount(BenchmarkData data)
    {
        return findEachLine(data.afterDenseCount, data.lines, data.groups);
    }

    @Benchmark
    public long findFresh(BenchmarkData data)
    {
        return findEachLine(data.fresh, data.lines, data.groups);
    }

    // One find a line, consuming each match's bounds.
    private static long findEachLine(RustRegexp pattern, Slice[] lines, int[] groups)
    {
        long result = 0;
        for (Slice line : lines) {
            if (pattern.findInto(line, groups)) {
                result += groups[0] * 31L + groups[1];
            }
        }
        return result;
    }

    private static Slice read(Path path)
    {
        try {
            return Slices.wrappedBuffer(Files.readAllBytes(path));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // The lines of `text`, each without its line feed, as views of one buffer.
    private static Slice[] lines(Slice text)
    {
        List<Slice> lines = new ArrayList<>();
        int start = 0;
        for (int position = 0; position < text.length(); position++) {
            if (text.getByte(position) == '\n') {
                lines.add(text.slice(start, position - start));
                start = position + 1;
            }
        }
        return lines.toArray(Slice[]::new);
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkDenseMatchStartLoan.class, args);
        new Runner(options).run();
    }
}
