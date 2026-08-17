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

import java.util.List;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static org.assertj.core.api.Assertions.assertThat;

public class TestCompilerDominatorElision
{
    @Test
    public void testLargeLiteralAlternationUsesDominatorFreeRoute()
    {
        String utf8Pattern = largeAlternation("ab", "a", "δelta", "δ");

        // Pinned re2_golden reports group zero at 1:3: the earlier "ab" branch wins over "a".
        assertThat(usesDominatorFreeRoute(utf8Slice(utf8Pattern), Re2.Options.defaults())).isTrue();
        Re2 utf8 = Re2.compile(utf8Slice(utf8Pattern));
        assertFirstMatch(utf8, utf8Slice("zabz"), 1, 3);

        String latin1Pattern = largeAlternation("\\xFF", "\\xFFx", "alpha", "alphabet");
        // Pinned re2_golden reports group zero at 1:2 for the Latin-1 byte FF branch.
        assertThat(usesDominatorFreeRoute(utf8Slice(latin1Pattern), Re2.Options.latin1())).isTrue();
        Re2 latin1 = Re2.compile(
                utf8Slice(latin1Pattern),
                Re2.Options.latin1());
        assertFirstMatch(latin1, wrappedBuffer(new byte[] {0, (byte) 0xFF, 'x'}), 1, 2);
    }

    @Test
    public void testSmallAndNullableContinuationPatternsUseUpstreamRoute()
    {
        assertThat(usesDominatorFreeRoute(utf8Slice("ab|a"), Re2.Options.defaults())).isFalse();

        String nullableContinuation = largeAlternation("(?:a|)b", "cab");
        assertThat(usesDominatorFreeRoute(utf8Slice(nullableContinuation), Re2.Options.defaults())).isFalse();

        String folded = largeAlternation("alpha", "beta");
        Re2.Options foldedOptions = Re2.Options.defaults().setCaseSensitive(false);
        assertThat(usesDominatorFreeRoute(utf8Slice(folded), foldedOptions)).isFalse();

        String repeated = largeAlternation("a+", "beta");
        assertThat(usesDominatorFreeRoute(utf8Slice(repeated), Re2.Options.defaults())).isFalse();
    }

    @Test
    public void testDominatorFreeProgramsAreIdenticalToUpstreamFlattening()
    {
        List<String> patterns = List.of(
                "ab|a",
                "a|ab",
                "alpha|alpine|beta|betamax",
                "a|b|c|x|y|z",
                "δelta|δ|βeta",
                "prefix|prefix-long|presto|present");
        for (String pattern : patterns) {
            assertFlatteningIdentical(utf8Slice(pattern), Re2.Options.defaults(), false);
            assertFlatteningIdentical(utf8Slice(pattern), Re2.Options.defaults(), true);
        }
        assertFlatteningIdentical(utf8Slice("\\xFFx|\\xFF|alpha"), Re2.Options.latin1(), false);
        assertFlatteningIdentical(utf8Slice("\\xFFx|\\xFF|alpha"), Re2.Options.latin1(), true);

        String largeUtf8Pattern = largeAlternation("ab", "a", "δelta", "δ");
        assertFlatteningIdentical(utf8Slice(largeUtf8Pattern), Re2.Options.defaults(), false);
        assertFlatteningIdentical(utf8Slice(largeUtf8Pattern), Re2.Options.defaults(), true);

        String largeLatin1Pattern = largeAlternation("\\xFF", "\\xFFx", "alpha", "alphabet");
        assertFlatteningIdentical(utf8Slice(largeLatin1Pattern), Re2.Options.latin1(), false);
        assertFlatteningIdentical(utf8Slice(largeLatin1Pattern), Re2.Options.latin1(), true);
    }

    private static String largeAlternation(String... leadingAlternatives)
    {
        StringBuilder pattern = new StringBuilder();
        for (String alternative : leadingAlternatives) {
            if (!pattern.isEmpty()) {
                pattern.append('|');
            }
            pattern.append(alternative);
        }
        for (int index = 0; index < 4096; index++) {
            pattern.append('|').append("padding").append(index);
        }
        return pattern.toString();
    }

    private static void assertFirstMatch(Re2 pattern, Slice input, int expectedStart, int expectedEnd)
    {
        Re2Matcher matcher = pattern.matcher(input);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(expectedStart);
        assertThat(matcher.end()).isEqualTo(expectedEnd);
    }

    private static void assertFlatteningIdentical(Slice pattern, Re2.Options options, boolean reversed)
    {
        Regexp parsed = RegexpParser.parse(pattern, options.parseFlags()).regexp();
        Regexp normalized = Simplifier.simplify(parsed);
        long forwardMemory = options.maxMemory() - (options.maxMemory() / 3);
        Prog control = Compiler.compileNormalizedForBenchmark(
                normalized,
                reversed,
                forwardMemory,
                Compiler.CompileStage.OPTIMIZED);
        Prog candidate = Compiler.compileNormalizedForBenchmark(
                normalized,
                reversed,
                forwardMemory,
                Compiler.CompileStage.OPTIMIZED);

        Prog.FlattenStructure controlStructure = control.flattenForTesting();
        Prog.FlattenStructure candidateStructure = candidate.flattenForTesting(true);
        control.computeByteMap();
        candidate.computeByteMap();

        assertThat(controlStructure.finalRootCount())
                .as(pattern.toStringUtf8())
                .isEqualTo(controlStructure.successorRootCount());
        assertThat(candidateStructure.finalRootCount())
                .as(pattern.toStringUtf8())
                .isEqualTo(controlStructure.finalRootCount());
        assertThat(candidate.dump()).as(pattern.toStringUtf8()).isEqualTo(control.dump());
        assertThat(candidate.dumpUnanchored()).as(pattern.toStringUtf8()).isEqualTo(control.dumpUnanchored());
        assertThat(candidate.dumpByteMap()).as(pattern.toStringUtf8()).isEqualTo(control.dumpByteMap());
        assertThat(candidate.getInstCount()).containsExactly(control.getInstCount());
        assertThat(candidate.listCount()).isEqualTo(control.listCount());
        assertThat(Compiler.estimatedProgramMemory(candidate)).isEqualTo(Compiler.estimatedProgramMemory(control));
    }

    private static boolean usesDominatorFreeRoute(Slice pattern, Re2.Options options)
    {
        Regexp parsed = RegexpParser.parse(pattern, options.parseFlags()).regexp();
        Regexp normalized = Simplifier.simplify(parsed);
        long forwardMemory = options.maxMemory() - (options.maxMemory() / 3);
        Prog optimized = Compiler.compileNormalizedForBenchmark(
                normalized,
                false,
                forwardMemory,
                Compiler.CompileStage.OPTIMIZED);
        return Compiler.shouldSkipDominatorPass(normalized, false, optimized.size());
    }
}
