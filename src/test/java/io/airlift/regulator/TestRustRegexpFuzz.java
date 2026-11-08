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
import io.airlift.regulator.TestingDifferentialFuzzer.CaseInput;
import io.airlift.regulator.TestingDifferentialFuzzer.Generated;
import io.airlift.regulator.TestingDifferentialFuzzer.Language;
import io.airlift.regulator.TestingDifferentialFuzzer.Oracle;
import io.airlift.regulator.TestingDifferentialFuzzer.OracleAbortException;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.airlift.regulator.TestingDifferentialFuzzer.scanPlanCoverage;
import static io.airlift.regulator.TestingOracleProcess.hexField;
import static io.airlift.regulator.TestingPatternGenerator.TEXT_CHARACTERS;
import static io.airlift.regulator.TestingPatternTree.foldsAddedAfter;
import static io.airlift.regulator.TestingPatternTree.quantifier;
import static io.airlift.regulator.TestingPatternTree.rangeHolds;
import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/// Differential fuzzing of [RustRegexp] against the pinned `regex` crate, through the
/// `rust-regex-golden` executable built from `tools/rust-regex-golden` and named by
/// `-Dregulator.fuzz.rustOracle=PATH`; see [TestingDifferentialFuzzer]. Options are the bits
/// below, one bit for each generated semantic option. The crate answers each case as
/// `TestRustRegexpGolden` reads it, over the whole input and over the region. Outside byte mode
/// the crate searches only valid UTF-8, so the variants alone compare a malformed case.
public class TestRustRegexpFuzz
{
    static final Language<RustRegexp, RustCompiled> LANGUAGE = new RustLanguage();

    static final int CASE_INSENSITIVE = 1;
    static final int MULTILINE = 1 << 1;
    static final int DOT_MATCHES_NEWLINE = 1 << 2;
    static final int CRLF = 1 << 3;
    static final int SWAP_GREED = 1 << 4;
    static final int COMMENTS = 1 << 5;
    static final int OCTAL = 1 << 6;
    static final int NO_UNICODE = 1 << 7;
    static final int BYTES = 1 << 8;

    private static final Map<Integer, String> OPTION_NAMES = Map.of(
            CASE_INSENSITIVE, "TestRustRegexpFuzz.CASE_INSENSITIVE",
            MULTILINE, "TestRustRegexpFuzz.MULTILINE",
            DOT_MATCHES_NEWLINE, "TestRustRegexpFuzz.DOT_MATCHES_NEWLINE",
            CRLF, "TestRustRegexpFuzz.CRLF",
            SWAP_GREED, "TestRustRegexpFuzz.SWAP_GREED",
            COMMENTS, "TestRustRegexpFuzz.COMMENTS",
            OCTAL, "TestRustRegexpFuzz.OCTAL",
            NO_UNICODE, "TestRustRegexpFuzz.NO_UNICODE",
            BYTES, "TestRustRegexpFuzz.BYTES");

    // For each option bit in order, one pattern in this many sets it.
    private static final int[] OPTION_ODDS = {8, 4, 6, 6, 10, 8, 10, 8, 4};

    // The golden tool's letters for the first seven options, in its canonical order: the crate's
    // inline flags, and `o`, the tool's own letter for the octal option.
    private static final String OPTION_FLAGS = "imsRUxo";

    private static final long SEED = 0x52_55_53_54_46_55_5AL;
    private static final int PATTERNS = 600;

    private static final String ORACLE_PROPERTY = "regulator.fuzz.rustOracle";

    // The crate records at most this many successive matches.
    private static final int RECORDED_MATCH_LIMIT = 64;

    // Characters whose properties and simple case folds are the same in Unicode 16.0, the data of
    // the crate's tables, and in Regulator's pinned 18.0 (RUST_REGEXP.md), so U+0295, which
    // changed from Ll to Lo, is left out.
    private static final List<String> CHARACTERS = Stream.concat(
                    TEXT_CHARACTERS.stream(),
                    Stream.of(
                            "ß",
                            "ẞ",
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
                            "\u2029",
                            "\u3000",
                            "\f",
                            "\u000B",
                            "$",
                            "^",
                            "|",
                            "#",
                            "&",
                            "~",
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
                            "<",
                            ">",
                            "="))
            .toList();

    // Names the crate resolves, with members that agree across those Unicode versions on the
    // characters above. Script_Extensions and the emoji properties, which change often, are left
    // out.
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
            "Cc",
            "Cf",
            "Letter",
            "Uppercase_Letter",
            "lowercase letter",
            "gc=Nd",
            "Any",
            "ASCII",
            "Greek",
            "Latin",
            "Cyrillic",
            "Han",
            "Hiragana",
            "Common",
            "Inherited",
            "Deseret",
            "sc=Greek",
            "sc:Latin",
            "sc!=Han",
            "Script=Cyrillic",
            "Alphabetic",
            "White_Space",
            "Uppercase",
            "Lowercase",
            "Hex_Digit",
            "Dash");

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

    private static final Vocabulary VOCABULARY = new Vocabulary(
            CHARACTERS,
            PROPERTY_NAMES,
            List.of("L", "M", "N", "P", "S", "Z", "C"),
            POSIX_CLASS_NAMES,
            "dDsSwW",
            List.of("^", "$", "\\A", "\\z", "\\b", "\\B", "\\<", "\\>", "\\b{start}", "\\b{end}", "\\b{start-half}", "\\b{end-half}"),
            "imsRUxu",
            List.of("n", "g", "name"),
            false,
            "&-~",
            8,
            5,
            (_, _, _, _) -> true);

    // The crate's case folding is Unicode 16.0 data, and characters added since then fold only in
    // Regulator, some into the orbit of an older letter, as U+1DF95 joins sharp s
    // (REGULATOR_DECISIONS.md, Pinned Unicode Case Folding). A range that ignores case leaves
    // them out.
    private static final Set<Integer> NEWER_FOLDS = foldsAddedAfter("16.0");

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

    private static RustRegexp.Options options(int options)
    {
        return RustRegexp.Options.defaults()
                .setCaseInsensitive((options & CASE_INSENSITIVE) != 0)
                .setMultiline((options & MULTILINE) != 0)
                .setDotMatchesNewline((options & DOT_MATCHES_NEWLINE) != 0)
                .setCrlf((options & CRLF) != 0)
                .setSwapGreed((options & SWAP_GREED) != 0)
                .setComments((options & COMMENTS) != 0)
                .setOctal((options & OCTAL) != 0)
                .setUnicode((options & NO_UNICODE) == 0)
                .setBytes((options & BYTES) != 0);
    }

    private static final class RustLanguage
            implements Language<RustRegexp, RustCompiled>
    {
        @Override
        public String name()
        {
            return "rust";
        }

        @Override
        public String replayName()
        {
            return "TestRustRegexpFuzz.LANGUAGE";
        }

        @Override
        public Generated generate(Random random)
        {
            int options = 0;
            for (int bit = 0; bit < OPTION_ODDS.length; bit++) {
                if (random.nextInt(OPTION_ODDS[bit]) == 0) {
                    options |= 1 << bit;
                }
            }
            if (random.nextInt(6) == 0) {
                // Random trees rarely compile to a scan plan, so one pattern in six has a plan's
                // shape and options, and the bytecode variants compile a class for it. The share
                // bounds that extra compile work.
                return new Generated(TestingPatternGenerator.scanPlanShape(random), options & ~(CASE_INSENSITIVE | SWAP_GREED));
            }
            if (random.nextInt(8) == 0) {
                // Random trees rarely reach the inner literal search's class-run proof, so one in
                // eight of the rest has its shape, with the literal exact. The bytes and Unicode
                // options decide whether a dot reads one byte, which admits longer runs.
                return new Generated(TestingPatternGenerator.innerLiteralShape(random), options & ~CASE_INSENSITIVE);
            }
            Node tree = TestingPatternGenerator.generate(random, VOCABULARY);
            if (random.nextInt(8) == 0) {
                tree = TestingPatternGenerator.withLargeCounts(random, tree);
            }
            return new Generated(tree, options);
        }

        @Override
        public String render(Node tree, int options)
        {
            return RustWriter.write(tree, options);
        }

        @Override
        public String describeOptions(int options)
        {
            return TestingDifferentialFuzzer.describeFlags(options, OPTION_NAMES);
        }

        @Override
        public String sample(Node tree, int options, String pattern, Random random)
        {
            return TestingPatternTree.sample(tree, random, (options & CASE_INSENSITIVE) != 0, CHARACTERS);
        }

        @Override
        public List<String> inputCharacters(Node tree, int options)
        {
            return CHARACTERS;
        }

        @Override
        public String filterInput(Node tree, int options, String input)
        {
            return input;
        }

        @Override
        public byte[] malformed(Random random, byte[] input)
        {
            return TestingDifferentialFuzzer.malformedSequence(random, input);
        }

        @Override
        public boolean byteWindows(int options)
        {
            return (options & BYTES) != 0;
        }

        @Override
        public boolean oracleReadsMalformedInput(int options)
        {
            return (options & BYTES) != 0;
        }

        @Override
        public RustRegexp compile(String pattern, int options, Variant variant)
        {
            RustRegexp.Options rustOptions = options(options).setGenerateBytecode(variant.generateBytecode());
            Slice slice = utf8Slice(pattern);
            return switch (variant.scanPlanMode()) {
                case DEFAULT -> RustRegexp.compile(slice, rustOptions);
                case FORCE_HANDOFF -> RustRegexp.compileForcingScanPlanHandoffForTesting(slice, rustOptions);
                case DISABLED -> RustRegexp.compileWithoutScanPlanForTesting(slice, rustOptions);
            };
        }

        @Override
        public Oracle<RustCompiled> oracle()
        {
            return CrateOracle.INSTANCE;
        }

        // The operations TestRustRegexpGolden compares, with each result as the crate records
        // it, over the whole input and over the region.
        @Override
        public void operations(RustRegexp regexp, CaseInput input, Trace trace)
        {
            Slice source = input.source();
            int regionStart = input.regionStart();
            int regionEnd = input.regionEnd();
            int groupCount = regexp.capturingGroupCount();
            trace.add("groups", () -> (groupCount + 1) + " " + groupNames(regexp));
            int[] groups = new int[2 * (groupCount + 1)];
            trace.add("find", () -> regexp.find(source));
            trace.add("lookingAt", () -> regexp.lookingAt(source));
            trace.add("matches", () -> regexp.matches(source));
            trace.add("findResult", () -> groups(regexp.findResult(source)));
            trace.add("lookingAtResult", () -> groups(regexp.lookingAtResult(source)));
            trace.add("matchesResult", () -> groups(regexp.matchesResult(source)));
            trace.add("findInto", () -> into(regexp.findInto(source, groups), groups));
            trace.add("lookingAtInto", () -> into(regexp.lookingAtInto(source, groups), groups));
            trace.add("matchesInto", () -> into(regexp.matchesInto(source, groups), groups));
            RegexpMatcher matcher = regexp.matcher(source);
            trace.add("find all", () -> findAll(matcher));
            trace.add("count", () -> regexp.count(source));
            trace.add("find range", () -> regexp.find(source, regionStart, regionEnd));
            trace.add("lookingAt range", () -> regexp.lookingAt(source, regionStart, regionEnd));
            trace.add("matches range", () -> regexp.matches(source, regionStart, regionEnd));
            trace.add("findResult range", () -> groups(regexp.findResult(source, regionStart, regionEnd)));
            trace.add("lookingAtResult range", () -> groups(regexp.lookingAtResult(source, regionStart, regionEnd)));
            trace.add("matchesResult range", () -> groups(regexp.matchesResult(source, regionStart, regionEnd)));
            trace.add("findInto range", () -> into(regexp.findInto(source, regionStart, regionEnd, groups), groups));
            trace.add("lookingAtInto range", () -> into(regexp.lookingAtInto(source, regionStart, regionEnd, groups), groups));
            trace.add("matchesInto range", () -> into(regexp.matchesInto(source, regionStart, regionEnd, groups), groups));
            trace.add("region find all", () -> findAll(matcher.reset(source, regionStart, regionEnd)));
            trace.addVariantOnly("find from", () -> {
                if (!matcher.reset(source, regionStart, regionEnd).find(input.searchStart())) {
                    return "null";
                }
                return groups(matcher.toMatchResult());
            });
            trace.addVariantOnly("group zero find all", () -> findAll(regexp.matcher(source, 0)));
        }

        // Rust plans agree with the ordinary engine on malformed input (REGULATOR_API.md), though
        // outside byte mode the crate assigns it no meaning.
        @Override
        public boolean variantsAgreeOnMalformedInput()
        {
            return true;
        }

        @Override
        public Set<String> coverage(RustRegexp compiled)
        {
            return scanPlanCoverage(compiled);
        }
    }

    // Names in group order, with - for an unnamed group, as the crate records them.
    private static String groupNames(RustRegexp regexp)
    {
        String[] names = new String[regexp.capturingGroupCount() + 1];
        Arrays.fill(names, "-");
        for (Map.Entry<String, Integer> name : regexp.namedCapturingGroups().entrySet()) {
            names[name.getValue()] = name.getKey();
        }
        return String.join(",", names);
    }

    // The first matches the crate records and how many there are.
    private static String findAll(RegexpMatcher matcher)
    {
        List<String> matches = new ArrayList<>();
        long count = 0;
        while (matcher.find()) {
            count++;
            if (matches.size() < RECORDED_MATCH_LIMIT) {
                matches.add(groups(matcher.toMatchResult()));
            }
        }
        return String.join(";", matches) + " count " + count;
    }

    // The crate's format, which TestRustRegexpGolden reads, rather than the harness's spans.
    private static String groups(MatchResult result)
    {
        if (result == null) {
            return "null";
        }
        List<String> groups = new ArrayList<>();
        for (int group = 0; group <= result.groupCount(); group++) {
            groups.add(result.matched(group) ? result.start(group) + ":" + result.end(group) : "-1:-1");
        }
        return String.join(",", groups);
    }

    private static String into(boolean matched, int[] groups)
    {
        if (!matched) {
            return "null";
        }
        List<String> spans = new ArrayList<>();
        for (int group = 0; 2 * group + 1 < groups.length; group++) {
            spans.add(groups[2 * group] + ":" + groups[2 * group + 1]);
        }
        return String.join(",", spans);
    }

    /// A pattern the crate compiled: its row fields and capture metadata.
    record RustCompiled(String patternHex, String options, String groupCount, String groupNames) {}

    // The crate's HIR, its high-level intermediate representation of a parsed pattern, as the
    // crate's HIR constructors build it, modelled as far as they decide which alternation
    // prefixes the crate lifts. They flatten concatenations and nested alternations, join
    // adjacent literals, and lift a prefix that every alternative shares.
    private static final class CrateHir
    {
        // A count of characters with no bound
        private static final long UNBOUNDED = Long.MAX_VALUE;

        private boolean liftsVariablePrefix;

        private CrateHir() {}

        /// Whether the crate's HIR constructors lift a prefix that can match more than one
        /// length out of an alternation, from the tree RustRegexpParser translates before
        /// Regulator's simplification reshapes it.
        static boolean liftsVariablePrefix(Regexp translated)
        {
            CrateHir hir = new CrateHir();
            hir.build(translated);
            return hir.liftsVariablePrefix;
        }

        // The translation already drops groups that do not capture and applies the crate's
        // repetition rules, so a node maps to the HIR node of the same kind.
        private Hir build(Regexp regexp)
        {
            return switch (regexp.op()) {
                case EMPTY_MATCH -> new HirEmpty();
                case LITERAL -> literal(regexp, new int[] {regexp.rune()});
                case LITERAL_STRING -> literal(regexp, regexp.runes());
                case CHAR_CLASS -> hirClass(regexp.charClass().rangeView(), (regexp.parseFlags() & Regexp.LATIN1) != 0);
                case ANY_CHAR -> hirClass(List.of(new RuneRange(0, CharClass.RUNEMAX)), false);
                case ANY_BYTE -> hirClass(List.of(new RuneRange(0, 0xFF)), true);
                case STAR -> repetition(regexp, 0, -1);
                case PLUS -> repetition(regexp, 1, -1);
                case QUEST -> repetition(regexp, 0, 1);
                case REPEAT -> repetition(regexp, regexp.min(), regexp.max());
                case CAPTURE -> new HirCapture(regexp.captureIndex(), build(regexp.child(0)));
                case CONCAT -> concat(regexp.children().stream().map(this::build).toList());
                case ALTERNATE -> alternation(regexp.children().stream().map(this::build).toList());
                case BEGIN_LINE, END_LINE, BEGIN_TEXT, END_TEXT, WORD_BOUNDARY, NO_WORD_BOUNDARY -> new HirLook(regexp);
                // The crate's expression that never matches is an empty class.
                case NO_MATCH -> hirClass(List.of(), false);
                case HAVE_MATCH -> throw new IllegalArgumentException("a translated pattern has no match instruction");
            };
        }

        private Hir repetition(Regexp regexp, int min, int max)
        {
            return new HirRepetition(min, max, (regexp.parseFlags() & Regexp.NON_GREEDY) == 0, build(regexp.child(0)));
        }

        // The crate's literals are bytes: a byte string's own, or else the UTF-8 of its characters.
        private static Hir literal(Regexp regexp, int[] runes)
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int rune : runes) {
                if ((regexp.parseFlags() & Regexp.LATIN1) != 0) {
                    bytes.write(rune);
                }
                else {
                    bytes.writeBytes(Character.toString(rune).getBytes(UTF_8));
                }
            }
            return new HirLiteral(byteString(bytes.toByteArray()));
        }

        // A class of one member is that literal.
        private static Hir hirClass(List<RuneRange> ranges, boolean bytes)
        {
            CharClassBuilder builder = new CharClassBuilder();
            for (RuneRange range : ranges) {
                builder.addRange(range.low(), range.high());
            }
            List<RuneRange> normalized = builder.toCharClass().rangeView();
            if (normalized.size() == 1 && normalized.getFirst().low() == normalized.getFirst().high()) {
                int member = normalized.getFirst().low();
                return new HirLiteral(bytes ? Character.toString(member) : byteString(Character.toString(member).getBytes(UTF_8)));
            }
            return new HirClass(List.copyOf(normalized), bytes);
        }

        // Flattens a nested concatenation, drops empty items, and joins adjacent literals.
        private static Hir concat(List<Hir> subs)
        {
            List<Hir> items = new ArrayList<>();
            for (Hir sub : subs) {
                for (Hir item : sub instanceof HirConcat concat ? concat.items() : List.of(sub)) {
                    if (item instanceof HirEmpty) {
                        continue;
                    }
                    if (item instanceof HirLiteral literal && !items.isEmpty() && items.getLast() instanceof HirLiteral prior) {
                        items.set(items.size() - 1, new HirLiteral(prior.bytes() + literal.bytes()));
                        continue;
                    }
                    items.add(item);
                }
            }
            return switch (items.size()) {
                case 0 -> new HirEmpty();
                case 1 -> items.getFirst();
                default -> new HirConcat(List.copyOf(items));
            };
        }

        // Flattens nested alternations, joins alternatives that are all single characters, all
        // single bytes, or all classes into one class, and otherwise lifts the prefix that every
        // alternative shares, when each is a concatenation.
        private Hir alternation(List<Hir> subs)
        {
            List<Hir> alternatives = new ArrayList<>();
            for (Hir sub : subs) {
                alternatives.addAll(sub instanceof HirAlternation alternation ? alternation.alternatives() : List.of(sub));
            }
            if (alternatives.size() == 1) {
                return alternatives.getFirst();
            }
            Hir joined = joinedClass(alternatives);
            if (joined != null) {
                return joined;
            }
            if (alternatives.stream().allMatch(HirConcat.class::isInstance)) {
                List<List<Hir>> concats = alternatives.stream().map(alternative -> ((HirConcat) alternative).items()).toList();
                List<Hir> first = concats.getFirst();
                int common = first.size();
                for (List<Hir> concat : concats) {
                    int shared = 0;
                    while (shared < Math.min(common, concat.size()) && first.get(shared).equals(concat.get(shared))) {
                        shared++;
                    }
                    common = shared;
                }
                if (common > 0) {
                    List<Hir> prefix = first.subList(0, common);
                    if (!prefix.stream().allMatch(CrateHir::fixedLength)) {
                        liftsVariablePrefix = true;
                    }
                    int length = common;
                    List<Hir> items = new ArrayList<>(prefix);
                    items.add(alternation(concats.stream().map(concat -> concat(concat.subList(length, concat.size()))).toList()));
                    return concat(items);
                }
            }
            return new HirAlternation(List.copyOf(alternatives));
        }

        private static Hir joinedClass(List<Hir> alternatives)
        {
            if (alternatives.stream().allMatch(alternative -> alternative instanceof HirLiteral literal && characterCount(literal) == 1)) {
                return hirClass(alternatives.stream().map(alternative -> character((HirLiteral) alternative)).toList(), false);
            }
            if (alternatives.stream().allMatch(alternative -> alternative instanceof HirLiteral literal && literal.bytes().length() == 1)) {
                return hirClass(alternatives.stream().map(alternative -> singleton(((HirLiteral) alternative).bytes().charAt(0))).toList(), true);
            }
            if (!alternatives.stream().allMatch(HirClass.class::isInstance)) {
                return null;
            }
            List<HirClass> classes = alternatives.stream().map(HirClass.class::cast).toList();
            List<RuneRange> ranges = classes.stream().flatMap(hirClass -> hirClass.ranges().stream()).toList();
            // A union holds characters, or bytes when a byte class in it is not ASCII; it cannot
            // hold both a byte and a character outside ASCII.
            boolean bytes = classes.stream().anyMatch(hirClass -> hirClass.bytes() && !hirClass.ascii());
            if (bytes && classes.stream().anyMatch(hirClass -> !hirClass.bytes() && !hirClass.ascii())) {
                return null;
            }
            return hirClass(ranges, bytes);
        }

        private static RuneRange character(HirLiteral literal)
        {
            return singleton(new String(byteArray(literal.bytes()), UTF_8).codePointAt(0));
        }

        private static RuneRange singleton(int codePoint)
        {
            return new RuneRange(codePoint, codePoint);
        }

        // A literal or a class matches a fixed number of bytes; anything else does when it
        // matches a fixed number of characters.
        private static boolean fixedLength(Hir hir)
        {
            if (hir instanceof HirLiteral || hir instanceof HirClass) {
                return true;
            }
            Length length = length(hir);
            return length != null && length.min() == length.max();
        }

        // The fewest and most characters `hir` matches, or null when a byte that is not a
        // whole character leaves the count of characters unknown.
        private static Length length(Hir hir)
        {
            return switch (hir) {
                case HirEmpty _, HirLook _ -> new Length(0, 0);
                case HirLiteral literal -> {
                    int count = characterCount(literal);
                    yield count < 0 ? null : new Length(count, count);
                }
                case HirClass hirClass -> hirClass.bytes() && !hirClass.ascii() ? null : new Length(1, 1);
                case HirCapture capture -> length(capture.sub());
                case HirRepetition repetition -> {
                    Length sub = length(repetition.sub());
                    yield sub == null ? null : new Length(times(repetition.min(), sub.min()), times(repetition.max() < 0 ? UNBOUNDED : repetition.max(), sub.max()));
                }
                case HirConcat concat -> {
                    long min = 0;
                    long max = 0;
                    for (Hir item : concat.items()) {
                        Length itemLength = length(item);
                        if (itemLength == null) {
                            yield null;
                        }
                        min = plus(min, itemLength.min());
                        max = plus(max, itemLength.max());
                    }
                    yield new Length(min, max);
                }
                case HirAlternation alternation -> {
                    long min = UNBOUNDED;
                    long max = 0;
                    for (Hir alternative : alternation.alternatives()) {
                        Length alternativeLength = length(alternative);
                        if (alternativeLength == null) {
                            yield null;
                        }
                        min = Math.min(min, alternativeLength.min());
                        max = Math.max(max, alternativeLength.max());
                    }
                    yield new Length(min, max);
                }
            };
        }

        // The characters of a literal, or -1 when its bytes are not UTF-8.
        private static int characterCount(HirLiteral literal)
        {
            byte[] bytes = byteArray(literal.bytes());
            String text = new String(bytes, UTF_8);
            return Arrays.equals(text.getBytes(UTF_8), bytes) ? text.codePointCount(0, text.length()) : -1;
        }

        // A literal's bytes are held as a string of one Latin-1 character per byte.
        private static String byteString(byte[] bytes)
        {
            return new String(bytes, ISO_8859_1);
        }

        private static byte[] byteArray(String byteString)
        {
            return byteString.getBytes(ISO_8859_1);
        }

        private static long times(long count, long length)
        {
            if (count == 0 || length == 0) {
                return 0;
            }
            return count == UNBOUNDED || length == UNBOUNDED ? UNBOUNDED : count * length;
        }

        private static long plus(long left, long right)
        {
            return left == UNBOUNDED || right == UNBOUNDED ? UNBOUNDED : left + right;
        }

        private sealed interface Hir
                permits HirAlternation,
                        HirCapture,
                        HirClass,
                        HirConcat,
                        HirEmpty,
                        HirLiteral,
                        HirLook,
                        HirRepetition {}

        private record HirEmpty()
                implements Hir {}

        // The literal's bytes, one Latin-1 character per byte
        private record HirLiteral(String bytes)
                implements Hir {}

        private record HirClass(List<RuneRange> ranges, boolean bytes)
                implements Hir
        {
            boolean ascii()
            {
                return ranges.isEmpty() || ranges.getLast().high() < 0x80;
            }
        }

        // An assertion, compared as the translated node
        private record HirLook(Regexp look)
                implements Hir {}

        // A repetition, with -1 for no maximum
        private record HirRepetition(int min, int max, boolean greedy, Hir sub)
                implements Hir {}

        private record HirCapture(int index, Hir sub)
                implements Hir {}

        private record HirConcat(List<Hir> items)
                implements Hir {}

        private record HirAlternation(List<Hir> alternatives)
                implements Hir {}

        // The fewest and most characters a node matches, UNBOUNDED for no most
        private record Length(long min, long max) {}
    }

    /// The pinned crate as the oracle, through `rust-regex-golden --mode=match`. A pattern
    /// compiles as a search of the empty text; each case asks for the whole input and for the
    /// region.
    private static final class CrateOracle
            implements Oracle<RustCompiled>
    {
        private static final CrateOracle INSTANCE = new CrateOracle(TestingOracleProcess.binary(ORACLE_PROPERTY));

        // The tool's failure to build the full-match regex it derives from an accepted pattern
        private static final Pattern FULL_MATCH_BUILD_FAILURE = Pattern.compile(": full-match regex for .* failed to build: error building NFA\\) on row: ");

        private final TestingOracleProcess process;

        private CrateOracle(Path binary)
        {
            this.process = binary == null ? null : new TestingOracleProcess(binary, List.of("--mode=match"));
        }

        @Override
        public boolean available()
        {
            return process != null;
        }

        @Override
        public void prepare(List<PreparedPattern> patterns)
        {
            process.prepare(patterns.stream().map(pattern -> row(hexField(pattern.pattern().getBytes(UTF_8)), optionsField(pattern.options()), new byte[0], "-", "-")).toList());
            List<String> rows = new ArrayList<>();
            for (PreparedPattern pattern : patterns) {
                RustCompiled compiled;
                try {
                    compiled = compile(pattern.pattern(), pattern.options());
                }
                catch (RuntimeException _) {
                    // A pattern the crate rejects or the oracle skips has no case rows; the
                    // compile comparison reports or skips it.
                    continue;
                }
                for (CaseInput input : pattern.inputs()) {
                    if (input.malformed() && !LANGUAGE.oracleReadsMalformedInput(pattern.options())) {
                        continue;
                    }
                    rows.add(row(compiled.patternHex(), compiled.options(), input.bytes(), "-", "-"));
                    rows.add(row(compiled.patternHex(), compiled.options(), input.bytes(), Integer.toString(input.regionStart()), Integer.toString(input.regionEnd())));
                }
            }
            process.prepare(rows);
        }

        @Override
        public RustCompiled compile(String pattern, int options)
        {
            String patternHex = hexField(pattern.getBytes(UTF_8));
            String optionsField = optionsField(options);
            JsonObject json;
            try {
                json = process.get(row(patternHex, optionsField, new byte[0], "-", "-"));
            }
            catch (OracleAbortException e) {
                // The tool's full-match regex is the pattern followed by \z under the same
                // compiled-size limit, which it can exceed where the pattern alone fits; the
                // limit is not reproduced (REGULATOR_DECISIONS.md, Rust Frontend).
                if (FULL_MATCH_BUILD_FAILURE.matcher(e.getMessage()).find()) {
                    throw new OracleSkipException("oracle size limit");
                }
                throw e;
            }
            if (!json.getBoolean("ok")) {
                // The crate's size_limit estimate has no counterpart in Regulator (RUST_REGEXP.md,
                // Compilation options).
                if (json.getString("error").equals("size_limit")) {
                    throw new OracleSkipException("oracle size limit");
                }
                throw new IllegalArgumentException(json.getString("error") + " " + json.getString("errorKind"));
            }
            // The crate's HIR constructors lift a prefix that every alternative shares out of the
            // alternation, so the crate prefers a later alternative where the prefix can match
            // more than one length, as in a*a|a*b; Regulator keeps leftmost-first
            // (REGULATOR_DECISIONS.md, Rust Frontend).
            if (liftsVariablePrefix(pattern, options)) {
                throw new OracleSkipException("crate lifts an alternation prefix");
            }
            return new RustCompiled(patternHex, optionsField, json.getString("groupCount"), json.getString("groupNames"));
        }

        // Whether the crate lifts a prefix that can match more than one length out of an
        // alternation; see CrateHir.
        private static boolean liftsVariablePrefix(String pattern, int options)
        {
            RustRegexp.Options rustOptions = options(options);
            Regexp translated;
            try {
                translated = RustRegexpParser.translateForTesting(utf8Slice(pattern), rustOptions.syntaxFlags(), rustOptions.nestLimit());
            }
            catch (RegexpParseException _) {
                // The compile comparison reports a pattern only the crate accepts.
                return false;
            }
            return CrateHir.liftsVariablePrefix(translated);
        }

        @Override
        public void operations(RustCompiled compiled, CaseInput input, Trace trace, long deadlineNanos)
        {
            JsonObject whole = process.get(row(compiled.patternHex(), compiled.options(), input.bytes(), "-", "-"));
            JsonObject region = process.get(row(compiled.patternHex(), compiled.options(), input.bytes(), Integer.toString(input.regionStart()), Integer.toString(input.regionEnd())));
            trace.add("groups", () -> compiled.groupCount() + " " + compiled.groupNames());
            record(whole, "", 0, input.bytes().length, trace);
            trace.add("count", () -> Long.parseLong(whole.getString("matchCount")));
            record(region, " range", input.regionStart(), input.regionEnd(), trace);
        }

        // The boolean operations come from the same searches as the spans.
        private static void record(JsonObject json, String suffix, int start, int end, Trace trace)
        {
            String captures = String.valueOf(json.getString("captures"));
            String anchored = String.valueOf(json.getString("anchoredCaptures"));
            add(trace, "find" + suffix, captures, start, end, () -> json.getBoolean("isMatch"));
            add(trace, "findResult" + suffix, captures, start, end, () -> captures);
            add(trace, "findInto" + suffix, captures, start, end, () -> captures);
            add(trace, "lookingAt" + suffix, anchored, start, end, () -> !anchored.equals("null"));
            add(trace, "lookingAtResult" + suffix, anchored, start, end, () -> anchored);
            add(trace, "lookingAtInto" + suffix, anchored, start, end, () -> anchored);
            // A window that stops short of the text can leave the full match undefined.
            if (suffix.isEmpty() || json.getBoolean("fullDefined")) {
                String full = String.valueOf(json.getString("fullCaptures"));
                add(trace, "matches" + suffix, full, start, end, () -> !full.equals("null"));
                add(trace, "matchesResult" + suffix, full, start, end, () -> full);
                add(trace, "matchesInto" + suffix, full, start, end, () -> full);
            }
            String matches = json.getString("matches");
            add(trace, suffix.isEmpty() ? "find all" : "region find all", matches, start, end, () -> matches + " count " + json.getString("matchCount"));
        }

        // regex-automata's single-byte prefilter checks only the start of the searched span, so
        // an anchored search of a window can report a match past its end: [&&]?b on "b" with the
        // window [0, 0) matches 0:1. A crate span outside the window judges nothing.
        private static void add(Trace trace, String operation, String spans, int start, int end, Supplier<?> result)
        {
            if (outside(spans, start, end)) {
                trace.skip(operation, "crate span outside the window");
            }
            else {
                trace.add(operation, result);
            }
        }

        // Whether a participating capture of the matches, written start:end with commas between
        // groups and semicolons between matches, lies outside [start, end].
        private static boolean outside(String spans, int start, int end)
        {
            if (spans.equals("null") || spans.isEmpty()) {
                return false;
            }
            for (String match : spans.split(";")) {
                for (String group : match.split(",")) {
                    String[] offsets = group.split(":");
                    int groupStart = Integer.parseInt(offsets[0]);
                    if (groupStart >= 0 && (groupStart < start || Integer.parseInt(offsets[1]) > end)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static String row(String patternHex, String options, byte[] text, String start, String end)
        {
            return String.join("\t", patternHex, options, hexField(text), start, end);
        }

        // The crate tool's canonical options: enabled flags, then -u, then bytes, or - alone.
        private static String optionsField(int options)
        {
            StringBuilder flags = new StringBuilder();
            for (int bit = 0; bit < OPTION_FLAGS.length(); bit++) {
                if ((options & (1 << bit)) != 0) {
                    flags.append(OPTION_FLAGS.charAt(bit));
                }
            }
            List<String> groups = new ArrayList<>();
            if (!flags.isEmpty()) {
                groups.add(flags.toString());
            }
            if ((options & NO_UNICODE) != 0) {
                groups.add("-u");
            }
            if ((options & BYTES) != 0) {
                groups.add("bytes");
            }
            return groups.isEmpty() ? "-" : String.join(",", groups);
        }
    }

    /// Writes a tree in the crate's syntax, or returns `null` when the tree is outside the
    /// dialect.
    private static final class RustWriter
    {
        private static final String METACHARACTERS = "\\.+*?()|[]{}^$";
        private static final String CLASS_METACHARACTERS = "\\^-&~[]";

        private final StringBuilder out = new StringBuilder();
        private final boolean octal;
        private final boolean caseInsensitive;

        private RustWriter(boolean octal, boolean caseInsensitive)
        {
            this.octal = octal;
            this.caseInsensitive = caseInsensitive;
        }

        static String write(Node tree, int options)
        {
            boolean caseInsensitive = (options & CASE_INSENSITIVE) != 0 || TestingPatternTree.contains(tree, element -> element instanceof FlagGroup group && group.enabled().indexOf('i') >= 0);
            RustWriter writer = new RustWriter((options & OCTAL) != 0, caseInsensitive);
            try {
                writer.node(tree, (options & COMMENTS) != 0, true);
            }
            catch (OutsideDialect _) {
                return null;
            }
            return writer.out.toString();
        }

        private void node(Node node, boolean extended, boolean endsGroup)
        {
            switch (node) {
                case Literal literal -> out.append(literal(literal.codePoint(), literal.style(), extended, false));
                case Quote _ -> throw new OutsideDialect();
                case AnyCharacter _ -> out.append('.');
                case PerlClass perlClass -> out.append('\\').append(perlClass.letter());
                case Property property -> out.append(property(property));
                case CharacterClass characterClass -> characterClass(characterClass, extended);
                case Anchor anchor -> out.append(anchor.text());
                case Comment comment -> {
                    // Verbose mode skips whitespace and comments to the end of the line.
                    if (extended) {
                        out.append(switch (comment.style()) {
                            case 0 -> " ";
                            case 1 -> "\t";
                            case 2 -> "\n";
                            case 3 -> " # a comment (with | metacharacters\n";
                            default -> "  ";
                        });
                    }
                }
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
                    node(group.body(), extended, true);
                    out.append(')');
                }
                case FlagGroup group -> {
                    String flags = group.disabled().isEmpty() ? group.enabled() : group.enabled() + "-" + group.disabled();
                    boolean innerExtended = group.enabled().indexOf('x') >= 0 || (extended && group.disabled().indexOf('x') < 0);
                    // Isolated flags apply to the rest of the enclosing group, later alternatives
                    // included, so they are written isolated only where no alternative follows.
                    if (group.isolated() && endsGroup && !(group.body() instanceof Alternation)) {
                        out.append("(?").append(flags).append(')');
                        node(group.body(), innerExtended, true);
                    }
                    else {
                        out.append("(?").append(flags).append(':');
                        node(group.body(), innerExtended, true);
                        out.append(')');
                    }
                }
                case Concat concat -> {
                    List<Node> items = concat.items();
                    for (int index = 0; index < items.size(); index++) {
                        Node item = items.get(index);
                        if (item instanceof Alternation && items.size() > 1) {
                            out.append("(?:");
                            node(item, extended, true);
                            out.append(')');
                        }
                        else {
                            node(item, extended, endsGroup && index == items.size() - 1);
                        }
                    }
                }
                case Alternation alternation -> {
                    List<Node> alternatives = alternation.alternatives();
                    for (int index = 0; index < alternatives.size(); index++) {
                        if (index > 0) {
                            out.append('|');
                        }
                        node(alternatives.get(index), extended, endsGroup && index == alternatives.size() - 1);
                    }
                }
                case Repeat repeat -> {
                    // A repeated repetition is grouped, which keeps the tree's repetition
                    // structure.
                    Node body = repeat.body();
                    boolean atom = body instanceof Literal || body instanceof AnyCharacter || body instanceof PerlClass ||
                            body instanceof Property || body instanceof CharacterClass || body instanceof Group || body instanceof FlagGroup;
                    if (atom) {
                        node(body, extended, false);
                    }
                    else {
                        out.append("(?:");
                        node(body, extended, true);
                        out.append(')');
                    }
                    if ((repeat.style() & 4) != 0 && extended) {
                        out.append(' ');
                    }
                    out.append(quantifier(repeat));
                }
            }
        }

        // Operands are combined from left to right with equal precedence (regex-syntax 0.8.11,
        // parse.rs), as the tree combines them.
        private void characterClass(CharacterClass characterClass, boolean extended)
        {
            out.append('[');
            if (characterClass.negated()) {
                out.append('^');
            }
            List<List<ClassItem>> operands = characterClass.operands();
            for (int operandIndex = 0; operandIndex < operands.size(); operandIndex++) {
                if (operandIndex > 0) {
                    char operator = characterClass.operators().charAt(operandIndex - 1);
                    out.append(operator).append(operator);
                }
                List<ClassItem> operand = operands.get(operandIndex);
                for (int itemIndex = 0; itemIndex < operand.size(); itemIndex++) {
                    ClassItem item = operand.get(itemIndex);
                    if (operandIndex == 0 && itemIndex == 0 && item instanceof ClassLiteral(int codePoint, int style) && codePoint == '-' && style == 0) {
                        // A dash that starts a class is a literal.
                        out.append('-');
                        continue;
                    }
                    switch (item) {
                        case ClassLiteral literal -> out.append(literal(literal.codePoint(), literal.style(), extended, true));
                        case ClassRange range -> {
                            if (caseInsensitive && rangeHolds(range, NEWER_FOLDS)) {
                                throw new OutsideDialect();
                            }
                            out.append(literal(range.low(), range.lowStyle(), extended, true)).append('-').append(literal(range.high(), range.highStyle(), extended, true));
                        }
                        case PerlClass perlClass -> out.append('\\').append(perlClass.letter());
                        case Property property -> out.append(property(property));
                        case PosixClass posixClass -> out.append("[:").append(posixClass.negated() ? "^" : "").append(posixClass.name()).append(":]");
                        case CharacterClass nested -> characterClass(nested, extended);
                    }
                }
            }
            out.append(']');
        }

        private static String property(Property property)
        {
            String sign = property.negated() ? "P" : "p";
            if (property.braceless() && property.name().length() == 1) {
                return "\\" + sign + property.name();
            }
            return "\\" + sign + "{" + property.name() + "}";
        }

        /// Writes a character in the escape `style` names where that escape can spell it, and
        /// otherwise plainly, with a backslash where the crate would read it as syntax.
        private String literal(int codePoint, int style, boolean extended, boolean inClass)
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
                    return "\\u{" + Integer.toHexString(codePoint) + "}";
                }
                case 4 -> {
                    return codePoint <= 0xFFFF ? "\\u" + HexFormat.of().toHexDigits((short) codePoint) : "\\U" + HexFormat.of().toHexDigits(codePoint);
                }
                case 5 -> {
                    // Octal escapes need the octal option. Three digits, so a digit after the
                    // escape stays a literal.
                    if (octal && codePoint <= 0777) {
                        return String.format(Locale.ROOT, "\\%03o", codePoint);
                    }
                }
                case 6 -> {
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
                case 7 -> {
                    // A backslash escapes ASCII punctuation other than < and >, which it makes
                    // word boundaries.
                    if (codePoint > ' ' && codePoint < 0x7F && !Character.isLetterOrDigit(codePoint) && codePoint != '<' && codePoint != '>') {
                        return "\\" + (char) codePoint;
                    }
                }
                default -> {}
            }
            if (extended && (codePoint == '#' || isWhitespace(codePoint))) {
                // Verbose mode skips Unicode whitespace and comments, inside classes too.
                return codePoint == '#' ? "\\#" : "\\x{" + Integer.toHexString(codePoint) + "}";
            }
            if ((inClass ? CLASS_METACHARACTERS : METACHARACTERS).indexOf(codePoint) >= 0) {
                return "\\" + (char) codePoint;
            }
            return Character.toString(codePoint);
        }

        // Rust's char::is_whitespace, the White_Space property.
        private static boolean isWhitespace(int codePoint)
        {
            return (codePoint >= '\t' && codePoint <= '\r') || codePoint == ' ' || codePoint == 0x85 || codePoint == 0xA0 || codePoint == 0x1680 ||
                    (codePoint >= 0x2000 && codePoint <= 0x200A) || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F || codePoint == 0x205F || codePoint == 0x3000;
        }
    }
}
