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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

/// Reads the Trino LIKE corpus from standard input and writes it to standard
/// output with each row's two result columns replaced by what Trino's
/// [LikeMatcher] does: `true` or `false` for a match result, or `error` when
/// the pattern does not compile. Comment and blank lines, row order, and the
/// first four columns are copied unchanged.
public final class TrinoLikeCorpusGenerator
{
    private static final HexFormat HEX_FORMAT = HexFormat.of();
    private static final int PADDING = 13;

    private TrinoLikeCorpusGenerator() {}

    public static void main(String[] args)
            throws IOException
    {
        if (args.length != 0) {
            throw new IllegalArgumentException("usage: TrinoLikeCorpusGenerator < corpus.tsv > generated.tsv");
        }

        int caseCount = 0;
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, UTF_8));
        Writer writer = new BufferedWriter(new OutputStreamWriter(System.out, UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.startsWith("#")) {
                writer.write(line);
            }
            else {
                writer.write(generate(line));
                caseCount++;
            }
            writer.write('\n');
        }
        writer.flush();
        // System.out swallows write errors, so check for one before the script moves the output over the corpus.
        if (System.out.checkError()) {
            throw new IOException("failed to write the corpus to standard output");
        }
        System.err.println("Generated " + caseCount + " Trino LIKE corpus cases");
    }

    private static String generate(String line)
    {
        String[] columns = line.split("\\t", -1);
        if (columns.length != 6) {
            throw new IllegalArgumentException("corpus row has %s columns, expected 6: %s".formatted(columns.length, line));
        }

        String name = columns[0];
        // Trino SQL decodes the pattern slice to a String as UTF-8, replacing malformed bytes with U+FFFD.
        String pattern = new String(parseHex(name, "pattern", columns[1]), UTF_8);
        Optional<Character> escape = columns[2].equals("-") ? Optional.empty() : Optional.of(parseEscape(name, columns[2]));
        byte[] input = parseHex(name, "input", columns[3]);
        return String.join("\t",
                name,
                columns[1],
                columns[2],
                columns[3],
                outcome(name, pattern, escape, input, false),
                outcome(name, pattern, escape, input, true));
    }

    private static byte[] parseHex(String name, String column, String hex)
    {
        try {
            return HEX_FORMAT.parseHex(hex);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("%s %s is not hexadecimal: %s".formatted(name, column, hex), e);
        }
    }

    private static char parseEscape(String name, String codePoint)
    {
        int escape;
        try {
            escape = Integer.parseUnsignedInt(codePoint, 16);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("%s escape is not a hexadecimal code point: %s".formatted(name, codePoint), e);
        }
        // LikeMatcher takes the escape as a single char, which cannot hold a supplementary code point. Trino SQL
        // decodes the escape from UTF-8, so it never passes a surrogate.
        if (!Character.isBmpCodePoint(escape) || Character.isSurrogate((char) escape)) {
            throw new IllegalArgumentException("%s escape U+%04X is not a character Trino SQL can pass".formatted(name, escape));
        }
        return (char) escape;
    }

    private static String outcome(String name, String pattern, Optional<Character> escape, byte[] input, boolean optimized)
    {
        LikeMatcher matcher;
        try {
            matcher = LikeMatcher.compile(pattern, escape, optimized);
        }
        catch (IllegalArgumentException e) {
            return "error";
        }

        boolean result = matcher.match(input);

        // The corpus records one result per row, so the input must match the same at a nonzero offset.
        byte[] padded = new byte[input.length + PADDING * 2];
        Arrays.fill(padded, (byte) '!');
        System.arraycopy(input, 0, padded, PADDING, input.length);
        if (matcher.match(padded, PADDING, input.length) != result) {
            throw new IllegalStateException(name + " offset result differed in optimized=" + optimized);
        }
        return Boolean.toString(result);
    }
}
