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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

import static io.airlift.regulator.Regexp.LATIN1;
import static io.airlift.regulator.Regexp.NON_GREEDY;
import static io.airlift.regulator.RegexpOp.ANY_BYTE;
import static io.airlift.regulator.RegexpOp.ANY_CHAR;
import static io.airlift.regulator.RegexpOp.CAPTURE;
import static io.airlift.regulator.RegexpOp.CHAR_CLASS;
import static io.airlift.regulator.RegexpOp.PLUS;
import static io.airlift.regulator.RegexpOp.REPEAT;
import static io.airlift.regulator.ScanPlanRun.SCALAR_CANDIDATE_PREFIX_BYTES;

final class BoundedCharacterClassCounter
{
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(BoundedCharacterClassCounter.class);
    private static final int COMPACT_ROUTE_MINIMUM_REPETITIONS = 32;
    private static final int COMPACT_ROUTE_MINIMUM_RANGES = 32;
    // Planes 4 through 13 hold no characters, so only a class that reaches the last planes ends
    // here or beyond, such as `\PL`, `\p{Mn}`, or Unicode `\w`. Its bitset would cost up to
    // 136 KiB per pattern, unless the class holds every non-ASCII character, as a negated ASCII
    // class does; its bitset then ends at U+07FF.
    private static final int LAST_MEMBER_LIMIT = 0x40000;
    private static final BoundedCharacterClassCounter UNSUPPORTED = new BoundedCharacterClassCounter();
    private static final RuneRange[] ALL_CHARACTERS = {new RuneRange(0, CharClass.RUNEMAX)};
    private static final RuneRange[] ALL_BYTES = {new RuneRange(0, 0xFF)};
    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long LOW_BITS = 0x0101010101010101L;
    private static final long HIGH_BITS = 0x8080808080808080L;
    // The bytes `twoByteLeads` checks at once for ASCII and two-byte characters. A block of
    // `countUtf8` is one such chunk, or two when the second passes too.
    private static final int TWO_BYTE_CHUNK_BYTES = 32;
    // The longest stretch `countUtf8` decodes between chunk checks, finishing the character that
    // crosses its end. Each failed check doubles the stretch, starting from one chunk, and a
    // passing check starts over.
    private static final int MAX_DECODE_STRETCH_BYTES = 1024;
    // What `twoByteLeads` returns for a chunk holding anything but ASCII and two-byte characters;
    // a chunk it accepts never has two adjacent leads, so it never has every bit set.
    private static final long INVALID_CHUNK = -1L;
    // The first code point that takes three UTF-8 bytes.
    private static final int TWO_BYTE_LIMIT = 0x800;

    private final long[] matchingCodePoints;
    private final boolean[] memberStartBytes;
    private final byte[] memberStartByteNibbleTables;
    private final int minimum;
    private final int maximum;
    // The run length at which `findSpan` stops testing membership: the maximum, or, for an
    // unbounded repeat of a UTF-8 class holding every character, the minimum, after which it skips
    // the rest of the run.
    private final int decidedRunLength;
    private final boolean readsBytes;
    private final boolean matchesAllNonAscii;

    private BoundedCharacterClassCounter()
    {
        matchingCodePoints = null;
        memberStartBytes = null;
        memberStartByteNibbleTables = null;
        minimum = 0;
        maximum = 0;
        decidedRunLength = 0;
        readsBytes = false;
        matchesAllNonAscii = false;
    }

    private BoundedCharacterClassCounter(RuneRange[] ranges, int minimum, int maximum, boolean latin1)
    {
        // Every byte of a non-ASCII character, malformed or not, is at least 0x80, so a class of
        // only ASCII characters reads alike byte by byte, as a Latin-1 class does.
        int lastHigh = ranges[ranges.length - 1].high();
        boolean readsBytes = latin1 || lastHigh <= 0x7F;
        boolean matchesAllNonAscii = !latin1 && holdsAllNonAscii(ranges);
        // The bitset ends at the class's last member, but a bitset that `countUtf8` reads reaches
        // at least U+07FF, so it reads the membership of every one- and two-byte code point from it. A
        // class holding every non-ASCII character marks only its ASCII members and every code
        // point from U+0080 to U+07FF. `matches` rejects every code point beyond the bitset,
        // except that such a class accepts them.
        int lastMember = matchesAllNonAscii ? 0x7F : Math.min(CharClass.RUNEMAX, lastHigh);
        matchingCodePoints = new long[readsBytes ? 4 : (Math.max(TWO_BYTE_LIMIT - 1, lastMember) >>> 6) + 1];
        for (RuneRange range : ranges) {
            int low = Math.max(0, range.low());
            int high = Math.min(latin1 ? 0xFF : lastMember, range.high());
            if (low <= high) {
                setRange(matchingCodePoints, low, high);
            }
        }
        if (matchesAllNonAscii) {
            setRange(matchingCodePoints, 0x80, TWO_BYTE_LIMIT - 1);
        }
        boolean matchesEveryCharacter = matchesAllNonAscii && matchingCodePoints[0] == -1L && matchingCodePoints[1] == -1L;
        memberStartBytes = buildMemberStartBytes(ranges, readsBytes);
        memberStartByteNibbleTables = VectorSupport.isAvailable() ? buildMemberStartByteNibbleTables(memberStartBytes) : null;
        this.minimum = minimum;
        this.maximum = maximum;
        this.decidedRunLength = maximum == -1 && matchesEveryCharacter ? minimum : maximum;
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

    /// Returns a counter whose runs are the matches of `regexp`, or `null`. A matcher would
    /// otherwise restart the engine for every match. `regexp` qualifies as one class of the compact
    /// route's kind, so each match is a single member, or as a greedy repeat of one class with a
    /// minimum of at least one, so each match is a run of members cut at the repeat's maximum. A
    /// repeat compiled for longest match keeps the engine, as on the compact route, and so does a
    /// repeat that RE2 or Trino case-folds; Java and Rust fold a class while parsing it. Count
    /// keeps its counting algorithm and reuses this counter for a class repeat; boolean find keeps
    /// its separate route.
    static BoundedCharacterClassCounter analyzeMatcherRoute(Regexp regexp, boolean longestMatch)
    {
        if ((regexp.parseFlags() & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) != 0) {
            return null;
        }
        if (regexp.op() == CHAR_CLASS) {
            RuneRange[] ranges = regexp.charClass().ranges();
            if ((regexp.parseFlags() & LATIN1) != 0 || !isLargeUnicodeClass(regexp) || needsLastPlaneBitset(ranges)) {
                return null;
            }
            return new BoundedCharacterClassCounter(ranges, 1, 1, false);
        }

        if (longestMatch) {
            return null;
        }
        Analysis analysis = analyzePattern(regexp);
        // A byte class retains a 256-bit set whatever its ranges.
        if (analysis == null || (!analysis.latin1() && needsLastPlaneBitset(analysis.ranges()))) {
            return null;
        }
        return new BoundedCharacterClassCounter(analysis.ranges(), analysis.minimum(), analysis.maximum(), analysis.latin1());
    }

    private static boolean needsLastPlaneBitset(RuneRange[] ranges)
    {
        return ranges[ranges.length - 1].high() >= LAST_MEMBER_LIMIT && !holdsAllNonAscii(ranges);
    }

    // The ranges are sorted, so only the last can hold every non-ASCII character.
    private static boolean holdsAllNonAscii(RuneRange[] ranges)
    {
        return Compiler.holdsAllNonAscii(ranges[ranges.length - 1]);
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

    boolean skipsEveryCharacterRunsForDiagnostics()
    {
        return decidedRunLength != maximum;
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + SizeOf.sizeOf(matchingCodePoints) + SizeOf.sizeOf(memberStartBytes) + SizeOf.sizeOf(memberStartByteNibbleTables);
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
        // A parser keeps a dot that matches newline, or a full class, as ANY_CHAR, and a Rust bytes
        // dot as ANY_BYTE.
        if (atom.op() == ANY_CHAR) {
            return new Analysis(ALL_CHARACTERS, minimum, maximum, (atom.parseFlags() & LATIN1) != 0);
        }
        if (atom.op() == ANY_BYTE) {
            return new Analysis(ALL_BYTES, minimum, maximum, true);
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

    // Decoding and bitset membership replace one complete matcher invocation per result. The
    // loops are in `countBytes`, `countUtf8`, and `countDecoded`.
    long count(Slice input)
    {
        // Every code point takes at least one byte, so an input shorter than the minimum can hold no match.
        if (input.length() < minimum) {
            return 0;
        }
        byte[] bytes = input.byteArray();
        int position = input.byteArrayOffset();
        int end = position + input.length();
        if (readsBytes) {
            return countBytes(bytes, position, end);
        }
        return countUtf8(bytes, position, end);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: A run ends at every word boundary of ordinary text, too
    // often and too irregularly for a branch on membership to predict, so a byte class counts
    // without one: a run reaching the maximum counts one match and starts over, and a nonmember
    // counts the run's remainder when it reaches the minimum. On Graviton this per-byte update
    // beat reading membership into a block mask, which only `countUtf8` keeps. Changes require
    // focused Intel and Graviton qualification.
    private long countBytes(byte[] bytes, int position, int end)
    {
        long[] members = matchingCodePoints;
        // No input reaches Integer.MAX_VALUE bytes, so a run of an unbounded repeat never reaches the maximum.
        int maximum = this.maximum == -1 ? Integer.MAX_VALUE : this.maximum;
        int minimum = this.minimum;
        int runLength = 0;
        long count = 0;
        for (; position < end; position++) {
            int value = bytes[position] & 0xFF;
            int member = (int) (members[value >>> 6] >>> value) & 1;
            runLength += member;
            int full = runLength == maximum ? 1 : 0;
            count += full;
            runLength &= full - 1;
            count += (member ^ 1) & ((minimum - 1 - runLength) >>> 31);
            runLength &= -member;
        }
        return count + (runLength >= minimum ? 1 : 0);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: Text of ASCII and two-byte characters, as in Latin, Greek,
    // Cyrillic, Hebrew, and Arabic, counts as `countBytes` does, without branching on width or
    // membership: each byte that ends a character, an ASCII byte or a continuation byte, takes
    // the membership of the code point it ends, and a lead byte is neither member nor nonmember,
    // so it leaves the run unchanged. Each 32-byte chunk of such text is checked first, and the
    // next one only when it passes. Other text, holding a longer character or a malformed
    // sequence, and the last bytes, fewer than one chunk, decode character by character with the
    // same run state, for a stretch that doubles while checks keep failing: CJK text, which fails
    // every check, would otherwise pay one per 32 bytes. A check that fails after the longest stretch hands the rest of the input,
    // whatever it holds, to `countDecoded`.
    // Changes require focused Intel and Graviton qualification.
    private long countUtf8(byte[] bytes, int position, int end)
    {
        long[] members = matchingCodePoints;
        // No input reaches Integer.MAX_VALUE bytes, so a run of an unbounded repeat holds one match at most.
        int maximum = this.maximum == -1 ? Integer.MAX_VALUE : this.maximum;
        int minimum = this.minimum;
        int runLength = 0;
        long count = 0;
        int stretch = TWO_BYTE_CHUNK_BYTES;
        while (position < end) {
            long leads = end - position >= TWO_BYTE_CHUNK_BYTES ? twoByteLeads(bytes, position, 0) : INVALID_CHUNK;
            if (leads != INVALID_CHUNK) {
                int blockBytes = TWO_BYTE_CHUNK_BYTES;
                if (end - position >= 2 * TWO_BYTE_CHUNK_BYTES) {
                    // Bit 7 when the first chunk ends with a lead byte, whose continuation begins the second.
                    long highLeads = twoByteLeads(bytes, position + TWO_BYTE_CHUNK_BYTES, (leads >>> 24) & 0x80);
                    if (highLeads != INVALID_CHUNK) {
                        leads |= highLeads << TWO_BYTE_CHUNK_BYTES;
                        blockBytes = 2 * TWO_BYTE_CHUNK_BYTES;
                    }
                }
                long memberBits = twoByteMembers(bytes, position, blockBytes, members) & ~leads;
                long nonmemberBits = ~(memberBits | leads) & (-1L >>> (Long.SIZE - blockBytes));
                long runs = countRuns(memberBits, nonmemberBits, runLength, minimum, maximum);
                count += (int) (runs >>> 32);
                runLength = (int) runs;
                // A lead in the last byte waits for the next block, which then begins at a character.
                position += blockBytes - (int) (leads >>> (blockBytes - 1));
                stretch = TWO_BYTE_CHUNK_BYTES;
                continue;
            }
            if (stretch > MAX_DECODE_STRETCH_BYTES) {
                return count + countDecoded(bytes, position, end, runLength);
            }
            // Written so that a slice ending near the largest array index cannot overflow.
            int stretchEnd = position + Math.min(end - position, stretch);
            stretch *= 2;
            while (position < stretchEnd) {
                long decoded = decodeUtf8(bytes, position, end);
                if (matches(Utf8.decodedCodePoint(decoded))) {
                    runLength++;
                }
                else {
                    count += countRun(runLength);
                    runLength = 0;
                }
                position += Utf8.decodedWidth(decoded);
            }
        }
        return count + countRun(runLength);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: Returns the matches from `position` on, after `runLength`
    // members, decoding character by character. `countUtf8` hands the rest of the input here after
    // its longest stretch, because Intel decodes long text slower inside the block loop than in a
    // loop of its own. Changes require focused Intel and Graviton qualification.
    private long countDecoded(byte[] bytes, int position, int end, int runLength)
    {
        long count = 0;
        while (position < end) {
            long decoded = decodeUtf8(bytes, position, end);
            int codePoint = Utf8.decodedCodePoint(decoded);
            int width = Utf8.decodedWidth(decoded);
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

    // Returns the matches in a decoded run of `runLength` members, as `runMatches` does, but with a
    // branch on the minimum. Intel decoded text slower with `runMatches`.
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

    // Returns the bits of the `blockBytes` bytes at `position`, bit `i` for byte `i`, that end a
    // member: an ASCII member, or a continuation byte ending a two-byte member. The block holds
    // only such characters and begins one, as `twoByteLeads` checks. A lead byte's bit is
    // meaningless; its code point is below `TWO_BYTE_LIMIT`, within the bitset. Each byte's lookup
    // depends only on the byte before it, not on that byte's lookup, so the lookups overlap.
    private static long twoByteMembers(byte[] bytes, int position, int blockBytes, long[] members)
    {
        long memberBits = 0;
        // The low five bits of the byte before, a lead byte for a continuation, shifted into place.
        int leadBits = 0;
        for (int bit = 0; bit < blockBytes; bit++) {
            int value = bytes[position + bit];
            // Bit 6 of a continuation byte is clear, so its low seven bits are its low six, and an
            // ASCII byte, whose sign bit is clear, takes no lead bits.
            int codePoint = (value & 0x7F) | (leadBits & (value >> 7));
            leadBits = (value & 0x1F) << 6;
            memberBits |= ((members[codePoint >>> 6] >>> codePoint) & 1) << bit;
        }
        return memberBits;
    }

    // Accounts for the runs of a block, given the bits of its member and nonmember characters, set
    // on the byte that ends each; a nonmember ends a run. `runLength` members precede the block.
    // Returns the matches of the runs that end in the block, shifted left 32 bits, combined with
    // the members of the run still open at its end.
    private static long countRuns(long memberBits, long nonmemberBits, int runLength, int minimum, int maximum)
    {
        int count = 0;
        while (nonmemberBits != 0) {
            long before = Long.lowestOneBit(nonmemberBits) - 1;
            count += runMatches(runLength + Long.bitCount(memberBits & before), minimum, maximum);
            memberBits &= ~before;
            runLength = 0;
            nonmemberBits &= nonmemberBits - 1;
        }
        return ((long) count << 32) | (runLength + Long.bitCount(memberBits));
    }

    // Returns the matches in a run of `runLength` members: one for each `maximum` members, and
    // one for a remainder of at least `minimum`.
    private static int runMatches(int runLength, int minimum, int maximum)
    {
        // Most runs hold one match at most, so only a longer one pays the division. A run ends at
        // every word boundary of ordinary text, too irregularly for a branch on the minimum to
        // predict.
        if (runLength <= maximum) {
            return (minimum - 1 - runLength) >>> 31;
        }
        int fullMatches = runLength / maximum;
        return fullMatches + ((minimum - 1 - (runLength - fullMatches * maximum)) >>> 31);
    }

    // Returns the lead bytes among the 32 bytes at `position`, bit `i` for byte `i`, or
    // `INVALID_CHUNK` if they hold anything other than ASCII bytes and two-byte characters of a
    // lead byte C2 through DF and one continuation byte. `carriedLead` is 0x80 when the byte
    // before `position` is a lead whose continuation begins them, and 0 when `position` begins a
    // character. The last byte can be a lead; the caller checks its continuation with the bytes
    // that follow.
    private static long twoByteLeads(byte[] bytes, int position, long carriedLead)
    {
        long invalid = 0;
        long leadBits = 0;
        for (int offset = 0; offset < TWO_BYTE_CHUNK_BYTES; offset += Long.BYTES) {
            long word = (long) LONG_HANDLE.get(bytes, position + offset);
            // Bit 7 of each byte of `word << 1` is bit 6 of that byte, and of `word << 2` bit 5.
            long shifted = word << 1;
            long leads = word & shifted & HIGH_BITS;
            long continuations = word & ~shifted & HIGH_BITS;
            // E0 and above begin longer characters or are never valid.
            invalid |= leads & (word << 2);
            // C0 and C1 begin only overlong encodings: a zero byte after clearing bit 0 and XOR
            // with C0.
            long overlong = (word & ~LOW_BITS) ^ 0xC0C0C0C0C0C0C0C0L;
            invalid |= (overlong - LOW_BITS) & ~overlong & HIGH_BITS;
            // Every continuation byte, and only a continuation byte, follows a lead byte.
            invalid |= continuations ^ ((leads << 8) | carriedLead);
            carriedLead = leads >>> 56;
            // Gathers bit 7 of each byte into the low byte: each step doubles the bits gathered.
            // Shifts and ORs, rather than a multiply, need no multiplier constant, which aarch64
            // builds with up to four instructions and C2 can rebuild on every word, as it did
            // in LiteralSearchKernel.
            long gathered = leads >>> 7;
            gathered |= gathered >>> 7;
            gathered |= gathered >>> 14;
            gathered |= gathered >>> 28;
            leadBits |= (gathered & 0xFF) << offset;
        }
        return invalid == 0 ? leadBits : INVALID_CHUNK;
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

    // PERFORMANCE-SENSITIVE HOT LOOP: the start-byte skip, the start-byte run read, and inline
    // decoding replace one DFA restart per match. Changes require focused Intel and Graviton
    // qualification.
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
            if (readsBytes) {
                // Each member is one byte and the start bytes are exactly the members, so the run
                // is read on the start-byte table, one load per byte, without decoding.
                int runEnd = maximum == -1 ? end : (int) Math.min(end, (long) runStart + maximum);
                position++;
                while (position < runEnd && memberStartBytes[bytes[position] & 0xFF]) {
                    position++;
                }
                if (position - runStart >= minimum) {
                    return packSpan(inputOffset, contextStart, runStart, position);
                }
                position++;
                continue;
            }
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
                if (runLength == decidedRunLength) {
                    if (maximum == -1) {
                        position = skipCharacters(bytes, position, end);
                    }
                    return packSpan(inputOffset, contextStart, runStart, position);
                }
            }
            if (runLength >= minimum) {
                return packSpan(inputOffset, contextStart, runStart, position);
            }
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    /// Returns whether a member begins at `start` within the region, without checking the run's
    /// minimum. If not, [#findSpan] from `start` reports no span beginning there.
    boolean beginsRun(Slice input, int contextStart, int contextEnd, int start)
    {
        if (contextStart < 0 || contextEnd > input.length() || start < contextStart || start >= contextEnd) {
            return false;
        }
        byte[] bytes = input.byteArray();
        int position = input.byteArrayOffset() + start;
        return memberStartBytes[bytes[position] & 0xFF] && matches(Utf8.decodedCodePoint(decode(bytes, position, input.byteArrayOffset() + contextEnd)));
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

    // Returns the end of the characters from `position` on, read as `decodeUtf8` reads them for a
    // class holding every non-ASCII character: an ASCII byte, or a lead byte and its continuation
    // bytes, valid or not. Only a byte that begins no such character ends them.
    private static int skipCharacters(byte[] bytes, int position, int end)
    {
        while (position < end) {
            int width = bytes[position] >= 0 ? 1 : Utf8.re2CharacterWidth(bytes, position, end);
            if (width == 0) {
                return position;
            }
            position += width;
        }
        return position;
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
        // The bitset alone decides a member; only a code point it rejects reads the flag.
        return (codePoint >= 0 && codePoint < matchingCodePoints.length * Long.SIZE && (matchingCodePoints[codePoint >>> 6] & (1L << codePoint)) != 0) || (matchesAllNonAscii && codePoint > 0x7F);
    }
}
