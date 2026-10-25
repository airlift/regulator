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

import static io.airlift.regulator.CharClass.RUNEMAX;
import static java.util.Objects.requireNonNull;

/// An int for every code point from 0 through `RUNEMAX`, held in a two-stage table of deltas
/// from the code point. Each block of 256 code points has an offset into the deltas; every block
/// without a mapping shares the all-zero block at offset 0, so a code point without a mapping
/// maps to itself, and a table of a few thousand mappings holds a few dozen blocks rather than an
/// entry per code point. The arrays sit in final fields so that a plain read of a reference to the
/// table publishes their contents.
final class CodePointTable
{
    private static final int BLOCK_SHIFT = 8;
    private static final int BLOCK_SIZE = 1 << BLOCK_SHIFT;
    private static final int BLOCK_MASK = BLOCK_SIZE - 1;

    private final int[] blockOffsets;
    private final int[] deltas;

    /// Maps each of the strictly ascending `codePoints` to the value at the same index, and
    /// every other code point to itself.
    CodePointTable(int[] codePoints, int[] values)
    {
        requireNonNull(codePoints, "codePoints is null");
        requireNonNull(values, "values is null");
        if (codePoints.length != values.length) {
            throw new IllegalArgumentException("codePoints and values differ in length");
        }
        int blockCount = 0;
        int previousCodePoint = -1;
        for (int codePoint : codePoints) {
            if (codePoint <= previousCodePoint || codePoint > RUNEMAX) {
                throw new IllegalArgumentException("code points are not ascending in range: " + codePoint);
            }
            if (previousCodePoint < 0 || (codePoint >>> BLOCK_SHIFT) != (previousCodePoint >>> BLOCK_SHIFT)) {
                blockCount++;
            }
            previousCodePoint = codePoint;
        }

        int[] blockOffsets = new int[(RUNEMAX + 1) >>> BLOCK_SHIFT];
        int[] deltas = new int[(blockCount + 1) * BLOCK_SIZE];
        int blockOffset = 0;
        int previousBlock = -1;
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            int block = codePoint >>> BLOCK_SHIFT;
            if (block != previousBlock) {
                blockOffset += BLOCK_SIZE;
                blockOffsets[block] = blockOffset;
                previousBlock = block;
            }
            deltas[blockOffset + (codePoint & BLOCK_MASK)] = values[index] - codePoint;
        }
        this.blockOffsets = blockOffsets;
        this.deltas = deltas;
    }

    /// Returns the value of `codePoint`, which must be from 0 through `RUNEMAX`.
    int get(int codePoint)
    {
        return codePoint + deltas[blockOffsets[codePoint >>> BLOCK_SHIFT] + (codePoint & BLOCK_MASK)];
    }
}
