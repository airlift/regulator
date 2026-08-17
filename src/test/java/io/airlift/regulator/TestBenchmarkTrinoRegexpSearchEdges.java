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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkTrinoRegexpSearchEdges
{
    @Test
    public void testBenchmarkResults()
    {
        BenchmarkTrinoRegexpSearchEdges benchmark = new BenchmarkTrinoRegexpSearchEdges();
        for (String workload : new String[] {
                "singleByteNoMatch",
                "singleByteLateMatch",
                "captureNoMatch",
                "captureLateMatch",
                "finalLineLiteralNoMatch",
                "finalLineLiteralLateMatch",
                "finalLineCharacterClassNoMatch",
                "finalLineCharacterClassLateMatch",
                "finalLineAlternationNoMatch",
                "finalLineAlternationLateMatch",
                "unsupportedBoundaryNoMatch",
                "unsupportedBoundaryLateMatch",
        }) {
            for (int sourceLength : new int[] {1024, 32768}) {
                BenchmarkTrinoRegexpSearchEdges.BenchmarkData data = new BenchmarkTrinoRegexpSearchEdges.BenchmarkData();
                data.workload = workload;
                data.sourceLength = sourceLength;
                data.setup();

                assertThat(benchmark.compile(data)).isNotNull();
                assertThat(benchmark.contains(data)).isEqualTo(data.expectedExtract() != null);
                assertThat(benchmark.containsGeneral(data)).isEqualTo(data.expectedExtract() != null);
                assertThat(benchmark.extract(data)).isEqualTo(data.expectedExtract());
                assertThat(benchmark.replace(data)).isEqualTo(data.expectedReplacement());
            }
        }

        BenchmarkTrinoRegexpSearchEdges.BenchmarkData noMatch = finalLineCharacterClassData("finalLineCharacterClassNoMatch");
        assertThat(benchmark.lookingAt(noMatch)).isFalse();
        assertThat(benchmark.lookingAtGeneral(noMatch)).isFalse();

        BenchmarkTrinoRegexpSearchEdges.BenchmarkData match = finalLineCharacterClassData("finalLineCharacterClassLateMatch");
        assertThat(benchmark.lookingAt(match)).isTrue();
        assertThat(benchmark.lookingAtGeneral(match)).isTrue();
    }

    private static BenchmarkTrinoRegexpSearchEdges.BenchmarkData finalLineCharacterClassData(String workload)
    {
        BenchmarkTrinoRegexpSearchEdges.BenchmarkData data = new BenchmarkTrinoRegexpSearchEdges.BenchmarkData();
        data.workload = workload;
        data.sourceLength = 4;
        data.setup();
        return data;
    }
}
