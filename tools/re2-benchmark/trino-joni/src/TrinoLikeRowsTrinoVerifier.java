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

import io.airlift.regulator.BenchmarkTrinoLikeRows;
import io.airlift.regulator.TestingTrinoRowsBenchmarkInputs;
import io.airlift.slice.Slice;
import io.trino.likematcher.LikeMatcher;
import org.openjdk.jmh.annotations.Param;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static io.airlift.regulator.TestingTrinoRowsBenchmarkInputs.MISSING_CORPUS_DIRECTORY;

/// Checks that [BenchmarkTrinoLikeRowsTrino] times the cells of Regulator's
/// `BenchmarkTrinoLikeRows`: the same parameters, and on every sampled row of every cell, the
/// result of the LIKE authority, Trino's `LikeMatcher` with optimization disabled, from
/// Regulator's interpreted and generated methods and from the Trino LIKE functions the twin times. The
/// OpenSubtitles workloads read test resources; the others are checked only when the first argument
/// names a corpus directory provisioned by `trino_rows.py fetch`.
public final class TrinoLikeRowsTrinoVerifier
{
    private static final List<String> PARAMETERS = List.of("workload", "literal", "corpusDirectory");

    private TrinoLikeRowsTrinoVerifier() {}

    public static void main(String[] args)
            throws Exception
    {
        for (String parameter : PARAMETERS) {
            List<String> regulator = parameter(BenchmarkTrinoLikeRows.BenchmarkData.class, parameter);
            List<String> trino = parameter(BenchmarkTrinoLikeRowsTrino.BenchmarkData.class, parameter);
            if (!regulator.equals(trino)) {
                throw new AssertionError("JMH %s parameters differ: Regulator %s, Trino %s".formatted(parameter, regulator, trino));
            }
        }

        String corpusDirectory = args.length > 0 ? args[0] : MISSING_CORPUS_DIRECTORY;
        BenchmarkTrinoLikeRows regulator = new BenchmarkTrinoLikeRows();
        BenchmarkTrinoLikeRowsTrino trino = new BenchmarkTrinoLikeRowsTrino();
        List<String> skipped = new ArrayList<>();
        int cellCount = 0;
        long comparisonCount = 0;
        for (String workload : parameter(BenchmarkTrinoLikeRowsTrino.BenchmarkData.class, "workload")) {
            if (TestingTrinoRowsBenchmarkInputs.readsCorpusDirectory(workload) && corpusDirectory.equals(MISSING_CORPUS_DIRECTORY)) {
                skipped.add(workload);
                continue;
            }
            for (String literal : TestingTrinoRowsBenchmarkInputs.likeLiteralIds(workload)) {
                // Each setup checks the rows and the cell's hit rows against the manifest.
                BenchmarkTrinoLikeRows.BenchmarkData regulatorData = new BenchmarkTrinoLikeRows.BenchmarkData();
                regulatorData.setWorkload(workload);
                regulatorData.setLiteral(literal);
                regulatorData.setCorpusDirectory(corpusDirectory);
                regulatorData.setup();
                BenchmarkTrinoLikeRows.GeneratedData generatedData = new BenchmarkTrinoLikeRows.GeneratedData();
                generatedData.setup(regulatorData);
                BenchmarkTrinoLikeRowsTrino.BenchmarkData trinoData = new BenchmarkTrinoLikeRowsTrino.BenchmarkData();
                trinoData.setWorkload(workload);
                trinoData.setLiteral(literal);
                trinoData.setCorpusDirectory(corpusDirectory);
                trinoData.setup();

                TestingTrinoRowsBenchmarkInputs.Input input = TestingTrinoRowsBenchmarkInputs.create(workload, literal, corpusDirectory);
                LikeMatcher authority = LikeMatcher.compile(input.likePattern().toStringUtf8(), Optional.empty(), false);
                List<Slice> sources = input.sources();
                for (int row = 0; row < sources.size(); row++) {
                    String cell = "%s/%s row %s".formatted(workload, literal, row);
                    Slice source = sources.get(row);
                    boolean expected = authority.match(source.byteArray(), source.byteArrayOffset(), source.length());

                    trinoData.setSourceIndex(row);
                    assertEqual(trino.matches(trinoData), expected, cell, "Trino matches");

                    regulatorData.setSourceIndex(row);
                    assertEqual(regulator.matches(regulatorData), expected, cell, "Regulator matches");

                    regulatorData.setSourceIndex(row);
                    assertEqual(regulator.matchesGenerated(regulatorData, generatedData), expected, cell, "Regulator matchesGenerated");

                    comparisonCount += 3;
                }
                cellCount++;
            }
        }
        System.out.println("Verified %s Trino LIKE row results against LikeMatcher over %s cells%s".formatted(
                comparisonCount,
                cellCount,
                skipped.isEmpty() ? "" : "; skipped %s without a corpus directory".formatted(skipped)));
    }

    private static List<String> parameter(Class<?> benchmarkDataClass, String name)
            throws ReflectiveOperationException
    {
        return Arrays.asList(benchmarkDataClass.getDeclaredField(name).getAnnotation(Param.class).value());
    }

    private static void assertEqual(boolean actual, boolean expected, String cell, String method)
    {
        if (actual != expected) {
            throw new AssertionError("%s/%s: %s, LikeMatcher=%s".formatted(cell, method, actual, expected));
        }
    }
}
