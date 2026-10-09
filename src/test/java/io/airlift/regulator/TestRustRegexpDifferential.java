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
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class TestRustRegexpDifferential
{
    @Test
    public void testPinnedRustRegex()
            throws IOException
    {
        List<String> mismatches = new ArrayList<>();
        int cases = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                requireNonNull(getClass().getResourceAsStream("rust-golden.tsv")), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t");
                Slice pattern = decode(fields[1]);
                Slice input = decode(fields[2]);
                String expected = fields[3];
                String actual;
                try {
                    RustRegexp regexp = RustRegexp.compile(pattern);
                    actual = matches(regexp.matcher(input));
                    if (!expected.equals("ERROR")) {
                        if (regexp.find(input) != !expected.equals("NONE")) {
                            mismatches.add(fields[0] + " boolean search differs");
                        }
                        if (regexp.count(input) != (expected.equals("NONE") ? 0 : expected.split(";").length)) {
                            mismatches.add(fields[0] + " count differs");
                        }
                    }
                }
                catch (RegexpParseException exception) {
                    actual = "ERROR";
                }
                if (!actual.equals(expected)) {
                    mismatches.add(fields[0] + " pattern=" + pattern.toStringUtf8() + " input=" + fields[2] +
                            " expected=" + expected + " actual=" + actual);
                }
                cases++;
            }
        }
        assertThat(cases).isGreaterThan(5000);
        assertThat(mismatches).isEmpty();
    }

    private static Slice decode(String hex)
    {
        return hex.equals("-") ? EMPTY_SLICE : wrappedBuffer(HexFormat.of().parseHex(hex));
    }

    private static String matches(RustRegexpMatcher matcher)
    {
        List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            List<String> groups = new ArrayList<>();
            for (int group = 0; group <= matcher.groupCount(); group++) {
                groups.add(matcher.start(group) + ":" + matcher.end(group));
            }
            matches.add(String.join(",", groups));
        }
        return matches.isEmpty() ? "NONE" : String.join(";", matches);
    }
}
