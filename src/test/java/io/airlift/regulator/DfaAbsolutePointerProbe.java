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
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Dfa.DfaInstance.Kind.FIRST_MATCH;
import static io.airlift.regulator.Dfa.DfaInstance.Kind.LONGEST_MATCH;
import static io.airlift.regulator.Re2BenchmarkRunner.compileProg;
import static io.airlift.slice.SizeOf.sizeOfLongArray;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.lang.Math.toIntExact;

public final class DfaAbsolutePointerProbe
{
    // The paired-row cases TestDfaPairedTransitions held when the probe began running them, so a
    // discovery that finds fewer fails instead of quietly testing one form.
    private static final int MINIMUM_PAIRED_TRANSITION_TESTS = 21;
    // The characters of the random words the budget-release fixtures search for.
    private static final String WORD_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

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

        exerciseCountSelfLoopMatchHandOff();
        exerciseCountSelfLoopMatchHandOffWithPairedRows();
        exerciseShortSearchesAfterPairedRejection();
        exercisePairedRowReleaseDuringSearch();
        exerciseOptionalStorageFailureAtomicity(true);
        exerciseOptionalStorageFailureAtomicity(false);
        exerciseCacheGrowthAtomicity();

        // With native access the sidecar is native memory charged its bytes; without it, a heap
        // array charged its real size and that of the class offsets the heap loops read.
        check(dfa.absolutePointerTransitionMemory() > 0, "search did not allocate a sidecar");
        MemorySegment segment = dfa.absolutePointerTransitionSegment();
        check(segment.isNative() == expectedNativeAccess, "sidecar memory does not follow native access");
        long expectedMemory = expectedNativeAccess ? segment.byteSize() : sizeOfLongArray(toIntExact(segment.byteSize() / Long.BYTES)) + sizeOfLongArray(256);
        check(dfa.absolutePointerTransitionMemory() == expectedMemory, "sidecar was not charged its real size");
        check(dfa.absolutePointerTransitionMemory() < 1L << 20, "small DFA reserved a maximum-capacity sidecar");
        check(dfa.absolutePointerTransitionCount() > 0, "search did not populate pointer transitions");
        check(dfa.availableStateMemory() + dfa.absolutePointerTransitionMemory() < dfa.stateBudget(), "sidecar was not charged to the DFA budget");
        check(reverseDfa.absolutePointerTransitionMemory() > 0, "variable boundary did not allocate a reverse sidecar");
        check(reverseDfa.absolutePointerTransitionCount() > 0, "variable boundary did not populate reverse pointer transitions");
        check(reverseDfa.absolutePointerBackwardSearchCount() > 0, "variable boundary did not use reverse pointer transitions");
        check(utf8ReverseDfa.absolutePointerBackwardSearchCount() > 0, "UTF-8 boundary did not use reverse pointer transitions");
        check(latin1ReverseDfa.absolutePointerBackwardSearchCount() > 0, "LATIN1 boundary did not use reverse pointer transitions");

        long originalMemory = dfa.absolutePointerTransitionMemory();
        exerciseResetOverlap(program, input, dfa, segment, originalMemory);
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
        growingDfa.failNextForTesting(Dfa.DfaInstance.TestingFailurePoint.POINTER_TABLE_GROWTH);
        expectTestingFailure(() -> Dfa.search(growingProgram, growingInput, false, Prog.MatchKind.FIRST_MATCH, true));
        check(growingDfa.testingSnapshotBeforeFailure().equals(growingDfa.failureAtomicitySnapshot()), "failed sidecar growth changed the DFA cache");
        exerciseGrowthOverlap(growingProgram, growthWarmupInput, growingInput, growingDfa);
        long growingPointerMemory = growingDfa.absolutePointerTransitionMemory();
        check((long) growingDfa.stateCount * growingDfa.nextSize * Long.BYTES > 48L << 10, "growing DFA did not cross the former initial tier");
        check(growingPointerMemory > initialGrowingPointerMemory, "growing DFA did not enlarge the sidecar");
        check(growingDfa.absolutePointerTransitionsAvailable(), "growing DFA switched to object traversal");
        check(growingDfa.availableStateMemory() + growingDfa.retainedStateMemory() + growingPointerMemory == growingDfa.stateBudget(), "growing DFA accounting did not reconcile to its memory budget");

        MemorySegment growingSegment = growingDfa.absolutePointerTransitionSegment();
        growingDfa.resetCacheExternal();
        check(!growingDfa.absolutePointerTransitionSegment().equals(growingSegment), "reset reused the grown sidecar");
        check(growingDfa.absolutePointerTransitionMemory() == growingPointerMemory, "reset changed the grown sidecar allocation");

        exerciseWithheldPointerTable("[ -~]*" + suffix + "$", input, expectedNativeAccess);
        exerciseFailedGrowthAllocation(growingPattern, growthWarmupInput, growingInput, expectedNativeAccess);

        runPairedTransitionTests();

        // The parent compares this line across forms.
        System.out.println("budget-release word-list resets=" + exerciseBudgetBoundRelease(expectedNativeAccess) +
                " input-shift states=" + exerciseInputShiftReleasesPointerTable() +
                " split-budget " + exerciseSplitBudgetDecisions());

        // The parent forces the paired form each way and checks the one this JVM ran.
        System.out.println((expectedNativeAccess ? "OK enabled" : "OK disabled") + " flat-paired-rows=" + Dfa.FLAT_PAIRED_ROWS);
    }

    // Runs every test of TestDfaPairedTransitions in this JVM's paired form, so their charge, cap,
    // release, and loop checks run in both forms on every host. The class has no fields or
    // lifecycle methods, so each test runs, in name order, on a fresh instance.
    private static void runPairedTransitionTests()
            throws ReflectiveOperationException
    {
        List<Method> tests = Arrays.stream(TestDfaPairedTransitions.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class))
                .sorted(Comparator.comparing(Method::getName))
                .toList();
        check(tests.size() >= MINIMUM_PAIRED_TRANSITION_TESTS, "found " + tests.size() + " TestDfaPairedTransitions tests, expected at least " + MINIMUM_PAIRED_TRANSITION_TESTS);
        for (Method test : tests) {
            try {
                test.invoke(new TestDfaPairedTransitions());
            }
            catch (InvocationTargetException e) {
                throw new AssertionError("TestDfaPairedTransitions." + test.getName() + " failed with flat-paired-rows=" + Dfa.FLAT_PAIRED_ROWS, e.getCause());
            }
        }
    }

    // A reset that cannot have a new segment, because superseded native segments still await
    // collection or the allocation fails, publishes its tables without the pointer table and keeps it
    // selected; a later exclusive search allocates it again. Superseded heap tables are neither
    // counted nor capped.
    private static void exerciseWithheldPointerTable(String pattern, Slice input, boolean expectedNativeAccess)
            throws InterruptedException
    {
        Prog program = compileProg(pattern);
        program.setDfaMemory(64L << 20);
        check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "withheld-table warm-up failed");
        Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);
        check(dfa.absolutePointerTransitionsAvailable(), "withheld-table warm-up did not allocate a sidecar");
        // Growth during the warm-up may have superseded native segments; the native cap checks below
        // count from zero.
        awaitSupersededMemory(dfa, 0);
        long segmentMemory = dfa.absolutePointerTransitionMemory();

        // Searches still reading superseded tables keep them. For native tables, two superseded
        // segments fit within the cap of twice the segment; a third would not.
        List<Dfa.CacheTables> readerTables = new ArrayList<>();
        for (int reset = 0; reset < 2; reset++) {
            readerTables.add(dfa.cacheTablesForTesting());
            dfa.resetCacheExternal();
            check(dfa.absolutePointerTransitionsAvailable(), "reset below the native superseded-segment cap withheld the sidecar");
        }
        readerTables.add(dfa.cacheTablesForTesting());
        dfa.resetCacheExternal();
        check(!readerTables.contains(dfa.cacheTablesForTesting()), "reset republished tables a reader holds");
        if (expectedNativeAccess) {
            check(!dfa.absolutePointerTransitionsAvailable(), "reset at the superseded-segment cap allocated another sidecar");
            check(dfa.supersededAbsolutePointerMemory() == 3 * segmentMemory, "superseded sidecars were not counted");
            assertWithheld(dfa, "superseded-segment cap");
            check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "search at the superseded-segment cap changed the result");
            check(!dfa.absolutePointerTransitionsAvailable(), "exclusive search allocated a sidecar at the superseded-segment cap");
        }
        else {
            check(dfa.absolutePointerTransitionsAvailable(), "reset withheld a heap sidecar while readers held superseded ones");
            check(dfa.supersededAbsolutePointerMemory() == 0, "superseded heap sidecars were counted");
        }

        // The readers return, releasing the superseded tables they held in either mode; only native
        // ones were counted toward the cap.
        readerTables.clear();
        if (expectedNativeAccess) {
            awaitSupersededMemory(dfa, 0);
            assertReselected(program, input, dfa, "superseded-segment cap");
        }

        dfa.failNextForTesting(Dfa.DfaInstance.TestingFailurePoint.POINTER_TABLE_RESET);
        dfa.resetCacheExternal();
        assertWithheld(dfa, "failed sidecar allocation");
        check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "search after a failed sidecar allocation changed the result");
        check(!dfa.absolutePointerTransitionsAvailable(), "exclusive search allocated a sidecar again before the next reset");
        dfa.resetCacheExternal();
        awaitSupersededMemory(dfa, 0);
        assertReselected(program, input, dfa, "failed sidecar allocation");
    }

    // A growth whose segment cannot be allocated withholds the table: the DFA goes on adding states
    // on object rows, refunds the current segment's charge and counts a native segment as
    // superseded, and allocates no pointer table again until the next reset.
    private static void exerciseFailedGrowthAllocation(String pattern, Slice warmupInput, Slice input, boolean expectedNativeAccess)
            throws InterruptedException
    {
        Prog program = growingProgram(pattern, warmupInput);
        Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);
        check(dfa.absolutePointerTransitionsAvailable(), "failed-growth warm-up did not allocate a sidecar");
        long segmentMemory = dfa.absolutePointerTransitionMemory();
        check(dfa.supersededAbsolutePointerMemory() == 0, "failed-growth warm-up superseded a sidecar");
        // A search still reading the warm-up tables keeps the segment the growth supersedes.
        List<Dfa.CacheTables> readerTables = new ArrayList<>(List.of(dfa.cacheTablesForTesting()));

        dfa.failNextForTesting(Dfa.DfaInstance.TestingFailurePoint.POINTER_TABLE_GROWTH_ALLOCATION);
        check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == input.length(), "search through a failed growth allocation changed the result");
        check((long) dfa.stateCount * dfa.nextSize * Long.BYTES > segmentMemory, "DFA with a failed growth allocation stopped adding states");
        assertWithheld(dfa, "failed growth allocation");
        check(dfa.supersededAbsolutePointerMemory() == (expectedNativeAccess ? segmentMemory : 0), "failed growth allocation miscounted the superseded sidecar");
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

    // A program of about 8,000 instructions, one alternative for each of 1,000 random words,
    // searched for each word's prefix, so nearly every searched byte adds a state. Its budget
    // cannot hold them all beside the pointer table, whose rows in use cost more than a quarter
    // of the generation's Java states, so the first state the budget cannot hold gives the table
    // back, and the DFA resets on object rows from then on. The choice reads only the graph, so a
    // native DFA that waits after each search for the collector to find its superseded segments
    // resets as often as one that leaves collection to chance, and as often as a heap DFA.
    // Returns the reset count.
    private static int exerciseBudgetBoundRelease(boolean expectedNativeAccess)
            throws InterruptedException
    {
        int resets = searchWordListOverBudget(false);
        if (expectedNativeAccess) {
            int collectedResets = searchWordListOverBudget(true);
            check(collectedResets == resets, "collecting superseded sidecars changed the budget-bound resets from " + resets + " to " + collectedResets);
        }
        exerciseWithheldTableBudgetRelease();
        // Without the slow-reset check, which gives the table back at the same reset, only the
        // budget rule's second state in a row can give it back.
        boolean bailWhenSlow = Dfa.dfaShouldBailWhenSlow;
        Dfa.dfaShouldBailWhenSlow = false;
        try {
            exerciseWithheldTableBudgetRelease();
        }
        finally {
            Dfa.dfaShouldBailWhenSlow = bailWhenSlow;
        }
        return resets;
    }

    private static int searchWordListOverBudget(boolean collectSuperseded)
            throws InterruptedException
    {
        List<String> words = randomWords(WORD_ALPHABET);
        Prog program = wordListProgram(words, 4L << 20);
        searchWordPrefixes(program, words, 0, 1);
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        searchEveryWordPrefixTwice(program, words, dfa, collectSuperseded, 0);
        return dfa.resetCount();
    }

    // A table that a failed growth allocation withholds is weighed as one held, but it has no
    // charge to refund, so the DFA resets instead: one search through every word's prefix only
    // records its first reset and gives the table back at its second, where it has reset too
    // often but goes on, as it would had it given the table back itself. Its resets are not
    // compared across forms; the fixture checks only where the release lands.
    private static void exerciseWithheldTableBudgetRelease()
            throws InterruptedException
    {
        List<String> words = randomWords(WORD_ALPHABET);
        Prog program = wordListProgram(words, 4L << 20);
        searchWordPrefixes(program, words, 0, 1);
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        // Enough short searches allocate the sidecar.
        for (int word = 1; word < words.size() && !dfa.absolutePointerTransitionsAvailable(); word++) {
            searchWordPrefixes(program, words, word, word + 1);
        }
        check(dfa.absolutePointerTransitionsAvailable(), "short word-prefix searches did not allocate a sidecar");
        dfa.failNextForTesting(Dfa.DfaInstance.TestingFailurePoint.POINTER_TABLE_GROWTH_ALLOCATION);
        StringBuilder prefixes = new StringBuilder();
        for (String word : words) {
            prefixes.append(wordPrefix(word));
        }
        long result = Dfa.search(program, utf8Slice(prefixes.toString()), false, Prog.MatchKind.FIRST_MATCH, true);
        check(result == Dfa.SEARCH_NO_MATCH, "search through every word's prefix returned " + result);
        check(dfa.resetCount() == 2, "search through every word's prefix reset " + dfa.resetCount() + " times, expected 2");
        check(dfa.releasedAbsolutePointerResetCount() == 2, "withheld sidecar was given back at reset " + dfa.releasedAbsolutePointerResetCount() + ", expected 2");
        searchEveryWordPrefixTwice(program, words, dfa, false, 2);
    }

    // Searches every word's prefix twice, then checks that the DFA reset on object rows after
    // giving the sidecar back at `releasedResetCount`.
    private static void searchEveryWordPrefixTwice(Prog program, List<String> words, Dfa.DfaInstance dfa, boolean collectSuperseded, int releasedResetCount)
            throws InterruptedException
    {
        for (int pass = 0; pass < 2; pass++) {
            for (int word = 0; word < words.size(); word++) {
                searchWordPrefixes(program, words, word, word + 1);
                if (collectSuperseded && dfa.supersededAbsolutePointerMemory() > 0) {
                    awaitSupersededMemory(dfa, 0);
                }
            }
        }
        check(dfa.resetCount() > 2, "budget-bound fixture reset " + dfa.resetCount() + " times, expected more than 2");
        check(dfa.releasedAbsolutePointerResetCount() == releasedResetCount, "budget rule gave the sidecar back at reset " + dfa.releasedAbsolutePointerResetCount() + ", expected " + releasedResetCount);
        check(!dfa.absolutePointerTransitionsAvailable(), "sidecar was published again after it was given back");
        check(dfa.absolutePointerTransitionsUnavailable(), "sidecar given back can be allocated again");
    }

    // The same program at a budget where the first 500 words' prefixes fit beside the pointer
    // table, but not the last 500's as well. Moving to the second half reaches a state the budget
    // cannot hold, which gives the table back instead of resetting: its charge is refunded, every
    // state is kept, and all 1,000 words' prefixes fit on object rows. Returns the state count,
    // which the parent compares across forms.
    private static int exerciseInputShiftReleasesPointerTable()
    {
        List<String> words = randomWords(WORD_ALPHABET);
        Prog reference = wordListProgram(words, 2 * (16 << 20));
        for (int pass = 0; pass < 2; pass++) {
            searchWordPrefixes(reference, words, 0, 1_000);
        }
        Dfa.DfaInstance warm = reference.getCachedDfa(FIRST_MATCH);
        check(warm.resetCount() == 0, "reference reset " + warm.resetCount() + " times over every word's prefixes");
        check(warm.absolutePointerTransitionsAvailable(), "reference held every word's prefixes without a sidecar");
        long fixedOverhead = (16 << 20) - warm.stateBudget();

        // Java states take a different size in each object layout, so the budget holds the warm
        // DFA's Java states, every word's prefixes on object rows, with an eighth to spare.
        Prog program = wordListProgram(words, 2 * (warm.retainedStateMemory() * 9 / 8 + fixedOverhead));
        for (int pass = 0; pass < 2; pass++) {
            searchWordPrefixes(program, words, 0, 500);
        }
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        check(dfa.resetCount() == 0, "first half of the words reset " + dfa.resetCount() + " times");
        check(dfa.absolutePointerTransitionsAvailable(), "first half of the words did not fit beside the sidecar");
        // The first pass reaches the state the budget cannot hold beside the table; the other
        // three replay the second half on object rows, which hold it without resetting.
        for (int pass = 0; pass < 4; pass++) {
            searchWordPrefixes(program, words, 500, 1_000);
        }
        check(dfa.resetCount() == 0, "second half of the words reset " + dfa.resetCount() + " times instead of giving the sidecar back");
        check(dfa.releasedAbsolutePointerResetCount() == 0, "state the budget could not hold gave the sidecar back at reset " + dfa.releasedAbsolutePointerResetCount() + ", expected 0");
        check(!dfa.absolutePointerTransitionsAvailable(), "sidecar given back is still published");
        check(dfa.absolutePointerTransitionsUnavailable(), "sidecar given back can be allocated again");
        check(dfa.availableStateMemory() + dfa.retainedStateMemory() == dfa.stateBudget(), "giving the sidecar back kept its charge");
        int stateCount = dfa.stateCount;
        searchWordPrefixes(program, words, 0, 500);
        check(dfa.stateCount == stateCount, "replaying the first half after the sidecar was given back added " + (dfa.stateCount - stateCount) + " states");

        // The table was gone with the tables published at reset count 0, so a count that began
        // there spends its allowance of two resets on the resets from then on.
        for (int resets = 0; ; resets++) {
            boolean resetsTooOften = dfa.countResetsTooOften(0, false);
            check(resetsTooOften == (resets >= 2), "count that began at the release judged " + resets + " resets too often: " + resetsTooOften);
            boolean handsResetsToLock = dfa.countHandsResetsToLock(0);
            check(handsResetsToLock == (resets < 2), "count that began at the release handed " + resets + " resets to the lock: " + handsResetsToLock);
            if (resets == 2) {
                return stateCount;
            }
            dfa.resetCacheExternal();
        }
    }

    // Public `Re2` finds of 1,000 random words, plus a branch that reads the whole line, at
    // budgets that first run out at the same word, and so the same graph, in either form. The heap
    // table's header makes its allocation larger than the native one's, and both are larger than
    // the rows in use, which the rule weighs. Over 14 letters the rows in use sit just under the
    // cutoff, at about 24% of the Java states with compressed object pointers and 20% without, so
    // the DFA keeps the table and resets. Over 20 letters they sit above it, at about 30% and 28%,
    // so the DFA gives the table back and does not reset. Returns each word and decision, which
    // the parent compares across forms.
    private static String exerciseSplitBudgetDecisions()
    {
        return "keep " + splitBudgetDecision(14, 3_070_000, false) + " give-back " + splitBudgetDecision(20, 4_000_000, true);
    }

    private static String splitBudgetDecision(int alphabetSize, long maxMemory, boolean releases)
    {
        List<String> words = randomWords(WORD_ALPHABET.substring(0, alphabetSize));
        Re2 pattern = Re2.compile(utf8Slice(String.join("|", words) + "|[ -~]+z"), Re2.Options.defaults().setMaxMemory(maxMemory));
        Prog program = pattern.forwardProgramForDiagnostics();
        for (int word = 0; word < words.size(); word++) {
            check(!pattern.matcher(utf8Slice(wordPrefix(words.get(word)))).find(), "word prefix matched");
            Dfa.DfaInstance dfa = program.cachedDfaIfPresent(FIRST_MATCH);
            if (dfa != null && (dfa.resetCount() > 0 || dfa.releasedAbsolutePointerResetCount() >= 0)) {
                String fixture = alphabetSize + "-letter split-budget fixture";
                if (releases) {
                    check(dfa.resetCount() == 0, fixture + " reset " + dfa.resetCount() + " times at word " + word + " instead of giving the sidecar back");
                    check(dfa.releasedAbsolutePointerResetCount() == 0, fixture + " gave the sidecar back at reset " + dfa.releasedAbsolutePointerResetCount() + ", expected 0");
                }
                else {
                    check(dfa.resetCount() == 1, fixture + " reset " + dfa.resetCount() + " times at word " + word + ", expected 1");
                    check(dfa.releasedAbsolutePointerResetCount() == -1, fixture + " gave back a sidecar whose rows cost under a quarter of the Java states, at reset " + dfa.releasedAbsolutePointerResetCount());
                }
                return "word=" + word + " resets=" + dfa.resetCount() + " released=" + dfa.releasedAbsolutePointerResetCount();
            }
        }
        throw new AssertionError(alphabetSize + "-letter split-budget fixture did not exhaust its budget");
    }

    // 1,000 random eight-character words over the alphabet, from a fixed seed, so every run and
    // form searches the same words.
    private static List<String> randomWords(String alphabet)
    {
        Random random = new Random(1);
        List<String> words = new ArrayList<>();
        for (int wordIndex = 0; wordIndex < 1_000; wordIndex++) {
            StringBuilder word = new StringBuilder();
            for (int letter = 0; letter < 8; letter++) {
                word.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            words.add(word.toString());
        }
        return words;
    }

    // A program that matches any of the words, with a DFA budget of `dfaMemory`.
    private static Prog wordListProgram(List<String> words, long dfaMemory)
    {
        Prog program = compileProg(String.join("|", words));
        program.setDfaMemory(dfaMemory);
        return program;
    }

    // A word without its last character, followed by a space, so no word matches it.
    private static String wordPrefix(String word)
    {
        return word.substring(0, word.length() - 1) + " ";
    }

    // Searches the prefix of each word in [from, to).
    private static void searchWordPrefixes(Prog program, List<String> words, int from, int to)
    {
        for (String word : words.subList(from, to)) {
            long result = Dfa.search(program, utf8Slice(wordPrefix(word)), false, Prog.MatchKind.FIRST_MATCH, true);
            check(result == Dfa.SEARCH_NO_MATCH, "search of word prefix " + wordPrefix(word) + " returned " + result);
        }
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
            MemorySegment originalSegment,
            long originalMemory)
            throws Exception
    {
        WeakReference<Object> supersededOwner = dfa.absolutePointerOwnerReferenceForTesting();
        FutureTask<Long> readerTask = new FutureTask<>(() -> Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true));
        Thread reader = Thread.ofPlatform().daemon().unstarted(readerTask);
        FutureTask<Void> resetTask = new FutureTask<>(() -> {
            dfa.resetCacheExternal();
            return null;
        });
        Dfa.armPointerReaderProbeForTesting(reader);
        try {
            reader.start();
            check(Dfa.awaitPointerReaderProbeForTesting(10, TimeUnit.SECONDS), "pointer reader did not reach the reset overlap probe");
            // Readers do not register: the reset runs while the reader is paused inside the pointer
            // table and publishes a fresh one, leaving the reader's table unchanged.
            Thread.ofPlatform().daemon().start(resetTask);
            resetTask.get(10, TimeUnit.SECONDS);
            check(!dfa.absolutePointerTransitionSegment().equals(originalSegment), "reset reused the sidecar a paused reader reads");
            check(dfa.absolutePointerTransitionCount() == 0, "reset left pointer transitions in the current table");
            // The reader's reference keeps the superseded table, and a native table's automatic
            // arena, alive.
            for (int i = 0; i < 5; i++) {
                System.gc();
                Thread.sleep(Duration.ofMillis(10));
            }
            check(supersededOwner.get() != null, "superseded pointer table owner was collected while a paused reader used it");
        }
        finally {
            Dfa.releasePointerReaderProbeForTesting();
        }
        check(readerTask.get(10, TimeUnit.SECONDS) == input.length(), "paused pointer reader changed the match result");
        check(dfa.absolutePointerTransitionMemory() == originalMemory, "reset changed the replacement sidecar charge");
        // The released reader finished in the superseded tables, which are no longer charged.
        check(dfa.availableStateMemory() + dfa.retainedStateMemory() + originalMemory == dfa.stateBudget(), "reset returned the replacement sidecar charge");

        awaitCollection(supersededOwner);
    }

    private static void exerciseGrowthOverlap(
            Prog program,
            Slice readerInput,
            Slice growingInput,
            Dfa.DfaInstance dfa)
            throws Exception
    {
        MemorySegment originalSegment = dfa.absolutePointerTransitionSegment();
        long originalMemory = dfa.absolutePointerTransitionMemory();
        WeakReference<Object> retiredOwner = dfa.absolutePointerOwnerReferenceForTesting();

        FutureTask<Long> readerTask = new FutureTask<>(() -> Dfa.search(program, readerInput, false, Prog.MatchKind.FIRST_MATCH, true));
        Thread reader = Thread.ofPlatform().daemon().unstarted(readerTask);
        FutureTask<Long> growthTask = new FutureTask<>(() -> Dfa.search(program, growingInput, false, Prog.MatchKind.FIRST_MATCH, true));
        Dfa.armPointerReaderProbeForTesting(reader);
        try {
            reader.start();
            check(Dfa.awaitPointerReaderProbeForTesting(10, TimeUnit.SECONDS), "pointer reader did not reach the growth overlap probe");
            // The growth completes while the reader is paused inside the retired table. The
            // reader's reference keeps the retired table, and a native table's automatic arena,
            // alive.
            Thread.ofPlatform().daemon().start(growthTask);
            check(growthTask.get(10, TimeUnit.SECONDS) == growingInput.length(), "growing DFA search failed while a reader was paused");
            check(!dfa.absolutePointerTransitionSegment().equals(originalSegment), "growth did not rebase the sidecar");
            for (int i = 0; i < 5; i++) {
                System.gc();
                Thread.sleep(Duration.ofMillis(10));
            }
            check(retiredOwner.get() != null, "retired pointer table owner was collected while a paused reader used it");
        }
        finally {
            Dfa.releasePointerReaderProbeForTesting();
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
        check(reference.get() == null, "superseded pointer table owner remained reachable");
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
    // match bit, so a count holds a pending match through the rest of the text, which must
    // continue through the pointer table.
    private static void exerciseCountSelfLoopMatchHandOff()
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
        boolean vectors = VectorSupport.isAvailable();
        int handOffCount = dfa.countSelfLoopMatchHandOffCount();
        // The first count computes the states after the x and asks for the pointer table, which its
        // exclusive search allocates when it ends. With vectors, the count self-loop scan stops
        // with the match pending, and its hand-off asks for the table rather than for paired rows,
        // which would prevent its allocation. Without vectors the scan does not run, and the
        // ordinary search asks for the table.
        check(pattern.count(input) == 1, "pending-match count miscounted");
        long cacheVersion = dfa.cacheVersion();
        for (int iteration = 0; iteration < 3; iteration++) {
            check(pattern.count(input) == 1, "pending-match count miscounted");
        }
        check(dfa.cacheVersion() == cacheVersion, "pending-match counts retried exclusively");
        if (vectors) {
            check(dfa.countSelfLoopMatchHandOffCount() > handOffCount, "count self-loop scan did not hand off the pending match");
        }
        check(dfa.absolutePointerTransitionsAvailable(), "pending-match counts did not allocate a pointer sidecar");
        check(dfa.pairedTransitionMemory() == 0, "pending-match counts built paired transitions");
    }

    // Paired rows block pointer-table allocation, so once an ordinary search has built them, a
    // pending match continues through the paired after-match loop. Requesting the
    // pointer table instead would force an exclusive retry on every count and still fall back to
    // the compact loop. Without vectors the count self-loop scan does not run, and each count
    // searches the paired rows from the start.
    private static void exerciseCountSelfLoopMatchHandOffWithPairedRows()
    {
        TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(".*(x|y).*"));
        Prog program = pattern.re2().forwardProgramForDiagnostics();
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        boolean vectors = VectorSupport.isAvailable();
        Slice noMatch = utf8Slice("\u00e9a".repeat(200));
        for (int iteration = 0; iteration < 2; iteration++) {
            check(Dfa.search(program, noMatch, false, Prog.MatchKind.FIRST_MATCH, true) == Dfa.SEARCH_NO_MATCH, "count self-loop paired warm-up found a match");
        }
        check(dfa.pairedTransitionMemory() > 0, "count self-loop paired warm-up did not build paired transitions");

        Slice input = utf8Slice("xaaaaaaaa" + "\u00e9a".repeat(2_000));
        // The first count computes the states after the x, and its exclusive search builds their
        // paired rows when it ends.
        check(pattern.count(input) == 1, "paired pending-match count miscounted");
        long cacheVersion = 0;
        for (int iteration = 0; iteration < 5; iteration++) {
            // The last three counts must run without an exclusive retry.
            if (iteration == 2) {
                cacheVersion = dfa.cacheVersion();
            }
            int handOffCount = dfa.countSelfLoopMatchHandOffCount();
            check(pattern.count(input) == 1, "paired pending-match count miscounted");
            if (vectors) {
                check(dfa.countSelfLoopMatchHandOffCount() > handOffCount, "count self-loop scan did not hand off the pending match to paired transitions");
            }
        }
        check(dfa.cacheVersion() == cacheVersion, "paired pending-match counts retried exclusively");
        check(dfa.pairedTransitionMemory() > 0, "paired pending-match counts released the paired transitions");
    }

    // The sixteenth short paired search rejects paired transitions but keeps its result, so the
    // rows remain until a later request releases them. Short searches never request paired rows,
    // and the rows block the pointer table, so the pointer request must release them; otherwise
    // every later short search retries exclusively and still uses the compact loop.
    private static void exerciseShortSearchesAfterPairedRejection()
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
        check(dfa.absolutePointerTransitionsAvailable(), "short searches after paired rejection did not allocate a pointer sidecar");
        check(dfa.pairedTransitionMemory() == 0, "pointer request retained rejected paired transitions");
    }

    // A paired search that reaches a long matching self-loop computes the loop's exit bytes, which
    // can add a state. Near the memory limit, the room for that state comes from releasing the
    // paired rows the search is reading, and the search must go on without them. The budgets at
    // which this happens move with unrelated changes, so each pattern sweeps a range around them.
    // The second pattern loops on a letter class instead of any byte, and its 20 byte classes make
    // its paired rows about eight times larger, so it releases them at over twice the budget, in
    // either form. The first pattern releases only its object rows: its flat table always leaves
    // room for the states the search adds.
    private static void exercisePairedRowReleaseDuringSearch()
    {
        Slice anyByteInput = utf8Slice("x".repeat(64) + "a".repeat(256));
        if (Dfa.FLAT_PAIRED_ROWS) {
            exerciseFlatPairedTableKeptThroughSearch("(?s).*x", 16 * 1024, 40 * 1024, anyByteInput);
        }
        else {
            exercisePairedRowReleaseSweep("(?s).*x", 19 * 1024, 24 * 1024, anyByteInput);
        }
        exercisePairedRowReleaseSweep(
                "^(x*a[b-z]*(\\x01c|\\x02d|\\x03e|\\x04f|\\x05g|\\x06h|\\x07i|\\x08j)?|x*a)",
                30 * 1024,
                50 * 1024,
                utf8Slice("x".repeat(300) + "a" + "b".repeat(200) + "q"));
    }

    // The first search builds paired rows where they fit. The release counter is read around the
    // repeat search because paired memory alone also drops to zero when an exclusive search ends
    // and the rebuilt rows do not fit. These patterns release the rows midway only while computing
    // a loop's exit bytes, which only the vector scan does during a search, so without vectors the
    // sweep need not reach a release. The searches call the DFA directly, because the public find
    // would hide a DFA that gave up after the release by running another engine.
    private static void exercisePairedRowReleaseSweep(String pattern, long minimumMemory, long maximumMemory, Slice input)
    {
        Re2 reference = Re2.compile(utf8Slice(pattern));
        String expected = findResult(reference, input);
        long expectedEnd = Dfa.search(reference.forwardProgramForDiagnostics(), input, false, Prog.MatchKind.FIRST_MATCH, true);
        boolean released = false;
        for (long maxMemory = minimumMemory; maxMemory <= maximumMemory; maxMemory += 64) {
            String description = pattern + " with max memory " + maxMemory;
            Re2 re2 = Re2.compile(utf8Slice(pattern), Re2.Options.defaults().setMaxMemory(maxMemory));
            Prog program = re2.forwardProgramForDiagnostics();
            long matchEnd = Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true);
            check(matchEnd != Dfa.SEARCH_FAILED, description + " gave up the first DFA search");
            check(matchEnd == expectedEnd, description + " changed the first DFA result");
            Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
            check(dfa.pairedTransitionMemory() == 0 || dfa.absolutePointerTransitionMemory() == 0, description + " held paired rows beside a pointer table after the first search");
            int releaseCount = dfa.pairedRowReleaseCount();
            check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == matchEnd, description + " changed the repeat DFA result");
            released |= dfa.pairedRowReleaseCount() > releaseCount;
            check(dfa.pairedTransitionMemory() == 0 || dfa.absolutePointerTransitionMemory() == 0, description + " held paired rows beside a pointer table after the repeat search");
            check(findResult(re2, input).equals(expected), description + " changed the result");
        }
        check(released || !VectorSupport.isAvailable(), pattern + " sweep did not release paired rows during a search");
    }

    // On the flat table, the release case above never arises for (?s).*x: at every budget from the
    // first sampled one at which its first DFA search works, that search pairs all of its states,
    // and the repeat search keeps the table to its end. The complete flat table always leaves room
    // for the states the repeat search adds, while the same states' object rows don't. The sweep
    // starts below the first sampled working budget, so it checks that budget too.
    private static void exerciseFlatPairedTableKeptThroughSearch(String pattern, long minimumMemory, long maximumMemory, Slice input)
    {
        Re2 reference = Re2.compile(utf8Slice(pattern));
        String expected = findResult(reference, input);
        long expectedEnd = Dfa.search(reference.forwardProgramForDiagnostics(), input, false, Prog.MatchKind.FIRST_MATCH, true);
        long firstWorkingMemory = -1;
        for (long maxMemory = minimumMemory; maxMemory <= maximumMemory; maxMemory += 64) {
            String description = pattern + " with max memory " + maxMemory;
            Re2 re2 = Re2.compile(utf8Slice(pattern), Re2.Options.defaults().setMaxMemory(maxMemory));
            Prog program = re2.forwardProgramForDiagnostics();
            long matchEnd = Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true);
            if (firstWorkingMemory < 0) {
                if (matchEnd == Dfa.SEARCH_FAILED) {
                    continue;
                }
                firstWorkingMemory = maxMemory;
            }
            check(matchEnd != Dfa.SEARCH_FAILED, description + " gave up the first DFA search above the first sampled working budget");
            check(matchEnd == expectedEnd, description + " changed the first DFA result");
            Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
            check(dfa.pairedTransitionRowCount() > 0 && dfa.pairedTransitionRowCount() == dfa.stateCount, description + " did not pair every state on the flat table");
            check(dfa.absolutePointerTransitionMemory() == 0, description + " held a pointer table beside the flat paired table");
            int releaseCount = dfa.pairedRowReleaseCount();
            check(Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true) == matchEnd, description + " changed the repeat DFA result");
            check(dfa.pairedRowReleaseCount() == releaseCount, description + " released the flat paired table during the repeat search");
            check(findResult(re2, input).equals(expected), description + " changed the result");
        }
        check(firstWorkingMemory > minimumMemory, pattern + " sweep did not start below the first sampled budget at which the first DFA search works");
    }

    // Fails a cache insertion with the pointer table, or with paired transitions, in place.
    private static void exerciseOptionalStorageFailureAtomicity(boolean pointerTable)
    {
        String suffix = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        Prog program = compileProg(pointerTable
                ? "(?:\\bx*|y*)" + suffix + '$'
                : "(?:\\bx*|y*)(a[01]{200}|a)");
        program.setDfaMemory(pointerTable ? 64L << 20 : 64 * 1024);
        Slice warmupInput = utf8Slice("x".repeat(300) + (pointerTable ? suffix : "a0"));
        boolean anchored = !pointerTable;
        Dfa.search(program, warmupInput, anchored, Prog.MatchKind.FIRST_MATCH, true);
        Dfa.DfaInstance dfa = program.getCachedDfa(pointerTable ? LONGEST_MATCH : FIRST_MATCH);
        if (pointerTable) {
            check(dfa.absolutePointerTransitionMemory() > 0, "pointer atomicity fixture did not allocate a pointer sidecar");
        }
        else {
            check(dfa.pairedTransitionMemory() > 0, "paired atomicity fixture did not allocate paired transitions");
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

    private static void exerciseCacheGrowthAtomicity()
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
        check(dfa.absolutePointerTransitionMemory() > 0, "cache-growth fixture did not allocate a pointer sidecar");

        Slice warmupInput = wrappedBuffer(stateExplosionInput(1_538));
        Dfa.search(program, warmupInput, false, Prog.MatchKind.FIRST_MATCH, true);
        Dfa.FailureAtomicitySnapshot warmed = dfa.failureAtomicitySnapshot();
        check(warmed.cacheEntries() == (warmed.cacheTableCapacity() * 3) / 4, "cache-growth fixture did not reach the resize threshold");

        Slice growingInput = wrappedBuffer(stateExplosionInput(16 * 1024));
        dfa.failNextForTesting(Dfa.DfaInstance.TestingFailurePoint.STATE_CACHE_INSERTION);
        expectTestingFailure(() -> Dfa.search(program, growingInput, false, Prog.MatchKind.FIRST_MATCH, true));
        check(dfa.testingSnapshotBeforeFailure().equals(dfa.failureAtomicitySnapshot()), "failed cache growth changed storage or accounting");
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

    private static int countTaggedTransitions(Dfa.DfaInstance dfa)
    {
        MemorySegment transitions = dfa.absolutePointerTransitionSegment();
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

    private static String findResult(Re2 re2, Slice input)
    {
        RegexpMatcher matcher = re2.matcher(input);
        return matcher.find() ? matcher.start() + "-" + matcher.end() : "none";
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
