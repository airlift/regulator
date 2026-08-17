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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkRe2CompilerPhases
{
    @ParameterizedTest
    @ValueSource(strings = {
            "(.*)-(\\d+)-of-(\\d+)",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
            "[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
            "([ -~])*(A)(B)(C)(D)(E)(F)(G)(H)(I)(J)(K)(L)(M)(N)(O)(P)(Q)(R)(S)(T)(U)(V)(W)(X)(Y)(Z)$",
            "[0-9]+.(.*)",
            "(?i)ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
            "[a-z]+-[0-9]+",
            "[a-z]{8}-[0-9]{4}",
    })
    public void testCompilerPhases(String pattern)
    {
        BenchmarkRe2CompilerPhases.CompilePatternState state = new BenchmarkRe2CompilerPhases.CompilePatternState();
        state.pattern = pattern;
        state.setup();

        BenchmarkRe2CompilerPhases benchmark = new BenchmarkRe2CompilerPhases();
        assertThat(benchmark.parse(state)).isNotNull();
        assertThat(benchmark.simplify(state)).isNotNull();
        assertThat(benchmark.analyzeExpression(state)).isNotNull();
        benchmark.analyzeRequiredPrefix(state);
        assertThat(benchmark.analyzeMatchLength(state)).isNotNull();
        assertThat(benchmark.compileNormalizedToRawProg(state)).isNotNull();
        assertThat(benchmark.compileNormalizedToOptimizedProg(state)).isNotNull();
        assertThat(benchmark.compileNormalizedToFlattenedProg(state)).isNotNull();
        assertThat(benchmark.compileNormalizedToByteMapProg(state)).isNotNull();
        assertThat(benchmark.compileNormalizedToOnePassProg(state)).isNotNull();
        assertThat(benchmark.compileRe2(state)).isNotNull();
        assertThat(benchmark.compileTrinoRegexp(state)).isNotNull();
        assertThat(benchmark.compileJoni(state)).isNotNull();
    }
}
