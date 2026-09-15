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
    {
        BenchmarkTrinoScanPlan.BenchmarkData source = new BenchmarkTrinoScanPlan.BenchmarkData();
        source.workload = "OPTIONAL_MIXED";
        source.dotAll = true;
        source.setup();
        TrinoRegexp[] patterns = Arrays.stream(source.expressions).map(TrinoRegexp::compile).toArray(TrinoRegexp[]::new);
        BenchmarkTrinoScanProfiles benchmark = new BenchmarkTrinoScanProfiles();
        for (int selected = -2; selected < patterns.length; selected++) {
            String selection = selected == -2 ? "INTERLEAVED" : selected == -1 ? "GROUPED" : "PLAN_" + selected;
            BenchmarkTrinoScanProfiles.BenchmarkData contains = data(selection);
            BenchmarkTrinoScanProfiles.BenchmarkData captures = data(selection);
            BenchmarkTrinoScanProfiles.BenchmarkData extract = data(selection);
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

        BenchmarkTrinoScanProfiles.BenchmarkData interleaved = data("INTERLEAVED");
        BenchmarkTrinoScanProfiles.BenchmarkData grouped = data("GROUPED");
        assertThat(grouped.sourceRows).isNotEqualTo(interleaved.sourceRows);
        int rowsPerPlan = source.inputs.length / patterns.length;
        for (int row = 0; row < grouped.sourceRows.length; row++) {
            assertThat(interleaved.sourceRows[row] % patterns.length).as("INTERLEAVED row %s", row).isEqualTo(row % patterns.length);
            assertThat(grouped.sourceRows[row] % patterns.length).as("GROUPED row %s", row).isEqualTo(row / rowsPerPlan);
        }
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

    private static BenchmarkTrinoScanProfiles.BenchmarkData data(String selection)
    {
        BenchmarkTrinoScanProfiles.BenchmarkData data = new BenchmarkTrinoScanProfiles.BenchmarkData();
        data.selection = selection;
        data.setup();
        return data;
    }
}
