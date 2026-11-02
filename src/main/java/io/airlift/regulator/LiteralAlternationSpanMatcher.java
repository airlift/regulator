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
/// strings, such as `ERROR|WARN|FATAL`, by walking a trie at each position it does not skip,
/// which serves short Trino patterns. [LiteralSetSpanMatcher] searches a larger alternation,
/// whose literals this class's analysis also extracts.
final class LiteralAlternationSpanMatcher
{
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(LiteralAlternationSpanMatcher.class);
    static final int MAXIMUM_ALTERNATIVES = 64;
    private static final int MAXIMUM_ANALYSIS_DEPTH = 256;
    // Bounds the bytes a large alternation's factored prefixes expand to, and also the number of
    // literals, since empty ones add no bytes; a dictionary of 43,029 English words holds about
    // 400 KB.
    private static final int MAXIMUM_LITERAL_BYTES = 1 << 22;
    // Bounds the work of a whole extraction, the bytes its concatenations copy plus the entries
    // its alternations add, which a long concatenation or nested choices make quadratic in the
    // pattern even when each step stays under MAXIMUM_LITERAL_BYTES. A sorted dictionary of
    // 43,029 words charges about 2 million units, and one of 235,976 words about 7 million.
    private static final long MAXIMUM_EXTRACTION_WORK = 1L << 24;
    // A class the parsers merge from a dictionary's single-character alternatives holds a few
    // members, such as [ds] from word(?:d|s); a wider one, such as [!-~], is not a literal set.
    private static final int MAXIMUM_CLASS_RUNES = 16;
    // The search restarts the trie at every input byte it does not skip, so a long literal costs
    // its length per byte on a dense near-miss input; such a pattern keeps the general engine's
    // linear scan.
    // At most 64 alternatives of this length also keep every trie index within a short.
    private static final int MAXIMUM_TRIE_LITERAL_BYTES = 64;
    // A denser filter would stop the vector scan so often that restarting it would cost more
    // than the root table's one lookup per byte.
    private static final double MAXIMUM_START_LITERAL_FILTER_DENSITY = 1.0 / 64;
    // Shorter inputs keep the root-table loop: a call's vector setup costs more than it saves,
    // and short everyday values keep the route they had before the filter.
    private static final int MINIMUM_START_LITERAL_FILTER_BYTES = 64;
    /// What [#findSpanOrDecline] and [#countOrDecline] return when the start-scan productivity
    /// check at the start of the input finds the filter's stops dense, for a caller that holds the
    /// general engine to run in their place. It only needs to differ from every span, count, and
    /// [Dfa#SEARCH_NO_MATCH], so it reuses the DFA's sentinel for a candidate search that falls
    /// back.
    static final long DECLINED = Dfa.CANDIDATE_SEARCH_FALLBACK;

    private final short[] rootStateByLeadingByte;
    private final short[] firstEdgeByState;
    private final short[] nextEdge;
    private final short[] targetState;
    private final byte[] edgeByte;
    private final short[] alternativeByState;
    // Null without the Vector API, or when the literals admit no selective filter.
    private final StartLiteralFilter startLiteralFilter;
    // Observability, updated without synchronization as the DFA's scan counters are.
    private long filterStopCount;
    private long rootTablePositionCount;
    private long sampledPositionCount;

    private LiteralAlternationSpanMatcher(
            short[] rootStateByLeadingByte,
            short[] firstEdgeByState,
            short[] nextEdge,
            short[] targetState,
            byte[] edgeByte,
            short[] alternativeByState,
            StartLiteralFilter startLiteralFilter)
    {
        this.rootStateByLeadingByte = rootStateByLeadingByte;
        this.firstEdgeByState = firstEdgeByState;
        this.nextEdge = nextEdge;
        this.targetState = targetState;
        this.edgeByte = edgeByte;
        this.alternativeByState = alternativeByState;
        this.startLiteralFilter = startLiteralFilter;
    }

    static LiteralAlternationSpanMatcher analyze(Regexp expression, int capturingGroupCount)
    {
        requireNonNull(expression, "expression is null");
        if (capturingGroupCount != 0) {
            return null;
        }

        List<byte[]> literalAlternatives = extractLiteralAlternatives(expression, false, false);
        if (literalAlternatives == null || literalAlternatives.size() < 2) {
            return null;
        }

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
                int nextState = findChild(
                        state,
                        literal[literalIndex],
                        firstEdgeByState,
                        nextEdge,
                        targetState,
                        edgeByte);
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
        return new LiteralAlternationSpanMatcher(
                rootStateByLeadingByte,
                firstEdgeByState,
                nextEdge,
                targetState,
                edgeByte,
                alternativeByState,
                VectorSupport.isAvailable() ? buildStartLiteralFilter(literalAlternatives) : null);
    }

    // Each literal is one path of the byte sets Prog.buildStartPositionByteSets returns: its byte
    // at each position below its length, and every byte at the positions past it, so the filter
    // never tests a position some literal does not reach. The filter therefore accepts every
    // position where a literal starts.
    private static StartLiteralFilter buildStartLiteralFilter(List<byte[]> literals)
    {
        long[][] paths = new long[literals.size()][];
        for (int index = 0; index < paths.length; index++) {
            byte[] literal = literals.get(index);
            long[] path = new long[StartLiteralFilter.PATH_POSITIONS * 4];
            for (int position = 0; position < StartLiteralFilter.PATH_POSITIONS; position++) {
                if (position < literal.length) {
                    int value = literal[position] & 0xFF;
                    path[position * 4 + (value >>> 6)] |= 1L << value;
                }
                else {
                    Arrays.fill(path, position * 4, path.length, -1L);
                    break;
                }
            }
            paths[index] = path;
        }
        StartLiteralFilter filter = StartLiteralFilter.build(paths, ByteFrequencies.englishText());
        return filter != null && filter.density() <= MAXIMUM_START_LITERAL_FILTER_DENSITY ? filter : null;
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE +
                SizeOf.sizeOf(rootStateByLeadingByte) +
                SizeOf.sizeOf(firstEdgeByState) +
                SizeOf.sizeOf(nextEdge) +
                SizeOf.sizeOf(targetState) +
                SizeOf.sizeOf(edgeByte) +
                SizeOf.sizeOf(alternativeByState) +
                (startLiteralFilter == null ? 0 : startLiteralFilter.estimatedRetainedSize());
    }

    /// True when a search with `length` bytes left may skip to the start literal filter's
    /// candidates rather than look up every byte in the root table. A search with at least 64 KiB
    /// left still reads the root table when its sample finds the filter's stops dense.
    boolean usesStartLiteralFilter(int length)
    {
        return startLiteralFilter != null && length >= MINIMUM_START_LITERAL_FILTER_BYTES;
    }

    /// The start literal filter built from the literals, or `null` when the matcher has none.
    StartLiteralFilter startLiteralFilterForDiagnostics()
    {
        return startLiteralFilter;
    }

    /// The positions where the start literal filter's scan stopped for the trie.
    long filterStopCountForDiagnostics()
    {
        return filterStopCount;
    }

    /// The positions the scalar root-table loop read in searches and counts that began with at
    /// least 64 bytes left. Shorter ones always read the root table, and count nothing. Filter
    /// stops also look up the root table, but [#filterStopCountForDiagnostics()] counts them.
    long rootTablePositionCountForDiagnostics()
    {
        return rootTablePositionCount;
    }

    /// The positions the start-scan productivity checks could read, at most
    /// [Dfa#BYTE_SCAN_SAMPLE_SIZE] for each check.
    long sampledPositionCountForDiagnostics()
    {
        return sampledPositionCount;
    }

    /// Expands `expression` into the literals of a large set for [LiteralSetSpanMatcher], in
    /// leftmost-first priority order, or returns `null`.
    static List<byte[]> extractLiteralSet(Regexp expression)
    {
        // The compiler reads a class as bytes when the whole program is Latin-1, so this does too.
        boolean latin1Program = (expression.parseFlags() & LATIN1) != 0;
        return extractLiteralAlternatives(expression, true, latin1Program);
    }

    /// Expands `expression` into its literals in leftmost-first priority order: a concatenation
    /// lists each prefix choice's literals before the next prefix choice's. The trie takes literal
    /// strings only, at most [#MAXIMUM_ALTERNATIVES]; a large set also takes empty matches and
    /// small classes, and concatenations whose only choice of several literals ends them, up to
    /// [#MAXIMUM_LITERAL_BYTES], and the whole extraction stops past [#MAXIMUM_EXTRACTION_WORK]
    /// or nesting more than [#MAXIMUM_ANALYSIS_DEPTH] levels deep. The walk keeps its nesting on
    /// an explicit stack, so a deeply nested pattern cannot overflow the thread's stack.
    private static List<byte[]> extractLiteralAlternatives(Regexp expression, boolean largeSet, boolean latin1Program)
    {
        long[] work = new long[1];
        Deque<Frame> stack = new ArrayDeque<>();
        stack.addLast(new Frame(expression, largeSet, latin1Program));
        while (true) {
            Frame frame = stack.getLast();
            if (frame.literals == null) {
                return null;
            }
            Regexp child = frame.nextChild();
            if (child != null) {
                // The new frame's depth is the number of frames enclosing it.
                if (stack.size() >= MAXIMUM_ANALYSIS_DEPTH) {
                    return null;
                }
                stack.addLast(new Frame(child, largeSet, latin1Program));
                continue;
            }

            stack.removeLast();
            if (stack.isEmpty()) {
                return frame.literals;
            }
            stack.getLast().acceptChild(frame.literals, largeSet, work);
        }
    }

    private static List<byte[]> literal(Regexp expression, int[] runes)
    {
        if ((expression.parseFlags() & (FOLD_CASE | FULL_CASE_FOLD)) != 0) {
            return null;
        }
        return List.of(Regexp.convertRunesToBytes((expression.parseFlags() & LATIN1) != 0, runes));
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
    /// earlier children spell, or returns `null`.
    private static List<byte[]> concatenate(List<byte[]> prefixes, List<byte[]> suffixes, boolean largeSet, long[] work)
    {
        long suffixBytes = totalLength(suffixes);
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
        else if ((long) prefixes.size() * suffixes.size() > MAXIMUM_ALTERNATIVES) {
            return null;
        }
        // Measured only now, so a skipped child does not rescan the prefixes.
        long combinedBytes = totalLength(prefixes) * suffixes.size() + suffixBytes * prefixes.size();
        if ((largeSet && combinedBytes > MAXIMUM_LITERAL_BYTES) || !charge(work, combinedBytes)) {
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

    private static boolean charge(long[] work, long amount)
    {
        work[0] += amount;
        return work[0] <= MAXIMUM_EXTRACTION_WORK;
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
    long count(Slice input)
    {
        return count(input, false);
    }

    /// Returns [#count(Slice)], or [#DECLINED] when the filter's stops are dense at the start.
    long countOrDecline(Slice input)
    {
        return count(input, true);
    }

    private long count(Slice input, boolean declinesDenseStart)
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
        while (position < inputEnd) {
            if (position >= routeEnd) {
                readsRootTable = readsRootTable(bytes, position, inputEnd);
                if (declinesDenseStart && position == inputOffset && readsRootTable && startLiteralFilter != null) {
                    return DECLINED;
                }
                routeEnd = routeEnd(position, inputEnd);
            }
            long span = findSpanInRoute(bytes, position, routeEnd, readsRootTable, inputOffset, inputEnd);
            if (span < 0) {
                position = routeEnd;
                continue;
            }
            count++;
            position = inputOffset + (int) span;
        }
        return count;
    }

    long findSpan(Slice input, int start)
    {
        return findSpan(input, start, null, false);
    }

    /// Returns the span of the first match at or after `start`, or [Dfa#SEARCH_NO_MATCH]. A
    /// `cursor`, which may be null, carries the route from one search of `input` to the next.
    long findSpan(Slice input, int start, Cursor cursor)
    {
        return findSpan(input, start, cursor, false);
    }

    /// Returns [#findSpan(Slice,int,Cursor)] from the start of `input` without a cursor, or
    /// [#DECLINED] when the filter's stops are dense at the start.
    long findSpanOrDecline(Slice input)
    {
        return findSpan(input, 0, null, true);
    }

    private long findSpan(Slice input, int start, Cursor cursor, boolean declinesDenseStart)
    {
        requireNonNull(input, "input is null");
        if (start < 0 || start > input.length()) {
            return Dfa.SEARCH_NO_MATCH;
        }

        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int inputEnd = inputOffset + input.length();
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
        boolean readsRootTable = cursor != null && cursor.readsRootTable;
        long span = Dfa.SEARCH_NO_MATCH;
        while (position < inputEnd) {
            if (position >= routeEnd) {
                readsRootTable = readsRootTable(bytes, position, inputEnd);
                if (declinesDenseStart && position == firstPosition && readsRootTable && startLiteralFilter != null) {
                    return DECLINED;
                }
                routeEnd = routeEnd(position, inputEnd);
            }
            span = findSpanInRoute(bytes, position, routeEnd, readsRootTable, inputOffset, inputEnd);
            if (span >= 0) {
                break;
            }
            position = routeEnd;
        }
        if (cursor != null) {
            cursor.routeEnd = routeEnd - inputOffset;
            cursor.readsRootTable = readsRootTable;
        }
        return span;
    }

    // True when the route from `position` reads every byte through the root table rather than
    // skipping to the filter's stops: there is no filter, or at least 64 KiB remain and the DFA's
    // start-scan productivity check finds the stops too dense in its sample. Near misses that
    // the filter passes and the trie rejects, such as ERxO under ERROR|WARN|FATAL every fifth
    // byte, make frequent filter stops, and restarting the vector scan at each costs more than
    // one root-table lookup per byte.
    private boolean readsRootTable(byte[] bytes, int position, int inputEnd)
    {
        if (startLiteralFilter == null) {
            return true;
        }
        if (inputEnd - position < Dfa.MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES) {
            return false;
        }
        // Each check reads at most this many positions.
        sampledPositionCount += Dfa.BYTE_SCAN_SAMPLE_SIZE;
        return !Dfa.isByteCandidateScanProductive(
                null,
                startLiteralFilter,
                bytes,
                position,
                inputEnd,
                Dfa.MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES,
                Dfa.BYTE_SCAN_SAMPLE_CANDIDATE_LIMIT);
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

    // Returns the span of the first match that starts in `[position, routeEnd)`, whose trie walk
    // may read to the input end, or Dfa.SEARCH_NO_MATCH. Each pass searches either one filter stop
    // or the rest of the route, so the trie walk has one call site: C2 inlined a copy at each of
    // two sites, which grew this method past its inline size limit, and its callers then called
    // it rather than inlining it.
    private long findSpanInRoute(byte[] bytes, int position, int routeEnd, boolean readsRootTable, int inputOffset, int inputEnd)
    {
        // The filter accepts every position where a literal starts, so the positions it skips
        // hold no match, and the trie decides each one it stops at. Every literal reaches the
        // filter's last position, so a scan that reads that far past the route end finds every
        // stop before the route end.
        int scanEnd = readsRootTable ? routeEnd : Math.min(inputEnd, routeEnd + startLiteralFilter.thirdOffset());
        while (true) {
            boolean filterStop = !readsRootTable && position < routeEnd && usesStartLiteralFilter(inputEnd - position);
            if (filterStop) {
                position = VectorByteSetScanner.findLiteralSet(bytes, position, scanEnd - position, startLiteralFilter);
                if (position < 0) {
                    return Dfa.SEARCH_NO_MATCH;
                }
                filterStopCount++;
            }
            long span = findSpanInRootTable(bytes, position, filterStop ? position + 1 : routeEnd, inputOffset, inputEnd);
            if (!filterStop) {
                // The filter's scan can stop past the route end, leaving the root table nothing to read.
                int readEnd = span < 0 ? Math.max(position, routeEnd) : inputOffset + (int) (span >>> 32) + 1;
                rootTablePositionCount += readEnd - position;
                return span;
            }
            if (span >= 0) {
                return span;
            }
            position++;
        }
    }

    // Returns the span of the first match that starts in `[position, routeEnd)`, or
    // Dfa.SEARCH_NO_MATCH. Each position's byte is looked up in the root table, and the trie is
    // walked from each one the table admits for the earliest alternative in pattern order. A
    // filter stop searches its one position here too. Walking in this loop keeps the child
    // lookup inlined: C2 compiled a separate walk method into its callers before that method's
    // own profile matured, and without call counts it left the child lookup a call.
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

    /// An expression the extraction is expanding, with the literals it spells so far: a leaf's
    /// own, or those its walked children combine to.
    private static final class Frame
    {
        private final Regexp expression;
        private int nextChildIndex;
        // Null once the expression has no literal expansion within the bounds.
        private List<byte[]> literals;
        // The bytes of an alternation's literals so far.
        private long literalBytes;

        private Frame(Regexp expression, boolean largeSet, boolean latin1Program)
        {
            this.expression = expression;
            literals = switch (expression.op()) {
                case LITERAL -> literal(expression, new int[] {expression.rune()});
                case LITERAL_STRING -> literal(expression, expression.runes());
                // An alternation starts with no literals, and a concatenation with the empty one.
                case ALTERNATE -> new ArrayList<>();
                case CONCAT -> List.of(new byte[0]);
                case EMPTY_MATCH -> largeSet ? List.of(new byte[0]) : null;
                case CHAR_CLASS -> largeSet ? characterClass(expression, latin1Program) : null;
                default -> null;
            };
        }

        private Regexp nextChild()
        {
            if (nextChildIndex >= expression.childCount()) {
                return null;
            }
            return expression.child(nextChildIndex++);
        }

        // Only alternations and concatenations start with literals and have children.
        private void acceptChild(List<byte[]> childLiterals, boolean largeSet, long[] work)
        {
            literals = switch (expression.op()) {
                case ALTERNATE -> alternate(childLiterals, largeSet, work);
                case CONCAT -> concatenate(literals, childLiterals, largeSet, work);
                default -> throw new IllegalStateException("unexpected literal parent: " + expression.op());
            };
        }

        /// Adds an alternation child's literals after those of the children before it, or returns
        /// `null`.
        private List<byte[]> alternate(List<byte[]> childAlternatives, boolean largeSet, long[] work)
        {
            if (largeSet) {
                literalBytes += totalLength(childAlternatives);
                if (literals.size() + childAlternatives.size() > MAXIMUM_LITERAL_BYTES || literalBytes > MAXIMUM_LITERAL_BYTES) {
                    return null;
                }
            }
            else if (literals.size() + childAlternatives.size() > MAXIMUM_ALTERNATIVES) {
                return null;
            }
            if (!charge(work, childAlternatives.size())) {
                return null;
            }
            literals.addAll(childAlternatives);
            return literals;
        }
    }

    /// The route one search of an input chose and the position it holds to, which a matcher's
    /// next search of the same input continues instead of sampling the filter again, as the
    /// DFA's group-zero cursor keeps its scan rejection. Positions are relative to the input.
    /// A new cursor is reset; a caller must [#reset()] it before searching a different input,
    /// since a route end carried past that input's end would read outside it.
    static final class Cursor
    {
        private int routeEnd;
        private boolean readsRootTable;

        /// Forgets the route, so the next search chooses one from its start position.
        void reset()
        {
            routeEnd = 0;
            readsRootTable = false;
        }
    }
}
