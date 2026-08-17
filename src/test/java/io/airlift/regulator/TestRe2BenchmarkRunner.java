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

import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestRe2BenchmarkRunner
{
    @Test
    public void testBooleanDfaDemandAndFailure()
    {
        Prog prog = Re2BenchmarkRunner.compileProg("a+");
        assertThat(Re2BenchmarkRunner.searchDfaBoolean(prog, Slices.utf8Slice("aaa"), false)).isTrue();
        // Boolean search selects earliest-match execution, not boundary recovery.
        assertThat(prog.cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH)).isNotNull();
        assertThat(prog.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        assertThat(Re2BenchmarkRunner.searchDfaBoolean(prog, Slices.utf8Slice("bbb"), false)).isFalse();
        assertThat(Re2BenchmarkRunner.dfaBooleanResult(0)).isTrue();
        assertThat(Re2BenchmarkRunner.dfaBooleanResult(Dfa.SEARCH_NO_MATCH)).isFalse();
        assertThatThrownBy(() -> Re2BenchmarkRunner.dfaBooleanResult(Dfa.SEARCH_FAILED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testRandomTextCorpus()
    {
        byte[] expected = HexFormat.of().parseHex("41412925652071207f2e64206623647b25312f5c703922597a2069376a202020");
        assertThat(Re2BenchmarkRunner.randomText(expected.length)).containsExactly(expected);
        assertThat(Re2BenchmarkRunner.randomText(8)).containsExactly(Arrays.copyOf(expected, 8));
    }

    @Test
    public void testBigFixedCachedProgramBudget()
    {
        int textSize = 262_144;
        String pattern = "^" + "x".repeat(textSize / 2) + ".*$";

        assertThat(Re2BenchmarkRunner.compileProg(pattern).size()).isGreaterThan(100_000);
    }
}
