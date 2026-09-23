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

import java.util.BitSet;
import java.util.List;
import java.util.Random;

import static io.airlift.regulator.CharClass.RUNEMAX;
import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestCharClassBuilder
{
    @Test
    public void testAddRangeMergesAndCounts()
    {
        CharClassBuilder b = new CharClassBuilder();
        b.addRange(10, 12);
        b.addRange(15, 17);
        b.addRange(13, 14);

        assertThat(b.rangeCount()).isEqualTo(1);
        assertThat(b.runeCount()).isEqualTo(8);
        assertThat(b.range(0)).isEqualTo(new RuneRange(10, 17));
        assertThat(b.contains(9)).isFalse();
        assertThat(b.contains(10)).isTrue();
        assertThat(b.contains(17)).isTrue();
        assertThat(b.contains(18)).isFalse();
    }

    @Test
    public void testNegate()
    {
        CharClassBuilder b = new CharClassBuilder();
        b.addRange(10, 20);
        b.negate();

        assertThat(b.contains(0)).isTrue();
        assertThat(b.contains(9)).isTrue();
        assertThat(b.contains(10)).isFalse();
        assertThat(b.contains(20)).isFalse();
        assertThat(b.contains(21)).isTrue();
        assertThat(b.contains(RUNEMAX)).isTrue();
    }

    @Test
    public void testFoldsAscii()
    {
        CharClassBuilder b = new CharClassBuilder();
        b.addRange('A', 'A');
        assertThat(b.foldsAscii()).isFalse();
        b.addRange('a', 'a');
        assertThat(b.foldsAscii()).isTrue();
    }

    @Test
    public void testUnicodeGroupReusesCachedRanges()
    {
        CharClass cached = UnicodeGroups.lookup("L");
        assertReusesCachedRanges(RegexpParser.parse(utf8Slice("\\p{L}+"), Regexp.LIKE_PERL).regexp(), cached);
        assertReusesCachedRanges(TrinoRegexpParser.parse(utf8Slice("\\p{L}+"), Regexp.LIKE_PERL).regexp(), cached);
        assertReusesCachedRanges(JavaRegexpParser.parse(utf8Slice("\\p{L}+"), Regexp.LIKE_PERL).regexp(), cached);
    }

    private static void assertReusesCachedRanges(Regexp parsed, CharClass cached)
    {
        CharClass characterClass = parsed.child(0).charClass();

        assertThat(characterClass.rangeCount()).isEqualTo(cached.rangeCount());
        assertThat(characterClass.range(0)).isSameAs(cached.range(0));
        assertThat(characterClass.range(characterClass.rangeCount() / 2))
                .isSameAs(cached.range(cached.rangeCount() / 2));
        assertThat(characterClass.range(characterClass.rangeCount() - 1))
                .isSameAs(cached.range(cached.rangeCount() - 1));
    }

    @Test
    public void testAddCharClassAppliesNewlineAndCaseFoldFlags()
    {
        CharClassBuilder newlineBuilder = new CharClassBuilder();
        newlineBuilder.addCharClass(new CharClass(false, 21, new RuneRange[] {new RuneRange(0, 20)}), 0);
        assertThat(newlineBuilder.contains('\t')).isTrue();
        assertThat(newlineBuilder.contains('\n')).isFalse();
        assertThat(newlineBuilder.contains('\u000B')).isTrue();

        CharClassBuilder caseFoldBuilder = new CharClassBuilder();
        caseFoldBuilder.addCharClass(
                new CharClass(false, 1, new RuneRange[] {new RuneRange('A', 'A')}),
                Regexp.CLASS_NEWLINE | Regexp.ASCII_FOLD_CASE);
        assertThat(caseFoldBuilder.contains('A')).isTrue();
        assertThat(caseFoldBuilder.contains('a')).isTrue();
    }

    @Test
    public void testAddRangeInsertsBeforeAndBetweenExistingRanges()
    {
        CharClassBuilder builder = new CharClassBuilder();
        builder.addRange(100, 110);
        builder.addRange(200, 210);
        builder.addRange(300, 310);
        builder.addRange(50, 60);
        builder.addRange(150, 160);
        builder.addRange(320, 330);
        builder.addRange(111, 149);

        assertThat(builder.rangeCount()).isEqualTo(5);
        assertThat(builder.range(0)).isEqualTo(new RuneRange(50, 60));
        assertThat(builder.range(1)).isEqualTo(new RuneRange(100, 160));
        assertThat(builder.range(2)).isEqualTo(new RuneRange(200, 210));
        assertThat(builder.range(3)).isEqualTo(new RuneRange(300, 310));
        assertThat(builder.range(4)).isEqualTo(new RuneRange(320, 330));
        assertThat(builder.runeCount()).isEqualTo(11 + 61 + 11 + 11 + 11);

        builder.addRange(0, RUNEMAX);
        assertThat(builder.rangeCount()).isEqualTo(1);
        assertThat(builder.runeCount()).isEqualTo(RUNEMAX + 1);
    }

    @Test
    public void testAddCharClassMergesLargeClasses()
    {
        CharClass upper = UnicodeGroups.lookup("Lu");
        CharClass lower = UnicodeGroups.lookup("Ll");

        CharClassBuilder merged = new CharClassBuilder();
        merged.addCharClass(upper, Regexp.LIKE_PERL);
        merged.addCharClass(lower, Regexp.LIKE_PERL);

        CharClassBuilder expected = new CharClassBuilder();
        for (RuneRange range : lower.ranges()) {
            expected.addRange(range.low(), range.high());
        }
        for (RuneRange range : upper.ranges()) {
            expected.addRange(range.low(), range.high());
        }
        assertThat(merged.toCharClass()).isEqualTo(expected.toCharClass());
        assertThat(merged.runeCount()).isEqualTo(upper.runeCount() + lower.runeCount());
        assertThat(merged.foldsAscii()).isTrue();

        CharClassBuilder fromBuilder = new CharClassBuilder();
        fromBuilder.addRange('a', 'c');
        fromBuilder.addCharClass(merged);
        assertThat(fromBuilder.toCharClass()).isEqualTo(merged.toCharClass());
    }

    @Test
    public void testAddCharClassMatchesBitSetModelForRandomClasses()
    {
        Random random = new Random(42);
        for (int iteration = 0; iteration < 200; iteration++) {
            CharClassBuilder existing = randomBuilder(random, 1 + random.nextInt(40));
            CharClassBuilder source = randomBuilder(random, 1 + random.nextInt(40));

            BitSet model = new BitSet();
            for (int index = 0; index < existing.rangeCount(); index++) {
                model.set(existing.range(index).low(), existing.range(index).high() + 1);
            }
            for (int index = 0; index < source.rangeCount(); index++) {
                model.set(source.range(index).low(), source.range(index).high() + 1);
            }

            CharClassBuilder viaBuilder = existing.copy();
            viaBuilder.addCharClass(source);
            CharClassBuilder viaClass = existing.copy();
            viaClass.addCharClass(source.toCharClass(), Regexp.CLASS_NEWLINE);

            for (CharClassBuilder builder : new CharClassBuilder[] {viaBuilder, viaClass}) {
                assertThat(builder.runeCount()).as("iteration %s", iteration).isEqualTo(model.cardinality());
                int expectedRanges = 0;
                for (int bit = model.nextSetBit(0); bit >= 0; bit = model.nextSetBit(model.nextClearBit(bit))) {
                    assertThat(builder.range(expectedRanges)).as("iteration %s", iteration).isEqualTo(new RuneRange(bit, model.nextClearBit(bit) - 1));
                    expectedRanges++;
                }
                assertThat(builder.rangeCount()).as("iteration %s", iteration).isEqualTo(expectedRanges);
            }
        }
    }

    @Test
    public void testAddCharClassExcludesNewlineFromMergedClass()
    {
        // Twenty ranges exceed the merge threshold of eight, so the class takes the linear merge.
        CharClassBuilder source = new CharClassBuilder();
        for (int base = 0; base < 20; base++) {
            source.addRange(base * 10, base * 10 + 3);
        }
        assertThat(source.contains('\n')).isTrue();

        CharClassBuilder builder = new CharClassBuilder();
        builder.addRange(1000, 1000);
        builder.addCharClass(source.toCharClass(), 0);
        assertThat(builder.contains('\n')).isFalse();
        assertThat(builder.contains(11)).isTrue();
        assertThat(builder.contains(13)).isTrue();
        assertThat(builder.contains(1000)).isTrue();
        assertThat(builder.rangeCount()).isEqualTo(source.rangeCount() + 1);
        assertThat(builder.runeCount()).isEqualTo(source.runeCount());
    }

    @Test
    public void testRangeViewIsUnmodifiable()
    {
        CharClass characterClass = new CharClass(false, 4, new RuneRange[] {new RuneRange('a', 'b'), new RuneRange('x', 'y')});
        List<RuneRange> view = characterClass.rangeView();
        assertThat(view).containsExactly(new RuneRange('a', 'b'), new RuneRange('x', 'y'));
        assertThatThrownBy(() -> view.set(0, new RuneRange('a', 'z'))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(characterClass.range(0)).isEqualTo(new RuneRange('a', 'b'));
    }

    private static CharClassBuilder randomBuilder(Random random, int rangeCount)
    {
        CharClassBuilder builder = new CharClassBuilder();
        for (int index = 0; index < rangeCount; index++) {
            int low = random.nextInt(400);
            builder.addRange(low, low + random.nextInt(6));
        }
        return builder;
    }
}
