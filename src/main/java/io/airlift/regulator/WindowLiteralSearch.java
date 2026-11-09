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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/// Finds the leftmost-first match of a pattern every match of which holds a literal `L` that
/// starts at most a bounded number of bytes, the window, past the match start, by searching for
/// `L` instead of running the DFA over every byte. Such a literal is one that every alternative
/// of an alternation holds after a prefix of bounded width, as `river` is for
/// `Tom.{10,25}river|river.{10,25}Tom`, or one after such a prefix in a concatenation that no
/// proof of [InnerLiteralSearch] admits, such as `(?:xy|z){0,2}xyz`. Hyperscan searches the
/// first of these for `river` alone and confirms around each occurrence.
///
/// The search visits every occurrence of `L` at or after the search start, overlapping ones
/// included, and for each tries the starts from the window before it through the occurrence
/// itself, earliest first, skipping every start already tried for an earlier occurrence and every
/// start whose byte cannot begin a match. Each try runs the whole pattern on a forward DFA
/// anchored at the start, unbounded by the occurrence, and the first that matches is the
/// leftmost-first match. A start that fails fails for every occurrence, since its try reads the
/// pattern from the start alone, so no start is tried twice. Every match holds an occurrence of
/// `L` within the window past its start, so a start earlier than the window before an occurrence
/// belongs to an earlier occurrence's window, and no start after the last occurrence can begin a
/// match. A match found from a start may run past the occurrence that led to it and use a later
/// one.
///
/// The window is a count of bytes, the largest that the elements before `L` can consume as the
/// compiled program reads them, which differs by language and mode: a character is one byte in a
/// Latin-1 program and for a node parsed as bytes, such as Rust's `(?-u:.)`, and up to four bytes
/// in UTF-8, where every character the compiler accepts, the lenient forms of
/// [Compiler#splitUtf8Range] for malformed input included, is one to four bytes. A class whose
/// largest rune is ASCII consumes one byte, and in UTF-8 one whose largest rune encodes in two or
/// three bytes consumes at most that many. The analysis leaves an element compiled with full case
/// folding, whose matches can hold a different number of characters, unbounded.
///
/// As [InnerLiteralSearch] does, a search reports [#FALLBACK], for its caller to run the ordinary
/// engine, when a DFA fails, when its first occurrence lies too near the search start for the
/// window before it to leave [#MINIMUM_SKIPPED_BYTES] skipped, and when the work of its anchored
/// tries passes half the bytes the search has passed, plus [#ANCHORED_WORK_ALLOWANCE_BYTES], which
/// keeps the work linear in the text. The work counts each byte a try reads and
/// [#ANCHORED_TRY_BYTES] for each try, and is checked after each try, so one try of a search that
/// reports a span may read to the search end before the guard sees it: it runs the first-match DFA
/// to the end of its leftmost-first match, or to where the DFA dies, as the inner literal search's
/// tries do. A search that needs only whether a match exists, a boolean find, which reports no
/// captures, takes [#searchExists] instead, unlike the inner literal search: each try runs the
/// longest-match DFA and stops at its first accepting state, as the ordinary engine's
/// earliest-match search does, and its text ends one byte past what the guard still allows, so a
/// try that would read on trips the guard there, with assertions still seeing the whole context.
/// Without both, one try could read the rest of the input where the ordinary engine reads a few
/// bytes: after a match it does not need, for `(?:x|zz){0,4}xyz.*QR|xyz`, or before failing, for
/// the `Tom` start of `(?s)(?:Tom|Huck).{0,5}river.*XYZ|river.{0,5}(?:Tom|Huck)` on
/// `Tom river Tom` and a long tail. A count's ordinary engine takes over from the search
/// start and hands back only at a match whose next occurrence lies far enough past it. A find never
/// runs the search on a search range shorter than [#MINIMUM_SEARCH_BYTES], nor a count on a shorter
/// text, as the inner literal search skips them for a single literal.
final class WindowLiteralSearch
{
    static final long FALLBACK = Dfa.CANDIDATE_SEARCH_FALLBACK;
    // A find window, or a whole-text count's text, shorter than this skips the search, as for the
    // inner literal search of a single literal of two or more bytes, whose scan this one shares.
    static final int MINIMUM_SEARCH_BYTES = InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES;
    // A first occurrence whose window starts closer than this to the search start leaves too few
    // bytes to skip.
    static final int MINIMUM_SKIPPED_BYTES = InnerLiteralSearch.MINIMUM_SKIPPED_BYTES;
    // Each occurrence may try a start at each byte of its window, so a wider window leaves the
    // pattern on the DFA.
    static final int MAXIMUM_WINDOW_BYTES = 255;
    // The work of one search's anchored tries may reach half the bytes from the search start to
    // the occurrence being tried, plus this allowance for the tries of the first occurrences;
    // past that the search falls back. Each try counts the bytes it reads and ANCHORED_TRY_BYTES,
    // which stands for its call and start-state setup and the literal scan restarted after each
    // occurrence. The allowance, which a near first occurrence's tries would otherwise exhaust
    // against the few bytes passed, kept the work beyond it to about 0.09 of the bytes passed on
    // the measured rows that win: tom-river and holmes-cochar-watson in Rust bytes mode, Unicode
    // on or off, and RE2. Back-to-back failing occurrences, as in synthetic/window-dense-fail,
    // peak at about 1.3 per byte passed and fall back after two or three occurrences. "Window
    // Literal Search" in REGULATOR_DECISIONS.md records the counts.
    static final int ANCHORED_WORK_ALLOWANCE_BYTES = 64;
    // The work one anchored try costs beyond the bytes it reads. On window-dense-fail, per text
    // byte, the tries read 6.27 bytes and made 0.395 tries, and the search took 8.4 times the
    // ordinary engine's time on r9g and 10.9 times on r8i; at one DFA step per byte read, a try
    // costs about 5 and 12 bytes there.
    static final int ANCHORED_TRY_BYTES = 16;

    // The literal is one the search kernel finds with its word scan, with the same minimum as the
    // members of InnerLiteralSearch's literal sets and the same maximum as its other literals.
    private static final int MINIMUM_LITERAL_LENGTH = 3;
    private static final int MAXIMUM_LITERAL_LENGTH = InnerLiteralSearch.MAXIMUM_LITERAL_LENGTH;
    // The literals tried, each with a walk over the expression, and the depth of every walk. Past
    // the depth a walk stops with its conservative answer: no literal or no bound.
    private static final int MAXIMUM_CANDIDATE_LITERALS = 16;
    private static final int MAX_RECURSIVE_DEPTH = 256;
    private static final int UNBOUNDED = -1;

    private static final int INSTANCE_SIZE = SizeOf.instanceSize(WindowLiteralSearch.class);

    private final LiteralSearchKernel literalSearch;
    private final int literalLength;
    private final int windowBytes;
    // A first occurrence closer than this to the search start falls back.
    private final int nearBytes;
    // The bytes that can begin a match, as the bits of four words, low bytes first, or all zero
    // when every byte can, or when the program reaches an assertion before its first byte.
    private final long startGate0;
    private final long startGate1;
    private final long startGate2;
    private final long startGate3;
    private final boolean hasStartGate;
    private final Prog forwardProgram;

    private WindowLiteralSearch(LiteralSearchKernel literalSearch, int windowBytes, long[] startGate, Prog forwardProgram)
    {
        this.literalSearch = requireNonNull(literalSearch, "literalSearch is null");
        this.literalLength = literalSearch.literalLength();
        this.windowBytes = windowBytes;
        this.nearBytes = windowBytes + MINIMUM_SKIPPED_BYTES;
        this.startGate0 = startGate[0];
        this.startGate1 = startGate[1];
        this.startGate2 = startGate[2];
        this.startGate3 = startGate[3];
        this.hasStartGate = (startGate0 | startGate1 | startGate2 | startGate3) != 0;
        this.forwardProgram = requireNonNull(forwardProgram, "forwardProgram is null");
    }

    /// Returns the search for the normalized `regexp`, compiled into `forwardProgram`, or null when
    /// no literal of [#MINIMUM_LITERAL_LENGTH] to [#MAXIMUM_LITERAL_LENGTH] exact ASCII bytes lies
    /// within [#MAXIMUM_WINDOW_BYTES] of the start of every match, the program is anchored, has a
    /// prefix scan, or has assertions the DFA cannot run, its first-match DFA would scan a start
    /// literal filter of one or two literals with 64-byte vectors, or the search would retain
    /// more than `retainedMemory`. Of the literals that do, it takes the rarest in English text.
    static WindowLiteralSearch analyze(Regexp regexp, Prog forwardProgram, long retainedMemory)
    {
        if (forwardProgram.start() == 0 || forwardProgram.anchorStart() || forwardProgram.anchorEnd() || forwardProgram.canPrefixAccel() || forwardProgram.hasDfaUnsupportedAssertions()) {
            return null;
        }
        LiteralWindow literalWindow = selectLiteral(regexp, new ExpressionWalk(regexp));
        if (literalWindow == null || keepsOneOrTwoLiteralStartFilter(forwardProgram)) {
            return null;
        }
        long[] startGate = new long[4];
        byte[] startBytes = forwardProgram.boundaryBytes();
        if (startBytes != null) {
            for (int value = 0; value < 0x100; value++) {
                if (startBytes[value] != 0) {
                    startGate[value >>> 6] |= 1L << value;
                }
            }
        }
        WindowLiteralSearch search = new WindowLiteralSearch(new LiteralSearchKernel(Slices.wrappedBuffer(literalWindow.literal())), literalWindow.windowBytes(), startGate, forwardProgram);
        return search.estimatedRetainedSize() <= retainedMemory ? search : null;
    }

    // Of the candidate literals that lie within a bounded window of the start of every match of
    // `regexp`, the rarest in English text, with the narrower window on a tie, or null.
    private static LiteralWindow selectLiteral(Regexp regexp, ExpressionWalk walk)
    {
        List<byte[]> candidates = new ArrayList<>();
        collectLiterals(regexp, candidates, 0);
        ByteFrequencies frequencies = ByteFrequencies.englishText();
        byte[] literal = null;
        int windowBytes = UNBOUNDED;
        double literalProbability = Double.MAX_VALUE;
        for (byte[] candidate : candidates) {
            int window = walk.literalWindow(regexp, candidate, 0);
            if (window == UNBOUNDED) {
                continue;
            }
            double probability = probability(frequencies, candidate);
            if (probability < literalProbability || (probability == literalProbability && window < windowBytes)) {
                literal = candidate;
                windowBytes = window;
                literalProbability = probability;
            }
        }
        return literal == null ? null : new LiteralWindow(literal, windowBytes);
    }

    /// The nodes the literal selection for the normalized `regexp` visits, with each width
    /// lookup counted, for tests that bound the analysis's work.
    static long selectionVisitsForDiagnostics(Regexp regexp)
    {
        ExpressionWalk walk = new ExpressionWalk(regexp);
        selectLiteral(regexp, walk);
        return walk.visits;
    }

    // Whether the program's first-match DFA scans a start literal filter of one or two literals,
    // which its scanner pads to two slots, with 64-byte vectors, as with AVX-512 on r8i. There a
    // two-literal scan already stops only near a match: the search took 1.18 to 1.22 times main's
    // time for tom-river, whose {Tom, riv} filter every vector width takes, and 1.29 to 1.38 for
    // holmes-cochar-watson, whose rare start bytes take {Hol, Wat} only at 64 lanes. On r9g's
    // 16-byte vectors, where tom-river keeps the same filter, the search took 0.68 times main's
    // time. For a one-literal filter, which runs the same two-slot scan, the window search yields
    // too, though no such row was measured. As in VectorByteSetScanner's choice of the two-literal
    // filter for rare start bytes, the lane count is the hardware input; 32-byte vectors were not
    // measured and keep the search. VectorSupport is checked first because preferredByteLanes
    // loads the Vector API.
    private static boolean keepsOneOrTwoLiteralStartFilter(Prog forwardProgram)
    {
        return VectorSupport.isAvailable() && VectorSupport.preferredByteLanes() >= 64 &&
                Dfa.DfaInstance.startLiteralFilterScannerWidth(forwardProgram, Dfa.DfaInstance.Kind.FIRST_MATCH) == 2;
    }

    // Adds the distinct exact ASCII literals of the expression, up to MAXIMUM_CANDIDATE_LITERALS,
    // in the order a walk from the left finds them. A literal must lie whole in one literal node:
    // simplification already joins adjacent literals into one.
    private static void collectLiterals(Regexp regexp, List<byte[]> candidates, int depth)
    {
        if (depth >= MAX_RECURSIVE_DEPTH || candidates.size() >= MAXIMUM_CANDIDATE_LITERALS) {
            return;
        }
        byte[] literal = exactAsciiLiteral(regexp);
        if (literal != null) {
            if (literal.length >= MINIMUM_LITERAL_LENGTH && literal.length <= MAXIMUM_LITERAL_LENGTH && candidates.stream().noneMatch(candidate -> Arrays.equals(candidate, literal))) {
                candidates.add(literal);
            }
            return;
        }
        for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
            collectLiterals(regexp.child(childIndex), candidates, depth + 1);
        }
    }

    // The bytes of a literal node that matches exactly its ASCII runes, or null. A case-insensitive
    // literal is left out: the kernel searches exact bytes, and a folded search would need a
    // folded kernel of its own.
    private static byte[] exactAsciiLiteral(Regexp regexp)
    {
        if ((regexp.op() != RegexpOp.LITERAL && regexp.op() != RegexpOp.LITERAL_STRING) || (regexp.parseFlags() & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) != 0) {
            return null;
        }
        return InnerLiteralSearch.asciiLiteral(regexp);
    }

    // The bytes an exact literal node compiles to, as the compiler encodes them: a rune is one
    // byte when its node is Latin-1 or the rune is ASCII, and otherwise its UTF-8 encoding. Null
    // for any other node, a case-insensitive one included.
    private static byte[] exactCompiledBytes(Regexp regexp)
    {
        if ((regexp.op() != RegexpOp.LITERAL && regexp.op() != RegexpOp.LITERAL_STRING) || (regexp.parseFlags() & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) != 0) {
            return null;
        }
        boolean latin1Node = (regexp.parseFlags() & Regexp.LATIN1) != 0;
        int[] runes = regexp.op() == RegexpOp.LITERAL ? new int[] {regexp.rune()} : regexp.runes();
        int length = 0;
        for (int rune : runes) {
            if (rune < 0 || rune > (latin1Node ? 0xFF : Regexp.RUNEMAX)) {
                return null;
            }
            length += runeBytes(rune, latin1Node);
        }
        byte[] bytes = new byte[length];
        int index = 0;
        for (int rune : runes) {
            if (latin1Node) {
                bytes[index++] = (byte) rune;
            }
            else {
                index = Utf8.encode(bytes, index, rune);
            }
        }
        return bytes;
    }

    private record LiteralWindow(byte[] literal, int windowBytes) {}

    // The walks of one expression for every candidate literal. A node's width does not depend on
    // the literal, so each is measured once, where the walk for each candidate and each enclosing
    // prefix would otherwise measure the nodes of a nested prefix again. A width is kept with the
    // depth it was measured at, since the depth limit can change it, and a node that the
    // simplifier shares at another depth is measured again there.
    private static final class ExpressionWalk
    {
        private final boolean latin1Program;
        private final Map<Regexp, Long> widths = new IdentityHashMap<>();
        private long visits;

        ExpressionWalk(Regexp regexp)
        {
            // The compiler reads the program's encoding from the root's flags.
            this.latin1Program = (regexp.parseFlags() & Regexp.LATIN1) != 0;
        }

        // The largest offset from the start of a match of `regexp` at which an occurrence of
        // `literal` every match must consume starts, at most MAXIMUM_WINDOW_BYTES, or UNBOUNDED
        // when some match need not consume one at a bounded offset. A concatenation holds the
        // literal of its first element that holds it, after the widest the elements before it
        // consume; an alternation the largest offset of its alternatives, each of which must hold
        // it; and a repetition of at least one copy the offset of its first copy.
        int literalWindow(Regexp regexp, byte[] literal, int depth)
        {
            visits++;
            if (depth >= MAX_RECURSIVE_DEPTH) {
                return UNBOUNDED;
            }
            return switch (regexp.op()) {
                case LITERAL, LITERAL_STRING -> {
                    byte[] bytes = exactCompiledBytes(regexp);
                    yield bytes == null ? UNBOUNDED : bounded(indexOf(bytes, literal));
                }
                case CAPTURE, PLUS -> literalWindow(regexp.child(0), literal, depth + 1);
                case REPEAT -> regexp.min() > 0 ? literalWindow(regexp.child(0), literal, depth + 1) : UNBOUNDED;
                case CONCAT -> {
                    int width = 0;
                    for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
                        Regexp child = regexp.child(childIndex);
                        int window = literalWindow(child, literal, depth + 1);
                        if (window != UNBOUNDED) {
                            yield bounded(width + window);
                        }
                        int childWidth = maximumBytes(child, depth + 1);
                        if (childWidth == UNBOUNDED) {
                            yield UNBOUNDED;
                        }
                        width = bounded(width + childWidth);
                        if (width == UNBOUNDED) {
                            yield UNBOUNDED;
                        }
                    }
                    yield UNBOUNDED;
                }
                case ALTERNATE -> {
                    int largest = 0;
                    for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
                        int window = literalWindow(regexp.child(childIndex), literal, depth + 1);
                        if (window == UNBOUNDED) {
                            yield UNBOUNDED;
                        }
                        largest = Math.max(largest, window);
                    }
                    yield largest;
                }
                default -> UNBOUNDED;
            };
        }

        // The most bytes a match of `regexp` consumes in the compiled program, at most
        // MAXIMUM_WINDOW_BYTES, or UNBOUNDED, as measureBytes measures it at `depth`.
        int maximumBytes(Regexp regexp, int depth)
        {
            visits++;
            Long known = widths.get(regexp);
            if (known != null && (int) (known >>> 32) == depth) {
                return known.intValue();
            }
            int width = measureBytes(regexp, depth);
            widths.put(regexp, ((long) depth << 32) | (width & 0xFFFF_FFFFL));
            return width;
        }

        // The widths follow the compiler: a literal rune is one byte when its node is Latin-1 or
        // the rune is ASCII, a case-insensitive one included, which compiles to a folded byte
        // range, and otherwise its UTF-8 length; a class is one byte in a Latin-1 program or node
        // and otherwise the UTF-8 length of its largest rune, since every encoding the compiler
        // accepts for a smaller rune, lenient or exact, is no longer; any character is one byte
        // in a Latin-1 program and up to four in UTF-8; any byte is one byte. ExpressionAnalysis's
        // maximum length cannot serve: it reads node flags alone and folds a case-insensitive
        // literal to its widest variant, where this walk reads the program's encoding from the
        // root and counts the bytes the compiler emits.
        private int measureBytes(Regexp regexp, int depth)
        {
            if (depth >= MAX_RECURSIVE_DEPTH) {
                return UNBOUNDED;
            }
            int flags = regexp.parseFlags();
            boolean latin1Node = (flags & Regexp.LATIN1) != 0;
            return switch (regexp.op()) {
                case EMPTY_MATCH, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT -> 0;
                case LITERAL -> (flags & Regexp.FULL_CASE_FOLD) != 0 ? UNBOUNDED : runeBytes(regexp.rune(), latin1Node);
                case LITERAL_STRING -> {
                    if ((flags & Regexp.FULL_CASE_FOLD) != 0) {
                        yield UNBOUNDED;
                    }
                    int width = 0;
                    for (int rune : regexp.runes()) {
                        width = bounded(width + runeBytes(rune, latin1Node));
                        if (width == UNBOUNDED) {
                            yield UNBOUNDED;
                        }
                    }
                    yield width;
                }
                case CHAR_CLASS -> {
                    CharClass charClass = regexp.charClass();
                    if (latin1Program || latin1Node || charClass.isEmpty()) {
                        yield 1;
                    }
                    yield Utf8.encodedLength(charClass.range(charClass.rangeCount() - 1).high());
                }
                case ANY_CHAR -> latin1Program ? 1 : 4;
                case ANY_BYTE -> 1;
                case CAPTURE, QUEST -> maximumBytes(regexp.child(0), depth + 1);
                case REPEAT -> {
                    if (regexp.max() < 0) {
                        yield UNBOUNDED;
                    }
                    int childWidth = maximumBytes(regexp.child(0), depth + 1);
                    yield childWidth == UNBOUNDED ? UNBOUNDED : bounded((long) childWidth * regexp.max());
                }
                case CONCAT -> {
                    int width = 0;
                    for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
                        int childWidth = maximumBytes(regexp.child(childIndex), depth + 1);
                        if (childWidth == UNBOUNDED) {
                            yield UNBOUNDED;
                        }
                        width = bounded(width + childWidth);
                        if (width == UNBOUNDED) {
                            yield UNBOUNDED;
                        }
                    }
                    yield width;
                }
                case ALTERNATE -> {
                    int largest = 0;
                    for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
                        int childWidth = maximumBytes(regexp.child(childIndex), depth + 1);
                        if (childWidth == UNBOUNDED) {
                            yield UNBOUNDED;
                        }
                        largest = Math.max(largest, childWidth);
                    }
                    yield largest;
                }
                // A repetition without an upper bound, and any node this analysis does not model.
                default -> UNBOUNDED;
            };
        }
    }

    private static int runeBytes(int rune, boolean latin1Node)
    {
        return latin1Node || rune < 0x80 ? 1 : Utf8.encodedLength(rune);
    }

    private static int bounded(long bytes)
    {
        return bytes > MAXIMUM_WINDOW_BYTES ? UNBOUNDED : (int) bytes;
    }

    private static int indexOf(byte[] bytes, byte[] literal)
    {
        for (int index = 0; index + literal.length <= bytes.length; index++) {
            if (Arrays.equals(bytes, index, index + literal.length, literal, 0, literal.length)) {
                return index;
            }
        }
        return UNBOUNDED;
    }

    // As for InnerLiteralSearch's class pairs, the bytes are estimated as independent.
    private static double probability(ByteFrequencies frequencies, byte[] literal)
    {
        double probability = 1;
        for (byte value : literal) {
            probability *= frequencies.probability(value);
        }
        return probability;
    }

    /// The literal the search scans for.
    byte[] literalForDiagnostics()
    {
        return literalSearch.literal();
    }

    /// The most bytes past a match start at which the literal can start.
    int windowBytesForDiagnostics()
    {
        return windowBytes;
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + literalSearch.estimatedRetainedSize();
    }

    /// Whether a search of `[start, end)` of `text` falls back because its first occurrence lies
    /// near the start. It reads at most `windowBytes + MINIMUM_SKIPPED_BYTES - 1` bytes plus the
    /// literal's length.
    boolean fallsBackNearStart(Slice text, int start, int end)
    {
        int length = Math.min(end - start, nearBytes - 1 + literalLength);
        return nextLiteral(text, start, start + length) >= 0;
    }

    /// Searches `[start, end)` of `text` with assertions seeing `[contextStart, contextEnd)`, and
    /// returns the match's Slice offsets packed as `start << 32 | end`, [Dfa#SEARCH_NO_MATCH], or
    /// [#FALLBACK].
    long search(Slice text, int contextStart, int contextEnd, int start, int end)
    {
        return search(text, contextStart, contextEnd, start, end, false);
    }

    /// As [#search(Slice,int,int,int,int)], for a caller that needs only whether a match exists:
    /// a non-negative result packs the match's start with the shortest match end from there, not
    /// the leftmost-first end. Each try stops at its first accepting state, and its text ends one
    /// byte past what the guard still allows, with assertions still seeing the whole context.
    long searchExists(Slice text, int contextStart, int contextEnd, int start, int end)
    {
        return search(text, contextStart, contextEnd, start, end, true);
    }

    private long search(Slice text, int contextStart, int contextEnd, int start, int end, boolean exists)
    {
        int literalStart = nextLiteral(text, start, end);
        if (literalStart < 0) {
            return Dfa.SEARCH_NO_MATCH;
        }
        if (literalStart - start < nearBytes) {
            return FALLBACK;
        }
        byte[] data = text.byteArray();
        int dataOffset = text.byteArrayOffset();
        // Every start up to `tried` has been tried and failed, or ruled out by the start gate.
        int tried = start - 1;
        long work = 0;
        for (; literalStart >= 0; literalStart = nextLiteral(text, literalStart + 1, end)) {
            for (int matchStart = Math.max(tried + 1, literalStart - windowBytes); matchStart <= literalStart; matchStart++) {
                if (hasStartGate && !inStartGate(data[dataOffset + matchStart])) {
                    continue;
                }
                int workLimit = (literalStart - start) / 2 + ANCHORED_WORK_ALLOWANCE_BYTES;
                long forward;
                if (exists) {
                    // One byte past the bytes the guard still allows: a try that reaches this
                    // end without accepting trips the guard below. A failing try would have
                    // tripped it after reading on; one whose first accept lies past this end
                    // falls back where it would have matched. Either way the answer is the
                    // ordinary engine's, and the work is at most the guard's plus one ordinary
                    // pass from the search start.
                    long tryEnd = matchStart + Math.max(0, workLimit - work - ANCHORED_TRY_BYTES + 1);
                    forward = Dfa.searchAnchoredEarliestWithStop(forwardProgram, text, contextStart, contextEnd, matchStart, (int) Math.min(end, tryEnd));
                }
                else {
                    forward = Dfa.searchAnchoredWithStop(forwardProgram, text, contextStart, contextEnd, matchStart, end);
                }
                if (forward >= 0) {
                    return ((long) matchStart << 32) | forward;
                }
                if (forward == Dfa.SEARCH_FAILED) {
                    return FALLBACK;
                }
                work += Dfa.anchoredStopPosition(forward) - matchStart + ANCHORED_TRY_BYTES;
                if (work > workLimit) {
                    return FALLBACK;
                }
            }
            tried = literalStart;
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    // The first occurrence in `[scanStart, end)`, or -1 when there is none.
    private int nextLiteral(Slice text, int scanStart, int end)
    {
        return end - scanStart < literalLength ? -1 : findLiteral(text, scanStart, end - scanStart);
    }

    // Long scans take the fused vector pair scan at or above the prefix scans' vector cutoff, as
    // InnerLiteralSearch's single literals do.
    private int findLiteral(Slice text, int offset, int length)
    {
        if (VectorSupport.isAvailable() && length >= Prog.FUSED_PREFIX_VECTOR_MINIMUM_BYTES) {
            int dataOffset = text.byteArrayOffset();
            int found = Prog.findLiteral(text.byteArray(), dataOffset + offset, length, literalSearch.literalBytes(), literalSearch);
            return found < 0 ? -1 : found - dataOffset;
        }
        return literalSearch.findContains(text, offset, length);
    }

    // The value is a byte, and a long shift uses only the low six bits of its count, so the byte
    // itself picks its bit in its word: bytes 0 to 63 in the first, 64 to 127 in the second, and
    // the negative bytes 128 to 255 in the third and fourth.
    private boolean inStartGate(byte value)
    {
        long word = value >= 0 ? (value < 64 ? startGate0 : startGate1) : (value < -64 ? startGate2 : startGate3);
        return ((word >>> value) & 1) != 0;
    }
}
