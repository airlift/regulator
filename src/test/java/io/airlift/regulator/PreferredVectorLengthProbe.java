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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public final class PreferredVectorLengthProbe
{
    private static final String LANES_PREFIX = "preferred byte lanes: ";

    private PreferredVectorLengthProbe() {}

    /// Prints the lane count of this JVM's preferred byte vector.
    public static void main(String[] arguments)
    {
        System.out.println(LANES_PREFIX + ByteVector.SPECIES_PREFERRED.length());
    }

    /// Forks a JVM with the Vector API module, inheriting this JVM's environment, such as
    /// `JAVA_TOOL_OPTIONS`, and returns whether the vector scanners run there.
    static boolean expectVectorScans()
            throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        command.add("--add-modules");
        command.add("jdk.incubator.vector");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(PreferredVectorLengthProbe.class.getName());

        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .redirectErrorStream(true);
        processBuilder.environment().remove("JDK_JAVA_OPTIONS");
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        // Inherited JVM options can add lines of their own, such as picked-up options or logging,
        // so read the count from the probe's marked line.
        int length = output.lines()
                .filter(line -> line.startsWith(LANES_PREFIX))
                .mapToInt(line -> Integer.parseInt(line.substring(LANES_PREFIX.length()).strip()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no lane count in probe output: " + output));
        return length >= 16 && length <= 64;
    }
}
