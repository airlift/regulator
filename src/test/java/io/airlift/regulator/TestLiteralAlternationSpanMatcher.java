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

import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestLiteralAlternationSpanMatcher
{
    @Test
    public void testPinnedNativeBoundaries()
    {
        LiteralAlternationSpanMatcher scanner = scanner("ERROR|WARN|FATAL");
        Slice input = utf8Slice("info WARN then ERROR and FATAL");

        assertSpan(scanner, input, 0, 5, 9);
        assertSpan(scanner, input, 9, 15, 20);
        assertSpan(scanner, input, 20, 25, 30);
        assertThat(scanner.findSpan(input, 30)).isEqualTo(Dfa.SEARCH_NO_MATCH);

        LiteralAlternationSpanMatcher unicode = scanner("βeta|γamma|alpha");
        assertSpan(unicode, utf8Slice("xxγamma then βeta"), 0, 2, 8);
        assertSpan(unicode, utf8Slice("xxγamma then βeta"), 8, 14, 19);
    }

    @Test
    public void testGeneralEngineAgreement()
    {
        List<String> patterns = List.of(
                "ERROR|WARN|FATAL",
                "foo|far|beta",
                "βeta|γamma|alpha",
                "alpha|βeta|終端");
        List<Slice> inputs = List.of(
                utf8Slice(""),
                utf8Slice("ordinary text"),
                utf8Slice("FATAL then ERROR"),
                utf8Slice("xxfar then foo"),
                utf8Slice("xxβeta終端alpha"),
                wrappedBuffer(new byte[] {(byte) 0xFF, 'E', 'R', 'R', 'O', 'R'}));

        for (String expression : patterns) {
            LiteralAlternationSpanMatcher scanner = scanner(expression);
            Re2 pattern = Re2.compile(utf8Slice(expression));
            for (Slice input : inputs) {
                for (int start = 0; start <= input.length(); start++) {
                    RegexpMatcher matcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
                    long span = scanner.findSpan(input, start);
                    if (matcher.find(start)) {
                        assertThat(span)
                                .as("pattern %s input %s start %s", expression, input, start)
                                .isEqualTo(((long) matcher.start() << 32) | (matcher.end() & 0xFFFF_FFFFL));
                    }
                    else {
                        assertThat(span)
                                .as("pattern %s input %s start %s", expression, input, start)
                                .isEqualTo(Dfa.SEARCH_NO_MATCH);
                    }
                }
            }
        }
    }

    @Test
    public void testEligibility()
    {
        assertThat(optionalScanner("ERROR|WARN|FATAL")).isNotNull();
        assertThat(optionalScanner("alpha|βeta|終端")).isNotNull();
        assertThat(optionalScanner("foo|far")).isNotNull();
        assertThat(optionalScanner("foo|foo")).isNull();
        assertThat(optionalScanner("sam|samwise")).isNull();
        assertThat(optionalScanner("foo|")).isNull();
        assertThat(optionalScanner("foo|f[ao]r")).isNull();
        assertThat(optionalScanner("foo|(bar)")).isNull();
        assertThat(optionalScanner("(?i:foo)|bar")).isNull();
        assertThat(optionalScanner("^foo|bar")).isNull();
        assertThat(optionalScanner("foo")).isNull();

        // Above the trie's limit, a literal set also takes the empty alternatives and merged
        // classes the parsers' factoring leaves in a dictionary.
        String dictionary = words(65);
        assertThat(optionalLiteralSet(words(64))).isNull();
        assertThat(optionalLiteralSet(dictionary)).isNotNull();
        assertThat(optionalLiteralSet(dictionary + "|sam|samwise|absentmindedness(?:|'s)|cat[sy]")).isNotNull();
        assertThat(optionalLiteralSet(words(60) + "|sam|samwise")).isNull();
        assertThat(optionalLiteralSet(dictionary + "|")).isNull();
        assertThat(optionalLiteralSet("(?i:" + dictionary + ")")).isNull();
        assertThat(optionalLiteralSet(dictionary + "|(bar)")).isNull();
        assertThat(optionalLiteralSet(dictionary + "|f[a-p]")).isNotNull();
        assertThat(optionalLiteralSet(dictionary + "|f.r")).isNull();
        assertThat(optionalLiteralSet(dictionary + "|f[a-q]")).isNull();

        // A choice of several literals must end its concatenation, since one before more text
        // expands to every combination. A choice of empty literals only adds nothing.
        assertThat(optionalLiteralSet(dictionary + "|f[a-p]r")).isNull();
        assertThat(optionalLiteralSet("(?:" + dictionary + ")z")).isNull();
        assertThat(optionalLiteralSet("[a-p][a-p][a-p]")).isNull();
        assertThat(optionalLiteralSet(dictionary + "|b[xy](?:|)|(?:|)c[xy]")).isNotNull();

        // Extraction stops past a bound on its work, which a run of separately flagged literals
        // or deeply nested choices makes quadratic in the pattern.
        String flagRun = "(?s:a)(?-s:a)".repeat(8_000);
        String nestedChoices = "b";
        for (int depth = 0; depth < 90; depth++) {
            nestedChoices = "a".repeat(1000) + "(?:|" + nestedChoices + ")";
        }
        assertThat(optionalScanner("foo|" + flagRun)).isNull();
        assertThat(optionalLiteralSet(dictionary + "|" + flagRun)).isNull();
        assertThat(optionalLiteralSet(dictionary + "|" + nestedChoices)).isNull();
    }

    @Test
    public void testLargeAlternationRoutes()
    {
        Slice dictionary = utf8Slice(words(65));
        // A view between sentinel bytes, so spans must be relative to its offset.
        Slice backing = utf8Slice("!prefix word17 and word64, then 12K 7\u212A 3k word3x word2?");
        Slice input = backing.slice(1, backing.length() - 2);
        // Rust's parser folds (?i)k into the class [Kk\x{212A}], which carries no case-folding flag.
        String foldedDictionary = "(?i)" + IntStream.range(0, 65).mapToObj(index -> index + "k").collect(Collectors.joining("|"));
        List<Re2> patterns = List.of(
                Re2.compile(dictionary),
                JavaRegexp.compile(dictionary).re2(),
                RustRegexp.compile(dictionary).re2(),
                RustRegexp.compile(dictionary, RustRegexp.Options.defaults().setUnicode(false).setBytes(true)).re2(),
                RustRegexp.compile(utf8Slice(foldedDictionary)).re2());
        for (Re2 pattern : patterns) {
            assertLiteralSetFind(pattern, input);
        }
        // A Latin-1 program reads a class's members as bytes.
        Slice latin1Backing = wrappedBuffer("!caf\u00E8 word5 cafe caf\u00E9?".getBytes(ISO_8859_1));
        assertLiteralSetFind(
                Re2.compile(utf8Slice(words(65) + "|caf[\\xE9\\xE8]"), Re2.Options.latin1()),
                latin1Backing.slice(1, latin1Backing.length() - 2));

        // Longest-match search keeps the program, so its forward budget is the uncharged control.
        Re2 longestMatch = Re2.compile(dictionary, Re2.Options.defaults().setLongestMatch(true));
        assertThat(longestMatch.literalSetSpanMatcher()).isNull();
        assertThat(patterns.getFirst().forwardProgramForDiagnostics().dfaMemory())
                .isEqualTo(longestMatch.forwardProgramForDiagnostics().dfaMemory() - patterns.getFirst().literalSetSpanMatcher().estimatedRetainedSize());
        assertThat(Re2.compile(utf8Slice(words(64))).literalSetSpanMatcher()).isNull();
        assertThat(Re2.compile(utf8Slice("(" + words(65) + ")")).literalSetSpanMatcher()).isNull();
    }

    @Test
    public void testLargeAlternationAgainstGeneralEngine()
    {
        String[] tokens = {"a", "b", "ab", "é", "ж", "終", "💰"};
        Random random = new Random(20261031);
        for (int iteration = 0; iteration < 40; iteration++) {
            List<String> literals = new ArrayList<>();
            // Repeats of a literal that the factoring places together collapse into one, so the
            // set counts distinct literals.
            int literalCount = 65 + random.nextInt(40);
            while (Set.copyOf(literals).size() < literalCount) {
                String base = literals.isEmpty() ? randomText(random, tokens, 1, 4) : literals.get(random.nextInt(literals.size()));
                String literal = switch (random.nextInt(5)) {
                    case 0 -> base + randomText(random, tokens, 1, 2);
                    case 1 -> randomText(random, tokens, 1, 2) + base;
                    case 2 -> base.substring(0, base.offsetByCodePoints(0, 1 + random.nextInt(base.codePointCount(0, base.length()))));
                    case 3 -> base.substring(base.offsetByCodePoints(0, random.nextInt(base.codePointCount(0, base.length()))));
                    default -> randomText(random, tokens, 1, 5);
                };
                literals.add(literal);
            }
            String expression = String.join("|", literals);
            Re2 pattern = Re2.compile(utf8Slice(expression));
            assertThat(pattern.literalSetSpanMatcher()).as(expression).isNotNull();
            LiteralSetSpanMatcher trinoScanner = literalSet(expression);

            for (int inputIndex = 0; inputIndex < 5; inputIndex++) {
                Slice input = randomInput(random, literals, tokens);
                assertFindAgreement(pattern, input);
                for (int start = 0; start <= input.length(); start++) {
                    RegexpMatcher generalMatcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
                    long expected = generalMatcher.find(start)
                            ? ((long) generalMatcher.start() << 32) | (generalMatcher.end() & 0xFFFF_FFFFL)
                            : Dfa.SEARCH_NO_MATCH;
                    assertThat(trinoScanner.findSpan(input, start, input.length()))
                            .as("pattern %s input %s start %s", expression, input.toStringUtf8(), start)
                            .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    public void testPublicRouteAndMemoryAccounting()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL"));
        LiteralAlternationSpanMatcher matcher = regexp.literalAlternationSpanMatcher();
        assertThat(matcher).isNotNull();
        assertThat(regexp.re2()).isNull();
        long maxMemory = TrinoRegexp.Options.defaults().maxMemory();
        long forwardMemory = maxMemory - Math.max(1, maxMemory / 3);
        assertThat(matcher.estimatedRetainedSize()).isLessThan(forwardMemory);

        assertThat(TrinoRegexp.compile(utf8Slice("foo|(bar)")).literalAlternationSpanMatcher()).isNull();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i:foo)|bar")).literalAlternationSpanMatcher()).isNull();
        assertThat(TrinoRegexp.compile(utf8Slice("foo|")).literalAlternationSpanMatcher()).isNull();

        assertThatThrownBy(() -> TrinoRegexp.compile(
                utf8Slice("ERROR|WARN|FATAL"),
                TrinoRegexp.Options.defaults().setMaxMemory(matcher.estimatedRetainedSize())))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);

        // A shared prefix expands to megabytes of literals but few states, and an automaton over
        // a small budget is not built, so the compile reports the program's memory limit.
        String sharedPrefix = "a".repeat(60_000) + IntStream.range(0, 65).mapToObj(index -> "pad" + index + "z").collect(Collectors.joining("|", "(?:", ")"));
        assertThat(optionalLiteralSet(sharedPrefix)).isNotNull();
        assertThat(optionalLiteralSet(sharedPrefix, 1024)).isNull();
        assertThatThrownBy(() -> TrinoRegexp.compile(utf8Slice(sharedPrefix), TrinoRegexp.Options.defaults().setMaxMemory(1024)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    @Test
    public void testConcurrentReuse()
            throws Exception
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL"));
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int task = 0; task < 32; task++) {
                int index = task;
                results.add(executor.submit(() -> regexp.contains(utf8Slice(index % 2 == 0 ? "prefix WARN suffix" : "ordinary text"))));
            }
            for (int task = 0; task < results.size(); task++) {
                assertThat(results.get(task).get()).isEqualTo(task % 2 == 0);
            }
        }
    }

    @Test
    public void testEverydayWorkloadInventory()
    {
        Set<String> eligibleWorkloads = TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds().stream()
                .filter(workload -> optionalScanner(
                        TestingEverydayTrinoRegexpBenchmarkInputs.create(workload).pattern().toStringUtf8()) != null)
                .collect(Collectors.toSet());
        assertThat(eligibleWorkloads).containsExactly("logLevel");
    }

    @Test
    public void testRepresentativeRebarInventory()
    {
        String english = "Sherlock Holmes|John Watson|Irene Adler|Inspector Lestrade|Professor Moriarty";
        String russian = "Шерлок Холмс|Джон Уотсон|Ирен Адлер|инспектор Лестрейд|профессор Мориарти";
        String chinese = "夏洛克·福尔摩斯|约翰华生|阿德勒|雷斯垂德|莫里亚蒂教授";

        assertThat(optionalScanner(english)).isNotNull();
        assertThat(optionalScanner(russian)).isNotNull();
        assertThat(optionalScanner(chinese)).isNotNull();
        assertThat(optionalScanner("(?i:" + english + ")")).isNull();
        assertThat(optionalScanner("(?i:" + russian + ")")).isNull();
    }

    private static String words(int count)
    {
        return IntStream.range(0, count).mapToObj(index -> "word" + index).collect(Collectors.joining("|"));
    }

    private static String randomText(Random random, String[] tokens, int minimumTokens, int maximumTokens)
    {
        StringBuilder text = new StringBuilder();
        int tokenCount = minimumTokens + random.nextInt(maximumTokens - minimumTokens + 1);
        for (int index = 0; index < tokenCount; index++) {
            text.append(tokens[random.nextInt(tokens.length)]);
        }
        return text.toString();
    }

    // Literals, their fragments, other text, and malformed bytes, which RE2 matches as bytes.
    private static Slice randomInput(Random random, List<String> literals, String[] tokens)
    {
        DynamicSliceOutput output = new DynamicSliceOutput(256);
        while (output.size() < 120) {
            Slice literal = utf8Slice(literals.get(random.nextInt(literals.size())));
            switch (random.nextInt(5)) {
                case 0, 1 -> output.appendBytes(literal);
                case 2 -> output.appendBytes(literal.slice(0, 1 + random.nextInt(literal.length())));
                case 3 -> output.appendBytes(utf8Slice(randomText(random, tokens, 1, 3)));
                default -> output.appendByte(random.nextBoolean() ? 0xFF : 0x80);
            }
        }
        return output.slice();
    }

    // A routed find runs the automaton, leaving the program's first-match DFA unbuilt, and its
    // spans are the general engine's.
    private static void assertLiteralSetFind(Re2 pattern, Slice input)
    {
        assertThat(pattern.literalSetSpanMatcher()).isNotNull();
        RegexpMatcher matcher = pattern.matcher(input);
        assertThat(matcher.literalSetEnabledForDiagnostics()).isTrue();
        assertThat(matcher.find()).isTrue();
        assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        assertThat(pattern.genericGroupZeroMatcherForDiagnostics(input).literalSetEnabledForDiagnostics()).isFalse();
        assertFindAgreement(pattern, input);
    }

    // The routed matcher's finds, region finds, and find(int) give the general engine's spans.
    private static void assertFindAgreement(Re2 pattern, Slice input)
    {
        String expression = pattern.pattern().toStringUtf8();
        String text = input.toStringUtf8();
        assertThat(findSpans(pattern.matcher(input, 0)))
                .as("pattern %s input %s", expression, text)
                .isEqualTo(findSpans(pattern.genericGroupZeroMatcherForDiagnostics(input)));
        int regionStart = input.length() / 3;
        int regionEnd = input.length() - input.length() / 4;
        assertThat(findSpans(pattern.matcher(input, 0).reset(input, regionStart, regionEnd)))
                .as("pattern %s input %s region %s-%s", expression, text, regionStart, regionEnd)
                .isEqualTo(findSpans(pattern.genericGroupZeroMatcherForDiagnostics(input).reset(input, regionStart, regionEnd)));
        RegexpMatcher matcher = pattern.matcher(input, 0);
        RegexpMatcher generalMatcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
        for (int start = 0; start <= input.length(); start++) {
            String expected = generalMatcher.find(start) ? generalMatcher.start() + "-" + generalMatcher.end() : "none";
            assertThat(matcher.find(start) ? matcher.start() + "-" + matcher.end() : "none")
                    .as("pattern %s input %s start %s", expression, text, start)
                    .isEqualTo(expected);
        }
    }

    private static List<String> findSpans(RegexpMatcher matcher)
    {
        List<String> spans = new ArrayList<>();
        while (matcher.find()) {
            spans.add(matcher.start() + "-" + matcher.end());
        }
        return spans;
    }

    private static LiteralAlternationSpanMatcher scanner(String pattern)
    {
        LiteralAlternationSpanMatcher scanner = optionalScanner(pattern);
        assertThat(scanner).isNotNull();
        return scanner;
    }

    private static LiteralAlternationSpanMatcher optionalScanner(String pattern)
    {
        ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL);
        return LiteralAlternationSpanMatcher.analyze(parsed.regexp(), parsed.capturingGroupCount());
    }

    private static LiteralSetSpanMatcher literalSet(String pattern)
    {
        LiteralSetSpanMatcher literalSet = optionalLiteralSet(pattern);
        assertThat(literalSet).isNotNull();
        return literalSet;
    }

    private static LiteralSetSpanMatcher optionalLiteralSet(String pattern)
    {
        return optionalLiteralSet(pattern, TrinoRegexp.Options.defaults().maxMemory());
    }

    private static LiteralSetSpanMatcher optionalLiteralSet(String pattern, long maximumRetainedBytes)
    {
        ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL);
        return LiteralSetSpanMatcher.analyze(parsed.regexp(), parsed.capturingGroupCount(), maximumRetainedBytes);
    }

    private static void assertSpan(
            LiteralAlternationSpanMatcher scanner,
            Slice input,
            int searchStart,
            int expectedStart,
            int expectedEnd)
    {
        long span = scanner.findSpan(input, searchStart);
        assertThat((int) (span >>> 32)).isEqualTo(expectedStart);
        assertThat((int) span).isEqualTo(expectedEnd);
    }
}
