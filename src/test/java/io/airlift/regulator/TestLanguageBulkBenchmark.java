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
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestLanguageBulkBenchmark
{
    private static final List<String> ENGINES = List.of("re2", "java", "trino", "rust", "jdk", "joni");
    private static final List<String> NON_RUST_ENGINES = ENGINES.stream().filter(engine -> !engine.equals("rust")).toList();

    @TempDir
    Path directory;

    @Test
    public void testAllModelsAndRepeatedExecution()
            throws Exception
    {
        check("count", "(a+)", "a baaa", 2, "source\n0:1:61;\n3:6:616161;\n");
        check("count-spans", "é|💰", "x💰é", 6, "source\n1:5:f09f92b0;\n5:7:c3a9;\n");
        check("count-captures", "(a)(b)?()", "a ab", 7, "source\n0:1:61;0:1:61;-;1:1:;\n2:4:6162;2:3:61;3:4:62;4:4:;\n");
        check("grep", "^a$", "a\r\nb\na\r", 2, "source\n1\nsource\n0\nsource\n1\n");
        check("grep", "a", "aa\nb\n", 1, "source\n1\nsource\n0\n");
        check("grep-captures",
                "(a)(b)?()",
                "a\r\nab\n",
                7,
                "source\n0:1:61;0:1:61;-;1:1:;\nsource\n0:2:6162;0:1:61;1:2:62;2:2:;\n");
        check("grep-captures",
                "(a)",
                "aa\nba\n",
                6,
                "source\n0:1:61;0:1:61;\n1:2:61;1:2:61;\nsource\n1:2:61;1:2:61;\n");
        check(NON_RUST_ENGINES, "count", "a*", "ab", 3, "source\n0:1:61;\n1:1:;\n2:2:;\n");
        // Rust skips an empty match that ends where the previous match ended.
        check(List.of("rust"), "count", "a*", "ab", 2, "source\n0:1:61;\n2:2:;\n");
        check("count", "a*", "", 1, "source\n0:0:;\n");
        // Exercise empty matches over a multibyte code point in the count, span and capture loops.
        check("count", "a*", "é", 2, "source\n0:0:;\n2:2:;\n");
        check("count-spans", "^|.", "éa", 1, "source\n0:0:;\n2:3:61;\n");
        check("count-captures", "(x*)", "é", 4, "source\n0:0:;0:0:;\n2:2:;2:2:;\n");
        check("grep-captures", "(x*)", "é\nb", 8, "source\n0:0:;0:0:;\n2:2:;2:2:;\nsource\n0:0:;0:0:;\n1:1:;1:1:;\n");
        check("grep", ".", "", 0, "");
    }

    @Test
    public void testRustByteModeWithoutUnicodeOrForMalformedInput()
            throws Exception
    {
        // Without Unicode, Rust compiles in byte mode, as RE2 compiles Latin-1, so a dot matches each byte of é.
        check(List.of("re2", "rust"), "count", ".", "é".getBytes(UTF_8), false, 2, "source\n0:1:c3;\n1:2:a9;\n");
        // With Unicode on, a malformed haystack still selects byte mode, where a byte-oriented dot compiles.
        check(List.of("rust"), "count", "(?-u:.)", new byte[] {'a', (byte) 0xFF}, true, 2, "source\n0:1:61;\n1:2:ff;\n");
        // Over UTF-8 text Rust keeps its default mode, which rejects that dot as the crate's str API does.
        var data = new BenchmarkLanguageBulk.BenchmarkData();
        data.engine = "rust";
        data.workloadFile = input("count", "(?-u:.)", "a").toString();
        assertThatThrownBy(data::setup)
                .isInstanceOfSatisfying(RegexpParseException.class, exception -> assertThat(exception.errorCode()).isEqualTo(RegexpParseErrorCode.BAD_UTF8));
    }

    @Test
    public void testBooleanLinesDoNotConstructSliceMatchers()
            throws Exception
    {
        Path input = input("grep", "([a-zA-Z][a-zA-Z0-9]*)://([^ /]+)(/[^ ]*)?", "https://example.com/a\nno uri\n");
        for (String engine : List.of("re2", "java", "rust")) {
            var data = new BenchmarkLanguageBulk.BenchmarkData();
            data.engine = engine;
            data.workloadFile = input.toString();
            data.expectedResult = 1;
            data.setup();
            var matcherField = BenchmarkLanguageBulk.BenchmarkData.class.getDeclaredField("sliceMatcher");
            matcherField.setAccessible(true);
            assertThat(matcherField.get(data)).isNull();
            assertThat(data.execute()).isEqualTo(1);
            StringWriter output = new StringWriter();
            BufferedWriter writer = new BufferedWriter(output);
            data.trace(writer);
            writer.flush();
            assertThat(output.toString()).isEqualTo("source\n1\nsource\n0\n");
        }
    }

    @Test
    public void testWideCaptureTraceWithNullAndEmptyGroups()
            throws Exception
    {
        StringBuilder trace = new StringBuilder("source\n0:32:" + "61".repeat(32) + ";");
        for (int group = 0; group < 32; group++) {
            trace.append(group).append(':').append(group + 1).append(":61;");
        }
        trace.append("-;32:32:;\n");
        check("count-captures",
                "(a)".repeat(32) + "(b)?()",
                "a".repeat(32),
                34,
                trace.toString());
    }

    @Test
    public void testCompileDoesNotExecute()
            throws Exception
    {
        check("compile", "a+", "aa", 0, "compiled\n");
        var data = new BenchmarkLanguageBulk.BenchmarkData();
        data.engine = "re2";
        data.workloadFile = input("compile", "a+", "aa").toString();
        data.setup();
        assertThatThrownBy(() -> new BenchmarkLanguageBulk().execute(data)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not compile");
    }

    @Test
    public void testSetupRejectsWrongExpectedResult()
            throws Exception
    {
        Path input = input("count", "a+", "aa");
        for (String engine : ENGINES) {
            var data = new BenchmarkLanguageBulk.BenchmarkData();
            data.engine = engine;
            data.workloadFile = input.toString();
            assertThatThrownBy(data::setup).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("verification failed");
        }
    }

    @Test
    public void testByteFrequencyFieldsChooseRegulatorDistribution()
            throws Exception
    {
        assertThat(read(input("count", "ex", "exex")).byteFrequencies()).isSameAs(ByteFrequencies.uniform());
        assertThat(read(input("count", "ex", "exex", "byte-frequencies", "english-text")).byteFrequencies())
                .isSameAs(ByteFrequencies.englishText());
        String sample = "e".repeat(100) + "x";
        RebarRunner.Benchmark learned = read(input("count", "ex", "exex", "byte-frequency-sample", sample));
        assertThat(learned.byteFrequencies().probeOffset("ex".getBytes(UTF_8))).isEqualTo(1);
        assertThat(learned.options().byteFrequencies()).isSameAs(learned.byteFrequencies());

        // Every engine still finds the same matches; only the Regulator scan plan changes.
        Path workload = input("count", "ex", "exex", "byte-frequency-sample", sample);
        for (String engine : ENGINES) {
            var data = new BenchmarkLanguageBulk.BenchmarkData();
            data.engine = engine;
            data.workloadFile = workload.toString();
            data.expectedResult = 2;
            data.setup();
            assertThat(new BenchmarkLanguageBulk().execute(data)).as(engine).isEqualTo(2);
        }
        // The Java, Trino and Rust patterns compile with the workload's distribution.
        for (String engine : List.of("java", "trino", "rust")) {
            assertThat(compiledProbeOffset(engine, "byte-frequency-sample", sample)).as(engine).isEqualTo(1);
            assertThat(compiledProbeOffset(engine)).as(engine).isZero();
        }

        assertThatThrownBy(() -> read(input("count", "ex", "exex", "byte-frequencies", "json", "byte-frequency-sample", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one byte-frequency distribution");
        assertThatThrownBy(() -> read(input("count", "ex", "exex", "byte-frequencies", "latin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown byte-frequency preset");
    }

    @Test
    public void testMaxMemoryFieldSetsRegulatorBudget()
            throws Exception
    {
        assertThat(read(input("count", "ex", "exex")).maxMemory()).isZero();
        assertThat(read(input("count", "ex", "exex", "max-memory", Long.toString(72L << 20))).options().maxMemory()).isEqualTo(72L << 20);

        // Two Unicode classes repeated 200 times compile within the default budget but not within 1 MiB.
        Path workload = input("count", "(?:\\p{L}\\p{N}){200}", "a1", "max-memory", Long.toString(1L << 20));
        for (String engine : ENGINES) {
            var data = new BenchmarkLanguageBulk.BenchmarkData();
            data.engine = engine;
            data.workloadFile = workload.toString();
            if (engine.equals("jdk") || engine.equals("joni")) {
                assertThatCode(data::initialize).as(engine).doesNotThrowAnyException();
            }
            else {
                assertThatThrownBy(data::initialize).as(engine)
                        .isInstanceOf(RegexpCompileMemoryLimitException.class);
            }
        }

        assertThatThrownBy(() -> read(input("count", "ex", "exex", "max-memory", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-memory must be positive");
    }

    @Test
    public void testLineSlicesKeepTheirOffsets()
    {
        var lines = BenchmarkLanguageBulk.lines(utf8Slice("paddinga\r\n\nb\r").slice(7, 6));
        assertThat(lines).extracting(value -> value.toStringUtf8()).containsExactly("a", "", "b");
        assertThat(lines.getFirst().byteArrayOffset()).isEqualTo(7);
    }

    private void check(String model, String pattern, String source, long expected, String trace)
            throws Exception
    {
        check(ENGINES, model, pattern, source, expected, trace);
    }

    private void check(List<String> engines, String model, String pattern, String source, long expected, String trace)
            throws Exception
    {
        check(engines, model, pattern, source.getBytes(UTF_8), true, expected, trace);
    }

    private void check(List<String> engines, String model, String pattern, byte[] source, boolean unicode, long expected, String trace)
            throws Exception
    {
        Path input = input(model, pattern, source, unicode);
        for (String engine : engines) {
            var data = new BenchmarkLanguageBulk.BenchmarkData();
            data.engine = engine;
            data.workloadFile = input.toString();
            data.expectedResult = expected;
            data.setup();
            assertThat(new BenchmarkLanguageBulk().compile(data)).isNotNull();
            if (!model.equals("compile")) {
                for (int pass = 0; pass < 2; pass++) {
                    assertThat(new BenchmarkLanguageBulk().execute(data)).as("%s/%s", engine, model).isEqualTo(expected);
                    if (model.equals("count") && List.of("re2", "java", "rust").contains(engine)) {
                        assertThat(new BenchmarkLanguageBulk().iterateMatches(data)).isEqualTo(expected);
                    }
                }
            }
            StringWriter output = new StringWriter();
            BufferedWriter writer = new BufferedWriter(output);
            data.trace(writer);
            writer.flush();
            assertThat(output.toString()).as("%s/%s trace", engine, model).isEqualTo(trace);
        }
    }

    private int compiledProbeOffset(String engine, String... extraFields)
            throws Exception
    {
        var data = new BenchmarkLanguageBulk.BenchmarkData();
        data.engine = engine;
        data.workloadFile = input("count", "ex", "exex", extraFields).toString();
        data.initialize();
        CompiledPattern pattern = switch (data.compilePattern()) {
            case JavaRegexp java -> java;
            case TrinoRegexp trino -> trino.re2();
            case RustRegexp rust -> rust;
            default -> throw new IllegalArgumentException("not a Regulator pattern: " + engine);
        };
        return pattern.forwardProgramForDiagnostics().prefixAccelProbeOffset();
    }

    private static RebarRunner.Benchmark read(Path input)
            throws Exception
    {
        return RebarRunner.Benchmark.read(Files.readAllBytes(input));
    }

    private Path input(String model, String pattern, String source, String... extraFields)
            throws Exception
    {
        return input(model, pattern, source.getBytes(UTF_8), true, extraFields);
    }

    private Path input(String model, String pattern, byte[] source, boolean unicode, String... extraFields)
            throws Exception
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<Map.Entry<String, byte[]>> fields = new ArrayList<>(List.of(
                entry("name", "test".getBytes(UTF_8)),
                entry("model", model.getBytes(UTF_8)),
                entry("pattern", pattern.getBytes(UTF_8)),
                entry("haystack", source),
                entry("unicode", Boolean.toString(unicode).getBytes(UTF_8)),
                entry("case-insensitive", "false".getBytes(UTF_8))));
        for (int index = 0; index < extraFields.length; index += 2) {
            fields.add(entry(extraFields[index], extraFields[index + 1].getBytes(UTF_8)));
        }
        for (Map.Entry<String, byte[]> field : fields) {
            output.write((field.getKey() + ":" + field.getValue().length + ":").getBytes(UTF_8));
            output.write(field.getValue());
            output.write('\n');
        }
        Path input = directory.resolve("input.klv");
        Files.write(input, output.toByteArray());
        return input;
    }
}
