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
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.GENERAL;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.LITERAL_NEIGHBORS;
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
        // A group-zero matcher of a literal alternation, a literal set, or a fixed-width pattern,
        // whose characters are bytes in Rust's byte mode and UTF-8 characters otherwise, finds on
        // the pattern's span search in every language, and its spans are the generic matcher's in
        // every region of a Slice view that begins inside its byte array: regions whose end cuts a
        // match, which leaves `ab` of `abc|x|ab` and `abc|ab` and no fixed-width match, and
        // regions that start inside a match.
        String literalSet = IntStream.range(0, 65).mapToObj(index -> "w" + index + "q").collect(Collectors.joining("|")) + "|abc|ab";
        Map<String, Class<? extends GroupZeroSpanSearch>> expressions = Map.of(
                "abc|x|ab|ERROR|βeta", LiteralAlternationSpanMatcher.class,
                literalSet, LiteralSetSpanMatcher.class,
                "[0-9]{2}-[0-9]{2}", FixedWidthByteSpanMatcher.class,
                "[a-q][^u-z]{2}[x\\xC0-\\xFF]", FixedWidthByteSpanMatcher.class);
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
                assertSpanSearchRegions(pattern, entry.getValue(), input, pattern);
            }
        }
    }

    @Test
    public void testDotStarLiteralSpanRegions()
    {
        // A group-zero matcher of `.*literal.*` finds on the dot-star literal matcher in RE2, Java
        // with UNIX_LINES, whose dot is `[^\n]`, and Rust, in UTF-8 and bytes mode, and so do the
        // unanchored one-shot searches of a range: find, findInto with group zero alone or with a
        // second pair, and findResult. The second pair stays unset: it is group 1 for `(x){0}`, or
        // unused storage for a pattern with only group 0. Their spans are those of the generic
        // matcher of the pattern with the dots inside a capture, which keeps the program, in every
        // region: a region end inside the trailing dots ends the match there, one inside the
        // literal leaves no match, and the dots stop at a line feed and, in UTF-8, at the malformed
        // byte at the #. None of the one-shot searches runs the program. A pattern whose empty
        // capture the simplifier removes keeps that capturing group, which no match sets, so its
        // matcher takes no span search: in RE2 and Java, its finds reach the dot-star literal
        // matcher through the program hook, with offsets relative to the region's start, and its
        // one-shot searches reach it as every row's do. Rust drops the group.
        // The second input has short lines, a carriage return and multibyte characters on the
        // literal's line, a literal at a line's start, and the malformed byte between a line feed
        // and the literal. In it, a region that starts inside a line has a line feed before its
        // start.
        List<Slice> inputs = Stream.of("!!xERRORy\nzERRORβ ERR ERROR\u2028tail ERRO#ERRORz é?", "!!a\nb\nc\r\nd\ré€💰ERRORf\nERROR\ng#hERROR é?")
                .map(text -> {
                    byte[] bytes = utf8Slice(text).getBytes();
                    bytes[utf8Slice(text.substring(0, text.indexOf('#'))).length()] = (byte) 0xFF;
                    return wrappedBuffer(bytes, 2, bytes.length - 3);
                })
                .toList();
        // A long input, too long for every region, checks a few long windows of at least 256 bytes
        // that start past its first byte: the whole rest, one that ends inside the literal, and one
        // that starts inside the dots and ends inside the trailing dots.
        byte[] longBytes = utf8Slice("!!" + "\n".repeat(5) + "x".repeat(250) + "ERROR" + "y".repeat(40) + "?").getBytes();
        Slice longInput = wrappedBuffer(longBytes, 2, longBytes.length - 3);
        List<int[]> longRegions = List.of(new int[] {1, longInput.length()}, new int[] {1, 257}, new int[] {7, 280});

        record Row(CompiledPattern pattern, CompiledPattern captured) {}

        for (Slice input : inputs) {
            // Each input compiles its own patterns: the pattern's own generic matcher in
            // assertSpanSearchRegions builds the program search.
            List<Row> rows = List.of(
                    new Row(Re2.compile(utf8Slice(".*ERROR.*")), Re2.compile(utf8Slice("(.*ERROR.*)"))),
                    new Row(Re2.compile(utf8Slice(".*ERROR.*"), Re2.Options.latin1()), Re2.compile(utf8Slice("(.*ERROR.*)"), Re2.Options.latin1())),
                    new Row(JavaRegexp.compile(utf8Slice("(?d).*ERROR.*")), JavaRegexp.compile(utf8Slice("(?d)(.*ERROR.*)"))),
                    new Row(RustRegexp.compile(utf8Slice(".*ERROR.*")), RustRegexp.compile(utf8Slice("(.*ERROR.*)"))),
                    new Row(RustRegexp.compile(utf8Slice("(?-u).*ERROR.*"), RustRegexp.Options.bytes()), RustRegexp.compile(utf8Slice("(?-u)(.*ERROR.*)"), RustRegexp.Options.bytes())),
                    new Row(Re2.compile(utf8Slice(".*ERROR.*(x){0}")), Re2.compile(utf8Slice("(.*ERROR.*)"))),
                    new Row(Re2.compile(utf8Slice(".*ERROR.*(x){0}"), Re2.Options.latin1()), Re2.compile(utf8Slice("(.*ERROR.*)"), Re2.Options.latin1())),
                    new Row(JavaRegexp.compile(utf8Slice("(?d).*ERROR.*(x){0}")), JavaRegexp.compile(utf8Slice("(?d)(.*ERROR.*)"))),
                    // A one-byte literal has no contains search, so these find it with Slice.indexOf,
                    // which must not find an R past the region's end.
                    new Row(Re2.compile(utf8Slice(".*R.*")), Re2.compile(utf8Slice("(.*R.*)"))),
                    new Row(JavaRegexp.compile(utf8Slice("(?d).*R.*")), JavaRegexp.compile(utf8Slice("(?d)(.*R.*)"))),
                    new Row(RustRegexp.compile(utf8Slice(".*R.*")), RustRegexp.compile(utf8Slice("(.*R.*)"))));
            for (Row row : rows) {
                CompiledPattern pattern = row.pattern();
                String label = label(pattern) + " on " + HEX_FORMAT.formatHex(input.getBytes());
                assertThat(pattern.createDotStarLiteralSpanMatcher()).as(label).isNotNull();
                assertThat(row.captured().createDotStarLiteralSpanMatcher()).as(label).isNull();
                RegexpMatcher generic = row.captured().genericGroupZeroMatcherForDiagnostics(input);
                for (int regionStart = 0; regionStart <= input.length(); regionStart++) {
                    for (int regionEnd = regionStart; regionEnd <= input.length(); regionEnd++) {
                        assertOneShotRegion(pattern, generic, input, regionStart, regionEnd, label);
                    }
                }
                if (input == inputs.getFirst()) {
                    for (int[] region : longRegions) {
                        assertOneShotRegion(pattern, generic, longInput, region[0], region[1], label(pattern) + " on long input");
                    }
                }
                assertNoProgramSearch(pattern, label);
                if (pattern.capturingGroupCount() == 0) {
                    assertSpanSearchRegions(pattern, DotStarLiteralSpanMatcher.class, input, row.captured());
                }
                else {
                    assertCapturingMatcherRegions(pattern, input, row.captured());
                }
            }
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

    @Test
    public void testLiteralNeighbors()
    {
        // Full-Slice find of a class run, a literal, and a class run, such as an email-like
        // `([^ @]+)@([^ @]+)`, reads only the bytes beside each hit of the literal, in every
        // language, and builds no DFA on inputs the matcher decides, as where every neighbor is
        // ASCII or the pattern reads bytes and the input is shorter than the density sample.
        String email = "([^ @]+)@([^ @]+)";
        CompiledPattern latin1 = Re2.compile(utf8Slice(email), Re2.Options.latin1());
        CompiledPattern unicodeOff = RustRegexp.compile(utf8Slice("(?-u)" + email), RustRegexp.Options.bytes());
        List<CompiledPattern> neighbors = List.of(
                Re2.compile(utf8Slice(email)),
                latin1,
                JavaRegexp.compile(utf8Slice(email)),
                RustRegexp.compile(utf8Slice(email)),
                unicodeOff,
                RustRegexp.compile(utf8Slice(email), RustRegexp.Options.bytes()),
                TrinoRegexp.compile(utf8Slice(email)).re2(),
                Re2.compile(utf8Slice("[a-z]+::[0-9]+")),
                JavaRegexp.compile(utf8Slice("[a-z]+::[0-9]+")));
        for (CompiledPattern pattern : neighbors) {
            String label = label(pattern);
            assertThat(pattern.findPlanForDiagnostics()).as(label).isEqualTo(LITERAL_NEIGHBORS);
            String literal = pattern.pattern().toStringUtf8().contains("::") ? "::" : "@";
            assertThat(pattern.find(utf8Slice("a" + literal + "7"))).as(label).isTrue();
            assertThat(pattern.find(utf8Slice("mail x" + literal + "7.z"))).as(label).isTrue();
            for (String line : List.of("x " + literal + " 7", literal + "x", "x" + literal, "a" + literal + " " + literal + "7")) {
                assertThat(pattern.find(utf8Slice(line))).as("%s on %s", label, line).isFalse();
            }
            if (pattern == latin1 || pattern == unicodeOff) {
                // A pattern that reads bytes decides a non-ASCII neighbor too, as a whole
                // character: a member of `[^ @]` on both sides, and then a space after.
                assertThat(pattern.find(bytes("ff40ff"))).as(label).isTrue();
                assertThat(pattern.find(bytes("ff4020"))).as(label).isFalse();
            }
            assertNoProgramSearchOrPrefixDfa(pattern, label);
        }
        // A side that can match empty, an element after the second run, a minimum above one, and
        // RE2's case-insensitive pattern, whose `@` keeps its fold flag, keep the general engine,
        // as does a counted repeat, which the simplifier expands into nested optional copies. So
        // do a required prefix, though the suffix the analysis reads has the plan's form, and a
        // UTF-8 literal that is not ASCII. Java's case-insensitive class lists its case variants
        // in its ranges and clears its fold flag, so it takes the plan.
        for (String expression : List.of("[^ @]*@[^ @]+", "[^ @]+@[^ @]+x", "[^ @]+@[^ @]{2,}", "(?i)[a-z]+@[a-z]+", "[^ @]{1,3}@[^ @]+", "^ab[^ @]+@[^ @]+", "[^ @]+é[^ @]+")) {
            assertThat(Re2.compile(utf8Slice(expression)).findPlanForDiagnostics()).as(expression).isEqualTo(GENERAL);
        }
        CompiledPattern caseInsensitive = JavaRegexp.compile(utf8Slice("(?i)[a-z]+@[a-z]+"));
        assertThat(caseInsensitive.findPlanForDiagnostics()).isEqualTo(LITERAL_NEIGHBORS);
        assertThat(caseInsensitive.find(utf8Slice("x A@b"))).isTrue();
        assertThat(caseInsensitive.find(utf8Slice("A@7 7@b"))).isFalse();
        // A Latin-1 literal may be a byte at or above 0x80, a whole character there.
        Re2 highLiteral = Re2.compile(utf8Slice("[a-z]+\\xE9[0-9]+"), Re2.Options.latin1());
        assertThat(highLiteral.findPlanForDiagnostics()).isEqualTo(LITERAL_NEIGHBORS);
        assertThat(highLiteral.find(bytes("2061e93720"))).isTrue();
        assertThat(highLiteral.find(bytes("2061e92037"))).isFalse();

        // The plan yields to the forward DFAs: at the least budget at which a DFA initializes, the
        // matcher's reservation would leave it too little, so the matcher is left out and find
        // runs the general engine.
        Map<String, LongFunction<CompiledPattern>> compilers = Map.of(
                "Re2 " + email, maxMemory -> Re2.compile(utf8Slice(email), Re2.Options.defaults().setMaxMemory(maxMemory)),
                "Re2 longest-match " + email, maxMemory -> Re2.compile(utf8Slice(email), Re2.Options.defaults().setLongestMatch(true).setMaxMemory(maxMemory)),
                "JavaRegexp " + email, maxMemory -> JavaRegexp.compile(utf8Slice(email), JavaRegexp.Options.defaults().setMaxMemory(maxMemory)),
                "RustRegexp " + email, maxMemory -> RustRegexp.compile(utf8Slice(email), RustRegexp.Options.defaults().setMaxMemory(maxMemory)));
        for (Map.Entry<String, LongFunction<CompiledPattern>> compiler : compilers.entrySet()) {
            assertThat(compiler.getValue().apply(Re2.Options.DEFAULT_MAX_MEMORY).literalNeighborMatcherForDiagnostics()).as(compiler.getKey()).isNotNull();
            for (Dfa.DfaInstance.Kind kind : List.of(Dfa.DfaInstance.Kind.FIRST_MATCH, Dfa.DfaInstance.Kind.LONGEST_MATCH)) {
                CompiledPattern pattern = compileAtSmallestDfaBudget(compiler.getValue(), kind);
                String label = compiler.getKey() + " " + kind;
                assertThat(pattern.literalNeighborMatcherForDiagnostics()).as(label).isNull();
                assertThat(pattern.find(utf8Slice("mail x@y.z"))).as(label).isTrue();
                assertThat(pattern.find(utf8Slice("x @ y"))).as(label).isFalse();
            }
        }

        // The literal is at most 64 bytes long, the inner literal search's limit: the scan restarts
        // one byte past each hit, so a longer literal that overlaps itself would be compared again
        // in full at each of its positions.
        String longest = "a".repeat(InnerLiteralSearch.MAXIMUM_LITERAL_LENGTH);
        String tooLong = "a".repeat(InnerLiteralSearch.MAXIMUM_LITERAL_LENGTH + 1);
        Re2 longestLiteral = Re2.compile(utf8Slice("[bc]+" + longest + "[bc]+"));
        assertThat(longestLiteral.findPlanForDiagnostics()).isEqualTo(LITERAL_NEIGHBORS);
        assertThat(longestLiteral.find(utf8Slice("b" + longest + "c"))).isTrue();
        assertThat(longestLiteral.find(utf8Slice("b" + tooLong + "c"))).isFalse();
        Re2 tooLongLiteral = Re2.compile(utf8Slice("[bc]+" + tooLong + "[bc]+"));
        assertThat(tooLongLiteral.findPlanForDiagnostics()).isEqualTo(GENERAL);
        assertThat(tooLongLiteral.find(utf8Slice("b" + tooLong + "c"))).isTrue();

        // The first hit the matcher cannot decide ends its scan, and the engine decides from the
        // input's start, so a line of such hits costs the engine's search and no scan of the hits
        // after the first: the matcher leaves this line undecided although its last hit, `a@b`,
        // would prove a match. The line is shorter than the density sample below reads.
        Slice undecidedFirst = utf8Slice("\u00E9@\u00E9 ".repeat(40) + "a@b");
        for (CompiledPattern pattern : List.of(
                Re2.compile(utf8Slice(email)),
                JavaRegexp.compile(utf8Slice(email)),
                RustRegexp.compile(utf8Slice(email)),
                RustRegexp.compile(utf8Slice(email), RustRegexp.Options.bytes()),
                TrinoRegexp.compile(utf8Slice(email)).re2())) {
            String label = label(pattern);
            assertThat(pattern.literalNeighborMatcherForDiagnostics().find(undecidedFirst)).as(label).isEqualTo(LiteralNeighborMatcher.UNDECIDED);
            assertThat(pattern.find(undecidedFirst)).as(label).isTrue();
        }

        // On an input of at least the sample's minimum, a one-byte literal that the DFA's small
        // byte-set density sample finds dense, as a space is in prose, leaves the find to the
        // engine before any scan, even where the first hit would decide it: on digit-free prose,
        // restarting the scan at every space, each rejected by the letter after it, took twice
        // the engine's single pass. A longer view behind padding without the literal is sampled
        // from its own bytes. A line one byte shorter is scanned without the sample, and a
        // sparse literal, as email's `@` is in the same prose, is scanned at any length.
        String sentence = "the quick brown fox jumps over the lazy dog ";
        String prose = sentence.repeat(LiteralNeighborMatcher.MIN_DENSITY_SAMPLE_BYTES / sentence.length() + 1);
        Slice dense = utf8Slice(prose.substring(0, LiteralNeighborMatcher.MIN_DENSITY_SAMPLE_BYTES));
        byte[] paddedDense = ("x".repeat(LiteralNeighborMatcher.MIN_DENSITY_SAMPLE_BYTES) + prose.substring(0, LiteralNeighborMatcher.MIN_DENSITY_SAMPLE_BYTES + 1)).getBytes(UTF_8);
        Slice denseView = wrappedBuffer(paddedDense, LiteralNeighborMatcher.MIN_DENSITY_SAMPLE_BYTES, LiteralNeighborMatcher.MIN_DENSITY_SAMPLE_BYTES + 1);
        Slice denseMatch = utf8Slice("x 7" + prose.substring(0, LiteralNeighborMatcher.MIN_DENSITY_SAMPLE_BYTES - 3));
        Slice shorter = utf8Slice(prose.substring(0, LiteralNeighborMatcher.MIN_DENSITY_SAMPLE_BYTES - 1));
        String spaceDigits = "[a-z]+ [0-9]+";
        for (Function<String, CompiledPattern> compiler : List.<Function<String, CompiledPattern>>of(
                pattern -> Re2.compile(utf8Slice(pattern)),
                pattern -> JavaRegexp.compile(utf8Slice(pattern)),
                pattern -> RustRegexp.compile(utf8Slice(pattern)),
                pattern -> TrinoRegexp.compile(utf8Slice(pattern)).re2())) {
            CompiledPattern pattern = compiler.apply(spaceDigits);
            String label = label(pattern);
            assertThat(pattern.findPlanForDiagnostics()).as(label).isEqualTo(LITERAL_NEIGHBORS);
            LiteralNeighborMatcher matcher = pattern.literalNeighborMatcherForDiagnostics();
            assertThat(matcher.find(dense)).as(label).isEqualTo(LiteralNeighborMatcher.UNDECIDED);
            assertThat(matcher.find(denseMatch)).as(label).isEqualTo(LiteralNeighborMatcher.UNDECIDED);
            assertThat(matcher.find(denseView)).as(label).isEqualTo(LiteralNeighborMatcher.UNDECIDED);
            assertThat(matcher.find(shorter)).as(label).isEqualTo(LiteralNeighborMatcher.NO_MATCH);
            assertThat(pattern.find(dense)).as(label).isFalse().isEqualTo(pattern.find(dense, 0, dense.length()));
            assertThat(pattern.find(denseMatch)).as(label).isTrue().isEqualTo(pattern.find(denseMatch, 0, denseMatch.length()));
            assertThat(pattern.find(shorter)).as(label).isFalse();

            CompiledPattern emailPattern = compiler.apply(email);
            LiteralNeighborMatcher emailMatcher = emailPattern.literalNeighborMatcherForDiagnostics();
            assertThat(emailMatcher.find(utf8Slice(prose.substring(0, LiteralNeighborMatcher.MIN_DENSITY_SAMPLE_BYTES) + "a@b"))).as(label(emailPattern)).isEqualTo(LiteralNeighborMatcher.MATCH);
            assertThat(emailMatcher.find(utf8Slice(prose.substring(0, LiteralNeighborMatcher.MIN_DENSITY_SAMPLE_BYTES) + "a @ b"))).as(label(emailPattern)).isEqualTo(LiteralNeighborMatcher.NO_MATCH);
        }

        // Each pattern agrees with the general engine on random lines, as views between bytes that
        // would complete a match: hits at the view's edges, doubled literals, spaces, and multibyte
        // and malformed bytes beside the literal, which leave a UTF-8 class with non-ASCII members
        // to the engine.
        String[] tokens = {"a", "@", "@@", "::", ":", " ", "7", "é", "€", "\n"};
        byte[][] malformed = {{(byte) 0xFF}, {(byte) 0x80}, {(byte) 0xC3}, {(byte) 0xE2, (byte) 0x80}, {(byte) 0xC0, (byte) 0xAF}};
        Random random = new Random(20261108);
        for (CompiledPattern pattern : neighbors) {
            String label = label(pattern);
            for (int inputIndex = 0; inputIndex < 2_000; inputIndex++) {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                text.writeBytes("1a".getBytes(UTF_8));
                int tokenCount = random.nextInt(10);
                for (int tokenIndex = 0; tokenIndex < tokenCount; tokenIndex++) {
                    text.writeBytes(random.nextInt(4) == 0 ? malformed[random.nextInt(malformed.length)] : tokens[random.nextInt(tokens.length)].getBytes(UTF_8));
                }
                text.writeBytes("1a".getBytes(UTF_8));
                byte[] backing = text.toByteArray();
                Slice input = wrappedBuffer(backing, 2, backing.length - 4);
                assertThat(pattern.find(input)).as("%s on %s", label, HEX_FORMAT.formatHex(backing, 2, backing.length - 2)).isEqualTo(pattern.find(input, 0, input.length()));
            }
        }
    }

    @Test
    public void testLiteralNeighborsStepAsideForRequiredLiteralHandoff()
    {
        // A find of at least the required bytes' window limit whose required literal hands its
        // first hit to the inner literal search takes that handoff, as without the plan, in every
        // language, where the matcher would have decided from the bytes beside the hit: the
        // handoff's search builds its reverse prefix DFA, where the matcher builds none. A line
        // one byte shorter keeps the matcher. Each find agrees with the ordinary engine, with a
        // match and without.
        String aa = "[a-z]+aa[a-z]+";
        List<Function<String, CompiledPattern>> compilers = List.of(
                pattern -> Re2.compile(utf8Slice(pattern)),
                pattern -> Re2.compile(utf8Slice(pattern), Re2.Options.latin1()),
                pattern -> JavaRegexp.compile(utf8Slice(pattern)),
                pattern -> RustRegexp.compile(utf8Slice(pattern)),
                pattern -> RustRegexp.compile(utf8Slice("(?-u)" + pattern), RustRegexp.Options.bytes()),
                pattern -> RustRegexp.compile(utf8Slice(pattern), RustRegexp.Options.bytes()),
                pattern -> TrinoRegexp.compile(utf8Slice(pattern)).re2());
        for (Function<String, CompiledPattern> compiler : compilers) {
            CompiledPattern pattern = compiler.apply(aa);
            // The expected answers come from a second compile, so the DFAs its ranged finds build
            // stay off the pattern under test.
            CompiledPattern ordinary = compiler.apply(aa);
            String label = label(pattern);
            assertThat(pattern.findPlanForDiagnostics()).as(label).isEqualTo(LITERAL_NEIGHBORS);
            RequiredBytes bytes = pattern.requiredBytesForDiagnostics();
            assertThat(bytes.handsOffToInnerLiteralSearch()).as(label).isTrue();
            int windowLimit = bytes.windowLimit();
            LiteralNeighborMatcher matcher = pattern.literalNeighborMatcherForDiagnostics();
            for (int length : List.of(windowLimit - 1, windowLimit)) {
                Slice match = utf8Slice(" ".repeat(length - 4) + "baab");
                Slice noMatch = utf8Slice(" ".repeat(length - 4) + "baa ");
                assertThat(matcher.find(match)).as("%s at %s", label, length).isEqualTo(LiteralNeighborMatcher.MATCH);
                assertThat(matcher.find(noMatch)).as("%s at %s", label, length).isEqualTo(LiteralNeighborMatcher.NO_MATCH);
                assertThat(pattern.find(noMatch)).as("%s at %s", label, length).isFalse().isEqualTo(ordinary.find(noMatch, 0, length));
                assertThat(pattern.find(match)).as("%s at %s", label, length).isTrue().isEqualTo(ordinary.find(match, 0, length));
                if (length < windowLimit) {
                    assertNoProgramSearchOrPrefixDfa(pattern, label);
                }
            }
            assertThat(pattern.innerLiteralSearchForDiagnostics().reversePrefixProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH))
                    .as("%s reverse prefix DFA", label)
                    .isNotNull();
        }
    }

    @Test
    public void testEmailLinesReachNoDfa()
    {
        // On lines like those of lh3lh3's regex HOWTO, where each `@` has ASCII bytes beside it,
        // letters, spaces, or punctuation, and any non-ASCII text lies elsewhere in the line, the
        // literal-neighbor matcher decides every email find, in every language. It leaves no line
        // to the engine, the only lines that could reach a DFA, and the pattern builds no DFA of
        // its program or of its search's reverse prefix.
        String email = "([^ @]+)@([^ @]+)";
        String[] words = {"the", "regex", "HOWTO", "a", "(re)", "x,", "1999", "café", "naïve", "—", "“quoted”", "lh3@example.org", "<user.name@host.co>", "@", "@home", "foo@", "a @ b", "\t"};
        Random random = new Random(20261109);
        List<Slice> lines = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < 5_000; lineIndex++) {
            lines.add(utf8Slice(IntStream.range(0, random.nextInt(16)).mapToObj(_ -> words[random.nextInt(words.length)]).collect(Collectors.joining(" "))));
        }
        List<Function<String, CompiledPattern>> compilers = List.of(
                pattern -> Re2.compile(utf8Slice(pattern)),
                pattern -> Re2.compile(utf8Slice(pattern), Re2.Options.latin1()),
                pattern -> JavaRegexp.compile(utf8Slice(pattern)),
                pattern -> RustRegexp.compile(utf8Slice(pattern)),
                pattern -> RustRegexp.compile(utf8Slice("(?-u)" + pattern), RustRegexp.Options.bytes()),
                pattern -> RustRegexp.compile(utf8Slice(pattern), RustRegexp.Options.bytes()),
                pattern -> TrinoRegexp.compile(utf8Slice(pattern)).re2());
        for (Function<String, CompiledPattern> compiler : compilers) {
            CompiledPattern pattern = compiler.apply(email);
            // The expected answers come from a second compile, so the DFAs its ranged finds build
            // stay off the pattern under test.
            CompiledPattern ordinary = compiler.apply(email);
            String label = label(pattern);
            int matchingLines = 0;
            for (Slice line : lines) {
                String description = "%s on %s".formatted(label, line.toStringUtf8());
                assertThat(pattern.literalNeighborMatcherForDiagnostics().find(line)).as(description).isNotEqualTo(LiteralNeighborMatcher.UNDECIDED);
                boolean found = pattern.find(line);
                assertThat(found).as(description).isEqualTo(ordinary.find(line, 0, line.length()));
                matchingLines += found ? 1 : 0;
            }
            assertThat(matchingLines).as(label).isPositive();
            assertNoProgramSearchOrPrefixDfa(pattern, label);
        }
    }

    // Requires `pattern` to have built no DFA of its program and, where it has an inner literal
    // search, none of the search's reverse prefix.
    private static void assertNoProgramSearchOrPrefixDfa(CompiledPattern pattern, String label)
    {
        assertNoProgramSearch(pattern, label);
        InnerLiteralSearch search = pattern.innerLiteralSearchForDiagnostics();
        if (search != null) {
            for (Dfa.DfaInstance.Kind kind : Dfa.DfaInstance.Kind.values()) {
                assertThat(search.reversePrefixProgramForDiagnostics().cachedDfaIfPresent(kind)).as("%s reverse prefix %s DFA", label, kind).isNull();
            }
        }
    }

    // The pattern's class, text, and encoding, which tell RE2's UTF-8 and Latin-1 cases apart.
    private static String label(CompiledPattern pattern)
    {
        return pattern.getClass().getSimpleName() + " " + pattern.pattern().toStringUtf8() + ((pattern.flags() & Regexp.LATIN1) != 0 ? " Latin-1" : " UTF-8");
    }

    /// Requires the unanchored one-shot searches of `pattern` in `[regionStart, regionEnd)` of
    /// `input`, a range find, findInto with group zero alone and with a second pair, and
    /// findResult, to report the first span `generic` finds there. The second pair stays unset:
    /// it is group 1 for `(x){0}`, or unused storage for a pattern with only group 0.
    private static void assertOneShotRegion(CompiledPattern pattern, RegexpMatcher generic, Slice input, int regionStart, int regionEnd, String label)
    {
        String region = "%s region %s-%s".formatted(label, regionStart, regionEnd);
        boolean found = generic.reset(input, regionStart, regionEnd).find();
        int[] span = found ? new int[] {generic.start(), generic.end()} : new int[] {-1, -1};
        assertThat(pattern.find(input, regionStart, regionEnd)).as(region).isEqualTo(found);
        int[] groupZero = {7, 7};
        assertThat(pattern.findInto(input, regionStart, regionEnd, groupZero)).as(region).isEqualTo(found);
        assertThat(groupZero).as(region).containsExactly(span);
        int[] extraPair = {7, 7, 7, 7};
        assertThat(pattern.findInto(input, regionStart, regionEnd, extraPair)).as(region).isEqualTo(found);
        assertThat(extraPair).as(region).containsExactly(span[0], span[1], -1, -1);
        MatchResult result = pattern.findResult(input, regionStart, regionEnd);
        assertThat(result != null).as(region).isEqualTo(found);
        if (result != null) {
            assertThat(new int[] {result.start(), result.end()}).as(region).containsExactly(span);
        }
    }

    /// Requires `pattern`'s group-zero matchers to find on its span search, an instance of
    /// `spanSearch`, and to report the spans of `oracle`'s generic matcher in every region of
    /// `input`. The oracle is `pattern` itself, or a pattern of the same group-zero spans whose
    /// generic matcher runs the program where `pattern`'s would not.
    private static void assertSpanSearchRegions(CompiledPattern pattern, Class<?> spanSearch, Slice input, CompiledPattern oracle)
    {
        String label = label(pattern);
        assertThat(pattern.groupZeroSpanSearch()).as(label).isInstanceOf(spanSearch);
        assertThat(pattern.matcher(input).spanSearchForDiagnostics()).as(label).isSameAs(pattern.groupZeroSpanSearch());
        assertThat(pattern.matcher(input, 0).spanSearchForDiagnostics()).as(label).isSameAs(pattern.groupZeroSpanSearch());
        assertThat(findSpans(pattern.matcher(input))).as(label).isNotEmpty();
        assertNoProgramSearch(pattern, label);
        assertThat(pattern.genericGroupZeroMatcherForDiagnostics(input).spanSearchForDiagnostics()).as(label).isNull();
        RegexpMatcher matcher = pattern.matcher(input, 0);
        RegexpMatcher generic = oracle.genericGroupZeroMatcherForDiagnostics(input);
        for (int regionStart = 0; regionStart <= input.length(); regionStart++) {
            for (int regionEnd = regionStart; regionEnd <= input.length(); regionEnd++) {
                assertThat(findSpans(matcher.reset(input, regionStart, regionEnd)))
                        .as("%s region %s-%s", label, regionStart, regionEnd)
                        .isEqualTo(findSpans(generic.reset(input, regionStart, regionEnd)));
            }
        }
    }

    /// Requires the matcher of `pattern`, whose one capturing group never participates, to take no
    /// span search, to report the spans of `oracle`'s generic matcher with that group unset in
    /// every region of `input`, and to run no program search.
    private static void assertCapturingMatcherRegions(CompiledPattern pattern, Slice input, CompiledPattern oracle)
    {
        String label = label(pattern);
        assertThat(pattern.capturingGroupCount()).as(label).isEqualTo(1);
        RegexpMatcher matcher = pattern.matcher(input);
        assertThat(matcher.spanSearchForDiagnostics()).as(label).isNull();
        RegexpMatcher generic = oracle.genericGroupZeroMatcherForDiagnostics(input);
        for (int regionStart = 0; regionStart <= input.length(); regionStart++) {
            for (int regionEnd = regionStart; regionEnd <= input.length(); regionEnd++) {
                String region = "%s region %s-%s".formatted(label, regionStart, regionEnd);
                matcher.reset(input, regionStart, regionEnd);
                generic.reset(input, regionStart, regionEnd);
                while (generic.find()) {
                    assertThat(matcher.find()).as(region).isTrue();
                    assertThat(new int[] {matcher.start(), matcher.end(), matcher.start(1), matcher.end(1)}).as(region).containsExactly(generic.start(), generic.end(), -1, -1);
                }
                assertThat(matcher.find()).as(region).isFalse();
            }
        }
        assertNoProgramSearch(pattern, label);
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
