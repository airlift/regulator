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

/**
 * The pinned Unicode Character Database data, read from the resource that
 * {@code tools/unicode-database/generate.py} writes. The generator documents the format.
 * <p>
 * The resource is read once, on first use, and each section is decoded once, on its own first
 * use, through a holder class, so a process that never folds case with a rule, or never uses a
 * property, decodes none of the data that only it needs.
 */
final class UnicodeDatabase
{
    static final String UNICODE_VERSION = "18.0.0";
    static final String RESOURCE = "unicode/unicode-" + UNICODE_VERSION + ".bin";

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
    private static final int SECTION_COUNT = 11;

    private UnicodeDatabase() {}

    /**
     * Returns the generation key: the SHA-256 that identifies the generator and the input manifest
     * that wrote the database.
     */
    static byte[] generationKey()
    {
        return Resource.RESOURCE.generationKey.clone();
    }

    /**
     * Returns the simple case folding, CaseFolding.txt statuses C and S: the code points it
     * changes and the code point each folds to.
     */
    static CodePointMappings simpleCaseFolding()
    {
        return SimpleCaseFolding.MAPPINGS;
    }

    /**
     * Returns the multi-character part of the full case folding, CaseFolding.txt status F: the
     * code points it changes and the two or more code points each folds to. Every other code
     * point fully folds as it simply folds.
     */
    static MultiCharacterMappings fullCaseFolding()
    {
        return FullCaseFolding.MAPPINGS;
    }

    /**
     * Returns the simple uppercase mappings, UnicodeData.txt field 12.
     */
    static CodePointMappings simpleUppercaseMappings()
    {
        return SimpleUppercaseMappings.MAPPINGS;
    }

    /**
     * Returns the simple lowercase mappings, UnicodeData.txt field 13.
     */
    static CodePointMappings simpleLowercaseMappings()
    {
        return SimpleLowercaseMappings.MAPPINGS;
    }

    /**
     * Returns the General_Category values of UnicodeData.txt field 2, such as Lu, and their code
     * points. Unassigned code points, which have the value Cn, are in none.
     */
    static PropertyValues generalCategories()
    {
        return GeneralCategories.VALUES;
    }

    /**
     * Returns the Script values of Scripts.txt, which are long names such as Old_Italic, and their
     * code points. Code points of the default value, Unknown, are in none.
     */
    static PropertyValues scripts()
    {
        return Scripts.VALUES;
    }

    /**
     * Returns the short name of each script in PropertyValueAliases.txt, such as Ital, and its long
     * name, such as Old_Italic. It lists scripts that Scripts.txt assigns no code point, such as
     * Katakana_Or_Hiragana, and the default value, Unknown.
     */
    static Map<String, String> scriptShortNames()
    {
        return ScriptShortNames.NAMES;
    }

    /**
     * Returns the blocks of Blocks.txt, by names such as Latin-1 Supplement, and their code
     * points. Code points of the default value, No_Block, are in none.
     */
    static PropertyValues blocks()
    {
        return Blocks.VALUES;
    }

    /**
     * Returns binary properties and the code points that have each: Bidi_Mirrored, the properties
     * of emoji-data.txt, and Hex_Digit, Ideographic, Join_Control, Noncharacter_Code_Point,
     * Other_Alphabetic, Other_ID_Continue, Other_ID_Start, Other_Lowercase, and Other_Uppercase of
     * PropList.txt. Two properties can share code points.
     */
    static PropertyValues binaryProperties()
    {
        return BinaryProperties.VALUES;
    }

    /**
     * Returns the Age values of DerivedAge.txt, versions such as 1.1, and their code points.
     * Unassigned code points are in none.
     */
    static PropertyValues ages()
    {
        return Ages.VALUES;
    }

    /**
     * Code points in strictly ascending order and the code point each maps to; every other code
     * point maps to itself. Holders must not modify the arrays.
     */
    record CodePointMappings(int[] codePoints, int[] values)
    {
        /**
         * Returns the code point that {@code codePoint} maps to, which is itself if it has no
         * mapping.
         */
        int map(int codePoint)
        {
            int index = Arrays.binarySearch(codePoints, codePoint);
            return index < 0 ? codePoint : values[index];
        }
    }

    /**
     * Code points in strictly ascending order and the two or more code points each maps to.
     * Holders must not modify the arrays.
     */
    record MultiCharacterMappings(int[] codePoints, int[][] values)
    {
        /**
         * Returns the code points that {@code codePoint} maps to, or null if it has no mapping.
         * Holders must not modify the array.
         */
        int[] mapping(int codePoint)
        {
            int index = Arrays.binarySearch(codePoints, codePoint);
            return index < 0 ? null : values[index];
        }
    }

    /**
     * The values of a property and the code points that have each. A value's code points are
     * ranges, as pairs of first and last code point, in ascending order; ranges neither overlap
     * nor touch. Holders must not modify the arrays.
     */
    static final class PropertyValues
    {
        private final List<String> names;
        private final Map<String, int[]> ranges;

        private PropertyValues(List<String> names, Map<String, int[]> ranges)
        {
            this.names = names;
            this.ranges = ranges;
        }

        /**
         * Returns the value names in ascending order.
         */
        List<String> names()
        {
            return names;
        }

        /**
         * Returns the ranges of the named value, or null if the property has no such value.
         */
        int[] ranges(String name)
        {
            return ranges.get(name);
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

    private static MultiCharacterMappings parseFullCaseFolding(Reader reader)
    {
        int count = reader.varint();
        if (count > RUNEMAX) {
            throw reader.corrupt("too many full case folds");
        }
        int[] sources = new int[count];
        int[][] values = new int[count][];
        int source = 0;
        for (int index = 0; index < count; index++) {
            source = nextCodePoint(reader, source, index);
            int length = reader.varint();
            if (length < 2 || length > reader.remaining()) {
                throw reader.corrupt("U+" + Integer.toHexString(source) + " fully folds to " + length + " code points");
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
            String key = scriptName(reader);
            if (previous != null && key.compareTo(previous) <= 0) {
                throw reader.corrupt("names are not ascending");
            }
            previous = key;
            names.put(key, scriptName(reader));
        }
        reader.requireEnd();
        return Map.copyOf(names);
    }

    private static String scriptName(Reader reader)
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

    /**
     * The resource bytes, the header, and the location of each section.
     */
    private static final class Resource
    {
        private static final Resource RESOURCE = load();

        private final byte[] data;
        private final byte[] generationKey;
        // The payload start and end of each known section, indexed by section ID; an end of 0
        // marks a missing section.
        private final int[] sectionStarts = new int[SECTION_COUNT];
        private final int[] sectionEnds = new int[SECTION_COUNT];

        private Resource(byte[] data)
        {
            this.data = data;
            Reader reader = new Reader(data, 0, data.length);
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
                int start = reader.position;
                reader.skip(length);
                // A section ID this reader does not know is skipped.
                if (sectionId < SECTION_COUNT) {
                    sectionStarts[sectionId] = start;
                    sectionEnds[sectionId] = start + length;
                }
            }
        }

        private Reader section(int sectionId)
        {
            if (sectionEnds[sectionId] == 0) {
                throw new IllegalStateException("corrupt Unicode database: missing section " + sectionId);
            }
            return new Reader(data, sectionStarts[sectionId], sectionEnds[sectionId]);
        }

        private static Resource load()
        {
            try (InputStream input = UnicodeDatabase.class.getResourceAsStream(UnicodeDatabase.RESOURCE)) {
                if (input == null) {
                    throw new IllegalStateException("missing Unicode database resource: " + UnicodeDatabase.RESOURCE);
                }
                return new Resource(input.readAllBytes());
            }
            catch (IOException e) {
                throw new UncheckedIOException("failed to read Unicode database resource: " + UnicodeDatabase.RESOURCE, e);
            }
        }
    }

    private static final class Reader
    {
        private final byte[] data;
        private final int end;
        private int position;

        private Reader(byte[] data, int position, int end)
        {
            this.data = data;
            this.position = position;
            this.end = end;
        }

        private boolean atEnd()
        {
            return position == end;
        }

        private int remaining()
        {
            return end - position;
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
            if (length < 0 || length > end - position) {
                throw corrupt("truncated");
            }
        }

        private IllegalStateException corrupt(String message)
        {
            return new IllegalStateException("corrupt Unicode database at byte " + position + ": " + message);
        }
    }

    private static final class SimpleCaseFolding
    {
        private static final CodePointMappings MAPPINGS = parseSimpleCaseFoldingOrbits(Resource.RESOURCE.section(SECTION_SIMPLE_CASE_FOLDING_ORBITS));
    }

    private static final class FullCaseFolding
    {
        private static final MultiCharacterMappings MAPPINGS = parseFullCaseFolding(Resource.RESOURCE.section(SECTION_FULL_CASE_FOLDING));
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
}
