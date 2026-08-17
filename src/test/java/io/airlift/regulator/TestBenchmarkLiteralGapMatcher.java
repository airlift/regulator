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

import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkLiteralGapMatcher
{
    @Test
    public void testAllRoutesAgree()
    {
        BenchmarkLiteralGapMatcher benchmark = new BenchmarkLiteralGapMatcher();
        for (BenchmarkLiteralGapMatcher.Scenario scenario : BenchmarkLiteralGapMatcher.Scenario.values()) {
            BenchmarkLiteralGapMatcher.BenchmarkData data = new BenchmarkLiteralGapMatcher.BenchmarkData();
            data.scenario = scenario;
            data.setup();

            boolean expected = benchmark.likePublic(data);
            assertThat(benchmark.sharedMatcher(data)).as("%s shared", scenario).isEqualTo(expected);
            assertThat(benchmark.likeWildcardControl(data)).as("%s wildcard", scenario).isEqualTo(expected);
            assertThat(benchmark.regexpPublic(data)).as("%s regexp", scenario).isEqualTo(expected);
            assertThat(benchmark.regexpPreviousRouteControl(data)).as("%s previous", scenario).isEqualTo(expected);
            assertThat(benchmark.regexpGeneralDfaControl(data)).as("%s DFA", scenario).isEqualTo(expected);
        }
    }
}
