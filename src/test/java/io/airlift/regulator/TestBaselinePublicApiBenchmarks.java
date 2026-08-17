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

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TestBaselinePublicApiBenchmarks
{
    @Test
    void testJavaRegexpBenchmark()
    {
        BenchmarkJavaRegexpPublicApi benchmark = new BenchmarkJavaRegexpPublicApi();
        BenchmarkJavaRegexpPublicApi.CompileData compileData = new BenchmarkJavaRegexpPublicApi.CompileData();
        compileData.setup();
        assertThat(benchmark.compile(compileData)).isNotNull();
        for (int sourceLength : new int[] {64, 32_768}) {
            for (String outcome : new String[] {"match", "noMatch"}) {
                BenchmarkJavaRegexpPublicApi.BenchmarkData data = new BenchmarkJavaRegexpPublicApi.BenchmarkData();
                data.sourceLength = sourceLength;
                data.outcome = outcome;
                data.setup();

                boolean expected = outcome.equals("match");
                assertThat(benchmark.find(data)).isEqualTo(expected);
                assertThat(benchmark.lookingAt(data)).isFalse();
                assertThat(benchmark.matches(data)).isFalse();
                assertThat(benchmark.findInRange(data)).isEqualTo(expected);
                assertThat(benchmark.findInto(data)).isEqualTo(expected);
                assertThat(benchmark.findResult(data) != null).isEqualTo(expected);
                assertThat(benchmark.matcherFind(data)).isEqualTo(expected);
            }
        }
    }

    @Test
    void testPatternCollectionBenchmark()
    {
        BenchmarkPatternCollections benchmark = new BenchmarkPatternCollections();
        for (int patternCount : new int[] {16, 128}) {
            for (int sourceLength : new int[] {1024, 32_768}) {
                BenchmarkPatternCollections.BenchmarkData data = new BenchmarkPatternCollections.BenchmarkData();
                data.patternCount = patternCount;
                data.sourceLength = sourceLength;
                data.setup();

                assertThat(benchmark.compileRe2Set(data)).isNotNull();
                assertThat(benchmark.matchesAny(data)).isTrue();
                assertThat(benchmark.matchingSetPatternIds(data)).containsExactly(patternCount - 1);
                assertThat(benchmark.compileFilteredRe2(data)).isNotNull();
                assertThat(benchmark.potentialPatternIds(data)).containsExactly(IntStream.range(0, patternCount).toArray());
                assertThat(benchmark.firstMatchingPatternId(data)).isEqualTo(patternCount - 1);
                assertThat(benchmark.matchingFilteredPatternIds(data)).containsExactly(patternCount - 1);
            }
        }
    }
}
