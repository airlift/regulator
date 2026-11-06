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

import static io.airlift.regulator.ExpressionAnalysis.Gap.ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS;
import static java.util.Objects.requireNonNull;

/// Finds the spans of a `.*literal.*` pattern whose dots match any character but a line feed:
/// the literal's first occurrence, widened over the characters the dots match on each side.
///
/// The dots read characters as the compiled program's byte ranges for the class `[^\n]` read them,
/// which in every language depend only on the pattern's encoding. A UTF-8 class holding all of
/// U+0080 through U+10FFFF compiles to lenient byte ranges (see [Utf8#re2CharacterWidth]), which
/// accept overlong three- and four-byte forms, encoded surrogates, and values above U+10FFFF, and
/// reject bare continuation bytes, C0, C1, F5-FF, and cut-short sequences. A Latin-1 pattern, RE2's
/// Latin-1 option or Rust's bytes API, reads each byte as a character. The encoding selects a table
/// of character widths at compile time, so the walk has no branch on the language.
final class DotStarLiteralSpanMatcher
        implements GroupZeroSpanSearch
{
    // Indexed by a byte, the width of the character the dot reads from it, or 0 where the dot
    // matches no character: at a line feed, and in UTF-8 at a byte that leads none. A width above
    // one also needs that many bytes before the end, each after the first a continuation byte.
    private static final byte[] UTF8_CHARACTER_WIDTHS = characterWidths(false);
    private static final byte[] LATIN1_CHARACTER_WIDTHS = characterWidths(true);

    private final Slice literal;
    // The pattern's shared contains search for this literal, or null for a one-byte literal or
    // no room in the forward-DFA budget. Null selects Slice.indexOf.
    private final LiteralSearchKernel search;
    private final byte[] characterWidths;

    private DotStarLiteralSpanMatcher(Slice literal, LiteralSearchKernel search, byte[] characterWidths)
    {
        this.literal = requireNonNull(literal, "literal is null");
        this.search = search;
        this.characterWidths = requireNonNull(characterWidths, "characterWidths is null");
    }

    /// Returns the matcher for `analysis`, or null; `search` is the pattern's boolean contains
    /// search for the matcher's literal, or null, and `latin1` whether the pattern reads its input
    /// as Latin-1. A Rust bytes-API pattern is Latin-1, and its only `[^\n]` dots are the byte
    /// classes of `(?-u)`, since its Unicode dot also excludes the surrogates, which the analysis
    /// does not take for a gap.
    static DotStarLiteralSpanMatcher analyze(ExpressionAnalysis analysis, LiteralSearchKernel search, boolean latin1)
    {
        ExpressionAnalysis.LiteralSequence sequence = analysis.literalSequence();
        if (analysis.hasCaptures() ||
                analysis.hasFoldCaseLiteral() ||
                analysis.hasNonGreedyRepetition() ||
                analysis.hasUnsupportedPositionAssertions() ||
                sequence == null ||
                sequence.literalCount() != 1 ||
                sequence.anchoredAtStart() ||
                sequence.anchoredAtEnd() ||
                sequence.leadingGap() != ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS ||
                sequence.gapAfter(0) != ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS) {
            return null;
        }

        Slice literal = sequence.retainedLiteral(0);
        byte[] characterWidths = latin1 ? LATIN1_CHARACTER_WIDTHS : UTF8_CHARACTER_WIDTHS;
        if (literal.length() == 0 || !isCharacters(literal, characterWidths)) {
            return null;
        }
        return new DotStarLiteralSpanMatcher(literal, search, characterWidths);
    }

    /// Returns whether the dots read `literal` as whole characters, none a line feed. Then a walk
    /// over the dots' characters steps onto every occurrence of the literal it reaches rather
    /// than over its first byte, and through the literal to its end, so the first occurrence
    /// fixes the leftmost match and the walk on from it finds the match end. A literal from a
    /// valid UTF-8 pattern always is, unless it holds a line feed.
    private static boolean isCharacters(Slice literal, byte[] characterWidths)
    {
        byte[] bytes = literal.byteArray();
        int end = literal.byteArrayOffset() + literal.length();
        int position = literal.byteArrayOffset();
        while (position < end) {
            int width = characterWidth(characterWidths, bytes, position, end);
            if (width == 0) {
                return false;
            }
            position += width;
        }
        return true;
    }

    LiteralSearchKernel searchForDiagnostics()
    {
        return search;
    }

    boolean readsLatin1ForDiagnostics()
    {
        return characterWidths == LATIN1_CHARACTER_WIDTHS;
    }

    /// Returns the span of the first match at or after `start` in `input`, or
    /// [Dfa#SEARCH_NO_MATCH].
    long findSpan(Slice input, int start)
    {
        requireNonNull(input, "input is null");
        if (start < 0 || start > input.length()) {
            return Dfa.SEARCH_NO_MATCH;
        }
        return findSpan(input, start, input.length());
    }

    /// Returns the span of the first match lying entirely in `[start, end)` of `input`, or
    /// [Dfa#SEARCH_NO_MATCH]; the caller checks that `0 <= start <= end <= input.length()`. The
    /// literal must end by `end`, and the trailing dots stop there, as at a line feed. The search
    /// reads nothing at or past `end`.
    long findSpan(Slice input, int start, int end)
    {
        int literalStart = findLiteral(input, start, end);
        if (literalStart < 0) {
            return Dfa.SEARCH_NO_MATCH;
        }

        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int regionEnd = inputOffset + end;
        int literalPosition = inputOffset + literalStart;
        // The match starts after the last byte before the literal where the dots match nothing.
        int matchStart = inputOffset + start;
        int position = matchStart;
        while (position < literalPosition) {
            int width = characterWidth(characterWidths, bytes, position, regionEnd);
            if (width == 0) {
                matchStart = ++position;
            }
            else {
                position += width;
            }
        }

        position = literalPosition + literal.length();
        while (position < regionEnd) {
            int width = characterWidth(characterWidths, bytes, position, regionEnd);
            if (width == 0) {
                break;
            }
            position += width;
        }
        return ((long) (matchStart - inputOffset) << 32) | ((position - inputOffset) & 0xFFFF_FFFFL);
    }

    /// Returns the number of non-overlapping matches in `input`. A match holds the literal, so it is
    /// never empty, and each search resumes at the previous match's end.
    long count(Slice input)
    {
        requireNonNull(input, "input is null");
        long count = 0;
        int searchStart = 0;
        while (searchStart < input.length()) {
            long span = findSpan(input, searchStart, input.length());
            if (span < 0) {
                break;
            }
            count++;
            searchStart = (int) span;
        }
        return count;
    }

    private int findLiteral(Slice input, int start, int end)
    {
        if (search != null) {
            return search.findContains(input, start, end - start);
        }
        // Slice.indexOf searches to the end of its Slice, so a region that ends before the input
        // searches a view that ends with it.
        return (end == input.length() ? input : input.slice(0, end)).indexOf(literal, start);
    }

    /// Returns the width of the character the dots read at `position`, before `end`, or 0 when
    /// they match none there.
    private static int characterWidth(byte[] characterWidths, byte[] bytes, int position, int end)
    {
        int width = characterWidths[bytes[position] & 0xFF];
        if (width > 1) {
            if (width > end - position) {
                return 0;
            }
            for (int offset = 1; offset < width; offset++) {
                if ((bytes[position + offset] & 0xC0) != 0x80) {
                    return 0;
                }
            }
        }
        return width;
    }

    private static byte[] characterWidths(boolean latin1)
    {
        byte[] widths = new byte[256];
        for (int value = 0; value < widths.length; value++) {
            if (value == '\n') {
                widths[value] = 0;
            }
            else if (latin1 || value < 0x80) {
                widths[value] = 1;
            }
            else if (value >= 0xC2 && value <= 0xF4) {
                widths[value] = (byte) (value < 0xE0 ? 2 : value < 0xF0 ? 3 : 4);
            }
        }
        return widths;
    }
}
