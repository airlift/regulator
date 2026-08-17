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

import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static io.airlift.slice.Slices.utf8Slice;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic differential coverage for the supported Java 25 regular-expression subset.
 */
public class TestJavaRegexpDifferential
{
    private static final String CORPUS_IDENTITY = "java-regexp-differential-v1-java25";
    private static final long CORPUS_SEED = 0x4A_41_56_41_25L;
    private static final int PATTERN_COUNT = 128;
    private static final int INPUTS_PER_PATTERN = 6;
    private static final int CASE_COUNT = PATTERN_COUNT * INPUTS_PER_PATTERN;
    private static final String CORPUS_SHA256 = "0575d0eb8d8bef770d680e5942fd1d524f337cc6424a47c86262a92d17c33467";

    private static final String[] ASCII_WORDS = {
            "alpha",
            "beta",
            "delta",
            "kelvin",
            "lambda",
            "omega",
    };

    private static final String[] UNICODE_WORDS = {
            "élan",
            "mañana",
            "Straße",
            "Καλημέρα",
            "Журнал",
            "東京",
    };

    private static final List<String> FROZEN_INPUTS = List.of(
            "",
            "a",
            "alpha",
            "ALPHA",
            "alphabet soup",
            "beta",
            "abcxyz",
            "mop",
            "123_456",
            "élan",
            "ÉLAN",
            "mañana",
            "Straße",
            "Καλημέρα",
            "Журнал",
            "東京",
            "💰",
            "a💰éb",
            "a\u0301",
            "\u0301",
            " ",
            "\t\u2007",
            "\n",
            "\r",
            "\r\n",
            "\u0085",
            "\u2028",
            "\u2029",
            "before\nafter",
            "before\r\nafter",
            "x\rword\ry",
            "x\nword\ny");

    private static final Corpus CORPUS = generateCorpus();

    @Test
    public void testUnixMultilineCaretMatchesJdk()
    {
        for (String pattern : List.of(
                "(?dm)^",
                "(?dm)^.*$",
                "(?m)^",
                "(?dm)$",
                "(?dm:^a)|(?m:^b)",
                "(?m:^a)|(?dm:^b)")) {
            Pattern jdk = Pattern.compile(pattern);
            JavaRegexp regexp = JavaRegexp.compile(utf8Slice(pattern));
            for (String input : List.of("", "\n", "a\n", "a\nb", "\rb", "a\r\nb", "\u0085b", "é\nb", "\u2028b")) {
                Matcher expected = jdk.matcher(input);
                Slice text = utf8Slice("padding" + input).slice(7, utf8Slice(input).length());
                Re2Matcher actual = regexp.matcher(text);
                while (expected.find()) {
                    assertThat(actual.find()).as("%s input=%s", pattern, input).isTrue();
                    assertThat(actual.start()).isEqualTo(utf8Slice(input.substring(0, expected.start())).length());
                    assertThat(actual.end()).isEqualTo(utf8Slice(input.substring(0, expected.end())).length());
                }
                assertThat(actual.find()).as("extra match: %s input=%s", pattern, input).isFalse();
            }
        }
    }

    @Test
    public void testUnixMultilineCaretUsesContextSensitiveFallback()
    {
        String pattern = "(?dm)^";
        Prog program = Compiler.compile(JavaRegexpParser.parse(utf8Slice(pattern), JavaRegexp.Options.defaults().parseFlags()).regexp());
        assertThat(program.hasTextDependentAssertions()).isTrue();

        // Exceed BitState's bitmap limit so the public matcher also exercises NFA assertions.
        String input = "a".repeat(program.bitStateTextMaxSize() + 1) + "\n";
        Re2Matcher actual = JavaRegexp.compile(utf8Slice(pattern)).matcher(utf8Slice(input));
        Matcher expected = Pattern.compile(pattern).matcher(input);
        while (expected.find()) {
            assertThat(actual.find()).isTrue();
            assertThat(actual.start()).isEqualTo(expected.start());
            assertThat(actual.end()).isEqualTo(expected.end());
        }
        assertThat(actual.find()).isFalse();
    }

    @Test
    public void testFullCharacterClassesAndDotNewlineMatchJdk()
    {
        List<String> inputs = List.of("ab", "a\nb", "a\rb", "a\r\nb", "a\u0085b", "a\u2028b", "a\u2029b", "aé💰b", "xa\nby");
        for (String atom : List.of("[\\s\\S]", "[\\d\\D]", "[\\w\\W]", "[^\\s\\S]", ".", "(?s:.)", "(?-s:.)")) {
            for (String prefix : List.of("", "(?s)", "(?d)")) {
                for (String repeat : List.of("", "{0,100}", "+")) {
                    String pattern = prefix + "a(" + atom + repeat + ")b";
                    assertMatchesJava25(pattern, OptionProfile.DEFAULT, inputs);
                    Pattern jdk = Pattern.compile(pattern);
                    JavaRegexp java = JavaRegexp.compile(utf8Slice(pattern));
                    for (String input : inputs) {
                        Matcher expected = jdk.matcher(input);
                        Slice source = utf8Slice("padding" + input).slice(7, utf8Slice(input).length());
                        Re2Matcher actual = java.matcher(source);
                        boolean matched = expected.find();
                        assertThat(actual.find()).as("%s %s", pattern, input).isEqualTo(matched);
                        if (matched) {
                            assertThat(actual.group(0)).isEqualTo(utf8Slice(expected.group(0)));
                            assertThat(actual.group(1)).isEqualTo(utf8Slice(expected.group(1)));
                        }
                    }
                }
            }
        }
    }

    @Test
    public void testCorpusIdentityAndCompileAcceptance()
            throws NoSuchAlgorithmException
    {
        assertThat(CORPUS.patterns()).hasSize(PATTERN_COUNT);
        assertThat(CORPUS.cases()).hasSize(CASE_COUNT);
        assertThat(corpusSha256(CORPUS)).isEqualTo(CORPUS_SHA256);

        for (GeneratedPattern generatedPattern : CORPUS.patterns()) {
            String description = generatedPattern.description();
            assertThatCode(() -> Pattern.compile(generatedPattern.pattern(), generatedPattern.options().jdkFlags()))
                    .as(description + " must compile with Java 25")
                    .doesNotThrowAnyException();
            assertThatCode(() -> JavaRegexp.compile(
                    utf8Slice(generatedPattern.pattern()),
                    generatedPattern.options().newJavaOptions()))
                    .as(description + " must compile with JavaRegexp")
                    .doesNotThrowAnyException();

            Pattern jdkPattern = Pattern.compile(generatedPattern.pattern(), generatedPattern.options().jdkFlags());
            JavaRegexp javaRegexp = JavaRegexp.compile(
                    utf8Slice(generatedPattern.pattern()),
                    generatedPattern.options().newJavaOptions());
            assertThat(javaRegexp.capturingGroupCount())
                    .as(description + " capturing group count")
                    .isEqualTo(jdkPattern.matcher("").groupCount());
            assertThat(javaRegexp.namedCapturingGroups())
                    .as(description + " named capturing groups")
                    .containsExactlyInAnyOrderEntriesOf(generatedPattern.namedGroups());
        }
    }

    @Test
    public void testOperationsAndCapturesMatchJava25()
    {
        for (DifferentialCase testCase : CORPUS.cases()) {
            Pattern jdkPattern = Pattern.compile(testCase.pattern().pattern(), testCase.pattern().options().jdkFlags());
            JavaRegexp javaRegexp = JavaRegexp.compile(
                    utf8Slice(testCase.pattern().pattern()),
                    testCase.pattern().options().newJavaOptions());

            for (Operation operation : Operation.values()) {
                assertOperation(testCase, operation, jdkPattern, javaRegexp);
            }
        }
    }

    @Test
    public void testIsAsciiPropertyMatchesJava25()
    {
        for (OptionProfile options : List.of(OptionProfile.DEFAULT, OptionProfile.UNICODE_CHARACTER_CLASSES)) {
            assertMatchesJava25("\\p{IsASCII}", options, List.of("A", "\u007F", "\u0080", "é", "💰"));
            assertMatchesJava25("\\P{IsASCII}", options, List.of("A", "\u007F", "\u0080", "é", "💰"));
        }

        List<String> caseFoldInputs = List.of("A", "a", "\u007F", "\u0080", "K", "ſ", "ı", "İ");
        for (String property : List.of("ASCII", "IsASCII")) {
            for (String prefix : List.of("(?iu)", "(?iU)")) {
                assertMatchesJava25(prefix + "\\p{" + property + "}", OptionProfile.DEFAULT, caseFoldInputs);
                assertMatchesJava25(prefix + "\\P{" + property + "}", OptionProfile.DEFAULT, caseFoldInputs);
                assertMatchesJava25(prefix + "[\\p{" + property + "}]", OptionProfile.DEFAULT, caseFoldInputs);
                assertMatchesJava25(prefix + "[\\P{" + property + "}]", OptionProfile.DEFAULT, caseFoldInputs);
            }
        }

        assertMatchesJava25("(?iu)[\\x00-\\x7f]", OptionProfile.DEFAULT, caseFoldInputs);
    }

    @Test
    public void testQuotedCharacterClassAtomsMatchJava25()
    {
        List<QuotedClassCase> testCases = List.of(
                quotedClassCase("[a-\\Qz\\E]", OptionProfile.DEFAULT, "a", "m", "z", "-"),
                quotedClassCase("[\\Qa\\E-z]", OptionProfile.DEFAULT, "a", "m", "z", "-"),
                quotedClassCase("[a-\\Qxz\\E]", OptionProfile.DEFAULT, "m", "x", "z", "-"),
                quotedClassCase("[\\Qax\\E-z]", OptionProfile.DEFAULT, "a", "m", "x", "z"),
                quotedClassCase("[a\\Q-]&&[\\Ez]", OptionProfile.DEFAULT, "a", "-", "]", "&", "[", "z"),
                quotedClassCase("[\\Q\\E]a]", OptionProfile.DEFAULT, "]", "a", "^"),
                quotedClassCase("[\\Q\\E^a]", OptionProfile.DEFAULT, "^", "a", "z"),
                quotedClassCase("[a-\\Q\\E]", OptionProfile.DEFAULT, "a", "-", "m"),
                quotedClassCase("[a-\\Q\\E[b]]", OptionProfile.DEFAULT, "a", "-", "b", "m"),
                quotedClassCase("[\\Qa-z\\E&&[m]]", OptionProfile.DEFAULT, "a", "-", "m", "z"),
                quotedClassCase("[\\Qa\\E-z&&[m]]", OptionProfile.DEFAULT, "a", "m", "z"),
                quotedClassCase("[a&\\Q\\E&b]", OptionProfile.DEFAULT, "a", "&", "b"),
                quotedClassCase("[a&\\Qx\\E&b]", OptionProfile.DEFAULT, "a", "&", "x", "b"),
                quotedClassCase("[a- \\Qz\\E]", OptionProfile.COMMENTS, "a", "m", "z", "-"),
                quotedClassCase("[a- # range endpoint\n \\Qz\\E]", OptionProfile.COMMENTS, "a", "m", "z", "-"),
                quotedClassCase("[a& &b]", OptionProfile.COMMENTS, "a", "&", "b"),
                quotedClassCase("[a&# logical intersection\n &b]", OptionProfile.COMMENTS, "a", "&", "b"),
                quotedClassCase("[\\Q #\\E]", OptionProfile.COMMENTS, " ", "#", "a"),
                quotedClassCase("[\\Q\\E # before first atom\n ^a]", OptionProfile.COMMENTS, "^", "a", "b"),
                quotedClassCase("[a-\\Q💰\\E]", OptionProfile.DEFAULT, "a", "z", "💰", "🤑"),
                quotedClassCase("[\\Q💰\\E-🤑]", OptionProfile.DEFAULT, "💰", "💵", "🤑", "z"),
                quotedClassCase("[\\Qa\\E-z]", OptionProfile.CASE_INSENSITIVE, "A", "M", "Z", "-"),
                quotedClassCase("[\\Qé\\E-é]", OptionProfile.UNICODE_CASE_INSENSITIVE, "é", "É", "e"));

        for (QuotedClassCase testCase : testCases) {
            assertMatchesJava25(testCase.pattern(), testCase.options(), testCase.inputs());
        }
    }

    @Test
    public void testQuotedCharacterClassRangeRejectionsMatchJava25()
    {
        for (String pattern : List.of("[z-\\Qa\\E]", "[🤑-\\Q💰\\E]", "[a-\\Q-\\E]")) {
            assertRejectsJava25(pattern, OptionProfile.DEFAULT, RegexpParseErrorCode.BAD_CHAR_RANGE);
        }
        assertRejectsJava25("[a-\\Q\\E # no endpoint\n ]", OptionProfile.COMMENTS, RegexpParseErrorCode.BAD_CHAR_RANGE);
    }

    private static void assertMatchesJava25(String pattern, OptionProfile options, List<String> inputs)
    {
        Pattern jdkPattern = Pattern.compile(pattern, options.jdkFlags());
        JavaRegexp javaRegexp = JavaRegexp.compile(utf8Slice(pattern), options.newJavaOptions());
        for (String input : inputs) {
            assertThat(javaRegexp.matches(utf8Slice(input)))
                    .as("/" + pattern + "/ flags=" + options + " input=\"" + input + "\"")
                    .isEqualTo(jdkPattern.matcher(input).matches());
        }
    }

    @Test
    public void testQuotedLiteralCaseFoldingMatchesJava25()
    {
        List<String> inputs = List.of("", "a", "A", "AA", "k", "K", "K", "é", "É", "ÉÉ", "aa", "ak", "aK");
        for (String literal : List.of("a", "A", "K", "é", "É")) {
            for (String suffix : List.of("", "+", "{2}", "\\Q\\E?", "(?-i:k)")) {
                for (OptionProfile options : List.of(OptionProfile.DEFAULT, OptionProfile.CASE_INSENSITIVE, OptionProfile.UNICODE_CASE_INSENSITIVE)) {
                    assertMatchesJava25("\\Q" + literal + "\\E" + suffix, options, inputs);
                }
                for (String flags : List.of("(?i)", "(?iu)", "(?iU)")) {
                    assertMatchesJava25(flags + "\\Q" + literal + "\\E" + suffix, OptionProfile.DEFAULT, inputs);
                }
            }
        }
    }

    @Test
    public void testPropertyCaseFoldingMatchesJava25()
    {
        List<String> inputs = List.of("a", "A", "é", "É", "ǅ", "ẖ", "ª", "K", "ı", "ſ", "1", "\n", "💰");
        for (String property : List.of(
                "Lu",
                "Ll",
                "Lt",
                "IsLu",
                "gc=Lu",
                "general_category=Ll",
                "javaLowerCase",
                "javaUpperCase",
                "javaTitleCase",
                "Lower",
                "Upper",
                "Alpha",
                "ASCII",
                "IsLowercase",
                "IsUppercase",
                "IsTitlecase")) {
            for (String flags : List.of("", "(?i)", "(?iu)", "(?iU)")) {
                for (String polarity : List.of("p", "P")) {
                    String expression = "\\" + polarity + "{" + property + "}";
                    assertMatchesJava25(flags + expression, OptionProfile.DEFAULT, inputs);
                    assertMatchesJava25(flags + "[" + expression + "&&[a-zéÉǅẖªKıſ]]", OptionProfile.DEFAULT, inputs);
                }
            }
        }
        assertMatchesJava25("(?i)[\\P{Lower}&&a]", OptionProfile.DEFAULT, inputs);
        assertMatchesJava25("(?i:\\p{Lower})(?-i:\\p{Lower})", OptionProfile.DEFAULT, List.of("Aa", "AA", "aa"));
        assertMatchesJava25("(?iu)[a-z]", OptionProfile.DEFAULT, inputs);
    }

    @Test
    public void testLatin1AndPrefixedPropertiesMatchJava25()
    {
        List<String> inputs = List.of("a", "A", "0", "_", " ", "\n", "é", "É", "ÿ", "Ā", "ǅ", "K", "ſ", "ﬀ", "😀");
        for (String property : List.of("L1", "IsL1", "gc=L1", "general_category=L1", "gc=Lower", "general_category=Lower", "gc=Upper", "gc=ASCII", "gc=Alpha", "gc=Space")) {
            for (String flags : List.of("", "(?i)", "(?iu)", "(?U)", "(?iU)")) {
                for (String sign : List.of("p", "P")) {
                    String expression = "\\" + sign + "{" + property + "}";
                    assertMatchesJava25(flags + expression, OptionProfile.DEFAULT, inputs);
                    assertMatchesJava25(flags + "[" + expression + "&&[a-zA-ZéÉÿĀǅKſ]]", OptionProfile.DEFAULT, inputs);
                }
            }
        }
    }

    @Test
    public void testPrefixedJavaPropertiesMatchJava25()
    {
        List<String> inputs = List.of("a", "A", "é", "É", "ǅ", "K", "ı", "ſ", "0", "_", "$", " ", "\n", "\u0085", "\u00A0", "\u200C", "中", "(", "😀", "\u0378");
        for (String property : List.of(
                "javaLowerCase",
                "javaUpperCase",
                "javaAlphabetic",
                "javaIdeographic",
                "javaTitleCase",
                "javaDigit",
                "javaDefined",
                "javaLetter",
                "javaLetterOrDigit",
                "javaJavaIdentifierStart",
                "javaJavaIdentifierPart",
                "javaUnicodeIdentifierStart",
                "javaUnicodeIdentifierPart",
                "javaIdentifierIgnorable",
                "javaSpaceChar",
                "javaWhitespace",
                "javaISOControl",
                "javaMirrored")) {
            for (String family : List.of("", "gc=", "general_category=")) {
                for (String flags : List.of("", "(?i)", "(?iu)", "(?U)", "(?iU)")) {
                    for (String sign : List.of("p", "P")) {
                        String expression = "\\" + sign + "{" + family + property + "}";
                        assertMatchesJava25(flags + expression, OptionProfile.DEFAULT, inputs);
                        assertMatchesJava25(flags + "[" + expression + "&&[^0]]", OptionProfile.DEFAULT, inputs);
                    }
                }
            }
        }
        for (String property : List.of("sc=javaLowerCase", "blk=javaLowerCase", "gc=IsjavaLowerCase", "gc=javaDoesNotExist", "general_category=javaDoesNotExist")) {
            assertRejectsJava25("\\p{" + property + "}", OptionProfile.DEFAULT, RegexpParseErrorCode.BAD_CHAR_RANGE);
        }
    }

    private static QuotedClassCase quotedClassCase(String pattern, OptionProfile options, String... inputs)
    {
        return new QuotedClassCase(pattern, options, List.of(inputs));
    }

    private static void assertRejectsJava25(String pattern, OptionProfile options, RegexpParseErrorCode errorCode)
    {
        assertThatThrownBy(() -> Pattern.compile(pattern, options.jdkFlags()))
                .as("Java 25 must reject /" + pattern + "/ flags=" + options)
                .isInstanceOf(PatternSyntaxException.class);
        assertThatThrownBy(() -> JavaRegexp.compile(utf8Slice(pattern), options.newJavaOptions()))
                .as("JavaRegexp must reject /" + pattern + "/ flags=" + options)
                .isInstanceOfSatisfying(RegexpParseException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(errorCode));
    }

    private static void assertOperation(
            DifferentialCase testCase,
            Operation operation,
            Pattern jdkPattern,
            JavaRegexp javaRegexp)
    {
        Matcher jdkMatcher = jdkPattern.matcher(testCase.input());
        boolean expectedMatched = operation.execute(jdkMatcher);
        Slice input = utf8Slice(testCase.input());
        boolean actualMatched = operation.execute(javaRegexp, input);
        MatchResult actualResult = operation.result(javaRegexp, input);
        String description = testCase.description(operation);

        assertThat(actualMatched)
                .as(description + " boolean result")
                .isEqualTo(expectedMatched);
        assertThat(actualResult != null)
                .as(description + " MatchResult presence")
                .isEqualTo(expectedMatched);
        if (!expectedMatched) {
            return;
        }

        assertThat(actualResult.groupCount())
                .as(description + " capturing group count")
                .isEqualTo(jdkMatcher.groupCount());
        for (int group = 0; group <= jdkMatcher.groupCount(); group++) {
            assertThat(actualResult.start(group))
                    .as(description + " group " + group + " start")
                    .isEqualTo(utf8Offset(testCase.input(), jdkMatcher.start(group)));
            assertThat(actualResult.end(group))
                    .as(description + " group " + group + " end")
                    .isEqualTo(utf8Offset(testCase.input(), jdkMatcher.end(group)));
            assertThat(actualResult.groupUtf8(group))
                    .as(description + " group " + group + " text")
                    .isEqualTo(jdkMatcher.group(group));
        }
        for (String groupName : testCase.pattern().namedGroups().keySet()) {
            assertThat(actualResult.groupUtf8(groupName))
                    .as(description + " named group " + groupName)
                    .isEqualTo(jdkMatcher.group(groupName));
        }
    }

    private static Corpus generateCorpus()
    {
        Random random = new Random(CORPUS_SEED);
        List<GeneratedPattern> patterns = new ArrayList<>(PATTERN_COUNT);
        List<DifferentialCase> cases = new ArrayList<>(CASE_COUNT);
        for (int patternIndex = 0; patternIndex < PATTERN_COUNT; patternIndex++) {
            GeneratedPattern pattern = generatePattern(random, patternIndex);
            patterns.add(pattern);

            List<String> inputs = new ArrayList<>(INPUTS_PER_PATTERN);
            inputs.addAll(pattern.requiredInputs());
            while (inputs.size() < INPUTS_PER_PATTERN) {
                inputs.add(FROZEN_INPUTS.get(random.nextInt(FROZEN_INPUTS.size())));
            }
            if (inputs.size() != INPUTS_PER_PATTERN) {
                throw new IllegalStateException(pattern.description() + " generated too many required inputs");
            }
            for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                cases.add(new DifferentialCase(patternIndex, inputIndex, pattern, inputs.get(inputIndex)));
            }
        }
        return new Corpus(List.copyOf(patterns), List.copyOf(cases));
    }

    private static GeneratedPattern generatePattern(Random random, int patternIndex)
    {
        String firstAsciiWord = ASCII_WORDS[random.nextInt(ASCII_WORDS.length)];
        String secondAsciiWord = ASCII_WORDS[random.nextInt(ASCII_WORDS.length)];
        String unicodeWord = UNICODE_WORDS[random.nextInt(UNICODE_WORDS.length)];
        return switch (patternIndex % 32) {
            case 0 -> pattern(patternIndex, Pattern.quote(firstAsciiWord), OptionProfile.DEFAULT, List.of(firstAsciiWord), Map.of());
            case 1 -> pattern(
                    patternIndex,
                    "(?:" + firstAsciiWord + "|" + secondAsciiWord + ")+",
                    OptionProfile.DEFAULT,
                    List.of(firstAsciiWord, secondAsciiWord + firstAsciiWord),
                    Map.of());
            case 2 -> pattern(
                    patternIndex,
                    "(" + firstAsciiWord + ")(" + secondAsciiWord + ")?",
                    OptionProfile.DEFAULT,
                    List.of(firstAsciiWord, firstAsciiWord + secondAsciiWord),
                    Map.of());
            case 3 -> pattern(
                    patternIndex,
                    "(?<word>" + firstAsciiWord + ")(?<suffix>" + secondAsciiWord + ")?",
                    OptionProfile.DEFAULT,
                    List.of(firstAsciiWord, firstAsciiWord + secondAsciiWord),
                    Map.of("word", 1, "suffix", 2));
            case 4 -> pattern(patternIndex, "[a-z&&[^xyz]]+", OptionProfile.DEFAULT, List.of("abc", "xyz"), Map.of());
            case 5 -> pattern(patternIndex, "[a-z&&[m-z]]+", OptionProfile.DEFAULT, List.of("mop", "alpha"), Map.of());
            case 6 -> pattern(
                    patternIndex,
                    "\\A(?:" + firstAsciiWord + "|" + secondAsciiWord + ")+\\z",
                    OptionProfile.DEFAULT,
                    List.of(firstAsciiWord, "x" + firstAsciiWord),
                    Map.of());
            case 7 -> pattern(
                    patternIndex,
                    "^" + firstAsciiWord + "$",
                    OptionProfile.MULTILINE,
                    List.of("x\n" + firstAsciiWord + "\ny", "x\r\n" + firstAsciiWord + "\r\ny"),
                    Map.of());
            case 8 -> pattern(
                    patternIndex,
                    "(?:" + firstAsciiWord + ")?",
                    OptionProfile.DEFAULT,
                    List.of("", firstAsciiWord),
                    Map.of());
            case 9 -> pattern(
                    patternIndex,
                    "(?U:\\b" + unicodeWord + "\\b)",
                    OptionProfile.DEFAULT,
                    List.of(unicodeWord, "x" + unicodeWord),
                    Map.of());
            case 10 -> pattern(
                    patternIndex,
                    "(?iu:" + firstAsciiWord + ")",
                    OptionProfile.DEFAULT,
                    List.of(firstAsciiWord.toUpperCase(Locale.ROOT), firstAsciiWord),
                    Map.of());
            case 11 -> pattern(
                    patternIndex,
                    "(?i:" + firstAsciiWord + ")(?-i:" + secondAsciiWord + ")",
                    OptionProfile.DEFAULT,
                    List.of(
                            firstAsciiWord.toUpperCase(Locale.ROOT) + secondAsciiWord,
                            firstAsciiWord.toUpperCase(Locale.ROOT) + secondAsciiWord.toUpperCase(Locale.ROOT)),
                    Map.of());
            case 12 -> pattern(patternIndex, "\\p{IsLatin}+", OptionProfile.DEFAULT, List.of("élan", "Журнал"), Map.of());
            case 13 -> pattern(patternIndex, "(?U:\\w+)", OptionProfile.DEFAULT, List.of(unicodeWord, "123_456"), Map.of());
            case 14 -> pattern(
                    patternIndex,
                    firstAsciiWord + " # generated comment\n " + secondAsciiWord,
                    OptionProfile.COMMENTS,
                    List.of(firstAsciiWord + secondAsciiWord),
                    Map.of());
            case 15 -> pattern(patternIndex, "^.$", OptionProfile.DOTALL, List.of("\n", "\u2028"), Map.of());
            case 16 -> pattern(
                    patternIndex,
                    "^" + firstAsciiWord + "$",
                    OptionProfile.UNIX_MULTILINE,
                    List.of("x\n" + firstAsciiWord + "\ny", "x\r" + firstAsciiWord + "\ry"),
                    Map.of());
            case 17 -> pattern(
                    patternIndex,
                    "[" + firstAsciiWord + "]+",
                    OptionProfile.LITERAL,
                    List.of("[" + firstAsciiWord + "]+", firstAsciiWord),
                    Map.of());
            case 18 -> pattern(
                    patternIndex,
                    "(?:" + firstAsciiWord + "){1,3}?",
                    OptionProfile.DEFAULT,
                    List.of(firstAsciiWord, firstAsciiWord.repeat(3)),
                    Map.of());
            case 19 -> pattern(patternIndex, "[a-c[m-p]]+", OptionProfile.DEFAULT, List.of("acmp", "xyz"), Map.of());
            case 20 -> pattern(patternIndex, "[a-z&&[^m-p]]+", OptionProfile.DEFAULT, List.of("abcxyz", "mop"), Map.of());
            case 21 -> pattern(patternIndex, "\\h+\\v+", OptionProfile.DEFAULT, List.of("\t\u2007\n\u2029", "abc"), Map.of());
            case 22 -> pattern(
                    patternIndex,
                    "(?<money>💰)(?<accent>é)?",
                    OptionProfile.DEFAULT,
                    List.of("💰", "💰é"),
                    Map.of("money", 1, "accent", 2));
            case 23 -> pattern(
                    patternIndex,
                    "\\Q[" + firstAsciiWord + "]+\\E",
                    OptionProfile.DEFAULT,
                    List.of("[" + firstAsciiWord + "]+"),
                    Map.of());
            case 24 -> pattern(
                    patternIndex,
                    firstAsciiWord + "\\Z",
                    OptionProfile.DEFAULT,
                    List.of(firstAsciiWord + "\r\n", firstAsciiWord + "\ntrailing"),
                    Map.of());
            case 25 -> pattern(
                    patternIndex,
                    "(" + firstAsciiWord + "+?)(" + secondAsciiWord + ")?",
                    OptionProfile.DEFAULT,
                    List.of(firstAsciiWord, firstAsciiWord + secondAsciiWord),
                    Map.of());
            case 26 -> pattern(
                    patternIndex,
                    firstAsciiWord,
                    OptionProfile.CASE_INSENSITIVE,
                    List.of(firstAsciiWord.toUpperCase(Locale.ROOT), firstAsciiWord),
                    Map.of());
            case 27 -> pattern(
                    patternIndex,
                    "é" + firstAsciiWord,
                    OptionProfile.UNICODE_CASE_INSENSITIVE,
                    List.of("É" + firstAsciiWord.toUpperCase(Locale.ROOT), "é" + firstAsciiWord),
                    Map.of());
            case 28 -> pattern(
                    patternIndex,
                    "\\w+",
                    OptionProfile.UNICODE_CHARACTER_CLASSES,
                    List.of(unicodeWord, "123_456"),
                    Map.of());
            case 29 -> pattern(
                    patternIndex,
                    "(?x:" + firstAsciiWord + " # scoped\n " + secondAsciiWord + ")",
                    OptionProfile.DEFAULT,
                    List.of(firstAsciiWord + secondAsciiWord),
                    Map.of());
            case 30 -> pattern(
                    patternIndex,
                    "[\\Q" + firstAsciiWord + "\\E]+",
                    OptionProfile.DEFAULT,
                    List.of(firstAsciiWord, "123"),
                    Map.of());
            case 31 -> pattern(patternIndex, "a\\B\u0301", OptionProfile.DEFAULT, List.of("a\u0301", "a"), Map.of());
            default -> throw new AssertionError();
        };
    }

    private static GeneratedPattern pattern(
            int patternIndex,
            String pattern,
            OptionProfile options,
            List<String> requiredInputs,
            Map<String, Integer> namedGroups)
    {
        return new GeneratedPattern(patternIndex, pattern, options, List.copyOf(requiredInputs), Map.copyOf(namedGroups));
    }

    private static String corpusSha256(Corpus corpus)
            throws NoSuchAlgorithmException
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((CORPUS_IDENTITY + "\tseed=" + CORPUS_SEED + "\n").getBytes(StandardCharsets.UTF_8));
        Base64.Encoder encoder = Base64.getEncoder();
        for (DifferentialCase testCase : corpus.cases()) {
            GeneratedPattern pattern = testCase.pattern();
            String namedGroups = pattern.namedGroups().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + "," + right)
                    .orElse("-");
            String row = testCase.patternIndex() + "\t" +
                    testCase.inputIndex() + "\t" +
                    encoder.encodeToString(pattern.pattern().getBytes(StandardCharsets.UTF_8)) + "\t" +
                    pattern.options() + "\t" +
                    namedGroups + "\t" +
                    encoder.encodeToString(testCase.input().getBytes(StandardCharsets.UTF_8)) + "\n";
            digest.update(row.getBytes(StandardCharsets.US_ASCII));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int utf8Offset(String input, int utf16Offset)
    {
        if (utf16Offset < 0) {
            return -1;
        }
        return input.substring(0, utf16Offset).getBytes(StandardCharsets.UTF_8).length;
    }

    private enum Operation
    {
        FIND,
        LOOKING_AT,
        MATCHES;

        public boolean execute(Matcher matcher)
        {
            return switch (this) {
                case FIND -> matcher.find();
                case LOOKING_AT -> matcher.lookingAt();
                case MATCHES -> matcher.matches();
            };
        }

        public boolean execute(JavaRegexp regexp, Slice input)
        {
            return switch (this) {
                case FIND -> regexp.find(input);
                case LOOKING_AT -> regexp.lookingAt(input);
                case MATCHES -> regexp.matches(input);
            };
        }

        public MatchResult result(JavaRegexp regexp, Slice input)
        {
            return switch (this) {
                case FIND -> regexp.findResult(input);
                case LOOKING_AT -> regexp.lookingAtResult(input);
                case MATCHES -> regexp.matchesResult(input);
            };
        }
    }

    private enum OptionProfile
    {
        DEFAULT(0),
        CASE_INSENSITIVE(Pattern.CASE_INSENSITIVE),
        UNICODE_CASE_INSENSITIVE(Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
        UNICODE_CHARACTER_CLASSES(Pattern.UNICODE_CHARACTER_CLASS),
        MULTILINE(Pattern.MULTILINE),
        UNIX_MULTILINE(Pattern.UNIX_LINES | Pattern.MULTILINE),
        DOTALL(Pattern.DOTALL),
        COMMENTS(Pattern.COMMENTS),
        LITERAL(Pattern.LITERAL);

        private final int jdkFlags;

        OptionProfile(int jdkFlags)
        {
            this.jdkFlags = jdkFlags;
        }

        public int jdkFlags()
        {
            return jdkFlags;
        }

        public JavaRegexp.Options newJavaOptions()
        {
            JavaRegexp.Options options = JavaRegexp.Options.defaults();
            return switch (this) {
                case DEFAULT -> options;
                case CASE_INSENSITIVE -> options.setCaseInsensitive(true);
                case UNICODE_CASE_INSENSITIVE -> options.setCaseInsensitive(true).setUnicodeCase(true);
                case UNICODE_CHARACTER_CLASSES -> options.setUnicodeCharacterClasses(true);
                case MULTILINE -> options.setMultiline(true);
                case UNIX_MULTILINE -> options.setUnixLines(true).setMultiline(true);
                case DOTALL -> options.setDotMatchesNewline(true);
                case COMMENTS -> options.setComments(true);
                case LITERAL -> options.setLiteral(true);
            };
        }
    }

    private record Corpus(List<GeneratedPattern> patterns, List<DifferentialCase> cases)
    {
        private Corpus
        {
            patterns = List.copyOf(requireNonNull(patterns, "patterns is null"));
            cases = List.copyOf(requireNonNull(cases, "cases is null"));
        }
    }

    private record QuotedClassCase(String pattern, OptionProfile options, List<String> inputs) {}

    private record GeneratedPattern(
            int patternIndex,
            String pattern,
            OptionProfile options,
            List<String> requiredInputs,
            Map<String, Integer> namedGroups)
    {
        private GeneratedPattern
        {
            requireNonNull(pattern, "pattern is null");
            requireNonNull(options, "options is null");
            requiredInputs = List.copyOf(requireNonNull(requiredInputs, "requiredInputs is null"));
            namedGroups = Map.copyOf(requireNonNull(namedGroups, "namedGroups is null"));
        }

        public String description()
        {
            return "pattern " + patternIndex + " /" + pattern + "/ flags=" + options;
        }
    }

    private record DifferentialCase(
            int patternIndex,
            int inputIndex,
            GeneratedPattern pattern,
            String input)
    {
        private DifferentialCase
        {
            requireNonNull(pattern, "pattern is null");
            requireNonNull(input, "input is null");
            if (patternIndex != pattern.patternIndex()) {
                throw new IllegalArgumentException("pattern index does not match pattern");
            }
        }

        public String description(Operation operation)
        {
            return pattern.description() + ", input " + inputIndex + " \"" + input + "\", operation=" + operation;
        }
    }
}
