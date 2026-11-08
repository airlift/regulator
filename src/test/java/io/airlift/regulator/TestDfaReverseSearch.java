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
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

public class TestDfaReverseSearch
{
    @Test
    public void testReverseSearchFindsMatchStart()
    {
        byte[] patternBytes = "ab".getBytes(StandardCharsets.UTF_8);
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer(patternBytes), Regexp.LIKE_PERL | Regexp.LATIN1);

        Prog forward = Compiler.compile(parsed.regexp(), false, 0);
        Prog reverse = Compiler.compile(parsed.regexp(), true, 0);
        assertThat(forward).isNotNull();
        assertThat(reverse).isNotNull();

        byte[] textBytes = "zzabyy".getBytes(StandardCharsets.UTF_8);
        Slice text = Slices.wrappedBuffer(textBytes);

        long forwardResult = Dfa.search(forward, text, false, Prog.MatchKind.FIRST_MATCH, true);
        assertThat(forwardResult >= 0).isTrue();

        int matchEnd = (int) forwardResult;
        Slice prefix = Slices.wrappedBuffer(textBytes, 0, matchEnd);
        long reverseResult = Dfa.search(reverse, text, 0, prefix.length(), true, Prog.MatchKind.LONGEST_MATCH, true);
        assertThat(reverseResult >= 0).isTrue();
        assertThat((int) reverseResult).isEqualTo(2);
    }

    @Test
    public void testReverseMatchingSelfLoopUsesDirectScan()
    {
        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice("(?s).*"), Regexp.LIKE_PERL);
        Prog reverse = Compiler.compile(parsed.regexp(), true, 0);
        Slice text = Slices.utf8Slice("x".repeat(10_000));

        assertThat(Dfa.search(reverse, text, 0, text.length(), true, Prog.MatchKind.LONGEST_MATCH, true))
                .isZero();
        assertThat(reverse.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH).matchingSelfLoopScanCount())
                .isPositive();
    }

    @Test
    public void testReverseMatchingSelfLoopStopsAtNewline()
    {
        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice(".*"), Regexp.LIKE_PERL);
        Prog reverse = Compiler.compile(parsed.regexp(), true, 0);
        Slice text = Slices.utf8Slice("before\nsuffix");

        assertThat(Dfa.search(reverse, text, 0, text.length(), true, Prog.MatchKind.LONGEST_MATCH, true))
                .isEqualTo("before\n".length());
        assertThat(reverse.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH).matchingSelfLoopScanCount())
                .isPositive();
    }

    @Test
    public void testReverseSearchSkipsTrailingSelfLoop()
    {
        // Read backward, the cloud-flare ReDoS pattern's trailing run loops in a non-matching state
        // that leaves only on `=`, a newline, or in UTF-8 a byte outside ASCII, so the search skips
        // to the last of them, once a search has computed the transitions the probe follows. UTF-8
        // takes the high-byte scan and Latin-1 the plain one. A 100-byte search takes the object
        // loop, a 10,000-byte one the absolute-pointer loop.
        for (int flags : new int[] {Regexp.LIKE_PERL, Regexp.LIKE_PERL | Regexp.LATIN1}) {
            ParseResult parsed = RegexpParser.parse(Slices.utf8Slice(".*.*=.*"), flags);
            Prog reverse = Compiler.compile(parsed.regexp(), true, 0);
            Dfa.DfaInstance dfa = reverse.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH);
            for (int runLength : new int[] {100, 10_000}) {
                int absolutePointerSearchCount = dfa.absolutePointerBackwardSearchCount();
                String run = "x".repeat(runLength);
                assertReverseSkip(reverse, Slices.utf8Slice("a\nb=" + run), 0, 2, true);
                // In UTF-8 a character outside ASCII ends the skip, and the search steps through it
                // and the rest; in Latin-1 its bytes are part of the run.
                assertReverseSkip(reverse, Slices.utf8Slice("a=" + run + "é" + run), 0, 0, true);
                // A run that reaches the search start leaves only the end-of-text transition, which
                // reads the byte before the start as context.
                assertReverseSkip(reverse, Slices.utf8Slice("=" + run), 1, Dfa.SEARCH_NO_MATCH, true);
                // A match within the probe leaves the search as it was.
                assertReverseSkip(reverse, Slices.utf8Slice(run + "="), 0, 0, false);
                assertThat(dfa.absolutePointerBackwardSearchCount() > absolutePointerSearchCount)
                        .as("flags=%s runLength=%s", flags, runLength)
                        .isEqualTo(runLength == 10_000);
            }
            // A search of the minimum 64 bytes skips, and a shorter one leaves the search as it was.
            assertReverseSkip(reverse, Slices.utf8Slice("=" + "x".repeat(63)), 0, 0, true);
            assertReverseSkip(reverse, Slices.utf8Slice("=" + "x".repeat(62)), 0, 0, false);
        }

        // Read backward, `=[A-Z]*` loops on capitals and leaves on every other byte, too many for
        // the exit form. The backward scans read only the exit form, so the search steps the run.
        Prog stayForm = Compiler.compile(RegexpParser.parse(Slices.utf8Slice("=[A-Z]*"), Regexp.LIKE_PERL).regexp(), true, 0);
        assertReverseSkip(stayForm, Slices.utf8Slice("=" + "B".repeat(1_000)), 0, 0, false);
    }

    @Test
    public void testReverseMatchingSelfLoopScansProgramWithLoop()
    {
        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice("(?s:.)+"), Regexp.LIKE_PERL);
        Prog reverse = Compiler.compile(parsed.regexp(), true, 0);
        Slice text = Slices.utf8Slice("x".repeat(10_000));

        assertThat(reverse.canMatchEmpty()).isFalse();
        assertThat(reverse.hasLoop()).isTrue();
        assertThat(Dfa.search(reverse, text, 0, text.length(), true, Prog.MatchKind.LONGEST_MATCH, true))
                .isZero();
        assertThat(reverse.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH).matchingSelfLoopScanCount())
                .isPositive();
    }

    @Test
    public void testCountedRepetitionCompilesWithoutLoop()
    {
        // A counted repetition compiles without a loop, so in a longest-match reverse search of a
        // program that cannot match empty, no state matches on a self-loop.
        Prog counted = Compiler.compile(RegexpParser.parse(Slices.utf8Slice("(?s:.){1,12}"), Regexp.LIKE_PERL).regexp(), true, 0);
        Prog repeated = Compiler.compile(RegexpParser.parse(Slices.utf8Slice("(?s:.)+"), Regexp.LIKE_PERL).regexp(), true, 0);

        assertThat(counted.hasLoop()).isFalse();
        assertThat(repeated.hasLoop()).isTrue();
        // A Java count of a body that can match empty stays counted, and an unbounded one loops on
        // its last copy.
        assertThat(JavaRegexp.compile(Slices.utf8Slice("(?:a?b?){2,}c")).forwardProgramForDiagnostics().hasLoop()).isTrue();
    }

    private static void assertReverseSkip(Prog reverse, Slice text, int start, long expected, boolean skips)
    {
        Dfa.DfaInstance dfa = reverse.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH);
        assertThat(Dfa.search(reverse, text, start, text.length(), true, Prog.MatchKind.LONGEST_MATCH, true))
                .as("length=%s", text.length())
                .isEqualTo(expected);
        int skipCount = dfa.reverseSelfLoopExitByteScanCount();
        assertThat(Dfa.search(reverse, text, start, text.length(), true, Prog.MatchKind.LONGEST_MATCH, true))
                .as("length=%s", text.length())
                .isEqualTo(expected);
        assertThat(dfa.reverseSelfLoopExitByteScanCount() > skipCount)
                .as("length=%s", text.length())
                .isEqualTo(skips && VectorSupport.isAvailable());
    }
}
