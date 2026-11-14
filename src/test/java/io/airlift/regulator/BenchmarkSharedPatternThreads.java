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
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;

/// Many threads searching one compiled pattern, whose lazily built DFA caches every thread
/// shares, as "Shared DFA Concurrency" in `REGULATOR_DECISIONS.md` describes. Each case pairs the
/// shared pattern with a control in which every thread or worker compiles its own copy, so the
/// difference is the cost of sharing:
///
/// - warm: finds over short rows and counts over a long row on a warmed pattern; run with
///   `-t 1`, `-t 8`, or `-t 50`
/// - cold: one wave of workers released together on a freshly compiled pattern, so the wave
///   builds the DFA states; the benchmark runs one JMH thread and sets the worker count itself
/// - thrashing: counts over a row whose count resets the DFA too often and bails, and over a
///   healthy row of the same pattern, alone with `-t`, or mixed in one group with `-tg`
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkSharedPatternThreads
{
    // Every warm pattern takes the general DFA route with default options, except the literal
    // alternation.
    // A start-byte range scan per candidate, about 810 scans per long count.
    private static final String CAPITALIZED_WORDS = "[A-Z][a-z]+ [A-Z][a-z]+";
    // A prefix run scan, about 296 per long count and one per short find.
    private static final String CASE_INSENSITIVE_PREFIX = "(?i)sherlock\\s+\\w+";
    // A pointer-table reverse search per find; counts run self-loop scans.
    private static final String WORD_PAIR = "(\\w+)\\s+(\\w+)";
    // A DFA-heavy word boundary search with few per-scan events, the control.
    private static final String WORD_BOUNDARY = "\\b\\w+n\\b";
    // Literal alternation filter stops (specialized route, not the DFA), about 603 per long count.
    private static final String LITERAL_ALTERNATION = "Sherlock|Watson|London";

    private static final int SHORT_ROW_COUNT = 64;
    private static final int SHORT_ROW_SIZE = 48;
    private static final int LONG_ROW_SIZE = 65536;
    private static final long LONG_ROW_SEED = 2;
    // Enough passes to build the states, paired rows, and pointer tables the measured searches use.
    private static final int WARM_UP_PASSES = 4;

    // Case folding keeps the pattern off the disjoint-suffix repeat matcher, which would answer
    // it without the DFA.
    static final String COLD_PATTERN = "(?i)[a-q][^u-z]{80}x";
    private static final int COLD_ROW_SIZE = 16 << 10;
    private static final long COLD_ROW_SEED = 42;

    // At a 16 MiB pattern budget, a count of this pattern over the 1 MiB state-growth row resets
    // the DFA too often and bails, while a count over its first 1 KiB is healthy. An outer
    // capture keeps it on the general route rather than the capture-free suffix scanner or the
    // fixed-width matcher.
    static final String THRASHING_COUNT = "([a-q][^u-z]{23}x)";
    private static final long THRASHING_COUNT_MEMORY = 16L << 20;
    private static final int THRASHING_COUNT_ROW_SIZE = 1 << 20;
    private static final int HEALTHY_COUNT_ROW_SIZE = 1024;
    private static final long THRASHING_ROW_SEED = 42;

    private static final String[] WORDS = {
            "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog", "and", "then", "runs",
            "home", "Sherlock", "Holmes", "Watson", "said", "error", "user", "id", "value", "2024-01-15", "12:30:45",
            "http://example.com/a/b", "alice@example.org", "42", "x", "ing", "station", "london", "baker", "street",
    };

    private static final Slice[] SHORT_ROWS = shortRows();
    private static final Slice LONG_ROW = Slices.wrappedBuffer(wordText(LONG_ROW_SIZE, LONG_ROW_SEED));
    private static final byte[] THRASHING_INPUT = stateGrowthInput(THRASHING_COUNT_ROW_SIZE, THRASHING_ROW_SEED);
    private static final Slice THRASHING_ROW = Slices.wrappedBuffer(THRASHING_INPUT);
    private static final Slice HEALTHY_ROW = Slices.wrappedBuffer(THRASHING_INPUT, 0, HEALTHY_COUNT_ROW_SIZE);

    public enum ColdRows
    {
        IDENTICAL,
        DISTINCT,
    }

    public enum PatternSharing
    {
        SHARED,
        PER_WORKER,
    }

    @State(Scope.Benchmark)
    public static class SharedWarmPattern
    {
        @Param({CAPITALIZED_WORDS, CASE_INSENSITIVE_PREFIX, WORD_PAIR, WORD_BOUNDARY, LITERAL_ALTERNATION})
        String pattern;

        private Re2 compiled;

        @Setup(Level.Trial)
        public void setup()
        {
            compiled = compileWarmPattern(pattern);
        }

        Re2 compiled()
        {
            return compiled;
        }
    }

    @State(Scope.Thread)
    public static class PerThreadWarmPattern
    {
        @Param({CAPITALIZED_WORDS, CASE_INSENSITIVE_PREFIX, WORD_PAIR, WORD_BOUNDARY, LITERAL_ALTERNATION})
        String pattern;

        private Re2 compiled;

        @Setup(Level.Trial)
        public void setup()
        {
            compiled = compileWarmPattern(pattern);
        }

        Re2 compiled()
        {
            return compiled;
        }
    }

    /// One thread's matcher over the shared pattern, and the short row it finds in next.
    @State(Scope.Thread)
    public static class SharedWarmMatcher
    {
        private RegexpMatcher matcher;
        private int nextShortRow;

        @Setup(Level.Trial)
        public void setup(SharedWarmPattern pattern)
        {
            matcher = pattern.compiled().matcher(SHORT_ROWS[0]);
        }

        boolean findNextShortRow()
        {
            Slice row = SHORT_ROWS[nextShortRow];
            nextShortRow = (nextShortRow + 1) % SHORT_ROW_COUNT;
            return matcher.reset(row).find();
        }
    }

    /// One thread's matcher over its own pattern, and the short row it finds in next.
    @State(Scope.Thread)
    public static class PerThreadWarmMatcher
    {
        private RegexpMatcher matcher;
        private int nextShortRow;

        @Setup(Level.Trial)
        public void setup(PerThreadWarmPattern pattern)
        {
            matcher = pattern.compiled().matcher(SHORT_ROWS[0]);
        }

        boolean findNextShortRow()
        {
            Slice row = SHORT_ROWS[nextShortRow];
            nextShortRow = (nextShortRow + 1) % SHORT_ROW_COUNT;
            return matcher.reset(row).find();
        }
    }

    /// Workers that count their rows together on freshly compiled patterns, one shared by every
    /// worker or one compiled for each. The compile happens before each wave, outside the
    /// measurement, so each wave builds its DFA states from an empty cache.
    @State(Scope.Thread)
    public static class ColdWaveState
    {
        @Param({"1", "8", "50"})
        int workerCount;

        @Param({"IDENTICAL", "DISTINCT"})
        ColdRows rows;

        @Param({"SHARED", "PER_WORKER"})
        PatternSharing sharing;

        private Slice[] workerRows;
        private Re2[] workerPatterns;
        private Phaser phaser;
        private Thread[] workers;
        private long[] results;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean isShutdown;

        @Setup(Level.Trial)
        public void setup()
        {
            workerRows = new Slice[workerCount];
            Slice identicalRow = Slices.wrappedBuffer(stateGrowthInput(COLD_ROW_SIZE, COLD_ROW_SEED));
            for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
                workerRows[workerIndex] = switch (rows) {
                    case IDENTICAL -> identicalRow;
                    case DISTINCT -> Slices.wrappedBuffer(stateGrowthInput(COLD_ROW_SIZE, COLD_ROW_SEED + workerIndex));
                };
            }
            workerPatterns = new Re2[workerCount];
            phaser = new Phaser(workerCount + 1);
            workers = new Thread[workerCount];
            results = new long[workerCount];

            for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
                int resultIndex = workerIndex;
                workers[workerIndex] = new Thread(() -> runWorker(resultIndex), "shared-pattern-cold-worker-" + workerIndex);
                workers[workerIndex].start();
            }
        }

        @Setup(Level.Invocation)
        public void compilePatterns()
        {
            Re2 sharedPattern = sharing == PatternSharing.SHARED ? compileColdPattern() : null;
            for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
                workerPatterns[workerIndex] = sharedPattern != null ? sharedPattern : compileColdPattern();
            }
        }

        @TearDown(Level.Trial)
        public void tearDown()
                throws InterruptedException
        {
            isShutdown = true;
            phaser.arriveAndAwaitAdvance();
            phaser.arriveAndDeregister();
            for (Thread worker : workers) {
                worker.join();
            }
        }

        private void runWorker(int resultIndex)
        {
            while (true) {
                phaser.arriveAndAwaitAdvance();
                if (isShutdown) {
                    phaser.arriveAndDeregister();
                    return;
                }

                try {
                    results[resultIndex] = workerPatterns[resultIndex].count(workerRows[resultIndex]);
                }
                catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
                finally {
                    phaser.arriveAndAwaitAdvance();
                }
            }
        }

        long runColdWave()
        {
            phaser.arriveAndAwaitAdvance();
            phaser.arriveAndAwaitAdvance();

            Throwable throwable = failure.get();
            if (throwable != null) {
                throw new AssertionError("cold count worker failed", throwable);
            }

            long checksum = 0;
            for (long result : results) {
                checksum += result;
            }
            return checksum;
        }

        Re2 workerPattern(int workerIndex)
        {
            return workerPatterns[workerIndex];
        }
    }

    /// A pattern every thread shares whose count of the thrashing row resets its DFA too often
    /// and bails. Setup runs one such count, so that measured counts follow a bail.
    @State(Scope.Benchmark)
    public static class SharedThrashingPattern
    {
        private Re2 pattern;

        @Setup(Level.Trial)
        public void setup()
        {
            pattern = compileThrashingPattern();
        }

        Re2 pattern()
        {
            return pattern;
        }
    }

    /// The same pattern as [SharedThrashingPattern], compiled for each thread.
    @State(Scope.Thread)
    public static class PerThreadThrashingPattern
    {
        private Re2 pattern;

        @Setup(Level.Trial)
        public void setup()
        {
            pattern = compileThrashingPattern();
        }

        Re2 pattern()
        {
            return pattern;
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public boolean findShortShared(SharedWarmMatcher matcher)
    {
        return matcher.findNextShortRow();
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public boolean findShortPerThread(PerThreadWarmMatcher matcher)
    {
        return matcher.findNextShortRow();
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public long countLongShared(SharedWarmPattern state)
    {
        return state.compiled.count(LONG_ROW);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public long countLongPerThread(PerThreadWarmPattern state)
    {
        return state.compiled.count(LONG_ROW);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public long countColdWave(ColdWaveState state)
    {
        return state.runColdWave();
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public long countThrashRowShared(SharedThrashingPattern state)
    {
        return state.pattern.count(THRASHING_ROW);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public long countThrashRowPerThread(PerThreadThrashingPattern state)
    {
        return state.pattern.count(THRASHING_ROW);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public long countHealthyRowShared(SharedThrashingPattern state)
    {
        return state.pattern.count(HEALTHY_ROW);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public long countHealthyRowPerThread(PerThreadThrashingPattern state)
    {
        return state.pattern.count(HEALTHY_ROW);
    }

    @Benchmark
    @Group("mixedShared")
    @GroupThreads(1)
    @BenchmarkMode(Mode.AverageTime)
    public long countThrashRowInMixedShared(SharedThrashingPattern state)
    {
        return state.pattern.count(THRASHING_ROW);
    }

    @Benchmark
    @Group("mixedShared")
    @GroupThreads(7)
    @BenchmarkMode(Mode.AverageTime)
    public long countHealthyRowInMixedShared(SharedThrashingPattern state)
    {
        return state.pattern.count(HEALTHY_ROW);
    }

    @Benchmark
    @Group("mixedPerThread")
    @GroupThreads(1)
    @BenchmarkMode(Mode.AverageTime)
    public long countThrashRowInMixedPerThread(PerThreadThrashingPattern state)
    {
        return state.pattern.count(THRASHING_ROW);
    }

    @Benchmark
    @Group("mixedPerThread")
    @GroupThreads(7)
    @BenchmarkMode(Mode.AverageTime)
    public long countHealthyRowInMixedPerThread(PerThreadThrashingPattern state)
    {
        return state.pattern.count(HEALTHY_ROW);
    }

    /// Compiles `expression` and runs every search the warm benchmarks measure a few times.
    private static Re2 compileWarmPattern(String expression)
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice(expression), Re2.Options.defaults());
        RegexpMatcher matcher = pattern.matcher(SHORT_ROWS[0]);
        for (int pass = 0; pass < WARM_UP_PASSES; pass++) {
            for (Slice row : SHORT_ROWS) {
                matcher.reset(row).find();
            }
            pattern.count(LONG_ROW);
            pattern.matcher(LONG_ROW).find();
        }
        return pattern;
    }

    private static Re2 compileColdPattern()
    {
        return Re2.compile(Slices.utf8Slice(COLD_PATTERN), Re2.Options.defaults());
    }

    private static Re2 compileThrashingPattern()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice(THRASHING_COUNT), Re2.Options.defaults().setMaxMemory(THRASHING_COUNT_MEMORY));
        // The one thrashing count whose bail the measured counts follow.
        pattern.count(THRASHING_ROW);
        return pattern;
    }

    private static Slice[] shortRows()
    {
        Slice[] rows = new Slice[SHORT_ROW_COUNT];
        for (int index = 0; index < rows.length; index++) {
            rows[index] = Slices.wrappedBuffer(wordText(SHORT_ROW_SIZE, index + 1));
        }
        return rows;
    }

    static byte[] wordText(int size, long seed)
    {
        Random random = new Random(seed);
        StringBuilder builder = new StringBuilder();
        while (builder.length() < size) {
            builder.append(WORDS[random.nextInt(WORDS.length)]);
            builder.append(random.nextInt(10) == 0 ? ", " : " ");
        }
        return builder.substring(0, size).getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] stateGrowthInput(int length, long seed)
    {
        Random random = new Random(seed);
        byte[] alphabet = "abcdefghijklmnopqrst \n,.;0123456789".getBytes(StandardCharsets.US_ASCII);
        byte[] input = new byte[length];
        for (int index = 0; index < input.length; index++) {
            input[index] = index % 997 == 996 ? (byte) 'x' : alphabet[random.nextInt(alphabet.length)];
        }
        return input;
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkSharedPatternThreads.class, args);
        new Runner(options).run();
    }
}
