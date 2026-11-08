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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDfaCountMatches
{
    // Case folding keeps the pattern off the disjoint-suffix repeat matcher, which would
    // answer it without the DFA. The input has no uppercase letters, so (?i) changes no match
    // and the pinned native RE2 counts still hold.
    private static final String STATE_GROWTH = "(?i)[a-q][^u-z]{80}x";

    @Test
    public void testCountsSuccessiveMatchesInOneSearchSession()
    {
        Prog program = compile("[a-z]+");

        assertThat(Dfa.countMatches(program, utf8Slice("one 22 three 444 five"), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(3);
    }

    @Test
    public void testPreservesContextAtSuccessiveBoundaries()
    {
        Prog program = compile("\\b[a-z]+\\b");

        assertThat(Dfa.countMatches(program, utf8Slice("one_two three four5 five"), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(2);
    }

    @Test
    public void testHonorsMatchKind()
    {
        Prog program = compile("a|aa");

        assertThat(Dfa.countMatches(program, utf8Slice("aa"), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(2);
        assertThat(Dfa.countMatches(program, utf8Slice("aa"), Prog.MatchKind.LONGEST_MATCH))
                .isEqualTo(1);
    }

    @Test
    public void testNonzeroSliceOffset()
    {
        Prog program = compile("[a-z]+");
        byte[] bytes = utf8Slice("--one 22 three--").getBytes();

        assertThat(Dfa.countMatches(program, Slices.wrappedBuffer(bytes, 2, bytes.length - 4), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(2);
    }

    @Test
    public void testShortFixedDistanceSkipUsesCompactContinuation()
    {
        Prog program = compile("[a-z]shing");
        String input = "xxxxxxsxxxxxx".repeat(512) + "ashing";

        assertThat(Dfa.countMatches(program, utf8Slice(input), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(1);
        assertThat(program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).fixedDistanceShortSkipFallbackCount())
                .isGreaterThan(0);
    }

    @Test
    public void testRejectsEmptyMatches()
    {
        Prog program = compile("x*");

        assertThat(Dfa.countMatches(program, utf8Slice("abc"), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(Dfa.COUNT_UNSUPPORTED);
    }

    @Test
    public void testCountDfaThrashingSkipsDfaUntilCacheReset()
    {
        Re2 pattern = Re2.compile(utf8Slice(STATE_GROWTH), Re2.Options.latin1().setMaxMemory(3L << 20));
        byte[] bytes = stateExplosionInput(16 * 1024);
        var input = Slices.wrappedBuffer(bytes);

        // Pinned native RE2 finds five non-overlapping matches in this deterministic input.
        RegexpMatcher matcher = pattern.matcher(input, 0);
        int nativeMatchCount = 0;
        while (matcher.find()) {
            nativeMatchCount++;
        }
        assertThat(nativeMatchCount).isEqualTo(5);

        assertThat(pattern.countMatches(input)).isEqualTo(Dfa.COUNT_UNSUPPORTED);
        Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        int resetCount = dfa.resetCount();
        assertThat(dfa.countSearchBailedWhenSlow()).isTrue();

        RegexpMatcher fallbackMatcher = pattern.matcher(input, 0);
        int fallbackCount = 0;
        while (fallbackMatcher.find()) {
            fallbackCount++;
        }
        assertThat(fallbackCount).isEqualTo(nativeMatchCount);
        assertThat(dfa.resetCount()).isEqualTo(resetCount);
        assertThat(dfa.countSearchBailedWhenSlow()).isTrue();

        assertThat(pattern.countMatches(input)).isEqualTo(Dfa.COUNT_UNSUPPORTED);
        int resetCountAfterFallback = dfa.resetCount();
        assertThat(pattern.countMatches(input)).isEqualTo(Dfa.COUNT_UNSUPPORTED);
        assertThat(dfa.resetCount()).isEqualTo(resetCountAfterFallback);

        dfa.resetCacheExternal();
        assertThat(dfa.countSearchBailedWhenSlow()).isFalse();

        int resetCountAfterExternalReset = dfa.resetCount();
        fallbackMatcher.reset(input);
        int countAfterExternalReset = 0;
        while (fallbackMatcher.find()) {
            countAfterExternalReset++;
        }
        assertThat(countAfterExternalReset).isEqualTo(nativeMatchCount);
        assertThat(dfa.resetCount()).isGreaterThan(resetCountAfterExternalReset);
    }

    @Test
    public void testStartStateExhaustionDoesNotBecomeNoMatch()
    {
        Re2 pattern = Re2.compile(utf8Slice(STATE_GROWTH), Re2.Options.latin1().setMaxMemory(4L << 20));
        var input = Slices.wrappedBuffer(stateExplosionInput(16 * 1024));

        RegexpMatcher fillingMatcher = pattern.matcher(input);
        int knownMatchStart = -1;
        int matchCount = 0;
        while (fillingMatcher.find()) {
            if (knownMatchStart < 0) {
                knownMatchStart = fillingMatcher.start();
            }
            matchCount++;
        }
        assertThat(matchCount).isEqualTo(5);

        RegexpMatcher reused = pattern.matcher(input).reset(input, knownMatchStart, input.length());
        assertThat(reused.lookingAt()).isTrue();

        Re2 fresh = Re2.compile(utf8Slice(STATE_GROWTH), Re2.Options.latin1().setMaxMemory(4L << 20));
        assertThat(fresh.matcher(input).reset(input, knownMatchStart, input.length()).lookingAt()).isTrue();
    }

    @Test
    public void testSuccessfulCountDfaThrashingSkipsDfaUntilCacheReset()
    {
        for (Re2.Options options : new Re2.Options[] {Re2.Options.defaults(), Re2.Options.latin1()}) {
            // An outer capture keeps this on the general route rather than the optional
            // capture-free suffix scanner. Counting still requests only group zero.
            Re2 pattern = Re2.compile(
                    utf8Slice("([a-q][^u-z]{23}x)"),
                    options.setMaxMemory(16L << 20));
            var input = Slices.wrappedBuffer(stateExplosionInput(1024 * 1024));

            // Pinned native RE2 finds 529 non-overlapping matches in this deterministic input.
            assertThat(pattern.count(input)).isEqualTo(529);
            assertThat(pattern.countMatches(input)).isEqualTo(Dfa.COUNT_UNSUPPORTED);
            Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics()
                    .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
            assertThat(dfa.resetCount()).isGreaterThanOrEqualTo(2);
            assertThat(dfa.byteScanFallbackCount()).isPositive();
            assertThat(dfa.countSearchBailedWhenSlow()).isTrue();

            RegexpMatcher matcher = pattern.matcher(input, 0);
            int fallbackCount = 0;
            while (matcher.find()) {
                fallbackCount++;
            }
            assertThat(fallbackCount).isEqualTo(529);

            int resetCountAfterFallback = dfa.resetCount();
            assertThat(pattern.count(input)).isEqualTo(529);
            assertThat(dfa.resetCount()).isEqualTo(resetCountAfterFallback);
            assertThat(pattern.countMatches(input)).isEqualTo(Dfa.COUNT_UNSUPPORTED);
            assertThat(dfa.resetCount()).isEqualTo(resetCountAfterFallback);

            dfa.resetCacheExternal();
            assertThat(dfa.countSearchBailedWhenSlow()).isFalse();
        }
    }

    @Test
    public void testSuccessfulCountDfaThrashingKeepsProductiveScanner()
    {
        // Case folding keeps the pattern off the disjoint-suffix repeat matcher, which would
        // answer it without the DFA. The input has no uppercase letters, so (?i) changes no match
        // and the pinned native RE2 count still holds.
        Re2 pattern = Re2.compile(utf8Slice("(?i)[a-q][^u-z]{13}x"), Re2.Options.latin1().setMaxMemory(3L << 19));
        var input = Slices.wrappedBuffer(stateExplosionInput(2 * 1024 * 1024));

        // Pinned native RE2 finds 1,074 non-overlapping matches in this deterministic input.
        assertThat(pattern.countMatches(input)).isEqualTo(1_074);
        Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(dfa.resetCount()).isGreaterThanOrEqualTo(2);
        assertThat(dfa.byteScanFallbackCount()).isZero();
        assertThat(dfa.countSearchBailedWhenSlow()).isFalse();
    }

    @Test
    public void testPointerTableThrashingKeepsDfa()
    {
        // These texts reach about 2,700 DFA states. A 2 MiB budget holds them all on object rows,
        // where the DFA never resets, but not beside the pointer table, which a fresh DFA keeps
        // through its first two resets, a native table whenever the collector runs. A count that
        // resets too often while the DFA has the table gives the table back instead of leaving the
        // count, and later finds, to the NFA.
        Slice patternText = utf8Slice("[ab]*a[ab]{12}c");
        Re2 reference = Re2.compile(patternText);
        Random random = new Random(1);
        String[] blocks = new String[100];
        for (int index = 0; index < blocks.length; index++) {
            StringBuilder block = new StringBuilder();
            for (int position = 0; position < 40; position++) {
                block.append(random.nextBoolean() ? 'a' : 'b');
            }
            blocks[index] = block.toString();
        }
        // The count resets twice between matches, or within its search through the blocks no c
        // completes; there the search gives the table back, and the matches after it must not
        // count the resets it made with the table.
        Slice manyMatches = utf8Slice(blockText(random, blocks, 3_000, 'c'));
        Slice longSearch = utf8Slice(blockText(random, blocks, 200, 'c') + blockText(random, blocks, 3_000, 'e') + blockText(random, blocks, 3_000, 'c'));

        for (Slice input : List.of(manyMatches, longSearch)) {
            // The first finds build the table without a reset.
            Re2 pattern = Re2.compile(patternText, Re2.Options.defaults().setMaxMemory(2L << 20));
            RegexpMatcher firstMatches = pattern.matcher(input);
            for (int match = 0; match < 80; match++) {
                assertThat(firstMatches.find()).isTrue();
            }
            Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
            assertThat(dfa.resetCount()).isZero();

            assertThat(pattern.count(input)).isEqualTo(reference.count(input));
            assertThat(dfa.resetCount()).isGreaterThanOrEqualTo(2);
            RegexpMatcher matcher = pattern.matcher(input);
            assertThat(findCount(matcher)).isEqualTo(findCount(reference.matcher(input)));
            assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isFalse();
            assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).isFalse();
        }
    }

    @Test
    public void testLockFreeCountHandsPointerTableResetsToLock()
            throws Exception
    {
        // A count over cached states runs without the lock, and between two of its matches finds
        // on another text reset the DFA twice while it holds the pointer table; neither find
        // resets often enough itself to give the table back. With the table the count goes on
        // under the lock, which gives the table back, and reports each match end once.
        Re2 pattern = Re2.compile(utf8Slice("(?i)[a-q][^u-z]{13}x"), Re2.Options.latin1().setMaxMemory(512L << 10));
        Prog program = pattern.forwardProgramForDiagnostics();
        Slice cached = utf8Slice(("x".repeat(15) + "a".repeat(14) + "x").repeat(4_000));
        assertThat(Dfa.countMatches(program, cached, Prog.MatchKind.FIRST_MATCH)).isEqualTo(4_000);
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        RegexpMatcher growth = pattern.matcher(Slices.wrappedBuffer(stateExplosionInput(1024 * 1024)));
        int[] matchEnds = {0};

        long result = Dfa.countMatches(program, cached, 0, Prog.MatchKind.FIRST_MATCH, _ -> {
            if (matchEnds[0]++ == 0) {
                assertThat(dfa.cacheMutationLockedForTesting()).isFalse();
                resetTwice(dfa, growth);
                assertThat(dfa.releasedAbsolutePointerResetCount()).isZero();
            }
            return true;
        }, null);

        assertThat(dfa.countSearchBailedWhenSlow()).isFalse();
        assertThat(result).isEqualTo(4_000L << Integer.SIZE | cached.length());
        assertThat(matchEnds[0]).isEqualTo(4_000);
        assertThat(dfa.releasedAbsolutePointerResetCount()).isPositive();

        // Now the DFA has no table to give back: it gave it back above. A count that sees two
        // foreign resets gives up without the lock, which this thread holds from the resets until
        // the count returns.
        dfa.resetCacheExternal();
        assertThat(Dfa.countMatches(program, cached, Prog.MatchKind.FIRST_MATCH)).isEqualTo(4_000);
        CountDownLatch resetsMade = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Long> count = executor.submit(() -> Dfa.countMatches(program, cached, 0, Prog.MatchKind.FIRST_MATCH, _ -> {
                if (resetsMade.getCount() != 0) {
                    resetTwice(dfa, growth);
                    resetsMade.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!dfa.cacheMutationLockedForTesting()) {
                        if (Thread.interrupted() || System.nanoTime() >= deadline) {
                            throw new AssertionError("The cache mutation lock was not taken");
                        }
                        Thread.onSpinWait();
                    }
                }
                return true;
            }, null));
            try {
                assertThat(resetsMade.await(10, TimeUnit.SECONDS)).isTrue();
                dfa.beginSearch(true);
                try {
                    assertThat(count.get(10, TimeUnit.SECONDS)).isEqualTo(Dfa.COUNT_UNSUPPORTED);
                }
                finally {
                    dfa.cancelExclusiveSearch();
                }
            }
            finally {
                executor.shutdownNow();
            }
        }
        assertThat(dfa.countSearchBailedWhenSlow()).isTrue();
    }

    @Test
    public void testLockFreeCountHandsOffResetsJudgedBeforeRelease()
    {
        // Another search can give the table back between a lock-free count's judgement that its
        // resets came too often and its choice to give up. No reset after the one that drops the
        // table has come yet, so the count hands the resets to the lock rather than giving up.
        Re2 pattern = Re2.compile(utf8Slice("(?i)[a-q][^u-z]{13}x"), Re2.Options.latin1().setMaxMemory(512L << 10));
        Prog program = pattern.forwardProgramForDiagnostics();
        Slice cached = utf8Slice(("x".repeat(15) + "a".repeat(14) + "x").repeat(4_000));
        assertThat(Dfa.countMatches(program, cached, Prog.MatchKind.FIRST_MATCH)).isEqualTo(4_000);
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        int initialResetCount = dfa.resetCount();
        resetTwice(dfa, pattern.matcher(Slices.wrappedBuffer(stateExplosionInput(1024 * 1024))));
        assertThat(dfa.countResetsTooOften(initialResetCount, false)).isTrue();

        dfa.beginSearch(true);
        try {
            assertThat(dfa.releaseAbsolutePointerTransitionsForSlowReset()).isTrue();
        }
        finally {
            dfa.endSearch(true);
        }
        assertThat(dfa.countHandsResetsToLock(initialResetCount)).isTrue();
    }

    @Test
    public void testLockedCountKeepsHandedOffResets()
            throws Exception
    {
        // A count without the lock hands two resets to the lock while another search holds it.
        // Before the count gets the lock, that search gives the table back, makes the reset that
        // drops it, and one more. The count must count that reset, so it gives up at the first
        // reset its own searches make under the lock.
        Re2 pattern = Re2.compile(utf8Slice("(?i)[a-q][^u-z]{13}x"), Re2.Options.latin1().setMaxMemory(512L << 10));
        Prog program = pattern.forwardProgramForDiagnostics();
        String match = "x".repeat(15) + "a".repeat(14) + "x";
        assertThat(Dfa.countMatches(program, utf8Slice(match.repeat(4_000)), Prog.MatchKind.FIRST_MATCH)).isEqualTo(4_000);
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        // After the cached matches, short pieces of state growth between matches make the count's
        // own searches reset the DFA, each piece at most once; the matches after each piece keep
        // the byte scans sampled, so the matches between pieces are judged.
        Random random = new Random(7);
        byte[] alphabet = "abcdefghijklmnopqrst \n,.;0123456789".getBytes(US_ASCII);
        StringBuilder text = new StringBuilder(match.repeat(4_000));
        for (int piece = 0; piece < 40; piece++) {
            for (int index = 0; index < 300; index++) {
                text.append((char) alphabet[random.nextInt(alphabet.length)]);
            }
            text.append(match.repeat(20));
        }
        text.append(match.repeat(4_000));
        Slice input = utf8Slice(text.toString());
        RegexpMatcher growth = pattern.matcher(Slices.wrappedBuffer(stateExplosionInput(1024 * 1024)));
        int startResetCount = dfa.resetCount();

        CountDownLatch resetsMade = new CountDownLatch(1);
        AtomicReference<Thread> countThread = new AtomicReference<>();
        long result;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Long> count = executor.submit(() -> Dfa.countMatches(program, input, 0, Prog.MatchKind.FIRST_MATCH, _ -> {
                if (resetsMade.getCount() != 0) {
                    resetTwice(dfa, growth);
                    countThread.set(Thread.currentThread());
                    resetsMade.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!dfa.cacheMutationLockedForTesting()) {
                        if (Thread.interrupted() || System.nanoTime() >= deadline) {
                            throw new AssertionError("The cache mutation lock was not taken");
                        }
                        Thread.onSpinWait();
                    }
                }
                return true;
            }, null));
            try {
                assertThat(resetsMade.await(10, TimeUnit.SECONDS)).isTrue();
                dfa.beginSearch(true);
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!count.isDone() && LockSupport.getBlocker(countThread.get()) == null) {
                        assertThat(System.nanoTime()).isLessThan(deadline);
                        Thread.onSpinWait();
                    }
                    if (!count.isDone() && dfa.releaseAbsolutePointerTransitionsForSlowReset()) {
                        dfa.resetCacheExternal();
                        dfa.resetCacheExternal();
                    }
                }
                finally {
                    dfa.cancelExclusiveSearch();
                }
                result = count.get(10, TimeUnit.SECONDS);
            }
            finally {
                executor.shutdownNow();
            }
        }

        assertThat(result).isEqualTo(Dfa.COUNT_UNSUPPORTED);
        assertThat(dfa.countSearchBailedWhenSlow()).isTrue();
        int releasedResetCount = dfa.releasedAbsolutePointerResetCount();
        assertThat(releasedResetCount).isPositive();
        assertThat(dfa.resetCount() - Math.max(startResetCount, releasedResetCount)).isEqualTo(2);
    }

    @Test
    public void testSelfLoopExitScanCountsRepeatedFiller()
    {
        for (String expression : new String[] {".*(x|y).*", ".*[xy].*"}) {
            for (int length : new int[] {1_024, 32_768}) {
                TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression));
                Slice input = utf8Slice("a".repeat(length));
                for (int iteration = 0; iteration < 3; iteration++) {
                    int scanCount = countSelfLoopScanCount(pattern);
                    assertThat(pattern.count(input))
                            .as("%s length=%s", expression, length)
                            .isZero();
                    // One scan covers the whole input.
                    assertThat(countSelfLoopScanCount(pattern) - scanCount)
                            .as("%s length=%s", expression, length)
                            .isEqualTo(VectorSupport.isAvailable() ? 1 : 0);
                }

                assertSelfLoopScanCount(expression, utf8Slice("a".repeat(length - 1) + "x"), 1);
            }
        }
    }

    @Test
    public void testSelfLoopExitScanMinimumLength()
    {
        // The route needs 64 bytes remaining.
        for (String expression : new String[] {".*(x|y).*", ".*[xy].*"}) {
            assertSelfLoopScanCount(expression, utf8Slice("a".repeat(62) + "x"), 1, 0);
            assertSelfLoopScanCount(expression, utf8Slice("a".repeat(63) + "x"), 1, 1);
        }
    }

    @Test
    public void testSelfLoopExitScanCountsMatchingLines()
    {
        LineInput input = lineInput(new Random(11), false, false);
        assertSelfLoopScanCount(".*(x|y).*", input.source(), input.matchingLines());
        assertSelfLoopScanCount(".*[xy].*", input.source(), input.matchingLines());
    }

    @Test
    public void testSelfLoopExitScanCountsMultibyteLines()
    {
        LineInput input = lineInput(new Random(12), true, false);
        assertSelfLoopScanCount(".*(x|y).*", input.source(), input.matchingLines());
        assertSelfLoopScanCount(".*[xy].*", input.source(), input.matchingLines());
    }

    @Test
    public void testSelfLoopExitScanCountsInvalidUtf8Lines()
    {
        for (boolean multibyte : new boolean[] {false, true}) {
            Slice source = lineInput(new Random(13), multibyte, true).source();
            assertSelfLoopScanCount(".*(x|y).*", source, trinoMatcherCount(".*(x|y).*", source));
            assertSelfLoopScanCount(".*[xy].*", source, trinoMatcherCount(".*[xy].*", source));
        }
    }

    @Test
    public void testSelfLoopExitScanMatchesAtTextEdges()
    {
        String filler = "a".repeat(2_000);
        assertSelfLoopScanCount(".*(x|y).*", utf8Slice("x" + filler), 1);
        assertSelfLoopScanCount(".*(x|y).*", utf8Slice(filler + "x"), 1);
        assertSelfLoopScanCount(".*(x|y).*", utf8Slice("x" + filler + "\n" + filler + "y"), 2);
        assertSelfLoopScanCount(".*(x|y).*", utf8Slice(filler + "x\n" + filler + "\ny"), 2);
        assertSelfLoopScanCount(".*(x|y).*", utf8Slice("\u00e9" + filler + "\u4e2dx\ud83d\udcb0"), 1);
    }

    @Test
    public void testSelfLoopExitScanResumesAfterMultibyteCharacters()
    {
        for (String character : new String[] {"\u00e9", "\u4e2d", "\ud83d\udcb0", "\u0080"}) {
            String chunks = (character + "a".repeat(100)).repeat(50);
            // Without a match, each chunk is one scan of the plain loop. After the leading x, the
            // matching loop resumes after each character: one scan before the first chunk and one per chunk.
            assertSelfLoopScanCount(".*(x|y).*", utf8Slice(chunks), 0, 50);
            assertSelfLoopScanCount(".*(x|y).*", utf8Slice("xaaaaaaaa" + chunks), 1, 51);
        }
    }

    @Test
    public void testSelfLoopExitScanNonzeroSliceOffset()
    {
        LineInput lines = lineInput(new Random(14), true, false);
        byte[] bytes = new byte[lines.source().length() + 16];
        Arrays.fill(bytes, (byte) 'x');
        lines.source().getBytes(0, bytes, 7, lines.source().length());
        assertSelfLoopScanCount(".*(x|y).*", Slices.wrappedBuffer(bytes, 7, lines.source().length()), lines.matchingLines());

        byte[] filler = ("xyxy" + "a".repeat(2_000) + "\n" + "a".repeat(1_000) + "x" + "a".repeat(1_000) + "yxyx").getBytes(US_ASCII);
        assertSelfLoopScanCount(".*(x|y).*", Slices.wrappedBuffer(filler, 4, filler.length - 8), 1);
        assertSelfLoopScanCount(".*(x|y).*", Slices.wrappedBuffer(filler, 4, 2_001), 0);
        assertSelfLoopScanCount(".*(x|y).*", Slices.wrappedBuffer(filler, 2, filler.length - 2), 2);
    }

    @Test
    public void testSelfLoopExitScanDotAll()
    {
        String filler = "ab\n\u00e9\u4e2d\ud83d\udcb0".repeat(1_024);
        for (String input : new String[] {filler, filler + "x" + filler, filler + "y", "x" + filler}) {
            Re2 pattern = Re2.compile(utf8Slice("(?s).*(x|y).*"));
            assertThat(pattern.count(utf8Slice(input))).isEqualTo(input.contains("x") || input.contains("y") ? 1 : 0);
            assertThat(pattern.forwardProgramForDiagnostics()
                    .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH)
                    .countSelfLoopScanCount() > 0)
                    .isEqualTo(VectorSupport.isAvailable());
        }
    }

    @Test
    public void testSelfLoopExitScanDenseExitBytes()
    {
        for (String input : new String[] {"xy".repeat(4_096), "\n".repeat(8_192), "x\n".repeat(4_096), "ax\ny\n\n".repeat(2_048), "\u00e9".repeat(4_096)}) {
            TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(".*(x|y).*"));
            assertThat(pattern.count(utf8Slice(input)))
                    .isEqualTo(trinoMatcherCount(".*(x|y).*", utf8Slice(input)))
                    .isEqualTo(input.lines().filter(line -> line.contains("x") || line.contains("y")).count());
        }
    }

    @Test
    public void testSelfLoopStayRangeScan()
    {
        // Each search of `.*[^A-Z]|[A-Z]` over a run of capitals records the `[A-Z]` match and then
        // follows `.*[^A-Z]` to the end of the text, in a state that stays on A-Z and leaves on the
        // other 230 bytes, too many for the exit form. Each search with at least 64 bytes left scans
        // that run for the first byte outside the bytes it stays on: one range for capitals,
        // lowercase letters, and digits, and four for word characters.
        String[][] cases = {
                {".*[^A-Z]|[A-Z]", "A"},
                {".*[^a-z]|[a-z]", "a"},
                {".*\\D|\\d", "1"},
                {".*\\W|\\w", "aZ_9"},
        };
        for (String[] testCase : cases) {
            for (int length : new int[] {100, 1_000}) {
                Slice source = utf8Slice(testCase[1].repeat(length / testCase[1].length()));
                Prog program = compile(testCase[0]);
                assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH)).isEqualTo(length);
                assertThat(program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).selfLoopStayRangeScanCount())
                        .as("%s length=%s", testCase[0], length)
                        .isEqualTo(VectorSupport.isAvailable() ? length - 63 : 0);
            }
        }

        // Unused range slots are zero, which would read as a range holding only NUL, so a NUL after
        // a run of capitals shows that the scan reads only the loop's ranges.
        Slice nulSeparated = utf8Slice("A".repeat(100) + "\u0000" + "A".repeat(100));
        assertThat(Dfa.countMatches(compile(".*[^A-Z]|[A-Z]"), nulSeparated, Prog.MatchKind.FIRST_MATCH)).isEqualTo(101);

        // Latin-1 builds the loop from single-byte characters rather than UTF-8 sequences, and
        // scans the same runs.
        Prog latin1 = Re2.compile(utf8Slice(".*[^A-Z]|[A-Z]"), Re2.Options.latin1()).forwardProgramForDiagnostics();
        assertThat(Dfa.countMatches(latin1, utf8Slice("A".repeat(1_000)), Prog.MatchKind.FIRST_MATCH)).isEqualTo(1_000);
        assertThat(latin1.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).selfLoopStayRangeScanCount()).isEqualTo(VectorSupport.isAvailable() ? 1_000 - 63 : 0);

        // In Latin-1, the `.*` of `.*[A-Z]|[^A-Z]` stays on every byte except a newline and the
        // capitals, so the run from `[` through 0xFF, 165 bytes, takes two ranges, the first of
        // the most bytes one range holds.
        byte[] lowercaseAndHigh = new byte[1_000];
        for (int index = 0; index < lowercaseAndHigh.length; index++) {
            lowercaseAndHigh[index] = (byte) (index % 2 == 0 ? 'a' + index % 26 : 0x80 + index % 128);
        }
        Prog wide = Re2.compile(utf8Slice(".*[A-Z]|[^A-Z]"), Re2.Options.latin1()).forwardProgramForDiagnostics();
        assertThat(Dfa.countMatches(wide, Slices.wrappedBuffer(lowercaseAndHigh), Prog.MatchKind.FIRST_MATCH)).isEqualTo(1_000);
        assertThat(wide.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).selfLoopStayRangeScanCount()).isEqualTo(VectorSupport.isAvailable() ? 1_000 - 63 : 0);
    }

    @Test
    public void testSelfLoopStayRangeScanMatchesStepping()
    {
        // Runs of random lengths of the bytes each loop stays on end at separators, multibyte
        // characters, and the region end, and the bytes outside the region extend the runs.
        // java.util.regex steps every character, so it decides the counts and the matches.
        String[] expressions = {".*[^A-Z]|[A-Z]", "[A-Z]+", "[a-z]+,", "\\d+;", "\\w+;", ".*\\W|\\w"};
        String[] runs = {"A", "Q", "z", "a", "5", "_", "aZ_9"};
        String[] separators = {",", ";", " ", "\n", "\u0000", "\u00e9", "\u4e2d", "\ud83d\udcb0"};
        Random random = new Random(21);
        int stayRangeScanCount = 0;
        for (int iteration = 0; iteration < 30; iteration++) {
            StringBuilder builder = new StringBuilder();
            while (builder.length() < 1_000) {
                builder.append(runs[random.nextInt(runs.length)].repeat(random.nextInt(100)));
                builder.append(separators[random.nextInt(separators.length)]);
            }
            String run = runs[random.nextInt(runs.length)];
            String text = builder + run.repeat(random.nextInt(100));
            byte[] textBytes = text.getBytes(UTF_8);
            int[] byteOffsets = utf8Offsets(text);
            byte[] padding = run.repeat(80).getBytes(UTF_8);
            byte[] backing = new byte[padding.length + textBytes.length + padding.length];
            System.arraycopy(padding, 0, backing, 0, padding.length);
            System.arraycopy(textBytes, 0, backing, padding.length, textBytes.length);
            System.arraycopy(padding, 0, backing, padding.length + textBytes.length, padding.length);
            Slice source = Slices.wrappedBuffer(backing, padding.length, textBytes.length);

            for (String expression : expressions) {
                List<Long> expectedMatches = new ArrayList<>();
                Matcher oracle = Pattern.compile(expression, Pattern.UNIX_LINES).matcher(text);
                while (oracle.find()) {
                    expectedMatches.add(((long) byteOffsets[oracle.start()] << 32) | byteOffsets[oracle.end()]);
                }

                Prog program = compile(expression);
                assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH)).as("%s iteration=%s", expression, iteration).isEqualTo(expectedMatches.size());
                stayRangeScanCount += program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).selfLoopStayRangeScanCount();

                Re2 pattern = Re2.compile(utf8Slice(expression));
                assertThat(pattern.count(source)).as("%s iteration=%s", expression, iteration).isEqualTo(expectedMatches.size());
                List<Long> matches = new ArrayList<>();
                RegexpMatcher matcher = pattern.matcher(source);
                while (matcher.find()) {
                    matches.add(((long) matcher.start() << 32) | matcher.end());
                }
                assertThat(matches).as("%s iteration=%s", expression, iteration).isEqualTo(expectedMatches);
            }
        }
        assertThat(stayRangeScanCount > 0).isEqualTo(VectorSupport.isAvailable());
    }

    @Test
    public void testSelfLoopScanRejectsUnrepresentableByteSets()
    {
        // The loop leaves on five digits and stays on more than four ranges around them.
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(".*[02468].*"));
        assertThat(pattern.count(utf8Slice("a".repeat(32_768)))).isZero();
        Dfa.DfaInstance dfa = pattern.re2().forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(dfa.countSelfLoopScanCount()).isZero();
        assertThat(dfa.pairedTransitionMemory()).isPositive();

        TestingTrinoRegexpBenchmarkInputs.Input input = TestingTrinoRegexpBenchmarkInputs.create("captureSparse", 32_768);
        Prog capture = compile(input.pattern().toStringUtf8());
        assertThat(Dfa.countMatches(capture, input.source(), Prog.MatchKind.FIRST_MATCH)).isEqualTo(3);
        assertThat(countSelfLoopScanCount(capture, Dfa.DfaInstance.Kind.FIRST_MATCH)).isZero();
    }

    @Test
    public void testSelfLoopExitScanStopsForIneligiblePattern()
    {
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(".*[02468].*"));
        Slice input = utf8Slice(("a".repeat(50) + "2\n").repeat(20) + "a".repeat(300));
        Dfa.DfaInstance dfa = pattern.re2().forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        // Without the vector scanners, the count never tries the route.
        int unproductiveLimit = VectorSupport.isAvailable() ? 8 : 0;

        assertThat(pattern.count(input)).isEqualTo(20);
        assertThat(dfa.countSelfLoopUnproductiveCount()).isEqualTo(unproductiveLimit);
        for (int iteration = 0; iteration < 4; iteration++) {
            assertThat(pattern.count(input)).isEqualTo(20);
            assertThat(dfa.countSelfLoopUnproductiveCount()).isEqualTo(unproductiveLimit);
        }
        assertThat(dfa.countSelfLoopScanCount()).isZero();

        dfa.resetCacheExternal();
        assertThat(dfa.countSelfLoopUnproductiveCount()).isZero();
        assertThat(pattern.count(input)).isEqualTo(20);
        assertThat(dfa.countSelfLoopUnproductiveCount()).isEqualTo(unproductiveLimit);
    }

    @Test
    public void testSelfLoopExitScanContinuesForEligiblePattern()
    {
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(".*(x|y).*"));
        LineInput input = lineInput(new Random(15), true, false);
        Dfa.DfaInstance dfa = pattern.re2().forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

        for (int iteration = 0; iteration < 50; iteration++) {
            int scanCount = dfa.countSelfLoopScanCount();
            assertThat(pattern.count(input.source())).isEqualTo(input.matchingLines());
            assertThat(dfa.countSelfLoopScanCount() > scanCount).isEqualTo(VectorSupport.isAvailable());
            assertThat(dfa.countSelfLoopUnproductiveCount()).isZero();
        }
    }

    @Test
    public void testSelfLoopExitScanFirstCountRetriesExclusively()
    {
        Prog program = compile(".*(x|y).*");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        String input = "a".repeat(1_000) + "x" + "a".repeat(1_000);

        // Each exclusive search advances the cache version when it ends.
        long cacheVersion = dfa.cacheVersion();
        assertThat(Dfa.countMatches(program, utf8Slice(input), Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);
        assertThat(dfa.cacheVersion()).isGreaterThan(cacheVersion);
        assertThat(countSelfLoopScanCount(program, Dfa.DfaInstance.Kind.FIRST_MATCH) > 0).isEqualTo(VectorSupport.isAvailable());
    }

    @Test
    public void testSelfLoopExitScanCutoffResetsForEachCount()
    {
        Prog program = compile(".*(x|y).*");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        // Without the vector scanners, the count never tries the route.
        int unproductiveLimit = VectorSupport.isAvailable() ? 8 : 0;
        int longLoopScanCount = VectorSupport.isAvailable() ? 1 : 0;

        // Every search over short lines dies after its match before reaching a loop.
        assertThat(Dfa.countMatches(program, utf8Slice("x\n".repeat(512)), Prog.MatchKind.FIRST_MATCH)).isEqualTo(512);
        assertThat(dfa.countSelfLoopUnproductiveCount()).isEqualTo(unproductiveLimit);
        assertThat(dfa.countSelfLoopScanCount()).isZero();

        // The cutoff reached by the previous count does not stop this one.
        assertThat(Dfa.countMatches(program, utf8Slice("a".repeat(32_768)), Prog.MatchKind.FIRST_MATCH)).isZero();
        assertThat(dfa.countSelfLoopScanCount()).isEqualTo(longLoopScanCount);

        // Within one count, the route stops after eight searches, even before a long loop.
        assertThat(Dfa.countMatches(program, utf8Slice("x\n".repeat(512) + "a".repeat(32_768)), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(512);
        assertThat(dfa.countSelfLoopUnproductiveCount()).isEqualTo(unproductiveLimit);
        assertThat(dfa.countSelfLoopScanCount()).isEqualTo(longLoopScanCount);
    }

    @Test
    public void testSelfLoopExitScanCutoffSpansCallsSharingSampling()
    {
        // Calls sharing one sampling count one text, as the inner literal count's fallbacks do,
        // so the route stops after eight searches across them, and its unproductive hand-offs
        // keep the paired rows the ordinary search uses.
        Prog program = compile("\\w+://[^\\s]+");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        Slice source = utf8Slice(("see the page at a site, then go to http://example.com/x and read on. ".repeat(20) + "\n").repeat(100));
        Dfa.CountByteScanSampling sampling = new Dfa.CountByteScanSampling();
        long count = 0;
        int position = 0;
        while (position < source.length()) {
            long counted = Dfa.countMatches(program, source, position, Prog.MatchKind.FIRST_MATCH, _ -> false, sampling);
            assertThat(counted).isNotNegative();
            assertThat(Dfa.countStopCount(counted)).isLessThanOrEqualTo(1);
            assertThat(Dfa.countStopPosition(counted)).isGreaterThan(position);
            count += Dfa.countStopCount(counted);
            position = Dfa.countStopPosition(counted);
        }
        assertThat(count).isEqualTo(2_000);
        if (VectorSupport.isAvailable()) {
            // The route stops after eight unproductive searches, each of which stops scanning
            // after four short scans.
            assertThat(dfa.countSelfLoopScanCount()).isLessThanOrEqualTo(8 * VectorByteSetScanner.SHORT_SCAN_LIMIT);
            assertThat(dfa.absolutePointerTransitionsAvailable()).isFalse();
            assertThat(dfa.pairedTransitionMemory()).isPositive();
        }

        // The sampling carries the count's own cutoff value, so a count of another text between
        // two calls, here one whose short searches reach the cutoff, does not stop the route for
        // the resumed count, which scans the long run of its next match.
        Prog holmes = compile("\\w+\\s+Holmes");
        Dfa.DfaInstance holmesDfa = holmes.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        Slice text = utf8Slice("word Holmes " + "a".repeat(70_000) + " Holmes");
        Dfa.CountByteScanSampling resumed = new Dfa.CountByteScanSampling();
        long first = Dfa.countMatches(holmes, text, 0, Prog.MatchKind.FIRST_MATCH, _ -> false, resumed);
        assertThat(Dfa.countStopCount(first)).isEqualTo(1);
        assertThat(Dfa.countMatches(holmes, utf8Slice("word Holmes ".repeat(20_000)), Prog.MatchKind.FIRST_MATCH)).isEqualTo(20_000);
        if (VectorSupport.isAvailable()) {
            assertThat(holmesDfa.countSelfLoopUnproductiveCount()).isEqualTo(8);
        }
        int scanCount = holmesDfa.countSelfLoopScanCount();
        long rest = Dfa.countMatches(holmes, text, Dfa.countStopPosition(first), Prog.MatchKind.FIRST_MATCH, _ -> true, resumed);
        assertThat(Dfa.countStopCount(rest)).isEqualTo(1);
        if (VectorSupport.isAvailable()) {
            assertThat(holmesDfa.countSelfLoopScanCount()).isGreaterThan(scanCount);
        }
    }

    @Test
    public void testSelfLoopExitScanRetriedSearchCountedOnce()
    {
        Prog program = compile(".*(x|y).*");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

        // The scan and the walk after the x record exit sets without requesting paired transitions.
        // Without the vector scanners, both counts take the ordinary routes.
        assertThat(Dfa.countMatches(program, utf8Slice("a".repeat(300) + "x\n" + "a".repeat(10)), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(1);
        if (VectorSupport.isAvailable()) {
            assertThat(dfa.countSelfLoopScanCount()).isEqualTo(1);
            assertThat(dfa.countSelfLoopUnproductiveCount()).isZero();
            assertThat(dfa.pairedTransitionMemory()).isZero();
        }

        // The walk now dies after the match without a mutation, and requesting paired transitions
        // retries the same search exclusively. The rest of the text is one more search that reaches
        // no eligible loop within the step limit: lead bytes that newlines end, so that the start
        // state stays for one byte at a time. Each search counts once.
        byte[] input = new byte[256];
        input[0] = 'x';
        for (int index = 1; index < input.length; index++) {
            input[index] = (byte) (index % 2 == 1 ? '\n' : 0xC3);
        }
        assertThat(Dfa.countMatches(program, Slices.wrappedBuffer(input), Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);
        if (VectorSupport.isAvailable()) {
            assertThat(dfa.countSelfLoopUnproductiveCount()).isEqualTo(2);
            assertThat(dfa.pairedTransitionMemory()).isPositive();
        }

        // A search too short for paired rows that reaches no eligible loop falls back to the
        // ordinary search, which retries it exclusively at a transition the warm-up did not
        // compute, the second byte of the `é`. The search counts once.
        Prog digits = compile(".*[02468].*");
        Dfa.DfaInstance digitsDfa = digits.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(Dfa.countMatches(digits, utf8Slice("a".repeat(100)), Prog.MatchKind.FIRST_MATCH)).isZero();
        long digitsCounted = Dfa.countMatches(digits, utf8Slice("a".repeat(80) + "\u00e9" + "2"), 0, Prog.MatchKind.FIRST_MATCH, _ -> false, null);
        assertThat(Dfa.countStopCount(digitsCounted)).isEqualTo(1);
        if (VectorSupport.isAvailable()) {
            assertThat(digitsDfa.countSelfLoopUnproductiveCount()).isEqualTo(1);
        }

        // An unproductive hand-off whose paired request retries the search counts it once. Each
        // one-search count scans only the short stay runs of three-letter words and hands off to
        // paired rows, where it matches within a few bytes. Sixteen such short paired searches
        // reject the rows and clear the request, so the next hand-off requests them again, which
        // retries that search exclusively.
        Prog words = compile("\\w+;");
        Dfa.DfaInstance wordsDfa = words.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        Slice source = utf8Slice("abc d e f; ".repeat(10_000));
        int position = 0;
        for (int call = 0; call < 18; call++) {
            long counted = Dfa.countMatches(words, source, position, Prog.MatchKind.FIRST_MATCH, _ -> false, null);
            assertThat(Dfa.countStopCount(counted)).isEqualTo(1);
            position = Dfa.countStopPosition(counted);
            if (VectorSupport.isAvailable()) {
                assertThat(wordsDfa.countSelfLoopUnproductiveCount()).as("call %s", call).isEqualTo(1);
            }
        }
        if (VectorSupport.isAvailable()) {
            assertThat(wordsDfa.pairedTransitionsDisabled()).isTrue();
        }

        // With a match pending, an unproductive hand-off also counts once and requests paired rows
        // rather than the pointer table. The matching loop of `.*=\w+(?:;\w+)*` stays on word
        // characters, and each semicolon ends its run after a few bytes.
        Prog pending = compile(".*=\\w+(?:;\\w+)*");
        Dfa.DfaInstance pendingDfa = pending.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(Dfa.countMatches(pending, utf8Slice("=" + "abcdefgh;".repeat(500)), Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);
        if (VectorSupport.isAvailable()) {
            assertThat(pendingDfa.countSelfLoopUnproductiveCount()).isEqualTo(1);
            assertThat(pendingDfa.absolutePointerTransitionsRequested()).isFalse();
            assertThat(pendingDfa.absolutePointerTransitionsAvailable()).isFalse();
            assertThat(pendingDfa.pairedTransitionMemory()).isPositive();
        }

        // A search with too few bytes left for paired rows hands off to the ordinary continuation
        // after four short scans, which retries it exclusively at the `é` the earlier count did not
        // compute. The search counts once.
        assertThat(Dfa.countMatches(pending, utf8Slice("=" + "abcdefgh;".repeat(8) + "\u00e9"), Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);
        if (VectorSupport.isAvailable()) {
            assertThat(pendingDfa.countSelfLoopUnproductiveCount()).isEqualTo(1);
        }

        // With enough bytes left, the search hands the pending match off to the paired rows the
        // earlier counts built, which retry it at the `ñ` none computed. It counts once too.
        int handOffCount = pendingDfa.countSelfLoopMatchHandOffCount();
        long pendingCounted = Dfa.countMatches(pending, utf8Slice("=" + "abcdefgh;".repeat(8) + "\u00f1" + "abcdefgh;".repeat(30)), 0, Prog.MatchKind.FIRST_MATCH, _ -> false, null);
        assertThat(Dfa.countStopCount(pendingCounted)).isEqualTo(1);
        if (VectorSupport.isAvailable()) {
            assertThat(pendingDfa.countSelfLoopMatchHandOffCount()).isEqualTo(handOffCount + 1);
            assertThat(pendingDfa.countSelfLoopUnproductiveCount()).isEqualTo(1);
        }
    }

    @Test
    public void testSelfLoopExitScanResetsUnproductiveCount()
    {
        Prog program = compile(".*(x|y).*");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        Slice input = utf8Slice("x\n".repeat(5) + "a".repeat(1_000));

        assertThat(Dfa.countMatches(program, input, Prog.MatchKind.FIRST_MATCH)).isEqualTo(5);
        assertThat(dfa.countSelfLoopScanCount()).isEqualTo(VectorSupport.isAvailable() ? 1 : 0);
        assertThat(dfa.countSelfLoopUnproductiveCount()).isZero();
    }

    @Test
    public void testSelfLoopExitScanShortScansHandOff()
    {
        // Four one-byte scans between two-byte characters stop the route before the x. With at
        // least 256 bytes left, the paired or absolute-pointer route finishes the search; otherwise
        // the compact loop. A long scan before the short scans, or of the filler after the match,
        // adds one scan.
        String shortScans = "\u00e9a".repeat(5) + "x\n";
        String[] inputs = {"a".repeat(300) + shortScans, shortScans + "a".repeat(300), "\u00e9a".repeat(86) + "xa"};
        int[] expectedScanCounts = {5, 5, 4};
        for (int index = 0; index < inputs.length; index++) {
            Slice source = utf8Slice(inputs[index]);
            Prog program = compile(".*(x|y).*");
            Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
            assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH))
                    .isEqualTo(trinoMatcherCount(".*(x|y).*", source))
                    .isEqualTo(1);
            assertThat(dfa.countSelfLoopScanCount())
                    .as(inputs[index])
                    .isEqualTo(VectorSupport.isAvailable() ? expectedScanCounts[index] : 0);
        }
    }

    @Test
    public void testSelfLoopExitScanShortScansHandOffUsesPointerTable()
    {
        // The alternative я, two bytes long and absent from the input, leaves no third narrow
        // position for a start literal filter, so the count keeps the start-byte scan.
        // The Russian letters in this input start with 0xD0 or 0xD1, the start state's exits, so
        // the start-byte scan's productivity check (64 KiB minimum, 64 sampled bytes) rejects the
        // scan, and four short scans stop the route with no match pending. The search continues
        // through the pointer loop, which the first count requests and its exclusive search
        // builds. Paired rows requested from the route would block the table.
        Prog program = compile("(?i)шерлок холмс|я");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(dfa.startLiteralFilter()).isNull();
        Slice input = utf8Slice(("Где же доктор Ватсон? Он ушёл на прогулку. ".repeat(10) + "Шерлок Холмс\n").repeat(200));
        for (int iteration = 0; iteration < 2; iteration++) {
            assertThat(Dfa.countMatches(program, input, Prog.MatchKind.FIRST_MATCH)).isEqualTo(200);
        }
        assertThat(dfa.countSelfLoopScanCount() > 0).isEqualTo(VectorSupport.isAvailable());
        // Without the vector scanners, the count takes the ordinary routes and never hands off.
        if (VectorSupport.isAvailable()) {
            assertThat(dfa.absolutePointerTransitionsAvailable()).isTrue();
            assertThat(dfa.countSelfLoopNoMatchPointerHandOffCount()).isPositive();
        }
    }

    @Test
    public void testSelfLoopExitScanHandOffKeepsPairedTransitions()
    {
        Prog program = compile(".*(x|y).*");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        warmPairedTransitions(program, utf8Slice("\u00e9a".repeat(200)));
        // Each line is one long scan and four short scans, then a paired search that matches
        // within a few bytes of the hand-off. The whole search is more than 300 bytes.
        Slice input = utf8Slice(("a".repeat(300) + "\u00e9a".repeat(5) + "x\n").repeat(8));

        for (int iteration = 0; iteration < 8; iteration++) {
            int scanCount = dfa.countSelfLoopScanCount();
            assertThat(Dfa.countMatches(program, input, Prog.MatchKind.FIRST_MATCH)).isEqualTo(8);
            assertThat(dfa.countSelfLoopScanCount() - scanCount).isEqualTo(VectorSupport.isAvailable() ? 40 : 0);
            assertThat(dfa.pairedTransitionsDisabled())
                    .as("iteration %s", iteration)
                    .isFalse();
            assertThat(dfa.pairedTransitionMemory()).isPositive();
        }
    }

    @Test
    public void testExclusiveCountRejectsPairedTransitions()
    {
        String expression = ".*(x|y).*";
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression));
        Dfa.DfaInstance dfa = pattern.re2().forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        Slice warmingInput = utf8Slice("x\n" + "\n".repeat(254));
        assertThat(pattern.count(warmingInput)).isEqualTo(1);
        assertThat(pattern.count(warmingInput)).isEqualTo(1);
        assertThat(dfa.pairedTransitionMemory()).isPositive();

        // The new characters retry the count exclusively. The short lines that follow end paired
        // searches within 16 bytes until the hint rejects paired transitions, which the next
        // search releases.
        Slice input = utf8Slice("\u4e2d\u0400x\n" + "x\n".repeat(40) + "a".repeat(300));
        assertThat(pattern.count(input))
                .isEqualTo(trinoMatcherCount(expression, input))
                .isEqualTo(41);
        assertThat(dfa.pairedTransitionsDisabled()).isTrue();
        assertThat(dfa.pairedTransitionMemory()).isZero();
    }

    @Test
    public void testCountAddsStatesAfterPairedTransitionsAreBuilt()
    {
        // An ordinary search builds paired transitions for the first count's states. The second
        // count adds states before the table is rebuilt, and its paired search then follows a
        // match transition into one of them, past the end of the paired table.
        Prog afterMatch = compile("[^,]+,.*");
        Slice noMatch = utf8Slice("\u00e9".repeat(132));
        warmPairedTransitions(afterMatch, noMatch);
        assertThat(Dfa.countMatches(afterMatch, noMatch, Prog.MatchKind.FIRST_MATCH)).isZero();
        assertThat(Dfa.countMatches(afterMatch, utf8Slice("\u00e9," + "\u00e9".repeat(130)), Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);

        // Here the paired search follows an ordinary transition into a state past the end of the table.
        // The count's vector self-loop scan computes the transitions that fill the table, and a
        // search over the same text then builds it, so without the vector scanners the second count
        // checks only its result.
        Prog afterOrdinaryTransition = compile(".*(abcdef|a)");
        Slice run = utf8Slice("a".repeat(300));
        assertThat(Dfa.countMatches(afterOrdinaryTransition, run, Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);
        assertThat(Dfa.search(afterOrdinaryTransition, run, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(run.length());
        if (VectorSupport.isAvailable()) {
            assertThat(afterOrdinaryTransition.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).pairedTransitionMemory()).isPositive();
        }
        assertThat(Dfa.countMatches(afterOrdinaryTransition, utf8Slice("a,abcdefq" + "\u00e9".repeat(150)), Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);
    }

    @Test
    public void testSelfLoopExitScanMatchHandOff()
    {
        // After the x, the matching loop exits at every two-byte character, so four short scans
        // stop the route with a match pending. The search continues through the pointer loop.
        String multibyte = "\u00e9a".repeat(2_000);
        assertSelfLoopMatchHandOff(utf8Slice("xaaaaaaaa" + multibyte), 1, 1);
        // The match covers the whole text; a match ending early would leave the trailing y for a
        // second match.
        Slice trailing = utf8Slice("xaaaaaaaa" + multibyte + "y");
        TrinoRegexpMatcher matcher = TrinoRegexp.compile(utf8Slice(".*(x|y).*")).matcher(trailing, 0);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.end()).isEqualTo(trailing.length());
        assertSelfLoopMatchHandOff(trailing, 1, 1);
        // The pointer loop carries the match through the ASCII tail and never builds paired
        // transitions.
        Slice tail = utf8Slice("xaaaaaaaa" + multibyte + "a".repeat(4_000));
        Prog program = assertSelfLoopMatchHandOff(tail, 1, 1);
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(Dfa.countMatches(program, tail, Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);
        // Without the vector scanners, the count takes the ordinary routes and never hands off.
        if (VectorSupport.isAvailable()) {
            assertThat(dfa.absolutePointerTransitionsAvailable()).isTrue();
            assertThat(dfa.pairedTransitionMemory()).isZero();
        }
        // The newline ends the first match, and the second search hands off the same way.
        String line = "aaaaaaaa" + "\u00e9a".repeat(1_000);
        assertSelfLoopMatchHandOff(utf8Slice("x" + line + "\ny" + line), 2, 2);

        byte[] bytes = ("xyxy" + "xaaaaaaaa" + multibyte + "yxyx").getBytes(UTF_8);
        assertSelfLoopMatchHandOff(Slices.wrappedBuffer(bytes, 4, bytes.length - 8), 1, 1);
    }

    @Test
    public void testSelfLoopExitScanPairedMatchHandOffScansAsciiTail()
    {
        // With paired rows built first, the matching hand-off continues through the paired loop
        // rather than the pointer loop.
        Prog program = compile(".*(x|y).*");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        warmPairedTransitions(program, utf8Slice("\u00e9a".repeat(200)));
        Slice tail = utf8Slice("xaaaaaaaa" + "\u00e9a".repeat(2_000) + "a".repeat(4_000));
        assertThat(Dfa.countMatches(program, tail, Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);
        int scanCount = dfa.matchingSelfLoopScanCount();
        assertThat(Dfa.countMatches(program, tail, Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);
        if (VectorSupport.isAvailable()) {
            // The paired after-match loop scans the ASCII tail as a matching self-loop.
            assertThat(dfa.matchingSelfLoopScanCount()).isGreaterThan(scanCount);
        }
    }

    @Test
    public void testSelfLoopExitScanLongestMatchHandOffUsesContinuation()
    {
        Slice source = utf8Slice("xaaaaaaaa" + "\u00e9a".repeat(2_000) + "y");
        Slice noMatch = utf8Slice("\u00e9a".repeat(200));
        Prog program = compile(".*(x|y).*");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH);
        // Short scans without a pending match hand off to the pointer route. A pending longest
        // match still continues through the compact loop.
        assertThat(Dfa.countMatches(program, noMatch, Prog.MatchKind.LONGEST_MATCH)).isZero();
        for (int iteration = 0; iteration < 2; iteration++) {
            assertThat(Dfa.countMatches(program, source, Prog.MatchKind.LONGEST_MATCH)).isEqualTo(1);
        }
        assertThat(dfa.countSelfLoopScanCount() > 0).isEqualTo(VectorSupport.isAvailable());
        // Without the vector scanners, the count takes the ordinary routes and never hands off.
        if (VectorSupport.isAvailable()) {
            assertThat(dfa.absolutePointerTransitionsAvailable()).isTrue();
        }
        assertThat(dfa.countSelfLoopMatchHandOffCount()).isZero();

        // A later count without a match hands off through the pointer table the first count
        // requested.
        assertThat(Dfa.countMatches(program, noMatch, Prog.MatchKind.LONGEST_MATCH)).isZero();
        assertThat(Nfa.search(program, noMatch, false, Prog.MatchKind.LONGEST_MATCH, null)).isFalse();
        assertThat(dfa.countSelfLoopNoMatchPointerHandOffCount() > 0).isEqualTo(VectorSupport.isAvailable());
    }

    @Test
    public void testSelfLoopExitScanLatin1()
    {
        String filler = "ab\u00e9\u00ff".repeat(1_024);
        for (String input : new String[] {filler, filler + "x" + filler, filler + "\n" + filler + "y\n" + filler}) {
            Re2 pattern = Re2.compile(utf8Slice(".*(x|y).*"), Re2.Options.latin1());
            Slice source = Slices.wrappedBuffer(input.getBytes(ISO_8859_1));
            long expected = input.lines().filter(line -> line.contains("x") || line.contains("y")).count();
            assertThat(pattern.count(source)).isEqualTo(expected);
            assertThat(countSelfLoopScanCount(pattern, Dfa.DfaInstance.Kind.FIRST_MATCH) > 0).isEqualTo(VectorSupport.isAvailable());

            Prog program = Re2.compile(utf8Slice(".*(x|y).*"), Re2.Options.latin1()).forwardProgramForDiagnostics();
            assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH)).isEqualTo(expected);
            assertThat(countSelfLoopScanCount(program, Dfa.DfaInstance.Kind.FIRST_MATCH) > 0).isEqualTo(VectorSupport.isAvailable());
        }
    }

    @Test
    public void testSelfLoopExitScanFullMatch()
    {
        // With a non-greedy prefix, the x or y thread leads the queue after the exit byte. In
        // Latin-1 dot-all mode every later byte matches, so the walk after the scan reaches the
        // full-match state and the match extends to the end of the text.
        String expression = "(?s).*?(x|y).*";
        String filler = "ab\n\u00e9\u00ff".repeat(1_024);
        for (String input : new String[] {filler + "x" + filler, filler + "y" + filler + "x" + filler, filler + "y", filler}) {
            Slice source = Slices.wrappedBuffer(input.getBytes(ISO_8859_1));
            long expected = input.contains("x") || input.contains("y") ? 1 : 0;
            Re2 pattern = Re2.compile(utf8Slice(expression), Re2.Options.latin1());
            assertThat(pattern.count(source)).isEqualTo(expected);
            assertThat(countSelfLoopScanCount(pattern, Dfa.DfaInstance.Kind.FIRST_MATCH) > 0).isEqualTo(VectorSupport.isAvailable());

            Prog program = Re2.compile(utf8Slice(expression), Re2.Options.latin1()).forwardProgramForDiagnostics();
            assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH)).isEqualTo(expected);
            assertThat(countSelfLoopScanCount(program, Dfa.DfaInstance.Kind.FIRST_MATCH) > 0).isEqualTo(VectorSupport.isAvailable());
        }
    }

    @Test
    public void testSelfLoopExitScanLongestMatch()
    {
        LineInput lines = lineInput(new Random(16), true, false);
        String filler = "a".repeat(2_000);
        Slice[] inputs = {lines.source(), utf8Slice(filler + "x" + filler), utf8Slice(filler + "\n" + filler + "y" + filler)};
        long[] expected = {lines.matchingLines(), 1, 1};
        for (int index = 0; index < inputs.length; index++) {
            Re2 pattern = Re2.compile(utf8Slice(".*(x|y).*"), Re2.Options.defaults().setLongestMatch(true));
            assertThat(pattern.count(inputs[index])).isEqualTo(expected[index]);
            assertThat(countSelfLoopScanCount(pattern, Dfa.DfaInstance.Kind.LONGEST_MATCH) > 0).isEqualTo(VectorSupport.isAvailable());

            Prog program = compile(".*(x|y).*");
            assertThat(Dfa.countMatches(program, inputs[index], Prog.MatchKind.LONGEST_MATCH)).isEqualTo(expected[index]);
            assertThat(countSelfLoopScanCount(program, Dfa.DfaInstance.Kind.LONGEST_MATCH) > 0).isEqualTo(VectorSupport.isAvailable());
        }
    }

    @Test
    public void testSelfLoopExitScanConcurrentFirstCount()
            throws Exception
    {
        // A fresh DFA computes its exit sets during the first count. The gate releases both tasks
        // to exercise concurrent first counts.
        LineInput lines = lineInput(new Random(17), true, false);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 100; iteration++) {
                Prog program = compile(".*(x|y).*");
                CountDownLatch startGate = new CountDownLatch(1);
                List<Future<Long>> counts = new ArrayList<>();
                for (int thread = 0; thread < 2; thread++) {
                    counts.add(executor.submit(() -> {
                        startGate.await();
                        return Dfa.countMatches(program, lines.source(), Prog.MatchKind.FIRST_MATCH);
                    }));
                }
                startGate.countDown();
                for (Future<Long> count : counts) {
                    assertThat(count.get(10, TimeUnit.SECONDS)).isEqualTo(lines.matchingLines());
                }
                assertThat(countSelfLoopScanCount(program, Dfa.DfaInstance.Kind.FIRST_MATCH) > 0).isEqualTo(VectorSupport.isAvailable());
            }
        }
    }

    @Test
    public void testStartByteScanSetSelectedOncePerCount()
    {
        TestingTrinoRegexpBenchmarkInputs.Input input = TestingTrinoRegexpBenchmarkInputs.create("captureSparse", 32_768);
        Prog program = compile(input.pattern().toStringUtf8());
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        long expected = trinoMatcherCount(input.pattern().toStringUtf8(), input.source());
        assertThat(expected).isEqualTo(3);

        // Each of the four spans around the three injected matches uses the range scanner. A
        // density sample taken at each match boundary would land on the next injected match and
        // fall back to the scalar scan for the second span.
        for (int iteration = 0; iteration < 3; iteration++) {
            int rangeScanCount = dfa.contiguousRangeScanCount();
            assertThat(Dfa.countMatches(program, input.source(), Prog.MatchKind.FIRST_MATCH)).isEqualTo(expected);
            assertThat(dfa.contiguousRangeScanCount() - rangeScanCount).isEqualTo(VectorSupport.isAvailable() ? 4 : 0);
        }
    }

    @Test
    public void testStartByteScanSetRejectedForDenseCandidates()
    {
        String expression = "([a-z]+)-([0-9]+)";
        Slice source = utf8Slice("abc-123.".repeat(4_096));
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression));
        Dfa.DfaInstance dfa = pattern.re2().forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

        for (int iteration = 0; iteration < 3; iteration++) {
            assertThat(pattern.count(source))
                    .isEqualTo(trinoMatcherCount(expression, source))
                    .isEqualTo(4_096);
        }
        assertThat(dfa.contiguousRangeScanCount()).isZero();
    }

    @Test
    public void testStartByteScanSetPromotedAfterDensePrefix()
    {
        String expression = "([a-z]+)-([0-9]+)";
        byte[] bytes = new byte[32_768 + 16];
        Arrays.fill(bytes, (byte) 'a');
        Arrays.fill(bytes, 7, 7 + 32_768, (byte) '.');
        System.arraycopy("abcdefghijkl-1".getBytes(US_ASCII), 0, bytes, 7, 14);
        Slice[] sources = {Slices.wrappedBuffer(bytes, 7, 32_768), utf8Slice("abcdefghijkl-1" + ".".repeat(32_754))};
        for (Slice source : sources) {
            Prog program = compile(expression);
            Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

            // The sample from the text start lands on the twelve candidates of the first match,
            // dense enough that no wider sample is taken, and selects the scalar scan. The sample
            // after that match sees only filler, so the rest of the count uses the range scanner.
            for (int iteration = 0; iteration < 3; iteration++) {
                int rangeScanCount = dfa.contiguousRangeScanCount();
                assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH))
                        .isEqualTo(trinoMatcherCount(expression, source))
                        .isEqualTo(1);
                assertThat(dfa.contiguousRangeScanCount() - rangeScanCount).isEqualTo(VectorSupport.isAvailable() ? 1 : 0);
            }
        }
    }

    @Test
    public void testEveryMatchCountSettlesBorderlineStartSample()
    {
        String expression = "([a-z]+)-([0-9]+)";
        Slice source = utf8Slice("abcd-1" + ".".repeat(32_762));
        Prog program = compile(expression);
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

        // The 64-byte sample from the text start holds four candidates, a borderline density.
        // The wider sample holds only those four candidates in 256 bytes, below its limit of 16,
        // so the search that finds the match and the one after it use the range scanner.
        for (int iteration = 0; iteration < 3; iteration++) {
            int rangeScanCount = dfa.contiguousRangeScanCount();
            assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH))
                    .isEqualTo(trinoMatcherCount(expression, source))
                    .isEqualTo(1);
            assertThat(dfa.contiguousRangeScanCount() - rangeScanCount).isEqualTo(VectorSupport.isAvailable() ? 2 : 0);
        }
    }

    @Test
    public void testStartByteScanSetPromotionThreshold()
    {
        String expression = "([a-z]+)-([0-9]+)";
        // The sample from the text start selects the scalar scan. Resampling after the first
        // match requires 4 KiB remaining, so only the longer filler promotes the range scanner.
        for (int fillerLength : new int[] {4_095, 4_096}) {
            Slice source = utf8Slice("abcdefghijkl-1" + ".".repeat(fillerLength));
            Prog program = compile(expression);
            Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

            for (int iteration = 0; iteration < 3; iteration++) {
                int rangeScanCount = dfa.contiguousRangeScanCount();
                assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH))
                        .isEqualTo(trinoMatcherCount(expression, source))
                        .isEqualTo(1);
                assertThat(dfa.contiguousRangeScanCount() - rangeScanCount)
                        .as("fillerLength=%s", fillerLength)
                        .isEqualTo(fillerLength < 4_096 || !VectorSupport.isAvailable() ? 0 : 1);
            }
        }
    }

    @Test
    public void testStartByteScanSetPromotionRequiresFullSample()
    {
        String expression = "([a-z]+)-([0-9]+)";
        String dense = "abc-123.".repeat(1_024);
        // Without a full sample the check reports any short span as productive, so a filler
        // tail shorter than the sample threshold must not promote the range scanner.
        for (int fillerLength : new int[] {4_000, 4_200}) {
            Slice source = utf8Slice(dense + ".".repeat(fillerLength));
            Prog program = compile(expression);
            Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

            assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH))
                    .isEqualTo(trinoMatcherCount(expression, source))
                    .isEqualTo(1_024);
            if (fillerLength < 4_096) {
                assertThat(dfa.contiguousRangeScanCount()).isZero();
            }
            else {
                assertThat(dfa.contiguousRangeScanCount() > 0).isEqualTo(VectorSupport.isAvailable());
            }
        }
    }

    @Test
    public void testStartByteScanSetNotSampledForFixedDistanceSearches()
    {
        String expression = "[a-z]{3}:[0-9]+";
        // The prefix, dense enough that no wider sample is taken, selects the scalar scan. Searches
        // with at least 4 KiB remaining use the fixed-distance scan for the colon, so they must
        // not sample start-byte density. A sample after a late prefix match would see only filler
        // and promote the range scanner for the final span, which is shorter than 4 KiB and
        // reaches the start-byte scan without sampling. Three letters keep the start class filter
        // off, which the selection would keep without the sample.
        Slice source = utf8Slice("zza:1.".repeat(64) + ".".repeat(8_000) + "bbb:2" + ".".repeat(2_000));
        Prog program = compile(expression);
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(dfa.startClassFilter()).isNull();

        for (int iteration = 0; iteration < 3; iteration++) {
            assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH))
                    .isEqualTo(trinoMatcherCount(expression, source))
                    .isEqualTo(65);
        }
        assertThat(dfa.contiguousRangeScanCount()).isZero();
    }

    @Test
    public void testStartByteScanSetPromotionSurvivesRetry()
    {
        Prog program = compile("([a-z]+)-([0-9]+)");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(Dfa.countMatches(program, utf8Slice("abcd-1" + ".".repeat(5_000)), Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);
        int rangeScanCount = dfa.contiguousRangeScanCount();

        // The first match uses only computed transitions, so the count is still shared when the
        // second search promotes the selection. That search needs a new transition after two
        // digits and retries exclusively, losing the promotion; the retry samples again, and the
        // final span keeps the promoted range scanner.
        Slice source = utf8Slice("abcdefghijkl-1" + ".".repeat(16_000) + "ab-12" + ".".repeat(16_000));
        assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH)).isEqualTo(2);
        assertThat(dfa.contiguousRangeScanCount() - rangeScanCount).isEqualTo(VectorSupport.isAvailable() ? 3 : 0);
    }

    @Test
    public void testCountsShareByteScanSamplingAcrossCalls()
    {
        String expression = "([a-z]+)-([0-9]+)";
        Slice source = utf8Slice("abc-123.".repeat(3 * 8_192));
        Prog program = compile(expression);
        Dfa.CountByteScanSampling sampling = new Dfa.CountByteScanSampling();

        // Each call stops at its first match end, as a fallback count does when the next literal
        // is far. Calls that start within 64 KiB of the first sample reuse its outcomes, and the
        // first call past that span samples again.
        long count = 0;
        int position = 0;
        int checksAfterFirstCall = 0;
        while (position < source.length()) {
            int checksBeforeCall = sampling.byteScanCheckCount();
            long counted = Dfa.countMatches(program, source, position, Prog.MatchKind.FIRST_MATCH, _ -> false, sampling);
            assertThat(counted).isNotNegative();
            assertThat(Dfa.countStopCount(counted)).isLessThanOrEqualTo(1);
            assertThat(Dfa.countStopPosition(counted)).isGreaterThan(position);
            if (position == 0) {
                checksAfterFirstCall = sampling.byteScanCheckCount();
                assertThat(checksAfterFirstCall).isPositive();
            }
            else if (position < Dfa.BYTE_SCAN_PRODUCTIVITY_RECHECK_BYTES) {
                assertThat(sampling.byteScanCheckCount()).as("position=%s", position).isEqualTo(checksAfterFirstCall);
            }
            else if (checksBeforeCall == checksAfterFirstCall) {
                assertThat(sampling.byteScanCheckCount()).as("position=%s", position).isGreaterThan(checksAfterFirstCall);
            }
            count += Dfa.countStopCount(counted);
            position = Dfa.countStopPosition(counted);
        }
        assertThat(count)
                .isEqualTo(trinoMatcherCount(expression, source))
                .isEqualTo(3 * 8_192);
    }

    @Test
    public void testSingleCountSamplesOncePerRecheck()
    {
        String expression = "([a-z]+)-([0-9]+)";
        Slice source = utf8Slice(("abc-1" + ".".repeat(195)).repeat(1_311));
        Prog program = compile(expression);
        // A plain count creates this sampling inside countMatches; the test passes it only to read
        // its check count.
        Dfa.CountByteScanSampling sampling = Dfa.CountByteScanSampling.forSingleCount();

        // The first search, at position 0, rejects the start-byte scan, which is not held, so the
        // next search samples again. A productive result then holds for 64 KiB past the position
        // sampled, and a vector scan choice holds for the whole count, so the count samples at
        // most once per 64 KiB rather than once per match.
        long counted = Dfa.countMatches(program, source, 0, Prog.MatchKind.FIRST_MATCH, _ -> true, sampling);
        assertThat(Dfa.countStopCount(counted))
                .isEqualTo(trinoMatcherCount(expression, source))
                .isEqualTo(1_311);
        assertThat(sampling.byteScanCheckCount()).isEqualTo(VectorSupport.isAvailable() ? 6 : 5);
    }

    @Test
    public void testSingleCountHoldsOnlySafeSamplingOutcomes()
    {
        // Twenty adjacent matches, then a match 40,000 bytes later and 64 KiB of filler. The 18
        // searches with at least three matches of the cluster ahead sample them, reject the
        // start-byte scan, and walk the DFA. The rejection is not held: the last searches in the
        // cluster sample filler, so the searches across the gap and after the far match scan.
        assertSingleCountFallbacks(
                "([a-z]+)-([0-9]+)",
                "abc-1".repeat(20) + ".".repeat(40_000) + "abc-1" + ".".repeat(Dfa.MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES),
                21,
                18);
        // A lone match ahead of the cluster: the first search samples sparse text and finds the
        // start-byte scan productive. The count holds that outcome across the cluster, so no
        // search walks the DFA; sampling at each search would reject the scan inside the cluster.
        assertSingleCountFallbacks("([a-z]+)-([0-9]+)", "abc-1" + ".".repeat(200) + "abc-1".repeat(20) + ".".repeat(70_000), 21, 0);
        // The cluster's dense `:` bytes reject the fixed-distance scan, and the count holds that
        // rejection. The searches whose start-scan samples also reject scanning walk the DFA: 15
        // fallbacks with the start class filter, 16 with the start-byte scan alone. Holding the
        // earlier productive fixed-distance result instead would leave none. The cluster repeats
        // `1:1:`, which the start class filter passes at every digit, as densely as the start
        // bytes; it would pass `a:1:` only at each letter.
        assertSingleCountFallbacks(
                "\\w:[0-9]+",
                "a:1" + ".".repeat(200) + "1:1:".repeat(20) + ".".repeat(1_000) + "a:1" + ".".repeat(1_000) + "a:1" + ".".repeat(70_000),
                23,
                VectorSupport.isAvailable() ? 15 : 16);
    }

    private static void assertSingleCountFallbacks(String expression, String text, long expectedCount, int expectedFallbackCount)
    {
        Slice source = utf8Slice(text);
        Prog program = compile(expression);
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

        assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH))
                .as(expression)
                .isEqualTo(trinoMatcherCount(expression, source))
                .isEqualTo(expectedCount);
        assertThat(dfa.byteScanFallbackCount()).as(expression).isEqualTo(expectedFallbackCount);
    }

    private static void assertSelfLoopScanCount(String expression, Slice source, long expected, int expectedScanCount)
    {
        assertSelfLoopScanCount(expression, source, expected, scanCount -> assertThat(scanCount)
                .as(expression)
                .isEqualTo(expectedScanCount));
    }

    private static void assertSelfLoopScanCount(String expression, Slice source, long expected)
    {
        assertSelfLoopScanCount(expression, source, expected, scanCount -> assertThat(scanCount)
                .as(expression)
                .isPositive());
    }

    private static void assertSelfLoopScanCount(String expression, Slice source, long expected, IntConsumer scanCountAssertion)
    {
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression));
        // Without the vector scanners, the count takes the ordinary routes and never scans.
        IntConsumer routeAssertion = VectorSupport.isAvailable()
                ? scanCountAssertion
                : scanCount -> assertThat(scanCount).as(expression).isZero();
        assertThat(pattern.count(source))
                .as(expression)
                .isEqualTo(trinoMatcherCount(expression, source))
                .isEqualTo(expected);
        routeAssertion.accept(countSelfLoopScanCount(pattern));

        // The inherited count falls back to the matcher loop for a negative result, so also check the DFA directly.
        Prog program = TrinoRegexp.compile(utf8Slice(expression)).re2().forwardProgramForDiagnostics();
        assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH))
                .as(expression)
                .isEqualTo(expected);
        routeAssertion.accept(countSelfLoopScanCount(program, Dfa.DfaInstance.Kind.FIRST_MATCH));
    }

    // The first count builds the pointer table or paired transitions when its exclusive search
    // ends, so the hand-off is checked on a second count.
    private static Prog assertSelfLoopMatchHandOff(Slice source, long expected, int expectedHandOffCount)
    {
        String expression = ".*(x|y).*";
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression));
        Dfa.DfaInstance patternDfa = pattern.re2().forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(pattern.count(source))
                .isEqualTo(trinoMatcherCount(expression, source))
                .isEqualTo(expected);
        int handOffCount = patternDfa.countSelfLoopMatchHandOffCount();
        assertThat(pattern.count(source)).isEqualTo(expected);
        // Without the vector scanners, the count takes the ordinary routes and never hands off.
        assertThat(patternDfa.countSelfLoopMatchHandOffCount() - handOffCount).isEqualTo(VectorSupport.isAvailable() ? expectedHandOffCount : 0);

        Prog program = compile(expression);
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH)).isEqualTo(expected);
        handOffCount = dfa.countSelfLoopMatchHandOffCount();
        assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH)).isEqualTo(expected);
        assertThat(dfa.countSelfLoopMatchHandOffCount() - handOffCount).isEqualTo(VectorSupport.isAvailable() ? expectedHandOffCount : 0);
        assertThat(dfa.countSelfLoopScanCount() > 0).isEqualTo(VectorSupport.isAvailable());
        return program;
    }

    private static int countSelfLoopScanCount(TrinoRegexp pattern)
    {
        return countSelfLoopScanCount(pattern.re2(), Dfa.DfaInstance.Kind.FIRST_MATCH);
    }

    private static int countSelfLoopScanCount(CompiledPattern pattern, Dfa.DfaInstance.Kind kind)
    {
        return countSelfLoopScanCount(pattern.forwardProgramForDiagnostics(), kind);
    }

    private static int countSelfLoopScanCount(Prog program, Dfa.DfaInstance.Kind kind)
    {
        return program.getCachedDfa(kind).countSelfLoopScanCount();
    }

    private static long trinoMatcherCount(String expression, Slice source)
    {
        TrinoRegexpMatcher matcher = TrinoRegexp.compile(utf8Slice(expression)).matcher(source, 0);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    // Lines of about 100 bytes contain x, y, both, or neither. Multibyte characters and invalid
    // UTF-8 bytes are placed both before and after the x and y bytes.
    private static LineInput lineInput(Random random, boolean multibyte, boolean invalid)
    {
        byte[][] multibyteCharacters = {
                "\u00e9".getBytes(UTF_8),
                "\u4e2d".getBytes(UTF_8),
                "\ud83d\udcb0".getBytes(UTF_8),
        };
        byte[] invalidBytes = {(byte) 0x80, (byte) 0xC0, (byte) 0xFF};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matchingLines = 0;
        for (int line = 0; line < 400; line++) {
            int length = 80 + random.nextInt(40);
            int kind = line % 4;
            int xPosition = kind == 0 || kind == 2 ? random.nextInt(length) : -1;
            int yPosition = kind == 1 || kind == 2 ? random.nextInt(length) : -1;
            if (kind != 3) {
                matchingLines++;
            }
            for (int position = 0; position < length; position++) {
                if (position == xPosition) {
                    output.write('x');
                }
                else if (position == yPosition) {
                    output.write('y');
                }
                else if (multibyte && random.nextInt(8) == 0) {
                    output.writeBytes(multibyteCharacters[random.nextInt(multibyteCharacters.length)]);
                }
                else if (invalid && random.nextInt(16) == 0) {
                    output.write(invalidBytes[random.nextInt(invalidBytes.length)]);
                }
                else {
                    output.write('a');
                }
            }
            output.write('\n');
        }
        return new LineInput(Slices.wrappedBuffer(output.toByteArray()), matchingLines);
    }

    private record LineInput(Slice source, int matchingLines) {}

    // The UTF-8 offset of each index of text that starts a character, and of its end.
    private static int[] utf8Offsets(String text)
    {
        int[] offsets = new int[text.length() + 1];
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int next = index + Character.charCount(codePoint);
            offsets[next] = offsets[index] + Character.toString(codePoint).getBytes(UTF_8).length;
            index = next;
        }
        return offsets;
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

    // Picks `count` of the blocks at random, each followed by the separator.
    private static String blockText(Random random, String[] blocks, int count, char separator)
    {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < count; index++) {
            text.append(blocks[random.nextInt(blocks.length)]).append(separator);
        }
        return text.toString();
    }

    // Resets the DFA twice with finds on another text, counting only the resets after the one
    // that drops a pointer table given back, as the count rule does.
    private static void resetTwice(Dfa.DfaInstance dfa, RegexpMatcher growth)
    {
        int resetCount = dfa.resetCount();
        while (dfa.resetCount() - Math.max(resetCount, dfa.releasedAbsolutePointerResetCount()) < 2) {
            assertThat(growth.find()).isTrue();
        }
    }

    private static int findCount(RegexpMatcher matcher)
    {
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    // Paired rows block the pointer table, so once an ordinary search has built them, count
    // hand-offs keep the paired routes.
    private static void warmPairedTransitions(Prog program, Slice noMatch)
    {
        for (int iteration = 0; iteration < 2; iteration++) {
            assertThat(Dfa.search(program, noMatch, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        }
        assertThat(program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).pairedTransitionMemory()).isPositive();
    }

    private static Prog compile(String pattern)
    {
        ParseResult parsed = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL);
        return Compiler.compile(parsed.regexp(), false, 0);
    }
}
