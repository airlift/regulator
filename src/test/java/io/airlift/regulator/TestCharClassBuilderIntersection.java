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

import static org.assertj.core.api.Assertions.assertThat;

public class TestCharClassBuilderIntersection
{
    @Test
    public void testRetainAll()
    {
        CharClassBuilder left = new CharClassBuilder();
        left.addRange('a', 'f');
        left.addRange('m', 'z');

        CharClassBuilder right = new CharClassBuilder();
        right.addRange('d', 'p');

        left.retainAll(right);

        assertThat(left.toCharClass()).isEqualTo(new CharClass(
                false,
                7,
                new RuneRange[] {
                        new RuneRange('d', 'f'),
                        new RuneRange('m', 'p'),
                }));
    }

    @Test
    public void testRetainAllRebuildsCaseMetadata()
    {
        CharClassBuilder left = new CharClassBuilder();
        left.addRange('A', 'Z');
        left.addRange('a', 'z');

        CharClassBuilder right = new CharClassBuilder();
        right.addRange('A', 'Z');

        left.retainAll(right);

        assertThat(left.toCharClass().foldsAscii()).isFalse();
        assertThat(left.runeCount()).isEqualTo(26);
    }

    @Test
    public void testAsciiCaseFolding()
    {
        CharClassBuilder characterClassBuilder = new CharClassBuilder();
        characterClassBuilder.addRangeFlags('k', 'k', Regexp.ASCII_FOLD_CASE);

        assertThat(characterClassBuilder.toCharClass()).isEqualTo(new CharClass(
                true,
                2,
                new RuneRange[] {
                        new RuneRange('K', 'K'),
                        new RuneRange('k', 'k'),
                }));
    }
}
