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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

// Ported from upstream RE2: re2/testing/required_prefix_test.cc.
public class TestUpstreamPrefixAccel
{
    @Test
    public void testFusedPrefixAccelRoute()
    {
        Prog nonAsciiProgram = compile("x");
        nonAsciiProgram.configurePrefixAccel(Slices.utf8Slice("Шерлок Холмс"), false);
        assertThat(nonAsciiProgram.prefixAccelStrategy(64)).isEqualTo(Prog.PrefixAccelStrategy.FUSED_SWAR);
        assertThat(nonAsciiProgram.prefixAccelStrategy(1023)).isEqualTo(Prog.PrefixAccelStrategy.FUSED_SWAR);
        assertThat(nonAsciiProgram.prefixAccelStrategy(1024)).isEqualTo(VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_VECTOR : Prog.PrefixAccelStrategy.FUSED_SWAR);

        Prog asciiProgram = compile("x");
        asciiProgram.configurePrefixAccel(Slices.utf8Slice("Sherlock Holmes"), false);
        assertThat(asciiProgram.prefixAccelStrategy(1023)).isEqualTo(Prog.PrefixAccelStrategy.REPEATED_BYTE);
        assertThat(asciiProgram.prefixAccelStrategy(1024)).isEqualTo(VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_VECTOR : Prog.PrefixAccelStrategy.REPEATED_BYTE);
    }

    @Test
    public void testFusedPrefixAccelMatchesReference()
    {
        Random random = new Random(2);
        for (String prefix : List.of("Шерлок Холмс", "夏洛克·福尔摩斯", "Sherlock Holmes", "aqj")) {
            byte[] literal = prefix.getBytes(StandardCharsets.UTF_8);
            Prog program = compile("x");
            program.configurePrefixAccel(Slices.wrappedBuffer(literal), false);

            for (int iteration = 0; iteration < 1_000; iteration++) {
                // Half the bytes come from the literal, so pair candidates are dense.
                byte[] data = new byte[random.nextInt(2049) + literal.length + 8];
                for (int index = 0; index < data.length; index++) {
                    data[index] = random.nextBoolean() ? literal[random.nextInt(literal.length)] : (byte) random.nextInt(256);
                }
                if (random.nextBoolean()) {
                    int insertionPosition = random.nextInt(data.length - literal.length + 1);
                    System.arraycopy(literal, 0, data, insertionPosition, literal.length);
                }

                int offset = random.nextInt(4);
                int length = data.length - offset - random.nextInt(4);
                assertThat(program.prefixAccel(data, offset, length))
                        .isEqualTo(referenceSearch(data, offset, length, literal));
            }
        }
    }

    @Test
    public void testFoldCasePrefixAccelCandidateScan()
    {
        // Pattern with case-insensitive prefix
        String pattern = "(?i)hello\\d+";
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(StandardCharsets.UTF_8)), Regexp.LIKE_PERL);

        Prog prog = Compiler.compile(parsed.regexp(), false, 0);
        assertThat(prog).isNotNull();
        assertThat(prog.canPrefixAccel()).isTrue();
        assertThat(prog.usesFoldCasePrefixCandidateScan()).isTrue();

        // Test various case combinations
        String[] texts = {
                "hello123",      // lowercase
                "HELLO456",      // uppercase
                "HeLLo789",      // mixed case
                "xHELLO111",     // with prefix
                "xxxhello222",   // with longer prefix
        };
        int[] expectedPositions = {0, 0, 0, 1, 3};

        for (int i = 0; i < texts.length; i++) {
            byte[] data = texts[i].getBytes(StandardCharsets.UTF_8);
            int result = prog.prefixAccel(data, 0, data.length);
            assertThat(result)
                    .as("prefixAccel for '%s'", texts[i])
                    .isEqualTo(expectedPositions[i]);
        }

        // Test that non-matching text returns -1
        byte[] noMatch = "world".getBytes(StandardCharsets.UTF_8);
        assertThat(prog.prefixAccel(noMatch, 0, noMatch.length)).isEqualTo(-1);

        // Test with text shorter than prefix
        byte[] tooShort = "hel".getBytes(StandardCharsets.UTF_8);
        assertThat(prog.prefixAccel(tooShort, 0, tooShort.length)).isEqualTo(-1);
    }

    @Test
    public void testFoldCasePrefixAccelCandidateScanMatchesReference()
    {
        Random random = new Random(1);
        // Spaces, digits, punctuation, and non-ASCII bytes compare exactly, including as the
        // first or last byte; setting bit 0x20 must not alias '@' with '`'. "Sherlock Holmes" is
        // cut to nine bytes and ends with a space. Short windows scan a pair at the prefix's first
        // and last bytes, like that of "ab", with fixed offsets, and a pair inside it, like the l
        // and k of "Sherlock Holmes", with the configured ones.
        boolean endPairs = false;
        boolean innerPairs = false;
        for (String prefix : List.of("ab", "aaaa", "abababa", "Sherlock Holmes", "ABCDEFGHIJKLMNO", "sherlock2", "1 x", "€uro", "@_`{")) {
            byte[] literal = prefix.getBytes(StandardCharsets.UTF_8);
            Prog program = compile("x");
            program.configurePrefixAccel(Slices.wrappedBuffer(literal), true);
            assertThat(program.canPrefixAccel()).as(prefix).isTrue();
            int acceleratedPrefixLength = Math.min(literal.length, 9);
            boolean endPair = program.prefixAccelFoldCaseFirstOffset() == 0 && program.prefixAccelFoldCaseSecondOffset() == acceleratedPrefixLength - 1;
            // Compare the same bytes with a folded-start-run scanner when the run has at least
            // three bytes.
            Prog.FoldedStartRun run = acceleratedPrefixLength < 3 ? null : Prog.FoldedStartRun.create(Arrays.copyOf(literal, acceleratedPrefixLength), 0, 0, ByteFrequencies.uniform());
            endPairs |= endPair;
            innerPairs |= !endPair;

            // Lengths on both sides of the vector cutoff. Half the texts are drawn mostly from the
            // prefix's bytes in either case, so many candidate positions fail the complete prefix
            // comparison; the rest are random bytes, so short prefixes also scan to the end.
            for (int iteration = 0; iteration < 400; iteration++) {
                byte[] data = new byte[6 + random.nextInt(2_600)];
                boolean dense = random.nextBoolean();
                for (int index = 0; index < data.length; index++) {
                    data[index] = dense && random.nextInt(8) != 0 ? randomCase(random, literal[random.nextInt(literal.length)]) : (byte) random.nextInt(256);
                }
                if (data.length >= literal.length && random.nextBoolean()) {
                    // Bias half the insertions toward the end.
                    int lastStart = data.length - literal.length;
                    int insertionPosition = random.nextBoolean() ? random.nextInt(lastStart + 1) : lastStart - random.nextInt(Math.min(lastStart, 80) + 1);
                    for (int index = 0; index < literal.length; index++) {
                        data[insertionPosition + index] = randomCase(random, literal[index]);
                    }
                }

                int offset = random.nextInt(4);
                int length = data.length - offset - random.nextInt(4);
                int expected = referenceFoldCaseSearch(data, offset, length, literal, acceleratedPrefixLength);
                assertThat(program.prefixAccel(data, offset, length))
                        .as("%s at offset %s length %s", prefix, offset, length)
                        .isEqualTo(expected);
                if (run != null) {
                    assertThat(run.find(data, offset, length))
                            .as("folded start run %s at offset %s length %s", prefix, offset, length)
                            .isEqualTo(expected);
                }

                // Every pair scan finds the same start whichever two prefix bytes it probes.
                int firstOffset = random.nextInt(acceleratedPrefixLength - 1);
                int secondOffset = firstOffset + 1 + random.nextInt(acceleratedPrefixLength - firstOffset - 1);
                assertThat(findFoldCaseSwar(program, data, offset, length, literal, firstOffset, secondOffset))
                        .as("SWAR %s probing %s and %s at offset %s length %s", prefix, firstOffset, secondOffset, offset, length)
                        .isEqualTo(expected);
                if (VectorSupport.isAvailable()) {
                    assertThat(VectorPrefixScanner.findFoldCase(data, offset, length, literal, acceleratedPrefixLength, firstOffset, secondOffset))
                            .as("vector %s probing %s and %s at offset %s length %s", prefix, firstOffset, secondOffset, offset, length)
                            .isEqualTo(expected);
                    assertThat(VectorPrefixScanner.findFoldCaseDense(data, offset, length, literal, acceleratedPrefixLength, firstOffset, secondOffset))
                            .as("dense vector %s probing %s and %s at offset %s length %s", prefix, firstOffset, secondOffset, offset, length)
                            .isEqualTo(expected);
                    assertThat(VectorPrefixScanner.findFoldCaseSve(data, offset, length, literal, acceleratedPrefixLength, firstOffset, secondOffset))
                            .as("SVE vector %s probing %s and %s at offset %s length %s", prefix, firstOffset, secondOffset, offset, length)
                            .isEqualTo(expected);
                    assertThat(VectorPrefixScanner.findFoldCaseDenseSve(data, offset, length, literal, acceleratedPrefixLength, firstOffset, secondOffset))
                            .as("SVE dense vector %s probing %s and %s at offset %s length %s", prefix, firstOffset, secondOffset, offset, length)
                            .isEqualTo(expected);
                }
            }
        }
        assertThat(endPairs).isTrue();
        assertThat(innerPairs).isTrue();
    }

    @Test
    public void testFoldCaseSwarComparesEveryByte()
    {
        // Every byte a folded prefix can hold, at its first, third, and last offset in a short and
        // a nine-byte prefix, against every data byte. Both SWAR kernels search 11 starts, the
        // first eight in one word and the rest after it, with the planted start in each part. The
        // end pair probes the first and last offsets but not the third; the configured pair
        // probes the tested offset in one search and not in the other. The prefix's other bytes
        // are distinct letters and the filler is a dot, so no other start can match. Under a flat
        // distribution no pair is less than a quarter as likely as the ends, so the folded pair
        // is the end pair the end pair kernel probes.
        Prog program = compile("x");
        long[] flatCounts = new long[256];
        Arrays.fill(flatCounts, 1);
        ByteFrequencies flat = ByteFrequencies.fromCounts(flatCounts);
        for (String template : List.of("bcdfg", "bcdfghjkm")) {
            int size = template.length();
            for (int index : new int[] {0, 2, size - 1}) {
                int probedOffset = index == 0 ? 3 : 1;
                for (int prefixByte = 0; prefixByte < 256; prefixByte++) {
                    byte[] prefix = template.getBytes(StandardCharsets.US_ASCII);
                    prefix[index] = (byte) prefixByte;
                    program.configurePrefixAccel(Slices.wrappedBuffer(prefix), true, flat);
                    assertThat(program.prefixAccelFoldCaseFirstOffset()).isZero();
                    assertThat(program.prefixAccelFoldCaseSecondOffset()).isEqualTo(size - 1);
                    for (int dataByte = 0; dataByte < 256; dataByte++) {
                        boolean folds = asciiLower(dataByte) == asciiLower(prefixByte);
                        for (int start : new int[] {3, 10}) {
                            byte[] data = new byte[size + 10];
                            Arrays.fill(data, (byte) '.');
                            System.arraycopy(prefix, 0, data, start, size);
                            data[start + index] = (byte) dataByte;
                            int expected = folds ? start : -1;
                            Object[] description = {template, prefixByte, index, dataByte, start};
                            assertThat(program.prefixAccelFoldCaseFrontAndBackSWAR(data, 0, data.length))
                                    .as("end pair: %s with byte %s at %s against byte %s at start %s", description)
                                    .isEqualTo(expected);
                            assertThat(findFoldCaseSwar(program, data, 0, data.length, prefix, Math.min(index, probedOffset), Math.max(index, probedOffset)))
                                    .as("probed: %s with byte %s at %s against byte %s at start %s", description)
                                    .isEqualTo(expected);
                            assertThat(findFoldCaseSwar(program, data, 0, data.length, prefix, 1, 3))
                                    .as("not probed: %s with byte %s at %s against byte %s at start %s", description)
                                    .isEqualTo(expected);
                        }
                    }
                }
            }
        }
    }

    // Scans with findFoldCaseSwar probing any two offsets of the program's folded prefix.
    private static int findFoldCaseSwar(Prog program, byte[] data, int offset, int length, byte[] prefix, int firstOffset, int secondOffset)
    {
        return program.findFoldCaseSwar(
                data,
                offset,
                length,
                firstOffset,
                secondOffset,
                Prog.foldCaseLowerBroadcast(prefix[firstOffset]),
                Prog.foldCaseBitBroadcast(prefix[firstOffset]),
                Prog.foldCaseLowerBroadcast(prefix[secondOffset]),
                Prog.foldCaseBitBroadcast(prefix[secondOffset]));
    }

    private static int asciiLower(int value)
    {
        return value >= 'A' && value <= 'Z' ? value + ('a' - 'A') : value;
    }

    private static byte randomCase(Random random, byte value)
    {
        if (random.nextBoolean() && value >= 'a' && value <= 'z') {
            return (byte) (value - ('a' - 'A'));
        }
        if (random.nextBoolean() && value >= 'A' && value <= 'Z') {
            return (byte) (value + ('a' - 'A'));
        }
        return value;
    }

    private static Prog compile(String pattern)
    {
        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice(pattern), Regexp.LIKE_PERL);
        return Compiler.compile(parsed.regexp(), false, 0);
    }

    private static int referenceFoldCaseSearch(byte[] data, int offset, int length, byte[] prefix, int prefixLength)
    {
        int end = offset + length - prefixLength;
        for (int position = offset; position <= end; position++) {
            boolean matches = true;
            for (int index = 0; index < prefixLength; index++) {
                int actual = data[position + index] & 0xFF;
                int expected = prefix[index] & 0xFF;
                if (actual >= 'A' && actual <= 'Z') {
                    actual += 'a' - 'A';
                }
                if (expected >= 'A' && expected <= 'Z') {
                    expected += 'a' - 'A';
                }
                if (actual != expected) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return position;
            }
        }
        return -1;
    }

    private static int referenceSearch(byte[] data, int offset, int length, byte[] literal)
    {
        int lastStart = offset + length - literal.length;
        for (int position = offset; position <= lastStart; position++) {
            boolean matches = true;
            for (int index = 0; index < literal.length; index++) {
                if (data[position + index] != literal[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return position;
            }
        }
        return -1;
    }

    @Test
    public void testPrefixAccelSimpleTests()
    {
        // From upstream re2/testing/required_prefix_test.cc PrefixAccel.SimpleTests.
        List<String> patterns = List.of(
                "aababc\\d+",
                "(?i)AABABC\\d+");

        for (String pattern : patterns) {
            ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(StandardCharsets.UTF_8)), Regexp.LIKE_PERL);

            Prog prog = Compiler.compile(parsed.regexp(), false, 0);
            assertThat(prog).as("compile: %s", pattern).isNotNull();
            assertThat(prog.canPrefixAccel()).as("canPrefixAccel: %s", pattern).isTrue();

            for (int j = 0; j < 100; j++) {
                StringBuilder text = new StringBuilder();
                text.append("a".repeat(j));

                byte[] data = text.toString().getBytes(StandardCharsets.UTF_8);
                assertThat(prog.prefixAccel(data, 0, data.length))
                        .as("prefix accel (no prefix): %s (j=%s)", pattern, j)
                        .isEqualTo(-1);

                text.append("aababc");
                for (int k = 0; k < 100; k++) {
                    text.append("a".repeat(k));
                    data = text.toString().getBytes(StandardCharsets.UTF_8);
                    assertThat(prog.prefixAccel(data, 0, data.length))
                            .as("prefix accel: %s (j=%s k=%s)", pattern, j, k)
                            .isEqualTo(j);
                }
            }
        }
    }
}
