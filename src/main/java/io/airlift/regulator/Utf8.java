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

import io.airlift.slice.SliceOutput;

/**
 * Minimal UTF-8 decoding utilities. The core engine assumes valid UTF-8 inputs (garbage in, garbage out).
 */
final class Utf8
{
    static final int RUNE_ERROR = 0xFFFD;

    private Utf8() {}

    /**
     * Decodes a single UTF-8 code point starting at {@code index}.
     * <p>
     * Returns a packed long:
     * <ul>
     * <li>high 32 bits: width in bytes (0 at end)
     * <li>low 32 bits: code point (unsigned)
     * </ul>
     * <p>
     * For invalid sequences, returns {@code RUNE_ERROR} with width 1.
     */
    static long decode(byte[] bytes, int index, int end)
    {
        if (index >= end) {
            return pack(0, 0);
        }

        int b0 = bytes[index] & 0xFF;
        if (b0 < 0x80) {
            return pack(b0, 1);
        }

        // Reject invalid leading bytes and overlong 2-byte sequences.
        if (b0 < 0xC2) {
            return pack(RUNE_ERROR, 1);
        }

        if (b0 < 0xE0) {
            if (index + 1 >= end) {
                return pack(RUNE_ERROR, 1);
            }
            int b1 = bytes[index + 1] & 0xFF;
            if ((b1 & 0xC0) != 0x80) {
                return pack(RUNE_ERROR, 1);
            }
            int codePoint = ((b0 & 0x1F) << 6) | (b1 & 0x3F);
            return pack(codePoint, 2);
        }

        if (b0 < 0xF0) {
            if (index + 2 >= end) {
                return pack(RUNE_ERROR, 1);
            }
            int b1 = bytes[index + 1] & 0xFF;
            int b2 = bytes[index + 2] & 0xFF;
            if (((b1 & 0xC0) != 0x80) || ((b2 & 0xC0) != 0x80)) {
                return pack(RUNE_ERROR, 1);
            }
            int codePoint = ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
            // Reject overlongs and surrogates.
            if (codePoint < 0x800 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                return pack(RUNE_ERROR, 1);
            }
            return pack(codePoint, 3);
        }

        if (b0 < 0xF5) {
            if (index + 3 >= end) {
                return pack(RUNE_ERROR, 1);
            }
            int b1 = bytes[index + 1] & 0xFF;
            int b2 = bytes[index + 2] & 0xFF;
            int b3 = bytes[index + 3] & 0xFF;
            if (((b1 & 0xC0) != 0x80) || ((b2 & 0xC0) != 0x80) || ((b3 & 0xC0) != 0x80)) {
                return pack(RUNE_ERROR, 1);
            }
            int codePoint = ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
            // Reject overlongs and outside Unicode range.
            if (codePoint < 0x10000 || codePoint > 0x10FFFF) {
                return pack(RUNE_ERROR, 1);
            }
            return pack(codePoint, 4);
        }

        return pack(RUNE_ERROR, 1);
    }

    static int decodedWidth(long decoded)
    {
        return (int) (decoded >>> 32);
    }

    static int decodedCodePoint(long decoded)
    {
        return (int) decoded;
    }

    static int encodedLength(int codePoint)
    {
        if (!Character.isValidCodePoint(codePoint)) {
            throw new IllegalArgumentException("invalid rune: " + codePoint);
        }
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    static int encode(byte[] output, int offset, int codePoint)
    {
        int length = encodedLength(codePoint);
        if (length == 1) {
            output[offset] = (byte) codePoint;
        }
        else if (length == 2) {
            output[offset] = (byte) (0xC0 | (codePoint >>> 6));
            output[offset + 1] = (byte) (0x80 | (codePoint & 0x3F));
        }
        else if (length == 3) {
            output[offset] = (byte) (0xE0 | (codePoint >>> 12));
            output[offset + 1] = (byte) (0x80 | ((codePoint >>> 6) & 0x3F));
            output[offset + 2] = (byte) (0x80 | (codePoint & 0x3F));
        }
        else {
            output[offset] = (byte) (0xF0 | (codePoint >>> 18));
            output[offset + 1] = (byte) (0x80 | ((codePoint >>> 12) & 0x3F));
            output[offset + 2] = (byte) (0x80 | ((codePoint >>> 6) & 0x3F));
            output[offset + 3] = (byte) (0x80 | (codePoint & 0x3F));
        }
        return offset + length;
    }

    static void encode(SliceOutput output, int codePoint)
    {
        int length = encodedLength(codePoint);
        if (length == 1) {
            output.writeByte(codePoint);
        }
        else if (length == 2) {
            output.writeByte(0xC0 | (codePoint >>> 6));
            output.writeByte(0x80 | (codePoint & 0x3F));
        }
        else if (length == 3) {
            output.writeByte(0xE0 | (codePoint >>> 12));
            output.writeByte(0x80 | ((codePoint >>> 6) & 0x3F));
            output.writeByte(0x80 | (codePoint & 0x3F));
        }
        else {
            output.writeByte(0xF0 | (codePoint >>> 18));
            output.writeByte(0x80 | ((codePoint >>> 12) & 0x3F));
            output.writeByte(0x80 | ((codePoint >>> 6) & 0x3F));
            output.writeByte(0x80 | (codePoint & 0x3F));
        }
    }

    private static long pack(int codePoint, int width)
    {
        return (((long) width) << 32) | (codePoint & 0xFFFFFFFFL);
    }
}
