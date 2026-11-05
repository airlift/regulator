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
import java.util.Random;

import static io.airlift.regulator.OnePass.CaptureFinalization.DIRECT_REBASE;
import static io.airlift.regulator.OnePass.CaptureFinalization.DIRECT_ZERO_ORIGIN;
import static io.airlift.regulator.OnePass.CaptureFinalization.SCRATCH_COPY;
import static io.airlift.regulator.Re2BenchmarkRunner.compileProg;
import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestOnePassCaptureFinalization
{
    @Test
    public void testCaptureFinalizationModes()
    {
        int[] oneCaptureGroup = new int[4];
        int[] threeCaptureGroups = new int[8];
        assertThat(OnePass.captureFinalization(oneCaptureGroup, Prog.MatchKind.FULL_MATCH, 0)).isEqualTo(DIRECT_REBASE);
        assertThat(OnePass.captureFinalization(threeCaptureGroups, Prog.MatchKind.FULL_MATCH, 0)).isEqualTo(DIRECT_ZERO_ORIGIN);
        assertThat(OnePass.captureFinalization(threeCaptureGroups, Prog.MatchKind.FULL_MATCH, 2)).isEqualTo(DIRECT_REBASE);
        assertThat(OnePass.captureFinalization(threeCaptureGroups, Prog.MatchKind.FIRST_MATCH, 0)).isEqualTo(SCRATCH_COPY);
        assertThat(OnePass.captureFinalization(null, Prog.MatchKind.FULL_MATCH, 0)).isEqualTo(SCRATCH_COPY);
    }

    @Test
    public void testDirectCaptureRebasesNonZeroSearchOrigin()
    {
        Prog program = compileProg("([0-9]+)");
        Slice context = utf8Slice("xx123yy");
        int[] groups = new int[4];
        assertThat(OnePass.search(program, context, 2, 5, true, Prog.MatchKind.FULL_MATCH, groups)).isTrue();
        assertThat(groups).containsExactly(0, 3, 0, 3);
    }

    /// Programs with more capture slots than a base action encodes take the extended loop, whose
    /// full-match form carries no per-byte match bookkeeping. It must agree with the NFA on
    /// matches, misses, unset optional groups, assertions, and a nonzero search origin.
    @Test
    public void testExtendedFullMatchAgreesWithNfa()
    {
        String alphabet = "AZ09;;YNabcdxyz-\t ";
        Random random = new Random(42);
        for (ExtendedCase testCase : List.of(
                new ExtendedCase("([A-Z0-9]+);([^;]+);([^;]*);([0-9]*);([YN]);([^;]*)", List.of("0041;A;Lu;0;Y;", "00A0;NBSP;;12;N;x", "X;;Lu;0;Y;", "0041;A;Lu;0;Y;x;")),
                new ExtendedCase("([0-9]{4})-([0-9]{2})\\t(\\w+)\\t(\\w+)(?:\\t(.+))?", List.of("2022-07\tinfo\ttls", "2022-07\tinfo\ttls\t{x}", "2022-07\t\ttls", "2022-07\tinfo\ttls\njunk")),
                new ExtendedCase("(a)(b)?(c)\\b(-)?(x|y)(z)?", List.of("ac-x", "abc-yz", "acx", "abc dx", "ac-y", "ac-xzz")))) {
            String pattern = testCase.pattern();
            Prog program = compileProg(pattern);
            assertThat(program.isOnePass()).as(pattern).isTrue();
            int slots = 2 * (Re2.parse(utf8Slice(pattern)).capturingGroupCount() + 1);
            assertThat(program.supportsOnePassCaptureSlots(slots)).as(pattern).isTrue();
            assertThat(program.onePassActionCapture()).as(pattern).isNotNull();
            int matches = 0;
            for (int trial = 0; trial < 4000; trial++) {
                String sample = trial % 3 == 0
                        ? testCase.samples().get(random.nextInt(testCase.samples().size()))
                        : randomText(random, alphabet);
                Slice context = utf8Slice("##" + sample);
                int[] expected = new int[slots];
                int[] actual = new int[slots];
                boolean nfaMatched = Nfa.search(program, context, 2, context.length(), true, Prog.MatchKind.FULL_MATCH, expected);
                boolean onePassMatched = OnePass.search(program, context, 2, context.length(), true, Prog.MatchKind.FULL_MATCH, actual);
                assertThat(onePassMatched).as("%s on %s", pattern, sample).isEqualTo(nfaMatched);
                if (nfaMatched) {
                    matches++;
                    assertThat(actual).as("%s on %s", pattern, sample).containsExactly(expected);
                }
            }
            assertThat(matches).as(pattern).isPositive();
        }
    }

    private static String randomText(Random random, String alphabet)
    {
        StringBuilder text = new StringBuilder();
        int length = random.nextInt(20);
        for (int index = 0; index < length; index++) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }

    private record ExtendedCase(String pattern, List<String> samples) {}
}
