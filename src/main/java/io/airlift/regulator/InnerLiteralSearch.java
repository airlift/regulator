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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Objects.requireNonNull;

/// Finds the leftmost-first match of a pattern `P L S`, whose top-level concatenation holds a
/// literal `L` after its first element, by searching for `L` instead of running the DFA over every
/// byte. `L` may also be a literal set, an alternation of literals, where an occurrence of any
/// member is an occurrence of `L`. This is the Rust crate's reverse inner strategy, which also
/// covers its reverse suffix strategy (regex-automata 0.4.18 `meta/strategy.rs` and
/// `meta/reverse_inner.rs`). For each occurrence of `L`, a reverse DFA of `P` anchored at the
/// occurrence finds the earliest start, and the forward DFA of the whole pattern anchored at that
/// start finds the end.
///
/// The first confirmed candidate is the leftmost-first match only when no earlier match can span
/// a later occurrence of `L`. [#analyze] accepts a pattern only when one of the crate's proofs
/// holds (`meta/reverse_inner.rs` `has_no_earlier_match`): `P` cannot consume every byte of a
/// single literal `L`, `P` has a fixed length, or the first or last element of `P`, skipping
/// elements such as assertions that match only the empty string, is a required class disjoint
/// from the rest of `P` and from every byte of `L`.
///
/// As in the crate, a search reports [#FALLBACK], for its caller to run the ordinary engine, when
/// the reverse search would read back past the previous candidate, or a candidate lies in text the
/// previous anchored confirmation already read, which keeps the work linear, or when a DFA fails.
/// It also falls back when its first candidate lies fewer than [#MINIMUM_SKIPPED_BYTES] past the
/// search start. The prefix lies before the literal, so the search could skip no more than that,
/// which cannot repay the reverse search and the anchored confirmation. In a window shorter than
/// that, the search is therefore only a check for the literal: a window without it has no match,
/// as for the many short lines of a line-by-line search, and any other runs the ordinary engine.
///
/// A case-insensitive ASCII literal is searched by its exact head, and each hit is checked for the
/// folded rest.
final class InnerLiteralSearch
{
    static final long FALLBACK = Dfa.CANDIDATE_SEARCH_FALLBACK;
    // A whole-text count of a shorter text skips the search. On such a text the search could
    // only check for the literal, which the count gains little from.
    static final int MINIMUM_TEXT_BYTES = 64;
    // A first candidate closer than this to the search start leaves too few bytes to skip.
    static final int MINIMUM_SKIPPED_BYTES = 64;

    // The safety proofs recurse over the prefix. Past this depth each one stops with its
    // conservative answer, which keeps the proofs stack safe, as MatchLength bounds its own
    // recursion. Another proof, such as the fixed-length check, may still admit the search.
    private static final int MAX_RECURSIVE_DEPTH = 256;
    // Each overlapping hit of a longer literal re-reads the whole literal.
    private static final int MAXIMUM_LITERAL_LENGTH = 64;
    // The set filter tests three positions of every member and holds at most eight members. The
    // crate treats its Teddy prefilter as fast only when every literal has at least three bytes.
    private static final int MINIMUM_SET_MEMBER_LENGTH = 3;
    private static final int MAXIMUM_SET_MEMBERS = 8;

    private static final int INSTANCE_SIZE = SizeOf.instanceSize(InnerLiteralSearch.class);

    // A single literal has its search kernel; a set has its members, in alternation order, and
    // their filter. A literal with ASCII case-insensitive bytes, such as `# noqa` in
    // `\s*# (?i:noqa)`, has the kernel of its exact head, `# `, and the whole literal with its
    // case-insensitive letters lowered, its exact bytes unchanged, and a mask of 0x20 at each
    // letter that matches either case.
    private final LiteralSearchKernel literalSearch;
    private final byte[] foldedBytes;
    private final byte[] foldedMasks;
    private final byte[][] literalSet;
    private final StartLiteralFilter literalSetFilter;
    private final int minimumLiteralLength;
    private final int maximumLiteralLength;
    private final Prog reversePrefixProgram;
    private final Prog forwardProgram;

    private InnerLiteralSearch(LiteralSearchKernel literalSearch, byte[] foldedBytes, byte[] foldedMasks, byte[][] literalSet, StartLiteralFilter literalSetFilter, Prog reversePrefixProgram, Prog forwardProgram)
    {
        this.literalSearch = literalSearch;
        this.foldedBytes = foldedBytes;
        this.foldedMasks = foldedMasks;
        this.literalSet = literalSet;
        this.literalSetFilter = literalSetFilter;
        if (foldedBytes != null) {
            this.minimumLiteralLength = foldedBytes.length;
            this.maximumLiteralLength = foldedBytes.length;
        }
        else if (literalSearch != null) {
            this.minimumLiteralLength = literalSearch.literalLength();
            this.maximumLiteralLength = literalSearch.literalLength();
        }
        else {
            requireNonNull(literalSetFilter, "literalSetFilter is null");
            int minimum = Integer.MAX_VALUE;
            int maximum = 0;
            for (byte[] literal : literalSet) {
                minimum = Math.min(minimum, literal.length);
                maximum = Math.max(maximum, literal.length);
            }
            this.minimumLiteralLength = minimum;
            this.maximumLiteralLength = maximum;
        }
        this.reversePrefixProgram = requireNonNull(reversePrefixProgram, "reversePrefixProgram is null");
        this.forwardProgram = requireNonNull(forwardProgram, "forwardProgram is null");
    }

    /// Returns the search for the normalized `regexp`, compiled into `forwardProgram`, or null when
    /// it has no usable inner literal, the safety proofs fail, or its reverse prefix DFA could not
    /// initialize. The reverse prefix program gets at most `prefixMemory` bytes, and the whole
    /// search, as [#estimatedRetainedSize] reports it, retains at most `retainedMemory`.
    static InnerLiteralSearch analyze(Regexp regexp, Prog forwardProgram, long prefixMemory, long retainedMemory, Compiler.Dialect dialect, CaseFolding caseFolding)
    {
        if (forwardProgram.anchorStart() || forwardProgram.anchorEnd() || forwardProgram.canPrefixAccel() || forwardProgram.hasDfaUnsupportedAssertions()) {
            return null;
        }
        InnerLiteral innerLiteral = findInnerLiteral(regexp, caseFolding);
        if (innerLiteral == null) {
            return null;
        }
        byte[] literal = innerLiteral.literal();
        StartLiteralFilter literalSetFilter = innerLiteral.literalSetFilter();
        LiteralSearchKernel literalSearch = literal == null ? null : new LiteralSearchKernel(Slices.wrappedBuffer(literal));
        byte[][] literalSet = literal == null ? innerLiteral.literals() : null;
        FoldedLiteral foldedLiteral = innerLiteral.foldedLiteral();
        byte[] foldedBytes = foldedLiteral == null ? null : foldedLiteral.bytes();
        byte[] foldedMasks = foldedLiteral == null ? null : foldedLiteral.masks();
        long memory = Math.min(prefixMemory, retainedMemory - INSTANCE_SIZE - literalRetainedSize(literalSearch, foldedBytes, foldedMasks, literalSet, literalSetFilter));
        if (memory <= 0) {
            // The compiler reads a budget of zero or less as no limit.
            return null;
        }
        Prog reversePrefixProgram;
        try {
            reversePrefixProgram = Compiler.compileNormalized(innerLiteral.prefix(), true, memory, dialect, caseFolding);
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
        return new InnerLiteralSearch(literalSearch, foldedBytes, foldedMasks, literalSet, literalSetFilter, reversePrefixProgram, forwardProgram);
    }

    /// Returns the shortest literal length [#analyze] would search the normalized `regexp` for:
    /// the length of its literal, or of the shortest member of its literal set, as
    /// [#minimumLiteralLength] counts it, or zero when it has none, without a compiled program. It checks on the expression, as the compiler
    /// does, that no prefix is accelerated. It cannot see what [#analyze] reads from the program
    /// alone: an anchored or DFA-unsupported assertion, which the caller's expression must not
    /// hold, and whether the reverse prefix DFA fits its memory.
    static int predictMinimumLiteralLength(Regexp regexp, CaseFolding caseFolding)
    {
        if (regexp.requiredPrefixForAccel(caseFolding) != null) {
            return 0;
        }
        InnerLiteral innerLiteral = findInnerLiteral(regexp, caseFolding);
        if (innerLiteral == null) {
            return 0;
        }
        FoldedLiteral foldedLiteral = innerLiteral.foldedLiteral();
        if (foldedLiteral != null) {
            return foldedLiteral.bytes().length;
        }
        return Arrays.stream(innerLiteral.literals()).mapToInt(literal -> literal.length).min().orElseThrow();
    }

    // The inner literal of the normalized `regexp` and the prefix before it: a single literal, or
    // the members of a literal set with their filter.
    private static InnerLiteral findInnerLiteral(Regexp regexp, CaseFolding caseFolding)
    {
        while (regexp.op() == RegexpOp.CAPTURE) {
            regexp = regexp.child(0);
        }
        if (regexp.op() != RegexpOp.CONCAT) {
            return null;
        }
        List<Regexp> children = new ArrayList<>();
        addConcatenationElements(regexp, children, 0);
        // As in the crate, the first element is skipped, since a literal there would have given a
        // prefix acceleration, and the first literal or literal set found decides. A set the
        // filter cannot search is skipped, as the crate skips a prefilter that is not fast.
        for (int index = 1; index < children.size(); index++) {
            // Case-insensitive ASCII elements extend the literal, with the exact bytes before the
            // first case-insensitive letter as the literal the kernel searches for and the proofs
            // check: every occurrence of the whole literal starts an occurrence of that head.
            FoldedLiteral foldedLiteral = foldedLiteral(children, index);
            // A head of one common byte would end the search below, so the element is judged by its
            // own literal as before, and a case-insensitive one lets the loop try later elements.
            if (foldedLiteral != null && foldedLiteral.head().length == 1 && isCommonByte(foldedLiteral.head()[0])) {
                foldedLiteral = null;
            }
            byte[] literal = foldedLiteral != null ? foldedLiteral.head() : asciiLiteral(children.get(index));
            byte[][] literals;
            StartLiteralFilter literalSetFilter = null;
            if (literal != null) {
                if ((literal.length == 1 && isCommonByte(literal[0])) || literal.length > MAXIMUM_LITERAL_LENGTH) {
                    return null;
                }
                literals = new byte[][] {literal};
            }
            else {
                literals = asciiLiteralSet(children.get(index));
                literalSetFilter = literals == null ? null : buildLiteralSetFilter(literals);
                if (literalSetFilter == null) {
                    continue;
                }
            }
            Regexp prefix = index == 1 ? children.get(0) : Regexp.concat(regexp.parseFlags(), children.subList(0, index));
            if (!hasNoEarlierMatch(prefix, literals, caseFolding)) {
                return null;
            }
            return new InnerLiteral(prefix, literal, literals, literalSetFilter, foldedLiteral);
        }
        return null;
    }

    // `literal` is the single literal, or null for a set, whose filter is `literalSetFilter`;
    // `literals` holds the single literal or the set's members. A case-insensitive literal's
    // `literal` is its exact head, and `foldedLiteral` holds the whole literal, or is null.
    private record InnerLiteral(Regexp prefix, byte[] literal, byte[][] literals, StartLiteralFilter literalSetFilter, FoldedLiteral foldedLiteral) {}

    // As the crate's top_concat does, a capturing group in the top-level concatenation is looked
    // through, and a concatenation inside it joins the top level, so `(\s*)(# x)` has the inner
    // literal `# `. The search finds only the match span; the ordinary engine fills in the groups.
    // Any other concatenation stays one element: simplification already flattens groups that do
    // not capture, so the rest are the copies it makes of a counted repetition, which the crate
    // keeps whole. Past the depth limit a group stays one element too, which only hides
    // literals inside it. RequiredBytes finds its literal with these elements and the literal
    // helpers below, so a change to them changes which short windows it rejects too.
    static void addConcatenationElements(Regexp concatenation, List<Regexp> elements, int depth)
    {
        for (Regexp child : concatenation.children()) {
            Regexp element = child;
            while (element.op() == RegexpOp.CAPTURE) {
                element = element.child(0);
            }
            if (element != child && element.op() == RegexpOp.CONCAT && depth < MAX_RECURSIVE_DEPTH) {
                addConcatenationElements(element, elements, depth + 1);
            }
            else {
                elements.add(element);
            }
        }
    }

    /// The literal, or the members of the literal set in alternation order. A case-insensitive
    /// literal has its case-insensitive letters lowered and its exact bytes unchanged.
    byte[][] literals()
    {
        if (foldedBytes != null) {
            return new byte[][] {foldedBytes.clone()};
        }
        if (literalSearch != null) {
            return new byte[][] {literalSearch.literal()};
        }
        return Arrays.stream(literalSet).map(byte[]::clone).toArray(byte[][]::new);
    }

    /// The literal's length, or the length of the shortest member of the literal set. A
    /// case-insensitive literal counts its whole length, since each hit of its head is checked for
    /// the folded rest before any engine runs.
    int minimumLiteralLength()
    {
        return minimumLiteralLength;
    }

    /// The reversed program of the prefix before the literal, whose longest-match DFA finds a
    /// candidate's start.
    Prog reversePrefixProgramForDiagnostics()
    {
        return reversePrefixProgram;
    }

    /// The forward program, whose first-match DFA confirms a candidate from its start.
    Prog forwardProgramForDiagnostics()
    {
        return forwardProgram;
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + literalRetainedSize(literalSearch, foldedBytes, foldedMasks, literalSet, literalSetFilter) + Compiler.estimatedProgramMemory(reversePrefixProgram) + reversePrefixProgram.dfaMemory();
    }

    // The folded arrays are null unless the literal is case-insensitive.
    private static long literalRetainedSize(LiteralSearchKernel literalSearch, byte[] foldedBytes, byte[] foldedMasks, byte[][] literalSet, StartLiteralFilter literalSetFilter)
    {
        if (literalSearch != null) {
            return literalSearch.estimatedRetainedSize() + SizeOf.sizeOf(foldedBytes) + SizeOf.sizeOf(foldedMasks);
        }
        long size = literalSetFilter.estimatedRetainedSize() + SizeOf.sizeOfObjectArray(literalSet.length);
        for (byte[] literal : literalSet) {
            size += SizeOf.sizeOf(literal);
        }
        return size;
    }

    /// Whether a search of `[start, end)` of `text` falls back because its first candidate lies
    /// near the start. It reads at most `MINIMUM_SKIPPED_BYTES - 1` bytes plus the longest
    /// literal's length. Within that read a shorter member of a set can start past the near bytes,
    /// where the search does not fall back.
    boolean fallsBackNearStart(Slice text, int start, int end)
    {
        int length = Math.min(end - start, MINIMUM_SKIPPED_BYTES - 1 + maximumLiteralLength);
        int literalStart = findLiteral(text, start, length);
        return literalStart >= 0 && literalStart - start < MINIMUM_SKIPPED_BYTES;
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
        while (end - scanStart >= minimumLiteralLength) {
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
            long candidate = Dfa.searchReverseThenAnchoredWithStop(reversePrefixProgram, forwardProgram, text, contextStart, contextEnd, start, literalStart, end, maximumReverseBytes);
            if (candidate == Dfa.SEARCH_FAILED) {
                candidate = confirmCandidate(text, contextStart, contextEnd, start, literalStart, end, maximumReverseBytes);
            }
            if (candidate >= 0 || candidate == FALLBACK) {
                return candidate;
            }
            if (candidate != Dfa.SEARCH_NO_MATCH) {
                minimumLiteralStart = Dfa.anchoredStopPosition(candidate);
            }
            scanStart = literalStart + 1;
            minimumMatchStart = literalStart + matchedLength(text, literalStart, end);
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    // Confirms the candidate at `literalStart` with the two searches one at a time, for a
    // candidate the single pass gave up on. Returns as that pass does, with FALLBACK in place of
    // its failure.
    private long confirmCandidate(Slice text, int contextStart, int contextEnd, int start, int literalStart, int end, int maximumReverseBytes)
    {
        long reverse = Dfa.searchReverseCandidate(reversePrefixProgram, text, contextStart, contextEnd, start, literalStart, maximumReverseBytes);
        if (reverse == Dfa.CANDIDATE_SEARCH_FALLBACK || reverse == Dfa.SEARCH_FAILED) {
            return FALLBACK;
        }
        if (reverse < 0) {
            return Dfa.SEARCH_NO_MATCH;
        }
        int matchStart = start + (int) reverse;
        long forward = Dfa.searchAnchoredWithStop(forwardProgram, text, contextStart, contextEnd, matchStart, end);
        if (forward >= 0) {
            return ((long) matchStart << 32) | forward;
        }
        if (forward == Dfa.SEARCH_FAILED) {
            return FALLBACK;
        }
        return forward;
    }

    private int findLiteral(Slice text, int offset, int length)
    {
        if (literalSearch == null) {
            return findLiteralSetMember(text, offset, length);
        }
        if (foldedBytes != null) {
            return findFoldedLiteral(text, offset, length);
        }
        if (usesVectorSearch(length)) {
            return findLiteralVector(text, offset, length);
        }
        return minimumLiteralLength == 1 ? literalSearch.findSingleByte(text, offset, length) : literalSearch.findContains(text, offset, length);
    }

    /// Whether a scan of `length` bytes for a single literal takes the vector kernels, under the
    /// same cutoff as the prefix scans. A case-insensitive literal never does: its kernel holds
    /// only the exact head, so a vector scan would accept hits without the folded rest.
    boolean usesVectorSearch(int length)
    {
        return literalSearch != null && foldedBytes == null && VectorSupport.isAvailable() && length >= Prog.FUSED_PREFIX_VECTOR_MINIMUM_BYTES;
    }

    // Each hit of the exact head, with room for the whole literal, is checked for the folded rest,
    // and a false hit continues the scan past it.
    private int findFoldedLiteral(Slice text, int offset, int length)
    {
        int headLength = literalSearch.literalLength();
        int restLength = foldedBytes.length - headLength;
        byte[] data = text.byteArray();
        int dataOffset = text.byteArrayOffset();
        int position = offset;
        int end = offset + length;
        while (end - position >= foldedBytes.length) {
            int headSearchLength = end - position - restLength;
            int hit = headLength == 1 ? literalSearch.findSingleByte(text, position, headSearchLength) : literalSearch.findContains(text, position, headSearchLength);
            if (hit < 0) {
                return -1;
            }
            if (foldedRestMatches(data, dataOffset + hit, headLength)) {
                return hit;
            }
            position = hit + 1;
        }
        return -1;
    }

    private boolean foldedRestMatches(byte[] data, int literalStart, int headLength)
    {
        for (int index = headLength; index < foldedBytes.length; index++) {
            if ((data[literalStart + index] | foldedMasks[index]) != foldedBytes[index]) {
                return false;
            }
        }
        return true;
    }

    // A one-byte literal takes the byte-set scan, and a longer one the fused pair scan, which
    // finishes with KMP once its failed candidates pass the comparison budget.
    private int findLiteralVector(Slice text, int offset, int length)
    {
        byte[] data = text.byteArray();
        int dataOffset = text.byteArrayOffset();
        int start = dataOffset + offset;
        byte[] literal = literalSearch.literalBytes();
        int found;
        if (minimumLiteralLength == 1) {
            found = VectorByteSetScanner.find(data, start, length, literal[0], (byte) 0, (byte) 0, 1);
        }
        else {
            found = Prog.findLiteral(data, start, length, literal, literalSearch);
        }
        return found < 0 ? -1 : found - dataOffset;
    }

    // The filter accepts a superset of the positions where a member starts, so each hit is
    // checked for a member, and a false hit continues the scan past it. As for the DFA's start
    // literal filter, a short range skips the vector setup.
    private int findLiteralSetMember(Slice text, int offset, int length)
    {
        byte[] data = text.byteArray();
        int dataOffset = text.byteArrayOffset();
        int position = dataOffset + offset;
        int end = position + length;
        while (position < end) {
            int remaining = end - position;
            int hit = VectorSupport.isAvailable() && remaining >= Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES
                    ? VectorByteSetScanner.findLiteralSet(data, position, remaining, literalSetFilter)
                    : literalSetFilter.find(data, position, remaining);
            if (hit < 0) {
                return -1;
            }
            if (setMemberLength(data, hit, end) > 0) {
                return hit - dataOffset;
            }
            position = hit + 1;
        }
        return -1;
    }

    // The length of the literal found at `literalStart` in `[literalStart, end)`. For a set this
    // is the first member in alternation order, as the crate's Teddy reports the leftmost-first
    // literal.
    private int matchedLength(Slice text, int literalStart, int end)
    {
        if (literalSearch != null) {
            return minimumLiteralLength;
        }
        int dataOffset = text.byteArrayOffset();
        return setMemberLength(text.byteArray(), dataOffset + literalStart, dataOffset + end);
    }

    // The length of the first member, in alternation order, at `position` and ending by `end`, or
    // zero when none is.
    private int setMemberLength(byte[] data, int position, int end)
    {
        for (byte[] literal : literalSet) {
            if (literal.length <= end - position && Arrays.equals(data, position, position + literal.length, literal, 0, literal.length)) {
                return literal.length;
            }
        }
        return 0;
    }

    // A single letter, digit, or whitespace occurs too often for its scan to beat the DFA.
    private static boolean isCommonByte(byte value)
    {
        return Character.isLetterOrDigit(value) || Character.isWhitespace(value);
    }

    // An alternation of two to eight literals, each ASCII, not folded, and three to
    // MAXIMUM_LITERAL_LENGTH bytes long, as its members in alternation order.
    private static byte[][] asciiLiteralSet(Regexp regexp)
    {
        while (regexp.op() == RegexpOp.CAPTURE) {
            regexp = regexp.child(0);
        }
        if (regexp.op() != RegexpOp.ALTERNATE || regexp.childCount() > MAXIMUM_SET_MEMBERS) {
            return null;
        }
        byte[][] literals = new byte[regexp.childCount()][];
        for (int index = 0; index < literals.length; index++) {
            byte[] literal = asciiLiteral(regexp.child(index));
            if (literal == null || literal.length < MINIMUM_SET_MEMBER_LENGTH || literal.length > MAXIMUM_LITERAL_LENGTH) {
                return null;
            }
            literals[index] = literal;
        }
        return literals;
    }

    // Each member is one path of byte sets, in the format Prog#buildStartPositionByteSets returns:
    // the member's byte at each position it covers, and every byte past its end.
    private static StartLiteralFilter buildLiteralSetFilter(byte[][] literals)
    {
        long[][] paths = new long[literals.length][StartLiteralFilter.PATH_POSITIONS * 4];
        for (int index = 0; index < literals.length; index++) {
            byte[] literal = literals[index];
            long[] path = paths[index];
            int positions = Math.min(literal.length, StartLiteralFilter.PATH_POSITIONS);
            for (int position = 0; position < positions; position++) {
                int value = literal[position] & 0xFF;
                path[position * 4 + (value >>> 6)] |= 1L << value;
            }
            Arrays.fill(path, positions * 4, path.length, -1L);
        }
        return StartLiteralFilter.build(paths, ByteFrequencies.englishText());
    }

    // The literal of the run of elements starting at `index` that are ASCII literals, folded or
    // not, and classes of exactly the two cases of an ASCII letter, up to MAXIMUM_LITERAL_LENGTH
    // bytes; the run stops at the first other element. Null when the run holds no folded letter or
    // starts with no exact head. A folded letter byte matches as the compiler's folded byte range
    // does: lowered, it equals the lower-case letter, which `(byte | 0x20) == letter` tests. Any
    // other folded ASCII byte matches only itself.
    static FoldedLiteral foldedLiteral(List<Regexp> children, int index)
    {
        byte[] bytes = new byte[MAXIMUM_LITERAL_LENGTH];
        byte[] masks = new byte[MAXIMUM_LITERAL_LENGTH];
        int length = 0;
        for (int childIndex = index; childIndex < children.size(); childIndex++) {
            int newLength = appendFoldedElement(children.get(childIndex), bytes, masks, length);
            if (newLength < 0) {
                break;
            }
            length = newLength;
        }
        int headLength = 0;
        while (headLength < length && masks[headLength] == 0) {
            headLength++;
        }
        if (headLength == 0 || headLength == length) {
            return null;
        }
        return new FoldedLiteral(Arrays.copyOf(bytes, headLength), Arrays.copyOf(bytes, length), Arrays.copyOf(masks, length));
    }

    // Appends the element's bytes and masks after `length`, and returns the new length, or -1 when
    // the element is not one the folded literal can hold or would not fit.
    private static int appendFoldedElement(Regexp element, byte[] bytes, byte[] masks, int length)
    {
        int flags = element.parseFlags();
        if ((flags & Regexp.FULL_CASE_FOLD) != 0) {
            return -1;
        }
        boolean foldCase = (flags & Regexp.FOLD_CASE) != 0;
        int[] runes = switch (element.op()) {
            case LITERAL -> new int[] {element.rune()};
            case LITERAL_STRING -> element.runes();
            case CHAR_CLASS -> {
                int letter = asciiLetterCasePair(element.charClass());
                foldCase = true;
                yield letter < 0 ? null : new int[] {letter};
            }
            default -> null;
        };
        if (runes == null || runes.length == 0 || length + runes.length > MAXIMUM_LITERAL_LENGTH) {
            return -1;
        }
        for (int rune : runes) {
            if (rune < 0 || rune > 0x7F || (foldCase && 'A' <= rune && rune <= 'Z')) {
                // A folded upper-case rune matches no byte, as the compiled range lowers the byte.
                return -1;
            }
            bytes[length] = (byte) rune;
            masks[length] = foldCase && 'a' <= rune && rune <= 'z' ? (byte) 0x20 : 0;
            length++;
        }
        return length;
    }

    // The lower-case letter of a class that holds exactly an ASCII letter in both cases, or -1.
    private static int asciiLetterCasePair(CharClass charClass)
    {
        if (charClass.rangeCount() != 2) {
            return -1;
        }
        RuneRange upper = charClass.range(0);
        RuneRange lower = charClass.range(1);
        if (upper.low() != upper.high() || lower.low() != lower.high() || upper.low() < 'A' || upper.low() > 'Z' || lower.low() != upper.low() + ('a' - 'A')) {
            return -1;
        }
        return lower.low();
    }

    // A case-insensitive literal: its exact head, and its whole bytes and masks.
    record FoldedLiteral(byte[] head, byte[] bytes, byte[] masks) {}

    static byte[] asciiLiteral(Regexp regexp)
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
    private static boolean hasNoEarlierMatch(Regexp prefix, byte[][] literals, CaseFolding caseFolding)
    {
        if (literals.length == 1 && !canConsumeEveryLiteralByte(prefix, literals[0])) {
            return true;
        }
        if (MatchLength.analyze(prefix, caseFolding).fixed() >= 0) {
            return true;
        }
        return hasDisjointClassSeparator(prefix, literals);
    }

    // An occurrence that crosses the end of the prefix must overlap the match's own occurrence, and
    // that overlap requires the prefix to consume every distinct byte of the literal. With a set,
    // an occurrence of one member can cross into another member, so the crate uses this proof
    // only for a single literal.
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
    // every literal, separates the match start from each candidate: a reverse search cannot slide
    // it across either one. The ends skip elements, such as assertions, that match only the empty
    // string.
    private static boolean hasDisjointClassSeparator(Regexp prefix, byte[][] literals)
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
        return hasDisjointClassSeparatorAt(children, last, literals) || (first != last && hasDisjointClassSeparatorAt(children, first, literals));
    }

    private static boolean hasDisjointClassSeparatorAt(List<Regexp> children, int separatorIndex, byte[][] literals)
    {
        Regexp separator = requiredClass(children.get(separatorIndex));
        if (separator == null) {
            return false;
        }
        for (byte[] literal : literals) {
            for (byte value : literal) {
                if (separator.charClass().contains(value)) {
                    return false;
                }
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
