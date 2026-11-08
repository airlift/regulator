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
import io.airlift.regulator.TestingPatternTree.PerlClass;
import io.airlift.regulator.TestingPatternTree.PosixClass;
import io.airlift.regulator.TestingPatternTree.Property;
import io.airlift.regulator.TestingPatternTree.Quote;
import io.airlift.regulator.TestingPatternTree.Repeat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static java.util.Objects.requireNonNull;

/// Generates random [TestingPatternTree] patterns from a dialect's [Vocabulary]. Each pattern
/// draws its literals from a small palette of the vocabulary's characters, as
/// TestTrinoRegexpDifferential's generator does, so inputs built from the same characters often
/// match. The generator applies
/// the dialect's repetition rule as it chooses quantifiers; the dialect's writer still decides
/// whether the whole tree is in its language.
final class TestingPatternGenerator
{
    // Characters whose properties are unchanged since Unicode 6.1, the version of pinned Joni's
    // property tables, and whose simple case folds are unchanged since Unicode 4.1, the version of
    // its fold tables, so every language's fuzzer and TestTrinoRegexpDifferential can draw on them;
    // each fuzzer adds the characters its own Unicode versions agree on. Capital sharp s, U+1DF95,
    // and the glottal stops, whose folds changed, are left out: REGULATOR_DECISIONS.md, Pinned
    // Unicode Properties, Pinned Unicode Case Folding, and Trino Text-Dependent Assertions And
    // Full Case Folding. Arabic-Indic digits, which pinned Joni's tables put in the Common script,
    // are left out for the same reason; Devanagari three stands for non-ASCII digits.
    static final List<String> TEXT_CHARACTERS = List.of(
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

    private static final int MAX_DEPTH = 3;

    // Counts around the limits of dialects that cap counted repetition at 1,000, alone or nested.
    private static final int[] LARGE_COUNTS = {16, 100, 255, 256, 500, 999, 1_000, 1_001};

    // The letters and delimiters of scan-plan shapes.
    private static final String PLAN_LETTERS = "abc";
    private static final String PLAN_DELIMITERS = "/=&:@,;-.";
    private static final List<ClassItem> PLAN_RUNS = List.of(
            new ClassRange('a', 'z', 0, 0),
            new ClassRange('0', '9', 0, 0),
            new ClassRange('a', 'c', 0, 0));

    // The literals and literal sets of inner literal shapes, whose letters every shape's class
    // holds, and the runs' counts, as minimum and maximum, where -1 is unbounded.
    private static final List<String> INNER_LITERALS = List.of("nn", "ab", "kzk", "Tom", "Finn");
    private static final List<List<String>> INNER_LITERAL_SETS = List.of(List.of("Tom", "Sawyer", "Finn"), List.of("abc", "ckz"), List.of("sax", "Saxx", "xas"));
    private static final int[][] INNER_RUN_COUNTS = {{1, -1}, {0, -1}, {0, 2}, {1, 3}, {2, 4}, {3, -1}};

    private TestingPatternGenerator() {}

    /// Decides whether a dialect may repeat `body` from `min` to `max` times, where a negative
    /// `max` is unbounded.
    interface RepeatRule
    {
        boolean allows(Node body, int min, int max, boolean greedy);
    }

    /// What a dialect can write.
    ///
    /// @param characters the characters literals, ranges, and the palette draw from
    /// @param propertyNames names for braced properties, inside and outside classes
    /// @param bracelessPropertyNames names a property may also spell as `\pL`
    /// @param posixClassNames names for bracket POSIX classes such as `[:alpha:]`, or none
    /// @param perlClassLetters the letters of class escapes such as `\d`
    /// @param anchors the anchors and boundaries, by their text
    /// @param flagLetters the inline flags
    /// @param groupNamePrefixes prefixes of capture names, followed by a number
    /// @param quotes whether the dialect has `\Q...\E`
    /// @param classOperators the operators that combine class operands, as [CharacterClass]
    ///         spells them, or empty when classes have one operand
    /// @param literalStyles the number of literal spellings the dialect writes
    /// @param commentStyles the number of [Comment] styles the dialect writes
    /// @param repeatRule the repetitions the dialect allows
    record Vocabulary(
            List<String> characters,
            List<String> propertyNames,
            List<String> bracelessPropertyNames,
            List<String> posixClassNames,
            String perlClassLetters,
            List<String> anchors,
            String flagLetters,
            List<String> groupNamePrefixes,
            boolean quotes,
            String classOperators,
            int literalStyles,
            int commentStyles,
            RepeatRule repeatRule)
    {
        Vocabulary
        {
            characters = List.copyOf(characters);
            propertyNames = List.copyOf(propertyNames);
            bracelessPropertyNames = List.copyOf(bracelessPropertyNames);
            posixClassNames = List.copyOf(posixClassNames);
            requireNonNull(perlClassLetters, "perlClassLetters is null");
            anchors = List.copyOf(anchors);
            requireNonNull(flagLetters, "flagLetters is null");
            groupNamePrefixes = List.copyOf(groupNamePrefixes);
            requireNonNull(classOperators, "classOperators is null");
            requireNonNull(repeatRule, "repeatRule is null");
        }
    }

    static Node generate(Random random, Vocabulary vocabulary)
    {
        return new Generator(random, vocabulary).expression(0);
    }

    /// Returns `tree` with about half its repetitions given large counts, up to just past 1,000,
    /// for dialects that cap counted repetition there.
    static Node withLargeCounts(Random random, Node tree)
    {
        return TestingPatternTree.transform(tree, node -> {
            if (!(node instanceof Repeat repeat) || random.nextBoolean()) {
                return node;
            }
            int count = LARGE_COUNTS[random.nextInt(LARGE_COUNTS.length)];
            return switch (random.nextInt(3)) {
                case 0 -> new Repeat(repeat.body(), count, count, repeat.greedy(), repeat.style());
                case 1 -> new Repeat(repeat.body(), count, -1, repeat.greedy(), repeat.style());
                default -> new Repeat(repeat.body(), random.nextInt(3), count, repeat.greedy(), repeat.style());
            };
        });
    }

    /// Returns a pattern of a scan-plan shape (SCAN_PLAN.md) for the RE2 and Rust fuzzers, a
    /// shape random trees rarely reach: literals and captured greedy runs of ASCII sets, each run
    /// stopped by the delimiter after it, after a start anchor, a literal, or an exact one-byte
    /// set, sometimes ending in `$` or `.*`.
    static Node scanPlanShape(Random random)
    {
        List<Node> items = new ArrayList<>();
        switch (random.nextInt(3)) {
            case 0 -> {
                items.add(new Anchor("^"));
                items.addAll(planLiteral(random));
                if (random.nextBoolean()) {
                    items.add(new Repeat(planLetter(random), 0, 1, true, 0));
                }
            }
            case 1 -> items.addAll(planLiteral(random));
            default -> items.add(new CharacterClass(false, List.of(List.of(planDelimiterItem(random), planDelimiterItem(random)))));
        }
        for (int segments = 1 + random.nextInt(3); segments > 0; segments--) {
            int delimiter = PLAN_DELIMITERS.charAt(random.nextInt(PLAN_DELIMITERS.length()));
            Node run = random.nextBoolean()
                    ? new CharacterClass(true, List.of(List.of(new ClassLiteral(delimiter, 0))))
                    : new CharacterClass(false, List.of(List.of(PLAN_RUNS.get(random.nextInt(PLAN_RUNS.size())))));
            Node repeat = switch (random.nextInt(4)) {
                case 0 -> new Repeat(run, 1, -1, true, 0);
                case 1 -> new Repeat(run, 0, -1, true, 0);
                case 2 -> new Repeat(run, 1, 3, true, 0);
                default -> new Repeat(run, 2, 2, true, 0);
            };
            items.add(random.nextInt(4) == 0 ? repeat : new Group(true, null, repeat));
            items.add(new Literal(delimiter, 0));
        }
        switch (random.nextInt(4)) {
            case 0 -> items.add(new Anchor("$"));
            case 1 -> items.add(new Repeat(new AnyCharacter(), 0, -1, true, 0));
            default -> {}
        }
        return new Concat(items);
    }

    /// Returns a pattern of the inner literal search's class-run shape (InnerLiteralSearch) for
    /// the regular-expression fuzzers, a shape random trees rarely reach: sometimes a leading
    /// boundary, a counted or unbounded run of one class that holds the literal's letters, then a
    /// literal or a set of literals, sometimes followed by a boundary or another run of the class.
    static Node innerLiteralShape(Random random)
    {
        List<Node> items = new ArrayList<>();
        switch (random.nextInt(3)) {
            case 0 -> items.add(new Anchor("\\b"));
            case 1 -> items.add(new Anchor("\\B"));
            default -> {}
        }
        Node run = switch (random.nextInt(4)) {
            case 0 -> new PerlClass('w');
            case 1 -> new AnyCharacter();
            case 2 -> new CharacterClass(false, List.of(List.of(new ClassRange('A', 'Z', 0, 0), new ClassRange('a', 'z', 0, 0))));
            default -> new CharacterClass(false, List.of(List.of(new ClassRange('A', 'Z', 0, 0), new ClassRange('a', 'z', 0, 0), new ClassLiteral('é', 0))));
        };
        int[] count = INNER_RUN_COUNTS[random.nextInt(INNER_RUN_COUNTS.length)];
        items.add(new Repeat(run, count[0], count[1], true, 0));
        if (random.nextBoolean()) {
            items.addAll(innerLiteral(INNER_LITERALS.get(random.nextInt(INNER_LITERALS.size()))));
        }
        else {
            List<Node> members = INNER_LITERAL_SETS.get(random.nextInt(INNER_LITERAL_SETS.size())).stream().map(member -> (Node) new Concat(innerLiteral(member))).toList();
            items.add(new Group(random.nextBoolean(), null, new Alternation(members)));
        }
        switch (random.nextInt(3)) {
            case 0 -> items.add(new Anchor("\\b"));
            case 1 -> items.add(new Repeat(run, 0, -1, true, 0));
            default -> {}
        }
        return new Concat(items);
    }

    private static List<Node> innerLiteral(String literal)
    {
        return literal.codePoints().mapToObj(codePoint -> (Node) new Literal(codePoint, 0)).toList();
    }

    private static List<Node> planLiteral(Random random)
    {
        List<Node> literal = new ArrayList<>();
        for (int count = 1 + random.nextInt(3); count > 0; count--) {
            literal.add(planLetter(random));
        }
        return literal;
    }

    private static Node planLetter(Random random)
    {
        return new Literal(PLAN_LETTERS.charAt(random.nextInt(PLAN_LETTERS.length())), 0);
    }

    private static ClassItem planDelimiterItem(Random random)
    {
        return new ClassLiteral(PLAN_DELIMITERS.charAt(random.nextInt(PLAN_DELIMITERS.length())), 0);
    }

    private record Flags(String enabled, String disabled) {}

    private static final class Generator
    {
        private final Random random;
        private final Vocabulary vocabulary;
        private final List<String> palette;
        private int groupNames;

        private Generator(Random random, Vocabulary vocabulary)
        {
            this.random = requireNonNull(random, "random is null");
            this.vocabulary = requireNonNull(vocabulary, "vocabulary is null");
            List<String> palette = new ArrayList<>();
            for (int count = 3 + random.nextInt(5); count > 0; count--) {
                palette.add(randomCharacter());
            }
            this.palette = List.copyOf(palette);
        }

        private Node expression(int depth)
        {
            int alternatives = random.nextInt(depth == 0 ? 3 : 4) == 0 ? 2 + random.nextInt(2) : 1;
            if (alternatives == 1) {
                return sequence(depth);
            }
            List<Node> nodes = new ArrayList<>(alternatives);
            for (int alternative = 0; alternative < alternatives; alternative++) {
                nodes.add(sequence(depth));
            }
            return new Alternation(nodes);
        }

        private Node sequence(int depth)
        {
            int pieces = random.nextInt(depth == 0 ? 6 : 4);
            if (depth == 0 && pieces == 0 && random.nextInt(4) != 0) {
                pieces = 1;
            }
            List<Node> items = new ArrayList<>();
            for (int piece = 0; piece < pieces; piece++) {
                if (vocabulary.commentStyles() > 0 && random.nextInt(8) == 0) {
                    items.add(new Comment(random.nextInt(vocabulary.commentStyles())));
                }
                if (depth < MAX_DEPTH && random.nextInt(12) == 0) {
                    // Isolated flags: the rest of the sequence is their body.
                    Flags flags = flags();
                    items.add(new FlagGroup(flags.enabled(), flags.disabled(), true, expression(depth + 1)));
                    break;
                }
                items.add(piece(depth));
            }
            return items.size() == 1 ? items.getFirst() : new Concat(items);
        }

        private Node piece(int depth)
        {
            Node atom = atom(depth);
            if (!quantifiable(atom) || random.nextInt(10) >= 3) {
                return atom;
            }
            return quantified(atom);
        }

        private Node quantified(Node atom)
        {
            Node quantified = quantify(atom);
            if (quantified == atom || random.nextInt(8) != 0) {
                return quantified;
            }
            return quantify(quantified);
        }

        // Returns `body` with a quantifier the dialect allows, or `body` itself when the dialect
        // allows none of a few tries.
        private Node quantify(Node body)
        {
            boolean singleCharacter = body instanceof Literal || body instanceof PerlClass || body instanceof Property || body instanceof CharacterClass || body instanceof AnyCharacter;
            for (int attempt = 0; attempt < 8; attempt++) {
                boolean greedy = random.nextInt(4) != 0;
                int min;
                int max;
                if (singleCharacter && random.nextInt(12) == 0) {
                    // Longer counted runs, as scan plans see in practice.
                    min = random.nextInt(9);
                    max = random.nextInt(4) == 0 ? -1 : min + random.nextInt(13);
                }
                else {
                    switch (random.nextInt(6)) {
                        case 0 -> {
                            min = 0;
                            max = -1;
                        }
                        case 1 -> {
                            min = 1;
                            max = -1;
                        }
                        case 2 -> {
                            min = 0;
                            max = 1;
                        }
                        case 3 -> {
                            min = random.nextInt(4);
                            max = min;
                        }
                        case 4 -> {
                            min = random.nextInt(3);
                            max = -1;
                        }
                        default -> {
                            min = random.nextInt(3);
                            max = min + random.nextInt(3);
                        }
                    }
                }
                if (vocabulary.repeatRule().allows(body, min, max, greedy)) {
                    int style = random.nextInt(4) == 0 ? random.nextInt(8) : 0;
                    return new Repeat(body, min, max, greedy, style);
                }
            }
            return body;
        }

        private static boolean quantifiable(Node node)
        {
            return !(node instanceof Anchor) && !(node instanceof Comment) && !(node instanceof FlagGroup group && group.isolated());
        }

        private Node atom(int depth)
        {
            boolean nested = depth < MAX_DEPTH;
            int choice = random.nextInt(nested ? 100 : 78);
            if (choice < 70) {
                return character(choice, depth);
            }
            if (choice < 78) {
                return new Anchor(vocabulary.anchors().get(random.nextInt(vocabulary.anchors().size())));
            }
            if (choice < 94) {
                return group(depth);
            }
            // A repetition directly inside a group, so the group's own quantifier nests it.
            boolean capturing = random.nextInt(3) == 0;
            Node repeatedAtom;
            do {
                repeatedAtom = atom(depth + 1);
            }
            while (!quantifiable(repeatedAtom));
            Node repeated = quantified(repeatedAtom);
            return new Group(capturing, capturing && random.nextInt(4) == 0 ? groupName() : null, repeated);
        }

        private Node character(int choice, int depth)
        {
            if (choice < 26) {
                return new Literal(literalCharacter().codePointAt(0), literalStyle());
            }
            if (choice < 30 && vocabulary.quotes()) {
                StringBuilder content = new StringBuilder();
                for (int count = 1 + random.nextInt(3); count > 0; count--) {
                    content.append(palette.get(random.nextInt(palette.size())));
                }
                return new Quote(content.toString());
            }
            if (choice < 35) {
                return new AnyCharacter();
            }
            if (choice < 42) {
                return perlClass();
            }
            if (choice < 48) {
                return property(false);
            }
            return characterClass(depth);
        }

        private PerlClass perlClass()
        {
            return new PerlClass(vocabulary.perlClassLetters().charAt(random.nextInt(vocabulary.perlClassLetters().length())));
        }

        private Property property(boolean inClass)
        {
            boolean negated = random.nextInt(4) == 0;
            if (!inClass && !vocabulary.bracelessPropertyNames().isEmpty() && random.nextInt(6) == 0) {
                return new Property(vocabulary.bracelessPropertyNames().get(random.nextInt(vocabulary.bracelessPropertyNames().size())), negated, true);
            }
            return new Property(vocabulary.propertyNames().get(random.nextInt(vocabulary.propertyNames().size())), negated, false);
        }

        private CharacterClass characterClass(int depth)
        {
            boolean negated = random.nextInt(4) == 0;
            List<List<ClassItem>> operands = new ArrayList<>();
            List<ClassItem> first = new ArrayList<>();
            if (random.nextInt(12) == 0) {
                // A dash that starts a class is a literal.
                first.add(new ClassLiteral('-', 0));
            }
            first.addAll(classOperand(depth));
            operands.add(first);
            String available = vocabulary.classOperators();
            StringBuilder operators = new StringBuilder();
            for (int count = 1; !available.isEmpty() && count < 3 && random.nextInt(5) == 0; count++) {
                operands.add(classOperand(depth));
                // A single operator takes no draw, which keeps that dialect's seeded patterns.
                operators.append(available.length() == 1 ? available.charAt(0) : available.charAt(random.nextInt(available.length())));
            }
            return new CharacterClass(negated, operands, operators.toString());
        }

        private List<ClassItem> classOperand(int depth)
        {
            List<ClassItem> items = new ArrayList<>();
            for (int count = 1 + random.nextInt(3); count > 0; count--) {
                int choice = random.nextInt(depth + 1 < MAX_DEPTH ? 100 : 88);
                if (choice < 35) {
                    items.add(new ClassLiteral(literalCharacter().codePointAt(0), literalStyle()));
                }
                else if (choice < 58) {
                    int first = randomCharacter().codePointAt(0);
                    int second = (random.nextBoolean() ? palette.get(random.nextInt(palette.size())) : randomCharacter()).codePointAt(0);
                    items.add(new ClassRange(Math.min(first, second), Math.max(first, second), literalStyle(), literalStyle()));
                }
                else if (choice < 70) {
                    items.add(perlClass());
                }
                else if (choice < 79) {
                    items.add(property(true));
                }
                else if (choice < 88) {
                    if (vocabulary.posixClassNames().isEmpty()) {
                        items.add(property(true));
                    }
                    else {
                        items.add(new PosixClass(vocabulary.posixClassNames().get(random.nextInt(vocabulary.posixClassNames().size())), random.nextInt(4) == 0));
                    }
                }
                else {
                    items.add(characterClass(depth + 1));
                }
            }
            return items;
        }

        private Node group(int depth)
        {
            int choice = random.nextInt(10);
            if (choice < 3) {
                return new Group(true, choice == 0 ? groupName() : null, expression(depth + 1));
            }
            if (choice < 6) {
                return new Group(false, null, expression(depth + 1));
            }
            Flags flags = flags();
            return new FlagGroup(flags.enabled(), flags.disabled(), false, expression(depth + 1));
        }

        // Returns enabled and disabled flags, never both empty.
        private Flags flags()
        {
            String available = vocabulary.flagLetters();
            StringBuilder enabled = new StringBuilder();
            StringBuilder disabled = new StringBuilder();
            while (enabled.isEmpty() && disabled.isEmpty()) {
                for (int i = 0; i < available.length(); i++) {
                    int choice = random.nextInt(5);
                    if (choice == 0) {
                        enabled.append(available.charAt(i));
                    }
                    else if (choice == 1) {
                        disabled.append(available.charAt(i));
                    }
                }
            }
            return new Flags(enabled.toString(), disabled.toString());
        }

        private String groupName()
        {
            List<String> prefixes = vocabulary.groupNamePrefixes();
            return prefixes.get(random.nextInt(prefixes.size())) + groupNames++;
        }

        private int literalStyle()
        {
            return random.nextInt(vocabulary.literalStyles());
        }

        private String literalCharacter()
        {
            return random.nextInt(5) == 0 ? randomCharacter() : palette.get(random.nextInt(palette.size()));
        }

        private String randomCharacter()
        {
            return vocabulary.characters().get(random.nextInt(vocabulary.characters().size()));
        }
    }
}
