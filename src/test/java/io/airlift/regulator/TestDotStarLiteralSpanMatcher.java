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

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDotStarLiteralSpanMatcher
{
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

        // In every language, the spans and counts are the general engine's on each malformed
        // sequence before, after, and on both sides of the literal, and at the input's ends.
        List<CompiledPattern> patterns = List.of(
                Re2.compile(utf8Slice(".*literal.*")),
                Re2.compile(utf8Slice(".*literal.*"), Re2.Options.latin1()),
                JavaRegexp.compile(utf8Slice("(?d).*literal.*")),
                RustRegexp.compile(utf8Slice(".*literal.*")),
                RustRegexp.compile(utf8Slice("(?-u).*literal.*"), RustRegexp.Options.bytes()),
                TrinoRegexp.compile(utf8Slice(".*literal.*")).re2());
        List<String> malformedSequences = List.of(
                // Bare continuation bytes.
                "80",
                "BF",
                // Overlong two-byte forms, which no lead byte C0 or C1 starts.
                "C080",
                "C1BF",
                // Sequences cut short by an ASCII byte or by the end.
                "C3",
                "E282",
                "F09F92",
                // Overlong three- and four-byte forms, an encoded surrogate, and values above
                // U+10FFFF, which the UTF-8 dot accepts.
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
        for (CompiledPattern pattern : patterns) {
            DotStarLiteralSpanMatcher spanMatcher = pattern.createDotStarLiteralSpanMatcher();
            assertThat(spanMatcher).as(pattern.pattern().toStringUtf8()).isNotNull();
            for (String malformed : malformedSequences) {
                for (String input : List.of(
                        "61 %s 6C69746572616C 7A",
                        "61 6C69746572616C %s 7A",
                        "%s 6C69746572616C %s",
                        "6C69746572616C %s",
                        "%s 6C69746572616C",
                        "61 %s 62 6C69746572616C 63 %s 6C69746572616C %s 64")) {
                    assertGeneralEngineAgreement(pattern, spanMatcher, bytes(input.replace("%s", malformed)));
                }
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
        // RE2, Java with UNIX_LINES, whose dot is `[^\n]`, and Rust in UTF-8 and bytes mode.
        Slice source = utf8Slice("before\nxxcoolfunctionnameyy\nafter\ncoolfunctionname!");
        for (CompiledPattern pattern : List.of(
                Re2.compile(utf8Slice(".*coolfunctionname.*")),
                JavaRegexp.compile(utf8Slice("(?d).*coolfunctionname.*")),
                RustRegexp.compile(utf8Slice(".*coolfunctionname.*")),
                RustRegexp.compile(utf8Slice("(?-u).*coolfunctionname.*"), RustRegexp.Options.bytes()))) {
            String label = pattern.getClass().getSimpleName() + " " + pattern.pattern().toStringUtf8();
            DotStarLiteralSpanMatcher spanMatcher = pattern.createDotStarLiteralSpanMatcher();
            assertThat(spanMatcher).as(label).isNotNull();
            assertThat(TestingPatternShapes.shape(pattern)).as(label).isEqualTo(CompiledPattern.Shape.LITERAL_KERNEL);
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
        }

        // Java's default dot also stops at `\r`, U+0085, U+2028, and U+2029, and Rust's Unicode
        // dot in bytes mode at the surrogates, so neither pattern is a dot-star literal.
        for (CompiledPattern pattern : List.of(
                JavaRegexp.compile(utf8Slice(".*coolfunctionname.*")),
                RustRegexp.compile(utf8Slice(".*coolfunctionname.*"), RustRegexp.Options.bytes()))) {
            String label = pattern.getClass().getSimpleName() + " " + pattern.pattern().toStringUtf8();
            assertThat(pattern.createDotStarLiteralSpanMatcher()).as(label).isNull();
            assertThat(pattern.matcher(source).spanSearchForDiagnostics()).as(label).isNull();
            assertThat(pattern.count(source)).as(label).isEqualTo(2);
        }
    }

    private static void assertNoProgramSearch(CompiledPattern pattern, String label)
    {
        for (Dfa.DfaInstance.Kind kind : Dfa.DfaInstance.Kind.values()) {
            assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(kind)).as("%s %s DFA", label, kind).isNull();
        }
        assertThat(pattern.isReverseProgramComputed()).as(label).isFalse();
    }

    @Test
    public void testEligibility()
    {
        assertThat(matcher(".*literal.*")).isNotNull();
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
    }

    @Test
    public void testGeneralEngineAgreement()
    {
        // A one-byte literal has no contains search, so this checks Slice.indexOf's spans.
        Re2 pattern = Re2.compile(utf8Slice(".*l.*"));
        DotStarLiteralSpanMatcher matcher = pattern.createDotStarLiteralSpanMatcher();
        assertThat(matcher.searchForDiagnostics()).isNull();
        assertGeneralEngineAgreement(pattern, matcher);

        // Spans share the pattern's contains search, so this checks the call-free scan's spans.
        TrinoCompiledPattern trinoPattern = TrinoRegexp.compile(utf8Slice(".*literal.*")).re2();
        DotStarLiteralSpanMatcher trinoMatcher = trinoPattern.createDotStarLiteralSpanMatcher();
        assertThat(trinoMatcher.searchForDiagnostics())
                .isNotNull()
                .isSameAs(trinoPattern.literalSearchForDiagnostics());
        assertGeneralEngineAgreement(trinoPattern, trinoMatcher);
    }

    private static void assertGeneralEngineAgreement(CompiledPattern pattern, DotStarLiteralSpanMatcher matcher)
    {
        for (Slice input : List.of(
                utf8Slice(""),
                utf8Slice("literal"),
                utf8Slice("before literal after"),
                utf8Slice("before\nliteral\nafter"),
                utf8Slice("literal one literal two"),
                utf8Slice("lxxxxxl litexxl lxxxxxl literal lxxxxxl"),
                utf8Slice("l".repeat(300) + "\nliteral " + "l".repeat(300)),
                utf8Slice("é💰literalж終"),
                wrappedBuffer(new byte[] {'a', (byte) 0xFF, 'l', 'i', 't', 'e', 'r', 'a', 'l', 'z'}),
                wrappedBuffer(new byte[] {'a', 'l', 'i', 't', 'e', 'r', 'a', 'l', (byte) 0xFF, 'z'}))) {
            assertGeneralEngineAgreement(pattern, matcher, input);
        }
    }

    /// Requires `matcher`'s span from every start in `input`, and `pattern`'s count, to be the
    /// general engine's.
    private static void assertGeneralEngineAgreement(CompiledPattern pattern, DotStarLiteralSpanMatcher matcher, Slice input)
    {
        String label = pattern.getClass().getSimpleName() + " " + pattern.pattern().toStringUtf8() + " on " + HexFormat.of().formatHex(input.getBytes());
        for (int start = 0; start <= input.length(); start++) {
            RegexpMatcher generalMatcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
            long span = matcher.findSpan(input, start);
            if (generalMatcher.find(start)) {
                assertThat(span)
                        .as("%s start %s", label, start)
                        .isEqualTo(((long) generalMatcher.start() << 32) | (generalMatcher.end() & 0xFFFF_FFFFL));
            }
            else {
                assertThat(span).as("%s start %s", label, start).isEqualTo(Dfa.SEARCH_NO_MATCH);
            }
        }

        RegexpMatcher generalMatcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
        long count = 0;
        while (generalMatcher.find()) {
            count++;
        }
        assertThat(pattern.count(input)).as(label).isEqualTo(count);
    }

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
}
