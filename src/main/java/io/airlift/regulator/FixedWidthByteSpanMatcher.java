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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
/// RE2's Latin-1 mode and Rust's byte mode, reads one byte, which a 256-bit mask decides. When
/// every position reads one byte, a match has as many bytes as positions and a search reads no
/// characters. A UTF-8 position with members above ASCII reads the character there as the
/// compiled class's byte ranges read it, malformed input included
/// ([Utf8#re2DecodeClassCharacter]), so matches can differ in byte length. Every character
/// such a position or an ASCII position accepts begins with a byte that is not a continuation
/// byte, and any bytes after it are continuation bytes, so the positions of a match start at the
/// bytes of its span that are not continuation bytes. Of two matches, the one that starts later
/// therefore has the candidate's character later too, and one start has at most one match, so
/// the first hit that verifies is the leftmost match and also the longest one there.
///
/// When neither an ASCII singleton scan nor a rare candidate applies, a pattern such as
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
    // Outside ASCII singleton patterns (see ASCII_SINGLETON_WIDTH), a position is the candidate
    // only when English text holds its first bytes less often than once in 100 bytes. That
    // admits the ten digits, at 0.0091, the commonest class of fixed-width record fields such as
    // dates, phone numbers, and codes, and a class or byte of rare punctuation or of the rare
    // letters j, q, x, and z, and refuses every position holding a common letter or a space. Each
    // hit costs a check of the positions around it, and a hit every 100 bytes leaves the vector
    // scan's cost per byte in charge. Where the estimate is wrong for the text, as with a long run
    // of digits, a failing hit costs at most one read of each position, at most 256 characters or
    // about 1 KiB, which stays within the O(text x program) bound of the NFA, since the program
    // has at least one instruction per position. The inner literal search's rare class pair
    // gates the product of two positions at 0.002 instead.
    private static final double MAXIMUM_CANDIDATE_PROBABILITY = 0.01;
    // An ASCII singleton pattern, whose every position is an ASCII byte or an ASCII class, which
    // is at most this wide, and which has a singleton position, one of a single member, scans for
    // its first singleton position whatever its probability: one byte, which the one-byte vector
    // scan or Slice.indexOf finds, so its route depends on no frequency estimate. Other candidate
    // scans need MAXIMUM_CANDIDATE_PROBABILITY; a pattern without one may take the block search.
    private static final int ASCII_SINGLETON_WIDTH = 64;
    // englishText() rates every byte at or above 0x80 as common as a space, which keeps literal
    // probes on ASCII bytes. English text holds those bytes about as rarely as its rarest ASCII
    // byte, so the candidate choice rates them so; it prefers a candidate of ASCII bytes
    // wherever one is rare enough, so text in another script, where lead bytes are common, still
    // scans an ASCII position when the pattern has a rare one.
    private static final double NON_ASCII_PROBABILITY = rarestAsciiProbability(ByteFrequencies.englishText());
    // That rating is right for English text only. In another script the lead bytes of its
    // letters are common, so a search whose candidate holds a byte at or above 0x80 counts the
    // candidates that fail, from the start of the search or the count, and once at least
    // MINIMUM_BAIL_FAILURES have failed at more than one per BYTES_PER_BAIL_FAILURE bytes it
    // hands the rest to the program. The program runs the route the pattern would take without
    // the matcher, so a bail on text the rating misjudges gives up the matcher's gain, adding
    // only the matcher's work before the bail, and never runs a slower route than that one.
    // Failed candidates per KiB, counted on the benchmark rows of this matcher's A/B: 0.8-1.1 for
    // the [a-q][^u-z]{n}[x\xE0-\xFF] targets and their x siblings on English text, against 136
    // for [一-龥]{3} on Chinese, 308 for (?i)Шерлок Холмс and 351 for [а-я]{5} on Russian. One per
    // 32 bytes is 32 per KiB, 29x the English rows and a quarter of the Chinese one. Candidates
    // of ASCII bytes keep their English rates, measured, and never bail: in those rows the digits
    // fail 0.9-1.4 times per KiB.
    private static final int MINIMUM_BAIL_FAILURES = 64;
    private static final int BYTES_PER_BAIL_FAILURE = 32;
    /// What [#findSpanOrBail] returns when the search bails, for a caller that holds the program
    /// to run in its place. It only needs to differ from every span and [Dfa#SEARCH_NO_MATCH],
    /// so it reuses the DFA's sentinel for a candidate search that falls back.
    static final long BAILED = Dfa.CANDIDATE_SEARCH_FALLBACK;

    // How a search scans for a candidate of several bytes. It makes the forward DFA's start-byte
    // scan choice, with the same code: a sample of the candidate bytes' density, taken where the
    // search begins, picks the vector or the scalar scan, and the search keeps it. A count of
    // every match settles a borderline sample with the wider one, keeps a vector choice for the
    // whole count, and samples a scalar choice again where a later search has text enough left.
    // Digits cluster, as in years and prices, so a scan that left the vector scan after a few
    // short skips spent long sparse stretches on the scalar scan. The DFA's productivity check
    // on 64 KiB or more, which sends a search to the plain DFA walk, is left out: the matcher has
    // no other route to send it to. VECTOR_SCAN and SCALAR_SCAN are kept for the search; a value
    // of zero or more is a count's scalar choice, sampled again at the first later search that
    // starts at or after it.
    private static final int VECTOR_SCAN = -1;
    private static final int SCALAR_SCAN = -2;

    // The block search, for a pattern whose every position reads one byte and that takes neither
    // an ASCII singleton scan nor a rare candidate. It needs the Vector API, which builds a
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
    // The bytes each position accepts, the ASCII ones of a UTF-8 position, in 1 << maskShift
    // words: two where no position accepts a byte above ASCII, as in every UTF-8 pattern, and
    // otherwise four. Null for the block search, which reads its classes instead.
    private final long[] masks;
    private final int maskShift;
    // Each position's reading, or null when every position reads one byte.
    private final byte[] kinds;
    // Each NON_ASCII_RANGES position's inclusive rune ranges, null at every other position, or
    // null where kinds is.
    private final int[][] runeRanges;
    private final int candidateOffset;
    // Whether the search scans a rare candidate, one chosen under MAXIMUM_CANDIDATE_PROBABILITY,
    // or searches blocks, either of which yields to the engine's scans, and whether a search of
    // the candidate bails on dense candidates.
    private final boolean yieldsToEngineScans;
    private final boolean bailsOnDenseCandidates;
    // The candidate's first byte where only one byte can begin its character, found with the
    // one-byte vector scan on long searches where the Vector API is available and with
    // Slice.indexOf otherwise.
    private final Slice candidate;
    // Otherwise its first bytes, scanned for with Dfa.findByteSet where the Vector API takes
    // them (candidateScanSet nonzero) and with this table elsewhere.
    private final byte[] candidateTable;
    private final int candidateScanSet;
    private final byte[] candidateNibbleTables;
    // The block search's classes, with candidateOffset -1, or null where the search scans a
    // candidate.
    private final BlockClasses blockClasses;

    private FixedWidthByteSpanMatcher(List<Position> positions, boolean readsCharacters, boolean latin1NonAscii, int candidateOffset, boolean yieldsToEngineScans, BlockClasses blockClasses)
    {
        width = positions.size();
        maskShift = latin1NonAscii ? 2 : 1;
        kinds = readsCharacters ? new byte[width] : null;
        runeRanges = readsCharacters ? new int[width][] : null;
        this.candidateOffset = candidateOffset;
        this.yieldsToEngineScans = yieldsToEngineScans;
        this.blockClasses = blockClasses;
        if (blockClasses != null) {
            // The block search reads its classes, not the masks, and its positions all read one
            // byte, so kinds and runeRanges are null too.
            masks = null;
            bailsOnDenseCandidates = false;
            candidate = null;
            candidateTable = null;
            candidateScanSet = 0;
            candidateNibbleTables = null;
            return;
        }
        masks = new long[width << maskShift];
        for (int index = 0; index < width; index++) {
            Position position = positions.get(index);
            System.arraycopy(position.mask(), 0, masks, index << maskShift, 1 << maskShift);
            if (readsCharacters) {
                kinds[index] = position.kind();
                runeRanges[index] = position.runeRanges();
            }
        }

        long[] candidateBytes = candidateBytes(positions.get(candidateOffset));
        bailsOnDenseCandidates = holdsNonAsciiByte(candidateBytes);
        byte[] table = null;
        int scanSet = 0;
        byte[] nibbleTables = null;
        if (byteCount(candidateBytes) == 1) {
            candidate = Slices.wrappedBuffer((byte) firstByte(candidateBytes));
        }
        else {
            candidate = null;
            table = new byte[256];
            for (int value = 0; value < 256; value++) {
                if (containsByte(candidateBytes, value)) {
                    table[value] = 1;
                }
            }
            // The literal set's root skip takes the same kernels; see LiteralSetSpanMatcher.
            if (VectorSupport.isAvailable()) {
                scanSet = Dfa.buildStartByteScanSet(table);
                if (scanSet == 0) {
                    nibbleTables = VectorByteSetScanner.buildNibbleTables(table);
                    scanSet = nibbleTables == null ? 0 : Dfa.MIXED_BYTE_SET_MARKER;
                }
            }
        }
        candidateTable = table;
        candidateScanSet = scanSet;
        candidateNibbleTables = nibbleTables;
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
        boolean asciiSingletonPattern = isAsciiSingletonPattern(positions);
        int candidateOffset = asciiSingletonPattern ? firstSingleton(positions) : rarestCandidate(positions);
        // The block search applies when neither an ASCII singleton scan nor a rare candidate does.
        if (candidateOffset < 0) {
            BlockClasses blockClasses = readsCharacters ? null : blockClasses(positions);
            if (blockClasses == null) {
                return null;
            }
            return new FixedWidthByteSpanMatcher(positions, false, latin1NonAscii, -1, true, blockClasses);
        }
        return new FixedWidthByteSpanMatcher(positions, readsCharacters, latin1NonAscii, candidateOffset, !asciiSingletonPattern, null);
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

    static TrinoRegexp createTrinoRegexp(TrinoCompiledPattern pattern, Regexp regexp)
    {
        FixedWidthByteSpanMatcher spanMatcher = pattern.createFixedWidthByteSpanMatcher(regexp);
        // The fixed-width subtype never bails. A matcher that can bail leaves every operation to
        // the compiled pattern, whose routes hand a bailed search to its program. A matcher that
        // scans a rare candidate or searches blocks answers only where the compiled pattern
        // retained it, having yielded to no route of the program.
        if (spanMatcher == null ||
                spanMatcher.bailsOnDenseCandidates() ||
                (spanMatcher.yieldsToEngineScans() && !pattern.hasRetainedFixedWidthByteSpanMatcher())) {
            return null;
        }
        return new TrinoRegexp.FixedWidthByteTrinoRegexp(pattern, spanMatcher);
    }

    long estimatedRetainedSize()
    {
        long size = INSTANCE_SIZE + SizeOf.sizeOf(masks);
        if (kinds != null) {
            size += SizeOf.sizeOf(kinds) + SizeOf.sizeOf(runeRanges);
            // The repetitions of a repeated position or group share its ranges, counted once.
            Set<int[]> countedRanges = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int[] ranges : runeRanges) {
                if (ranges != null && countedRanges.add(ranges)) {
                    size += SizeOf.sizeOf(ranges);
                }
            }
        }
        if (candidate != null) {
            size += candidate.getRetainedSize();
        }
        if (candidateTable != null) {
            size += SizeOf.sizeOf(candidateTable);
        }
        if (candidateNibbleTables != null) {
            size += SizeOf.sizeOf(candidateNibbleTables);
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

    /// Returns the rarest position whose first bytes pass [#MAXIMUM_CANDIDATE_PROBABILITY],
    /// preferring one whose first bytes are all ASCII, or `-1` when no position is rare enough.
    private static int rarestCandidate(List<Position> positions)
    {
        ByteFrequencies frequencies = ByteFrequencies.englishText();
        int rarest = -1;
        double rarestProbability = Double.MAX_VALUE;
        boolean rarestAscii = false;
        for (int index = 0; index < positions.size(); index++) {
            long[] candidateBytes = candidateBytes(positions.get(index));
            boolean ascii = !holdsNonAsciiByte(candidateBytes);
            double probability = englishProbability(candidateBytes, frequencies);
            if (probability < MAXIMUM_CANDIDATE_PROBABILITY &&
                    (rarest < 0 || (ascii && !rarestAscii) || (ascii == rarestAscii && probability < rarestProbability))) {
                rarest = index;
                rarestProbability = probability;
                rarestAscii = ascii;
            }
        }
        return rarest;
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

    /// Returns the bytes that can begin the character of `position`: its one-byte members, and
    /// the lead bytes of its members' UTF-8 encodings.
    private static long[] candidateBytes(Position position)
    {
        long[] bytes = position.mask().clone();
        if (position.kind() == ALL_NON_ASCII) {
            // The lead bytes Utf8.re2CharacterWidth accepts.
            addBytes(bytes, 0xC2, 0xF4);
        }
        else if (position.kind() == NON_ASCII_RANGES) {
            int[] ranges = position.runeRanges();
            for (int index = 0; index < ranges.length; index += 2) {
                // Lead bytes rise with the code point among encodings of one length.
                addLeadBytes(bytes, Math.max(ranges[index], 0x80), Math.min(ranges[index + 1], 0x7FF));
                addLeadBytes(bytes, Math.max(ranges[index], 0x800), Math.min(ranges[index + 1], 0xFFFF));
                addLeadBytes(bytes, Math.max(ranges[index], 0x10000), ranges[index + 1]);
            }
        }
        return bytes;
    }

    private static void addLeadBytes(long[] bytes, int low, int high)
    {
        if (low <= high) {
            addBytes(bytes, leadByte(low), leadByte(high));
        }
    }

    private static int leadByte(int rune)
    {
        if (rune < 0x800) {
            return 0xC0 | (rune >>> 6);
        }
        return rune < 0x10000 ? 0xE0 | (rune >>> 12) : 0xF0 | (rune >>> 18);
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

    /// Returns whether the search yields to the engine's scans: whether its candidate was chosen
    /// as rare, under [#MAXIMUM_CANDIDATE_PROBABILITY], as for every pattern but an
    /// [ASCII singleton pattern][#isAsciiSingletonPattern], or it tests blocks for want of one.
    /// Either way the pattern's frequencies, not one byte the text holds, chose the route.
    boolean yieldsToEngineScans()
    {
        return yieldsToEngineScans;
    }

    /// Returns whether [#findSpanOrBail] and [#countOrBail] can bail: whether the candidate holds
    /// a byte at or above 0x80.
    boolean bailsOnDenseCandidates()
    {
        return bailsOnDenseCandidates;
    }

    /// Returns an upper bound on the bytes a match spans: one for each position where every
    /// position reads one byte, and otherwise four for each position, the most any character
    /// spans, even where some positions read only one byte.
    int maximumMatchBytes()
    {
        return kinds == null ? width : 4 * width;
    }

    /// Returns the number of non-overlapping matches in `input`. Matches are never empty, so
    /// each search resumes where the previous match ended.
    long count(Slice input)
    {
        requireNonNull(input, "input is null");
        if (usesSingleByteVectorScan(input, 0)) {
            return searchLong(input, 0, true, false);
        }
        return kinds != null
                ? searchCharacters(input, 0, true, false)
                : blockClasses != null
                  ? searchBlocks(input, 0, true)
                  : searchBytes(input, 0, true, false);
    }

    /// Returns [#count(Slice)], or, where the count bails on dense candidates, a negative value
    /// from which [#countBeforeBail] reads the matches counted and [#resumeAfterBail] where the
    /// last of them ended, zero when none was counted. The whole count is one search, so its
    /// failures and the bytes they are measured against run from the input start across every
    /// match.
    long countOrBail(Slice input)
    {
        requireNonNull(input, "input is null");
        if (usesSingleByteVectorScan(input, 0)) {
            return searchLong(input, 0, true, bailsOnDenseCandidates);
        }
        return kinds != null
                ? searchCharacters(input, 0, true, bailsOnDenseCandidates)
                : blockClasses != null
                  ? searchBlocks(input, 0, true)
                  : searchBytes(input, 0, true, bailsOnDenseCandidates);
    }

    static long countBeforeBail(long bailedCount)
    {
        return (bailedCount & Long.MAX_VALUE) >>> 32;
    }

    static int resumeAfterBail(long bailedCount)
    {
        return (int) bailedCount;
    }

    // Matches are at least one byte long, so the count and the resume position each fit in 31
    // bits, and the sign bit marks the bail.
    private static long bailedCount(long count, int resume)
    {
        return Long.MIN_VALUE | (count << 32) | resume;
    }

    /// Returns the span of the first match at or after `start` in `input`, or
    /// [Dfa#SEARCH_NO_MATCH].
    long findSpan(Slice input, int start)
    {
        requireNonNull(input, "input is null");
        if (usesSingleByteVectorScan(input, start)) {
            return searchLong(input, start, false, false);
        }
        return kinds != null
                ? searchCharacters(input, start, false, false)
                : blockClasses != null
                  ? searchBlocks(input, start, false)
                  : searchBytes(input, start, false, false);
    }

    /// Returns the span of the first match lying entirely in `[start, end)` of `input`,
    /// [Dfa#SEARCH_NO_MATCH], or [#BAILED] where the search bails on dense candidates; the caller
    /// checks that `0 <= start <= end <= input.length()`. A region that ends before the input
    /// searches a view of the input that ends with it, so the candidate search and every
    /// character read stop at the region end; a full-input region searches the input itself. A
    /// bailed search reports nothing about the region, so the caller searches it again from
    /// `start`.
    long findSpanOrBail(Slice input, int start, int end)
    {
        requireNonNull(input, "input is null");
        Slice region = end == input.length() ? input : input.slice(0, end);
        if (usesSingleByteVectorScan(region, start)) {
            return searchLong(region, start, false, bailsOnDenseCandidates);
        }
        return kinds != null
                ? searchCharacters(region, start, false, bailsOnDenseCandidates)
                : blockClasses != null
                  ? searchBlocks(region, start, false)
                  : searchBytes(region, start, false, bailsOnDenseCandidates);
    }

    /// Returns the first match's span at or after `start`, or with `counting`, the number of
    /// matches from there; or, with `bailing`, [#BAILED] or a bailed count once the candidates
    /// that fail are dense. [#searchLongBytes] copies this loop for a
    /// [long one-byte candidate search][#usesSingleByteVectorScan]; its matching, restart, and
    /// bail logic must stay the same as this one's.
    private long searchBytes(Slice input, int start, boolean counting, boolean bailing)
    {
        int lastStart = input.length() - width;
        if (start < 0 || start > lastStart) {
            return counting ? 0 : Dfa.SEARCH_NO_MATCH;
        }

        int lastCandidate = lastStart + candidateOffset;
        int firstCandidate = start + candidateOffset;
        int candidatePosition = firstCandidate;
        int scanChoice = firstScanChoice(input, firstCandidate, lastCandidate + 1, counting);
        long count = 0;
        int lastMatchEnd = start;
        long failures = 0;
        // lastCandidate is at least firstCandidate, so an unsigned compare also ends the loop on a
        // candidate position that overflowed past Integer.MAX_VALUE.
        while (Integer.compareUnsigned(candidatePosition, lastCandidate) <= 0) {
            int found = findCandidate(input, candidatePosition, lastCandidate + 1, scanChoice == VECTOR_SCAN);
            if (found < 0) {
                break;
            }
            int matchStart = found - candidateOffset;
            if (matchesBytes(input, matchStart)) {
                int matchEnd = matchStart + width;
                if (!counting) {
                    return ((long) matchStart << 32) | (matchEnd & 0xFFFF_FFFFL);
                }
                count++;
                lastMatchEnd = matchEnd;
                candidatePosition = matchEnd + candidateOffset;
                if (scanChoice >= 0 && Integer.compareUnsigned(candidatePosition, lastCandidate) <= 0) {
                    scanChoice = resampledScanChoice(input, scanChoice, firstCandidate, candidatePosition, lastCandidate + 1);
                }
                continue;
            }
            if (bailing && ++failures >= MINIMUM_BAIL_FAILURES && failures * BYTES_PER_BAIL_FAILURE > found + 1 - firstCandidate) {
                return counting ? bailedCount(count, lastMatchEnd) : BAILED;
            }
            candidatePosition = found + 1;
        }
        return counting ? count : Dfa.SEARCH_NO_MATCH;
    }

    /// [#searchBytes] for a pattern that [searches blocks][#BLOCK_BYTES]. A block starts at each
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

    /// [#searchBytes] for a pattern whose characters are not all one byte. [#searchLongCharacters]
    /// copies this loop for a [long one-byte candidate search][#usesSingleByteVectorScan]; its
    /// matching, restart, and bail logic must stay the same as this one's.
    private long searchCharacters(Slice input, int start, boolean counting, boolean bailing)
    {
        // The candidate's character starts after at least one byte for each earlier position and
        // leaves at least one for itself and each later one.
        int lastCandidate = input.length() - (width - candidateOffset);
        if (start < 0 || start > lastCandidate - candidateOffset) {
            return counting ? 0 : Dfa.SEARCH_NO_MATCH;
        }

        int firstCandidate = start + candidateOffset;
        byte[] bytes = input.byteArray();
        int offset = input.byteArrayOffset();
        int end = offset + input.length();
        int candidatePosition = firstCandidate;
        int searchStart = start;
        int scanChoice = firstScanChoice(input, firstCandidate, lastCandidate + 1, counting);
        long count = 0;
        long failures = 0;
        // lastCandidate is at least firstCandidate, so an unsigned compare also ends the loop on a
        // candidate position that overflowed past Integer.MAX_VALUE.
        while (Integer.compareUnsigned(candidatePosition, lastCandidate) <= 0) {
            int found = findCandidate(input, candidatePosition, lastCandidate + 1, scanChoice == VECTOR_SCAN);
            if (found < 0) {
                break;
            }
            long span = matchAround(bytes, offset, offset + searchStart, offset + found, end);
            if (span >= 0) {
                if (!counting) {
                    return span;
                }
                count++;
                searchStart = (int) span;
                candidatePosition = searchStart + candidateOffset;
                if (scanChoice >= 0 && Integer.compareUnsigned(candidatePosition, lastCandidate) <= 0) {
                    scanChoice = resampledScanChoice(input, scanChoice, firstCandidate, candidatePosition, lastCandidate + 1);
                }
                continue;
            }
            if (bailing && ++failures >= MINIMUM_BAIL_FAILURES && failures * BYTES_PER_BAIL_FAILURE > found + 1 - firstCandidate) {
                return counting ? bailedCount(count, searchStart) : BAILED;
            }
            candidatePosition = found + 1;
        }
        return counting ? count : Dfa.SEARCH_NO_MATCH;
    }

    /// Returns whether a search of `input` from `start` scans for a one-byte candidate with the
    /// vector byte-set scan, as a one-byte literal search does: a search of at least
    /// [Prog#FUSED_PREFIX_VECTOR_MINIMUM_BYTES] candidate positions where the Vector API is
    /// available, which runs in [#searchLong]. A shorter search keeps the word-at-a-time scan of
    /// `Slice.indexOf` in [#searchBytes] or [#searchCharacters], which compile as they would
    /// without the vector scan: a length test on each candidate search made short rows up to
    /// 3.6% slower on Graviton, and holding the array for the vector call up to 8.5% slower on
    /// x86-64. Each entry makes the choice before it dispatches on the reading, since the length
    /// test would push [#searchCharacters] past C2's inline size (`TestHotMethodSize`).
    boolean usesSingleByteVectorScan(Slice input, int start)
    {
        // The search has a candidate position for each match start from start to
        // input.length() - width. The length test keeps start alone on its right, so it cannot
        // wrap for a start far past the end: the left side is at least
        // -(MAXIMUM_WIDTH + (Prog.FUSED_PREFIX_VECTOR_MINIMUM_BYTES - 1)).
        return VectorSupport.isAvailable() && start >= 0 && input.length() - width - (Prog.FUSED_PREFIX_VECTOR_MINIMUM_BYTES - 1) >= start && candidate != null;
    }

    /// [#searchBytes] or [#searchCharacters] for a
    /// [long one-byte candidate search][#usesSingleByteVectorScan].
    private long searchLong(Slice input, int start, boolean counting, boolean bailing)
    {
        return kinds == null ? searchLongBytes(input, start, counting, bailing) : searchLongCharacters(input, start, counting, bailing);
    }

    /// [#searchBytes] for a [long one-byte candidate search][#usesSingleByteVectorScan]. Every
    /// candidate is found with the vector scan, including those in the short rest of the input
    /// after a match.
    private long searchLongBytes(Slice input, int start, boolean counting, boolean bailing)
    {
        // The entry test guarantees a nonnegative start with at least 1,024 candidate positions,
        // so the search needs no range check.
        int lastCandidate = input.length() - width + candidateOffset;
        int firstCandidate = start + candidateOffset;
        int candidatePosition = firstCandidate;
        long count = 0;
        int lastMatchEnd = start;
        long failures = 0;
        // lastCandidate is at least firstCandidate, so an unsigned compare also ends the loop on a
        // candidate position that overflowed past Integer.MAX_VALUE.
        while (Integer.compareUnsigned(candidatePosition, lastCandidate) <= 0) {
            int found = findCandidateVector(input, candidatePosition, lastCandidate + 1);
            if (found < 0) {
                break;
            }
            int matchStart = found - candidateOffset;
            if (matchesBytes(input, matchStart)) {
                int matchEnd = matchStart + width;
                if (!counting) {
                    return ((long) matchStart << 32) | (matchEnd & 0xFFFF_FFFFL);
                }
                count++;
                lastMatchEnd = matchEnd;
                candidatePosition = matchEnd + candidateOffset;
                continue;
            }
            if (bailing && ++failures >= MINIMUM_BAIL_FAILURES && failures * BYTES_PER_BAIL_FAILURE > found + 1 - firstCandidate) {
                return counting ? bailedCount(count, lastMatchEnd) : BAILED;
            }
            candidatePosition = found + 1;
        }
        return counting ? count : Dfa.SEARCH_NO_MATCH;
    }

    /// [#searchCharacters] for a [long one-byte candidate search][#usesSingleByteVectorScan].
    /// Every candidate is found with the vector scan, including those in the short rest of the
    /// input after a match.
    private long searchLongCharacters(Slice input, int start, boolean counting, boolean bailing)
    {
        // The candidate's character starts after at least one byte for each earlier position and
        // leaves at least one for itself and each later one. The entry test guarantees a
        // nonnegative start with at least 1,024 candidate positions, so the search needs no range
        // check.
        int lastCandidate = input.length() - (width - candidateOffset);
        int firstCandidate = start + candidateOffset;
        byte[] bytes = input.byteArray();
        int offset = input.byteArrayOffset();
        int end = offset + input.length();
        int candidatePosition = firstCandidate;
        int searchStart = start;
        long count = 0;
        long failures = 0;
        // lastCandidate is at least firstCandidate, so an unsigned compare also ends the loop on a
        // candidate position that overflowed past Integer.MAX_VALUE.
        while (Integer.compareUnsigned(candidatePosition, lastCandidate) <= 0) {
            int found = findCandidateVector(input, candidatePosition, lastCandidate + 1);
            if (found < 0) {
                break;
            }
            long span = matchAround(bytes, offset, offset + searchStart, offset + found, end);
            if (span >= 0) {
                if (!counting) {
                    return span;
                }
                count++;
                searchStart = (int) span;
                candidatePosition = searchStart + candidateOffset;
                continue;
            }
            if (bailing && ++failures >= MINIMUM_BAIL_FAILURES && failures * BYTES_PER_BAIL_FAILURE > found + 1 - firstCandidate) {
                return counting ? bailedCount(count, searchStart) : BAILED;
            }
            candidatePosition = found + 1;
        }
        return counting ? count : Dfa.SEARCH_NO_MATCH;
    }

    /// Returns the scan choice a search of the candidates in `[from, to)` of `input` begins
    /// with: the forward DFA's start-byte scan choice for the candidate bytes, which a count makes
    /// as the DFA's count of every match does. A count's scalar choice is sampled again by
    /// [#resampledScanChoice].
    private int firstScanChoice(Slice input, int from, int to, boolean counting)
    {
        if (candidateScanSet == 0) {
            return SCALAR_SCAN;
        }
        byte[] bytes = input.byteArray();
        int offset = input.byteArrayOffset();
        int selected = counting
                ? Dfa.selectEveryMatchCountByteScanSet(candidateScanSet, candidateTable, bytes, offset + from, offset + to)
                : Dfa.selectByteScanSet(candidateScanSet, candidateTable, bytes, offset + from, offset + to);
        if (selected != 0) {
            return VECTOR_SCAN;
        }
        return counting ? 0 : SCALAR_SCAN;
    }

    /// Returns the scan choice of a count's search of the candidates in `[from, to)` of `input`
    /// after its scalar choice `scanChoice`, the count's candidates starting at `firstCandidate`.
    /// As in the forward DFA's count, a search with at least
    /// [Dfa#MIN_SMALL_BYTE_SET_PRODUCTIVITY_CHECK_BYTES] left samples again, and a count of at
    /// least [Dfa#MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES] holds a scalar outcome for the DFA's
    /// recheck distance.
    private int resampledScanChoice(Slice input, int scanChoice, int firstCandidate, int from, int to)
    {
        if (to - from < Dfa.MIN_SMALL_BYTE_SET_PRODUCTIVITY_CHECK_BYTES || from < scanChoice) {
            return scanChoice;
        }
        int offset = input.byteArrayOffset();
        if (Dfa.selectByteScanSet(candidateScanSet, candidateTable, input.byteArray(), offset + from, offset + to) != 0) {
            return VECTOR_SCAN;
        }
        return to - firstCandidate >= Dfa.MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES ? Dfa.nextProductivityCheckPosition(from, to) : 0;
    }

    /// Returns the next byte in `[from, to)` of `input` that can begin the candidate position's
    /// character, or `-1`, with the vector scan where `vectorScan` chose it.
    private int findCandidate(Slice input, int from, int to, boolean vectorScan)
    {
        if (candidate != null) {
            int found = input.indexOf(candidate, from);
            return found < to ? found : -1;
        }
        byte[] bytes = input.byteArray();
        int offset = input.byteArrayOffset();
        if (vectorScan && to - from >= CompiledPattern.MINIMUM_LONG_SEARCH_BYTES) {
            int found = Dfa.findByteSet(bytes, offset + from, to - from, candidateScanSet, candidateNibbleTables);
            return found < 0 ? -1 : found - offset;
        }
        for (int position = offset + from; position < offset + to; position++) {
            if (candidateTable[bytes[position] & 0xFF] != 0) {
                return position - offset;
            }
        }
        return -1;
    }

    /// Returns the next one-byte candidate in `[from, to)` of `input`, or `-1`, with the vector
    /// byte-set scan.
    private int findCandidateVector(Slice input, int from, int to)
    {
        int offset = input.byteArrayOffset();
        int found = VectorByteSetScanner.find(input.byteArray(), offset + from, to - from, candidate.getByte(0), (byte) 0, (byte) 0, 1);
        return found < 0 ? -1 : found - offset;
    }

    private boolean matchesBytes(Slice input, int start)
    {
        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset() + start;
        for (int index = 0; index < width; index++) {
            // The candidate position accepts exactly the bytes the scan finds.
            if (index != candidateOffset && !containsByte(index, bytes[inputOffset + index] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /// Returns the span, relative to `offset`, of the match whose candidate character starts at
    /// `candidatePosition` and whose characters lie in `[start, end)` of `bytes`, or `-1`. The
    /// earlier positions read backward from the candidate and the later ones forward from it,
    /// and the check returns on the first rejected character.
    private long matchAround(byte[] bytes, int offset, int start, int candidatePosition, int end)
    {
        int matchEnd = readForward(candidateOffset, bytes, candidatePosition, end);
        if (matchEnd < 0) {
            return -1;
        }
        int matchStart = candidatePosition;
        for (int index = candidateOffset - 1; index >= 0; index--) {
            matchStart = matchStart > start ? readBackward(index, bytes, start, matchStart) : -1;
            if (matchStart < 0) {
                return -1;
            }
        }
        for (int index = candidateOffset + 1; index < width; index++) {
            matchEnd = matchEnd < end ? readForward(index, bytes, matchEnd, end) : -1;
            if (matchEnd < 0) {
                return -1;
            }
        }
        return ((long) (matchStart - offset) << 32) | ((matchEnd - offset) & 0xFFFF_FFFFL);
    }

    /// Returns where the character of position `index` that starts at `position`, before `end`,
    /// ends, or `-1` when the position rejects it.
    private int readForward(int index, byte[] bytes, int position, int end)
    {
        int value = bytes[position] & 0xFF;
        if (value < 0x80 || kinds[index] == ONE_BYTE) {
            return containsByte(index, value) ? position + 1 : -1;
        }
        if (kinds[index] == ALL_NON_ASCII) {
            // The lenient byte ranges accept every character re2CharacterWidth reads.
            int characterWidth = Utf8.re2CharacterWidth(bytes, position, end);
            return characterWidth == 0 ? -1 : position + characterWidth;
        }
        long decoded = Utf8.re2DecodeClassCharacter(bytes, position, end, false);
        return containsRune(runeRanges[index], Utf8.decodedCodePoint(decoded)) ? position + Utf8.decodedWidth(decoded) : -1;
    }

    /// Returns where the character of position `index` that ends at `end`, after `start`,
    /// starts, or `-1` when no character it accepts ends there.
    private int readBackward(int index, byte[] bytes, int start, int end)
    {
        int value = bytes[end - 1] & 0xFF;
        if (value < 0x80 || kinds[index] == ONE_BYTE) {
            return containsByte(index, value) ? end - 1 : -1;
        }
        int characterStart = Utf8.previousCharacterStart(bytes, start, end);
        return readForward(index, bytes, characterStart, end) == end ? characterStart : -1;
    }

    private boolean containsByte(int index, int value)
    {
        return (value < 0x80 || maskShift == 2) && ((masks[(index << maskShift) | (value >>> 6)] >>> value) & 1) != 0;
    }

    private static boolean containsRune(int[] ranges, int rune)
    {
        for (int index = 0; index < ranges.length; index += 2) {
            if (rune < ranges[index]) {
                return false;
            }
            if (rune <= ranges[index + 1]) {
                return true;
            }
        }
        return false;
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
