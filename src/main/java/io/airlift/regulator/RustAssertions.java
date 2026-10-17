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

/**
 * Text-dependent Rust assertions use the existing bounded BitState/NFA execution path.
 */
final class RustAssertions
{
    private static final int WORD_BOUNDARY = 1 << 18;
    private static final int NO_WORD_BOUNDARY = 1 << 19;
    private static final int LEFT_WORD = 1 << 20;
    private static final int LEFT_NON_WORD = 1 << 21;
    private static final int RIGHT_WORD = 1 << 22;
    private static final int RIGHT_NON_WORD = 1 << 23;
    private static final int ASCII_LEFT_WORD = 1 << 24;
    private static final int ASCII_LEFT_NON_WORD = 1 << 25;
    private static final int ASCII_RIGHT_WORD = 1 << 26;
    private static final int ASCII_RIGHT_NON_WORD = 1 << 27;
    static final int BEGIN_LINE = 1 << 28;
    static final int END_LINE = 1 << 29;
    private static final int ASCII_WORD_BOUNDARY = 1 << 30;
    private static final int ASCII_NO_WORD_BOUNDARY = 1 << 31;
    static final int ALL = ~((1 << 18) - 1);
    private static final int UNICODE_WORD = WORD_BOUNDARY | NO_WORD_BOUNDARY | LEFT_WORD | LEFT_NON_WORD | RIGHT_WORD | RIGHT_NON_WORD;
    private static final int ASCII_WORD = ASCII_WORD_BOUNDARY | ASCII_NO_WORD_BOUNDARY | ASCII_LEFT_WORD | ASCII_LEFT_NON_WORD | ASCII_RIGHT_WORD | ASCII_RIGHT_NON_WORD;

    private RustAssertions() {}

    static int wordBoundary(int flags, boolean negate, boolean reversed)
    {
        boolean unicode = (flags & Regexp.RUST_UNICODE) != 0;
        int kind = (flags & Regexp.RUST_BOUNDARY_MASK) >>> Regexp.RUST_BOUNDARY_SHIFT;
        if (kind == 0) {
            if (unicode) {
                return negate ? NO_WORD_BOUNDARY : WORD_BOUNDARY;
            }
            return negate ? ASCII_NO_WORD_BOUNDARY : ASCII_WORD_BOUNDARY;
        }
        int leftWord = unicode ? LEFT_WORD : ASCII_LEFT_WORD;
        int leftNonWord = unicode ? LEFT_NON_WORD : ASCII_LEFT_NON_WORD;
        int rightWord = unicode ? RIGHT_WORD : ASCII_RIGHT_WORD;
        int rightNonWord = unicode ? RIGHT_NON_WORD : ASCII_RIGHT_NON_WORD;
        return switch (kind) {
            case 1 -> reversed ? leftWord | rightNonWord : leftNonWord | rightWord;
            case 2 -> reversed ? leftNonWord | rightWord : leftWord | rightNonWord;
            case 3 -> reversed ? rightNonWord : leftNonWord;
            case 4 -> reversed ? leftNonWord : rightNonWord;
            default -> throw new IllegalArgumentException("Unknown Rust boundary kind: " + kind);
        };
    }

    static int context(byte[] bytes, int begin, int end, int position, int assertions)
    {
        int result = 0;
        if ((assertions & BEGIN_LINE) != 0 && (position == begin ||
                bytes[position - 1] == '\n' || (bytes[position - 1] == '\r' && (position == end || bytes[position] != '\n')))) {
            result |= BEGIN_LINE;
        }
        if ((assertions & END_LINE) != 0 && (position == end ||
                bytes[position] == '\r' || (bytes[position] == '\n' && (position == begin || bytes[position - 1] != '\r')))) {
            result |= END_LINE;
        }
        if ((assertions & UNICODE_WORD) != 0 && EmptyOp.isCodePointBoundary(bytes, begin, end, position)) {
            boolean left = UnicodeGroups.isWord(EmptyOp.previousCodePoint(bytes, begin, position));
            boolean right = UnicodeGroups.isWord(EmptyOp.nextCodePoint(bytes, end, position));
            result |= left != right ? WORD_BOUNDARY : NO_WORD_BOUNDARY;
            result |= left ? LEFT_WORD : LEFT_NON_WORD;
            result |= right ? RIGHT_WORD : RIGHT_NON_WORD;
        }
        if ((assertions & ASCII_WORD) != 0 && EmptyOp.isCodePointBoundary(bytes, begin, end, position)) {
            boolean left = position > begin && asciiWord(bytes[position - 1]);
            boolean right = position < end && asciiWord(bytes[position]);
            result |= left != right ? ASCII_WORD_BOUNDARY : ASCII_NO_WORD_BOUNDARY;
            result |= left ? ASCII_LEFT_WORD : ASCII_LEFT_NON_WORD;
            result |= right ? ASCII_RIGHT_WORD : ASCII_RIGHT_NON_WORD;
        }
        return result;
    }

    private static boolean asciiWord(int value)
    {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z') ||
                (value >= '0' && value <= '9') || value == '_';
    }
}
