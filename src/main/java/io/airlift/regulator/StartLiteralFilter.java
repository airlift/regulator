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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Three byte positions of every literal a match can begin with, checked together to find the
/// positions where a match can start. Each literal is a group: at each of the three positions its
/// literal has one byte, or two bytes that differ in one bit, such as the two cases of an ASCII
/// letter or of a Cyrillic letter's UTF-8 bytes. The scalar and two-group scans OR every group's
/// differing bits into the input byte before comparing, so one OR per position serves every
/// group, and a group may accept up to four bytes there. A filter of more than two groups also
/// holds nibble tables of one bit per group, looked up by a byte's low and high nibbles; a group's
/// bit is set in both entries exactly when the group accepts the byte.
///
/// The filter is a superset of the match starts: a group's position may accept more bytes than
/// its literal, but never fewer. Matches that begin with up to eight such groups, each at least
/// three bytes long, can use it. The Rust regex crate treats its Teddy prefilter as fast only
/// when every literal has at least three bytes.
final class StartLiteralFilter
{
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(StartLiteralFilter.class);

    // Paths are read this deep, so a filter can skip UTF-8 lead bytes, which many letters of a
    // script share, and test the continuation bytes of several characters, or bytes past letters
    // that many words begin with. All three positions must fit in a 16-byte window, the
    // narrowest vector the nibble scan runs on, because it carries the first two positions' bits
    // over from the previous vector step.
    static final int PATH_POSITIONS = 12;
    // Paths that stay distinct past the sixth byte can need more groups than a filter holds,
    // which merge within the first six bytes, so a filter is also built from these first
    // positions, and the lower estimate wins.
    static final int SHALLOW_PATH_POSITIONS = 6;
    private static final int MAXIMUM_GROUPS = 8;
    private static final int FILTER_POSITIONS = 3;
    // Bounds the groups merged from the paths; the three chosen positions can still fold them
    // into at most MAXIMUM_GROUPS distinct groups.
    private static final int MAXIMUM_PATH_GROUPS = 32;
    // Estimates for bytes at or above 0x80 in non-English text: a UTF-8 lead byte is shared by
    // many letters of a script, and a continuation byte by about one letter in 64.
    private static final double LEAD_BYTE_PROBABILITY = 0.25;
    private static final double CONTINUATION_BYTE_PROBABILITY = 1.0 / 64;
    // Each nibble table's 16 entries are repeated to the widest vector's 64 bytes, so one load of
    // the preferred species reads a table.
    static final int NIBBLE_TABLE_BYTES = 64;
    /// The most [#estimatedRetainedSize] returns: the scanner width, which pads the values, is at
    /// most [#MAXIMUM_GROUPS].
    static final long MAXIMUM_RETAINED_SIZE = INSTANCE_SIZE +
            SizeOf.sizeOfByteArray(MAXIMUM_GROUPS * FILTER_POSITIONS) +
            SizeOf.sizeOfByteArray(FILTER_POSITIONS * 2 * NIBBLE_TABLE_BYTES);

    private final int firstOffset;
    private final int secondOffset;
    private final int thirdOffset;
    private final byte firstMask;
    private final byte secondMask;
    private final byte thirdMask;
    // Three bytes per group, padded to the scanner's width with copies of the first group.
    private final byte[] values;
    private final int groupCount;
    // Null when the scanner width is two.
    private final byte[] nibbleTables;
    private final double density;

    private StartLiteralFilter(int[] offsets, byte[] masks, byte[] values, int groupCount, byte[] nibbleTables, double density)
    {
        this.firstOffset = offsets[0];
        this.secondOffset = offsets[1];
        this.thirdOffset = offsets[2];
        this.firstMask = masks[0];
        this.secondMask = masks[1];
        this.thirdMask = masks[2];
        this.values = values;
        this.groupCount = groupCount;
        this.nibbleTables = nibbleTables;
        this.density = density;
    }

    /// Builds a filter from the byte sets [Prog#buildStartPositionByteSets] returns, at the three
    /// positions with the lowest estimated density, from all the paths' positions or from their
    /// first [#SHALLOW_PATH_POSITIONS]. Returns null when, at each depth it tries, the paths merge
    /// into more than [#MAXIMUM_PATH_GROUPS] groups, no three positions are narrow in every group,
    /// or every choice needs more than two mask bits at a position or more than
    /// [#MAXIMUM_GROUPS] distinct groups.
    static StartLiteralFilter build(long[][] paths, ByteFrequencies byteFrequencies)
    {
        int positionCount = paths[0].length / 4;
        StartLiteralFilter filter = build(paths, positionCount, byteFrequencies);
        if (positionCount > SHALLOW_PATH_POSITIONS) {
            StartLiteralFilter shallowFilter = build(paths, SHALLOW_PATH_POSITIONS, byteFrequencies);
            if (filter == null || (shallowFilter != null && shallowFilter.density < filter.density)) {
                return shallowFilter;
            }
        }
        return filter;
    }

    private static StartLiteralFilter build(long[][] paths, int positionCount, ByteFrequencies byteFrequencies)
    {
        // Each group holds the first positionCount positions, which bound the merge and the
        // candidate positions.
        List<long[]> groups = new ArrayList<>();
        for (long[] path : paths) {
            boolean merged = false;
            for (long[] group : groups) {
                if (canMerge(group, path)) {
                    for (int index = 0; index < group.length; index++) {
                        group[index] |= path[index];
                    }
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                if (groups.size() == MAXIMUM_PATH_GROUPS) {
                    return null;
                }
                groups.add(Arrays.copyOf(path, positionCount * 4));
            }
        }

        // A candidate position is narrow in every group, and its mask has at most two bits: more
        // would accept more than four bytes for each group's byte.
        int[] positions = new int[positionCount];
        byte[] candidateMasks = new byte[positionCount];
        int candidateCount = 0;
        position:
        for (int position = 0; position < positionCount; position++) {
            int mask = 0;
            for (long[] group : groups) {
                if (!isNarrow(group, position)) {
                    continue position;
                }
                mask |= differingBits(group, position);
            }
            if (Integer.bitCount(mask) <= 2) {
                positions[candidateCount] = position;
                candidateMasks[candidateCount] = (byte) mask;
                candidateCount++;
            }
        }
        if (candidateCount < FILTER_POSITIONS) {
            return null;
        }

        // Every triple reads the same group values and probabilities, so each is computed once.
        int groupCount = groups.size();
        int[] candidateValues = new int[groupCount * candidateCount];
        double[] probabilities = new double[groupCount * candidateCount];
        for (int group = 0; group < groupCount; group++) {
            long[] byteSets = groups.get(group);
            int row = group * candidateCount;
            for (int candidate = 0; candidate < candidateCount; candidate++) {
                int mask = candidateMasks[candidate] & 0xFF;
                int value = firstByte(byteSets, positions[candidate]) | mask;
                candidateValues[row + candidate] = value;
                probabilities[row + candidate] = probability(value, mask, byteFrequencies);
            }
        }

        // A triple's density is the sum over its distinct groups, each a key holding the group's
        // three values, first in the high byte. Only the best triple becomes a filter.
        int[] keys = new int[MAXIMUM_GROUPS];
        int[] bestKeys = new int[MAXIMUM_GROUPS];
        int bestCount = 0;
        int bestFirst = -1;
        int bestSecond = -1;
        int bestThird = -1;
        double bestDensity = 0;
        for (int first = 0; first < candidateCount; first++) {
            for (int second = first + 1; second < candidateCount; second++) {
                triple:
                for (int third = second + 1; third < candidateCount; third++) {
                    int distinctCount = 0;
                    double density = 0;
                    for (int group = 0; group < groupCount; group++) {
                        int row = group * candidateCount;
                        int key = (candidateValues[row + first] << 16) | (candidateValues[row + second] << 8) | candidateValues[row + third];
                        int existing = 0;
                        while (existing < distinctCount && keys[existing] != key) {
                            existing++;
                        }
                        if (existing < distinctCount) {
                            continue;
                        }
                        if (distinctCount == MAXIMUM_GROUPS) {
                            continue triple;
                        }
                        keys[distinctCount++] = key;
                        density += probabilities[row + first] * probabilities[row + second] * probabilities[row + third];
                    }
                    if (bestFirst < 0 || density < bestDensity) {
                        bestFirst = first;
                        bestSecond = second;
                        bestThird = third;
                        System.arraycopy(keys, 0, bestKeys, 0, distinctCount);
                        bestCount = distinctCount;
                        bestDensity = density;
                    }
                }
            }
        }
        if (bestFirst < 0) {
            return null;
        }

        int[] offsets = {positions[bestFirst], positions[bestSecond], positions[bestThird]};
        byte[] filterMasks = {candidateMasks[bestFirst], candidateMasks[bestSecond], candidateMasks[bestThird]};
        int width = scannerWidth(bestCount);
        byte[] filterValues = new byte[width * FILTER_POSITIONS];
        for (int group = 0; group < width; group++) {
            int key = bestKeys[group < bestCount ? group : 0];
            filterValues[group * FILTER_POSITIONS] = (byte) (key >>> 16);
            filterValues[group * FILTER_POSITIONS + 1] = (byte) (key >>> 8);
            filterValues[group * FILTER_POSITIONS + 2] = (byte) key;
        }
        byte[] nibbleTables = width > 2 ? buildNibbleTables(filterValues, bestCount, filterMasks) : null;
        return new StartLiteralFilter(offsets, filterMasks, filterValues, bestCount, nibbleTables, bestDensity);
    }

    // For each position, a low-nibble table then a high-nibble table. Group g sets bit g at the
    // nibbles of every byte it accepts there, so a byte passes when its two entries share a bit.
    // The table is exact: a group accepts every byte that keeps its value's bits outside the mask
    // and has any combination of the mask's bits, so its accepted low and high nibbles combine
    // independently and every pairing of the two is itself accepted.
    private static byte[] buildNibbleTables(byte[] values, int groupCount, byte[] masks)
    {
        byte[] tables = new byte[FILTER_POSITIONS * 2 * NIBBLE_TABLE_BYTES];
        for (int group = 0; group < groupCount; group++) {
            byte bit = (byte) (1 << group);
            for (int index = 0; index < FILTER_POSITIONS; index++) {
                int mask = masks[index] & 0xFF;
                int value = values[group * FILTER_POSITIONS + index] & ~mask & 0xFF;
                int lowTable = index * 2 * NIBBLE_TABLE_BYTES;
                int highTable = lowTable + NIBBLE_TABLE_BYTES;
                for (int bits = mask; ; bits = (bits - 1) & mask) {
                    int member = value | bits;
                    for (int copy = 0; copy < NIBBLE_TABLE_BYTES; copy += 16) {
                        tables[lowTable + copy + (member & 0xF)] |= bit;
                        tables[highTable + copy + (member >>> 4)] |= bit;
                    }
                    if (bits == 0) {
                        break;
                    }
                }
            }
        }
        return tables;
    }

    // The values are padded to this width with copies of the first group, and the vector scan
    // picks its kernel from it: two groups run the compare kernel, which reads the spare slots,
    // and four or eight groups share the nibble kernel.
    private static int scannerWidth(int groupCount)
    {
        if (groupCount <= 2) {
            return 2;
        }
        return groupCount <= 4 ? 4 : MAXIMUM_GROUPS;
    }

    // A path joins a group when both are narrow at the same positions and each merged position
    // stays narrow, so the two cases of a letter that the program reaches on separate paths
    // join into one group.
    private static boolean canMerge(long[] group, long[] path)
    {
        for (int position = 0; position < group.length / 4; position++) {
            boolean groupNarrow = isNarrow(group, position);
            if (groupNarrow != isNarrow(path, position)) {
                return false;
            }
            if (groupNarrow) {
                // The union of two narrow sets is narrow when every byte in it differs from the
                // group's first byte in the same one bit.
                int unionBits = differingBits(group, position) | differingBits(path, position) | (firstByte(group, position) ^ firstByte(path, position));
                if (Integer.bitCount(unionBits) > 1) {
                    return false;
                }
            }
        }
        return true;
    }

    // One byte, or two bytes that differ in one bit.
    private static boolean isNarrow(long[] byteSets, int position)
    {
        int count = 0;
        for (int index = 0; index < 4; index++) {
            count += Long.bitCount(byteSets[position * 4 + index]);
        }
        return count == 1 || (count == 2 && Integer.bitCount(differingBits(byteSets, position)) == 1);
    }

    private static int differingBits(long[] byteSets, int position)
    {
        int first = firstByte(byteSets, position);
        int bits = 0;
        // Visit only the members: a narrow set has one or two.
        for (int index = 0; index < 4; index++) {
            for (long members = byteSets[position * 4 + index]; members != 0; members &= members - 1) {
                bits |= (index * 64 + Long.numberOfTrailingZeros(members)) ^ first;
            }
        }
        return bits;
    }

    private static int firstByte(long[] byteSets, int position)
    {
        for (int index = 0; index < 4; index++) {
            long bits = byteSets[position * 4 + index];
            if (bits != 0) {
                return index * 64 + Long.numberOfTrailingZeros(bits);
            }
        }
        throw new IllegalArgumentException("empty byte set");
    }

    // The probability that a byte ORed with the mask equals the value.
    private static double probability(int value, int mask, ByteFrequencies byteFrequencies)
    {
        double probability = 0;
        for (int bits = mask; ; bits = (bits - 1) & mask) {
            int member = value & ~bits;
            if (member < 0x80) {
                probability += byteFrequencies.probability((byte) member);
            }
            else {
                probability += member < 0xC0 ? CONTINUATION_BYTE_PROBABILITY : LEAD_BYTE_PROBABILITY;
            }
            if (bits == 0) {
                return probability;
            }
        }
    }

    /// The estimated fraction of positions the filter accepts.
    double density()
    {
        return density;
    }

    int firstOffset()
    {
        return firstOffset;
    }

    int secondOffset()
    {
        return secondOffset;
    }

    int thirdOffset()
    {
        return thirdOffset;
    }

    byte firstMask()
    {
        return firstMask;
    }

    byte secondMask()
    {
        return secondMask;
    }

    byte thirdMask()
    {
        return thirdMask;
    }

    byte[] values()
    {
        return values;
    }

    /// The low- and high-nibble tables of the first, second, and third positions, each
    /// [#NIBBLE_TABLE_BYTES] long, or null when the scanner width is two.
    byte[] nibbleTables()
    {
        return nibbleTables;
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + SizeOf.sizeOf(values) + SizeOf.sizeOf(nibbleTables);
    }

    /// True when a match can start at `position`: every filter byte lies before `end` and one
    /// group accepts them.
    boolean accepts(byte[] data, int position, int end)
    {
        if (position + thirdOffset >= end) {
            return false;
        }
        byte first = (byte) (data[position + firstOffset] | firstMask);
        byte second = (byte) (data[position + secondOffset] | secondMask);
        byte third = (byte) (data[position + thirdOffset] | thirdMask);
        for (int index = 0; index < groupCount * FILTER_POSITIONS; index += FILTER_POSITIONS) {
            if (first == values[index] && second == values[index + 1] && third == values[index + 2]) {
                return true;
            }
        }
        return false;
    }

    /// Finds the first position in `[offset, offset + length)` where a match can start, reading
    /// no byte at or past `offset + length`, or -1.
    int find(byte[] data, int offset, int length)
    {
        int end = offset + length;
        for (int position = offset; position + thirdOffset < end; position++) {
            if (accepts(data, position, end)) {
                return position;
            }
        }
        return -1;
    }
}
