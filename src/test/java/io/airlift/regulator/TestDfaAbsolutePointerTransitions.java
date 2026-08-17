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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestDfaAbsolutePointerTransitions
{
    @Test
    public void testWithAndWithoutNativeAccess()
            throws Exception
    {
        ProbeResult disabled = runProbe(false);
        assertThat(disabled.exitCode()).as(disabled.output()).isZero();
        assertThat(disabled.output()).contains("OK disabled");
        assertThat(disabled.output()).doesNotContain("restricted method", "native access");

        ProbeResult enabled = runProbe(true);
        assertThat(enabled.exitCode()).as(enabled.output()).isZero();
        assertThat(enabled.output()).contains("OK enabled");
        assertThat(enabled.output()).doesNotContain("restricted method");
    }

    @Test
    public void testProbeDeadlineTerminatesChild()
    {
        assertThatThrownBy(() -> runProbe(false, Duration.ofNanos(1)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("native pointer probe timed out");
    }

    private static ProbeResult runProbe(boolean enableNativeAccess)
            throws IOException, InterruptedException
    {
        return runProbe(enableNativeAccess, Duration.ofSeconds(60));
    }

    private static ProbeResult runProbe(boolean enableNativeAccess, Duration timeout)
            throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        if (enableNativeAccess) {
            command.add("--enable-native-access=ALL-UNNAMED");
            command.add("-Dio.airlift.regulator.dfa.native-reader-probe=true");
        }
        command.add("--illegal-native-access=deny");
        command.add("--add-modules");
        command.add("jdk.incubator.vector");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(DfaAbsolutePointerProbe.class.getName());
        command.add(Boolean.toString(enableNativeAccess));

        Path output = Files.createTempFile("regulator-native-probe-", ".log");
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            processBuilder.environment().remove("JDK_JAVA_OPTIONS");
            Process process = processBuilder.start();
            try {
                assertThat(process.waitFor(timeout))
                        .as("native pointer probe timed out: %s", Files.readString(output))
                        .isTrue();
                return new ProbeResult(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
            }
            finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(Duration.ofSeconds(10))).as("native pointer probe did not terminate").isTrue();
                }
            }
        }
        finally {
            Files.deleteIfExists(output);
        }
    }

    private record ProbeResult(int exitCode, String output) {}
}
