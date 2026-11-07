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

/// Searches A B{n} x where A is ASCII and the ASCII suffix x is excluded from B. A Latin-1 B,
/// as in RE2's Latin-1 mode and Rust's byte mode, reads one byte per character.
final class DisjointSuffixRepeatMatcher
{
    private final long prefixLowMask;
    private final long prefixHighMask;
    private final int[] repeatedRanges;
    private final boolean repeatedLatin1;
    private final boolean repeatedMatchesAllNonAscii;
    private final int repeatCount;
    private final Slice suffix;

    private DisjointSuffixRepeatMatcher(long prefixLowMask, long prefixHighMask, int[] repeatedRanges, boolean repeatedLatin1, boolean repeatedMatchesAllNonAscii, int repeatCount, int suffix)
    {
        this.prefixLowMask = prefixLowMask;
        this.prefixHighMask = prefixHighMask;
        this.repeatedRanges = repeatedRanges;
        this.repeatedLatin1 = repeatedLatin1;
        this.repeatedMatchesAllNonAscii = repeatedMatchesAllNonAscii;
        this.repeatCount = repeatCount;
        this.suffix = Slices.wrappedBuffer((byte) suffix);
    }

    static DisjointSuffixRepeatMatcher analyze(Regexp expression)
    {
        if (expression.op() != RegexpOp.CONCAT || expression.childCount() != 3 || (expression.parseFlags() & Regexp.FOLD_CASE) != 0) {
            return null;
        }
        Regexp first = expression.child(0);
        Regexp repeat = expression.child(1);
        Regexp last = expression.child(2);
        if (first.op() != RegexpOp.CHAR_CLASS || first.charClass().foldsAscii() ||
                repeat.op() != RegexpOp.REPEAT || repeat.min() != repeat.max() || repeat.min() < 1 || repeat.max() > 1000 ||
                repeat.child(0).op() != RegexpOp.CHAR_CLASS || repeat.child(0).charClass().foldsAscii() ||
                last.op() != RegexpOp.LITERAL || last.rune() < 0 || last.rune() > 127 ||
                ((first.parseFlags() | repeat.parseFlags() | repeat.child(0).parseFlags() | last.parseFlags()) & Regexp.FOLD_CASE) != 0) {
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
        boolean matchesAllNonAscii = false;
        for (int index = 0; index < repeated.rangeCount(); index++) {
            RuneRange range = repeated.range(index);
            ranges[2 * index] = range.low();
            ranges[2 * index + 1] = range.high();
            matchesAllNonAscii |= Compiler.holdsAllNonAscii(range);
        }
        // A and x are ASCII, which reads alike in both encodings, so only B's encoding matters.
        boolean repeatedLatin1 = (repeat.child(0).parseFlags() & Regexp.LATIN1) != 0;
        return new DisjointSuffixRepeatMatcher(lowMask, highMask, ranges, repeatedLatin1, matchesAllNonAscii, repeat.min(), last.rune());
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
            long span = search(input, start, input.length(), CompiledPattern.Anchor.UNANCHORED);
            if (span == Dfa.SEARCH_NO_MATCH) {
                break;
            }
            count++;
            start = (int) span;
        }
        return count;
    }

    long search(Slice input, int start, int end, CompiledPattern.Anchor anchor)
    {
        if (end - start < repeatCount + 2) {
            return Dfa.SEARCH_NO_MATCH;
        }
        int searchEnd = anchor == CompiledPattern.Anchor.UNANCHORED ? end : (int) Math.min(end, (long) start + 4L * repeatCount + 2);
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
                int previous = previousRepeatedStart(bytes, base + start, position);
                if (previous < 0) {
                    break;
                }
                position = previous;
                remaining--;
            }
            int matchStart = position - base - 1;
            if (remaining == 0 && matchStart >= start && containsPrefix(bytes[position - 1]) &&
                    (anchor == CompiledPattern.Anchor.UNANCHORED || matchStart == start) &&
                    (anchor != CompiledPattern.Anchor.ANCHOR_BOTH || found + 1 == end)) {
                return ((long) matchStart << 32) | ((found + 1) & 0xFFFF_FFFFL);
            }
            candidate = found + 1;
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    /// Returns the start of the character that ends at `end` and starts at or after `start` when B
    /// holds it, or `-1`.
    private int previousRepeatedStart(byte[] bytes, int start, int end)
    {
        if (repeatedLatin1) {
            return containsRepeated(bytes[end - 1] & 0xFF) ? end - 1 : -1;
        }
        // B's byte ranges read a character as Utf8.re2DecodeClassCharacter does, so the only
        // character that can end at `end` starts at the nearest byte that is not a continuation.
        int previous = Utf8.previousCharacterStart(bytes, start, end);
        long decoded = Utf8.re2DecodeClassCharacter(bytes, previous, end, repeatedMatchesAllNonAscii);
        return previous + Utf8.decodedWidth(decoded) == end && containsRepeated(Utf8.decodedCodePoint(decoded)) ? previous : -1;
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
