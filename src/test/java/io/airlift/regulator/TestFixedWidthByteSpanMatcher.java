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

import io.airlift.joni.Regex;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import static io.airlift.regulator.CompiledPattern.shouldAnalyzeFixedWidthByteSpanForTrino;
import static io.airlift.regulator.TestingPatternShapes.assertNoProgramSearch;
import static io.airlift.regulator.TestingPatternShapes.assertProgramSearch;
import static io.airlift.regulator.TestingPatternShapes.assertProgramSearchUnless;
import static io.airlift.regulator.TestingPatternShapes.shape;
import static io.airlift.regulator.TestingTrinoJoni.joniMatches;
import static io.airlift.regulator.TestingTrinoJoni.joniPattern;
import static io.airlift.regulator.TestingTrinoJoni.verifyOperations;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        long maxMemory = 6 * 1024;
        TrinoCompiledPattern pattern = TrinoRegexp.compile(expression, TrinoRegexp.Options.defaults().setMaxMemory(maxMemory)).re2();
        FixedWidthByteSpanMatcher matcher = pattern.fixedWidthByteSpanMatcherForDiagnostics();
        assertThat(matcher).isNotNull();
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
        assertThat(matcher.findSpanOrBail(input, 0, 16)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        assertThat(matcher.findSpanOrBail(input, 0, 17)).isEqualTo(span(12, 17));
        assertThat(matcher.findSpanOrBail(input, 13, 22)).isEqualTo(span(17, 22));
        assertThat(matcher.findSpanOrBail(input, 13, 21)).isEqualTo(Dfa.SEARCH_NO_MATCH);

        // The character search scans for a one-byte candidate, here the x, with the vector scan in
        // a search of at least 1,024 candidate positions where the Vector API is available. A
        // false candidate and the match lie well past the first vectors of a view that starts
        // inside its array, and the match ends the view.
        FixedWidthByteSpanMatcher ending = matcher("[a-q][^u-z]{13}x");
        String filler = "b".repeat(1100);
        Slice longBacking = utf8Slice("!" + filler + "zx" + filler + "x?");
        Slice longInput = wrappedBuffer(longBacking.byteArray(), longBacking.byteArrayOffset() + 1, longBacking.length() - 2);
        int matchEnd = longInput.length();
        assertSpan(ending, longInput, 0, matchEnd - 15, matchEnd);
        assertThat(ending.count(longInput)).isEqualTo(1);
        assertThat(ending.findSpanOrBail(longInput, 0, matchEnd - 1)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        assertThat(ending.findSpanOrBail(longInput, 0, matchEnd)).isEqualTo(span(matchEnd - 15, matchEnd));

        // The byte search does the same, and a long count keeps the vector scan for the short rest
        // of the input after a match: a match at the start, then a false `-` candidate and
        // adjacent matches at the end.
        Slice longDatesBacking = utf8Slice("!12-34" + filler + "12-x4 12-3412-34?");
        Slice longDates = wrappedBuffer(longDatesBacking.byteArray(), longDatesBacking.byteArrayOffset() + 1, longDatesBacking.length() - 2);
        int lateMatch = longDates.length() - 10;
        assertThat(matcher.count(longDates)).isEqualTo(3);
        assertSpan(matcher, longDates, 1, lateMatch, lateMatch + 5);
        assertThat(matcher.findSpanOrBail(longDates, 1, longDates.length() - 1)).isEqualTo(span(lateMatch, lateMatch + 5));

        // A start past the end finds nothing, even one so far past it that its distance to the
        // end would wrap into a long search.
        assertThat(matcher.findSpan(Slices.EMPTY_SLICE, Integer.MAX_VALUE)).isEqualTo(Dfa.SEARCH_NO_MATCH);
    }

    @Test
    public void testSingleByteVectorScanCutoff()
    {
        // A search with a one-byte candidate takes the vector scan once it has 1,024 candidate
        // positions, one for each match start, where the Vector API is available, whatever its
        // start.
        FixedWidthByteSpanMatcher ending = matcher("[a-q][^u-z]{13}x");
        int width = 15;
        for (int start : new int[] {0, 7}) {
            assertThat(ending.usesSingleByteVectorScan(utf8Slice("b".repeat(start + 1_023 + width - 1)), start)).isFalse();
            assertThat(ending.usesSingleByteVectorScan(utf8Slice("b".repeat(start + 1_024 + width - 1)), start)).isEqualTo(VectorSupport.isAvailable());
            assertThat(ending.usesSingleByteVectorScan(utf8Slice("b".repeat(start + 1_025 + width - 1)), start)).isEqualTo(VectorSupport.isAvailable());
        }

        // A candidate of several bytes, here the ten digits, keeps its byte-set scan.
        assertThat(matcher("[0-9]{4}").usesSingleByteVectorScan(utf8Slice("b".repeat(4_096)), 0)).isFalse();
    }

    @Test
    public void testMalformedUtf8AndLatin1()
    {
        FixedWidthByteSpanMatcher matcher = matcher("[0-9]{2}-[0-9]{2}");
        Slice malformed = wrappedBuffer(new byte[] {
                '1', (byte) 0xFF, '-', '3', '4', ' ', '1', '2', '-', '3', '4',
        });
        assertSpan(matcher, malformed, 0, 6, 11);

        FixedWidthByteSpanMatcher latin1 = optionalMatcher("[0-9]{2}-[0-9]{2}", Re2.Options.latin1());
        assertThat(latin1).isNotNull();
        assertSpan(latin1, wrappedBuffer(new byte[] {(byte) 0xFF, '1', '2', '-', '3', '4'}), 0, 1, 6);
    }

    @Test
    public void testEligibility()
    {
        assertThat(optionalMatcher("[0-9]{4}-[0-9]{2}-[0-9]{2}")).isNotNull();
        assertThat(optionalMatcher("\\d{3}/\\d{3}/\\d{4}")).isNotNull();
        assertThat(optionalMatcher("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}")).isNotNull();
        // Digits are rare enough in English text to scan for without a literal.
        assertThat(optionalMatcher("[0-9]{4}")).isNotNull();
        assertThat(optionalMatcher("[é]{2}-[0-9]{2}")).isNotNull();
        // At most 256 positions, by repeat or by concatenation.
        assertThat(optionalMatcher("[0-9]{256}")).isNotNull();
        assertThat(optionalMatcher("[0-9]{255}-")).isNotNull();
        assertThat(optionalMatcher("[0-9]{257}")).isNull();
        assertThat(optionalMatcher("[0-9]{256}-")).isNull();
        assertThat(optionalMatcher("literal")).isNull();
        assertThat(optionalMatcher("[,;]")).isNull();
        assertThat(optionalMatcher("[0-9]+-[0-9]+")).isNull();
        assertThat(optionalMatcher("\"([^\"]*)\"")).isNull();
        assertThat(optionalMatcher("([0-9]{2}-[0-9]{2})")).isNull();
        assertThat(optionalMatcher("^[0-9]{2}-[0-9]{2}")).isNull();
        assertThat(optionalMatcher("[0-9]{2}-[0-9]{2}$")).isNull();
        assertThat(optionalMatcher("(?i:[a-f]{2}-[a-f]{2})")).isNull();
        assertThat(optionalMatcher("(?:[0-9]{2}|[a-z]{2})-[0-9]{2}")).isNull();
        // A Rust pattern that mixes a byte class with UTF-8 classes is refused, since the byte
        // can be a continuation byte, and matches on the engine.
        RustRegexp mixed = RustRegexp.compile(utf8Slice("(?-u:[\\x80-\\xFF])[а-я]"), RustRegexp.Options.bytes());
        RustRegexp.Options bytes = RustRegexp.Options.bytes();
        assertThat(mixed.createFixedWidthByteSpanMatcher(RustRegexpParser.parse(utf8Slice("(?-u:[\\x80-\\xFF])[а-я]"), bytes.syntaxFlags(), bytes.nestLimit()).regexp())).isNull();
        Slice mixedInput = wrappedBuffer(bytes(0xD1, 0x8F, 0xD0, 0xB0, 0x20, 0xFF, 0xD0, 0xB1, 0xD0));
        assertThat(mixed.count(mixedInput)).isEqualTo(generalEngineCount(mixed, mixedInput)).isEqualTo(2);
    }

    @Test
    public void testWideClassPatternsTakeTheMatcher()
    {
        // Every position of [a-q][^u-z]{80}[x\xE0-\xFF] is a class. The forward DFA's subset
        // states grow with the repeat until it bails, so without the matcher every count runs
        // the NFA. The matcher scans for the rare last class, in Latin-1 its bytes and in UTF-8
        // the first bytes of its members, and checks the other positions around each hit.
        for (int repeat : List.of(16, 80, 160)) {
            String expression = "[a-q][^u-z]{%s}[x\\xE0-\\xFF]".formatted(repeat);
            Map<String, CompiledPattern> patterns = new LinkedHashMap<>(re2JavaAndRustPatterns(expression));
            patterns.put("RE2 Latin-1 " + expression, Re2.compile(utf8Slice(expression), Re2.Options.latin1()));
            // Trino rejects \xE0, so its form names the characters. The candidate holds a byte
            // above ASCII, so a search can bail, and the pattern keeps its program and answers
            // through the compiled pattern's routes.
            String trinoExpression = "[a-q][^u-z]{%s}[xà-ÿ]".formatted(repeat);
            TrinoRegexp trino = TrinoRegexp.compile(utf8Slice(trinoExpression));
            assertThat(trino).as(trinoExpression).isExactlyInstanceOf(TrinoRegexp.class);
            patterns.put("Trino " + trinoExpression, trino.re2());
            for (Map.Entry<String, CompiledPattern> entry : patterns.entrySet()) {
                assertThat(entry.getValue().hasRetainedFixedWidthByteSpanMatcher()).as(entry.getKey()).isTrue();
                assertThat(shape(entry.getValue())).as(entry.getKey()).isEqualTo(CompiledPattern.Shape.FIXED_WIDTH);
            }
        }

        // ASCII patterns wider than 64 positions take it too. Trino compiles the program to decide
        // the order, finds that no route of it precedes the matcher, and keeps none.
        String wideAscii = "[a-q][a-z ]{80}x";
        for (Map.Entry<String, CompiledPattern> entry : re2JavaAndRustPatterns(wideAscii).entrySet()) {
            assertThat(entry.getValue().hasRetainedFixedWidthByteSpanMatcher()).as(entry.getKey()).isTrue();
        }
        TrinoRegexp wideAsciiTrino = TrinoRegexp.compile(utf8Slice(wideAscii));
        assertThat(wideAsciiTrino).isInstanceOf(TrinoRegexp.FixedWidthByteTrinoRegexp.class);
        assertThat(wideAsciiTrino.re2().forwardProgramForDiagnostics().start()).isZero();

        // Where every position's first bytes are common in English text, analysis refuses the
        // pattern unless the block search takes it, and the engine keeps it. The search refuses a
        // pattern whose estimated starts are denser than once a block, \w\s\w; one of a single
        // class, a class repeat that the class-run counter counts, [a-z]{5} and \w{20}; one of
        // five classes; one with a class whose ranges and complement both exceed four, [aeiou];
        // and one wider than a block. \w{20}, the five classes, and [aeiou]\w{8}\s\w{3} estimate
        // 0.011, 0.0036, and 0.0042, under 1/64, so only their own limits refuse them.
        for (String expression : List.of(
                "\\w\\s\\w",
                "[a-z]{5}",
                "\\w{20}",
                "[a-z]{3}[b-z]{3}[c-z]{3}[d-z]{3}[e-z]{3}",
                "[aeiou]\\w{8}\\s\\w{3}",
                "\\w{60}\\s\\w{4}",
                "[a-q][^u-z]{80}[a-z]")) {
            Map<String, Regexp> trees = re2JavaAndRustTrees(expression);
            for (Map.Entry<String, CompiledPattern> entry : re2JavaAndRustPatterns(expression).entrySet()) {
                assertThat(entry.getValue().createFixedWidthByteSpanMatcher(trees.get(entry.getKey()))).as(entry.getKey()).isNull();
            }
            assertThat(TrinoRegexp.compile(utf8Slice(expression))).as(expression).isNotInstanceOf(TrinoRegexp.FixedWidthByteTrinoRegexp.class);
        }

        // An ASCII singleton pattern, of ASCII bytes and ASCII classes with a singleton position,
        // scans for its first singleton position, however common. A repeat of count zero adds no
        // position.
        for (String expression : List.of("[a-z]e[a-z]", "(?:a?){0}[a-z]-")) {
            for (Map.Entry<String, CompiledPattern> entry : re2JavaAndRustPatterns(expression).entrySet()) {
                assertThat(entry.getValue().hasRetainedFixedWidthByteSpanMatcher()).as(entry.getKey()).isTrue();
                assertThat(entry.getValue().fixedWidthByteSpanMatcherForDiagnostics().candidateOffsetForDiagnostics()).as(entry.getKey()).isEqualTo(1);
            }
        }
        // Any other pattern scans a rare position: here not the space, the commonest byte of
        // English text, but the first byte of е, which both its cases share. The forward DFA
        // searches for its start literal filter, three bytes of Шер, wherever the Vector API
        // runs, and there the engine keeps the pattern.
        CompiledPattern cyrillic = RustRegexp.compile(utf8Slice("(?i)Шерлок Холмс"));
        RustRegexp.Options rust = RustRegexp.Options.defaults();
        Regexp cyrillicTree = RustRegexpParser.parse(utf8Slice("(?i)Шерлок Холмс"), rust.syntaxFlags(), rust.nestLimit()).regexp();
        assertThat(cyrillic.createFixedWidthByteSpanMatcher(cyrillicTree).candidateOffsetForDiagnostics()).isEqualTo(1);
        assertThat(cyrillic.hasRetainedFixedWidthByteSpanMatcher()).isEqualTo(!VectorSupport.isAvailable());
        // [а-я]{3}[.,] keeps the engine the same way in every language. Its filter tests the lead
        // byte of the first letter and the [.,] six bytes later, which stop far less often than
        // the lead byte of every Cyrillic letter.
        String shortCyrillic = "[а-я]{3}[.,]";
        for (Map.Entry<String, CompiledPattern> entry : Map.of(
                "RE2 " + shortCyrillic, Re2.compile(utf8Slice(shortCyrillic)),
                "Java " + shortCyrillic, JavaRegexp.compile(utf8Slice(shortCyrillic)),
                "Rust " + shortCyrillic, RustRegexp.compile(utf8Slice(shortCyrillic)),
                "Trino " + shortCyrillic, TrinoRegexp.compile(utf8Slice(shortCyrillic)).re2()).entrySet()) {
            Prog program = entry.getValue().forwardProgramForDiagnostics();
            assertThat(Dfa.DfaInstance.searchesStartLiteralFilter(program, Dfa.DfaInstance.Kind.FIRST_MATCH)).as(entry.getKey()).isEqualTo(VectorSupport.isAvailable());
            assertThat(shape(entry.getValue())).as(entry.getKey()).isEqualTo(VectorSupport.isAvailable() ? CompiledPattern.Shape.ENGINE : CompiledPattern.Shape.FIXED_WIDTH);
        }
        // So it does where the forward program skips to a literal prefix, or the class-run
        // counter counts the pattern, in every language.
        for (String expression : List.of("abc[^u-z]{80}x", "[а-я]{5}")) {
            TrinoRegexp trino = TrinoRegexp.compile(utf8Slice(expression));
            Map<String, Regexp> trees = Map.of(
                    "RE2 " + expression, RegexpParser.parse(utf8Slice(expression), Re2.Options.defaults().parseFlags()).regexp(),
                    "Java " + expression, JavaRegexpParser.parse(utf8Slice(expression), JavaRegexp.Options.defaults().parseFlags()).regexp(),
                    "Rust " + expression, RustRegexpParser.parse(utf8Slice(expression), rust.syntaxFlags(), rust.nestLimit()).regexp(),
                    "Trino " + expression, TrinoRegexpParser.parse(utf8Slice(expression), Regexp.LIKE_PERL).regexp());
            for (Map.Entry<String, CompiledPattern> entry : Map.of(
                    "RE2 " + expression, Re2.compile(utf8Slice(expression)),
                    "Java " + expression, JavaRegexp.compile(utf8Slice(expression)),
                    "Rust " + expression, RustRegexp.compile(utf8Slice(expression)),
                    "Trino " + expression, trino.re2()).entrySet()) {
                assertThat(entry.getValue().createFixedWidthByteSpanMatcher(trees.get(entry.getKey()))).as(entry.getKey()).isNotNull();
                assertThat(entry.getValue().hasRetainedFixedWidthByteSpanMatcher()).as(entry.getKey()).isFalse();
            }
            assertThat(trino).as(expression).isNotInstanceOf(TrinoRegexp.FixedWidthByteTrinoRegexp.class);
        }
        // Trino decides the order on its program, as every language does, so where a forward DFA
        // searches for the start literal filter of these three-class prefixes, Trino keeps the
        // engine with RE2.
        for (String expression : List.of("[de][hi][no][a-z]{20}[0-9]", "[ac][eg][ln][0-9][a-z]{80}")) {
            assertThat(Re2.compile(utf8Slice(expression)).hasRetainedFixedWidthByteSpanMatcher()).as(expression).isEqualTo(!VectorSupport.isAvailable());
            TrinoRegexp trino = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(trino.re2().hasRetainedFixedWidthByteSpanMatcher()).as(expression).isEqualTo(!VectorSupport.isAvailable());
        }
        // The class-run counter keeps only a pattern whose candidate holds bytes above ASCII,
        // whose density English text does not show. The counter counts [0-9]{4} too, but the
        // matcher scans its digits, whose density it does show.
        String digits = "[0-9]{4}";
        for (Map.Entry<String, CompiledPattern> entry : Map.of(
                "RE2 " + digits, Re2.compile(utf8Slice(digits)),
                "Java " + digits, JavaRegexp.compile(utf8Slice(digits)),
                "Rust " + digits, RustRegexp.compile(utf8Slice(digits))).entrySet()) {
            assertThat(entry.getValue().classMatchCounter()).as(entry.getKey()).isNotNull();
            assertThat(entry.getValue().hasRetainedFixedWidthByteSpanMatcher()).as(entry.getKey()).isTrue();
        }
        assertThat(TrinoRegexp.compile(utf8Slice(digits))).isInstanceOf(TrinoRegexp.FixedWidthByteTrinoRegexp.class);
    }

    @Test
    public void testGeneralEngineAgreement()
    {
        List<Slice> inputs = new ArrayList<>(List.of(
                utf8Slice(""),
                utf8Slice("ordinary text"),
                utf8Slice("2025-12-09"),
                utf8Slice("bad 2026/08/10 then 2025-12-09 and 2024-01-31"),
                utf8Slice("212/555/0101 or 650/555/0142"),
                utf8Slice("01234567-89ab"),
                utf8Slice("é💰2025-12-09ж終"),
                wrappedBuffer(new byte[] {'1', (byte) 0xFF, '-', '3', '4'}),
                wrappedBuffer("\u00FF2025-12-09\u00C3212/555/0101\u00E201234567-89ab2024-01-31\u00F0".getBytes(ISO_8859_1)),
                // Matches at the start and the end, adjacent and overlapping ones, and a candidate
                // whose match would start before the input.
                utf8Slice("abcxbcdxcdex"),
                utf8Slice("xxabéxaàÿàbé"),
                utf8Slice("àbcdàbcdxyz"),
                utf8Slice("α12!一34💰56ж78"),
                utf8Slice("a一bc丏da\uFFFDba\uD7FFc"),
                utf8Slice("abαc ab一d ef💰g"),
                wrappedBuffer("\u00E9a\u00E0bc\u00FFx\u00C0de".getBytes(ISO_8859_1))));
        inputs.addAll(fragmentInputs());

        // Boolean find, count, and the span searches of RE2, Java, and Rust patterns run on the
        // matcher and agree with the general engine, including on malformed UTF-8 and Latin-1.
        for (String expression : List.of(
                "[0-9]{4}-[0-9]{2}-[0-9]{2}",
                // Not \d, because Rust's Unicode \d is not a one-byte class.
                "[0-9]{3}/[0-9]{3}/[0-9]{4}",
                "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}",
                // Classes above ASCII, read as bytes in Latin-1 and Rust's byte mode and as
                // characters elsewhere, as the scanned position last, first, and in the middle,
                // and a literal, beside classes that hold every non-ASCII character.
                "[a-q][^u-z]{3}[x\\xE0-\\xFF]",
                "[\\xE0-\\xFF][a-z]{3}",
                "[a-z]{2}[\\xC0-\\xFF][a-z]{2}",
                "[^a-z]\\xE9[a-z]",
                "[^0-9][0-9]{2}")) {
            Map<String, CompiledPattern> patterns = new LinkedHashMap<>(re2JavaAndRustPatterns(expression));
            patterns.put("RE2 Latin-1 " + expression, Re2.compile(utf8Slice(expression), Re2.Options.latin1()));
            assertGeneralEngineAgreement(patterns, inputs, TestFixedWidthByteSpanMatcher::everyRegionEnd);
        }
        // Members of every UTF-8 length, beside ASCII positions, holding the surrogates, and as
        // the scanned position, which Rust's byte mode cannot write.
        for (String expression : List.of(
                "[\\x{3B1}-\\x{3C9}\\x{4E00}-\\x{9FFF}\\x{1F4B0}-\\x{1F4B2}][0-9]{2}[^0-9]",
                "[a-z][\\x{4E00}-\\x{4E0F}][a-z]",
                "[a-z]{2}[\\x{3B1}\\x{4E00}\\x{1F4B0}][a-z]",
                "[a-z][\\x{800}-\\x{FFFF}][0-9]")) {
            assertGeneralEngineAgreement(Map.of(
                    "RE2 " + expression, Re2.compile(utf8Slice(expression)),
                    "Java " + expression, JavaRegexp.compile(utf8Slice(expression)),
                    "Rust " + expression, RustRegexp.compile(utf8Slice(expression))), inputs, TestFixedWidthByteSpanMatcher::everyRegionEnd);
        }

        // Trino's fixed-width subtype answers every operation from the matcher's spans, whose
        // characters here differ in byte width, and agrees with Joni on UTF-8 text. A pattern
        // whose candidate holds bytes above ASCII keeps its program, so a search can bail, and
        // answers through the compiled pattern's routes. Each pattern maps to whether it bails.
        List<Slice> trinoSources = new ArrayList<>();
        for (Slice input : inputs) {
            if (Utf8.firstInvalidOffset(input) < 0) {
                // Bytes on either side of the view must not reach results.
                trinoSources.add(utf8Slice("àb" + input.toStringUtf8() + "àb").slice(3, input.length()));
            }
        }
        Map<String, Boolean> trinoPatterns = Map.of(
                "[a-q][^u-z]{3}[xà-ÿ]", true,
                "[α-ω一-鿿💰-💲][0-9]{2}[^0-9]", false,
                "[a-z]{2}[α一💰][a-z]", true,
                "[^0-9][0-9]{2}", false);
        for (Map.Entry<String, Boolean> entry : trinoPatterns.entrySet()) {
            String expression = entry.getKey();
            boolean bails = entry.getValue();
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(shape(regexp.re2())).as(expression).isEqualTo(CompiledPattern.Shape.FIXED_WIDTH);
            assertThat(regexp.re2().fixedWidthByteSpanMatcherForDiagnostics().bailsOnDenseCandidates()).as(expression).isEqualTo(bails);
            // Only a matcher that never bails allows compiling no program, and each pattern here
            // whose matcher never bails compiled none, so its forward program is the placeholder
            // that starts at zero.
            assertThat(regexp.re2().forwardProgramForDiagnostics().start() == 0).as(expression).isEqualTo(!bails);
            if (bails) {
                assertThat(regexp).as(expression).isExactlyInstanceOf(TrinoRegexp.class);
            }
            else {
                assertThat(regexp).as(expression).isInstanceOf(TrinoRegexp.FixedWidthByteTrinoRegexp.class);
            }
            Regex oracle = joniPattern(expression);
            verifyOperations(expression, regexp, source -> joniMatches(oracle, source), trinoSources);
            // The compiled pattern's region and anchored matches agree with the program of the
            // same pattern in a group, on malformed rows too. The subtype's public operations
            // never reach these, and the patterns that bail keep their program.
            CompiledPattern reference = TrinoRegexp.compile(utf8Slice("(" + expression + ")")).re2();
            assertThat(shape(reference)).as(expression).isNotEqualTo(CompiledPattern.Shape.FIXED_WIDTH);
            for (Slice source : inputs) {
                assertRegionMatchAgreement("Trino " + expression, regexp.re2(), reference, source);
            }
        }
    }

    @Test
    public void testBlockSearch()
    {
        // No position of \w{5}\s\w{6}\s\w{7} is rare enough to scan for: English text holds word
        // bytes and whitespace far more often than once in 100 bytes. Where every position reads
        // one byte and the Vector API runs, the matcher tests every start of a 64-byte block at
        // once instead; VectorScannerProbe checks that without it the engine keeps the pattern.
        // Rust's Unicode \w and \s hold characters above ASCII, so there the pattern keeps the
        // engine.
        String words = "\\w{5}\\s\\w{6}\\s\\w{7}";
        assertThat(RustRegexp.compile(utf8Slice(words)).hasRetainedFixedWidthByteSpanMatcher()).isFalse();
        if (!VectorSupport.isAvailable()) {
            return;
        }
        assertBlockSearch(blockSearchPatterns(words));
        // Trino's \w and \s are ASCII classes, and its subtype answers every operation from the
        // search without a program.
        TrinoRegexp trino = TrinoRegexp.compile(utf8Slice(words));
        assertThat(trino).isInstanceOf(TrinoRegexp.FixedWidthByteTrinoRegexp.class);
        assertThat(trino.re2().fixedWidthByteSpanMatcherForDiagnostics().searchesBlocksForDiagnostics()).isTrue();
        assertThat(trino.re2().forwardProgramForDiagnostics().start()).isZero();

        // A pattern with a rare candidate keeps scanning it. testWideClassPatternsTakeTheMatcher
        // covers a singleton position and the patterns the search refuses.
        for (String expression : List.of("[0-9]{4}-[0-9]{2}-[0-9]{2}", "[a-q][^u-z]{16}[x\\xE0-\\xFF]")) {
            for (Map.Entry<String, CompiledPattern> entry : blockSearchPatterns(expression).entrySet()) {
                assertThat(entry.getValue().fixedWidthByteSpanMatcherForDiagnostics().searchesBlocksForDiagnostics()).as(entry.getKey()).isFalse();
            }
        }

        // Count, boolean find, and the span searches agree with the general engine on short
        // rows and malformed UTF-8, and on rows several blocks long of English words, digits,
        // and text above ASCII, whose matches start in every offset of a block and cross into
        // the next. The patterns hold two to four classes, a class of four ranges, and positions
        // at every offset of a block.
        List<Slice> shortInputs = new ArrayList<>(fragmentInputs());
        shortInputs.addAll(List.of(
                utf8Slice("Holmes, which there lived a"),
                utf8Slice("about which Holmes thought\nawhile"),
                utf8Slice("Ab_9z w0rd_x abcdefg"),
                wrappedBuffer("cafés à la carte, whichÿ".getBytes(ISO_8859_1))));
        List<Slice> longInputs = blockSearchInputs();
        for (String expression : List.of(
                words,
                "[A-Z][a-z]{4}\\s[a-z]{3}",
                "[A-Z][a-z]{3}[a-fn-z]\\s",
                "\\w{30}\\s\\w{33}")) {
            assertBlockSearch(blockSearchPatterns(expression));
            assertGeneralEngineAgreement(blockSearchPatterns(expression), shortInputs, TestFixedWidthByteSpanMatcher::everyRegionEnd);
            assertGeneralEngineAgreement(blockSearchPatterns(expression), longInputs, TestFixedWidthByteSpanMatcher::blockRegionEnds);
        }
        // Classes above ASCII take the search where they read bytes, among them a class with
        // more than four ranges whose complement fits in four.
        String separated = "[^\\t\\n ,.]{4}[ ,.][^\\t\\n ,.]{4}[ ,.]";
        assertBlockSearch(separatedPatterns(separated));
        assertGeneralEngineAgreement(separatedPatterns(separated), shortInputs, TestFixedWidthByteSpanMatcher::everyRegionEnd);
        assertGeneralEngineAgreement(separatedPatterns(separated), longInputs, TestFixedWidthByteSpanMatcher::blockRegionEnds);

        // \w{30}\s\w{33} is a block wide, so its last position reads the next block's word. Its
        // matches start at a block's first byte (0 and 64), inside a block (1, 37, and 100), and
        // at its last byte (63); each that starts past a block's first byte crosses into the next
        // block. A find from inside the match finds the overlapping match of its last 30 y's and
        // the z's, which the count skips.
        String widest = "\\w{30}\\s\\w{33}";
        for (int matchStart : List.of(0, 1, 37, 63, 64, 100)) {
            Slice input = utf8Slice(" ".repeat(matchStart) + "x".repeat(30) + " " + "y".repeat(33) + " " + "z".repeat(70));
            for (Map.Entry<String, CompiledPattern> entry : blockSearchPatterns(widest).entrySet()) {
                String label = "%s at %s".formatted(entry.getKey(), matchStart);
                CompiledPattern pattern = entry.getValue();
                assertThat(pattern.count(input)).as(label).isEqualTo(1);
                RegexpMatcher matcher = pattern.matcher(input);
                assertThat(matcher.find()).as(label).isTrue();
                assertThat(List.of(matcher.start(), matcher.end())).as(label).containsExactly(matchStart, matchStart + 64);
                assertThat(matcher.find(matchStart + 1)).as(label).isTrue();
                assertThat(List.of(matcher.start(), matcher.end())).as(label).containsExactly(matchStart + 34, matchStart + 98);
                assertThat(matcher.find()).as(label).isFalse();
            }
        }

        Regex oracle = joniPattern(words);
        List<Slice> trinoSources = new ArrayList<>();
        for (Slice input : longInputs) {
            if (Utf8.firstInvalidOffset(input) < 0) {
                trinoSources.add(input);
            }
        }
        verifyOperations(words, trino, source -> joniMatches(oracle, source), trinoSources);
    }

    @Test
    public void testBlockSearchNearMaximumInput()
    {
        // An input within a block of the largest array finds the match at its start. There
        // lastStart - block would overflow, so the block loop's exit compares block with
        // lastStart - BLOCK_BYTES.
        assumeTrue(VectorSupport.isAvailable(), "requires the vector scanners");
        assumeTrue(Runtime.getRuntime().maxMemory() >= 3L << 30, "the input needs a heap of 3 GiB");
        byte[] bytes = new byte[Integer.MAX_VALUE - 16];
        byte[] words = "abcde abcdef abcdefg".getBytes(UTF_8);
        System.arraycopy(words, 0, bytes, 0, words.length);
        Slice input = wrappedBuffer(bytes);
        String expression = "\\w{5}\\s\\w{6}\\s\\w{7}";
        Re2 pattern = Re2.compile(utf8Slice(expression));
        assertBlockSearch(Map.of("RE2 " + expression, pattern));
        assertThat(pattern.count(input)).isEqualTo(1);
        RegexpMatcher matcher = pattern.matcher(input);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isEqualTo(words.length);
    }

    @Test
    public void testBlockClassWordsInBothForms()
    {
        // Both forms of a block's class word, whichever this platform takes, set the bit of each
        // byte in one of one to four ranges, ranges that start at 0x00 and end at 0xFF included.
        assumeTrue(VectorSupport.isAvailable(), "requires the vector scanners");
        Random random = new Random(20261108);
        for (int iteration = 0; iteration < 2_000; iteration++) {
            int rangeCount = 1 + random.nextInt(4);
            int[] bounds = random.ints(2L * rangeCount, 0, 256).sorted().toArray();
            if (iteration % 7 == 0) {
                bounds[0] = 0;
                bounds[bounds.length - 1] = 0xFF;
            }
            long ranges = 0;
            for (int range = 0; range < rangeCount; range++) {
                ranges |= (long) ((bounds[2 * range + 1] - bounds[2 * range]) << 8 | bounds[2 * range]) << (16 * range);
            }
            byte[] data = new byte[64 + 8];
            for (int index = 0; index < data.length; index++) {
                data[index] = (byte) (random.nextBoolean() ? bounds[random.nextInt(bounds.length)] + random.nextInt(3) - 1 : random.nextInt(256));
            }
            int position = random.nextInt(9);
            long expected = 0;
            for (int index = 0; index < 64; index++) {
                int value = data[position + index] & 0xFF;
                for (int range = 0; range < rangeCount; range++) {
                    if (bounds[2 * range] <= value && value <= bounds[2 * range + 1]) {
                        expected |= 1L << index;
                    }
                }
            }
            String label = "ranges %s at %s".formatted(Arrays.toString(bounds), position);
            assertThat(VectorBlockMaskScanner.members(data, position, ranges, rangeCount)).as(label).isEqualTo(expected);
            assertThat(VectorBlockMaskScanner.membersSve(data, position, ranges, rangeCount)).as(label).isEqualTo(expected);
        }
    }

    // Compiles `expression` in the languages whose \w and \s read one byte: RE2, in UTF-8 and
    // Latin-1, Java, and Rust's byte mode without Unicode.
    private static Map<String, CompiledPattern> blockSearchPatterns(String expression)
    {
        return Map.of(
                "RE2 " + expression, Re2.compile(utf8Slice(expression)),
                "RE2 Latin-1 " + expression, Re2.compile(utf8Slice(expression), Re2.Options.latin1()),
                "Java " + expression, JavaRegexp.compile(utf8Slice(expression)),
                "Rust bytes (?-u)" + expression, RustRegexp.compile(utf8Slice("(?-u)" + expression), RustRegexp.Options.bytes()));
    }

    // Compiles `expression`, whose classes hold bytes above ASCII, where those classes read
    // bytes: RE2's Latin-1 mode and Rust's byte mode.
    private static Map<String, CompiledPattern> separatedPatterns(String expression)
    {
        return Map.of(
                "RE2 Latin-1 " + expression, Re2.compile(utf8Slice(expression), Re2.Options.latin1()),
                "Rust bytes (?-u)" + expression, RustRegexp.compile(utf8Slice("(?-u)" + expression), RustRegexp.Options.bytes()));
    }

    private static void assertBlockSearch(Map<String, CompiledPattern> patterns)
    {
        for (Map.Entry<String, CompiledPattern> entry : patterns.entrySet()) {
            assertThat(entry.getValue().hasRetainedFixedWidthByteSpanMatcher()).as(entry.getKey()).isTrue();
            assertThat(entry.getValue().fixedWidthByteSpanMatcherForDiagnostics().searchesBlocksForDiagnostics()).as(entry.getKey()).isTrue();
        }
    }

    // Rows of 300 to 1,500 bytes: English words of one to nine letters between whitespace and
    // punctuation, words among digits, Cyrillic and Chinese text around English words, and
    // malformed UTF-8 bytes among words. Each row is also searched as a view with bytes on both
    // sides, which must not reach results, so its blocks start past the array's first byte. The
    // seed is fixed, so a failure replays.
    private static List<Slice> blockSearchInputs()
    {
        Random random = new Random(20261108);
        List<String> separators = List.of(" ", " ", " ", ", ", ". ", "\n", "\t", "  ");
        String otherScripts = "жизньШерлок終一龥";
        List<Slice> inputs = new ArrayList<>();
        for (int inputIndex = 0; inputIndex < 6; inputIndex++) {
            DynamicSliceOutput output = new DynamicSliceOutput(1024);
            int targetLength = 300 + random.nextInt(1200);
            while (output.size() < targetLength) {
                int wordLength = 1 + random.nextInt(9);
                for (int index = 0; index < wordLength; index++) {
                    int kind = random.nextInt(20);
                    if (inputIndex == 1 && kind < 8) {
                        output.writeByte('0' + random.nextInt(10));
                    }
                    else if (inputIndex == 2 && kind < 6) {
                        output.writeBytes(utf8Slice(String.valueOf(otherScripts.charAt(random.nextInt(otherScripts.length())))));
                    }
                    else if (inputIndex == 3 && kind < 2) {
                        output.writeByte(List.of(0x80, 0xBF, 0xC3, 0xE9, 0xFF).get(random.nextInt(5)));
                    }
                    else if (kind == 0) {
                        output.writeByte('A' + random.nextInt(26));
                    }
                    else {
                        output.writeByte(kind == 1 ? '_' : 'a' + random.nextInt(26));
                    }
                }
                output.writeBytes(utf8Slice(separators.get(random.nextInt(separators.size()))));
            }
            Slice row = output.slice();
            inputs.add(row);
            DynamicSliceOutput padded = new DynamicSliceOutput(row.length() + 6);
            padded.writeBytes(utf8Slice("àb"));
            padded.writeBytes(row);
            padded.writeBytes(utf8Slice("àb"));
            inputs.add(padded.slice().slice(3, row.length()));
        }
        return inputs;
    }

    @Test
    public void testDenseCandidatesBail()
    {
        // [а-я]{6}[.,] scans the lead bytes of Cyrillic letters, rare in English text, and a
        // Russian text of short words fails at nearly every letter. Once at least 64 candidates
        // have failed at more than one per 32 bytes, the search hands the rest to the program,
        // and every route agrees with the general engine. The count keeps the match before the
        // bail and counts the rest from its end. Its [.,] lies past the twelve bytes a start
        // literal filter reads, so no filter takes the pattern from the matcher.
        String expression = "[а-я]{6}[.,]";
        String filler = "и я да он мы ".repeat(20);
        Slice dense = utf8Slice("работа, " + filler + "машина. страна, письмо.");
        Slice denseFromStart = utf8Slice(filler + "машина.");
        // A region that starts after the first match and ends inside the last one.
        int regionStart = "работа, ".getBytes(UTF_8).length;
        int regionEnd = dense.length() - 1;
        // English text keeps the scan: its only Cyrillic letters are those of one word at its end,
        // which matches.
        Slice english = utf8Slice("The next morning a café near the old railway station served a quiet breakfast to the travelers who had arrived late. ".repeat(40) +
                "Словарь, then more text.");
        Map<String, CompiledPattern> patterns = Map.of(
                "RE2 " + expression, Re2.compile(utf8Slice(expression)),
                "Java " + expression, JavaRegexp.compile(utf8Slice(expression)),
                "Rust " + expression, RustRegexp.compile(utf8Slice(expression)));
        for (Map.Entry<String, CompiledPattern> entry : patterns.entrySet()) {
            String label = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(shape(pattern)).as(label).isEqualTo(CompiledPattern.Shape.FIXED_WIDTH);
            FixedWidthByteSpanMatcher matcher = pattern.fixedWidthByteSpanMatcherForDiagnostics();
            assertThat(matcher.bailsOnDenseCandidates()).as(label).isTrue();

            long bailed = matcher.countOrBail(dense);
            assertThat(bailed).as(label).isNegative();
            assertThat(FixedWidthByteSpanMatcher.countBeforeBail(bailed)).as(label).isEqualTo(1);
            assertThat(FixedWidthByteSpanMatcher.resumeAfterBail(bailed)).as(label).isEqualTo("работа,".getBytes(UTF_8).length);
            assertThat(matcher.findSpanOrBail(denseFromStart, 0, denseFromStart.length())).as(label).isEqualTo(FixedWidthByteSpanMatcher.BAILED);
            assertThat(matcher.findSpanOrBail(dense, regionStart, regionEnd)).as(label).isEqualTo(FixedWidthByteSpanMatcher.BAILED);
            for (Slice input : List.of(dense, denseFromStart, english)) {
                assertThat(pattern.count(input)).as(label).isEqualTo(generalEngineCount(pattern, input));
                assertThat(pattern.find(input)).as(label).isTrue();
                assertMatcherSpans(pattern, input, 0, input.length(), label);
            }
            assertMatcherSpans(pattern, dense, regionStart, regionEnd, label);
            // A bail ends with its search: the next count of the same matcher runs on it.
            assertThat(matcher.countOrBail(english)).as(label).isEqualTo(1);
        }

        TrinoRegexp trino = TrinoRegexp.compile(utf8Slice(expression));
        assertThat(trino).isExactlyInstanceOf(TrinoRegexp.class);
        assertThat(shape(trino.re2())).isEqualTo(CompiledPattern.Shape.FIXED_WIDTH);
        Regex oracle = joniPattern(expression);
        verifyOperations(expression, trino, source -> joniMatches(oracle, source), List.of(dense, denseFromStart, english));

        // A search of at least 1,024 candidate positions that scans for a one-byte candidate with
        // the vector scan, where the Vector API is available, bails the same way. The letters а
        // to п share one lead byte.
        FixedWidthByteSpanMatcher longMatcher = matcher("[а-п]{6}[.,]");
        Slice longDense = utf8Slice("голова, " + "да он и ни ".repeat(100));
        long longBailed = longMatcher.countOrBail(longDense);
        assertThat(longBailed).isNegative();
        assertThat(FixedWidthByteSpanMatcher.countBeforeBail(longBailed)).isEqualTo(1);
        assertThat(FixedWidthByteSpanMatcher.resumeAfterBail(longBailed)).isEqualTo("голова,".getBytes(UTF_8).length);
        assertThat(longMatcher.findSpanOrBail(longDense, "голова,".getBytes(UTF_8).length, longDense.length())).isEqualTo(FixedWidthByteSpanMatcher.BAILED);

        // The byte search bails the same way. In Latin-1 and Rust's byte mode, [\xE0-\xFF]{3}[.,]
        // scans for the bytes à to ÿ, which this Latin-1 text of short words holds at nearly
        // every letter.
        String byteExpression = "[\\xE0-\\xFF]{3}[.,]";
        String byteFiller = "à é îô ùû ".repeat(20);
        Slice byteDense = wrappedBuffer(("àéîôù, " + byteFiller + "ôùû.").getBytes(ISO_8859_1));
        for (Map.Entry<String, CompiledPattern> entry : Map.of(
                "RE2 Latin-1 " + byteExpression, Re2.compile(utf8Slice(byteExpression), Re2.Options.latin1()),
                "Rust bytes (?-u)" + byteExpression, RustRegexp.compile(utf8Slice("(?-u)" + byteExpression), RustRegexp.Options.bytes())).entrySet()) {
            String label = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(shape(pattern)).as(label).isEqualTo(CompiledPattern.Shape.FIXED_WIDTH);
            FixedWidthByteSpanMatcher matcher = pattern.fixedWidthByteSpanMatcherForDiagnostics();
            long bailed = matcher.countOrBail(byteDense);
            assertThat(bailed).as(label).isNegative();
            assertThat(FixedWidthByteSpanMatcher.countBeforeBail(bailed)).as(label).isEqualTo(1);
            assertThat(FixedWidthByteSpanMatcher.resumeAfterBail(bailed)).as(label).isEqualTo(6);
            assertThat(matcher.findSpanOrBail(byteDense, 6, byteDense.length())).as(label).isEqualTo(FixedWidthByteSpanMatcher.BAILED);
            assertThat(pattern.count(byteDense)).as(label).isEqualTo(generalEngineCount(pattern, byteDense));
            assertThat(pattern.find(byteDense)).as(label).isTrue();
            assertMatcherSpans(pattern, byteDense, 0, byteDense.length(), label);
        }

        // So does a byte search of at least 1,024 candidate positions that scans for a one-byte
        // candidate, here à, with the vector scan where the Vector API is available.
        Re2 longBytePattern = Re2.compile(utf8Slice("[a-z]{3}\\xE0"), Re2.Options.latin1());
        assertThat(shape(longBytePattern)).isEqualTo(CompiledPattern.Shape.FIXED_WIDTH);
        FixedWidthByteSpanMatcher longByteMatcher = longBytePattern.fixedWidthByteSpanMatcherForDiagnostics();
        Slice longByteDense = wrappedBuffer(("abcà" + "à ".repeat(600)).getBytes(ISO_8859_1));
        long longByteBailed = longByteMatcher.countOrBail(longByteDense);
        assertThat(longByteBailed).isNegative();
        assertThat(FixedWidthByteSpanMatcher.countBeforeBail(longByteBailed)).isEqualTo(1);
        assertThat(FixedWidthByteSpanMatcher.resumeAfterBail(longByteBailed)).isEqualTo(4);
        assertThat(longByteMatcher.findSpanOrBail(longByteDense, 4, longByteDense.length())).isEqualTo(FixedWidthByteSpanMatcher.BAILED);

        // The target's candidate, x and the first byte of à to ÿ, fails at most twice in each
        // sentence of that text, under once per 32 bytes, and the count never bails.
        String target = "[a-q][^u-z]{80}[x\\xE0-\\xFF]";
        for (Map.Entry<String, CompiledPattern> entry : re2JavaAndRustPatterns(target).entrySet()) {
            FixedWidthByteSpanMatcher matcher = entry.getValue().fixedWidthByteSpanMatcherForDiagnostics();
            assertThat(matcher.bailsOnDenseCandidates()).as(entry.getKey()).isTrue();
            assertThat(matcher.countOrBail(english)).as(entry.getKey()).isEqualTo(generalEngineCount(entry.getValue(), english));
        }

        // A count that bails hands the rest of the text to the ordinary count, which declines
        // under this budget; the pattern then counts the whole text by its matches, rather than
        // adding the matches before the bail to the declined count.
        String narrow = "[a-q][^u-z]{12}[x\\xE0-\\xFF]";
        Re2 constrained = Re2.compile(utf8Slice(narrow), Re2.Options.defaults().setMaxMemory(40_000));
        String match = "a" + "é".repeat(12) + "x";
        Slice text = utf8Slice(match.repeat(3) + "x я xy ".repeat(50) + match.repeat(23));
        long bailed = constrained.fixedWidthByteSpanMatcherForDiagnostics().countOrBail(text);
        assertThat(FixedWidthByteSpanMatcher.countBeforeBail(bailed)).isEqualTo(3);
        assertThat(FixedWidthByteSpanMatcher.resumeAfterBail(bailed)).isEqualTo(3 * match.getBytes(UTF_8).length);
        assertThat(constrained.countOnFixedWidth(text)).isEqualTo(-1);
        assertThat(constrained.count(text)).isEqualTo(26);
    }

    // Each find of a group-zero matcher in the region reports the general engine's next span.
    private static void assertMatcherSpans(CompiledPattern pattern, Slice input, int regionStart, int regionEnd, String label)
    {
        RegexpMatcher matcher = pattern.matcher(input, 0).reset(input, regionStart, regionEnd);
        RegexpMatcher generalMatcher = pattern.genericGroupZeroMatcherForDiagnostics(input).reset(input, regionStart, regionEnd);
        while (generalMatcher.find()) {
            assertThat(matcher.find()).as(label).isTrue();
            assertThat(List.of(matcher.start(), matcher.end())).as(label).containsExactly(generalMatcher.start(), generalMatcher.end());
        }
        assertThat(matcher.find()).as(label).isFalse();
    }

    // Requires boolean find, count, the matcher's find from every start and its region search
    // from there to each of `regionEnds`, and a matcher's group-zero finds, to match the general
    // engine's.
    private static void assertGeneralEngineAgreement(Map<String, CompiledPattern> patterns, List<Slice> inputs, BiFunction<Slice, Integer, IntStream> regionEnds)
    {
        for (Map.Entry<String, CompiledPattern> entry : patterns.entrySet()) {
            String label = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(shape(pattern)).as(label).isEqualTo(CompiledPattern.Shape.FIXED_WIDTH);
            // Boolean find and count answer from the matcher without building the general
            // engine's DFA or reverse program, so they run before the general engine does.
            List<Boolean> found = new ArrayList<>();
            List<Long> counts = new ArrayList<>();
            for (Slice input : inputs) {
                found.add(pattern.find(input));
                counts.add(pattern.count(input));
            }
            assertNoProgramSearch(pattern, label);
            FixedWidthByteSpanMatcher matcher = pattern.fixedWidthByteSpanMatcherForDiagnostics();
            for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                Slice input = inputs.get(inputIndex);
                String inputLabel = "%s input %s".formatted(label, HexFormat.of().formatHex(input.getBytes()));
                long expectedCount = generalEngineCount(pattern, input);
                assertThat(found.get(inputIndex)).as(inputLabel).isEqualTo(expectedCount > 0);
                assertThat(counts.get(inputIndex)).as(inputLabel).isEqualTo(expectedCount);
                RegexpMatcher generalMatcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
                for (int start = 0; start <= input.length(); start++) {
                    long expected = generalMatcher.find(start) ? span(generalMatcher.start(), generalMatcher.end()) : Dfa.SEARCH_NO_MATCH;
                    assertThat(matcher.findSpan(input, start)).as("%s start %s", inputLabel, start).isEqualTo(expected);
                    // A region search reports the general engine's first match in the region.
                    for (int end : regionEnds.apply(input, start).toArray()) {
                        expected = generalMatcher.reset(input, start, end).find() ? span(generalMatcher.start(), generalMatcher.end()) : Dfa.SEARCH_NO_MATCH;
                        assertThat(matcher.findSpanOrBail(input, start, end)).as("%s region %s-%s", inputLabel, start, end).isEqualTo(expected);
                    }
                }
                assertMatcherSpans(pattern, input, 0, input.length(), inputLabel);
                if (input.length() >= 2) {
                    assertMatcherSpans(pattern, input, 1, input.length() - 1, inputLabel);
                }
            }
        }
    }

    // Every end of a region from `start`, for short inputs.
    private static IntStream everyRegionEnd(Slice input, int start)
    {
        return IntStream.rangeClosed(start, input.length());
    }

    // The ends of the regions from `start` that end near a block boundary past it, and of the
    // one that ends a byte before the input, for inputs several blocks long.
    private static IntStream blockRegionEnds(Slice input, int start)
    {
        return IntStream.of(start + 63, start + 64, start + 65, start + 127, start + 128, input.length() - 1)
                .filter(end -> end >= start && end <= input.length());
    }

    // Requires, for every region of `input`, `pattern`'s boolean find and its find, lookingAt,
    // and matches group zero, and a matcher's lookingAt and find spans and matches boolean, to
    // match `reference`'s.
    private static void assertRegionMatchAgreement(String label, CompiledPattern pattern, CompiledPattern reference, Slice input)
    {
        RegexpMatcher matcher = pattern.matcher(input, 0);
        RegexpMatcher referenceMatcher = reference.matcher(input, 0);
        String inputLabel = "%s input %s".formatted(label, HexFormat.of().formatHex(input.getBytes()));
        for (int start = 0; start <= input.length(); start++) {
            for (int end = start; end <= input.length(); end++) {
                String regionLabel = "%s region %s-%s".formatted(inputLabel, start, end);
                assertThat(pattern.find(input, start, end)).as(regionLabel).isEqualTo(reference.find(input, start, end));
                assertThat(resultSpan(pattern.findResult(input, start, end))).as(regionLabel).isEqualTo(resultSpan(reference.findResult(input, start, end)));
                assertThat(resultSpan(pattern.lookingAtResult(input, start, end))).as(regionLabel).isEqualTo(resultSpan(reference.lookingAtResult(input, start, end)));
                assertThat(resultSpan(pattern.matchesResult(input, start, end))).as(regionLabel).isEqualTo(resultSpan(reference.matchesResult(input, start, end)));
                assertThat(matcherSpan(matcher.reset(input, start, end).lookingAt(), matcher)).as(regionLabel).isEqualTo(matcherSpan(referenceMatcher.reset(input, start, end).lookingAt(), referenceMatcher));
                assertThat(matcher.reset(input, start, end).matches()).as(regionLabel).isEqualTo(referenceMatcher.reset(input, start, end).matches());
                assertThat(matcherSpan(matcher.reset(input, start, end).find(), matcher)).as(regionLabel).isEqualTo(matcherSpan(referenceMatcher.reset(input, start, end).find(), referenceMatcher));
            }
        }
    }

    private static long resultSpan(MatchResult result)
    {
        return result == null ? Dfa.SEARCH_NO_MATCH : span(result.start(), result.end());
    }

    private static long matcherSpan(boolean matched, RegexpMatcher matcher)
    {
        return matched ? span(matcher.start(), matcher.end()) : Dfa.SEARCH_NO_MATCH;
    }

    // Short rows joined from ASCII, Latin-1 and UTF-8 characters, and malformed UTF-8: truncated
    // sequences, stray continuation bytes, overlong forms, an encoded surrogate, and bytes no
    // UTF-8 sequence holds. The seed is fixed, so a failure replays.
    private static List<Slice> fragmentInputs()
    {
        List<byte[]> fragments = List.of(
                bytes(0x61),
                bytes(0x62),
                bytes(0x78),
                bytes(0x7A),
                bytes(0x31),
                bytes(0x32),
                bytes(0x20),
                bytes(0x2D),
                bytes(0xC3, 0xA9),
                bytes(0xC3, 0xA0),
                bytes(0xC3, 0xBF),
                bytes(0xCE, 0xB1),
                bytes(0xE4, 0xB8, 0x80),
                bytes(0xE4, 0xB8, 0x8F),
                bytes(0xF0, 0x9F, 0x92, 0xB0),
                bytes(0xEF, 0xBF, 0xBD),
                bytes(0xED, 0xA0, 0x80),
                bytes(0xC0, 0x80),
                bytes(0xE0, 0x80, 0x80),
                bytes(0xF0, 0x80, 0x80, 0x80),
                bytes(0x80),
                bytes(0xBF),
                bytes(0xC3),
                bytes(0xE4, 0xB8),
                bytes(0xF0, 0x9F, 0x92),
                bytes(0xE9),
                bytes(0xFF),
                bytes(0xF5, 0x80, 0x80, 0x80));
        Random random = new Random(20261107);
        List<Slice> inputs = new ArrayList<>();
        for (int inputIndex = 0; inputIndex < 40; inputIndex++) {
            DynamicSliceOutput output = new DynamicSliceOutput(32);
            int fragmentCount = 1 + random.nextInt(12);
            for (int fragmentIndex = 0; fragmentIndex < fragmentCount; fragmentIndex++) {
                output.writeBytes(fragments.get(random.nextInt(fragments.size())));
            }
            inputs.add(output.slice());
        }
        return inputs;
    }

    private static byte[] bytes(int... values)
    {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
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

        // Trino follows the same rule: its plain subtype answers contains, and count, on the
        // ordinary engine, which searches for shing in inputs of at least MINIMUM_LONG_SEARCH_BYTES,
        // and agrees with RE2's general engine; the last block below covers shorter counts.
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

        // A rare class pair is a two-byte inner literal too, searched for as its members in inputs
        // of at least MINIMUM_LONG_SEARCH_BYTES; these shorter ones check that its routes agree.
        TrinoRegexp classPair = TrinoRegexp.compile(utf8Slice("[a-z][?!.][\"']x"));
        assertThat(classPair).isExactlyInstanceOf(TrinoRegexp.class);
        assertThat(classPair.re2().innerLiteralSearchForDiagnostics().literals()).isDeepEqualTo(new byte[][] {"!\"".getBytes(ISO_8859_1), "!'".getBytes(ISO_8859_1), ".\"".getBytes(ISO_8859_1), ".'".getBytes(ISO_8859_1), "?\"".getBytes(ISO_8859_1), "?'".getBytes(ISO_8859_1)});
        Re2 classPairReference = Re2.compile(utf8Slice("[a-z][?!.][\"']x"));
        for (String text : List.of("?\"x has no letter before", "ab?\"x", "no.'x here", "ok!\"x, so?'x and z.\"x")) {
            Slice input = utf8Slice(text);
            long expectedCount = generalEngineCount(classPairReference, input);
            assertThat(classPair.contains(input)).as(text).isEqualTo(expectedCount > 0);
            assertThat(classPair.count(input)).as(text).isEqualTo(expectedCount);
        }

        // A pattern with a short-search fixed-width matcher runs the inner literal search from
        // MINIMUM_LONG_SEARCH_BYTES, for a single literal, whose own minimum is longer, as for a
        // class pair. On fewer bytes every language, Trino included, counts and finds matcher
        // spans with that matcher, which builds no program search, while full-Slice find stays on
        // the engine. Each search covers the last bytes of a 1024-byte input that ends in a match,
        // so the find that resumes after the match has no bytes left. Every match of [a-z]shing
        // ends at its literal's end, so its count of the 95-byte input, whose hit lies past the
        // near-start fallback, confirms the hit on the reverse prefix DFA alone.
        for (Map.Entry<String, String> example : List.of(Map.entry("[a-z]shing", "ishing"), Map.entry("[a-z][?!.][\"']x", "a?\"x"))) {
            String expression = example.getKey();
            int matchStart = 1024 - example.getValue().length();
            Slice longInput = utf8Slice("x".repeat(matchStart) + example.getValue());
            for (int length : List.of(CompiledPattern.MINIMUM_LONG_SEARCH_BYTES - 1, CompiledPattern.MINIMUM_LONG_SEARCH_BYTES, InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES - 1)) {
                Slice input = longInput.slice(longInput.length() - length, length);
                boolean shortSearch = length < CompiledPattern.MINIMUM_LONG_SEARCH_BYTES;
                boolean literalEndCount = expression.equals("[a-z]shing") && length == InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES - 1;
                for (Map.Entry<String, CompiledPattern> entry : innerLiteralPatterns(expression).entrySet()) {
                    String label = "%s count of %s bytes".formatted(entry.getKey(), length);
                    CompiledPattern pattern = entry.getValue();
                    assertThat(pattern.shortFixedWidthByteMatcher()).as(label).isNotNull();
                    assertThat(pattern.count(input)).as(label).isEqualTo(1);
                    if (literalEndCount) {
                        assertNoProgramSearch(pattern, label);
                        assertThat(pattern.innerLiteralSearchForDiagnostics().reversePrefixProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH)).as(label).isNotNull();
                    }
                    else {
                        assertProgramSearchUnless(shortSearch, pattern, label);
                    }
                }
                for (Map.Entry<String, CompiledPattern> entry : innerLiteralPatterns(expression).entrySet()) {
                    String label = "%s matcher find with %s bytes left".formatted(entry.getKey(), length);
                    CompiledPattern pattern = entry.getValue();
                    RegexpMatcher matcher = pattern.matcher(longInput, 0);
                    assertThat(matcher.find(longInput.length() - length)).as(label).isTrue();
                    assertThat(List.of(matcher.start(), matcher.end())).as(label).containsExactly(matchStart, 1024);
                    assertThat(matcher.find()).as(label).isFalse();
                    assertProgramSearchUnless(shortSearch, pattern, label);
                }
                for (Map.Entry<String, CompiledPattern> entry : innerLiteralPatterns(expression).entrySet()) {
                    String label = "%s find of %s bytes".formatted(entry.getKey(), length);
                    assertThat(entry.getValue().find(input)).as(label).isTrue();
                    assertProgramSearch(entry.getValue(), label);
                }
            }
        }

        // The short-search matcher follows the full matcher's order: where a forward DFA searches
        // for the start literal filter of [Шш]abc, the engine keeps the short searches too.
        RustRegexp cyrillicPrefix = RustRegexp.compile(utf8Slice("[Шш]abc[0-9]"));
        assertThat(cyrillicPrefix.shortFixedWidthByteMatcher() != null).isEqualTo(!VectorSupport.isAvailable());
        Slice shortRow = utf8Slice("the row ends in a match: шabc7.");
        assertThat(cyrillicPrefix.count(shortRow)).isEqualTo(1);
        assertProgramSearchUnless(!VectorSupport.isAvailable(), cyrillicPrefix, "[Шш]abc[0-9] count of a short row");

        // A one-byte inner literal is no rarer than the matcher's byte, so Trino keeps the
        // matcher on its route without a program.
        for (String expression : List.of("[a-z]{8}-[0-9]{4}", "\\d{4}-\\d{2}-\\d{2}")) {
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(regexp).as(expression).isInstanceOf(TrinoRegexp.FixedWidthByteTrinoRegexp.class);
            assertThat(regexp.re2().forwardProgramForDiagnostics().start()).as(expression).isZero();
        }
    }

    @Test
    public void testTrinoMaxMemoryOfFixedWidthPatterns()
    {
        // Each pattern compiles from the least maxMemory at which the route without a program
        // fits; below it neither the matcher nor a program fits. Up to 8 KiB the inner literal
        // search of a shing pattern does not fit, so the matcher is the route. The last pattern
        // compiles a program only from a maxMemory beyond the 8 KiB checked here, so from 4921 it
        // compiles only on the route without one.
        Map<String, Long> minimumMaxMemory = Map.of(
                "[a-z]shing", 4465L,
                "\\d{4}-\\d{2}-\\d{2}", 4561L,
                "[0-9a-fA-F]{8}shing[0-9a-fA-F]{12}", 4921L);
        for (Map.Entry<String, Long> entry : minimumMaxMemory.entrySet()) {
            Slice expression = utf8Slice(entry.getKey());
            for (long maxMemory = 256; maxMemory <= 8192; maxMemory++) {
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
        TrinoRegexp fallback = TrinoRegexp.compile(utf8Slice("[0-9a-fA-F]{8}shing[0-9a-fA-F]{12}"), TrinoRegexp.Options.defaults().setMaxMemory(4921));
        assertThat(fallback.re2().forwardProgramForDiagnostics().start()).isZero();

        // The repetitions of a repeated group share its positions' ranges, so the matcher charges
        // them once, not once for each repetition.
        TrinoRegexp repeatedGroup = TrinoRegexp.compile(utf8Slice("(?:[é中][a-z]){127}[0-9]{2}"), TrinoRegexp.Options.defaults().setMaxMemory(13_000));
        assertThat(repeatedGroup.re2().hasRetainedFixedWidthByteSpanMatcher()).isTrue();

        // Where the program fits but neither the inner literal search nor the matcher fits beside
        // it, the pattern takes the route without a program, which reserves the matcher.
        Map<String, List<Long>> matcherOnlyMaxMemory = Map.of(
                "[a-z]shing", List.of(5111L, 5125L),
                "[a-z]{2}ing", List.of(5003L, 5028L));
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

    // The parse trees of re2JavaAndRustPatterns, by the same labels. A compiled pattern keeps no
    // parse tree, so the matcher of a pattern that retains none is analyzed from its language's.
    private static Map<String, Regexp> re2JavaAndRustTrees(String expression)
    {
        RustRegexp.Options rust = RustRegexp.Options.defaults();
        RustRegexp.Options bytes = RustRegexp.Options.bytes();
        return Map.of(
                "RE2 " + expression, RegexpParser.parse(utf8Slice(expression), Re2.Options.defaults().parseFlags()).regexp(),
                "Java " + expression, JavaRegexpParser.parse(utf8Slice(expression), JavaRegexp.Options.defaults().parseFlags()).regexp(),
                "Rust " + expression, RustRegexpParser.parse(utf8Slice(expression), rust.syntaxFlags(), rust.nestLimit()).regexp(),
                "Rust bytes (?-u)" + expression, RustRegexpParser.parse(utf8Slice("(?-u)" + expression), bytes.syntaxFlags(), bytes.nestLimit()).regexp());
    }

    // Freshly compiles `expression` in every language, Trino included, so no earlier search has
    // built a DFA.
    private static Map<String, CompiledPattern> innerLiteralPatterns(String expression)
    {
        Map<String, CompiledPattern> patterns = new LinkedHashMap<>(re2JavaAndRustPatterns(expression));
        patterns.put("Trino " + expression, TrinoRegexp.compile(utf8Slice(expression)).re2());
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
        return optionalMatcher(pattern, Re2.Options.defaults());
    }

    private static FixedWidthByteSpanMatcher optionalMatcher(String pattern, Re2.Options options)
    {
        Slice expression = utf8Slice(pattern);
        return Re2.compile(expression, options).createFixedWidthByteSpanMatcher(RegexpParser.parse(expression, options.parseFlags()).regexp());
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
