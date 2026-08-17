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

/**
 * A reusable matcher for one compiled pattern.
 * <p>
 * The matcher owns one capture buffer and reuses it across operations. It is mutable and not
 * thread-safe. Group values are zero-copy views of the current input.
 */
public final class Re2Matcher
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
    private OnePass.Workspace onePassWorkspace;
    private BitState.Workspace bitStateWorkspace;
    private Nfa.Workspace nfaWorkspace;

    private Slice input;
    private int regionStart;
    private int regionEnd;
    private int nextFindStart;
    private boolean hasMatch;
    private boolean groupZeroForwardCursorInitialized;
    private Dfa.GroupZeroForwardCursor groupZeroForwardCursor;

    Re2Matcher(Re2 pattern, Slice input)
    {
        this(pattern, input, null);
    }

    Re2Matcher(Re2 pattern, Slice input, SingleByteMatcher singleByteMatcher)
    {
        this(pattern, input, singleByteMatcher, pattern.capturingGroupCount());
    }

    Re2Matcher(Re2 pattern, Slice input, SingleByteMatcher singleByteMatcher, int capturingGroupCount)
    {
        this(pattern, input, singleByteMatcher, capturingGroupCount, true);
    }

    Re2Matcher(Re2 pattern, Slice input, SingleByteMatcher singleByteMatcher, int capturingGroupCount, boolean enableGroupZeroForwardCursor)
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

    /**
     * Resets this matcher to the complete logical input Slice.
     */
    public Re2Matcher reset(Slice input)
    {
        requireNonNull(input, "input is null");
        return reset(input, 0, input.length());
    }

    /**
     * Resets this matcher to a logical input region without constructing a Slice view. Match and
     * group offsets remain relative to the logical input Slice.
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "The matcher intentionally retains the caller's Slice for zero-copy matching")
    public Re2Matcher reset(Slice input, int start, int end)
    {
        requireNonNull(input, "input is null");
        if (start < 0 || end < start || end > input.length()) {
            throw new IndexOutOfBoundsException("region out of bounds: [" + start + ", " + end + ")");
        }
        this.input = input;
        regionStart = start;
        regionEnd = end;
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
        invalidateMatch();
        return this;
    }

    /**
     * Finds the next non-overlapping match in the current region.
     */
    public boolean find()
    {
        int regionLength = regionLength();
        if (nextFindStart > regionLength) {
            invalidateMatch();
            return false;
        }

        if (singleByteMatcher != null) {
            int matchStart = singleByteMatcher.find(input, regionStart + nextFindStart, regionEnd);
            if (matchStart < 0) {
                nextFindStart = regionLength + 1;
                invalidateMatch();
                return false;
            }
            matchStart -= regionStart;
            groups[0] = matchStart;
            groups[1] = matchStart + 1;
            nextFindStart = matchStart + 1;
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
                nextFindStart = regionLength + 1;
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
                nextFindStart = regionLength + 1;
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
                nextFindStart = regionLength + 1;
                invalidateMatch();
                return false;
            }
        }

        long loweredCandidateStart = Dfa.SEARCH_FAILED;
        if (hasLoweredCandidateSearch) {
            loweredCandidateStart = pattern.findLoweredCandidateStart(input, regionStart, regionEnd, searchStart);
        }
        if (loweredCandidateStart == Dfa.SEARCH_NO_MATCH) {
            nextFindStart = regionLength + 1;
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
            nextFindStart = regionLength + 1;
            invalidateMatch();
            return false;
        }

        setMatchAndAdvance(groups[0], groups[1]);
        return true;
    }

    private void setMatchAndAdvance(int matchStart, int matchEnd)
    {
        hasMatch = true;
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

    /**
     * Finds the next match starting at the specified byte offset in the logical input Slice.
     * The offset must be within the current region.
     */
    public boolean find(int start)
    {
        if (start < regionStart || start > regionEnd) {
            throw new IndexOutOfBoundsException("start out of bounds: " + start);
        }
        if (groupZeroForwardCursor != null) {
            groupZeroForwardCursor.reset();
        }
        nextFindStart = start - regionStart;
        invalidateMatch();
        return find();
    }

    /**
     * Returns whether the pattern matches the complete current region.
     */
    public boolean matches()
    {
        return match(ANCHOR_BOTH);
    }

    /**
     * Returns whether the pattern matches at the beginning of the current region.
     */
    public boolean lookingAt()
    {
        return match(ANCHOR_START);
    }

    private boolean match(Re2.Anchor anchor)
    {
        invalidateMatch();
        if (!pattern.matchRegionInto(
                input,
                regionStart,
                regionEnd,
                regionStart,
                regionEnd,
                anchor,
                groups,
                this)) {
            return false;
        }
        setMatchAndAdvance(groups[0], groups[1]);
        return true;
    }

    /**
     * Returns the retained capturing-group count, excluding group zero.
     */
    public int groupCount()
    {
        return (groups.length / 2) - 1;
    }

    /**
     * Returns whether a retained group participated in the current match.
     *
     * @throws IllegalStateException if no successful match is current
     */
    public boolean matched(int group)
    {
        checkMatch();
        checkGroup(group);
        return groups[group * 2] >= 0;
    }

    /**
     * Returns the start byte offset of group zero in the logical input.
     */
    public int start()
    {
        return start(0);
    }

    /**
     * Returns a group's start byte offset, or {@code -1} when it did not participate.
     *
     * @throws IllegalStateException if no successful match is current
     */
    public int start(int group)
    {
        checkMatch();
        checkGroup(group);
        int start = groups[group * 2];
        return start < 0 ? -1 : regionStart + start;
    }

    /**
     * Returns the end byte offset of group zero in the logical input.
     */
    public int end()
    {
        return end(0);
    }

    /**
     * Returns a group's end byte offset, or {@code -1} when it did not participate.
     *
     * @throws IllegalStateException if no successful match is current
     */
    public int end(int group)
    {
        checkMatch();
        checkGroup(group);
        int end = groups[group * 2 + 1];
        return end < 0 ? -1 : regionStart + end;
    }

    /**
     * Returns a zero-copy view of group zero.
     */
    public Slice group()
    {
        return group(0);
    }

    /**
     * Returns a zero-copy view of a group, or {@code null} when the group did not participate.
     */
    public Slice group(int group)
    {
        int start = start(group);
        if (start < 0) {
            return null;
        }
        return input.slice(start, end(group) - start);
    }

    /**
     * Returns a zero-copy named-group view, or {@code null} when the group did not participate.
     *
     * @throws IllegalArgumentException if the name is unknown
     * @throws IllegalStateException if no successful match is current
     */
    public Slice group(String groupName)
    {
        requireNonNull(groupName, "groupName is null");
        Integer group = namedCapturingGroups.get(groupName);
        if (group == null) {
            throw new IllegalArgumentException("unknown named group: " + groupName);
        }
        return group(group);
    }

    /**
     * Returns an immutable snapshot of current boundaries and metadata. The snapshot retains the
     * input storage, whose mutable contents remain visible through its zero-copy group views.
     *
     * @throws IllegalStateException if no successful match is current
     */
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
