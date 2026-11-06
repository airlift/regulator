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

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.CodeAttribute;
import java.util.List;

import static io.airlift.regulator.TestScanKernel.classfile;
import static org.assertj.core.api.Assertions.assertThat;

public class TestHotMethodSize
{
    // C2 inlines a hot callee only when its bytecode is at most FreqInlineSize, 325 bytes by
    // default on x86-64, AArch64 and RISC-V. PPC64 and s390x use 175 through JDK 26.
    private static final int FREQ_INLINE_SIZE = 325;

    /// Keeps each full-Slice find, count, matches, and lookingAt route small enough for C2 to
    /// inline into the one-line override of the pattern's shape class, and that override into
    /// callers such as `Re2.count` and `TrinoRegexp.count`.
    @Test
    public void testRoutesFitInlineSize()
    {
        for (String route : List.of(
                "findOnEngine",
                "findOnExactLiteral",
                "findOnLiteralKernel",
                "findOnLiteralAlternation",
                "findOnFixedWidth",
                "findOnClassRun",
                "findOnByteTable",
                "findOnScanPlan",
                "matchesOnExactLiteral",
                "lookingAtOnExactLiteral",
                "matchesOnLiteralKernel",
                "lookingAtOnLiteralKernel",
                "matchesOnScanPlan",
                "lookingAtOnScanPlan",
                "matchOnScanPlan",
                "countOnEngine",
                "countOnExactLiteral",
                "countOnLiteralKernel",
                "countOnLiteralAlternation",
                "countOnFixedWidth",
                "countOnClassRun",
                "countOnByteTable",
                "countOnScanPlan")) {
            assertThat(codeLength(CompiledPattern.class, route))
                    .as("CompiledPattern.%s bytecode bytes", route)
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        }
    }

    /// Keeps every RegexpMatcher constructor under the C2 hot-method inline limit. Trino builds a
    /// new matcher for each row, so a constructor C2 will not inline puts a call on every row.
    @Test
    public void testMatcherConstructorsFitInlineSize()
    {
        assertThat(codeLengths(RegexpMatcher.class, "<init>"))
                .as("RegexpMatcher constructor bytecode bytes")
                .isNotEmpty()
                .allSatisfy(length -> assertThat(length).isPositive().isLessThanOrEqualTo(FREQ_INLINE_SIZE));
    }

    private static int codeLength(Class<?> type, String methodName)
    {
        List<Integer> lengths = codeLengths(type, methodName);
        assertThat(lengths).as("methods named %s", methodName).hasSize(1);
        return lengths.getFirst();
    }

    private static List<Integer> codeLengths(Class<?> type, String methodName)
    {
        return ClassFile.of().parse(classfile(type)).methods().stream()
                .filter(method -> method.methodName().equalsString(methodName))
                .map(method -> method.findAttribute(Attributes.code()).map(CodeAttribute::codeLength).orElse(0))
                .toList();
    }
}
