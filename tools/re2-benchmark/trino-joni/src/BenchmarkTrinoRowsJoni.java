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

import io.airlift.regulator.TestingTrinoRowsBenchmarkInputs;
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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.TestingTrinoRowsBenchmarkInputs.MISSING_CORPUS_DIRECTORY;
import static io.trino.operator.scalar.JoniRegexpCasts.joniRegexp;

/**
 * Joni twin of Regulator's {@code BenchmarkTrinoRows}: the same parameters, methods, rows, row order,
 * and setup checks, with each function answered by Trino's Joni functions, so every cell compares one
 * to one.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(5)
@Warmup(iterations = 4, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 4, time = 400, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkTrinoRowsJoni
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"english", "russian", "chinese", "nginx", "apache", "loghub", "gharchive"})
        String workload;

        @Param({
                "low-none",
                "low-selective",
                "low-common",
                "mid-none",
                "mid-selective",
                "mid-common",
                "high-none",
                "high-selective",
                "high-common",
                "picked-1",
                "picked-2",
        })
        String literal;

        @Param(MISSING_CORPUS_DIRECTORY)
        String corpusDirectory;

        private JoniRegexp regexp;
        private Slice[] sources;
        private int sourceIndex;
        private Slice replacement;

        @Setup
        public void setup()
                throws IOException
        {
            TestingTrinoRowsBenchmarkInputs.Input input = TestingTrinoRowsBenchmarkInputs.create(workload, literal, corpusDirectory);
            regexp = joniRegexp(input.pattern());
            input.verifyHits(
                    source -> JoniRegexpFunctions.regexpLike(source, regexp),
                    source -> JoniRegexpFunctions.regexpCount(source, regexp));
            sources = input.sources().toArray(Slice[]::new);
            replacement = Slices.utf8Slice("_");
            sourceIndex = 0;
        }

        public void setWorkload(String workload)
        {
            this.workload = workload;
        }

        public void setLiteral(String literal)
        {
            this.literal = literal;
        }

        public void setCorpusDirectory(String corpusDirectory)
        {
            this.corpusDirectory = corpusDirectory;
        }

        public void setSourceIndex(int sourceIndex)
        {
            this.sourceIndex = sourceIndex;
        }

        private Slice nextSource()
        {
            Slice source = sources[sourceIndex];
            sourceIndex = (sourceIndex + 1) & (sources.length - 1);
            return source;
        }
    }

    @Benchmark
    public boolean contains(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpLike(data.nextSource(), data.regexp);
    }

    @Benchmark
    public long count(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpCount(data.nextSource(), data.regexp);
    }

    @Benchmark
    public long position(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpPosition(data.nextSource(), data.regexp);
    }

    @Benchmark
    public Slice extract(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpExtract(data.nextSource(), data.regexp);
    }

    @Benchmark
    public List<Slice> split(BenchmarkData data)
    {
        return JoniSliceOperations.split(data.nextSource(), data.regexp);
    }

    @Benchmark
    public Slice replace(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpReplace(data.nextSource(), data.regexp, data.replacement);
    }
}
