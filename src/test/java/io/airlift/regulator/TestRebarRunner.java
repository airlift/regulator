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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TestRebarRunner
{
    @Test
    void testCompile()
            throws Exception
    {
        assertCounts(run("compile", bytes("a+"), true, bytes("baaa ca")), 2, 2);
    }

    @Test
    void testCount()
            throws Exception
    {
        assertCounts(run("count", bytes("a+"), true, bytes("baaa ca")), 2, 2);
    }

    @Test
    void testCountDoesNotComputeMatchStarts()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("a+b"));

        assertThat(RebarRunner.countMatches(pattern, Slices.utf8Slice("aaab ab"))).isEqualTo(2);
        assertThat(pattern.isReverseProgramComputed()).isFalse();
    }

    @Test
    void testCountUsesSpecializedCounter()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("\\p{L}{8,13}"));

        assertThat(RebarRunner.countMatches(pattern, Slices.utf8Slice("abcdefgh abcdefghijk 123"))).isEqualTo(2);
        assertThat(pattern.isBoundedCharacterClassCounterComputed()).isTrue();
        assertThat(pattern.isReverseProgramComputed()).isFalse();
    }

    @Test
    void testCountSpans()
            throws Exception
    {
        assertCounts(run("count-spans", bytes("a+"), true, bytes("baaa ca")), 4, 4);
    }

    @Test
    void testCountCaptures()
            throws Exception
    {
        assertCounts(run("count-captures", bytes("(a)(b)?"), true, bytes("a ab")), 5, 5);
    }

    @Test
    void testGrep()
            throws Exception
    {
        assertCounts(run("grep", bytes("^a"), true, bytes("a\nba\r\na2\n")), 2, 2);
    }

    @Test
    void testGrepCaptures()
            throws Exception
    {
        assertCounts(run("grep-captures", bytes("(a)"), true, bytes("a\nba\n")), 4, 4);
    }

    @Test
    void testGrepSplitsLinesAtEveryWordOffset()
            throws Exception
    {
        // The word search looks for each terminator two eight-byte words at a time and the last
        // bytes of the haystack one at a time; the runner takes it except on aarch64, and
        // testLineEndFormsFindLineEnds drives both forms on every host. Lines of 0 to 40 bytes,
        // each followed by a second line of 0x0B alone or 0x0B and sixteen 'b', put the terminator
        // at every offset 0 to 15 of the two-word step and 0 to 13 of the byte-at-a-time tail, with
        // a "\r\n" straddling each word boundary. The lines hold 0x8A and 0xFF, which share bits
        // with '\n', and each terminator is followed by 0x0B, the byte a word search's borrow can
        // falsely mark after a '\n'. None may end a line.
        byte[] highBitBytes = {'a', (byte) 0x8A, (byte) 0xFF};
        for (int length = 0; length <= 40; length++) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int index = 0; index < length; index++) {
                line.write(highBitBytes[index % highBitBytes.length]);
            }
            for (String terminator : List.of("\n", "\r\n")) {
                for (int fillerLength : new int[] {0, 16}) {
                    ByteArrayOutputStream haystack = new ByteArrayOutputStream();
                    haystack.writeBytes(line.toByteArray());
                    haystack.writeBytes(bytes(terminator));
                    haystack.write(0x0B);
                    haystack.writeBytes(bytes("b".repeat(fillerLength)));
                    byte[] input = haystack.toByteArray();

                    // The empty pattern matches every line, so the count is the number of engine calls.
                    assertCounts(run("grep", bytes(""), false, input), 2, 2);
                    assertCounts(run("grep", bytes("^[a\\x8A\\xFF]{" + length + "}$"), false, input), 1, 1);
                    assertCounts(run("grep", bytes("^\\x0Bb*$"), false, input), 1, 1);
                    assertCounts(run("grep-captures", bytes("^[a\\x8A\\xFF]{" + length + "}$|^(\\x0Bb*)$"), false, input), 3, 3);
                }
            }
        }
    }

    @Test
    void testGrepCountsEveryLine()
            throws Exception
    {
        assertCounts(run("grep", bytes(""), false, bytes("a")), 1, 1);
        assertCounts(run("grep", bytes(""), false, bytes("a\nb")), 2, 2);
        assertCounts(run("grep", bytes(""), false, bytes("\n\n\na\n\n")), 5, 5);
        assertCounts(run("grep", bytes("^$"), false, bytes("\n\n\na\n\n")), 4, 4);
        // With the word search, sixteen newlines put several in one two-word step, so the search
        // must pick the earliest.
        assertCounts(run("grep", bytes("^$"), false, bytes("\n".repeat(16))), 16, 16);
        assertCounts(run("grep", bytes("^a\rb$"), false, bytes("a\rb\r\n")), 1, 1);
        // Only a "\r\n" terminator loses its '\r'; a final line without a '\n' keeps it.
        assertCounts(run("grep", bytes("\r$"), false, bytes("a\r\nb\r")), 1, 1);
        assertCounts(run("grep-captures", bytes("(\r)$"), false, bytes("a\r\nb\r")), 2, 2);
        // With the word search, lines longer than a two-word step make later lines' searches enter
        // the two-word loop from a nonzero start.
        String longLines = "0123456789abcdefghij\r\n0123456789abcdefghijklmnopq\n0123456789\n";
        assertCounts(run("grep", bytes(""), false, bytes(longLines.repeat(3))), 9, 9);
        assertCounts(run("grep", bytes("^[0-9a-z]{27}$"), false, bytes(longLines.repeat(3))), 3, 3);
    }

    @Test
    void testGrepStopsAtTheEndOfTheBackingArray()
    {
        // A haystack that fills its backing array has no byte after it to read. With the word
        // search, lengths 1 and 17 end in the byte-at-a-time tail, one before and one after a
        // two-word step.
        for (int length : new int[] {1, 17}) {
            Slice haystack = Slices.wrappedBuffer(bytes("a".repeat(length)));
            RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("(a)")).matcher(haystack);
            assertThat(RebarRunner.grepCapturesResult(matcher, haystack, haystack.byteArray(), haystack.byteArrayOffset())).isEqualTo(2L * length);
        }
    }

    @Test
    void testLineEndFormsFindLineEnds()
    {
        // Lines whose lengths sit around one word and one two-word step of the word search and run
        // several steps long, runs of empty lines, and final lines with and without a '\n' or
        // '\r'. In the second form, short leading lines shift where the long line starts, and
        // trailing 'c' bytes add an unterminated final line.
        List<byte[]> haystacks = new ArrayList<>();
        for (int length : new int[] {0, 1, 7, 8, 15, 16, 17, 63, 64, 65}) {
            for (String terminator : List.of("", "\n", "\r", "\r\n", "\n\n\n", "\r\n\r\n\n")) {
                haystacks.add(bytes("a".repeat(length) + terminator));
                haystacks.add(bytes("b\n".repeat(length % 9) + "a".repeat(length) + terminator + "c".repeat(length % 5)));
            }
        }
        // Random haystacks of 0 to 200 bytes, dense in '\n', '\r' and bytes that share bits with them.
        byte[] alphabet = {'\n', '\r', 'a', 0x0B, 0x09, (byte) 0x80, (byte) 0x8A, (byte) 0x8D, (byte) 0xFF};
        Random random = new Random(20261109);
        for (int length = 0; length <= 200; length++) {
            for (int round = 0; round < 20; round++) {
                byte[] haystack = new byte[length];
                int terminatorSparseness = 1 + random.nextInt(40);
                for (int index = 0; index < length; index++) {
                    haystack[index] = random.nextInt(terminatorSparseness) == 0 ? alphabet[random.nextInt(2)] : alphabet[random.nextInt(alphabet.length)];
                }
                haystacks.add(haystack);
            }
        }

        for (byte[] haystack : haystacks) {
            // Each haystack fills its backing array, and also sits in a larger one, at an unaligned
            // offset for the word reads, padded with 'a' bytes and a final '\n'. 'a' ends no line,
            // and the '\n' at the far end of the padding would end a search that read past the
            // haystack, so it would not stop at its length.
            int offset = Long.BYTES + 3;
            byte[] padded = new byte[haystack.length + offset + Long.BYTES];
            Arrays.fill(padded, (byte) 'a');
            padded[padded.length - 1] = '\n';
            System.arraycopy(haystack, 0, padded, offset, haystack.length);
            List<Integer> expected = expectedLineEnds(haystack);
            for (Slice slice : List.of(Slices.wrappedBuffer(haystack), Slices.wrappedBuffer(padded, offset, haystack.length))) {
                for (boolean byteLoop : new boolean[] {true, false}) {
                    assertThat(lineEnds(slice, byteLoop))
                            .as("%s line ends at offset %s of %s", byteLoop ? "byte" : "word", slice.byteArrayOffset(), HexFormat.of().formatHex(haystack))
                            .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void testLatin1Haystack()
            throws Exception
    {
        assertCounts(run("count", bytes("\\xFF"), false, new byte[] {(byte) 0xFF}), 1, 1);
    }

    @Test
    void testMatchModelsRequestOnlyRequiredCaptures()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("(a)(b)?"));
        Slice haystack = Slices.utf8Slice("a ab");

        assertThat(RebarRunner.MatchMeasurement.COUNT.createMatcher(pattern, haystack).groupCount()).isZero();
        assertThat(RebarRunner.MatchMeasurement.SPAN_LENGTH.createMatcher(pattern, haystack).groupCount()).isZero();
        assertThat(RebarRunner.MatchMeasurement.CAPTURES.createMatcher(pattern, haystack).groupCount()).isEqualTo(2);
    }

    @Test
    void testManifest()
            throws Exception
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RebarRunner.writeManifest(
                new ByteArrayInputStream(input("count", bytes("a+"), true, bytes("baaa ca"))),
                output);

        List<String> fields = parseCsv(output.toString(StandardCharsets.UTF_8).strip());
        assertThat(fields)
                .hasSize(28)
                .containsExactly(
                        "test",
                        "count",
                        "false",
                        "true",
                        "2",
                        "85e200cddd8f0561cb88c3dcdf9baf7fe1b363679c1749ed58b8aad551e9a233",
                        "7",
                        "42be0b25b4655c360bb29b18997d5186e238ca5aa8be80035c8d17bf48f1b93f",
                        "2",
                        "match-count",
                        Boolean.toString(Dfa.nativeAccessEnabled()),
                        "false",
                        "false",
                        "true",
                        "true",
                        "FIRST_MATCH",
                        fields.get(16),
                        fields.get(17),
                        fields.get(18),
                        fields.get(19),
                        "0",
                        "false",
                        "",
                        "0",
                        "0",
                        "0",
                        "0",
                        "0");
        assertThat(Long.parseLong(fields.get(16))).isNotNegative();
        assertThat(Integer.parseInt(fields.get(17))).isNotNegative();
        assertThat(Long.parseLong(fields.get(18))).isNotNegative();
        assertThat(Integer.parseInt(fields.get(19))).isNotNegative();
    }

    private static List<Integer> lineEnds(Slice haystack, boolean byteLoop)
    {
        List<Integer> lineEnds = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < haystack.length()) {
            int lineEnd = byteLoop
                    ? RebarRunner.byteLineEnd(haystack, haystack.byteArray(), haystack.byteArrayOffset(), lineStart)
                    : RebarRunner.wordLineEnd(haystack, haystack.byteArray(), haystack.byteArrayOffset(), lineStart);
            lineEnds.add(lineEnd);
            lineStart = lineEnd + 1;
        }
        return lineEnds;
    }

    /// Returns each line's end: the index of each `\n`, and the haystack length for a final line
    /// without one.
    private static List<Integer> expectedLineEnds(byte[] haystack)
    {
        List<Integer> lineEnds = new ArrayList<>();
        for (int index = 0; index < haystack.length; index++) {
            if (haystack[index] == '\n') {
                lineEnds.add(index);
            }
        }
        if (haystack.length > 0 && haystack[haystack.length - 1] != '\n') {
            lineEnds.add(haystack.length);
        }
        return lineEnds;
    }

    private static List<String> run(String model, byte[] pattern, boolean unicode, byte[] haystack)
            throws Exception
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RebarRunner.run(new ByteArrayInputStream(input(model, pattern, unicode, haystack)), output);
        return output.toString(StandardCharsets.US_ASCII).lines().toList();
    }

    private static byte[] input(String model, byte[] pattern, boolean unicode, byte[] haystack)
    {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        write(input, "name", bytes("test"));
        write(input, "model", bytes(model));
        write(input, "case-insensitive", bytes("false"));
        write(input, "unicode", bytes(Boolean.toString(unicode)));
        write(input, "max-iters", bytes("2"));
        write(input, "max-warmup-iters", bytes("1"));
        write(input, "max-time", bytes("1000000000"));
        write(input, "max-warmup-time", bytes("1000000000"));
        write(input, "pattern", pattern);
        write(input, "haystack", haystack);
        return input.toByteArray();
    }

    private static List<String> parseCsv(String line)
    {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                }
                else {
                    quoted = !quoted;
                }
            }
            else if (character == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            }
            else {
                field.append(character);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static void assertCounts(List<String> samples, long... expectedCounts)
    {
        assertThat(samples)
                .hasSize(expectedCounts.length);
        for (int index = 0; index < expectedCounts.length; index++) {
            String[] fields = samples.get(index).split(",");
            assertThat(Long.parseLong(fields[0])).isPositive();
            assertThat(Long.parseLong(fields[1])).isEqualTo(expectedCounts[index]);
        }
    }

    private static void write(ByteArrayOutputStream output, String key, byte[] value)
    {
        output.writeBytes(bytes(key + ":" + value.length + ":"));
        output.writeBytes(value);
        output.write('\n');
    }

    private static byte[] bytes(String value)
    {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
