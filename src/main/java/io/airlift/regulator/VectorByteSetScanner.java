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

final class VectorByteSetScanner
{
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    // The nibble tables are replicated to 64 bytes, one reason VectorSupport allows at most 64 lanes.
    private static final int NIBBLE_TABLE_BYTES = 64;
    private static final int HIGH_NIBBLE_TABLE_OFFSET = NIBBLE_TABLE_BYTES;
    // The low-nibble table, then the high-nibble table.
    static final int NIBBLE_TABLES_LENGTH = NIBBLE_TABLE_BYTES * 2;
    // A loop that skips ahead with these scans stops skipping once SHORT_SCAN_LIMIT scans in a
    // row each skipped fewer than SHORT_SCAN_BYTES bytes: the stops are dense, and a scan's setup
    // costs more than the few bytes it skips. The forward DFA's count self-loop scan and the
    // literal set's root skip apply the rule through shortScanCount.
    static final int SHORT_SCAN_BYTES = 16;
    static final int SHORT_SCAN_LIMIT = 4;
    // Whether the two-literal scan of a filter without masks takes the XOR form: under
    // VectorSupport.SVE_128, as the other two-literal scan does, and under
    // VectorSupport.X86_WITH_64_BYTE_VECTORS, which names the hosts measured and unmeasured. On
    // r8i, comparing each of the six bytes into its own mask register and joining the masks with
    // four kandq and a korq leaves more masks live than r8i's seven mask registers hold, so in
    // r8i's assembly one mask spilled and reloaded every step; the XOR form compares once. NEON,
    // where the XOR form took 1.05x the compare-and-AND form's time for
    // Tom.{10,25}river|river.{10,25}Tom, and other hosts keep compare and AND: AVX2 and SVE wider
    // than 16 bytes were not measured. C2 folds this constant, so the scan compiles one form.
    private static final boolean TWO_EXACT_LITERALS_XOR = VectorSupport.SVE_128 || VectorSupport.X86_WITH_64_BYTE_VECTORS;

    // Whether the nibble scan of three to eight groups takes one load per step and moves the
    // later positions' bits into place, rather than a load at each position. Only under
    // VectorSupport.X86_WITH_64_BYTE_VECTORS, which names the hosts measured and unmeasured, and
    // with AVX-512 VBMI, as on r8i, where one vpermb for each later position moves its bits and
    // rows the scan serves took 0.89x to 1.00x the sliding scan's time, against up to 1.078x of
    // it with a load at each position. Without VBMI, as on Cascade Lake (r5), C2 emulates the
    // permute, and the one-load form took up to 1.13x main's time on rows the per-position form
    // held at 0.99x to 1.03x of main's, so x86 without VBMI keeps a load at each position.
    // VectorSupport.hasAvx512Vbmi reads VBMI on Linux only; x86 on other OSes is unmeasured and
    // keeps a load at each position, as do NEON, AVX2, and SVE, 512-bit SVE's 64-byte vectors
    // included. "Start Literal Filter" in REGULATOR_DECISIONS.md records the measurements. C2
    // folds this constant, so the scan compiles one form.
    private static final boolean ONE_LOAD_NIBBLES = VectorSupport.X86_WITH_64_BYTE_VECTORS && VectorSupport.hasAvx512Vbmi();

    private VectorByteSetScanner() {}

    /// Returns the number of short scans in a row after a scan that skipped `skippedBytes`, given
    /// the `shortScanCount` before it. A caller stops skipping when it reaches [#SHORT_SCAN_LIMIT].
    static int shortScanCount(int shortScanCount, int skippedBytes)
    {
        return skippedBytes < SHORT_SCAN_BYTES ? shortScanCount + 1 : 0;
    }

    /// Returns the largest multiple of the vector length at most `length`.
    static int vectorLoopBound(int length)
    {
        return SPECIES.loopBound(length);
    }

    static boolean shouldSampleCandidateDensity()
    {
        return SPECIES.length() > 16;
    }

    // Whether a DFA whose start bytes are too rare for the start literal filter should still take
    // one that holds two literals. Only with 64-byte vectors, as with AVX-512 on r8i, where the
    // two-literal scan took 0.56x to 0.60x the single-byte scanners' time for huck-saw, 0.85x to
    // 0.96x for name-alt2 (1.00x in Trino), and 0.87x to 0.94x for name-alt4. On r9g's 16-byte
    // vectors it won huck-saw (0.84x to 0.91x) but took 1.17x to 1.19x for name-alt2 and 1.19x
    // for name-alt4 in its SVE XOR form, and 1.22x for both with NEON (1.00x for name-alt2 in
    // Trino), so 16-byte vectors keep their scanners; 32-byte vectors, as with AVX2 or 256-bit
    // SVE, were not measured.
    // "Start Literal Filter" in REGULATOR_DECISIONS.md records the measurements.
    static boolean shouldFilterRareStartsWithTwoLiterals()
    {
        return SPECIES.length() == 64;
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Candidate count is always one, two, or three; two
    // and three take the XOR form under VectorSupport.SVE_128.
    // Copied as VectorScanKernelSupport.findByteSet; keep the copy in step.
    static int find(byte[] data, int offset, int length, byte first, byte second, byte third, int candidateCount)
    {
        return switch (candidateCount) {
            case 1 -> findOne(data, offset, length, first);
            case 2 -> VectorSupport.SVE_128 ? findTwoSve(data, offset, length, first, second) : findTwo(data, offset, length, first, second);
            case 3 -> VectorSupport.SVE_128 ? findThreeSve(data, offset, length, first, second, third) : findThree(data, offset, length, first, second, third);
            default -> throw new IllegalArgumentException("candidateCount must be one, two, or three");
        };
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Finds the first byte with its high bit set or equal to
    // one of the candidates. Candidate count is zero, one, two, or three.
    static int findHighBitOr(byte[] data, int offset, int length, byte first, byte second, byte third, int candidateCount)
    {
        return switch (candidateCount) {
            case 0 -> findHighBit(data, offset, length);
            case 1 -> findHighBitOrOne(data, offset, length, first);
            case 2 -> findHighBitOrTwo(data, offset, length, first, second);
            case 3 -> findHighBitOrThree(data, offset, length, first, second, third);
            default -> throw new IllegalArgumentException("candidateCount must be zero, one, two, or three");
        };
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. find scanning backward: returns the last position in
    // [offset, offset + length) holding a candidate. Candidate count is one, two, or
    // three; two and three take the XOR form under VectorSupport.SVE_128.
    static int findLast(byte[] data, int offset, int length, byte first, byte second, byte third, int candidateCount)
    {
        return switch (candidateCount) {
            case 1 -> findLastOne(data, offset, length, first);
            case 2 -> VectorSupport.SVE_128 ? findLastTwoSve(data, offset, length, first, second) : findLastTwo(data, offset, length, first, second);
            case 3 -> VectorSupport.SVE_128 ? findLastThreeSve(data, offset, length, first, second, third) : findLastThree(data, offset, length, first, second, third);
            default -> throw new IllegalArgumentException("candidateCount must be one, two, or three");
        };
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. findHighBitOr scanning backward: returns the last position
    // in [offset, offset + length) holding a byte with its high bit set or equal to one
    // of the candidates. Candidate count is zero, one, two, or three.
    static int findLastHighBitOr(byte[] data, int offset, int length, byte first, byte second, byte third, int candidateCount)
    {
        return switch (candidateCount) {
            case 0 -> findLastHighBit(data, offset, length);
            case 1 -> findLastHighBitOrOne(data, offset, length, first);
            case 2 -> findLastHighBitOrTwo(data, offset, length, first, second);
            case 3 -> findLastHighBitOrThree(data, offset, length, first, second, third);
            default -> throw new IllegalArgumentException("candidateCount must be zero, one, two, or three");
        };
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: unsigned comparisons preserve byte-set
    // ordering across the signed-byte boundary without an extra vector transform.
    // Copied as VectorScanKernelSupport.findRange; keep the copy in step.
    static int findRange(byte[] data, int offset, int length, int lowerBound, int upperBound)
    {
        ByteVector lowerBoundVector = ByteVector.broadcast(SPECIES, (byte) lowerBound);
        ByteVector upperBoundVector = ByteVector.broadcast(SPECIES, (byte) upperBound);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.compare(VectorOperators.UGE, lowerBoundVector)
                    .and(values.compare(VectorOperators.ULE, upperBoundVector));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            int value = data[position] & 0xFF;
            if (value >= lowerBound && value <= upperBound) {
                return position;
            }
        }
        return -1;
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Finds the first byte outside every one of `rangeCount` ranges, one
    // to four, that `ranges` packs in 16-bit slots, range i in bits 16i to 16i + 15. Each 16-bit
    // slot holds the unsigned lower bound in its low byte and `highest - lowest` (at most 127) in
    // its high byte. A byte is in a range when its distance above the lower bound, as an unsigned
    // byte, is at most `highest - lowest`, one subtraction and one unsigned compare per range. The
    // forward DFA's self-loop scans call it for loops described by the bytes that stay in them,
    // and the class-run counter for a long run of a byte class.
    static int findOutsideRanges(byte[] data, int offset, int length, long ranges, int rangeCount)
    {
        ByteVector firstLowest = ByteVector.broadcast(SPECIES, (byte) ranges);
        ByteVector firstWidth = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 8));
        ByteVector secondLowest = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 16));
        ByteVector secondWidth = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 24));
        ByteVector thirdLowest = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 32));
        ByteVector thirdWidth = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 40));
        ByteVector fourthLowest = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 48));
        ByteVector fourthWidth = ByteVector.broadcast(SPECIES, (byte) (ranges >>> 56));
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> stops = values.sub(firstLowest).compare(VectorOperators.UGT, firstWidth);
            if (rangeCount > 1) {
                stops = stops.and(values.sub(secondLowest).compare(VectorOperators.UGT, secondWidth));
            }
            if (rangeCount > 2) {
                stops = stops.and(values.sub(thirdLowest).compare(VectorOperators.UGT, thirdWidth));
            }
            if (rangeCount > 3) {
                stops = stops.and(values.sub(fourthLowest).compare(VectorOperators.UGT, fourthWidth));
            }
            if (stops.anyTrue()) {
                return position + stops.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            if (isOutsideRanges(data[position], ranges, rangeCount)) {
                return position;
            }
        }
        return -1;
    }

    private static boolean isOutsideRanges(byte value, long ranges, int rangeCount)
    {
        for (int range = 0; range < rangeCount; range++) {
            int shift = range * Short.SIZE;
            if (((value - (int) (ranges >>> shift)) & 0xFF) <= ((int) (ranges >>> (shift + 8)) & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Finds the first position where one of the filter's literals
    // matches at all three of its positions. For two groups, each vector step loads three
    // vectors, ORs each once with its position's mask, and checks both groups against those
    // three, by compare and AND or, under VectorSupport.SVE_128, by XOR distance; a filter without
    // masks skips the ORs and picks its form by TWO_EXACT_LITERALS_XOR. For more, each step
    // looks up every group's bit for each position by nibble, from a load at each position or,
    // under ONE_LOAD_NIBBLES, from one load; the high nibbles take the shift
    // VectorSupport.SHIFT_NIBBLES_AS_SHORTS selects.
    //
    // A two-group filter without masks first checks the start with the filter's scalar compare
    // and returns it when accepted, before its scan broadcasts the six probes: in a run of dense
    // matches of Tom.{10,25}river|river.{10,25}Tom, nearly every scan the forward DFA's
    // start-byte skip makes stops at its start, so the vector setup bought no skipping. Without
    // the check, on r8i, C2's register allocation of the DFA loop that calls this, where such rows
    // spend most of their cycles, compiled most JVM forks of a dense row into a slower form that
    // spills more to the stack. The check is here rather than in the scans because on NEON it
    // grew a scan's compiled code past the size at which C2 inlines a method it has already
    // compiled. "Start Literal Filter" in REGULATOR_DECISIONS.md records the measurements.
    static int findLiteralSet(byte[] data, int offset, int length, StartLiteralFilter filter)
    {
        return switch (filter.values().length / 3) {
            case 2 -> {
                if (!filter.exactBytes()) {
                    yield VectorSupport.SVE_128 ? findTwoLiteralsSve(data, offset, length, filter) : findTwoLiterals(data, offset, length, filter);
                }
                if (filter.accepts(data, offset, offset + length)) {
                    yield offset;
                }
                yield TWO_EXACT_LITERALS_XOR ? findTwoExactLiteralsXor(data, offset, length, filter) : findTwoExactLiterals(data, offset, length, filter);
            }
            case 4, 8 -> ONE_LOAD_NIBBLES ? findLiteralNibblesOneLoad(data, offset, length, filter) : findLiteralNibbles(data, offset, length, filter);
            default -> throw new IllegalArgumentException("literal filter width must be two, four, or eight");
        };
    }

    // The SVE form is findTwoLiteralsSve; keep the two in step.
    private static int findTwoLiterals(byte[] data, int offset, int length, StartLiteralFilter filter)
    {
        byte[] values = filter.values();
        ByteVector firstValue0 = ByteVector.broadcast(SPECIES, values[0]);
        ByteVector secondValue0 = ByteVector.broadcast(SPECIES, values[1]);
        ByteVector thirdValue0 = ByteVector.broadcast(SPECIES, values[2]);
        ByteVector firstValue1 = ByteVector.broadcast(SPECIES, values[3]);
        ByteVector secondValue1 = ByteVector.broadcast(SPECIES, values[4]);
        ByteVector thirdValue1 = ByteVector.broadcast(SPECIES, values[5]);
        ByteVector firstMask = ByteVector.broadcast(SPECIES, filter.firstMask());
        ByteVector secondMask = ByteVector.broadcast(SPECIES, filter.secondMask());
        ByteVector thirdMask = ByteVector.broadcast(SPECIES, filter.thirdMask());
        int firstOffset = filter.firstOffset();
        int secondOffset = filter.secondOffset();
        int thirdOffset = filter.thirdOffset();
        int end = offset + length;
        // The third position of the last vector's last lane must lie before the end.
        int vectorEnd = end - thirdOffset - SPECIES.length();
        int position = offset;
        for (; position <= vectorEnd; position += SPECIES.length()) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position + firstOffset).or(firstMask);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + secondOffset).or(secondMask);
            ByteVector third = ByteVector.fromArray(SPECIES, data, position + thirdOffset).or(thirdMask);
            VectorMask<Byte> matches = literalMatches(first, second, third, firstValue0, secondValue0, thirdValue0)
                    .or(literalMatches(first, second, third, firstValue1, secondValue1, thirdValue1));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }
        return filter.find(data, position, end - position);
    }

    // findTwoLiterals in the form VectorSupport.SVE_128 selects. It is a separate method because
    // both forms in one body would exceed C2's 325-byte limit for inlining a hot method. Keep the
    // two in step.
    private static int findTwoLiteralsSve(byte[] data, int offset, int length, StartLiteralFilter filter)
    {
        byte[] values = filter.values();
        ByteVector firstValue0 = ByteVector.broadcast(SPECIES, values[0]);
        ByteVector secondValue0 = ByteVector.broadcast(SPECIES, values[1]);
        ByteVector thirdValue0 = ByteVector.broadcast(SPECIES, values[2]);
        ByteVector firstValue1 = ByteVector.broadcast(SPECIES, values[3]);
        ByteVector secondValue1 = ByteVector.broadcast(SPECIES, values[4]);
        ByteVector thirdValue1 = ByteVector.broadcast(SPECIES, values[5]);
        ByteVector firstMask = ByteVector.broadcast(SPECIES, filter.firstMask());
        ByteVector secondMask = ByteVector.broadcast(SPECIES, filter.secondMask());
        ByteVector thirdMask = ByteVector.broadcast(SPECIES, filter.thirdMask());
        int firstOffset = filter.firstOffset();
        int secondOffset = filter.secondOffset();
        int thirdOffset = filter.thirdOffset();
        int end = offset + length;
        // The third position of the last vector's last lane must lie before the end.
        int vectorEnd = end - thirdOffset - SPECIES.length();
        int position = offset;
        for (; position <= vectorEnd; position += SPECIES.length()) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position + firstOffset).or(firstMask);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + secondOffset).or(secondMask);
            ByteVector third = ByteVector.fromArray(SPECIES, data, position + thirdOffset).or(thirdMask);
            VectorMask<Byte> matches = xorTwoLiteralMatches(first, second, third, firstValue0, secondValue0, thirdValue0, firstValue1, secondValue1, thirdValue1);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }
        return filter.find(data, position, end - position);
    }

    // findTwoLiterals for a filter without masks: each loaded vector is compared as it is, which
    // saves an OR per position and the three registers that hold the masks. The XOR form is
    // findTwoExactLiteralsXor; keep the two in step.
    static int findTwoExactLiterals(byte[] data, int offset, int length, StartLiteralFilter filter)
    {
        byte[] values = filter.values();
        ByteVector firstValue0 = ByteVector.broadcast(SPECIES, values[0]);
        ByteVector secondValue0 = ByteVector.broadcast(SPECIES, values[1]);
        ByteVector thirdValue0 = ByteVector.broadcast(SPECIES, values[2]);
        ByteVector firstValue1 = ByteVector.broadcast(SPECIES, values[3]);
        ByteVector secondValue1 = ByteVector.broadcast(SPECIES, values[4]);
        ByteVector thirdValue1 = ByteVector.broadcast(SPECIES, values[5]);
        int firstOffset = filter.firstOffset();
        int secondOffset = filter.secondOffset();
        int thirdOffset = filter.thirdOffset();
        int end = offset + length;
        // The third position of the last vector's last lane must lie before the end.
        int vectorEnd = end - thirdOffset - SPECIES.length();
        int position = offset;
        for (; position <= vectorEnd; position += SPECIES.length()) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position + firstOffset);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + secondOffset);
            ByteVector third = ByteVector.fromArray(SPECIES, data, position + thirdOffset);
            VectorMask<Byte> matches = literalMatches(first, second, third, firstValue0, secondValue0, thirdValue0)
                    .or(literalMatches(first, second, third, firstValue1, secondValue1, thirdValue1));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }
        return filter.find(data, position, end - position);
    }

    // findTwoExactLiterals in the XOR form TWO_EXACT_LITERALS_XOR selects. It is a separate
    // method, as findTwoLiteralsSve is, so that each body holds one form and tests can call both
    // on any platform. Keep the two in step.
    static int findTwoExactLiteralsXor(byte[] data, int offset, int length, StartLiteralFilter filter)
    {
        byte[] values = filter.values();
        ByteVector firstValue0 = ByteVector.broadcast(SPECIES, values[0]);
        ByteVector secondValue0 = ByteVector.broadcast(SPECIES, values[1]);
        ByteVector thirdValue0 = ByteVector.broadcast(SPECIES, values[2]);
        ByteVector firstValue1 = ByteVector.broadcast(SPECIES, values[3]);
        ByteVector secondValue1 = ByteVector.broadcast(SPECIES, values[4]);
        ByteVector thirdValue1 = ByteVector.broadcast(SPECIES, values[5]);
        int firstOffset = filter.firstOffset();
        int secondOffset = filter.secondOffset();
        int thirdOffset = filter.thirdOffset();
        int end = offset + length;
        // The third position of the last vector's last lane must lie before the end.
        int vectorEnd = end - thirdOffset - SPECIES.length();
        int position = offset;
        for (; position <= vectorEnd; position += SPECIES.length()) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position + firstOffset);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + secondOffset);
            ByteVector third = ByteVector.fromArray(SPECIES, data, position + thirdOffset);
            VectorMask<Byte> matches = xorTwoLiteralMatches(first, second, third, firstValue0, secondValue0, thirdValue0, firstValue1, secondValue1, thirdValue1);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }
        return filter.find(data, position, end - position);
    }

    // Each position's two nibble lookups give every group's bit at once, so the cost does not
    // grow with the number of groups. A lane keeps a group's bit only where the group accepts all
    // three bytes, so a nonzero lane is exactly a position the filter accepts. The lookups are
    // selectFrom, not rearrange: converting the indexes to a shuffle adds index-wrapping code
    // before every table lookup on aarch64. selectFrom already wraps each index to the vector's
    // lanes, and the tables repeat their 16 entries across all 64 bytes, so a lookup by the raw
    // byte reads its low nibble's entry, and one after a 16-bit shift ignores the bits the shift
    // moves in from the next byte. An AND of 0x0F would add an instruction, and on x86 a register
    // copy for the three-input AND that C2 fuses it into. Each step loads a vector at each of the
    // three positions rather than shifting the first two positions' bits in from the previous
    // step's, as the Rust crate's Teddy does, since each such shift costs a lane permute and a
    // blend. The loads overlap, and the third ends before the end. The one-load form is
    // findLiteralNibblesOneLoad; keep the two in step.
    static int findLiteralNibbles(byte[] data, int offset, int length, StartLiteralFilter filter)
    {
        byte[] tables = filter.nibbleTables();
        int tableBytes = StartLiteralFilter.NIBBLE_TABLE_BYTES;
        ByteVector firstLow = ByteVector.fromArray(SPECIES, tables, 0);
        ByteVector firstHigh = ByteVector.fromArray(SPECIES, tables, tableBytes);
        ByteVector secondLow = ByteVector.fromArray(SPECIES, tables, 2 * tableBytes);
        ByteVector secondHigh = ByteVector.fromArray(SPECIES, tables, 3 * tableBytes);
        ByteVector thirdLow = ByteVector.fromArray(SPECIES, tables, 4 * tableBytes);
        ByteVector thirdHigh = ByteVector.fromArray(SPECIES, tables, 5 * tableBytes);
        int firstOffset = filter.firstOffset();
        int secondOffset = filter.secondOffset();
        int thirdOffset = filter.thirdOffset();
        int end = offset + length;
        // The third position of the last vector's last lane must lie before the end.
        int vectorEnd = end - thirdOffset - SPECIES.length();
        int position = offset;
        // Each position's nibble split is written out rather than called: a helper C2 leaves out
        // of line boxes the vectors it takes and returns.
        for (; position <= vectorEnd; position += SPECIES.length()) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position + firstOffset);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + secondOffset);
            ByteVector third = ByteVector.fromArray(SPECIES, data, position + thirdOffset);
            ByteVector firstHighNibbles = VectorSupport.SHIFT_NIBBLES_AS_SHORTS
                    ? first.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 4).reinterpretAsBytes()
                    : first.lanewise(VectorOperators.LSHR, 4);
            ByteVector secondHighNibbles = VectorSupport.SHIFT_NIBBLES_AS_SHORTS
                    ? second.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 4).reinterpretAsBytes()
                    : second.lanewise(VectorOperators.LSHR, 4);
            ByteVector thirdHighNibbles = VectorSupport.SHIFT_NIBBLES_AS_SHORTS
                    ? third.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 4).reinterpretAsBytes()
                    : third.lanewise(VectorOperators.LSHR, 4);
            ByteVector groupBits = first.selectFrom(firstLow).and(firstHighNibbles.selectFrom(firstHigh))
                    .and(second.selectFrom(secondLow).and(secondHighNibbles.selectFrom(secondHigh)))
                    .and(third.selectFrom(thirdLow).and(thirdHighNibbles.selectFrom(thirdHigh)));
            VectorMask<Byte> matches = groupBits.compare(VectorOperators.NE, (byte) 0);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }
        return filter.find(data, position, end - position);
    }

    // findLiteralNibbles in the form ONE_LOAD_NIBBLES selects, as Hyperscan's AVX-512 VBMI Teddy
    // scans: each step loads one vector at the first position, looks up all three positions'
    // bits from it, and moves the second and third positions' bits down by their distance from
    // the first with one lane permute each. The permute indexes wrap, so a lane whose third byte
    // lies past the vector reads another lane's bits; the compare skips those lanes, and each
    // step advances by the lanes it checked. The third position is at most eleven bytes past the
    // first, within StartLiteralFilter.PATH_POSITIONS, so a step advances at least the vector's
    // lanes less eleven, 53 at the 64 lanes this form runs on. It is a separate method because
    // both forms in one body would exceed C2's 325-byte limit for inlining a hot method.
    static int findLiteralNibblesOneLoad(byte[] data, int offset, int length, StartLiteralFilter filter)
    {
        byte[] tables = filter.nibbleTables();
        int tableBytes = StartLiteralFilter.NIBBLE_TABLE_BYTES;
        ByteVector firstLow = ByteVector.fromArray(SPECIES, tables, 0);
        ByteVector firstHigh = ByteVector.fromArray(SPECIES, tables, tableBytes);
        ByteVector secondLow = ByteVector.fromArray(SPECIES, tables, 2 * tableBytes);
        ByteVector secondHigh = ByteVector.fromArray(SPECIES, tables, 3 * tableBytes);
        ByteVector thirdLow = ByteVector.fromArray(SPECIES, tables, 4 * tableBytes);
        ByteVector thirdHigh = ByteVector.fromArray(SPECIES, tables, 5 * tableBytes);
        int firstOffset = filter.firstOffset();
        int secondDistance = filter.secondOffset() - firstOffset;
        int thirdDistance = filter.thirdOffset() - firstOffset;
        ByteVector secondLanes = ByteVector.zero(SPECIES).addIndex(1).add((byte) secondDistance);
        ByteVector thirdLanes = ByteVector.zero(SPECIES).addIndex(1).add((byte) thirdDistance);
        int step = SPECIES.length() - thirdDistance;
        VectorMask<Byte> checkedLanes = SPECIES.indexInRange(0, step);
        int end = offset + length;
        // The load must end before the end; the last checked lane's third byte is its last byte.
        int vectorEnd = end - firstOffset - SPECIES.length();
        int position = offset;
        for (; position <= vectorEnd; position += step) {
            ByteVector bytes = ByteVector.fromArray(SPECIES, data, position + firstOffset);
            ByteVector highNibbles = VectorSupport.SHIFT_NIBBLES_AS_SHORTS
                    ? bytes.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 4).reinterpretAsBytes()
                    : bytes.lanewise(VectorOperators.LSHR, 4);
            ByteVector groupBits = bytes.selectFrom(firstLow).and(highNibbles.selectFrom(firstHigh))
                    .and(secondLanes.selectFrom(bytes.selectFrom(secondLow).and(highNibbles.selectFrom(secondHigh))))
                    .and(thirdLanes.selectFrom(bytes.selectFrom(thirdLow).and(highNibbles.selectFrom(thirdHigh))));
            VectorMask<Byte> matches = groupBits.compare(VectorOperators.NE, (byte) 0, checkedLanes);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }
        return filter.find(data, position, end - position);
    }

    /// Returns the lanes where `first`, `second`, and `third` equal either group of values, by
    /// XOR, OR, UMIN, and one compare with zero; see [VectorSupport#SVE_128] and
    /// [#TWO_EXACT_LITERALS_XOR].
    private static VectorMask<Byte> xorTwoLiteralMatches(
            ByteVector first,
            ByteVector second,
            ByteVector third,
            ByteVector firstValue0,
            ByteVector secondValue0,
            ByteVector thirdValue0,
            ByteVector firstValue1,
            ByteVector secondValue1,
            ByteVector thirdValue1)
    {
        // A lane of a group's difference is zero only where all three bytes match it, so the
        // smaller difference is zero where either group matches.
        return literalDifference(first, second, third, firstValue0, secondValue0, thirdValue0)
                .lanewise(VectorOperators.UMIN, literalDifference(first, second, third, firstValue1, secondValue1, thirdValue1))
                .compare(VectorOperators.EQ, (byte) 0);
    }

    private static ByteVector literalDifference(
            ByteVector first,
            ByteVector second,
            ByteVector third,
            ByteVector firstValue,
            ByteVector secondValue,
            ByteVector thirdValue)
    {
        return first.lanewise(VectorOperators.XOR, firstValue)
                .or(second.lanewise(VectorOperators.XOR, secondValue))
                .or(third.lanewise(VectorOperators.XOR, thirdValue));
    }

    private static VectorMask<Byte> literalMatches(
            ByteVector first,
            ByteVector second,
            ByteVector third,
            ByteVector firstValue,
            ByteVector secondValue,
            ByteVector thirdValue)
    {
        return first.eq(firstValue).and(second.eq(secondValue)).and(third.eq(thirdValue));
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Finds the first position whose byte is in slots 0 to 2 of `bytes`
    // and whose next byte is in slots 3 to 5, a side with fewer bytes repeating one. The scan is a
    // counted loop, with its step count fixed before it starts, so C2 can strip-mine its
    // safepoint poll out of each step; the step that hits is compared again after the loop. The
    // comparison is written out at both places rather than in a helper: C2 boxes the vectors a
    // helper it leaves out of line takes or returns, on every step, and nothing guarantees it
    // inlines one. The scalar tail is the one the scalar search uses. The SVE form is
    // findClassPairSve; keep the two in step.
    static int findClassPair(byte[] data, int offset, int length, byte[] bytes)
    {
        ByteVector first0 = ByteVector.broadcast(SPECIES, bytes[0]);
        ByteVector first1 = ByteVector.broadcast(SPECIES, bytes[1]);
        ByteVector first2 = ByteVector.broadcast(SPECIES, bytes[2]);
        ByteVector second0 = ByteVector.broadcast(SPECIES, bytes[3]);
        ByteVector second1 = ByteVector.broadcast(SPECIES, bytes[4]);
        ByteVector second2 = ByteVector.broadcast(SPECIES, bytes[5]);
        int lanes = SPECIES.length();
        // The second byte of each step's last lane must lie before the end.
        int steps = length > 0 ? (length - 1) / lanes : 0;
        int step = 0;
        for (; step < steps; step++) {
            int stepStart = offset + step * lanes;
            ByteVector first = ByteVector.fromArray(SPECIES, data, stepStart);
            ByteVector second = ByteVector.fromArray(SPECIES, data, stepStart + 1);
            if (first.eq(first0).or(first.eq(first1)).or(first.eq(first2))
                    .and(second.eq(second0).or(second.eq(second1)).or(second.eq(second2)))
                    .anyTrue()) {
                break;
            }
        }
        int position = offset + step * lanes;
        if (step < steps) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + 1);
            VectorMask<Byte> matches = first.eq(first0).or(first.eq(first1)).or(first.eq(first2))
                    .and(second.eq(second0).or(second.eq(second1)).or(second.eq(second2)));
            return position + matches.firstTrue();
        }

        return InnerLiteralSearch.findClassPair(data, position, offset + length - position, bytes);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. findClassPair in the form VectorSupport.SVE_128 selects: each side's
    // bytes are XORed with the loaded vector, the differences reduced by unsigned minimum, the two
    // sides ORed, and one compare with zero gives the mask. It is a separate method, as
    // findTwoLiteralsSve is, so that each body holds one form and its loop. The step that hits is
    // compared again after the loop as in findClassPair. Keep the two in step.
    static int findClassPairSve(byte[] data, int offset, int length, byte[] bytes)
    {
        ByteVector first0 = ByteVector.broadcast(SPECIES, bytes[0]);
        ByteVector first1 = ByteVector.broadcast(SPECIES, bytes[1]);
        ByteVector first2 = ByteVector.broadcast(SPECIES, bytes[2]);
        ByteVector second0 = ByteVector.broadcast(SPECIES, bytes[3]);
        ByteVector second1 = ByteVector.broadcast(SPECIES, bytes[4]);
        ByteVector second2 = ByteVector.broadcast(SPECIES, bytes[5]);
        int lanes = SPECIES.length();
        // The second byte of each step's last lane must lie before the end.
        int steps = length > 0 ? (length - 1) / lanes : 0;
        int step = 0;
        for (; step < steps; step++) {
            int stepStart = offset + step * lanes;
            ByteVector first = ByteVector.fromArray(SPECIES, data, stepStart);
            ByteVector second = ByteVector.fromArray(SPECIES, data, stepStart + 1);
            // A side's smallest difference is zero only where its byte is one of the side's, so
            // the OR of both sides is zero only where the pair matches.
            if (first.lanewise(VectorOperators.XOR, first0)
                    .lanewise(VectorOperators.UMIN, first.lanewise(VectorOperators.XOR, first1))
                    .lanewise(VectorOperators.UMIN, first.lanewise(VectorOperators.XOR, first2))
                    .lanewise(VectorOperators.OR, second.lanewise(VectorOperators.XOR, second0)
                            .lanewise(VectorOperators.UMIN, second.lanewise(VectorOperators.XOR, second1))
                            .lanewise(VectorOperators.UMIN, second.lanewise(VectorOperators.XOR, second2)))
                    .compare(VectorOperators.EQ, (byte) 0)
                    .anyTrue()) {
                break;
            }
        }
        int position = offset + step * lanes;
        if (step < steps) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + 1);
            VectorMask<Byte> matches = first.eq(first0).or(first.eq(first1)).or(first.eq(first2))
                    .and(second.eq(second0).or(second.eq(second1)).or(second.eq(second2)));
            return position + matches.firstTrue();
        }

        return InnerLiteralSearch.findClassPair(data, position, offset + length - position, bytes);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Finds the first position where every byte of the filter's span
    // lies in its offset's range, the span's last byte before offset + length, or -1. A pair
    // and a triple each have a compare form and the form VectorSupport.SVE_128 selects, each in
    // a body of its own so that every body holds one form and its loop.
    static int findClassSequence(byte[] data, int offset, int length, StartClassFilter filter)
    {
        return switch (filter.span()) {
            case 2 -> VectorSupport.SVE_128 ? findClassSequencePairSve(data, offset, length, filter) : findClassSequencePair(data, offset, length, filter);
            case 3 -> VectorSupport.SVE_128 ? findClassSequenceTripleSve(data, offset, length, filter) : findClassSequenceTriple(data, offset, length, filter);
            default -> throw new IllegalArgumentException("class filter span must be two or three");
        };
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. findClassSequence for a span of two, in the form of findClassPair:
    // a byte lies in a range of low to high when its distance above low, as an unsigned byte, is
    // at most high - low, so each offset takes a subtract and an unsigned compare, and the masks
    // are ANDed. The loop is counted, the step that hits is compared again after it, the
    // comparison is written out at both places, and the tail is the filter's scalar search, as
    // findClassPair records. The SVE form is findClassSequencePairSve; keep the two in step.
    static int findClassSequencePair(byte[] data, int offset, int length, StartClassFilter filter)
    {
        ByteVector firstLow = ByteVector.broadcast(SPECIES, (byte) filter.firstLow());
        ByteVector firstWidth = ByteVector.broadcast(SPECIES, (byte) (filter.firstHigh() - filter.firstLow()));
        ByteVector secondLow = ByteVector.broadcast(SPECIES, (byte) filter.secondLow());
        ByteVector secondWidth = ByteVector.broadcast(SPECIES, (byte) (filter.secondHigh() - filter.secondLow()));
        int lanes = SPECIES.length();
        // The second byte of each step's last lane must lie before the end.
        int steps = length > 0 ? (length - 1) / lanes : 0;
        int step = 0;
        for (; step < steps; step++) {
            int stepStart = offset + step * lanes;
            ByteVector first = ByteVector.fromArray(SPECIES, data, stepStart);
            ByteVector second = ByteVector.fromArray(SPECIES, data, stepStart + 1);
            if (first.sub(firstLow).compare(VectorOperators.ULE, firstWidth).and(second.sub(secondLow).compare(VectorOperators.ULE, secondWidth)).anyTrue()) {
                break;
            }
        }
        int position = offset + step * lanes;
        if (step < steps) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + 1);
            VectorMask<Byte> matches = first.sub(firstLow).compare(VectorOperators.ULE, firstWidth).and(second.sub(secondLow).compare(VectorOperators.ULE, secondWidth));
            return position + matches.firstTrue();
        }

        return filter.find(data, position, offset + length - position);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. findClassSequencePair in the form VectorSupport.SVE_128 selects: an
    // offset's distance above its low bound, raised to at least high - low by unsigned maximum
    // and less high - low, is zero only where the byte lies in the range, so the offsets are
    // ORed and one compare with zero gives the mask. The step that hits is compared again
    // after the loop as in findClassSequencePair. Keep the two in step.
    static int findClassSequencePairSve(byte[] data, int offset, int length, StartClassFilter filter)
    {
        ByteVector firstLow = ByteVector.broadcast(SPECIES, (byte) filter.firstLow());
        ByteVector firstWidth = ByteVector.broadcast(SPECIES, (byte) (filter.firstHigh() - filter.firstLow()));
        ByteVector secondLow = ByteVector.broadcast(SPECIES, (byte) filter.secondLow());
        ByteVector secondWidth = ByteVector.broadcast(SPECIES, (byte) (filter.secondHigh() - filter.secondLow()));
        int lanes = SPECIES.length();
        // The second byte of each step's last lane must lie before the end.
        int steps = length > 0 ? (length - 1) / lanes : 0;
        int step = 0;
        for (; step < steps; step++) {
            int stepStart = offset + step * lanes;
            ByteVector first = ByteVector.fromArray(SPECIES, data, stepStart);
            ByteVector second = ByteVector.fromArray(SPECIES, data, stepStart + 1);
            if (first.sub(firstLow).lanewise(VectorOperators.UMAX, firstWidth).sub(firstWidth)
                    .lanewise(VectorOperators.OR, second.sub(secondLow).lanewise(VectorOperators.UMAX, secondWidth).sub(secondWidth))
                    .compare(VectorOperators.EQ, (byte) 0)
                    .anyTrue()) {
                break;
            }
        }
        int position = offset + step * lanes;
        if (step < steps) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + 1);
            VectorMask<Byte> matches = first.sub(firstLow).compare(VectorOperators.ULE, firstWidth).and(second.sub(secondLow).compare(VectorOperators.ULE, secondWidth));
            return position + matches.firstTrue();
        }

        return filter.find(data, position, offset + length - position);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. findClassSequencePair with a third offset. The SVE form is
    // findClassSequenceTripleSve; keep the two in step.
    static int findClassSequenceTriple(byte[] data, int offset, int length, StartClassFilter filter)
    {
        ByteVector firstLow = ByteVector.broadcast(SPECIES, (byte) filter.firstLow());
        ByteVector firstWidth = ByteVector.broadcast(SPECIES, (byte) (filter.firstHigh() - filter.firstLow()));
        ByteVector secondLow = ByteVector.broadcast(SPECIES, (byte) filter.secondLow());
        ByteVector secondWidth = ByteVector.broadcast(SPECIES, (byte) (filter.secondHigh() - filter.secondLow()));
        ByteVector thirdLow = ByteVector.broadcast(SPECIES, (byte) filter.thirdLow());
        ByteVector thirdWidth = ByteVector.broadcast(SPECIES, (byte) (filter.thirdHigh() - filter.thirdLow()));
        int lanes = SPECIES.length();
        // The third byte of each step's last lane must lie before the end.
        int steps = length > 1 ? (length - 2) / lanes : 0;
        int step = 0;
        for (; step < steps; step++) {
            int stepStart = offset + step * lanes;
            ByteVector first = ByteVector.fromArray(SPECIES, data, stepStart);
            ByteVector second = ByteVector.fromArray(SPECIES, data, stepStart + 1);
            ByteVector third = ByteVector.fromArray(SPECIES, data, stepStart + 2);
            if (first.sub(firstLow).compare(VectorOperators.ULE, firstWidth)
                    .and(second.sub(secondLow).compare(VectorOperators.ULE, secondWidth))
                    .and(third.sub(thirdLow).compare(VectorOperators.ULE, thirdWidth))
                    .anyTrue()) {
                break;
            }
        }
        int position = offset + step * lanes;
        if (step < steps) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + 1);
            ByteVector third = ByteVector.fromArray(SPECIES, data, position + 2);
            VectorMask<Byte> matches = first.sub(firstLow).compare(VectorOperators.ULE, firstWidth)
                    .and(second.sub(secondLow).compare(VectorOperators.ULE, secondWidth))
                    .and(third.sub(thirdLow).compare(VectorOperators.ULE, thirdWidth));
            return position + matches.firstTrue();
        }

        return filter.find(data, position, offset + length - position);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. findClassSequencePairSve with a third offset. Keep it in step with
    // findClassSequenceTriple.
    static int findClassSequenceTripleSve(byte[] data, int offset, int length, StartClassFilter filter)
    {
        ByteVector firstLow = ByteVector.broadcast(SPECIES, (byte) filter.firstLow());
        ByteVector firstWidth = ByteVector.broadcast(SPECIES, (byte) (filter.firstHigh() - filter.firstLow()));
        ByteVector secondLow = ByteVector.broadcast(SPECIES, (byte) filter.secondLow());
        ByteVector secondWidth = ByteVector.broadcast(SPECIES, (byte) (filter.secondHigh() - filter.secondLow()));
        ByteVector thirdLow = ByteVector.broadcast(SPECIES, (byte) filter.thirdLow());
        ByteVector thirdWidth = ByteVector.broadcast(SPECIES, (byte) (filter.thirdHigh() - filter.thirdLow()));
        int lanes = SPECIES.length();
        // The third byte of each step's last lane must lie before the end.
        int steps = length > 1 ? (length - 2) / lanes : 0;
        int step = 0;
        for (; step < steps; step++) {
            int stepStart = offset + step * lanes;
            ByteVector first = ByteVector.fromArray(SPECIES, data, stepStart);
            ByteVector second = ByteVector.fromArray(SPECIES, data, stepStart + 1);
            ByteVector third = ByteVector.fromArray(SPECIES, data, stepStart + 2);
            if (first.sub(firstLow).lanewise(VectorOperators.UMAX, firstWidth).sub(firstWidth)
                    .lanewise(VectorOperators.OR, second.sub(secondLow).lanewise(VectorOperators.UMAX, secondWidth).sub(secondWidth))
                    .lanewise(VectorOperators.OR, third.sub(thirdLow).lanewise(VectorOperators.UMAX, thirdWidth).sub(thirdWidth))
                    .compare(VectorOperators.EQ, (byte) 0)
                    .anyTrue()) {
                break;
            }
        }
        int position = offset + step * lanes;
        if (step < steps) {
            ByteVector first = ByteVector.fromArray(SPECIES, data, position);
            ByteVector second = ByteVector.fromArray(SPECIES, data, position + 1);
            ByteVector third = ByteVector.fromArray(SPECIES, data, position + 2);
            VectorMask<Byte> matches = first.sub(firstLow).compare(VectorOperators.ULE, firstWidth)
                    .and(second.sub(secondLow).compare(VectorOperators.ULE, secondWidth))
                    .and(third.sub(thirdLow).compare(VectorOperators.ULE, thirdWidth));
            return position + matches.firstTrue();
        }

        return filter.find(data, position, offset + length - position);
    }

    static byte[] buildNibbleTables(byte[] candidates)
    {
        if (candidates == null) {
            return null;
        }

        long highNibbleIndexes = -1L;
        int highNibbleCount = 0;
        int candidateCount = 0;
        for (int value = 0; value < candidates.length; value++) {
            if (candidates[value] == 0) {
                continue;
            }
            candidateCount++;
            int highNibble = value >>> 4;
            if (((highNibbleIndexes >>> (highNibble * 4)) & 0xF) == 0xF) {
                if (highNibbleCount == Byte.SIZE) {
                    return null;
                }
                highNibbleIndexes &= ~(0xFL << (highNibble * 4));
                highNibbleIndexes |= (long) highNibbleCount << (highNibble * 4);
                highNibbleCount++;
            }
        }
        if (candidateCount < 4 || candidateCount > 64) {
            return null;
        }

        // Each high nibble owns one bit. A byte is a member when the masks selected by
        // its low and high nibbles share that bit. Replication supports lane-local byte
        // shuffles while still allowing full-width rearranges where the ISA provides them.
        byte[] tables = new byte[NIBBLE_TABLES_LENGTH];
        for (int value = 0; value < candidates.length; value++) {
            if (candidates[value] == 0) {
                continue;
            }
            int highNibble = value >>> 4;
            int highNibbleIndex = (int) ((highNibbleIndexes >>> (highNibble * 4)) & 0xF);
            byte bit = (byte) (1 << highNibbleIndex);
            for (int tableIndex = value & 0xF; tableIndex < NIBBLE_TABLE_BYTES; tableIndex += 16) {
                tables[tableIndex] |= bit;
            }
            for (int tableIndex = highNibble; tableIndex < NIBBLE_TABLE_BYTES; tableIndex += 16) {
                tables[HIGH_NIBBLE_TABLE_OFFSET + tableIndex] = bit;
            }
        }
        return tables;
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: the replicated tables are part of the code shape.
    // Changes require target-host assembly and integrated benchmark evidence.
    // Copied as VectorScanKernelSupport.findNibbleTable; keep the copy in step.
    static int findNibbleTable(byte[] data, int offset, int length, byte[] tables)
    {
        ByteVector lowNibbleTable = ByteVector.fromArray(SPECIES, tables, 0);
        ByteVector highNibbleTable = ByteVector.fromArray(SPECIES, tables, HIGH_NIBBLE_TABLE_OFFSET);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            ByteVector lowNibbleMasks = lowNibbleTable.rearrange(values.and((byte) 0x0F).toShuffle());
            ByteVector highNibbleMasks = highNibbleTable.rearrange(
                    values.lanewise(VectorOperators.LSHR, 4).and((byte) 0x0F).toShuffle());
            VectorMask<Byte> matches = lowNibbleMasks.and(highNibbleMasks)
                    .compare(VectorOperators.NE, (byte) 0);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            int value = data[position] & 0xFF;
            if ((tables[value & 0xF] & tables[HIGH_NIBBLE_TABLE_OFFSET + (value >>> 4)]) != 0) {
                return position;
            }
        }
        return -1;
    }

    // The forward kernels from here through findHighBitOrThree have backward mirrors, the findLast
    // kernels from findLastOne on; keep each in step with its mirror.
    // Copied as VectorScanKernelSupport.findOne; keep the copy in step.
    private static int findOne(byte[] data, int offset, int length, byte candidate)
    {
        ByteVector candidateVector = ByteVector.broadcast(SPECIES, candidate);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, data, position)
                    .compare(VectorOperators.EQ, candidateVector);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            if (data[position] == candidate) {
                return position;
            }
        }
        return -1;
    }

    // Copied as VectorScanKernelSupport.findTwo; keep the copy in step. The SVE form is
    // findTwoSve; keep the two in step.
    private static int findTwo(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.compare(VectorOperators.EQ, firstVector)
                    .or(values.compare(VectorOperators.EQ, secondVector));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value == first || value == second) {
                return position;
            }
        }
        return -1;
    }

    // findTwo in the form VectorSupport.SVE_128 selects: XOR with each candidate, UMIN, and one
    // compare with zero. Copied as VectorScanKernelSupport.findTwoSve; keep the copy in step.
    private static int findTwoSve(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.lanewise(VectorOperators.XOR, firstVector)
                    .lanewise(VectorOperators.UMIN, values.lanewise(VectorOperators.XOR, secondVector))
                    .compare(VectorOperators.EQ, (byte) 0);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value == first || value == second) {
                return position;
            }
        }
        return -1;
    }

    // Copied as VectorScanKernelSupport.findThree; keep the copy in step. The SVE form is
    // findThreeSve; keep the two in step.
    private static int findThree(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(SPECIES, third);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.compare(VectorOperators.EQ, firstVector)
                    .or(values.compare(VectorOperators.EQ, secondVector))
                    .or(values.compare(VectorOperators.EQ, thirdVector));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value == first || value == second || value == third) {
                return position;
            }
        }
        return -1;
    }

    // findThree in the form VectorSupport.SVE_128 selects: XOR with each candidate, UMIN, and one
    // compare with zero. Copied as VectorScanKernelSupport.findThreeSve; keep the copy in step.
    private static int findThreeSve(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(SPECIES, third);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.lanewise(VectorOperators.XOR, firstVector)
                    .lanewise(VectorOperators.UMIN, values.lanewise(VectorOperators.XOR, secondVector))
                    .lanewise(VectorOperators.UMIN, values.lanewise(VectorOperators.XOR, thirdVector))
                    .compare(VectorOperators.EQ, (byte) 0);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value == first || value == second || value == third) {
                return position;
            }
        }
        return -1;
    }

    private static int findHighBit(byte[] data, int offset, int length)
    {
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, data, position)
                    .compare(VectorOperators.LT, (byte) 0);
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            if (data[position] < 0) {
                return position;
            }
        }
        return -1;
    }

    private static int findHighBitOrOne(byte[] data, int offset, int length, byte candidate)
    {
        ByteVector candidateVector = ByteVector.broadcast(SPECIES, candidate);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.compare(VectorOperators.LT, (byte) 0)
                    .or(values.compare(VectorOperators.EQ, candidateVector));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value < 0 || value == candidate) {
                return position;
            }
        }
        return -1;
    }

    private static int findHighBitOrTwo(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.compare(VectorOperators.LT, (byte) 0)
                    .or(values.compare(VectorOperators.EQ, firstVector))
                    .or(values.compare(VectorOperators.EQ, secondVector));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value < 0 || value == first || value == second) {
                return position;
            }
        }
        return -1;
    }

    private static int findHighBitOrThree(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(SPECIES, third);
        int vectorEnd = offset + SPECIES.loopBound(length);
        int position = offset;
        for (; position < vectorEnd; position += SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position);
            VectorMask<Byte> matches = values.compare(VectorOperators.LT, (byte) 0)
                    .or(values.compare(VectorOperators.EQ, firstVector))
                    .or(values.compare(VectorOperators.EQ, secondVector))
                    .or(values.compare(VectorOperators.EQ, thirdVector));
            if (matches.anyTrue()) {
                return position + matches.firstTrue();
            }
        }

        int end = offset + length;
        for (; position < end; position++) {
            byte value = data[position];
            if (value < 0 || value == first || value == second || value == third) {
                return position;
            }
        }
        return -1;
    }

    // The backward kernels below mirror the forward ones above, loading whole vectors from the end
    // of the range toward its start and leaving the head shorter than a vector to a scalar loop;
    // keep each in step with its forward form.
    private static int findLastOne(byte[] data, int offset, int length, byte candidate)
    {
        ByteVector candidateVector = ByteVector.broadcast(SPECIES, candidate);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, data, position - SPECIES.length())
                    .compare(VectorOperators.EQ, candidateVector);
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            if (data[position - 1] == candidate) {
                return position - 1;
            }
        }
        return -1;
    }

    // The SVE form is findLastTwoSve; keep the two in step.
    private static int findLastTwo(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.compare(VectorOperators.EQ, firstVector)
                    .or(values.compare(VectorOperators.EQ, secondVector));
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value == first || value == second) {
                return position - 1;
            }
        }
        return -1;
    }

    // findLastTwo in the form VectorSupport.SVE_128 selects, as findTwoSve is for findTwo.
    private static int findLastTwoSve(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.lanewise(VectorOperators.XOR, firstVector)
                    .lanewise(VectorOperators.UMIN, values.lanewise(VectorOperators.XOR, secondVector))
                    .compare(VectorOperators.EQ, (byte) 0);
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value == first || value == second) {
                return position - 1;
            }
        }
        return -1;
    }

    // The SVE form is findLastThreeSve; keep the two in step.
    private static int findLastThree(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(SPECIES, third);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.compare(VectorOperators.EQ, firstVector)
                    .or(values.compare(VectorOperators.EQ, secondVector))
                    .or(values.compare(VectorOperators.EQ, thirdVector));
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value == first || value == second || value == third) {
                return position - 1;
            }
        }
        return -1;
    }

    // findLastThree in the form VectorSupport.SVE_128 selects, as findThreeSve is for findThree.
    private static int findLastThreeSve(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(SPECIES, third);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.lanewise(VectorOperators.XOR, firstVector)
                    .lanewise(VectorOperators.UMIN, values.lanewise(VectorOperators.XOR, secondVector))
                    .lanewise(VectorOperators.UMIN, values.lanewise(VectorOperators.XOR, thirdVector))
                    .compare(VectorOperators.EQ, (byte) 0);
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value == first || value == second || value == third) {
                return position - 1;
            }
        }
        return -1;
    }

    private static int findLastHighBit(byte[] data, int offset, int length)
    {
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, data, position - SPECIES.length())
                    .compare(VectorOperators.LT, (byte) 0);
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            if (data[position - 1] < 0) {
                return position - 1;
            }
        }
        return -1;
    }

    private static int findLastHighBitOrOne(byte[] data, int offset, int length, byte candidate)
    {
        ByteVector candidateVector = ByteVector.broadcast(SPECIES, candidate);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.compare(VectorOperators.LT, (byte) 0)
                    .or(values.compare(VectorOperators.EQ, candidateVector));
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value < 0 || value == candidate) {
                return position - 1;
            }
        }
        return -1;
    }

    private static int findLastHighBitOrTwo(byte[] data, int offset, int length, byte first, byte second)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.compare(VectorOperators.LT, (byte) 0)
                    .or(values.compare(VectorOperators.EQ, firstVector))
                    .or(values.compare(VectorOperators.EQ, secondVector));
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value < 0 || value == first || value == second) {
                return position - 1;
            }
        }
        return -1;
    }

    private static int findLastHighBitOrThree(byte[] data, int offset, int length, byte first, byte second, byte third)
    {
        ByteVector firstVector = ByteVector.broadcast(SPECIES, first);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, second);
        ByteVector thirdVector = ByteVector.broadcast(SPECIES, third);
        int vectorBegin = offset + length - SPECIES.loopBound(length);
        int position = offset + length;
        for (; position > vectorBegin; position -= SPECIES.length()) {
            ByteVector values = ByteVector.fromArray(SPECIES, data, position - SPECIES.length());
            VectorMask<Byte> matches = values.compare(VectorOperators.LT, (byte) 0)
                    .or(values.compare(VectorOperators.EQ, firstVector))
                    .or(values.compare(VectorOperators.EQ, secondVector))
                    .or(values.compare(VectorOperators.EQ, thirdVector));
            if (matches.anyTrue()) {
                return position - SPECIES.length() + matches.lastTrue();
            }
        }

        for (; position > offset; position--) {
            byte value = data[position - 1];
            if (value < 0 || value == first || value == second || value == third) {
                return position - 1;
            }
        }
        return -1;
    }
}
