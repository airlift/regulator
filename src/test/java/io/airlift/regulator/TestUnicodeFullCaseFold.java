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

import io.airlift.jcodings.CaseFoldCodeItem;
import io.airlift.jcodings.Config;
import io.airlift.jcodings.specific.NonStrictUTF8Encoding;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestUnicodeFullCaseFold
{
    @Test
    public void testJvmMappingsCoverPinnedJoniMultiCharacterFolds()
    {
        int joniMappingCount = 0;
        int jvmMappingCount = 0;

        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                continue;
            }

            byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
            CaseFoldCodeItem[] joniItems = NonStrictUTF8Encoding.INSTANCE.caseFoldCodesByString(
                    Config.ENC_CASE_FOLD_DEFAULT,
                    encoded,
                    0,
                    encoded.length);
            int[] jvmFold = UnicodeFullCaseFold.fold(new int[] {codePoint});

            boolean hasJoniMultiCharacterFold = false;
            boolean jvmFoldMatchesJoni = false;
            for (CaseFoldCodeItem item : joniItems) {
                if (item.codeLen <= 1) {
                    continue;
                }
                hasJoniMultiCharacterFold = true;
                int[] expectedFold = Arrays.stream(item.code)
                        .map(TestUnicodeFullCaseFold::canonicalSimpleFold)
                        .toArray();
                if (Arrays.equals(jvmFold, expectedFold)) {
                    jvmFoldMatchesJoni = true;
                }
                assertFullFoldMatch(new int[] {codePoint}, item.code);
                assertFullFoldMatch(item.code, new int[] {codePoint});
            }

            if (hasJoniMultiCharacterFold) {
                joniMappingCount++;
                assertThat(jvmFoldMatchesJoni)
                        .as("JVM full fold for U+%04X", codePoint)
                        .isTrue();
            }
            if (jvmFold.length > 1) {
                jvmMappingCount++;
                if (!hasJoniMultiCharacterFold) {
                    assertThat(codePoint)
                            .as("JVM-only full fold")
                            .isEqualTo(0x1E9E);
                }
            }
        }

        assertThat(joniMappingCount).isEqualTo(103);
        assertThat(jvmMappingCount).isEqualTo(104);
    }

    private static void assertFullFoldMatch(int[] patternRunes, int[] inputRunes)
    {
        StringBuilder pattern = new StringBuilder("(?i)\\A");
        for (int rune : patternRunes) {
            pattern.append("\\x{").append(Integer.toHexString(rune)).append('}');
        }
        pattern.append("\\z");

        String input = new String(inputRunes, 0, inputRunes.length);
        assertThat(TrinoRegexp.compile(utf8Slice(pattern.toString())).contains(utf8Slice(input)))
                .as("%s matches %s", Arrays.toString(patternRunes), Arrays.toString(inputRunes))
                .isTrue();
    }

    private static int canonicalSimpleFold(int rune)
    {
        return Character.toLowerCase(Character.toUpperCase(rune));
    }
}
