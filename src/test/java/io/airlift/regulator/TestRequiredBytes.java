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

import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    // The quick form of the aws-keys pattern, whose prefix scan looks for its one-byte prefix A.
    private static final String AWS_KEY_QUICK = "(?:ASIA|AKIA|AROA|AIDA)[A-Z0-7]{16}";
    // The most budgets past the smallest that compiles to try for one that keeps the required bytes.
    private static final long BUDGET_SEARCH_LIMIT = 4096;

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
        // A pattern with no literal and no byte in every match keeps none.
        assertThat(requiredBytes("[^,\\s](\\s*)(?:[-+*/|!<=>%&^]+|:=)(\\s*)")).isNull();
        // Without a literal, each alternative gives its rarest byte, up to three bytes.
        assertThat(requiredBytes("a|bc")).isEqualTo("one of ab");
        assertThat(requiredBytes(URI_OR_EMAIL)).isEqualTo("one of /@");
        assertThat(requiredBytes("ab|cd|ef")).isEqualTo("one of bcf");
        assertThat(requiredBytes("ab|cd|ef|gh")).isNull();
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
        // required prefix, keeps none: no window the check reads can hold a match.
        assertThat(requiredBytes("abé{126}\\d")).isEqualTo("ab");
        assertThat(requiredBytes("abé{127}")).isNull();
        assertThat(requiredBytes("^" + "a".repeat(252) + "\\d+bc")).isEqualTo("bc");
        assertThat(requiredBytes("^" + "a".repeat(253) + "\\d+bc")).isNull();
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
        Map<String, Boolean> literalSpellings = Map.of(NOQA_LITERAL, true, "# NoQA", true, "#  noqa", false, "# noq@", false);
        Map<String, Boolean> oneOfSpellings = Map.of("/", true, "@", true, ":", false);
        for (int length = 0; length < 40; length++) {
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
                // Keeps its one-of bytes only once its inner literal search gives way at that budget.
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

    // Finds the smallest budget that compiles, as TestTrinoRegexpFunctions does, and then the
    // first budget from there that keeps the required bytes.
    private static long smallestBudgetKeepingRequiredBytes(String pattern)
    {
        long compiled = smallestBudget(pattern, _ -> true);
        long budget = compiled;
        while (compileWithMaxMemory(pattern, budget).requiredBytesForDiagnostics() == null) {
            assertThat(budget).as("%s keeps no required bytes up to %s", pattern, budget).isLessThan(compiled + BUDGET_SEARCH_LIMIT);
            budget++;
        }
        assertThat(budget).as(pattern).isGreaterThan(compiled);
        return budget;
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
        long rejected = 512;
        long accepted = 1 << 20;
        while (accepted - rejected > 1) {
            long maxMemory = (rejected + accepted) >>> 1;
            boolean passes;
            try {
                passes = accepts.test(compileWithMaxMemory(pattern, maxMemory));
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

    private static void assertNoForwardDfa(Re2 pattern, String description)
    {
        Prog program = pattern.forwardProgramForDiagnostics();
        for (Dfa.DfaInstance.Kind kind : List.of(Dfa.DfaInstance.Kind.FIRST_MATCH, Dfa.DfaInstance.Kind.LONGEST_MATCH)) {
            assertThat(program.cachedDfaIfPresent(kind)).as("%s %s", description, kind).isNull();
        }
    }

    private record ForwardDfa(String expression, Dfa.DfaInstance.Kind kind) {}
}
