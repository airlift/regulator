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
import static java.util.Objects.requireNonNull;

/**
 * Full case-fold mappings that join the simple folding of one {@link CaseFolding} with the JVM's
 * multi-character {@code String} case mappings. Only a rule with multi-character folds, the Trino
 * rule, builds them.
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

    private final CaseFolding caseFolding;
    private final Data data;
    // ASCII literals dominate Trino (?i) patterns, and the compiler asks for their classes once
    // per rune per literal.
    private final CharClass[] asciiSimpleFoldClasses;

    private UnicodeFullCaseFold(CaseFolding caseFolding, Map<Integer, int[]> mappingByRune, List<FoldToken> multiCharacterTokens)
    {
        this.caseFolding = requireNonNull(caseFolding, "caseFolding is null");
        this.data = new Data(mappingByRune, multiCharacterTokens);
        asciiSimpleFoldClasses = new CharClass[0x80];
        for (int rune = 0; rune < asciiSimpleFoldClasses.length; rune++) {
            asciiSimpleFoldClasses[rune] = buildSimpleFoldClass(rune);
        }
    }

    int[] fold(int[] runes)
    {
        int[] folded = new int[Math.max(4, runes.length)];
        int size = 0;
        for (int rune : runes) {
            int[] mapping = data.mapping(rune);
            int length = mapping == null ? 1 : mapping.length;
            if (size + length > folded.length) {
                folded = Arrays.copyOf(folded, Math.max(folded.length * 2, size + length));
            }
            if (mapping == null) {
                folded[size] = caseFolding.simpleFold(rune);
            }
            else {
                System.arraycopy(mapping, 0, folded, size, length);
            }
            size += length;
        }
        return size == folded.length ? folded : Arrays.copyOf(folded, size);
    }

    CharClass simpleFoldClass(int rune)
    {
        if (rune >= 0 && rune < asciiSimpleFoldClasses.length) {
            return asciiSimpleFoldClasses[rune];
        }
        return buildSimpleFoldClass(rune);
    }

    private CharClass buildSimpleFoldClass(int rune)
    {
        CharClassBuilder builder = new CharClassBuilder();
        int current = rune;
        do {
            if (current < Character.MIN_SURROGATE || current > Character.MAX_SURROGATE) {
                builder.addRange(current, current);
            }
            current = caseFolding.cycleFoldRune(current);
        }
        while (current != rune);
        return builder.toCharClass();
    }

    List<FoldToken> multiCharacterTokens()
    {
        return data.multiCharacterTokens;
    }

    /**
     * Returns the tokens whose folded sequence begins with {@code foldedRune}, in the order of
     * {@link #multiCharacterTokens()}.
     */
    List<FoldToken> multiCharacterTokensStartingWith(int foldedRune)
    {
        int start = data.firstTokenIndex(foldedRune);
        int end = data.tokenEndIndex(foldedRune, start);
        return data.multiCharacterTokens.subList(start, end);
    }

    boolean requiresFullCaseFold(int[] runes)
    {
        int[] folded = fold(runes);
        for (int index = 0; index < folded.length; index++) {
            for (FoldToken token : multiCharacterTokensStartingWith(folded[index])) {
                if (token.matches(folded, index)) {
                    return true;
                }
            }
        }
        return false;
    }

    List<FoldToken> multiCharacterTokens(CharClass characterClass)
    {
        // Most classes contain no rune with a multi-character fold, so one intersection test
        // against the union of all token sources answers for every token.
        if (!intersects(characterClass, data.allSourceRunes)) {
            return List.of();
        }
        List<FoldToken> result = new ArrayList<>();
        for (FoldToken token : data.multiCharacterTokens) {
            if (intersects(characterClass, token.sourceRunes())) {
                result.add(token);
            }
        }
        return List.copyOf(result);
    }

    ByteLength byteLength(int[] runes)
    {
        int[] folded = fold(runes);
        int[] minimum = new int[folded.length + 1];
        int[] maximum = new int[folded.length + 1];
        for (int index = folded.length - 1; index >= 0; index--) {
            int rune = folded[index];
            // Equivalent to the UTF-8 length bounds of simpleFoldClass(rune), without building the class.
            int simpleMinimum = Integer.MAX_VALUE;
            int simpleMaximum = 0;
            int current = rune;
            do {
                if (current < Character.MIN_SURROGATE || current > Character.MAX_SURROGATE) {
                    int length = utf8Length(current);
                    simpleMinimum = Math.min(simpleMinimum, length);
                    simpleMaximum = Math.max(simpleMaximum, length);
                }
                current = caseFolding.cycleFoldRune(current);
            }
            while (current != rune);
            minimum[index] = simpleMinimum + minimum[index + 1];
            maximum[index] = simpleMaximum + maximum[index + 1];

            int start = data.firstTokenIndex(rune);
            int end = data.tokenEndIndex(rune, start);
            for (int tokenIndex = start; tokenIndex < end; tokenIndex++) {
                FoldToken token = data.tokens[tokenIndex];
                if (!token.matches(folded, index)) {
                    continue;
                }
                int next = index + token.foldedRunes().length;
                minimum[index] = Math.min(minimum[index], data.tokenMinimumLengths[tokenIndex] + minimum[next]);
                maximum[index] = Math.max(maximum[index], data.tokenMaximumLengths[tokenIndex] + maximum[next]);
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

    /**
     * Returns the full case folding that joins the simple folding of {@code caseFolding} with the
     * JVM's multi-character {@code String} case mappings.
     */
    static UnicodeFullCaseFold withJvmMultiCharacterFolds(CaseFolding caseFolding)
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
                directMappings.put(codePoint, canonicalize(caseFolding, mapping));
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
        return new UnicodeFullCaseFold(caseFolding, mappingByRune, tokens);
    }

    private static int[] canonicalize(CaseFolding caseFolding, int[] runes)
    {
        int[] canonical = runes.clone();
        for (int index = 0; index < canonical.length; index++) {
            canonical[index] = caseFolding.simpleFold(canonical[index]);
        }
        return canonical;
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

    /**
     * Mappings and tokens with primitive lookup tables. Mapped runes are sorted for binary search.
     * Tokens are sorted by folded sequence, so tokens sharing a first rune are contiguous.
     */
    private static final class Data
    {
        private final List<FoldToken> multiCharacterTokens;
        private final int[] mappedRunes;
        private final int[][] mappings;
        private final FoldToken[] tokens;
        private final int[] tokenFirstRunes;
        private final int[] tokenMinimumLengths;
        private final int[] tokenMaximumLengths;
        private final CharClass allSourceRunes;

        private Data(Map<Integer, int[]> mappingByRune, List<FoldToken> multiCharacterTokens)
        {
            this.multiCharacterTokens = multiCharacterTokens;
            mappedRunes = mappingByRune.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
            mappings = new int[mappedRunes.length][];
            for (int index = 0; index < mappedRunes.length; index++) {
                mappings[index] = mappingByRune.get(mappedRunes[index]);
            }
            tokens = multiCharacterTokens.toArray(FoldToken[]::new);
            tokenFirstRunes = new int[tokens.length];
            tokenMinimumLengths = new int[tokens.length];
            tokenMaximumLengths = new int[tokens.length];
            for (int index = 0; index < tokens.length; index++) {
                tokenFirstRunes[index] = tokens[index].foldedRunes()[0];
                tokenMinimumLengths[index] = minimumUtf8Length(tokens[index].sourceRunes());
                tokenMaximumLengths[index] = maximumUtf8Length(tokens[index].sourceRunes());
            }
            // Each mapped rune is a source rune of exactly one token.
            CharClassBuilder sources = new CharClassBuilder();
            for (int rune : mappedRunes) {
                sources.addRange(rune, rune);
            }
            allSourceRunes = sources.toCharClass();
        }

        private int[] mapping(int rune)
        {
            if (rune < mappedRunes[0]) {
                return null;
            }
            int index = Arrays.binarySearch(mappedRunes, rune);
            return index < 0 ? null : mappings[index];
        }

        private int firstTokenIndex(int foldedRune)
        {
            int low = 0;
            int high = tokenFirstRunes.length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (tokenFirstRunes[middle] < foldedRune) {
                    low = middle + 1;
                }
                else {
                    high = middle;
                }
            }
            return low;
        }

        private int tokenEndIndex(int foldedRune, int start)
        {
            int end = start;
            while (end < tokenFirstRunes.length && tokenFirstRunes[end] == foldedRune) {
                end++;
            }
            return end;
        }
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
