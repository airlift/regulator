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

import static java.util.Objects.requireNonNull;

final class EmptyOp
{
    public static final int EMPTY_BEGIN_LINE = 1 << 0;
    public static final int EMPTY_END_LINE = 1 << 1;
    public static final int EMPTY_BEGIN_TEXT = 1 << 2;
    public static final int EMPTY_END_TEXT = 1 << 3;
    public static final int EMPTY_WORD_BOUNDARY = 1 << 4;
    public static final int EMPTY_NO_WORD_BOUNDARY = 1 << 5;
    // DFA-supported empty-width assertions must fit in one byte; these use the last two bits.
    public static final int EMPTY_TRINO_BEGIN_LINE = 1 << 6;
    public static final int EMPTY_TRINO_END_LINE = 1 << 7;
    public static final int EMPTY_END_TEXT_OR_FINAL_NEWLINE = 1 << 8;
    public static final int EMPTY_UNICODE_WORD_BOUNDARY = 1 << 9;
    public static final int EMPTY_NO_UNICODE_WORD_BOUNDARY = 1 << 10;
    public static final int EMPTY_JAVA_BEGIN_LINE = 1 << 11;
    public static final int EMPTY_JAVA_END_LINE = 1 << 12;
    public static final int EMPTY_JAVA_END_TEXT_OR_FINAL_TERMINATOR = 1 << 13;
    public static final int EMPTY_JAVA_WORD_BOUNDARY = 1 << 14;
    public static final int EMPTY_JAVA_NO_WORD_BOUNDARY = 1 << 15;
    public static final int EMPTY_JAVA_UNICODE_WORD_BOUNDARY = 1 << 16;
    public static final int EMPTY_JAVA_NO_UNICODE_WORD_BOUNDARY = 1 << 17;

    public static final int TEXT_DEPENDENT =
            EMPTY_END_TEXT_OR_FINAL_NEWLINE |
                    EMPTY_UNICODE_WORD_BOUNDARY |
                    EMPTY_NO_UNICODE_WORD_BOUNDARY |
                    EMPTY_JAVA_BEGIN_LINE |
                    EMPTY_JAVA_END_LINE |
                    EMPTY_JAVA_END_TEXT_OR_FINAL_TERMINATOR |
                    EMPTY_JAVA_WORD_BOUNDARY |
                    EMPTY_JAVA_NO_WORD_BOUNDARY |
                    EMPTY_JAVA_UNICODE_WORD_BOUNDARY |
                    EMPTY_JAVA_NO_UNICODE_WORD_BOUNDARY |
                    EMPTY_TRINO_BEGIN_LINE |
                    EMPTY_TRINO_END_LINE;

    private EmptyOp() {}

    static int contextFlags(Slice text, int position)
    {
        requireNonNull(text, "text is null");
        int begin = text.byteArrayOffset();
        int end = begin + text.length();
        checkPosition(position, begin, end);
        return contextFlags(text.byteArray(), begin, end, position);
    }

    static int contextFlags(Slice text, int position, int textDependentAssertions)
    {
        requireNonNull(text, "text is null");
        int begin = text.byteArrayOffset();
        int end = begin + text.length();
        checkPosition(position, begin, end);
        return contextFlags(text.byteArray(), begin, end, position, textDependentAssertions);
    }

    static int contextFlags(byte[] bytes, int begin, int end, int position, int textDependentAssertions)
    {
        int flags = contextFlags(bytes, begin, end, position);
        if ((textDependentAssertions & TEXT_DEPENDENT) == 0) {
            return flags;
        }

        if ((textDependentAssertions & EMPTY_END_TEXT_OR_FINAL_NEWLINE) != 0 &&
                (position == end || (position + 1 == end && bytes[position] == '\n'))) {
            flags |= EMPTY_END_TEXT_OR_FINAL_NEWLINE;
        }

        if ((textDependentAssertions & EMPTY_JAVA_BEGIN_LINE) != 0 &&
                isJavaBeginLine(bytes, begin, end, position)) {
            flags |= EMPTY_JAVA_BEGIN_LINE;
        }

        if ((textDependentAssertions & EMPTY_JAVA_END_LINE) != 0 &&
                isJavaEndLine(bytes, begin, end, position)) {
            flags |= EMPTY_JAVA_END_LINE;
        }

        if ((textDependentAssertions & EMPTY_TRINO_BEGIN_LINE) != 0 &&
                isTrinoBeginLine(bytes, begin, end, position)) {
            flags |= EMPTY_TRINO_BEGIN_LINE;
        }

        if ((textDependentAssertions & EMPTY_TRINO_END_LINE) != 0 &&
                isTrinoEndLine(bytes, begin, end, position)) {
            flags |= EMPTY_TRINO_END_LINE;
        }

        if ((textDependentAssertions & EMPTY_JAVA_END_TEXT_OR_FINAL_TERMINATOR) != 0 &&
                isJavaEndTextOrFinalTerminator(bytes, end, position)) {
            flags |= EMPTY_JAVA_END_TEXT_OR_FINAL_TERMINATOR;
        }

        if ((textDependentAssertions &
                (EMPTY_UNICODE_WORD_BOUNDARY | EMPTY_NO_UNICODE_WORD_BOUNDARY)) != 0 &&
                isCodePointBoundary(bytes, begin, end, position)) {
            boolean unicodeBoundary = isUnicodeWord(previousCodePoint(bytes, begin, position)) !=
                    isUnicodeWord(nextCodePoint(bytes, end, position));
            flags |= unicodeBoundary ? EMPTY_UNICODE_WORD_BOUNDARY : EMPTY_NO_UNICODE_WORD_BOUNDARY;
        }

        if ((textDependentAssertions &
                (EMPTY_JAVA_WORD_BOUNDARY |
                        EMPTY_JAVA_NO_WORD_BOUNDARY |
                        EMPTY_JAVA_UNICODE_WORD_BOUNDARY |
                        EMPTY_JAVA_NO_UNICODE_WORD_BOUNDARY)) != 0 &&
                isCodePointBoundary(bytes, begin, end, position)) {
            if ((textDependentAssertions &
                    (EMPTY_JAVA_WORD_BOUNDARY | EMPTY_JAVA_NO_WORD_BOUNDARY)) != 0) {
                boolean javaBoundary = isJavaWord(bytes, begin, end, position, false, false) !=
                        isJavaWord(bytes, begin, end, position, true, false);
                flags |= javaBoundary ? EMPTY_JAVA_WORD_BOUNDARY : EMPTY_JAVA_NO_WORD_BOUNDARY;
            }
            if ((textDependentAssertions &
                    (EMPTY_JAVA_UNICODE_WORD_BOUNDARY | EMPTY_JAVA_NO_UNICODE_WORD_BOUNDARY)) != 0) {
                boolean javaBoundary = isJavaWord(bytes, begin, end, position, false, true) !=
                        isJavaWord(bytes, begin, end, position, true, true);
                flags |= javaBoundary ? EMPTY_JAVA_UNICODE_WORD_BOUNDARY : EMPTY_JAVA_NO_UNICODE_WORD_BOUNDARY;
            }
        }

        return flags;
    }

    private static boolean isJavaBeginLine(byte[] bytes, int begin, int end, int position)
    {
        if (position == end) {
            return false;
        }
        if (position == begin) {
            return true;
        }

        int previousCodePoint = previousCodePoint(bytes, begin, position);
        if (!isJavaLineTerminator(previousCodePoint)) {
            return false;
        }
        return previousCodePoint != '\r' || nextCodePoint(bytes, end, position) != '\n';
    }

    private static boolean isTrinoBeginLine(byte[] bytes, int begin, int end, int position)
    {
        return position == begin || (position < end && bytes[position - 1] == '\n');
    }

    private static boolean isTrinoEndLine(byte[] bytes, int begin, int end, int position)
    {
        return position == end || (position > begin && bytes[position] == '\n');
    }

    private static boolean isJavaEndLine(byte[] bytes, int begin, int end, int position)
    {
        if (position == end) {
            return true;
        }

        int nextCodePoint = nextCodePoint(bytes, end, position);
        if (!isJavaLineTerminator(nextCodePoint)) {
            return false;
        }
        return nextCodePoint != '\n' || previousCodePoint(bytes, begin, position) != '\r';
    }

    private static boolean isJavaEndTextOrFinalTerminator(byte[] bytes, int end, int position)
    {
        if (position == end) {
            return true;
        }

        int nextCodePoint = nextCodePoint(bytes, end, position);
        int nextPosition = nextCodePointPosition(bytes, end, position);
        if (nextCodePoint == '\r' && nextPosition < end && nextCodePoint(bytes, end, nextPosition) == '\n') {
            return nextCodePointPosition(bytes, end, nextPosition) == end;
        }
        return isJavaLineTerminator(nextCodePoint) && nextPosition == end;
    }

    private static boolean isJavaLineTerminator(int codePoint)
    {
        return codePoint == '\n' ||
                codePoint == '\r' ||
                codePoint == 0x0085 ||
                codePoint == 0x2028 ||
                codePoint == 0x2029;
    }

    private static boolean isJavaWord(
            byte[] bytes,
            int begin,
            int end,
            int position,
            boolean right,
            boolean unicodeCharacterClass)
    {
        if (right ? position == end : position == begin) {
            return false;
        }

        int codePointStart = right ? position : previousCodePointStart(bytes, begin, position);
        int codePoint = nextCodePoint(bytes, end, codePointStart);
        if (unicodeCharacterClass ? UnicodeGroups.isWord(codePoint) : isAsciiWord(codePoint)) {
            return true;
        }
        return Character.getType(codePoint) == Character.NON_SPACING_MARK &&
                hasJavaBoundaryBaseCharacter(bytes, begin, end, codePointStart);
    }

    private static boolean hasJavaBoundaryBaseCharacter(byte[] bytes, int begin, int end, int position)
    {
        // Java ignores a nonspacing mark for boundary purposes unless a preceding letter or digit gives it a base.
        int codePointStart = position;
        while (codePointStart >= begin) {
            int codePoint = nextCodePoint(bytes, end, codePointStart);
            if (Character.isLetterOrDigit(codePoint)) {
                return true;
            }
            if (Character.getType(codePoint) != Character.NON_SPACING_MARK || codePointStart == begin) {
                return false;
            }
            codePointStart = previousCodePointStart(bytes, begin, codePointStart);
        }
        return false;
    }

    private static int contextFlags(byte[] bytes, int begin, int end, int position)
    {
        int flags = 0;

        if (position == begin) {
            flags |= EMPTY_BEGIN_TEXT | EMPTY_BEGIN_LINE;
        }
        else if (bytes[position - 1] == '\n') {
            flags |= EMPTY_BEGIN_LINE;
        }

        if (position == end) {
            flags |= EMPTY_END_TEXT | EMPTY_END_LINE;
        }
        else if (bytes[position] == '\n') {
            flags |= EMPTY_END_LINE;
        }

        boolean asciiBoundary = isAsciiWord(position > begin ? bytes[position - 1] : -1) !=
                isAsciiWord(position < end ? bytes[position] : -1);
        flags |= asciiBoundary ? EMPTY_WORD_BOUNDARY : EMPTY_NO_WORD_BOUNDARY;
        return flags;
    }

    private static void checkPosition(int position, int begin, int end)
    {
        if (position < begin || position > end) {
            throw new IllegalArgumentException("position out of range for text slice: position=" + position +
                    " begin=" + begin + " end=" + end);
        }
    }

    private static boolean isCodePointBoundary(byte[] bytes, int begin, int end, int position)
    {
        return position == begin ||
                position == end ||
                (bytes[position] & 0xC0) != 0x80;
    }

    private static int previousCodePoint(byte[] bytes, int begin, int position)
    {
        if (position == begin) {
            return -1;
        }

        int codePointStart = previousCodePointStart(bytes, begin, position);
        long decoded = Utf8.decode(bytes, codePointStart, position);
        if (Utf8.decodedWidth(decoded) != position - codePointStart) {
            return Utf8.RUNE_ERROR;
        }
        return Utf8.decodedCodePoint(decoded);
    }

    private static int previousCodePointStart(byte[] bytes, int begin, int position)
    {
        int codePointStart = position - 1;
        while (codePointStart > begin &&
                position - codePointStart < 4 &&
                (bytes[codePointStart] & 0xC0) == 0x80) {
            codePointStart--;
        }
        return codePointStart;
    }

    private static int nextCodePoint(byte[] bytes, int end, int position)
    {
        if (position == end) {
            return -1;
        }
        return Utf8.decodedCodePoint(Utf8.decode(bytes, position, end));
    }

    private static int nextCodePointPosition(byte[] bytes, int end, int position)
    {
        return position + Utf8.decodedWidth(Utf8.decode(bytes, position, end));
    }

    private static boolean isAsciiWord(int value)
    {
        return ('A' <= value && value <= 'Z') ||
                ('a' <= value && value <= 'z') ||
                ('0' <= value && value <= '9') ||
                value == '_';
    }

    static boolean isUnicodeWord(int codePoint)
    {
        if (codePoint < 0) {
            return false;
        }
        int type = Character.getType(codePoint);
        return Character.isAlphabetic(codePoint) ||
                type == Character.NON_SPACING_MARK ||
                type == Character.ENCLOSING_MARK ||
                type == Character.COMBINING_SPACING_MARK ||
                type == Character.DECIMAL_DIGIT_NUMBER ||
                type == Character.CONNECTOR_PUNCTUATION ||
                isJoniLatin1WordNumber(codePoint);
    }

    private static boolean isJoniLatin1WordNumber(int codePoint)
    {
        // Joni's Java syntax includes these six superscript and fraction numbers.
        return codePoint == 0x00B2 ||
                codePoint == 0x00B3 ||
                codePoint == 0x00B9 ||
                (codePoint >= 0x00BC && codePoint <= 0x00BE);
    }
}
