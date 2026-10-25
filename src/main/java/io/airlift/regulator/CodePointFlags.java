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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.airlift.regulator.CharClass.RUNEMAX;
import static java.util.Objects.requireNonNull;

/// Up to eight flags for every code point from 0 through `RUNEMAX`, held in a two-stage table
/// of flag bytes. Each block of 256 code points has an offset into the flags, and blocks with the
/// same flags are stored once, so the long runs of unassigned, private-use, and ideographic code
/// points share a few blocks. The Latin-1 block is stored first, at offset 0, so [#get]
/// reads a Latin-1 code point without the first stage. The arrays sit in final fields so that a
/// plain read of a reference to the table publishes their contents.
final class CodePointFlags
{
    private static final int BLOCK_SHIFT = 8;
    private static final int BLOCK_SIZE = 1 << BLOCK_SHIFT;
    private static final int BLOCK_MASK = BLOCK_SIZE - 1;
    private static final int MAX_FLAGS = Byte.SIZE;

    private final int[] blockOffsets;
    private final byte[] flags;

    /// Gives each code point flag `1 << index` for each class at `index` that holds it.
    CodePointFlags(List<CharClass> classes)
    {
        requireNonNull(classes, "classes is null");
        if (classes.size() > MAX_FLAGS) {
            throw new IllegalArgumentException("more than " + MAX_FLAGS + " classes");
        }
        int[] rangeIndexes = new int[classes.size()];
        int[] blockOffsets = new int[(RUNEMAX + 1) >>> BLOCK_SHIFT];
        Map<ByteBuffer, Integer> offsetsByBlock = new HashMap<>();
        byte[] flags = new byte[BLOCK_SIZE * 16];
        int flagsLength = 0;
        byte[] blockFlags = new byte[BLOCK_SIZE];
        for (int block = 0; block < blockOffsets.length; block++) {
            Arrays.fill(blockFlags, (byte) 0);
            int blockLow = block << BLOCK_SHIFT;
            int blockHigh = blockLow + BLOCK_MASK;
            for (int index = 0; index < classes.size(); index++) {
                CharClass characterClass = classes.get(index);
                // Ranges ascend and do not overlap, so each class resumes at the first range that
                // can reach this block.
                while (rangeIndexes[index] < characterClass.rangeCount()) {
                    RuneRange range = characterClass.range(rangeIndexes[index]);
                    if (range.low() > blockHigh) {
                        break;
                    }
                    for (int codePoint = Math.max(range.low(), blockLow); codePoint <= Math.min(range.high(), blockHigh); codePoint++) {
                        blockFlags[codePoint & BLOCK_MASK] |= (byte) (1 << index);
                    }
                    if (range.high() > blockHigh) {
                        break;
                    }
                    rangeIndexes[index]++;
                }
            }

            Integer offset = offsetsByBlock.get(ByteBuffer.wrap(blockFlags));
            if (offset == null) {
                offset = flagsLength;
                if (flags.length < flagsLength + BLOCK_SIZE) {
                    flags = Arrays.copyOf(flags, flags.length * 2);
                }
                System.arraycopy(blockFlags, 0, flags, flagsLength, BLOCK_SIZE);
                flagsLength += BLOCK_SIZE;
                offsetsByBlock.put(ByteBuffer.wrap(blockFlags.clone()), offset);
            }
            blockOffsets[block] = offset;
        }
        this.blockOffsets = blockOffsets;
        this.flags = Arrays.copyOf(flags, flagsLength);
    }

    /// Returns the flags of `codePoint`, which must be from 0 through `RUNEMAX`.
    int get(int codePoint)
    {
        if (codePoint < BLOCK_SIZE) {
            return flags[codePoint] & 0xFF;
        }
        return flags[blockOffsets[codePoint >>> BLOCK_SHIFT] + (codePoint & BLOCK_MASK)] & 0xFF;
    }
}
