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

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkTrinoScanPlan
{
    @Test
    public void testBenchmarkResults()
            throws ReflectiveOperationException
    {
        BenchmarkTrinoScanPlan benchmark = new BenchmarkTrinoScanPlan();
        for (String workload : BenchmarkTrinoScanPlan.workloads()) {
            for (boolean dotAll : new boolean[] {true, false}) {
                // Each method advances its own cursor, so give each one a separate state.
                BenchmarkTrinoScanPlan.BenchmarkData contains = data(workload, dotAll);
                BenchmarkTrinoScanPlan.BenchmarkData count = data(workload, dotAll);
                BenchmarkTrinoScanPlan.BenchmarkData boundaries = data(workload, dotAll);
                BenchmarkTrinoScanPlan.BenchmarkData captures = data(workload, dotAll);
                BenchmarkTrinoScanPlan.BenchmarkData extract = data(workload, dotAll);
                BenchmarkTrinoScanPlan.BenchmarkData replace = data(workload, dotAll);
                BenchmarkTrinoScanPlan.BenchmarkData compile = data(workload, dotAll);
                TrinoRegexp[] patterns = Arrays.stream(contains.expressions).map(TrinoRegexp::compile).toArray(TrinoRegexp[]::new);
                boolean[] planMatched = new boolean[patterns.length];
                // Continue past the last row so every method also wraps around.
                for (int call = 0; call < contains.inputs.length + patterns.length + 1; call++) {
                    int row = call % contains.inputs.length;
                    Slice input = contains.inputs[row];
                    TrinoRegexp pattern = patterns[row % patterns.length];
                    int group = pattern.capturingGroupCount() == 0 ? 0 : 1;
                    String description = workload + " dotAll=" + dotAll + " row=" + row;
                    if (input.length() > 0) {
                        assertThat(input.byteArrayOffset()).as(description).isPositive();
                    }

                    boolean matched = pattern.contains(input);
                    planMatched[row % patterns.length] |= matched;
                    assertThat(benchmark.contains(contains)).as(description).isEqualTo(matched);
                    assertThat(benchmark.count(count)).as(description).isEqualTo(pattern.count(input));

                    TrinoRegexpMatcher boundaryMatcher = pattern.matcher(input, 0);
                    long expectedBoundaries = boundaryMatcher.find() ? ((long) boundaryMatcher.start() << 32) | boundaryMatcher.end() : -1;
                    assertThat(benchmark.boundaries(boundaries)).as(description).isEqualTo(expectedBoundaries);

                    TrinoRegexpMatcher captureMatcher = pattern.matcher(input);
                    long expectedCaptures = -1;
                    if (captureMatcher.find()) {
                        expectedCaptures = 0;
                        for (int captureGroup = 0; captureGroup <= captureMatcher.groupCount(); captureGroup++) {
                            expectedCaptures = expectedCaptures * 31 + captureMatcher.start(captureGroup);
                            expectedCaptures = expectedCaptures * 31 + captureMatcher.end(captureGroup);
                        }
                    }
                    assertThat(benchmark.captures(captures)).as(description).isEqualTo(expectedCaptures);

                    assertThat(benchmark.extract(extract)).as(description).isEqualTo(pattern.extract(input, group));
                    assertThat(benchmark.replace(replace)).as(description).isEqualTo(pattern.replace(input, utf8Slice("$" + group)));

                    TrinoRegexp compiled = benchmark.compile(compile);
                    assertThat(compiled.capturingGroupCount()).as(description).isEqualTo(pattern.capturingGroupCount());
                    assertThat(compiled.contains(input)).as(description).isEqualTo(matched);
                    assertThat(compiled.extract(input, group)).as(description).isEqualTo(pattern.extract(input, group));
                }
                // A row paired with the wrong plan would time only failure paths.
                assertThat(planMatched).as("%s dotAll=%s", workload, dotAll).containsOnly(true);
            }
        }
    }

    @Test
    public void testGroupZeroResultsAndUrlLengths()
    {
        BenchmarkTrinoScanPlan benchmark = new BenchmarkTrinoScanPlan();
        BenchmarkTrinoScanPlan.BenchmarkData noCapture = data("ANCHORED_PREFIX", false);
        assertThat(benchmark.extract(noCapture)).isEqualTo(utf8Slice("http://host0.example.com/"));
        assertThat(benchmark.replace(noCapture)).isEqualTo(noCapture.inputs[1]);

        for (int length : new int[] {48, 128, 4096}) {
            BenchmarkTrinoScanPlan.BenchmarkData data = data("URL_" + (length == 48 ? "SHORT" : length), false);
            assertThat(data.inputs).as("URL length %s", length).allSatisfy(input -> assertThat(input.length()).isEqualTo(length));
        }
    }

    private static BenchmarkTrinoScanPlan.BenchmarkData data(String workload, boolean dotAll)
    {
        BenchmarkTrinoScanPlan.BenchmarkData data = new BenchmarkTrinoScanPlan.BenchmarkData();
        data.workload = workload;
        data.dotAll = dotAll;
        data.setup();
        return data;
    }
}
