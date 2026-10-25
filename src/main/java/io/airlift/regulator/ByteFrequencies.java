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
 * {@link Re2.Options#setByteFrequencies}, {@link TrinoRegexp.Options#setByteFrequencies},
 * {@link JavaRegexp.Options#setByteFrequencies}, and {@link RustRegexp.Options#setByteFrequencies}
 * select the distribution used during compilation; a compiled pattern keeps only the chosen probe
 * offsets. The options default to {@link #uniform()}, which gives every byte the same
 * probability, so every literal scan probes its first byte.
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
 * Only a case-sensitive literal of more than one byte can probe a later byte. Outside scan plans,
 * Rust, RE2, and Java patterns move the probe only when the literal's first byte is ASCII. Some
 * Trino, RE2, Java, and Rust patterns that begin with a literal and capture what follows it run on a
 * scan plan, which can also move the probe of a literal whose first byte is not ASCII.
 * <p>
 * A distribution learned with {@link #builder()} from the caller's own data is the most reliable
 * choice. {@link #fromCounts(long[])} builds one from byte counts. Either measures the data it was
 * built from, so its probe may be any byte, including punctuation, digits, and bytes at or above
 * 0x80. Each probability is add-one smoothed, {@code (count + 1) / (total + 256)}, so a byte
 * absent from the sample is rare but not impossible.
 * <p>
 * Two presets serve callers who know what kind of data they search:
 * <ul>
 * <li>{@link #englishText()} matches English prose. It is measured on three English corpora of
 * the Leipzig Corpora Collection (https://wortschatz.uni-leipzig.de, CC BY): news from 2025,
 * Wikipedia from 2016, and web text from 2018, 100,000 sentences each. Their cleaned sentences
 * contain none of {@code # = [ \ ] ^ _ { | }} and almost no {@code @}, so this preset rates those
 * bytes very rare.
 * <li>{@link #json()} matches JSON documents. It is measured on the example documents of the
 * simdjson project (https://github.com/simdjson/simdjson, Apache-2.0), one file per document, so
 * pretty-printed, compact, and escaped copies of a document count once. Its GitHub events file
 * and its array of random numbers are excluded.
 * </ul>
 * Each preset is the unweighted mean of its sources' add-one smoothed distributions and may probe
 * any ASCII byte. Each byte at or above 0x80 has the probability of the most common ASCII byte,
 * so presets probe only ASCII bytes. Every table is regenerated from pinned public sources by
 * {@code tools/byte-frequencies/generate-byte-frequencies.py} in the source repository.
 * <p>
 * Instances are immutable and thread-safe.
 */
public final class ByteFrequencies
{
    private static final int PROBE_FACTOR = 4;

    // Mean of three Leipzig English corpora; see the class comment and tools/byte-frequencies.
    private static final double[] ENGLISH_TEXT_PROBABILITIES = {
            8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, // 0x00
            8.598379e-08, 8.598379e-08, 8.598465e-03, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, // 0x08
            8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, // 0x10
            8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, // 0x18
            1.567979e-01, 1.594137e-04, 1.256243e-03, 8.598379e-08, 1.715362e-04, 1.365022e-04, 5.837179e-05, 1.417920e-03, // 0x20
            7.307531e-04, 7.175092e-04, 2.689449e-05, 1.757593e-05, 8.751018e-03, 1.878125e-03, 9.073092e-03, 1.435352e-04, // 0x28
            1.911912e-03, 1.725895e-03, 1.334475e-03, 6.231161e-04, 5.487740e-04, 6.730065e-04, 4.917321e-04, 4.665437e-04, // 0x30
            5.317295e-04, 8.171205e-04, 4.365478e-04, 1.638909e-04, 3.351139e-06, 8.598379e-08, 4.787254e-06, 2.603034e-04, // 0x38
            2.157529e-07, 2.418017e-03, 1.424404e-03, 1.938761e-03, 1.096545e-03, 9.262731e-04, 9.711292e-04, 8.353700e-04, // 0x40
            1.164170e-03, 2.728542e-03, 5.894318e-04, 4.396462e-04, 9.105371e-04, 1.576720e-03, 8.919388e-04, 7.898212e-04, // 0x48
            1.353704e-03, 8.417741e-05, 9.581206e-04, 2.419782e-03, 3.169716e-03, 5.453075e-04, 3.781542e-04, 1.154647e-03, // 0x50
            8.321298e-05, 3.101075e-04, 8.081021e-05, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, 8.598379e-08, // 0x58
            1.710655e-06, 6.377759e-02, 1.063849e-02, 2.414242e-02, 2.910950e-02, 9.490384e-02, 1.624063e-02, 1.563516e-02, // 0x60
            3.552255e-02, 5.665595e-02, 8.877703e-04, 5.541904e-03, 3.217460e-02, 1.847585e-02, 5.564337e-02, 5.848444e-02, // 0x68
            1.582437e-02, 7.489406e-04, 4.952959e-02, 5.092777e-02, 6.805934e-02, 2.148193e-02, 8.383441e-03, 1.260610e-02, // 0x70
            1.656049e-03, 1.364007e-02, 7.965875e-04, 8.598379e-08, 8.598379e-08, 8.598379e-08, 3.513871e-06, 8.598379e-08, // 0x78
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0x80
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0x88
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0x90
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0x98
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0xA0
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0xA8
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0xB0
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0xB8
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0xC0
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0xC8
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0xD0
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0xD8
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0xE0
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0xE8
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0xF0
            1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, 1.567979e-01, // 0xF8
    };

    // Mean of the simdjson example documents; see the class comment and tools/byte-frequencies.
    private static final double[] JSON_PROBABILITIES = {
            1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, // 0x00
            1.560831e-05, 1.560831e-05, 2.064806e-02, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, // 0x08
            1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, // 0x10
            1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, 1.560831e-05, // 0x18
            2.535678e-01, 4.916698e-05, 5.681095e-02, 6.623320e-05, 1.562961e-04, 1.448512e-04, 1.103768e-04, 1.241097e-04, // 0x20
            3.856775e-04, 3.944670e-04, 8.562688e-05, 8.189461e-04, 3.270073e-02, 7.103851e-03, 1.443001e-02, 8.248642e-03, // 0x28
            3.527710e-02, 1.932369e-02, 1.634875e-02, 1.400059e-02, 1.294263e-02, 1.349233e-02, 1.305984e-02, 1.364192e-02, // 0x30
            1.329601e-02, 2.221882e-02, 2.091858e-02, 3.766909e-05, 1.351646e-04, 2.375690e-04, 1.355170e-04, 4.914102e-05, // 0x38
            3.230515e-04, 1.568987e-03, 1.340673e-03, 1.666162e-03, 1.195620e-03, 1.040418e-03, 9.122317e-04, 1.113598e-03, // 0x40
            6.752184e-04, 1.686312e-03, 5.817418e-04, 6.823291e-04, 8.991990e-04, 1.422191e-03, 7.752764e-04, 8.123679e-04, // 0x48
            1.165168e-03, 5.125279e-04, 8.321689e-04, 2.071152e-03, 1.627177e-03, 9.212029e-04, 5.070348e-04, 5.814306e-04, // 0x50
            3.912874e-04, 4.995263e-04, 4.163536e-04, 4.117567e-03, 1.975019e-03, 4.118998e-03, 1.635422e-05, 8.751035e-03, // 0x58
            2.217697e-05, 2.623152e-02, 6.089830e-03, 1.207061e-02, 1.401559e-02, 3.545403e-02, 7.039941e-03, 7.187282e-03, // 0x60
            7.732717e-03, 2.161739e-02, 1.664981e-03, 3.048507e-03, 1.809820e-02, 1.120962e-02, 2.051559e-02, 2.300755e-02, // 0x68
            1.158693e-02, 4.235048e-04, 1.989925e-02, 1.939683e-02, 2.469433e-02, 1.289811e-02, 3.552565e-03, 4.855904e-03, // 0x70
            1.715302e-03, 3.412256e-03, 9.711982e-04, 3.872627e-03, 2.316391e-05, 3.872579e-03, 2.274480e-05, 1.560831e-05, // 0x78
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0x80
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0x88
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0x90
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0x98
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0xA0
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0xA8
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0xB0
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0xB8
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0xC0
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0xC8
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0xD0
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0xD8
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0xE0
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0xE8
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0xF0
            2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, 2.535678e-01, // 0xF8
    };

    private static final ByteFrequencies ENGLISH_TEXT = new ByteFrequencies(ENGLISH_TEXT_PROBABILITIES);
    private static final ByteFrequencies JSON = new ByteFrequencies(JSON_PROBABILITIES);
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
     * Returns the preset measured on English prose.
     */
    public static ByteFrequencies englishText()
    {
        return ENGLISH_TEXT;
    }

    /**
     * Returns the preset measured on JSON documents.
     */
    public static ByteFrequencies json()
    {
        return JSON;
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
