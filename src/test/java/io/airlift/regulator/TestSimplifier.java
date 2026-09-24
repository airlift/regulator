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

public class TestSimplifier
{
    @Test
    public void testFullClassUsesAnyCharWithoutChangingDotFlags()
    {
        for (int flags : List.of(Regexp.LIKE_PERL, Regexp.LIKE_PERL | Regexp.DOT_MATCHES_NEWLINE)) {
            Regexp parsed = RegexpParser.parse(utf8Slice("[\\s\\S]"), flags).regexp();
            assertThat(parsed.op()).isEqualTo(RegexpOp.CHAR_CLASS);
            Regexp simplified = Simplifier.simplify(parsed);
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

        Regexp simplified = Simplifier.simplify(re);
        assertThat(simplified.op()).isEqualTo(RegexpOp.LITERAL_STRING);
        assertThat(simplified.runes()).containsExactly('a', 'b');
    }

    @Test
    public void testConcatWithNoMatchIsNoMatch()
    {
        Regexp re = Regexp.concat(0, List.of(Regexp.literal(0, 'a'), Regexp.noMatch(0)));
        assertThat(Simplifier.simplify(re).op()).isEqualTo(RegexpOp.NO_MATCH);
    }

    @Test
    public void testAlternateDropsNoMatchAndFlattens()
    {
        Regexp re = Regexp.alternate(0, List.of(
                Regexp.noMatch(0),
                Regexp.alternate(0, List.of(Regexp.literal(0, 'a'), Regexp.literal(0, 'b')))));

        Regexp simplified = Simplifier.simplify(re);
        assertThat(simplified.op()).isEqualTo(RegexpOp.CHAR_CLASS);
        assertThat(simplified.charClass().contains('a')).isTrue();
        assertThat(simplified.charClass().contains('b')).isTrue();
    }

    @Test
    public void testStarNoMatchIsEmpty()
    {
        Regexp re = Regexp.star(0, Regexp.noMatch(0));
        assertThat(Simplifier.simplify(re).op()).isEqualTo(RegexpOp.EMPTY_MATCH);
    }

    @Test
    public void testCoalesceReturnsInputWithoutCoalescingPair()
    {
        for (String pattern : List.of("abc", "(a|b)*c", "[a-z]+-[0-9]+", "(?:x+y)+z", "a*b*", "a+(a)")) {
            Regexp parsed = parse(pattern);
            assertThat(Simplifier.Coalescer.coalesce(parsed)).as(pattern).isSameAs(parsed);
        }
        for (String pattern : List.of("a*a+", "x(?:a+a)y", "(b|c*c)", "[0-9]+[0-9]{2}")) {
            Regexp parsed = parse(pattern);
            assertThat(Simplifier.Coalescer.coalesce(parsed)).as(pattern).isNotSameAs(parsed);
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
            Regexp coalesced = Simplifier.Coalescer.coalesce(parsed);
            assertThat(coalesced).as("depth %s", depth).isNotSameAs(parsed);
            Regexp expected = parse("(".repeat(depth) + "a+" + ")".repeat(depth));
            assertThat(Simplifier.simplify(coalesced)).as("depth %s", depth).isEqualTo(Simplifier.simplify(expected));

            String unchanged = "(".repeat(depth) + "a*b+" + ")".repeat(depth);
            parsed = parse(unchanged);
            assertThat(Simplifier.Coalescer.coalesce(parsed)).as("depth %s", depth).isSameAs(parsed);
        }
    }

    private static Regexp parse(String pattern)
    {
        return RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();
    }
}
