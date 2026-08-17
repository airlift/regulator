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
package io.airlift.regulator.benchmark;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.airlift.regulator.TrinoLikePattern;
import io.trino.likematcher.LikeMatcher;
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

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkTrinoLike
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param
        Scenario scenario;

        private TrinoLikePattern candidate;
        private LikeMatcher trinoSql;
        private LikeMatcher trinoOptimized;
        private Slice sliceInput;
        private byte[] byteInput;

        @Setup
        public void setup()
        {
            Input input = scenario.input();
            candidate = TrinoLikePattern.compile(Slices.utf8Slice(input.pattern()));
            trinoSql = LikeMatcher.compile(input.pattern(), Optional.empty(), false);
            trinoOptimized = LikeMatcher.compile(input.pattern(), Optional.empty(), true);
            byteInput = input.input();
            sliceInput = Slices.wrappedBuffer(byteInput);

            boolean candidateResult = candidate.matches(sliceInput);
            boolean sqlResult = trinoSql.match(byteInput);
            boolean optimizedResult = trinoOptimized.match(byteInput);
            if (candidateResult != input.expected() ||
                    sqlResult != input.expected() ||
                    optimizedResult != input.expected()) {
                throw new IllegalStateException("benchmark outcome mismatch for " + scenario);
            }
        }
    }

    public enum Scenario
    {
        EXACT_MATCH,
        PREFIX_LARGE,
        SUFFIX_LARGE,
        CONTAINS_ABSENT,
        CONTAINS_LATE,
        ORDERED_LATE,
        ORDERED_DENSE_FALSE,
        ANY_ASCII,
        ANY_MULTIBYTE,
        MIXED_LATE,
        MIXED_ABSENT,
        WILDCARD_CHAIN;

        private Input input()
        {
            return switch (this) {
                case EXACT_MATCH -> benchmarkInput("needle", bytes("needle"), true);
                case PREFIX_LARGE -> benchmarkInput("needle%", inject(filled(32_768), bytes("needle"), 0), true);
                case SUFFIX_LARGE -> benchmarkInput("%needle", inject(filled(32_768), bytes("needle"), 32_762), true);
                case CONTAINS_ABSENT -> benchmarkInput("%needle%", filled(32_768), false);
                case CONTAINS_LATE -> benchmarkInput("%needle%", inject(filled(32_768), bytes("needle"), 32_700), true);
                case ORDERED_LATE -> benchmarkInput(
                        "%alpha%omega%",
                        inject(inject(filled(32_768), bytes("alpha"), 31_900), bytes("omega"), 32_700),
                        true);
                case ORDERED_DENSE_FALSE -> benchmarkInput("%aab%bba%", denseFalse(), false);
                case ANY_ASCII -> benchmarkInput("_", bytes("x"), true);
                case ANY_MULTIBYTE -> benchmarkInput("_", bytes("💰"), true);
                case MIXED_LATE -> benchmarkInput(
                        "%alpha_omega%",
                        inject(filled(32_768), bytes("alpha💰omega"), 32_700),
                        true);
                case MIXED_ABSENT -> benchmarkInput("%alpha_omega%", filled(32_768), false);
                case WILDCARD_CHAIN -> benchmarkInput(
                        "%alpha_bravo_charlie%",
                        inject(filled(1_024), bytes("alpha-bravo-charlie"), 900),
                        true);
            };
        }
    }

    private record Input(String pattern, byte[] input, boolean expected)
    {
        private Input
        {
            input = input.clone();
        }

        @Override
        public byte[] input()
        {
            return input.clone();
        }
    }

    @Benchmark
    public boolean candidate(BenchmarkData data)
    {
        return data.candidate.matches(data.sliceInput);
    }

    @Benchmark
    public boolean trinoSql(BenchmarkData data)
    {
        return data.trinoSql.match(data.byteInput);
    }

    @Benchmark
    public boolean trinoOptimized(BenchmarkData data)
    {
        return data.trinoOptimized.match(data.byteInput);
    }

    private static Input benchmarkInput(String pattern, byte[] input, boolean expected)
    {
        return new Input(pattern, input, expected);
    }

    private static byte[] bytes(String value)
    {
        return value.getBytes(UTF_8);
    }

    private static byte[] filled(int length)
    {
        byte[] input = new byte[length];
        Arrays.fill(input, (byte) 'x');
        return input;
    }

    private static byte[] inject(byte[] input, byte[] value, int offset)
    {
        System.arraycopy(value, 0, input, offset, value.length);
        return input;
    }

    private static byte[] denseFalse()
    {
        byte[] input = filled(32_768);
        byte[] candidate = bytes("aabx");
        for (int offset = 0; offset + candidate.length <= input.length; offset += candidate.length) {
            System.arraycopy(candidate, 0, input, offset, candidate.length);
        }
        return input;
    }
}
