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

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

final class VectorByteSetScanner
{
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    // The nibble tables are replicated to 64 bytes, one reason VectorSupport allows at most 64 lanes.
    private static final int NIBBLE_TABLE_BYTES = 64;
    private static final int HIGH_NIBBLE_TABLE_OFFSET = NIBBLE_TABLE_BYTES;
    // The low-nibble table, then the high-nibble table.
    static final int NIBBLE_TABLES_LENGTH = NIBBLE_TABLE_BYTES * 2;
    // A loop that skips ahead with these scans stops skipping once SHORT_SCAN_LIMIT scans in a
    // row each skipped fewer than SHORT_SCAN_BYTES bytes: the stops are dense, and a scan's setup
    // costs more than the few bytes it skips. The forward DFA's count self-loop scan and the
    // literal set's root skip apply the rule through shortScanCount.
    static final int SHORT_SCAN_BYTES = 16;
    static final int SHORT_SCAN_LIMIT = 4;

    private VectorByteSetScanner() {}

    /// Returns the number of short scans in a row after a scan that skipped `skippedBytes`, given
    /// the `shortScanCount` before it. A caller stops skipping when it reaches [#SHORT_SCAN_LIMIT].
    static int shortScanCount(int shortScanCount, int skippedBytes)
    {
        return skippedBytes < SHORT_SCAN_BYTES ? shortScanCount + 1 : 0;
    }

    static boolean shouldSampleCandidateDensity()
    {
        return SPECIES.length() > 16;
    }

    // Whether a DFA whose start bytes are too rare for the start literal filter should still take
    // one that holds two literals. Only with 64-byte vectors, as with AVX-512 on r8i, where the
    // two-literal scan took 0.56x to 0.60x the single-byte scanners' time for huck-saw, 0.85x to
    // 0.96x for name-alt2 (1.00x in Trino), and 0.87x to 0.94x for name-alt4. On r9g's 16-byte
    // vectors it won huck-saw (0.84x to 0.91x) but took 1.17x to 1.19x for name-alt2 and 1.19x
    // for name-alt4 in its SVE XOR form, and 1.22x for both with NEON (1.00x for name-alt2 in
    // Trino), so 16-byte vectors keep their scanners; 32-byte vectors, as with AVX2 or 256-bit
    // SVE, were not measured.
    // "Start Literal Filter" in REGULATOR_DECISIONS.md records the measurements.
    static boolean shouldFilterRareStartsWithTwoLiterals()
    {
        return SPECIES.length() == 64;
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Candidate count is always one, two, or three; two
    // and three take the XOR form under VectorSupport.SVE_128.
    // Copied as VectorScanKernelSupport.findByteSet; keep the copy in step.
    static int find(byte[] data, int offset, int length, byte first, byte second, byte third, int candidateCount)
    {
        return switch (candidateCount) {
            case 1 -> findOne(data, offset, length, first);
            case 2 -> VectorSupport.SVE_128 ? findTwoSve(data, offset, length, first, second) : findTwo(data, offset, length, first, second);
            case 3 -> VectorSupport.SVE_128 ? findThreeSve(data, offset, length, first, second, third) : findThree(data, offset, length, first, second, third);
            default -> throw new IllegalArgumentException("candidateCount must be one, two, or three");
        };
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Finds the first byte with its high bit set or equal to
    // one of the candidates. Candidate count is zero, one, two, or three.
    static int findHighBitOr(byte[] data, int offset, int length, byte first, byte second, byte third, int candidateCount)
    {
        return switch (candidateCount) {
            case 0 -> findHighBit(data, offset, length);
            case 1 -> findHighBitOrOne(data, offset, length, first);
            case 2 -> findHighBitOrTwo(data, offset, length, first, second);
            case 3 -> findHighBitOrThree(data, offset, length, first, second, third);
            default -> throw new IllegalArgumentException("candidateCount must be zero, one, two, or three");
        };
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. find scanning backward: returns the last position in
    // [offset, offset + length) holding a candidate. Candidate count is one, two, or
    // three; two and three take the XOR form under VectorSupport.SVE_128.
    static int findLast(byte[] data, int offset, int length, byte first, byte second, byte third, int candidateCount)
    {
        return switch (candidateCount) {
            case 1 -> findLastOne(data, offset, length, first);
            case 2 -> VectorSupport.SVE_128 ? findLastTwoSve(data, offset, length, first, second) : findLastTwo(data, offset, length, first, second);
            case 3 -> VectorSupport.SVE_128 ? findLastThreeSve(data, offset, length, first, second, third) : findLastThree(data, offset, length, first, second, third);
            default -> throw new IllegalArgumentException("candidateCount must be one, two, or three");
        };
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. findHighBitOr scanning backward: returns the last position
    // in [offset, offset + length) holding a byte with its high bit set or equal to one
    // of the candidates. Candidate count is zero, one, two, or three.
    static int findLastHighBitOr(byte[] data, int offset, int length, byte first, byte second, byte third, int candidateCount)
    {
        return switch (candidateCount) {
            case 0 -> findLastHighBit(data, offset, length);
            case 1 -> findLastHighBitOrOne(data, offset, length, first);
            case 2 -> findLastHighBitOrTwo(data, offset, length, first, second);
            case 3 -> findLastHighBitOrThree(data, offset, length, first, second, third);
            default -> throw new IllegalArgumentException("candidateCount must be zero, one, two, or three");
        };
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: unsigned comparisons preserve byte-set
    // ordering across the signed-byte boundary without an extra vector transform.
    // Copied as VectorScanKernelSupport.findRange; keep the copy in step.
    static int findRange(byte[] data, int offset, int length, int lowerBound, int upperBound)
    {
        ByteVector lowerBoundVector = ByteVector.broadcast(SPECIES, (byte) lowerBound);
        ByteVector upperBoundVector = ByteVector.broadcast(SPECIES, (byte) upperBound);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.compare(VectorOperators.UGE, lowerBoundVector)
                    .and(values.compare(VectorOperators.ULE, upperBoundVector));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            int value = data[position] & 0xFF;
            if (value >= lowerBound && value <= upperBound) {
                return position;
            }
        }
        return -1;
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Finds the first position where one of the filter's literals
    // matches at all three of its positions. For two groups, each vector step loads three
    // vectors, ORs each once with its position's mask, and checks both groups against those
    // three, by compare and AND or, under VectorSupport.SVE_128, by XOR distance. For more, each
    // step loads one vector, at the third position, looks up every group's bit for all three
    // positions by nibble, and shifts the first two positions' bits into place from the previous
    // step's; the high nibbles take the shift VectorSupport.SHIFT_NIBBLES_AS_SHORTS selects.
    static int findLiteralSet(byte[] data, int offset, int length, StartLiteralFilter filter)
    {
        return switch (filter.values().length / 3) {
            case 2 -> VectorSupport.SVE_128 ? findTwoLiteralsSve(data, offset, length, filter) : findTwoLiterals(data, offset, length, filter);
            case 4, 8 -> findLiteralNibbles(data, offset, length, filter);
            default -> throw new IllegalArgumentException("literal filter width must be two, four, or eight");
        };
    }

    // The SVE form is findTwoLiteralsSve; keep the two in step.
    private static int findTwoLiterals(byte[] data, int offset, int length, StartLiteralFilter filter)
    {
        byte[] values = filter.values();
        ByteVector firstValue0 = ByteVector.broadcast(SPECIES, values[0]);
        ByteVector secondValue0 = ByteVector.broadcast(SPECIES, values[1]);
        ByteVector thirdValue0 = ByteVector.broadcast(SPECIES, values[2]);
        ByteVector firstValue1 = ByteVector.broadcast(SPECIES, values[3]);
        ByteVector secondValue1 = ByteVector.broadcast(SPECIES, values[4]);
        ByteVector thirdValue1 = ByteVector.broadcast(SPECIES, values[5]);
        ByteVector firstMask = ByteVector.broadcast(SPECIES, filter.firstMask());
        ByteVector secondMask = ByteVector.broadcast(SPECIES, filter.secondMask());
        ByteVector thirdMask = ByteVector.broadcast(SPECIES, filter.thirdMask());
        int firstOffset = filter.firstOffset();
        int secondOffset = filter.secondOffset();
        int thirdOffset = filter.thirdOffset();
        int end = offset + length;
        // The third position of the last vector's last lane must lie before the end.
        int vectorEnd = end - thirdOffset - SPECIES.length();
        int position = offset;
        for (; position <= vectorEnd; position += SPECIES.length()) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position + firstOffset).or(firstMask);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + secondOffset).or(secondMask);
            ByteVector third = ByteVector.fromArray(SPECIES, data, position + thirdOffset).or(thirdMask);
            VectorMask<Byte> matches = literalMatches(first, second, third, firstValue0, secondValue0, thirdValue0)
                    .or(literalMatches(first, second, third, firstValue1, secondValue1, thirdValue1));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }
        return filter.find(data, position, end - position);
    }

    // findTwoLiterals in the form VectorSupport.SVE_128 selects. It is a separate method because
    // both forms in one body would exceed C2's 325-byte limit for inlining a hot method. Keep the
    // two in step.
    private static int findTwoLiteralsSve(byte[] data, int offset, int length, StartLiteralFilter filter)
    {
        byte[] values = filter.values();
        ByteVector firstValue0 = ByteVector.broadcast(SPECIES, values[0]);
        ByteVector secondValue0 = ByteVector.broadcast(SPECIES, values[1]);
        ByteVector thirdValue0 = ByteVector.broadcast(SPECIES, values[2]);
        ByteVector firstValue1 = ByteVector.broadcast(SPECIES, values[3]);
        ByteVector secondValue1 = ByteVector.broadcast(SPECIES, values[4]);
        ByteVector thirdValue1 = ByteVector.broadcast(SPECIES, values[5]);
        ByteVector firstMask = ByteVector.broadcast(SPECIES, filter.firstMask());
        ByteVector secondMask = ByteVector.broadcast(SPECIES, filter.secondMask());
        ByteVector thirdMask = ByteVector.broadcast(SPECIES, filter.thirdMask());
        int firstOffset = filter.firstOffset();
        int secondOffset = filter.secondOffset();
        int thirdOffset = filter.thirdOffset();
        int end = offset + length;
        // The third position of the last vector's last lane must lie before the end.
        int vectorEnd = end - thirdOffset - SPECIES.length();
        int position = offset;
        for (; position <= vectorEnd; position += SPECIES.length()) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position + firstOffset).or(firstMask);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + secondOffset).or(secondMask);
            ByteVector third = ByteVector.fromArray(SPECIES, data, position + thirdOffset).or(thirdMask);
            VectorMask<Byte> matches = sveTwoLiteralMatches(first, second, third, firstValue0, secondValue0, thirdValue0, firstValue1, secondValue1, thirdValue1);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }
        return filter.find(data, position, end - position);
    }

    // Each position's two nibble lookups give every group's bit at once, so the cost does not
    // grow with the number of groups. A lane keeps a group's bit only where the group accepts all
    // three bytes, so a nonzero lane is exactly a position the filter accepts. The lookups are
    // selectFrom, not rearrange: converting the indexes to a shuffle adds index-wrapping code
    // before every table lookup on aarch64. The one load per step follows the Rust crate's Teddy.
    private static int findLiteralNibbles(byte[] data, int offset, int length, StartLiteralFilter filter)
    {
        byte[] tables = filter.nibbleTables();
        int tableBytes = StartLiteralFilter.NIBBLE_TABLE_BYTES;
        ByteVector firstLow = ByteVector.fromArray(SPECIES, tables, 0);
        ByteVector firstHigh = ByteVector.fromArray(SPECIES, tables, tableBytes);
        ByteVector secondLow = ByteVector.fromArray(SPECIES, tables, 2 * tableBytes);
        ByteVector secondHigh = ByteVector.fromArray(SPECIES, tables, 3 * tableBytes);
        ByteVector thirdLow = ByteVector.fromArray(SPECIES, tables, 4 * tableBytes);
        ByteVector thirdHigh = ByteVector.fromArray(SPECIES, tables, 5 * tableBytes);
        int firstOffset = filter.firstOffset();
        int secondOffset = filter.secondOffset();
        int thirdOffset = filter.thirdOffset();
        int lanes = SPECIES.length();
        int end = offset + length;
        // The third position of the last vector's last lane must lie before the end.
        int vectorEnd = end - thirdOffset - lanes;
        int position = offset;
        if (position > vectorEnd) {
            return filter.find(data, offset, length);
        }
        // Lane j of a step's bits is for the byte at the step's third position plus j. The first
        // and second positions' bytes for the same start lie fewer than
        // StartLiteralFilter.PATH_POSITIONS lanes earlier, so their bits come from this step's and
        // the previous step's. Before the first step, the previous step's lanes that are used come
        // from a load at the first position, moved up so each byte sits in the lane the previous
        // step's load would give it.
        int firstOrigin = lanes - (thirdOffset - firstOffset);
        int secondOrigin = lanes - (thirdOffset - secondOffset);
        // Written out here and in the loop rather than called: on 64-byte vectors the slices'
        // force-inlined code uses up C2's inlining node budget, and a helper call it then refuses
        // boxes its vectors.
        ByteVector previous = ByteVector.fromArray(SPECIES, data, position + firstOffset).unslice(firstOrigin);
        ByteVector previousLow = previous.and((byte) 0x0F);
        ByteVector previousHigh = VectorSupport.SHIFT_NIBBLES_AS_SHORTS
                ? previous.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 4).reinterpretAsBytes().and((byte) 0x0F)
                : previous.lanewise(VectorOperators.LSHR, 4);
        ByteVector previousFirstBits = previousLow.selectFrom(firstLow).and(previousHigh.selectFrom(firstHigh));
        ByteVector previousSecondBits = previousLow.selectFrom(secondLow).and(previousHigh.selectFrom(secondHigh));
        for (; position <= vectorEnd; position += lanes) {
            ByteVector third = ByteVector.fromArray(SPECIES, data, position + thirdOffset);
            ByteVector low = third.and((byte) 0x0F);
            ByteVector high = VectorSupport.SHIFT_NIBBLES_AS_SHORTS
                    ? third.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 4).reinterpretAsBytes().and((byte) 0x0F)
                    : third.lanewise(VectorOperators.LSHR, 4);
            ByteVector firstBits = low.selectFrom(firstLow).and(high.selectFrom(firstHigh));
            ByteVector secondBits = low.selectFrom(secondLow).and(high.selectFrom(secondHigh));
            ByteVector groupBits = low.selectFrom(thirdLow).and(high.selectFrom(thirdHigh))
                    .and(previousFirstBits.slice(firstOrigin, firstBits))
                    .and(previousSecondBits.slice(secondOrigin, secondBits));
            VectorMask<Byte> matches = groupBits.compare(VectorOperators.NE, (byte) 0);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
            previousFirstBits = firstBits;
            previousSecondBits = secondBits;
        }
        return filter.find(data, position, end - position);
    }

    /// Returns the lanes where `first`, `second`, and `third` equal either group of values, by
    /// XOR, OR, UMIN, and one compare with zero; see [VectorSupport#SVE_128].
    private static VectorMask<Byte> sveTwoLiteralMatches(
            ByteVector first,
            ByteVector second,
            ByteVector third,
            ByteVector firstValue0,
            ByteVector secondValue0,
            ByteVector thirdValue0,
            ByteVector firstValue1,
            ByteVector secondValue1,
            ByteVector thirdValue1)
    {
        // A lane of a group's difference is zero only where all three bytes match it, so the
        // smaller difference is zero where either group matches.
        return literalDifference(first, second, third, firstValue0, secondValue0, thirdValue0)
                .lanewise(VectorOperators.UMIN, literalDifference(first, second, third, firstValue1, secondValue1, thirdValue1))
                .compare(VectorOperators.EQ, (byte) 0);
    }

    private static ByteVector literalDifference(
            ByteVector first,
            ByteVector second,
            ByteVector third,
            ByteVector firstValue,
            ByteVector secondValue,
            ByteVector thirdValue)
    {
        return first.lanewise(VectorOperators.XOR, firstValue)
                .or(second.lanewise(VectorOperators.XOR, secondValue))
                .or(third.lanewise(VectorOperators.XOR, thirdValue));
    }

    private static VectorMask<Byte> literalMatches(
            ByteVector first,
            ByteVector second,
            ByteVector third,
            ByteVector firstValue,
            ByteVector secondValue,
            ByteVector thirdValue)
    {
        return first.eq(firstValue).and(second.eq(secondValue)).and(third.eq(thirdValue));
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Finds the first position whose byte is in slots 0 to 2 of `bytes`
    // and whose next byte is in slots 3 to 5, a side with fewer bytes repeating one. The scan is a
    // counted loop, with its step count fixed before it starts, so C2 can strip-mine its
    // safepoint poll out of each step; the step that hits is compared again after the loop. The
    // comparison is written out at both places rather than in a helper: C2 boxes the vectors a
    // helper it leaves out of line takes or returns, on every step, and nothing guarantees it
    // inlines one. The scalar tail is the one the scalar search uses. The SVE form is
    // findClassPairSve; keep the two in step.
    static int findClassPair(byte[] data, int offset, int length, byte[] bytes)
    {
        ByteVector first0 = ByteVector.broadcast(SPECIES, bytes[0]);
        ByteVector first1 = ByteVector.broadcast(SPECIES, bytes[1]);
        ByteVector first2 = ByteVector.broadcast(SPECIES, bytes[2]);
        ByteVector second0 = ByteVector.broadcast(SPECIES, bytes[3]);
        ByteVector second1 = ByteVector.broadcast(SPECIES, bytes[4]);
        ByteVector second2 = ByteVector.broadcast(SPECIES, bytes[5]);
        int lanes = SPECIES.length();
        // The second byte of each step's last lane must lie before the end.
        int steps = length > 0 ? (length - 1) / lanes : 0;
        int step = 0;
        for (; step < steps; step++) {
            int stepStart = offset + step * lanes;
            ByteVector first = ByteVector.fromArray(SPECIES, data, stepStart);
            ByteVector second = ByteVector.fromArray(SPECIES, data, stepStart + 1);
            if (first.eq(first0).or(first.eq(first1)).or(first.eq(first2))
                    .and(second.eq(second0).or(second.eq(second1)).or(second.eq(second2)))
                    .anyTrue()) {
                break;
            }
        }
        int position = offset + step * lanes;
        if (step < steps) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + 1);
            VectorMask<Byte> matches = first.eq(first0).or(first.eq(first1)).or(first.eq(first2))
                    .and(second.eq(second0).or(second.eq(second1)).or(second.eq(second2)));
            return position + matches.firstTrue();
        }

        return InnerLiteralSearch.findClassPair(data, position, offset + length - position, bytes);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. findClassPair in the form VectorSupport.SVE_128 selects: each side's
    // bytes are XORed with the loaded vector, the differences reduced by unsigned minimum, the two
    // sides ORed, and one compare with zero gives the mask. It is a separate method, as
    // findTwoLiteralsSve is, so that each body holds one form and its loop. The step that hits is
    // compared again after the loop as in findClassPair. Keep the two in step.
    static int findClassPairSve(byte[] data, int offset, int length, byte[] bytes)
    {
        ByteVector first0 = ByteVector.broadcast(SPECIES, bytes[0]);
        ByteVector first1 = ByteVector.broadcast(SPECIES, bytes[1]);
        ByteVector first2 = ByteVector.broadcast(SPECIES, bytes[2]);
        ByteVector second0 = ByteVector.broadcast(SPECIES, bytes[3]);
        ByteVector second1 = ByteVector.broadcast(SPECIES, bytes[4]);
        ByteVector second2 = ByteVector.broadcast(SPECIES, bytes[5]);
        int lanes = SPECIES.length();
        // The second byte of each step's last lane must lie before the end.
        int steps = length > 0 ? (length - 1) / lanes : 0;
        int step = 0;
        for (; step < steps; step++) {
            int stepStart = offset + step * lanes;
            ByteVector first = ByteVector.fromArray(SPECIES, data, stepStart);
            ByteVector second = ByteVector.fromArray(SPECIES, data, stepStart + 1);
            // A side's smallest difference is zero only where its byte is one of the side's, so
            // the OR of both sides is zero only where the pair matches.
            if (first.lanewise(VectorOperators.XOR, first0)
                    .lanewise(VectorOperators.UMIN, first.lanewise(VectorOperators.XOR, first1))
                    .lanewise(VectorOperators.UMIN, first.lanewise(VectorOperators.XOR, first2))
                    .lanewise(VectorOperators.OR, second.lanewise(VectorOperators.XOR, second0)
                            .lanewise(VectorOperators.UMIN, second.lanewise(VectorOperators.XOR, second1))
                            .lanewise(VectorOperators.UMIN, second.lanewise(VectorOperators.XOR, second2)))
                    .compare(VectorOperators.EQ, (byte) 0)
                    .anyTrue()) {
                break;
            }
        }
        int position = offset + step * lanes;
        if (step < steps) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + 1);
            VectorMask<Byte> matches = first.eq(first0).or(first.eq(first1)).or(first.eq(first2))
                    .and(second.eq(second0).or(second.eq(second1)).or(second.eq(second2)));
            return position + matches.firstTrue();
        }

        return InnerLiteralSearch.findClassPair(data, position, offset + length - position, bytes);
    }

    static byte[] buildNibbleTables(byte[] candidates)
    {
        if (candidates == null) {
            return null;
        }

        long highNibbleIndexes = -1L;
        int highNibbleCount = 0;
        int candidateCount = 0;
        for (int value = 0; value < candidates.length; value++) {
            if (candidates[value] == 0) {
                continue;
            }
            candidateCount++;
            int highNibble = value >>> 4;
            if (((highNibbleIndexes >>> (highNibble * 4)) & 0xF) == 0xF) {
                if (highNibbleCount == Byte.SIZE) {
                    return null;
                }
                highNibbleIndexes &= ~(0xFL << (highNibble * 4));
                highNibbleIndexes |= (long) highNibbleCount << (highNibble * 4);
                highNibbleCount++;
            }
        }
        if (candidateCount < 4 || candidateCount > 64) {
            return null;
        }

        // Each high nibble owns one bit. A byte is a member when the masks selected by
        // its low and high nibbles share that bit. Replication supports lane-local byte
        // shuffles while still allowing full-width rearranges where the ISA provides them.
        byte[] tables = new byte[NIBBLE_TABLES_LENGTH];
        for (int value = 0; value < candidates.length; value++) {
            if (candidates[value] == 0) {
                continue;
            }
            int highNibble = value >>> 4;
            int highNibbleIndex = (int) ((highNibbleIndexes >>> (highNibble * 4)) & 0xF);
            byte bit = (byte) (1 << highNibbleIndex);
            for (int tableIndex = value & 0xF; tableIndex < NIBBLE_TABLE_BYTES; tableIndex += 16) {
                tables[tableIndex] |= bit;
            }
            for (int tableIndex = highNibble; tableIndex < NIBBLE_TABLE_BYTES; tableIndex += 16) {
                tables[HIGH_NIBBLE_TABLE_OFFSET + tableIndex] = bit;
            }
        }
        return tables;
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: the replicated tables are part of the code shape.
    // Changes require target-host assembly and integrated benchmark evidence.
    // Copied as VectorScanKernelSupport.findNibbleTable; keep the copy in step.
    static int findNibbleTable(byte[] data, int offset, int length, byte[] tables)
    {
        ByteVector lowNibbleTable = ByteVector.fromArray(SPECIES, tables, 0);
        ByteVector highNibbleTable = ByteVector.fromArray(SPECIES, tables, HIGH_NIBBLE_TABLE_OFFSET);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            ByteVector lowNibbleMasks = lowNibbleTable.rearrange(values.and((byte) 0x0F).toShuffle());
            ByteVector highNibbleMasks = highNibbleTable.rearrange(
                    values.lanewise(VectorOperators.LSHR, 4).and((byte) 0x0F).toShuffle());
            VectorMask<Byte> matches = lowNibbleMasks.and(highNibbleMasks)
                    .compare(VectorOperators.NE, (byte) 0);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            int value = data[position] & 0xFF;
            if ((tables[value & 0xF] & tables[HIGH_NIBBLE_TABLE_OFFSET + (value >>> 4)]) != 0) {
                return position;
            }
        }
        return -1;
    }

    // The forward kernels from here through findHighBitOrThree have backward mirrors, the findLast
    // kernels from findLastOne on; keep each in step with its mirror.
    // Copied as VectorScanKernelSupport.findOne; keep the copy in step.
    private static int findOne(byte[] data, int offset, int length, byte candidate)
    {
        ByteVector candidateVector = ByteVector.broadcast(SPECIES, candidate);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, data, position)
                    .compare(VectorOperators.EQ, candidateVector);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            if (data[position] == candidate) {
                return position;
            }
        }
        return -1;
    }

    // Copied as VectorScanKernelSupport.findTwo; keep the copy in step. The SVE form is
    // findTwoSve; keep the two in step.
    private static int findTwo(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.compare(VectorOperators.EQ, firstVector)
                    .or(values.compare(VectorOperators.EQ, secondVector));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value == first || value == second) {
                return position;
            }
        }
        return -1;
    }

    // findTwo in the form VectorSupport.SVE_128 selects: XOR with each candidate, UMIN, and one
    // compare with zero. Copied as VectorScanKernelSupport.findTwoSve; keep the copy in step.
    private static int findTwoSve(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.lanewise(VectorOperators.XOR, firstVector)
                    .lanewise(VectorOperators.UMIN, values.lanewise(VectorOperators.XOR, secondVector))
                    .compare(VectorOperators.EQ, (byte) 0);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value == first || value == second) {
                return position;
            }
        }
        return -1;
    }

    // Copied as VectorScanKernelSupport.findThree; keep the copy in step. The SVE form is
    // findThreeSve; keep the two in step.
    private static int findThree(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(SPECIES, third);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.compare(VectorOperators.EQ, firstVector)
                    .or(values.compare(VectorOperators.EQ, secondVector))
                    .or(values.compare(VectorOperators.EQ, thirdVector));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value == first || value == second || value == third) {
                return position;
            }
        }
        return -1;
    }

    // findThree in the form VectorSupport.SVE_128 selects: XOR with each candidate, UMIN, and one
    // compare with zero. Copied as VectorScanKernelSupport.findThreeSve; keep the copy in step.
    private static int findThreeSve(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(SPECIES, third);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.lanewise(VectorOperators.XOR, firstVector)
                    .lanewise(VectorOperators.UMIN, values.lanewise(VectorOperators.XOR, secondVector))
                    .lanewise(VectorOperators.UMIN, values.lanewise(VectorOperators.XOR, thirdVector))
                    .compare(VectorOperators.EQ, (byte) 0);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value == first || value == second || value == third) {
                return position;
            }
        }
        return -1;
    }

    private static int findHighBit(byte[] data, int offset, int length)
    {
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, data, position)
                    .compare(VectorOperators.LT, (byte) 0);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            if (data[position] < 0) {
                return position;
            }
        }
        return -1;
    }

    private static int findHighBitOrOne(byte[] data, int offset, int length, byte candidate)
    {
        ByteVector candidateVector = ByteVector.broadcast(SPECIES, candidate);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.compare(VectorOperators.LT, (byte) 0)
                    .or(values.compare(VectorOperators.EQ, candidateVector));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value < 0 || value == candidate) {
                return position;
            }
        }
        return -1;
    }

    private static int findHighBitOrTwo(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.compare(VectorOperators.LT, (byte) 0)
                    .or(values.compare(VectorOperators.EQ, firstVector))
                    .or(values.compare(VectorOperators.EQ, secondVector));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value < 0 || value == first || value == second) {
                return position;
            }
        }
        return -1;
    }

    private static int findHighBitOrThree(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(SPECIES, third);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.compare(VectorOperators.LT, (byte) 0)
                    .or(values.compare(VectorOperators.EQ, firstVector))
                    .or(values.compare(VectorOperators.EQ, secondVector))
                    .or(values.compare(VectorOperators.EQ, thirdVector));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value < 0 || value == first || value == second || value == third) {
                return position;
            }
        }
        return -1;
    }

    // The backward kernels below mirror the forward ones above, loading whole vectors from the end
    // of the range toward its start and leaving the head shorter than a vector to a scalar loop;
    // keep each in step with its forward form.
    private static int findLastOne(byte[] data, int offset, int length, byte candidate)
    {
        ByteVector candidateVector = ByteVector.broadcast(SPECIES, candidate);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, data, position - SPECIES.length())
                    .compare(VectorOperators.EQ, candidateVector);
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            if (data[position - 1] == candidate) {
                return position - 1;
            }
        }
        return -1;
    }

    // The SVE form is findLastTwoSve; keep the two in step.
    private static int findLastTwo(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.compare(VectorOperators.EQ, firstVector)
                    .or(values.compare(VectorOperators.EQ, secondVector));
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value == first || value == second) {
                return position - 1;
            }
        }
        return -1;
    }

    // findLastTwo in the form VectorSupport.SVE_128 selects, as findTwoSve is for findTwo.
    private static int findLastTwoSve(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.lanewise(VectorOperators.XOR, firstVector)
                    .lanewise(VectorOperators.UMIN, values.lanewise(VectorOperators.XOR, secondVector))
                    .compare(VectorOperators.EQ, (byte) 0);
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value == first || value == second) {
                return position - 1;
            }
        }
        return -1;
    }

    // The SVE form is findLastThreeSve; keep the two in step.
    private static int findLastThree(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(SPECIES, third);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.compare(VectorOperators.EQ, firstVector)
                    .or(values.compare(VectorOperators.EQ, secondVector))
                    .or(values.compare(VectorOperators.EQ, thirdVector));
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value == first || value == second || value == third) {
                return position - 1;
            }
        }
        return -1;
    }

    // findLastThree in the form VectorSupport.SVE_128 selects, as findThreeSve is for findThree.
    private static int findLastThreeSve(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(SPECIES, third);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.lanewise(VectorOperators.XOR, firstVector)
                    .lanewise(VectorOperators.UMIN, values.lanewise(VectorOperators.XOR, secondVector))
                    .lanewise(VectorOperators.UMIN, values.lanewise(VectorOperators.XOR, thirdVector))
                    .compare(VectorOperators.EQ, (byte) 0);
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value == first || value == second || value == third) {
                return position - 1;
            }
        }
        return -1;
    }

    private static int findLastHighBit(byte[] data, int offset, int length)
    {
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, data, position - SPECIES.length())
                    .compare(VectorOperators.LT, (byte) 0);
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            if (data[position - 1] < 0) {
                return position - 1;
            }
        }
        return -1;
    }

    private static int findLastHighBitOrOne(byte[] data, int offset, int length, byte candidate)
    {
        ByteVector candidateVector = ByteVector.broadcast(SPECIES, candidate);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.compare(VectorOperators.LT, (byte) 0)
                    .or(values.compare(VectorOperators.EQ, candidateVector));
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value < 0 || value == candidate) {
                return position - 1;
            }
        }
        return -1;
    }

    private static int findLastHighBitOrTwo(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.compare(VectorOperators.LT, (byte) 0)
                    .or(values.compare(VectorOperators.EQ, firstVector))
                    .or(values.compare(VectorOperators.EQ, secondVector));
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value < 0 || value == first || value == second) {
                return position - 1;
            }
        }
        return -1;
    }

    private static int findLastHighBitOrThree(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(SPECIES, third);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.compare(VectorOperators.LT, (byte) 0)
                    .or(values.compare(VectorOperators.EQ, firstVector))
                    .or(values.compare(VectorOperators.EQ, secondVector))
                    .or(values.compare(VectorOperators.EQ, thirdVector));
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value < 0 || value == first || value == second || value == third) {
                return position - 1;
            }
        }
        return -1;
    }
}
