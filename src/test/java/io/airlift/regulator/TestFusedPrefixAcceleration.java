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
import java.util.List;
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
        // Occurrences at and straddling the edges of 16, 32, and 64-byte blocks, which cover every
        // preferred vector width the scan uses, and at the end of the text, for a prefix that pairs
        // a continuation byte, one that pairs adjacent bytes, and one that pairs its first and last.
        for (byte[] prefix : List.of(PREFIX, utf8("aeaeaeaeae"), utf8("Sherlock Holmes"))) {
            Prog program = configuredProgram(prefix, false);
            int length = 1_024 + (2 * 64) + prefix.length;
            for (int blockSize : new int[] {16, 32, 64}) {
                for (int position : new int[] {
                        blockSize - 1,
                        blockSize,
                        blockSize + 1,
                        blockSize - (prefix.length / 2),
                        (2 * blockSize) - 1,
                        2 * blockSize,
                        length - prefix.length,
                }) {
                    assertMatchAt(program, prefix, length, blockSize - 1, position);
                }
            }
        }
    }

    @Test
    public void testPeriodicPrefixPairsAdjacentBytes()
    {
        // A periodic prefix pairs the first two adjacent bytes that differ.
        assertThat(configuredProgram(utf8("aeaeaeaeae"), false).fusedProbeOffsetsForDiagnostics()).containsExactly(0, 1);
        assertThat(configuredProgram(utf8("abab"), false).fusedProbeOffsetsForDiagnostics()).containsExactly(0, 1);
        assertThat(configuredProgram(utf8("aabaab"), false).fusedProbeOffsetsForDiagnostics()).containsExactly(1, 2);
        // A periodic prefix led by a multibyte character starts at that character's last byte.
        assertThat(configuredProgram(utf8("éé"), false).fusedProbeOffsetsForDiagnostics()).containsExactly(1, 2);
        // Other prefixes keep the pair with the last byte: a period longer than half the prefix,
        // no period, and one repeated byte, which has no adjacent bytes that differ.
        assertThat(configuredProgram(utf8("abcab"), false).fusedProbeOffsetsForDiagnostics()).containsExactly(0, 4);
        assertThat(configuredProgram(utf8("Sherlock Holmes"), false).fusedProbeOffsetsForDiagnostics()).containsExactly(0, 14);
        assertThat(configuredProgram(utf8("aaaa"), false).fusedProbeOffsetsForDiagnostics()).containsExactly(0, 3);
        assertThat(configuredProgram(PREFIX, false).fusedProbeOffsetsForDiagnostics()).containsExactly(1, PREFIX.length - 1);
    }

    @Test
    public void testLongSearchesMatchReference()
    {
        // Text drawn from each prefix's own bytes holds dense candidates that fail at every
        // length. The prefix scan and the literal search behind a leading assertion share the pair
        // choice.
        Random random = new Random(41);
        for (String value : List.of("aeaeaeaeae", "aabaab", "abcab", "aaaa", "ab", "abc", "éé", "Sherlock Holmes", "Шерлок", "Шx")) {
            byte[] prefix = utf8(value);
            Prog program = configuredProgram(prefix, false);
            LiteralSearchKernel literalSearch = new LiteralSearchKernel(Slices.wrappedBuffer(prefix));
            byte[] alphabet = utf8(value + "x");
            for (int iteration = 0; iteration < 200; iteration++) {
                byte[] data = new byte[1_030 + random.nextInt(1_024)];
                for (int index = 0; index < data.length; index++) {
                    data[index] = alphabet[random.nextInt(alphabet.length)];
                }
                if (random.nextBoolean()) {
                    System.arraycopy(prefix, 0, data, random.nextInt(data.length - prefix.length + 1), prefix.length);
                }
                int offset = random.nextInt(4);
                int length = data.length - offset - random.nextInt(4);
                int expected = referenceSearch(data, offset, length, prefix);
                assertThat(program.prefixAccel(data, offset, length)).as("%s iteration %s at offset %s length %s", value, iteration, offset, length).isEqualTo(expected);
                assertThat(Prog.findLiteral(data, offset, length, prefix, literalSearch)).as("%s iteration %s at offset %s length %s", value, iteration, offset, length).isEqualTo(expected);
                if (VectorSupport.isAvailable()) {
                    int[] pair = program.fusedProbeOffsetsForDiagnostics();
                    assertPrefixScans(data, offset, length, prefix, pair[0], pair[1], literalSearch, expected);
                }
            }
        }
    }

    @Test
    public void testPrefixScanFormsAtEveryStart()
    {
        // Each form of the vector scan alone finds the prefix at every start in windows that fill
        // arrays of PREFIX.length to PREFIX.length + 192 bytes, three 64-byte vectors past the
        // prefix, so the match lands in every lane of a full vector and at each offset of the
        // scalar tail, and a scan that reads past the window fails. Around it, every third byte is
        // the primary pair byte and every third the secondary one, placed so that no start has
        // both.
        assumeTrue(VectorSupport.isAvailable(), "requires the vector scanners");
        LiteralSearchKernel literalSearch = new LiteralSearchKernel(Slices.wrappedBuffer(PREFIX));
        int pairOffset = Prog.pairOffset(PREFIX);
        int backOffset = PREFIX.length - 1;
        assertThat((backOffset - pairOffset) % 3).isZero();
        for (int end = PREFIX.length; end <= (3 * 64) + PREFIX.length; end++) {
            byte[] filler = new byte[end];
            for (int index = 0; index < end; index++) {
                filler[index] = switch (index % 3) {
                    case 0 -> PREFIX[pairOffset];
                    case 1 -> PREFIX[backOffset];
                    default -> (byte) 'x';
                };
            }
            assertPrefixScans(filler, 0, end, PREFIX, pairOffset, backOffset, literalSearch, -1);
            for (int start = 0; start <= end - PREFIX.length; start++) {
                byte[] data = filler.clone();
                System.arraycopy(PREFIX, 0, data, start, PREFIX.length);
                assertPrefixScans(data, 0, end, PREFIX, pairOffset, backOffset, literalSearch, start);
            }
        }
    }

    @Test
    public void testDenseFalseCandidatesBeforeFinalMatch()
    {
        Prog program = configuredProgram(PREFIX, false);
        int length = 2_048;
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) 'x');
        // Each start holds the bytes the pair scans test and the lead byte before them, so every
        // one is a candidate that fails at the prefix's third byte. Starts four bytes apart keep
        // each start's last byte clear of the next start's copy.
        int pairOffset = Prog.pairOffset(PREFIX);
        int backOffset = PREFIX.length - 1;
        for (int position = 0; position <= length - PREFIX.length; position += 4) {
            System.arraycopy(PREFIX, 0, data, position, pairOffset + 1);
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
    public void testMultibyteLeadPairsCharacterLastByte()
    {
        assertThat(Prog.pairOffset("Шерлок".getBytes(StandardCharsets.UTF_8))).isEqualTo(1);
        assertThat(Prog.pairOffset("福尔摩斯".getBytes(StandardCharsets.UTF_8))).isEqualTo(2);
        assertThat(Prog.pairOffset("😀x".getBytes(StandardCharsets.UTF_8))).isEqualTo(3);
        assertThat(Prog.pairOffset("Holmes".getBytes(StandardCharsets.UTF_8))).isZero();
        // The character must end before the last byte, its bytes after the lead must be
        // continuation bytes, and the first byte must be a lead byte.
        assertThat(Prog.pairOffset("Ш".getBytes(StandardCharsets.UTF_8))).isZero();
        assertThat(Prog.pairOffset(new byte[] {(byte) 0xC3, 1, 2, (byte) 0xC3})).isZero();
        assertThat(Prog.pairOffset(new byte[] {(byte) 0xA5, (byte) 0xA5, (byte) 0xA5})).isZero();

        // Lead bytes everywhere, and decoys that share the pair's two bytes but not the lead, which
        // only the comparison from each candidate's start rejects. 256 bytes runs the SWAR scan
        // and 4,096 the vector scan when it is available.
        byte[] prefix = "Шерлок".getBytes(StandardCharsets.UTF_8);
        Prog program = configuredProgram(prefix, false);
        for (int length : new int[] {256, 4_096}) {
            byte[] data = new byte[length + 5];
            for (int position = 0; position + 1 < data.length; position += 2) {
                data[position] = (byte) 0xD0;
                data[position + 1] = (byte) 0xB0;
            }
            for (int position = 0; position + prefix.length < length - 64; position += 37) {
                System.arraycopy(prefix, 0, data, position, prefix.length);
                data[position] = (byte) 0xD1;
            }
            int matchPosition = length - prefix.length;
            System.arraycopy(prefix, 0, data, matchPosition, prefix.length);
            assertThat(program.prefixAccel(data, 0, length)).isEqualTo(matchPosition);
            assertThat(program.prefixAccel(data, 0, length - 1)).isEqualTo(-1);
        }

        // The literal ends in its lead byte, so in a run of that byte a first-byte pair would find
        // a failing candidate at every byte and hand off to KMP at 64. The character's last byte,
        // 0xA8, never occurs there, so neither scan hands off.
        Prog leadRun = configuredProgram(new byte[] {(byte) 0xD0, (byte) 0xA8, (byte) 0xD0}, false);
        byte[] leads = new byte[4_096];
        Arrays.fill(leads, (byte) 0xD0);
        for (int length : new int[] {256, 4_096}) {
            assertThat(leadRun.prefixAccelKmpHandoffForDiagnostics(leads, 0, length)).isEqualTo(-1);
        }
    }

    @Test
    public void testLiteralSetScanForAlternativesPastSharedPrefix()
    {
        // The alternatives of rebar's rust-functions row share only "fn ", and Rust source holds
        // it far more often than either alternative. Long searches pair 'f' with the '_' both
        // alternatives hold and compare both literals at each candidate.
        Prog program = compiledProgram("fn is_(\\w+)|fn as_(\\w+)");
        assertThat(program.prefixAccelStrategy(1_023)).isEqualTo(Prog.PrefixAccelStrategy.REPEATED_BYTE);
        assertThat(program.prefixAccelStrategy(1_024))
                .isEqualTo(VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_LITERAL_SET : Prog.PrefixAccelStrategy.REPEATED_BYTE);
        // Eight literals keep the set, as many as it compares at each candidate.
        assertThat(compiledProgram("fn is_|fn as_|fn os_|fn us_|fn es_|fn ys_|fn ws_|fn rs_").prefixAccelStrategy(1_024))
                .isEqualTo(VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_LITERAL_SET : Prog.PrefixAccelStrategy.REPEATED_BYTE);
        // A single literal is its own prefix; alternatives that share no byte past the prefix, a
        // folded byte past it, a prefix as long as the paths the set is read from, nine literals,
        // and an alternative that ends at the prefix keep the prefix's pair scan.
        for (String pattern : List.of(
                "fn is_(\\w+)",
                "fn is_\\w+|fn a\\w+",
                "fn (?i:i)s_\\w+|fn (?i:a)s_\\w+",
                "fn is_a\\w+|fn is_b\\w+",
                "fn is_|fn as_|fn os_|fn us_|fn es_|fn ys_|fn ws_|fn rs_|fn ts_",
                "fn is_|fn ")) {
            assertThat(compiledProgram(pattern).prefixAccelStrategy(4_096))
                    .as(pattern)
                    .isEqualTo(VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_VECTOR : Prog.PrefixAccelStrategy.REPEATED_BYTE);
        }
    }

    @Test
    public void testLiteralSetScanSkipsPrefixOccurrences()
    {
        // Occurrences of the prefix followed by neither alternative, and pair candidates that fail
        // the comparison, come before the match; each scan finds only the match. The prefix's
        // scans stop at the first occurrence.
        assumeTrue(VectorSupport.isAvailable(), "requires the vector scanners");
        Prog program = compiledProgram("fn is_(\\w+)|fn as_(\\w+)");
        String decoys = "fn new() fn xs_ fx is_ fn is fn i_s_ fn ais_ ";
        String match = "fn as_x";
        String text = decoys.repeat(4_096 / decoys.length()) + match;
        byte[] data = ("..." + text + "fn is_").getBytes(StandardCharsets.US_ASCII);
        int matchPosition = 3 + text.length() - match.length();
        assertThat(program.prefixAccel(data, 3, text.length())).isEqualTo(matchPosition);
        assertThat(program.prefixAccel(data, 3, text.length() - 2)).isEqualTo(-1);
        // The match lies in the scalar tail of a 1 KiB search, after its whole vectors.
        assertThat(program.prefixAccel(data, matchPosition - 1_024 + match.length(), 1_024)).isEqualTo(matchPosition);
    }

    @Test
    public void testLiteralSetScanBoundaries()
    {
        // Literals of different lengths, "fn as" and "fn ise", share the 's' past the prefix. A
        // candidate near the end is a match only when a literal ends by the end of the search.
        assumeTrue(VectorSupport.isAvailable(), "requires the vector scanners");
        Prog program = compiledProgram("fn ise|fn as");
        assertThat(program.prefixAccelStrategy(1_024)).isEqualTo(Prog.PrefixAccelStrategy.FUSED_LITERAL_SET);
        byte[] longest = "fn ise".getBytes(StandardCharsets.US_ASCII);
        int vectorWidth = ByteVector.SPECIES_PREFERRED.length();
        int length = 1_024 + (2 * vectorWidth) + longest.length;
        for (String literal : List.of("fn as", "fn ise")) {
            byte[] bytes = literal.getBytes(StandardCharsets.US_ASCII);
            for (int position : new int[] {0, vectorWidth - 1, vectorWidth, vectorWidth + 1, (2 * vectorWidth) - 1, 2 * vectorWidth, length - bytes.length}) {
                assertMatchAt(program, bytes, length, vectorWidth - 1, position);
            }
        }
        // "fn is" is a candidate for both scans but begins neither literal, so a search ending
        // before the "e" that completes "fn ise" finds nothing, and one ending after it does.
        byte[] data = new byte[length + 1];
        Arrays.fill(data, (byte) 'x');
        int start = length + 1 - longest.length;
        System.arraycopy(longest, 0, data, start, longest.length);
        assertThat(program.prefixAccel(data, 0, length)).isEqualTo(-1);
        assertThat(program.prefixAccel(data, 0, length + 1)).isEqualTo(start);
        assertNoMatch(program, length, 3);
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
        byte[] prefixBytes = withA5(prefix.getBytes(StandardCharsets.US_ASCII));
        Prog program = configuredProgram(prefixBytes, false);

        assertThat(program.prefixAccelStrategy(length)).isEqualTo(strategy);
        assertThat(program.prefixAccelKmpHandoffForDiagnostics(data, backingOffset, length)).isEqualTo(backingOffset + kmpHandoff);
        assertThat(program.prefixAccel(data, backingOffset, length)).isEqualTo(backingOffset + lead.length());
        if (strategy == Prog.PrefixAccelStrategy.FUSED_VECTOR) {
            // Both forms of the vector scan hand off to KMP at the same position.
            int[] pair = program.fusedProbeOffsetsForDiagnostics();
            assertThat(VectorPrefixScanner.findPair(data, backingOffset, length, prefixBytes, pair[0], pair[1])).as("findPair").isEqualTo(-2 - (backingOffset + kmpHandoff));
            assertThat(VectorPrefixScanner.findPairMasked(data, backingOffset, length, prefixBytes, pair[0], pair[1])).as("findPairMasked").isEqualTo(-2 - (backingOffset + kmpHandoff));
        }
    }

    // Both forms of the vector prefix scan run on every platform, though find picks the masked
    // form only on x86 with 64-byte vectors. They return the same result, KMP handoffs included,
    // and the search they hand off finds the expected match.
    private static void assertPrefixScans(byte[] data, int offset, int length, byte[] prefix, int primaryOffset, int secondaryOffset, LiteralSearchKernel literalSearch, int expected)
    {
        String description = "%s probing %s and %s at offset %s length %s".formatted(new String(prefix, StandardCharsets.UTF_8), primaryOffset, secondaryOffset, offset, length);
        int found = VectorPrefixScanner.findPair(data, offset, length, prefix, primaryOffset, secondaryOffset);
        assertThat(VectorPrefixScanner.findPairMasked(data, offset, length, prefix, primaryOffset, secondaryOffset)).as("masked " + description).isEqualTo(found);
        assertThat(found < -1 ? literalSearch.findKmp(data, -2 - found, offset + length) : found).as(description).isEqualTo(expected);
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
        assertThat(Prog.findLiteral(data, backingOffset, length, prefix, new LiteralSearchKernel(Slices.wrappedBuffer(prefix)))).isEqualTo(backingOffset + matchPosition);
    }

    private static int referenceSearch(byte[] data, int offset, int length, byte[] prefix)
    {
        int lastStart = offset + length - prefix.length;
        for (int position = offset; position <= lastStart; position++) {
            if (Arrays.equals(data, position, position + prefix.length, prefix, 0, prefix.length)) {
                return position;
            }
        }
        return -1;
    }

    private static byte[] utf8(String value)
    {
        return value.getBytes(StandardCharsets.UTF_8);
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

    private static Prog compiledProgram(String pattern)
    {
        return Compiler.compile(RegexpParser.parse(Slices.utf8Slice(pattern), Regexp.LIKE_PERL).regexp(), false, 0);
    }
}
