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
import java.util.function.IntConsumer;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDfaCountMatches
{
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
        Re2 pattern = Re2.compile(
                utf8Slice("[a-q][^u-z]{80}x"),
                Re2.Options.latin1().setMaxMemory(3L << 20));
        byte[] bytes = stateExplosionInput(16 * 1024);
        var input = Slices.wrappedBuffer(bytes);

        // Pinned native RE2 finds five non-overlapping matches in this deterministic input.
        Re2Matcher matcher = pattern.matcher(input, 0);
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

        Re2Matcher fallbackMatcher = pattern.matcher(input, 0);
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
        Re2 pattern = Re2.compile(
                utf8Slice("[a-q][^u-z]{80}x"),
                Re2.Options.latin1().setMaxMemory(4L << 20));
        var input = Slices.wrappedBuffer(stateExplosionInput(16 * 1024));

        Re2Matcher fillingMatcher = pattern.matcher(input);
        int knownMatchStart = -1;
        int matchCount = 0;
        while (fillingMatcher.find()) {
            if (knownMatchStart < 0) {
                knownMatchStart = fillingMatcher.start();
            }
            matchCount++;
        }
        assertThat(matchCount).isEqualTo(5);

        Re2Matcher reused = pattern.matcher(input).reset(input, knownMatchStart, input.length());
        assertThat(reused.lookingAt()).isTrue();

        Re2 fresh = Re2.compile(
                utf8Slice("[a-q][^u-z]{80}x"),
                Re2.Options.latin1().setMaxMemory(4L << 20));
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

            Re2Matcher matcher = pattern.matcher(input, 0);
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
        Re2 pattern = Re2.compile(
                utf8Slice("[a-q][^u-z]{13}x"),
                Re2.Options.latin1().setMaxMemory(3L << 19));
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
                            .isEqualTo(1);
                }

                assertSelfLoopScanCount(expression, utf8Slice("a".repeat(length - 1) + "x"), 1);
            }
        }
    }

    @Test
    public void testSelfLoopExitScanMinimumLength()
    {
        // The route needs 256 bytes remaining, the same gate that requests paired transitions.
        for (String expression : new String[] {".*(x|y).*", ".*[xy].*"}) {
            assertSelfLoopScanCount(expression, utf8Slice("a".repeat(254) + "x"), 1, 0);
            assertSelfLoopScanCount(expression, utf8Slice("a".repeat(255) + "x"), 1, 1);
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
                    .countSelfLoopScanCount())
                    .isPositive();
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
    public void testSelfLoopExitScanRejectsManyExitBytes()
    {
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(".*[0-9].*"));
        assertThat(pattern.count(utf8Slice("a".repeat(32_768)))).isZero();
        Dfa.DfaInstance dfa = pattern.pattern().forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(dfa.countSelfLoopScanCount()).isZero();
        assertThat(dfa.pairedTransitionMemory()).isPositive();

        TestingTrinoRegexpBenchmarkInputs.Input input = TestingTrinoRegexpBenchmarkInputs.create("captureSparse", 32_768);
        TrinoRegexp capture = TrinoRegexp.compile(input.pattern());
        assertThat(capture.count(input.source())).isEqualTo(3);
        assertThat(countSelfLoopScanCount(capture)).isZero();
    }

    @Test
    public void testSelfLoopExitScanStopsForIneligiblePattern()
    {
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(".*[0-9].*"));
        Slice input = utf8Slice(("a".repeat(50) + "1\n").repeat(20) + "a".repeat(300));
        Dfa.DfaInstance dfa = pattern.pattern().forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

        assertThat(pattern.count(input)).isEqualTo(20);
        assertThat(dfa.countSelfLoopNotApplicableCount()).isEqualTo(8);
        for (int iteration = 0; iteration < 4; iteration++) {
            assertThat(pattern.count(input)).isEqualTo(20);
            assertThat(dfa.countSelfLoopNotApplicableCount()).isEqualTo(8);
        }
        assertThat(dfa.countSelfLoopScanCount()).isZero();

        dfa.resetCacheExternal();
        assertThat(dfa.countSelfLoopNotApplicableCount()).isZero();
        assertThat(pattern.count(input)).isEqualTo(20);
        assertThat(dfa.countSelfLoopNotApplicableCount()).isEqualTo(8);
    }

    @Test
    public void testSelfLoopExitScanContinuesForEligiblePattern()
    {
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(".*(x|y).*"));
        LineInput input = lineInput(new Random(15), true, false);
        Dfa.DfaInstance dfa = pattern.pattern().forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

        for (int iteration = 0; iteration < 50; iteration++) {
            int scanCount = dfa.countSelfLoopScanCount();
            assertThat(pattern.count(input.source())).isEqualTo(input.matchingLines());
            assertThat(dfa.countSelfLoopScanCount()).isGreaterThan(scanCount);
            assertThat(dfa.countSelfLoopNotApplicableCount()).isZero();
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
        assertThat(countSelfLoopScanCount(program, Dfa.DfaInstance.Kind.FIRST_MATCH)).isPositive();
    }

    @Test
    public void testSelfLoopExitScanCutoffResetsForEachCount()
    {
        Prog program = compile(".*(x|y).*");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

        // Every search over short lines dies after its match before reaching a loop.
        assertThat(Dfa.countMatches(program, utf8Slice("x\n".repeat(512)), Prog.MatchKind.FIRST_MATCH)).isEqualTo(512);
        assertThat(dfa.countSelfLoopNotApplicableCount()).isEqualTo(8);
        assertThat(dfa.countSelfLoopScanCount()).isZero();

        // The cutoff reached by the previous count does not stop this one.
        assertThat(Dfa.countMatches(program, utf8Slice("a".repeat(32_768)), Prog.MatchKind.FIRST_MATCH)).isZero();
        assertThat(dfa.countSelfLoopScanCount()).isEqualTo(1);

        // Within one count, the route stops after eight searches, even before a long loop.
        assertThat(Dfa.countMatches(program, utf8Slice("x\n".repeat(512) + "a".repeat(32_768)), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(512);
        assertThat(dfa.countSelfLoopNotApplicableCount()).isEqualTo(8);
        assertThat(dfa.countSelfLoopScanCount()).isEqualTo(1);
    }

    @Test
    public void testSelfLoopExitScanRetriedSearchCountedOnce()
    {
        Prog program = compile(".*(x|y).*");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

        // The scan and the walk after the x record exit sets without requesting paired transitions.
        assertThat(Dfa.countMatches(program, utf8Slice("a".repeat(300) + "x\n" + "a".repeat(10)), Prog.MatchKind.FIRST_MATCH))
                .isEqualTo(1);
        assertThat(dfa.countSelfLoopScanCount()).isEqualTo(1);
        assertThat(dfa.countSelfLoopNotApplicableCount()).isZero();
        assertThat(dfa.pairedTransitionMemory()).isZero();

        // The walk now dies after the match without a mutation, and requesting paired transitions
        // retries the same search exclusively.
        assertThat(Dfa.countMatches(program, utf8Slice("x\n" + "\n".repeat(254)), Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);
        assertThat(dfa.countSelfLoopNotApplicableCount()).isEqualTo(1);
        assertThat(dfa.pairedTransitionMemory()).isPositive();
    }

    @Test
    public void testSelfLoopExitScanResetsNotApplicableCount()
    {
        Prog program = compile(".*(x|y).*");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        Slice input = utf8Slice("x\n".repeat(5) + "a".repeat(1_000));

        assertThat(Dfa.countMatches(program, input, Prog.MatchKind.FIRST_MATCH)).isEqualTo(5);
        assertThat(dfa.countSelfLoopScanCount()).isEqualTo(1);
        assertThat(dfa.countSelfLoopNotApplicableCount()).isZero();
    }

    @Test
    public void testSelfLoopExitScanShortScansHandOff()
    {
        // Four one-byte scans between two-byte characters stop the route before the x. With at
        // least 256 bytes left, the paired route finishes the search; otherwise the compact loop.
        // A long scan before the short scans, or of the filler after the match, adds one scan.
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
                    .isEqualTo(expectedScanCounts[index]);
        }
    }

    @Test
    public void testSelfLoopExitScanHandOffKeepsPairedTransitions()
    {
        Prog program = compile(".*(x|y).*");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        // Each line is one long scan and four short scans, then a paired search that matches
        // within a few bytes of the hand-off. The whole search is more than 300 bytes.
        Slice input = utf8Slice(("a".repeat(300) + "\u00e9a".repeat(5) + "x\n").repeat(8));

        for (int iteration = 0; iteration < 8; iteration++) {
            int scanCount = dfa.countSelfLoopScanCount();
            assertThat(Dfa.countMatches(program, input, Prog.MatchKind.FIRST_MATCH)).isEqualTo(8);
            assertThat(dfa.countSelfLoopScanCount() - scanCount).isEqualTo(40);
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
        Dfa.DfaInstance dfa = pattern.pattern().forwardProgramForDiagnostics()
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
    public void testSelfLoopExitScanMatchHandOff()
    {
        // After the x, the matching loop exits at every two-byte character, so four short scans
        // stop the route with a match pending. The search continues through the absolute-pointer
        // loop, or without native access through the paired after-match loop.
        String multibyte = "\u00e9a".repeat(2_000);
        assertSelfLoopMatchHandOff(utf8Slice("xaaaaaaaa" + multibyte), 1, 1);
        // The match covers the whole text; a match ending early would leave the trailing y for a
        // second match.
        Slice trailing = utf8Slice("xaaaaaaaa" + multibyte + "y");
        TrinoRegexpMatcher matcher = TrinoRegexp.compile(utf8Slice(".*(x|y).*")).matcher(trailing, 0);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.end()).isEqualTo(trailing.length());
        assertSelfLoopMatchHandOff(trailing, 1, 1);
        // The paired after-match loop scans the ASCII tail as a matching self-loop, and the
        // absolute-pointer loop never builds paired transitions.
        Slice tail = utf8Slice("xaaaaaaaa" + multibyte + "a".repeat(4_000));
        Prog program = assertSelfLoopMatchHandOff(tail, 1, 1);
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        int matchingScanCount = dfa.matchingSelfLoopScanCount();
        assertThat(Dfa.countMatches(program, tail, Prog.MatchKind.FIRST_MATCH)).isEqualTo(1);
        if (dfa.absolutePointerTransitionsAvailable()) {
            assertThat(dfa.pairedTransitionMemory()).isZero();
        }
        else {
            assertThat(dfa.matchingSelfLoopScanCount()).isGreaterThan(matchingScanCount);
        }
        // The newline ends the first match, and the second search hands off the same way.
        String line = "aaaaaaaa" + "\u00e9a".repeat(1_000);
        assertSelfLoopMatchHandOff(utf8Slice("x" + line + "\ny" + line), 2, 2);

        byte[] bytes = ("xyxy" + "xaaaaaaaa" + multibyte + "yxyx").getBytes(UTF_8);
        assertSelfLoopMatchHandOff(Slices.wrappedBuffer(bytes, 4, bytes.length - 8), 1, 1);
    }

    @Test
    public void testSelfLoopExitScanLongestMatchHandOffUsesContinuation()
    {
        Slice source = utf8Slice("xaaaaaaaa" + "\u00e9a".repeat(2_000) + "y");
        Prog program = compile(".*(x|y).*");
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH);
        // Short scans without a pending match hand off to the paired route, which builds paired
        // transitions. A pending longest match still continues through the compact loop.
        assertThat(Dfa.countMatches(program, utf8Slice("\u00e9a".repeat(200)), Prog.MatchKind.LONGEST_MATCH)).isZero();
        for (int iteration = 0; iteration < 2; iteration++) {
            assertThat(Dfa.countMatches(program, source, Prog.MatchKind.LONGEST_MATCH)).isEqualTo(1);
        }
        assertThat(dfa.countSelfLoopScanCount()).isPositive();
        assertThat(dfa.pairedTransitionMemory()).isPositive();
        assertThat(dfa.countSelfLoopMatchHandOffCount()).isZero();
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
            assertThat(countSelfLoopScanCount(pattern, Dfa.DfaInstance.Kind.FIRST_MATCH)).isPositive();

            Prog program = Re2.compile(utf8Slice(".*(x|y).*"), Re2.Options.latin1()).forwardProgramForDiagnostics();
            assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH)).isEqualTo(expected);
            assertThat(countSelfLoopScanCount(program, Dfa.DfaInstance.Kind.FIRST_MATCH)).isPositive();
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
            assertThat(countSelfLoopScanCount(pattern, Dfa.DfaInstance.Kind.FIRST_MATCH)).isPositive();

            Prog program = Re2.compile(utf8Slice(expression), Re2.Options.latin1()).forwardProgramForDiagnostics();
            assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH)).isEqualTo(expected);
            assertThat(countSelfLoopScanCount(program, Dfa.DfaInstance.Kind.FIRST_MATCH)).isPositive();
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
            assertThat(countSelfLoopScanCount(pattern, Dfa.DfaInstance.Kind.LONGEST_MATCH)).isPositive();

            Prog program = compile(".*(x|y).*");
            assertThat(Dfa.countMatches(program, inputs[index], Prog.MatchKind.LONGEST_MATCH)).isEqualTo(expected[index]);
            assertThat(countSelfLoopScanCount(program, Dfa.DfaInstance.Kind.LONGEST_MATCH)).isPositive();
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
                assertThat(countSelfLoopScanCount(program, Dfa.DfaInstance.Kind.FIRST_MATCH)).isPositive();
            }
        }
    }

    @Test
    public void testStartByteScanSetSelectedOncePerCount()
    {
        TestingTrinoRegexpBenchmarkInputs.Input input = TestingTrinoRegexpBenchmarkInputs.create("captureSparse", 32_768);
        TrinoRegexp pattern = TrinoRegexp.compile(input.pattern());
        Dfa.DfaInstance dfa = pattern.pattern().forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        long expected = trinoMatcherCount(input.pattern().toStringUtf8(), input.source());
        assertThat(expected).isEqualTo(3);

        // Each of the four spans around the three injected matches uses the range scanner. A
        // density sample taken at each match boundary would land on the next injected match and
        // fall back to the scalar scan for the second span.
        for (int iteration = 0; iteration < 3; iteration++) {
            int rangeScanCount = dfa.contiguousRangeScanCount();
            assertThat(pattern.count(input.source())).isEqualTo(expected);
            assertThat(dfa.contiguousRangeScanCount() - rangeScanCount).isEqualTo(4);
        }
    }

    @Test
    public void testStartByteScanSetRejectedForDenseCandidates()
    {
        String expression = "([a-z]+)-([0-9]+)";
        Slice source = utf8Slice("abc-123.".repeat(4_096));
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression));
        Dfa.DfaInstance dfa = pattern.pattern().forwardProgramForDiagnostics()
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
        System.arraycopy("abcd-1".getBytes(US_ASCII), 0, bytes, 7, 6);
        Slice[] sources = {Slices.wrappedBuffer(bytes, 7, 32_768), utf8Slice("abcd-1" + ".".repeat(32_762))};
        for (Slice source : sources) {
            TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression));
            Dfa.DfaInstance dfa = pattern.pattern().forwardProgramForDiagnostics()
                    .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

            // The sample from the text start lands on the candidates of the first match and
            // selects the scalar scan. The sample after that match sees only filler, so the rest
            // of the count uses the range scanner.
            for (int iteration = 0; iteration < 3; iteration++) {
                int rangeScanCount = dfa.contiguousRangeScanCount();
                assertThat(pattern.count(source))
                        .isEqualTo(trinoMatcherCount(expression, source))
                        .isEqualTo(1);
                assertThat(dfa.contiguousRangeScanCount() - rangeScanCount).isEqualTo(1);
            }
        }
    }

    @Test
    public void testStartByteScanSetPromotionThreshold()
    {
        String expression = "([a-z]+)-([0-9]+)";
        // The sample from the text start selects the scalar scan. Resampling after the first
        // match requires 4 KiB remaining, so only the longer filler promotes the range scanner.
        for (int fillerLength : new int[] {4_095, 4_096}) {
            Slice source = utf8Slice("abcd-1" + ".".repeat(fillerLength));
            TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression));
            Dfa.DfaInstance dfa = pattern.pattern().forwardProgramForDiagnostics()
                    .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

            for (int iteration = 0; iteration < 3; iteration++) {
                int rangeScanCount = dfa.contiguousRangeScanCount();
                assertThat(pattern.count(source))
                        .isEqualTo(trinoMatcherCount(expression, source))
                        .isEqualTo(1);
                assertThat(dfa.contiguousRangeScanCount() - rangeScanCount)
                        .as("fillerLength=%s", fillerLength)
                        .isEqualTo(fillerLength < 4_096 ? 0 : 1);
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
            TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression));
            Dfa.DfaInstance dfa = pattern.pattern().forwardProgramForDiagnostics()
                    .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

            assertThat(pattern.count(source))
                    .isEqualTo(trinoMatcherCount(expression, source))
                    .isEqualTo(1_024);
            if (fillerLength < 4_096) {
                assertThat(dfa.contiguousRangeScanCount()).isZero();
            }
            else {
                assertThat(dfa.contiguousRangeScanCount()).isPositive();
            }
        }
    }

    @Test
    public void testStartByteScanSetNotSampledForFixedDistanceSearches()
    {
        String expression = "[a-z]:[0-9]+";
        // The dense prefix selects the scalar scan. Searches with at least 4 KiB remaining use the
        // fixed-distance scan for the colon, so they must not sample start-byte density. A sample
        // after a late prefix match would see only filler and promote the range scanner for the
        // final span, which is shorter than 4 KiB and reaches the start-byte scan without sampling.
        Slice source = utf8Slice("a:1.".repeat(64) + ".".repeat(8_000) + "b:2" + ".".repeat(2_000));
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression));
        Dfa.DfaInstance dfa = pattern.pattern().forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);

        for (int iteration = 0; iteration < 3; iteration++) {
            assertThat(pattern.count(source))
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
        Slice source = utf8Slice("abcd-1" + ".".repeat(16_000) + "ab-12" + ".".repeat(16_000));
        assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH)).isEqualTo(2);
        assertThat(dfa.contiguousRangeScanCount() - rangeScanCount).isEqualTo(3);
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
        assertThat(pattern.count(source))
                .as(expression)
                .isEqualTo(trinoMatcherCount(expression, source))
                .isEqualTo(expected);
        scanCountAssertion.accept(countSelfLoopScanCount(pattern));

        // Re2.count falls back to the matcher loop for a negative result, so also check the DFA directly.
        Prog program = TrinoRegexp.compile(utf8Slice(expression)).pattern().forwardProgramForDiagnostics();
        assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH))
                .as(expression)
                .isEqualTo(expected);
        scanCountAssertion.accept(countSelfLoopScanCount(program, Dfa.DfaInstance.Kind.FIRST_MATCH));
    }

    // The first count builds the pointer table or paired transitions when its exclusive search
    // ends, so the hand-off is checked on a second count.
    private static Prog assertSelfLoopMatchHandOff(Slice source, long expected, int expectedHandOffCount)
    {
        String expression = ".*(x|y).*";
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression));
        Dfa.DfaInstance patternDfa = pattern.pattern().forwardProgramForDiagnostics()
                .getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(pattern.count(source))
                .isEqualTo(trinoMatcherCount(expression, source))
                .isEqualTo(expected);
        int handOffCount = patternDfa.countSelfLoopMatchHandOffCount();
        assertThat(pattern.count(source)).isEqualTo(expected);
        assertThat(patternDfa.countSelfLoopMatchHandOffCount() - handOffCount).isEqualTo(expectedHandOffCount);

        Prog program = compile(expression);
        Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH)).isEqualTo(expected);
        handOffCount = dfa.countSelfLoopMatchHandOffCount();
        assertThat(Dfa.countMatches(program, source, Prog.MatchKind.FIRST_MATCH)).isEqualTo(expected);
        assertThat(dfa.countSelfLoopMatchHandOffCount() - handOffCount).isEqualTo(expectedHandOffCount);
        assertThat(dfa.countSelfLoopScanCount()).isPositive();
        return program;
    }

    private static int countSelfLoopScanCount(TrinoRegexp pattern)
    {
        return countSelfLoopScanCount(pattern.pattern(), Dfa.DfaInstance.Kind.FIRST_MATCH);
    }

    private static int countSelfLoopScanCount(Re2 pattern, Dfa.DfaInstance.Kind kind)
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

    private static Prog compile(String pattern)
    {
        ParseResult parsed = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL);
        return Compiler.compile(parsed.regexp(), false, 0);
    }
}
