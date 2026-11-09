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

import java.lang.management.ManagementFactory;
import java.util.List;

final class TestingJvmArguments
{
    private static final List<String> VECTOR_WIDTH_OPTION_PREFIXES = List.of("-XX:MaxVectorSize=", "-XX:UseSVE=", "-XX:UseAVX=");

    private TestingJvmArguments() {}

    /// Returns this JVM's options that set its vector width, such as the `-XX:MaxVectorSize=8` of
    /// CI's 8-byte build. They come from the JVM's input arguments, which include
    /// `JAVA_TOOL_OPTIONS` and `JDK_JAVA_OPTIONS` as well as the command line. A test that forks a
    /// JVM passes these to it so the child runs at this JVM's width; a width option the test adds
    /// after them wins.
    static List<String> vectorWidthOptions()
    {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> VECTOR_WIDTH_OPTION_PREFIXES.stream().anyMatch(argument::startsWith))
                .toList();
    }
}
