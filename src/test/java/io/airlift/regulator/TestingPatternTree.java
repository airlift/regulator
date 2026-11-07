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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;

/// A regular-expression tree that the differential fuzzer generates, writes in each language's
/// syntax, and shrinks. A node says what the pattern means, not how it is spelled: a dialect
/// writes it, chooses among the spellings a node's style asks for, and refuses a tree that falls
/// outside its language or into a recorded difference. Isolated flags such as `(?i)` are a
/// [FlagGroup] whose body runs to the end of the enclosing group, and a dialect writes one
/// isolated only where its language reads the text that way.
final class TestingPatternTree
{
    /// The empty pattern.
    static final Node EMPTY = new Concat(List.of());

    private TestingPatternTree() {}

    sealed interface Node
            permits Alternation,
                    Anchor,
                    AnyCharacter,
                    CharacterClass,
                    Comment,
                    Concat,
                    FlagGroup,
                    Group,
                    Literal,
                    PerlClass,
                    Property,
                    Quote,
                    Repeat {}

    sealed interface ClassItem
            permits CharacterClass,
                    ClassLiteral,
                    ClassRange,
                    PerlClass,
                    PosixClass,
                    Property {}

    /// A literal character. `style` picks one of the dialect's spellings, such as a hexadecimal
    /// escape; a dialect without that spelling for the character writes it plainly.
    record Literal(int codePoint, int style)
            implements Node {}

    /// A quoted run, `\Q...\E`, of at least one character. As in Java and Joni, a quantifier on
    /// it repeats only its last character.
    record Quote(String text)
            implements Node
    {
        Quote
        {
            if (requireNonNull(text, "text is null").isEmpty()) {
                throw new IllegalArgumentException("quote is empty");
            }
        }
    }

    record AnyCharacter()
            implements Node {}

    /// A class escape such as `\d`, by its letter.
    record PerlClass(char letter)
            implements Node, ClassItem {}

    /// A property such as `\p{L}`. `braceless` asks for the `\pL` spelling, which a dialect
    /// writes only where its language reads it as a property.
    record Property(String name, boolean negated, boolean braceless)
            implements Node, ClassItem
    {
        Property
        {
            requireNonNull(name, "name is null");
        }
    }

    /// A bracket class: the union of each operand's items, combined across the operands from
    /// left to right, and then complemented when `negated`. Every operand has an item.
    /// `operators` has one character for each operand after the first, the operation that
    /// combines it with the result so far: `&` intersection, `-` difference, or `~` symmetric
    /// difference.
    record CharacterClass(boolean negated, List<List<ClassItem>> operands, String operators)
            implements Node, ClassItem
    {
        CharacterClass
        {
            operands = operands.stream().map(List::copyOf).toList();
            if (operands.isEmpty() || operands.stream().anyMatch(List::isEmpty)) {
                throw new IllegalArgumentException("class operand is empty");
            }
            if (operators.length() != operands.size() - 1 || !operators.chars().allMatch(operator -> "&-~".indexOf(operator) >= 0)) {
                throw new IllegalArgumentException("class operators do not match operands: " + operators);
            }
        }

        /// A class whose operands are all intersected.
        CharacterClass(boolean negated, List<List<ClassItem>> operands)
        {
            this(negated, operands, "&".repeat(operands.size() - 1));
        }

        CharacterClass withOperands(List<List<ClassItem>> newOperands)
        {
            return new CharacterClass(negated, newOperands, operators);
        }
    }

    record ClassLiteral(int codePoint, int style)
            implements ClassItem {}

    record ClassRange(int low, int high, int lowStyle, int highStyle)
            implements ClassItem
    {
        ClassRange
        {
            if (low > high) {
                throw new IllegalArgumentException("range is reversed");
            }
        }
    }

    /// A bracket POSIX class such as `[:alpha:]`.
    record PosixClass(String name, boolean negated)
            implements ClassItem
    {
        PosixClass
        {
            requireNonNull(name, "name is null");
        }
    }

    /// An anchor or boundary, by its text, such as `^` or `\b`.
    record Anchor(String text)
            implements Node
    {
        Anchor
        {
            requireNonNull(text, "text is null");
        }
    }

    /// A group. A capturing group without a name is numbered only.
    record Group(boolean capturing, String name, Node body)
            implements Node
    {
        Group
        {
            requireNonNull(body, "body is null");
            if (name != null && !capturing) {
                throw new IllegalArgumentException("named group does not capture");
            }
        }
    }

    /// A flag group such as `(?i-s:...)`. When `isolated`, a dialect writes `(?i-s)` before the
    /// body where its language reads the body as running to the end of the enclosing group.
    record FlagGroup(String enabled, String disabled, boolean isolated, Node body)
            implements Node
    {
        FlagGroup
        {
            requireNonNull(enabled, "enabled is null");
            requireNonNull(disabled, "disabled is null");
            requireNonNull(body, "body is null");
        }
    }

    record Concat(List<Node> items)
            implements Node
    {
        Concat
        {
            items = List.copyOf(items);
        }
    }

    record Alternation(List<Node> alternatives)
            implements Node
    {
        Alternation
        {
            alternatives = List.copyOf(alternatives);
            if (alternatives.size() < 2) {
                throw new IllegalArgumentException("alternation needs two alternatives");
            }
        }
    }

    /// A repetition of `body`, at least `min` and at most `max` times, where a negative `max` is
    /// unbounded. `style` picks a spelling: bit 0 writes braces where a short form exists, bit 1
    /// writes the quantifier directly after a repeated body where the dialect reads that as a
    /// nested repetition, and bit 2 puts a comment before the quantifier where the dialect ignores
    /// one there.
    record Repeat(Node body, int min, int max, boolean greedy, int style)
            implements Node
    {
        Repeat
        {
            requireNonNull(body, "body is null");
            if (min < 0 || (max >= 0 && max < min)) {
                throw new IllegalArgumentException("invalid bounds");
            }
        }

        boolean unbounded()
        {
            return max < 0;
        }

        /// The maximum, with an unbounded repetition as [Integer#MAX_VALUE].
        int maximum()
        {
            return max < 0 ? Integer.MAX_VALUE : max;
        }
    }

    /// Text the language ignores, such as a comment group or whitespace in extended mode; `style`
    /// picks which.
    record Comment(int style)
            implements Node {}

    /// Whether `node` can match the empty string. Anchors and boundaries count as empty.
    static boolean nullable(Node node)
    {
        return switch (node) {
            case Literal _, Quote _, AnyCharacter _, PerlClass _, Property _, CharacterClass _ -> false;
            case Anchor _, Comment _ -> true;
            case Group group -> nullable(group.body());
            case FlagGroup group -> nullable(group.body());
            case Concat concat -> concat.items().stream().allMatch(TestingPatternTree::nullable);
            case Alternation alternation -> alternation.alternatives().stream().anyMatch(TestingPatternTree::nullable);
            case Repeat repeat -> repeat.min() == 0 || nullable(repeat.body());
        };
    }

    /// Whether `node` holds a capturing group.
    static boolean hasCapturingGroup(Node node)
    {
        return contains(node, element -> element instanceof Group group && group.capturing());
    }

    /// Whether `node` or anything in it, including class items, satisfies `predicate`.
    static boolean contains(Node node, Predicate<Object> predicate)
    {
        if (predicate.test(node)) {
            return true;
        }
        if (node instanceof CharacterClass characterClass) {
            return classContains(characterClass, predicate);
        }
        for (Node child : children(node)) {
            if (contains(child, predicate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean classContains(CharacterClass characterClass, Predicate<Object> predicate)
    {
        for (List<ClassItem> operand : characterClass.operands()) {
            for (ClassItem item : operand) {
                if (predicate.test(item) || (item instanceof CharacterClass nested && classContains(nested, predicate))) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Passes `node` and everything in it, including class items, to `visitor`.
    static void walk(Node node, Consumer<Object> visitor)
    {
        contains(node, element -> {
            visitor.accept(element);
            return false;
        });
    }

    /// Returns the named groups and their numbers. Groups are numbered in the order they open.
    static Map<String, Integer> namedGroups(Node node)
    {
        Map<String, Integer> names = new LinkedHashMap<>();
        numberGroups(node, names, new int[1]);
        return names;
    }

    private static void numberGroups(Node node, Map<String, Integer> names, int[] count)
    {
        if (node instanceof Group group && group.capturing()) {
            count[0]++;
            if (group.name() != null) {
                names.put(group.name(), count[0]);
            }
        }
        for (Node child : children(node)) {
            numberGroups(child, names, count);
        }
    }

    /// Returns the characters the pattern writes, as literals, quotes, class members, or range
    /// endpoints, so inputs can use them.
    static Set<String> characters(Node node)
    {
        Set<String> characters = new LinkedHashSet<>();
        walk(node, element -> {
            switch (element) {
                case Literal literal -> characters.add(Character.toString(literal.codePoint()));
                case Quote quote -> characters.addAll(quote.text().codePoints().mapToObj(Character::toString).toList());
                case ClassLiteral literal -> characters.add(Character.toString(literal.codePoint()));
                case ClassRange range -> {
                    characters.add(Character.toString(range.low()));
                    characters.add(Character.toString(range.high()));
                }
                default -> {}
            }
        });
        return characters;
    }

    static List<Node> children(Node node)
    {
        return switch (node) {
            case Group group -> List.of(group.body());
            case FlagGroup group -> List.of(group.body());
            case Concat concat -> concat.items();
            case Alternation alternation -> alternation.alternatives();
            case Repeat repeat -> List.of(repeat.body());
            default -> List.of();
        };
    }

    /// Returns `node` with `function` applied to every node, children first.
    static Node transform(Node node, UnaryOperator<Node> function)
    {
        List<Node> children = children(node);
        Node rebuilt = node;
        for (int index = 0; index < children.size(); index++) {
            rebuilt = withChild(rebuilt, index, transform(children.get(index), function));
        }
        return function.apply(rebuilt);
    }

    /// Returns `node` with `child` in place of its child at `index`.
    static Node withChild(Node node, int index, Node child)
    {
        return switch (node) {
            case Group group -> new Group(group.capturing(), group.name(), child);
            case FlagGroup group -> new FlagGroup(group.enabled(), group.disabled(), group.isolated(), child);
            case Concat concat -> new Concat(replaced(concat.items(), index, child));
            case Alternation alternation -> new Alternation(replaced(alternation.alternatives(), index, child));
            case Repeat repeat -> new Repeat(child, repeat.min(), repeat.max(), repeat.greedy(), repeat.style());
            default -> throw new IllegalArgumentException("node has no children: " + node);
        };
    }

    /// Returns a string `node` should match, a best guess used only to raise the share of inputs
    /// that match. `caseInsensitive` says whether case-insensitive matching is on where `node`
    /// starts, and `characters` supplies characters for classes and dots.
    static String sample(Node node, Random random, boolean caseInsensitive, List<String> characters)
    {
        StringBuilder sample = new StringBuilder();
        appendSample(node, random, caseInsensitive, characters, sample);
        return sample.toString();
    }

    private static void appendSample(Node node, Random random, boolean caseInsensitive, List<String> characters, StringBuilder sample)
    {
        switch (node) {
            case Literal literal -> sample.appendCodePoint(caseInsensitive ? caseVariant(literal.codePoint(), random) : literal.codePoint());
            case Quote quote -> {
                for (int codePoint : quote.text().codePoints().toArray()) {
                    sample.appendCodePoint(caseInsensitive ? caseVariant(codePoint, random) : codePoint);
                }
            }
            case AnyCharacter _ -> sample.append(randomCharacterExcept(random, characters, "\n"));
            case PerlClass perlClass -> sample.append(perlSample(perlClass.letter(), random, characters));
            case Property _ -> sample.append(characters.get(random.nextInt(characters.size())));
            case CharacterClass characterClass -> sample.append(classSample(characterClass, random, characters));
            case Anchor _, Comment _ -> {}
            case Group group -> appendSample(group.body(), random, caseInsensitive, characters, sample);
            case FlagGroup group -> {
                boolean inner = group.enabled().indexOf('i') >= 0 || (caseInsensitive && group.disabled().indexOf('i') < 0);
                appendSample(group.body(), random, inner, characters, sample);
            }
            case Concat concat -> {
                for (Node item : concat.items()) {
                    appendSample(item, random, caseInsensitive, characters, sample);
                }
            }
            case Alternation alternation -> {
                List<Node> alternatives = alternation.alternatives();
                appendSample(alternatives.get(random.nextInt(alternatives.size())), random, caseInsensitive, characters, sample);
            }
            case Repeat repeat -> {
                int repetitions = Math.min(repeat.min(), 20);
                if (repeat.maximum() > repetitions && random.nextBoolean()) {
                    repetitions++;
                }
                for (int i = 0; i < repetitions; i++) {
                    appendSample(repeat.body(), random, caseInsensitive, characters, sample);
                }
            }
        }
    }

    private static String classSample(CharacterClass characterClass, Random random, List<String> characters)
    {
        if (characterClass.negated()) {
            return characters.get(random.nextInt(characters.size()));
        }
        List<ClassItem> operand = characterClass.operands().getFirst();
        return switch (operand.get(random.nextInt(operand.size()))) {
            case ClassLiteral literal -> Character.toString(literal.codePoint());
            case ClassRange range -> Character.toString(random.nextBoolean() ? range.low() : range.high());
            case PerlClass perlClass -> perlSample(perlClass.letter(), random, characters);
            case CharacterClass nested -> classSample(nested, random, characters);
            case Property _, PosixClass _ -> characters.get(random.nextInt(characters.size()));
        };
    }

    private static String perlSample(char letter, Random random, List<String> characters)
    {
        return switch (letter) {
            case 'd' -> random.nextBoolean() ? "7" : "0";
            case 's' -> random.nextBoolean() ? " " : "\t";
            case 'w' -> random.nextBoolean() ? "_" : "k";
            case 'h' -> " ";
            case 'v' -> "\n";
            default -> randomCharacterExcept(random, characters, " ");
        };
    }

    private static String randomCharacterExcept(Random random, List<String> characters, String excluded)
    {
        for (int attempt = 0; attempt < 8; attempt++) {
            String character = characters.get(random.nextInt(characters.size()));
            if (!character.equals(excluded)) {
                return character;
            }
        }
        return "a";
    }

    // Another spelling of the character under simple case folding, or the character itself.
    private static int caseVariant(int codePoint, Random random)
    {
        List<Integer> variants = new ArrayList<>(List.of(codePoint, Character.toUpperCase(codePoint), Character.toLowerCase(codePoint), Character.toTitleCase(codePoint)));
        switch (codePoint) {
            case 'k', 'K' -> variants.add(0x212A);
            case 's', 'S' -> variants.add(0x017F);
            case 0x3C9, 0x3A9 -> variants.add(0x2126);
            case 0x3C3, 0x3A3 -> variants.add(0x3C2);
            case 0xE5, 0xC5 -> variants.add(0x212B);
            case 0x3B8, 0x398 -> variants.add(0x3D1);
            case 0xB5 -> variants.add(0x3BC);
            default -> {}
        }
        return variants.get(random.nextInt(variants.size()));
    }

    /// Returns the trees one simplification away from `node`, larger reductions first: the
    /// empty pattern, a node replaced by a child, a deleted item or alternative, lowered
    /// repetition bounds, a greedy or plainly spelled repetition, a class replaced by one of its
    /// literals, a simpler class, literal, or quote, a group that no longer captures, and a
    /// dropped flag or group name.
    static List<Node> simplifications(Node node)
    {
        List<Node> simpler = new ArrayList<>();
        if (!node.equals(EMPTY)) {
            simpler.add(EMPTY);
        }
        switch (node) {
            case Concat concat -> {
                for (int index = 0; index < concat.items().size(); index++) {
                    List<Node> items = new ArrayList<>(concat.items());
                    items.remove(index);
                    simpler.add(items.size() == 1 ? items.getFirst() : new Concat(items));
                }
            }
            case Alternation alternation -> {
                simpler.addAll(alternation.alternatives());
                if (alternation.alternatives().size() > 2) {
                    for (int index = 0; index < alternation.alternatives().size(); index++) {
                        List<Node> alternatives = new ArrayList<>(alternation.alternatives());
                        alternatives.remove(index);
                        simpler.add(new Alternation(alternatives));
                    }
                }
            }
            case Group group -> {
                simpler.add(group.body());
                if (group.name() != null) {
                    simpler.add(new Group(true, null, group.body()));
                }
                if (group.capturing()) {
                    simpler.add(new Group(false, null, group.body()));
                }
            }
            case FlagGroup group -> {
                simpler.add(group.body());
                for (int index = 0; index < group.enabled().length(); index++) {
                    simpler.add(new FlagGroup(dropCharacter(group.enabled(), index), group.disabled(), group.isolated(), group.body()));
                }
                for (int index = 0; index < group.disabled().length(); index++) {
                    simpler.add(new FlagGroup(group.enabled(), dropCharacter(group.disabled(), index), group.isolated(), group.body()));
                }
            }
            case Repeat repeat -> {
                simpler.add(repeat.body());
                if (repeat.min() > 0) {
                    simpler.add(new Repeat(repeat.body(), repeat.min() - 1, repeat.max(), repeat.greedy(), repeat.style()));
                }
                if (repeat.unbounded()) {
                    simpler.add(new Repeat(repeat.body(), repeat.min(), repeat.min() + 1, repeat.greedy(), repeat.style()));
                }
                else if (repeat.max() > repeat.min()) {
                    simpler.add(new Repeat(repeat.body(), repeat.min(), repeat.max() - 1, repeat.greedy(), repeat.style()));
                }
                if (!repeat.greedy()) {
                    simpler.add(new Repeat(repeat.body(), repeat.min(), repeat.max(), true, repeat.style()));
                }
                if (repeat.style() != 0) {
                    simpler.add(new Repeat(repeat.body(), repeat.min(), repeat.max(), repeat.greedy(), 0));
                }
            }
            case Quote quote -> {
                simpler.add(new Literal(quote.text().codePointAt(0), 0));
                List<String> characters = quote.text().codePoints().mapToObj(Character::toString).toList();
                if (characters.size() > 1) {
                    for (int index = 0; index < characters.size(); index++) {
                        List<String> remaining = new ArrayList<>(characters);
                        remaining.remove(index);
                        simpler.add(new Quote(String.join("", remaining)));
                    }
                }
            }
            case Literal literal -> {
                if (literal.style() != 0) {
                    simpler.add(new Literal(literal.codePoint(), 0));
                }
                if (literal.codePoint() != 'a') {
                    simpler.add(new Literal('a', literal.style()));
                }
            }
            case Property property -> {
                if (property.negated() || property.braceless()) {
                    simpler.add(new Property(property.name(), false, false));
                }
            }
            case CharacterClass characterClass -> {
                for (ClassItem item : characterClass.operands().getFirst()) {
                    if (item instanceof ClassLiteral literal) {
                        simpler.add(new Literal(literal.codePoint(), literal.style()));
                    }
                }
                for (ClassItem simplified : classItemSimplifications(characterClass)) {
                    simpler.add((CharacterClass) simplified);
                }
            }
            default -> {}
        }
        List<Node> children = children(node);
        for (int index = 0; index < children.size(); index++) {
            for (Node child : simplifications(children.get(index))) {
                simpler.add(withChild(node, index, child));
            }
        }
        return simpler;
    }

    private static List<ClassItem> classItemSimplifications(ClassItem item)
    {
        List<ClassItem> simpler = new ArrayList<>();
        switch (item) {
            case CharacterClass characterClass -> {
                List<List<ClassItem>> operands = characterClass.operands();
                if (characterClass.negated()) {
                    simpler.add(new CharacterClass(false, operands, characterClass.operators()));
                }
                if (operands.size() > 1) {
                    simpler.add(new CharacterClass(characterClass.negated(), List.of(operands.getFirst())));
                }
                if (characterClass.operators().chars().anyMatch(operator -> operator != '&')) {
                    simpler.add(new CharacterClass(characterClass.negated(), operands));
                }
                for (int operandIndex = 0; operandIndex < operands.size(); operandIndex++) {
                    List<ClassItem> operand = operands.get(operandIndex);
                    for (int itemIndex = 0; itemIndex < operand.size(); itemIndex++) {
                        if (operand.size() > 1) {
                            List<ClassItem> remaining = new ArrayList<>(operand);
                            remaining.remove(itemIndex);
                            simpler.add(characterClass.withOperands(replaced(operands, operandIndex, remaining)));
                        }
                        ClassItem current = operand.get(itemIndex);
                        if (current instanceof CharacterClass nested && !nested.negated() && nested.operands().size() == 1) {
                            List<ClassItem> flattened = new ArrayList<>(operand);
                            flattened.remove(itemIndex);
                            flattened.addAll(itemIndex, nested.operands().getFirst());
                            simpler.add(characterClass.withOperands(replaced(operands, operandIndex, flattened)));
                        }
                        for (ClassItem simplified : classItemSimplifications(current)) {
                            simpler.add(characterClass.withOperands(replaced(operands, operandIndex, replaced(operand, itemIndex, simplified))));
                        }
                    }
                }
            }
            case ClassRange range -> {
                simpler.add(new ClassLiteral(range.low(), range.lowStyle()));
                simpler.add(new ClassLiteral(range.high(), range.highStyle()));
            }
            case ClassLiteral literal -> {
                if (literal.style() != 0) {
                    simpler.add(new ClassLiteral(literal.codePoint(), 0));
                }
            }
            case Property property -> {
                if (property.negated()) {
                    simpler.add(new Property(property.name(), false, false));
                }
            }
            case PosixClass posixClass -> {
                if (posixClass.negated()) {
                    simpler.add(new PosixClass(posixClass.name(), false));
                }
            }
            case PerlClass _ -> {}
        }
        return simpler;
    }

    /// Returns the quantifier that writes `repeat`, in the syntax Java, Trino, RE2, and Rust share.
    static String quantifier(Repeat repeat)
    {
        boolean braces = (repeat.style() & 1) != 0;
        String text;
        if (repeat.min() == 0 && repeat.unbounded() && !braces) {
            text = "*";
        }
        else if (repeat.min() == 1 && repeat.unbounded() && !braces) {
            text = "+";
        }
        else if (repeat.min() == 0 && repeat.max() == 1 && !braces) {
            text = "?";
        }
        else if (repeat.min() == repeat.max()) {
            text = "{" + repeat.min() + "}";
        }
        else if (repeat.unbounded()) {
            text = "{" + repeat.min() + ",}";
        }
        else {
            text = "{" + repeat.min() + "," + repeat.max() + "}";
        }
        return repeat.greedy() ? text : text + "?";
    }

    /// Returns the code points with a simple case fold that Unicode assigned after `version`.
    static Set<Integer> foldsAddedAfter(String version)
    {
        CharClass assigned = requireNonNull(UnicodeGroups.agesThrough(version), () -> "unknown Unicode age " + version);
        Set<Integer> added = new HashSet<>();
        for (int codePoint : UnicodeDatabase.simpleCaseFolding().codePoints()) {
            if (!assigned.contains(codePoint)) {
                added.add(codePoint);
            }
        }
        return Set.copyOf(added);
    }

    /// Whether the range holds one of `codePoints`.
    static boolean rangeHolds(ClassRange range, Set<Integer> codePoints)
    {
        return codePoints.stream().anyMatch(codePoint -> range.low() <= codePoint && codePoint <= range.high());
    }

    private static <T> List<T> replaced(List<T> list, int index, T value)
    {
        List<T> copy = new ArrayList<>(list);
        copy.set(index, value);
        return copy;
    }

    private static String dropCharacter(String value, int index)
    {
        return value.substring(0, index) + value.substring(index + 1);
    }

    /// Thrown by a writer for a tree outside its dialect.
    static final class OutsideDialect
            extends RuntimeException
    {
        OutsideDialect()
        {
            super("outside the dialect", null, false, false);
        }
    }
}
