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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.airlift.regulator.CharClass.RUNEMAX;
import static java.lang.Math.max;
import static java.lang.Math.min;

@SuppressWarnings("CharUsedInArithmeticContext")
final class CharClassBuilder
{
    private static final int ALPHA_MASK = (1 << 26) - 1;
    // Up to this many ranges, per-range insertion avoids allocating a merged copy.
    private static final int MERGE_THRESHOLD = 8;

    private ArrayList<RuneRange> ranges = new ArrayList<>();
    private int runeCount;

    // ASCII A-Z and a-z coverage bitmasks (bit 0 = 'A'/'a').
    private int upper;
    private int lower;

    public boolean isEmpty()
    {
        return ranges.isEmpty();
    }

    public int runeCount()
    {
        return runeCount;
    }

    public int rangeCount()
    {
        return ranges.size();
    }

    public RuneRange range(int index)
    {
        return ranges.get(index);
    }

    public boolean contains(int rune)
    {
        // Search by an upper-bound key so ranges with matching low endpoint stay on the left.
        int index = Collections.binarySearch(ranges, new RuneRange(rune, Integer.MAX_VALUE));
        int candidateIndex = (index >= 0) ? index : (-index - 2);
        return candidateIndex >= 0 && ranges.get(candidateIndex).contains(rune);
    }

    public void addRange(int low, int high)
    {
        if (high < low) {
            return;
        }

        if (low <= 'z' && high >= 'A') {
            updateAsciiBitmaps(low, high);
        }

        // Append when the new range lies beyond every existing range. Parsers and Unicode
        // tables add ranges in ascending order, so this is the common case.
        int size = ranges.size();
        if (size == 0 || ranges.get(size - 1).high() + 1 < low) {
            ranges.add(new RuneRange(low, high));
            runeCount += high - low + 1;
            return;
        }

        // Merge/insert into a sorted, non-overlapping, non-adjacent list.
        int insertAt = firstRangeReaching(low);

        int newLow = low;
        int newHigh = high;
        int removeFrom = insertAt;
        int removeTo = insertAt;
        while (removeTo < size && ranges.get(removeTo).low() <= newHigh + 1) {
            RuneRange runeRange = ranges.get(removeTo);
            newLow = min(newLow, runeRange.low());
            newHigh = max(newHigh, runeRange.high());
            removeTo++;
        }

        // Update runeCount: remove any merged ranges, then add the new merged range.
        for (int removeIndex = removeFrom; removeIndex < removeTo; removeIndex++) {
            RuneRange runeRange = ranges.get(removeIndex);
            runeCount -= runeRange.high() - runeRange.low() + 1;
        }
        runeCount += newHigh - newLow + 1;

        RuneRange merged = new RuneRange(newLow, newHigh);
        if (removeFrom == removeTo) {
            ranges.add(removeFrom, merged);
        }
        else {
            ranges.set(removeFrom, merged);
            if (removeFrom + 1 != removeTo) {
                ranges.subList(removeFrom + 1, removeTo).clear();
            }
        }
    }

    // Returns the index of the first range that overlaps or is adjacent to a range starting
    // at low, or the position where such a range would be inserted.
    private int firstRangeReaching(int low)
    {
        int lowIndex = 0;
        int highIndex = ranges.size();
        while (lowIndex < highIndex) {
            int middle = (lowIndex + highIndex) >>> 1;
            if (ranges.get(middle).high() + 1 < low) {
                lowIndex = middle + 1;
            }
            else {
                highIndex = middle;
            }
        }
        return lowIndex;
    }

    // Adds all ranges from another character class builder.
    public void addCharClass(CharClassBuilder other)
    {
        addSortedRanges(other.ranges);
    }

    public void addCharClass(CharClass characterClass, int parseFlags)
    {
        boolean excludeNewline = ((parseFlags & Regexp.CLASS_NEWLINE) == 0) || ((parseFlags & Regexp.NEVER_NEWLINE) != 0);
        boolean foldCase = (parseFlags & (Regexp.ASCII_FOLD_CASE | Regexp.FOLD_CASE)) != 0;
        if (!foldCase && (!excludeNewline || !characterClass.contains('\n'))) {
            // CharClass and RuneRange are immutable, so canonical ranges can be reused directly.
            addSortedRanges(characterClass.rangeView());
            return;
        }

        for (RuneRange runeRange : characterClass.ranges()) {
            addRangeFlags(runeRange.low(), runeRange.high(), parseFlags);
        }
    }

    // Adds ranges that are already sorted, non-overlapping, and non-adjacent.
    private void addSortedRanges(List<RuneRange> source)
    {
        int sourceSize = source.size();
        if (sourceSize == 0) {
            return;
        }
        if (ranges.isEmpty()) {
            ranges.addAll(source);
            rebuildMetadata();
            return;
        }
        if (sourceSize <= MERGE_THRESHOLD) {
            for (int sourceIndex = 0; sourceIndex < sourceSize; sourceIndex++) {
                RuneRange runeRange = source.get(sourceIndex);
                addRange(runeRange.low(), runeRange.high());
            }
            return;
        }

        // Linear merge of two sorted range lists. Inserting each range individually would
        // shift the tail of the list for every insertion, which is quadratic for large
        // Unicode classes such as [\p{Lu}\p{Ll}].
        int existingSize = ranges.size();
        ArrayList<RuneRange> merged = new ArrayList<>(existingSize + sourceSize);
        int existingIndex = 0;
        int sourceIndex = 0;
        RuneRange current = null;
        while (existingIndex < existingSize || sourceIndex < sourceSize) {
            RuneRange next;
            if (sourceIndex >= sourceSize ||
                    (existingIndex < existingSize && ranges.get(existingIndex).low() <= source.get(sourceIndex).low())) {
                next = ranges.get(existingIndex++);
            }
            else {
                next = source.get(sourceIndex++);
            }
            if (current == null) {
                current = next;
            }
            else if (next.low() <= current.high() + 1) {
                if (next.high() > current.high()) {
                    current = new RuneRange(current.low(), next.high());
                }
            }
            else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        ranges = merged;
        rebuildMetadata();
    }

    public void retainAll(CharClassBuilder other)
    {
        List<RuneRange> intersection = new ArrayList<>();
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < ranges.size() && rightIndex < other.ranges.size()) {
            RuneRange left = ranges.get(leftIndex);
            RuneRange right = other.ranges.get(rightIndex);

            int low = max(left.low(), right.low());
            int high = min(left.high(), right.high());
            if (low <= high) {
                intersection.add(new RuneRange(low, high));
            }

            if (left.high() < right.high()) {
                leftIndex++;
            }
            else {
                rightIndex++;
            }
        }

        ranges.clear();
        ranges.addAll(intersection);
        rebuildMetadata();
    }

    @SuppressWarnings("BuilderReturnThis")
    public CharClassBuilder copy()
    {
        CharClassBuilder copy = new CharClassBuilder();
        copy.ranges.addAll(ranges);
        copy.runeCount = runeCount;
        copy.upper = upper;
        copy.lower = lower;
        return copy;
    }

    public void addRangeFlags(int low, int high, int parseFlags)
    {
        // Exclude \n unless CLASS_NEWLINE is set, or NEVER_NEWLINE forces exclusion.
        boolean excludeNewline = ((parseFlags & Regexp.CLASS_NEWLINE) == 0) || ((parseFlags & Regexp.NEVER_NEWLINE) != 0);
        if (excludeNewline && low <= '\n' && '\n' <= high) {
            if (low < '\n') {
                addRangeFlags(low, '\n' - 1, parseFlags);
            }
            if (high > '\n') {
                addRangeFlags('\n' + 1, high, parseFlags);
            }
            return;
        }

        // If folding case, add fold-equivalent characters too.
        if ((parseFlags & Regexp.ASCII_FOLD_CASE) != 0) {
            addRange(low, high);
            int upperLow = max(low, 'A');
            int upperHigh = min(high, 'Z');
            if (upperLow <= upperHigh) {
                addRange(upperLow - 'A' + 'a', upperHigh - 'A' + 'a');
            }
            int lowerLow = max(low, 'a');
            int lowerHigh = min(high, 'z');
            if (lowerLow <= lowerHigh) {
                addRange(lowerLow - 'a' + 'A', lowerHigh - 'a' + 'A');
            }
            return;
        }
        if ((parseFlags & Regexp.FOLD_CASE) != 0) {
            if ((parseFlags & Regexp.LATIN1) != 0) {
                addFoldedRangeLatin1(low, high);
            }
            else {
                addFoldedRange(low, high);
            }
            return;
        }

        addRange(low, high);
    }

    private void addFoldedRangeLatin1(int low, int high)
    {
        while (low <= high) {
            addRange(low, low);
            if ('A' <= low && low <= 'Z') {
                addRange(low - 'A' + 'a', low - 'A' + 'a');
            }
            if ('a' <= low && low <= 'z') {
                addRange(low - 'a' + 'A', low - 'a' + 'A');
            }
            low++;
        }
    }

    private void addFoldedRange(int low, int high)
    {
        addRange(low, high);

        int rune = UnicodeCaseFold.nextCaseFoldedRuneAtOrAfter(low);
        while (rune >= 0 && rune <= high) {
            int foldedRune = UnicodeCaseFold.cycleFoldRune(rune);
            while (foldedRune != rune) {
                addRange(foldedRune, foldedRune);
                foldedRune = UnicodeCaseFold.cycleFoldRune(foldedRune);
            }
            rune = rune == CharClass.RUNEMAX ? -1 : UnicodeCaseFold.nextCaseFoldedRuneAtOrAfter(rune + 1);
        }
    }

    public boolean foldsAscii()
    {
        return ((upper ^ lower) & ALPHA_MASK) == 0;
    }

    public void negate()
    {
        List<RuneRange> negatedRanges = new ArrayList<>();
        int next = 0;
        for (RuneRange runeRange : ranges) {
            if (next < runeRange.low()) {
                negatedRanges.add(new RuneRange(next, runeRange.low() - 1));
            }
            next = runeRange.high() + 1;
        }
        if (next <= RUNEMAX) {
            negatedRanges.add(new RuneRange(next, RUNEMAX));
        }

        ranges.clear();
        ranges.addAll(negatedRanges);

        runeCount = (RUNEMAX + 1) - runeCount;
        upper = ALPHA_MASK & ~upper;
        lower = ALPHA_MASK & ~lower;
    }

    public void removeAbove(int runeLimit)
    {
        if (ranges.isEmpty()) {
            return;
        }

        int rangeIndex = 0;
        while (rangeIndex < ranges.size()) {
            RuneRange runeRange = ranges.get(rangeIndex);
            if (runeRange.low() > runeLimit) {
                // remove whole suffix
                for (int removeIndex = rangeIndex; removeIndex < ranges.size(); removeIndex++) {
                    RuneRange removed = ranges.get(removeIndex);
                    runeCount -= removed.high() - removed.low() + 1;
                }
                ranges.subList(rangeIndex, ranges.size()).clear();
                return;
            }
            if (runeRange.high() > runeLimit) {
                // trim this range
                runeCount -= runeRange.high() - runeRange.low() + 1;
                RuneRange trimmed = new RuneRange(runeRange.low(), runeLimit);
                runeCount += trimmed.high() - trimmed.low() + 1;
                ranges.set(rangeIndex, trimmed);
                // remove rest
                for (int removeIndex = rangeIndex + 1; removeIndex < ranges.size(); removeIndex++) {
                    RuneRange removed = ranges.get(removeIndex);
                    runeCount -= removed.high() - removed.low() + 1;
                }
                ranges.subList(rangeIndex + 1, ranges.size()).clear();
                return;
            }
            rangeIndex++;
        }
    }

    public CharClass toCharClass()
    {
        return new CharClass(foldsAscii(), runeCount, ranges.toArray(RuneRange[]::new));
    }

    private void updateAsciiBitmaps(int low, int high)
    {
        int upperAlphaLow = max(low, 'A');
        int upperAlphaHigh = min(high, 'Z');
        if (upperAlphaLow <= upperAlphaHigh) {
            upper |= maskBits(upperAlphaLow - 'A', upperAlphaHigh - 'A');
        }
        int lowerAlphaLow = max(low, 'a');
        int lowerAlphaHigh = min(high, 'z');
        if (lowerAlphaLow <= lowerAlphaHigh) {
            lower |= maskBits(lowerAlphaLow - 'a', lowerAlphaHigh - 'a');
        }
    }

    private void rebuildMetadata()
    {
        runeCount = 0;
        upper = 0;
        lower = 0;
        for (RuneRange runeRange : ranges) {
            runeCount += runeRange.high() - runeRange.low() + 1;
            if (runeRange.low() <= 'z' && runeRange.high() >= 'A') {
                updateAsciiBitmaps(runeRange.low(), runeRange.high());
            }
        }
    }

    private static int maskBits(int from, int to)
    {
        // Inclusive range [from..to] within [0..25].
        int width = to - from + 1;
        int mask = (1 << width) - 1;
        return mask << from;
    }
}
