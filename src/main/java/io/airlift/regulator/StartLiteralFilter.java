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
/// letter or of a Cyrillic letter's UTF-8 bytes. Each position ORs every group's differing bits
/// into the input byte before comparing, so one OR per position serves every group, and a group
/// may accept up to four bytes there.
///
/// The filter is a superset of the match starts: a group's position may accept more bytes than
/// its literal, but never fewer. Matches that begin with up to eight such groups, each at least
/// three bytes long, can use it. The Rust regex crate treats its Teddy prefilter as fast only
/// when every literal has at least three bytes.
final class StartLiteralFilter
{
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(StartLiteralFilter.class);

    // Paths are read this deep, so a filter can skip UTF-8 lead bytes, which many letters of a
    // script share, and test the continuation bytes of two or three characters.
    static final int PATH_POSITIONS = 6;
    private static final int MAXIMUM_GROUPS = 8;
    private static final int FILTER_POSITIONS = 3;
    // Bounds the groups merged from the paths; the three chosen positions can still fold them
    // into at most MAXIMUM_GROUPS distinct groups.
    private static final int MAXIMUM_PATH_GROUPS = 32;
    // Estimates for bytes at or above 0x80 in non-English text: a UTF-8 lead byte is shared by
    // many letters of a script, and a continuation byte by about one letter in 64.
    private static final double LEAD_BYTE_PROBABILITY = 0.25;
    private static final double CONTINUATION_BYTE_PROBABILITY = 1.0 / 64;

    private final int firstOffset;
    private final int secondOffset;
    private final int thirdOffset;
    private final byte firstMask;
    private final byte secondMask;
    private final byte thirdMask;
    // Three bytes per group, padded to the scanner's width with copies of the first group.
    private final byte[] values;
    private final int groupCount;
    private final double density;

    private StartLiteralFilter(int[] offsets, byte[] masks, byte[] values, int groupCount, double density)
    {
        this.firstOffset = offsets[0];
        this.secondOffset = offsets[1];
        this.thirdOffset = offsets[2];
        this.firstMask = masks[0];
        this.secondMask = masks[1];
        this.thirdMask = masks[2];
        this.values = values;
        this.groupCount = groupCount;
        this.density = density;
    }

    /// Builds a filter from the byte sets [Prog#buildStartPositionByteSets] returns, at the three
    /// positions with the lowest estimated density. Returns null when the paths merge into more
    /// than [#MAXIMUM_PATH_GROUPS] groups, when no three positions are narrow in every group, or
    /// when every choice needs more than two mask bits at a position or more than
    /// [#MAXIMUM_GROUPS] distinct groups.
    static StartLiteralFilter build(long[][] paths, ByteFrequencies byteFrequencies)
    {
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
                groups.add(path.clone());
            }
        }

        int positionCount = paths[0].length / 4;
        int[] usable = new int[positionCount];
        int usableCount = 0;
        for (int position = 0; position < positionCount; position++) {
            boolean constrained = true;
            for (long[] group : groups) {
                constrained &= isNarrow(group, position);
            }
            if (constrained) {
                usable[usableCount++] = position;
            }
        }

        StartLiteralFilter best = null;
        for (int first = 0; first < usableCount; first++) {
            for (int second = first + 1; second < usableCount; second++) {
                for (int third = second + 1; third < usableCount; third++) {
                    StartLiteralFilter candidate = build(groups, new int[] {usable[first], usable[second], usable[third]}, byteFrequencies);
                    if (candidate != null && (best == null || candidate.density < best.density)) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static StartLiteralFilter build(List<long[]> groups, int[] offsets, ByteFrequencies byteFrequencies)
    {
        byte[] masks = new byte[FILTER_POSITIONS];
        for (int index = 0; index < FILTER_POSITIONS; index++) {
            int mask = 0;
            for (long[] group : groups) {
                mask |= differingBits(group, offsets[index]);
            }
            // Two bits accept four bytes for each group's byte; more would accept too many.
            if (Integer.bitCount(mask) > 2) {
                return null;
            }
            masks[index] = (byte) mask;
        }

        List<byte[]> distinct = new ArrayList<>();
        double density = 0;
        for (long[] group : groups) {
            byte[] groupValues = new byte[FILTER_POSITIONS];
            for (int index = 0; index < FILTER_POSITIONS; index++) {
                groupValues[index] = (byte) (firstByte(group, offsets[index]) | masks[index]);
            }
            if (distinct.stream().anyMatch(existing -> Arrays.equals(existing, groupValues))) {
                continue;
            }
            if (distinct.size() == MAXIMUM_GROUPS) {
                return null;
            }
            distinct.add(groupValues);
            double groupDensity = 1;
            for (int index = 0; index < FILTER_POSITIONS; index++) {
                groupDensity *= probability(groupValues[index] & 0xFF, masks[index] & 0xFF, byteFrequencies);
            }
            density += groupDensity;
        }

        int width = scannerWidth(distinct.size());
        byte[] values = new byte[width * FILTER_POSITIONS];
        for (int group = 0; group < width; group++) {
            byte[] groupValues = distinct.get(group < distinct.size() ? group : 0);
            System.arraycopy(groupValues, 0, values, group * FILTER_POSITIONS, FILTER_POSITIONS);
        }
        return new StartLiteralFilter(offsets, masks, values, distinct.size(), density);
    }

    // The scanner compares a fixed number of groups; spare slots repeat the first group.
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
        long[] union = new long[4];
        for (int position = 0; position < group.length / 4; position++) {
            boolean groupNarrow = isNarrow(group, position);
            if (groupNarrow != isNarrow(path, position)) {
                return false;
            }
            if (groupNarrow) {
                for (int index = 0; index < 4; index++) {
                    union[index] = group[position * 4 + index] | path[position * 4 + index];
                }
                if (!isNarrow(union, 0)) {
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
        for (int value = 0; value < 256; value++) {
            if ((byteSets[position * 4 + (value >>> 6)] & (1L << value)) != 0) {
                bits |= value ^ first;
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

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + SizeOf.sizeOf(values);
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
