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

/// Decides whether a pattern of the form `C+ L D+` has a match, such as `([^ @]+)@([^ @]+)`: a
/// class repeated one or more times, a literal, and a class repeated one or more times, with
/// captures looked through. Such a match exists exactly where some occurrence of the literal
/// has a character of the first class just before it and a character of the second just after it,
/// since one character of each class completes a match, so the matcher scans for the literal and
/// reads one byte on each side of every hit. Spans, captures, and leftmost-first or
/// leftmost-longest selection do not change whether a match exists, so the decision serves
/// full-Slice boolean find alone.
///
/// A class decides a neighbor byte alone when the byte is a whole character: any byte beside a
/// Latin-1 class, as in RE2's Latin-1 mode and Rust's byte mode with Unicode off, and an ASCII byte
/// beside a UTF-8 class, since an ASCII byte is a whole character and never part of another, even
/// in malformed input. Such a byte proves the side when the class holds it and rejects the hit when
/// it does not. A byte at or above 0x80 next to the literal in UTF-8 can end or begin a character
/// of a class with non-ASCII members, or be malformed, which only the engine reads correctly; the
/// first such hit that no neighbor rejects ends the scan and leaves the decision to the engine,
/// which reads the input from its start as it would without the matcher. A UTF-8 class with only
/// ASCII members rejects that byte, as its compiled program does.
final class LiteralNeighborMatcher
{
    static final int NO_MATCH = 0;
    static final int MATCH = 1;
    static final int UNDECIDED = 2;

    private static final int INSTANCE_SIZE = SizeOf.instanceSize(LiteralNeighborMatcher.class);
    private static final int NEIGHBOR_TABLE_SIZE = 256;

    // The two bits of a byte's state on each side: a member that proves the side, or a byte that
    // leaves it to the engine. A byte with neither bit set rejects the hit.
    private static final int PROVES = 1;
    private static final int UNDECIDES = 2;
    private static final int AFTER_SHIFT = 2;

    // The shortest input on which a one-byte literal's density is sampled, the length from which
    // the DFA samples its small start-byte sets.
    static final int MIN_DENSITY_SAMPLE_BYTES = Dfa.MIN_SMALL_BYTE_SET_PRODUCTIVITY_CHECK_BYTES;

    private final LiteralSearchKernel literalSearch;
    // The state of each byte before the literal in the low two bits and after it in the next two.
    private final byte[] neighbors;
    // A one-byte literal's byte, as the DFA's density sample reads a candidate set; null for a
    // longer literal.
    private final byte[] literalCandidates;

    private LiteralNeighborMatcher(LiteralSearchKernel literalSearch, byte[] neighbors, byte[] literalCandidates)
    {
        this.literalSearch = requireNonNull(literalSearch, "literalSearch is null");
        this.neighbors = requireNonNull(neighbors, "neighbors is null");
        this.literalCandidates = literalCandidates;
    }

    /// Returns the matcher of `regexp`, the normalized expression a pattern compiles, or `null`
    /// when it is not of the form `C+ L D+`, as described above. Each side must be a `+` repeat of
    /// a class, as the simplifier leaves `{1,}`, so that one character proves the side; the
    /// simplifier expands a counted repeat such as `{1,3}` into nested optional copies, which keep
    /// the engine. The class's ranges list every rune it matches, so a Java or Rust
    /// case-insensitive class, whose parser adds the case variants to its ranges and clears its
    /// fold flag, qualifies; a class or literal that keeps a fold flag, as RE2's case-insensitive
    /// ones do, keeps the engine. So do a literal of characters the encoding cannot hold in one
    /// byte each, and a literal longer than [InnerLiteralSearch#MAXIMUM_LITERAL_LENGTH] bytes: the
    /// scan restarts one byte past each hit, so a longer literal that overlaps itself would be
    /// compared again in full at each of its positions, as the inner literal search's limit
    /// records.
    static LiteralNeighborMatcher analyze(Regexp regexp)
    {
        int rootFlags = regexp.parseFlags();
        Regexp root = withoutCaptures(regexp);
        if (root.op() != RegexpOp.CONCAT || root.children().size() != 3) {
            return null;
        }
        List<Regexp> elements = root.children();
        boolean latin1 = (rootFlags & Regexp.LATIN1) != 0;
        byte[] literal = literalBytes(withoutCaptures(elements.get(1)), latin1);
        if (literal == null || literal.length > InnerLiteralSearch.MAXIMUM_LITERAL_LENGTH) {
            return null;
        }
        byte[] neighbors = new byte[NEIGHBOR_TABLE_SIZE];
        if (!addClass(neighbors, elements.get(0), latin1, 0) || !addClass(neighbors, elements.get(2), latin1, AFTER_SHIFT)) {
            return null;
        }
        byte[] literalCandidates = null;
        if (literal.length == 1) {
            literalCandidates = new byte[NEIGHBOR_TABLE_SIZE];
            literalCandidates[literal[0] & 0xFF] = 1;
        }
        return new LiteralNeighborMatcher(new LiteralSearchKernel(Slices.wrappedBuffer(literal)), neighbors, literalCandidates);
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + literalSearch.estimatedRetainedSize() + SizeOf.sizeOf(neighbors) + (literalCandidates == null ? 0 : SizeOf.sizeOf(literalCandidates));
    }

    /// Scans the hits of the literal in order. It skips each hit that lies at an edge of `input` or
    /// has a rejecting byte beside it. At the first other hit it returns [#MATCH] when both
    /// neighbors prove membership and otherwise [#UNDECIDED], for the engine to decide from the
    /// start of `input`, so the hits after an undecided one cost nothing beyond the engine's own
    /// search. It returns [#NO_MATCH] when the scan ends.
    ///
    /// A one-byte literal on an input of at least [#MIN_DENSITY_SAMPLE_BYTES] is first sampled
    /// as the DFA samples its small start-byte sets, and the matcher returns [#UNDECIDED] without
    /// scanning when the sample finds the byte dense: a scan that restarts at each hit of a
    /// frequent byte, such as a space in prose, costs more than the engine's single pass, as a
    /// restarting start-byte scan costs more than stepping the DFA.
    int find(Slice input)
    {
        byte[] data = input.byteArray();
        int dataOffset = input.byteArrayOffset();
        int literalLength = literalSearch.literalLength();
        if (literalLength == 1 && input.length() >= MIN_DENSITY_SAMPLE_BYTES && isDense(data, dataOffset, dataOffset + input.length())) {
            return UNDECIDED;
        }
        // A hit at the first byte has no byte before it, and one ending at the last byte none after
        // it, so the scan starts at the second byte and stops one byte short of the end.
        int scanEnd = input.length() - 1;
        int position = 1;
        while (scanEnd - position >= literalLength) {
            int hit = literalLength == 1 ? literalSearch.findSingleByte(input, position, scanEnd - position) : literalSearch.findContains(input, position, scanEnd - position);
            if (hit < 0) {
                return NO_MATCH;
            }
            int before = neighbors[data[dataOffset + hit - 1] & 0xFF] & (PROVES | UNDECIDES);
            int after = (neighbors[data[dataOffset + hit + literalLength] & 0xFF] >>> AFTER_SHIFT) & (PROVES | UNDECIDES);
            if (before != 0 && after != 0) {
                return before == PROVES && after == PROVES ? MATCH : UNDECIDED;
            }
            position = hit + 1;
        }
        return NO_MATCH;
    }

    // Whether the DFA's small byte-set sample finds the one-byte literal too dense for a scan that
    // restarts at each hit, as it does for the DFA's own start-byte scan.
    private boolean isDense(byte[] data, int start, int end)
    {
        return !Dfa.isByteCandidateScanProductive(literalCandidates, null, null, data, start, end, MIN_DENSITY_SAMPLE_BYTES, Dfa.SMALL_BYTE_SET_SCAN_SAMPLE_CANDIDATE_LIMIT);
    }

    private static Regexp withoutCaptures(Regexp regexp)
    {
        while (regexp.op() == RegexpOp.CAPTURE) {
            regexp = regexp.child(0);
        }
        return regexp;
    }

    // The bytes of a case-sensitive literal whose every character is one byte in the encoding, or
    // null.
    private static byte[] literalBytes(Regexp element, boolean latin1)
    {
        if ((element.parseFlags() & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) != 0) {
            return null;
        }
        int[] runes = switch (element.op()) {
            case LITERAL -> new int[] {element.rune()};
            case LITERAL_STRING -> element.runes();
            default -> null;
        };
        if (runes == null) {
            return null;
        }
        byte[] bytes = new byte[runes.length];
        for (int index = 0; index < runes.length; index++) {
            int rune = runes[index];
            if (rune < 0 || rune > (latin1 ? 0xFF : 0x7F)) {
                return null;
            }
            bytes[index] = (byte) rune;
        }
        return bytes;
    }

    // Records the neighbor states of a class without a fold flag repeated by `+`, at `shift`, or
    // returns false for any other element. A class's ranges list every rune it matches, whatever
    // CharClass.foldsAscii tells the compiler.
    private static boolean addClass(byte[] neighbors, Regexp element, boolean rootLatin1, int shift)
    {
        element = withoutCaptures(element);
        if (element.op() != RegexpOp.PLUS) {
            return false;
        }
        Regexp characterClass = withoutCaptures(element.child(0));
        if (characterClass.op() != RegexpOp.CHAR_CLASS || (characterClass.parseFlags() & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) != 0) {
            return false;
        }
        // As the compiler reads it, a class matches bytes when the program is Latin-1 or the node
        // carries LATIN1.
        boolean latin1 = rootLatin1 || (characterClass.parseFlags() & Regexp.LATIN1) != 0;
        int byteLimit = latin1 ? 0xFF : 0x7F;
        boolean nonAscii = false;
        CharClass members = characterClass.charClass();
        for (int rangeIndex = 0; rangeIndex < members.rangeCount(); rangeIndex++) {
            RuneRange range = members.range(rangeIndex);
            for (int rune = Math.max(range.low(), 0); rune <= Math.min(range.high(), byteLimit); rune++) {
                neighbors[rune] |= (byte) (PROVES << shift);
            }
            nonAscii |= range.high() > 0x7F;
        }
        if (!latin1 && nonAscii) {
            for (int value = 0x80; value < NEIGHBOR_TABLE_SIZE; value++) {
                neighbors[value] |= (byte) (UNDECIDES << shift);
            }
        }
        return true;
    }
}
