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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openjdk.jmh.infra.Blackhole;

import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkCompileCorpus
{
    @ParameterizedTest
    @EnumSource(BenchmarkCompileCorpus.Frontend.class)
    public void testFrontendCompilesItsCorpus(BenchmarkCompileCorpus.Frontend frontend)
    {
        BenchmarkCompileCorpus.CorpusState state = new BenchmarkCompileCorpus.CorpusState();
        state.frontend = frontend;
        state.setup();

        assertThat(state.patterns).hasSize(frontend.corpus().size());
        for (String pattern : frontend.corpus()) {
            Object compiled = frontend.compile(Slices.utf8Slice(pattern));
            assertThat(compiled).as("%s %s", frontend, pattern).isInstanceOf(expectedType(frontend));
        }
        // The measured loop compiles through the same frontend the assertions above checked.
        Blackhole blackhole = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        new BenchmarkCompileCorpus().compileCorpus(state, blackhole);
    }

    @Test
    public void testLikeCorpusCompilesWithBackslashEscape()
    {
        // The LIKE corpus is compiled with a backslash escape, so an escaped underscore is a literal.
        TrinoLikePattern escaped = (TrinoLikePattern) BenchmarkCompileCorpus.Frontend.LIKE.compile(Slices.utf8Slice("user\\_%"));
        assertThat(escaped.planForDiagnostics()).isEqualTo(TrinoLikePattern.Plan.STARTS_WITH);
        assertThat(escaped.matches(Slices.utf8Slice("user_name"))).isTrue();
        assertThat(escaped.matches(Slices.utf8Slice("username"))).isFalse();
    }

    private static Class<?> expectedType(BenchmarkCompileCorpus.Frontend frontend)
    {
        return switch (frontend) {
            case RE2 -> Re2.class;
            case TRINO -> TrinoRegexp.class;
            case JAVA -> JavaRegexp.class;
            case LIKE -> TrinoLikePattern.class;
        };
    }
}
