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

import static io.airlift.regulator.Re2.Anchor.ANCHOR_BOTH;
import static io.airlift.regulator.Re2.Anchor.ANCHOR_START;
import static io.airlift.regulator.Re2.Anchor.UNANCHORED;
import static java.util.Objects.requireNonNull;

/// A reusable matcher for one compiled RE2, Java, or Rust pattern, and the matcher that
/// [TrinoRegexpMatcher] wraps.
///
/// The matcher owns one capture buffer and reuses it across operations. It is mutable and not
/// thread-safe. Group values are zero-copy views of the current input.
public final class RegexpMatcher
        implements Re2.MatchWorkspaces
{
    private final Re2 pattern;
    private final int[] groups;
    private final Map<String, Integer> namedCapturingGroups;
    private final boolean latin1;
    private final SingleByteMatcher singleByteMatcher;
    private final Dfa.CandidateStartCursor candidateStartCursor;
    private final boolean enableGroupZeroForwardCursor;
    private final boolean hasLoweredCandidateSearch;
    private final boolean hasDirectLoweredGroupZeroSearch;
    // The pattern's start-anchored scan plan that find() can run directly. See
    // Re2.startAnchoredScanPlan.
    private final ScanPlan startAnchoredScanPlan;
    private final boolean rustSearch;
    private final boolean readsCarriageReturnBeforeRegion;
    private OnePass.Workspace onePassWorkspace;
    private BitState.Workspace bitStateWorkspace;
    private Nfa.Workspace nfaWorkspace;

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
    // CR, so every search of the region runs on the NFA. See Re2.matchRegionAfterCarriageReturnInto.
    private boolean regionStartsAfterCarriageReturn;
    private Dfa.GroupZeroForwardCursor groupZeroForwardCursor;

    RegexpMatcher(Re2 pattern, Slice input)
    {
        this(pattern, input, null);
    }

    RegexpMatcher(Re2 pattern, Slice input, SingleByteMatcher singleByteMatcher)
    {
        this(pattern, input, singleByteMatcher, pattern.capturingGroupCount());
    }

    RegexpMatcher(Re2 pattern, Slice input, SingleByteMatcher singleByteMatcher, int capturingGroupCount)
    {
        this(pattern, input, singleByteMatcher, capturingGroupCount, true);
    }

    RegexpMatcher(Re2 pattern, Slice input, SingleByteMatcher singleByteMatcher, int capturingGroupCount, boolean enableGroupZeroForwardCursor)
    {
        this.pattern = requireNonNull(pattern, "pattern is null");
        if (capturingGroupCount < 0 || capturingGroupCount > pattern.capturingGroupCount()) {
            throw new IllegalArgumentException("capturingGroupCount out of range: " + capturingGroupCount);
        }
        this.groups = new int[2 * (capturingGroupCount + 1)];
        this.namedCapturingGroups = retainedNamedCapturingGroups(pattern, capturingGroupCount);
        this.latin1 = (pattern.flags() & Regexp.LATIN1) != 0;
        this.singleByteMatcher = groups.length == 2 ? singleByteMatcher : null;
        this.candidateStartCursor = groups.length == 2 && this.singleByteMatcher == null
                ? pattern.createCandidateStartCursor()
                : null;
        this.enableGroupZeroForwardCursor = enableGroupZeroForwardCursor &&
                this.singleByteMatcher == null &&
                pattern.canUseGroupZeroForwardCursor(groups.length);
        this.hasLoweredCandidateSearch = pattern.hasLoweredCandidateSearch();
        this.hasDirectLoweredGroupZeroSearch =
                groups.length == 2 && pattern.hasDirectLoweredGroupZeroSearch();
        this.startAnchoredScanPlan = this.singleByteMatcher == null ? pattern.startAnchoredScanPlan() : null;
        this.rustSearch = (pattern.flags() & Regexp.RUST_SEARCH) != 0;
        this.readsCarriageReturnBeforeRegion = pattern.readsCarriageReturnBeforeRegion();
        reset(input);
    }

    private static Map<String, Integer> retainedNamedCapturingGroups(Re2 pattern, int capturingGroupCount)
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
        if (candidateStartCursor != null) {
            candidateStartCursor.reset(end - start);
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
        invalidateMatch();
        return this;
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
        if (rustSearch) {
            return findRust();
        }
        return findGeneral();
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
        nextFindStart = retryStart;
        invalidateMatch();
        return false;
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

    private boolean findGeneral()
    {
        int regionLength = regionLength();
        if (nextFindStart > regionLength) {
            invalidateMatch();
            return false;
        }

        if (rustSearch && isPartialRegion()) {
            return findNextWithInputContext();
        }

        if (regionStartsAfterCarriageReturn) {
            return findNextAfterCarriageReturn();
        }

        if (singleByteMatcher != null) {
            int matchStart = singleByteMatcher.find(input, regionStart + nextFindStart, regionEnd);
            if (matchStart < 0) {
                nextFindStart = retryStart;
                invalidateMatch();
                return false;
            }
            matchStart -= regionStart;
            groups[0] = matchStart;
            groups[1] = matchStart + 1;
            nextFindStart = matchStart + 1;
            retryStart = matchStart + 1;
            hasMatch = true;
            return true;
        }

        if (pattern.canReturnEmptyAtStart(input, regionStart, regionEnd, regionStart + nextFindStart)) {
            groups[0] = nextFindStart;
            groups[1] = nextFindStart;
            setMatchAndAdvance(nextFindStart, nextFindStart);
            return true;
        }

        if (candidateStartCursor != null && candidateStartCursor.enabled()) {
            long candidateBoundary = Dfa.searchCandidateBoundary(
                    candidateStartCursor,
                    input,
                    regionStart,
                    regionEnd,
                    regionStart + nextFindStart);
            if (candidateBoundary >= 0) {
                groups[0] = (int) (candidateBoundary >>> 32);
                groups[1] = (int) candidateBoundary;
                setMatchAndAdvance(groups[0], groups[1]);
                return true;
            }
            if (candidateBoundary == Dfa.SEARCH_NO_MATCH) {
                nextFindStart = retryStart;
                invalidateMatch();
                return false;
            }
        }

        Dfa.GroupZeroForwardCursor groupZeroForwardCursor = groupZeroForwardCursor();
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
                        regionStart + groups[0],
                        regionStart + groups[1],
                        regionStart,
                        groups)) {
                    setMatchAndAdvance(groups[0], groups[1]);
                    return true;
                }
            }
            if (matchBoundary == Dfa.SEARCH_NO_MATCH) {
                nextFindStart = retryStart;
                invalidateMatch();
                return false;
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
                nextFindStart = retryStart;
                invalidateMatch();
                return false;
            }
        }

        long loweredCandidateStart = Dfa.SEARCH_FAILED;
        if (hasLoweredCandidateSearch) {
            loweredCandidateStart = pattern.findLoweredCandidateStart(input, regionStart, regionEnd, searchStart);
        }
        if (loweredCandidateStart == Dfa.SEARCH_NO_MATCH) {
            nextFindStart = retryStart;
            invalidateMatch();
            return false;
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
            nextFindStart = retryStart;
            invalidateMatch();
            return false;
        }

        setMatchAndAdvance(groups[0], groups[1]);
        return true;
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
            nextFindStart = retryStart;
            invalidateMatch();
            return false;
        }
        toRegionOffsets();
        setMatchAndAdvance(groups[0], groups[1]);
        return true;
    }

    // The pattern's $ reads a CR the region cannot show, so this search runs on the NFA through
    // Re2.matchRegionAfterCarriageReturnInto.
    private boolean findNextAfterCarriageReturn()
    {
        if (!pattern.matchRegionAfterCarriageReturnInto(input, regionStart, regionEnd, regionStart + nextFindStart, UNANCHORED, groups, this)) {
            nextFindStart = retryStart;
            invalidateMatch();
            return false;
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
            if (groupZeroForwardCursor != null) {
                groupZeroForwardCursor.reset();
            }
            retryStart = 0;
            previousMatchEnd = -1;
            invalidateMatch();
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

    private boolean match(Re2.Anchor anchor)
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

    private boolean matchWithInputContext(Re2.Anchor anchor)
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

    boolean nfaWorkspaceInitializedForDiagnostics()
    {
        return nfaWorkspace != null;
    }

    boolean candidateStartCursorEnabledForDiagnostics()
    {
        return candidateStartCursor != null && candidateStartCursor.enabled();
    }

    boolean groupZeroForwardCursorEnabledForDiagnostics()
    {
        return enableGroupZeroForwardCursor;
    }

    boolean startAnchoredScanPlanEnabledForDiagnostics()
    {
        return startAnchoredScanPlan != null;
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
