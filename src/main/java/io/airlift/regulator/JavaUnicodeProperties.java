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
import java.util.Map;
import java.util.TreeMap;

import static io.airlift.regulator.UnicodeGroups.alphabetic;
import static io.airlift.regulator.UnicodeGroups.any;
import static io.airlift.regulator.UnicodeGroups.assigned;
import static io.airlift.regulator.UnicodeGroups.binary;
import static io.airlift.regulator.UnicodeGroups.cached;
import static io.airlift.regulator.UnicodeGroups.codePoints;
import static io.airlift.regulator.UnicodeGroups.difference;
import static io.airlift.regulator.UnicodeGroups.generalCategory;
import static io.airlift.regulator.UnicodeGroups.majorClass;
import static io.airlift.regulator.UnicodeGroups.union;
import static io.airlift.regulator.UnicodeGroups.word;
import static java.util.Objects.requireNonNull;

/// Resolves the Unicode property names of Java patterns to the classes [UnicodeGroups]
/// builds from the pinned [UnicodeDatabase]; [TrinoUnicodeProperties] maps the Java predicates
/// of Trino patterns onto them. The names cover aliases, blocks, binary properties, and the
/// properties of [Character]; each property is computed as the JDK computes it. A Java
/// pattern must give the JDK's results, so keep the JDK's computation even where the database
/// stores a Unicode property of the same name.
final class JavaUnicodeProperties
{
    // The script of every code point that Scripts.txt omits, Character.UnicodeScript.UNKNOWN.
    private static final String UNKNOWN_SCRIPT = "Unknown";

    // Character.UnicodeBlock keeps the identifier of the earlier name of three blocks that Unicode
    // renamed, and accepts that name too: GREEK for Greek and Coptic, COMBINING_MARKS_FOR_SYMBOLS
    // for Combining Diacritical Marks for Symbols, and CYRILLIC_SUPPLEMENTARY for Cyrillic
    // Supplement, as the javadoc of each constant states.
    private static final Map<String, String> EARLIER_BLOCK_NAMES = Map.of(
            "Greek and Coptic", "Greek",
            "Combining Diacritical Marks for Symbols", "Combining Marks for Symbols",
            "Cyrillic Supplement", "Cyrillic Supplementary");

    // Character.UnicodeBlock.forName accepts the deprecated SURROGATES_AREA, but its javadoc states
    // that UnicodeBlock.of returns the standard surrogate blocks instead, so it holds no code point.
    private static final String SURROGATES_AREA = "SURROGATES_AREA";

    private JavaUnicodeProperties() {}

    /// Returns the class of a Java property name, such as Lu, IsGreek, InBasicLatin, script=Greek,
    /// IsAlphabetic, or javaLowerCase, or null if this resolver does not support the name.
    public static CharClass lookup(String name)
    {
        return lookup(name, false, false);
    }

    /// Returns the class of a Java property name, resolved as `Pattern.family` resolves it, or null
    /// if this resolver does not support the name. Under `UNICODE_CHARACTER_CLASS`, a name without
    /// a prefix or `=` is first read as a Unicode POSIX name regardless of letter case. Under
    /// `CASE_INSENSITIVE`, the cased properties widen as `CharPredicates` widens them: Lu, Ll, and
    /// Lt to LC, the ASCII POSIX Lower and Upper to Alpha, and the others to every code point in
    /// javaLowerCase, javaUpperCase, or javaTitleCase.
    static CharClass lookup(String name, boolean unicodeCharacterClass, boolean caseInsensitive)
    {
        requireNonNull(name, "name is null");
        int equalsIndex = name.indexOf('=');
        if (equalsIndex >= 0) {
            String propertyName = UnicodeCase.toLowerCase(name.substring(0, equalsIndex));
            String value = name.substring(equalsIndex + 1);
            return switch (propertyName) {
                case "sc", "script" -> javaScript(value);
                case "blk", "block" -> block(value);
                case "gc", "general_category" -> forProperty(value, caseInsensitive);
                default -> null;
            };
        }

        if (name.startsWith("In")) {
            return block(name.substring(2));
        }
        if (name.startsWith("Is")) {
            String value = name.substring(2);
            CharClass property = unicodeProperty(value, caseInsensitive);
            if (property == null) {
                property = forProperty(value, caseInsensitive);
            }
            return property != null ? property : javaScript(value);
        }

        CharClass property = unicodeCharacterClass ? posixProperty(UnicodeCase.toUpperCase(name), caseInsensitive) : null;
        return property != null ? property : forProperty(name, caseInsensitive);
    }

    // CharPredicates.forProperty: the short names of General_Category values and groupings, the
    // groupings LD and L1, all, the ASCII POSIX names, and the predicates of Character, each spelled
    // exactly. UAX #44 short names are at most two letters; a long name such as Uppercase_Letter is
    // not one of these.
    private static CharClass forProperty(String name, boolean caseInsensitive)
    {
        return switch (name) {
            case "Lu", "Ll", "Lt" -> generalCategory(caseInsensitive ? "LC" : name);
            case "LD" -> categories("Ll", "Lm", "Lo", "Lt", "Lu", "Nd");
            case "L1" -> cached("java:L1", () -> codePoints(0x00, 0xFF));
            case "all" -> any();
            default -> {
                CharClass property = name.length() <= 2 ? generalCategory(name) : null;
                if (property == null) {
                    property = javaProperty(name, caseInsensitive);
                }
                yield property != null ? property : asciiPosixProperty(name, caseInsensitive);
            }
        };
    }

    // Character.UnicodeScript.forName uppercases the name with Locale.ENGLISH and accepts a
    // script's ISO 15924 code, which is its short name in PropertyValueAliases.txt, before its
    // long name.
    private static CharClass javaScript(String name)
    {
        String longName = ScriptNames.BY_UPPERCASE_NAME.get(UnicodeCase.toUpperCase(name));
        if (longName == null) {
            return null;
        }
        return longName.equals(UNKNOWN_SCRIPT) ? UnicodeGroups.unknownScript() : UnicodeGroups.script(longName);
    }

    // Character.UnicodeBlock.forName uppercases the name with Locale.US, and accepts a block's
    // identifier, which is its name with spaces and hyphens replaced by underscores, its name, and
    // its name without spaces.
    private static CharClass block(String name)
    {
        String block = BlockNames.BY_KEY.get(UnicodeCase.toUpperCase(name));
        if (block == null) {
            return null;
        }
        if (block.equals(SURROGATES_AREA)) {
            return cached("blk:" + SURROGATES_AREA, () -> new CharClassBuilder().toCharClass());
        }
        return UnicodeGroups.block(block);
    }

    // CharPredicates.forUnicodeProperty: the Unicode properties of java.util.regex and then the
    // Unicode POSIX names, uppercased with Locale.ROOT and otherwise spelled exactly, computed as
    // CharPredicates computes them, even where the database stores a property of the same name.
    private static CharClass unicodeProperty(String name, boolean caseInsensitive)
    {
        String uppercaseName = UnicodeCase.toUpperCase(name);
        return switch (uppercaseName) {
            case "ALPHABETIC" -> alphabetic();
            case "ASSIGNED" -> assigned();
            case "CONTROL" -> categories("Cc");
            case "EMOJI" -> binary("Emoji");
            case "EMOJI_PRESENTATION" -> binary("Emoji_Presentation");
            case "EMOJI_MODIFIER" -> binary("Emoji_Modifier");
            case "EMOJI_MODIFIER_BASE" -> binary("Emoji_Modifier_Base");
            case "EMOJI_COMPONENT" -> binary("Emoji_Component");
            case "EXTENDED_PICTOGRAPHIC" -> binary("Extended_Pictographic");
            case "HEXDIGIT", "HEX_DIGIT" -> hexDigit();
            case "IDEOGRAPHIC" -> binary("Ideographic");
            case "JOINCONTROL", "JOIN_CONTROL" -> binary("Join_Control");
            case "LETTER" -> majorClass('L');
            case "LOWERCASE" -> caseInsensitive ? cased() : lowercase();
            case "NONCHARACTERCODEPOINT", "NONCHARACTER_CODE_POINT" -> binary("Noncharacter_Code_Point");
            case "TITLECASE" -> caseInsensitive ? cased() : categories("Lt");
            case "PUNCTUATION" -> majorClass('P');
            case "UPPERCASE" -> caseInsensitive ? cased() : uppercase();
            case "WHITESPACE", "WHITE_SPACE" -> whiteSpace();
            case "WORD" -> word();
            default -> posixProperty(uppercaseName, caseInsensitive);
        };
    }

    // CharPredicates.forPOSIXName: the Unicode POSIX names, uppercased.
    private static CharClass posixProperty(String uppercaseName, boolean caseInsensitive)
    {
        return switch (uppercaseName) {
            case "ALPHA" -> alphabetic();
            case "LOWER" -> caseInsensitive ? cased() : lowercase();
            case "UPPER" -> caseInsensitive ? cased() : uppercase();
            case "SPACE" -> whiteSpace();
            case "PUNCT" -> majorClass('P');
            case "XDIGIT" -> hexDigit();
            case "ALNUM" -> cached("java:Alnum", () -> union(alphabetic(), categories("Nd")));
            case "CNTRL" -> categories("Cc");
            case "DIGIT" -> categories("Nd");
            case "BLANK" -> blank();
            case "GRAPH" -> graph();
            case "PRINT" -> cached("java:Print", () -> difference(union(graph(), blank()), categories("Cc")));
            default -> null;
        };
    }

    // The predicates of Character, computed as the JDK computes them.
    private static CharClass javaProperty(String name, boolean caseInsensitive)
    {
        return switch (name) {
            case "javaLowerCase" -> caseInsensitive ? cased() : lowercase();
            case "javaUpperCase" -> caseInsensitive ? cased() : uppercase();
            case "javaAlphabetic" -> alphabetic();
            case "javaIdeographic" -> binary("Ideographic");
            case "javaTitleCase" -> caseInsensitive ? cased() : categories("Lt");
            case "javaDigit" -> categories("Nd");
            case "javaDefined" -> assigned();
            case "javaLetter" -> majorClass('L');
            case "javaLetterOrDigit" -> categories("Ll", "Lm", "Lo", "Lt", "Lu", "Nd");
            case "javaJavaIdentifierStart" -> cached("java:JavaIdentifierStart", () -> union(majorClass('L'), categories("Nl", "Pc", "Sc")));
            case "javaJavaIdentifierPart" -> cached("java:JavaIdentifierPart", () -> union(
                    majorClass('L'),
                    categories("Mc", "Mn", "Nd", "Nl", "Pc", "Sc"),
                    identifierIgnorable()));
            case "javaUnicodeIdentifierStart" -> cached("java:UnicodeIdentifierStart", () -> union(
                    majorClass('L'),
                    categories("Nl"),
                    binary("Other_ID_Start")));
            case "javaUnicodeIdentifierPart" -> cached("java:UnicodeIdentifierPart", () -> union(
                    majorClass('L'),
                    categories("Mc", "Mn", "Nd", "Nl", "Pc"),
                    identifierIgnorable(),
                    binary("Other_ID_Start"),
                    binary("Other_ID_Continue")));
            case "javaIdentifierIgnorable" -> identifierIgnorable();
            case "javaSpaceChar" -> majorClass('Z');
            // Character.isWhitespace: a space, line, or paragraph separator other than the
            // no-break spaces U+00A0, U+2007, and U+202F, or one of U+0009 through U+000D and
            // U+001C through U+001F.
            case "javaWhitespace" -> cached("java:Whitespace", () -> union(
                    difference(majorClass('Z'), codePoints(0xA0, 0xA0, 0x2007, 0x2007, 0x202F, 0x202F)),
                    codePoints(0x09, 0x0D, 0x1C, 0x1F)));
            // Character.isISOControl: U+0000 through U+001F and U+007F through U+009F.
            case "javaISOControl" -> cached("java:ISOControl", () -> codePoints(0x00, 0x1F, 0x7F, 0x9F));
            case "javaMirrored" -> binary("Bidi_Mirrored");
            default -> null;
        };
    }

    private static CharClass asciiPosixProperty(String name, boolean caseInsensitive)
    {
        return switch (name) {
            case "ASCII" -> ascii(name, 0x00, 0x7F);
            case "Alnum" -> ascii(name, '0', '9', 'A', 'Z', 'a', 'z');
            case "Alpha" -> ascii(name, 'A', 'Z', 'a', 'z');
            case "Blank" -> ascii(name, '\t', '\t', ' ', ' ');
            case "Cntrl" -> ascii(name, 0x00, 0x1F, 0x7F, 0x7F);
            case "Digit" -> ascii(name, '0', '9');
            case "Graph" -> ascii(name, 0x21, 0x7E);
            case "Lower" -> caseInsensitive ? asciiPosixProperty("Alpha", false) : ascii(name, 'a', 'z');
            case "Print" -> ascii(name, 0x20, 0x7E);
            case "Punct" -> ascii(name, 0x21, 0x2F, 0x3A, 0x40, 0x5B, 0x60, 0x7B, 0x7E);
            case "Space" -> ascii(name, '\t', '\r', ' ', ' ');
            case "Upper" -> caseInsensitive ? asciiPosixProperty("Alpha", false) : ascii(name, 'A', 'Z');
            case "XDigit" -> ascii(name, '0', '9', 'A', 'F', 'a', 'f');
            default -> null;
        };
    }

    private static CharClass ascii(String name, int... ranges)
    {
        return cached("posix:" + name, () -> codePoints(ranges));
    }

    private static CharClass categories(String... values)
    {
        return requireNonNull(UnicodeGroups.generalCategories(List.of(values)), "missing General_Category value");
    }

    // CharPredicates.HEX_DIGIT: Nd or Hex_Digit.
    private static CharClass hexDigit()
    {
        return cached("java:HexDigit", () -> union(categories("Nd"), binary("Hex_Digit")));
    }

    // CharPredicates.WHITE_SPACE: Zs, Zl, Zp, U+0009 through U+000D, or U+0085.
    private static CharClass whiteSpace()
    {
        return cached("java:WhiteSpace", () -> union(majorClass('Z'), codePoints(0x09, 0x0D, 0x85, 0x85)));
    }

    // Character.isLowerCase: Ll or Other_Lowercase.
    private static CharClass lowercase()
    {
        return cached("java:Lowercase", () -> union(categories("Ll"), binary("Other_Lowercase")));
    }

    // Character.isUpperCase: Lu or Other_Uppercase.
    private static CharClass uppercase()
    {
        return cached("java:Uppercase", () -> union(categories("Lu"), binary("Other_Uppercase")));
    }

    // The case-insensitive cased properties: Character.isLowerCase, isUpperCase, or isTitleCase.
    private static CharClass cased()
    {
        return cached("java:Cased", () -> union(lowercase(), uppercase(), categories("Lt")));
    }

    // CharPredicates.BLANK: Zs or U+0009.
    private static CharClass blank()
    {
        return cached("java:Blank", () -> union(categories("Zs"), codePoints(0x09, 0x09)));
    }

    // CharPredicates.GRAPH: none of Zs, Zl, Zp, Cc, Cs, and Cn.
    private static CharClass graph()
    {
        return cached("java:Graph", () -> {
            CharClassBuilder builder = UnicodeGroups.builder(union(categories("Cc", "Cs", "Zl", "Zp", "Zs"), generalCategory("Cn")));
            builder.negate();
            return builder.toCharClass();
        });
    }

    // Character.isIdentifierIgnorable: U+0000 through U+0008, U+000E through U+001B, U+007F
    // through U+009F, and Cf.
    private static CharClass identifierIgnorable()
    {
        return cached("java:IdentifierIgnorable", () -> union(codePoints(0x00, 0x08, 0x0E, 0x1B, 0x7F, 0x9F), categories("Cf")));
    }

    /// Returns the identifier of the block of `codePoint`, which
    /// `Character.UnicodeBlock.of(codePoint).toString()` returns, such as
    /// LATIN_1_SUPPLEMENT, or null if the code point is in no block.
    static String javaBlockIdentifier(int codePoint)
    {
        int index = Arrays.binarySearch(BlockNames.FIRST_CODE_POINTS, codePoint);
        if (index < 0) {
            index = -index - 2;
        }
        if (index < 0 || codePoint > BlockNames.LAST_CODE_POINTS[index]) {
            return null;
        }
        return BlockNames.IDENTIFIERS[index];
    }

    private static final class ScriptNames
    {
        private static final Map<String, String> BY_UPPERCASE_NAME;

        static {
            // The Java scripts are the scripts of Scripts.txt and Unknown.
            List<String> names = new ArrayList<>(UnicodeDatabase.scripts().names());
            names.add(UNKNOWN_SCRIPT);

            Map<String, String> byUppercaseName = new HashMap<>();
            for (String name : names) {
                byUppercaseName.put(UnicodeCase.toUpperCase(name), name);
            }
            // A short name of a script that Scripts.txt assigns no code point, such as Hrkt, names
            // no Java script.
            UnicodeDatabase.scriptShortNames().forEach((shortName, longName) -> {
                if (names.contains(longName)) {
                    byUppercaseName.put(UnicodeCase.toUpperCase(shortName), longName);
                }
            });
            BY_UPPERCASE_NAME = Map.copyOf(byUppercaseName);
        }
    }

    private static final class BlockNames
    {
        private static final Map<String, String> BY_KEY;
        // The first code point, last code point, and identifier of each block, in ascending order.
        private static final int[] FIRST_CODE_POINTS;
        private static final int[] LAST_CODE_POINTS;
        private static final String[] IDENTIFIERS;

        static {
            Map<String, String> byKey = new HashMap<>();
            Map<Integer, String> identifiersByFirstCodePoint = new TreeMap<>();
            Map<Integer, Integer> lastCodePointsByFirst = new HashMap<>();
            for (String block : UnicodeDatabase.blocks().names()) {
                String identifierName = EARLIER_BLOCK_NAMES.getOrDefault(block, block);
                String identifier = UnicodeCase.toUpperCase(identifierName).replace(' ', '_').replace('-', '_');
                byKey.put(identifier, block);
                for (String name : List.of(block, identifierName)) {
                    String uppercaseName = UnicodeCase.toUpperCase(name);
                    byKey.put(uppercaseName, block);
                    byKey.put(uppercaseName.replace(" ", ""), block);
                }
                int[] ranges = UnicodeDatabase.blocks().ranges(block);
                for (int index = 0; index < ranges.length; index += 2) {
                    identifiersByFirstCodePoint.put(ranges[index], identifier);
                    lastCodePointsByFirst.put(ranges[index], ranges[index + 1]);
                }
            }
            byKey.put(SURROGATES_AREA, SURROGATES_AREA);
            BY_KEY = Map.copyOf(byKey);

            FIRST_CODE_POINTS = identifiersByFirstCodePoint.keySet().stream().mapToInt(Integer::intValue).toArray();
            LAST_CODE_POINTS = Arrays.stream(FIRST_CODE_POINTS).map(lastCodePointsByFirst::get).toArray();
            IDENTIFIERS = identifiersByFirstCodePoint.values().toArray(String[]::new);
        }
    }
}
