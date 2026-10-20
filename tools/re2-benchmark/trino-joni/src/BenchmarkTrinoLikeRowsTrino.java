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
import io.trino.type.LikeFunctions;
import io.trino.type.LikePattern;
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
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.TestingTrinoRowsBenchmarkInputs.MISSING_CORPUS_DIRECTORY;

/**
 * Trino twin of Regulator's {@code BenchmarkTrinoLikeRows}: the same parameters, rows, row order,
 * LIKE pattern, and setup checks, with each row answered by Trino's LIKE functions, so every cell
 * compares one to one. Setup compiles the pattern with Trino's {@code $like_pattern} function,
 * without an escape character, and fails for a literal containing a LIKE metacharacter, as
 * Regulator's setup does. {@code matches} calls Trino's {@code $like} function once per row.
 * Unlike Regulator's benchmark, which binds its pattern as a constant, it reads the pattern from
 * the benchmark state, as {@code BenchmarkTrinoLikeTpch} does; C2 folds none of the pattern's
 * fields from a constant receiver, so the binding does not change Trino's compiled code.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(5)
@Warmup(iterations = 4, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 4, time = 400, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkTrinoLikeRowsTrino
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

        private LikePattern pattern;
        private Slice[] sources;
        private int sourceIndex;

        @Setup
        public void setup()
                throws IOException
        {
            TestingTrinoRowsBenchmarkInputs.Input input = TestingTrinoRowsBenchmarkInputs.create(workload, literal, corpusDirectory);
            pattern = LikeFunctions.likePattern(input.likePattern());
            input.verifyHitRows(source -> LikeFunctions.likeVarchar(source, pattern));
            sources = input.sources().toArray(Slice[]::new);
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
    public boolean matches(BenchmarkData data)
    {
        return LikeFunctions.likeVarchar(data.nextSource(), data.pattern);
    }
}
