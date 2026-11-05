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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestSeededHash
{
    // Placement is measured from each key's seeded home slot, so a table that places keys with
    // any other hash leaves them far from home. Keys that collided under the old unseeded fmix64
    // sit in one chain, n(n-1)/2 slots from home in all, if the seed stops reaching the hash. At
    // these tables' final loads of at most one half, a random hash averages under one slot a key;
    // eight leaves ample slack while rejecting the old colliding placements. Each table ends at
    // 2048 slots with hundreds of keys inserted after its last resize, so a table that rehashes
    // with the seeded hash but inserts with another one fails too.
    private static final int KEY_COUNT = 1024;
    private static final int CAPACITY = 2048;
    private static final int MAXIMUM_DISPLACEMENT_PER_KEY = 8;

    /// A class whose member-table masks all collide under unseeded fmix64 still spreads them over
    /// the mask table, both the masks its last resize placed and those inserted after it.
    @Test
    public void testCollidingMasksSpreadOverMaskTable()
    {
        // fmix64 is invertible, so these masks have unseeded hashes with 32 low zero bits, which
        // is home slot 0 at every table size. Each is the last-byte mask of the three-byte prefix
        // shared by the 64 code points from 0x10000 + 64 * index.
        long[] masks = new long[KEY_COUNT - 1];
        for (int index = 0; index < masks.length; index++) {
            masks[index] = unmix((long) (index + 1) << 32);
            assertThat((int) unseededHash(masks[index])).isZero();
        }
        List<RuneRange> ranges = new ArrayList<>();
        int runeCount = 0;
        for (int rune = 0x10000; rune < 0x10000 + 64 * masks.length; rune++) {
            if ((masks[(rune - 0x10000) >>> 6] & (1L << (rune & 0x3F))) != 0) {
                RuneRange last = ranges.isEmpty() ? null : ranges.getLast();
                if (last != null && last.high() == rune - 1) {
                    ranges.set(ranges.size() - 1, new RuneRange(last.low(), rune));
                }
                else {
                    ranges.add(new RuneRange(rune, rune));
                }
                runeCount++;
            }
        }

        SeededHash.Placement placement = Utf8MemberTable.maskPlacementForTesting(new CharClass(false, runeCount, ranges.toArray(RuneRange[]::new)));

        // The table also holds the zero mask, which collides with the others. Its 513th key
        // resizes it to 2048 slots, and the last 511 keys are inserted after that.
        assertSpread(placement, KEY_COUNT, CAPACITY);
    }

    /// Range groups that all collide under unseeded fmix64 still spread over the byte map's
    /// merged-group set, both the groups its last resize placed and those inserted after it.
    @Test
    public void testCollidingRangeGroupsSpreadOverMergedGroupSet()
    {
        // The set records groups only after the first 32 merges. Its 513th group resizes it to
        // 2048 slots, and the last 511 groups are inserted after that.
        Prog.ByteMapBuilder builder = new Prog.ByteMapBuilder();
        for (long key : collidingGroupKeys(32 + KEY_COUNT, CAPACITY)) {
            builder.mark((int) (key >>> 24) & 0xFF, (int) (key >>> 16) & 0xFF);
            builder.mark((int) (key >>> 8) & 0xFF, (int) key & 0xFF);
            builder.merge();
        }

        assertSpread(builder.mergedGroupPlacementForTesting(), KEY_COUNT, CAPACITY);
    }

    /// Rune-suffix keys that all collide under unseeded fmix64 still spread over the rune cache,
    /// both the keys its last resize placed and those inserted after it.
    @Test
    public void testCollidingRuneSuffixesSpreadOverRuneCache()
    {
        // The cache resizes before a put that would pass three quarters full, so its 769th key
        // resizes it to 2048 slots, and the last 256 keys are inserted after that.
        assertSpread(Compiler.runeCachePlacementForTesting(collidingRuneSuffixKeys(KEY_COUNT, CAPACITY)), KEY_COUNT, CAPACITY);
    }

    private static void assertSpread(SeededHash.Placement placement, int keyCount, int capacity)
    {
        assertThat(placement.keyCount()).isEqualTo(keyCount);
        assertThat(placement.capacity()).isEqualTo(capacity);
        assertThat(placement.displacement()).isLessThanOrEqualTo((long) MAXIMUM_DISPLACEMENT_PER_KEY * keyCount);
    }

    // Packed groups of two byte ranges, as the byte map packs them, whose unseeded hash is home
    // slot 0 in every set of up to `capacity` slots. Every low byte is nonzero, so neither range
    // is the [00-ff] range that marking ignores.
    private static long[] collidingGroupKeys(int count, int capacity)
    {
        long[] keys = new long[count];
        int found = 0;
        for (long ranges = 1L << 24; found < count; ranges++) {
            int firstLow = (int) (ranges >>> 24) & 0xFF;
            int firstHigh = (int) (ranges >>> 16) & 0xFF;
            int secondLow = (int) (ranges >>> 8) & 0xFF;
            int secondHigh = (int) ranges & 0xFF;
            long key = (2L << 32) | ranges;
            if (firstLow != 0 && firstLow <= firstHigh && secondLow != 0 && secondLow <= secondHigh &&
                    (unseededHash(key) & (capacity - 1)) == 0) {
                keys[found++] = key;
            }
        }
        return keys;
    }

    // Rune-suffix keys the compiler caches, laid out as its rune cache lays them out (next
    // instruction, low byte, high byte, fold flag), whose unseeded hash is home slot 0 in every
    // table of up to `capacity` slots. The compiler's addEncodedRange caches a byte range that
    // continues to another instruction only for continuation bytes [80-bf], never case folded.
    private static long[] collidingRuneSuffixKeys(int count, int capacity)
    {
        long[] keys = new long[count];
        int found = 0;
        for (long next = 1; ; next++) {
            for (int low = 0x80; low <= 0xBF; low++) {
                for (int high = low; high <= 0xBF; high++) {
                    long key = (next << 17) | (low << 9) | (high << 1);
                    if ((unseededHash(key) & (capacity - 1)) == 0) {
                        keys[found++] = key;
                        if (found == count) {
                            return keys;
                        }
                    }
                }
            }
        }
    }

    // MurmurHash3 fmix64, the hash the member table and rune cache used before it was seeded.
    private static long unseededHash(long key)
    {
        key ^= key >>> 33;
        key *= 0xFF51AFD7ED558CCDL;
        key ^= key >>> 33;
        key *= 0xC4CEB9FE1A85EC53L;
        return key ^ (key >>> 33);
    }

    // The inverse of unseededHash, from the modular inverses of its multipliers.
    private static long unmix(long key)
    {
        key ^= key >>> 33;
        key *= 0x9CB4B2F8129337DBL;
        key ^= key >>> 33;
        key *= 0x4F74430C22A54005L;
        return key ^ (key >>> 33);
    }
}
