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

import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestProgCompileEquivalence
{
    private static final String[] PATTERNS = {
            "(.*)-(\\d+)-of-(\\d+)",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
            "[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
            "([ -~])*(A)(B)(C)(D)(E)(F)(G)(H)(I)(J)(K)(L)(M)(N)(O)(P)(Q)(R)(S)(T)(U)(V)(W)(X)(Y)(Z)$",
            "[0-9]+.(.*)",
            "(?i)ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
    };

    @Test
    public void testCompileAndBytemapStageProduceEquivalentPrograms()
    {
        for (String pattern : PATTERNS) {
            ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(StandardCharsets.UTF_8)), Regexp.LIKE_PERL);

            assertEquivalent(pattern, false, parsed.regexp());
            assertEquivalent(pattern, true, parsed.regexp());
        }
    }

    /// The hash tables built while compiling are seeded per process, and the seed only places
    /// their entries: programs and member tables compiled under two seeds are identical.
    @Test
    public void testHashSeedDoesNotChangeCompiledOutput()
            throws Exception
    {
        List<String> first = runProbe();
        List<String> second = runProbe();
        assertThat(first.getFirst()).startsWith("seed ").isNotEqualTo(second.getFirst());
        assertThat(first.subList(1, first.size())).isEqualTo(second.subList(1, second.size()));
    }

    private static List<String> runProbe()
            throws Exception
    {
        ProcessBuilder processBuilder = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "--add-modules",
                "jdk.incubator.vector",
                "-cp",
                System.getProperty("java.class.path"),
                HashSeedProbe.class.getName());
        Path output = Files.createTempFile("regulator-hash-seed-probe-", ".log");
        try {
            // The incubator module warning goes to stderr, so only stdout is compared.
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(output.toFile());
            processBuilder.environment().remove("JDK_JAVA_OPTIONS");
            Process process = processBuilder.start();
            try {
                assertThat(process.waitFor(Duration.ofSeconds(60)))
                        .as("hash seed probe timed out: %s", Files.readString(output, StandardCharsets.UTF_8))
                        .isTrue();
                String probeOutput = Files.readString(output, StandardCharsets.UTF_8);
                assertThat(process.exitValue()).as(probeOutput).isZero();
                return probeOutput.lines().toList();
            }
            finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(Duration.ofSeconds(10))).as("hash seed probe did not terminate").isTrue();
                }
            }
        }
        finally {
            Files.deleteIfExists(output);
        }
    }

    private static void assertEquivalent(String pattern, boolean reversed, Regexp re)
    {
        Prog baseline = Compiler.compile(re, reversed, 0);
        Prog staged = Compiler.compileForBenchmark(re, reversed, 0, Compiler.CompileStage.BYTEMAP);

        assertThat(baseline).as("baseline compile: %s reversed=%s", pattern, reversed).isNotNull();
        assertThat(staged).as("staged compile: %s reversed=%s", pattern, reversed).isNotNull();

        assertThat(staged.dump()).as("dump: %s reversed=%s", pattern, reversed).isEqualTo(baseline.dump());
        assertThat(staged.dumpUnanchored()).as("dumpUnanchored: %s reversed=%s", pattern, reversed).isEqualTo(baseline.dumpUnanchored());
        assertThat(staged.dumpByteMap()).as("dumpByteMap: %s reversed=%s", pattern, reversed).isEqualTo(baseline.dumpByteMap());

        assertThat(staged.start()).as("start: %s reversed=%s", pattern, reversed).isEqualTo(baseline.start());
        assertThat(staged.startUnanchored()).as("startUnanchored: %s reversed=%s", pattern, reversed).isEqualTo(baseline.startUnanchored());
        assertThat(staged.anchorStart()).as("anchorStart: %s reversed=%s", pattern, reversed).isEqualTo(baseline.anchorStart());
        assertThat(staged.anchorEnd()).as("anchorEnd: %s reversed=%s", pattern, reversed).isEqualTo(baseline.anchorEnd());

        for (InstOp op : InstOp.values()) {
            assertThat(staged.getInstCount(op))
                    .as("instCount[%s]: %s reversed=%s", op, pattern, reversed)
                    .isEqualTo(baseline.getInstCount(op));
        }
    }
}
