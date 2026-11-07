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

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestSimplifier
{
    @Test
    public void testFullClassUsesAnyCharWithoutChangingDotFlags()
    {
        for (int flags : List.of(Regexp.LIKE_PERL, Regexp.LIKE_PERL | Regexp.DOT_MATCHES_NEWLINE)) {
            Regexp parsed = RegexpParser.parse(utf8Slice("[\\s\\S]"), flags).regexp();
            assertThat(parsed.op()).isEqualTo(RegexpOp.CHAR_CLASS);
            Regexp simplified = Simplifier.simplify(parsed, CaseFolding.RE2);
            assertThat(simplified.op()).isEqualTo(RegexpOp.ANY_CHAR);
            assertThat(simplified.parseFlags()).isEqualTo(flags);
            Prog compiled = Compiler.compile(simplified);
            assertThat(Nfa.search(compiled, utf8Slice("\n"), 0, 1, true, Prog.MatchKind.FIRST_MATCH, new int[2])).isTrue();
        }
    }

    @Test
    public void testFlattenConcatAndRemoveEmpty()
    {
        Regexp re = Regexp.concat(0, List.of(
                Regexp.emptyMatch(0),
                Regexp.concat(0, List.of(Regexp.literal(0, 'a'), Regexp.literal(0, 'b'))),
                Regexp.emptyMatch(0)));

        Regexp simplified = Simplifier.simplify(re, CaseFolding.RE2);
        assertThat(simplified.op()).isEqualTo(RegexpOp.LITERAL_STRING);
        assertThat(simplified.runes()).containsExactly('a', 'b');
    }

    @Test
    public void testConcatWithNoMatchIsNoMatch()
    {
        Regexp re = Regexp.concat(0, List.of(Regexp.literal(0, 'a'), Regexp.noMatch(0)));
        assertThat(Simplifier.simplify(re, CaseFolding.RE2).op()).isEqualTo(RegexpOp.NO_MATCH);
    }

    @Test
    public void testAlternateDropsNoMatchAndFlattens()
    {
        Regexp re = Regexp.alternate(0, List.of(
                Regexp.noMatch(0),
                Regexp.alternate(0, List.of(Regexp.literal(0, 'a'), Regexp.literal(0, 'b')))));

        Regexp simplified = Simplifier.simplify(re, CaseFolding.RE2);
        assertThat(simplified.op()).isEqualTo(RegexpOp.CHAR_CLASS);
        assertThat(simplified.charClass().contains('a')).isTrue();
        assertThat(simplified.charClass().contains('b')).isTrue();
    }

    @Test
    public void testStarNoMatchIsEmpty()
    {
        Regexp re = Regexp.star(0, Regexp.noMatch(0));
        assertThat(Simplifier.simplify(re, CaseFolding.RE2).op()).isEqualTo(RegexpOp.EMPTY_MATCH);
    }

    @Test
    public void testCoalesceReturnsInputWithoutCoalescingPair()
    {
        for (String pattern : List.of("abc", "(a|b)*c", "[a-z]+-[0-9]+", "(?:x+y)+z", "a*b*", "a+(a)")) {
            Regexp parsed = parse(pattern);
            assertThat(Simplifier.Coalescer.coalesce(parsed, CaseFolding.RE2)).as(pattern).isSameAs(parsed);
        }
        for (String pattern : List.of("a*a+", "x(?:a+a)y", "(b|c*c)", "[0-9]+[0-9]{2}")) {
            Regexp parsed = parse(pattern);
            assertThat(Simplifier.Coalescer.coalesce(parsed, CaseFolding.RE2)).as(pattern).isNotSameAs(parsed);
        }
    }

    @Test
    public void testCoalesceFindsPairsBeyondScanDepthBound()
    {
        // Below the recursion bound the scan finds the pair itself; beyond it the walk does. Either
        // way an expression without a coalescing pair is returned unchanged.
        for (int depth : new int[] {200, 300, 2_000}) {
            String coalescing = "(".repeat(depth) + "a*a+" + ")".repeat(depth);
            Regexp parsed = parse(coalescing);
            Regexp coalesced = Simplifier.Coalescer.coalesce(parsed, CaseFolding.RE2);
            assertThat(coalesced).as("depth %s", depth).isNotSameAs(parsed);
            Regexp expected = parse("(".repeat(depth) + "a+" + ")".repeat(depth));
            assertThat(Simplifier.simplify(coalesced, CaseFolding.RE2)).as("depth %s", depth).isEqualTo(Simplifier.simplify(expected, CaseFolding.RE2));

            String unchanged = "(".repeat(depth) + "a*b+" + ")".repeat(depth);
            parsed = parse(unchanged);
            assertThat(Simplifier.Coalescer.coalesce(parsed, CaseFolding.RE2)).as("depth %s", depth).isSameAs(parsed);
        }
    }

    @Test
    public void testCoalescedRepeatCountsSaturate()
    {
        // Coalescing summed repeat counts in an int, so two repeats of a billion overflowed into
        // a negative count. The sum now saturates, and the budget rejects it before expansion.
        Regexp half = Regexp.repeat(Regexp.LIKE_PERL, Regexp.literal(Regexp.LIKE_PERL, 'a'), 1 << 30, 1 << 30);
        Regexp halves = Regexp.concat(Regexp.LIKE_PERL, List.of(half, half));
        Regexp coalesced = Simplifier.Coalescer.coalesce(halves, CaseFolding.RE2);
        assertThat(coalesced.op()).isEqualTo(RegexpOp.REPEAT);
        assertThat(coalesced.min()).isEqualTo(Integer.MAX_VALUE);
        assertThat(coalesced.max()).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> Simplifier.simplify(halves, CaseFolding.RE2, Re2.Options.DEFAULT_MAX_MEMORY))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    @Test
    public void testExpansionBudgetAcceptsWhatTheCompilerAccepts()
    {
        // The expansion count is a lower bound on what the compiler counts for the same budget, so
        // a repetition whose expansion compiles is never rejected for its size while it expands.
        List<String> patterns = List.of(
                "a{40}",
                "(?:abc){20,30}",
                "(?i:abc){25}",
                "(?:[a-c]x){10,}",
                "(?:(a)|b\\b){15}",
                "(?:x*){12}",
                "(?:a+){3,9}?",
                "(?:(?:ab){2,4}){6}",
                "^(?:\\w\\d?){1,20}$",
                "(?:é|Ā){18}",
                "(?:\\p{L}a){12}",
                "(?:ß|ss){10,20}",
                "(?:a{0,3}b){8}",
                // Bound by the visit limit: the never-matching branch is visited but emits nothing.
                "(?:x|([^\\s\\S])){100}",
                // The never-matching branch discards its copies of a, which count nothing.
                "(?:a{0,1000}(?:[^\\s\\S]|[^\\s\\S]){1})|z",
                // Every rune folded, and ß folded in full to ss.
                "(?i:straße){12}");
        for (String pattern : patterns) {
            assertExpansionFitsWhereCompileFits(pattern, Compiler.Dialect.RE2, RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL));
            assertExpansionFitsWhereCompileFits(pattern, Compiler.Dialect.RE2, RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL | Regexp.LATIN1));
            assertExpansionFitsWhereCompileFits(pattern, Compiler.Dialect.TRINO, TrinoRegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL | Regexp.FOLD_CASE));
        }
    }

    @Test
    public void testSimplifyReturnsUnchangedNodes()
    {
        for (String pattern : List.of("(a+)x*[b-d]", "(?:ab)?[0-9]+z", "(x[a-f]+)+")) {
            Regexp parsed = parse(pattern);
            assertThat(Simplifier.simplify(parsed, CaseFolding.RE2)).as(pattern).isSameAs(parsed);
        }
        Regexp repeated = parse("(a{2})b");
        assertThat(Simplifier.simplify(repeated, CaseFolding.RE2)).isNotSameAs(repeated);
    }

    private static Regexp parse(String pattern)
    {
        return RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();
    }

    private static void assertExpansionFitsWhereCompileFits(String pattern, Compiler.Dialect dialect, ParseResult parsed)
    {
        Regexp simplified = Simplifier.simplify(parsed.regexp(), parsed.caseFolding());
        // No program fits in the compiler's fixed 512-byte overhead.
        long rejected = 512;
        long compiled = 1 << 24;
        assertThat(compiles(simplified, compiled, dialect, parsed.caseFolding())).as(pattern).isTrue();
        while (compiled - rejected > 1) {
            long budget = (rejected + compiled) >>> 1;
            if (compiles(simplified, budget, dialect, parsed.caseFolding())) {
                compiled = budget;
            }
            else {
                rejected = budget;
            }
        }
        Simplifier.simplify(parsed.regexp(), parsed.caseFolding(), compiled);
    }

    private static boolean compiles(Regexp simplified, long maxMemory, Compiler.Dialect dialect, CaseFolding caseFolding)
    {
        try {
            Compiler.compileNormalized(simplified, false, maxMemory, dialect, caseFolding);
            return true;
        }
        catch (RegexpCompileMemoryLimitException ignored) {
            return false;
        }
    }
}
