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

import io.airlift.joni.NameEntry;
import io.airlift.joni.Regex;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static io.airlift.regulator.TestingTrinoJoni.joniMatches;
import static io.airlift.regulator.TestingTrinoJoni.joniPattern;
import static io.airlift.regulator.TestingTrinoJoni.verifyOperations;
import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// Seeded random differential coverage of the Trino regular-expression language against Trino's
/// pinned Joni, `io.airlift:joni:2.1.5.3`.
///
/// A recursive grammar generates patterns over the syntax `TrinoRegexp` supports, each with short
/// valid UTF-8 inputs drawn from the pattern's own characters, a sample the pattern should match,
/// and a fixed alphabet. The grammar leaves out only recorded differences, each cited where it is
/// left out.
public class TestTrinoRegexpDifferential
{
    private static final String CORPUS_IDENTITY = "trino-regexp-differential-v1-joni-2.1.5.3";
    private static final long CORPUS_SEED = 0x54_52_49_4E_4FL;
    private static final int PATTERN_COUNT = 400;
    private static final int INPUTS_PER_PATTERN = 8;
    private static final int CASE_COUNT = PATTERN_COUNT * INPUTS_PER_PATTERN;
    private static final String CORPUS_SHA256 = "4ad598861f4d46603fb3201c2674481cc34416cdbefb5a996e6a1b9a20e4e87b";

    private static final int MAX_DEPTH = 3;
    private static final int MAX_INPUT_CHARACTERS = 12;

    // Characters whose properties are unchanged since Unicode 6.1, the version of pinned Joni's
    // property tables, and whose simple case folds are unchanged since Unicode 4.1, the version of
    // its fold tables. Capital sharp s, U+1DF95, and the glottal stops, whose folds changed, are
    // left out: REGULATOR_DECISIONS.md, Pinned Unicode Properties, Pinned Unicode Case Folding,
    // and Trino Text-Dependent Assertions And Full Case Folding. Arabic-Indic digits, which pinned
    // Joni's tables put in the Common script, are left out for the same reason; Devanagari three
    // stands for non-ASCII digits.
    private static final List<String> TEXT_CHARACTERS = List.of(
            "a",
            "b",
            "c",
            "k",
            "s",
            "x",
            "z",
            "A",
            "B",
            "K",
            "S",
            "Z",
            "0",
            "1",
            "7",
            "_",
            "-",
            ".",
            ",",
            "!",
            "'",
            " ",
            "\t",
            "\n",
            "\r",
            "é",
            "É",
            "ñ",
            "ü",
            "µ",
            "\u00A0",
            "²",
            "α",
            "σ",
            "Σ",
            "ς",
            "ω",
            "Ω",
            "Ж",
            "ж",
            "東",
            "中",
            "あ",
            "💰",
            "𐐀",
            "𐐨",
            "\u0301",
            "\u212A",
            "\u017F",
            "\u01C5",
            "\u2126",
            "\u2028",
            "\u0085",
            "३",
            "Ａ");

    // Trino's pinned Joni leaves these out of [[:punct:]], and Regulator includes them as modern
    // Joni does: TRINO_REGEXP.md, Unicode data and case folding. Inputs of a pattern that uses
    // the bracket punct class leave them out.
    private static final List<String> ASCII_SYMBOLS = List.of("$", "+", "<", "=", ">", "^", "`", "|", "~");

    // Characters whose full case folding has more than one code point. A case-insensitive pattern
    // keeps them out of both the pattern and its inputs, so pinned Joni's tokenization artifacts
    // and Regulator's literal-run rule never meet: TRINO_REGEXP.md, Other matching differences
    // from Joni, and REGULATOR_DECISIONS.md, Trino Text-Dependent Assertions And Full Case Folding.
    private static final List<String> FULL_FOLD_CHARACTERS = List.of("ß", "ﬁ", "İ", "ŉ", "ΐ");
    private static final List<String> FULL_FOLD_AND_TEXT_CHARACTERS = Stream.concat(TEXT_CHARACTERS.stream(), FULL_FOLD_CHARACTERS.stream()).toList();

    // Letters that Joni reads as themselves after a backslash.
    private static final List<String> LITERAL_ESCAPE_LETTERS = List.of("C", "E", "H", "R", "V", "X", "h", "q");

    // The simple case-fold orbits of the pattern characters, so a sample of a case-insensitive
    // literal can use another spelling.
    private static final List<List<String>> CASE_ORBITS = List.of(
            List.of("a", "A"),
            List.of("b", "B"),
            List.of("c", "C"),
            List.of("e", "E"),
            List.of("h", "H"),
            List.of("k", "K", "\u212A"),
            List.of("q", "Q"),
            List.of("s", "S", "\u017F"),
            List.of("x", "X"),
            List.of("z", "Z"),
            List.of("é", "É"),
            List.of("ñ", "Ñ"),
            List.of("ü", "Ü"),
            List.of("µ", "μ", "Μ"),
            List.of("α", "Α"),
            List.of("σ", "Σ", "ς"),
            List.of("ω", "Ω", "\u2126"),
            List.of("ж", "Ж"),
            List.of("𐐀", "𐐨"),
            List.of("ǆ", "\u01C5", "Ǆ"),
            List.of("Ａ", "ａ"));

    private static final Map<String, List<String>> CASE_ORBIT_BY_CHARACTER = caseOrbitByCharacter();

    // Joni keeps bits 7 and 0-4 of the whole character after \c, so a non-ASCII text character
    // can write a control character: \c中 is a carriage return, and \cǅ (U+01C5) is U+0085.
    private static final Map<Integer, List<String>> NON_ASCII_CONTROL_OPERANDS = nonAsciiControlOperands();

    // Property names TrinoRegexp and pinned Joni both accept with the same members on the text
    // characters. Olck, Sundanese, and Saurashtra are left out: pinned Joni reads them as ASCII
    // \s, \d, and \w, a recorded difference (REGULATOR_DECISIONS.md, Trino Compatibility Adapter).
    private static final List<String> PROPERTY_NAMES = List.of(
            "L",
            "Lu",
            "Ll",
            "Lt",
            "Lm",
            "Lo",
            "M",
            "Mn",
            "Mc",
            "N",
            "Nd",
            "Nl",
            "No",
            "P",
            "Pc",
            "Pd",
            "Ps",
            "Pe",
            "Po",
            "S",
            "Sm",
            "Sc",
            "Sk",
            "So",
            "Z",
            "Zs",
            "Zl",
            "C",
            "Cc",
            "Cf",
            "lu",
            "nd",
            "Latin",
            "Greek",
            "Cyrillic",
            "Han",
            "Common",
            "Inherited",
            "Hiragana",
            "Deseret",
            "Arabic",
            "greek",
            "Latn",
            "InBasicLatin",
            "InGreekandCoptic",
            "InCyrillic",
            "InCJKUnifiedIdeographs",
            "InCombiningDiacriticalMarks",
            "InMiscellaneousSymbolsandPictographs",
            "InLetterlikeSymbols",
            "InGeneralPunctuation",
            "InHiragana",
            "InDeseret",
            "InHalfwidthandFullwidthForms",
            "InArabic",
            "Alphabetic",
            "Uppercase",
            "Lowercase",
            "WhiteSpace",
            "Ideographic",
            "Letter",
            "Punctuation",
            "Control",
            "Digit",
            "Assigned",
            "Any",
            "NoncharacterCodePoint",
            "JoinControl",
            "javaLowerCase",
            "javaUpperCase",
            "javaDigit",
            "javaLetter",
            "javaWhitespace",
            "javaAlphabetic",
            "javaTitleCase",
            "javaMirrored",
            "javaLetterOrDigit",
            "javaSpaceChar",
            "javaISOControl",
            "javaJavaIdentifierStart",
            "javaUnicodeIdentifierPart",
            "javaIdentifierIgnorable",
            "javaDefined",
            "javaIdeographic",
            "Alpha",
            "Alnum",
            "Upper",
            "Lower",
            "Space",
            "Punct",
            "Word",
            "XDigit",
            "Blank",
            "Cntrl",
            "Graph",
            "Print",
            "ASCII",
            "HexDigit",
            "alpha",
            "LC",
            "CasedLetter",
            "Qaac",
            "InLatin1Supplement",
            "InGreek",
            "InNoBlock",
            "WSpace",
            "AHex",
            "Dash",
            "Math",
            "XIDContinue",
            "OAlpha",
            "Ideo",
            "Newline",
            "age=1.1",
            "age=3.0",
            "age=6.1",
            "L1",
            "LD",
            "all",
            "perlHorizontalWhiteSpace",
            "perlVerticalWhiteSpace",
            "javaJavaIdentifierPart",
            "javaUnicodeIdentifierStart");

    private static final List<String> SHORT_PROPERTY_NAMES = List.of("L", "N", "P", "S", "Z", "M", "C", "l");

    private static final List<String> POSIX_BRACKET_NAMES = List.of(
            "alpha",
            "digit",
            "alnum",
            "upper",
            "lower",
            "space",
            "punct",
            "word",
            "xdigit",
            "blank",
            "cntrl",
            "graph",
            "print",
            "ascii");

    private static final Corpus CORPUS = generateCorpus();

    @Test
    public void testCorpusIdentityAndCompileAcceptance()
            throws NoSuchAlgorithmException
    {
        assertThat(CORPUS.patterns()).hasSize(PATTERN_COUNT);
        assertThat(CORPUS.cases()).hasSize(CASE_COUNT);
        assertThat(corpusSha256(CORPUS)).isEqualTo(CORPUS_SHA256);

        for (GeneratedPattern generatedPattern : CORPUS.patterns()) {
            String description = generatedPattern.description();
            assertThatCode(() -> joniPattern(generatedPattern.pattern())).as(description + " must compile with Joni").doesNotThrowAnyException();
            assertThatCode(() -> TrinoRegexp.compile(utf8Slice(generatedPattern.pattern()))).as(description + " must compile with TrinoRegexp").doesNotThrowAnyException();

            Regex joni = joniPattern(generatedPattern.pattern());
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(generatedPattern.pattern()));
            assertThat(regexp.capturingGroupCount()).as(description + " capturing group count").isEqualTo(joni.numberOfCaptures());
            assertThat(joniNamedGroups(description, joni)).as(description + " generated named groups").isEqualTo(generatedPattern.namedGroups());
            assertThat(regexp.namedCapturingGroups()).as(description + " named capturing groups").isEqualTo(generatedPattern.namedGroups());
        }
    }

    @Test
    @Timeout(30)
    public void testOperationsMatchJoni()
    {
        // Joni checks for interrupts as it backtracks, so a runaway backtrack in a regenerated
        // corpus fails the test instead of hanging the build.
        for (GeneratedPattern generatedPattern : CORPUS.patterns()) {
            int firstCase = generatedPattern.patternIndex() * INPUTS_PER_PATTERN;
            List<String> sourceDescriptions = new ArrayList<>(INPUTS_PER_PATTERN);
            List<Slice> sources = new ArrayList<>(INPUTS_PER_PATTERN);
            for (DifferentialCase testCase : CORPUS.cases().subList(firstCase, firstCase + INPUTS_PER_PATTERN)) {
                sourceDescriptions.add(generatedPattern.description() + " input " + testCase.inputIndex() + " " + javaLiteral(testCase.input()));
                // Bytes on either side of the view must not reach assertions or results.
                sources.add(utf8Slice("padding" + testCase.input() + "outside").slice(7, utf8Slice(testCase.input()).length()));
            }
            Regex oracle = joniPattern(generatedPattern.pattern());
            verifyOperations(sourceDescriptions, TrinoRegexp.compile(utf8Slice(generatedPattern.pattern())), source -> joniMatches(oracle, source), sources);
        }
    }

    private static Map<String, Integer> joniNamedGroups(String description, Regex joni)
    {
        Map<String, Integer> namedGroups = new HashMap<>();
        if (joni.numberOfNames() == 0) {
            return namedGroups;
        }
        for (Iterator<NameEntry> entries = joni.namedBackrefIterator(); entries.hasNext(); ) {
            NameEntry entry = entries.next();
            String name = new String(entry.name, entry.nameP, entry.nameEnd - entry.nameP, UTF_8);
            int[] groups = entry.getBackRefs();
            assertThat(groups).as("%s group %s", description, name).hasSize(1);
            namedGroups.put(name, groups[0]);
        }
        return namedGroups;
    }

    private static Corpus generateCorpus()
    {
        Random random = new Random(CORPUS_SEED);
        List<GeneratedPattern> patterns = new ArrayList<>(PATTERN_COUNT);
        List<DifferentialCase> cases = new ArrayList<>(CASE_COUNT);
        for (int patternIndex = 0; patternIndex < PATTERN_COUNT; patternIndex++) {
            GeneratedPattern pattern = generatePattern(random, patternIndex);
            patterns.add(pattern);
            List<String> inputs = generateInputs(random, pattern);
            for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                cases.add(new DifferentialCase(patternIndex, inputIndex, pattern, inputs.get(inputIndex)));
            }
        }
        return new Corpus(patterns, cases);
    }

    private static GeneratedPattern generatePattern(Random random, int patternIndex)
    {
        PatternGenerator generator = new PatternGenerator(random, random.nextInt(3) == 0);
        Fragment expression = generator.expression(0, new Scope(false, false, false));
        return new GeneratedPattern(
                patternIndex,
                expression.text(),
                generator.namedGroups,
                generator.mayIgnoreCase,
                generator.usesBracketPunct,
                generator.usesNestedNegation,
                generator.usesEndAnchor,
                expression.nullable(),
                List.copyOf(generator.characters),
                expression.sample());
    }

    /// Returns the sample, the sample with characters around it, a sample with one character
    /// changed, and random strings, their characters drawn from the pattern's characters, the text
    /// characters, the ASCII symbols, and, unless the pattern may ignore case, the full-fold
    /// characters. Each input then drops the characters a recorded difference rules out for the
    /// pattern and is cut to [#MAX_INPUT_CHARACTERS] characters, so the first may not be the sample.
    private static List<String> generateInputs(Random random, GeneratedPattern pattern)
    {
        List<String> alphabet = new ArrayList<>(TEXT_CHARACTERS);
        alphabet.addAll(ASCII_SYMBOLS);
        if (!pattern.mayIgnoreCase()) {
            alphabet.addAll(FULL_FOLD_CHARACTERS);
        }
        List<String> inputs = new ArrayList<>(INPUTS_PER_PATTERN);
        List<String> sample = codePoints(pattern.sample());
        inputs.add(pattern.sample());
        List<String> surrounded = new ArrayList<>(randomCharacters(random, pattern, alphabet, random.nextInt(3)));
        surrounded.addAll(sample);
        surrounded.addAll(randomCharacters(random, pattern, alphabet, random.nextInt(3)));
        inputs.add(String.join("", surrounded));
        List<String> changed = new ArrayList<>(sample);
        int position = random.nextInt(changed.size() + 1);
        switch (changed.isEmpty() ? 0 : random.nextInt(3)) {
            case 0 -> changed.add(position, randomCharacter(random, pattern, alphabet));
            case 1 -> changed.remove(Math.min(position, changed.size() - 1));
            default -> changed.set(Math.min(position, changed.size() - 1), randomCharacter(random, pattern, alphabet));
        }
        inputs.add(String.join("", changed));
        while (inputs.size() < INPUTS_PER_PATTERN) {
            inputs.add(String.join("", randomCharacters(random, pattern, alphabet, random.nextInt(MAX_INPUT_CHARACTERS + 1))));
        }

        List<String> filtered = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            StringBuilder kept = new StringBuilder();
            int keptCharacters = 0;
            for (String character : codePoints(input)) {
                if (keptCharacters == MAX_INPUT_CHARACTERS) {
                    break;
                }
                if (pattern.usesBracketPunct() && ASCII_SYMBOLS.contains(character)) {
                    continue;
                }
                if (pattern.usesNestedNegation() && character.codePointAt(0) >= 0x80) {
                    continue;
                }
                kept.append(character);
                keptCharacters++;
            }
            // Before $ or \z, pinned Joni can miss the empty match at the end of an input that ends
            // with a multibyte character, a recorded difference (TRINO_REGEXP.md, Other matching
            // differences from Joni). Only a pattern that can match empty has that match to miss,
            // so inputs of such a pattern that uses either end with an ASCII character, a trim
            // deliberately broader than the defect.
            while (pattern.usesEndAnchor() && pattern.nullable() && !kept.isEmpty() && kept.codePointBefore(kept.length()) >= 0x80) {
                kept.setLength(kept.offsetByCodePoints(kept.length(), -1));
            }
            filtered.add(kept.toString());
        }
        return filtered;
    }

    private static List<String> randomCharacters(Random random, GeneratedPattern pattern, List<String> alphabet, int count)
    {
        List<String> characters = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            characters.add(randomCharacter(random, pattern, alphabet));
        }
        return characters;
    }

    private static String randomCharacter(Random random, GeneratedPattern pattern, List<String> alphabet)
    {
        if (random.nextBoolean()) {
            return pattern.characters().get(random.nextInt(pattern.characters().size()));
        }
        return alphabet.get(random.nextInt(alphabet.size()));
    }

    private static List<String> codePoints(String value)
    {
        return value.codePoints().mapToObj(Character::toString).toList();
    }

    private static Map<Integer, List<String>> nonAsciiControlOperands()
    {
        Map<Integer, List<String>> operands = new HashMap<>();
        for (String character : TEXT_CHARACTERS) {
            int codePoint = character.codePointAt(0);
            if (codePoint >= 0x80) {
                operands.computeIfAbsent(codePoint & 0x9F, _ -> new ArrayList<>()).add(character);
            }
        }
        return Map.copyOf(operands);
    }

    private static Map<String, List<String>> caseOrbitByCharacter()
    {
        Map<String, List<String>> orbits = new HashMap<>();
        for (List<String> orbit : CASE_ORBITS) {
            for (String character : orbit) {
                orbits.put(character, orbit);
            }
        }
        return Map.copyOf(orbits);
    }

    private static String corpusSha256(Corpus corpus)
            throws NoSuchAlgorithmException
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((CORPUS_IDENTITY + "\tseed=" + CORPUS_SEED + "\n").getBytes(UTF_8));
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
                    encoder.encodeToString(pattern.pattern().getBytes(UTF_8)) + "\t" +
                    encoder.encodeToString(namedGroups.getBytes(UTF_8)) + "\t" +
                    encoder.encodeToString(testCase.input().getBytes(UTF_8)) + "\n";
            digest.update(row.getBytes(US_ASCII));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Writes `value` as a Java string literal, so a failing pattern or input can be pasted
    /// into a test.
    private static String javaLiteral(String value)
    {
        StringBuilder literal = new StringBuilder("\"");
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '\\' -> literal.append("\\\\");
                case '"' -> literal.append("\\\"");
                case '\n' -> literal.append("\\n");
                case '\r' -> literal.append("\\r");
                case '\t' -> literal.append("\\t");
                default -> {
                    if (codePoint < 0x20 || codePoint == 0x85 || codePoint == 0xA0 || codePoint == 0x2028 || (codePoint >= 0x300 && codePoint < 0x370) ||
                            codePoint == 0x17F || codePoint == 0x212A || codePoint == 0x2126) {
                        literal.append("\\u").append(HexFormat.of().withUpperCase().toHexDigits((short) codePoint));
                    }
                    else {
                        literal.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return literal.append('"').toString();
    }

    /// Generates one pattern. Each grammar method returns the text it generated with a sample of what it
    /// matches; the sample is a best guess, used only to raise the share of inputs that match.
    private static final class PatternGenerator
    {
        private final Random random;
        // Whether the pattern may turn on case-insensitive matching. Only a pattern that may not
        // has full-fold characters in it or its inputs.
        private final boolean mayIgnoreCase;
        private final List<String> palette;
        private final Set<String> characters = new LinkedHashSet<>();
        private final Map<String, Integer> namedGroups = new LinkedHashMap<>();
        private int capturingGroupCount;
        private boolean usesBracketPunct;
        private boolean usesNestedNegation;
        private boolean usesEndAnchor;
        // Whether the text so far can end directly after a greedy .* or .+, with only empty matches
        // since.
        private boolean afterDotStar;
        // Pinned Joni's lexer field for a brace-less \pL, which greedy quantifiers and brace-less
        // properties set and reluctant quantifiers and braced properties outside a class clear.
        // While it is set, pinned Joni reads a braced property in a class as brace-less.
        private boolean joniBraceLessPropertyFlag;
        // Whether every path from the start of the pattern to here can match only empty text, and
        // whether one passes an anchor or boundary.
        private boolean zeroWidthPrefix = true;
        private boolean assertedPrefix;

        private PatternGenerator(Random random, boolean mayIgnoreCase)
        {
            this.random = requireNonNull(random, "random is null");
            this.mayIgnoreCase = mayIgnoreCase;
            List<String> candidates = new ArrayList<>(TEXT_CHARACTERS);
            candidates.addAll(ASCII_SYMBOLS);
            candidates.addAll(LITERAL_ESCAPE_LETTERS);
            if (!mayIgnoreCase) {
                candidates.addAll(FULL_FOLD_CHARACTERS);
            }
            List<String> palette = new ArrayList<>();
            for (int count = 3 + random.nextInt(5); count > 0; count--) {
                palette.add(candidates.get(random.nextInt(candidates.size())));
            }
            this.palette = List.copyOf(palette);
            this.characters.addAll(palette);
        }

        private Fragment expression(int depth, Scope scope)
        {
            int alternatives = random.nextInt(depth == 0 ? 3 : 4) == 0 ? 2 + random.nextInt(2) : 1;
            StringBuilder text = new StringBuilder();
            List<String> samples = new ArrayList<>();
            boolean nullable = false;
            boolean capturing = false;
            // An isolated option such as (?i) opens a group that runs to the end of the enclosing
            // group, so later alternatives follow the text before it: ab(?i)c|def is ab(?i:c|def).
            StringBuilder carriedSample = new StringBuilder();
            boolean carriedNullable = true;
            Position carriedPosition = position();
            boolean endsAfterDotStar = false;
            boolean endsZeroWidth = true;
            boolean endsAsserted = false;
            Shape shape = Shape.OTHER;
            for (int alternative = 0; alternative < alternatives; alternative++) {
                if (alternative > 0) {
                    text.append('|');
                }
                restore(carriedPosition);
                Sequence sequence = sequence(depth, scope);
                Fragment fragment = sequence.fragment();
                endsAfterDotStar |= afterDotStar;
                endsZeroWidth &= zeroWidthPrefix;
                endsAsserted |= assertedPrefix;
                shape = alternatives == 1 ? fragment.shape() : Shape.OTHER;
                text.append(fragment.text());
                samples.add(carriedSample + fragment.sample());
                nullable |= carriedNullable && fragment.nullable();
                capturing |= fragment.capturing();
                if (sequence.isolatedPrefixSample() != null) {
                    carriedSample.append(sequence.isolatedPrefixSample());
                    carriedNullable &= sequence.isolatedPrefixNullable();
                    carriedPosition = sequence.isolatedPrefixPosition();
                }
            }
            afterDotStar = endsAfterDotStar;
            zeroWidthPrefix = endsZeroWidth;
            assertedPrefix = endsAsserted;
            return new Fragment(text.toString(), samples.get(random.nextInt(samples.size())), nullable, capturing, true, shape);
        }

        private Sequence sequence(int depth, Scope scope)
        {
            int pieces = random.nextInt(depth == 0 ? 6 : 4);
            if (depth == 0 && pieces == 0 && random.nextInt(4) != 0) {
                pieces = 1;
            }
            StringBuilder text = new StringBuilder();
            StringBuilder sample = new StringBuilder();
            boolean nullable = true;
            boolean capturing = false;
            String isolatedPrefixSample = null;
            boolean isolatedPrefixNullable = true;
            Position isolatedPrefixPosition = null;
            Shape shape = Shape.OTHER;
            for (int piece = 0; piece < pieces; piece++) {
                if (scope.extended && random.nextInt(4) == 0) {
                    text.append(ignoredText());
                }
                if (random.nextInt(16) == 0) {
                    text.append(commentGroup());
                }
                if (random.nextInt(12) == 0) {
                    text.append(isolatedFlags(scope));
                    isolatedPrefixSample = sample.toString();
                    isolatedPrefixNullable = nullable;
                    isolatedPrefixPosition = position();
                    continue;
                }
                Fragment fragment = piece(depth, scope);
                // Both Jonis can wrap a long decimal escape into a backreference and Oniguruma
                // does not, a recorded difference (TRINO_REGEXP.md, Other matching differences
                // from Joni), so an empty group keeps a literal digit from extending an escape
                // past six digits, a conservative cap.
                if (endsWithLongDecimalEscape(text) && startsWithDigit(fragment.text())) {
                    text.append("(?:)");
                }
                text.append(fragment.text());
                sample.append(fragment.sample());
                nullable &= fragment.nullable();
                capturing |= fragment.capturing();
                if (pieces == 1) {
                    // A sequence of one piece is that piece to Joni, as comments are ignored.
                    shape = fragment.shape();
                }
            }
            return new Sequence(
                    new Fragment(text.toString(), sample.toString(), nullable, capturing, true, shape),
                    isolatedPrefixSample,
                    isolatedPrefixNullable,
                    isolatedPrefixPosition);
        }

        private Fragment piece(int depth, Scope scope)
        {
            Position before = position();
            Fragment atom = atom(depth, scope);
            if (!atom.quantifiable() || random.nextInt(10) >= 3) {
                return atom;
            }
            return quantified(atom, before, scope.dotAll);
        }

        /// Returns `atom` with a quantifier, and sometimes a second that repeats the first, as in
        /// `a**`. `before` is the position before the atom, and `dotAll` says whether `.` matches
        /// a line terminator there.
        private Fragment quantified(Fragment atom, Position before, boolean dotAll)
        {
            Quantifier first = quantifier(atom, null, before, dotAll);
            Fragment quantified = withQuantifier(atom, first, before);
            if (random.nextInt(8) != 0) {
                return quantified;
            }
            return withQuantifier(quantified, quantifier(quantified, first, before, dotAll), before);
        }

        private Fragment withQuantifier(Fragment atom, Quantifier quantifier, Position before)
        {
            // Pinned Joni keeps a quantifier's greedy flag where it later reads whether a
            // property in a class is brace-less; see the property operand in classOperand.
            joniBraceLessPropertyFlag = quantifier.greedy();
            boolean anyCharacter = isAnyCharacter(atom);
            if (anyCharacter && quantifier.greedy() && quantifier.unbounded()) {
                afterDotStar = true;
            }
            else if (quantifier.minimum() == 0) {
                afterDotStar |= before.afterDotStar();
            }
            if (quantifier.maximum() == 0) {
                zeroWidthPrefix = before.zeroWidth();
                assertedPrefix = before.asserted();
            }
            String separator = random.nextInt(10) == 0 ? commentGroup() : "";
            int repetitions = quantifier.minimum() == quantifier.sampleMaximum() || random.nextBoolean() ? quantifier.minimum() : quantifier.minimum() + 1;
            Shape shape = Shape.OTHER;
            if (quantifier.minimum() == 1 && quantifier.maximum() == 1) {
                // Joni drops a repeat of exactly one, so a later quantifier sees the atom.
                shape = atom.shape();
            }
            else if (quantifier.minimum() == 1 && quantifier.unbounded() && !quantifier.greedy()) {
                shape = Shape.RELUCTANT_PLUS;
            }
            else if (anyCharacter) {
                shape = Shape.REPEATED_ANY_CHARACTER;
            }
            return new Fragment(
                    atom.text() + separator + quantifier.text(),
                    atom.sample().repeat(repetitions),
                    atom.nullable() || quantifier.minimum() == 0,
                    atom.capturing(),
                    false,
                    shape);
        }

        /// Returns a quantifier for `atom` that leaves out the recorded differences. `previous` is
        /// the quantifier `atom` ends with, or null.
        private Quantifier quantifier(Fragment atom, Quantifier previous, Position before, boolean dotAll)
        {
            while (true) {
                Quantifier quantifier = randomQuantifier();
                // Directly after a greedy quantifier, ? makes it reluctant and + makes it
                // possessive, which is unsupported (TRINO_REGEXP.md, Unsupported features).
                if (previous != null && previous.greedy() && (quantifier.text().startsWith("?") || quantifier.text().startsWith("+"))) {
                    continue;
                }
                // Joni keeps capture histories in a loop whose body can match empty, a recorded
                // difference (REGULATOR_DECISIONS.md, Trino Compatibility Adapter, and
                // UNSUPPORTED_FEATURES.md), so a capture never sits in an unbounded repetition of
                // a nullable body.
                if (atom.nullable() && atom.capturing() && quantifier.unbounded()) {
                    continue;
                }
                // Pinned Joni's results for a body that can match empty, repeated with a count of
                // two or more, depend on how it compiles the repeat, and it fails on an empty
                // group repeated exactly 2 through 100 times, such as (?:){2}, recorded
                // differences (TRINO_REGEXP.md, Other matching differences from Joni), so such a
                // body takes only ?, *, +, or a count of at most one.
                if (atom.nullable() && quantifier.maximum() >= 2 && (quantifier.minimum() >= 2 || !quantifier.unbounded())) {
                    continue;
                }
                // Pinned Joni runs the body of a reluctant {0}? when what follows fails, a recorded
                // difference (TRINO_REGEXP.md, Other matching differences from Joni).
                if (!quantifier.greedy() && quantifier.maximum() == 0) {
                    continue;
                }
                // Pinned Joni reduces (?:x+?)+ to x+? and (?:x+?)* to (?:x+?)?, as modern Joni
                // does not, a recorded difference (TRINO_REGEXP.md, Other matching differences
                // from Joni).
                if (atom.shape() == Shape.RELUCTANT_PLUS && quantifier.greedy() && quantifier.unbounded() && quantifier.minimum() <= 1) {
                    continue;
                }
                // Pinned Joni searches only from the start for a dotall .* that only anchors and
                // boundaries precede, a recorded difference (TRINO_REGEXP.md, Other matching
                // differences from Joni).
                if (isAnyCharacter(atom) && dotAll && quantifier.greedy() && quantifier.unbounded() && quantifier.minimum() == 0 && before.zeroWidth() && before.asserted()) {
                    continue;
                }
                return quantifier;
            }
        }

        private Position position()
        {
            return new Position(afterDotStar, zeroWidthPrefix, assertedPrefix);
        }

        private void restore(Position position)
        {
            afterDotStar = position.afterDotStar();
            zeroWidthPrefix = position.zeroWidth();
            assertedPrefix = position.asserted();
        }

        private static boolean isAnyCharacter(Fragment atom)
        {
            return atom.shape() == Shape.ANY_CHARACTER || atom.shape() == Shape.REPEATED_ANY_CHARACTER;
        }

        private Quantifier randomQuantifier()
        {
            boolean greedy = random.nextInt(4) != 0;
            String suffix = greedy ? "" : "?";
            return switch (random.nextInt(6)) {
                case 0 -> new Quantifier("*" + suffix, 0, 2, true, greedy);
                case 1 -> new Quantifier("+" + suffix, 1, 2, true, greedy);
                case 2 -> new Quantifier("?" + suffix, 0, 1, false, greedy);
                case 3 -> {
                    int count = random.nextInt(4);
                    yield new Quantifier("{" + count + "}" + suffix, count, count, false, greedy);
                }
                case 4 -> {
                    int minimum = random.nextInt(3);
                    yield new Quantifier("{" + minimum + ",}" + suffix, minimum, minimum + 1, true, greedy);
                }
                default -> {
                    int minimum = random.nextInt(3);
                    int maximum = minimum + random.nextInt(3);
                    yield new Quantifier("{" + minimum + "," + maximum + "}" + suffix, minimum, maximum, false, greedy);
                }
            };
        }

        private Fragment atom(int depth, Scope scope)
        {
            boolean nested = depth < MAX_DEPTH;
            int choice = random.nextInt(nested ? 100 : 78);
            if (choice < 70) {
                Fragment atom = character(choice, depth, scope);
                afterDotStar = false;
                zeroWidthPrefix = false;
                return atom;
            }
            if (choice < 78) {
                return anchor();
            }
            if (choice < 94) {
                return group(depth, scope);
            }
            // A repetition directly inside a group, so the group's own quantifier nests it.
            boolean capturing = random.nextInt(3) == 0;
            String open = capturing ? openCapture(captureName()) : "(?:";
            Position before = position();
            boolean usedEndAnchor = usesEndAnchor;
            Fragment repeatedAtom;
            do {
                // Only an anchor is not quantifiable, and a discarded one leaves no state behind.
                restore(before);
                usesEndAnchor = usedEndAnchor;
                repeatedAtom = atom(depth + 1, scope.copy());
            }
            while (!repeatedAtom.quantifiable());
            Fragment repeated = quantified(repeatedAtom, before, scope.dotAll);
            return new Fragment(
                    open + repeated.text() + ")",
                    repeated.sample(),
                    repeated.nullable(),
                    capturing || repeated.capturing(),
                    true,
                    capturing ? Shape.OTHER : repeated.shape());
        }

        /// Returns an atom that matches one character, or a quoted run of them.
        private Fragment character(int choice, int depth, Scope scope)
        {
            if (choice < 26) {
                return literal(scope);
            }
            if (choice < 30) {
                return quote(scope);
            }
            if (choice < 35) {
                return new Fragment(".", randomCharacterExcept("\n"), false, false, true, Shape.ANY_CHARACTER);
            }
            if (choice < 42) {
                return perlClass(false);
            }
            if (choice < 48) {
                return property(false);
            }
            return bracketClass(depth, scope, false);
        }

        private Fragment literal(Scope scope)
        {
            String character = random.nextInt(5) == 0 ? randomTextCharacter() : palette.get(random.nextInt(palette.size()));
            characters.add(character);
            String sample = character;
            if (scope.caseInsensitive) {
                List<String> orbit = CASE_ORBIT_BY_CHARACTER.get(character);
                if (orbit != null) {
                    sample = orbit.get(random.nextInt(orbit.size()));
                    characters.addAll(orbit);
                }
            }
            return new Fragment(literalText(character.codePointAt(0), scope, false), sample, false, false, true);
        }

        private Fragment quote(Scope scope)
        {
            StringBuilder content = new StringBuilder();
            for (int count = 1 + random.nextInt(3); count > 0; count--) {
                content.append(palette.get(random.nextInt(palette.size())));
            }
            // As in Joni, a quantifier on the quote repeats only its last character, so a sample
            // that repeats the whole quote is a guess.
            String sample = content.toString();
            if (scope.caseInsensitive) {
                StringBuilder folded = new StringBuilder();
                for (String character : codePoints(sample)) {
                    List<String> orbit = CASE_ORBIT_BY_CHARACTER.get(character);
                    if (orbit == null) {
                        folded.append(character);
                    }
                    else {
                        folded.append(orbit.get(random.nextInt(orbit.size())));
                        characters.addAll(orbit);
                    }
                }
                sample = folded.toString();
            }
            return new Fragment("\\Q" + content + "\\E", sample, false, false, true);
        }

        /// Writes one character as a literal, raw or with one of the escapes that identify it.
        private String literalText(int codePoint, Scope scope, boolean inClass)
        {
            int form = random.nextInt(14);
            if (form == 0 && codePoint <= 0x7F) {
                return "\\x" + HexFormat.of().toHexDigits((byte) codePoint);
            }
            if (form == 1) {
                return "\\x{" + (random.nextBoolean() ? Integer.toHexString(codePoint) : HexFormat.of().withUpperCase().toHexDigits(codePoint)) + "}";
            }
            if (form == 2 && codePoint <= 0xFFFF) {
                return "\\u" + HexFormat.of().toHexDigits((short) codePoint);
            }
            if (form == 3) {
                boolean hasLetterOperand = codePoint >= 1 && codePoint <= 26;
                List<String> operands = NON_ASCII_CONTROL_OPERANDS.getOrDefault(codePoint, List.of());
                if (!operands.isEmpty() && (!hasLetterOperand || random.nextBoolean())) {
                    // Joni reads an escaped operand as itself.
                    return "\\c" + (random.nextBoolean() ? "\\" : "") + operands.get(random.nextInt(operands.size()));
                }
                if (hasLetterOperand) {
                    return "\\c" + (char) ((random.nextBoolean() ? 'A' : 'a') + codePoint - 1);
                }
            }
            // Octal escapes stay at or below \177: above it they are non-ASCII byte escapes,
            // which are unsupported (TRINO_REGEXP.md, Unsupported features).
            if (form == 4 && codePoint >= 1 && codePoint <= 0177) {
                return String.format(Locale.ROOT, "\\%03o", codePoint);
            }
            if (form == 5 && !inClass && LITERAL_ESCAPE_LETTERS.contains(Character.toString(codePoint))) {
                return "\\" + (char) codePoint;
            }
            return switch (codePoint) {
                case '\t' -> scope.extended || random.nextBoolean() ? "\\t" : "\t";
                case '\n' -> scope.extended || random.nextBoolean() ? "\\n" : "\n";
                case '\r' -> scope.extended || random.nextBoolean() ? "\\r" : "\r";
                case ' ', '#' -> scope.extended && !inClass ? "\\" + (char) codePoint : Character.toString(codePoint);
                case '\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}' -> "\\" + (char) codePoint;
                // Escaped inside a class so a dash is never read as a range, including after a
                // range, where pinned Joni can drop it (TRINO_REGEXP.md, Other matching differences
                // from Joni), and an ampersand never starts an intersection.
                case '-', '&' -> inClass ? "\\" + (char) codePoint : Character.toString(codePoint);
                default -> Character.toString(codePoint);
            };
        }

        private Fragment perlClass(boolean inClass)
        {
            int choice = random.nextInt(6);
            String text = "\\" + "dDsSwW".charAt(choice);
            String sample = switch (choice) {
                case 0 -> random.nextBoolean() ? "7" : "0";
                case 1 -> randomCharacterExcept("0");
                case 2 -> random.nextBoolean() ? " " : "\t";
                case 3 -> randomCharacterExcept(" ");
                case 4 -> random.nextBoolean() ? "_" : "k";
                default -> random.nextBoolean() ? "!" : "é";
            };
            characters.add(sample);
            return new Fragment(text, sample, false, false, !inClass);
        }

        private Fragment property(boolean inClass)
        {
            String sign = random.nextInt(4) == 0 ? "P" : "p";
            String text;
            if (!inClass && random.nextInt(6) == 0) {
                // A brace-less \pL is a property only outside a class.
                text = "\\" + sign + SHORT_PROPERTY_NAMES.get(random.nextInt(SHORT_PROPERTY_NAMES.size()));
                joniBraceLessPropertyFlag = true;
            }
            else {
                text = "\\" + sign + "{" + PROPERTY_NAMES.get(random.nextInt(PROPERTY_NAMES.size())) + "}";
                joniBraceLessPropertyFlag &= inClass;
            }
            return new Fragment(text, randomTextCharacter(), false, false, !inClass);
        }

        private Fragment anchor()
        {
            assertedPrefix = true;
            // Directly after a greedy .* or .+, pinned Joni's \b and \B read a stale previous
            // character, a recorded difference (TRINO_REGEXP.md, Other matching differences from
            // Joni).
            String text = switch (random.nextInt(afterDotStar ? 6 : 8)) {
                case 0, 1 -> "^";
                case 2, 3 -> {
                    usesEndAnchor = true;
                    yield "$";
                }
                case 4 -> "\\A";
                case 5 -> {
                    usesEndAnchor = true;
                    yield "\\z";
                }
                case 6 -> "\\b";
                default -> "\\B";
            };
            return new Fragment(text, "", true, false, false);
        }

        /// Returns a class. `nested` says whether it sits inside another class.
        private Fragment bracketClass(int depth, Scope scope, boolean nested)
        {
            StringBuilder text = new StringBuilder("[");
            // Pinned Joni can match or reject a non-ASCII character by its first byte in a class
            // that holds a negated class, a recorded difference (TRINO_REGEXP.md, Other matching
            // differences from Joni), so inputs of a pattern with a negated nested class are
            // ASCII only.
            boolean negated = random.nextInt(4) == 0;
            if (negated) {
                text.append('^');
                usesNestedNegation |= nested;
            }
            List<String> samples = new ArrayList<>();
            if (random.nextInt(12) == 0) {
                // A dash that starts a class is a literal.
                text.append('-');
                samples.add("-");
            }
            classOperand(depth, scope, text, samples);
            // An intersection operand is never empty; an empty one is unsupported (TRINO_REGEXP.md,
            // Unsupported features).
            for (int operands = 1; operands < 3 && random.nextInt(5) == 0; operands++) {
                text.append("&&");
                classOperand(depth, scope, text, new ArrayList<>());
            }
            text.append(']');
            String sample = negated ? randomTextCharacter() : samples.get(random.nextInt(samples.size()));
            return new Fragment(text.toString(), sample, false, false, true);
        }

        private void classOperand(int depth, Scope scope, StringBuilder text, List<String> samples)
        {
            for (int items = 1 + random.nextInt(3); items > 0; items--) {
                int choice = random.nextInt(depth + 1 < MAX_DEPTH ? 100 : 88);
                if (choice < 35) {
                    String character = random.nextInt(4) == 0 ? randomTextCharacter() : palette.get(random.nextInt(palette.size()));
                    characters.add(character);
                    text.append(literalText(character.codePointAt(0), scope, true));
                    samples.add(character);
                }
                else if (choice < 58) {
                    // Range endpoints are characters, never classes, which are unsupported as
                    // endpoints (TRINO_REGEXP.md, Unsupported features).
                    int first = randomTextCharacter().codePointAt(0);
                    int second = (random.nextBoolean() ? palette.get(random.nextInt(palette.size())) : randomTextCharacter()).codePointAt(0);
                    int low = Math.min(first, second);
                    int high = Math.max(first, second);
                    text.append(literalText(low, scope, true)).append('-').append(literalText(high, scope, true));
                    String endpoint = Character.toString(random.nextBoolean() ? low : high);
                    characters.add(endpoint);
                    samples.add(endpoint);
                }
                else if (choice < 70) {
                    Fragment perlClass = perlClass(true);
                    text.append(perlClass.text());
                    samples.add(perlClass.sample());
                }
                else if (choice < 79) {
                    // After a greedy quantifier or a brace-less \pL, pinned Joni reads a property
                    // in a class as brace-less, a recorded difference (TRINO_REGEXP.md, Other
                    // matching differences from Joni), so a Perl class stands in for it.
                    Fragment operand = joniBraceLessPropertyFlag ? perlClass(true) : property(true);
                    text.append(operand.text());
                    samples.add(operand.sample());
                }
                else if (choice < 88) {
                    String name = POSIX_BRACKET_NAMES.get(random.nextInt(POSIX_BRACKET_NAMES.size()));
                    usesBracketPunct |= name.equals("punct");
                    text.append("[:").append(random.nextInt(4) == 0 ? "^" : "").append(name).append(":]");
                    samples.add(randomTextCharacter());
                }
                else {
                    Fragment nestedClass = bracketClass(depth + 1, scope, true);
                    text.append(nestedClass.text());
                    samples.add(nestedClass.sample());
                }
            }
        }

        private Fragment group(int depth, Scope scope)
        {
            int choice = random.nextInt(10);
            if (choice < 3) {
                String open = openCapture(choice == 0 ? captureName() : null);
                Fragment inner = expression(depth + 1, scope.copy());
                return new Fragment(open + inner.text() + ")", inner.sample(), inner.nullable(), true, true);
            }
            if (choice < 6) {
                // Joni parses a noncapturing group as its contents.
                Fragment inner = expression(depth + 1, scope.copy());
                return new Fragment("(?:" + inner.text() + ")", inner.sample(), inner.nullable(), inner.capturing(), true, inner.shape());
            }
            Scope innerScope = scope.copy();
            String flags = flags(innerScope);
            Fragment expression = expression(depth + 1, innerScope);
            return new Fragment("(?" + flags + ":" + expression.text() + ")", expression.sample(), expression.nullable(), expression.capturing(), true);
        }

        /// Returns isolated flags such as `(?i)` and applies them to the rest of `scope`.
        private String isolatedFlags(Scope scope)
        {
            return "(?" + flags(scope) + ")";
        }

        /// Returns a flag list such as `i-s`, never empty, and applies it to `scope`. The flags
        /// `d`, `u`, and `U` are unsupported (TRINO_REGEXP.md, Unsupported features).
        private String flags(Scope scope)
        {
            String available = mayIgnoreCase ? "imsx" : "msx";
            StringBuilder enabled = new StringBuilder();
            StringBuilder disabled = new StringBuilder();
            while (enabled.isEmpty() && disabled.isEmpty()) {
                for (int i = 0; i < available.length(); i++) {
                    int choice = random.nextInt(5);
                    if (choice == 0) {
                        enabled.append(available.charAt(i));
                        scope.apply(available.charAt(i), true);
                    }
                    else if (choice == 1) {
                        disabled.append(available.charAt(i));
                        scope.apply(available.charAt(i), false);
                    }
                }
            }
            return disabled.isEmpty() ? enabled.toString() : enabled + "-" + disabled;
        }

        private String captureName()
        {
            int number = namedGroups.size();
            return switch (random.nextInt(6)) {
                case 0 -> "é" + number;
                case 1 -> "group_" + number;
                default -> "n" + number;
            };
        }

        private String openCapture(String name)
        {
            capturingGroupCount++;
            if (name == null) {
                return "(";
            }
            namedGroups.put(name, capturingGroupCount);
            return random.nextBoolean() ? "(?<" + name + ">" : "(?'" + name + "'";
        }

        /// Returns extended-mode whitespace or a comment, which `TrinoRegexp` and pinned Joni ignore.
        private String ignoredText()
        {
            return switch (random.nextInt(5)) {
                case 0 -> " ";
                case 1 -> "\t";
                case 2 -> "\n";
                case 3 -> "  ";
                default -> " # a comment (with | metacharacters\n";
            };
        }

        /// Returns a comment group, which `TrinoRegexp` and pinned Joni ignore outside a class.
        private String commentGroup()
        {
            return switch (random.nextInt(4)) {
                case 0 -> "(?#)";
                case 1 -> "(?#note)";
                case 2 -> "(?#a|b*[)";
                default -> "(?#escaped \\) paren)";
            };
        }

        private String randomTextCharacter()
        {
            List<String> alphabet = mayIgnoreCase ? TEXT_CHARACTERS : FULL_FOLD_AND_TEXT_CHARACTERS;
            return alphabet.get(random.nextInt(alphabet.size()));
        }

        private String randomCharacterExcept(String excluded)
        {
            while (true) {
                String character = randomTextCharacter();
                if (!character.equals(excluded)) {
                    return character;
                }
            }
        }

        /// Returns whether `text` ends with a backslash and six or more digits. Six is a
        /// conservative cap, not the length at which a number overflows an int.
        private static boolean endsWithLongDecimalEscape(CharSequence text)
        {
            int digits = 0;
            int index = text.length() - 1;
            while (index >= 0 && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
                digits++;
                index--;
            }
            return digits >= 6 && index >= 0 && text.charAt(index) == '\\';
        }

        private static boolean startsWithDigit(String text)
        {
            return !text.isEmpty() && text.charAt(0) >= '0' && text.charAt(0) <= '9';
        }
    }

    /// The flags a generated group sees that change what the generator writes or leaves out: `i`
    /// decides how literals are sampled, `x` how they are written and where ignored text goes, and
    /// `s` which quantifiers a `.` takes; `m` is not tracked.
    private static final class Scope
    {
        private boolean caseInsensitive;
        private boolean extended;
        private boolean dotAll;

        private Scope(boolean caseInsensitive, boolean extended, boolean dotAll)
        {
            this.caseInsensitive = caseInsensitive;
            this.extended = extended;
            this.dotAll = dotAll;
        }

        private Scope copy()
        {
            return new Scope(caseInsensitive, extended, dotAll);
        }

        private void apply(char flag, boolean enabled)
        {
            switch (flag) {
                case 'i' -> caseInsensitive = enabled;
                case 'x' -> extended = enabled;
                case 's' -> dotAll = enabled;
                default -> {}
            }
        }
    }

    /// How Joni reads a fragment, where that changes what a quantifier on it does.
    private enum Shape
    {
        ANY_CHARACTER,
        // A repetition of ., which a second quantifier can reduce to .* as in .?*.
        REPEATED_ANY_CHARACTER,
        // A reluctant repetition of at least one, such as x+? or (?:x+?).
        RELUCTANT_PLUS,
        OTHER,
    }

    private record Fragment(String text, String sample, boolean nullable, boolean capturing, boolean quantifiable, Shape shape)
    {
        private Fragment
        {
            requireNonNull(text, "text is null");
            requireNonNull(sample, "sample is null");
            requireNonNull(shape, "shape is null");
        }

        private Fragment(String text, String sample, boolean nullable, boolean capturing, boolean quantifiable)
        {
            this(text, sample, nullable, capturing, quantifiable, Shape.OTHER);
        }
    }

    /// A quantifier. A sample repeats its minimum, or one more when it allows that. `sampleMaximum`
    /// is the maximum of a bounded quantifier and only a hint for an unbounded one.
    private record Quantifier(String text, int minimum, int sampleMaximum, boolean unbounded, boolean greedy)
    {
        private Quantifier
        {
            requireNonNull(text, "text is null");
        }

        private int maximum()
        {
            return unbounded ? Integer.MAX_VALUE : sampleMaximum;
        }
    }

    /// What the text generated so far means for the next piece: whether it can end directly after
    /// a greedy `.*` or `.+` with only empty matches since, whether every path from the start of
    /// the pattern can match only empty text, and whether one passes an anchor or boundary.
    private record Position(boolean afterDotStar, boolean zeroWidth, boolean asserted) {}

    private record Sequence(Fragment fragment, String isolatedPrefixSample, boolean isolatedPrefixNullable, Position isolatedPrefixPosition)
    {
        private Sequence
        {
            requireNonNull(fragment, "fragment is null");
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

    private record GeneratedPattern(
            int patternIndex,
            String pattern,
            Map<String, Integer> namedGroups,
            boolean mayIgnoreCase,
            boolean usesBracketPunct,
            boolean usesNestedNegation,
            boolean usesEndAnchor,
            boolean nullable,
            List<String> characters,
            String sample)
    {
        private GeneratedPattern
        {
            requireNonNull(pattern, "pattern is null");
            namedGroups = Map.copyOf(requireNonNull(namedGroups, "namedGroups is null"));
            characters = List.copyOf(requireNonNull(characters, "characters is null"));
            requireNonNull(sample, "sample is null");
        }

        public String description()
        {
            return "pattern " + patternIndex + " " + javaLiteral(pattern);
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
    }
}
