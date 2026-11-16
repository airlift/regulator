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
import static io.airlift.regulator.CompiledPattern.Shape.EXACT_LITERAL;
import static io.airlift.regulator.TestingPatternShapes.assertNoProgramSearch;
import static io.airlift.regulator.TestingPatternShapes.assertProgramSearch;
import static io.airlift.regulator.TestingPatternShapes.compileAtSmallestDfaBudget;
import static io.airlift.regulator.TestingPatternShapes.shape;
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
        // A group-zero matcher of `.*literal.*` finds on the dot-star literal matcher in RE2, Java,
        // with and without UNIX_LINES, and Rust, with and without CRLF mode, in UTF-8 and bytes
        // mode, and so do the unanchored one-shot searches of a range: find, findInto with group
        // zero alone or with a second pair, and findResult. The second pair stays unset: it is
        // group 1 for `(x){0}`, or unused storage for a pattern with only group 0. Their spans are
        // those of the generic matcher of the pattern with the dots inside a capture, which keeps
        // the program, in every region: a region end inside the trailing dots ends the match there,
        // one inside the literal leaves no match, and the dots stop at a line feed, at the other
        // stops of Java's default dot and Rust's CRLF dot, and, in UTF-8, at the malformed byte at
        // the #. None of the one-shot searches runs the program. A pattern whose empty
        // capture the simplifier removes keeps that capturing group, which no match sets, so its
        // matcher takes no span search: in RE2 and Java, its finds reach the dot-star literal
        // matcher through the program hook, with offsets relative to the region's start, and its
        // one-shot searches reach it as every row's do. Rust drops the group.
        // The second input has short lines, a carriage return and multibyte characters on the
        // literal's line, a literal at a line's start, and the malformed byte between a line feed
        // and the literal. In it, a region that starts inside a line has a line feed before its
        // start. The third has each of Java's line terminators beside a literal, a carriage return
        // and a line feed on either side of one in both orders, and the characters on either side
        // of U+0085 and of U+2028 and U+2029, whose encodings share their lead bytes, and its
        // regions cut each multibyte character short.
        List<Slice> inputs = Stream.of(
                        "!!xERRORy\nzERRORβ ERR ERROR\u2028tail ERRO#ERRORz é?",
                        "!!a\nb\nc\r\nd\ré€💰ERRORf\nERROR\ng#hERROR é?",
                        "!!\u0085ERROR\rERROR\r\n\u0084ERROR\u0086\r\u2029\u2027ERROR\u202A\rERROR\n#\u2028ERROR\u2028?")
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
                    new Row(JavaRegexp.compile(utf8Slice(".*ERROR.*")), JavaRegexp.compile(utf8Slice("(.*ERROR.*)"))),
                    new Row(RustRegexp.compile(utf8Slice(".*ERROR.*")), RustRegexp.compile(utf8Slice("(.*ERROR.*)"))),
                    new Row(RustRegexp.compile(utf8Slice("(?R).*ERROR.*")), RustRegexp.compile(utf8Slice("(?R)(.*ERROR.*)"))),
                    new Row(RustRegexp.compile(utf8Slice("(?-u).*ERROR.*"), RustRegexp.Options.bytes()), RustRegexp.compile(utf8Slice("(?-u)(.*ERROR.*)"), RustRegexp.Options.bytes())),
                    new Row(RustRegexp.compile(utf8Slice("(?R-u).*ERROR.*"), RustRegexp.Options.bytes()), RustRegexp.compile(utf8Slice("(?R-u)(.*ERROR.*)"), RustRegexp.Options.bytes())),
                    new Row(Re2.compile(utf8Slice(".*ERROR.*(x){0}")), Re2.compile(utf8Slice("(.*ERROR.*)"))),
                    new Row(Re2.compile(utf8Slice(".*ERROR.*(x){0}"), Re2.Options.latin1()), Re2.compile(utf8Slice("(.*ERROR.*)"), Re2.Options.latin1())),
                    new Row(JavaRegexp.compile(utf8Slice("(?d).*ERROR.*(x){0}")), JavaRegexp.compile(utf8Slice("(?d)(.*ERROR.*)"))),
                    new Row(JavaRegexp.compile(utf8Slice(".*ERROR.*(x){0}")), JavaRegexp.compile(utf8Slice("(.*ERROR.*)"))),
                    // A one-byte literal has no contains search, so these find it with Slice.indexOf,
                    // which must not find an R past the region's end.
                    new Row(Re2.compile(utf8Slice(".*R.*")), Re2.compile(utf8Slice("(.*R.*)"))),
                    new Row(JavaRegexp.compile(utf8Slice("(?d).*R.*")), JavaRegexp.compile(utf8Slice("(?d)(.*R.*)"))),
                    new Row(JavaRegexp.compile(utf8Slice(".*R.*")), JavaRegexp.compile(utf8Slice("(.*R.*)"))),
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
    public void testExactLiteralSpanRegions()
    {
        // A nonempty exact literal's unanchored one-shot searches of a range, find, findInto with
        // group zero alone or with a second pair, which stays unset, and findResult, in RE2, Java,
        // and Rust, and a group-zero matcher's finds in every language, Trino's included, take the
        // exact-literal span search and run no program. Their spans are those of the generic
        // matcher of the literal inside a capture, which keeps the program, in every region of a
        // Slice view that begins inside its byte array: a region end inside an occurrence leaves
        // it unmatched, `aab` holds `ab` after a false start, and nothing matches the malformed
        // byte at the #. The long input's windows of at least 64 bytes take the literal's prefix
        // scan, which must not report an occurrence that ends past the region's end. A one-byte
        // literal's matcher finds on the single-byte table, so only its one-shot searches are
        // checked here. Java's `(?x)a b` is the literal `ab`, so it takes the route too.
        String text = "!!ab aab ERRORβeta ERRERROR R#ab ERROR βeta ab?";
        byte[] bytes = utf8Slice(text).getBytes();
        bytes[utf8Slice(text.substring(0, text.indexOf('#'))).length()] = (byte) 0xFF;
        Slice input = wrappedBuffer(bytes, 2, bytes.length - 3);
        byte[] longBytes = utf8Slice("!!ab" + "x".repeat(100) + "aab" + "x".repeat(80) + "ERRORβeta" + "x".repeat(70) + "R?").getBytes();
        Slice longInput = wrappedBuffer(longBytes, 2, longBytes.length - 3);

        record Row(CompiledPattern pattern, CompiledPattern captured, boolean oneShot) {}

        List<Row> rows = new ArrayList<>();
        for (String literal : List.of("ab", "ERROR", "βeta", "R")) {
            rows.add(new Row(Re2.compile(utf8Slice(literal)), Re2.compile(utf8Slice("(" + literal + ")")), true));
            rows.add(new Row(JavaRegexp.compile(utf8Slice(literal)), JavaRegexp.compile(utf8Slice("(" + literal + ")")), true));
            rows.add(new Row(RustRegexp.compile(utf8Slice(literal)), RustRegexp.compile(utf8Slice("(" + literal + ")")), true));
            rows.add(new Row(TrinoRegexp.compile(utf8Slice(literal)).re2(), TrinoRegexp.compile(utf8Slice("(" + literal + ")")).re2(), false));
        }
        rows.add(new Row(Re2.compile(utf8Slice("ab"), Re2.Options.latin1()), Re2.compile(utf8Slice("(ab)"), Re2.Options.latin1()), true));
        rows.add(new Row(RustRegexp.compile(utf8Slice("(?-u)ab"), RustRegexp.Options.bytes()), RustRegexp.compile(utf8Slice("(?-u)(ab)"), RustRegexp.Options.bytes()), true));
        rows.add(new Row(JavaRegexp.compile(utf8Slice("(?x)a b")), JavaRegexp.compile(utf8Slice("(?x)(a b)")), true));
        for (Row row : rows) {
            CompiledPattern pattern = row.pattern();
            String label = label(pattern);
            assertThat(shape(pattern)).as(label).isEqualTo(EXACT_LITERAL);
            assertThat(shape(row.captured())).as(label(row.captured())).isEqualTo(EXACT_LITERAL);
            if (row.oneShot()) {
                RegexpMatcher generic = row.captured().genericGroupZeroMatcherForDiagnostics(input);
                for (int regionStart = 0; regionStart <= input.length(); regionStart++) {
                    for (int regionEnd = regionStart; regionEnd <= input.length(); regionEnd++) {
                        assertOneShotRegion(pattern, generic, input, regionStart, regionEnd, label);
                    }
                }
                RegexpMatcher longGeneric = row.captured().genericGroupZeroMatcherForDiagnostics(longInput);
                for (int regionStart : List.of(0, 1, 2, 3, 103)) {
                    for (int regionEnd = regionStart; regionEnd <= longInput.length(); regionEnd++) {
                        assertOneShotRegion(pattern, longGeneric, longInput, regionStart, regionEnd, label + " on long input");
                    }
                }
                int[] groups = {7, 7};
                assertThat(pattern.findInto(longInput, groups)).as(label).isTrue();
                longGeneric.reset(longInput);
                assertThat(longGeneric.find()).as(label).isTrue();
                assertThat(groups).as(label).containsExactly(longGeneric.start(), longGeneric.end());
                assertNoProgramSearch(pattern, label);
            }
            if (pattern.sharedSingleByteMatcher() == null) {
                assertThat(pattern.groupZeroSpanSearch()).as(label).isSameAs(pattern);
                assertSpanSearchRegions(pattern, pattern.getClass(), input, row.captured());
                assertSpanSearchRegions(pattern, pattern.getClass(), longInput, row.captured());
                assertNoProgramSearch(pattern, label);
            }
        }

        // A one-shot search of a literal with captures keeps the program, which reports them, and
        // so does one of a Java pattern whose flags make it no exact literal.
        Re2 captures = Re2.compile(utf8Slice("(ab)"));
        int[] groups = new int[4];
        assertThat(captures.findInto(input, groups)).isTrue();
        assertThat(groups).containsExactly(0, 2, 0, 2);
        assertProgramSearch(captures, label(captures));
        JavaRegexp caseInsensitive = JavaRegexp.compile(utf8Slice("(?i)ab"));
        assertThat(shape(caseInsensitive)).isNotEqualTo(EXACT_LITERAL);
        assertThat(caseInsensitive.findInto(utf8Slice("xAb"), new int[2])).isTrue();
        assertProgramSearch(caseInsensitive, label(caseInsensitive));
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
