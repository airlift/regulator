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
import io.airlift.slice.Slice;
import io.airlift.slice.SliceUtf8;

import java.util.HashMap;
import java.util.Map;

import static io.airlift.regulator.CompiledPattern.Anchor.ANCHOR_BOTH;
import static io.airlift.regulator.CompiledPattern.Anchor.ANCHOR_START;
import static io.airlift.regulator.CompiledPattern.Anchor.UNANCHORED;
import static java.util.Objects.requireNonNull;

/// A reusable matcher for one compiled RE2, Java, or Rust pattern, and the matcher that
/// [TrinoRegexpMatcher] wraps.
///
/// The matcher owns one capture buffer and reuses it across operations. It is mutable and not
/// thread-safe. Group values are zero-copy views of the current input.
public final class RegexpMatcher
        implements CompiledPattern.MatchWorkspaces
{
    // A scan for the required literal or one-of bytes that finds them within
    // NEAR_REQUIRED_LITERAL_BYTES of where the find starts saves nothing: the engine reads those
    // bytes anyway, and the scan paid its setup. After NEAR_REQUIRED_LITERAL_SCAN_LIMIT such
    // scans in a row, the matcher stops scanning until the next reset, since on a region that
    // dense the check cannot pay. RE2's replaceAll applies the same rule to its loop; see
    // CompiledPattern.nearRequiredLiteralScansInLoop.
    static final int NEAR_REQUIRED_LITERAL_BYTES = 256;
    static final int NEAR_REQUIRED_LITERAL_SCAN_LIMIT = 8;

    private final CompiledPattern pattern;
    private final int[] groups;
    private final Map<String, Integer> namedCapturingGroups;
    private final boolean latin1;
    // The pattern's single-byte table, which a group-zero find runs in place of the program on
    // fewer than CompiledPattern.MINIMUM_LONG_SEARCH_BYTES bytes. See
    // CompiledPattern.sharedSingleByteMatcher.
    private final SingleByteMatcher singleByteMatcher;
    // The pattern's literal-alternation, literal-set, dot-star literal, or fixed-width span search,
    // which find() runs in place of the program, a literal alternation's only on fewer than
    // CompiledPattern.MINIMUM_LONG_SEARCH_BYTES bytes and a short-search fixed-width matcher's
    // only on searches too short for the inner literal search. See
    // CompiledPattern.groupZeroSpanSearch.
    private final GroupZeroSpanSearch spanSearch;
    private final BoundedCharacterClassCounter characterClassCounter;
    // Created by the constructor for a matcher with no single-byte table, span search, or class
    // counter, by a literal alternation's matcher on its first long search, and by a fixed-width
    // matcher on its first search that bails on dense candidates. See
    // createLongSearchCandidateStartCursor.
    private Dfa.CandidateStartCursor candidateStartCursor;
    private boolean longSearchCandidateStartCursorCreated;
    private final boolean enableGroupZeroForwardCursor;
    private final boolean hasLoweredCandidateSearch;
    private final boolean hasDirectLoweredGroupZeroSearch;
    // The pattern's start-anchored scan plan that find() can run directly. See
    // CompiledPattern.startAnchoredScanPlan.
    private final ScanPlan startAnchoredScanPlan;
    private final boolean rustSearch;
    private final boolean readsCarriageReturnBeforeRegion;
    // The fewest bytes a match spans, so findGeneral fails a find whose rest of the region is
    // shorter before any engine runs.
    private final int minimumMatchLength;
    // An upper bound on the bytes a region can have and still hold a match, so findGeneral fails
    // a find of a longer region before any engine runs. See CompiledPattern.maximumSpanLength.
    private final int maximumSpanLength;
    // The pattern's required literal, or one-of bytes, that findGeneral checks the rest of a long
    // region for, or null. See CompiledPattern.longWindowRequiredLiteral.
    private final RequiredBytes longWindowRequiredLiteral;
    private OnePass.Workspace onePassWorkspace;
    private BitState.Workspace bitStateWorkspace;
    private Nfa.Workspace nfaWorkspace;
    private PositionNfa.Workspace positionNfaWorkspace;

    private Slice input;
    private int regionStart;
    private int regionEnd;
    private int nextFindStart;
    // Where find() searches again after a failed find, matches, or lookingAt: the region-relative
    // end of the last match, or 0 when nothing has matched since the last reset.
    private int retryStart;
    private boolean hasMatch;
    private boolean groupZeroForwardCursorInitialized;
    // The region-relative end of the last match a Rust search returned. It starts at -1 so Rust can
    // return a first empty match at 0, and failures keep it so a repeated empty match stays
    // skipped. In contrast, retryStart starts at 0 and records every match.
    private int previousMatchEnd = -1;
    // Set at reset when the region starts on the LF of a CR LF and the pattern's Java $ reads that
    // CR, so every search of the region runs on the NFA. See
    // CompiledPattern.matchRegionAfterCarriageReturnInto.
    private boolean regionStartsAfterCarriageReturn;
    // The consecutive searches of the current input whose DFA failed, in a program whose DFA can
    // quit on a byte outside ASCII. See CompiledPattern.DFA_QUIT_STREAK_LIMIT.
    private int dfaQuitStreak;
    // Set when the current find's candidate cursor quit, so its fallback skips the DFA: the quit
    // already counted toward the streak.
    private boolean candidateStartCursorQuit;
    // Set by a count whose DFA count bailed, so this matcher, which counts that call's matches,
    // skips the forward DFA, the inner-literal search, the end-anchored reverse DFA, the candidate
    // start cursor, and the group-zero forward cursor rather than filling the cache again for
    // each match. Cleared at reset.
    private boolean skipsForwardDfa;
    private Dfa.GroupZeroForwardCursor groupZeroForwardCursor;
    // The region-relative start of the occurrence of the pattern's long-window required literal
    // or one-of bytes that the last scan of the rest of the region found, or -1 when no scan has
    // run since the last reset or the last one found none. See restOfRegionLacksRequiredLiteral.
    private int requiredLiteralOccurrence;
    // The scans in a row since the last reset that found the literal or one-of bytes within
    // NEAR_REQUIRED_LITERAL_BYTES of their start.
    private int nearRequiredLiteralScans;
    // The scans for the long-window required literal or one-of bytes, across resets, for
    // diagnostics.
    private long requiredLiteralScanCount;

    RegexpMatcher(CompiledPattern pattern, Slice input, SingleByteMatcher singleByteMatcher)
    {
        this(pattern, input, singleByteMatcher, pattern.capturingGroupCount());
    }

    RegexpMatcher(CompiledPattern pattern, Slice input, SingleByteMatcher singleByteMatcher, int capturingGroupCount)
    {
        this(pattern, input, singleByteMatcher, capturingGroupCount, true);
    }

    /// `enableSpecializedGroupZeroRoutes` false turns off the group-zero forward cursor, the class
    /// counter, and the span search of [#find()], for comparisons.
    RegexpMatcher(CompiledPattern pattern, Slice input, SingleByteMatcher singleByteMatcher, int capturingGroupCount, boolean enableSpecializedGroupZeroRoutes)
    {
        this.pattern = requireNonNull(pattern, "pattern is null");
        checkCapturingGroupCount(pattern, capturingGroupCount);
        this.groups = new int[2 * (capturingGroupCount + 1)];
        this.namedCapturingGroups = retainedNamedCapturingGroups(pattern, capturingGroupCount);
        this.latin1 = (pattern.flags() & Regexp.LATIN1) != 0;
        this.singleByteMatcher = capturingGroupCount == 0 ? singleByteMatcher : null;
        // The table passed in is the pattern's own, and groupZeroSpanSearch() returns no span
        // search beside it or a class counter.
        this.spanSearch = capturingGroupCount == 0 && enableSpecializedGroupZeroRoutes ? pattern.groupZeroSpanSearch() : null;
        this.characterClassCounter = capturingGroupCount == 0 && enableSpecializedGroupZeroRoutes ? pattern.classMatchCounter() : null;
        this.candidateStartCursor = capturingGroupCount == 0 && this.singleByteMatcher == null && spanSearch == null && characterClassCounter == null ? pattern.createCandidateStartCursor() : null;
        this.enableGroupZeroForwardCursor = enableSpecializedGroupZeroRoutes &&
                characterClassCounter == null &&
                pattern.canUseGroupZeroForwardCursor(groups.length);
        this.hasLoweredCandidateSearch = pattern.hasLoweredCandidateSearch();
        this.hasDirectLoweredGroupZeroSearch = capturingGroupCount == 0 && pattern.hasDirectLoweredGroupZeroSearch();
        this.startAnchoredScanPlan = this.singleByteMatcher == null ? pattern.startAnchoredScanPlan() : null;
        this.rustSearch = (pattern.flags() & Regexp.RUST_SEARCH) != 0;
        this.readsCarriageReturnBeforeRegion = pattern.readsCarriageReturnBeforeRegion();
        this.minimumMatchLength = pattern.minimumMatchLength();
        this.maximumSpanLength = pattern.maximumSpanLength();
        this.longWindowRequiredLiteral = pattern.longWindowRequiredLiteral();
        reset(input);
    }

    /// Checks the retained group count out of line so that the constructor stays small enough for
    /// C2 to inline.
    private static void checkCapturingGroupCount(CompiledPattern pattern, int capturingGroupCount)
    {
        if (capturingGroupCount < 0 || capturingGroupCount > pattern.capturingGroupCount()) {
            throw new IllegalArgumentException("capturingGroupCount out of range: " + capturingGroupCount);
        }
    }

    private static Map<String, Integer> retainedNamedCapturingGroups(CompiledPattern pattern, int capturingGroupCount)
    {
        if (capturingGroupCount == 0) {
            return Map.of();
        }
        Map<String, Integer> namedCapturingGroups = pattern.namedCapturingGroups();
        if (capturingGroupCount == pattern.capturingGroupCount()) {
            return namedCapturingGroups;
        }

        Map<String, Integer> retainedGroups = new HashMap<>();
        for (Map.Entry<String, Integer> entry : namedCapturingGroups.entrySet()) {
            if (entry.getValue() <= capturingGroupCount) {
                retainedGroups.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(retainedGroups);
    }

    /// Resets this matcher to the complete logical input Slice.
    public RegexpMatcher reset(Slice input)
    {
        requireNonNull(input, "input is null");
        return reset(input, 0, input.length());
    }

    /// Resets this matcher to a logical input region without constructing a Slice view. Match and
    /// group offsets remain relative to the logical input Slice.
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "The matcher intentionally retains the caller's Slice for zero-copy matching")
    public RegexpMatcher reset(Slice input, int start, int end)
    {
        requireNonNull(input, "input is null");
        if (start < 0 || end < start || end > input.length()) {
            throw new IndexOutOfBoundsException("region out of bounds: [" + start + ", " + end + ")");
        }
        this.input = input;
        regionStart = start;
        regionEnd = end;
        regionStartsAfterCarriageReturn = readsCarriageReturnBeforeRegion && start > 0 && start < end && input.getByte(start - 1) == '\r' && input.getByte(start) == '\n';
        resetSearchState();
        return this;
    }

    private void resetSearchState()
    {
        if (candidateStartCursor != null) {
            candidateStartCursor.reset(regionLength());
        }
        if (groupZeroForwardCursor != null) {
            groupZeroForwardCursor.reset();
        }
        else {
            groupZeroForwardCursorInitialized = false;
        }
        nextFindStart = 0;
        retryStart = 0;
        previousMatchEnd = -1;
        dfaQuitStreak = 0;
        candidateStartCursorQuit = false;
        requiredLiteralOccurrence = -1;
        nearRequiredLiteralScans = 0;
        skipsForwardDfa = false;
        invalidateMatch();
    }

    /// Finds the next non-overlapping match in the current region. After a failed `find`,
    /// `matches`, or `lookingAt` it searches again from the end of the last match, or from the
    /// region start when nothing has matched since the last reset. Right after an empty match at
    /// the region end it, and every later find, fails without searching.
    public boolean find()
    {
        // Kept small enough for C2 to inline; each route stays out of line.
        if (startAnchoredScanPlan != null) {
            return findWithStartAnchoredScanPlan();
        }
        if (spanSearch != null) {
            return findSpanSearch();
        }
        if (pattern.hasAnchoredSubmatchRoute()) {
            return findAnchoredSubmatch();
        }
        if (rustSearch) {
            return findRust();
        }
        return findGeneral();
    }

    /// A span search's patterns have no assertions, match no empty string, and read no context
    /// outside the region, so every language's search, a Rust one over a partial region included,
    /// is the span search of the rest of the region.
    ///
    /// The search is dispatched on its class rather than through an interface method; see
    /// [GroupZeroSpanSearch].
    private boolean findSpanSearch()
    {
        int start = regionStart + nextFindStart;
        long span;
        if (spanSearch instanceof LiteralAlternationSpanMatcher literalAlternation) {
            // A region with at least MINIMUM_LONG_SEARCH_BYTES left takes the program's search,
            // whose start-byte scan the span search replaced: the DFA candidate cursor where the
            // literals differ in length, and the group-zero forward cursor where they share one.
            // Without it, a matcher's finds of ERROR|WARN|FATAL took 2.0x the time on r9g and
            // 4.4-4.6x on r8i on 1 KiB rows; 16 to 64 byte rows measured flat.
            if (regionEnd - start >= CompiledPattern.MINIMUM_LONG_SEARCH_BYTES) {
                createLongSearchCandidateStartCursor();
                return rustSearch ? findRust() : findGeneral();
            }
            // With fewer bytes left, the span search takes the root-table loop and carries no
            // route from one find to the next.
            span = literalAlternation.findSpan(input, start, regionEnd);
        }
        else if (spanSearch instanceof LiteralSetSpanMatcher literalSet) {
            span = literalSet.findSpan(input, start, regionEnd);
        }
        else if (spanSearch instanceof DotStarLiteralSpanMatcher dotStarLiteral) {
            span = dotStarLiteral.findSpan(input, start, regionEnd);
        }
        else {
            // The short-search matcher answers only regions too short for the inner literal search;
            // a longer one goes to the engine, which runs that search. See
            // CompiledPattern.shortFixedWidthByteMatcher.
            if (spanSearch == pattern.shortFixedWidthByteMatcher() && pattern.searchesInnerLiteralWindow(regionEnd - start)) {
                return rustSearch ? findRust() : findGeneral();
            }
            // A search that bails on dense candidates runs again from its start on the program's
            // search, as a find of the pattern without the matcher would.
            span = ((FixedWidthByteSpanMatcher) spanSearch).findSpanOrBail(input, start, regionEnd);
            if (span == FixedWidthByteSpanMatcher.BAILED) {
                createLongSearchCandidateStartCursor();
                return rustSearch ? findRust() : findGeneral();
            }
        }
        if (span < 0) {
            return failFind();
        }
        groups[0] = (int) (span >>> 32) - regionStart;
        groups[1] = (int) span - regionStart;
        setMatchAndAdvance(groups[0], groups[1]);
        return true;
    }

    /// The start-anchored plan can match only when the search starts at the region start. The
    /// plan reads the region as its whole input, but a Rust search still sees the rest of the
    /// input, so a Rust search of a partial region takes [#findRust], which reaches the plan only
    /// when the region ends at the input end. A region whose Java $ reads a CR before it takes
    /// [#findGeneral].
    private boolean findWithStartAnchoredScanPlan()
    {
        if (rustSearch && isPartialRegion()) {
            return findRust();
        }
        if (regionStartsAfterCarriageReturn) {
            return findGeneral();
        }
        if (nextFindStart == 0 && pattern.findWithStartAnchoredScanPlan(startAnchoredScanPlan, input, regionStart, regionEnd, groups)) {
            setMatchAndAdvance(groups[0], groups[1]);
            return true;
        }
        return failFind();
    }

    /// A start-anchored pattern can match only at the input start, so a find over the whole input,
    /// from the input start with nothing matched since reset and with groups to fill, goes
    /// straight to the submatch engine. The pattern never matches empty, so the Rust empty-match
    /// rule never applies. Every other find takes the usual route.
    private boolean findAnchoredSubmatch()
    {
        if (nextFindStart != 0 || groups.length == 2 || isPartialRegion()) {
            return rustSearch ? findRust() : findGeneral();
        }
        if (!pattern.matchAnchoredSubmatch(input, groups, this)) {
            return failFind();
        }
        previousMatchEnd = groups[1];
        setMatchAndAdvance(groups[0], groups[1]);
        return true;
    }

    /// The Rust crate skips an empty match that ends where the previous match ended or, except in
    /// its bytes API, that splits a UTF-8 character.
    private boolean findRust()
    {
        while (findGeneral()) {
            int matchEnd = groups[1];
            if (groups[0] != matchEnd || (matchEnd != previousMatchEnd && (latin1 || Utf8.isCharacterBoundary(input, regionStart + matchEnd)))) {
                previousMatchEnd = matchEnd;
                return true;
            }
        }
        return false;
    }

    /// Creates the candidate cursor the first time a span search hands a find to the program:
    /// when a literal alternation's matcher, whose span search answers its short searches,
    /// searches a long region, or when a fixed-width matcher's search bails on dense candidates.
    /// Creating it builds the program's DFA, so a matcher whose span search answers every find
    /// builds none. An alternation whose literals share one length has no candidate cursor and
    /// takes the group-zero forward cursor instead.
    private void createLongSearchCandidateStartCursor()
    {
        if (!longSearchCandidateStartCursorCreated) {
            longSearchCandidateStartCursorCreated = true;
            candidateStartCursor = pattern.createCandidateStartCursor();
            if (candidateStartCursor != null) {
                candidateStartCursor.reset(regionLength());
            }
        }
    }

    private boolean findGeneral()
    {
        candidateStartCursorQuit = false;
        int regionLength = regionLength();
        if (nextFindStart > regionLength) {
            invalidateMatch();
            return false;
        }
        // A match of a pattern anchored at both ends spans all of its context but a final line
        // terminator, and the context holds the region. A match lies inside the rest of the
        // region, whatever context a Rust search reads.
        if (regionLength - nextFindStart < minimumMatchLength || regionLength > maximumSpanLength) {
            return failFind();
        }
        // So do the bytes every match holds.
        if (pattern.shortWindowLacksRequiredBytes(input, regionStart + nextFindStart, regionEnd)) {
            return failFind();
        }
        if (restOfRegionLacksRequiredLiteral()) {
            return failFind();
        }

        if (rustSearch && isPartialRegion()) {
            return findNextWithInputContext();
        }

        if (regionStartsAfterCarriageReturn) {
            return findNextAfterCarriageReturn();
        }

        // The table reads every byte, so a search with at least MINIMUM_LONG_SEARCH_BYTES left
        // takes the group-zero forward cursor, whose vector byte scan, which the table had
        // replaced, skips about 1013 bytes of each 1024-byte benchmark row the table read whole.
        if (singleByteMatcher != null && regionEnd - (regionStart + nextFindStart) < CompiledPattern.MINIMUM_LONG_SEARCH_BYTES) {
            int matchStart = singleByteMatcher.find(input, regionStart + nextFindStart, regionEnd);
            if (matchStart < 0) {
                return failFind();
            }
            matchStart -= regionStart;
            groups[0] = matchStart;
            groups[1] = matchStart + 1;
            nextFindStart = matchStart + 1;
            retryStart = matchStart + 1;
            hasMatch = true;
            return true;
        }

        // The counter finds a class pattern's next match directly: one member, or a run of members
        // cut at the repeat's maximum when it has one.
        if (characterClassCounter != null) {
            long span = characterClassCounter.findSpan(input, regionStart, regionEnd, regionStart + nextFindStart);
            if (span < 0) {
                return failFind();
            }
            groups[0] = (int) (span >>> 32);
            groups[1] = (int) span;
            setMatchAndAdvance(groups[0], groups[1]);
            return true;
        }

        if (pattern.canReturnEmptyAtStart(input, regionStart, regionEnd, regionStart + nextFindStart)) {
            groups[0] = nextFindStart;
            groups[1] = nextFindStart;
            setMatchAndAdvance(nextFindStart, nextFindStart);
            return true;
        }

        // After the quit streak reaches its limit, a cursor whose DFA can quit is skipped with the
        // DFA until the next reset, and a matcher counting after its count's DFA bailed skips the
        // cursor, which runs the forward DFA.
        boolean candidateCursorEnabled = candidateStartCursor != null && candidateStartCursor.enabled() && !skipsForwardDfa &&
                !(dfaQuitStreak >= CompiledPattern.DFA_QUIT_STREAK_LIMIT && candidateStartCursor.quitsOnNonAscii());
        boolean groupZeroForwardCursorEnabled = enableGroupZeroForwardCursor && !skipsForwardDfa;
        // Where the inner literal lies matters only to a cursor the inner literal search would replace.
        boolean searchesInnerLiteral = (candidateCursorEnabled || groupZeroForwardCursorEnabled) && pattern.searchesInnerLiteral(input, regionStart + nextFindStart, regionEnd);
        if (!searchesInnerLiteral && candidateCursorEnabled) {
            long candidateBoundary = Dfa.searchCandidateBoundary(
                    candidateStartCursor,
                    input,
                    regionStart,
                    regionEnd,
                    regionStart + nextFindStart);
            while (Dfa.isQuitCandidate(candidateBoundary)) {
                candidateBoundary = settleQuitCandidate(Dfa.quitCandidateStart(candidateBoundary));
            }
            if (candidateBoundary >= 0) {
                // A completed cursor search, or a match settled at a quit candidate, ends the streak.
                dfaQuitStreak = 0;
                groups[0] = (int) (candidateBoundary >>> 32);
                groups[1] = (int) candidateBoundary;
                setMatchAndAdvance(groups[0], groups[1]);
                return true;
            }
            if (candidateBoundary == Dfa.SEARCH_NO_MATCH) {
                dfaQuitStreak = 0;
                return failFind();
            }
            if (candidateBoundary == Dfa.CANDIDATE_SEARCH_QUIT) {
                dfaQuitStreak++;
                candidateStartCursorQuit = true;
            }
        }

        Dfa.GroupZeroForwardCursor groupZeroForwardCursor = groupZeroForwardCursorEnabled && !searchesInnerLiteral ? groupZeroForwardCursor() : null;
        if (groupZeroForwardCursor != null) {
            long matchBoundary = pattern.findGroupZeroBoundary(
                    groupZeroForwardCursor,
                    input,
                    regionStart,
                    regionEnd,
                    regionStart + nextFindStart);
            if (matchBoundary >= 0) {
                groups[0] = (int) (matchBoundary >>> 32);
                groups[1] = (int) matchBoundary;
                if (groups.length == 2 || pattern.materializeTaggedAlternation(
                        input,
                        regionStart,
                        regionEnd,
                        regionStart + groups[0],
                        regionStart + groups[1],
                        groups)) {
                    setMatchAndAdvance(groups[0], groups[1]);
                    return true;
                }
            }
            if (matchBoundary == Dfa.SEARCH_NO_MATCH) {
                return failFind();
            }
        }

        int searchStart = regionStart + nextFindStart;
        if (hasDirectLoweredGroupZeroSearch) {
            long matchBoundary = pattern.findLoweredGroupZeroBoundary(
                    input,
                    regionStart,
                    regionEnd,
                    searchStart);
            if (matchBoundary >= 0) {
                groups[0] = (int) (matchBoundary >>> 32);
                groups[1] = (int) matchBoundary;
                setMatchAndAdvance(groups[0], groups[1]);
                return true;
            }
            if (matchBoundary == Dfa.SEARCH_NO_MATCH) {
                return failFind();
            }
        }

        long loweredCandidateStart = Dfa.SEARCH_FAILED;
        if (hasLoweredCandidateSearch) {
            loweredCandidateStart = pattern.findLoweredCandidateStart(input, regionStart, regionEnd, searchStart);
        }
        if (loweredCandidateStart == Dfa.SEARCH_NO_MATCH) {
            return failFind();
        }
        if (loweredCandidateStart >= 0) {
            searchStart += (int) loweredCandidateStart;
        }

        if (!pattern.matchRegionInto(
                input,
                regionStart,
                regionEnd,
                searchStart,
                regionEnd,
                UNANCHORED,
                groups,
                this)) {
            // The lowered program preserves existence, but the semantic program remains authoritative
            // for boundaries and captures. Fall back if a future lowering does not preserve its start.
            if (loweredCandidateStart >= 0 && searchStart != regionStart + nextFindStart &&
                    pattern.matchRegionInto(
                            input,
                            regionStart,
                            regionEnd,
                            regionStart + nextFindStart,
                            regionEnd,
                            UNANCHORED,
                            groups,
                            this)) {
                setMatchAndAdvance(groups[0], groups[1]);
                return true;
            }
            return failFind();
        }

        setMatchAndAdvance(groups[0], groups[1]);
        return true;
    }

    /// Settles `candidate`, the region-relative start of the candidate whose check quit in the
    /// candidate cursor's last search, and returns the cursor's result from there: the match
    /// starting at the candidate, or the cursor's search from the byte after it. Every start
    /// before the candidate holds no match, so this find's other routes search from the candidate
    /// on, and from the byte after it once it is settled. The match is found on the engines that
    /// decode the text, over at most the bytes the longest match spans, which the cursor charges
    /// to its work budget; where the budget cannot cover them, the cursor falls back. A settled
    /// quit does not count toward the quit streak, and a settled match ends it.
    private long settleQuitCandidate(int candidate)
    {
        nextFindStart = candidate;
        int windowEnd = (int) Math.min(regionLength(), (long) candidate + pattern.maximumMatchLength());
        if (!candidateStartCursor.chargeQuitCandidate(windowEnd - candidate)) {
            return Dfa.CANDIDATE_SEARCH_FALLBACK;
        }
        if (pattern.matchQuitCandidateInto(input, regionStart, regionEnd, regionStart + candidate, regionStart + windowEnd, groups, this)) {
            return ((long) groups[0] << 32) | groups[1];
        }
        nextFindStart = candidate + 1;
        return Dfa.searchCandidateBoundary(candidateStartCursor, input, regionStart, regionEnd, regionStart + nextFindStart);
    }

    /// Whether the rest of the region, at least [RequiredBytes#SHORT_WINDOW_LIMIT] bytes, lacks
    /// the literal every match consumes, or every one of the one-of bytes, for a pattern whose
    /// long windows are checked for them; see [CompiledPattern#longWindowLacksRequiredLiteral]. A find that has not passed the
    /// occurrence the last scan found reuses it, so a find loop scans at most once per match:
    /// again only once a match has passed the occurrence. A reused occurrence only lets the
    /// engine run, so it stays right even if the input changes; a scan that found nothing is not
    /// reused, and every find reads the input, as the engine does. After
    /// [#NEAR_REQUIRED_LITERAL_SCAN_LIMIT] scans in a row find the literal or one-of bytes near
    /// their start, the finds until the next reset skip the check.
    private boolean restOfRegionLacksRequiredLiteral()
    {
        RequiredBytes literal = longWindowRequiredLiteral;
        int from = nextFindStart;
        if (literal == null || regionLength() - from < RequiredBytes.SHORT_WINDOW_LIMIT || requiredLiteralOccurrence >= from) {
            return false;
        }
        if (nearRequiredLiteralScans >= NEAR_REQUIRED_LITERAL_SCAN_LIMIT) {
            return false;
        }
        requiredLiteralScanCount++;
        int occurrence = literal.indexIn(input, regionStart + from, regionEnd);
        requiredLiteralOccurrence = occurrence < 0 ? -1 : occurrence - regionStart;
        nearRequiredLiteralScans = occurrence >= 0 && requiredLiteralOccurrence - from < NEAR_REQUIRED_LITERAL_BYTES ? nearRequiredLiteralScans + 1 : 0;
        return occurrence < 0;
    }

    // A Rust search of a region still sees the input outside it, so it bypasses the region-context
    // fast paths.
    private boolean findNextWithInputContext()
    {
        if (!pattern.matchRegionInto(
                input,
                0,
                input.length(),
                regionStart + nextFindStart,
                regionEnd,
                UNANCHORED,
                groups,
                this)) {
            return failFind();
        }
        toRegionOffsets();
        setMatchAndAdvance(groups[0], groups[1]);
        return true;
    }

    // The pattern's $ reads a CR the region cannot show, so this search runs on the NFA through
    // CompiledPattern.matchRegionAfterCarriageReturnInto.
    private boolean findNextAfterCarriageReturn()
    {
        if (!pattern.matchRegionAfterCarriageReturnInto(input, regionStart, regionEnd, regionStart + nextFindStart, UNANCHORED, groups, this)) {
            return failFind();
        }
        setMatchAndAdvance(groups[0], groups[1]);
        return true;
    }

    private boolean isPartialRegion()
    {
        return regionStart != 0 || regionEnd != input.length();
    }

    private void toRegionOffsets()
    {
        for (int index = 0; index < groups.length; index++) {
            if (groups[index] >= 0) {
                groups[index] -= regionStart;
            }
        }
    }

    // A failed find leaves no match, and the next find searches again from retryStart.
    private boolean failFind()
    {
        nextFindStart = retryStart;
        invalidateMatch();
        return false;
    }

    private void setMatchAndAdvance(int matchStart, int matchEnd)
    {
        hasMatch = true;
        retryStart = matchEnd;
        if (matchStart != matchEnd) {
            nextFindStart = matchEnd;
        }
        else if (matchEnd == regionLength()) {
            nextFindStart = regionLength() + 1;
        }
        else if (latin1) {
            nextFindStart = matchEnd + 1;
        }
        else {
            nextFindStart = matchEnd + SliceUtf8.lengthOfCodePointSafe(
                    input.byteArray(),
                    input.byteArrayOffset() + regionStart,
                    regionLength(),
                    matchEnd);
        }
    }

    /// Resets this matcher to the complete logical input Slice, as [#reset(Slice)] does, and
    /// finds the next match starting at the specified byte offset in it, as the JDK's
    /// `Matcher.find(int)` does. The offset may be anywhere in the input, its end included.
    public boolean find(int start)
    {
        if (start < 0 || start > input.length()) {
            throw new IndexOutOfBoundsException("start out of bounds: " + start);
        }
        if (isPartialRegion()) {
            reset(input);
        }
        else {
            // A whole-input matcher already has the reset region, so clear only the search state.
            resetSearchState();
        }
        nextFindStart = start;
        return find();
    }

    /// Returns whether the pattern matches the complete current region.
    public boolean matches()
    {
        return match(ANCHOR_BOTH);
    }

    /// Returns whether the pattern matches at the beginning of the current region.
    public boolean lookingAt()
    {
        return match(ANCHOR_START);
    }

    private boolean match(CompiledPattern.Anchor anchor)
    {
        invalidateMatch();
        if (rustSearch) {
            return matchWithInputContext(anchor);
        }
        boolean matched = regionStartsAfterCarriageReturn
                ? pattern.matchRegionAfterCarriageReturnInto(input, regionStart, regionEnd, regionStart, anchor, groups, this)
                : pattern.matchRegionInto(input, regionStart, regionEnd, regionStart, regionEnd, anchor, groups, this);
        if (!matched) {
            nextFindStart = retryStart;
            return false;
        }
        setMatchAndAdvance(groups[0], groups[1]);
        return true;
    }

    private boolean matchWithInputContext(CompiledPattern.Anchor anchor)
    {
        // An anchored Rust search cannot skip ahead, so an empty match splitting a character fails,
        // except in the crate's bytes API.
        if (!latin1 && !Utf8.isCharacterBoundary(input, regionStart)) {
            nextFindStart = retryStart;
            return false;
        }
        if (!pattern.matchRegionInto(
                input,
                0,
                input.length(),
                regionStart,
                regionEnd,
                anchor,
                groups,
                this)) {
            nextFindStart = retryStart;
            return false;
        }
        toRegionOffsets();
        setMatchAndAdvance(groups[0], groups[1]);
        previousMatchEnd = groups[1];
        return true;
    }

    /// Returns the retained capturing-group count, excluding group zero.
    public int groupCount()
    {
        return (groups.length / 2) - 1;
    }

    /// Returns whether a retained group participated in the current match.
    ///
    /// @throws IllegalStateException if no successful match is current
    public boolean matched(int group)
    {
        checkMatch();
        checkGroup(group);
        return groups[group * 2] >= 0;
    }

    /// Returns the start byte offset of group zero in the logical input.
    public int start()
    {
        return start(0);
    }

    /// Returns a group's start byte offset, or `-1` when it did not participate.
    ///
    /// @throws IllegalStateException if no successful match is current
    public int start(int group)
    {
        checkMatch();
        checkGroup(group);
        int start = groups[group * 2];
        return start < 0 ? -1 : regionStart + start;
    }

    /// Returns the end byte offset of group zero in the logical input.
    public int end()
    {
        return end(0);
    }

    /// Returns a group's end byte offset, or `-1` when it did not participate.
    ///
    /// @throws IllegalStateException if no successful match is current
    public int end(int group)
    {
        checkMatch();
        checkGroup(group);
        int end = groups[group * 2 + 1];
        return end < 0 ? -1 : regionStart + end;
    }

    /// Returns a zero-copy view of group zero.
    public Slice group()
    {
        return group(0);
    }

    /// Returns a zero-copy view of a group, or `null` when the group did not participate.
    public Slice group(int group)
    {
        int start = start(group);
        if (start < 0) {
            return null;
        }
        return input.slice(start, end(group) - start);
    }

    /// Returns a zero-copy named-group view, or `null` when the group did not participate.
    ///
    /// @throws IllegalArgumentException if the name is unknown
    /// @throws IllegalStateException if no successful match is current
    public Slice group(String groupName)
    {
        requireNonNull(groupName, "groupName is null");
        Integer group = namedCapturingGroups.get(groupName);
        if (group == null) {
            throw new IllegalArgumentException("unknown named group: " + groupName);
        }
        return group(group);
    }

    /// Returns an immutable snapshot of current boundaries and metadata. The snapshot retains the
    /// input storage, whose mutable contents remain visible through its zero-copy group views.
    ///
    /// @throws IllegalStateException if no successful match is current
    public MatchResult toMatchResult()
    {
        checkMatch();
        int[] resultGroups = groups.clone();
        if (regionStart != 0) {
            for (int index = 0; index < resultGroups.length; index++) {
                if (resultGroups[index] >= 0) {
                    resultGroups[index] += regionStart;
                }
            }
        }
        return new MatchResult(input, resultGroups, namedCapturingGroups);
    }

    int groupOffsetForDiagnostics(int index)
    {
        return groups[index];
    }

    OnePass.Workspace onePassWorkspace()
    {
        if (onePassWorkspace == null) {
            onePassWorkspace = new OnePass.Workspace();
        }
        return onePassWorkspace;
    }

    BitState.Workspace bitStateWorkspace()
    {
        if (bitStateWorkspace == null) {
            bitStateWorkspace = new BitState.Workspace();
        }
        return bitStateWorkspace;
    }

    Nfa.Workspace nfaWorkspace()
    {
        if (nfaWorkspace == null) {
            nfaWorkspace = new Nfa.Workspace();
        }
        return nfaWorkspace;
    }

    PositionNfa.Workspace positionNfaWorkspace()
    {
        if (positionNfaWorkspace == null) {
            positionNfaWorkspace = new PositionNfa.Workspace();
        }
        return positionNfaWorkspace;
    }

    int dfaQuitStreak()
    {
        return dfaQuitStreak;
    }

    void setDfaQuitStreak(int dfaQuitStreak)
    {
        this.dfaQuitStreak = dfaQuitStreak;
    }

    boolean candidateStartCursorQuit()
    {
        return candidateStartCursorQuit;
    }

    boolean skipsForwardDfa()
    {
        return skipsForwardDfa;
    }

    void markSkipsForwardDfa()
    {
        skipsForwardDfa = true;
    }

    boolean onePassWorkspaceInitializedForDiagnostics()
    {
        return onePassWorkspace != null;
    }

    boolean bitStateWorkspaceInitializedForDiagnostics()
    {
        return bitStateWorkspace != null;
    }

    boolean nfaWorkspaceInitializedForDiagnostics()
    {
        return nfaWorkspace != null;
    }

    boolean characterClassCounterEnabledForDiagnostics()
    {
        return characterClassCounter != null;
    }

    boolean candidateStartCursorEnabledForDiagnostics()
    {
        return candidateStartCursor != null && candidateStartCursor.enabled();
    }

    boolean candidateStartCursorSearchesLiteralForDiagnostics()
    {
        return candidateStartCursor != null && candidateStartCursor.searchesLiteral();
    }

    boolean candidateStartCursorSearchesFoldedStartRunForDiagnostics()
    {
        return candidateStartCursor != null && candidateStartCursor.searchesFoldedStartRun();
    }

    boolean candidateStartCursorReportsQuitCandidatesForDiagnostics()
    {
        return candidateStartCursor != null && candidateStartCursor.reportsQuitCandidates();
    }

    boolean groupZeroForwardCursorEnabledForDiagnostics()
    {
        return enableGroupZeroForwardCursor;
    }

    boolean startAnchoredScanPlanEnabledForDiagnostics()
    {
        return startAnchoredScanPlan != null;
    }

    GroupZeroSpanSearch spanSearchForDiagnostics()
    {
        return spanSearch;
    }

    SingleByteMatcher singleByteMatcherForDiagnostics()
    {
        return singleByteMatcher;
    }

    int groupZeroForwardProductivityCheckCountForDiagnostics()
    {
        return groupZeroForwardCursor == null ? 0 : groupZeroForwardCursor.byteScanProductivityCheckCount();
    }

    private Dfa.GroupZeroForwardCursor groupZeroForwardCursor()
    {
        if (!enableGroupZeroForwardCursor) {
            return null;
        }
        if (!groupZeroForwardCursorInitialized) {
            groupZeroForwardCursor = pattern.createGroupZeroForwardCursor();
            groupZeroForwardCursorInitialized = true;
        }
        return groupZeroForwardCursor;
    }

    long candidateStartRouteCountForDiagnostics()
    {
        return candidateStartCursor == null ? 0 : candidateStartCursor.routeCount();
    }

    long candidateStartFallbackCountForDiagnostics()
    {
        return candidateStartCursor == null ? 0 : candidateStartCursor.fallbackCount();
    }

    long candidateStartProductivityRejectionCountForDiagnostics()
    {
        return candidateStartCursor == null ? 0 : candidateStartCursor.productivityRejectionCount();
    }

    long candidateStartFoldedRunSearchCountForDiagnostics()
    {
        return candidateStartCursor == null ? 0 : candidateStartCursor.foldedRunSearchCount();
    }

    long requiredLiteralScanCountForDiagnostics()
    {
        return requiredLiteralScanCount;
    }

    private void invalidateMatch()
    {
        hasMatch = false;
    }

    private void checkMatch()
    {
        if (!hasMatch) {
            throw new IllegalStateException("no successful match");
        }
    }

    private void checkGroup(int group)
    {
        if (group < 0 || group > groupCount()) {
            throw new IllegalArgumentException("group index out of range: " + group);
        }
    }

    private int regionLength()
    {
        return regionEnd - regionStart;
    }
}
