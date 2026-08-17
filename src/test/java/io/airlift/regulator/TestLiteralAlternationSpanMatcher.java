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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
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
                    Re2Matcher matcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
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
    }

    @Test
    public void testPublicRouteAndMemoryAccounting()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL"));
        LiteralAlternationSpanMatcher matcher = regexp.literalAlternationSpanMatcher();
        assertThat(matcher).isNotNull();
        assertThat(regexp.pattern()).isNull();
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
