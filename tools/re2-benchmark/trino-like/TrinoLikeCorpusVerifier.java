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

import io.trino.likematcher.LikeMatcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class TrinoLikeCorpusVerifier
{
    private static final HexFormat HEX_FORMAT = HexFormat.of();
    private static final int PADDING = 13;

    private TrinoLikeCorpusVerifier() {}

    public static void main(String[] args)
            throws Exception
    {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: TrinoLikeCorpusVerifier <corpus.tsv>");
        }

        int caseCount = 0;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            verify(line);
            caseCount++;
        }
        System.out.println("Verified " + caseCount + " Trino LIKE corpus cases");
    }

    private static void verify(String line)
    {
        String[] columns = line.split("\\t", -1);
        if (columns.length != 6) {
            throw new IllegalArgumentException("invalid corpus row: " + line);
        }

        String name = columns[0];
        String pattern = new String(HEX_FORMAT.parseHex(columns[1]), UTF_8);
        Optional<Character> escape = columns[2].equals("-")
                ? Optional.empty()
                : Optional.of((char) Integer.parseInt(columns[2], 16));
        byte[] input = HEX_FORMAT.parseHex(columns[3]);
        verifyMode(name, pattern, escape, input, false, columns[4]);
        verifyMode(name, pattern, escape, input, true, columns[5]);
    }

    private static void verifyMode(String name, String pattern, Optional<Character> escape, byte[] input, boolean optimized, String expected)
    {
        try {
            LikeMatcher matcher = LikeMatcher.compile(pattern, escape, optimized);
            if (expected.equals("error")) {
                throw new AssertionError(name + " expected compilation to fail in optimized=" + optimized);
            }

            boolean expectedResult = Boolean.parseBoolean(expected);
            boolean result = matcher.match(input);
            if (result != expectedResult) {
                throw new AssertionError(name + " expected " + expectedResult + " but was " + result + " in optimized=" + optimized);
            }

            byte[] padded = new byte[input.length + PADDING * 2];
            Arrays.fill(padded, (byte) '!');
            System.arraycopy(input, 0, padded, PADDING, input.length);
            boolean offsetResult = matcher.match(padded, PADDING, input.length);
            if (offsetResult != expectedResult) {
                throw new AssertionError(name + " offset result differed in optimized=" + optimized);
            }
        }
        catch (IllegalArgumentException e) {
            if (!expected.equals("error")) {
                throw new AssertionError(name + " unexpectedly failed compilation in optimized=" + optimized, e);
            }
        }
    }
}
