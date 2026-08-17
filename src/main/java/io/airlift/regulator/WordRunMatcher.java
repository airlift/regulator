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

/**
 * Matches a nonempty ASCII word-class run with the original dialect's endpoint assertions.
 */
final class WordRunMatcher
{
    private final long lowMask;
    private final long highMask;
    private final int boundary;

    private WordRunMatcher(long lowMask, long highMask, int boundary)
    {
        this.lowMask = lowMask;
        this.highMask = highMask;
        this.boundary = boundary;
    }

    static WordRunMatcher analyze(Regexp expression, Prog program)
    {
        if (expression.op() != RegexpOp.CONCAT || expression.childCount() != 3 ||
                expression.child(0).op() != RegexpOp.WORD_BOUNDARY ||
                expression.child(2).op() != RegexpOp.WORD_BOUNDARY ||
                expression.child(0).parseFlags() != expression.child(2).parseFlags()) {
            return null;
        }
        Regexp repetition = expression.child(1);
        if (repetition.op() != RegexpOp.PLUS ||
                (repetition.parseFlags() & (Regexp.NON_GREEDY | Regexp.LATIN1)) != 0 ||
                repetition.child(0).op() != RegexpOp.CHAR_CLASS) {
            return null;
        }
        int boundary = program.textDependentAssertions();
        if (boundary != EmptyOp.EMPTY_JAVA_WORD_BOUNDARY &&
                boundary != EmptyOp.EMPTY_JAVA_UNICODE_WORD_BOUNDARY &&
                boundary != EmptyOp.EMPTY_UNICODE_WORD_BOUNDARY) {
            return null;
        }
        long lowMask = 0;
        long highMask = 0;
        CharClass characterClass = repetition.child(0).charClass();
        for (int index = 0; index < characterClass.rangeCount(); index++) {
            RuneRange range = characterClass.range(index);
            if (range.low() < 0 || range.high() > 127) {
                return null;
            }
            for (int rune = range.low(); rune <= range.high(); rune++) {
                if (!((rune >= 'a' && rune <= 'z') || (rune >= 'A' && rune <= 'Z') ||
                        (rune >= '0' && rune <= '9') || rune == '_')) {
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
        return (lowMask | highMask) == 0 ? null : new WordRunMatcher(lowMask, highMask, boundary);
    }

    long estimatedRetainedSize()
    {
        return SizeOf.instanceSize(WordRunMatcher.class);
    }

    long search(Slice text, int contextStart, int contextEnd, int start, int end, Re2.Anchor anchor)
    {
        byte[] bytes = text.byteArray();
        int base = text.byteArrayOffset();
        int position = base + start;
        int limit = base + end;
        while (position < limit) {
            if (!contains(bytes[position])) {
                if (anchor != Re2.Anchor.UNANCHORED) {
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
            if ((EmptyOp.contextFlags(bytes, base + contextStart, base + contextEnd, runStart, boundary) & boundary) != 0 &&
                    (EmptyOp.contextFlags(bytes, base + contextStart, base + contextEnd, position, boundary) & boundary) != 0 &&
                    (anchor != Re2.Anchor.ANCHOR_BOTH || position == limit)) {
                return ((long) (runStart - base - contextStart) << 32) | ((position - base - contextStart) & 0xFFFF_FFFFL);
            }
            if (anchor != Re2.Anchor.UNANCHORED) {
                return Dfa.SEARCH_NO_MATCH;
            }
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    private boolean contains(int value)
    {
        return value >= 0 && (((value < 64 ? lowMask : highMask) >>> (value & 63)) & 1) != 0;
    }
}
