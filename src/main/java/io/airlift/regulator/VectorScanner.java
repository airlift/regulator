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

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/// Vector scans for [ScanPlan] and [ScanPlanRun]. Callers must check
/// [VectorSupport#isAvailable()] first and use their scalar loops otherwise.
final class VectorScanner
{
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_128;
    // Loaded at offsets 2, 1, and 0, the lanes of a block that a byte one, two, or three bytes
    // back can affect.
    private static final byte[] FIRST_BLOCK_LANES = {0, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
    // Subtracted from the input's last block, as unsigned bytes that saturate at 0: the high bit
    // stays exactly in a lead byte of at least 0xF0, 0xE0, or 0xC0 in the block's last three
    // lanes, a character the end of the input cuts short.
    private static final byte[] LAST_BLOCK_LEADS = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x70, 0x60, 0x40};

    private VectorScanner() {}

    // PERFORMANCE-SENSITIVE HOT LOOPS: changes here require target-host assembly
    // and benchmark evidence. Keep Vector API linkage isolated in this class. The
    // run scans broadcast their needles before the block loop because C2 does not
    // hoist per-block broadcasts out of it on aarch64 or x86.

    /// Returns the first byte at or after `cursor` outside `members`, or `end`
    /// when none remains. `needles` holds one to four bytes: the members, or the
    /// non-members when `inverted`.
    /// Copied as `VectorScanKernelSupport.findRunStop`; keep the copy in step. The SVE form is
    /// [#findRunStopSve]; keep the two in step.
    static int findRunStop(byte[] input, int cursor, int end, byte[] needles, boolean inverted, boolean[] members)
    {
        int count = needles.length;
        ByteVector first = ByteVector.broadcast(SPECIES, needles[0]);
        ByteVector second = ByteVector.broadcast(SPECIES, count > 1 ? needles[1] : needles[0]);
        ByteVector third = ByteVector.broadcast(SPECIES, count > 2 ? needles[2] : needles[0]);
        ByteVector fourth = ByteVector.broadcast(SPECIES, count > 3 ? needles[3] : needles[0]);
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            ByteVector bytes = ByteVector.fromArray(SPECIES, input, cursor);
            VectorMask<Byte> matches = bytes.compare(VectorOperators.EQ, first);
            if (count > 1) {
                matches = matches.or(bytes.compare(VectorOperators.EQ, second));
            }
            if (count > 2) {
                matches = matches.or(bytes.compare(VectorOperators.EQ, third));
            }
            if (count > 3) {
                matches = matches.or(bytes.compare(VectorOperators.EQ, fourth));
            }
            VectorMask<Byte> stops = inverted ? matches : matches.not();
            if (stops.anyTrue()) {
                return cursor + stops.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            if (!members[input[cursor] & 0xFF]) {
                return cursor;
            }
        }
        return end;
    }

    /// [#findRunStop] in the form [VectorSupport#SVE_128] selects: XOR with each needle, UMIN,
    /// and one compare with zero. Callers choose it, which keeps `findRunStop` within C2's
    /// inlining limit. Copied as `VectorScanKernelSupport.findRunStopSve`; keep the copy in step.
    static int findRunStopSve(byte[] input, int cursor, int end, byte[] needles, boolean inverted, boolean[] members)
    {
        int count = needles.length;
        ByteVector first = ByteVector.broadcast(SPECIES, needles[0]);
        ByteVector second = ByteVector.broadcast(SPECIES, count > 1 ? needles[1] : needles[0]);
        ByteVector third = ByteVector.broadcast(SPECIES, count > 2 ? needles[2] : needles[0]);
        ByteVector fourth = ByteVector.broadcast(SPECIES, count > 3 ? needles[3] : needles[0]);
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            ByteVector bytes = ByteVector.fromArray(SPECIES, input, cursor);
            ByteVector difference = bytes.lanewise(VectorOperators.XOR, first);
            if (count > 1) {
                difference = difference.lanewise(VectorOperators.UMIN, bytes.lanewise(VectorOperators.XOR, second));
            }
            if (count > 2) {
                difference = difference.lanewise(VectorOperators.UMIN, bytes.lanewise(VectorOperators.XOR, third));
            }
            if (count > 3) {
                difference = difference.lanewise(VectorOperators.UMIN, bytes.lanewise(VectorOperators.XOR, fourth));
            }
            VectorMask<Byte> matches = difference.compare(VectorOperators.EQ, (byte) 0);
            VectorMask<Byte> stops = inverted ? matches : matches.not();
            if (stops.anyTrue()) {
                return cursor + stops.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            if (!members[input[cursor] & 0xFF]) {
                return cursor;
            }
        }
        return end;
    }

    /// Returns the first byte at or after `cursor` that is non-ASCII or equals one of
    /// `needles`, or `end` when none remains. `needles` holds the RE2 run's
    /// ASCII non-members, zero to four bytes. RE2 runs validate the non-ASCII bytes this stops at.
    /// Copied as `VectorScanKernelSupport.findRe2RunStop`; keep the copy in step.
    static int findRe2RunStop(byte[] input, int cursor, int end, byte[] needles)
    {
        if (needles.length > 1) {
            return findRe2SetStop(input, cursor, end, needles);
        }
        return findRe2ByteStop(input, cursor, end, needles.length > 0 ? needles[0] : -1);
    }

    /// [#findRe2RunStop] for at most one needle: returns the first byte at or after `cursor` that
    /// is non-ASCII or equals `stopByte`, an ASCII byte or `-1` for none, or `end` when none
    /// remains. Copied as `VectorScanKernelSupport.findRe2ByteStop`; keep the copy in step.
    static int findRe2ByteStop(byte[] input, int cursor, int end, int stopByte)
    {
        // Most runs stop at one ASCII byte, such as a host's '/', and their scans often end in the
        // first block. Keep this path small: callers inline it, and a larger body pushed them past
        // C2's inlining limits. The needle is ASCII, so a byte XORed with the needle is zero where
        // the byte equals it and negative where the byte is non-ASCII: one signed compare with 1
        // finds both. With no excluded ASCII byte, XOR with zero and compare with 0, stopping at
        // non-ASCII bytes only.
        ByteVector needle = ByteVector.broadcast(SPECIES, (byte) Math.max(stopByte, 0));
        ByteVector limit = ByteVector.broadcast(SPECIES, (byte) (stopByte >= 0 ? 1 : 0));
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            VectorMask<Byte> stops = ByteVector.fromArray(SPECIES, input, cursor).lanewise(VectorOperators.XOR, needle).compare(VectorOperators.LT, limit);
            if (stops.anyTrue()) {
                return cursor + stops.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            byte value = input[cursor];
            if (value < 0 || value == stopByte) {
                return cursor;
            }
        }
        return end;
    }

    /// For an RE2 run of every non-ASCII character and every ASCII byte except `stopByte`, an
    /// ASCII byte or `-1` for none, that starts at `cursor`, a character boundary: passes whole
    /// blocks of characters the run reads, and returns the character boundary at or before the
    /// first byte where the run may stop, from which the caller reads by character to the exact
    /// stop. It is out of line, called once per stretch of non-ASCII text that the inlined
    /// [#findRe2ByteStop] stops at.
    ///
    /// The run reads characters as [Utf8#re2CharacterWidth] does, so a block passes when it holds
    /// no `stopByte`, no C0, C1, or F5-FF, and a continuation byte exactly where a lead byte one,
    /// two, or three bytes before it expects one. This is the structural part of the vector UTF-8
    /// check of Keiser and Lemire, narrowed to RE2's lenient reading: it does not reject overlong
    /// three- and four-byte forms, encoded surrogates, or values above U+10FFFF. The loads one to
    /// three bytes back carry each lead byte's expectations across blocks; in the first block they
    /// ignore the bytes before `cursor`. A block of ASCII bytes other than `stopByte` passes on the
    /// compare of [#findRe2ByteStop], unless the block before it ended inside a character. With
    /// [VectorSupport#SVE_128], each check sets the high bit of a byte and one compare tests their
    /// OR, as "Candidate Masks on SVE" in `REGULATOR_DECISIONS.md` describes; elsewhere each check
    /// is a compare and their masks are ORed.
    /// Copied as `VectorScanKernelSupport.skipRe2Run`; keep the copy in step.
    static int skipRe2Run(byte[] input, int cursor, int end, int stopByte)
    {
        int start = cursor;
        if (start < 3) {
            return start;
        }
        ByteVector needle = ByteVector.broadcast(SPECIES, (byte) Math.max(stopByte, 0));
        ByteVector limit = ByteVector.broadcast(SPECIES, (byte) (stopByte >= 0 ? 1 : 0));
        // The SVE form's constants. Each subtraction saturates at 0 as unsigned bytes, and only
        // the high bit of its result counts:
        // - a byte one, two, or three bytes after a lead byte of at least 0xC0, 0xE0, or 0xF0
        //   must be a continuation byte, and `previous - 0x40`, `secondPrevious - 0x60`, or
        //   `thirdPrevious - 0x70` keeps the high bit exactly for such a lead byte;
        // - `shifted = byte + 0x40` is 0xC0-0xFF for a continuation byte, so `shifted - 0x40`
        //   keeps the high bit exactly for one, and XOR with the expectation flags a mismatch;
        // - `shifted` is 0 or 1 for C0 or C1, so `0x81 - shifted` has the high bit for those;
        // - `byte - 0x75` has the high bit for F5-FF;
        // - `0x80 - (byte XOR stopByte)` has the high bit for the stop byte, and with no stop
        //   byte the minuend is 0.
        ByteVector afterLead = ByteVector.broadcast(SPECIES, (byte) 0x40);
        ByteVector afterThreeByteLead = ByteVector.broadcast(SPECIES, (byte) 0x60);
        ByteVector afterFourByteLead = ByteVector.broadcast(SPECIES, (byte) 0x70);
        ByteVector overlongLead = ByteVector.broadcast(SPECIES, (byte) 0x81);
        ByteVector invalidLead = ByteVector.broadcast(SPECIES, (byte) 0x75);
        ByteVector needleBit = ByteVector.broadcast(SPECIES, (byte) (stopByte >= 0 ? 0x80 : 0));
        // Set after a block with non-ASCII bytes, whose last character may continue into the next.
        boolean pending = false;
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            ByteVector bytes = ByteVector.fromArray(SPECIES, input, cursor);
            boolean hasNonAsciiOrStop = bytes.lanewise(VectorOperators.XOR, needle).compare(VectorOperators.LT, limit).anyTrue();
            if (!hasNonAsciiOrStop && !pending) {
                continue;
            }
            ByteVector previous = ByteVector.fromArray(SPECIES, input, cursor - 1);
            ByteVector secondPrevious = ByteVector.fromArray(SPECIES, input, cursor - 2);
            ByteVector thirdPrevious = ByteVector.fromArray(SPECIES, input, cursor - 3);
            if (cursor == start) {
                previous = previous.and(ByteVector.fromArray(SPECIES, FIRST_BLOCK_LANES, 2));
                secondPrevious = secondPrevious.and(ByteVector.fromArray(SPECIES, FIRST_BLOCK_LANES, 1));
                thirdPrevious = thirdPrevious.and(ByteVector.fromArray(SPECIES, FIRST_BLOCK_LANES, 0));
            }
            VectorMask<Byte> stops;
            if (VectorSupport.SVE_128) {
                ByteVector expected = previous.lanewise(VectorOperators.SUSUB, afterLead)
                        .lanewise(VectorOperators.OR, secondPrevious.lanewise(VectorOperators.SUSUB, afterThreeByteLead))
                        .lanewise(VectorOperators.OR, thirdPrevious.lanewise(VectorOperators.SUSUB, afterFourByteLead));
                ByteVector shifted = bytes.add(afterLead);
                stops = shifted.lanewise(VectorOperators.SUSUB, afterLead).lanewise(VectorOperators.XOR, expected)
                        .lanewise(VectorOperators.OR, overlongLead.lanewise(VectorOperators.SUSUB, shifted))
                        .lanewise(VectorOperators.OR, bytes.lanewise(VectorOperators.SUSUB, invalidLead))
                        .lanewise(VectorOperators.OR, needleBit.lanewise(VectorOperators.SUSUB, bytes.lanewise(VectorOperators.XOR, needle)))
                        .compare(VectorOperators.LT, (byte) 0);
            }
            else {
                VectorMask<Byte> expected = previous.compare(VectorOperators.UGE, (byte) 0xC0)
                        .or(secondPrevious.compare(VectorOperators.UGE, (byte) 0xE0))
                        .or(thirdPrevious.compare(VectorOperators.UGE, (byte) 0xF0));
                stops = bytes.compare(VectorOperators.LT, (byte) 0xC0).xor(expected)
                        .or(bytes.lanewise(VectorOperators.AND, (byte) 0xFE).compare(VectorOperators.EQ, (byte) 0xC0))
                        .or(bytes.compare(VectorOperators.UGE, (byte) 0xF5))
                        .or(bytes.lanewise(VectorOperators.XOR, needle).compare(VectorOperators.ULT, limit));
            }
            if (stops.anyTrue()) {
                return characterStart(input, start, cursor + stops.firstTrue());
            }
            pending = hasNonAsciiOrStop;
        }
        return characterStart(input, start, cursor);
    }

    /// Finishes the byte stop scan from `cursor` of an RE2 run that [#skipRe2Run] reads, which
    /// stopped at `stop`. A stop at `end` or at the stop byte is where the run stops, and returns
    /// `stop`. At a non-ASCII byte, this checks the input's last block, the 16 bytes before `end`:
    /// the input is eligible when `cursor <= end - 16 < stop < end` and `end >= 19`, so the three
    /// bytes before the block can be read. Eligible input returns where the run stops, the block's
    /// first `stopByte` or `end`, when the run reads every character before it, and `-1` when the
    /// block holds a sequence the run may reject before that, where the caller walks by character.
    /// Ineligible input returns `-1`. A short run with a few non-ASCII characters near the end,
    /// such as a quoted field's, takes this one check rather than the walk by character.
    ///
    /// A lane is rejected on the checks of [#skipRe2Run] other than the stop byte, and where a lead
    /// byte in the last three lanes starts a character the end cuts short; the run stops at the
    /// first stop byte when no lane up to it is rejected. Unlike skipRe2Run's first block, the
    /// lanes the bytes before the block reach are not cleared: a lead byte there that expects a
    /// continuation byte in the block expects one in its first lane, which holds ASCII text before
    /// `stop`, so that lane is rejected and the walk decides; the check never passes a character
    /// the run rejects.
    /// Copied as `VectorScanKernelSupport.findRe2LastBlockStop`; keep the copy in step.
    static int findRe2LastBlockStop(byte[] input, int cursor, int stop, int end, int stopByte)
    {
        if (stop == end || input[stop] >= 0) {
            return stop;
        }
        int block = end - SPECIES.length();
        if (stop <= block || cursor > block || block < 3) {
            return -1;
        }
        // skipRe2Run's SVE form on every platform, which keeps this within C2's inlining limit,
        // with the stop byte apart and the end's lead bytes added.
        ByteVector bytes = ByteVector.fromArray(SPECIES, input, block);
        ByteVector expected = ByteVector.fromArray(SPECIES, input, block - 1).lanewise(VectorOperators.SUSUB, (byte) 0x40)
                .lanewise(VectorOperators.OR, ByteVector.fromArray(SPECIES, input, block - 2).lanewise(VectorOperators.SUSUB, (byte) 0x60))
                .lanewise(VectorOperators.OR, ByteVector.fromArray(SPECIES, input, block - 3).lanewise(VectorOperators.SUSUB, (byte) 0x70));
        ByteVector shifted = bytes.lanewise(VectorOperators.ADD, (byte) 0x40);
        VectorMask<Byte> rejected = shifted.lanewise(VectorOperators.SUSUB, (byte) 0x40).lanewise(VectorOperators.XOR, expected)
                .lanewise(VectorOperators.OR, ByteVector.broadcast(SPECIES, (byte) 0x81).lanewise(VectorOperators.SUSUB, shifted))
                .lanewise(VectorOperators.OR, bytes.lanewise(VectorOperators.SUSUB, (byte) 0x75))
                .lanewise(VectorOperators.OR, bytes.lanewise(VectorOperators.SUSUB, ByteVector.fromArray(SPECIES, LAST_BLOCK_LEADS, 0)))
                .compare(VectorOperators.LT, (byte) 0);
        VectorMask<Byte> stops = ByteVector.broadcast(SPECIES, (byte) (stopByte >= 0 ? 0x80 : 0))
                .lanewise(VectorOperators.SUSUB, bytes.lanewise(VectorOperators.XOR, (byte) Math.max(stopByte, 0)))
                .compare(VectorOperators.LT, (byte) 0);
        int rejectedLane = rejected.firstTrue();
        int stopLane = stops.firstTrue();
        if (stopLane < rejectedLane) {
            return block + stopLane;
        }
        return rejectedLane == SPECIES.length() ? end : -1;
    }

    // Returns `position`, or the lead byte of a character that starts in the three bytes before it,
    // at or after `start`, and continues past it. Copied as VectorScanKernelSupport.characterStart;
    // keep the copy in step.
    private static int characterStart(byte[] input, int start, int position)
    {
        for (int back = 1; back <= 3 && position - back >= start; back++) {
            int value = input[position - back] & 0xFF;
            if (value < 0x80) {
                return position;
            }
            if (value >= 0xC0) {
                int width = value < 0xE0 ? 2 : value < 0xF0 ? 3 : 4;
                return back < width ? position - back : position;
            }
        }
        return position;
    }

    // findRe2RunStop for two to four needles. Copied as VectorScanKernelSupport.findRe2SetStop;
    // keep the copy in step.
    private static int findRe2SetStop(byte[] input, int cursor, int end, byte[] needles)
    {
        int count = needles.length;
        ByteVector first = ByteVector.broadcast(SPECIES, needles[0]);
        ByteVector second = ByteVector.broadcast(SPECIES, needles[1]);
        ByteVector third = ByteVector.broadcast(SPECIES, count > 2 ? needles[2] : 0);
        ByteVector fourth = ByteVector.broadcast(SPECIES, count > 3 ? needles[3] : 0);
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            ByteVector bytes = ByteVector.fromArray(SPECIES, input, cursor);
            // As in findRe2RunStop: each needle is ASCII, so the smallest XOR difference is zero at
            // a needle and has its high bit set at a non-ASCII byte, so a stop is a lane below 1 as
            // a signed byte.
            ByteVector difference = bytes.lanewise(VectorOperators.XOR, first).lanewise(VectorOperators.UMIN, bytes.lanewise(VectorOperators.XOR, second));
            if (count > 2) {
                difference = difference.lanewise(VectorOperators.UMIN, bytes.lanewise(VectorOperators.XOR, third));
            }
            if (count > 3) {
                difference = difference.lanewise(VectorOperators.UMIN, bytes.lanewise(VectorOperators.XOR, fourth));
            }
            VectorMask<Byte> stops = difference.compare(VectorOperators.LT, (byte) 1);
            if (stops.anyTrue()) {
                return cursor + stops.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            byte value = input[cursor];
            if (value < 0) {
                return cursor;
            }
            for (byte needle : needles) {
                if (value == needle) {
                    return cursor;
                }
            }
        }
        return end;
    }

    /// Returns the first byte at or after `cursor` in `members`, or `-1` when
    /// none remains. `needles` is as for [#findRunStop].
    /// Copied as `VectorScanKernelSupport.findRunCandidate`; keep the copy in step.
    static int findRunCandidate(byte[] input, int cursor, int end, byte[] needles, boolean inverted, boolean[] members)
    {
        int count = needles.length;
        ByteVector first = ByteVector.broadcast(SPECIES, needles[0]);
        ByteVector second = ByteVector.broadcast(SPECIES, count > 1 ? needles[1] : needles[0]);
        ByteVector third = ByteVector.broadcast(SPECIES, count > 2 ? needles[2] : needles[0]);
        ByteVector fourth = ByteVector.broadcast(SPECIES, count > 3 ? needles[3] : needles[0]);
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            ByteVector bytes = ByteVector.fromArray(SPECIES, input, cursor);
            VectorMask<Byte> matches = bytes.compare(VectorOperators.EQ, first);
            if (count > 1) {
                matches = matches.or(bytes.compare(VectorOperators.EQ, second));
            }
            if (count > 2) {
                matches = matches.or(bytes.compare(VectorOperators.EQ, third));
            }
            if (count > 3) {
                matches = matches.or(bytes.compare(VectorOperators.EQ, fourth));
            }
            VectorMask<Byte> candidates = inverted ? matches.not() : matches;
            if (candidates.anyTrue()) {
                return cursor + candidates.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            if (members[input[cursor] & 0xFF]) {
                return cursor;
            }
        }
        return -1;
    }

    /// Returns whether the `literal` step occurs in `[cursor, end)`.
    /// Copied as `VectorScanKernelSupport.containsLiteral`; keep the copy in step.
    static boolean containsLiteral(byte[] bytes, int cursor, int end, ScanPlan.Step literal)
    {
        int lastOffset = literal.literal.length - 1;
        int lastStart = end - literal.literal.length;
        // Filter on the first and last literal bytes together so a frequent first byte does
        // not restart the scan at each occurrence.
        ByteVector first = ByteVector.broadcast(SPECIES, literal.literal[0]);
        ByteVector last = ByteVector.broadcast(SPECIES, literal.literal[lastOffset]);
        for (; cursor <= lastStart - SPECIES.length() + 1; cursor += SPECIES.length()) {
            ByteVector front = ByteVector.fromArray(SPECIES, bytes, cursor);
            ByteVector back = ByteVector.fromArray(SPECIES, bytes, cursor + lastOffset);
            VectorMask<Byte> matches = VectorSupport.SVE_128
                    ? VectorPrefixScanner.sveMatches(front, first, back, last)
                    : front.compare(VectorOperators.EQ, first).and(back.compare(VectorOperators.EQ, last));
            long bits = matches.toLong();
            while (bits != 0) {
                if (literal.matches(bytes, cursor + Long.numberOfTrailingZeros(bits), end)) {
                    return true;
                }
                bits &= bits - 1;
            }
        }
        for (; cursor <= lastStart; cursor++) {
            if (literal.matches(bytes, cursor, end)) {
                return true;
            }
        }
        return false;
    }

    // Copied as VectorScanKernelSupport.findByte; keep the copy in step.
    static int findByte(byte[] bytes, int cursor, int end, byte target)
    {
        ByteVector wanted = ByteVector.broadcast(SPECIES, target);
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, bytes, cursor).compare(VectorOperators.EQ, wanted);
            if (matches.anyTrue()) {
                return cursor + matches.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            if (bytes[cursor] == target) {
                return cursor;
            }
        }
        return -1;
    }

}
