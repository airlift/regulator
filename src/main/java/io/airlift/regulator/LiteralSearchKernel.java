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
    // LOW_BITS, read from a field so the word loops of the contains search hold it in a register:
    // on aarch64 C2 cannot encode -LOW_BITS as an immediate, and rebuilt the constant with four
    // instructions every word. The constructor assigns it, because an initializer would make it a
    // compile-time constant that javac inlines.
    private final long lowBits;

    LiteralSearchKernel(Slice literal)
    {
        requireNonNull(literal, "literal is null");
        this.literal = literal.getBytes();
        this.prefixShifts = computePrefixShifts(this.literal);
        this.frontBroadcast = broadcast(this.literal[0]);
        this.backBroadcast = broadcast(this.literal[this.literal.length - 1]);
        this.lowBits = LOW_BITS;
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

    /// Returns the Slice-relative offset of the first occurrence of the literal in the range, or
    /// `-1`. Requires a one-byte literal; OrderedLiteralMatcher and LiteralGapMatcher call it for such
    /// literals and [#findContains] for longer ones. TrinoLikeKernelGenerator emits a copy of
    /// its scan for each one-byte literal of a generated LIKE pattern; keep the copy in step.
    int findSingleByte(Slice input, int offset, int length)
    {
        requireNonNull(input, "input is null");
        if (offset < 0 || length < 0 || offset > input.length() - length || literal.length > length) {
            return -1;
        }

        byte[] inputBytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int searchStart = inputOffset + offset;
        int searchEnd = searchStart + length;
        int wordEnd = searchStart + (length / (2 * Long.BYTES)) * (2 * Long.BYTES);
        int position = searchStart;
        for (; position < wordEnd; position += 2 * Long.BYTES) {
            long firstDifference = ((long) LONG_HANDLE.get(inputBytes, position)) ^ frontBroadcast;
            long secondDifference = ((long) LONG_HANDLE.get(inputBytes, position + Long.BYTES)) ^ frontBroadcast;
            long firstMatches = zeroByteMask(firstDifference);
            long secondMatches = zeroByteMask(secondDifference);
            if ((firstMatches | secondMatches) != 0) {
                if (firstMatches != 0) {
                    return position + (Long.numberOfTrailingZeros(firstMatches) >>> 3) - inputOffset;
                }
                return position + Long.BYTES + (Long.numberOfTrailingZeros(secondMatches) >>> 3) - inputOffset;
            }
        }
        for (; position < searchEnd; position++) {
            if (inputBytes[position] == literal[0]) {
                return position - inputOffset;
            }
        }
        return -1;
    }

    /// Returns the Slice-relative offset of the first occurrence of the literal in the range, or
    /// `-1`, for a literal of at least two bytes and a valid range. LIKE and Trino regexp contains,
    /// Trino dot-star spans, OrderedLiteralMatcher, and LiteralGapMatcher's first literal call it
    /// for such literals. Its word scan makes no call until it finds a possible candidate, and a
    /// separate method verifies the candidates.
    int findContains(Slice input, int offset, int length)
    {
        if (literal.length > length) {
            return -1;
        }
        byte[] inputBytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int searchStart = inputOffset + offset;
        int searchEnd = searchStart + length;
        int matchOffset = skipToContainsCandidate(inputBytes, searchStart, searchEnd);
        if (matchOffset < -1) {
            matchOffset = findKmp(inputBytes, -2 - matchOffset, searchEnd);
        }
        return matchOffset < 0 ? -1 : matchOffset - inputOffset;
    }

    /// Returns the Slice-relative offset where [#findContains] continues with KMP; only tests
    /// call it. Returns -1 when the search ends, with or without a match, before it switches to
    /// KMP, when the range is invalid, and when the literal is a single byte.
    int kmpHandoffOffsetForDiagnostics(Slice input, int offset, int length)
    {
        requireNonNull(input, "input is null");
        if (offset < 0 || length < 0 || offset > input.length() - length || literal.length > length || literal.length < 2) {
            return -1;
        }

        int inputOffset = input.byteArrayOffset();
        int searchStart = inputOffset + offset;
        int result = skipToContainsCandidate(input.byteArray(), searchStart, searchStart + length);
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

    /// Skips to the first word, or tail position, holding a possible candidate, and returns
    /// [#verifyContainsCandidates] from there, or -1. The word loop makes no call until it
    /// finds a possible candidate and has no inner loop, so C2 keeps it innermost and unrolls it,
    /// and it reads LOW_BITS from [#lowBits]. TrinoLikeKernelGenerator emits a copy of this
    /// scan; keep the copies in step.
    private int skipToContainsCandidate(byte[] input, int searchStart, int searchEnd)
    {
        int backOffset = literal.length - 1;
        int wordEnd = searchStart + ((searchEnd - searchStart - backOffset) / Long.BYTES) * Long.BYTES;
        long front = frontBroadcast;
        long back = backBroadcast;
        long lowBits = this.lowBits;
        int position = searchStart;

        for (; position < wordEnd; position += Long.BYTES) {
            long frontDifference = ((long) LONG_HANDLE.get(input, position)) ^ front;
            long backDifference = ((long) LONG_HANDLE.get(input, position + backOffset)) ^ back;
            if ((((frontDifference - lowBits) & ~frontDifference) & ((backDifference - lowBits) & ~backDifference) & HIGH_BITS) != 0) {
                return verifyContainsCandidates(input, searchStart, position, searchEnd);
            }
        }

        int candidateEnd = searchEnd - backOffset;
        byte first = literal[0];
        byte last = literal[backOffset];
        for (; position < candidateEnd; position++) {
            if (input[position] == first && input[position + backOffset] == last) {
                return verifyContainsCandidates(input, searchStart, position, searchEnd);
            }
        }
        return -1;
    }

    /// Verifies possible candidates selected by the first-and-last-byte masks, in order from
    /// `verifyStart`, the word or tail position where [#skipToContainsCandidate] found
    /// the first one. Returns the match position, -1 when there is no match, or `-2 - resume`
    /// when the search must continue with [#findKmp] at `resume`; returning the handoff,
    /// rather than running KMP here, lets [#kmpHandoffOffsetForDiagnostics] observe it. The
    /// KMP handoff counts from `searchStart`, because the bytes skipped to reach
    /// `verifyStart` count as covered. The word loop reads LOW_BITS from [#lowBits], as
    /// the scan does. TrinoLikeKernelGenerator emits a copy of this method; keep the copies in step.
    private int verifyContainsCandidates(byte[] input, int searchStart, int verifyStart, int searchEnd)
    {
        int candidateCount = searchEnd - searchStart - literal.length + 1;
        int wordEnd = searchStart + (candidateCount / Long.BYTES) * Long.BYTES;
        int backOffset = literal.length - 1;
        long lowBits = this.lowBits;
        // searchStart plus the failed candidates' compared bytes, minus the budget, so the search
        // switches when it passes the candidate. It never passes the current candidate by more
        // than the literal length, so it cannot overflow. In the former in-line scan, counting the
        // bytes alone and comparing them with candidate - searchStart kept searchStart live in the
        // loop, and on aarch64 C2 then rebuilt the -LOW_BITS constant on every word.
        int comparisonEnd = searchStart - CANDIDATE_COMPARISON_BUDGET;
        int position = verifyStart;

        for (; position < wordEnd; position += Long.BYTES) {
            long frontDifference = ((long) LONG_HANDLE.get(input, position)) ^ frontBroadcast;
            long backDifference = ((long) LONG_HANDLE.get(input, position + backOffset)) ^ backBroadcast;
            long candidates = ((frontDifference - lowBits) & ~frontDifference) & ((backDifference - lowBits) & ~backDifference) & HIGH_BITS;
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
