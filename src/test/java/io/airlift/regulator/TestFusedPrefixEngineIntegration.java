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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

public class TestFusedPrefixEngineIntegration
{
    private static final String PREFIX = "Шерлок Холмс";
    private static final String PATTERN = PREFIX + "([0-9]+)";

    @Test
    public void testDfaResumesAfterRejectedCandidate()
    {
        SearchCase searchCase = createSearchCase();
        Prog program = compile(PATTERN);

        assertThat(program.canPrefixAccel()).isTrue();
        assertThat(program.prefixAccelStrategy(searchCase.text().length()))
                .isEqualTo(VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_VECTOR : Prog.PrefixAccelStrategy.FUSED_SWAR);
        assertThat(Dfa.search(program, searchCase.text(), false, Prog.MatchKind.FIRST_MATCH, true))
                .isEqualTo(searchCase.matchEnd());
        assertThat(Dfa.search(program, searchCase.text(), false, Prog.MatchKind.LONGEST_MATCH, true))
                .isEqualTo(searchCase.matchEnd());
    }

    @Test
    public void testNfaWithAndWithoutCaptures()
    {
        SearchCase searchCase = createSearchCase();
        Prog program = compile(PATTERN);

        assertThat(Nfa.search(program, searchCase.text(), false, Prog.MatchKind.FIRST_MATCH, null)).isTrue();

        int[] groups = new int[4];
        assertThat(Nfa.search(program, searchCase.text(), false, Prog.MatchKind.FIRST_MATCH, groups)).isTrue();
        assertThat(groups).containsExactly(
                searchCase.matchStart(),
                searchCase.matchEnd(),
                searchCase.captureStart(),
                searchCase.matchEnd());
    }

    @Test
    public void testBitStateWithCaptures()
    {
        SearchCase searchCase = createSearchCase();
        Prog program = compile(PATTERN);
        assertThat(program.canBitState()).isTrue();

        int[] groups = new int[4];
        assertThat(BitState.search(program, searchCase.text(), false, Prog.MatchKind.FIRST_MATCH, groups)).isTrue();
        assertThat(groups).containsExactly(
                searchCase.matchStart(),
                searchCase.matchEnd(),
                searchCase.captureStart(),
                searchCase.matchEnd());
    }

    @Test
    public void testDfaCountWithRejectedCandidatesAndBackingOffset()
    {
        Prog program = compile(PATTERN);
        String body = "x".repeat(1_100) + PREFIX + "x " + PREFIX + "12 " + PREFIX + "345";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] backing = new byte[bodyBytes.length + 11];
        System.arraycopy(bodyBytes, 0, backing, 7, bodyBytes.length);
        Slice text = Slices.wrappedBuffer(backing, 7, bodyBytes.length);

        assertThat(Dfa.countMatches(program, text, Prog.MatchKind.FIRST_MATCH)).isEqualTo(2);
        assertThat(Dfa.countMatches(program, text, Prog.MatchKind.LONGEST_MATCH)).isEqualTo(2);
    }

    @Test
    public void testPeriodicLiteralPairsAdjacentBytesInEveryLanguage()
    {
        // rebar's folly/literal-never-match-frequent literal. Every language plans it as an exact
        // literal on the prefix scan, which pairs its adjacent a and e. Each near miss holds four
        // candidates, the first failing at the literal's ninth byte, and the occurrence followed by
        // "aeae" overlaps two more.
        Slice literal = Slices.utf8Slice("aeaeaeaeae");
        Slice text = Slices.utf8Slice("aeaeaeaexe ".repeat(200) + "aeaeaeaeae" + " ae".repeat(50) + "aeaeaeaeaeaeae");
        for (Map.Entry<String, ? extends CompiledPattern> compiled : List.of(
                Map.entry("RE2", Re2.compile(literal)),
                Map.entry("Java", JavaRegexp.compile(literal)),
                Map.entry("Rust", RustRegexp.compile(literal)),
                Map.entry("Rust bytes", RustRegexp.compile(literal, RustRegexp.Options.bytes())),
                Map.entry("Trino", TrinoRegexp.compile(literal).re2()))) {
            CompiledPattern pattern = compiled.getValue();
            assertThat(pattern.forwardProgramForDiagnostics().fusedProbeOffsetsForDiagnostics()).as(compiled.getKey()).containsExactly(0, 1);
            assertThat(pattern.count(text)).as(compiled.getKey()).isEqualTo(2);
        }
    }

    @Test
    public void testLiteralSetScanEnginesAndCaptures()
    {
        // The prefix "fn " is followed by neither alternative before the match. The DFA, the Pike
        // VM, and BitState skip from it to the match, and the Pike VM and BitState capture the
        // alternative's group.
        String pattern = "fn is_(\\w+)|fn as_(\\w+)";
        Prog program = compile(pattern);
        String lead = "fn new() fx is_ fn ais_ ".repeat(50);
        Slice text = Slices.utf8Slice(lead + "fn as_x1 fn is_y");
        int matchStart = lead.length();
        assertThat(program.prefixAccelStrategy(text.length()))
                .isEqualTo(VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_LITERAL_SET : Prog.PrefixAccelStrategy.REPEATED_BYTE);

        assertThat(Dfa.search(program, text, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(matchStart + 8);
        assertThat(Dfa.search(program, text, false, Prog.MatchKind.LONGEST_MATCH, true)).isEqualTo(matchStart + 8);
        assertThat(Dfa.countMatches(program, text, Prog.MatchKind.FIRST_MATCH)).isEqualTo(2);
        int[] expectedGroups = {matchStart, matchStart + 8, -1, -1, matchStart + 6, matchStart + 8};
        int[] groups = new int[6];
        assertThat(Nfa.search(program, text, false, Prog.MatchKind.FIRST_MATCH, groups)).isTrue();
        assertThat(groups).containsExactly(expectedGroups);
        groups = new int[6];
        assertThat(BitState.search(program, text, false, Prog.MatchKind.FIRST_MATCH, groups)).isTrue();
        assertThat(groups).containsExactly(expectedGroups);
    }

    @Test
    public void testLiteralSetScanMatchesJdkInEveryLanguage()
    {
        // Text of the literals, their prefixes and pair-sharing decoys, and filler, in windows
        // inside a larger array. Each language's matcher finds the JDK's matches and groups, and
        // the DFA's count finds as many matches. The JDK is the oracle because an unanchored NFA
        // search over these windows calls the same literal set scan.
        List<String> fragments = List.of("fn is_", "fn as_", "fn is", "fn as", "fn ", "fx is_", "fn i_s_", "f", "_", "s_", "x", "1", " ", "\n");
        Random random = new Random(11);
        for (String pattern : List.of("fn is_(\\w+)|fn as_(\\w+)", "fn (is)e|fn (as)", "fn is_|fn as_|fn os_x\\d+")) {
            Prog program = compile(pattern);
            if (VectorSupport.isAvailable()) {
                assertThat(program.prefixAccelStrategy(1_024)).as(pattern).isEqualTo(Prog.PrefixAccelStrategy.FUSED_LITERAL_SET);
            }
            else {
                assertThat(program.prefixAccelStrategy(1_024)).as(pattern).isNotEqualTo(Prog.PrefixAccelStrategy.FUSED_LITERAL_SET);
            }
            Pattern oracle = Pattern.compile(pattern);
            List<CompiledPattern> languages = List.of(
                    Re2.compile(Slices.utf8Slice(pattern), Re2.Options.defaults()),
                    JavaRegexp.compile(Slices.utf8Slice(pattern)),
                    RustRegexp.compile(Slices.utf8Slice(pattern)),
                    TrinoRegexp.compile(Slices.utf8Slice(pattern)).re2());
            for (int trial = 0; trial < 20; trial++) {
                StringBuilder body = new StringBuilder();
                int length = 1_024 + random.nextInt(4 * 1_024);
                while (body.length() < length) {
                    body.append(fragments.get(random.nextInt(fragments.size())));
                }
                byte[] bodyBytes = body.toString().getBytes(StandardCharsets.US_ASCII);
                int backingOffset = random.nextInt(8);
                byte[] backing = new byte[backingOffset + bodyBytes.length + 8];
                Arrays.fill(backing, (byte) '_');
                System.arraycopy(bodyBytes, 0, backing, backingOffset, bodyBytes.length);
                Slice text = Slices.wrappedBuffer(backing, backingOffset, bodyBytes.length);

                List<List<Integer>> expected = new ArrayList<>();
                Matcher oracleMatcher = oracle.matcher(body);
                while (oracleMatcher.find()) {
                    List<Integer> spans = new ArrayList<>();
                    for (int group = 0; group <= oracleMatcher.groupCount(); group++) {
                        spans.add(oracleMatcher.start(group));
                        spans.add(oracleMatcher.end(group));
                    }
                    expected.add(spans);
                }
                assertThat(Dfa.countMatches(program, text, Prog.MatchKind.FIRST_MATCH)).as(pattern).isEqualTo(expected.size());
                for (CompiledPattern language : languages) {
                    RegexpMatcher matcher = language.matcher(text);
                    List<List<Integer>> actual = new ArrayList<>();
                    while (matcher.find()) {
                        List<Integer> spans = new ArrayList<>();
                        for (int group = 0; group <= matcher.groupCount(); group++) {
                            spans.add(matcher.start(group));
                            spans.add(matcher.end(group));
                        }
                        actual.add(spans);
                    }
                    assertThat(actual).as("%s %s", language.getClass().getSimpleName(), pattern).isEqualTo(expected);
                }
            }
        }
    }

    private static SearchCase createSearchCase()
    {
        String rejectedCandidate = PREFIX + "x";
        String prefix = "x".repeat(1_100) + rejectedCandidate + " ";
        String match = PREFIX + "123";
        Slice text = Slices.utf8Slice(prefix + match);
        int matchStart = prefix.getBytes(StandardCharsets.UTF_8).length;
        int captureStart = matchStart + PREFIX.getBytes(StandardCharsets.UTF_8).length;
        return new SearchCase(text, matchStart, captureStart, text.length());
    }

    private static Prog compile(String pattern)
    {
        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice(pattern), Regexp.LIKE_PERL);
        return Compiler.compile(parsed.regexp(), false, 0);
    }

    private record SearchCase(Slice text, int matchStart, int captureStart, int matchEnd) {}
}
