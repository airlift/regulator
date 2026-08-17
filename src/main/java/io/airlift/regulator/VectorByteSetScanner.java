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
    private static final int NIBBLE_TABLE_BYTES = 64;
    private static final int HIGH_NIBBLE_TABLE_OFFSET = NIBBLE_TABLE_BYTES;

    private VectorByteSetScanner() {}

    static boolean shouldSampleCandidateDensity()
    {
        return SPECIES.length() > 16;
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Candidate count is always one, two, or three.
    static int find(byte[] data, int offset, int length, byte first, byte second, byte third, int candidateCount)
    {
        return switch (candidateCount) {
            case 1 -> findOne(data, offset, length, first);
            case 2 -> findTwo(data, offset, length, first, second);
            case 3 -> findThree(data, offset, length, first, second, third);
            default -> throw new IllegalArgumentException("candidateCount must be one, two, or three");
        };
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: unsigned comparisons preserve byte-set
    // ordering across the signed-byte boundary without an extra vector transform.
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
}
