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

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static io.airlift.slice.Slices.EMPTY_SLICE;

/**
 * Profiles the dot-all {@code OPTIONAL_MIXED} workload of {@link BenchmarkTrinoScanPlan} with its
 * twelve plans interleaved, grouped by plan, or restricted to one plan.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkTrinoScanProfiles
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({
                "INTERLEAVED",
                "GROUPED",
                "PLAN_0",
                "PLAN_1",
                "PLAN_2",
                "PLAN_3",
                "PLAN_4",
                "PLAN_5",
                "PLAN_6",
                "PLAN_7",
                "PLAN_8",
                "PLAN_9",
                "PLAN_10",
                "PLAN_11",
        })
        public String selection;

        Slice[] inputs;
        TrinoRegexp[] patterns;
        TrinoRegexpMatcher[] matchers;
        int[] sourceRows;
        private int cursor;

        @Setup
        public void setup()
        {
            BenchmarkTrinoScanPlan.BenchmarkData source = new BenchmarkTrinoScanPlan.BenchmarkData();
            source.workload = "OPTIONAL_MIXED";
            source.dotAll = true;
            source.setup();
            int count = source.expressions.length;
            int rowsPerPlan = source.inputs.length / count;
            int selectedPlan = selection.startsWith("PLAN_") ? Integer.parseInt(selection.substring(5)) : -1;
            TrinoRegexp[] compiled = new TrinoRegexp[count];
            TrinoRegexpMatcher[] captures = new TrinoRegexpMatcher[count];
            for (int plan = 0; plan < count; plan++) {
                compiled[plan] = TrinoRegexp.compile(source.expressions[plan]);
                captures[plan] = compiled[plan].matcher(EMPTY_SLICE);
            }
            inputs = new Slice[source.inputs.length];
            patterns = new TrinoRegexp[inputs.length];
            matchers = new TrinoRegexpMatcher[inputs.length];
            sourceRows = new int[inputs.length];
            for (int row = 0; row < inputs.length; row++) {
                int sourceRow;
                if (selectedPlan >= 0) {
                    sourceRow = (row % rowsPerPlan) * count + selectedPlan;
                }
                else if (selection.equals("GROUPED")) {
                    sourceRow = (row % rowsPerPlan) * count + row / rowsPerPlan;
                }
                else {
                    sourceRow = row;
                }
                int plan = sourceRow % count;
                inputs[row] = source.inputs[sourceRow];
                patterns[row] = compiled[plan];
                matchers[row] = captures[plan];
                sourceRows[row] = sourceRow;
            }
        }

        private int next()
        {
            int row = cursor++;
            if (cursor == inputs.length) {
                cursor = 0;
            }
            return row;
        }
    }

    @Benchmark
    public boolean contains(BenchmarkData data)
    {
        int row = data.next();
        return data.patterns[row].contains(data.inputs[row]);
    }

    @Benchmark
    public long captures(BenchmarkData data)
    {
        int row = data.next();
        TrinoRegexpMatcher matcher = data.matchers[row];
        if (!matcher.reset(data.inputs[row]).find()) {
            return -1;
        }
        long result = 0;
        for (int group = 0; group <= matcher.groupCount(); group++) {
            result = result * 31 + matcher.start(group);
            result = result * 31 + matcher.end(group);
        }
        return result;
    }

    @Benchmark
    public Slice extract(BenchmarkData data)
    {
        int row = data.next();
        return data.patterns[row].extract(data.inputs[row], 1);
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkTrinoScanProfiles.class, args);
        new Runner(options).run();
    }
}
