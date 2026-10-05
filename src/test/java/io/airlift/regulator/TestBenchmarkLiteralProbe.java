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

import io.airlift.regulator.BenchmarkLiteralProbe.InputShape;
import io.airlift.regulator.BenchmarkLiteralProbe.Workload;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkLiteralProbe
{
    @Test
    public void testResultsAndRoutes()
    {
        for (Workload workload : Workload.values()) {
            for (InputShape inputShape : InputShape.values()) {
                BenchmarkLiteralProbe benchmark = new BenchmarkLiteralProbe();
                benchmark.workload = workload;
                benchmark.inputShape = inputShape;
                benchmark.sourceLength = 65536;
                benchmark.setup();

                String description = workload + " " + inputShape;
                if (inputShape == InputShape.LATE_MATCH) {
                    assertThat(benchmark.expectedCount()).as(description).isEqualTo(1);
                }
                else {
                    assertThat(benchmark.expectedCount()).as(description).isGreaterThan(100);
                }
                assertThat(benchmark.re2Find()).as(description).isTrue();
                assertThat(benchmark.re2Count()).as(description).isEqualTo(benchmark.expectedCount());
                assertThat(benchmark.trinoContains()).as(description).isTrue();
                assertThat(benchmark.trinoCount()).as(description).isEqualTo(benchmark.expectedCount());
                assertThat(benchmark.dfaSearch()).as(description).isPositive();
                assertThat(benchmark.program().prefixAccelProbeOffset()).as(description).isEqualTo(workload.probeOffset());
            }
        }
    }

    @Test
    public void testTrinoLeadingLiteralProbe()
    {
        for (Workload workload : Workload.values()) {
            ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(workload.expression()), Regexp.LIKE_PERL);
            TrinoScanPlan plan = TrinoScanPlan.analyze(parsed.regexp(), parsed.capturingGroupCount(), false);
            assertThat(plan).as(workload.toString()).isNotNull();
            assertThat(plan.leadingLiteralProbeOffsetForDiagnostics()).as(workload.toString()).isEqualTo(workload.probeOffset());
        }
    }

    @Test
    public void testLiteralOccursOnlyAtTargets()
    {
        for (Workload workload : Workload.values()) {
            for (InputShape inputShape : InputShape.values()) {
                Slice input = BenchmarkLiteralProbe.logLines(workload, inputShape, 65536);
                String text = input.toStringUtf8();
                assertThat(input.length()).isGreaterThanOrEqualTo(65536);
                assertThat(text).endsWith("\n");
                long lines = text.chars().filter(character -> character == '\n').count();
                long targets = inputShape == InputShape.LATE_MATCH ? 1 : lines;
                for (Workload other : Workload.values()) {
                    // URL and HTTPS targets each contain the other's literal.
                    long expected = other == workload || workload.occurrence(0).contains(other.literal()) ? targets : 0;
                    assertThat(occurrences(text, other.literal()))
                            .as("%s %s %s", workload, inputShape, other)
                            .isEqualTo(expected);
                }
                if (workload == Workload.HTTPS) {
                    String firstLine = text.substring(0, text.indexOf('\n') + 1);
                    assertThat(firstLine.chars().filter(character -> character == '/').count()).isGreaterThanOrEqualTo(10);
                }
                if (inputShape == InputShape.LATE_MATCH) {
                    String lastLine = text.substring(text.lastIndexOf('\n', text.length() - 2) + 1);
                    assertThat(lastLine).contains(workload.literal());
                }
            }
        }
    }

    private static long occurrences(String text, String literal)
    {
        long count = 0;
        for (int index = text.indexOf(literal); index >= 0; index = text.indexOf(literal, index + 1)) {
            count++;
        }
        return count;
    }
}
