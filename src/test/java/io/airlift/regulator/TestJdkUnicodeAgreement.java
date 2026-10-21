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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

import static io.airlift.regulator.CharClass.RUNEMAX;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.UNICODE_CASE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks Java and Trino properties, word boundaries, Java character names, name case conversions,
 * and Java case classes against the running JDK. Inputs and case-mapping outputs newer than the
 * JDK's Unicode version are excluded. {@code EXPECTED_DIFFERENCES} lists allowed changes to older
 * characters.
 */
public class TestJdkUnicodeAgreement
{
    // The Age values of the database, oldest first.
    private static final List<String> AGES = UnicodeDatabase.ages().names().stream()
            .sorted(Comparator.comparingInt(TestJdkUnicodeAgreement::versionKey))
            .toList();

    // The Unicode version of the running JDK, which the Character javadoc names, such as 16.0 for
    // Java SE 25 and 17.0 for Java SE 26: the newest Age whose assigned code points the JDK all
    // defines.
    private static final String JDK_UNICODE_VERSION = jdkUnicodeVersion();

    // The code points whose Age is after the JDK's Unicode version, which the JDK does not assign.
    private static final BitSet NEWER = codePointsOfAges(AGES.subList(AGES.indexOf(JDK_UNICODE_VERSION) + 1, AGES.size()));

    // The code points whose Age is at most the JDK's Unicode version.
    private static final BitSet ASSIGNED_BY_JDK = codePointsOfAges(AGES.subList(0, AGES.indexOf(JDK_UNICODE_VERSION) + 1));

    // Changes Unicode made to older characters after the JDK's version, and how many code points
    // each changes.
    private static final List<ExpectedDifference> EXPECTED_DIFFERENCES = List.of(
            // Unicode 17.0 changed U+0295 LATIN LETTER PHARYNGEAL VOICED FRICATIVE from Ll to Lo.
            new ExpectedDifference("17.0", Set.of("Ll", "Lo", "LC", "IsLowercase", "javaLowerCase"), 1, codePoint -> codePoint == 0x0295),
            // Unicode 17.0 removed Extended_Pictographic from 689 code points that are not emoji,
            // such as U+2388 HELM SYMBOL and U+2605 BLACK STAR. The JDK assigns 672 of them; the
            // other 17 were unassigned.
            new ExpectedDifference("17.0", Set.of("IsExtended_Pictographic"), 672, codePoint -> !JavaUnicodeProperties.lookup("IsExtended_Pictographic").contains(codePoint)));

    private static final List<Category> CATEGORIES = List.of(
            new Category("Cn", Character.UNASSIGNED),
            new Category("Lu", Character.UPPERCASE_LETTER),
            new Category("Ll", Character.LOWERCASE_LETTER),
            new Category("Lt", Character.TITLECASE_LETTER),
            new Category("Lm", Character.MODIFIER_LETTER),
            new Category("Lo", Character.OTHER_LETTER),
            new Category("Mn", Character.NON_SPACING_MARK),
            new Category("Me", Character.ENCLOSING_MARK),
            new Category("Mc", Character.COMBINING_SPACING_MARK),
            new Category("Nd", Character.DECIMAL_DIGIT_NUMBER),
            new Category("Nl", Character.LETTER_NUMBER),
            new Category("No", Character.OTHER_NUMBER),
            new Category("Zs", Character.SPACE_SEPARATOR),
            new Category("Zl", Character.LINE_SEPARATOR),
            new Category("Zp", Character.PARAGRAPH_SEPARATOR),
            new Category("Cc", Character.CONTROL),
            new Category("Cf", Character.FORMAT),
            new Category("Co", Character.PRIVATE_USE),
            new Category("Cs", Character.SURROGATE),
            new Category("Pd", Character.DASH_PUNCTUATION),
            new Category("Ps", Character.START_PUNCTUATION),
            new Category("Pe", Character.END_PUNCTUATION),
            new Category("Pc", Character.CONNECTOR_PUNCTUATION),
            new Category("Po", Character.OTHER_PUNCTUATION),
            new Category("Sm", Character.MATH_SYMBOL),
            new Category("Sc", Character.CURRENCY_SYMBOL),
            new Category("Sk", Character.MODIFIER_SYMBOL),
            new Category("So", Character.OTHER_SYMBOL),
            new Category("Pi", Character.INITIAL_QUOTE_PUNCTUATION),
            new Category("Pf", Character.FINAL_QUOTE_PUNCTUATION));

    private static final List<CategoryGroup> CATEGORY_GROUPS = List.of(
            new CategoryGroup("L", type -> type >= Character.UPPERCASE_LETTER && type <= Character.OTHER_LETTER),
            new CategoryGroup("LC", type -> type >= Character.UPPERCASE_LETTER && type <= Character.TITLECASE_LETTER),
            new CategoryGroup("LD", type -> type >= Character.UPPERCASE_LETTER && type <= Character.OTHER_LETTER || type == Character.DECIMAL_DIGIT_NUMBER),
            new CategoryGroup("M", type -> type >= Character.NON_SPACING_MARK && type <= Character.COMBINING_SPACING_MARK),
            new CategoryGroup("N", type -> type >= Character.DECIMAL_DIGIT_NUMBER && type <= Character.OTHER_NUMBER),
            new CategoryGroup("Z", type -> type >= Character.SPACE_SEPARATOR && type <= Character.PARAGRAPH_SEPARATOR),
            new CategoryGroup("C", type -> type == Character.CONTROL || type == Character.FORMAT ||
                    type == Character.SURROGATE || type == Character.PRIVATE_USE || type == Character.UNASSIGNED),
            new CategoryGroup("P", type -> type >= Character.DASH_PUNCTUATION && type <= Character.OTHER_PUNCTUATION ||
                    type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION),
            new CategoryGroup("S", type -> type >= Character.MATH_SYMBOL && type <= Character.OTHER_SYMBOL));

    private static final List<PropertyExpectation> BINARY_PROPERTIES = List.of(
            new PropertyExpectation("IsAlphabetic", Character::isAlphabetic),
            new PropertyExpectation("IsAssigned", Character::isDefined),
            new PropertyExpectation("IsControl", codePoint -> Character.getType(codePoint) == Character.CONTROL),
            new PropertyExpectation("IsDigit", Character::isDigit),
            new PropertyExpectation("IsEmoji", Character::isEmoji),
            new PropertyExpectation("IsEmoji_Component", Character::isEmojiComponent),
            new PropertyExpectation("IsEmoji_Modifier", Character::isEmojiModifier),
            new PropertyExpectation("IsEmoji_Modifier_Base", Character::isEmojiModifierBase),
            new PropertyExpectation("IsEmoji_Presentation", Character::isEmojiPresentation),
            new PropertyExpectation("IsExtended_Pictographic", Character::isExtendedPictographic),
            new PropertyExpectation("IsHex_Digit", TestJdkUnicodeAgreement::isHexDigit),
            new PropertyExpectation("IsIdeographic", Character::isIdeographic),
            new PropertyExpectation("IsJoin_Control", codePoint -> codePoint == 0x200C || codePoint == 0x200D),
            new PropertyExpectation("IsLetter", Character::isLetter),
            new PropertyExpectation("IsLowercase", Character::isLowerCase),
            new PropertyExpectation("IsNoncharacter_Code_Point", TestJdkUnicodeAgreement::isNoncharacterCodePoint),
            new PropertyExpectation("IsPunctuation", TestJdkUnicodeAgreement::isPunctuation),
            new PropertyExpectation("IsTitlecase", Character::isTitleCase),
            new PropertyExpectation("IsUppercase", Character::isUpperCase),
            new PropertyExpectation("IsWhite_Space", TestJdkUnicodeAgreement::isWhiteSpace),
            new PropertyExpectation("IsWord", TestJdkUnicodeAgreement::isWord),
            new PropertyExpectation("IsAlnum", codePoint -> Character.isAlphabetic(codePoint) || Character.isDigit(codePoint)),
            new PropertyExpectation("IsBlank", codePoint -> Character.getType(codePoint) == Character.SPACE_SEPARATOR || codePoint == '\t'),
            new PropertyExpectation("IsGraph", TestJdkUnicodeAgreement::isGraph),
            new PropertyExpectation("IsPrint", codePoint -> isGraph(codePoint) ||
                    (Character.getType(codePoint) == Character.SPACE_SEPARATOR || codePoint == '\t') &&
                            Character.getType(codePoint) != Character.CONTROL));

    private static final List<PropertyExpectation> JAVA_PROPERTIES = List.of(
            new PropertyExpectation("javaLowerCase", Character::isLowerCase),
            new PropertyExpectation("javaUpperCase", Character::isUpperCase),
            new PropertyExpectation("javaAlphabetic", Character::isAlphabetic),
            new PropertyExpectation("javaIdeographic", Character::isIdeographic),
            new PropertyExpectation("javaTitleCase", Character::isTitleCase),
            new PropertyExpectation("javaDigit", Character::isDigit),
            new PropertyExpectation("javaDefined", Character::isDefined),
            new PropertyExpectation("javaLetter", Character::isLetter),
            new PropertyExpectation("javaLetterOrDigit", Character::isLetterOrDigit),
            new PropertyExpectation("javaJavaIdentifierStart", Character::isJavaIdentifierStart),
            new PropertyExpectation("javaJavaIdentifierPart", Character::isJavaIdentifierPart),
            new PropertyExpectation("javaUnicodeIdentifierStart", Character::isUnicodeIdentifierStart),
            new PropertyExpectation("javaUnicodeIdentifierPart", Character::isUnicodeIdentifierPart),
            new PropertyExpectation("javaIdentifierIgnorable", Character::isIdentifierIgnorable),
            new PropertyExpectation("javaSpaceChar", Character::isSpaceChar),
            new PropertyExpectation("javaWhitespace", Character::isWhitespace),
            new PropertyExpectation("javaISOControl", Character::isISOControl),
            new PropertyExpectation("javaMirrored", Character::isMirrored));

    @Test
    public void testGeneralCategoriesAgreeWithCharacterType()
    {
        for (Category category : CATEGORIES) {
            assertRanges(JavaUnicodeProperties.lookup(category.name()), codePoint -> Character.getType(codePoint) == category.type(), category.name());
        }
        for (CategoryGroup categoryGroup : CATEGORY_GROUPS) {
            assertRanges(JavaUnicodeProperties.lookup(categoryGroup.name()), codePoint -> categoryGroup.types().test(Character.getType(codePoint)), categoryGroup.name());
        }
    }

    @Test
    public void testScriptsAgreeWithCharacterUnicodeScript()
    {
        for (Character.UnicodeScript script : Character.UnicodeScript.values()) {
            assertRanges(JavaUnicodeProperties.lookup(script.name()), codePoint -> Character.UnicodeScript.of(codePoint) == script, script.name());
        }

        // A short or long name that UnicodeScript.forName rejects names a script Unicode added
        // after the JDK's version.
        List<String> names = new ArrayList<>(JavaUnicodeProperties.scriptNames());
        names.addAll(UnicodeDatabase.scriptShortNames().keySet());
        for (String name : names) {
            for (String spelling : List.of(name, name.toLowerCase(Locale.ROOT), name.replace("_", ""))) {
                assertSameClassAsJdk(spelling, jdkScript(spelling), JavaUnicodeProperties.lookup("script=" + spelling));
            }
        }
    }

    @Test
    public void testBlocksAgreeWithCharacterUnicodeBlock()
    {
        List<Character.UnicodeBlock> blocks = Arrays.stream(Character.UnicodeBlock.class.getFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getType() == Character.UnicodeBlock.class)
                .map(field -> {
                    try {
                        return (Character.UnicodeBlock) field.get(null);
                    }
                    catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                })
                .distinct()
                .sorted(Comparator.comparing(Object::toString))
                .toList();
        for (Character.UnicodeBlock block : blocks) {
            String propertyName = "In" + block;
            assertRanges(JavaUnicodeProperties.lookup(propertyName), codePoint -> Character.UnicodeBlock.of(codePoint) == block, propertyName);
        }

        // A spelling of a Blocks.txt name that UnicodeBlock.forName rejects is a block Unicode added
        // after the JDK's version, or a spelling both reject.
        for (String name : UnicodeDatabase.blocks().names()) {
            String uppercaseName = name.toUpperCase(Locale.ROOT);
            for (String spelling : List.of(name, uppercaseName, name.replace(" ", ""), uppercaseName.replace(' ', '_').replace('-', '_'))) {
                assertSameClassAsJdk(spelling, jdkBlock(spelling), JavaUnicodeProperties.lookup("block=" + spelling));
            }
        }
    }

    @Test
    public void testBinaryPropertiesAgreeWithJdkPredicates()
    {
        for (PropertyExpectation property : BINARY_PROPERTIES) {
            assertRanges(JavaUnicodeProperties.lookup(property.name()), property.predicate(), property.name());
        }
    }

    @Test
    public void testJavaPropertiesAgreeWithCharacterPredicates()
    {
        for (PropertyExpectation property : JAVA_PROPERTIES) {
            assertRanges(JavaUnicodeProperties.lookup(property.name()), property.predicate(), property.name());
        }
    }

    // The word classes that match-time boundaries read for each input code point.
    @Test
    public void testWordBoundaryClassesAgreeWithCharacterPredicates()
    {
        assertAgreement(UnicodeGroups::isWord, TestJdkUnicodeAgreement::isWord, "Java word");
        assertAgreement(EmptyOp::isUnicodeWord, TestJdkUnicodeAgreement::isTrinoWord, "Trino word");
        assertAgreement(UnicodeGroups::isNonspacingMark, codePoint -> Character.getType(codePoint) == Character.NON_SPACING_MARK, "Mn");
        assertAgreement(UnicodeGroups::isLetterOrDigit, Character::isLetterOrDigit, "javaLetterOrDigit");
    }

    // Every name that Java's \N{name} resolves is the JDK's name for its code point, and both
    // resolve it.
    @Test
    public void testCharacterNamesAgreeWithCharacter()
    {
        for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
            if (NEWER.get(codePoint)) {
                continue;
            }
            String name = JavaCharacterNames.getName(codePoint);
            String jdkName = Character.getName(codePoint);
            if (!Objects.equals(name, jdkName)) {
                throw new AssertionError(String.format(Locale.ROOT, "U+%04X is named %s, not %s as in the JDK's Unicode %s", codePoint, name, jdkName, JDK_UNICODE_VERSION));
            }
            if (name != null && (JavaCharacterNames.codePointOf(name) != codePoint || Character.codePointOf(name) != codePoint)) {
                throw new AssertionError(String.format(Locale.ROOT, "%s does not name U+%04X", name, codePoint));
            }
        }

        // Character.codePointOf trims characters up to U+0020 and uppercases as the root locale
        // does, which takes some non-ASCII letters to ASCII, but accepts neither aliases, such as
        // LINE FEED, nor the names Unicode derives for ranges, such as CJK UNIFIED IDEOGRAPH-4E00.
        for (String name : List.of(
                " latin small letter a\t",
                "Latin Small Letter A",
                "LATIN SMALL LETTER  A",
                "\u00A0LATIN SMALL LETTER A",
                "lat\u0131n small letter a",
                "LAT\u0130N SMALL LETTER A",
                "\u017Fpace",
                "CRO\u00DF MARK",
                "\uFB01RE",
                "\u212Aelvin sign",
                "line feed (lf)",
                "LINE FEED",
                "BEL",
                "BELL",
                "PADDING CHARACTER",
                "latin 1 supplement 84",
                "BASIC LATIN 41",
                "cjk unified ideographs 4e00",
                "CJK UNIFIED IDEOGRAPHS 04E00",
                "CJK UNIFIED IDEOGRAPHS +4E00",
                "CJK UNIFIED IDEOGRAPH-4E00",
                "cjk compatibility ideograph-f900",
                "TANGUT COMPONENT-001",
                "HIGH SURROGATES D800",
                "HANGUL SYLLABLE GA",
                "GREEK 0378",
                "",
                " ")) {
            assertThat(JavaCharacterNames.codePointOf(name)).as(name).isEqualTo(jdkCodePointOf(name));
        }
    }

    // The parsers normalize property and character names with the case conversions of String and
    // Character without a locale, from the database's mappings. A mapping Unicode added after the
    // JDK's version can map an older character to a newer one.
    @Test
    public void testCaseConversionsAgreeWithString()
    {
        for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
            String value = Character.toString(codePoint);
            String uppercase = UnicodeCase.toUpperCase(value);
            String lowercase = UnicodeCase.toLowerCase(value);
            if (NEWER.get(codePoint) || hasNewer(uppercase) || hasNewer(lowercase)) {
                continue;
            }
            if (!uppercase.equals(value.toUpperCase(Locale.ROOT)) ||
                    !lowercase.equals(value.toLowerCase(Locale.ROOT)) ||
                    UnicodeCase.toUpperCase(codePoint) != Character.toUpperCase(codePoint)) {
                throw new AssertionError(String.format(Locale.ROOT, "U+%04X converts case unlike the JDK's Unicode %s", codePoint, JDK_UNICODE_VERSION));
            }
            for (int other : new int[] {Character.toUpperCase(codePoint), Character.toLowerCase(codePoint), 'I', 'K', 'S'}) {
                String otherValue = Character.toString(other);
                if (UnicodeCase.equalsIgnoreCase(value, otherValue) != value.equalsIgnoreCase(otherValue)) {
                    throw new AssertionError(String.format(Locale.ROOT, "U+%04X and U+%04X compare ignoring case unlike the JDK's Unicode %s", codePoint, other, JDK_UNICODE_VERSION));
                }
            }
        }
    }

    private static boolean hasNewer(String value)
    {
        return value.codePoints().anyMatch(NEWER::get);
    }

    private static int jdkCodePointOf(String name)
    {
        try {
            return Character.codePointOf(name);
        }
        catch (IllegalArgumentException e) {
            return -1;
        }
    }

    @Test
    public void testJavaPropertyAliases()
    {
        assertThat(JavaUnicodeProperties.lookup("InBasic_Latin"))
                .isSameAs(JavaUnicodeProperties.lookup("block=Basic_Latin"));
        assertThat(JavaUnicodeProperties.lookup("Greek"))
                .isSameAs(JavaUnicodeProperties.lookup("script=Greek"))
                .isSameAs(JavaUnicodeProperties.lookup("IsGreek"));
        assertThat(JavaUnicodeProperties.lookup("Lu"))
                .isSameAs(JavaUnicodeProperties.lookup("Uppercase_Letter"))
                .isSameAs(JavaUnicodeProperties.lookup("general_category=Lu"));
        assertThat(JavaUnicodeProperties.lookup("IsAlphabetic"))
                .isSameAs(JavaUnicodeProperties.lookup("IsAlpha"));
        assertThat(JavaUnicodeProperties.lookup("IsControl"))
                .isSameAs(JavaUnicodeProperties.lookup("IsCntrl"));
        assertThat(JavaUnicodeProperties.lookup("IsHex_Digit"))
                .isSameAs(JavaUnicodeProperties.lookup("IsXDigit"));
        assertThat(JavaUnicodeProperties.lookup("IsLowercase"))
                .isSameAs(JavaUnicodeProperties.lookup("IsLower"));
        assertThat(JavaUnicodeProperties.lookup("IsPunctuation"))
                .isSameAs(JavaUnicodeProperties.lookup("IsPunct"));
        assertThat(JavaUnicodeProperties.lookup("IsUppercase"))
                .isSameAs(JavaUnicodeProperties.lookup("IsUpper"));
        assertThat(JavaUnicodeProperties.lookup("IsWhite_Space"))
                .isSameAs(JavaUnicodeProperties.lookup("IsSpace"));
        assertThat(JavaUnicodeProperties.lookup("Not_A_Unicode_Property")).isNull();
    }

    // Java folds as the JVM's case classes do, but from the database's case mappings. Unicode keeps
    // the case mappings of an assigned character, but a newer character can join the orbit of an
    // older one, so the two agree wherever the JVM assigns every member of the orbit. The database's
    // Unicode version is at least that of every supported JVM.
    @Test
    public void testCaseFoldingMatchesJvmCaseClasses()
    {
        for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
            if (orbitAssignedByJvm(codePoint) && CaseFolding.JAVA.simpleFold(codePoint) != canonicalCaseFold(codePoint)) {
                throw new AssertionError(String.format(Locale.ROOT, "%s: wrong simple fold for U+%04X", CaseFolding.JAVA, codePoint));
            }
        }
    }

    private static boolean orbitAssignedByJvm(int codePoint)
    {
        int member = codePoint;
        do {
            if (!Character.isDefined(member)) {
                return false;
            }
            member = CaseFolding.JAVA.cycleFoldRune(member);
        }
        while (member != codePoint);
        return true;
    }

    @Test
    public void testFocusedCaseFoldingMatchesJavaPattern()
    {
        assertCaseEquivalent('I', 'i', 0x0130, 0x0131);
        assertCaseEquivalent('S', 's', 0x017F);
        assertCaseEquivalent('K', 'k', 0x212A);
        assertCaseEquivalent(0x03A3, 0x03C3, 0x03C2);
    }

    private static void assertCaseEquivalent(int... codePoints)
    {
        for (int patternCodePoint : codePoints) {
            Pattern pattern = Pattern.compile(new String(Character.toChars(patternCodePoint)), CASE_INSENSITIVE | UNICODE_CASE);
            for (int inputCodePoint : codePoints) {
                boolean javaMatches = pattern.matcher(new String(Character.toChars(inputCodePoint))).matches();
                assertThat(inFoldCycle(patternCodePoint, inputCodePoint))
                        .as("U+%04X against U+%04X", patternCodePoint, inputCodePoint)
                        .isEqualTo(javaMatches);
            }
        }
    }

    private static boolean inFoldCycle(int initialCodePoint, int candidateCodePoint)
    {
        int codePoint = initialCodePoint;
        do {
            if (codePoint == candidateCodePoint) {
                return true;
            }
            codePoint = CaseFolding.JAVA.cycleFoldRune(codePoint);
        }
        while (codePoint != initialCodePoint);
        return false;
    }

    private static int canonicalCaseFold(int codePoint)
    {
        return Character.toLowerCase(Character.toUpperCase(codePoint));
    }

    private static String jdkUnicodeVersion()
    {
        String version = null;
        for (String age : AGES) {
            int[] ranges = UnicodeDatabase.ages().ranges(age);
            for (int index = 0; index < ranges.length; index += 2) {
                for (int codePoint = ranges[index]; codePoint <= ranges[index + 1]; codePoint++) {
                    // Noncharacters have an Age but no General_Category.
                    if (!isNoncharacterCodePoint(codePoint) && !Character.isDefined(codePoint)) {
                        assertThat(version).as("the JDK's Unicode version").isNotNull();
                        return version;
                    }
                }
            }
            version = age;
        }
        return version;
    }

    private static BitSet codePointsOfAges(List<String> ages)
    {
        BitSet codePoints = new BitSet();
        for (String age : ages) {
            int[] ranges = UnicodeDatabase.ages().ranges(age);
            for (int index = 0; index < ranges.length; index += 2) {
                codePoints.set(ranges[index], ranges[index + 1] + 1);
            }
        }
        return codePoints;
    }

    // Orders Age values, which are a major and a minor version, such as 15.1.
    private static int versionKey(String version)
    {
        int dot = version.indexOf('.');
        return Integer.parseInt(version.substring(0, dot)) * 1000 + Integer.parseInt(version.substring(dot + 1));
    }

    private static boolean newerThanJdk(String version)
    {
        return versionKey(JDK_UNICODE_VERSION) < versionKey(version);
    }

    private static Character.UnicodeScript jdkScript(String name)
    {
        try {
            return Character.UnicodeScript.forName(name);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Character.UnicodeBlock jdkBlock(String name)
    {
        try {
            return Character.UnicodeBlock.forName(name);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    // A name the JDK accepts names the class of the JDK's own name for it; a name only the database
    // knows holds no code point that the JDK's Unicode version assigns.
    private static void assertSameClassAsJdk(String name, Object jdkValue, CharClass characterClass)
    {
        if (jdkValue != null) {
            String jdkName = jdkValue instanceof Character.UnicodeBlock ? "In" + jdkValue : "sc=" + jdkValue;
            assertThat(characterClass).as(name).isSameAs(JavaUnicodeProperties.lookup(jdkName));
            return;
        }
        if (characterClass != null) {
            for (RuneRange range : characterClass.rangeView()) {
                int codePoint = ASSIGNED_BY_JDK.nextSetBit(range.low());
                if (codePoint >= 0 && codePoint <= range.high()) {
                    throw new AssertionError(String.format(Locale.ROOT, "the JDK rejects %s, which holds U+%04X of Unicode %s", name, codePoint, JDK_UNICODE_VERSION));
                }
            }
        }
    }

    private static boolean isHexDigit(int codePoint)
    {
        return Character.isDigit(codePoint) ||
                codePoint >= 'A' && codePoint <= 'F' ||
                codePoint >= 'a' && codePoint <= 'f' ||
                codePoint >= 0xFF21 && codePoint <= 0xFF26 ||
                codePoint >= 0xFF41 && codePoint <= 0xFF46;
    }

    private static boolean isNoncharacterCodePoint(int codePoint)
    {
        return (codePoint & 0xFFFE) == 0xFFFE || codePoint >= 0xFDD0 && codePoint <= 0xFDEF;
    }

    private static boolean isPunctuation(int codePoint)
    {
        int type = Character.getType(codePoint);
        return type >= Character.DASH_PUNCTUATION && type <= Character.OTHER_PUNCTUATION ||
                type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION;
    }

    private static boolean isWhiteSpace(int codePoint)
    {
        int type = Character.getType(codePoint);
        return type >= Character.SPACE_SEPARATOR && type <= Character.PARAGRAPH_SEPARATOR ||
                codePoint >= '\t' && codePoint <= '\r' || codePoint == 0x85;
    }

    private static boolean isWord(int codePoint)
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

    // Java's word characters without Join_Control, plus six superscript and fraction numbers that
    // Joni's Java syntax includes.
    private static boolean isTrinoWord(int codePoint)
    {
        return isWord(codePoint) && codePoint != 0x200C && codePoint != 0x200D ||
                codePoint == 0xB2 || codePoint == 0xB3 || codePoint == 0xB9 || codePoint >= 0xBC && codePoint <= 0xBE;
    }

    private static boolean isGraph(int codePoint)
    {
        int type = Character.getType(codePoint);
        return type != Character.SPACE_SEPARATOR &&
                type != Character.LINE_SEPARATOR &&
                type != Character.PARAGRAPH_SEPARATOR &&
                type != Character.CONTROL &&
                type != Character.SURROGATE &&
                type != Character.UNASSIGNED;
    }

    private static void assertRanges(CharClass characterClass, IntPredicate expected, String name)
    {
        assertThat(characterClass).as(name).isNotNull();
        assertAgreement(characterClass::contains, expected, name);
    }

    // Compares a set of code points with the JDK at every code point that is not newer than the
    // JDK's Unicode version, apart from the expected differences of the property, each of which
    // must excuse exactly its count of code points.
    private static void assertAgreement(IntPredicate actualCodePoints, IntPredicate expected, String name)
    {
        List<ExpectedDifference> differences = EXPECTED_DIFFERENCES.stream()
                .filter(difference -> difference.properties().contains(name) && newerThanJdk(difference.since()))
                .toList();
        int[] excusedCounts = new int[differences.size()];
        for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
            boolean actual = actualCodePoints.test(codePoint);
            if (actual != expected.test(codePoint) && !NEWER.get(codePoint)) {
                int difference = excusingDifference(differences, codePoint);
                if (difference < 0) {
                    throw new AssertionError(String.format(Locale.ROOT, "%s U+%04X %s, unlike the JDK's Unicode %s", actual ? "unexpected" : "missing", codePoint, name, JDK_UNICODE_VERSION));
                }
                excusedCounts[difference]++;
            }
        }
        for (int difference = 0; difference < differences.size(); difference++) {
            assertThat(excusedCounts[difference])
                    .as("%s code points that Unicode %s changed", name, differences.get(difference).since())
                    .isEqualTo(differences.get(difference).codePointCount());
        }
    }

    private static int excusingDifference(List<ExpectedDifference> differences, int codePoint)
    {
        for (int difference = 0; difference < differences.size(); difference++) {
            if (differences.get(difference).codePoints().test(codePoint)) {
                return difference;
            }
        }
        return -1;
    }

    private record Category(String name, int type) {}

    private record CategoryGroup(String name, IntPredicate types) {}

    private record PropertyExpectation(String name, IntPredicate predicate) {}

    // The code points of the properties that Unicode changed in the given version, and how many of
    // them differ from an earlier JDK.
    private record ExpectedDifference(String since, Set<String> properties, int codePointCount, IntPredicate codePoints) {}
}
