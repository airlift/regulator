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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.airlift.regulator.TestingPatternShapes.assertNoProgramSearch;
import static io.airlift.regulator.TestingPatternShapes.assertProgramSearch;
import static io.airlift.regulator.TestingPatternShapes.compileAtSmallestDfaBudget;
import static io.airlift.regulator.TestingPatternShapes.shape;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDotStarLiteralSpanMatcher
{
    private static final String DENSE_RUNS = IntStream.rangeClosed(1, 24).mapToObj(length -> "a".repeat(length) + "é").collect(Collectors.joining());

    @Test
    public void testNativeBoundaries()
    {
        DotStarLiteralSpanMatcher matcher = matcher(".*coolfunctionname.*");

        assertSpan(matcher, utf8Slice("prefix-coolfunctionname-suffix"), 0, 0, 30);
        assertSpan(matcher, utf8Slice("coolfunctionname-middle-coolfunctionname"), 0, 0, 40);
        assertSpan(matcher, utf8Slice("before\nxxcoolfunctionnameyy\nafter"), 0, 7, 27);
        assertSpan(matcher, utf8Slice("abc--coolfunctionname--tail"), 3, 3, 27);
        assertSpan(matcher, utf8Slice("skip\nxxcoolfunctionnameyy\nafter"), 2, 5, 25);
        assertSpan(matcher, utf8Slice("é💰coolfunctionnameж終"), 0, 0, 27);
        assertThat(matcher.findSpan(utf8Slice("ordinaryFunction"), 0)).isEqualTo(Dfa.SEARCH_NO_MATCH);

        // The match starts after the last line feed before the literal, past a byte after it that
        // leads no character, and at a start after that line feed.
        assertSpan(matcher, utf8Slice("a\nbb\nc\nd\nxxcoolfunctionnameyy\nz"), 0, 9, 29);
        assertSpan(matcher(".*literal.*"), bytes("61 0A 62 FF 63 6C69746572616C"), 0, 4, 12);
        assertSpan(matcher, utf8Slice("skip\nxxcoolfunctionnameyy"), 6, 6, 25);
    }

    @Test
    public void testMalformedUtf8Boundaries()
    {
        // A byte that leads no character, before the literal, starts the match after it, and
        // after the literal ends the match.
        DotStarLiteralSpanMatcher matcher = matcher(".*coolfunctionname.*");
        assertSpan(
                matcher,
                wrappedBuffer(new byte[] {
                        'a', ' ', (byte) 0xFF,
                        'c', 'o', 'o', 'l', 'f', 'u', 'n', 'c', 't', 'i', 'o', 'n', 'n', 'a', 'm', 'e', ' ', 'z',
                }),
                0,
                3,
                21);
        assertSpan(
                matcher,
                wrappedBuffer(new byte[] {
                        'a', ' ',
                        'c', 'o', 'o', 'l', 'f', 'u', 'n', 'c', 't', 'i', 'o', 'n', 'n', 'a', 'm', 'e',
                        (byte) 0xFF, ' ', 'z',
                }),
                0,
                0,
                18);
        // The UTF-8 dot reads an encoded surrogate, an overlong three-byte form, and a value above
        // U+10FFFF as one character each, as the program's byte ranges for `[^\n]` do.
        assertSpan(matcher(".*literal.*"), bytes("61 EDA080 E08080 F4908080 6C69746572616C 7A"), 0, 0, 19);

        // In every language, the spans and counts are the general engine's, run on the pattern
        // with its dots inside a capture, on each malformed sequence before, after, and on both
        // sides of the literal, at the input's ends, and between the last of several line feeds
        // and the literal. A run of 100 ASCII bytes before the literal or between it and a
        // malformed sequence or a line feed takes the vector scan from some starts and is walked
        // byte by byte from the rest, by the rule the scan counts below pin.
        String longAscii = "78".repeat(100);
        List<String> malformedSequences = List.of(
                // Bare continuation bytes.
                "80",
                "BF",
                // Overlong two-byte forms, which no lead byte C0 or C1 starts.
                "C080",
                "C1BF",
                // Sequences cut short by an ASCII byte or by the end. C2 is the smallest UTF-8 lead
                // byte, cut short here; Latin-1 reads every byte alone.
                "C2",
                "C3",
                "E282",
                "E280",
                "F09F92",
                // Overlong three- and four-byte forms, an encoded surrogate, and values above
                // U+10FFFF, which the UTF-8 dot of `[^\n]` or `[^\r\n]` accepts, and Java's
                // default dot, whose class leaves out non-ASCII characters, accepts only the
                // surrogate.
                "E08080",
                "F0808080",
                "EDA080",
                "EDBFBF",
                "F4908080",
                // Lead bytes of no UTF-8 sequence.
                "F5808080",
                "FF",
                // A line feed after a cut-short sequence.
                "E2820A");
        List<Slice> inputs = new ArrayList<>();
        for (String malformed : malformedSequences) {
            for (String input : List.of(
                    "61 %s 6C69746572616C 7A",
                    "61 6C69746572616C %s 7A",
                    "%s 6C69746572616C %s",
                    "6C69746572616C %s",
                    "%s 6C69746572616C",
                    "61 %s 62 6C69746572616C 63 %s 6C69746572616C %s 64",
                    "%s L 6C69746572616C L %s",
                    "61 %s L 6C69746572616C L 0A L 6C69746572616C L %s",
                    "61 0A 62 0A %s 63 6C69746572616C 7A",
                    "61 0A L %s L 6C69746572616C")) {
                inputs.add(bytes(input.replace("%s", malformed).replace("L", longAscii)));
            }
        }
        for (Row row : everyLanguage()) {
            CompiledPattern pattern = row.pattern();
            assertGeneralEngineAgreement(row, inputs);
            DotStarLiteralSpanMatcher spanMatcher = pattern.createDotStarLiteralSpanMatcher();

            // A run of ASCII bytes takes the vector scan when the walk has read 16 of its bytes,
            // DotStarLiteralSpanMatcher.INLINE_DOT_ASCII_BYTES, in its inline loop and at least
            // CompiledPattern.MINIMUM_LONG_SEARCH_BYTES remain before the literal or the region
            // end; any other run is walked byte by byte. So the long runs on both sides of the
            // literal take one scan each, as does a run of 16 + MINIMUM_LONG_SEARCH_BYTES bytes
            // before it, while a run one byte shorter, one-byte runs between two-byte characters,
            // and the short row take none.
            int shortestScannedRun = 16 + CompiledPattern.MINIMUM_LONG_SEARCH_BYTES;
            Map<String, Integer> vectorScans = Map.of(
                    "61 L 6C69746572616C L 7A", 2,
                    "61 6C69746572616C 7A", 0,
                    "61C3A9".repeat(300) + " 6C69746572616C", 0,
                    "78".repeat(shortestScannedRun - 1) + " 6C69746572616C", 0,
                    "78".repeat(shortestScannedRun) + " 6C69746572616C", 1);
            for (Map.Entry<String, Integer> entry : vectorScans.entrySet()) {
                Slice text = bytes(entry.getKey().replace("L", longAscii));
                String label = "%s %s".formatted(label(pattern), entry.getKey());
                DotStarLiteralSpanMatcher.ScanDiagnostics diagnostics = new DotStarLiteralSpanMatcher.ScanDiagnostics();
                assertThat(spanMatcher.findSpanForDiagnostics(text, 0, text.length(), diagnostics)).as(label).isEqualTo((long) text.length());
                assertThat(diagnostics.vectorScanCount()).as(label).isEqualTo(VectorSupport.isAvailable() ? entry.getValue() : 0);
            }

            // A count needs only each match's end, so it skips the dots before the literal that a
            // span search walks for the match start: here the search takes two vector scans and
            // the count one.
            Slice runsAroundLiteral = bytes("78".repeat(100) + " 6C69746572616C " + "79".repeat(100));
            DotStarLiteralSpanMatcher.ScanDiagnostics findDiagnostics = new DotStarLiteralSpanMatcher.ScanDiagnostics();
            assertThat(spanMatcher.findSpanForDiagnostics(runsAroundLiteral, 0, runsAroundLiteral.length(), findDiagnostics)).as(label(pattern)).isEqualTo((long) runsAroundLiteral.length());
            DotStarLiteralSpanMatcher.ScanDiagnostics countDiagnostics = new DotStarLiteralSpanMatcher.ScanDiagnostics();
            assertThat(spanMatcher.countForDiagnostics(runsAroundLiteral, countDiagnostics)).as(label(pattern)).isEqualTo(1);
            assertThat(findDiagnostics.vectorScanCount()).as(label(pattern)).isEqualTo(VectorSupport.isAvailable() ? 2 : 0);
            assertThat(countDiagnostics.vectorScanCount()).as(label(pattern)).isEqualTo(VectorSupport.isAvailable() ? 1 : 0);
        }
    }

    @Test
    public void testLineFeedsBeforeLiteral()
    {
        // In every language, the spans and counts are the general engine's, run on the pattern
        // with its dots inside a capture, after one long line, with and without a multibyte
        // character or a malformed byte, many short lines, multibyte characters and malformed
        // bytes before the first line feed and after the last, and carriage returns before the
        // literal, and with the literal at a line's start. If the walk meets a line feed before the
        // literal, it jumps past the last one, scanning a range of MINIMUM_LONG_SEARCH_BYTES or
        // more with the vector scan when the Vector API is available, and a shorter one byte by
        // byte, and never at a line feed before the region.
        String longAscii = "78".repeat(100);
        String malformedPrefix = "FF ".repeat(256) + "6C69746572616C";
        String malformedAfterLines = "L 0A 61 0A L FF 62 FF 6C69746572616C";
        List<Slice> inputs = Stream.of(
                        malformedPrefix,
                        malformedAfterLines,
                        "L L 6C69746572616C 7A",
                        "L C3A9 L 6C69746572616C 7A",
                        "L FF L 6C69746572616C 7A",
                        "0A" + "61 62 0A".repeat(40) + "63 6C69746572616C 64 0A 65 6C69746572616C",
                        "C3A9 FF 61 0A" + "61 62 0A".repeat(40) + "FF C3A9 6C69746572616C",
                        "61 0A L 6C69746572616C 0A L 0A",
                        "61 0A C3A9 E282AC F09F92B0 6C69746572616C 0A C3A9 L E282AC F09F92B0 6C69746572616C",
                        "61 0A 0D 62 0D 6C69746572616C 0D 0A 0D L 0D 6C69746572616C",
                        "61 0A 6C69746572616C 0A 6C69746572616C 6C69746572616C 0A")
                .map(input -> bytes(input.replace("L", longAscii)))
                .toList();
        for (Row row : everyLanguage()) {
            CompiledPattern pattern = row.pattern();
            assertGeneralEngineAgreement(row, inputs);

            // The walk scans the long line before the first line feed and the one after the last,
            // and jumps over the line between, which a walk stepping past each line feed would
            // scan too.
            DotStarLiteralSpanMatcher spanMatcher = pattern.createDotStarLiteralSpanMatcher();
            Slice lines = bytes("L 0A L 0A L 6C69746572616C".replace("L", longAscii));
            DotStarLiteralSpanMatcher.ScanDiagnostics diagnostics = new DotStarLiteralSpanMatcher.ScanDiagnostics();
            assertThat(spanMatcher.findSpanForDiagnostics(lines, 0, lines.length(), diagnostics)).as(label(pattern)).isEqualTo((202L << 32) | lines.length());
            assertThat(diagnostics.vectorScanCount()).as(label(pattern)).isEqualTo(VectorSupport.isAvailable() ? 2 : 0);

            // Only the first line feed starts a backward scan, whether or not malformed bytes
            // follow the last one; a malformed byte moves the match start without one, so a
            // line of 256 of them takes none. Latin-1 reads every byte as a character, so FF is
            // malformed only in UTF-8.
            assertThat(diagnostics.backwardScanCount()).as(label(pattern)).isEqualTo(1);
            assertBackwardScans(spanMatcher, bytes(malformedAfterLines.replace("L", longAscii)), 1, label(pattern));
            if ((pattern.flags() & Regexp.LATIN1) == 0) {
                assertBackwardScans(spanMatcher, bytes(malformedPrefix), 0, label(pattern));
            }
        }
    }

    @Test
    public void testLatin1TreatsHighBytesAsCharacters()
    {
        Re2 pattern = Re2.compile(
                wrappedBuffer(new byte[] {'.', '*', (byte) 0xE9, '.', '*'}),
                Re2.Options.latin1());
        DotStarLiteralSpanMatcher matcher = pattern.createDotStarLiteralSpanMatcher();
        assertThat(matcher).isNotNull();
        assertSpan(matcher, wrappedBuffer(new byte[] {'a', (byte) 0xFF, (byte) 0xE9, 'z'}), 0, 0, 4);
    }

    @Test
    public void testEveryLanguageCountsAndFindsOnSpans()
    {
        // Count and a group-zero matcher's find run the dot-star literal matcher, never a DFA, in
        // RE2, also with longest-match semantics, under which this pattern's spans are the same,
        // Java, and Rust in UTF-8 and bytes mode. So do RE2's replaceFirst, replaceAll, and
        // extract of group zero, which agree with the pattern with the dots inside a capture,
        // which keeps the program. A start-anchored search keeps the program: from the start of
        // `before`, the dots stop at the line feed, past which the matcher would search on for
        // the literal. Dots of exactly `[^\n]`, as in RE2, Rust, and Java with UNIX_LINES, take
        // the matcher class itself, and dots that also stop at other characters, as Java's
        // default dot and Rust's CRLF dot also stop at a carriage return, take MultipleStops.
        Slice source = utf8Slice("before\nxxcoolfunctionnameyy\nafter\ncoolfunctionname!");
        Re2 captured = Re2.compile(utf8Slice("(.*coolfunctionname.*)"));
        assertThat(captured.createDotStarLiteralSpanMatcher()).isNull();

        record ModeRow(CompiledPattern pattern, boolean longestMatch, Class<?> matcherClass) {}

        for (ModeRow row : List.of(
                new ModeRow(Re2.compile(utf8Slice(".*coolfunctionname.*")), false, DotStarLiteralSpanMatcher.class),
                new ModeRow(Re2.compile(utf8Slice(".*coolfunctionname.*"), Re2.Options.defaults().setLongestMatch(true)), true, DotStarLiteralSpanMatcher.class),
                new ModeRow(JavaRegexp.compile(utf8Slice("(?d).*coolfunctionname.*")), false, DotStarLiteralSpanMatcher.class),
                new ModeRow(JavaRegexp.compile(utf8Slice(".*coolfunctionname.*")), false, DotStarLiteralSpanMatcher.MultipleStops.class),
                new ModeRow(RustRegexp.compile(utf8Slice(".*coolfunctionname.*")), false, DotStarLiteralSpanMatcher.class),
                new ModeRow(RustRegexp.compile(utf8Slice("(?R).*coolfunctionname.*")), false, DotStarLiteralSpanMatcher.MultipleStops.class),
                new ModeRow(RustRegexp.compile(utf8Slice("(?-u).*coolfunctionname.*"), RustRegexp.Options.bytes()), false, DotStarLiteralSpanMatcher.class))) {
            CompiledPattern pattern = row.pattern();
            String label = label(pattern) + (row.longestMatch() ? " longest-match" : "");
            DotStarLiteralSpanMatcher spanMatcher = pattern.createDotStarLiteralSpanMatcher();
            assertThat(spanMatcher).as(label).isExactlyInstanceOf(row.matcherClass());
            assertThat(shape(pattern)).as(label).isEqualTo(CompiledPattern.Shape.LITERAL_KERNEL);
            assertThat(pattern.count(source)).as(label).isEqualTo(2);
            assertNoProgramSearch(pattern, label);

            RegexpMatcher matcher = pattern.matcher(source);
            assertThat(matcher.spanSearchForDiagnostics()).as(label).isSameAs(spanMatcher);
            List<String> spans = new ArrayList<>();
            while (matcher.find()) {
                spans.add(matcher.start() + "-" + matcher.end());
            }
            assertThat(spans).as(label).containsExactly("7-27", "34-51");
            assertNoProgramSearch(pattern, label);

            if (pattern instanceof Re2 re2) {
                Slice rewrite = utf8Slice("<\\0>");
                Slice capturedRewrite = utf8Slice("<\\1>");
                assertThat(re2.replaceFirst(source, rewrite)).as(label).isEqualTo(captured.replaceFirst(source, capturedRewrite));
                assertThat(re2.replaceAll(source, rewrite)).as(label).isEqualTo(captured.replaceAll(source, capturedRewrite));
                assertThat(re2.extract(source, rewrite)).as(label).isEqualTo(captured.extract(source, capturedRewrite));
                assertNoProgramSearch(pattern, label);
            }

            assertThat(pattern.lookingAt(source, 0, source.length())).as(label).isFalse();
            assertProgramSearch(pattern, label);
        }

        // Rust's Unicode dot in bytes mode is a class of characters, which also leaves out the
        // surrogates, in a pattern that reads bytes, so it is not a dot-star literal.
        CompiledPattern rustBytes = RustRegexp.compile(utf8Slice(".*coolfunctionname.*"), RustRegexp.Options.bytes());
        assertThat(rustBytes.createDotStarLiteralSpanMatcher()).isNull();
        assertThat(rustBytes.matcher(source).spanSearchForDiagnostics()).isNull();
        assertThat(rustBytes.count(source)).isEqualTo(2);
    }

    @Test
    public void testEligibility()
    {
        assertThat(matcher(".*literal.*")).isNotNull();
        // Dots may stop at eight characters, three of them ASCII.
        assertThat(matcher("[^\\t\\n\\r\\x{85}\\x{A0}\\x{2028}\\x{2029}\\x{3000}]*literal[^\\t\\n\\r\\x{85}\\x{A0}\\x{2028}\\x{2029}\\x{3000}]*"))
                .isExactlyInstanceOf(DotStarLiteralSpanMatcher.MultipleStops.class);
        assertThat(optionalMatcher("(?s:.*literal.*)")).isNull();
        assertThat(optionalMatcher("(.*literal.*)")).isNull();
        assertThat(optionalMatcher("^.*literal.*")).isNull();
        assertThat(optionalMatcher(".*literal.*$")).isNull();
        assertThat(optionalMatcher(".*foo\\nbar.*")).isNull();
        assertThat(optionalMatcher("literal.*")).isNull();
        assertThat(optionalMatcher(".*literal")).isNull();
        assertThat(optionalMatcher("(?i:.*literal.*)")).isNull();
        assertThat(optionalMatcher(".*?literal.*")).isNull();
        assertThat(optionalMatcher(".*literal.*?")).isNull();

        // A dot that matches every character keeps the engine, as do dots with more than eight
        // stops or more ASCII stops than the vector scans take, a literal holding one of the
        // stops, and two gaps that stop at different characters.
        for (CompiledPattern pattern : List.of(
                Re2.compile(utf8Slice("(?s).*literal.*"), Re2.Options.latin1()),
                JavaRegexp.compile(utf8Slice("(?s).*literal.*")),
                JavaRegexp.compile(utf8Slice("(?ds).*literal.*")),
                RustRegexp.compile(utf8Slice("(?s).*literal.*")),
                RustRegexp.compile(utf8Slice("(?s-u).*literal.*"), RustRegexp.Options.bytes()),
                Re2.compile(utf8Slice("[^\\t\\n\\r\\x{85}\\x{A0}\\x{2028}\\x{2029}\\x{3000}\\x{FEFF}]*literal[^\\t\\n\\r\\x{85}\\x{A0}\\x{2028}\\x{2029}\\x{3000}\\x{FEFF}]*")),
                Re2.compile(utf8Slice("[^\\t\\n\\v\\r]*literal[^\\t\\n\\v\\r]*")),
                JavaRegexp.compile(utf8Slice(".*lit\\reral.*")),
                Re2.compile(utf8Slice("[^\\r\\n]*literal[^\\t\\n]*")))) {
            assertThat(pattern.createDotStarLiteralSpanMatcher()).as(label(pattern)).isNull();
        }
    }

    @Test
    public void testRetainedMatcherUsesForwardBudget()
    {
        // The matcher reserves its own size from the forward DFA budget after the contains search,
        // and the required bytes reserve theirs after the matcher.
        Slice pattern = utf8Slice(".*coolfunctionname.*");
        int flags = Re2.Options.defaults().parseFlags();
        ParseResult parsed = RegexpParser.parse(pattern, flags);
        long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
        Prog control = Compiler.compile(parsed.regexp(), false, maxMemory - maxMemory / 3);
        Re2 compiled = Re2.compileParsed(pattern, parsed, flags, maxMemory);
        DotStarLiteralSpanMatcher spanMatcher = compiled.createDotStarLiteralSpanMatcher();
        assertThat(spanMatcher).isNotNull();
        assertThat(compiled.forwardProgramForDiagnostics().dfaMemory())
                .isEqualTo(control.dfaMemory() -
                        compiled.literalSearchForDiagnostics().estimatedRetainedSize() -
                        spanMatcher.estimatedRetainedSize() -
                        compiled.requiredBytesRetainedSizeForDiagnostics());

        // The matcher yields to the forward DFAs: at the least budget at which a DFA initializes,
        // its reservation would leave that DFA too little, so it is left out in every language.
        // Trino had the contains search in release 2.0, which reserved it whenever it fit, and
        // keeps it; the other languages leave it out there too.
        List<LongFunction<CompiledPattern>> compilers = List.of(
                budget -> Re2.compile(pattern, Re2.Options.defaults().setMaxMemory(budget)),
                budget -> Re2.compile(pattern, Re2.Options.latin1().setMaxMemory(budget)),
                budget -> JavaRegexp.compile(utf8Slice("(?d).*coolfunctionname.*"), JavaRegexp.Options.defaults().setMaxMemory(budget)),
                budget -> JavaRegexp.compile(pattern, JavaRegexp.Options.defaults().setMaxMemory(budget)),
                budget -> RustRegexp.compile(pattern, RustRegexp.Options.defaults().setMaxMemory(budget)),
                budget -> RustRegexp.compile(utf8Slice("(?-u).*coolfunctionname.*"), RustRegexp.Options.bytes().setMaxMemory(budget)),
                budget -> TrinoRegexp.compile(pattern, TrinoRegexp.Options.defaults().setMaxMemory(budget)).re2());
        for (LongFunction<CompiledPattern> compiler : compilers) {
            CompiledPattern atDefaultBudget = compiler.apply(maxMemory);
            assertThat(atDefaultBudget.createDotStarLiteralSpanMatcher()).as(label(atDefaultBudget)).isNotNull();
            for (Dfa.DfaInstance.Kind kind : List.of(Dfa.DfaInstance.Kind.FIRST_MATCH, Dfa.DfaInstance.Kind.LONGEST_MATCH)) {
                CompiledPattern atDfaBudget = compileAtSmallestDfaBudget(compiler, kind);
                String label = label(atDfaBudget) + " " + kind;
                assertThat(atDfaBudget.createDotStarLiteralSpanMatcher()).as(label).isNull();
                assertThat(atDfaBudget.literalSearchForDiagnostics() != null).as(label).isEqualTo(atDfaBudget instanceof TrinoCompiledPattern);
                // Without the matcher, the group-zero matcher's find and count run the program and
                // report the general engine's spans.
                for (Slice input : List.of(
                        utf8Slice(""),
                        utf8Slice("coolfunctionname"),
                        utf8Slice("before\nxxcoolfunctionnameyy\nafter\ncoolfunctionname!"),
                        utf8Slice("cool\nfunctionname\ncoolcoolfunctionname\n\nfunctionnamecool"),
                        utf8Slice("é💰coolfunctionnameж終"),
                        wrappedBuffer("x\u00FFcoolfunctionname\n\u00E9coolfunctionname".getBytes(ISO_8859_1)))) {
                    RegexpMatcher matcher = atDfaBudget.matcher(input);
                    assertThat(matcher.spanSearchForDiagnostics()).as(label).isNull();
                    RegexpMatcher generalMatcher = atDfaBudget.genericGroupZeroMatcherForDiagnostics(input);
                    long count = 0;
                    while (generalMatcher.find()) {
                        assertThat(matcher.find()).as(label).isTrue();
                        assertThat(matcher.start()).as(label).isEqualTo(generalMatcher.start());
                        assertThat(matcher.end()).as(label).isEqualTo(generalMatcher.end());
                        count++;
                    }
                    assertThat(matcher.find()).as(label).isFalse();
                    assertThat(atDfaBudget.count(input)).as(label).isEqualTo(count);
                }
            }
        }
    }

    @Test
    public void testGeneralEngineAgreement()
    {
        // A one-byte literal has no contains search, so this checks Slice.indexOf's spans.
        Re2 pattern = Re2.compile(utf8Slice(".*l.*"));
        DotStarLiteralSpanMatcher matcher = pattern.createDotStarLiteralSpanMatcher();
        assertThat(matcher.searchForDiagnostics()).isNull();
        assertGeneralEngineAgreement(new Row(pattern, Re2.compile(utf8Slice("(.*l.*)"))));

        // Spans share the pattern's contains search, so this checks the call-free scan's spans.
        TrinoCompiledPattern trinoPattern = TrinoRegexp.compile(utf8Slice(".*literal.*")).re2();
        DotStarLiteralSpanMatcher trinoMatcher = trinoPattern.createDotStarLiteralSpanMatcher();
        assertThat(trinoMatcher).isExactlyInstanceOf(DotStarLiteralSpanMatcher.class);
        assertThat(trinoMatcher.searchForDiagnostics())
                .isNotNull()
                .isSameAs(trinoPattern.literalSearchForDiagnostics());
        assertGeneralEngineAgreement(new Row(trinoPattern, TrinoRegexp.compile(utf8Slice("(.*literal.*)")).re2()));
    }

    private static void assertGeneralEngineAgreement(Row row)
    {
        assertGeneralEngineAgreement(row, List.of(
                utf8Slice(""),
                utf8Slice("literal"),
                utf8Slice("before literal after"),
                utf8Slice("before\nliteral\nafter"),
                utf8Slice("literal one literal two"),
                utf8Slice("lxxxxxl litexxl lxxxxxl literal lxxxxxl"),
                utf8Slice("l".repeat(300) + "\nliteral " + "l".repeat(300)),
                // ASCII runs of 1 to 24 bytes between two-byte characters, on both sides of the
                // inline loop's 16 bytes; testMalformedUtf8Boundaries pins which runs take the
                // vector scan.
                utf8Slice(DENSE_RUNS + "literal" + DENSE_RUNS + "\nliteral" + DENSE_RUNS),
                utf8Slice("é💰literalж終"),
                wrappedBuffer(new byte[] {'a', (byte) 0xFF, 'l', 'i', 't', 'e', 'r', 'a', 'l', 'z'}),
                wrappedBuffer(new byte[] {'a', 'l', 'i', 't', 'e', 'r', 'a', 'l', (byte) 0xFF, 'z'})));
    }

    /// Requires the row pattern to have a dot-star literal matcher and its captured pattern none,
    /// and, in each of `inputs`, the matcher's span from every start, and the pattern's and the
    /// matcher's counts, to be the general engine's: those of the generic matcher of the captured
    /// pattern, which, without the matcher, runs the program.
    private static void assertGeneralEngineAgreement(Row row, List<Slice> inputs)
    {
        CompiledPattern pattern = row.pattern();
        DotStarLiteralSpanMatcher matcher = pattern.createDotStarLiteralSpanMatcher();
        assertThat(matcher).as(label(pattern)).isNotNull();
        assertThat(row.captured().createDotStarLiteralSpanMatcher()).as(label(row.captured())).isNull();
        for (Slice input : inputs) {
            String label = label(pattern) + " on " + HexFormat.of().formatHex(input.getBytes());
            for (int start = 0; start <= input.length(); start++) {
                RegexpMatcher generalMatcher = row.captured().genericGroupZeroMatcherForDiagnostics(input);
                long span = matcher.findSpan(input, start);
                if (generalMatcher.find(start)) {
                    assertThat(span).as("%s start %s", label, start).isEqualTo(((long) generalMatcher.start() << 32) | (generalMatcher.end() & 0xFFFF_FFFFL));
                }
                else {
                    assertThat(span).as("%s start %s", label, start).isEqualTo(Dfa.SEARCH_NO_MATCH);
                }
            }

            RegexpMatcher generalMatcher = row.captured().genericGroupZeroMatcherForDiagnostics(input);
            long count = 0;
            while (generalMatcher.find()) {
                count++;
            }
            assertThat(pattern.count(input)).as(label).isEqualTo(count);
            assertThat(matcher.count(input)).as(label).isEqualTo(count);
        }
    }

    /// Returns `.*literal.*` in every language, encoding, and set of dot stops that takes the
    /// matcher, each with the same pattern with its dots inside a capture.
    private static List<Row> everyLanguage()
    {
        return List.of(
                new Row(Re2.compile(utf8Slice(".*literal.*")), Re2.compile(utf8Slice("(.*literal.*)"))),
                new Row(Re2.compile(utf8Slice(".*literal.*"), Re2.Options.latin1()), Re2.compile(utf8Slice("(.*literal.*)"), Re2.Options.latin1())),
                new Row(JavaRegexp.compile(utf8Slice("(?d).*literal.*")), JavaRegexp.compile(utf8Slice("(?d)(.*literal.*)"))),
                new Row(JavaRegexp.compile(utf8Slice(".*literal.*")), JavaRegexp.compile(utf8Slice("(.*literal.*)"))),
                new Row(RustRegexp.compile(utf8Slice(".*literal.*")), RustRegexp.compile(utf8Slice("(.*literal.*)"))),
                new Row(RustRegexp.compile(utf8Slice("(?R).*literal.*")), RustRegexp.compile(utf8Slice("(?R)(.*literal.*)"))),
                new Row(RustRegexp.compile(utf8Slice("(?-u).*literal.*"), RustRegexp.Options.bytes()), RustRegexp.compile(utf8Slice("(?-u)(.*literal.*)"), RustRegexp.Options.bytes())),
                new Row(RustRegexp.compile(utf8Slice("(?R-u).*literal.*"), RustRegexp.Options.bytes()), RustRegexp.compile(utf8Slice("(?R-u)(.*literal.*)"), RustRegexp.Options.bytes())),
                new Row(TrinoRegexp.compile(utf8Slice(".*literal.*")).re2(), TrinoRegexp.compile(utf8Slice("(.*literal.*)")).re2()));
    }

    /// A pattern that takes the dot-star literal matcher, and the same pattern with its dots inside
    /// a capture, which takes none and keeps the program.
    private record Row(CompiledPattern pattern, CompiledPattern captured) {}

    private static DotStarLiteralSpanMatcher matcher(String pattern)
    {
        return Re2.compile(utf8Slice(pattern)).createDotStarLiteralSpanMatcher();
    }

    private static DotStarLiteralSpanMatcher optionalMatcher(String pattern)
    {
        return Re2.compile(utf8Slice(pattern)).createDotStarLiteralSpanMatcher();
    }

    private static Slice bytes(String hex)
    {
        return wrappedBuffer(HexFormat.of().parseHex(hex.replace(" ", "")));
    }

    private static void assertSpan(DotStarLiteralSpanMatcher matcher, Slice input, int searchStart, int expectedStart, int expectedEnd)
    {
        long span = matcher.findSpan(input, searchStart);
        assertThat((int) (span >>> 32)).isEqualTo(expectedStart);
        assertThat((int) span).isEqualTo(expectedEnd);
    }

    // The pattern's class, text, and encoding, which tell RE2's UTF-8 and Latin-1 cases apart.
    private static String label(CompiledPattern pattern)
    {
        return pattern.getClass().getSimpleName() + " " + pattern.pattern().toStringUtf8() + ((pattern.flags() & Regexp.LATIN1) != 0 ? " Latin-1" : " UTF-8");
    }

    private static void assertBackwardScans(DotStarLiteralSpanMatcher matcher, Slice input, long expected, String label)
    {
        DotStarLiteralSpanMatcher.ScanDiagnostics diagnostics = new DotStarLiteralSpanMatcher.ScanDiagnostics();
        assertThat(matcher.findSpanForDiagnostics(input, 0, input.length(), diagnostics)).as(label).isEqualTo(matcher.findSpan(input, 0, input.length()));
        assertThat(diagnostics.backwardScanCount()).as(label).isEqualTo(expected);
    }
}
