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

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import static com.sun.management.VMOption.Origin.DEFAULT;
import static com.sun.management.VMOption.Origin.ERGONOMIC;

final class TestingJvmArguments
{
    private static final List<String> VECTOR_WIDTH_OPTIONS = List.of("MaxVectorSize", "UseSVE", "UseAVX");

    private TestingJvmArguments() {}

    /// Returns this JVM's options that set its vector width, such as the `-XX:MaxVectorSize=8` of
    /// CI's 8-byte build. The JVM reports each option's value and where it came from, so an option
    /// set on the command line, in `JAVA_TOOL_OPTIONS` or in `JDK_JAVA_OPTIONS` is returned, while a
    /// default or a value the JVM chose for this CPU is not. An option this CPU lacks, such as
    /// `UseSVE` on x86, is skipped. A test that forks a JVM passes these to it so the child runs at
    /// this JVM's width; a width option the test adds after them wins.
    static List<String> vectorWidthOptions()
    {
        HotSpotDiagnosticMXBean diagnostics = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        List<String> options = new ArrayList<>();
        for (String name : VECTOR_WIDTH_OPTIONS) {
            VMOption option;
            try {
                option = diagnostics.getVMOption(name);
            }
            catch (IllegalArgumentException _) {
                continue;
            }
            if (option.getOrigin() != DEFAULT && option.getOrigin() != ERGONOMIC) {
                options.add("-XX:" + name + "=" + option.getValue());
            }
        }
        return List.copyOf(options);
    }
}
