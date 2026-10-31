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
    // The DFA keeps the first eight assertions as state flags and maps the Rust ones and the Java
    // and Trino word boundaries below onto its own flags; these use the last two of the eight.
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
    // The Rust crate's CRLF multi-line anchors, its Unicode \b and \B, and the sides of its start
    // and end word boundaries. A start or end boundary requires one side of each kind; a half
    // boundary requires only the non-word side, and for Unicode word characters also that the
    // text on that side, if any, decodes as UTF-8.
    public static final int EMPTY_RUST_CRLF_BEGIN_LINE = 1 << 18;
    public static final int EMPTY_RUST_CRLF_END_LINE = 1 << 19;
    public static final int EMPTY_RUST_WORD_BEFORE = 1 << 20;
    public static final int EMPTY_RUST_NON_WORD_BEFORE = 1 << 21;
    public static final int EMPTY_RUST_WORD_AFTER = 1 << 22;
    public static final int EMPTY_RUST_NON_WORD_AFTER = 1 << 23;
    public static final int EMPTY_RUST_UNICODE_WORD_BEFORE = 1 << 24;
    public static final int EMPTY_RUST_UNICODE_NON_WORD_BEFORE = 1 << 25;
    public static final int EMPTY_RUST_UNICODE_WORD_AFTER = 1 << 26;
    public static final int EMPTY_RUST_UNICODE_NON_WORD_AFTER = 1 << 27;
    public static final int EMPTY_RUST_UNICODE_WORD_BOUNDARY = 1 << 28;
    public static final int EMPTY_RUST_NO_UNICODE_WORD_BOUNDARY = 1 << 29;
    public static final int EMPTY_RUST_UNICODE_VALID_BEFORE = 1 << 30;
    public static final int EMPTY_RUST_UNICODE_VALID_AFTER = 1 << 31;
    // All 32 assertion bits are allocated.

    static final int RUST_CRLF_LINE = EMPTY_RUST_CRLF_BEGIN_LINE | EMPTY_RUST_CRLF_END_LINE;
    private static final int RUST_WORD_SIDES =
            EMPTY_RUST_WORD_BEFORE |
                    EMPTY_RUST_NON_WORD_BEFORE |
                    EMPTY_RUST_WORD_AFTER |
                    EMPTY_RUST_NON_WORD_AFTER;
    static final int RUST_UNICODE_WORD_ASSERTIONS =
            EMPTY_RUST_UNICODE_WORD_BEFORE |
                    EMPTY_RUST_UNICODE_NON_WORD_BEFORE |
                    EMPTY_RUST_UNICODE_WORD_AFTER |
                    EMPTY_RUST_UNICODE_NON_WORD_AFTER |
                    EMPTY_RUST_UNICODE_WORD_BOUNDARY |
                    EMPTY_RUST_NO_UNICODE_WORD_BOUNDARY |
                    EMPTY_RUST_UNICODE_VALID_BEFORE |
                    EMPTY_RUST_UNICODE_VALID_AFTER;
    static final int RUST_WORD_ASSERTIONS = RUST_WORD_SIDES | RUST_UNICODE_WORD_ASSERTIONS;
    static final int RUST_ASSERTIONS = RUST_CRLF_LINE | RUST_WORD_ASSERTIONS;

    // The JDK's Dollar assertions: $, multiline $, and \Z.
    static final int JAVA_END_ASSERTIONS = EMPTY_JAVA_END_LINE | EMPTY_JAVA_END_TEXT_OR_FINAL_TERMINATOR;
    private static final int JAVA_WORD_BOUNDARIES = EMPTY_JAVA_WORD_BOUNDARY | EMPTY_JAVA_NO_WORD_BOUNDARY;
    private static final int JAVA_UNICODE_WORD_BOUNDARIES = EMPTY_JAVA_UNICODE_WORD_BOUNDARY | EMPTY_JAVA_NO_UNICODE_WORD_BOUNDARY;
    // Assertions that hold only at a code point boundary.
    private static final int CODE_POINT_ASSERTIONS = JAVA_WORD_BOUNDARIES | JAVA_UNICODE_WORD_BOUNDARIES;
    // Java's \b and \B and Trino's Unicode \b and \B, which decode the character on each side.
    static final int JAVA_TRINO_WORD_BOUNDARIES = CODE_POINT_ASSERTIONS | EMPTY_UNICODE_WORD_BOUNDARY | EMPTY_NO_UNICODE_WORD_BOUNDARY;
    // The word assertions that decode the text. Between ASCII bytes and text edges each is its
    // ASCII form, so the DFA evaluates them there and quits next to a byte outside ASCII.
    static final int DECODING_WORD_ASSERTIONS = RUST_UNICODE_WORD_ASSERTIONS | JAVA_TRINO_WORD_BOUNDARIES;

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
                    EMPTY_TRINO_END_LINE |
                    RUST_ASSERTIONS;

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

        if ((textDependentAssertions & EMPTY_JAVA_END_TEXT_OR_FINAL_TERMINATOR) != 0 && isJavaEndTextOrFinalTerminator(bytes, begin, end, position)) {
            flags |= EMPTY_JAVA_END_TEXT_OR_FINAL_TERMINATOR;
        }

        if ((textDependentAssertions &
                (EMPTY_UNICODE_WORD_BOUNDARY | EMPTY_NO_UNICODE_WORD_BOUNDARY)) != 0 &&
                isCodePointBoundary(bytes, begin, end, position)) {
            boolean unicodeBoundary = isUnicodeWord(previousCodePoint(bytes, begin, position)) !=
                    isUnicodeWord(nextCodePoint(bytes, end, position));
            flags |= unicodeBoundary ? EMPTY_UNICODE_WORD_BOUNDARY : EMPTY_NO_UNICODE_WORD_BOUNDARY;
        }

        // No program mixes Rust and Java assertions, so the Rust arm excludes both Java arms.
        if ((textDependentAssertions & (CODE_POINT_ASSERTIONS | RUST_ASSERTIONS)) != 0) {
            if ((textDependentAssertions & JAVA_WORD_BOUNDARIES) != 0) {
                if (isCodePointBoundary(bytes, begin, end, position)) {
                    boolean javaBoundary = isJavaWord(bytes, begin, end, position, false, false) !=
                            isJavaWord(bytes, begin, end, position, true, false);
                    flags |= javaBoundary ? EMPTY_JAVA_WORD_BOUNDARY : EMPTY_JAVA_NO_WORD_BOUNDARY;
                    if ((textDependentAssertions & JAVA_UNICODE_WORD_BOUNDARIES) != 0) {
                        flags |= javaUnicodeWordBoundaryFlags(bytes, begin, end, position);
                    }
                }
            }
            else if ((textDependentAssertions & RUST_ASSERTIONS) != 0) {
                flags |= rustContextFlags(bytes, begin, end, position, textDependentAssertions);
            }
            else if (isCodePointBoundary(bytes, begin, end, position)) {
                flags |= javaUnicodeWordBoundaryFlags(bytes, begin, end, position);
            }
        }

        return flags;
    }

    private static int javaUnicodeWordBoundaryFlags(byte[] bytes, int begin, int end, int position)
    {
        boolean javaBoundary = isJavaWord(bytes, begin, end, position, false, true) !=
                isJavaWord(bytes, begin, end, position, true, true);
        return javaBoundary ? EMPTY_JAVA_UNICODE_WORD_BOUNDARY : EMPTY_JAVA_NO_UNICODE_WORD_BOUNDARY;
    }

    // The crate's assertions from regex-automata's util/look.rs, which read bytes at any position,
    // including inside a character or in text that is not UTF-8.
    private static int rustContextFlags(byte[] bytes, int begin, int end, int position, int textDependentAssertions)
    {
        int flags = 0;
        if ((textDependentAssertions & RUST_CRLF_LINE) != 0) {
            if (position == begin ||
                    bytes[position - 1] == '\n' ||
                    (bytes[position - 1] == '\r' && (position == end || bytes[position] != '\n'))) {
                flags |= EMPTY_RUST_CRLF_BEGIN_LINE;
            }
            if (position == end ||
                    bytes[position] == '\r' ||
                    (bytes[position] == '\n' && (position == begin || bytes[position - 1] != '\r'))) {
                flags |= EMPTY_RUST_CRLF_END_LINE;
            }
        }
        if ((textDependentAssertions & RUST_WORD_SIDES) != 0) {
            flags |= isAsciiWord(position > begin ? bytes[position - 1] : -1) ? EMPTY_RUST_WORD_BEFORE : EMPTY_RUST_NON_WORD_BEFORE;
            flags |= isAsciiWord(position < end ? bytes[position] : -1) ? EMPTY_RUST_WORD_AFTER : EMPTY_RUST_NON_WORD_AFTER;
        }
        if ((textDependentAssertions & RUST_UNICODE_WORD_ASSERTIONS) != 0) {
            // The crate's utf8::decode_last and utf8::decode: the character before the position is
            // the one decoded from the nearest byte that is not a continuation byte, within the
            // four bytes before the position, even if stray continuation bytes follow it before the
            // position. A side that does not decode is not a word character.
            long before = position == begin ? 0 : Utf8.decode(bytes, previousCodePointStart(bytes, begin, position), position);
            long after = position == end ? 0 : Utf8.decode(bytes, position, end);
            boolean validBefore = position == begin || isValid(before);
            boolean validAfter = position == end || isValid(after);
            boolean wordBefore = position > begin && validBefore && UnicodeGroups.isWord(Utf8.decodedCodePoint(before));
            boolean wordAfter = position < end && validAfter && UnicodeGroups.isWord(Utf8.decodedCodePoint(after));
            flags |= wordBefore ? EMPTY_RUST_UNICODE_WORD_BEFORE : EMPTY_RUST_UNICODE_NON_WORD_BEFORE;
            flags |= wordAfter ? EMPTY_RUST_UNICODE_WORD_AFTER : EMPTY_RUST_UNICODE_NON_WORD_AFTER;
            if (wordBefore != wordAfter) {
                flags |= EMPTY_RUST_UNICODE_WORD_BOUNDARY;
            }
            // \B requires each side that is present to decode, and a half boundary its non-word
            // side, so neither matches inside a character. A missing side at the start or end of
            // the text counts as valid and non-word.
            if (validBefore) {
                flags |= EMPTY_RUST_UNICODE_VALID_BEFORE;
            }
            if (validAfter) {
                flags |= EMPTY_RUST_UNICODE_VALID_AFTER;
            }
            if (wordBefore == wordAfter && validBefore && validAfter) {
                flags |= EMPTY_RUST_NO_UNICODE_WORD_BOUNDARY;
            }
        }
        return flags;
    }

    private static boolean isValid(long decoded)
    {
        return Utf8.decodedCodePoint(decoded) != Utf8.RUNE_ERROR || Utf8.decodedWidth(decoded) != 1;
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

    private static boolean isJavaEndTextOrFinalTerminator(byte[] bytes, int begin, int end, int position)
    {
        if (position == end) {
            return true;
        }

        int nextCodePoint = nextCodePoint(bytes, end, position);
        int nextPosition = nextCodePointPosition(bytes, end, position);
        if (nextCodePoint == '\r' && nextPosition < end && nextCodePoint(bytes, end, nextPosition) == '\n') {
            return nextCodePointPosition(bytes, end, nextPosition) == end;
        }
        // A final CR LF is one terminator, so the JDK refuses the position between its bytes.
        if (nextCodePoint == '\n' && position > begin && bytes[position - 1] == '\r') {
            return false;
        }
        return isJavaLineTerminator(nextCodePoint) && nextPosition == end;
    }

    /// Returns the Java $ flags to clear when a CR precedes the context: at a context start on the
    /// LF of a CR LF, because the JDK's $ reads the CR before a matcher region.
    static int javaEndFlagsRefusedAfterCarriageReturn(byte[] bytes, int begin, int end, int position)
    {
        if (position == begin && position < end && bytes[position] == '\n') {
            return JAVA_END_ASSERTIONS;
        }
        return 0;
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
        return UnicodeGroups.isNonspacingMark(codePoint) &&
                hasJavaBoundaryBaseCharacter(bytes, begin, end, codePointStart);
    }

    private static boolean hasJavaBoundaryBaseCharacter(byte[] bytes, int begin, int end, int position)
    {
        // Java ignores a nonspacing mark for boundary purposes unless a preceding letter or digit gives it a base.
        int codePointStart = position;
        while (codePointStart >= begin) {
            int codePoint = nextCodePoint(bytes, end, codePointStart);
            if (UnicodeGroups.isLetterOrDigit(codePoint)) {
                return true;
            }
            if (!UnicodeGroups.isNonspacingMark(codePoint) || codePointStart == begin) {
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
        return codePoint >= 0 && UnicodeGroups.isTrinoWord(codePoint);
    }
}
