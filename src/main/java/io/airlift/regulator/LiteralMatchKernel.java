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

final class LiteralMatchKernel
{
    private LiteralMatchKernel() {}

    // The first and last words overlap for non-power-of-two lengths. Together they
    // cover every literal byte without reading outside the matched range. This
    // byte-equality kernel has no regex or LIKE language semantics of its own.
    static final class ShortLiteral
    {
        private final int width;
        private final int lastOffset;
        private final long first;
        private final long last;

        ShortLiteral(Slice literal)
        {
            int length = literal.length();
            if (length < 1 || length > 16) {
                throw new IllegalArgumentException("short literal length must be between 1 and 16");
            }
            width = Math.min(Integer.highestOneBit(length), Long.BYTES);
            lastOffset = length - width;
            first = read(literal, 0, width);
            last = read(literal, lastOffset, width);
        }

        boolean matchesAt(Slice input, int offset)
        {
            return switch (width) {
                case 1 -> input.getByte(offset) == first;
                case 2 -> ((input.getShort(offset) ^ first) | (input.getShort(offset + lastOffset) ^ last)) == 0;
                case 4 -> ((input.getInt(offset) ^ first) | (input.getInt(offset + lastOffset) ^ last)) == 0;
                case 8 -> ((input.getLong(offset) ^ first) | (input.getLong(offset + lastOffset) ^ last)) == 0;
                default -> throw new IllegalStateException("unknown literal word width: " + width);
            };
        }

        private static long read(Slice input, int offset, int width)
        {
            return switch (width) {
                case 1 -> input.getByte(offset);
                case 2 -> input.getShort(offset);
                case 4 -> input.getInt(offset);
                case 8 -> input.getLong(offset);
                default -> throw new IllegalArgumentException("unknown literal word width: " + width);
            };
        }
    }

    static boolean equals(Slice input, Slice literal)
    {
        return input.length() == literal.length() && matchesAt(input, 0, literal);
    }

    static boolean startsWith(Slice input, Slice literal)
    {
        return input.length() >= literal.length() && matchesAt(input, 0, literal);
    }

    static boolean endsWith(Slice input, Slice literal)
    {
        return input.length() >= literal.length() && matchesAt(input, input.length() - literal.length(), literal);
    }

    static boolean contains(Slice input, Slice literal)
    {
        return input.indexOf(literal) >= 0;
    }

    static int find(Slice input, Slice literal, int start)
    {
        return input.indexOf(literal, start);
    }

    static boolean matchesAt(Slice input, int offset, Slice literal)
    {
        int literalLength = literal.length();
        return input.equals(offset, literalLength, literal, 0, literalLength);
    }
}
