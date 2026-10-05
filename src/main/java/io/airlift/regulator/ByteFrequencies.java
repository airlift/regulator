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

import static java.util.Objects.requireNonNull;

/**
 * Background byte probabilities used to choose which byte of a literal a candidate scan probes.
 * <p>
 * A scan for a literal stops at every occurrence of its probe byte, so probing a rare byte
 * hands fewer candidates to verification. {@link #probeOffset} leaves the first byte only for an
 * ASCII letter more than {@value #PROBE_FACTOR} times rarer. Punctuation and digit frequencies
 * depend on the data format, as with {@code /} in URLs or {@code =} in key=value logs, so no
 * static table predicts them, while letter frequencies vary far less. The factor keeps the first
 * byte when the estimate is close, because the estimate is a blend rather than the searched text.
 * <p>
 * The {@link #DEFAULT} order of ASCII bytes is the rank order of {@code default_rank.rs} in
 * Rust's memchr crate (https://github.com/BurntSushi/memchr, dual-licensed MIT or Unlicense).
 * A rank order has no magnitudes, so the table gives the byte of the n-th lowest rank the
 * n-th lowest probability of an equal blend of English prose, source code, log, and JSON
 * corpora, and tied ranks share the mean of their probabilities. The scale is therefore
 * compressed, but the factor compares bytes on the scale the rule was evaluated with. Every byte
 * at or above 0x80 has the probability of the most common ASCII byte, so a UTF-8 lead or
 * continuation byte is never chosen and a literal of only such bytes keeps its first byte.
 * <p>
 * The values were computed from those corpus byte counts, not tuned by hand. Changing them
 * changes which byte a literal probes.
 */
final class ByteFrequencies
{
    private static final int PROBE_FACTOR = 4;

    // memchr rank order with blended corpus magnitudes; see the class comment.
    private static final double[] DEFAULT_PROBABILITIES = {
            0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, // 0x00
            0.000000e+00, 0.000000e+00, 9.314984e-04, 0.000000e+00, 0.000000e+00, 5.207451e-04, 0.000000e+00, 0.000000e+00, // 0x08
            0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, // 0x10
            0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, 0.000000e+00, // 0x18
            1.501102e-02, 2.582847e-06, 3.707210e-06, 2.659203e-06, 1.684713e-06, 3.418046e-06, 2.911748e-06, 4.406015e-06, // 0x20
            1.212436e-04, 1.961864e-04, 1.544611e-06, 9.348502e-08, 5.680049e-04, 9.489001e-06, 1.939966e-05, 2.503356e-04, // 0x28
            1.171095e-05, 3.608788e-05, 1.051246e-05, 6.679638e-06, 5.621722e-06, 4.755412e-06, 4.717568e-06, 4.125178e-06, // 0x30
            4.742636e-06, 9.168235e-06, 3.657045e-04, 7.587635e-06, 2.850215e-06, 5.634476e-06, 4.408965e-06, 2.831346e-07, // 0x38
            0.000000e+00, 7.050371e-06, 3.085115e-06, 7.225371e-06, 4.294955e-06, 6.970204e-06, 3.627098e-06, 3.481053e-06, // 0x40
            2.762117e-06, 7.169798e-06, 2.098982e-06, 1.734184e-06, 4.302819e-06, 4.543728e-06, 6.322199e-06, 4.017460e-06, // 0x48
            6.341025e-06, 0.000000e+00, 4.543429e-06, 7.107451e-06, 6.709013e-06, 2.979580e-06, 1.915084e-06, 2.145891e-06, // 0x50
            1.015637e-07, 1.436609e-06, 4.722504e-07, 2.555190e-06, 1.857305e-06, 2.533999e-06, 0.000000e+00, 2.100567e-04, // 0x58
            2.796653e-06, 1.432320e-03, 2.029839e-05, 8.122844e-04, 6.767436e-04, 1.775505e-03, 3.836710e-04, 2.445230e-05, // 0x60
            5.267953e-04, 1.222000e-03, 1.547249e-06, 5.225047e-06, 9.057207e-04, 5.762233e-04, 1.171964e-03, 1.015175e-03, // 0x68
            5.449869e-04, 1.889351e-06, 1.033659e-03, 9.844494e-04, 1.709101e-03, 6.410654e-04, 9.239975e-06, 7.860664e-06, // 0x70
            9.037847e-04, 1.760221e-05, 2.835576e-06, 5.349949e-06, 1.144820e-05, 5.226103e-06, 3.428578e-07, 0.000000e+00, // 0x78
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0x80
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0x88
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0x90
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0x98
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0xA0
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0xA8
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0xB0
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0xB8
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0xC0
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0xC8
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0xD0
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0xD8
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0xE0
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0xE8
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0xF0
            1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, 1.501102e-02, // 0xF8
    };

    static final ByteFrequencies DEFAULT = new ByteFrequencies(DEFAULT_PROBABILITIES);

    private final double[] probabilities;

    ByteFrequencies(double[] probabilities)
    {
        requireNonNull(probabilities, "probabilities is null");
        if (probabilities.length != 256) {
            throw new IllegalArgumentException("probabilities must have 256 entries");
        }
        this.probabilities = probabilities.clone();
    }

    /**
     * Returns the offset in {@code literal[0, length)} of the byte a candidate scan should
     * probe: the rarest ASCII letter after the first byte, earliest on ties, when it is more
     * than {@value #PROBE_FACTOR} times rarer than the first byte, and otherwise 0.
     */
    int probeOffset(byte[] literal, int length)
    {
        if (length < 1 || length > literal.length) {
            throw new IllegalArgumentException("length must be between 1 and the literal length");
        }
        int rarest = 0;
        for (int offset = 1; offset < length; offset++) {
            if (isAsciiLetter(literal[offset]) && (rarest == 0 || probability(literal[offset]) < probability(literal[rarest]))) {
                rarest = offset;
            }
        }
        return rarest != 0 && probability(literal[rarest]) * PROBE_FACTOR < probability(literal[0]) ? rarest : 0;
    }

    double probability(byte value)
    {
        return probabilities[value & 0xFF];
    }

    private static boolean isAsciiLetter(byte value)
    {
        int lower = value | 0x20;
        return lower >= 'a' && lower <= 'z';
    }
}
