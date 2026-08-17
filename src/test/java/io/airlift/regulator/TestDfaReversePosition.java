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

import static org.assertj.core.api.Assertions.assertThat;

// Project-authored coverage for reverse-DFA match boundaries and anchor inversion.
public class TestDfaReversePosition
{
    @Test
    public void testMatchPositions()
    {
        assertMatch("ab", "zzabyy", 2, 4);
        assertMatch("\\d", "abc1xyz", 3, 4);
        assertMatch("\\d+", "abc123xyz", 3, 6);
        assertMatch("\\w+", "  hello  ", 2, 7);
        assertMatch("\\bword\\b", "xx word yy", 3, 7);
        assertMatch("[a-z]+", "123abc456", 3, 6);
        assertMatch(".", "x", 0, 1);
        assertMatch("\\d\\d", "aa12bb", 2, 4);
    }

    @Test
    public void testUtf8BytePositions()
    {
        assertMatch("café", "xxcaféyy", 2, 7);
    }

    @Test
    public void testCompositePatterns()
    {
        assertMatch("(abc|def|ghi)", "xxdefyy", 2, 5);
        assertMatch("a{2,5}", "xxaaaaayy", 2, 7);
        assertMatch("((a+)b+)+", "xxaaabbbaabyy", 2, 11);
    }

    @Test
    public void testEmptyAndFullMatches()
    {
        assertMatch("a*", "xyz", 0, 0);
        assertMatch(".*", "hello", 0, 5);
    }

    @Test
    public void testAnchoredMatches()
    {
        assertMatch("^", "hello", 0, 0);
        assertMatch("$", "hello", 5, 5);
        assertMatch("^abc$", "abc", 0, 3);
    }

    @Test
    public void testReverseCompilationSwapsAnchors()
    {
        Programs startAnchored = compile("^");
        assertThat(startAnchored.forward().anchorStart()).isTrue();
        assertThat(startAnchored.forward().anchorEnd()).isFalse();
        assertThat(startAnchored.reverse().anchorStart()).isFalse();
        assertThat(startAnchored.reverse().anchorEnd()).isTrue();

        Programs endAnchored = compile("$");
        assertThat(endAnchored.forward().anchorStart()).isFalse();
        assertThat(endAnchored.forward().anchorEnd()).isTrue();
        assertThat(endAnchored.reverse().anchorStart()).isTrue();
        assertThat(endAnchored.reverse().anchorEnd()).isFalse();

        Programs fullyAnchored = compile("^abc$");
        assertThat(fullyAnchored.forward().anchorStart()).isTrue();
        assertThat(fullyAnchored.forward().anchorEnd()).isTrue();
        assertThat(fullyAnchored.reverse().anchorStart()).isTrue();
        assertThat(fullyAnchored.reverse().anchorEnd()).isTrue();
    }

    private static void assertMatch(String pattern, String text, int expectedStart, int expectedEnd)
    {
        Programs programs = compile(pattern);
        Slice input = Slices.utf8Slice(text);

        long forwardResult = Dfa.search(
                programs.forward(),
                input,
                false,
                Prog.MatchKind.FIRST_MATCH,
                true);
        assertThat(forwardResult)
                .as("forward result for pattern '%s' and text '%s'", pattern, text)
                .isEqualTo(expectedEnd);

        long reverseResult = Dfa.search(
                programs.reverse(),
                input,
                0,
                expectedEnd,
                true,
                Prog.MatchKind.LONGEST_MATCH,
                true);
        assertThat(reverseResult)
                .as("reverse result for pattern '%s' and text '%s'", pattern, text)
                .isEqualTo(expectedStart);
    }

    private static Programs compile(String pattern)
    {
        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice(pattern), Regexp.LIKE_PERL);
        Prog forward = Compiler.compile(parsed.regexp(), false, 0);
        Prog reverse = Compiler.compile(parsed.regexp(), true, 0);
        assertThat(forward).isNotNull();
        assertThat(reverse).isNotNull();
        return new Programs(forward, reverse);
    }

    private record Programs(Prog forward, Prog reverse) {}
}
