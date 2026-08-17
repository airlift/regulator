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
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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
        Prog growingProgram = compileProg("[ -~]*" + growingSuffix + "$");
        growingProgram.setDfaMemory(64L << 20);
        Slice growthWarmupInput = utf8Slice("_".repeat(4_096));
        Dfa.DfaInstance growingDfa = growingProgram.getCachedDfa(LONGEST_MATCH);
        growingDfa.beginSearch(true);
        try {
            growingDfa.analyzeStart(
                    growthWarmupInput,
                    growthWarmupInput.byteArrayOffset(),
                    growthWarmupInput.byteArrayOffset() + growthWarmupInput.length(),
                    false,
                    true);
            growingDfa.requestAbsolutePointerTransitions();
        }
        finally {
            growingDfa.endSearch(true, null);
        }
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
        check(growingDfa.absolutePointerTransitionBaseAddress() == growingAddress, "reset changed the grown sidecar address");
        check(growingDfa.absolutePointerTransitionMemory() == growingPointerMemory, "reset changed the grown sidecar allocation");

        System.out.println("OK enabled");
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
            check(Dfa.readerRegistrySnapshot().activeReaders() > 0, "paused native reader was not registered");
            Thread.ofPlatform().daemon().start(resetTask);
            awaitCondition(dfa::hasPendingCacheMutationForTesting, "reset did not publish pending mutation");
            check(!resetTask.isDone(), "reset completed while a native reader was paused");
            check(dfa.absolutePointerTransitionBaseAddress() == originalAddress, "blocked reset changed the sidecar address");
            check(dfa.absolutePointerTransitionMemory() == originalMemory, "blocked reset changed the sidecar allocation");
        }
        finally {
            Dfa.releaseNativeReaderProbeForTesting();
        }
        check(readerTask.get(10, TimeUnit.SECONDS) == input.length(), "paused native reader changed the match result");
        resetTask.get(10, TimeUnit.SECONDS);
        check(dfa.absolutePointerTransitionBaseAddress() == originalAddress, "reset changed the sidecar address");
        check(dfa.absolutePointerTransitionMemory() == originalMemory, "reset changed the permanent sidecar charge");
        check(dfa.absolutePointerTransitionCount() == 0, "reset retained pointer transitions");
        check(dfa.availableStateMemory() + dfa.retainedStateMemory() + originalMemory == dfa.stateBudget(), "reset returned the permanent sidecar charge");
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
            check(Dfa.readerRegistrySnapshot().activeReaders() > 0, "paused growth reader was not registered");
            Thread.ofPlatform().daemon().start(growthTask);
            awaitCondition(dfa::hasPendingCacheMutationForTesting, "growth did not publish pending mutation");
            check(!growthTask.isDone(), "growth completed while a native reader was paused");
            check(dfa.absolutePointerTransitionBaseAddress() == originalAddress, "blocked growth rebased the sidecar");
            check(dfa.absolutePointerTransitionMemory() == originalMemory, "blocked growth changed the sidecar allocation");
        }
        finally {
            Dfa.releaseNativeReaderProbeForTesting();
        }
        check(readerTask.get(10, TimeUnit.SECONDS) == Dfa.SEARCH_NO_MATCH, "paused growth reader changed the search result");
        check(growthTask.get(10, TimeUnit.SECONDS) == growingInput.length(), "growing DFA search failed");
        check(dfa.absolutePointerTransitionBaseAddress() != originalAddress, "growth did not rebase the sidecar");
        check(dfa.absolutePointerTransitionMemory() > originalMemory, "growth did not enlarge the sidecar");
        check(dfa.availableStateMemory() + dfa.retainedStateMemory() + dfa.absolutePointerTransitionMemory() == dfa.stateBudget(), "growth accounting did not reconcile");

        awaitCollection(retiredOwner);
    }

    private static void awaitCondition(BooleanSupplier condition, String message)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofMillis(1));
        }
        check(condition.getAsBoolean(), message);
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
            dfa.analyzeStart(seedInput, 0, seedInput.length(), false, true);
            dfa.requestAbsolutePointerTransitions();
        }
        finally {
            dfa.endSearch(true, null);
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
