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

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Param;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkEverydayTrinoRegexp
{
    private static final String EXPECTED_MANIFEST_SHA256 = "f93c4cc31ef23efa29ee1addfc6709a1bc45bdf8805742bd014e5f120982aaff";

    @Test
    public void testManifestShape()
    {
        assertThat(TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds()).hasSize(13);
        for (String workloadId : TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds()) {
            TestingEverydayTrinoRegexpBenchmarkInputs.Input input =
                    TestingEverydayTrinoRegexpBenchmarkInputs.create(workloadId);
            assertThat(input.sources()).hasSize(8);
            assertThat(input.sources()).extracting(TestingEverydayTrinoRegexpBenchmarkInputs.Source::sourceId)
                    .doesNotHaveDuplicates();
            assertThat(input.sources()).extracting(TestingEverydayTrinoRegexpBenchmarkInputs.Source::value)
                    .doesNotHaveDuplicates();
            assertThat(input.sources()).extracting(TestingEverydayTrinoRegexpBenchmarkInputs.Source::sliceOffset)
                    .contains(0, 3);
            assertThat(input.sources()).extracting(TestingEverydayTrinoRegexpBenchmarkInputs.Source::expectedMatchCount)
                    .contains(0L)
                    .anyMatch(count -> count > 0);
        }

        List<TestingEverydayTrinoRegexpBenchmarkInputs.Input> inputs =
                TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds().stream()
                        .map(TestingEverydayTrinoRegexpBenchmarkInputs::create)
                        .toList();
        assertThat(inputs)
                .extracting(TestingEverydayTrinoRegexpBenchmarkInputs.Input::family)
                .contains(
                        "capture",
                        "contains-pattern",
                        "literal-alternation",
                        "literal-search",
                        "structured-token",
                        "tokenization",
                        "unicode");
        List<TestingEverydayTrinoRegexpBenchmarkInputs.Source> sources = inputs.stream()
                .flatMap(input -> input.sources().stream())
                .toList();
        assertThat(sources).hasSize(104);
        assertThat(sources).anyMatch(source -> source.value().length() <= 8);
        assertThat(sources).anyMatch(source -> source.value().length() >= 64);
        assertThat(sources).anyMatch(source -> source.value().length() > source.value().toStringUtf8().length());
        assertThat(sources)
                .extracting(TestingEverydayTrinoRegexpBenchmarkInputs.Source::expectedMatchCount)
                .contains(0L, 1L, 2L, 3L, 6L);
    }

    @Test
    public void testJmhParametersMatchManifest()
            throws ReflectiveOperationException
    {
        Field workload = BenchmarkEverydayTrinoRegexp.BenchmarkData.class.getDeclaredField("workload");
        List<String> parameters = Arrays.asList(workload.getAnnotation(Param.class).value());

        assertThat(parameters).containsExactlyElementsOf(TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds());
    }

    @Test
    public void testEveryOperationAndSource()
    {
        BenchmarkEverydayTrinoRegexp benchmark = new BenchmarkEverydayTrinoRegexp();
        for (String workloadId : TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds()) {
            TestingEverydayTrinoRegexpBenchmarkInputs.Input input =
                    TestingEverydayTrinoRegexpBenchmarkInputs.create(workloadId);
            BenchmarkEverydayTrinoRegexp.BenchmarkData data = new BenchmarkEverydayTrinoRegexp.BenchmarkData();
            data.setWorkload(workloadId);
            data.setup();

            for (int sourceIndex = 0; sourceIndex < input.sources().size(); sourceIndex++) {
                long expectedCount = input.sources().get(sourceIndex).expectedMatchCount();
                data.setSourceIndex(sourceIndex);
                assertThat(benchmark.contains(data)).isEqualTo(expectedCount > 0);
                data.setSourceIndex(sourceIndex);
                assertThat(benchmark.count(data)).isEqualTo(expectedCount);
                data.setSourceIndex(sourceIndex);
                if (expectedCount >= 3) {
                    assertThat(benchmark.positionThird(data)).isPositive();
                }
                else {
                    assertThat(benchmark.positionThird(data)).isEqualTo(-1);
                }
                data.setSourceIndex(sourceIndex);
                assertThat(benchmark.extract(data) == null).isEqualTo(expectedCount == 0);
                data.setSourceIndex(sourceIndex);
                assertThat(benchmark.extractAll(data)).hasSize((int) expectedCount);
                data.setSourceIndex(sourceIndex);
                assertThat(benchmark.split(data)).isNotEmpty();
                data.setSourceIndex(sourceIndex);
                assertThat(benchmark.replace(data)).isNotNull();
                data.setSourceIndex(sourceIndex);
                assertThat(benchmark.replaceLambda(data)).isNotNull();
            }
        }
    }

    @Test
    public void testLikeConstructionDiagnostic()
    {
        BenchmarkEverydayTrinoRegexp benchmark = new BenchmarkEverydayTrinoRegexp();
        BenchmarkEverydayTrinoRegexp.BenchmarkData data = new BenchmarkEverydayTrinoRegexp.BenchmarkData();
        data.setWorkload("logLevel");
        data.setup();

        assertThat(benchmark.constructLikeWildcardChain(data).planForDiagnostics())
                .isEqualTo(TrinoLikePattern.Plan.LITERAL_GAPS);
    }

    @Test
    public void testOriginsAreExplicit()
    {
        assertThat(TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds())
                .extracting(TestingEverydayTrinoRegexpBenchmarkInputs::create)
                .extracting(TestingEverydayTrinoRegexpBenchmarkInputs.Input::origin)
                .allMatch(origin -> origin.equals("designed-control") || origin.startsWith("trino-c7503d170344-"));
        assertThat(new HashSet<>(TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds())).hasSize(13);
    }

    @Test
    public void testManifestChecksum()
            throws Exception
    {
        byte[] manifest;
        try (InputStream input = requireNonNull(TestingEverydayTrinoRegexpBenchmarkInputs.class
                .getResourceAsStream("/io/airlift/regulator/everyday-trino-workloads.tsv"))) {
            manifest = input.readAllBytes();
        }

        assertThat(HexFormat.of().formatHex(sha256().digest(manifest))).isEqualTo(EXPECTED_MANIFEST_SHA256);
    }

    @Test
    public void testPinnedTrinoTranslationDecisions()
            throws Exception
    {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(requireNonNull(
                TestingEverydayTrinoRegexpBenchmarkInputs.class.getResourceAsStream(
                        "/io/airlift/regulator/trino-regexp-benchmark-translations.tsv")), UTF_8))) {
            assertThat(reader.lines()).containsExactly(
                    "source_case\tpattern\tdecision\tworkload_id\treason",
                    "dot-star-x\t.*x.*\tdiagnostic-only\t-\tSynthetic single-byte dot-star probe remains in the diagnostic matrix",
                    "dot-star-alternation\t.*(x|y).*\tdiagnostic-only\t-\tSynthetic alternation dot-star probe remains in the diagnostic matrix",
                    "longdotstar\t.*coolfunctionname.*\tincluded\ttrinoDotStarLiteral\tTranslated to rotating changing Slice values",
                    "phone\t\\d{3}/\\d{3}/\\d{4}\tincluded\ttrinoPhone\tTranslated exactly with deterministic changing inputs",
                    "literal\tliteral\tincluded\ttrinoLiteral\tTranslated exactly with deterministic changing inputs");
        }
    }

    private static MessageDigest sha256()
    {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
