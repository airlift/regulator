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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static io.airlift.regulator.CharClass.RUNEMAX;
import static java.util.Objects.requireNonNull;

/**
 * Builds Unicode property classes from the pinned {@link UnicodeDatabase} and caches them as
 * immutable range sets. These are the Unicode lookups every language shares; each language
 * resolves its own property names to them. RE2 reads the raw property values of the database,
 * and {@link JavaUnicodeProperties} resolves the Java and Trino property names.
 */
final class UnicodeGroups
{
    private static final ConcurrentHashMap<String, CharClass> CACHE = new ConcurrentHashMap<>();

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
     * Returns the code points of the General_Category value or grouping with the given UAX #44
     * short or long name, such as Lu or Uppercase_Letter, or null if no value or grouping has
     * that name. Unassigned, Cn, holds every code point that UnicodeData.txt does not assign, and
     * the groupings, such as L for Lu, Ll, Lt, Lm, and Lo, and LC for Lu, Ll, and Lt, hold their
     * values' code points; Other, C, includes Cn.
     */
    static CharClass generalCategory(String name)
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
            case "Mc", "Spacing_Mark" -> categories("Mc");
            case "Nd", "Decimal_Number" -> categories("Nd");
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
            case "Ps", "Open_Punctuation" -> categories("Ps");
            case "Pe", "Close_Punctuation" -> categories("Pe");
            case "Pc", "Connector_Punctuation" -> categories("Pc");
            case "Po", "Other_Punctuation" -> categories("Po");
            case "Sm", "Math_Symbol" -> categories("Sm");
            case "Sc", "Currency_Symbol" -> categories("Sc");
            case "Sk", "Modifier_Symbol" -> categories("Sk");
            case "So", "Other_Symbol" -> categories("So");
            case "Pi", "Initial_Punctuation" -> categories("Pi");
            case "Pf", "Final_Punctuation" -> categories("Pf");
            case "L", "Letter" -> majorClass('L');
            case "LC", "Cased_Letter" -> categories("Ll", "Lt", "Lu");
            case "M", "Mark" -> majorClass('M');
            case "N", "Number" -> majorClass('N');
            case "Z", "Separator" -> majorClass('Z');
            case "C", "Other" -> cached("gc:Cc|Cf|Cn|Co|Cs", () -> union(majorClass('C'), unassigned()));
            case "P", "Punctuation" -> majorClass('P');
            case "S", "Symbol" -> majorClass('S');
            default -> null;
        };
    }

    /**
     * Returns the code points that UnicodeData.txt assigns any General_Category value.
     */
    static CharClass assigned()
    {
        return requireNonNull(generalCategories(generalCategoryValues()), "missing General_Category value");
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
     * Returns the word characters of UTS #18 (Unicode Regular Expressions, Annex C): Alphabetic,
     * marks, Decimal_Number, Connector_Punctuation, and Join_Control. This is the class of Java's
     * {@code \p{IsWord}}.
     */
    static CharClass word()
    {
        return cached("java:Word", () -> union(
                alphabetic(),
                categories("Mc", "Me", "Mn", "Nd", "Pc"),
                binary("Join_Control")));
    }

    /**
     * Returns the Alphabetic code points by the formula of {@code Character.isAlphabetic}: Lu, Ll,
     * Lt, Lm, Lo, Nl, or Other_Alphabetic.
     */
    static CharClass alphabetic()
    {
        return cached("java:Alphabetic", () -> union(majorClass('L'), categories("Nl"), binary("Other_Alphabetic")));
    }

    /**
     * Returns the code points that the database gives the binary property with the given name,
     * which it must hold, such as Hex_Digit.
     */
    static CharClass binary(String property)
    {
        int[] ranges = requireNonNull(UnicodeDatabase.binaryProperties().ranges(property), () -> "missing binary property " + property);
        return cached("binary:" + property, () -> codePoints(ranges));
    }

    private static CharClass categories(String... values)
    {
        return requireNonNull(generalCategories(List.of(values)), "missing General_Category value");
    }

    private static CharClass unassigned()
    {
        return cached("gc:Cn", () -> {
            CharClassBuilder builder = builder(assigned());
            builder.negate();
            return builder.toCharClass();
        });
    }

    /**
     * Returns the class cached under {@code identity}, building it with {@code supplier} if none
     * is, so each class is built once however it is named.
     */
    static CharClass cached(String identity, Supplier<CharClass> supplier)
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

    static CharClass union(CharClass... classes)
    {
        CharClassBuilder builder = new CharClassBuilder();
        for (CharClass characterClass : classes) {
            builder.addCharClass(builder(characterClass));
        }
        return builder.toCharClass();
    }

    static CharClass difference(CharClass characterClass, CharClass excluded)
    {
        CharClassBuilder builder = builder(characterClass);
        CharClassBuilder retained = builder(excluded);
        retained.negate();
        builder.retainAll(retained);
        return builder.toCharClass();
    }

    static CharClassBuilder builder(CharClass characterClass)
    {
        CharClassBuilder builder = new CharClassBuilder();
        for (RuneRange range : characterClass.rangeView()) {
            builder.addRange(range.low(), range.high());
        }
        return builder;
    }

    // Builds a class from pairs of first and last code point.
    static CharClass codePoints(int... ranges)
    {
        CharClassBuilder builder = new CharClassBuilder();
        addRanges(builder, ranges);
        return builder.toCharClass();
    }

    static void addRanges(CharClassBuilder builder, int[] ranges)
    {
        for (int index = 0; index < ranges.length; index += 2) {
            builder.addRange(ranges[index], ranges[index + 1]);
        }
    }

    /**
     * Builds the table that Java and Trino word boundaries read, if no boundary has yet, so that
     * compiling a pattern with such a boundary, not its first match, reads the database.
     */
    static void initializeWordFlags()
    {
        requireNonNull(WordFlags.FLAGS, "word flags are null");
    }

    /**
     * Returns whether {@code codePoint}, from 0 through {@code RUNEMAX}, is a word character of
     * {@code java.util.regex} with {@code UNICODE_CHARACTER_CLASS}, the class of
     * {@code \p{IsWord}}, which Java's Unicode word boundaries read.
     */
    static boolean isWord(int codePoint)
    {
        return (WordFlags.FLAGS.get(codePoint) & WordFlags.JAVA_WORD) != 0;
    }

    /**
     * Returns whether {@code codePoint}, from 0 through {@code RUNEMAX}, is a word character of
     * Trino's word boundaries.
     */
    static boolean isTrinoWord(int codePoint)
    {
        return (WordFlags.FLAGS.get(codePoint) & WordFlags.TRINO_WORD) != 0;
    }

    /**
     * Returns whether {@code codePoint}, from 0 through {@code RUNEMAX}, is a nonspacing mark,
     * {@code Mn}, which Java's word boundaries attach to a preceding letter or digit.
     */
    static boolean isNonspacingMark(int codePoint)
    {
        return (WordFlags.FLAGS.get(codePoint) & WordFlags.NONSPACING_MARK) != 0;
    }

    /**
     * Returns whether {@code codePoint}, from 0 through {@code RUNEMAX}, is a letter or decimal
     * digit, as {@code Character.isLetterOrDigit} defines it, which can give a nonspacing mark a
     * base in Java's word boundaries.
     */
    static boolean isLetterOrDigit(int codePoint)
    {
        return (WordFlags.FLAGS.get(codePoint) & WordFlags.LETTER_OR_DIGIT) != 0;
    }

    // The classes that match-time word boundaries read for each input code point, as the flags of
    // one table, built when the first pattern with such a boundary compiles rather than by each
    // pattern.
    private static final class WordFlags
    {
        private static final int JAVA_WORD = 1;
        private static final int TRINO_WORD = 1 << 1;
        private static final int NONSPACING_MARK = 1 << 2;
        private static final int LETTER_OR_DIGIT = 1 << 3;

        private static final CodePointFlags FLAGS = new CodePointFlags(List.of(
                word(),
                // Trino's word characters are Java's without Join_Control, plus six superscript and
                // fraction numbers that Joni's Java syntax includes.
                union(alphabetic(), categories("Mc", "Me", "Mn", "Nd", "Pc"), codePoints(0xB2, 0xB3, 0xB9, 0xB9, 0xBC, 0xBE)),
                categories("Mn"),
                // Character.isLetterOrDigit: Lu, Ll, Lt, Lm, Lo, or Nd.
                categories("Ll", "Lm", "Lo", "Lt", "Lu", "Nd")));
    }
}
