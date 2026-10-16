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

import io.airlift.regulator.TrinoLikeParser.Any;
import io.airlift.regulator.TrinoLikeParser.Element;
import io.airlift.regulator.TrinoLikeParser.Literal;
import io.airlift.regulator.TrinoLikeParser.ZeroOrMore;
import io.airlift.slice.Slice;

import java.util.Arrays;
import java.util.List;

/**
 * Matches Trino LIKE patterns with {@code _} over UTF-8 characters. Malformed input is read one
 * maximal subpart at a time ({@link Utf8#characterWidth}); such a character is consumed by
 * {@code _} or {@code %} but never equals a literal code point.
 */
final class TrinoLikeWildcardMatcher
{
    private static final int MATCH_ANY = -1;
    private static final int MATCH_NONE = -2;
    // A malformed character equals no literal code point and no match marker.
    private static final int INVALID_CHARACTER = Integer.MIN_VALUE;
    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private final boolean exact;
    private final boolean[] loopback;
    private final int[] match;
    private final int acceptState;
    private final int directAnyCount;

    TrinoLikeWildcardMatcher(List<Element> pattern, int start, int end, boolean exact)
    {
        this.exact = exact;
        int stateCount = calculateStateCount(pattern, start, end);
        this.loopback = new boolean[stateCount];
        this.match = new int[stateCount];
        Arrays.fill(match, MATCH_NONE);
        this.acceptState = stateCount - 1;
        this.directAnyCount = exact && start == end && pattern.get(start) instanceof Any any ? any.count() : -1;

        int state = 0;
        for (int elementIndex = start; elementIndex <= end; elementIndex++) {
            switch (pattern.get(elementIndex)) {
                case Literal literal -> {
                    Slice bytes = literal.bytes();
                    int position = bytes.byteArrayOffset();
                    int literalEnd = position + bytes.length();
                    while (position < literalEnd) {
                        long decoded = Utf8.decode(bytes.byteArray(), position, literalEnd);
                        match[state++] = Utf8.decodedCodePoint(decoded);
                        position += Utf8.decodedWidth(decoded);
                    }
                }
                case Any any -> {
                    for (int anyIndex = 0; anyIndex < any.count(); anyIndex++) {
                        match[state++] = MATCH_ANY;
                    }
                }
                case ZeroOrMore _ -> loopback[state] = true;
            }
        }
    }

    boolean matches(Slice input, int offset, int length)
    {
        byte[] bytes = input.byteArray();
        int position = input.byteArrayOffset() + offset;
        int end = position + length;
        if (directAnyCount == 1) {
            // Without a loop: when the profiled input has one character width, C2 makes the loop
            // below a counted loop, and its range check setup costs more than the character.
            return position < end && position + Utf8.characterWidth(bytes, position, end) == end;
        }
        if (directAnyCount >= 0) {
            // Up to four characters step without a loop, for the same reason as one: C2 would
            // make the loop below a counted loop, whose setup costs more than a short run. A
            // longer run pays the setup once for the loop's faster steps.
            if (position >= end) {
                return false;
            }
            position += Utf8.characterWidth(bytes, position, end);
            if (position >= end) {
                return false;
            }
            position += Utf8.characterWidth(bytes, position, end);
            if (directAnyCount == 2) {
                return position == end;
            }
            if (position >= end) {
                return false;
            }
            position += Utf8.characterWidth(bytes, position, end);
            if (directAnyCount == 3) {
                return position == end;
            }
            if (position >= end) {
                return false;
            }
            position += Utf8.characterWidth(bytes, position, end);
            for (int anyIndex = 4; anyIndex < directAnyCount; anyIndex++) {
                if (position >= end) {
                    return false;
                }
                position += Utf8.characterWidth(bytes, position, end);
            }
            return position == end;
        }

        Workspace workspace = WORKSPACE.get();
        workspace.ensureCapacity(match.length);
        int[] currentStates = workspace.currentStates;
        int[] nextStates = workspace.nextStates;
        int currentStateCount = 1;
        currentStates[0] = 0;
        boolean accept = false;

        while (position < end) {
            int codePoint = bytes[position] & 0xFF;
            if (codePoint < 0x80) {
                position++;
            }
            else {
                int width = Utf8.characterWidth(bytes, position, end);
                codePoint = nonAsciiCodePoint(bytes, position, width);
                position += width;
            }

            int generation = workspace.nextGeneration();
            int nextStateCount = 0;
            accept = false;
            for (int stateIndex = 0; stateIndex < currentStateCount; stateIndex++) {
                int state = currentStates[stateIndex];
                if (loopback[state] && workspace.markSeen(state, generation)) {
                    nextStates[nextStateCount++] = state;
                    accept |= state == acceptState;
                }
                int nextState = state + 1;
                if ((match[state] == MATCH_ANY || match[state] == codePoint) &&
                        workspace.markSeen(nextState, generation)) {
                    nextStates[nextStateCount++] = nextState;
                    accept |= nextState == acceptState;
                }
            }
            if (nextStateCount == 0) {
                return false;
            }
            if (!exact && accept) {
                return true;
            }

            int[] previousStates = currentStates;
            currentStates = nextStates;
            nextStates = previousStates;
            currentStateCount = nextStateCount;
        }
        return accept;
    }

    private static int calculateStateCount(List<Element> pattern, int start, int end)
    {
        int stateCount = 1;
        for (int elementIndex = start; elementIndex <= end; elementIndex++) {
            switch (pattern.get(elementIndex)) {
                case Literal literal -> stateCount += countCodePoints(literal.bytes());
                case Any any -> stateCount += any.count();
                case ZeroOrMore _ -> {}
            }
        }
        return stateCount;
    }

    private static int countCodePoints(Slice literal)
    {
        byte[] bytes = literal.byteArray();
        int position = literal.byteArrayOffset();
        int end = position + literal.length();
        int count = 0;
        while (position < end) {
            long decoded = Utf8.decode(bytes, position, end);
            position += Utf8.decodedWidth(decoded);
            count++;
        }
        return count;
    }

    // A character is well formed exactly when it is as long as its lead byte announces;
    // every shorter non-ASCII character is a maximal subpart of malformed input.
    private static int nonAsciiCodePoint(byte[] input, int position, int width)
    {
        int header = input[position] & 0xFF;
        if (width == 2 && header < 0xE0) {
            return ((header & 0b0001_1111) << 6) | (input[position + 1] & 0b0011_1111);
        }
        if (width == 3 && header < 0xF0) {
            return ((header & 0b0000_1111) << 12) |
                    ((input[position + 1] & 0b0011_1111) << 6) |
                    (input[position + 2] & 0b0011_1111);
        }
        if (width == 4) {
            return ((header & 0b0000_0111) << 18) |
                    ((input[position + 1] & 0b0011_1111) << 12) |
                    ((input[position + 2] & 0b0011_1111) << 6) |
                    (input[position + 3] & 0b0011_1111);
        }
        return INVALID_CHARACTER;
    }

    private static final class Workspace
    {
        private int[] currentStates = new int[0];
        private int[] nextStates = new int[0];
        private int[] seenGeneration = new int[0];
        private int generation;

        private void ensureCapacity(int stateCount)
        {
            if (currentStates.length >= stateCount) {
                return;
            }
            currentStates = new int[stateCount];
            nextStates = new int[stateCount];
            seenGeneration = new int[stateCount + 1];
            generation = 0;
        }

        private int nextGeneration()
        {
            generation++;
            if (generation == 0) {
                Arrays.fill(seenGeneration, 0);
                generation = 1;
            }
            return generation;
        }

        private boolean markSeen(int state, int generation)
        {
            if (seenGeneration[state] == generation) {
                return false;
            }
            seenGeneration[state] = generation;
            return true;
        }
    }
}
