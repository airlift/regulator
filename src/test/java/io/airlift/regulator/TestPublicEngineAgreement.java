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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.GENERAL;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.ORDERED_LITERALS;
import static io.airlift.regulator.TestingPatternShapes.assertNoProgramSearch;
import static io.airlift.regulator.TestingPatternShapes.compileAtSmallestDfaBudget;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestPublicEngineAgreement
{
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    @Test
    public void testByteAndBoundaryCases()
    {
        List<String> patterns = List.of(
                ".",
                "(?s:.)",
                ".*",
                "\\C",
                "a.*b",
                "(?:a|)",
                "(a)?b",
                "^a$",
                "a\\z",
                "\\b",
                "[^\\n]");
        List<Slice> texts = List.of(
                bytes(""),
                bytes("00"),
                bytes("0a"),
                bytes("61"),
                bytes("6162"),
                bytes("80"),
                bytes("c0af"),
                bytes("e282"),
                bytes("f0808080"),
                bytes("c3a9"),
                bytes("61ff62"));

        List<String> failures = new ArrayList<>();
        for (String pattern : patterns) {
            Tester tester = new Tester(Slices.wrappedBuffer(pattern.getBytes(UTF_8)), Tester.Config.fullMatrix());
            for (Slice text : texts) {
                if (!tester.testInput(text)) {
                    failures.add("pattern=" + pattern + " text=" + HEX_FORMAT.formatHex(text.byteArray(), text.byteArrayOffset(), text.byteArrayOffset() + text.length()) + " " + tester.failureMessage());
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    public void testSpanSearchRegions()
    {
        // A group-zero matcher of a literal alternation, a literal set, or a fixed-width pattern
        // finds on the pattern's span search in every language, and its spans are the generic
        // matcher's in every region of a Slice view that begins inside its byte array: regions
        // whose end cuts a match, which leaves `ab` of `abc|x|ab` and `abc|ab` and no fixed-width
        // match, and regions that start inside a match.
        String literalSet = IntStream.range(0, 65).mapToObj(index -> "w" + index + "q").collect(Collectors.joining("|")) + "|abc|ab";
        Map<String, Class<? extends GroupZeroSpanSearch>> expressions = Map.of(
                "abc|x|ab|ERROR|βeta", LiteralAlternationSpanMatcher.class,
                literalSet, LiteralSetSpanMatcher.class,
                "[0-9]{2}-[0-9]{2}", FixedWidthByteSpanMatcher.class);
        // Malformed UTF-8 at the #, between sentinel bytes outside the view.
        byte[] bytes = utf8Slice("!!abcab ERRORβeta w7q12-3456-78 w64qabéERRO#12-34abc?").getBytes();
        bytes[bytes.length - 10] = (byte) 0xFF;
        Slice input = wrappedBuffer(bytes, 2, bytes.length - 3);
        for (Map.Entry<String, Class<? extends GroupZeroSpanSearch>> entry : expressions.entrySet()) {
            String expression = entry.getKey();
            List<CompiledPattern> patterns = List.of(
                    Re2.compile(utf8Slice(expression)),
                    JavaRegexp.compile(utf8Slice(expression)),
                    RustRegexp.compile(utf8Slice(expression)),
                    RustRegexp.compile(utf8Slice("(?-u)" + expression), RustRegexp.Options.bytes()));
            for (CompiledPattern pattern : patterns) {
                assertSpanSearchRegions(pattern, entry.getValue(), input);
            }
        }
    }

    @Test
    public void testDotStarLiteralSpanRegions()
    {
        // A group-zero matcher of `.*literal.*` finds on the dot-star literal matcher in RE2, Java
        // with UNIX_LINES, whose dot is `[^\n]`, and Rust, in UTF-8 and bytes mode, and its spans
        // are the generic matcher's in every region: a region end inside the trailing dots ends the
        // match there, one inside the literal leaves no match, and the dots stop at a line feed
        // and, in UTF-8, at the malformed byte at the #.
        byte[] bytes = utf8Slice("!!xERRORy\nzERRORβ ERR ERROR\u2028tail ERRO#ERRORz é?").getBytes();
        bytes[bytes.length - 11] = (byte) 0xFF;
        Slice input = wrappedBuffer(bytes, 2, bytes.length - 3);
        List<CompiledPattern> patterns = List.of(
                Re2.compile(utf8Slice(".*ERROR.*")),
                Re2.compile(utf8Slice(".*ERROR.*"), Re2.Options.latin1()),
                JavaRegexp.compile(utf8Slice("(?d).*ERROR.*")),
                RustRegexp.compile(utf8Slice(".*ERROR.*")),
                RustRegexp.compile(utf8Slice("(?-u).*ERROR.*"), RustRegexp.Options.bytes()));
        for (CompiledPattern pattern : patterns) {
            assertSpanSearchRegions(pattern, DotStarLiteralSpanMatcher.class, input);
        }
    }

    @Test
    public void testOrderedLiteralGaps()
    {
        // Full-Slice find of literals in order runs the ordered-literal matcher, which reads
        // nothing between the literals, only where every gap matches any bytes: RE2's `\C*`, a
        // Latin-1 RE2 dot, and Rust's `(?s-u:.)` in the bytes API. A UTF-8 `(?s:.)` matches no
        // malformed UTF-8 in RE2, Java, or Rust, so `(?s:a.*b)` keeps the general engine and finds
        // nothing in `61 ff 62`.
        record Row(CompiledPattern pattern, boolean orderedLiterals) {}

        List<Row> twoLiterals = List.of(
                new Row(Re2.compile(utf8Slice("(?s:a.*b)")), false),
                new Row(JavaRegexp.compile(utf8Slice("(?s:a.*b)")), false),
                new Row(RustRegexp.compile(utf8Slice("(?s:a.*b)")), false),
                new Row(Re2.compile(utf8Slice("a\\C*b")), true),
                new Row(Re2.compile(utf8Slice("(?s:a.*b)"), Re2.Options.latin1()), true),
                new Row(RustRegexp.compile(utf8Slice("(?s-u:a.*b)"), RustRegexp.Options.bytes()), true));
        for (Row row : twoLiterals) {
            assertThat(row.pattern().find(bytes("61ff62"))).as(label(row.pattern())).isEqualTo(row.orderedLiterals());
            if (row.orderedLiterals()) {
                assertNoProgramSearch(row.pattern(), label(row.pattern()));
            }
        }
        // Longer sequences, with literals of more than one byte and leading and trailing gaps of
        // any character, which can match empty.
        List<Row> longer = List.of(
                new Row(Re2.compile(utf8Slice("a(?s:.*)bc(?s:.*)c")), false),
                new Row(Re2.compile(utf8Slice("(?s:.*)ab\\C*bc\\C*c(?s:.*)")), true),
                new Row(Re2.compile(utf8Slice("a(?s:.*)bc(?s:.*)c"), Re2.Options.latin1()), true));

        // Each pattern agrees with the general engine on random inputs, as views between sentinels
        // that would complete a match.
        String[] tokens = {"a", "b", "c", "bc", "x", "\n", "é", "€"};
        byte[][] malformed = {{(byte) 0xFF}, {(byte) 0x80}, {(byte) 0xC3}, {(byte) 0xE2, (byte) 0x82}, {(byte) 0xC0, (byte) 0xAF}};
        for (Row row : Stream.concat(twoLiterals.stream(), longer.stream()).toList()) {
            CompiledPattern pattern = row.pattern();
            String label = label(pattern);
            assertThat(pattern.findPlanForDiagnostics()).as(label).isEqualTo(row.orderedLiterals() ? ORDERED_LITERALS : GENERAL);
            assertThat(pattern.orderedLiteralMatcherForDiagnostics() != null).as(label).isEqualTo(row.orderedLiterals());
            Random random = new Random(20261105);
            for (int inputIndex = 0; inputIndex < 300; inputIndex++) {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                text.writeBytes("abc".getBytes(UTF_8));
                int tokenCount = random.nextInt(12);
                for (int tokenIndex = 0; tokenIndex < tokenCount; tokenIndex++) {
                    text.writeBytes(random.nextInt(4) == 0 ? malformed[random.nextInt(malformed.length)] : tokens[random.nextInt(tokens.length)].getBytes(UTF_8));
                }
                text.writeBytes("abc".getBytes(UTF_8));
                byte[] backing = text.toByteArray();
                Slice input = wrappedBuffer(backing, 3, backing.length - 6);
                assertThat(pattern.find(input)).as("%s on %s", label, HEX_FORMAT.formatHex(backing, 3, backing.length - 3)).isEqualTo(pattern.find(input, 0, input.length()));
            }
        }

        // The plan yields to the forward DFAs: at the least budget at which a DFA initializes, the
        // matcher's reservation would leave it too little, so the matcher is left out and find
        // runs the general engine, as in release 2.0, which had no such plan outside Trino.
        Map<String, LongFunction<CompiledPattern>> compilers = Map.of(
                "Re2 a\\C*b", maxMemory -> Re2.compile(utf8Slice("a\\C*b"), Re2.Options.defaults().setMaxMemory(maxMemory)),
                "Re2 longest-match a\\C*b", maxMemory -> Re2.compile(utf8Slice("a\\C*b"), Re2.Options.defaults().setLongestMatch(true).setMaxMemory(maxMemory)),
                "Re2 Latin-1 (?s:a.*b)", maxMemory -> Re2.compile(utf8Slice("(?s:a.*b)"), Re2.Options.latin1().setMaxMemory(maxMemory)),
                "RustRegexp bytes (?s-u:a.*b)", maxMemory -> RustRegexp.compile(utf8Slice("(?s-u:a.*b)"), RustRegexp.Options.bytes().setMaxMemory(maxMemory)));
        for (Map.Entry<String, LongFunction<CompiledPattern>> compiler : compilers.entrySet()) {
            assertThat(compiler.getValue().apply(Re2.Options.DEFAULT_MAX_MEMORY).orderedLiteralMatcherForDiagnostics()).as(compiler.getKey()).isNotNull();
            for (Dfa.DfaInstance.Kind kind : List.of(Dfa.DfaInstance.Kind.FIRST_MATCH, Dfa.DfaInstance.Kind.LONGEST_MATCH)) {
                CompiledPattern pattern = compileAtSmallestDfaBudget(compiler.getValue(), kind);
                String label = compiler.getKey() + " " + kind;
                assertThat(pattern.orderedLiteralMatcherForDiagnostics()).as(label).isNull();
                assertThat(pattern.find(bytes("61ff62"))).as(label).isTrue();
            }
        }
    }

    private static String label(CompiledPattern pattern)
    {
        return pattern.getClass().getSimpleName() + " " + pattern.pattern().toStringUtf8();
    }

    /// Requires `pattern`'s group-zero matchers to find on its span search, an instance of
    /// `spanSearch`, and to report the generic matcher's spans in every region of `input`.
    private static void assertSpanSearchRegions(CompiledPattern pattern, Class<?> spanSearch, Slice input)
    {
        String label = label(pattern);
        assertThat(pattern.groupZeroSpanSearch()).as(label).isInstanceOf(spanSearch);
        assertThat(pattern.matcher(input).spanSearchForDiagnostics()).as(label).isSameAs(pattern.groupZeroSpanSearch());
        assertThat(pattern.matcher(input, 0).spanSearchForDiagnostics()).as(label).isSameAs(pattern.groupZeroSpanSearch());
        assertThat(findSpans(pattern.matcher(input))).as(label).isNotEmpty();
        assertNoProgramSearch(pattern, label);
        assertThat(pattern.genericGroupZeroMatcherForDiagnostics(input).spanSearchForDiagnostics()).as(label).isNull();
        RegexpMatcher matcher = pattern.matcher(input, 0);
        RegexpMatcher generic = pattern.genericGroupZeroMatcherForDiagnostics(input);
        for (int regionStart = 0; regionStart <= input.length(); regionStart++) {
            for (int regionEnd = regionStart; regionEnd <= input.length(); regionEnd++) {
                assertThat(findSpans(matcher.reset(input, regionStart, regionEnd)))
                        .as("%s region %s-%s", label, regionStart, regionEnd)
                        .isEqualTo(findSpans(generic.reset(input, regionStart, regionEnd)));
            }
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

    private static Slice bytes(String hex)
    {
        return Slices.wrappedBuffer(hex.isEmpty() ? new byte[0] : HEX_FORMAT.parseHex(hex));
    }
}
