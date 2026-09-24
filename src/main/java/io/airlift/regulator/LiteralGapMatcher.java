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

import io.airlift.regulator.TrinoLikeParser.Any;
import io.airlift.regulator.TrinoLikeParser.Element;
import io.airlift.regulator.TrinoLikeParser.Literal;
import io.airlift.regulator.TrinoLikeParser.ZeroOrMore;
import io.airlift.slice.SizeOf;
import io.airlift.slice.Slice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds literal chains separated by a fixed number of UTF-8 code points.
 */
final class LiteralGapMatcher
{
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(LiteralGapMatcher.class);

    private final LiteralSearchKernel[] literals;
    private final int[] codePointGaps;
    private final boolean exact;

    private LiteralGapMatcher(List<Slice> literals, int[] codePointGaps, boolean exact)
    {
        this.literals = new LiteralSearchKernel[literals.size()];
        for (int literalIndex = 0; literalIndex < literals.size(); literalIndex++) {
            this.literals[literalIndex] = new LiteralSearchKernel(literals.get(literalIndex));
        }
        this.codePointGaps = codePointGaps;
        this.exact = exact;
    }

    static LiteralGapMatcher analyze(List<Element> pattern, int start, int end, boolean exact)
    {
        return analyze(pattern, start, end, exact, 2);
    }

    static LiteralGapMatcher analyzeUnrestrictedForDiagnostics(List<Element> pattern, int start, int end, boolean exact)
    {
        return analyze(pattern, start, end, exact, 1);
    }

    private static LiteralGapMatcher analyze(List<Element> pattern, int start, int end, boolean exact, int minimumFirstLiteralBytes)
    {
        if (exact || start >= end || pattern.get(start) != ZeroOrMore.INSTANCE) {
            return null;
        }

        List<Slice> literals = new ArrayList<>();
        int[] codePointGaps = new int[(end - start) / 2];
        int gapCount = 0;
        int elementIndex = start + 1;
        if (!(pattern.get(elementIndex) instanceof Literal firstLiteral)) {
            return null;
        }
        if (firstLiteral.bytes().length() < minimumFirstLiteralBytes) {
            return null;
        }
        literals.add(firstLiteral.bytes());
        elementIndex++;

        while (elementIndex <= end) {
            if (!(pattern.get(elementIndex) instanceof Any any) ||
                    elementIndex + 1 > end ||
                    !(pattern.get(elementIndex + 1) instanceof Literal literal)) {
                return null;
            }
            codePointGaps[gapCount++] = any.count();
            literals.add(literal.bytes());
            elementIndex += 2;
        }
        if (literals.size() < 2) {
            return null;
        }
        return new LiteralGapMatcher(literals, Arrays.copyOf(codePointGaps, gapCount), false);
    }

    int[] codePointGapsForDiagnostics()
    {
        return codePointGaps.clone();
    }

    long estimatedRetainedSize()
    {
        long retainedSize = INSTANCE_SIZE + SizeOf.sizeOf(literals) + SizeOf.sizeOf(codePointGaps);
        for (LiteralSearchKernel literal : literals) {
            retainedSize += literal.estimatedRetainedSize();
        }
        return retainedSize;
    }

    boolean matches(Slice input, int offset, int length)
    {
        int end = offset + length;
        LiteralSearchKernel firstLiteral = literals[0];
        for (int searchOffset = offset; searchOffset <= end - firstLiteral.literalLength(); ) {
            int matchOffset = firstLiteral.find(input, searchOffset, end - searchOffset);
            if (matchOffset < 0) {
                return false;
            }

            int position = matchOffset + firstLiteral.literalLength();
            boolean matched = true;
            for (int literalIndex = 1; literalIndex < literals.length; literalIndex++) {
                for (int codePointIndex = 0; codePointIndex < codePointGaps[literalIndex - 1]; codePointIndex++) {
                    if (position >= end) {
                        matched = false;
                        break;
                    }
                    int inputPosition = input.byteArrayOffset() + position;
                    long decoded = Utf8.decode(input.byteArray(), inputPosition, input.byteArrayOffset() + end);
                    if (Utf8.decodedCodePoint(decoded) == Utf8.RUNE_ERROR && Utf8.decodedWidth(decoded) == 1) {
                        matched = false;
                        break;
                    }
                    position += Utf8.decodedWidth(decoded);
                }
                LiteralSearchKernel literal = literals[literalIndex];
                if (!matched || position > end - literal.literalLength() || !literal.matchesAt(input, position)) {
                    matched = false;
                    break;
                }
                position += literal.literalLength();
            }
            if (matched && (!exact || position == end)) {
                return true;
            }
            searchOffset = matchOffset + 1;
        }
        return false;
    }
}
