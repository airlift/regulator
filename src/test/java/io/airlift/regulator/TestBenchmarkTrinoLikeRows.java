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
import java.util.function.BooleanSupplier;

import static io.airlift.regulator.TestingTrinoRowsBenchmarkInputs.MISSING_CORPUS_DIRECTORY;
import static io.airlift.regulator.TrinoLikePattern.Plan.ORDERED_LITERALS;
import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkTrinoLikeRows
{
    @Test
    public void testJmhParametersMatchTrinoRows()
            throws ReflectiveOperationException
    {
        // TestBenchmarkTrinoRows checks workload and literal against the manifests. The LIKE
        // benchmark has no dot-star cells, whose %literal% would repeat their grid or picked cell.
        for (String name : List.of("workload", "corpusDirectory")) {
            assertThat(parameter(BenchmarkTrinoLikeRows.BenchmarkData.class, name))
                    .as(name)
                    .isEqualTo(parameter(BenchmarkTrinoRows.BenchmarkData.class, name));
        }
        assertThat(parameter(BenchmarkTrinoLikeRows.BenchmarkData.class, "literal"))
                .isEqualTo(parameter(BenchmarkTrinoRows.BenchmarkData.class, "literal").stream()
                        .filter(literal -> !literal.startsWith("dotstar-"))
                        .toList());
    }

    @Test
    public void testOrderedCellsUseOrderedLiteralsPlan()
    {
        List<Map<String, String>> ordered = TestingTrinoRowsBenchmarkInputs.cellRows("ordered");
        assertThat(ordered).isNotEmpty();
        for (Map<String, String> row : ordered) {
            Slice likePattern = TestingTrinoRowsBenchmarkInputs.likePattern(row.get("workload"), row.get("literal_id"), TestingTrinoRowsBenchmarkInputs.literals(row));
            assertThat(TrinoLikePattern.compile(likePattern, TrinoLikePattern.Options.defaults()).planForDiagnostics())
                    .as("%s/%s", row.get("workload"), row.get("literal_id"))
                    .isEqualTo(ORDERED_LITERALS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"english", "russian", "chinese"})
    public void testEveryMethodCyclesThroughRows(String workload)
            throws Exception
    {
        List<String> literals = TestingTrinoRowsBenchmarkInputs.likeLiteralIds(workload);
        assertThat(literals).isNotEmpty();
        BenchmarkTrinoLikeRows benchmark = new BenchmarkTrinoLikeRows();
        for (String literal : literals) {
            // Setup verifies the sampled rows and the cell's hit rows against the manifest.
            BenchmarkTrinoLikeRows.BenchmarkData data = new BenchmarkTrinoLikeRows.BenchmarkData();
            data.setWorkload(workload);
            data.setLiteral(literal);
            data.setCorpusDirectory(MISSING_CORPUS_DIRECTORY);
            data.setup();
            BenchmarkTrinoLikeRows.GeneratedData generated = new BenchmarkTrinoLikeRows.GeneratedData();
            generated.setup(data);

            TestingTrinoRowsBenchmarkInputs.Input input = TestingTrinoRowsBenchmarkInputs.create(workload, literal, MISSING_CORPUS_DIRECTORY);
            // Each method takes one cycle through the rows, so the next method starts again at the
            // first row. The pattern finds the literals in order anywhere in the row.
            String cell = workload + "/" + literal;
            assertEveryRow(cell, input, () -> benchmark.matches(data));
            assertEveryRow(cell, input, () -> benchmark.matchesGenerated(data, generated));
        }
    }

    private static void assertEveryRow(String cell, TestingTrinoRowsBenchmarkInputs.Input input, BooleanSupplier method)
    {
        for (int row = 0; row < input.sources().size(); row++) {
            Slice source = input.sources().get(row);
            assertThat(method.getAsBoolean()).as("%s row %s", cell, row).isEqualTo(containsInOrder(source, input.literals()));
        }
    }

    private static boolean containsInOrder(Slice source, List<Slice> literals)
    {
        int start = 0;
        for (Slice literal : literals) {
            int found = source.indexOf(literal, start);
            if (found < 0) {
                return false;
            }
            start = found + literal.length();
        }
        return true;
    }

    private static List<String> parameter(Class<?> benchmarkDataClass, String name)
            throws ReflectiveOperationException
    {
        return Arrays.asList(benchmarkDataClass.getDeclaredField(name).getAnnotation(Param.class).value());
    }
}
