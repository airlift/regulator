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

import io.airlift.regulator.Re2.ReplaceAllResult;
import io.airlift.regulator.TestingEverydayTrinoRegexpBenchmarkInputs.Source;
import io.airlift.regulator.TestingSpecializedPatternBenchmarkInputs.Workload;
import io.airlift.regulator.TestingSpecializedPatternBenchmarks.Variant;
import io.airlift.slice.Slice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

/// Writes what a native comparator's semantic gate checks for each specialized-pattern
/// workload: `<workload>.input`, its pattern and sources in the native runners' input format;
/// `<workload>.expected`, the values every Regulator variant returns for each source, one line
/// per source as a runner's `--verify-specialized` prints them; and a `workloads.tsv` line with
/// the operation the runner times for the workload and the capture group it applies.
public final class SpecializedPatternNativeInputs
{
    private static final HexFormat HEX = HexFormat.of();

    private SpecializedPatternNativeInputs() {}

    public static void main(String[] args)
            throws Exception
    {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: SpecializedPatternNativeInputs <comparator> <output-directory>");
        }
        String comparator = args[0];
        Path output = Files.createDirectories(Path.of(args[1]));
        StringBuilder workloads = new StringBuilder();
        List<String> workloadIds = TestingSpecializedPatternBenchmarkInputs.workloadIds();
        for (String workloadId : workloadIds) {
            Workload workload = TestingSpecializedPatternBenchmarkInputs.create(workloadId);
            List<Variant> variants = variants(comparator, workloadId);
            List<Source> sources = workload.sources();
            StringBuilder input = new StringBuilder(HEX.formatHex(workload.pattern().getBytes())).append('\n');
            StringBuilder expected = new StringBuilder();
            for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
                Source source = sources.get(sourceIndex);
                input.append(source.sliceOffset()).append('\t').append(source.expectedMatchCount()).append('\t').append(HEX.formatHex(source.value().getBytes())).append('\n');
                String values = values(variants.getFirst().invoke(sourceIndex));
                for (Variant variant : variants.subList(1, variants.size())) {
                    String actual = values(variant.invoke(sourceIndex));
                    if (!actual.equals(values)) {
                        throw new IllegalStateException("%s/%s %s returns %s, not %s".formatted(workloadId, source.sourceId(), variant.name(), actual, values));
                    }
                }
                expected.append(values).append('\n');
            }
            Files.writeString(output.resolve(workloadId + ".input"), input);
            Files.writeString(output.resolve(workloadId + ".expected"), expected);
            workloads.append(workloadId).append('\t').append(runnerOperation(comparator, workload)).append('\t').append(workload.capturedGroup()).append('\n');
        }
        Files.writeString(output.resolve("workloads.tsv"), workloads);
        System.out.printf("Wrote %d %s specialized workloads%n", workloadIds.size(), comparator);
    }

    private static List<Variant> variants(String comparator, String workloadId)
            throws ReflectiveOperationException
    {
        return switch (comparator) {
            case "native-re2" -> BenchmarkSpecializedRe2.variantsForTesting(workloadId);
            case "rust-regex" -> BenchmarkSpecializedRustRegexp.variantsForTesting(workloadId);
            default -> throw new IllegalArgumentException("not a native comparator: " + comparator);
        };
    }

    /// The operation the runner times for `workload`: native RE2 applies the workload's own, as
    /// [BenchmarkSpecializedRe2] does, and the `regex` crate applies the
    /// [capture operation][Workload#captureOperation()] that [BenchmarkSpecializedRustRegexp] times.
    private static String runnerOperation(String comparator, Workload workload)
    {
        return switch (comparator) {
            case "native-re2" -> workload.operation().benchmarkName();
            case "rust-regex" -> workload.captureOperation().benchmarkName();
            default -> throw new IllegalArgumentException("not a native comparator: " + comparator);
        };
    }

    /// The values of one benchmark result as a native runner prints them: their count, then each
    /// in hex, or `-` for a group that did not participate. An extract without a match has no
    /// values.
    static String values(Object result)
    {
        List<?> values = switch (result) {
            case null -> List.of();
            case ReplaceAllResult replaced -> List.of(replaced.result());
            case List<?> list -> list;
            default -> List.of(result);
        };
        return values.size() + ":" + values.stream()
                .map(value -> switch (value) {
                    case null -> "-";
                    case Slice slice -> HEX.formatHex(slice.getBytes());
                    case String string -> HEX.formatHex(string.getBytes(UTF_8));
                    default -> throw new IllegalArgumentException("not a captured value: " + value.getClass().getName());
                })
                .collect(joining(","));
    }
}
