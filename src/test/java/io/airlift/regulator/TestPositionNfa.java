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

import com.sun.management.ThreadMXBean;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Predicate;

import static io.airlift.regulator.TestingPatternShapes.compileAtSmallestDfaBudget;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestPositionNfa
{
    private static final String BOUNDED_REPEAT = "[a-q][^u-z]{20,80}[x\\xE0-\\xFF]";
    // A repeat whose UTF-8 program the automaton admits.
    private static final String UTF_8_BOUNDED_REPEAT = "[a-q][^u-z]{10,30}[x\\xE0-\\xFF]";
    private static final int EXPRESSIONS = 80;

    // The languages and modes whose group-zero searches the differential tests compare with
    // their capture engines. Each text is a Java string, valid in its charset. RE2 and Rust in
    // UTF-8 promise the ordinary engines' results on malformed UTF-8 too, so their texts also hold
    // a stray continuation byte and a truncated sequence beside a match; Trino's capture route and
    // group-zero route disagree on malformed UTF-8 with or without the automaton.
    private static final List<Language> LANGUAGES = List.of(
            new Language("RE2 Latin-1", ISO_8859_1, false, expression -> Re2.compile(utf8Slice(expression), Re2.Options.latin1())),
            new Language("RE2 Latin-1 longest match", ISO_8859_1, false, expression -> Re2.compile(utf8Slice(expression), Re2.Options.latin1().setLongestMatch(true))),
            new Language("Rust bytes", ISO_8859_1, false, TestPositionNfa::rustBytes),
            new Language("RE2", UTF_8, true, expression -> Re2.compile(utf8Slice(expression))),
            new Language("Rust", UTF_8, true, expression -> RustRegexp.compile(utf8Slice(expression))),
            new Language("Java", UTF_8, false, expression -> JavaRegexp.compile(utf8Slice(expression))),
            new Language("Trino", UTF_8, false, expression -> TrinoRegexp.compile(utf8Slice(expression)).re2()));
    // The modes whose boolean searches judge the resets of the DFA they share, each compiling at a
    // 3 MiB budget, at which the DFA of its bounded repeat thrashes over short words.
    private static final List<ThrashingMode> THRASHING_MODES = List.of(
            new ThrashingMode("Rust bytes", BOUNDED_REPEAT, expression -> RustRegexp.compile(utf8Slice(expression), RustRegexp.Options.bytes().setUnicode(false).setMaxMemory(3L << 20))),
            new ThrashingMode("Rust", UTF_8_BOUNDED_REPEAT, expression -> RustRegexp.compile(utf8Slice(expression), RustRegexp.Options.defaults().setMaxMemory(3L << 20))),
            new ThrashingMode("RE2 Latin-1 longest match", BOUNDED_REPEAT, expression -> Re2.compile(utf8Slice(expression), Re2.Options.latin1().setLongestMatch(true).setMaxMemory(3L << 20))));
    // A stray continuation byte and a truncated three-byte sequence.
    private static final List<byte[]> MALFORMED_UTF_8 = List.of(new byte[] {(byte) 0x80}, new byte[] {(byte) 0xE6, (byte) 0x97});
    // Each text sits this far into its backing array, past any longest match, so a window left in
    // backing-array positions would miss the match it was found for.
    private static final int BACKING_OFFSET = 1024;

    // The atoms of random expressions, each with a text it matches in every language. The
    // two-character literal is a group, so that a quantifier repeats all of it. The text of
    // `(?i:[a-c]é)` folds only its ASCII letter, since Java without Unicode case and the Latin-1
    // languages fold no other. A Latin-1 pattern would read a non-ASCII literal as its UTF-8
    // bytes, so its expressions write each such literal as a byte escape and leave out the last
    // atom, which Latin-1 cannot hold.
    private static final List<Case> ATOMS = List.of(
            new Case("a", "a"),
            new Case("b", "b"),
            new Case("(?:ab)", "ab"),
            new Case("é", "é"),
            new Case("ÿ", "ÿ"),
            new Case("[a-c]", "c"),
            new Case("[^u-z]", "é"),
            new Case(".", "ÿ"),
            new Case("(?i:ab)", "aB"),
            new Case("(?i:[a-c]é)", "Cé"),
            new Case("日", "日"));

    /// A count on a matcher whose DFA bailed runs the automaton, not the Pike NFA.
    /// `TestDfaCountMatches` owns the public count's handoff to such a matcher.
    @Test
    public void testCountAfterDfaBailSkipsPikeNfa()
    {
        // At a 3 MiB budget, a DFA count of this pattern over 256 KiB of short words resets the
        // DFA too often and bails. The text holds an x every 997 bytes and no other byte of u-z
        // or above 0x7F, so each x ends one match.
        RustRegexp pattern = RustRegexp.compile(utf8Slice(BOUNDED_REPEAT), RustRegexp.Options.bytes().setUnicode(false).setMaxMemory(3L << 20));
        assertThat(pattern.forwardProgramForDiagnostics().positionNfa()).isNotNull();
        Slice text = wrappedBuffer(stateExplosionInput(256 << 10));
        assertThat(pattern.countMatches(text)).isEqualTo(Dfa.COUNT_BAILED);

        // The matcher a bailed count falls back on skips the forward DFA. Its finds take the
        // automaton, and BitState finds each span in the automaton's window.
        RegexpMatcher matcher = pattern.matcher(text, 0);
        matcher.markSkipsForwardDfa();
        List<String> spans = spans(matcher, 0);
        assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).isTrue();
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isFalse();

        RustRegexp captured = RustRegexp.compile(utf8Slice("(" + BOUNDED_REPEAT + ")"), RustRegexp.Options.bytes().setUnicode(false).setMaxMemory(3L << 20));
        assertThat(spans).hasSize(262).isEqualTo(spans(captured.matcher(text), 1));
        assertThat(pattern.count(text)).isEqualTo(262);
    }

    /// On a text longer than BitState's limit, BitState searches the automaton's window only when
    /// its visited bitmap is at most the cap, since each find clears it. The first windows of this
    /// pattern's one-byte matches, the narrowest 403 bytes, fit BitState's limit, but their
    /// bitmaps are over the cap, so the Pike NFA searches them. The automaton, of seven words of
    /// state, keeps its state in the matcher's workspace, which shows that it ran.
    @Test
    public void testWideWindowSearchesWithPikeNfa()
            throws ReflectiveOperationException
    {
        String expression = "[a-q]|[r-z0-9 ][^u-z]{20,400}[x\\xE0-\\xFF]";
        CompiledPattern pattern = rustBytes(expression);
        starveDfas(pattern);
        Prog program = pattern.forwardProgramForDiagnostics();
        assertThat(program.positionNfa().maxLength()).isEqualTo(402);
        assertThat(program.positionNfa().usesWorkspace()).isTrue();
        int firstWindow = 1 + program.positionNfa().maxLength();
        assertThat(firstWindow).isLessThanOrEqualTo(program.unanchoredBitStateTextMaxSize());
        assertThat(program.listCount() * (firstWindow + 1L)).isGreaterThan(CompiledPattern.POSITION_NFA_WINDOW_BIT_STATE_MAXIMUM_BYTES * Byte.SIZE);

        // Dense one-byte matches, then a filler longer than BitState's limit, which holds none.
        String filler = "!".repeat(program.unanchoredBitStateTextMaxSize() + 1);
        Slice text = wrappedBuffer(("abcdefghijklmnopq".repeat(60) + filler).getBytes(ISO_8859_1));
        RegexpMatcher matcher = pattern.matcher(text);
        List<String> spans = spans(matcher, 0);
        assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).isFalse();
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isTrue();
        Field workspaceField = RegexpMatcher.class.getDeclaredField("positionNfaWorkspace");
        workspaceField.setAccessible(true);
        assertThat(workspaceField.get(matcher)).isNotNull();
        assertThat(spans).hasSize(1020).isEqualTo(spans(rustBytes("(" + expression + ")").matcher(text), 1));
    }

    /// Boolean searches of short lines judge the resets of the DFA they share across searches:
    /// after two slow resets in a row, the next searches skip the DFA and answer from the position
    /// automaton directly, without trying BitState.
    @Test
    public void testLineSearchesSkipDfaAfterSlowResets()
    {
        // Each boolean search of a 200-byte line reads far less text than the 3 MiB cache holds
        // states, so none resets the cache twice, but the DFA they share builds a state for most
        // bytes and resets long before it has read ten bytes for each state.
        ThrashingMode mode = THRASHING_MODES.getFirst();
        CompiledPattern pattern = mode.compile(mode.expression());
        CompiledPattern reference = mode.compile(mode.expression());
        List<Slice> lines = lines(wrappedBuffer(stateExplosionInput(1 << 20)), 200);
        List<Slice> skippedLines = new ArrayList<>();
        int skippedMatches = 0;
        int resetsAtFirstSkip = -1;
        for (int index = 0; index < lines.size(); index++) {
            Dfa.DfaInstance dfa = booleanSearchDfa(pattern);
            int resetsBefore = dfa == null ? 0 : dfa.resetCount();
            BooleanSearch search = search(pattern, reference, lines.get(index), "line " + index);
            if (search.skipped()) {
                // A skipped search leaves the DFA alone.
                assertThat(dfa.resetCount()).as("line %s", index).isEqualTo(resetsBefore);
                if (resetsAtFirstSkip < 0) {
                    resetsAtFirstSkip = resetsBefore;
                }
                skippedLines.add(lines.get(index));
                if (search.found()) {
                    skippedMatches++;
                }
            }
        }
        // The first reset has no allowance to judge, so the window opens at the third, and the
        // searches after it, which find almost every match, take the automaton.
        assertThat(resetsAtFirstSkip).isEqualTo(3);
        assertThat(skippedLines).hasSizeGreaterThan(5000);
        assertThat(skippedMatches).isGreaterThan(900);
        assertSkippedSearchesAllocateNothing(pattern, reference, skippedLines);
    }

    /// At most 65,536 boolean searches skip the DFA after their window opens, whichever threads
    /// take them, and the next public search runs the DFA again.
    @Test
    public void testLineSearchWindowBoundsConcurrentSkips()
            throws Exception
    {
        ThrashingMode mode = THRASHING_MODES.getFirst();
        CompiledPattern pattern = mode.compile(mode.expression());
        CompiledPattern reference = mode.compile(mode.expression());
        List<Slice> lines = lines(wrappedBuffer(stateExplosionInput(1 << 20)), 200);
        int next = searchLinesUntil(pattern, reference, lines, 0, dfa -> dfa.booleanSearchSkipsRemaining() > 0);
        Dfa.DfaInstance dfa = booleanSearchDfa(pattern);
        assertThat(dfa.booleanSearchSkipsRemaining()).isEqualTo(1 << 16);

        // At most 65,536 searches skip per window, whichever threads take them. Each searches a
        // line of u, which holds no byte that starts a match, so a search that loses a skip to
        // another thread runs the DFA, which resets nothing and opens no other window.
        Prog program = pattern.forwardProgramForDiagnostics();
        Slice quiet = utf8Slice("u".repeat(200));
        int skips = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<Integer>> threadSkips = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                threadSkips.add(executor.submit(() -> {
                    startGate.await();
                    int skipped = 0;
                    for (int search = 0; search < 9000; search++) {
                        long result = Dfa.searchJudgingSlowResets(program, quiet, 0, quiet.length(), 0, quiet.length(), Prog.MatchKind.FIRST_MATCH);
                        assertThat(result).isIn(Dfa.BOOLEAN_SEARCH_SKIPPED, Dfa.SEARCH_NO_MATCH);
                        if (result == Dfa.BOOLEAN_SEARCH_SKIPPED) {
                            skipped++;
                        }
                    }
                    return skipped;
                }));
            }
            startGate.countDown();
            for (Future<Integer> skipped : threadSkips) {
                skips += skipped.get(30, TimeUnit.SECONDS);
            }
        }

        // The searches that follow on one thread take what the window has left, then run the
        // DFA, and so does the next public search of a line of words, which builds states for
        // it, or resets.
        long result = Dfa.searchJudgingSlowResets(program, quiet, 0, quiet.length(), 0, quiet.length(), Prog.MatchKind.FIRST_MATCH);
        while (result == Dfa.BOOLEAN_SEARCH_SKIPPED && skips <= 1 << 16) {
            skips++;
            result = Dfa.searchJudgingSlowResets(program, quiet, 0, quiet.length(), 0, quiet.length(), Prog.MatchKind.FIRST_MATCH);
        }
        assertThat(skips).isEqualTo(1 << 16);
        assertThat(result).isEqualTo(Dfa.SEARCH_NO_MATCH);
        int states = dfa.stateCount;
        int resets = dfa.resetCount();
        assertThat(search(pattern, reference, lines.get(next), "line " + next).skipped()).isFalse();
        assertThat(dfa.stateCount != states || dfa.resetCount() != resets).as("the next search ran the DFA").isTrue();
    }

    /// A reset is judged by the boolean search whose starting reset count still owns the
    /// allowance, so in searches replayed in flight together, without racing on that check,
    /// searches across one reset that is not slow open no window, an older search that judges
    /// after a newer one started past its reset count does not end the streak, and two slow
    /// resets, each judged by one search, open the window.
    @Test
    public void testLineSearchesJudgeEachResetOnce()
    {
        // Each schedule replays boolean searches in flight together with the DFA's own start and
        // judgement. Before each reset, public searches of word lines build the states that seed
        // the next allowance, and their own judgements charge the interval they search in. A
        // replayed judgement charges either one 200-byte line without a match, or a row past any
        // allowance.
        List<ReplayedSchedule> schedules = List.of(
                new ReplayedSchedule(
                        "three judges at one reset that is not slow",
                        "reset, start first, judge first past, streak 0, start a, start b, start c, reset, judge a line, judge b line, judge c line",
                        0,
                        false),
                new ReplayedSchedule(
                        "older search judging after a newer one started",
                        "reset, start slow, reset, judge slow line, streak 1, start older, reset, start newer, reset, judge older past, judge newer line",
                        2,
                        true),
                new ReplayedSchedule(
                        "two slow resets",
                        "reset, start first, reset, judge first line, start second, reset, judge second line",
                        2,
                        true));
        ThrashingMode mode = THRASHING_MODES.getFirst();
        // Four lines build about 200 states each, far fewer than the cache holds.
        List<Slice> lines = lines(wrappedBuffer(stateExplosionInput(5 * 200)), 200);
        List<Slice> buildLines = lines.subList(0, 4);
        int buildBytes = 4 * 200;
        int lineCharge = 200;
        int pastCharge = 1 << 20;
        List<ReplayedSchedule> outcomes = new ArrayList<>();
        for (ReplayedSchedule schedule : schedules) {
            String label = schedule.name();
            CompiledPattern pattern = mode.compile(mode.expression());
            CompiledPattern reference = mode.compile(mode.expression());
            buildLines.forEach(pattern::find);
            Dfa.DfaInstance dfa = booleanSearchDfa(pattern);
            assertThat(dfa).as(label).isNotNull();
            assertThat(dfa.resetCount()).as(label).isZero();
            assertThat(dfa.booleanSearchSlowResetStreak()).as(label).isZero();
            assertThat(dfa.booleanSearchSkipsRemaining()).as(label).isZero();
            Map<String, Integer> resetsBefore = new HashMap<>();
            int resets = 0;
            for (String step : schedule.steps().split(", ")) {
                String[] words = step.split(" ");
                switch (words[0]) {
                    case "reset" -> {
                        buildLines.forEach(pattern::find);
                        assertThat(dfa.resetCount()).as("%s: %s", label, step).isEqualTo(resets);
                        dfa.resetCacheExternal();
                        resets++;
                        assertThat(dfa.resetCount()).as("%s: %s", label, step).isEqualTo(resets);
                        // The allowance outlasts an interval's public searches and a line, so a
                        // reset after them is slow, and a row past any allowance is not.
                        assertThat(dfa.slowResetAllowance()).as("%s: %s", label, step).isGreaterThan(4 * (buildBytes + lineCharge)).isLessThan(pastCharge);
                    }
                    case "start" -> resetsBefore.put(words[1], dfa.startBooleanSearchForTesting());
                    case "judge" -> dfa.judgeBooleanSearchForTesting(resetsBefore.remove(words[1]), Dfa.SEARCH_NO_MATCH, words[2].equals("past") ? pastCharge : lineCharge);
                    case "streak" -> {
                        assertThat(dfa.booleanSearchSlowResetStreak()).as("%s: %s", label, step).isEqualTo(Integer.parseInt(words[1]));
                        assertThat(dfa.booleanSearchSkipsRemaining()).as("%s: %s", label, step).isZero();
                    }
                    default -> throw new IllegalArgumentException(step);
                }
            }
            assertThat(dfa.resetCount()).as(label).isEqualTo(resets);
            boolean windowOpen = dfa.booleanSearchSkipsRemaining() > 0;
            outcomes.add(new ReplayedSchedule(label, schedule.steps(), dfa.booleanSearchSlowResetStreak(), windowOpen));
            // The next public search skips the DFA exactly when the window is open.
            assertThat(search(pattern, reference, lines.getLast(), label).skipped()).as(label).isEqualTo(windowOpen);
        }
        assertThat(outcomes).isEqualTo(schedules);
    }

    /// Progress past the allowance ends the boolean searches' streak of slow resets, whether the
    /// search that makes it resets the cache or not, so one slow reset on either side of it opens
    /// no window.
    @Test
    public void testProductiveLineSearchEndsStreak()
    {
        List<Slice> lines = lines(wrappedBuffer(stateExplosionInput(1 << 20)), 200);
        byte[] unmatched = stateExplosionInput(64 << 10);
        for (int index = 0; index < unmatched.length; index++) {
            if (unmatched[index] == 'x') {
                unmatched[index] = 'a';
            }
        }
        for (ThrashingMode mode : THRASHING_MODES) {
            for (boolean resets : List.of(false, true)) {
                String label = mode.name() + (resets ? " resetting" : " without a reset");
                CompiledPattern pattern = mode.compile(mode.expression());
                CompiledPattern reference = mode.compile(mode.expression());
                int next = searchLinesUntil(pattern, reference, lines, 0, dfa -> dfa.booleanSearchSlowResetStreak() == 1);
                Dfa.DfaInstance dfa = booleanSearchDfa(pattern);

                // A row of ! twice the allowance long, which the DFA reads in one state, and, to
                // reset the cache in the same search, words without the x that ends a match, half
                // again as many as the cache held states.
                int allowance = dfa.slowResetAllowance();
                byte[] run = new byte[2 * allowance];
                Arrays.fill(run, (byte) '!');
                Slice row = wrappedBuffer(resets ? concat(run, Arrays.copyOf(unmatched, 3 * allowance / 20)) : run);
                int resetsBefore = dfa.resetCount();
                assertThat(search(pattern, reference, row, label).skipped()).as(label).isFalse();
                assertThat(dfa.resetCount() - resetsBefore).as(label).isEqualTo(resets ? 1 : 0);
                assertThat(dfa.booleanSearchSlowResetStreak()).as(label).isZero();

                // The next slow reset starts a new streak, which opens no window.
                searchLinesUntil(pattern, reference, lines, next, booleanDfa -> booleanDfa.booleanSearchSlowResetStreak() == 1);
                assertThat(dfa.booleanSearchSkipsRemaining()).as(label).isZero();
            }
        }
    }

    /// A boolean search is charged the progress it returns, not the window it was given: rows of
    /// megabytes, each of whose searches resets the cache after reading far more than ten bytes
    /// for each state, keep the DFA, and searches of long windows that match within a kilobyte,
    /// over a DFA that thrashes, skip it, in each of [#THRASHING_MODES].
    @Test
    public void testLineSearchesChargeTheProgressTheyReturn()
    {
        byte[] words = stateExplosionInput(2 << 20);
        List<Slice> productiveRows = new ArrayList<>();
        for (int row = 0; row < 8; row++) {
            // A megabyte of ! the DFA reads in one state, then words without the x that ends a
            // match, which fill the cache.
            byte[] bytes = new byte[(1 << 20) + 2048];
            Arrays.fill(bytes, 0, 1 << 20, (byte) '!');
            System.arraycopy(words, row * 2048, bytes, 1 << 20, 2048);
            for (int index = 1 << 20; index < bytes.length; index++) {
                if (bytes[index] == 'x') {
                    bytes[index] = 'a';
                }
            }
            productiveRows.add(wrappedBuffer(bytes));
        }
        Slice text = wrappedBuffer(words);
        List<Slice> earlyMatchingRows = new ArrayList<>();
        for (int offset = 0; offset < 1 << 20; offset += 997) {
            earlyMatchingRows.add(text.slice(offset, text.length() - offset));
        }
        for (ThrashingMode mode : THRASHING_MODES) {
            assertThat(lineSearchesSkipDfa(mode, productiveRows)).as("%s productive rows", mode.name()).isFalse();
            assertThat(lineSearchesSkipDfa(mode, earlyMatchingRows)).as("%s early matches", mode.name()).isTrue();
        }
    }

    /// Runs the boolean searches of `mode`'s pattern over `rows`, checks them against a separate
    /// pattern's group-zero findInto, and returns whether one skipped the DFA. The searches judge
    /// at least three resets.
    private static boolean lineSearchesSkipDfa(ThrashingMode mode, List<Slice> rows)
    {
        CompiledPattern pattern = mode.compile(mode.expression());
        CompiledPattern reference = mode.compile(mode.expression());
        for (int index = 0; index < rows.size(); index++) {
            Dfa.DfaInstance dfa = booleanSearchDfa(pattern);
            int resetsBefore = dfa == null ? 0 : dfa.resetCount();
            if (search(pattern, reference, rows.get(index), mode.name() + " row " + index).skipped()) {
                assertThat(resetsBefore).as("%s resets before the first skip", mode.name()).isGreaterThanOrEqualTo(3);
                return true;
            }
        }
        assertThat(booleanSearchDfa(pattern).resetCount()).as("%s resets", mode.name()).isGreaterThanOrEqualTo(3);
        return false;
    }

    /// Boolean searches judge only the resets their own DFA work causes: one after another, the
    /// resets that counts and a matcher's finds cause before a boolean search never move its
    /// streak, in bytes and UTF-8 modes.
    @Test
    public void testLineSearchesNeverJudgeEarlierResets()
    {
        Slice thrashing = wrappedBuffer(stateExplosionInput(256 << 10));
        Slice quiet = utf8Slice("u".repeat(200));
        for (Re2 pattern : List.of(
                Re2.compile(utf8Slice(BOUNDED_REPEAT), Re2.Options.latin1().setLongestMatch(true).setMaxMemory(3L << 20)),
                Re2.compile(utf8Slice(UTF_8_BOUNDED_REPEAT), Re2.Options.defaults().setLongestMatch(true).setMaxMemory(3L << 20)))) {
            // With the longest-match option, counts and finds run the DFA that boolean searches
            // run.
            Dfa.DfaInstance dfa = null;
            int initialResets = 0;
            for (int round = 0; round < 8; round++) {
                if (round % 2 == 0) {
                    pattern.countMatches(thrashing);
                }
                else {
                    RegexpMatcher matcher = pattern.matcher(thrashing, 0);
                    while (matcher.find()) {
                        // The loop drains the matcher to drive resets of the shared DFA.
                    }
                }
                dfa = booleanSearchDfa(pattern);
                int resets = dfa.resetCount();
                if (round == 0) {
                    initialResets = resets;
                }
                // The line, all u, holds no byte that starts a match, so its search resets nothing.
                assertThat(pattern.find(quiet)).isFalse();
                assertThat(dfa.resetCount()).isEqualTo(resets);
                assertThat(dfa.booleanSearchSlowResetStreak()).isZero();
                assertThat(dfa.booleanSearchSkipsRemaining()).isZero();
            }
            assertThat(dfa.resetCount() - initialResets).isGreaterThanOrEqualTo(4);
        }
    }

    /// A full-Slice find that runs its inner literal search first makes the ordinary engine's skip
    /// of a thrashing DFA after the search falls back, in bytes and UTF-8 modes. The bytes mode
    /// also shows that a skipped search answers from the position automaton directly.
    @Test
    public void testInnerLiteralFirstLineSearchesSkipDfaAfterSlowResets()
    {
        // An @ every 97 bytes passes the search's gates, so most lines fall back to a DFA that
        // builds a state for most bytes, as in testLineSearchesSkipDfaAfterSlowResets. The lines
        // are short enough for BitState, which a skipped search does not try.
        byte[] bytes = stateExplosionInput(1 << 20);
        for (int index = 96; index < bytes.length; index += 97) {
            bytes[index] = '@';
        }
        List<Slice> lines = lines(wrappedBuffer(bytes), 160);
        for (ThrashingMode mode : THRASHING_MODES.subList(0, 2)) {
            String expression = mode.expression().replace("[^u-z]", "[^u-z@]").replace("[x\\xE0-\\xFF]", "@[a-q]");
            CompiledPattern pattern = mode.compile(expression);
            CompiledPattern reference = mode.compile(expression);
            assertThat(pattern.findsInnerLiteralFirstForDiagnostics()).as(mode.name()).isTrue();
            assertThat(pattern.forwardProgramForDiagnostics().positionNfa()).as(mode.name()).isNotNull();
            List<Slice> skippedLines = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                if (search(pattern, reference, lines.get(index), "%s line %s".formatted(mode.name(), index)).skipped()) {
                    skippedLines.add(lines.get(index));
                }
            }
            assertThat(skippedLines).as(mode.name()).hasSizeGreaterThan(1000);
            // The automaton of the UTF-8 program needs a workspace, which each boolean search
            // allocates, so only the bytes program's searches show the route by allocating nothing.
            if (!pattern.forwardProgramForDiagnostics().positionNfa().usesWorkspace()) {
                assertSkippedSearchesAllocateNothing(pattern, reference, skippedLines);
            }
        }
    }

    /// Boolean searches and counts that run one DFA keep their own skip windows: neither path's
    /// judgements or skips move the other's window, in bytes and UTF-8 modes.
    @Test
    public void testLineSearchesAndCountsKeepTheirOwnSkipWindows()
    {
        Slice text = wrappedBuffer(stateExplosionInput(1 << 20));
        // The first 997 bytes of the text hold one match, which a DFA count counts without a bail.
        Slice row = text.slice(0, 997);
        for (Re2 pattern : List.of(
                Re2.compile(utf8Slice(BOUNDED_REPEAT), Re2.Options.latin1().setLongestMatch(true).setMaxMemory(3L << 20)),
                Re2.compile(utf8Slice(UTF_8_BOUNDED_REPEAT), Re2.Options.defaults().setLongestMatch(true).setMaxMemory(3L << 20)))) {
            String label = pattern.pattern().toStringUtf8();
            // With the longest-match option, counts run the DFA that boolean searches run. Two
            // bailing counts open the counts' window, and boolean searches leave it whole: the
            // next 16 counts skip the DFA, and the one after counts on it.
            assertThat(pattern.countMatches(text.slice(0, 256 << 10))).as(label).isEqualTo(Dfa.COUNT_BAILED);
            assertThat(pattern.countMatches(text.slice(0, 256 << 10))).as(label).isEqualTo(Dfa.COUNT_BAILED);
            Dfa.DfaInstance dfa = booleanSearchDfa(pattern);
            for (int line = 0; line < 16; line++) {
                pattern.find(text.slice(line * 200, 200));
            }
            assertThat(dfa.booleanSearchSkipsRemaining()).as(label).isZero();
            for (int skipped = 0; skipped < 16; skipped++) {
                assertThat(pattern.countMatches(row)).as("%s skipped count %s", label, skipped).isEqualTo(Dfa.COUNT_BAILED);
            }
            assertThat(pattern.countMatches(row)).as(label).isEqualTo(1);

            // Boolean searches open their own window, and a count still runs the DFA and leaves
            // it whole.
            for (int offset = 0; offset < text.length() && dfa.booleanSearchSkipsRemaining() == 0; offset += 200) {
                pattern.find(text.slice(offset, Math.min(200, text.length() - offset)));
            }
            int searchSkips = dfa.booleanSearchSkipsRemaining();
            assertThat(searchSkips).as(label).isPositive();
            assertThat(pattern.countMatches(row)).as(label).isEqualTo(1);
            assertThat(dfa.booleanSearchSkipsRemaining()).as(label).isEqualTo(searchSkips);
        }
    }

    /// The DFA a boolean search of `pattern` runs, its forward program's longest-match DFA, or
    /// null before one is built.
    private static Dfa.DfaInstance booleanSearchDfa(CompiledPattern pattern)
    {
        return pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH);
    }

    /// Runs one boolean search of `line`, checks its answer against `reference`'s group-zero
    /// findInto, and returns the answer and whether the search took a skip of the boolean
    /// searches' window.
    private static BooleanSearch search(CompiledPattern pattern, CompiledPattern reference, Slice line, String label)
    {
        Dfa.DfaInstance dfa = booleanSearchDfa(pattern);
        int skipsBefore = dfa == null ? 0 : dfa.booleanSearchSkipsRemaining();
        boolean found = pattern.find(line);
        assertThat(found).as(label).isEqualTo(reference.findInto(line, new int[2]));
        return new BooleanSearch(found, skipsBefore > 0 && dfa.booleanSearchSkipsRemaining() == skipsBefore - 1);
    }

    /// Runs the boolean searches of `lines` from `start`, as [#search] does, until `stop` holds
    /// for their DFA, and returns the index of the next line.
    private static int searchLinesUntil(CompiledPattern pattern, CompiledPattern reference, List<Slice> lines, int start, Predicate<Dfa.DfaInstance> stop)
    {
        for (int index = start; index < lines.size(); index++) {
            search(pattern, reference, lines.get(index), "line " + index);
            Dfa.DfaInstance dfa = booleanSearchDfa(pattern);
            if (dfa != null && stop.test(dfa)) {
                return index + 1;
            }
        }
        throw new AssertionError("the lines ran out");
    }

    /// Runs the boolean searches of `lines`, each of which skipped the DFA before and skips it
    /// again while the window lasts, checks their answers, and checks that they allocated almost
    /// nothing on this thread. A position automaton of at most two words of state keeps its state
    /// in locals, but the submatch engines try BitState first on a line as short as these, and
    /// BitState allocates its visited bitmap, thousands of bytes, on each search without a
    /// workspace.
    private static void assertSkippedSearchesAllocateNothing(CompiledPattern pattern, CompiledPattern reference, List<Slice> lines)
    {
        Prog program = pattern.forwardProgramForDiagnostics();
        assertThat(program.positionNfa().usesWorkspace()).isFalse();
        assertThat(program.canBitState()).isTrue();
        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(program.unanchoredBitStateTextMaxSize()));
        Dfa.DfaInstance dfa = booleanSearchDfa(pattern);
        int skipsBefore = dfa.booleanSearchSkipsRemaining();
        boolean[] found = new boolean[lines.size()];
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        for (int index = 0; index < found.length; index++) {
            found[index] = pattern.find(lines.get(index));
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        assertThat(dfa.booleanSearchSkipsRemaining()).isEqualTo(skipsBefore - found.length);
        assertThat(allocatedBytes).isLessThan(16L * lines.size());
        for (int index = 0; index < found.length; index++) {
            assertThat(found[index]).as("line %s", index).isEqualTo(reference.findInto(lines.get(index), new int[2]));
        }
    }

    /// The lines of `text` of `length` bytes, the last one shorter.
    private static List<Slice> lines(Slice text, int length)
    {
        List<Slice> lines = new ArrayList<>();
        for (int offset = 0; offset < text.length(); offset += length) {
            lines.add(text.slice(offset, Math.min(length, text.length() - offset)));
        }
        return lines;
    }

    @Test
    public void testAdmission()
    {
        // A class is one position however many ranges it holds, so BOUNDED_REPEAT has one
        // position per class, 82. In UTF-8, [^u-z] is seven positions: its sequences take one for
        // each byte, and share the positions of their common continuation bytes.
        assertPositions(rustBytes(BOUNDED_REPEAT), 82, 82);
        assertPositions(Re2.compile(utf8Slice(UTF_8_BOUNDED_REPEAT)), 214, 123);
        assertPositions(Re2.compile(utf8Slice("[a-q][a-z ]{20,80}x")), 82, 82);
        // One, two, three and eight words of state.
        assertPositions(rustBytes("a[b-r]{61,62}x"), 64, 64);
        assertPositions(rustBytes("a[b-r]{62,63}x"), 65, 65);
        assertPositions(rustBytes("a[b-r]{125,126}x"), 128, 128);
        assertPositions(rustBytes("a[b-r]{126,127}x"), 129, 129);
        assertPositions(rustBytes("a[b-r]{509,510}x"), 512, 512);

        for (CompiledPattern rejected : List.of(
                // Unbounded matches.
                rustBytes("[a-q][^u-z]+x"),
                // An assertion.
                rustBytes("\\b[a-q]{2,5}"),
                // An empty match.
                rustBytes("[a-q]{0,5}"),
                // A match that must end the text.
                rustBytes("[a-q]{2,5}$"),
                // More than 512 positions, in bytes and in UTF-8.
                rustBytes("a[b-r]{510,511}x"),
                Re2.compile(utf8Slice("[a-q][^u-z]{80}[x\\xE0-\\xFF]")),
                // More than 32 fan-in word tests a byte.
                rustBytes("(?:a[b-r]{1,9}){13}"))) {
            assertThat(rejected.forwardProgramForDiagnostics().positionNfa()).as(rejected.pattern().toStringUtf8()).isNull();
        }
    }

    /// A matcher owns the workspace of a program of more than two words of state and reuses it
    /// across its searches, each of which starts from an empty state. The first search ends on the
    /// last required copy of the class, which steps to an optional copy that would accept the lone
    /// `a` after the match were the next search to start from that state. A search with no live
    /// state skips the `!`, which no match starts at, without stepping it, so only a search that
    /// starts from an empty state finds no match there.
    @Test
    public void testMatcherOwnsAndReusesWorkspace()
            throws ReflectiveOperationException
    {
        CompiledPattern pattern = rustBytes("a[a-r]{126,128}");
        starveDfas(pattern);
        Prog program = pattern.forwardProgramForDiagnostics();
        assertThat(program.positionNfa().positionCountForDiagnostics()).isEqualTo(129);
        String match = "a" + "c".repeat(126);
        String filler = "!".repeat(program.unanchoredBitStateTextMaxSize() + 1);
        Slice input = wrappedBuffer((filler + match + "!a" + filler + match).getBytes(ISO_8859_1));
        int secondStart = 2 * filler.length() + match.length() + 2;
        List<String> expected = List.of(filler.length() + ":" + (filler.length() + match.length()), secondStart + ":" + (secondStart + match.length()));

        RegexpMatcher matcher = pattern.matcher(input);
        Field workspaceField = RegexpMatcher.class.getDeclaredField("positionNfaWorkspace");
        workspaceField.setAccessible(true);
        assertThat(workspaceField.get(matcher)).isNull();
        assertThat(spans(matcher, 0)).isEqualTo(expected);
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isFalse();
        Object workspace = workspaceField.get(matcher);
        assertThat(workspace).isNotNull();
        Field statesField = PositionNfa.Workspace.class.getDeclaredField("states");
        statesField.setAccessible(true);
        Object states = statesField.get(workspace);
        assertThat(states).isNotNull();

        matcher.reset(input);
        assertThat(spans(matcher, 0)).isEqualTo(expected);
        assertThat(workspaceField.get(matcher)).isSameAs(workspace);
        assertThat(statesField.get(workspace)).isSameAs(states);
    }

    /// The automaton yields to the forward DFAs: it is reserved after every route selection makes,
    /// and only where the DFA still initializes, so once a forward DFA initializes at some budget,
    /// it initializes at every larger one. At the least budget at which a DFA initializes, the
    /// automaton's reservation would leave it too little, so the automaton is left out.
    @Test
    public void testYieldsToForwardDfas()
    {
        for (String expression : List.of("a", "[ab]")) {
            LongFunction<CompiledPattern> compiler = maxMemory -> Re2.compile(utf8Slice(expression), Re2.Options.latin1().setMaxMemory(maxMemory));
            for (Dfa.DfaInstance.Kind kind : List.of(Dfa.DfaInstance.Kind.FIRST_MATCH, Dfa.DfaInstance.Kind.LONGEST_MATCH)) {
                String label = expression + " " + kind;
                assertThat(compileAtSmallestDfaBudget(compiler, kind).forwardProgramForDiagnostics().positionNfa()).as(label).isNull();
                long initializedBudget = -1;
                int keptBesideDfa = 0;
                // The range spans both expressions' least DFA budgets of both kinds.
                for (long maxMemory = 10_000; maxMemory <= 16_000; maxMemory += 8) {
                    Prog program = compiler.apply(maxMemory).forwardProgramForDiagnostics();
                    boolean initialized = program.getCachedDfa(kind) != null;
                    if (initializedBudget >= 0) {
                        assertThat(initialized).as("%s at %s, initialized at %s", label, maxMemory, initializedBudget).isTrue();
                    }
                    else if (initialized) {
                        initializedBudget = maxMemory;
                    }
                    if (initialized && program.positionNfa() != null) {
                        keptBesideDfa++;
                    }
                }
                assertThat(initializedBudget).as(label).isGreaterThan(10_000);
                assertThat(keptBesideDfa).as(label).isPositive();
            }
        }
    }

    /// A step's fan-in work stays small. Each copy of a repeated multibyte class in UTF-8 begins
    /// where a few sequences of the copy before it end; those sequences' ends step by their
    /// follow sets, so a bounded repeat tests one fan-in group a byte in UTF-8, as it does in
    /// bytes mode. Fan-in tests, groups times words of state, are capped at 32 a byte.
    @Test
    public void testFanInTestsPerByteAreBounded()
    {
        for (CompiledPattern pattern : List.of(
                rustBytes(UTF_8_BOUNDED_REPEAT),
                Re2.compile(utf8Slice(UTF_8_BOUNDED_REPEAT)),
                RustRegexp.compile(utf8Slice(UTF_8_BOUNDED_REPEAT), RustRegexp.Options.bytes().setUnicode(true)))) {
            assertThat(pattern.forwardProgramForDiagnostics().positionNfa().fanInGroupCountForDiagnostics()).as(pattern.pattern().toStringUtf8()).isEqualTo(1);
        }

        // Each copy of the group after the first begins a fan-in group: of the nine copies of
        // [b-r] before it, the ninth steps to it by the shift, and the other eight, exactly the
        // fan-in minimum, make its group. Twelve copies hold 120 positions in two words, so 11
        // groups make 22 tests; thirteen hold 130 in three, so 12 groups would make 36, and
        // testAdmission rejects them.
        PositionNfa twelve = rustBytes("(?:a[b-r]{1,9}){12}").forwardProgramForDiagnostics().positionNfa();
        assertThat(twelve.positionCountForDiagnostics()).isEqualTo(120);
        assertThat(twelve.fanInGroupCountForDiagnostics()).isEqualTo(11);
    }

    /// Each language's group-zero searches, with its DFAs starved so that every search falls
    /// back as it does after a DFA bails, find what the capture engines find for the same pattern
    /// in a capture group.
    @Test
    public void testFallbackAgreesWithCaptureEngines()
    {
        List<Case> shared = List.of(
                new Case("[a-q][a-z ]{20,80}x", "a" + "z".repeat(80) + "x"),
                new Case("[a-q][^u-z]{10,30}x", "a" + "à".repeat(10) + "x"),
                // The earliest-ending match is not the leftmost-first one.
                new Case("a.{5}b|c", "a12c45b"),
                new Case("(?:ab|a)(?:bcd|c){1,3}", "abcdc"),
                new Case("x{2,6}?y|xz", "xxy"),
                // Leftmost-first takes the earlier alternative, leftmost-longest the longer one.
                new Case("a{1,3}|a{2,5}b", "aaaab"),
                new Case("(?i)[a-c]{3,9}z", "AbCz"),
                // These fill one word of state exactly, or step from the last position of a word
                // of state to the first of the next.
                new Case("a[b-r]{61,62}x", "a" + "b".repeat(62) + "x"),
                new Case("a[b-r]{62,63}x", "a" + "r".repeat(63) + "x"),
                new Case("a[b-r]{125,126}x", "a" + "c".repeat(126) + "x"),
                new Case("a[b-r]{126,127}x", "a" + "q".repeat(127) + "x"),
                new Case("a[b-r]{509,510}x", "a" + "b".repeat(510) + "x"),
                // Eleven fan-in groups across two words of state.
                new Case("(?:a[b-r]{1,9}){12}", "abc".repeat(12)));
        List<Case> bytes = new ArrayList<>(shared);
        bytes.addAll(List.of(
                new Case(BOUNDED_REPEAT, "a" + "b".repeat(20) + "x"),
                new Case("[a-q][^u-z]{1,80}[x\\xE0-\\xFF]", "abà"),
                new Case(UTF_8_BOUNDED_REPEAT, "a" + "c".repeat(10) + "ÿ"),
                new Case("[a-q][^u-z]{20,160}[x\\xE0-\\xFF]", "q" + "a".repeat(160) + "x"),
                new Case("[a-q].{20,80}x", "a" + " ".repeat(20) + "x")));

        for (Language language : LANGUAGES) {
            Random random = new Random(7);
            for (Case testCase : language.charset() == ISO_8859_1 ? bytes : shared) {
                String label = language.name() + " " + testCase.expression();
                CompiledPattern pattern = language.compiler().apply(testCase.expression());
                assertThat(pattern.forwardProgramForDiagnostics().positionNfa()).as(label).isNotNull();
                starveDfas(pattern);
                // A filler of a byte that begins no match.
                assertFallbackAgrees(label, language, pattern, testCase.match(), '!', 150, random);
            }
        }
    }

    /// The fallback agrees with the capture engines on random expressions of literals, classes,
    /// any character, case-insensitive groups, and alternations, under optional and bounded
    /// repeats, greedy or lazy, whose copies the automaton's shift, fan-in groups, and follow sets
    /// step through, a few with bounds from 20 to 79.
    @Test
    public void testFallbackAgreesOnRandomExpressions()
    {
        for (Language language : LANGUAGES) {
            Random random = new Random(20261109);
            int admitted = 0;
            for (int index = 0; index < EXPRESSIONS; index++) {
                StringBuilder expression = new StringBuilder();
                StringBuilder match = new StringBuilder();
                appendSequence(random, language.charset() == ISO_8859_1, 2, true, expression, match);
                String label = language.name() + " " + expression;
                CompiledPattern pattern = language.compiler().apply(expression.toString());
                // Every expression matches only bounded, non-empty text, but one can hold more
                // positions or fan-in tests than the automaton admits.
                if (pattern.forwardProgramForDiagnostics().positionNfa() == null) {
                    continue;
                }
                admitted++;
                // The DFAs starve before the first search: a DFA that search cached would serve
                // every later one in place of the automaton.
                starveDfas(pattern);
                // The filler is u, which [^u-z] does not match, or, where a run of u holds a
                // match, a line feed, which few expressions match.
                String uFiller = "u".repeat(pattern.forwardProgramForDiagnostics().positionNfa().maxLength());
                char filler = pattern.find(wrappedBuffer(uFiller.getBytes(language.charset()))) ? '\n' : 'u';
                assertFallbackAgrees(label, language, pattern, match.toString(), filler, 12, random);
            }
            assertThat(admitted).as(language.name()).isGreaterThan(EXPRESSIONS / 2);
        }
    }

    /// Checks that the group-zero searches of `pattern`, whose DFAs are starved, agree with the
    /// capture engines on texts of `match`: at the start and the end, adjacent, cut short, beside
    /// fillers longer than BitState's limits, beside malformed UTF-8 where the language allows
    /// it, and in `randomTexts` texts of runs of its characters. Each text is a view at
    /// [#BACKING_OFFSET] into its backing array.
    private static void assertFallbackAgrees(String label, Language language, CompiledPattern pattern, String match, char filler, int randomTexts, Random random)
    {
        CompiledPattern captured = language.compiler().apply("(" + pattern.pattern().toStringUtf8() + ")");
        assertThat(captured.matches(wrappedBuffer(match.getBytes(language.charset())))).as("%s fully matches %s", label, match).isTrue();

        // BitState searches a text as long as its limit itself; the automaton takes longer ones.
        Prog program = pattern.forwardProgramForDiagnostics();
        String unanchoredFiller = String.valueOf(filler).repeat(program.unanchoredBitStateTextMaxSize() + 1);
        String anchoredFiller = String.valueOf(filler).repeat(program.anchoredBitStateTextMaxSize() + 1);
        List<String> texts = new ArrayList<>();
        // Matches at the start and the end, adjacent matches, and a match cut short.
        for (String text : List.of(match, match + match, "zz" + match, match + "zz", match + match + "!" + match, match.substring(1))) {
            texts.addAll(List.of(text, unanchoredFiller + text, text + unanchoredFiller + text, text + anchoredFiller));
        }
        for (int index = 0; index < randomTexts; index++) {
            String text = runs(random, match);
            texts.add(text);
            if (index % 4 == 0) {
                texts.add(text + unanchoredFiller + text);
            }
        }
        List<byte[]> inputs = new ArrayList<>();
        for (String text : texts) {
            inputs.add(text.getBytes(language.charset()));
        }
        if (language.allowsMalformed()) {
            byte[] matchBytes = match.getBytes(language.charset());
            byte[] fillerBytes = unanchoredFiller.getBytes(language.charset());
            for (byte[] malformed : MALFORMED_UTF_8) {
                inputs.add(concat(matchBytes, malformed, matchBytes));
                inputs.add(concat(fillerBytes, malformed, matchBytes, malformed));
                inputs.add(concat(matchBytes, malformed, fillerBytes, matchBytes));
            }
        }
        for (byte[] input : inputs) {
            byte[] backing = new byte[BACKING_OFFSET + input.length];
            System.arraycopy(input, 0, backing, BACKING_OFFSET, input.length);
            assertAgrees(label, pattern, captured, wrappedBuffer(backing, BACKING_OFFSET, input.length));
        }
    }

    private static byte[] concat(byte[]... parts)
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            bytes.writeBytes(part);
        }
        return bytes.toByteArray();
    }

    /// Appends to `expression` a sequence of one to three random terms, and to `match` a text the
    /// sequence matches. A required sequence matches no empty text: its first term is a required
    /// atom or group, repeated at least once.
    private static void appendSequence(Random random, boolean latin1, int depth, boolean required, StringBuilder expression, StringBuilder match)
    {
        int terms = 1 + random.nextInt(3);
        for (int term = 0; term < terms; term++) {
            boolean requiredTerm = required && term == 0;
            StringBuilder termExpression = new StringBuilder();
            StringBuilder termMatch = new StringBuilder();
            int kind = depth == 0 ? 0 : random.nextInt(5);
            boolean atomTerm = kind <= 2;
            if (atomTerm) {
                Case atom = ATOMS.get(random.nextInt(latin1 ? ATOMS.size() - 1 : ATOMS.size()));
                termExpression.append(latin1 ? byteEscapes(atom.expression()) : atom.expression());
                termMatch.append(atom.match());
            }
            else {
                termExpression.append("(?:");
                appendSequence(random, latin1, depth - 1, requiredTerm, termExpression, termMatch);
                if (kind == 4) {
                    termExpression.append('|');
                    StringBuilder alternativeMatch = new StringBuilder();
                    appendSequence(random, latin1, depth - 1, requiredTerm, termExpression, alternativeMatch);
                    if (random.nextBoolean()) {
                        termMatch = alternativeMatch;
                    }
                }
                termExpression.append(')');
            }

            // The term occurs once, optionally, or in a bounded repeat, greedy or lazy. A few
            // single atoms take bounds from 20 to 79, which spread a class's copies across words
            // of state. A required term occurs at least once.
            int minimum = 1;
            int maximum = 1;
            int quantifier = random.nextInt(4);
            if (quantifier == 1 && !requiredTerm) {
                minimum = 0;
                termExpression.append(random.nextBoolean() ? "?" : "??");
            }
            else if (quantifier >= 2) {
                boolean wide = atomTerm && depth == 2 && random.nextInt(5) == 0;
                minimum = wide ? 20 + random.nextInt(41) : (requiredTerm ? 1 : 0) + random.nextInt(3);
                maximum = Math.max(1, minimum + random.nextInt(wide ? 20 : 4));
                termExpression.append('{').append(minimum).append(',').append(maximum).append('}');
                if (quantifier == 3) {
                    termExpression.append('?');
                }
            }
            expression.append(termExpression);
            match.append(termMatch.toString().repeat(minimum + random.nextInt(maximum - minimum + 1)));
        }
    }

    /// Writes each non-ASCII character of `expression` as the byte escape of its Latin-1 byte.
    private static String byteEscapes(String expression)
    {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < expression.length(); index++) {
            char character = expression.charAt(index);
            if (character < 0x80) {
                escaped.append(character);
            }
            else {
                escaped.append(String.format(Locale.ROOT, "\\x%02X", (int) character));
            }
        }
        return escaped.toString();
    }

    private record Case(String expression, String match) {}

    private record Language(String name, Charset charset, boolean allowsMalformed, Function<String, CompiledPattern> compiler) {}

    private record BooleanSearch(boolean found, boolean skipped) {}

    /// A schedule of `steps`, separated by commas, that replays boolean searches in flight
    /// together, and the streak of slow resets and whether the skip window is open after it.
    private record ReplayedSchedule(String name, String steps, int streak, boolean windowOpen) {}

    /// A mode that compiles `expression`, a bounded repeat, with `compiler`.
    private record ThrashingMode(String name, String expression, Function<String, CompiledPattern> compiler)
    {
        CompiledPattern compile(String expression)
        {
            return compiler.apply(expression);
        }
    }

    private static void assertAgrees(String label, CompiledPattern pattern, CompiledPattern captured, Slice input)
    {
        String description = label + " on " + describe(input);
        List<String> expected = spans(captured.matcher(input), 1);
        RegexpMatcher matcher = pattern.matcher(input);
        assertThat(spans(matcher, 0)).as(description).isEqualTo(expected);
        // Where BitState takes the automaton's widest window, on any text, it finds each span in
        // it, so no find steps the Pike NFA.
        Prog program = pattern.forwardProgramForDiagnostics();
        int widestWindow = 2 * program.positionNfa().maxLength();
        if (widestWindow <= program.unanchoredBitStateTextMaxSize() && program.listCount() * (widestWindow + 1L) <= CompiledPattern.POSITION_NFA_WINDOW_BIT_STATE_MAXIMUM_BYTES * Byte.SIZE) {
            assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).as(description).isFalse();
        }
        // A region that starts past the first byte searches from a nonzero start.
        if (input.length() > 0) {
            List<String> expectedInRegion = spans(captured.matcher(input).reset(input, 1, input.length()), 1);
            assertThat(spans(pattern.matcher(input).reset(input, 1, input.length()), 0)).as("%s in a region from 1", description).isEqualTo(expectedInRegion);
        }
        assertThat(pattern.count(input)).as(description).isEqualTo(expected.size());
        assertThat(pattern.find(input)).as(description).isEqualTo(!expected.isEmpty());

        boolean expectedMatches = captured.matcher(input).matches();
        assertThat(pattern.matches(input)).as(description).isEqualTo(expectedMatches);
        assertThat(pattern.matcher(input).matches()).as(description).isEqualTo(expectedMatches);

        RegexpMatcher expectedLookingAt = captured.matcher(input);
        boolean lookingAt = expectedLookingAt.lookingAt();
        assertThat(pattern.lookingAt(input)).as(description).isEqualTo(lookingAt);
        RegexpMatcher actualLookingAt = pattern.matcher(input);
        assertThat(actualLookingAt.lookingAt()).as(description).isEqualTo(lookingAt);
        if (lookingAt) {
            assertThat(actualLookingAt.end()).as(description).isEqualTo(expectedLookingAt.end());
        }
    }

    /// Starves the forward DFAs before their first search, so that each search finds them unable
    /// to initialize and falls back as it does after a DFA bails.
    private static void starveDfas(CompiledPattern pattern)
    {
        pattern.forwardProgramForDiagnostics().setDfaMemory(0);
        if (pattern.booleanProgramForDiagnostics() != null) {
            pattern.booleanProgramForDiagnostics().setDfaMemory(0);
        }
    }

    /// Returns each find's span of `group`, which is group zero's span in the capture-group form.
    private static List<String> spans(RegexpMatcher matcher, int group)
    {
        List<String> spans = new ArrayList<>();
        while (matcher.find()) {
            spans.add(matcher.start(group) + ":" + matcher.end(group));
        }
        return spans;
    }

    /// Returns `input` as Latin-1 text, with each run of more than 16 of one character written
    /// as the character, `×`, and the run's length.
    private static String describe(Slice input)
    {
        String text = input.toString(ISO_8859_1);
        StringBuilder description = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            int runEnd = index + 1;
            while (runEnd < text.length() && text.charAt(runEnd) == character) {
                runEnd++;
            }
            if (runEnd - index > 16) {
                description.append(character).append('×').append(runEnd - index);
            }
            else {
                description.append(text, index, runEnd);
            }
            index = runEnd;
        }
        return description.toString();
    }

    /// Random text of runs of the characters of `match` and a few others, so that runs of a
    /// repeated class come in lengths around its bounds.
    private static String runs(Random random, String match)
    {
        String alphabet = match + "abcqrxyz \nàÿ";
        StringBuilder text = new StringBuilder();
        int length = random.nextInt(2 * match.length() + 40);
        while (text.length() < length) {
            char character = alphabet.charAt(random.nextInt(alphabet.length()));
            int run = random.nextInt(4) == 0 ? 1 + random.nextInt(match.length() + 1) : 1;
            text.append(String.valueOf(character).repeat(run));
        }
        return text.toString();
    }

    private static void assertPositions(CompiledPattern pattern, int positionCount, int maxLength)
    {
        PositionNfa positionNfa = pattern.forwardProgramForDiagnostics().positionNfa();
        String label = pattern.pattern().toStringUtf8();
        assertThat(positionNfa).as(label).isNotNull();
        assertThat(positionNfa.positionCountForDiagnostics()).as(label).isEqualTo(positionCount);
        assertThat(positionNfa.maxLength()).as(label).isEqualTo(maxLength);
    }

    private static RustRegexp rustBytes(String expression)
    {
        return RustRegexp.compile(utf8Slice(expression), RustRegexp.Options.bytes().setUnicode(false));
    }

    /// Short words of the letters a to t, digits, and punctuation, with an x every 997 bytes.
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
}
