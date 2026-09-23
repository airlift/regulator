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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    @Test
    public void testAsciiLiteralsMatchMultiCharacterFoldTargets()
    {
        // ASCII runes have no multi-character mapping of their own, but their folded sequence can
        // still be the target of one, so the Trino frontend must keep those alternatives.
        assertThat(UnicodeFullCaseFold.requiresFullCaseFold(new int[] {'S', 'S'})).isTrue();
        assertThat(UnicodeFullCaseFold.requiresFullCaseFold(new int[] {'f', 'f', 'i'})).isTrue();
        assertThat(UnicodeFullCaseFold.requiresFullCaseFold("hello".codePoints().toArray())).isFalse();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)ss")).contains(utf8Slice("\u00DF"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)ffi")).contains(utf8Slice("\uFB03"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)stra\u00DFe")).contains(utf8Slice("STRASSE"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)ss")).contains(utf8Slice("s"))).isFalse();
    }

    @Test
    public void testTokensSelectedByFirstFoldedRune()
    {
        List<UnicodeFullCaseFold.FoldToken> tokens = UnicodeFullCaseFold.multiCharacterTokens();
        for (UnicodeFullCaseFold.FoldToken token : tokens) {
            int firstRune = token.foldedRunes()[0];
            assertThat(UnicodeFullCaseFold.multiCharacterTokensStartingWith(firstRune))
                    .containsExactlyElementsOf(tokens.stream()
                            .filter(candidate -> candidate.foldedRunes()[0] == firstRune)
                            .toList());
        }
        assertThat(UnicodeFullCaseFold.multiCharacterTokensStartingWith('_')).isEmpty();
        assertThat(UnicodeFullCaseFold.multiCharacterTokensStartingWith(0x1F600)).isEmpty();
    }

    @Test
    public void testIndexedLookupsMatchCompleteTokenScan()
    {
        List<UnicodeFullCaseFold.FoldToken> tokens = UnicodeFullCaseFold.multiCharacterTokens();
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            assertIndexedLookupsMatch(tokens, new int[] {codePoint});
        }

        List<int[]> sequences = new ArrayList<>();
        for (UnicodeFullCaseFold.FoldToken token : tokens) {
            int[] folded = token.foldedRunes();
            sequences.add(folded);
            sequences.add(concat(new int[] {'x'}, folded));
            sequences.add(concat(folded, new int[] {0x1E9E}));
            sequences.add(concat(folded, folded));
            sequences.add(Arrays.copyOf(folded, folded.length - 1));
            sequences.add(new int[] {token.sourceRunes().range(0).low(), folded[0]});
        }
        sequences.add("Strasse".codePoints().toArray());
        sequences.add("\uFB00i ss \u0130".codePoints().toArray());
        for (int[] sequence : sequences) {
            assertIndexedLookupsMatch(tokens, sequence);
        }
    }

    private static void assertIndexedLookupsMatch(List<UnicodeFullCaseFold.FoldToken> tokens, int[] runes)
    {
        int[] folded = UnicodeFullCaseFold.fold(runes);
        assertThat(folded).as("fold %s", Arrays.toString(runes)).isEqualTo(referenceFold(tokens, runes));
        assertThat(UnicodeFullCaseFold.requiresFullCaseFold(runes))
                .as("requiresFullCaseFold %s", Arrays.toString(runes))
                .isEqualTo(referenceRequiresFullCaseFold(tokens, folded));
        assertThat(UnicodeFullCaseFold.byteLength(runes))
                .as("byteLength %s", Arrays.toString(runes))
                .isEqualTo(referenceByteLength(tokens, folded));
    }

    // Folds each rune by a full token scan; the last token whose sources contain the rune wins.
    private static int[] referenceFold(List<UnicodeFullCaseFold.FoldToken> tokens, int[] runes)
    {
        List<Integer> folded = new ArrayList<>();
        for (int rune : runes) {
            int[] mapping = new int[] {canonicalSimpleFold(rune)};
            for (UnicodeFullCaseFold.FoldToken token : tokens) {
                if (token.sourceRunes().contains(rune)) {
                    mapping = token.foldedRunes();
                }
            }
            for (int value : mapping) {
                folded.add(value);
            }
        }
        return folded.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean referenceRequiresFullCaseFold(List<UnicodeFullCaseFold.FoldToken> tokens, int[] folded)
    {
        for (int index = 0; index < folded.length; index++) {
            for (UnicodeFullCaseFold.FoldToken token : tokens) {
                if (token.matches(folded, index)) {
                    return true;
                }
            }
        }
        return false;
    }

    // The production algorithm before tokens were indexed: every token is tried at every position.
    private static UnicodeFullCaseFold.ByteLength referenceByteLength(List<UnicodeFullCaseFold.FoldToken> tokens, int[] folded)
    {
        int[] minimum = new int[folded.length + 1];
        int[] maximum = new int[folded.length + 1];
        for (int index = folded.length - 1; index >= 0; index--) {
            CharClass simpleClass = UnicodeFullCaseFold.simpleFoldClass(folded[index]);
            minimum[index] = minimumUtf8Length(simpleClass) + minimum[index + 1];
            maximum[index] = maximumUtf8Length(simpleClass) + maximum[index + 1];
            for (UnicodeFullCaseFold.FoldToken token : tokens) {
                if (token.matches(folded, index)) {
                    int next = index + token.foldedRunes().length;
                    minimum[index] = Math.min(minimum[index], minimumUtf8Length(token.sourceRunes()) + minimum[next]);
                    maximum[index] = Math.max(maximum[index], maximumUtf8Length(token.sourceRunes()) + maximum[next]);
                }
            }
        }
        return new UnicodeFullCaseFold.ByteLength(minimum[0], maximum[0]);
    }

    private static int minimumUtf8Length(CharClass characterClass)
    {
        int minimum = Integer.MAX_VALUE;
        for (RuneRange range : characterClass.ranges()) {
            minimum = Math.min(minimum, Utf8.encodedLength(range.low()));
        }
        return minimum;
    }

    private static int maximumUtf8Length(CharClass characterClass)
    {
        int maximum = 0;
        for (RuneRange range : characterClass.ranges()) {
            maximum = Math.max(maximum, Utf8.encodedLength(range.high()));
        }
        return maximum;
    }

    private static int[] concat(int[] left, int[] right)
    {
        int[] result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
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
