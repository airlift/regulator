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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestBoundedCharacterClassCounterForms
{
    // `countUtf8` reads LOW_BITS from a field on aarch64 and uses the constant elsewhere, chosen
    // once per JVM from os.arch, so each form runs in a JVM of its own on any host.
    @Test
    public void testFieldFormCounts()
            throws Exception
    {
        ProbeResult result = runProbe("aarch64", true);
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("OK field");
    }

    @Test
    public void testConstantFormCounts()
            throws Exception
    {
        ProbeResult result = runProbe("amd64", false);
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("OK constant");
    }

    private static ProbeResult runProbe(String osArch, boolean expectedAarch64)
            throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        command.addAll(TestingJvmArguments.vectorWidthOptions());
        command.add("-Dos.arch=" + osArch);
        command.add("--add-modules");
        command.add("jdk.incubator.vector");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(BoundedCharacterClassCounterFormProbe.class.getName());
        command.add(Boolean.toString(expectedAarch64));

        Path output = Files.createTempFile("regulator-counter-form-probe-", ".log");
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            processBuilder.environment().remove("JDK_JAVA_OPTIONS");
            Process process = processBuilder.start();
            try {
                assertThat(process.waitFor(Duration.ofSeconds(60)))
                        .as("counter form probe timed out: %s", Files.readString(output))
                        .isTrue();
                return new ProbeResult(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
            }
            finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(Duration.ofSeconds(10))).as("counter form probe did not terminate").isTrue();
                }
            }
        }
        finally {
            Files.deleteIfExists(output);
        }
    }

    private record ProbeResult(int exitCode, String output) {}
}
