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

import io.airlift.regulator.GoldenJsonl.JsonObject;
import io.airlift.regulator.TestingDifferentialFuzzer.OracleAbortException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/// A native golden tool run as a batch oracle for [TestingDifferentialFuzzer]. Each row is a
/// tab-separated case without its id; a run gives each row an id, sends a few thousand rows to
/// one process, and reads back one JSON line per row. Answers are cached by row, so a case the
/// fuzzer prepared costs nothing to ask about again. A run that fails or times out is split
/// until the rows that fail it stand alone; asking about such a row throws
/// [OracleAbortException].
final class TestingOracleProcess
{
    private static final int ROWS_PER_RUN = 4_000;
    private static final int MAX_CACHED_ROWS = 200_000;
    // A run may take this long per row, and at least the minimum, so splitting a run that times
    // out to find its row takes about twice the first run's limit.
    private static final Duration ROW_TIMEOUT = Duration.ofMillis(150);
    private static final Duration MIN_RUN_TIMEOUT = Duration.ofSeconds(10);

    private final Path binary;
    private final String name;
    private final List<String> arguments;
    private final Map<String, Answer> answers = new HashMap<>();

    // A row's JSON answer, or the reason the row made the process fail.
    private record Answer(JsonObject json, String abort) {}

    // The answers of a run by row, or why the run failed: its exit status and standard error, or
    // its timeout.
    private record RunResult(Map<String, JsonObject> answers, String failure) {}

    TestingOracleProcess(Path binary, List<String> arguments)
    {
        this.binary = requireNonNull(binary, "binary is null");
        this.name = binary.getFileName().toString();
        this.arguments = List.copyOf(arguments);
    }

    /// Returns the configured binary, or `null` when the property is unset. A set property must
    /// name an executable file.
    static Path binary(String property)
    {
        String value = System.getProperty(property);
        if (value == null || value.isEmpty()) {
            return null;
        }
        Path path = Path.of(value);
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new IllegalArgumentException("-D%s=%s is not an executable file".formatted(property, value));
        }
        // The process runs the file checked here, not a program of that name on the path.
        return path.toAbsolutePath();
    }

    /// Writes `bytes` as a row field: hexadecimal, or `-` when empty.
    static String hexField(byte[] bytes)
    {
        return bytes.length == 0 ? "-" : HexFormat.of().formatHex(bytes);
    }

    /// Answers every row not answered yet.
    void prepare(Collection<String> rows)
    {
        List<String> missing = new ArrayList<>(new LinkedHashSet<>(rows));
        missing.removeIf(answers::containsKey);
        if (missing.isEmpty()) {
            return;
        }
        if (answers.size() + missing.size() > MAX_CACHED_ROWS) {
            answers.clear();
        }
        for (int start = 0; start < missing.size(); start += ROWS_PER_RUN) {
            answer(missing.subList(start, Math.min(missing.size(), start + ROWS_PER_RUN)));
        }
    }

    /// Returns the answer to `row`, running the process for it when it was not prepared.
    JsonObject get(String row)
    {
        Answer answer = answers.get(row);
        if (answer == null) {
            prepare(List.of(row));
            answer = answers.get(row);
        }
        if (answer.abort() != null) {
            throw new OracleAbortException(answer.abort());
        }
        return answer.json();
    }

    // Runs the rows, splitting a failed run until each failing row stands alone.
    private void answer(List<String> rows)
    {
        RunResult result = run(rows);
        if (result.answers() != null) {
            for (Map.Entry<String, JsonObject> entry : result.answers().entrySet()) {
                answers.put(entry.getKey(), new Answer(entry.getValue(), null));
            }
            return;
        }
        if (rows.size() == 1) {
            answers.put(rows.getFirst(), new Answer(null, "%s failed (%s) on row: %s".formatted(name, result.failure(), rows.getFirst())));
            return;
        }
        int middle = rows.size() / 2;
        answer(rows.subList(0, middle));
        answer(rows.subList(middle, rows.size()));
    }

    private RunResult run(List<String> rows)
    {
        try (TemporaryFiles files = new TemporaryFiles()) {
            return run(rows, files.create(".tsv"), files.create(".jsonl"), files.create(".err"));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RunResult run(List<String> rows, Path input, Path output, Path errors)
            throws IOException
    {
        StringBuilder lines = new StringBuilder();
        for (int index = 0; index < rows.size(); index++) {
            lines.append('r').append(index).append('\t').append(rows.get(index)).append('\n');
        }
        Files.writeString(input, lines, UTF_8);
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.addAll(arguments);
        Process process = new ProcessBuilder(command)
                .redirectInput(input.toFile())
                .redirectOutput(output.toFile())
                .redirectError(errors.toFile())
                .start();
        Duration timeout = ROW_TIMEOUT.multipliedBy(rows.size());
        if (timeout.compareTo(MIN_RUN_TIMEOUT) < 0) {
            timeout = MIN_RUN_TIMEOUT;
        }
        try {
            if (!process.waitFor(timeout)) {
                stop(process);
                return new RunResult(null, "ran past %s".formatted(timeout));
            }
        }
        catch (InterruptedException e) {
            // The process must not outlive the run that started it.
            stop(process);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (process.exitValue() != 0) {
            return new RunResult(null, "exit %s: %s".formatted(process.exitValue(), new String(Files.readAllBytes(errors), UTF_8).strip()));
        }
        Map<String, JsonObject> results = new HashMap<>();
        List<String> answerLines = Files.readAllLines(output, UTF_8);
        for (int index = 0; index < answerLines.size(); index++) {
            String line = answerLines.get(index);
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            JsonObject json = GoldenJsonl.parseObject(name, index + 1, line);
            String id = json.getString("id");
            results.put(rows.get(Integer.parseInt(id.substring(1))), json);
        }
        if (results.size() != new LinkedHashSet<>(rows).size()) {
            throw new IllegalStateException("%s answered %s of %s rows".formatted(name, results.size(), rows.size()));
        }
        return new RunResult(results, null);
    }

    // Kills the process and waits for it to exit, even when this thread is interrupted.
    private static void stop(Process process)
    {
        process.destroyForcibly();
        process.onExit().join();
    }

    // Temporary files that closing deletes, attempting every deletion even after one fails.
    private static final class TemporaryFiles
            implements AutoCloseable
    {
        private final List<Path> paths = new ArrayList<>();

        Path create(String suffix)
                throws IOException
        {
            Path path = Files.createTempFile("regulator-fuzz-oracle", suffix);
            paths.add(path);
            return path;
        }

        @Override
        public void close()
        {
            UncheckedIOException failure = null;
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                }
                catch (IOException e) {
                    if (failure == null) {
                        failure = new UncheckedIOException(e);
                    }
                    else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
