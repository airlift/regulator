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

import java.util.Random;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDfaCountMatches
{
    @Test
    public void testCountsSuccessiveMatchesInOneSearchSession()
    {
        Prog program = compile("[a-z]+");

        assertThat(Dfa.countMatches(program, utf8Slice("one 22 three 444 five"), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(3);
    }

    @Test
    public void testPreservesContextAtSuccessiveBoundaries()
    {
        Prog program = compile("\\b[a-z]+\\b");

        assertThat(Dfa.countMatches(program, utf8Slice("one_two three four5 five"), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(2);
    }

    @Test
    public void testHonorsMatchKind()
    {
        Prog program = compile("a|aa");

        assertThat(Dfa.countMatches(program, utf8Slice("aa"), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(2);
        assertThat(Dfa.countMatches(program, utf8Slice("aa"), Prog.MatchKind.LONGEST_MATCH))
                .isEqualTo(1);
    }

    @Test
    public void testNonzeroSliceOffset()
    {
        Prog program = compile("[a-z]+");
        byte[] bytes = utf8Slice("--one 22 three--").getBytes();

        assertThat(Dfa.countMatches(program, Slices.wrappedBuffer(bytes, 2, bytes.length - 4), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(2);
    }

    @Test
    public void testShortFixedDistanceSkipUsesCompactContinuation()
    {
        Prog program = compile("[a-z]shing");
        String input = "xxxxxxsxxxxxx".repeat(512) + "ashing";

        assertThat(Dfa.countMatches(program, utf8Slice(input), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(1);
        assertThat(program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).fixedDistanceShortSkipFallbackCount())
                .isGreaterThan(0);
    }

    @Test
    public void testRejectsEmptyMatches()
    {
        Prog program = compile("x*");

        assertThat(Dfa.countMatches(program, utf8Slice("abc"), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(Dfa.COUNT_UNSUPPORTED);
    }

    @Test
    public void testCountDfaThrashingSkipsDfaUntilCacheReset()
    {
        Re2 pattern = Re2.compile(
                utf8Slice("[a-q][^u-z]{80}x"),
                Re2.Options.latin1().setMaxMemory(3L << 20));
        byte[] bytes = stateExplosionInput(16 * 1024);
        var input = Slices.wrappedBuffer(bytes);

        // Pinned native RE2 finds five non-overlapping matches in this deterministic input.
        Re2Matcher matcher = pattern.matcher(input, 0);
        int nativeMatchCount = 0;
        while (matcher.find()) {
            nativeMatchCount++;
        }
        assertThat(nativeMatchCount).isEqualTo(5);

        assertThat(pattern.countMatches(input)).isEqualTo(Dfa.COUNT_UNSUPPORTED);
        Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        int resetCount = dfa.resetCount();
        assertThat(dfa.countSearchBailedWhenSlow()).isTrue();

        Re2Matcher fallbackMatcher = pattern.matcher(input, 0);
        int fallbackCount = 0;
        while (fallbackMatcher.find()) {
            fallbackCount++;
        }
        assertThat(fallbackCount).isEqualTo(nativeMatchCount);
        assertThat(dfa.resetCount()).isEqualTo(resetCount);
        assertThat(dfa.countSearchBailedWhenSlow()).isTrue();

        assertThat(pattern.countMatches(input)).isEqualTo(Dfa.COUNT_UNSUPPORTED);
        int resetCountAfterFallback = dfa.resetCount();
        assertThat(pattern.countMatches(input)).isEqualTo(Dfa.COUNT_UNSUPPORTED);
        assertThat(dfa.resetCount()).isEqualTo(resetCountAfterFallback);

        dfa.resetCacheExternal();
        assertThat(dfa.countSearchBailedWhenSlow()).isFalse();

        int resetCountAfterExternalReset = dfa.resetCount();
        fallbackMatcher.reset(input);
        int countAfterExternalReset = 0;
        while (fallbackMatcher.find()) {
            countAfterExternalReset++;
        }
        assertThat(countAfterExternalReset).isEqualTo(nativeMatchCount);
        assertThat(dfa.resetCount()).isGreaterThan(resetCountAfterExternalReset);
    }

    @Test
    public void testStartStateExhaustionDoesNotBecomeNoMatch()
    {
        Re2 pattern = Re2.compile(
                utf8Slice("[a-q][^u-z]{80}x"),
                Re2.Options.latin1().setMaxMemory(4L << 20));
        var input = Slices.wrappedBuffer(stateExplosionInput(16 * 1024));

        Re2Matcher fillingMatcher = pattern.matcher(input);
        int knownMatchStart = -1;
        int matchCount = 0;
        while (fillingMatcher.find()) {
            if (knownMatchStart < 0) {
                knownMatchStart = fillingMatcher.start();
            }
            matchCount++;
        }
        assertThat(matchCount).isEqualTo(5);

        Re2Matcher reused = pattern.matcher(input).reset(input, knownMatchStart, input.length());
        assertThat(reused.lookingAt()).isTrue();

        Re2 fresh = Re2.compile(
                utf8Slice("[a-q][^u-z]{80}x"),
                Re2.Options.latin1().setMaxMemory(4L << 20));
        assertThat(fresh.matcher(input).reset(input, knownMatchStart, input.length()).lookingAt()).isTrue();
    }

    @Test
    public void testSuccessfulCountDfaThrashingSkipsDfaUntilCacheReset()
    {
        for (Re2.Options options : new Re2.Options[] {Re2.Options.defaults(), Re2.Options.latin1()}) {
            // An outer capture keeps this on the general route rather than the optional
            // capture-free suffix scanner. Counting still requests only group zero.
            Re2 pattern = Re2.compile(
                    utf8Slice("([a-q][^u-z]{23}x)"),
                    options.setMaxMemory(16L << 20));
            var input = Slices.wrappedBuffer(stateExplosionInput(1024 * 1024));

            // Pinned native RE2 finds 529 non-overlapping matches in this deterministic input.
            assertThat(pattern.count(input)).isEqualTo(529);
            assertThat(pattern.countMatches(input)).isEqualTo(Dfa.COUNT_UNSUPPORTED);
            Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics()
                    .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
            assertThat(dfa.resetCount()).isGreaterThanOrEqualTo(2);
            assertThat(dfa.byteScanFallbackCount()).isPositive();
            assertThat(dfa.countSearchBailedWhenSlow()).isTrue();

            Re2Matcher matcher = pattern.matcher(input, 0);
            int fallbackCount = 0;
            while (matcher.find()) {
                fallbackCount++;
            }
            assertThat(fallbackCount).isEqualTo(529);

            int resetCountAfterFallback = dfa.resetCount();
            assertThat(pattern.count(input)).isEqualTo(529);
            assertThat(dfa.resetCount()).isEqualTo(resetCountAfterFallback);
            assertThat(pattern.countMatches(input)).isEqualTo(Dfa.COUNT_UNSUPPORTED);
            assertThat(dfa.resetCount()).isEqualTo(resetCountAfterFallback);

            dfa.resetCacheExternal();
            assertThat(dfa.countSearchBailedWhenSlow()).isFalse();
        }
    }

    @Test
    public void testSuccessfulCountDfaThrashingKeepsProductiveScanner()
    {
        Re2 pattern = Re2.compile(
                utf8Slice("[a-q][^u-z]{13}x"),
                Re2.Options.latin1().setMaxMemory(3L << 19));
        var input = Slices.wrappedBuffer(stateExplosionInput(2 * 1024 * 1024));

        // Pinned native RE2 finds 1,074 non-overlapping matches in this deterministic input.
        assertThat(pattern.countMatches(input)).isEqualTo(1_074);
        Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(dfa.resetCount()).isGreaterThanOrEqualTo(2);
        assertThat(dfa.byteScanFallbackCount()).isZero();
        assertThat(dfa.countSearchBailedWhenSlow()).isFalse();
    }

    private static byte[] stateExplosionInput(int length)
    {
        Random random = new Random(42);
        byte[] alphabet = "abcdefghijklmnopqrst \n,.;0123456789".getBytes(US_ASCII);
        byte[] input = new byte[length];
        for (int index = 0; index < input.length; index++) {
            input[index] = index % 997 == 996 ? (byte) 'x' : alphabet[random.nextInt(alphabet.length)];
        }
        return input;
    }

    private static Prog compile(String pattern)
    {
        ParseResult parsed = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL);
        return Compiler.compile(parsed.regexp(), false, 0);
    }
}
