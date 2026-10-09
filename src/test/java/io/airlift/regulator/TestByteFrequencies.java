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
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

public class TestByteFrequencies
{
    @Test
    public void testProbeLeavesFirstByteOnlyForMoreThanFourTimesRarerByte()
    {
        // Smoothed, a, b, c, and d are 40, 10, 9, and 1 parts in 312.
        long[] counts = new long[256];
        counts['a'] = 39;
        counts['b'] = 9;
        counts['c'] = 8;
        ByteFrequencies frequencies = ByteFrequencies.fromCounts(counts);

        // Exactly four times rarer keeps the first byte; more than four times rarer moves.
        assertThat(probeOffset(frequencies, "ab")).isZero();
        assertThat(probeOffset(frequencies, "ac")).isEqualTo(1);
        // The rarest byte is chosen, not the first one that clears the factor.
        assertThat(probeOffset(frequencies, "acd")).isEqualTo(2);
        // A rarer first byte stays.
        assertThat(probeOffset(frequencies, "da")).isZero();
        assertThat(probeOffset(frequencies, "a")).isZero();
    }

    @Test
    public void testFromCountsSmoothsCounts()
    {
        long[] counts = new long[256];
        counts['a'] = 6;
        counts[0xE9] = 249;
        ByteFrequencies frequencies = ByteFrequencies.fromCounts(counts);
        // The counts are copied.
        counts['a'] = 1_000;

        // Each probability is (count + 1) / (total + 256), so an absent byte is rare but possible.
        assertThat(frequencies.probability((byte) 'a')).isEqualTo(7.0 / 511);
        assertThat(frequencies.probability((byte) 0xE9)).isEqualTo(250.0 / 511);
        assertThat(frequencies.probability((byte) 'b')).isEqualTo(1.0 / 511);
        double total = 0;
        for (int value = 0; value < 256; value++) {
            total += frequencies.probability((byte) value);
        }
        assertThat(total).isCloseTo(1.0, within(1e-12));
    }

    @Test
    public void testBuilderCountsSampleBytes()
    {
        Slice backing = utf8Slice("xxhttps://a.b/c?id=1 café=2xx");
        ByteFrequencies.Builder builder = ByteFrequencies.builder();
        ByteFrequencies learned = builder
                .add(backing.slice(2, backing.length() - 4))
                .add(utf8Slice("id=3"))
                .build();

        long[] counts = new long[256];
        for (byte value : "https://a.b/c?id=1 café=2id=3".getBytes(UTF_8)) {
            counts[value & 0xFF]++;
        }
        ByteFrequencies expected = ByteFrequencies.fromCounts(counts);
        for (int value = 0; value < 256; value++) {
            assertThat(learned.probability((byte) value)).as("byte %s", value).isEqualTo(expected.probability((byte) value));
        }

        assertThatThrownBy(() -> ByteFrequencies.builder().add(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("sample is null");
        assertThatThrownBy(() -> builder.add(utf8Slice("x")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testSuppliedDistributionProbesAnyByte()
    {
        // A measured distribution may probe punctuation, digits, and bytes at or above 0x80.
        long[] counts = new long[256];
        Arrays.fill(counts, 1_000);
        counts['/'] = 0;
        counts['='] = 0;
        counts['7'] = 0;
        counts[0xA9] = 0;
        ByteFrequencies frequencies = ByteFrequencies.fromCounts(counts);
        assertThat(probeOffset(frequencies, "https://")).isEqualTo(6);
        assertThat(probeOffset(frequencies, "id=")).isEqualTo(2);
        assertThat(probeOffset(frequencies, "port 7")).isEqualTo(5);
        assertThat(probeOffset(frequencies, "café")).isEqualTo(4);
        // Earliest on ties, and a rare first byte stays.
        assertThat(probeOffset(frequencies, "a/=")).isEqualTo(1);
        assertThat(probeOffset(frequencies, "/=")).isZero();
        assertThat(probeOffset(frequencies, "user")).isZero();
    }

    @Test
    public void testUniformKeepsFirstByte()
    {
        for (int value = 1; value < 256; value++) {
            assertThat(ByteFrequencies.uniform().probability((byte) value)).isEqualTo(ByteFrequencies.uniform().probability((byte) 0));
        }
        for (String literal : new String[] {"svc1://", "https://", "content-type:", "aab", "café="}) {
            assertThat(probeOffset(ByteFrequencies.uniform(), literal)).as(literal).isZero();
        }
    }

    @Test
    public void testRejectsInvalidArguments()
    {
        assertThatThrownBy(() -> ByteFrequencies.fromCounts(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("counts is null");
        assertThatThrownBy(() -> ByteFrequencies.fromCounts(new long[255]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("counts must have 256 entries: 255");
        long[] negative = new long[256];
        negative['x'] = -1;
        assertThatThrownBy(() -> ByteFrequencies.fromCounts(negative))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("count for byte 120 is negative: -1");
    }

    private static int probeOffset(ByteFrequencies frequencies, String literal)
    {
        return frequencies.probeOffset(literal.getBytes(UTF_8));
    }
}
