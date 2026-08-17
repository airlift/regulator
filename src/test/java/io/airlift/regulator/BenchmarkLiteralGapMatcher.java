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

import io.airlift.regulator.TrinoLikeParser.Element;
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

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkLiteralGapMatcher
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param
        Scenario scenario;

        private Slice input;
        private LiteralGapMatcher sharedMatcher;
        private TrinoLikeWildcardMatcher wildcardControl;
        private TrinoLikePattern likePattern;
        private TrinoRegexp trinoRegexp;
        private Re2 regexpPattern;

        @Setup
        public void setup()
        {
            BenchmarkInput benchmarkInput = scenario.input();
            input = Slices.wrappedBuffer(benchmarkInput.input());

            Slice likePatternSlice = Slices.utf8Slice(benchmarkInput.likePattern());
            List<Element> elements = TrinoLikeParser.parse(likePatternSlice, OptionalInt.empty());
            int matcherEnd = elements.size() - 2;
            sharedMatcher = LiteralGapMatcher.analyzeUnrestrictedForDiagnostics(elements, 0, matcherEnd, false);
            wildcardControl = new TrinoLikeWildcardMatcher(elements, 0, matcherEnd, false);
            likePattern = TrinoLikePattern.compile(likePatternSlice);
            trinoRegexp = TrinoRegexp.compile(Slices.utf8Slice(benchmarkInput.regexpPattern()));
            regexpPattern = trinoRegexp.pattern();

            boolean likeUsesSharedMatcher = scenario != Scenario.DENSE_SHORT_FALSE_32K && scenario != Scenario.DENSE_SHORT_LATE_32K;
            if (sharedMatcher == null ||
                    likePattern.usesSharedLiteralGapSearchForDiagnostics() != likeUsesSharedMatcher ||
                    trinoRegexp.usesLiteralGapMatcherForDiagnostics()) {
                throw new IllegalStateException("unexpected literal-gap route for " + scenario);
            }

            boolean expected = benchmarkInput.expected();
            if (sharedMatcher.matches(input, 0, input.length()) != expected ||
                    wildcardControl.matches(input, 0, input.length()) != expected ||
                    likePattern.matches(input) != expected ||
                    trinoRegexp.contains(input) != expected ||
                    regexpPattern.find(input) != expected ||
                    regexpPattern.matchInto(input, Re2.Anchor.UNANCHORED, null) != expected) {
                throw new IllegalStateException("benchmark outcome mismatch for " + scenario);
            }
        }
    }

    public enum Scenario
    {
        SPARSE_ABSENT_1K,
        SPARSE_ABSENT_32K,
        SPARSE_LATE_ASCII_32K,
        SPARSE_LATE_MULTIBYTE_32K,
        RETRY_LATE_32K,
        DENSE_LONG_FALSE_32K,
        DENSE_LONG_LATE_32K,
        DENSE_SHORT_FALSE_32K,
        DENSE_SHORT_LATE_32K,
        CHAIN_LATE_32K;

        BenchmarkInput input()
        {
            return switch (this) {
                case SPARSE_ABSENT_1K -> literalGapInput(1_024, filled(1_024), false);
                case SPARSE_ABSENT_32K -> literalGapInput(32_768, filled(32_768), false);
                case SPARSE_LATE_ASCII_32K -> literalGapInput(
                        32_768,
                        inject(filled(32_768), utf8("alpha-omega"), 32_700),
                        true);
                case SPARSE_LATE_MULTIBYTE_32K -> literalGapInput(
                        32_768,
                        inject(filled(32_768), utf8("alpha💰omega"), 32_700),
                        true);
                case RETRY_LATE_32K -> literalGapInput(
                        32_768,
                        inject(inject(filled(32_768), utf8("alpha--"), 64), utf8("alpha\nomega"), 32_700),
                        true);
                case DENSE_LONG_FALSE_32K -> literalGapInput(32_768, repeated(32_768, utf8("alpha--")), false);
                case DENSE_LONG_LATE_32K -> literalGapInput(
                        32_768,
                        inject(repeated(32_768, utf8("alpha--")), utf8("alpha-omega"), 32_757),
                        true);
                case DENSE_SHORT_FALSE_32K -> benchmarkInput(
                        "%a_b%",
                        "(a)(?s:.)(b)",
                        filled(32_768, (byte) 'a'),
                        false);
                case DENSE_SHORT_LATE_32K -> benchmarkInput(
                        "%a_b%",
                        "(a)(?s:.)(b)",
                        inject(filled(32_768, (byte) 'a'), utf8("a-b"), 32_765),
                        true);
                case CHAIN_LATE_32K -> benchmarkInput(
                        "%alpha_bravo_charlie%",
                        "(alpha)(?s:.)(bravo)(?s:.)(charlie)",
                        inject(filled(32_768), utf8("alpha-bravo-charlie"), 32_700),
                        true);
            };
        }
    }

    @Benchmark
    public boolean sharedMatcher(BenchmarkData data)
    {
        return data.sharedMatcher.matches(data.input, 0, data.input.length());
    }

    @Benchmark
    public boolean likePublic(BenchmarkData data)
    {
        return data.likePattern.matches(data.input);
    }

    @Benchmark
    public boolean likeWildcardControl(BenchmarkData data)
    {
        return data.wildcardControl.matches(data.input, 0, data.input.length());
    }

    @Benchmark
    public boolean regexpPublic(BenchmarkData data)
    {
        return data.trinoRegexp.contains(data.input);
    }

    @Benchmark
    public boolean regexpPreviousRouteControl(BenchmarkData data)
    {
        return data.regexpPattern.find(data.input);
    }

    @Benchmark
    public boolean regexpGeneralDfaControl(BenchmarkData data)
    {
        return data.regexpPattern.matchInto(data.input, Re2.Anchor.UNANCHORED, null);
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkLiteralGapMatcher.class, args);
        new Runner(options).run();
    }

    private static BenchmarkInput literalGapInput(int expectedLength, byte[] input, boolean expected)
    {
        if (input.length != expectedLength) {
            throw new IllegalArgumentException("input length does not match scenario: " + input.length);
        }
        return benchmarkInput("%alpha_omega%", "(alpha)(?s:.)(omega)", input, expected);
    }

    private static BenchmarkInput benchmarkInput(String likePattern, String regexpPattern, byte[] input, boolean expected)
    {
        return new BenchmarkInput(likePattern, regexpPattern, input, expected);
    }

    private static byte[] filled(int length)
    {
        return filled(length, (byte) 'x');
    }

    private static byte[] filled(int length, byte value)
    {
        byte[] input = new byte[length];
        Arrays.fill(input, value);
        return input;
    }

    private static byte[] repeated(int length, byte[] value)
    {
        byte[] input = new byte[length];
        for (int offset = 0; offset < length; offset += value.length) {
            System.arraycopy(value, 0, input, offset, Math.min(value.length, length - offset));
        }
        return input;
    }

    private static byte[] inject(byte[] input, byte[] value, int offset)
    {
        System.arraycopy(value, 0, input, offset, value.length);
        return input;
    }

    private static byte[] utf8(String value)
    {
        return Slices.utf8Slice(value).getBytes();
    }

    private record BenchmarkInput(String likePattern, String regexpPattern, byte[] input, boolean expected)
    {
        private BenchmarkInput
        {
            input = input.clone();
        }

        @Override
        public byte[] input()
        {
            return input.clone();
        }
    }
}
