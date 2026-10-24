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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkJavaScanPlan
{
    @Test
    public void testBenchmarkResults()
            throws ReflectiveOperationException
    {
        assertThat(BenchmarkJavaScanPlan.workloads()).isEqualTo(BenchmarkRe2ScanPlan.workloads());
        BenchmarkJavaScanPlan benchmark = new BenchmarkJavaScanPlan();
        for (String workload : BenchmarkJavaScanPlan.workloads()) {
            for (boolean dotAll : new boolean[] {true, false}) {
                for (boolean scanPlan : new boolean[] {true, false}) {
                    String state = "%s dotAll=%s scanPlan=%s".formatted(workload, dotAll, scanPlan);
                    // Each method advances its own cursor, so give each one a separate state.
                    BenchmarkJavaScanPlan.BenchmarkData contains = data(workload, dotAll, scanPlan);
                    BenchmarkJavaScanPlan.BenchmarkData count = data(workload, dotAll, scanPlan);
                    BenchmarkJavaScanPlan.BenchmarkData boundaries = data(workload, dotAll, scanPlan);
                    BenchmarkJavaScanPlan.BenchmarkData extract = data(workload, dotAll, scanPlan);
                    BenchmarkJavaScanPlan.BenchmarkData extractAll = data(workload, dotAll, scanPlan);
                    BenchmarkJavaScanPlan.BenchmarkData compile = data(workload, dotAll, scanPlan);
                    // The rows are the RE2 benchmark's, and each Java pattern matches what its RE2
                    // pattern matches. With scanPlan=true, each Java pattern selects a plan exactly
                    // when its RE2 pattern does; with scanPlan=false, none does.
                    BenchmarkRe2ScanPlan.BenchmarkData re2 = re2Data(workload, dotAll);
                    assertThat(contains.inputs).as(state).isEqualTo(re2.inputs);
                    for (int index = 0; index < contains.patterns.length; index++) {
                        assertThat(contains.patterns[index].pattern().usesTrinoScanPlanForDiagnostics())
                                .as("%s pattern %s", state, index)
                                .isEqualTo(scanPlan && re2.patterns[index].usesTrinoScanPlanForDiagnostics());
                    }
                    JavaRegexp[] patterns = Arrays.stream(contains.expressions).map(JavaRegexp::compile).toArray(JavaRegexp[]::new);
                    boolean[] planMatched = new boolean[patterns.length];
                    // Continue past the last row so that each method's cursor also wraps around.
                    for (int call = 0; call < contains.inputs.length + patterns.length + 1; call++) {
                        int row = call % contains.inputs.length;
                        int patternIndex = row % patterns.length;
                        Slice input = contains.inputs[row];
                        JavaRegexp pattern = patterns[patternIndex];
                        int group = pattern.capturingGroupCount() == 0 ? 0 : 1;
                        String description = state + " row=" + row;

                        MatchResult match = pattern.findResult(input);
                        assertThat(groupBounds(match)).as(description).isEqualTo(groupBounds(re2.patterns[patternIndex].findResult(input)));
                        planMatched[patternIndex] |= match != null;
                        assertThat(benchmark.contains(contains)).as(description).isEqualTo(match != null);
                        assertThat(benchmark.count(count)).as(description).isEqualTo(pattern.count(input));
                        long expectedBoundaries = match == null ? -1 : ((long) match.start() << 32) | match.end();
                        assertThat(benchmark.boundaries(boundaries)).as(description).isEqualTo(expectedBoundaries);
                        assertThat(benchmark.extract(extract)).as(description).isEqualTo(match == null ? null : match.groupSlice(group));

                        Re2Matcher matcher = pattern.matcher(input);
                        List<Slice> expectedMatches = new ArrayList<>();
                        while (matcher.find()) {
                            expectedMatches.add(matcher.group(group));
                        }
                        assertThat(benchmark.extractAll(extractAll)).as(description).isEqualTo(expectedMatches);

                        JavaRegexp compiled = benchmark.compile(compile);
                        assertThat(compiled.capturingGroupCount()).as(description).isEqualTo(pattern.capturingGroupCount());
                        assertThat(compiled.find(input)).as(description).isEqualTo(match != null);
                        assertThat(compiled.pattern().usesTrinoScanPlanForDiagnostics()).as(description)
                                .isEqualTo(contains.patterns[patternIndex].pattern().usesTrinoScanPlanForDiagnostics());
                    }
                    // A row paired with the wrong plan would time only failure paths.
                    assertThat(planMatched).as(state).containsOnly(true);
                }
            }
        }
    }

    private static List<Integer> groupBounds(MatchResult match)
    {
        if (match == null) {
            return null;
        }
        List<Integer> bounds = new ArrayList<>();
        for (int group = 0; group <= match.groupCount(); group++) {
            bounds.add(match.start(group));
            bounds.add(match.end(group));
        }
        return bounds;
    }

    private static BenchmarkJavaScanPlan.BenchmarkData data(String workload, boolean dotAll, boolean scanPlan)
    {
        BenchmarkJavaScanPlan.BenchmarkData data = new BenchmarkJavaScanPlan.BenchmarkData();
        data.workload = workload;
        data.dotAll = dotAll;
        data.scanPlan = scanPlan;
        data.setup();
        return data;
    }

    private static BenchmarkRe2ScanPlan.BenchmarkData re2Data(String workload, boolean dotAll)
    {
        BenchmarkRe2ScanPlan.BenchmarkData data = new BenchmarkRe2ScanPlan.BenchmarkData();
        data.workload = workload;
        data.dotAll = dotAll;
        data.scanPlan = true;
        data.setup();
        return data;
    }
}
