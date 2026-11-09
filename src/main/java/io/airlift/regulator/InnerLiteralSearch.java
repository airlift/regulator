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
/// bytes do not rule out, a reverse DFA of `P` anchored at the occurrence finds the leftmost start,
/// and the forward DFA of the whole pattern anchored at that start finds the end.
///
/// The first confirmed candidate is the leftmost-first match only when no earlier match can span
/// a later occurrence of `L`. [#analyze] accepts a literal or literal set only when one of these
/// proofs holds: the crate's three (`meta/reverse_inner.rs` `has_no_earlier_match`), that `P`
/// cannot consume every byte of a single literal `L`, that `P` has a fixed length, or that the
/// first or last element of `P`, skipping elements such as assertions that match only the empty
/// string, is a required class disjoint from the rest of `P` and from every byte of `L`; and a
/// fourth of Regulator's own, that `P` is leading assertions and then a run of one class, such as
/// `\b\w+` or `.{0,2}`. Let an earlier match start at `s0`, with its prefix ending at its own
/// occurrence `g`, and let `P` also match `[e, h)` before a later occurrence `h`, with
/// `s0 < e <= h < g`. Then such a `P` matches `[s0, h)` too, so the reverse search from `h` finds
/// the earlier match's start. A run whose minimum is two or more characters qualifies only when
/// each character of its class is one byte. A class pair may instead pass a proof of its own,
/// described below.
///
/// As in the crate, a search reports [#FALLBACK], for its caller to run the ordinary engine, when
/// the reverse search would read back past the previous candidate, or a candidate lies in text the
/// previous anchored confirmation already read, which keeps the work linear, or when a DFA fails.
/// It also falls back when its first candidate lies fewer than [#MINIMUM_SKIPPED_BYTES] past the
/// search start. The prefix lies before the literal, so the search could skip no more than that,
/// which cannot repay the reverse search and the anchored confirmation.
///
/// A find never runs the search on a search window shorter than [#minimumSearchBytes], nor a count
/// on a whole text shorter than [#minimumCountBytes], though a count's later searches cover
/// whatever text remains; the exception is a pattern that keeps a short-search fixed-width matcher,
/// which runs the search from [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES] whatever its literal
/// kind. A shorter window, as for the many short lines of a line-by-line search, runs the ordinary
/// engine, or that fixed-width matcher for a count or matcher find. A gated one-byte search, for a
/// one-byte exact literal with byte gates, also runs on a shorter find window, as a
/// [check][#checksShortWindows] that rejects a window without a gate-passing hit, and where the
/// pattern qualifies the ordinary engine's full-Slice boolean find, which, for a pattern on the
/// literal-neighbor plan, runs only when that matcher leaves the find undecided, runs it first, in
/// place of a required-bytes check of its byte; see `CompiledPattern.runsInnerLiteralSearchFirst`.
/// A matcher's find or a full-Slice boolean find first takes the short-window required bytes reject
/// where the pattern keeps required bytes, though a ranged find and `findInto` do not: a literal of
/// two or more bytes, and a single required byte, check windows shorter than 256 bytes, and a
/// one-of set of two or three distinct bytes only those too short for the search, which itself
/// scans a longer window for its literal before any engine runs unless the find skips the DFA. The
/// minimums are where the literal's scan measured faster than the ordinary engine on r9g and r8i: a
/// class pair's scan and a one-byte literal's from [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES], a
/// longer or case-insensitive single literal's word-at-a-time scan from
/// [#MINIMUM_SINGLE_LITERAL_SEARCH_BYTES], and a set's scalar filter from
/// [#MINIMUM_LITERAL_SET_SEARCH_BYTES], or behind a class run that only the class-run proof admits
/// from [Dfa#MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES], where the vector scan takes over. A
/// case-insensitive literal without a searched head, one whose exact head is empty or one common
/// byte, searches from [Prog#FUSED_PREFIX_VECTOR_MINIMUM_BYTES], the folded prefix scan's vector
/// cutoff, reused here and unmeasured for this search. A count of a set not behind a class run
/// searches from [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES]: one count search continues across
/// many matches, so its scan pays off sooner, while each find starts a fresh search. The
/// fixed-width matcher took 2.7 to 5.8 times the search's time on 64- to 127-byte counts of
/// `[a-z]shing`, a single literal; for a fixed-width class pair it is unmeasured. A find loop also
/// keeps its cursors, as the ordinary engine falls back, where its first candidate [lies near the
/// window start][#fallsBackNearStart].
///
/// A case-insensitive ASCII literal is searched, over at least
/// [Prog#FUSED_PREFIX_VECTOR_MINIMUM_BYTES] with the Vector API, by the folded pair kernel of the
/// folded prefix scans over its first nine bytes, and over fewer by its searched head, the exact
/// bytes before its first case-insensitive letter; each hit is checked for the rest. A literal
/// without a searched head, whose exact head is empty, as in `\w+\s(?i)holmes\b`, or one common
/// byte, as in `\w+(?i: holmes)`, is searched only by the kernel, so only with the Vector API. The
/// 1 KiB cutoff admits its search, whose shorter scans then take the same kernel, and it gives way
/// to a later element's search that runs on shorter windows, as `@x` does in `[a-z](?i: noqa)@x`.
/// Two adjacent elements that each match one of a few ASCII bytes, such as the `[?!.]["']` that
/// ends `["'][^"']{0,30}[?!.]["']`, are searched as the set of their two-byte members when English
/// text rarely holds them, under a proof of its own.
///
/// A hit is skipped before either DFA runs when the byte before it is not one the reverse prefix
/// program can consume first, as a slash after a letter is not for a date, or when one of the up
/// to three bytes before that is not one the program can consume there, as a byte that is neither
/// a word byte nor whitespace is not two bytes before the `Holmes` of `\w+\s+Holmes`. A hit of a
/// literal, or a set whose members share one length, is skipped too when the element after it is
/// a single ASCII byte class, alone or repeated at least once, and the byte after it is outside
/// that class, as the `ing` of `kingdom` is for `\s[a-zA-Z]{0,12}ing\s`, or when a word boundary
/// or non-boundary right after it rules out the ASCII byte after it, as a word byte after the `nn`
/// of `\b\w+nn\b` does. A skipped hit is no candidate, so it never makes the search fall back.
///
/// A [count's search][#searchForCount] skips the forward DFA where every match provably ends at its
/// literal's end: it asks the reverse DFA only whether some start exists, and takes the literal's
/// end as the match end; see REGULATOR_DECISIONS.md, Inner Literal Counts To The Literal End.
final class InnerLiteralSearch
{
    static final long FALLBACK = Dfa.CANDIDATE_SEARCH_FALLBACK;
    // A search window, or a whole-text count's text, shorter than this skips the search of a
    // single literal of two or more bytes. Its word-at-a-time scan lost to the DFA in 64-byte
    // windows and won from 100 bytes, on r9g and r8i.
    static final int MINIMUM_SINGLE_LITERAL_SEARCH_BYTES = 96;
    // A find window shorter than this skips a literal set's search. Its scalar filter lost to the
    // DFA in 64- and 128-byte windows on r9g and r8i, and for `ing|ment` won again by 300 bytes on
    // r9g.
    static final int MINIMUM_LITERAL_SET_SEARCH_BYTES = 256;
    // A first candidate closer than this to the search start leaves too few bytes to skip.
    static final int MINIMUM_SKIPPED_BYTES = 64;

    // The safety proofs recurse over the prefix. Past this depth each one stops with its
    // conservative answer, which keeps the proofs stack safe, as MatchLength bounds its own
    // recursion. Another proof, such as the fixed-length check, may still admit the search.
    private static final int MAX_RECURSIVE_DEPTH = 256;
    // Each overlapping hit of a longer literal re-reads the whole literal. LiteralNeighborMatcher,
    // which restarts its scan one byte past each hit, and WindowLiteralSearch, which visits every
    // overlapping occurrence, take the same limit.
    static final int MAXIMUM_LITERAL_LENGTH = 64;
    // The set filter tests three positions of every member and holds at most eight members. The
    // crate treats its Teddy prefilter as fast only when every literal has at least three bytes.
    private static final int MINIMUM_SET_MEMBER_LENGTH = 3;
    private static final int MAXIMUM_SET_MEMBERS = 8;
    // Each side of a class pair is compared against up to this many bytes.
    private static final int MAXIMUM_CLASS_PAIR_SIDE_BYTES = 3;
    // How many bytes before a hit its gates check, as Hyperscan's Rose lookaround checks a few
    // bytes around a literal.
    private static final int MAXIMUM_PREFIX_GATES = 4;
    // The ASCII word bytes `[0-9A-Za-z_]`, as the bits of the first two gate words.
    private static final long ASCII_WORD_BYTES_0 = 0x03FF_0000_0000_0000L;
    private static final long ASCII_WORD_BYTES_1 = 0x07FF_FFFE_87FF_FFFEL;
    // What the word boundaries after a literal say of the next character, as flags: that it is a
    // word character, and that it is not.
    private static final int NEXT_IS_WORD = 1;
    private static final int NEXT_IS_NOT_WORD = 2;
    // A class pair is searched only when it is estimated below once in 500 bytes, from English
    // text byte frequencies, whatever frequencies the pattern was compiled with, as if each byte
    // were independent of its neighbor. Each hit may pay a reverse search and an anchored
    // confirmation, so a commoner pair could stop the scan about as often as the start-byte scan
    // of the ordinary engine it replaces, and add the reverse search to its stops.
    private static final double MAXIMUM_CLASS_PAIR_PROBABILITY = 0.002;

    private static final int INSTANCE_SIZE = SizeOf.instanceSize(InnerLiteralSearch.class);

    // A single literal has its search kernel; a set has its members, in alternation order, or for a
    // class pair in class order, and their filter, or for a class pair its scan bytes. A literal
    // with ASCII case-insensitive bytes, such as `# noqa` in `\s*# (?i:noqa)`, has the kernel of
    // its searched head, `# `, or none when its exact head is empty or one common byte, and the
    // whole literal with its case-insensitive letters lowered, its exact bytes unchanged, and a
    // mask of 0x20 at each letter that matches either case.
    private final LiteralSearchKernel literalSearch;
    private final byte[] foldedBytes;
    private final byte[] foldedMasks;
    // A case-insensitive literal's folded pair kernel compares its first
    // Prog.MAX_FOLD_CASE_PREFIX_BYTES bytes, probing the two at these offsets, and verifies inside
    // its vector loop when foldedDense is set, all chosen by Prog.foldedPairScan, as for a folded
    // prefix.
    // The kernel folds every ASCII letter, while an exact letter of the literal matches only
    // itself, so a kernel hit is checked from foldedVerifiedBytes on: the kernel compares the
    // bytes before it under the literal's own rule.
    private final int foldedFirstOffset;
    private final int foldedSecondOffset;
    private final int foldedVerifiedBytes;
    private final boolean foldedDense;
    private final byte[][] literalSet;
    private final StartLiteralFilter literalSetFilter;
    // A class pair set has no filter, but each side's bytes, the last repeated to three: the
    // first side's in slots 0 to 2 and the second side's in slots 3 to 5.
    private final byte[] classPairBytes;
    private final int minimumLiteralLength;
    private final int maximumLiteralLength;
    // The bytes allowed before the literal, as the bits of four words, and the ASCII bytes allowed
    // after it, as the bits of two, low bytes first. A gate whose words are all zero is no gate.
    // A gate after the literal that only word boundaries set also admits every byte past ASCII,
    // and the window's end. Held in the instance, these gates take no memory beyond the instance
    // size the search is charged for. The bytes allowed two, three, and four bytes before the
    // literal, as far as the reverse prefix program limits them, follow four words each in one
    // array, charged to the search's retained size, or null when it limits none or the array does
    // not fit the search's memory.
    private final long prefixGate0;
    private final long prefixGate1;
    private final long prefixGate2;
    private final long prefixGate3;
    private final long suffixGate0;
    private final long suffixGate1;
    private final boolean suffixGateFromWordBoundaries;
    private final long[] deeperPrefixGates;
    private final boolean hasByteGates;
    // Whether a count may take a confirmed hit's literal end as its match end, once the reverse
    // search finds any start, and whether that also needs the byte before the hit to be ASCII, or
    // the byte after it to be an ASCII byte that is not a word byte; see [#countEnd].
    private final boolean countsToLiteralEnd;
    private final boolean countEndNeedsAsciiBefore;
    private final boolean countEndNeedsNonWordAfter;
    // The minimums are derived from the literal kind and this, not kept, so that the search keeps
    // the 128-byte layout with compressed references, whose padding the booleans fill, that its
    // memory budget charges.
    private final boolean behindClassRun;
    private final boolean checksShortWindows;
    private final Prog reversePrefixProgram;
    private final Prog forwardProgram;
    private final boolean addedByClassRunProof;

    private InnerLiteralSearch(LiteralSearchKernel literalSearch, byte[] foldedBytes, byte[] foldedMasks, FoldedScan foldedScan, byte[][] literalSet, StartLiteralFilter literalSetFilter, byte[] classPairBytes, boolean behindClassRun, boolean addedByClassRunProof, ByteGate prefixGate, long[] deeperPrefixGates, ByteGate suffixGate, CountEnd countEnd, Prog reversePrefixProgram, Prog forwardProgram)
    {
        this.literalSearch = literalSearch;
        this.foldedBytes = foldedBytes;
        this.foldedMasks = foldedMasks;
        this.foldedFirstOffset = foldedScan.firstOffset();
        this.foldedSecondOffset = foldedScan.secondOffset();
        this.foldedVerifiedBytes = foldedScan.verifiedBytes();
        this.foldedDense = foldedScan.dense();
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
        // Only a gate set by word boundaries alone admits bytes past ASCII.
        this.suffixGateFromWordBoundaries = (suffixGate.word2() | suffixGate.word3()) != 0;
        this.deeperPrefixGates = deeperPrefixGates;
        this.hasByteGates = (prefixGate0 | prefixGate1 | prefixGate2 | prefixGate3 | suffixGate0 | suffixGate1) != 0 || deeperPrefixGates != null;
        this.countsToLiteralEnd = countEnd != null;
        this.countEndNeedsAsciiBefore = countEnd != null && countEnd.asciiBefore();
        this.countEndNeedsNonWordAfter = countEnd != null && countEnd.nonWordAfter();
        this.behindClassRun = behindClassRun;
        // A gated one-byte search keeps the check below its minimum that every inner literal
        // search ran before the minimums: of lh3lh3's date lines under 64 bytes that hold `/`,
        // 41,614 of 43,452 fail the digit gates and then skip the forward DFA.
        this.checksShortWindows = hasByteGates && oneByteLiteral() >= 0;
        this.reversePrefixProgram = requireNonNull(reversePrefixProgram, "reversePrefixProgram is null");
        this.forwardProgram = requireNonNull(forwardProgram, "forwardProgram is null");
        this.addedByClassRunProof = addedByClassRunProof;
    }

    /// Returns the search for the normalized `regexp`, compiled into `forwardProgram`, or null when
    /// it has no usable inner literal, the safety proofs fail, or its reverse prefix DFA could not
    /// initialize. The reverse prefix program gets at most `prefixMemory` bytes, and the whole
    /// search, as [#estimatedRetainedSize] reports it, retains at most `retainedMemory`. A
    /// case-insensitive literal's folded pair kernel probes the pair `byteFrequencies` chooses, as
    /// for a folded prefix.
    static InnerLiteralSearch analyze(Regexp regexp, Prog forwardProgram, long prefixMemory, long retainedMemory, Compiler.Dialect dialect, CaseFolding caseFolding, ByteFrequencies byteFrequencies)
    {
        if (forwardProgram.start() == 0 || forwardProgram.anchorStart() || forwardProgram.anchorEnd() || forwardProgram.canPrefixAccel() || forwardProgram.hasDfaUnsupportedAssertions()) {
            return null;
        }
        return chooseSearch(regexp, true, forwardProgram, prefixMemory, retainedMemory, dialect, caseFolding, byteFrequencies);
    }

    // The search the analysis chooses, with or without the class-run proof: the search of the
    // first of its inner literals whose search can be built.
    private static InnerLiteralSearch chooseSearch(Regexp regexp, boolean classRunProof, Prog forwardProgram, long prefixMemory, long retainedMemory, Compiler.Dialect dialect, CaseFolding caseFolding, ByteFrequencies byteFrequencies)
    {
        for (InnerLiteral innerLiteral : innerLiterals(regexp, caseFolding, classRunProof)) {
            InnerLiteralSearch search = build(regexp, innerLiteral, forwardProgram, prefixMemory, retainedMemory, dialect, caseFolding, byteFrequencies);
            if (search != null) {
                return search;
            }
        }
        return null;
    }

    // The inner literals of the normalized `regexp` the analysis tries, with or without the
    // class-run proof, in the order it tries their searches. A case-insensitive literal without a
    // searched head, which only the folded pair kernel searches, from the 1 KiB cutoff, gives way
    // to a later element's literal whose search runs on shorter windows, and falls back on it
    // where its own search cannot be built. Later elements are judged each by its own literal, as
    // without the Vector API, so one such literal never displaces another, and a run of them costs
    // one more look, not one per literal. So `[a-z](?i: noqa)@x` searches the exact `@x` from 96
    // bytes rather than ` noqa@x` from 1024, while `\w+\s(?i)holmes\b`, with nothing later to
    // search, searches its case-insensitive literal.
    private static List<InnerLiteral> innerLiterals(Regexp regexp, CaseFolding caseFolding, boolean classRunProof)
    {
        InnerLiteral innerLiteral = findInnerLiteral(regexp, caseFolding, classRunProof, true, 1);
        if (innerLiteral == null) {
            return List.of();
        }
        if (!innerLiteral.searchedOnlyByFoldedKernel()) {
            return List.of(innerLiteral);
        }
        InnerLiteral laterLiteral = findInnerLiteral(regexp, caseFolding, classRunProof, false, innerLiteral.element() + 1);
        if (laterLiteral == null) {
            return List.of(innerLiteral);
        }
        return laterLiteral.minimumSearchBytes() < innerLiteral.minimumSearchBytes() ? List.of(laterLiteral, innerLiteral) : List.of(innerLiteral, laterLiteral);
    }

    // The search for `innerLiteral`, the inner literal of `regexp`, or null when its memory or its
    // reverse prefix DFA does not allow one; see [#analyze].
    private static InnerLiteralSearch build(Regexp regexp, InnerLiteral innerLiteral, Prog forwardProgram, long prefixMemory, long retainedMemory, Compiler.Dialect dialect, CaseFolding caseFolding, ByteFrequencies byteFrequencies)
    {
        byte[] literal = innerLiteral.literal();
        StartLiteralFilter literalSetFilter = innerLiteral.literalSetFilter();
        LiteralSearchKernel literalSearch = literal == null ? null : new LiteralSearchKernel(Slices.wrappedBuffer(literal));
        FoldedLiteral foldedLiteral = innerLiteral.foldedLiteral();
        byte[][] literalSet = literal == null && foldedLiteral == null ? innerLiteral.literals() : null;
        byte[] foldedBytes = foldedLiteral == null ? null : foldedLiteral.bytes();
        byte[] foldedMasks = foldedLiteral == null ? null : foldedLiteral.masks();
        FoldedScan foldedScan = foldedLiteral == null ? FoldedScan.NONE : FoldedScan.of(foldedLiteral, byteFrequencies);
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
        // The bytes before the literal end the prefix, so the reverse prefix program names the
        // bytes each can be, whatever the prefix's shape. The byte right before keeps its gate
        // only where it rarely borders the literal; the deeper bytes are checked only after it
        // passes, and keep theirs where the search's memory allows.
        long[] positionByteSets = reversePrefixProgram.boundaryPositionByteSets(MAXIMUM_PREFIX_GATES);
        ByteGate prefixGate = positionByteSets == null ? ByteGate.NONE : ByteGate.of(positionByteSets).unlessHolding(literalEdgeBytes(innerLiteral.literals(), foldedLiteral, false));
        long[] deeperPrefixGates = positionByteSets == null || positionByteSets.length == 4 ? null : Arrays.copyOfRange(positionByteSets, 4, positionByteSets.length);
        if (deeperPrefixGates != null && retainedSize(literalSearch, foldedBytes, foldedMasks, literalSet, literalSetFilter, classPairBytes, reversePrefixProgram, deeperPrefixGates) > retainedMemory) {
            deeperPrefixGates = null;
        }
        boolean addedByClassRunProof = innerLiteral.behindClassRun() && findsNoSearchWithoutClassRunProof(regexp, forwardProgram, prefixMemory, retainedMemory, dialect, caseFolding, byteFrequencies);
        return new InnerLiteralSearch(literalSearch, foldedBytes, foldedMasks, foldedScan, literalSet, literalSetFilter, classPairBytes, innerLiteral.behindClassRun(), addedByClassRunProof, prefixGate, deeperPrefixGates, innerLiteral.suffixGate(), innerLiteral.countEnd(), reversePrefixProgram, forwardProgram);
    }

    // Whether the analysis would find no search without the class-run proof, which alone admitted
    // the literal. The analysis is rerun without it rather than resumed after the literal, since
    // the literal may be the later search that a case-insensitive literal before it gave way to,
    // and without the proof that earlier literal is chosen again. The searches it builds decide,
    // since their memory and reverse prefix DFAs still decide whether there is one.
    private static boolean findsNoSearchWithoutClassRunProof(Regexp regexp, Prog forwardProgram, long prefixMemory, long retainedMemory, Compiler.Dialect dialect, CaseFolding caseFolding, ByteFrequencies byteFrequencies)
    {
        return chooseSearch(regexp, false, forwardProgram, prefixMemory, retainedMemory, dialect, caseFolding, byteFrequencies) == null;
    }

    /// Returns the shortest literal length [#analyze] would search the normalized `regexp` for:
    /// the length of its literal, or of the shortest member of its literal set, as
    /// [#minimumLiteralLength] counts it, or zero when it has none, without a compiled program. It
    /// also returns zero when the expression has a prefix or a folded run the compiler could scan
    /// for, a conservative exclusion: the compiler leaves out a leading run where vectors hold 64
    /// bytes, which this does not check. It cannot see what [#analyze] reads from the program
    /// alone: an anchored or DFA-unsupported assertion, which the caller's expression must not
    /// hold, and whether the reverse prefix DFA fits its memory.
    static int predictMinimumLiteralLength(Regexp regexp, CaseFolding caseFolding, ByteFrequencies byteFrequencies)
    {
        if (regexp.requiredPrefixForAccel(caseFolding) != null || !regexp.foldedRunsForAccel(caseFolding, byteFrequencies).isEmpty()) {
            return 0;
        }
        List<InnerLiteral> innerLiterals = innerLiterals(regexp, caseFolding, true);
        if (innerLiterals.isEmpty()) {
            return 0;
        }
        InnerLiteral innerLiteral = innerLiterals.getFirst();
        FoldedLiteral foldedLiteral = innerLiteral.foldedLiteral();
        if (foldedLiteral != null) {
            return foldedLiteral.bytes().length;
        }
        return Arrays.stream(innerLiteral.literals()).mapToInt(literal -> literal.length).min().orElseThrow();
    }

    // The inner literal of the normalized `regexp` and the prefix before it: a single literal, the
    // members of a literal set with their filter, or the members of a class pair with its sides'
    // bytes, the byte gate after it, and what a count needs to end a match at the literal's end,
    // from the top-level concatenation's element `firstElement` on. With `classRunProof` false,
    // the class-run proof admits no prefix. With `allowsFoldedKernelOnlyLiterals` false, a
    // case-insensitive literal without a searched head is judged by its own element, as without
    // the Vector API.
    private static InnerLiteral findInnerLiteral(Regexp regexp, CaseFolding caseFolding, boolean classRunProof, boolean allowsFoldedKernelOnlyLiterals, int firstElement)
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
        // is a class pair, or a case-insensitive literal without a searched head, that no proof
        // admits.
        for (int index = firstElement; index < children.size(); index++) {
            // Case-insensitive ASCII elements extend the literal. Its exact head, the bytes before
            // the first case-insensitive letter, is what the proofs check where there is one: every
            // occurrence of the whole literal starts an occurrence of that head. Without one they
            // check the whole literal, reading each letter in either case.
            FoldedLiteral foldedLiteral = foldedLiteral(children, index);
            // A short window scans for the searched head. A head that is empty, or one common
            // byte, whose scan would stop about as often as the DFA's start bytes, has no scan, and
            // the literal is searched only by the folded pair kernel, which needs the Vector API.
            // Without it, or without such literals, the element is judged by its own literal, as
            // without the Vector API, and a case-insensitive one lets the loop try later elements.
            byte[] searchedHead = foldedLiteral == null ? null : searchedHead(foldedLiteral);
            if (InnerLiteral.searchedOnlyByFoldedKernel(foldedLiteral, searchedHead) && !(allowsFoldedKernelOnlyLiterals && VectorSupport.isAvailable())) {
                foldedLiteral = null;
            }
            byte[] literal = foldedLiteral != null ? searchedHead : asciiLiteral(children.get(index));
            byte[][] literals;
            StartLiteralFilter literalSetFilter = null;
            ClassPair classPair = null;
            boolean lettersEitherCase = false;
            if (foldedLiteral != null) {
                lettersEitherCase = foldedLiteral.head().length == 0;
                literals = new byte[][] {lettersEitherCase ? foldedLiteral.bytes() : foldedLiteral.head()};
            }
            else if (literal != null) {
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
            boolean provenOtherwise = hasNoEarlierMatch(prefix, literals, lettersEitherCase, caseFolding) || (classPair != null && hasNoEarlierMatchAcrossLastByte(prefix, literals));
            boolean behindClassRun = !provenOtherwise && classRunProof && isClassRunPrefix(prefix);
            if (!provenOtherwise && !behindClassRun) {
                // As a class pair does, a case-insensitive literal without a searched head gives
                // way to the elements after it.
                if (classPair != null || InnerLiteral.searchedOnlyByFoldedKernel(foldedLiteral, literal)) {
                    continue;
                }
                return null;
            }
            // The byte after the literal, and the match end, are known only when every member has
            // one length.
            ByteGate suffixGate = ByteGate.NONE;
            CountEnd countEnd = null;
            if (hasOneLength(literals)) {
                int literalElements = foldedLiteral != null ? foldedLiteral.elements() : classPair != null ? 2 : 1;
                ByteGate lastBytes = literalEdgeBytes(literals, foldedLiteral, true);
                suffixGate = suffixGate(children, index + literalElements, lastBytes).unlessHolding(lastBytes);
                countEnd = countEnd(children, index + literalElements, lastBytes, prefix, literals, foldedLiteral, classPair != null, behindClassRun, caseFolding);
            }
            return new InnerLiteral(index, prefix, literal, literals, literalSetFilter, foldedLiteral, classPair == null ? null : classPair.scanBytes(), behindClassRun, suffixGate, countEnd);
        }
        return null;
    }

    // `literal` is the single literal, or null for a set, whose filter is `literalSetFilter`, or
    // for a class pair, whose sides' bytes are `classPairBytes`; `literals` holds the single
    // literal or the members. A case-insensitive literal's `literal` is its searched exact head,
    // or null without one, `literals` holds what the proofs checked, and `foldedLiteral` holds the
    // whole literal, or is null. `suffixGate` holds the bytes the byte after a hit may be, or is
    // `ByteGate.NONE` for no gate. `behindClassRun` says that only the class-run proof admits the
    // prefix. `countEnd` says what a count needs to end a match at the literal's end, or is null
    // when it may not. `element` is the literal's first element in the top-level concatenation.
    private record InnerLiteral(int element, Regexp prefix, byte[] literal, byte[][] literals, StartLiteralFilter literalSetFilter, FoldedLiteral foldedLiteral, byte[] classPairBytes, boolean behindClassRun, ByteGate suffixGate, CountEnd countEnd)
    {
        // Whether this is a case-insensitive literal without a searched head, which only the
        // folded pair kernel searches.
        boolean searchedOnlyByFoldedKernel()
        {
            return searchedOnlyByFoldedKernel(foldedLiteral, literal);
        }

        // Whether `foldedLiteral`, searched for `literal`, its searched head, is searched only by
        // the folded pair kernel.
        static boolean searchedOnlyByFoldedKernel(FoldedLiteral foldedLiteral, byte[] literal)
        {
            return foldedLiteral != null && literal == null;
        }

        // The fewest bytes of a find window the literal's search runs on; see
        // InnerLiteralSearch.minimumSearchBytes.
        int minimumSearchBytes()
        {
            return InnerLiteralSearch.minimumSearchBytes(literalSetFilter != null, classPairBytes != null, foldedLiteral != null, literal == null ? 0 : literal.length, behindClassRun);
        }
    }

    // A count may end a match at its literal's end once the reverse search finds any start, and
    // when `asciiBefore` is set the byte before the hit is ASCII, and when `nonWordAfter` is set
    // the byte after it is an ASCII byte that is not a word byte.
    private record CountEnd(boolean asciiBefore, boolean nonWordAfter) {}

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

    /// The masks of a case-insensitive literal, `0x20` at each case-insensitive letter of its one
    /// [#literals] member, or null for any other search.
    byte[] foldedMasks()
    {
        return foldedMasks == null ? null : foldedMasks.clone();
    }

    /// The filter a literal set's search scans for its members with, or null for any other search.
    StartLiteralFilter literalSetFilterForDiagnostics()
    {
        return literalSetFilter;
    }

    /// Whether only the class-run proof gives the pattern an inner literal search: without that
    /// proof, [#analyze] returns none. Such a pattern checks long windows for its required literal
    /// or one-of bytes just as it would without the search; see
    /// `CompiledPattern.checksRequiredLiteralOnLongWindows`.
    boolean addedByClassRunProof()
    {
        return addedByClassRunProof;
    }

    /// Whether every match begins with the literal: the prefix before it matches only the empty
    /// string, as the word boundary before `the` in `\bthe\b.*?,z` does, so the prefix consumes
    /// no byte, and its reverse program reaches no byte instruction. A hit then lands where a
    /// match would start, as a prefix scan's does, and not inside a match, as the hit for `,` in
    /// `\w+,\bz` does; see `CompiledPattern.mayCheckRequiredLiteralOnLongWindows`.
    boolean literalBeginsEveryMatch()
    {
        Prog program = reversePrefixProgram;
        boolean[] visited = new boolean[program.size()];
        // Each instruction is visited once and pushes at most three others, after the start.
        int[] stack = new int[3 * program.size() + 1];
        int stackPointer = 0;
        stack[stackPointer++] = program.start();
        while (stackPointer > 0) {
            int instructionId = stack[--stackPointer];
            if (instructionId <= 0 || instructionId >= program.size() || visited[instructionId]) {
                continue;
            }
            visited[instructionId] = true;
            Prog.Inst instruction = program.inst(instructionId);
            if (instruction.opcode() == InstOp.BYTE_RANGE) {
                return false;
            }
            if (!instruction.last()) {
                stack[stackPointer++] = instructionId + 1;
            }
            switch (instruction.opcode()) {
                case ALT, ALT_MATCH -> {
                    stack[stackPointer++] = instruction.out1();
                    stack[stackPointer++] = instruction.out();
                }
                case CAPTURE, EMPTY_WIDTH, NOP -> stack[stackPointer++] = instruction.out();
                default -> {}
            }
        }
        return true;
    }

    /// The bytes the byte before the literal and the byte after it must be one of, each as a
    /// string in byte order, empty for no gate. A gate after the literal that holds bytes past
    /// ASCII is one that only word boundaries set, which the window's end passes too.
    List<String> byteGatesForDiagnostics()
    {
        long highBytes = suffixGateFromWordBoundaries ? -1L : 0L;
        return List.of(gateBytes(prefixGate0, prefixGate1, prefixGate2, prefixGate3), gateBytes(suffixGate0, suffixGate1, highBytes, highBytes));
    }

    /// The bytes the bytes two, three, and four before the literal must be one of, nearest first,
    /// each as a string in byte order, as far as the reverse prefix program limits them, and none
    /// when the search's memory left the gates out.
    List<String> deeperPrefixGatesForDiagnostics()
    {
        if (deeperPrefixGates == null) {
            return List.of();
        }
        List<String> gates = new ArrayList<>();
        for (int word = 0; word < deeperPrefixGates.length; word += 4) {
            gates.add(gateBytes(deeperPrefixGates[word], deeperPrefixGates[word + 1], deeperPrefixGates[word + 2], deeperPrefixGates[word + 3]));
        }
        return gates;
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
    /// case-insensitive literal counts its whole length, since each hit is checked for the whole
    /// literal before any engine runs.
    int minimumLiteralLength()
    {
        return minimumLiteralLength;
    }

    /// The fewest bytes of a find's search window that its callers run the search on where the
    /// alternative is the ordinary engine:
    /// [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES] for a class pair or a one-byte literal,
    /// [#MINIMUM_SINGLE_LITERAL_SEARCH_BYTES] for a longer or case-insensitive single literal,
    /// [Prog#FUSED_PREFIX_VECTOR_MINIMUM_BYTES] for a case-insensitive literal without a searched
    /// head, which only the folded pair kernel searches, and
    /// [#MINIMUM_LITERAL_SET_SEARCH_BYTES] for a literal set, or
    /// [Dfa#MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES] for one behind a class run that only the
    /// class-run proof admits. A pattern with a short-search fixed-width matcher, whose shorter
    /// counts and matcher finds run that matcher instead, runs the search from
    /// [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES], and a search that
    /// [checks short windows][#checksShortWindows] runs on them too; see
    /// [CompiledPattern#searchesInnerLiteralWindow].
    int minimumSearchBytes()
    {
        return minimumSearchBytes(literalSetFilter != null, classPairBytes != null, foldedBytes != null, literalSearch == null ? 0 : literalSearch.literalLength(), behindClassRun);
    }

    // The fewest bytes of a find window a search runs on, by its kind, which an inner literal
    // knows before its search is built: a literal set, a class pair, a case-insensitive literal,
    // or a single literal, of which `literalLength` is the length of the literal or searched head
    // a kernel scans for, or zero for none.
    private static int minimumSearchBytes(boolean literalSet, boolean classPair, boolean folded, int literalLength, boolean behindClassRun)
    {
        // A short window costs a literal set's scalar filter more than the DFA it would replace,
        // and behind a class run, which only the class-run proof admits, a set's search lost to
        // the DFA up to the vector scan's cutoff. A class pair's scan and a one-byte literal's
        // beat the DFA from 64 bytes. A case-insensitive literal is a single one, searched below
        // the vector cutoff by its searched head, and without one left to the ordinary engine
        // there.
        if (literalSet) {
            return behindClassRun ? Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES : MINIMUM_LITERAL_SET_SEARCH_BYTES;
        }
        if (classPair) {
            return CompiledPattern.MINIMUM_LONG_SEARCH_BYTES;
        }
        if (folded) {
            return literalLength > 0 ? MINIMUM_SINGLE_LITERAL_SEARCH_BYTES : Prog.FUSED_PREFIX_VECTOR_MINIMUM_BYTES;
        }
        return literalLength == 1 ? CompiledPattern.MINIMUM_LONG_SEARCH_BYTES : MINIMUM_SINGLE_LITERAL_SEARCH_BYTES;
    }

    /// Whether a find runs the search on a window shorter than [#minimumSearchBytes] too, as the
    /// gated one-byte search of a one-byte exact literal with byte gates does. There the search is
    /// only a check: a window whose hits all fail the gates has no match, and one with a hit that
    /// passes them falls back near its start to the ordinary engine. A whole-text count keeps
    /// [#minimumCountBytes]. Where the pattern qualifies, the ordinary engine's full-Slice boolean
    /// find, which, for a pattern on the literal-neighbor plan, runs only when that matcher leaves
    /// the find undecided, runs such a search first; see
    /// `CompiledPattern.runsInnerLiteralSearchFirst`.
    boolean checksShortWindows()
    {
        return checksShortWindows;
    }

    /// Whether the search is for the one literal `literal`, without case-insensitive letters.
    boolean searchesExactLiteral(byte[] literal)
    {
        return literalSearch != null && foldedBytes == null && Arrays.equals(literalSearch.literalBytes(), literal);
    }

    /// Whether the gate after the literal admits the ASCII byte `value` alone: no other byte, and
    /// not the window's end, which only a gate that word boundaries alone set admits.
    boolean admitsOnlyAfterLiteral(byte value)
    {
        if (value < 0 || suffixGateFromWordBoundaries) {
            return false;
        }
        return value < 64 ? suffixGate0 == 1L << value && suffixGate1 == 0 : suffixGate0 == 0 && suffixGate1 == 1L << value;
    }

    /// The byte of a one-byte exact literal, as an unsigned value, or `-1` for every other literal
    /// kind.
    int oneByteLiteral()
    {
        return literalSearch != null && foldedBytes == null && literalSearch.literalLength() == 1 ? literalSearch.literalBytes()[0] & 0xFF : -1;
    }

    /// The fewest bytes of a whole-text count's text that it runs the search on where the
    /// alternative is the ordinary engine: [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES] for a literal
    /// set not behind a class run, whose one search continues across the count's matches, and
    /// [#minimumSearchBytes] for every other kind. A pattern with a short-search fixed-width
    /// matcher counts through the search from [CompiledPattern#MINIMUM_LONG_SEARCH_BYTES].
    int minimumCountBytes()
    {
        // One count search continues across many matches, so a set's scan pays off sooner there
        // than in a find, which starts a fresh search: counts of `[a-z](?:ing|ment)` on 64- and
        // 128-byte texts took 1.09 to 1.22 times the search's time on the DFA on r9g, and 0.90 to
        // 0.95 times on r8i.
        return literalSetFilter != null && !behindClassRun ? CompiledPattern.MINIMUM_LONG_SEARCH_BYTES : minimumSearchBytes();
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
        return retainedSize(literalSearch, foldedBytes, foldedMasks, literalSet, literalSetFilter, classPairBytes, reversePrefixProgram, deeperPrefixGates);
    }

    // What a search of these parts retains, for the budget check as it is built and for its
    // report afterward.
    private static long retainedSize(LiteralSearchKernel literalSearch, byte[] foldedBytes, byte[] foldedMasks, byte[][] literalSet, StartLiteralFilter literalSetFilter, byte[] classPairBytes, Prog reversePrefixProgram, long[] deeperPrefixGates)
    {
        return INSTANCE_SIZE + literalRetainedSize(literalSearch, foldedBytes, foldedMasks, literalSet, literalSetFilter, classPairBytes) + Compiler.estimatedProgramMemory(reversePrefixProgram) + reversePrefixProgram.dfaMemory() + SizeOf.sizeOf(deeperPrefixGates);
    }

    // The folded arrays are null unless the literal is case-insensitive, which has no kernel
    // without a searched head, and a set has either its filter or its class pair bytes.
    private static long literalRetainedSize(LiteralSearchKernel literalSearch, byte[] foldedBytes, byte[] foldedMasks, byte[][] literalSet, StartLiteralFilter literalSetFilter, byte[] classPairBytes)
    {
        if (foldedBytes != null) {
            return (literalSearch == null ? 0 : literalSearch.estimatedRetainedSize()) + SizeOf.sizeOf(foldedBytes) + SizeOf.sizeOf(foldedMasks);
        }
        if (literalSearch != null) {
            return literalSearch.estimatedRetainedSize();
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
    /// literal's length, and one byte past that for the gate after a hit, and the gates before a
    /// hit read only bytes of the window. Within that read a shorter member of a set can start
    /// past the near bytes, where the search does not fall back. As in the search, a hit that
    /// fails the byte gates is no candidate, so the check looks past it.
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

    /// Whether the search is for one literal without case-insensitive letters and `[start, end)`
    /// of `text` lacks it. Every match holds that literal, so such a window holds no match. A
    /// route that skips the search scans for the literal in its place, since a pattern with a
    /// search the class-run proof does not [add][#addedByClassRunProof] never checks long windows
    /// for this literal: such a search inside a match keeps the pattern from the long-window
    /// check, and one for a literal that begins every match checks only for a literal beside it.
    boolean windowLacksLiteral(Slice text, int start, int end)
    {
        return literalSearch != null && foldedBytes == null && nextLiteral(text, start, end) < 0;
    }

    /// Returns the Slice offset of the first hit of the search's literal in `[start, end)` of
    /// `text`, before any gate, or -1, by the scan the search itself runs; see
    /// `RequiredBytes.handoffIndexIn`.
    int literalIndexIn(Slice text, int start, int end)
    {
        return nextLiteral(text, start, end);
    }

    /// Searches `[start, end)` of `text` with assertions seeing `[contextStart, contextEnd)`, and
    /// returns the match's Slice offsets packed as `start << 32 | end`, [Dfa#SEARCH_NO_MATCH], or
    /// [#FALLBACK].
    long search(Slice text, int contextStart, int contextEnd, int start, int end)
    {
        return search(text, contextStart, contextEnd, start, end, false);
    }

    /// As [#search], for a count, which needs only the match end: every result that is not
    /// negative carries the end in its low half, and its start may be absent. Where every match
    /// provably ends at its literal's end, a candidate's start is not looked for; see
    /// REGULATOR_DECISIONS.md, Inner Literal Counts To The Literal End.
    long searchForCount(Slice text, int contextStart, int contextEnd, int start, int end)
    {
        return search(text, contextStart, contextEnd, start, end, countsToLiteralEnd);
    }

    // The search, which confirms a candidate to its literal's end, as [#confirm] does, when
    // `toLiteralEnd` is set.
    private long search(Slice text, int contextStart, int contextEnd, int start, int end, boolean toLiteralEnd)
    {
        // Patterns without a gate keep this loop free of the gate check, and gated ones run their
        // own copy: with the check in one shared loop, patterns without a gate measured up to
        // about 4% slower, on lh3lh3 uri (Graviton) and lh3lh3 email (Intel) before their
        // prefixes were gated; see REGULATOR_DECISIONS.md, Inner Literal Byte Gates.
        if (hasByteGates) {
            return searchGated(text, contextStart, contextEnd, start, end, toLiteralEnd);
        }
        return searchUngatedFrom(text, contextStart, contextEnd, start, end, start, toLiteralEnd);
    }

    /// As [#search], for a caller that has already found `literalStart`, the first occurrence in
    /// `[start, end)` of the literal of a search for one literal without case-insensitive letters,
    /// or, where the gate after that literal admits one byte alone, the first occurrence of the
    /// literal followed by that byte; see `CompiledPattern.findFromRequiredLiteral`. The search
    /// starts there, rather than scanning the window from `start` again, and returns what [#search]
    /// returns. Before `literalStart` lies no hit, or, where the gate after the literal admits one
    /// byte alone, none that passes that gate, and a hit that fails a gate changes no state of the
    /// loop, so the loop reaches `literalStart` as [#search] does. A search without a gate falls
    /// back on a first hit near the start, as the first step of [#search] does, and otherwise
    /// continues as that loop does after its first scan.
    long searchFromCheckedHit(Slice text, int contextStart, int contextEnd, int start, int end, int literalStart)
    {
        if (hasByteGates) {
            return searchGatedFrom(text, contextStart, contextEnd, start, end, literalStart, false);
        }
        if (literalStart - start < MINIMUM_SKIPPED_BYTES) {
            return FALLBACK;
        }
        return searchUngatedFrom(text, contextStart, contextEnd, start, end, literalStart, false);
    }

    // The search loop of a pattern without a byte gate, scanning from `firstScanStart`: `start`, or
    // a hit that searchFromCheckedHit's caller supplied and searchFromCheckedHit judged past the
    // near-start bytes.
    private long searchUngatedFrom(Slice text, int contextStart, int contextEnd, int start, int end, int firstScanStart, boolean toLiteralEnd)
    {
        // The reverse search may read back to the end of the previous candidate, and a candidate
        // may not start inside the text the previous confirmation read.
        int minimumMatchStart = start;
        int minimumLiteralStart = start;
        int scanStart = firstScanStart;
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
            long candidate = confirm(text, contextStart, contextEnd, start, literalStart, end, maximumReverseBytes, toLiteralEnd);
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

    // Confirms the candidate at `literalStart` with the single pass of
    // [Dfa#searchReverseThenAnchoredWithStop], and returns as it does. For a count whose matches
    // end at the literal's end, `toLiteralEnd`, where the bytes beside the hit
    // [allow][#endsAtLiteral] it, the reverse DFA's earliest-match mode instead looks only for
    // some start within the same bound, and the literal's end takes the place of the span.
    private long confirm(Slice text, int contextStart, int contextEnd, int start, int literalStart, int end, int maximumReverseBytes, boolean toLiteralEnd)
    {
        if (toLiteralEnd && endsAtLiteral(text, start, literalStart, end)) {
            long reverse = Dfa.searchReverseCandidateExists(reversePrefixProgram, text, contextStart, contextEnd, start, literalStart, maximumReverseBytes);
            return reverse >= 0 ? literalStart + minimumLiteralLength : reverse;
        }
        return Dfa.searchReverseThenAnchoredWithStop(reversePrefixProgram, forwardProgram, text, contextStart, contextEnd, start, literalStart, end, maximumReverseBytes);
    }

    // Whether the bytes beside the hit at `literalStart` let the match end at the literal's end:
    // an ASCII byte before it, where the separator at the prefix's end needs one, and an ASCII
    // byte that is not a word byte after it, in the window, where a word boundary follows the
    // literal; see [#countEnd].
    private boolean endsAtLiteral(Slice text, int start, int literalStart, int end)
    {
        byte[] data = text.byteArray();
        int dataOffset = text.byteArrayOffset();
        if (countEndNeedsAsciiBefore && (literalStart <= start || data[dataOffset + literalStart - 1] < 0)) {
            return false;
        }
        if (!countEndNeedsNonWordAfter) {
            return true;
        }
        int literalEnd = literalStart + minimumLiteralLength;
        if (literalEnd >= end) {
            return false;
        }
        byte next = data[dataOffset + literalEnd];
        return next >= 0 && !isAsciiWordByte(next);
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

    // The search of a pattern with a byte gate. A window without a hit, such as a long line
    // without the literal or the tail a count has left, returns here without entering the loop;
    // see REGULATOR_DECISIONS.md, Inner Literal Byte Gates.
    private long searchGated(Slice text, int contextStart, int contextEnd, int start, int end, boolean toLiteralEnd)
    {
        int literalStart = nextLiteral(text, start, end);
        if (literalStart < 0) {
            return Dfa.SEARCH_NO_MATCH;
        }
        return searchGatedFrom(text, contextStart, contextEnd, start, end, literalStart, toLiteralEnd);
    }

    // The search loop of a pattern with a byte gate, from the hit at `firstLiteralStart`: as in
    // search, with each hit first checked against the gates, so the fallbacks see only the hits
    // that pass.
    private long searchGatedFrom(Slice text, int contextStart, int contextEnd, int start, int end, int firstLiteralStart, boolean toLiteralEnd)
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
                // slash after a letter would send a window holding a date to the ordinary engine,
                // or inside text a confirmation read, since a skipped hit runs no DFA to read that
                // text again.
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
            long candidate = confirm(text, contextStart, contextEnd, start, literalStart, end, maximumReverseBytes, toLiteralEnd);
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
    // `[start, end)`. Every gate before the literal is on a byte every match consumes, so a hit
    // too near `start` fails it, since that byte lies outside the window, or outside the array at
    // its first byte. The gate after the literal is on a consumed byte too, which the window's end
    // fails, unless only word boundaries set it: then the boundary is judged with the context, and
    // the end, or a byte past ASCII, which may begin or continue a word character, passes. The
    // deeper gates before the literal are checked only once the nearer ones pass.
    private boolean passesByteGates(Slice text, int start, int literalStart, int end)
    {
        byte[] data = text.byteArray();
        int dataOffset = text.byteArrayOffset();
        if ((prefixGate0 | prefixGate1 | prefixGate2 | prefixGate3) != 0 &&
                (literalStart <= start || !inByteGate(data[dataOffset + literalStart - 1], prefixGate0, prefixGate1, prefixGate2, prefixGate3))) {
            return false;
        }
        int literalEnd = literalStart + minimumLiteralLength;
        if ((suffixGate0 | suffixGate1) != 0) {
            long highBytes = suffixGateFromWordBoundaries ? -1L : 0L;
            if (literalEnd < end ? !inByteGate(data[dataOffset + literalEnd], suffixGate0, suffixGate1, highBytes, highBytes) : !suffixGateFromWordBoundaries) {
                return false;
            }
        }
        return deeperPrefixGates == null || passesDeeperPrefixGates(data, dataOffset, start, literalStart);
    }

    // Whether each byte from two before the literal at `literalStart` on back, as far as the
    // deeper gates reach, lies in `[start, literalStart)` and is one its gate admits.
    private boolean passesDeeperPrefixGates(byte[] data, int dataOffset, int start, int literalStart)
    {
        long[] gates = deeperPrefixGates;
        int position = literalStart - 2;
        for (int word = 0; word < gates.length; word += 4, position--) {
            if (position < start || !inByteGate(data[dataOffset + position], gates[word], gates[word + 1], gates[word + 2], gates[word + 3])) {
                return false;
            }
        }
        return true;
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
        if (foldedBytes != null) {
            return findFoldedLiteral(text, offset, length);
        }
        if (literalSearch == null) {
            return classPairBytes != null ? findClassPairMember(text, offset, length) : findLiteralSetMember(text, offset, length);
        }
        if (usesVectorSearch(length)) {
            return findLiteralVector(text, offset, length);
        }
        return minimumLiteralLength == 1 ? literalSearch.findSingleByte(text, offset, length) : literalSearch.findContains(text, offset, length);
    }

    /// Whether a scan of `length` bytes for a single literal takes the vector kernels, under the
    /// same cutoff as the prefix scans. A case-insensitive literal takes the folded pair kernel
    /// there, and at every length when it has no searched head, since no other scan searches it:
    /// the cutoff admits that search, and its shorter scans, such as near-start probes and the
    /// tails of a count, take the same kernel. Such a literal is searched only with the Vector API.
    /// A set never takes the vector kernels.
    boolean usesVectorSearch(int length)
    {
        return (literalSearch != null || foldedBytes != null) && VectorSupport.isAvailable() && (length >= Prog.FUSED_PREFIX_VECTOR_MINIMUM_BYTES || literalSearch == null);
    }

    // A vector scan finds the literal with the folded pair kernel. Otherwise each hit of the exact
    // head, with room for the whole literal, is checked for the folded rest, and a false hit
    // continues the scan past it.
    private int findFoldedLiteral(Slice text, int offset, int length)
    {
        if (usesVectorSearch(length)) {
            return findFoldedLiteralVector(text, offset, length);
        }
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

    // Each hit of the folded pair kernel over the literal's first bytes, with room for the whole
    // literal, is checked from foldedVerifiedBytes on, and a false hit continues the scan past it.
    // A literal shorter than nine bytes is compared whole by the kernel. The kernel reads no byte
    // at or past the end of its scan, which leaves room for the rest.
    private int findFoldedLiteralVector(Slice text, int offset, int length)
    {
        byte[] data = text.byteArray();
        int dataOffset = text.byteArrayOffset();
        int kernelBytes = Math.min(foldedBytes.length, Prog.MAX_FOLD_CASE_PREFIX_BYTES);
        int restLength = foldedBytes.length - kernelBytes;
        int position = dataOffset + offset;
        int end = position + length;
        while (end - position >= foldedBytes.length) {
            int scanLength = end - position - restLength;
            int hit = foldedDense
                    ? VectorPrefixScanner.findFoldCaseDensePair(data, position, scanLength, foldedBytes, kernelBytes, foldedFirstOffset, foldedSecondOffset)
                    : VectorPrefixScanner.findFoldCasePair(data, position, scanLength, foldedBytes, kernelBytes, foldedFirstOffset, foldedSecondOffset);
            if (hit < 0) {
                return -1;
            }
            if (foldedRestMatches(data, hit, foldedVerifiedBytes)) {
                return hit - dataOffset;
            }
            position = hit + 1;
        }
        return -1;
    }

    // Whether the literal's bytes from `firstIndex` on match at `literalStart`.
    private boolean foldedRestMatches(byte[] data, int literalStart, int firstIndex)
    {
        for (int index = firstIndex; index < foldedBytes.length; index++) {
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
        if (literalSet == null || minimumLiteralLength == maximumLiteralLength) {
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
        // Groups of one bit, as this search's filter was measured with.
        return StartLiteralFilter.build(paths, ByteFrequencies.englishText(), 1);
    }

    // The literal of the run of elements starting at `index` that are ASCII literals, folded or
    // not, and classes of exactly the two cases of an ASCII letter, up to MAXIMUM_LITERAL_LENGTH
    // bytes; the run stops at the first other element. Null when the run holds no folded letter,
    // or is one byte. A folded letter byte matches as the compiler's folded byte range does:
    // lowered, it equals the lower-case letter, which `(byte | 0x20) == letter` tests. Any other
    // folded ASCII byte matches only itself.
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
        if (headLength == length || length < 2) {
            return null;
        }
        return new FoldedLiteral(Arrays.copyOf(bytes, headLength), Arrays.copyOf(bytes, length), Arrays.copyOf(masks, length), elements);
    }

    // Appends the element's bytes and masks after `length`, and returns the new length, or -1 when
    // the element is not one the folded literal can hold or would not fit in them. Callers pass
    // `bytes` and `masks` of equal length. LiteralAlternationSpanMatcher spells the folded
    // literals of an alternation with it.
    static int appendFoldedElement(Regexp element, byte[] bytes, byte[] masks, int length)
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
        if (runes == null || runes.length == 0 || length + runes.length > bytes.length) {
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

    // A case-insensitive literal: its exact head, which may be empty, its whole bytes and masks,
    // and the number of elements it covers.
    record FoldedLiteral(byte[] head, byte[] bytes, byte[] masks, int elements) {}

    // The exact head a short window of a case-insensitive literal scans for, or null when the head
    // is empty or one common byte.
    private static byte[] searchedHead(FoldedLiteral foldedLiteral)
    {
        byte[] head = foldedLiteral.head();
        return head.length == 0 || (head.length == 1 && isCommonByte(head[0])) ? null : head;
    }

    // How the folded pair kernel scans for a case-insensitive literal; see the fields it sets.
    private record FoldedScan(int firstOffset, int secondOffset, int verifiedBytes, boolean dense)
    {
        static final FoldedScan NONE = new FoldedScan(0, 0, 0, false);

        // The kernel compares the literal's first Prog.MAX_FOLD_CASE_PREFIX_BYTES bytes under the
        // folded prefix rule, both sides with A-Z lowered. That is the literal's own rule at a
        // folded letter, which the literal holds lowered and masked, and at a byte that is no
        // letter, which matches only itself either way, but not at an exact letter, which the
        // kernel lets match its other case too. So the kernel's comparison stands for the bytes
        // before the first exact letter, and a hit is checked from there, or from the end of the
        // kernel's bytes when they hold no exact letter.
        static FoldedScan of(FoldedLiteral foldedLiteral, ByteFrequencies byteFrequencies)
        {
            byte[] bytes = foldedLiteral.bytes();
            byte[] masks = foldedLiteral.masks();
            int kernelBytes = Math.min(bytes.length, Prog.MAX_FOLD_CASE_PREFIX_BYTES);
            int verifiedBytes = 0;
            while (verifiedBytes < kernelBytes && (masks[verifiedBytes] != 0 || otherCase(bytes[verifiedBytes]) == bytes[verifiedBytes])) {
                verifiedBytes++;
            }
            Prog.FoldedPairScan pairScan = Prog.foldedPairScan(bytes, kernelBytes, byteFrequencies);
            return new FoldedScan(pairScan.firstOffset(), pairScan.secondOffset(), verifiedBytes, pairScan.dense());
        }
    }

    // The byte's other case when it is an ASCII letter, and the byte itself otherwise.
    private static byte otherCase(byte value)
    {
        int lowerCase = value | 0x20;
        return lowerCase >= 'a' && lowerCase <= 'z' ? (byte) (value ^ 0x20) : value;
    }

    private static boolean hasOneLength(byte[][] literals)
    {
        for (byte[] literal : literals) {
            if (literal.length != literals[0].length) {
                return false;
            }
        }
        return true;
    }

    // The ASCII bytes allowed after the literal, whose members' last bytes are `lastBytes`: those
    // of the first element at or after `index` that does not match only the empty string, when it
    // must consume one of a set of ASCII bytes first, since the elements before it consume
    // nothing. A word boundary or non-boundary among those elements, right after a literal whose
    // last bytes are all word bytes or all not, says whether the next character is a word
    // character, and every language reads an ASCII byte as one exactly when it is
    // `[0-9A-Za-z_]`. With an ASCII class after the boundary, the gate is the bytes both the class
    // and the boundary admit. Otherwise it is the boundary's gate alone, which also admits every
    // byte past ASCII, since that may begin or continue a character of either kind, and, as the
    // instance's `suffixGateFromWordBoundaries` records, the window's end, where the context
    // decides. Without either, or where the two admit no byte in common, there is no gate, all
    // words zero.
    private static ByteGate suffixGate(List<Regexp> children, int index, ByteGate lastBytes)
    {
        boolean lastWord = holdsOnlyAsciiWordBytes(lastBytes);
        boolean lastNonWord = holdsOnlyAsciiNonWordBytes(lastBytes);
        // The NEXT_IS_WORD and NEXT_IS_NOT_WORD flags the boundaries set.
        int nextWord = 0;
        for (int childIndex = index; childIndex < children.size(); childIndex++) {
            Regexp element = children.get(childIndex);
            if (!matchesEmptyOnly(element, true, 0)) {
                ByteGate gate = byteGate(element);
                if (nextWord == 0) {
                    return gate;
                }
                return gate.equals(ByteGate.NONE) ? wordGate(nextWord) : gate.intersect(wordGate(nextWord));
            }
            while (element.op() == RegexpOp.CAPTURE) {
                element = element.child(0);
            }
            boolean wordBoundary = isPlainWordBoundary(element);
            if ((wordBoundary || element.op() == RegexpOp.NO_WORD_BOUNDARY) && (lastWord || lastNonWord)) {
                nextWord |= lastWord == wordBoundary ? NEXT_IS_NOT_WORD : NEXT_IS_WORD;
            }
        }
        return nextWord == 0 ? ByteGate.NONE : wordGate(nextWord);
    }

    // Whether the expression is a word boundary, which every language judges by the characters
    // on either side, and not a Rust start, end, or half boundary.
    private static boolean isPlainWordBoundary(Regexp regexp)
    {
        return regexp.op() == RegexpOp.WORD_BOUNDARY && (regexp.parseFlags() & (Regexp.RUST_ASSERTION | Regexp.JAVA_WORD_BOUNDARY)) != Regexp.RUST_ASSERTION;
    }

    // Whether every byte of the gate is an ASCII word byte.
    private static boolean holdsOnlyAsciiWordBytes(ByteGate gate)
    {
        return ((gate.word0() & ~ASCII_WORD_BYTES_0) | (gate.word1() & ~ASCII_WORD_BYTES_1) | gate.word2() | gate.word3()) == 0;
    }

    // Whether every byte of the gate is an ASCII byte that is not a word byte.
    private static boolean holdsOnlyAsciiNonWordBytes(ByteGate gate)
    {
        return ((gate.word0() & ASCII_WORD_BYTES_0) | (gate.word1() & ASCII_WORD_BYTES_1) | gate.word2() | gate.word3()) == 0;
    }

    private static boolean isAsciiWordByte(byte value)
    {
        return inByteGate(value, ASCII_WORD_BYTES_0, ASCII_WORD_BYTES_1, 0, 0);
    }

    // The gate of word boundaries alone: the ASCII word bytes for `NEXT_IS_WORD`, the other ASCII
    // bytes for `NEXT_IS_NOT_WORD`, either with every byte past ASCII, and no gate for both, which
    // no position meets.
    private static ByteGate wordGate(int nextWord)
    {
        return switch (nextWord) {
            case NEXT_IS_WORD -> new ByteGate(ASCII_WORD_BYTES_0, ASCII_WORD_BYTES_1, -1L, -1L);
            case NEXT_IS_NOT_WORD -> new ByteGate(~ASCII_WORD_BYTES_0, ~ASCII_WORD_BYTES_1, -1L, -1L);
            default -> ByteGate.NONE;
        };
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

        // The bytes of the first four words of `words`.
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

        // The bytes both gates admit.
        ByteGate intersect(ByteGate other)
        {
            return new ByteGate(word0 & other.word0, word1 & other.word1, word2 & other.word2, word3 & other.word3);
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

    // meta/reverse_inner.rs has_no_earlier_match, with the helpers of meta/prefix.rs. The class-run
    // proof, which the Rust regex crate does not have, is checked apart, since it sets a set's
    // minimum window, and a search only it admits leaves the long-window check as it is without
    // the search. With `lettersEitherCase`, each ASCII letter of the literals may occur in either
    // case.
    private static boolean hasNoEarlierMatch(Regexp prefix, byte[][] literals, boolean lettersEitherCase, CaseFolding caseFolding)
    {
        if (hasLiteralOrFixedLengthProof(prefix, literals, lettersEitherCase, caseFolding)) {
            return true;
        }
        return hasDisjointClassSeparator(prefix, literals, lettersEitherCase, true);
    }

    // The crate's first two proofs, which also bound a count's match to its literal; see
    // [#countEnd]: a single literal the prefix cannot consume every byte of, or a prefix of fixed
    // length. With `lettersEitherCase`, each ASCII letter of the literal may occur in either case.
    private static boolean hasLiteralOrFixedLengthProof(Regexp prefix, byte[][] literals, boolean lettersEitherCase, CaseFolding caseFolding)
    {
        return (literals.length == 1 && !canConsumeEveryLiteralByte(prefix, literals[0], lettersEitherCase)) || MatchLength.analyze(prefix, caseFolding).fixed() >= 0;
    }

    // What a count needs to end each match at its literal's end, or null when it may not; see
    // REGULATOR_DECISIONS.md, Inner Literal Counts To The Literal End. The elements from
    // `suffixIndex` on must add nothing to a match: each matches only the empty string without an
    // assertion, or is a word boundary right after a literal whose last bytes, `lastBytes`, are
    // all ASCII word bytes, which holds where the byte after the hit is an ASCII byte that is not
    // a word byte. A match that starts at or before a hit from which the reverse search finds a
    // start at or after the search position then has its literal at that hit under
    // [#hasLiteralOrFixedLengthProof], the class-pair proof, the separator at the prefix's end
    // where the byte before the hit is ASCII, and the class-run proof for a single literal before
    // a word boundary, when every ASCII byte of the run's class is a word byte. The separator at
    // the prefix's start, and the class-run proof without the boundary, bound only the match
    // start: from the start of ` xHolmesHolmes`, `\s+\w+Holmes` matches past the first hit, and
    // from the start of `xababab`, `\w+ab` does, so a count search that stopped at the first
    // literal's end would end inside the match. Each proof compares the prefix with the literal's
    // exact bytes, so a case-insensitive literal, whose other cases they do not see, takes the
    // full confirmation.
    private static CountEnd countEnd(List<Regexp> children, int suffixIndex, ByteGate lastBytes, Regexp prefix, byte[][] literals, FoldedLiteral foldedLiteral, boolean classPair, boolean behindClassRun, CaseFolding caseFolding)
    {
        if (foldedLiteral != null) {
            return null;
        }
        boolean nonWordAfter = false;
        for (int index = suffixIndex; index < children.size(); index++) {
            Regexp element = children.get(index);
            while (element.op() == RegexpOp.CAPTURE) {
                element = element.child(0);
            }
            if (isPlainWordBoundary(element) && holdsOnlyAsciiWordBytes(lastBytes)) {
                nonWordAfter = true;
            }
            else if (!matchesEmptyOnly(element, false, 0)) {
                return null;
            }
        }
        if (behindClassRun) {
            return nonWordAfter && literals.length == 1 && hasAsciiWordRunClass(prefix) ? new CountEnd(false, true) : null;
        }
        if (hasLiteralOrFixedLengthProof(prefix, literals, false, caseFolding) || (classPair && hasNoEarlierMatchAcrossLastByte(prefix, literals))) {
            return new CountEnd(false, nonWordAfter);
        }
        return hasDisjointClassSeparator(prefix, literals, false, false) ? new CountEnd(true, nonWordAfter) : null;
    }

    // Whether every ASCII byte the class of a class-run prefix matches is a word byte. The run's
    // characters read each ASCII byte as one of their own.
    private static boolean hasAsciiWordRunClass(Regexp prefix)
    {
        Regexp leaf = concatenatedClassRun(elementsAfterLeadingEmptyOnly(prefix), 0).leaf();
        return switch (leaf.op()) {
            case LITERAL -> leaf.rune() > 0x7F || isAsciiWordByte((byte) leaf.rune());
            case CHAR_CLASS -> {
                for (int value = 0; value < 0x80; value++) {
                    if (leaf.charClass().contains(value) && !isAsciiWordByte((byte) value)) {
                        yield false;
                    }
                }
                yield true;
            }
            default -> false;
        };
    }

    // A prefix of leading assertions and then a run of one class has the property the search
    // needs. Let an earlier match start at s0, with its prefix ending at its own occurrence g, and
    // let the prefix also match [e, h) before a later occurrence h, with s0 < e <= h < g. Then it
    // matches [s0, h), so the reverse search from h finds s0 or earlier. The assertions are judged at s0 on the real text, as for [s0, g). The
    // occurrence starts with an ASCII byte, which every compiled class reads as a character of its
    // own, so [s0, h) is a shorter run of the class, of at least one character. That is enough
    // when the minimum is at most one. A larger minimum also needs [s0, h) to hold more characters
    // than [e, h), which holds only when each character is one byte: a lenient UTF-8 class may
    // read malformed bytes from e as more characters than from s0. An assertion after the run, or
    // a second class, would let [s0, h) fail where [s0, g) matched.
    private static boolean isClassRunPrefix(Regexp prefix)
    {
        ClassRun run = concatenatedClassRun(elementsAfterLeadingEmptyOnly(prefix), 0);
        return run != null && run.leaf() != null && (run.minimum() <= 1 || hasOneByteCharacters(run.leaf()));
    }

    // The elements of the prefix's top-level concatenation, its groups looked through, from the
    // first that does not match only the empty string, as an assertion does.
    private static List<Regexp> elementsAfterLeadingEmptyOnly(Regexp prefix)
    {
        while (prefix.op() == RegexpOp.CAPTURE) {
            prefix = prefix.child(0);
        }
        List<Regexp> elements = prefix.op() == RegexpOp.CONCAT ? prefix.children() : List.of(prefix);
        int first = 0;
        while (first < elements.size() && matchesEmptyOnly(elements.get(first), true, 0)) {
            first++;
        }
        return elements.subList(first, elements.size());
    }

    // A run of one class: an expression that matches one character of the class, or null when
    // the run matches only the empty string, and the least number of characters. The run matches
    // every number of characters from its minimum up to its greatest, which the proof never needs.
    private record ClassRun(Regexp leaf, int minimum) {}

    // The run `regexp` matches, or null when it is not a run of one class or the depth limit is
    // reached.
    private static ClassRun classRun(Regexp regexp, int depth)
    {
        if (depth >= MAX_RECURSIVE_DEPTH) {
            return null;
        }
        return switch (regexp.op()) {
            case EMPTY_MATCH -> new ClassRun(null, 0);
            case CHAR_CLASS, ANY_CHAR, ANY_BYTE -> new ClassRun(regexp, 1);
            case LITERAL -> (regexp.parseFlags() & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) == 0 ? new ClassRun(regexp, 1) : null;
            case CAPTURE -> classRun(regexp.child(0), depth + 1);
            case CONCAT -> concatenatedClassRun(regexp.children(), depth + 1);
            case STAR, PLUS, QUEST, REPEAT -> repeatedClassRun(regexp, depth + 1);
            default -> null;
        };
    }

    // The counts of a concatenation add, as for the copies the simplifier makes of a counted
    // repetition: C{2,5} becomes C C (?:C(?:C(?:C)?)?)?.
    private static ClassRun concatenatedClassRun(List<Regexp> elements, int depth)
    {
        Regexp leaf = null;
        int minimum = 0;
        for (Regexp element : elements) {
            ClassRun run = classRun(element, depth);
            if (run == null) {
                return null;
            }
            if (leaf == null) {
                leaf = run.leaf();
            }
            else if (run.leaf() != null && !isSameClass(leaf, run.leaf())) {
                return null;
            }
            minimum = addCounts(minimum, run.minimum());
        }
        return new ClassRun(leaf, minimum);
    }

    // A repetition of a contiguous run whose minimum is at most one is contiguous again: each
    // count of the body overlaps or adjoins the next. A larger minimum may leave gaps: (?:CC)+
    // matches two or four characters but not three. Admitted, (?:[a-z]{2})+(?:foo|bar) would match
    // yzfoo from the first hit in xyzfoozzbar, where the engine matches the whole of it. With a
    // body minimum of zero or one, the repetition's minimum is its count's minimum or zero.
    private static ClassRun repeatedClassRun(Regexp repetition, int depth)
    {
        ClassRun body = classRun(repetition.child(0), depth);
        if (body == null || body.minimum() > 1) {
            return null;
        }
        return switch (repetition.op()) {
            case STAR, QUEST -> new ClassRun(body.leaf(), 0);
            case PLUS -> body;
            default -> new ClassRun(body.leaf(), repetition.min() * body.minimum());
        };
    }

    // Saturating keeps a long concatenation's minimum from wrapping around to a small one.
    private static int addCounts(int left, int right)
    {
        return (int) Math.min((long) left + right, Integer.MAX_VALUE);
    }

    // Two leaves are one class when they compile to the same byte sequences: the same operation,
    // the same encoding, and the same runes. A leaf literal never folds case.
    private static boolean isSameClass(Regexp left, Regexp right)
    {
        if (left.op() != right.op() || ((left.parseFlags() ^ right.parseFlags()) & Regexp.LATIN1) != 0) {
            return false;
        }
        return switch (left.op()) {
            case LITERAL -> left.rune() == right.rune();
            case CHAR_CLASS -> left.charClass().equals(right.charClass());
            default -> true;
        };
    }

    // Latin-1 and Rust bytes classes read one byte per character, as does UTF-8 for ASCII runes.
    private static boolean hasOneByteCharacters(Regexp leaf)
    {
        if ((leaf.parseFlags() & Regexp.LATIN1) != 0) {
            return true;
        }
        return switch (leaf.op()) {
            case ANY_BYTE -> true;
            case LITERAL -> leaf.rune() <= 0x7F;
            case CHAR_CLASS -> !hasNonAscii(leaf.charClass());
            default -> false;
        };
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
        List<Regexp> elements = elementsAfterLeadingEmptyOnly(prefix);
        if (elements.isEmpty() || asciiByteSet(elements.get(0), 0x80) == null) {
            return false;
        }
        for (int index = 1; index < elements.size(); index++) {
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
    //
    // A case-insensitive literal without an exact head is judged with `lettersEitherCase`: the
    // prefix must consume one case or the other of each letter, exact letters too. Let the prefix
    // span a later occurrence at h before its own at g, consuming [h, g). Apart, the occurrences
    // put a whole occurrence there, each byte in a case its position allows. Overlapping, the
    // byte at h + i for i >= g - h is also the byte at g + i - (g - h), so positions i and
    // i - (g - h) allow a common byte, each of them a case of one letter or one other byte, and
    // stepping down by g - h reaches a consumed byte below g - h, a case of the same letter. That
    // case need not be the one position i allows, as in `[Aa]a`, so both are counted. In
    // `(?:QHO)?(?i:ho)` the prefix consumes `H` and `O`, and on `QHOho` the reverse search from
    // `HO` would find its start, confirm `HO`, and miss the earlier match `QHOho`.
    private static boolean canConsumeEveryLiteralByte(Regexp regexp, byte[] literal, boolean lettersEitherCase)
    {
        for (byte value : literal) {
            if (!canConsumeByte(regexp, value, 0) && !(lettersEitherCase && canConsumeByte(regexp, otherCase(value), 0))) {
                return false;
            }
        }
        return true;
    }

    // The literal is ASCII, so an exact non-ASCII rune, whose UTF-8 bytes are all non-ASCII, never
    // consumes one of its bytes. A folded literal consumes any byte one of its runes can; a class
    // lists every rune it matches.
    private static boolean canConsumeByte(Regexp regexp, byte value, int depth)
    {
        if (depth >= MAX_RECURSIVE_DEPTH) {
            return true;
        }
        return switch (regexp.op()) {
            case NO_MATCH, EMPTY_MATCH, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT, HAVE_MATCH -> false;
            case LITERAL -> runeCanConsumeByte(regexp.rune(), regexp.parseFlags(), value);
            case LITERAL_STRING -> {
                for (int rune : regexp.runes()) {
                    if (runeCanConsumeByte(rune, regexp.parseFlags(), value)) {
                        yield true;
                    }
                }
                yield false;
            }
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

    // The value is an ASCII byte of a literal. An exact rune consumes only itself. A folded ASCII
    // rune consumes its own byte and, for a letter, the other case's, because no fold rule joins
    // two ASCII runes outside a case pair and no ASCII rune has a multi-character fold. A folded
    // non-ASCII rune can fold to ASCII, as the Kelvin sign does to k, so it is assumed to consume
    // any byte.
    private static boolean runeCanConsumeByte(int rune, int flags, byte value)
    {
        if ((flags & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) == 0) {
            return rune == value;
        }
        if (rune > 0x7F) {
            return true;
        }
        return rune == value || (isAsciiLetter(rune) && (rune | 0x20) == (value | 0x20));
    }

    private static boolean isAsciiLetter(int rune)
    {
        return ('a' <= rune && rune <= 'z') || ('A' <= rune && rune <= 'Z');
    }

    // A required class at either end of the prefix, or only at its last when `includeFirst` is
    // false, disjoint from the rest of the prefix and from every literal, separates the match
    // start from each candidate: a reverse search cannot slide it across either one. The ends skip
    // elements, such as assertions, that match only the empty string. With `lettersEitherCase`,
    // the class must hold neither case of a literal's letter.
    private static boolean hasDisjointClassSeparator(Regexp prefix, byte[][] literals, boolean lettersEitherCase, boolean includeFirst)
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
            if (!matchesEmptyOnly(children.get(index), true, 0)) {
                if (first < 0) {
                    first = index;
                }
                last = index;
            }
        }
        if (first < 0) {
            return false;
        }
        return hasDisjointClassSeparatorAt(children, last, literals, lettersEitherCase) || (includeFirst && first != last && hasDisjointClassSeparatorAt(children, first, literals, lettersEitherCase));
    }

    private static boolean hasDisjointClassSeparatorAt(List<Regexp> children, int separatorIndex, byte[][] literals, boolean lettersEitherCase)
    {
        Regexp separator = requiredClass(children.get(separatorIndex));
        if (separator == null) {
            return false;
        }
        for (byte[] literal : literals) {
            for (byte value : literal) {
                if (separator.charClass().contains(value) || (lettersEitherCase && separator.charClass().contains(otherCase(value)))) {
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

    // Whether the expression matches only the empty string, an assertion counting as one that
    // does only when `assertionsMatchEmpty` is set.
    private static boolean matchesEmptyOnly(Regexp regexp, boolean assertionsMatchEmpty, int depth)
    {
        if (depth >= MAX_RECURSIVE_DEPTH) {
            return false;
        }
        return switch (regexp.op()) {
            case EMPTY_MATCH -> true;
            case BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT -> assertionsMatchEmpty;
            case CAPTURE -> matchesEmptyOnly(regexp.child(0), assertionsMatchEmpty, depth + 1);
            case CONCAT -> {
                for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
                    if (!matchesEmptyOnly(regexp.child(childIndex), assertionsMatchEmpty, depth + 1)) {
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
