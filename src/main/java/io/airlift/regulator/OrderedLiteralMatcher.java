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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.List;

import static io.airlift.regulator.ExpressionAnalysis.Gap.NONE;
import static io.airlift.regulator.ExpressionAnalysis.Gap.ZERO_OR_MORE_BYTES;
import static io.airlift.regulator.ExpressionAnalysis.Gap.ZERO_OR_MORE_CODE_POINTS;

/// Finds a sequence of byte literals in left-to-right order.
final class OrderedLiteralMatcher
{
    static final int NO_MATCH = 0;
    static final int MATCH = 1;
    static final int UNDECIDED = 2;

    private static final int INSTANCE_SIZE = SizeOf.instanceSize(OrderedLiteralMatcher.class);
    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long HIGH_BITS = 0x8080808080808080L;
    // The bytes of an ASCII run asciiRunEnd reads a word at a time before it considers the vector
    // scan.
    private static final int INLINE_ASCII_BYTES = 16;

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

    List<LiteralSearchKernel> literals()
    {
        return List.of(literals);
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

    // TrinoLikeKernelGenerator emits a copy of this search for generated LIKE patterns; keep the
    // copy in step.
    boolean matches(Slice input, int offset, int length)
    {
        int searchOffset = offset;
        int remaining = length;
        for (int literalIndex = 0; literalIndex < literals.length; literalIndex++) {
            if (remaining == 0) {
                return false;
            }
            LiteralSearchKernel literal = literals[literalIndex];
            // A literal of two or more bytes scans for its first and last bytes together and
            // verifies candidates out of line; a one-byte literal scans for that byte.
            int matchOffset = literal.literalLength() > 1
                    ? literal.findContains(input, searchOffset, remaining)
                    : literal.findSingleByte(input, searchOffset, remaining);
            if (matchOffset < 0) {
                return false;
            }
            int matchEnd = matchOffset + literal.literalLength();
            remaining -= matchEnd - searchOffset;
            searchOffset = matchEnd;
        }
        return !exact || remaining == 0;
    }

    /// Trino's contains: [#matches] over all of `input`, which also reads each gap between two
    /// found literals as the engine's UTF-8 `(?s:.)` reads it. Every gap of a Trino pattern is a
    /// gap of any characters, and the engine's dot rejects malformed UTF-8 there. Returns
    /// [#NO_MATCH] when the literals do not occur in order, [#MATCH] when each gap is whole
    /// characters that dot accepts, and otherwise [#UNDECIDED], for the engine to decide from the
    /// start of `input`: a later placement of the literals may still have such gaps. Each literal
    /// is found at its earliest place after the one before it, so no such placement means no match,
    /// and accepted gaps spell a match that the engine finds. The gaps before the first literal and
    /// after the last can match empty, so they are not read.
    int findWithCharacterGaps(Slice input)
    {
        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int searchOffset = 0;
        int remaining = input.length();
        boolean gapsAccepted = true;
        for (int literalIndex = 0; literalIndex < literals.length; literalIndex++) {
            if (remaining == 0) {
                return NO_MATCH;
            }
            LiteralSearchKernel literal = literals[literalIndex];
            int matchOffset = literal.literalLength() > 1
                    ? literal.findContains(input, searchOffset, remaining)
                    : literal.findSingleByte(input, searchOffset, remaining);
            if (matchOffset < 0) {
                return NO_MATCH;
            }
            // After a rejected gap the search continues only to tell no match from undecided.
            if (literalIndex > 0 && gapsAccepted) {
                gapsAccepted = isWholeDotCharacters(bytes, inputOffset + searchOffset, inputOffset + matchOffset);
            }
            int matchEnd = matchOffset + literal.literalLength();
            remaining -= matchEnd - searchOffset;
            searchOffset = matchEnd;
        }
        return gapsAccepted ? MATCH : UNDECIDED;
    }

    /// Returns whether the bytes from `start` to `end` are whole characters that the UTF-8
    /// `(?s:.)` accepts: ASCII bytes, and the non-ASCII characters [Utf8#re2CharacterWidth] reads.
    /// That accepts overlong three- and four-byte forms, encoded surrogates, and values above
    /// U+10FFFF, as the engine's dot does, so it falls back less often than a strict UTF-8 check.
    private static boolean isWholeDotCharacters(byte[] bytes, int start, int end)
    {
        int position = start;
        while (position < end) {
            if (bytes[position] >= 0) {
                position = asciiRunEnd(bytes, position, end);
            }
            else {
                int width = Utf8.re2CharacterWidth(bytes, position, end);
                if (width == 0) {
                    return false;
                }
                position += width;
            }
        }
        return true;
    }

    /// Returns the first position at or after `position`, before `end`, holding a byte with its
    /// high bit set, or `end`. The run is read a word at a time. One that outlasts the first
    /// [#INLINE_ASCII_BYTES] with at least [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES] left skips
    /// the rest with the vector high-bit scan, as `DotStarLiteralSpanMatcher.skipDotAscii` does:
    /// the short runs between the words of non-ASCII text would pay for a vector call that skips a
    /// byte or two.
    private static int asciiRunEnd(byte[] bytes, int position, int end)
    {
        int inlineEnd = position + INLINE_ASCII_BYTES;
        while (end - position >= Long.BYTES) {
            long highBits = (long) LONG_HANDLE.get(bytes, position) & HIGH_BITS;
            if (highBits != 0) {
                return position + (Long.numberOfTrailingZeros(highBits) >>> 3);
            }
            position += Long.BYTES;
            if (position >= inlineEnd && end - position >= CompiledPattern.MINIMUM_LONG_SEARCH_BYTES && VectorSupport.isAvailable()) {
                int stop = VectorByteSetScanner.findHighBitOr(bytes, position, end - position, (byte) 0, (byte) 0, (byte) 0, 0);
                return stop < 0 ? end : stop;
            }
        }
        while (position < end && bytes[position] >= 0) {
            position++;
        }
        return position;
    }
}
