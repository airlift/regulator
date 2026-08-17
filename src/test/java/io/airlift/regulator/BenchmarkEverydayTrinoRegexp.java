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

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkEverydayTrinoRegexp
{
    private static final Slice LIKE_WILDCARD_CHAIN_PATTERN = Slices.utf8Slice("%alpha_bravo_charlie%");

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
        })
        String workload;

        private TrinoRegexp regexp;
        private List<TestingEverydayTrinoRegexpBenchmarkInputs.Source> sources;
        private int sourceIndex;
        private Slice replacement;
        private Function<List<Slice>, Slice> lambdaReplacement;

        @Setup
        public void setup()
        {
            TestingEverydayTrinoRegexpBenchmarkInputs.Input input =
                    TestingEverydayTrinoRegexpBenchmarkInputs.create(workload);
            regexp = TrinoRegexp.compile(input.pattern());
            sources = input.sources();
            replacement = Slices.utf8Slice("_");
            lambdaReplacement = _ -> replacement;
            sourceIndex = 0;
        }

        public void setWorkload(String workload)
        {
            this.workload = workload;
        }

        public void setSourceIndex(int sourceIndex)
        {
            this.sourceIndex = sourceIndex;
        }

        private Slice nextSource()
        {
            Slice source = sources.get(sourceIndex).value();
            sourceIndex = (sourceIndex + 1) & (sources.size() - 1);
            return source;
        }
    }

    @Benchmark
    public boolean contains(BenchmarkData data)
    {
        return data.regexp.contains(data.nextSource());
    }

    @Benchmark
    public long count(BenchmarkData data)
    {
        return data.regexp.count(data.nextSource());
    }

    @Benchmark
    public long positionThird(BenchmarkData data)
    {
        return data.regexp.position(data.nextSource(), 1, 3);
    }

    @Benchmark
    public Slice extract(BenchmarkData data)
    {
        return data.regexp.extract(data.nextSource());
    }

    @Benchmark
    public List<Slice> extractAll(BenchmarkData data)
    {
        return data.regexp.extractAll(data.nextSource());
    }

    @Benchmark
    public List<Slice> split(BenchmarkData data)
    {
        return data.regexp.split(data.nextSource());
    }

    @Benchmark
    public Slice replace(BenchmarkData data)
    {
        return data.regexp.replace(data.nextSource(), data.replacement);
    }

    @Benchmark
    public Slice replaceLambda(BenchmarkData data)
    {
        return data.regexp.replace(data.nextSource(), data.lambdaReplacement);
    }

    @Benchmark
    public TrinoLikePattern constructLikeWildcardChain(BenchmarkData data)
    {
        return TrinoLikePattern.compile(LIKE_WILDCARD_CHAIN_PATTERN);
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkEverydayTrinoRegexp.class, args);
        new Runner(options).run();
    }
}
