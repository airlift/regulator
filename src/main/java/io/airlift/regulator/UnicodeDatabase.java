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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.airlift.regulator.CharClass.RUNEMAX;

/// The pinned Unicode Character Database data, read from the resources that
/// `tools/unicode-database/generate.py` writes. The generator documents the format.
///
/// Each resource is read once, on first use, and each section is decoded once, on its own first
/// use, through a holder class. Character names have a separate resource, loaded only by Java's
/// `\N{name}`, so consumers of other Unicode data can omit it.
final class UnicodeDatabase
{
    static final String UNICODE_VERSION = "18.0.0";
    static final String RESOURCE = "unicode/unicode-" + UNICODE_VERSION + ".bin";
    static final String NAMES_RESOURCE = "unicode/unicode-names-" + UNICODE_VERSION + ".bin";

    private static final byte[] MAGIC = {'R', 'G', 'U', 'D'};
    private static final int FORMAT_VERSION = 1;
    private static final int DIGEST_LENGTH = 32;
    private static final int SECTION_SIMPLE_CASE_FOLDING_ORBITS = 1;
    private static final int SECTION_FULL_CASE_FOLDING = 2;
    private static final int SECTION_SIMPLE_UPPERCASE_MAPPINGS = 3;
    private static final int SECTION_SIMPLE_LOWERCASE_MAPPINGS = 4;
    private static final int SECTION_GENERAL_CATEGORY = 5;
    private static final int SECTION_SCRIPT = 6;
    private static final int SECTION_SCRIPT_SHORT_NAMES = 7;
    private static final int SECTION_BLOCK = 8;
    private static final int SECTION_BINARY_PROPERTIES = 9;
    private static final int SECTION_AGE = 10;
    private static final int SECTION_FULL_UPPERCASE_MAPPINGS = 11;
    private static final int SECTION_FULL_LOWERCASE_MAPPINGS = 12;
    private static final int SECTION_NAME = 13;
    private static final int SECTION_CONTROL_NAME = 14;
    private static final int SECTION_SCRIPT_EXTENSIONS = 15;
    private static final int SECTION_GRAPHEME_CLUSTER_BREAK = 16;
    private static final int SECTION_WORD_BREAK = 17;
    private static final int SECTION_SENTENCE_BREAK = 18;
    private static final int SECTION_PROPERTY_ALIASES = 19;
    private static final int SECTION_PROPERTY_VALUE_ALIASES = 20;
    private static final int SECTION_COUNT = 21;
    private static final int MAX_NAME_TOKENS = 15;
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private UnicodeDatabase() {}

    /// Returns the generation key: the SHA-256 that identifies the generator and the input manifest
    /// that wrote the database.
    static byte[] generationKey()
    {
        return Resource.RESOURCE.generationKey.clone();
    }

    /// Returns the simple case folding, CaseFolding.txt statuses C and S: the code points it
    /// changes and the code point each folds to.
    static CodePointMappings simpleCaseFolding()
    {
        return SimpleCaseFolding.MAPPINGS;
    }

    /// Returns the multi-character part of the full case folding, CaseFolding.txt status F: the
    /// code points it changes and the two or more code points each folds to. Every other code
    /// point fully folds as it simply folds.
    static MultiCharacterMappings fullCaseFolding()
    {
        return FullCaseFolding.MAPPINGS;
    }

    /// Returns the simple uppercase mappings, UnicodeData.txt field 12.
    static CodePointMappings simpleUppercaseMappings()
    {
        return SimpleUppercaseMappings.MAPPINGS;
    }

    /// Returns the simple lowercase mappings, UnicodeData.txt field 13.
    static CodePointMappings simpleLowercaseMappings()
    {
        return SimpleLowercaseMappings.MAPPINGS;
    }

    /// Returns the General_Category values of UnicodeData.txt field 2, such as Lu, and their code
    /// points. Unassigned code points, which have the value Cn, are in none.
    static PropertyValues generalCategories()
    {
        return GeneralCategories.VALUES;
    }

    /// Returns the Script values of Scripts.txt, which are long names such as Old_Italic, and their
    /// code points. Code points of the default value, Unknown, are in none.
    static PropertyValues scripts()
    {
        return Scripts.VALUES;
    }

    /// Returns the short name of each script in PropertyValueAliases.txt, such as Ital, and its long
    /// name, such as Old_Italic. It lists scripts that Scripts.txt assigns no code point, such as
    /// Katakana_Or_Hiragana, and the default value, Unknown.
    static Map<String, String> scriptShortNames()
    {
        return ScriptShortNames.NAMES;
    }

    /// Returns the blocks of Blocks.txt, by names such as Latin-1 Supplement, and their code
    /// points. Code points of the default value, No_Block, are in none.
    static PropertyValues blocks()
    {
        return Blocks.VALUES;
    }

    /// Returns binary properties and the code points that have each: Bidi_Mirrored, the properties
    /// of PropList.txt and emoji-data.txt, and the binary properties of DerivedCoreProperties.txt.
    /// Two properties can share code points.
    static PropertyValues binaryProperties()
    {
        return BinaryProperties.VALUES;
    }

    /// Returns the Age values of DerivedAge.txt, versions such as 1.1, and their code points.
    /// Unassigned code points are in none.
    static PropertyValues ages()
    {
        return Ages.VALUES;
    }

    /// Returns the scripts of ScriptExtensions.txt, by short names such as Arab, and the code points
    /// whose list names each. A code point the file does not list has the extension of its Script
    /// value alone. Two scripts can share code points.
    static PropertyValues scriptExtensions()
    {
        return ScriptExtensions.VALUES;
    }

    /// Returns the Grapheme_Cluster_Break values of GraphemeBreakProperty.txt, long names such as
    /// Regional_Indicator, and their code points. Code points of the default value, Other, are in
    /// none.
    static PropertyValues graphemeClusterBreaks()
    {
        return GraphemeClusterBreaks.VALUES;
    }

    /// Returns the Word_Break values of WordBreakProperty.txt, long names such as ALetter, and their
    /// code points. Code points of the default value, Other, are in none.
    static PropertyValues wordBreaks()
    {
        return WordBreaks.VALUES;
    }

    /// Returns the Sentence_Break values of SentenceBreakProperty.txt, long names such as ATerm, and
    /// their code points. Code points of the default value, Other, are in none.
    static PropertyValues sentenceBreaks()
    {
        return SentenceBreaks.VALUES;
    }

    /// Returns the names of each property in PropertyAliases.txt, in ascending order of long name.
    static List<Aliases> propertyAliases()
    {
        return PropertyAliases.ALIASES;
    }

    /// Returns the names of each value in PropertyValueAliases.txt of General_Category, Script,
    /// Age, and the three break properties, keyed by the property's short name, such as gc, and in
    /// ascending order of long name.
    static Map<String, List<Aliases>> propertyValueAliases()
    {
        return PropertyValueAliases.ALIASES;
    }

    /// Returns the unconditional uppercase mappings of SpecialCasing.txt to two or more code
    /// points. Every other code point fully uppercases as it simply uppercases.
    static MultiCharacterMappings fullUppercaseMappings()
    {
        return FullUppercaseMappings.MAPPINGS;
    }

    /// Returns the unconditional lowercase mappings of SpecialCasing.txt to two or more code
    /// points. Every other code point fully lowercases as it simply lowercases, except where a
    /// conditional mapping, such as Final_Sigma, applies.
    static MultiCharacterMappings fullLowercaseMappings()
    {
        return FullLowercaseMappings.MAPPINGS;
    }

    /// Returns the Name of each code point that UnicodeData.txt field 1 names, such as LATIN SMALL
    /// LETTER A. Code points that the field gives a label in angle brackets, such as
    /// `<control>` or a range of CJK ideographs, have none.
    static CharacterNames names()
    {
        return Names.NAMES;
    }

    /// Returns the Unicode_1_Name, UnicodeData.txt field 10, of each control code point that has
    /// one, such as LINE FEED (LF).
    static CharacterNames controlNames()
    {
        return ControlNames.NAMES;
    }

    /// The names of a property or a property value: its short name, such as WSpace, its long name,
    /// such as White_Space, and its other aliases, such as space.
    record Aliases(String shortName, String longName, List<String> otherAliases) {}

    /// Code points in strictly ascending order and the code point each maps to; every other code
    /// point maps to itself. Holders must not modify the arrays.
    record CodePointMappings(int[] codePoints, int[] values)
    {
        /// Returns the code point that `codePoint` maps to, which is itself if it has no
        /// mapping.
        int map(int codePoint)
        {
            int index = Arrays.binarySearch(codePoints, codePoint);
            return index < 0 ? codePoint : values[index];
        }
    }

    /// Code points in strictly ascending order and the two or more code points each maps to.
    /// Holders must not modify the arrays.
    record MultiCharacterMappings(int[] codePoints, int[][] values)
    {
        /// Returns the code points that `codePoint` maps to, or null if it has no mapping.
        /// Holders must not modify the array.
        int[] mapping(int codePoint)
        {
            int index = Arrays.binarySearch(codePoints, codePoint);
            return index < 0 ? null : values[index];
        }
    }

    /// The values of a property and the code points that have each. A value's code points are
    /// ranges, as pairs of first and last code point, in ascending order; ranges neither overlap
    /// nor touch. Holders must not modify the arrays.
    static final class PropertyValues
    {
        private final List<String> names;
        private final Map<String, int[]> ranges;

        private PropertyValues(List<String> names, Map<String, int[]> ranges)
        {
            this.names = names;
            this.ranges = ranges;
        }

        /// Returns the value names in ascending order.
        List<String> names()
        {
            return names;
        }

        /// Returns the ranges of the named value, or null if the property has no such value.
        int[] ranges(String name)
        {
            return ranges.get(name);
        }
    }

    /// Code points and a name of each. A name is words of uppercase letters, digits, hyphens, and
    /// parentheses, separated by single spaces.
    static final class CharacterNames
    {
        private final byte[] words;
        private final int[] wordEnds;
        private final int frequentWordCount;
        private final int[] codePoints;
        private final int[] tokenEnds;
        // Token 0 is a hyphen and the code point in hexadecimal; token n is word n - 1.
        private final char[] tokens;
        // An open-addressing table of name indexes plus one, by the hash of their tokens; 0 marks
        // an empty slot. It has at least half again as many slots as names, so that a lookup
        // probes few of them.
        private final int[] namesByHash;

        private CharacterNames(byte[] words, int[] wordEnds, int frequentWordCount, int[] codePoints, int[] tokenEnds, char[] tokens)
        {
            this.words = words;
            this.wordEnds = wordEnds;
            this.frequentWordCount = frequentWordCount;
            this.codePoints = codePoints;
            this.tokenEnds = tokenEnds;
            this.tokens = tokens;
            namesByHash = new int[Integer.highestOneBit(Math.max(1, codePoints.length * 3 / 2 - 1)) << 1];
            for (int index = 0; index < codePoints.length; index++) {
                int start = index == 0 ? 0 : tokenEnds[index - 1];
                int slot = hash(tokens, start, tokenEnds[index]) & (namesByHash.length - 1);
                while (namesByHash[slot] != 0) {
                    slot = (slot + 1) & (namesByHash.length - 1);
                }
                namesByHash[slot] = index + 1;
            }
        }

        /// Returns the name of `codePoint`, or null if it has none.
        String name(int codePoint)
        {
            int index = Arrays.binarySearch(codePoints, codePoint);
            if (index < 0) {
                return null;
            }
            StringBuilder name = new StringBuilder();
            for (int position = index == 0 ? 0 : tokenEnds[index - 1]; position < tokenEnds[index]; position++) {
                int token = tokens[position];
                if (token == 0) {
                    name.append('-');
                    int digits = Math.max(4, (Integer.SIZE - Integer.numberOfLeadingZeros(codePoint) + 3) / 4);
                    for (int digit = digits - 1; digit >= 0; digit--) {
                        name.append(HEX_DIGITS[(codePoint >>> (digit * 4)) & 0xF]);
                    }
                    continue;
                }
                if (!name.isEmpty()) {
                    name.append(' ');
                }
                for (int offset = wordStart(token - 1); offset < wordEnds[token - 1]; offset++) {
                    name.append((char) words[offset]);
                }
            }
            return name.toString();
        }

        /// Returns the code point whose name is exactly `name`, or -1 if there is none.
        int codePoint(String name)
        {
            // A name that ends in a hyphen and its code point, such as CJK COMPATIBILITY
            // IDEOGRAPH-F900, can hold the code point as a token of its own.
            int hyphen = name.lastIndexOf('-');
            if (hyphen >= 0 && name.length() - hyphen - 1 >= 4 && name.length() - hyphen - 1 <= 6) {
                int codePoint = 0;
                for (int index = hyphen + 1; index < name.length() && codePoint >= 0; index++) {
                    int digit = Arrays.binarySearch(HEX_DIGITS, name.charAt(index));
                    codePoint = digit < 0 ? -1 : codePoint << 4 | digit;
                }
                if (codePoint >= 0 && name.equals(name(codePoint))) {
                    return codePoint;
                }
            }

            char[] expected = new char[MAX_NAME_TOKENS];
            int tokenCount = 0;
            int wordStart = 0;
            while (true) {
                int wordEnd = name.indexOf(' ', wordStart);
                if (wordEnd < 0) {
                    wordEnd = name.length();
                }
                int token = wordToken(name, wordStart, wordEnd);
                if (token < 0 || tokenCount == MAX_NAME_TOKENS) {
                    return -1;
                }
                expected[tokenCount++] = (char) token;
                if (wordEnd == name.length()) {
                    break;
                }
                wordStart = wordEnd + 1;
            }
            for (int slot = hash(expected, 0, tokenCount) & (namesByHash.length - 1); namesByHash[slot] != 0; slot = (slot + 1) & (namesByHash.length - 1)) {
                int index = namesByHash[slot] - 1;
                if (Arrays.equals(tokens, index == 0 ? 0 : tokenEnds[index - 1], tokenEnds[index], expected, 0, tokenCount)) {
                    return codePoints[index];
                }
            }
            return -1;
        }

        private static int hash(char[] tokens, int start, int end)
        {
            int hash = 0;
            for (int index = start; index < end; index++) {
                hash = hash * 31 + tokens[index];
            }
            return hash ^ (hash >>> 16);
        }

        // Returns the token of the word from start to end of name, or -1 if it is no word.
        private int wordToken(String name, int start, int end)
        {
            for (int word = 0; word < frequentWordCount; word++) {
                if (compareWord(word, name, start, end) == 0) {
                    return word + 1;
                }
            }
            int low = frequentWordCount;
            int high = wordEnds.length - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int comparison = compareWord(middle, name, start, end);
                if (comparison == 0) {
                    return middle + 1;
                }
                if (comparison < 0) {
                    low = middle + 1;
                }
                else {
                    high = middle - 1;
                }
            }
            return -1;
        }

        private int compareWord(int word, String name, int start, int end)
        {
            int offset = wordStart(word);
            int wordEnd = wordEnds[word];
            while (offset < wordEnd && start < end) {
                int difference = words[offset++] - name.charAt(start++);
                if (difference != 0) {
                    return difference;
                }
            }
            return (wordEnd - offset) - (end - start);
        }

        private int wordStart(int word)
        {
            return word == 0 ? 0 : wordEnds[word - 1];
        }
    }

    private static CodePointMappings parseSimpleCaseFoldingOrbits(Reader reader)
    {
        int orbitCount = reader.varint();
        long[] folds = new long[0];
        int foldCount = 0;
        int target = 0;
        for (int orbit = 0; orbit < orbitCount; orbit++) {
            int delta = reader.varint();
            if (delta == 0 && orbit > 0) {
                throw reader.corrupt("orbit targets are not ascending");
            }
            target = checkedCodePoint(reader, (long) target + delta);
            int memberCount = reader.varint();
            if (memberCount == 0) {
                throw reader.corrupt("orbit of U+" + Integer.toHexString(target) + " has no other members");
            }
            if ((long) foldCount + memberCount > RUNEMAX) {
                throw reader.corrupt("too many simple case folds");
            }
            if (folds.length < foldCount + memberCount) {
                folds = Arrays.copyOf(folds, Math.max(folds.length * 2, foldCount + memberCount));
            }
            int member = target;
            for (int index = 0; index < memberCount; index++) {
                int previous = member;
                member = checkedCodePoint(reader, (long) member + reader.signedVarint());
                if (index > 0 && member <= previous) {
                    throw reader.corrupt("orbit members are not ascending");
                }
                if (member == target) {
                    throw reader.corrupt("orbit of U+" + Integer.toHexString(target) + " lists its target");
                }
                folds[foldCount++] = ((long) member << 32) | target;
            }
        }
        reader.requireEnd();

        Arrays.sort(folds, 0, foldCount);
        int[] sources = new int[foldCount];
        int[] targets = new int[foldCount];
        for (int index = 0; index < foldCount; index++) {
            sources[index] = (int) (folds[index] >>> 32);
            targets[index] = (int) folds[index];
            if (index > 0 && sources[index] == sources[index - 1]) {
                throw reader.corrupt("U+" + Integer.toHexString(sources[index]) + " is in two orbits");
            }
        }
        for (int value : targets) {
            if (Arrays.binarySearch(sources, value) >= 0) {
                throw reader.corrupt("orbit target U+" + Integer.toHexString(value) + " is a member of another orbit");
            }
        }
        return new CodePointMappings(sources, targets);
    }

    private static MultiCharacterMappings parseMultiCharacterMappings(Reader reader)
    {
        int count = reader.varint();
        if (count > RUNEMAX) {
            throw reader.corrupt("too many multi-character mappings");
        }
        int[] sources = new int[count];
        int[][] values = new int[count][];
        int source = 0;
        for (int index = 0; index < count; index++) {
            source = nextCodePoint(reader, source, index);
            int length = reader.varint();
            if (length < 2 || length > reader.remaining()) {
                throw reader.corrupt("U+" + Integer.toHexString(source) + " maps to " + length + " code points");
            }
            int[] mapping = new int[length];
            int value = source;
            for (int offset = 0; offset < length; offset++) {
                value = checkedCodePoint(reader, (long) value + reader.signedVarint());
                mapping[offset] = value;
            }
            sources[index] = source;
            values[index] = mapping;
        }
        reader.requireEnd();
        return new MultiCharacterMappings(sources, values);
    }

    private static CodePointMappings parseMappings(Reader reader)
    {
        int count = reader.varint();
        if (count > RUNEMAX) {
            throw reader.corrupt("too many mappings");
        }
        int[] sources = new int[count];
        int[] targets = new int[count];
        int source = 0;
        for (int index = 0; index < count; index++) {
            source = nextCodePoint(reader, source, index);
            int target = checkedCodePoint(reader, (long) source + reader.signedVarint());
            if (target == source) {
                throw reader.corrupt("U+" + Integer.toHexString(source) + " maps to itself");
            }
            sources[index] = source;
            targets[index] = target;
        }
        reader.requireEnd();
        return new CodePointMappings(sources, targets);
    }

    private static PropertyValues parsePropertyValues(Reader reader)
    {
        int count = reader.varint();
        if (count > reader.remaining()) {
            throw reader.corrupt("too many property values");
        }
        String[] names = new String[count];
        Map<String, int[]> ranges = new HashMap<>();
        for (int index = 0; index < count; index++) {
            byte[] name = reader.bytes(reader.varint());
            for (byte character : name) {
                if (character < ' ' || character > '~') {
                    throw reader.corrupt("property value name is not printable ASCII");
                }
            }
            names[index] = new String(name, StandardCharsets.US_ASCII);
            if (name.length == 0 || (index > 0 && names[index].compareTo(names[index - 1]) <= 0)) {
                throw reader.corrupt("property value names are empty or not ascending");
            }

            int rangeCount = reader.varint();
            if (rangeCount > reader.remaining()) {
                throw reader.corrupt("too many ranges for " + names[index]);
            }
            int[] valueRanges = new int[rangeCount * 2];
            long next = 0;
            for (int range = 0; range < rangeCount; range++) {
                long gap = reader.varint();
                if (gap == 0 && range > 0) {
                    throw reader.corrupt("ranges of " + names[index] + " touch");
                }
                long low = next + gap;
                long high = low + reader.varint();
                if (high > RUNEMAX) {
                    throw reader.corrupt("invalid code point " + high);
                }
                valueRanges[range * 2] = (int) low;
                valueRanges[range * 2 + 1] = (int) high;
                next = high + 1;
            }
            ranges.put(names[index], valueRanges);
        }
        reader.requireEnd();
        return new PropertyValues(List.of(names), Map.copyOf(ranges));
    }

    private static Map<String, String> parseScriptShortNames(Reader reader)
    {
        int count = reader.varint();
        if (count > reader.remaining()) {
            throw reader.corrupt("too many names");
        }
        Map<String, String> names = new HashMap<>();
        String previous = null;
        for (int index = 0; index < count; index++) {
            String key = asciiName(reader);
            if (previous != null && key.compareTo(previous) <= 0) {
                throw reader.corrupt("names are not ascending");
            }
            previous = key;
            names.put(key, asciiName(reader));
        }
        reader.requireEnd();
        return Map.copyOf(names);
    }

    private static List<Aliases> parsePropertyAliases(Reader reader)
    {
        List<Aliases> aliases = parseAliasList(reader);
        reader.requireEnd();
        return aliases;
    }

    private static Map<String, List<Aliases>> parsePropertyValueAliases(Reader reader)
    {
        int count = reader.varint();
        if (count > reader.remaining()) {
            throw reader.corrupt("too many properties");
        }
        Map<String, List<Aliases>> properties = new HashMap<>();
        String previous = null;
        for (int index = 0; index < count; index++) {
            String property = asciiName(reader);
            if (previous != null && property.compareTo(previous) <= 0) {
                throw reader.corrupt("properties are not ascending");
            }
            previous = property;
            properties.put(property, parseAliasList(reader));
        }
        reader.requireEnd();
        return Map.copyOf(properties);
    }

    private static List<Aliases> parseAliasList(Reader reader)
    {
        int count = reader.varint();
        if (count > reader.remaining()) {
            throw reader.corrupt("too many alias rows");
        }
        Aliases[] rows = new Aliases[count];
        for (int index = 0; index < count; index++) {
            String shortName = asciiName(reader);
            String longName = asciiName(reader);
            if (index > 0 && longName.compareTo(rows[index - 1].longName()) <= 0) {
                throw reader.corrupt("long names are not ascending");
            }
            int aliasCount = reader.varint();
            if (aliasCount > reader.remaining()) {
                throw reader.corrupt("too many aliases of " + longName);
            }
            String[] otherAliases = new String[aliasCount];
            for (int alias = 0; alias < aliasCount; alias++) {
                otherAliases[alias] = asciiName(reader);
            }
            rows[index] = new Aliases(shortName, longName, List.of(otherAliases));
        }
        return List.of(rows);
    }

    private static CharacterNames parseCharacterNames(Reader reader)
    {
        int wordCount = reader.varint();
        int frequentWordCount = reader.varint();
        // A token is a char, and token 0 is the code point suffix.
        if (wordCount > reader.remaining() || wordCount >= 0xFFFF || frequentWordCount > wordCount) {
            throw reader.corrupt("bad word counts");
        }
        byte[] words = new byte[0];
        int[] wordEnds = new int[wordCount];
        int length = 0;
        for (int word = 0; word < wordCount; word++) {
            // The previous word runs from start to length.
            int start = word < 2 ? 0 : wordEnds[word - 2];
            int shared = reader.varint();
            byte[] rest = reader.bytes(reader.varint());
            if (shared > length - start || shared + rest.length == 0) {
                throw reader.corrupt("bad word " + word);
            }
            if (words.length < length + shared + rest.length) {
                words = Arrays.copyOf(words, Math.max(words.length * 2, length + shared + rest.length));
            }
            System.arraycopy(words, start, words, length, shared);
            for (byte character : rest) {
                if (!((character >= 'A' && character <= 'Z') || (character >= '0' && character <= '9') || character == '-' || character == '(' || character == ')')) {
                    throw reader.corrupt("word " + word + " has a character other than an uppercase letter, digit, hyphen, or parenthesis");
                }
            }
            System.arraycopy(rest, 0, words, length + shared, rest.length);
            wordEnds[word] = length + shared + rest.length;
            if (word > frequentWordCount && Arrays.compare(words, start, length, words, length, wordEnds[word]) >= 0) {
                throw reader.corrupt("words are not ascending");
            }
            length = wordEnds[word];
        }

        int nameCount = reader.varint();
        if (nameCount > reader.remaining()) {
            throw reader.corrupt("too many names");
        }
        int[] codePoints = new int[nameCount];
        int[] tokenEnds = new int[nameCount];
        char[] tokens = new char[0];
        int tokenCount = 0;
        int codePoint = 0;
        for (int index = 0; index < nameCount; index++) {
            codePoint = nextCodePoint(reader, codePoint, index);
            // The tokens of the previous name run from start to tokenCount.
            int start = index < 2 ? 0 : tokenEnds[index - 2];
            int counts = reader.varint();
            int kept = counts >>> 4;
            int added = counts & 0xF;
            if (kept > tokenCount - start || kept + added == 0 || kept + added > MAX_NAME_TOKENS) {
                throw reader.corrupt("bad token counts of U+" + Integer.toHexString(codePoint));
            }
            if (tokens.length < tokenCount + kept + added) {
                tokens = Arrays.copyOf(tokens, Math.max(tokens.length * 2, tokenCount + kept + added));
            }
            System.arraycopy(tokens, start, tokens, tokenCount, kept);
            for (int offset = kept; offset < kept + added; offset++) {
                int token = reader.varint();
                if (token > wordCount || (token == 0 && offset == 0)) {
                    throw reader.corrupt("bad token in the name of U+" + Integer.toHexString(codePoint));
                }
                tokens[tokenCount + offset] = (char) token;
            }
            codePoints[index] = codePoint;
            tokenCount += kept + added;
            tokenEnds[index] = tokenCount;
        }
        reader.requireEnd();
        return new CharacterNames(Arrays.copyOf(words, length), wordEnds, frequentWordCount, codePoints, tokenEnds, Arrays.copyOf(tokens, tokenCount));
    }

    private static String asciiName(Reader reader)
    {
        byte[] name = reader.bytes(reader.varint());
        if (name.length == 0) {
            throw reader.corrupt("empty name");
        }
        for (byte character : name) {
            if (character <= ' ' || character > '~') {
                throw reader.corrupt("name is not printable ASCII other than space");
            }
        }
        return new String(name, StandardCharsets.US_ASCII);
    }

    // Reads the distance to the next mapped code point, which must exceed the previous one.
    private static int nextCodePoint(Reader reader, int previous, int index)
    {
        int delta = reader.varint();
        if (delta == 0 && index > 0) {
            throw reader.corrupt("mapped code points are not ascending");
        }
        return checkedCodePoint(reader, (long) previous + delta);
    }

    private static int checkedCodePoint(Reader reader, long value)
    {
        if (value < 0 || value > RUNEMAX || (value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE)) {
            throw reader.corrupt("invalid code point " + value);
        }
        return (int) value;
    }

    /// The header of the resource and the payload of each known section.
    private static final class Resource
    {
        private static final Resource RESOURCE = load(UnicodeDatabase.RESOURCE);

        private final byte[] generationKey;
        // The payload of each known section, indexed by section ID; null marks a missing section.
        private final byte[][] sections = new byte[SECTION_COUNT][];

        private Resource(byte[] data)
        {
            Reader reader = new Reader("resource", data);
            if (!Arrays.equals(reader.bytes(MAGIC.length), MAGIC)) {
                throw reader.corrupt("bad magic");
            }
            int formatVersion = reader.varint();
            if (formatVersion != FORMAT_VERSION) {
                throw reader.corrupt("unsupported format version " + formatVersion);
            }
            String version = new String(reader.bytes(reader.varint()), StandardCharsets.US_ASCII);
            if (!version.equals(UNICODE_VERSION)) {
                throw reader.corrupt("Unicode version " + version + ", not " + UNICODE_VERSION);
            }
            generationKey = reader.bytes(DIGEST_LENGTH);
            // The input digest records the UCD files the database came from; reading does not need it.
            reader.skip(DIGEST_LENGTH);

            int previousSectionId = 0;
            while (!reader.atEnd()) {
                int sectionId = reader.varint();
                if (sectionId <= previousSectionId) {
                    throw reader.corrupt("section " + sectionId + " is out of order");
                }
                previousSectionId = sectionId;
                int length = reader.varint();
                // A section ID this reader does not know is skipped.
                if (sectionId < SECTION_COUNT) {
                    sections[sectionId] = reader.bytes(length);
                }
                else {
                    reader.skip(length);
                }
            }
        }

        private Reader section(int sectionId)
        {
            byte[] section = sections[sectionId];
            if (section == null) {
                throw new IllegalStateException("corrupt Unicode database: missing section " + sectionId);
            }
            return new Reader("section " + sectionId, section);
        }

        private static Resource load(String resource)
        {
            try (InputStream input = UnicodeDatabase.class.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("missing Unicode database resource: " + resource);
                }
                return new Resource(input.readAllBytes());
            }
            catch (IOException e) {
                throw new UncheckedIOException("failed to read Unicode database resource: " + resource, e);
            }
        }
    }

    private static final class Reader
    {
        private final String part;
        private final byte[] data;
        private int position;

        private Reader(String part, byte[] data)
        {
            this.part = part;
            this.data = data;
        }

        private boolean atEnd()
        {
            return position == data.length;
        }

        private int remaining()
        {
            return data.length - position;
        }

        private void requireEnd()
        {
            if (!atEnd()) {
                throw corrupt("trailing bytes in section");
            }
        }

        private byte[] bytes(int length)
        {
            requireAvailable(length);
            byte[] bytes = Arrays.copyOfRange(data, position, position + length);
            position += length;
            return bytes;
        }

        private void skip(int length)
        {
            requireAvailable(length);
            position += length;
        }

        private int varint()
        {
            int value = 0;
            for (int shift = 0; shift < 32; shift += 7) {
                requireAvailable(1);
                int next = data[position++] & 0xFF;
                if (shift == 28 && (next & 0xF8) != 0) {
                    throw corrupt("varint overflow");
                }
                value |= (next & 0x7F) << shift;
                if ((next & 0x80) == 0) {
                    return value;
                }
            }
            throw corrupt("varint overflow");
        }

        private int signedVarint()
        {
            int value = varint();
            return (value >>> 1) ^ -(value & 1);
        }

        private void requireAvailable(int length)
        {
            if (length < 0 || length > data.length - position) {
                throw corrupt("truncated");
            }
        }

        private IllegalStateException corrupt(String message)
        {
            return new IllegalStateException("corrupt Unicode database " + part + " at byte " + position + ": " + message);
        }
    }

    private static final class SimpleCaseFolding
    {
        private static final CodePointMappings MAPPINGS = parseSimpleCaseFoldingOrbits(Resource.RESOURCE.section(SECTION_SIMPLE_CASE_FOLDING_ORBITS));
    }

    private static final class FullCaseFolding
    {
        private static final MultiCharacterMappings MAPPINGS = parseMultiCharacterMappings(Resource.RESOURCE.section(SECTION_FULL_CASE_FOLDING));
    }

    private static final class SimpleUppercaseMappings
    {
        private static final CodePointMappings MAPPINGS = parseMappings(Resource.RESOURCE.section(SECTION_SIMPLE_UPPERCASE_MAPPINGS));
    }

    private static final class SimpleLowercaseMappings
    {
        private static final CodePointMappings MAPPINGS = parseMappings(Resource.RESOURCE.section(SECTION_SIMPLE_LOWERCASE_MAPPINGS));
    }

    private static final class GeneralCategories
    {
        private static final PropertyValues VALUES = parsePropertyValues(Resource.RESOURCE.section(SECTION_GENERAL_CATEGORY));
    }

    private static final class Scripts
    {
        private static final PropertyValues VALUES = parsePropertyValues(Resource.RESOURCE.section(SECTION_SCRIPT));
    }

    private static final class ScriptShortNames
    {
        private static final Map<String, String> NAMES = parseScriptShortNames(Resource.RESOURCE.section(SECTION_SCRIPT_SHORT_NAMES));
    }

    private static final class Blocks
    {
        private static final PropertyValues VALUES = parsePropertyValues(Resource.RESOURCE.section(SECTION_BLOCK));
    }

    private static final class BinaryProperties
    {
        private static final PropertyValues VALUES = parsePropertyValues(Resource.RESOURCE.section(SECTION_BINARY_PROPERTIES));
    }

    private static final class Ages
    {
        private static final PropertyValues VALUES = parsePropertyValues(Resource.RESOURCE.section(SECTION_AGE));
    }

    private static final class ScriptExtensions
    {
        private static final PropertyValues VALUES = parsePropertyValues(Resource.RESOURCE.section(SECTION_SCRIPT_EXTENSIONS));
    }

    private static final class GraphemeClusterBreaks
    {
        private static final PropertyValues VALUES = parsePropertyValues(Resource.RESOURCE.section(SECTION_GRAPHEME_CLUSTER_BREAK));
    }

    private static final class WordBreaks
    {
        private static final PropertyValues VALUES = parsePropertyValues(Resource.RESOURCE.section(SECTION_WORD_BREAK));
    }

    private static final class SentenceBreaks
    {
        private static final PropertyValues VALUES = parsePropertyValues(Resource.RESOURCE.section(SECTION_SENTENCE_BREAK));
    }

    private static final class PropertyAliases
    {
        private static final List<Aliases> ALIASES = parsePropertyAliases(Resource.RESOURCE.section(SECTION_PROPERTY_ALIASES));
    }

    private static final class PropertyValueAliases
    {
        private static final Map<String, List<Aliases>> ALIASES = parsePropertyValueAliases(Resource.RESOURCE.section(SECTION_PROPERTY_VALUE_ALIASES));
    }

    private static final class FullUppercaseMappings
    {
        private static final MultiCharacterMappings MAPPINGS = parseMultiCharacterMappings(Resource.RESOURCE.section(SECTION_FULL_UPPERCASE_MAPPINGS));
    }

    private static final class FullLowercaseMappings
    {
        private static final MultiCharacterMappings MAPPINGS = parseMultiCharacterMappings(Resource.RESOURCE.section(SECTION_FULL_LOWERCASE_MAPPINGS));
    }

    private static final class Names
    {
        private static final CharacterNames NAMES = parseCharacterNames(Resource.load(NAMES_RESOURCE).section(SECTION_NAME));
    }

    private static final class ControlNames
    {
        private static final CharacterNames NAMES = parseCharacterNames(Resource.RESOURCE.section(SECTION_CONTROL_NAME));
    }
}
