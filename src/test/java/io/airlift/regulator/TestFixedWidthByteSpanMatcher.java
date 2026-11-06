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
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.airlift.regulator.CompiledPattern.shouldAnalyzeFixedWidthByteSpanForTrino;
import static io.airlift.regulator.TestingPatternShapes.assertProgramSearch;
import static io.airlift.regulator.TestingPatternShapes.assertProgramSearchUnless;
import static io.airlift.regulator.TestingPatternShapes.shape;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestFixedWidthByteSpanMatcher
{
    @Test
    public void testTrinoCompactAnalysisEligibility()
    {
        assertThat(shouldAnalyzeFixedWidthByteSpanForTrino(
                TrinoRegexpParser.parse(utf8Slice("literal"), Regexp.LIKE_PERL))).isFalse();
        assertThat(shouldAnalyzeFixedWidthByteSpanForTrino(
                TrinoRegexpParser.parse(utf8Slice("[0-9]{2}-[0-9]{2}"), Regexp.LIKE_PERL))).isTrue();
        assertThat(shouldAnalyzeFixedWidthByteSpanForTrino(
                TrinoRegexpParser.parse(utf8Slice("([0-9]{2}-[0-9]{2})"), Regexp.LIKE_PERL))).isFalse();
    }

    @Test
    public void testTrinoFixedWidthRouteDoesNotRetainUnusedSemanticProgram()
    {
        Slice expression = utf8Slice("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        TrinoRegexp regexp = TrinoRegexp.compile(expression);
        TrinoCompiledPattern pattern = regexp.re2();

        // Pinned re2_golden reports group zero at 1:37 for this unanchored search.
        Slice input = utf8Slice("!123e4567-e89b-12d3-a456-426614174000?");
        assertThat(regexp.contains(input)).isTrue();
        assertThat(regexp.extract(input)).isEqualTo(input.slice(1, 36));
        assertThat(regexp.count(input)).isEqualTo(1);
        assertThat(regexp.replace(input, utf8Slice("<$0>")))
                .isEqualTo(utf8Slice("!<123e4567-e89b-12d3-a456-426614174000>?"));
        assertThatThrownBy(() -> regexp.replace(input, utf8Slice("$1")))
                .isInstanceOf(TrinoRegexpReplacementException.class);
        assertThat(regexp.contains(utf8Slice("not a uuid"))).isFalse();

        Slice repeated = utf8Slice("!123e4567-e89b-12d3-a456-426614174000?123e4567-e89b-12d3-a456-426614174001.");
        assertThat(regexp.count(repeated)).isEqualTo(2);
        assertThat(regexp.position(repeated, 1, 2)).isEqualTo(39);
        assertThat(regexp.extractAll(repeated)).containsExactly(
                utf8Slice("123e4567-e89b-12d3-a456-426614174000"),
                utf8Slice("123e4567-e89b-12d3-a456-426614174001"));
        assertThat(regexp.split(repeated)).containsExactly(utf8Slice("!"), utf8Slice("?"), utf8Slice("."));
        assertThat(regexp.replace(repeated, _ -> utf8Slice("X"))).isEqualTo(utf8Slice("!X?X."));

        // The fixed-width Trino subtype handles every public operation from the retained byte
        // predicates; its compiled pattern uses them for boolean find and count. Its semantic
        // program must therefore be the compact sentinel rather than the otherwise unused
        // 37-state OnePass program.
        assertThat(pattern.hasRetainedFixedWidthByteSpanMatcher()).isTrue();
        assertThat(shape(pattern)).isEqualTo(CompiledPattern.Shape.FIXED_WIDTH);
        assertThat(pattern.forwardProgramForDiagnostics().isOnePass()).isFalse();

        // Only Trino's fixed-width route drops the semantic program.
        assertThat(Re2.compile(expression).forwardProgramForDiagnostics().isOnePass()).isTrue();
    }

    @Test
    public void testCompactTrinoRouteMemoryAccounting()
    {
        Slice expression = utf8Slice("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        long maxMemory = 4 * 1024;
        TrinoCompiledPattern pattern = TrinoRegexp.compile(expression, TrinoRegexp.Options.defaults().setMaxMemory(maxMemory)).re2();
        FixedWidthByteSpanMatcher matcher = pattern.createFixedWidthByteSpanMatcher();
        assertThat(pattern.hasRetainedFixedWidthByteSpanMatcher()).isTrue();
        assertThat(Compiler.estimatedProgramMemory(pattern.forwardProgramForDiagnostics()) + matcher.estimatedRetainedSize())
                .isLessThanOrEqualTo(maxMemory - (maxMemory / 3));

        assertThatThrownBy(() -> TrinoRegexp.compile(expression, TrinoRegexp.Options.defaults().setMaxMemory(512)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    @Test
    public void testPinnedNativeBoundaries()
    {
        FixedWidthByteSpanMatcher dates = matcher("[0-9]{4}-[0-9]{2}-[0-9]{2}");
        Slice dateInput = utf8Slice("bad 2026/08/10 then 2025-12-09 and 2024-01-31");
        assertSpan(dates, dateInput, 0, 20, 30);
        assertSpan(dates, dateInput, 30, 35, 45);

        FixedWidthByteSpanMatcher phones = matcher("\\d{3}/\\d{3}/\\d{4}");
        Slice phoneInput = utf8Slice("bad 415-555-0100 then 212/555/0101 or 650/555/0142");
        assertSpan(phones, phoneInput, 4, 22, 34);
        assertSpan(phones, phoneInput, 34, 38, 50);
    }

    @Test
    public void testFalseCandidatesLogicalOffsetsAndAdjacentMatches()
    {
        FixedWidthByteSpanMatcher matcher = matcher("[0-9]{2}-[0-9]{2}");
        Slice backing = utf8Slice("!xx-yy 12-x4 12-3412-34?");
        Slice input = Slices.wrappedBuffer(backing.byteArray(), backing.byteArrayOffset() + 1, backing.length() - 2);
        assertSpan(matcher, input, 0, 12, 17);
        assertSpan(matcher, input, 17, 17, 22);
        assertThat(matcher.findSpan(input, 22)).isEqualTo(Dfa.SEARCH_NO_MATCH);

        // A region search reports only a match lying entirely in the region.
        assertThat(matcher.findSpan(input, 0, 16)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        assertThat(matcher.findSpan(input, 0, 17)).isEqualTo(span(12, 17));
        assertThat(matcher.findSpan(input, 13, 22)).isEqualTo(span(17, 22));
        assertThat(matcher.findSpan(input, 13, 21)).isEqualTo(Dfa.SEARCH_NO_MATCH);
    }

    @Test
    public void testMalformedUtf8AndLatin1()
    {
        FixedWidthByteSpanMatcher matcher = matcher("[0-9]{2}-[0-9]{2}");
        Slice malformed = wrappedBuffer(new byte[] {
                '1', (byte) 0xFF, '-', '3', '4', ' ', '1', '2', '-', '3', '4',
        });
        assertSpan(matcher, malformed, 0, 6, 11);

        Re2 latin1Pattern = Re2.compile(utf8Slice("[0-9]{2}-[0-9]{2}"), Re2.Options.latin1());
        FixedWidthByteSpanMatcher latin1 = latin1Pattern.createFixedWidthByteSpanMatcher();
        assertThat(latin1).isNotNull();
        assertSpan(latin1, wrappedBuffer(new byte[] {(byte) 0xFF, '1', '2', '-', '3', '4'}), 0, 1, 6);
    }

    @Test
    public void testEligibility()
    {
        assertThat(optionalMatcher("[0-9]{4}-[0-9]{2}-[0-9]{2}")).isNotNull();
        assertThat(optionalMatcher("\\d{3}/\\d{3}/\\d{4}")).isNotNull();
        assertThat(optionalMatcher("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}")).isNotNull();
        assertThat(optionalMatcher("literal")).isNull();
        assertThat(optionalMatcher("[0-9]{4}")).isNull();
        assertThat(optionalMatcher("[,;]")).isNull();
        assertThat(optionalMatcher("[0-9]+-[0-9]+")).isNull();
        assertThat(optionalMatcher("\"([^\"]*)\"")).isNull();
        assertThat(optionalMatcher("([0-9]{2}-[0-9]{2})")).isNull();
        assertThat(optionalMatcher("^[0-9]{2}-[0-9]{2}")).isNull();
        assertThat(optionalMatcher("[0-9]{2}-[0-9]{2}$")).isNull();
        assertThat(optionalMatcher("(?i:[a-f]{2}-[a-f]{2})")).isNull();
        assertThat(optionalMatcher("[é]{2}-[0-9]{2}")).isNull();
        assertThat(optionalMatcher("(?:[0-9]{2}|[a-z]{2})-[0-9]{2}")).isNull();
    }

    @Test
    public void testGeneralEngineAgreement()
    {
        List<String> patterns = List.of(
                "[0-9]{4}-[0-9]{2}-[0-9]{2}",
                // Not \d, because Rust's Unicode \d is not a one-byte class.
                "[0-9]{3}/[0-9]{3}/[0-9]{4}",
                "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}");
        List<Slice> inputs = List.of(
                utf8Slice(""),
                utf8Slice("ordinary text"),
                utf8Slice("2025-12-09"),
                utf8Slice("bad 2026/08/10 then 2025-12-09 and 2024-01-31"),
                utf8Slice("212/555/0101 or 650/555/0142"),
                utf8Slice("01234567-89ab"),
                utf8Slice("é💰2025-12-09ж終"),
                wrappedBuffer(new byte[] {'1', (byte) 0xFF, '-', '3', '4'}),
                wrappedBuffer("\u00FF2025-12-09\u00C3212/555/0101\u00E201234567-89ab2024-01-31\u00F0".getBytes(ISO_8859_1)));

        // Boolean find and count of RE2, Java, and Rust patterns run on the matcher and agree
        // with the general engine, including on malformed UTF-8.
        for (String expression : patterns) {
            for (Map.Entry<String, CompiledPattern> entry : re2JavaAndRustPatterns(expression).entrySet()) {
                String label = entry.getKey();
                CompiledPattern pattern = entry.getValue();
                assertThat(shape(pattern)).as(label).isEqualTo(CompiledPattern.Shape.FIXED_WIDTH);
                // Count answers from the matcher without building the general engine's DFA.
                pattern.count(inputs.getLast());
                assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).as(label).isNull();
                for (Slice input : inputs) {
                    long expectedCount = generalEngineCount(pattern, input);
                    assertThat(pattern.find(input)).as("%s input %s", label, input.toStringUtf8()).isEqualTo(expectedCount > 0);
                    assertThat(pattern.count(input)).as("%s input %s", label, input.toStringUtf8()).isEqualTo(expectedCount);
                }
            }
        }

        for (String expression : patterns) {
            Re2 pattern = Re2.compile(utf8Slice(expression));
            FixedWidthByteSpanMatcher matcher = pattern.createFixedWidthByteSpanMatcher();
            assertThat(matcher).as(expression).isNotNull();
            for (Slice input : inputs) {
                for (int start = 0; start <= input.length(); start++) {
                    RegexpMatcher generalMatcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
                    long span = matcher.findSpan(input, start);
                    if (generalMatcher.find(start)) {
                        assertThat(span)
                                .as("pattern %s input %s start %s", expression, input, start)
                                .isEqualTo(span(generalMatcher.start(), generalMatcher.end()));
                    }
                    else {
                        assertThat(span)
                                .as("pattern %s input %s start %s", expression, input, start)
                                .isEqualTo(Dfa.SEARCH_NO_MATCH);
                    }
                    // A region search reports the general engine's first match in the region.
                    for (int end = start; end <= input.length(); end++) {
                        long expected = generalMatcher.reset(input, start, end).find() ? span(generalMatcher.start(), generalMatcher.end()) : Dfa.SEARCH_NO_MATCH;
                        assertThat(matcher.findSpan(input, start, end)).as("pattern %s input %s region %s-%s", expression, input, start, end).isEqualTo(expected);
                    }
                }
            }
        }
    }

    @Test
    public void testMultiByteInnerLiteralKeepsTheDfaExceptOnShortSearches()
    {
        // The matcher would check every s for [a-z]shing, which English text holds far more often
        // than shing, so boolean find, and count and matcher find on long inputs, keep the DFA's
        // search for the inner literal; the last block below covers the short searches.
        for (Map.Entry<String, CompiledPattern> entry : re2JavaAndRustPatterns("[a-z]shing").entrySet()) {
            String label = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(pattern.hasRetainedFixedWidthByteSpanMatcher()).as(label).isFalse();
            assertThat(shape(pattern)).as(label).isEqualTo(CompiledPattern.Shape.ENGINE);
            assertThat(pattern.innerLiteralSearchForDiagnostics().literals()).as(label).isDeepEqualTo(new byte[][] {"shing".getBytes(ISO_8859_1)});
        }

        // Trino follows the same rule: its plain subtype answers contains, and count on long
        // inputs, on the ordinary engine, which searches for shing, and agrees with RE2's general
        // engine.
        TrinoRegexp trino = TrinoRegexp.compile(utf8Slice("[a-z]shing"));
        assertThat(trino).isExactlyInstanceOf(TrinoRegexp.class);
        assertThat(trino.re2().hasRetainedFixedWidthByteSpanMatcher()).isFalse();
        assertThat(trino.re2().innerLiteralSearchForDiagnostics().literals()).isDeepEqualTo(new byte[][] {"shing".getBytes(ISO_8859_1)});
        Re2 reference = Re2.compile(utf8Slice("[a-z]shing"));
        String prose = "the status of these sessions is still unsettled, so we must assess its uses. ";
        for (String text : List.of("", "fishing", prose, prose + "go fishing, then washing", "shing ashing " + prose)) {
            Slice input = utf8Slice(text);
            long expectedCount = generalEngineCount(reference, input);
            assertThat(trino.contains(input)).as(text).isEqualTo(expectedCount > 0);
            assertThat(trino.count(input)).as(text).isEqualTo(expectedCount);
        }

        // The inner literal search runs only on at least MINIMUM_LONG_SEARCH_BYTES bytes, so on
        // fewer every language, Trino included, counts and finds matcher spans with a second,
        // short-search fixed-width matcher, which builds no program search, while full-Slice find
        // stays on the engine. Each search covers the last 63 or 64 bytes of a 1024-byte input
        // that ends in ishing, so the find that resumes after the match has no bytes left.
        byte[] bytes = "x".repeat(1024).getBytes(ISO_8859_1);
        System.arraycopy("ishing".getBytes(ISO_8859_1), 0, bytes, 1018, 6);
        Slice longInput = wrappedBuffer(bytes);
        for (int length : List.of(63, 64)) {
            Slice input = longInput.slice(longInput.length() - length, length);
            boolean shortSearch = length < CompiledPattern.MINIMUM_LONG_SEARCH_BYTES;
            for (Map.Entry<String, CompiledPattern> entry : shingPatterns().entrySet()) {
                String label = "%s count of %s bytes".formatted(entry.getKey(), length);
                CompiledPattern pattern = entry.getValue();
                assertThat(pattern.shortFixedWidthByteMatcher()).as(label).isNotNull();
                assertThat(pattern.count(input)).as(label).isEqualTo(1);
                assertProgramSearchUnless(shortSearch, pattern, label);
            }
            for (Map.Entry<String, CompiledPattern> entry : shingPatterns().entrySet()) {
                String label = "%s matcher find with %s bytes left".formatted(entry.getKey(), length);
                CompiledPattern pattern = entry.getValue();
                RegexpMatcher matcher = pattern.matcher(longInput, 0);
                assertThat(matcher.find(longInput.length() - length)).as(label).isTrue();
                assertThat(List.of(matcher.start(), matcher.end())).as(label).containsExactly(1018, 1024);
                assertThat(matcher.find()).as(label).isFalse();
                assertProgramSearchUnless(shortSearch, pattern, label);
            }
            for (Map.Entry<String, CompiledPattern> entry : shingPatterns().entrySet()) {
                String label = "%s find of %s bytes".formatted(entry.getKey(), length);
                assertThat(entry.getValue().find(input)).as(label).isTrue();
                assertProgramSearch(entry.getValue(), label);
            }
        }

        // A one-byte inner literal is no rarer than the matcher's byte, so Trino keeps the
        // matcher on its route that compiles no program.
        for (String expression : List.of("[a-z]{8}-[0-9]{4}", "\\d{4}-\\d{2}-\\d{2}")) {
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(regexp).as(expression).isInstanceOf(TrinoRegexp.FixedWidthByteTrinoRegexp.class);
            assertThat(regexp.re2().forwardProgramForDiagnostics().start()).as(expression).isZero();
        }
    }

    @Test
    public void testTrinoMaxMemoryOfFixedWidthPatterns()
    {
        // Each pattern compiles from the same maxMemory as when every Trino fixed-width pattern
        // took the route that compiles no program; below it neither the matcher nor a program
        // fits. Up to 4 KiB the inner literal search of a shing pattern does not fit, so the
        // matcher is the route. The last pattern compiles a program only from a maxMemory beyond
        // the 4 KiB checked here, so from 1729 it compiles only on the route without one.
        Map<String, Long> minimumMaxMemory = Map.of(
                "[a-z]shing", 1487L,
                "\\d{4}-\\d{2}-\\d{2}", 1487L,
                "[0-9a-fA-F]{8}shing[0-9a-fA-F]{12}", 1729L);
        for (Map.Entry<String, Long> entry : minimumMaxMemory.entrySet()) {
            Slice expression = utf8Slice(entry.getKey());
            for (long maxMemory = 256; maxMemory <= 4096; maxMemory++) {
                TrinoRegexp.Options options = TrinoRegexp.Options.defaults().setMaxMemory(maxMemory);
                String label = "%s at %s".formatted(entry.getKey(), maxMemory);
                if (maxMemory < entry.getValue()) {
                    assertThatThrownBy(() -> TrinoRegexp.compile(expression, options)).as(label).isInstanceOf(RegexpCompileMemoryLimitException.class);
                }
                else {
                    assertThat(TrinoRegexp.compile(expression, options)).as(label).isInstanceOf(TrinoRegexp.FixedWidthByteTrinoRegexp.class);
                }
            }
        }
        TrinoRegexp fallback = TrinoRegexp.compile(utf8Slice("[0-9a-fA-F]{8}shing[0-9a-fA-F]{12}"), TrinoRegexp.Options.defaults().setMaxMemory(1729));
        assertThat(fallback.re2().forwardProgramForDiagnostics().start()).isZero();

        // Where the program fits but neither the inner literal search nor the matcher fits beside
        // it, the pattern takes the route without a program, which reserves the matcher.
        Map<String, List<Long>> matcherOnlyMaxMemory = Map.of(
                "[a-z]shing", List.of(2567L, 2797L),
                "[a-z]{2}ing", List.of(2387L, 2604L));
        for (Map.Entry<String, List<Long>> entry : matcherOnlyMaxMemory.entrySet()) {
            for (long maxMemory = entry.getValue().get(0); maxMemory <= entry.getValue().get(1); maxMemory++) {
                String label = "%s at %s".formatted(entry.getKey(), maxMemory);
                TrinoCompiledPattern pattern = TrinoRegexp.compile(utf8Slice(entry.getKey()), TrinoRegexp.Options.defaults().setMaxMemory(maxMemory)).re2();
                assertThat(pattern.hasRetainedFixedWidthByteSpanMatcher()).as(label).isTrue();
                assertThat(pattern.forwardProgramForDiagnostics().start()).as(label).isZero();
            }
        }
    }

    // Compiles `expression` as RE2, Java, Rust, and Rust byte mode, keyed by a failure label.
    // Trino's fixed-width subtype is covered separately.
    private static Map<String, CompiledPattern> re2JavaAndRustPatterns(String expression)
    {
        return Map.of(
                "RE2 " + expression, Re2.compile(utf8Slice(expression)),
                "Java " + expression, JavaRegexp.compile(utf8Slice(expression)),
                "Rust " + expression, RustRegexp.compile(utf8Slice(expression)),
                "Rust bytes (?-u)" + expression, RustRegexp.compile(utf8Slice("(?-u)" + expression), RustRegexp.Options.bytes()));
    }

    // Freshly compiles [a-z]shing in every language, Trino included, so no earlier search has
    // built a DFA.
    private static Map<String, CompiledPattern> shingPatterns()
    {
        Map<String, CompiledPattern> patterns = new LinkedHashMap<>(re2JavaAndRustPatterns("[a-z]shing"));
        patterns.put("Trino [a-z]shing", TrinoRegexp.compile(utf8Slice("[a-z]shing")).re2());
        return patterns;
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

    private static FixedWidthByteSpanMatcher matcher(String pattern)
    {
        FixedWidthByteSpanMatcher matcher = optionalMatcher(pattern);
        assertThat(matcher).isNotNull();
        return matcher;
    }

    private static FixedWidthByteSpanMatcher optionalMatcher(String pattern)
    {
        return Re2.compile(utf8Slice(pattern)).createFixedWidthByteSpanMatcher();
    }

    private static long span(int start, int end)
    {
        return ((long) start << 32) | (end & 0xFFFF_FFFFL);
    }

    private static void assertSpan(FixedWidthByteSpanMatcher matcher, Slice input, int searchStart, int expectedStart, int expectedEnd)
    {
        long span = matcher.findSpan(input, searchStart);
        assertThat((int) (span >>> 32)).isEqualTo(expectedStart);
        assertThat((int) span).isEqualTo(expectedEnd);
    }
}
