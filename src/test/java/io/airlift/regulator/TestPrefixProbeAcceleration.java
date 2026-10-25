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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestPrefixProbeAcceleration
{
    private static final List<String> LITERALS = List.of(
            "svc1://",
            "svc5://",
            "content-type:",
            "https://",
            "user=",
            "error: ",
            "aab",
            "abab",
            "ab",
            "ba",
            "http",
            "GET /",
            "café=",
            "Sherlock Holmes",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    private static final ByteFrequencies JSON_RECORDS = TestingByteFrequencies.learnedJsonRecords();
    private static final ByteFrequencies RARE_NON_LETTERS = TestingByteFrequencies.rareNonLetters();

    @Test
    public void testOtherStrategiesKeepFirstByte()
    {
        // Single-byte, folded, and non-ASCII-led prefixes keep their own scans.
        assertThat(configuredProgram("=".getBytes(UTF_8), false).prefixAccelProbeOffset()).isZero();
        assertThat(configuredProgram("svc1://".getBytes(UTF_8), true).prefixAccelProbeOffset()).isZero();
        Prog nonAscii = configuredProgram("év".getBytes(UTF_8), false);
        assertThat(nonAscii.prefixAccelStrategy(4_096)).isNotEqualTo(Prog.PrefixAccelStrategy.REPEATED_BYTE);
        assertThat(nonAscii.prefixAccelProbeOffset()).isZero();
    }

    @Test
    public void testCompiledProgramsInBothEncodings()
    {
        Re2.Options utf8Json = Re2.Options.defaults().setByteFrequencies(JSON_RECORDS);
        Re2.Options latin1Json = Re2.Options.latin1().setByteFrequencies(JSON_RECORDS);
        Re2 utf8 = Re2.compile(Slices.utf8Slice("svc1://(\\w+)"), utf8Json);
        assertThat(utf8.forwardProgramForDiagnostics().canPrefixAccel()).isTrue();
        assertThat(utf8.forwardProgramForDiagnostics().prefixAccelStrategy(4_096)).isEqualTo(Prog.PrefixAccelStrategy.REPEATED_BYTE);
        assertThat(utf8.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(1);
        Re2 latin1 = Re2.compile(Slices.utf8Slice("svc1://(\\w+)"), latin1Json);
        assertThat(latin1.forwardProgramForDiagnostics().canPrefixAccel()).isTrue();
        assertThat(latin1.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(1);

        // A Latin-1 pattern is Latin-1 text, so ï is one byte there and two in UTF-8. The sample
        // lacks Latin-1 ï and the trailing UTF-8 byte of ï, though the UTF-8 lead byte it shares
        // with é is common, so the probe moves to the last byte of ï, at a different offset.
        Re2 utf8Accent = Re2.compile(Slices.utf8Slice("naïve=(\\w+)"), utf8Json);
        assertThat(utf8Accent.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(3);
        Re2 latin1Accent = Re2.compile(latin1Slice("naïve=(\\w+)"), latin1Json);
        assertThat(latin1Accent.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(2);

        assertThat(Re2.compile(Slices.utf8Slice("https://(\\S+)"), utf8Json).forwardProgramForDiagnostics().prefixAccelProbeOffset()).isZero();

        // Default options keep first-byte scanning.
        for (Re2.Options options : List.of(Re2.Options.defaults(), Re2.Options.latin1())) {
            Re2 service = Re2.compile(Slices.utf8Slice("svc1://(\\w+)"), options);
            assertThat(service.forwardProgramForDiagnostics().canPrefixAccel()).isTrue();
            assertThat(service.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isZero();
        }

        // A distribution in which punctuation is rare probes the colon.
        for (Re2.Options options : List.of(Re2.Options.defaults(), Re2.Options.latin1())) {
            Re2 url = Re2.compile(Slices.utf8Slice("https://(\\S+)"), options.setByteFrequencies(RARE_NON_LETTERS));
            assertThat(url.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(5);
        }
    }

    @Test
    public void testRangeBoundaries()
    {
        for (String value : LITERALS) {
            byte[] literal = value.getBytes(UTF_8);
            Prog program = configuredProgram(literal, false);
            for (int backingOffset : new int[] {0, 3}) {
                // An occurrence at the very start of the range puts the probe byte at
                // offset + probe.
                assertMatchAt(program, literal, 64, backingOffset, 0);
                assertMatchAt(program, literal, 64, backingOffset, 1);
                assertMatchAt(program, literal, 64, backingOffset, 64 - literal.length);
                assertMatchAt(program, literal, 64, backingOffset, 64 - literal.length - 1);
                // A haystack exactly as long as the prefix.
                assertMatchAt(program, literal, literal.length, backingOffset, 0);
                assertNoMatch(program, literal.length, backingOffset);
                assertNoMatch(program, literal.length - 1, backingOffset);
            }
        }
    }

    @Test
    public void testOccurrencesOutsideRange()
    {
        for (String value : LITERALS) {
            byte[] literal = value.getBytes(UTF_8);
            Prog program = configuredProgram(literal, false);
            int probe = program.prefixAccelProbeOffset();
            byte[] data = new byte[64];
            Arrays.fill(data, (byte) '#');
            // An occurrence starting one byte before the range has its probe byte, when that is
            // not the first byte, inside the range; no candidate may start before the range.
            System.arraycopy(literal, 0, data, 7, literal.length);
            assertThat(program.prefixAccel(data, 8, 40)).as(value).isEqualTo(-1);
            // An occurrence ending one byte past the range end.
            assertThat(program.prefixAccel(data, 0, 6 + literal.length)).as(value).isEqualTo(-1);
            assertThat(program.prefixAccel(data, 0, 7 + literal.length)).as(value).isEqualTo(7);
            // A lone probe byte before the range, followed by an occurrence inside it.
            Arrays.fill(data, (byte) '#');
            data[9] = literal[probe];
            System.arraycopy(literal, 0, data, 20, literal.length);
            assertThat(program.prefixAccel(data, 10, 50)).as(value).isEqualTo(20);
        }
    }

    @Test
    public void testOverlappingSelfSimilarLiterals()
    {
        assertThat(search("aab", "aaab", 0)).isEqualTo(1);
        assertThat(search("aab", "aaaaaab", 0)).isEqualTo(4);
        assertThat(search("abab", "ababab", 0)).isZero();
        assertThat(search("abab", "ababab", 1)).isEqualTo(2);
        assertThat(search("abab", "abaabab", 0)).isEqualTo(3);
        assertThat(search("aababc", "aabaababcaababc", 0)).isEqualTo(3);

        // Exact-literal count advances past each match, so overlapping occurrences count once.
        Re2.Options jsonRecords = Re2.Options.defaults().setByteFrequencies(JSON_RECORDS);
        assertThat(Re2.compile(Slices.utf8Slice("abab"), jsonRecords).count(Slices.utf8Slice("abababab"))).isEqualTo(2);
        assertThat(Re2.compile(Slices.utf8Slice("aab"), jsonRecords).count(Slices.utf8Slice("aaabaabxaab"))).isEqualTo(3);
    }

    @Test
    public void testMultipleMatchesInBothEncodings()
    {
        String text = "ts=1 user=alice x user= user=bob user=user=carol svc1://h/ svc1:/ svc1://";
        List<Re2.Options> optionsList = List.of(
                Re2.Options.defaults().setByteFrequencies(JSON_RECORDS),
                Re2.Options.latin1().setByteFrequencies(JSON_RECORDS));
        for (Re2.Options options : optionsList) {
            assertMatches(Re2.compile(Slices.utf8Slice("user=(\\w+)"), options), text, "user=alice", "user=bob", "user=user");
            assertMatches(Re2.compile(Slices.utf8Slice("svc1://"), options), text, "svc1://", "svc1://");
            assertThat(Re2.compile(Slices.utf8Slice("user="), options).count(Slices.utf8Slice(text))).isEqualTo(5);
            assertThat(Re2.compile(Slices.utf8Slice("user=(\\w+)"), options).count(Slices.utf8Slice(text))).isEqualTo(3);
        }

        // The rare-non-letters distribution moves the probe onto é, one byte in Latin-1 and two
        // in UTF-8.
        byte[] latin1Text = "x café=1 cafe=2 café=".getBytes(ISO_8859_1);
        Re2 latin1 = Re2.compile(latin1Slice("café=(\\w*)"), Re2.Options.latin1().setByteFrequencies(RARE_NON_LETTERS));
        assertThat(latin1.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(3);
        assertMatches(latin1, Slices.wrappedBuffer(latin1Text), List.of(new int[] {2, 8}, new int[] {16, 21}));
        byte[] utf8Text = "x café=1 cafe=2 café=".getBytes(UTF_8);
        Re2 utf8 = Re2.compile(Slices.utf8Slice("café=(\\w*)"), Re2.Options.defaults().setByteFrequencies(RARE_NON_LETTERS));
        assertThat(utf8.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(3);
        assertMatches(utf8, Slices.wrappedBuffer(utf8Text), List.of(new int[] {2, 9}, new int[] {17, 23}));
    }

    @Test
    public void testPrefixAccelMatchesReference()
    {
        Random random = new Random(17);
        boolean firstByteProbe = false;
        boolean letterProbe = false;
        boolean asciiNonLetterProbe = false;
        boolean nonAsciiProbe = false;
        for (ByteFrequencies frequencies : List.of(JSON_RECORDS, RARE_NON_LETTERS)) {
            for (String value : LITERALS) {
                byte[] literal = value.getBytes(UTF_8);
                Prog program = configuredProgram(literal, false, frequencies);
                // A first-byte probe and a later probe run separate scan loops.
                int probe = program.prefixAccelProbeOffset();
                firstByteProbe |= probe == 0;
                letterProbe |= probe > 0 && Character.isLetter(literal[probe]);
                asciiNonLetterProbe |= probe > 0 && literal[probe] >= 0 && !Character.isLetter(literal[probe]);
                nonAsciiProbe |= probe > 0 && literal[probe] < 0;
                // Text drawn from the literal's own bytes produces dense partial and overlapping
                // occurrences around the probe.
                byte[] alphabet = (value + "#").getBytes(UTF_8);
                for (int iteration = 0; iteration < 1_000; iteration++) {
                    byte[] data = new byte[random.nextInt(600) + 8];
                    for (int index = 0; index < data.length; index++) {
                        data[index] = random.nextInt(8) == 0 ? (byte) random.nextInt(256) : alphabet[random.nextInt(alphabet.length)];
                    }
                    if (data.length >= literal.length && random.nextBoolean()) {
                        System.arraycopy(literal, 0, data, random.nextInt(data.length - literal.length + 1), literal.length);
                    }
                    int offset = random.nextInt(4);
                    int length = data.length - offset - random.nextInt(4);
                    assertThat(program.prefixAccel(data, offset, length))
                            .as("%s at offset %s length %s", value, offset, length)
                            .isEqualTo(referenceSearch(data, offset, length, literal));
                }
            }
        }
        assertThat(firstByteProbe).isTrue();
        assertThat(letterProbe).isTrue();
        assertThat(asciiNonLetterProbe).isTrue();
        assertThat(nonAsciiProbe).isTrue();
    }

    @Test
    public void testPublicFindMatchesReference()
    {
        Random random = new Random(29);
        List<Re2.Options> optionsList = List.of(
                Re2.Options.defaults(),
                Re2.Options.latin1(),
                Re2.Options.defaults().setByteFrequencies(RARE_NON_LETTERS),
                Re2.Options.latin1().setByteFrequencies(RARE_NON_LETTERS));
        boolean asciiNonLetterProbe = false;
        boolean nonAsciiProbe = false;
        boolean trinoProbe = false;
        for (String value : List.of("svc1://", "content-type:", "user=", "error: ", "café=", "aab", "abab", "http")) {
            for (Re2.Options options : optionsList) {
                Re2 pattern = Re2.compile(Slices.utf8Slice(Re2.quote(Slices.utf8Slice(value)).toStringUtf8() + "[0-9]*"), options);
                byte[] literal = value.getBytes(UTF_8);
                int probe = pattern.forwardProgramForDiagnostics().prefixAccelProbeOffset();
                asciiNonLetterProbe |= probe > 0 && literal[probe] >= 0 && !Character.isLetter(literal[probe]);
                nonAsciiProbe |= probe > 0 && literal[probe] < 0;
                // A Trino pattern of the bare literal plans the exact-literal search.
                TrinoRegexp trino = TrinoRegexp.compile(Slices.utf8Slice(value), TrinoRegexp.Options.defaults().setByteFrequencies(options.byteFrequencies()));
                assertThat(trino.pattern().hasExactLiteralSpan()).isTrue();
                assertThat(trino.pattern().booleanPartialMatchStrategyForDiagnostics()).isEqualTo(Re2.BooleanPartialMatchStrategy.EXACT_LITERAL);
                assertThat(trino.pattern().forwardProgramForDiagnostics().prefixAccelProbeOffset()).as(value).isEqualTo(probe);
                assertThat(trino.pattern().exactLiteralSpanUsesPrefixScan(63)).as(value).isEqualTo(probe > 0);
                assertThat(trino.pattern().exactLiteralSpanUsesPrefixScan(64)).as(value).isTrue();
                trinoProbe |= probe > 0;
                byte[] alphabet = (value + "0123").getBytes(UTF_8);
                for (int iteration = 0; iteration < 200; iteration++) {
                    byte[] data = new byte[random.nextInt(300)];
                    for (int index = 0; index < data.length; index++) {
                        data[index] = alphabet[random.nextInt(alphabet.length)];
                    }
                    if (data.length >= literal.length && random.nextBoolean()) {
                        System.arraycopy(literal, 0, data, random.nextInt(data.length - literal.length + 1), literal.length);
                    }
                    Slice input = Slices.wrappedBuffer(data);
                    List<int[]> expected = referenceMatches(data, literal, true);
                    assertMatches(pattern, input, expected);
                    assertThat(pattern.count(input)).isEqualTo(expected.size());
                    List<int[]> literalMatches = referenceMatches(data, literal, false);
                    assertThat(trino.count(input)).isEqualTo(literalMatches.size());
                    assertThat(trino.replace(input, Slices.EMPTY_SLICE)).isEqualTo(withoutMatches(data, literalMatches));
                }
            }
        }
        assertThat(asciiNonLetterProbe).isTrue();
        assertThat(nonAsciiProbe).isTrue();
        assertThat(trinoProbe).isTrue();
    }

    private static int search(String literal, String text, int offset)
    {
        byte[] data = text.getBytes(UTF_8);
        return configuredProgram(literal.getBytes(UTF_8), false).prefixAccel(data, offset, data.length - offset);
    }

    private static void assertMatches(Re2 pattern, String text, String... expected)
    {
        Re2Matcher matcher = pattern.matcher(Slices.utf8Slice(text));
        List<String> actual = new ArrayList<>();
        while (matcher.find()) {
            actual.add(matcher.group().toStringUtf8());
        }
        assertThat(actual).containsExactly(expected);
    }

    private static void assertMatches(Re2 pattern, Slice input, List<int[]> expected)
    {
        Re2Matcher matcher = pattern.matcher(input);
        for (int[] match : expected) {
            assertThat(matcher.find()).isTrue();
            assertThat(new int[] {matcher.start(), matcher.end()}).containsExactly(match);
        }
        assertThat(matcher.find()).isFalse();
    }

    /// Matches of `literal`, each extended by the longest following run of ASCII digits when
    /// `digitRun` is set, in order and without overlap.
    private static List<int[]> referenceMatches(byte[] data, byte[] literal, boolean digitRun)
    {
        List<int[]> matches = new ArrayList<>();
        int position = 0;
        while ((position = referenceSearch(data, position, data.length - position, literal)) >= 0) {
            int end = position + literal.length;
            while (digitRun && end < data.length && data[end] >= '0' && data[end] <= '9') {
                end++;
            }
            matches.add(new int[] {position, end});
            position = end;
        }
        return matches;
    }

    private static Slice withoutMatches(byte[] data, List<int[]> matches)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int position = 0;
        for (int[] match : matches) {
            output.write(data, position, match[0] - position);
            position = match[1];
        }
        output.write(data, position, data.length - position);
        return Slices.wrappedBuffer(output.toByteArray());
    }

    private static int referenceSearch(byte[] data, int offset, int length, byte[] literal)
    {
        int lastStart = offset + length - literal.length;
        for (int position = offset; position <= lastStart; position++) {
            if (Arrays.equals(data, position, position + literal.length, literal, 0, literal.length)) {
                return position;
            }
        }
        return -1;
    }

    private static void assertMatchAt(Prog program, byte[] prefix, int length, int backingOffset, int matchPosition)
    {
        byte[] data = new byte[backingOffset + length + 5];
        Arrays.fill(data, (byte) '#');
        System.arraycopy(prefix, 0, data, backingOffset + matchPosition, prefix.length);
        assertThat(program.prefixAccel(data, backingOffset, length)).isEqualTo(backingOffset + matchPosition);
    }

    private static void assertNoMatch(Prog program, int length, int backingOffset)
    {
        byte[] data = new byte[backingOffset + length + 5];
        Arrays.fill(data, (byte) '#');
        assertThat(program.prefixAccel(data, backingOffset, length)).isEqualTo(-1);
    }

    private static Slice latin1Slice(String value)
    {
        return Slices.wrappedBuffer(value.getBytes(ISO_8859_1));
    }

    /// Configures `prefix` under [TestingByteFrequencies#learnedJsonRecords()], which
    /// moves the probes of several [#LITERALS] off their first byte.
    private static Prog configuredProgram(byte[] prefix, boolean foldCase)
    {
        return configuredProgram(prefix, foldCase, JSON_RECORDS);
    }

    private static Prog configuredProgram(byte[] prefix, boolean foldCase, ByteFrequencies frequencies)
    {
        Prog program = new Prog();
        program.configurePrefixAccel(Slices.wrappedBuffer(prefix), foldCase, frequencies);
        return program;
    }
}
