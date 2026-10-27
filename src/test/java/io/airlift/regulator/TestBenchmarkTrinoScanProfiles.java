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
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkTrinoScanProfiles
{
    @Test
    public void testSelectionsPreserveInputsAndResults()
            throws Throwable
    {
        BenchmarkTrinoScanPlan.BenchmarkData source = new BenchmarkTrinoScanPlan.BenchmarkData();
        source.workload = "OPTIONAL_MIXED";
        source.dotAll = true;
        source.setup();
        TrinoRegexp[] patterns = Arrays.stream(source.expressions).map(TrinoRegexp::compile).toArray(TrinoRegexp[]::new);
        BenchmarkTrinoScanProfiles benchmark = new BenchmarkTrinoScanProfiles();
        for (boolean generateBytecode : new boolean[] {false, true}) {
            for (int selected = -2; selected < patterns.length; selected++) {
                String selection = selected == -2 ? "INTERLEAVED" : selected == -1 ? "GROUPED" : "PLAN_" + selected;
                BenchmarkTrinoScanProfiles.BenchmarkData contains = data(selection, generateBytecode);
                BenchmarkTrinoScanProfiles.BenchmarkData captures = data(selection, generateBytecode);
                BenchmarkTrinoScanProfiles.BenchmarkData extract = data(selection, generateBytecode);
                assertThat(contains.patterns).as(selection).allSatisfy(pattern -> assertThat(pattern.usesGeneratedBytecode()).isEqualTo(generateBytecode));
                int[] occurrences = new int[source.inputs.length];
                for (int row = 0; row < contains.inputs.length; row++) {
                    int sourceRow = contains.sourceRows[row];
                    occurrences[sourceRow]++;
                    assertThat(contains.inputs[row]).as("%s row %s", selection, row).isEqualTo(source.inputs[sourceRow]);
                    assertThat(contains.inputs[row].byteArrayOffset()).as("%s row %s", selection, row).isEqualTo(source.inputs[sourceRow].byteArrayOffset());
                    TrinoRegexp pattern = patterns[sourceRow % patterns.length];
                    assertThat(benchmark.contains(contains)).as("%s row %s", selection, row).isEqualTo(pattern.contains(source.inputs[sourceRow]));
                    assertThat(benchmark.extract(extract)).as("%s row %s", selection, row).isEqualTo(pattern.extract(source.inputs[sourceRow], 1));
                    assertThat(benchmark.captures(captures)).as("%s row %s", selection, row).isEqualTo(checksum(pattern, source.inputs[sourceRow]));
                }
                for (int row = 0; row < occurrences.length; row++) {
                    int expected = selected < 0 ? 1 : row % patterns.length == selected ? patterns.length : 0;
                    assertThat(occurrences[row]).as("%s row %s", selection, row).isEqualTo(expected);
                }
                // Every method wraps around to the first row.
                TrinoRegexp first = patterns[contains.sourceRows[0] % patterns.length];
                assertThat(benchmark.contains(contains)).as(selection).isEqualTo(first.contains(contains.inputs[0]));
                assertThat(benchmark.extract(extract)).as(selection).isEqualTo(first.extract(extract.inputs[0], 1));
                assertThat(benchmark.captures(captures)).as(selection).isEqualTo(checksum(first, captures.inputs[0]));
            }
        }

        BenchmarkTrinoScanProfiles.BenchmarkData interleaved = data("INTERLEAVED", false);
        BenchmarkTrinoScanProfiles.BenchmarkData grouped = data("GROUPED", false);
        assertThat(grouped.sourceRows).isNotEqualTo(interleaved.sourceRows);
        int rowsPerPlan = source.inputs.length / patterns.length;
        for (int row = 0; row < grouped.sourceRows.length; row++) {
            assertThat(interleaved.sourceRows[row] % patterns.length).as("INTERLEAVED row %s", row).isEqualTo(row % patterns.length);
            assertThat(grouped.sourceRows[row] % patterns.length).as("GROUPED row %s", row).isEqualTo(row / rowsPerPlan);
        }

        // The patterns without the scan plan give the results of the measured patterns.
        BenchmarkTrinoScanProfiles.BenchmarkData containsWithoutScanPlan = data("INTERLEAVED", false);
        BenchmarkTrinoScanProfiles.BenchmarkData capturesWithoutScanPlan = data("INTERLEAVED", false);
        BenchmarkTrinoScanProfiles.BenchmarkData extractWithoutScanPlan = data("INTERLEAVED", false);
        assertThat(containsWithoutScanPlan.patternsWithoutScanPlan).allSatisfy(pattern -> assertThat(pattern.usesScanPlanForDiagnostics()).isFalse());
        for (int row = 0; row < source.inputs.length; row++) {
            TrinoRegexp pattern = patterns[row % patterns.length];
            Slice input = source.inputs[row];
            assertThat(benchmark.containsWithoutScanPlan(containsWithoutScanPlan)).as("row %s", row).isEqualTo(pattern.contains(input));
            assertThat(benchmark.extractWithoutScanPlan(extractWithoutScanPlan)).as("row %s", row).isEqualTo(pattern.extract(input, 1));
            assertThat(benchmark.capturesWithoutScanPlan(capturesWithoutScanPlan)).as("row %s", row).isEqualTo(checksum(pattern, input));
        }

        // Generated neighbors run beside the measured patterns, which stay without generated code.
        BenchmarkTrinoScanProfiles.BenchmarkData neighbors = data("INTERLEAVED", false, 4);
        neighbors.runNeighbors();
        assertThat(neighbors.patterns).allSatisfy(pattern -> assertThat(pattern.usesGeneratedBytecode()).isFalse());
        assertThat(neighbors.sourceRows).isEqualTo(interleaved.sourceRows);
        assertThat(benchmark.contains(neighbors)).isEqualTo(patterns[neighbors.sourceRows[0] % patterns.length].contains(neighbors.inputs[0]));
    }

    private static long checksum(TrinoRegexp pattern, Slice input)
    {
        TrinoRegexpMatcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return -1;
        }
        long checksum = 0;
        for (int group = 0; group <= matcher.groupCount(); group++) {
            checksum = checksum * 31 + matcher.start(group);
            checksum = checksum * 31 + matcher.end(group);
        }
        return checksum;
    }

    private static BenchmarkTrinoScanProfiles.BenchmarkData data(String selection, boolean generateBytecode)
    {
        return data(selection, generateBytecode, 0);
    }

    private static BenchmarkTrinoScanProfiles.BenchmarkData data(String selection, boolean generateBytecode, int generatedNeighbors)
    {
        BenchmarkTrinoScanProfiles.BenchmarkData data = new BenchmarkTrinoScanProfiles.BenchmarkData();
        data.selection = selection;
        data.generateBytecode = generateBytecode;
        data.generatedNeighbors = generatedNeighbors;
        data.setup();
        return data;
    }
}
