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

import static io.airlift.regulator.ExpressionAnalysis.Gap.ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS;
import static java.util.Objects.requireNonNull;

final class DotStarLiteralSpanMatcher
{
    private final Slice literal;
    private final boolean latin1;

    private DotStarLiteralSpanMatcher(Slice literal, boolean latin1)
    {
        this.literal = requireNonNull(literal, "literal is null");
        this.latin1 = latin1;
    }

    static DotStarLiteralSpanMatcher analyze(ExpressionAnalysis analysis)
    {
        ExpressionAnalysis.LiteralSequence sequence = analysis.literalSequence();
        if (analysis.hasCaptures() ||
                analysis.hasFoldCaseLiteral() ||
                analysis.hasNonGreedyRepetition() ||
                analysis.hasUnsupportedPositionAssertions() ||
                sequence == null ||
                sequence.literalCount() != 1 ||
                sequence.anchoredAtStart() ||
                sequence.anchoredAtEnd() ||
                sequence.leadingGap() != ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS ||
                sequence.gapAfter(0) != ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS) {
            return null;
        }

        Slice literal = sequence.retainedLiteral(0);
        if (literal.length() == 0 || containsLineFeed(literal)) {
            return null;
        }
        return new DotStarLiteralSpanMatcher(literal, analysis.latin1());
    }

    private static boolean containsLineFeed(Slice literal)
    {
        for (int index = 0; index < literal.length(); index++) {
            if (literal.getByte(index) == '\n') {
                return true;
            }
        }
        return false;
    }

    long findSpan(Slice input, int start)
    {
        requireNonNull(input, "input is null");
        if (start < 0 || start > input.length()) {
            return Dfa.SEARCH_NO_MATCH;
        }

        int literalStart = input.indexOf(literal, start);
        if (literalStart < 0) {
            return Dfa.SEARCH_NO_MATCH;
        }

        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int matchStart = start;
        int position = start;
        while (position < literalStart) {
            int current = bytes[inputOffset + position] & 0xFF;
            if (current == '\n') {
                matchStart = ++position;
            }
            else if (latin1 || current < 0x80) {
                position++;
            }
            else {
                int width = validUtf8Width(bytes, inputOffset + position, inputOffset + input.length());
                if (width == 0) {
                    matchStart = ++position;
                }
                else {
                    position += width;
                }
            }
        }

        position = literalStart + literal.length();
        while (position < input.length()) {
            int current = bytes[inputOffset + position] & 0xFF;
            if (current == '\n') {
                break;
            }
            if (latin1 || current < 0x80) {
                position++;
                continue;
            }

            int width = validUtf8Width(bytes, inputOffset + position, inputOffset + input.length());
            if (width == 0) {
                break;
            }
            position += width;
        }
        return ((long) matchStart << 32) | (position & 0xFFFF_FFFFL);
    }

    private static int validUtf8Width(byte[] bytes, int position, int end)
    {
        long decoded = Utf8.decode(bytes, position, end);
        int width = Utf8.decodedWidth(decoded);
        if (width == 1 && Utf8.decodedCodePoint(decoded) == Utf8.RUNE_ERROR && (bytes[position] & 0xFF) >= 0x80) {
            return 0;
        }
        return width;
    }
}
