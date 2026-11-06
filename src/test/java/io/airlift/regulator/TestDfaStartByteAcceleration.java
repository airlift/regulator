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
import jdk.incubator.vector.ByteVector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Dfa.SEARCH_NO_MATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TestDfaStartByteAcceleration
{
    @Test
    public void testLeadingByteClassSelectsStartByteAcceleration()
    {
        Prog program = compile("([a-z]+)-([0-9]+)");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] text = "...z...".getBytes(StandardCharsets.UTF_8);

        assertThat(dfa.canStartByteAcceleration()).isTrue();
        assertThat(dfa.findStartByteCandidate(text, 0, text.length)).isEqualTo(3);
        assertThat(dfa.findStartByteCandidate(text, 4, text.length - 4)).isEqualTo(-1);
        assertThat(Dfa.canUseStartByteAcceleration(dfa, false, true, 0)).isTrue();
        assertThat(Dfa.canUseStartByteAcceleration(dfa, true, true, 0)).isFalse();
        assertThat(Dfa.canUseStartByteAcceleration(dfa, false, false, 0)).isFalse();
        assertThat(Dfa.canUseStartByteAcceleration(dfa, false, true, 1)).isFalse();
    }

    @Test
    public void testAnchoredProgramRejectsImpossibleFirstByte()
    {
        Prog anchored = compile("^(?i)[urb]*['\"](.*)['\"]$");
        byte[] startBytes = anchored.anchoredStartBytes();
        assertThat(startBytes).isNotNull();
        for (char value : "uUrRbB'\"".toCharArray()) {
            assertThat(startBytes[value]).as("byte %s", value).isNotZero();
        }
        assertThat(startBytes[' ']).isZero();
        assertThat(startBytes['x']).isZero();

        // Nullable, assertion-led, and unanchored programs keep no table.
        assertThat(compile("^a*").anchoredStartBytes()).isNull();
        assertThat(compile("^\\bx").anchoredStartBytes()).isNull();
        assertThat(compile("ab").anchoredStartBytes()).isNull();

        Re2 pattern = Re2.compile(Slices.utf8Slice("^(?i)[urb]*['\"](?P<raw>.*)['\"]$"));
        RegexpMatcher matcher = pattern.matcher(Slices.utf8Slice("    x = 'a'"));
        assertThat(matcher.find()).isFalse();
        // The rejection comes before any submatch engine sets up.
        assertThat(matcher.onePassWorkspaceInitializedForDiagnostics()).isFalse();
        assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).isFalse();
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isFalse();
        matcher.reset(Slices.utf8Slice("rb'a'"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group("raw").toStringUtf8()).isEqualTo("a");
        matcher.reset(Slices.utf8Slice("\"b\""));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("b");
    }

    @Test
    public void testOptionalLeadingByteAndUtf8Candidates()
    {
        Prog optional = compile("(?:[a-z])?[0-9]");
        Dfa.DfaInstance optionalDfa = optional.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] optionalText = "...7".getBytes(StandardCharsets.UTF_8);
        assertThat(optionalDfa.canStartByteAcceleration()).isTrue();
        assertThat(optionalDfa.findStartByteCandidate(optionalText, 0, optionalText.length)).isEqualTo(3);

        Prog utf8 = compile("[éê]x");
        Dfa.DfaInstance utf8Dfa = utf8.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] utf8Text = "...êx".getBytes(StandardCharsets.UTF_8);
        assertThat(utf8Dfa.canStartByteAcceleration()).isTrue();
        assertThat(utf8Dfa.findStartByteCandidate(utf8Text, 0, utf8Text.length)).isEqualTo(3);

        Dfa.DfaInstance foldedDfa = firstMatchDfa("(?i)[a-z]+-[0-9]+");
        byte[] foldedText = "...Z".getBytes(StandardCharsets.UTF_8);
        assertThat(foldedDfa.canStartByteAcceleration()).isTrue();
        assertThat(foldedDfa.findStartByteCandidate(foldedText, 0, foldedText.length)).isEqualTo(3);
    }

    @Test
    public void testTwoAndThreeStartBytesUseSmallSetScanner()
    {
        for (String pattern : List.of("[xy][0-9]{4}", "[xyz][0-9]{4}")) {
            Prog program = compile(pattern);
            Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
            byte[] text = new byte[8 * 1024];
            Arrays.fill(text, (byte) 'a');
            byte[] match = "x1234".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(match, 0, text, text.length - match.length, match.length);

            assertThat(Dfa.search(program, Slices.wrappedBuffer(text), false, Prog.MatchKind.FIRST_MATCH, true))
                    .isEqualTo(text.length);
            assertThat(dfa.smallByteSetScanCount() > 0).as(pattern).isEqualTo(VectorSupport.isAvailable());
        }
    }

    @Test
    public void testSmallStartByteSetsMatchScalarReference()
    {
        Random random = new Random(91);
        // The last two sets hold Ã, whose lead byte C3 differs in sign from ASCII bytes, so the
        // two- and three-byte vector scans must take the unsigned minimum of their XORs.
        for (String candidates : List.of("xy", "xyz", "x\u00C3", "xy\u00C3")) {
            String pattern = "[" + candidates + "][0-9]{4}";
            Dfa.DfaInstance dfa = firstMatchDfa(pattern);
            for (int iteration = 0; iteration < 1_000; iteration++) {
                int offset = random.nextInt(8);
                int length = random.nextInt(4_097);
                byte[] data = new byte[offset + length + random.nextInt(8)];
                random.nextBytes(data);

                assertThat(dfa.findStartByteCandidate(data, offset, length))
                        .as("%s offset=%s length=%s", pattern, offset, length)
                        .isEqualTo(findCandidate(data, offset, length, candidates));
            }
        }
    }

    @Test
    public void testContiguousStartByteRangeUsesRangeScanner()
    {
        Prog program = compile("([a-z]+)-([0-9]+)");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] text = new byte[8 * 1024];
        Arrays.fill(text, (byte) '.');
        byte[] match = "abc-123".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(match, 0, text, text.length - match.length, match.length);

        assertThat(Dfa.search(program, Slices.wrappedBuffer(text), false, Prog.MatchKind.FIRST_MATCH, true))
                .isEqualTo(text.length);
        assertThat(dfa.contiguousRangeScanCount() > 0).isEqualTo(VectorSupport.isAvailable());
    }

    @Test
    public void testNoncontiguousStartByteSetDoesNotUseRangeScanner()
    {
        Prog program = compile("[aceg][0-9]{4}");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] text = new byte[8 * 1024];
        Arrays.fill(text, (byte) '.');
        byte[] match = "a1234".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(match, 0, text, text.length - match.length, match.length);

        assertThat(Dfa.search(program, Slices.wrappedBuffer(text), false, Prog.MatchKind.FIRST_MATCH, true))
                .isEqualTo(text.length);
        assertThat(dfa.contiguousRangeScanCount()).isZero();
    }

    @Test
    public void testMixedStartByteSetUsesMixedSetScanner()
    {
        Prog program = compile("(?:[A-Z_a-z]+)-([0-9]+)");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] text = new byte[8 * 1024];
        Arrays.fill(text, (byte) '.');
        byte[] match = "abc-123".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(match, 0, text, text.length - match.length, match.length);

        assertThat(Dfa.search(program, Slices.wrappedBuffer(text), false, Prog.MatchKind.FIRST_MATCH, true))
                .isEqualTo(text.length);
        assertThat(dfa.mixedByteSetScanCount() > 0).isEqualTo(VectorSupport.isAvailable());
    }

    @Test
    public void testCorpusMixedStartShapesUseMixedSetScanner()
    {
        for (MixedSetCase testCase : List.of(
                new MixedSetCase("(\\p{Sm}+)-([0-9]+)", "+-1234"),
                new MixedSetCase("(\\p{Lu}+)-([0-9]+)", "A-1234"),
                new MixedSetCase("(\\w+)-([0-9]+)", "A-1"),
                new MixedSetCase("(\\p{Greek}+)-([0-9]+)", "Α-1"))) {
            Prog program = compile(testCase.pattern());
            Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
            byte[] text = new byte[8 * 1024];
            Arrays.fill(text, (byte) '.');
            byte[] match = testCase.match().getBytes(StandardCharsets.UTF_8);
            System.arraycopy(match, 0, text, text.length - match.length, match.length);

            assertThat(Dfa.search(program, Slices.wrappedBuffer(text), false, Prog.MatchKind.FIRST_MATCH, true))
                    .as(testCase.pattern())
                    .isEqualTo(text.length);
            assertThat(dfa.mixedByteSetScanCount() > 0)
                    .as(testCase.pattern())
                    .isEqualTo(VectorSupport.isAvailable());
        }
    }

    @Test
    public void testContiguousRangeVectorScannerMatchesScalarReference()
    {
        Random random = new Random(93);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            int offset = random.nextInt(8);
            int length = random.nextInt(4_097);
            byte[] data = new byte[offset + length + random.nextInt(8)];
            random.nextBytes(data);
            int rangeLength = 4 + random.nextInt(61);
            int lowerBound = random.nextInt(257 - rangeLength);
            int upperBound = lowerBound + rangeLength - 1;

            int expected = -1;
            for (int position = offset; position < offset + length; position++) {
                int value = data[position] & 0xFF;
                if (value >= lowerBound && value <= upperBound) {
                    expected = position;
                    break;
                }
            }

            assertThat(VectorByteSetScanner.findRange(data, offset, length, lowerBound, upperBound))
                    .as("offset=%s length=%s range=%s-%s", offset, length, lowerBound, upperBound)
                    .isEqualTo(expected);
        }
    }

    @Test
    public void testMixedSetVectorScannerMatchesScalarReference()
    {
        assumeTrue(VectorSupport.isAvailable(), "requires the vector scanners");
        Random random = new Random(94);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            byte[] candidates = new byte[256];
            int highNibbleCount = 1 + random.nextInt(8);
            int remainingHighNibbles = highNibbleCount;
            boolean[] selectedHighNibbles = new boolean[16];
            while (remainingHighNibbles > 0) {
                int highNibble = random.nextInt(16);
                if (!selectedHighNibbles[highNibble]) {
                    selectedHighNibbles[highNibble] = true;
                    remainingHighNibbles--;
                }
            }
            int remainingCandidates = 4 + random.nextInt(Math.min(64, highNibbleCount * 16) - 3);
            while (remainingCandidates > 0) {
                int value = random.nextInt(256);
                if (selectedHighNibbles[value >>> 4] && candidates[value] == 0) {
                    candidates[value] = 1;
                    remainingCandidates--;
                }
            }

            byte[] tables = VectorByteSetScanner.buildNibbleTables(candidates);
            int offset = random.nextInt(8);
            int length = random.nextInt(4_097);
            byte[] data = new byte[offset + length + random.nextInt(8)];
            random.nextBytes(data);

            assertThat(VectorByteSetScanner.findNibbleTable(data, offset, length, tables))
                    .as("offset=%s length=%s", offset, length)
                    .isEqualTo(findCandidate(data, offset, length, candidates));
        }
    }

    @Test
    public void testMixedSetScannerRejectsMoreThanEightHighNibbles()
    {
        byte[] candidates = new byte[256];
        for (int highNibble = 0; highNibble < 9; highNibble++) {
            candidates[highNibble << 4] = 1;
        }
        assertThat(VectorByteSetScanner.buildNibbleTables(candidates)).isNull();
    }

    @Test
    public void testDenseContiguousStartByteRangeFallsBackToCompactSearch()
    {
        Prog program = compile("([a-z]+)-([0-9]+)");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] text = new byte[64 * 1024];
        Arrays.fill(text, (byte) 'a');

        assertThat(Dfa.search(program, Slices.wrappedBuffer(text), false, Prog.MatchKind.FIRST_MATCH, true))
                .isEqualTo(SEARCH_NO_MATCH);
        assertThat(dfa.contiguousRangeScanCount()).isZero();
        assertThat(dfa.byteScanFallbackCount()).isPositive();
    }

    @Test
    public void testDenseContiguousStartByteRangeUsesScalarCandidateTable()
    {
        Prog program = compile("([a-z]+)-([0-9]+)");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] text = new byte[8 * 1024];
        Arrays.fill(text, (byte) '.');
        for (int position = 0; position < text.length; position += 13) {
            text[position] = 'a';
        }

        assertThat(Dfa.search(program, Slices.wrappedBuffer(text), false, Prog.MatchKind.FIRST_MATCH, true))
                .isEqualTo(SEARCH_NO_MATCH);
        assertThat(dfa.contiguousRangeScanCount()).isZero();
        assertThat(dfa.byteScanFallbackCount()).isZero();
    }

    @Test
    public void testSingleByteVectorScannerMatchesScalarReference()
    {
        Random random = new Random(92);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            int offset = random.nextInt(8);
            int length = random.nextInt(4_097);
            byte[] data = new byte[offset + length + random.nextInt(8)];
            random.nextBytes(data);
            byte candidate = (byte) random.nextInt(256);

            int expected = -1;
            for (int position = offset; position < offset + length; position++) {
                if (data[position] == candidate) {
                    expected = position;
                    break;
                }
            }

            assertThat(VectorByteSetScanner.find(data, offset, length, candidate, (byte) 0, (byte) 0, 1))
                    .as("offset=%s length=%s candidate=%s", offset, length, candidate & 0xFF)
                    .isEqualTo(expected);
        }
    }

    @Test
    public void testHighBitOrCandidateVectorScannerMatchesScalarReference()
    {
        Random random = new Random(95);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            int offset = random.nextInt(8);
            int length = random.nextInt(4_097);
            byte[] data = new byte[offset + length + random.nextInt(8)];
            for (int index = 0; index < data.length; index++) {
                data[index] = (byte) (random.nextInt(64) == 0 ? random.nextInt(256) : random.nextInt(128));
            }
            int candidateCount = random.nextInt(4);
            byte first = (byte) random.nextInt(128);
            byte second = (byte) random.nextInt(128);
            byte third = (byte) random.nextInt(128);
            assertHighBitOrMatchesScalarReference(data, offset, length, first, second, third, candidateCount);
        }

        // Long spans without a hit cover every full vector, and a single hit in the last byte is
        // past SPECIES.loopBound(length): the odd hit lengths leave a scalar tail for every supported
        // vector width.
        byte[] hits = {(byte) 0x80, (byte) 0xFF, 'x', 'y', 'z'};
        for (int candidateCount = 0; candidateCount <= 3; candidateCount++) {
            for (int length : new int[] {4_096, 4_097}) {
                for (int offset : new int[] {0, 3}) {
                    byte[] data = new byte[offset + length + 5];
                    Arrays.fill(data, (byte) 'a');
                    Arrays.fill(data, offset + length, data.length, (byte) 'x');
                    assertThat(VectorByteSetScanner.findHighBitOr(data, offset, length, (byte) 'x', (byte) 'y', (byte) 'z', candidateCount))
                            .as("offset=%s length=%s candidateCount=%s", offset, length, candidateCount)
                            .isEqualTo(-1);
                }
            }
            for (int length : new int[] {1, 15, 17, 33, 4_097, 4_111, 4_159}) {
                for (byte hit : hits) {
                    byte[] data = new byte[length + 3];
                    Arrays.fill(data, (byte) 'a');
                    data[2 + length - 1] = hit;
                    assertHighBitOrMatchesScalarReference(data, 2, length, (byte) 'x', (byte) 'y', (byte) 'z', candidateCount);
                }
            }
        }
    }

    private static void assertHighBitOrMatchesScalarReference(
            byte[] data,
            int offset,
            int length,
            byte first,
            byte second,
            byte third,
            int candidateCount)
    {
        int expected = -1;
        for (int position = offset; position < offset + length; position++) {
            byte value = data[position];
            if (value < 0 ||
                    (candidateCount >= 1 && value == first) ||
                    (candidateCount >= 2 && value == second) ||
                    (candidateCount >= 3 && value == third)) {
                expected = position;
                break;
            }
        }

        assertThat(VectorByteSetScanner.findHighBitOr(data, offset, length, first, second, third, candidateCount))
                .as("offset=%s length=%s candidateCount=%s", offset, length, candidateCount)
                .isEqualTo(expected);
    }

    @Test
    public void testBackwardByteSetVectorScannersMatchScalarReference()
    {
        // The reverse self-loop skip passes findLast any exit bytes and findLastHighBitOr only
        // ASCII ones, so the random candidates are drawn the same way.
        Random random = new Random(96);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            int offset = random.nextInt(8);
            int length = random.nextInt(4_097);
            byte[] data = new byte[offset + length + random.nextInt(8)];
            for (int index = 0; index < data.length; index++) {
                data[index] = (byte) (random.nextInt(64) == 0 ? random.nextInt(256) : random.nextInt(128));
            }
            int candidateCount = random.nextInt(4);
            byte first = (byte) random.nextInt(256);
            byte second = (byte) random.nextInt(256);
            byte third = (byte) random.nextInt(256);
            assertFindLastMatchesScalarReference(data, offset, length, first, second, third, candidateCount);
            byte asciiFirst = (byte) random.nextInt(128);
            byte asciiSecond = (byte) random.nextInt(128);
            byte asciiThird = (byte) random.nextInt(128);
            assertFindLastHighBitOrMatchesScalarReference(data, offset, length, asciiFirst, asciiSecond, asciiThird, candidateCount);
        }

        byte[] hits = {(byte) 0x80, (byte) 0xFF, 'x', 'y', 'z'};
        for (int candidateCount = 0; candidateCount <= 3; candidateCount++) {
            // Long spans without a hit cover every full vector. Hits just outside the range must not
            // be found.
            for (int length : new int[] {4_096, 4_097}) {
                for (int offset : new int[] {0, 3}) {
                    byte[] data = new byte[offset + length + 5];
                    Arrays.fill(data, (byte) 'x');
                    Arrays.fill(data, offset, offset + length, (byte) 'a');
                    assertFindLastMatchesScalarReference(data, offset, length, (byte) 'x', (byte) 'y', (byte) 'z', candidateCount);
                    assertFindLastHighBitOrMatchesScalarReference(data, offset, length, (byte) 'x', (byte) 'y', (byte) 'z', candidateCount);
                }
            }
            // A single hit in the first byte of an odd length lies before the last
            // SPECIES.loopBound(length) bytes, so it falls to the scalar head at every supported
            // vector width.
            for (int length : new int[] {1, 15, 17, 33, 4_097, 4_111, 4_159}) {
                for (byte hit : hits) {
                    byte[] data = new byte[length + 3];
                    Arrays.fill(data, (byte) 'a');
                    data[2] = hit;
                    assertFindLastMatchesScalarReference(data, 2, length, (byte) 'x', (byte) 'y', (byte) 'z', candidateCount);
                    assertFindLastHighBitOrMatchesScalarReference(data, 2, length, (byte) 'x', (byte) 'y', (byte) 'z', candidateCount);
                }
            }
        }

        // Ã's lead byte C3 differs in sign from ASCII bytes, so the two- and three-byte vector scans
        // must take the unsigned minimum of their XORs. Each hit lies inside a full vector.
        for (byte[] candidates : new byte[][] {{'x', (byte) 0xC3, 'z'}, {'x', 'y', (byte) 0xC3}}) {
            for (int candidateCount = 2; candidateCount <= 3; candidateCount++) {
                for (byte hit : new byte[] {'x', (byte) 0xC3}) {
                    for (int hitPosition : new int[] {100, 4_095}) {
                        byte[] data = new byte[4_096];
                        Arrays.fill(data, (byte) 'a');
                        data[hitPosition] = hit;
                        assertFindLastMatchesScalarReference(data, 0, data.length, candidates[0], candidates[1], candidates[2], candidateCount);
                    }
                }
            }
        }
    }

    private static void assertFindLastMatchesScalarReference(
            byte[] data,
            int offset,
            int length,
            byte first,
            byte second,
            byte third,
            int candidateCount)
    {
        // findLast takes one to three candidates.
        if (candidateCount == 0) {
            return;
        }
        int expected = -1;
        for (int position = offset + length - 1; position >= offset; position--) {
            byte value = data[position];
            if (value == first ||
                    (candidateCount >= 2 && value == second) ||
                    (candidateCount >= 3 && value == third)) {
                expected = position;
                break;
            }
        }
        assertThat(VectorByteSetScanner.findLast(data, offset, length, first, second, third, candidateCount))
                .as("offset=%s length=%s candidateCount=%s", offset, length, candidateCount)
                .isEqualTo(expected);
    }

    private static void assertFindLastHighBitOrMatchesScalarReference(
            byte[] data,
            int offset,
            int length,
            byte first,
            byte second,
            byte third,
            int candidateCount)
    {
        int expected = -1;
        for (int position = offset + length - 1; position >= offset; position--) {
            byte value = data[position];
            if (value < 0 ||
                    (candidateCount >= 1 && value == first) ||
                    (candidateCount >= 2 && value == second) ||
                    (candidateCount >= 3 && value == third)) {
                expected = position;
                break;
            }
        }
        assertThat(VectorByteSetScanner.findLastHighBitOr(data, offset, length, first, second, third, candidateCount))
                .as("offset=%s length=%s candidateCount=%s", offset, length, candidateCount)
                .isEqualTo(expected);
    }

    @Test
    public void testDenseSmallStartByteSetUsesWidthAppropriateScanner()
    {
        Prog program = compile("[xy][0-9]{4}");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] text = new byte[32 * 1024];
        Arrays.fill(text, (byte) 'a');
        for (int position = 0; position < text.length; position += 13) {
            text[position] = 'x';
        }

        assertThat(Dfa.search(program, Slices.wrappedBuffer(text), false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(-1);
        if (!VectorSupport.isAvailable() || VectorByteSetScanner.shouldSampleCandidateDensity()) {
            assertThat(dfa.smallByteSetScanCount()).isZero();
        }
        else {
            assertThat(dfa.smallByteSetScanCount()).isPositive();
        }
        assertThat(dfa.byteScanFallbackCount()).isZero();
    }

    @Test
    public void testDenseUtf8CandidatesFallBackToCompactSearch()
    {
        // A one-letter literal followed by a broad class leaves too few narrow positions for a
        // start literal filter.
        String pattern = "[ШДИип][а-я]+[0-9]";
        Prog program = compile(pattern);
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        Slice shortInput = Slices.utf8Slice("а".repeat(16 * 1024));
        Slice longInput = Slices.utf8Slice("а".repeat(64 * 1024));

        assertThat(dfa.canStartByteAcceleration()).isTrue();
        assertThat(dfa.startLiteralFilter()).isNull();
        assertThat(dfa.canFixedDistanceByteAcceleration()).isFalse();
        assertThat(Dfa.search(program, shortInput, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(SEARCH_NO_MATCH);
        assertThat(dfa.byteScanFallbackCount()).isZero();
        assertThat(Dfa.search(program, longInput, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(SEARCH_NO_MATCH);
        assertThat(dfa.byteScanFallbackCount()).isPositive();
    }

    @Test
    public void testRejectedFixedDistanceScanUsesStartByteAcceleration()
    {
        Prog program = compile("[a-z]{8}-[0-9]{4}");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] input = new byte[128 * 1024];
        Arrays.fill(input, (byte) '-');

        assertThat(dfa.canStartByteAcceleration()).isTrue();
        assertThat(dfa.canFixedDistanceByteAcceleration()).isTrue();
        assertThat(Dfa.search(program, Slices.wrappedBuffer(input), false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(SEARCH_NO_MATCH);
        assertThat(dfa.byteScanFallbackCount()).isZero();
    }

    @Test
    public void testUnsupportedPatternsDoNotConfigureAcceleration()
    {
        assertThat(firstMatchDfa("abc").canStartByteAcceleration()).isFalse();
        assertThat(firstMatchDfa("a*").canStartByteAcceleration()).isFalse();
        assertThat(firstMatchDfa("^[a-z]+").canStartByteAcceleration()).isFalse();
        assertThat(firstMatchDfa("\\b[a-z]+").canStartByteAcceleration()).isFalse();
        assertThat(firstMatchDfa("[^a]b").canStartByteAcceleration()).isFalse();
    }

    @Test
    public void testDenseLiteralStartsUseStartLiteralFilter()
    {
        assumeTrue(VectorSupport.isAvailable(), "requires the vector scanners");
        // Text dense in the literals' lead byte passes the start set at every character but never
        // passes the filter, so the scan stays productive.
        Prog program = compile("Шерлок Холмс|Джон Уотсон|Ирен Адлер|инспектор Лестрейд|профессор Мориарти");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(Dfa.search(program, Slices.utf8Slice("а".repeat(64 * 1024)), false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(SEARCH_NO_MATCH);
        assertThat(dfa.startLiteralFilterScanCount()).isPositive();
        assertThat(dfa.byteScanFallbackCount()).isZero();
        // Two-byte literals leave no third position, and a one-byte literal followed by a broad
        // class leaves too few narrow positions.
        assertThat(firstMatchDfa("(?i)ab|cd").startLiteralFilter()).isNull();
        assertThat(firstMatchDfa("(?i)a[a-z]+|b[a-z]+").startLiteralFilter()).isNull();
    }

    @Test
    public void testRareLiteralStartsUseStartLiteralFilterOnLongSearches()
    {
        assumeTrue(VectorSupport.isAvailable(), "requires the vector scanners");
        // A rare start set of more than three bytes that is not one range takes the filter, but
        // only on vector searches; a shorter search stops on the start bytes.
        Dfa.DfaInstance dfa = firstMatchDfa("Sherlock|Holmes|Watson|Irene|Adler|John|Baker");
        byte[] text = ("S" + " ".repeat(1023)).getBytes(StandardCharsets.UTF_8);
        assertThat(dfa.findStartByteCandidate(text, 0, 1023)).isZero();
        assertThat(dfa.findStartByteCandidate(text, 0, 1024)).isEqualTo(-1);
        // Other rare start sets take a filter of two literals only with 64-byte vectors, and a
        // rare small set or range never takes one of more literals.
        assertThat(firstMatchDfa("Huck[a-zA-Z]+|Saw[a-zA-Z]+").startLiteralFilter() != null)
                .as("two-literal filter with %s preferred byte lanes", ByteVector.SPECIES_PREFERRED.length())
                .isEqualTo(ByteVector.SPECIES_PREFERRED.length() == 64);
        assertThat(firstMatchDfa("Huck|Hill|Saw").startLiteralFilter()).isNull();
        assertThat(firstMatchDfa("Abcd|Bcde|Cdef|Defg").startLiteralFilter()).isNull();
    }

    @Test
    public void testInitializationMemoryCountsStartByteTables()
    {
        // The exact memory a forward DFA needs exceeds its minimum by its start-byte tables, which
        // stay within their bound even for the widest literal filter, so a budget that keeps the
        // bound initializes the DFA without building them.
        for (String expression : List.of("(abc|def)", "(abc|def|ghi|jkl|mno|pqr|stu|vwx)")) {
            Prog program = compile(expression);
            for (Dfa.DfaInstance.Kind kind : List.of(Dfa.DfaInstance.Kind.FIRST_MATCH, Dfa.DfaInstance.Kind.LONGEST_MATCH)) {
                String description = kind + " " + expression;
                long initializationMemory = Dfa.DfaInstance.initializationMemory(program, kind);
                assertThat(initializationMemory - Dfa.DfaInstance.minimumMemory(program, kind)).as(description).isLessThanOrEqualTo(Dfa.DfaInstance.MAXIMUM_START_BYTE_TABLES_MEMORY);
                assertThat(new Dfa.DfaInstance(program, kind, initializationMemory).ok()).as(description).isTrue();
                assertThat(new Dfa.DfaInstance(program, kind, initializationMemory - 1).ok()).as(description).isFalse();
            }
        }
    }

    @Test
    public void testStartLiteralFilterVectorScannerMatchesScalarReference()
    {
        assumeTrue(VectorSupport.isAvailable(), "requires the vector scanners");
        Random random = new Random(96);
        Set<Integer> scannerWidths = new HashSet<>();
        for (String pattern : startLiteralFilterPatterns()) {
            StartLiteralFilter filter = firstMatchDfa(pattern).startLiteralFilter();
            scannerWidths.add(filter.values().length / 3);
            for (int iteration = 0; iteration < 200; iteration++) {
                byte[] text = literalText(random, pattern, 1 + random.nextInt(4_096));
                int offset = random.nextInt(Math.min(8, text.length));
                int length = random.nextInt(text.length - offset + 1);

                int expected = -1;
                for (int position = offset; position < offset + length; position++) {
                    if (filter.accepts(text, position, offset + length)) {
                        expected = position;
                        break;
                    }
                }
                assertThat(filter.find(text, offset, length))
                        .as("pattern %s offset=%s length=%s", pattern, offset, length)
                        .isEqualTo(expected);
                assertThat(VectorByteSetScanner.findLiteralSet(text, offset, length, filter))
                        .as("pattern %s offset=%s length=%s", pattern, offset, length)
                        .isEqualTo(expected);
            }

            // The first bytes of one literal end a zero-filled window that ends the array: the only
            // accepted position lands in the last lane of a full vector or at each offset of the
            // scalar tail, and a scan that reads past the window fails.
            byte[] literal = literals(pattern)[0].getBytes(StandardCharsets.UTF_8);
            int span = filter.thirdOffset() + 1;
            for (int end = span; end <= 200; end++) {
                byte[] text = new byte[end];
                System.arraycopy(literal, 0, text, end - span, span);
                assertThat(filter.find(text, 0, end)).as("pattern %s end=%s", pattern, end).isEqualTo(end - span);
                assertThat(VectorByteSetScanner.findLiteralSet(text, 0, end, filter)).as("pattern %s end=%s", pattern, end).isEqualTo(end - span);
            }
        }
        // Two-group filters run the compare kernel; four- and eight-group filters share the
        // nibble kernel, the eight-group one with group bits up to the sign bit.
        assertThat(scannerWidths).containsExactlyInAnyOrder(2, 4, 8);
    }

    @Test
    public void testClassPairVectorScannerMatchesScalarReference()
    {
        assumeTrue(VectorSupport.isAvailable(), "requires the vector scanners");
        Random random = new Random(98);
        // Each side's bytes in three slots, a side with fewer repeating its last byte, as the inner
        // literal search stores a class pair.
        List<byte[]> pairs = List.of(
                new byte[] {'!', '.', '?', '"', '\'', '\''},
                new byte[] {'@', '@', '@', 'x', 'x', 'x'},
                new byte[] {'a', 'b', 'b', '1', '2', '3'});
        for (byte[] bytes : pairs) {
            String description = new String(bytes, StandardCharsets.US_ASCII);
            for (int iteration = 0; iteration < 200; iteration++) {
                // Pair bytes from dense to rare among letters and spaces.
                int rarity = 1 + random.nextInt(200);
                byte[] text = new byte[1 + random.nextInt(4_096)];
                for (int index = 0; index < text.length; index++) {
                    text[index] = random.nextInt(rarity) == 0 ? bytes[random.nextInt(bytes.length)] : (byte) " abcdefghijklmnopqrstuvwxyz".charAt(random.nextInt(27));
                }
                int offset = random.nextInt(Math.min(8, text.length));
                int length = random.nextInt(text.length - offset + 1);

                int expected = -1;
                for (int position = offset; position < offset + length - 1; position++) {
                    if (inClassPairSide(text[position], bytes, 0) && inClassPairSide(text[position + 1], bytes, 3)) {
                        expected = position;
                        break;
                    }
                }
                assertThat(InnerLiteralSearch.findClassPair(text, offset, length, bytes))
                        .as("pair %s offset=%s length=%s", description, offset, length)
                        .isEqualTo(expected);
                assertThat(VectorByteSetScanner.findClassPair(text, offset, length, bytes))
                        .as("pair %s offset=%s length=%s", description, offset, length)
                        .isEqualTo(expected);
                assertThat(VectorByteSetScanner.findClassPairSve(text, offset, length, bytes))
                        .as("SVE pair %s offset=%s length=%s", description, offset, length)
                        .isEqualTo(expected);
            }

            // The pair ends a zero-filled window that ends the array, its second byte the last byte
            // of the window: the only hit lands in the last lane of a full vector or at each offset
            // of the scalar tail. One byte shorter, the window holds no pair, and a scan that reads
            // past it fails.
            for (int end = 2; end <= 200; end++) {
                byte[] text = new byte[end];
                text[end - 2] = bytes[2];
                text[end - 1] = bytes[5];
                assertThat(InnerLiteralSearch.findClassPair(text, 0, end, bytes)).as("pair %s end=%s", description, end).isEqualTo(end - 2);
                assertThat(VectorByteSetScanner.findClassPair(text, 0, end, bytes)).as("pair %s end=%s", description, end).isEqualTo(end - 2);
                assertThat(InnerLiteralSearch.findClassPair(text, 0, end - 1, bytes)).as("pair %s end=%s", description, end).isEqualTo(-1);
                assertThat(VectorByteSetScanner.findClassPair(text, 0, end - 1, bytes)).as("pair %s end=%s", description, end).isEqualTo(-1);
                assertThat(VectorByteSetScanner.findClassPairSve(text, 0, end, bytes)).as("SVE pair %s end=%s", description, end).isEqualTo(end - 2);
                assertThat(VectorByteSetScanner.findClassPairSve(text, 0, end - 1, bytes)).as("SVE pair %s end=%s", description, end).isEqualTo(-1);
            }
        }
    }

    @Test
    public void testStartLiteralFilterSearchMatchesNfa()
    {
        Random random = new Random(97);
        for (String pattern : startLiteralFilterPatterns()) {
            Re2 re2 = Re2.compileWithoutScanPlanForTesting(Slices.utf8Slice(pattern), Re2.Options.defaults());
            Prog program = re2.forwardProgramForDiagnostics();
            assertThat(program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).startLiteralFilter() != null).as("pattern %s", pattern).isEqualTo(VectorSupport.isAvailable());

            for (int trial = 0; trial < 20; trial++) {
                byte[] body = literalText(random, pattern, 1_024 + random.nextInt(8 * 1_024));
                int backingOffset = random.nextInt(8);
                byte[] backing = new byte[backingOffset + body.length + random.nextInt(8)];
                System.arraycopy(body, 0, backing, backingOffset, body.length);
                Slice text = Slices.wrappedBuffer(backing, backingOffset, body.length);

                List<Boundary> expected = nfaBoundaries(program, text);
                assertThat(Dfa.countMatches(program, text, Prog.MatchKind.FIRST_MATCH))
                        .as("count for pattern %s", pattern)
                        .isEqualTo(expected.size());
                assertThat(matcherBoundaries(re2.groupZeroMatcher(text, null)))
                        .as("matches for pattern %s", pattern)
                        .isEqualTo(expected);

                int start = random.nextInt(128);
                int end = text.length() - random.nextInt(128);
                assertDfaMatchesNfa(program, text, start, end, Prog.MatchKind.FIRST_MATCH, pattern);
                assertDfaMatchesNfa(program, text, start, end, Prog.MatchKind.LONGEST_MATCH, pattern);
            }
        }

        // Trino folds the ligature ﬆ to "st", so its program reaches the filter with more paths,
        // and the filter still stops at a match that begins with the ligature.
        String pattern = "(?i)straße|strasse|masse";
        Prog program = TrinoRegexp.compileWithoutScanPlanForTesting(Slices.utf8Slice(pattern)).re2().forwardProgramForDiagnostics();
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(dfa.startLiteralFilter() != null).isEqualTo(VectorSupport.isAvailable());
        Slice text = Slices.utf8Slice(".".repeat(1_024) + "ﬆraße");
        assertDfaMatchesNfa(program, text, 0, text.length(), Prog.MatchKind.FIRST_MATCH, pattern);
        assertThat(Dfa.search(program, text, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(text.length());
        assertThat(dfa.startLiteralFilterScanCount() > 0).isEqualTo(VectorSupport.isAvailable());

        // The path walk does not evaluate assertions, so a literal with one in its first bytes
        // must not lose its matches to a filter built from the other literals.
        pattern = "(?i)abc\\b def|ghi";
        program = compile(pattern);
        text = Slices.utf8Slice(".".repeat(4_096) + "abcdef abc def GHI");
        assertDfaMatchesNfa(program, text, 0, text.length(), Prog.MatchKind.FIRST_MATCH, pattern);

        // Three literals make a four-group filter, which the nibble kernel scans with a two-bit
        // mask at the second position: [bc] with [bf] keeps it inside the low nibble, and [bc]
        // with [bB] spreads it across both nibbles. The scan finds every literal the pattern
        // accepts inside a full vector.
        for (char other : "fB".toCharArray()) {
            pattern = "a[bc]x|e[b" + other + "]y|idz";
            program = compile(pattern);
            dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
            assertThat(dfa.startLiteralFilter() != null).isEqualTo(VectorSupport.isAvailable());
            if (dfa.startLiteralFilter() != null) {
                assertThat(dfa.startLiteralFilter().secondMask()).isEqualTo((byte) (other == 'f' ? 0x05 : 0x21));
            }
            for (String literal : List.of("abx", "acx", "eby", "e" + other + "y", "idz")) {
                text = Slices.utf8Slice(".".repeat(2_048) + literal + ".".repeat(64));
                assertDfaMatchesNfa(program, text, 0, text.length(), Prog.MatchKind.FIRST_MATCH, pattern);
                assertThat(Dfa.search(program, text, false, Prog.MatchKind.FIRST_MATCH, true)).as("pattern %s literal %s", pattern, literal).isEqualTo(2_051);
            }
            assertThat(dfa.startLiteralFilterScanCount() > 0).isEqualTo(VectorSupport.isAvailable());
        }
    }

    @Test
    public void testFixedDistanceByteSelectsMoreSelectiveCandidate()
    {
        Dfa.DfaInstance dfa = firstMatchDfa("[a-z]{8}-[0-9]{4}");
        byte[] text = "............abcdefgh-1234".getBytes(StandardCharsets.UTF_8);

        assertThat(dfa.stateBudget() - dfa.availableStateMemory())
                .isEqualTo(dfa.retainedStateMemory());
        assertThat(dfa.canFixedDistanceByteAcceleration()).isTrue();
        long memoryAfterCandidateAnalysis = dfa.availableStateMemory();
        assertThat(dfa.stateBudget() - memoryAfterCandidateAnalysis)
                .isEqualTo(dfa.retainedStateMemory() + 304);
        assertThat(dfa.fixedDistanceByteOffset()).isEqualTo(8);
        assertThat(dfa.findFixedDistanceByteCandidate(text, 0, text.length)).isEqualTo(12);
        byte[] offsetText = "....-.......abcdefgh-1234".getBytes(StandardCharsets.UTF_8);
        assertThat(dfa.findFixedDistanceByteCandidate(offsetText, 4, offsetText.length - 4)).isEqualTo(12);
        assertThat(Dfa.canUseFixedDistanceByteAcceleration(false, true, 0, 8 * 1024)).isTrue();
        assertThat(Dfa.canUseFixedDistanceByteAcceleration(false, true, 0, 4096)).isTrue();
        assertThat(Dfa.canUseFixedDistanceByteAcceleration(false, true, 0, 4095)).isFalse();
        assertThat(Dfa.canUseFixedDistanceByteAcceleration(false, true, 0, 1024)).isFalse();
        assertThat(Dfa.canUseFixedDistanceByteAcceleration(true, true, 0, 8 * 1024)).isFalse();
        assertThat(Dfa.canUseFixedDistanceByteAcceleration(false, false, 0, 8 * 1024)).isFalse();
        assertThat(Dfa.canUseFixedDistanceByteAcceleration(false, true, 1, 8 * 1024)).isFalse();

        dfa.resetCacheExternal();
        assertThat(dfa.availableStateMemory()).isEqualTo(memoryAfterCandidateAnalysis);
    }

    @Test
    public void testFixedDistanceByteCandidateRetriesAfterCacheReset()
            throws Exception
    {
        Dfa.DfaInstance dfa = firstMatchDfa("[a-z]{8}-[0-9]{4}");
        Field availableStateMemory = Dfa.DfaInstance.class.getDeclaredField("availableStateMemory");
        availableStateMemory.setAccessible(true);
        availableStateMemory.setLong(dfa, 303);

        assertThat(dfa.canFixedDistanceByteAcceleration()).isFalse();
        assertThat(dfa.fixedDistanceByteCandidatesInitialized()).isTrue();

        dfa.resetCacheExternal();
        assertThat(dfa.fixedDistanceByteCandidatesInitialized()).isFalse();
        assertThat(dfa.canFixedDistanceByteAcceleration()).isTrue();
        assertThat(dfa.stateBudget() - dfa.availableStateMemory())
                .isEqualTo(dfa.retainedStateMemory() + 304);
    }

    @Test
    public void testFixedDistanceByteAccelerationRequiresOneOffsetOnEveryPath()
    {
        assertThat(firstMatchDfa("(?:abc|xyz)-[0-9]").canFixedDistanceByteAcceleration()).isTrue();
        assertThat(firstMatchDfa("[a-z]:[0-9]").fixedDistanceByteOffset()).isEqualTo(1);
        assertThat(firstMatchDfa("[a-z]{16}-[0-9]").fixedDistanceByteOffset()).isEqualTo(16);
        assertThat(firstMatchDfa("[a-z]+-[0-9]").canFixedDistanceByteAcceleration()).isFalse();
        // Unicode case folding adds multi-byte equivalents, so ':' is not at one byte offset.
        assertThat(firstMatchDfa("(?i)[a-z]{4}:[0-9]").canFixedDistanceByteAcceleration()).isFalse();
        assertThat(firstMatchDfa("a*").canFixedDistanceByteAcceleration()).isFalse();
        assertThat(firstMatchDfa("\\b[a-z]{8}-[0-9]").canFixedDistanceByteAcceleration()).isFalse();
        assertThat(firstMatchDfa("abc").canFixedDistanceByteAcceleration()).isFalse();
    }

    @Test
    public void testFixedDistanceByteSearchMatchesPinnedUpstreamBoundary()
    {
        Prog program = compile("[a-z]{8}-[0-9]{4}");
        byte[] text = new byte[8 * 1024];
        Arrays.fill(text, (byte) 'a');
        byte[] match = "abcdefgh-1234".getBytes(StandardCharsets.UTF_8);
        int matchStart = 6 * 1024;
        System.arraycopy(match, 0, text, matchStart, match.length);

        assertThat(Dfa.search(
                program,
                Slices.wrappedBuffer(text),
                0,
                text.length,
                false,
                Prog.MatchKind.FIRST_MATCH,
                true))
                .isEqualTo(matchStart + match.length);
    }

    @Test
    public void testFixedDistanceByteSearchResetsAfterCacheExhaustion()
            throws Exception
    {
        for (Prog.MatchKind matchKind : List.of(Prog.MatchKind.FIRST_MATCH, Prog.MatchKind.LONGEST_MATCH)) {
            String pattern = "[a-z]{8}-[0-9]{4}";
            Prog program = compile(pattern);
            Dfa.DfaInstance.Kind dfaKind = matchKind == Prog.MatchKind.FIRST_MATCH
                    ? Dfa.DfaInstance.Kind.FIRST_MATCH
                    : Dfa.DfaInstance.Kind.LONGEST_MATCH;
            Dfa.DfaInstance dfa = program.getCachedDfa(dfaKind);

            assertThat(Dfa.search(program, Slices.EMPTY_SLICE, false, matchKind, true)).isEqualTo(SEARCH_NO_MATCH);
            assertThat(dfa.canFixedDistanceByteAcceleration()).isTrue();

            Field availableStateMemory = Dfa.DfaInstance.class.getDeclaredField("availableStateMemory");
            availableStateMemory.setAccessible(true);
            availableStateMemory.setLong(dfa, 0);

            byte[] text = new byte[8 * 1024];
            Arrays.fill(text, (byte) 'a');
            byte[] match = "abcdefgh-1234".getBytes(StandardCharsets.UTF_8);
            int matchStart = 6 * 1024;
            System.arraycopy(match, 0, text, matchStart, match.length);
            Slice context = Slices.wrappedBuffer(text);
            int resetCount = dfa.resetCount();

            assertDfaMatchesNfa(program, context, 0, context.length(), matchKind, pattern);
            assertThat(dfa.resetCount()).isEqualTo(resetCount + 1);
            assertThat(dfa.canFixedDistanceByteAcceleration()).isTrue();
        }
    }

    @Test
    public void testFixedDistanceByteSearchSupportsCandidateSets()
    {
        Dfa.DfaInstance byteClass = firstMatchDfa("[a-z]{8}[01][0-9]{4}");
        assertThat(byteClass.fixedDistanceByteOffset()).isEqualTo(8);
        assertThat(byteClass.findFixedDistanceByteCandidate("........0....".getBytes(StandardCharsets.UTF_8), 0, 13)).isZero();
        assertThat(byteClass.findFixedDistanceByteCandidate("........1....".getBytes(StandardCharsets.UTF_8), 0, 13)).isZero();
        assertThat(byteClass.findFixedDistanceByteCandidate("........2....".getBytes(StandardCharsets.UTF_8), 0, 13)).isEqualTo(-1);

        Dfa.DfaInstance foldedLiteral = firstMatchDfa("[a-z]{8}(?i:x)[0-9]{4}");
        assertThat(foldedLiteral.fixedDistanceByteOffset()).isEqualTo(8);
        assertThat(foldedLiteral.findFixedDistanceByteCandidate("........x....".getBytes(StandardCharsets.UTF_8), 0, 13)).isZero();
        assertThat(foldedLiteral.findFixedDistanceByteCandidate("........X....".getBytes(StandardCharsets.UTF_8), 0, 13)).isZero();
        assertThat(foldedLiteral.findFixedDistanceByteCandidate("........y....".getBytes(StandardCharsets.UTF_8), 0, 13)).isEqualTo(-1);
    }

    @Test
    public void testTwoAndThreeFixedDistanceBytesUseSmallSetScanner()
    {
        for (String pattern : List.of("[a-z]{8}[01][0-9]{4}", "[a-z]{8}[012][0-9]{4}")) {
            Prog program = compile(pattern);
            Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
            byte[] text = new byte[8 * 1024];
            Arrays.fill(text, (byte) 'a');
            byte[] match = "abcdefgh01234".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(match, 0, text, text.length - match.length, match.length);

            assertThat(Dfa.search(program, Slices.wrappedBuffer(text), false, Prog.MatchKind.FIRST_MATCH, true))
                    .isEqualTo(text.length);
            assertThat(dfa.smallByteSetScanCount() > 0).as(pattern).isEqualTo(VectorSupport.isAvailable());
        }
    }

    @Test
    public void testFixedDistanceByteSearchMatchesNfa()
    {
        for (FixedDistanceCase testCase : fixedDistanceCases()) {
            Prog program = compile(testCase.pattern());
            assertThat(program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).canFixedDistanceByteAcceleration())
                    .as("pattern %s", testCase.pattern())
                    .isTrue();

            byte[] text = new byte[8 * 1024];
            Arrays.fill(text, (byte) 'a');
            byte[] match = testCase.match().getBytes(StandardCharsets.UTF_8);
            int matchStart = 6 * 1024;
            System.arraycopy(match, 0, text, matchStart, match.length);
            Slice context = Slices.wrappedBuffer(text);

            assertDfaMatchesNfa(program, context, 100, text.length - 100, Prog.MatchKind.FIRST_MATCH, testCase.pattern());
            assertDfaMatchesNfa(program, context, 100, text.length - 100, Prog.MatchKind.LONGEST_MATCH, testCase.pattern());
        }
    }

    @Test
    public void testFixedDistanceByteSearchRandomizedDifferential()
    {
        Random random = new Random(0);
        for (FixedDistanceCase testCase : fixedDistanceCases()) {
            Prog program = compile(testCase.pattern());
            assertThat(program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).canFixedDistanceByteAcceleration())
                    .as("pattern %s", testCase.pattern())
                    .isTrue();

            for (int trial = 0; trial < 20; trial++) {
                byte[] text = new byte[8 * 1024];
                random.nextBytes(text);
                int textStart = random.nextInt(128);
                int textEnd = text.length - random.nextInt(128);
                if ((trial & 1) == 0) {
                    byte[] match = testCase.match().getBytes(StandardCharsets.UTF_8);
                    int matchStart = textStart + random.nextInt(textEnd - textStart - match.length);
                    System.arraycopy(match, 0, text, matchStart, match.length);
                }

                Slice context = Slices.wrappedBuffer(text);
                assertDfaMatchesNfa(program, context, textStart, textEnd, Prog.MatchKind.FIRST_MATCH, testCase.pattern());
                assertDfaMatchesNfa(program, context, textStart, textEnd, Prog.MatchKind.LONGEST_MATCH, testCase.pattern());

                int[] nfaGroups = new int[2];
                boolean nfaMatched = Nfa.search(program, context, textStart, textEnd, false, Prog.MatchKind.LONGEST_MATCH, nfaGroups);
                assertThat(Dfa.search(program, context, textStart, textEnd, false, Prog.MatchKind.FIRST_MATCH, false) >= 0)
                        .as("boundary-free match presence for pattern %s", testCase.pattern())
                        .isEqualTo(nfaMatched);
            }
        }
    }

    @Test
    public void testFixedDistanceByteSearchSemanticBoundaries()
    {
        String pattern = "[a-z]{4}:(?:[0-9]|[0-9]{3})";
        Prog program = compile(pattern);
        byte[] backing = new byte[(8 * 1024) + 34];
        Arrays.fill(backing, (byte) 'a');
        Slice context = Slices.wrappedBuffer(backing, 17, 8 * 1024);
        byte[] firstMatch = "abcd:123".getBytes(StandardCharsets.UTF_8);
        byte[] secondMatch = "wxyz:456".getBytes(StandardCharsets.UTF_8);
        int firstMatchStart = 256;
        int secondMatchStart = context.length() - secondMatch.length;
        System.arraycopy(firstMatch, 0, backing, context.byteArrayOffset() + firstMatchStart, firstMatch.length);
        System.arraycopy(secondMatch, 0, backing, context.byteArrayOffset() + secondMatchStart, secondMatch.length);

        assertThat(program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).canFixedDistanceByteAcceleration()).isTrue();
        assertDfaMatchesNfa(program, context, 0, context.length(), Prog.MatchKind.FIRST_MATCH, pattern);
        assertDfaMatchesNfa(program, context, 0, context.length(), Prog.MatchKind.LONGEST_MATCH, pattern);
        assertThat(Dfa.search(program, context, 0, context.length(), false, Prog.MatchKind.FIRST_MATCH, true))
                .isEqualTo(firstMatchStart + "abcd:1".length());
        assertThat(Dfa.search(program, context, 0, context.length(), false, Prog.MatchKind.LONGEST_MATCH, true))
                .isEqualTo(firstMatchStart + firstMatch.length);
        assertDfaMatchesNfa(program, context, firstMatchStart + firstMatch.length, context.length(), Prog.MatchKind.FIRST_MATCH, pattern);

        byte[] denseFalsePositives = new byte[8 * 1024];
        Arrays.fill(denseFalsePositives, (byte) 'a');
        for (int position = 4; position < denseFalsePositives.length; position += 7) {
            denseFalsePositives[position] = ':';
        }
        Slice denseContext = Slices.wrappedBuffer(denseFalsePositives);
        assertDfaMatchesNfa(program, denseContext, 0, denseContext.length(), Prog.MatchKind.FIRST_MATCH, pattern);
        assertDfaMatchesNfa(program, denseContext, 0, denseContext.length(), Prog.MatchKind.LONGEST_MATCH, pattern);
    }

    @Test
    public void testFixedDistanceByteSearchLatin1()
    {
        String pattern = "[\\x80\\x81]{4}:[0-9]";
        Prog program = compile(pattern, Regexp.LIKE_PERL | Regexp.LATIN1);
        byte[] text = new byte[8 * 1024];
        Arrays.fill(text, (byte) 0x80);
        byte[] match = {(byte) 0x80, (byte) 0x81, (byte) 0x80, (byte) 0x81, ':', '7'};
        int matchStart = 6 * 1024;
        System.arraycopy(match, 0, text, matchStart, match.length);
        Slice context = Slices.wrappedBuffer(text);

        assertThat(program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).canFixedDistanceByteAcceleration()).isTrue();
        assertDfaMatchesNfa(program, context, 0, context.length(), Prog.MatchKind.FIRST_MATCH, pattern);
        assertDfaMatchesNfa(program, context, 0, context.length(), Prog.MatchKind.LONGEST_MATCH, pattern);
    }

    @Test
    public void testAnchoredSearchDoesNotBuildFixedDistanceCandidates()
    {
        Prog program = compile("[a-z]{8}-[0-9]{4}");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] text = new byte[8 * 1024];
        Arrays.fill(text, (byte) 'a');
        Slice context = Slices.wrappedBuffer(text);

        assertThat(dfa.fixedDistanceByteCandidatesInitialized()).isFalse();
        Dfa.search(program, context, 0, text.length, true, Prog.MatchKind.FIRST_MATCH, true);

        assertThat(dfa.fixedDistanceByteCandidatesInitialized()).isFalse();
    }

    @Test
    public void testFixedDistanceSearchRemainsCorrectWithConcurrentResets()
            throws Exception
    {
        Prog program = compile("[a-z]{8}-[0-9]{4}");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] text = new byte[8 * 1024];
        Arrays.fill(text, (byte) 'a');
        byte[] match = "abcdefgh-1234".getBytes(StandardCharsets.UTF_8);
        int matchStart = 6 * 1024;
        System.arraycopy(match, 0, text, matchStart, match.length);
        Slice context = Slices.wrappedBuffer(text);
        CountDownLatch startGate = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> searches = executor.submit(() -> {
                await(startGate);
                for (int iteration = 0; iteration < 100; iteration++) {
                    assertThat(Dfa.search(program, context, 0, text.length, false, Prog.MatchKind.FIRST_MATCH, true))
                            .isEqualTo(matchStart + match.length);
                }
            });
            Future<?> resets = executor.submit(() -> {
                await(startGate);
                for (int iteration = 0; iteration < 100; iteration++) {
                    dfa.resetCacheExternal();
                }
            });

            startGate.countDown();
            searches.get(10, TimeUnit.SECONDS);
            resets.get(10, TimeUnit.SECONDS);
        }
    }

    private static void assertDfaMatchesNfa(
            Prog program,
            Slice context,
            int start,
            int end,
            Prog.MatchKind matchKind,
            String pattern)
    {
        int[] nfaGroups = new int[2];
        boolean nfaMatched = Nfa.search(program, context, start, end, false, matchKind, nfaGroups);
        long dfaMatchEnd = Dfa.search(program, context, start, end, false, matchKind, true);

        assertThat(dfaMatchEnd >= 0)
                .as("match presence for pattern %s and kind %s", pattern, matchKind)
                .isEqualTo(nfaMatched);
        if (nfaMatched) {
            assertThat(dfaMatchEnd)
                    .as("match end for pattern %s and kind %s", pattern, matchKind)
                    .isEqualTo(nfaGroups[1]);
        }
    }

    @Test
    public void testNullableStartByteEarlyMatch()
    {
        Dfa.DfaInstance star = firstMatchDfa("x*");
        byte[] miss = "aaa".getBytes(StandardCharsets.UTF_8);
        byte[] hit = "xaa".getBytes(StandardCharsets.UTF_8);

        assertThat(star.canStartByteAcceleration()).isFalse();
        assertThat(star.canUseNullableStartByteCheck()).isTrue();
        assertThat(Dfa.canReturnEmptyAtStart(star, miss, 0, miss.length, true, false)).isTrue();
        assertThat(Dfa.canReturnEmptyAtStart(star, hit, 0, hit.length, true, false)).isFalse();
        assertThat(Dfa.canReturnEmptyAtStart(star, miss, 0, miss.length, false, false)).isFalse();
        assertThat(Dfa.canReturnEmptyAtStart(star, miss, 0, miss.length, true, true)).isFalse();

        Dfa.DfaInstance byteFirst = firstMatchDfa("a|");
        assertThat(Dfa.canReturnEmptyAtStart(byteFirst, miss, 0, miss.length, true, false)).isFalse();
        assertThat(Dfa.canReturnEmptyAtStart(byteFirst, "bbb".getBytes(StandardCharsets.UTF_8), 0, 3, true, false)).isTrue();

        Dfa.DfaInstance utf8 = firstMatchDfa("é*");
        assertThat(Dfa.canReturnEmptyAtStart(utf8, miss, 0, miss.length, true, false)).isTrue();

        assertThat(firstMatchDfa("\\b|x").canUseNullableStartByteCheck()).isFalse();
    }

    @Test
    public void testNullableSearchMatchesPinnedUpstreamBoundaries()
    {
        assertThat(Dfa.search(compile("x*"), Slices.utf8Slice("aaa"), 0, 3, false, Prog.MatchKind.FIRST_MATCH, true)).isZero();
        assertThat(Dfa.search(compile("x*"), Slices.utf8Slice("xxa"), 0, 3, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(2);
        assertThat(Dfa.search(compile("x*"), Slices.utf8Slice("xxa"), 2, 3, false, Prog.MatchKind.FIRST_MATCH, true)).isZero();
        assertThat(Dfa.search(compile("|a"), Slices.utf8Slice("a"), 0, 1, false, Prog.MatchKind.FIRST_MATCH, true)).isZero();
        assertThat(Dfa.search(compile("a|"), Slices.utf8Slice("a"), 0, 1, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(1);
    }

    @Test
    public void testSearchMatchesPinnedUpstreamBoundaries()
    {
        Prog program = compile("([a-z]+)-([0-9]+)");
        Slice context = Slices.wrappedBuffer(new byte[] {(byte) 0xFF, '.', '.', 'a', 'b', 'c', '-', '1', '2', '.', (byte) 0xFE});

        // Pinned re2_golden reports groups 3:9,3:6,7:9 for this exact window.
        assertThat(Dfa.search(program, context, 1, 10, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(8);
        assertThat(Dfa.search(program, context, 1, 3, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(SEARCH_NO_MATCH);
    }

    private static Prog compile(String pattern)
    {
        return compile(pattern, Regexp.LIKE_PERL);
    }

    private static Prog compile(String pattern, int parseFlags)
    {
        Regexp regexp = RegexpParser.parse(Slices.utf8Slice(pattern), parseFlags).regexp();
        return Compiler.compile(regexp, false, 0);
    }

    private static Dfa.DfaInstance firstMatchDfa(String pattern)
    {
        return compile(pattern).getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
    }

    private static int findCandidate(byte[] data, int offset, int length, String candidates)
    {
        int end = offset + length;
        for (int position = offset; position < end; position++) {
            if (candidates.indexOf(data[position] & 0xFF) >= 0) {
                return position;
            }
        }
        return -1;
    }

    private static int findCandidate(byte[] data, int offset, int length, byte[] candidates)
    {
        int end = offset + length;
        for (int position = offset; position < end; position++) {
            if (candidates[data[position] & 0xFF] != 0) {
                return position;
            }
        }
        return -1;
    }

    private static List<FixedDistanceCase> fixedDistanceCases()
    {
        return List.of(
                new FixedDistanceCase("[a-z]{8}-[0-9]{4}", "abcdefgh-1234"),
                new FixedDistanceCase("(?:abc|xyz)-[0-9]", "xyz-7"),
                new FixedDistanceCase("(?i)(?:ab|cd){2}:[0-9]", "AbCd:7"),
                new FixedDistanceCase("[éĀ]{2}:[0-9]", "éĀ:7"),
                new FixedDistanceCase("([a-z]{4}):([0-9]+)", "abcd:123"),
                new FixedDistanceCase("(?:ab|cd){2}:[0-9]", "abcd:7"));
    }

    // The 02-literal-alternate and name-alt rows, folds that add a multibyte path (Kelvin sign
    // for k and long s for s), a Cyrillic literal whose cases differ in a lead-byte bit the
    // ASCII cases do not, so one position's mask has two bits, two literals for the narrowest
    // scanner, and a rare start set of mixed bytes, which takes the filter wherever vector
    // scanning is enabled.
    private static List<String> startLiteralFilterPatterns()
    {
        return List.of(
                "(?i)Sherlock Holmes|John Watson|Irene Adler|Inspector Lestrade|Professor Moriarty",
                "(?i)Sherlock|Holmes|Watson",
                "(?i)Sher[a-z]+|Hol[a-z]+",
                "Шерлок Холмс|Джон Уотсон|Ирен Адлер|инспектор Лестрейд|профессор Мориарти",
                "(?i)Шерлок Холмс|Джон Уотсон|Ирен Адлер|инспектор Лестрейд|профессор Мориарти",
                "夏洛克·福尔摩斯|约翰华生|阿德勒|雷斯垂德|莫里亚蒂教授",
                "(?i)kelvin|sky|kiss",
                "(?i)Holmes|Watson|рст",
                "(?i)Holmes|Watson",
                "Sherlock|Holmes|Watson|Irene|Adler|John|Baker");
    }

    // Text made of the pattern's literals in random case, prefixes of them of every length, some
    // of which pass the filter but fail the match, and filler that shares their letters.
    private static byte[] literalText(Random random, String pattern, int length)
    {
        String[] literals = literals(pattern);
        StringBuilder text = new StringBuilder();
        while (text.length() < length) {
            String literal = literals[random.nextInt(literals.length)];
            switch (random.nextInt(16)) {
                case 0 -> text.append(randomCase(random, literal));
                case 1, 2, 3 -> text.append(randomCase(random, literal.substring(0, 1 + random.nextInt(literal.length()))));
                case 4, 5 -> text.append(" ");
                default -> text.append(literal.charAt(random.nextInt(literal.length())));
            }
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    // Whether the byte is in the side of a class pair whose three slots start at `side`.
    private static boolean inClassPairSide(byte value, byte[] bytes, int side)
    {
        return value == bytes[side] || value == bytes[side + 1] || value == bytes[side + 2];
    }

    // The literals of a pattern from startLiteralFilterPatterns(), with each class read as "ock".
    private static String[] literals(String pattern)
    {
        return pattern.replace("(?i)", "").replace("[a-z]+", "ock").split("\\|");
    }

    private static String randomCase(Random random, String literal)
    {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < literal.length(); index++) {
            char value = literal.charAt(index);
            char lower = Character.toLowerCase(value);
            result.append(switch (random.nextInt(4)) {
                case 0 -> Character.toUpperCase(value);
                case 1 -> lower == 'k' ? '\u212A' : lower == 's' ? '\u017F' : lower;
                default -> lower;
            });
        }
        return result.toString();
    }

    private static List<Boundary> nfaBoundaries(Prog program, Slice text)
    {
        List<Boundary> boundaries = new ArrayList<>();
        int[] groups = new int[2];
        int position = 0;
        while (Nfa.search(program, text, position, text.length(), false, Prog.MatchKind.FIRST_MATCH, groups)) {
            // The NFA reports offsets from the search start.
            boundaries.add(new Boundary(position + groups[0], position + groups[1]));
            position += groups[1];
        }
        return boundaries;
    }

    private static List<Boundary> matcherBoundaries(RegexpMatcher matcher)
    {
        List<Boundary> boundaries = new ArrayList<>();
        while (matcher.find()) {
            boundaries.add(new Boundary(matcher.start(), matcher.end()));
        }
        return boundaries;
    }

    private static void await(CountDownLatch latch)
    {
        try {
            latch.await();
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruptedException);
        }
    }

    private record FixedDistanceCase(String pattern, String match) {}

    private record MixedSetCase(String pattern, String match) {}

    private record Boundary(int start, int end) {}
}
