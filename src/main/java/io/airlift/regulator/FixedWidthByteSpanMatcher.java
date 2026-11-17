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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Objects.requireNonNull;

/// Searches a pattern whose every match holds the same number of characters, one per position,
/// each position a literal or a class, such as `[0-9]{4}-[0-9]{2}-[0-9]{2}` or
/// `[a-q][^u-z]{80}[x\xE0-\xFF]`. A bounded repeat of classes like the second makes the lazy
/// DFA's subset states grow with the repeat until it gives up and the search runs the NFA. This
/// matcher avoids the DFA state explosion that Hyperscan avoids with LimEx: it scans for the
/// bytes that can begin the character of one position, the candidate, and checks the other
/// positions around each hit.
///
/// A position whose class has only ASCII members, or any position of a Latin-1 pattern, as in
/// RE2's Latin-1 mode and Rust's byte mode, reads one byte, which the position's masks decide:
/// two words for the ASCII bytes, loaded by the position alone, so they load while the byte
/// does, and only in a Latin-1 pattern with a class above ASCII, two more for the bytes above
/// ASCII, which only such a byte reads. When every position reads one byte, a match has as
/// many bytes as positions and a search reads no characters. A UTF-8 position with members
/// above ASCII reads the character there as the compiled class's byte ranges read it,
/// malformed input included ([Utf8#re2DecodeClassCharacter]), so matches can differ in byte
/// length. Every character such a position or an ASCII position accepts begins with a byte
/// that is not a continuation byte, and any bytes after it are continuation bytes, so the
/// positions of a match start at the bytes of its span that are not continuation bytes. Of two
/// matches, the one that starts later therefore has the candidate's character later too, and
/// one start has at most one match, so the first hit that verifies is the leftmost match and
/// also the longest one there. Sweep leg a8-no-f2: only an
/// [ASCII singleton pattern][#isAsciiSingletonPattern] scans a candidate, so the matcher takes
/// no pattern whose positions read characters.
///
/// When an ASCII singleton scan does not apply, a pattern such as
/// `\w{5}\s\w{6}\s\w{7}`, whose positions all read one byte, instead tests every start of a
/// 64-byte block at once, a Shift-And whose bits are text positions: one word per distinct class,
/// a bit for each byte of the block it holds, which the Vector API builds; each position's word
/// shifted down by the position's offset, the next block's word supplying the bits past the block;
/// and the AND of those, whose bits are the block's match starts. The lowest is the leftmost
/// match, and a count takes them from the lowest, skipping each start inside the match before it.
final class FixedWidthByteSpanMatcher
        implements GroupZeroSpanSearch
{
    static final int MAXIMUM_WIDTH = 256;
    private static final int MAXIMUM_ANALYSIS_DEPTH = 256;
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(FixedWidthByteSpanMatcher.class);
    // An ASCII singleton pattern, whose every position is an ASCII byte or an ASCII class, which
    // is at most this wide, and which has a singleton position, one of a single member, scans for
    // its first singleton position whatever its probability: one byte, which Slice.indexOf
    // finds, so its route depends on no frequency estimate. Sweep leg a8-no-f2: no other pattern
    // scans a candidate; a pattern without one may take the block search.
    private static final int ASCII_SINGLETON_WIDTH = 64;
    // englishText() rates every byte at or above 0x80 as common as a space, which keeps literal
    // probes on ASCII bytes. English text holds those bytes about as rarely as its rarest ASCII
    // byte, so the block search's class rating rates them so.
    private static final double NON_ASCII_PROBABILITY = rarestAsciiProbability(ByteFrequencies.englishText());

    // The block search, for a pattern whose every position reads one byte and that takes no
    // ASCII singleton scan. It needs the Vector API, which builds a
    // block's class words, and a pattern at most a block wide, whose match ends at the latest in
    // the next block. Its cost per block is the class words plus a shift and an AND for each
    // position, whatever the text holds, so it takes a pattern of two to four distinct classes,
    // each of at most four byte ranges or the complement of at most four, where the product of its
    // positions' English byte probabilities estimates a start probability of at most 1/64:
    // \w{5}\s\w{6}\s\w{7} rates about 0.0005. A pattern of one class is a class repeat, which the
    // class-run counter counts, and a pattern whose starts are denser than that matches so often
    // that its matches, not its blocks, bound a count.
    private static final int BLOCK_BYTES = Long.SIZE;
    private static final int MAXIMUM_BLOCK_CLASSES = 4;
    // Four ranges fill the four 16-bit slots of a class's packed long.
    private static final int MAXIMUM_BLOCK_CLASS_RANGES = 4;
    private static final double MAXIMUM_BLOCK_START_PROBABILITY = 1.0 / BLOCK_BYTES;

    // How a search reads each position of a pattern whose characters are not all one byte.
    private static final byte ONE_BYTE = 0;
    private static final byte ALL_NON_ASCII = 1;
    private static final byte NON_ASCII_RANGES = 2;

    private final int width;
    // The ASCII bytes each position accepts, two words for each position: at 2 * index its
    // members below 64, and at 2 * index + 1 that word XOR its members from 64 to 127, so
    // containsByte reconstructs the membership word without a branch. Both words are addressed by
    // the position alone, so their loads can overlap the read of the input byte. Null for block
    // searches, which read blockClasses instead.
    private final long[] asciiMasks;
    private final int candidateOffset;
    // Whether the search searches blocks, which yields to the engine's scans.
    private final boolean yieldsToEngineScans;
    // The candidate's byte, the first singleton of an ASCII singleton pattern, found with
    // Slice.indexOf, or null where the search searches blocks.
    private final Slice candidate;
    // The block search's classes, with candidateOffset -1, or null where the search scans a
    // candidate.
    private final BlockClasses blockClasses;
    // Counts the searchCandidateByte searches for the diagnostics, only when tests turn on route
    // counts: every thread searching a compiled pattern shares this matcher.
    private long candidateByteSearchCount;

    private FixedWidthByteSpanMatcher(List<Position> positions, int candidateOffset, boolean yieldsToEngineScans, BlockClasses blockClasses)
    {
        width = positions.size();
        this.candidateOffset = candidateOffset;
        this.yieldsToEngineScans = yieldsToEngineScans;
        this.blockClasses = blockClasses;
        if (blockClasses != null) {
            // The block search reads its classes, not the masks.
            asciiMasks = null;
            candidate = null;
            return;
        }
        asciiMasks = new long[2 * width];
        for (int index = 0; index < width; index++) {
            Position position = positions.get(index);
            asciiMasks[2 * index] = position.mask()[0];
            asciiMasks[2 * index + 1] = position.mask()[0] ^ position.mask()[1];
        }
        candidate = Slices.wrappedBuffer((byte) firstByte(positions.get(candidateOffset).mask()));
    }

    static FixedWidthByteSpanMatcher analyze(Regexp regexp, ExpressionAnalysis analysis)
    {
        if (analysis.hasCaptures() ||
                analysis.hasFoldCaseLiteral() ||
                analysis.hasNonGreedyRepetition() ||
                analysis.hasUnsupportedPositionAssertions() ||
                analysis.canMatchEmpty()) {
            return null;
        }
        return analyze(regexp);
    }

    static FixedWidthByteSpanMatcher analyze(Regexp regexp)
    {
        List<Position> positions = new ArrayList<>();
        // A single position is a byte or character class, which has its own routes.
        if (!append(regexp, positions, 0) || positions.size() < 2 || positions.size() > MAXIMUM_WIDTH) {
            return null;
        }

        boolean allLiterals = true;
        boolean readsCharacters = false;
        boolean latin1NonAscii = false;
        for (Position position : positions) {
            allLiterals &= position.literal();
            readsCharacters |= position.kind() != ONE_BYTE;
            latin1NonAscii |= holdsNonAsciiByte(position.mask());
        }
        // Exact literals already have a smaller dedicated route. A Rust pattern can mix byte
        // classes with UTF-8 ones, as (?-u:\xFF) in a Unicode pattern; a byte there may be a
        // continuation byte, which would break the character boundaries the search relies on.
        if (allLiterals || (readsCharacters && latin1NonAscii)) {
            return null;
        }
        // The block search applies when an ASCII singleton scan does not.
        if (!isAsciiSingletonPattern(positions)) {
            BlockClasses blockClasses = readsCharacters ? null : blockClasses(positions);
            if (blockClasses == null) {
                return null;
            }
            return new FixedWidthByteSpanMatcher(positions, -1, true, blockClasses);
        }
        return new FixedWidthByteSpanMatcher(positions, firstSingleton(positions), false, null);
    }

    /// Returns the classes of the [block search][#BLOCK_BYTES] of a pattern whose every position
    /// reads one byte, or null where the search does not take the pattern.
    private static BlockClasses blockClasses(List<Position> positions)
    {
        if (positions.size() > BLOCK_BYTES || !VectorSupport.isAvailable()) {
            return null;
        }
        ByteFrequencies frequencies = ByteFrequencies.englishText();
        List<long[]> classes = new ArrayList<>();
        List<Double> classProbabilities = new ArrayList<>();
        List<List<Integer>> classOffsets = new ArrayList<>();
        double startProbability = 1;
        for (int offset = 0; offset < positions.size(); offset++) {
            long[] mask = positions.get(offset).mask();
            int classIndex = 0;
            while (classIndex < classes.size() && !Arrays.equals(classes.get(classIndex), mask)) {
                classIndex++;
            }
            if (classIndex == classes.size()) {
                if (classIndex == MAXIMUM_BLOCK_CLASSES) {
                    return null;
                }
                classes.add(mask);
                classProbabilities.add(englishProbability(mask, frequencies));
                classOffsets.add(new ArrayList<>());
            }
            startProbability *= classProbabilities.get(classIndex);
            classOffsets.get(classIndex).add(offset);
        }
        if (classes.size() < 2 || startProbability > MAXIMUM_BLOCK_START_PROBABILITY) {
            return null;
        }

        int classCount = classes.size();
        long[] ranges = new long[classCount];
        int[] rangeCounts = new int[classCount];
        long[] inversions = new long[classCount];
        int[][] offsets = new int[classCount][];
        byte[] table = new byte[256];
        for (int index = 0; index < classCount; index++) {
            long[] mask = classes.get(index);
            int count = rangeCount(mask);
            if (count <= MAXIMUM_BLOCK_CLASS_RANGES) {
                ranges[index] = packedRanges(mask);
                rangeCounts[index] = count;
            }
            else {
                long[] complement = new long[4];
                for (int word = 0; word < 4; word++) {
                    complement[word] = ~mask[word];
                }
                int complementCount = rangeCount(complement);
                if (complementCount > MAXIMUM_BLOCK_CLASS_RANGES) {
                    return null;
                }
                ranges[index] = packedRanges(complement);
                rangeCounts[index] = complementCount;
                inversions[index] = -1;
            }
            offsets[index] = classOffsets.get(index).stream().mapToInt(Integer::intValue).toArray();
            for (int value = 0; value < 256; value++) {
                if (containsByte(mask, value)) {
                    table[value] |= (byte) (1 << index);
                }
            }
        }
        return new BlockClasses(ranges, rangeCounts, inversions, offsets, table);
    }

    /// Returns the number of runs of consecutive bytes in `bytes`.
    private static int rangeCount(long[] bytes)
    {
        int count = 0;
        for (int value = 0; value < 256; value++) {
            if (containsByte(bytes, value) && (value == 0 || !containsByte(bytes, value - 1))) {
                count++;
            }
        }
        return count;
    }

    /// Returns the runs of `bytes`, at most four, in the layout [VectorBlockMaskScanner] reads.
    private static long packedRanges(long[] bytes)
    {
        long ranges = 0;
        int shift = 0;
        for (int value = 0; value < 256; value++) {
            if (containsByte(bytes, value) && (value == 0 || !containsByte(bytes, value - 1))) {
                int highest = value;
                while (highest < 255 && containsByte(bytes, highest + 1)) {
                    highest++;
                }
                ranges |= ((long) ((highest - value) << Byte.SIZE | value)) << shift;
                shift += Short.SIZE;
            }
        }
        return ranges;
    }

    static TrinoRegexp createTrinoRegexp(TrinoCompiledPattern pattern, TrinoRegexp.CompileInputs compileInputs, Regexp regexp)
    {
        FixedWidthByteSpanMatcher spanMatcher = pattern.createFixedWidthByteSpanMatcher(regexp);
        // A matcher that searches blocks answers only where the compiled pattern retained it,
        // having yielded to no route of the program.
        if (spanMatcher == null || (spanMatcher.yieldsToEngineScans() && !pattern.hasRetainedFixedWidthByteSpanMatcher())) {
            return null;
        }
        return new TrinoRegexp.FixedWidthByteTrinoRegexp(pattern, compileInputs, spanMatcher);
    }

    long estimatedRetainedSize()
    {
        long size = INSTANCE_SIZE + SizeOf.sizeOf(asciiMasks);
        if (candidate != null) {
            size += candidate.getRetainedSize();
        }
        if (blockClasses != null) {
            size += blockClasses.estimatedRetainedSize();
        }
        return size;
    }

    /// Returns whether the pattern is an ASCII singleton pattern: at most
    /// [#ASCII_SINGLETON_WIDTH] wide, every position an ASCII byte or an ASCII class, and some
    /// position a singleton, one of a single member.
    private static boolean isAsciiSingletonPattern(List<Position> positions)
    {
        if (positions.size() > ASCII_SINGLETON_WIDTH) {
            return false;
        }
        boolean singleton = false;
        for (Position position : positions) {
            if (position.kind() != ONE_BYTE || holdsNonAsciiByte(position.mask())) {
                return false;
            }
            singleton |= position.literal();
        }
        return singleton;
    }

    /// Returns the first singleton position, which an
    /// [ASCII singleton pattern][#isAsciiSingletonPattern] scans for whatever its probability.
    private static int firstSingleton(List<Position> positions)
    {
        for (int index = 0; ; index++) {
            if (positions.get(index).literal()) {
                return index;
            }
        }
    }

    /// Returns how often English text holds one of `bytes`, rating each byte at or above 0x80 at
    /// [#NON_ASCII_PROBABILITY].
    private static double englishProbability(long[] bytes, ByteFrequencies frequencies)
    {
        double probability = 0;
        for (int value = 0; value < 256; value++) {
            if (containsByte(bytes, value)) {
                probability += value < 0x80 ? frequencies.probability((byte) value) : NON_ASCII_PROBABILITY;
            }
        }
        return probability;
    }

    private static void addBytes(long[] bytes, int low, int high)
    {
        for (int value = low; value <= high; value++) {
            bytes[value >>> 6] |= 1L << value;
        }
    }

    private static boolean containsByte(long[] bytes, int value)
    {
        return ((bytes[value >>> 6] >>> value) & 1) != 0;
    }

    private static boolean holdsNonAsciiByte(long[] bytes)
    {
        return (bytes[2] | bytes[3]) != 0;
    }

    private static int byteCount(long[] bytes)
    {
        return Long.bitCount(bytes[0]) + Long.bitCount(bytes[1]) + Long.bitCount(bytes[2]) + Long.bitCount(bytes[3]);
    }

    private static int firstByte(long[] bytes)
    {
        for (int word = 0; word < 4; word++) {
            if (bytes[word] != 0) {
                return 64 * word + Long.numberOfTrailingZeros(bytes[word]);
            }
        }
        throw new IllegalArgumentException("no bytes");
    }

    private static double rarestAsciiProbability(ByteFrequencies frequencies)
    {
        double rarest = Double.MAX_VALUE;
        for (int value = 0; value < 0x80; value++) {
            rarest = Math.min(rarest, frequencies.probability((byte) value));
        }
        return rarest;
    }

    private static boolean append(Regexp regexp, List<Position> positions, int depth)
    {
        if (depth >= MAXIMUM_ANALYSIS_DEPTH || positions.size() > MAXIMUM_WIDTH) {
            return false;
        }
        return switch (regexp.op()) {
            case EMPTY_MATCH -> true;
            case LITERAL -> appendLiteral(regexp, new int[] {regexp.rune()}, positions);
            case LITERAL_STRING -> appendLiteral(regexp, regexp.runes(), positions);
            case CHAR_CLASS -> appendCharacterClass(regexp, positions);
            case CONCAT -> appendChildren(regexp, positions, depth);
            case REPEAT -> appendRepeat(regexp, positions, depth);
            default -> false;
        };
    }

    private static boolean appendChildren(Regexp regexp, List<Position> positions, int depth)
    {
        for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
            if (!append(regexp.child(childIndex), positions, depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean appendRepeat(Regexp regexp, List<Position> positions, int depth)
    {
        if (regexp.min() != regexp.max() || regexp.min() < 0) {
            return false;
        }
        // A repeat of count zero adds no position, whatever its child.
        if (regexp.min() == 0) {
            return true;
        }
        // The repetitions share the child's positions.
        List<Position> child = new ArrayList<>();
        if (!append(regexp.child(0), child, depth + 1) || (long) child.size() * regexp.min() > MAXIMUM_WIDTH - positions.size()) {
            return false;
        }
        for (int repetition = 0; repetition < regexp.min(); repetition++) {
            positions.addAll(child);
        }
        return true;
    }

    private static boolean appendLiteral(Regexp regexp, int[] runes, List<Position> positions)
    {
        if ((regexp.parseFlags() & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) != 0) {
            return false;
        }
        boolean latin1 = (regexp.parseFlags() & Regexp.LATIN1) != 0;
        for (int rune : runes) {
            if (rune < 0 || rune > (latin1 ? 0xFF : Regexp.RUNEMAX) || positions.size() == MAXIMUM_WIDTH) {
                return false;
            }
            long[] mask = new long[4];
            if (latin1 || rune < 0x80) {
                addBytes(mask, rune, rune);
                positions.add(new Position(mask, ONE_BYTE, null, true));
            }
            else {
                positions.add(new Position(mask, NON_ASCII_RANGES, new int[] {rune, rune}, true));
            }
        }
        return true;
    }

    private static boolean appendCharacterClass(Regexp regexp, List<Position> positions)
    {
        if (positions.size() == MAXIMUM_WIDTH) {
            return false;
        }
        // A class of a Latin-1 pattern, or of Rust's byte mode, compiles to bytes; any other
        // compiles to UTF-8 byte ranges, lenient ones when it holds every non-ASCII rune (see
        // Compiler.holdsAllNonAscii).
        boolean latin1 = (regexp.parseFlags() & Regexp.LATIN1) != 0;
        long[] mask = new long[4];
        boolean nonAscii = false;
        boolean allNonAscii = false;
        CharClass characterClass = regexp.charClass();
        for (int rangeIndex = 0; rangeIndex < characterClass.rangeCount(); rangeIndex++) {
            RuneRange range = characterClass.range(rangeIndex);
            if (range.low() < 0) {
                return false;
            }
            int byteHigh = Math.min(range.high(), latin1 ? 0xFF : 0x7F);
            if (range.low() <= byteHigh) {
                addBytes(mask, range.low(), byteHigh);
            }
            if (!latin1 && range.high() > 0x7F) {
                nonAscii = true;
                allNonAscii |= Compiler.holdsAllNonAscii(range);
            }
        }
        if (!nonAscii) {
            if ((mask[0] | mask[1] | mask[2] | mask[3]) == 0) {
                return false;
            }
            positions.add(new Position(mask, ONE_BYTE, null, byteCount(mask) == 1));
        }
        else if (allNonAscii) {
            positions.add(new Position(mask, ALL_NON_ASCII, null, false));
        }
        else {
            int[] ranges = new int[2 * characterClass.rangeCount()];
            for (int rangeIndex = 0; rangeIndex < characterClass.rangeCount(); rangeIndex++) {
                ranges[2 * rangeIndex] = characterClass.range(rangeIndex).low();
                ranges[2 * rangeIndex + 1] = characterClass.range(rangeIndex).high();
            }
            positions.add(new Position(mask, NON_ASCII_RANGES, ranges, characterClass.runeCount() == 1));
        }
        return true;
    }

    int candidateOffsetForDiagnostics()
    {
        return candidateOffset;
    }

    boolean searchesBlocksForDiagnostics()
    {
        return blockClasses != null;
    }

    /// Returns the number of [#searchCandidateByte] searches this matcher ran: one for each find
    /// on that route, and for each count one from its start and one from the end of each match,
    /// the last of which finds none. Other routes leave it unchanged. Only tests count them, under
    /// the DFA's route count switch.
    long candidateByteSearchCountForDiagnostics()
    {
        if (!Dfa.COUNTS_ROUTES) {
            throw new IllegalStateException("Candidate byte search counting is off; set -Dio.airlift.regulator.dfa.route-counts=true");
        }
        return candidateByteSearchCount;
    }

    // A call site is smaller bytecode than a field increment, so counting grows no caller.
    private void countCandidateByteSearch()
    {
        if (Dfa.COUNTS_ROUTES) {
            candidateByteSearchCount++;
        }
    }

    /// Returns whether the search yields to the engine's scans: whether it tests blocks for want
    /// of an [ASCII singleton][#isAsciiSingletonPattern].
    boolean yieldsToEngineScans()
    {
        return yieldsToEngineScans;
    }

    /// Returns an upper bound on the bytes a match spans: one for each position, since every
    /// position reads one byte.
    int maximumMatchBytes()
    {
        return width;
    }

    /// Returns the number of non-overlapping matches in `input`. Matches are never empty, so
    /// each search resumes where the previous match ended.
    long count(Slice input)
    {
        requireNonNull(input, "input is null");
        return blockClasses != null ? searchBlocks(input, 0, true) : countCandidateBytes(input);
    }

    /// Returns the span of the first match at or after `start` in `input`, or
    /// [Dfa#SEARCH_NO_MATCH].
    long findSpan(Slice input, int start)
    {
        requireNonNull(input, "input is null");
        return blockClasses != null ? searchBlocks(input, start, false) : searchCandidateByte(input, start);
    }

    /// Returns the span of the first match lying entirely in `[start, end)` of `input`, or
    /// [Dfa#SEARCH_NO_MATCH]; the caller checks that `0 <= start <= end <= input.length()`. A
    /// region that ends before the input searches a view of the input that ends with it, so the
    /// candidate search and every byte read stop at the region end; a full-input region searches
    /// the input itself. Sweep leg a8-no-f2: no search bails.
    long findSpanOrBail(Slice input, int start, int end)
    {
        requireNonNull(input, "input is null");
        Slice region = end == input.length() ? input : input.slice(0, end);
        return blockClasses != null ? searchBlocks(region, start, false) : searchCandidateByte(region, start);
    }

    /// Returns the span of the first match at or after `start`, or [Dfa#SEARCH_NO_MATCH], for a
    /// candidate of one byte that never bails, in a pattern whose every position reads one byte,
    /// finding each candidate with `Slice.indexOf`.
    private long searchCandidateByte(Slice input, int start)
    {
        countCandidateByteSearch();
        int lastStart = input.length() - width;
        if (start < 0 || start > lastStart) {
            return Dfa.SEARCH_NO_MATCH;
        }

        int lastCandidate = lastStart + candidateOffset;
        int candidatePosition = start + candidateOffset;
        // lastCandidate is at least candidatePosition, so an unsigned compare also ends the loop
        // on a candidate position that overflowed past Integer.MAX_VALUE.
        while (Integer.compareUnsigned(candidatePosition, lastCandidate) <= 0) {
            int found = input.indexOf(candidate, candidatePosition);
            if (found < 0 || found > lastCandidate) {
                return Dfa.SEARCH_NO_MATCH;
            }
            int matchStart = found - candidateOffset;
            if (matchesBytes(input, matchStart)) {
                int matchEnd = matchStart + width;
                return ((long) matchStart << 32) | (matchEnd & 0xFFFF_FFFFL);
            }
            candidatePosition = found + 1;
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    /// Returns the number of non-overlapping matches in `input` for a matcher that runs
    /// [#searchCandidateByte], which it calls from the input start and then from the end of each
    /// match, until a search finds none. C2 compiles the search on its own and calls it, as 2.0's
    /// count called its search: a count kept in the search's loop stayed live across
    /// `Slice.indexOf` and the check, and a UUID count stored 14 values to the stack each time it
    /// searched for a candidate.
    private long countCandidateBytes(Slice input)
    {
        long count = 0;
        int searchStart = 0;
        while (true) {
            long span = searchCandidateByte(input, searchStart);
            if (span < 0) {
                return count;
            }
            count++;
            searchStart = (int) span;
        }
    }

    /// The search of a pattern that [searches blocks][#BLOCK_BYTES]. A block starts at each
    /// multiple of 64 bytes past `start`, and a start's bit is set in the AND of its positions'
    /// shifted class words only where every position holds its byte: bytes past the input end
    /// are in no class, so no set bit starts a match that would end past it.
    private long searchBlocks(Slice input, int start, boolean counting)
    {
        int length = input.length();
        int lastStart = length - width;
        if (start < 0 || start > lastStart) {
            return counting ? 0 : Dfa.SEARCH_NO_MATCH;
        }

        byte[] bytes = input.byteArray();
        int offset = input.byteArrayOffset();
        BlockClasses classes = blockClasses;
        int[][] offsets = classes.offsets();
        int classCount = offsets.length;
        // The search begins a block before start, whose words are empty, so it has no starts:
        // the position at offset zero reads the empty word alone. Every block's words are then
        // built where the loop builds the next block's, one call site for each class.
        long first = 0;
        long second = 0;
        long third = 0;
        long fourth = 0;
        long count = 0;
        // The earliest start a match can take, the end of the last match counted.
        int resume = start;
        int block = start - BLOCK_BYTES;
        while (true) {
            // nextLength, the bytes from the next block to the input end, lies between
            // width - BLOCK_BYTES and the input length. On an input within BLOCK_BYTES of
            // Integer.MAX_VALUE, length - block can wrap, but the wrapping subtraction still
            // yields nextLength exactly. nextPosition can wrap there too once the next block
            // starts past the input end; it is read only where nextLength is positive, where it
            // lies before the end.
            int nextLength = length - block - BLOCK_BYTES;
            int nextPosition = offset + block + BLOCK_BYTES;
            long nextFirst = blockMembers(classes, 0, bytes, nextPosition, nextLength);
            long nextSecond = blockMembers(classes, 1, bytes, nextPosition, nextLength);
            long nextThird = classCount > 2 ? blockMembers(classes, 2, bytes, nextPosition, nextLength) : 0;
            long nextFourth = classCount > 3 ? blockMembers(classes, 3, bytes, nextPosition, nextLength) : 0;
            long starts = blockStarts(first, nextFirst, offsets[0], -1);
            starts = blockStarts(second, nextSecond, offsets[1], starts);
            if (classCount > 2) {
                starts = blockStarts(third, nextThird, offsets[2], starts);
            }
            if (classCount > 3) {
                starts = blockStarts(fourth, nextFourth, offsets[3], starts);
            }
            starts &= startsFrom(resume - block);
            while (starts != 0) {
                int matchStart = block + Long.numberOfTrailingZeros(starts);
                int matchEnd = matchStart + width;
                if (!counting) {
                    return ((long) matchStart << 32) | (matchEnd & 0xFFFF_FFFFL);
                }
                count++;
                resume = matchEnd;
                starts &= startsFrom(resume - block);
            }
            // lastStart is at least start, so lastStart - BLOCK_BYTES cannot overflow, while
            // lastStart - block can on an input within BLOCK_BYTES of Integer.MAX_VALUE.
            if (block > lastStart - BLOCK_BYTES) {
                return counting ? count : Dfa.SEARCH_NO_MATCH;
            }
            block += BLOCK_BYTES;
            first = nextFirst;
            second = nextSecond;
            third = nextThird;
            fourth = nextFourth;
        }
    }

    /// Returns the word of block class `classIndex` for the block at `position` of `bytes`, of
    /// which `available` bytes lie before the input end: the Vector API's word for a whole block,
    /// and otherwise the table's bits for the bytes there, none where `available` is not positive.
    private static long blockMembers(BlockClasses classes, int classIndex, byte[] bytes, int position, int available)
    {
        if (available >= BLOCK_BYTES) {
            long ranges = classes.ranges()[classIndex];
            int rangeCount = classes.rangeCounts()[classIndex];
            long word = VectorSupport.SVE_128
                    ? VectorBlockMaskScanner.membersSve(bytes, position, ranges, rangeCount)
                    : VectorBlockMaskScanner.members(bytes, position, ranges, rangeCount);
            return word ^ classes.inversions()[classIndex];
        }
        byte[] table = classes.table();
        long members = 0;
        for (int index = 0; index < available; index++) {
            members |= (long) ((table[bytes[position + index] & 0xFF] >>> classIndex) & 1) << index;
        }
        return members;
    }

    /// Returns `starts` with the bits cleared where a position at one of `offsets` lacks its
    /// byte: the class's word for the block, `current`, shifted down by the offset, with the
    /// next block's word, `next`, supplying the bits past the block. The second shift of `next`
    /// is split in two so an offset of zero shifts all of `next` out.
    private static long blockStarts(long current, long next, int[] offsets, long starts)
    {
        for (int offset : offsets) {
            starts &= (current >>> offset) | ((next << (63 - offset)) << 1);
        }
        return starts;
    }

    /// Returns the mask of a block's starts at or after `skip` bytes into it.
    private static long startsFrom(int skip)
    {
        if (skip <= 0) {
            return -1;
        }
        return skip >= BLOCK_BYTES ? 0 : -1L << skip;
    }

    private boolean matchesBytes(Slice input, int start)
    {
        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset() + start;
        // Bounded by the array's length, two words a position, so the check of each position
        // needs no range check.
        for (int index = 0; index < asciiMasks.length >>> 1; index++) {
            // The candidate position accepts exactly the bytes the scan finds.
            if (index != candidateOffset && !containsByte(index, bytes[inputOffset + index])) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether position `index` accepts byte `value`. A byte above ASCII, negative
    /// here, is in no position of an ASCII singleton pattern. For ASCII, the words at `2 * index` and `2 * index + 1` hold low and low XOR
    /// high. Bit 6 selects low for bytes 0..63 or reconstructs high for bytes 64..127,
    /// without a branch. Both loads depend only on the position, so they can overlap the
    /// input-byte load.
    private boolean containsByte(int index, byte value)
    {
        if (value < 0) {
            return false;
        }
        long low = asciiMasks[2 * index];
        long difference = asciiMasks[2 * index + 1];
        // All ones for a byte from 64 to 127, which turns the low word into the high one.
        long mask = low ^ (difference & -(long) (value >>> 6));
        return ((mask >>> value) & 1) != 0;
    }

    /// One position of the pattern: the bytes it accepts in four words (a UTF-8 position's ASCII
    /// members), how a search reads its character, its inclusive rune ranges where that reading
    /// needs them, and whether it holds exactly one member.
    private record Position(long[] mask, byte kind, int[] runeRanges, boolean literal) {}

    /// The classes of the block search: each class's ranges in the layout
    /// [VectorBlockMaskScanner] reads, their count, `-1` where the ranges are the class's
    /// complement and `0` elsewhere, and the offsets of its positions. Bit `c` of `table[b]` is
    /// set where class `c` holds byte `b`, which builds the words of the bytes past the last
    /// whole block.
    private record BlockClasses(long[] ranges, int[] rangeCounts, long[] inversions, int[][] offsets, byte[] table)
    {
        private static final int INSTANCE_SIZE = SizeOf.instanceSize(BlockClasses.class);

        long estimatedRetainedSize()
        {
            long size = INSTANCE_SIZE + SizeOf.sizeOf(ranges) + SizeOf.sizeOf(rangeCounts) + SizeOf.sizeOf(inversions) + SizeOf.sizeOf(offsets) + SizeOf.sizeOf(table);
            for (int[] classOffsets : offsets) {
                size += SizeOf.sizeOf(classOffsets);
            }
            return size;
        }
    }
}
