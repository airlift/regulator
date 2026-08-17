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

import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllLines;
import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkCompilerHints
{
    @Test
    public void testWritesJmhCompilerControls(@TempDir Path temporaryDirectory)
            throws Exception
    {
        Path compilerHints = temporaryDirectory.resolve("compiler-hints.txt");
        Path jvmArguments = temporaryDirectory.resolve("jvm-arguments.txt");

        BenchmarkCompilerHints.main(new String[] {compilerHints.toString(), jvmArguments.toString()});

        assertThat(readAllLines(compilerHints, UTF_8))
                .contains(
                        "blackhole,org/openjdk/jmh/infra/Blackhole.consumeCompiler",
                        "dontinline,*.*_avgt_jmhStub",
                        "inline,io/airlift/regulator/BenchmarkTrinoRegexpSearchEdges.contains");
        assertThat(readAllLines(jvmArguments, UTF_8))
                .contains(
                        "-XX:+UnlockDiagnosticVMOptions",
                        "-XX:+UnlockExperimentalVMOptions",
                        "-DcompilerBlackholesEnabled=true",
                        "-XX:CompileCommandFile=" + compilerHints.toAbsolutePath());
    }
}
