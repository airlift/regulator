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

import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestInnerLiteralSearch
{
    private static final RustRegexp.Options RUST_BYTES_OPTIONS = RustRegexp.Options.defaults().setBytes(true);
    private static final String FILLER = "-".repeat(InnerLiteralSearch.MINIMUM_SKIPPED_BYTES);

    private static final List<String> PATTERNS = List.of(
            "[\\w\\.+-]+@[\\w\\.-]+\\.[\\w\\.-]+",
            "[a-z]shing",
            "\\b\\w+\\s+Holmes\\s+\\w+\\b",
            "\\w+\\s+Holmes\\s+\\w+",
            "\\w+\\s+Holmes",
            "\\s[a-zA-Z]{0,12}ing\\s",
            "(\\w+)@(\\w+)\\.(\\w+)",
            "\\w+://[^\\s/]+",
            "(?m)^\\w+:\\s",
            "\\w+\\b@\\w",
            "[a-q][^u-z]{3}xy",
            "[a-z]+ing",
            "\\w+\\w+Holmes",
            "[xz]+zx",
            "(?:az|z)xx",
            "(?:a@.*b|c)@",
            "(?:a[^@]*@[^@]*d|c)@");

    @Test
    public void testSelectsLiteralWithProof()
    {
        // The prefix cannot hold the literal.
        assertThat(literal("[\\w\\.+-]+@[\\w\\.-]+\\.[\\w\\.-]+")).isEqualTo("@");
        assertThat(literal("\\w+://[^\\s/]+")).isEqualTo("://");
        // The prefix has a fixed length.
        assertThat(literal("[a-z]shing")).isEqualTo("shing");
        assertThat(literal("[a-q][^u-z]{3}xy")).isEqualTo("xy");
        assertThat(literal("[a-z]" + "xy".repeat(32))).isEqualTo("xy".repeat(32));
        // A required class at an end of the prefix separates the literal from an earlier match.
        assertThat(literal("\\w+\\s+Holmes")).isEqualTo("Holmes");
        assertThat(literal("\\b\\w+\\s+Holmes\\s+\\w+\\b")).isEqualTo("Holmes");
        assertThat(literal("\\s[a-zA-Z]{0,12}ing\\s")).isEqualTo("ing");
        assertThat(literal("\\w+(?:ab)?\\s+ing")).isEqualTo("ing");

        for (String pattern : List.of(
                // No proof rules out an earlier match spanning a later occurrence: in ac@d@, the
                // match ac@d@ spans the occurrence that c@ would confirm first.
                "(?:a[^@]*@[^@]*d|c)@",
                "(?:a@.*b|c)@",
                "[xz]+zx",
                "[a-z]+ing",
                "\\w+\\w+Holmes",
                // The bytes C3 A9 encode é, so neither byte class is disjoint from [a-zé].
                "(?-u:[\\xC3\\xC4])+[a-z\u00E9]*(?-u:[\\xA9\\xAA])ab",
                // Anchored, prefix-accelerated, folded, common single-byte, and overlong literals.
                "^\\w+@\\w+",
                "Holmes\\w+@",
                "\\w+(?i:holmes)",
                "\\w+ \\w+",
                "[a-z]" + "xy".repeat(32) + "z")) {
            assertThat(innerLiteralSearch(pattern)).as(pattern).isNull();
        }
    }

    @Test
    public void testRetainedSearchUsesReverseBudget()
    {
        Slice pattern = utf8Slice("\\w+\\s+Holmes");
        int flags = JavaRegexp.Options.defaults().parseFlags();
        ParseResult parsed = JavaRegexpParser.parse(pattern, flags);
        long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
        Prog control = Compiler.compile(parsed.regexp(), false, maxMemory - maxMemory / 3);
        Re2 compiled = Re2.compileParsed(pattern, parsed, flags, maxMemory);
        InnerLiteralSearch search = compiled.innerLiteralSearchForDiagnostics();
        assertThat(search).isNotNull();
        assertThat(compiled.forwardProgramForDiagnostics().dfaMemory()).isEqualTo(control.dfaMemory());

        // The full reverse program takes what the inner literal search leaves of the reverse third.
        assertThat(compiled.reverseProgramSize()).isPositive();
        Prog reverse = compiled.reverseProgramIfComputedForDiagnostics();
        assertThat(search.estimatedRetainedSize() + Compiler.estimatedProgramMemory(reverse) + reverse.dfaMemory()).isLessThanOrEqualTo(maxMemory / 3);

        // At small budgets the search leaves the full reverse DFA enough to initialize, as it does
        // without the search, and a search that is kept can run its reverse prefix DFA.
        for (SmallBudget budget : List.of(
                new SmallBudget("[a-z]+@\\p{L}+\\p{N}", 1L << 20, "ab@cd1", true),
                new SmallBudget("[a-z]+@\\p{L}+\\p{N}", 896L << 10, "ab@cd1", true),
                new SmallBudget("[a-z]+-[0-9]+", 24L << 10, "ab-12", false))) {
            String description = budget.pattern() + " " + budget.maxMemory();
            Re2 small = Re2.compile(utf8Slice(budget.pattern()), Re2.Options.defaults().setMaxMemory(budget.maxMemory()));
            assertThat(small.reverseProgramSize()).as(description).isPositive();
            assertThat(small.reverseProgramIfComputedForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH)).as(description).isNotNull();
            InnerLiteralSearch smallSearch = small.innerLiteralSearchForDiagnostics();
            assertThat(smallSearch != null).as(description).isEqualTo(budget.keepsSearch());
            if (budget.keepsSearch()) {
                Slice text = utf8Slice(".".repeat(100) + budget.match());
                assertThat(smallSearch.search(text, 0, text.length(), 0, text.length())).as(description).isEqualTo((100L << 32) | text.length());
            }
        }
    }

    @Test
    public void testAgreesWithOrdinaryEngine()
    {
        List<Slice> texts = texts();
        for (String pattern : PATTERNS) {
            // An alternative that never matches leaves the top-level concatenation, and with it
            // the inner literal, out of the expected engine.
            String ordinary = "(?:" + pattern + ")|\\b\\B";
            assertThat(innerLiteralSearch(ordinary)).as(ordinary).isNull();
            for (Language language : Language.values()) {
                Engine actual = language.compile(pattern);
                Engine expected = language.compile(ordinary);
                for (Slice text : texts) {
                    String description = "%s %s %s".formatted(language, pattern, text.toStringUtf8());
                    assertThat(actual.spans(text)).as(description).isEqualTo(expected.spans(text));
                    assertThat(actual.count(text)).as(description).isEqualTo(expected.count(text));
                    assertThat(actual.find(text)).as(description).isEqualTo(expected.find(text));
                }
            }
        }
    }

    @Test
    public void testAgreesWithOrdinaryEngineInWindows()
    {
        // A ranged find keeps the whole Slice as assertion context, so its closing \b sees the
        // byte past the window, while a matcher region is its own context. The Slice starts inside
        // its backing array, and the literal lies past the near-start window.
        String regex = "\\b\\w+\\s+Holmes\\s+\\w+\\b";
        Re2 pattern = Re2.compile(utf8Slice(regex));
        InnerLiteralSearch search = pattern.innerLiteralSearchForDiagnostics();
        Re2 ordinary = Re2.compile(utf8Slice("(?:" + regex + ")|\\b\\B"));
        String window = FILLER + "Sherlock Holmes sa";
        int start = 2;
        int end = start + window.length();
        long span = ((long) (start + FILLER.length()) << 32) | end;
        for (String after : List.of("id", " x")) {
            byte[] backing = ("abcx " + window + after).getBytes(UTF_8);
            Slice text = Slices.wrappedBuffer(backing, 3, backing.length - 3);
            boolean wordAfter = after.equals("id");
            assertThat(search.search(text, 0, text.length(), start, end)).as(after).isEqualTo(wordAfter ? Dfa.SEARCH_NO_MATCH : span);
            assertThat(search.search(text, start, end, start, end)).as(after).isEqualTo(span);

            int[] actual = new int[2];
            int[] expected = new int[2];
            assertThat(pattern.findInto(text, start, end, actual)).as(after).isEqualTo(!wordAfter);
            assertThat(ordinary.findInto(text, start, end, expected)).as(after).isEqualTo(!wordAfter);
            assertThat(actual).as(after).isEqualTo(expected);

            RegexpMatcher matcher = pattern.matcher(text).reset(text, start, end);
            RegexpMatcher expectedMatcher = ordinary.matcher(text).reset(text, start, end);
            assertThat(matcher.find()).as(after).isTrue();
            assertThat(expectedMatcher.find()).as(after).isTrue();
            assertThat(List.of(matcher.start(), matcher.end())).as(after).isEqualTo(List.of(expectedMatcher.start(), expectedMatcher.end()));
        }
    }

    @Test
    public void testFallsBackWhenLiteralIsNearStart()
    {
        String filler = ".".repeat(200);
        // A search skips at most the bytes before its first literal, as in a uuid whose hyphen
        // sits 8 bytes in, so a first hit closer than the minimum falls back to the ordinary
        // engine. Once a first hit fails to confirm, a later hit takes the search wherever it lies.
        assertNearStart(
                "[a-z]+-[0-9]+",
                List.of("abcdefgh-1234" + filler, ".".repeat(61) + "ab-12" + filler, ".".repeat(62) + "--ab-12" + filler),
                List.of(".".repeat(62) + "ab-12" + filler, filler + "ab-12", filler + "ab-....cd-12" + filler));
        // A longer literal is near at 63 bytes in, and not at 64 or 68, where the near check's read
        // ends inside it.
        assertNearStart(
                "\\w+\\s+Holmes",
                List.of(".".repeat(61) + "a Holmes" + filler),
                List.of(".".repeat(62) + "a Holmes" + filler, ".".repeat(66) + "a Holmes" + filler));
    }

    private static void assertNearStart(String regex, List<String> nearTexts, List<String> farTexts)
    {
        Re2 pattern = Re2.compile(utf8Slice(regex));
        InnerLiteralSearch search = pattern.innerLiteralSearchForDiagnostics();
        assertThat(search).as(regex).isNotNull();
        Engine expected = regexpEngine(Re2.compile(utf8Slice("(?:" + regex + ")|\\b\\B")));
        for (String text : nearTexts) {
            assertSearchRoute(pattern, search, expected, utf8Slice(text), true);
        }
        for (String text : farTexts) {
            assertSearchRoute(pattern, search, expected, utf8Slice(text), false);
        }
    }

    private static void assertSearchRoute(Re2 pattern, InnerLiteralSearch search, Engine expected, Slice text, boolean nearStart)
    {
        String description = text.toStringUtf8();
        long span = search.search(text, 0, text.length(), 0, text.length());
        assertThat(span == InnerLiteralSearch.FALLBACK).as(description).isEqualTo(nearStart);
        assertThat(pattern.searchesInnerLiteral(text, 0, text.length())).as(description).isEqualTo(!nearStart);
        // A matcher keeps its candidate cursor, the ordinary engine's route, only for a near hit.
        RegexpMatcher matcher = pattern.groupZeroMatcher(text, null);
        assertThat(matcher.find()).as(description).isTrue();
        assertThat(matcher.candidateStartRouteCountForDiagnostics()).as(description).isEqualTo(nearStart ? 1 : 0);
        assertThat(regexpEngine(pattern).spans(text)).as(description).isEqualTo(expected.spans(text));
        assertThat(pattern.count(text)).as(description).isEqualTo(expected.count(text));
    }

    private static String literal(String pattern)
    {
        InnerLiteralSearch search = innerLiteralSearch(pattern);
        assertThat(search).as(pattern).isNotNull();
        return new String(search.literal(), UTF_8);
    }

    private static List<Slice> texts()
    {
        List<Slice> texts = new ArrayList<>();
        // Overlapping and adjacent candidates, and failing confirmations that would rescan.
        for (String text : List.of(
                "Holmes Holmes Holmes Holmes Holmes Holmes Holmes Holmes Holmes Holmes Holmes",
                "singing bringing thing, singingX ing ing ingest sing ing\n".repeat(4),
                "a@b@c@d@e@f@g@h@i@j@k@l@m@n@o@p@q@r@s@t@u@v@w@x@y@z@a.b@c.d@e.f".repeat(2),
                "xshingshingshing ashing shing Ashing shingle punishing".repeat(2),
                "azxx zxx azx azxxx ".repeat(5),
                "abcdxy aaaaxy uuuuxy axyzxy ".repeat(4),
                "key: value\nother:\tx\n: none\nk:v\n".repeat(4),
                "http://example.com/a ftp://x mailto:y https:// x://y".repeat(2),
                "a@c@b@ a@c@b@ c@ a@b@ a@@b@".repeat(4),
                "ac@d@ ac@d@ c@ ac@@d@",
                // A run of near literals the ordinary count takes over: the next literal lies 63
                // bytes past a match end, which stays near, then 64, which hands back to the inner
                // literal, and the last match ends with the text.
                "ashing" + ".".repeat(62) + "bshing" + ".".repeat(63) + "cshing" + ".".repeat(63) + "dshingeshing",
                "a Holmes" + ".".repeat(61) + "b Holmes" + ".".repeat(62) + "c Holmes")) {
            texts.add(utf8Slice(text));
            // A search whose first hit is near its start falls back, so filler keeps the search
            // and its later candidates in play.
            texts.add(utf8Slice(FILLER + text));
        }
        String[] tokens = {"Holmes", " ", "  ", "\t", "\n", "a", "Z", "_", "9", "@", ".", "-", "+", ":", "//", "x", "y", "b", "c", "ing", "sh", "s", "é", "\u2028", "\uFFFF"};
        Random random = new Random(20261031);
        for (int textIndex = 0; textIndex < 400; textIndex++) {
            DynamicSliceOutput output = new DynamicSliceOutput(256);
            if (textIndex % 2 == 1) {
                output.writeBytes(FILLER.getBytes(UTF_8));
            }
            int length = output.size() + random.nextInt(240);
            while (output.size() < length) {
                if (random.nextInt(40) == 0) {
                    // Malformed UTF-8: a bare continuation byte or a truncated lead byte.
                    output.writeByte(random.nextBoolean() ? 0x80 : 0xE2);
                }
                else {
                    output.writeBytes(tokens[random.nextInt(tokens.length)].getBytes(UTF_8));
                }
            }
            texts.add(output.slice());
        }
        return texts;
    }

    private static InnerLiteralSearch innerLiteralSearch(String pattern)
    {
        return RustRegexp.compile(utf8Slice(pattern), RUST_BYTES_OPTIONS).re2().innerLiteralSearchForDiagnostics();
    }

    private record SmallBudget(String pattern, long maxMemory, String match, boolean keepsSearch) {}

    private enum Language
    {
        RE2, JAVA, TRINO, RUST, RUST_BYTES, RUST_BYTES_ASCII;

        Engine compile(String pattern)
        {
            Slice slice = utf8Slice(pattern);
            return switch (this) {
                case RE2 -> regexpEngine(Re2.compile(slice));
                case JAVA -> regexpEngine(JavaRegexp.compile(slice).re2());
                case TRINO -> trinoEngine(TrinoRegexp.compile(slice));
                case RUST -> regexpEngine(RustRegexp.compile(slice).re2());
                case RUST_BYTES -> regexpEngine(RustRegexp.compile(slice, RUST_BYTES_OPTIONS).re2());
                case RUST_BYTES_ASCII -> regexpEngine(RustRegexp.compile(slice, RustRegexp.Options.defaults().setBytes(true).setUnicode(false)).re2());
            };
        }
    }

    private interface Engine
    {
        List<String> spans(Slice text);

        long count(Slice text);

        boolean find(Slice text);
    }

    private static Engine regexpEngine(Re2 pattern)
    {
        return new Engine()
        {
            @Override
            public List<String> spans(Slice text)
            {
                List<String> spans = new ArrayList<>();
                RegexpMatcher boundaries = pattern.matcher(text, 0);
                while (boundaries.find()) {
                    spans.add(boundaries.start() + "-" + boundaries.end());
                }
                RegexpMatcher groups = pattern.matcher(text);
                while (groups.find()) {
                    for (int group = 0; group <= groups.groupCount(); group++) {
                        spans.add(group + ":" + groups.start(group) + "-" + groups.end(group));
                    }
                }
                return spans;
            }

            @Override
            public long count(Slice text)
            {
                return pattern.count(text);
            }

            @Override
            public boolean find(Slice text)
            {
                return pattern.find(text);
            }
        };
    }

    private static Engine trinoEngine(TrinoRegexp pattern)
    {
        return new Engine()
        {
            @Override
            public List<String> spans(Slice text)
            {
                List<String> spans = new ArrayList<>();
                TrinoRegexpMatcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    for (int group = 0; group <= matcher.groupCount(); group++) {
                        spans.add(group + ":" + matcher.start(group) + "-" + matcher.end(group));
                    }
                }
                return spans;
            }

            @Override
            public long count(Slice text)
            {
                return pattern.count(text);
            }

            @Override
            public boolean find(Slice text)
            {
                return pattern.contains(text);
            }
        };
    }
}
