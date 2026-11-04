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

import io.airlift.slice.Slice;

/// The matching engine of a Trino pattern. A [TrinoRegexp] holds one when it needs one, rather
/// than extending it: its Trino operations, its generated class, and every specialized subclass
/// of `TrinoRegexp` except its literal-alternation and literal-set subclasses run on the engine,
/// whose own operations are the shared engine's rather than Trino's.
sealed class TrinoCompiledPattern
        extends CompiledPattern
        permits TrinoCompiledPattern.ByteTableTrinoCompiledPattern,
                TrinoCompiledPattern.ClassRunTrinoCompiledPattern,
                TrinoCompiledPattern.ExactLiteralTrinoCompiledPattern,
                TrinoCompiledPattern.FixedWidthTrinoCompiledPattern,
                TrinoCompiledPattern.LiteralKernelTrinoCompiledPattern,
                TrinoCompiledPattern.ScanPlanTrinoCompiledPattern
{
    private TrinoCompiledPattern(Analysis analysis)
    {
        super(analysis);
    }

    /// Constructs an instance of the analyzed pattern's shape class.
    static TrinoCompiledPattern create(Analysis analysis)
    {
        return switch (analysis.shape()) {
            // TrinoRegexp.compile answers every literal alternation this shape could hold with its
            // own literal-alternation matcher before it compiles an engine.
            case ENGINE, LITERAL_ALTERNATION -> new TrinoCompiledPattern(analysis);
            case EXACT_LITERAL -> new ExactLiteralTrinoCompiledPattern(analysis);
            case LITERAL_KERNEL -> new LiteralKernelTrinoCompiledPattern(analysis);
            case FIXED_WIDTH -> new FixedWidthTrinoCompiledPattern(analysis);
            case CLASS_RUN -> new ClassRunTrinoCompiledPattern(analysis);
            case BYTE_TABLE -> new ByteTableTrinoCompiledPattern(analysis);
            case SCAN_PLAN -> new ScanPlanTrinoCompiledPattern(analysis);
        };
    }

    /// Returns `false`. A Trino pattern's generated class belongs to its [TrinoRegexp]; see
    /// [TrinoRegexp#usesGeneratedBytecode()].
    @Override
    public boolean usesGeneratedBytecode()
    {
        return false;
    }

    /// A pattern of the [exact-literal shape][Shape#EXACT_LITERAL].
    static final class ExactLiteralTrinoCompiledPattern
            extends TrinoCompiledPattern
    {
        private ExactLiteralTrinoCompiledPattern(Analysis analysis)
        {
            super(analysis);
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnExactLiteral(input);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnExactLiteral(text);
        }

        @Override
        public boolean matches(Slice input)
        {
            return matchesOnExactLiteral(input);
        }

        @Override
        public boolean lookingAt(Slice input)
        {
            return lookingAtOnExactLiteral(input);
        }
    }

    /// A pattern of the [literal-kernel shape][Shape#LITERAL_KERNEL].
    static final class LiteralKernelTrinoCompiledPattern
            extends TrinoCompiledPattern
    {
        private LiteralKernelTrinoCompiledPattern(Analysis analysis)
        {
            super(analysis);
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnLiteralKernel(input);
        }

        @Override
        public boolean matches(Slice input)
        {
            return matchesOnLiteralKernel(input);
        }

        @Override
        public boolean lookingAt(Slice input)
        {
            return lookingAtOnLiteralKernel(input);
        }
    }

    /// A pattern of the [fixed-width shape][Shape#FIXED_WIDTH].
    static final class FixedWidthTrinoCompiledPattern
            extends TrinoCompiledPattern
    {
        private FixedWidthTrinoCompiledPattern(Analysis analysis)
        {
            super(analysis);
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnFixedWidth(input);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnFixedWidth(text);
        }
    }

    /// A pattern of the [class-run shape][Shape#CLASS_RUN].
    static final class ClassRunTrinoCompiledPattern
            extends TrinoCompiledPattern
    {
        private final boolean retainedClassCountDfa;

        private ClassRunTrinoCompiledPattern(Analysis analysis)
        {
            super(analysis);
            this.retainedClassCountDfa = analysis.retainedClassCountDfa();
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnClassRun(input);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnClassRun(text, retainedClassCountDfa);
        }

        @Override
        boolean matchInternal(Prog prog, Slice text, int contextStart, int contextEnd, int start, int end, Anchor anchor, int[] groupOffsets, MatchWorkspaces workspaces)
        {
            return matchInternalOnClassRun(prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces);
        }

        @Override
        boolean usesCompactBoundedCharacterClassForDiagnostics()
        {
            return true;
        }
    }

    /// A pattern of the [byte-table shape][Shape#BYTE_TABLE].
    static final class ByteTableTrinoCompiledPattern
            extends TrinoCompiledPattern
    {
        private ByteTableTrinoCompiledPattern(Analysis analysis)
        {
            super(analysis);
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnByteTable(input);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnByteTable(text);
        }
    }

    /// A pattern of the [scan-plan shape][Shape#SCAN_PLAN].
    static final class ScanPlanTrinoCompiledPattern
            extends TrinoCompiledPattern
    {
        private final boolean ordinaryShortBooleanMatch;
        private final boolean singleAttemptCount;
        private final boolean literalKernelFind;

        private ScanPlanTrinoCompiledPattern(Analysis analysis)
        {
            super(analysis);
            this.ordinaryShortBooleanMatch = analysis.ordinaryShortBooleanMatch();
            this.singleAttemptCount = analysis.singleAttemptCount();
            this.literalKernelFind = analysis.literalKernelFind();
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnScanPlan(input, ordinaryShortBooleanMatch, literalKernelFind);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnScanPlan(text, ordinaryShortBooleanMatch, singleAttemptCount);
        }

        @Override
        public boolean matches(Slice input)
        {
            return matchesOnScanPlan(input);
        }

        @Override
        public boolean lookingAt(Slice input)
        {
            return lookingAtOnScanPlan(input);
        }

        @Override
        boolean matchInternal(Prog prog, Slice text, int contextStart, int contextEnd, int start, int end, Anchor anchor, int[] groupOffsets, MatchWorkspaces workspaces)
        {
            return matchInternalOnScanPlan(prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces, ordinaryShortBooleanMatch);
        }

        @Override
        ScanPlanCount scanPlanCount()
        {
            return countRouteOnScanPlan(singleAttemptCount);
        }

        @Override
        boolean usesOrdinaryShortBooleanMatchForDiagnostics()
        {
            return ordinaryShortBooleanMatch;
        }

        @Override
        boolean usesScanPlanForFindForDiagnostics()
        {
            return !literalKernelFind;
        }
    }
}
