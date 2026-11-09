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
/// `try_find_fwd`), which the `regex` crate uses for such patterns, so each input byte follows
/// one transition plus the failure links it amortizes.
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
/// At the root, a search skips the bytes that lead no literal with a vector scan for the root's
/// leading bytes. Such a byte leaves the state at the root and records nothing, so the skip is
/// exact. A search at the root has no match pending, since once one is recorded no state the
/// search reaches fails back to the root. The scan uses the forward DFA's kernel selection,
/// [Dfa#buildStartByteScanSet] and [Dfa#findByteSet] over two or three bytes or a contiguous
/// range, and otherwise its nibble tables, which take 4 to 64 bytes with at most 8 high nibbles.
/// It is not the DFA's start-byte skip, which prefers its start literal filter for up to 16
/// common start bytes, covers a single start byte with its prefix scan, and vector-scans only
/// with [Dfa#MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES] bytes left. A matcher with the skip is a
/// [RootSkippingMatcher], so one without it retains only its trie arrays and the object holding
/// them, and the skip costs nothing where it is not built. No method is overridden: the
/// searches reach the skip's scan set by testing `this` against the subclass.
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

    private final int[] rootStateByByte;
    // The first edge of each state, plus a final entry ending the last state's edges.
    private final int[] firstEdgeByState;
    private final byte[] edgeByte;
    private final int[] failureByState;
    // The byte length of the literal a search reports on entering the state, or zero.
    private final int[] matchLengthByState;

    private LiteralSetSpanMatcher(int[] rootStateByByte, int[] firstEdgeByState, byte[] edgeByte, int[] failureByState, int[] matchLengthByState)
    {
        this.rootStateByByte = rootStateByByte;
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
    /// build a multiple of it; past that, the build takes the literals one at a time. The root's
    /// skip is built only where the matcher with it still retains at most `maximumRetainedBytes`,
    /// so a matcher a caller admitted without it is admitted with it or built without it.
    static LiteralSetSpanMatcher analyze(Regexp expression, int capturingGroupCount, long maximumRetainedBytes)
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
            return trie == null ? null : trie.build(maximumRetainedBytes);
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
        return trie.build(maximumRetainedBytes);
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
        // The root's skip is built only where it fits, so the least set has none.
        return attempt.trie().build(0).estimatedRetainedSize();
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

        /// Builds the automaton of the literals added. `maximumRetainedBytes` decides only whether
        /// the root's skip fits; whether to admit the automaton is the caller's decision.
        private LiteralSetSpanMatcher build(long maximumRetainedBytes)
        {
            // Phase 2: number the states breadth-first and fill failure links in that order, so a
            // state's failure state, which is shallower, is final when the state is reached.
            int[] trieStateByOrder = new int[stateCount];
            int[] orderByTrieState = new int[stateCount];
            int[] failure = new int[stateCount];
            int orderedCount = 1;
            for (int value = 0; value < 256; value++) {
                int child = rootChildByByte[value];
                if (child != ROOT) {
                    failure[child] = matchLength[child] != 0 ? DEAD : ROOT;
                    orderByTrieState[child] = orderedCount;
                    trieStateByOrder[orderedCount++] = child;
                }
            }
            for (int order = 1; order < orderedCount; order++) {
                int state = trieStateByOrder[order];
                for (int child = firstChild[state]; child != ROOT; child = nextSibling[child]) {
                    orderByTrieState[child] = orderedCount;
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

            // Phase 4: the root's skip, whose scan set and any nibble tables, with the larger
            // object holding them, are a retained charge that never costs the matcher itself:
            // where they do not fit, the matcher searches without them.
            RootSkippingMatcher rootSkipping = VectorSupport.isAvailable() ? RootSkippingMatcher.build(matcher, rootChildByByte) : null;
            return rootSkipping != null && rootSkipping.estimatedRetainedSize() <= maximumRetainedBytes ? rootSkipping : matcher;
        }
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
                SizeOf.sizeOf(firstEdgeByState) +
                SizeOf.sizeOf(edgeByte) +
                SizeOf.sizeOf(failureByState) +
                SizeOf.sizeOf(matchLengthByState);
        if (this instanceof RootSkippingMatcher rootSkipping) {
            return RootSkippingMatcher.INSTANCE_SIZE + trieSize + SizeOf.sizeOf(rootSkipping.rootByteNibbleTables);
        }
        return INSTANCE_SIZE + trieSize;
    }

    /// Returns this matcher without the root's skip: one sharing its trie arrays, or this matcher
    /// if it has none. It retains what the matcher built without the skip would.
    final LiteralSetSpanMatcher withoutRootSkipping()
    {
        if (this instanceof RootSkippingMatcher) {
            return new LiteralSetSpanMatcher(rootStateByByte, firstEdgeByState, edgeByte, failureByState, matchLengthByState);
        }
        return this;
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
    /// without a root edge at the root to the next byte with one. It stops skipping once
    /// [VectorByteSetScanner#SHORT_SCAN_LIMIT] skips in a row each skipped fewer than
    /// [VectorByteSetScanner#SHORT_SCAN_BYTES] bytes, the rule of the forward DFA's count
    /// self-loop scan: on prose such as `word. word.`, a skip lands on the next word. The rest of
    /// that search, and every search that may not skip, is the plain trie walk.
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
        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int matchEnd = -1;
        int state = ROOT;
        for (int position = start; position < end; position++) {
            byte value = bytes[inputOffset + position];
            state = state == ROOT ? rootStateByByte[value & 0xFF] : findChild(state, value);
            if (state == ROOT) {
                break;
            }
            if (matchLengthByState[state] == position + 1 - start) {
                matchEnd = position + 1;
            }
        }
        return matchEnd;
    }

    /// Returns [#findSpan(Slice,int,int)], adding the root skips it makes to `diagnostics`.
    final long findSpanForDiagnostics(Slice input, int start, int end, SkipDiagnostics diagnostics)
    {
        requireNonNull(diagnostics, "diagnostics is null");
        long[] rootSkipCount = new long[1];
        long span = findSpan(input, start, end, rootSkipCount);
        diagnostics.rootSkipCount += rootSkipCount[0];
        return span;
    }

    // Picks the search's walk once, so a search that may not skip never checks for a skip. A
    // null `rootSkipCount`, as every production search passes, counts nothing. It is an array
    // rather than a SkipDiagnostics because C2 does not inline a method whose signature names a
    // class that is not loaded, and production never loads SkipDiagnostics.
    private long findSpan(Slice input, int start, int end, long[] rootSkipCount)
    {
        if (!skipsRootBytes(end - start)) {
            int inputOffset = input.byteArrayOffset();
            return walkTrie(input, inputOffset + start, inputOffset + end, Dfa.SEARCH_NO_MATCH);
        }
        RootSkippingMatcher rootSkipping = (RootSkippingMatcher) this;
        return walkTrieSkippingRootBytes(input, start, end, rootSkipping.rootByteScanSet, rootSkipping.rootByteNibbleTables, rootSkipCount);
    }

    // The plain trie walk from the root at array index `position` to array index `inputEnd`,
    // with `match` recorded before `position`. It has no skip check and calls only the inlined
    // child lookup, so C2 keeps the trie arrays and the state in registers across the loop.
    private long walkTrie(Slice input, int position, int inputEnd, long match)
    {
        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int state = ROOT;
        for (; position < inputEnd; position++) {
            byte value = bytes[position];
            int next;
            while (true) {
                if (state == ROOT) {
                    next = rootStateByByte[value & 0xFF];
                    break;
                }
                next = findChild(state, value);
                if (next != ROOT) {
                    break;
                }
                state = failureByState[state];
                if (state == DEAD) {
                    return match;
                }
            }
            state = next;
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
    private long walkTrieSkippingRootBytes(Slice input, int start, int end, int rootByteScanSet, byte[] rootByteNibbleTables, long[] rootSkipCount)
    {
        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int inputEnd = inputOffset + end;
        long match = Dfa.SEARCH_NO_MATCH;
        int state = ROOT;
        int shortSkipCount = 0;
        for (int position = inputOffset + start; position < inputEnd; position++) {
            byte value = bytes[position];
            int next;
            while (true) {
                if (state == ROOT) {
                    next = rootStateByByte[value & 0xFF];
                    break;
                }
                next = findChild(state, value);
                if (next != ROOT) {
                    break;
                }
                state = failureByState[state];
                if (state == DEAD) {
                    return match;
                }
            }
            // Only the root's row leads back to the root, for a byte that leads no literal, so
            // the state stays at the root.
            if (next == ROOT) {
                // The skip starts only at a second root miss in a row: on dense text, where most
                // bytes lead a literal, a vector call that lands a byte ahead costs more than the
                // step it saves. Text whose misses come in short runs, such as the `. ` between
                // words, passes that check, so the short-skip limit ends skipping there.
                if (inputEnd - position >= CompiledPattern.MINIMUM_LONG_SEARCH_BYTES && rootStateByByte[bytes[position + 1] & 0xFF] == ROOT) {
                    if (rootSkipCount != null) {
                        rootSkipCount[0]++;
                    }
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
            state = next;
            int matchLength = matchLengthByState[state];
            if (matchLength != 0) {
                int matchEnd = position + 1 - inputOffset;
                match = ((long) (matchEnd - matchLength) << 32) | (matchEnd & 0xFFFF_FFFFL);
            }
        }
        return match;
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

        /// The vector scans that skipped from the root to the next byte leading a literal.
        long rootSkipCount()
        {
            return rootSkipCount;
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
            super(trie.rootStateByByte, trie.firstEdgeByState, trie.edgeByte, trie.failureByState, trie.matchLengthByState);
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
