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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.airlift.slice.SizeOf;
import io.airlift.slice.Slice;

import java.util.Arrays;

import static io.airlift.regulator.ExpressionAnalysis.Gap.ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS;
import static io.airlift.regulator.ExpressionAnalysis.Gap.ZERO_OR_MORE_NON_STOP_CODE_POINTS;
import static java.util.Objects.requireNonNull;

/// Finds the spans of a `.*literal.*` pattern whose dots match any character but their stops: the
/// literal's first occurrence, widened over the characters the dots match on each side.
///
/// This class serves dots whose only stop is a line feed. The dots read characters as the
/// compiled program's byte ranges for the class `[^\n]` read them, which in every language depend
/// only on the pattern's encoding. A UTF-8 class holding all of U+0080 through U+10FFFF compiles to
/// lenient byte ranges (see [Utf8#re2CharacterWidth]), which accept overlong three- and four-byte
/// forms, encoded surrogates, and values above U+10FFFF, and reject bare continuation bytes, C0,
/// C1, F5-FF, and cut-short sequences. A Latin-1 pattern, RE2's Latin-1 option or Rust's bytes API,
/// reads each byte as a character. The encoding selects a table of character widths at compile
/// time, so the walk has no branch on the language. Dots with other stops as well, such as Java's
/// default dot, take [MultipleStops], whose searches and count walk by the same rules. Each class
/// keeps its walks private, and each search and count entry calls its own class's walk directly:
/// in a JVM that has linked an override of a walk, the walk is no longer statically bound, and C2
/// stops inlining the line-feed walk into its callers.
sealed class DotStarLiteralSpanMatcher
        implements GroupZeroSpanSearch
        permits DotStarLiteralSpanMatcher.MultipleStops
{
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(DotStarLiteralSpanMatcher.class);
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

    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "Sealed, and its one subclass is final, so no subclass can finalize a partly built matcher")
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
    /// pattern reads its input as Latin-1. Dots of exactly `[^\n]` take this class, and dots that
    /// also stop at other characters take [MultipleStops]. A Rust bytes-API pattern is Latin-1, and
    /// its only dots that take a matcher are the byte classes of `(?-u)`: its Unicode dot is a
    /// class of characters, which also excludes the surrogates, and a Latin-1 pattern takes no
    /// matcher for one.
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
                sequence.gapAfter(0) != sequence.leadingGap()) {
            return null;
        }
        if (sequence.leadingGap() == ZERO_OR_MORE_NON_STOP_CODE_POINTS) {
            return MultipleStops.analyze(literal, search, sequence.dotStops(), latin1);
        }
        if (sequence.leadingGap() != ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS) {
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
        return INSTANCE_SIZE;
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
        return findSpanWithinLine(input, start, input.length(), null);
    }

    /// Returns the span of the first match lying entirely in `[start, end)` of `input`, or
    /// [Dfa#SEARCH_NO_MATCH]; the caller checks that `0 <= start <= end <= input.length()`. The
    /// literal must end by `end`, and the trailing dots stop there, as at a line feed. The match
    /// starts at `start`, or after the last byte between `start` and the literal where the dots
    /// match nothing, a line feed or a byte where the walk finds no character, so the walk from
    /// `start` to the literal moves the start past each such byte, and once it meets the first line
    /// feed jumps to after the last one, which a backward scan finds. That start is exact: no
    /// character the dots read holds a line feed, so the walk would land on every line feed, and
    /// each one starts the match after it whatever lies before. The search reads nothing before
    /// `start` or at or past `end`.
    long findSpan(Slice input, int start, int end)
    {
        return findSpanWithinLine(input, start, end, null);
    }

    /// Returns [#findSpan(Slice,int,int)], adding its forward vector scans over ASCII runs and
    /// its backward scans for the last ASCII stop to `diagnostics`.
    long findSpanForDiagnostics(Slice input, int start, int end, ScanDiagnostics diagnostics)
    {
        return findSpanWithinLine(input, start, end, requireNonNull(diagnostics, "diagnostics is null"));
    }

    /// Returns [#findSpan(Slice,int,int)], adding its scans to `diagnostics` as
    /// [#findSpanForDiagnostics(Slice,int,int,ScanDiagnostics)] counts them, unless it is `null`,
    /// as every production search passes it. The literal-kernel searches call this overload.
    long findSpan(Slice input, int start, int end, ScanDiagnostics diagnostics)
    {
        return findSpanWithinLine(input, start, end, diagnostics);
    }

    // The walk behind every search, for dots whose only stop is a line feed. It calls the contains
    // search itself rather than through findLiteral, to keep the search's word reads within C2's
    // inline depth below the public entries; see CompiledPattern.oneShotDotStarLiteral.
    private long findSpanWithinLine(Slice input, int start, int end, ScanDiagnostics diagnostics)
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
                position = bytes[position] == '\n' ? afterLastLineFeed(bytes, position, literalPosition, diagnostics) : position + 1;
                matchStart = position;
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

    /// Returns the position after the last line feed in `[begin, end)`, or `begin` when the range
    /// holds none, reading nothing outside it. A range of at least
    /// [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES] bytes takes the backward vector scan the reverse
    /// DFA uses to leave a self-loop; a shorter one, or any without the Vector API, steps back
    /// byte by byte. Either way, it adds one backward scan to `diagnostics` unless it is `null`.
    private static int afterLastLineFeed(byte[] bytes, int begin, int end, ScanDiagnostics diagnostics)
    {
        if (diagnostics != null) {
            diagnostics.backwardScanCount++;
        }
        if (end - begin >= CompiledPattern.MINIMUM_LONG_SEARCH_BYTES && VectorSupport.isAvailable()) {
            int lineFeed = VectorByteSetScanner.findLast(bytes, begin, end - begin, (byte) '\n', (byte) 0, (byte) 0, 1);
            return lineFeed < 0 ? begin : lineFeed + 1;
        }
        for (int position = end; position > begin; position--) {
            if (bytes[position - 1] == '\n') {
                return position;
            }
        }
        return begin;
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

    /// The matcher for dots that stop at a line feed and at other characters, the gap's
    /// [ExpressionAnalysis.DotStops]: Java's default dot, which also stops at a carriage return,
    /// U+0085, U+2028, and U+2029, Rust's CRLF dot, which also stops at a carriage return, or a
    /// class such as `[^\r\n]` in any language. The searches and the count walk as the enclosing
    /// class's do, with these differences:
    ///
    /// - The ASCII runs stop at every ASCII stop, at most [#MAXIMUM_ASCII_STOPS], which the vector
    ///   scans take as their candidates.
    /// - The walk to the literal, at an ASCII stop, jumps to after the last ASCII stop before the
    ///   literal. No character the dots read holds an ASCII byte after its first, so the walk would
    ///   land on each of them, and each one starts the match after it.
    /// - A non-ASCII byte reads as the compiled program's byte ranges for the class read it. A UTF-8
    ///   class without a non-ASCII stop holds all of U+0080 through U+10FFFF, whose lenient ranges
    ///   the enclosing class's width table reads. Any other UTF-8 class accepts exactly the
    ///   encodings of its members, an encoded surrogate among them unless the surrogates are stops,
    ///   which [Utf8#re2DecodeClassCharacter] reads, and then the dots match the character unless
    ///   it is a stop. A Latin-1 class reads each byte as a character, one of the stops or not.
    ///
    /// The enclosing class keeps its own walks, rather than these with a line feed as the only
    /// stop, so the dots of RE2, Rust, Trino, and Java's `(?d)` compile as they did before this
    /// class. A change to the rules of one walk needs the same change in the other. This class
    /// overrides only the thin entries that call the walks, never a walk, so linking it leaves
    /// the enclosing class's walk statically bound and inlined into its callers.
    static final class MultipleStops
            extends DotStarLiteralSpanMatcher
    {
        private static final int INSTANCE_SIZE = SizeOf.instanceSize(MultipleStops.class);

        // The most ASCII stops the vector scans take.
        private static final int MAXIMUM_ASCII_STOPS = 3;

        // The ASCII stops, a line feed among them, and how many there are; an unused one repeats
        // the first.
        private final byte firstAsciiStop;
        private final byte secondAsciiStop;
        private final byte thirdAsciiStop;
        private final int asciiStopCount;
        // In a UTF-8 pattern with non-ASCII stops, each one's range as its first and last code
        // points, and otherwise null, where the width table reads every byte.
        private final int[] nonAsciiStops;

        private MultipleStops(Slice literal, LiteralSearchKernel search, byte[] characterWidths, byte[] asciiStops, int asciiStopCount, int[] nonAsciiStops)
        {
            super(literal, search, characterWidths);
            this.firstAsciiStop = asciiStops[0];
            this.secondAsciiStop = asciiStopCount > 1 ? asciiStops[1] : asciiStops[0];
            this.thirdAsciiStop = asciiStopCount > 2 ? asciiStops[2] : asciiStops[0];
            this.asciiStopCount = asciiStopCount;
            this.nonAsciiStops = nonAsciiStops;
        }

        /// Returns the matcher for dots with `stops`, or null when they have more ASCII stops than
        /// the vector scans take, when the stops' class reads characters in another encoding than
        /// the pattern, or when the dots do not read `literal` as whole characters, none a stop.
        private static MultipleStops analyze(Slice literal, LiteralSearchKernel search, ExpressionAnalysis.DotStops stops, boolean latin1)
        {
            if (stops.latin1() != latin1) {
                return null;
            }
            byte[] characterWidths = characterWidths(latin1);
            byte[] asciiStops = new byte[MAXIMUM_ASCII_STOPS];
            int asciiStopCount = 0;
            int[] nonAsciiStops = new int[stops.ranges().size() * 2];
            int nonAsciiStopCount = 0;
            for (RuneRange range : stops.ranges()) {
                for (int value = range.low(); value <= Math.min(range.high(), latin1 ? 0xFF : 0x7F); value++) {
                    characterWidths[value] = 0;
                    if (value < 0x80) {
                        if (asciiStopCount == MAXIMUM_ASCII_STOPS) {
                            return null;
                        }
                        asciiStops[asciiStopCount++] = (byte) value;
                    }
                }
                if (!latin1 && range.high() >= 0x80) {
                    nonAsciiStops[nonAsciiStopCount++] = Math.max(range.low(), 0x80);
                    nonAsciiStops[nonAsciiStopCount++] = range.high();
                }
            }
            MultipleStops matcher = new MultipleStops(literal, search, characterWidths, asciiStops, asciiStopCount, nonAsciiStopCount == 0 ? null : Arrays.copyOf(nonAsciiStops, nonAsciiStopCount));
            return matcher.readsCharacters(literal) ? matcher : null;
        }

        /// Returns the memory this matcher adds to its pattern: the matcher, its width table, which
        /// zeroes its stops, and its non-ASCII stops.
        @Override
        long estimatedRetainedSize()
        {
            return INSTANCE_SIZE + SizeOf.sizeOf(super.characterWidths) + SizeOf.sizeOf(nonAsciiStops);
        }

        /// Returns whether the dots read the literal as whole characters, none a stop; see the
        /// enclosing class's isCharacters.
        private boolean readsCharacters(Slice literal)
        {
            if (literal.length() == 0) {
                return false;
            }
            byte[] bytes = literal.byteArray();
            int end = literal.byteArrayOffset() + literal.length();
            int position = literal.byteArrayOffset();
            while (position < end) {
                byte value = bytes[position];
                int width = value >= 0 ? super.characterWidths[value] : nonAsciiWidth(bytes, position, end);
                if (width == 0) {
                    return false;
                }
                position += width;
            }
            return true;
        }

        @Override
        long findSpan(Slice input, int start)
        {
            requireNonNull(input, "input is null");
            if (start < 0 || start > input.length()) {
                return Dfa.SEARCH_NO_MATCH;
            }
            return findSpanBetweenStops(input, start, input.length(), null);
        }

        @Override
        long findSpan(Slice input, int start, int end)
        {
            return findSpanBetweenStops(input, start, end, null);
        }

        @Override
        long findSpanForDiagnostics(Slice input, int start, int end, ScanDiagnostics diagnostics)
        {
            return findSpanBetweenStops(input, start, end, requireNonNull(diagnostics, "diagnostics is null"));
        }

        @Override
        long findSpan(Slice input, int start, int end, ScanDiagnostics diagnostics)
        {
            return findSpanBetweenStops(input, start, end, diagnostics);
        }

        // The enclosing class's findSpanWithinLine for these dots, which calls the contains
        // search itself for the same reason.
        private long findSpanBetweenStops(Slice input, int start, int end, ScanDiagnostics diagnostics)
        {
            LiteralSearchKernel search = super.search;
            int literalStart = search != null ? search.findContains(input, start, end - start) : super.findLiteral(input, start, end);
            if (literalStart < 0) {
                return Dfa.SEARCH_NO_MATCH;
            }

            byte[] bytes = input.byteArray();
            int inputOffset = input.byteArrayOffset();
            int regionEnd = inputOffset + end;
            int literalPosition = inputOffset + literalStart;
            // The match starts after the last byte before the literal where the dots match nothing.
            int matchStart = inputOffset + start;
            int position = skipAscii(bytes, matchStart, literalPosition, diagnostics);
            while (position < literalPosition) {
                if (bytes[position] >= 0) {
                    position = afterLastAsciiStop(bytes, position, literalPosition, diagnostics);
                    matchStart = position;
                }
                else {
                    int width = nonAsciiWidth(bytes, position, regionEnd);
                    if (width == 0) {
                        position++;
                        matchStart = position;
                    }
                    else {
                        position += width;
                    }
                }
                position = skipAscii(bytes, position, literalPosition, diagnostics);
            }

            int matchEnd = dotsEnd(bytes, literalPosition + super.literal.length(), regionEnd, diagnostics);
            return ((long) (matchStart - inputOffset) << 32) | ((matchEnd - inputOffset) & 0xFFFF_FFFFL);
        }

        @Override
        long count(Slice input)
        {
            return countMatches(input, null);
        }

        @Override
        long countForDiagnostics(Slice input, ScanDiagnostics diagnostics)
        {
            return countMatches(input, requireNonNull(diagnostics, "diagnostics is null"));
        }

        // A null `diagnostics`, as every production count passes, counts nothing.
        private long countMatches(Slice input, ScanDiagnostics diagnostics)
        {
            byte[] bytes = input.byteArray();
            int inputOffset = input.byteArrayOffset();
            int inputEnd = inputOffset + input.length();
            int literalLength = super.literal.length();
            long count = 0;
            int searchStart = 0;
            while (searchStart < input.length()) {
                int literalStart = super.findLiteral(input, searchStart, input.length());
                if (literalStart < 0) {
                    break;
                }
                count++;
                searchStart = dotsEnd(bytes, inputOffset + literalStart + literalLength, inputEnd, diagnostics) - inputOffset;
            }
            return count;
        }

        /// Returns where the dots after a literal ending at `position` stop: at the first byte
        /// before `end` where they match no character, or at `end`.
        private int dotsEnd(byte[] bytes, int position, int end, ScanDiagnostics diagnostics)
        {
            position = skipAscii(bytes, position, end, diagnostics);
            while (position < end && bytes[position] < 0) {
                int width = nonAsciiWidth(bytes, position, end);
                if (width == 0) {
                    break;
                }
                position += width;
                position = skipAscii(bytes, position, end, diagnostics);
            }
            return position;
        }

        /// Returns the position after the last ASCII stop in `[begin, end)`, which holds one at
        /// `begin`, reading nothing outside it, by the enclosing class's afterLastLineFeed rules.
        private int afterLastAsciiStop(byte[] bytes, int begin, int end, ScanDiagnostics diagnostics)
        {
            if (diagnostics != null) {
                diagnostics.backwardScanCount++;
            }
            if (end - begin >= CompiledPattern.MINIMUM_LONG_SEARCH_BYTES && VectorSupport.isAvailable()) {
                int stop = VectorByteSetScanner.findLast(bytes, begin, end - begin, firstAsciiStop, secondAsciiStop, thirdAsciiStop, asciiStopCount);
                return stop < 0 ? begin : stop + 1;
            }
            byte[] characterWidths = super.characterWidths;
            for (int position = end; position > begin; position--) {
                byte value = bytes[position - 1];
                if (value >= 0 && characterWidths[value] == 0) {
                    return position;
                }
            }
            return begin;
        }

        /// Returns the first position at or after `position`, before `end`, holding an ASCII stop
        /// or a byte with its high bit set, or `end`, by the enclosing class's skipDotAscii rules.
        private int skipAscii(byte[] bytes, int position, int end, ScanDiagnostics diagnostics)
        {
            byte[] characterWidths = super.characterWidths;
            int inlineEnd = end - position >= INLINE_DOT_ASCII_BYTES + CompiledPattern.MINIMUM_LONG_SEARCH_BYTES ? position + INLINE_DOT_ASCII_BYTES : end;
            while (position < inlineEnd) {
                byte value = bytes[position];
                if (value < 0 || characterWidths[value] == 0) {
                    return position;
                }
                position++;
            }
            if (end - position >= CompiledPattern.MINIMUM_LONG_SEARCH_BYTES && VectorSupport.isAvailable()) {
                if (diagnostics != null) {
                    diagnostics.vectorScanCount++;
                }
                int stop = VectorByteSetScanner.findHighBitOr(bytes, position, end - position, firstAsciiStop, secondAsciiStop, thirdAsciiStop, asciiStopCount);
                return stop < 0 ? end : stop;
            }
            while (position < end) {
                byte value = bytes[position];
                if (value < 0 || characterWidths[value] == 0) {
                    break;
                }
                position++;
            }
            return position;
        }

        /// Returns the width of the character the dots read at `position`, a byte with its high bit
        /// set before `end`, or 0 where they match none there.
        private int nonAsciiWidth(byte[] bytes, int position, int end)
        {
            int[] stops = nonAsciiStops;
            if (stops == null) {
                return characterWidth(super.characterWidths, bytes, position, end);
            }
            long decoded = Utf8.re2DecodeClassCharacter(bytes, position, end, false);
            int codePoint = Utf8.decodedCodePoint(decoded);
            if (codePoint < 0) {
                return 0;
            }
            for (int index = 0; index < stops.length; index += 2) {
                if (codePoint >= stops[index] && codePoint <= stops[index + 1]) {
                    return 0;
                }
            }
            return Utf8.decodedWidth(decoded);
        }
    }

    /// Counts the scans that a diagnostics search or count of a dot-star literal takes: the
    /// forward vector scans over ASCII runs and the backward scans for the last ASCII stop.
    static final class ScanDiagnostics
    {
        private long vectorScanCount;
        private long backwardScanCount;

        /// The vector scans that skipped a run of ASCII bytes the dots match.
        long vectorScanCount()
        {
            return vectorScanCount;
        }

        /// The backward scans, vector or byte by byte, for the last ASCII stop before the literal.
        long backwardScanCount()
        {
            return backwardScanCount;
        }
    }
}
