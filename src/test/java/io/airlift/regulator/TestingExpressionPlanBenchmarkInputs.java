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

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class TestingExpressionPlanBenchmarkInputs
{
    private static final int PADDING = 19;
    private static final byte[] MULTIBYTE_CODE_POINT = {(byte) 0xF0, (byte) 0x9F, (byte) 0x92, (byte) 0xB0};

    private TestingExpressionPlanBenchmarkInputs() {}

    public enum Workload
    {
        EMPTY_ABSENT(0, 1, Position.ABSENT, Layout.COMPACT, 1, false, Outcome.NONE),
        EXACT_MATCH(16, 16, Position.EXACT, Layout.COMPACT, 2, false, Outcome.ALL),
        TINY_ABSENT(8, 1, Position.ABSENT, Layout.COMPACT, 2, false, Outcome.NONE),
        TINY_BEGINNING(8, 4, Position.BEGINNING, Layout.COMPACT, 2, false, Outcome.BEGINNING),
        SHORT_MIDDLE(64, 16, Position.MIDDLE, Layout.COMPACT, 3, false, Outcome.SEARCH),
        KIB_EARLY(1_024, 4, Position.EARLY, Layout.COMPACT, 3, false, Outcome.SEARCH),
        KIB_LATE(1_024, 16, Position.LATE, Layout.COMPACT, 8, false, Outcome.SEARCH),
        LARGE_ABSENT(32_768, 64, Position.ABSENT, Layout.COMPACT, 8, false, Outcome.NONE),
        LARGE_END(32_768, 16, Position.END, Layout.COMPACT, 3, false, Outcome.END),
        MIB_ABSENT(1 << 20, 16, Position.ABSENT, Layout.COMPACT, 16, false, Outcome.NONE),
        MIB_LATE(1 << 20, 64, Position.LATE, Layout.COMPACT, 8, false, Outcome.SEARCH),
        DENSE_FALSE(32_768, 4, Position.DENSE_FALSE, Layout.COMPACT, 3, false, Outcome.NONE),
        DENSE_TRUE(32_768, 4, Position.DENSE_TRUE, Layout.COMPACT, 3, false, Outcome.DENSE_TRUE),
        MULTIBYTE(1_024, 12, Position.MIDDLE, Layout.COMPACT, 3, true, Outcome.SEARCH),
        UTF8_PREFIX_REMAINDER(32_768, 16, Position.BEGINNING, Layout.COMPACT, 3, false, Outcome.BEGINNING_ORDERED),
        UTF8_SUFFIX_PREFIX(32_768, 16, Position.END, Layout.COMPACT, 3, false, Outcome.END),
        NON_ZERO_OFFSET(1_024, 16, Position.LATE, Layout.NON_ZERO_OFFSET, 3, false, Outcome.SEARCH);

        private final int sourceLength;
        private final int literalLength;
        private final Position position;
        private final Layout layout;
        private final int tokenCount;
        private final boolean multibyte;
        private final Outcome outcome;

        Workload(int sourceLength, int literalLength, Position position, Layout layout, int tokenCount, boolean multibyte, Outcome outcome)
        {
            this.sourceLength = sourceLength;
            this.literalLength = literalLength;
            this.position = position;
            this.layout = layout;
            this.tokenCount = tokenCount;
            this.multibyte = multibyte;
            this.outcome = outcome;
        }
    }

    record Input(
            Re2 literal,
            Re2 contains,
            Re2 ordered,
            Re2 unsupported,
            Re2 prefix,
            Re2 suffix,
            Slice literalSource,
            Slice orderedSource,
            Expected expected,
            Re2.BooleanPartialMatchStrategy orderedStrategy) {}

    static Input create(Workload workload)
    {
        String literal = workload.multibyte
                ? multibyteLiteral(workload.literalLength)
                : asciiLiteral(workload.literalLength, 0);
        String[] tokens = orderedTokens(workload.tokenCount);
        Slice literalSource = layout(literalSource(workload, literal.getBytes(UTF_8)), workload.layout);
        Slice orderedSource = layout(orderedSource(workload, tokens), workload.layout);

        return new Input(
                Re2.compile(Slices.utf8Slice(literal)),
                Re2.compile(Slices.utf8Slice(".*" + literal + ".*")),
                Re2.compile(Slices.utf8Slice(String.join("(?s:.*)", tokens))),
                Re2.compile(Slices.utf8Slice("(?:" + literal + "|z+)")),
                Re2.compile(Slices.utf8Slice("^" + literal + "(?s:.*)$")),
                Re2.compile(Slices.utf8Slice("(?s:.*)" + literal + "$")),
                literalSource,
                orderedSource,
                workload.outcome.expected(),
                tokens.length == 1 ? Re2.BooleanPartialMatchStrategy.EXACT_LITERAL : Re2.BooleanPartialMatchStrategy.GENERAL);
    }

    private static byte[] literalSource(Workload workload, byte[] literal)
    {
        byte[] source = new byte[workload.sourceLength];
        Arrays.fill(source, (byte) 'x');

        switch (workload.position) {
            case ABSENT -> {
                return source;
            }
            case EXACT -> {
                inject(source, literal, 0);
                return source;
            }
            case DENSE_FALSE -> fillDenseFalse(source, literal);
            case DENSE_TRUE -> fillDenseTrue(source, literal);
            default -> inject(source, literal, position(workload.position, source.length, literal.length));
        }
        if (workload == Workload.UTF8_PREFIX_REMAINDER) {
            inject(source, MULTIBYTE_CODE_POINT, source.length - 8);
        }
        else if (workload == Workload.UTF8_SUFFIX_PREFIX) {
            inject(source, MULTIBYTE_CODE_POINT, 4);
        }
        return source;
    }

    private static byte[] orderedSource(Workload workload, String[] tokens)
    {
        byte[] source = new byte[workload.sourceLength];
        Arrays.fill(source, (byte) 'x');
        if (workload.position == Position.DENSE_FALSE) {
            fillDenseTrue(source, tokens[0].getBytes(UTF_8));
            return source;
        }
        if (workload.position == Position.DENSE_TRUE) {
            fillDenseOrdered(source, tokens);
            return source;
        }
        injectOrdered(source, tokens, workload.position);
        return source;
    }

    private static void injectOrdered(byte[] source, String[] tokens, Position position)
    {
        if (position == Position.ABSENT || source.length == 0) {
            return;
        }

        int totalLength = 0;
        byte[][] encodedTokens = new byte[tokens.length][];
        for (int tokenIndex = 0; tokenIndex < tokens.length; tokenIndex++) {
            encodedTokens[tokenIndex] = tokens[tokenIndex].getBytes(UTF_8);
            totalLength += encodedTokens[tokenIndex].length;
        }
        int gap = tokens.length <= 1 ? 0 : 1;
        int requiredLength = totalLength + gap * (tokens.length - 1);
        if (requiredLength > source.length) {
            return;
        }

        int offset = position(position, source.length, requiredLength);
        for (byte[] token : encodedTokens) {
            System.arraycopy(token, 0, source, offset, token.length);
            offset += token.length + gap;
        }
    }

    private static int position(Position position, int sourceLength, int valueLength)
    {
        if (valueLength > sourceLength) {
            return -1;
        }
        return switch (position) {
            case ABSENT, DENSE_FALSE, DENSE_TRUE, BEGINNING, EXACT -> 0;
            case EARLY -> Math.min(16, sourceLength - valueLength);
            case MIDDLE -> (sourceLength - valueLength) / 2;
            case LATE -> Math.max(0, sourceLength * 7 / 8 - valueLength);
            case END -> sourceLength - valueLength;
        };
    }

    private static void inject(byte[] target, byte[] value, int position)
    {
        if (position >= 0 && position + value.length <= target.length) {
            System.arraycopy(value, 0, target, position, value.length);
        }
    }

    private static void fillDenseFalse(byte[] source, byte[] literal)
    {
        if (literal.length == 0) {
            return;
        }
        byte[] nearMiss = literal.clone();
        nearMiss[nearMiss.length - 1] ^= 1;
        for (int position = 0; position + nearMiss.length <= source.length; position += nearMiss.length) {
            System.arraycopy(nearMiss, 0, source, position, nearMiss.length);
        }
    }

    private static void fillDenseTrue(byte[] source, byte[] literal)
    {
        if (literal.length == 0) {
            return;
        }
        for (int position = 0; position + literal.length <= source.length; position += literal.length + 1) {
            System.arraycopy(literal, 0, source, position, literal.length);
        }
    }

    private static void fillDenseOrdered(byte[] source, String[] tokens)
    {
        int position = 0;
        while (position < source.length) {
            for (String token : tokens) {
                byte[] bytes = token.getBytes(UTF_8);
                if (position + bytes.length > source.length) {
                    return;
                }
                System.arraycopy(bytes, 0, source, position, bytes.length);
                position += bytes.length;
                if (position < source.length) {
                    source[position++] = 'x';
                }
            }
        }
    }

    private static Slice layout(byte[] source, Layout layout)
    {
        if (layout == Layout.COMPACT) {
            return Slices.wrappedBuffer(source);
        }

        byte[] padded = new byte[source.length + PADDING * 2];
        Arrays.fill(padded, (byte) '!');
        System.arraycopy(source, 0, padded, PADDING, source.length);
        return Slices.wrappedBuffer(padded).slice(PADDING, source.length);
    }

    private static String asciiLiteral(int byteLength, int salt)
    {
        String alphabet = "NeedleSearchToken";
        StringBuilder builder = new StringBuilder(byteLength);
        for (int index = 0; index < byteLength; index++) {
            builder.append(alphabet.charAt((index + salt) % alphabet.length()));
        }
        return builder.toString();
    }

    private static String multibyteLiteral(int byteLength)
    {
        String unit = "夏洛克";
        StringBuilder builder = new StringBuilder();
        while (builder.toString().getBytes(UTF_8).length < byteLength) {
            builder.append(unit);
        }
        byte[] bytes = builder.toString().getBytes(UTF_8);
        int length = Math.min(byteLength, bytes.length);
        while (length > 0 && length < bytes.length && (bytes[length] & 0xC0) == 0x80) {
            length--;
        }
        return new String(bytes, 0, length, UTF_8);
    }

    private static String[] orderedTokens(int tokenCount)
    {
        String[] tokens = new String[tokenCount];
        for (int tokenIndex = 0; tokenIndex < tokenCount; tokenIndex++) {
            tokens[tokenIndex] = asciiLiteral(4, tokenIndex + 3) + tokenIndex;
        }
        return tokens;
    }

    private enum Position
    {
        ABSENT,
        EXACT,
        BEGINNING,
        EARLY,
        MIDDLE,
        LATE,
        END,
        DENSE_FALSE,
        DENSE_TRUE,
    }

    private enum Layout
    {
        COMPACT,
        NON_ZERO_OFFSET,
    }

    private enum Outcome
    {
        NONE,
        ALL,
        BEGINNING,
        BEGINNING_ORDERED,
        SEARCH,
        END,
        DENSE_TRUE;

        Expected expected()
        {
            return switch (this) {
                case NONE -> new Expected(false, false, false, false, false, false, false);
                case ALL -> new Expected(true, true, true, true, true, true, true);
                case BEGINNING -> new Expected(true, true, false, true, false, true, false);
                case BEGINNING_ORDERED -> new Expected(true, true, true, true, false, true, false);
                case SEARCH -> new Expected(true, true, true, false, false, false, false);
                case END -> new Expected(true, true, true, false, false, false, true);
                case DENSE_TRUE -> new Expected(true, true, true, true, false, true, false);
            };
        }
    }

    record Expected(
            boolean findLiteral,
            boolean findContains,
            boolean findOrdered,
            boolean lookingAtLiteral,
            boolean matchesLiteral,
            boolean matchesPrefix,
            boolean matchesSuffix) {}
}
