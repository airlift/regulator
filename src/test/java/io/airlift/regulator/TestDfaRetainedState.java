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

        // A budget that holds the warm DFA's Java state but none of its pointer rows. The pointer
        // table is first sized while the tables are small, and it must leave the budget their
        // growth to one row per instruction, beside the reference row of a state behind each
        // pointer row it adds.
        Prog tight = compileProgram(pattern, 64 << 20);
        tight.setDfaMemory(2 * (javaStateMemory + fixedOverhead));
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
        assertThat(firstPointerTable.stateCapacity()).isLessThan(tightDfa.programStateCapacity());
        long addedPointerRows = tightDfa.absolutePointerTransitionMemory() / ((long) tightDfa.nextSize * Long.BYTES) - (firstPointerTable.stateCount() + 1L);
        assertThat(tightDfa.availableStateMemory()).isGreaterThanOrEqualTo(
                stateTableMemory(tightDfa.programStateCapacity(), tightDfa.nextSize) -
                        stateTableMemory(firstPointerTable.stateCapacity(), tightDfa.nextSize) +
                        addedPointerRows * sizeOfObjectArray(tightDfa.nextSize + 1));

        // A budget a little above what the warm DFA would hold with tables that started at one row
        // per instruction, its Java state and one pointer row per state, holds them all: the
        // pointer table must not take the budget the tables' growth needs, which would make the DFA
        // reset on every pass, or give up its pointer table.
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

    // `length` pseudo-random bits. 4,096 reach every state of `1[01]{6}$`, and 200,000, searched
    // 64 at a time, every state of `1[01]{11}$`.
    private static Slice bitText(int length)
    {
        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < length; i++) {
            bits.append(Integer.bitCount(i * 0x9E37) & 1);
        }
        return Slices.utf8Slice(bits.toString());
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
        assertThat(trinoDfa.cacheSnapshot().stateBudget() - trinoDfa.cacheSnapshot().availableStateMemory())
                .isEqualTo(trinoDfa.stateMemorySnapshot().totalBytes());
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
        // same cache. The search after é quits on its first byte, after caching its start state.
        assertThat(Dfa.search(rustProgram, Slices.utf8Slice("\rabc\r"), 1, 4, true, Prog.MatchKind.LONGEST_MATCH, true))
                .isNotEqualTo(Dfa.SEARCH_FAILED);
        assertThat(Dfa.search(rustProgram, Slices.utf8Slice("éabcé"), 2, 5, true, Prog.MatchKind.LONGEST_MATCH, true))
                .isEqualTo(Dfa.SEARCH_FAILED);
        assertThat(rustDfa.cacheSnapshot().populatedStartStates()).isEqualTo(2);
        rustDfa.resetCacheExternal();

        assertThat(rustDfa.cacheSnapshot().populatedStartStates()).isZero();
        assertThat(rustDfa.retainedStateMemory())
                .isEqualTo(rustDfa.stateMemorySnapshot().totalBytes());
        assertThat(rustDfa.cacheSnapshot().stateBudget() - rustDfa.cacheSnapshot().availableStateMemory())
                .isEqualTo(rustDfa.stateMemorySnapshot().totalBytes());
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
