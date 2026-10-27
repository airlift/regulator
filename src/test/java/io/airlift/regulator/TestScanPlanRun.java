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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.regex.Pattern;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class TestScanPlanRun
{
    @Test
    public void testRunMinimumReadsOnlyTheMinimum()
    {
        // Most runs would continue through this tail; the minimum check must not.
        String tail = "y".repeat(4096);
        for (String expression : List.of("[^/]+", "[^/]{3,}", "[^/]{2,5}", "[a-z]{2,}", "(?s).*", "[^/]*", "[^/]?", "[^/]", "[0-9]+")) {
            ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(expression), Regexp.LIKE_PERL);
            ScanPlanRun run = requireNonNull(ScanPlanRun.analyze(parsed.regexp(), parsed.caseFolding(), false), expression);
            for (String text : List.of("", "a", "ab", "abc", "é", "é😀x", "例え", "😀😀😀😀", "a/", "/", "ab/c", "A", "1")) {
                String value = text + tail;
                byte[] input = ("!" + value + "!").getBytes(UTF_8);
                int end = input.length - 1;
                String description = expression + " on " + text;
                int stop = run.matchMinimum(input, 1, end);
                assertThat(stop < 0).as(description).isEqualTo(run.match(input, 1, end) < 0);
                if (stop >= 0) {
                    String minimum = value.substring(0, value.offsetByCodePoints(0, run.minimum()));
                    assertThat(stop).as(description).isEqualTo(1 + minimum.getBytes(UTF_8).length);
                }
            }
        }
        // A bounded view cannot take its minimum from bytes after the view.
        ParseResult parsed = TrinoRegexpParser.parse(utf8Slice("[^/]{3,}"), Regexp.LIKE_PERL);
        ScanPlanRun run = requireNonNull(ScanPlanRun.analyze(parsed.regexp(), parsed.caseFolding(), false));
        byte[] input = "xabc".getBytes(UTF_8);
        assertThat(run.matchMinimum(input, 1, 3)).isEqualTo(-1);
        assertThat(run.matchMinimum(input, 0, 3)).isEqualTo(3);
    }

    @Test
    public void testSetRunReadsCharactersAsTheOrdinaryEngine()
    {
        // A run of some non-ASCII characters but not all reads one character as the ordinary
        // engine's byte ranges for the class do: on every sequence of one or two bytes, every
        // three-byte sequence whose lead is at or above E0 and whose other bytes are continuation
        // bytes or a sample of others, and random and edge four-byte sequences, malformed UTF-8
        // included.
        List<Byte> following = new ArrayList<>();
        for (int value = 0x80; value <= 0xBF; value++) {
            following.add((byte) value);
        }
        for (int value : new int[] {0x00, 0x41, 0x7F, 0xC0, 0xC2, 0xE2, 0xF0, 0xFF}) {
            following.add((byte) value);
        }
        List<byte[]> sequences = new ArrayList<>();
        for (int first = 0; first < 256; first++) {
            sequences.add(new byte[] {(byte) first});
            for (int second = 0; second < 256; second++) {
                sequences.add(new byte[] {(byte) first, (byte) second});
            }
        }
        for (int first = 0xE0; first < 256; first++) {
            for (byte second : following) {
                for (byte third : following) {
                    sequences.add(new byte[] {(byte) first, second, third});
                }
            }
        }
        Random random = new Random(0x10FFFF);
        for (int iteration = 0; iteration < 20_000; iteration++) {
            byte[] sequence = {(byte) (0xF0 + random.nextInt(16)), 0, 0, 0};
            for (int index = 1; index < sequence.length; index++) {
                sequence[index] = following.get(random.nextInt(following.size()));
            }
            sequences.add(sequence);
        }
        // The four-byte edges: the first character, the last two, and the first value past them.
        for (String hex : List.of("f0908080", "f48fbfbe", "f48fbfbf", "f4908080")) {
            sequences.add(HexFormat.of().parseHex(hex));
        }
        for (SetCase set : List.of(
                // Java's default dot excludes U+0085, U+2028, and U+2029 as well as LF and CR.
                java("."),
                java("(?iu)k"),
                rust("\\w"),
                rust("\\d"),
                rust("\\s"),
                rust("(?i)s"),
                re2("[é😀]"),
                re2("[^/\\x{85}]"),
                // Every non-ASCII character but a few, or but the surrogates: most leads are full,
                // and the exclusions include the first or last character of a width.
                re2("[^\\x{85}\\x{7FF}\\x{FFFD}\\x{10FFFF}]"),
                re2("[\\x{80}-\\x{D7FF}\\x{E000}-\\x{10FFFF}]"),
                re2("[^\\x{85}\\x{7FF}\\x{800}\\x{FFFD}\\x{10000}]"),
                // Encoded surrogates are members of a class that holds them, and a class that
                // holds only some of them keeps lead ED in the trie.
                re2("[\\x{800}-\\x{FFFF}]"),
                re2("[\\x{D000}-\\x{D800}]"),
                re2("[\\x{10000}-\\x{10FFFF}]"),
                // Ranges across the edges of encoding widths and of the surrogates, and every
                // character but the last.
                re2("[\\x{7FF}-\\x{800}]"),
                re2("[\\x{FFFF}-\\x{10000}]"),
                re2("[\\x{D7FF}-\\x{E000}]"),
                re2("[\\x{80}-\\x{10FFFE}]"),
                re2("[^\\x{10FFFF}]"))) {
            ParseResult parsed = set.parsed();
            ScanPlanRun run = requireNonNull(ScanPlanRun.analyze(parsed.regexp(), parsed.caseFolding(), true), set.name());
            assertThat(run.matchesSomeNonAscii()).as(set.name()).isTrue();
            for (byte[] sequence : sequences) {
                String description = set.name() + " on " + HexFormat.of().formatHex(sequence);
                MatchResult result = set.ordinary().lookingAtResult(wrappedBuffer(sequence));
                int expectedStop = result == null ? -1 : result.end();
                int stop = run.re2SetMatch(sequence, 0, sequence.length);
                assertThat(stop).as(description).isEqualTo(expectedStop);
                // Generated kernels read the set by a copy of the run's reader.
                assertThat(ScanKernelSupport.re2SetRunMatch(run, sequence, 0, sequence.length)).as("kernel " + description).isEqualTo(expectedStop);
                if (stop > 0) {
                    // Each character the run reads starts with a member byte.
                    assertThat(run.members()[sequence[0] & 0xFF]).as(description).isTrue();
                }
            }
            // A continuation byte starts no character, and no run of such a set is the
            // complement of one byte.
            for (int value = 0x80; value <= 0xBF; value++) {
                assertThat(run.members()[value]).as("%s byte %s", set.name(), value).isFalse();
            }
            ParseResult unbounded = set.parse(set.name() + "+");
            ScanPlanRun unboundedRun = requireNonNull(ScanPlanRun.analyze(unbounded.regexp(), unbounded.caseFolding(), true), set.name());
            assertThat(unboundedRun.unboundedScanTerminator()).as(set.name()).isEqualTo(-1);
            for (int value = 0; value < 256; value++) {
                assertThat(unboundedRun.isComplementOf((byte) value)).as("%s byte %s", set.name(), value).isFalse();
            }
            // Trino reads every non-ASCII byte alike, so such a set is not a Trino run.
            assertThat(ScanPlanRun.analyze(parsed.regexp(), parsed.caseFolding(), false)).as(set.name()).isNull();
        }
        // Interned blocks and masks keep the member table of Rust's \w near 12 KB.
        ParseResult word = rust("\\w").parsed();
        Utf8MemberTable wordTable = requireNonNull(ScanPlanRun.analyze(word.regexp(), word.caseFolding(), true)).nonAsciiMembers();
        assertThat(wordTable.estimatedRetainedSize()).isLessThan(16 * 1024);
    }

    private record SetCase(String name, Function<String, ParseResult> parser, Re2 ordinary)
    {
        ParseResult parsed()
        {
            return parse(name);
        }

        ParseResult parse(String expression)
        {
            return parser.apply(expression);
        }
    }

    private static SetCase re2(String set)
    {
        Re2.Options options = Re2.Options.defaults();
        return new SetCase(set, expression -> RegexpParser.parse(utf8Slice(expression), options.parseFlags()), Re2.compileWithoutScanPlanForTesting(utf8Slice(set), options));
    }

    private static SetCase java(String set)
    {
        JavaRegexp.Options options = JavaRegexp.Options.defaults();
        return new SetCase(set, expression -> JavaRegexpParser.parse(utf8Slice(expression), options.parseFlags()), JavaRegexp.compileWithoutScanPlanForTesting(utf8Slice(set), options).pattern());
    }

    private static SetCase rust(String set)
    {
        RustRegexp.Options options = RustRegexp.Options.defaults();
        return new SetCase(
                set,
                expression -> RustRegexpParser.parse(utf8Slice(expression), options.syntaxFlags(), options.nestLimit()),
                RustRegexp.compileWithoutScanPlanForTesting(utf8Slice(set), options).pattern());
    }

    @Test
    public void testRunComplementMatchesMemberSet()
    {
        // A run is the complement of a byte only when that byte is its single non-member. The
        // negated classes hold line feeds and every non-ASCII byte without dotAll.
        for (String set : List.of("[^/]", "[^:]", "[^,]", "[^\\n]", "[^/x]", "[^=]", "[^\\x00-\\x7F]", "\\w", "[a]", "[\\x00-\\x7F]", ".", "(?s).")) {
            ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(set + "+"), Regexp.LIKE_PERL);
            ScanPlanRun run = requireNonNull(ScanPlanRun.analyze(parsed.regexp(), parsed.caseFolding(), false), set);
            // Only a line feed ends a line in this dialect.
            Pattern reference = Pattern.compile(set, Pattern.UNIX_LINES);
            int excluded = -1;
            int nonMembers = 0;
            for (int value = 0; value < 256; value++) {
                // Every non-ASCII byte shares the membership of non-ASCII code points.
                String character = value < 0x80 ? String.valueOf((char) value) : "é";
                if (!reference.matcher(character).matches()) {
                    excluded = value;
                    nonMembers++;
                }
            }
            for (int value = 0; value < 256; value++) {
                assertThat(run.isComplementOf((byte) value)).as("%s byte %s", set, value).isEqualTo(nonMembers == 1 && value == excluded);
            }
        }
    }

    @Test
    public void testRunCandidateScanMatchesScalarReference()
    {
        // Covers each candidate scan: one to three bytes, a contiguous range, a nibble-table
        // set, and larger sets and complements that keep the table scan. The generated kernels'
        // copy must agree.
        for (String set : List.of("[a]", "[?&]", "[abz]", "[0-9]", "[0-9a]", "[a-z@]", "\\w", "[\\x00-\\x3F]", "[\\x00-\\x7F]", "[^=]", "[^/:?#]", "[^\\x00-\\x7F]", ".")) {
            ParseResult parsed = TrinoRegexpParser.parse(utf8Slice("(?s)" + set + "+"), Regexp.LIKE_PERL);
            ScanPlanRun run = requireNonNull(ScanPlanRun.analyze(parsed.regexp(), parsed.caseFolding(), false), set);
            Pattern reference = Pattern.compile("(?s)" + set);
            boolean[] members = new boolean[256];
            int filler = -1;
            for (int value = 0; value < 256; value++) {
                // Every non-ASCII byte shares the membership of non-ASCII code points.
                String character = value < 0x80 ? String.valueOf((char) value) : "é";
                members[value] = reference.matcher(character).matches();
                if (!members[value] && filler < 0) {
                    filler = value;
                }
            }
            byte[] bytes = new byte[200];
            for (int value = 0; value < 256; value++) {
                for (int cursor : new int[] {0, 1, 7, 15, 16, 17, 31, 32, 33}) {
                    for (int length : new int[] {0, 1, 2, 3, 15, 16, 17, 31, 32, 33, 63, 64, 65, 129}) {
                        for (int position = Math.max(0, cursor - 1); position <= cursor + length && position < bytes.length; position++) {
                            Arrays.fill(bytes, (byte) (filler < 0 ? 0 : filler));
                            bytes[position] = (byte) value;
                            int end = cursor + length;
                            if (filler < 0 && length == 0) {
                                // Callers never scan an empty range; a set of every byte answers the cursor.
                                continue;
                            }
                            int expected = filler < 0 ? cursor : (members[value] && position >= cursor && position < end ? position : -1);
                            assertThat(run.findCandidate(bytes, cursor, end))
                                    .as("%s byte %s cursor %s length %s position %s", set, value, cursor, length, position)
                                    .isEqualTo(expected);
                            assertThat(ScanKernelSupport.runFindCandidate(run, bytes, cursor, end))
                                    .as("generated %s byte %s cursor %s length %s position %s", set, value, cursor, length, position)
                                    .isEqualTo(expected);
                        }
                    }
                }
            }
            Random random = new Random(set.hashCode());
            for (int iteration = 0; iteration < 2000; iteration++) {
                for (int index = 0; index < bytes.length; index++) {
                    bytes[index] = (byte) (random.nextInt(4) == 0 ? random.nextInt(256) : (filler < 0 ? 0 : filler));
                }
                int cursor = random.nextInt(bytes.length);
                int end = cursor + 1 + random.nextInt(bytes.length - cursor);
                int expected = -1;
                for (int index = cursor; index < end; index++) {
                    if (members[bytes[index] & 0xFF]) {
                        expected = index;
                        break;
                    }
                }
                assertThat(run.findCandidate(bytes, cursor, end)).as("%s random %s", set, iteration).isEqualTo(expected);
            }
        }
    }
}
