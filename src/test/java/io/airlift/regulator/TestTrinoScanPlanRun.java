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

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class TestTrinoScanPlanRun
{
    @Test
    public void testRunMinimumReadsOnlyTheMinimum()
    {
        // Most runs would continue through this tail; the minimum check must not.
        String tail = "y".repeat(4096);
        for (String expression : List.of("[^/]+", "[^/]{3,}", "[^/]{2,5}", "[a-z]{2,}", "(?s).*", "[^/]*", "[^/]?", "[^/]", "[0-9]+")) {
            TrinoScanPlanRun run = requireNonNull(TrinoScanPlanRun.analyze(TrinoRegexpParser.parse(utf8Slice(expression), Regexp.LIKE_PERL).regexp()), expression);
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
        TrinoScanPlanRun run = requireNonNull(TrinoScanPlanRun.analyze(TrinoRegexpParser.parse(utf8Slice("[^/]{3,}"), Regexp.LIKE_PERL).regexp()));
        byte[] input = "xabc".getBytes(UTF_8);
        assertThat(run.matchMinimum(input, 1, 3)).isEqualTo(-1);
        assertThat(run.matchMinimum(input, 0, 3)).isEqualTo(3);
    }

    @Test
    public void testRunComplementMatchesMemberSet()
    {
        // A run is the complement of a byte only when that byte is its single non-member. The
        // negated classes hold line feeds and every non-ASCII byte without dotAll.
        for (String set : List.of("[^/]", "[^:]", "[^,]", "[^\\n]", "[^/x]", "[^=]", "[^\\x00-\\x7F]", "\\w", "[a]", "[\\x00-\\x7F]", ".", "(?s).")) {
            ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(set + "+"), Regexp.LIKE_PERL);
            TrinoScanPlanRun run = requireNonNull(TrinoScanPlanRun.analyze(parsed.regexp()), set);
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
        // set, and larger sets and complements that keep the table scan.
        for (String set : List.of("[a]", "[?&]", "[abz]", "[0-9]", "[0-9a]", "[a-z@]", "\\w", "[\\x00-\\x3F]", "[\\x00-\\x7F]", "[^=]", "[^/:?#]", "[^\\x00-\\x7F]", ".")) {
            ParseResult parsed = TrinoRegexpParser.parse(utf8Slice("(?s)" + set + "+"), Regexp.LIKE_PERL);
            TrinoScanPlanRun run = requireNonNull(TrinoScanPlanRun.analyze(parsed.regexp()), set);
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
