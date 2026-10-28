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

import io.airlift.regulator.TestingEverydayTrinoRegexpBenchmarkInputs.Source;
import io.airlift.regulator.TestingSpecializedPatternBenchmarkInputs;
import io.airlift.regulator.TestingSpecializedPatternBenchmarkInputs.Operation;
import io.airlift.regulator.TestingSpecializedPatternBenchmarkInputs.Workload;
import io.airlift.slice.Slice;
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

import static io.trino.operator.scalar.JoniRegexpCasts.joniRegexp;

/// This repository's Joni harness for `BenchmarkSpecializedTrinoRegexp`: each workload's
/// operation on pinned Joni, over the same inputs. Replace and extract call pinned Trino's Joni
/// functions; extract-all calls [JoniSliceOperations], which runs pinned Trino's loop but
/// returns a Slice list in place of a SQL Block. The baseline shards run each workload in separate
/// JVMs, as they do for the Regulator variants.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkSpecializedTrinoJoni
{
    @State(Scope.Thread)
    public static class ReplaceData
            extends WorkloadData
    {
        @Param("clickbenchHost")
        String workload;

        @Setup
        public void setup()
        {
            load(workload, Operation.REPLACE);
        }

        public void setWorkload(String workload)
        {
            this.workload = workload;
        }
    }

    @State(Scope.Thread)
    public static class ExtractData
            extends WorkloadData
    {
        @Param({"urlHostPort", "quotedField"})
        String workload;

        @Setup
        public void setup()
        {
            load(workload, Operation.EXTRACT);
        }

        public void setWorkload(String workload)
        {
            this.workload = workload;
        }
    }

    @State(Scope.Thread)
    public static class ExtractAllData
            extends WorkloadData
    {
        @Param({"queryParameterNames", "keyValue"})
        String workload;

        @Setup
        public void setup()
        {
            load(workload, Operation.EXTRACT_ALL);
        }

        public void setWorkload(String workload)
        {
            this.workload = workload;
        }
    }

    abstract static class WorkloadData
    {
        Workload definition;
        JoniRegexp regexp;
        private List<Source> sources;
        private int sourceIndex;

        void load(String workloadId, Operation operation)
        {
            definition = TestingSpecializedPatternBenchmarkInputs.create(workloadId);
            definition.requireOperation(operation);
            regexp = joniRegexp(definition.pattern());
            sources = definition.sources();
            sourceIndex = 0;
        }

        public void setSourceIndex(int sourceIndex)
        {
            this.sourceIndex = sourceIndex;
        }

        Slice nextSource()
        {
            Slice source = sources.get(sourceIndex).value();
            sourceIndex = (sourceIndex + 1) & (sources.size() - 1);
            return source;
        }
    }

    @Benchmark
    public Slice replace(ReplaceData data)
    {
        return JoniRegexpFunctions.regexpReplace(data.nextSource(), data.regexp, data.definition.replacement());
    }

    @Benchmark
    public Slice extract(ExtractData data)
    {
        return JoniRegexpFunctions.regexpExtract(data.nextSource(), data.regexp, data.definition.group());
    }

    @Benchmark
    public List<Slice> extractAll(ExtractAllData data)
    {
        return JoniSliceOperations.extractAll(data.nextSource(), data.regexp, data.definition.group());
    }
}
