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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A bit-parallel Glushkov automaton over a flattened program's byte positions, which finds the
/// earliest end of any match in one pass over the text. The word operations of a step grow with
/// the words of state, the fan-in groups, and the live exception sources described below, while
/// the Pike NFA steps every live thread. A group-zero search takes it in place of the Pike NFA
/// over the whole text once the forward DFA fails, and a boolean search once it skips a DFA that
/// thrashed across calls; see `CompiledPattern.searchOnPositionNfa`, which turns the earliest end
/// into a short window for BitState or the Pike NFA.
///
/// A position is a set of byte ranges of one instruction list that step to the same list, so in a
/// byte program a class such as `[^u-z]`, whose ranges the list holds side by side, is one
/// position. The state is the set of positions that just read a byte, one bit each, in program
/// order. A target's other predecessors are those besides the position just before it in program
/// order. One step computes the positions that may read the next byte from three parts of the
/// follow relation, split at build time:
///
/// - the shift: each edge from a position to the next one in order, by a shift of the masked
///   state;
/// - the fan-in groups: each target with at least [#FAN_IN_MINIMUM_PREDECESSORS] other
///   predecessors, such as the suffix of `x{n,m}`, whose optional copies all step to it, by one
///   test of the state against those predecessors, shared by the targets that have the same ones;
/// - the exceptions: every other edge, by the full follow set of its source, for each exception
///   source in the state.
///
/// A group's test costs a word operation per word of state on every byte, whichever positions are
/// live, while a source's follow set costs that only on the bytes its source is live, as a Pike
/// NFA thread does. A target with only a few other predecessors therefore steps by their follow
/// sets: in UTF-8, each copy of a repeated multibyte class such as `[^u-z]` begins where two to
/// four sequences of the copy before it end, and a group for each copy would test every word of
/// state on every byte, though few of those sequence ends are live at once. A program whose groups
/// would make more than [#MAXIMUM_FAN_IN_TESTS] word tests a byte is not admitted.
///
/// An unanchored search adds the start positions before every byte, and the step keeps the
/// positions whose ranges hold the byte, looked up by the byte's class.
///
/// Only programs whose matches are bounded are admitted: no assertion, no empty match, and an
/// acyclic position graph, whose longest path is the longest match. See [#build].
final class PositionNfa
{
    /// The most positions an admitted program may have.
    private static final int MAXIMUM_POSITIONS = 512;
    /// The fewest other predecessors, those besides the position just before it, of a target that
    /// steps by a fan-in group.
    private static final int FAN_IN_MINIMUM_PREDECESSORS = 8;
    /// The most fan-in word tests, groups times words of state, of one step of an admitted
    /// program. This many cost about what the Pike NFA spends a byte on one or two live threads,
    /// so a program over the limit could pay more for them on every byte than the Pike NFA pays
    /// on sparse text.
    private static final int MAXIMUM_FAN_IN_TESTS = 32;

    private static final int INSTANCE_SIZE = SizeOf.instanceSize(PositionNfa.class);

    private final int positionCount;
    private final int words;
    private final int maxLength;
    // The program's byte classes; the program owns the array.
    private final byte[] bytemap;
    // [byteClass * words + word]: the positions whose ranges hold the bytes of the class.
    private final long[] reach;
    // The start positions, which an unanchored search adds before every byte.
    private final long[] first;
    // The positions after which a match ends.
    private final long[] accept;
    // The positions with an edge to the next position, which the shift carries.
    private final long[] shiftMask;
    private final int fanInGroupCount;
    // [group * words + word]: a group's predecessors and the targets they step to.
    private final long[] fanInPredecessors;
    private final long[] fanInTargets;
    // The exception sources, the positions with an edge the shift and the groups leave out, and
    // [position * words + word] the full follow set of each of them; empty when there is none.
    private final long[] exceptionSources;
    private final long[] exceptionFollow;
    // Nonzero for each byte a start position reads.
    private final byte[] startBytes;

    private PositionNfa(
            int positionCount,
            int maxLength,
            byte[] bytemap,
            long[] reach,
            long[] first,
            long[] accept,
            long[] shiftMask,
            int fanInGroupCount,
            long[] fanInPredecessors,
            long[] fanInTargets,
            long[] exceptionSources,
            long[] exceptionFollow,
            byte[] startBytes)
    {
        this.positionCount = positionCount;
        this.words = wordCount(positionCount);
        this.maxLength = maxLength;
        this.bytemap = bytemap;
        this.reach = reach;
        this.first = first;
        this.accept = accept;
        this.shiftMask = shiftMask;
        this.fanInGroupCount = fanInGroupCount;
        this.fanInPredecessors = fanInPredecessors;
        this.fanInTargets = fanInTargets;
        this.exceptionSources = exceptionSources;
        this.exceptionFollow = exceptionFollow;
        this.startBytes = startBytes;
    }

    /// Builds the automaton of `program`, or returns null when the program is not admitted:
    /// it must be a flattened forward program that is not end-anchored, holds no assertion, and
    /// whose instruction lists hold only byte ranges, no-ops, captures, and the match; it must not
    /// match empty; it must have from one to [#MAXIMUM_POSITIONS] positions; its position graph
    /// must be acyclic, so its matches are at most [#maxLength()] bytes long; and its fan-in
    /// groups must make at most [#MAXIMUM_FAN_IN_TESTS] word tests a step.
    static PositionNfa build(Prog program)
    {
        // A program the compiler recorded a loop in has an unbounded match, so it is rejected
        // before the builder allocates for it.
        if (program.hasLoop() || !program.didFlatten() || program.reversed() || program.anchorEnd() || program.start() == 0 ||
                program.getInstCount(InstOp.EMPTY_WIDTH) != 0 || program.getInstCount(InstOp.ALT_MATCH) != 0) {
            return null;
        }
        return new Builder(program).build();
    }

    int positionCountForDiagnostics()
    {
        return positionCount;
    }

    /// The number of fan-in groups, each of which a step tests word by word.
    int fanInGroupCountForDiagnostics()
    {
        return fanInGroupCount;
    }

    /// The length of the longest match, in bytes.
    int maxLength()
    {
        return maxLength;
    }

    /// Whether a search keeps its state in a [Workspace], as only a program of more than two
    /// words of state does.
    boolean usesWorkspace()
    {
        return words > 2;
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE +
                SizeOf.sizeOf(reach) +
                SizeOf.sizeOf(first) +
                SizeOf.sizeOf(accept) +
                SizeOf.sizeOf(shiftMask) +
                SizeOf.sizeOf(fanInPredecessors) +
                SizeOf.sizeOf(fanInTargets) +
                SizeOf.sizeOf(exceptionSources) +
                SizeOf.sizeOf(exceptionFollow) +
                SizeOf.sizeOf(startBytes);
    }

    /// Returns the absolute index in `bytes` at which the earliest-ending match that starts at or
    /// after `from` and ends by `to` ends, exclusive, or -1 when there is none. `program` is the
    /// program this automaton was built from; its start-candidate skip, when it has one, skips the
    /// bytes no match can start at. A program that [uses a workspace][#usesWorkspace()] keeps its
    /// state in `workspace`, or, when that is null, in an array this call allocates.
    ///
    /// One and two words of state each have their own step, which keeps the state in locals; more
    /// words step through an array, with a load and a store for each word on every byte.
    int earliestEnd(Prog program, byte[] bytes, int from, int to, Workspace workspace)
    {
        if (usesWorkspace()) {
            long[] states = workspace == null ? new long[2 * words] : workspace.prepareStates(words);
            return earliestEndInWords(program, bytes, from, to, states);
        }
        if (words == 1) {
            return earliestEndInOneWord(program, bytes, from, to);
        }
        return earliestEndInTwoWords(program, bytes, from, to);
    }

    private int earliestEndInOneWord(Prog program, byte[] bytes, int from, int to)
    {
        long shift = shiftMask[0];
        long inject = first[0];
        long acceptMask = accept[0];
        long exceptionMask = exceptionSources[0];
        int groups = fanInGroupCount;
        int leadSpread = program.prefixAccelMaximumLead() - program.prefixAccelMinimumLead();
        int skipCoversThrough = -1;
        long state = 0;
        int position = from;
        while (position < to) {
            if (state == 0 && (leadSpread == 0 || position > skipCoversThrough)) {
                int found = nextStart(program, bytes, position, to);
                if (found < 0) {
                    return -1;
                }
                if (leadSpread != 0 && found > position) {
                    skipCoversThrough = found + leadSpread;
                }
                position = found;
            }
            long next = ((state & shift) << 1) | inject;
            for (int group = 0; group < groups; group++) {
                if ((state & fanInPredecessors[group]) != 0) {
                    next |= fanInTargets[group];
                }
            }
            long exceptions = state & exceptionMask;
            while (exceptions != 0) {
                next |= exceptionFollow[Long.numberOfTrailingZeros(exceptions)];
                exceptions &= exceptions - 1;
            }
            state = next & reach[bytemap[bytes[position] & 0xFF] & 0xFF];
            position++;
            if ((state & acceptMask) != 0) {
                return position;
            }
        }
        return -1;
    }

    private int earliestEndInTwoWords(Prog program, byte[] bytes, int from, int to)
    {
        long shift0 = shiftMask[0];
        long shift1 = shiftMask[1];
        long inject0 = first[0];
        long inject1 = first[1];
        long accept0 = accept[0];
        long accept1 = accept[1];
        long exceptionMask0 = exceptionSources[0];
        long exceptionMask1 = exceptionSources[1];
        int groups = fanInGroupCount;
        int leadSpread = program.prefixAccelMaximumLead() - program.prefixAccelMinimumLead();
        int skipCoversThrough = -1;
        long state0 = 0;
        long state1 = 0;
        int position = from;
        while (position < to) {
            if ((state0 | state1) == 0 && (leadSpread == 0 || position > skipCoversThrough)) {
                int found = nextStart(program, bytes, position, to);
                if (found < 0) {
                    return -1;
                }
                if (leadSpread != 0 && found > position) {
                    skipCoversThrough = found + leadSpread;
                }
                position = found;
            }
            long shifted0 = state0 & shift0;
            long next0 = (shifted0 << 1) | inject0;
            long next1 = ((state1 & shift1) << 1) | (shifted0 >>> 63) | inject1;
            for (int group = 0; group < groups; group++) {
                int offset = 2 * group;
                if (((state0 & fanInPredecessors[offset]) | (state1 & fanInPredecessors[offset + 1])) != 0) {
                    next0 |= fanInTargets[offset];
                    next1 |= fanInTargets[offset + 1];
                }
            }
            long exceptions = state0 & exceptionMask0;
            while (exceptions != 0) {
                int offset = 2 * Long.numberOfTrailingZeros(exceptions);
                next0 |= exceptionFollow[offset];
                next1 |= exceptionFollow[offset + 1];
                exceptions &= exceptions - 1;
            }
            exceptions = state1 & exceptionMask1;
            while (exceptions != 0) {
                int offset = 2 * (Long.SIZE + Long.numberOfTrailingZeros(exceptions));
                next0 |= exceptionFollow[offset];
                next1 |= exceptionFollow[offset + 1];
                exceptions &= exceptions - 1;
            }
            int reachOffset = 2 * (bytemap[bytes[position] & 0xFF] & 0xFF);
            state0 = next0 & reach[reachOffset];
            state1 = next1 & reach[reachOffset + 1];
            position++;
            if (((state0 & accept0) | (state1 & accept1)) != 0) {
                return position;
            }
        }
        return -1;
    }

    /// `states` holds the state in its first `words` longs, all zero, and the next state in the
    /// `words` after them.
    private int earliestEndInWords(Prog program, byte[] bytes, int from, int to, long[] states)
    {
        int words = this.words;
        int leadSpread = program.prefixAccelMaximumLead() - program.prefixAccelMinimumLead();
        int skipCoversThrough = -1;
        boolean live = false;
        int position = from;
        while (position < to) {
            if (!live && (leadSpread == 0 || position > skipCoversThrough)) {
                int found = nextStart(program, bytes, position, to);
                if (found < 0) {
                    return -1;
                }
                if (leadSpread != 0 && found > position) {
                    skipCoversThrough = found + leadSpread;
                }
                position = found;
            }
            long carry = 0;
            for (int word = 0; word < words; word++) {
                long shifted = states[word] & shiftMask[word];
                states[words + word] = (shifted << 1) | carry | first[word];
                carry = shifted >>> 63;
            }
            for (int group = 0; group < fanInGroupCount; group++) {
                int offset = group * words;
                long predecessors = 0;
                for (int word = 0; word < words; word++) {
                    predecessors |= states[word] & fanInPredecessors[offset + word];
                }
                if (predecessors != 0) {
                    for (int word = 0; word < words; word++) {
                        states[words + word] |= fanInTargets[offset + word];
                    }
                }
            }
            for (int sourceWord = 0; sourceWord < words; sourceWord++) {
                long exceptions = states[sourceWord] & exceptionSources[sourceWord];
                while (exceptions != 0) {
                    int offset = (sourceWord * Long.SIZE + Long.numberOfTrailingZeros(exceptions)) * words;
                    for (int word = 0; word < words; word++) {
                        states[words + word] |= exceptionFollow[offset + word];
                    }
                    exceptions &= exceptions - 1;
                }
            }
            int reachOffset = words * (bytemap[bytes[position] & 0xFF] & 0xFF);
            long any = 0;
            long accepted = 0;
            for (int word = 0; word < words; word++) {
                long state = states[words + word] & reach[reachOffset + word];
                states[word] = state;
                any |= state;
                accepted |= state & accept[word];
            }
            position++;
            if (accepted != 0) {
                return position;
            }
            live = any != 0;
        }
        return -1;
    }

    /// Returns an index in `[position, to)` before which no match starts, or -1 when none starts
    /// in the range. A folded run behind a lead of varying width answers the skips after its
    /// result up to the lead's spread, as [Prog#prefixAccel] describes, so each search skips them.
    private int nextStart(Prog program, byte[] bytes, int position, int to)
    {
        if (program.canSkipToStartCandidate()) {
            return program.skipToStartCandidate(bytes, position, to - position);
        }
        while (position < to && startBytes[bytes[position] & 0xFF] == 0) {
            position++;
        }
        return position < to ? position : -1;
    }

    /// The state of a search of a program of more than two words of state, which a matcher keeps
    /// across its searches so that only its first such search allocates. It holds two states of
    /// at most [#MAXIMUM_POSITIONS] bits, 16 longs.
    static final class Workspace
    {
        private static final int INSTANCE_SIZE = SizeOf.instanceSize(Workspace.class);

        private long[] states;

        long estimatedRetainedSize()
        {
            return INSTANCE_SIZE + SizeOf.sizeOf(states);
        }

        /// Returns an array of at least `2 * words` longs whose first `words` are zero, for a
        /// program of `words` words of state.
        private long[] prepareStates(int words)
        {
            if (states == null || states.length < 2 * words) {
                states = new long[2 * words];
            }
            else {
                Arrays.fill(states, 0, words, 0);
            }
            return states;
        }
    }

    private static int wordCount(int positionCount)
    {
        return (positionCount + Long.SIZE - 1) / Long.SIZE;
    }

    private static void setBit(long[] bits, int offset, int index)
    {
        bits[offset + (index >>> 6)] |= 1L << index;
    }

    private static final class Builder
    {
        private final Prog program;
        // Positions by their list and the list they step to.
        private final Map<Long, Integer> positionIds = new HashMap<>();
        private final List<long[]> positionBytes = new ArrayList<>();
        private final List<Integer> positionOutputs = new ArrayList<>();
        private final List<Integer> positionFirstInstructions = new ArrayList<>();
        private final boolean[] scannedLists;
        private final int[] visitedLists;
        // A closure scans each list once, so it pushes each no-op and capture at most once, after
        // the list it starts from.
        private final int[] stack;
        private int visitStamp;
        private boolean closureReachesMatch;

        private Builder(Prog program)
        {
            this.program = program;
            this.scannedLists = new boolean[program.size()];
            this.visitedLists = new int[program.size()];
            this.stack = new int[program.size() + 1];
        }

        private PositionNfa build()
        {
            BitSet firstPositions = closure(program.start());
            if (firstPositions == null || closureReachesMatch || firstPositions.isEmpty()) {
                return null;
            }
            // Positions are numbered in discovery order: each follow set may add positions, which
            // the loop then visits in turn.
            List<BitSet> follows = new ArrayList<>();
            List<Boolean> accepts = new ArrayList<>();
            for (int position = 0; position < positionOutputs.size(); position++) {
                BitSet follow = closure(positionOutputs.get(position));
                if (follow == null) {
                    return null;
                }
                follows.add(follow);
                accepts.add(closureReachesMatch);
            }
            int positionCount = positionOutputs.size();

            // Number the positions in program order: programOrder maps each discovery number to
            // its program-order number, which every table below uses.
            Integer[] byProgramOrder = new Integer[positionCount];
            for (int position = 0; position < positionCount; position++) {
                byProgramOrder[position] = position;
            }
            Arrays.sort(byProgramOrder, Comparator.comparingInt(positionFirstInstructions::get));
            int[] programOrder = new int[positionCount];
            for (int order = 0; order < positionCount; order++) {
                programOrder[byProgramOrder[order]] = order;
            }
            int[][] successors = new int[positionCount][];
            boolean[] accepting = new boolean[positionCount];
            for (int position = 0; position < positionCount; position++) {
                BitSet follow = follows.get(position);
                int[] targets = new int[follow.cardinality()];
                int count = 0;
                for (int target = follow.nextSetBit(0); target >= 0; target = follow.nextSetBit(target + 1)) {
                    targets[count++] = programOrder[target];
                }
                successors[programOrder[position]] = targets;
                accepting[programOrder[position]] = accepts.get(position);
            }

            int maxLength = longestMatch(successors, accepting, firstPositions, programOrder);
            if (maxLength <= 0) {
                return null;
            }

            int words = wordCount(positionCount);
            long[] first = new long[words];
            for (int position = firstPositions.nextSetBit(0); position >= 0; position = firstPositions.nextSetBit(position + 1)) {
                setBit(first, 0, programOrder[position]);
            }
            long[] accept = new long[words];
            long[] shiftMask = new long[words];
            BitSet[] otherPredecessors = new BitSet[positionCount];
            for (int position = 0; position < positionCount; position++) {
                otherPredecessors[position] = new BitSet();
            }
            for (int source = 0; source < positionCount; source++) {
                if (accepting[source]) {
                    setBit(accept, 0, source);
                }
                for (int target : successors[source]) {
                    if (target == source + 1) {
                        setBit(shiftMask, 0, source);
                    }
                    else {
                        otherPredecessors[target].set(source);
                    }
                }
            }
            // Targets with enough other predecessors share a group by their predecessors; each
            // other predecessor of a remaining target is an exception source and steps by its
            // full follow set, which is filled when the source is first found.
            Map<BitSet, BitSet> groups = new LinkedHashMap<>();
            long[] exceptionSources = new long[words];
            long[] exceptionFollow = new long[0];
            for (int target = 0; target < positionCount; target++) {
                BitSet predecessors = otherPredecessors[target];
                if (predecessors.cardinality() >= FAN_IN_MINIMUM_PREDECESSORS) {
                    groups.computeIfAbsent(predecessors, _ -> new BitSet()).set(target);
                    continue;
                }
                for (int source = predecessors.nextSetBit(0); source >= 0; source = predecessors.nextSetBit(source + 1)) {
                    if ((exceptionSources[source >>> 6] & (1L << source)) != 0) {
                        continue;
                    }
                    setBit(exceptionSources, 0, source);
                    if (exceptionFollow.length == 0) {
                        exceptionFollow = new long[positionCount * words];
                    }
                    for (int successor : successors[source]) {
                        setBit(exceptionFollow, source * words, successor);
                    }
                }
            }
            if (groups.size() * words > MAXIMUM_FAN_IN_TESTS) {
                return null;
            }
            long[] fanInPredecessors = new long[groups.size() * words];
            long[] fanInTargets = new long[groups.size() * words];
            int group = 0;
            for (Map.Entry<BitSet, BitSet> entry : groups.entrySet()) {
                for (int source = entry.getKey().nextSetBit(0); source >= 0; source = entry.getKey().nextSetBit(source + 1)) {
                    setBit(fanInPredecessors, group * words, source);
                }
                for (int target = entry.getValue().nextSetBit(0); target >= 0; target = entry.getValue().nextSetBit(target + 1)) {
                    setBit(fanInTargets, group * words, target);
                }
                group++;
            }

            long[] reach = reach(positionCount, words, programOrder);
            if (reach == null) {
                return null;
            }
            byte[] bytemap = program.bytemapArray();
            byte[] startBytes = new byte[256];
            for (int value = 0; value < 256; value++) {
                int reachOffset = words * (bytemap[value] & 0xFF);
                for (int word = 0; word < words; word++) {
                    if ((reach[reachOffset + word] & first[word]) != 0) {
                        startBytes[value] = 1;
                        break;
                    }
                }
            }
            return new PositionNfa(
                    positionCount,
                    maxLength,
                    bytemap,
                    reach,
                    first,
                    accept,
                    shiftMask,
                    groups.size(),
                    fanInPredecessors,
                    fanInTargets,
                    exceptionSources,
                    exceptionFollow,
                    startBytes);
        }

        /// Returns the positions in the epsilon closure of the list at `listHead`, registering each
        /// position it finds for the first time, and sets [#closureReachesMatch]. Returns null
        /// when the closure holds an instruction other than a byte range, a no-op, a capture, or
        /// the match, or when it would register more than [#MAXIMUM_POSITIONS] positions. A
        /// `listHead` of 0, an instruction's output when it steps nowhere, gives an empty closure.
        private BitSet closure(int listHead)
        {
            BitSet positions = new BitSet();
            closureReachesMatch = false;
            visitStamp++;
            int stackPointer = 0;
            if (listHead != 0) {
                stack[stackPointer++] = listHead;
            }
            while (stackPointer > 0) {
                int head = stack[--stackPointer];
                if (visitedLists[head] == visitStamp) {
                    continue;
                }
                visitedLists[head] = visitStamp;
                // Every range of a position lies in its one list, so only a list's first scan,
                // in any closure, reads its ranges.
                boolean firstScan = !scannedLists[head];
                scannedLists[head] = true;
                for (int id = head; ; id++) {
                    long instruction = program.word(id);
                    switch (Prog.instOpcode(instruction)) {
                        case BYTE_RANGE -> {
                            int position = position(head, id, instruction, firstScan);
                            if (position < 0) {
                                return null;
                            }
                            positions.set(position);
                        }
                        case NOP, CAPTURE -> {
                            if (Prog.instOut(instruction) != 0) {
                                stack[stackPointer++] = Prog.instOut(instruction);
                            }
                        }
                        case MATCH -> closureReachesMatch = true;
                        default -> {
                            return null;
                        }
                    }
                    if (Prog.instLast(instruction)) {
                        break;
                    }
                }
            }
            return positions;
        }

        /// Returns the number of the position of `instruction`, the range at `id` in the list at
        /// `listHead`, registering it when it is new, or -1 when a new position would be over
        /// [#MAXIMUM_POSITIONS].
        private int position(int listHead, int id, long instruction, boolean firstScan)
        {
            long key = ((long) listHead << 32) | Prog.instOut(instruction);
            Integer position = positionIds.get(key);
            if (position == null) {
                if (positionOutputs.size() == MAXIMUM_POSITIONS) {
                    return -1;
                }
                position = positionOutputs.size();
                positionIds.put(key, position);
                positionBytes.add(new long[4]);
                positionOutputs.add(Prog.instOut(instruction));
                positionFirstInstructions.add(id);
            }
            if (firstScan) {
                long[] bytes = positionBytes.get(position);
                for (int value = 0; value < 256; value++) {
                    if (Prog.instMatches(instruction, value)) {
                        setBit(bytes, 0, value);
                    }
                }
            }
            return position;
        }

        /// Returns the length of the longest match: the most positions on a path from a start
        /// position to an accepting one. Returns -1 when the graph has a cycle, or 0 when no start
        /// position reaches an accepting one.
        private static int longestMatch(int[][] successors, boolean[] accepting, BitSet firstPositions, int[] programOrder)
        {
            int positionCount = successors.length;
            int[] predecessorCounts = new int[positionCount];
            for (int[] targets : successors) {
                for (int target : targets) {
                    predecessorCounts[target]++;
                }
            }
            int[] topologicalOrder = new int[positionCount];
            int ordered = 0;
            for (int position = 0; position < positionCount; position++) {
                if (predecessorCounts[position] == 0) {
                    topologicalOrder[ordered++] = position;
                }
            }
            for (int next = 0; next < ordered; next++) {
                for (int target : successors[topologicalOrder[next]]) {
                    if (--predecessorCounts[target] == 0) {
                        topologicalOrder[ordered++] = target;
                    }
                }
            }
            if (ordered != positionCount) {
                return -1;
            }
            // The longest path from each position to an accepting one, 0 when it reaches none.
            int[] longestTail = new int[positionCount];
            for (int order = positionCount - 1; order >= 0; order--) {
                int position = topologicalOrder[order];
                int longest = accepting[position] ? 1 : 0;
                for (int target : successors[position]) {
                    if (longestTail[target] > 0) {
                        longest = Math.max(longest, longestTail[target] + 1);
                    }
                }
                longestTail[position] = longest;
            }
            int maxLength = 0;
            for (int position = firstPositions.nextSetBit(0); position >= 0; position = firstPositions.nextSetBit(position + 1)) {
                maxLength = Math.max(maxLength, longestTail[programOrder[position]]);
            }
            return maxLength;
        }

        /// Returns the reach rows by byte class, or null when a class holds bytes that some
        /// position reads differently, which the program's byte classes rule out.
        private long[] reach(int positionCount, int words, int[] programOrder)
        {
            long[] reach = new long[program.bytemapRange() * words];
            boolean[] seen = new boolean[program.bytemapRange()];
            long[] row = new long[words];
            for (int value = 0; value < 256; value++) {
                Arrays.fill(row, 0);
                for (int position = 0; position < positionCount; position++) {
                    if ((positionBytes.get(position)[value >>> 6] & (1L << value)) != 0) {
                        setBit(row, 0, programOrder[position]);
                    }
                }
                int byteClass = program.bytemap(value);
                int offset = byteClass * words;
                if (!seen[byteClass]) {
                    seen[byteClass] = true;
                    System.arraycopy(row, 0, reach, offset, words);
                }
                else if (!Arrays.equals(row, 0, words, reach, offset, offset + words)) {
                    return null;
                }
            }
            return reach;
        }
    }
}
