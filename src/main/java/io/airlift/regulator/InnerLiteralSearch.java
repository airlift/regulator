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
import io.airlift.slice.Slices;

import java.util.List;

import static java.util.Objects.requireNonNull;

/// Finds the leftmost-first match of a pattern `P L S`, whose top-level concatenation holds a
/// literal `L` after its first element, by searching for `L` instead of running the DFA over every
/// byte. This is the Rust crate's reverse inner strategy, which also covers its reverse suffix
/// strategy (regex-automata 0.4.18 `meta/strategy.rs` and `meta/reverse_inner.rs`). For each
/// occurrence of `L`, a reverse DFA of `P` anchored at the occurrence finds the earliest start, and
/// the forward DFA of the whole pattern anchored at that start finds the end.
///
/// The first confirmed candidate is the leftmost-first match only when no earlier match can span
/// a later occurrence of `L`. [#analyze] accepts a pattern only when one of the crate's proofs
/// holds (`meta/reverse_inner.rs` `has_no_earlier_match`): `P` cannot consume every byte of `L`,
/// `P` has a fixed length, or the first or last element of `P`, skipping elements such as
/// assertions that match only the empty string, is a required class disjoint from the rest of `P`
/// and from `L`.
///
/// As in the crate, a search reports [#FALLBACK], for its caller to run the ordinary engine, when
/// the reverse search would read back past the previous candidate, or a candidate lies in text the
/// previous anchored confirmation already read, which keeps the work linear, or when a DFA fails.
/// It also falls back when its first candidate lies fewer than [#MINIMUM_SKIPPED_BYTES] past the
/// search start. The prefix lies before the literal, so the search could skip no more than that,
/// which cannot repay the reverse search and the anchored confirmation.
final class InnerLiteralSearch
{
    static final long FALLBACK = Dfa.CANDIDATE_SEARCH_FALLBACK;
    // Searches of shorter windows stay on the ordinary engine and skip the literal scan setup. A
    // literal in such a window lies near its start, where the search would fall back anyway.
    static final int MINIMUM_SEARCH_BYTES = 64;
    // A first candidate closer than this to the search start leaves too few bytes to skip.
    static final int MINIMUM_SKIPPED_BYTES = 64;

    // The safety proofs recurse over the prefix. Past this depth each one stops with its
    // conservative answer, which keeps the proofs stack safe, as MatchLength bounds its own
    // recursion. Another proof, such as the fixed-length check, may still admit the search.
    private static final int MAX_RECURSIVE_DEPTH = 256;
    // Each overlapping hit of a longer literal re-reads the whole literal.
    private static final int MAXIMUM_LITERAL_LENGTH = 64;

    private static final int INSTANCE_SIZE = SizeOf.instanceSize(InnerLiteralSearch.class);

    private final LiteralSearchKernel literalSearch;
    private final int literalLength;
    private final Prog reversePrefixProgram;
    private final Prog forwardProgram;

    private InnerLiteralSearch(LiteralSearchKernel literalSearch, Prog reversePrefixProgram, Prog forwardProgram)
    {
        this.literalSearch = requireNonNull(literalSearch, "literalSearch is null");
        this.literalLength = literalSearch.literalLength();
        this.reversePrefixProgram = requireNonNull(reversePrefixProgram, "reversePrefixProgram is null");
        this.forwardProgram = requireNonNull(forwardProgram, "forwardProgram is null");
    }

    /// Returns the search for the normalized `regexp`, compiled into `forwardProgram`, or null when
    /// it has no usable inner literal, the safety proofs fail, or its reverse prefix DFA could not
    /// initialize. The reverse prefix program gets at most `prefixMemory` bytes, and the whole
    /// search, as [#estimatedRetainedSize] reports it, retains at most `retainedMemory`.
    static InnerLiteralSearch analyze(Regexp regexp, Prog forwardProgram, long prefixMemory, long retainedMemory, Compiler.Dialect dialect, CaseFolding caseFolding)
    {
        while (regexp.op() == RegexpOp.CAPTURE) {
            regexp = regexp.child(0);
        }
        if (regexp.op() != RegexpOp.CONCAT || forwardProgram.anchorStart() || forwardProgram.anchorEnd() ||
                forwardProgram.canPrefixAccel() || forwardProgram.hasDfaUnsupportedAssertions()) {
            return null;
        }
        List<Regexp> children = regexp.children();
        // As in the crate, the first element is skipped, since a literal there would have given a
        // prefix acceleration, and the first literal found decides.
        for (int index = 1; index < children.size(); index++) {
            byte[] literal = asciiLiteral(children.get(index));
            if (literal == null) {
                continue;
            }
            if ((literal.length == 1 && isCommonByte(literal[0])) || literal.length > MAXIMUM_LITERAL_LENGTH) {
                return null;
            }
            Regexp prefix = index == 1 ? children.get(0) : Regexp.concat(regexp.parseFlags(), children.subList(0, index));
            if (!hasNoEarlierMatch(prefix, literal, caseFolding)) {
                return null;
            }
            LiteralSearchKernel literalSearch = new LiteralSearchKernel(Slices.wrappedBuffer(literal));
            long memory = Math.min(prefixMemory, retainedMemory - INSTANCE_SIZE - literalSearch.estimatedRetainedSize());
            if (memory <= 0) {
                // The compiler reads a budget of zero or less as no limit.
                return null;
            }
            Prog reversePrefixProgram;
            try {
                reversePrefixProgram = Compiler.compileNormalized(prefix, true, memory, dialect, caseFolding);
            }
            catch (RegexpCompileException ignored) {
                // An optional search must not reject a valid semantic program.
                return null;
            }
            // Without its reverse prefix DFA, every search would fall back.
            if (reversePrefixProgram.hasDfaUnsupportedAssertions() ||
                    reversePrefixProgram.dfaMemory() < Dfa.DfaInstance.minimumMemory(reversePrefixProgram, Dfa.DfaInstance.Kind.LONGEST_MATCH)) {
                return null;
            }
            return new InnerLiteralSearch(literalSearch, reversePrefixProgram, forwardProgram);
        }
        return null;
    }

    byte[] literal()
    {
        return literalSearch.literal();
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + literalSearch.estimatedRetainedSize() + Compiler.estimatedProgramMemory(reversePrefixProgram) + reversePrefixProgram.dfaMemory();
    }

    /// Whether a search of `[start, end)` of `text` falls back because its first candidate lies
    /// near the start. It reads at most `MINIMUM_SKIPPED_BYTES - 1` bytes plus the literal's length.
    boolean fallsBackNearStart(Slice text, int start, int end)
    {
        int length = Math.min(end - start, MINIMUM_SKIPPED_BYTES - 1 + literalLength);
        return findLiteral(text, start, length) >= 0;
    }

    /// Searches `[start, end)` of `text` with assertions seeing `[contextStart, contextEnd)`, and
    /// returns the match's Slice offsets packed as `start << 32 | end`, [Dfa#SEARCH_NO_MATCH], or
    /// [#FALLBACK].
    long search(Slice text, int contextStart, int contextEnd, int start, int end)
    {
        // The reverse search may read back to the end of the previous candidate, and a candidate
        // may not start inside the text the previous confirmation read.
        int minimumMatchStart = start;
        int minimumLiteralStart = start;
        int scanStart = start;
        while (end - scanStart >= literalLength) {
            int literalStart = findLiteral(text, scanStart, end - scanStart);
            if (literalStart < 0) {
                return Dfa.SEARCH_NO_MATCH;
            }
            if (scanStart == start && literalStart - start < MINIMUM_SKIPPED_BYTES) {
                return FALLBACK;
            }
            if (literalStart < minimumLiteralStart) {
                return FALLBACK;
            }
            int maximumReverseBytes = minimumMatchStart == start ? Integer.MAX_VALUE : Math.max(1, literalStart - minimumMatchStart);
            long reverse = Dfa.searchReverseCandidate(reversePrefixProgram, text, contextStart, contextEnd, start, literalStart, maximumReverseBytes);
            if (reverse == Dfa.CANDIDATE_SEARCH_FALLBACK || reverse == Dfa.SEARCH_FAILED) {
                return FALLBACK;
            }
            if (reverse >= 0) {
                int matchStart = start + (int) reverse;
                long forward = Dfa.searchAnchoredWithStop(forwardProgram, text, contextStart, contextEnd, matchStart, end);
                if (forward >= 0) {
                    return ((long) matchStart << 32) | forward;
                }
                if (forward == Dfa.SEARCH_FAILED) {
                    return FALLBACK;
                }
                minimumLiteralStart = Dfa.anchoredStopPosition(forward);
            }
            scanStart = literalStart + 1;
            minimumMatchStart = literalStart + literalLength;
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    private int findLiteral(Slice text, int offset, int length)
    {
        return literalLength == 1 ? literalSearch.findSingleByte(text, offset, length) : literalSearch.findContains(text, offset, length);
    }

    // A single letter, digit, or whitespace occurs too often for its scan to beat the DFA.
    private static boolean isCommonByte(byte value)
    {
        return Character.isLetterOrDigit(value) || Character.isWhitespace(value);
    }

    private static byte[] asciiLiteral(Regexp regexp)
    {
        while (regexp.op() == RegexpOp.CAPTURE) {
            regexp = regexp.child(0);
        }
        if ((regexp.parseFlags() & Regexp.FOLD_CASE) != 0) {
            return null;
        }
        int[] runes = switch (regexp.op()) {
            case LITERAL -> new int[] {regexp.rune()};
            case LITERAL_STRING -> regexp.runes();
            default -> null;
        };
        if (runes == null || runes.length == 0) {
            return null;
        }
        byte[] literal = new byte[runes.length];
        for (int index = 0; index < runes.length; index++) {
            if (runes[index] < 0 || runes[index] > 0x7F) {
                return null;
            }
            literal[index] = (byte) runes[index];
        }
        return literal;
    }

    // meta/reverse_inner.rs has_no_earlier_match, with the helpers of meta/prefix.rs.
    private static boolean hasNoEarlierMatch(Regexp prefix, byte[] literal, CaseFolding caseFolding)
    {
        if (!canConsumeEveryLiteralByte(prefix, literal)) {
            return true;
        }
        if (MatchLength.analyze(prefix, caseFolding).fixed() >= 0) {
            return true;
        }
        return hasDisjointClassSeparator(prefix, literal);
    }

    // An occurrence that crosses the end of the prefix must overlap the match's own occurrence, and
    // that overlap requires the prefix to consume every distinct byte of the literal.
    private static boolean canConsumeEveryLiteralByte(Regexp regexp, byte[] literal)
    {
        for (byte value : literal) {
            if (!canConsumeByte(regexp, value, 0)) {
                return false;
            }
        }
        return true;
    }

    // The literal is ASCII, so a non-ASCII rune, whose UTF-8 bytes are all non-ASCII, never
    // consumes one of its bytes. A folded literal is assumed to consume any byte; a class lists
    // every rune it matches.
    private static boolean canConsumeByte(Regexp regexp, byte value, int depth)
    {
        if (depth >= MAX_RECURSIVE_DEPTH) {
            return true;
        }
        return switch (regexp.op()) {
            case NO_MATCH, EMPTY_MATCH, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT, HAVE_MATCH -> false;
            case LITERAL -> (regexp.parseFlags() & Regexp.FOLD_CASE) != 0 || regexp.rune() == value;
            case LITERAL_STRING -> (regexp.parseFlags() & Regexp.FOLD_CASE) != 0 || containsRune(regexp.runes(), value);
            case CHAR_CLASS -> regexp.charClass().contains(value);
            case ANY_CHAR, ANY_BYTE -> true;
            case STAR, PLUS, QUEST, REPEAT, CAPTURE -> canConsumeByte(regexp.child(0), value, depth + 1);
            case CONCAT, ALTERNATE -> {
                for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
                    if (canConsumeByte(regexp.child(childIndex), value, depth + 1)) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }

    private static boolean containsRune(int[] runes, int rune)
    {
        for (int value : runes) {
            if (value == rune) {
                return true;
            }
        }
        return false;
    }

    // A required class at either end of the prefix, disjoint from the rest of the prefix and from
    // the literal, separates the match start from each candidate: a reverse search cannot slide it
    // across either one. The ends skip elements, such as assertions, that match only the empty
    // string.
    private static boolean hasDisjointClassSeparator(Regexp prefix, byte[] literal)
    {
        while (prefix.op() == RegexpOp.CAPTURE) {
            prefix = prefix.child(0);
        }
        if (prefix.op() != RegexpOp.CONCAT || prefix.childCount() < 2) {
            return false;
        }
        List<Regexp> children = prefix.children();
        int first = -1;
        int last = -1;
        for (int index = 0; index < children.size(); index++) {
            if (!matchesEmptyOnly(children.get(index), 0)) {
                if (first < 0) {
                    first = index;
                }
                last = index;
            }
        }
        if (first < 0) {
            return false;
        }
        return hasDisjointClassSeparatorAt(children, last, literal) || (first != last && hasDisjointClassSeparatorAt(children, first, literal));
    }

    private static boolean hasDisjointClassSeparatorAt(List<Regexp> children, int separatorIndex, byte[] literal)
    {
        Regexp separator = requiredClass(children.get(separatorIndex));
        if (separator == null) {
            return false;
        }
        for (byte value : literal) {
            if (separator.charClass().contains(value)) {
                return false;
            }
        }
        for (int index = 0; index < children.size(); index++) {
            if (index != separatorIndex && !isDisjointFromClass(children.get(index), separator, 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesEmptyOnly(Regexp regexp, int depth)
    {
        if (depth >= MAX_RECURSIVE_DEPTH) {
            return false;
        }
        return switch (regexp.op()) {
            case EMPTY_MATCH, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT -> true;
            case CAPTURE -> matchesEmptyOnly(regexp.child(0), depth + 1);
            case CONCAT -> {
                for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
                    if (!matchesEmptyOnly(regexp.child(childIndex), depth + 1)) {
                        yield false;
                    }
                }
                yield true;
            }
            default -> false;
        };
    }

    private static Regexp requiredClass(Regexp regexp)
    {
        while (regexp.op() == RegexpOp.CAPTURE) {
            regexp = regexp.child(0);
        }
        if (regexp.op() == RegexpOp.PLUS || (regexp.op() == RegexpOp.REPEAT && regexp.min() > 0)) {
            regexp = regexp.child(0);
            while (regexp.op() == RegexpOp.CAPTURE) {
                regexp = regexp.child(0);
            }
        }
        return regexp.op() == RegexpOp.CHAR_CLASS ? regexp : null;
    }

    // Runes compare directly only within one encoding. A Latin-1 rune at or above 0x80 is a
    // single byte that may sit inside the UTF-8 encoding of any non-ASCII rune, as the Rust bytes
    // API's (?-u:[\xC3]) does inside é, so across encodings any two non-ASCII runes are
    // conservatively treated as overlapping.
    private static boolean isDisjointFromClass(Regexp regexp, Regexp separator, int depth)
    {
        if (depth >= MAX_RECURSIVE_DEPTH) {
            return false;
        }
        CharClass separatorClass = separator.charClass();
        boolean nonAsciiOverlaps = ((regexp.parseFlags() ^ separator.parseFlags()) & Regexp.LATIN1) != 0 && hasNonAscii(separatorClass);
        return switch (regexp.op()) {
            case NO_MATCH, EMPTY_MATCH, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT, HAVE_MATCH -> true;
            case LITERAL -> (regexp.parseFlags() & Regexp.FOLD_CASE) == 0 && !(nonAsciiOverlaps && regexp.rune() >= 0x80) && !separatorClass.contains(regexp.rune());
            case LITERAL_STRING -> (regexp.parseFlags() & Regexp.FOLD_CASE) == 0 &&
                    !(nonAsciiOverlaps && hasNonAscii(regexp.runes())) &&
                    noneContained(regexp.runes(), separatorClass);
            case CHAR_CLASS -> !(nonAsciiOverlaps && hasNonAscii(regexp.charClass())) && classesAreDisjoint(regexp.charClass(), separatorClass);
            case ANY_CHAR, ANY_BYTE -> false;
            case STAR, PLUS, QUEST, REPEAT, CAPTURE -> isDisjointFromClass(regexp.child(0), separator, depth + 1);
            case CONCAT, ALTERNATE -> {
                for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
                    if (!isDisjointFromClass(regexp.child(childIndex), separator, depth + 1)) {
                        yield false;
                    }
                }
                yield true;
            }
        };
    }

    private static boolean hasNonAscii(CharClass charClass)
    {
        return charClass.rangeCount() > 0 && charClass.range(charClass.rangeCount() - 1).high() >= 0x80;
    }

    private static boolean hasNonAscii(int[] runes)
    {
        for (int rune : runes) {
            if (rune >= 0x80) {
                return true;
            }
        }
        return false;
    }

    private static boolean noneContained(int[] runes, CharClass charClass)
    {
        for (int rune : runes) {
            if (charClass.contains(rune)) {
                return false;
            }
        }
        return true;
    }

    private static boolean classesAreDisjoint(CharClass left, CharClass right)
    {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.rangeCount() && rightIndex < right.rangeCount()) {
            RuneRange leftRange = left.range(leftIndex);
            RuneRange rightRange = right.range(rightIndex);
            if (leftRange.high() < rightRange.low()) {
                leftIndex++;
            }
            else if (rightRange.high() < leftRange.low()) {
                rightIndex++;
            }
            else {
                return false;
            }
        }
        return true;
    }
}
