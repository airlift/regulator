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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkDfaSmallByteSet
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param
        Consumer consumer;

        @Param
        InputShape inputShape;

        @Param({"4096", "32768"})
        int sourceLength;

        private Re2 pattern;
        private Slice source;
        private long expectedCount;

        @Setup
        public void setup()
        {
            pattern = Re2.compile(Slices.utf8Slice(consumer.pattern()));
            expectedCount = 0;
            byte[] sourceBytes = new byte[sourceLength];
            Arrays.fill(sourceBytes, consumer.background());

            switch (inputShape) {
                case ABSENT -> {}
                case LATE_MATCH -> {
                    byte[] match = consumer.match().getBytes(StandardCharsets.UTF_8);
                    System.arraycopy(match, 0, sourceBytes, sourceBytes.length - match.length, match.length);
                    expectedCount = 1;
                }
                case DENSE_FALSE_POSITIVE -> consumer.addFalseCandidates(sourceBytes);
                case REPEATED_MATCH -> expectedCount = consumer.addMatches(sourceBytes);
            }
            source = Slices.wrappedBuffer(sourceBytes);
        }

        long expectedCount()
        {
            return expectedCount;
        }
    }

    public enum Consumer
    {
        START_TWO("[xy][0-9]{4}", "x1234", "xy", 0, 'a'),
        START_THREE("[xyz][0-9]{4}", "x1234", "xyz", 0, 'a'),
        START_RANGE("([a-z]+)-([0-9]+)", "abc-123", "abcdefghijklmnopqrstuvwxyz", 0, '.'),
        START_MIXED("(?:[A-Z_a-z]+)-([0-9]+)", "abc-123", "ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz", 0, '.'),
        START_ALTERNATION("Tom|Sawyer|Huckleberry|Finn", "Tom", "FHST", 0, '.'),
        START_SYMBOL("(\\p{Sm}+)-([0-9]+)", "+-1234", "+", 0, '.'),
        START_UPPERCASE("(\\p{Lu}+)-([0-9]+)", "A-1234", "A", 0, '.'),
        START_WORD("(\\w+)-([0-9]+)", "A-1", "A", 0, '*'),
        START_GREEK("(\\p{Greek}+)-([0-9]+)", "Α-1", "Î", 0, '.'),
        FIXED_TWO("[a-z]{8}[01][0-9]{4}", "abcdefgh01234", "01", 8, 'a'),
        FIXED_THREE("[a-z]{8}[012][0-9]{4}", "abcdefgh01234", "012", 8, 'a');

        private final String pattern;
        private final String match;
        private final String candidates;
        private final int candidateOffset;
        private final byte background;

        Consumer(String pattern, String match, String candidates, int candidateOffset, char background)
        {
            this.pattern = pattern;
            this.match = match;
            this.candidates = candidates;
            this.candidateOffset = candidateOffset;
            this.background = (byte) background;
        }

        String pattern()
        {
            return pattern;
        }

        String match()
        {
            return match;
        }

        byte background()
        {
            return background;
        }

        void addFalseCandidates(byte[] source)
        {
            for (int position = candidateOffset; position < source.length; position += 13) {
                source[position] = (byte) candidates.charAt((position / 13) % candidates.length());
            }
        }

        int addMatches(byte[] source)
        {
            byte[] matchBytes = match.getBytes(StandardCharsets.UTF_8);
            int count = 0;
            for (int position = 0; position + matchBytes.length <= source.length; position += 97) {
                System.arraycopy(matchBytes, 0, source, position, matchBytes.length);
                count++;
            }
            return count;
        }
    }

    public enum InputShape
    {
        ABSENT,
        LATE_MATCH,
        DENSE_FALSE_POSITIVE,
        REPEATED_MATCH,
    }

    @Benchmark
    public boolean find(BenchmarkData data)
    {
        return data.pattern.find(data.source);
    }

    @Benchmark
    public long countMatches(BenchmarkData data)
    {
        return data.pattern.countMatches(data.source);
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkDfaSmallByteSet.class, args);
        new Runner(options).run();
    }
}
