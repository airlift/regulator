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

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestByteFrequencies
{
    @Test
    public void testProbeLeavesFirstByteOnlyForMoreThanFourTimesRarerLetter()
    {
        ByteFrequencies frequencies = frequencies(Map.of('a', 0.4, 'b', 0.1, 'c', 0.09, 'd', 0.01));

        // Exactly four times rarer keeps the first byte; more than four times rarer moves.
        assertThat(probeOffset(frequencies, "ab")).isZero();
        assertThat(probeOffset(frequencies, "ac")).isEqualTo(1);
        // The rarest letter is chosen, not the first one that clears the factor.
        assertThat(probeOffset(frequencies, "acd")).isEqualTo(2);
        // A rarer first byte stays.
        assertThat(probeOffset(frequencies, "da")).isZero();
        // Only the first length bytes are considered.
        assertThat(frequencies.probeOffset("acd".getBytes(UTF_8), 2)).isEqualTo(1);
        assertThat(probeOffset(frequencies, "a")).isZero();
    }

    @Test
    public void testProbeMovesOnlyToAsciiLetters()
    {
        ByteFrequencies frequencies = frequencies(Map.of('a', 0.4, 'B', 0.05, '=', 0.001, '5', 0.001, '/', 0.001));

        // Rarer punctuation and digits never become the probe, even when no letter qualifies.
        assertThat(probeOffset(frequencies, "a=")).isZero();
        assertThat(probeOffset(frequencies, "a5/")).isZero();
        // A letter is chosen over rarer punctuation and digits, in either case.
        assertThat(probeOffset(frequencies, "a=5B/")).isEqualTo(3);
        assertThat(probeOffset(frequencies, "=aB")).isZero();
    }

    @Test
    public void testDefaultNeverProbesNonAsciiBytes()
    {
        // Every byte at or above 0x80 is as common as the most common ASCII byte.
        double mostCommonAscii = 0;
        for (int value = 0; value < 0x80; value++) {
            mostCommonAscii = Math.max(mostCommonAscii, ByteFrequencies.DEFAULT.probability((byte) value));
        }
        for (int value = 0x80; value < 256; value++) {
            assertThat(ByteFrequencies.DEFAULT.probability((byte) value)).as("byte %s", value).isEqualTo(mostCommonAscii);
        }
        assertThat(ByteFrequencies.DEFAULT.probability((byte) ' ')).isEqualTo(mostCommonAscii);

        for (String literal : new String[] {"Шерлок Холмс", "夏洛克·福尔摩斯", "例え"}) {
            assertThat(probeOffset(ByteFrequencies.DEFAULT, literal)).as(literal).isZero();
        }
        // A non-ASCII first byte still yields to a rare letter, and a UTF-8 or Latin-1 byte
        // never becomes the probe.
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "év")).isEqualTo(2);
        assertThat(ByteFrequencies.DEFAULT.probeOffset("év".getBytes(ISO_8859_1), 2)).isEqualTo(1);
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "aé")).isZero();
    }

    @Test
    public void testDefaultProbes()
    {
        // Common first bytes move to a much rarer letter.
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "svc1://")).isEqualTo(1);
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "abc")).isEqualTo(1);
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "content-type:")).isEqualTo(9);
        // A letter is chosen over a rarer digit.
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "svc5://")).isEqualTo(1);
        // Rarer punctuation, whose frequency depends on the data format, is never chosen.
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "https://")).isZero();
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "http://")).isZero();
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "user=")).isZero();
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "error: ")).isZero();
        // Rare or uncontested first bytes, and letters too close to call, stay.
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "Sherlock Holmes")).isZero();
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "\"level\":\"ERROR\"")).isZero();
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "GET /")).isZero();
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "http")).isZero();
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "literal")).isZero();
        assertThat(probeOffset(ByteFrequencies.DEFAULT, "ERROR")).isZero();
    }

    @Test
    public void testRejectsInvalidArguments()
    {
        assertThatThrownBy(() -> new ByteFrequencies(new double[255]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ByteFrequencies.DEFAULT.probeOffset(new byte[2], 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ByteFrequencies.DEFAULT.probeOffset(new byte[2], 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int probeOffset(ByteFrequencies frequencies, String literal)
    {
        byte[] bytes = literal.getBytes(UTF_8);
        return frequencies.probeOffset(bytes, bytes.length);
    }

    private static ByteFrequencies frequencies(Map<Character, Double> probabilities)
    {
        double[] table = new double[256];
        Arrays.fill(table, 1);
        probabilities.forEach((value, probability) -> table[value] = probability);
        return new ByteFrequencies(table);
    }
}
