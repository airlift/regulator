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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Dfa.DfaInstance.Kind.FIRST_MATCH;
import static io.airlift.regulator.Dfa.DfaInstance.Kind.LONGEST_MATCH;
import static io.airlift.regulator.Re2BenchmarkRunner.compileProg;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;

public final class DfaAbsolutePointerProbe
{
    private DfaAbsolutePointerProbe() {}

    public static void main(String[] arguments)
            throws Exception
    {
        boolean expectedNativeAccess = Boolean.parseBoolean(arguments[0]);
        check(Dfa.nativeAccessEnabled() == expectedNativeAccess, "unexpected native-access capability");

        String suffix = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        Prog program = compileProg("[ -~]*" + suffix + "$");
        program.setDfaMemory(64L << 20);
        Slice input = utf8Slice("x".repeat(4_096) + suffix);
        check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "initial search failed");
        Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);

        Slice boundaryInput = utf8Slice("aa-1 bb-22 ccc-333");
        Prog reverseProgram = compileReverse("[a-z]+-[0-9]+", false);
        for (int iteration = 0; iteration < 20; iteration++) {
            check(Dfa.search(reverseProgram, boundaryInput, 0, boundaryInput.length(), 0, 4, true, Prog.MatchKind.LONGEST_MATCH, true) == 0, "first variable boundary changed");
            check(Dfa.search(reverseProgram, boundaryInput, 0, boundaryInput.length(), 0, 10, true, Prog.MatchKind.LONGEST_MATCH, true) == 5, "second variable boundary changed");
            check(Dfa.search(reverseProgram, boundaryInput, 0, boundaryInput.length(), 0, 18, true, Prog.MatchKind.LONGEST_MATCH, true) == 11, "third variable boundary changed");
        }
        Dfa.DfaInstance reverseDfa = reverseProgram.getCachedDfa(LONGEST_MATCH);

        Slice utf8BoundaryInput = utf8Slice("xxéé-12yy");
        Dfa.DfaInstance utf8ReverseDfa = exerciseReverseBoundary(
                "[é]+-[0-9]+",
                false,
                utf8BoundaryInput,
                2,
                utf8BoundaryInput.length() - 2,
                7);

        byte[] latin1BoundaryBytes = {(byte) '.', (byte) '.', (byte) 0xE9, (byte) 0xE9, (byte) '-', (byte) '1', (byte) '2', (byte) '.', (byte) '.'};
        Dfa.DfaInstance latin1ReverseDfa = exerciseReverseBoundary(
                "[\\xE9]+-[0-9]+",
                true,
                wrappedBuffer(latin1BoundaryBytes),
                2,
                7,
                5);

        Prog countProgram = compileProg(".*[^A-Z]|[A-Z]");
        countProgram.setDfaMemory(64L << 20);
        byte[] countBytes = utf8Slice(".." + "A".repeat(200) + "..").getBytes();
        Slice countInput = wrappedBuffer(countBytes, 2, 200);
        check(Dfa.countMatches(countProgram, countInput, Prog.MatchKind.FIRST_MATCH) == 200, "short fused count changed the result");
        Dfa.DfaInstance countDfa = countProgram.getCachedDfa(FIRST_MATCH);

        Prog latin1CountProgram = compileLatin1(".*[^A-Z]|[A-Z]");
        latin1CountProgram.setDfaMemory(64L << 20);
        check(Dfa.countMatches(latin1CountProgram, countInput, Prog.MatchKind.FIRST_MATCH) == 200, "LATIN1 short fused count changed the result");
        Dfa.DfaInstance latin1CountDfa = latin1CountProgram.getCachedDfa(FIRST_MATCH);

        exerciseCountSelfLoopMatchHandOff(expectedNativeAccess);
        exerciseCountSelfLoopMatchHandOffWithPairedRows();
        exerciseShortSearchesAfterPairedRejection(expectedNativeAccess);
        exerciseOptionalStorageFailureAtomicity(expectedNativeAccess);
        if (expectedNativeAccess) {
            exerciseNativeCacheGrowthAtomicity();
        }

        if (!expectedNativeAccess) {
            check(dfa.absolutePointerTransitionMemory() == 0, "disabled native access allocated a sidecar");
            check(reverseDfa.absolutePointerTransitionMemory() == 0, "disabled native access allocated a reverse sidecar");
            check(utf8ReverseDfa.absolutePointerTransitionMemory() == 0, "disabled native access allocated a UTF-8 reverse sidecar");
            check(latin1ReverseDfa.absolutePointerTransitionMemory() == 0, "disabled native access allocated a LATIN1 reverse sidecar");
            check(countDfa.absolutePointerTransitionMemory() == 0, "disabled native access allocated a fused-count sidecar");
            check(latin1CountDfa.absolutePointerTransitionMemory() == 0, "disabled native access allocated a LATIN1 fused-count sidecar");
            System.out.println("OK disabled");
            return;
        }

        check(dfa.absolutePointerTransitionMemory() > 0, "enabled native access did not allocate a sidecar");
        check(dfa.absolutePointerTransitionMemory() < 1L << 20, "small DFA reserved a maximum-capacity sidecar");
        check(dfa.absolutePointerTransitionCount() > 0, "search did not populate pointer transitions");
        check(dfa.availableStateMemory() + dfa.absolutePointerTransitionMemory() < dfa.stateBudget(), "sidecar was not charged to the DFA budget");
        check(reverseDfa.absolutePointerTransitionMemory() > 0, "variable boundary did not allocate a reverse sidecar");
        check(reverseDfa.absolutePointerTransitionCount() > 0, "variable boundary did not populate reverse pointer transitions");
        check(reverseDfa.absolutePointerBackwardSearchCount() > 0, "variable boundary did not use reverse pointer transitions");
        check(utf8ReverseDfa.absolutePointerBackwardSearchCount() > 0, "UTF-8 boundary did not use reverse pointer transitions");
        check(latin1ReverseDfa.absolutePointerBackwardSearchCount() > 0, "LATIN1 boundary did not use reverse pointer transitions");

        long originalAddress = dfa.absolutePointerTransitionBaseAddress();
        long originalMemory = dfa.absolutePointerTransitionMemory();
        exerciseResetOverlap(program, input, dfa, originalAddress, originalMemory);
        check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "search after reset failed");
        check(dfa.absolutePointerTransitionCount() > 0, "search after reset did not rebuild pointer transitions");

        Prog shortProgram = compileProg("(\\s*)((?:# [Nn][Oo][Qq][Aa])(?::\\s?(([A-Z]+[0-9]+(?:[,\\s]+)?)+))?)");
        shortProgram.setDfaMemory(64L << 20);
        Slice shortMatch = utf8Slice("# noqa");
        check(Dfa.search(shortProgram, shortMatch, false, Prog.MatchKind.FIRST_MATCH, true) == shortMatch.length(), "short matching search failed");
        Slice shortInput = utf8Slice("int value = 123;");
        for (int iteration = 0; iteration < 20; iteration++) {
            check(Dfa.search(shortProgram, shortInput, false, Prog.MatchKind.FIRST_MATCH, true) == Dfa.SEARCH_NO_MATCH, "short search failed");
        }
        Dfa.DfaInstance shortDfa = shortProgram.getCachedDfa(FIRST_MATCH);
        check(shortDfa.absolutePointerTransitionMemory() > 0, "short search did not allocate a pointer sidecar");
        check(shortDfa.absolutePointerTransitionCount() > 0, "short search did not populate pointer transitions");

        check(countDfa.absolutePointerTransitionMemory() > 0, "short fused count did not allocate a pointer sidecar");
        check(countDfa.absolutePointerTransitionCount() > 0, "short fused count did not populate pointer transitions");

        check(latin1CountDfa.absolutePointerTransitionMemory() > 0, "LATIN1 short fused count did not allocate a pointer sidecar");
        check(latin1CountDfa.absolutePointerTransitionCount() > 0, "LATIN1 short fused count did not populate pointer transitions");

        Slice deadInput = utf8Slice("x".repeat(4_096) + suffix.substring(0, suffix.length() - 1) + "_");
        check(Dfa.search(program, deadInput, false, Prog.MatchKind.FIRST_MATCH, true) == Dfa.SEARCH_NO_MATCH, "dead transition changed the result");
        check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "match transition changed the boundary");
        check(countTaggedTransitions(dfa) > 0, "sidecar did not encode computed terminal transitions");

        Prog regionProgram = compileProg("(?m:^a$)");
        regionProgram.setDfaMemory(64L << 20);
        Slice regionMatch = utf8Slice("a\n");
        Slice regionNoMatch = utf8Slice("ab");
        for (int iteration = 0; iteration < 20; iteration++) {
            check(Dfa.search(regionProgram, regionMatch, 0, 1, false, Prog.MatchKind.FULL_MATCH, true) == 1, "region end context changed a match");
            check(Dfa.search(regionProgram, regionNoMatch, 0, 1, false, Prog.MatchKind.FULL_MATCH, true) == Dfa.SEARCH_NO_MATCH, "region end context created a match");
        }
        Dfa.DfaInstance regionDfa = regionProgram.getCachedDfa(LONGEST_MATCH);
        check(regionDfa.absolutePointerTransitionMemory() > 0, "region search did not allocate a pointer sidecar");
        check(countTaggedTransitions(regionDfa) > 0, "region sidecar did not encode computed terminal transitions");

        byte[] paddedInput = new byte[input.length() + 32];
        input.getBytes(0, paddedInput, 16, input.length());
        Slice inputView = wrappedBuffer(paddedInput, 16, input.length());
        check(Dfa.search(program, inputView, false, Prog.MatchKind.FIRST_MATCH, true) == inputView.length(), "nonzero Slice offset changed the boundary");

        Prog latin1Program = compileLatin1("[\\x00-\\xff]*" + suffix + "$");
        latin1Program.setDfaMemory(64L << 20);
        byte[] latin1Bytes = new byte[512 + suffix.length()];
        Arrays.fill(latin1Bytes, 0, 512, (byte) 0xE9);
        System.arraycopy(suffix.getBytes(StandardCharsets.US_ASCII), 0, latin1Bytes, 512, suffix.length());
        Slice latin1Input = wrappedBuffer(latin1Bytes);
        for (int iteration = 0; iteration < 20; iteration++) {
            check(Dfa.search(latin1Program, latin1Input, false, Prog.MatchKind.FIRST_MATCH, true) == latin1Input.length(), "LATIN1 match transition changed the boundary");
        }
        Dfa.DfaInstance latin1Dfa = latin1Program.getCachedDfa(LONGEST_MATCH);
        check(latin1Dfa.absolutePointerTransitionMemory() > 0, "LATIN1 search did not allocate a pointer sidecar");
        check(countTaggedTransitions(latin1Dfa) > 0, "LATIN1 sidecar did not encode computed terminal transitions");

        String growingSuffix = suffix.repeat(4);
        String growingPattern = "[ -~]*" + growingSuffix + "$";
        Slice growthWarmupInput = utf8Slice("_".repeat(4_096));
        Prog growingProgram = growingProgram(growingPattern, growthWarmupInput);
        Dfa.DfaInstance growingDfa = growingProgram.getCachedDfa(LONGEST_MATCH);
        long initialGrowingPointerMemory = growingDfa.absolutePointerTransitionMemory();
        check(initialGrowingPointerMemory < 1L << 20, "growing DFA reserved a maximum-capacity sidecar before graph construction");
        Slice growingInput = utf8Slice("_".repeat(4_096) + growingSuffix);
        growingDfa.failNextForTesting(Dfa.DfaInstance.TestingFailurePoint.NATIVE_TRANSITION_GROWTH);
        expectTestingFailure(() -> Dfa.search(growingProgram, growingInput, false, Prog.MatchKind.FIRST_MATCH, true));
        check(growingDfa.testingSnapshotBeforeFailure().equals(growingDfa.failureAtomicitySnapshot()), "failed native growth changed the DFA cache");
        exerciseGrowthOverlap(growingProgram, growthWarmupInput, growingInput, growingDfa);
        long growingPointerMemory = growingDfa.absolutePointerTransitionMemory();
        check((long) growingDfa.stateCount * growingDfa.nextSize * Long.BYTES > 48L << 10, "growing DFA did not cross the former initial tier");
        check(growingPointerMemory > initialGrowingPointerMemory, "growing DFA did not enlarge the sidecar");
        check(growingDfa.absolutePointerTransitionsAvailable(), "growing DFA switched to object traversal");
        check(growingDfa.availableStateMemory() + growingDfa.retainedStateMemory() + growingPointerMemory == growingDfa.stateBudget(), "growing DFA accounting did not reconcile to its memory budget");

        long growingAddress = growingDfa.absolutePointerTransitionBaseAddress();
        growingDfa.resetCacheExternal();
        check(growingDfa.absolutePointerTransitionBaseAddress() != growingAddress, "reset reused the grown sidecar");
        check(growingDfa.absolutePointerTransitionMemory() == growingPointerMemory, "reset changed the grown sidecar allocation");

        exerciseWithheldPointerTable("[ -~]*" + suffix + "$", input);
        exerciseFailedGrowthAllocation(growingPattern, growthWarmupInput, growingInput);

        System.out.println("OK enabled");
    }

    // A reset that cannot have a new segment, because superseded segments still await collection or
    // the allocation fails, publishes its tables without the pointer table and keeps it selected; a
    // later exclusive search allocates it again.
    private static void exerciseWithheldPointerTable(String pattern, Slice input)
            throws InterruptedException
    {
        Prog program = compileProg(pattern);
        program.setDfaMemory(64L << 20);
        check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "withheld-table warm-up failed");
        Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);
        check(dfa.absolutePointerTransitionsAvailable(), "withheld-table warm-up did not allocate a sidecar");
        // Growth during the warm-up may have superseded segments; the cap checks below count from zero.
        awaitSupersededMemory(dfa, 0);
        long segmentMemory = dfa.absolutePointerTransitionMemory();

        // Searches still reading superseded tables keep their segments. Two superseded segments fit
        // within twice the segment; a third would not.
        List<Dfa.CacheTables> readerTables = new ArrayList<>();
        for (int reset = 0; reset < 2; reset++) {
            readerTables.add(dfa.cacheTablesForTesting());
            dfa.resetCacheExternal();
            check(dfa.absolutePointerTransitionsAvailable(), "reset below the superseded-segment cap withheld the sidecar");
        }
        readerTables.add(dfa.cacheTablesForTesting());
        dfa.resetCacheExternal();
        check(!dfa.absolutePointerTransitionsAvailable(), "reset at the superseded-segment cap allocated another sidecar");
        check(dfa.supersededAbsolutePointerMemory() == 3 * segmentMemory, "superseded sidecars were not counted");
        assertWithheld(dfa, "superseded-segment cap");
        check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "search at the superseded-segment cap changed the result");
        check(!dfa.absolutePointerTransitionsAvailable(), "exclusive search allocated a sidecar at the superseded-segment cap");

        check(!readerTables.contains(dfa.cacheTablesForTesting()), "reset republished tables a reader holds");
        // The readers return.
        readerTables.clear();
        awaitSupersededMemory(dfa, 0);
        assertReselected(program, input, dfa, "superseded-segment cap");

        dfa.failNextForTesting(Dfa.DfaInstance.TestingFailurePoint.NATIVE_TRANSITION_RESET);
        dfa.resetCacheExternal();
        assertWithheld(dfa, "failed sidecar allocation");
        check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "search after a failed sidecar allocation changed the result");
        check(!dfa.absolutePointerTransitionsAvailable(), "exclusive search allocated a sidecar again before the next reset");
        dfa.resetCacheExternal();
        awaitSupersededMemory(dfa, 0);
        assertReselected(program, input, dfa, "failed sidecar allocation");
    }

    // A growth whose segment cannot be allocated withholds the table: the DFA goes on adding states
    // on object rows, refunds the current segment's charge and counts that segment as superseded,
    // and allocates no pointer table again until the next reset.
    private static void exerciseFailedGrowthAllocation(String pattern, Slice warmupInput, Slice input)
            throws InterruptedException
    {
        Prog program = growingProgram(pattern, warmupInput);
        Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);
        check(dfa.absolutePointerTransitionsAvailable(), "failed-growth warm-up did not allocate a sidecar");
        long segmentMemory = dfa.absolutePointerTransitionMemory();
        check(dfa.supersededAbsolutePointerMemory() == 0, "failed-growth warm-up superseded a sidecar");
        // A search still reading the warm-up tables keeps the segment the growth supersedes.
        List<Dfa.CacheTables> readerTables = new ArrayList<>(List.of(dfa.cacheTablesForTesting()));

        dfa.failNextForTesting(Dfa.DfaInstance.TestingFailurePoint.NATIVE_TRANSITION_GROWTH_ALLOCATION);
        check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "search through a failed growth allocation changed the result");
        check((long) dfa.stateCount * dfa.nextSize * Long.BYTES > segmentMemory, "DFA with a failed growth allocation stopped adding states");
        assertWithheld(dfa, "failed growth allocation");
        check(dfa.supersededAbsolutePointerMemory() == segmentMemory, "failed growth allocation did not count the superseded sidecar");
        check(!readerTables.contains(dfa.cacheTablesForTesting()), "failed growth allocation republished tables a reader holds");
        // The reader returns.
        readerTables.clear();

        check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "search after a failed growth allocation changed the result");
        dfa.beginSearch(true);
        dfa.endSearch(true);
        check(!dfa.absolutePointerTransitionsAvailable(), "exclusive search allocated a sidecar again before the next reset");
        dfa.resetCacheExternal();
        awaitSupersededMemory(dfa, 0);
        assertReselected(program, input, dfa, "failed growth allocation");
    }

    // A program whose pointer table is sized for the warm-up's states, so a longer input grows it.
    private static Prog growingProgram(String pattern, Slice warmupInput)
    {
        Prog program = compileProg(pattern);
        program.setDfaMemory(64L << 20);
        Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);
        dfa.beginSearch(true);
        try {
            dfa.analyzeStart(
                    dfa.cacheTablesForTesting(),
                    warmupInput,
                    warmupInput.byteArrayOffset(),
                    warmupInput.byteArrayOffset() + warmupInput.length(),
                    false,
                    true);
            dfa.requestAbsolutePointerTransitions(dfa.cacheTablesForTesting());
        }
        finally {
            dfa.endSearch(true);
        }
        return program;
    }

    private static void assertWithheld(Dfa.DfaInstance dfa, String cause)
    {
        check(!dfa.absolutePointerTransitionsAvailable(), cause + " published a sidecar");
        check(!dfa.absolutePointerTransitionsUnavailable(), cause + " made the sidecar unavailable");
        check(dfa.pairedTransitionsDisabled(), cause + " enabled paired transitions");
        check(dfa.availableStateMemory() + dfa.retainedStateMemory() == dfa.stateBudget(), cause + " kept the sidecar charge");
    }

    private static void assertReselected(Prog program, Slice input, Dfa.DfaInstance dfa, String cause)
    {
        // An exclusive search allocates a withheld table when it ends.
        dfa.beginSearch(true);
        dfa.endSearch(true);
        check(dfa.absolutePointerTransitionsAvailable(), "exclusive search did not allocate the sidecar withheld by the " + cause);
        check(dfa.availableStateMemory() + dfa.retainedStateMemory() + dfa.absolutePointerTransitionMemory() == dfa.stateBudget(), "sidecar allocated after the " + cause + " was not charged");
        check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "search after the " + cause + " changed the result");
    }

    private static void awaitSupersededMemory(Dfa.DfaInstance dfa, long expected)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (dfa.supersededAbsolutePointerMemory() != expected && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(Duration.ofMillis(10));
        }
        check(dfa.supersededAbsolutePointerMemory() == expected, "superseded sidecars were not collected: " + dfa.supersededAbsolutePointerMemory());
    }

    private static void exerciseResetOverlap(
            Prog program,
            Slice input,
            Dfa.DfaInstance dfa,
            long originalAddress,
            long originalMemory)
            throws Exception
    {
        FutureTask<Long> readerTask = new FutureTask<>(() -> Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true));
        Thread reader = Thread.ofPlatform().daemon().unstarted(readerTask);
        FutureTask<Void> resetTask = new FutureTask<>(() -> {
            dfa.resetCacheExternal();
            return null;
        });
        Dfa.armNativeReaderProbeForTesting(reader);
        try {
            reader.start();
            check(Dfa.awaitNativeReaderProbeForTesting(10, TimeUnit.SECONDS), "native reader did not reach the reset overlap probe");
            // Readers do not register: the reset runs while the reader is paused inside the native
            // table and publishes a fresh one, leaving the reader's table unchanged.
            Thread.ofPlatform().daemon().start(resetTask);
            resetTask.get(10, TimeUnit.SECONDS);
            check(dfa.absolutePointerTransitionBaseAddress() != originalAddress, "reset reused the sidecar a paused reader reads");
            check(dfa.absolutePointerTransitionCount() == 0, "reset left pointer transitions in the current table");
        }
        finally {
            Dfa.releaseNativeReaderProbeForTesting();
        }
        check(readerTask.get(10, TimeUnit.SECONDS) == input.length(), "paused native reader changed the match result");
        check(dfa.absolutePointerTransitionMemory() == originalMemory, "reset changed the replacement sidecar charge");
        // The released reader finished in the superseded tables, which are no longer charged.
        check(dfa.availableStateMemory() + dfa.retainedStateMemory() + originalMemory == dfa.stateBudget(), "reset returned the replacement sidecar charge");
    }

    private static void exerciseGrowthOverlap(
            Prog program,
            Slice readerInput,
            Slice growingInput,
            Dfa.DfaInstance dfa)
            throws Exception
    {
        long originalAddress = dfa.absolutePointerTransitionBaseAddress();
        long originalMemory = dfa.absolutePointerTransitionMemory();
        WeakReference<Object> retiredOwner = dfa.absolutePointerOwnerReferenceForTesting();

        FutureTask<Long> readerTask = new FutureTask<>(() -> Dfa.search(program, readerInput, false, Prog.MatchKind.FIRST_MATCH, true));
        Thread reader = Thread.ofPlatform().daemon().unstarted(readerTask);
        FutureTask<Long> growthTask = new FutureTask<>(() -> Dfa.search(program, growingInput, false, Prog.MatchKind.FIRST_MATCH, true));
        Dfa.armNativeReaderProbeForTesting(reader);
        try {
            reader.start();
            check(Dfa.awaitNativeReaderProbeForTesting(10, TimeUnit.SECONDS), "native reader did not reach the growth overlap probe");
            // The growth completes while the reader is paused inside the retired table. The
            // reader's reference keeps the retired table's automatic arena alive.
            Thread.ofPlatform().daemon().start(growthTask);
            check(growthTask.get(10, TimeUnit.SECONDS) == growingInput.length(), "growing DFA search failed while a reader was paused");
            check(dfa.absolutePointerTransitionBaseAddress() != originalAddress, "growth did not rebase the sidecar");
            for (int i = 0; i < 5; i++) {
                System.gc();
                Thread.sleep(Duration.ofMillis(10));
            }
            check(retiredOwner.get() != null, "retired native transition owner was collected while a paused reader used it");
        }
        finally {
            Dfa.releaseNativeReaderProbeForTesting();
        }
        check(readerTask.get(10, TimeUnit.SECONDS) == Dfa.SEARCH_NO_MATCH, "paused growth reader changed the search result");
        check(dfa.absolutePointerTransitionMemory() > originalMemory, "growth did not enlarge the sidecar");
        check(dfa.availableStateMemory() + dfa.retainedStateMemory() + dfa.absolutePointerTransitionMemory() == dfa.stateBudget(), "growth accounting did not reconcile");

        awaitCollection(retiredOwner);
    }

    private static void awaitCollection(WeakReference<?> reference)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (reference.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(Duration.ofMillis(10));
        }
        check(reference.get() == null, "retired native transition owner remained reachable");
    }

    private static void expectTestingFailure(Runnable runnable)
    {
        try {
            runnable.run();
        }
        catch (Error expected) {
            return;
        }
        throw new AssertionError("expected injected DFA failure");
    }

    // After the x, two of every three transitions of the two-byte character cycle carry the
    // match bit, so the count self-loop scan stops with a match pending. The pending match must
    // continue through the pointer table; requesting paired rows first would prevent the table
    // from being allocated.
    private static void exerciseCountSelfLoopMatchHandOff(boolean expectedNativeAccess)
    {
        byte[] prefix = "xaaaaaaaa".getBytes(StandardCharsets.UTF_8);
        byte[] cycle = "\u00e9a".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[32_768];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        for (int index = prefix.length; index < bytes.length; index++) {
            bytes[index] = cycle[(index - prefix.length) % cycle.length];
        }
        Slice input = wrappedBuffer(bytes);
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(".*(x|y).*"));
        Dfa.DfaInstance dfa = pattern.re2().forwardProgramForDiagnostics().getCachedDfa(FIRST_MATCH);
        int handOffCount = dfa.countSelfLoopMatchHandOffCount();
        for (int iteration = 0; iteration < 4; iteration++) {
            check(pattern.count(input) == 1, "count self-loop match hand-off changed the count");
        }
        check(dfa.countSelfLoopMatchHandOffCount() > handOffCount, "count self-loop scan did not hand off the pending match");
        if (expectedNativeAccess) {
            check(dfa.absolutePointerTransitionsAvailable(), "count self-loop match hand-off did not allocate a pointer sidecar");
            check(dfa.pairedTransitionMemory() == 0, "count self-loop match hand-off built paired transitions");
        }
        else {
            check(dfa.pairedTransitionMemory() > 0, "count self-loop match hand-off did not build paired transitions");
        }
    }

    // Paired rows block pointer-table allocation, so once an ordinary search has built them, a
    // pending match continues through the paired after-match loop. Requesting the
    // pointer table instead would force an exclusive retry on every count and still fall back to
    // the compact loop.
    private static void exerciseCountSelfLoopMatchHandOffWithPairedRows()
    {
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(".*(x|y).*"));
        Prog program = pattern.re2().forwardProgramForDiagnostics();
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        Slice noMatch = utf8Slice("\u00e9a".repeat(200));
        for (int iteration = 0; iteration < 2; iteration++) {
            check(Dfa.search(program, noMatch, false, Prog.MatchKind.FIRST_MATCH, true) == Dfa.SEARCH_NO_MATCH, "count self-loop paired warm-up found a match");
        }
        check(dfa.pairedTransitionMemory() > 0, "count self-loop paired warm-up did not build paired transitions");

        Slice input = utf8Slice("xaaaaaaaa" + "\u00e9a".repeat(2_000));
        // The first count computes the states after the x, and its exclusive search builds their
        // paired rows when it ends.
        check(pattern.count(input) == 1, "count self-loop paired match hand-off changed the count");
        long cacheVersion = 0;
        for (int iteration = 0; iteration < 5; iteration++) {
            // The last three counts must run without an exclusive retry.
            if (iteration == 2) {
                cacheVersion = dfa.cacheVersion();
            }
            int handOffCount = dfa.countSelfLoopMatchHandOffCount();
            check(pattern.count(input) == 1, "count self-loop paired match hand-off changed the count");
            check(dfa.countSelfLoopMatchHandOffCount() > handOffCount, "count self-loop scan did not hand off the pending match to paired transitions");
        }
        check(dfa.cacheVersion() == cacheVersion, "count self-loop paired match hand-off retried exclusively");
    }

    // The sixteenth short paired search rejects paired transitions but keeps its result, so the
    // rows remain until a later request releases them. Short searches never request paired rows,
    // and the rows block the pointer table, so the pointer request must release them; otherwise
    // every later short search retries exclusively and still uses the compact loop.
    private static void exerciseShortSearchesAfterPairedRejection(boolean expectedNativeAccess)
    {
        Prog program = compileProg("^x*(ab|a)");
        Slice warmingInput = utf8Slice("x".repeat(300) + "abz");
        Slice pairedInput = utf8Slice("ab" + "z".repeat(300));
        Slice shortInput = utf8Slice("ab" + "z".repeat(10));
        check(Dfa.search(program, warmingInput, true, Prog.MatchKind.FIRST_MATCH, true) == 302, "paired rejection warm-up changed the boundary");
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        check(dfa.pairedTransitionMemory() > 0, "paired rejection warm-up did not build paired transitions");
        for (int search = 0; search < 16; search++) {
            check(Dfa.search(program, pairedInput, true, Prog.MatchKind.FIRST_MATCH, true) == 2, "short paired search changed the boundary");
        }
        check(dfa.pairedTransitionMemory() > 0, "rejecting paired search released paired transitions");

        long cacheVersion = 0;
        for (int search = 0; search < 40; search++) {
            // The last twenty searches must run without an exclusive retry.
            if (search == 20) {
                cacheVersion = dfa.cacheVersion();
            }
            check(Dfa.search(program, shortInput, true, Prog.MatchKind.FIRST_MATCH, true) == 2, "short search after paired rejection changed the boundary");
        }
        check(dfa.cacheVersion() == cacheVersion, "short searches after paired rejection retried exclusively");
        if (expectedNativeAccess) {
            check(dfa.absolutePointerTransitionsAvailable(), "short searches after paired rejection did not allocate a pointer sidecar");
            check(dfa.pairedTransitionMemory() == 0, "pointer request retained rejected paired transitions");
        }
    }

    private static void exerciseOptionalStorageFailureAtomicity(boolean expectedNativeAccess)
    {
        String suffix = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        Prog program = compileProg(expectedNativeAccess
                ? "(?:\\bx*|y*)" + suffix + '$'
                : "(?:\\bx*|y*)(a[01]{200}|a)");
        program.setDfaMemory(expectedNativeAccess ? 64L << 20 : 64 * 1024);
        Slice warmupInput = utf8Slice("x".repeat(300) + (expectedNativeAccess ? suffix : "a0"));
        boolean anchored = !expectedNativeAccess;
        Dfa.search(program, warmupInput, anchored, Prog.MatchKind.FIRST_MATCH, true);
        Dfa.DfaInstance dfa = program.getCachedDfa(expectedNativeAccess ? LONGEST_MATCH : FIRST_MATCH);
        if (expectedNativeAccess) {
            check(dfa.absolutePointerTransitionMemory() > 0, "native atomicity fixture did not allocate a pointer sidecar");
        }
        else {
            check(dfa.pairedTransitionMemory() > 0, "safe atomicity fixture did not allocate paired transitions");
        }

        byte[] contextBytes = new byte[warmupInput.length() + 1];
        contextBytes[0] = 'z';
        warmupInput.getBytes(0, contextBytes, 1, warmupInput.length());
        Slice contextInput = wrappedBuffer(contextBytes);
        dfa.failNextForTesting(Dfa.DfaInstance.TestingFailurePoint.STATE_CACHE_INSERTION);
        expectTestingFailure(() -> Dfa.search(
                program,
                contextInput,
                0,
                contextInput.length(),
                1,
                contextInput.length(),
                anchored,
                Prog.MatchKind.FIRST_MATCH,
                true));
        check(dfa.testingSnapshotBeforeFailure().equals(dfa.failureAtomicitySnapshot()), "failed cache insertion changed optional storage");
        long result = Dfa.search(program, contextInput, 0, contextInput.length(), 1, contextInput.length(), anchored, Prog.MatchKind.FIRST_MATCH, true);
        check(Dfa.search(program, contextInput, 0, contextInput.length(), 1, contextInput.length(), anchored, Prog.MatchKind.FIRST_MATCH, true) == result, "cache insertion failure changed later reuse");

        dfa.resetCacheExternal();
        dfa.failStateAllocationsForTesting(2);
        check(Dfa.search(program, warmupInput, anchored, Prog.MatchKind.FIRST_MATCH, true) == Dfa.SEARCH_FAILED, "second start-state failure did not use the general fallback");
        long fallbackResult = Dfa.search(program, warmupInput, anchored, Prog.MatchKind.FIRST_MATCH, true);
        int stateCount = dfa.stateCount;
        check(Dfa.search(program, warmupInput, anchored, Prog.MatchKind.FIRST_MATCH, true) == fallbackResult, "start-state fallback changed the later result");
        check(dfa.stateCount == stateCount, "later search did not reuse the recovered start state");
    }

    private static void exerciseNativeCacheGrowthAtomicity()
    {
        Prog program = compileProg("[a-q][^u-z]{80}x");
        program.setDfaMemory(4L << 20);
        Slice seedInput = wrappedBuffer(stateExplosionInput(64));
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        dfa.beginSearch(true);
        try {
            dfa.analyzeStart(dfa.cacheTablesForTesting(), seedInput, 0, seedInput.length(), false, true);
            dfa.requestAbsolutePointerTransitions(dfa.cacheTablesForTesting());
        }
        finally {
            dfa.endSearch(true);
        }
        check(dfa.absolutePointerTransitionMemory() > 0, "native cache-growth fixture did not allocate a pointer sidecar");

        Slice warmupInput = wrappedBuffer(stateExplosionInput(1_538));
        Dfa.search(program, warmupInput, false, Prog.MatchKind.FIRST_MATCH, true);
        Dfa.FailureAtomicitySnapshot warmed = dfa.failureAtomicitySnapshot();
        check(warmed.cacheEntries() == (warmed.cacheTableCapacity() * 3) / 4, "native cache-growth fixture did not reach the resize threshold");

        Slice growingInput = wrappedBuffer(stateExplosionInput(16 * 1024));
        dfa.failNextForTesting(Dfa.DfaInstance.TestingFailurePoint.STATE_CACHE_INSERTION);
        expectTestingFailure(() -> Dfa.search(program, growingInput, false, Prog.MatchKind.FIRST_MATCH, true));
        check(dfa.testingSnapshotBeforeFailure().equals(dfa.failureAtomicitySnapshot()), "failed native cache growth changed storage or accounting");
        Dfa.search(program, growingInput, false, Prog.MatchKind.FIRST_MATCH, true);
    }

    private static byte[] stateExplosionInput(int length)
    {
        Random random = new Random(42);
        byte[] alphabet = "abcdefghijklmnopqrst \n,.;0123456789".getBytes(StandardCharsets.US_ASCII);
        byte[] input = new byte[length];
        for (int index = 0; index < input.length; index++) {
            input[index] = index % 997 == 996 ? (byte) 'x' : alphabet[random.nextInt(alphabet.length)];
        }
        return input;
    }

    @SuppressWarnings("restricted")
    private static int countTaggedTransitions(Dfa.DfaInstance dfa)
    {
        MemorySegment transitions = MemorySegment.ofAddress(dfa.absolutePointerTransitionBaseAddress())
                .reinterpret(dfa.absolutePointerTransitionMemory());
        int count = 0;
        for (long offset = 0; offset < transitions.byteSize(); offset += Long.BYTES) {
            long transition = transitions.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
            if (transition != 0 && (transition & (Long.BYTES - 1)) != 0) {
                count++;
            }
        }
        return count;
    }

    private static Prog compileLatin1(String pattern)
    {
        ParseResult parsed = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL | Regexp.LATIN1);
        return Compiler.compile(parsed.regexp(), false, 0);
    }

    private static Dfa.DfaInstance exerciseReverseBoundary(String pattern, boolean latin1, Slice input, int contextStart, int contextEnd, int matchLength)
    {
        Prog reverseProgram = compileReverse(pattern, latin1);
        for (int iteration = 0; iteration < 20; iteration++) {
            long result = Dfa.search(
                    reverseProgram,
                    input,
                    contextStart,
                    contextEnd,
                    contextStart,
                    contextStart + matchLength,
                    true,
                    Prog.MatchKind.LONGEST_MATCH,
                    true);
            check(result == 0, "nonzero-region boundary changed: " + result);
        }
        return reverseProgram.getCachedDfa(LONGEST_MATCH);
    }

    private static Prog compileReverse(String pattern, boolean latin1)
    {
        int flags = Regexp.LIKE_PERL | (latin1 ? Regexp.LATIN1 : 0);
        ParseResult parsed = RegexpParser.parse(utf8Slice(pattern), flags);
        Prog reverseProgram = Compiler.compile(parsed.regexp(), true, 0);
        reverseProgram.setDfaMemory(64L << 20);
        return reverseProgram;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
