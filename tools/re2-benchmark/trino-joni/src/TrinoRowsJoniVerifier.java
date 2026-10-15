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

import io.airlift.regulator.BenchmarkTrinoRows;
import io.airlift.regulator.TestingTrinoRowsBenchmarkInputs;
import io.airlift.slice.Slice;
import io.trino.type.JoniRegexp;
import org.openjdk.jmh.annotations.Param;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static io.airlift.regulator.TestingTrinoRowsBenchmarkInputs.MISSING_CORPUS_DIRECTORY;
import static io.trino.operator.scalar.JoniRegexpCasts.joniRegexp;

/**
 * Checks that {@link BenchmarkTrinoRowsJoni} times the cells of Regulator's {@code BenchmarkTrinoRows}:
 * the same parameters, and on every sampled row of every cell, the same result from each benchmark
 * method. The OpenSubtitles workloads read test resources; the others are checked only when the first
 * argument names a corpus directory provisioned by {@code trino_rows.py fetch}.
 */
public final class TrinoRowsJoniVerifier
{
    private static final List<String> PARAMETERS = List.of("workload", "literal", "corpusDirectory");

    private TrinoRowsJoniVerifier() {}

    public static void main(String[] args)
            throws Exception
    {
        for (String parameter : PARAMETERS) {
            List<String> regulator = parameter(BenchmarkTrinoRows.BenchmarkData.class, parameter);
            List<String> joni = parameter(BenchmarkTrinoRowsJoni.BenchmarkData.class, parameter);
            if (!regulator.equals(joni)) {
                throw new AssertionError("JMH %s parameters differ: Regulator %s, Joni %s".formatted(parameter, regulator, joni));
            }
        }

        String corpusDirectory = args.length > 0 ? args[0] : MISSING_CORPUS_DIRECTORY;
        BenchmarkTrinoRows regulator = new BenchmarkTrinoRows();
        BenchmarkTrinoRowsJoni joni = new BenchmarkTrinoRowsJoni();
        List<String> skipped = new ArrayList<>();
        int cellCount = 0;
        long comparisonCount = 0;
        for (String workload : parameter(BenchmarkTrinoRowsJoni.BenchmarkData.class, "workload")) {
            if (TestingTrinoRowsBenchmarkInputs.readsCorpusDirectory(workload) && corpusDirectory.equals(MISSING_CORPUS_DIRECTORY)) {
                skipped.add(workload);
                continue;
            }
            for (String literal : TestingTrinoRowsBenchmarkInputs.literalIds(workload)) {
                // Each setup checks the rows and the literal's hit count against the manifest.
                BenchmarkTrinoRows.BenchmarkData regulatorData = new BenchmarkTrinoRows.BenchmarkData();
                regulatorData.setWorkload(workload);
                regulatorData.setLiteral(literal);
                regulatorData.setCorpusDirectory(corpusDirectory);
                regulatorData.setup();
                BenchmarkTrinoRowsJoni.BenchmarkData joniData = new BenchmarkTrinoRowsJoni.BenchmarkData();
                joniData.setWorkload(workload);
                joniData.setLiteral(literal);
                joniData.setCorpusDirectory(corpusDirectory);
                joniData.setup();

                TestingTrinoRowsBenchmarkInputs.Input input = TestingTrinoRowsBenchmarkInputs.create(workload, literal, corpusDirectory);
                JoniRegexp pattern = joniRegexp(input.pattern());
                for (int row = 0; row < input.sources().size(); row++) {
                    JoniSliceOperationsVerifier.verify(input.sources().get(row), pattern);
                    String cell = "%s/%s row %s".formatted(workload, literal, row);

                    regulatorData.setSourceIndex(row);
                    joniData.setSourceIndex(row);
                    assertEqual(regulator.contains(regulatorData), joni.contains(joniData), cell, "contains");

                    regulatorData.setSourceIndex(row);
                    joniData.setSourceIndex(row);
                    assertEqual(regulator.count(regulatorData), joni.count(joniData), cell, "count");

                    regulatorData.setSourceIndex(row);
                    joniData.setSourceIndex(row);
                    assertEqual(regulator.position(regulatorData), joni.position(joniData), cell, "position");

                    regulatorData.setSourceIndex(row);
                    joniData.setSourceIndex(row);
                    assertEqual(regulator.extract(regulatorData), joni.extract(joniData), cell, "extract");

                    regulatorData.setSourceIndex(row);
                    joniData.setSourceIndex(row);
                    assertEqual(regulator.split(regulatorData), joni.split(joniData), cell, "split");

                    regulatorData.setSourceIndex(row);
                    joniData.setSourceIndex(row);
                    assertEqual(regulator.replace(regulatorData), joni.replace(joniData), cell, "replace");

                    comparisonCount += 6;
                }
                cellCount++;
            }
        }
        System.out.println("Verified %s Trino row Regulator/Joni results over %s literals%s".formatted(
                comparisonCount,
                cellCount,
                skipped.isEmpty() ? "" : "; skipped %s without a corpus directory".formatted(skipped)));
    }

    private static List<String> parameter(Class<?> benchmarkDataClass, String name)
            throws ReflectiveOperationException
    {
        return Arrays.asList(benchmarkDataClass.getDeclaredField(name).getAnnotation(Param.class).value());
    }

    private static void assertEqual(Object regulatorValue, Object joniValue, String cell, String method)
    {
        if (!Objects.equals(regulatorValue, joniValue)) {
            throw new AssertionError("%s/%s: Regulator=%s, Joni=%s".formatted(cell, method, regulatorValue, joniValue));
        }
    }
}
