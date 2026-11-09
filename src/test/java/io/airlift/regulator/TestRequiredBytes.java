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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestRequiredBytes
{
    // Ruff's noqa comment pattern, and a variant without its leading whitespace group or outer
    // capture.
    private static final String NOQA_REAL = "(\\s*)((?:# [Nn][Oo][Qq][Aa])(?::\\s?(([A-Z]+[0-9]+(?:[,\\s]+)?)+))?)";
    private static final String NOQA_TWEAKED = "(?:# [Nn][Oo][Qq][Aa])(?::\\s?(([A-Z]+[0-9]+(?:[,\\s]+)?)+))?";
    private static final String NOQA_LITERAL = "# noqa";
    private static final String CODING = "^[ \\t\\f]*#.*?coding[:=][ \\t]*utf-?8";
    private static final String URI_OR_EMAIL = "([a-zA-Z][a-zA-Z0-9]*)://([^ /]+)(/[^ ]*)?|([^ @]+)@([^ @]+)";
    private static final String EMAIL = "([^ @]+)@([^ @]+)";
    private static final String NOQA_ASSIGNMENT = "[a-z]+=(?i:noqa)";
    // The quick form of the aws-keys pattern, whose prefix scan looks for its one-byte prefix A.
    private static final String AWS_KEY_QUICK = "(?:ASIA|AKIA|AROA|AIDA)[A-Z0-7]{16}";
    // rebar's leipzig/bounded-strings-ending-z pattern: no element is a literal of two bytes, but
    // every match ends with `,z`.
    private static final String BOUNDED_STRINGS = "(.*?,){13}z";
    private static final String BOUNDED_STRINGS_MATCH = ",".repeat(13) + "z";
    // A line with one comma and no `,z`. Seven lines pass the short-window limit and stay under
    // the 1 KiB vector scan minimum; thirty pass it.
    private static final String FILLER_LINE = "the quick brown fox, jumps over the lazy dog\n";
    private static final String SHORT_FILLER = FILLER_LINE.repeat(7);
    private static final String LONG_FILLER = FILLER_LINE.repeat(30);
    // The literal `,z` between long fillers, which no match ends with.
    private static final String LONG_FILLER_WITH_LITERAL = LONG_FILLER + ",z\n" + LONG_FILLER;
    // The most budgets past a starting budget to try for one that keeps the required bytes.
    private static final long BUDGET_SEARCH_LIMIT = 4096;
    // Patterns whose matches end with an exact suffix, which bounds a long search's end, each with
    // one of its matches and that suffix. In order, the suffix is the required literal, is longer
    // than the literal, lies apart from the literal, ends at an assertion, ends at a repetition, and
    // follows an assertion that reads outside a region.
    private static final List<SuffixCase> SUFFIX_CASES = List.of(
            new SuffixCase(BOUNDED_STRINGS, BOUNDED_STRINGS_MATCH, ",z"),
            new SuffixCase("(.*?;){3}abcdefgh", "a;b;c;abcdefgh", ";abcdefgh"),
            new SuffixCase("(.*?;){3}abcdefgh.*zz", "a;b;c;abcdefghzz", "zz"),
            new SuffixCase("(.*?,){3}\\bz,", "a,b,c,z,", "z,"),
            new SuffixCase("(.*?,){3}(?:,z)+", "a,b,c,,z,z", ",z"),
            new SuffixCase("\\b(.*?,){3}z,", "ab,c,d,z,", ",z,"),
            // An inner literal search behind a class run, whose count the bound ends too.
            new SuffixCase("([a-z]+)ing", "testing", "ing"));
    // Append this always-matching assertion to disable suffix extraction without changing
    // matches.
    private static final String WITHOUT_SUFFIX = "(?:\\b|\\B)";

    private static final Map<String, Boolean> NOQA_LINES = Map.of(
            "x = 1", false,
            "x = 1  # comment", false,
            "x = 1  # noq", false,
            "# no", false,
            "x = 1  # NoQa", true,
            "x = 1  # noqa: E501, W291", true,
            "  # NOQA:E1 x", true,
            "# comment # noqa", true);

    @Test
    public void testSelectsRequiredBytes()
    {
        assertThat(requiredBytes(NOQA_REAL)).isEqualTo(NOQA_LITERAL);
        assertThat(requiredBytes(NOQA_TWEAKED)).isEqualTo(NOQA_LITERAL);
        assertThat(requiredBytes("(?P<spaces>\\s*)(?P<noqa>(?i:# noqa)(?::\\s?(?P<codes>([A-Z]+[0-9]+(?:[,\\s]+)?)+))?)")).isEqualTo(NOQA_LITERAL);
        assertThat(requiredBytes(CODING)).isEqualTo("coding");
        assertThat(requiredBytes("(?:ab)?cd")).isEqualTo("cd");
        // A pattern that is a single literal keeps it, exact or case-insensitive after two exact
        // bytes.
        assertThat(requiredBytes("abcabd")).isEqualTo("abcabd");
        assertThat(requiredBytes("(?i)# noqa")).isEqualTo(NOQA_LITERAL);
        // A one-byte literal, or a case-insensitive literal without a two-byte exact head, is not used.
        assertThat(requiredBytes("\\s+(?i:noqa)")).isNull();
        // Without an element literal, the literal joins the exact suffix of an element, the exact
        // elements after it, and the exact prefix of the next, and the rarest such run is kept.
        // The copies of a counted capture flatten into `,` and `z` elements; a counted group
        // that does not capture stays whole, and an assertion consumes nothing.
        assertThat(requiredBytes(BOUNDED_STRINGS)).isEqualTo(",z");
        assertThat(requiredBytes("(?:\\d+,){2}y")).isEqualTo(",y");
        assertThat(requiredBytes("a+,$")).isEqualTo("a,");
        assertThat(requiredBytes("\\w+,\\bz")).isEqualTo(",z");
        // An element literal still wins over a rarer joined one, and a case-insensitive letter
        // ends a run.
        assertThat(requiredBytes("(?:x+,){2}yz")).isEqualTo("yz");
        assertThat(requiredBytes("x+,(?i:b)")).isEqualTo("x,");
        // Of the runs `a,x` and `x;`, the rarer is kept, compared in logarithms so that runs of
        // rare bytes whose probabilities multiply to zero still differ, runs of the same bytes
        // tie and keep the first, and a run keeps at most its first 64 bytes.
        assertThat(requiredBytes("a+,x+;")).isEqualTo("x;");
        assertThat(requiredBytes(".*(<\\B){63}<.*(#\\B){63}#")).isEqualTo("#".repeat(64));
        assertThat(requiredBytes(".*a\\Bb\\Bc.*c\\Bb\\Ba")).isEqualTo("abc");
        assertThat(requiredBytes("a+(?:,\\B){70}")).isEqualTo("a" + ",".repeat(63));
        // A pattern with no literal and no byte in every match keeps none.
        assertThat(requiredBytes("[^,\\s](\\s*)(?:[-+*/|!<=>%&^]+|:=)(\\s*)")).isNull();
        // Without a literal, each alternative gives its rarest byte, up to three bytes.
        assertThat(requiredBytes("a|bc")).isEqualTo("one of ab");
        assertThat(requiredBytes(URI_OR_EMAIL)).isEqualTo("one of /@");
        assertThat(requiredBytes("ab|cd|ef")).isEqualTo("one of bcf");
        assertThat(requiredBytes("ab|cd|ef|gh")).isNull();
        // A pattern with an inner literal search keeps its bytes too, since a find window shorter
        // than that search's minimum skips it.
        assertThat(Re2.compile(utf8Slice("[^=]*=.*")).innerLiteralSearchForDiagnostics()).isNotNull();
        assertThat(requiredBytes("[^=]*=.*")).isEqualTo("one of =");
        // A prefix scan runs after the find and engine setup the check saves, so a pattern with
        // one keeps its bytes.
        assertThat(Re2.compile(utf8Slice(AWS_KEY_QUICK)).forwardProgramForDiagnostics().canPrefixAccel()).isTrue();
        assertThat(requiredBytes(AWS_KEY_QUICK)).isEqualTo("one of A");
        // A pattern with a single-byte table keeps none: its group-zero matchers find short
        // windows on the table.
        compile(",").forEach((language, pattern) -> {
            assertThat(pattern.sharedSingleByteMatcher()).as(language).isNotNull();
            assertThat(pattern.requiredBytesForDiagnostics()).as(language).isNull();
        });
        // A case-insensitive letter gives no byte: one byte cannot cover both cases, and k also
        // matches the Kelvin sign.
        assertThat(requiredBytes("\\d(?i:k)")).isNull();
        assertThat(RustRegexp.compile(utf8Slice("\\d(?i:k)")).find(utf8Slice("1\u212A"))).isTrue();
        // A pattern whose matches span at least 256 bytes, counting the two bytes of each é and a
        // required prefix, keeps none: no short window can hold such a match, so such a pattern
        // goes without the long-window check too.
        assertThat(requiredBytes("abé{126}\\d")).isEqualTo("ab");
        assertThat(requiredBytes("abé{127}")).isNull();
        assertThat(requiredBytes("^" + "a".repeat(252) + "\\d+bc")).isEqualTo("bc");
        assertThat(requiredBytes("^" + "a".repeat(253) + "\\d+bc")).isNull();
    }

    @Test
    public void testSelectsRequiredSuffix()
    {
        // The suffix is the exact bytes every match ends with, groups looked through. A counted
        // capture's copies put `,` before `z`, a repetition of at least one ends with its child's
        // suffix, and the bytes before a child join its suffix only when the child is one exact
        // string.
        assertThat(requiredSuffix(BOUNDED_STRINGS)).isEqualTo(",z");
        assertThat(requiredSuffix("(?:x+,){2}yz")).isEqualTo("x,yz");
        assertThat(requiredSuffix("(?:,z)+")).isEqualTo(",z");
        assertThat(requiredSuffix("(.*?;){3}abcdefgh")).isEqualTo(";abcdefgh");
        // A zero-width op after the last byte, at any depth, leaves none, and one between bytes
        // ends them there.
        assertThat(requiredSuffix("foo\\b")).isNull();
        assertThat(requiredSuffix(",z$")).isNull();
        assertThat(requiredSuffix(",z(?:$|)")).isNull();
        assertThat(requiredSuffix("(?:,z\\b)+")).isNull();
        assertThat(requiredSuffix("a\\bcd")).isEqualTo("cd");
        assertThat(requiredSuffix("(b\\B)cd")).isEqualTo("cd");
        // No exact bytes, a case-insensitive letter, or one byte, gives none, and a longer run
        // keeps its last 64 bytes.
        assertThat(requiredSuffix("(?:a|b)")).isNull();
        assertThat(requiredSuffix("\\w+")).isNull();
        assertThat(requiredSuffix("(?i)ab")).isNull();
        assertThat(requiredSuffix("x+,(?i:b)")).isNull();
        assertThat(requiredSuffix("\\d+z")).isNull();
        assertThat(requiredSuffix("a+(?:,b){40}")).isEqualTo(",b".repeat(32));

        // A pattern whose long windows are checked for its literal takes the bound in every
        // language, unless an assertion the DFA cannot run reads past the next byte: Java's and
        // Trino's $ here. One whose literal another route searches takes neither.
        compile(BOUNDED_STRINGS).forEach((language, pattern) -> {
            assertThat(pattern.requiredBytesForDiagnostics().boundsSearchEnd()).as(language).isTrue();
            assertThat(pattern.requiredBytesForDiagnostics().suffixForDiagnostics()).as(language).isEqualTo(",z".getBytes(UTF_8));
        });
        String finalTerminator = "(?:a$)?" + BOUNDED_STRINGS;
        Map.of("Java", JavaRegexp.compile(utf8Slice(finalTerminator)), "Trino", TrinoRegexp.compile(utf8Slice(finalTerminator)).re2()).forEach((language, pattern) -> {
            assertThat(pattern.forwardProgramForDiagnostics().hasDfaUnsupportedAssertions()).as(language).isTrue();
            assertThat(pattern.requiredBytesForDiagnostics().searchesLongWindows()).as(language).isTrue();
            assertThat(pattern.requiredBytesForDiagnostics().boundsSearchEnd()).as(language).isFalse();
        });
        assertThat(Re2.compile(utf8Slice(finalTerminator)).requiredBytesForDiagnostics().boundsSearchEnd()).isTrue();
        compile(BOUNDED_STRINGS + WITHOUT_SUFFIX).forEach((language, pattern) -> {
            assertThat(pattern.requiredBytesForDiagnostics().searchesLongWindows()).as(language).isTrue();
            assertThat(pattern.requiredBytesForDiagnostics().boundsSearchEnd()).as(language).isFalse();
        });
        compile("x(.*?,){3}z").forEach((language, pattern) -> assertThat(pattern.longWindowRequiredLiteral()).as(language).isNull());

        // A suffix longer than the required literal needs a separate scanner, and its larger
        // charge may not fit when the forward check does. From the smallest budget at which the
        // first-match DFA initializes, the long-window search fits first, and the pattern keeps
        // it without the bound.
        String longer = "(.*?;){3}abcdefgh";
        long initializes = smallestBudget(longer, TestRequiredBytes::firstMatchDfaInitializes);
        long searched = firstBudget(initializes, budget -> {
            RequiredBytes bytes = requiredBytesAt(longer, budget);
            return bytes != null && bytes.searchesLongWindows();
        });
        RequiredBytes unbounded = requiredBytesAt(longer, searched);
        assertThat(unbounded.boundsSearchEnd()).isFalse();
        long bounded = firstBudget(searched, budget -> requiredBytesAt(longer, budget).boundsSearchEnd());
        assertThat(bounded).isGreaterThan(searched);
        assertThat(requiredBytesAt(longer, bounded).estimatedRetainedSize()).isGreaterThan(unbounded.estimatedRetainedSize());
    }

    @Test
    public void testRejectsShortWindowsWithoutRequiredBytes()
    {
        Map<String, Map<String, Boolean>> cases = Map.of(
                NOQA_REAL, NOQA_LINES,
                NOQA_TWEAKED, NOQA_LINES,
                CODING, Map.of(
                        "# -*- coding: utf-8 -*-", true,
                        "  #coding=utf8", true,
                        "    return coding", false,
                        "# -*- codin: utf-8 -*-", false),
                URI_OR_EMAIL, Map.of(
                        "see http://example.com/x", true,
                        "mail me at a@b", true,
                        "nothing to see here, just words", false,
                        "a colon: but no slashes", false),
                "abcabd", Map.of(
                        "x abcabd y", true,
                        "x abcab d y", false),
                AWS_KEY_QUICK, Map.of(
                        "key = AKIAIOSFODNN7EXAMPLE", true,
                        "key = AKIAIOSFODNN8EXAMPLE", false,
                        "no key on this line", false));
        for (Map.Entry<String, Map<String, Boolean>> testCase : cases.entrySet()) {
            String pattern = testCase.getKey();
            for (Map.Entry<String, CompiledPattern> compiled : compile(pattern).entrySet()) {
                String language = compiled.getKey();
                CompiledPattern regexp = compiled.getValue();
                assertThat(regexp.requiredBytesForDiagnostics()).as("%s %s", language, pattern).isNotNull();
                for (Map.Entry<String, Boolean> lineCase : testCase.getValue().entrySet()) {
                    String line = lineCase.getKey();
                    boolean expected = lineCase.getValue();
                    Slice input = utf8Slice(line);
                    assertThat(regexp.find(input)).as("%s %s on %s", language, pattern, line).isEqualTo(expected);
                    assertThat(regexp.matcher(input).find()).as("%s matcher %s on %s", language, pattern, line).isEqualTo(expected);
                    // A region that starts after the literal's first bytes has no match.
                    if (expected && NOQA_LINES.containsKey(line)) {
                        RegexpMatcher matcher = regexp.matcher(input);
                        matcher.reset(input, line.toLowerCase(Locale.ROOT).lastIndexOf(NOQA_LITERAL) + 2, input.length());
                        assertThat(matcher.find()).as("%s region %s on %s", language, pattern, line).isFalse();
                    }
                }
            }
        }

        // The check rejects a short window before any engine runs: without it, these finds would
        // build and cache a forward DFA.
        Slice line = utf8Slice("nothing to see here, just words");
        Re2 matcherPattern = Re2.compile(utf8Slice(URI_OR_EMAIL));
        assertThat(matcherPattern.matcher(line).find()).isFalse();
        assertNoForwardDfa(matcherPattern, "matcher");
        Re2 findPattern = Re2.compile(utf8Slice(URI_OR_EMAIL));
        assertThat(findPattern.find(line)).isFalse();
        assertNoForwardDfa(findPattern, "find");
    }

    @Test
    public void testFindsRequiredBytesAtEveryOffset()
    {
        CompiledPattern noqa = Re2.compile(utf8Slice(NOQA_TWEAKED));
        RequiredBytes literal = noqa.requiredBytesForDiagnostics();
        RequiredBytes oneOf = Re2.compile(utf8Slice(URI_OR_EMAIL)).requiredBytesForDiagnostics();
        // One byte takes its own scan, two words a step, then a word and a last word that
        // overlaps the ones before it; a window under a word is compared byte by byte.
        RequiredBytes oneByte = Re2.compile(utf8Slice("[^@]*@.*")).requiredBytesForDiagnostics();
        assertThat(oneByte.isSingleByte()).isTrue();
        assertThat(oneOf.isSingleByte()).isFalse();
        Map<String, Boolean> literalSpellings = Map.of(NOQA_LITERAL, true, "# NoQA", true, "#  noqa", false, "# noq@", false);
        Map<String, Boolean> oneOfSpellings = Map.of("/", true, "@", true, ":", false);
        Map<String, Boolean> oneByteSpellings = Map.of("@", true, "/", false);
        for (int length = 0; length <= 40; length++) {
            for (Map.Entry<String, Boolean> spelling : literalSpellings.entrySet()) {
                for (int offset = 0; offset + spelling.getKey().length() <= length; offset++) {
                    Slice input = line(spelling.getKey(), length, offset);
                    assertOccursAt(literal, input, spelling.getKey(), offset, spelling.getValue());
                    assertThat(noqa.find(input)).as(input.toStringUtf8()).isEqualTo(spelling.getValue());
                }
            }
            for (Map.Entry<String, Boolean> spelling : oneOfSpellings.entrySet()) {
                for (int offset = 0; offset + spelling.getKey().length() <= length; offset++) {
                    assertOccursAt(oneOf, line(spelling.getKey(), length, offset), spelling.getKey(), offset, spelling.getValue());
                }
            }
            for (Map.Entry<String, Boolean> spelling : oneByteSpellings.entrySet()) {
                for (int offset = 0; offset + spelling.getKey().length() <= length; offset++) {
                    assertOccursAt(oneByte, line(spelling.getKey(), length, offset), spelling.getKey(), offset, spelling.getValue());
                }
            }
        }
    }

    @Test
    public void testChecksOnlyShortWindows()
    {
        // Windows one byte under and at the limit, as slices that start past their array's first
        // byte with the literal just outside them. Both answer false; only the shorter one is
        // rejected by the check.
        CompiledPattern noqa = Re2.compile(utf8Slice(NOQA_TWEAKED));
        int literalLength = NOQA_LITERAL.length();
        for (int length : new int[] {RequiredBytes.SHORT_WINDOW_LIMIT - 1, RequiredBytes.SHORT_WINDOW_LIMIT}) {
            byte[] bytes = (NOQA_LITERAL + "x".repeat(length) + NOQA_LITERAL).getBytes(UTF_8);
            Slice input = Slices.wrappedBuffer(bytes, literalLength, length);
            assertThat(noqa.shortWindowLacksRequiredBytes(input, 0, length)).as("check at %s", length).isEqualTo(length < RequiredBytes.SHORT_WINDOW_LIMIT);
            assertThat(noqa.find(input)).as("find at %s", length).isFalse();
            assertThat(noqa.matcher(input).find()).as("matcher at %s", length).isFalse();

            // The literal at the window's last bytes is found.
            Slice ending = Slices.wrappedBuffer(bytes, 2 * literalLength, length);
            assertThat(noqa.shortWindowLacksRequiredBytes(ending, 0, length)).as("ending check at %s", length).isFalse();
            assertThat(noqa.find(ending)).as("ending find at %s", length).isTrue();
            assertThat(noqa.matcher(ending).find()).as("ending matcher at %s", length).isTrue();
        }

        // A single required byte checks every short window beside an inner literal search too, so
        // a line takes one route whatever its length, and the search still runs from its minimum,
        // as for the folded literal `=noqa`. A gated one-byte search for that byte itself, the
        // email's `@` and Trino's `=`, runs at every length in place of the check, and the pattern
        // keeps no required bytes.
        for (String pattern : List.of(EMAIL, NOQA_ASSIGNMENT)) {
            for (Map.Entry<String, CompiledPattern> compiled : compile(pattern).entrySet()) {
                String description = "%s %s".formatted(compiled.getKey(), pattern);
                CompiledPattern regexp = compiled.getValue();
                InnerLiteralSearch search = regexp.innerLiteralSearchForDiagnostics();
                boolean searchesByte = pattern.equals(EMAIL) || compiled.getKey().equals("Trino");
                assertThat(search.checksShortWindows()).as(description).isEqualTo(searchesByte);
                assertThat(regexp.requiredBytesForDiagnostics() == null).as(description).isEqualTo(searchesByte);
                for (int length = 0; length < RequiredBytes.SHORT_WINDOW_LIMIT; length++) {
                    Slice input = utf8Slice("x".repeat(length));
                    if (!searchesByte) {
                        assertThat(regexp.shortWindowLacksRequiredBytes(input, 0, length)).as("%s at %s", description, length).isTrue();
                    }
                    assertThat(regexp.searchesInnerLiteralWindow(length)).as("%s at %s", description, length).isEqualTo(searchesByte || length >= search.minimumSearchBytes());
                }
            }
        }

        // A one-of set of two or three distinct bytes beside an inner literal search checks exactly
        // the short windows too short for that search, whose minimum depends on its literal's kind:
        // 64 bytes for the class pair `[?!.]["']` and 256 for the set `ing|ment`, whose bytes keep
        // their check of every short window.
        for (OneOfBesideSearch oneOf : List.of(
                new OneOfBesideSearch("\\w+[?!.][\"'](?:=x|;y)", "one of =;", CompiledPattern.MINIMUM_LONG_SEARCH_BYTES),
                new OneOfBesideSearch("[a-z](?:ing|ment)", "one of gm", InnerLiteralSearch.MINIMUM_LITERAL_SET_SEARCH_BYTES))) {
            for (Map.Entry<String, CompiledPattern> compiled : compile(oneOf.pattern()).entrySet()) {
                String description = "%s %s".formatted(compiled.getKey(), oneOf.pattern());
                CompiledPattern regexp = compiled.getValue();
                assertThat(regexp.requiredBytesForDiagnostics()).as(description).hasToString(oneOf.bytes());
                assertThat(regexp.innerLiteralSearchForDiagnostics().minimumSearchBytes()).as(description).isEqualTo(oneOf.searchMinimum());
                for (int length = 0; length < RequiredBytes.SHORT_WINDOW_LIMIT; length++) {
                    Slice input = utf8Slice("x".repeat(length));
                    assertThat(regexp.shortWindowLacksRequiredBytes(input, 0, length)).as("%s at %s", description, length).isEqualTo(!regexp.searchesInnerLiteralWindow(length));
                }
            }
        }

        // A window without `@` is the search's to reject at every short length, so neither find
        // starts the forward DFA.
        for (int length : new int[] {0, 7, 8, 63, RequiredBytes.SHORT_WINDOW_LIMIT - 1}) {
            Slice input = utf8Slice("x".repeat(length));
            for (Map.Entry<String, CompiledPattern> compiled : compile(EMAIL).entrySet()) {
                String description = "%s at %s".formatted(compiled.getKey(), length);
                CompiledPattern email = compiled.getValue();
                assertThat(email.find(input)).as(description).isFalse();
                assertThat(email.matcher(input).find()).as(description).isFalse();
                assertNoForwardDfa(email, description);
            }
        }

        // One byte short of the search's minimum, 96 bytes for the folded literal `=noqa` and 64 for
        // Trino's `=`, the check rejects a window without `=`, and in Trino the search does, so a
        // find starts no forward DFA there either.
        for (Map.Entry<String, CompiledPattern> compiled : compile(NOQA_ASSIGNMENT).entrySet()) {
            CompiledPattern folded = compiled.getValue();
            int length = folded.innerLiteralSearchForDiagnostics().minimumSearchBytes() - 1;
            String description = "%s at %s".formatted(compiled.getKey(), length);
            assertThat(folded.find(utf8Slice("x".repeat(length)))).as(description).isFalse();
            assertNoForwardDfa(folded, description);
        }
    }

    @Test
    public void testRejectsLongWindowsWithoutRequiredLiteral()
    {
        assertThat(SHORT_FILLER.length()).isBetween(RequiredBytes.SHORT_WINDOW_LIMIT, Prog.FUSED_PREFIX_VECTOR_MINIMUM_BYTES - 1);
        assertThat(LONG_FILLER.length()).isGreaterThanOrEqualTo(Prog.FUSED_PREFIX_VECTOR_MINIMUM_BYTES);

        // A window of at least the short-window limit that lacks the literal is rejected before
        // any engine runs, under and over the vector scan minimum: without the check, each search
        // would build and cache a forward DFA. Only a literal wholly inside the window counts, so
        // one cut by its end or start does not keep it, and a rejected findInto unsets the groups.
        String match = BOUNDED_STRINGS_MATCH;
        String endsWithMatch = LONG_FILLER + match;
        String startsWithMatch = match + "\n" + LONG_FILLER;
        List<RegionCase> windows = List.of(
                new RegionCase(SHORT_FILLER, 0, SHORT_FILLER.length(), List.of()),
                new RegionCase(LONG_FILLER, 0, LONG_FILLER.length(), List.of()),
                new RegionCase(endsWithMatch, 0, endsWithMatch.length() - 1, List.of()),
                new RegionCase(startsWithMatch, match.length() - 1, startsWithMatch.length(), List.of()));
        for (RegionCase window : windows) {
            Slice input = utf8Slice(window.text());
            int start = window.start();
            int end = window.end();
            String description = "[%s, %s) of %s bytes".formatted(start, end, input.length());
            for (Map.Entry<String, CompiledPattern> entry : compile(BOUNDED_STRINGS).entrySet()) {
                String language = entry.getKey() + " " + description;
                RegexpMatcher matcher = entry.getValue().matcher(input);
                matcher.reset(input, start, end);
                assertThat(matcher.find()).as(language).isFalse();
                assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(language).isEqualTo(1);
                assertNoForwardDfa(entry.getValue(), language + " matcher");
            }
            for (Map.Entry<String, CompiledPattern> entry : compile(BOUNDED_STRINGS).entrySet()) {
                String language = entry.getKey() + " " + description;
                assertThat(entry.getValue().find(input, start, end)).as(language).isFalse();
                assertNoForwardDfa(entry.getValue(), language + " range find");
            }
            for (Map.Entry<String, CompiledPattern> entry : compile(BOUNDED_STRINGS).entrySet()) {
                String language = entry.getKey() + " " + description;
                CompiledPattern pattern = entry.getValue();
                int[] groups = new int[2 * (pattern.capturingGroupCount() + 1)];
                Arrays.fill(groups, 7);
                assertThat(pattern.findInto(input, start, end, groups)).as(language).isFalse();
                assertThat(groups).as(language).containsOnly(-1);
                assertNoForwardDfa(pattern, language + " range findInto");
            }
            if (start == 0 && end == input.length()) {
                for (Map.Entry<String, CompiledPattern> entry : compile(BOUNDED_STRINGS).entrySet()) {
                    assertThat(entry.getValue().find(input)).as(entry.getKey()).isFalse();
                    assertNoForwardDfa(entry.getValue(), entry.getKey() + " find");
                }
                for (Map.Entry<String, CompiledPattern> entry : compile(BOUNDED_STRINGS).entrySet()) {
                    assertThat(entry.getValue().count(input)).as(entry.getKey()).isZero();
                    assertNoForwardDfa(entry.getValue(), entry.getKey() + " count");
                }
            }
        }

        // A window that holds the literal runs the engine, over and under the vector scan
        // minimum. A region that starts inside the first match finds only the second.
        String ends = match + "\n" + LONG_FILLER + match;
        String inner = LONG_FILLER + match + "\n" + LONG_FILLER;
        String shortInner = SHORT_FILLER + match;
        List<RegionCase> cases = List.of(
                new RegionCase(LONG_FILLER_WITH_LITERAL, 0, LONG_FILLER_WITH_LITERAL.length(), List.of()),
                new RegionCase(inner, 0, inner.length(), List.of(new Span(LONG_FILLER.length(), LONG_FILLER.length() + match.length()))),
                new RegionCase(shortInner, 0, shortInner.length(), List.of(new Span(SHORT_FILLER.length(), shortInner.length()))),
                new RegionCase(ends, 0, ends.length(), List.of(new Span(0, match.length()), new Span(ends.length() - match.length(), ends.length()))),
                new RegionCase(ends, match.length() - 1, ends.length(), List.of(new Span(ends.length() - match.length(), ends.length()))));
        for (RegionCase regionCase : cases) {
            Slice input = utf8Slice(regionCase.text());
            int start = regionCase.start();
            int end = regionCase.end();
            boolean found = !regionCase.spans().isEmpty();
            for (Map.Entry<String, CompiledPattern> entry : compile(BOUNDED_STRINGS).entrySet()) {
                String description = "%s [%s, %s) of %s bytes".formatted(entry.getKey(), start, end, input.length());
                CompiledPattern pattern = entry.getValue();
                RegexpMatcher matcher = pattern.matcher(input);
                matcher.reset(input, start, end);
                assertThat(spans(matcher)).as(description).isEqualTo(regionCase.spans());
                assertThat(pattern.find(input, start, end)).as(description).isEqualTo(found);
                if (start == 0 && end == input.length()) {
                    assertThat(pattern.find(input)).as(description).isEqualTo(found);
                    assertThat(pattern.count(input)).as(description).isEqualTo(regionCase.spans().size());
                }
            }
        }

        // A Slice that starts past its array's first byte, with the match at its end, past the
        // bytes a search that ignored the offset would read.
        int offset = 64;
        byte[] bytes = ("x".repeat(offset) + endsWithMatch).getBytes(UTF_8);
        Slice offsetInput = Slices.wrappedBuffer(bytes, offset, endsWithMatch.length());
        Span offsetMatch = new Span(LONG_FILLER.length(), endsWithMatch.length());
        for (Map.Entry<String, CompiledPattern> entry : compile(BOUNDED_STRINGS).entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(spans(pattern.matcher(offsetInput))).as(language).containsExactly(offsetMatch);
            assertThat(pattern.find(offsetInput)).as(language).isTrue();
            assertThat(pattern.count(offsetInput)).as(language).isEqualTo(1);
        }

        // A literal set's search that only the class-run proof admits leaves the check as the
        // pattern has it without that search, so the check rejects a window that lacks the literal
        // and is shorter than the minimum a set behind a class run needs before its search runs.
        Slice names = utf8Slice(SHORT_FILLER + "Tom and Finn\n");
        for (Map.Entry<String, CompiledPattern> entry : compile(".{0,2}(Tom|Finn)xyz").entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(pattern.innerLiteralSearchForDiagnostics().minimumSearchBytes()).as(language).isGreaterThan(names.length());
            RegexpMatcher matcher = pattern.matcher(names);
            assertThat(matcher.find()).as(language).isFalse();
            assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(language).isEqualTo(1);
            assertThat(pattern.find(names)).as(language).isFalse();
            assertThat(pattern.count(names)).as(language).isZero();
            assertNoForwardDfa(pattern, language + " names");
        }
    }

    @Test
    public void testScansForRequiredLiteralOncePerSearch()
    {
        String match = BOUNDED_STRINGS_MATCH;
        Slice matches = utf8Slice((LONG_FILLER + match + "\n").repeat(3) + LONG_FILLER);
        Slice dense = utf8Slice(("\n" + match).repeat(100));
        for (Map.Entry<String, CompiledPattern> entry : compile(BOUNDED_STRINGS).entrySet()) {
            String language = entry.getKey();
            // Each find of a loop scans from its start, the last finding no literal.
            RegexpMatcher matcher = entry.getValue().matcher(matches);
            assertThat(spans(matcher)).as(language).hasSize(3);
            assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(language).isEqualTo(4);
            // A find again from the same start scans again, since only a found occurrence is
            // reused.
            long scans = matcher.requiredLiteralScanCountForDiagnostics();
            assertThat(matcher.find()).as(language).isFalse();
            assertThat(matcher.requiredLiteralScanCountForDiagnostics() - scans).as(language).isEqualTo(1);
            // A find that fails although its scan found the literal keeps that occurrence, so a
            // find again from before it does not scan.
            matcher.reset(utf8Slice(LONG_FILLER_WITH_LITERAL));
            scans = matcher.requiredLiteralScanCountForDiagnostics();
            assertThat(matcher.find()).as(language).isFalse();
            assertThat(matcher.find()).as(language).isFalse();
            assertThat(matcher.requiredLiteralScanCountForDiagnostics() - scans).as(language).isEqualTo(1);
            // A reset forgets the occurrence the last scan found.
            scans = matcher.requiredLiteralScanCountForDiagnostics();
            matcher.reset(utf8Slice(LONG_FILLER));
            assertThat(matcher.find()).as(language).isFalse();
            matcher.reset(matches);
            assertThat(matcher.find()).as(language).isTrue();
            assertThat(matcher.requiredLiteralScanCountForDiagnostics() - scans).as(language).isEqualTo(2);
            // On a dense window, the scans stop after finding the literal near their start
            // NEAR_REQUIRED_LITERAL_SCAN_LIMIT times in a row, until a reset.
            scans = matcher.requiredLiteralScanCountForDiagnostics();
            matcher.reset(dense);
            assertThat(spans(matcher)).as(language).hasSize(100);
            assertThat(matcher.requiredLiteralScanCountForDiagnostics() - scans).as(language).isEqualTo(RegexpMatcher.NEAR_REQUIRED_LITERAL_SCAN_LIMIT);
            scans = matcher.requiredLiteralScanCountForDiagnostics();
            matcher.reset(utf8Slice(LONG_FILLER));
            assertThat(matcher.find()).as(language).isFalse();
            assertThat(matcher.requiredLiteralScanCountForDiagnostics() - scans).as(language).isEqualTo(1);
        }

        // A pattern whose literal another route already searches for, an inner literal search, a
        // prefix scan, or a start literal filter, or whose literal is case-insensitive, does not
        // scan for it on long windows. Neither does one whose class pair only the class-run proof
        // admits, `[?!.]["']` behind `\w+`, since without that proof the search is for the later
        // `foobar`. The DFA builds a start literal filter only with the Vector API. A folded run
        // that stands in for no prefix counts as the start literal filter the program would have
        // without it: (?i)kernel scans for ernel behind the Kelvin sign's lead where its start
        // bytes would carry a filter.
        List<OtherRoute> otherRoutes = new ArrayList<>(List.of(
                new OtherRoute("\\w+,\\bz", "ab,z", pattern -> pattern.innerLiteralSearchForDiagnostics() != null),
                new OtherRoute("\\w+[?!.][\"']foobar", "ab!'foobar", pattern -> pattern.innerLiteralSearchForDiagnostics() != null),
                new OtherRoute("x(.*?,){3}z", "x,,,z", pattern -> pattern.forwardProgramForDiagnostics().canPrefixAccel()),
                new OtherRoute("(.*?,){3}# [Nn]oqa", ",,,# Noqa", pattern -> !pattern.requiredBytesForDiagnostics().isExactLiteral())));
        if (VectorSupport.isAvailable()) {
            otherRoutes.add(new OtherRoute("(?:abc|def)(?:[a-q],){3}z", "abca,b,c,z", pattern -> Dfa.DfaInstance.searchesStartLiteralFilter(pattern.forwardProgramForDiagnostics(), Dfa.DfaInstance.Kind.FIRST_MATCH)));
            otherRoutes.add(new OtherRoute("(?i)kernel.*?,#", "Kernel,#", pattern -> pattern.forwardProgramForDiagnostics().canPrefixAccel()));
        }
        for (OtherRoute otherRoute : otherRoutes) {
            Slice input = utf8Slice(LONG_FILLER + otherRoute.match());
            for (Map.Entry<String, CompiledPattern> entry : compile(otherRoute.expression()).entrySet()) {
                String description = entry.getKey() + " " + otherRoute.expression();
                CompiledPattern pattern = entry.getValue();
                assertThat(pattern.requiredBytesForDiagnostics()).as(description).isNotNull();
                assertThat(otherRoute.route().test(pattern)).as(description).isTrue();
                assertThat(pattern.longWindowRequiredLiteral()).as(description).isNull();
                RegexpMatcher matcher = pattern.matcher(input);
                assertThat(matcher.find()).as(description).isTrue();
                assertThat(matcher.find()).as(description).isFalse();
                assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(description).isZero();
            }
        }

        // Otherwise a folded run that stands in for no prefix is no other route: Trino's
        // (?i)Holmes, whose s also matches the long s, scans for its run and its literal. A run
        // that replaces a prefix is one: RE2's (?i)password scans for word in place of pa. With
        // 64-byte vectors (?i)Holmes has no run, and its rare start bytes take the two-literal
        // start literal filter, another route, as on main.
        CompiledPattern holmes = TrinoRegexp.compile(utf8Slice("(?i)Holmes[^\\n]{0,40}\\(\\)")).re2();
        if (!VectorSupport.isAvailable() || VectorSupport.preferredByteLanes() < 64) {
            assertThat(holmes.forwardProgramForDiagnostics().canPrefixAccel()).isTrue();
            assertThat(holmes.longWindowRequiredLiteral()).isNotNull();
            RegexpMatcher holmesMatcher = holmes.matcher(utf8Slice(LONG_FILLER));
            assertThat(holmesMatcher.find()).isFalse();
            assertThat(holmesMatcher.requiredLiteralScanCountForDiagnostics()).isEqualTo(1);
        }
        else {
            assertThat(holmes.forwardProgramForDiagnostics().canPrefixAccel()).isFalse();
            assertThat(holmes.longWindowRequiredLiteral()).isNull();
        }
        assertThat(Re2.compile(utf8Slice("(?i)password(?:.*?,){3}#")).longWindowRequiredLiteral()).isNull();

        // An inner literal search that only the class-run proof admits is no other route: the
        // pattern scans for its literal on long windows as it would without that search.
        for (Map.Entry<String, CompiledPattern> entry : compile("(\\w+)foobar(\\d)").entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(pattern.innerLiteralSearchForDiagnostics()).as(language).isNotNull();
            assertThat(pattern.longWindowRequiredLiteral()).as(language).isNotNull();
            RegexpMatcher matcher = pattern.matcher(utf8Slice(LONG_FILLER));
            assertThat(matcher.find()).as(language).isFalse();
            assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(language).isEqualTo(1);
        }

        // A start literal filter that no DFA search runs is no other route. Java's $ is an
        // assertion the DFA cannot run, so the pattern scans for its literal before BitState
        // would search the window.
        if (VectorSupport.isAvailable()) {
            CompiledPattern pattern = JavaRegexp.compile(utf8Slice("(?:abc|def)(?:[a-q],){40}z$"));
            Prog program = pattern.forwardProgramForDiagnostics();
            assertThat(program.hasDfaUnsupportedAssertions()).isTrue();
            assertThat(Dfa.DfaInstance.searchesStartLiteralFilter(program, Dfa.DfaInstance.Kind.FIRST_MATCH)).isTrue();
            RegexpMatcher matcher = pattern.matcher(utf8Slice(LONG_FILLER));
            assertThat(matcher.find()).isFalse();
            assertThat(matcher.requiredLiteralScanCountForDiagnostics()).isEqualTo(1);
            assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).isFalse();
        }
    }

    @Test
    public void testBitStateCaptureRejectsWindowWithoutInnerLiteral()
    {
        // A pattern with an inner literal search that the class-run proof does not add keeps no
        // long-window check, so the routes that skip that search scan for its literal instead. A
        // find with groups of a window BitState can hold skips both DFAs and the search, and a
        // window without the literal is rejected before BitState starts.
        Slice letters = utf8Slice("a".repeat(8192));
        for (Map.Entry<String, CompiledPattern> entry : compile("[a-z]+[a-c]*(,z)").entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(pattern.innerLiteralSearchForDiagnostics()).as(language).isNotNull();
            assertThat(pattern.longWindowRequiredLiteral()).as(language).isNull();
            assertThat(pattern.canUseDirectBitStateCapture(pattern.forwardProgramForDiagnostics(), letters.length(), 2)).as(language).isTrue();
            RegexpMatcher matcher = pattern.matcher(letters);
            assertThat(matcher.find()).as(language).isFalse();
            assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).as(language).isFalse();
        }
    }

    @Test
    public void testSkippedDfaRejectsWindowWithoutInnerLiteral()
    {
        // A find that skips the DFA skips the inner literal search too, so it scans for the
        // search's literal instead. A Unicode word boundary makes the DFA quit on a byte outside
        // ASCII, and after DFA_QUIT_STREAK_LIMIT finds in a row whose DFA quit, a matcher's finds
        // skip the DFA. Every window up to the last match is longer than BitState holds, so those
        // finds run the NFA, and the last window, which holds no literal, would be the first that
        // BitState holds.
        Map<String, CompiledPattern> boundaries = compile("\\b[a-z]+,z\\b");
        for (String language : List.of("Java", "Trino", "Rust")) {
            CompiledPattern pattern = boundaries.get(language);
            assertThat(pattern.forwardProgramForDiagnostics().dfaQuitsOnNonAscii()).as(language).isTrue();
            assertThat(pattern.longWindowRequiredLiteral()).as(language).isNull();
            int tail = pattern.forwardProgramForDiagnostics().unanchoredBitStateTextMaxSize() - 1;
            Slice input = utf8Slice("\u00E9 ab,z ".repeat(6) + "-".repeat(tail));
            RegexpMatcher matcher = pattern.matcher(input);
            assertThat(spans(matcher)).as(language).hasSize(6);
            assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).as(language).isTrue();
            assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).as(language).isFalse();
        }
    }

    @Test
    public void testBoundsLongCountsAndRangeSearchesByLastRequiredSuffix()
    {
        // With the bytes every match ends with only at the window start, a long range search
        // ends there, before the engine has room for a match, so no forward DFA is built; without
        // the bound the engine reads the window. Under and over the vector scan minimum, and in a
        // Slice past its array's first byte.
        for (String filler : List.of(SHORT_FILLER, LONG_FILLER)) {
            String text = ",z\n" + filler;
            byte[] padded = ("x".repeat(64) + text).getBytes(UTF_8);
            for (Slice input : List.of(utf8Slice(text), Slices.wrappedBuffer(padded, 64, text.length()))) {
                for (Map.Entry<String, CompiledPattern> entry : compile(BOUNDED_STRINGS).entrySet()) {
                    String language = entry.getKey() + " " + filler.length();
                    CompiledPattern pattern = entry.getValue();
                    assertThat(pattern.find(input, 0, input.length())).as(language).isFalse();
                    int[] groups = new int[2 * (pattern.capturingGroupCount() + 1)];
                    assertThat(pattern.findInto(input, 0, input.length(), groups)).as(language).isFalse();
                    assertThat(groups).as(language).containsOnly(-1);
                    assertNoForwardDfa(pattern, language + " range");
                }
            }
            // With the bytes also at the end, the window holds them there and the engine reads it.
            Slice atEnd = utf8Slice(text + ",z");
            for (Map.Entry<String, CompiledPattern> entry : compile(BOUNDED_STRINGS).entrySet()) {
                assertThat(entry.getValue().find(atEnd, 0, atEnd.length())).as(entry.getKey()).isFalse();
                Prog program = entry.getValue().forwardProgramForDiagnostics();
                assertThat(program.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH) != null || program.cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH) != null).as(entry.getKey()).isTrue();
            }
        }

        // A count reads only up to the bytes' last occurrence: with it at the start, the DFA
        // meets none of the commas after it, whose counted copies take states, and builds the
        // states that counting the bytes alone builds. That baseline is padded to the pattern's
        // minimum length with newlines, which add no state, since a shorter count runs no engine.
        String commas = ("a," + "b,".repeat(11) + "c\n").repeat(40);
        Map<String, CompiledPattern> counted = compile(BOUNDED_STRINGS);
        Map<String, CompiledPattern> countedAlone = compile(BOUNDED_STRINGS);
        for (String language : counted.keySet()) {
            assertThat(counted.get(language).count(utf8Slice(",z\n" + commas))).as(language).isZero();
            assertThat(countedAlone.get(language).count(utf8Slice(",z" + "\n".repeat(12)))).as(language).isZero();
            assertThat(firstMatchStates(counted.get(language))).as(language).isEqualTo(firstMatchStates(countedAlone.get(language)));
        }

        // Where the bytes every match ends with do not hold the literal, a window holding them may
        // still lack the literal, so it is checked for the literal too: one ending with them but
        // holding no literal runs no engine.
        String apart = "(.*?;){3}abcdefgh.*zz";
        Slice withoutLiteral = utf8Slice("a;b;c;zz\n".repeat(60).stripTrailing());
        for (Map.Entry<String, CompiledPattern> entry : compile(apart).entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(pattern.requiredBytesForDiagnostics().boundsSearchEnd()).as(language).isTrue();
            assertThat(pattern.requiredBytesForDiagnostics().suffixHoldsLiteral()).as(language).isFalse();
            assertThat(pattern.count(withoutLiteral)).as(language).isZero();
            assertThat(pattern.find(withoutLiteral, 0, withoutLiteral.length())).as(language).isFalse();
            int[] groups = new int[2 * (pattern.capturingGroupCount() + 1)];
            assertThat(pattern.findInto(withoutLiteral, 0, withoutLiteral.length(), groups)).as(language).isFalse();
            assertNoForwardDfa(pattern, language);
        }
        Re2 re2 = Re2.compile(utf8Slice(apart));
        assertThat(re2.replaceAll(withoutLiteral, utf8Slice("x"))).isEqualTo(new Re2.ReplaceAllResult(0, withoutLiteral));
        assertNoForwardDfa(re2, "RE2 replaceAll");
    }

    @Test
    public void testSearchEndBoundKeepsEveryResult()
    {
        // Each pattern with the bound gives the results of the same pattern without it, in every
        // language, on windows whose bytes lie early, late, or nowhere, with matches early, late,
        // dense, across CR LF, and after non-ASCII text.
        for (SuffixCase suffixCase : SUFFIX_CASES) {
            String match = suffixCase.match();
            String suffix = suffixCase.suffix();
            List<String> texts = List.of(
                    match + "\n" + LONG_FILLER,
                    LONG_FILLER + match,
                    match + "\n" + LONG_FILLER + match + "\n" + LONG_FILLER,
                    suffix + "\n" + LONG_FILLER,
                    SHORT_FILLER + suffix + "\n" + SHORT_FILLER + match + "x",
                    ("\n" + match).repeat(60),
                    (match + "\r\n" + FILLER_LINE.replace("\n", "\r\n")).repeat(12),
                    "é".repeat(200) + match + "é".repeat(300));
            Map<String, CompiledPattern> bounded = compile(suffixCase.expression());
            Map<String, CompiledPattern> unbounded = compile(suffixCase.expression() + WITHOUT_SUFFIX);
            for (String language : bounded.keySet()) {
                String description = language + " " + suffixCase.expression();
                assertThat(bounded.get(language).requiredBytesForDiagnostics().boundsSearchEnd()).as(description).isTrue();
                assertThat(unbounded.get(language).requiredBytesForDiagnostics().boundsSearchEnd()).as(description).isFalse();
                assertThat(spans(bounded.get(language).matcher(utf8Slice(match)))).as(description).containsExactly(new Span(0, match.length()));
                for (String text : texts) {
                    Slice input = utf8Slice(text);
                    assertThat(results(bounded.get(language), input)).as("%s on %s", description, text).isEqualTo(results(unbounded.get(language), input));
                }
            }
            Re2 re2 = Re2.compile(utf8Slice(suffixCase.expression()));
            Re2 re2Unbounded = Re2.compile(utf8Slice(suffixCase.expression() + WITHOUT_SUFFIX));
            TrinoRegexp trino = TrinoRegexp.compile(utf8Slice(suffixCase.expression()));
            TrinoRegexp trinoUnbounded = TrinoRegexp.compile(utf8Slice(suffixCase.expression() + WITHOUT_SUFFIX));
            for (String text : texts) {
                String description = suffixCase.expression() + " on " + text;
                Slice input = utf8Slice(text);
                Slice rewrite = utf8Slice("<\\0|\\1>");
                assertThat(re2.replaceAll(input, rewrite)).as(description).isEqualTo(re2Unbounded.replaceAll(input, rewrite));
                assertThat(re2.replaceFirst(input, rewrite)).as(description).isEqualTo(re2Unbounded.replaceFirst(input, rewrite));
                assertThat(re2.extract(input, rewrite)).as(description).isEqualTo(re2Unbounded.extract(input, rewrite));
                assertThat(trino.extractAll(input, 1)).as(description).isEqualTo(trinoUnbounded.extractAll(input, 1));
                assertThat(trino.replace(input, utf8Slice("[$1]"))).as(description).isEqualTo(trinoUnbounded.replace(input, utf8Slice("[$1]")));
            }
        }
    }

    @Test
    public void testFindAfterFailedFindRereadsInput()
    {
        // A find after a failed one reads the input again, as the engine does, so a match written
        // into the input between them, without a reset, is found.
        int offset = 10 * FILLER_LINE.length();
        for (Map.Entry<String, CompiledPattern> entry : compile(BOUNDED_STRINGS).entrySet()) {
            String language = entry.getKey();
            Slice input = utf8Slice(LONG_FILLER);
            RegexpMatcher matcher = entry.getValue().matcher(input);
            assertThat(matcher.find()).as(language).isFalse();
            input.setBytes(offset, utf8Slice(BOUNDED_STRINGS_MATCH));
            assertThat(spans(matcher)).as(language).containsExactly(new Span(offset, offset + BOUNDED_STRINGS_MATCH.length()));
        }
    }

    @Test
    public void testUnicodeFoldsPassTheCheck()
    {
        // Each pattern keeps a fact, so the check runs, on a line that holds a non-ASCII case
        // fold of the pattern's letters, which each language matches: the Kelvin sign for k, and
        // in Trino ß for ss.
        Slice kelvin = utf8Slice("x \u212As");
        Map<String, CompiledPattern> patterns = Map.of(
                "RE2", Re2.compile(utf8Slice("x (?i:ks)")),
                "Java", JavaRegexp.compile(utf8Slice("x (?iu:ks)")),
                "Rust", RustRegexp.compile(utf8Slice("x (?i:ks)")),
                "Trino", TrinoRegexp.compile(utf8Slice("x (?i:ks)")).re2());
        for (Map.Entry<String, CompiledPattern> entry : patterns.entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(pattern.requiredBytesForDiagnostics()).as(language).hasToString("x ");
            assertThat(pattern.find(kelvin)).as(language).isTrue();
            assertThat(pattern.matcher(kelvin).find()).as(language).isTrue();
        }
        TrinoRegexp sharpS = TrinoRegexp.compile(utf8Slice("(?i)ss:x"));
        Slice line = utf8Slice("ß:x");
        assertThat(sharpS.re2().requiredBytesForDiagnostics()).hasToString("one of :");
        assertThat(sharpS.contains(line)).isTrue();
        assertThat(sharpS.re2().matcher(line).find()).isTrue();
    }

    @Test
    public void testFinalLineDollarKeepsRequiredBytes()
    {
        // Trino's final-line $ lowers the boolean program, which then holds the forward DFA budget
        // and the required bytes' charge.
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("foo\\d+$"));
        assertThat(regexp.re2().booleanProgramForDiagnostics()).isNotNull();
        assertThat(regexp.re2().requiredBytesForDiagnostics()).hasToString("foo");
        Slice without = utf8Slice("x fo12\n");
        assertThat(regexp.re2().shortWindowLacksRequiredBytes(without, 0, without.length())).isTrue();
        assertThat(regexp.contains(without)).isFalse();
        assertThat(regexp.re2().matcher(without).find()).isFalse();
        // A final LF changes neither the answer nor the match; a second one leaves no match.
        Slice input = utf8Slice("x foo12\n");
        assertThat(regexp.contains(input)).isTrue();
        assertThat(regexp.extract(input)).isEqualTo(utf8Slice("foo12"));
        RegexpMatcher matcher = regexp.re2().matcher(input);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(2);
        assertThat(matcher.end()).isEqualTo(7);
        assertThat(regexp.contains(utf8Slice("x foo12\n\n"))).isFalse();
    }

    @Test
    public void testFallsBackWithoutMemoryForRequiredBytes()
    {
        // At the smallest budget that keeps the required bytes, they take all of the forward DFA
        // allowance; one byte less, the pattern compiles without them, with the same results.
        Map<String, Map<String, Boolean>> cases = Map.of(
                "# noqa\\d+", Map.of("x = 1  # noqa12", true, "# noqa1", true, "x = 1  # noq12", false, "x = 1  # NOQA1", false),
                // A one-of fact.
                "[a-z]+@[a-z]+", Map.of("mail me at a@b", true, "a@b", true, "nothing to see here", false, "@ alone", false));
        for (Map.Entry<String, Map<String, Boolean>> testCase : cases.entrySet()) {
            String pattern = testCase.getKey();
            long budget = smallestBudgetKeepingRequiredBytes(pattern);
            Re2 kept = compileWithMaxMemory(pattern, budget);
            Re2 fallback = compileWithMaxMemory(pattern, budget - 1);
            assertThat(kept.requiredBytesForDiagnostics()).as(pattern).isNotNull();
            assertThat(kept.forwardProgramForDiagnostics().dfaMemory()).as(pattern).isZero();
            assertThat(fallback.requiredBytesForDiagnostics()).as(pattern).isNull();
            assertThat(fallback.forwardProgramForDiagnostics().dfaMemory()).as(pattern).isEqualTo(kept.requiredBytesRetainedSizeForDiagnostics() - 1);
            for (Map.Entry<String, Boolean> lineCase : testCase.getValue().entrySet()) {
                String line = lineCase.getKey();
                Slice input = utf8Slice(line);
                for (Re2 regexp : List.of(kept, fallback)) {
                    assertThat(regexp.find(input)).as("%s on %s", pattern, line).isEqualTo(lineCase.getValue());
                    assertThat(regexp.matcher(input).find()).as("%s matcher on %s", pattern, line).isEqualTo(lineCase.getValue());
                }
            }
        }
    }

    @Test
    public void testRequiredBytesKeepForwardDfaRoute()
    {
        // At the smallest budget whose forward DFA allowance holds what a forward DFA needs with
        // its start-byte tables, which (abc|def) fills with a literal filter where vectors are
        // available, the required bytes' charge would leave that DFA unable to initialize, so
        // they give way. One byte less, the DFA cannot initialize anyway and the pattern keeps
        // them. The public search that builds that DFA still takes it: a matcher's find builds
        // the first-match DFA, and a boolean find the longest-match DFA.
        for (ForwardDfa forwardDfa : List.of(
                new ForwardDfa("(ab|cd)", Dfa.DfaInstance.Kind.FIRST_MATCH),
                new ForwardDfa("(ab|cd)", Dfa.DfaInstance.Kind.LONGEST_MATCH),
                new ForwardDfa("(abc|def)", Dfa.DfaInstance.Kind.FIRST_MATCH),
                new ForwardDfa("(abc|def)", Dfa.DfaInstance.Kind.LONGEST_MATCH))) {
            String description = forwardDfa.toString();
            long initializationMemory = Dfa.DfaInstance.initializationMemory(Re2.compile(utf8Slice(forwardDfa.expression())).forwardProgramForDiagnostics(), forwardDfa.kind());
            long maxMemory = smallestBudgetHolding(forwardDfa.expression(), initializationMemory);
            Re2 pattern = compileWithMaxMemory(forwardDfa.expression(), maxMemory);
            Prog program = pattern.forwardProgramForDiagnostics();
            assertThat(pattern.requiredBytesForDiagnostics()).as(description).isNull();
            assertThat(Dfa.DfaInstance.initializationMemory(program, forwardDfa.kind())).as(description).isEqualTo(program.dfaMemory() / 2);
            assertThat(compileWithMaxMemory(forwardDfa.expression(), maxMemory - 1).requiredBytesForDiagnostics()).as(description).isNotNull();

            Slice input = utf8Slice("xx cd def yy");
            boolean found = forwardDfa.kind() == Dfa.DfaInstance.Kind.FIRST_MATCH ? pattern.matcher(input).find() : pattern.find(input);
            assertThat(found).as(description).isTrue();
            assertThat(program.cachedDfaIfPresent(forwardDfa.kind())).as(description).isNotNull();
        }
    }

    @Test
    public void testKeepsShortWindowCheckAloneWithoutMemoryForLongWindowSearch()
    {
        // From the smallest budget at which the first-match DFA initializes beside the required
        // bytes' charge, that charge would cost the DFA, so none is kept. Further up, the
        // short-window check's charge fits before the long-window literal's larger one, and the
        // pattern keeps the check alone.
        String expression = "(?s).*,z.*q";
        long compiledInitializes = smallestBudget(expression, TestRequiredBytes::firstMatchDfaInitializes);
        long compiledBudget = firstBudgetKeepingRequiredBytes(compiledInitializes, maxMemory -> compileWithMaxMemory(expression, maxMemory));
        Re2 compiled = compileWithMaxMemory(expression, compiledBudget);
        assertThat(compiled.requiredBytesForDiagnostics()).hasToString(",z");
        assertThat(compiled.requiredBytesForDiagnostics().searchesLongWindows()).isFalse();
        assertThat(compiled.find(utf8Slice("x,z q"))).isTrue();
        assertThat(compiled.find(utf8Slice("x, zq"))).isFalse();

        // The same holds after compilation, where Trino's ordered-literal route is charged with
        // the required bytes' charge handed back, and they are judged again beside it, although
        // compiled without the route the pattern takes the long-window literal at that budget.
        Slice pattern = utf8Slice(expression);
        LongFunction<CompiledPattern> compileTrino = maxMemory -> compileTrinoWithMaxMemory(pattern, maxMemory).re2();
        long initializes = smallestBudget(maxMemory -> firstMatchDfaInitializes(compileTrino.apply(maxMemory)));
        long budget = firstBudgetKeepingRequiredBytes(initializes, compileTrino);
        TrinoRegexp regexp = compileTrinoWithMaxMemory(pattern, budget);
        assertThat(regexp.usesOrderedLiteralMatcherForDiagnostics()).isTrue();
        RequiredBytes kept = regexp.re2().requiredBytesForDiagnostics();
        assertThat(kept).hasToString(",z");
        assertThat(kept.searchesLongWindows()).isFalse();
        ParseResult parsed = TrinoRegexpParser.parse(pattern, Regexp.LIKE_PERL);
        TrinoCompiledPattern withoutRoute = TrinoCompiledPattern.create(CompiledPattern.analyzeParsedForTrino(pattern, parsed, Regexp.LIKE_PERL, budget, ByteFrequencies.uniform(), CompiledPattern.ScanPlanMode.DEFAULT));
        assertThat(withoutRoute.requiredBytesForDiagnostics().searchesLongWindows()).isTrue();
        assertThat(regexp.contains(utf8Slice("x,z q"))).isTrue();
        assertThat(regexp.contains(utf8Slice("x, zq"))).isFalse();
    }

    private static String requiredSuffix(String pattern)
    {
        Regexp parsed = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();
        byte[] suffix = RequiredBytes.requiredSuffix(Simplifier.simplify(parsed, CaseFolding.RE2));
        return suffix == null ? null : new String(suffix, UTF_8);
    }

    private static RequiredBytes requiredBytesAt(String pattern, long maxMemory)
    {
        return compileWithMaxMemory(pattern, maxMemory).requiredBytesForDiagnostics();
    }

    // The first budget from `from` that `accepts`.
    private static long firstBudget(long from, LongPredicate accepts)
    {
        long budget = from;
        while (!accepts.test(budget)) {
            assertThat(budget).as("accepts no budget up to %s", budget).isLessThan(from + BUDGET_SEARCH_LIMIT);
            budget++;
        }
        return budget;
    }

    private static int firstMatchStates(CompiledPattern pattern)
    {
        return pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH).cacheEntryCount();
    }

    /// The results of every search of `input`: a matcher's find loop with its groups, over the
    /// whole input, after `find(int)` from a few starts, and over a few regions, then the count,
    /// and the one-shot searches of the whole input and of those regions, with groups.
    private static List<Object> results(CompiledPattern pattern, Slice input)
    {
        int length = input.length();
        List<Object> results = new ArrayList<>();
        RegexpMatcher matcher = pattern.matcher(input);
        results.add(matches(matcher));
        for (int start : new int[] {1, length / 3, length - 300}) {
            if (start >= 0 && start <= length) {
                results.add(matcher.find(start) ? List.of(start, groups(matcher), matches(matcher)) : List.of(start));
            }
        }
        results.add(pattern.count(input));
        results.add(pattern.find(input));
        for (int[] region : new int[][] {{0, length}, {1, length}, {0, length - 1}, {length / 4, length - length / 4}, {length / 2, length}, {5, Math.min(length, 300)}}) {
            int start = region[0];
            int end = Math.max(start, region[1]);
            matcher.reset(input, start, end);
            results.add(matches(matcher));
            results.add(pattern.find(input, start, end));
            int[] groups = new int[2 * (pattern.capturingGroupCount() + 1)];
            results.add(pattern.findInto(input, start, end, groups) ? Arrays.toString(groups) : "none");
        }
        return results;
    }

    private static List<String> matches(RegexpMatcher matcher)
    {
        List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(groups(matcher));
        }
        return matches;
    }

    private static String groups(RegexpMatcher matcher)
    {
        StringBuilder groups = new StringBuilder();
        for (int group = 0; group <= matcher.groupCount(); group++) {
            groups.append('[').append(matcher.start(group)).append(',').append(matcher.end(group)).append(')');
        }
        return groups.toString();
    }

    private static String requiredBytes(String pattern)
    {
        RequiredBytes bytes = Re2.compile(utf8Slice(pattern)).requiredBytesForDiagnostics();
        return bytes == null ? null : bytes.toString();
    }

    private static Map<String, CompiledPattern> compile(String pattern)
    {
        Slice slice = utf8Slice(pattern);
        return Map.of(
                "RE2", Re2.compile(slice),
                "Java", JavaRegexp.compile(slice),
                "Trino", TrinoRegexp.compile(slice).re2(),
                "Rust", RustRegexp.compile(slice),
                "Rust bytes", RustRegexp.compile(slice, RustRegexp.Options.defaults().setBytes(true)));
    }

    private static Re2 compileWithMaxMemory(String pattern, long maxMemory)
    {
        return Re2.compile(utf8Slice(pattern), Re2.Options.defaults().setMaxMemory(maxMemory));
    }

    private static TrinoRegexp compileTrinoWithMaxMemory(Slice pattern, long maxMemory)
    {
        return TrinoRegexp.compile(pattern, TrinoRegexp.Options.defaults().setMaxMemory(maxMemory));
    }

    // Finds the smallest budget that compiles, as TestTrinoRegexpFunctions does, and then the
    // first budget from there that keeps the required bytes.
    private static long smallestBudgetKeepingRequiredBytes(String pattern)
    {
        return firstBudgetKeepingRequiredBytes(smallestBudget(pattern, _ -> true), maxMemory -> compileWithMaxMemory(pattern, maxMemory));
    }

    // The first budget past `from`, which keeps no required bytes, at which `compile` keeps them.
    private static long firstBudgetKeepingRequiredBytes(long from, LongFunction<? extends CompiledPattern> compile)
    {
        long budget = from;
        while (compile.apply(budget).requiredBytesForDiagnostics() == null) {
            assertThat(budget).as("keeps no required bytes up to %s", budget).isLessThan(from + BUDGET_SEARCH_LIMIT);
            budget++;
        }
        assertThat(budget).isGreaterThan(from);
        return budget;
    }

    // Whether the first-match DFA initializes in its half of the forward DFA allowance before the
    // required bytes' charge.
    private static boolean firstMatchDfaInitializes(CompiledPattern pattern)
    {
        Prog program = pattern.forwardProgramForDiagnostics();
        long allowance = program.dfaMemory() + pattern.requiredBytesRetainedSizeForDiagnostics();
        return Dfa.DfaInstance.initializes(program, Dfa.DfaInstance.Kind.FIRST_MATCH, allowance / 2);
    }

    // The smallest budget whose forward DFA allowance, before the required bytes' charge, gives
    // each half at least `dfaMemory`.
    private static long smallestBudgetHolding(String pattern, long dfaMemory)
    {
        return smallestBudget(pattern, regexp -> (regexp.forwardProgramForDiagnostics().dfaMemory() + regexp.requiredBytesRetainedSizeForDiagnostics()) / 2 >= dfaMemory);
    }

    // The smallest budget at which the pattern compiles and passes `accepts`, which holds at
    // every larger budget.
    private static long smallestBudget(String pattern, Predicate<Re2> accepts)
    {
        return smallestBudget(maxMemory -> accepts.test(compileWithMaxMemory(pattern, maxMemory)));
    }

    // The smallest budget that `accepts` without a compile memory failure, which it does at every
    // larger budget.
    private static long smallestBudget(LongPredicate accepts)
    {
        long rejected = 512;
        long accepted = 1 << 20;
        while (accepted - rejected > 1) {
            long maxMemory = (rejected + accepted) >>> 1;
            boolean passes;
            try {
                passes = accepts.test(maxMemory);
            }
            catch (RegexpCompileMemoryLimitException ignored) {
                passes = false;
            }
            if (passes) {
                accepted = maxMemory;
            }
            else {
                rejected = maxMemory;
            }
        }
        return accepted;
    }

    private static Slice line(String spelling, int length, int offset)
    {
        return utf8Slice("x".repeat(offset) + spelling + "x".repeat(length - offset - spelling.length()));
    }

    // Checks the bytes against `line`, which holds `spelling` at `offset`, and against windows of
    // it that cut the spelling's last byte or start past its first.
    private static void assertOccursAt(RequiredBytes bytes, Slice line, String spelling, int offset, boolean expected)
    {
        String text = line.toStringUtf8();
        assertThat(bytes.occursIn(line, 0, line.length())).as(text).isEqualTo(expected);
        assertThat(bytes.occursIn(line, 0, offset + spelling.length() - 1)).as(text + " cut").isFalse();
        assertThat(bytes.occursIn(line, offset + 1, line.length())).as(text + " late").isFalse();
    }

    private static void assertNoForwardDfa(CompiledPattern pattern, String description)
    {
        Prog program = pattern.forwardProgramForDiagnostics();
        for (Dfa.DfaInstance.Kind kind : List.of(Dfa.DfaInstance.Kind.FIRST_MATCH, Dfa.DfaInstance.Kind.LONGEST_MATCH)) {
            assertThat(program.cachedDfaIfPresent(kind)).as("%s %s", description, kind).isNull();
        }
    }

    private static List<Span> spans(RegexpMatcher matcher)
    {
        List<Span> spans = new ArrayList<>();
        while (matcher.find()) {
            spans.add(new Span(matcher.start(), matcher.end()));
        }
        return spans;
    }

    private record ForwardDfa(String expression, Dfa.DfaInstance.Kind kind) {}

    // A pattern whose one-of set of two or three distinct bytes sits beside an inner literal
    // search, its bytes, and the search's minimum.
    private record OneOfBesideSearch(String pattern, String bytes, int searchMinimum) {}

    // A pattern that takes another route than the long-window check, which `route` tells, and
    // the text of one of its matches.
    private record OtherRoute(String expression, String match, Predicate<CompiledPattern> route) {}

    // A matcher region of `text` and the spans it finds.
    private record RegionCase(String text, int start, int end, List<Span> spans) {}

    // A pattern whose matches end with `suffix`, and one of its matches.
    private record SuffixCase(String expression, String match, String suffix) {}

    private record Span(int start, int end) {}
}
