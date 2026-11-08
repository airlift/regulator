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

/// One byte range at each of the first two or three offsets of every match, checked together to
/// find the positions where a match can start. Each range is the smallest unsigned range that
/// covers every byte some path of the program reads at its offset, the union over all paths, and
/// every offset lies before the shortest match ends, so each match holds a byte of every range at
/// its offset and the filter accepts every position where a match can start. For the IPv4
/// address of mariomka/ip, whose matches begin with two digits and then a dot or a digit, the
/// ranges are `0-9`, `0-9`, and `.-9`.
///
/// A position is accepted when every byte of the span lies in its range: for a range of `low`
/// to `high`, `(value - low)` as an unsigned byte is at most `high - low`.
final class StartClassFilter
{
    static final long INSTANCE_SIZE = SizeOf.instanceSize(StartClassFilter.class);

    /// The most offsets a filter checks.
    static final int MAXIMUM_SPAN = 3;
    /// Start bytes rarer than this in English text already stop seldom, so a filter has little to
    /// remove; the forward DFA does not walk the program for them.
    static final double MINIMUM_START_PROBABILITY = 0.002;
    // The filter checks two or three bytes per position, so it replaces the start byte scan only
    // when it is estimated to stop this many times less often.
    private static final int NARROWING_FACTOR = 16;
    // A filter is admitted only when it is estimated to stop below once in 500 bytes, the cap
    // InnerLiteralSearch sets on a class pair: each stop starts the DFA, which usually fails
    // again within a few bytes.
    private static final double MAXIMUM_PROBABILITY = 0.002;
    // A third offset joins a pair only when it narrows the pair's estimate this many times.
    private static final int THIRD_OFFSET_NARROWING_FACTOR = 4;

    private final int span;
    private final int firstLow;
    private final int firstHigh;
    private final int secondLow;
    private final int secondHigh;
    // Unread when the span is two.
    private final int thirdLow;
    private final int thirdHigh;

    /// Creates a filter that checks the first `span` offsets, two or three, each range given by
    /// its inclusive unsigned bounds. A span of two leaves the third range unread.
    StartClassFilter(int span, int firstLow, int firstHigh, int secondLow, int secondHigh, int thirdLow, int thirdHigh)
    {
        if (span != 2 && span != 3) {
            throw new IllegalArgumentException("span must be two or three: " + span);
        }
        checkRange(firstLow, firstHigh);
        checkRange(secondLow, secondHigh);
        checkRange(thirdLow, thirdHigh);
        this.span = span;
        this.firstLow = firstLow;
        this.firstHigh = firstHigh;
        this.secondLow = secondLow;
        this.secondHigh = secondHigh;
        this.thirdLow = thirdLow;
        this.thirdHigh = thirdHigh;
    }

    /// Builds a filter from the byte sets [Prog#buildStartOffsetByteSets] returns, replacing start
    /// bytes whose English probability is `startByteProbability`. Each offset's range is the
    /// smallest unsigned range covering its bytes, which stays sound when it holds bytes no path
    /// reads there, and its estimate is the probability of that range in `byteFrequencies`, as if
    /// each byte were independent of its neighbors. A third offset is checked only when it
    /// narrows the pair's estimate [#THIRD_OFFSET_NARROWING_FACTOR] times. Returns null unless
    /// the filter is estimated to stop [#NARROWING_FACTOR] times less often than the start bytes
    /// and below [#MAXIMUM_PROBABILITY].
    static StartClassFilter build(byte[][] byteSets, ByteFrequencies byteFrequencies, double startByteProbability)
    {
        int[] lows = new int[MAXIMUM_SPAN];
        int[] highs = new int[MAXIMUM_SPAN];
        double[] probabilities = new double[byteSets.length];
        for (int offset = 0; offset < byteSets.length; offset++) {
            byte[] byteSet = byteSets[offset];
            int low = 0;
            while (byteSet[low] == 0) {
                low++;
            }
            int high = byteSet.length - 1;
            while (byteSet[high] == 0) {
                high--;
            }
            lows[offset] = low;
            highs[offset] = high;
            for (int value = low; value <= high; value++) {
                probabilities[offset] += byteFrequencies.probability((byte) value);
            }
        }
        int span = byteSets.length;
        if (span == 3 && probabilities[2] * THIRD_OFFSET_NARROWING_FACTOR > 1) {
            span = 2;
        }
        double density = 1;
        for (int offset = 0; offset < span; offset++) {
            density *= probabilities[offset];
        }
        if (density * NARROWING_FACTOR > startByteProbability || density >= MAXIMUM_PROBABILITY) {
            return null;
        }
        return new StartClassFilter(span, lows[0], highs[0], lows[1], highs[1], lows[2], highs[2]);
    }

    private static void checkRange(int low, int high)
    {
        if (low < 0 || high > 255 || low > high) {
            throw new IllegalArgumentException("invalid byte range: " + low + "-" + high);
        }
    }

    /// The number of offsets checked, two or three.
    int span()
    {
        return span;
    }

    int firstLow()
    {
        return firstLow;
    }

    int firstHigh()
    {
        return firstHigh;
    }

    int secondLow()
    {
        return secondLow;
    }

    int secondHigh()
    {
        return secondHigh;
    }

    int thirdLow()
    {
        return thirdLow;
    }

    int thirdHigh()
    {
        return thirdHigh;
    }

    /// True when the filter accepts `position`: every byte of the span lies before `end` and in its
    /// offset's range.
    boolean accepts(byte[] data, int position, int end)
    {
        if (position + span > end) {
            return false;
        }
        return ((data[position] - firstLow) & 0xFF) <= firstHigh - firstLow &&
                ((data[position + 1] - secondLow) & 0xFF) <= secondHigh - secondLow &&
                (span == 2 || ((data[position + 2] - thirdLow) & 0xFF) <= thirdHigh - thirdLow);
    }

    /// Finds the first position in `[offset, offset + length)` that the filter accepts, reading no
    /// byte at or past `offset + length`, or -1.
    int find(byte[] data, int offset, int length)
    {
        int end = offset + length;
        for (int position = offset; position + span <= end; position++) {
            if (accepts(data, position, end)) {
                return position;
            }
        }
        return -1;
    }
}
