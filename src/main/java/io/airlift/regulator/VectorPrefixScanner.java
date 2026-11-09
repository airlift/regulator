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

import java.util.Arrays;

final class VectorPrefixScanner
{
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

    // Whether the literal prefix scans, Prog's prefix scan and Prog.findLiteral, take
    // findPairMasked, which compares the secondary bytes under the mask of the primary compare,
    // rather than findPair. Only under VectorSupport.X86_WITH_64_BYTE_VECTORS, which names the
    // hosts measured and unmeasured, where C2 emits the second compare as one compare writing a
    // mask register under the first's mask, in place of a second compare and a kandq. Other hosts
    // keep findPair: SVE with 16-byte vectors, as on r9g, took 1.54x to 1.76x the XOR form's time
    // in the masked form, and NEON, AVX2, and wider SVE were not measured. "Candidate Masks on
    // SVE" in REGULATOR_DECISIONS.md records the measurements. C2 folds this constant, so find
    // compiles to one call.
    private static final boolean MASKED_PAIR_COMPARE = VectorSupport.X86_WITH_64_BYTE_VECTORS;

    private VectorPrefixScanner() {}

    // Returns the first occurrence of prefix, -1, or -2 - resume when the failed candidates pass
    // LiteralSearchKernel's comparison budget and the search must continue with KMP at resume.
    // Prog's prefix scan and Prog.findLiteral call it. Each form is its own method, so that each
    // body holds one candidate test and tests can call both on any platform.
    static int find(byte[] data, int offset, int length, byte[] prefix, int primaryOffset, int secondaryOffset)
    {
        return MASKED_PAIR_COMPARE
                ? findPairMasked(data, offset, length, prefix, primaryOffset, secondaryOffset)
                : findPair(data, offset, length, prefix, primaryOffset, secondaryOffset);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Keep Vector API linkage isolated in this class.
    // find's scan with the platform candidate test: the XOR form under VectorSupport.SVE_128, and
    // compare and AND elsewhere. The masked form is findPairMasked; keep the two in step.
    static int findPair(byte[] data, int offset, int length, byte[] prefix, int primaryOffset, int secondaryOffset)
    {
        int prefixSize = prefix.length;
        int candidateCount = length - prefixSize + 1;
        if (candidateCount <= 0) {
            return -1;
        }

        int vectorEnd = offset + SPECIES.loopBound(candidateCount);
        ByteVector primaryVector = ByteVector.broadcast(SPECIES, prefix[primaryOffset]);
        ByteVector secondaryVector = ByteVector.broadcast(SPECIES, prefix[secondaryOffset]);
        int comparisonEnd = LiteralSearchKernel.initialComparisonEnd(offset);
        int position = offset;

        while (position < vectorEnd) {
            // Each pass counts the whole vectors left and runs a loop that only tests each one for
            // a candidate; candidates are verified after it breaks. With no verification inside,
            // C2 compiles it as an innermost counted loop, unrolled four times on r9g. The method
            // is too large for C2 to inline into find, the dispatch its callers in Prog inline;
            // "Literal Prefix Vector Scan" in REGULATOR_DECISIONS.md records the measured
            // trade-off.
            //
            // The candidate test follows "Candidate Masks on SVE" there: the XOR form on SVE with
            // 16-byte vectors, and compare and AND, written in line, elsewhere. Keep every vector
            // and mask in this method or in a helper C2 inlines at each call site: a vector or mask
            // returned from a helper C2 leaves out of line is boxed on every search.
            int steps = (vectorEnd - position) / SPECIES.length();
            int step = 0;
            for (; step < steps; step++) {
                int stepPosition = position + step * SPECIES.length();
                if (VectorSupport.SVE_128
                        ? pairDifference(data, stepPosition, primaryOffset, secondaryOffset, primaryVector, secondaryVector).compare(VectorOperators.EQ, (byte) 0).anyTrue()
                        : ByteVector.fromArray(SPECIES, data, stepPosition + primaryOffset).compare(VectorOperators.EQ, primaryVector)
                          .and(ByteVector.fromArray(SPECIES, data, stepPosition + secondaryOffset).compare(VectorOperators.EQ, secondaryVector))
                          .anyTrue()) {
                    break;
                }
            }
            position += step * SPECIES.length();
            if (step == steps) {
                break;
            }
            // Rebuild this vector's candidates as bits. The XOR form is repeated in line so that
            // pairDifference keeps its one call site, in the scan loop.
            ByteVector primary = ByteVector.fromArray(SPECIES, data, position + primaryOffset);
            ByteVector secondary = ByteVector.fromArray(SPECIES, data, position + secondaryOffset);
            long candidateBits = VectorSupport.SVE_128
                    ? primary.lanewise(VectorOperators.XOR, primaryVector).lanewise(VectorOperators.OR, secondary.lanewise(VectorOperators.XOR, secondaryVector))
                      .compare(VectorOperators.EQ, (byte) 0).toLong()
                    : primary.compare(VectorOperators.EQ, primaryVector).and(secondary.compare(VectorOperators.EQ, secondaryVector)).toLong();
            while (candidateBits != 0) {
                int candidate = position + Long.numberOfTrailingZeros(candidateBits);
                // Keep sparse verification in this method. When composed with the DFA,
                // C2 otherwise treats this call site as cold and leaves it out of line.
                int prefixIndex = 0;
                while (prefixIndex < prefix.length && data[candidate + prefixIndex] == prefix[prefixIndex]) {
                    prefixIndex++;
                }
                if (prefixIndex == prefix.length) {
                    return candidate;
                }
                comparisonEnd = LiteralSearchKernel.chargeFailedCandidate(comparisonEnd, prefixIndex);
                if (LiteralSearchKernel.comparisonBudgetSpent(comparisonEnd, candidate)) {
                    return -2 - (candidate + 1);
                }
                candidateBits &= candidateBits - 1;
            }
            position += SPECIES.length();
        }
        return findScalar(data, position, offset + candidateCount - position, prefix, primaryOffset, secondaryOffset, comparisonEnd);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Keep Vector API linkage isolated in this class.
    // findPair in the form MASKED_PAIR_COMPARE selects: the candidate test compares the secondary
    // bytes under the mask of the primary compare, written in line. With AVX-512, C2 emits the
    // second as one compare writing a mask register under the first's mask, so the AND costs no
    // mask instruction; where compares cannot take a mask, as with NEON, it compares and ANDs.
    // Everything else is findPair's; keep the two in step.
    static int findPairMasked(byte[] data, int offset, int length, byte[] prefix, int primaryOffset, int secondaryOffset)
    {
        int prefixSize = prefix.length;
        int candidateCount = length - prefixSize + 1;
        if (candidateCount <= 0) {
            return -1;
        }

        int vectorEnd = offset + SPECIES.loopBound(candidateCount);
        ByteVector primaryVector = ByteVector.broadcast(SPECIES, prefix[primaryOffset]);
        ByteVector secondaryVector = ByteVector.broadcast(SPECIES, prefix[secondaryOffset]);
        int comparisonEnd = LiteralSearchKernel.initialComparisonEnd(offset);
        int position = offset;

        while (position < vectorEnd) {
            // As in findPair, a counted loop only tests each vector for a candidate, and
            // candidates are verified after it breaks.
            int steps = (vectorEnd - position) / SPECIES.length();
            int step = 0;
            for (; step < steps; step++) {
                int stepPosition = position + step * SPECIES.length();
                if (ByteVector.fromArray(SPECIES, data, stepPosition + secondaryOffset)
                        .compare(VectorOperators.EQ, secondaryVector, ByteVector.fromArray(SPECIES, data, stepPosition + primaryOffset).compare(VectorOperators.EQ, primaryVector))
                        .anyTrue()) {
                    break;
                }
            }
            position += step * SPECIES.length();
            if (step == steps) {
                break;
            }
            // Rebuild this vector's candidates as bits.
            ByteVector primary = ByteVector.fromArray(SPECIES, data, position + primaryOffset);
            ByteVector secondary = ByteVector.fromArray(SPECIES, data, position + secondaryOffset);
            long candidateBits = secondary.compare(VectorOperators.EQ, secondaryVector, primary.compare(VectorOperators.EQ, primaryVector)).toLong();
            while (candidateBits != 0) {
                int candidate = position + Long.numberOfTrailingZeros(candidateBits);
                // Keep sparse verification in this method. When composed with the DFA,
                // C2 otherwise treats this call site as cold and leaves it out of line.
                int prefixIndex = 0;
                while (prefixIndex < prefix.length && data[candidate + prefixIndex] == prefix[prefixIndex]) {
                    prefixIndex++;
                }
                if (prefixIndex == prefix.length) {
                    return candidate;
                }
                comparisonEnd = LiteralSearchKernel.chargeFailedCandidate(comparisonEnd, prefixIndex);
                if (LiteralSearchKernel.comparisonBudgetSpent(comparisonEnd, candidate)) {
                    return -2 - (candidate + 1);
                }
                candidateBits &= candidateBits - 1;
            }
            position += SPECIES.length();
        }
        return findScalar(data, position, offset + candidateCount - position, prefix, primaryOffset, secondaryOffset, comparisonEnd);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Keep Vector API linkage isolated in this class.
    // Returns the first position where one of `literals` occurs, ending by offset + length, or -1.
    // The literals are sorted by length, shortest first, and all hold the shortest one's bytes at
    // primaryOffset and secondaryOffset, which lie before its end. The scan is findPair's, testing
    // that pair, and each candidate is compared with every literal that fits before the end. Prog
    // builds at most eight literals of at most six bytes, so the comparisons at a candidate are
    // bounded and the scan keeps no comparison budget.
    static int findLiteralSet(byte[] data, int offset, int length, byte[][] literals, int primaryOffset, int secondaryOffset)
    {
        byte[] shortest = literals[0];
        int candidateCount = length - shortest.length + 1;
        if (candidateCount <= 0) {
            return -1;
        }

        int end = offset + length;
        int vectorEnd = offset + SPECIES.loopBound(candidateCount);
        ByteVector primaryVector = ByteVector.broadcast(SPECIES, shortest[primaryOffset]);
        ByteVector secondaryVector = ByteVector.broadcast(SPECIES, shortest[secondaryOffset]);
        int position = offset;

        while (position < vectorEnd) {
            // As in findPair, a counted loop only tests each vector for a candidate, and candidates
            // are verified after it breaks. The XOR form is written in line rather than through
            // pairDifference, which keeps its one call site in findPair.
            int steps = (vectorEnd - position) / SPECIES.length();
            int step = 0;
            for (; step < steps; step++) {
                int stepPosition = position + step * SPECIES.length();
                if (VectorSupport.SVE_128
                        ? ByteVector.fromArray(SPECIES, data, stepPosition + primaryOffset).lanewise(VectorOperators.XOR, primaryVector)
                          .lanewise(VectorOperators.OR, ByteVector.fromArray(SPECIES, data, stepPosition + secondaryOffset).lanewise(VectorOperators.XOR, secondaryVector))
                          .compare(VectorOperators.EQ, (byte) 0).anyTrue()
                        : ByteVector.fromArray(SPECIES, data, stepPosition + primaryOffset).compare(VectorOperators.EQ, primaryVector)
                          .and(ByteVector.fromArray(SPECIES, data, stepPosition + secondaryOffset).compare(VectorOperators.EQ, secondaryVector))
                          .anyTrue()) {
                    break;
                }
            }
            position += step * SPECIES.length();
            if (step == steps) {
                break;
            }
            ByteVector primary = ByteVector.fromArray(SPECIES, data, position + primaryOffset);
            ByteVector secondary = ByteVector.fromArray(SPECIES, data, position + secondaryOffset);
            long candidateBits = VectorSupport.SVE_128
                    ? primary.lanewise(VectorOperators.XOR, primaryVector).lanewise(VectorOperators.OR, secondary.lanewise(VectorOperators.XOR, secondaryVector))
                      .compare(VectorOperators.EQ, (byte) 0).toLong()
                    : primary.compare(VectorOperators.EQ, primaryVector).and(secondary.compare(VectorOperators.EQ, secondaryVector)).toLong();
            while (candidateBits != 0) {
                int candidate = position + Long.numberOfTrailingZeros(candidateBits);
                // Keep sparse verification in this method, as in findPair.
                literal:
                for (byte[] literal : literals) {
                    if (literal.length > end - candidate) {
                        // The rest are no shorter.
                        break;
                    }
                    for (int index = 0; index < literal.length; index++) {
                        if (data[candidate + index] != literal[index]) {
                            continue literal;
                        }
                    }
                    return candidate;
                }
                candidateBits &= candidateBits - 1;
            }
            position += SPECIES.length();
        }
        return findLiteralSetScalar(data, position, offset + candidateCount, end, literals, primaryOffset, secondaryOffset);
    }

    /// Returns the XOR difference of the bytes at `position + primaryOffset` from the primary probe
    /// byte, ORed with that of the bytes at `position + secondaryOffset` from the secondary one, so
    /// a lane is zero only at a candidate; see [VectorSupport#SVE_128]. Call it only from the scan
    /// loop in `findPair`: a second, rarely run call site can leave it out of line, and the vector
    /// it returns is then boxed.
    private static ByteVector pairDifference(byte[] data, int position, int primaryOffset, int secondaryOffset, ByteVector primaryVector, ByteVector secondaryVector)
    {
        return ByteVector.fromArray(SPECIES, data, position + primaryOffset).lanewise(VectorOperators.XOR, primaryVector)
                .lanewise(VectorOperators.OR, ByteVector.fromArray(SPECIES, data, position + secondaryOffset).lanewise(VectorOperators.XOR, secondaryVector));
    }

    // The folded pair kernels' entry points, which the folded prefix scan in Prog and the folded
    // inner literal search call: findFoldCase and findFoldCaseDense, or under
    // VectorSupport.SVE_128 their XOR forms, as "Candidate Masks on SVE" in REGULATOR_DECISIONS.md
    // describes. They pass only ints and arrays, so every vector and mask stays inside the
    // kernels, and their two returns keep them within MaxInlineSize, where a conditional
    // expression's jump would not.
    static int findFoldCasePair(byte[] data, int offset, int length, byte[] prefix, int prefixSize, int firstOffset, int secondOffset)
    {
        if (VectorSupport.SVE_128) {
            return findFoldCaseSve(data, offset, length, prefix, prefixSize, firstOffset, secondOffset);
        }
        return findFoldCase(data, offset, length, prefix, prefixSize, firstOffset, secondOffset);
    }

    static int findFoldCaseDensePair(byte[] data, int offset, int length, byte[] prefix, int prefixSize, int firstOffset, int secondOffset)
    {
        if (VectorSupport.SVE_128) {
            return findFoldCaseDenseSve(data, offset, length, prefix, prefixSize, firstOffset, secondOffset);
        }
        return findFoldCaseDense(data, offset, length, prefix, prefixSize, firstOffset, secondOffset);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Keep Vector API linkage isolated in this class.
    // Returns the last occurrence of literal, -1, or -2 - resumeEnd when the failed candidates
    // pass LiteralSearchKernel's comparison budget, counted down from the last candidate, and the
    // search must continue with LiteralSearchKernel.findLastKmp over the range up to resumeEnd.
    // It mirrors findPair: the vectors step down from the last candidate, and a vector's
    // candidates are verified highest lane first. It keeps findPair's platform candidate test on
    // every host, as it was not measured in findPairMasked's masked form.
    static int findLast(byte[] data, int offset, int length, byte[] literal, int primaryOffset, int secondaryOffset)
    {
        int literalSize = literal.length;
        int candidateCount = length - literalSize + 1;
        if (candidateCount <= 0) {
            return -1;
        }

        int candidateEnd = offset + candidateCount;
        int vectorStart = candidateEnd - SPECIES.loopBound(candidateCount);
        ByteVector primaryVector = ByteVector.broadcast(SPECIES, literal[primaryOffset]);
        ByteVector secondaryVector = ByteVector.broadcast(SPECIES, literal[secondaryOffset]);
        // Distances below the last candidate, so the candidates covered are those from it down.
        int lastCandidate = candidateEnd - 1;
        int comparisonEnd = LiteralSearchKernel.initialComparisonEnd(0);
        int position = candidateEnd;

        while (position > vectorStart) {
            // As in findPair, the loop only tests each vector for a candidate, and the candidate
            // test follows "Candidate Masks on SVE": keep every vector and mask in this method or
            // in a helper C2 inlines at each call site.
            int steps = (position - vectorStart) / SPECIES.length();
            int step = 0;
            for (; step < steps; step++) {
                int stepPosition = position - (step + 1) * SPECIES.length();
                if (VectorSupport.SVE_128
                        ? lastPairDifference(data, stepPosition, primaryOffset, secondaryOffset, primaryVector, secondaryVector).compare(VectorOperators.EQ, (byte) 0).anyTrue()
                        : ByteVector.fromArray(SPECIES, data, stepPosition + primaryOffset).compare(VectorOperators.EQ, primaryVector)
                          .and(ByteVector.fromArray(SPECIES, data, stepPosition + secondaryOffset).compare(VectorOperators.EQ, secondaryVector))
                          .anyTrue()) {
                    break;
                }
            }
            position -= step * SPECIES.length();
            if (step == steps) {
                break;
            }
            // Rebuild this vector's candidates as bits, the XOR form in line as in findPair.
            int vectorPosition = position - SPECIES.length();
            ByteVector primary = ByteVector.fromArray(SPECIES, data, vectorPosition + primaryOffset);
            ByteVector secondary = ByteVector.fromArray(SPECIES, data, vectorPosition + secondaryOffset);
            long candidateBits = VectorSupport.SVE_128
                    ? primary.lanewise(VectorOperators.XOR, primaryVector).lanewise(VectorOperators.OR, secondary.lanewise(VectorOperators.XOR, secondaryVector))
                      .compare(VectorOperators.EQ, (byte) 0).toLong()
                    : primary.compare(VectorOperators.EQ, primaryVector).and(secondary.compare(VectorOperators.EQ, secondaryVector)).toLong();
            while (candidateBits != 0) {
                int lane = 63 - Long.numberOfLeadingZeros(candidateBits);
                int candidate = vectorPosition + lane;
                // Keep sparse verification in this method, as in findPair.
                int literalIndex = 0;
                while (literalIndex < literal.length && data[candidate + literalIndex] == literal[literalIndex]) {
                    literalIndex++;
                }
                if (literalIndex == literal.length) {
                    return candidate;
                }
                comparisonEnd = LiteralSearchKernel.chargeFailedCandidate(comparisonEnd, literalIndex);
                if (LiteralSearchKernel.comparisonBudgetSpent(comparisonEnd, lastCandidate - candidate)) {
                    return -2 - (candidate + literalSize - 1);
                }
                candidateBits ^= 1L << lane;
            }
            position = vectorPosition;
        }
        return findLastScalar(data, offset, position - offset, literal, primaryOffset, secondaryOffset, comparisonEnd, lastCandidate);
    }

    /// A copy of [#pairDifference] for `findLast`'s scan loop, so each helper has one call site.
    /// A second call site of either can leave it out of line, and the vector it returns is then
    /// boxed.
    private static ByteVector lastPairDifference(byte[] data, int position, int primaryOffset, int secondaryOffset, ByteVector primaryVector, ByteVector secondaryVector)
    {
        return ByteVector.fromArray(SPECIES, data, position + primaryOffset).lanewise(VectorOperators.XOR, primaryVector)
                .lanewise(VectorOperators.OR, ByteVector.fromArray(SPECIES, data, position + secondaryOffset).lanewise(VectorOperators.XOR, secondaryVector));
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Keep Vector API linkage isolated in this class.
    // Finds the first occurrence of the first prefixSize bytes of prefix under the folded
    // prefix rule: both sides map A-Z to a-z, and every other byte compares exactly. Candidates
    // need both probe bytes, at firstOffset and secondOffset within the prefix, before the whole
    // folded prefix is compared. The prefix holds at most nine bytes, so failed comparisons stay
    // bounded and the scan needs no KMP budget.
    //
    // The folded prefix scan in Prog and the folded inner literal search call this kernel through
    // findFoldCasePair when few vectors are predicted to hold a candidate of the probe pair, and
    // findFoldCaseDense through findFoldCaseDensePair otherwise; see Prog.usesDenseFoldCaseScan.
    // With sparse candidates the counted test loop runs long stretches without leaving. The XOR
    // form is findFoldCaseSve; keep the two in step.
    static int findFoldCase(byte[] data, int offset, int length, byte[] prefix, int prefixSize, int firstOffset, int secondOffset)
    {
        int candidateCount = length - prefixSize + 1;
        if (candidateCount <= 0) {
            return -1;
        }

        int vectorEnd = offset + SPECIES.loopBound(candidateCount);
        // Setting bit 0x20 maps both cases of an ASCII letter to its lower case. A probe that
        // is not a letter ORs in zero and compares exactly, since 0x20 would alias pairs such
        // as '@' and '`'.
        byte firstLowerCase = asciiLower(prefix[firstOffset]);
        byte secondLowerCase = asciiLower(prefix[secondOffset]);
        ByteVector firstCaseBit = ByteVector.broadcast(SPECIES, caseBit(firstLowerCase));
        ByteVector secondCaseBit = ByteVector.broadcast(SPECIES, caseBit(secondLowerCase));
        ByteVector firstVector = ByteVector.broadcast(SPECIES, firstLowerCase);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, secondLowerCase);
        int position = offset;

        while (position < vectorEnd) {
            // Each pass counts the whole vectors left and runs a loop that only tests each one for
            // a candidate; candidates are verified after it breaks, as in findPair, so C2 can
            // compile the test loop as an innermost counted loop.
            //
            // The candidate test compares and ANDs; findFoldCasePair takes findFoldCaseSve, the XOR
            // form, under VectorSupport.SVE_128, as "Candidate Masks on SVE" in
            // REGULATOR_DECISIONS.md describes. Keep every vector and mask in this method: a vector
            // or mask returned from a helper C2 leaves out of line is boxed on every search.
            int steps = (vectorEnd - position) / SPECIES.length();
            int step = 0;
            for (; step < steps; step++) {
                int stepPosition = position + step * SPECIES.length();
                if (ByteVector.fromArray(SPECIES, data, stepPosition + firstOffset).or(firstCaseBit).compare(VectorOperators.EQ, firstVector)
                        .and(ByteVector.fromArray(SPECIES, data, stepPosition + secondOffset).or(secondCaseBit).compare(VectorOperators.EQ, secondVector))
                        .anyTrue()) {
                    break;
                }
            }
            position += step * SPECIES.length();
            if (step == steps) {
                break;
            }
            // Rebuild this vector's candidates as bits.
            long candidateBits = ByteVector.fromArray(SPECIES, data, position + firstOffset).or(firstCaseBit).compare(VectorOperators.EQ, firstVector)
                    .and(ByteVector.fromArray(SPECIES, data, position + secondOffset).or(secondCaseBit).compare(VectorOperators.EQ, secondVector))
                    .toLong();
            while (candidateBits != 0) {
                int candidate = position + Long.numberOfTrailingZeros(candidateBits);
                // Keep sparse verification in this method. When composed with the DFA,
                // C2 otherwise treats this call site as cold and leaves it out of line.
                int prefixIndex = 0;
                while (prefixIndex < prefixSize && asciiLower(data[candidate + prefixIndex]) == asciiLower(prefix[prefixIndex])) {
                    prefixIndex++;
                }
                if (prefixIndex == prefixSize) {
                    return candidate;
                }
                candidateBits &= candidateBits - 1;
            }
            position += SPECIES.length();
        }
        return findFoldCaseScalar(data, position, offset + candidateCount - position, prefix, prefixSize);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. findFoldCase in the XOR form, which findFoldCasePair takes under
    // VectorSupport.SVE_128: each probe's case-mapped bytes are XORed with its lower-case byte,
    // the differences are ORed, and one compare with zero gives the candidates. It is a separate
    // method so that each form compiles on its own and findFoldCase keeps the size it was
    // measured at. Keep the two in step.
    static int findFoldCaseSve(byte[] data, int offset, int length, byte[] prefix, int prefixSize, int firstOffset, int secondOffset)
    {
        int candidateCount = length - prefixSize + 1;
        if (candidateCount <= 0) {
            return -1;
        }

        int vectorEnd = offset + SPECIES.loopBound(candidateCount);
        // The case bits and probes are those of findFoldCase.
        byte firstLowerCase = asciiLower(prefix[firstOffset]);
        byte secondLowerCase = asciiLower(prefix[secondOffset]);
        ByteVector firstCaseBit = ByteVector.broadcast(SPECIES, caseBit(firstLowerCase));
        ByteVector secondCaseBit = ByteVector.broadcast(SPECIES, caseBit(secondLowerCase));
        ByteVector firstVector = ByteVector.broadcast(SPECIES, firstLowerCase);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, secondLowerCase);
        int position = offset;

        while (position < vectorEnd) {
            // The counted test loop of findFoldCase. Keep every vector and mask in this method: a
            // vector or mask returned from a helper C2 leaves out of line is boxed on every search.
            int steps = (vectorEnd - position) / SPECIES.length();
            int step = 0;
            for (; step < steps; step++) {
                int stepPosition = position + step * SPECIES.length();
                if (ByteVector.fromArray(SPECIES, data, stepPosition + firstOffset).or(firstCaseBit).lanewise(VectorOperators.XOR, firstVector)
                        .or(ByteVector.fromArray(SPECIES, data, stepPosition + secondOffset).or(secondCaseBit).lanewise(VectorOperators.XOR, secondVector))
                        .compare(VectorOperators.EQ, (byte) 0)
                        .anyTrue()) {
                    break;
                }
            }
            position += step * SPECIES.length();
            if (step == steps) {
                break;
            }
            // Rebuild this vector's candidates as bits.
            long candidateBits = ByteVector.fromArray(SPECIES, data, position + firstOffset).or(firstCaseBit).lanewise(VectorOperators.XOR, firstVector)
                    .or(ByteVector.fromArray(SPECIES, data, position + secondOffset).or(secondCaseBit).lanewise(VectorOperators.XOR, secondVector))
                    .compare(VectorOperators.EQ, (byte) 0)
                    .toLong();
            while (candidateBits != 0) {
                int candidate = position + Long.numberOfTrailingZeros(candidateBits);
                // Keep sparse verification in this method. When composed with the DFA,
                // C2 otherwise treats this call site as cold and leaves it out of line.
                int prefixIndex = 0;
                while (prefixIndex < prefixSize && asciiLower(data[candidate + prefixIndex]) == asciiLower(prefix[prefixIndex])) {
                    prefixIndex++;
                }
                if (prefixIndex == prefixSize) {
                    return candidate;
                }
                candidateBits &= candidateBits - 1;
            }
            position += SPECIES.length();
        }
        return findFoldCaseScalar(data, position, offset + candidateCount - position, prefix, prefixSize);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. Keep Vector API linkage isolated in this class.
    // Finds what findFoldCase finds, verifying each vector's candidates inside the vector loop.
    //
    // The folded prefix scan in Prog and the folded inner literal search call this kernel through
    // findFoldCaseDensePair when many vectors are predicted to hold a candidate of the probe pair,
    // as for a common pair in 64-byte vectors; see Prog.usesDenseFoldCaseScan. There the counted
    // loop leaves and re-enters on many vectors, and its exit, candidate rebuild, and re-entry
    // cost more than testing and verifying each vector in one loop. Keep this method within
    // FreqInlineSize, as TestHotMethodSize checks, and every vector and mask inside it: a vector
    // or mask passed to or returned from a helper C2 leaves out of line is boxed. The XOR form is
    // findFoldCaseDenseSve; keep the two in step.
    static int findFoldCaseDense(byte[] data, int offset, int length, byte[] prefix, int prefixSize, int firstOffset, int secondOffset)
    {
        int candidateCount = length - prefixSize + 1;
        if (candidateCount <= 0) {
            return -1;
        }

        int vectorEnd = offset + SPECIES.loopBound(candidateCount);
        // The case bits and probes are those of findFoldCase.
        byte firstLowerCase = asciiLower(prefix[firstOffset]);
        byte secondLowerCase = asciiLower(prefix[secondOffset]);
        ByteVector firstCaseBit = ByteVector.broadcast(SPECIES, caseBit(firstLowerCase));
        ByteVector secondCaseBit = ByteVector.broadcast(SPECIES, caseBit(secondLowerCase));
        ByteVector firstVector = ByteVector.broadcast(SPECIES, firstLowerCase);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, secondLowerCase);
        // The loop steps the first probe's position and loads the second probe a fixed distance
        // from it, so the loop holds one index and one distance, as findFoldCase's front and back
        // loads did before the pair could move. Stepping the candidate start instead keeps both
        // offsets live in the loop; inlined into the DFA scan, C2 left them on the stack and
        // reloaded the array from it on every vector, about 20% slower on 64-byte vectors.
        int distance = secondOffset - firstOffset;
        int probePosition = offset + firstOffset;
        int probeEnd = vectorEnd + firstOffset;

        for (; probePosition < probeEnd; probePosition += SPECIES.length()) {
            VectorMask<Byte> candidates = ByteVector.fromArray(SPECIES, data, probePosition)
                    .or(firstCaseBit)
                    .compare(VectorOperators.EQ, firstVector)
                    .and(ByteVector.fromArray(SPECIES, data, probePosition + distance)
                            .or(secondCaseBit)
                            .compare(VectorOperators.EQ, secondVector));
            long candidateBits = candidates.toLong();
            while (candidateBits != 0) {
                int candidate = probePosition - firstOffset + Long.numberOfTrailingZeros(candidateBits);
                // Keep candidate verification in this method. When composed with the DFA,
                // C2 otherwise treats this call site as cold and leaves it out of line.
                int prefixIndex = 0;
                while (prefixIndex < prefixSize && asciiLower(data[candidate + prefixIndex]) == asciiLower(prefix[prefixIndex])) {
                    prefixIndex++;
                }
                if (prefixIndex == prefixSize) {
                    return candidate;
                }
                candidateBits &= candidateBits - 1;
            }
        }
        int position = probePosition - firstOffset;
        return findFoldCaseScalar(data, position, offset + candidateCount - position, prefix, prefixSize);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. findFoldCaseDense in the XOR form, which findFoldCaseDensePair
    // takes under VectorSupport.SVE_128, with the candidate test of findFoldCaseSve. Keep this
    // method within FreqInlineSize, as TestHotMethodSize checks, and every vector and mask inside
    // it. Keep the two in step.
    static int findFoldCaseDenseSve(byte[] data, int offset, int length, byte[] prefix, int prefixSize, int firstOffset, int secondOffset)
    {
        int candidateCount = length - prefixSize + 1;
        if (candidateCount <= 0) {
            return -1;
        }

        int vectorEnd = offset + SPECIES.loopBound(candidateCount);
        // The case bits, probes, and stepped probe position are those of findFoldCaseDense.
        byte firstLowerCase = asciiLower(prefix[firstOffset]);
        byte secondLowerCase = asciiLower(prefix[secondOffset]);
        ByteVector firstCaseBit = ByteVector.broadcast(SPECIES, caseBit(firstLowerCase));
        ByteVector secondCaseBit = ByteVector.broadcast(SPECIES, caseBit(secondLowerCase));
        ByteVector firstVector = ByteVector.broadcast(SPECIES, firstLowerCase);
        ByteVector secondVector = ByteVector.broadcast(SPECIES, secondLowerCase);
        int distance = secondOffset - firstOffset;
        int probePosition = offset + firstOffset;
        int probeEnd = vectorEnd + firstOffset;

        for (; probePosition < probeEnd; probePosition += SPECIES.length()) {
            VectorMask<Byte> candidates = ByteVector.fromArray(SPECIES, data, probePosition)
                    .or(firstCaseBit)
                    .lanewise(VectorOperators.XOR, firstVector)
                    .or(ByteVector.fromArray(SPECIES, data, probePosition + distance)
                            .or(secondCaseBit)
                            .lanewise(VectorOperators.XOR, secondVector))
                    .compare(VectorOperators.EQ, (byte) 0);
            long candidateBits = candidates.toLong();
            while (candidateBits != 0) {
                int candidate = probePosition - firstOffset + Long.numberOfTrailingZeros(candidateBits);
                // Keep candidate verification in this method. When composed with the DFA,
                // C2 otherwise treats this call site as cold and leaves it out of line.
                int prefixIndex = 0;
                while (prefixIndex < prefixSize && asciiLower(data[candidate + prefixIndex]) == asciiLower(prefix[prefixIndex])) {
                    prefixIndex++;
                }
                if (prefixIndex == prefixSize) {
                    return candidate;
                }
                candidateBits &= candidateBits - 1;
            }
        }
        int position = probePosition - firstOffset;
        return findFoldCaseScalar(data, position, offset + candidateCount - position, prefix, prefixSize);
    }

    /// Returns the lanes where `first` equals `firstProbe` and `second` equals `secondProbe`, by
    /// XOR, OR, and one compare with zero; see [VectorSupport#SVE_128]. `VectorScanner` calls it.
    /// Copied as `VectorScanKernelSupport.sveMatches`; keep the copy in step.
    static VectorMask<Byte> sveMatches(ByteVector first, ByteVector firstProbe, ByteVector second, ByteVector secondProbe)
    {
        return first.lanewise(VectorOperators.XOR, firstProbe).or(second.lanewise(VectorOperators.XOR, secondProbe)).compare(VectorOperators.EQ, (byte) 0);
    }

    private static int findScalar(byte[] data, int offset, int candidateCount, byte[] prefix, int primaryOffset, int secondaryOffset, int comparisonEnd)
    {
        int end = offset + candidateCount;
        for (int position = offset; position < end; position++) {
            if (data[position + primaryOffset] == prefix[primaryOffset] &&
                    data[position + secondaryOffset] == prefix[secondaryOffset]) {
                int matchedLength = matchingPrefixLength(data, position, prefix);
                if (matchedLength == prefix.length) {
                    return position;
                }
                comparisonEnd = LiteralSearchKernel.chargeFailedCandidate(comparisonEnd, matchedLength);
                if (LiteralSearchKernel.comparisonBudgetSpent(comparisonEnd, position)) {
                    return -2 - (position + 1);
                }
            }
        }
        return -1;
    }

    // The candidates of findLast below its vectors, from the highest down, charged to its
    // comparison end on their distance below its last candidate.
    private static int findLastScalar(byte[] data, int offset, int candidateCount, byte[] literal, int primaryOffset, int secondaryOffset, int comparisonEnd, int lastCandidate)
    {
        for (int position = offset + candidateCount - 1; position >= offset; position--) {
            if (data[position + primaryOffset] == literal[primaryOffset] &&
                    data[position + secondaryOffset] == literal[secondaryOffset]) {
                int matchedLength = matchingPrefixLength(data, position, literal);
                if (matchedLength == literal.length) {
                    return position;
                }
                comparisonEnd = LiteralSearchKernel.chargeFailedCandidate(comparisonEnd, matchedLength);
                if (LiteralSearchKernel.comparisonBudgetSpent(comparisonEnd, lastCandidate - position)) {
                    return -2 - (position + literal.length - 1);
                }
            }
        }
        return -1;
    }

    // The candidates of findLiteralSet in [offset, candidateEnd), one position at a time.
    private static int findLiteralSetScalar(byte[] data, int offset, int candidateEnd, int end, byte[][] literals, int primaryOffset, int secondaryOffset)
    {
        byte primary = literals[0][primaryOffset];
        byte secondary = literals[0][secondaryOffset];
        for (int position = offset; position < candidateEnd; position++) {
            if (data[position + primaryOffset] == primary && data[position + secondaryOffset] == secondary && literalSetMatchesAt(data, position, end, literals)) {
                return position;
            }
        }
        return -1;
    }

    private static boolean literalSetMatchesAt(byte[] data, int position, int end, byte[][] literals)
    {
        for (byte[] literal : literals) {
            if (literal.length > end - position) {
                return false;
            }
            if (Arrays.equals(data, position, position + literal.length, literal, 0, literal.length)) {
                return true;
            }
        }
        return false;
    }

    private static int matchingPrefixLength(byte[] data, int position, byte[] prefix)
    {
        for (int index = 0; index < prefix.length; index++) {
            if (data[position + index] != prefix[index]) {
                return index;
            }
        }
        return prefix.length;
    }

    private static int findFoldCaseScalar(byte[] data, int offset, int candidateCount, byte[] prefix, int prefixSize)
    {
        int end = offset + candidateCount;
        for (int position = offset; position < end; position++) {
            if (foldCasePrefixMatchesAt(data, position, prefix, prefixSize)) {
                return position;
            }
        }
        return -1;
    }

    private static boolean foldCasePrefixMatchesAt(byte[] data, int position, byte[] prefix, int prefixSize)
    {
        for (int index = 0; index < prefixSize; index++) {
            if (asciiLower(data[position + index]) != asciiLower(prefix[index])) {
                return false;
            }
        }
        return true;
    }

    private static byte caseBit(byte lowerCase)
    {
        return lowerCase >= 'a' && lowerCase <= 'z' ? (byte) 0x20 : 0;
    }

    private static byte asciiLower(byte value)
    {
        return value >= 'A' && value <= 'Z' ? (byte) (value + ('a' - 'A')) : value;
    }
}
