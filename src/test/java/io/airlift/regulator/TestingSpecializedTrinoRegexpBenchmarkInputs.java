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

import io.airlift.regulator.TestingEverydayTrinoRegexpBenchmarkInputs.Input;
import io.airlift.regulator.TestingEverydayTrinoRegexpBenchmarkInputs.Source;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.util.List;
import java.util.Map;

/**
 * The specialized-pattern workloads: each applies one Trino operation to a rotation of inputs.
 * The URL workloads share a rotation in {@code specialized-trino-workloads.tsv}; the others
 * reuse their everyday rotation.
 */
public final class TestingSpecializedTrinoRegexpBenchmarkInputs
{
    private static final String RESOURCE = "/io/airlift/regulator/specialized-trino-workloads.tsv";
    private static final Map<String, Input> URL_INPUTS = TestingEverydayTrinoRegexpBenchmarkInputs.loadInputs(RESOURCE);
    private static final List<Workload> WORKLOADS = List.of(
            // ClickBench q29's pattern keeps the host of each referer; the URLs are designed.
            new Workload(urlInput("clickbenchHost"), Operation.REPLACE, 0, Slices.utf8Slice("$1")),
            new Workload(urlInput("urlHostPort"), Operation.EXTRACT, 2, null),
            new Workload(urlInput("queryParameterNames"), Operation.EXTRACT_ALL, 1, null),
            // Extracts the value of every key=value pair.
            new Workload(TestingEverydayTrinoRegexpBenchmarkInputs.create("keyValue"), Operation.EXTRACT_ALL, 2, null),
            new Workload(TestingEverydayTrinoRegexpBenchmarkInputs.create("quotedField"), Operation.EXTRACT, 1, null));

    private TestingSpecializedTrinoRegexpBenchmarkInputs() {}

    public static List<String> workloadIds()
    {
        return WORKLOADS.stream()
                .map(Workload::workloadId)
                .toList();
    }

    public static List<String> workloadIds(Operation operation)
    {
        return WORKLOADS.stream()
                .filter(workload -> workload.operation() == operation)
                .map(Workload::workloadId)
                .toList();
    }

    public static Workload create(String workloadId)
    {
        return WORKLOADS.stream()
                .filter(workload -> workload.workloadId().equals(workloadId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown workload: " + workloadId));
    }

    static List<String> urlWorkloadIds()
    {
        return List.copyOf(URL_INPUTS.keySet());
    }

    private static Input urlInput(String workloadId)
    {
        Input input = URL_INPUTS.get(workloadId);
        if (input == null) {
            throw new IllegalStateException("missing URL workload in %s: %s".formatted(RESOURCE, workloadId));
        }
        return input;
    }

    public enum Operation
    {
        REPLACE("replace"),
        EXTRACT("extract"),
        EXTRACT_ALL("extractAll");

        private final String benchmarkName;

        Operation(String benchmarkName)
        {
            this.benchmarkName = benchmarkName;
        }

        public String benchmarkName()
        {
            return benchmarkName;
        }
    }

    /**
     * One workload: {@code replace} substitutes {@code replacement} for every match;
     * {@code extract} returns capturing group {@code group} of the first match, and
     * {@code extractAll} that group of every match.
     */
    public record Workload(Input input, Operation operation, int group, Slice replacement)
    {
        public String workloadId()
        {
            return input.workloadId();
        }

        public Slice pattern()
        {
            return input.pattern();
        }

        public List<Source> sources()
        {
            return input.sources();
        }

        public void requireOperation(Operation expected)
        {
            if (operation != expected) {
                throw new IllegalStateException("%s is a %s workload, not %s".formatted(workloadId(), operation.benchmarkName(), expected.benchmarkName()));
            }
        }
    }
}
