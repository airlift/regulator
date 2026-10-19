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
import java.util.Arrays;

import static java.util.Objects.requireNonNull;

final class LiteralSearchKernel
{
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(LiteralSearchKernel.class);
    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long LOW_BITS = 0x0101010101010101L;
    private static final long HIGH_BITS = 0x8080808080808080L;
    // The search switches to KMP once failed candidates compare more bytes than the search has
    // covered plus this budget, so candidate work stays linear in the input. Candidates that
    // compare at most one byte per byte covered, such as "bba" over repeated "aabx", never switch,
    // while closely spaced candidates with long partial matches switch within a few candidates.
    static final int CANDIDATE_COMPARISON_BUDGET = 64;

    private final byte[] literal;
    private final int[] prefixShifts;
    private final long frontBroadcast;
    private final long backBroadcast;

    LiteralSearchKernel(Slice literal)
    {
        requireNonNull(literal, "literal is null");
        this.literal = literal.getBytes();
        this.prefixShifts = computePrefixShifts(this.literal);
        this.frontBroadcast = broadcast(this.literal[0]);
        this.backBroadcast = broadcast(this.literal[this.literal.length - 1]);
    }

    int literalLength()
    {
        return literal.length;
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + SizeOf.sizeOf(literal) + SizeOf.sizeOf(prefixShifts);
    }

    byte[] literal()
    {
        return literal.clone();
    }

    int[] prefixShifts()
    {
        return prefixShifts.clone();
    }

    int find(Slice input, int offset, int length)
    {
        requireNonNull(input, "input is null");
        if (offset < 0 || length < 0 || offset > input.length() - length || literal.length > length || literal.length == 0) {
            return -1;
        }

        byte[] inputBytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int searchStart = inputOffset + offset;
        int searchEnd = searchStart + length;
        int matchOffset;
        if (literal.length == 1) {
            matchOffset = findSingleByte(inputBytes, searchStart, searchEnd);
        }
        else {
            matchOffset = findFrontAndBack(inputBytes, searchStart, searchEnd);
            if (matchOffset < -1) {
                matchOffset = findKmp(inputBytes, -2 - matchOffset, searchEnd);
            }
        }
        return matchOffset < 0 ? -1 : matchOffset - inputOffset;
    }

    /**
     * Returns the Slice-relative offset where {@link #find} continues with KMP, or -1 when the
     * front-and-back scan finishes, matching or not, without switching, when the range is
     * invalid, or when the literal is a single byte; only tests call it.
     */
    int kmpHandoffOffsetForDiagnostics(Slice input, int offset, int length)
    {
        requireNonNull(input, "input is null");
        if (offset < 0 || length < 0 || offset > input.length() - length || literal.length > length || literal.length < 2) {
            return -1;
        }

        int inputOffset = input.byteArrayOffset();
        int searchStart = inputOffset + offset;
        int result = findFrontAndBack(input.byteArray(), searchStart, searchStart + length);
        return result < -1 ? -2 - result - inputOffset : -1;
    }

    boolean matchesAt(Slice input, int offset)
    {
        if (offset < 0 || offset > input.length() - literal.length) {
            return false;
        }
        int inputOffset = input.byteArrayOffset() + offset;
        return Arrays.equals(input.byteArray(), inputOffset, inputOffset + literal.length, literal, 0, literal.length);
    }

    /**
     * Returns the match position, -1 when there is no match, or {@code -2 - resume} when the
     * search must continue with {@link #findKmp} at {@code resume}. Returning the handoff, rather
     * than running KMP here, lets {@link #kmpHandoffOffsetForDiagnostics} observe it.
     * <p>
     * TrinoLikeKernelGenerator emits a copy of this scan, and of findSingleByte, for each literal
     * of a generated LIKE pattern; keep the copies in step. The generated copy runs KMP directly.
     */
    private int findFrontAndBack(byte[] input, int searchStart, int searchEnd)
    {
        int candidateCount = searchEnd - searchStart - literal.length + 1;
        int wordEnd = searchStart + (candidateCount / Long.BYTES) * Long.BYTES;
        int backOffset = literal.length - 1;
        // searchStart plus the failed candidates' compared bytes, minus the budget, so the search
        // switches when it passes the candidate. It never passes the current candidate by more
        // than the literal length, so it cannot overflow. Counting the bytes alone and comparing
        // them with candidate - searchStart kept searchStart live in the loop, and on aarch64 C2
        // then rebuilt the -LOW_BITS constant on every word.
        int comparisonEnd = searchStart - CANDIDATE_COMPARISON_BUDGET;
        int position = searchStart;

        for (; position < wordEnd; position += Long.BYTES) {
            long frontDifference = ((long) LONG_HANDLE.get(input, position)) ^ frontBroadcast;
            long backDifference = ((long) LONG_HANDLE.get(input, position + backOffset)) ^ backBroadcast;
            long candidates = zeroByteMask(frontDifference) & zeroByteMask(backDifference);
            while (candidates != 0) {
                int candidate = position + (Long.numberOfTrailingZeros(candidates) >>> 3);
                int matchedLength = matchingPrefixLength(input, candidate);
                if (matchedLength == literal.length) {
                    return candidate;
                }
                comparisonEnd += matchedLength + 1;
                if (comparisonEnd > candidate) {
                    return -2 - (candidate + 1);
                }
                candidates &= candidates - 1;
            }
        }

        int candidateEnd = searchEnd - literal.length + 1;
        for (; position < candidateEnd; position++) {
            if (input[position] == literal[0] && input[position + backOffset] == literal[backOffset]) {
                int matchedLength = matchingPrefixLength(input, position);
                if (matchedLength == literal.length) {
                    return position;
                }
                comparisonEnd += matchedLength + 1;
                if (comparisonEnd > position) {
                    return -2 - (position + 1);
                }
            }
        }
        return -1;
    }

    // TrinoLikeKernelGenerator emits a copy of this scan for each one-byte literal of a generated
    // LIKE pattern; keep the copy in step.
    private int findSingleByte(byte[] input, int searchStart, int searchEnd)
    {
        int wordEnd = searchStart + ((searchEnd - searchStart) / (2 * Long.BYTES)) * (2 * Long.BYTES);
        int position = searchStart;
        for (; position < wordEnd; position += 2 * Long.BYTES) {
            long firstDifference = ((long) LONG_HANDLE.get(input, position)) ^ frontBroadcast;
            long secondDifference = ((long) LONG_HANDLE.get(input, position + Long.BYTES)) ^ frontBroadcast;
            long firstMatches = zeroByteMask(firstDifference);
            long secondMatches = zeroByteMask(secondDifference);
            if ((firstMatches | secondMatches) != 0) {
                if (firstMatches != 0) {
                    return position + (Long.numberOfTrailingZeros(firstMatches) >>> 3);
                }
                return position + Long.BYTES + (Long.numberOfTrailingZeros(secondMatches) >>> 3);
            }
        }
        for (; position < searchEnd; position++) {
            if (input[position] == literal[0]) {
                return position;
            }
        }
        return -1;
    }

    // Copied as TrinoLikeKernelSupport.findKmp; keep the copy in step.
    private int findKmp(byte[] input, int searchStart, int searchEnd)
    {
        int matchedLength = 0;
        for (int position = searchStart; position < searchEnd; position++) {
            while (matchedLength >= 0 && input[position] != literal[matchedLength]) {
                matchedLength = prefixShifts[matchedLength];
            }
            matchedLength++;
            if (matchedLength == literal.length) {
                return position - literal.length + 1;
            }
        }
        return -1;
    }

    // Copied as TrinoLikeKernelSupport.matchingPrefixLength; keep the copy in step.
    private int matchingPrefixLength(byte[] input, int inputOffset)
    {
        for (int index = 0; index < literal.length; index++) {
            if (input[inputOffset + index] != literal[index]) {
                return index;
            }
        }
        return literal.length;
    }

    private static int[] computePrefixShifts(byte[] literal)
    {
        int[] shifts = new int[literal.length + 1];
        shifts[0] = -1;
        int prefixLength = -1;
        for (int position = 1; position < shifts.length; position++) {
            while (prefixLength >= 0 && literal[position - 1] != literal[prefixLength]) {
                prefixLength = shifts[prefixLength];
            }
            prefixLength++;
            shifts[position] = prefixLength;
        }
        return shifts;
    }

    private static long broadcast(byte value)
    {
        return (value & 0xFFL) * LOW_BITS;
    }

    // TrinoLikeKernelGenerator emits this mask in a deliberately different form with the same
    // bits (see its zeroByteMask); keep the two in step.
    private static long zeroByteMask(long value)
    {
        return (value - LOW_BITS) & ~value & HIGH_BITS;
    }
}
