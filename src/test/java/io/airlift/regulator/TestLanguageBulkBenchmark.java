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
import java.util.List;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestLanguageBulkBenchmark
{
    private static final List<String> ENGINES = List.of("re2", "java", "trino", "jdk", "joni");

    @TempDir
    Path directory;

    @Test
    public void testAllModelsAndRepeatedExecution()
            throws Exception
    {
        check("count", "a+", "a baaa", 2, "source\n0:1:61;\n3:6:616161;\n");
        check("count-spans", "é|💰", "x💰é", 6, "source\n1:5:f09f92b0;\n5:7:c3a9;\n");
        check("count-captures", "(a)(b)?()", "a ab", 7, "source\n0:1:61;0:1:61;-;1:1:;\n2:4:6162;2:3:61;3:4:62;4:4:;\n");
        check("grep", "^a$", "a\r\nb\na\r", 2, "source\n1\nsource\n0\nsource\n1\n");
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
        check("count", "a*", "ab", 3, "source\n0:1:61;\n1:1:;\n2:2:;\n");
        check("count", "a*", "", 1, "source\n0:0:;\n");
        check("grep", ".", "", 0, "");
    }

    @Test
    public void testBooleanLinesDoNotConstructSliceMatchers()
            throws Exception
    {
        Path input = input("grep", "([a-zA-Z][a-zA-Z0-9]*)://([^ /]+)(/[^ ]*)?", "https://example.com/a\nno uri\n");
        for (String engine : List.of("re2", "java")) {
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
    public void testLineSlicesKeepTheirOffsets()
    {
        var lines = BenchmarkLanguageBulk.lines(utf8Slice("paddinga\r\n\nb\r").slice(7, 6));
        assertThat(lines).extracting(value -> value.toStringUtf8()).containsExactly("a", "", "b");
        assertThat(lines.getFirst().byteArrayOffset()).isEqualTo(7);
    }

    private void check(String model, String pattern, String source, long expected, String trace)
            throws Exception
    {
        Path input = input(model, pattern, source);
        for (String engine : ENGINES) {
            var data = new BenchmarkLanguageBulk.BenchmarkData();
            data.engine = engine;
            data.workloadFile = input.toString();
            data.expectedResult = expected;
            data.setup();
            assertThat(new BenchmarkLanguageBulk().compile(data)).isNotNull();
            if (!model.equals("compile")) {
                for (int pass = 0; pass < 2; pass++) {
                    assertThat(new BenchmarkLanguageBulk().execute(data)).as("%s/%s", engine, model).isEqualTo(expected);
                    if (model.equals("count") && List.of("re2", "java").contains(engine)) {
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

    private Path input(String model, String pattern, String source)
            throws Exception
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String[][] fields = {
                {"name", "test"}, {"model", model}, {"pattern", pattern},
                {"haystack", source}, {"unicode", "true"}, {"case-insensitive", "false"},
        };
        for (String[] field : fields) {
            byte[] value = field[1].getBytes(UTF_8);
            output.write((field[0] + ":" + value.length + ":").getBytes(UTF_8));
            output.write(value);
            output.write('\n');
        }
        Path input = directory.resolve("input.klv");
        Files.write(input, output.toByteArray());
        return input;
    }
}
