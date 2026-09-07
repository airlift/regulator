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

import static io.airlift.regulator.BenchmarkClickBenchRegexp.PATTERN;
import static io.airlift.regulator.BenchmarkClickBenchRegexp.REWRITE;
import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkClickBenchRegexp
{
    @Test
    public void testWorkloads()
    {
        BenchmarkClickBenchRegexp benchmark = new BenchmarkClickBenchRegexp();
        for (boolean dotAll : new boolean[] {true, false}) {
            BenchmarkClickBenchRegexp.CompileData compileData = new BenchmarkClickBenchRegexp.CompileData();
            compileData.dotAll = dotAll;
            compileData.setup();
            assertThat(benchmark.compile(compileData).pattern()).isEqualTo(PATTERN);

            for (BenchmarkClickBenchRegexp.Workload workload : BenchmarkClickBenchRegexp.Workload.values()) {
                BenchmarkClickBenchRegexp.MatchData data = new BenchmarkClickBenchRegexp.MatchData();
                data.workload = workload;
                data.dotAll = dotAll;
                data.setup();

                Slice host = workload.expectedHost(dotAll);
                boolean matched = host != null;
                String description = workload + " dotAll=" + dotAll;
                assertThat(benchmark.contains(data)).as(description).isEqualTo(matched);
                assertThat(benchmark.matchBoundaries(data)).as(description)
                        .isEqualTo(matched ? workload.source().length() : -1L);
                long expectedCapture = matched ? ((long) workload.hostStart() << 32) | (workload.hostStart() + host.length()) : -1;
                // Repeated calls must reset capture state correctly.
                for (int iteration = 0; iteration < 3; iteration++) {
                    assertThat(benchmark.captureWithReusedMatcher(data)).as(description).isEqualTo(expectedCapture);
                }
                assertThat(benchmark.extract(data)).as(description).isEqualTo(host);
                Slice expectedReplacement = matched ? host : workload.source();
                assertThat(benchmark.replaceFirst(data)).as(description).isEqualTo(expectedReplacement);
                assertThat(benchmark.replaceAll(data)).as(description).isEqualTo(expectedReplacement);
            }
        }
    }

    @Test
    public void testMalformedUtf8IsNotAnAllByteWildcard()
    {
        Re2 pattern = Re2.compile(PATTERN, Re2.Options.defaults().setDotMatchesNewline(true));
        for (String input : new String[] {"https://examXple.com/path", "https://example.com/paXth"}) {
            Slice source = utf8Slice(input);
            source.setByte(input.indexOf('X'), 0xFF);
            assertThat(pattern.find(source)).isFalse();
            assertThat(pattern.extract(source, REWRITE)).isNull();
            assertThat(pattern.replaceFirst(source, REWRITE)).isSameAs(source);
        }
    }
}
