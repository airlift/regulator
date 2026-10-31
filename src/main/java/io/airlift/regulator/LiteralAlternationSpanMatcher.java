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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.airlift.regulator.Regexp.FOLD_CASE;
import static io.airlift.regulator.Regexp.FULL_CASE_FOLD;
import static io.airlift.regulator.Regexp.LATIN1;
import static java.util.Objects.requireNonNull;

/// Finds the leftmost-first match of an alternation of at most [#MAXIMUM_ALTERNATIVES] literal
/// strings, such as `ERROR|WARN|FATAL`, by walking a trie from each position, which serves short
/// Trino patterns. [LiteralSetSpanMatcher] searches a larger alternation, whose literals this
/// class's analysis also extracts.
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

    private final short[] rootStateByLeadingByte;
    private final short[] firstEdgeByState;
    private final short[] nextEdge;
    private final short[] targetState;
    private final byte[] edgeByte;
    private final short[] alternativeByState;

    private LiteralAlternationSpanMatcher(
            short[] rootStateByLeadingByte,
            short[] firstEdgeByState,
            short[] nextEdge,
            short[] targetState,
            byte[] edgeByte,
            short[] alternativeByState)
    {
        this.rootStateByLeadingByte = rootStateByLeadingByte;
        this.firstEdgeByState = firstEdgeByState;
        this.nextEdge = nextEdge;
        this.targetState = targetState;
        this.edgeByte = edgeByte;
        this.alternativeByState = alternativeByState;
    }

    static LiteralAlternationSpanMatcher analyze(Regexp expression, int capturingGroupCount)
    {
        requireNonNull(expression, "expression is null");
        if (capturingGroupCount != 0) {
            return null;
        }

        List<byte[]> literalAlternatives = extractLiteralAlternatives(expression, false, false, new long[1], 0);
        if (literalAlternatives == null || literalAlternatives.size() < 2) {
            return null;
        }

        int totalLiteralBytes = 0;
        for (byte[] literal : literalAlternatives) {
            if (literal.length == 0 || literal.length >= Short.MAX_VALUE - totalLiteralBytes) {
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
                alternativeByState);
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE +
                SizeOf.sizeOf(rootStateByLeadingByte) +
                SizeOf.sizeOf(firstEdgeByState) +
                SizeOf.sizeOf(nextEdge) +
                SizeOf.sizeOf(targetState) +
                SizeOf.sizeOf(edgeByte) +
                SizeOf.sizeOf(alternativeByState);
    }

    /// Expands `expression` into the literals of a large set for [LiteralSetSpanMatcher], in
    /// leftmost-first priority order, or returns `null`.
    static List<byte[]> extractLiteralSet(Regexp expression)
    {
        // The compiler reads a class as bytes when the whole program is Latin-1, so this does too.
        boolean latin1Program = (expression.parseFlags() & LATIN1) != 0;
        return extractLiteralAlternatives(expression, true, latin1Program, new long[1], 0);
    }

    /// Expands `expression` into its literals in leftmost-first priority order: a concatenation
    /// lists each prefix choice's literals before the next prefix choice's. The trie takes literal
    /// strings only, at most [#MAXIMUM_ALTERNATIVES]; a large set also takes empty matches and
    /// small classes, and concatenations whose only choice of several literals ends them, up to
    /// [#MAXIMUM_LITERAL_BYTES]. `work` holds the work charged so far, up to
    /// [#MAXIMUM_EXTRACTION_WORK].
    private static List<byte[]> extractLiteralAlternatives(Regexp expression, boolean largeSet, boolean latin1Program, long[] work, int depth)
    {
        if (depth >= MAXIMUM_ANALYSIS_DEPTH) {
            return null;
        }
        return switch (expression.op()) {
            case LITERAL -> literal(expression, new int[] {expression.rune()});
            case LITERAL_STRING -> literal(expression, expression.runes());
            case ALTERNATE -> alternate(expression, largeSet, latin1Program, work, depth);
            case CONCAT -> concatenate(expression, largeSet, latin1Program, work, depth);
            case EMPTY_MATCH -> largeSet ? List.of(new byte[0]) : null;
            case CHAR_CLASS -> largeSet ? characterClass(expression, latin1Program) : null;
            default -> null;
        };
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

    private static List<byte[]> alternate(Regexp expression, boolean largeSet, boolean latin1Program, long[] work, int depth)
    {
        List<byte[]> alternatives = new ArrayList<>();
        long alternativeBytes = 0;
        for (Regexp child : expression.children()) {
            List<byte[]> childAlternatives = extractLiteralAlternatives(child, largeSet, latin1Program, work, depth + 1);
            if (childAlternatives == null) {
                return null;
            }
            if (largeSet) {
                alternativeBytes += totalLength(childAlternatives);
                if (alternatives.size() + childAlternatives.size() > MAXIMUM_LITERAL_BYTES || alternativeBytes > MAXIMUM_LITERAL_BYTES) {
                    return null;
                }
            }
            else if (alternatives.size() + childAlternatives.size() > MAXIMUM_ALTERNATIVES) {
                return null;
            }
            if (!charge(work, childAlternatives.size())) {
                return null;
            }
            alternatives.addAll(childAlternatives);
        }
        return alternatives;
    }

    private static List<byte[]> concatenate(Regexp expression, boolean largeSet, boolean latin1Program, long[] work, int depth)
    {
        List<byte[]> prefixes = List.of(new byte[0]);
        for (Regexp child : expression.children()) {
            List<byte[]> suffixes = extractLiteralAlternatives(child, largeSet, latin1Program, work, depth + 1);
            if (suffixes == null) {
                return null;
            }
            long suffixBytes = totalLength(suffixes);
            if (largeSet) {
                // A large set takes the factored prefixes of a dictionary, such as
                // word(?:s|ing|ed), whose choice of several literals ends the concatenation, so
                // the literals share the pattern's prefixes and the automaton stays within the
                // pattern's size. A choice before more text, as in (?:a|b)c or [ab][cd],
                // expands to every combination. A child of empty literals only, such as the
                // (?:|) of a duplicate word, adds nothing in either position.
                if (suffixBytes == 0) {
                    continue;
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
            prefixes = combined;
        }
        return prefixes;
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

    long findSpan(Slice input, int start)
    {
        requireNonNull(input, "input is null");
        if (start < 0 || start > input.length()) {
            return Dfa.SEARCH_NO_MATCH;
        }

        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int inputEnd = inputOffset + input.length();
        for (int position = inputOffset + start; position < inputEnd; position++) {
            int state = rootStateByLeadingByte[bytes[position] & 0xFF];
            if (state == 0) {
                continue;
            }

            int bestAlternative = alternativeByState[state];
            int bestEnd = bestAlternative < 0 ? -1 : position + 1;
            int inputIndex = position + 1;
            while (inputIndex < inputEnd) {
                int nextState = findChild(state, bytes[inputIndex]);
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

    private int findChild(int state, byte value)
    {
        return findChild(state, value, firstEdgeByState, nextEdge, targetState, edgeByte);
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
