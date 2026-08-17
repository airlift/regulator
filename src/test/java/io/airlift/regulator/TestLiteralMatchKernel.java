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
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestLiteralMatchKernel
{
    @Test
    public void testLiteralComparisons()
    {
        assertComparisons("1234567", "1234567", true);
        assertComparisons("1234567", "1234568", false);
        assertComparisons("12345678", "12345678", true);
        assertComparisons("12345678", "12345679", false);
        assertComparisons("π-value", "π-value", true);
        assertComparisons("π-value", "ρ-value", false);
    }

    @Test
    public void testContainsAndFind()
    {
        Slice input = offsetSlice("head-π-tail");
        Slice literal = Slices.utf8Slice("π");

        assertThat(LiteralMatchKernel.contains(input, literal)).isTrue();
        assertThat(LiteralMatchKernel.find(input, literal, 0)).isEqualTo(5);
        assertThat(LiteralMatchKernel.find(input, literal, 6)).isNegative();
        assertThat(LiteralMatchKernel.contains(input, Slices.utf8Slice("ρ"))).isFalse();
    }

    private static void assertComparisons(String value, String candidate, boolean expected)
    {
        Slice input = offsetSlice("head-" + value + "-tail");
        Slice literal = Slices.utf8Slice(candidate);
        int valueLength = Slices.utf8Slice(value).length();

        assertThat(LiteralMatchKernel.equals(offsetSlice(value), literal)).isEqualTo(expected);
        assertThat(LiteralMatchKernel.startsWith(input, Slices.utf8Slice("head-" + candidate))).isEqualTo(expected);
        assertThat(LiteralMatchKernel.endsWith(input, Slices.utf8Slice(candidate + "-tail"))).isEqualTo(expected);
        assertThat(LiteralMatchKernel.matchesAt(input, 5, literal)).isEqualTo(expected);
        assertThat(LiteralMatchKernel.matchesAt(input, 5 + valueLength, Slices.EMPTY_SLICE)).isTrue();
    }

    private static Slice offsetSlice(String value)
    {
        Slice logicalValue = Slices.utf8Slice(value);
        return Slices.utf8Slice("ignored-" + value + "-ignored").slice(8, logicalValue.length());
    }
}
