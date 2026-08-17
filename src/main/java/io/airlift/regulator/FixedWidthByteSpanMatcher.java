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
import java.util.List;

import static java.util.Objects.requireNonNull;

final class FixedWidthByteSpanMatcher
{
    private static final int MAXIMUM_WIDTH = 64;
    private static final int MAXIMUM_ANALYSIS_DEPTH = 256;

    private final long[] lowMasks;
    private final long[] highMasks;
    private final int candidateOffset;
    private final Slice candidate;

    private FixedWidthByteSpanMatcher(long[] lowMasks, long[] highMasks, int candidateOffset, int candidateByte)
    {
        this.lowMasks = lowMasks;
        this.highMasks = highMasks;
        this.candidateOffset = candidateOffset;
        this.candidate = Slices.wrappedBuffer((byte) candidateByte);
    }

    static FixedWidthByteSpanMatcher analyze(Regexp regexp, ExpressionAnalysis analysis)
    {
        if (analysis.hasCaptures() ||
                analysis.hasFoldCaseLiteral() ||
                analysis.hasNonGreedyRepetition() ||
                analysis.hasUnsupportedPositionAssertions() ||
                analysis.canMatchEmpty()) {
            return null;
        }
        return analyze(regexp);
    }

    static FixedWidthByteSpanMatcher analyze(Regexp regexp)
    {
        List<BytePredicate> predicates = new ArrayList<>();
        if (!append(regexp, predicates, 0) || predicates.isEmpty() || predicates.size() > MAXIMUM_WIDTH) {
            return null;
        }

        int candidateOffset = -1;
        int candidateByte = -1;
        int minimumCandidateCount = Integer.MAX_VALUE;
        boolean allSingleton = true;
        long[] lowMasks = new long[predicates.size()];
        long[] highMasks = new long[predicates.size()];
        for (int index = 0; index < predicates.size(); index++) {
            BytePredicate predicate = predicates.get(index);
            lowMasks[index] = predicate.lowMask();
            highMasks[index] = predicate.highMask();
            int candidateCount = Long.bitCount(predicate.lowMask()) + Long.bitCount(predicate.highMask());
            allSingleton &= candidateCount == 1;
            if (candidateCount < minimumCandidateCount) {
                minimumCandidateCount = candidateCount;
                candidateOffset = index;
                candidateByte = singletonByte(predicate);
            }
        }

        // Exact literals already have a smaller dedicated route. A singleton candidate keeps the
        // short-input scan independent of the general DFA byte-set machinery.
        if (allSingleton || minimumCandidateCount != 1) {
            return null;
        }
        return new FixedWidthByteSpanMatcher(lowMasks, highMasks, candidateOffset, candidateByte);
    }

    static TrinoRegexp createTrinoRegexp(Re2 pattern)
    {
        FixedWidthByteSpanMatcher spanMatcher = pattern.createFixedWidthByteSpanMatcher();
        if (spanMatcher == null) {
            return null;
        }
        return new TrinoRegexp.FixedWidthByteTrinoRegexp(pattern, spanMatcher);
    }

    long estimatedRetainedSize()
    {
        return SizeOf.instanceSize(FixedWidthByteSpanMatcher.class) +
                SizeOf.sizeOf(lowMasks) +
                SizeOf.sizeOf(highMasks) +
                candidate.getRetainedSize();
    }

    private static int singletonByte(BytePredicate predicate)
    {
        if (predicate.lowMask() != 0) {
            return Long.numberOfTrailingZeros(predicate.lowMask());
        }
        return 64 + Long.numberOfTrailingZeros(predicate.highMask());
    }

    private static boolean append(Regexp regexp, List<BytePredicate> predicates, int depth)
    {
        if (depth >= MAXIMUM_ANALYSIS_DEPTH || predicates.size() > MAXIMUM_WIDTH) {
            return false;
        }
        return switch (regexp.op()) {
            case EMPTY_MATCH -> true;
            case LITERAL -> appendLiteral(regexp, new int[] {regexp.rune()}, predicates);
            case LITERAL_STRING -> appendLiteral(regexp, regexp.runes(), predicates);
            case CHAR_CLASS -> appendCharacterClass(regexp, predicates);
            case CONCAT -> appendChildren(regexp, predicates, depth);
            case REPEAT -> appendRepeat(regexp, predicates, depth);
            default -> false;
        };
    }

    private static boolean appendChildren(Regexp regexp, List<BytePredicate> predicates, int depth)
    {
        for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
            if (!append(regexp.child(childIndex), predicates, depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean appendRepeat(Regexp regexp, List<BytePredicate> predicates, int depth)
    {
        if (regexp.min() != regexp.max() || regexp.min() < 0) {
            return false;
        }
        for (int repetition = 0; repetition < regexp.min(); repetition++) {
            if (!append(regexp.child(0), predicates, depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean appendLiteral(Regexp regexp, int[] runes, List<BytePredicate> predicates)
    {
        if ((regexp.parseFlags() & Regexp.FOLD_CASE) != 0) {
            return false;
        }
        for (int rune : runes) {
            if (rune < 0 || rune > 0x7F || predicates.size() == MAXIMUM_WIDTH) {
                return false;
            }
            predicates.add(BytePredicate.singleton(rune));
        }
        return true;
    }

    private static boolean appendCharacterClass(Regexp regexp, List<BytePredicate> predicates)
    {
        if (predicates.size() == MAXIMUM_WIDTH) {
            return false;
        }
        long lowMask = 0;
        long highMask = 0;
        CharClass characterClass = regexp.charClass();
        for (int rangeIndex = 0; rangeIndex < characterClass.rangeCount(); rangeIndex++) {
            RuneRange range = characterClass.range(rangeIndex);
            if (range.low() < 0 || range.high() > 0x7F) {
                return false;
            }
            for (int value = range.low(); value <= range.high(); value++) {
                if (value < 64) {
                    lowMask |= 1L << value;
                }
                else {
                    highMask |= 1L << (value - 64);
                }
            }
        }
        if ((lowMask | highMask) == 0) {
            return false;
        }
        predicates.add(new BytePredicate(lowMask, highMask));
        return true;
    }

    long findSpan(Slice input, int start)
    {
        requireNonNull(input, "input is null");
        int width = lowMasks.length;
        int lastStart = input.length() - width;
        if (start < 0 || start > lastStart) {
            return Dfa.SEARCH_NO_MATCH;
        }

        int lastCandidate = lastStart + candidateOffset;
        int candidatePosition = start + candidateOffset;
        while (candidatePosition <= lastCandidate) {
            int found = input.indexOf(candidate, candidatePosition);
            if (found < 0 || found > lastCandidate) {
                return Dfa.SEARCH_NO_MATCH;
            }
            int matchStart = found - candidateOffset;
            if (matches(input, matchStart)) {
                int matchEnd = matchStart + width;
                return ((long) matchStart << 32) | (matchEnd & 0xFFFF_FFFFL);
            }
            candidatePosition = found + 1;
        }
        return Dfa.SEARCH_NO_MATCH;
    }

    private boolean matches(Slice input, int start)
    {
        byte[] bytes = input.byteArray();
        int inputOffset = input.byteArrayOffset() + start;
        for (int index = 0; index < lowMasks.length; index++) {
            if (index == candidateOffset) {
                continue;
            }
            int value = bytes[inputOffset + index];
            if (value < 0) {
                return false;
            }
            long mask = value < 64 ? lowMasks[index] : highMasks[index];
            if ((mask & (1L << (value & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    private record BytePredicate(long lowMask, long highMask)
    {
        private static BytePredicate singleton(int value)
        {
            return value < 64
                    ? new BytePredicate(1L << value, 0)
                    : new BytePredicate(0, 1L << (value - 64));
        }
    }
}
