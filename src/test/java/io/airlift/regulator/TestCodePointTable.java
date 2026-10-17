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

import java.util.Locale;

import static io.airlift.regulator.CharClass.RUNEMAX;
import static org.assertj.core.api.Assertions.assertThat;

public class TestCodePointTable
{
    // Mappings at both ends of the code point range and on each side of a block boundary; every
    // other code point maps to itself.
    @Test
    public void testSparseMappingsAcrossBlocks()
    {
        int[] codePoints = {0, 0xFF, 0x100, 0x1FF, RUNEMAX - 1, RUNEMAX};
        int[] values = {RUNEMAX, 0x100, 0xFF, 0, 7, 0};
        CodePointTable table = new CodePointTable(codePoints, values);

        int mapping = 0;
        for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
            int expected = codePoint;
            if (mapping < codePoints.length && codePoints[mapping] == codePoint) {
                expected = values[mapping++];
            }
            if (table.get(codePoint) != expected) {
                throw new AssertionError(String.format(Locale.ROOT, "U+%04X maps to U+%04X", codePoint, table.get(codePoint)));
            }
        }
        assertThat(mapping).isEqualTo(codePoints.length);
    }
}
