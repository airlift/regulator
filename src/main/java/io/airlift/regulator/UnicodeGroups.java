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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static io.airlift.regulator.CharClass.RUNEMAX;
import static java.util.Objects.requireNonNull;

/**
 * Builds Unicode property classes from the pinned {@link UnicodeDatabase} and caches them as
 * immutable range sets. RE2 reads the raw property values of the database; {@link #lookup}
 * resolves the Java and Trino property names, which add aliases, blocks, binary properties, and
 * the properties of {@link Character}, rebuilt from the formulas its documentation gives.
 */
final class UnicodeGroups
{
    private static final ConcurrentHashMap<String, CharClass> CACHE = new ConcurrentHashMap<>();

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

    private UnicodeGroups() {}

    /**
     * Returns the class of every code point.
     */
    static CharClass any()
    {
        return cached("any", () -> codePoints(0, RUNEMAX));
    }

    /**
     * Returns the General_Category values that UnicodeData.txt assigns, in ascending order.
     */
    private static List<String> generalCategoryValues()
    {
        return UnicodeDatabase.generalCategories().names();
    }

    /**
     * Returns the code points that UnicodeData.txt assigns any of the given General_Category
     * values, or null if one of them is not a value it assigns.
     */
    static CharClass generalCategories(List<String> values)
    {
        UnicodeDatabase.PropertyValues categories = UnicodeDatabase.generalCategories();
        for (String value : values) {
            if (categories.ranges(value) == null) {
                return null;
            }
        }
        return cached("gc:" + String.join("|", values), () -> {
            CharClassBuilder builder = new CharClassBuilder();
            for (String value : values) {
                addRanges(builder, categories.ranges(value));
            }
            return builder.toCharClass();
        });
    }

    /**
     * Returns the code points of every General_Category value whose name starts with the given
     * letter, such as L for Lu, Ll, Lt, Lm, and Lo, or null if no value does.
     */
    static CharClass majorClass(char letter)
    {
        List<String> values = generalCategoryValues().stream()
                .filter(value -> value.charAt(0) == letter)
                .toList();
        return values.isEmpty() ? null : generalCategories(values);
    }

    /**
     * Returns the code points that Scripts.txt assigns the script with the given long name, such as
     * Old_Italic, or null if it assigns no such script.
     */
    static CharClass script(String longName)
    {
        int[] ranges = UnicodeDatabase.scripts().ranges(longName);
        if (ranges == null) {
            return null;
        }
        return cached("sc:" + longName, () -> codePoints(ranges));
    }

    /**
     * Returns the long names of the Java and Trino scripts, which are the scripts of Scripts.txt
     * and Unknown.
     */
    static List<String> scriptNames()
    {
        return ScriptNames.NAMES;
    }

    /**
     * Returns the class of a Java or Trino property name, such as Lu, IsGreek, InBasicLatin,
     * script=Greek, IsAlphabetic, or javaLowerCase, or null if the name is not one.
     */
    public static CharClass lookup(String name)
    {
        requireNonNull(name, "name is null");
        if (name.equals("Any") || name.equals("all")) {
            return any();
        }

        int equalsIndex = name.indexOf('=');
        if (equalsIndex >= 0) {
            String propertyName = name.substring(0, equalsIndex).toLowerCase(Locale.ROOT);
            String value = name.substring(equalsIndex + 1);
            return switch (propertyName) {
                case "sc", "script" -> javaScript(value);
                case "blk", "block" -> block(value);
                case "gc", "general_category" -> category(value);
                default -> null;
            };
        }

        if (name.startsWith("In")) {
            return block(name.substring(2));
        }
        if (name.startsWith("Is")) {
            String value = name.substring(2);
            CharClass property = binaryProperty(value);
            if (property == null) {
                property = category(value);
            }
            return property != null ? property : javaScript(value);
        }

        CharClass property = category(name);
        if (property == null) {
            property = javaProperty(name);
        }
        if (property == null) {
            property = asciiPosixProperty(name);
        }
        return property != null ? property : javaScript(name);
    }

    private static CharClass category(String name)
    {
        return switch (name) {
            case "Cn", "Unassigned" -> unassigned();
            case "Lu", "Uppercase_Letter" -> categories("Lu");
            case "Ll", "Lowercase_Letter" -> categories("Ll");
            case "Lt", "Titlecase_Letter" -> categories("Lt");
            case "Lm", "Modifier_Letter" -> categories("Lm");
            case "Lo", "Other_Letter" -> categories("Lo");
            case "Mn", "Nonspacing_Mark" -> categories("Mn");
            case "Me", "Enclosing_Mark" -> categories("Me");
            case "Mc", "Spacing_Mark", "Combining_Spacing_Mark" -> categories("Mc");
            case "Nd", "Decimal_Number", "Decimal_Digit_Number" -> categories("Nd");
            case "Nl", "Letter_Number" -> categories("Nl");
            case "No", "Other_Number" -> categories("No");
            case "Zs", "Space_Separator" -> categories("Zs");
            case "Zl", "Line_Separator" -> categories("Zl");
            case "Zp", "Paragraph_Separator" -> categories("Zp");
            case "Cc", "Control" -> categories("Cc");
            case "Cf", "Format" -> categories("Cf");
            case "Co", "Private_Use" -> categories("Co");
            case "Cs", "Surrogate" -> categories("Cs");
            case "Pd", "Dash_Punctuation" -> categories("Pd");
            case "Ps", "Open_Punctuation", "Start_Punctuation" -> categories("Ps");
            case "Pe", "Close_Punctuation", "End_Punctuation" -> categories("Pe");
            case "Pc", "Connector_Punctuation" -> categories("Pc");
            case "Po", "Other_Punctuation" -> categories("Po");
            case "Sm", "Math_Symbol" -> categories("Sm");
            case "Sc", "Currency_Symbol" -> categories("Sc");
            case "Sk", "Modifier_Symbol" -> categories("Sk");
            case "So", "Other_Symbol" -> categories("So");
            case "Pi", "Initial_Punctuation", "Initial_Quote_Punctuation" -> categories("Pi");
            case "Pf", "Final_Punctuation", "Final_Quote_Punctuation" -> categories("Pf");
            case "L", "Letter" -> majorClass('L');
            case "LC", "Cased_Letter" -> categories("Ll", "Lt", "Lu");
            case "M", "Mark" -> majorClass('M');
            case "N", "Number" -> majorClass('N');
            case "Z", "Separator" -> majorClass('Z');
            // Character.getType reports an unassigned code point as UNASSIGNED, which
            // java.util.regex counts in C.
            case "C", "Other" -> cached("gc:Cc|Cf|Cn|Co|Cs", () -> union(majorClass('C'), unassigned()));
            case "P", "Punctuation" -> majorClass('P');
            case "S", "Symbol" -> majorClass('S');
            case "LD" -> categories("Ll", "Lm", "Lo", "Lt", "Lu", "Nd");
            default -> null;
        };
    }

    // Character.UnicodeScript.forName uppercases the name with Locale.ENGLISH and accepts a
    // script's ISO 15924 code, which is its short name in PropertyValueAliases.txt, before its
    // long name.
    private static CharClass javaScript(String name)
    {
        String longName = ScriptNames.BY_UPPERCASE_NAME.get(name.toUpperCase(Locale.ENGLISH));
        if (longName == null) {
            return null;
        }
        if (longName.equals(UNKNOWN_SCRIPT)) {
            return cached("sc:" + UNKNOWN_SCRIPT, () -> {
                CharClassBuilder builder = new CharClassBuilder();
                for (String script : UnicodeDatabase.scripts().names()) {
                    addRanges(builder, UnicodeDatabase.scripts().ranges(script));
                }
                builder.negate();
                return builder.toCharClass();
            });
        }
        return script(longName);
    }

    // Character.UnicodeBlock.forName uppercases the name with Locale.US, and accepts a block's
    // identifier, which is its name with spaces and hyphens replaced by underscores, its name, and
    // its name without spaces.
    private static CharClass block(String name)
    {
        String block = BlockNames.BY_KEY.get(name.toUpperCase(Locale.US));
        if (block == null) {
            return null;
        }
        if (block.equals(SURROGATES_AREA)) {
            return cached("blk:" + SURROGATES_AREA, () -> new CharClassBuilder().toCharClass());
        }
        return cached("blk:" + block, () -> codePoints(UnicodeDatabase.blocks().ranges(block)));
    }

    // The Unicode properties of java.util.regex, defined in CharPredicates.
    private static CharClass binaryProperty(String name)
    {
        String normalizedName = name.replace("_", "").toUpperCase(Locale.ROOT);
        return switch (normalizedName) {
            case "ALPHABETIC", "ALPHA" -> alphabetic();
            case "ASSIGNED" -> assigned();
            case "CONTROL", "CNTRL" -> categories("Cc");
            case "DIGIT" -> categories("Nd");
            case "EMOJI" -> binary("Emoji");
            case "EMOJICOMPONENT" -> binary("Emoji_Component");
            case "EMOJIMODIFIER" -> binary("Emoji_Modifier");
            case "EMOJIMODIFIERBASE" -> binary("Emoji_Modifier_Base");
            case "EMOJIPRESENTATION" -> binary("Emoji_Presentation");
            case "EXTENDEDPICTOGRAPHIC" -> binary("Extended_Pictographic");
            case "HEXDIGIT", "XDIGIT" -> cached("java:HexDigit", () -> union(categories("Nd"), binary("Hex_Digit")));
            case "IDEOGRAPHIC" -> binary("Ideographic");
            case "JOINCONTROL" -> binary("Join_Control");
            case "LETTER" -> majorClass('L');
            case "LOWERCASE", "LOWER" -> lowercase();
            case "NONCHARACTERCODEPOINT" -> binary("Noncharacter_Code_Point");
            case "PUNCTUATION", "PUNCT" -> majorClass('P');
            case "TITLECASE" -> categories("Lt");
            case "UPPERCASE", "UPPER" -> uppercase();
            case "WHITESPACE", "SPACE" -> cached("java:WhiteSpace", () -> union(majorClass('Z'), codePoints(0x09, 0x0D, 0x85, 0x85)));
            case "WORD" -> cached("java:Word", () -> union(
                    alphabetic(),
                    categories("Mc", "Me", "Mn", "Nd", "Pc"),
                    binary("Join_Control")));
            case "ALNUM" -> cached("java:Alnum", () -> union(alphabetic(), categories("Nd")));
            case "BLANK" -> blank();
            case "GRAPH" -> graph();
            case "PRINT" -> cached("java:Print", () -> difference(union(graph(), blank()), categories("Cc")));
            default -> null;
        };
    }

    // The predicates of Character, from the formulas of their javadoc.
    private static CharClass javaProperty(String name)
    {
        return switch (name) {
            case "javaLowerCase" -> lowercase();
            case "javaUpperCase" -> uppercase();
            case "javaAlphabetic" -> alphabetic();
            case "javaIdeographic" -> binary("Ideographic");
            case "javaTitleCase" -> categories("Lt");
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

    private static CharClass asciiPosixProperty(String name)
    {
        return switch (name) {
            case "ASCII" -> ascii(name, 0x00, 0x7F);
            case "Alnum" -> ascii(name, '0', '9', 'A', 'Z', 'a', 'z');
            case "Alpha" -> ascii(name, 'A', 'Z', 'a', 'z');
            case "Blank" -> ascii(name, '\t', '\t', ' ', ' ');
            case "Cntrl" -> ascii(name, 0x00, 0x1F, 0x7F, 0x7F);
            case "Digit" -> ascii(name, '0', '9');
            case "Graph" -> ascii(name, 0x21, 0x7E);
            case "Lower" -> ascii(name, 'a', 'z');
            case "Print" -> ascii(name, 0x20, 0x7E);
            case "Punct" -> ascii(name, 0x21, 0x2F, 0x3A, 0x40, 0x5B, 0x60, 0x7B, 0x7E);
            case "Space" -> ascii(name, '\t', '\r', ' ', ' ');
            case "Upper" -> ascii(name, 'A', 'Z');
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
        return requireNonNull(generalCategories(List.of(values)), "missing General_Category value");
    }

    private static CharClass assigned()
    {
        return requireNonNull(generalCategories(generalCategoryValues()), "missing General_Category value");
    }

    private static CharClass unassigned()
    {
        return cached("gc:Cn", () -> {
            CharClassBuilder builder = builder(assigned());
            builder.negate();
            return builder.toCharClass();
        });
    }

    private static CharClass binary(String property)
    {
        int[] ranges = requireNonNull(UnicodeDatabase.binaryProperties().ranges(property), () -> "missing binary property " + property);
        return cached("binary:" + property, () -> codePoints(ranges));
    }

    // Character.isAlphabetic: Lu, Ll, Lt, Lm, Lo, Nl, or Other_Alphabetic.
    private static CharClass alphabetic()
    {
        return cached("java:Alphabetic", () -> union(majorClass('L'), categories("Nl"), binary("Other_Alphabetic")));
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

    // CharPredicates.BLANK: Zs or U+0009.
    private static CharClass blank()
    {
        return cached("java:Blank", () -> union(categories("Zs"), codePoints(0x09, 0x09)));
    }

    // CharPredicates.GRAPH: none of Zs, Zl, Zp, Cc, Cs, and Cn.
    private static CharClass graph()
    {
        return cached("java:Graph", () -> {
            CharClassBuilder builder = builder(union(categories("Cc", "Cs", "Zl", "Zp", "Zs"), unassigned()));
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

    private static CharClass cached(String identity, Supplier<CharClass> supplier)
    {
        // Not computeIfAbsent: a class built from other classes looks them up in the cache.
        CharClass characterClass = CACHE.get(identity);
        if (characterClass != null) {
            return characterClass;
        }
        characterClass = supplier.get();
        CharClass previous = CACHE.putIfAbsent(identity, characterClass);
        return previous != null ? previous : characterClass;
    }

    private static CharClass union(CharClass... classes)
    {
        CharClassBuilder builder = new CharClassBuilder();
        for (CharClass characterClass : classes) {
            builder.addCharClass(builder(characterClass));
        }
        return builder.toCharClass();
    }

    private static CharClass difference(CharClass characterClass, CharClass excluded)
    {
        CharClassBuilder builder = builder(characterClass);
        CharClassBuilder retained = builder(excluded);
        retained.negate();
        builder.retainAll(retained);
        return builder.toCharClass();
    }

    private static CharClassBuilder builder(CharClass characterClass)
    {
        CharClassBuilder builder = new CharClassBuilder();
        for (RuneRange range : characterClass.rangeView()) {
            builder.addRange(range.low(), range.high());
        }
        return builder;
    }

    // Builds a class from pairs of first and last code point.
    private static CharClass codePoints(int... ranges)
    {
        CharClassBuilder builder = new CharClassBuilder();
        addRanges(builder, ranges);
        return builder.toCharClass();
    }

    private static void addRanges(CharClassBuilder builder, int[] ranges)
    {
        for (int index = 0; index < ranges.length; index += 2) {
            builder.addRange(ranges[index], ranges[index + 1]);
        }
    }

    // The word property of java.util.regex on the executing JVM, which the match-time word
    // boundaries of Java and Trino read.
    static boolean isWord(int codePoint)
    {
        int type = Character.getType(codePoint);
        return Character.isAlphabetic(codePoint) ||
                type == Character.NON_SPACING_MARK ||
                type == Character.ENCLOSING_MARK ||
                type == Character.COMBINING_SPACING_MARK ||
                type == Character.DECIMAL_DIGIT_NUMBER ||
                type == Character.CONNECTOR_PUNCTUATION ||
                codePoint == 0x200C || codePoint == 0x200D;
    }

    private static final class ScriptNames
    {
        private static final List<String> NAMES;
        private static final Map<String, String> BY_UPPERCASE_NAME;

        static {
            List<String> names = new ArrayList<>(UnicodeDatabase.scripts().names());
            names.add(UNKNOWN_SCRIPT);
            NAMES = List.copyOf(names);

            Map<String, String> byUppercaseName = new HashMap<>();
            for (String name : NAMES) {
                byUppercaseName.put(name.toUpperCase(Locale.ENGLISH), name);
            }
            // A short name of a script that Scripts.txt assigns no code point, such as Hrkt, names
            // no Java script.
            UnicodeDatabase.scriptShortNames().forEach((shortName, longName) -> {
                if (NAMES.contains(longName)) {
                    byUppercaseName.put(shortName.toUpperCase(Locale.ENGLISH), longName);
                }
            });
            BY_UPPERCASE_NAME = Map.copyOf(byUppercaseName);
        }
    }

    private static final class BlockNames
    {
        private static final Map<String, String> BY_KEY;

        static {
            Map<String, String> byKey = new HashMap<>();
            for (String block : UnicodeDatabase.blocks().names()) {
                String identifierName = EARLIER_BLOCK_NAMES.getOrDefault(block, block);
                byKey.put(identifierName.toUpperCase(Locale.US).replace(' ', '_').replace('-', '_'), block);
                for (String name : List.of(block, identifierName)) {
                    String uppercaseName = name.toUpperCase(Locale.US);
                    byKey.put(uppercaseName, block);
                    byKey.put(uppercaseName.replace(" ", ""), block);
                }
            }
            byKey.put(SURROGATES_AREA, SURROGATES_AREA);
            BY_KEY = Map.copyOf(byKey);
        }
    }
}
