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
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Resolves Rust regex Unicode class names with the names {@link RustUnicodePropertyTables} builds
 * from the pinned {@link UnicodeDatabase} by the crate's rules. Resolution follows the crate's
 * lookup order and errors exactly; membership then comes from the database through
 * {@link UnicodeGroups}. A value the crate names but has no class for, such as Unknown, is a
 * value the database assigns no code point.
 */
final class RustUnicodeProperties
{
    enum Status
    {
        FOUND,
        PROPERTY_NOT_FOUND,
        PROPERTY_VALUE_NOT_FOUND,
    }

    /**
     * A resolved class or a crate lookup error.
     */
    record Resolution(Status status, CharClass charClass)
    {
        Resolution
        {
            requireNonNull(status, "status is null");
        }

        private static Resolution found(CharClass charClass)
        {
            return new Resolution(Status.FOUND, requireNonNull(charClass, "charClass is null"));
        }
    }

    private static final Resolution PROPERTY_NOT_FOUND = new Resolution(Status.PROPERTY_NOT_FOUND, null);
    private static final Resolution PROPERTY_VALUE_NOT_FOUND = new Resolution(Status.PROPERTY_VALUE_NOT_FOUND, null);
    private static final CharClass ASCII = UnicodeGroups.codePoints(0, 0x7F);

    private RustUnicodeProperties() {}

    /**
     * Resolves a one-letter ({@code \pL}) or bare-name ({@code \p{Greek}}) class.
     */
    static Resolution resolveName(String name)
    {
        String normalized = normalize(name);
        // The crate treats these abbreviations as general categories, not as the properties they also abbreviate.
        if (!normalized.equals("cf") && !normalized.equals("sc") && !normalized.equals("lc")) {
            // The crate's binary properties are those of PropList.txt, DerivedCoreProperties.txt,
            // and emoji-data.txt, and Bidi_Mirrored, which are the database's binary properties.
            String property = RustUnicodePropertyTables.PROPERTY_NAMES.get(normalized);
            if (property != null) {
                return found(UnicodeGroups.binaryPropertyValue(property), PROPERTY_NOT_FOUND);
            }
        }
        String generalCategory = canonicalGeneralCategory(normalized);
        if (generalCategory != null) {
            return generalCategory(generalCategory);
        }
        String script = RustUnicodePropertyTables.SCRIPT.get(normalized);
        if (script != null) {
            return script(script);
        }
        return PROPERTY_NOT_FOUND;
    }

    /**
     * Resolves a {@code \p{name=value}} or {@code \p{name:value}} class.
     */
    static Resolution resolveValue(String name, String value)
    {
        String property = RustUnicodePropertyTables.PROPERTY_NAMES.get(normalize(name));
        if (property == null) {
            return PROPERTY_NOT_FOUND;
        }
        String normalizedValue = normalize(value);
        if (property.equals("General_Category")) {
            String generalCategory = canonicalGeneralCategory(normalizedValue);
            return generalCategory == null ? PROPERTY_VALUE_NOT_FOUND : generalCategory(generalCategory);
        }
        if (property.equals("Script")) {
            String script = RustUnicodePropertyTables.SCRIPT.get(normalizedValue);
            return script == null ? PROPERTY_VALUE_NOT_FOUND : script(script);
        }
        Map<String, String> values = RustUnicodePropertyTables.PROPERTY_VALUES.get(property);
        if (values == null) {
            return PROPERTY_VALUE_NOT_FOUND;
        }
        String canonicalValue = values.get(normalizedValue);
        if (canonicalValue == null) {
            return PROPERTY_VALUE_NOT_FOUND;
        }
        CharClass charClass = switch (property) {
            case "Age" -> age(canonicalValue);
            case "Script_Extensions" -> UnicodeGroups.scriptExtension(canonicalValue);
            case "Grapheme_Cluster_Break" -> UnicodeGroups.graphemeClusterBreak(canonicalValue);
            case "Sentence_Break" -> UnicodeGroups.sentenceBreak(canonicalValue);
            case "Word_Break" -> UnicodeGroups.wordBreak(canonicalValue);
            default -> throw new IllegalStateException("no classes for the values of " + property);
        };
        return found(charClass, PROPERTY_VALUE_NOT_FOUND);
    }

    private static String canonicalGeneralCategory(String normalized)
    {
        return switch (normalized) {
            case "any" -> "Any";
            case "assigned" -> "Assigned";
            case "ascii" -> "ASCII";
            default -> RustUnicodePropertyTables.GENERAL_CATEGORY.get(normalized);
        };
    }

    private static Resolution generalCategory(String generalCategory)
    {
        return switch (generalCategory) {
            case "Any" -> Resolution.found(UnicodeGroups.any());
            case "ASCII" -> Resolution.found(ASCII);
            case "Assigned" -> Resolution.found(UnicodeGroups.assigned());
            // The crate leaves out surrogates, which a Rust char cannot hold.
            case "Surrogate" -> PROPERTY_VALUE_NOT_FOUND;
            default -> Resolution.found(requireNonNull(UnicodeGroups.generalCategory(generalCategory), () -> "missing General_Category " + generalCategory));
        };
    }

    private static Resolution script(String script)
    {
        return found(UnicodeGroups.script(script), PROPERTY_VALUE_NOT_FOUND);
    }

    private static Resolution found(CharClass charClass, Resolution notFound)
    {
        return charClass == null ? notFound : Resolution.found(charClass);
    }

    // The crate's Age is cumulative: a version, such as 6.0, holds every code point assigned in it
    // or earlier. Unassigned, which names no version, has no class.
    private static CharClass age(String version)
    {
        if (UnicodeDatabase.ages().ranges(version) == null) {
            return null;
        }
        int key = versionKey(version);
        List<String> ages = new ArrayList<>();
        for (String age : UnicodeDatabase.ages().names()) {
            if (versionKey(age) <= key) {
                ages.add(age);
            }
        }
        return UnicodeGroups.ages(ages);
    }

    // Orders Age values, which are a major and a minor version, such as 15.1.
    private static int versionKey(String version)
    {
        int dot = version.indexOf('.');
        return Integer.parseInt(version.substring(0, dot)) * 1000 + Integer.parseInt(version.substring(dot + 1));
    }

    /**
     * Normalizes a name as the crate normalizes a query (UAX44-LM3): drops an {@code is} prefix
     * from a name of two or more characters, drops spaces, underscores, hyphens, and non-ASCII
     * characters, and lowercases ASCII letters, except that {@code isc} stays {@code isc}. The
     * table names of {@link RustUnicodePropertyTables} keep the prefix of a two-character name,
     * as the crate's table generator does.
     */
    private static String normalize(String name)
    {
        boolean startsWithIs = name.length() >= 2 && (name.charAt(0) == 'i' || name.charAt(0) == 'I') && (name.charAt(1) == 's' || name.charAt(1) == 'S');
        StringBuilder normalized = new StringBuilder(name.length());
        for (int index = startsWithIs ? 2 : 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character == ' ' || character == '_' || character == '-' || character > 0x7F) {
                continue;
            }
            normalized.append(character >= 'A' && character <= 'Z' ? (char) (character + ('a' - 'A')) : character);
        }
        // "isc", the short name of ISO_Comment, is kept whole, because stripping "is" would leave
        // "c", the Other general category.
        if (startsWithIs && normalized.length() == 1 && normalized.charAt(0) == 'c') {
            return "isc";
        }
        return normalized.toString();
    }
}
