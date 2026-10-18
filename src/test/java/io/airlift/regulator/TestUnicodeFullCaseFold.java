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

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TestUnicodeFullCaseFold
{
    @Test
    public void testMappingsCoverPinnedJoniMultiCharacterFolds()
    {
        int joniMappingCount = 0;

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
            int[] trinoFold = CaseFolding.TRINO.fold(new int[] {codePoint});

            boolean hasJoniMultiCharacterFold = false;
            boolean trinoFoldMatchesJoni = false;
            for (CaseFoldCodeItem item : joniItems) {
                if (item.codeLen <= 1) {
                    continue;
                }
                hasJoniMultiCharacterFold = true;
                int[] expectedFold = Arrays.stream(item.code)
                        .map(CaseFolding.TRINO::simpleFold)
                        .toArray();
                if (Arrays.equals(trinoFold, expectedFold)) {
                    trinoFoldMatchesJoni = true;
                }
                assertFullFoldMatch(new int[] {codePoint}, item.code);
                assertFullFoldMatch(item.code, new int[] {codePoint});
            }

            if (hasJoniMultiCharacterFold) {
                joniMappingCount++;
                assertThat(trinoFoldMatchesJoni)
                        .as("Trino full fold for U+%04X", codePoint)
                        .isTrue();
            }
            if (trinoFold.length > 1 && !hasJoniMultiCharacterFold) {
                assertThat(codePoint)
                        .as("full fold missing from Joni's Unicode 4.1 tables")
                        .isIn(0x1E9E, 0x1DF95);
            }
        }

        assertThat(joniMappingCount).isEqualTo(103);
        // Joni's tables lack the Unicode 18.0 fold of U+1DF95, so require it directly.
        assertFullFoldMatch(new int[] {0x1DF95}, new int[] {'s', 's'});
        assertFullFoldMatch(new int[] {'s', 's'}, new int[] {0x1DF95});
    }

    @Test
    public void testFoldMatchesJdkFullCaseFolding()
            throws ReflectiveOperationException
    {
        // JDK 26 added full case folding comparison, at the Unicode version of its case mappings.
        Field order;
        try {
            order = String.class.getField("UNICODE_CASEFOLD_ORDER");
        }
        catch (NoSuchFieldException e) {
            order = null;
        }
        assumeTrue(order != null, "requires String.UNICODE_CASEFOLD_ORDER");
        @SuppressWarnings("unchecked")
        Comparator<String> caseFoldOrder = (Comparator<String>) order.get(null);
        // JDK 26 compares two Latin-1 strings without folding the micro sign to Greek small mu, so
        // its order is not transitive. A shared non-Latin-1 suffix keeps every comparison folded.
        assertFoldMatches((left, right) -> caseFoldOrder.compare(left + "Ā", right + "Ā"));
    }

    // Trino folding must equal Unicode full case folding (statuses C and F) at the Unicode version
    // of the JVM for every character the JVM assigns: the reference comparator and Trino folding
    // produce identical equivalence classes, and comparing each code point with its Trino fold and
    // its JDK case mappings gives the same result under both rules. Unicode never changes the full
    // fold of an assigned character, so the database, whose version is at least the JVM's, agrees
    // on them.
    private static void assertFoldMatches(Comparator<String> reference)
    {
        List<String> codePoints = new ArrayList<>();
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (Character.isDefined(codePoint) && Character.getType(codePoint) != Character.SURROGATE) {
                codePoints.add(Character.toString(codePoint));
            }
        }
        codePoints.sort(reference);
        // Sorting by the reference places each class of equivalent code points in one run.
        Map<String, String> referenceClassByTrinoFold = new HashMap<>();
        String referenceClass = null;
        String referenceClassTrinoFold = null;
        for (int index = 0; index < codePoints.size(); index++) {
            String value = codePoints.get(index);
            String trinoFold = trinoFold(value);
            if (index == 0 || reference.compare(codePoints.get(index - 1), value) != 0) {
                referenceClass = value;
                referenceClassTrinoFold = trinoFold;
            }
            else if (!trinoFold.equals(referenceClassTrinoFold)) {
                throw new AssertionError(String.format(Locale.ROOT, "Trino separates U+%04X from U+%04X", value.codePointAt(0), referenceClass.codePointAt(0)));
            }
            String previous = referenceClassByTrinoFold.putIfAbsent(trinoFold, referenceClass);
            if (previous != null && !previous.equals(referenceClass)) {
                throw new AssertionError(String.format(Locale.ROOT, "Trino joins U+%04X with U+%04X", value.codePointAt(0), previous.codePointAt(0)));
            }

            for (String candidate : List.of(trinoFold, value.toUpperCase(Locale.ROOT), value.toLowerCase(Locale.ROOT))) {
                if ((reference.compare(value, candidate) == 0) != trinoFold.equals(trinoFold(candidate))) {
                    throw new AssertionError(String.format(Locale.ROOT, "U+%04X against %s", value.codePointAt(0), candidate.codePoints()
                            .mapToObj(codePoint -> String.format(Locale.ROOT, "U+%04X", codePoint))
                            .toList()));
                }
            }
        }
    }

    private static String trinoFold(String value)
    {
        int[] folded = CaseFolding.TRINO.fold(value.codePoints().toArray());
        return new String(folded, 0, folded.length);
    }

    @Test
    public void testAsciiLiteralsMatchMultiCharacterFoldTargets()
    {
        // ASCII runes have no multi-character mapping of their own, but their folded sequence can
        // still be the target of one, so the Trino frontend must keep those alternatives.
        assertThat(CaseFolding.TRINO.requiresFullCaseFold(new int[] {'S', 'S'})).isTrue();
        assertThat(CaseFolding.TRINO.requiresFullCaseFold(new int[] {'f', 'f', 'i'})).isTrue();
        assertThat(CaseFolding.TRINO.requiresFullCaseFold("hello".codePoints().toArray())).isFalse();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)ss")).contains(utf8Slice("\u00DF"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)ffi")).contains(utf8Slice("\uFB03"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)stra\u00DFe")).contains(utf8Slice("STRASSE"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)ss")).contains(utf8Slice("s"))).isFalse();
    }

    @Test
    public void testTokensSelectedByFirstFoldedRune()
    {
        List<UnicodeFullCaseFold.FoldToken> tokens = CaseFolding.TRINO.multiCharacterTokens();
        for (UnicodeFullCaseFold.FoldToken token : tokens) {
            int firstRune = token.foldedRunes()[0];
            assertThat(CaseFolding.TRINO.multiCharacterTokensStartingWith(firstRune))
                    .containsExactlyElementsOf(tokens.stream()
                            .filter(candidate -> candidate.foldedRunes()[0] == firstRune)
                            .toList());
        }
        assertThat(CaseFolding.TRINO.multiCharacterTokensStartingWith('_')).isEmpty();
        assertThat(CaseFolding.TRINO.multiCharacterTokensStartingWith(0x1F600)).isEmpty();
    }

    @Test
    public void testAsciiSimpleFoldClassesAreCachedAndEqualUncached()
    {
        for (int rune = 0; rune < 0x80; rune++) {
            CharClassBuilder expected = new CharClassBuilder();
            int current = rune;
            do {
                expected.addRange(current, current);
                current = CaseFolding.TRINO.cycleFoldRune(current);
            }
            while (current != rune);
            CharClass cached = CaseFolding.TRINO.simpleFoldClass(rune);
            assertThat(cached).as("rune %s", rune).isEqualTo(expected.toCharClass());
            assertThat(CaseFolding.TRINO.simpleFoldClass(rune)).isSameAs(cached);
        }
        assertThat(CaseFolding.TRINO.simpleFoldClass('k').contains(0x212A)).isTrue();
        assertThat(CaseFolding.TRINO.simpleFoldClass(0x212A)).isEqualTo(CaseFolding.TRINO.simpleFoldClass('k'));
    }

    @Test
    public void testClassTokensMatchCompleteScan()
    {
        List<UnicodeFullCaseFold.FoldToken> tokens = CaseFolding.TRINO.multiCharacterTokens();
        List<CharClass> classes = new ArrayList<>();
        classes.add(classOf('0', '9'));
        classes.add(classOf('a', 'z'));
        classes.add(classOf(0, 0x10FFFF));
        classes.add(classOf(0x1F600, 0x1F64F));
        classes.add(classOf(0xDF, 0xDF));
        for (UnicodeFullCaseFold.FoldToken token : tokens) {
            RuneRange range = token.sourceRunes().range(0);
            classes.add(classOf(range.low(), range.low()));
            classes.add(classOf(range.high() + 1, range.high() + 1));
        }
        for (CharClass characterClass : classes) {
            List<UnicodeFullCaseFold.FoldToken> expected = tokens.stream()
                    .filter(token -> intersects(token.sourceRunes(), characterClass))
                    .toList();
            assertThat(CaseFolding.TRINO.multiCharacterTokens(characterClass))
                    .as(characterClass.toString())
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    public void testIndexedLookupsMatchCompleteTokenScan()
    {
        List<UnicodeFullCaseFold.FoldToken> tokens = CaseFolding.TRINO.multiCharacterTokens();
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
        int[] folded = CaseFolding.TRINO.fold(runes);
        assertThat(folded).as("fold %s", Arrays.toString(runes)).isEqualTo(referenceFold(tokens, runes));
        assertThat(CaseFolding.TRINO.requiresFullCaseFold(runes))
                .as("requiresFullCaseFold %s", Arrays.toString(runes))
                .isEqualTo(referenceRequiresFullCaseFold(tokens, folded));
        assertThat(CaseFolding.TRINO.byteLength(runes))
                .as("byteLength %s", Arrays.toString(runes))
                .isEqualTo(referenceByteLength(tokens, folded));
    }

    // Folds each rune by a full token scan; the last token whose sources contain the rune wins.
    private static int[] referenceFold(List<UnicodeFullCaseFold.FoldToken> tokens, int[] runes)
    {
        List<Integer> folded = new ArrayList<>();
        for (int rune : runes) {
            int[] mapping = new int[] {CaseFolding.TRINO.simpleFold(rune)};
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
            CharClass simpleClass = CaseFolding.TRINO.simpleFoldClass(folded[index]);
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

    private static CharClass classOf(int low, int high)
    {
        CharClassBuilder builder = new CharClassBuilder();
        builder.addRange(low, high);
        return builder.toCharClass();
    }

    private static boolean intersects(CharClass left, CharClass right)
    {
        for (int index = 0; index < left.rangeCount(); index++) {
            RuneRange range = left.range(index);
            for (int rune = range.low(); rune <= range.high(); rune++) {
                if (right.contains(rune)) {
                    return true;
                }
            }
        }
        return false;
    }
}
