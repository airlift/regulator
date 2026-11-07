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

import io.airlift.regulator.GoldenJsonl.JsonObject;
import io.airlift.regulator.Re2.ReplaceAllResult;
import io.airlift.regulator.TestingDifferentialFuzzer.CaseInput;
import io.airlift.regulator.TestingDifferentialFuzzer.Generated;
import io.airlift.regulator.TestingDifferentialFuzzer.Language;
import io.airlift.regulator.TestingDifferentialFuzzer.Oracle;
import io.airlift.regulator.TestingDifferentialFuzzer.OracleSkipException;
import io.airlift.regulator.TestingDifferentialFuzzer.PreparedPattern;
import io.airlift.regulator.TestingDifferentialFuzzer.Trace;
import io.airlift.regulator.TestingDifferentialFuzzer.Variant;
import io.airlift.regulator.TestingPatternGenerator.Vocabulary;
import io.airlift.regulator.TestingPatternTree.Alternation;
import io.airlift.regulator.TestingPatternTree.Anchor;
import io.airlift.regulator.TestingPatternTree.AnyCharacter;
import io.airlift.regulator.TestingPatternTree.CharacterClass;
import io.airlift.regulator.TestingPatternTree.ClassItem;
import io.airlift.regulator.TestingPatternTree.ClassLiteral;
import io.airlift.regulator.TestingPatternTree.ClassRange;
import io.airlift.regulator.TestingPatternTree.Comment;
import io.airlift.regulator.TestingPatternTree.Concat;
import io.airlift.regulator.TestingPatternTree.FlagGroup;
import io.airlift.regulator.TestingPatternTree.Group;
import io.airlift.regulator.TestingPatternTree.Literal;
import io.airlift.regulator.TestingPatternTree.Node;
import io.airlift.regulator.TestingPatternTree.OutsideDialect;
import io.airlift.regulator.TestingPatternTree.PerlClass;
import io.airlift.regulator.TestingPatternTree.PosixClass;
import io.airlift.regulator.TestingPatternTree.Property;
import io.airlift.regulator.TestingPatternTree.Quote;
import io.airlift.regulator.TestingPatternTree.Repeat;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static io.airlift.regulator.CompiledPattern.Anchor.ANCHOR_BOTH;
import static io.airlift.regulator.CompiledPattern.Anchor.ANCHOR_START;
import static io.airlift.regulator.CompiledPattern.Anchor.UNANCHORED;
import static io.airlift.regulator.TestingDifferentialFuzzer.groups;
import static io.airlift.regulator.TestingDifferentialFuzzer.hex;
import static io.airlift.regulator.TestingDifferentialFuzzer.scanPlanCoverage;
import static io.airlift.regulator.TestingDifferentialFuzzer.spans;
import static io.airlift.regulator.TestingOracleProcess.hexField;
import static io.airlift.regulator.TestingPatternGenerator.TEXT_CHARACTERS;
import static io.airlift.regulator.TestingPatternTree.foldsAddedAfter;
import static io.airlift.regulator.TestingPatternTree.quantifier;
import static io.airlift.regulator.TestingPatternTree.rangeHolds;
import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;

/// Differential fuzzing of [Re2] against pinned upstream RE2, through the `re2_golden`
/// executable built from `tools/re2-golden` and named by `-Dregulator.fuzz.re2Oracle=PATH`; see
/// [TestingDifferentialFuzzer]. Options are the bits below, one bit for each generated semantic
/// option. Upstream answers the first match of a pattern in a window under each anchor, and its
/// replacements, where GlobalReplace is the one upstream API that iterates over matches. A
/// matcher's `find()` and `count` advance one code point after an empty match, Regulator's own
/// rule (RE2.md), so `re2_golden` steps upstream's matches by that rule for them.
public class TestRe2Fuzz
{
    static final Language<Re2, Re2Compiled> LANGUAGE = new Re2Language();

    static final int LATIN1 = 1;
    static final int POSIX = 1 << 1;
    static final int LONGEST = 1 << 2;
    static final int LITERAL = 1 << 3;
    static final int NEVER_NEWLINE = 1 << 4;
    static final int DOT_MATCHES_NEWLINE = 1 << 5;
    static final int NEVER_CAPTURE = 1 << 6;
    static final int CASE_INSENSITIVE = 1 << 7;
    static final int PERL_CLASSES = 1 << 8;
    static final int WORD_BOUNDARY = 1 << 9;
    static final int ONE_LINE = 1 << 10;

    private static final Map<Integer, String> OPTION_NAMES = Map.ofEntries(
            entry(LATIN1, "TestRe2Fuzz.LATIN1"),
            entry(POSIX, "TestRe2Fuzz.POSIX"),
            entry(LONGEST, "TestRe2Fuzz.LONGEST"),
            entry(LITERAL, "TestRe2Fuzz.LITERAL"),
            entry(NEVER_NEWLINE, "TestRe2Fuzz.NEVER_NEWLINE"),
            entry(DOT_MATCHES_NEWLINE, "TestRe2Fuzz.DOT_MATCHES_NEWLINE"),
            entry(NEVER_CAPTURE, "TestRe2Fuzz.NEVER_CAPTURE"),
            entry(CASE_INSENSITIVE, "TestRe2Fuzz.CASE_INSENSITIVE"),
            entry(PERL_CLASSES, "TestRe2Fuzz.PERL_CLASSES"),
            entry(WORD_BOUNDARY, "TestRe2Fuzz.WORD_BOUNDARY"),
            entry(ONE_LINE, "TestRe2Fuzz.ONE_LINE"));

    // POSIX syntax leaves out Perl's extensions, which these options restore.
    private static final List<Integer> PERL_EXTENSIONS = List.of(PERL_CLASSES, WORD_BOUNDARY, ONE_LINE);

    private static final long SEED = 0x52_45_32_46_55_5A_5AL;
    private static final int PATTERNS = 600;

    private static final String ORACLE_PROPERTY = "regulator.fuzz.re2Oracle";

    // Characters whose properties and simple case folds are the same in Unicode 15.1, the data
    // of pinned upstream's tables, and in Regulator's pinned 18.0 (RE2.md), so U+0295, which
    // changed from Ll to Lo, is left out. Inputs are valid UTF-8 unless made malformed, so no
    // lone surrogate.
    private static final List<String> CHARACTERS = Stream.concat(
                    TEXT_CHARACTERS.stream(),
                    Stream.of(
                            "ß",
                            "ﬁ",
                            "İ",
                            "ı",
                            "ŉ",
                            "ΐ",
                            "θ",
                            "ϑ",
                            "Å",
                            "å",
                            "\u212B",
                            "ÿ",
                            "Ÿ",
                            "ā",
                            "×",
                            "ª",
                            "\u00AD",
                            "\u0080",
                            "\u2029",
                            "\f",
                            "\u000B",
                            "$",
                            "^",
                            "|",
                            "#",
                            "[",
                            "]",
                            "{",
                            "}",
                            "(",
                            ")",
                            "*",
                            "?",
                            "+",
                            "\\",
                            ":",
                            "<",
                            "="))
            .toList();

    // The characters of a Latin-1 pattern: one byte each.
    private static final List<String> LATIN1_CHARACTERS = CHARACTERS.stream().filter(character -> character.codePointAt(0) <= 0xFF).toList();

    // Upstream's groups: every General_Category value in UnicodeData.txt, the major classes,
    // scripts by long name, and Any (REGULATOR_DECISIONS.md, RE2 Unicode Property Groups).
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
            "Me",
            "N",
            "Nd",
            "Nl",
            "No",
            "P",
            "Pc",
            "Pd",
            "Ps",
            "Pe",
            "Pi",
            "Pf",
            "Po",
            "S",
            "Sm",
            "Sc",
            "Sk",
            "So",
            "Z",
            "Zs",
            "Zl",
            "Zp",
            "C",
            "Cc",
            "Cf",
            "Co",
            "Cs",
            "Any",
            "Latin",
            "Greek",
            "Cyrillic",
            "Han",
            "Hiragana",
            "Devanagari",
            "Deseret",
            "Common",
            "Inherited");

    private static final List<String> POSIX_CLASS_NAMES = List.of(
            "alnum",
            "alpha",
            "ascii",
            "blank",
            "cntrl",
            "digit",
            "graph",
            "lower",
            "print",
            "punct",
            "space",
            "upper",
            "word",
            "xdigit");

    private static final Vocabulary VOCABULARY = vocabulary(CHARACTERS);
    private static final Vocabulary LATIN1_VOCABULARY = vocabulary(LATIN1_CHARACTERS);

    // The rewrites for a pattern without captures and for one with them. The first ends with an
    // escaped backslash, which writes one backslash.
    private static final String GROUP_ZERO_REWRITE = "<\\0\\\\>";
    private static final String CAPTURE_REWRITE = "[\\1|\\0]";

    private static final List<String> ANCHORS = List.of("unanchored", "start", "both");

    private static final List<LetterPair> LETTER_PAIRS = List.of(
            new LetterPair('K', 'k', 0x212A, 0xE000),
            new LetterPair('S', 's', 0x17F, 0xE001));

    // Upstream's case folding is Unicode 15.1 data, and characters added since then fold only in
    // Regulator, some into the orbit of an older letter, as U+1DF95 joins sharp s
    // (REGULATOR_DECISIONS.md, Pinned Unicode Case Folding). A range that ignores case leaves
    // them out.
    private static final Set<Integer> NEWER_FOLDS = foldsAddedAfter("15.1");

    @Test
    public void testFuzz()
    {
        TestingDifferentialFuzzer.fuzz(LANGUAGE, SEED, PATTERNS);
    }

    @Test
    public void testScanPlanShapes()
    {
        TestingDifferentialFuzzer.assertScanPlanShapes(LANGUAGE, SEED);
    }

    // `C` is `\C`, one byte. RE2 has no class operators and no comments.
    private static Vocabulary vocabulary(List<String> characters)
    {
        return new Vocabulary(
                characters,
                PROPERTY_NAMES,
                List.of("L", "M", "N", "P", "S", "Z", "C"),
                POSIX_CLASS_NAMES,
                "dDsSwWC",
                List.of("^", "$", "\\A", "\\z", "\\b", "\\B"),
                "imsU",
                List.of("n", "g", "name"),
                true,
                "",
                6,
                0,
                (_, _, _, _) -> true);
    }

    private static Re2.Options options(int options)
    {
        return Re2.Options.defaults()
                .setEncoding((options & LATIN1) != 0 ? Re2.Options.Encoding.LATIN1 : Re2.Options.Encoding.UTF8)
                .setPosixSyntax((options & POSIX) != 0)
                .setLongestMatch((options & LONGEST) != 0)
                .setLiteral((options & LITERAL) != 0)
                .setNeverNewline((options & NEVER_NEWLINE) != 0)
                .setDotMatchesNewline((options & DOT_MATCHES_NEWLINE) != 0)
                .setNeverCapture((options & NEVER_CAPTURE) != 0)
                .setCaseSensitive((options & CASE_INSENSITIVE) == 0)
                .setPerlClasses((options & PERL_CLASSES) != 0)
                .setWordBoundary((options & WORD_BOUNDARY) != 0)
                .setOneLine((options & ONE_LINE) != 0);
    }

    private static String rewrite(int groupCount)
    {
        return groupCount == 0 ? GROUP_ZERO_REWRITE : CAPTURE_REWRITE;
    }

    // Find all stops after more matches than an input of `length` bytes can hold, which ends a
    // search that never advances.
    private static int findAllLimit(int length)
    {
        return length + 2;
    }

    private static CompiledPattern.Anchor anchor(String name)
    {
        return switch (name) {
            case "unanchored" -> UNANCHORED;
            case "start" -> ANCHOR_START;
            case "both" -> ANCHOR_BOTH;
            default -> throw new IllegalArgumentException("unknown anchor: " + name);
        };
    }

    private static final class Re2Language
            implements Language<Re2, Re2Compiled>
    {
        @Override
        public String name()
        {
            return "re2";
        }

        @Override
        public String replayName()
        {
            return "TestRe2Fuzz.LANGUAGE";
        }

        @Override
        public Generated generate(Random random)
        {
            int options = 0;
            if (random.nextInt(8) == 0) {
                options |= LATIN1;
            }
            if (random.nextInt(8) == 0) {
                options |= POSIX;
                for (int option : PERL_EXTENSIONS) {
                    if (random.nextBoolean()) {
                        options |= option;
                    }
                }
            }
            if (random.nextInt(4) == 0) {
                options |= LONGEST;
            }
            if (random.nextInt(16) == 0) {
                options |= LITERAL;
            }
            if (random.nextInt(10) == 0) {
                options |= NEVER_NEWLINE;
            }
            if (random.nextInt(8) == 0) {
                options |= DOT_MATCHES_NEWLINE;
            }
            if (random.nextInt(10) == 0) {
                options |= NEVER_CAPTURE;
            }
            if (random.nextInt(8) == 0) {
                options |= CASE_INSENSITIVE;
            }
            if (random.nextInt(6) == 0) {
                // Random trees rarely compile to a scan plan, so one pattern in six has a plan's
                // shape and options, and the bytecode variants compile a class for it. The share
                // bounds that extra compile work.
                return new Generated(TestingPatternGenerator.scanPlanShape(random), options & ~(LATIN1 | LONGEST | LITERAL | CASE_INSENSITIVE));
            }
            Node tree = TestingPatternGenerator.generate(random, (options & LATIN1) != 0 ? LATIN1_VOCABULARY : VOCABULARY);
            if (random.nextInt(6) == 0) {
                tree = TestingPatternGenerator.withLargeCounts(random, tree);
            }
            return new Generated(tree, options);
        }

        @Override
        public String render(Node tree, int options)
        {
            boolean caseInsensitive = (options & CASE_INSENSITIVE) != 0 || TestingPatternTree.contains(tree, element -> element instanceof FlagGroup group && group.enabled().indexOf('i') >= 0);
            return Re2Writer.write(tree, (options & LATIN1) != 0, caseInsensitive);
        }

        @Override
        public String describeOptions(int options)
        {
            return TestingDifferentialFuzzer.describeFlags(options, OPTION_NAMES);
        }

        @Override
        public String sample(Node tree, int options, String pattern, Random random)
        {
            if ((options & LITERAL) != 0) {
                return pattern;
            }
            return TestingPatternTree.sample(tree, random, (options & CASE_INSENSITIVE) != 0, inputCharacters(tree, options));
        }

        @Override
        public List<String> inputCharacters(Node tree, int options)
        {
            return (options & LATIN1) != 0 ? LATIN1_CHARACTERS : CHARACTERS;
        }

        @Override
        public String filterInput(Node tree, int options, String input)
        {
            // An explicit letter pair such as [Kk] matches only its two letters, though upstream's
            // alternation factoring widens a pair it merges with a neighboring alternative, as in
            // [Kk]|x, to the whole orbit (REGULATOR_DECISIONS.md, Pinned Unicode Case Folding;
            // RE2.md).
            Set<Integer> widened = widenedLetters(tree, options);
            if (widened.isEmpty()) {
                return input;
            }
            StringBuilder kept = new StringBuilder();
            for (int codePoint : input.codePoints().toArray()) {
                if (!widened.contains(codePoint)) {
                    kept.appendCodePoint(codePoint);
                }
            }
            return kept.toString();
        }

        // Latin-1 characters are one byte each; other characters keep their UTF-8 bytes, so an
        // input can still hold them.
        @Override
        public byte[] encode(String input, int options)
        {
            if ((options & LATIN1) == 0) {
                return input.getBytes(UTF_8);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int codePoint : input.codePoints().toArray()) {
                if (codePoint <= 0xFF) {
                    bytes.write(codePoint);
                }
                else {
                    bytes.writeBytes(Character.toString(codePoint).getBytes(UTF_8));
                }
            }
            return bytes.toByteArray();
        }

        // A Latin-1 input reads one character from each byte, so a malformed edit spells the
        // characters of its bytes there.
        @Override
        public Set<String> decodedCharacters(byte[] input, int options)
        {
            if ((options & LATIN1) == 0) {
                return TestingDifferentialFuzzer.utf8Characters(input);
            }
            Set<String> characters = new HashSet<>();
            for (byte value : input) {
                characters.add(Character.toString(value & 0xFF));
            }
            return characters;
        }

        @Override
        public byte[] malformed(Random random, byte[] input)
        {
            return TestingDifferentialFuzzer.malformedSequence(random, input);
        }

        @Override
        public boolean byteWindows(int options)
        {
            return (options & LATIN1) != 0;
        }

        @Override
        public boolean oracleReadsMalformedInput(int options)
        {
            return true;
        }

        @Override
        public Re2 compile(String pattern, int options, Variant variant)
        {
            Re2.Options re2Options = options(options).setGenerateBytecode(variant.generateBytecode());
            Slice slice = utf8Slice(pattern);
            return switch (variant.scanPlanMode()) {
                case DEFAULT -> Re2.compile(slice, re2Options);
                case FORCE_HANDOFF -> Re2.compileForcingScanPlanHandoffForTesting(slice, re2Options);
                case DISABLED -> Re2.compileWithoutScanPlanForTesting(slice, re2Options);
            };
        }

        @Override
        public Oracle<Re2Compiled> oracle()
        {
            return Re2Oracle.INSTANCE;
        }

        @Override
        public void operations(Re2 regexp, CaseInput input, Trace trace)
        {
            Slice source = input.source();
            int regionStart = input.regionStart();
            int regionEnd = input.regionEnd();
            int groupCount = regexp.capturingGroupCount();
            Map<String, Integer> names = new TreeMap<>(regexp.namedCapturingGroups());
            int limit = findAllLimit(input.bytes().length);
            trace.add("groups", () -> groupCount + " " + names);
            int[] groups = new int[2 * (groupCount + 1)];
            for (String anchor : ANCHORS) {
                trace.add("match " + anchor, () -> into(regexp.matchInto(source, 0, source.length(), anchor(anchor), groups), groups));
                trace.add("match " + anchor + " range", () -> into(regexp.matchInto(source, regionStart, regionEnd, anchor(anchor), groups), groups));
            }
            trace.add("find", () -> regexp.find(source));
            trace.add("lookingAt", () -> regexp.lookingAt(source));
            trace.add("matches", () -> regexp.matches(source));
            trace.add("findResult", () -> result(regexp.findResult(source)));
            trace.add("lookingAtResult", () -> result(regexp.lookingAtResult(source)));
            trace.add("matchesResult", () -> result(regexp.matchesResult(source)));
            int[] groupZero = new int[2];
            trace.add("findInto", () -> into(regexp.findInto(source, groups), groups));
            trace.add("findInto group zero", () -> into(regexp.findInto(source, groupZero), groupZero));
            trace.add("lookingAtInto", () -> into(regexp.lookingAtInto(source, groups), groups));
            trace.add("matchesInto", () -> into(regexp.matchesInto(source, groups), groups));
            trace.add("find range", () -> regexp.find(source, regionStart, regionEnd));
            trace.add("lookingAt range", () -> regexp.lookingAt(source, regionStart, regionEnd));
            trace.add("matches range", () -> regexp.matches(source, regionStart, regionEnd));
            trace.add("findResult range", () -> result(regexp.findResult(source, regionStart, regionEnd)));
            trace.add("lookingAtResult range", () -> result(regexp.lookingAtResult(source, regionStart, regionEnd)));
            trace.add("matchesResult range", () -> result(regexp.matchesResult(source, regionStart, regionEnd)));
            trace.add("findInto range", () -> into(regexp.findInto(source, regionStart, regionEnd, groups), groups));
            trace.add("lookingAtInto range", () -> into(regexp.lookingAtInto(source, regionStart, regionEnd, groups), groups));
            trace.add("matchesInto range", () -> into(regexp.matchesInto(source, regionStart, regionEnd, groups), groups));
            Slice rewrite = utf8Slice(rewrite(groupCount));
            trace.add("replaceFirst", () -> hex(regexp.replaceFirst(source, rewrite)));
            trace.add("replaceAll", () -> {
                ReplaceAllResult result = regexp.replaceAll(source, rewrite);
                return result.replacementCount() + " " + hex(result.result());
            });
            trace.add("extract", () -> hex(regexp.extract(source, rewrite)));

            RegexpMatcher matcher = regexp.matcher(source);
            trace.add("find all", () -> findAll(matcher, names, limit));
            trace.add("count", () -> regexp.count(source));
            trace.add("region find all", () -> findAll(matcher.reset(source, regionStart, regionEnd), names, limit));
            trace.add("find from", () -> current(matcher.reset(source, regionStart, regionEnd).find(input.searchStart()), matcher));
            trace.add("group zero find all", () -> findAll(regexp.matcher(source, 0), Map.of(), limit));
        }

        @Override
        public boolean variantsAgreeOnMalformedInput()
        {
            return true;
        }

        @Override
        public Set<String> coverage(Re2 compiled)
        {
            return scanPlanCoverage(compiled);
        }
    }

    // Returns the letters upstream adds to the letter pairs of a pattern. Upstream's parser
    // reads a case-sensitive class of exactly an ASCII letter's two cases as that letter folded,
    // and its alternation factoring folds a letter it merges into one class with neighboring
    // alternatives by the whole orbit, which adds the Kelvin sign to [Kk] and the long s to
    // [Ss]. Regulator's factoring is a port of upstream's, so parsing the pattern with a
    // private-use marker for each pair shows the merges: a parsed class that holds a pair's
    // marker but not the letter the orbit adds is a pair upstream widens. Latin-1 holds neither
    // letter, and a literal pattern has no class.
    private static Set<Integer> widenedLetters(Node tree, int options)
    {
        if ((options & (LATIN1 | LITERAL)) != 0) {
            return Set.of();
        }
        Node marked = markLetterPairs(tree, (options & CASE_INSENSITIVE) != 0);
        if (marked.equals(tree)) {
            return Set.of();
        }
        String pattern = LANGUAGE.render(marked, options);
        Regexp regexp;
        try {
            regexp = RegexpParser.parse(utf8Slice(pattern), options(options).parseFlags()).regexp();
        }
        catch (RegexpParseException _) {
            // The compile comparison reports a pattern that does not parse.
            return Set.of();
        }
        Set<Integer> widened = new HashSet<>();
        addWidenedLetters(regexp, widened);
        return widened;
    }

    // Returns `node` with each case-sensitive letter pair as its marker. Under case-insensitive
    // matching a pair already holds the whole orbit, as upstream's does.
    private static Node markLetterPairs(Node node, boolean caseInsensitive)
    {
        if (node instanceof CharacterClass characterClass) {
            Set<Integer> members = new HashSet<>();
            if (caseInsensitive || characterClass.negated() || !addMembers(characterClass, members)) {
                return node;
            }
            for (LetterPair pair : LETTER_PAIRS) {
                if (members.equals(Set.of(pair.upper(), pair.lower()))) {
                    return new Literal(pair.marker(), 0);
                }
            }
            return node;
        }
        boolean inner = caseInsensitive;
        if (node instanceof FlagGroup group) {
            inner = group.enabled().indexOf('i') >= 0 || (caseInsensitive && group.disabled().indexOf('i') < 0);
        }
        List<Node> children = TestingPatternTree.children(node);
        Node marked = node;
        for (int index = 0; index < children.size(); index++) {
            marked = TestingPatternTree.withChild(marked, index, markLetterPairs(children.get(index), inner));
        }
        return marked;
    }

    private static void addWidenedLetters(Regexp regexp, Set<Integer> widened)
    {
        if (regexp.op() == RegexpOp.CHAR_CLASS) {
            for (LetterPair pair : LETTER_PAIRS) {
                if (regexp.charClass().contains(pair.marker()) && !regexp.charClass().contains(pair.added())) {
                    widened.add(pair.added());
                }
            }
        }
        for (Regexp child : regexp.children()) {
            addWidenedLetters(child, widened);
        }
    }

    // Adds the members of a class of literals, ranges, and nested positive classes of them, or
    // returns false for a class with any other item, which holds more than two letters.
    private static boolean addMembers(CharacterClass characterClass, Set<Integer> members)
    {
        for (List<ClassItem> operand : characterClass.operands()) {
            for (ClassItem item : operand) {
                switch (item) {
                    case ClassLiteral literal -> members.add(literal.codePoint());
                    // A wider range holds more than two letters.
                    case ClassRange range when range.high() - range.low() < 2 -> {
                        for (int codePoint = range.low(); codePoint <= range.high(); codePoint++) {
                            members.add(codePoint);
                        }
                    }
                    case CharacterClass nested when !nested.negated() -> {
                        if (!addMembers(nested, members)) {
                            return false;
                        }
                    }
                    default -> {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static String findAll(RegexpMatcher matcher, Map<String, Integer> names, int limit)
    {
        List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            if (matches.size() == limit) {
                matches.add("more than " + limit);
                break;
            }
            StringBuilder match = new StringBuilder(spans(groups(matcher)));
            for (Map.Entry<String, Integer> name : names.entrySet()) {
                match.append(' ').append(name.getKey()).append('=').append(hex(matcher.group(name.getValue())));
            }
            matches.add(match.toString());
        }
        return matches.toString();
    }

    private static String current(boolean matched, RegexpMatcher matcher)
    {
        return matched ? spans(groups(matcher)) : "none";
    }

    private static String result(MatchResult result)
    {
        return result == null ? "none" : spans(groups(result));
    }

    private static String into(boolean matched, int[] groups)
    {
        return matched ? spans(groups) : "none";
    }

    // A letter pair upstream can widen: its two letters, the letter the whole orbit adds, and the
    // private-use marker that stands in for the pair.
    private record LetterPair(int upper, int lower, int added, int marker) {}

    /// A pattern upstream compiled: its row fields and capture metadata.
    record Re2Compiled(String patternHex, int options, int groupCount, Map<String, Integer> names) {}

    /// Pinned upstream RE2 as the oracle, through `re2_golden --mode=options_match`,
    /// `--mode=options_replace`, and `--mode=options_find_all`. A pattern compiles as a
    /// replacement of the empty text, which reports its capture metadata; each case asks for its
    /// matches with every group under each anchor, over the whole input and over the region, for
    /// the first match from the search start, for its replacements, and for its successive
    /// matches over the whole input and over the region, stepped as Regulator's matcher steps.
    private static final class Re2Oracle
            implements Oracle<Re2Compiled>
    {
        private static final Re2Oracle INSTANCE = new Re2Oracle(TestingOracleProcess.binary(ORACLE_PROPERTY));

        private final TestingOracleProcess match;
        private final TestingOracleProcess replace;
        private final TestingOracleProcess findAll;

        private Re2Oracle(Path binary)
        {
            this.match = binary == null ? null : new TestingOracleProcess(binary, List.of("--mode=options_match"));
            this.replace = binary == null ? null : new TestingOracleProcess(binary, List.of("--mode=options_replace"));
            this.findAll = binary == null ? null : new TestingOracleProcess(binary, List.of("--mode=options_find_all"));
        }

        @Override
        public boolean available()
        {
            return match != null;
        }

        @Override
        public void prepare(List<PreparedPattern> patterns)
        {
            replace.prepare(patterns.stream().map(pattern -> compileRow(pattern.pattern(), pattern.options())).toList());
            List<String> matchRows = new ArrayList<>();
            List<String> replaceRows = new ArrayList<>();
            List<String> findAllRows = new ArrayList<>();
            for (PreparedPattern pattern : patterns) {
                Re2Compiled compiled;
                try {
                    compiled = compile(pattern.pattern(), pattern.options());
                }
                catch (RuntimeException _) {
                    // A pattern upstream rejects or the oracle skips has no case rows; the
                    // compile comparison reports or skips it.
                    continue;
                }
                for (CaseInput input : pattern.inputs()) {
                    for (String anchor : ANCHORS) {
                        matchRows.add(matchRow(compiled, input.bytes(), 0, input.bytes().length, anchor));
                        matchRows.add(matchRow(compiled, input.bytes(), input.regionStart(), input.regionEnd(), anchor));
                    }
                    matchRows.add(matchRow(compiled, input.bytes(), input.searchStart(), input.bytes().length, "unanchored"));
                    replaceRows.add(replaceRow(compiled.patternHex(), compiled.options(), input.bytes(), rewrite(compiled.groupCount())));
                    findAllRows.add(findAllRow(compiled, input.bytes(), 0, input.bytes().length));
                    findAllRows.add(findAllRow(compiled, input.bytes(), input.regionStart(), input.regionEnd()));
                }
            }
            match.prepare(matchRows);
            replace.prepare(replaceRows);
            findAll.prepare(findAllRows);
        }

        @Override
        public Re2Compiled compile(String pattern, int options)
        {
            JsonObject json = replace.get(compileRow(pattern, options));
            if (!json.getBoolean("ok")) {
                String error = json.getString("error");
                // Upstream's default memory budget is 8 MiB, Regulator's 96 MiB
                // (REGULATOR_DECISIONS.md, Memory Budgets And DFA Caching).
                if (error.startsWith("pattern too large")) {
                    throw new OracleSkipException("oracle pattern too large");
                }
                throw new IllegalArgumentException(error);
            }
            Map<String, Integer> names = new TreeMap<>();
            String groupNames = json.getString("groupNames");
            if (!groupNames.isEmpty()) {
                // Upstream's parser keeps named groups capturing under never_capture, which
                // Regulator does not (REGULATOR_DECISIONS.md, RE2 Never-Capture Option).
                if ((options & NEVER_CAPTURE) != 0) {
                    throw new OracleSkipException("named group under never_capture");
                }
                for (String entry : groupNames.split(",")) {
                    int equals = entry.lastIndexOf('=');
                    names.put(entry.substring(0, equals), Integer.parseInt(entry.substring(equals + 1)));
                }
            }
            return new Re2Compiled(hexField(pattern.getBytes(UTF_8)), options, Integer.parseInt(json.getString("groupCount")), names);
        }

        @Override
        public void operations(Re2Compiled compiled, CaseInput input, Trace trace, long deadlineNanos)
        {
            byte[] bytes = input.bytes();
            int groupCount = compiled.groupCount();
            trace.add("groups", () -> groupCount + " " + compiled.names());
            Map<String, String> whole = new TreeMap<>();
            Map<String, String> region = new TreeMap<>();
            for (String anchor : ANCHORS) {
                String wholeSpans = spansOf(match.get(matchRow(compiled, bytes, 0, bytes.length, anchor)));
                String regionSpans = spansOf(match.get(matchRow(compiled, bytes, input.regionStart(), input.regionEnd(), anchor)));
                whole.put(anchor, wholeSpans);
                region.put(anchor, regionSpans);
                trace.add("match " + anchor, () -> wholeSpans);
                trace.add("match " + anchor + " range", () -> regionSpans);
            }
            String find = whole.get("unanchored");
            String lookingAt = whole.get("start");
            String matches = whole.get("both");
            trace.add("find", () -> !find.equals("none"));
            trace.add("lookingAt", () -> !lookingAt.equals("none"));
            trace.add("matches", () -> !matches.equals("none"));
            trace.add("findResult", () -> find);
            trace.add("lookingAtResult", () -> lookingAt);
            trace.add("matchesResult", () -> matches);
            trace.add("findInto", () -> find);
            trace.add("findInto group zero", () -> groupZero(find));
            trace.add("lookingAtInto", () -> lookingAt);
            trace.add("matchesInto", () -> matches);
            String rangeFind = region.get("unanchored");
            String rangeLookingAt = region.get("start");
            String rangeMatches = region.get("both");
            trace.add("find range", () -> !rangeFind.equals("none"));
            trace.add("lookingAt range", () -> !rangeLookingAt.equals("none"));
            trace.add("matches range", () -> !rangeMatches.equals("none"));
            trace.add("findResult range", () -> rangeFind);
            trace.add("lookingAtResult range", () -> rangeLookingAt);
            trace.add("matchesResult range", () -> rangeMatches);
            trace.add("findInto range", () -> rangeFind);
            trace.add("lookingAtInto range", () -> rangeLookingAt);
            trace.add("matchesInto range", () -> rangeMatches);

            String rewrite = rewrite(groupCount);
            JsonObject replaced = replace.get(replaceRow(compiled.patternHex(), compiled.options(), bytes, rewrite));
            if (!replaced.getBoolean("rewriteOk")) {
                throw new IllegalStateException("upstream rejected the rewrite %s of %s: %s".formatted(rewrite, compiled, replaced.getString("rewriteError")));
            }
            String text = HexFormat.of().formatHex(bytes);
            trace.add("replaceFirst", () -> replaced.getBoolean("replaceMatched") ? replaced.getString("replaceHex") : text);
            int replacements = Integer.parseInt(replaced.getString("globalCount"));
            trace.add("replaceAll", () -> replacements + " " + (replacements > 0 ? replaced.getString("globalHex") : text));
            trace.add("extract", () -> replaced.getBoolean("extractMatched") ? replaced.getString("extractHex") : "null");

            // find(int) searches the whole input from the search start.
            String from = spansOf(match.get(matchRow(compiled, bytes, input.searchStart(), bytes.length, "unanchored")));
            trace.add("find from", () -> from);
            int limit = findAllLimit(bytes.length);
            JsonObject all = findAll.get(findAllRow(compiled, bytes, 0, bytes.length));
            List<int[]> allMatches = matchesOf(all);
            trace.add("find all", () -> format(allMatches, all.getBoolean("more"), compiled.names(), bytes, groupCount + 1, limit));
            if (!all.getBoolean("more")) {
                trace.add("count", allMatches::size);
            }
            trace.add("group zero find all", () -> format(allMatches, all.getBoolean("more"), Map.of(), bytes, 1, limit));
            JsonObject regionAll = findAll.get(findAllRow(compiled, bytes, input.regionStart(), input.regionEnd()));
            trace.add("region find all", () -> format(matchesOf(regionAll), regionAll.getBoolean("more"), compiled.names(), bytes, groupCount + 1, limit));
        }

        // The successive matches' groups, the start and end of each.
        private static List<int[]> matchesOf(JsonObject json)
        {
            String value = requireCompiled(json).getString("matches");
            List<int[]> matches = new ArrayList<>();
            if (value.isEmpty()) {
                return matches;
            }
            for (String match : value.split(";")) {
                matches.add(groupOffsets(match));
            }
            return matches;
        }

        private static String spansOf(JsonObject json)
        {
            if (!requireCompiled(json).getBoolean("matched")) {
                return "none";
            }
            return spans(groupOffsets(json.getString("groups")));
        }

        // The start and end of each group, from upstream's comma-separated start:end ranges.
        private static int[] groupOffsets(String match)
        {
            String[] ranges = match.split(",");
            int[] groups = new int[2 * ranges.length];
            for (int group = 0; group < ranges.length; group++) {
                String[] offsets = ranges[group].split(":");
                groups[2 * group] = Integer.parseInt(offsets[0]);
                groups[2 * group + 1] = Integer.parseInt(offsets[1]);
            }
            return groups;
        }

        // Returns a row of a pattern upstream compiled, which the oracle answers only after its
        // compile row succeeded.
        private static JsonObject requireCompiled(JsonObject json)
        {
            if (!json.getBoolean("ok")) {
                throw new IllegalStateException("upstream rejected a pattern it compiled: " + json);
            }
            return json;
        }

        // Writes the matches as findAll does, with the first `groups` groups of each.
        private static String format(List<int[]> matches, boolean more, Map<String, Integer> names, byte[] bytes, int groups, int limit)
        {
            List<String> formatted = new ArrayList<>();
            for (int[] match : matches) {
                StringBuilder value = new StringBuilder(spans(Arrays.copyOf(match, 2 * groups)));
                for (Map.Entry<String, Integer> name : names.entrySet()) {
                    int number = name.getValue();
                    value.append(' ').append(name.getKey()).append('=').append(match[2 * number] < 0 ? "null" : HexFormat.of().formatHex(bytes, match[2 * number], match[2 * number + 1]));
                }
                formatted.add(value.toString());
            }
            if (more) {
                formatted.add("more than " + limit);
            }
            return formatted.toString();
        }

        // The span of group zero alone, from the spans of every group.
        private static String groupZero(String spans)
        {
            int space = spans.indexOf(' ');
            return space < 0 ? spans : spans.substring(0, space) + "]";
        }

        private static String compileRow(String pattern, int options)
        {
            return replaceRow(hexField(pattern.getBytes(UTF_8)), options, new byte[0], "\\0");
        }

        private static String matchRow(Re2Compiled compiled, byte[] text, int start, int end, String anchor)
        {
            return String.join("\t", compiled.patternHex(), hexField(text), Integer.toString(start), Integer.toString(end), anchor, optionFields(compiled.options()), Integer.toString(compiled.groupCount() + 1));
        }

        private static String findAllRow(Re2Compiled compiled, byte[] text, int start, int end)
        {
            return String.join("\t", compiled.patternHex(), hexField(text), Integer.toString(start), Integer.toString(end), optionFields(compiled.options()), Integer.toString(compiled.groupCount() + 1), Integer.toString(findAllLimit(text.length)));
        }

        private static String replaceRow(String patternHex, int options, byte[] text, String rewrite)
        {
            return String.join("\t", patternHex, hexField(text), optionFields(options), hexField(rewrite.getBytes(UTF_8)));
        }

        private static String optionFields(int options)
        {
            return String.join(
                    "\t",
                    (options & LATIN1) != 0 ? "latin1" : "utf8",
                    flag(options, POSIX),
                    flag(options, LONGEST),
                    flag(options, LITERAL),
                    flag(options, NEVER_NEWLINE),
                    flag(options, DOT_MATCHES_NEWLINE),
                    flag(options, NEVER_CAPTURE),
                    String.valueOf((options & CASE_INSENSITIVE) == 0),
                    flag(options, PERL_CLASSES),
                    flag(options, WORD_BOUNDARY),
                    flag(options, ONE_LINE));
        }

        private static String flag(int options, int option)
        {
            return String.valueOf((options & option) != 0);
        }
    }

    /// Writes a tree in RE2 syntax, or returns `null` when the tree is outside the dialect. A
    /// Latin-1 pattern is written in ASCII, its other characters escaped, so its bytes mean the
    /// same in either encoding.
    private static final class Re2Writer
    {
        private static final String METACHARACTERS = "\\.+*?()|[]{}^$";
        private static final String CLASS_METACHARACTERS = "\\^-[]";

        private final StringBuilder out = new StringBuilder();
        private final boolean latin1;
        private final boolean caseInsensitive;

        private Re2Writer(boolean latin1, boolean caseInsensitive)
        {
            this.latin1 = latin1;
            this.caseInsensitive = caseInsensitive;
        }

        static String write(Node tree, boolean latin1, boolean caseInsensitive)
        {
            Re2Writer writer = new Re2Writer(latin1, caseInsensitive);
            try {
                writer.node(tree, true);
            }
            catch (OutsideDialect _) {
                return null;
            }
            return writer.out.toString();
        }

        private void node(Node node, boolean endsGroup)
        {
            switch (node) {
                case Literal literal -> out.append(literal(literal.codePoint(), literal.style(), false));
                case Quote quote -> {
                    // A backslash in a quote could end it, and a Latin-1 quote cannot escape
                    // its characters.
                    if (quote.text().contains("\\") || (latin1 && !quote.text().chars().allMatch(character -> character < 0x80))) {
                        throw new OutsideDialect();
                    }
                    out.append("\\Q").append(quote.text()).append("\\E");
                }
                case AnyCharacter _ -> out.append('.');
                // The letters include C: \C matches any one byte, even inside a UTF-8
                // character, and is valid only outside a class.
                case PerlClass perlClass -> out.append('\\').append(perlClass.letter());
                case Property property -> out.append(property(property));
                case CharacterClass characterClass -> {
                    out.append('[');
                    if (characterClass.negated()) {
                        out.append('^');
                    }
                    classItems(characterClass, true);
                    out.append(']');
                }
                case Anchor anchor -> out.append(anchor.text());
                case Comment _ -> {}
                case Group group -> {
                    if (!group.capturing()) {
                        out.append("(?:");
                    }
                    else if (group.name() != null) {
                        // The name's hash picks one of the two spellings, so a name is always
                        // written the same way.
                        out.append((group.name().hashCode() & 1) == 0 ? "(?P<" : "(?<").append(group.name()).append('>');
                    }
                    else {
                        out.append('(');
                    }
                    node(group.body(), true);
                    out.append(')');
                }
                case FlagGroup group -> {
                    String flags = group.disabled().isEmpty() ? group.enabled() : group.enabled() + "-" + group.disabled();
                    // Isolated flags apply to the rest of the enclosing group, later alternatives
                    // included, so they are written isolated only where no alternative follows.
                    // Nothing repeats an isolated flag group: a quantifier there is an error
                    // (a(?i)* is rejected).
                    if (group.isolated() && endsGroup && !(group.body() instanceof Alternation)) {
                        out.append("(?").append(flags).append(')');
                        node(group.body(), true);
                    }
                    else {
                        out.append("(?").append(flags).append(':');
                        node(group.body(), true);
                        out.append(')');
                    }
                }
                case Concat concat -> {
                    List<Node> items = concat.items();
                    for (int index = 0; index < items.size(); index++) {
                        Node item = items.get(index);
                        if (item instanceof Alternation && items.size() > 1) {
                            out.append("(?:");
                            node(item, true);
                            out.append(')');
                        }
                        else {
                            node(item, endsGroup && index == items.size() - 1);
                        }
                    }
                }
                case Alternation alternation -> {
                    List<Node> alternatives = alternation.alternatives();
                    for (int index = 0; index < alternatives.size(); index++) {
                        if (index > 0) {
                            out.append('|');
                        }
                        node(alternatives.get(index), endsGroup && index == alternatives.size() - 1);
                    }
                }
                case Repeat repeat -> {
                    // Perl syntax rejects a quantifier directly after another, so a repeated
                    // repetition is grouped.
                    Node body = repeat.body();
                    boolean atom = body instanceof Literal || body instanceof Quote || body instanceof AnyCharacter || body instanceof PerlClass ||
                            body instanceof Property || body instanceof CharacterClass || body instanceof Group || body instanceof FlagGroup;
                    if (atom) {
                        node(body, false);
                    }
                    else {
                        out.append("(?:");
                        node(body, true);
                        out.append(')');
                    }
                    out.append(quantifier(repeat));
                }
            }
        }

        // Writes the items of a class. RE2 classes do not nest, so a nested class's items join
        // its parent's, which needs the nested class to be a plain union.
        private void classItems(CharacterClass characterClass, boolean outermost)
        {
            if (characterClass.operands().size() != 1) {
                throw new OutsideDialect();
            }
            List<ClassItem> items = characterClass.operands().getFirst();
            for (int index = 0; index < items.size(); index++) {
                ClassItem item = items.get(index);
                if (outermost && index == 0 && item instanceof ClassLiteral(int codePoint, int style) && codePoint == '-' && style == 0) {
                    // A dash that starts a class is a literal.
                    out.append('-');
                    continue;
                }
                switch (item) {
                    case ClassLiteral literal -> out.append(literal(literal.codePoint(), literal.style(), true));
                    case ClassRange range -> {
                        if (caseInsensitive && rangeHolds(range, NEWER_FOLDS)) {
                            throw new OutsideDialect();
                        }
                        out.append(literal(range.low(), range.lowStyle(), true)).append('-').append(literal(range.high(), range.highStyle(), true));
                    }
                    case PerlClass perlClass -> {
                        // \C is an escape error in an RE2 class.
                        if (perlClass.letter() == 'C') {
                            throw new OutsideDialect();
                        }
                        out.append('\\').append(perlClass.letter());
                    }
                    case Property property -> out.append(property(property));
                    case PosixClass posixClass -> out.append("[:").append(posixClass.negated() ? "^" : "").append(posixClass.name()).append(":]");
                    case CharacterClass nested -> {
                        if (nested.negated()) {
                            throw new OutsideDialect();
                        }
                        classItems(nested, false);
                    }
                }
            }
        }

        // \pL and \p{Greek}, negated as \PL, \P{Greek}, or \p{^Greek}.
        private static String property(Property property)
        {
            if (property.braceless() && property.name().length() == 1) {
                return (property.negated() ? "\\P" : "\\p") + property.name();
            }
            if (!property.negated()) {
                return "\\p{" + property.name() + "}";
            }
            // The name's hash picks one of the two spellings, so a name is always written the
            // same way.
            return (property.name().hashCode() & 1) == 0 ? "\\P{" + property.name() + "}" : "\\p{^" + property.name() + "}";
        }

        /// Writes a character in the escape `style` names where that escape can spell it, and
        /// otherwise plainly, with a backslash where RE2 would read it as syntax.
        private String literal(int codePoint, int style, boolean inClass)
        {
            switch (style) {
                case 1 -> {
                    if (codePoint <= 0xFF) {
                        return "\\x" + HexFormat.of().toHexDigits((byte) codePoint);
                    }
                }
                case 2 -> {
                    return "\\x{" + Integer.toHexString(codePoint) + "}";
                }
                case 3 -> {
                    // Three octal digits, so a digit after the escape stays a literal.
                    if (codePoint <= 0777) {
                        return String.format(Locale.ROOT, "\\%03o", codePoint);
                    }
                }
                case 4 -> {
                    String escape = switch (codePoint) {
                        case 0x07 -> "\\a";
                        case '\f' -> "\\f";
                        case '\t' -> "\\t";
                        case '\n' -> "\\n";
                        case '\r' -> "\\r";
                        case 0x0B -> "\\v";
                        default -> null;
                    };
                    if (escape != null) {
                        return escape;
                    }
                }
                case 5 -> {
                    // A backslash escapes ASCII punctuation.
                    if (codePoint > ' ' && codePoint < 0x7F && !Character.isLetterOrDigit(codePoint) && codePoint != '_') {
                        return "\\" + (char) codePoint;
                    }
                }
                default -> {}
            }
            if (latin1 && codePoint >= 0x80) {
                return "\\x{" + Integer.toHexString(codePoint) + "}";
            }
            if ((inClass ? CLASS_METACHARACTERS : METACHARACTERS).indexOf(codePoint) >= 0) {
                return "\\" + (char) codePoint;
            }
            return Character.toString(codePoint);
        }
    }
}
