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
import static io.airlift.regulator.Regexp.LATIN1;
import static java.util.Objects.requireNonNull;

final class LiteralAlternationSpanMatcher
{
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(LiteralAlternationSpanMatcher.class);
    private static final int MAXIMUM_ALTERNATIVES = 64;
    private static final int MAXIMUM_ANALYSIS_DEPTH = 256;

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

        List<byte[]> literalAlternatives = extractLiteralAlternatives(expression, 0);
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

    private static List<byte[]> extractLiteralAlternatives(Regexp expression, int depth)
    {
        if (depth >= MAXIMUM_ANALYSIS_DEPTH) {
            return null;
        }
        return switch (expression.op()) {
            case LITERAL -> literal(expression, new int[] {expression.rune()});
            case LITERAL_STRING -> literal(expression, expression.runes());
            case ALTERNATE -> alternate(expression, depth);
            case CONCAT -> concatenate(expression, depth);
            default -> null;
        };
    }

    private static List<byte[]> literal(Regexp expression, int[] runes)
    {
        if ((expression.parseFlags() & FOLD_CASE) != 0) {
            return null;
        }
        return List.of(Regexp.convertRunesToBytes((expression.parseFlags() & LATIN1) != 0, runes));
    }

    private static List<byte[]> alternate(Regexp expression, int depth)
    {
        List<byte[]> alternatives = new ArrayList<>();
        for (Regexp child : expression.children()) {
            List<byte[]> childAlternatives = extractLiteralAlternatives(child, depth + 1);
            if (childAlternatives == null || alternatives.size() + childAlternatives.size() > MAXIMUM_ALTERNATIVES) {
                return null;
            }
            alternatives.addAll(childAlternatives);
        }
        return alternatives;
    }

    private static List<byte[]> concatenate(Regexp expression, int depth)
    {
        List<byte[]> prefixes = List.of(new byte[0]);
        for (Regexp child : expression.children()) {
            List<byte[]> suffixes = extractLiteralAlternatives(child, depth + 1);
            if (suffixes == null || (long) prefixes.size() * suffixes.size() > MAXIMUM_ALTERNATIVES) {
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
