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

import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceUtf8;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static java.util.Objects.requireNonNull;

final class TrinoLikeParser
{
    private TrinoLikeParser() {}

    static List<Element> parse(Slice pattern, OptionalInt escapeCodePoint)
    {
        requireNonNull(pattern, "pattern is null");
        if (escapeCodePoint.isPresent() && !Character.isValidCodePoint(escapeCodePoint.orElseThrow())) {
            throw new IllegalArgumentException("escape must be one code point");
        }
        if (escapeCodePoint.isEmpty()) {
            List<Element> ascii = parseAsciiNoEscape(pattern);
            if (ascii != null) {
                return ascii;
            }
        }

        List<Element> result = new ArrayList<>();
        LiteralBuilder literal = new LiteralBuilder(pattern);
        int anyCount = 0;
        boolean hasZeroOrMore = false;
        boolean inEscape = false;
        int escape = escapeCodePoint.orElse(0);
        int escapeByteOffset = -1;

        byte[] bytes = pattern.byteArray();
        int inputStart = pattern.byteArrayOffset();
        int inputEnd = inputStart + pattern.length();
        for (int position = inputStart; position < inputEnd; ) {
            long decoded = Utf8.decode(bytes, position, inputEnd);
            int codePoint = Utf8.decodedCodePoint(decoded);
            int width = Utf8.decodedWidth(decoded);
            int byteOffset = position - inputStart;
            if (inEscape) {
                if (codePoint != '%' && codePoint != '_' && codePoint != escape) {
                    throw syntaxException(byteOffset);
                }
                literal.append(byteOffset, width, codePoint);
                inEscape = false;
            }
            else if (escapeCodePoint.isPresent() && codePoint == escape) {
                addWildcards(result, anyCount, hasZeroOrMore);
                anyCount = 0;
                hasZeroOrMore = false;
                inEscape = true;
                escapeByteOffset = byteOffset;
            }
            else if (codePoint == '%' || codePoint == '_') {
                literal.addTo(result);
                if (codePoint == '%') {
                    hasZeroOrMore = true;
                }
                else {
                    anyCount++;
                }
            }
            else {
                addWildcards(result, anyCount, hasZeroOrMore);
                anyCount = 0;
                hasZeroOrMore = false;
                literal.append(byteOffset, width, codePoint);
            }
            position += width;
        }

        if (inEscape) {
            throw syntaxException(escapeByteOffset);
        }
        literal.addTo(result);
        addWildcards(result, anyCount, hasZeroOrMore);
        return List.copyOf(result);
    }

    /**
     * Parses a pattern without an escape character when every byte is ASCII, so no code point
     * decoding or malformed-byte replacement is needed. Returns null for other patterns. For the
     * patterns it accepts, the result must equal what {@link #parse} produces without an escape.
     */
    static List<Element> parseAsciiNoEscape(Slice pattern)
    {
        requireNonNull(pattern, "pattern is null");
        if (!SliceUtf8.isAscii(pattern)) {
            return null;
        }

        byte[] bytes = pattern.byteArray();
        int start = pattern.byteArrayOffset();
        int end = start + pattern.length();
        List<Element> result = new ArrayList<>();
        int literalStart = start;
        int anyCount = 0;
        boolean hasZeroOrMore = false;

        for (int position = start; position < end; position++) {
            byte value = bytes[position];
            if (value != '%' && value != '_') {
                continue;
            }
            if (literalStart < position) {
                addWildcards(result, anyCount, hasZeroOrMore);
                anyCount = 0;
                hasZeroOrMore = false;
                result.add(new Literal(pattern.copy(literalStart - start, position - literalStart)));
            }
            if (value == '%') {
                hasZeroOrMore = true;
            }
            else {
                anyCount++;
            }
            literalStart = position + 1;
        }
        if (literalStart < end) {
            addWildcards(result, anyCount, hasZeroOrMore);
            anyCount = 0;
            hasZeroOrMore = false;
            result.add(new Literal(pattern.copy(literalStart - start, end - literalStart)));
        }
        addWildcards(result, anyCount, hasZeroOrMore);
        return List.copyOf(result);
    }

    private static void addWildcards(List<Element> result, int anyCount, boolean hasZeroOrMore)
    {
        if (anyCount != 0) {
            result.add(new Any(anyCount));
        }
        if (hasZeroOrMore) {
            result.add(ZeroOrMore.INSTANCE);
        }
    }

    /**
     * Accumulates one literal. A literal is usually one contiguous run of pattern bytes, which is
     * copied once; escapes and replaced malformed bytes fall back to an output buffer. Once the
     * buffer exists, every later literal is built in it.
     */
    private static final class LiteralBuilder
    {
        private final Slice pattern;
        private int runStart = -1;
        private int runEnd;
        private DynamicSliceOutput buffer;

        private LiteralBuilder(Slice pattern)
        {
            this.pattern = pattern;
        }

        void append(int byteOffset, int width, int codePoint)
        {
            boolean replaced = codePoint == Utf8.RUNE_ERROR && width == 1;
            if (buffer == null && !replaced) {
                if (runStart < 0) {
                    runStart = byteOffset;
                    runEnd = byteOffset + width;
                    return;
                }
                if (runEnd == byteOffset) {
                    runEnd += width;
                    return;
                }
            }
            if (buffer == null) {
                buffer = new DynamicSliceOutput(pattern.length());
            }
            if (runStart >= 0) {
                buffer.writeBytes(pattern, runStart, runEnd - runStart);
                runStart = -1;
            }
            if (replaced) {
                Utf8.encode(buffer, Utf8.RUNE_ERROR);
            }
            else {
                buffer.writeBytes(pattern, byteOffset, width);
            }
        }

        void addTo(List<Element> result)
        {
            if (runStart >= 0) {
                result.add(new Literal(pattern.copy(runStart, runEnd - runStart)));
                runStart = -1;
            }
            else if (buffer != null && buffer.size() != 0) {
                result.add(new Literal(buffer.copySlice()));
                buffer.reset();
            }
        }
    }

    private static TrinoLikePatternSyntaxException syntaxException(int byteOffset)
    {
        return new TrinoLikePatternSyntaxException(
                "escape character must be followed by '%', '_', or the escape character",
                byteOffset);
    }

    sealed interface Element
            permits Any,
                    Literal,
                    ZeroOrMore {}

    record Literal(Slice bytes)
            implements Element {}

    record Any(int count)
            implements Element
    {
        Any
        {
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive");
            }
        }
    }

    enum ZeroOrMore
            implements Element
    {
        INSTANCE
    }
}
