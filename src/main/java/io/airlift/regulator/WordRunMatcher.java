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

/// Matches a nonempty ASCII word-class run, at least `minimumLength` and at most
/// `maximumLength` bytes long, with the original dialect's endpoint assertions.
final class WordRunMatcher
{
    // [0-9A-Za-z_] as bits of the low (0-63) and high (64-127) ASCII halves.
    private static final long WORD_LOW_MASK = 0x03FF_0000_0000_0000L;
    private static final long WORD_HIGH_MASK = 0x07FF_FFFE_87FF_FFFEL;

    private final long lowMask;
    private final long highMask;
    private final int boundary;
    private final int minimumLength;
    private final int maximumLength;

    private WordRunMatcher(long lowMask, long highMask, int boundary, int minimumLength, int maximumLength)
    {
        this.lowMask = lowMask;
        this.highMask = highMask;
        this.boundary = boundary;
        this.minimumLength = minimumLength;
        this.maximumLength = maximumLength;
    }

    static WordRunMatcher analyze(Regexp expression, Prog program)
    {
        if (expression.op() != RegexpOp.CONCAT || expression.childCount() != 3 ||
                expression.child(0).op() != RegexpOp.WORD_BOUNDARY ||
                expression.child(2).op() != RegexpOp.WORD_BOUNDARY ||
                expression.child(0).parseFlags() != expression.child(2).parseFlags()) {
            return null;
        }
        int boundary = program.textDependentAssertions();
        // An ASCII \b (RE2, Rust with Unicode off) is not text dependent; it compiles to
        // EMPTY_WORD_BOUNDARY.
        boolean asciiBoundary = boundary == 0;
        if (asciiBoundary) {
            boundary = EmptyOp.EMPTY_WORD_BOUNDARY;
        }
        Regexp repetition = expression.child(1);
        // A greedy run between two boundaries can only end at the end of its maximal class run:
        // every shorter end lies between two word characters. So a bounded repetition matches a
        // maximal class run whose length it allows and whose ends are boundaries.
        int minimumLength = 1;
        int maximumLength = Integer.MAX_VALUE;
        if (repetition.op() == RegexpOp.REPEAT && repetition.min() >= 1) {
            minimumLength = repetition.min();
            maximumLength = repetition.max() < 0 ? Integer.MAX_VALUE : repetition.max();
        }
        else if (repetition.op() != RegexpOp.PLUS) {
            return null;
        }
        // Latin-1 only changes how non-ASCII bytes decode, and matching an ASCII class between
        // ASCII boundaries does not depend on that decoding.
        if ((repetition.parseFlags() & (asciiBoundary ? Regexp.NON_GREEDY : Regexp.NON_GREEDY | Regexp.LATIN1)) != 0 ||
                repetition.child(0).op() != RegexpOp.CHAR_CLASS) {
            return null;
        }
        if (boundary != EmptyOp.EMPTY_WORD_BOUNDARY &&
                boundary != EmptyOp.EMPTY_JAVA_WORD_BOUNDARY &&
                boundary != EmptyOp.EMPTY_JAVA_UNICODE_WORD_BOUNDARY &&
                boundary != EmptyOp.EMPTY_RUST_UNICODE_WORD_BOUNDARY &&
                boundary != EmptyOp.EMPTY_UNICODE_WORD_BOUNDARY) {
            return null;
        }
        long lowMask = 0;
        long highMask = 0;
        CharClass characterClass = repetition.child(0).charClass();
        for (int index = 0; index < characterClass.rangeCount(); index++) {
            RuneRange range = characterClass.range(index);
            // In UTF-8 this also keeps a case-insensitive RE2 class such as (?i)[a-z] off the plan:
            // its fold adds the Kelvin sign to k and the long s to s. Latin-1 has neither character,
            // so there the folded class stays ASCII.
            if (range.low() < 0 || range.high() > 127) {
                return null;
            }
            for (int rune = range.low(); rune <= range.high(); rune++) {
                if (!isAsciiWord(rune)) {
                    return null;
                }
                if (rune < 64) {
                    lowMask |= 1L << rune;
                }
                else {
                    highMask |= 1L << (rune - 64);
                }
            }
        }
        return (lowMask | highMask) == 0 ? null : new WordRunMatcher(lowMask, highMask, boundary, minimumLength, maximumLength);
    }

    long estimatedRetainedSize()
    {
        return SizeOf.instanceSize(WordRunMatcher.class);
    }

    long search(Slice text, int contextStart, int contextEnd, int start, int end, CompiledPattern.Anchor anchor)
    {
        byte[] bytes = text.byteArray();
        int base = text.byteArrayOffset();
        int position = base + start;
        int limit = base + end;
        if (minimumLength > 1 && anchor == CompiledPattern.Anchor.UNANCHORED) {
            return searchLongRuns(bytes, base, contextStart, contextEnd, position, limit);
        }
        while (position < limit) {
            if (!contains(bytes[position])) {
                if (anchor != CompiledPattern.Anchor.UNANCHORED) {
                    return Dfa.SEARCH_NO_MATCH;
                }
                position++;
                continue;
            }
            int runStart = position;
            do {
                position++;
            }
            while (position < limit && contains(bytes[position]));

            // Every interior position is between two word characters. Only the endpoints can
            // satisfy a boundary, including when a narrower class splits a larger word.
            int length = position - runStart;
            if (length >= minimumLength && length <= maximumLength &&
                    boundaryAt(bytes, base + contextStart, base + contextEnd, runStart, runStart - 1) &&
                    boundaryAt(bytes, base + contextStart, base + contextEnd, position, position) &&
                    (anchor != CompiledPattern.Anchor.ANCHOR_BOTH || position == limit)) {
                return ((long) (runStart - base - contextStart) << 32) | ((position - base - contextStart) & 0xFFFF_FFFFL);
            }
            if (anchor != CompiledPattern.Anchor.UNANCHORED) {
                return Dfa.SEARCH_NO_MATCH;
            }
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    // A qualifying run that starts between position and the probe, minimumLength - 1 bytes
    // ahead, covers the probe. A probe outside the class rules out every such run, so the
    // search moves past it. A probe in the class scans back to its run's start; when that
    // start lies after position, the search resumes there, since no qualifying run starts
    // earlier. Only a run that reaches back to position is scanned forward for its end, so
    // runs shorter than the minimum are mostly stepped over without reading each byte.
    private long searchLongRuns(byte[] bytes, int base, int contextStart, int contextEnd, int position, int limit)
    {
        int reach = minimumLength - 1;
        while (limit - position > reach) {
            int probe = position + reach;
            if (!contains(bytes[probe])) {
                position = probe + 1;
                continue;
            }
            int runStart = probe;
            while (runStart > position && contains(bytes[runStart - 1])) {
                runStart--;
            }
            if (runStart > position) {
                position = runStart;
                continue;
            }
            int runEnd = probe + 1;
            while (runEnd < limit && contains(bytes[runEnd])) {
                runEnd++;
            }
            if (runEnd - runStart <= maximumLength &&
                    boundaryAt(bytes, base + contextStart, base + contextEnd, runStart, runStart - 1) &&
                    boundaryAt(bytes, base + contextStart, base + contextEnd, runEnd, runEnd)) {
                return ((long) (runStart - base - contextStart) << 32) | ((runEnd - base - contextStart) & 0xFFFF_FFFFL);
            }
            position = runEnd;
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    // `neighbor` is the byte across `position` from the run. A run byte is an ASCII word
    // character, so an ASCII \b holds exactly where the neighbor is outside the text or is not
    // one; the other dialects' boundaries read more context.
    private boolean boundaryAt(byte[] bytes, int begin, int end, int position, int neighbor)
    {
        if (boundary == EmptyOp.EMPTY_WORD_BOUNDARY) {
            return neighbor < begin || neighbor >= end || !isAsciiWord(bytes[neighbor]);
        }
        return (EmptyOp.contextFlags(bytes, begin, end, position, boundary) & boundary) != 0;
    }

    private static boolean isAsciiWord(int value)
    {
        return value >= 0 && (((value < 64 ? WORD_LOW_MASK : WORD_HIGH_MASK) >>> (value & 63)) & 1) != 0;
    }

    private boolean contains(int value)
    {
        return value >= 0 && (((value < 64 ? lowMask : highMask) >>> (value & 63)) & 1) != 0;
    }
}
