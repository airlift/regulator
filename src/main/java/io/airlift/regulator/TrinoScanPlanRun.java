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

import java.util.Arrays;

/**
 * A greedy character-set run whose continuation needs no repetition backtracking.
 */
final class TrinoScanPlanRun
{
    static final int MATCHES_EVERY_BYTE = 256;

    private static final int INSTANCE_SIZE = SizeOf.instanceSize(TrinoScanPlanRun.class);
    private static final int UNSUPPORTED_ATOM_FLAGS = Regexp.FOLD_CASE | Regexp.LATIN1;
    private static final int UNSUPPORTED_FLAGS = UNSUPPORTED_ATOM_FLAGS | Regexp.NON_GREEDY;

    private final boolean[] members;
    private final long members0;
    private final long members1;
    private final long members2;
    private final long members3;
    private final byte[] needles;
    private final boolean inverted;
    // Set when the run matches every non-ASCII byte; otherwise it matches none.
    private final boolean matchesNonAscii;
    private final int minimum;
    private final int maximum;

    private TrinoScanPlanRun(boolean[] members, boolean matchesNonAscii, int minimum, int maximum)
    {
        this.members = members;
        this.members0 = word(members, 0);
        this.members1 = word(members, 64);
        this.members2 = word(members, 128);
        this.members3 = word(members, 192);
        this.matchesNonAscii = matchesNonAscii;
        this.minimum = minimum;
        this.maximum = maximum;
        int count = 0;
        for (boolean member : members) {
            count += member ? 1 : 0;
        }
        this.inverted = count > 128;
        int needleCount = inverted ? 256 - count : count;
        this.needles = needleCount <= 4 ? new byte[needleCount] : null;
        if (needles != null) {
            int index = 0;
            for (int value = 0; value < members.length; value++) {
                if (members[value] != inverted) {
                    needles[index++] = (byte) value;
                }
            }
        }
    }

    static TrinoScanPlanRun analyze(Regexp expression)
    {
        if ((expression.parseFlags() & UNSUPPORTED_FLAGS) != 0) {
            return null;
        }
        int minimum = 1;
        int maximum = 1;
        Regexp atom = expression;
        switch (expression.op()) {
            case STAR, PLUS -> {
                minimum = expression.op() == RegexpOp.STAR ? 0 : 1;
                maximum = -1;
                atom = expression.child(0);
            }
            case QUEST -> {
                minimum = 0;
                maximum = 1;
                atom = expression.child(0);
            }
            case REPEAT -> {
                minimum = expression.min();
                maximum = expression.max();
                atom = expression.child(0);
            }
            default -> {
                // A bare character atom has an exact repetition count of one.
            }
        }
        if ((atom.parseFlags() & UNSUPPORTED_ATOM_FLAGS) != 0 ||
                minimum > Regexp.MAX_REPEAT || maximum > Regexp.MAX_REPEAT) {
            return null;
        }
        boolean[] members = new boolean[256];
        boolean matchesNonAscii = false;
        switch (atom.op()) {
            case LITERAL -> {
                if (atom.rune() >= 128) {
                    return null;
                }
                members[atom.rune()] = true;
            }
            case ANY_CHAR -> {
                Arrays.fill(members, true);
                matchesNonAscii = true;
            }
            case CHAR_CLASS -> {
                CharClass set = atom.charClass();
                for (int index = 0; index < set.rangeCount(); index++) {
                    RuneRange range = set.range(index);
                    if (range.high() >= 128) {
                        // A byte scan is valid only if all non-ASCII characters have the same
                        // membership. Arbitrary Unicode classes stay on the ordinary engine.
                        if (range.low() > 128 || range.high() != Regexp.RUNEMAX) {
                            return null;
                        }
                        matchesNonAscii = true;
                    }
                }
                for (int value = 0; value < 128; value++) {
                    members[value] = set.contains(value);
                }
                Arrays.fill(members, 128, 256, matchesNonAscii);
            }
            default -> {
                return null;
            }
        }
        return new TrinoScanPlanRun(members, matchesNonAscii, minimum, maximum);
    }

    int unboundedScanTerminator()
    {
        if (maximum < 0 && minimum <= 1 && inverted && needles != null) {
            if (needles.length == 0) {
                return MATCHES_EVERY_BYTE;
            }
            if (needles.length == 1) {
                return needles[0] & 0xFF;
            }
        }
        return -1;
    }

    boolean canStopBefore(long[] following, int offset)
    {
        return minimum == maximum ||
                ((members0 & following[offset]) |
                        (members1 & following[offset + 1]) |
                        (members2 & following[offset + 2]) |
                        (members3 & following[offset + 3])) == 0;
    }

    void addFirstBytes(long[] first, int offset)
    {
        if (maximum != 0) {
            first[offset] |= members0;
            first[offset + 1] |= members1;
            first[offset + 2] |= members2;
            first[offset + 3] |= members3;
        }
    }

    private static long word(boolean[] members, int offset)
    {
        long word = 0;
        for (int index = 0; index < Long.SIZE; index++) {
            if (members[offset + index]) {
                word |= 1L << index;
            }
        }
        return word;
    }

    int minimum()
    {
        return minimum;
    }

    boolean isUnbounded()
    {
        return maximum < 0;
    }

    /**
     * Returns whether {@code value} is the only byte that is not a member.
     */
    boolean isComplementOf(byte value)
    {
        return inverted && needles != null && needles.length == 1 && needles[0] == value;
    }

    boolean isVariable()
    {
        return minimum != maximum;
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + SizeOf.sizeOf(members) + SizeOf.sizeOf(needles);
    }

    int match(byte[] input, int cursor, int end)
    {
        if (!matchesNonAscii) {
            int limit = maximum < 0 ? end : cursor + Math.min(maximum, end - cursor);
            int stop = scan(input, cursor, limit);
            return stop - cursor >= minimum ? stop : -1;
        }
        if (maximum < 0 && minimum <= 1) {
            int stop = scan(input, cursor, end);
            return stop - cursor >= minimum ? stop : -1;
        }
        // Finite bounds count characters, not encoded bytes. For an unbounded run, decode
        // only enough characters to prove its minimum, then resume the byte scan.
        int count = 0;
        int limit = maximum < 0 ? minimum : maximum;
        while (count < limit && cursor < end && members[input[cursor] & 0xFF]) {
            cursor += Utf8.decodedWidth(Utf8.decode(input, cursor, end));
            count++;
        }
        if (count < minimum) {
            return -1;
        }
        return maximum < 0 ? scan(input, cursor, end) : cursor;
    }

    /**
     * Returns the position after the run's minimum characters, or {@code -1} when they are absent.
     * It fails exactly when {@link #match} fails but reads no further than the minimum.
     */
    int matchMinimum(byte[] input, int cursor, int end)
    {
        for (int count = 0; count < minimum; count++) {
            if (cursor >= end || !members[input[cursor] & 0xFF]) {
                return -1;
            }
            cursor += matchesNonAscii ? Utf8.decodedWidth(Utf8.decode(input, cursor, end)) : 1;
        }
        return cursor;
    }

    private int scan(byte[] input, int cursor, int end)
    {
        if (needles != null) {
            if (needles.length == 0) {
                return inverted ? end : cursor;
            }
            // Small sets and their complements share the same equality scans. Keep larger
            // sets on a scalar table until measurements justify another vector operation.
            if (VectorSupport.isAvailable()) {
                return VectorTrinoScanner.findRunStop(input, cursor, end, needles, inverted, members);
            }
        }
        for (; cursor < end; cursor++) {
            if (!members[input[cursor] & 0xFF]) {
                return cursor;
            }
        }
        return end;
    }
}
