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

import io.airlift.slice.Slice;

import static java.util.Objects.requireNonNull;

/**
 * Background byte probabilities used to choose which byte of a literal a candidate scan probes.
 * {@link Re2.Options#setByteFrequencies}, {@link TrinoRegexp.Options#setByteFrequencies}, and
 * {@link JavaRegexp.Options#setByteFrequencies} select the distribution used during compilation;
 * a compiled pattern keeps only the chosen probe offsets. The options default to
 * {@link #uniform()}, which gives every byte the same probability, so every literal scan probes
 * its first byte.
 * <p>
 * A scan for a literal stops at every occurrence of its probe byte, so probing a rare byte
 * hands fewer candidates to verification. The probe leaves the first byte only for a later
 * byte more than {@value #PROBE_FACTOR} times rarer, earliest on ties. The factor keeps the
 * first byte when the estimate is close, because an estimate is not the searched text. A
 * distribution that matches the searched data lets the scan probe a rarer byte. A mismatched
 * distribution can make literal scans several times slower per byte than the default, because
 * the probe byte occurs more often than the first byte. A distribution never changes what
 * matches, and the cost stays linear in the input.
 * <p>
 * Only a case-sensitive literal of more than one byte can probe a later byte. RE2 and Java
 * patterns move the probe only when the literal's first byte is ASCII. Some Trino patterns that
 * begin with a literal and capture what follows it can also move the probe of a literal whose
 * first byte is not ASCII.
 * <p>
 * A distribution learned with {@link #builder()} from the caller's own data is the most reliable
 * choice. {@link #fromCounts(long[])} builds one from byte counts. Either measures the data it was
 * built from, so its probe may be any byte, including punctuation, digits, and bytes at or above
 * 0x80. Each probability is add-one smoothed, {@code (count + 1) / (total + 256)}, so a byte
 * absent from the sample is rare but not impossible.
 * <p>
 * Instances are immutable and thread-safe.
 */
public final class ByteFrequencies
{
    private static final int PROBE_FACTOR = 4;

    private static final ByteFrequencies UNIFORM = fromCounts(new long[256]);

    private final double[] probabilities;

    private ByteFrequencies(double[] probabilities)
    {
        this.probabilities = probabilities;
    }

    /**
     * Returns a distribution in which every byte is equally common, so every literal scan probes
     * its first byte. Compile options use it unless the caller chooses another.
     */
    public static ByteFrequencies uniform()
    {
        return UNIFORM;
    }

    /**
     * Creates an add-one smoothed distribution from 256 non-negative byte counts, indexed by
     * unsigned byte value. The counts are copied.
     */
    public static ByteFrequencies fromCounts(long[] counts)
    {
        requireNonNull(counts, "counts is null");
        if (counts.length != 256) {
            throw new IllegalArgumentException("counts must have 256 entries: " + counts.length);
        }
        long[] countsCopy = counts.clone();
        double total = 0;
        for (int value = 0; value < countsCopy.length; value++) {
            if (countsCopy[value] < 0) {
                throw new IllegalArgumentException("count for byte " + value + " is negative: " + countsCopy[value]);
            }
            total += countsCopy[value];
        }
        double[] probabilities = new double[256];
        for (int value = 0; value < probabilities.length; value++) {
            probabilities[value] = (countsCopy[value] + 1.0) / (total + 256);
        }
        return new ByteFrequencies(probabilities);
    }

    /**
     * Creates a single-use builder that counts the bytes of sample data.
     * <p>
     * Learn from data like the data being searched, preferably a few hundred kilobytes of whole
     * lines sampled across it rather than the first bytes of one file, which can over-represent
     * one part of the data. A small sample may miss a byte that the searched data contains often;
     * smoothing then rates that byte rare, and a literal containing it probes a common byte.
     */
    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Returns the offset in {@code literal} of the byte a candidate scan should probe: the
     * rarest byte after the first, earliest on ties, when it is more than
     * {@value #PROBE_FACTOR} times rarer than the first byte, and otherwise 0.
     */
    int probeOffset(byte[] literal)
    {
        int rarest = 0;
        for (int offset = 1; offset < literal.length; offset++) {
            if (rarest == 0 || probability(literal[offset]) < probability(literal[rarest])) {
                rarest = offset;
            }
        }
        return rarest != 0 && probability(literal[rarest]) * PROBE_FACTOR < probability(literal[0]) ? rarest : 0;
    }

    double probability(byte value)
    {
        return probabilities[value & 0xFF];
    }

    /**
     * Counts sample bytes for {@link ByteFrequencies#builder()}. Builders are not thread-safe.
     */
    public static final class Builder
    {
        private final long[] counts = new long[256];
        private boolean built;

        private Builder() {}

        /**
         * Counts every byte of {@code sample}.
         */
        public Builder add(Slice sample)
        {
            requireNotBuilt();
            requireNonNull(sample, "sample is null");
            byte[] bytes = sample.byteArray();
            int start = sample.byteArrayOffset();
            int end = start + sample.length();
            for (int index = start; index < end; index++) {
                counts[bytes[index] & 0xFF]++;
            }
            return this;
        }

        /**
         * Creates the distribution of the counted bytes, as {@link #fromCounts(long[])} does, and
         * invalidates this builder.
         */
        public ByteFrequencies build()
        {
            requireNotBuilt();
            built = true;
            return fromCounts(counts);
        }

        private void requireNotBuilt()
        {
            if (built) {
                throw new IllegalStateException("ByteFrequencies builder has already built a distribution");
            }
        }
    }
}
