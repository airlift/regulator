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
import jdk.incubator.vector.ByteVector;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TestFusedPrefixAcceleration
{
    private static final byte[] PREFIX = "Шерлок Холмс".getBytes(StandardCharsets.UTF_8);

    @Test
    public void testExactStrategyCutoff()
    {
        Prog program = configuredProgram(PREFIX, false);

        Prog.PrefixAccelStrategy expectedStrategy = VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_VECTOR : Prog.PrefixAccelStrategy.FUSED_SWAR;
        assertThat(program.prefixAccelStrategy(1_023)).isEqualTo(Prog.PrefixAccelStrategy.FUSED_SWAR);
        assertThat(program.prefixAccelStrategy(1_024)).isEqualTo(expectedStrategy);
        assertThat(program.prefixAccelStrategy(1_025)).isEqualTo(expectedStrategy);

        for (int length : new int[] {1_023, 1_024, 1_025}) {
            assertMatchAt(program, PREFIX, length, 7, 0);
            assertMatchAt(program, PREFIX, length, 7, length - PREFIX.length);
            assertNoMatch(program, length, 7);
        }
    }

    @Test
    public void testSingleByteStrategyCutoff()
    {
        Prog program = configuredProgram(new byte[] {'A'}, false);

        assertThat(program.usesVectorForSingleBytePrefix(1_023)).isFalse();
        assertThat(program.usesVectorForSingleBytePrefix(1_024)).isEqualTo(VectorSupport.isAvailable());
        assertThat(program.usesVectorForSingleBytePrefix(1_025)).isEqualTo(VectorSupport.isAvailable());
    }

    @Test
    public void testFoldCaseStrategyCutoff()
    {
        Prog program = configuredProgram("sherlock".getBytes(StandardCharsets.UTF_8), true);

        assertThat(program.usesVectorForFoldCasePrefix(1_023)).isFalse();
        assertThat(program.usesVectorForFoldCasePrefix(1_024)).isEqualTo(VectorSupport.isAvailable());
        assertThat(program.usesVectorForFoldCasePrefix(1_025)).isEqualTo(VectorSupport.isAvailable());
        // A single folded byte keeps its SWAR byte search.
        assertThat(configuredProgram(new byte[] {'s'}, true).usesVectorForFoldCasePrefix(4_096)).isFalse();
    }

    @Test
    public void testVectorLaneAndTailBoundaries()
    {
        int vectorWidth = ByteVector.SPECIES_PREFERRED.length();
        int length = 1_024 + (2 * vectorWidth) + PREFIX.length;
        for (int position : new int[] {
                vectorWidth - 1,
                vectorWidth,
                vectorWidth + 1,
                (2 * vectorWidth) - 1,
                2 * vectorWidth,
                length - PREFIX.length,
        }) {
            assertMatchAt(configuredProgram(PREFIX, false), PREFIX, length, vectorWidth - 1, position);
        }
    }

    @Test
    public void testDenseFalseCandidatesBeforeFinalMatch()
    {
        Prog program = configuredProgram(PREFIX, false);
        int length = 2_048;
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) 'x');
        int backOffset = PREFIX.length - 1;
        for (int position = 0; position <= length - PREFIX.length; position += 3) {
            data[position] = PREFIX[0];
            data[position + backOffset] = PREFIX[backOffset];
        }
        int matchPosition = length - PREFIX.length;
        System.arraycopy(PREFIX, 0, data, matchPosition, PREFIX.length);

        assertThat(program.prefixAccel(data, 0, data.length)).isEqualTo(matchPosition);
    }

    @Test
    public void testRepeatedEndpointAndLongPrefixBoundaries()
    {
        byte[] repeatedEndpointPrefix = {(byte) 0xC3, 1, 2, (byte) 0xC3};
        assertMatchAt(configuredProgram(repeatedEndpointPrefix, false), repeatedEndpointPrefix, 1_025, 1, 1_021);

        byte[] longPrefix = new byte[1_030];
        Arrays.fill(longPrefix, (byte) 0xA5);
        Prog program = configuredProgram(longPrefix, false);
        assertThat(program.prefixAccel(new byte[1_029], 0, 1_029)).isEqualTo(-1);
        assertThat(program.prefixAccel(longPrefix.clone(), 0, longPrefix.length)).isZero();
    }

    @Test
    public void testCandidateWorkBound()
    {
        // In a run of the prefix's first byte every position is a candidate that matches half the
        // prefix before it fails, so the first candidate passes LiteralSearchKernel's comparison
        // budget, and KMP finds the match after the lead's candidates.
        Prog.PrefixAccelStrategy vectorStrategy = VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_VECTOR : Prog.PrefixAccelStrategy.FUSED_SWAR;
        assertCandidateWorkBound(500, "a".repeat(1 << 20), 0, vectorStrategy, 1);
        assertCandidateWorkBound(100, "a".repeat(822), 0, Prog.PrefixAccelStrategy.FUSED_SWAR, 1);
        // From a window inside the array, KMP searches to the window's end, where the match ends.
        assertCandidateWorkBound(100, "a".repeat(822), 3, Prog.PrefixAccelStrategy.FUSED_SWAR, 1);
        // Failed candidates in the SWAR word loop and the vector loop carry their charges into
        // the scalar tail, where the budget runs out one candidate sooner than a fresh one would.
        // The vector row's 1,039 candidates leave a tail of at least 15 at every vector width, and
        // its run of 13 sits 200 bytes before the tail's run, which supplies their last bytes.
        assertCandidateWorkBound(50, "a".repeat(7) + "x".repeat(9) + "a".repeat(6), 0, Prog.PrefixAccelStrategy.FUSED_SWAR, 17);
        assertCandidateWorkBound(100, "x".repeat(824) + "a".repeat(13) + "x".repeat(187) + "a".repeat(14), 0, vectorStrategy, 1_034);
    }

    @Test
    public void testExpectedCandidateMaskForm()
    {
        // The arm64 CI build, whose runner has SVE with 16-byte vectors, sets this property, so
        // CI fails if VectorSupport there stops selecting the SVE form.
        String expected = System.getProperty("regulator.expectSve128");
        assumeTrue(expected != null, "requires regulator.expectSve128");
        assertThat(VectorSupport.SVE_128)
                .as("SVE_128 on %s with %s preferred byte lanes", System.getProperty("os.arch"), ByteVector.SPECIES_PREFERRED.length())
                .isEqualTo(Boolean.parseBoolean(expected));
    }

    @Test
    public void testFirstByteAndPairScansAgree()
    {
        // Each input runs through both scans of the fused search. They cover a match in the
        // first vector and past the first-byte scan's 8 KiB call limit, 16 failed candidates in a
        // row, failed candidates that spend the comparison budget, and no match before and past
        // the call limit.
        Random random = new Random(42);
        byte[] prefix = "The".getBytes(StandardCharsets.US_ASCII);
        for (int iteration = 0; iteration < 200; iteration++) {
            byte[] data = new byte[1_024 + random.nextInt(20_000)];
            for (int index = 0; index < data.length; index++) {
                data[index] = (byte) "Tthe x".charAt(random.nextInt(6));
            }
            int matches = random.nextInt(4);
            for (int match = 0; match < matches; match++) {
                System.arraycopy(prefix, 0, data, random.nextInt(data.length - prefix.length + 1), prefix.length);
            }
            assertScansAgree(prefix, data, random.nextInt(8));
        }
        byte[] sparse = new byte[20_000];
        Arrays.fill(sparse, (byte) 'x');
        assertScansAgree(prefix, Arrays.copyOf(sparse, 4_096), 0);
        assertScansAgree(prefix, sparse, 0);
        System.arraycopy(prefix, 0, sparse, 12_000, prefix.length);
        assertScansAgree(prefix, sparse, 0);
        assertScansAgree(prefix, sparse, 11_999);
        byte[] failures = sparse.clone();
        for (int position = 100; position < 200; position += 5) {
            failures[position] = 'T';
        }
        assertScansAgree(prefix, failures, 0);
        byte[] atCallLimit = sparse.clone();
        System.arraycopy(prefix, 0, atCallLimit, 8_192, prefix.length);
        assertScansAgree(prefix, atCallLimit, 0);
        assertScansAgree(prefix, atCallLimit, 9_000);
        byte[] afterFailures = sparse.clone();
        Arrays.fill(afterFailures, 0, 16, (byte) 'T');
        System.arraycopy(prefix, 0, afterFailures, 16, prefix.length);
        assertScansAgree(prefix, afterFailures, 0);
        // 8,193 candidate starts, one more than a call scans, with the match at the last.
        byte[] lastStart = Arrays.copyOf(sparse, 8_192 + prefix.length);
        System.arraycopy(prefix, 0, lastStart, 8_192, prefix.length);
        assertScansAgree(prefix, lastStart, 0);

        byte[] budgetPrefix = withA5(("a".repeat(100) + "b" + "a".repeat(100)).getBytes(StandardCharsets.US_ASCII));
        byte[] budgetData = withA5(("x".repeat(824) + "a".repeat(400) + "x".repeat(200)).getBytes(StandardCharsets.US_ASCII));
        System.arraycopy(budgetPrefix, 0, budgetData, 1_000, budgetPrefix.length);
        assertScansAgree(budgetPrefix, budgetData, 0);
        // The candidate at 0 spends the budget, and KMP resumes at the match right after it.
        byte[] longPrefix = new byte[1_001];
        Arrays.fill(longPrefix, (byte) 0xA5);
        longPrefix[500] = 'b';
        byte[] afterBudget = new byte[20_000];
        Arrays.fill(afterBudget, (byte) 'x');
        afterBudget[0] = (byte) 0xA5;
        System.arraycopy(longPrefix, 0, afterBudget, 1, longPrefix.length);
        assertScansAgree(longPrefix, afterBudget, 0);
        // The first-byte scan hands that search to KMP without scoring it, so a match at 8 after
        // the spent candidate leaves a new program's score at zero.
        byte[] sampledAfterBudget = new byte[20_000];
        Arrays.fill(sampledAfterBudget, (byte) 'x');
        Arrays.fill(sampledAfterBudget, 0, 8, (byte) 0xA5);
        System.arraycopy(longPrefix, 0, sampledAfterBudget, 8, longPrefix.length);
        Prog budgetProgram = configuredProgram(longPrefix, false);
        assertThat(budgetProgram.prefixAccelFusedForTesting(sampledAfterBudget, 0, sampledAfterBudget.length, true)).isEqualTo(8);
        assertThat(budgetProgram.prefixScanScoreForTesting()).isZero();
    }

    @Test
    public void testScoreSwitchesBetweenScans()
    {
        // Sparse matches move the search to the first-byte scan, where a counted match adds two
        // less its failed candidates, a search without a match takes a point per whole KiB, and a
        // call that fails 16 candidates moves it back, even when the last failure ends the search.
        assumeTrue(VectorSupport.SIXTEEN_LANES, "requires 16-lane vectors");
        byte[] prefix = "ab".getBytes(StandardCharsets.US_ASCII);
        Prog program = configuredProgram(prefix, false);
        byte[] sparse = new byte[4_096];
        Arrays.fill(sparse, (byte) 'x');
        System.arraycopy(prefix, 0, sparse, 256, prefix.length);
        for (int search = 0; search < 16; search++) {
            assertThat(program.prefixAccel(sparse, 0, sparse.length)).isEqualTo(256);
        }
        assertThat(program.prefixScanScoreForTesting()).isEqualTo(32);
        assertThat(program.prefixAccel(sparse, 0, sparse.length)).isEqualTo(256);
        assertThat(program.prefixScanScoreForTesting()).isEqualTo(34);
        byte[] atEnd = Arrays.copyOf(sparse, 2_048);
        System.arraycopy(prefix, 0, atEnd, atEnd.length - prefix.length, prefix.length);
        // A match at the last start, off the sampled positions, leaves the score as it is.
        assertThat(program.prefixAccel(atEnd, 300, atEnd.length - 300)).isEqualTo(atEnd.length - prefix.length);
        assertThat(program.prefixScanScoreForTesting()).isEqualTo(34);
        byte[] absent = new byte[4_096];
        Arrays.fill(absent, (byte) 'x');
        assertThat(program.prefixAccel(absent, 0, absent.length)).isEqualTo(-1);
        assertThat(program.prefixScanScoreForTesting()).isEqualTo(31);

        byte[] failures = new byte[1_024];
        Arrays.fill(failures, (byte) 'x');
        for (int position = 62; position <= failures.length - prefix.length; position += 64) {
            failures[position] = 'a';
        }
        // 31 less the 16-point penalty falls below 16, so the program returns to the pair scan.
        assertThat(program.prefixAccel(failures, 0, failures.length)).isEqualTo(-1);
        assertThat(program.prefixScanScoreForTesting()).isZero();
    }

    @Test
    public void testIneligiblePrefixesStayOnExistingRoutes()
    {
        assertThat(configuredProgram(PREFIX, true).prefixAccelStrategy(4_096))
                .isEqualTo(Prog.PrefixAccelStrategy.REPEATED_BYTE);
        assertThat(configuredProgram(new byte[] {(byte) 0xD0}, false).prefixAccelStrategy(4_096))
                .isEqualTo(Prog.PrefixAccelStrategy.REPEATED_BYTE);
    }

    private static void assertScansAgree(byte[] prefix, byte[] data, int offset)
    {
        Prog program = configuredProgram(prefix, false);
        int length = data.length - offset;
        int expected = Slices.wrappedBuffer(data).indexOf(Slices.wrappedBuffer(prefix), offset);
        assertThat(program.prefixAccelFusedForTesting(data, offset, length, false)).isEqualTo(expected);
        assertThat(program.prefixAccelFusedForTesting(data, offset, length, true)).isEqualTo(expected);
    }

    /// Asserts where, relative to the window, the scan of a window holding `lead` followed by
    /// `a`×`halfLength` `b` `a`×`halfLength` continues with KMP, and that it finds that prefix
    /// after the lead. The window starts `backingOffset` bytes into its array and is followed by
    /// five more bytes. Each `a` stands for 0xA5, a non-ASCII first byte that selects the fused
    /// scans.
    private static void assertCandidateWorkBound(int halfLength, String lead, int backingOffset, Prog.PrefixAccelStrategy strategy, int kmpHandoff)
    {
        String prefix = "a".repeat(halfLength) + "b" + "a".repeat(halfLength);
        int length = lead.length() + prefix.length();
        byte[] data = withA5(("x".repeat(backingOffset) + lead + prefix + "x".repeat(5)).getBytes(StandardCharsets.US_ASCII));
        Prog program = configuredProgram(withA5(prefix.getBytes(StandardCharsets.US_ASCII)), false);

        assertThat(program.prefixAccelStrategy(length)).isEqualTo(strategy);
        assertThat(program.prefixAccelKmpHandoffForDiagnostics(data, backingOffset, length)).isEqualTo(backingOffset + kmpHandoff);
        assertThat(program.prefixAccel(data, backingOffset, length)).isEqualTo(backingOffset + lead.length());
    }

    private static byte[] withA5(byte[] bytes)
    {
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == 'a') {
                bytes[index] = (byte) 0xA5;
            }
        }
        return bytes;
    }

    private static void assertMatchAt(Prog program, byte[] prefix, int length, int backingOffset, int matchPosition)
    {
        byte[] data = new byte[backingOffset + length + 5];
        Arrays.fill(data, (byte) 'x');
        System.arraycopy(prefix, 0, data, backingOffset + matchPosition, prefix.length);
        assertThat(program.prefixAccel(data, backingOffset, length)).isEqualTo(backingOffset + matchPosition);
    }

    private static void assertNoMatch(Prog program, int length, int backingOffset)
    {
        byte[] data = new byte[backingOffset + length + 5];
        Arrays.fill(data, (byte) 'x');
        assertThat(program.prefixAccel(data, backingOffset, length)).isEqualTo(-1);
    }

    private static Prog configuredProgram(byte[] prefix, boolean foldCase)
    {
        Prog program = new Prog();
        program.configurePrefixAccel(Slices.wrappedBuffer(prefix), foldCase);
        return program;
    }
}
