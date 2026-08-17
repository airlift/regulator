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

import java.util.Arrays;

import static io.airlift.regulator.Regexp.LATIN1;
import static io.airlift.regulator.Regexp.NON_GREEDY;
import static io.airlift.regulator.RegexpOp.CAPTURE;
import static io.airlift.regulator.RegexpOp.CHAR_CLASS;
import static io.airlift.regulator.RegexpOp.PLUS;
import static io.airlift.regulator.RegexpOp.REPEAT;

final class BoundedCharacterClassCounter
{
    private static final int COMPACT_ROUTE_MINIMUM_REPETITIONS = 32;
    private static final int COMPACT_ROUTE_MINIMUM_RANGES = 32;
    private static final int CODE_POINT_WORD_COUNT = (CharClass.RUNEMAX + Long.SIZE) / Long.SIZE;
    private static final BoundedCharacterClassCounter UNSUPPORTED = new BoundedCharacterClassCounter();

    private final long[] matchingCodePoints;
    private final int minimum;
    private final int maximum;
    private final boolean latin1;

    private BoundedCharacterClassCounter()
    {
        matchingCodePoints = null;
        minimum = 0;
        maximum = 0;
        latin1 = false;
    }

    private BoundedCharacterClassCounter(RuneRange[] ranges, int minimum, int maximum, boolean latin1)
    {
        matchingCodePoints = new long[latin1 ? 4 : CODE_POINT_WORD_COUNT];
        for (RuneRange range : ranges) {
            int low = Math.max(0, range.low());
            int high = Math.min(latin1 ? 0xFF : CharClass.RUNEMAX, range.high());
            if (low <= high) {
                setRange(matchingCodePoints, low, high);
            }
        }
        this.minimum = minimum;
        this.maximum = maximum;
        this.latin1 = latin1;
    }

    private static void setRange(long[] words, int low, int high)
    {
        int firstWord = low >>> 6;
        int lastWord = high >>> 6;
        long firstMask = -1L << (low & 63);
        long lastMask = -1L >>> (63 - (high & 63));
        if (firstWord == lastWord) {
            words[firstWord] |= firstMask & lastMask;
            return;
        }

        words[firstWord] |= firstMask;
        Arrays.fill(words, firstWord + 1, lastWord, -1L);
        words[lastWord] |= lastMask;
    }

    static BoundedCharacterClassCounter analyze(Regexp regexp)
    {
        Analysis analysis = analyzePattern(regexp);
        if (analysis == null) {
            return null;
        }
        return new BoundedCharacterClassCounter(
                analysis.ranges(),
                analysis.minimum(),
                analysis.maximum(),
                analysis.latin1());
    }

    static BoundedCharacterClassCounter analyzeCompactRoute(Regexp regexp)
    {
        if ((regexp.parseFlags() & (LATIN1 | NON_GREEDY | Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) != 0) {
            return null;
        }

        boolean largeFixedRepeat = regexp.op() == REPEAT &&
                regexp.min() == regexp.max() &&
                regexp.min() >= COMPACT_ROUTE_MINIMUM_REPETITIONS;
        boolean variableRepeat = regexp.op() == PLUS ||
                (regexp.op() == REPEAT && regexp.min() != regexp.max());
        if (!largeFixedRepeat && !variableRepeat) {
            return null;
        }

        Analysis analysis = analyzePattern(regexp);
        if (analysis == null) {
            return null;
        }

        Regexp atom = regexp.child(0);
        if (atom.op() != CHAR_CLASS ||
                atom.charClass().rangeCount() < COMPACT_ROUTE_MINIMUM_RANGES ||
                atom.charClass().range(atom.charClass().rangeCount() - 1).high() <= 0xFF) {
            return null;
        }
        return new BoundedCharacterClassCounter(
                analysis.ranges(),
                analysis.minimum(),
                analysis.maximum(),
                analysis.latin1());
    }

    long estimatedRetainedSize()
    {
        return SizeOf.instanceSize(BoundedCharacterClassCounter.class) +
                SizeOf.sizeOf(matchingCodePoints);
    }

    static boolean supports(Regexp regexp)
    {
        return analyzePattern(regexp) != null;
    }

    private static Analysis analyzePattern(Regexp regexp)
    {
        while (regexp.op() == CAPTURE) {
            regexp = regexp.child(0);
        }
        if ((regexp.parseFlags() & NON_GREEDY) != 0) {
            return null;
        }

        int minimum;
        int maximum;
        if (regexp.op() == PLUS) {
            minimum = 1;
            maximum = -1;
        }
        else if (regexp.op() == REPEAT &&
                regexp.min() > 0 &&
                (regexp.max() == -1 || regexp.max() >= regexp.min())) {
            minimum = regexp.min();
            maximum = regexp.max();
        }
        else {
            return null;
        }

        Regexp atom = regexp.child(0);
        while (atom.op() == CAPTURE) {
            atom = atom.child(0);
        }
        if (atom.op() != CHAR_CLASS || atom.charClass().isEmpty()) {
            return null;
        }
        return new Analysis(
                atom.charClass().ranges(),
                minimum,
                maximum,
                (regexp.parseFlags() & LATIN1) != 0);
    }

    private record Analysis(RuneRange[] ranges, int minimum, int maximum, boolean latin1) {}

    static BoundedCharacterClassCounter unsupported()
    {
        return UNSUPPORTED;
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: decoding and bitset membership replace one complete matcher
    // invocation per result. Changes require focused Intel and Graviton qualification.
    long count(Slice input)
    {
        byte[] bytes = input.byteArray();
        int position = input.byteArrayOffset();
        int end = position + input.length();
        int runLength = 0;
        long count = 0;

        while (position < end) {
            int codePoint;
            int width;
            if (latin1) {
                codePoint = bytes[position] & 0xFF;
                width = 1;
            }
            else {
                long decoded = Utf8.decode(bytes, position, end);
                codePoint = Utf8.decodedCodePoint(decoded);
                width = Utf8.decodedWidth(decoded);
                if (width == 1 && codePoint == Utf8.RUNE_ERROR && (bytes[position] & 0xFF) >= 0x80) {
                    codePoint = -1;
                }
            }

            if (matches(codePoint)) {
                runLength++;
            }
            else {
                count += countRun(runLength);
                runLength = 0;
            }
            position += width;
        }
        return count + countRun(runLength);
    }

    private long countRun(int runLength)
    {
        if (maximum == -1) {
            return runLength >= minimum ? 1 : 0;
        }
        int completeMatches = runLength / maximum;
        int remainder = runLength - (completeMatches * maximum);
        return (long) completeMatches + (remainder >= minimum ? 1 : 0);
    }

    int sampleMatchingCodePointCount(Slice input, int inputOffset, int inputBytes, int maximumMatchingCodePoints)
    {
        byte[] bytes = input.byteArray();
        int position = input.byteArrayOffset() + inputOffset;
        int end = position + inputBytes;
        int matchingCodePoints = 0;

        while (position < end) {
            long decoded = decode(bytes, position, end);
            int codePoint = Utf8.decodedCodePoint(decoded);
            int width = Utf8.decodedWidth(decoded);
            if (matches(codePoint) && ++matchingCodePoints > maximumMatchingCodePoints) {
                return matchingCodePoints;
            }
            position += width;
        }
        return matchingCodePoints;
    }

    long findSpan(Slice input, int contextStart, int contextEnd, int start)
    {
        if (contextStart < 0 || contextEnd < contextStart || contextEnd > input.length() ||
                start < contextStart || start > contextEnd) {
            return Dfa.SEARCH_NO_MATCH;
        }

        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int position = inputOffset + start;
        int end = inputOffset + contextEnd;
        while (position < end) {
            int runStart = position;
            int runLength = 0;
            while (position < end) {
                long decoded = decode(bytes, position, end);
                int codePoint = Utf8.decodedCodePoint(decoded);
                int width = Utf8.decodedWidth(decoded);
                if (!matches(codePoint)) {
                    if (runLength >= minimum) {
                        return packSpan(inputOffset, contextStart, runStart, position);
                    }
                    position += width;
                    break;
                }

                position += width;
                runLength++;
                if (runLength == maximum) {
                    return packSpan(inputOffset, contextStart, runStart, position);
                }
            }
            if (runLength >= minimum) {
                return packSpan(inputOffset, contextStart, runStart, position);
            }
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    private long decode(byte[] bytes, int position, int end)
    {
        if (latin1) {
            return (1L << 32) | (bytes[position] & 0xFFL);
        }
        long decoded = Utf8.decode(bytes, position, end);
        int codePoint = Utf8.decodedCodePoint(decoded);
        int width = Utf8.decodedWidth(decoded);
        if (width == 1 && codePoint == Utf8.RUNE_ERROR && (bytes[position] & 0xFF) >= 0x80) {
            return (1L << 32) | 0xFFFF_FFFFL;
        }
        return decoded;
    }

    private static long packSpan(int inputOffset, int contextStart, int matchStart, int matchEnd)
    {
        int relativeStart = matchStart - inputOffset - contextStart;
        int relativeEnd = matchEnd - inputOffset - contextStart;
        return ((long) relativeStart << 32) | (relativeEnd & 0xFFFF_FFFFL);
    }

    private boolean matches(int codePoint)
    {
        return codePoint >= 0 &&
                codePoint < matchingCodePoints.length * Long.SIZE &&
                (matchingCodePoints[codePoint >>> 6] & (1L << codePoint)) != 0;
    }
}
