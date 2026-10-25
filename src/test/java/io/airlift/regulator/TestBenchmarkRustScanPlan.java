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

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkRustScanPlan
{
    @Test
    public void testBenchmarkResults()
            throws ReflectiveOperationException
    {
        assertThat(BenchmarkRustScanPlan.workloads()).isEqualTo(BenchmarkRe2ScanPlan.workloads());
        BenchmarkRustScanPlan benchmark = new BenchmarkRustScanPlan();
        for (String workload : BenchmarkRustScanPlan.workloads()) {
            for (boolean dotAll : new boolean[] {true, false}) {
                String state = "%s dotAll=%s".formatted(workload, dotAll);
                // Each method advances its own cursor, so give each one a separate state.
                BenchmarkRustScanPlan.BenchmarkData contains = data(workload, dotAll);
                BenchmarkRustScanPlan.BenchmarkData count = data(workload, dotAll);
                BenchmarkRustScanPlan.BenchmarkData boundaries = data(workload, dotAll);
                BenchmarkRustScanPlan.BenchmarkData extract = data(workload, dotAll);
                BenchmarkRustScanPlan.BenchmarkData extractAll = data(workload, dotAll);
                BenchmarkRustScanPlan.BenchmarkData compile = data(workload, dotAll);
                // The rows are the RE2 benchmark's, and each Rust pattern matches what its RE2
                // pattern matches. Outside byte mode, results on malformed UTF-8 are unspecified, so
                // on the malformed rows the RE2 result stands for Regulator's ordinary engine, which
                // decodes malformed input by RE2's rules.
                BenchmarkRe2ScanPlan.BenchmarkData re2 = re2Data(workload, dotAll);
                assertThat(contains.inputs).as(state).isEqualTo(re2.inputs);
                RustRegexp[] patterns = Arrays.stream(contains.expressions).map(RustRegexp::compile).toArray(RustRegexp[]::new);
                boolean[] planMatched = new boolean[patterns.length];
                // Continue past the last row so that each method's cursor also wraps around.
                for (int call = 0; call < contains.inputs.length + patterns.length + 1; call++) {
                    int row = call % contains.inputs.length;
                    int patternIndex = row % patterns.length;
                    Slice input = contains.inputs[row];
                    RustRegexp pattern = patterns[patternIndex];
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

                    RustRegexp compiled = benchmark.compile(compile);
                    assertThat(compiled.capturingGroupCount()).as(description).isEqualTo(pattern.capturingGroupCount());
                    assertThat(compiled.find(input)).as(description).isEqualTo(match != null);
                }
                // A row paired with the wrong plan would time only failure paths.
                assertThat(planMatched).as(state).containsOnly(true);
            }
        }
    }

    @Test
    public void testPerlClassesMatchRe2()
    {
        for (String perlClass : List.of("\\d", "\\w")) {
            Re2 re2 = Re2.compile(utf8Slice(perlClass));
            RustRegexp rust = RustRegexp.compile(utf8Slice(BenchmarkRustScanPlan.rustExpression(perlClass)));
            // Rust's Unicode \d and \w would also match U+0663, an Arabic-Indic digit, and its
            // Unicode \w would match é.
            List<String> characters = new ArrayList<>(List.of("\u0663", "é"));
            for (char character = 0; character < 0x80; character++) {
                characters.add(String.valueOf(character));
            }
            for (String character : characters) {
                Slice input = utf8Slice(character);
                assertThat(rust.matches(input)).as("%s on U+%04X", perlClass, character.codePointAt(0)).isEqualTo(re2.matches(input));
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

    private static BenchmarkRustScanPlan.BenchmarkData data(String workload, boolean dotAll)
    {
        BenchmarkRustScanPlan.BenchmarkData data = new BenchmarkRustScanPlan.BenchmarkData();
        data.workload = workload;
        data.dotAll = dotAll;
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
