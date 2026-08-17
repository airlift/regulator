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

import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestLanguageBenchmark
{
    private static final List<String> ENGINES = List.of("re2", "java", "trino", "jdk", "joni");

    @TempDir
    Path directory;

    @Test
    public void testEverydayCorpusAcrossPublicFrontends()
    {
        for (String id : TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds()) {
            var everyday = TestingEverydayTrinoRegexpBenchmarkInputs.create(id);
            var inputs = new TestingLanguageBenchmarkInputs(everyday.pattern(), everyday.pattern().toStringUtf8(),
                    everyday.sources().stream()
                            .map(source -> new TestingLanguageBenchmarkInputs.Source(source.value(), source.value().toStringUtf8(), source.expectedMatchCount()))
                            .toList());
            assertEnginesAgree(inputs);
        }
    }

    @Test
    public void testUnicodeOffsetsEmptyMatchesAndReset()
            throws Exception
    {
        Path path = directory.resolve("unicode.tsv");
        Files.writeString(path, hex("é|💰") + "\n3\t2\t" + hex("x💰é") + "\n0\t0\t\n");
        var inputs = TestingLanguageBenchmarkInputs.read(path);
        assertThat(inputs.sources().getFirst().bytes().byteArrayOffset()).isEqualTo(3);
        assertThat(inputs.sources().getFirst().text()).isEqualTo("x💰é");
        assertEnginesAgree(inputs);

        Files.writeString(path, hex("a*") + "\n0\t3\t" + hex("ab") + "\n3\t1\t\n");
        assertEnginesAgree(TestingLanguageBenchmarkInputs.read(path));
    }

    @Test
    public void testNewlinesSurviveInputTransport()
            throws Exception
    {
        Path path = directory.resolve("newlines.tsv");
        Files.writeString(path, hex("(?s)a.*z") + "\n3\t1\t" + hex("a\n💰z") + "\n0\t0\t" + hex("a\nx") + "\n");
        var inputs = TestingLanguageBenchmarkInputs.read(path);
        assertThat(inputs.sources().getFirst().text()).isEqualTo("a\n💰z");
        assertEnginesAgree(inputs);
    }

    @Test
    public void testRejectMalformedUtf8AndUnknownEngine()
            throws Exception
    {
        Path path = directory.resolve("invalid.tsv");
        Files.writeString(path, "61\n0\t0\tff\n");
        assertThatThrownBy(() -> TestingLanguageBenchmarkInputs.read(path)).isInstanceOf(CharacterCodingException.class);
        Files.writeString(path, "61\n0\t1\t61\n");
        var inputs = TestingLanguageBenchmarkInputs.read(path);
        assertThatThrownBy(() -> BenchmarkLanguageComparison.compile("unknown", inputs))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testLifecycleMethodsUseSameInputsAndConsumeResults()
            throws Exception
    {
        Path path = directory.resolve("lifecycle.tsv");
        Files.writeString(path, "61\n3\t2\t6161\n0\t0\t62\n");
        var benchmark = new BenchmarkLanguageComparison();
        for (String engine : ENGINES) {
            var data = new BenchmarkLanguageComparison.BenchmarkData();
            data.engine = engine;
            data.workloadFile = path.toString();
            data.setup("reusedCount");
            assertThat(benchmark.compile(data)).isNotNull();
            assertThat(benchmark.singleUseContains(data)).isTrue();
            assertThat(benchmark.singleUseContains(data)).isFalse();
            assertThat(benchmark.reusedContains(data)).isTrue();
            assertThat(benchmark.reusedContains(data)).isFalse();
            assertThat(benchmark.singleUseCount(data)).isEqualTo(2);
            assertThat(benchmark.singleUseCount(data)).isZero();
            assertThat(benchmark.reusedCount(data)).isEqualTo(2);
            assertThat(benchmark.reusedCount(data)).isZero();
        }
    }

    @Test
    public void testOperationVerificationUsesActualLifecycleMethods()
            throws Exception
    {
        Path path = directory.resolve("operations.tsv");
        Files.writeString(path, "61\n3\t2\t6161\n0\t0\t62\n");
        for (String engine : ENGINES) {
            for (String operation : List.of("compile", "singleUseContains", "singleUseCount", "reusedContains", "reusedCount")) {
                var data = new BenchmarkLanguageComparison.BenchmarkData();
                data.engine = engine;
                data.workloadFile = path.toString();
                data.setup(operation);
                assertThat(BenchmarkLanguageComparison.verifyOperation(data, operation))
                        .isEqualTo(operation.equals("compile") ? "compiled\n" : operation.endsWith("Contains") ? "1\n0\n" : "2\n0\n");
            }
        }
    }

    @Test
    public void testCompilationPreflightDoesNotExecuteMatches()
            throws Exception
    {
        Path path = directory.resolve("compile-only.tsv");
        // Deliberately wrong match count: compile setup must neither count nor run contains.
        Files.writeString(path, "61\n0\t0\t61\n");
        for (String engine : ENGINES) {
            var data = new BenchmarkLanguageComparison.BenchmarkData();
            data.engine = engine;
            data.workloadFile = path.toString();
            data.setup("compile");
            assertThatThrownBy(() -> data.setup("reusedCount"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("verification failed");
        }
    }

    private static void assertEnginesAgree(TestingLanguageBenchmarkInputs inputs)
    {
        var reference = BenchmarkLanguageComparison.compile("jdk", inputs);
        for (String engine : ENGINES) {
            var compiled = BenchmarkLanguageComparison.compile(engine, inputs);
            for (int pass = 0; pass < 2; pass++) {
                for (var source : inputs.sources()) {
                    assertThat(compiled.matches(source)).as("%s %s", engine, inputs.patternText())
                            .isEqualTo(reference.matches(source));
                    assertThat(compiled.count(source)).as("%s %s", engine, inputs.patternText())
                            .isEqualTo(source.expectedCount());
                    assertThat(compiled.contains(source)).isEqualTo(source.expectedCount() > 0);
                }
            }
        }
    }

    private static String hex(String value)
    {
        return HexFormat.of().formatHex(utf8Slice(value).getBytes());
    }
}
