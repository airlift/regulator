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
import static io.airlift.regulator.ScanPlanRun.SCALAR_CANDIDATE_PREFIX_BYTES;

final class BoundedCharacterClassCounter
{
    private static final int COMPACT_ROUTE_MINIMUM_REPETITIONS = 32;
    private static final int COMPACT_ROUTE_MINIMUM_RANGES = 32;
    // Planes 4 through 13 hold no characters, so only a class that reaches the last planes ends
    // here or beyond: a negated class, or one with plane-14 members such as `\p{Mn}` or Unicode
    // `\w`. Its bitset would cost up to 136 KiB per pattern.
    private static final int SINGLE_CLASS_LAST_MEMBER_LIMIT = 0x40000;
    private static final BoundedCharacterClassCounter UNSUPPORTED = new BoundedCharacterClassCounter();

    private final long[] matchingCodePoints;
    private final boolean[] memberStartBytes;
    private final byte[] memberStartByteNibbleTables;
    private final int minimum;
    private final int maximum;
    private final boolean readsBytes;
    private final boolean matchesAllNonAscii;

    private BoundedCharacterClassCounter()
    {
        matchingCodePoints = null;
        memberStartBytes = null;
        memberStartByteNibbleTables = null;
        minimum = 0;
        maximum = 0;
        readsBytes = false;
        matchesAllNonAscii = false;
    }

    private BoundedCharacterClassCounter(RuneRange[] ranges, int minimum, int maximum, boolean latin1)
    {
        // Every byte of a non-ASCII character, malformed or not, is at least 0x80, so a class of
        // only ASCII characters reads alike byte by byte, as a Latin-1 class does.
        int lastHigh = ranges[ranges.length - 1].high();
        boolean readsBytes = latin1 || lastHigh <= 0x7F;
        // The bitset ends at the class's last member; `matches` rejects every code point beyond.
        int lastMember = Math.min(CharClass.RUNEMAX, lastHigh);
        matchingCodePoints = new long[readsBytes ? 4 : (lastMember >>> 6) + 1];
        boolean matchesAllNonAscii = false;
        for (RuneRange range : ranges) {
            matchesAllNonAscii |= !latin1 && Compiler.holdsAllNonAscii(range);
            int low = Math.max(0, range.low());
            int high = Math.min(latin1 ? 0xFF : CharClass.RUNEMAX, range.high());
            if (low <= high) {
                setRange(matchingCodePoints, low, high);
            }
        }
        memberStartBytes = buildMemberStartBytes(ranges, readsBytes);
        memberStartByteNibbleTables = VectorSupport.isAvailable() ? buildMemberStartByteNibbleTables(memberStartBytes) : null;
        this.minimum = minimum;
        this.maximum = maximum;
        this.readsBytes = readsBytes;
        this.matchesAllNonAscii = matchesAllNonAscii;
    }

    // Marks each byte that can begin a member. A class that reads bytes marks its members. Any
    // other class marks its ASCII members and every lead byte that begins one of its code points.
    //
    // `decodeUtf8` can also read a malformed sequence as a member: an encoded surrogate, or any
    // malformed sequence in a class that holds every non-ASCII character. Such a sequence begins
    // with a lead byte that the class's own code points already mark.
    private static boolean[] buildMemberStartBytes(RuneRange[] ranges, boolean readsBytes)
    {
        boolean[] startBytes = new boolean[256];
        int highLimit = readsBytes ? 0xFF : CharClass.RUNEMAX;
        int singleByteLimit = readsBytes ? 0xFF : 0x7F;
        for (RuneRange range : ranges) {
            int low = Math.max(0, range.low());
            int high = Math.min(highLimit, range.high());
            for (int value = low; value <= Math.min(high, singleByteLimit); value++) {
                startBytes[value] = true;
            }
            if (!readsBytes) {
                markLeadBytes(startBytes, low, high, 0x80, 0x7FF, 6, 0xC0);
                markLeadBytes(startBytes, low, high, 0x800, 0xFFFF, 12, 0xE0);
                markLeadBytes(startBytes, low, high, 0x10000, CharClass.RUNEMAX, 18, 0xF0);
            }
        }
        return startBytes;
    }

    // A sparse class has few start bytes, which the vector nibble scan finds. The tables are null
    // for fewer than 4 or more than 64 start bytes, as in `\pL`, or for more than 8 high nibbles.
    private static byte[] buildMemberStartByteNibbleTables(boolean[] memberStartBytes)
    {
        byte[] candidates = new byte[memberStartBytes.length];
        for (int value = 0; value < memberStartBytes.length; value++) {
            candidates[value] = (byte) (memberStartBytes[value] ? 1 : 0);
        }
        return VectorByteSetScanner.buildNibbleTables(candidates);
    }

    // Marks the lead bytes of the code points in `low..high` that encode in one UTF-8 width,
    // `widthLow..widthHigh`. Such a code point's lead byte is `leadBits` combined with the code
    // point shifted right by `shift`.
    private static void markLeadBytes(boolean[] startBytes, int low, int high, int widthLow, int widthHigh, int shift, int leadBits)
    {
        int first = Math.max(low, widthLow);
        int last = Math.min(high, widthHigh);
        if (first > last) {
            return;
        }
        Arrays.fill(startBytes, leadBits | (first >>> shift), (leadBits | (last >>> shift)) + 1, true);
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
        if (analysis == null || !isLargeUnicodeClass(regexp.child(0))) {
            return null;
        }
        return new BoundedCharacterClassCounter(
                analysis.ranges(),
                analysis.minimum(),
                analysis.maximum(),
                analysis.latin1());
    }

    /// Returns a counter whose runs are the matches of `regexp` when it is one class of the
    /// compact route's kind, so each match is a single member, or `null`. Count and boolean find
    /// keep the program; the counter serves a matcher, which would otherwise restart the engine
    /// for every member.
    static BoundedCharacterClassCounter analyzeSingleClass(Regexp regexp)
    {
        if ((regexp.parseFlags() & (LATIN1 | Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) != 0 ||
                !isLargeUnicodeClass(regexp) ||
                regexp.charClass().range(regexp.charClass().rangeCount() - 1).high() >= SINGLE_CLASS_LAST_MEMBER_LIMIT) {
            return null;
        }
        return new BoundedCharacterClassCounter(regexp.charClass().ranges(), 1, 1, false);
    }

    private static boolean isLargeUnicodeClass(Regexp atom)
    {
        return atom.op() == CHAR_CLASS && atom.charClass().rangeCount() >= COMPACT_ROUTE_MINIMUM_RANGES && atom.charClass().range(atom.charClass().rangeCount() - 1).high() > 0xFF;
    }

    boolean readsBytesForDiagnostics()
    {
        return readsBytes;
    }

    boolean scansMemberStartsWithVectorsForDiagnostics()
    {
        return memberStartByteNibbleTables != null;
    }

    long estimatedRetainedSize()
    {
        return SizeOf.instanceSize(BoundedCharacterClassCounter.class) +
                SizeOf.sizeOf(matchingCodePoints) +
                SizeOf.sizeOf(memberStartBytes) +
                SizeOf.sizeOf(memberStartByteNibbleTables);
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
        // A Rust bytes pattern marks only its byte classes, so the class decides the encoding.
        return new Analysis(
                atom.charClass().ranges(),
                minimum,
                maximum,
                (atom.parseFlags() & LATIN1) != 0);
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
            if (readsBytes) {
                codePoint = bytes[position] & 0xFF;
                width = 1;
            }
            else {
                long decoded = decodeUtf8(bytes, position, end);
                codePoint = Utf8.decodedCodePoint(decoded);
                width = Utf8.decodedWidth(decoded);
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
        // Most runs hold one match at most, so only a longer one pays the division.
        if (runLength <= maximum) {
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

    /// Returns whether `input` has a match: a run of at least `minimum` matching code points.
    /// Unlike [#findSpan], it returns as soon as a run reaches that length rather than reading to
    /// the run's end.
    boolean contains(Slice input)
    {
        byte[] bytes = input.byteArray();
        int position = input.byteArrayOffset();
        int end = position + input.length();
        int runLength = 0;
        while (position < end) {
            long decoded = decode(bytes, position, end);
            if (matches(Utf8.decodedCodePoint(decoded))) {
                if (++runLength >= minimum) {
                    return true;
                }
            }
            else {
                runLength = 0;
            }
            position += Utf8.decodedWidth(decoded);
        }
        return false;
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: the start-byte skip and inline decoding replace one DFA
    // restart per match. Changes require focused Intel and Graviton qualification.
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
            // Skip the bytes that cannot begin a member. Each costs one table load, as in the DFA's
            // loop; a sparse class instead scans a short prefix byte by byte, then vectors. In UTF-8
            // mode the bytes inside a sequence are continuation bytes, which are never marked, so
            // the skip stops where the decode loop would.
            if (memberStartByteNibbleTables == null) {
                while (!memberStartBytes[bytes[position] & 0xFF]) {
                    if (++position == end) {
                        return Dfa.SEARCH_NO_MATCH;
                    }
                }
            }
            else {
                position = findMemberStart(bytes, position, end);
                if (position < 0) {
                    return Dfa.SEARCH_NO_MATCH;
                }
            }
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

    // Returns the first index at or after `position` of a byte that can begin a member, or `-1`.
    private int findMemberStart(byte[] bytes, int position, int end)
    {
        // Dense text has a member start within a byte or two, where vector setup costs more than it saves.
        int scalarEnd = Math.min(end, position + SCALAR_CANDIDATE_PREFIX_BYTES);
        for (; position < scalarEnd; position++) {
            if (memberStartBytes[bytes[position] & 0xFF]) {
                return position;
            }
        }
        if (position == end) {
            return -1;
        }
        return VectorByteSetScanner.findNibbleTable(bytes, position, end - position, memberStartByteNibbleTables);
    }

    private long decode(byte[] bytes, int position, int end)
    {
        if (readsBytes) {
            return (1L << 32) | (bytes[position] & 0xFFL);
        }
        return decodeUtf8(bytes, position, end);
    }

    // ASCII and well-formed two-byte characters, most of the text in Latin, Greek, Cyrillic,
    // Hebrew, and Arabic, are decoded inline; every other sequence, malformed ones included, takes
    // the general decoder.
    private long decodeUtf8(byte[] bytes, int position, int end)
    {
        int first = bytes[position];
        if (first >= 0) {
            return (1L << 32) | first;
        }
        if (first >= (byte) 0xC2 && first < (byte) 0xE0 && position + 1 < end) {
            int second = bytes[position + 1];
            if ((second & 0xC0) == 0x80) {
                return (2L << 32) | ((first & 0x1F) << 6) | (second & 0x3F);
            }
        }
        long decoded = Utf8.decode(bytes, position, end);
        int codePoint = Utf8.decodedCodePoint(decoded);
        int width = Utf8.decodedWidth(decoded);
        if (width == 1 && codePoint == Utf8.RUNE_ERROR) {
            return Utf8.re2DecodeMalformed(bytes, position, end, matchesAllNonAscii);
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
