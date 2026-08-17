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

import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2.Anchor.ANCHOR_BOTH;
import static io.airlift.regulator.Re2.Anchor.ANCHOR_START;
import static io.airlift.regulator.Re2.Anchor.UNANCHORED;
import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkExpressionPlans
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param
        TestingExpressionPlanBenchmarkInputs.Workload workload;

        Re2 literal;
        Re2 contains;
        Re2 ordered;
        Re2 unsupported;
        Re2 prefix;
        Re2 suffix;
        private Slice literalSource;
        private Slice orderedSource;
        TestingExpressionPlanBenchmarkInputs.Expected expected;
        Re2.BooleanPartialMatchStrategy orderedStrategy;

        @Setup
        public void setup()
        {
            TestingExpressionPlanBenchmarkInputs.Input input = TestingExpressionPlanBenchmarkInputs.create(workload);
            literal = input.literal();
            contains = input.contains();
            ordered = input.ordered();
            unsupported = input.unsupported();
            prefix = input.prefix();
            suffix = input.suffix();
            literalSource = input.literalSource();
            orderedSource = input.orderedSource();
            expected = input.expected();
            orderedStrategy = input.orderedStrategy();
        }
    }

    public enum FindControl
    {
        CAPTURE,
        CAPTURE_CONTAINS,
        FOLDING,
        NULLABLE,
        ANCHOR_START,
        ANCHOR_END,
        ANCHOR_BOTH,
    }

    @State(Scope.Thread)
    public static class FindControlData
    {
        @Param
        FindControl control;

        Re2 re2;
        Slice input;
        boolean expected;

        @Setup
        public void setup()
        {
            ControlInput controlInput = switch (control) {
                case CAPTURE -> utf8Control("(foo)", "xxfooyy", true);
                case CAPTURE_CONTAINS -> utf8Control(".*(foo).*", "xxfooyy", true);
                case FOLDING -> utf8Control("(?i:foo)", "xxFOOyy", true);
                case NULLABLE -> utf8Control("a*", "zzz", true);
                case ANCHOR_START -> utf8Control("^foo", "fooyy", true);
                case ANCHOR_END -> utf8Control("foo$", "xxfoo", true);
                case ANCHOR_BOTH -> utf8Control("^foo$", "foo", true);
            };
            re2 = controlInput.re2();
            input = controlInput.input();
            expected = controlInput.expected();
        }
    }

    public enum CompleteControl
    {
        NEWLINE,
        MALFORMED_UTF8_PREFIX,
        MALFORMED_UTF8_SUFFIX,
        LATIN1_PREFIX,
        LATIN1_SUFFIX,
    }

    @State(Scope.Thread)
    public static class CompleteControlData
    {
        @Param
        CompleteControl control;

        Re2 re2;
        Slice input;
        boolean expected;

        @Setup
        public void setup()
        {
            ControlInput controlInput = switch (control) {
                case NEWLINE -> utf8Control("^foo.*$", "foo\nbar", false);
                case MALFORMED_UTF8_PREFIX -> new ControlInput(
                        Re2.compile(Slices.utf8Slice("(?s:.*)foo$")),
                        Slices.wrappedBuffer(new byte[] {(byte) 0xFF, 'f', 'o', 'o'}),
                        false);
                case MALFORMED_UTF8_SUFFIX -> new ControlInput(
                        Re2.compile(Slices.utf8Slice("^foo(?s:.*)$")),
                        Slices.wrappedBuffer(new byte[] {'f', 'o', 'o', (byte) 0xFF}),
                        false);
                case LATIN1_PREFIX -> new ControlInput(
                        Re2.compile(Slices.utf8Slice("(?s:.*)foo$"), Re2.Options.latin1()),
                        Slices.wrappedBuffer(new byte[] {(byte) 0xE9, 'f', 'o', 'o'}),
                        true);
                case LATIN1_SUFFIX -> new ControlInput(
                        Re2.compile(Slices.utf8Slice("^foo(?s:.*)$"), Re2.Options.latin1()),
                        Slices.wrappedBuffer(new byte[] {'f', 'o', 'o', (byte) 0xE9}),
                        true);
            };
            re2 = controlInput.re2();
            input = controlInput.input();
            expected = controlInput.expected();
        }
    }

    private record ControlInput(Re2 re2, Slice input, boolean expected) {}

    private static ControlInput utf8Control(String pattern, String input, boolean expected)
    {
        return new ControlInput(Re2.compile(Slices.utf8Slice(pattern)), Slices.utf8Slice(input), expected);
    }

    @Benchmark
    public boolean findLiteral(BenchmarkData data)
    {
        return data.literal.find(data.literalSource);
    }

    @Benchmark
    public boolean findLiteralGeneral(BenchmarkData data)
    {
        return data.literal.matchInto(data.literalSource, UNANCHORED, null);
    }

    @Benchmark
    public boolean findContains(BenchmarkData data)
    {
        return data.contains.find(data.literalSource);
    }

    @Benchmark
    public boolean findContainsGeneral(BenchmarkData data)
    {
        return data.contains.matchInto(data.literalSource, UNANCHORED, null);
    }

    @Benchmark
    public boolean findOrdered(BenchmarkData data)
    {
        return data.ordered.find(data.orderedSource);
    }

    @Benchmark
    public boolean findOrderedGeneral(BenchmarkData data)
    {
        return data.ordered.matchInto(data.orderedSource, UNANCHORED, null);
    }

    @Benchmark
    public boolean findUnsupported(BenchmarkData data)
    {
        return data.unsupported.find(data.literalSource);
    }

    @Benchmark
    public boolean findUnsupportedGeneral(BenchmarkData data)
    {
        return data.unsupported.matchInto(data.literalSource, UNANCHORED, null);
    }

    @Benchmark
    public boolean findProtectedControl(FindControlData data)
    {
        return data.re2.find(data.input);
    }

    @Benchmark
    public boolean findProtectedControlGeneral(FindControlData data)
    {
        return data.re2.matchInto(data.input, UNANCHORED, null);
    }

    @Benchmark
    public boolean lookingAtLiteral(BenchmarkData data)
    {
        return data.literal.lookingAt(data.literalSource);
    }

    @Benchmark
    public boolean lookingAtLiteralGeneral(BenchmarkData data)
    {
        return data.literal.matchInto(data.literalSource, ANCHOR_START, null);
    }

    @Benchmark
    public boolean lookingAtUnsupported(BenchmarkData data)
    {
        return data.unsupported.lookingAt(data.literalSource);
    }

    @Benchmark
    public boolean lookingAtUnsupportedGeneral(BenchmarkData data)
    {
        return data.unsupported.matchInto(data.literalSource, ANCHOR_START, null);
    }

    @Benchmark
    public boolean matchesLiteral(BenchmarkData data)
    {
        return data.literal.matches(data.literalSource);
    }

    @Benchmark
    public boolean matchesLiteralGeneral(BenchmarkData data)
    {
        return data.literal.matchInto(data.literalSource, ANCHOR_BOTH, null);
    }

    @Benchmark
    public boolean matchesPrefix(BenchmarkData data)
    {
        return data.prefix.matches(data.literalSource);
    }

    @Benchmark
    public boolean matchesPrefixGeneral(BenchmarkData data)
    {
        return data.prefix.matchInto(data.literalSource, ANCHOR_BOTH, null);
    }

    @Benchmark
    public boolean matchesSuffix(BenchmarkData data)
    {
        return data.suffix.matches(data.literalSource);
    }

    @Benchmark
    public boolean matchesSuffixGeneral(BenchmarkData data)
    {
        return data.suffix.matchInto(data.literalSource, ANCHOR_BOTH, null);
    }

    @Benchmark
    public boolean matchesUnsupported(BenchmarkData data)
    {
        return data.unsupported.matches(data.literalSource);
    }

    @Benchmark
    public boolean matchesUnsupportedGeneral(BenchmarkData data)
    {
        return data.unsupported.matchInto(data.literalSource, ANCHOR_BOTH, null);
    }

    @Benchmark
    public boolean matchesProtectedControl(CompleteControlData data)
    {
        return data.re2.matches(data.input);
    }

    @Benchmark
    public boolean matchesProtectedControlGeneral(CompleteControlData data)
    {
        return data.re2.matchInto(data.input, ANCHOR_BOTH, null);
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkExpressionPlans.class, args);
        new Runner(options).run();
    }
}
