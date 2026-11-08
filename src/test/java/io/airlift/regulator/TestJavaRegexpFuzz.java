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

import io.airlift.regulator.TestingDifferentialFuzzer.CaseInput;
import io.airlift.regulator.TestingDifferentialFuzzer.Generated;
import io.airlift.regulator.TestingDifferentialFuzzer.Language;
import io.airlift.regulator.TestingDifferentialFuzzer.Masked;
import io.airlift.regulator.TestingDifferentialFuzzer.Oracle;
import io.airlift.regulator.TestingDifferentialFuzzer.OracleSkipException;
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
import io.airlift.regulator.UnicodeDatabase.CodePointMappings;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

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
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.airlift.regulator.TestingDifferentialFuzzer.groups;
import static io.airlift.regulator.TestingDifferentialFuzzer.hex;
import static io.airlift.regulator.TestingDifferentialFuzzer.routeClass;
import static io.airlift.regulator.TestingPatternGenerator.TEXT_CHARACTERS;
import static io.airlift.regulator.TestingPatternTree.quantifier;
import static io.airlift.slice.Slices.utf8Slice;
import static java.util.Comparator.comparingInt;
import static java.util.regex.Pattern.CANON_EQ;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.COMMENTS;
import static java.util.regex.Pattern.DOTALL;
import static java.util.regex.Pattern.LITERAL;
import static java.util.regex.Pattern.MULTILINE;
import static java.util.regex.Pattern.UNICODE_CASE;
import static java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;
import static java.util.regex.Pattern.UNIX_LINES;
import static org.assertj.core.api.Assertions.assertThat;

/// Differential fuzzing of [JavaRegexp] against the JDK's `java.util.regex.Pattern`; see
/// [TestingDifferentialFuzzer]. Options are `Pattern` flag bits. The dialect writes the
/// supported regular subset and leaves out each recorded difference, cited where it is left out.
public class TestJavaRegexpFuzz
{
    static final Language<Compiled, JdkPattern> LANGUAGE = new JavaLanguage();

    private static final long SEED = 0x4A_41_56_41_46_55_5CL;
    private static final int PATTERNS = 600;

    // The flags that options combine, LITERAL apart.
    private static final int[] FLAGS = {UNIX_LINES, CASE_INSENSITIVE, COMMENTS, MULTILINE, DOTALL, UNICODE_CASE, UNICODE_CHARACTER_CLASS};
    private static final Map<Integer, String> FLAG_NAMES = Map.of(
            UNIX_LINES, "Pattern.UNIX_LINES",
            CASE_INSENSITIVE, "Pattern.CASE_INSENSITIVE",
            COMMENTS, "Pattern.COMMENTS",
            MULTILINE, "Pattern.MULTILINE",
            LITERAL, "Pattern.LITERAL",
            DOTALL, "Pattern.DOTALL",
            UNICODE_CASE, "Pattern.UNICODE_CASE",
            CANON_EQ, "Pattern.CANON_EQ",
            UNICODE_CHARACTER_CLASS, "Pattern.UNICODE_CHARACTER_CLASS");

    // An uncompared group's spans or value, in the braces that mark it.
    private static final Pattern UNCOMPARED_GROUP = Pattern.compile("\\{[^}]*}");

    // Characters whose properties and case mappings are the same in Unicode 16.0 (JDK 25), 17.0
    // (JDK 26), and Regulator's pinned 18.0. Capital sharp s is left out, because JDK 25 misses
    // some case-insensitive matches between it and sharp s that JDK 26 makes, and so is U+0295,
    // which Unicode 17.0 changed (JAVA_REGEXP.md). Inputs are valid UTF-8, so no lone surrogate.
    private static final List<String> CHARACTERS = Stream.concat(
                    TEXT_CHARACTERS.stream(),
                    Stream.of(
                            "ß",
                            "ﬁ",
                            "İ",
                            "ı",
                            "ŉ",
                            "ΐ",
                            "\u2029",
                            "\f",
                            "\u000B",
                            "\u212B",
                            "Å",
                            "å",
                            "θ",
                            "ϑ",
                            "ÿ",
                            "\u0178",
                            "ā",
                            "$",
                            "^",
                            "|",
                            "#",
                            "&",
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
                            "~",
                            "<",
                            "="))
            .toList();

    // Property names Pattern resolves, with members that agree across those Unicode versions on
    // the characters above. Extended_Pictographic is left out (JAVA_REGEXP.md).
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
            "LC",
            "LD",
            "L1",
            "all",
            "IsL",
            "IsLu",
            "gc=Ll",
            "general_category=Nd",
            "IsLatin",
            "IsGreek",
            "IsCyrillic",
            "IsHan",
            "IsCommon",
            "IsInherited",
            "IsHiragana",
            "IsDeseret",
            "sc=Latin",
            "script=Greek",
            "InBasicLatin",
            "InGreek",
            "InCyrillic",
            "InCJKUnifiedIdeographs",
            "InCombiningDiacriticalMarks",
            "InLetterlikeSymbols",
            "InGeneralPunctuation",
            "InHalfwidthandFullwidthForms",
            "blk=Hiragana",
            "block=Deseret",
            "IsAlphabetic",
            "IsIdeographic",
            "IsLetter",
            "IsLowercase",
            "IsUppercase",
            "IsTitlecase",
            "IsPunctuation",
            "IsControl",
            "IsWhite_Space",
            "IsDigit",
            "IsHex_Digit",
            "IsJoin_Control",
            "IsAssigned",
            "Lower",
            "Upper",
            "ASCII",
            "Alpha",
            "Digit",
            "Alnum",
            "Punct",
            "Graph",
            "Print",
            "Blank",
            "Cntrl",
            "XDigit",
            "Space",
            "javaLowerCase",
            "javaUpperCase",
            "javaTitleCase",
            "javaDigit",
            "javaLetter",
            "javaLetterOrDigit",
            "javaAlphabetic",
            "javaIdeographic",
            "javaWhitespace",
            "javaSpaceChar",
            "javaISOControl",
            "javaMirrored",
            "javaDefined",
            "javaJavaIdentifierStart",
            "javaJavaIdentifierPart",
            "javaUnicodeIdentifierStart",
            "javaUnicodeIdentifierPart",
            "javaIdentifierIgnorable");

    private static final Vocabulary VOCABULARY = new Vocabulary(
            CHARACTERS,
            PROPERTY_NAMES,
            List.of("L", "N", "P", "S", "Z", "M", "C"),
            List.of(),
            "dDsSwWhHvV",
            List.of("^", "$", "\\A", "\\Z", "\\z", "\\b", "\\B"),
            // Canonical equivalence, c, is unsupported (JAVA_REGEXP.md, Unsupported features).
            "dimsuxU",
            // Java group names are ASCII letters and digits.
            List.of("n", "g", "name"),
            true,
            "&",
            12,
            5,
            TestJavaRegexpFuzz::allowsRepeat);

    // The sources of the closing folds of case-insensitive ranges, and their targets.
    private static final CodePointMappings CLOSING_FOLDS = closingFolds();

    @Test
    public void testFuzz()
    {
        TestingDifferentialFuzzer.fuzz(LANGUAGE, SEED, PATTERNS);
    }

    @Test
    public void testCaptureShapes()
    {
        assertThat(CaptureShapes.uncomparedGroups("(?:(a))*a", 0)).containsExactly(1);
        // Every match runs fixed counts again.
        assertThat(CaptureShapes.uncomparedGroups("(?:(?:(a)){1}){1}a", 0)).isEmpty();
        // An empty quote is dropped, so the count applies to the group.
        assertThat(CaptureShapes.uncomparedGroups("(?:(a)\\Q\\E{1,2})*", 0)).containsExactly(1);
        // Comments mode ends a comment at CR, or only at LF in UNIX_LINES mode.
        assertThat(CaptureShapes.uncomparedGroups("(?x)(?:(a)#comment\r)*ab", 0)).containsExactly(1);
        assertThat(CaptureShapes.uncomparedGroups("(?xd)(?:(a)#comment\r)\n)*ab", 0)).containsExactly(1);
        // Comments mode reads past whitespace in a count, after an opening parenthesis, and
        // before a lazy quantifier's ?.
        assertThat(CaptureShapes.uncomparedGroups("(?x)(?:(a)){1 2}", 0)).isEmpty();
        assertThat(CaptureShapes.uncomparedGroups("(?x)( ?:(a))*ab", 0)).containsExactly(1);
        assertThat(CaptureShapes.uncomparedGroups("(?x)(?:(a)+ ?b)*", 0)).isEmpty();
    }

    // Repeated captures whose body can match the empty string are unsupported (JAVA_REGEXP.md,
    // Unsupported features); as in the parser, anchors count as empty.
    private static boolean allowsRepeat(Node body, int min, int max, boolean greedy)
    {
        return max == 1 || !TestingPatternTree.hasCapturingGroup(body) || !TestingPatternTree.nullable(body);
    }

    private static final class JavaLanguage
            implements Language<Compiled, JdkPattern>
    {
        @Override
        public String name()
        {
            return "java";
        }

        @Override
        public String replayName()
        {
            return "TestJavaRegexpFuzz.LANGUAGE";
        }

        @Override
        public Generated generate(Random random)
        {
            int options = 0;
            for (int flag : FLAGS) {
                if (random.nextInt(4) == 0) {
                    options |= flag;
                }
            }
            if (random.nextInt(16) == 0) {
                options |= LITERAL;
            }
            return new Generated(TestingPatternGenerator.generate(random, VOCABULARY), options);
        }

        @Override
        public String render(Node tree, int options)
        {
            return JavaWriter.write(tree, options);
        }

        @Override
        public String describeOptions(int options)
        {
            return TestingDifferentialFuzzer.describeFlags(options, FLAG_NAMES);
        }

        @Override
        public String sample(Node tree, int options, String pattern, Random random)
        {
            if ((options & LITERAL) != 0) {
                return pattern;
            }
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
            // JDK 25 misses some case-insensitive matches of capital sharp s (JAVA_REGEXP.md).
            return input.replace("\u1E9E", "");
        }

        @Override
        public Compiled compile(String pattern, int options, Variant variant)
        {
            JavaRegexp.Options javaOptions = JavaRegexp.Options.defaults()
                    .setUnixLines((options & UNIX_LINES) != 0)
                    .setCaseInsensitive((options & CASE_INSENSITIVE) != 0)
                    .setComments((options & COMMENTS) != 0)
                    .setMultiline((options & MULTILINE) != 0)
                    .setLiteral((options & LITERAL) != 0)
                    .setDotMatchesNewline((options & DOTALL) != 0)
                    .setUnicodeCase((options & UNICODE_CASE) != 0)
                    .setUnicodeCharacterClasses((options & UNICODE_CHARACTER_CLASS) != 0)
                    .setGenerateBytecode(variant.generateBytecode());
            Slice slice = utf8Slice(pattern);
            JavaRegexp regexp = switch (variant.scanPlanMode()) {
                case DEFAULT -> JavaRegexp.compile(slice, javaOptions);
                case FORCE_HANDOFF -> JavaRegexp.compileForcingScanPlanHandoffForTesting(slice, javaOptions);
                case DISABLED -> JavaRegexp.compileWithoutScanPlanForTesting(slice, javaOptions);
            };
            return new Compiled(regexp, CaptureShapes.uncomparedGroups(pattern, options));
        }

        @Override
        public Oracle<JdkPattern> oracle()
        {
            return JdkOracle.INSTANCE;
        }

        @Override
        public void operations(Compiled compiled, CaseInput input, Trace trace)
        {
            JavaRegexp regexp = compiled.regexp();
            Set<Integer> uncompared = compiled.uncomparedGroups();
            Slice source = input.source();
            int regionStart = input.regionStart();
            int regionEnd = input.regionEnd();
            int groupCount = regexp.capturingGroupCount();
            Map<String, Integer> names = new TreeMap<>(regexp.namedCapturingGroups());
            int limit = input.bytes().length + 2;
            trace.add("groups", () -> groupCount + " " + names);
            RegexpMatcher matcher = regexp.matcher(source);
            trace.add("find all", () -> compared(findAll(matcher, names, uncompared, limit)));
            trace.add("count", () -> regexp.count(source));
            trace.add("find", () -> regexp.find(source));
            trace.add("lookingAt", () -> regexp.lookingAt(source));
            trace.add("matches", () -> regexp.matches(source));
            trace.add("findResult", () -> compared(result(regexp.findResult(source), uncompared)));
            trace.add("lookingAtResult", () -> compared(result(regexp.lookingAtResult(source), uncompared)));
            trace.add("matchesResult", () -> compared(result(regexp.matchesResult(source), uncompared)));
            int[] groups = new int[2 * (groupCount + 1)];
            int[] groupZero = new int[2];
            trace.add("findInto", () -> compared(into(regexp.findInto(source, groups), groups, uncompared)));
            trace.add("findInto group zero", () -> compared(into(regexp.findInto(source, groupZero), groupZero, uncompared)));
            trace.add("lookingAtInto", () -> compared(into(regexp.lookingAtInto(source, groups), groups, uncompared)));
            trace.add("matchesInto", () -> compared(into(regexp.matchesInto(source, groups), groups, uncompared)));
            trace.add("find range", () -> regexp.find(source, regionStart, regionEnd));
            trace.add("lookingAt range", () -> regexp.lookingAt(source, regionStart, regionEnd));
            trace.add("matches range", () -> regexp.matches(source, regionStart, regionEnd));
            trace.add("findResult range", () -> compared(result(regexp.findResult(source, regionStart, regionEnd), uncompared)));
            trace.add("lookingAtResult range", () -> compared(result(regexp.lookingAtResult(source, regionStart, regionEnd), uncompared)));
            trace.add("matchesResult range", () -> compared(result(regexp.matchesResult(source, regionStart, regionEnd), uncompared)));
            trace.add("findInto range", () -> compared(into(regexp.findInto(source, regionStart, regionEnd, groups), groups, uncompared)));
            trace.add("lookingAtInto range", () -> compared(into(regexp.lookingAtInto(source, regionStart, regionEnd, groups), groups, uncompared)));
            trace.add("matchesInto range", () -> compared(into(regexp.matchesInto(source, regionStart, regionEnd, groups), groups, uncompared)));
            trace.add("region find all", () -> compared(findAll(matcher.reset(source, regionStart, regionEnd), names, uncompared, limit)));
            trace.add("region lookingAt", () -> compared(current(matcher.reset(source, regionStart, regionEnd).lookingAt(), matcher, uncompared)));
            trace.add("region lookingAt then find", () -> {
                matcher.reset(source, regionStart, regionEnd).lookingAt();
                return compared(current(matcher.find(), matcher, uncompared));
            });
            trace.add("region matches", () -> compared(current(matcher.reset(source, regionStart, regionEnd).matches(), matcher, uncompared)));
            trace.add("region matches then find", () -> {
                matcher.reset(source, regionStart, regionEnd).matches();
                return compared(current(matcher.find(), matcher, uncompared));
            });
            trace.add("find from", () -> compared(current(matcher.reset(source, regionStart, regionEnd).find(input.searchStart()), matcher, uncompared)));
            trace.add("find from then find", () -> {
                matcher.reset(source, regionStart, regionEnd).find(input.searchStart());
                return compared(current(matcher.find(), matcher, uncompared));
            });
            trace.add("group zero find all", () -> compared(findAll(regexp.matcher(source, 0), Map.of(), uncompared, limit)));
            if (groupCount >= 2) {
                int retained = partialGroups(input, groupCount);
                trace.add("partial find all", () -> compared(findAll(regexp.matcher(source, retained), Map.of(), uncompared, limit)));
            }
        }

        // Java plans agree with the ordinary engine on malformed input (REGULATOR_API.md), though
        // the JDK assigns it no meaning.
        @Override
        public boolean variantsAgreeOnMalformedInput()
        {
            return true;
        }

        // Unlike RE2's and Rust's, Java's routes leave out a find that runs on the scan plan.
        @Override
        public Set<String> coverage(Compiled compiled)
        {
            JavaRegexp regexp = compiled.regexp();
            Set<String> routes = new HashSet<>();
            routes.add(routeClass(regexp));
            if (regexp.usesScanPlanForDiagnostics()) {
                routes.add("scan plan");
            }
            if (regexp.usesGeneratedBytecode()) {
                routes.add("generated bytecode");
            }
            return routes;
        }
    }

    private static int partialGroups(CaseInput input, int groupCount)
    {
        return 1 + input.bytes().length % (groupCount - 1);
    }

    private static String findAll(RegexpMatcher matcher, Map<String, Integer> names, Set<Integer> uncompared, int limit)
    {
        List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            if (matches.size() == limit) {
                matches.add("more than " + limit);
                break;
            }
            String spans = spans(groups(matcher), uncompared);
            StringBuilder match = new StringBuilder(spans);
            appendNames(match, names, uncompared, group -> hex(matcher.group(group)));
            String result = spans(groups(matcher.toMatchResult()), uncompared);
            if (!result.equals(spans)) {
                match.append(" toMatchResult ").append(result);
            }
            matches.add(match.toString());
        }
        return matches.toString();
    }

    private static void appendNames(StringBuilder match, Map<String, Integer> names, Set<Integer> uncompared, IntFunction<String> group)
    {
        for (Map.Entry<String, Integer> name : names.entrySet()) {
            match.append(' ').append(name.getKey()).append('=');
            if (uncompared.contains(name.getValue())) {
                match.append('{').append(group.apply(name.getValue())).append('}');
            }
            else {
                match.append(group.apply(name.getValue()));
            }
        }
    }

    // Takes a result that marks its uncompared groups with braces, and returns it without the
    // braces as the raw result the variants compare, and with each of those groups written as ?
    // as the result the oracle judges.
    private static Object compared(String result)
    {
        if (result.indexOf('{') < 0) {
            return result;
        }
        return new Masked(result.replace("{", "").replace("}", ""), UNCOMPARED_GROUP.matcher(result).replaceAll("?"));
    }

    // The spans of each group, with an uncompared group (CaptureShapes) in braces for
    // [#compared].
    private static String spans(int[] groups, Set<Integer> uncompared)
    {
        StringBuilder spans = new StringBuilder("[");
        for (int group = 0; 2 * group + 1 < groups.length; group++) {
            if (group > 0) {
                spans.append(' ');
            }
            if (uncompared.contains(group)) {
                spans.append('{').append(groups[2 * group]).append(':').append(groups[2 * group + 1]).append('}');
            }
            else {
                spans.append(groups[2 * group]).append(':').append(groups[2 * group + 1]);
            }
        }
        return spans.append(']').toString();
    }

    private static String current(boolean matched, RegexpMatcher matcher, Set<Integer> uncompared)
    {
        return matched ? spans(groups(matcher), uncompared) : "none";
    }

    private static String result(MatchResult result, Set<Integer> uncompared)
    {
        return result == null ? "none" : spans(groups(result), uncompared);
    }

    private static String into(boolean matched, int[] groups, Set<Integer> uncompared)
    {
        return matched ? spans(groups, uncompared) : "none";
    }

    /// A compiled pattern and the groups its results leave uncompared ([CaptureShapes]).
    record Compiled(JavaRegexp regexp, Set<Integer> uncomparedGroups) {}

    /// The JDK's compiled pattern and the groups its results leave uncompared ([CaptureShapes]).
    record JdkPattern(Pattern pattern, Set<Integer> uncomparedGroups) {}

    /// The capturing groups whose JDK values are recorded differences (REGULATOR_DECISIONS.md,
    /// Java Regular-Subset Frontend). The oracle comparison writes their spans and values as `?`
    /// on both sides and still compares the match bounds and every other group, and the variants
    /// compare them all with one another. Both sides read the groups from the pattern text, all a
    /// replay has, so the pattern is parsed back into its groups, alternations, and repetitions.
    ///
    /// The JDK runs a repetition of a group whose body has no alternation, optional part, or
    /// variable count as a group loop that matches the body without what follows it and never
    /// restores the captures inside. Those captures keep values from abandoned iterations and
    /// failed attempts, unless the loop's count is fixed and nothing around it lets a match skip
    /// it, so that every match runs all its iterations again. A greedy variable count of such a
    /// capturing group inside a repetition that can run twice also writes its own capture again
    /// as it backs off, over the value a later iteration set.
    static final class CaptureShapes
    {
        // Atoms and anchors read as one node, since none of them affects the shapes.
        private static final Node ATOM = new AnyCharacter();

        private final String pattern;
        private int position;
        private boolean extended;
        private boolean unixLines;

        private CaptureShapes(String pattern, boolean extended, boolean unixLines)
        {
            this.pattern = withoutEmptyQuotes(pattern);
            this.extended = extended;
            this.unixLines = unixLines;
        }

        static Set<Integer> uncomparedGroups(String pattern, int options)
        {
            if ((options & LITERAL) != 0) {
                return Set.of();
            }
            Node tree = new CaptureShapes(pattern, (options & COMMENTS) != 0, (options & UNIX_LINES) != 0).alternation();
            Set<Integer> uncompared = new HashSet<>();
            addUncompared(tree, false, false, false, new int[1], uncompared);
            return Set.copyOf(uncompared);
        }

        // Adds the uncompared groups of `node`: `optional` when an enclosing alternation or
        // repetition lets a match skip it, `repeated` when an enclosing repetition can run it
        // twice, and `stale` when it lies inside a group loop whose captures can be stale.
        private static void addUncompared(Node node, boolean optional, boolean repeated, boolean stale, int[] groups, Set<Integer> uncompared)
        {
            switch (node) {
                case Group group -> {
                    if (group.capturing()) {
                        groups[0]++;
                        if (stale) {
                            uncompared.add(groups[0]);
                        }
                    }
                    addUncompared(group.body(), optional, repeated, stale, groups, uncompared);
                }
                case Concat concat -> {
                    for (Node item : concat.items()) {
                        addUncompared(item, optional, repeated, stale, groups, uncompared);
                    }
                }
                case Alternation alternation -> {
                    for (Node alternative : alternation.alternatives()) {
                        addUncompared(alternative, true, repeated, stale, groups, uncompared);
                    }
                }
                case Repeat repeat -> {
                    boolean innerRepeated = repeated || repeat.maximum() >= 2;
                    // `?` and `{0,1}` on a group run as an alternation.
                    if (repeat.body() instanceof Group group && !(repeat.min() == 0 && repeat.max() == 1) && deterministic(group.body())) {
                        if (group.capturing()) {
                            groups[0]++;
                            boolean backsOff = repeat.greedy() && repeat.min() < repeat.maximum() && repeated;
                            if (stale || backsOff) {
                                uncompared.add(groups[0]);
                            }
                        }
                        boolean innerStale = stale || (repeat.max() != 0 && (repeat.min() != repeat.maximum() || optional));
                        addUncompared(group.body(), optional || repeat.min() != repeat.maximum(), innerRepeated, innerStale, groups, uncompared);
                    }
                    else {
                        addUncompared(repeat.body(), optional || repeat.min() != repeat.maximum(), innerRepeated, stale, groups, uncompared);
                    }
                }
                default -> {}
            }
        }

        // Whether the JDK reads `node` as deterministic, with no alternation, optional part, or
        // variable count, which makes a repetition of a group around it a group loop.
        private static boolean deterministic(Node node)
        {
            return switch (node) {
                case Group group -> deterministic(group.body());
                case Concat concat -> concat.items().stream().allMatch(CaptureShapes::deterministic);
                case Alternation _ -> false;
                case Repeat repeat -> repeat.min() == repeat.max() && deterministic(repeat.body());
                default -> true;
            };
        }

        private Node alternation()
        {
            List<Node> alternatives = new ArrayList<>();
            alternatives.add(concat());
            while (position < pattern.length() && pattern.charAt(position) == '|') {
                position++;
                alternatives.add(concat());
            }
            return alternatives.size() == 1 ? alternatives.getFirst() : new Alternation(alternatives);
        }

        private Node concat()
        {
            List<Node> items = new ArrayList<>();
            while (true) {
                skipIgnored();
                if (position == pattern.length() || pattern.charAt(position) == '|' || pattern.charAt(position) == ')') {
                    return new Concat(items);
                }
                Node atom = atom();
                if (atom != null) {
                    items.add(quantified(atom));
                }
            }
        }

        // An atom, or null for isolated flags, which apply to the rest of the enclosing group.
        private Node atom()
        {
            switch (pattern.charAt(position)) {
                case '(' -> {
                    return group();
                }
                case '[' -> characterClass();
                case '\\' -> escape();
                default -> position += Character.charCount(pattern.codePointAt(position));
            }
            return ATOM;
        }

        private Node group()
        {
            boolean enclosingExtended = extended;
            boolean enclosingUnixLines = unixLines;
            position++;
            skipIgnored();
            Node group;
            if (pattern.startsWith("?<", position)) {
                int close = pattern.indexOf('>', position);
                String name = pattern.substring(position + 2, close);
                position = close + 1;
                group = new Group(true, name, groupBody());
            }
            else if (pattern.startsWith("?", position)) {
                position++;
                boolean enabled = true;
                // The JDK reads the flags with comments mode as each flag leaves it.
                while (pattern.charAt(position) != ':' && pattern.charAt(position) != ')') {
                    char flag = pattern.charAt(position++);
                    if (flag == '-') {
                        enabled = false;
                    }
                    else if (flag == 'x') {
                        extended = enabled;
                    }
                    else if (flag == 'd') {
                        unixLines = enabled;
                    }
                    skipIgnored();
                }
                if (pattern.charAt(position++) == ')') {
                    return null;
                }
                group = new Group(false, null, groupBody());
            }
            else {
                group = new Group(true, null, groupBody());
            }
            extended = enclosingExtended;
            unixLines = enclosingUnixLines;
            return group;
        }

        private Node groupBody()
        {
            Node body = alternation();
            position++;
            return body;
        }

        private Node quantified(Node atom)
        {
            while (true) {
                skipIgnored();
                if (position == pattern.length()) {
                    return atom;
                }
                int min;
                int max;
                switch (pattern.charAt(position)) {
                    case '*' -> {
                        min = 0;
                        max = -1;
                    }
                    case '+' -> {
                        min = 1;
                        max = -1;
                    }
                    case '?' -> {
                        min = 0;
                        max = 1;
                    }
                    case '{' -> {
                        // The JDK reads the first digit as written and the rest of the count
                        // past whitespace and comments.
                        position++;
                        min = number();
                        max = min;
                        if (pattern.charAt(position) == ',') {
                            position++;
                            skipIgnored();
                            max = pattern.charAt(position) == '}' ? -1 : number();
                        }
                    }
                    default -> {
                        return atom;
                    }
                }
                position++;
                skipIgnored();
                boolean greedy = position == pattern.length() || pattern.charAt(position) != '?';
                if (!greedy) {
                    position++;
                }
                atom = new Repeat(atom, min, max, greedy, 0);
            }
        }

        // Reads the digits of a count, and what comments mode ignores after each.
        private int number()
        {
            int number = 0;
            while (pattern.charAt(position) >= '0' && pattern.charAt(position) <= '9') {
                number = number * 10 + pattern.charAt(position++) - '0';
                skipIgnored();
            }
            return number;
        }

        private void characterClass()
        {
            int depth = 0;
            while (position < pattern.length()) {
                char character = pattern.charAt(position);
                if (character == '\\') {
                    escape();
                    continue;
                }
                if (extended && character == '#') {
                    skipComment();
                    continue;
                }
                position++;
                if (character == '[') {
                    depth++;
                }
                else if (character == ']' && --depth == 0) {
                    return;
                }
            }
        }

        private void escape()
        {
            position++;
            if (pattern.startsWith("Q", position)) {
                int end = pattern.indexOf("\\E", position);
                position = end < 0 ? pattern.length() : end + 2;
                return;
            }
            char letter = pattern.charAt(position);
            position += Character.charCount(pattern.codePointAt(position));
            if (letter == 'c') {
                position += Character.charCount(pattern.codePointAt(position));
            }
            else if ("xNpP".indexOf(letter) >= 0 && pattern.startsWith("{", position)) {
                position = pattern.indexOf('}', position) + 1;
            }
        }

        // Comments mode skips ASCII whitespace and comments.
        private void skipIgnored()
        {
            while (extended && position < pattern.length()) {
                char character = pattern.charAt(position);
                if (character == '#') {
                    skipComment();
                }
                else if (character == ' ' || (character >= '\t' && character <= '\r')) {
                    position++;
                }
                else {
                    return;
                }
            }
        }

        // A comment ends before a line terminator, only \n in UNIX_LINES mode, or a NUL.
        private void skipComment()
        {
            while (position < pattern.length()) {
                char character = pattern.charAt(position);
                if (character == '\n' || character == 0 || (!unixLines && (character == '\r' || character == '\u0085' || character == '\u2028' || character == '\u2029'))) {
                    return;
                }
                position++;
            }
        }

        // The JDK drops an empty \Q\E before parsing, so a quantifier after it applies to the
        // atom before it.
        private static String withoutEmptyQuotes(String pattern)
        {
            StringBuilder result = new StringBuilder();
            int position = 0;
            while (position < pattern.length()) {
                if (pattern.startsWith("\\Q\\E", position)) {
                    position += 4;
                }
                else if (pattern.startsWith("\\Q", position)) {
                    int end = pattern.indexOf("\\E", position + 2);
                    int next = end < 0 ? pattern.length() : end + 2;
                    result.append(pattern, position, next);
                    position = next;
                }
                else {
                    int next = Math.min(pattern.length(), position + (pattern.charAt(position) == '\\' ? 2 : 1));
                    result.append(pattern, position, next);
                    position = next;
                }
            }
            return result.toString();
        }
    }

    /// The JDK as the oracle. The JDK searches UTF-16 text, so positions are converted to UTF-8
    /// byte offsets, and after an empty match before a supplementary character the next search
    /// starts after that character, as Regulator's code-point advance does, instead of between
    /// its surrogates.
    private static final class JdkOracle
            implements Oracle<JdkPattern>
    {
        private static final JdkOracle INSTANCE = new JdkOracle();

        @Override
        public JdkPattern compile(String pattern, int options)
        {
            return new JdkPattern(Pattern.compile(pattern, options), CaptureShapes.uncomparedGroups(pattern, options));
        }

        @Override
        public void operations(JdkPattern compiled, CaseInput input, Trace trace, long deadlineNanos)
        {
            Pattern pattern = compiled.pattern();
            Set<Integer> uncompared = compiled.uncomparedGroups();
            Text text = new Text(input, deadlineNanos);
            int groupCount = pattern.matcher("").groupCount();
            Map<String, Integer> names = new TreeMap<>(pattern.namedGroups());
            int regionStart = text.index(input.regionStart());
            int regionEnd = text.index(input.regionEnd());
            int limit = input.bytes().length + 2;
            trace.add("groups", () -> groupCount + " " + names);
            List<int[]> all = findAll(pattern, text, 0, text.length(), false, limit);
            trace.add("find all", () -> compared(format(all, names, uncompared, input, groupCount, limit)));
            trace.add("count", () -> (long) all.size());
            String first = all.isEmpty() ? "none" : spans(all.getFirst(), uncompared);
            trace.add("find", () -> !all.isEmpty());
            Matcher matcher = pattern.matcher(text);
            boolean lookingAt = matcher.lookingAt();
            String lookingAtSpans = current(lookingAt, matcher, text, uncompared);
            boolean matches = matcher.matches();
            String matchesSpans = current(matches, matcher, text, uncompared);
            trace.add("lookingAt", () -> lookingAt);
            trace.add("matches", () -> matches);
            trace.add("findResult", () -> compared(first));
            trace.add("lookingAtResult", () -> compared(lookingAtSpans));
            trace.add("matchesResult", () -> compared(matchesSpans));
            trace.add("findInto", () -> compared(first));
            trace.add("findInto group zero", () -> compared(all.isEmpty() ? "none" : spans(new int[] {all.getFirst()[0], all.getFirst()[1]}, uncompared)));
            trace.add("lookingAtInto", () -> compared(lookingAtSpans));
            trace.add("matchesInto", () -> compared(matchesSpans));

            Matcher transparent = pattern.matcher(text).useTransparentBounds(true).useAnchoringBounds(false);
            boolean rangeFind = transparent.region(regionStart, regionEnd).find();
            String rangeFindSpans = current(rangeFind, transparent, text, uncompared);
            boolean rangeLookingAt = transparent.region(regionStart, regionEnd).lookingAt();
            String rangeLookingAtSpans = current(rangeLookingAt, transparent, text, uncompared);
            boolean rangeMatches = transparent.region(regionStart, regionEnd).matches();
            String rangeMatchesSpans = current(rangeMatches, transparent, text, uncompared);
            trace.add("find range", () -> rangeFind);
            trace.add("lookingAt range", () -> rangeLookingAt);
            trace.add("matches range", () -> rangeMatches);
            trace.add("findResult range", () -> compared(rangeFindSpans));
            trace.add("lookingAtResult range", () -> compared(rangeLookingAtSpans));
            trace.add("matchesResult range", () -> compared(rangeMatchesSpans));
            trace.add("findInto range", () -> compared(rangeFindSpans));
            trace.add("lookingAtInto range", () -> compared(rangeLookingAtSpans));
            trace.add("matchesInto range", () -> compared(rangeMatchesSpans));

            List<int[]> region = findAll(pattern, text, regionStart, regionEnd, true, limit);
            trace.add("region find all", () -> compared(format(region, names, uncompared, input, groupCount, limit)));
            Matcher bounded = pattern.matcher(text);
            boolean regionLookingAt = bounded.region(regionStart, regionEnd).lookingAt();
            trace.add("region lookingAt", () -> compared(current(regionLookingAt, bounded, text, uncompared)));
            // After a failed match, the JDK can resume elsewhere, a recorded difference
            // (JAVA_REGEXP.md, Matching), so only the variants compare the next find.
            if (regionLookingAt && resumable(bounded, text)) {
                boolean next = bounded.find();
                trace.add("region lookingAt then find", () -> compared(current(next, bounded, text, uncompared)));
            }
            boolean regionMatches = bounded.region(regionStart, regionEnd).matches();
            trace.add("region matches", () -> compared(current(regionMatches, bounded, text, uncompared)));
            if (regionMatches && resumable(bounded, text)) {
                boolean next = bounded.find();
                trace.add("region matches then find", () -> compared(current(next, bounded, text, uncompared)));
            }
            boolean from = bounded.find(text.index(input.searchStart()));
            trace.add("find from", () -> compared(current(from, bounded, text, uncompared)));
            if (from) {
                boolean next = resumable(bounded, text) ? bounded.find() : bounded.find(bounded.end() + 2);
                trace.add("find from then find", () -> compared(current(next, bounded, text, uncompared)));
            }
            trace.add("group zero find all", () -> compared(format(all, Map.of(), uncompared, input, 0, limit)));
            if (groupCount >= 2) {
                int retained = partialGroups(input, groupCount);
                trace.add("partial find all", () -> compared(format(all, Map.of(), uncompared, input, retained, limit)));
            }
        }

        // Whether find() after the current match resumes where Regulator does: not after an
        // empty match before a supplementary character, where the JDK would search between its
        // surrogates.
        private static boolean resumable(Matcher matcher, Text text)
        {
            return matcher.start() != matcher.end() || !text.beforeSupplementary(matcher.end());
        }

        private static List<int[]> findAll(Pattern pattern, Text text, int start, int end, boolean region, int limit)
        {
            Matcher matcher = pattern.matcher(text);
            if (region) {
                matcher.region(start, end);
            }
            int offset = 0;
            List<int[]> matches = new ArrayList<>();
            boolean found = matcher.find();
            while (found && matches.size() <= limit) {
                matches.add(byteOffsets(matcher, text, offset));
                int matchEnd = matcher.end() + offset;
                if (matcher.start() == matcher.end() && matchEnd < end && text.beforeSupplementary(matchEnd)) {
                    if (region && offset == 0) {
                        // find(int) forgets the region, so the rest of the search runs on the
                        // region's text alone, which a region with default bounds reads the same
                        // way past its first character.
                        offset = start;
                        matcher = pattern.matcher(text.subSequence(start, end));
                    }
                    found = matcher.find(matchEnd + 2 - offset);
                }
                else {
                    found = matcher.find();
                }
            }
            return matches;
        }

        private static String format(List<int[]> matches, Map<String, Integer> names, Set<Integer> uncompared, CaseInput input, int groups, int limit)
        {
            List<String> formatted = new ArrayList<>();
            for (int[] match : matches) {
                if (formatted.size() == limit) {
                    formatted.add("more than " + limit);
                    break;
                }
                StringBuilder value = new StringBuilder(spans(Arrays.copyOf(match, 2 * (groups + 1)), uncompared));
                appendNames(value, names, uncompared, group -> hex(match[2 * group] < 0 ? null : input.source().slice(match[2 * group], match[2 * group + 1] - match[2 * group])));
                formatted.add(value.toString());
            }
            return formatted.toString();
        }

        private static String current(boolean matched, Matcher matcher, Text text, Set<Integer> uncompared)
        {
            return matched ? spans(byteOffsets(matcher, text, 0), uncompared) : "none";
        }

        // The byte offsets of each group of the JDK's match, whose indexes count from character
        // `shift` of the text.
        private static int[] byteOffsets(Matcher matcher, Text text, int shift)
        {
            int[] groups = new int[2 * (matcher.groupCount() + 1)];
            for (int group = 0; group <= matcher.groupCount(); group++) {
                groups[2 * group] = matcher.start(group) < 0 ? -1 : text.offset(matcher.start(group) + shift);
                groups[2 * group + 1] = matcher.end(group) < 0 ? -1 : text.offset(matcher.end(group) + shift);
            }
            return groups;
        }
    }

    /// The input as UTF-16 text for the JDK, which throws [OracleSkipException] once a search
    /// reads it past the deadline or more than [#READ_BUDGET] times. The budget usually stops a
    /// runaway backtrack before the deadline, so a bounded run skips the same cases on most
    /// machines, though a slow one can reach the deadline first.
    private static final class Text
            implements CharSequence
    {
        private static final int READ_BUDGET = 1 << 24;

        private final String text;
        private final int start;
        private final int length;
        private final long deadlineNanos;
        private final int[] offsets;
        private final int[] indexes;
        private int reads;

        private Text(CaseInput input, long deadlineNanos)
        {
            this.text = input.text();
            this.start = 0;
            this.length = text.length();
            this.deadlineNanos = deadlineNanos;
            this.offsets = new int[length + 1];
            this.indexes = new int[input.bytes().length + 1];
            int offset = 0;
            for (int index = 0; index < length; index++) {
                offsets[index] = offset;
                indexes[offset] = index;
                char character = text.charAt(index);
                if (Character.isHighSurrogate(character)) {
                    offsets[index + 1] = offset;
                    offset += 4;
                    index++;
                }
                else {
                    offset += character < 0x80 ? 1 : character < 0x800 ? 2 : 3;
                }
            }
            offsets[length] = offset;
            indexes[offset] = length;
        }

        private Text(Text parent, int start, int end)
        {
            this.text = parent.text;
            this.start = parent.start + start;
            this.length = end - start;
            this.deadlineNanos = parent.deadlineNanos;
            this.offsets = parent.offsets;
            this.indexes = parent.indexes;
        }

        // The UTF-16 index of a byte offset at a character boundary.
        int index(int offset)
        {
            return indexes[offset];
        }

        // The byte offset of a UTF-16 index. Positions are byte offsets (JAVA_REGEXP.md), so a
        // case is skipped when the JDK, which tries every UTF-16 index as a start for a pattern
        // without supplementary characters, reports a position inside a surrogate pair, as `\B`
        // does in Unicode-character-class mode.
        int offset(int index)
        {
            if (index > 0 && index < text.length() && Character.isLowSurrogate(text.charAt(index)) && Character.isHighSurrogate(text.charAt(index - 1))) {
                throw new OracleSkipException("JDK position inside a surrogate pair");
            }
            return offsets[index];
        }

        boolean beforeSupplementary(int index)
        {
            return index + 1 < text.length() && Character.isHighSurrogate(text.charAt(index)) && Character.isLowSurrogate(text.charAt(index + 1));
        }

        @Override
        public int length()
        {
            return length;
        }

        @Override
        public char charAt(int index)
        {
            if ((++reads & 0x3FF) == 0) {
                if (reads > READ_BUDGET) {
                    throw new OracleSkipException("oracle read budget");
                }
                if (System.nanoTime() - deadlineNanos > 0) {
                    throw new OracleSkipException("oracle timeout");
                }
            }
            return text.charAt(start + index);
        }

        @Override
        public CharSequence subSequence(int start, int end)
        {
            return new Text(this, start, end);
        }

        @Override
        public String toString()
        {
            return text.substring(start, start + length);
        }
    }

    // The closing folds the JDK's case-insensitive ranges use, as CaseFolding selects them.
    private static CodePointMappings closingFolds()
    {
        CodePointMappings folds = UnicodeDatabase.simpleCaseFolding();
        CodePointMappings uppercase = UnicodeDatabase.simpleUppercaseMappings();
        CodePointMappings lowercase = UnicodeDatabase.simpleLowercaseMappings();
        List<int[]> closing = new ArrayList<>();
        for (int index = 0; index < folds.codePoints().length; index++) {
            int source = folds.codePoints()[index];
            int target = folds.values()[index];
            if (uppercase.map(target) != source && lowercase.map(target) != source) {
                closing.add(new int[] {source, target});
            }
        }
        closing.add(new int[] {0x0130, 'i'});
        closing.add(new int[] {0x0131, 'I'});
        closing.sort(comparingInt(pair -> pair[0]));
        return new CodePointMappings(closing.stream().mapToInt(pair -> pair[0]).toArray(), closing.stream().mapToInt(pair -> pair[1]).toArray());
    }

    /// Writes a tree in Java syntax, or returns `null` when the tree is outside the dialect.
    private static final class JavaWriter
    {
        private static final String METACHARACTERS = "\\^$.|?*+()[]{}";
        private static final String CLASS_METACHARACTERS = "\\^-&[]";

        private final StringBuilder out = new StringBuilder();
        // JDK 25's ranges lack the closing characters of Unicode case folding that JDK 26 and
        // Regulator match (JAVA_REGEXP.md), so where the pattern may fold case by Unicode, no
        // range holds the source of a closing fold whose target lies outside it.
        private final boolean unicodeFolding;

        private JavaWriter(boolean unicodeFolding)
        {
            this.unicodeFolding = unicodeFolding;
        }

        static String write(Node tree, int options)
        {
            boolean caseInsensitive = (options & CASE_INSENSITIVE) != 0 || TestingPatternTree.contains(tree, element -> element instanceof FlagGroup group && group.enabled().indexOf('i') >= 0);
            boolean unicodeCase = (options & (UNICODE_CASE | UNICODE_CHARACTER_CLASS)) != 0 ||
                    TestingPatternTree.contains(tree, element -> element instanceof FlagGroup group && (group.enabled().indexOf('u') >= 0 || group.enabled().indexOf('U') >= 0));
            JavaWriter writer = new JavaWriter(caseInsensitive && unicodeCase);
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
                case Quote quote -> out.append("\\Q").append(quote.text()).append("\\E");
                case AnyCharacter _ -> out.append('.');
                case PerlClass perlClass -> out.append('\\').append(perlClass.letter());
                case Property property -> out.append(property(property, false));
                case CharacterClass characterClass -> characterClass(characterClass, extended);
                case Anchor anchor -> out.append(anchor.text());
                case Comment comment -> {
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
                        out.append("(?<").append(group.name()).append('>');
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
                    if (!flags.isEmpty() && group.isolated() && endsGroup && !(group.body() instanceof Alternation)) {
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
                        boolean last = index == items.size() - 1;
                        if (item instanceof Alternation && items.size() > 1) {
                            out.append("(?:");
                            node(item, extended, true);
                            out.append(')');
                        }
                        else {
                            node(item, extended, endsGroup && last);
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
                case Repeat repeat -> repeat(repeat, extended);
            }
        }

        private void repeat(Repeat repeat, boolean extended)
        {
            if (!allowsRepeat(repeat.body(), repeat.min(), repeat.max(), repeat.greedy())) {
                throw new OutsideDialect();
            }
            Node body = repeat.body();
            // A count right after another repetition repeats the empty match in Java
            // (JAVA_REGEXP.md), and `+` or `?` after one makes it possessive or lazy, so a
            // repeated repetition is grouped.
            boolean atom = body instanceof Literal || body instanceof Quote || body instanceof AnyCharacter || body instanceof PerlClass ||
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

        private void characterClass(CharacterClass characterClass, boolean extended)
        {
            out.append('[');
            if (characterClass.negated()) {
                out.append('^');
            }
            List<List<ClassItem>> operands = characterClass.operands();
            for (int operandIndex = 0; operandIndex < operands.size(); operandIndex++) {
                if (operandIndex > 0) {
                    out.append("&&");
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
                            if (unicodeFolding && hasClosingFold(range.low(), range.high())) {
                                throw new OutsideDialect();
                            }
                            out.append(literal(range.low(), range.lowStyle(), extended, true)).append('-').append(literal(range.high(), range.highStyle(), extended, true));
                        }
                        case PerlClass perlClass -> out.append('\\').append(perlClass.letter());
                        case Property property -> out.append(property(property, true));
                        case CharacterClass nested -> characterClass(nested, extended);
                        case PosixClass _ -> throw new OutsideDialect();
                    }
                }
            }
            out.append(']');
        }

        private static boolean hasClosingFold(int low, int high)
        {
            int[] sources = CLOSING_FOLDS.codePoints();
            int[] targets = CLOSING_FOLDS.values();
            for (int index = 0; index < sources.length; index++) {
                if (low <= sources[index] && sources[index] <= high && (targets[index] < low || targets[index] > high)) {
                    return true;
                }
            }
            return false;
        }

        private static String property(Property property, boolean inClass)
        {
            String sign = property.negated() ? "P" : "p";
            if (property.braceless() && !inClass) {
                return "\\" + sign + property.name();
            }
            return "\\" + sign + "{" + property.name() + "}";
        }

        /// Writes a character in the escape `style` names where that escape can spell it, and
        /// otherwise plainly, with a backslash where Java would read it as syntax.
        private static String literal(int codePoint, int style, boolean extended, boolean inClass)
        {
            boolean whitespace = codePoint == ' ' || (codePoint >= '\t' && codePoint <= '\r');
            switch (style) {
                case 1 -> {
                    if (codePoint <= 0xFF) {
                        return "\\x" + HexFormat.of().toHexDigits((byte) codePoint);
                    }
                }
                case 2 -> {
                    if (codePoint <= 0xFFFF) {
                        return "\\u" + HexFormat.of().toHexDigits((short) codePoint);
                    }
                }
                case 3 -> {
                    return "\\x{" + Integer.toHexString(codePoint) + "}";
                }
                case 4 -> {
                    if (codePoint <= 0377) {
                        return String.format(Locale.ROOT, "\\0%03o", codePoint);
                    }
                }
                case 5 -> {
                    // \cX is X ^ 64. The operand is a printable ASCII character other than a
                    // backslash, which would start an escape, and #, which starts a comment.
                    int operand = codePoint ^ 64;
                    if (codePoint < 0x80 && operand > ' ' && operand < 0x7F && operand != '\\' && operand != '#') {
                        return "\\c" + (char) operand;
                    }
                }
                case 6 -> {
                    String name = Character.getName(codePoint);
                    if (name != null && Character.codePointOf(name) == codePoint) {
                        return "\\N{" + name + "}";
                    }
                }
                case 7 -> {
                    // A backslash escapes any character other than an ASCII letter or digit.
                    boolean asciiAlphanumeric = (codePoint >= '0' && codePoint <= '9') || (codePoint >= 'a' && codePoint <= 'z') || (codePoint >= 'A' && codePoint <= 'Z');
                    if (!asciiAlphanumeric && !whitespace) {
                        return "\\" + Character.toString(codePoint);
                    }
                }
                default -> {}
            }
            if (extended && (whitespace || codePoint == '#')) {
                // Comments mode skips ASCII whitespace and comments, inside classes too.
                return switch (codePoint) {
                    case '\t' -> "\\t";
                    case '\n' -> "\\n";
                    case '\r' -> "\\r";
                    case '\f' -> "\\f";
                    case 0x0B -> "\\x0B";
                    default -> "\\" + (char) codePoint;
                };
            }
            if ((inClass ? CLASS_METACHARACTERS : METACHARACTERS).indexOf(codePoint) >= 0) {
                return "\\" + (char) codePoint;
            }
            return Character.toString(codePoint);
        }
    }
}
