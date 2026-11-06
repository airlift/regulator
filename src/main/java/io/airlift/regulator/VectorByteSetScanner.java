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

    private VectorByteSetScanner() {}

    static boolean shouldSampleCandidateDensity()
    {
        return SPECIES.length() > 16;
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Candidate count is always one, two, or three.
    // Copied as VectorScanKernelSupport.findByteSet; keep the copy in step.
    static int find(byte[] data, int offset, int length, byte first, byte second, byte third, int candidateCount)
    {
        return switch (candidateCount) {
            case 1 -> findOne(data, offset, length, first);
            case 2 -> findTwo(data, offset, length, first, second);
            case 3 -> findThree(data, offset, length, first, second, third);
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
    // matches at all three of its positions. Each vector step loads three vectors. For two
    // groups, it ORs each once with its position's mask and checks both groups against those
    // three, by compare and AND or, under VectorSupport.SVE_128, by XOR distance; for more, it
    // looks up every group's bit by nibble.
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
    // before every table lookup on aarch64.
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
        int end = offset + length;
        // The third position of the last vector's last lane must lie before the end.
        int vectorEnd = end - thirdOffset - SPECIES.length();
        int position = offset;
        for (; position <= vectorEnd; position += SPECIES.length()) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position + firstOffset);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + secondOffset);
            ByteVector third = ByteVector.fromArray(SPECIES, data, position + thirdOffset);
            ByteVector groupBits = first.and((byte) 0x0F).selectFrom(firstLow)
                    .and(first.lanewise(VectorOperators.LSHR, 4).selectFrom(firstHigh))
                    .and(second.and((byte) 0x0F).selectFrom(secondLow))
                    .and(second.lanewise(VectorOperators.LSHR, 4).selectFrom(secondHigh))
                    .and(third.and((byte) 0x0F).selectFrom(thirdLow))
                    .and(third.lanewise(VectorOperators.LSHR, 4).selectFrom(thirdHigh));
            VectorMask<Byte> matches = groupBits.compare(VectorOperators.NE, (byte) 0);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
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
        byte[] tables = new byte[NIBBLE_TABLE_BYTES * 2];
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

    // Copied as VectorScanKernelSupport.findTwo; keep the copy in step.
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

    // Copied as VectorScanKernelSupport.findThree; keep the copy in step.
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
}
