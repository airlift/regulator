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

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

public class TestDfaGroupZeroForwardCursor
{
    private static final int PRODUCTIVITY_EPOCH_BYTES = 64 * 1024;

    @Test
    public void testNegativeProductivityDecisionIsReusedAndRevalidated()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("[a-z]+-[0-9]+"));
        Dfa.GroupZeroForwardCursor cursor = pattern.createGroupZeroForwardCursor();
        byte[] bytes = new byte[3 * PRODUCTIVITY_EPOCH_BYTES];
        Arrays.fill(bytes, (byte) '.');
        for (int position = 0; position < PRODUCTIVITY_EPOCH_BYTES; position += 3) {
            bytes[position] = 'a';
            bytes[position + 1] = '-';
            bytes[position + 2] = '1';
        }
        int sparseMatchStart = 2 * PRODUCTIVITY_EPOCH_BYTES;
        bytes[sparseMatchStart] = 'a';
        bytes[sparseMatchStart + 1] = '-';
        bytes[sparseMatchStart + 2] = '1';
        Slice input = Slices.wrappedBuffer(bytes);

        // Pinned native RE2 returns each dense three-byte match and the later sparse match.
        assertThat(search(cursor, input, 0)).isEqualTo(3);
        assertThat(search(cursor, input, 3)).isEqualTo(3);
        assertThat(search(cursor, input, 6)).isEqualTo(3);
        assertThat(cursor.byteScanProductivityCheckCount()).isEqualTo(1);

        Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        int scansBeforeRevalidation = scannerCount(dfa);
        assertThat(search(cursor, input, PRODUCTIVITY_EPOCH_BYTES))
                .isEqualTo(sparseMatchStart + 3L - PRODUCTIVITY_EPOCH_BYTES);
        assertThat(cursor.byteScanProductivityCheckCount()).isEqualTo(3);
        assertThat(scannerCount(dfa)).isGreaterThan(scansBeforeRevalidation);
    }

    @Test
    public void testNegativeProductivityDecisionExpiresForShortTail()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("[a-z]+-[0-9]+"));
        Dfa.GroupZeroForwardCursor cursor = pattern.createGroupZeroForwardCursor();
        byte[] bytes = new byte[PRODUCTIVITY_EPOCH_BYTES + 10];
        Arrays.fill(bytes, (byte) '.');
        Arrays.fill(bytes, 0, 16, (byte) 'a');
        bytes[20] = 'a';
        bytes[21] = '-';
        bytes[22] = '1';
        Slice input = Slices.wrappedBuffer(bytes);

        assertThat(search(cursor, input, 0)).isEqualTo(23);
        assertThat(cursor.byteScanProductivityCheckCount()).isEqualTo(1);

        Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        int fallbacksBeforeShortTail = dfa.byteScanFallbackCount();
        assertThat(search(cursor, input, 11)).isEqualTo(12);
        assertThat(cursor.byteScanProductivityCheckCount()).isEqualTo(1);
        assertThat(dfa.byteScanFallbackCount()).isEqualTo(fallbacksBeforeShortTail);
    }

    @Test
    public void testMatcherResetRevalidatesNegativeProductivityDecision()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("[a-z][a-z][a-z]"));
        byte[] denseBytes = new byte[2 * PRODUCTIVITY_EPOCH_BYTES];
        Arrays.fill(denseBytes, (byte) 'a');
        Re2Matcher matcher = pattern.groupZeroMatcher(Slices.wrappedBuffer(denseBytes), null);

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isEqualTo(3);
        int checksBeforeReset = matcher.groupZeroForwardProductivityCheckCountForDiagnostics();
        assertThat(checksBeforeReset).isPositive();

        byte[] sparseBytes = new byte[denseBytes.length];
        Arrays.fill(sparseBytes, (byte) '.');
        Arrays.fill(sparseBytes, PRODUCTIVITY_EPOCH_BYTES, PRODUCTIVITY_EPOCH_BYTES + 3, (byte) 'a');
        matcher.reset(Slices.wrappedBuffer(sparseBytes));

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(PRODUCTIVITY_EPOCH_BYTES);
        assertThat(matcher.end()).isEqualTo(PRODUCTIVITY_EPOCH_BYTES + 3);
        assertThat(matcher.groupZeroForwardProductivityCheckCountForDiagnostics()).isGreaterThan(checksBeforeReset);
    }

    @Test
    public void testExplicitFindPositionRevalidatesNegativeProductivityDecision()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("[a-z][a-z][a-z]"));
        byte[] bytes = new byte[2 * PRODUCTIVITY_EPOCH_BYTES];
        Arrays.fill(bytes, (byte) '.');
        Arrays.fill(bytes, 0, 3, (byte) 'a');
        Arrays.fill(bytes, PRODUCTIVITY_EPOCH_BYTES, bytes.length, (byte) 'a');
        Re2Matcher matcher = pattern.groupZeroMatcher(Slices.wrappedBuffer(bytes), null);

        assertThat(matcher.find(PRODUCTIVITY_EPOCH_BYTES)).isTrue();
        assertThat(matcher.start()).isEqualTo(PRODUCTIVITY_EPOCH_BYTES);
        int checksBeforeReposition = matcher.groupZeroForwardProductivityCheckCountForDiagnostics();
        assertThat(checksBeforeReposition).isPositive();

        assertThat(matcher.find(0)).isTrue();
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isEqualTo(3);
        assertThat(matcher.groupZeroForwardProductivityCheckCountForDiagnostics()).isGreaterThan(checksBeforeReposition);
    }

    @Test
    public void testNegativeProductivityReusePreservesUtf8AndLatin1()
    {
        byte[] utf8Bytes = new byte[2 * PRODUCTIVITY_EPOCH_BYTES];
        Arrays.fill(utf8Bytes, (byte) 'a');
        Re2 utf8Pattern = Re2.compile(Slices.utf8Slice("[ab][ab]"));
        assertRepeatedMatchesReuseDecision(utf8Pattern, Slices.wrappedBuffer(utf8Bytes), 2);

        byte[] latin1Bytes = new byte[2 * PRODUCTIVITY_EPOCH_BYTES];
        Arrays.fill(latin1Bytes, (byte) 'a');
        Re2 latin1Pattern = Re2.compile(Slices.utf8Slice("[ab][ab]"), Re2.Options.latin1());
        assertRepeatedMatchesReuseDecision(latin1Pattern, Slices.wrappedBuffer(latin1Bytes), 2);
    }

    private static void assertRepeatedMatchesReuseDecision(Re2 pattern, Slice input, int matchLength)
    {
        Dfa.GroupZeroForwardCursor cursor = pattern.createGroupZeroForwardCursor();
        assertThat(search(cursor, input, 0)).isEqualTo(matchLength);
        int checksAfterFirstMatch = cursor.byteScanProductivityCheckCount();
        assertThat(checksAfterFirstMatch).isPositive();

        assertThat(search(cursor, input, matchLength)).isEqualTo(matchLength);
        assertThat(search(cursor, input, 2 * matchLength)).isEqualTo(matchLength);
        assertThat(cursor.byteScanProductivityCheckCount()).isEqualTo(checksAfterFirstMatch);
    }

    private static long search(Dfa.GroupZeroForwardCursor cursor, Slice input, int start)
    {
        return Dfa.searchGroupZeroForward(cursor, input, 0, input.length(), start);
    }

    private static int scannerCount(Dfa.DfaInstance dfa)
    {
        return dfa.smallByteSetScanCount() + dfa.contiguousRangeScanCount() + dfa.mixedByteSetScanCount();
    }
}
