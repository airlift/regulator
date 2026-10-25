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

import io.airlift.regulator.BenchmarkSpecializedTrinoRegexp.Variant;
import io.airlift.regulator.TestingEverydayTrinoRegexpBenchmarkInputs.Source;
import io.airlift.regulator.TestingSpecializedTrinoRegexpBenchmarkInputs.Operation;
import io.airlift.regulator.TestingSpecializedTrinoRegexpBenchmarkInputs.Workload;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Param;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

public class TestBenchmarkSpecializedTrinoRegexp
{
    private static final String EXPECTED_MANIFEST_SHA256 = "2245cd0891103d086a72162d344fac0b342a73d75c10ef090aafceb63da11047";
    private static final Path CATALOG = Path.of("tools/re2-benchmark/baseline/specialized-patterns.tsv");

    @Test
    public void testWorkloads()
            throws ReflectiveOperationException
    {
        assertThat(parameters(BenchmarkSpecializedTrinoRegexp.ReplaceData.class))
                .containsExactlyElementsOf(TestingSpecializedTrinoRegexpBenchmarkInputs.workloadIds(Operation.REPLACE));
        assertThat(parameters(BenchmarkSpecializedTrinoRegexp.ExtractData.class))
                .containsExactlyElementsOf(TestingSpecializedTrinoRegexpBenchmarkInputs.workloadIds(Operation.EXTRACT));
        assertThat(parameters(BenchmarkSpecializedTrinoRegexp.ExtractAllData.class))
                .containsExactlyElementsOf(TestingSpecializedTrinoRegexpBenchmarkInputs.workloadIds(Operation.EXTRACT_ALL));

        // The URL workloads share one rotation of distinct URLs; the everyday test checks the others.
        List<String> urlWorkloadIds = TestingSpecializedTrinoRegexpBenchmarkInputs.urlWorkloadIds();
        List<Source> urls = TestingSpecializedTrinoRegexpBenchmarkInputs.create(urlWorkloadIds.getFirst()).sources();
        assertThat(urls).hasSize(8);
        assertThat(urls).extracting(Source::sourceId).doesNotHaveDuplicates();
        assertThat(urls).extracting(Source::value).doesNotHaveDuplicates();
        assertThat(urls).extracting(Source::sliceOffset).contains(0, 3);
        assertThat(urls).anyMatch(url -> url.value().length() > url.value().toStringUtf8().length());
        List<Tuple> rotation = urls.stream()
                .map(url -> tuple(url.sourceId(), url.value(), url.sliceOffset()))
                .toList();
        for (String workloadId : urlWorkloadIds) {
            Workload workload = TestingSpecializedTrinoRegexpBenchmarkInputs.create(workloadId);
            assertThat(workload.sources()).extracting(Source::sourceId, Source::value, Source::sliceOffset).as(workloadId).containsExactlyElementsOf(rotation);
            assertThat(workload.input().origin()).as(workloadId).isEqualTo(workloadId.equals("clickbenchHost") ? "clickbench-q29" : "designed-control");
            assertThat(workload.sources()).extracting(Source::expectedMatchCount)
                    .contains(0L)
                    .anyMatch(count -> count > 0);
            TrinoRegexp regexp = TrinoRegexp.compile(workload.pattern());
            for (Source source : workload.sources()) {
                assertThat(regexp.count(source.value())).as("%s/%s", workloadId, source.sourceId()).isEqualTo(source.expectedMatchCount());
            }
        }
    }

    @Test
    public void testCatalogDescribesTheWorkloads()
            throws Exception
    {
        // The baseline's rows.tsv and the report take the workloads from this catalog.
        List<String> catalog = Files.readAllLines(CATALOG).stream()
                .filter(line -> line.startsWith("trino-specialized-patterns-"))
                .map(line -> String.join("\t", List.of(line.split("\t", -1)).subList(1, 4)))
                .toList();
        assertThat(catalog).containsExactlyElementsOf(TestingSpecializedTrinoRegexpBenchmarkInputs.workloadIds().stream()
                .map(TestingSpecializedTrinoRegexpBenchmarkInputs::create)
                .map(workload -> String.join("\t", workload.workloadId(), workload.operation().benchmarkName(), workload.pattern().toStringUtf8()))
                .toList());
    }

    @Test
    public void testEveryVariantProducesTheSameResult()
            throws ReflectiveOperationException
    {
        for (String workloadId : TestingSpecializedTrinoRegexpBenchmarkInputs.workloadIds()) {
            Workload workload = TestingSpecializedTrinoRegexpBenchmarkInputs.create(workloadId);
            List<Variant> variants = BenchmarkSpecializedTrinoRegexp.variantsForTesting(workloadId);
            List<Source> sources = workload.sources();
            List<Object> expected = new ArrayList<>();
            for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
                Source source = sources.get(sourceIndex);
                String description = workloadId + "/" + source.sourceId();
                Object result = variants.getFirst().invoke(sourceIndex);
                long matches = source.expectedMatchCount();
                switch (workload.operation()) {
                    case REPLACE -> assertThat(result.equals(source.value())).as(description).isEqualTo(matches == 0);
                    case EXTRACT -> assertThat(result == null).as(description).isEqualTo(matches == 0);
                    case EXTRACT_ALL -> assertThat((List<?>) result).as(description).hasSize((int) matches);
                }
                expected.add(result);
            }

            // Started at the first source, each variant returns each source's result as its
            // rotation advances and wraps around.
            for (Variant variant : variants) {
                variant.setSourceIndex(0);
                for (int call = 0; call <= sources.size(); call++) {
                    int sourceIndex = call % sources.size();
                    assertThat(variant.invoke()).as("%s/%s %s", workloadId, sources.get(sourceIndex).sourceId(), variant.name()).isEqualTo(expected.get(sourceIndex));
                }
            }
        }
    }

    @Test
    public void testManifestChecksum()
            throws Exception
    {
        byte[] manifest;
        try (InputStream input = requireNonNull(TestingSpecializedTrinoRegexpBenchmarkInputs.class
                .getResourceAsStream("/io/airlift/regulator/specialized-trino-workloads.tsv"))) {
            manifest = input.readAllBytes();
        }

        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest))).isEqualTo(EXPECTED_MANIFEST_SHA256);
    }

    private static List<String> parameters(Class<?> state)
            throws ReflectiveOperationException
    {
        return List.of(state.getDeclaredField("workload").getAnnotation(Param.class).value());
    }
}
