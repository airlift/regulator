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

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static io.airlift.regulator.CharClass.RUNEMAX;
import static java.lang.Math.max;
import static java.lang.Math.min;

// The set of a nested bracket class while its enclosing classes are open. A CharClassBuilder
// copies a closed class into the enclosing one and rewrites its whole range list to negate or
// intersect it, so doing that at every level of a deep or chained class is quadratic. Here
// negation is a flag, sets of comparable weight (ranges plus operations folded in) merge as
// sorted lists, and otherwise a union or intersection changes the heavier set by the ranges of
// the lighter one. Deep, chained and balanced classes of n ranges and operations cost
// O(n log n). The worst case is O(n log^2 n), for sets just over MERGE_RATIO times apart in
// weight at every level, where each of the lighter set's ranges costs a tree update; that bound
// is accepted, as a set merging in n log n time at every ratio would need a finger tree or a
// split-and-join tree. A symmetric difference inverts the heavier set's ranges inside each of
// the lighter set's ranges, so a chain of toggles of wide ranges costs n times the number of
// toggles. That is accepted too: the Rust nesting limit bounds the chain by default, a
// CharClassBuilder costs the same, and a bound would need a tree that inverts a span of ranges
// lazily.
//
// CharClassBuilder builds flat classes, this class does the set algebra across bracket levels,
// and the simplifier's PendingClass is a merged class that grows while it is compared for
// equality.
final class RuneRangeSet
{
    // A set at most this many times as heavy as the other set of an operation is combined
    // with it by a linear CharClassBuilder operation, which costs O(1) per unit of the lighter
    // set's weight. Small nested classes such as [a-z[0-9]] stay on that path.
    private static final int MERGE_RATIO = 8;

    // A set rewrites its builder for at most this many unbalanced operations. Classes such as
    // [\p{L}&&[^a]&&[^b]] or [[\p{Han}][a][b]] combine a large set with a few small ones, and
    // a linear operation is cheaper than building a tree for each of them. Allowing each set a
    // constant number costs O(n log n) in total, as a set's weight at least doubles each time
    // its ranges move to another set.
    private static final int MAX_UNBALANCED_REWRITES = 4;

    // The stored ranges live in a builder until the set is the heavier side of more unbalanced
    // operations than MAX_UNBALANCED_REWRITES. They then move to a tree from low to high of
    // sorted, non-overlapping, non-adjacent ranges, which inserts or removes a range in O(log n)
    // plus the ranges it absorbs. A balanced operation moves them back into a builder.
    private CharClassBuilder builder;
    private TreeMap<Integer, Integer> ranges;
    // The unbalanced operations the builder has been rewritten for.
    private int unbalancedRewrites;
    // The set is the complement of the stored ranges.
    private boolean complemented;
    // The ranges and operations folded into the set. It bounds the stored ranges, and since
    // the lighter set is combined into the heavier, each unit of weight is on the lighter
    // side of O(log n) operations.
    private long weight;

    private RuneRangeSet(CharClassBuilder builder)
    {
        this.builder = builder;
        this.weight = builder.rangeCount() + 1L;
    }

    // Takes ownership of the builder.
    public static RuneRangeSet of(CharClassBuilder builder)
    {
        return new RuneRangeSet(builder);
    }

    public void negate()
    {
        complemented = !complemented;
        weight++;
    }

    // Callers trim to Regexp.maxRune, which lies above the ASCII letters, so a builder's
    // letter bitmaps stay exact.
    public void removeAbove(int runeLimit)
    {
        if (runeLimit >= RUNEMAX) {
            return;
        }
        weight++;
        if (builder == null) {
            updateTree(runeLimit + 1, RUNEMAX, complemented);
        }
        else if (complemented) {
            builder.addRange(runeLimit + 1, RUNEMAX);
        }
        else {
            builder.removeAbove(runeLimit);
        }
    }

    // Consumes both inputs and returns their union, stored in one of the two sets.
    public RuneRangeSet union(RuneRangeSet other)
    {
        return combine(this, other, Operation.UNION);
    }

    // Returns the union with the builder's ranges, which is consumed with this set.
    public RuneRangeSet union(CharClassBuilder members)
    {
        return members.isEmpty() ? this : union(of(members));
    }

    // Returns the intersection, stored in one of the two sets. Both sets are consumed.
    public RuneRangeSet intersection(RuneRangeSet other)
    {
        return combine(this, other, Operation.INTERSECTION);
    }

    // Returns the symmetric difference, stored in one of the two sets. Both sets are consumed.
    public RuneRangeSet symmetricDifference(RuneRangeSet other)
    {
        return combine(this, other, Operation.SYMMETRIC_DIFFERENCE);
    }

    public boolean containsRuneAbove(int rune)
    {
        int storedLow;
        int storedHigh;
        if (builder != null) {
            if (builder.isEmpty()) {
                return complemented;
            }
            RuneRange last = builder.range(builder.rangeCount() - 1);
            storedLow = last.low();
            storedHigh = last.high();
        }
        else {
            if (ranges.isEmpty()) {
                return complemented;
            }
            Map.Entry<Integer, Integer> last = ranges.lastEntry();
            storedLow = last.getKey();
            storedHigh = last.getValue();
        }
        if (!complemented) {
            return storedHigh > rune;
        }
        // The complement lacks every rune above the given one only if the last stored range
        // covers them all.
        return storedHigh < RUNEMAX || storedLow > rune + 1;
    }

    // Returns a new set of the runes at or below the limit, leaving this set unchanged. It costs
    // O(r) for the r stored ranges starting at or below the limit, plus O(log n) to find them. A
    // builder first merges its pending ranges, a cost each range pays once.
    public RuneRangeSet copyAtOrBelow(int runeLimit)
    {
        // The stored ranges, or for a complement the gaps between them, at or below the limit.
        CharClassBuilder members = new CharClassBuilder();
        int next = 0;
        if (builder != null) {
            int count = firstRangeFrom(runeLimit + 1);
            for (int index = 0; index < count; index++) {
                RuneRange range = builder.range(index);
                next = copyStoredRange(members, next, range.low(), min(range.high(), runeLimit));
            }
        }
        else {
            for (Map.Entry<Integer, Integer> entry : ranges.headMap(runeLimit, true).entrySet()) {
                next = copyStoredRange(members, next, entry.getKey(), min(entry.getValue(), runeLimit));
            }
        }
        if (complemented) {
            members.addRange(next, runeLimit);
        }
        return of(members);
    }

    // Adds a stored range, or for a complement the gap from next up to it, and returns the rune
    // after the range.
    private int copyStoredRange(CharClassBuilder members, int next, int low, int high)
    {
        if (complemented) {
            members.addRange(next, low - 1);
        }
        else {
            members.addRange(low, high);
        }
        return high + 1;
    }

    // Consumes the set and returns a builder holding it.
    public CharClassBuilder toCharClassBuilder()
    {
        if (builder == null) {
            int[] effectiveRanges = storedRanges(complemented);
            builder = new CharClassBuilder();
            for (int index = 0; index < effectiveRanges.length; index += 2) {
                builder.addRange(effectiveRanges[index], effectiveRanges[index + 1]);
            }
            ranges = null;
        }
        else if (complemented) {
            builder.negate();
        }
        complemented = false;
        return builder;
    }

    private static RuneRangeSet combine(RuneRangeSet left, RuneRangeSet right, Operation operation)
    {
        RuneRangeSet heavier = (left.weight >= right.weight) ? left : right;
        RuneRangeSet lighter = (heavier == left) ? right : left;
        long weight = heavier.weight + lighter.weight + 1;
        boolean balanced = heavier.weight <= MERGE_RATIO * lighter.weight;
        // A balanced merge costs O(1) per unit of the lighter set's weight even when the heavier
        // set is a tree, which moves back into a builder in one pass over its ranges.
        if (balanced || (heavier.builder != null && heavier.unbalancedRewrites < MAX_UNBALANCED_REWRITES)) {
            if (!balanced) {
                heavier.unbalancedRewrites++;
            }
            combineBuilders(heavier, lighter, operation);
        }
        else if (operation == Operation.SYMMETRIC_DIFFERENCE) {
            // With the lighter set S stored as ranges T, S = T or S = T', and H ^ T' = (H ^ T)'.
            int[] lighterRanges = lighter.storedRanges(false);
            if (heavier.fewStoredRangesInside(lighterRanges)) {
                heavier.convertToTree();
                for (int index = 0; index < lighterRanges.length; index += 2) {
                    heavier.invertTree(lighterRanges[index], lighterRanges[index + 1]);
                }
                heavier.complemented ^= lighter.complemented;
            }
            else {
                combineBuilders(heavier, lighter, operation);
            }
        }
        else {
            heavier.convertToTree();
            // With the heavier set H stored as ranges R, H = R or H = R'. A union adds the
            // lighter set S to R, or removes it when H = R', since R' | S = (R - S)'. An
            // intersection removes S' from R, or adds it when H = R', since R' & S = (R | S')'.
            boolean union = operation == Operation.UNION;
            boolean add = union != heavier.complemented;
            int[] lighterRanges = lighter.storedRanges(union == lighter.complemented);
            for (int index = 0; index < lighterRanges.length; index += 2) {
                heavier.updateTree(lighterRanges[index], lighterRanges[index + 1], add);
            }
        }
        heavier.weight = weight;
        return heavier;
    }

    private static void combineBuilders(RuneRangeSet heavier, RuneRangeSet lighter, Operation operation)
    {
        CharClassBuilder heavierBuilder = heavier.toCharClassBuilder();
        CharClassBuilder lighterBuilder = lighter.toCharClassBuilder();
        switch (operation) {
            case UNION -> heavierBuilder.addCharClass(lighterBuilder);
            case INTERSECTION -> heavierBuilder.retainAll(lighterBuilder);
            case SYMMETRIC_DIFFERENCE -> {
                CharClassBuilder common = heavierBuilder.copy();
                common.retainAll(lighterBuilder);
                common.negate();
                heavierBuilder.addCharClass(lighterBuilder);
                heavierBuilder.retainAll(common);
            }
        }
    }

    // Inverting a range costs a tree update per stored range inside it, so when the lighter
    // set's ranges hold more than about n / log n of the n stored ranges, one linear pass over
    // both sets is cheaper.
    private boolean fewStoredRangesInside(int[] lighterRanges)
    {
        int storedCount = storedRangeCount();
        int limit = storedCount / max(1, Integer.SIZE - Integer.numberOfLeadingZeros(storedCount));
        int inside = 0;
        for (int index = 0; index < lighterRanges.length; index += 2) {
            int low = lighterRanges[index];
            int high = lighterRanges[index + 1];
            if (builder != null) {
                inside += firstRangeFrom(high + 1) - firstRangeFrom(low);
                if (inside > limit) {
                    return false;
                }
            }
            else {
                for (Integer _ : ranges.subMap(low, true, high, true).keySet()) {
                    inside++;
                    if (inside > limit) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // Returns the index of the builder's first range starting at or after the rune.
    private int firstRangeFrom(int rune)
    {
        int low = 0;
        int high = builder.rangeCount();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (builder.range(middle).low() < rune) {
                low = middle + 1;
            }
            else {
                high = middle;
            }
        }
        return low;
    }

    private void convertToTree()
    {
        if (ranges != null) {
            return;
        }
        // The tree takes the builder's sorted ranges in one pass rather than one insert per range.
        ranges = new TreeMap<>(new SortedRanges(builder));
        builder = null;
    }

    private int storedRangeCount()
    {
        return (builder != null) ? builder.rangeCount() : ranges.size();
    }

    // Returns the stored ranges, or the gaps between them, as low and high pairs in order.
    private int[] storedRanges(boolean gaps)
    {
        int storedCount = storedRangeCount();
        int[] stored = new int[2 * storedCount];
        if (builder != null) {
            for (int index = 0; index < storedCount; index++) {
                RuneRange runeRange = builder.range(index);
                stored[2 * index] = runeRange.low();
                stored[2 * index + 1] = runeRange.high();
            }
        }
        else {
            int index = 0;
            for (Map.Entry<Integer, Integer> entry : ranges.entrySet()) {
                stored[index++] = entry.getKey();
                stored[index++] = entry.getValue();
            }
        }
        if (!gaps) {
            return stored;
        }

        int[] gapRanges = new int[stored.length + 2];
        int gapIndex = 0;
        int next = 0;
        for (int index = 0; index < stored.length; index += 2) {
            if (next < stored[index]) {
                gapRanges[gapIndex++] = next;
                gapRanges[gapIndex++] = stored[index] - 1;
            }
            next = stored[index + 1] + 1;
        }
        if (next <= RUNEMAX) {
            gapRanges[gapIndex++] = next;
            gapRanges[gapIndex++] = RUNEMAX;
        }
        return (gapIndex == gapRanges.length) ? gapRanges : Arrays.copyOf(gapRanges, gapIndex);
    }

    // Adds [low, high] to the stored ranges, or removes it.
    private void updateTree(int low, int high, boolean add)
    {
        if (add) {
            // Absorb a range that starts at or before low and reaches low - 1 or beyond, and
            // every range that starts within [low, high + 1].
            Map.Entry<Integer, Integer> before = ranges.floorEntry(low);
            if (before != null && before.getValue() >= low - 1) {
                if (before.getValue() >= high) {
                    return;
                }
                low = before.getKey();
            }
            NavigableMap<Integer, Integer> absorbed = ranges.subMap(low, true, high + 1, true);
            if (!absorbed.isEmpty()) {
                high = max(high, absorbed.lastEntry().getValue());
                absorbed.clear();
            }
            ranges.put(low, high);
            return;
        }

        // Trim a range that starts before low, then drop every range that starts within
        // [low, high], keeping any part of the last one beyond high.
        Map.Entry<Integer, Integer> before = ranges.lowerEntry(low);
        if (before != null && before.getValue() >= low) {
            ranges.put(before.getKey(), low - 1);
            if (before.getValue() > high) {
                ranges.put(high + 1, before.getValue());
                return;
            }
        }
        NavigableMap<Integer, Integer> removed = ranges.subMap(low, true, high, true);
        if (!removed.isEmpty()) {
            int removedHigh = removed.lastEntry().getValue();
            removed.clear();
            if (removedHigh > high) {
                ranges.put(high + 1, removedHigh);
            }
        }
    }

    // Inverts the stored ranges within [low, high]: the gaps between the stored ranges there
    // become the stored ranges.
    private void invertTree(int low, int high)
    {
        int next = low;
        Map.Entry<Integer, Integer> before = ranges.lowerEntry(low);
        if (before != null && before.getValue() >= low) {
            ranges.put(before.getKey(), low - 1);
            if (before.getValue() > high) {
                ranges.put(high + 1, before.getValue());
                return;
            }
            next = before.getValue() + 1;
        }
        NavigableMap<Integer, Integer> inside = ranges.subMap(low, true, high, true);
        int[] gaps = new int[2 * (inside.size() + 1)];
        int gapIndex = 0;
        int insideHigh = -1;
        for (Map.Entry<Integer, Integer> entry : inside.entrySet()) {
            if (next < entry.getKey()) {
                gaps[gapIndex++] = next;
                gaps[gapIndex++] = entry.getKey() - 1;
            }
            insideHigh = entry.getValue();
            next = insideHigh + 1;
        }
        if (next <= high) {
            gaps[gapIndex++] = next;
            gaps[gapIndex++] = high;
        }
        inside.clear();
        if (insideHigh > high) {
            ranges.put(high + 1, insideHigh);
        }
        // Adding merges the first and last gaps with a stored range they touch outside [low, high].
        for (int index = 0; index < gapIndex; index += 2) {
            updateTree(gaps[index], gaps[index + 1], true);
        }
    }

    private enum Operation
    {
        UNION,
        INTERSECTION,
        SYMMETRIC_DIFFERENCE,
    }

    // A builder's ranges as a sorted map from low to high, which TreeMap copies in linear time.
    // TreeMap's constructor takes that path for any SortedMap and then reads only the comparator,
    // size and entries, so this implements only SortedMap, the interface the linear path needs.
    @SuppressWarnings("JdkObsolete")
    private static final class SortedRanges
            extends AbstractMap<Integer, Integer>
            implements SortedMap<Integer, Integer>
    {
        private final CharClassBuilder builder;

        private SortedRanges(CharClassBuilder builder)
        {
            this.builder = builder;
        }

        @Override
        public Comparator<? super Integer> comparator()
        {
            return null;
        }

        @Override
        public int size()
        {
            return builder.rangeCount();
        }

        @Override
        public Set<Map.Entry<Integer, Integer>> entrySet()
        {
            return new AbstractSet<>()
            {
                @Override
                public int size()
                {
                    return builder.rangeCount();
                }

                @Override
                public Iterator<Map.Entry<Integer, Integer>> iterator()
                {
                    return new Iterator<>()
                    {
                        private int index;

                        @Override
                        public boolean hasNext()
                        {
                            return index < builder.rangeCount();
                        }

                        @Override
                        public Map.Entry<Integer, Integer> next()
                        {
                            RuneRange runeRange = builder.range(index++);
                            return Map.entry(runeRange.low(), runeRange.high());
                        }
                    };
                }
            };
        }

        @Override
        public SortedMap<Integer, Integer> subMap(Integer fromKey, Integer toKey)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public SortedMap<Integer, Integer> headMap(Integer toKey)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public SortedMap<Integer, Integer> tailMap(Integer fromKey)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Integer firstKey()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Integer lastKey()
        {
            throw new UnsupportedOperationException();
        }
    }
}
