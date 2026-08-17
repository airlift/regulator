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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

// Adapted from upstream RE2: re2/onepass.cc and re2/testing/search_test.cc.
public class TestUpstreamOnePassSearch
{
    private static Prog compile(String pattern, int flags)
    {
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(StandardCharsets.UTF_8)), flags);

        Prog prog = Compiler.compile(parsed.regexp(), false, 0);
        assertThat(prog).as("compile: %s", pattern).isNotNull();
        return prog;
    }

    private static Slice latin1(String s)
    {
        return Slices.wrappedBuffer(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    public void testAnchoredOnly()
    {
        Prog prog = compile("^([0-9]+)-([0-9]+)$", Regexp.LIKE_PERL | Regexp.LATIN1);
        assertThat(prog.isOnePass()).isTrue();

        Slice text = latin1("123-45");

        int[] submatch = new int[6];
        assertThat(OnePass.search(prog, text, true, Prog.MatchKind.FIRST_MATCH, submatch)).isTrue();
        assertThat(submatch).containsExactly(0, 6, 0, 3, 4, 6);

        int[] unanchored = new int[6];
        assertThat(OnePass.search(prog, text, false, Prog.MatchKind.FIRST_MATCH, unanchored)).isFalse();
        assertThat(unanchored).containsExactly(0, 0, 0, 0, 0, 0);
    }

    @Test
    public void testCaptureAgreementWithNfa()
    {
        Prog prog = compile("([a-z]+)([0-9]+)", Regexp.LIKE_PERL | Regexp.LATIN1);
        assertThat(prog.isOnePass()).isTrue();

        Slice text = latin1("abc123");

        int[] onepass = new int[6];
        assertThat(OnePass.search(prog, text, true, Prog.MatchKind.FIRST_MATCH, onepass)).isTrue();

        int[] nfa = new int[6];
        assertThat(Nfa.search(prog, text, true, Prog.MatchKind.FIRST_MATCH, nfa)).isTrue();

        assertThat(onepass).containsExactly(nfa);
    }

    @Test
    public void testMoreThanFiveCaptureGroups()
    {
        Prog prog = compile("^(a)(b)(c)(d)(e)(f)$", Regexp.LIKE_PERL | Regexp.LATIN1);
        assertThat(prog.isOnePass()).isTrue();

        Slice text = latin1("abcdef");
        int[] onePass = new int[14];
        assertThat(OnePass.search(prog, text, true, Prog.MatchKind.FIRST_MATCH, onePass)).isTrue();

        int[] nfa = new int[14];
        assertThat(Nfa.search(prog, text, true, Prog.MatchKind.FIRST_MATCH, nfa)).isTrue();
        assertThat(onePass).containsExactly(nfa);
    }

    @Test
    public void testRequestedCapturePrefix()
    {
        Prog prog = compile("^(a)(a)(a)(a)(a)(a)(a)(a)(a)(a)(a)(a)(a)(a)(a)(a)(a)(a)$", Regexp.LIKE_PERL | Regexp.LATIN1);
        Slice text = latin1("a".repeat(18));

        int[] onePass = new int[34];
        assertThat(OnePass.search(prog, text, true, Prog.MatchKind.FIRST_MATCH, onePass)).isTrue();

        int[] nfa = new int[34];
        assertThat(Nfa.search(prog, text, true, Prog.MatchKind.FIRST_MATCH, nfa)).isTrue();
        assertThat(onePass).containsExactly(nfa);
    }

    @Test
    public void testNonGreedyOptionalCapture()
    {
        int flags = (Regexp.LIKE_PERL & ~Regexp.ONE_LINE) | Regexp.NON_GREEDY;
        Prog prog = compile("(?:(b)(?:(b)?))", flags);
        assertThat(prog.isOnePass()).isTrue();

        Slice context = latin1("bbbbbcac");
        int[] onePass = new int[6];
        assertThat(OnePass.search(prog, context, 0, 7, true, Prog.MatchKind.FIRST_MATCH, onePass)).isTrue();
        assertThat(onePass).containsExactly(0, 1, 0, 1, -1, -1);

        int[] nfa = new int[6];
        assertThat(Nfa.search(prog, context, 0, 7, true, Prog.MatchKind.FIRST_MATCH, nfa)).isTrue();
        assertThat(nfa).containsExactly(onePass);
    }

    @Test
    public void testBooleanSearchAgreement()
    {
        assertBooleanSearchAgreement("a*", "", 0, 0, Prog.MatchKind.FIRST_MATCH);
        assertBooleanSearchAgreement("a+", "xxaaay", 2, 5, Prog.MatchKind.FIRST_MATCH);
        assertBooleanSearchAgreement("a+", "xxaaay", 2, 5, Prog.MatchKind.LONGEST_MATCH);
        assertBooleanSearchAgreement("a+", "xxaaay", 2, 5, Prog.MatchKind.FULL_MATCH);
        assertBooleanSearchAgreement("ab", "xxacyy", 2, 4, Prog.MatchKind.FIRST_MATCH);
        assertBooleanSearchAgreement("ab|ac", "xxacyy", 2, 4, Prog.MatchKind.FIRST_MATCH);
        assertBooleanSearchAgreement("a+?", "xxaaay", 2, 5, Prog.MatchKind.FIRST_MATCH);
    }

    @Test
    public void testScalarGroupZeroEndMatchesNative()
    {
        // Pinned RE2 reports [2, 3) for first-match a+?, [2, 5) for longest a+?,
        // an empty [2, 2) match for a*, and no match for ab in these regions.
        assertScalarGroupZeroEnd("a+?", Regexp.LIKE_PERL | Regexp.LATIN1, latin1("xxaaay"), 2, 5, Prog.MatchKind.FIRST_MATCH, 3);
        assertScalarGroupZeroEnd("a+?", Regexp.LIKE_PERL | Regexp.LATIN1, latin1("xxaaay"), 2, 5, Prog.MatchKind.LONGEST_MATCH, 5);
        assertScalarGroupZeroEnd("a+", Regexp.LIKE_PERL | Regexp.LATIN1, latin1("xxaaay"), 2, 5, Prog.MatchKind.FULL_MATCH, 5);
        assertScalarGroupZeroEnd("a*", Regexp.LIKE_PERL | Regexp.LATIN1, latin1("xx"), 2, 2, Prog.MatchKind.FIRST_MATCH, 2);
        assertScalarGroupZeroEnd("ab", Regexp.LIKE_PERL | Regexp.LATIN1, latin1("xxacyy"), 2, 4, Prog.MatchKind.FIRST_MATCH, -1);

        Slice utf8 = Slices.utf8Slice("xxééz");
        assertScalarGroupZeroEnd("é+", Regexp.LIKE_PERL, utf8, 2, 6, Prog.MatchKind.FIRST_MATCH, 6);
    }

    @Test
    public void testNotOnePass()
    {
        Prog prog = compile("^(.*) (.*)$", Regexp.LIKE_PERL | Regexp.LATIN1);
        assertThat(prog.isOnePass()).isFalse();

        Slice text = latin1("abc def");

        int[] onepass = new int[6];
        assertThat(OnePass.search(prog, text, true, Prog.MatchKind.FIRST_MATCH, onepass)).isFalse();

        int[] nfa = new int[6];
        assertThat(Nfa.search(prog, text, true, Prog.MatchKind.FIRST_MATCH, nfa)).isTrue();
        assertThat(nfa[0]).isEqualTo(0);
        assertThat(nfa[1]).isEqualTo(text.length());
    }

    private static void assertBooleanSearchAgreement(String pattern, String input, int start, int end, Prog.MatchKind matchKind)
    {
        Prog prog = compile(pattern, Regexp.LIKE_PERL | Regexp.LATIN1);
        assertThat(prog.isOnePass()).as("one-pass eligibility for %s", pattern).isTrue();
        Slice text = latin1(input);

        boolean booleanResult = OnePass.search(prog, text, start, end, true, matchKind, null);

        int[] onePassGroups = new int[2];
        boolean onePassCaptureResult = OnePass.search(prog, text, start, end, true, matchKind, onePassGroups);
        assertThat(booleanResult)
                .as("boolean and capture OnePass agreement for %s", pattern)
                .isEqualTo(onePassCaptureResult);

        int[] nfaGroups = new int[2];
        boolean nfaResult = Nfa.search(prog, text, start, end, true, matchKind, nfaGroups);
        assertThat(booleanResult)
                .as("boolean OnePass and NFA agreement for %s", pattern)
                .isEqualTo(nfaResult);
    }

    private static void assertScalarGroupZeroEnd(String pattern, int flags, Slice context, int start, int end, Prog.MatchKind matchKind, int expectedEnd)
    {
        Prog prog = compile(pattern, flags);
        assertThat(prog.isOnePass()).as("one-pass eligibility for %s", pattern).isTrue();

        int matchEnd = OnePass.searchGroupZeroEnd(prog, context, start, end, matchKind);
        assertThat(matchEnd).as("scalar group-zero end for %s", pattern).isEqualTo(expectedEnd);

        int[] specializedGroups = {Integer.MIN_VALUE, Integer.MIN_VALUE};
        boolean specializedMatched = OnePass.searchGroupZero(prog, context, start, end, matchKind, specializedGroups);
        assertThat(specializedMatched).as("specialized group-zero match for %s", pattern).isEqualTo(expectedEnd >= 0);
        assertThat(specializedGroups).as("specialized group-zero boundaries for %s", pattern)
                .containsExactly(expectedEnd >= 0 ? 0 : -1, expectedEnd >= 0 ? expectedEnd - context.byteArrayOffset() - start : -1);

        int[] nativeGroundedGroups = new int[2];
        boolean matched = Nfa.search(prog, context, start, end, true, matchKind, nativeGroundedGroups);
        assertThat(matchEnd >= 0).isEqualTo(matched);
        if (matched) {
            assertThat(matchEnd).isEqualTo(context.byteArrayOffset() + start + nativeGroundedGroups[1]);
        }
    }
}
