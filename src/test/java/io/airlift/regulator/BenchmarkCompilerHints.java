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

import org.openjdk.jmh.runner.CompilerHints;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public final class BenchmarkCompilerHints
{
    private static final String COMPILE_COMMAND_FILE_PREFIX = "-XX:CompileCommandFile=";

    private BenchmarkCompilerHints() {}

    public static void main(String[] arguments)
            throws IOException
    {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected compiler-hints and JVM-arguments output paths");
        }

        Path compilerHintsPath = Path.of(arguments[0]).toAbsolutePath();
        Path jvmArgumentsPath = Path.of(arguments[1]).toAbsolutePath();
        List<String> jvmArguments = new ArrayList<>();
        CompilerHints.addCompilerHints(jvmArguments);

        boolean compilerHintsCopied = false;
        for (int index = 0; index < jvmArguments.size(); index++) {
            String argument = jvmArguments.get(index);
            if (argument.startsWith(COMPILE_COMMAND_FILE_PREFIX)) {
                Path generatedHintsPath = Path.of(argument.substring(COMPILE_COMMAND_FILE_PREFIX.length()));
                Files.copy(generatedHintsPath, compilerHintsPath, REPLACE_EXISTING);
                jvmArguments.set(index, COMPILE_COMMAND_FILE_PREFIX + compilerHintsPath);
                compilerHintsCopied = true;
            }
        }
        if (!compilerHintsCopied) {
            throw new IllegalStateException("JMH did not produce a compiler command file");
        }
        Files.write(jvmArgumentsPath, jvmArguments, UTF_8);
    }
}
