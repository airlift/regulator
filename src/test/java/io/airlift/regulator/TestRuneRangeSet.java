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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.airlift.regulator.CharClass.RUNEMAX;
import static org.assertj.core.api.Assertions.assertThat;

public class TestRuneRangeSet
{
    @Test
    public void testMatchesCharClassBuilderOperations()
    {
        Random random = new Random(42);
        for (int iteration = 0; iteration < 300; iteration++) {
            // Each set is paired with the same set built by CharClassBuilder operations. Repeated
            // lopsided operations switch to a tree; comparable weights use linear merges.
            List<RuneRangeSet> sets = new ArrayList<>();
            List<CharClassBuilder> expected = new ArrayList<>();
            // Once the random steps run out, the remaining sets combine into one.
            for (int step = random.nextInt(200); step >= 0 || sets.size() > 1; step--) {
                int choice = (step < 0) ? 9 : random.nextInt(10);
                if (sets.isEmpty() || choice < 3) {
                    CharClassBuilder builder = randomBuilder(random, random.nextInt(4) == 0 ? random.nextInt(400) : random.nextInt(4));
                    sets.add(RuneRangeSet.of(builder.copy()));
                    expected.add(builder);
                    continue;
                }
                int index = random.nextInt(sets.size());
                if (choice < 5) {
                    sets.get(index).negate();
                    expected.get(index).negate();
                    continue;
                }
                if (choice < 6) {
                    int runeLimit = random.nextBoolean() ? 0xFF : RUNEMAX;
                    sets.get(index).removeAbove(runeLimit);
                    expected.get(index).removeAbove(runeLimit);
                    continue;
                }
                if (sets.size() < 2) {
                    continue;
                }
                RuneRangeSet left = sets.remove(index);
                CharClassBuilder expectedLeft = expected.remove(index);
                int otherIndex = random.nextInt(sets.size());
                RuneRangeSet right = sets.remove(otherIndex);
                CharClassBuilder expectedRight = expected.remove(otherIndex);
                switch (random.nextInt(3)) {
                    case 0 -> {
                        sets.add(left.union(right));
                        expectedLeft.addCharClass(expectedRight);
                    }
                    case 1 -> {
                        sets.add(left.intersection(right));
                        expectedLeft.retainAll(expectedRight);
                    }
                    default -> {
                        sets.add(left.symmetricDifference(right));
                        CharClassBuilder common = expectedLeft.copy();
                        common.retainAll(expectedRight);
                        common.negate();
                        expectedLeft.addCharClass(expectedRight);
                        expectedLeft.retainAll(common);
                    }
                }
                expected.add(expectedLeft);
            }
            CharClassBuilder last = expected.getFirst();
            boolean expectedAboveAscii = !last.isEmpty() && last.range(last.rangeCount() - 1).high() > 0x7F;
            assertThat(sets.getFirst().containsRuneAbove(0x7F)).as("iteration %s", iteration).isEqualTo(expectedAboveAscii);
            assertThat(sets.getFirst().toCharClassBuilder().toCharClass()).as("iteration %s", iteration).isEqualTo(last.toCharClass());
        }
    }

    private static CharClassBuilder randomBuilder(Random random, int rangeCount)
    {
        CharClassBuilder builder = new CharClassBuilder();
        for (int index = 0; index < rangeCount; index++) {
            int low = random.nextInt(random.nextBoolean() ? 0x300 : RUNEMAX);
            builder.addRange(low, Math.min(RUNEMAX, low + random.nextInt(4)));
        }
        return builder;
    }
}
