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
import java.util.HashMap;
import java.util.Map;

/// A greedy character-set run whose continuation needs no repetition backtracking.
///
/// Its member bytes are the ASCII characters it holds, plus every byte above 0x7F when it holds
/// every non-ASCII character. A run of an RE2, Java, or Rust plan may also hold some non-ASCII
/// characters but not all, which it reads by the exact encodings the compiler gives them (see
/// [Utf8MemberTable]); its member bytes above 0x7F are then the lead bytes of those
/// encodings alone.
final class ScanPlanRun
{
    static final int MATCHES_EVERY_BYTE = 256;

    private static final int CANDIDATE_SCAN_TABLE = 0;
    static final int CANDIDATE_SCAN_SMALL_SET = 1;
    static final int CANDIDATE_SCAN_RANGE = 2;
    static final int CANDIDATE_SCAN_NIBBLE_TABLE = 3;
    private static final int MAX_VECTOR_CANDIDATE_BYTES = 64;
    static final int SCALAR_CANDIDATE_PREFIX_BYTES = 16;
    static final int RE2_RUN_STOP_MINIMUM_BYTES = 16;

    private static final int INSTANCE_SIZE = SizeOf.instanceSize(ScanPlanRun.class);
    private static final int UNSUPPORTED_ATOM_FLAGS = Regexp.LATIN1;
    private static final int UNSUPPORTED_FLAGS = UNSUPPORTED_ATOM_FLAGS | Regexp.NON_GREEDY;

    private final boolean[] members;
    private final long members0;
    private final long members1;
    private final long members2;
    private final long members3;
    private final byte[] needles;
    private final boolean inverted;
    // Candidate scans reuse the DFA's start-byte scanners for sets of at most 64 bytes.
    private final int candidateScan;
    private final int lowestMember;
    private final int highestMember;
    private final byte[] candidateNibbleTables;
    // Set when the run holds every non-ASCII character, so every byte above 0x7F is a member.
    private final boolean matchesNonAscii;
    // The encodings of the run's non-ASCII members when it holds some but not all, or null.
    // Only the set-run match methods, which RE2_SET_RUN operations call, may match such a run.
    private final Utf8MemberTable nonAsciiMembers;
    // For a run with nonAsciiMembers, the ASCII bytes that are not members of the run when at
    // most four, for the vector stop scan; otherwise null.
    private final byte[] asciiStopNeedles;
    private final int minimum;
    private final int maximum;

    private ScanPlanRun(boolean[] members, boolean matchesNonAscii, Utf8MemberTable nonAsciiMembers, int minimum, int maximum)
    {
        this.members = members;
        this.members0 = word(members, 0);
        this.members1 = word(members, 64);
        this.members2 = word(members, 128);
        this.members3 = word(members, 192);
        this.matchesNonAscii = matchesNonAscii;
        this.nonAsciiMembers = nonAsciiMembers;
        this.asciiStopNeedles = nonAsciiMembers == null ? null : asciiStopNeedles(members);
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
        int lowest = -1;
        int highest = -1;
        for (int value = 0; value < members.length; value++) {
            if (members[value]) {
                if (lowest < 0) {
                    lowest = value;
                }
                highest = value;
            }
        }
        byte[] nibbleTables = null;
        int scan = CANDIDATE_SCAN_TABLE;
        if (count >= 1 && count <= 3) {
            scan = CANDIDATE_SCAN_SMALL_SET;
        }
        else if (count >= 4 && count <= MAX_VECTOR_CANDIDATE_BYTES) {
            if (highest - lowest + 1 == count) {
                scan = CANDIDATE_SCAN_RANGE;
            }
            else if (VectorSupport.isAvailable()) {
                byte[] candidates = new byte[members.length];
                for (int value = 0; value < members.length; value++) {
                    candidates[value] = (byte) (members[value] ? 1 : 0);
                }
                nibbleTables = VectorByteSetScanner.buildNibbleTables(candidates);
                scan = nibbleTables == null ? CANDIDATE_SCAN_TABLE : CANDIDATE_SCAN_NIBBLE_TABLE;
            }
        }
        this.candidateScan = VectorSupport.isAvailable() ? scan : CANDIDATE_SCAN_TABLE;
        this.lowestMember = lowest;
        this.highestMember = highest;
        this.candidateNibbleTables = candidateScan == CANDIDATE_SCAN_NIBBLE_TABLE ? nibbleTables : null;
    }

    private static byte[] asciiStopNeedles(boolean[] members)
    {
        int count = 0;
        for (int value = 0; value < 128; value++) {
            count += members[value] ? 0 : 1;
        }
        if (count > 4) {
            return null;
        }
        byte[] needles = new byte[count];
        int index = 0;
        for (int value = 0; value < 128; value++) {
            if (!members[value]) {
                needles[index++] = (byte) value;
            }
        }
        return needles;
    }

    /// Returns the run of `expression` as [#analyze(Regexp,CaseFolding,boolean,Map)]
    /// does, with a member table of its own.
    static ScanPlanRun analyze(Regexp expression, CaseFolding caseFolding, boolean re2Characters)
    {
        return analyze(expression, caseFolding, re2Characters, new HashMap<>());
    }

    /// Returns the run of `expression`, or `null` when it is not a supported run.
    /// With `re2Characters`, a class that holds some non-ASCII characters but not all can
    /// be a run; otherwise it cannot, since a Trino run reads every non-ASCII byte alike. The
    /// member table of such a class comes from `memberTables`, or is built and added there
    /// when it is absent, so the runs of one plan share one table for each set.
    static ScanPlanRun analyze(Regexp expression, CaseFolding caseFolding, boolean re2Characters, Map<CharClass, Utf8MemberTable> memberTables)
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
        Utf8MemberTable nonAsciiMembers = null;
        switch (atom.op()) {
            case LITERAL -> {
                if (atom.rune() >= 128) {
                    return null;
                }
                if ((atom.parseFlags() & Regexp.FOLD_CASE) != 0) {
                    if (!Regexp.foldsAsAsciiPairs(atom.parseFlags(), new int[] {atom.rune()}, caseFolding)) {
                        return null;
                    }
                    int rune = atom.rune();
                    members[rune] = true;
                    if (('A' <= rune && rune <= 'Z') || ('a' <= rune && rune <= 'z')) {
                        members[rune ^ 0x20] = true;
                    }
                }
                else {
                    if ((atom.parseFlags() & Regexp.FULL_CASE_FOLD) != 0) {
                        return null;
                    }
                    members[atom.rune()] = true;
                }
            }
            case ANY_CHAR -> {
                Arrays.fill(members, true);
                matchesNonAscii = true;
            }
            case CHAR_CLASS -> {
                CharClass set = atom.charClass();
                boolean matchesSomeNonAscii = false;
                for (int index = 0; index < set.rangeCount(); index++) {
                    RuneRange range = set.range(index);
                    if (range.high() >= 128) {
                        if (Compiler.holdsAllNonAscii(range)) {
                            matchesNonAscii = true;
                        }
                        else {
                            matchesSomeNonAscii = true;
                        }
                    }
                }
                for (int value = 0; value < 128; value++) {
                    members[value] = set.contains(value);
                }
                if (matchesSomeNonAscii) {
                    // A Trino byte scan is valid only if all non-ASCII characters have the
                    // same membership, so other Unicode classes stay on its ordinary engine.
                    if (!re2Characters) {
                        return null;
                    }
                    nonAsciiMembers = memberTables.computeIfAbsent(set, Utf8MemberTable::of);
                    for (int value = 128; value < 256; value++) {
                        members[value] = nonAsciiMembers.isLead(value);
                    }
                }
                else {
                    Arrays.fill(members, 128, 256, matchesNonAscii);
                }
            }
            default -> {
                return null;
            }
        }
        return new ScanPlanRun(members, matchesNonAscii, nonAsciiMembers, minimum, maximum);
    }

    int unboundedScanTerminator()
    {
        // An inverted run with needles excludes at most four byte values, and a run of some
        // non-ASCII characters excludes all 64 continuation bytes, so it never meets this
        // condition.
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

    /// Returns whether the run holds some non-ASCII characters but not all.
    boolean matchesSomeNonAscii()
    {
        return nonAsciiMembers != null;
    }

    /// Returns whether the run holds `codePoint`, a valid Unicode scalar value.
    boolean matchesCharacter(int codePoint)
    {
        if (codePoint < 128) {
            return members[codePoint];
        }
        if (nonAsciiMembers == null) {
            return matchesNonAscii;
        }
        byte[] encoded = new byte[Utf8.encodedLength(codePoint)];
        int length = Utf8.encode(encoded, 0, codePoint);
        return nonAsciiMembers.memberWidth(encoded, 0, length) == length;
    }

    // These accessors serve the copies of match, matchMinimum, findCandidate, and the RE2 run
    // helpers in ScanKernelSupport.

    int maximum()
    {
        return maximum;
    }

    boolean[] members()
    {
        return members;
    }

    byte[] needles()
    {
        return needles;
    }

    boolean isInverted()
    {
        return inverted;
    }

    boolean matchesNonAscii()
    {
        return matchesNonAscii;
    }

    Utf8MemberTable nonAsciiMembers()
    {
        return nonAsciiMembers;
    }

    byte[] asciiStopNeedles()
    {
        return asciiStopNeedles;
    }

    int candidateScan()
    {
        return candidateScan;
    }

    int lowestMember()
    {
        return lowestMember;
    }

    int highestMember()
    {
        return highestMember;
    }

    byte[] candidateNibbleTables()
    {
        return candidateNibbleTables;
    }

    boolean isUnbounded()
    {
        return maximum < 0;
    }

    /// Returns whether every member byte is ASCII, so the run never consumes a byte of a
    /// multibyte or malformed sequence.
    boolean acceptsOnlyAscii()
    {
        return !matchesNonAscii && nonAsciiMembers == null;
    }

    /// Returns whether the run reads exactly one character and each of its member bytes starts
    /// a character: it holds only ASCII characters, or holds some non-ASCII characters but not all
    /// and so has only lead bytes as member bytes above 0x7F. A run of every non-ASCII character
    /// also holds the continuation bytes.
    boolean isSingleCharacterOfStartBytes()
    {
        return !matchesNonAscii && minimum == 1 && maximum == 1;
    }

    /// Returns whether every character of `other` is certainly a member: a sufficient
    /// check, decided by member bytes, except that it recognizes a run of some non-ASCII
    /// characters only inside a run of every non-ASCII character.
    boolean containsAll(ScanPlanRun other)
    {
        if (other.nonAsciiMembers != null && !matchesNonAscii) {
            return false;
        }
        return (other.members0 & ~members0) == 0 && (other.members1 & ~members1) == 0 &&
                (other.members2 & ~members2) == 0 && (other.members3 & ~members3) == 0;
    }

    /// Returns whether `value` is the only byte that is not a member.
    boolean isComplementOf(byte value)
    {
        return inverted && needles != null && needles.length == 1 && needles[0] == value;
    }

    /// Returns whether the run holds every ASCII byte except `value`, and holds some
    /// non-ASCII characters but not all.
    boolean isAsciiComplementOf(byte value)
    {
        return asciiStopNeedles != null && asciiStopNeedles.length == 1 && asciiStopNeedles[0] == value;
    }

    boolean isVariable()
    {
        return minimum != maximum;
    }

    // Excludes nonAsciiMembers, which runs of one plan can share, so the plan counts each table
    // once.
    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + SizeOf.sizeOf(members) + SizeOf.sizeOf(needles) + SizeOf.sizeOf(candidateNibbleTables) + SizeOf.sizeOf(asciiStopNeedles);
    }

    // Copied as ScanKernelSupport.runMatch; keep the copy in step.
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

    /// Returns the position after the run's minimum characters, or `-1` when they are absent.
    /// It fails exactly when [#match] fails but reads no further than the minimum.
    /// Copied as `ScanKernelSupport.runMatchMinimum`; keep the copy in step.
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

    /// The RE2 version of [#match], for a run that matches every non-ASCII character. It
    /// reads characters as RE2 does (see [Utf8#re2CharacterWidth]), so a rejected sequence
    /// ends the run like a non-member byte.
    /// Copied as `ScanKernelSupport.re2RunMatch`; keep the copy in step.
    int re2Match(byte[] input, int cursor, int end)
    {
        if (maximum < 0 && minimum <= 1) {
            int stop = re2Scan(input, cursor, end);
            return stop - cursor >= minimum ? stop : -1;
        }
        int count = 0;
        int limit = maximum < 0 ? minimum : maximum;
        int width;
        while (count < limit && (width = re2MemberWidth(input, cursor, end)) != 0) {
            cursor += width;
            count++;
        }
        if (count < minimum) {
            return -1;
        }
        return maximum < 0 ? re2Scan(input, cursor, end) : cursor;
    }

    /// The RE2 version of [#matchMinimum]: the position after the run's minimum
    /// characters as [#re2Match] reads them, or `-1` when they are absent.
    /// Copied as `ScanKernelSupport.re2RunMatchMinimum`; keep the copy in step.
    int re2MatchMinimum(byte[] input, int cursor, int end)
    {
        for (int count = 0; count < minimum; count++) {
            int width = re2MemberWidth(input, cursor, end);
            if (width == 0) {
                return -1;
            }
            cursor += width;
        }
        return cursor;
    }

    /// For a run of every character except the ASCII `delimiter`: returns the position
    /// where [#re2Match] stops when `delimiter` is there, or `-1` when the run
    /// fails or stops at a rejected sequence or the end.
    /// Copied as `ScanKernelSupport.re2RunMatchBefore`; keep the copy in step.
    int re2MatchBefore(byte[] input, int cursor, int end, byte delimiter)
    {
        int stop = re2Match(input, cursor, end);
        return stop >= 0 && stop < end && input[stop] == delimiter ? stop : -1;
    }

    // Copied as ScanKernelSupport.re2RunMemberWidth; keep the copy in step.
    private int re2MemberWidth(byte[] input, int cursor, int end)
    {
        if (cursor >= end) {
            return 0;
        }
        int value = input[cursor];
        if (value < 0) {
            return Utf8.re2CharacterWidth(input, cursor, end);
        }
        return members[value] ? 1 : 0;
    }

    // Copied as ScanKernelSupport.re2RunScan; keep the copy in step.
    private int re2Scan(byte[] input, int cursor, int end)
    {
        while (true) {
            // Find the first non-member ASCII byte or non-ASCII byte, then consume the
            // non-ASCII characters there. Less than a vector of input is scanned by byte, as
            // Trino's run scan does for its tail.
            if (needles != null && end - cursor >= RE2_RUN_STOP_MINIMUM_BYTES && VectorSupport.isAvailable()) {
                cursor = VectorScanner.findRe2RunStop(input, cursor, end, needles);
            }
            else if (needles != null && needles.length == 0) {
                // Every ASCII byte is a member.
                while (cursor < end && input[cursor] >= 0) {
                    cursor++;
                }
            }
            else {
                while (cursor < end && input[cursor] >= 0 && members[input[cursor]]) {
                    cursor++;
                }
            }
            int width;
            while (cursor < end && input[cursor] < 0 && (width = Utf8.re2CharacterWidth(input, cursor, end)) != 0) {
                cursor += width;
            }
            if (cursor >= end || input[cursor] < 0 || !members[input[cursor]]) {
                return cursor;
            }
        }
    }

    /// The version of [#re2Match] for a run of some non-ASCII characters but not all. It
    /// reads a non-ASCII character as the ordinary engine does, by the exact encodings of the
    /// run's members (see [Utf8MemberTable]), so any other sequence ends the run like a
    /// non-member byte.
    /// Copied as `ScanKernelSupport.re2SetRunMatch`; keep the copy in step.
    int re2SetMatch(byte[] input, int cursor, int end)
    {
        if (maximum < 0 && minimum <= 1) {
            int stop = re2SetScan(input, cursor, end);
            return stop - cursor >= minimum ? stop : -1;
        }
        int count = 0;
        int limit = maximum < 0 ? minimum : maximum;
        int width;
        while (count < limit && (width = re2SetMemberWidth(input, cursor, end)) != 0) {
            cursor += width;
            count++;
        }
        if (count < minimum) {
            return -1;
        }
        return maximum < 0 ? re2SetScan(input, cursor, end) : cursor;
    }

    /// The version of [#re2MatchMinimum] for a run of some non-ASCII characters but not all:
    /// the position after the run's minimum characters as [#re2SetMatch] reads them, or
    /// `-1` when they are absent.
    /// Copied as `ScanKernelSupport.re2SetRunMatchMinimum`; keep the copy in step.
    int re2SetMatchMinimum(byte[] input, int cursor, int end)
    {
        for (int count = 0; count < minimum; count++) {
            int width = re2SetMemberWidth(input, cursor, end);
            if (width == 0) {
                return -1;
            }
            cursor += width;
        }
        return cursor;
    }

    // Copied as ScanKernelSupport.re2SetRunMemberWidth; keep the copy in step.
    private int re2SetMemberWidth(byte[] input, int cursor, int end)
    {
        if (cursor >= end) {
            return 0;
        }
        int value = input[cursor];
        if (value < 0) {
            return nonAsciiMembers.memberWidth(input, cursor, end);
        }
        return members[value] ? 1 : 0;
    }

    // Copied as ScanKernelSupport.re2SetRunScan; keep the copy in step.
    private int re2SetScan(byte[] input, int cursor, int end)
    {
        while (true) {
            // As re2Scan does: find the first non-member ASCII byte or non-ASCII byte, then
            // consume the member characters there. ASCII input never reads the non-ASCII trie.
            if (asciiStopNeedles != null && end - cursor >= RE2_RUN_STOP_MINIMUM_BYTES && VectorSupport.isAvailable()) {
                cursor = VectorScanner.findRe2RunStop(input, cursor, end, asciiStopNeedles);
            }
            else if (asciiStopNeedles != null && asciiStopNeedles.length == 0) {
                // Every ASCII byte is a member.
                while (cursor < end && input[cursor] >= 0) {
                    cursor++;
                }
            }
            else {
                while (cursor < end && input[cursor] >= 0 && members[input[cursor]]) {
                    cursor++;
                }
            }
            int width;
            while (cursor < end && input[cursor] < 0 && (width = nonAsciiMembers.memberWidth(input, cursor, end)) != 0) {
                cursor += width;
            }
            if (cursor >= end || input[cursor] < 0 || !members[input[cursor]]) {
                return cursor;
            }
        }
    }

    // Copied as ScanKernelSupport.runScan; keep the copy in step.
    private int scan(byte[] input, int cursor, int end)
    {
        if (needles != null) {
            if (needles.length == 0) {
                return inverted ? end : cursor;
            }
            // Small sets and their complements share the same equality scans. Keep larger
            // sets on a scalar table until measurements justify another vector operation.
            if (VectorSupport.isAvailable()) {
                return VectorScanner.findRunStop(input, cursor, end, needles, inverted, members);
            }
        }
        for (; cursor < end; cursor++) {
            if (!members[input[cursor] & 0xFF]) {
                return cursor;
            }
        }
        return end;
    }

    /// Returns the first member byte at or after `cursor`, or `-1` when none remains.
    /// Member sets of one to 64 bytes use the same vector scanners as the DFA's start-byte
    /// search; larger sets and their complements keep the plan's own scans.
    /// Copied as `ScanKernelSupport.runFindCandidate`; keep the copy in step.
    int findCandidate(byte[] input, int cursor, int end)
    {
        // Dense inputs usually have a candidate at the cursor, and large sets such as word
        // characters within a few bytes, where vector setup costs more than it saves.
        int scalarEnd = Math.min(end, cursor + (candidateScan == CANDIDATE_SCAN_NIBBLE_TABLE ? SCALAR_CANDIDATE_PREFIX_BYTES : 1));
        for (; cursor < scalarEnd; cursor++) {
            if (members[input[cursor] & 0xFF]) {
                return cursor;
            }
        }
        if (cursor >= end) {
            return -1;
        }
        switch (candidateScan) {
            case CANDIDATE_SCAN_SMALL_SET -> {
                return VectorByteSetScanner.find(
                        input,
                        cursor,
                        end - cursor,
                        needles[0],
                        needles.length > 1 ? needles[1] : needles[0],
                        needles.length > 2 ? needles[2] : needles[0],
                        needles.length);
            }
            case CANDIDATE_SCAN_RANGE -> {
                return VectorByteSetScanner.findRange(input, cursor, end - cursor, lowestMember, highestMember);
            }
            case CANDIDATE_SCAN_NIBBLE_TABLE -> {
                return VectorByteSetScanner.findNibbleTable(input, cursor, end - cursor, candidateNibbleTables);
            }
            default -> {
                return findCandidateInTable(input, cursor, end);
            }
        }
    }

    // Copied as ScanKernelSupport.runFindCandidateInTable; keep the copy in step.
    private int findCandidateInTable(byte[] input, int cursor, int end)
    {
        if (needles != null) {
            if (needles.length == 0) {
                return inverted ? cursor : -1;
            }
            if (VectorSupport.isAvailable()) {
                return VectorScanner.findRunCandidate(input, cursor, end, needles, inverted, members);
            }
        }
        for (; cursor < end; cursor++) {
            if (members[input[cursor] & 0xFF]) {
                return cursor;
            }
        }
        return -1;
    }
}
