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

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntPredicate;
import java.util.function.IntToLongFunction;

/// The hash of the open-addressing tables built while compiling a pattern. A seed chosen once per
/// process varies where keys land, so a set of colliding keys cannot be worked out ahead of time.
/// A table that uses this hash must never let slot order reach its output, so the seed never
/// changes a compiled pattern; `TestProgCompileEquivalence` checks the tables `HashSeedProbe`
/// exercises.
final class SeededHash
{
    private static final long SEED = ThreadLocalRandom.current().nextLong();

    private SeededHash() {}

    /// Returns the seeded MurmurHash3 `fmix64` of `key`, truncated to an `int`.
    static int hash(long key)
    {
        key ^= SEED;
        key ^= key >>> 33;
        key *= 0xFF51AFD7ED558CCDL;
        key ^= key >>> 33;
        key *= 0xC4CEB9FE1A85EC53L;
        key ^= key >>> 33;
        return (int) key;
    }

    /// Returns this process's seed, so a test can tell two processes apart.
    static long seedForTesting()
    {
        return SEED;
    }

    /// Where a linear-probing table's keys ended up: how many it holds, its slot count, and the
    /// total wrapping distance of its keys from their home slots `hash(key) & (capacity - 1)`.
    /// The home slots come from the seeded [#hash], so keys a table placed with any other hash
    /// land far from them. Keys that collide on one home slot make the distance grow with the
    /// square of their count.
    record Placement(int keyCount, int capacity, long displacement)
    {
        /// Measures a table of `capacity` slots, where `occupied` marks the slots that hold a
        /// key and `key` returns it.
        static Placement of(int capacity, IntPredicate occupied, IntToLongFunction key)
        {
            int keyCount = 0;
            long displacement = 0;
            for (int slot = 0; slot < capacity; slot++) {
                if (occupied.test(slot)) {
                    keyCount++;
                    displacement += (slot - hash(key.applyAsLong(slot))) & (capacity - 1);
                }
            }
            return new Placement(keyCount, capacity, displacement);
        }
    }
}
