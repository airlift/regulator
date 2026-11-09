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

import io.airlift.regulator.LiteralAlternationSpanMatcher.ExtractedLiterals;
import io.airlift.slice.SizeOf;
import io.airlift.slice.Slice;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static java.util.Objects.requireNonNull;

/// Finds the leftmost-first match of an alternation of more than
/// [LiteralAlternationSpanMatcher#MAXIMUM_ALTERNATIVES] literals, such as a dictionary of words,
/// which [LiteralAlternationSpanMatcher]'s trie walk from each position does not scale to. It
/// searches a leftmost-first Aho-Corasick automaton ported from the `aho-corasick` crate's
/// noncontiguous NFA (`nfa/noncontiguous.rs`) and its leftmost search (`automaton.rs`,
/// `try_find_fwd`), which the `regex` crate uses for such patterns, so each byte the automaton
/// reads follows one transition plus the failure links it amortizes.
///
/// The trie omits a literal whose path passes through an earlier literal's match state, which
/// a leftmost-first search never reports. A state with its own match therefore keeps
/// only the edges of earlier literals that extend it, such as `samwise` in `samwise|sam`. Its
/// failure link is [#DEAD], so a failure path that reaches it ends there. Every other state's
/// failure link is the longest proper suffix of its path that is also a trie path, and a state
/// without its own match takes the match of its failure state, a literal ending at the same
/// byte and starting later. So once a search records a match, every state it reaches has a
/// failure path that ends at [#DEAD] instead of the root, and the search extends only to
/// literals starting no later than that match, as `b|xbc|axbcd` records `b`, `xbc`, and then
/// `axbcd` in `axbcd`. It stops at the first byte none of them can follow and never restarts
/// at a later position.
///
/// States are numbered breadth-first and their edges stored in that order, sorted by byte, so
/// a state's edges are contiguous and edge `e` leads to state `e + 1`. The root keeps a dense
/// 256-entry row whose missing bytes stay at the root.
///
/// On typical text, most transitions of a search leave shallow states, which have the most
/// edges, so as in the `aho-corasick` crate's noncontiguous NFA (`nfa/noncontiguous.rs`,
/// `densify`), the states after the root up to a small depth also keep dense rows, over byte
/// classes. Where the crate's row holds only the state's own edges, a row here holds the state's
/// complete leftmost-first transition on each class: its edge, else its failure state's
/// transition, so a failure path that reaches a state with a row ends there. Deeper states scan
/// their sorted edges and follow failure links until they reach the root, a state with a dense
/// row, or [#DEAD]. The bytes labeling no edge of any state with a row share one class, on which
/// each such state leads to the root or to [#DEAD]. The rows are built at the deepest depth up to
/// [#DENSE_DEPTH] whose rows fit [#MAXIMUM_DENSE_ROW_BYTES] and the build's memory budget, and at
/// none where none fits.
///
/// A set of long literals, each of at least [SkipTable#MINIMUM_LENGTH] bytes, skips ahead as in
/// Wu-Manber (Wu and Manber, "A Fast Algorithm for Multi-Pattern Searching", 1994), where its
/// [SkipTable] fits the build's memory budget and has distance zero for at most a quarter of its
/// hashes. The search reads windows as long as the shortest literal, at most
/// [SkipTable#MAXIMUM_WINDOW] bytes. The table maps the last block of each window to a distance
/// from the window start within which no literal starts, and a nonzero one moves the window start
/// that far. A zero distance leaves a walk down the trie's edges from the window start, which
/// reports the literal starting there that [#findPrefixEnd] would. The first window start with a
/// literal holds the leftmost-first match. After each failed walk, the search checks the trie
/// edges its walks have followed against [#WALK_ALLOWANCE] plus two per byte it has passed, so one
/// walk can take the total past the allowance; then the automaton's search takes the rest from the
/// byte after that walk's start, so a run of near misses costs linear time. Only a search of at
/// least [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES] skips ahead; a shorter one runs the automaton.
///
/// On text that no literal starts in, such as digits searched for words, a window step crosses at
/// most `window - block + 1` bytes. The root's vector scan below crosses the whole run at once, so
/// a set with that scan uses it while skipping ahead, at a window start whose byte and the next
/// lead no literal. Until the short-scan limit ends its scans, such a set stops skipping ahead
/// [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES] before the search's end, where the scan stops too,
/// and leaves the rest to the automaton. Once the limit ends them, the search steps windows up to
/// the last full window with a new walk allowance, so it can skip into those last bytes, and an
/// automaton it later hands the search to starts its own short-scan count.
///
/// At the root, a search skips the bytes that lead no literal with a vector scan for the root's
/// leading bytes. Such a byte leaves the state at the root and records nothing, so the skip is
/// exact. A search at the root has no match pending, since once one is recorded no state the
/// search reaches fails back to the root. The scan uses the forward DFA's kernel selection,
/// [Dfa#buildStartByteScanSet] and [Dfa#findByteSet] over two or three bytes or a contiguous
/// range, and otherwise its nibble tables, which take 4 to 64 bytes with at most 8 high nibbles.
/// It is not the DFA's start-byte skip, which prefers its start literal filter for up to 16
/// common start bytes, covers a single start byte with its prefix scan, and vector-scans only
/// with [Dfa#MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES] bytes left. A matcher with the skip is a
/// [RootSkippingMatcher], so one without it retains only its trie arrays, any dense rows and skip
/// table, and the object holding them, which carries the rows' fields whether or not it has rows;
/// the skip costs nothing where it is not built. No method is overridden: the searches reach the
/// skip's scan set by testing `this` against the subclass.
sealed class LiteralSetSpanMatcher
        implements GroupZeroSpanSearch
        permits LiteralSetSpanMatcher.RootSkippingMatcher
{
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(LiteralSetSpanMatcher.class);
    private static final int ROOT = 0;
    private static final int DEAD = -1;
    // Each state retains an edge offset, a failure link, and a match length, and all but the
    // root retain the byte of the edge leading to them.
    private static final int RETAINED_BYTES_PER_STATE = 3 * Integer.BYTES + 1;
    private static final int INITIAL_TRIE_CAPACITY = 1024;
    // The deepest states with dense rows, which a smaller budget or the row limit reduces.
    private static final int DENSE_DEPTH = 3;
    private static final long MAXIMUM_DENSE_ROW_BYTES = 1L << 20;
    // The trie edges the walks at window starts may follow, beyond two per byte the search has
    // passed, before the automaton takes the rest of the search. The check follows each failed
    // walk, so one walk can take the total past it.
    private static final int WALK_ALLOWANCE = 4096;
    // The indexes of the counts a diagnostics search adds to.
    private static final int ROOT_SKIP_COUNT = 0;
    private static final int WINDOW_SKIP_COUNT = 1;
    private static final int AUTOMATON_HANDOFF_COUNT = 2;

    private final int[] rootStateByByte;
    // The class of each byte in a dense row, or null without dense rows.
    private final byte[] classByByte;
    private final int classCount;
    // The depth of the deepest states with dense rows, or zero for none.
    private final int denseDepth;
    // States below this count, the root and the shallowest states after it, have dense rows. A
    // search at the root reads its 256-entry row; the root's dense row only seeds the rows of the
    // states that fail to it.
    private final int denseStateCount;
    // The transition of each state with a dense row on each byte class, row after row from the
    // root's, or null without dense rows.
    private final int[] denseNext;
    // The skip ahead of a set of long literals, or null.
    private final SkipTable skipTable;
    // The first edge of each state, plus a final entry ending the last state's edges.
    private final int[] firstEdgeByState;
    private final byte[] edgeByte;
    private final int[] failureByState;
    // The byte length of the literal a search reports on entering the state, or zero.
    private final int[] matchLengthByState;

    private LiteralSetSpanMatcher(int[] rootStateByByte, int[] firstEdgeByState, byte[] edgeByte, int[] failureByState, int[] matchLengthByState)
    {
        this(rootStateByByte, null, 0, 0, 1, null, null, firstEdgeByState, edgeByte, failureByState, matchLengthByState);
    }

    private LiteralSetSpanMatcher(
            int[] rootStateByByte,
            byte[] classByByte,
            int classCount,
            int denseDepth,
            int denseStateCount,
            int[] denseNext,
            SkipTable skipTable,
            int[] firstEdgeByState,
            byte[] edgeByte,
            int[] failureByState,
            int[] matchLengthByState)
    {
        this.rootStateByByte = rootStateByByte;
        this.classByByte = classByByte;
        this.classCount = classCount;
        this.denseDepth = denseDepth;
        this.denseStateCount = denseStateCount;
        this.denseNext = denseNext;
        this.skipTable = skipTable;
        this.firstEdgeByState = firstEdgeByState;
        this.edgeByte = edgeByte;
        this.failureByState = failureByState;
        this.matchLengthByState = matchLengthByState;
    }

    /// Returns a matcher for a capture-free alternation of more than
    /// [LiteralAlternationSpanMatcher#MAXIMUM_ALTERNATIVES] non-empty literals, or `null`. Beyond
    /// the trie's literal strings, the alternation may contain what the parsers' factoring leaves
    /// in a dictionary, such as an empty alternative in `absentmindedness(?:|'s)` or a class
    /// merged from single-character alternatives, where such a choice ends its concatenation. A
    /// literal or class carrying a case-folding flag is not taken, but a class the parser has
    /// already case-folded, such as Rust's `[Kk\x{212A}]` for `(?i)k`, is an ordinary class. Its
    /// literals keep the alternation's leftmost-first priority order. `maximumRetainedBytes`
    /// bounds the states the build adds, but not the root's row or the object overhead, so a
    /// caller admits the result by its [#estimatedRetainedSize()]. The literals' extraction may
    /// build a multiple of it; past that, the build takes the literals one at a time. The skip
    /// table of a set of long literals, the dense rows, and then the root's skip, are built only
    /// where the matcher with them still retains at most `maximumRetainedBytes`, the rows at a
    /// smaller depth where that is what fits, so a matcher a caller admitted without them is
    /// admitted with them or built without them. The table comes first because it takes far less
    /// memory than the rows for a larger gain; see [Trie#build].
    static LiteralSetSpanMatcher analyze(Regexp expression, int capturingGroupCount, long maximumRetainedBytes)
    {
        return analyze(expression, capturingGroupCount, maximumRetainedBytes, DENSE_DEPTH, true);
    }

    /// As [#analyze(Regexp,int,long)], with dense rows for the states up to `maximumDenseDepth`,
    /// and skipping ahead in a set of long literals only where `skipsAhead` allows it.
    static LiteralSetSpanMatcher analyze(Regexp expression, int capturingGroupCount, long maximumRetainedBytes, int maximumDenseDepth, boolean skipsAhead)
    {
        requireNonNull(expression, "expression is null");
        if (capturingGroupCount != 0) {
            return null;
        }

        ExtractedLiterals extracted = LiteralAlternationSpanMatcher.extractLiteralSet(expression, maximumRetainedBytes);
        if (extracted == ExtractedLiterals.OVER_BUDGET) {
            // Past the extraction's bound, the literals go into the trie one at a time as the
            // expansion spells them, so the set is admitted exactly when the finished set fits.
            Trie trie = LiteralAlternationSpanMatcher.spellLiteralSet(expression, maximumRetainedBytes);
            return trie == null ? null : trie.build(maximumRetainedBytes, maximumDenseDepth, skipsAhead);
        }
        if (extracted == null || extracted.literalCount() <= LiteralAlternationSpanMatcher.MAXIMUM_ALTERNATIVES) {
            return null;
        }
        List<byte[]> literals = extracted.literals();
        for (byte[] literal : literals) {
            // An empty literal matches everywhere, which the automaton's search does not report.
            if (literal.length == 0) {
                return null;
            }
        }
        Trie trie = new Trie(LiteralAlternationSpanMatcher.totalLength(literals), maximumRetainedBytes);
        for (byte[] literal : literals) {
            if (!trie.add(literal)) {
                return null;
            }
        }
        return trie.build(maximumRetainedBytes, maximumDenseDepth, skipsAhead);
    }

    /// Returns a lower bound on what the large set [#analyze] builds from the capture-free
    /// `expression` retains, at the least budget that admits it, or [Compiler#NO_BUDGET] where no
    /// budget admits one. The set's literals are spelled into a trie with a budget of sixteen
    /// times `forwardMemory`, the rejected forward budget, but at least 4 MiB and at most what a
    /// set of the most literal bytes can fill; a set larger than that is bounded by the states
    /// the trie held when it filled.
    static long leastRetainedSize(Regexp expression, long forwardMemory)
    {
        requireNonNull(expression, "expression is null");
        LiteralAlternationSpanMatcher.SpellAttempt attempt = LiteralAlternationSpanMatcher.attemptSpelling(expression, spellingBudget(forwardMemory), false);
        if (!attempt.spelled()) {
            return leastRetainedSizeBeyond(attempt.trie());
        }
        // The skip table, the dense rows and the root's skip are built only where they fit, so the
        // least set has none of them.
        return attempt.trie().build(0, 0, false).estimatedRetainedSize();
    }

    /// Returns a lower bound on the memory the large set of the capture-free `expression` and its
    /// [ExtendingLiterals] take together where a pattern runs every operation on them, or
    /// [Compiler#NO_BUDGET] where no budget holds them, measured as [#leastRetainedSize] does.
    /// Both the trie spelling every literal and the extending literals beside the set must fit.
    static long leastMemoryWithExtendingLiterals(Regexp expression, long forwardMemory)
    {
        requireNonNull(expression, "expression is null");
        LiteralAlternationSpanMatcher.SpellAttempt attempt = LiteralAlternationSpanMatcher.attemptSpelling(expression, spellingBudget(forwardMemory), true);
        if (!attempt.spelled()) {
            return leastRetainedSizeBeyond(attempt.trie());
        }
        long setSize = leastRetainedSize(expression, forwardMemory);
        if (setSize == Compiler.NO_BUDGET) {
            return Compiler.NO_BUDGET;
        }
        long extendingLiteralsSize = attempt.trie().extendingLiterals(Long.MAX_VALUE).estimatedRetainedSize();
        return Math.max((long) attempt.trie().stateCount * RETAINED_BYTES_PER_STATE, extendingLiteralsSize + setSize);
    }

    // Sixteen times the rejected forward budget, but at least 4 MiB: large enough to spell most
    // sets a rejected budget nearly holds, while a rejection measures with memory in proportion
    // to the budget it was given. From a sixteenth of the largest it is the largest, at which
    // the literals' bytes bound the trie and the budget never stops it.
    private static long spellingBudget(long forwardMemory)
    {
        long largest = (LiteralAlternationSpanMatcher.MAXIMUM_LITERAL_BYTES + 1L) * RETAINED_BYTES_PER_STATE;
        return forwardMemory >= largest / 16 ? largest : Math.max(4L << 20, 16 * forwardMemory);
    }

    // A spelling that stopped short of a full trie stopped for its literals, at every budget; one
    // that filled the trie needed at least one more state than the budget let it hold.
    private static long leastRetainedSizeBeyond(Trie trie)
    {
        return trie.filledBudget() ? (trie.maximumStates + 1L) * RETAINED_BYTES_PER_STATE : Compiler.NO_BUDGET;
    }

    /// Phase 1 of the automaton's build: the leftmost-first trie of non-empty literals, added in
    /// priority order, or, spelled by [LiteralAlternationSpanMatcher#spellEveryLiteral], of every
    /// literal; such a trie is only walked. Each state records the byte of its incoming edge and
    /// its children as a list sorted by byte, so the breadth-first pass emits sorted edges. A
    /// literal that passes through an earlier literal's match state adds nothing to the
    /// leftmost-first trie, since the earlier one always matches first.
    static final class Trie
    {
        /// The state every literal starts from.
        static final int START = ROOT;
        /// What [#step] returns once the trie holds all the states the budget can retain.
        static final int FULL = -2;

        private final int maximumStates;
        // Whether the budget, not the literals' bytes, bounds the states.
        private final boolean budgetBound;
        private final int[] rootChildByByte = new int[256];
        private int capacity;
        private int[] firstChild;
        private int[] nextSibling;
        private byte[] incomingByte;
        private int[] matchLength;
        private int stateCount = 1;

        /// A trie of literals of at most `totalLiteralBytes` bytes in all, whose states may retain
        /// at most `maximumRetainedBytes`.
        Trie(long totalLiteralBytes, long maximumRetainedBytes)
        {
            // Literals sharing a long prefix expand to far more bytes than the trie has states, so
            // the trie arrays grow with the states added, up to the most the budget can retain.
            long literalStates = totalLiteralBytes + 1;
            long budgetStates = maximumRetainedBytes / RETAINED_BYTES_PER_STATE;
            maximumStates = (int) Math.min(literalStates, Math.max(1, budgetStates));
            budgetBound = budgetStates < literalStates;
            capacity = Math.min(maximumStates, INITIAL_TRIE_CAPACITY);
            firstChild = new int[capacity];
            nextSibling = new int[capacity];
            incomingByte = new byte[capacity];
            matchLength = new int[capacity];
        }

        /// Adds `literal`, or returns `false` if the trie has no room for its states.
        private boolean add(byte[] literal)
        {
            int state = ROOT;
            for (byte value : literal) {
                if (matchLength[state] != 0) {
                    return true;
                }
                state = step(state, value);
                if (state == FULL) {
                    return false;
                }
            }
            matchLength[state] = literal.length;
            return true;
        }

        /// Returns the state `value` leads to from `state`, adding it if new, or [#FULL].
        int step(int state, byte value)
        {
            int child = state == ROOT ? rootChildByByte[value & 0xFF] : findTrieChild(firstChild[state], value, nextSibling, incomingByte);
            if (child != ROOT) {
                return child;
            }
            if (stateCount == capacity) {
                if (capacity == maximumStates) {
                    return FULL;
                }
                capacity = (int) Math.min(maximumStates, 2L * capacity);
                firstChild = Arrays.copyOf(firstChild, capacity);
                nextSibling = Arrays.copyOf(nextSibling, capacity);
                incomingByte = Arrays.copyOf(incomingByte, capacity);
                matchLength = Arrays.copyOf(matchLength, capacity);
            }
            child = stateCount++;
            incomingByte[child] = value;
            if (state == ROOT) {
                rootChildByByte[value & 0xFF] = child;
            }
            else {
                insertTrieChild(state, child, firstChild, nextSibling, incomingByte);
            }
            return child;
        }

        /// Whether the trie holds all the states its budget can retain, so a spelling that stopped
        /// may have stopped for room.
        private boolean filledBudget()
        {
            return budgetBound && stateCount == maximumStates;
        }

        /// The length of the literal ending at `state`, or `0` where none does.
        int matchLength(int state)
        {
            return matchLength[state];
        }

        void setMatchLength(int state, int length)
        {
            matchLength[state] = length;
        }

        /// Returns the literals this trie marks that extend another literal it marks, in unsigned
        /// byte order, or `null` where they would retain more than `maximumRetainedBytes`. Only a
        /// trie [LiteralAlternationSpanMatcher#spellEveryLiteral] spelled marks such literals. An
        /// extending literal that came before the literal it extends is kept too, though the search
        /// trie holds it; a full match reads the trie first.
        private ExtendingLiterals extendingLiterals(long maximumRetainedBytes)
        {
            ByteArrayOutputStream literalBytes = new ByteArrayOutputStream();
            int[] literalEnds = new int[16];
            int literalCount = 0;
            // The path from the root to the state visited, its byte and state at each depth, and
            // whether it marks a literal above each depth.
            byte[] path = new byte[16];
            int[] stateByDepth = new int[16];
            boolean[] markedAbove = new boolean[16];
            for (int value = 0; value < 256; value++) {
                int state = rootChildByByte[value];
                if (state == ROOT) {
                    continue;
                }
                // A depth-first walk of the subtree under the root's child, in byte order, which
                // reaches a literal before the literals extending it.
                int depth = 0;
                while (true) {
                    if (depth + 1 == path.length) {
                        int length = 2 * path.length;
                        path = Arrays.copyOf(path, length);
                        stateByDepth = Arrays.copyOf(stateByDepth, length);
                        markedAbove = Arrays.copyOf(markedAbove, length);
                    }
                    path[depth] = incomingByte[state];
                    stateByDepth[depth] = state;
                    boolean marked = matchLength[state] != 0;
                    if (marked && markedAbove[depth]) {
                        literalBytes.write(path, 0, depth + 1);
                        if (literalCount == literalEnds.length) {
                            literalEnds = Arrays.copyOf(literalEnds, 2 * literalCount);
                        }
                        literalEnds[literalCount++] = literalBytes.size();
                        if (ExtendingLiterals.retainedSize(literalBytes.size(), literalCount) > maximumRetainedBytes) {
                            return null;
                        }
                    }
                    if (firstChild[state] != ROOT) {
                        markedAbove[depth + 1] = markedAbove[depth] || marked;
                        depth++;
                        state = firstChild[state];
                        continue;
                    }
                    // Back up to the deepest state with a next sibling; the root's children are
                    // not linked as siblings, so the walk ends at the root's child.
                    while (depth > 0 && nextSibling[stateByDepth[depth]] == ROOT) {
                        depth--;
                    }
                    if (depth == 0) {
                        break;
                    }
                    state = nextSibling[stateByDepth[depth]];
                }
            }
            return new ExtendingLiterals(literalBytes.toByteArray(), Arrays.copyOf(literalEnds, literalCount));
        }

        /// Builds the automaton of the literals added, with dense rows for the states up to
        /// `maximumDenseDepth`, and with the skip table of a set of long literals if `skipsAhead`,
        /// unless more than a quarter of the table's hashes would have distance zero.
        /// `maximumRetainedBytes` decides only whether the skip table fits, the depth of the dense
        /// rows, and whether the root's skip fits; whether to admit the automaton is the caller's
        /// decision.
        private LiteralSetSpanMatcher build(long maximumRetainedBytes, int maximumDenseDepth, boolean skipsAhead)
        {
            // Phase 2: number the states breadth-first and fill failure links in that order, so a
            // state's failure state, which is shallower, is final when the state is reached.
            int[] trieStateByOrder = new int[stateCount];
            int[] orderByTrieState = new int[stateCount];
            int[] failure = new int[stateCount];
            int[] depthByOrder = new int[stateCount];
            int orderedCount = 1;
            for (int value = 0; value < 256; value++) {
                int child = rootChildByByte[value];
                if (child != ROOT) {
                    failure[child] = matchLength[child] != 0 ? DEAD : ROOT;
                    orderByTrieState[child] = orderedCount;
                    depthByOrder[orderedCount] = 1;
                    trieStateByOrder[orderedCount++] = child;
                }
            }
            for (int order = 1; order < orderedCount; order++) {
                int state = trieStateByOrder[order];
                for (int child = firstChild[state]; child != ROOT; child = nextSibling[child]) {
                    orderByTrieState[child] = orderedCount;
                    depthByOrder[orderedCount] = depthByOrder[order] + 1;
                    trieStateByOrder[orderedCount++] = child;
                    if (matchLength[child] != 0) {
                        failure[child] = DEAD;
                        continue;
                    }
                    byte value = incomingByte[child];
                    int fallback = failure[state];
                    while (fallback != ROOT && fallback != DEAD && findTrieChild(firstChild[fallback], value, nextSibling, incomingByte) == ROOT) {
                        fallback = failure[fallback];
                    }
                    if (fallback == ROOT) {
                        fallback = rootChildByByte[value & 0xFF];
                    }
                    else if (fallback != DEAD) {
                        fallback = findTrieChild(firstChild[fallback], value, nextSibling, incomingByte);
                    }
                    failure[child] = fallback;
                    if (fallback != DEAD) {
                        matchLength[child] = matchLength[fallback];
                    }
                }
            }

            // Phase 3: renumber into the breadth-first order with contiguous sorted edges.
            int[] rootStateByByte = new int[256];
            for (int value = 0; value < 256; value++) {
                rootStateByByte[value] = orderByTrieState[rootChildByByte[value]];
            }
            int[] firstEdgeByState = new int[stateCount + 1];
            byte[] edgeByte = new byte[stateCount - 1];
            int[] failureByState = new int[stateCount];
            int[] matchLengthByState = new int[stateCount];
            int edgeCount = 0;
            for (int order = 0; order < stateCount; order++) {
                int state = trieStateByOrder[order];
                firstEdgeByState[order] = edgeCount;
                if (order == ROOT) {
                    for (int value = 0; value < 256; value++) {
                        if (rootChildByByte[value] != ROOT) {
                            edgeByte[edgeCount++] = (byte) value;
                        }
                    }
                }
                else {
                    for (int child = firstChild[state]; child != ROOT; child = nextSibling[child]) {
                        edgeByte[edgeCount++] = incomingByte[child];
                    }
                    failureByState[order] = failure[state] == DEAD ? DEAD : orderByTrieState[failure[state]];
                    matchLengthByState[order] = matchLength[state];
                }
            }
            firstEdgeByState[stateCount] = edgeCount;
            LiteralSetSpanMatcher matcher = new LiteralSetSpanMatcher(rootStateByByte, firstEdgeByState, edgeByte, failureByState, matchLengthByState);

            // Phase 4: the skip table of a set of long literals, where it fits. It takes the budget
            // before the dense rows: it retains at most 64 KiB against the rows' 1 MiB, and on
            // english-10 it brings searches to 0.21x the time without either, where the rows alone
            // give 0.41x.
            if (skipsAhead) {
                matcher = matcher.withSkipTable(depthByOrder, maximumRetainedBytes);
            }

            // Phase 5: dense rows for the shallowest states, at the deepest depth whose rows fit.
            matcher = matcher.withDenseRows(depthByOrder, maximumDenseDepth, maximumRetainedBytes);

            // Phase 6: the root's skip, whose scan set and any nibble tables, with the larger
            // object holding them, are a retained charge that never costs the matcher itself:
            // where they do not fit, the matcher searches without them.
            RootSkippingMatcher rootSkipping = VectorSupport.isAvailable() ? RootSkippingMatcher.build(matcher, rootChildByByte) : null;
            return rootSkipping != null && rootSkipping.estimatedRetainedSize() <= maximumRetainedBytes ? rootSkipping : matcher;
        }
    }

    /// Returns this matcher, which has no dense rows or skip table, with the skip table of its
    /// literals where each has at least [SkipTable#MINIMUM_LENGTH] bytes, [SkipTable#build] returns
    /// a table, and the matcher with it retains at most `maximumRetainedBytes`, or else this
    /// matcher. `depthByOrder` holds each state's depth, which grows with its breadth-first number.
    private LiteralSetSpanMatcher withSkipTable(int[] depthByOrder, long maximumRetainedBytes)
    {
        // A state with its own match ends a literal of its depth; every other match length a
        // state holds is its failure state's, shorter than its depth.
        int stateCount = failureByState.length;
        int minimumLength = Integer.MAX_VALUE;
        for (int state = 1; state < stateCount; state++) {
            if (matchLengthByState[state] == depthByOrder[state]) {
                minimumLength = Math.min(minimumLength, depthByOrder[state]);
            }
        }
        if (minimumLength < SkipTable.MINIMUM_LENGTH) {
            return this;
        }
        // Every literal passes through a state at the window's depth, and the path to that state
        // spells the literal's first window bytes. A state's parent is the state with the edge
        // leading to it.
        int window = Math.min(minimumLength, SkipTable.MAXIMUM_WINDOW);
        int[] parentByState = new int[stateCount];
        for (int state = 0; state < stateCount; state++) {
            for (int edge = firstEdgeByState[state]; edge < firstEdgeByState[state + 1]; edge++) {
                // Edge e leads to state e + 1.
                parentByState[edge + 1] = state;
            }
        }
        int windowStart = 1;
        while (depthByOrder[windowStart] < window) {
            windowStart++;
        }
        int windowEnd = windowStart;
        while (windowEnd < stateCount && depthByOrder[windowEnd] == window) {
            windowEnd++;
        }
        byte[] windowBytes = new byte[(windowEnd - windowStart) * window];
        for (int state = windowStart; state < windowEnd; state++) {
            int offset = (state - windowStart) * window;
            for (int index = window - 1, pathState = state; index >= 0; index--, pathState = parentByState[pathState]) {
                windowBytes[offset + index] = edgeByte[pathState - 1];
            }
        }
        SkipTable table = SkipTable.build(windowBytes, window);
        if (table == null || estimatedRetainedSize() + table.estimatedRetainedSize() > maximumRetainedBytes) {
            return this;
        }
        return new LiteralSetSpanMatcher(
                rootStateByByte,
                classByByte,
                classCount,
                denseDepth,
                denseStateCount,
                denseNext,
                table,
                firstEdgeByState,
                edgeByte,
                failureByState,
                matchLengthByState);
    }

    /// Returns this matcher, which has no dense rows, with dense rows for the states up to the
    /// deepest depth up to `maximumDenseDepth` whose rows fit [#MAXIMUM_DENSE_ROW_BYTES] and leave
    /// the matcher retaining at most `maximumRetainedBytes`, or this matcher where none fits.
    /// `depthByOrder` holds each state's depth, which grows with its breadth-first number.
    private LiteralSetSpanMatcher withDenseRows(int[] depthByOrder, int maximumDenseDepth, long maximumRetainedBytes)
    {
        int stateCount = failureByState.length;
        long retainedSize = estimatedRetainedSize();
        for (int depth = Math.min(maximumDenseDepth, depthByOrder[stateCount - 1]); depth > 0; depth--) {
            int denseStateCount = 1;
            while (denseStateCount < stateCount && depthByOrder[denseStateCount] <= depth) {
                denseStateCount++;
            }
            // The edges of the states with dense rows are the first ones, in state order.
            byte[] classByByte = new byte[256];
            int classCount = byteClasses(edgeByte, firstEdgeByState[denseStateCount], classByByte);
            long rowBytes = (long) denseStateCount * classCount * Integer.BYTES;
            if (rowBytes > MAXIMUM_DENSE_ROW_BYTES ||
                    retainedSize + SizeOf.sizeOf(classByByte) + SizeOf.sizeOfIntArray(denseStateCount * classCount) > maximumRetainedBytes) {
                continue;
            }
            int[] denseNext = denseRows(denseStateCount, classByByte, classCount);
            return new LiteralSetSpanMatcher(
                    rootStateByByte,
                    classByByte,
                    classCount,
                    depth,
                    denseStateCount,
                    denseNext,
                    skipTable,
                    firstEdgeByState,
                    edgeByte,
                    failureByState,
                    matchLengthByState);
        }
        return this;
    }

    /// Fills `classByByte` with a class for each byte that labels one of the first `edgeCount`
    /// edges and one class shared by every other byte, and returns the number of classes.
    private static int byteClasses(byte[] edgeByte, int edgeCount, byte[] classByByte)
    {
        boolean[] labels = new boolean[256];
        for (int edge = 0; edge < edgeCount; edge++) {
            labels[edgeByte[edge] & 0xFF] = true;
        }
        int classCount = 0;
        for (int value = 0; value < 256; value++) {
            if (labels[value]) {
                classByByte[value] = (byte) classCount++;
            }
        }
        // With every byte labeling an edge, there are 256 classes and no shared one.
        if (classCount < 256) {
            for (int value = 0; value < 256; value++) {
                if (!labels[value]) {
                    classByByte[value] = (byte) classCount;
                }
            }
            classCount++;
        }
        return classCount;
    }

    /// Returns the dense rows of the first `denseStateCount` states. Each failure state is
    /// shallower, so its row is complete when a state copies it before adding its own edges. The
    /// root's row, which its missing bytes leave at the root, starts the failure paths to it.
    private int[] denseRows(int denseStateCount, byte[] classByByte, int classCount)
    {
        int[] denseNext = new int[denseStateCount * classCount];
        for (int state = 0; state < denseStateCount; state++) {
            int row = state * classCount;
            if (state != ROOT) {
                int failure = failureByState[state];
                if (failure == DEAD) {
                    Arrays.fill(denseNext, row, row + classCount, DEAD);
                }
                else {
                    System.arraycopy(denseNext, failure * classCount, denseNext, row, classCount);
                }
            }
            for (int edge = firstEdgeByState[state]; edge < firstEdgeByState[state + 1]; edge++) {
                denseNext[row + (classByByte[edgeByte[edge] & 0xFF] & 0xFF)] = edge + 1;
            }
        }
        return denseNext;
    }

    private static int findTrieChild(int child, byte value, int[] nextSibling, byte[] incomingByte)
    {
        int unsignedValue = value & 0xFF;
        while (child != ROOT) {
            int childByte = incomingByte[child] & 0xFF;
            if (childByte >= unsignedValue) {
                return childByte == unsignedValue ? child : ROOT;
            }
            child = nextSibling[child];
        }
        return ROOT;
    }

    private static void insertTrieChild(int state, int child, int[] firstChild, int[] nextSibling, byte[] incomingByte)
    {
        int unsignedValue = incomingByte[child] & 0xFF;
        int previous = ROOT;
        int next = firstChild[state];
        while (next != ROOT && (incomingByte[next] & 0xFF) < unsignedValue) {
            previous = next;
            next = nextSibling[next];
        }
        nextSibling[child] = next;
        if (previous == ROOT) {
            firstChild[state] = child;
        }
        else {
            nextSibling[previous] = child;
        }
    }

    final long estimatedRetainedSize()
    {
        long trieSize = SizeOf.sizeOf(rootStateByByte) +
                SizeOf.sizeOf(classByByte) +
                SizeOf.sizeOf(denseNext) +
                SizeOf.sizeOf(firstEdgeByState) +
                SizeOf.sizeOf(edgeByte) +
                SizeOf.sizeOf(failureByState) +
                SizeOf.sizeOf(matchLengthByState) +
                (skipTable == null ? 0 : skipTable.estimatedRetainedSize());
        if (this instanceof RootSkippingMatcher rootSkipping) {
            return RootSkippingMatcher.INSTANCE_SIZE + trieSize + SizeOf.sizeOf(rootSkipping.rootByteNibbleTables);
        }
        return INSTANCE_SIZE + trieSize;
    }

    /// Returns this matcher without the root's skip: one sharing its trie arrays, any dense rows,
    /// and any skip table, or this matcher if it has none. It retains what the matcher built
    /// without the skip would.
    final LiteralSetSpanMatcher withoutRootSkipping()
    {
        if (this instanceof RootSkippingMatcher) {
            return new LiteralSetSpanMatcher(
                    rootStateByByte,
                    classByByte,
                    classCount,
                    denseDepth,
                    denseStateCount,
                    denseNext,
                    skipTable,
                    firstEdgeByState,
                    edgeByte,
                    failureByState,
                    matchLengthByState);
        }
        return this;
    }

    /// Returns this matcher without the tables that only speed up its search of the trie, its
    /// dense rows and skip table: one sharing its trie arrays and any root skip, or this matcher if
    /// it has neither. It keeps the root skip it has and does not reconsider one the tables left
    /// no room for.
    final LiteralSetSpanMatcher withoutSearchTables()
    {
        if (denseNext == null && skipTable == null) {
            return this;
        }
        LiteralSetSpanMatcher trie = new LiteralSetSpanMatcher(rootStateByByte, firstEdgeByState, edgeByte, failureByState, matchLengthByState);
        if (this instanceof RootSkippingMatcher rootSkipping) {
            return new RootSkippingMatcher(trie, rootSkipping.rootByteScanSet, rootSkipping.rootByteNibbleTables);
        }
        return trie;
    }

    /// Returns the depth of the deepest states with dense rows, or `0` without them.
    final int denseDepthForDiagnostics()
    {
        return denseDepth;
    }

    /// Returns whether the set has a skip table, with which a search of at least
    /// [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES] skips ahead by windows of its long literals.
    final boolean skipsAheadForDiagnostics()
    {
        return skipTable != null;
    }

    /// Returns the scan set of the root's skip for [Dfa#findByteSet], or `0` without the skip.
    final int rootByteScanSetForDiagnostics()
    {
        return this instanceof RootSkippingMatcher rootSkipping ? rootSkipping.rootByteScanSet : 0;
    }

    /// Returns whether a search at the root with `remaining` bytes left, the current byte
    /// included, may skip to the next leading byte with the root's vector scan; shorter searches
    /// keep the byte-by-byte root walk, which won on the benchmark's short rows. See
    /// CompiledPattern.MINIMUM_LONG_SEARCH_BYTES.
    final boolean skipsRootBytes(int remaining)
    {
        return remaining >= CompiledPattern.MINIMUM_LONG_SEARCH_BYTES && this instanceof RootSkippingMatcher;
    }

    /// Returns the number of non-overlapping leftmost-first matches in `input`. Its literals never
    /// match empty, so each search resumes at the previous match's end.
    final long count(Slice input)
    {
        long count = 0;
        int searchStart = 0;
        while (searchStart < input.length()) {
            long span = findSpan(input, searchStart, input.length());
            if (span < 0) {
                break;
            }
            count++;
            searchStart = (int) span;
        }
        return count;
    }

    /// Finds the leftmost-first match in `[start, end)` of `input`; the caller checks that
    /// `0 <= start <= end <= input.length()`. This is the crate's leftmost search: record the
    /// match on entering a match state, and return the last one recorded on reaching [#DEAD] or
    /// the end. A search that may skip root bytes, see [#skipsRootBytes(int)], skips from a byte
    /// without a root edge at the root to the next byte with one. It stops skipping for good once
    /// [VectorByteSetScanner#SHORT_SCAN_LIMIT] skips in a row each skipped fewer than
    /// [VectorByteSetScanner#SHORT_SCAN_BYTES] bytes: on prose such as `word. word.`, a skip lands
    /// on the next word. The forward DFA's count self-loop scan takes the same limit, but there it
    /// may re-arm its scan instead of stopping. The rest of that search, and every search that may
    /// not skip, is the plain trie walk. A search of at least
    /// [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES] in a set with a skip table skips ahead by
    /// windows instead, see the class doc; where the set has the root skip, it scans under the
    /// same short-scan limit.
    final long findSpan(Slice input, int start, int end)
    {
        return findSpan(input, start, end, null);
    }

    /// Returns the end of the leftmost-first match that starts at `start` in `[start, end)` of
    /// `input`, or `-1`; the caller checks that `0 <= start <= end <= input.length()`. The walk
    /// follows the trie's edges from the root and keeps the deepest state with its own match on
    /// the path, one whose match length is its depth. The states below a state with its own match
    /// belong only to earlier literals, which extend it, so the deeper match has priority, and a
    /// literal the trie omits loses to the earlier literal it extends, which also starts here. So
    /// the result is `end` exactly when all of `[start, end)` is a literal the trie holds; a
    /// literal the trie omits, one extending an earlier literal, is not found here, see
    /// [ExtendingLiterals].
    final int findPrefixEnd(Slice input, int start, int end)
    {
        int inputOffset = input.byteArrayOffset();
        int matchLength = (int) (walkPrefix(input.byteArray(), inputOffset + start, inputOffset + end) >>> 32);
        return matchLength == 0 ? -1 : start + matchLength;
    }

    // Walks the trie's edges from the root along the bytes from array index `position` to array
    // index `inputEnd`, and returns the match length of the deepest state on the walked path that
    // ends a literal, or zero for none, in the high half, and the edges followed in the low half.
    private long walkPrefix(byte[] bytes, int position, int inputEnd)
    {
        int matchLength = 0;
        int depth = 0;
        int state = ROOT;
        while (position + depth < inputEnd) {
            byte value = bytes[position + depth];
            state = state == ROOT ? rootStateByByte[value & 0xFF] : findChild(state, value);
            if (state == ROOT) {
                break;
            }
            depth++;
            if (matchLengthByState[state] == depth) {
                matchLength = depth;
            }
        }
        return ((long) matchLength << 32) | depth;
    }

    /// Returns [#findSpan(Slice,int,int)], adding its root scans, window skips and automaton
    /// handoffs to `diagnostics`.
    final long findSpanForDiagnostics(Slice input, int start, int end, SkipDiagnostics diagnostics)
    {
        requireNonNull(diagnostics, "diagnostics is null");
        long[] counts = new long[AUTOMATON_HANDOFF_COUNT + 1];
        long span = findSpan(input, start, end, counts);
        diagnostics.rootSkipCount += counts[ROOT_SKIP_COUNT];
        diagnostics.windowSkipCount += counts[WINDOW_SKIP_COUNT];
        diagnostics.automatonHandoffCount += counts[AUTOMATON_HANDOFF_COUNT];
        return span;
    }

    // Picks the search's walk once, so a search that may not skip root bytes never checks for a
    // skip. A null `counts`, as every production search passes, counts nothing. It is an array
    // rather than a SkipDiagnostics because C2 does not inline a method whose signature names a
    // class that is not loaded, and production never loads SkipDiagnostics.
    private long findSpan(Slice input, int start, int end, long[] counts)
    {
        if (skipTable == null || end - start < CompiledPattern.MINIMUM_LONG_SEARCH_BYTES) {
            return searchAutomaton(input, start, end, counts);
        }
        if (this instanceof RootSkippingMatcher rootSkipping) {
            return skipAheadScanningRootBytes(input, start, end, rootSkipping.rootByteScanSet, rootSkipping.rootByteNibbleTables, counts);
        }
        return skipAhead(input, start, end, counts);
    }

    // The automaton's search of `[start, end)`, from the root with no match recorded.
    private long searchAutomaton(Slice input, int start, int end, long[] counts)
    {
        if (!skipsRootBytes(end - start)) {
            int inputOffset = input.byteArrayOffset();
            return walkTrie(input, inputOffset + start, inputOffset + end, Dfa.SEARCH_NO_MATCH);
        }
        RootSkippingMatcher rootSkipping = (RootSkippingMatcher) this;
        return walkTrieSkippingRootBytes(input, start, end, rootSkipping.rootByteScanSet, rootSkipping.rootByteNibbleTables, counts);
    }

    // Skips from window start to window start until a trie walk finds a literal starting at one.
    // Every literal has at least a window of bytes, so none starts after the last full window's
    // start. Once a failed walk takes the edges the walks followed past their allowance, the
    // automaton searches the rest from the next position, where no literal starts before it.
    private long skipAhead(Slice input, int start, int end, long[] counts)
    {
        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int inputEnd = inputOffset + end;
        SkipTable table = skipTable;
        int lastWindowStart = inputEnd - table.window;
        long walked = 0;
        int searchStart = inputOffset + start;
        int position = searchStart;
        while (position <= lastWindowStart) {
            int shift = table.shift(bytes, position);
            if (shift != 0) {
                addCount(counts, WINDOW_SKIP_COUNT);
                position += shift;
                continue;
            }
            long prefix = walkPrefix(bytes, position, inputEnd);
            int matchLength = (int) (prefix >>> 32);
            if (matchLength != 0) {
                int matchStart = position - inputOffset;
                return ((long) matchStart << 32) | ((matchStart + matchLength) & 0xFFFF_FFFFL);
            }
            walked += (int) prefix;
            position++;
            if (walked > 2L * (position - searchStart) + WALK_ALLOWANCE) {
                addCount(counts, AUTOMATON_HANDOFF_COUNT);
                return searchAutomaton(input, position - inputOffset, end, counts);
            }
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    // The skip ahead of a search that may skip root bytes, with the root skip's scan set and any
    // nibble tables. At a window start whose byte and the next lead no literal, it scans to the
    // next leading byte, the rule of walkTrieSkippingRootBytes, where skipAhead would step a
    // window at a time across text that no literal starts in. Its loop repeats skipAhead's for
    // the reason walkTrieSkippingRootBytes repeats walkTrie's, and once the short-skip limit ends
    // scanning, the rest of the search is skipAhead from the leading byte the last scan found.
    // Like the automaton's root skip, it skips only from a window start at least
    // CompiledPattern.MINIMUM_LONG_SEARCH_BYTES before the end; the automaton searches the rest
    // from the first window start past that, where no literal starts before it. 64 exceeds
    // SkipTable.MAXIMUM_WINDOW, so each window it checks fits before the search's end.
    private long skipAheadScanningRootBytes(Slice input, int start, int end, int rootByteScanSet, byte[] rootByteNibbleTables, long[] counts)
    {
        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int inputEnd = inputOffset + end;
        SkipTable table = skipTable;
        int lastSkipStart = inputEnd - CompiledPattern.MINIMUM_LONG_SEARCH_BYTES;
        long walked = 0;
        int searchStart = inputOffset + start;
        int position = searchStart;
        int shortScanCount = 0;
        while (position <= lastSkipStart) {
            if (rootStateByByte[bytes[position] & 0xFF] == ROOT && rootStateByByte[bytes[position + 1] & 0xFF] == ROOT) {
                addCount(counts, ROOT_SKIP_COUNT);
                int leadingBytePosition = Dfa.findByteSet(bytes, position + 1, inputEnd - position - 1, rootByteScanSet, rootByteNibbleTables);
                if (leadingBytePosition < 0) {
                    return Dfa.SEARCH_NO_MATCH;
                }
                shortScanCount = VectorByteSetScanner.shortScanCount(shortScanCount, leadingBytePosition - position - 1);
                if (shortScanCount == VectorByteSetScanner.SHORT_SCAN_LIMIT) {
                    return skipAhead(input, leadingBytePosition - inputOffset, end, counts);
                }
                position = leadingBytePosition;
                continue;
            }
            int shift = table.shift(bytes, position);
            if (shift != 0) {
                addCount(counts, WINDOW_SKIP_COUNT);
                position += shift;
                continue;
            }
            long prefix = walkPrefix(bytes, position, inputEnd);
            int matchLength = (int) (prefix >>> 32);
            if (matchLength != 0) {
                int matchStart = position - inputOffset;
                return ((long) matchStart << 32) | ((matchStart + matchLength) & 0xFFFF_FFFFL);
            }
            walked += (int) prefix;
            position++;
            if (walked > 2L * (position - searchStart) + WALK_ALLOWANCE) {
                addCount(counts, AUTOMATON_HANDOFF_COUNT);
                return searchAutomaton(input, position - inputOffset, end, counts);
            }
        }
        return searchAutomaton(input, position - inputOffset, end, counts);
    }

    // The plain trie walk from the root at array index `position` to array index `inputEnd`,
    // with `match` recorded before `position`. It has no skip check and calls only the inlined
    // sparseTransition, so C2 keeps the trie arrays and the state in registers across the loop.
    private long walkTrie(Slice input, int position, int inputEnd, long match)
    {
        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int state = ROOT;
        for (; position < inputEnd; position++) {
            byte value = bytes[position];
            if (state == ROOT) {
                state = rootStateByByte[value & 0xFF];
            }
            else {
                state = state < denseStateCount ? denseNext[state * classCount + (classByByte[value & 0xFF] & 0xFF)] : sparseTransition(state, value);
                if (state == DEAD) {
                    return match;
                }
            }
            int matchLength = matchLengthByState[state];
            if (matchLength != 0) {
                int matchEnd = position + 1 - inputOffset;
                match = ((long) (matchEnd - matchLength) << 32) | (matchEnd & 0xFFFF_FFFFL);
            }
        }
        return match;
    }

    // The trie walk of a search that may skip root bytes, with the skip's scan set and any nibble
    // tables. Its trie step repeats walkTrie's instead of sharing one loop with it: the skip's
    // checks and kernel calls would then sit in the loop of every search, and their calls make
    // C2 reload the trie arrays and spill the state around them. Once the short-skip limit ends
    // skipping, the rest of the search is walkTrie from the leading byte the last scan found.
    private long walkTrieSkippingRootBytes(Slice input, int start, int end, int rootByteScanSet, byte[] rootByteNibbleTables, long[] counts)
    {
        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int inputEnd = inputOffset + end;
        long match = Dfa.SEARCH_NO_MATCH;
        int state = ROOT;
        int shortSkipCount = 0;
        for (int position = inputOffset + start; position < inputEnd; position++) {
            byte value = bytes[position];
            if (state == ROOT) {
                state = rootStateByByte[value & 0xFF];
            }
            else {
                state = state < denseStateCount ? denseNext[state * classCount + (classByByte[value & 0xFF] & 0xFF)] : sparseTransition(state, value);
                if (state == DEAD) {
                    return match;
                }
            }
            // Only the root's row, read directly or through a dense row that copies it, leads to
            // the root, for a byte that leads no literal.
            if (state == ROOT) {
                // The skip starts only at a second root miss in a row: on dense text, where most
                // bytes lead a literal, a vector call that lands a byte ahead costs more than the
                // step it saves. Text whose misses come in short runs, such as the `. ` between
                // words, passes that check, so the short-skip limit ends skipping there.
                if (inputEnd - position >= CompiledPattern.MINIMUM_LONG_SEARCH_BYTES && rootStateByByte[bytes[position + 1] & 0xFF] == ROOT) {
                    addCount(counts, ROOT_SKIP_COUNT);
                    int leadingBytePosition = Dfa.findByteSet(bytes, position + 1, inputEnd - position - 1, rootByteScanSet, rootByteNibbleTables);
                    if (leadingBytePosition < 0) {
                        return match;
                    }
                    shortSkipCount = VectorByteSetScanner.shortScanCount(shortSkipCount, leadingBytePosition - position - 1);
                    if (shortSkipCount == VectorByteSetScanner.SHORT_SCAN_LIMIT) {
                        return walkTrie(input, leadingBytePosition, inputEnd, match);
                    }
                    position = leadingBytePosition - 1;
                }
                continue;
            }
            int matchLength = matchLengthByState[state];
            if (matchLength != 0) {
                int matchEnd = position + 1 - inputOffset;
                match = ((long) (matchEnd - matchLength) << 32) | (matchEnd & 0xFFFF_FFFFL);
            }
        }
        return match;
    }

    // Adds one to the count at `index` of a diagnostics search; every production search passes
    // null.
    private static void addCount(long[] counts, int index)
    {
        if (counts != null) {
            counts[index]++;
        }
    }

    // The transition of a state without a dense row: its edge, else its failure state's
    // transition, read from the root's row or a dense row once the failure path reaches one.
    private int sparseTransition(int state, byte value)
    {
        while (true) {
            int next = findChild(state, value);
            if (next != ROOT) {
                return next;
            }
            state = failureByState[state];
            if (state == DEAD) {
                return DEAD;
            }
            if (state < denseStateCount) {
                return state == ROOT ? rootStateByByte[value & 0xFF] : denseNext[state * classCount + (classByByte[value & 0xFF] & 0xFF)];
            }
        }
    }

    // Edge e leads to state e + 1, and no edge leads to the root, so ROOT means no transition.
    // A state's edges are sorted by byte, and a byte above the last edge, or any byte at a state
    // with no edges, is rejected first, so the scan always stops at or before the last edge and
    // needs no bound test.
    private int findChild(int state, byte value)
    {
        int unsignedValue = value & 0xFF;
        int edge = firstEdgeByState[state];
        int lastEdge = firstEdgeByState[state + 1] - 1;
        if (edge > lastEdge || (edgeByte[lastEdge] & 0xFF) < unsignedValue) {
            return ROOT;
        }
        while ((edgeByte[edge] & 0xFF) < unsignedValue) {
            edge++;
        }
        return (edgeByte[edge] & 0xFF) == unsignedValue ? edge + 1 : ROOT;
    }

    /// The literals of a large set that extend another of its literals, such as `samwise` in
    /// `sam|samwise` or `samwise|sam`, which a full match needs beside the automaton: the trie
    /// omits every such literal that follows the one it extends, since a search never reports it.
    /// Every other literal is a trie state with its own match, see
    /// [LiteralSetSpanMatcher#findPrefixEnd], so a string is one of the set's literals exactly
    /// when the trie holds it or it is one of these. They are kept in unsigned byte order, packed
    /// into one array, for a binary search.
    static final class ExtendingLiterals
    {
        private static final int INSTANCE_SIZE = SizeOf.instanceSize(ExtendingLiterals.class);

        private final byte[] literalBytes;
        // The end of each literal in literalBytes, where the next one starts.
        private final int[] literalEnds;

        private ExtendingLiterals(byte[] literalBytes, int[] literalEnds)
        {
            this.literalBytes = literalBytes;
            this.literalEnds = literalEnds;
        }

        /// Returns the extending literals of the large set [LiteralSetSpanMatcher#analyze] would
        /// build from `expression`, or `null` where the expression is unsupported or they, or the
        /// trie spelling them, would retain more than `maximumRetainedBytes`.
        static ExtendingLiterals analyze(Regexp expression, long maximumRetainedBytes)
        {
            requireNonNull(expression, "expression is null");
            Trie trie = LiteralAlternationSpanMatcher.spellEveryLiteral(expression, maximumRetainedBytes);
            return trie == null ? null : trie.extendingLiterals(maximumRetainedBytes);
        }

        private static long retainedSize(int byteCount, int literalCount)
        {
            return INSTANCE_SIZE + SizeOf.sizeOfByteArray(byteCount) + SizeOf.sizeOfIntArray(literalCount);
        }

        long estimatedRetainedSize()
        {
            return retainedSize(literalBytes.length, literalEnds.length);
        }

        /// Returns whether all of `[start, end)` of `input` is one of these literals.
        boolean contains(Slice input, int start, int end)
        {
            byte[] bytes = input.byteArray();
            int inputStart = input.byteArrayOffset() + start;
            int inputEnd = input.byteArrayOffset() + end;
            int low = 0;
            int high = literalEnds.length - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int literalStart = middle == 0 ? 0 : literalEnds[middle - 1];
                int comparison = Arrays.compareUnsigned(literalBytes, literalStart, literalEnds[middle], bytes, inputStart, inputEnd);
                if (comparison == 0) {
                    return true;
                }
                if (comparison < 0) {
                    low = middle + 1;
                }
                else {
                    high = middle - 1;
                }
            }
            return false;
        }
    }

    /// Counts what a diagnostics search of a literal set reads.
    static final class SkipDiagnostics
    {
        private long rootSkipCount;
        private long windowSkipCount;
        private long automatonHandoffCount;

        /// The vector scans that skipped from the root to the next byte leading a literal.
        long rootSkipCount()
        {
            return rootSkipCount;
        }

        /// The skips ahead from one window start to a later one.
        long windowSkipCount()
        {
            return windowSkipCount;
        }

        /// The searches that skipped ahead until their walks read their allowance and then ran
        /// the automaton.
        long automatonHandoffCount()
        {
            return automatonHandoffCount;
        }
    }

    /// The Wu-Manber skip table of a set whose literals all have at least [#MINIMUM_LENGTH]
    /// bytes. Each window is as long as the shortest literal, at most [#MAXIMUM_WINDOW] bytes, and
    /// its last block is three bytes for a window of six or more and two for a shorter one. Each
    /// hash entry stores the least shift that any block with that hash allows, the distance from
    /// that block's end to the window's end, over the blocks within any literal's first window
    /// bytes. A hash no block has stores `window - block + 1`. Shifts are measured from the window
    /// start, so no literal starts within a window's shift of its start, and a zero shift leaves
    /// the trie walk to decide whether one starts there.
    static final class SkipTable
    {
        static final int MINIMUM_LENGTH = 4;
        static final int MAXIMUM_WINDOW = 16;
        private static final int INSTANCE_SIZE = SizeOf.instanceSize(SkipTable.class);
        private static final int MINIMUM_HASH_BITS = 10;
        private static final int MAXIMUM_HASH_BITS = 16;
        private static final int HASH_MULTIPLIER = 0x9E3779B1;

        // The skip distance of each block hash.
        private final byte[] shiftByHash;
        private final int window;
        // The shift that keeps a window's last block of the three bytes read at its end.
        private final int blockShift;
        // The shift that turns a block, times HASH_MULTIPLIER, into its hash.
        private final int hashShift;

        private SkipTable(byte[] shiftByHash, int window, int block, int bits)
        {
            this.shiftByHash = shiftByHash;
            this.window = window;
            this.blockShift = Byte.SIZE * (3 - block);
            this.hashShift = Integer.SIZE - bits;
        }

        /// Returns the table of the windows packed in `windowBytes`, `window` bytes each, or
        /// `null` where more than a quarter of its hashes have distance zero, which leaves too
        /// little to skip.
        private static SkipTable build(byte[] windowBytes, int window)
        {
            int block = window >= 6 ? 3 : 2;
            int windowCount = windowBytes.length / window;
            long targetEntries = (long) windowCount * (window - block + 1) * 4;
            int bits = Math.clamp(Long.SIZE - Long.numberOfLeadingZeros(targetEntries - 1), MINIMUM_HASH_BITS, MAXIMUM_HASH_BITS);
            byte[] shiftByHash = new byte[1 << bits];
            Arrays.fill(shiftByHash, (byte) (window - block + 1));
            for (int offset = 0; offset < windowBytes.length; offset += window) {
                for (int blockEnd = block - 1; blockEnd < window; blockEnd++) {
                    int packed = 0;
                    for (int index = 0; index < block; index++) {
                        packed |= (windowBytes[offset + blockEnd - block + 1 + index] & 0xFF) << (Byte.SIZE * index);
                    }
                    int hash = (packed * HASH_MULTIPLIER) >>> (Integer.SIZE - bits);
                    shiftByHash[hash] = (byte) Math.min(shiftByHash[hash], window - 1 - blockEnd);
                }
            }
            int zeros = 0;
            for (byte shift : shiftByHash) {
                if (shift == 0) {
                    zeros++;
                }
            }
            return zeros > shiftByHash.length / 4 ? null : new SkipTable(shiftByHash, window, block, bits);
        }

        private long estimatedRetainedSize()
        {
            return INSTANCE_SIZE + SizeOf.sizeOf(shiftByHash);
        }

        /// Returns the number of positions from the window start at array index `position` of
        /// `bytes` at which no literal starts, or `0` where one may start at `position`.
        private int shift(byte[] bytes, int position)
        {
            // The window's last three bytes, of which a block of two keeps the last two.
            int blockEnd = position + window - 1;
            int packed = (bytes[blockEnd - 2] & 0xFF) | (bytes[blockEnd - 1] & 0xFF) << 8 | (bytes[blockEnd] & 0xFF) << 16;
            return shiftByHash[((packed >>> blockShift) * HASH_MULTIPLIER) >>> hashShift];
        }
    }

    /// A matcher that skips the root's missing bytes; see [#skipsRootBytes(int)].
    static final class RootSkippingMatcher
            extends LiteralSetSpanMatcher
    {
        private static final int INSTANCE_SIZE = SizeOf.instanceSize(RootSkippingMatcher.class);

        // The scan set of the root's leading bytes for Dfa.findByteSet: two or three bytes or a
        // contiguous range, which need no tables, or Dfa.MIXED_BYTE_SET_MARKER.
        private final int rootByteScanSet;
        // The nibble tables of a mixed scan set, or null.
        private final byte[] rootByteNibbleTables;

        private RootSkippingMatcher(LiteralSetSpanMatcher trie, int rootByteScanSet, byte[] rootByteNibbleTables)
        {
            super(trie.rootStateByByte,
                    trie.classByByte,
                    trie.classCount,
                    trie.denseDepth,
                    trie.denseStateCount,
                    trie.denseNext,
                    trie.skipTable,
                    trie.firstEdgeByState,
                    trie.edgeByte,
                    trie.failureByState,
                    trie.matchLengthByState);
            this.rootByteScanSet = rootByteScanSet;
            this.rootByteNibbleTables = rootByteNibbleTables;
        }

        /// Returns `trie` with the skip of its root's leading bytes, `rootChildByByte`'s non-root
        /// entries, or `null` where neither of the forward DFA's scans takes them: the scan set
        /// [Dfa#buildStartByteScanSet] selects, else the nibble tables, which take 4 to 64 bytes
        /// with at most 8 high nibbles. Loaded only with the Vector API, as the DFA builds its
        /// tables.
        private static RootSkippingMatcher build(LiteralSetSpanMatcher trie, int[] rootChildByByte)
        {
            byte[] leadingBytes = new byte[256];
            for (int value = 0; value < 256; value++) {
                if (rootChildByByte[value] != ROOT) {
                    leadingBytes[value] = 1;
                }
            }
            int scanSet = Dfa.buildStartByteScanSet(leadingBytes);
            if (scanSet != 0) {
                return new RootSkippingMatcher(trie, scanSet, null);
            }
            byte[] nibbleTables = VectorByteSetScanner.buildNibbleTables(leadingBytes);
            return nibbleTables == null ? null : new RootSkippingMatcher(trie, Dfa.MIXED_BYTE_SET_MARKER, nibbleTables);
        }
    }
}
