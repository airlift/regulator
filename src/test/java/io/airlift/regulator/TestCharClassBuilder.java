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

import static io.airlift.regulator.CharClass.RUNEMAX;
import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

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
}
