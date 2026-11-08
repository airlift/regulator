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

import io.airlift.jcodings.Config;
import io.airlift.jcodings.specific.NonStrictUTF8Encoding;
import io.airlift.joni.Matcher;
import io.airlift.joni.NameEntry;
import io.airlift.joni.Option;
import io.airlift.joni.Regex;
import io.airlift.joni.Region;
import io.airlift.regulator.TestingDifferentialFuzzer.CaseInput;
import io.airlift.regulator.TestingDifferentialFuzzer.Generated;
import io.airlift.regulator.TestingDifferentialFuzzer.Language;
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
import io.airlift.regulator.TestingTrinoJoni.SplitAndReplace;
import io.airlift.regulator.UnicodeDatabase.CodePointMappings;
import io.airlift.regulator.UnicodeFullCaseFold.FoldToken;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Stream;

import static io.airlift.regulator.TestingDifferentialFuzzer.hex;
import static io.airlift.regulator.TestingDifferentialFuzzer.routeClass;
import static io.airlift.regulator.TestingDifferentialFuzzer.spans;
import static io.airlift.regulator.TestingPatternGenerator.TEXT_CHARACTERS;
import static io.airlift.regulator.TestingPatternTree.quantifier;
import static io.airlift.regulator.TestingTrinoJoni.ASCII_SYMBOLS;
import static io.airlift.regulator.TestingTrinoJoni.FULL_FOLD_CHARACTERS;
import static io.airlift.regulator.TestingTrinoJoni.LITERAL_ESCAPE_LETTERS;
import static io.airlift.regulator.TestingTrinoJoni.NON_ASCII_CONTROL_OPERANDS;
import static io.airlift.regulator.TestingTrinoJoni.POSIX_BRACKET_NAMES;
import static io.airlift.regulator.TestingTrinoJoni.PROPERTY_NAMES;
import static io.airlift.regulator.TestingTrinoJoni.SHORT_PROPERTY_NAMES;
import static io.airlift.regulator.TestingTrinoJoni.group;
import static io.airlift.regulator.TestingTrinoJoni.joniPattern;
import static io.airlift.regulator.TestingTrinoJoni.splitAndReplace;
import static io.airlift.slice.SliceUtf8.countCodePoints;
import static io.airlift.slice.SliceUtf8.lengthOfCodePointFromStartByte;
import static io.airlift.slice.SliceUtf8.offsetOfCodePoint;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/// Differential fuzzing of [TrinoRegexp] against Trino's pinned Joni, `io.airlift:joni:2.1.5.3`;
/// see [TestingDifferentialFuzzer]. The dialect is the grammar of [TestTrinoRegexpDifferential],
/// leaving out the same recorded differences, each cited where it is left out. Options are
/// always zero: Trino's compile options set a memory budget, byte frequencies, and bytecode
/// generation, none of which changes a result, and the variants cover bytecode.
public class TestTrinoRegexpFuzz
{
    static final Language<TrinoRegexp, Regex> LANGUAGE = new TrinoLanguage();

    private static final long SEED = 0x54_52_49_4E_4F_46_55_5AL;
    private static final int PATTERNS = 600;

    private static final Slice GROUP_ZERO_REPLACEMENT = utf8Slice("$0");
    private static final Slice FIRST_GROUP_REPLACEMENT = utf8Slice("$1");
    // Each position(source, start, occurrence) call searches again from the start, so the
    // operations ask only for the first few occurrences.
    private static final int POSITION_OCCURRENCES = 3;

    // A pattern that may ignore case has no full-fold characters, in it or its inputs.
    private static final Vocabulary IGNORING_CASE = vocabulary(true);
    private static final Vocabulary KEEPING_CASE = vocabulary(false);

    // The characters that pinned Joni's Unicode 4.1 fold data and Regulator's Unicode 18.0
    // folding tell apart on the inputs of a case-insensitive pattern, a recorded difference
    // (REGULATOR_DECISIONS.md, Trino Text-Dependent Assertions And Full Case Folding): capital
    // sharp s and U+1DF95, which fold to ss only in Regulator. A case-insensitive pattern holds
    // none of them in a literal or a class; see TrinoWriter.
    private static final Set<Integer> FOLD_DIFFERENCES = foldDifferences(IGNORING_CASE.characters());

    @Test
    public void testFuzz()
    {
        TestingDifferentialFuzzer.fuzz(LANGUAGE, SEED, PATTERNS);
    }

    private static Vocabulary vocabulary(boolean mayIgnoreCase)
    {
        List<String> characters = Stream.of(TEXT_CHARACTERS, ASCII_SYMBOLS, LITERAL_ESCAPE_LETTERS, mayIgnoreCase ? List.<String>of() : FULL_FOLD_CHARACTERS)
                .flatMap(List::stream)
                .toList();
        return new Vocabulary(
                characters,
                PROPERTY_NAMES,
                SHORT_PROPERTY_NAMES,
                POSIX_BRACKET_NAMES,
                "dDsSwW",
                List.of("^", "$", "\\A", "\\z", "\\b", "\\B"),
                // The flags d, u, and U are unsupported (TRINO_REGEXP.md, Unsupported features).
                mayIgnoreCase ? "imsx" : "msx",
                List.of("é", "group_", "n"),
                true,
                "&",
                12,
                9,
                TestTrinoRegexpFuzz::allowsRepeat);
    }

    // Whether the dialect may repeat `body` within the bounds: TestTrinoRegexpDifferential leaves
    // out the same repetitions, those that the body and the bounds decide without the text
    // around them.
    private static boolean allowsRepeat(Node body, int min, int max, boolean greedy)
    {
        boolean nullable = TestingPatternTree.nullable(body);
        boolean unbounded = max < 0;
        int maximum = unbounded ? Integer.MAX_VALUE : max;
        // Joni keeps capture histories in a loop whose body can match empty, a recorded
        // difference (REGULATOR_DECISIONS.md, Trino Compatibility Adapter, and
        // UNSUPPORTED_FEATURES.md), so a capture never sits in an unbounded repetition of a
        // nullable body.
        if (nullable && unbounded && TestingPatternTree.hasCapturingGroup(body)) {
            return false;
        }
        // Pinned Joni's results for a body that can match empty, repeated with a count of two or
        // more, depend on how it compiles the repeat, and it fails on an empty group repeated
        // exactly 2 through 100 times, recorded differences (TRINO_REGEXP.md, Other matching
        // differences from Joni).
        if (nullable && maximum >= 2 && (min >= 2 || !unbounded)) {
            return false;
        }
        // Pinned Joni runs the body of a reluctant {0}? when what follows fails, a recorded
        // difference (TRINO_REGEXP.md, Other matching differences from Joni).
        if (!greedy && maximum == 0) {
            return false;
        }
        // Pinned Joni reduces (?:x+?)+ to x+? and (?:x+?)* to (?:x+?)?, as modern Joni does not,
        // a recorded difference (TRINO_REGEXP.md, Other matching differences from Joni).
        return !(shape(body) == Shape.RELUCTANT_PLUS && greedy && unbounded && min <= 1);
    }

    // Returns the characters that pinned Joni and Regulator fold differently where the input
    // characters can tell: a character whose simple fold partners, in one engine and not the
    // other, include an input character or one of its partners, or whose multi-character fold
    // differs where the input characters can spell either fold.
    private static Set<Integer> foldDifferences(List<String> characters)
    {
        Map<Integer, Set<Integer>> joniPartners = new HashMap<>();
        Map<Integer, List<Integer>> joniStrings = new HashMap<>();
        NonStrictUTF8Encoding.INSTANCE.applyAllCaseFold(Config.ENC_CASE_FOLD_DEFAULT, (from, to, length, _) -> {
            if (length == 1) {
                Set<Integer> partners = joniPartners.computeIfAbsent(from, codePoint -> new HashSet<>(Set.of(codePoint)));
                Set<Integer> others = joniPartners.computeIfAbsent(to[0], codePoint -> new HashSet<>(Set.of(codePoint)));
                if (partners != others) {
                    partners.addAll(others);
                    for (int other : others) {
                        joniPartners.put(other, partners);
                    }
                }
            }
            else {
                joniStrings.put(from, Arrays.stream(to, 0, length).boxed().toList());
            }
        }, null);

        Set<Integer> candidates = new HashSet<>(joniPartners.keySet());
        candidates.addAll(joniStrings.keySet());
        CodePointMappings folds = UnicodeDatabase.simpleCaseFolding();
        for (int codePoint : folds.codePoints()) {
            candidates.add(codePoint);
        }
        for (int codePoint : folds.values()) {
            candidates.add(codePoint);
        }
        for (FoldToken token : CaseFolding.TRINO.multiCharacterTokens()) {
            candidates.addAll(members(token.sourceRunes()));
        }

        Set<Integer> inputs = new HashSet<>();
        for (String character : characters) {
            int codePoint = character.codePointAt(0);
            inputs.addAll(joniPartners.getOrDefault(codePoint, Set.of(codePoint)));
            inputs.addAll(members(CaseFolding.TRINO.simpleFoldClass(codePoint)));
            inputs.add(codePoint);
        }

        Set<Integer> differences = new HashSet<>();
        for (int candidate : candidates) {
            Set<Integer> joni = joniPartners.getOrDefault(candidate, Set.of(candidate));
            Set<Integer> regulator = new HashSet<>(members(CaseFolding.TRINO.simpleFoldClass(candidate)));
            regulator.add(candidate);
            boolean partnersDiffer = inputs.stream().anyMatch(input -> joni.contains(input) != regulator.contains(input));
            int[] folded = CaseFolding.TRINO.fold(new int[] {candidate});
            List<Integer> regulatorString = folded.length > 1 ? Arrays.stream(folded).boxed().toList() : null;
            List<Integer> joniString = joniStrings.get(candidate);
            boolean stringsDiffer = !Objects.equals(joniString, regulatorString) && Stream.of(joniString, regulatorString).filter(Objects::nonNull).anyMatch(inputs::containsAll);
            if (partnersDiffer || stringsDiffer) {
                differences.add(candidate);
            }
        }
        return Set.copyOf(differences);
    }

    private static List<Integer> members(CharClass characterClass)
    {
        List<Integer> members = new ArrayList<>();
        for (RuneRange range : characterClass.ranges()) {
            for (int codePoint = range.low(); codePoint <= range.high(); codePoint++) {
                members.add(codePoint);
            }
        }
        return members;
    }

    private static boolean mayIgnoreCase(Node tree)
    {
        return TestingPatternTree.contains(tree, element -> element instanceof FlagGroup group && group.enabled().indexOf('i') >= 0);
    }

    private static final class TrinoLanguage
            implements Language<TrinoRegexp, Regex>
    {
        @Override
        public String name()
        {
            return "trino";
        }

        @Override
        public String replayName()
        {
            return "TestTrinoRegexpFuzz.LANGUAGE";
        }

        @Override
        public Generated generate(Random random)
        {
            if (random.nextInt(8) == 0) {
                // Random trees rarely reach the inner literal search's class-run proof, so one
                // pattern in eight has its shape.
                return new Generated(TestingPatternGenerator.innerLiteralShape(random), 0);
            }
            return new Generated(TestingPatternGenerator.generate(random, random.nextInt(3) == 0 ? IGNORING_CASE : KEEPING_CASE), 0);
        }

        @Override
        public String render(Node tree, int options)
        {
            return TrinoWriter.write(tree);
        }

        @Override
        public String describeOptions(int options)
        {
            return "0";
        }

        @Override
        public String sample(Node tree, int options, String pattern, Random random)
        {
            return TestingPatternTree.sample(tree, random, false, TEXT_CHARACTERS);
        }

        @Override
        public List<String> inputCharacters(Node tree, int options)
        {
            return (mayIgnoreCase(tree) ? IGNORING_CASE : KEEPING_CASE).characters();
        }

        @Override
        public String filterInput(Node tree, int options, String input)
        {
            boolean ignoresCase = mayIgnoreCase(tree);
            boolean bracketPunct = TestingPatternTree.contains(tree, element -> element instanceof PosixClass posixClass && posixClass.name().equals("punct"));
            boolean nestedNegation = TestingPatternTree.contains(tree, element -> element instanceof CharacterClass characterClass && hasNegatedNestedClass(characterClass));
            boolean endAnchor = TestingPatternTree.contains(tree, element -> element instanceof Anchor anchor && (anchor.text().equals("$") || anchor.text().equals("\\z")));
            StringBuilder kept = new StringBuilder();
            for (int codePoint : input.codePoints().toArray()) {
                String character = Character.toString(codePoint);
                // A case-insensitive pattern keeps full-fold characters out of its inputs
                // (TRINO_REGEXP.md, Other matching differences from Joni, and
                // REGULATOR_DECISIONS.md, Trino Text-Dependent Assertions And Full Case Folding).
                if (ignoresCase && FULL_FOLD_CHARACTERS.contains(character)) {
                    continue;
                }
                // Pinned Joni leaves these symbols out of [[:punct:]] (TRINO_REGEXP.md, Unicode
                // data and case folding).
                if (bracketPunct && ASCII_SYMBOLS.contains(character)) {
                    continue;
                }
                // Pinned Joni can match or reject a non-ASCII character by its first byte in a
                // class that holds a negated class (TRINO_REGEXP.md, Other matching differences
                // from Joni).
                if (nestedNegation && codePoint >= 0x80) {
                    continue;
                }
                kept.appendCodePoint(codePoint);
            }
            // Before $ or \z, pinned Joni can miss the empty match at the end of an input that
            // ends with a multibyte character (TRINO_REGEXP.md, Other matching differences from
            // Joni), so inputs of a nullable pattern that uses either end with an ASCII character.
            boolean nullable = TestingPatternTree.nullable(tree);
            while (endAnchor && nullable && !kept.isEmpty() && kept.codePointBefore(kept.length()) >= 0x80) {
                kept.setLength(kept.offsetByCodePoints(kept.length(), -1));
            }
            return kept.toString();
        }

        @Override
        public TrinoRegexp compile(String pattern, int options, Variant variant)
        {
            TrinoRegexp.Options trinoOptions = TrinoRegexp.Options.defaults().setGenerateBytecode(variant.generateBytecode());
            Slice slice = utf8Slice(pattern);
            return switch (variant.scanPlanMode()) {
                case DEFAULT -> TrinoRegexp.compile(slice, trinoOptions);
                case FORCE_HANDOFF -> TrinoRegexp.compileForcingScanPlanHandoffForTesting(slice, trinoOptions);
                case DISABLED -> TrinoRegexp.compileWithoutScanPlanForTesting(slice, trinoOptions);
            };
        }

        @Override
        public Oracle<Regex> oracle()
        {
            return JoniOracle.INSTANCE;
        }

        @Override
        public void operations(TrinoRegexp regexp, CaseInput input, Trace trace)
        {
            Slice source = input.source();
            int groupCount = regexp.capturingGroupCount();
            int limit = source.length() + 2;
            trace.add("groups", () -> groupCount + " " + new TreeMap<>(regexp.namedCapturingGroups()));
            trace.add("contains", () -> regexp.contains(source));
            trace.add("count", () -> regexp.count(source));
            trace.add("position", () -> positions(regexp, source, 1, POSITION_OCCURRENCES));
            long start = positionStart(input);
            trace.add("position from " + start, () -> positions(regexp, source, start, POSITION_OCCURRENCES));
            trace.add("matcher", () -> matches(regexp.matcher(EMPTY_SLICE).reset(source), groupCount, limit));
            trace.add("group zero matcher", () -> matches(regexp.matcher(EMPTY_SLICE, 0).reset(source), 0, limit));
            if (groupCount >= 2) {
                int retained = partialGroups(input, groupCount);
                trace.add("partial matcher", () -> matches(regexp.matcher(source, retained), retained, limit));
            }
            for (int group = 0; group <= groupCount; group++) {
                int extracted = group;
                trace.add("extract " + group, () -> hex(regexp.extract(source, extracted)));
                trace.add("extractAll " + group, () -> regexp.extractAll(source, extracted).stream().map(TestingDifferentialFuzzer::hex).toList());
            }
            trace.add("split", () -> regexp.split(source).stream().map(TestingDifferentialFuzzer::hex).toList());
            trace.add("replace", () -> hex(regexp.replace(source, groupCount == 0 ? GROUP_ZERO_REPLACEMENT : FIRST_GROUP_REPLACEMENT)));
            trace.add("replace callback", () -> hex(regexp.replace(source, TestScanPlan::callbackReplacement)));
        }

        // Malformed input is garbage in, garbage out (TRINO_REGEXP.md), and a Trino scan plan
        // promises no exact agreement with the ordinary engine on it (SCAN_PLAN.md).
        @Override
        public boolean variantsAgreeOnMalformedInput()
        {
            return false;
        }

        // Unlike RE2's and Rust's, Trino's routes read the scan plan from the RE2 pattern it
        // wraps, and leave out a find that runs on the plan.
        @Override
        public Set<String> coverage(TrinoRegexp compiled)
        {
            Set<String> routes = new HashSet<>();
            routes.add(routeClass(compiled));
            if (compiled.re2() != null && compiled.re2().usesScanPlanForDiagnostics()) {
                routes.add("scan plan");
            }
            if (compiled.usesGeneratedBytecode()) {
                routes.add("generated bytecode");
            }
            return routes;
        }
    }

    private static int partialGroups(CaseInput input, int groupCount)
    {
        return 1 + input.bytes().length % (groupCount - 1);
    }

    // A one-based start at the search start, or past the last code point.
    private static long positionStart(CaseInput input)
    {
        return countCodePoints(input.source(), 0, input.searchStart()) + 1L;
    }

    private static String positions(TrinoRegexp regexp, Slice source, long start, int limit)
    {
        List<Long> positions = new ArrayList<>();
        for (int occurrence = 1; occurrence <= limit; occurrence++) {
            long position = regexp.position(source, start, occurrence);
            positions.add(position);
            if (position < 0) {
                break;
            }
        }
        return positions.toString();
    }

    private static String matches(TrinoRegexpMatcher matcher, int groups, int limit)
    {
        List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            if (matches.size() == limit) {
                matches.add("more than " + limit);
                return matches.toString();
            }
            int[] spans = new int[2 * (groups + 1)];
            StringBuilder values = new StringBuilder();
            for (int group = 0; group <= groups; group++) {
                spans[2 * group] = matcher.start(group);
                spans[2 * group + 1] = matcher.end(group);
                values.append(' ').append(hex(matcher.group(group)));
            }
            matches.add(spans(spans) + values);
        }
        String after;
        try {
            after = "start " + matcher.start();
        }
        catch (IllegalStateException _) {
            after = "no match";
        }
        return matches + " then " + after;
    }

    /// Pinned Joni as the oracle, searching again from the previous match end as Trino's Joni
    /// functions do. A watchdog interrupts a search that runs past its deadline.
    private static final class JoniOracle
            implements Oracle<Regex>
    {
        private static final JoniOracle INSTANCE = new JoniOracle();
        private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "trino-fuzz-watchdog");
            thread.setDaemon(true);
            return thread;
        });

        @Override
        public Regex compile(String pattern, int options)
        {
            return joniPattern(pattern);
        }

        // The watchdog interrupts the searching thread to stop a runaway Joni backtrack.
        @SuppressWarnings("Interruption")
        @Override
        public void operations(Regex regex, CaseInput input, Trace trace, long deadlineNanos)
        {
            Thread searcher = Thread.currentThread();
            Object lock = new Object();
            boolean[] active = {true};
            ScheduledFuture<?> interrupt = WATCHDOG.schedule(() -> {
                synchronized (lock) {
                    if (active[0]) {
                        searcher.interrupt();
                    }
                }
            }, deadlineNanos - System.nanoTime(), NANOSECONDS);
            try {
                record(regex, input, trace);
            }
            finally {
                synchronized (lock) {
                    active[0] = false;
                }
                interrupt.cancel(false);
                // Clears an interrupt that came after the last search.
                Thread.interrupted();
            }
        }

        private static void record(Regex regex, CaseInput input, Trace trace)
        {
            Slice source = input.source();
            int groupCount = regex.numberOfCaptures();
            int limit = source.length() + 2;
            List<int[]> matches = matches(regex, source, 0, limit);
            trace.add("groups", () -> groupCount + " " + namedGroups(regex));
            trace.add("contains", () -> !matches.isEmpty());
            trace.add("count", () -> (long) matches.size());
            trace.add("position", () -> positions(regex, source, 1, POSITION_OCCURRENCES));
            long start = positionStart(input);
            String fromStart = positions(regex, source, start, POSITION_OCCURRENCES);
            trace.add("position from " + start, () -> fromStart);
            trace.add("matcher", () -> format(source, matches, groupCount, limit));
            trace.add("group zero matcher", () -> format(source, matches, 0, limit));
            if (groupCount >= 2) {
                int retained = partialGroups(input, groupCount);
                trace.add("partial matcher", () -> format(source, matches, retained, limit));
            }
            for (int group = 0; group <= groupCount; group++) {
                int extracted = group;
                trace.add("extract " + group, () -> matches.isEmpty() ? "null" : hex(group(source, matches.getFirst(), extracted)));
                trace.add("extractAll " + group, () -> matches.stream().map(match -> hex(group(source, match, extracted))).toList());
            }
            SplitAndReplace expected = splitAndReplace(source, matches, groupCount);
            trace.add("split", () -> expected.parts().stream().map(TestingDifferentialFuzzer::hex).toList());
            trace.add("replace", () -> hex(expected.replaced()));
            trace.add("replace callback", () -> hex(expected.replacedByCallback()));
        }

        // As in Trino, a start past the last code point finds nothing, so an empty source has
        // no position even where the pattern matches it.
        private static String positions(Regex regex, Slice source, long start, int limit)
        {
            List<Long> positions = new ArrayList<>();
            int byteStart = start - 1 > source.length() ? -1 : offsetOfCodePoint(source, (int) (start - 1));
            if (byteStart >= 0) {
                for (int[] match : matches(regex, source, byteStart, limit)) {
                    positions.add(countCodePoints(source, 0, match[0]) + 1L);
                }
            }
            positions.add(-1L);
            return positions.subList(0, Math.min(positions.size(), limit)).toString();
        }

        private static String format(Slice source, List<int[]> matches, int groups, int limit)
        {
            List<String> formatted = new ArrayList<>();
            for (int[] match : matches) {
                if (formatted.size() == limit) {
                    formatted.add("more than " + limit);
                    return formatted.toString();
                }
                StringBuilder values = new StringBuilder();
                for (int group = 0; group <= groups; group++) {
                    values.append(' ').append(hex(group(source, match, group)));
                }
                formatted.add(spans(Arrays.copyOf(match, 2 * (groups + 1))) + values);
            }
            return formatted + " then no match";
        }

        // TestingTrinoJoni.joniMatches from a start, up to a limit, with a search the watchdog can
        // interrupt.
        private static List<int[]> matches(Regex regex, Slice source, int start, int limit)
        {
            int base = source.byteArrayOffset();
            Matcher matcher = regex.matcher(source.byteArray(), base, base + source.length());
            List<int[]> matches = new ArrayList<>();
            int next = start;
            try {
                while (next <= source.length() && matches.size() <= limit && matcher.searchInterruptible(base + next, base + source.length(), Option.DEFAULT) >= 0) {
                    Region region = matcher.getEagerRegion();
                    int[] match = new int[2 * (regex.numberOfCaptures() + 1)];
                    for (int group = 0; group <= regex.numberOfCaptures(); group++) {
                        match[2 * group] = region.beg[group];
                        match[2 * group + 1] = region.end[group];
                    }
                    matches.add(match);
                    next = matcher.getEnd();
                    if (matcher.getBegin() == next) {
                        next += next < source.length() ? lengthOfCodePointFromStartByte(source.getByte(next)) : 1;
                    }
                }
            }
            catch (InterruptedException _) {
                throw new OracleSkipException("oracle timeout");
            }
            catch (ArrayIndexOutOfBoundsException e) {
                // Pinned Joni can fail on a multiline ^ after an optional one-character prefix,
                // a recorded oracle bug (TRINO_REGEXP.md, Other matching differences from Joni).
                if (failsAtBeginLine(e)) {
                    throw new OracleSkipException("oracle multiline caret failure");
                }
                throw e;
            }
            return matches;
        }

        private static boolean failsAtBeginLine(ArrayIndexOutOfBoundsException e)
        {
            for (StackTraceElement frame : e.getStackTrace()) {
                if (frame.getClassName().equals("io.airlift.joni.ByteCodeMachine") && frame.getMethodName().equals("opBeginLine")) {
                    return true;
                }
            }
            return false;
        }

        private static Map<String, Integer> namedGroups(Regex regex)
        {
            Map<String, Integer> namedGroups = new TreeMap<>();
            if (regex.numberOfNames() == 0) {
                return namedGroups;
            }
            for (Iterator<NameEntry> entries = regex.namedBackrefIterator(); entries.hasNext(); ) {
                NameEntry entry = entries.next();
                String name = new String(entry.name, entry.nameP, entry.nameEnd - entry.nameP, UTF_8);
                for (int group : entry.getBackRefs()) {
                    namedGroups.put(name, group);
                }
            }
            return namedGroups;
        }
    }

    /// How Joni reads a node, where that changes what a quantifier on it does.
    private enum Shape
    {
        ANY_CHARACTER,
        // A repetition of ., which a second quantifier can reduce to .* as in .?*.
        REPEATED_ANY_CHARACTER,
        // A reluctant repetition of at least one, such as x+? or (?:x+?).
        RELUCTANT_PLUS,
        OTHER,
    }

    private static Shape shape(Node node)
    {
        return switch (node) {
            case AnyCharacter _ -> Shape.ANY_CHARACTER;
            case Repeat repeat -> {
                if (repeat.min() == 1 && repeat.max() == 1) {
                    // Joni drops a repeat of exactly one, so a later quantifier sees the body.
                    yield shape(repeat.body());
                }
                if (repeat.min() == 1 && repeat.unbounded() && !repeat.greedy()) {
                    yield Shape.RELUCTANT_PLUS;
                }
                yield anyCharacter(shape(repeat.body())) ? Shape.REPEATED_ANY_CHARACTER : Shape.OTHER;
            }
            // Joni parses a noncapturing group as its contents.
            case Group group -> group.capturing() ? Shape.OTHER : shape(group.body());
            case Concat concat -> {
                // A sequence of one piece is that piece to Joni, as comments are ignored.
                List<Node> pieces = concat.items().stream().filter(item -> !(item instanceof Comment)).toList();
                yield pieces.size() == 1 ? shape(pieces.getFirst()) : Shape.OTHER;
            }
            default -> Shape.OTHER;
        };
    }

    private static boolean anyCharacter(Shape shape)
    {
        return shape == Shape.ANY_CHARACTER || shape == Shape.REPEATED_ANY_CHARACTER;
    }

    private static boolean hasNegatedNestedClass(CharacterClass characterClass)
    {
        for (List<ClassItem> operand : characterClass.operands()) {
            for (ClassItem item : operand) {
                if (item instanceof CharacterClass nested && (nested.negated() || hasNegatedNestedClass(nested))) {
                    return true;
                }
            }
        }
        return false;
    }

    /// What the text written so far means for the next piece: whether it can end directly after
    /// a greedy `.*` or `.+` with only empty matches since, whether every path from the start of
    /// the pattern can match only empty text, and whether one passes an anchor or boundary.
    private record Position(boolean afterDotStar, boolean zeroWidth, boolean asserted) {}

    /// The flags that change what the writer writes or leaves out: `x` how literals are written
    /// and where ignored text goes, `s` which quantifiers a `.` takes, and `i` which characters
    /// literals and classes may hold.
    private record Scope(boolean extended, boolean dotAll, boolean ignoreCase)
    {
        Scope apply(FlagGroup group)
        {
            return new Scope(
                    group.enabled().indexOf('x') >= 0 || (extended && group.disabled().indexOf('x') < 0),
                    group.enabled().indexOf('s') >= 0 || (dotAll && group.disabled().indexOf('s') < 0),
                    group.enabled().indexOf('i') >= 0 || (ignoreCase && group.disabled().indexOf('i') < 0));
        }
    }

    /// Writes a tree in Trino syntax, or returns `null` when the tree is outside the dialect. It
    /// follows the text in order, as pinned Joni's parser does, for the differences that depend
    /// on what came before.
    private static final class TrinoWriter
    {
        private static final String METACHARACTERS = "\\^$.|?*+()[]{}";

        private final StringBuilder out = new StringBuilder();
        private boolean afterDotStar;
        // Pinned Joni's lexer field for a brace-less \pL, which greedy quantifiers and brace-less
        // properties set and reluctant quantifiers and braced properties outside a class clear.
        // While it is set, pinned Joni reads a braced property in a class as brace-less.
        private boolean braceLessPropertyFlag;
        private boolean zeroWidthPrefix = true;
        private boolean assertedPrefix;

        static String write(Node tree)
        {
            // A case-insensitive pattern has no full-fold characters; see filterInput.
            if (mayIgnoreCase(tree) && TestingPatternTree.characters(tree).stream().anyMatch(FULL_FOLD_CHARACTERS::contains)) {
                return null;
            }
            TrinoWriter writer = new TrinoWriter();
            try {
                writer.node(tree, new Scope(false, false, false), true);
            }
            catch (OutsideDialect _) {
                return null;
            }
            return writer.out.toString();
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

        private void node(Node node, Scope scope, boolean endsGroup)
        {
            switch (node) {
                case Literal literal -> {
                    checkFolding(literal.codePoint(), scope);
                    character(literal(literal.codePoint(), literal.style(), scope, false));
                }
                // As in Joni, a quantifier on a quote repeats only its last character.
                case Quote quote -> {
                    for (int codePoint : quote.text().codePoints().toArray()) {
                        checkFolding(codePoint, scope);
                    }
                    character("\\Q" + quote.text() + "\\E");
                }
                case AnyCharacter _ -> character(".");
                case PerlClass perlClass -> character("\\" + perlClass.letter());
                case Property property -> character(property(property, false));
                case CharacterClass characterClass -> {
                    StringBuilder text = new StringBuilder();
                    characterClass(characterClass, scope, text);
                    if (scope.ignoreCase() && !characterClass.negated()) {
                        checkFolding(characterClass, scope);
                    }
                    character(text.toString());
                }
                case Anchor anchor -> {
                    // Directly after a greedy .* or .+, pinned Joni's \b and \B read a stale
                    // previous character, a recorded difference (TRINO_REGEXP.md, Other matching
                    // differences from Joni).
                    if (afterDotStar && (anchor.text().equals("\\b") || anchor.text().equals("\\B"))) {
                        throw new OutsideDialect();
                    }
                    assertedPrefix = true;
                    out.append(anchor.text());
                }
                case Comment comment -> out.append(comment(comment.style(), scope));
                case Group group -> {
                    if (!group.capturing()) {
                        out.append("(?:");
                    }
                    else if (group.name() != null) {
                        out.append((group.name().hashCode() & 1) == 0 ? "(?<" + group.name() + ">" : "(?'" + group.name() + "'");
                    }
                    else {
                        out.append('(');
                    }
                    node(group.body(), scope, true);
                    out.append(')');
                }
                case FlagGroup group -> {
                    String flags = group.disabled().isEmpty() ? group.enabled() : group.enabled() + "-" + group.disabled();
                    // Isolated flags open a group that runs to the end of the enclosing group, so
                    // they are written isolated only where no alternative follows.
                    if (!flags.isEmpty() && group.isolated() && endsGroup) {
                        out.append("(?").append(flags).append(')');
                        node(group.body(), scope.apply(group), true);
                    }
                    else {
                        out.append("(?").append(flags).append(':');
                        node(group.body(), scope.apply(group), true);
                        out.append(')');
                    }
                }
                case Concat concat -> {
                    List<Node> items = concat.items();
                    for (int index = 0; index < items.size(); index++) {
                        Node item = items.get(index);
                        int mark = out.length();
                        if (item instanceof Alternation && items.size() > 1) {
                            out.append("(?:");
                            node(item, scope, true);
                            out.append(')');
                        }
                        else {
                            node(item, scope, endsGroup && index == items.size() - 1);
                        }
                        // Pinned and modern Joni can wrap a long decimal escape into a
                        // backreference and Oniguruma does not, a recorded difference (TRINO_REGEXP.md, Other
                        // matching differences from Joni), so an empty group keeps a literal
                        // digit from extending an escape past six digits, a conservative cap.
                        if (mark < out.length() && Character.isDigit(out.charAt(mark)) && out.charAt(mark) < 0x80 && endsWithLongDecimalEscape(out, mark)) {
                            out.insert(mark, "(?:)");
                        }
                    }
                }
                case Alternation alternation -> {
                    Position start = position();
                    boolean endsAfterDotStar = false;
                    boolean endsZeroWidth = true;
                    boolean endsAsserted = false;
                    List<Node> alternatives = alternation.alternatives();
                    for (int index = 0; index < alternatives.size(); index++) {
                        if (index > 0) {
                            out.append('|');
                        }
                        restore(start);
                        node(alternatives.get(index), scope, endsGroup && index == alternatives.size() - 1);
                        endsAfterDotStar |= afterDotStar;
                        endsZeroWidth &= zeroWidthPrefix;
                        endsAsserted |= assertedPrefix;
                    }
                    restore(new Position(endsAfterDotStar, endsZeroWidth, endsAsserted));
                }
                case Repeat repeat -> repeat(repeat, scope);
            }
        }

        private void character(String text)
        {
            out.append(text);
            afterDotStar = false;
            zeroWidthPrefix = false;
        }

        private void repeat(Repeat repeat, Scope scope)
        {
            Position before = position();
            repeatBody(repeat, scope);
            applyQuantifier(repeat, before, scope);
        }

        // Writes the body of `repeat`, with any quantifiers inside it, but not its own quantifier.
        private void repeatBody(Repeat repeat, Scope scope)
        {
            Node body = repeat.body();
            String quantifier = quantifier(repeat);
            if (body instanceof Repeat inner && (repeat.style() & 2) != 0 && !(inner.greedy() && (quantifier.startsWith("?") || quantifier.startsWith("+")))) {
                // Joni repeats a repetition when a quantifier follows another, as in a**. Directly
                // after a greedy quantifier, + makes it possessive, which is unsupported
                // (TRINO_REGEXP.md, Unsupported features), and ? makes it reluctant, so those are
                // grouped.
                repeat(inner, scope);
            }
            else if (body instanceof Literal || body instanceof Quote || body instanceof AnyCharacter || body instanceof PerlClass ||
                    body instanceof Property || body instanceof CharacterClass || body instanceof Group || body instanceof FlagGroup) {
                node(body, scope, false);
            }
            else {
                out.append("(?:");
                node(body, scope, true);
                out.append(')');
            }
        }

        private void applyQuantifier(Repeat repeat, Position before, Scope scope)
        {
            if (!allowsRepeat(repeat.body(), repeat.min(), repeat.max(), repeat.greedy())) {
                throw new OutsideDialect();
            }
            boolean anyCharacter = anyCharacter(shape(repeat.body()));
            // Pinned Joni searches only from the start for a dotall .* that only anchors and
            // boundaries precede, a recorded difference (TRINO_REGEXP.md, Other matching
            // differences from Joni).
            if (anyCharacter && scope.dotAll() && repeat.greedy() && repeat.unbounded() && repeat.min() == 0 && before.zeroWidth() && before.asserted()) {
                throw new OutsideDialect();
            }
            if ((repeat.style() & 4) != 0) {
                out.append("(?#)");
            }
            out.append(quantifier(repeat));
            // Pinned Joni keeps a quantifier's greedy flag where it later reads whether a
            // property in a class is brace-less.
            braceLessPropertyFlag = repeat.greedy();
            if (anyCharacter && repeat.greedy() && repeat.unbounded()) {
                afterDotStar = true;
            }
            else if (repeat.min() == 0) {
                afterDotStar |= before.afterDotStar();
            }
            if (repeat.maximum() == 0) {
                zeroWidthPrefix = before.zeroWidth();
                assertedPrefix = before.asserted();
            }
        }

        // A case-insensitive literal holds no character whose folding differs; see
        // FOLD_DIFFERENCES.
        private static void checkFolding(int codePoint, Scope scope)
        {
            if (scope.ignoreCase() && FOLD_DIFFERENCES.contains(codePoint)) {
                throw new OutsideDialect();
            }
        }

        // A positive case-insensitive class, nested negations included, holds no character whose
        // folding differs (FOLD_DIFFERENCES). Its members before folding are what Regulator
        // matches for the class written without case insensitivity. A negated class needs no
        // check: both parsers add the multi-character spellings only to a positive class, and
        // filterInput keeps the full-fold characters, such as ß, out of the inputs.
        private void checkFolding(CharacterClass characterClass, Scope scope)
        {
            StringBuilder members = new StringBuilder(scope.extended() ? "(?x)" : "");
            characterClass(characterClass, scope, members);
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(members.toString()));
            for (int codePoint : FOLD_DIFFERENCES) {
                if (regexp.contains(utf8Slice(Character.toString(codePoint)))) {
                    throw new OutsideDialect();
                }
            }
        }

        private void characterClass(CharacterClass characterClass, Scope scope, StringBuilder text)
        {
            text.append('[');
            if (characterClass.negated()) {
                text.append('^');
            }
            List<List<ClassItem>> operands = characterClass.operands();
            for (int operandIndex = 0; operandIndex < operands.size(); operandIndex++) {
                // An intersection operand is never empty; an empty one is unsupported
                // (TRINO_REGEXP.md, Unsupported features).
                if (operandIndex > 0) {
                    text.append("&&");
                }
                List<ClassItem> operand = operands.get(operandIndex);
                for (int itemIndex = 0; itemIndex < operand.size(); itemIndex++) {
                    ClassItem item = operand.get(itemIndex);
                    if (operandIndex == 0 && itemIndex == 0 && item instanceof ClassLiteral(int codePoint, int style) && codePoint == '-' && style == 0) {
                        // A dash that starts a class is a literal.
                        text.append('-');
                        continue;
                    }
                    switch (item) {
                        case ClassLiteral literal -> text.append(literal(literal.codePoint(), literal.style(), scope, true));
                        // Range endpoints are characters, never classes, which are unsupported as
                        // endpoints (TRINO_REGEXP.md, Unsupported features).
                        case ClassRange range -> text.append(literal(range.low(), range.lowStyle(), scope, true)).append('-').append(literal(range.high(), range.highStyle(), scope, true));
                        case PerlClass perlClass -> text.append('\\').append(perlClass.letter());
                        case Property property -> {
                            // After a greedy quantifier or a brace-less \pL, pinned Joni reads a
                            // property in a class as brace-less, a recorded difference
                            // (TRINO_REGEXP.md, Other matching differences from Joni).
                            if (braceLessPropertyFlag || property.braceless()) {
                                throw new OutsideDialect();
                            }
                            text.append(property(property, true));
                        }
                        case PosixClass posixClass -> text.append("[:").append(posixClass.negated() ? "^" : "").append(posixClass.name()).append(":]");
                        case CharacterClass nested -> characterClass(nested, scope, text);
                    }
                }
            }
            text.append(']');
        }

        private String property(Property property, boolean inClass)
        {
            String sign = property.negated() ? "P" : "p";
            if (property.braceless()) {
                // A brace-less \pL is a property only outside a class.
                braceLessPropertyFlag = true;
                return "\\" + sign + property.name();
            }
            braceLessPropertyFlag &= inClass;
            return "\\" + sign + "{" + property.name() + "}";
        }

        /// Writes a character in the escape `style` names where that escape can spell it, and
        /// otherwise plainly, with a backslash where Joni would read it as syntax.
        private static String literal(int codePoint, int style, Scope scope, boolean inClass)
        {
            switch (style) {
                case 1 -> {
                    if (codePoint <= 0x7F) {
                        return "\\x" + HexFormat.of().toHexDigits((byte) codePoint);
                    }
                }
                case 2 -> {
                    return "\\x{" + Integer.toHexString(codePoint) + "}";
                }
                case 3 -> {
                    return "\\x{" + HexFormat.of().withUpperCase().toHexDigits(codePoint) + "}";
                }
                case 4 -> {
                    if (codePoint <= 0xFFFF) {
                        return "\\u" + HexFormat.of().toHexDigits((short) codePoint);
                    }
                }
                case 5, 6 -> {
                    // Joni keeps bits 7 and 0-4 of the whole character after \c, so a non-ASCII
                    // text character can write a control character, and reads an escaped
                    // operand as itself.
                    List<String> operands = NON_ASCII_CONTROL_OPERANDS.getOrDefault(codePoint, List.of());
                    if (!operands.isEmpty()) {
                        return "\\c" + (style == 6 ? "\\" : "") + operands.getFirst();
                    }
                    if (codePoint >= 1 && codePoint <= 26) {
                        return "\\c" + (char) ((style == 5 ? 'A' : 'a') + codePoint - 1);
                    }
                }
                case 7 -> {
                    // Octal escapes stay at or below \177: above it they are non-ASCII byte
                    // escapes, which are unsupported (TRINO_REGEXP.md, Unsupported features).
                    if (codePoint >= 1 && codePoint <= 0177) {
                        return String.format(Locale.ROOT, "\\%03o", codePoint);
                    }
                }
                case 8 -> {
                    // Joni reads these letters as themselves after a backslash.
                    if (!inClass && LITERAL_ESCAPE_LETTERS.contains(Character.toString(codePoint))) {
                        return "\\" + (char) codePoint;
                    }
                }
                default -> {}
            }
            boolean escaped = scope.extended() || style % 2 == 1;
            return switch (codePoint) {
                case '\t' -> escaped ? "\\t" : "\t";
                case '\n' -> escaped ? "\\n" : "\n";
                case '\r' -> escaped ? "\\r" : "\r";
                case ' ', '#' -> scope.extended() && !inClass ? "\\" + (char) codePoint : Character.toString(codePoint);
                // Escaped inside a class so a dash is never read as a range, including after a
                // range, where pinned Joni can drop it (TRINO_REGEXP.md, Other matching
                // differences from Joni), and an ampersand never starts an intersection.
                case '-', '&' -> inClass ? "\\" + (char) codePoint : Character.toString(codePoint);
                default -> METACHARACTERS.indexOf(codePoint) >= 0 ? "\\" + (char) codePoint : Character.toString(codePoint);
            };
        }

        /// Returns extended-mode whitespace or a comment, which `TrinoRegexp` and pinned Joni
        /// ignore, or a comment group, which they ignore outside a class.
        private static String comment(int style, Scope scope)
        {
            if (style >= 4 && scope.extended()) {
                return switch (style) {
                    case 4 -> " ";
                    case 5 -> "\t";
                    case 6 -> "\n";
                    case 7 -> "  ";
                    default -> " # a comment (with | metacharacters\n";
                };
            }
            return switch (style % 4) {
                case 0 -> "(?#)";
                case 1 -> "(?#note)";
                case 2 -> "(?#a|b*[)";
                default -> "(?#escaped \\) paren)";
            };
        }

        /// Returns whether the text before `end` ends with a backslash and six or more digits.
        /// Six is a conservative cap, not the length at which a number overflows an int.
        private static boolean endsWithLongDecimalEscape(CharSequence text, int end)
        {
            int digits = 0;
            int index = end - 1;
            while (index >= 0 && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
                digits++;
                index--;
            }
            return digits >= 6 && index >= 0 && text.charAt(index) == '\\';
        }
    }
}
