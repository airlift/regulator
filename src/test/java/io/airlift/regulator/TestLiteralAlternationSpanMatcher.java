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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.airlift.regulator.TestingPatternShapes.shape;
import static io.airlift.slice.SizeOf.instanceSize;
import static io.airlift.slice.SizeOf.sizeOfByteArray;
import static io.airlift.slice.SizeOf.sizeOfObjectArray;
import static io.airlift.slice.SizeOf.sizeOfShortArray;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.lang.Math.toIntExact;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestLiteralAlternationSpanMatcher
{
    // The DFA's start-scan recheck distance, for which a search keeps the route its sample chose.
    private static final int RECHECK_BYTES = 64 * 1024;

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
                "alpha|βeta|終端",
                // The first alternative wins over a longer later one at the same start.
                "xa|b|xab");
        List<Slice> inputs = List.of(
                utf8Slice(""),
                utf8Slice("ordinary text"),
                utf8Slice("FATAL then ERROR"),
                utf8Slice("xxfar then foo"),
                utf8Slice("xxβeta終端alpha"),
                utf8Slice("xab xa b xxab ÿþ"),
                wrappedBuffer(new byte[] {(byte) 0xFF, 'E', 'R', 'R', 'O', 'R'}),
                wrappedBuffer("\u00FFFATAL\u00CEWARN\u00CE\u00CE\u00B2eta\u00E7\u00B5ERROR\u00FF\u00FE".getBytes(ISO_8859_1)));

        // Boolean find and count of RE2, Java, and Rust patterns run on the matcher and agree
        // with the general engine, including on malformed UTF-8.
        Map<String, CompiledPattern> compiledPatterns = new LinkedHashMap<>();
        for (String expression : patterns) {
            compiledPatterns.putAll(re2JavaAndRustPatterns(expression));
        }
        // A Rust byte-mode escape is a single byte, not a code point.
        compiledPatterns.put("Rust bytes (?-u)ERROR|\\xFF\\xFE", RustRegexp.compile(utf8Slice("(?-u)ERROR|\\xFF\\xFE"), RustRegexp.Options.bytes()));
        for (Map.Entry<String, CompiledPattern> entry : compiledPatterns.entrySet()) {
            String label = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(shape(pattern)).as(label).isEqualTo(CompiledPattern.Shape.LITERAL_ALTERNATION);
            // Count answers from the matcher without building the general engine's DFA.
            pattern.count(inputs.getLast());
            assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).as(label).isNull();
            for (Slice input : inputs) {
                long expectedCount = generalEngineCount(pattern, input);
                assertThat(pattern.find(input)).as("%s input %s", label, input.toStringUtf8()).isEqualTo(expectedCount > 0);
                assertThat(pattern.count(input)).as("%s input %s", label, input.toStringUtf8()).isEqualTo(expectedCount);
            }
        }

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

        // The matcher reports leftmost-first matches, so a leftmost-longest pattern keeps the
        // general engine.
        assertThat(shape(Re2.compile(utf8Slice("xa|b|xab"), Re2.Options.defaults().setLongestMatch(true)))).isEqualTo(CompiledPattern.Shape.ENGINE);
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
        // A literal over 64 bytes would cost its length per byte of a dense near-miss input.
        assertThat(optionalScanner("a".repeat(63) + "b|x")).isNotNull();
        assertThat(optionalScanner("a".repeat(64) + "b|x")).isNull();

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
        List<CompiledPattern> patterns = List.of(
                Re2.compile(dictionary),
                JavaRegexp.compile(dictionary),
                RustRegexp.compile(dictionary),
                RustRegexp.compile(dictionary, RustRegexp.Options.defaults().setUnicode(false).setBytes(true)),
                RustRegexp.compile(utf8Slice(foldedDictionary)));
        for (CompiledPattern pattern : patterns) {
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
        // The 14 literal bytes size the trie's arrays, and the filter, built only with the Vector
        // API, adds its own tables.
        StartLiteralFilter filter = matcher.startLiteralFilterForDiagnostics();
        assertThat(filter != null).isEqualTo(VectorSupport.isAvailable());
        long trieSize = instanceSize(LiteralAlternationSpanMatcher.class) +
                sizeOfShortArray(256) +
                2 * sizeOfShortArray(15) +
                2 * sizeOfShortArray(14) +
                sizeOfByteArray(14);
        // The literals grouped by leading byte for the stop compare, which exist only with the
        // filter: E, W and F lead one literal each.
        long stopLiteralsSize = filter == null ? 0 : sizeOfObjectArray(256) + 3 * sizeOfObjectArray(1) + 2 * sizeOfByteArray(5) + sizeOfByteArray(4);
        assertThat(matcher.estimatedRetainedSize()).isEqualTo(trieSize + stopLiteralsSize + (filter == null ? 0 : filter.estimatedRetainedSize()));

        assertThat(TrinoRegexp.compile(utf8Slice("foo|(bar)")).literalAlternationSpanMatcher()).isNull();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i:foo)|bar")).literalAlternationSpanMatcher()).isNull();
        assertThat(TrinoRegexp.compile(utf8Slice("foo|")).literalAlternationSpanMatcher()).isNull();
        assertThat(TrinoRegexp.compile(utf8Slice("a".repeat(64) + "b|x")).literalAlternationSpanMatcher()).isNull();

        // With the Vector API, the filter's nibble tables and the stop compare's literals make the
        // matcher retain more than the general engine needs. The budget is half the size without
        // the stop literals, so that it stays too small for both.
        assertThatThrownBy(() -> TrinoRegexp.compile(
                utf8Slice("ERROR|WARN|FATAL"),
                TrinoRegexp.Options.defaults().setMaxMemory((matcher.estimatedRetainedSize() - stopLiteralsSize) / 2)))
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

        // Long text skips to the start literal filter's candidates where the Vector API is
        // available; shorter inputs keep the root-table loop.
        for (String pattern : List.of(english, russian, chinese, "Tom|Sawyer|Huckleberry|Finn")) {
            LiteralAlternationSpanMatcher scanner = scanner(pattern);
            assertThat(scanner.usesStartLiteralFilter(63)).as(pattern).isFalse();
            assertThat(scanner.usesStartLiteralFilter(64)).as(pattern).isEqualTo(VectorSupport.isAvailable());
        }
    }

    @Test
    public void testLongInputAgreement()
    {
        Random random = new Random(42);
        List<String> patterns = List.of(
                "Sherlock Holmes|John Watson|Irene Adler|Inspector Lestrade|Professor Moriarty",
                "Шерлок Холмс|Джон Уотсон|Ирен Адлер|инспектор Лестрейд|профессор Мориарти",
                "夏洛克·福尔摩斯|约翰华生|阿德勒|雷斯垂德|莫里亚蒂教授",
                "Tom|Sawyer|Huckleberry|Finn",
                // At a filter stop where several literals match, the first in pattern order wins,
                // whether it is the longer or the shorter one.
                "Holmes|Watson|Holm|Watsonian",
                "ERROR|WARN|FATAL",
                // In abcde, the near miss of abcx hides the start of bcde one byte later.
                "abcx|bcde");
        for (String expression : patterns) {
            LiteralAlternationSpanMatcher scanner = scanner(expression);
            Re2 pattern = Re2.compile(utf8Slice(expression));
            TrinoRegexp trinoPattern = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(shape(pattern)).as(expression).isEqualTo(CompiledPattern.Shape.LITERAL_ALTERNATION);
            assertThat(trinoPattern.literalAlternationSpanMatcher()).as(expression).isNotNull();
            for (int trial = 0; trial < 4; trial++) {
                // A literal ends the text, so a match lands in its last bytes.
                byte[] body = literalText(random, expression.split("\\|"), 2_048 + random.nextInt(4_096));
                int backingOffset = random.nextInt(64);
                byte[] backing = new byte[backingOffset + body.length + random.nextInt(64)];
                random.nextBytes(backing);
                System.arraycopy(body, 0, backing, backingOffset, body.length);
                Slice input = wrappedBuffer(backing, backingOffset, body.length);

                RegexpMatcher matcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
                for (int start = 0; start <= input.length(); start++) {
                    long expected = matcher.find(start)
                            ? ((long) matcher.start() << 32) | (matcher.end() & 0xFFFF_FFFFL)
                            : Dfa.SEARCH_NO_MATCH;
                    assertThat(scanner.findSpan(input, start)).as("pattern %s trial %s start %s", expression, trial, start).isEqualTo(expected);
                }
                long expectedCount = generalEngineCount(pattern, input);
                assertThat(scanner.count(input)).as("pattern %s trial %s", expression, trial).isEqualTo(expectedCount);
                assertThat(pattern.count(input)).as("pattern %s trial %s", expression, trial).isEqualTo(expectedCount);
                assertThat(trinoPattern.count(input)).as("pattern %s trial %s", expression, trial).isEqualTo(expectedCount);
            }
        }

        // Sparse log lines, then near misses with a literal at random places, so searches sample
        // sparse stops in the log and dense ones in the near misses, and switch between the
        // filter and the root table at the 64 KiB rechecks. Spans from random starts, every match
        // in turn, and the counts agree with the general engine.
        StringBuilder mixed = new StringBuilder(logLines(RECHECK_BYTES));
        String[] literals = {"ERROR", "WARN", "FATAL"};
        while (mixed.length() < 5 * RECHECK_BYTES / 2) {
            mixed.append(random.nextInt(64) == 0 ? literals[random.nextInt(literals.length)] : "ERxO WAxN FAxA ");
        }
        Slice input = utf8Slice(mixed.toString());
        LiteralAlternationSpanMatcher scanner = scanner("ERROR|WARN|FATAL");
        Re2 pattern = Re2.compile(utf8Slice("ERROR|WARN|FATAL"));
        RegexpMatcher matcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
        for (int trial = 0; trial < 4_096; trial++) {
            int start = random.nextInt(input.length() + 1);
            long expected = matcher.find(start)
                    ? ((long) matcher.start() << 32) | (matcher.end() & 0xFFFF_FFFFL)
                    : Dfa.SEARCH_NO_MATCH;
            assertThat(scanner.findSpan(input, start)).as("start %s", start).isEqualTo(expected);
        }
        LiteralAlternationSpanMatcher.Cursor cursor = new LiteralAlternationSpanMatcher.Cursor();
        matcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
        long span = scanner.findSpan(input, 0, cursor);
        long expectedCount = 0;
        while (matcher.find()) {
            assertThat(span).isEqualTo(((long) matcher.start() << 32) | (matcher.end() & 0xFFFF_FFFFL));
            expectedCount++;
            span = scanner.findSpan(input, (int) span, cursor);
        }
        assertThat(span).isEqualTo(Dfa.SEARCH_NO_MATCH);
        assertThat(scanner.filterStopCountForDiagnostics() > 0).isEqualTo(VectorSupport.isAvailable());
        assertThat(scanner.rootTablePositionCountForDiagnostics()).isGreaterThan(RECHECK_BYTES / 2);
        assertThat(scanner.count(input)).isEqualTo(expectedCount);
        assertThat(pattern.count(input)).isEqualTo(expectedCount);
        assertThat(TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL")).count(input)).isEqualTo(expectedCount);
    }

    @Test
    public void testDenseNearMissesReadTheRootTable()
    {
        // The filter checks the first, second, and fourth bytes of ERROR, WARN, and FATAL, so it
        // passes ERxO, WAxN, and FAxA, and the trie rejects each at its third byte. Every fifth
        // byte starts such a near miss, and nothing matches.
        int length = 4 * RECHECK_BYTES;
        byte[] nearMisses = nearMisses(length);
        Slice input = wrappedBuffer(nearMisses);
        LiteralAlternationSpanMatcher scanner = scanner("ERROR|WARN|FATAL");
        StartLiteralFilter filter = scanner.startLiteralFilterForDiagnostics();
        assertThat(filter != null).isEqualTo(VectorSupport.isAvailable());
        if (filter != null) {
            assertThat(List.of(filter.firstOffset(), filter.secondOffset(), filter.thirdOffset())).containsExactly(0, 1, 3);
            for (int position = 0; position + 4 <= length; position += 5) {
                assertThat(filter.accepts(nearMisses, position, length)).as("position %s", position).isTrue();
            }
        }

        // Each of the four 64 KiB routes samples the filter's stops once, finds them dense, and
        // reads the root table, so the vector scan makes no stop. The root table alone reads each
        // position once. Without the Vector API there is no filter to sample.
        assertThat(scanner.count(input)).isZero();
        assertThat(scanner.filterStopCountForDiagnostics()).isZero();
        assertThat(scanner.rootTablePositionCountForDiagnostics()).isEqualTo(length);
        assertThat(scanner.sampledPositionCountForDiagnostics()).isEqualTo(VectorSupport.isAvailable() ? 4L * Dfa.BYTE_SCAN_SAMPLE_SIZE : 0);
        assertThat(scanner.findSpan(input, 0)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        assertThat(scanner.filterStopCountForDiagnostics()).isZero();
        assertThat(scanner.rootTablePositionCountForDiagnostics()).isEqualTo(2L * length);
    }

    @Test
    public void testMatcherResetForgetsTheRoute()
    {
        // A Trino matcher reset onto a short input forgets the long input's root-table route.
        Slice input = wrappedBuffer("ERxO WAxN FAxA ".repeat(4 * RECHECK_BYTES / 15).getBytes(UTF_8));
        TrinoRegexpMatcher matcher = TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL")).matcher(input);
        assertThat(matcher.find()).isFalse();
        assertThat(matcher.reset(utf8Slice("xxxx")).find()).isFalse();
        assertThat(matcher.reset(utf8Slice("xxWARN")).find()).isTrue();
        assertThat(List.of(matcher.start(), matcher.end())).containsExactly(2, 6);
    }

    @Test
    public void testLogSamplesOncePerRecheck()
    {
        // Log lines with an occasional WARN or ERROR keep the filter. A count and a Trino
        // matcher's finds sample once per 64 KiB route that has 64 KiB left, not once per match.
        Slice logInput = utf8Slice(logLines(2 * RECHECK_BYTES + 1024));
        long expectedCount = generalEngineCount(Re2.compile(utf8Slice("ERROR|WARN|FATAL")), logInput);
        assertThat(expectedCount).isGreaterThan(100);
        long sampledPositions = VectorSupport.isAvailable() ? (logInput.length() / RECHECK_BYTES) * (long) Dfa.BYTE_SCAN_SAMPLE_SIZE : 0;
        LiteralAlternationSpanMatcher logScanner = scanner("ERROR|WARN|FATAL");
        assertThat(logScanner.count(logInput)).isEqualTo(expectedCount);
        assertThat(logScanner.filterStopCountForDiagnostics() > 0).isEqualTo(VectorSupport.isAvailable());
        assertThat(logScanner.rootTablePositionCountForDiagnostics() < 64).isEqualTo(VectorSupport.isAvailable());
        assertThat(logScanner.sampledPositionCountForDiagnostics()).isEqualTo(sampledPositions);

        TrinoRegexp trinoPattern = TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL"));
        TrinoRegexpMatcher matcher = trinoPattern.matcher(logInput);
        long matches = 0;
        while (matcher.find()) {
            matches++;
        }
        assertThat(matches).isEqualTo(expectedCount);
        LiteralAlternationSpanMatcher trinoScanner = trinoPattern.literalAlternationSpanMatcher();
        assertThat(trinoScanner.sampledPositionCountForDiagnostics()).isEqualTo(sampledPositions);

        // Trino's other multi-match operations carry the route the same way within each call.
        assertThat(trinoPattern.extractAll(logInput, 0)).hasSize(toIntExact(expectedCount));
        assertThat(trinoScanner.sampledPositionCountForDiagnostics()).isEqualTo(2 * sampledPositions);
    }

    @Test
    public void testMatchAcrossTheRecheck()
    {
        // Spaces keep the filter for the first 64 KiB route, and WARN starts two bytes before its
        // end, so the filter reads past the route end to reach WARN's fourth byte.
        String text = " ".repeat(RECHECK_BYTES - 2) + "WARN" + " ".repeat(RECHECK_BYTES);
        byte[] backing = ("!" + text + "!").getBytes(UTF_8);
        TrinoRegexp trinoPattern = TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL"));
        for (Slice input : List.of(utf8Slice(text), wrappedBuffer(backing, 1, text.length()))) {
            LiteralAlternationSpanMatcher scanner = scanner("ERROR|WARN|FATAL");
            assertSpan(scanner, input, 0, RECHECK_BYTES - 2, RECHECK_BYTES + 2);
            assertThat(scanner.filterStopCountForDiagnostics() > 0).isEqualTo(VectorSupport.isAvailable());
            assertThat(scanner.count(input)).isOne();

            TrinoRegexpMatcher matcher = trinoPattern.matcher(input);
            assertThat(matcher.find()).isTrue();
            assertThat(List.of(matcher.start(), matcher.end())).containsExactly(RECHECK_BYTES - 2, RECHECK_BYTES + 2);
            assertThat(matcher.find()).isFalse();
        }
    }

    // Whole log lines of at least `length` bytes, one in 50 at ERROR and one in 20 at WARN.
    private static String logLines(int length)
    {
        StringBuilder log = new StringBuilder();
        for (int line = 0; log.length() < length; line++) {
            String level = line % 50 == 7 ? "ERROR" : line % 20 == 3 ? "WARN" : "INFO";
            log.append("2026-11-01T12:00:00 ").append(level).append(" request served for user ").append(line).append('\n');
        }
        return log.toString();
    }

    @Test
    public void testDenseNearMissesAtTheStartRunTheDfa()
    {
        // RE2 boolean find and count hand a long input whose filter stops are dense from its
        // start to the DFA, which runs as it does without the matcher, so the matcher reads none
        // of it after its sample. A real ERROR every 10,007 bytes gives the DFA matches to find.
        byte[] bytes = nearMisses(4 * RECHECK_BYTES);
        int insertedMatches = 0;
        for (int position = 5_000; position + 5 <= bytes.length; position += 10_007) {
            System.arraycopy("ERROR".getBytes(UTF_8), 0, bytes, position, 5);
            insertedMatches++;
        }
        Slice input = wrappedBuffer(bytes);
        Re2 pattern = Re2.compile(utf8Slice("ERROR|WARN|FATAL"));
        Re2 reference = Re2.compile(utf8Slice("ERROR|WARN|FATAL"));
        LiteralAlternationSpanMatcher scanner = pattern.literalAlternationSpanMatcherForDiagnostics();
        assertThat(shape(pattern)).isEqualTo(CompiledPattern.Shape.LITERAL_ALTERNATION);
        // The reference runs the same count and find on the DFA directly, which also builds the
        // DFA counters the hand-off must reproduce.
        long dfaCount = Dfa.countMatches(reference.forwardProgramForDiagnostics(), input, Prog.MatchKind.FIRST_MATCH);
        assertThat(dfaCount).isEqualTo(insertedMatches);
        assertThat(reference.matchInto(input, CompiledPattern.Anchor.UNANCHORED, null)).isTrue();
        assertThat(pattern.countMatches(input)).isEqualTo(dfaCount);
        assertThat(pattern.find(input)).isTrue();
        // With the Vector API each call samples the filter's stops once and declines, so the
        // matcher reads no position; without it there is no filter, and the matcher keeps both.
        assertThat(scanner.filterStopCountForDiagnostics()).isZero();
        assertThat(scanner.rootTablePositionCountForDiagnostics() == 0).isEqualTo(VectorSupport.isAvailable());
        assertThat(scanner.sampledPositionCountForDiagnostics()).isEqualTo(VectorSupport.isAvailable() ? 2L * Dfa.BYTE_SCAN_SAMPLE_SIZE : 0);
        if (VectorSupport.isAvailable()) {
            assertThat(dfaScanCounters(pattern)).isNotEmpty().isEqualTo(dfaScanCounters(reference));
        }

        // Only the check at the start hands off. Here it samples three windows of sparse log lines
        // and one of near misses, so the matcher keeps the input and reads the root table where a
        // later check samples mostly near misses.
        int logLines = 9_362;
        int nearMissBytes = 3 * RECHECK_BYTES - 4 * 1024;
        Slice mixed = utf8Slice("WARN disk usage above threshold on node 7\n".repeat(logLines) + nearMissText(nearMissBytes));
        List<String> dfaCounters = dfaScanCounters(pattern);
        long rootTablePositions = scanner.rootTablePositionCountForDiagnostics();
        assertThat(pattern.count(mixed)).isEqualTo(logLines);
        assertThat(dfaScanCounters(pattern)).isEqualTo(dfaCounters);
        assertThat(scanner.filterStopCountForDiagnostics() > 0).isEqualTo(VectorSupport.isAvailable());
        assertThat(scanner.rootTablePositionCountForDiagnostics() - rootTablePositions).isGreaterThanOrEqualTo(RECHECK_BYTES);

        // Find follows the same rule: in the same layout without matches, the later dense checks
        // read the root table rather than hand off.
        Slice quiet = utf8Slice("INFO disk usage above threshold on node 7\n".repeat(logLines) + nearMissText(nearMissBytes));
        assertThat(pattern.find(quiet)).isFalse();
        assertThat(dfaScanCounters(pattern)).isEqualTo(dfaCounters);
    }

    // Near misses of ERROR|WARN|FATAL that its start literal filter passes, one every five bytes.
    private static String nearMissText(int length)
    {
        return "ERxO WAxN FAxA ".repeat(length / 15 + 1).substring(0, length);
    }

    private static byte[] nearMisses(int length)
    {
        return nearMissText(length).getBytes(UTF_8);
    }

    // The scan counters of each DFA the pattern's forward program has built.
    private static List<String> dfaScanCounters(CompiledPattern pattern)
    {
        List<String> counters = new ArrayList<>();
        for (Dfa.DfaInstance.Kind kind : Dfa.DfaInstance.Kind.values()) {
            Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(kind);
            if (dfa != null) {
                counters.add(kind + " fallbacks " + dfa.byteScanFallbackCount() +
                        " filter scans " + dfa.startLiteralFilterScanCount() +
                        " small set scans " + dfa.smallByteSetScanCount() +
                        " mixed set scans " + dfa.mixedByteSetScanCount() +
                        " range scans " + dfa.contiguousRangeScanCount() +
                        " self-loop count scans " + dfa.countSelfLoopScanCount());
            }
        }
        return counters;
    }

    // The literals, prefixes of them, which no literal matches, single bytes and two-byte
    // runs of them that a filter may accept, spaces, and malformed UTF-8, ending with a literal.
    private static byte[] literalText(Random random, String[] literals, int length)
    {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        while (text.size() < length) {
            byte[] literal = literals[random.nextInt(literals.length)].getBytes(UTF_8);
            switch (random.nextInt(12)) {
                case 0 -> text.writeBytes(literal);
                case 1, 2, 3 -> text.write(literal, 0, 1 + random.nextInt(literal.length));
                case 4 -> text.write(' ');
                case 5 -> text.write(new byte[] {(byte) 0xFF, (byte) 0x80, (byte) 0xE5}[random.nextInt(3)]);
                default -> {
                    int index = random.nextInt(literal.length);
                    text.write(literal, index, Math.min(2, literal.length - index));
                }
            }
        }
        text.writeBytes(literals[random.nextInt(literals.length)].getBytes(UTF_8));
        return text.toByteArray();
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
    private static void assertLiteralSetFind(CompiledPattern pattern, Slice input)
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
    private static void assertFindAgreement(CompiledPattern pattern, Slice input)
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

    // Compiles `expression` as RE2, Java, Rust, and Rust byte mode, keyed by a failure label.
    // Trino's literal-alternation subtype is covered separately.
    private static Map<String, CompiledPattern> re2JavaAndRustPatterns(String expression)
    {
        return Map.of(
                "RE2 " + expression, Re2.compile(utf8Slice(expression)),
                "Java " + expression, JavaRegexp.compile(utf8Slice(expression)),
                "Rust " + expression, RustRegexp.compile(utf8Slice(expression)),
                "Rust bytes (?-u)" + expression, RustRegexp.compile(utf8Slice("(?-u)" + expression), RustRegexp.Options.bytes()));
    }

    private static long generalEngineCount(CompiledPattern pattern, Slice input)
    {
        RegexpMatcher matcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
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
