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
import io.airlift.slice.Slices;

/**
 * Searches A B{n} x where A is ASCII and the ASCII suffix x is excluded from B.
 */
final class DisjointSuffixRepeatMatcher
{
    private final long prefixLowMask;
    private final long prefixHighMask;
    private final int[] repeatedRanges;
    private final int repeatCount;
    private final Slice suffix;

    private DisjointSuffixRepeatMatcher(long prefixLowMask, long prefixHighMask, int[] repeatedRanges, int repeatCount, int suffix)
    {
        this.prefixLowMask = prefixLowMask;
        this.prefixHighMask = prefixHighMask;
        this.repeatedRanges = repeatedRanges;
        this.repeatCount = repeatCount;
        this.suffix = Slices.wrappedBuffer((byte) suffix);
    }

    static DisjointSuffixRepeatMatcher analyze(Regexp expression)
    {
        if (expression.op() != RegexpOp.CONCAT || expression.childCount() != 3 ||
                (expression.parseFlags() & (Regexp.LATIN1 | Regexp.FOLD_CASE)) != 0) {
            return null;
        }
        Regexp first = expression.child(0);
        Regexp repeat = expression.child(1);
        Regexp last = expression.child(2);
        if (first.op() != RegexpOp.CHAR_CLASS || first.charClass().foldsAscii() ||
                repeat.op() != RegexpOp.REPEAT || repeat.min() != repeat.max() || repeat.min() < 1 || repeat.max() > 1000 ||
                repeat.child(0).op() != RegexpOp.CHAR_CLASS || repeat.child(0).charClass().foldsAscii() ||
                last.op() != RegexpOp.LITERAL || last.rune() < 0 || last.rune() > 127 ||
                ((first.parseFlags() | repeat.parseFlags() | repeat.child(0).parseFlags() | last.parseFlags()) & (Regexp.FOLD_CASE | Regexp.LATIN1)) != 0) {
            return null;
        }
        CharClass repeated = repeat.child(0).charClass();
        if (repeated.contains(last.rune()) || repeated.rangeCount() > 64) {
            return null;
        }
        long lowMask = 0;
        long highMask = 0;
        for (int index = 0; index < first.charClass().rangeCount(); index++) {
            RuneRange range = first.charClass().range(index);
            if (range.low() < 0 || range.high() > 127) {
                return null;
            }
            for (int rune = range.low(); rune <= range.high(); rune++) {
                if (rune < 64) {
                    lowMask |= 1L << rune;
                }
                else {
                    highMask |= 1L << (rune - 64);
                }
            }
        }
        int[] ranges = new int[2 * repeated.rangeCount()];
        for (int index = 0; index < repeated.rangeCount(); index++) {
            ranges[2 * index] = repeated.range(index).low();
            ranges[2 * index + 1] = repeated.range(index).high();
        }
        return new DisjointSuffixRepeatMatcher(lowMask, highMask, ranges, repeat.min(), last.rune());
    }

    long estimatedRetainedSize()
    {
        return SizeOf.instanceSize(DisjointSuffixRepeatMatcher.class) + SizeOf.sizeOf(repeatedRanges) + suffix.getRetainedSize();
    }

    long count(Slice input)
    {
        long count = 0;
        int start = 0;
        while (start < input.length()) {
            long span = search(input, start, input.length(), Re2.Anchor.UNANCHORED);
            if (span == Dfa.SEARCH_NO_MATCH) {
                break;
            }
            count++;
            start = (int) span;
        }
        return count;
    }

    long search(Slice input, int start, int end, Re2.Anchor anchor)
    {
        if (end - start < repeatCount + 2) {
            return Dfa.SEARCH_NO_MATCH;
        }
        int searchEnd = anchor == Re2.Anchor.UNANCHORED ? end : (int) Math.min(end, (long) start + 4L * repeatCount + 2);
        Slice searchText = searchEnd == input.length() ? input : input.slice(0, searchEnd);
        byte[] bytes = input.byteArray();
        int base = input.byteArrayOffset();
        int candidate = start + repeatCount + 1;
        while (candidate < searchEnd) {
            int found = searchText.indexOf(suffix, candidate);
            if (found < 0 || found >= searchEnd) {
                return Dfa.SEARCH_NO_MATCH;
            }
            int position = base + found;
            int remaining = repeatCount;
            // B excludes the suffix. A backward check therefore stops before the previous
            // candidate, so failed candidates cannot repeatedly traverse the same long run.
            while (remaining > 0 && position > base + start) {
                int previous = previousRuneStart(bytes, base + start, position);
                if (previous < 0) {
                    break;
                }
                long decoded = Utf8.decode(bytes, previous, position);
                if (!containsRepeated(Utf8.decodedCodePoint(decoded))) {
                    break;
                }
                position = previous;
                remaining--;
            }
            int matchStart = position - base - 1;
            if (remaining == 0 && matchStart >= start && containsPrefix(bytes[position - 1]) &&
                    (anchor == Re2.Anchor.UNANCHORED || matchStart == start) &&
                    (anchor != Re2.Anchor.ANCHOR_BOTH || found + 1 == end)) {
                return ((long) matchStart << 32) | ((found + 1) & 0xFFFF_FFFFL);
            }
            candidate = found + 1;
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    private static int previousRuneStart(byte[] bytes, int start, int end)
    {
        int position = end - 1;
        if (bytes[position] >= 0) {
            return position;
        }
        int minimum = Math.max(start, end - 4);
        while (position > minimum && (bytes[position] & 0xC0) == 0x80) {
            position--;
        }
        long decoded = Utf8.decode(bytes, position, end);
        int width = Utf8.decodedWidth(decoded);
        // A malformed byte decodes to RUNE_ERROR with width one. A real encoded U+FFFD
        // has width three and remains eligible if B contains it.
        return width > 1 && position + width == end ? position : -1;
    }

    private boolean containsRepeated(int rune)
    {
        for (int index = 0; index < repeatedRanges.length; index += 2) {
            if (rune < repeatedRanges[index]) {
                return false;
            }
            if (rune <= repeatedRanges[index + 1]) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPrefix(int value)
    {
        return value >= 0 && (((value < 64 ? prefixLowMask : prefixHighMask) >>> (value & 63)) & 1) != 0;
    }
}
