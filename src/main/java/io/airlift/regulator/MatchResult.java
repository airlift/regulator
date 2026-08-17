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

import io.airlift.slice.Slice;

import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * An immutable snapshot of one match's boundaries and group metadata.
 * <p>
 * Group offsets are byte offsets relative to the original logical Slice. Group slices are
 * zero-copy views and therefore retain the input's backing storage. Changes to mutable backing
 * storage remain visible through previously returned group slices.
 */
public final class MatchResult
{
    private final Slice text;
    private final int[] groups;
    private final Map<String, Integer> namedCapturingGroups;

    MatchResult(Slice text, int[] groups, Map<String, Integer> namedCapturingGroups)
    {
        this.text = requireNonNull(text, "text is null");
        this.groups = requireNonNull(groups, "groups is null");
        if ((groups.length % 2) != 0) {
            throw new IllegalArgumentException("groups length must be even: " + groups.length);
        }
        this.namedCapturingGroups = requireNonNull(namedCapturingGroups, "namedCapturingGroups is null");
    }

    /**
     * Returns the number of capturing groups, excluding group zero.
     */
    public int groupCount()
    {
        return Math.max(0, (groups.length / 2) - 1);
    }

    /**
     * Returns the start byte offset of group zero.
     */
    public int start()
    {
        return start(0);
    }

    /**
     * Returns whether the group participated in the match.
     */
    public boolean matched(int group)
    {
        checkGroup(group);
        int start = groups[group * 2];
        int end = groups[group * 2 + 1];
        return start >= 0 && end >= start;
    }

    /**
     * Returns the start byte offset, or {@code -1} when the group did not participate.
     */
    public int start(int group)
    {
        checkGroup(group);
        if (!matched(group)) {
            return -1;
        }
        return groups[group * 2];
    }

    /**
     * Returns the end byte offset, or {@code -1} when the group did not participate.
     */
    public int end(int group)
    {
        checkGroup(group);
        if (!matched(group)) {
            return -1;
        }
        return groups[group * 2 + 1];
    }

    /**
     * Returns the end byte offset of group zero.
     */
    public int end()
    {
        return end(0);
    }

    /**
     * Returns the byte length, or {@code -1} when the group did not participate.
     */
    public int length(int group)
    {
        int start = start(group);
        if (start < 0) {
            return -1;
        }
        return end(group) - start;
    }

    /**
     * Returns the byte length of group zero.
     */
    public int length()
    {
        return length(0);
    }

    /**
     * Returns a zero-copy group view, or {@code null} when the group did not participate.
     */
    public Slice groupSlice(int group)
    {
        int start = start(group);
        if (start < 0) {
            return null;
        }
        int end = end(group);
        return text.slice(start, end - start);
    }

    /**
     * Returns a zero-copy view of group zero.
     */
    public Slice groupSlice()
    {
        return groupSlice(0);
    }

    /**
     * Returns a zero-copy named-group view, or {@code null} when the group did not participate.
     *
     * @throws IllegalArgumentException if the name is unknown
     */
    public Slice groupSlice(String groupName)
    {
        return groupSlice(groupIndex(groupName));
    }

    /**
     * Decodes a group as UTF-8, or returns {@code null} when the group did not participate.
     */
    public String groupUtf8(int group)
    {
        Slice slice = groupSlice(group);
        if (slice == null) {
            return null;
        }
        return slice.toStringUtf8();
    }

    /**
     * Decodes group zero as UTF-8.
     */
    public String groupUtf8()
    {
        return groupUtf8(0);
    }

    /**
     * Decodes a named group as UTF-8, or returns {@code null} when it did not participate.
     *
     * @throws IllegalArgumentException if the name is unknown
     */
    public String groupUtf8(String groupName)
    {
        return groupUtf8(groupIndex(groupName));
    }

    /**
     * Parses a matched group as a decimal signed integer.
     *
     * @throws NumberFormatException if the group is unmatched, malformed, or out of range
     */
    public int parseInt(int group)
    {
        return parseInt(group, 10);
    }

    /**
     * Parses a matched group as a signed integer in the supplied radix.
     *
     * @throws NumberFormatException if the radix or group value is invalid
     */
    public int parseInt(int group, int radix)
    {
        long value = parseSignedLongGroup(group, radix);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new NumberFormatException("group " + group + " value out of int range");
        }
        return (int) value;
    }

    /**
     * Parses a matched group as a decimal signed long.
     *
     * @throws NumberFormatException if the group is unmatched, malformed, or out of range
     */
    public long parseLong(int group)
    {
        return parseLong(group, 10);
    }

    /**
     * Parses a matched group as a signed long in the supplied radix.
     *
     * @throws NumberFormatException if the radix or group value is invalid
     */
    public long parseLong(int group, int radix)
    {
        return parseSignedLongGroup(group, radix);
    }

    /**
     * Parses a matched group as a decimal unsigned 32-bit integer returned in a long.
     *
     * @throws NumberFormatException if the group is unmatched, malformed, or out of range
     */
    public long parseUnsignedInt(int group)
    {
        return parseUnsignedInt(group, 10);
    }

    /**
     * Parses a matched group as an unsigned 32-bit integer in the supplied radix.
     *
     * @throws NumberFormatException if the radix or group value is invalid
     */
    public long parseUnsignedInt(int group, int radix)
    {
        long value = parseUnsignedLongGroup(group, radix);
        if (Long.compareUnsigned(value, 0xFFFF_FFFFL) > 0) {
            throw new NumberFormatException("group " + group + " value out of unsigned int range");
        }
        return value;
    }

    /**
     * Parses a matched group as a decimal unsigned 64-bit value represented by raw long bits.
     *
     * @throws NumberFormatException if the group is unmatched, malformed, or out of range
     */
    public long parseUnsignedLong(int group)
    {
        return parseUnsignedLong(group, 10);
    }

    /**
     * Parses a matched group as an unsigned 64-bit value represented by raw long bits.
     *
     * @throws NumberFormatException if the radix or group value is invalid
     */
    public long parseUnsignedLong(int group, int radix)
    {
        return parseUnsignedLongGroup(group, radix);
    }

    private int groupIndex(String groupName)
    {
        requireNonNull(groupName, "groupName is null");
        Integer groupIndex = namedCapturingGroups.get(groupName);
        if (groupIndex == null) {
            throw new IllegalArgumentException("unknown named group: " + groupName);
        }
        return groupIndex;
    }

    private long parseSignedLongGroup(int group, int radix)
    {
        Slice slice = requireMatchedGroup(group);
        return NumericParsers.parseSignedLong(slice.byteArray(), slice.byteArrayOffset(), slice.length(), radix);
    }

    private long parseUnsignedLongGroup(int group, int radix)
    {
        Slice slice = requireMatchedGroup(group);
        return NumericParsers.parseUnsignedLong(slice.byteArray(), slice.byteArrayOffset(), slice.length(), radix);
    }

    private Slice requireMatchedGroup(int group)
    {
        Slice slice = groupSlice(group);
        if (slice == null) {
            throw new NumberFormatException("group " + group + " is unmatched");
        }
        return slice;
    }

    private void checkGroup(int group)
    {
        if (group < 0 || group > groupCount()) {
            throw new IllegalArgumentException("group index out of range: " + group);
        }
    }
}
