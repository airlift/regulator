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
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestLanguageBenchmark
{
    private static final List<String> ENGINES = List.of("re2", "java", "trino", "rust", "jdk", "joni");
    private static final List<String> ENGINES_AND_NULL = List.of("re2", "java", "trino", "rust", "jdk", "joni", "null");

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
        // The JDK reports the UTF-16 offsets 1 to 3; every engine's first match is the UTF-8 bytes 1 to 5.
        var jdk = BenchmarkLanguageComparison.compile("jdk", inputs);
        assertThat(jdk.utf8Bounds(0, jdk.find(0)))
                .isEqualTo(BenchmarkLanguageComparison.bounds(1, 5));

        Files.writeString(path, hex("a*") + "\n0\t3\t" + hex("ab") + "\n3\t1\t\n");
        assertEnginesAgree(TestingLanguageBenchmarkInputs.read(path), ENGINES.stream().filter(engine -> !engine.equals("rust")).toList());
        // Rust skips an empty match that ends where the previous match ended.
        Files.writeString(path, hex("a*") + "\n0\t2\t" + hex("ab") + "\n3\t1\t\n");
        inputs = TestingLanguageBenchmarkInputs.read(path);
        var rust = BenchmarkLanguageComparison.compile("rust", inputs);
        assertThat(rust.matches(0)).containsExactly("61", "");
        assertThat(rust.count(0)).isEqualTo(2);
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
    public void testOperationVerificationUsesTheTimedAdapters()
            throws Exception
    {
        Path path = directory.resolve("operations.tsv");
        Files.writeString(path, "61\n" + "3\t2\t6161\n0\t0\t62\n".repeat(4));
        for (String engine : ENGINES_AND_NULL) {
            for (String operation : List.of("compile", "singleUseContains", "singleUseCount", "reusedContains", "reusedCount", "reusedFind")) {
                var data = new BenchmarkLanguageComparison.BenchmarkData();
                data.engine = engine;
                data.workloadFile = path.toString();
                if (engine.equals("null") && !operation.startsWith("reused")) {
                    assertThatThrownBy(() -> data.setup(operation))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("null engine");
                    continue;
                }
                data.setup(operation);
                String trace = switch (operation) {
                    case "compile" -> "compiled\n";
                    case "singleUseContains", "reusedContains" -> "1\n0\n".repeat(4);
                    // The first match spans bytes 0 to 1, packed as 0 << 32 | 1.
                    case "reusedFind" -> "1\n-1\n".repeat(4);
                    default -> "2\n0\n".repeat(4);
                };
                if (engine.equals("null")) {
                    // The null engine returns each input's byte length.
                    trace = "2\n1\n".repeat(4);
                }
                assertThat(BenchmarkLanguageComparison.verifyOperation(data, operation)).as("%s %s", engine, operation).isEqualTo(trace);
            }
        }
    }

    @Test
    public void testEveryPassHandsEachInputToItsOperation()
            throws Exception
    {
        Path path = directory.resolve("operations.tsv");
        Files.writeString(path, "61\n" + "3\t2\t6161\n0\t0\t62\n".repeat(4));
        TestingBenchmarkPasses.verifyPasses(new BenchmarkLanguageComparison(), pass -> {
            List<BenchmarkLanguageComparison.BenchmarkData> states = new ArrayList<>();
            // The null engine has only the reused operations.
            for (String engine : pass.getName().startsWith("reused") ? ENGINES_AND_NULL : ENGINES) {
                var data = new BenchmarkLanguageComparison.BenchmarkData();
                data.engine = engine;
                data.workloadFile = path.toString();
                data.setup(pass.getName());
                states.add(data);
            }
            return states;
        }, state -> {
            var data = (BenchmarkLanguageComparison.BenchmarkData) state;
            return data.compiled != null ? data.compiled.inputs() : data.singleUse.inputs();
        });
    }

    @Test
    public void testSetupRejectsAWorkloadWithoutEightInputs()
            throws Exception
    {
        Path path = directory.resolve("short.tsv");
        Files.writeString(path, "61\n" + "3\t2\t6161\n0\t0\t62\n".repeat(3));
        var data = new BenchmarkLanguageComparison.BenchmarkData();
        data.engine = "re2";
        data.workloadFile = path.toString();
        assertThatThrownBy(() -> data.setup("reusedCount"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("6 inputs, not 8");
    }

    @Test
    public void testFindVerificationRejectsBoundsThatChangeOnTheSecondPass()
            throws Exception
    {
        Path path = directory.resolve("operations.tsv");
        Files.writeString(path, "61\n" + "3\t2\t6161\n0\t0\t62\n".repeat(4));
        // A wrong end, then a wrong start, each only on the second pass; match or no match is unchanged.
        for (long drift : List.of(1L, 1L << 32)) {
            var data = new BenchmarkLanguageComparison.BenchmarkData();
            data.engine = "re2";
            data.workloadFile = path.toString();
            data.setup("reusedFind");
            data.compiled = new DriftingFind(data.compiled, drift, new HashSet<>());
            assertThatThrownBy(() -> BenchmarkLanguageComparison.verifyOperation(data, "reusedFind"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("verification failed");
        }
    }

    @Test
    public void testCompilationPreflightDoesNotExecuteMatches()
            throws Exception
    {
        Path path = directory.resolve("compile-only.tsv");
        // Deliberately wrong match count: compile setup must neither count nor run contains.
        Files.writeString(path, "61\n" + "0\t0\t61\n".repeat(8));
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
        assertEnginesAgree(inputs, ENGINES);
    }

    private static void assertEnginesAgree(TestingLanguageBenchmarkInputs inputs, List<String> engines)
    {
        var reference = BenchmarkLanguageComparison.compile("jdk", inputs);
        for (String engine : engines) {
            var compiled = BenchmarkLanguageComparison.compile(engine, inputs);
            for (int pass = 0; pass < 2; pass++) {
                for (int index = 0; index < inputs.sources().size(); index++) {
                    var source = inputs.sources().get(index);
                    assertThat(compiled.matches(index)).as("%s %s", engine, inputs.patternText())
                            .isEqualTo(reference.matches(index));
                    assertThat(compiled.count(index)).as("%s %s", engine, inputs.patternText())
                            .isEqualTo(source.expectedCount());
                    assertThat(compiled.contains(index)).isEqualTo(source.expectedCount() > 0 ? 1 : 0);
                    long bounds = compiled.utf8Bounds(index, compiled.find(index));
                    assertThat(bounds).as("%s %s", engine, inputs.patternText())
                            .isEqualTo(reference.utf8Bounds(index, reference.find(index)));
                    assertThat(BenchmarkLanguageComparison.holdsFirstMatch(source, bounds, compiled.matches(index))).isTrue();
                }
            }
        }
    }

    /// Reports the delegate's first-match bounds moved by `drift` whenever it sees an input a second time.
    private record DriftingFind(BenchmarkLanguageComparison.Compiled delegate, long drift, Set<Integer> seen)
            implements BenchmarkLanguageComparison.Compiled
    {
        @Override
        public Object[] inputs()
        {
            return delegate.inputs();
        }

        @Override
        public void containsPass(Blackhole blackhole)
        {
            delegate.containsPass(blackhole);
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            delegate.countPass(blackhole);
        }

        @Override
        public void findPass(Blackhole blackhole)
        {
            delegate.findPass(blackhole);
        }

        @Override
        public long contains(int index)
        {
            return delegate.contains(index);
        }

        @Override
        public long count(int index)
        {
            return delegate.count(index);
        }

        @Override
        public long find(int index)
        {
            long bounds = delegate.find(index);
            return seen.add(index) || bounds == BenchmarkLanguageComparison.NO_MATCH ? bounds : bounds + drift;
        }

        @Override
        public List<String> matches(int index)
        {
            return delegate.matches(index);
        }
    }

    private static String hex(String value)
    {
        return HexFormat.of().formatHex(utf8Slice(value).getBytes());
    }
}
