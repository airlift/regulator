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
/// byte. `L` may also be a literal set, an alternation of literals or the members of a class pair,
/// where an occurrence of any member is an occurrence of `L`. This is the Rust crate's reverse
/// inner strategy, which also covers its reverse suffix strategy (regex-automata 0.4.18
/// `meta/strategy.rs` and `meta/reverse_inner.rs`). For each occurrence of `L` that its neighboring
/// bytes do not rule out, a reverse DFA of `P` anchored at the occurrence finds the earliest start,
/// and the forward DFA of the whole pattern anchored at that start finds the end.
///
/// The first confirmed candidate is the leftmost-first match only when no earlier match can span
/// a later occurrence of `L`. [#analyze] accepts a literal or literal set only when one of the
/// crate's proofs holds (`meta/reverse_inner.rs` `has_no_earlier_match`): `P` cannot consume every
/// byte of a single literal `L`, `P` has a fixed length, or the first or last element of `P`,
/// skipping elements such as assertions that match only the empty string, is a required class
/// disjoint from the rest of `P` and from every byte of `L`. A class pair may instead pass a proof
/// of its own, described below.
///
/// As in the crate, a search reports [#FALLBACK], for its caller to run the ordinary engine, when
/// the reverse search would read back past the previous candidate, or a candidate lies in text the
/// previous anchored confirmation already read, which keeps the work linear, or when a DFA fails.
/// It also falls back when its first candidate lies fewer than [#MINIMUM_SKIPPED_BYTES] past the
/// search start. The prefix lies before the literal, so the search could skip no more than that,
/// which cannot repay the reverse search and the anchored confirmation. In a window shorter than
/// that, the search is therefore only a check for a candidate: a window without a hit that passes
/// the byte gates has no match, as for the many short lines of a line-by-line search, and any
/// other runs the ordinary engine.
///
/// A case-insensitive ASCII literal is searched by its exact head, and each hit is checked for the
/// folded rest. Two adjacent elements that each match one of a few ASCII bytes, such as the
/// `[?!.]["']` that ends `["'][^"']{0,30}[?!.]["']`, are searched as the set of their two-byte
/// members when English text rarely holds them, under a proof of its own.
///
/// A hit is skipped before either DFA runs when the byte before it is not one the reverse prefix
/// program can consume first, as a slash after a letter is not for a date. A hit of a literal, or
/// a set whose members share one length, is skipped too when the element after it is a single
/// ASCII byte class, alone or repeated at least once, and the byte after it is outside that class,
/// as the `ing` of `kingdom` is for `\s[a-zA-Z]{0,12}ing\s`.
/// A skipped hit is no candidate, so it never makes the search fall back.
final class InnerLiteralSearch
{
    static final long FALLBACK = Dfa.CANDIDATE_SEARCH_FALLBACK;
    // A whole-text count of a shorter text skips the search. On such a text the search could
    // only check for a hit that passes the byte gates, which the count gains little from.
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
    // Each side of a class pair is compared against up to this many bytes.
    private static final int MAXIMUM_CLASS_PAIR_SIDE_BYTES = 3;
    // A class pair is searched only when it is estimated below once in 500 bytes, from English
    // text byte frequencies, whatever frequencies the pattern was compiled with, as if each byte
    // were independent of its neighbor. Each hit may pay a reverse search and an anchored
    // confirmation, so a commoner pair could stop the scan about as often as the start-byte scan
    // of the ordinary engine it replaces, and add the reverse search to its stops.
    private static final double MAXIMUM_CLASS_PAIR_PROBABILITY = 0.002;

    private static final int INSTANCE_SIZE = SizeOf.instanceSize(InnerLiteralSearch.class);

    // A single literal has its search kernel; a set has its members, in alternation order, or for
    // a class pair in class order, and their filter, or for a class pair its scan bytes. A literal
    // with ASCII case-insensitive bytes, such as `# noqa` in `\s*# (?i:noqa)`, has the kernel of
    // its exact head, `# `, and the whole literal with its case-insensitive letters lowered, its
    // exact bytes unchanged, and a mask of 0x20 at each letter that matches either case.
    private final LiteralSearchKernel literalSearch;
    private final byte[] foldedBytes;
    private final byte[] foldedMasks;
    private final byte[][] literalSet;
    private final StartLiteralFilter literalSetFilter;
    // A class pair set has no filter, but each side's bytes, the last repeated to three: the
    // first side's in slots 0 to 2 and the second side's in slots 3 to 5.
    private final byte[] classPairBytes;
    private final int minimumLiteralLength;
    private final int maximumLiteralLength;
    // The bytes allowed before the literal, as the bits of four words, and the ASCII bytes allowed
    // after it, as the bits of two, low bytes first. A gate whose words are all zero is no gate.
    // Held in the instance, the gates take no memory beyond the instance size the search is
    // charged for.
    private final long prefixGate0;
    private final long prefixGate1;
    private final long prefixGate2;
    private final long prefixGate3;
    private final long suffixGate0;
    private final long suffixGate1;
    private final boolean hasByteGates;
    private final Prog reversePrefixProgram;
    private final Prog forwardProgram;

    private InnerLiteralSearch(LiteralSearchKernel literalSearch, byte[] foldedBytes, byte[] foldedMasks, byte[][] literalSet, StartLiteralFilter literalSetFilter, byte[] classPairBytes, ByteGate prefixGate, ByteGate suffixGate, Prog reversePrefixProgram, Prog forwardProgram)
    {
        this.literalSearch = literalSearch;
        this.foldedBytes = foldedBytes;
        this.foldedMasks = foldedMasks;
        this.literalSet = literalSet;
        this.literalSetFilter = literalSetFilter;
        this.classPairBytes = classPairBytes;
        if (foldedBytes != null) {
            this.minimumLiteralLength = foldedBytes.length;
            this.maximumLiteralLength = foldedBytes.length;
        }
        else if (literalSearch != null) {
            this.minimumLiteralLength = literalSearch.literalLength();
            this.maximumLiteralLength = literalSearch.literalLength();
        }
        else {
            if (classPairBytes == null) {
                requireNonNull(literalSetFilter, "literalSetFilter and classPairBytes are null");
            }
            int minimum = Integer.MAX_VALUE;
            int maximum = 0;
            for (byte[] literal : literalSet) {
                minimum = Math.min(minimum, literal.length);
                maximum = Math.max(maximum, literal.length);
            }
            this.minimumLiteralLength = minimum;
            this.maximumLiteralLength = maximum;
        }
        this.prefixGate0 = prefixGate.word0();
        this.prefixGate1 = prefixGate.word1();
        this.prefixGate2 = prefixGate.word2();
        this.prefixGate3 = prefixGate.word3();
        this.suffixGate0 = suffixGate.word0();
        this.suffixGate1 = suffixGate.word1();
        this.hasByteGates = (prefixGate0 | prefixGate1 | prefixGate2 | prefixGate3 | suffixGate0 | suffixGate1) != 0;
        this.reversePrefixProgram = requireNonNull(reversePrefixProgram, "reversePrefixProgram is null");
        this.forwardProgram = requireNonNull(forwardProgram, "forwardProgram is null");
    }

    /// Returns the search for the normalized `regexp`, compiled into `forwardProgram`, or null when
    /// it has no usable inner literal, the safety proofs fail, or its reverse prefix DFA could not
    /// initialize. The reverse prefix program gets at most `prefixMemory` bytes, and the whole
    /// search, as [#estimatedRetainedSize] reports it, retains at most `retainedMemory`.
    static InnerLiteralSearch analyze(Regexp regexp, Prog forwardProgram, long prefixMemory, long retainedMemory, Compiler.Dialect dialect, CaseFolding caseFolding)
    {
        if (forwardProgram.start() == 0 || forwardProgram.anchorStart() || forwardProgram.anchorEnd() || forwardProgram.canPrefixAccel() || forwardProgram.hasDfaUnsupportedAssertions()) {
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
        byte[] classPairBytes = innerLiteral.classPairBytes();
        long memory = Math.min(prefixMemory, retainedMemory - INSTANCE_SIZE - literalRetainedSize(literalSearch, foldedBytes, foldedMasks, literalSet, literalSetFilter, classPairBytes));
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
        // The byte before the literal ends the prefix, so the reverse prefix program names the
        // bytes it can be, whatever the prefix's shape.
        ByteGate prefixGate = ByteGate.of(reversePrefixProgram.boundaryBytes()).unlessHolding(literalEdgeBytes(innerLiteral.literals(), foldedLiteral, false));
        return new InnerLiteralSearch(literalSearch, foldedBytes, foldedMasks, literalSet, literalSetFilter, classPairBytes, prefixGate, innerLiteral.suffixGate(), reversePrefixProgram, forwardProgram);
    }

    /// Returns the shortest literal length [#analyze] would search the normalized `regexp` for:
    /// the length of its literal, or of the shortest member of its literal set, as
    /// [#minimumLiteralLength] counts it, or zero when it has none, without a compiled program. It
    /// also returns zero when the expression has a prefix or a folded run the compiler could scan
    /// for, a conservative exclusion: the compiler leaves out a leading run where vectors hold 64
    /// bytes, which this does not check. It cannot see what [#analyze] reads from the program
    /// alone: an anchored or DFA-unsupported assertion, which the caller's expression must not
    /// hold, and whether the reverse prefix DFA fits its memory.
    static int predictMinimumLiteralLength(Regexp regexp, CaseFolding caseFolding)
    {
        if (regexp.requiredPrefixForAccel(caseFolding) != null || !regexp.foldedRunsForAccel(caseFolding).isEmpty()) {
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

    // The inner literal of the normalized `regexp` and the prefix before it: a single literal, the
    // members of a literal set with their filter, or the members of a class pair with its sides'
    // bytes, and the byte gate after it.
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
        // filter cannot search is skipped, as the crate skips a prefilter that is not fast, and so
        // is a class pair no proof admits.
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
            ClassPair classPair = null;
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
                    classPair = asciiClassPair(children, index);
                    if (classPair == null) {
                        continue;
                    }
                    literals = classPair.members();
                }
            }
            Regexp prefix = index == 1 ? children.get(0) : Regexp.concat(regexp.parseFlags(), children.subList(0, index));
            boolean admitted = hasNoEarlierMatch(prefix, literals, caseFolding) || (classPair != null && hasNoEarlierMatchAcrossLastByte(prefix, literals));
            if (!admitted) {
                if (classPair != null) {
                    continue;
                }
                return null;
            }
            // The byte after the literal is known only when every member has one length.
            ByteGate suffixGate = ByteGate.NONE;
            if (hasOneLength(literals)) {
                int literalElements = foldedLiteral != null ? foldedLiteral.elements() : classPair != null ? 2 : 1;
                suffixGate = suffixGate(children, index + literalElements).unlessHolding(literalEdgeBytes(literals, foldedLiteral, true));
            }
            return new InnerLiteral(prefix, literal, literals, literalSetFilter, foldedLiteral, classPair == null ? null : classPair.scanBytes(), suffixGate);
        }
        return null;
    }

    // `literal` is the single literal, or null for a set, whose filter is `literalSetFilter`, or
    // for a class pair, whose sides' bytes are `classPairBytes`; `literals` holds the single
    // literal or the members. A case-insensitive literal's `literal` is its exact head, and
    // `foldedLiteral` holds the whole literal, or is null. `suffixGate` holds the bytes the byte
    // after a hit may be, or is `ByteGate.NONE` for no gate.
    private record InnerLiteral(Regexp prefix, byte[] literal, byte[][] literals, StartLiteralFilter literalSetFilter, FoldedLiteral foldedLiteral, byte[] classPairBytes, ByteGate suffixGate) {}

    // As the crate's top_concat does, a capturing group in the top-level concatenation is looked
    // through, and a concatenation inside it joins the top level, so `(\s*)(# x)` has the inner
    // literal `# `. The search finds only the match span; the ordinary engine fills in the groups.
    // Any other concatenation stays one element: simplification already flattens groups that do
    // not capture, so the rest are the copies it makes of a counted repetition, which the crate
    // keeps whole. Past the depth limit a group stays one element too, which only hides
    // literals inside it. RequiredBytes finds its literal with these elements and the literal
    // helpers below, so a change to them changes which windows, short or long, it rejects too.
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

    /// The literal, or the members of the literal set in alternation order, or for a class pair in
    /// class order. A case-insensitive
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

    /// The bytes the byte before the literal and the byte after it must be one of, each as a
    /// string in byte order, empty for no gate.
    List<String> byteGatesForDiagnostics()
    {
        return List.of(gateBytes(prefixGate0, prefixGate1, prefixGate2, prefixGate3), gateBytes(suffixGate0, suffixGate1, 0, 0));
    }

    // Each byte as the char of its value, so a byte past ASCII shows as its Latin-1 char.
    private static String gateBytes(long word0, long word1, long word2, long word3)
    {
        StringBuilder bytes = new StringBuilder();
        for (int value = 0; value < 0x100; value++) {
            if (inByteGate((byte) value, word0, word1, word2, word3)) {
                bytes.append((char) value);
            }
        }
        return bytes.toString();
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
        return INSTANCE_SIZE + literalRetainedSize(literalSearch, foldedBytes, foldedMasks, literalSet, literalSetFilter, classPairBytes) + Compiler.estimatedProgramMemory(reversePrefixProgram) + reversePrefixProgram.dfaMemory();
    }

    // The folded arrays are null unless the literal is case-insensitive, and a set has either its
    // filter or its class pair bytes.
    private static long literalRetainedSize(LiteralSearchKernel literalSearch, byte[] foldedBytes, byte[] foldedMasks, byte[][] literalSet, StartLiteralFilter literalSetFilter, byte[] classPairBytes)
    {
        if (literalSearch != null) {
            return literalSearch.estimatedRetainedSize() + SizeOf.sizeOf(foldedBytes) + SizeOf.sizeOf(foldedMasks);
        }
        long filterSize = classPairBytes != null ? SizeOf.sizeOf(classPairBytes) : literalSetFilter.estimatedRetainedSize();
        long size = filterSize + SizeOf.sizeOfObjectArray(literalSet.length);
        for (byte[] literal : literalSet) {
            size += SizeOf.sizeOf(literal);
        }
        return size;
    }

    /// Whether a search of `[start, end)` of `text` falls back because its first candidate lies
    /// near the start. It reads at most `MINIMUM_SKIPPED_BYTES - 1` bytes plus the longest
    /// literal's length, and one byte past that for the gate after a hit. Within that
    /// read a shorter member of a set can start past the near bytes, where the search does not
    /// fall back. As in the search, a hit that fails the byte gates is no candidate, so the check
    /// looks past it.
    boolean fallsBackNearStart(Slice text, int start, int end)
    {
        int length = Math.min(end - start, MINIMUM_SKIPPED_BYTES - 1 + maximumLiteralLength);
        int literalStart = findLiteral(text, start, length);
        while (hasByteGates && literalStart >= 0 && !passesByteGates(text, start, literalStart, end)) {
            int next = literalStart + 1;
            literalStart = start + length - next < minimumLiteralLength ? -1 : findLiteral(text, next, start + length - next);
        }
        return literalStart >= 0 && literalStart - start < MINIMUM_SKIPPED_BYTES;
    }

    /// Searches `[start, end)` of `text` with assertions seeing `[contextStart, contextEnd)`, and
    /// returns the match's Slice offsets packed as `start << 32 | end`, [Dfa#SEARCH_NO_MATCH], or
    /// [#FALLBACK].
    long search(Slice text, int contextStart, int contextEnd, int start, int end)
    {
        // Patterns without a gate keep this loop free of the gate check, and gated ones run their
        // own copy: with the check in one shared loop, patterns without a gate measured up to
        // about 4% slower, on lh3lh3 uri (Graviton) and lh3lh3 email (Intel) before their
        // prefixes were gated; see REGULATOR_DECISIONS.md, Inner Literal Byte Gates.
        if (hasByteGates) {
            return searchGated(text, contextStart, contextEnd, start, end);
        }
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

    // The search of a pattern with a byte gate. A window without a hit, as most lines of a grep
    // are, returns here without entering the loop; see REGULATOR_DECISIONS.md, Inner Literal Byte
    // Gates.
    private long searchGated(Slice text, int contextStart, int contextEnd, int start, int end)
    {
        int literalStart = nextLiteral(text, start, end);
        if (literalStart < 0) {
            return Dfa.SEARCH_NO_MATCH;
        }
        return searchGatedFrom(text, contextStart, contextEnd, start, end, literalStart);
    }

    // The search loop of a pattern with a byte gate, from the hit at `firstLiteralStart`: as in
    // search, with each hit first checked against the gates, so the fallbacks see only the hits
    // that pass.
    private long searchGatedFrom(Slice text, int contextStart, int contextEnd, int start, int end, int firstLiteralStart)
    {
        // The reverse search may read back to the end of the previous candidate, and a candidate
        // may not start inside the text the previous confirmation read.
        int minimumMatchStart = start;
        int minimumLiteralStart = start;
        boolean firstCandidate = true;
        for (int literalStart = firstLiteralStart; literalStart >= 0; literalStart = nextLiteral(text, literalStart + 1, end)) {
            if (!passesByteGates(text, start, literalStart, end)) {
                // No match uses the literal here, which is all a rejection by the DFAs would tell
                // the loop, so the scan moves on as after a DFA rejection. No reverse search ran,
                // so the bound on how far back the next reverse search may read stays where it
                // was: raising it, as after a rejection, would make the second `ing` of `singing`
                // fall back. Nor does the hit make the search fall back: near the start, where a
                // slash after a letter would send a short line holding a date to the ordinary
                // engine, or inside text a confirmation read, since a skipped hit runs no DFA to
                // read that text again.
                continue;
            }
            if (firstCandidate && literalStart - start < MINIMUM_SKIPPED_BYTES) {
                return FALLBACK;
            }
            if (literalStart < minimumLiteralStart) {
                return FALLBACK;
            }
            firstCandidate = false;
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
            minimumMatchStart = literalStart + matchedLength(text, literalStart, end);
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    // The first hit in `[scanStart, end)`, or -1 when there is none.
    private int nextLiteral(Slice text, int scanStart, int end)
    {
        return end - scanStart < minimumLiteralLength ? -1 : findLiteral(text, scanStart, end - scanStart);
    }

    // Whether the bytes next to the literal at `literalStart` can be those of a match in
    // `[start, end)`. A hit at `start` fails a gate before it, since that byte lies outside the
    // window, or outside the array at its first byte.
    private boolean passesByteGates(Slice text, int start, int literalStart, int end)
    {
        byte[] data = text.byteArray();
        int dataOffset = text.byteArrayOffset();
        if ((prefixGate0 | prefixGate1 | prefixGate2 | prefixGate3) != 0 &&
                (literalStart <= start || !inByteGate(data[dataOffset + literalStart - 1], prefixGate0, prefixGate1, prefixGate2, prefixGate3))) {
            return false;
        }
        int literalEnd = literalStart + minimumLiteralLength;
        return (suffixGate0 | suffixGate1) == 0 ||
                (literalEnd < end && inByteGate(data[dataOffset + literalEnd], suffixGate0, suffixGate1, 0, 0));
    }

    // The value is a byte, and a long shift uses only the low six bits of its count, so the byte
    // itself picks its bit in its word: bytes 0 to 63 in the first, 64 to 127 in the second, and
    // the negative bytes 128 to 255 in the third and fourth.
    private static boolean inByteGate(byte value, long word0, long word1, long word2, long word3)
    {
        long word = value >= 0 ? (value < 64 ? word0 : word1) : (value < -64 ? word2 : word3);
        return ((word >>> value) & 1) != 0;
    }

    private int findLiteral(Slice text, int offset, int length)
    {
        if (literalSearch == null) {
            return classPairBytes != null ? findClassPairMember(text, offset, length) : findLiteralSetMember(text, offset, length);
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

    // A class pair set is searched by its two sides' bytes, without a filter, and every hit is a
    // member. As for the filter, a short range skips the vector setup.
    private int findClassPairMember(Slice text, int offset, int length)
    {
        byte[] data = text.byteArray();
        int dataOffset = text.byteArrayOffset();
        int position = dataOffset + offset;
        int hit;
        if (VectorSupport.isAvailable() && length >= Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES) {
            hit = VectorSupport.SVE_128
                    ? VectorByteSetScanner.findClassPairSve(data, position, length, classPairBytes)
                    : VectorByteSetScanner.findClassPair(data, position, length, classPairBytes);
        }
        else {
            hit = findClassPair(data, position, length, classPairBytes);
        }
        return hit < 0 ? -1 : hit - dataOffset;
    }

    // The first position in `[offset, offset + length - 1)` whose byte is in slots 0 to 2 of
    // `bytes` and whose next byte is in slots 3 to 5, or -1.
    static int findClassPair(byte[] data, int offset, int length, byte[] bytes)
    {
        byte first0 = bytes[0];
        byte first1 = bytes[1];
        byte first2 = bytes[2];
        byte second0 = bytes[3];
        byte second1 = bytes[4];
        byte second2 = bytes[5];
        int end = offset + length - 1;
        for (int position = offset; position < end; position++) {
            byte first = data[position];
            if (first == first0 || first == first1 || first == first2) {
                byte second = data[position + 1];
                if (second == second0 || second == second1 || second == second2) {
                    return position;
                }
            }
        }
        return -1;
    }

    // The length of the literal found at `literalStart` in `[literalStart, end)`. For a set this
    // is the first member in alternation order, as the crate's Teddy reports the leftmost-first
    // literal, and for a set whose members share one length, such as a class pair, that length.
    private int matchedLength(Slice text, int literalStart, int end)
    {
        if (literalSearch != null || minimumLiteralLength == maximumLiteralLength) {
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

    // The elements at `index` and `index + 1`, when each consumes exactly one ASCII byte from at
    // most three, at least one of them matches more than one byte, and English text rarely holds
    // the pair, or null. Two literals are already a literal string.
    private static ClassPair asciiClassPair(List<Regexp> children, int index)
    {
        if (index + 1 >= children.size()) {
            return null;
        }
        byte[] first = asciiByteSet(children.get(index), MAXIMUM_CLASS_PAIR_SIDE_BYTES);
        byte[] second = asciiByteSet(children.get(index + 1), MAXIMUM_CLASS_PAIR_SIDE_BYTES);
        if (first == null || second == null || first.length * second.length < 2) {
            return null;
        }
        ByteFrequencies frequencies = ByteFrequencies.englishText();
        if (probability(frequencies, first) * probability(frequencies, second) >= MAXIMUM_CLASS_PAIR_PROBABILITY) {
            return null;
        }
        return new ClassPair(first, second);
    }

    private static double probability(ByteFrequencies frequencies, byte[] bytes)
    {
        double probability = 0;
        for (byte value : bytes) {
            probability += frequencies.probability(value);
        }
        return probability;
    }

    // The bytes, in class order, of an element that consumes exactly one ASCII byte: an ASCII
    // literal, or a class of at most `maximumBytes` ASCII runes, neither parsed case-insensitive;
    // or null for any other element. A class's ranges list every rune it matches, whatever
    // CharClass.foldsAscii tells the compiler.
    private static byte[] asciiByteSet(Regexp regexp, int maximumBytes)
    {
        while (regexp.op() == RegexpOp.CAPTURE) {
            regexp = regexp.child(0);
        }
        if ((regexp.parseFlags() & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) != 0) {
            return null;
        }
        if (regexp.op() == RegexpOp.LITERAL) {
            return regexp.rune() >= 0 && regexp.rune() <= 0x7F ? new byte[] {(byte) regexp.rune()} : null;
        }
        if (regexp.op() != RegexpOp.CHAR_CLASS) {
            return null;
        }
        CharClass charClass = regexp.charClass();
        byte[] bytes = new byte[maximumBytes];
        int count = 0;
        for (int rangeIndex = 0; rangeIndex < charClass.rangeCount(); rangeIndex++) {
            RuneRange range = charClass.range(rangeIndex);
            if (range.low() < 0 || range.high() > 0x7F || count + range.high() - range.low() + 1 > maximumBytes) {
                return null;
            }
            for (int rune = range.low(); rune <= range.high(); rune++) {
                bytes[count++] = (byte) rune;
            }
        }
        return count == 0 ? null : Arrays.copyOf(bytes, count);
    }

    // The bytes each side of a class pair matches, in class order.
    private record ClassPair(byte[] first, byte[] second)
    {
        // Every first byte followed by every second byte, the first byte major.
        byte[][] members()
        {
            byte[][] members = new byte[first.length * second.length][];
            for (int firstIndex = 0; firstIndex < first.length; firstIndex++) {
                for (int secondIndex = 0; secondIndex < second.length; secondIndex++) {
                    members[firstIndex * second.length + secondIndex] = new byte[] {first[firstIndex], second[secondIndex]};
                }
            }
            return members;
        }

        // Each side's bytes in three slots, a side with fewer repeating its last byte.
        byte[] scanBytes()
        {
            byte[] bytes = new byte[2 * MAXIMUM_CLASS_PAIR_SIDE_BYTES];
            for (int slot = 0; slot < MAXIMUM_CLASS_PAIR_SIDE_BYTES; slot++) {
                bytes[slot] = first[Math.min(slot, first.length - 1)];
                bytes[MAXIMUM_CLASS_PAIR_SIDE_BYTES + slot] = second[Math.min(slot, second.length - 1)];
            }
            return bytes;
        }
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
        int elements = 0;
        for (int childIndex = index; childIndex < children.size(); childIndex++) {
            int newLength = appendFoldedElement(children.get(childIndex), bytes, masks, length);
            if (newLength < 0) {
                break;
            }
            length = newLength;
            elements++;
        }
        int headLength = 0;
        while (headLength < length && masks[headLength] == 0) {
            headLength++;
        }
        if (headLength == 0 || headLength == length) {
            return null;
        }
        return new FoldedLiteral(Arrays.copyOf(bytes, headLength), Arrays.copyOf(bytes, length), Arrays.copyOf(masks, length), elements);
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

    // A case-insensitive literal: its exact head, its whole bytes and masks, and the number of
    // elements it covers.
    record FoldedLiteral(byte[] head, byte[] bytes, byte[] masks, int elements) {}

    private static boolean hasOneLength(byte[][] literals)
    {
        for (byte[] literal : literals) {
            if (literal.length != literals[0].length) {
                return false;
            }
        }
        return true;
    }

    // The ASCII bytes allowed after the literal: those of the first element at or after `index`
    // that does not match only the empty string, when it must consume one of a set of ASCII
    // bytes first, since the elements before it consume nothing. Otherwise no gate, all words
    // zero.
    private static ByteGate suffixGate(List<Regexp> children, int index)
    {
        for (int childIndex = index; childIndex < children.size(); childIndex++) {
            if (!matchesEmptyOnly(children.get(childIndex), 0)) {
                return byteGate(children.get(childIndex));
            }
        }
        return ByteGate.NONE;
    }

    // A single-byte ASCII element, alone or repeated at least once, consumes one of its bytes
    // first; any other element gives no gate.
    private static ByteGate byteGate(Regexp element)
    {
        while (element.op() == RegexpOp.CAPTURE) {
            element = element.child(0);
        }
        if (element.op() == RegexpOp.PLUS || (element.op() == RegexpOp.REPEAT && element.min() > 0)) {
            element = element.child(0);
        }
        byte[] bytes = asciiByteSet(element, 0x80);
        if (bytes == null) {
            return ByteGate.NONE;
        }
        long[] words = new long[4];
        for (byte value : bytes) {
            ByteGate.add(words, value);
        }
        return ByteGate.of(words);
    }

    // The bytes the literal, or any member of the set, can have first, or last when `last` is
    // set. A case-insensitive letter can have either case.
    private static ByteGate literalEdgeBytes(byte[][] literals, FoldedLiteral foldedLiteral, boolean last)
    {
        byte[][] members = foldedLiteral == null ? literals : new byte[][] {foldedLiteral.bytes()};
        long[] words = new long[4];
        for (byte[] member : members) {
            int position = last ? member.length - 1 : 0;
            ByteGate.add(words, member[position]);
            if (foldedLiteral != null) {
                ByteGate.add(words, (byte) (member[position] ^ foldedLiteral.masks()[position]));
            }
        }
        return ByteGate.of(words);
    }

    // The bytes a gate admits: bytes 0 to 63 in `word0`, 64 to 127 in `word1`, 128 to 191 in
    // `word2`, and 192 to 255 in `word3`.
    private record ByteGate(long word0, long word1, long word2, long word3)
    {
        static final ByteGate NONE = new ByteGate(0, 0, 0, 0);

        // The bytes with a nonzero entry in a 256-entry table, or no gate for a null table.
        static ByteGate of(byte[] table)
        {
            if (table == null) {
                return NONE;
            }
            long[] words = new long[4];
            for (int value = 0; value < 0x100; value++) {
                if (table[value] != 0) {
                    add(words, (byte) value);
                }
            }
            return of(words);
        }

        static ByteGate of(long[] words)
        {
            return new ByteGate(words[0], words[1], words[2], words[3]);
        }

        // The value is a byte, and a long shift uses only the low six bits of its count.
        static void add(long[] words, byte value)
        {
            words[(value & 0xFF) >>> 6] |= 1L << value;
        }

        // No gate when this one admits one of `bytes`. A gate that holds the literal's own edge
        // byte borders runs of its own bytes, as a letter before `shing` does inside a word, so
        // it passes nearly every hit and only adds its test.
        ByteGate unlessHolding(ByteGate bytes)
        {
            return ((word0 & bytes.word0) | (word1 & bytes.word1) | (word2 & bytes.word2) | (word3 & bytes.word3)) != 0 ? NONE : this;
        }
    }

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

    // Not one of the crate's proofs. Used only for a class pair set, but sound for any set whose
    // members share one length. Let C be the members' last bytes. No earlier match spans a later
    // candidate when the first prefix element that does not match only the empty string consumes
    // exactly one ASCII byte, every later prefix element is disjoint from C, and no member holds a
    // byte of C before its last. Suppose the search confirms the candidate whose member of length
    // len lies at x, with the start s <= x its reverse search finds, and an earlier match starts at
    // s' < s with its own member at x' > x. That match consumes the candidate's last byte, a byte
    // of C at q = x + len - 1, since s' < s <= x <= q < x' + len - 1. Its suffix starts past its
    // member, and its member could hold q only before the member's last position, where no member
    // holds a byte of C, so its prefix holds q. But the prefix's first element consumes only
    // s' < q, the elements before it consume nothing, and every element after it is disjoint from
    // C. An earlier match whose member lies at x is ruled out by the reverse search, which finds
    // the earliest start, and one whose member lies before x by that member's own candidate, whose
    // reverse search and confirmation would have found it first. In `["'][^"']{0,30}[?!.]["']`, C
    // is the quotes, `["']` matches one byte, `[^"']{0,30}` excludes the quotes, and no member
    // starts with one.
    private static boolean hasNoEarlierMatchAcrossLastByte(Regexp prefix, byte[][] literals)
    {
        int length = literals[0].length;
        boolean[] lastBytes = new boolean[0x80];
        for (byte[] literal : literals) {
            if (literal.length != length) {
                return false;
            }
            lastBytes[literal[length - 1]] = true;
        }
        for (byte[] literal : literals) {
            for (int position = 0; position < length - 1; position++) {
                if (lastBytes[literal[position]]) {
                    return false;
                }
            }
        }
        while (prefix.op() == RegexpOp.CAPTURE) {
            prefix = prefix.child(0);
        }
        List<Regexp> elements = prefix.op() == RegexpOp.CONCAT ? prefix.children() : List.of(prefix);
        int first = 0;
        while (first < elements.size() && matchesEmptyOnly(elements.get(first), 0)) {
            first++;
        }
        if (first == elements.size() || asciiByteSet(elements.get(first), 0x80) == null) {
            return false;
        }
        for (int index = first + 1; index < elements.size(); index++) {
            for (int value = 0; value < lastBytes.length; value++) {
                if (lastBytes[value] && canConsumeByte(elements.get(index), (byte) value, 0)) {
                    return false;
                }
            }
        }
        return true;
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
