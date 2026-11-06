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
/// The trie omits a literal whose path passes through an earlier literal's match state, since
/// leftmost-first priority means it can never match. A state with its own match therefore keeps
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
    /// bounds the states the build adds but not the root's row or the object overhead, so a
    /// caller admits the result by its [#estimatedRetainedSize()]. The root's skip is built only
    /// where the matcher with it still retains at most `maximumRetainedBytes`, so a matcher a
    /// caller admitted without it is admitted with it or built without it.
    static LiteralSetSpanMatcher analyze(Regexp expression, int capturingGroupCount, long maximumRetainedBytes)
    {
        requireNonNull(expression, "expression is null");
        if (capturingGroupCount != 0) {
            return null;
        }

        List<byte[]> literals = LiteralAlternationSpanMatcher.extractLiteralSet(expression);
        if (literals == null || literals.size() <= LiteralAlternationSpanMatcher.MAXIMUM_ALTERNATIVES) {
            return null;
        }
        for (byte[] literal : literals) {
            // An empty literal matches everywhere, which the automaton's search does not report.
            if (literal.length == 0) {
                return null;
            }
        }
        return build(literals, maximumRetainedBytes);
    }

    /// Builds the automaton of non-empty `literals`, listed in priority order, or returns `null`
    /// if its states would retain more than `maximumRetainedBytes`.
    private static LiteralSetSpanMatcher build(List<byte[]> literals, long maximumRetainedBytes)
    {
        // Literals sharing a long prefix expand to far more bytes than the trie has states, so
        // the trie arrays grow with the states added, up to the most the budget can retain.
        long totalLiteralBytes = LiteralAlternationSpanMatcher.totalLength(literals);
        int maximumStates = (int) Math.min(totalLiteralBytes + 1, Math.max(1, maximumRetainedBytes / RETAINED_BYTES_PER_STATE));

        // Phase 1: the leftmost-first trie. Each state records the byte of its incoming edge and
        // its children as a list sorted by byte, so the breadth-first pass emits sorted edges.
        int[] rootChildByByte = new int[256];
        int capacity = Math.min(maximumStates, INITIAL_TRIE_CAPACITY);
        int[] firstChild = new int[capacity];
        int[] nextSibling = new int[capacity];
        byte[] incomingByte = new byte[capacity];
        int[] matchLength = new int[capacity];
        int stateCount = 1;
        nextLiteral:
        for (byte[] literal : literals) {
            int state = ROOT;
            for (byte value : literal) {
                if (matchLength[state] != 0) {
                    continue nextLiteral;
                }
                int child = state == ROOT ? rootChildByByte[value & 0xFF] : findTrieChild(firstChild[state], value, nextSibling, incomingByte);
                if (child == ROOT) {
                    if (stateCount == capacity) {
                        if (capacity == maximumStates) {
                            return null;
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
                }
                state = child;
            }
            matchLength[state] = literal.length;
        }

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

        // Phase 4: the root's skip, whose scan set and any nibble tables, with the larger object
        // holding them, are a retained charge that never costs the matcher itself: where they do
        // not fit, the matcher searches without them.
        RootSkippingMatcher rootSkipping = VectorSupport.isAvailable() ? RootSkippingMatcher.build(matcher, rootChildByByte) : null;
        return rootSkipping != null && rootSkipping.estimatedRetainedSize() <= maximumRetainedBytes ? rootSkipping : matcher;
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
    private int findChild(int state, byte value)
    {
        int unsignedValue = value & 0xFF;
        int edgeEnd = firstEdgeByState[state + 1];
        for (int edge = firstEdgeByState[state]; edge < edgeEnd; edge++) {
            int candidate = edgeByte[edge] & 0xFF;
            if (candidate >= unsignedValue) {
                return candidate == unsignedValue ? edge + 1 : ROOT;
            }
        }
        return ROOT;
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
