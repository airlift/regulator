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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.airlift.regulator.TestingPatternShapes.assertNoProgramSearch;
import static io.airlift.regulator.TestingPatternShapes.assertProgramSearchUnless;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Includes tests adapted from upstream RE2: re2/testing/re2_test.cc.
public class TestRegexpMatcher
{
    @Test
    public void testBooleanMatchDoesNotInspectCaptureWorkspace()
            throws ReflectiveOperationException
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("[a-z]+"));
        Slice input = Slices.utf8Slice("123abc456");
        Method matchInternal = CompiledPattern.class.getDeclaredMethod(
                "matchInternal",
                Prog.class,
                Slice.class,
                int.class,
                int.class,
                int.class,
                int.class,
                CompiledPattern.Anchor.class,
                int[].class,
                CompiledPattern.MatchWorkspaces.class);
        matchInternal.setAccessible(true);

        assertThat(matchInternal.invoke(
                pattern,
                pattern.forwardProgramForDiagnostics(),
                input,
                0,
                input.length(),
                0,
                input.length(),
                CompiledPattern.Anchor.UNANCHORED,
                null,
                new UnreadableMatchWorkspaces()))
                .isEqualTo(true);
    }

    @Test
    public void testGroupZeroForwardCursorFindsFixedWidthClassSequence()
            throws ReflectiveOperationException
    {
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("[ab][cd]"))
                .groupZeroMatcher(Slices.utf8Slice("xxaczz"), null);

        assertThat(matcher.groupZeroForwardCursorEnabledForDiagnostics()).isTrue();
        assertThat(workspace(matcher, "onePassWorkspace")).isNull();
        assertThat(workspace(matcher, "bitStateWorkspace")).isNull();
        assertThat(workspace(matcher, "nfaWorkspace")).isNull();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(2);
        assertThat(matcher.end()).isEqualTo(4);
        assertThat(matcher.find()).isFalse();
        assertThat(workspace(matcher, "onePassWorkspace")).isNull();
        assertThat(workspace(matcher, "bitStateWorkspace")).isNull();
        assertThat(workspace(matcher, "nfaWorkspace")).isNull();
    }

    @Test
    public void testGroupZeroForwardCursorPreservesRegionAndUtf8Boundaries()
    {
        // The optional suffix keeps the pattern off the class counter.
        Slice input = Slices.utf8Slice("xαβxαx");
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("[αβ]+γ?"))
                .groupZeroMatcher(input, null)
                .reset(input, 1, 8);

        assertThat(matcher.groupZeroForwardCursorEnabledForDiagnostics()).isTrue();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(5);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(6);
        assertThat(matcher.end()).isEqualTo(8);
        assertThat(matcher.find()).isFalse();
    }

    @Test
    public void testRepeatedFindReusesMatcher()
    {
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("(?<word>\\w+)"))
                .matcher(Slices.utf8Slice("one two"));

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(0);
        assertThat(matcher.end()).isEqualTo(3);
        assertThat(matcher.group("word").toStringUtf8()).isEqualTo("one");

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(4);
        assertThat(matcher.end()).isEqualTo(7);
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("two");
        assertThat(matcher.find()).isFalse();
    }

    @Test
    public void testRepeatedAnchoredMatchingOverRemainingInput()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("\\s*(\\w+)"));
        Slice remaining = Slices.utf8Slice("   aaa b!@#$@#$cccc");

        RegexpMatcher matcher = pattern.matcher(remaining);
        assertThat(matcher.lookingAt()).isTrue();
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("aaa");
        remaining = remaining.slice(matcher.end(), remaining.length() - matcher.end());

        matcher.reset(remaining);
        assertThat(matcher.lookingAt()).isTrue();
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("b");
        remaining = remaining.slice(matcher.end(), remaining.length() - matcher.end());

        assertThat(matcher.reset(remaining).lookingAt()).isFalse();
    }

    @Test
    public void testRepeatedFindSkipsNonMatchingInput()
    {
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("(\\w+)"))
                .matcher(Slices.utf8Slice("   aaa b!@#$@#$cccc"));

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("aaa");
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("b");
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("cccc");
        assertThat(matcher.find()).isFalse();

        matcher = Re2.compile(Slices.utf8Slice("aaa")).matcher(Slices.utf8Slice("aaa"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.end()).isEqualTo(3);
        assertThat(matcher.find()).isFalse();
    }

    @Test
    public void testStartAnchoredFindAgreesWithOrdinaryEngine()
    {
        // A find over the whole input of a start-anchored pattern, from the input start with
        // nothing matched since reset, goes straight to its one-pass or BitState engine. The
        // ordinary engine is the oracle; a second find fails.
        Random random = new Random(20261105);
        String alphabet = "ab1;;;;YA\t 'u\"x";
        List<String> rows = List.of("A1;x;y;1;z;;;;Y", "A;a;b;1;a;;1;;Y", "\"q\"", "aab", "abb");
        List<CompiledPattern> patterns = List.of(
                // One-pass; the rest run BitState.
                RustRegexp.compile(Slices.utf8Slice("^([A-Z0-9]+);([^;]+);([^;]+);([0-9]+);([^;]+);([^;]*);([0-9]*);([^;]*);([YN])$")),
                Re2.compile(Slices.utf8Slice("^(?i)[urb]*['\"](.*)['\"]$")),
                Re2.compile(Slices.utf8Slice("^(a+)(a*)b")),
                Re2.compile(Slices.utf8Slice("^(a|ab)(b*)")));
        assertThat(patterns.getFirst().forwardProgramForDiagnostics().isOnePass()).isTrue();
        for (CompiledPattern pattern : patterns) {
            String regex = pattern.pattern().toStringUtf8();
            assertThat(pattern.hasAnchoredSubmatchRoute()).as(regex).isTrue();
            int[] expected = new int[2 * (pattern.capturingGroupCount() + 1)];
            int matches = 0;
            for (int iteration = 0; iteration < 2000; iteration++) {
                String text;
                if (iteration % 3 == 0) {
                    text = rows.get(random.nextInt(rows.size()));
                }
                else {
                    StringBuilder builder = new StringBuilder();
                    for (int length = random.nextInt(32); length > 0; length--) {
                        builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
                    }
                    text = builder.toString();
                }
                Slice input = Slices.utf8Slice(text);
                boolean expectedMatch = pattern.matchRegionInto(input, 0, input.length(), 0, input.length(), CompiledPattern.Anchor.UNANCHORED, expected, new OnePass.Workspace(), new BitState.Workspace(), new Nfa.Workspace());
                RegexpMatcher matcher = pattern.matcher(input);
                String description = regex + " on " + text;
                assertThat(matcher.find()).as(description).isEqualTo(expectedMatch);
                if (expectedMatch) {
                    matches++;
                    for (int group = 0; group <= pattern.capturingGroupCount(); group++) {
                        assertThat(matcher.start(group)).as(description).isEqualTo(expected[2 * group]);
                        assertThat(matcher.end(group)).as(description).isEqualTo(expected[2 * group + 1]);
                    }
                }
                assertThat(matcher.find()).as(description).isFalse();
            }
            assertThat(matches).as(regex).isPositive();
        }
        // A pattern that can match empty, or is not start-anchored, such as the last one above
        // without its anchor, keeps the usual route.
        assertThat(Re2.compile(Slices.utf8Slice("^(a*)")).hasAnchoredSubmatchRoute()).isFalse();
        assertThat(Re2.compile(Slices.utf8Slice("(a|ab)(b*)")).hasAnchoredSubmatchRoute()).isFalse();
    }

    @Test
    public void testGroupZeroMatcherDoesNotExtractCaptures()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("(💰)"));
        RegexpMatcher matcher = pattern.groupZeroMatcher(Slices.utf8Slice("a💰b💰"), null);

        assertThat(matcher.groupCount()).isZero();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(5);
        assertThat(matcher.group().toStringUtf8()).isEqualTo("💰");
        assertThatThrownBy(() -> matcher.group(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("group index out of range: 1");

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(6);
        assertThat(matcher.end()).isEqualTo(10);
        assertThat(matcher.find()).isFalse();
    }

    @Test
    public void testGroupZeroMatchUsesNoCaptureNfaWorkspaceWhenDfaFails()
    {
        // Case folding keeps the pattern off the disjoint-suffix repeat matcher, which would
        // answer it without the DFA, and the unbounded x+ keeps it off the position automaton,
        // whose window BitState would search.
        Re2 pattern = Re2.compile(Slices.utf8Slice("(?i)[a-q][^u-z]{80}x+"), Re2.Options.latin1().setMaxMemory(4L << 20));
        // The only match ends the text, so the forward DFA thrashes on the way to it and fails.
        byte[] bytes = stateExplosionInput(64 << 10);
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == 'x') {
                bytes[index] = '.';
            }
        }
        Arrays.fill(bytes, bytes.length - 82, bytes.length - 1, (byte) 'a');
        bytes[bytes.length - 1] = 'x';
        Slice input = Slices.wrappedBuffer(bytes);
        Nfa.Workspace workspace = new Nfa.Workspace();
        int[] groupZero = new int[2];

        assertThat(pattern.matchInto(input, 0, input.length(), CompiledPattern.Anchor.UNANCHORED, groupZero, null, null, workspace)).isTrue();
        assertThat(pattern.matchInto(input, 0, input.length(), CompiledPattern.Anchor.UNANCHORED, groupZero, null, null, workspace)).isTrue();
        assertThat(workspace.groupZeroWorkspaceReuseCountForDiagnostics()).isGreaterThan(0);
    }

    @Test
    public void testPublicMatcherCanLimitCaptures()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("(?<first>a)(?<second>b)"));
        assertThat(pattern.isNamedCapturingGroupsComputed()).isFalse();

        RegexpMatcher matcher = pattern.matcher(Slices.utf8Slice("zab"), 0);
        assertThat(matcher.groupCount()).isZero();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(3);
        assertThat(pattern.isNamedCapturingGroupsComputed()).isFalse();
        assertThatThrownBy(() -> matcher.group(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("group index out of range: 1");
    }

    private static byte[] stateExplosionInput(int length)
    {
        Random random = new Random(42);
        byte[] alphabet = "abcdefghijklmnopqrst \n,.;0123456789".getBytes(US_ASCII);
        byte[] input = new byte[length];
        for (int index = 0; index < input.length; index++) {
            input[index] = index % 997 == 996 ? (byte) 'x' : alphabet[random.nextInt(alphabet.length)];
        }
        return input;
    }

    @Test
    public void testGroupZeroMatcherDoesNotLoadNamedCaptures()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("(?<value>a)"));
        assertThat(pattern.isNamedCapturingGroupsComputed()).isFalse();

        assertThat(pattern.groupZeroMatcher(Slices.utf8Slice("a"), null).find()).isTrue();
        assertThat(pattern.isNamedCapturingGroupsComputed()).isFalse();

        assertThat(pattern.matcher(Slices.utf8Slice("a")).find()).isTrue();
        assertThat(pattern.isNamedCapturingGroupsComputed()).isTrue();
    }

    @Test
    public void testGroupZeroMatcherUsesSingleByteMatcher()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("([,;])"));
        SingleByteMatcher byteMatcher = pattern.sharedSingleByteMatcher();
        assertThat(byteMatcher).isNotNull();

        RegexpMatcher matcher = pattern.groupZeroMatcher(Slices.utf8Slice("a,b;c"), byteMatcher);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(2);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(3);
        assertThat(matcher.end()).isEqualTo(4);
        assertThat(matcher.find()).isFalse();
        assertThat(pattern.isReverseProgramComputed()).isFalse();
    }

    @Test
    public void testGroupZeroMatcherSkipsReversePassWhenEveryPositionMatches()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice(".*"));
        RegexpMatcher matcher = pattern.groupZeroMatcher(Slices.utf8Slice("ab\ncd"), null);
        assertThat(matcher.groupZeroForwardCursorEnabledForDiagnostics()).isTrue();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(0);
        assertThat(matcher.end()).isEqualTo(2);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(2);
        assertThat(matcher.end()).isEqualTo(2);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(3);
        assertThat(matcher.end()).isEqualTo(5);
        assertThat(pattern.isReverseProgramComputed()).isFalse();

        // An assertion can refuse the search start, so the start comes from the reverse pass.
        pattern = Re2.compile(Slices.utf8Slice("\\ba*"));
        matcher = pattern.groupZeroMatcher(Slices.utf8Slice("-aa"), null);
        assertThat(matcher.groupZeroForwardCursorEnabledForDiagnostics()).isTrue();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(3);
        assertThat(pattern.isReverseProgramComputed()).isTrue();
    }

    @Test
    public void testGroupZeroMatcherFindsOneCharacterStartsWithoutReversePass()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("(\\pL)"));
        RegexpMatcher matcher = pattern.groupZeroMatcher(Slices.utf8Slice("aé-有𝐀b"), null);
        assertThat(matcher.groupZeroForwardCursorEnabledForDiagnostics()).isTrue();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(0);
        assertThat(matcher.end()).isEqualTo(1);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(3);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(4);
        assertThat(matcher.end()).isEqualTo(7);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(7);
        assertThat(matcher.end()).isEqualTo(11);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(11);
        assertThat(matcher.end()).isEqualTo(12);
        assertThat(matcher.find()).isFalse();
        assertThat(pattern.isReverseProgramComputed()).isFalse();

        // Starts stay relative to a Slice that begins inside its byte array.
        // Without a capture group, the class counter finds them.
        pattern = Re2.compile(Slices.utf8Slice("\\pL"));
        matcher = pattern.groupZeroMatcher(Slices.wrappedBuffer(Slices.utf8Slice("xxé𝐀xx").getBytes(), 2, 6), null);
        assertThat(matcher.characterClassCounterEnabledForDiagnostics()).isTrue();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(0);
        assertThat(matcher.end()).isEqualTo(2);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(2);
        assertThat(matcher.end()).isEqualTo(6);
        assertThat(matcher.find()).isFalse();
        assertThat(pattern.isReverseProgramComputed()).isFalse();

        // The program matches an encoded surrogate as one character. Those bytes are not valid
        // UTF-8, so the surrogate's start comes from the reverse pass.
        pattern = Re2.compile(Slices.utf8Slice("(?s:.)"));
        matcher = pattern.groupZeroMatcher(Slices.wrappedBuffer(new byte[] {'a', (byte) 0xED, (byte) 0xA0, (byte) 0x80, 'b'}), null);
        assertThat(matcher.groupZeroForwardCursorEnabledForDiagnostics()).isTrue();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(0);
        assertThat(matcher.end()).isEqualTo(1);
        assertThat(pattern.isReverseProgramComputed()).isFalse();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(4);
        assertThat(pattern.isReverseProgramComputed()).isTrue();
    }

    @Test
    public void testGroupZeroMatcherPreservesMatchBoundaries()
    {
        assertGroupZeroMatchesFull("(a+)", "zaaazaa");
        assertGroupZeroMatchesFull("((ab)|a)+", "zzabaabzaba");
        assertGroupZeroMatchesFull("(a*)", "baa");
        assertGroupZeroMatchesFull("(a+?)", "zaaazaa");
        assertGroupZeroMatchesFull("((a)?b)", "bbab");
        assertGroupZeroMatchesFull("^(a+)", "aaab");
        assertGroupZeroMatchesFull("(💰)", "a💰b💰");
        assertGroupZeroMatchesFull("(a|ab)", "zab");
        assertGroupZeroMatchesFull(
                "POSIX (a|aa)",
                Re2.compile(Slices.utf8Slice("(a|aa)"), Re2.Options.posix()),
                Slices.utf8Slice("zaa"));
        assertGroupZeroMatchesFull(
                "Latin1 (.)",
                Re2.compile(Slices.utf8Slice("(.)"), Re2.Options.latin1()),
                Slices.wrappedBuffer(new byte[] {(byte) 0xFF, 'a'}));
        assertGroupZeroMatchesFull(
                "nonzero Slice window",
                Re2.compile(Slices.utf8Slice("(a+)")),
                Slices.wrappedBuffer(Slices.utf8Slice("xxaaaxx").getBytes(), 2, 3));
    }

    @Test
    public void testDenseFindsSwitchToMatchStartSet()
    {
        // The candidate cursor answers patterns without assertions, so these hold one.
        // Leftmost-first priority from each start: alternation order, greedy and lazy, and an
        // earlier branch that is shorter.
        assertMatchStartSet("[a-z]|[a-z][a-z]|$", "aaaa ");
        assertMatchStartSet("[a-z][a-z]|[a-z]|$", "aaaa ");
        assertMatchStartSet("[a-z]+|$", "aaaa ");
        assertMatchStartSet("[a-z]+?|$", "aaaa ");
        assertMatchStartSet("[a-z]|[a-z]+|$", "aaaa ");
        // Empty matches, and matches at the end of the input.
        assertMatchStartSet("\\b[a-z]*", "ab cd ");
        assertMatchStartSet("[a-z]+\\b", "ab cd ");
        // Adjacent matches, each ending where the next starts.
        assertMatchStartSet("[a-z]{1,2}|$", "abab");
        // Empty matches beside non-ASCII text advance by a whole character.
        assertMatchStartSet("[a-z]+|\\B", "ab \u00e9\u00e9 cd ");
    }

    @Test
    public void testMatchStartSetReadsRegionContext()
    {
        // The region cuts words at both edges; its bounds are the text's edges for \\b.
        Re2 pattern = Re2.compile(Slices.utf8Slice("\\b[a-z]+\\b|[0-9]"));
        Slice input = denseText("ab1 cd ");
        assertMatchStartSet(pattern, input, 1, input.length() - 2);
        // A Slice that begins inside its byte array.
        Slice view = Slices.wrappedBuffer(input.getBytes(), 3, input.length() - 3);
        assertMatchStartSet(pattern, view, 0, view.length());
    }

    @Test
    public void testFindsOutsideAnOperationReadEachEdit()
    {
        // A caller's find loop may change the input between finds, so only the library's own
        // all-match operations switch: each find here reads the byte changed after the 64th.
        String text = "ab...".repeat(3000);
        Slice input = Slices.utf8Slice(text);
        StringBuilder edited = new StringBuilder(text);
        RegexpMatcher matcher = JavaRegexp.compile(Slices.utf8Slice("[a-z]+\\b")).matcher(input, 0);
        Matcher jdk = Pattern.compile("[a-z]+\\b").matcher(edited);
        for (int find = 0; find < RegexpMatcher.MATCH_START_SET_SWITCH_FINDS; find++) {
            assertThat(matcher.find()).isTrue();
            assertThat(jdk.find()).isTrue();
        }
        input.setByte(matcher.end(), 'z');
        edited.setCharAt(jdk.end(), 'z');
        while (jdk.find()) {
            assertThat(matcher.find()).isTrue();
            assertThat(List.of(matcher.start(), matcher.end())).isEqualTo(List.of(jdk.start(), jdk.end()));
        }
        assertThat(matcher.find()).isFalse();
        assertThat(matcher.matchStartSetSwitchCountForDiagnostics()).isZero();
    }

    @Test
    public void testTightBudgetBorrowsNothingForStartSet()
    {
        // A reverse DFA that cannot spare the set's memory keeps every find on the cursor, and
        // lends nothing to a start-set DFA the operation could not use.
        Slice dense = Slices.utf8Slice("ab1 cd ".repeat(1024 * 1024 / 7));
        Re2 tight = Re2.compile(Slices.utf8Slice("\\b[a-z]+\\b|[0-9]"), Re2.Options.defaults().setMaxMemory(256 << 10));
        RegexpMatcher matcher = tight.groupZeroMatcher(dense, null);
        assertThat(operationSpans(matcher)).isEqualTo(spans(tight.genericGroupZeroMatcherForDiagnostics(dense), 0));
        assertThat(matcher.matchStartSetSwitchCountForDiagnostics()).isZero();
        assertThat(tight.reverseProgramIfComputedForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.MANY_MATCH)).isNull();
    }

    @Test
    public void testForwardCacheResetsAfterSwitch()
    {
        // The anchored searches after the switch reset the forward DFA's cache many times, and
        // each one follows the search's own rules.
        Slice abWords = Slices.utf8Slice(abWords(64 * 1024));
        Re2 resetting = Re2.compile(Slices.utf8Slice("\\b[ab]*a[ab]{12}\\b"), Re2.Options.defaults().setMaxMemory(1 << 20));
        RegexpMatcher matcher = resetting.groupZeroMatcher(abWords, null);
        List<Long> expected = spans(resetting.genericGroupZeroMatcherForDiagnostics(abWords), 0);
        Dfa.DfaInstance forward = resetting.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        matcher.beginAllMatchOperation();
        try {
            List<Long> found = spans(matcher, RegexpMatcher.MATCH_START_SET_SWITCH_FINDS);
            assertThat(matcher.matchStartSetSwitchCountForDiagnostics()).isEqualTo(1);
            int resetsAtSwitch = forward.resetCount();
            found.addAll(spans(matcher, 0));
            assertThat(forward.resetCount()).isGreaterThan(resetsAtSwitch);
            assertThat(found).isEqualTo(expected);
        }
        finally {
            matcher.endAllMatchOperation();
        }
    }

    @Test
    public void testDenseOperationLeavesLaterSearchesTheirMemory()
    {
        // The start-set DFA's loan comes back before the reverse DFA would refuse a state, so the
        // finds after a dense operation reset that DFA no more often than without the switch.
        Slice dense = Slices.utf8Slice("aaaaabbbbbbaaaaabbbbbbaaaa ".repeat(1000));
        String[] rows = abWords(100 * 1024).split(" ");
        int[] resets = new int[2];
        for (int switching = 0; switching < 2; switching++) {
            Re2 pattern = Re2.compile(Slices.utf8Slice("\\b[ab]{12}a[ab]*\\b"), Re2.Options.defaults().setMaxMemory(8 << 20));
            RegexpMatcher denseMatcher = switching == 1 ? pattern.groupZeroMatcher(dense, null) : pattern.genericGroupZeroMatcherForDiagnostics(dense);
            operationSpans(denseMatcher);
            assertThat(denseMatcher.matchStartSetSwitchCountForDiagnostics()).isEqualTo(switching);
            Dfa.DfaInstance reverse = pattern.reverseProgramIfComputedForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH);
            int resetsBefore = reverse.resetCount();
            for (String row : rows) {
                pattern.groupZeroMatcher(Slices.utf8Slice(row), null).find();
            }
            resets[switching] = reverse.resetCount() - resetsBefore;
        }
        assertThat(resets[1]).isEqualTo(resets[0]);
    }

    @Test
    public void testSharedPatternSwitchesOnManyThreads()
            throws Exception
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("\\b[a-z]+\\b|[0-9]"));
        Slice input = denseText("ab1 cd ");
        List<Long> expected = spans(pattern.genericGroupZeroMatcherForDiagnostics(input), 0);
        // One operation first builds the start-set DFA, so no worker loses a race to build it.
        operationSpans(pattern.groupZeroMatcher(input, null));
        List<Future<List<Long>>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int task = 0; task < 32; task++) {
                results.add(executor.submit(() -> {
                    RegexpMatcher matcher = pattern.groupZeroMatcher(input, null);
                    List<Long> spans = operationSpans(matcher);
                    assertThat(matcher.matchStartSetSwitchCountForDiagnostics()).isEqualTo(1);
                    return spans;
                }));
            }
            for (Future<List<Long>> result : results) {
                assertThat(result.get()).isEqualTo(expected);
            }
        }
        // Every operation returned its memory.
        assertThat(pattern.reverseProgramIfComputedForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH).matchStartSetReservationForDiagnostics()).isZero();
    }

    @Test
    public void testMatchStartSetIsChargedOnlyDuringItsOperation()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("\\b[a-z]+\\b|[0-9]"));
        Slice input = denseText("ab1 cd ");
        // The first operation builds the DFAs, the start-set DFA among them, which the pattern keeps.
        operationSpans(pattern.groupZeroMatcher(input, null));
        long retainedSize = pattern.retainedSizeInBytes();
        Dfa.DfaInstance lender = pattern.reverseProgramIfComputedForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH);

        // The switch at the last of the first finds covers the rest of the input from its end.
        RegexpMatcher matcher = pattern.groupZeroMatcher(input, null);
        matcher.beginAllMatchOperation();
        spans(matcher, RegexpMatcher.MATCH_START_SET_SWITCH_FINDS);
        assertThat(matcher.holdsMatchStartSetForDiagnostics()).isTrue();
        long charge = lender.matchStartSetReservationForDiagnostics();
        assertThat(charge).isEqualTo(Dfa.matchStartSetBytes(input.length() - matcher.end()));
        assertThat(pattern.retainedSizeInBytes()).isEqualTo(retainedSize + charge);

        matcher.endAllMatchOperation();
        assertThat(matcher.holdsMatchStartSetForDiagnostics()).isFalse();
        assertThat(lender.matchStartSetReservationForDiagnostics()).isZero();
        assertThat(pattern.retainedSizeInBytes()).isEqualTo(retainedSize);
    }

    // The spans of the next `limit` finds, or of every find when `limit` is 0, each packed as
    // start and end.
    private static List<Long> spans(RegexpMatcher matcher, int limit)
    {
        List<Long> spans = new ArrayList<>();
        while ((limit == 0 || spans.size() < limit) && matcher.find()) {
            spans.add(((long) matcher.start() << 32) | matcher.end());
        }
        return spans;
    }

    // The spans of one all-match operation over the matcher's region, as the library's loops run it.
    private static List<Long> operationSpans(RegexpMatcher matcher)
    {
        matcher.beginAllMatchOperation();
        try {
            return spans(matcher, 0);
        }
        finally {
            matcher.endAllMatchOperation();
        }
    }

    // About `length` bytes of random words of 14 to 23 letters a and b, each followed by a space.
    private static String abWords(int length)
    {
        Random random = new Random(7);
        StringBuilder words = new StringBuilder();
        while (words.length() < length) {
            int wordLength = 14 + random.nextInt(10);
            for (int i = 0; i < wordLength; i++) {
                words.append(random.nextBoolean() ? 'a' : 'b');
            }
            words.append(' ');
        }
        return words.toString();
    }

    private static void assertMatchStartSet(String expression, String unit)
    {
        Slice input = denseText(unit);
        assertMatchStartSet(Re2.compile(Slices.utf8Slice(expression)), input, 0, input.length());
    }

    // About 16 KiB of `unit` repeated, ending without the unit's trailing space.
    private static Slice denseText(String unit)
    {
        return Slices.utf8Slice(unit.repeat(16 * 1024 / unit.length()).strip());
    }

    private static void assertMatchStartSet(CompiledPattern pattern, Slice input, int regionStart, int regionEnd)
    {
        assertMatchStartSet(pattern, input, regionStart, regionEnd, 1);
    }

    /// Checks that an all-match operation over the region switches to a match start set
    /// `switches` times, once or never, and finds the matches the generic matcher finds without
    /// one, and so does a find after the loop's failed one, which searches again from the last
    /// match end. The failed find releases the set. The language twins share it.
    static void assertMatchStartSet(CompiledPattern pattern, Slice input, int regionStart, int regionEnd, long switches)
    {
        RegexpMatcher matcher = pattern.matcher(input, 0).reset(input, regionStart, regionEnd);
        RegexpMatcher expected = pattern.genericGroupZeroMatcherForDiagnostics(input).reset(input, regionStart, regionEnd);
        String description = new String(pattern.pattern().getBytes(), UTF_8);
        matcher.beginAllMatchOperation();
        try {
            while (expected.find()) {
                assertThat(matcher.find()).as("find of %s", description).isTrue();
                assertThat(matcher.start()).as("match start of %s", description).isEqualTo(expected.start());
                assertThat(matcher.end()).as("match end of %s", description).isEqualTo(expected.end());
            }
            assertThat(matcher.find()).as("find after the last match of %s", description).isFalse();
            assertThat(matcher.matchStartSetSwitchCountForDiagnostics()).as("start set switches of %s", description).isEqualTo(switches);
            assertThat(matcher.holdsMatchStartSetForDiagnostics()).isFalse();
            boolean expectedRetry = expected.find();
            assertThat(matcher.find()).isEqualTo(expectedRetry);
            if (expectedRetry) {
                assertThat(matcher.start()).isEqualTo(expected.start());
                assertThat(matcher.end()).isEqualTo(expected.end());
            }
        }
        finally {
            matcher.endAllMatchOperation();
        }
    }

    private static void assertGroupZeroMatchesFull(String expression, String input)
    {
        assertGroupZeroMatchesFull(expression, Re2.compile(Slices.utf8Slice(expression)), Slices.utf8Slice(input));
    }

    private static Object workspace(RegexpMatcher matcher, String name)
            throws ReflectiveOperationException
    {
        var field = RegexpMatcher.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(matcher);
    }

    private static void assertGroupZeroMatchesFull(String description, Re2 pattern, Slice input)
    {
        RegexpMatcher matcher = pattern.matcher(input);
        RegexpMatcher groupZeroMatcher = pattern.groupZeroMatcher(input, null);

        while (matcher.find()) {
            assertThat(groupZeroMatcher.find())
                    .as("group-zero match for %s", description)
                    .isTrue();
            assertThat(groupZeroMatcher.start())
                    .as("match start for %s", description)
                    .isEqualTo(matcher.start());
            assertThat(groupZeroMatcher.end())
                    .as("match end for %s", description)
                    .isEqualTo(matcher.end());
        }
        assertThat(groupZeroMatcher.find())
                .as("no extra group-zero match for %s", description)
                .isFalse();
    }

    @Test
    public void testEmptyMatchesAdvanceByUtf8CodePoint()
    {
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice(""))
                .matcher(Slices.utf8Slice("a有💰"));
        List<Integer> starts = new ArrayList<>();

        while (matcher.find()) {
            starts.add(matcher.start());
            assertThat(matcher.end()).isEqualTo(matcher.start());
        }

        assertThat(starts).containsExactly(0, 1, 4, 8);
    }

    @Test
    public void testEmptyMatchesAdvanceByByteInLatin1()
    {
        RegexpMatcher matcher = Re2.compile(
                        Slices.wrappedBuffer(new byte[0]),
                        Re2.Options.latin1())
                .matcher(Slices.wrappedBuffer(new byte[] {(byte) 0xC3, (byte) 0xA9}));
        List<Integer> starts = new ArrayList<>();

        while (matcher.find()) {
            starts.add(matcher.start());
        }

        assertThat(starts).containsExactly(0, 1, 2);
    }

    @Test
    public void testAnchoredEmptyMatchAdvancesNextFind()
    {
        RegexpMatcher utf8 = Re2.compile(Slices.utf8Slice(""))
                .matcher(Slices.utf8Slice("💰"));
        assertThat(utf8.lookingAt()).isTrue();
        assertThat(utf8.start()).isZero();
        assertThat(utf8.end()).isZero();
        assertThat(utf8.find()).isTrue();
        assertThat(utf8.start()).isEqualTo(4);
        assertThat(utf8.end()).isEqualTo(4);
        assertThat(utf8.find()).isFalse();

        RegexpMatcher latin1 = Re2.compile(Slices.EMPTY_SLICE, Re2.Options.latin1())
                .matcher(Slices.wrappedBuffer(new byte[] {(byte) 0xFF, 'a'}));
        assertThat(latin1.lookingAt()).isTrue();
        assertThat(latin1.find()).isTrue();
        assertThat(latin1.start()).isEqualTo(1);

        Slice regionInput = Slices.utf8Slice("x💰y");
        RegexpMatcher region = Re2.compile(Slices.utf8Slice(""))
                .matcher(regionInput)
                .reset(regionInput, 1, 5);
        assertThat(region.lookingAt()).isTrue();
        assertThat(region.start()).isEqualTo(1);
        assertThat(region.find()).isTrue();
        assertThat(region.start()).isEqualTo(5);
    }

    @Test
    public void testAnchoredMatchCursorLifecycleControls()
    {
        RegexpMatcher terminal = Re2.compile(Slices.utf8Slice(""))
                .matcher(Slices.EMPTY_SLICE);
        assertThat(terminal.matches()).isTrue();
        assertThat(terminal.find()).isFalse();

        RegexpMatcher nonempty = Re2.compile(Slices.utf8Slice("a"))
                .matcher(Slices.utf8Slice("aa"));
        assertThat(nonempty.lookingAt()).isTrue();
        assertThat(nonempty.find()).isTrue();
        assertThat(nonempty.start()).isEqualTo(1);

        RegexpMatcher failedAnchor = Re2.compile(Slices.utf8Slice("a"))
                .matcher(Slices.utf8Slice("ba"));
        assertThat(failedAnchor.find()).isTrue();
        assertThat(failedAnchor.start()).isEqualTo(1);
        assertThat(failedAnchor.lookingAt()).isFalse();
        assertThat(failedAnchor.find()).isFalse();
    }

    @Test
    public void testAnchoredEmptyMatchAdvancementEdgeCases()
    {
        byte[] padded = new byte[12];
        Slice money = Slices.utf8Slice("💰");
        money.getBytes(0, padded, 4, money.length());
        Slice offsetInput = Slices.wrappedBuffer(padded, 4, money.length());
        RegexpMatcher offsetMatcher = Re2.compile(Slices.EMPTY_SLICE).matcher(offsetInput);
        assertThat(offsetMatcher.lookingAt()).isTrue();
        assertThat(offsetMatcher.find()).isTrue();
        assertThat(offsetMatcher.start()).isEqualTo(4);

        Slice malformedInput = Slices.wrappedBuffer(new byte[] {(byte) 0xF0, 'a'});
        RegexpMatcher malformedMatcher = Re2.compile(Slices.EMPTY_SLICE).matcher(malformedInput);
        assertThat(malformedMatcher.lookingAt()).isTrue();
        assertThat(malformedMatcher.find()).isTrue();
        assertThat(malformedMatcher.start()).isEqualTo(1);

        RegexpMatcher captureMatcher = Re2.compile(Slices.utf8Slice("()"))
                .matcher(Slices.utf8Slice("a"));
        assertThat(captureMatcher.lookingAt()).isTrue();
        assertThat(captureMatcher.start(1)).isZero();
        assertThat(captureMatcher.end(1)).isZero();
        assertThat(captureMatcher.find()).isTrue();
        assertThat(captureMatcher.start()).isEqualTo(1);
        assertThat(captureMatcher.start(1)).isEqualTo(1);

        RegexpMatcher groupZeroMatcher = Re2.compile(Slices.EMPTY_SLICE)
                .groupZeroMatcher(Slices.utf8Slice("a"), null);
        assertThat(groupZeroMatcher.lookingAt()).isTrue();
        assertThat(groupZeroMatcher.find()).isTrue();
        assertThat(groupZeroMatcher.start()).isEqualTo(1);

        RegexpMatcher repeatedAnchor = Re2.compile(Slices.EMPTY_SLICE)
                .matcher(Slices.utf8Slice("💰"));
        assertThat(repeatedAnchor.lookingAt()).isTrue();
        assertThat(repeatedAnchor.lookingAt()).isTrue();
        assertThat(repeatedAnchor.find()).isTrue();
        assertThat(repeatedAnchor.start()).isEqualTo(4);

        RegexpMatcher initialFailedAnchor = Re2.compile(Slices.utf8Slice("a"))
                .matcher(Slices.utf8Slice("ba"));
        assertThat(initialFailedAnchor.lookingAt()).isFalse();
        assertThat(initialFailedAnchor.find()).isTrue();
        assertThat(initialFailedAnchor.start()).isEqualTo(1);
    }

    @Test
    public void testNullableStartByteFastPath()
    {
        Re2 star = Re2.compile(Slices.utf8Slice("x*"));
        assertThat(star.canReturnEmptyAtStart(Slices.utf8Slice("aaa"), 0)).isTrue();
        assertThat(star.canReturnEmptyAtStart(Slices.utf8Slice("xaa"), 0)).isFalse();
        assertThat(star.canReturnEmptyAtStart(Slices.utf8Slice("xaa"), 1)).isTrue();
        assertThat(star.canReturnEmptyAtStart(Slices.utf8Slice("xaa"), 3)).isTrue();

        assertThat(Re2.compile(Slices.utf8Slice("(x*)")).canReturnEmptyAtStart(Slices.utf8Slice("aaa"), 0)).isFalse();
        assertThat(Re2.compile(Slices.utf8Slice("^a")).canReturnEmptyAtStart(Slices.EMPTY_SLICE, 0)).isFalse();
        assertThat(Re2.compile(Slices.utf8Slice("x*$")).canReturnEmptyAtStart(Slices.utf8Slice("aaa"), 0)).isFalse();
        assertThat(Re2.compile(Slices.utf8Slice("\\b|x")).canReturnEmptyAtStart(Slices.utf8Slice("aaa"), 0)).isFalse();

        RegexpMatcher matcher = star.matcher(Slices.utf8Slice("xxa"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isEqualTo(2);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(2);
        assertThat(matcher.end()).isEqualTo(2);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(3);
        assertThat(matcher.end()).isEqualTo(3);
        assertThat(matcher.find()).isFalse();
    }

    @Test
    public void testNullableStartByteFastPathPreservesMatchPriority()
    {
        assertMatchRanges(Re2.compile(Slices.utf8Slice("a|")), "ab", "0:1", "1:1", "2:2");
        assertMatchRanges(Re2.compile(Slices.utf8Slice("|a")), "a", "0:0", "1:1");
        assertMatchRanges(Re2.compile(Slices.utf8Slice("x*?")), "xaa", "0:0", "1:1", "2:2", "3:3");
        assertMatchRanges(Re2.compile(Slices.utf8Slice("(?:xy)*")), "xza", "0:0", "1:1", "2:2", "3:3");
        assertMatchRanges(
                Re2.compile(Slices.utf8Slice("|a"), Re2.Options.defaults().setLongestMatch(true)),
                "a",
                "0:1",
                "1:1");
    }

    private static void assertMatchRanges(Re2 pattern, String input, String... expectedRanges)
    {
        RegexpMatcher matcher = pattern.matcher(Slices.utf8Slice(input));
        List<String> actualRanges = new ArrayList<>();
        while (matcher.find()) {
            actualRanges.add(matcher.start() + ":" + matcher.end());
        }
        assertThat(actualRanges).containsExactly(expectedRanges);
    }

    @Test
    public void testMatchModesAndReset()
    {
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("(abc)"))
                .matcher(Slices.utf8Slice("abc"));

        assertThat(matcher.matches()).isTrue();
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("abc");

        matcher.reset(Slices.utf8Slice("abc xyz"));
        assertThat(matcher.lookingAt()).isTrue();
        assertThat(matcher.end()).isEqualTo(3);

        matcher.reset(Slices.utf8Slice("xyz abc"));
        assertThat(matcher.find(4)).isTrue();
        assertThat(matcher.start()).isEqualTo(4);
    }

    @Test
    public void testLogicalRegionMatchesSliceView()
    {
        assertRegionMatchesSliceView("^abc$", Slices.utf8Slice("xabcx"), 1, 4);
        assertRegionMatchesSliceView("(?m)^abc$", Slices.utf8Slice("xabc\ny"), 1, 4);
        assertRegionMatchesSliceView("\\b(abc)\\b", Slices.utf8Slice("xabcx"), 1, 4);
        assertRegionMatchesSliceView("", Slices.utf8Slice("x💰y"), 1, 5);
        assertRegionMatchesSliceView("^$", Slices.utf8Slice("xy"), 1, 1);
        assertRegionMatchesSliceView("(abc)$", Slices.utf8Slice("xabc\r\ny"), 1, 4);

        byte[] malformedUtf8 = {'x', 'a', (byte) 0xFF, 'b', 'y'};
        assertRegionMatchesSliceView("a.b", Slices.wrappedBuffer(malformedUtf8), 1, 4);

        byte[] backing = Slices.utf8Slice("padding-xabcx-padding").getBytes();
        Slice nonzeroOffset = Slices.wrappedBuffer(backing, 8, 5);
        assertRegionMatchesSliceView("(abc)", nonzeroOffset, 1, 4);
    }

    @Test
    public void testLogicalRegionValidation()
    {
        Slice originalInput = Slices.utf8Slice("a");
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("a")).matcher(originalInput);
        assertThat(matcher.matches()).isTrue();

        assertThatThrownBy(() -> matcher.reset(Slices.utf8Slice("abc"), -1, 2))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage("region out of bounds: [-1, 2)");
        assertThatThrownBy(() -> matcher.reset(Slices.utf8Slice("abc"), 2, 1))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage("region out of bounds: [2, 1)");
        assertThatThrownBy(() -> matcher.reset(Slices.utf8Slice("zzz"), 0, 4))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage("region out of bounds: [0, 4)");

        assertThat(matcher.group().toStringUtf8()).isEqualTo("a");
    }

    @Test
    public void testLogicalRegionMatchModesAndSnapshot()
    {
        Slice input = Slices.utf8Slice("xabcx");
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("(abc)"))
                .matcher(input)
                .reset(input, 1, 4);

        assertThat(matcher.matches()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(4);
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("abc");
        MatchResult result = matcher.toMatchResult();
        assertThat(result.start(1)).isEqualTo(1);
        assertThat(result.end(1)).isEqualTo(4);
        assertThat(result.groupSlice(1).toStringUtf8()).isEqualTo("abc");

        assertThat(matcher.reset(input, 1, 5).lookingAt()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(4);
    }

    @Test
    public void testLogicalRegionUsesSingleByteMatcher()
    {
        Slice input = Slices.utf8Slice("x,a,y");
        Re2 pattern = Re2.compile(Slices.utf8Slice("([,;])"));
        RegexpMatcher matcher = pattern.groupZeroMatcher(input, pattern.sharedSingleByteMatcher())
                .reset(input, 2, 4);

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(3);
        assertThat(matcher.end()).isEqualTo(4);
        assertThat(matcher.find()).isFalse();
    }

    @Test
    public void testSingleByteTableHandsOffToForwardCursor()
    {
        // A find with fewer than CompiledPattern.MINIMUM_LONG_SEARCH_BYTES bytes left reads the
        // single-byte table, which builds no program search, and one with at least that many runs
        // the group-zero forward cursor, whose byte scan the table replaced, on the program's DFA.
        // Each find starts partway into a 1024-byte input, so the bytes left decide, not the
        // input length, and the find that resumes after the match has 9 left. These finds prove
        // the hand-off to a program search. Each pass compiles its patterns afresh, so a DFA that
        // an earlier pass built cannot stand in for this pass's search.
        byte[] bytes = "x".repeat(1024).getBytes(US_ASCII);
        bytes[1014] = ',';
        Slice input = Slices.wrappedBuffer(bytes);
        for (int remaining : List.of(63, 64, 65)) {
            for (CompiledPattern pattern : List.of(
                    Re2.compile(Slices.utf8Slice("[,;]")),
                    JavaRegexp.compile(Slices.utf8Slice("[,;]")),
                    RustRegexp.compile(Slices.utf8Slice("[,;]")),
                    TrinoRegexp.compile(Slices.utf8Slice("[,;]")).re2())) {
                String label = pattern.getClass().getSimpleName() + " " + remaining;
                RegexpMatcher matcher = pattern.matcher(input, 0);
                assertThat(matcher.singleByteMatcherForDiagnostics()).as(label).isNotNull();
                assertThat(matcher.groupZeroForwardCursorEnabledForDiagnostics()).as(label).isTrue();
                assertThat(matcher.find(input.length() - remaining)).as(label).isTrue();
                assertThat(List.of(matcher.start(), matcher.end())).as(label).containsExactly(1014, 1015);
                assertThat(matcher.find()).as(label).isFalse();
                assertProgramSearchUnless(remaining < CompiledPattern.MINIMUM_LONG_SEARCH_BYTES, pattern, label);
            }
        }

        // A find with the comma near the end of an input longer than the forward cursor's
        // productivity check distance proves that cursor runs: its byte scan checks the scan's
        // productivity once, where any other program search makes no check.
        byte[] longBytes = "x".repeat(Dfa.MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES + 1024).getBytes(US_ASCII);
        longBytes[longBytes.length - 10] = ',';
        Slice longInput = Slices.wrappedBuffer(longBytes);
        for (CompiledPattern pattern : List.of(
                Re2.compile(Slices.utf8Slice("[,;]")),
                JavaRegexp.compile(Slices.utf8Slice("[,;]")),
                RustRegexp.compile(Slices.utf8Slice("[,;]")),
                TrinoRegexp.compile(Slices.utf8Slice("[,;]")).re2())) {
            String label = pattern.getClass().getSimpleName();
            RegexpMatcher matcher = pattern.matcher(longInput, 0);
            assertThat(matcher.find(0)).as(label).isTrue();
            assertThat(matcher.start()).as(label).isEqualTo(longBytes.length - 10);
            assertThat(matcher.groupZeroForwardProductivityCheckCountForDiagnostics()).as(label).isPositive();
        }
    }

    @Test
    public void testLiteralAlternationHandsOffToCandidateCursor()
    {
        // A find with fewer than CompiledPattern.MINIMUM_LONG_SEARCH_BYTES bytes left runs the
        // literal-alternation span search, which builds no program search, and one with at least
        // that many runs the candidate cursor, whose start-byte scan the span search replaced.
        // Each find starts partway into a 1024-byte input, so the bytes left decide, not the
        // input length, and the find that resumes after the match has 6 left.
        byte[] bytes = "x".repeat(1024).getBytes(US_ASCII);
        System.arraycopy("WARN".getBytes(US_ASCII), 0, bytes, 1014, 4);
        Slice input = Slices.wrappedBuffer(bytes);
        for (int remaining : List.of(63, 64, 65)) {
            for (CompiledPattern pattern : List.of(
                    Re2.compile(Slices.utf8Slice("ERROR|WARN|FATAL")),
                    JavaRegexp.compile(Slices.utf8Slice("ERROR|WARN|FATAL")),
                    RustRegexp.compile(Slices.utf8Slice("ERROR|WARN|FATAL")),
                    RustRegexp.compile(Slices.utf8Slice("(?-u)ERROR|WARN|FATAL"), RustRegexp.Options.bytes()))) {
                String label = pattern.getClass().getSimpleName() + " " + pattern.pattern().toStringUtf8() + " " + remaining;
                RegexpMatcher matcher = pattern.matcher(input, 0);
                assertThat(matcher.spanSearchForDiagnostics()).as(label).isInstanceOf(LiteralAlternationSpanMatcher.class);
                assertThat(matcher.find(input.length() - remaining)).as(label).isTrue();
                assertThat(List.of(matcher.start(), matcher.end())).as(label).containsExactly(1014, 1018);
                assertThat(matcher.find()).as(label).isFalse();
                if (remaining < CompiledPattern.MINIMUM_LONG_SEARCH_BYTES) {
                    assertThat(matcher.candidateStartRouteCountForDiagnostics()).as(label).isZero();
                    assertNoProgramSearch(pattern, label);
                }
                else {
                    assertThat(matcher.candidateStartRouteCountForDiagnostics()).as(label).isPositive();
                }
            }
        }
    }

    @Test
    public void testFusedPrefixLogicalRegionAndBackingOffset()
    {
        String prefix = "Шерлок Холмс";
        String match = prefix + "123";
        byte[] matchBytes = Slices.utf8Slice(match).getBytes();
        byte[] regionBytes = Slices.utf8Slice(match + "x".repeat(1_100) + match).getBytes();
        byte[] decoyBytes = Slices.utf8Slice(match + " ").getBytes();
        byte[] logicalInput = new byte[(2 * decoyBytes.length) + regionBytes.length];
        System.arraycopy(decoyBytes, 0, logicalInput, 0, decoyBytes.length);
        System.arraycopy(regionBytes, 0, logicalInput, decoyBytes.length, regionBytes.length);
        System.arraycopy(decoyBytes, 0, logicalInput, decoyBytes.length + regionBytes.length, decoyBytes.length);

        byte[] backing = new byte[logicalInput.length + 13];
        System.arraycopy(logicalInput, 0, backing, 7, logicalInput.length);
        Slice input = Slices.wrappedBuffer(backing, 7, logicalInput.length);
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice(prefix + "([0-9]+)"))
                .matcher(input)
                .reset(input, decoyBytes.length, decoyBytes.length + regionBytes.length);

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(decoyBytes.length);
        assertThat(matcher.end()).isEqualTo(decoyBytes.length + matchBytes.length);
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("123");

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(decoyBytes.length + regionBytes.length - matchBytes.length);
        assertThat(matcher.end()).isEqualTo(decoyBytes.length + regionBytes.length);
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("123");
        assertThat(matcher.find()).isFalse();
    }

    @Test
    public void testLogicalRegionExhaustiveAgreement()
    {
        Slice input = Slices.utf8Slice(" a\nab💰x\n");
        List<String> expressions = List.of(
                "",
                "^",
                "$",
                "(?m)^a",
                "a$",
                "\\ba\\b",
                "(a|ab)+",
                "(.)",
                "a*?",
                "[^\\n]+",
                "💰?");

        for (String expression : expressions) {
            for (int regionStart = 0; regionStart <= input.length(); regionStart++) {
                for (int regionEnd = regionStart; regionEnd <= input.length(); regionEnd++) {
                    assertRegionMatchesSliceView(expression, input, regionStart, regionEnd);
                }
            }
        }
    }

    private static void assertRegionMatchesSliceView(String expression, Slice input, int regionStart, int regionEnd)
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice(expression));
        RegexpMatcher regionMatcher = pattern.matcher(input).reset(input, regionStart, regionEnd);
        RegexpMatcher viewMatcher = pattern.matcher(input.slice(regionStart, regionEnd - regionStart));

        while (viewMatcher.find()) {
            assertThat(regionMatcher.find()).as("region match for %s", expression).isTrue();
            assertThat(regionMatcher.start()).isEqualTo(regionStart + viewMatcher.start());
            assertThat(regionMatcher.end()).isEqualTo(regionStart + viewMatcher.end());
            assertThat(regionMatcher.groupCount()).isEqualTo(viewMatcher.groupCount());
            for (int group = 0; group <= viewMatcher.groupCount(); group++) {
                assertThat(regionMatcher.matched(group)).isEqualTo(viewMatcher.matched(group));
                int expectedStart = viewMatcher.start(group) < 0 ? -1 : regionStart + viewMatcher.start(group);
                int expectedEnd = viewMatcher.end(group) < 0 ? -1 : regionStart + viewMatcher.end(group);
                assertThat(regionMatcher.start(group)).isEqualTo(expectedStart);
                assertThat(regionMatcher.end(group)).isEqualTo(expectedEnd);
                assertThat(regionMatcher.group(group)).isEqualTo(viewMatcher.group(group));
            }
        }
        assertThat(regionMatcher.find()).as("no extra region match for %s", expression).isFalse();
    }

    @Test
    public void testSnapshotIsIndependentOfLaterFinds()
    {
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("(\\w+)"))
                .matcher(Slices.utf8Slice("one two"));

        assertThat(matcher.find()).isTrue();
        MatchResult first = matcher.toMatchResult();
        assertThat(matcher.find()).isTrue();

        assertThat(first.groupSlice(1).toStringUtf8()).isEqualTo("one");
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("two");
    }

    @Test
    public void testUnmatchedGroupAndMatchState()
    {
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("(a)|(b)"))
                .matcher(Slices.utf8Slice("a"));

        assertThatThrownBy(matcher::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("no successful match");

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.matched(1)).isTrue();
        assertThat(matcher.matched(2)).isFalse();
        assertThat(matcher.group(2)).isNull();
    }

    @Test
    public void testResetInvalidatesWithoutClearingCaptureBuffer()
    {
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("(a)"))
                .matcher(Slices.utf8Slice("a"));

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.groupOffsetForDiagnostics(0)).isZero();
        assertThat(matcher.groupOffsetForDiagnostics(1)).isEqualTo(1);

        matcher.reset(Slices.utf8Slice("b"));

        assertThatThrownBy(matcher::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("no successful match");
        assertThat(matcher.groupOffsetForDiagnostics(0)).isZero();
        assertThat(matcher.groupOffsetForDiagnostics(1)).isEqualTo(1);
    }

    private static final class UnreadableMatchWorkspaces
            implements CompiledPattern.MatchWorkspaces {}
}
