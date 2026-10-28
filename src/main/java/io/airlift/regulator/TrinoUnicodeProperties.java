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
import java.util.function.Supplier;

import static io.airlift.regulator.UnicodeGroups.addRanges;
import static io.airlift.regulator.UnicodeGroups.agesThrough;
import static io.airlift.regulator.UnicodeGroups.binary;
import static io.airlift.regulator.UnicodeGroups.cached;
import static io.airlift.regulator.UnicodeGroups.codePoints;
import static io.airlift.regulator.UnicodeGroups.generalCategories;
import static io.airlift.regulator.UnicodeGroups.generalCategory;
import static io.airlift.regulator.UnicodeGroups.script;
import static java.util.Objects.requireNonNull;

/// Unicode property names of Trino patterns other than Joni's POSIX names, built from the pinned
/// [UnicodeDatabase] by the rule jcodings' table generator applies to Onigmo's names: each Unicode
/// category, script, or binary property name or alias other than Bidi_Mirrored, and each block name
/// with an `In` prefix, lowercased with its spaces, hyphens, and underscores removed. Onigmo adds
/// `Any`, `Assigned`, `Newline`, `InNoBlock`, and cumulative Age names, `age=` and a version, which
/// hold the code points assigned in that version or earlier. A name matches a table name in any
/// ASCII casing and nothing else, so a name with `_`, `-`, a space, `=` other than in an Age name,
/// an `Is` prefix, or a character outside ASCII names no property. The table also holds the names
/// Trino's fork of jcodings adds (airlift/jcodings e81c0a3). Membership comes from the database
/// through [UnicodeGroups]; the Java names keep the JDK's computation through
/// [JavaUnicodeProperties].
final class TrinoUnicodeProperties
{
    // Onigmo reads its binary properties from PropList.txt, DerivedCoreProperties.txt, and
    // emoji-data.txt, so it has no Bidi_Mirrored, the database's other binary property.
    private static final String BIDI_MIRRORED = "Bidi_Mirrored";

    // The script of every code point that Scripts.txt omits, which it does not name.
    private static final String UNKNOWN_SCRIPT = "Unknown";

    // The current names of the three blocks whose Java identifiers Trino's fork of jcodings adds
    // as block names, by those identifiers.
    private static final Map<String, String> BLOCKS_BY_EARLIER_NAME = Map.of(
            "Greek", "Greek and Coptic",
            "Cyrillic Supplementary", "Cyrillic Supplement",
            "Combining Marks for Symbols", "Combining Diacritical Marks for Symbols");

    // The predicates of Character that Trino's fork of jcodings adds, as JavaUnicodeProperties
    // names them.
    private static final List<String> JAVA_PROPERTIES = List.of(
            "javaAlphabetic",
            "javaDefined",
            "javaDigit",
            "javaIdentifierIgnorable",
            "javaIdeographic",
            "javaISOControl",
            "javaJavaIdentifierPart",
            "javaJavaIdentifierStart",
            "javaLetter",
            "javaLetterOrDigit",
            "javaLowerCase",
            "javaMirrored",
            "javaSpaceChar",
            "javaTitleCase",
            "javaUnicodeIdentifierPart",
            "javaUnicodeIdentifierStart",
            "javaUpperCase",
            "javaWhitespace");

    private TrinoUnicodeProperties() {}

    /// Returns the class of a Trino property name other than a POSIX name, given as its ASCII
    /// lowercase key, such as lu, uppercaseletter, greek, inlatin1supplement, wspace, age=6.0,
    /// or javalowercase, or null if the key names no property.
    static CharClass lookup(String key)
    {
        Supplier<CharClass> property = Names.BY_KEY.get(key);
        return property == null ? null : property.get();
    }

    private static final class Names
    {
        private static final Map<String, Supplier<CharClass>> BY_KEY = names();

        private static Map<String, Supplier<CharClass>> names()
        {
            Map<String, Supplier<CharClass>> names = new HashMap<>();

            // Onigmo's names that no Unicode file defines.
            addName(names, UnicodeGroups::any, "Any");
            addName(names, UnicodeGroups::assigned, "Assigned");
            addName(names, () -> cached("trino:Newline", () -> codePoints('\n', '\n')), "Newline");

            // Trino's additions, airlift/jcodings e81c0a3.
            addName(names, UnicodeGroups::any, "all");
            addName(names, () -> cached("trino:L1", () -> codePoints(0x00, 0xFF)), "L1");
            addName(names, () -> generalCategories(List.of("Ll", "Lm", "Lo", "Lt", "Lu", "Nd")), "LD");
            addName(names, TrinoUnicodeProperties::horizontalWhiteSpace, "perlHorizontalWhiteSpace");
            addName(names, () -> cached("trino:VerticalWhiteSpace", () -> codePoints(0x0A, 0x0D, 0x85, 0x85, 0x2028, 0x2029)), "perlVerticalWhiteSpace");
            BLOCKS_BY_EARLIER_NAME.forEach((earlierName, block) -> addName(names, () -> block(block), "In " + earlierName));
            for (String property : JAVA_PROPERTIES) {
                addName(names, () -> requireNonNull(JavaUnicodeProperties.lookup(property), () -> "missing Java property " + property), property);
            }

            for (Aliases aliases : UnicodeDatabase.propertyValueAliases().get("gc")) {
                addAliases(names, () -> requireNonNull(generalCategory(aliases.shortName()), () -> "missing General_Category " + aliases.shortName()), aliases);
            }

            List<String> scripts = UnicodeDatabase.scripts().names();
            for (Aliases aliases : UnicodeDatabase.propertyValueAliases().get("sc")) {
                // A script that Scripts.txt assigns no code point, such as Katakana_Or_Hiragana,
                // has no name; Unknown holds every code point it omits.
                if (aliases.longName().equals(UNKNOWN_SCRIPT)) {
                    addAliases(names, UnicodeGroups::unknownScript, aliases);
                }
                else if (scripts.contains(aliases.longName())) {
                    addAliases(names, () -> script(aliases.longName()), aliases);
                }
            }

            Map<String, Aliases> propertyAliases = new HashMap<>();
            for (Aliases aliases : UnicodeDatabase.propertyAliases()) {
                propertyAliases.put(aliases.longName(), aliases);
            }
            for (String property : UnicodeDatabase.binaryProperties().names()) {
                if (!property.equals(BIDI_MIRRORED)) {
                    Aliases aliases = propertyAliases.get(property);
                    if (aliases == null) {
                        addName(names, () -> binary(property), property);
                    }
                    else {
                        addAliases(names, () -> binary(property), aliases);
                    }
                }
            }

            // Onigmo's Age is cumulative: a version holds every code point assigned in it or
            // earlier.
            for (String age : UnicodeDatabase.ages().names()) {
                addName(names, () -> agesThrough(age), "age=" + age);
            }

            for (String block : UnicodeDatabase.blocks().names()) {
                addName(names, () -> block(block), "In " + block);
            }
            addName(names, TrinoUnicodeProperties::noBlock, "In No Block");
            return Map.copyOf(names);
        }

        private static void addAliases(Map<String, Supplier<CharClass>> names, Supplier<CharClass> property, Aliases aliases)
        {
            addName(names, property, aliases.shortName());
            addName(names, property, aliases.longName());
            for (String alias : aliases.otherAliases()) {
                addName(names, property, alias);
            }
        }

        // Two spellings of one property, such as a script whose short and long names are both
        // Thai, give one name; a name of two properties fails rather than depending on their
        // order.
        private static void addName(Map<String, Supplier<CharClass>> names, Supplier<CharClass> property, String name)
        {
            String key = normalize(name);
            Supplier<CharClass> previous = names.put(key, property);
            if (previous != null && previous != property) {
                throw new IllegalStateException("Trino property name %s names two properties".formatted(key));
            }
        }

        // jcodings' generator lowercases Onigmo's names, which spell spaces and hyphens as
        // underscores, and removes the underscores.
        private static String normalize(String name)
        {
            StringBuilder key = new StringBuilder(name.length());
            for (int index = 0; index < name.length(); index++) {
                char character = name.charAt(index);
                if (character != ' ' && character != '-' && character != '_') {
                    key.append(character >= 'A' && character <= 'Z' ? (char) (character + ('a' - 'A')) : character);
                }
            }
            return key.toString();
        }
    }

    private static CharClass horizontalWhiteSpace()
    {
        return cached("trino:HorizontalWhiteSpace", () -> codePoints(
                0x09, 0x09, 0x20, 0x20, 0xA0, 0xA0, 0x1680, 0x1680, 0x180E, 0x180E, 0x2000, 0x200A, 0x202F, 0x202F, 0x205F, 0x205F, 0x3000, 0x3000));
    }

    private static CharClass block(String block)
    {
        return requireNonNull(UnicodeGroups.block(block), () -> "missing block " + block);
    }

    // Every code point that Blocks.txt assigns no block.
    private static CharClass noBlock()
    {
        return cached("blk:No_Block", () -> {
            CharClassBuilder builder = new CharClassBuilder();
            for (String block : UnicodeDatabase.blocks().names()) {
                addRanges(builder, UnicodeDatabase.blocks().ranges(block));
            }
            builder.negate();
            return builder.toCharClass();
        });
    }
}
