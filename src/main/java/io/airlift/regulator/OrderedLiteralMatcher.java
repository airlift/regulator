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

import io.airlift.slice.SizeOf;
import io.airlift.slice.Slice;

import static io.airlift.regulator.ExpressionAnalysis.Gap.NONE;
import static io.airlift.regulator.ExpressionAnalysis.Gap.ZERO_OR_MORE_BYTES;
import static io.airlift.regulator.ExpressionAnalysis.Gap.ZERO_OR_MORE_CODE_POINTS;

/**
 * Finds a sequence of byte literals in left-to-right order.
 */
final class OrderedLiteralMatcher
{
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(OrderedLiteralMatcher.class);

    private final LiteralSearchKernel[] literals;
    private final boolean exact;

    OrderedLiteralMatcher(Slice[] literals, boolean exact)
    {
        this.literals = new LiteralSearchKernel[literals.length];
        this.exact = exact;
        for (int literalIndex = 0; literalIndex < literals.length; literalIndex++) {
            this.literals[literalIndex] = new LiteralSearchKernel(literals[literalIndex]);
        }
    }

    static OrderedLiteralMatcher analyze(ExpressionAnalysis analysis)
    {
        ExpressionAnalysis.LiteralSequence sequence = analysis.literalSequence();
        if (analysis.hasFoldCaseLiteral() ||
                analysis.hasUnsupportedPositionAssertions() ||
                sequence == null ||
                sequence.literalCount() < 2 ||
                sequence.anchoredAtStart() ||
                sequence.anchoredAtEnd() ||
                !isOptionalBoundaryGap(sequence.leadingGap()) ||
                !isOptionalBoundaryGap(sequence.gapAfter(sequence.literalCount() - 1))) {
            return null;
        }

        Slice[] literals = new Slice[sequence.literalCount()];
        for (int literalIndex = 0; literalIndex < literals.length; literalIndex++) {
            if (literalIndex < literals.length - 1 && !isUnrestrictedGap(sequence.gapAfter(literalIndex))) {
                return null;
            }
            literals[literalIndex] = sequence.retainedLiteral(literalIndex);
        }
        return new OrderedLiteralMatcher(literals, false);
    }

    private static boolean isOptionalBoundaryGap(ExpressionAnalysis.Gap gap)
    {
        return gap == NONE || isUnrestrictedGap(gap);
    }

    private static boolean isUnrestrictedGap(ExpressionAnalysis.Gap gap)
    {
        return gap == ZERO_OR_MORE_BYTES || gap == ZERO_OR_MORE_CODE_POINTS;
    }

    boolean usesSharedLiteralSearchForDiagnostics()
    {
        return true;
    }

    long estimatedRetainedSize()
    {
        long retainedSize = INSTANCE_SIZE + SizeOf.sizeOf(literals);
        for (LiteralSearchKernel literal : literals) {
            retainedSize += literal.estimatedRetainedSize();
        }
        return retainedSize;
    }

    boolean matches(Slice input, int offset, int length)
    {
        int searchOffset = offset;
        int remaining = length;
        for (int literalIndex = 0; literalIndex < literals.length; literalIndex++) {
            if (remaining == 0) {
                return false;
            }
            LiteralSearchKernel literal = literals[literalIndex];
            int matchOffset = literal.find(input, searchOffset, remaining);
            if (matchOffset < 0) {
                return false;
            }
            int matchEnd = matchOffset + literal.literalLength();
            remaining -= matchEnd - searchOffset;
            searchOffset = matchEnd;
        }
        return !exact || remaining == 0;
    }
}
