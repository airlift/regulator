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
     * Copied as {@code TrinoScanKernelSupport.decode}; keep the copy in step.
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

    /**
     * Returns the width of the character starting at {@code index}, which must be before
     * {@code end}, when malformed input is read as if each maximal subpart had been replaced by
     * U+FFFD (Unicode chapter 3, "U+FFFD Substitution of Maximal Subparts"). A well-formed sequence
     * is one character. Otherwise the character is the longest prefix of a well-formed sequence
     * that a bad byte or {@code end} cuts off, or one byte when no well-formed sequence starts
     * with it. Only continuation bytes can follow the first byte of a character, so every ASCII
     * byte and every lead byte starts a new character.
     */
    static int characterWidth(byte[] bytes, int index, int end)
    {
        int b0 = bytes[index] & 0xFF;
        if (b0 < 0x80) {
            return 1;
        }
        if (b0 < 0xC2 || index + 1 >= end) {
            return 1;
        }
        int b1 = bytes[index + 1] & 0xFF;
        if ((b1 & 0xC0) != 0x80) {
            return 1;
        }
        if (b0 < 0xE0) {
            return 2;
        }
        if (b0 < 0xF0) {
            // After E0 a continuation byte below A0 would be overlong; after ED one above 9F, a surrogate.
            if ((b0 == 0xE0 && b1 < 0xA0) || (b0 == 0xED && b1 > 0x9F)) {
                return 1;
            }
            return index + 2 < end && (bytes[index + 2] & 0xC0) == 0x80 ? 3 : 2;
        }
        // After F0 a continuation byte below 90 would be overlong; after F4 one above 8F, beyond U+10FFFF.
        if (b0 > 0xF4 || (b0 == 0xF0 && b1 < 0x90) || (b0 == 0xF4 && b1 > 0x8F)) {
            return 1;
        }
        if (index + 2 >= end || (bytes[index + 2] & 0xC0) != 0x80) {
            return 2;
        }
        return index + 3 < end && (bytes[index + 3] & 0xC0) == 0x80 ? 4 : 3;
    }

    static int decodedWidth(long decoded)
    {
        return (int) (decoded >>> 32);
    }

    static int decodedCodePoint(long decoded)
    {
        return (int) decoded;
    }

    static int firstInvalidOffset(Slice input)
    {
        byte[] bytes = input.byteArray();
        int startOffset = input.byteArrayOffset();
        int byteOffset = startOffset;
        int endOffset = startOffset + input.length();

        // Fast path: pure ASCII input is always valid UTF-8.
        while (byteOffset < endOffset && (bytes[byteOffset] & 0x80) == 0) {
            byteOffset++;
        }
        if (byteOffset == endOffset) {
            return -1;
        }

        while (byteOffset < endOffset) {
            long decodedRune = decode(bytes, byteOffset, endOffset);
            int decodedWidth = decodedWidth(decodedRune);
            if (decodedWidth == 0) {
                return byteOffset - startOffset;
            }
            int decodedCodePoint = decodedCodePoint(decodedRune);
            if (decodedWidth == 1 && decodedCodePoint == RUNE_ERROR && (bytes[byteOffset] & 0xFF) >= 0x80) {
                return byteOffset - startOffset;
            }
            byteOffset += decodedWidth;
        }
        return -1;
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

    // Copied as TrinoScanKernelSupport.pack; keep the copy in step.
    private static long pack(int codePoint, int width)
    {
        return (((long) width) << 32) | (codePoint & 0xFFFFFFFFL);
    }
}
