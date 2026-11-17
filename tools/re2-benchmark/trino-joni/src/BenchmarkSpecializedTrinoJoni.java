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

import io.airlift.regulator.TestingSpecializedPatternBenchmarkInputs;
import io.airlift.regulator.TestingSpecializedPatternBenchmarkInputs.Operation;
import io.airlift.regulator.TestingSpecializedPatternBenchmarkInputs.Workload;
import io.airlift.slice.Slice;
import io.trino.type.JoniRegexp;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
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

import static io.airlift.regulator.TestingSpecializedPatternBenchmarks.INPUTS;
import static io.airlift.regulator.TestingSpecializedPatternBenchmarks.inputs;
import static io.airlift.regulator.TestingSpecializedPatternBenchmarks.nullOperation;
import static io.trino.operator.scalar.JoniRegexpCasts.joniRegexp;
import static org.openjdk.jmh.annotations.CompilerControl.Mode.DONT_INLINE;

/// This repository's Joni harness for `BenchmarkSpecializedTrinoRegexp`: each workload's
/// operation on pinned Joni, over the same inputs. Replace and extract call pinned Trino's Joni
/// functions; extract-all calls [JoniSliceOperations], which runs pinned Trino's loop but
/// returns a Slice list in place of a SQL Block. The baseline shards run each workload in separate
/// JVMs, as they do for the Regulator variants. Each method is the same pass over the workload's
/// inputs as the Regulator methods, through a static operation the JIT may not inline into the
/// pass, and `nullEngine` is that pass with no engine.
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

        /// The replacement of input `index`, as `replace` applies it, for the verifier.
        public Slice replace(int index)
        {
            return regexpReplace(regexp, definition.replacement(), inputs[index]);
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

        /// The extract of input `index`, as `extract` applies it, for the verifier.
        public Slice extract(int index)
        {
            return regexpExtract(regexp, definition.group(), inputs[index]);
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

        /// The extract-all of input `index`, as `extractAll` applies it, for the verifier.
        public List<Slice> extractAll(int index)
        {
            return regexpExtractAll(regexp, definition.group(), inputs[index]);
        }
    }

    /// The inputs of any workload, for the null engine.
    @State(Scope.Thread)
    public static class NullData
    {
        @Param({"clickbenchHost", "urlHostPort", "queryParameterNames", "keyValue", "quotedField"})
        String workload;

        Slice[] inputs;

        @Setup
        public void setup()
        {
            inputs = inputs(TestingSpecializedPatternBenchmarkInputs.create(workload));
        }
    }

    abstract static class WorkloadData
    {
        Workload definition;
        JoniRegexp regexp;
        Slice[] inputs;

        void load(String workloadId, Operation operation)
        {
            definition = TestingSpecializedPatternBenchmarkInputs.create(workloadId);
            definition.requireOperation(operation);
            regexp = joniRegexp(definition.pattern());
            inputs = inputs(definition);
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void replace(ReplaceData data, Blackhole blackhole)
    {
        JoniRegexp regexp = data.regexp;
        Slice replacement = data.definition.replacement();
        for (Slice input : data.inputs) {
            blackhole.consume(regexpReplace(regexp, replacement, input));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void extract(ExtractData data, Blackhole blackhole)
    {
        JoniRegexp regexp = data.regexp;
        int group = data.definition.group();
        for (Slice input : data.inputs) {
            blackhole.consume(regexpExtract(regexp, group, input));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void extractAll(ExtractAllData data, Blackhole blackhole)
    {
        JoniRegexp regexp = data.regexp;
        int group = data.definition.group();
        for (Slice input : data.inputs) {
            blackhole.consume(regexpExtractAll(regexp, group, input));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void nullEngine(NullData data, Blackhole blackhole)
    {
        for (Slice input : data.inputs) {
            blackhole.consume(nullOperation(input));
        }
    }

    // Each operation on one input, as its benchmark method applies it to each input of a pass.

    @CompilerControl(DONT_INLINE)
    private static Slice regexpReplace(JoniRegexp regexp, Slice replacement, Slice input)
    {
        return JoniRegexpFunctions.regexpReplace(input, regexp, replacement);
    }

    @CompilerControl(DONT_INLINE)
    private static Slice regexpExtract(JoniRegexp regexp, int group, Slice input)
    {
        return JoniRegexpFunctions.regexpExtract(input, regexp, group);
    }

    @CompilerControl(DONT_INLINE)
    private static List<Slice> regexpExtractAll(JoniRegexp regexp, int group, Slice input)
    {
        return JoniSliceOperations.extractAll(input, regexp, group);
    }
}
