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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
        // pattern, and the engine keeps it, on the class-run counter for [a-z]{5}.
        for (String expression : List.of("[a-z]{5}", "[a-q][^u-z]{80}[a-z]")) {
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
            assertGeneralEngineAgreement(patterns, inputs);
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
                    "Rust " + expression, RustRegexp.compile(utf8Slice(expression))), inputs);
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
            if (bails) {
                assertThat(regexp).as(expression).isExactlyInstanceOf(TrinoRegexp.class);
            }
            else {
                assertThat(regexp).as(expression).isInstanceOf(TrinoRegexp.FixedWidthByteTrinoRegexp.class);
            }
            Regex oracle = joniPattern(expression);
            verifyOperations(expression, regexp, source -> joniMatches(oracle, source), trinoSources);
        }
    }

    @Test
    public void testDenseCandidatesBail()
    {
        // [а-я]{3}[.,] scans the lead bytes of Cyrillic letters, rare in English text, and a
        // Russian text of short words fails at nearly every letter. Once at least 64 candidates
        // have failed at more than one per 32 bytes, the search hands the rest to the program,
        // and every route agrees with the general engine. The count keeps the match before the
        // bail and counts the rest from its end.
        String expression = "[а-я]{3}[.,]";
        String filler = "и я да он мы ".repeat(20);
        Slice dense = utf8Slice("слово, " + filler + "дом. сад, лес.");
        Slice denseFromStart = utf8Slice(filler + "дом.");
        // A region that starts after the first match and ends inside the last one.
        int regionStart = "слово, ".getBytes(UTF_8).length;
        int regionEnd = dense.length() - 1;
        // English text keeps the scan: its only Cyrillic letters are those of one word at its end,
        // which matches.
        Slice english = utf8Slice("The next morning a café near the old railway station served a quiet breakfast to the travelers who had arrived late. ".repeat(40) +
                "Слово, then more text.");
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
            assertThat(FixedWidthByteSpanMatcher.resumeAfterBail(bailed)).as(label).isEqualTo("слово,".getBytes(UTF_8).length);
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

    private static void assertGeneralEngineAgreement(Map<String, CompiledPattern> patterns, List<Slice> inputs)
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
                    for (int end = start; end <= input.length(); end++) {
                        expected = generalMatcher.reset(input, start, end).find() ? span(generalMatcher.start(), generalMatcher.end()) : Dfa.SEARCH_NO_MATCH;
                        assertThat(matcher.findSpanOrBail(input, start, end)).as("%s region %s-%s", inputLabel, start, end).isEqualTo(expected);
                    }
                }
            }
        }
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
        // so the find that resumes after the match has no bytes left.
        for (Map.Entry<String, String> example : List.of(Map.entry("[a-z]shing", "ishing"), Map.entry("[a-z][?!.][\"']x", "a?\"x"))) {
            String expression = example.getKey();
            int matchStart = 1024 - example.getValue().length();
            Slice longInput = utf8Slice("x".repeat(matchStart) + example.getValue());
            for (int length : List.of(CompiledPattern.MINIMUM_LONG_SEARCH_BYTES - 1, CompiledPattern.MINIMUM_LONG_SEARCH_BYTES, InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES - 1)) {
                Slice input = longInput.slice(longInput.length() - length, length);
                boolean shortSearch = length < CompiledPattern.MINIMUM_LONG_SEARCH_BYTES;
                for (Map.Entry<String, CompiledPattern> entry : innerLiteralPatterns(expression).entrySet()) {
                    String label = "%s count of %s bytes".formatted(entry.getKey(), length);
                    CompiledPattern pattern = entry.getValue();
                    assertThat(pattern.shortFixedWidthByteMatcher()).as(label).isNotNull();
                    assertThat(pattern.count(input)).as(label).isEqualTo(1);
                    assertProgramSearchUnless(shortSearch, pattern, label);
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
        // fits; below it neither the matcher nor a program fits. Up to 4 KiB the inner literal
        // search of a shing pattern does not fit, so the matcher is the route. The last pattern
        // compiles a program only from a maxMemory beyond the 4 KiB checked here, so from 1741 it
        // compiles only on the route without one.
        Map<String, Long> minimumMaxMemory = Map.of(
                "[a-z]shing", 1487L,
                "\\d{4}-\\d{2}-\\d{2}", 1487L,
                "[0-9a-fA-F]{8}shing[0-9a-fA-F]{12}", 1741L);
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
        TrinoRegexp fallback = TrinoRegexp.compile(utf8Slice("[0-9a-fA-F]{8}shing[0-9a-fA-F]{12}"), TrinoRegexp.Options.defaults().setMaxMemory(1741));
        assertThat(fallback.re2().forwardProgramForDiagnostics().start()).isZero();

        // The repetitions of a repeated group share its positions' ranges, so the matcher charges
        // them once, not once for each repetition.
        TrinoRegexp repeatedGroup = TrinoRegexp.compile(utf8Slice("(?:[é中][a-z]){127}[0-9]{2}"), TrinoRegexp.Options.defaults().setMaxMemory(10_000));
        assertThat(repeatedGroup.re2().hasRetainedFixedWidthByteSpanMatcher()).isTrue();

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
