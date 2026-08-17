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
import io.airlift.slice.Slices;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class TestingEverydayTrinoRegexpBenchmarkInputs
{
    private static final String RESOURCE = "/io/airlift/regulator/everyday-trino-workloads.tsv";
    private static final Map<String, Input> INPUTS = loadInputs();

    private TestingEverydayTrinoRegexpBenchmarkInputs() {}

    public static List<String> workloadIds()
    {
        return List.copyOf(INPUTS.keySet());
    }

    public static Input create(String workloadId)
    {
        Input input = INPUTS.get(workloadId);
        if (input == null) {
            throw new IllegalArgumentException("unknown workload: " + workloadId);
        }
        return input;
    }

    private static Map<String, Input> loadInputs()
    {
        InputStream inputStream = TestingEverydayTrinoRegexpBenchmarkInputs.class.getResourceAsStream(RESOURCE);
        if (inputStream == null) {
            throw new IllegalStateException("missing benchmark input resource: " + RESOURCE);
        }

        Map<String, InputBuilder> builders = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, UTF_8))) {
            String header = reader.readLine();
            if (!"workload_id\tfamily\torigin\tsource_id\tslice_offset\texpected_match_count\tpattern\tsource".equals(header)) {
                throw new IllegalStateException("unexpected benchmark input header: " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 8) {
                    throw new IllegalStateException("unexpected benchmark input field count: " + line);
                }
                String workloadId = fields[0];
                InputBuilder builder = builders.computeIfAbsent(
                        workloadId,
                        _ -> new InputBuilder(workloadId, fields[1], fields[2], fields[6]));
                builder.add(fields);
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Map<String, Input> inputs = new LinkedHashMap<>();
        builders.forEach((workloadId, builder) -> inputs.put(workloadId, builder.build()));
        return Collections.unmodifiableMap(inputs);
    }

    private static final class InputBuilder
    {
        private final String workloadId;
        private final String family;
        private final String origin;
        private final String pattern;
        private final List<Source> sources = new ArrayList<>();

        private InputBuilder(String workloadId, String family, String origin, String pattern)
        {
            this.workloadId = workloadId;
            this.family = family;
            this.origin = origin;
            this.pattern = pattern;
        }

        private void add(String[] fields)
        {
            if (!family.equals(fields[1]) || !origin.equals(fields[2]) || !pattern.equals(fields[6])) {
                throw new IllegalStateException("inconsistent workload metadata: " + workloadId);
            }
            int sliceOffset = Integer.parseInt(fields[4]);
            long expectedMatchCount = Long.parseLong(fields[5]);
            byte[] sourceBytes = fields[7].getBytes(UTF_8);
            byte[] storage = new byte[sliceOffset + sourceBytes.length + 3];
            System.arraycopy(sourceBytes, 0, storage, sliceOffset, sourceBytes.length);
            Slice source = Slices.wrappedBuffer(storage).slice(sliceOffset, sourceBytes.length);
            sources.add(new Source(fields[3], source, sliceOffset, expectedMatchCount));
        }

        private Input build()
        {
            return new Input(
                    workloadId,
                    family,
                    origin,
                    Slices.utf8Slice(pattern),
                    List.copyOf(sources));
        }
    }

    public record Input(String workloadId, String family, String origin, Slice pattern, List<Source> sources) {}

    public record Source(String sourceId, Slice value, int sliceOffset, long expectedMatchCount) {}
}
