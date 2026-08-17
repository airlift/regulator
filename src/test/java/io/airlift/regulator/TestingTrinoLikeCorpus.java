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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;

import static java.nio.charset.StandardCharsets.UTF_8;

final class TestingTrinoLikeCorpus
{
    private static final HexFormat HEX_FORMAT = HexFormat.of();
    private static final String RESOURCE = "/io/airlift/regulator/trino-like-corpus.tsv";

    private TestingTrinoLikeCorpus() {}

    static List<TestCase> load()
    {
        InputStream input = TestingTrinoLikeCorpus.class.getResourceAsStream(RESOURCE);
        if (input == null) {
            throw new IllegalStateException("missing resource: " + RESOURCE);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF_8))) {
            List<TestCase> cases = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\\t", -1);
                if (columns.length != 6) {
                    throw new IllegalArgumentException("invalid Trino LIKE corpus row: " + line);
                }
                cases.add(new TestCase(
                        columns[0],
                        HEX_FORMAT.parseHex(columns[1]),
                        columns[2].equals("-") ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(columns[2], 16)),
                        HEX_FORMAT.parseHex(columns[3]),
                        Outcome.parse(columns[4]),
                        Outcome.parse(columns[5])));
            }
            return List.copyOf(cases);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    enum Outcome
    {
        MATCH,
        NO_MATCH,
        ERROR;

        static Outcome parse(String value)
        {
            return switch (value) {
                case "true" -> MATCH;
                case "false" -> NO_MATCH;
                case "error" -> ERROR;
                default -> throw new IllegalArgumentException("unknown Trino LIKE outcome: " + value);
            };
        }
    }

    record TestCase(
            String name,
            byte[] pattern,
            OptionalInt escapeCodePoint,
            byte[] input,
            Outcome sqlOutcome,
            Outcome optimizedOutcome)
    {
        TestCase
        {
            pattern = pattern.clone();
            input = input.clone();
        }

        @Override
        public byte[] pattern()
        {
            return pattern.clone();
        }

        @Override
        public byte[] input()
        {
            return input.clone();
        }
    }
}
