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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static io.airlift.regulator.Regexp.FOLD_CASE;
import static io.airlift.regulator.Regexp.FULL_CASE_FOLD;
import static io.airlift.regulator.Regexp.LATIN1;
import static java.util.Objects.requireNonNull;

/// Finds the leftmost-first match of an alternation of at most [#MAXIMUM_ALTERNATIVES] literal
/// strings, such as `ERROR|WARN|FATAL`, which serves short patterns in every language. It walks
/// a trie at each position it reads through its root table, and compares the literals that begin
/// with the byte at each start literal filter stop. [LiteralSetSpanMatcher] searches a larger
/// alternation, whose literals this class's analysis also extracts.
///
/// An alternation of ASCII literals that fold case, such as `(?i)Tom|Sawyer` in Rust's bytes
/// mode, has no trie: it is served only with a start literal filter, and its root table and its
/// filter stops both compare the literals that begin with the byte under their case masks.
///
/// The analysis builds an [ExactMatcher] or a [FoldedMatcher], each with its own search loops,
/// so the exact literals' trie walk compiles as it would with no folded literals in the
/// library: a branch to the folded literal compares at the root-table method's entry, before
/// its loop, changes the loop's compiled shape. "Start Literal Filter" in REGULATOR_DECISIONS.md
/// records the measurements. No search method is overridden; each search tests the matcher's
/// class and calls that class's own search, so every call binds statically. [FoldedMatcher]'s
/// `count`, `findSpan`, `holdsFilterRoute`, `readsRootTable`, and `findSpanInRoute` copy
/// [ExactMatcher]'s, so a change to one changes the other.
abstract sealed class LiteralAlternationSpanMatcher
        implements GroupZeroSpanSearch
        permits LiteralAlternationSpanMatcher.ExactMatcher, LiteralAlternationSpanMatcher.FoldedMatcher
{
    static final int MAXIMUM_ALTERNATIVES = 64;
    private static final int MAXIMUM_ANALYSIS_DEPTH = 256;
    // Bounds the bytes a large alternation's factored prefixes expand to, and also the number of
    // literals, since empty ones add no bytes; a dictionary of 43,029 English words holds about
    // 400 KB.
    static final int MAXIMUM_LITERAL_BYTES = 1 << 22;
    // Bounds the work of a whole extraction, the bytes its concatenations copy plus the entries
    // its alternations add, which a long concatenation or nested choices make quadratic in the
    // pattern even when each step stays under MAXIMUM_LITERAL_BYTES. A sorted dictionary of
    // 43,029 words charges about 2 million units, and one of 235,976 words about 7 million.
    private static final long MAXIMUM_EXTRACTION_WORK = 1L << 24;
    // A large set's extraction charges each frame and literal array it builds, each entry it adds
    // to a list of literals, and each duplicate table, as the DFA charges its states.
    private static final long FRAME_BYTES = SizeOf.instanceSize(Frame.class);
    private static final long REFERENCE_BYTES = (SizeOf.sizeOfObjectArray(8) - SizeOf.sizeOfObjectArray(0)) / 8;
    // Building literal lists costs a few times the set they make, so a large set's extraction may
    // build this many times the budget its caller admits the result by.
    private static final long EXTRACTION_BUDGET_MULTIPLE = 8;
    private static final int MINIMUM_DUPLICATE_TABLE_SLOTS = 16;
    // A half-full table averages under one step per lookup, so more than 8 means colliding hashes.
    private static final long MAXIMUM_TABLE_STEPS_PER_LOOKUP = 8;
    private static final long TABLE_STEP_ALLOWANCE = 1_024;
    // A class the parsers merge from a dictionary's single-character alternatives holds a few
    // members, such as [ds] from word(?:d|s); a wider one, such as [!-~], is not a literal set.
    private static final int MAXIMUM_CLASS_RUNES = 16;
    // The root-table route restarts the trie, or compares a folded matcher's literals, at every
    // input byte, and a filter stop compares the literals that begin with its byte, so a long
    // literal costs its length per byte on a dense near-miss input; such a pattern keeps the
    // general engine's linear scan.
    // At most 64 alternatives of this length also keep every trie index within a short.
    private static final int MAXIMUM_TRIE_LITERAL_BYTES = 64;
    // A denser filter would stop the vector scan so often that restarting it would cost more
    // than the root table's one lookup per byte.
    private static final double MAXIMUM_START_LITERAL_FILTER_DENSITY = 1.0 / 64;
    // Shorter inputs keep the root-table loop: a call's vector setup costs more than it saves,
    // and short everyday values keep the route they had before the filter.
    private static final int MINIMUM_START_LITERAL_FILTER_BYTES = 64;
    // A filter route whose stops stay below one per this many bytes, half the density the sample
    // calls dense, holds the filter for the next route without sampling.
    private static final int HELD_ROUTE_MINIMUM_BYTES_PER_STOP = 2 * Dfa.BYTE_SCAN_SAMPLE_SIZE / Dfa.BYTE_SCAN_SAMPLE_CANDIDATE_LIMIT;
    /// What [#findSpanOrDecline] and [#countOrDecline] return when the start-scan productivity
    /// check at the start of the input finds the filter's stops dense, for a caller that holds the
    /// general engine to run in their place. It only needs to differ from every span, count, and
    /// [Dfa#SEARCH_NO_MATCH], so it reuses the DFA's sentinel for a candidate search that falls
    /// back.
    static final long DECLINED = Dfa.CANDIDATE_SEARCH_FALLBACK;

    static LiteralAlternationSpanMatcher analyze(Regexp expression, int capturingGroupCount)
    {
        requireNonNull(expression, "expression is null");
        if (capturingGroupCount != 0) {
            return null;
        }

        // The trie's extraction charges no bytes; it stops at a literal longer than the trie takes.
        ExtractedLiterals extracted = extractLiteralAlternatives(expression, false, false, Long.MAX_VALUE);
        if (extracted == null) {
            return analyzeFolded(expression);
        }
        if (extracted.literals().size() < 2) {
            return null;
        }
        List<byte[]> literalAlternatives = extracted.literals();

        int totalLiteralBytes = 0;
        for (byte[] literal : literalAlternatives) {
            if (literal.length == 0 || literal.length > MAXIMUM_TRIE_LITERAL_BYTES) {
                return null;
            }
            totalLiteralBytes += literal.length;
        }

        short[] rootStateByLeadingByte = new short[256];
        short[] firstEdgeByState = new short[totalLiteralBytes + 1];
        short[] nextEdge = new short[totalLiteralBytes];
        short[] targetState = new short[totalLiteralBytes];
        byte[] edgeByte = new byte[totalLiteralBytes];
        short[] alternativeByState = new short[totalLiteralBytes + 1];
        Arrays.fill(alternativeByState, (short) -1);

        int stateCount = 1;
        int edgeCount = 0;
        for (int alternativeIndex = 0; alternativeIndex < literalAlternatives.size(); alternativeIndex++) {
            byte[] literal = literalAlternatives.get(alternativeIndex);
            int state = rootStateByLeadingByte[literal[0] & 0xFF];
            if (state == 0) {
                state = stateCount++;
                rootStateByLeadingByte[literal[0] & 0xFF] = (short) state;
            }
            for (int literalIndex = 1; literalIndex < literal.length; literalIndex++) {
                int nextState = ExactMatcher.findChild(state, literal[literalIndex], firstEdgeByState, nextEdge, targetState, edgeByte);
                if (nextState == 0) {
                    nextState = stateCount++;
                    edgeByte[edgeCount] = literal[literalIndex];
                    targetState[edgeCount] = (short) nextState;
                    nextEdge[edgeCount] = firstEdgeByState[state];
                    firstEdgeByState[state] = (short) (edgeCount + 1);
                    edgeCount++;
                }
                state = nextState;
            }
            if (alternativeByState[state] < 0) {
                alternativeByState[state] = (short) alternativeIndex;
            }
        }
        StartLiteralFilter startLiteralFilter = VectorSupport.isAvailable() ? buildStartLiteralFilter(literalAlternatives, null) : null;
        return new ExactMatcher(
                rootStateByLeadingByte,
                firstEdgeByState,
                nextEdge,
                targetState,
                edgeByte,
                alternativeByState,
                startLiteralFilter == null ? null : byLeadingByte(literalAlternatives, literalAlternatives, null),
                startLiteralFilter);
    }

    // An alternation whose literals fold case is served only where its start literal filter is
    // selective; without one the general engine keeps it, since the folded matcher compares
    // literals directly where the trie shares their prefixes.
    private static FoldedMatcher analyzeFolded(Regexp expression)
    {
        if (!VectorSupport.isAvailable()) {
            return null;
        }
        List<FoldedLiteral> folded = foldedLiterals(expression, 0);
        if (folded == null || folded.size() < 2) {
            return null;
        }
        List<byte[]> literals = new ArrayList<>(folded.size());
        List<byte[]> masks = new ArrayList<>(folded.size());
        for (FoldedLiteral literal : folded) {
            literals.add(literal.bytes());
            masks.add(literal.masks());
        }
        // With no trie, a filter stop or a root-table position compares every literal listed
        // under its byte, so the literals under one leading byte may total no more than one
        // literal the trie takes, which caps byte compares at 64 per position.
        byte[][][] literalsByLeadingByte = byLeadingByte(literals, literals, masks);
        for (byte[][] group : literalsByLeadingByte) {
            if (group != null) {
                int groupBytes = 0;
                for (byte[] literal : group) {
                    groupBytes += literal.length;
                }
                if (groupBytes > MAXIMUM_TRIE_LITERAL_BYTES) {
                    return null;
                }
            }
        }
        StartLiteralFilter startLiteralFilter = buildStartLiteralFilter(literals, masks);
        if (startLiteralFilter == null) {
            return null;
        }
        return new FoldedMatcher(literalsByLeadingByte, byLeadingByte(masks, literals, masks), startLiteralFilter);
    }

    /// Expands `expression` into ASCII literals whose letters may fold case, each with its case
    /// masks, in leftmost-first priority order as [#extractLiteralAlternatives] lists exact ones:
    /// a concatenation lists each prefix choice's literals before the next prefix choice's. It
    /// takes the elements [InnerLiteralSearch#appendFoldedElement] holds, which keep a letter's
    /// two cases only where the parser leaves no other case of it, so `(?i)s` with Unicode, whose
    /// class also holds the long s, is no folded literal. Returns `null` for unsupported
    /// expressions or elements, or when it passes [#MAXIMUM_ALTERNATIVES] literals,
    /// [#MAXIMUM_TRIE_LITERAL_BYTES] bytes in one, or [#MAXIMUM_ANALYSIS_DEPTH] levels of nesting.
    private static List<FoldedLiteral> foldedLiterals(Regexp expression, int depth)
    {
        if (depth >= MAXIMUM_ANALYSIS_DEPTH) {
            return null;
        }
        switch (expression.op()) {
            case LITERAL, LITERAL_STRING, CHAR_CLASS -> {
                byte[] bytes = new byte[MAXIMUM_TRIE_LITERAL_BYTES];
                byte[] masks = new byte[MAXIMUM_TRIE_LITERAL_BYTES];
                int length = InnerLiteralSearch.appendFoldedElement(expression, bytes, masks, 0);
                return length < 0 ? null : List.of(new FoldedLiteral(Arrays.copyOf(bytes, length), Arrays.copyOf(masks, length)));
            }
            case CONCAT -> {
                List<FoldedLiteral> prefixes = List.of(new FoldedLiteral(new byte[0], new byte[0]));
                for (Regexp child : expression.children()) {
                    List<FoldedLiteral> suffixes = foldedLiterals(child, depth + 1);
                    if (suffixes == null || (long) prefixes.size() * suffixes.size() > MAXIMUM_ALTERNATIVES) {
                        return null;
                    }
                    List<FoldedLiteral> combined = new ArrayList<>(prefixes.size() * suffixes.size());
                    for (FoldedLiteral prefix : prefixes) {
                        for (FoldedLiteral suffix : suffixes) {
                            FoldedLiteral literal = prefix.append(suffix);
                            if (literal == null) {
                                return null;
                            }
                            combined.add(literal);
                        }
                    }
                    prefixes = combined;
                }
                return prefixes;
            }
            case ALTERNATE -> {
                List<FoldedLiteral> alternatives = new ArrayList<>();
                for (Regexp child : expression.children()) {
                    List<FoldedLiteral> childAlternatives = foldedLiterals(child, depth + 1);
                    if (childAlternatives == null || alternatives.size() + childAlternatives.size() > MAXIMUM_ALTERNATIVES) {
                        return null;
                    }
                    alternatives.addAll(childAlternatives);
                }
                return alternatives;
            }
            default -> {
                return null;
            }
        }
    }

    // A literal's bytes and its case masks: a folded letter's byte is its lower case, and its mask
    // 0x20 admits the upper case too.
    private record FoldedLiteral(byte[] bytes, byte[] masks)
    {
        // Null when appending `suffix` would pass MAXIMUM_TRIE_LITERAL_BYTES.
        FoldedLiteral append(FoldedLiteral suffix)
        {
            int length = bytes.length + suffix.bytes.length;
            if (length > MAXIMUM_TRIE_LITERAL_BYTES) {
                return null;
            }
            byte[] combinedBytes = Arrays.copyOf(bytes, length);
            byte[] combinedMasks = Arrays.copyOf(masks, length);
            System.arraycopy(suffix.bytes, 0, combinedBytes, bytes.length, suffix.bytes.length);
            System.arraycopy(suffix.masks, 0, combinedMasks, bytes.length, suffix.masks.length);
            return new FoldedLiteral(combinedBytes, combinedMasks);
        }
    }

    // Lists each value, a literal or its masks, under the leading byte of its literal, and also
    // under that byte's other case when it is a folded letter, in pattern order. With null
    // `masks`, every literal is exact.
    private static byte[][][] byLeadingByte(List<byte[]> values, List<byte[]> literals, List<byte[]> masks)
    {
        byte[][][] valuesByLeadingByte = new byte[256][][];
        for (int index = 0; index < literals.size(); index++) {
            int value = literals.get(index)[0] & 0xFF;
            int mask = masks == null ? 0 : masks.get(index)[0] & 0xFF;
            addToGroup(valuesByLeadingByte, value, values.get(index));
            if (mask != 0) {
                addToGroup(valuesByLeadingByte, value & ~mask, values.get(index));
            }
        }
        return valuesByLeadingByte;
    }

    private static void addToGroup(byte[][][] valuesByLeadingByte, int leadingByte, byte[] entry)
    {
        byte[][] group = valuesByLeadingByte[leadingByte];
        group = group == null ? new byte[1][] : Arrays.copyOf(group, group.length + 1);
        group[group.length - 1] = entry;
        valuesByLeadingByte[leadingByte] = group;
    }

    // Each literal is one path of the byte sets Prog.buildStartPositionByteSets returns: its byte
    // at each position below its length, both cases of a folded letter, and every byte at the
    // positions past it, so the filter never tests a position some literal does not reach. The
    // filter therefore accepts every position where a literal starts. With null `masks`, every
    // literal is exact.
    private static StartLiteralFilter buildStartLiteralFilter(List<byte[]> literals, List<byte[]> masks)
    {
        long[][] paths = new long[literals.size()][];
        for (int index = 0; index < paths.length; index++) {
            byte[] literal = literals.get(index);
            long[] path = new long[StartLiteralFilter.PATH_POSITIONS * 4];
            for (int position = 0; position < StartLiteralFilter.PATH_POSITIONS; position++) {
                if (position < literal.length) {
                    int value = literal[position] & 0xFF;
                    int mask = masks == null ? 0 : masks.get(index)[position] & 0xFF;
                    // A mask is 0 or the case bit 0x20, so both cases fall in the same word.
                    path[position * 4 + (value >>> 6)] |= (1L << value) | (1L << (value & ~mask));
                }
                else {
                    Arrays.fill(path, position * 4, path.length, -1L);
                    break;
                }
            }
            paths[index] = path;
        }
        // Groups of one bit, two cases of a letter, as this matcher's filter was measured with.
        StartLiteralFilter filter = StartLiteralFilter.build(paths, ByteFrequencies.englishText(), 1);
        return filter != null && filter.density() <= MAXIMUM_START_LITERAL_FILTER_DENSITY ? filter : null;
    }

    // A folded literal listed under both its cases' leading bytes is one array, counted per listing.
    private static long retainedSizeByLeadingByte(byte[][][] valuesByLeadingByte)
    {
        if (valuesByLeadingByte == null) {
            return 0;
        }
        long size = SizeOf.sizeOfObjectArray(valuesByLeadingByte.length);
        for (byte[][] literals : valuesByLeadingByte) {
            if (literals != null) {
                size += SizeOf.sizeOfObjectArray(literals.length);
                for (byte[] literal : literals) {
                    size += SizeOf.sizeOf(literal);
                }
            }
        }
        return size;
    }

    /// The bytes the matcher retains, which its pattern charges to the memory budget.
    abstract long estimatedRetainedSize();

    /// True when a search with `length` bytes left may skip to the start literal filter's
    /// candidates rather than look up every byte in the root table. A search with at least 64 KiB
    /// left still reads the root table when its sample finds the filter's stops dense.
    abstract boolean usesStartLiteralFilter(int length);

    /// The start literal filter built from the literals, or `null` when the matcher has none.
    abstract StartLiteralFilter startLiteralFilterForDiagnostics();

    /// The positions where the start literal filter's scan stopped to compare the literals, in
    /// every search of this matcher. Only tests count them, under the DFA's route count switch.
    abstract long filterStopCountForDiagnostics();

    private static void checkFilterStopsCounted()
    {
        if (!Dfa.COUNTS_ROUTES) {
            throw new IllegalStateException("Filter stop counting is off; set -Dio.airlift.regulator.dfa.route-counts=true");
        }
    }

    /// Expands `expression` into the literals of a large set for [LiteralSetSpanMatcher], in
    /// leftmost-first priority order. Returns `null` where it has no literal expansion within the
    /// extraction's bounds, or [ExtractedLiterals#OVER_BUDGET] where what it builds would pass
    /// [#EXTRACTION_BUDGET_MULTIPLE] times `maximumRetainedBytes`, or where its duplicate tables'
    /// lookups step past more slots in all than [#MAXIMUM_TABLE_STEPS_PER_LOOKUP] per lookup plus
    /// [#TABLE_STEP_ALLOWANCE], a cumulative bound under which one lookup may step past more.
    static ExtractedLiterals extractLiteralSet(Regexp expression, long maximumRetainedBytes)
    {
        // The compiler reads a class as bytes when the whole program is Latin-1, so this does too.
        boolean latin1Program = (expression.parseFlags() & LATIN1) != 0;
        return extractLiteralAlternatives(expression, true, latin1Program, maximumRetainedBytes);
    }

    /// Adds the literals [#extractLiteralSet] would list for `expression` to a trie whose states
    /// retain at most `maximumRetainedBytes`, one at a time in the same order, and returns the
    /// trie, or `null` where the extraction would return `null`, list at most
    /// [#MAXIMUM_ALTERNATIVES] literals or an empty one, or the trie outgrows its budget. It
    /// applies the extraction's bounds to what the extraction would list, but keeps only the
    /// trie and a frame per nesting level, so it serves an expression whose extraction passed its
    /// memory bound.
    static LiteralSetSpanMatcher.Trie spellLiteralSet(Regexp expression, long maximumRetainedBytes)
    {
        return spell(expression, maximumRetainedBytes, false);
    }

    /// Spells every literal [#spellLiteralSet] would, also those that pass through an earlier
    /// literal's match state, into a trie that marks each literal's state with its length and
    /// is only walked, never searched, or returns `null` where [#spellLiteralSet] would. A full
    /// match of such a set can still end at a literal the search never reports, as `samwise`
    /// matches all of itself in `sam|samwise`.
    static LiteralSetSpanMatcher.Trie spellEveryLiteral(Regexp expression, long maximumRetainedBytes)
    {
        return spell(expression, maximumRetainedBytes, true);
    }

    private static LiteralSetSpanMatcher.Trie spell(Regexp expression, long maximumRetainedBytes, boolean spellsEveryLiteral)
    {
        LiteralSpeller speller = speller(expression, maximumRetainedBytes, spellsEveryLiteral);
        return speller.spell(expression) ? speller.trie : null;
    }

    /// Spells `expression` as [#spellEveryLiteral] does with `spellsEveryLiteral`, and otherwise
    /// as [#spellLiteralSet] does, and returns the trie whether or not the spelling finished,
    /// so a rejected compile can tell whether the budget stopped it.
    static SpellAttempt attemptSpelling(Regexp expression, long maximumRetainedBytes, boolean spellsEveryLiteral)
    {
        LiteralSpeller speller = speller(expression, maximumRetainedBytes, spellsEveryLiteral);
        boolean spelled = speller.spell(expression);
        return new SpellAttempt(speller.trie, spelled);
    }

    private static LiteralSpeller speller(Regexp expression, long maximumRetainedBytes, boolean spellsEveryLiteral)
    {
        boolean latin1Program = (expression.parseFlags() & LATIN1) != 0;
        return new LiteralSpeller(new LiteralSetSpanMatcher.Trie(MAXIMUM_LITERAL_BYTES, maximumRetainedBytes), latin1Program, spellsEveryLiteral);
    }

    /// The trie a spelling built, finished where `spelled`.
    record SpellAttempt(LiteralSetSpanMatcher.Trie trie, boolean spelled) {}

    /// Expands `expression` into its literals in leftmost-first priority order: a concatenation
    /// lists each prefix choice's literals before the next prefix choice's. The trie takes literal
    /// strings only, at most [#MAXIMUM_ALTERNATIVES]; a large set also takes empty matches and
    /// small classes, and concatenations whose only choice of several literals ends them, up to
    /// [#MAXIMUM_LITERAL_BYTES], and the whole extraction stops past [#MAXIMUM_EXTRACTION_WORK]
    /// or nesting more than [#MAXIMUM_ANALYSIS_DEPTH] levels deep. The walk keeps its nesting on
    /// an explicit stack, so a deeply nested pattern cannot overflow the thread's stack.
    ///
    /// A large set's extraction also stops once what it builds passes
    /// [#EXTRACTION_BUDGET_MULTIPLE] times `maximumRetainedBytes`. The trie's charges no bytes and
    /// stops instead at the first literal longer than [#MAXIMUM_TRIE_LITERAL_BYTES], which
    /// [#analyze] would reject: it skips nothing, every finished child expansion holds a
    /// literal, and concatenation and alternation only lengthen or keep a literal, so that one
    /// reaches the end. Each list then holds at most [#MAXIMUM_ALTERNATIVES] literals of at most
    /// that length.
    ///
    /// Where a large set's alternation ends the pattern, it skips each literal that equals or
    /// extends an earlier one, which a leftmost-first search never reports and which
    /// [LiteralSetSpanMatcher]'s build drops. Followed by more text, such as `(?:|a)b` in `ab` or
    /// `(?:ab|abc)d` in `abcd`, a later alternative can still match, so a choice there keeps every
    /// literal.
    private static ExtractedLiterals extractLiteralAlternatives(Regexp expression, boolean largeSet, boolean latin1Program, long maximumRetainedBytes)
    {
        ExtractionCharges charges = new ExtractionCharges(maximumRetainedBytes > Long.MAX_VALUE / EXTRACTION_BUDGET_MULTIPLE ? Long.MAX_VALUE : maximumRetainedBytes * EXTRACTION_BUDGET_MULTIPLE);
        Deque<Frame> stack = new ArrayDeque<>();
        stack.addLast(new Frame(expression, true, largeSet, latin1Program, charges));
        while (true) {
            Frame frame = stack.getLast();
            if (frame.literals == null) {
                return charges.bytesLeft < 0 || !charges.tableStepsWithinBound() ? ExtractedLiterals.OVER_BUDGET : null;
            }
            Regexp child = frame.nextChild(charges);
            if (frame.literals == null) {
                return null;
            }
            if (child != null) {
                // The new frame's depth is the number of frames enclosing it.
                if (stack.size() >= MAXIMUM_ANALYSIS_DEPTH) {
                    return null;
                }
                stack.addLast(new Frame(child, frame.childEndsPattern(), largeSet, latin1Program, charges));
                continue;
            }

            stack.removeLast();
            if (stack.isEmpty()) {
                return new ExtractedLiterals(frame.literals, frame.literals.size() + frame.skippedLiteralCount);
            }
            stack.getLast().acceptChild(frame, largeSet, charges);
        }
    }

    private static List<byte[]> literal(Regexp expression, int[] runes, boolean largeSet)
    {
        if ((expression.parseFlags() & (FOLD_CASE | FULL_CASE_FOLD)) != 0) {
            return null;
        }
        // Every rune spells at least one byte, so a trie literal of too many runes is too long.
        if (!largeSet && runes.length > MAXIMUM_TRIE_LITERAL_BYTES) {
            return null;
        }
        byte[] literal = Regexp.convertRunesToBytes((expression.parseFlags() & LATIN1) != 0, runes);
        return !largeSet && literal.length > MAXIMUM_TRIE_LITERAL_BYTES ? null : List.of(literal);
    }

    // The ranges list every member, whatever CharClass.foldsAscii tells the compiler.
    // Distinct code points never match at the same position, so the members need no order.
    private static List<byte[]> characterClass(Regexp expression, boolean latin1Program)
    {
        CharClass characterClass = expression.charClass();
        if ((expression.parseFlags() & (FOLD_CASE | FULL_CASE_FOLD)) != 0 || characterClass.runeCount() > MAXIMUM_CLASS_RUNES) {
            return null;
        }
        boolean latin1 = latin1Program || (expression.parseFlags() & LATIN1) != 0;
        List<byte[]> members = new ArrayList<>(characterClass.runeCount());
        for (RuneRange range : characterClass.rangeView()) {
            for (int rune = range.low(); rune <= range.high(); rune++) {
                if (latin1 ? rune > 0xFF : (rune >= Character.MIN_SURROGATE && rune <= Character.MAX_SURROGATE)) {
                    return null;
                }
                members.add(Regexp.convertRunesToBytes(latin1, new int[] {rune}));
            }
        }
        return members;
    }

    /// Appends each literal of a concatenation's next child to every prefix the concatenation's
    /// earlier children spell, or returns `null`. The bounds count the `skippedCount` literals of
    /// `skippedBytes` bytes the child skipped.
    private static List<byte[]> concatenate(List<byte[]> prefixes, List<byte[]> suffixes, long skippedCount, long skippedBytes, boolean largeSet, ExtractionCharges charges)
    {
        long suffixBytes = totalLength(suffixes) + skippedBytes;
        if (largeSet) {
            // A large set takes the factored prefixes of a dictionary, such as
            // word(?:s|ing|ed), whose choice of several literals ends the concatenation, so
            // the literals share the pattern's prefixes and the automaton stays within the
            // pattern's size. A choice before more text, as in (?:a|b)c or [ab][cd],
            // expands to every combination. A child of empty literals only, such as the
            // (?:|) of a duplicate word, adds nothing in either position.
            if (suffixBytes == 0) {
                return prefixes;
            }
            if (prefixes.size() > 1) {
                return null;
            }
        }
        else if ((long) prefixes.size() * suffixes.size() > MAXIMUM_ALTERNATIVES || maximumLength(prefixes) + maximumLength(suffixes) > MAXIMUM_TRIE_LITERAL_BYTES) {
            return null;
        }
        // Measured only now, so a skipped child does not rescan the prefixes.
        long combinedBytes = totalLength(prefixes) * (suffixes.size() + skippedCount) + suffixBytes * prefixes.size();
        if ((largeSet && combinedBytes > MAXIMUM_LITERAL_BYTES) ||
                !charges.chargeWork(combinedBytes) ||
                (largeSet && !charges.chargeBytes(concatenatedBytes(prefixes, suffixes)))) {
            return null;
        }

        List<byte[]> combined = new ArrayList<>(prefixes.size() * suffixes.size());
        for (byte[] prefix : prefixes) {
            for (byte[] suffix : suffixes) {
                byte[] literal = Arrays.copyOf(prefix, prefix.length + suffix.length);
                System.arraycopy(suffix, 0, literal, prefix.length, suffix.length);
                combined.add(literal);
            }
        }
        return combined;
    }

    private static long maximumLength(List<byte[]> literals)
    {
        long length = 0;
        for (byte[] literal : literals) {
            length = Math.max(length, literal.length);
        }
        return length;
    }

    // Each literal concatenate builds and its list entry, rounded as alternate refunds a skipped
    // literal.
    private static long concatenatedBytes(List<byte[]> prefixes, List<byte[]> suffixes)
    {
        long bytes = 0;
        for (byte[] prefix : prefixes) {
            for (byte[] suffix : suffixes) {
                bytes += REFERENCE_BYTES + SizeOf.sizeOfByteArray(prefix.length + suffix.length);
            }
        }
        return bytes;
    }

    static long totalLength(List<byte[]> literals)
    {
        long length = 0;
        for (byte[] literal : literals) {
            length += literal.length;
        }
        return length;
    }

    /// Returns the number of non-overlapping matches in `input`. Matches are never empty, so
    /// each search resumes where the previous match ended, on the route the previous one chose.
    final long count(Slice input)
    {
        return this instanceof ExactMatcher exact ? exact.count(input, false, null) : ((FoldedMatcher) this).count(input, false, null);
    }

    /// Returns [#count(Slice)], or [#DECLINED] when the filter's stops are dense at the start.
    final long countOrDecline(Slice input)
    {
        return this instanceof ExactMatcher exact ? exact.count(input, true, null) : ((FoldedMatcher) this).count(input, true, null);
    }

    /// Returns [#count(Slice)], adding the routes it reads to `diagnostics`.
    final long countForDiagnostics(Slice input, RouteDiagnostics diagnostics)
    {
        requireNonNull(diagnostics, "diagnostics is null");
        return this instanceof ExactMatcher exact ? exact.count(input, false, diagnostics) : ((FoldedMatcher) this).count(input, false, diagnostics);
    }

    /// Returns [#countOrDecline(Slice)], adding the routes it reads to `diagnostics`.
    final long countOrDeclineForDiagnostics(Slice input, RouteDiagnostics diagnostics)
    {
        requireNonNull(diagnostics, "diagnostics is null");
        return this instanceof ExactMatcher exact ? exact.count(input, true, diagnostics) : ((FoldedMatcher) this).count(input, true, diagnostics);
    }

    final long findSpan(Slice input, int start)
    {
        return this instanceof ExactMatcher exact
                ? exact.findSpan(input, start, input.length(), null, false, null)
                : ((FoldedMatcher) this).findSpan(input, start, input.length(), null, false, null);
    }

    /// Returns the span of the first match at or after `start`, or [Dfa#SEARCH_NO_MATCH]. A
    /// `cursor`, which may be null, carries the route from one search of `input` to the next.
    final long findSpan(Slice input, int start, Cursor cursor)
    {
        return this instanceof ExactMatcher exact
                ? exact.findSpan(input, start, input.length(), cursor, false, null)
                : ((FoldedMatcher) this).findSpan(input, start, input.length(), cursor, false, null);
    }

    /// Returns the span of the first match lying entirely in `[start, end)` of `input`, or
    /// [Dfa#SEARCH_NO_MATCH]; the caller checks that `0 <= start <= end <= input.length()`. The
    /// matcher stops at `end`, so where a preferred alternative would run past it, a shorter
    /// one that ends within it matches, as the general engine reports for the region: `ab` of
    /// `abc|x|ab` in a region ending after `ab`.
    final long findSpan(Slice input, int start, int end)
    {
        return this instanceof ExactMatcher exact ? exact.findSpan(input, start, end, null, false, null) : ((FoldedMatcher) this).findSpan(input, start, end, null, false, null);
    }

    /// Returns [#findSpan(Slice,int,Cursor)] from the start of `input` without a cursor, or
    /// [#DECLINED] when the filter's stops are dense at the start.
    final long findSpanOrDecline(Slice input)
    {
        return this instanceof ExactMatcher exact
                ? exact.findSpan(input, 0, input.length(), null, true, null)
                : ((FoldedMatcher) this).findSpan(input, 0, input.length(), null, true, null);
    }

    /// Returns [#findSpan(Slice,int,Cursor)], adding the routes it reads to `diagnostics`.
    final long findSpanForDiagnostics(Slice input, int start, Cursor cursor, RouteDiagnostics diagnostics)
    {
        requireNonNull(diagnostics, "diagnostics is null");
        return this instanceof ExactMatcher exact
                ? exact.findSpan(input, start, input.length(), cursor, false, diagnostics)
                : ((FoldedMatcher) this).findSpan(input, start, input.length(), cursor, false, diagnostics);
    }

    /// Returns [#findSpanOrDecline(Slice)], adding the routes it reads to `diagnostics`.
    final long findSpanOrDeclineForDiagnostics(Slice input, RouteDiagnostics diagnostics)
    {
        requireNonNull(diagnostics, "diagnostics is null");
        return this instanceof ExactMatcher exact
                ? exact.findSpan(input, 0, input.length(), null, true, diagnostics)
                : ((FoldedMatcher) this).findSpan(input, 0, input.length(), null, true, diagnostics);
    }

    // A route chosen with at least 64 KiB left holds for the DFA's recheck distance, and one
    // chosen with less holds to the end. Without a filter nothing is sampled, and the root-table
    // route is simply chosen again every 64 KiB.
    private static int routeEnd(int position, int inputEnd)
    {
        if (inputEnd - position < Dfa.MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES) {
            return inputEnd;
        }
        return position + Dfa.BYTE_SCAN_PRODUCTIVITY_RECHECK_BYTES;
    }

    /// An alternation of exact literals, searched with a trie and, where the literals admit a
    /// selective one, a start literal filter.
    static final class ExactMatcher
            extends LiteralAlternationSpanMatcher
    {
        private static final int INSTANCE_SIZE = SizeOf.instanceSize(ExactMatcher.class);

        private final short[] rootStateByLeadingByte;
        private final short[] firstEdgeByState;
        private final short[] nextEdge;
        private final short[] targetState;
        private final byte[] edgeByte;
        private final short[] alternativeByState;
        // The literals that begin with each leading byte, in pattern order, which a filter stop
        // compares directly instead of walking the trie. Null when there is no start literal filter,
        // whose stops are its only reader.
        private final byte[][][] literalsByLeadingByte;
        // Null without the Vector API, or when the literals admit no selective filter.
        private final StartLiteralFilter startLiteralFilter;
        // Counts filter stops for the diagnostics, only when tests turn on route counts: every
        // thread searching a compiled pattern shares this matcher. The held-route check counts
        // each search's own stops instead.
        private long filterStopCount;

        private ExactMatcher(
                short[] rootStateByLeadingByte,
                short[] firstEdgeByState,
                short[] nextEdge,
                short[] targetState,
                byte[] edgeByte,
                short[] alternativeByState,
                byte[][][] literalsByLeadingByte,
                StartLiteralFilter startLiteralFilter)
        {
            this.rootStateByLeadingByte = rootStateByLeadingByte;
            this.firstEdgeByState = firstEdgeByState;
            this.nextEdge = nextEdge;
            this.targetState = targetState;
            this.edgeByte = edgeByte;
            this.alternativeByState = alternativeByState;
            this.literalsByLeadingByte = literalsByLeadingByte;
            this.startLiteralFilter = startLiteralFilter;
        }

        @Override
        long estimatedRetainedSize()
        {
            return INSTANCE_SIZE +
                    SizeOf.sizeOf(rootStateByLeadingByte) +
                    SizeOf.sizeOf(firstEdgeByState) +
                    SizeOf.sizeOf(nextEdge) +
                    SizeOf.sizeOf(targetState) +
                    SizeOf.sizeOf(edgeByte) +
                    SizeOf.sizeOf(alternativeByState) +
                    retainedSizeByLeadingByte(literalsByLeadingByte) +
                    (startLiteralFilter == null ? 0 : startLiteralFilter.estimatedRetainedSize());
        }

        @Override
        boolean usesStartLiteralFilter(int length)
        {
            return startLiteralFilter != null && length >= MINIMUM_START_LITERAL_FILTER_BYTES;
        }

        @Override
        StartLiteralFilter startLiteralFilterForDiagnostics()
        {
            return startLiteralFilter;
        }

        @Override
        long filterStopCountForDiagnostics()
        {
            checkFilterStopsCounted();
            return filterStopCount;
        }

        // A call site is smaller bytecode than a field increment, so counting grows no caller.
        private void countFilterStop()
        {
            if (Dfa.COUNTS_ROUTES) {
                filterStopCount++;
            }
        }

        // A null `diagnostics`, as every production search passes, counts nothing.
        private long count(Slice input, boolean declinesDenseStart, RouteDiagnostics diagnostics)
        {
            requireNonNull(input, "input is null");
            byte[] bytes = input.byteArray();
            int inputOffset = input.byteArrayOffset();
            int inputEnd = inputOffset + input.length();
            long count = 0;
            int position = inputOffset;
            if (input.length() < MINIMUM_START_LITERAL_FILTER_BYTES) {
                // As in findSpan, a short input reads the root table to its end.
                while (position < inputEnd) {
                    long span = findSpanInRootTable(bytes, position, inputEnd, inputOffset, inputEnd);
                    if (span < 0) {
                        break;
                    }
                    count++;
                    position = inputOffset + (int) span;
                }
                return count;
            }
            int routeEnd = position;
            boolean readsRootTable = false;
            int routeStopCount = 0;
            while (position < inputEnd) {
                if (position >= routeEnd) {
                    if (position == inputOffset || !holdsFilterRoute(readsRootTable, routeStopCount)) {
                        readsRootTable = readsRootTable(bytes, position, inputEnd, diagnostics);
                    }
                    if (declinesDenseStart && position == inputOffset && readsRootTable && startLiteralFilter != null) {
                        return DECLINED;
                    }
                    routeEnd = routeEnd(position, inputEnd);
                    routeStopCount = 0;
                }
                long span = findSpanInRoute(bytes, position, routeEnd, readsRootTable, inputOffset, inputEnd, diagnostics);
                if (span == Dfa.SEARCH_NO_MATCH) {
                    position = routeEnd;
                    continue;
                }
                // Every other return made a filter stop, except a root-table match, which counts
                // too but decides nothing: a root-table route never holds, and a filter route reads
                // the root table only in the input's last 64 bytes, where no later route samples.
                routeStopCount++;
                if (span < 0) {
                    position = ~(int) span;
                    continue;
                }
                count++;
                position = inputOffset + (int) span;
            }
            return count;
        }

        // The search reads nothing at or past `end`, so `inputEnd` below is the region end: the
        // routes, the filter's scan, and the trie walk all stop at it, as they stop at the input end
        // of a full-input search. A null `diagnostics`, as every production search passes, counts
        // nothing.
        private long findSpan(Slice input, int start, int end, Cursor cursor, boolean declinesDenseStart, RouteDiagnostics diagnostics)
        {
            requireNonNull(input, "input is null");
            if (start < 0 || start > end) {
                return Dfa.SEARCH_NO_MATCH;
            }

            byte[] bytes = input.byteArray();
            int inputOffset = input.byteArrayOffset();
            int inputEnd = inputOffset + end;
            int firstPosition = inputOffset + start;
            // With fewer than MINIMUM_START_LITERAL_FILTER_BYTES left, every route reads the root table
            // to the input end: the filter loop needs that many bytes left, the productivity sample and
            // the decline need 64 KiB, and a route chosen with less than 64 KiB left holds to the end.
            // Going straight to the root-table loop skips choosing the route. Such a search neither
            // reads nor updates the cursor, whose route stays within the input.
            if (inputEnd - firstPosition < MINIMUM_START_LITERAL_FILTER_BYTES) {
                return findSpanInRootTable(bytes, firstPosition, inputEnd, inputOffset, inputEnd);
            }
            int position = firstPosition;
            int routeEnd = cursor == null ? position : inputOffset + cursor.routeEnd;
            boolean readsRootTable = cursor == null || cursor.readsRootTable;
            int routeStopCount = cursor == null ? 0 : cursor.routeStopCount;
            long span = Dfa.SEARCH_NO_MATCH;
            while (position < inputEnd) {
                if (position >= routeEnd) {
                    if (!holdsFilterRoute(readsRootTable, routeStopCount)) {
                        readsRootTable = readsRootTable(bytes, position, inputEnd, diagnostics);
                    }
                    if (declinesDenseStart && position == firstPosition && readsRootTable && startLiteralFilter != null) {
                        return DECLINED;
                    }
                    routeEnd = routeEnd(position, inputEnd);
                    routeStopCount = 0;
                }
                span = findSpanInRoute(bytes, position, routeEnd, readsRootTable, inputOffset, inputEnd, diagnostics);
                if (span == Dfa.SEARCH_NO_MATCH) {
                    position = routeEnd;
                    continue;
                }
                // Counted as in count.
                routeStopCount++;
                if (span >= 0) {
                    break;
                }
                position = ~(int) span;
                // A search that ends after a stop without a match reports none.
                span = Dfa.SEARCH_NO_MATCH;
            }
            if (cursor != null) {
                cursor.routeEnd = routeEnd - inputOffset;
                cursor.readsRootTable = readsRootTable;
                cursor.routeStopCount = routeStopCount;
            }
            return span;
        }

        // A filter route whose stops stayed sparse keeps the filter for the next route instead of
        // sampling again. Each sample reads windows spread across the rest of the input, often cold,
        // and a long input paid one every recheck distance. `routeStopCount` is the stops the route
        // made in this search, or in the searches of the cursor that carries it, never those of
        // other searches sharing the matcher. A search with no route yet starts as if on the root
        // table, which never holds, so its first route samples; reusing that flag rather than
        // adding one keeps findSpan within C2's FreqInlineSize of 325 bytecode bytes.
        private static boolean holdsFilterRoute(boolean readsRootTable, int routeStopCount)
        {
            return !readsRootTable && routeStopCount * HELD_ROUTE_MINIMUM_BYTES_PER_STOP < Dfa.BYTE_SCAN_PRODUCTIVITY_RECHECK_BYTES;
        }

        // True when the route from `position` reads every byte through the root table rather than
        // skipping to the filter's stops: there is no filter, or at least 64 KiB remain and the DFA's
        // start-scan productivity check finds the stops too dense in its sample. Near misses that
        // the filter passes and the literal compare rejects, such as ERxO under ERROR|WARN|FATAL
        // every fifth byte, make frequent filter stops, and restarting the vector scan at each costs
        // more than one root-table lookup per byte.
        private boolean readsRootTable(byte[] bytes, int position, int inputEnd, RouteDiagnostics diagnostics)
        {
            if (startLiteralFilter == null) {
                return true;
            }
            if (inputEnd - position < Dfa.MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES) {
                return false;
            }
            if (diagnostics != null) {
                // Each check reads at most this many positions.
                diagnostics.sampledPositionCount += Dfa.BYTE_SCAN_SAMPLE_SIZE;
            }
            return !Dfa.isByteCandidateScanProductive(
                    null,
                    startLiteralFilter,
                    null,
                    bytes,
                    position,
                    inputEnd,
                    Dfa.MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES,
                    Dfa.BYTE_SCAN_SAMPLE_CANDIDATE_LIMIT);
        }

        // Searches the route from `position`, which lies before `routeEnd`, either to its next filter
        // stop, comparing the stop's literals, or through the root table to the route end. Returns
        // the span of the match found, Dfa.SEARCH_NO_MATCH when the route holds no further stop or
        // match, or, for a stop whose literals do not match, the complement of the position after it,
        // at most the route end, which is below Dfa.SEARCH_NO_MATCH. Checking a match may read to the
        // input end. Returning at each stop lets the caller count the stops in its own state, since
        // every thread searching the pattern shares this matcher. The root-table trie walk keeps this
        // one call site: C2 inlined a copy at each of two sites, which grew this method past its
        // inline size limit, and its callers then called it rather than inlining it.
        private long findSpanInRoute(byte[] bytes, int position, int routeEnd, boolean readsRootTable, int inputOffset, int inputEnd, RouteDiagnostics diagnostics)
        {
            // The filter accepts every position where a literal starts, so the positions it skips
            // hold no match, and comparing the literals at each stop decides it. Every literal reaches
            // the filter's last position, so a scan that reads that far past the route end finds
            // every stop before the route end.
            if (!readsRootTable && usesStartLiteralFilter(inputEnd - position)) {
                position = VectorByteSetScanner.findLiteralSet(bytes, position, Math.min(inputEnd, routeEnd + startLiteralFilter.thirdOffset()) - position, startLiteralFilter);
                if (position < 0) {
                    return Dfa.SEARCH_NO_MATCH;
                }
                countFilterStop();
                long span = findSpanAtStop(bytes, position, inputOffset, inputEnd);
                // The filter's scan can stop past the route end, after which the next route starts
                // at the route end, as when the scan finds no stop.
                return span >= 0 ? span : ~(long) Math.min(position + 1, routeEnd);
            }
            long span = findSpanInRootTable(bytes, position, routeEnd, inputOffset, inputEnd);
            if (diagnostics != null) {
                int readEnd = span < 0 ? routeEnd : inputOffset + (int) (span >>> 32) + 1;
                diagnostics.rootTablePositionCount += readEnd - position;
            }
            return span;
        }

        // Returns the span of the match that starts at `position`, or Dfa.SEARCH_NO_MATCH: the first
        // literal in pattern order that begins with the position's byte and matches there, which is
        // the alternative the trie walk would pick.
        private long findSpanAtStop(byte[] bytes, int position, int inputOffset, int inputEnd)
        {
            byte[][] candidates = literalsByLeadingByte[bytes[position] & 0xFF];
            if (candidates == null) {
                return Dfa.SEARCH_NO_MATCH;
            }
            int remaining = inputEnd - position;
            for (byte[] literal : candidates) {
                if (literal.length <= remaining && Arrays.mismatch(bytes, position, position + literal.length, literal, 0, literal.length) < 0) {
                    int matchStart = position - inputOffset;
                    return ((long) matchStart << 32) | ((matchStart + literal.length) & 0xFFFF_FFFFL);
                }
            }
            return Dfa.SEARCH_NO_MATCH;
        }

        // Returns the span of the first match that starts in `[position, routeEnd)`, or
        // Dfa.SEARCH_NO_MATCH. Each position's byte is looked up in the root table, and the trie is
        // walked from each one the table admits for the earliest alternative in pattern order.
        // Walking in this loop keeps the child lookup inlined: C2 compiled a separate walk method
        // into its callers before that method's own profile matured, and without call counts it left
        // the child lookup a call.
        private long findSpanInRootTable(byte[] bytes, int position, int routeEnd, int inputOffset, int inputEnd)
        {
            for (; position < routeEnd; position++) {
                int state = rootStateByLeadingByte[bytes[position] & 0xFF];
                if (state == 0) {
                    continue;
                }
                int bestAlternative = alternativeByState[state];
                int bestEnd = bestAlternative < 0 ? -1 : position + 1;
                int inputIndex = position + 1;
                while (inputIndex < inputEnd) {
                    int nextState = findChild(state, bytes[inputIndex], firstEdgeByState, nextEdge, targetState, edgeByte);
                    if (nextState == 0) {
                        break;
                    }
                    state = nextState;
                    inputIndex++;
                    int alternative = alternativeByState[state];
                    if (alternative >= 0 && (bestAlternative < 0 || alternative < bestAlternative)) {
                        bestAlternative = alternative;
                        bestEnd = inputIndex;
                    }
                }
                if (bestAlternative >= 0) {
                    int matchStart = position - inputOffset;
                    int matchEnd = bestEnd - inputOffset;
                    return ((long) matchStart << 32) | (matchEnd & 0xFFFF_FFFFL);
                }
            }
            return Dfa.SEARCH_NO_MATCH;
        }

        private static int findChild(
                int state,
                byte value,
                short[] firstEdgeByState,
                short[] nextEdge,
                short[] targetState,
                byte[] edgeByte)
        {
            int edge = firstEdgeByState[state];
            while (edge != 0) {
                int edgeIndex = edge - 1;
                if (edgeByte[edgeIndex] == value) {
                    return targetState[edgeIndex];
                }
                edge = nextEdge[edgeIndex];
            }
            return 0;
        }
    }

    /// An alternation of ASCII literals whose letters fold case. It takes [ExactMatcher]'s routes
    /// and, in place of the trie walk, compares the literals that begin with a byte under their
    /// case masks.
    static final class FoldedMatcher
            extends LiteralAlternationSpanMatcher
    {
        private static final int INSTANCE_SIZE = SizeOf.instanceSize(FoldedMatcher.class);

        // The literals that begin with each leading byte, in pattern order, a folded literal
        // listed under the leading byte of each of its cases.
        private final byte[][][] literalsByLeadingByte;
        // The case masks of literalsByLeadingByte's literals, indexed the same way: a byte matches
        // when, with its mask's bits set, it equals the literal's byte.
        private final byte[][][] literalMasksByLeadingByte;
        private final StartLiteralFilter startLiteralFilter;
        // Counts filter stops for the diagnostics as the exact matcher's counter does.
        private long filterStopCount;

        private FoldedMatcher(byte[][][] literalsByLeadingByte, byte[][][] literalMasksByLeadingByte, StartLiteralFilter startLiteralFilter)
        {
            this.literalsByLeadingByte = requireNonNull(literalsByLeadingByte, "literalsByLeadingByte is null");
            this.literalMasksByLeadingByte = requireNonNull(literalMasksByLeadingByte, "literalMasksByLeadingByte is null");
            this.startLiteralFilter = requireNonNull(startLiteralFilter, "startLiteralFilter is null");
        }

        @Override
        long estimatedRetainedSize()
        {
            return INSTANCE_SIZE +
                    retainedSizeByLeadingByte(literalsByLeadingByte) +
                    retainedSizeByLeadingByte(literalMasksByLeadingByte) +
                    startLiteralFilter.estimatedRetainedSize();
        }

        @Override
        boolean usesStartLiteralFilter(int length)
        {
            return length >= MINIMUM_START_LITERAL_FILTER_BYTES;
        }

        @Override
        StartLiteralFilter startLiteralFilterForDiagnostics()
        {
            return startLiteralFilter;
        }

        @Override
        long filterStopCountForDiagnostics()
        {
            checkFilterStopsCounted();
            return filterStopCount;
        }

        // A call site is smaller bytecode than a field increment, so counting grows no caller.
        private void countFilterStop()
        {
            if (Dfa.COUNTS_ROUTES) {
                filterStopCount++;
            }
        }

        // The route methods from here through findSpanInRoute copy ExactMatcher's, whose comments
        // explain them, including the stop counts, the short search that leaves the cursor alone,
        // and the filter scan past the route end. They drop its checks for a missing filter,
        // since this matcher always has one, and call this matcher's compares.
        private long count(Slice input, boolean declinesDenseStart, RouteDiagnostics diagnostics)
        {
            requireNonNull(input, "input is null");
            byte[] bytes = input.byteArray();
            int inputOffset = input.byteArrayOffset();
            int inputEnd = inputOffset + input.length();
            long count = 0;
            int position = inputOffset;
            if (input.length() < MINIMUM_START_LITERAL_FILTER_BYTES) {
                while (position < inputEnd) {
                    long span = findSpanInRootTable(bytes, position, inputEnd, inputOffset, inputEnd);
                    if (span < 0) {
                        break;
                    }
                    count++;
                    position = inputOffset + (int) span;
                }
                return count;
            }
            int routeEnd = position;
            boolean readsRootTable = false;
            int routeStopCount = 0;
            while (position < inputEnd) {
                if (position >= routeEnd) {
                    if (position == inputOffset || !holdsFilterRoute(readsRootTable, routeStopCount)) {
                        readsRootTable = readsRootTable(bytes, position, inputEnd, diagnostics);
                    }
                    if (declinesDenseStart && position == inputOffset && readsRootTable) {
                        return DECLINED;
                    }
                    routeEnd = routeEnd(position, inputEnd);
                    routeStopCount = 0;
                }
                long span = findSpanInRoute(bytes, position, routeEnd, readsRootTable, inputOffset, inputEnd, diagnostics);
                if (span == Dfa.SEARCH_NO_MATCH) {
                    position = routeEnd;
                    continue;
                }
                routeStopCount++;
                if (span < 0) {
                    position = ~(int) span;
                    continue;
                }
                count++;
                position = inputOffset + (int) span;
            }
            return count;
        }

        private long findSpan(Slice input, int start, int end, Cursor cursor, boolean declinesDenseStart, RouteDiagnostics diagnostics)
        {
            requireNonNull(input, "input is null");
            if (start < 0 || start > end) {
                return Dfa.SEARCH_NO_MATCH;
            }

            byte[] bytes = input.byteArray();
            int inputOffset = input.byteArrayOffset();
            int inputEnd = inputOffset + end;
            int firstPosition = inputOffset + start;
            if (inputEnd - firstPosition < MINIMUM_START_LITERAL_FILTER_BYTES) {
                return findSpanInRootTable(bytes, firstPosition, inputEnd, inputOffset, inputEnd);
            }
            int position = firstPosition;
            int routeEnd = cursor == null ? position : inputOffset + cursor.routeEnd;
            boolean readsRootTable = cursor == null || cursor.readsRootTable;
            int routeStopCount = cursor == null ? 0 : cursor.routeStopCount;
            long span = Dfa.SEARCH_NO_MATCH;
            while (position < inputEnd) {
                if (position >= routeEnd) {
                    if (!holdsFilterRoute(readsRootTable, routeStopCount)) {
                        readsRootTable = readsRootTable(bytes, position, inputEnd, diagnostics);
                    }
                    if (declinesDenseStart && position == firstPosition && readsRootTable) {
                        return DECLINED;
                    }
                    routeEnd = routeEnd(position, inputEnd);
                    routeStopCount = 0;
                }
                span = findSpanInRoute(bytes, position, routeEnd, readsRootTable, inputOffset, inputEnd, diagnostics);
                if (span == Dfa.SEARCH_NO_MATCH) {
                    position = routeEnd;
                    continue;
                }
                routeStopCount++;
                if (span >= 0) {
                    break;
                }
                position = ~(int) span;
                span = Dfa.SEARCH_NO_MATCH;
            }
            if (cursor != null) {
                cursor.routeEnd = routeEnd - inputOffset;
                cursor.readsRootTable = readsRootTable;
                cursor.routeStopCount = routeStopCount;
            }
            return span;
        }

        private static boolean holdsFilterRoute(boolean readsRootTable, int routeStopCount)
        {
            return !readsRootTable && routeStopCount * HELD_ROUTE_MINIMUM_BYTES_PER_STOP < Dfa.BYTE_SCAN_PRODUCTIVITY_RECHECK_BYTES;
        }

        private boolean readsRootTable(byte[] bytes, int position, int inputEnd, RouteDiagnostics diagnostics)
        {
            if (inputEnd - position < Dfa.MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES) {
                return false;
            }
            if (diagnostics != null) {
                diagnostics.sampledPositionCount += Dfa.BYTE_SCAN_SAMPLE_SIZE;
            }
            return !Dfa.isByteCandidateScanProductive(
                    null,
                    startLiteralFilter,
                    null,
                    bytes,
                    position,
                    inputEnd,
                    Dfa.MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES,
                    Dfa.BYTE_SCAN_SAMPLE_CANDIDATE_LIMIT);
        }

        private long findSpanInRoute(byte[] bytes, int position, int routeEnd, boolean readsRootTable, int inputOffset, int inputEnd, RouteDiagnostics diagnostics)
        {
            if (!readsRootTable && usesStartLiteralFilter(inputEnd - position)) {
                position = VectorByteSetScanner.findLiteralSet(bytes, position, Math.min(inputEnd, routeEnd + startLiteralFilter.thirdOffset()) - position, startLiteralFilter);
                if (position < 0) {
                    return Dfa.SEARCH_NO_MATCH;
                }
                countFilterStop();
                long span = findSpanAtStop(bytes, position, inputOffset, inputEnd);
                return span >= 0 ? span : ~(long) Math.min(position + 1, routeEnd);
            }
            long span = findSpanInRootTable(bytes, position, routeEnd, inputOffset, inputEnd);
            if (diagnostics != null) {
                int readEnd = span < 0 ? routeEnd : inputOffset + (int) (span >>> 32) + 1;
                diagnostics.rootTablePositionCount += readEnd - position;
            }
            return span;
        }

        // Returns the span of the match that starts at `position`, or Dfa.SEARCH_NO_MATCH: the
        // first literal in pattern order that begins with the position's byte and matches there.
        private long findSpanAtStop(byte[] bytes, int position, int inputOffset, int inputEnd)
        {
            int leadingByte = bytes[position] & 0xFF;
            byte[][] candidates = literalsByLeadingByte[leadingByte];
            if (candidates == null) {
                return Dfa.SEARCH_NO_MATCH;
            }
            byte[][] masks = literalMasksByLeadingByte[leadingByte];
            int remaining = inputEnd - position;
            for (int index = 0; index < candidates.length; index++) {
                byte[] literal = candidates[index];
                if (literal.length <= remaining && matchesFolded(bytes, position, literal, masks[index])) {
                    int matchStart = position - inputOffset;
                    return ((long) matchStart << 32) | ((matchStart + literal.length) & 0xFFFF_FFFFL);
                }
            }
            return Dfa.SEARCH_NO_MATCH;
        }

        // True when the bytes at `position`, each with its mask's bits set, equal the literal: a
        // folded letter's mask sets the case bit, so either case equals the lower-case letter, as
        // the compiler's folded byte range lowers the byte.
        private static boolean matchesFolded(byte[] bytes, int position, byte[] literal, byte[] masks)
        {
            for (int index = 0; index < literal.length; index++) {
                if ((byte) (bytes[position + index] | masks[index]) != literal[index]) {
                    return false;
                }
            }
            return true;
        }

        // The root table: each position's byte selects the literals to compare there, as at a
        // filter stop.
        private long findSpanInRootTable(byte[] bytes, int position, int routeEnd, int inputOffset, int inputEnd)
        {
            for (; position < routeEnd; position++) {
                if (literalsByLeadingByte[bytes[position] & 0xFF] != null) {
                    long span = findSpanAtStop(bytes, position, inputOffset, inputEnd);
                    if (span >= 0) {
                        return span;
                    }
                }
            }
            return Dfa.SEARCH_NO_MATCH;
        }
    }

    /// What an extraction has charged against its bounds so far.
    private static final class ExtractionCharges
    {
        // The work so far, bounded by MAXIMUM_EXTRACTION_WORK.
        private long work;
        // The bytes the extraction may still build, negative once it has built more.
        private long bytesLeft;
        // The duplicate tables' lookups, and the slots those lookups stepped past their first.
        private long tableLookups;
        private long tableSteps;

        private ExtractionCharges(long bytesLeft)
        {
            this.bytesLeft = bytesLeft;
        }

        private boolean chargeWork(long amount)
        {
            work += amount;
            return work <= MAXIMUM_EXTRACTION_WORK;
        }

        private boolean chargeBytes(long bytes)
        {
            bytesLeft -= bytes;
            return bytesLeft >= 0;
        }

        private void refundBytes(long bytes)
        {
            bytesLeft += bytes;
        }

        private boolean tableStepsWithinBound()
        {
            return tableSteps <= MAXIMUM_TABLE_STEPS_PER_LOOKUP * tableLookups + TABLE_STEP_ALLOWANCE;
        }
    }

    /// An expression the extraction is expanding, with the literals it spells so far: a leaf's
    /// own, or those its walked children combine to.
    private static final class Frame
    {
        // What a duplicate table lookup returns for a slot once its steps pass the bound.
        private static final int NO_SLOT = -1;

        private final Regexp expression;
        // True when nothing follows the expression, in it or in the expressions enclosing it.
        private final boolean endsPattern;
        // A large set's alternation that ends the pattern skips the literals a leftmost-first search
        // never reports.
        private final boolean skipsUnmatchable;
        // The bytes the extraction could still build when the frame began, so the charges of the
        // frame and everything below it are this less the bytes left once it ends.
        private final long bytesLeftAtStart;
        private int nextChildIndex;
        // Null once the expression has no literal expansion within the bounds.
        private List<byte[]> literals;
        // The literals the expansion would list beyond `literals`, which it skipped, and their bytes.
        private long skippedLiteralCount;
        private long skippedLiteralBytes;
        // The bytes of an alternation's literals so far.
        private long literalBytes;
        private boolean holdsEmptyLiteral;
        // An open-addressing table of each listed literal's index plus one, so a zero slot is
        // empty, which finds the earlier literal a skipping alternation's literal equals or extends.
        private int[] literalSlots;

        private Frame(Regexp expression, boolean endsPattern, boolean largeSet, boolean latin1Program, ExtractionCharges charges)
        {
            this.expression = expression;
            this.endsPattern = endsPattern;
            this.skipsUnmatchable = largeSet && endsPattern && expression.op() == RegexpOp.ALTERNATE;
            this.bytesLeftAtStart = charges.bytesLeft;
            List<byte[]> startLiterals = switch (expression.op()) {
                case LITERAL -> literal(expression, new int[] {expression.rune()}, largeSet);
                case LITERAL_STRING -> literal(expression, expression.runes(), largeSet);
                // An alternation starts with no literals, and a concatenation with the empty one.
                case ALTERNATE -> new ArrayList<>();
                case CONCAT -> List.of(new byte[0]);
                case EMPTY_MATCH -> largeSet ? List.of(new byte[0]) : null;
                case CHAR_CLASS -> largeSet ? characterClass(expression, latin1Program) : null;
                default -> null;
            };
            literals = startLiterals != null && (!largeSet || charges.chargeBytes(FRAME_BYTES + builtBytes(startLiterals))) ? startLiterals : null;
        }

        // The literals a frame starts with are new, so it charges their arrays and their list.
        private static long builtBytes(List<byte[]> literals)
        {
            long bytes = SizeOf.sizeOfObjectArray(literals.size());
            for (byte[] literal : literals) {
                bytes += SizeOf.sizeOf(literal);
            }
            return bytes;
        }

        // Returns the next child to expand, or `null` once there is none or the expansion is out of
        // bounds, which leaves no literals.
        private Regexp nextChild(ExtractionCharges charges)
        {
            while (nextChildIndex < expression.childCount()) {
                Regexp child = expression.child(nextChildIndex++);
                // An empty alternative after an empty literal is skipped unbuilt; any other skipped
                // literal is built, and alternate refunds its charge.
                if (skipsUnmatchable && holdsEmptyLiteral && child.op() == RegexpOp.EMPTY_MATCH) {
                    skippedLiteralCount++;
                    if (literals.size() + skippedLiteralCount > MAXIMUM_LITERAL_BYTES || !charges.chargeWork(1)) {
                        literals = null;
                        return null;
                    }
                    continue;
                }
                return child;
            }
            return null;
        }

        // An alternation's children end the pattern where it does, and a concatenation's last
        // child where the concatenation does.
        private boolean childEndsPattern()
        {
            return endsPattern && (expression.op() == RegexpOp.ALTERNATE || nextChildIndex == expression.childCount());
        }

        // Only alternations and concatenations start with literals and have children.
        private void acceptChild(Frame child, boolean largeSet, ExtractionCharges charges)
        {
            long childSkippedCount = child.skippedLiteralCount;
            long childSkippedBytes = child.skippedLiteralBytes;
            literals = switch (expression.op()) {
                case ALTERNATE -> alternate(child, largeSet, charges);
                case CONCAT -> {
                    List<byte[]> combined = concatenate(literals, child.literals, childSkippedCount, childSkippedBytes, largeSet, charges);
                    // Only the last child skips literals. Appended to a single prefix, each spells
                    // a literal the expansion skipped; a child of empty literals adds nothing.
                    if (combined != null && combined != literals) {
                        skippedLiteralCount = literals.size() * childSkippedCount;
                        skippedLiteralBytes = totalLength(literals) * childSkippedCount + childSkippedBytes * literals.size();
                    }
                    yield combined;
                }
                default -> throw new IllegalStateException("unexpected literal parent: " + expression.op());
            };
        }

        /// Adds an alternation child's literals after those of the children before it, or returns
        /// `null`. The bounds count the literals the child skipped and their bytes.
        private List<byte[]> alternate(Frame child, boolean largeSet, ExtractionCharges charges)
        {
            // What the child's frame and everything below it charged, measured before this charges.
            long childBytes = child.bytesLeftAtStart - charges.bytesLeft;
            List<byte[]> childAlternatives = child.literals;
            long childCount = childAlternatives.size() + child.skippedLiteralCount;
            if (largeSet) {
                literalBytes += totalLength(childAlternatives) + child.skippedLiteralBytes;
                if (literals.size() + skippedLiteralCount + childCount > MAXIMUM_LITERAL_BYTES || literalBytes > MAXIMUM_LITERAL_BYTES) {
                    return null;
                }
            }
            else if (literals.size() + childAlternatives.size() > MAXIMUM_ALTERNATIVES) {
                return null;
            }
            if (!charges.chargeWork(childCount) || (largeSet && !charges.chargeBytes(childAlternatives.size() * REFERENCE_BYTES))) {
                return null;
            }
            skippedLiteralCount += child.skippedLiteralCount;
            skippedLiteralBytes += child.skippedLiteralBytes;
            if (!skipsUnmatchable) {
                literals.addAll(childAlternatives);
                return literals;
            }
            if (!reserveLiteralSlots(literals.size() + childAlternatives.size(), charges)) {
                return null;
            }
            // A skipped literal's array and list entry are garbage at once, and so is everything
            // the child built when it lists nothing more.
            int skipped = 0;
            long skippedBytes = 0;
            for (byte[] literal : childAlternatives) {
                if (!addUnlessExtendingEarlier(literal, charges)) {
                    skipped++;
                    skippedBytes += SizeOf.sizeOf(literal) + REFERENCE_BYTES;
                }
                if (!charges.tableStepsWithinBound()) {
                    return null;
                }
            }
            charges.refundBytes(skipped == childAlternatives.size() ? childBytes + skipped * REFERENCE_BYTES : skippedBytes);
            return literals;
        }

        // Keeps the duplicate table at most half full once it holds `literalCount` literals.
        private boolean reserveLiteralSlots(int literalCount, ExtractionCharges charges)
        {
            if (literalSlots != null && literalCount * 2L <= literalSlots.length) {
                return true;
            }
            int slotCount = Math.max(MINIMUM_DUPLICATE_TABLE_SLOTS, Integer.highestOneBit(literalCount) * 4);
            if (!charges.chargeBytes(SizeOf.sizeOfIntArray(slotCount))) {
                return false;
            }
            literalSlots = new int[slotCount];
            for (int index = 0; index < literals.size(); index++) {
                int slot = emptySlot(literalHash(literals.get(index)), charges);
                if (slot == NO_SLOT) {
                    return false;
                }
                literalSlots[slot] = index + 1;
            }
            return true;
        }

        // Lists `literal` unless it equals or extends an earlier literal, which matches wherever it
        // would and first, as an empty literal does before everything after it. Its trie path
        // passes through the earlier literal's match state. Each prefix's hash is a step of the
        // literal's own, so the check looks up one hash per byte. Returns `false` at once when a
        // lookup passes the bound on table steps.
        private boolean addUnlessExtendingEarlier(byte[] literal, ExtractionCharges charges)
        {
            int hash = 0;
            for (int length = 0; length <= literal.length; length++) {
                if (length > 0) {
                    hash = nextHash(hash, literal[length - 1]);
                }
                if (listsPrefix(literal, length, hash, charges)) {
                    skippedLiteralCount++;
                    skippedLiteralBytes += literal.length;
                    return false;
                }
                if (!charges.tableStepsWithinBound()) {
                    return false;
                }
            }
            int slot = emptySlot(hash, charges);
            if (slot == NO_SLOT) {
                return false;
            }
            literals.add(literal);
            literalSlots[slot] = literals.size();
            holdsEmptyLiteral |= literal.length == 0;
            return true;
        }

        // Each lookup counts toward the bound on the slots lookups step past, and stops, finding
        // nothing, once its steps pass that bound.
        private boolean listsPrefix(byte[] literal, int length, int hash, ExtractionCharges charges)
        {
            int slotMask = literalSlots.length - 1;
            int slot = hash & slotMask;
            charges.tableLookups++;
            while (literalSlots[slot] != 0) {
                byte[] listed = literals.get(literalSlots[slot] - 1);
                if (listed.length == length && Arrays.equals(listed, 0, length, literal, 0, length)) {
                    return true;
                }
                slot = (slot + 1) & slotMask;
                charges.tableSteps++;
                if (!charges.tableStepsWithinBound()) {
                    return false;
                }
            }
            return false;
        }

        // Returns `NO_SLOT` once the lookup's steps pass the bound.
        private int emptySlot(int hash, ExtractionCharges charges)
        {
            int slotMask = literalSlots.length - 1;
            int slot = hash & slotMask;
            charges.tableLookups++;
            while (literalSlots[slot] != 0) {
                slot = (slot + 1) & slotMask;
                charges.tableSteps++;
                if (!charges.tableStepsWithinBound()) {
                    return NO_SLOT;
                }
            }
            return slot;
        }

        // Seeded, so a set of colliding literals cannot be worked out before the process picks
        // its seed.
        private static int literalHash(byte[] literal)
        {
            int hash = 0;
            for (byte value : literal) {
                hash = nextHash(hash, value);
            }
            return hash;
        }

        private static int nextHash(int hash, byte value)
        {
            return SeededHash.hash(((long) hash << 8) | (value & 0xFF));
        }
    }

    /// Spells a large set's literals into a trie for [#spellLiteralSet] or [#spellEveryLiteral].
    /// Each literal is a path in the trie from the state its prefix reached, so a concatenation
    /// keeps its prefix as one state: its choice of several literals must end it, so the prefix
    /// before that choice is a single literal. Until a concatenation's child spells a second
    /// literal, the child may be a single literal the next child extends, a choice of empty
    /// literals that adds nothing, or the choice that ends the concatenation. Its first literal's
    /// state is marked as a match while the rest of the child spells, as the extraction lists that
    /// literal before them, and unmarked when the child is not the choice.
    private static final class LiteralSpeller
    {
        // The state of a child after the choice, which spells nothing, or, unless the speller
        // spells every literal, of a literal whose path passed through an earlier literal's match
        // state, which adds nothing to the trie.
        private static final int NO_STATE = -1;

        private final LiteralSetSpanMatcher.Trie trie;
        private final boolean latin1Program;
        // Whether a literal's path continues past an earlier literal's match state.
        private final boolean spellsEveryLiteral;
        private final List<SpellFrame> stack = new ArrayList<>();
        private final byte[] encodedRune = new byte[4];
        private long work;
        private long literalCount;
        private boolean failed;

        private LiteralSpeller(LiteralSetSpanMatcher.Trie trie, boolean latin1Program, boolean spellsEveryLiteral)
        {
            this.trie = trie;
            this.latin1Program = latin1Program;
            this.spellsEveryLiteral = spellsEveryLiteral;
        }

        private boolean spell(Regexp expression)
        {
            enter(expression, LiteralSetSpanMatcher.Trie.START, 0);
            while (!failed && !stack.isEmpty()) {
                SpellFrame frame = stack.getLast();
                if (frame.nextChildIndex < frame.expression.childCount()) {
                    Regexp child = frame.expression.child(frame.nextChildIndex++);
                    if (stack.size() >= MAXIMUM_ANALYSIS_DEPTH) {
                        return false;
                    }
                    frame.startChild();
                    if (!enter(child, frame.childState(), frame.childLength()) && !failed) {
                        frame.endChild();
                    }
                    continue;
                }
                stack.removeLast();
                frame.finish();
                if (!failed && !stack.isEmpty()) {
                    stack.getLast().endChild();
                }
            }
            return !failed && literalCount > MAXIMUM_ALTERNATIVES;
        }

        // Spells a leaf's literals at once, or pushes the frame of an expression with children
        // and returns `true`.
        private boolean enter(Regexp expression, int state, long length)
        {
            switch (expression.op()) {
                case ALTERNATE, CONCAT -> {
                    stack.add(new SpellFrame(expression, stack.size(), state, length));
                    return true;
                }
                case LITERAL -> spellRunes(expression, new int[] {expression.rune()}, state, length);
                case LITERAL_STRING -> spellRunes(expression, expression.runes(), state, length);
                case EMPTY_MATCH -> deliver(stack.size(), state, length);
                case CHAR_CLASS -> {
                    List<byte[]> members = characterClass(expression, latin1Program);
                    if (members == null) {
                        failed = true;
                        break;
                    }
                    for (byte[] member : members) {
                        int memberState = state;
                        for (byte value : member) {
                            memberState = walk(memberState, value);
                        }
                        if (failed) {
                            break;
                        }
                        deliver(stack.size(), memberState, length + member.length);
                    }
                }
                default -> failed = true;
            }
            return false;
        }

        // Encodes the runes as the extraction does, one at a time, so a long literal string needs
        // no copy.
        private void spellRunes(Regexp expression, int[] runes, int state, long length)
        {
            if ((expression.parseFlags() & (FOLD_CASE | FULL_CASE_FOLD)) != 0) {
                failed = true;
                return;
            }
            boolean latin1 = (expression.parseFlags() & LATIN1) != 0;
            for (int rune : runes) {
                if (latin1) {
                    state = walk(state, (byte) rune);
                    length++;
                }
                else {
                    int encodedLength = Utf8.encode(encodedRune, 0, rune);
                    for (int index = 0; index < encodedLength; index++) {
                        state = walk(state, encodedRune[index]);
                    }
                    length += encodedLength;
                }
                // A full trie fails the set at once; past an earlier literal's match, the walk
                // only counts the bytes.
                if (failed) {
                    return;
                }
            }
            deliver(stack.size(), state, length);
        }

        // Like the trie's build, the walk stops at an earlier literal's match, unless the speller
        // spells every literal.
        private int walk(int state, byte value)
        {
            if (state == NO_STATE || (!spellsEveryLiteral && trie.matchLength(state) != 0)) {
                return NO_STATE;
            }
            int next = trie.step(state, value);
            if (next == LiteralSetSpanMatcher.Trie.FULL) {
                failed = true;
                return NO_STATE;
            }
            return next;
        }

        // Hands a literal ending at `state` to the frames below `level`, and adds it to the trie
        // once the expression's own list takes it.
        private void deliver(int level, int state, long length)
        {
            for (int index = level - 1; index >= 0; index--) {
                if (!stack.get(index).accept(state, length)) {
                    return;
                }
            }
            literalCount++;
            if (length == 0) {
                // An empty literal matches everywhere, which the automaton's search does not report.
                failed = true;
            }
            else if (state != NO_STATE) {
                trie.setMatchLength(state, (int) length);
            }
        }

        private boolean chargeCombination(long combinedBytes)
        {
            work += combinedBytes;
            failed |= combinedBytes > MAXIMUM_LITERAL_BYTES || work > MAXIMUM_EXTRACTION_WORK;
            return !failed;
        }

        /// An alternation or concatenation being spelled, with the counts the extraction's bounds
        /// read. Lengths count from the start of the expression's whole literal.
        private final class SpellFrame
        {
            private final Regexp expression;
            private final boolean concatenation;
            // The frame's index on the stack, below which it hands its literals.
            private final int level;
            private final int startState;
            private final long startLength;
            private int nextChildIndex;
            // An alternation's literals so far and their bytes.
            private long literalCount;
            private long literalBytes;
            // A concatenation's prefix: the state and length of its single literal, until the
            // choice of several literals ends it.
            private int prefixState;
            private long prefixLength;
            private boolean hasCompletedChoice;
            // The concatenation's child being spelled: its literals and their bytes so far, and the
            // first one's state, length, empty copies, and the match length its mark replaced.
            private long childLiteralCount;
            private long childBytes;
            private boolean childIsChoice;
            private int firstState;
            private long firstLength;
            private long firstCopies;
            private int replacedMatchLength;

            private SpellFrame(Regexp expression, int level, int startState, long startLength)
            {
                this.expression = expression;
                this.concatenation = expression.op() == RegexpOp.CONCAT;
                this.level = level;
                this.startState = startState;
                this.startLength = startLength;
                this.prefixState = startState;
                this.prefixLength = startLength;
            }

            private int childState()
            {
                if (!concatenation) {
                    return startState;
                }
                return hasCompletedChoice ? NO_STATE : prefixState;
            }

            private long childLength()
            {
                return concatenation ? prefixLength : startLength;
            }

            private void startChild()
            {
                childLiteralCount = 0;
                childBytes = 0;
                childIsChoice = false;
            }

            // Returns whether the literal passes on to the frame below, as an alternation's do.
            private boolean accept(int state, long length)
            {
                if (!concatenation) {
                    literalCount++;
                    literalBytes += length - startLength;
                    work++;
                    failed |= literalCount > MAXIMUM_LITERAL_BYTES || literalBytes > MAXIMUM_LITERAL_BYTES || work > MAXIMUM_EXTRACTION_WORK;
                    return !failed;
                }
                long addedBytes = length - prefixLength;
                childLiteralCount++;
                childBytes += addedBytes;
                if (hasCompletedChoice) {
                    // After the choice of several literals, the extraction gives up on any child
                    // that adds bytes.
                    failed |= addedBytes > 0;
                    return false;
                }
                if (childIsChoice) {
                    return true;
                }
                if (childLiteralCount == 1) {
                    firstState = state;
                    firstLength = length;
                    firstCopies = 1;
                    if (state != NO_STATE) {
                        replacedMatchLength = trie.matchLength(state);
                        trie.setMatchLength(state, (int) length);
                    }
                    return false;
                }
                if (childBytes == 0) {
                    firstCopies++;
                    return false;
                }
                childIsChoice = true;
                for (long copy = 0; copy < firstCopies && !failed; copy++) {
                    deliver(level, firstState, firstLength);
                }
                if (!failed) {
                    deliver(level, state, length);
                }
                return false;
            }

            private void endChild()
            {
                if (!concatenation || hasCompletedChoice) {
                    return;
                }
                long prefixBytes = prefixLength - startLength;
                if (childIsChoice) {
                    hasCompletedChoice = chargeCombination(prefixBytes * childLiteralCount + childBytes);
                    return;
                }
                if (childLiteralCount > 0 && firstState != NO_STATE) {
                    trie.setMatchLength(firstState, replacedMatchLength);
                }
                // A child of empty literals adds nothing; a single literal extends the prefix.
                if (childBytes > 0 && chargeCombination(prefixBytes + childBytes)) {
                    prefixState = firstState;
                    prefixLength = firstLength;
                }
            }

            private void finish()
            {
                if (concatenation && !hasCompletedChoice) {
                    deliver(level, prefixState, prefixLength);
                }
            }
        }
    }

    /// The literals an expansion lists in priority order, and how many it would list counting
    /// those it skipped because a leftmost-first search never reports them. The route is chosen by
    /// that count, so the skipped literals count toward it.
    record ExtractedLiterals(List<byte[]> literals, long literalCount)
    {
        /// What an extraction returns once what it builds passes its memory bound, or its
        /// duplicate tables' lookups step past their bound.
        static final ExtractedLiterals OVER_BUDGET = new ExtractedLiterals(List.of(), 0);
    }

    /// Counts the routes each search passed this object reads, for tests to check. Production
    /// searches pass none, so their hot loops write neither count into the shared matcher.
    static final class RouteDiagnostics
    {
        private long rootTablePositionCount;
        private long sampledPositionCount;

        /// The positions the scalar root-table loop read in searches and counts that began with
        /// at least 64 bytes left. Shorter ones always read the root table, and count nothing.
        /// Filter stops compare the literals instead, and
        /// [LiteralAlternationSpanMatcher#filterStopCountForDiagnostics()] counts them.
        long rootTablePositionCount()
        {
            return rootTablePositionCount;
        }

        /// The positions the start-scan productivity checks could read, at most
        /// [Dfa#BYTE_SCAN_SAMPLE_SIZE] for each check.
        long sampledPositionCount()
        {
            return sampledPositionCount;
        }
    }

    /// The route one search of an input chose and the position it holds to, which the next
    /// search of the same input continues instead of sampling the filter again, as the DFA's
    /// group-zero cursor keeps its scan rejection. It also carries the filter stops its searches
    /// made on the route, which decide whether the next route keeps the filter without sampling;
    /// a cursor without a route reads as on the root table, so its next search samples.
    /// Positions are relative to the input. Its callers search whole inputs: Trino's matcher
    /// across its finds, and Trino's operations that find repeatedly within one call. A new cursor
    /// is reset; a caller must [#reset()] it before searching a different input, since a route end
    /// carried past that input's end would read outside it. The cursor is mutable, so each caller
    /// owns its own, and the shared span matcher holds none.
    static final class Cursor
    {
        private int routeEnd;
        private boolean readsRootTable = true;
        private int routeStopCount;

        /// Forgets the route, so the next search chooses one from its start position.
        void reset()
        {
            routeEnd = 0;
            readsRootTable = true;
            routeStopCount = 0;
        }
    }
}
