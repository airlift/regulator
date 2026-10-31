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
        // its backing array.
        String regex = "\\b\\w+\\s+Holmes\\s+\\w+\\b";
        Re2 pattern = Re2.compile(utf8Slice(regex));
        InnerLiteralSearch search = pattern.innerLiteralSearchForDiagnostics();
        Re2 ordinary = Re2.compile(utf8Slice("(?:" + regex + ")|\\b\\B"));
        String filler = "-".repeat(InnerLiteralSearch.MINIMUM_SEARCH_BYTES);
        String window = filler + "Sherlock Holmes sa";
        int start = 2;
        int end = start + window.length();
        long span = ((long) (start + filler.length()) << 32) | end;
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
                "-".repeat(64) + "ac@d@ ac@d@ c@ ac@@d@")) {
            texts.add(utf8Slice(text));
        }
        String[] tokens = {"Holmes", " ", "  ", "\t", "\n", "a", "Z", "_", "9", "@", ".", "-", "+", ":", "//", "x", "y", "b", "c", "ing", "sh", "s", "é", "\u2028", "\uFFFF"};
        Random random = new Random(20261031);
        for (int textIndex = 0; textIndex < 400; textIndex++) {
            DynamicSliceOutput output = new DynamicSliceOutput(256);
            int length = random.nextInt(240);
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
