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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.zip.GZIPInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * The rows and literals of the normative Trino row workloads, shared by {@code BenchmarkTrinoRows},
 * {@code BenchmarkTrinoLikeRows}, and their Joni and Trino LIKE twins so all of them time the same
 * cells. Each workload's rows and literals come from {@code trino-row-workloads.tsv} and
 * {@code trino-row-literals.tsv}, which {@code tools/re2-benchmark/trino-rows/trino_rows.py}
 * generates from pinned corpora; see its README for the sampling and literal rules. The
 * OpenSubtitles workloads read test resources; the others read a corpus directory provisioned by
 * the generator's {@code fetch} command.
 */
public final class TestingTrinoRowsBenchmarkInputs
{
    public static final String MISSING_CORPUS_DIRECTORY = "required-corpus-directory";

    private static final String WORKLOADS_RESOURCE = "/io/airlift/regulator/trino-row-workloads.tsv";
    private static final String LITERALS_RESOURCE = "/io/airlift/regulator/trino-row-literals.tsv";
    private static final long SHUFFLE_SEED = 20261015L;
    private static final List<Map<String, String>> WORKLOAD_ROWS = manifestRows(WORKLOADS_RESOURCE);
    private static final List<Map<String, String>> LITERAL_ROWS = manifestRows(LITERALS_RESOURCE);

    private TestingTrinoRowsBenchmarkInputs() {}

    /**
     * Returns the IDs of the workload's literals in manifest order, without its empty cells.
     */
    public static List<String> literalIds(String workload)
    {
        return literalRows().stream()
                .filter(row -> row.get("workload").equals(workload) && !row.get("pattern").isEmpty())
                .map(row -> row.get("literal_id"))
                .toList();
    }

    /**
     * Returns whether the workload reads a corpus from the corpus directory rather than from test
     * resources.
     */
    public static boolean readsCorpusDirectory(String workload)
    {
        return Arrays.stream(workloadRow(workload).get("sources").split(","))
                .anyMatch(source -> !source.startsWith("/"));
    }

    /**
     * Returns the cell's literals, its pattern, and the workload's sampled rows, verified against the
     * manifest's population sizes and checksum, shuffled and laid out back to back in one buffer, as
     * a Trino block holds them. A cell without a literal fails.
     */
    public static Input create(String workload, String literalId, String corpusDirectory)
            throws IOException
    {
        Map<String, String> literalRow = literalRow(workload, literalId);
        if (literalRow.get("pattern").isEmpty()) {
            throw new IllegalArgumentException("Workload %s has no literal in cell %s".formatted(workload, literalId));
        }
        List<byte[]> shuffled = loadRows(workloadRow(workload), corpusDirectory);
        Collections.shuffle(shuffled, new Random(SHUFFLE_SEED));
        Slice buffer = Slices.wrappedBuffer(concat(shuffled));
        List<Slice> sources = new ArrayList<>(shuffled.size());
        int offset = 0;
        for (byte[] row : shuffled) {
            sources.add(buffer.slice(offset, row.length));
            offset += row.length;
        }
        return new Input(
                workload,
                literalId,
                literals(literalRow),
                Slices.utf8Slice(literalRow.get("pattern")),
                List.copyOf(sources),
                Long.parseLong(literalRow.get("hit_rows")),
                Long.parseLong(literalRow.get("matches")));
    }

    /**
     * Returns the literals a cell searches: one literal, or the two of an ordered cell, which its
     * pattern finds in order.
     */
    static List<Slice> literals(Map<String, String> literalRow)
    {
        return literalRow.get("second_literal").isEmpty()
                ? List.of(Slices.utf8Slice(literalRow.get("literal")))
                : List.of(Slices.utf8Slice(literalRow.get("literal")), Slices.utf8Slice(literalRow.get("second_literal")));
    }

    /**
     * Returns the LIKE pattern {@code %literal%}, or {@code %a%b%} for an ordered cell, which finds
     * the literals in order anywhere in a row without an escape character. A literal containing
     * {@code _}, {@code %}, or {@code \} fails, because the pattern would need an escape character.
     */
    static Slice likePattern(String workload, String literalId, List<Slice> literals)
    {
        StringBuilder pattern = new StringBuilder("%");
        for (Slice literal : literals) {
            String text = literal.toStringUtf8();
            if (text.chars().anyMatch(character -> character == '_' || character == '%' || character == '\\')) {
                throw new IllegalArgumentException("Literal %s/%s, %s, contains a LIKE metacharacter".formatted(workload, literalId, text));
            }
            pattern.append(text).append('%');
        }
        return Slices.utf8Slice(pattern.toString());
    }

    public record Input(String workload, String literalId, List<Slice> literals, Slice pattern, List<Slice> sources, long hitRows, long matches)
    {
        /**
         * Returns the LIKE pattern {@code %literal%}, or {@code %a%b%} for an ordered cell. A
         * literal containing {@code _}, {@code %}, or {@code \} fails.
         */
        public Slice likePattern()
        {
            return TestingTrinoRowsBenchmarkInputs.likePattern(workload, literalId, literals);
        }

        /**
         * Checks that an engine finds the cell's pattern in the manifest's number of rows, with its
         * number of non-overlapping matches.
         */
        public void verifyHits(Predicate<Slice> contains, ToLongFunction<Slice> count)
        {
            long actualHitRows = 0;
            long actualMatches = 0;
            for (Slice source : sources) {
                actualHitRows += contains.test(source) ? 1 : 0;
                actualMatches += count.applyAsLong(source);
            }
            if (actualHitRows != hitRows || actualMatches != matches) {
                throw new IllegalStateException("Literal %s/%s found %s rows and %s matches, expected %s and %s".formatted(
                        workload, literalId, actualHitRows, actualMatches, hitRows, matches));
            }
        }

        /**
         * Checks that a LIKE pattern finds the cell's literals in the manifest's number of rows.
         */
        public void verifyHitRows(Predicate<Slice> like)
        {
            long actualHitRows = sources.stream().filter(like).count();
            if (actualHitRows != hitRows) {
                throw new IllegalStateException("Literal %s/%s found %s rows, expected %s".formatted(workload, literalId, actualHitRows, hitRows));
            }
        }
    }

    /**
     * Returns the workload's sampled rows in manifest order: the stride sample of each corpus, in
     * pooling order, verified against the manifest's population sizes and checksum.
     */
    private static List<byte[]> loadRows(Map<String, String> workloadRow, String corpusDirectory)
            throws IOException
    {
        String[] sources = workloadRow.get("sources").split(",");
        String[] populations = workloadRow.get("population_rows").split(",");
        String[] counts = workloadRow.get("sampled_rows").split(",");
        List<byte[]> rows = new ArrayList<>();
        for (int corpus = 0; corpus < sources.length; corpus++) {
            List<byte[]> lines = lines(readSource(sources[corpus], corpusDirectory));
            if (lines.size() != Integer.parseInt(populations[corpus])) {
                throw new IllegalStateException("Corpus %s has %s rows, expected %s".formatted(sources[corpus], lines.size(), populations[corpus]));
            }
            long count = Long.parseLong(counts[corpus]);
            for (long index = 0; index < count; index++) {
                rows.add(lines.get((int) (index * lines.size() / count)));
            }
        }
        MessageDigest digest = sha256();
        for (byte[] row : rows) {
            digest.update(row);
            digest.update((byte) '\n');
        }
        if (rows.size() != Integer.parseInt(workloadRow.get("rows")) || !HexFormat.of().formatHex(digest.digest()).equals(workloadRow.get("rows_sha256"))) {
            throw new IllegalStateException("Sampled rows of workload %s differ from the manifest".formatted(workloadRow.get("workload")));
        }
        return rows;
    }

    /**
     * Returns the nonempty lines without their line feed or CR LF terminator.
     */
    private static List<byte[]> lines(byte[] data)
    {
        List<byte[]> lines = new ArrayList<>();
        int start = 0;
        while (start < data.length) {
            int end = start;
            while (end < data.length && data[end] != '\n') {
                end++;
            }
            int contentEnd = end > start && data[end - 1] == '\r' ? end - 1 : end;
            if (contentEnd > start) {
                lines.add(Arrays.copyOfRange(data, start, contentEnd));
            }
            start = end + 1;
        }
        return lines;
    }

    private static byte[] readSource(String source, String corpusDirectory)
            throws IOException
    {
        if (source.startsWith("/")) {
            try (InputStream input = requireNonNull(TestingTrinoRowsBenchmarkInputs.class.getResourceAsStream(source), source);
                    GZIPInputStream gzipInput = new GZIPInputStream(input)) {
                return gzipInput.readAllBytes();
            }
        }
        if (corpusDirectory.equals(MISSING_CORPUS_DIRECTORY)) {
            throw new IllegalArgumentException("Corpus %s needs -p corpusDirectory=<directory provisioned by trino_rows.py fetch>".formatted(source));
        }
        return Files.readAllBytes(Path.of(corpusDirectory).resolve(source));
    }

    private static Map<String, String> workloadRow(String workload)
    {
        return workloadRows().stream()
                .filter(row -> row.get("workload").equals(workload))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown workload: " + workload));
    }

    private static Map<String, String> literalRow(String workload, String literalId)
    {
        return literalRows().stream()
                .filter(row -> row.get("workload").equals(workload) && row.get("literal_id").equals(literalId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Workload %s has no literal %s".formatted(workload, literalId)));
    }

    static List<Map<String, String>> workloadRows()
    {
        return WORKLOAD_ROWS;
    }

    static List<Map<String, String>> literalRows()
    {
        return LITERAL_ROWS;
    }

    /**
     * Returns the manifest rows of the group's cells in every workload, without the empty cells.
     */
    static List<Map<String, String>> cellRows(String group)
    {
        return literalRows().stream()
                .filter(row -> row.get("group").equals(group) && !row.get("pattern").isEmpty())
                .toList();
    }

    private static List<Map<String, String>> manifestRows(String resource)
    {
        String text;
        try (InputStream input = requireNonNull(TestingTrinoRowsBenchmarkInputs.class.getResourceAsStream(resource), resource)) {
            text = new String(input.readAllBytes(), UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> lines = text.lines().toList();
        String[] header = lines.getFirst().split("\t", -1);
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t", -1);
            Map<String, String> row = new HashMap<>();
            for (int column = 0; column < header.length; column++) {
                row.put(header[column], fields[column]);
            }
            rows.add(Map.copyOf(row));
        }
        return List.copyOf(rows);
    }

    private static byte[] concat(List<byte[]> rows)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] row : rows) {
            output.writeBytes(row);
        }
        return output.toByteArray();
    }

    private static MessageDigest sha256()
    {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
