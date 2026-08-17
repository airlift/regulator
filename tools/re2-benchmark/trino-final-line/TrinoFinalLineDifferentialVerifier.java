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

import java.util.Objects;

public final class TrinoFinalLineDifferentialVerifier
{
    private TrinoFinalLineDifferentialVerifier() {}

    public static void main(String[] args)
    {
        BenchmarkTrinoFinalLine benchmark = new BenchmarkTrinoFinalLine();
        int comparisonCount = 0;
        for (String workload : BenchmarkTrinoFinalLine.WORKLOADS) {
            for (int sourceLength : BenchmarkTrinoFinalLine.SOURCE_LENGTHS) {
                BenchmarkTrinoFinalLine.BenchmarkData data = new BenchmarkTrinoFinalLine.BenchmarkData();
                data.workload = workload;
                data.sourceLength = sourceLength;
                data.setup();
                var input = BenchmarkTrinoFinalLine.createInput(workload, sourceLength);
                JoniSliceOperationsVerifier.verify(input.source(), JoniRegexpCasts.joniRegexp(input.pattern()));

                assertEqual(benchmark.containsRegulator(data), benchmark.containsJoni(data), workload, sourceLength, "contains");
                assertEqual(benchmark.countRegulator(data), benchmark.countJoni(data), workload, sourceLength, "count");
                assertEqual(benchmark.positionRegulator(data), benchmark.positionJoni(data), workload, sourceLength, "position");
                assertEqual(benchmark.extractRegulator(data), benchmark.extractJoni(data), workload, sourceLength, "extract");
                assertEqual(benchmark.extractAllRegulator(data), benchmark.extractAllJoni(data), workload, sourceLength, "extractAll");
                assertEqual(benchmark.splitRegulator(data), benchmark.splitJoni(data), workload, sourceLength, "split");
                assertEqual(benchmark.replaceRegulator(data), benchmark.replaceJoni(data), workload, sourceLength, "replace");
                assertEqual(benchmark.replaceLambdaRegulator(data), benchmark.replaceLambdaJoni(data), workload, sourceLength, "replaceLambda");
                comparisonCount += 8;
            }
        }
        System.out.println("Verified " + comparisonCount + " Trino final-line operation comparisons");
    }

    private static void assertEqual(Object actual, Object expected, String workload, int sourceLength, String operation)
    {
        if (!Objects.equals(actual, expected)) {
            throw failure(workload, sourceLength, operation, actual, expected);
        }
    }

    private static AssertionError failure(
            String workload,
            int sourceLength,
            String operation,
            Object actual,
            Object expected)
    {
        return new AssertionError("%s/%s/%s: Slice=%s, Joni=%s"
                .formatted(workload, sourceLength, operation, actual, expected));
    }
}
