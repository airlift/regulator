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

import io.airlift.regulator.TestingTrinoRegexpBenchmarkInputs;
import io.airlift.regulator.TrinoRegexp;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.type.JoniRegexp;
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

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static io.trino.operator.scalar.JoniRegexpCasts.joniRegexp;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkTrinoJoniComparator
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"literalSparse", "captureSparse", "delimiterDense", "emptyMatches", "unicodeSparse"})
        String workload;

        @Param({"1024", "32768"})
        int sourceLength;

        Slice pattern;
        Slice source;
        private TrinoRegexp regulatorPattern;
        private JoniRegexp joniPattern;
        private Slice replacement;
        private Function<List<Slice>, Slice> regulatorLambdaReplacement;
        private Function<List<Slice>, Slice> joniLambdaReplacement;

        @Setup
        public void setup()
        {
            TestingTrinoRegexpBenchmarkInputs.Input input = TestingTrinoRegexpBenchmarkInputs.create(workload, sourceLength);
            pattern = input.pattern();
            source = input.source();
            regulatorPattern = TrinoRegexp.compile(pattern);
            joniPattern = joniRegexp(pattern);
            replacement = Slices.utf8Slice("_");
            regulatorLambdaReplacement = groups -> groups.isEmpty() ? replacement : groups.getFirst();
            joniLambdaReplacement = groups -> groups.isEmpty() ? replacement : groups.getFirst();
        }
    }

    @Benchmark
    public boolean containsRegulator(BenchmarkData data)
    {
        return data.regulatorPattern.contains(data.source);
    }

    @Benchmark
    public boolean containsJoni(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpLike(data.source, data.joniPattern);
    }

    @Benchmark
    public long countRegulator(BenchmarkData data)
    {
        return data.regulatorPattern.count(data.source);
    }

    @Benchmark
    public long countJoni(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpCount(data.source, data.joniPattern);
    }

    @Benchmark
    public long positionThirdRegulator(BenchmarkData data)
    {
        return data.regulatorPattern.position(data.source, 1, 3);
    }

    @Benchmark
    public long positionThirdJoni(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpPosition(data.source, data.joniPattern, 1, 3);
    }

    @Benchmark
    public Slice extractRegulator(BenchmarkData data)
    {
        return data.regulatorPattern.extract(data.source);
    }

    @Benchmark
    public Slice extractJoni(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpExtract(data.source, data.joniPattern);
    }

    @Benchmark
    public List<Slice> extractAllRegulator(BenchmarkData data)
    {
        return data.regulatorPattern.extractAll(data.source);
    }

    @Benchmark
    public List<Slice> extractAllJoni(BenchmarkData data)
    {
        return JoniSliceOperations.extractAll(data.source, data.joniPattern);
    }

    @Benchmark
    public List<Slice> splitRegulator(BenchmarkData data)
    {
        return data.regulatorPattern.split(data.source);
    }

    @Benchmark
    public List<Slice> splitJoni(BenchmarkData data)
    {
        return JoniSliceOperations.split(data.source, data.joniPattern);
    }

    @Benchmark
    public Slice replaceRegulator(BenchmarkData data)
    {
        return data.regulatorPattern.replace(data.source, data.replacement);
    }

    @Benchmark
    public Slice replaceJoni(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpReplace(data.source, data.joniPattern, data.replacement);
    }

    @Benchmark
    public Slice replaceLambdaRegulator(BenchmarkData data)
    {
        return data.regulatorPattern.replace(data.source, data.regulatorLambdaReplacement);
    }

    @Benchmark
    public Slice replaceLambdaJoni(BenchmarkData data)
    {
        return JoniSliceOperations.replace(data.source, data.joniPattern, data.joniLambdaReplacement);
    }
}
