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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkRe2ScanPlan
{
    // Workloads whose patterns select no plan, so both scanPlan values time the ordinary engine.
    private static final Set<String> WITHOUT_PLAN = Set.of(
            "UNANCHORED_RUN",
            "UNANCHORED_RUN_LEADING",
            "LONG_UNANCHORED_RUN",
            "LITERAL_CONTROLS",
            "DEEP_REJECTIONS",
            "FALLBACK",
            "LOWERED_FALLBACK");

    @Test
    public void testBenchmarkResults()
            throws ReflectiveOperationException
    {
        assertThat(BenchmarkRe2ScanPlan.workloads()).containsAll(BenchmarkTrinoScanPlan.workloads()).containsAll(WITHOUT_PLAN);
        BenchmarkRe2ScanPlan benchmark = new BenchmarkRe2ScanPlan();
        for (String workload : BenchmarkRe2ScanPlan.workloads()) {
            for (boolean dotAll : new boolean[] {true, false}) {
                for (boolean scanPlan : new boolean[] {true, false}) {
                    // Each method advances its own cursor, so give each one a separate state.
                    BenchmarkRe2ScanPlan.BenchmarkData contains = data(workload, dotAll, scanPlan);
                    BenchmarkRe2ScanPlan.BenchmarkData count = data(workload, dotAll, scanPlan);
                    BenchmarkRe2ScanPlan.BenchmarkData boundaries = data(workload, dotAll, scanPlan);
                    BenchmarkRe2ScanPlan.BenchmarkData extract = data(workload, dotAll, scanPlan);
                    BenchmarkRe2ScanPlan.BenchmarkData extractAll = data(workload, dotAll, scanPlan);
                    BenchmarkRe2ScanPlan.BenchmarkData replace = data(workload, dotAll, scanPlan);
                    BenchmarkRe2ScanPlan.BenchmarkData compile = data(workload, dotAll, scanPlan);
                    boolean planned = scanPlan && !WITHOUT_PLAN.contains(BenchmarkRe2ScanPlan.trinoWorkload(workload));
                    assertThat(contains.patterns).as("%s dotAll=%s scanPlan=%s", workload, dotAll, scanPlan)
                            .allSatisfy(pattern -> assertThat(pattern.usesTrinoScanPlanForDiagnostics()).isEqualTo(planned));
                    Re2[] patterns = Arrays.stream(contains.expressions).map(Re2::compile).toArray(Re2[]::new);
                    boolean[] planMatched = new boolean[patterns.length];
                    // Continue past the last row so that each method's cursor also wraps around.
                    for (int call = 0; call < contains.inputs.length + patterns.length + 1; call++) {
                        int row = call % contains.inputs.length;
                        Slice input = contains.inputs[row];
                        Re2 pattern = patterns[row % patterns.length];
                        int group = pattern.capturingGroupCount() == 0 ? 0 : 1;
                        String description = workload + " dotAll=" + dotAll + " scanPlan=" + scanPlan + " row=" + row;
                        if (input.length() > 0) {
                            assertThat(input.byteArrayOffset()).as(description).isPositive();
                        }

                        MatchResult match = pattern.findResult(input);
                        planMatched[row % patterns.length] |= match != null;
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
                        assertThat(benchmark.replace(replace)).as(description).isEqualTo(pattern.replaceAll(input, utf8Slice("\\" + group)).result());

                        Re2 compiled = benchmark.compile(compile);
                        assertThat(compiled.capturingGroupCount()).as(description).isEqualTo(pattern.capturingGroupCount());
                        assertThat(compiled.find(input)).as(description).isEqualTo(match != null);
                        assertThat(compiled.usesTrinoScanPlanForDiagnostics()).as(description).isEqualTo(planned);
                    }
                    // A row paired with the wrong plan would time only failure paths.
                    assertThat(planMatched).as("%s dotAll=%s scanPlan=%s", workload, dotAll, scanPlan).containsOnly(true);
                }
            }
        }
    }

    @Test
    public void testTextVariants()
            throws ReflectiveOperationException
    {
        for (String workload : BenchmarkRe2ScanPlan.workloads()) {
            if (workload.equals(BenchmarkRe2ScanPlan.trinoWorkload(workload))) {
                continue;
            }
            for (boolean dotAll : new boolean[] {true, false}) {
                BenchmarkRe2ScanPlan.BenchmarkData data = data(workload, dotAll, true);
                String description = workload + " dotAll=" + dotAll;
                assertThat(data.inputs).as(description).hasSize(504);
                if (workload.endsWith("_NON_ASCII")) {
                    assertThat(data.inputs).as(description).allSatisfy(input -> {
                        assertThat(isValidUtf8(input)).isTrue();
                        assertThat(input.toStringUtf8()).matches("(?s).*[^\\x00-\\x7F].*");
                    });
                    continue;
                }
                // Malformed rows include runs or tails that consume an accepted sequence and ones that
                // a rejected sequence stops.
                assertThat(data.inputs).as(description).allSatisfy(input -> assertThat(isValidUtf8(input)).isFalse());
                Re2 pattern = data.patterns[0];
                assertThat(data.inputs).as(description)
                        .anySatisfy(input -> assertThat(consumesMalformed(pattern, input)).isTrue())
                        .anySatisfy(input -> assertThat(stopsAtMalformed(pattern, input)).isTrue());
            }
        }
    }

    /**
     * Returns whether the row's capture holds a sequence that RE2 accepts but that is not valid
     * UTF-8.
     */
    private static boolean consumesMalformed(Re2 pattern, Slice input)
    {
        MatchResult match = pattern.findResult(input);
        return match != null && !isValidUtf8(input.slice(match.start(1), match.end(1) - match.start(1)));
    }

    /**
     * Returns whether the row's capture stops at a sequence that RE2 rejects, or whether the row
     * misses only because of such sequences, matching once their bytes become ASCII.
     */
    private static boolean stopsAtMalformed(Re2 pattern, Slice input)
    {
        byte[] bytes = input.getBytes();
        MatchResult match = pattern.findResult(input);
        if (match != null) {
            return match.end(1) < bytes.length && re2Width(bytes, match.end(1)) == 0;
        }
        boolean rejected = false;
        int position = 0;
        while (position < bytes.length) {
            int width = re2Width(bytes, position);
            if (width == 0) {
                bytes[position] = 'z';
                rejected = true;
                width = 1;
            }
            position += width;
        }
        return rejected && pattern.find(wrappedBuffer(bytes));
    }

    /**
     * Returns the width of the character that RE2's classes read at {@code index}, or 0 at a
     * sequence they reject.
     */
    private static int re2Width(byte[] bytes, int index)
    {
        return bytes[index] >= 0 ? 1 : Utf8.re2CharacterWidth(bytes, index, bytes.length);
    }

    private static boolean isValidUtf8(Slice input)
    {
        try {
            UTF_8.newDecoder().decode(ByteBuffer.wrap(input.getBytes()));
            return true;
        }
        catch (CharacterCodingException e) {
            return false;
        }
    }

    private static BenchmarkRe2ScanPlan.BenchmarkData data(String workload, boolean dotAll, boolean scanPlan)
    {
        BenchmarkRe2ScanPlan.BenchmarkData data = new BenchmarkRe2ScanPlan.BenchmarkData();
        data.workload = workload;
        data.dotAll = dotAll;
        data.scanPlan = scanPlan;
        data.setup();
        return data;
    }
}
