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

/**
 * Vector scans for {@link TrinoScanKernelSupport}: copies of the {@link VectorTrinoScanner}
 * and {@link VectorByteSetScanner} scans that the interpreter's helpers call, kept apart for
 * the reason given in {@link TrinoScanKernelSupport}. Callers must check
 * {@link VectorSupport#isAvailable()} first and use their scalar loops otherwise.
 */
final class VectorTrinoScanKernelSupport
{
    // The plan's own scans use 128-bit vectors, and the byte-set scans shared with the DFA use
    // the preferred species, as in the originals.
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_128;
    private static final VectorSpecies<Byte> BYTE_SET_SPECIES = ByteVector.SPECIES_PREFERRED;
    private static final int HIGH_NIBBLE_TABLE_OFFSET = 64;

    private VectorTrinoScanKernelSupport() {}

    // PERFORMANCE-SENSITIVE HOT LOOPS: changes here require target-host assembly
    // and benchmark evidence. Keep Vector API linkage isolated in this class.

    /**
     * A copy of {@code VectorTrinoScanner.findRunStop}.
     */
    static int findRunStop(byte[] input, int cursor, int end, byte[] needles, boolean inverted, boolean[] members)
    {
        ByteVector first = ByteVector.broadcast(SPECIES, needles[0]);
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            ByteVector bytes = ByteVector.fromArray(SPECIES, input, cursor);
            VectorMask<Byte> matches = bytes.compare(VectorOperators.EQ, first);
            for (int index = 1; index < needles.length; index++) {
                matches = matches.or(bytes.compare(VectorOperators.EQ, needles[index]));
            }
            VectorMask<Byte> stops = inverted ? matches : matches.not();
            if (stops.anyTrue()) {
                return cursor + stops.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            if (!members[input[cursor] & 0xFF]) {
                return cursor;
            }
        }
        return end;
    }

    /**
     * A copy of {@code VectorTrinoScanner.findRe2RunStop}.
     */
    static int findRe2RunStop(byte[] input, int cursor, int end, byte[] needles)
    {
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            ByteVector bytes = ByteVector.fromArray(SPECIES, input, cursor);
            VectorMask<Byte> stops = bytes.compare(VectorOperators.LT, (byte) 0);
            for (byte needle : needles) {
                stops = stops.or(bytes.compare(VectorOperators.EQ, needle));
            }
            if (stops.anyTrue()) {
                return cursor + stops.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            byte value = input[cursor];
            if (value < 0) {
                return cursor;
            }
            for (byte needle : needles) {
                if (value == needle) {
                    return cursor;
                }
            }
        }
        return end;
    }

    /**
     * A copy of {@code VectorTrinoScanner.findRunCandidate}.
     */
    static int findRunCandidate(byte[] input, int cursor, int end, byte[] needles, boolean inverted, boolean[] members)
    {
        ByteVector first = ByteVector.broadcast(SPECIES, needles[0]);
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            ByteVector bytes = ByteVector.fromArray(SPECIES, input, cursor);
            VectorMask<Byte> matches = bytes.compare(VectorOperators.EQ, first);
            for (int index = 1; index < needles.length; index++) {
                matches = matches.or(bytes.compare(VectorOperators.EQ, needles[index]));
            }
            VectorMask<Byte> candidates = inverted ? matches.not() : matches;
            if (candidates.anyTrue()) {
                return cursor + candidates.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            if (members[input[cursor] & 0xFF]) {
                return cursor;
            }
        }
        return -1;
    }

    /**
     * A copy of {@code VectorTrinoScanner.containsLiteral}.
     */
    static boolean containsLiteral(byte[] bytes, int cursor, int end, TrinoScanPlan.Step literal)
    {
        int lastOffset = literal.literal.length - 1;
        int lastStart = end - literal.literal.length;
        // Filter on the first and last literal bytes together so a frequent first byte does
        // not restart the scan at each occurrence.
        ByteVector first = ByteVector.broadcast(SPECIES, literal.literal[0]);
        ByteVector last = ByteVector.broadcast(SPECIES, literal.literal[lastOffset]);
        for (; cursor <= lastStart - SPECIES.length() + 1; cursor += SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, bytes, cursor).compare(VectorOperators.EQ, first)
                    .and(ByteVector.fromArray(SPECIES, bytes, cursor + lastOffset).compare(VectorOperators.EQ, last));
            long bits = matches.toLong();
            while (bits != 0) {
                if (TrinoScanKernelSupport.literalMatches(literal, bytes, cursor + Long.numberOfTrailingZeros(bits), end)) {
                    return true;
                }
                bits &= bits - 1;
            }
        }
        for (; cursor <= lastStart; cursor++) {
            if (TrinoScanKernelSupport.literalMatches(literal, bytes, cursor, end)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A copy of {@code VectorTrinoScanner.findByte}.
     */
    static int findByte(byte[] bytes, int cursor, int end, byte target)
    {
        ByteVector wanted = ByteVector.broadcast(SPECIES, target);
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, bytes, cursor).compare(VectorOperators.EQ, wanted);
            if (matches.anyTrue()) {
                return cursor + matches.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            if (bytes[cursor] == target) {
                return cursor;
            }
        }
        return -1;
    }

    /**
     * A copy of {@code VectorByteSetScanner.find}.
     */
    static int findByteSet(byte[] data, int offset, int length, byte first, byte second, byte third, int candidateCount)
    {
        return switch (candidateCount) {
            case 1 -> findOne(data, offset, length, first);
            case 2 -> findTwo(data, offset, length, first, second);
            case 3 -> findThree(data, offset, length, first, second, third);
            default -> throw new IllegalArgumentException("candidateCount must be one, two, or three");
        };
    }

    /**
     * A copy of {@code VectorByteSetScanner.findRange}.
     */
    static int findRange(byte[] data, int offset, int length, int lowerBound, int upperBound)
    {
        ByteVector lowerBoundVector = ByteVector.broadcast(BYTE_SET_SPECIES, (byte) lowerBound);
        ByteVector upperBoundVector = ByteVector.broadcast(BYTE_SET_SPECIES, (byte) upperBound);
        int vectorEnd = offset + BYTE_SET_SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += BYTE_SET_SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(BYTE_SET_SPECIES, data, position);
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

    /**
     * A copy of {@code VectorByteSetScanner.findNibbleTable}.
     */
    static int findNibbleTable(byte[] data, int offset, int length, byte[] tables)
    {
        ByteVector lowNibbleTable = ByteVector.fromArray(BYTE_SET_SPECIES, tables, 0);
        ByteVector highNibbleTable = ByteVector.fromArray(BYTE_SET_SPECIES, tables, HIGH_NIBBLE_TABLE_OFFSET);
        int vectorEnd = offset + BYTE_SET_SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += BYTE_SET_SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(BYTE_SET_SPECIES, data, position);
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

    // A copy of VectorByteSetScanner.findOne.
    private static int findOne(byte[] data, int offset, int length, byte candidate)
    {
        ByteVector candidateVector = ByteVector.broadcast(BYTE_SET_SPECIES, candidate);
        int vectorEnd = offset + BYTE_SET_SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += BYTE_SET_SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(BYTE_SET_SPECIES, data, position)
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

    // A copy of VectorByteSetScanner.findTwo.
    private static int findTwo(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(BYTE_SET_SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(BYTE_SET_SPECIES, second);
        int vectorEnd = offset + BYTE_SET_SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += BYTE_SET_SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(BYTE_SET_SPECIES, data, position);
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

    // A copy of VectorByteSetScanner.findThree.
    private static int findThree(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(BYTE_SET_SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(BYTE_SET_SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(BYTE_SET_SPECIES, third);
        int vectorEnd = offset + BYTE_SET_SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += BYTE_SET_SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(BYTE_SET_SPECIES, data, position);
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
