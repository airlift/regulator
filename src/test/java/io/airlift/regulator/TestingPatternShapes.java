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

import io.airlift.regulator.CompiledPattern.Shape;

import static io.airlift.regulator.CompiledPattern.Shape.BYTE_TABLE;
import static io.airlift.regulator.CompiledPattern.Shape.CLASS_RUN;
import static io.airlift.regulator.CompiledPattern.Shape.ENGINE;
import static io.airlift.regulator.CompiledPattern.Shape.EXACT_LITERAL;
import static io.airlift.regulator.CompiledPattern.Shape.FIXED_WIDTH;
import static io.airlift.regulator.CompiledPattern.Shape.LITERAL_ALTERNATION;
import static io.airlift.regulator.CompiledPattern.Shape.LITERAL_KERNEL;
import static io.airlift.regulator.CompiledPattern.Shape.SCAN_PLAN;

final class TestingPatternShapes
{
    private TestingPatternShapes() {}

    /// Returns the shape of `pattern`'s class: the engine for the language class itself, and
    /// otherwise the shape of its language's shape class. A generated class has the scan-plan
    /// shape of the class it extends.
    static Shape shape(CompiledPattern pattern)
    {
        return switch (pattern) {
            case Re2.ExactLiteralRe2 _, JavaRegexp.ExactLiteralJavaRegexp _, RustRegexp.ExactLiteralRustRegexp _, TrinoCompiledPattern.ExactLiteralTrinoCompiledPattern _ -> EXACT_LITERAL;
            case Re2.LiteralKernelRe2 _, JavaRegexp.LiteralKernelJavaRegexp _, RustRegexp.LiteralKernelRustRegexp _, TrinoCompiledPattern.LiteralKernelTrinoCompiledPattern _ -> LITERAL_KERNEL;
            case Re2.LiteralAlternationRe2 _, JavaRegexp.LiteralAlternationJavaRegexp _, RustRegexp.LiteralAlternationRustRegexp _ -> LITERAL_ALTERNATION;
            case Re2.FixedWidthRe2 _, JavaRegexp.FixedWidthJavaRegexp _, RustRegexp.FixedWidthRustRegexp _, TrinoCompiledPattern.FixedWidthTrinoCompiledPattern _ -> FIXED_WIDTH;
            case Re2.ClassRunRe2 _, JavaRegexp.ClassRunJavaRegexp _, RustRegexp.ClassRunRustRegexp _, TrinoCompiledPattern.ClassRunTrinoCompiledPattern _ -> CLASS_RUN;
            case Re2.ByteTableRe2 _, JavaRegexp.ByteTableJavaRegexp _, RustRegexp.ByteTableRustRegexp _, TrinoCompiledPattern.ByteTableTrinoCompiledPattern _ -> BYTE_TABLE;
            case Re2.ScanPlanRe2 _, JavaRegexp.ScanPlanJavaRegexp _, RustRegexp.ScanPlanRustRegexp _, TrinoCompiledPattern.ScanPlanTrinoCompiledPattern _ -> SCAN_PLAN;
            case Re2 _, JavaRegexp _, RustRegexp _, TrinoCompiledPattern _ -> ENGINE;
        };
    }
}
