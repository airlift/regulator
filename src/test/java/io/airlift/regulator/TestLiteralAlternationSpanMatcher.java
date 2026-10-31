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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

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
        Map<String, Re2> compiledPatterns = new LinkedHashMap<>();
        for (String expression : patterns) {
            compiledPatterns.putAll(re2JavaAndRustPatterns(expression));
        }
        // A Rust byte-mode escape is a single byte, not a code point.
        compiledPatterns.put("Rust bytes (?-u)ERROR|\\xFF\\xFE", RustRegexp.compile(utf8Slice("(?-u)ERROR|\\xFF\\xFE"), RustRegexp.Options.bytes()).re2());
        for (Map.Entry<String, Re2> entry : compiledPatterns.entrySet()) {
            String label = entry.getKey();
            Re2 pattern = entry.getValue();
            assertThat(pattern.booleanPartialMatchStrategyForDiagnostics()).as(label).isEqualTo(Re2.BooleanPartialMatchStrategy.LITERAL_ALTERNATION);
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
        assertThat(Re2.compile(utf8Slice("xa|b|xab"), Re2.Options.defaults().setLongestMatch(true)).booleanPartialMatchStrategyForDiagnostics())
                .isEqualTo(Re2.BooleanPartialMatchStrategy.GENERAL);
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
        assertThat(TrinoRegexp.compile(utf8Slice("a".repeat(64) + "b|x")).literalAlternationSpanMatcher()).isNull();

        assertThatThrownBy(() -> TrinoRegexp.compile(
                utf8Slice("ERROR|WARN|FATAL"),
                TrinoRegexp.Options.defaults().setMaxMemory(matcher.estimatedRetainedSize())))
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

    // Compiles `expression` as RE2, Java, Rust, and Rust byte mode, keyed by a failure label.
    // Trino's literal-alternation subtype is covered separately.
    private static Map<String, Re2> re2JavaAndRustPatterns(String expression)
    {
        return Map.of(
                "RE2 " + expression, Re2.compile(utf8Slice(expression)),
                "Java " + expression, JavaRegexp.compile(utf8Slice(expression)).re2(),
                "Rust " + expression, RustRegexp.compile(utf8Slice(expression)).re2(),
                "Rust bytes (?-u)" + expression, RustRegexp.compile(utf8Slice("(?-u)" + expression), RustRegexp.Options.bytes()).re2());
    }

    private static long generalEngineCount(Re2 pattern, Slice input)
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
