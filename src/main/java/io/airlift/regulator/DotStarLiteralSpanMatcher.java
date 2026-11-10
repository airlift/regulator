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

import io.airlift.slice.SizeOf;
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
    // The bytes of an ASCII run skipDotAscii reads inline before it considers the vector scan.
    private static final int INLINE_DOT_ASCII_BYTES = 16;
    // Indexed by a byte, the width of the character the dot reads from it, or 0 where the dot
    // matches no character: at a line feed, and in UTF-8 at a byte that leads none. A width above
    // one also needs that many bytes before the end, each after the first a continuation byte.
    private static final byte[] UTF8_CHARACTER_WIDTHS = characterWidths(false);
    private static final byte[] LATIN1_CHARACTER_WIDTHS = characterWidths(true);

    private final Slice literal;
    // The pattern's shared contains search for this literal, or null for a one-byte literal or
    // no room beside the forward DFAs. Null selects Slice.indexOf.
    private final LiteralSearchKernel search;
    private final byte[] characterWidths;

    private DotStarLiteralSpanMatcher(Slice literal, LiteralSearchKernel search, byte[] characterWidths)
    {
        this.literal = requireNonNull(literal, "literal is null");
        this.search = search;
        this.characterWidths = requireNonNull(characterWidths, "characterWidths is null");
    }

    /// Returns the matcher for `analysis`, or null. `literal` is the pattern's boolean plans'
    /// literal, which they take from the same literal sequence when it holds exactly one literal,
    /// as every sequence this accepts does, so the matcher retains no copy of its own. `search` is
    /// the pattern's boolean contains search for that literal, or null, and `latin1` whether the
    /// pattern reads its input as Latin-1. A Rust bytes-API pattern is Latin-1, and its only
    /// `[^\n]` dots are the byte classes of `(?-u)`, since its Unicode dot also excludes the
    /// surrogates, which the analysis does not take for a gap.
    static DotStarLiteralSpanMatcher analyze(ExpressionAnalysis analysis, Slice literal, LiteralSearchKernel search, boolean latin1)
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

        byte[] characterWidths = latin1 ? LATIN1_CHARACTER_WIDTHS : UTF8_CHARACTER_WIDTHS;
        if (literal.length() == 0 || !isCharacters(literal, characterWidths)) {
            return null;
        }
        return new DotStarLiteralSpanMatcher(literal, search, characterWidths);
    }

    /// Returns whether the dots read `literal` as whole characters, none a line feed. When they
    /// do, a walk over the dots' characters steps onto every occurrence of the literal it reaches
    /// rather than over its first byte, and through the literal to its end, so the first
    /// occurrence fixes the leftmost match and the walk on from it finds the match end. A literal
    /// from a valid UTF-8 pattern always is, unless it holds a line feed.
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

    /// Returns the memory this matcher adds to its pattern. Its literal is the boolean plans', its
    /// search the pattern's contains search, which reserves its own memory, and its width table is
    /// shared by every matcher, so only the matcher itself is new.
    long estimatedRetainedSize()
    {
        return SizeOf.instanceSize(DotStarLiteralSpanMatcher.class);
    }

    LiteralSearchKernel searchForDiagnostics()
    {
        return search;
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
        return findSpan(input, start, end, null);
    }

    /// Returns [#findSpan(Slice,int,int)], adding the vector scans its dots take to
    /// `diagnostics`.
    long findSpanForDiagnostics(Slice input, int start, int end, ScanDiagnostics diagnostics)
    {
        return findSpan(input, start, end, requireNonNull(diagnostics, "diagnostics is null"));
    }

    /// Returns [#findSpan(Slice,int,int)], adding the vector scans its dots take to `diagnostics`
    /// unless it is `null`, as every production search passes it. The literal-kernel searches
    /// call this overload directly, which saves them the frame of the other, and it calls the
    /// contains search itself rather than through `findLiteral`, to keep the search's word reads
    /// within C2's inline depth below the public entries; see
    /// [CompiledPattern#oneShotDotStarLiteral].
    long findSpan(Slice input, int start, int end, ScanDiagnostics diagnostics)
    {
        int literalStart = search != null ? search.findContains(input, start, end - start) : findLiteral(input, start, end);
        if (literalStart < 0) {
            return Dfa.SEARCH_NO_MATCH;
        }

        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int regionEnd = inputOffset + end;
        int literalPosition = inputOffset + literalStart;
        // The match starts after the last byte before the literal where the dots match nothing.
        int matchStart = inputOffset + start;
        int position = skipDotAscii(bytes, matchStart, literalPosition, diagnostics);
        while (position < literalPosition) {
            int width = characterWidth(characterWidths, bytes, position, regionEnd);
            if (width == 0) {
                matchStart = ++position;
            }
            else {
                position += width;
            }
            position = skipDotAscii(bytes, position, literalPosition, diagnostics);
        }

        int matchEnd = trailingDotsEnd(bytes, literalPosition + literal.length(), regionEnd, diagnostics);
        return ((long) (matchStart - inputOffset) << 32) | ((matchEnd - inputOffset) & 0xFFFF_FFFFL);
    }

    /// Returns the number of non-overlapping matches in `input`. A match holds the literal, so it is
    /// never empty, and each search resumes at the previous match's end. The count needs only
    /// each match's end, which the trailing dots fix from the literal's first occurrence, so it
    /// never walks the dots before the literal, as [#findSpan(Slice,int,int)] does for the
    /// match start.
    long count(Slice input)
    {
        return count(input, null);
    }

    /// Returns [#count(Slice)], adding the vector scans its trailing dots take to `diagnostics`.
    long countForDiagnostics(Slice input, ScanDiagnostics diagnostics)
    {
        return count(input, requireNonNull(diagnostics, "diagnostics is null"));
    }

    // A null `diagnostics`, as every production count passes, counts nothing.
    private long count(Slice input, ScanDiagnostics diagnostics)
    {
        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset();
        int inputEnd = inputOffset + input.length();
        long count = 0;
        int searchStart = 0;
        while (searchStart < input.length()) {
            int literalStart = findLiteral(input, searchStart, input.length());
            if (literalStart < 0) {
                break;
            }
            count++;
            searchStart = trailingDotsEnd(bytes, inputOffset + literalStart + literal.length(), inputEnd, diagnostics) - inputOffset;
        }
        return count;
    }

    /// Returns where the dots after a literal ending at `position` stop: at the first byte before
    /// `end` where they match no character, or at `end`.
    private int trailingDotsEnd(byte[] bytes, int position, int end, ScanDiagnostics diagnostics)
    {
        position = skipDotAscii(bytes, position, end, diagnostics);
        while (position < end) {
            int width = characterWidth(characterWidths, bytes, position, end);
            if (width == 0) {
                break;
            }
            position += width;
            position = skipDotAscii(bytes, position, end, diagnostics);
        }
        return position;
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

    /// Returns the first position at or after `position`, before `end`, holding a line feed or a
    /// byte with its high bit set, or `end`. Every other byte is an ASCII character of width one
    /// in every encoding, so the walks step over such runs without the width table, which they
    /// read only at the bytes this stops at. A search with fewer than [#INLINE_DOT_ASCII_BYTES]
    /// plus [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES] bytes before `end` walks its run in one
    /// inline loop. A longer one reads the first [#INLINE_DOT_ASCII_BYTES] bytes inline and, once
    /// it has read that many without a stop, skips the rest of the run with the vector scan the
    /// forward DFA's count uses to leave a `[^\n]` self-loop, which the count on this matcher
    /// replaced; the dots' width-table walk read every byte of 1024-byte benchmark rows. Without
    /// the Vector API, it walks the rest of the run byte by byte.
    private static int skipDotAscii(byte[] bytes, int position, int end, ScanDiagnostics diagnostics)
    {
        // Text that mixes short ASCII runs with other characters, such as spaced words of
        // non-ASCII text, stops within a few bytes, where a vector call that lands a byte or two
        // ahead costs more than the bytes it skips. The DFA's self-loop scan this replaced leaves
        // such text to its ordinary search once repeated short scans show its exits are dense;
        // here the scan starts only after the inline loop has read a long run. A search too short
        // to reach the scan walks its run in one loop: a second loop after the first
        // INLINE_DOT_ASCII_BYTES bytes measured 3-4% slower on Trino's dot-star line counts on
        // both platforms.
        int inlineEnd = end - position >= INLINE_DOT_ASCII_BYTES + CompiledPattern.MINIMUM_LONG_SEARCH_BYTES ? position + INLINE_DOT_ASCII_BYTES : end;
        while (position < inlineEnd) {
            byte value = bytes[position];
            if (value < 0 || value == '\n') {
                return position;
            }
            position++;
        }
        if (end - position >= CompiledPattern.MINIMUM_LONG_SEARCH_BYTES && VectorSupport.isAvailable()) {
            if (diagnostics != null) {
                diagnostics.vectorScanCount++;
            }
            int stop = VectorByteSetScanner.findHighBitOr(bytes, position, end - position, (byte) '\n', (byte) 0, (byte) 0, 1);
            return stop < 0 ? end : stop;
        }
        while (position < end) {
            byte value = bytes[position];
            if (value < 0 || value == '\n') {
                break;
            }
            position++;
        }
        return position;
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

    /// Counts the vector scans a diagnostics search or count of a dot-star literal takes.
    static final class ScanDiagnostics
    {
        private long vectorScanCount;

        /// The vector scans that skipped a run of ASCII bytes the dots match.
        long vectorScanCount()
        {
            return vectorScanCount;
        }
    }
}
