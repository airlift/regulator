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

import io.airlift.slice.SizeOf;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.LITERAL_NEIGHBORS;
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
            new SuffixCase("([a-z]+)ing", "testing", "ing"),
            // Matches of at least the short-window limit, whose literal serves long windows only.
            new SuffixCase("(.*?,){300}z", ",".repeat(300) + "z", ",z"));
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
        // required prefix, keeps none unless the long-window check takes its exact literal: no
        // short window can hold such a match. A prefix scan, or the anchor of a required prefix,
        // leaves these without that check.
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
        // Trino's $ here. One beside another literal route, a prefix scan here, takes the check
        // without the bound.
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
        compile("x(.*?,){3}z").forEach((language, pattern) -> {
            assertThat(pattern.longWindowRequiredLiteral().longWindowLiteralForDiagnostics()).as(language).isEqualTo(",z");
            assertThat(pattern.longWindowRequiredLiteral().boundsSearchEnd()).as(language).isFalse();
        });

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

        // The long-window search for the one-of bytes finds the first of them at every offset of
        // windows under and over the vector scan minimum, past a byte just before the window's
        // start, and before a later one. The window lies in a Slice that starts past its array's
        // first byte, which holds one of the bytes, and ends before the Slice's last byte, which
        // holds another.
        RequiredBytes oneOfSearch = Re2.compile(utf8Slice(URI_OR_EMAIL)).longWindowRequiredLiteral();
        List<Integer> lengths = new ArrayList<>();
        for (int length = 1; length < 40; length++) {
            lengths.add(length);
        }
        lengths.add(Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES + 37);
        for (int length : lengths) {
            for (int offset = -1; offset < length; offset++) {
                char[] window = ("@" + "x".repeat(length)).toCharArray();
                if (offset >= 0) {
                    window[1 + offset] = '/';
                    if (offset + 2 < length) {
                        window[offset + 3] = '@';
                    }
                }
                byte[] padded = ("/" + new String(window) + "@").getBytes(UTF_8);
                Slice input = Slices.wrappedBuffer(padded, 1, padded.length - 1);
                assertThat(oneOfSearch.indexIn(input, 1, input.length() - 1)).as("%s at %s", length, offset).isEqualTo(offset < 0 ? -1 : 1 + offset);
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
        // email's `@` and Trino's `=`, runs at every length in place of the check on the ordinary
        // engine's route, which, for a pattern on the literal-neighbor plan, as the email is, runs
        // only when that matcher leaves the find undecided, and the pattern keeps no required
        // bytes.
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

        // A window without `@` is rejected at every short length, by the literal-neighbor matcher
        // in full-Slice find and by the search in a matcher's find, so neither find starts the
        // forward DFA.
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
            assertRejectsBeforeAnyEngine(BOUNDED_STRINGS, utf8Slice(window.text()), window.start(), window.end());
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
    public void testChecksLongWindowsForLongMatches()
    {
        // A pattern whose every match spans at least the short-window limit keeps its exact
        // literal for the long-window check alone: each short-window hook rejects a shorter
        // window on its length first. The 300 copies of the counted capture flatten as 13 do,
        // so every match ends with `,z`, which also bounds a long search's end.
        String expression = "(.*?,){300}z";
        String match = ",".repeat(300) + "z";
        Map<String, CompiledPattern> patterns = compile(expression);
        for (Map.Entry<String, CompiledPattern> entry : patterns.entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(pattern.minimumMatchLength()).as(language).isGreaterThanOrEqualTo(RequiredBytes.SHORT_WINDOW_LIMIT);
            RequiredBytes literal = pattern.requiredBytesForDiagnostics();
            assertThat(literal).as(language).hasToString(",z");
            assertThat(literal.isLongWindowsOnly()).as(language).isTrue();
            assertThat(literal.boundsSearchEnd()).as(language).isTrue();
            assertThat(pattern.longWindowRequiredLiteral()).as(language).isSameAs(literal);
        }

        // A long window without the literal is rejected before any engine runs, under and over
        // the vector scan minimum, by every hook.
        for (String filler : List.of(SHORT_FILLER, LONG_FILLER)) {
            Slice input = utf8Slice(filler + ",".repeat(300));
            for (Map.Entry<String, CompiledPattern> entry : compile(expression).entrySet()) {
                String language = entry.getKey() + " " + input.length();
                RegexpMatcher matcher = entry.getValue().matcher(input);
                assertThat(matcher.find()).as(language).isFalse();
                assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(language).isEqualTo(1);
                assertNoForwardDfa(entry.getValue(), language + " matcher");
            }
            for (Map.Entry<String, CompiledPattern> entry : compile(expression).entrySet()) {
                String language = entry.getKey() + " " + input.length();
                assertThat(entry.getValue().find(input)).as(language).isFalse();
                assertNoForwardDfa(entry.getValue(), language + " find");
            }
            for (Map.Entry<String, CompiledPattern> entry : compile(expression).entrySet()) {
                String language = entry.getKey() + " " + input.length();
                assertThat(entry.getValue().count(input)).as(language).isZero();
                assertNoForwardDfa(entry.getValue(), language + " count");
            }
            for (Map.Entry<String, CompiledPattern> entry : compile(expression).entrySet()) {
                String language = entry.getKey() + " " + input.length();
                CompiledPattern pattern = entry.getValue();
                int[] groups = new int[2 * (pattern.capturingGroupCount() + 1)];
                Arrays.fill(groups, 7);
                assertThat(pattern.findInto(input, 0, input.length(), groups)).as(language).isFalse();
                assertThat(groups).as(language).containsOnly(-1);
                assertNoForwardDfa(pattern, language + " range findInto");
            }
            // With the literal only at the window start, the search-end bound ends a range search
            // there, before the engine has room for a match. A count reads only up to that
            // occurrence, so its DFA builds the states that counting the literal alone builds.
            // That baseline is padded with newlines to the pattern's minimum length, since a
            // shorter count runs no engine, and the bound ends its count at `,z` before them.
            Slice early = utf8Slice(",z\n" + input.toStringUtf8());
            Map<String, CompiledPattern> countedAlone = compile(expression);
            for (Map.Entry<String, CompiledPattern> entry : compile(expression).entrySet()) {
                String language = entry.getKey() + " " + early.length();
                CompiledPattern pattern = entry.getValue();
                assertThat(pattern.find(early, 0, early.length())).as(language).isFalse();
                assertNoForwardDfa(pattern, language + " bounded range find");
                assertThat(pattern.count(early)).as(language).isZero();
                CompiledPattern alone = countedAlone.get(entry.getKey());
                assertThat(alone.count(utf8Slice(",z" + "\n".repeat(299)))).as(language).isZero();
                assertThat(firstMatchStates(pattern)).as(language + " bounded count").isEqualTo(firstMatchStates(alone));
            }
        }

        // A window that holds the literal runs the engine.
        Slice input = utf8Slice(LONG_FILLER + match + "\n" + LONG_FILLER_WITH_LITERAL);
        for (Map.Entry<String, CompiledPattern> entry : patterns.entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(spans(pattern.matcher(input))).as(language).containsExactly(new Span(LONG_FILLER.length(), LONG_FILLER.length() + match.length()));
            assertThat(pattern.find(input)).as(language).isTrue();
            assertThat(pattern.count(input)).as(language).isEqualTo(1);
        }

        // A short window, which cannot hold a match, is rejected on its length before any engine
        // runs, although it holds the literal.
        Slice shortInput = utf8Slice(",".repeat(RequiredBytes.SHORT_WINDOW_LIMIT - 2) + "z");
        for (Map.Entry<String, CompiledPattern> entry : compile(expression).entrySet()) {
            String language = entry.getKey();
            assertThat(entry.getValue().find(shortInput)).as(language).isFalse();
            assertNoForwardDfa(entry.getValue(), language + " short find");
        }
        for (Map.Entry<String, CompiledPattern> entry : compile(expression).entrySet()) {
            String language = entry.getKey();
            assertThat(entry.getValue().matcher(shortInput).find()).as(language).isFalse();
            assertNoForwardDfa(entry.getValue(), language + " short matcher");
        }

        // Beside another literal route, a prefix scan for x, such a literal is checked on long
        // windows too, but without the search-end bound.
        compile("x" + expression).forEach((language, pattern) -> {
            RequiredBytes literal = pattern.requiredBytesForDiagnostics();
            assertThat(pattern.forwardProgramForDiagnostics().canPrefixAccel()).as(language).isTrue();
            assertThat(literal.isLongWindowsOnly()).as(language).isTrue();
            assertThat(literal.longWindowLiteralForDiagnostics()).as(language).isEqualTo(",z");
            assertThat(literal.boundsSearchEnd()).as(language).isFalse();
        });
    }

    @Test
    public void testRejectsLongWindowsWithoutOneOfBytes()
    {
        assertThat(SHORT_FILLER.length()).isBetween(RequiredBytes.SHORT_WINDOW_LIMIT, Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES - 1);
        assertThat(LONG_FILLER.length()).isGreaterThanOrEqualTo(Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES);
        for (Map.Entry<String, CompiledPattern> entry : compile(URI_OR_EMAIL).entrySet()) {
            assertThat(entry.getValue().longWindowRequiredLiteral()).as(entry.getKey()).hasToString("one of /@");
        }

        // The one-of bytes of a pattern with no other literal route check long windows too, under
        // and over the vector scan minimum, before any engine runs: without the check, each
        // search would build and cache a forward DFA.
        for (String filler : List.of(SHORT_FILLER, LONG_FILLER)) {
            Slice input = utf8Slice(filler);
            assertRejectsBeforeAnyEngine(URI_OR_EMAIL, input, 0, input.length());

            // A window with one of the bytes runs the engine, which finds the matches around
            // them; a find loop scans once per match, and the last finds none.
            Slice matches = utf8Slice("mail a@b \n" + filler + "see http://x.y/z \n" + filler);
            for (Map.Entry<String, CompiledPattern> entry : compile(URI_OR_EMAIL).entrySet()) {
                String language = entry.getKey() + " " + filler.length();
                CompiledPattern pattern = entry.getValue();
                RegexpMatcher matcher = pattern.matcher(matches);
                assertThat(spans(matcher)).as(language).containsExactly(new Span(5, 8), new Span(14 + filler.length(), 26 + filler.length()));
                assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(language).isEqualTo(3);
                assertThat(pattern.find(matches)).as(language).isTrue();
                assertThat(pattern.count(matches)).as(language).isEqualTo(2);
            }
        }

        // A set of three distinct bytes searches a window over the vector scan minimum for each of
        // them: the third holds the only match.
        Slice thirdByte = utf8Slice(LONG_FILLER + " a#b ");
        Span thirdByteMatch = new Span(LONG_FILLER.length() + 1, LONG_FILLER.length() + 4);
        for (Map.Entry<String, CompiledPattern> entry : compile("[a-z]+://[^ ]+|[^ @]+@[^ @]+|[a-z]+#[a-z]+").entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(pattern.longWindowRequiredLiteral()).as(language).hasToString("one of /@#");
            assertThat(spans(pattern.matcher(thirdByte))).as(language).containsExactly(thirdByteMatch);
            assertThat(pattern.find(thirdByte)).as(language).isTrue();
            assertThat(pattern.count(thirdByte)).as(language).isEqualTo(1);
            int[] groups = new int[2 * (pattern.capturingGroupCount() + 1)];
            assertThat(pattern.findInto(thirdByte, 0, thirdByte.length(), groups)).as(language).isTrue();
            assertThat(new Span(groups[0], groups[1])).as(language).isEqualTo(thirdByteMatch);
        }

        // On a dense window, the scans stop after finding a byte near their start
        // NEAR_REQUIRED_LITERAL_SCAN_LIMIT times in a row, as for a literal.
        Slice dense = utf8Slice("a@b ".repeat(100));
        for (Map.Entry<String, CompiledPattern> entry : compile(URI_OR_EMAIL).entrySet()) {
            RegexpMatcher matcher = entry.getValue().matcher(dense);
            assertThat(spans(matcher)).as(entry.getKey()).hasSize(100);
            assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(entry.getKey()).isEqualTo(RegexpMatcher.NEAR_REQUIRED_LITERAL_SCAN_LIMIT);
        }

        // An inner literal search that only the class-run proof admits is no other route, as for
        // a literal: the pattern checks long windows for its one-of bytes as it would without it,
        // over the search's minimum and under it, where the search does not run.
        for (String filler : List.of(LONG_FILLER.substring(0, 512), LONG_FILLER)) {
            for (Map.Entry<String, CompiledPattern> entry : compile(".{0,2}(ab@|cd#)").entrySet()) {
                String language = entry.getKey() + " " + filler.length();
                CompiledPattern pattern = entry.getValue();
                assertThat(pattern.innerLiteralSearchForDiagnostics().addedByClassRunProof()).as(language).isTrue();
                assertThat(pattern.innerLiteralSearchForDiagnostics().minimumSearchBytes()).as(language).isBetween(513, LONG_FILLER.length());
                assertThat(pattern.longWindowRequiredLiteral()).as(language).hasToString("one of @#");
                RegexpMatcher matcher = pattern.matcher(utf8Slice(filler));
                assertThat(matcher.find()).as(language).isFalse();
                assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(language).isEqualTo(1);
                assertNoForwardDfa(pattern, language + " class run");
            }
        }

        // A start literal filter that only groups wider than one bit give is no other route, as
        // for the fixed-width matcher's yield, since the check was measured against filters of
        // one-bit groups. The a to g of [a-g]{3}://\S+|[a-g]{3}@[^ @]+ give its forward DFA such a
        // filter: the pattern checks long windows for its / or @ before the DFA scans for the
        // filter, and the DFA keeps the filter for a window that holds one.
        Slice wideFilterMatch = utf8Slice(LONG_FILLER + "see abc@def ");
        for (Map.Entry<String, CompiledPattern> entry : compile("[a-g]{3}://\\S+|[a-g]{3}@[^ @]+").entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            Prog program = pattern.forwardProgramForDiagnostics();
            assertThat(Dfa.DfaInstance.selectStartLiteralFilter(program, Dfa.DfaInstance.Kind.FIRST_MATCH, 1)).as(language).isNull();
            assertThat(pattern.longWindowRequiredLiteral()).as(language).hasToString("one of /@");
            RegexpMatcher matcher = pattern.matcher(utf8Slice(LONG_FILLER));
            assertThat(matcher.find()).as(language).isFalse();
            assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(language).isEqualTo(1);
            Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
            assertThat(dfa.startLiteralFilter() != null).as(language).isEqualTo(VectorSupport.isAvailable());
            assertThat(dfa.startLiteralFilterScanCount()).as(language).isZero();
            matcher.reset(wideFilterMatch);
            assertThat(spans(matcher)).as(language).containsExactly(new Span(LONG_FILLER.length() + 4, LONG_FILLER.length() + 11));
            assertThat(dfa.startLiteralFilterScanCount() > 0).as(language).isEqualTo(VectorSupport.isAvailable());
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

        // A pattern does not scan long windows for a literal when its other route already
        // searches for every exact literal it has: a prefix scan, a transparent start literal and
        // an inner literal search, or a scan plan. Nor does one whose only literal is
        // case-insensitive. Nor does a pattern whose inner literal search finds a literal inside
        // every match: that search already scans the window, and where nearly every line holds
        // the other literal near its end, a scan beside it would double the scan and never
        // reject.
        List<OtherRoute> otherRoutes = List.of(
                new OtherRoute("xyz.*?q", "xyzq", pattern -> pattern.forwardProgramForDiagnostics().canPrefixAccel()),
                new OtherRoute("\\bxyz\\b.*?q", "xyz q", pattern -> pattern.innerLiteralSearchForDiagnostics() != null),
                new OtherRoute("[0-9]([0-9]*)zz", "1zz", CompiledPattern::usesScanPlanForDiagnostics),
                new OtherRoute("(.*?,){3}# [Nn]oqa", ",,,# Noqa", pattern -> !pattern.requiredBytesForDiagnostics().isExactLiteral()),
                new OtherRoute("\\w+,\\bz", "ab,z", pattern -> pattern.innerLiteralSearchForDiagnostics() != null),
                // Neither does one whose class pair only the class-run proof admits, `[?!.]["']`
                // behind `\w+`, since without that proof the search is for the later `foobar`.
                new OtherRoute("\\w+[?!.][\"']foobar", "ab!'foobar", pattern -> pattern.innerLiteralSearchForDiagnostics() != null),
                new OtherRoute("(?:\\b|\\B)Holmes\\w*", "Holmes", pattern -> pattern.forwardProgramForDiagnostics().transparentStartLiteral() != null));
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

        // A folded run that stands in for no prefix is no other route: Trino's (?i)Holmes, whose
        // s also matches the long s, scans for its run and checks its literal with the search-end
        // bound. With 64-byte vectors (?i)Holmes has no run, and checks its literal beside the
        // two-literal start literal filter its rare start bytes take, without the bound. A run
        // that replaces a prefix is a route too: RE2's (?i)password scans for word in place of
        // pa, and checks `,#` beside it.
        CompiledPattern holmes = TrinoRegexp.compile(utf8Slice("(?i)Holmes[^\\n]{0,40}\\(\\)")).re2();
        boolean holmesRun = !VectorSupport.isAvailable() || VectorSupport.preferredByteLanes() < 64;
        assertThat(holmes.forwardProgramForDiagnostics().canPrefixAccel()).isEqualTo(holmesRun);
        assertThat(holmes.longWindowRequiredLiteral().longWindowLiteralForDiagnostics()).isEqualTo("()");
        assertThat(holmes.longWindowRequiredLiteral().boundsSearchEnd()).isEqualTo(holmesRun);
        RegexpMatcher holmesMatcher = holmes.matcher(utf8Slice(LONG_FILLER));
        assertThat(holmesMatcher.find()).isFalse();
        assertThat(holmesMatcher.requiredLiteralScanCountForDiagnostics()).isEqualTo(1);
        RequiredBytes password = Re2.compile(utf8Slice("(?i)password(?:.*?,){3}#")).longWindowRequiredLiteral();
        assertThat(password.longWindowLiteralForDiagnostics()).isEqualTo(",#");
        assertThat(password.boundsSearchEnd()).isFalse();

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

        // Nor is the literal-neighbor find plan, which answers full-Slice boolean find alone: the
        // matcher and count reject a long window that lacks the literal before any engine runs, as
        // without the plan.
        for (Map.Entry<String, CompiledPattern> entry : compile("[a-z]+aa[a-z]+").entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(pattern.findPlanForDiagnostics()).as(language).isEqualTo(LITERAL_NEIGHBORS);
            assertThat(pattern.longWindowRequiredLiteral()).as(language).isNotNull();
            assertThat(pattern.count(utf8Slice(LONG_FILLER))).as(language).isZero();
            assertNoForwardDfa(pattern, language + " [a-z]+aa[a-z]+ count");
            RegexpMatcher matcher = pattern.matcher(utf8Slice(LONG_FILLER));
            assertThat(matcher.find()).as(language).isFalse();
            assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(language).isEqualTo(1);
            matcher.reset(utf8Slice(LONG_FILLER + "baab"));
            assertThat(matcher.find()).as(language).isTrue();
        }
        // RE2's longest-match `[a-z]+::[0-9]+` has no inner literal search, so without the check
        // its count would build the DFA.
        Re2 longest = Re2.compile(utf8Slice("[a-z]+::[0-9]+"), Re2.Options.defaults().setLongestMatch(true));
        assertThat(longest.findPlanForDiagnostics()).isEqualTo(LITERAL_NEIGHBORS);
        assertThat(longest.innerLiteralSearchForDiagnostics()).isNull();
        assertThat(longest.longWindowRequiredLiteral()).isNotNull();
        assertThat(longest.count(utf8Slice("x".repeat(512)))).isZero();
        assertNoForwardDfa(longest, "Re2 longest-match [a-z]+::[0-9]+");
        assertThat(longest.count(utf8Slice("x".repeat(512) + " ab::12"))).isEqualTo(1);

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
    public void testHandsLongWindowLiteralToInnerLiteralSearch()
    {
        // Full-Slice find of a long window hands the required literal's first occurrence to the
        // inner literal search where that search is for the literal itself, behind a class run,
        // or for the literal less its last byte, which the gate after the search's literal admits
        // alone. That second handoff has its own scan: matcher, count, and ranged searches keep
        // the long windows unchecked, and a long window without `,z` is rejected before any DFA.
        // A search beside a long-window search for another literal, `,` or `the` beside `,z`, hands
        // nothing off, nor does a folded search, nor a search for `,` that `\B` leaves without a
        // gate after it. The required bytes' charge takes in that scan of its own, which shares the
        // literal's array, and the forward DFA budget gives up that charge; a handoff on the
        // long-window search costs nothing more.
        Map<String, Boolean> patterns = Map.of(
                "\\w+,\\bz", true,
                "(\\w+),(z)", true,
                "(\\w+)foobar(\\d)", true,
                "[a-zA-Z]+ing", true,
                BOUNDED_STRINGS, false,
                "\\bthe\\b.*?,z", false,
                "(?i)\\w+foobar\\d", false,
                "\\w+,\\Bz", false);
        for (Map.Entry<String, Boolean> entry : patterns.entrySet()) {
            for (Map.Entry<String, CompiledPattern> compiled : compile(entry.getKey()).entrySet()) {
                String description = compiled.getKey() + " " + entry.getKey();
                CompiledPattern pattern = compiled.getValue();
                RequiredBytes bytes = pattern.requiredBytesForDiagnostics();
                assertThat(bytes != null && bytes.handsOffToInnerLiteralSearch()).as(description).isEqualTo(entry.getValue());
                if (!entry.getValue()) {
                    continue;
                }
                long ownSearchSize = 0;
                if (bytes.toString().equals(",z")) {
                    assertThat(pattern.longWindowRequiredLiteral()).as(description).isNull();
                    assertThat(pattern.find(utf8Slice(LONG_FILLER))).as(description).isFalse();
                    assertNoForwardDfa(pattern, description);
                    byte[] literal = ",z".getBytes(UTF_8);
                    ownSearchSize = LiteralSearchKernel.sharing(Slices.wrappedBuffer(literal)).estimatedRetainedSize() - SizeOf.sizeOf(literal);
                }
                assertThat(pattern.requiredBytesRetainedSizeForDiagnostics()).as(description).isEqualTo(bytes.withoutInnerLiteralHandoff().estimatedRetainedSize() + ownSearchSize);
            }
        }
        for (String expression : List.of("\\w+,\\bz", "(\\w+)foobar(\\d)")) {
            Slice pattern = utf8Slice(expression);
            int flags = Re2.Options.defaults().parseFlags();
            ParseResult parsed = RegexpParser.parse(pattern, flags);
            long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
            Prog control = Compiler.compile(parsed.regexp(), false, maxMemory - maxMemory / 3);
            Re2 compiled = Re2.compileParsed(pattern, parsed, flags, maxMemory);
            assertThat(compiled.requiredBytesForDiagnostics().handsOffToInnerLiteralSearch()).as(expression).isTrue();
            assertThat(compiled.forwardProgramForDiagnostics().dfaMemory()).as(expression).isEqualTo(control.dfaMemory() - compiled.requiredBytesRetainedSizeForDiagnostics());
        }
    }

    @Test
    public void testBitStateCaptureRejectsWindowWithoutInnerLiteral()
    {
        // A pattern whose inner literal search finds a literal inside every match, and that the
        // class-run proof does not add, keeps no long-window check, so the routes that skip that
        // search scan for its literal instead. A
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
    public void testWindowLiteralSearchKeepsLongWindowCheck()
    {
        // A window literal search does not stand in for the long-window check, since the routes
        // that skip the window search never search for its literal. For a search range small
        // enough for BitState, a find with capture groups uses BitState and skips both DFAs and the
        // window literal search, and a range without xyz is rejected with one scan before BitState
        // starts.
        Slice letters = utf8Slice("a".repeat(8192));
        for (Map.Entry<String, CompiledPattern> entry : compile("((?:xy|z){0,2})xyz").entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(pattern.windowLiteralSearchForDiagnostics()).as(language).isNotNull();
            assertThat(pattern.longWindowRequiredLiteral()).as(language).hasToString("xyz");
            assertThat(pattern.canUseDirectBitStateCapture(pattern.forwardProgramForDiagnostics(), letters.length(), 2)).as(language).isTrue();
            RegexpMatcher matcher = pattern.matcher(letters);
            assertThat(matcher.find()).as(language).isFalse();
            assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(language).isEqualTo(1);
            assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).as(language).isFalse();
        }
        // A Unicode \B makes the DFA quit on a byte outside ASCII after xyz, and after
        // DFA_QUIT_STREAK_LIMIT finds in a row whose DFA quit, a matcher's finds skip the DFA and
        // the window search with it. The windows up to the last match are longer than BitState
        // holds and run the NFA, and the last, which holds no xyz, is rejected before BitState
        // would search it. Java's \B is Unicode only with (?U).
        String boundary = "(?:yz|x){0,1}xyz\\B";
        Map<String, CompiledPattern> boundaries = Map.of(
                "Java", JavaRegexp.compile(utf8Slice("(?U)" + boundary)),
                "Trino", TrinoRegexp.compile(utf8Slice(boundary)).re2(),
                "Rust", RustRegexp.compile(utf8Slice(boundary)));
        for (Map.Entry<String, CompiledPattern> entry : boundaries.entrySet()) {
            String language = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(pattern.forwardProgramForDiagnostics().dfaQuitsOnNonAscii()).as(language).isTrue();
            assertThat(pattern.windowLiteralSearchForDiagnostics()).as(language).isNotNull();
            assertThat(pattern.longWindowRequiredLiteral()).as(language).hasToString("xyz");
            String last = "\u00E9 ";
            int tail = pattern.forwardProgramForDiagnostics().unanchoredBitStateTextMaxSize() - last.getBytes(UTF_8).length;
            int finds = CompiledPattern.DFA_QUIT_STREAK_LIMIT + 2;
            Slice input = utf8Slice(("xyz" + last).repeat(finds) + "-".repeat(tail));
            RegexpMatcher matcher = pattern.matcher(input);
            assertThat(spans(matcher)).as(language).hasSize(finds);
            assertThat(matcher.dfaQuitStreak()).as(language).isGreaterThanOrEqualTo(CompiledPattern.DFA_QUIT_STREAK_LIMIT);
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
    public void testChecksLiteralBesideOtherRoute()
    {
        // A pattern with another literal route checks long windows for its rarest exact literal
        // that the route does not search for, which every match consumes too. The route searches
        // for a literal where a match starts, as a prefix scan, a transparent start literal, an
        // inner literal search for a literal or set that begins every match, or a start literal
        // filter does, or it is a scan plan. The short-window check keeps the required bytes.
        // Without the long-window check, each search of the filler, which holds `the` on every
        // line, would build and cache a forward DFA. A matcher whose candidate cursor builds the
        // DFA when it is created rejects the filler with one scan. The DFA builds a start literal
        // filter only with the Vector API.
        Predicate<CompiledPattern> prefixScan = pattern -> pattern.forwardProgramForDiagnostics().canPrefixAccel();
        List<BesideRoute> routes = new ArrayList<>(List.of(
                new BesideRoute("the.*?,z", "the,z", "the", ",z", prefixScan),
                new BesideRoute("x(.*?,){3}z", "x,,,z", ",z", ",z", prefixScan),
                new BesideRoute("Holmes.*?Watson", "Holmes Watson", "Holmes", "Watson", prefixScan),
                new BesideRoute("\\bthe\\b.*?,z", "the,z", "the", ",z", pattern -> pattern.forwardProgramForDiagnostics().transparentStartLiteral() != null),
                new BesideRoute("\\b(?:the|and)\\b.*?,z", "and,z", ",z", ",z", pattern -> pattern.innerLiteralSearchForDiagnostics().literalBeginsEveryMatch()),
                new BesideRoute("(?:\\b|\\B)Holmes.*?foobar", "Holmes foobar", "Holmes", "foobar", pattern -> pattern.forwardProgramForDiagnostics().transparentStartLiteral() != null),
                // A route literal rules out only a literal its exact bytes hold: `foobar` holds no
                // `FOO`, and the folded `bar` of `foo[Bb][Aa][Rr]` may be `BAR`.
                new BesideRoute("\\Bfoobar.*?FOO", "x", "foobarFOO", "foobar", "FOO", pattern -> pattern.innerLiteralSearchForDiagnostics().foldedMasks() == null),
                new BesideRoute("\\Bfoo[Bb][Aa][Rr].*?BAR", "x", "fooBarBAR", "foobar", "BAR", pattern -> pattern.innerLiteralSearchForDiagnostics().foldedMasks() != null),
                new BesideRoute("\\Bfoo[Bb][Aa][Rr].*?bar", "x", "fooBARbar", "foobar", "bar", pattern -> pattern.innerLiteralSearchForDiagnostics().foldedMasks() != null),
                new BesideRoute("\\d(\\d*)ab(\\d+)cde", "1ab2cde", "ab", "ab", CompiledPattern::usesScanPlanForDiagnostics)));
        if (VectorSupport.isAvailable()) {
            routes.add(new BesideRoute("(?:abc|def)(?:[a-q],){3}z", "abca,b,c,z", ",z", ",z", pattern -> Dfa.DfaInstance.searchesStartLiteralFilter(pattern.forwardProgramForDiagnostics(), Dfa.DfaInstance.Kind.FIRST_MATCH)));
            // A folded run that stands in for no prefix counts as the start literal filter the
            // program would have without it: (?i)kernel scans for ernel behind the Kelvin sign's
            // lead where its start bytes would carry a filter.
            routes.add(new BesideRoute("(?i)kernel.*?,#", "Kernel,#", ",#", ",#", pattern -> pattern.forwardProgramForDiagnostics().canPrefixAccel()));
        }
        Slice filler = utf8Slice(LONG_FILLER);
        for (BesideRoute route : routes) {
            for (Map.Entry<String, CompiledPattern> entry : compile(route.expression()).entrySet()) {
                String description = entry.getKey() + " " + route.expression();
                CompiledPattern pattern = entry.getValue();
                assertThat(route.route().test(pattern)).as(description).isTrue();
                assertThat(pattern.requiredBytesForDiagnostics()).as(description).hasToString(route.shortWindowBytes());
                assertThat(pattern.longWindowRequiredLiteral()).as(description).isNotNull();
                assertThat(pattern.longWindowRequiredLiteral().longWindowLiteralForDiagnostics()).as(description).isEqualTo(route.longWindowLiteral());
                RegexpMatcher matcher = pattern.matcher(filler);
                assertThat(matcher.find()).as(description).isFalse();
                assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(description).isEqualTo(1);
            }
            for (Map.Entry<String, CompiledPattern> entry : compile(route.expression()).entrySet()) {
                String description = entry.getKey() + " " + route.expression();
                assertThat(entry.getValue().find(filler)).as(description).isFalse();
                assertNoForwardDfa(entry.getValue(), description + " find");
            }
            for (Map.Entry<String, CompiledPattern> entry : compile(route.expression()).entrySet()) {
                String description = entry.getKey() + " " + route.expression();
                assertThat(entry.getValue().count(filler)).as(description).isZero();
                assertNoForwardDfa(entry.getValue(), description + " count");
            }
            for (Map.Entry<String, CompiledPattern> entry : compile(route.expression()).entrySet()) {
                String description = entry.getKey() + " " + route.expression();
                CompiledPattern pattern = entry.getValue();
                int[] groups = new int[2 * (pattern.capturingGroupCount() + 1)];
                assertThat(pattern.findInto(filler, 0, filler.length(), groups)).as(description).isFalse();
                assertNoForwardDfa(pattern, description + " findInto");
            }

            // A window that holds the literal runs the route and the engine.
            Slice input = utf8Slice(LONG_FILLER + route.before() + route.match());
            Span match = new Span(LONG_FILLER.length() + route.before().length(), input.length());
            for (Map.Entry<String, CompiledPattern> entry : compile(route.expression()).entrySet()) {
                String description = entry.getKey() + " " + route.expression();
                CompiledPattern pattern = entry.getValue();
                assertThat(spans(pattern.matcher(input))).as(description).containsExactly(match);
                assertThat(pattern.find(input)).as(description).isTrue();
                assertThat(pattern.count(input)).as(description).isEqualTo(1);
            }
        }
    }

    @Test
    @Timeout(30)
    public void testLiteralBesideRoutesStaysLinear()
    {
        // Compared at each of the 20,000 start bytes, these 1,000 runs of 64 bytes, all but the
        // last an `a`, would take about 1.3 * 10^9 byte comparisons. Compared with the first 64
        // alone, they stay well inside the comparison budget.
        String pattern = "a".repeat(20_000) + ("." + "a".repeat(63) + "b").repeat(1_000);
        assertThat(literalBesideRoutes(pattern)).isEqualTo("a".repeat(63) + "b");

        // These 30,000 runs pass the budget, so the pattern searches for no literal beside its
        // routes. Compared with all 300,000 start bytes, they would take about 6 * 10^11
        // comparisons. The comparisons allocate nothing, so only the timeout guards this case.
        String longPattern = "a".repeat(300_000) + ("." + "a".repeat(63) + "b").repeat(30_000);
        assertThat(literalBesideRoutes(longPattern)).isNull();
    }

    @Test
    public void testReplaceAllBacksOffOnDenseText()
    {
        // RE2's replaceAll runs the one-shot search once per match. For a pattern without the
        // search-end bound, its loop checks the text from each search's start to its end for the
        // literal, with a matcher's back-off. A text without the literal takes one scan and no
        // engine.
        String match = BOUNDED_STRINGS_MATCH;
        Slice rewrite = utf8Slice("x");
        String expression = BOUNDED_STRINGS + WITHOUT_SUFFIX;
        Re2 pattern = Re2.compile(utf8Slice(expression));
        Slice noMatch = utf8Slice(LONG_FILLER_WITH_LITERAL);
        assertThat(pattern.replaceAll(noMatch, rewrite)).isEqualTo(new Re2.ReplaceAllResult(0, noMatch));

        Slice noLiteral = utf8Slice(LONG_FILLER);
        Re2 rejecting = Re2.compile(utf8Slice(expression));
        assertThat(rejecting.replaceAll(noLiteral, rewrite)).isEqualTo(new Re2.ReplaceAllResult(0, noLiteral));
        assertNoForwardDfa(rejecting, "replaceAll");

        String sparse = (LONG_FILLER + match + "\n").repeat(3) + LONG_FILLER;
        assertThat(pattern.replaceAll(utf8Slice(sparse), rewrite)).isEqualTo(new Re2.ReplaceAllResult(3, utf8Slice(sparse.replace(match, "x"))));
        String dense = ("\n" + match).repeat(100);
        assertThat(pattern.replaceAll(utf8Slice(dense), rewrite)).isEqualTo(new Re2.ReplaceAllResult(100, utf8Slice("\nx".repeat(100))));

        // On the dense text each search starts at the end of a match, and its scan finds the
        // literal near its start. After NEAR_REQUIRED_LITERAL_SCAN_LIMIT such scans the loop scans
        // no more, so a rest without the literal is no longer rejected. One short of the limit,
        // it still is. A scan that finds the literal far from its start starts the count over.
        int limit = RegexpMatcher.NEAR_REQUIRED_LITERAL_SCAN_LIMIT;
        assertThat(pattern.replaceAllNearLiteralScansForDiagnostics(utf8Slice(dense), rewrite)).isEqualTo(limit);
        Slice pastLimit = utf8Slice(("\n" + match).repeat(limit) + LONG_FILLER);
        assertThat(pattern.replaceAll(pastLimit, rewrite).replacementCount()).isEqualTo(limit);
        assertThat(pattern.replaceAllNearLiteralScansForDiagnostics(pastLimit, rewrite)).isEqualTo(limit);
        Slice belowLimit = utf8Slice(("\n" + match).repeat(limit - 1) + LONG_FILLER);
        assertThat(pattern.replaceAll(belowLimit, rewrite).replacementCount()).isEqualTo(limit - 1);
        assertThat(pattern.replaceAllNearLiteralScansForDiagnostics(belowLimit, rewrite)).isEqualTo(-1);
        Slice restarted = utf8Slice(("\n" + match).repeat(limit - 1) + LONG_FILLER + match + ("\n" + match).repeat(limit - 1) + LONG_FILLER);
        assertThat(pattern.replaceAll(restarted, rewrite).replacementCount()).isEqualTo(2 * limit - 1);
        assertThat(pattern.replaceAllNearLiteralScansForDiagnostics(restarted, rewrite)).isEqualTo(-1);

        // A pattern with the bound makes no such scan: one bound of the whole text ends every
        // search, and a text without the bytes its matches end with runs no engine.
        Re2 bounded = Re2.compile(utf8Slice(BOUNDED_STRINGS));
        assertThat(bounded.requiredBytesForDiagnostics().boundsSearchEnd()).isTrue();
        assertThat(bounded.replaceAll(noLiteral, rewrite)).isEqualTo(new Re2.ReplaceAllResult(0, noLiteral));
        assertNoForwardDfa(bounded, "bounded replaceAll");
        assertThat(bounded.replaceAll(utf8Slice(dense), rewrite)).isEqualTo(new Re2.ReplaceAllResult(100, utf8Slice("\nx".repeat(100))));
        assertThat(bounded.replaceAllNearLiteralScansForDiagnostics(utf8Slice(dense), rewrite)).isZero();

        // Where those bytes do not hold the literal, the bound stands in for no check, so the loop
        // still scans: past the one match, the rest holds no literal and runs no search.
        Re2 apart = Re2.compile(utf8Slice("(.*?;){3}abcdefgh.*zz"));
        Slice apartText = utf8Slice("a;b;c;abcdefghzz\n" + "a;b;c;zz\n".repeat(60).stripTrailing());
        assertThat(apart.replaceAll(apartText, rewrite).replacementCount()).isEqualTo(1);
        assertThat(apart.replaceAllNearLiteralScansForDiagnostics(apartText, rewrite)).isEqualTo(-1);
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

        // A literal that serves long windows only has no short-window check to fall back to. At
        // the smallest budget at which the first-match DFA of a pattern whose matches span at
        // least the short-window limit initializes, the literal's charge would stop it from
        // initializing, so the pattern keeps no required bytes. One byte lower, the DFA cannot
        // initialize either way, and the pattern keeps the literal.
        String longMatches = "(?s)[ab]{256},z";
        long longMatchesInitializes = smallestBudget(longMatches, TestRequiredBytes::firstMatchDfaInitializes);
        assertThat(requiredBytesAt(longMatches, longMatchesInitializes - 1).isLongWindowsOnly()).isTrue();
        assertThat(requiredBytesAt(longMatches, longMatchesInitializes)).isNull();

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

    private static String literalBesideRoutes(String pattern)
    {
        Regexp parsed = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();
        byte[] literal = RequiredBytes.literalBesideRoutes(Simplifier.simplify(parsed, CaseFolding.RE2), List.of());
        return literal == null ? null : new String(literal, UTF_8);
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

    // Checks that every search of `[start, end)` of `input` for `pattern` finds no match before
    // any engine runs, each on a newly compiled pattern: a matcher's find, which scans once, a
    // range find, and a range findInto, which unsets the groups, and, where the window is the
    // whole input, find and count.
    private static void assertRejectsBeforeAnyEngine(String pattern, Slice input, int start, int end)
    {
        String description = "[%s, %s) of %s bytes".formatted(start, end, input.length());
        for (Map.Entry<String, CompiledPattern> entry : compile(pattern).entrySet()) {
            String language = entry.getKey() + " " + description;
            RegexpMatcher matcher = entry.getValue().matcher(input);
            matcher.reset(input, start, end);
            assertThat(matcher.find()).as(language).isFalse();
            assertThat(matcher.requiredLiteralScanCountForDiagnostics()).as(language).isEqualTo(1);
            assertNoForwardDfa(entry.getValue(), language + " matcher");
        }
        for (Map.Entry<String, CompiledPattern> entry : compile(pattern).entrySet()) {
            String language = entry.getKey() + " " + description;
            assertThat(entry.getValue().find(input, start, end)).as(language).isFalse();
            assertNoForwardDfa(entry.getValue(), language + " range find");
        }
        for (Map.Entry<String, CompiledPattern> entry : compile(pattern).entrySet()) {
            String language = entry.getKey() + " " + description;
            CompiledPattern compiled = entry.getValue();
            int[] groups = new int[2 * (compiled.capturingGroupCount() + 1)];
            Arrays.fill(groups, 7);
            assertThat(compiled.findInto(input, start, end, groups)).as(language).isFalse();
            assertThat(groups).as(language).containsOnly(-1);
            assertNoForwardDfa(compiled, language + " range findInto");
        }
        if (start == 0 && end == input.length()) {
            for (Map.Entry<String, CompiledPattern> entry : compile(pattern).entrySet()) {
                String language = entry.getKey() + " " + description;
                assertThat(entry.getValue().find(input)).as(language).isFalse();
                assertNoForwardDfa(entry.getValue(), language + " find");
            }
            for (Map.Entry<String, CompiledPattern> entry : compile(pattern).entrySet()) {
                String language = entry.getKey() + " " + description;
                assertThat(entry.getValue().count(input)).as(language).isZero();
                assertNoForwardDfa(entry.getValue(), language + " count");
            }
        }
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

    // A pattern whose `route` searches for a literal, with its short-window bytes and the literal
    // it checks long windows for beside the route; `before` precedes the match in a window, for a
    // match that cannot start after a newline.
    private record BesideRoute(String expression, String before, String match, String shortWindowBytes, String longWindowLiteral, Predicate<CompiledPattern> route)
    {
        BesideRoute(String expression, String match, String shortWindowBytes, String longWindowLiteral, Predicate<CompiledPattern> route)
        {
            this(expression, "", match, shortWindowBytes, longWindowLiteral, route);
        }
    }

    // A matcher region of `text` and the spans it finds.
    private record RegionCase(String text, int start, int end, List<Span> spans) {}

    // A pattern whose matches end with `suffix`, and one of its matches.
    private record SuffixCase(String expression, String match, String suffix) {}

    private record Span(int start, int end) {}
}
