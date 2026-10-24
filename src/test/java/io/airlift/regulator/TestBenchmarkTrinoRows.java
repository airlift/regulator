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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openjdk.jmh.annotations.Param;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.airlift.regulator.Re2.BooleanPartialMatchStrategy.CONTAINS;
import static io.airlift.regulator.TestingTrinoRowsBenchmarkInputs.MISSING_CORPUS_DIRECTORY;
import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestBenchmarkTrinoRows
{
    // The core tier documented in tools/re2-benchmark/trino-rows/README.md.
    private static final List<String> CORE_WORKLOADS = List.of("english", "nginx", "loghub", "gharchive");
    private static final List<String> CORE_LITERALS = List.of("low-none", "mid-none", "high-none", "mid-selective");

    @Test
    public void testJmhParametersMatchManifest()
            throws ReflectiveOperationException
    {
        List<Map<String, String>> literals = TestingTrinoRowsBenchmarkInputs.literalRows();
        assertThat(parameter("workload")).containsExactlyElementsOf(TestingTrinoRowsBenchmarkInputs.workloadRows().stream()
                .map(row -> row.get("workload"))
                .toList());
        assertThat(parameter("literal")).containsExactlyElementsOf(literals.stream()
                .map(row -> row.get("literal_id"))
                .distinct()
                .toList());

        for (String workload : CORE_WORKLOADS) {
            assertThat(TestingTrinoRowsBenchmarkInputs.literalIds(workload)).as(workload).containsAll(CORE_LITERALS);
        }
    }

    @Test
    public void testOrderedCellsUseOrderedLiteralMatcher()
    {
        List<Map<String, String>> ordered = TestingTrinoRowsBenchmarkInputs.cellRows("ordered");
        assertThat(ordered).isNotEmpty();
        for (Map<String, String> row : ordered) {
            String cell = row.get("workload") + "/" + row.get("literal_id");
            assertThat(TrinoRegexp.compile(utf8Slice(row.get("pattern"))).usesOrderedLiteralMatcherForDiagnostics()).as(cell).isTrue();
        }
    }

    @Test
    public void testDotStarCellsUseDotStarLiteralSpanMatcher()
    {
        List<Map<String, String>> dotStar = TestingTrinoRowsBenchmarkInputs.cellRows("dotstar");
        assertThat(dotStar).isNotEmpty();
        for (Map<String, String> row : dotStar) {
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(row.get("pattern")));
            String cell = row.get("workload") + "/" + row.get("literal_id");
            assertThat(regexp.usesDotStarLiteralSpanMatcherForDiagnostics()).as(cell).isTrue();
            assertThat(regexp.pattern().booleanPartialMatchStrategyForDiagnostics()).as(cell).isEqualTo(CONTAINS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"english", "russian", "chinese"})
    public void testEveryMethodCyclesThroughRows(String workload)
            throws Exception
    {
        List<String> literals = TestingTrinoRowsBenchmarkInputs.literalIds(workload);
        assertThat(literals).isNotEmpty();
        assertThat(TestingTrinoRowsBenchmarkInputs.readsCorpusDirectory(workload)).isFalse();
        BenchmarkTrinoRows benchmark = new BenchmarkTrinoRows();
        Slice replacement = utf8Slice("_");
        for (String literal : literals) {
            // Setup verifies the sampled rows and the cell's hit count against the manifest.
            BenchmarkTrinoRows.BenchmarkData data = data(workload, literal);
            data.setup();
            TestingTrinoRowsBenchmarkInputs.Input input = TestingTrinoRowsBenchmarkInputs.create(workload, literal, MISSING_CORPUS_DIRECTORY);
            TrinoRegexp regexp = TrinoRegexp.compile(input.pattern());
            // Each method takes one cycle through the rows, so the next method starts again at the
            // first row.
            String cell = workload + "/" + literal;
            assertEveryRow(cell, data, input.sources(), benchmark::contains, regexp::contains);
            assertEveryRow(cell, data, input.sources(), benchmark::count, regexp::count);
            assertEveryRow(cell, data, input.sources(), benchmark::position, regexp::position);
            assertEveryRow(cell, data, input.sources(), benchmark::extract, regexp::extract);
            assertEveryRow(cell, data, input.sources(), benchmark::split, regexp::split);
            assertEveryRow(cell, data, input.sources(), benchmark::replace, source -> regexp.replace(source, replacement));
        }
    }

    @Test
    public void testEmptyCellFailsSetup()
    {
        // No English word with a rare first byte appears in enough rows to fill this cell.
        assertThatThrownBy(data("english", "low-common")::setup)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workload english has no literal in cell low-common");
    }

    private static void assertEveryRow(
            String cell,
            BenchmarkTrinoRows.BenchmarkData data,
            List<Slice> sources,
            Function<BenchmarkTrinoRows.BenchmarkData, Object> method,
            Function<Slice, Object> expected)
    {
        for (int row = 0; row < sources.size(); row++) {
            assertThat(method.apply(data)).as("%s row %s", cell, row).isEqualTo(expected.apply(sources.get(row)));
        }
    }

    private static BenchmarkTrinoRows.BenchmarkData data(String workload, String literal)
    {
        BenchmarkTrinoRows.BenchmarkData data = new BenchmarkTrinoRows.BenchmarkData();
        data.setWorkload(workload);
        data.setLiteral(literal);
        data.setCorpusDirectory(MISSING_CORPUS_DIRECTORY);
        return data;
    }

    private static List<String> parameter(String name)
            throws ReflectiveOperationException
    {
        return Arrays.asList(BenchmarkTrinoRows.BenchmarkData.class.getDeclaredField(name).getAnnotation(Param.class).value());
    }
}
