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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.TestingTrinoRowsBenchmarkInputs.MISSING_CORPUS_DIRECTORY;

/// Normative Trino rows: each method calls one Trino function once per row, as Trino calls it, over
/// 4,096 real corpus rows visited in one fixed shuffled order. [TestingTrinoRowsBenchmarkInputs]
/// supplies each workload's rows and literals. Grid literals are named `<tercile>-<band>` for the
/// frequency of their first byte and the share of rows containing them; `ordered-<band>` cells
/// search two grid candidates in order with `a(?s:.*)b`, named for the share of rows holding
/// both in that order; `picked-N` literals are hand-picked queries; `dotstar-<id>` cells
/// search the literal of grid or hand-picked cell `<id>` with `.*literal.*`. A cell
/// without a literal fails setup. Setup verifies the sampled rows' checksum and each cell's hit count.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(5)
@Warmup(iterations = 4, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 4, time = 400, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkTrinoRows
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
                "ordered-none",
                "ordered-selective",
                "ordered-common",
                "dotstar-low-none",
                "dotstar-low-selective",
                "dotstar-low-common",
                "dotstar-mid-none",
                "dotstar-mid-selective",
                "dotstar-mid-common",
                "dotstar-high-none",
                "dotstar-high-selective",
                "dotstar-high-common",
                "picked-1",
                "dotstar-picked-1",
                "picked-2",
                "dotstar-picked-2",
        })
        String literal;

        @Param(MISSING_CORPUS_DIRECTORY)
        String corpusDirectory;

        private TrinoRegexp regexp;
        private Slice[] sources;
        private int sourceIndex;
        private Slice replacement;

        @Setup
        public void setup()
                throws IOException
        {
            TestingTrinoRowsBenchmarkInputs.Input input = TestingTrinoRowsBenchmarkInputs.create(workload, literal, corpusDirectory);
            regexp = TrinoRegexp.compile(input.pattern());
            input.verifyHits(regexp::contains, regexp::count);
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
        return data.regexp.contains(data.nextSource());
    }

    @Benchmark
    public long count(BenchmarkData data)
    {
        return data.regexp.count(data.nextSource());
    }

    @Benchmark
    public long position(BenchmarkData data)
    {
        return data.regexp.position(data.nextSource());
    }

    @Benchmark
    public Slice extract(BenchmarkData data)
    {
        return data.regexp.extract(data.nextSource());
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
}
