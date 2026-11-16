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
package io.trino.operator.scalar;

import io.airlift.regulator.TestingEverydayTrinoRegexpBenchmarkInputs;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.type.JoniRegexp;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static io.airlift.regulator.BenchmarkEverydayTrinoRegexp.INPUTS;
import static io.airlift.regulator.BenchmarkEverydayTrinoRegexp.inputs;
import static io.airlift.regulator.BenchmarkEverydayTrinoRegexp.nullOperation;
import static io.trino.operator.scalar.JoniRegexpCasts.joniRegexp;

/// The Joni twin of `BenchmarkEverydayTrinoRegexp`, with the same passes: each method runs its
/// operation on the workload's inputs in order and consumes each input's result.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkEverydayTrinoJoni
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({
                "logLevel",
                "isoDate",
                "uuid",
                "ipv4",
                "email",
                "keyValue",
                "quotedField",
                "whitespace",
                "delimiter",
                "unicodeLetters",
                "trinoPhone",
                "trinoLiteral",
                "trinoDotStarLiteral",
                "googlebotLine"})
        String workload;

        private JoniRegexp regexp;
        Slice[] inputs;
        private Slice replacement;
        private Function<List<Slice>, Slice> lambdaReplacement;

        @Setup
        public void setup()
        {
            TestingEverydayTrinoRegexpBenchmarkInputs.Input input =
                    TestingEverydayTrinoRegexpBenchmarkInputs.create(workload);
            regexp = joniRegexp(input.pattern());
            inputs = inputs(input);
            replacement = Slices.utf8Slice("_");
            lambdaReplacement = ignored -> replacement;
        }

        public void setWorkload(String workload)
        {
            this.workload = workload;
        }

        // Each operation on input `index`, through the static function its benchmark method calls on each input, for the
        // verifier.

        public boolean contains(int index)
        {
            return JoniRegexpFunctions.regexpLike(inputs[index], regexp);
        }

        public long count(int index)
        {
            return JoniRegexpFunctions.regexpCount(inputs[index], regexp);
        }

        public long positionThird(int index)
        {
            return JoniRegexpFunctions.regexpPosition(inputs[index], regexp, 1, 3);
        }

        public Slice extract(int index)
        {
            return JoniRegexpFunctions.regexpExtract(inputs[index], regexp);
        }

        public List<Slice> extractAll(int index)
        {
            return JoniSliceOperations.extractAll(inputs[index], regexp);
        }

        public List<Slice> split(int index)
        {
            return JoniSliceOperations.split(inputs[index], regexp);
        }

        public Slice replace(int index)
        {
            return JoniRegexpFunctions.regexpReplace(inputs[index], regexp, replacement);
        }

        public Slice replaceLambda(int index)
        {
            return JoniSliceOperations.replace(inputs[index], regexp, lambdaReplacement);
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void contains(BenchmarkData data, Blackhole blackhole)
    {
        JoniRegexp regexp = data.regexp;
        for (Slice input : data.inputs) {
            blackhole.consume(JoniRegexpFunctions.regexpLike(input, regexp));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void count(BenchmarkData data, Blackhole blackhole)
    {
        JoniRegexp regexp = data.regexp;
        for (Slice input : data.inputs) {
            blackhole.consume(JoniRegexpFunctions.regexpCount(input, regexp));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void positionThird(BenchmarkData data, Blackhole blackhole)
    {
        JoniRegexp regexp = data.regexp;
        for (Slice input : data.inputs) {
            blackhole.consume(JoniRegexpFunctions.regexpPosition(input, regexp, 1, 3));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void extract(BenchmarkData data, Blackhole blackhole)
    {
        JoniRegexp regexp = data.regexp;
        for (Slice input : data.inputs) {
            blackhole.consume(JoniRegexpFunctions.regexpExtract(input, regexp));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void extractAll(BenchmarkData data, Blackhole blackhole)
    {
        JoniRegexp regexp = data.regexp;
        for (Slice input : data.inputs) {
            blackhole.consume(JoniSliceOperations.extractAll(input, regexp));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void split(BenchmarkData data, Blackhole blackhole)
    {
        JoniRegexp regexp = data.regexp;
        for (Slice input : data.inputs) {
            blackhole.consume(JoniSliceOperations.split(input, regexp));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void replace(BenchmarkData data, Blackhole blackhole)
    {
        JoniRegexp regexp = data.regexp;
        Slice replacement = data.replacement;
        for (Slice input : data.inputs) {
            blackhole.consume(JoniRegexpFunctions.regexpReplace(input, regexp, replacement));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void replaceLambda(BenchmarkData data, Blackhole blackhole)
    {
        JoniRegexp regexp = data.regexp;
        Function<List<Slice>, Slice> replacement = data.lambdaReplacement;
        for (Slice input : data.inputs) {
            blackhole.consume(JoniSliceOperations.replace(input, regexp, replacement));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void nullEngine(BenchmarkData data, Blackhole blackhole)
    {
        for (Slice input : data.inputs) {
            blackhole.consume(nullOperation(input));
        }
    }
}
