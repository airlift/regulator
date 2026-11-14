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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestOptionalVectorPrefixScanner
{
    @Test
    public void testWithAndWithoutVectorModule()
            throws Exception
    {
        ProbeResult disabled = runProbe(List.of(), false);
        assertThat(disabled.exitCode()).as(disabled.output()).isZero();
        assertThat(disabled.output()).contains("OK swar");
        assertThat(disabled.classLoadLog())
                .doesNotContain("io.airlift.regulator.VectorPrefixScanner source:")
                .doesNotContain("io.airlift.regulator.VectorByteSetScanner source:")
                .doesNotContain("jdk.incubator.vector.");

        // With the module, the probe still takes the SWAR scans if its preferred vector is too
        // small, such as under a limit in `JAVA_TOOL_OPTIONS`.
        boolean vectorScans = PreferredVectorLengthProbe.expectVectorScans();
        ProbeResult enabled = runProbe(List.of("--add-modules", "jdk.incubator.vector"), vectorScans);
        assertThat(enabled.exitCode()).as(enabled.output()).isZero();
        assertThat(enabled.output()).contains(vectorScans ? "OK vector" : "OK swar");
        assertThat(enabled.classLoadLog()).contains("jdk.incubator.vector.ByteVector");
        assertThat(enabled.classLoadLog().contains("io.airlift.regulator.VectorPrefixScanner source:")).isEqualTo(vectorScans);
        assertThat(enabled.classLoadLog().contains("io.airlift.regulator.VectorByteSetScanner source:")).isEqualTo(vectorScans);
    }

    @Test
    public void testEightByteVectorsUseSwarPrefixScanner()
            throws Exception
    {
        // The vector scans need at least 16 lanes, so an 8-byte preferred vector keeps every
        // scan in the probe off the Vector API even with the module resolved.
        ProbeResult smallVectors = runProbe(List.of("--add-modules", "jdk.incubator.vector", "-XX:MaxVectorSize=8"), false);
        assertThat(smallVectors.exitCode()).as(smallVectors.output()).isZero();
        assertThat(smallVectors.output()).contains("OK swar");
        assertThat(smallVectors.classLoadLog())
                .doesNotContain("io.airlift.regulator.VectorPrefixScanner source:")
                .doesNotContain("io.airlift.regulator.VectorByteSetScanner source:");
    }

    private static ProbeResult runProbe(List<String> jvmArguments, boolean expectedVectorScans)
            throws IOException, InterruptedException
    {
        Path classLoadLog = Files.createTempFile("vector-prefix-class-load-", ".log");
        try {
            List<String> command = new ArrayList<>();
            command.add(System.getProperty("java.home") + "/bin/java");
            command.add("-Xlog:class+load=info:file=" + classLoadLog);
            // The probe runs at this JVM's vector width, such as that of CI's 8-byte build. Those
            // options come first, so a test's own `-XX:MaxVectorSize=8` wins over them.
            command.addAll(TestingJvmArguments.vectorWidthOptions());
            command.addAll(jvmArguments);
            // The probe checks which scans its searches took.
            command.add("-Dio.airlift.regulator.dfa.route-counts=true");
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(VectorPrefixScannerProbe.class.getName());
            command.add(Boolean.toString(expectedVectorScans));

            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            processBuilder.environment().remove("JDK_JAVA_OPTIONS");
            Process process = processBuilder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new ProbeResult(exitCode, output, Files.readString(classLoadLog));
        }
        finally {
            Files.deleteIfExists(classLoadLog);
        }
    }

    private record ProbeResult(int exitCode, String output, String classLoadLog) {}
}
