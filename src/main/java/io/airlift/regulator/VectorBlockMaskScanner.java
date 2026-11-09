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

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/// The membership words of [FixedWidthByteSpanMatcher]'s block search: for 64 bytes, one bit per
/// byte, set where the byte lies in a class of one to four byte ranges. The ranges take the
/// layout [VectorByteSetScanner#findOutsideRanges] reads with the full eight-bit width, range `i`
/// in bits `16i` to `16i + 15`, its lowest byte in the low eight and its width, the highest byte
/// less the lowest, up to 255, in the high eight. [VectorSupport#isAvailable] admits preferred
/// byte vectors of 16 to 64 lanes, each a divisor of 64, so a block is a whole number of vectors
/// and each vector's mask fits a `long`.
final class VectorBlockMaskScanner
{
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    private static final int BLOCK_BYTES = Long.SIZE;

    private VectorBlockMaskScanner() {}

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Keep Vector API linkage isolated in this class, and every vector
    // and mask inside the method that builds it: one returned from a helper C2 leaves out of
    // line is boxed on every call. Callers choose between the two forms, as "Candidate Masks on
    // SVE" in REGULATOR_DECISIONS.md records.

    /// Returns the word whose bit `i` is set where `data[position + i]` lies in one of the
    /// `rangeCount` ranges, for the 64 bytes from `position`. Each range compares its offset from
    /// the lowest byte with the width, and the masks are ORed. The SVE form is [#membersSve]; keep
    /// the two in step.
    static long members(byte[] data, int position, long ranges, int rangeCount)
    {
        ByteVector firstLowest = ByteVector.broadcast(SPECIES, (byte) ranges);
        ByteVector firstWidth = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 8));
        ByteVector secondLowest = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 16));
        ByteVector secondWidth = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 24));
        ByteVector thirdLowest = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 32));
        ByteVector thirdWidth = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 40));
        ByteVector fourthLowest = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 48));
        ByteVector fourthWidth = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 56));
        long members = 0;
        for (int lane = 0; lane < BLOCK_BYTES; lane += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position + lane);
            VectorMask<Byte> inside = values.sub(firstLowest).compare(VectorOperators.ULE, firstWidth);
            if (rangeCount > 1) {
                inside = inside.or(values.sub(secondLowest).compare(VectorOperators.ULE, secondWidth));
            }
            if (rangeCount > 2) {
                inside = inside.or(values.sub(thirdLowest).compare(VectorOperators.ULE, thirdWidth));
            }
            if (rangeCount > 3) {
                inside = inside.or(values.sub(fourthLowest).compare(VectorOperators.ULE, fourthWidth));
            }
            members |= inside.toLong() << lane;
        }
        return members;
    }

    /// [#members] in the form [VectorSupport#SVE_128] selects: each range's distance past its
    /// width, the offset from its lowest byte less the width with unsigned saturation, is zero
    /// inside it. The unsigned minimum joins the ranges, and one compare with zero gives the mask.
    static long membersSve(byte[] data, int position, long ranges, int rangeCount)
    {
        ByteVector firstLowest = ByteVector.broadcast(SPECIES, (byte) ranges);
        ByteVector firstWidth = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 8));
        ByteVector secondLowest = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 16));
        ByteVector secondWidth = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 24));
        ByteVector thirdLowest = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 32));
        ByteVector thirdWidth = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 40));
        ByteVector fourthLowest = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 48));
        ByteVector fourthWidth = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 56));
        long members = 0;
        for (int lane = 0; lane < BLOCK_BYTES; lane += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position + lane);
            ByteVector distance = values.sub(firstLowest).lanewise(VectorOperators.SUSUB, firstWidth);
            if (rangeCount > 1) {
                distance = distance.lanewise(VectorOperators.UMIN, values.sub(secondLowest).lanewise(VectorOperators.SUSUB, secondWidth));
            }
            if (rangeCount > 2) {
                distance = distance.lanewise(VectorOperators.UMIN, values.sub(thirdLowest).lanewise(VectorOperators.SUSUB, thirdWidth));
            }
            if (rangeCount > 3) {
                distance = distance.lanewise(VectorOperators.UMIN, values.sub(fourthLowest).lanewise(VectorOperators.SUSUB, fourthWidth));
            }
            members |= distance.compare(VectorOperators.EQ, (byte) 0).toLong() << lane;
        }
        return members;
    }
}
