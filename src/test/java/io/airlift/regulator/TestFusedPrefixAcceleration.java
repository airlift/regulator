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
    public void testIneligiblePrefixesStayOnExistingRoutes()
    {
        assertThat(configuredProgram(PREFIX, true).prefixAccelStrategy(4_096))
                .isEqualTo(Prog.PrefixAccelStrategy.REPEATED_BYTE);
        assertThat(configuredProgram(new byte[] {(byte) 0xD0}, false).prefixAccelStrategy(4_096))
                .isEqualTo(Prog.PrefixAccelStrategy.REPEATED_BYTE);
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
