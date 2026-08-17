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

import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkRe2SearchExtra
{
    @Test
    public void testSuccessfulScalarNfaControls()
    {
        BenchmarkRe2SearchExtra benchmark = new BenchmarkRe2SearchExtra();
        for (int size : new int[] {64, 32_768}) {
            BenchmarkRe2SearchExtra.SearchState state = new BenchmarkRe2SearchExtra.SearchState();
            state.textSize = size;
            state.setup();

            boolean successHasShortcut = false;
            for (int instruction = 0; instruction < state.progSuccess.size(); instruction++) {
                successHasShortcut |= state.progSuccess.inst(instruction).opcode() == InstOp.ALT_MATCH;
            }
            boolean alternateHasShortcut = false;
            for (int instruction = 0; instruction < state.progAltMatch.size(); instruction++) {
                alternateHasShortcut |= state.progAltMatch.inst(instruction).opcode() == InstOp.ALT_MATCH;
            }
            assertThat(successHasShortcut).isFalse();
            assertThat(alternateHasShortcut).isTrue();
            assertThat(benchmark.searchSuccessRe2FullMatch(state)).isTrue();
            assertThat(benchmark.searchSuccessNfa(state)).isTrue();
            assertThat(benchmark.searchAltMatchRe2FullMatch(state)).isTrue();
            assertThat(benchmark.searchAltMatchNfa(state)).isTrue();
        }
    }

    @Test
    public void testAlternateMatchRequestsExistenceOnly()
    {
        BenchmarkRe2SearchExtra.SearchState state = new BenchmarkRe2SearchExtra.SearchState();
        state.text = Slices.utf8Slice("aaa");
        state.progAltMatch = Re2BenchmarkRunner.compileProg("a+");
        assertThat(new BenchmarkRe2SearchExtra().searchAltMatchDfaBoolean(state)).isTrue();
        assertThat(state.progAltMatch.cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH)).isNotNull();
        assertThat(state.progAltMatch.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
    }

    @Test
    public void testBigFixedBenchmark()
    {
        BenchmarkRe2SearchExtra.BigFixedState state = new BenchmarkRe2SearchExtra.BigFixedState();
        state.textSize = 262_144;
        state.setup();

        BenchmarkRe2SearchExtra benchmark = new BenchmarkRe2SearchExtra();
        // The end anchor promotes this search to longest-match semantics.
        Dfa.DfaInstance dfa = state.progBigFixed.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH);
        assertThat(dfa).isNotNull();
        assertThat(dfa.ok()).isTrue();
        assertThat(benchmark.searchBigFixedDfaBoolean(state))
                .describedAs("DFA cache resets: %s", dfa.resetCount())
                .isTrue();
        assertThat(dfa.resetCount()).isZero();
        assertThat(benchmark.searchBigFixedRe2(state)).isTrue();
    }
}
