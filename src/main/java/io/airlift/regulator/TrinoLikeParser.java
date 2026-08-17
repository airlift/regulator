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

        List<Element> result = new ArrayList<>();
        DynamicSliceOutput literal = new DynamicSliceOutput(pattern.length());
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
                appendCodePoint(literal, pattern, byteOffset, width, codePoint);
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
                addLiteral(result, literal);
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
                appendCodePoint(literal, pattern, byteOffset, width, codePoint);
            }
            position += width;
        }

        if (inEscape) {
            throw syntaxException(escapeByteOffset);
        }
        addLiteral(result, literal);
        addWildcards(result, anyCount, hasZeroOrMore);
        return List.copyOf(result);
    }

    private static void addLiteral(List<Element> result, DynamicSliceOutput literal)
    {
        if (literal.size() == 0) {
            return;
        }
        result.add(new Literal(literal.copySlice()));
        literal.reset();
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

    private static void appendCodePoint(DynamicSliceOutput literal, Slice pattern, int byteOffset, int width, int codePoint)
    {
        if (codePoint == Utf8.RUNE_ERROR && width == 1) {
            Utf8.encode(literal, Utf8.RUNE_ERROR);
            return;
        }
        literal.writeBytes(pattern, byteOffset, width);
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
