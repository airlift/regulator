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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
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
    // Languages whose word boundaries decode characters, so their DFA quits next to a byte outside
    // ASCII. Java runs with Unicode case and character classes.
    private static final List<String> QUITTING_LANGUAGES = List.of("Java", "Trino", "Rust");

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

        // A folded-run cursor's boundaries are relative to a region of a view that starts inside
        // its backing array.
        String runExpression = "\\bthe (?i)Sherlock Holmes";
        String viewText = "ignored the Sherlock Holmes " + FILLER;
        Slice view = utf8("padding" + viewText + "trailer").slice("padding".length(), viewText.length());
        int runRegionStart = "ignored ".length();
        RegexpMatcher runCandidate = compile(runExpression).groupZeroMatcher(view, null).reset(view, runRegionStart, view.length());
        assertThat(runCandidate.candidateStartCursorSearchesFoldedStartRunForDiagnostics()).isTrue();
        assertThat(boundaries(runCandidate))
                .containsExactly(new Boundary(8, 27))
                .isEqualTo(shifted(ordinaryBoundaries(runExpression, view.slice(runRegionStart, view.length() - runRegionStart)), runRegionStart));
        assertThat(runCandidate.candidateStartRouteCountForDiagnostics()).isEqualTo(1);
        assertThat(runCandidate.candidateStartFoldedRunSearchCountForDiagnostics()).isPositive();

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
            Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
            assertThat(dfa.matchingSelfLoopScanCount()).isPositive();
            assertThat(dfa.matchingSelfLoopExitByteScanCount() > 0).isEqualTo(VectorSupport.isAvailable());
        }

        // A run ends at a newline and at a multibyte character.
        assertCandidateAgrees("[0-9]+.*=.*", "1=" + "y".repeat(50) + "\n2=y");
        assertCandidateAgrees("[0-9]+.*=.*", "1=" + "y".repeat(50) + "é" + "y".repeat(50));

        // The match [1, 102) ends inside the run, one byte before the newline ends it, so its end
        // comes from the scan.
        Re2 wordBounded = assertLiteralCandidateAgrees("\\Bab[a-z]*\\B", utf8("xab" + "y".repeat(100) + "\n"));
        assertThat(wordBounded.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).matchingSelfLoopScanCount()).isEqualTo(1);

        // The scanned run is charged to the work budget, which runs out within it here: after 15
        // lines within the run's stepped prefix, and after 14 within its vector scan.
        for (int lines : List.of(14, 15)) {
            Slice workLimitedInput = utf8("aaaaaa\n".repeat(lines) + "b=y" + "y".repeat(100));
            Re2 workLimitedPattern = compile("a.*z|b+=y.*");
            RegexpMatcher workLimited = workLimitedPattern.groupZeroMatcher(workLimitedInput, null);
            Dfa.DfaInstance workLimitedDfa = workLimitedPattern.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
            assertThat(boundaries(workLimited)).containsExactly(new Boundary(7 * lines, workLimitedInput.length()));
            assertThat(workLimited.candidateStartRouteCountForDiagnostics()).isZero();
            assertThat(workLimited.candidateStartFallbackCountForDiagnostics()).isEqualTo(1);
            assertThat(workLimitedDfa.matchingSelfLoopScanCount()).isPositive();
            assertThat(workLimitedDfa.matchingSelfLoopExitByteScanCount() > 0).isEqualTo(lines == 14 && VectorSupport.isAvailable());
        }
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
    public void testFoldedStartRunCursorSearchesRarestRunRarerThanLiteral()
    {
        // `the ` is common in English and the folded run ` holme` behind it far rarer, so the
        // cursor searches for the run, 12 to 15 bytes in, past the `s` and `k` that may be the
        // long s and the Kelvin sign. The earlier `herloc` is also rare enough, but ` holme` is
        // rarer. Java's ASCII folding makes ` sherlock` one run 3 bytes in. A caller distribution
        // under which `c` never occurs makes `herloc` the rarest.
        String expression = "\\bthe (?i)Sherlock Holmes";
        for (String language : List.of("RE2", "Rust", "Java (?u)", "Trino")) {
            assertFoldedStartRun(language + " " + expression, compilePattern(language, expression), " holme", 12, 15, 1, 4);
        }
        assertFoldedStartRun("Java " + expression, compilePattern("Java", expression), " sherlock", 3, 3, 5, 8);
        long[] counts = new long[256];
        Arrays.fill(counts, 1_000);
        counts['c'] = 0;
        counts['C'] = 0;
        Re2 withoutC = Re2.compile(utf8(expression), Re2.Options.defaults().setByteFrequencies(ByteFrequencies.fromCounts(counts)));
        assertFoldedStartRun("RE2 " + expression + " without c", withoutC, "herloc", 5, 6, 0, 5);

        // A run must be more than four times rarer than the literal, so `ab` keeps the literal
        // cursor against `ernel` and Java's `kernel`, `got` against the `you` of `\bgot(?i)syou`
        // and Java's `syou`, and `the` against Java's `this`; the `thi` of `\bthe(?i)this` is
        // led by the common `t`, so no other language finds a run.
        for (String rejected : List.of("\\bab(?i)kernel", "\\bthe(?i)this", "\\bgot(?i)syou")) {
            for (String language : List.of("RE2", "Java", "Rust", "Trino")) {
                String description = language + " " + rejected;
                RegexpMatcher matcher = compilePattern(language, rejected).groupZeroMatcher(utf8(FILLER), null);
                assertThat(matcher.candidateStartCursorSearchesLiteralForDiagnostics()).as(description).isTrue();
                assertThat(matcher.candidateStartCursorSearchesFoldedStartRunForDiagnostics()).as(description).isFalse();
            }
        }
    }

    @Test
    public void testFoldedStartRunCursorVerifiesEveryStartTheRunAllows()
    {
        // Each start a run allows is verified from the byte before it, so `\b` and `\B` are judged
        // at the text start, after a space, and after a word byte, as in `bathe`, for matches in
        // mixed case and with the wider folds, back to back, and in windows on both sides of the
        // cursor's minimum and of the vector scan's. The space of `the ` is a run byte, so a
        // three-byte run behind a start literal needs the folded word to follow the literal
        // directly, as the `wat` of `\bthe(?i)watson`, led by the rare `w`, does.
        for (FoldedRunCase runCase : List.of(
                new FoldedRunCase("\\bthe (?i)Sherlock Holmes", List.of("the Sherlock Holmes", "the SHERLOCK holmes", "the \u017Fherloc\u212A Holmes"), "bathe sherlock holmes"),
                new FoldedRunCase("\\Bhe (?i)Sherlock Holmes", List.of("bathe sHeRlOcK HoLmEs", "athe \u017FHERLOC\u212A HOLMES"), "a he sherlock holmes"),
                new FoldedRunCase("\\bthe(?i)watson", List.of("thewatson", "theWATSON", "thewat\u017Fon"), "bathewatson"))) {
            List<String> texts = TestPrefixProbeAcceleration.foldedRunTexts(runCase.spellings(), runCase.nearMiss());
            for (String language : List.of("RE2", "Java", "Rust", "Trino")) {
                String description = language + " " + runCase.expression();
                CompiledPattern pattern = compilePattern(language, runCase.expression());
                CompiledPattern control = compilePattern(language, "(" + runCase.expression() + ")");
                long routes = 0;
                for (String text : texts) {
                    routes += assertFoldedRunCandidateAgrees(description, pattern, control, utf8(text)).candidateStartRouteCountForDiagnostics();
                }
                assertThat(routes).as(description).isPositive();
            }
        }
    }

    @Test
    public void testFoldedStartRunCursorVerifiesOnlyStartsThatHoldTheLiteral()
    {
        // Behind an opening curly quote, whose bytes lie outside ASCII, the run ` holme` of
        // `abcMr. Sherlock Holmes` allows a start at the `a`, which holds no `the `. Rust, Java
        // with Unicode case, and Trino judge `\b` with a DFA that quits on a start after a byte
        // outside ASCII, and four quits in a row turn the cursor and the DFA off until the next
        // reset. The cursor rules such a start out unverified, so every find routes through it, as
        // with the literal cursor. A start that holds the literal right after the quote still
        // quits and falls back, as with the literal cursor.
        String expression = "\\bthe (?i)Sherlock Holmes";
        String tail = " ".repeat(Dfa.MINIMUM_LITERAL_CANDIDATE_SEARCH_BYTES);
        for (String language : List.of("Rust", "Java (?u)", "Trino")) {
            CompiledPattern pattern = compilePattern(language, expression);
            CompiledPattern control = compilePattern(language, "(" + expression + ")");
            for (RunCursorCase runCase : List.of(
                    new RunCursorCase("\u201CabcMr. Sherlock Holmes. the Sherlock Holmes. ".repeat(4) + tail, 4, 0),
                    new RunCursorCase("\u201Cthe Sherlock Holmes. the Sherlock Holmes. ".repeat(4) + tail, 4, 4))) {
                String description = language + " " + expression + " on " + runCase.text();
                RegexpMatcher candidate = assertFoldedRunCandidateAgrees(language + " " + expression, pattern, control, utf8(runCase.text()));
                assertThat(candidate.candidateStartRouteCountForDiagnostics()).as(description).isEqualTo(runCase.routes());
                assertThat(candidate.candidateStartFallbackCountForDiagnostics()).as(description).isEqualTo(runCase.fallbacks());
            }
        }
    }

    @Test
    public void testFoldedStartRunCursorFindsLeftmostMatch()
    {
        // A match longer than an anchored verification's transition limit falls back to the
        // matcher's other routes. A find from inside a match, whose run lies behind a lead wider
        // than the find's offset, verifies no start before the find's. The run of the match at 1
        // starts at 4 and overlaps the run found at 3, so the scan resumes right after each run it
        // finds, not past its end.
        String filler = FILLER_UNIT.repeat(8);
        for (RunFindCase runCase : List.of(
                new RunFindCase("Rust", "\\bthe (?i)Sherlock Holmes[a-z ]*", "the Sherlock Holmes " + "and so on ".repeat(20) + ". the " + filler, 0, new Boundary(0, 220), 0, 1),
                new RunFindCase("RE2", "\\bthe (?i)Sherlock Holmes", "the \u017Fherlock Holmes the " + filler, 1, null, 0, 0),
                new RunFindCase("RE2", "\\Bthz(?i)zzzz", "zthzzzzz the thz " + filler, 0, new Boundary(1, 8), 1, 0),
                new RunFindCase("Trino", "\\Bthz(?i)zzzz", "zthzzzzz the thz " + filler, 0, new Boundary(1, 8), 1, 0))) {
            String description = runCase.language() + " " + runCase.expression() + " from " + runCase.from() + " on " + runCase.text();
            RegexpMatcher candidate = compilePattern(runCase.language(), runCase.expression()).groupZeroMatcher(utf8(runCase.text()), null);
            assertThat(candidate.candidateStartCursorSearchesFoldedStartRunForDiagnostics()).as(description).isTrue();
            boolean found = candidate.find(runCase.from());
            assertThat(found ? new Boundary(candidate.start(), candidate.end()) : null).as(description).isEqualTo(runCase.expected());
            assertThat(candidate.candidateStartRouteCountForDiagnostics()).as(description).isEqualTo(runCase.routes());
            assertThat(candidate.candidateStartFallbackCountForDiagnostics()).as(description).isEqualTo(runCase.fallbacks());
            // A search that proves there is no match counts neither a route nor a fallback.
            if (runCase.expected() == null) {
                assertThat(candidate.candidateStartFoldedRunSearchCountForDiagnostics()).as(description).isPositive();
            }
        }
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
    public void testLiteralCandidateCursorSettlesQuitCandidates()
    {
        // A word boundary that decodes characters quits the DFA next to a byte outside ASCII: here
        // at the literal's first occurrence, after a letter, a curly quote, and malformed UTF-8.
        // The matcher settles that one candidate without the DFA, matched or not, and the cursor
        // resumes after it, so the cursor's DFA finds every later match, nothing falls back, and
        // no quit counts toward the streak. The count, a region, find(int), and a Slice past its
        // array's first byte agree with a matcher without the cursor.
        String expression = "\\bgot(?i)syou";
        String rest = "gotsyou, gotSYOU and gotsyou " + FILLER;
        List<byte[]> neighbors = List.of("É".getBytes(UTF_8), "“".getBytes(UTF_8), new byte[] {(byte) 0xC3}, new byte[] {(byte) 0x89}, new byte[] {(byte) 0xFF});
        for (String language : QUITTING_LANGUAGES) {
            CompiledPattern pattern = compile(language, expression);
            CompiledPattern controlPattern = compile(language, "(" + expression + ")");
            for (byte[] neighbor : neighbors) {
                Slice input = concatenate("fo".getBytes(UTF_8), neighbor, rest.getBytes(UTF_8));
                String description = language + " after " + HexFormat.of().formatHex(neighbor);
                RegexpMatcher control = controlPattern.matcher(input);
                assertThat(control.candidateStartCursorEnabledForDiagnostics()).as(description).isFalse();
                List<Boundary> expected = boundaries(control);
                RegexpMatcher matcher = pattern.groupZeroMatcher(input, null);

                assertThat(matcher.candidateStartCursorSearchesLiteralForDiagnostics()).as(description).isTrue();
                assertThat(boundaries(matcher)).as(description).isEqualTo(expected);
                // A match settled at a quit candidate is not a cursor route.
                assertThat(matcher.candidateStartRouteCountForDiagnostics()).as(description).isEqualTo(2);
                assertThat(matcher.candidateStartFallbackCountForDiagnostics()).as(description).isZero();
                assertThat(matcher.candidateStartCursorReportsQuitCandidatesForDiagnostics()).as(description).isTrue();
                assertThat(matcher.dfaQuitStreak()).as(description).isZero();
                assertThat(pattern.count(input)).as(description).isEqualTo(expected.size());

                assertThat(matcher.find(1)).as(description).isEqualTo(control.find(1));
                assertThat(matcher.start()).as(description).isEqualTo(control.start());
                assertThat(matcher.end()).as(description).isEqualTo(control.end());

                // The region keeps more than the cursor's minimum window past the first candidate.
                // Rust searches a partial region with the text around it as context, without the
                // cursor, so its region checks only the matches.
                int regionStart = 1;
                int regionEnd = input.length() - 1;
                matcher.reset(input, regionStart, regionEnd);
                assertThat(matcher.candidateStartCursorReportsQuitCandidatesForDiagnostics()).as(description).isFalse();
                control.reset(input, regionStart, regionEnd);
                long routesBeforeRegion = matcher.candidateStartRouteCountForDiagnostics();
                assertThat(boundaries(matcher)).as(description).isEqualTo(boundaries(control));
                if (!language.equals("Rust")) {
                    assertThat(matcher.candidateStartCursorReportsQuitCandidatesForDiagnostics()).as(description).isTrue();
                    assertThat(matcher.candidateStartRouteCountForDiagnostics()).as(description).isGreaterThan(routesBeforeRegion);
                }

                byte[] padded = concatenate("xyz".getBytes(UTF_8), input.getBytes()).getBytes();
                Slice offsetInput = Slices.wrappedBuffer(padded, 3, input.length());
                assertThat(boundaries(pattern.groupZeroMatcher(offsetInput, null))).as(description).isEqualTo(expected);
            }
        }
    }

    @Test
    public void testLiteralCandidateCursorSettlesQuitCandidateExactly()
    {
        // Each input's first find meets a candidate whose check quits next to a byte outside
        // ASCII, which the matcher settles. Each find agrees with a matcher without the cursor,
        // the cursor's fallback count stays zero, and no quit counts toward the streak.
        String sherlock = "zqx cat, Ézqx Sherlock, “";
        int quotedCandidateStart = sherlock.getBytes(UTF_8).length;
        List<QuitCandidateCase> cases = List.of(
                // The first occurrence fails its suffix. The second quits and fails its word
                // boundary. The quoted occurrence quits and matches the shorter `Sherlock`
                // alternative.
                new QuitCandidateCase(
                        "\\bzqx (?i)(?:Sherlock|Sherlock Holmes)",
                        sherlock + "zqx SHERLOCK Holmes, zqx sherlock holmes " + FILLER,
                        Optional.of(new Boundary(quotedCandidateStart, quotedCandidateStart + "zqx SHERLOCK".length()))),
                // Matches that span the most bytes a match can, so the settle reads every one. The
                // long s and the Kelvin sign are the longest folds of s and k.
                new QuitCandidateCase("\\bab[a-z]{0,3}", "“abxyz " + FILLER, Optional.of(new Boundary(3, 8))),
                new QuitCandidateCase("\\bgot(?i)syou", "“gotſyou " + FILLER, Optional.of(new Boundary(3, 11))),
                new QuitCandidateCase("\\bab(?i)sk", "“abſ\u212A " + FILLER, Optional.of(new Boundary(3, 10))),
                // The text ends inside the longest match's bytes after the candidate, so the
                // settle reads only to its end.
                new QuitCandidateCase("\\bgot(?i)syou", FILLER + "“gotsyo", Optional.empty()),
                // The byte after the settled bytes rules out a match at the first candidate.
                new QuitCandidateCase("\\bab\\b", "“abc ab " + FILLER, Optional.of(new Boundary(7, 9))),
                // The literal occurs again at the byte after the settled candidate.
                new QuitCandidateCase("\\Baa[bc]", "éaaab " + FILLER, Optional.of(new Boundary(3, 6))),
                // Five candidates settle without a match, and the window after the last is too
                // short for the cursor, so no completed cursor search resets the streak afterward;
                // a settle that counted toward the streak would leave it nonzero.
                new QuitCandidateCase("\\bab(?i)k\\b", "“abc ".repeat(4) + FILLER_UNIT.repeat(4) + "“abc " + FILLER_UNIT + "filler", Optional.empty()));
        for (QuitCandidateCase quitCase : cases) {
            Slice input = utf8(quitCase.input());
            for (String language : QUITTING_LANGUAGES) {
                String description = language + " " + quitCase.expression() + " on " + quitCase.input();
                RegexpMatcher matcher = compile(language, quitCase.expression()).groupZeroMatcher(input, null);
                List<Boundary> expected = ordinaryBoundaries(language, quitCase.expression(), input);

                assertThat(matcher.candidateStartCursorSearchesLiteralForDiagnostics()).as(description).isTrue();
                assertThat(expected.stream().findFirst()).as(description).isEqualTo(quitCase.firstMatch());
                assertThat(boundaries(matcher)).as(description).isEqualTo(expected);
                assertThat(matcher.candidateStartFallbackCountForDiagnostics()).as(description).isZero();
                assertThat(matcher.dfaQuitStreak()).as(description).isZero();
                assertThat(matcher.candidateStartCursorSearchesFoldedStartRunForDiagnostics()).as(description).isFalse();
                assertThat(matcher.candidateStartCursorReportsQuitCandidatesForDiagnostics()).as(description).isTrue();
            }
        }

        // A cursor that scans for a folded start run settles no quit: each start after `é` that
        // holds the literal quits and falls back, and counts toward the streak.
        String runExpression = "\\bthe (?i)Sherlock Holmes";
        Slice runInput = utf8("éthe Sherlock Holmes, the Sherlock Holmes. ".repeat(3) + FILLER);
        for (String language : QUITTING_LANGUAGES) {
            String description = language + " " + runExpression;
            CompiledPattern pattern = compile(language, runExpression);
            RegexpMatcher matcher = pattern.groupZeroMatcher(runInput, null);
            List<Boundary> expected = ordinaryBoundaries(language, runExpression, runInput);

            assertThat(matcher.candidateStartCursorSearchesFoldedStartRunForDiagnostics()).as(description).isTrue();
            assertThat(boundaries(matcher)).as(description).hasSize(3).isEqualTo(expected);
            assertThat(pattern.count(runInput)).as(description).isEqualTo(expected.size());
            assertThat(matcher.candidateStartFallbackCountForDiagnostics()).as(description).isEqualTo(3);
            assertThat(matcher.dfaQuitStreak()).as(description).isEqualTo(3);
            assertThat(matcher.candidateStartCursorReportsQuitCandidatesForDiagnostics()).as(description).isFalse();
        }
    }

    @Test
    public void testLiteralCandidateCursorBoundsQuitCandidateWork()
    {
        // Each quit candidate is settled over at most the bytes the longest match spans, which the
        // cursor charges to its work budget. Here every candidate follows a curly quote, so each
        // find settles one and matches there; each match consumes three bytes, but each settle is
        // charged the much longer maximum match length. The budget runs out before the candidates
        // do, and the cursor falls back for the rest of the input, whose matches are the same.
        String expression = "\\bab[^\\n]{0,300}?X";
        Slice input = utf8("“abX ".repeat(1_000) + FILLER);
        for (String language : QUITTING_LANGUAGES) {
            CompiledPattern pattern = compile(language, expression);
            RegexpMatcher matcher = pattern.groupZeroMatcher(input, null);
            int window = pattern.maximumMatchLength();
            List<Boundary> found = new ArrayList<>();
            int settledFinds = -1;
            while (matcher.find()) {
                if (settledFinds < 0 && !matcher.candidateStartCursorEnabledForDiagnostics()) {
                    settledFinds = found.size();
                }
                found.add(new Boundary(matcher.start(), matcher.end()));
            }

            assertThat(window).as(language).isGreaterThan(300);
            assertThat(found).as(language).isEqualTo(ordinaryBoundaries(language, expression, input));
            assertThat(settledFinds).as(language).isPositive().isLessThan(found.size());
            assertThat((long) settledFinds * window).as(language).isLessThanOrEqualTo(3L * input.length());
            assertThat(matcher.candidateStartFallbackCountForDiagnostics()).as(language).isEqualTo(1);
        }

        // The first quit after a reset repeats its search in the loop that reports quits, and the
        // work of the search that quit is refunded. Here that search checks every candidate before
        // the quoted one, each over the longest match's bytes, so it spends most of the budget:
        // charged twice, it would exhaust the budget and the cursor would fall back.
        Slice costlyInput = utf8(("ab" + "y".repeat(120) + "\n").repeat(400) + "“abX " + FILLER);
        for (String language : QUITTING_LANGUAGES) {
            RegexpMatcher matcher = compile(language, expression).groupZeroMatcher(costlyInput, null);

            assertThat(boundaries(matcher)).as(language).isEqualTo(ordinaryBoundaries(language, expression, costlyInput));
            assertThat(matcher.candidateStartFallbackCountForDiagnostics()).as(language).isZero();
        }
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
        for (long maxMemory = 6 * 1024; maxMemory <= 32 * 1024; maxMemory += 64) {
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

    @Test
    public void testFoldedStartRunMemoryIsReservedFromForwardDfa()
    {
        // The run is reserved after the literal, from the allowance the literal's charge leaves,
        // and is kept only where both charges leave both forward DFAs their minimum, so the sweep
        // passes budgets that keep the literal and the run and budgets that keep only the
        // literal. The required bytes are reserved after both, as in the literal's sweep above.
        Slice expression = utf8("\\bthe (?i)Sherlock Holmes");
        Re2 pattern = Re2.compile(expression);
        Prog program = pattern.forwardProgramForDiagnostics();
        long literalReservation = program.transparentStartLiteralSearch().estimatedRetainedSize() + SizeOf.sizeOf(program.transparentStartLiteral());
        long runReservation = pattern.foldedStartRunForDiagnostics().estimatedRetainedSize();
        List<Boolean> outcomes = new ArrayList<>();
        for (long maxMemory = 4 * 1024; maxMemory <= 512 * 1024; maxMemory += 512) {
            Re2 longestPattern;
            Re2 firstMatchPattern;
            try {
                longestPattern = Re2.compile(expression, Re2.Options.defaults().setLongestMatch(true).setMaxMemory(maxMemory));
                firstMatchPattern = Re2.compile(expression, Re2.Options.defaults().setMaxMemory(maxMemory));
            }
            catch (RegexpCompileMemoryLimitException ignored) {
                // A budget the program does not fit reserves nothing.
                continue;
            }
            Prog longest = longestPattern.forwardProgramForDiagnostics();
            Prog firstMatch = firstMatchPattern.forwardProgramForDiagnostics();
            long allowance = longest.dfaMemory() + longestPattern.requiredBytesRetainedSizeForDiagnostics();
            long minimum = Dfa.DfaInstance.minimumMemory(longest, Dfa.DfaInstance.Kind.LONGEST_MATCH);
            boolean keepsLiteral = (allowance - literalReservation) / 2 >= minimum;
            boolean keepsRun = keepsLiteral && (allowance - literalReservation - runReservation) / 2 >= minimum;
            assertThat(firstMatch.transparentStartLiteral() != null).as("keeps literal at %s", maxMemory).isEqualTo(keepsLiteral);
            assertThat(firstMatchPattern.foldedStartRunForDiagnostics() != null).as("keeps run at %s", maxMemory).isEqualTo(keepsRun);
            assertThat(allowance - firstMatch.dfaMemory() - firstMatchPattern.requiredBytesRetainedSizeForDiagnostics())
                    .as("reservation at %s", maxMemory)
                    .isEqualTo((keepsLiteral ? literalReservation : 0) + (keepsRun ? runReservation : 0));
            if (keepsLiteral) {
                outcomes.add(keepsRun);
            }
        }
        assertThat(outcomes).contains(false, true);
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

    private static RegexpMatcher assertFoldedRunCandidateAgrees(String patternDescription, CompiledPattern pattern, CompiledPattern control, Slice input)
    {
        RegexpMatcher candidate = pattern.groupZeroMatcher(input, null);
        RegexpMatcher controlMatcher = control.matcher(input);
        List<Boundary> expected = boundaries(controlMatcher);

        String description = patternDescription + " on " + input.toStringUtf8();
        assertThat(controlMatcher.candidateStartCursorEnabledForDiagnostics()).as(description).isFalse();
        assertThat(candidate.candidateStartCursorSearchesFoldedStartRunForDiagnostics()).as(description).isTrue();
        assertThat(boundaries(candidate)).as(description).isEqualTo(expected);
        return candidate;
    }

    private static void assertFoldedStartRun(String description, CompiledPattern pattern, String run, int minimumLead, int maximumLead, int firstOffset, int secondOffset)
    {
        Prog.FoldedStartRun foldedStartRun = pattern.foldedStartRunForDiagnostics();
        assertThat(foldedStartRun).as(description).isNotNull();
        assertThat(foldedStartRun.run()).as(description).isEqualTo(run.getBytes(UTF_8));
        assertThat(foldedStartRun.minimumLead()).as(description).isEqualTo(minimumLead);
        assertThat(foldedStartRun.maximumLead()).as(description).isEqualTo(maximumLead);
        assertThat(foldedStartRun.firstOffset()).as(description).isEqualTo(firstOffset);
        assertThat(foldedStartRun.secondOffset()).as(description).isEqualTo(secondOffset);
    }

    private static CompiledPattern compilePattern(String language, String expression)
    {
        Slice source = utf8(expression);
        return switch (language) {
            case "RE2" -> Re2.compile(source);
            case "Java" -> JavaRegexp.compile(source);
            case "Java (?u)" -> JavaRegexp.compile(utf8("(?u)" + expression));
            case "Rust" -> RustRegexp.compile(source);
            case "Trino" -> TrinoRegexp.compile(source).re2();
            default -> throw new IllegalArgumentException(language);
        };
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

    // A capturing matcher of the language takes the ordinary path rather than the candidate cursor.
    private static List<Boundary> ordinaryBoundaries(String language, String expression, Slice input)
    {
        RegexpMatcher control = compile(language, "(" + expression + ")").matcher(input);
        assertThat(control.candidateStartCursorEnabledForDiagnostics()).isFalse();
        return boundaries(control);
    }

    private static CompiledPattern compile(String language, String expression)
    {
        Slice pattern = utf8(expression);
        return switch (language) {
            case "Java" -> JavaRegexp.compile(pattern, JavaRegexp.Options.defaults().setUnicodeCase(true).setUnicodeCharacterClasses(true));
            case "Trino" -> TrinoRegexp.compile(pattern).re2();
            case "Rust" -> RustRegexp.compile(pattern);
            default -> throw new IllegalArgumentException(language);
        };
    }

    private static Slice concatenate(byte[]... parts)
    {
        DynamicSliceOutput output = new DynamicSliceOutput(64);
        for (byte[] part : parts) {
            output.appendBytes(part);
        }
        return output.slice();
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

    private record FoldedRunCase(String expression, List<String> spellings, String nearMiss) {}

    private record RunCursorCase(String text, int routes, int fallbacks) {}

    private record RunFindCase(String language, String expression, String text, int from, Boundary expected, int routes, int fallbacks) {}

    private record QuitCandidateCase(String expression, String input, Optional<Boundary> firstMatch) {}
}
