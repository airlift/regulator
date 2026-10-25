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

import io.airlift.regulator.UnicodeDatabase.Aliases;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/// Unicode property names of the Rust regex crate, built from the property and value aliases of
/// the pinned [UnicodeDatabase] by the rules of ucd-generate, the crate's table generator.
/// Each name map, including each inner `PROPERTY_VALUES` map, maps normalized aliases to
/// canonical names, normalizing as ucd-generate does; the Age map instead maps them to the
/// database's Age values, such as 6.0. The maps hold names only; character membership also comes
/// from the database.
final class RustUnicodePropertyTables
{
    // ucd-generate adds the UAX #51 emoji properties under their long names
    // (util.rs, PropertyNames::from_ucd_dir).
    private static final List<String> EMOJI_PROPERTIES = List.of("Emoji", "Emoji_Component", "Emoji_Modifier", "Emoji_Modifier_Base", "Emoji_Presentation", "Extended_Pictographic");

    static final Map<String, String> PROPERTY_NAMES = propertyNames();
    static final Map<String, String> GENERAL_CATEGORY = valueNames("gc");
    static final Map<String, String> SCRIPT = valueNames("sc");

    // The properties whose values regex-syntax names besides General_Category and Script, which
    // are resolved directly: those of `ucd-generate property-values --include
    // gc,script,scx,age,gcb,wb,sb`. Script_Extensions shares the Script values.
    static final Map<String, Map<String, String>> PROPERTY_VALUES = Map.of(
            "Age", valueNames("age", Aliases::shortName),
            "Grapheme_Cluster_Break", valueNames("GCB"),
            "Script_Extensions", SCRIPT,
            "Sentence_Break", valueNames("SB"),
            "Word_Break", valueNames("WB"));

    private RustUnicodePropertyTables() {}

    private static Map<String, String> propertyNames()
    {
        Map<String, String> names = new HashMap<>();
        for (Aliases aliases : UnicodeDatabase.propertyAliases()) {
            addAliases(names, aliases, aliases.longName());
        }
        for (String property : EMOJI_PROPERTIES) {
            addName(names, property, property);
        }
        return Map.copyOf(names);
    }

    private static Map<String, String> valueNames(String property)
    {
        return valueNames(property, Aliases::longName);
    }

    private static Map<String, String> valueNames(String property, Function<Aliases, String> canonicalName)
    {
        Map<String, String> names = new HashMap<>();
        for (Aliases aliases : UnicodeDatabase.propertyValueAliases().get(property)) {
            addAliases(names, aliases, canonicalName.apply(aliases));
        }
        return Map.copyOf(names);
    }

    private static void addAliases(Map<String, String> names, Aliases aliases, String canonicalName)
    {
        addName(names, aliases.shortName(), canonicalName);
        addName(names, aliases.longName(), canonicalName);
        for (String alias : aliases.otherAliases()) {
            addName(names, alias, canonicalName);
        }
    }

    // ucd-generate keeps the last of two names that normalize alike. The database has no such
    // names, and a collision fails rather than depending on their order.
    private static void addName(Map<String, String> names, String name, String canonicalName)
    {
        String normalizedName = normalize(name);
        String previous = names.put(normalizedName, canonicalName);
        if (previous != null && !previous.equals(canonicalName)) {
            throw new IllegalStateException("normalized Unicode name %s names both %s and %s".formatted(normalizedName, previous, canonicalName));
        }
    }

    /// Normalizes a name as ucd-util's `symbolic_name_normalize` does (UAX44-LM3): drops an
    /// `is` prefix from a name longer than two characters, drops spaces, underscores,
    /// hyphens, and non-ASCII characters, and lowercases ASCII letters, except that `isc`
    /// stays `isc`. This differs from the crate's normalization of queries only for
    /// two-character names, whose prefix a query drops.
    private static String normalize(String name)
    {
        boolean startsWithIs = name.length() > 2 && (name.charAt(0) == 'i' || name.charAt(0) == 'I') && (name.charAt(1) == 's' || name.charAt(1) == 'S');
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
