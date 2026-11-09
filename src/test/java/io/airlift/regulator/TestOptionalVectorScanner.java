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

import jdk.incubator.vector.ByteVector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TestOptionalVectorScanner
{
    @Test
    public void testWithAndWithoutVectorModule()
            throws Exception
    {
        ProbeResult disabled = runProbe(List.of(), false);
        assertThat(disabled.exitCode()).as(disabled.output()).isZero();
        assertThat(disabled.output()).contains("OK scalar");
        assertThat(disabled.classLoadLog())
                .contains("io.airlift.regulator.ScanPlan source:")
                .contains("io.airlift.regulator.ScanPlanRun source:")
                .doesNotContain("io.airlift.regulator.VectorScanner source:")
                .doesNotContain("io.airlift.regulator.VectorByteSetScanner source:")
                .doesNotContain("jdk.incubator.vector.");

        // With the module, the probe still takes the scalar scans if its preferred vector is too
        // small, such as under a limit in `JAVA_TOOL_OPTIONS`.
        boolean vectorScans = PreferredVectorLengthProbe.expectVectorScans();
        ProbeResult enabled = runProbe(List.of("--add-modules", "jdk.incubator.vector"), vectorScans);
        assertThat(enabled.exitCode()).as(enabled.output()).isZero();
        assertThat(enabled.output()).contains(vectorScans ? "OK vector" : "OK scalar");
        assertThat(enabled.classLoadLog()).contains("jdk.incubator.vector.ByteVector");
        assertThat(enabled.classLoadLog().contains("io.airlift.regulator.VectorScanner source:")).isEqualTo(vectorScans);
        assertThat(enabled.classLoadLog().contains("io.airlift.regulator.VectorByteSetScanner source:")).isEqualTo(vectorScans);
    }

    @Test
    public void testEightByteVectorsUseScalarScanners()
            throws Exception
    {
        // The nibble-table scans need at least 16 lanes, so an 8-byte preferred vector
        // keeps every scan scalar even with the module resolved.
        ProbeResult smallVectors = runProbe(List.of("--add-modules", "jdk.incubator.vector", "-XX:MaxVectorSize=8"), false);
        assertThat(smallVectors.exitCode()).as(smallVectors.output()).isZero();
        assertThat(smallVectors.output()).contains("OK scalar");
        assertThat(smallVectors.classLoadLog())
                .doesNotContain("io.airlift.regulator.VectorScanner source:")
                .doesNotContain("io.airlift.regulator.VectorByteSetScanner source:");
    }

    /// A forked probe JVM runs at this JVM's vector width, such as that of CI's 8-byte and 16-byte
    /// builds, rather than at the machine's.
    @Test
    public void testProbesRunAtThisJvmVectorWidth()
            throws Exception
    {
        assumeTrue(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent(), "requires the Vector API module");
        assertThat(PreferredVectorLengthProbe.preferredByteLanes()).isEqualTo(ByteVector.SPECIES_PREFERRED.length());
    }

    private static ProbeResult runProbe(List<String> jvmArguments, boolean expectedVectorScans)
            throws IOException, InterruptedException
    {
        Path classLoadLog = Files.createTempFile("vector-trino-class-load-", ".log");
        try {
            List<String> command = new ArrayList<>();
            command.add(System.getProperty("java.home") + "/bin/java");
            command.add("-Xlog:class+load=info:file=" + classLoadLog);
            // The probe runs at this JVM's vector width, such as that of CI's 8-byte build. Those
            // options come first, so a test's own `-XX:MaxVectorSize=8` wins over them.
            command.addAll(TestingJvmArguments.vectorWidthOptions());
            command.addAll(jvmArguments);
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(VectorScannerProbe.class.getName());
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
