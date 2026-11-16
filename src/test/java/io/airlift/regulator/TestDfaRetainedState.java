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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static io.airlift.regulator.Dfa.DfaInstance.Kind.FIRST_MATCH;
import static io.airlift.regulator.Dfa.DfaInstance.Kind.LONGEST_MATCH;
import static io.airlift.slice.SizeOf.sizeOfIntArray;
import static io.airlift.slice.SizeOf.sizeOfLongArray;
import static io.airlift.slice.SizeOf.sizeOfObjectArray;
import static java.lang.Math.toIntExact;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestDfaRetainedState
{
    @Test
    public void testMemoryBudgetDoesNotChangeColdAllocation()
    {
        Dfa.DfaInstance smallBudget = compileDfa("0[01]{8}$", 1 << 20);
        Dfa.DfaInstance largeBudget = compileDfa("0[01]{8}$", 32 << 20);

        assertThat(largeBudget.retainedStateMemory())
                .isEqualTo(smallBudget.retainedStateMemory());
    }

    @Test
    public void testResetPublishesEmptyTablesAndReturnsStateCharge()
    {
        Prog program = compileProgram("0[01]{8}$", 1 << 20);
        Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);

        Dfa.CacheSnapshot cold = dfa.cacheSnapshot();
        assertThat(cold.stateCount()).isZero();
        assertThat(cold.cacheEntries()).isZero();
        assertThat(cold.populatedStateData()).isZero();
        assertThat(cold.populatedStateReferences()).isZero();
        assertThat(cold.populatedStartStates()).isZero();
        assertThat(dfa.retainedStateMemory())
                .isEqualTo(dfa.stateMemorySnapshot().totalBytes());
        assertThat(cold.stateBudget() - cold.availableStateMemory())
                .isEqualTo(dfa.stateMemorySnapshot().totalBytes());

        Slice input = Slices.utf8Slice("0010101010");
        Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true);
        Dfa.CacheSnapshot warm = dfa.cacheSnapshot();
        assertThat(warm.stateCount()).isPositive();
        assertThat(warm.cacheEntries()).isEqualTo(warm.stateCount());
        assertThat(warm.populatedStateData()).isEqualTo(warm.stateCount());
        assertThat(warm.populatedStateReferences()).isEqualTo(warm.stateCount());
        assertThat(warm.availableStateMemory()).isLessThan(warm.stateBudget());
        assertThat(dfa.retainedStateMemory())
                .isEqualTo(dfa.stateMemorySnapshot().totalBytes());
        assertThat(warm.stateBudget() - warm.availableStateMemory())
                .isGreaterThanOrEqualTo(dfa.stateMemorySnapshot().totalBytes());

        dfa.resetCacheExternal();
        Dfa.CacheSnapshot reset = dfa.cacheSnapshot();
        assertThat(reset.stateCount()).isZero();
        assertThat(reset.cacheEntries()).isZero();
        assertThat(reset.populatedStateData()).isZero();
        assertThat(reset.populatedStateReferences()).isZero();
        assertThat(reset.populatedStartStates()).isZero();
        assertThat(reset.pairedRows()).isZero();
        assertThat(reset.pairedTransitionMemory()).isZero();
        assertThat(dfa.retainedStateMemory())
                .isEqualTo(dfa.stateMemorySnapshot().totalBytes());
        assertThat(reset.stateBudget() - reset.availableStateMemory())
                .isEqualTo(dfa.stateMemorySnapshot().totalBytes() + reset.fixedDistanceByteCandidateMemory());
        assertThat(reset.transitionCapacity()).isEqualTo(cold.transitionCapacity());
        assertThat(reset.stateCapacity()).isEqualTo(cold.stateCapacity());
        assertThat(reset.stateReferenceCapacity()).isEqualTo(cold.stateReferenceCapacity());

        Dfa.search(program, input, false, Prog.MatchKind.FIRST_MATCH, true);
        Dfa.CacheSnapshot rebuilt = dfa.cacheSnapshot();
        assertThat(rebuilt.transitionCapacity()).isEqualTo(cold.transitionCapacity());
        assertThat(rebuilt.stateCapacity()).isEqualTo(cold.stateCapacity());
        assertThat(rebuilt.stateReferenceCapacity()).isEqualTo(cold.stateReferenceCapacity());
        assertThat(rebuilt.stateCount()).isEqualTo(warm.stateCount());
        assertThat(rebuilt.cacheEntries()).isEqualTo(warm.cacheEntries());
    }

    @Test
    public void testResetLeavesSupersededTablesUnchanged()
    {
        // Up to 2^7 states, more than the initial capacity, so the rebuilt cache grows.
        Prog program = compileProgram("1[01]{6}$", 1 << 20);
        Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);
        Slice input = bitText(4_096);
        Dfa.search(program, input, false, Prog.MatchKind.LONGEST_MATCH, true);

        // A search that began before the reset reads these tables until it ends.
        Dfa.CacheTables superseded = dfa.cacheTablesForTesting();
        int[] transitions = superseded.transitions.clone();
        Dfa.StateData[] stateData = superseded.stateData.clone();
        Object[][] rows = new Object[superseded.stateRefArrays.length][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = superseded.stateRefArrays[i] == null ? null : superseded.stateRefArrays[i].clone();
        }
        long[] selfLoopExitByteSets = superseded.selfLoopExitByteSets.clone();
        List<Dfa.StateData> startStates = startStates(superseded);

        dfa.resetCacheExternal();
        Dfa.CacheTables reset = dfa.cacheTablesForTesting();
        Dfa.search(program, input, false, Prog.MatchKind.LONGEST_MATCH, true);

        assertThat(superseded.transitions).isEqualTo(transitions);
        assertThat(superseded.stateData).isEqualTo(stateData);
        for (int i = 0; i < rows.length; i++) {
            // Rows refer to themselves through self-loops, so compare their slots by identity.
            assertThat(Arrays.equals(superseded.stateRefArrays[i], rows[i])).as("state reference row %s", i).isTrue();
        }
        assertThat(superseded.selfLoopExitByteSets).isEqualTo(selfLoopExitByteSets);
        assertThat(startStates(superseded)).isEqualTo(startStates);
        assertThat(reset).isNotSameAs(superseded);
        assertThat(dfa.cacheTablesForTesting().transitions.length).isGreaterThan(reset.transitions.length);
    }

    @Test
    public void testReaderOfGrownOutTablesRetries()
    {
        Prog program = compileProgram("1[01]{6}$", 1 << 20);
        Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);
        Dfa.search(program, Slices.utf8Slice("1"), false, Prog.MatchKind.LONGEST_MATCH, true);
        // A search that began now reads these tables until it ends.
        Dfa.CacheTables stale = dfa.cacheTablesForTesting();

        // Growth publishes larger arrays; the stale tables share their start caches and rows.
        Dfa.search(program, bitText(4_096), false, Prog.MatchKind.LONGEST_MATCH, true);
        Slice anchoredText = Slices.utf8Slice("1010101");
        Dfa.search(program, anchoredText, true, Prog.MatchKind.LONGEST_MATCH, true);
        assertThat(dfa.cacheTablesForTesting().transitions.length).isGreaterThan(stale.transitions.length);

        // A start state cached after the growth, in a start cache the stale tables share. The
        // indexes select the forward, anchored slot.
        Dfa.StateData anchoredStart = stale.startBeginText[1][1];
        assertThat(anchoredStart.offset()).isGreaterThanOrEqualTo(stale.transitions.length);
        int textBegin = anchoredText.byteArrayOffset();
        assertThatThrownBy(() -> dfa.analyzeStart(stale, anchoredText, textBegin, textBegin + anchoredText.length(), true, true))
                .isInstanceOf(Dfa.RetrySearchException.class);

        // A shared row of the stale tables leads to a row allocated after the growth, whose state
        // lies beyond them.
        Object[] grownRow = grownRowReachableFrom(stale, dfa.nextSize);
        assertThatThrownBy(() -> Dfa.rowStateOffset(grownRow, dfa.nextSize, stale.transitions))
                .isInstanceOf(Dfa.RetrySearchException.class);
        int grownOffset = ((Dfa.StateData) grownRow[dfa.nextSize]).offset();
        assertThatThrownBy(() -> dfa.stateReferences(stale, grownOffset))
                .isInstanceOf(Dfa.RetrySearchException.class);
    }

    @Test
    public void testLargeProgramStateTablesStartSmallAndGrow()
    {
        // 2,000 instructions the bit text never reaches make a program of 2,017 instructions, and
        // a 16 MiB budget holds a row for each. The bit pattern's states take its DFA's tables past
        // 256 rows, past one row per instruction, and on.
        Prog program = compileProgram("1[01]{11}$|" + "z".repeat(2_000), 16 << 20);
        Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);
        int initialCapacity = Dfa.DfaInstance.MAXIMUM_INITIAL_STATE_CAPACITY;
        assertThat(program.size()).isGreaterThan(4 * initialCapacity);
        assertThat(dfa.cacheSnapshot().stateCapacity()).isEqualTo(initialCapacity);
        // The tables a program of this size prepaid alone exceed all the DFA retains.
        assertThat(dfa.retainedStateMemory()).isLessThan(stateTableMemory(program.size(), dfa.nextSize));

        // Growth doubles, stops once at one row per instruction, then doubles on.
        List<Integer> capacities = new ArrayList<>(List.of(initialCapacity));
        Slice bits = bitText(200_000);
        for (int offset = 0; offset < bits.length(); offset += 64) {
            Dfa.search(program, bits.slice(offset, 64), false, Prog.MatchKind.LONGEST_MATCH, true);
            int capacity = dfa.cacheSnapshot().stateCapacity();
            if (capacity != capacities.getLast()) {
                capacities.add(capacity);
            }
        }
        assertThat(dfa.cacheSnapshot().stateCount()).isGreaterThan(2 * program.size());
        assertThat(dfa.cacheSnapshot().resetCount()).isZero();
        assertThat(capacities).containsExactly(initialCapacity, 2 * initialCapacity, 4 * initialCapacity, program.size(), 2 * program.size(), 4 * program.size());

        dfa.resetCacheExternal();
        assertThat(dfa.cacheSnapshot().stateCapacity()).isEqualTo(initialCapacity);
    }

    @Test
    public void testStateTableGrowthStopsAtRowsTheBudgetFills()
    {
        // The bit pattern's 1,025 states need 1,026 rows, row zero included, more than the 1,024
        // rows its tables double to, so a doubling past them leaves about half the rows empty.
        String pattern = "1[01]{9}$";
        Slice bits = bitText(20_000);
        Prog referenceProgram = compileProgram(pattern, 64 << 20);
        referenceProgram.setDfaMemory(2 * (16 << 20));
        searchBits(referenceProgram, bits);
        Dfa.DfaInstance warm = referenceProgram.getCachedDfa(LONGEST_MATCH);
        Dfa.CacheSnapshot warmCache = warm.cacheSnapshot();
        assertThat(warmCache.stateCount()).isEqualTo(1_025);
        assertThat(warmCache.stateCapacity()).isEqualTo(2_048);
        int rowsInUse = warmCache.stateCount() + 1;
        long doubledTablesCharge = warm.stateBudget() - warm.availableStateMemory();
        long workingSetCharge = workingSetCharge(warm);
        long fixedOverhead = (16 << 20) - warm.stateBudget();

        // Budgets a little above the working set's charge, with tables of one row per state, but
        // too small for the doubled tables, hold every state. The tables grow only by rows the
        // budget can fill, so each row left empty has at least a reference row of budget behind
        // it.
        for (int marginPercent : List.of(6, 12)) {
            long stateBudget = workingSetCharge * (100 + marginPercent) / 100;
            assertThat(stateBudget).isLessThan(doubledTablesCharge);
            Prog program = compileProgram(pattern, 64 << 20);
            program.setDfaMemory(2 * (stateBudget + fixedOverhead));
            searchBits(program, bits);
            searchBits(program, bits);
            Dfa.DfaInstance dfa = program.getCachedDfa(LONGEST_MATCH);
            assertThat(dfa.stateBudget()).isEqualTo(stateBudget);
            Dfa.CacheSnapshot cache = dfa.cacheSnapshot();
            assertThat(cache.resetCount()).as("resets %s%% above the working set's charge", marginPercent).isZero();
            assertThat(cache.stateCount()).isEqualTo(warmCache.stateCount());
            assertEmptyRowsCanBeFilled(dfa, marginPercent + "% above the working set's charge");
        }

        // A budget above the working set's charge by 16 times its charge per row in use funds
        // fewer than the 32 rows, a thirty-second of 1,024, that a growth must add, so the DFA
        // resets rather than grow the tables by a few rows, which the next few states would
        // outgrow.
        long floorBudget = workingSetCharge + 16 * workingSetCharge / rowsInUse;
        Prog floorProgram = compileProgram(pattern, 64 << 20);
        floorProgram.setDfaMemory(2 * (floorBudget + fixedOverhead));
        searchBits(floorProgram, bits);
        Dfa.DfaInstance floorDfa = floorProgram.getCachedDfa(LONGEST_MATCH);
        assertThat(floorDfa.stateBudget()).isEqualTo(floorBudget);
        assertThat(floorDfa.cacheSnapshot().resetCount()).as("resets 16 rows' charge above the working set").isPositive();
    }

    @Test
    public void testStateTableGrowthLeavesStateHashTableDoubling()
    {
        // The state hash table doubles whole once an insert would fill more than three-quarters of
        // it. Over 1,535 bytes the pattern reaches 1,536 states, which fill a table of 2,048
        // entries to that line. The budget is the working set's charge, with tables of one row per
        // state, plus half the doubling, so the tables' growth from 1,292 rows stops at the line:
        // a row past it would need the whole doubling beside its state. The budget comes from the
        // reference DFA's charges, so the growth stops there in either object layout. A growth
        // priced without the doubling, with compressed object pointers, reaches 1,565 rows for the
        // 1,536 states and leaves 6,736 bytes against a 16,384-byte doubling; this check catches
        // it.
        String pattern = "[a-q][^u-z]{80}x";
        Slice text = unmatchedStateExplosionText(1_535);
        Prog referenceProgram = compileProgram(pattern, 64 << 20);
        referenceProgram.setDfaMemory(2 * (16 << 20));
        Dfa.search(referenceProgram, text, false, Prog.MatchKind.FIRST_MATCH, true);
        Dfa.DfaInstance warm = referenceProgram.getCachedDfa(FIRST_MATCH);
        int cacheTableCapacity = warm.stateMemorySnapshot().cacheTableCapacity();
        assertThat(warm.cacheSnapshot().stateCount()).isEqualTo(1_536);
        assertThat(cacheTableCapacity).isEqualTo(2_048);
        long stateBudget = workingSetCharge(warm) + cacheTableDoublingMemory(cacheTableCapacity) / 2;
        long fixedOverhead = (16 << 20) - warm.stateBudget();

        Prog program = compileProgram(pattern, 64 << 20);
        program.setDfaMemory(2 * (stateBudget + fixedOverhead));
        Dfa.search(program, text, false, Prog.MatchKind.FIRST_MATCH, true);
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        assertThat(dfa.stateBudget()).isEqualTo(stateBudget);
        Dfa.CacheSnapshot cache = dfa.cacheSnapshot();
        assertThat(cache.resetCount()).isZero();
        assertThat(cache.stateCount()).isEqualTo(1_536);
        assertThat(cache.stateCapacity())
                .as("rows, row zero included, of tables grown to the state hash table's line")
                .isEqualTo(cacheTableCapacity * 3 / 4 + 1);
    }

    // What the DFA charges, with its tables cut to one row per state, row zero included.
    private static long workingSetCharge(Dfa.DfaInstance dfa)
    {
        Dfa.CacheSnapshot cache = dfa.cacheSnapshot();
        return dfa.stateBudget() - dfa.availableStateMemory() - stateTableMemory(cache.stateCapacity(), dfa.nextSize) + stateTableMemory(cache.stateCount() + 1, dfa.nextSize);
    }

    // The bytes a state hash table of `cacheTableCapacity` entries adds when it doubles.
    private static long cacheTableDoublingMemory(int cacheTableCapacity)
    {
        return sizeOfObjectArray(4 * cacheTableCapacity) - sizeOfObjectArray(2 * cacheTableCapacity);
    }

    // The budget left behind the rows the tables hold past the states: at least a reference row
    // for each, a pointer row for each the DFA's pointer table does not hold, and the state hash
    // table's doubling once when their states would fill it past three-quarters. A row's full
    // price, its table bytes and an average state, is not checked: a bound at it falls inside the
    // sizing's slack in some object layouts.
    private static void assertEmptyRowsCanBeFilled(Dfa.DfaInstance dfa, String description)
    {
        Dfa.CacheSnapshot cache = dfa.cacheSnapshot();
        long emptyRows = (long) cache.stateCapacity() - (cache.stateCount() + 1);
        long pointerRowMemory = 0;
        if (dfa.absolutePointerTransitionsAvailable()) {
            long pointerRowBytes = (long) dfa.nextSize * Long.BYTES;
            long pointerRows = dfa.absolutePointerTransitionSegment().byteSize() / pointerRowBytes;
            pointerRowMemory = Math.max(0, cache.stateCapacity() - pointerRows) * pointerRowBytes;
        }
        int cacheTableCapacity = dfa.stateMemorySnapshot().cacheTableCapacity();
        long cacheGrowthMemory = 0;
        if (cache.stateCount() + emptyRows > cacheTableCapacity * 3L / 4) {
            cacheGrowthMemory = cacheTableDoublingMemory(cacheTableCapacity);
        }
        assertThat(emptyRows * sizeOfObjectArray(dfa.nextSize + 1) + pointerRowMemory + cacheGrowthMemory)
                .as("budget behind the empty rows, at least a reference row each, %s", description)
                .isLessThanOrEqualTo(cache.availableStateMemory());
    }

    private static void searchBits(Prog program, Slice bits)
    {
        for (int offset = 0; offset < bits.length(); offset += 64) {
            Dfa.search(program, bits.slice(offset, Math.min(64, bits.length() - offset)), false, Prog.MatchKind.LONGEST_MATCH, true);
        }
    }

    @Disabled("Sweep leg m-pointer-limit-1: sizes the pointer table a later short search requests")
    @Test
    public void testPointerTableLeavesRoomForStateTableGrowth()
    {
        // A program of more than 256 instructions, so the state tables start below one row per
        // instruction and grow as the DFA's states arrive.
        String pattern = "[ab]*a[ab]{6}\\pL";
        List<Slice> texts = abTexts();
        Prog referenceProgram = compileProgram(pattern, 64 << 20);
        referenceProgram.setDfaMemory(2 * (16 << 20));
        searchAll(referenceProgram, texts);
        searchAll(referenceProgram, texts);
        Dfa.DfaInstance warm = referenceProgram.getCachedDfa(FIRST_MATCH);
        assertThat(referenceProgram.size()).isGreaterThan(Dfa.DfaInstance.MAXIMUM_INITIAL_STATE_CAPACITY);
        assertThat(warm.cacheSnapshot().stateCount()).isGreaterThan(Dfa.DfaInstance.MAXIMUM_INITIAL_STATE_CAPACITY);
        assertThat(warm.absolutePointerTransitionsAvailable()).isTrue();
        long javaStateMemory = warm.stateBudget() - warm.availableStateMemory() - warm.absolutePointerTransitionMemory() +
                stateTableMemory(referenceProgram.size(), warm.nextSize) -
                stateTableMemory(warm.cacheSnapshot().stateCapacity(), warm.nextSize);
        long pointerRowMemory = (warm.cacheSnapshot().stateCount() + 1L) * warm.nextSize * Long.BYTES;
        long fixedOverhead = (16 << 20) - warm.stateBudget();

        // A budget that holds four fifths of the warm DFA's Java state, counted with tables of one
        // row per instruction, and none of its pointer rows, so it cannot fund a state per
        // instruction beside a pointer table, and the budget, not the table's doubling, sizes the
        // table in either object layout. The pointer table is first sized while the state tables
        // are small. It must leave the budget the tables' next growth, to at least a row per
        // pointer row, and a reference row behind each pointer row past the rows in use, but less
        // than the tables' doubling would charge.
        Prog tight = compileProgram(pattern, 64 << 20);
        tight.setDfaMemory(2 * (javaStateMemory * 4 / 5 + fixedOverhead));
        Dfa.DfaInstance tightDfa = null;
        for (Slice text : texts) {
            Dfa.search(tight, text, false, Prog.MatchKind.FIRST_MATCH, true);
            tightDfa = tight.getCachedDfa(FIRST_MATCH);
            if (tightDfa.absolutePointerTransitionMemory() > 0) {
                break;
            }
        }
        assertThat(tightDfa.absolutePointerTransitionMemory()).isPositive();
        Dfa.CacheSnapshot firstPointerTable = tightDfa.cacheSnapshot();
        int doubledStateCapacity = 2 * firstPointerTable.stateCapacity();
        assertThat(doubledStateCapacity).isLessThan(tightDfa.programStateCapacity());
        long pointerRows = tightDfa.absolutePointerTransitionSegment().byteSize() / ((long) tightDfa.nextSize * Long.BYTES);
        long rowsInUse = firstPointerTable.stateCount() + 1L;
        assertThat(pointerRows).isLessThan(2 * rowsInUse);
        long firstTableMemory = stateTableMemory(firstPointerTable.stateCapacity(), tightDfa.nextSize);
        long addedReferenceRowMemory = (pointerRows - rowsInUse) * sizeOfObjectArray(tightDfa.nextSize + 1);
        assertThat(tightDfa.availableStateMemory())
                .isGreaterThanOrEqualTo(stateTableMemory(toIntExact(pointerRows), tightDfa.nextSize) - firstTableMemory + addedReferenceRowMemory)
                .isLessThan(stateTableMemory(doubledStateCapacity, tightDfa.nextSize) - firstTableMemory + addedReferenceRowMemory);

        // A budget a little above what the warm DFA would hold with tables that started at one row
        // per instruction, its Java state and one pointer row per state, holds them all: the
        // pointer table must not take the budget the tables' growth needs, which would make the DFA
        // reset on every pass, or give up its pointer table. The tables grow beside the pointer
        // table only by rows the budget left can fill, each with a pointer row where the table
        // holds none.
        for (int marginPercent = 2; marginPercent <= 6; marginPercent += 2) {
            long stateBudget = (javaStateMemory + pointerRowMemory) * (100 + marginPercent) / 100;
            Prog program = compileProgram(pattern, 64 << 20);
            program.setDfaMemory(2 * (stateBudget + fixedOverhead));
            searchAll(program, texts);
            searchAll(program, texts);
            Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
            assertThat(dfa.stateBudget()).isEqualTo(stateBudget);
            int warmResets = dfa.cacheSnapshot().resetCount();
            searchAll(program, texts);
            searchAll(program, texts);
            assertThat(dfa.cacheSnapshot().resetCount())
                    .as("resets after warming, %s%% above the warm DFA's memory", marginPercent)
                    .isEqualTo(warmResets);
            assertThat(dfa.absolutePointerTransitionsAvailable())
                    .as("pointer table %s%% above the warm DFA's memory", marginPercent)
                    .isTrue();
            assertEmptyRowsCanBeFilled(dfa, marginPercent + "% above the warm DFA's memory, beside the pointer table");
        }
    }

    // The state tables' charge at `stateCapacity` rows: transitions, state data, state
    // references, and self-loop exit byte sets.
    private static long stateTableMemory(int stateCapacity, int nextSize)
    {
        return sizeOfIntArray(stateCapacity * nextSize) + 2 * sizeOfObjectArray(stateCapacity) + sizeOfLongArray(stateCapacity);
    }

    private static void searchAll(Prog program, List<Slice> texts)
    {
        for (Slice text : texts) {
            assertThat(Dfa.search(program, text, false, Prog.MatchKind.FIRST_MATCH, true)).isNotEqualTo(Dfa.SEARCH_FAILED);
        }
    }

    // Pseudo-random texts, mostly a and b, short ones and a few long enough to request a pointer
    // table.
    private static List<Slice> abTexts()
    {
        Random random = new Random(99);
        List<Slice> texts = new ArrayList<>();
        int[] lengths = new int[60];
        for (int i = 0; i < lengths.length; i++) {
            lengths[i] = i < 40 ? 20 + random.nextInt(200) : (i < 56 ? 3000 + random.nextInt(3000) : 150_000);
        }
        for (int length : lengths) {
            byte[] bytes = new byte[length];
            for (int j = 0; j < length; j++) {
                int value = random.nextInt(100);
                bytes[j] = (byte) (value < 48 ? 'a' : value < 96 ? 'b' : value < 98 ? 'c' : 'x');
            }
            texts.add(Slices.wrappedBuffer(bytes));
        }
        return texts;
    }

    // `length` pseudo-random bits. 4,096 reach every state of `1[01]{6}$`; searched 64 at a time,
    // 20,000 reach every state of `1[01]{9}$`, and 200,000 every state of `1[01]{11}$`.
    private static Slice bitText(int length)
    {
        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < length; i++) {
            bits.append(Integer.bitCount(i * 0x9E37) & 1);
        }
        return Slices.utf8Slice(bits.toString());
    }

    // `length` pseudo-random bytes of the letters a to t, digits, spaces, newlines, and
    // punctuation. Searched for `[a-q][^u-z]{80}x`, which none of it matches, each byte reaches a
    // new state.
    private static Slice unmatchedStateExplosionText(int length)
    {
        Random random = new Random(42);
        byte[] alphabet = "abcdefghijklmnopqrst \n,.;0123456789".getBytes(US_ASCII);
        byte[] input = new byte[length];
        for (int index = 0; index < input.length; index++) {
            input[index] = alphabet[random.nextInt(alphabet.length)];
        }
        return Slices.wrappedBuffer(input);
    }

    private static Object[] grownRowReachableFrom(Dfa.CacheTables tables, int nextSize)
    {
        for (Object[] row : tables.stateRefArrays) {
            if (row == null) {
                continue;
            }
            for (int byteClass = 0; byteClass < nextSize; byteClass++) {
                if (row[byteClass] instanceof Object[] next && ((Dfa.StateData) next[nextSize]).offset() >= tables.transitions.length) {
                    return next;
                }
            }
        }
        throw new AssertionError("no row of the grown cache is reachable from the stale tables");
    }

    private static List<Dfa.StateData> startStates(Dfa.CacheTables tables)
    {
        return Stream.of(tables.startBeginText, tables.startBeginLine, tables.startAfterWord, tables.startAfterNonWord)
                .flatMap(Arrays::stream)
                .flatMap(Arrays::stream)
                .toList();
    }

    @Test
    public void testTrinoStartStateCacheIsCharged()
    {
        assertTrinoStartStateCacheIsCharged(false, true);
        assertTrinoStartStateCacheIsCharged(true, false);
    }

    private static void assertTrinoStartStateCacheIsCharged(boolean reversed, boolean hasTrinoStartStateCache)
    {
        long memoryBudget = 1 << 20;
        Prog re2Program = compileProgram("(?m)^[a-z]+", reversed, memoryBudget);
        Prog trinoProgram = compileTrinoProgram("(?m)^[a-z]+", reversed, memoryBudget);
        Dfa.DfaInstance re2Dfa = re2Program.getCachedDfa(LONGEST_MATCH);
        Dfa.DfaInstance trinoDfa = trinoProgram.getCachedDfa(LONGEST_MATCH);
        long expectedTrinoCacheBytes = hasTrinoStartStateCache ? 3 * sizeOfObjectArray(2) : 0;

        assertThat(trinoDfa.retainedStateMemory() - re2Dfa.retainedStateMemory())
                .isEqualTo(expectedTrinoCacheBytes);
        assertThat(trinoDfa.stateMemorySnapshot().totalBytes() - re2Dfa.stateMemorySnapshot().totalBytes())
                .isEqualTo(expectedTrinoCacheBytes);
        assertThat(trinoDfa.stateMemorySnapshot().optionalStartStateCacheBytes()).isEqualTo(expectedTrinoCacheBytes);

        Dfa.CacheSnapshot cold = trinoDfa.cacheSnapshot();
        assertThat(cold.stateBudget() - cold.availableStateMemory())
                .isEqualTo(trinoDfa.stateMemorySnapshot().totalBytes());

        assertThat(Dfa.search(trinoProgram, Slices.utf8Slice("abc"), true, Prog.MatchKind.LONGEST_MATCH, true))
                .isNotEqualTo(Dfa.SEARCH_FAILED);
        trinoDfa.resetCacheExternal();

        assertThat(trinoDfa.retainedStateMemory())
                .isEqualTo(trinoDfa.stateMemorySnapshot().totalBytes());
        // A pointer table the search requested outlives the reset and keeps its charge.
        assertThat(trinoDfa.cacheSnapshot().stateBudget() - trinoDfa.cacheSnapshot().availableStateMemory())
                .isEqualTo(trinoDfa.stateMemorySnapshot().totalBytes() + trinoDfa.absolutePointerTransitionMemory());
    }

    @Test
    public void testRustStartStateCachesAreCharged()
    {
        assertRustStartStateCachesAreCharged(false);
        assertRustStartStateCachesAreCharged(true);
    }

    private static void assertRustStartStateCachesAreCharged(boolean reversed)
    {
        long memoryBudget = 1 << 20;
        Prog rustProgram = compileRustProgram("(?mR)^\\b[a-z]+", reversed, memoryBudget);
        Dfa.DfaInstance rustDfa = rustProgram.getCachedDfa(LONGEST_MATCH);
        // The caches for a start after CR and after a byte outside ASCII.
        long expectedRustCacheBytes = 2 * 3 * sizeOfObjectArray(2);

        assertThat(rustDfa.retainedStateMemory())
                .isEqualTo(rustDfa.stateMemorySnapshot().totalBytes());
        assertThat(rustDfa.stateMemorySnapshot().optionalStartStateCacheBytes())
                .isEqualTo(expectedRustCacheBytes);

        Dfa.CacheSnapshot cold = rustDfa.cacheSnapshot();
        assertThat(cold.stateBudget() - cold.availableStateMemory())
                .isEqualTo(rustDfa.stateMemorySnapshot().totalBytes());

        // Each range has CR or é on both sides, so a forward and a reverse search start from the
        // same cache. The forward search after é, a word character, starts from a cache the search
        // creates and charges, and a reset frees it.
        assertThat(Dfa.search(rustProgram, Slices.utf8Slice("\rabc\r"), 1, 4, true, Prog.MatchKind.LONGEST_MATCH, true))
                .isNotEqualTo(Dfa.SEARCH_FAILED);
        assertThat(Dfa.search(rustProgram, Slices.utf8Slice("éabcé"), 2, 5, true, Prog.MatchKind.LONGEST_MATCH, true))
                .isNotEqualTo(Dfa.SEARCH_FAILED);
        assertThat(rustDfa.cacheSnapshot().populatedStartStates()).isEqualTo(2);
        // The decoded word caches the searches add count in the retained state memory, which
        // measures a DFA without a budget, as well as in the charge, beside the pointer table the
        // first short search requested.
        assertThat(rustDfa.retainedStateMemory())
                .isEqualTo(rustDfa.stateMemorySnapshot().totalBytes());
        assertThat(rustDfa.cacheSnapshot().stateBudget() - rustDfa.cacheSnapshot().availableStateMemory())
                .isEqualTo(rustDfa.stateMemorySnapshot().totalBytes() + rustDfa.absolutePointerTransitionMemory());
        rustDfa.resetCacheExternal();

        assertThat(rustDfa.cacheSnapshot().populatedStartStates()).isZero();
        assertThat(rustDfa.retainedStateMemory())
                .isEqualTo(rustDfa.stateMemorySnapshot().totalBytes());
        // A pointer table the search requested outlives the reset and keeps its charge.
        assertThat(rustDfa.cacheSnapshot().stateBudget() - rustDfa.cacheSnapshot().availableStateMemory())
                .isEqualTo(rustDfa.stateMemorySnapshot().totalBytes() + rustDfa.absolutePointerTransitionMemory());
    }

    @Test
    public void testStateIndexInvertsStateOffset()
    {
        // A row holds one entry per byte class, 1 to 256 classes, plus the end-of-text entry.
        for (int nextSize = 2; nextSize <= 257; nextSize++) {
            int shift = Integer.numberOfTrailingZeros(nextSize);
            int multiplier = Dfa.DfaInstance.stateIndexMultiplier(nextSize);
            int maximumStateIndex = Integer.MAX_VALUE / nextSize;
            for (int stateIndex = 0; stateIndex < maximumStateIndex; stateIndex += Math.max(1, stateIndex / 64)) {
                assertThat(Dfa.DfaInstance.stateIndex(stateIndex * nextSize, shift, multiplier))
                        .as("row width %s", nextSize)
                        .isEqualTo(stateIndex);
            }
            assertThat(Dfa.DfaInstance.stateIndex(maximumStateIndex * nextSize, shift, multiplier))
                    .as("row width %s", nextSize)
                    .isEqualTo(maximumStateIndex);
        }
    }

    private static Dfa.DfaInstance compileDfa(String expression, long memoryBudget)
    {
        return compileProgram(expression, memoryBudget).getCachedDfa(LONGEST_MATCH);
    }

    private static Prog compileProgram(String expression, long memoryBudget)
    {
        return compileProgram(expression, false, memoryBudget);
    }

    private static Prog compileProgram(String expression, boolean reversed, long memoryBudget)
    {
        return Compiler.compile(
                RegexpParser.parse(Slices.utf8Slice(expression), Regexp.LIKE_PERL).regexp(),
                reversed,
                memoryBudget);
    }

    private static Prog compileTrinoProgram(String expression, boolean reversed, long memoryBudget)
    {
        ParseResult parsed = TrinoRegexpParser.parse(Slices.utf8Slice(expression), Regexp.LIKE_PERL);
        Regexp regexp = Simplifier.simplify(parsed.regexp(), parsed.caseFolding());
        return Compiler.compileNormalized(regexp, reversed, memoryBudget, Compiler.Dialect.TRINO, parsed.caseFolding());
    }

    private static Prog compileRustProgram(String expression, boolean reversed, long memoryBudget)
    {
        RustRegexp.Options options = RustRegexp.Options.defaults();
        ParseResult parsed = RustRegexpParser.parse(Slices.utf8Slice(expression), options.syntaxFlags(), options.nestLimit());
        Regexp regexp = Simplifier.simplify(parsed.regexp(), parsed.caseFolding());
        return Compiler.compileNormalized(regexp, reversed, memoryBudget, Compiler.Dialect.RE2, parsed.caseFolding());
    }
}
