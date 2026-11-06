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
    private static final VarHandle INT_HANDLE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long LOW_BITS = 0x0101010101010101L;
    private static final long HIGH_BITS = 0x8080808080808080L;
    // The search switches to KMP once failed candidates compare more bytes than the search has
    // covered plus this budget, so candidate work stays linear in the input. Candidates that
    // compare at most one byte per byte covered, such as "bba" over repeated "aabx", never switch,
    // while closely spaced candidates with long partial matches switch within a few candidates.
    // The prefix scans in Prog and VectorPrefixScanner apply the same rule through the static
    // methods below.
    static final int CANDIDATE_COMPARISON_BUDGET = 64;

    // The prefix shifts of every literal shorter than this table's 64 entries whose first byte
    // does not recur: no prefix of such a literal has a border, so every shift after the first is
    // zero. Kernels share it and never write it; KMP reads only the shifts below the literal's
    // length.
    private static final int[] BORDER_FREE_PREFIX_SHIFTS = borderFreePrefixShifts(64);

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
        this(requireNonNull(literal, "literal is null").getBytes());
    }

    private LiteralSearchKernel(byte[] literal)
    {
        this.literal = requireNonNull(literal, "literal is null");
        this.prefixShifts = computePrefixShifts(literal);
        this.frontBroadcast = broadcast(this.literal[0]);
        this.backBroadcast = broadcast(this.literal[this.literal.length - 1]);
        this.lowBits = LOW_BITS;
    }

    int literalLength()
    {
        return literal.length;
    }

    /// Returns a kernel for `literal` that holds its bytes rather than a copy when it spans its
    /// whole backing array. The caller must never change those bytes.
    static LiteralSearchKernel sharing(Slice literal)
    {
        requireNonNull(literal, "literal is null");
        byte[] bytes = literal.byteArray();
        if (literal.byteArrayOffset() == 0 && literal.length() == bytes.length) {
            return new LiteralSearchKernel(bytes);
        }
        return new LiteralSearchKernel(literal.getBytes());
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + SizeOf.sizeOf(literal) + prefixShiftsRetainedSize(prefixShifts);
    }

    /// Returns the retained size of a table from [#computePrefixShifts]: 0 for the shared table
    /// of border-free literals.
    static long prefixShiftsRetainedSize(int[] prefixShifts)
    {
        return prefixShifts == BORDER_FREE_PREFIX_SHIFTS ? 0 : SizeOf.sizeOf(prefixShifts);
    }

    /// The literal itself, not a copy, for the vector kernels. The caller must never change these
    /// bytes.
    byte[] literalBytes()
    {
        return literal;
    }

    byte[] literal()
    {
        return literal.clone();
    }

    int[] prefixShifts()
    {
        return Arrays.copyOf(prefixShifts, literal.length + 1);
    }

    /// Returns the Slice-relative offset of the first occurrence of the literal in the range, or
    /// `-1`. Requires a one-byte literal; OrderedLiteralMatcher, LiteralGapMatcher, and
    /// InnerLiteralSearch call it for such literals and [#findContains(Slice,int,int)] for longer
    /// ones. It scans two words at a time and finishes the range with [#findSingleByteTail].
    /// TrinoLikeKernelGenerator emits a copy of this scan for each one-byte literal of a generated
    /// LIKE pattern; keep the copy in step.
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
        return findSingleByteTail(inputBytes, inputOffset, position, searchEnd, length);
    }

    /// Returns the Slice-relative offset of the first occurrence of the one-byte literal in the up
    /// to 15 bytes from `position` to `searchEnd` that [#findSingleByte] leaves, or `-1`;
    /// `length` is the length of the whole range. A range of at least eight bytes finishes with up
    /// to two more words, the last ending at the range end, where it can overlap bytes already
    /// searched, as memchr ends with an overlapping vector; a shorter range checks its bytes one at
    /// a time. On short rows a byte loop over the bytes the word loop leaves, its trip count varying
    /// with every row, costs more than the words. Callers inline findSingleByte only while it fits
    /// C2's 325-byte FreqInlineSize, so the finish lives in its own method.
    /// TrinoLikeKernelGenerator emits a copy of this finish; keep the copy in step.
    private int findSingleByteTail(byte[] inputBytes, int inputOffset, int position, int searchEnd, int length)
    {
        if (length >= Long.BYTES) {
            // Up to 15 bytes remain. Search a word at the position when more than eight remain, then
            // the word ending at the range end, which starts at or after the range start because the
            // range holds at least eight bytes. The bytes it shares with words already searched hold
            // no match, and the mask sets false bits only above a true match, so its lowest set bit
            // is the first match.
            if (searchEnd - position > Long.BYTES) {
                long matches = zeroByteMask(((long) LONG_HANDLE.get(inputBytes, position)) ^ frontBroadcast);
                if (matches != 0) {
                    return position + (Long.numberOfTrailingZeros(matches) >>> 3) - inputOffset;
                }
            }
            if (position < searchEnd) {
                int lastWord = searchEnd - Long.BYTES;
                long matches = zeroByteMask(((long) LONG_HANDLE.get(inputBytes, lastWord)) ^ frontBroadcast);
                if (matches != 0) {
                    return lastWord + (Long.numberOfTrailingZeros(matches) >>> 3) - inputOffset;
                }
            }
            return -1;
        }
        for (; position < searchEnd; position++) {
            if (inputBytes[position] == literal[0]) {
                return position - inputOffset;
            }
        }
        return -1;
    }

    /// Returns the Slice-relative offset of the first occurrence of the literal in the range, or
    /// `-1`, for a literal of at least two bytes and a valid range. LIKE and every language's
    /// regexp contains, Trino dot-star spans, OrderedLiteralMatcher, LiteralGapMatcher's first
    /// literal, and InnerLiteralSearch call it for such literals. Its word scan makes no call until
    /// it finds a possible candidate, and a separate method verifies the candidates.
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
            matchOffset = findKmp(inputBytes, -2 - matchOffset, searchEnd, literal, prefixShifts);
        }
        return matchOffset < 0 ? -1 : matchOffset - inputOffset;
    }

    /// Returns the array-absolute index in `input` of the first occurrence of the literal in the
    /// range, or `-1`, for a literal of at least two bytes. It searches as
    /// [#findContains(Slice,int,int)] does; [Prog#findLiteral] calls it for the transparent start
    /// literal, whose searches work on absolute indexes.
    int findContains(byte[] input, int offset, int length)
    {
        if (literal.length > length) {
            return -1;
        }
        int searchEnd = offset + length;
        int matchOffset = skipToContainsCandidate(input, offset, searchEnd);
        if (matchOffset < -1) {
            matchOffset = findKmp(input, -2 - matchOffset, searchEnd);
        }
        return matchOffset;
    }

    /// Returns the Slice-relative offset where [#findContains(Slice,int,int)] continues with KMP;
    /// only tests call it. Returns -1 when the search ends, with or without a match, before it
    /// switches to KMP, when the range is invalid, and when the literal is a single byte.
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
    /// and it reads LOW_BITS from [#lowBits]. After at least one word, the scan finishes with one
    /// word ending at the last candidate, which can overlap candidates already scanned, rather than
    /// a loop over up to seven candidates, whose trip count varies with every row; a search of
    /// fewer than eight candidates checks them one at a time. TrinoLikeKernelGenerator emits a
    /// copy of this scan; keep the copies in step.
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
        if (position > searchStart) {
            // The word loop scanned at least one word, so at least eight candidates exist, and the
            // word ending at the last candidate starts at or after the range start; its back read
            // ends at the range end. Up to seven candidates remain. The word sets no mask bit on the
            // candidates it shares with words already scanned: those words set none there, and a
            // borrow chain in this word starts no lower than in theirs. It can set a false bit on a
            // new candidate just above an overlap byte that equals the literal's first byte;
            // verification rejects it, and the comparison counts toward
            // [#CANDIDATE_COMPARISON_BUDGET], which can only bring the KMP handoff earlier.
            if (position < candidateEnd) {
                int lastWord = candidateEnd - Long.BYTES;
                long frontDifference = ((long) LONG_HANDLE.get(input, lastWord)) ^ front;
                long backDifference = ((long) LONG_HANDLE.get(input, lastWord + backOffset)) ^ back;
                if ((((frontDifference - lowBits) & ~frontDifference) & ((backDifference - lowBits) & ~backDifference) & HIGH_BITS) != 0) {
                    return verifyContainsCandidates(input, searchStart, lastWord, searchEnd);
                }
            }
            return -1;
        }
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
        // In the former in-line scan, counting the compared bytes alone and comparing them with
        // candidate - searchStart kept searchStart live in the loop, and on aarch64 C2 then rebuilt
        // the -LOW_BITS constant on every word.
        int comparisonEnd = initialComparisonEnd(searchStart);
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
                comparisonEnd = chargeFailedCandidate(comparisonEnd, matchedLength);
                if (comparisonBudgetSpent(comparisonEnd, candidate)) {
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
                comparisonEnd = chargeFailedCandidate(comparisonEnd, matchedLength);
                if (comparisonBudgetSpent(comparisonEnd, position)) {
                    return -2 - (position + 1);
                }
            }
        }
        return -1;
    }

    /// Returns `searchStart` minus [#CANDIDATE_COMPARISON_BUDGET], the comparison end that a
    /// candidate search over text from `searchStart` begins with. Each failed candidate adds its
    /// compared bytes to it, and the search switches to KMP when the comparison end passes the
    /// failed candidate. It never passes the current candidate by more than the literal length, so
    /// it cannot overflow.
    static int initialComparisonEnd(int searchStart)
    {
        return searchStart - CANDIDATE_COMPARISON_BUDGET;
    }

    /// Charges a failed candidate's compared bytes, the `matchedLength` matching bytes and the
    /// mismatch, to the comparison end from [#initialComparisonEnd].
    static int chargeFailedCandidate(int comparisonEnd, int matchedLength)
    {
        return comparisonEnd + matchedLength + 1;
    }

    /// True when the search must switch to KMP after the failed `candidate`, because the
    /// comparison end passed it.
    static boolean comparisonBudgetSpent(int comparisonEnd, int candidate)
    {
        return comparisonEnd > candidate;
    }

    /// Returns the absolute offset of the first occurrence of this kernel's literal that lies in
    /// `[searchStart, searchEnd)`, or -1; [Prog#findLiteral] continues a budgeted scan with it.
    int findKmp(byte[] input, int searchStart, int searchEnd)
    {
        return findKmp(input, searchStart, searchEnd, literal, prefixShifts);
    }

    /// Returns the absolute offset of the first occurrence of `literal` that lies in
    /// `[searchStart, searchEnd)`, or -1, using the shifts from [#computePrefixShifts].
    /// Copied as `TrinoLikeKernelSupport.findKmp`; keep the copy in step.
    static int findKmp(byte[] input, int searchStart, int searchEnd, byte[] literal, int[] prefixShifts)
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

    // Compares 8-byte words for literals of 8 or more bytes, two 4-byte reads for 4 to 7 bytes,
    // and single bytes below 4. The last read overlaps the bytes already compared and finds the
    // first difference past them. Copied as TrinoLikeKernelSupport.matchingPrefixLength; keep
    // the copy in step.
    private int matchingPrefixLength(byte[] input, int inputOffset)
    {
        int length = literal.length;
        if (length >= Long.BYTES) {
            int index = 0;
            for (; index < length - Long.BYTES; index += Long.BYTES) {
                long difference = ((long) LONG_HANDLE.get(input, inputOffset + index)) ^ ((long) LONG_HANDLE.get(literal, index));
                if (difference != 0) {
                    return index + (Long.numberOfTrailingZeros(difference) >>> 3);
                }
            }
            index = length - Long.BYTES;
            long difference = ((long) LONG_HANDLE.get(input, inputOffset + index)) ^ ((long) LONG_HANDLE.get(literal, index));
            return difference == 0 ? length : index + (Long.numberOfTrailingZeros(difference) >>> 3);
        }
        if (length >= Integer.BYTES) {
            int difference = ((int) INT_HANDLE.get(input, inputOffset)) ^ ((int) INT_HANDLE.get(literal, 0));
            if (difference != 0) {
                return Integer.numberOfTrailingZeros(difference) >>> 3;
            }
            int index = length - Integer.BYTES;
            difference = ((int) INT_HANDLE.get(input, inputOffset + index)) ^ ((int) INT_HANDLE.get(literal, index));
            return difference == 0 ? length : index + (Integer.numberOfTrailingZeros(difference) >>> 3);
        }
        for (int index = 0; index < length; index++) {
            if (input[inputOffset + index] != literal[index]) {
                return index;
            }
        }
        return length;
    }

    static int[] computePrefixShifts(byte[] literal)
    {
        if (literal.length < BORDER_FREE_PREFIX_SHIFTS.length && !firstByteRecurs(literal)) {
            return BORDER_FREE_PREFIX_SHIFTS;
        }
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

    private static boolean firstByteRecurs(byte[] literal)
    {
        for (int index = 1; index < literal.length; index++) {
            if (literal[index] == literal[0]) {
                return true;
            }
        }
        return false;
    }

    private static int[] borderFreePrefixShifts(int length)
    {
        int[] shifts = new int[length];
        shifts[0] = -1;
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
