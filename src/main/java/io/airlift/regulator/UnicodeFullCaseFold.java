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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.airlift.regulator.CharClass.RUNEMAX;

/**
 * JVM-derived full case-fold mappings used by the Trino language frontend.
 */
final class UnicodeFullCaseFold
{
    record FoldToken(int[] foldedRunes, CharClass sourceRunes)
    {
        FoldToken
        {
            foldedRunes = foldedRunes.clone();
        }

        boolean matches(int[] runes, int index)
        {
            if (index + foldedRunes.length > runes.length) {
                return false;
            }
            for (int offset = 0; offset < foldedRunes.length; offset++) {
                if (runes[index + offset] != foldedRunes[offset]) {
                    return false;
                }
            }
            return true;
        }
    }

    record ByteLength(int minimum, int maximum) {}

    private UnicodeFullCaseFold() {}

    static int[] fold(int[] runes)
    {
        int[] folded = new int[Math.max(4, runes.length)];
        int size = 0;
        for (int rune : runes) {
            int[] mapping = DataHolder.DATA.mappingByRune().get(rune);
            if (mapping == null) {
                mapping = new int[] {canonicalSimpleFold(rune)};
            }
            if (size + mapping.length > folded.length) {
                folded = Arrays.copyOf(folded, Math.max(folded.length * 2, size + mapping.length));
            }
            System.arraycopy(mapping, 0, folded, size, mapping.length);
            size += mapping.length;
        }
        return Arrays.copyOf(folded, size);
    }

    static CharClass simpleFoldClass(int rune)
    {
        CharClassBuilder builder = new CharClassBuilder();
        int current = rune;
        do {
            if (current < Character.MIN_SURROGATE || current > Character.MAX_SURROGATE) {
                builder.addRange(current, current);
            }
            current = UnicodeCaseFold.cycleFoldRune(current);
        }
        while (current != rune);
        return builder.toCharClass();
    }

    static List<FoldToken> multiCharacterTokens()
    {
        return DataHolder.DATA.multiCharacterTokens();
    }

    static boolean requiresFullCaseFold(int[] runes)
    {
        int[] folded = fold(runes);
        for (int index = 0; index < folded.length; index++) {
            for (FoldToken token : DataHolder.DATA.multiCharacterTokens()) {
                if (token.matches(folded, index)) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<FoldToken> multiCharacterTokens(CharClass characterClass)
    {
        List<FoldToken> result = new ArrayList<>();
        for (FoldToken token : DataHolder.DATA.multiCharacterTokens()) {
            if (intersects(characterClass, token.sourceRunes())) {
                result.add(token);
            }
        }
        return result;
    }

    static ByteLength byteLength(int[] runes)
    {
        int[] folded = fold(runes);
        int[] minimum = new int[folded.length + 1];
        int[] maximum = new int[folded.length + 1];
        for (int index = folded.length - 1; index >= 0; index--) {
            CharClass simpleClass = simpleFoldClass(folded[index]);
            minimum[index] = minimumUtf8Length(simpleClass) + minimum[index + 1];
            maximum[index] = maximumUtf8Length(simpleClass) + maximum[index + 1];
            for (FoldToken token : DataHolder.DATA.multiCharacterTokens()) {
                if (!token.matches(folded, index)) {
                    continue;
                }
                int next = index + token.foldedRunes().length;
                minimum[index] = Math.min(minimum[index], minimumUtf8Length(token.sourceRunes()) + minimum[next]);
                maximum[index] = Math.max(maximum[index], maximumUtf8Length(token.sourceRunes()) + maximum[next]);
            }
        }
        return new ByteLength(minimum[0], maximum[0]);
    }

    private static boolean intersects(CharClass left, CharClass right)
    {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.rangeCount() && rightIndex < right.rangeCount()) {
            RuneRange leftRange = left.range(leftIndex);
            RuneRange rightRange = right.range(rightIndex);
            if (leftRange.high() < rightRange.low()) {
                leftIndex++;
            }
            else if (rightRange.high() < leftRange.low()) {
                rightIndex++;
            }
            else {
                return true;
            }
        }
        return false;
    }

    private static int minimumUtf8Length(CharClass characterClass)
    {
        int minimum = Integer.MAX_VALUE;
        for (RuneRange range : characterClass.ranges()) {
            minimum = Math.min(minimum, utf8Length(range.low()));
        }
        return minimum;
    }

    private static int maximumUtf8Length(CharClass characterClass)
    {
        int maximum = 0;
        for (RuneRange range : characterClass.ranges()) {
            maximum = Math.max(maximum, utf8Length(range.high()));
        }
        return maximum;
    }

    private static int utf8Length(int rune)
    {
        return Utf8.encodedLength(rune);
    }

    private static Data build()
    {
        Map<Integer, int[]> directMappings = new HashMap<>();
        for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            String value = new String(Character.toChars(codePoint));
            int[] uppercase = value.toUpperCase(Locale.ROOT).codePoints().toArray();
            int[] lowercase = value.toLowerCase(Locale.ROOT).codePoints().toArray();
            int[] mapping = uppercase.length > 1 ? uppercase : (lowercase.length > 1 ? lowercase : null);
            if (mapping != null) {
                directMappings.put(codePoint, canonicalize(mapping));
            }
        }

        Map<Integer, int[]> mappingByRune = new HashMap<>(directMappings);
        for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
            if (mappingByRune.containsKey(codePoint)) {
                continue;
            }
            int[] mapping = directMappings.get(Character.toLowerCase(codePoint));
            if (mapping == null) {
                mapping = directMappings.get(Character.toUpperCase(codePoint));
            }
            if (mapping != null) {
                mappingByRune.put(codePoint, mapping);
            }
        }

        Map<IntSequence, CharClassBuilder> sourcesByMapping = new HashMap<>();
        for (Map.Entry<Integer, int[]> entry : mappingByRune.entrySet()) {
            sourcesByMapping.computeIfAbsent(new IntSequence(entry.getValue()), _ -> new CharClassBuilder())
                    .addRange(entry.getKey(), entry.getKey());
        }

        List<FoldToken> tokens = sourcesByMapping.entrySet().stream()
                .map(entry -> new FoldToken(entry.getKey().runes(), entry.getValue().toCharClass()))
                .sorted((left, right) -> compare(left.foldedRunes(), right.foldedRunes()))
                .toList();
        return new Data(Map.copyOf(mappingByRune), tokens);
    }

    private static int[] canonicalize(int[] runes)
    {
        int[] canonical = runes.clone();
        for (int index = 0; index < canonical.length; index++) {
            canonical[index] = canonicalSimpleFold(canonical[index]);
        }
        return canonical;
    }

    private static int canonicalSimpleFold(int rune)
    {
        return Character.toLowerCase(Character.toUpperCase(rune));
    }

    private static int compare(int[] left, int[] right)
    {
        int length = Math.min(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(left[index], right[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private record Data(Map<Integer, int[]> mappingByRune, List<FoldToken> multiCharacterTokens) {}

    private static final class DataHolder
    {
        private static final Data DATA = build();
    }

    private record IntSequence(int[] runes)
    {
        private IntSequence
        {
            runes = runes.clone();
        }

        @Override
        public boolean equals(Object object)
        {
            return object instanceof IntSequence other && Arrays.equals(runes, other.runes);
        }

        @Override
        public int hashCode()
        {
            return Arrays.hashCode(runes);
        }
    }
}
