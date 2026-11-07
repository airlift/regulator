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
import io.airlift.slice.SizeOf;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDfaCandidateStartCursor
{
    // Longer than the literal cursor's minimum window, with words, spaces, and punctuation.
    private static final String FILLER_UNIT = "filler text, ";
    private static final String FILLER = FILLER_UNIT.repeat(Dfa.MINIMUM_LITERAL_CANDIDATE_SEARCH_BYTES / FILLER_UNIT.length() + 1);

    @Test
    public void testCandidateCursorReturnsBoundariesWithoutReverseProgram()
    {
        Re2 pattern = compile("a+z|b+");
        RegexpMatcher matcher = pattern.groupZeroMatcher(utf8("xxaaazyybbb"), null);

        assertThat(matcher.candidateStartCursorEnabledForDiagnostics()).isTrue();
        assertThat(boundaries(matcher)).containsExactly(new Boundary(2, 6), new Boundary(8, 11));
        assertThat(matcher.candidateStartRouteCountForDiagnostics()).isEqualTo(2);
        assertThat(matcher.candidateStartFallbackCountForDiagnostics()).isZero();
        assertThat(pattern.isReverseProgramComputed()).isFalse();
    }

    @Test
    public void testCandidateCursorPreservesFirstMatchPriority()
    {
        assertCandidateAgrees("a|ab|b", "xab");
        assertCandidateAgrees("ab|a|b", "xab");
        assertCandidateAgrees("a+|b", "xaaay");
        assertCandidateAgrees("a+?|b", "xaaay");
        assertCandidateAgrees("ab|b", "acb");
        assertCandidateAgrees("a.*z|b", "acb");
    }

    @Test
    public void testCandidateCursorUsesCachedStateAtEndOfText()
    {
        Re2 pattern = compile("(a+|b+)");
        assertThat(boundaries(pattern.groupZeroMatcher(utf8("xxxxb"), null)))
                .containsExactly(new Boundary(4, 5));

        RegexpMatcher matcher = pattern.groupZeroMatcher(utf8("xxxxxxxxxxxxxxb"), null);
        assertThat(matcher.find(14)).isTrue();
        assertThat(matcher.start()).isEqualTo(14);
        assertThat(matcher.end()).isEqualTo(15);
    }

    @Test
    public void testCandidateCursorBulkScansSparseCapturePattern()
    {
        TestingTrinoRegexpBenchmarkInputs.Input input = TestingTrinoRegexpBenchmarkInputs.create("captureSparse", 32_768);
        // A class rather than the benchmark pattern's literal hyphen keeps the inner literal search
        // from taking it, with the same matches.
        Re2 pattern = compile("([a-z]+)[-+]([0-9]+)");
        RegexpMatcher matcher = pattern.groupZeroMatcher(input.source(), null);

        assertThat(boundaries(matcher)).containsExactly(
                new Boundary(8_192, 8_199),
                new Boundary(16_384, 16_391),
                new Boundary(24_576, 24_583));
        assertThat(matcher.candidateStartRouteCountForDiagnostics()).isEqualTo(3);
        assertThat(matcher.candidateStartFallbackCountForDiagnostics()).isZero();
        assertThat(pattern.isReverseProgramComputed()).isFalse();
    }

    @Test
    public void testCandidateCursorRejectsDenseStartBytes()
    {
        // A class rather than a literal @ keeps the inner literal search from taking the pattern.
        Re2 pattern = compile("\\w+[@#]\\w+");
        Slice input = utf8("identifier ".repeat(8_192) + "a@b");
        RegexpMatcher matcher = pattern.groupZeroMatcher(input, null);

        assertThat(boundaries(matcher)).containsExactly(new Boundary(input.length() - 3, input.length()));
        assertThat(matcher.candidateStartProductivityRejectionCountForDiagnostics()).isEqualTo(1);
        assertThat(matcher.candidateStartRouteCountForDiagnostics()).isZero();
        assertThat(matcher.candidateStartFallbackCountForDiagnostics()).isEqualTo(1);
    }

    @Test
    public void testCandidateCursorBoundsBulkScanToRemainingWork()
    {
        Dfa.CandidateStartCursor cursor = compile("a+z|b+").createCandidateStartCursor();
        cursor.reset(4);

        assertThat(cursor.workBoundedScanLength(3)).isEqualTo(3);
        assertThat(cursor.workBoundedScanLength(100)).isEqualTo(12);
    }

    @Test
    public void testCandidateCursorPreservesRegionsAndUtf8()
    {
        Slice input = utf8("padding-x💰💰y-padding");
        Re2 pattern = compile("(💰+|y)");
        RegexpMatcher candidate = pattern.groupZeroMatcher(input, null).reset(input, 9, 18);
        RegexpMatcher control = pattern.matcher(input.slice(9, 9));

        assertThat(candidate.candidateStartCursorEnabledForDiagnostics()).isTrue();
        assertThat(boundaries(candidate)).isEqualTo(shifted(boundaries(control), 9));

        byte[] malformed = {'x', 'a', (byte) 0xFF, 'b', 'y'};
        assertCandidateAgrees("a+|b+", Slices.wrappedBuffer(malformed));

        // A region is its own context, so a line start or word boundary at its first byte holds
        // whether the region starts after LF or mid-line.
        Slice lines = utf8(FILLER + "\nab x\nab" + FILLER + "xab\nab");
        for (String expression : List.of("(?m)^ab", "\\bab", "\\Bab", "(?m)^ab|ab$")) {
            Re2 linePattern = compile(expression);
            for (int regionStart : List.of(FILLER.length() + 1, FILLER.length() + 2)) {
                RegexpMatcher lineCandidate = linePattern.groupZeroMatcher(lines, null).reset(lines, regionStart, lines.length());
                List<Boundary> expected = ordinaryBoundaries(expression, lines.slice(regionStart, lines.length() - regionStart));
                assertThat(lineCandidate.candidateStartCursorSearchesLiteralForDiagnostics()).as(expression).isTrue();
                assertThat(boundaries(lineCandidate)).as("%s at %s", expression, regionStart).isEqualTo(shifted(expected, regionStart));
            }
        }

        // Truncated UTF-8 sequences before the literal are non-word bytes that start no line.
        byte[] filler = FILLER.getBytes(UTF_8);
        for (byte[] truncated : List.of(new byte[] {(byte) 0xC3}, new byte[] {(byte) 0xE2, (byte) 0x80})) {
            Slice text = new DynamicSliceOutput(filler.length + 4).appendBytes(filler).appendBytes(truncated).appendBytes("ab".getBytes(UTF_8)).slice();
            for (String expression : List.of("(?m)^ab|ab$", "\\bab|ab\\b", "\\Bab|ab\\B")) {
                assertLiteralCandidateAgrees(expression, text);
            }
        }

        Slice multibyteStart = utf8("x💰a");
        Re2 insideCodePoint = compile("(a+|💰+)");
        RegexpMatcher insideCodePointCandidate = insideCodePoint.groupZeroMatcher(multibyteStart, null);
        RegexpMatcher insideCodePointControl = insideCodePoint.matcher(multibyteStart);
        assertThat(insideCodePointCandidate.find(2)).isEqualTo(insideCodePointControl.find(2));
        assertThat(insideCodePointCandidate.start()).isEqualTo(insideCodePointControl.start());
        assertThat(insideCodePointCandidate.end()).isEqualTo(insideCodePointControl.end());
    }

    @Test
    public void testCandidateCursorFallsBackUntilReset()
    {
        Re2 pattern = compile("(a.*z|b)");
        Slice input = utf8("a" + "x".repeat(1_000) + "b");
        RegexpMatcher matcher = pattern.groupZeroMatcher(input, null);

        assertThat(boundaries(matcher)).containsExactly(new Boundary(1_001, 1_002));
        assertThat(matcher.candidateStartFallbackCountForDiagnostics()).isEqualTo(1);
        assertThat(pattern.isReverseProgramComputed()).isTrue();

        matcher.reset(utf8("xxaz"));
        assertThat(matcher.candidateStartCursorEnabledForDiagnostics()).isTrue();
        assertThat(boundaries(matcher)).containsExactly(new Boundary(2, 4));
        assertThat(matcher.candidateStartRouteCountForDiagnostics()).isEqualTo(1);

        matcher.reset(input);
        assertThat(boundaries(matcher)).containsExactly(new Boundary(1_001, 1_002));
        assertThat(matcher.candidateStartCursorEnabledForDiagnostics()).isFalse();
        assertThat(matcher.find(1_001)).isTrue();
        assertThat(matcher.candidateStartRouteCountForDiagnostics()).isEqualTo(2);
    }

    @Test
    public void testCandidateCursorScansMatchingSelfLoop()
    {
        // `[0-9]+` stands in for the cloud-flare ReDoS pattern's leading alternatives. Once `=` is
        // read, each following ASCII byte but a newline extends the match, and the check scans that
        // run rather than stepping each byte.
        for (Re2.Options options : List.of(Re2.Options.defaults(), Re2.Options.latin1())) {
            Re2 pattern = Re2.compile(utf8("[0-9]+.*=.*"), options);
            Slice input = utf8("1 x=" + "y".repeat(100));
            RegexpMatcher matcher = pattern.groupZeroMatcher(input, null);

            assertThat(boundaries(matcher)).containsExactly(new Boundary(0, input.length()));
            assertThat(matcher.candidateStartRouteCountForDiagnostics()).isEqualTo(1);
            assertThat(matcher.candidateStartFallbackCountForDiagnostics()).isZero();
            assertThat(pattern.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).matchingSelfLoopScanCount()).isPositive();
        }

        // A run ends at a newline and at a multibyte character.
        assertCandidateAgrees("[0-9]+.*=.*", "1=" + "y".repeat(50) + "\n2=y");
        assertCandidateAgrees("[0-9]+.*=.*", "1=" + "y".repeat(50) + "é" + "y".repeat(50));

        // The match [1, 102) ends inside the run, one byte before the newline ends it, so its end
        // comes from the scan.
        Re2 wordBounded = assertLiteralCandidateAgrees("\\Bab[a-z]*\\B", utf8("xab" + "y".repeat(100) + "\n"));
        assertThat(wordBounded.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).matchingSelfLoopScanCount()).isEqualTo(1);

        // The scanned run is charged to the work budget, which runs out within it here.
        Slice workLimitedInput = utf8("aaaaaa\n".repeat(15) + "b=y" + "y".repeat(100));
        Re2 workLimitedPattern = compile("a.*z|b+=y.*");
        RegexpMatcher workLimited = workLimitedPattern.groupZeroMatcher(workLimitedInput, null);
        assertThat(boundaries(workLimited)).containsExactly(new Boundary(105, workLimitedInput.length()));
        assertThat(workLimited.candidateStartRouteCountForDiagnostics()).isZero();
        assertThat(workLimited.candidateStartFallbackCountForDiagnostics()).isEqualTo(1);
        assertThat(workLimitedPattern.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).matchingSelfLoopScanCount()).isPositive();
    }

    @Test
    public void testCandidateCursorFallsBackAtTransitionLimit()
    {
        // A check falls back once it takes the transition limit, whether the step loop or a
        // matching self-loop's scan reaches it, and one byte short of the limit it returns the
        // match itself.
        int limit = Dfa.MAX_CANDIDATE_START_TRANSITIONS;
        for (Re2.Options options : List.of(Re2.Options.defaults(), Re2.Options.latin1())) {
            for (int length : List.of(limit - 1, limit, limit + 1)) {
                assertCandidateTransitionLimit("a+z|b+", "a".repeat(length - 1) + "z", options);
                assertCandidateTransitionLimit("[0-9]+.*=.*", "1=" + "y".repeat(length - 2), options);
            }
            // The self-loop is first seen at the last byte, so its scan is empty.
            assertCandidateTransitionLimit("[0-9]+.*=.*", "1" + "x".repeat(limit - 4) + "=yy", options);
        }
    }

    @Test
    public void testCandidateCursorFallsBackAfterCheckReadsToEnd()
    {
        // A check that reaches the end of the text alive and unmatched has read every later
        // candidate, so from 16 bytes on, the forward search takes over rather than the cursor
        // checking them again. The text starts with the `=` every match holds, ahead of the
        // candidate, so the short-window check lets the cursor run.
        Re2 pattern = compile("[0-9]+.*=.*");
        RegexpMatcher matcher = pattern.groupZeroMatcher(utf8("=1" + "y".repeat(Dfa.MINIMUM_UNMATCHED_CANDIDATE_FALLBACK_BYTES - 1)), null);

        assertThat(matcher.find()).isFalse();
        assertThat(matcher.candidateStartFallbackCountForDiagnostics()).isEqualTo(1);
        assertThat(matcher.candidateStartCursorEnabledForDiagnostics()).isFalse();

        matcher.reset(utf8("=1" + "y".repeat(Dfa.MINIMUM_UNMATCHED_CANDIDATE_FALLBACK_BYTES - 2)));
        assertThat(matcher.find()).isFalse();
        assertThat(matcher.candidateStartFallbackCountForDiagnostics()).isEqualTo(1);
        assertThat(matcher.candidateStartCursorEnabledForDiagnostics()).isTrue();
    }

    @Test
    public void testCandidateCursorRemainsCorrectWithConcurrentCacheResets()
            throws Exception
    {
        Re2 pattern = compile("a+z|b+");
        Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        Slice input = utf8("xxaaazyybbb");
        List<Boundary> expected = List.of(new Boundary(2, 6), new Boundary(8, 11));
        CountDownLatch startGate = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> searches = executor.submit(() -> {
                await(startGate);
                for (int iteration = 0; iteration < 100; iteration++) {
                    assertThat(boundaries(pattern.groupZeroMatcher(input, null))).isEqualTo(expected);
                }
            });
            Future<?> resets = executor.submit(() -> {
                await(startGate);
                for (int iteration = 0; iteration < 100; iteration++) {
                    dfa.resetCacheExternal();
                }
            });

            startGate.countDown();
            searches.get(10, TimeUnit.SECONDS);
            resets.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testCandidateCursorReadsGrownTransitionTable()
    {
        // A transition computed during a check can grow the transition table, and the check's
        // later steps must read the new table. This search grows it from a fresh cache, and the
        // check at the first byte finds the match.
        Re2 pattern = Re2.compile(utf8("([ab]*a[ab]{9}[zq])"), Re2.Options.defaults().setMaxMemory(16 << 20));
        Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        int initialTransitions = dfa.cacheTablesForTesting().transitions.length;
        String text = "bbbaaabbabaabbbbaabababaababbbbaabaabbbaaaaabbbaabbabbbaababbbabaababaabbbbbbbbbz";
        RegexpMatcher matcher = pattern.groupZeroMatcher(utf8(text), null);

        assertThat(matcher.candidateStartCursorEnabledForDiagnostics()).isTrue();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isEqualTo(text.length());
        assertThat(matcher.candidateStartRouteCountForDiagnostics()).isEqualTo(1);
        assertThat(matcher.candidateStartFallbackCountForDiagnostics()).isZero();
        assertThat(dfa.cacheTablesForTesting().transitions.length).isGreaterThan(initialTransitions);
    }

    @Test
    public void testLiteralCandidateCursorVerifiesAnchorsAtCandidates()
    {
        // Each candidate's start state comes from the byte before it, so a leading line or word
        // assertion is judged at a line start, mid-line, after LF and CR LF, before them, and at
        // the end of the text. The tails of the last two patterns follow the literal. Each
        // alternation keeps the inner literal search out.
        for (LiteralCase literalCase : List.of(
                new LiteralCase("(?m)^Sherlock Holmes|Sherlock Holmes$", "Sherlock Holmes"),
                new LiteralCase("(?m)^ab|ab\\b", "ab"),
                new LiteralCase("\\bab|ab\\b", "ab"),
                new LiteralCase("\\Bab|ab\\B", "ab"),
                new LiteralCase("(?m)^ab|ab$", "ab"),
                new LiteralCase("(?m)^Sherlock \\w+|Sherlock Holmes$", "Sherlock Holmes"),
                new LiteralCase("\\bHolmes[a-z]*|Holmes$", "Holmes"))) {
            String literal = literalCase.literal();
            List<String> texts = List.of(
                    literal + FILLER,
                    FILLER + "x" + literal + "x " + literal + "s" + FILLER,
                    FILLER + "\n" + literal + FILLER,
                    FILLER + "\r\n" + literal + FILLER,
                    FILLER + literal + "\n" + FILLER,
                    FILLER + literal + "\r\n" + FILLER,
                    FILLER + literal,
                    literal + "\n" + FILLER + "x" + literal + "\r\n" + literal + " " + literal + "\n" + FILLER + literal);
            for (String text : texts) {
                assertLiteralCandidateAgrees(literalCase.expression(), utf8(text));
            }
        }
        // The literal occurs mid-line, right after a failed line-start candidate.
        assertLiteralCandidateAgrees("(?m)^aab", utf8(FILLER + "\naaab"));
    }

    @Test
    public void testLiteralCandidateCursorKeepsProseAndFallsBackOnDenseCandidates()
    {
        // A mid-line candidate of a line start fails in its start state, so prose such as this
        // costs one unit of work per candidate beyond the bytes scanned and stays within the
        // budget, without the reverse program. The literal is near every search start, so the
        // inner literal search leaves the searches to the cursor.
        Re2 prosePattern = compile("(?m)^the");
        Slice prose = utf8("the theme of the other thesis then, the theory there; ".repeat(40) + "\nthe end");
        RegexpMatcher proseMatcher = prosePattern.groupZeroMatcher(prose, null);

        assertThat(boundaries(proseMatcher)).isEqualTo(ordinaryBoundaries("(?m)^the", prose));
        assertThat(proseMatcher.candidateStartRouteCountForDiagnostics()).isEqualTo(2);
        assertThat(proseMatcher.candidateStartFallbackCountForDiagnostics()).isZero();
        assertThat(prosePattern.isReverseProgramComputed()).isFalse();

        // A candidate whose verification reads past the next one exhausts the budget, and the
        // fallback finds the same matches. An alternation keeps the inner literal search out.
        Re2 densePattern = compile("\\Bab[a-z]*X|ab$");
        Slice dense = utf8(("ab".repeat(30) + " ").repeat(20) + "xabX");
        RegexpMatcher denseMatcher = densePattern.groupZeroMatcher(dense, null);

        assertThat(boundaries(denseMatcher)).isEqualTo(ordinaryBoundaries("\\Bab[a-z]*X|ab$", dense));
        assertThat(denseMatcher.candidateStartRouteCountForDiagnostics()).isZero();
        assertThat(denseMatcher.candidateStartFallbackCountForDiagnostics()).isEqualTo(1);
        assertThat(denseMatcher.candidateStartCursorEnabledForDiagnostics()).isFalse();
    }

    @Test
    public void testLiteralCandidateCursorLeavesShortWindowsToExistingMatcher()
    {
        Re2 pattern = compile("(?m)^ab");
        Slice input = utf8("x\nab\nab ab");
        RegexpMatcher matcher = pattern.groupZeroMatcher(input, null);

        assertThat(boundaries(matcher)).containsExactly(new Boundary(2, 4), new Boundary(5, 7));
        assertThat(matcher.candidateStartRouteCountForDiagnostics()).isZero();
        assertThat(matcher.candidateStartFallbackCountForDiagnostics()).isZero();
        assertThat(matcher.candidateStartCursorEnabledForDiagnostics()).isTrue();
    }

    @Test
    public void testIneligiblePatternsUseExistingMatcher()
    {
        assertThat(compile("a*").groupZeroMatcher(utf8("aaa"), null).candidateStartCursorEnabledForDiagnostics()).isFalse();
        assertThat(compile("^a+").groupZeroMatcher(utf8("aaa"), null).candidateStartCursorEnabledForDiagnostics()).isFalse();
        assertThat(compile("a+$").groupZeroMatcher(utf8("aaa"), null).candidateStartCursorEnabledForDiagnostics()).isFalse();
        assertThat(compile("a{2}").groupZeroMatcher(utf8("aaa"), null).candidateStartCursorEnabledForDiagnostics()).isFalse();
        assertThat(compile("\\ba+").groupZeroMatcher(utf8("aaa"), null).candidateStartCursorEnabledForDiagnostics()).isFalse();
        // A one-byte literal, a folded literal, no common literal, a possibly empty match, a plain
        // literal, and an end anchor leave the literal cursor out. The DFA runs RE2's assertions,
        // so no group-zero NFA skips to the literal either, and compile keeps none.
        for (String expression : List.of("(?m)^a", "(?i)\\bfoo", "(?m)^foo|bar", "\\bfoo|", "foo", "\\bfoo$")) {
            Re2 pattern = compile(expression);
            assertThat(pattern.forwardProgramForDiagnostics().transparentStartLiteral()).as(expression).isNull();
            assertThat(pattern.groupZeroMatcher(utf8("aaa"), null).candidateStartCursorEnabledForDiagnostics()).as(expression).isFalse();
        }
    }

    @Test
    public void testLiteralMemoryIsReservedFromForwardDfa()
    {
        // The cursor serves only first-match searches, so a longest-match compile keeps no literal,
        // and its forward DFA allowance is the first-match compile's before the literal's charge.
        // The literal is kept only where that charge leaves both forward DFAs their minimum. The
        // required bytes are reserved after it, and only where each forward DFA that initializes
        // without them still does, so the sweep passes budgets that keep them and budgets that
        // drop them.
        Slice expression = utf8("(?m)^ab|ab$");
        Re2 pattern = Re2.compile(expression);
        Prog program = pattern.forwardProgramForDiagnostics();
        long reservation = program.transparentStartLiteralSearch().estimatedRetainedSize() + SizeOf.sizeOf(program.transparentStartLiteral());
        long requiredBytesSize = pattern.requiredBytesRetainedSizeForDiagnostics();
        assertThat(pattern.booleanProgramForDiagnostics()).isNull();
        assertThat(requiredBytesSize).isPositive();
        List<Boolean> outcomes = new ArrayList<>();
        List<Boolean> requiredBytesOutcomes = new ArrayList<>();
        for (long maxMemory = 4 * 1024; maxMemory <= 32 * 1024; maxMemory += 64) {
            Re2 longestPattern = Re2.compile(expression, Re2.Options.defaults().setLongestMatch(true).setMaxMemory(maxMemory));
            Prog longest = longestPattern.forwardProgramForDiagnostics();
            Re2 firstMatchPattern = Re2.compile(expression, Re2.Options.defaults().setMaxMemory(maxMemory));
            Prog firstMatch = firstMatchPattern.forwardProgramForDiagnostics();
            long allowance = longest.dfaMemory() + longestPattern.requiredBytesRetainedSizeForDiagnostics();
            boolean keeps = (allowance - reservation) / 2 >= Dfa.DfaInstance.minimumMemory(longest, Dfa.DfaInstance.Kind.LONGEST_MATCH);
            assertThat(firstMatch.transparentStartLiteral() != null).as("keeps at %s", maxMemory).isEqualTo(keeps);
            assertThat(allowance - firstMatch.dfaMemory() - firstMatchPattern.requiredBytesRetainedSizeForDiagnostics())
                    .as("reservation at %s", maxMemory)
                    .isEqualTo(keeps ? reservation : 0);
            requiredBytesOutcomes.add(assertRequiredBytesKeepDfaInitialization(longestPattern, requiredBytesSize, "longest-match at " + maxMemory));
            requiredBytesOutcomes.add(assertRequiredBytesKeepDfaInitialization(firstMatchPattern, requiredBytesSize, "first-match at " + maxMemory));
            outcomes.add(keeps);
        }
        assertThat(outcomes).contains(false, true);
        assertThat(requiredBytesOutcomes).contains(false, true);
    }

    // Asserts that the pattern keeps its required bytes exactly where reserving
    // `requiredBytesSize` from the allowance it had before their charge leaves each forward DFA
    // that initialized able to, and that each such DFA initializes with what every charge leaves.
    // Returns whether the pattern keeps them.
    private static boolean assertRequiredBytesKeepDfaInitialization(Re2 pattern, long requiredBytesSize, String description)
    {
        Prog program = pattern.forwardProgramForDiagnostics();
        long allowance = program.dfaMemory() + pattern.requiredBytesRetainedSizeForDiagnostics();
        boolean keeps = true;
        for (Dfa.DfaInstance.Kind kind : List.of(Dfa.DfaInstance.Kind.FIRST_MATCH, Dfa.DfaInstance.Kind.LONGEST_MATCH)) {
            if (initializes(program, kind, allowance / 2)) {
                assertThat(program.getCachedDfa(kind)).as("%s %s", kind, description).isNotNull();
                keeps &= initializes(program, kind, (allowance - requiredBytesSize) / 2);
            }
        }
        assertThat(pattern.requiredBytesForDiagnostics() != null).as("required bytes %s", description).isEqualTo(keeps);
        return keeps;
    }

    private static boolean initializes(Prog program, Dfa.DfaInstance.Kind kind, long maxMemory)
    {
        return maxMemory > 0 && new Dfa.DfaInstance(program, kind, maxMemory).ok();
    }

    private static void assertCandidateTransitionLimit(String expression, String input, Re2.Options options)
    {
        boolean fallsBack = input.length() >= Dfa.MAX_CANDIDATE_START_TRANSITIONS;
        Re2 pattern = Re2.compile(utf8(expression), options);
        // The first search fills a fresh cache and the second steps through its cached transitions.
        for (String cache : List.of("fresh", "warm")) {
            RegexpMatcher matcher = pattern.groupZeroMatcher(utf8(input), null);
            String description = expression + " on " + input.length() + " bytes with a " + cache + " cache";
            assertThat(boundaries(matcher)).as(description).containsExactly(new Boundary(0, input.length()));
            assertThat(matcher.candidateStartRouteCountForDiagnostics()).as(description).isEqualTo(fallsBack ? 0 : 1);
            assertThat(matcher.candidateStartFallbackCountForDiagnostics()).as(description).isEqualTo(fallsBack ? 1 : 0);
        }
    }

    private static Re2 assertLiteralCandidateAgrees(String expression, Slice input)
    {
        Re2 pattern = compile("(" + expression + ")");
        RegexpMatcher candidate = pattern.groupZeroMatcher(input, null);
        List<Boundary> expected = ordinaryBoundaries(expression, input);

        String description = expression + " on " + input.toStringUtf8();
        assertThat(candidate.candidateStartCursorSearchesLiteralForDiagnostics()).as(description).isTrue();
        assertThat(boundaries(candidate)).as(description).isEqualTo(expected);
        assertThat(candidate.candidateStartFallbackCountForDiagnostics()).as(description).isZero();
        assertThat(candidate.candidateStartRouteCountForDiagnostics() > 0).as(description).isEqualTo(!expected.isEmpty());
        return pattern;
    }

    // A capturing matcher takes the ordinary path rather than the candidate cursor.
    private static List<Boundary> ordinaryBoundaries(String expression, Slice input)
    {
        RegexpMatcher control = compile("(" + expression + ")").matcher(input);
        assertThat(control.candidateStartCursorEnabledForDiagnostics()).isFalse();
        return boundaries(control);
    }

    private static List<Boundary> shifted(List<Boundary> boundaries, int offset)
    {
        return boundaries.stream().map(boundary -> new Boundary(boundary.start() + offset, boundary.end() + offset)).toList();
    }

    private static void assertCandidateAgrees(String expression, String input)
    {
        assertCandidateAgrees(expression, utf8(input));
    }

    private static void assertCandidateAgrees(String expression, Slice input)
    {
        Re2 pattern = compile("(" + expression + ")");
        RegexpMatcher candidate = pattern.groupZeroMatcher(input, null);
        RegexpMatcher control = pattern.matcher(input);

        assertThat(candidate.candidateStartCursorEnabledForDiagnostics()).isTrue();
        assertThat(boundaries(candidate)).isEqualTo(boundaries(control));
    }

    private static List<Boundary> boundaries(RegexpMatcher matcher)
    {
        List<Boundary> boundaries = new ArrayList<>();
        while (matcher.find()) {
            boundaries.add(new Boundary(matcher.start(), matcher.end()));
        }
        return boundaries;
    }

    private static Re2 compile(String expression)
    {
        return Re2.compile(utf8(expression));
    }

    private static Slice utf8(String value)
    {
        return Slices.utf8Slice(value);
    }

    private static void await(CountDownLatch latch)
    {
        try {
            latch.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private record Boundary(int start, int end) {}

    private record LiteralCase(String expression, String literal) {}
}
