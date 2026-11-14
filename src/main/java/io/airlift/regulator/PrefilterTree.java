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

import io.airlift.regulator.Prefilter.PrefilterOp;
import io.airlift.slice.SizeOf;
import io.airlift.slice.Slice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Deduplicates prefilter nodes and determines which regular expressions may
/// match after their required atoms are found.
final class PrefilterTree
{
    private record PrefilterKey(PrefilterOp operation, Slice atom, List<Integer> childEntryIndexes)
    {
        private PrefilterKey
        {
            childEntryIndexes = List.copyOf(childEntryIndexes);
        }
    }

    private static final class PrefilterEntry
    {
        private final PrefilterOp operation;
        private final List<Integer> childIndexes;
        private final List<Integer> parentIndexes = new ArrayList<>();

        private PrefilterEntry(PrefilterOp operation, List<Integer> childIndexes)
        {
            this.operation = operation;
            this.childIndexes = List.copyOf(childIndexes);
        }
    }

    private static final int INSTANCE_SIZE = SizeOf.instanceSize(PrefilterTree.class);
    private static final int KEY_INSTANCE_SIZE = SizeOf.instanceSize(PrefilterKey.class);
    private static final int ENTRY_INSTANCE_SIZE = SizeOf.instanceSize(PrefilterEntry.class);

    private final List<PrefilterEntry> entries;
    private final Map<PrefilterKey, Integer> entriesByKey;
    private final List<Integer> regexpRootIndexes;
    private final List<Slice> atoms;
    private final Map<Slice, Integer> atomIndexes;
    private final List<List<Integer>> entryIndexesByAtom;
    private final int minimumAtomLength;
    private boolean compiled;

    PrefilterTree(int minimumAtomLength)
    {
        this.entries = new ArrayList<>();
        this.entriesByKey = new HashMap<>();
        this.regexpRootIndexes = new ArrayList<>();
        this.atoms = new ArrayList<>();
        this.atomIndexes = new HashMap<>();
        this.entryIndexesByAtom = new ArrayList<>();
        this.minimumAtomLength = minimumAtomLength;
    }

    /// Adds a prefilter and returns its regular-expression index.
    int add(Prefilter prefilter)
    {
        int regexpIndex = regexpRootIndexes.size();
        if (!keepNode(prefilter)) {
            regexpRootIndexes.add(-1);
        }
        else {
            int entryIndex = addInternal(prefilter);
            regexpRootIndexes.add(entryIndex);
        }
        return regexpIndex;
    }

    // Decides whether a prefilter can guard its regexp, removing from each AND the children that
    // cannot. An AND is kept when any child is; an OR only when every child is, so its walk stops
    // at the first child that is not. Nested alternations build prefilters tens of thousands of
    // levels deep, so this walk and the others over prefilters keep their nesting on the heap.
    private boolean keepNode(Prefilter prefilter)
    {
        if (prefilter == null) {
            return false;
        }
        if (prefilter.op() != PrefilterOp.AND && prefilter.op() != PrefilterOp.OR) {
            return keepLeaf(prefilter);
        }

        Deque<KeepFrame> frames = new ArrayDeque<>();
        frames.addLast(new KeepFrame(prefilter));
        while (true) {
            KeepFrame frame = frames.getLast();
            boolean isAnd = frame.prefilter.op() == PrefilterOp.AND;
            if (frame.nextChildIndex < frame.children.size() && (isAnd || !frame.anyChildDropped)) {
                Prefilter child = frame.children.get(frame.nextChildIndex++);
                if (child.op() == PrefilterOp.AND || child.op() == PrefilterOp.OR) {
                    frames.addLast(new KeepFrame(child));
                }
                else {
                    frame.childVisited(child, keepLeaf(child));
                }
                continue;
            }

            boolean kept = isAnd ? frame.prefilter.retainChildren(frame.keptChildren::contains) : !frame.anyChildDropped;
            frames.removeLast();
            if (frames.isEmpty()) {
                return kept;
            }
            frames.getLast().childVisited(frame.prefilter, kept);
        }
    }

    private boolean keepLeaf(Prefilter prefilter)
    {
        return switch (prefilter.op()) {
            case ALL, NONE -> false;
            case ATOM -> prefilter.atom().length() >= minimumAtomLength;
            case AND, OR -> throw new IllegalArgumentException("not a leaf: " + prefilter.op());
        };
    }

    private static final class KeepFrame
    {
        private final Prefilter prefilter;
        private final List<Prefilter> children;
        // Only an AND removes children, so only its frame holds the kept child objects, which
        // Prefilter compares by identity.
        private final Set<Prefilter> keptChildren;
        private int nextChildIndex;
        private boolean anyChildDropped;

        private KeepFrame(Prefilter prefilter)
        {
            this.prefilter = prefilter;
            this.children = prefilter.children();
            this.keptChildren = prefilter.op() == PrefilterOp.AND ? new HashSet<>() : null;
        }

        private void childVisited(Prefilter child, boolean kept)
        {
            if (!kept) {
                anyChildDropped = true;
            }
            else if (keptChildren != null) {
                keptChildren.add(child);
            }
        }
    }

    // Adds the entries of a kept prefilter, each after its children's, and returns the root's index.
    private int addInternal(Prefilter prefilter)
    {
        if (prefilter.op() == PrefilterOp.ATOM) {
            return addAtomEntry(prefilter.atom());
        }

        Deque<AddFrame> frames = new ArrayDeque<>();
        frames.addLast(new AddFrame(prefilter));
        while (true) {
            AddFrame frame = frames.getLast();
            if (frame.childIndexes.size() < frame.children.size()) {
                Prefilter child = frame.children.get(frame.childIndexes.size());
                if (child.op() == PrefilterOp.ATOM) {
                    frame.childIndexes.add(addAtomEntry(child.atom()));
                }
                else {
                    frames.addLast(new AddFrame(child));
                }
                continue;
            }

            int entryIndex = addOperatorEntry(frame.prefilter.op(), frame.childIndexes);
            frames.removeLast();
            if (frames.isEmpty()) {
                return entryIndex;
            }
            frames.getLast().childIndexes.add(entryIndex);
        }
    }

    private record AddFrame(Prefilter prefilter, List<Prefilter> children, List<Integer> childIndexes)
    {
        private AddFrame(Prefilter prefilter)
        {
            this(prefilter, prefilter.children(), new ArrayList<>());
        }
    }

    private int addOperatorEntry(PrefilterOp operation, List<Integer> childIndexes)
    {
        PrefilterKey key = new PrefilterKey(operation, null, childIndexes);
        Integer existingEntryIndex = entriesByKey.get(key);
        if (existingEntryIndex != null) {
            return existingEntryIndex;
        }

        int entryIndex = entries.size();
        PrefilterEntry entry = new PrefilterEntry(operation, childIndexes);
        entries.add(entry);
        entriesByKey.put(key, entryIndex);

        for (int childIndex : childIndexes) {
            entries.get(childIndex).parentIndexes.add(entryIndex);
        }

        return entryIndex;
    }

    private int addAtomEntry(Slice atom)
    {
        PrefilterKey key = new PrefilterKey(PrefilterOp.ATOM, atom, List.of());
        Integer existingEntryIndex = entriesByKey.get(key);
        if (existingEntryIndex != null) {
            return existingEntryIndex;
        }

        int entryIndex = entries.size();
        PrefilterEntry entry = new PrefilterEntry(PrefilterOp.ATOM, List.of());
        entries.add(entry);
        entriesByKey.put(key, entryIndex);

        Integer atomListIndex = atomIndexes.get(atom);
        if (atomListIndex == null) {
            atomListIndex = atoms.size();
            atoms.add(atom);
            atomIndexes.put(atom, atomListIndex);
            entryIndexesByAtom.add(new ArrayList<>());
        }
        entryIndexesByAtom.get(atomListIndex).add(entryIndex);

        return entryIndex;
    }

    /// Compiles the prefilter tree and populates the atom list.
    void compile(List<Slice> outputAtoms)
    {
        if (regexpRootIndexes.isEmpty()) {
            return;
        }
        if (compiled) {
            return;
        }

        compiled = true;
        outputAtoms.clear();
        atoms.stream()
                .map(Slice::copy)
                .forEach(outputAtoms::add);
    }

    /// The bytes this tree retains: its lists, maps, atoms, and boxed indexes. It fills each list
    /// and map one element at a time from empty, and does not change once compiled.
    long estimatedRetainedSize()
    {
        long size = INSTANCE_SIZE;
        for (PrefilterEntry entry : entries) {
            size += ENTRY_INSTANCE_SIZE + copiedListSize(entry.childIndexes) + RetainedSizeSupport.arrayListSize(entry.parentIndexes);
        }
        size += RetainedSizeSupport.arrayListSize(entries);
        // A key's atom is the one the atom list holds.
        size += RetainedSizeSupport.hashMapSize(entriesByKey, key -> KEY_INSTANCE_SIZE + copiedListSize(key.childEntryIndexes()));
        size += RetainedSizeSupport.arrayListSize(regexpRootIndexes);
        size += RetainedSizeSupport.arrayListSize(atoms);
        for (Slice atom : atoms) {
            size += atom.getRetainedSize();
        }
        size += RetainedSizeSupport.hashMapSize(atomIndexes, _ -> 0);
        size += RetainedSizeSupport.arrayListSize(entryIndexesByAtom);
        for (List<Integer> indexes : entryIndexesByAtom) {
            size += RetainedSizeSupport.arrayListSize(indexes);
        }
        return size;
    }

    // A list from `List.copyOf` and its boxed indexes.
    private static long copiedListSize(List<Integer> list)
    {
        long size = RetainedSizeSupport.unmodifiableListSize(list);
        for (int index : list) {
            size += RetainedSizeSupport.boxSize(index);
        }
        return size;
    }

    /// Finds every regular expression whose prefilter accepts the matched atoms.
    int[] regexpMatches(int[] atomIndices)
    {
        int[] regexpIndices = new int[regexpRootIndexes.size()];

        if (entries.isEmpty()) {
            for (int regexpIndex = 0; regexpIndex < regexpIndices.length; regexpIndex++) {
                regexpIndices[regexpIndex] = regexpIndex;
            }
            return regexpIndices;
        }

        SparseIntArray matchedEntries = new SparseIntArray(entries.size());
        SparseIntArray remainingChildCounts = new SparseIntArray(entries.size());

        for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
            PrefilterEntry entry = entries.get(entryIndex);
            if (entry.operation == PrefilterOp.AND) {
                remainingChildCounts.set(entryIndex, entry.childIndexes.size());
            }
        }

        for (int matchedAtomIndex : atomIndices) {
            if (matchedAtomIndex < 0 || matchedAtomIndex >= entryIndexesByAtom.size()) {
                continue;
            }
            for (int entryIndex : entryIndexesByAtom.get(matchedAtomIndex)) {
                if (!matchedEntries.hasIndex(entryIndex)) {
                    matchedEntries.set(entryIndex, 1);
                }
            }
        }
        propagateMatches(matchedEntries, remainingChildCounts);

        int regexpCount = 0;
        for (int regexpIndex = 0; regexpIndex < regexpRootIndexes.size(); regexpIndex++) {
            int rootIndex = regexpRootIndexes.get(regexpIndex);
            if (rootIndex == -1 || matchedEntries.hasIndex(rootIndex)) {
                regexpIndices[regexpCount] = regexpIndex;
                regexpCount++;
            }
        }
        return Arrays.copyOf(regexpIndices, regexpCount);
    }

    // Marks every ancestor whose match the matched atom entries complete: an OR when any child
    // matches, an AND when all of its children have. The matched entries are also the work queue:
    // entries matched here are visited later in this loop, and each entry is visited once.
    private void propagateMatches(SparseIntArray matchedEntries, SparseIntArray remainingChildCounts)
    {
        for (int position = 0; position < matchedEntries.size(); position++) {
            PrefilterEntry entry = entries.get(matchedEntries.denseIndexAt(position));
            for (int parentIndex : entry.parentIndexes) {
                PrefilterEntry parent = entries.get(parentIndex);
                boolean parentMatched = false;
                if (parent.operation == PrefilterOp.OR) {
                    parentMatched = true;
                }
                else if (parent.operation == PrefilterOp.AND) {
                    int remainingChildren = remainingChildCounts.getExisting(parentIndex) - 1;
                    remainingChildCounts.setExisting(parentIndex, remainingChildren);
                    parentMatched = remainingChildren == 0;
                }
                if (parentMatched && !matchedEntries.hasIndex(parentIndex)) {
                    matchedEntries.set(parentIndex, 1);
                }
            }
        }
    }
}
