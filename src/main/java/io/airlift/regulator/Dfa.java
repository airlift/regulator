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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.airlift.slice.Slice;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntPredicate;

import static io.airlift.slice.SizeOf.instanceSize;
import static io.airlift.slice.SizeOf.sizeOfIntArray;
import static io.airlift.slice.SizeOf.sizeOfLongArray;
import static io.airlift.slice.SizeOf.sizeOfObjectArray;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/// DFA-based regex search implementation (ported from upstream `re2/dfa.cc`).
///
/// Ordinary transitions use compact object-reference rows with an integer
/// sidecar for abnormal transitions. Eligible long forward searches can use
/// either a bounded depth-two table or an optional pointer sidecar, while
/// object rows remain authoritative for every fallback.
final class Dfa
{
    // Whether paired rows live in one flat int table of pre-scaled byte offsets, read through a heap
    // memory segment, rather than as an object row per state. Only on aarch64: the flat step ran
    // 9-21% faster on r9g (Graviton 4) paired rows, while on r8i C2 carried the row offset through
    // a vector register and the same rows ran 3-72% slower. Only those two hosts were measured:
    // Graviton 2 and 3, Apple, and other aarch64 hosts get the flat form by `os.arch` without
    // measurement, and every x86 host keeps the object rows, as do other platforms. "Bounded Paired
    // DFA Transitions" in REGULATOR_DECISIONS.md records the measurements. The
    // `io.airlift.regulator.dfa.flat-paired-rows` property overrides the form for a run; it is a
    // testing and diagnostic switch, not supported configuration. Tests run both forms on every
    // host by forcing it in child JVMs. C2 folds this constant, so each search compiles one form.
    static final boolean FLAT_PAIRED_ROWS = selectFlatPairedRows();
    // The flat table's charge counts four-byte slots and no row header, where the object rows'
    // charge counts eight-byte references and a header for each row, so the flat caps are half the
    // object caps and still hold complete tables of more states. The DFA's free state budget, which
    // limits the table when it is smaller, is not halved, so under a tight budget the flat table
    // fits about twice the rows.
    static final long MAX_PAIRED_TRANSITION_MEMORY = FLAT_PAIRED_ROWS ? 32 * 1024 : 64 * 1024;
    private static final long MAX_PARTIAL_PAIRED_TRANSITION_MEMORY = FLAT_PAIRED_ROWS ? 8 * 1024 : 16 * 1024;
    private static final int CONSERVATIVE_REFERENCE_BYTES = Long.BYTES;
    private static final int MIN_PAIRED_SEARCH_BYTES = 256;
    private static final int MIN_SELECTIVE_SCAN_SEARCH_BYTES = 4 * 1024;
    static final int MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES = 1024;
    static final int MIN_SMALL_BYTE_SET_PRODUCTIVITY_CHECK_BYTES = 4 * 1024;
    static final int MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES = 64 * 1024;
    static final int BYTE_SCAN_PRODUCTIVITY_RECHECK_BYTES = MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES;
    static final int BYTE_SCAN_SAMPLE_SIZE = 64;
    private static final int BYTE_SCAN_SAMPLE_WINDOWS = 4;
    static final int BYTE_SCAN_SAMPLE_CANDIDATE_LIMIT = 8;
    private static final int SMALL_BYTE_SET_SCAN_SAMPLE_CANDIDATE_LIMIT = 4;
    // A count of every match settles a borderline start sample, from 4 to 7 candidates in 64 bytes,
    // with 256 bytes in 16 windows at the same density.
    private static final int COUNT_START_BORDERLINE_CANDIDATE_LIMIT = 2 * SMALL_BYTE_SET_SCAN_SAMPLE_CANDIDATE_LIMIT;
    private static final int COUNT_START_SAMPLE_SIZE = 256;
    private static final int COUNT_START_SAMPLE_WINDOWS = 16;
    private static final int COUNT_START_SAMPLE_CANDIDATE_LIMIT = SMALL_BYTE_SET_SCAN_SAMPLE_CANDIDATE_LIMIT * COUNT_START_SAMPLE_SIZE / BYTE_SCAN_SAMPLE_SIZE;
    private static final boolean BYTE_SCAN_PRODUCTIVITY_CHECK_ENABLED = true;
    private static final int MIN_SELF_LOOP_SAMPLE_SEARCH_BYTES = 64 * 1024;
    private static final int SELF_LOOP_SAMPLE_SIZE = 64;
    private static final int SELF_LOOP_SAMPLE_PERCENTAGE = 90;
    private static final int SHORT_PAIRED_SEARCH_BYTES = 16;
    private static final int SHORT_PAIRED_SEARCH_LIMIT = 16;
    private static final int SHORT_ABSOLUTE_POINTER_SEARCH_LIMIT = 16;
    // Sidecar rows and entries are eight-byte aligned, so their low bits encode computed terminals.
    private static final long ABSOLUTE_POINTER_TAG_MASK = Long.BYTES - 1;
    private static final long ABSOLUTE_POINTER_MATCH_TAG = 1;
    private static final long ABSOLUTE_POINTER_DEAD_TAG = 2;
    private static final long ABSOLUTE_POINTER_FULL_MATCH_TAG = 3;
    private static final long ABSOLUTE_POINTER_RETRY_OBJECT = Long.MIN_VALUE + 2;
    // One pointer-table form serves the whole JVM, so the pointer loops see one segment kind. With
    // native access a table is native memory whose entries are absolute addresses, read through a
    // segment spanning the whole address space. Without it a table is a `long[]` read through its
    // heap segment, whose base address is zero, so an entry is the byte offset of its target row in
    // the array, with the same tags; offset zero is the reserved row before the first state, so
    // zero still means uncomputed. A failed native allocation falls back to object rows, never to a
    // heap table.
    //
    // Entries of both forms are eight-byte aligned and use plain access, which 64-bit HotSpot
    // performs as one access, so an entry never tears. With JDK 25 C2 on AArch64, opaque heap
    // access kept the segment's field loads and checks inside the loop. "Bounded DFA Pointer
    // Transition Table" in REGULATOR_DECISIONS.md gives the full argument.
    private static final boolean HEAP_POINTER_TABLES = !nativeAccessEnabled();
    static final int MAX_CANDIDATE_START_TRANSITIONS = 128;
    // A shorter unmatched check costs less to repeat than giving up the cursor for the rest of the reset.
    static final int MINIMUM_UNMATCHED_CANDIDATE_FALLBACK_BYTES = 16;
    // A shorter window costs the literal cursor more to set up than the matcher's other routes.
    static final int MINIMUM_LITERAL_CANDIDATE_SEARCH_BYTES = 64;
    private static final int CANDIDATE_NO_MATCH = -1;
    private static final int CANDIDATE_FALLBACK = -2;
    private static final int PREFIX_SCAN = -1;
    // A prefix scan for a folded run behind a lead of varying width; see searchForwardScanAcceleration.
    private static final int PREFIX_RUN_SCAN = -2;
    // The scan set of a start byte set that only the nibble tables of [#findByteSet] take.
    static final int MIXED_BYTE_SET_MARKER = Integer.MIN_VALUE;
    private static final int START_LITERAL_FILTER_MARKER = Integer.MIN_VALUE + 1;
    private static final int START_CLASS_FILTER_MARKER = Integer.MIN_VALUE + 2;
    // Start byte sets at least this probable in English text stop often enough that checking
    // three bytes of each literal pays. Rarer sets keep their single-byte scanners, which cost
    // less per byte, except where buildStartLiteralFilter says or a start class filter replaces
    // them. UTF-8 lead bytes rate as common as the most common ASCII byte.
    private static final double MINIMUM_START_LITERAL_FILTER_START_PROBABILITY = 0.05;
    // Room for the folded first letters of eight literals.
    private static final int MAXIMUM_START_LITERAL_FILTER_START_BYTES = 16;
    // The filter checks three bytes per position, so it replaces the start byte scan only when
    // it is estimated to stop this many times less often.
    private static final int START_LITERAL_FILTER_NARROWING_FACTOR = 4;
    private static final int MINIMUM_PRODUCTIVE_FIXED_DISTANCE_SKIP = 16;
    private static final long REFERENCE_BYTES = (sizeOfObjectArray(8) - sizeOfObjectArray(0)) / 8;
    private static final long STATE_DATA_BYTES = instanceSize(StateData.class);
    private static final long STATE_KEY_BYTES = instanceSize(StateKey.class);
    private static final long STATE_CACHE_BYTES = instanceSize(StateCache.class);
    private static final long CACHE_TABLES_BYTES = instanceSize(CacheTables.class);
    // One conservatively aligned plan record and its 256-byte membership array.
    private static final long FIXED_DISTANCE_BYTE_CANDIDATE_MEMORY = 32L + 16 + 256;
    private static final Prog.FixedDistanceByteCandidates NO_FIXED_DISTANCE_BYTE_CANDIDATES =
            new Prog.FixedDistanceByteCandidates(null, -1);
    private static final Prog.FixedDistanceByteCandidates RETRY_FIXED_DISTANCE_BYTE_CANDIDATES_AFTER_RESET =
            new Prog.FixedDistanceByteCandidates(null, -2);
    @SuppressWarnings("StaticAssignmentOfThrowable")
    private static final RetrySearchException RETRY_SEARCH = new RetrySearchException();
    @SuppressWarnings("StaticAssignmentOfThrowable")
    private static final QuitSearchException QUIT_SEARCH = new QuitSearchException();
    @SuppressWarnings("StaticAssignmentOfThrowable")
    private static final StartStateAllocationException START_STATE_ALLOCATION_FAILED = new StartStateAllocationException();
    @SuppressWarnings("StaticAssignmentOfThrowable")
    private static final TestingFailureError TESTING_FAILURE = new TestingFailureError();
    private static final PointerReaderProbe POINTER_READER_PROBE = Boolean.getBoolean("io.airlift.regulator.dfa.pointer-reader-probe")
            ? new PointerReaderProbe()
            : null;

    private Dfa() {}

    private static boolean selectFlatPairedRows()
    {
        String override = System.getProperty("io.airlift.regulator.dfa.flat-paired-rows");
        return override == null ? "aarch64".equals(System.getProperty("os.arch")) : Boolean.parseBoolean(override);
    }

    static boolean nativeAccessEnabled()
    {
        return Dfa.class.getModule().isNativeAccessEnabled();
    }

    public static final long SEARCH_FAILED = Long.MIN_VALUE;
    public static final long SEARCH_NO_MATCH = -1;
    // What searchJudgingSlowResets returns for a boolean search that skips the DFA, a value no
    // search returns.
    static final long BOOLEAN_SEARCH_SKIPPED = Long.MIN_VALUE + 3;
    static final long COUNT_UNSUPPORTED = -1;
    // A DFA count whose forward search returned SEARCH_FAILED or that reset too often, or one that
    // skipped the DFA after a streak of such counts. Its caller counts the text without the DFA,
    // with a matcher whose finds skip the forward DFA or with a counter of its own. The skip after
    // a streak deviates from upstream RE2, which bails one search at a time.
    static final long COUNT_BAILED = -2;
    static final long CANDIDATE_SEARCH_FALLBACK = Long.MIN_VALUE + 1;
    // A candidate search the text stopped: the DFA quit on a byte outside ASCII. The search falls
    // back as for CANDIDATE_SEARCH_FALLBACK, but the cursor always stays enabled, and the quit
    // counts toward the matcher's quit streak.
    static final long CANDIDATE_SEARCH_QUIT = Long.MIN_VALUE + 2;
    private static final long COUNT_SCAN_REJECTED_MATCH_FLAG = 1L << Integer.SIZE;
    private static final long COUNT_START_BYTE_SCAN_PROMOTED_FLAG = 1L << (Integer.SIZE + 1);
    private static final long COUNT_MATCH_LENGTH_MASK = COUNT_SCAN_REJECTED_MATCH_FLAG - 1;
    private static final long COUNT_SELF_LOOP_SCAN_NOT_APPLICABLE = Long.MIN_VALUE + 2;
    // Unproductive searches of one count in a row, after which the count's remaining searches take
    // the ordinary routes directly. A search advances the streak when it reaches no eligible
    // self-loop or scans only stay-form runs shorter than VectorByteSetScanner.SHORT_SCAN_BYTES;
    // any exit-form scan or a stay run of at least that length clears it.
    private static final int COUNT_SELF_LOOP_UNPRODUCTIVE_LIMIT = 8;
    // A count search at least this long tries the self-loop scan. As for the reverse self-loop
    // skip, four 16-byte vectors, including the bytes stepped to reach the loop, pay for its few
    // transitions and one scan. It sits below MIN_PAIRED_SEARCH_BYTES so that searches with 64 to
    // 255 bytes remaining, such as the final shrinking searches of `.*[^A-Z]|[A-Z]` over a run of
    // capitals, scan their runs too.
    private static final int MIN_COUNT_SELF_LOOP_SCAN_BYTES = 64;
    // A four-byte UTF-8 character inside a matching loop ends in the loop's unflagged copy, which
    // needs one more byte to return to the matching loop.
    private static final int COUNT_SELF_LOOP_MAX_STEPS = 5;
    // After its scans stop paying, a count search whose scans have skipped at least half of the
    // bytes it covered requests a stretch of this many bytes to step through the transition table,
    // and then scans again from the next eligible loop. Each later re-arm of the same search
    // doubles the stretch it requests, unless the scans since the previous re-arm skipped at least
    // a quarter as many bytes as that re-arm requested, which starts the doubling over: on prose,
    // a stretch that grows whenever the scans skip fewer bytes than it stepped would be stepping
    // most bytes. Text whose exits are dense throughout hands off to the ordinary loops at once, as
    // before. A sparse head at least as long as a dense tail keeps the whole search's share at half
    // or more, so once a re-arm has requested COUNT_SELF_LOOP_RECENT_REARM_BYTES, a search whose
    // scans since then skipped under half of the bytes covered since that re-arm also hands off.
    // That rule normally ends the doubling there: a stretch that long continues only when its scans
    // skipped at least as many bytes as it stepped, which also starts the doubling over.
    // COUNT_SELF_LOOP_MAX_REARM_BYTES only bounds the rare stretch the stepper cuts short. "Count
    // Self-Loop Exit-Byte Scan" in REGULATOR_DECISIONS.md records the settings tried and rejected.
    private static final int COUNT_SELF_LOOP_REARM_BYTES = 256;
    private static final int COUNT_SELF_LOOP_RECENT_REARM_BYTES = 8 * 1024;
    private static final int COUNT_SELF_LOOP_MAX_REARM_BYTES = 1 << 24;
    // A matching self-loop run is stepped through the transition table for this many bytes before
    // a vector scan for its exit bytes takes over. This is two vectors at the 16-byte width, twice
    // the VectorByteSetScanner.SHORT_SCAN_BYTES below which a scan's setup costs more than the bytes
    // it skips, so runs that end within it cost what the stepped loop costs.
    private static final int MATCHING_SELF_LOOP_STEPPED_BYTES = 2 * VectorByteSetScanner.SHORT_SCAN_BYTES;
    // A reverse search at least this long probes whether its last bytes reach a non-matching state
    // that loops on itself, and skips that state's run with a backward vector scan for its exit
    // bytes. Four 16-byte vectors, including the probe's bytes, pay for its few transitions and one
    // scan.
    private static final int MIN_REVERSE_SELF_LOOP_SKIP_BYTES = 64;
    // The reverse probe steps at most this many bytes from the end of the search, as the count
    // self-loop scan does to pass the start state's context flags and a trailing UTF-8 character.
    private static final int REVERSE_SELF_LOOP_PROBE_STEPS = COUNT_SELF_LOOP_MAX_STEPS;
    private static final long REVERSE_SELF_LOOP_NO_SKIP = -1;
    private static final IntPredicate COUNT_EVERY_MATCH = Dfa::countEveryMatch;

    // Like upstream dfa_should_bail_when_slow. When true (default), the DFA
    // returns SEARCH_FAILED if it detects cache thrashing. Tests can set this
    // to false to force the DFA to keep going despite thrashing.
    static volatile boolean dfaShouldBailWhenSlow = true;

    enum ManyMatchResult
    {
        MATCH,
        NO_MATCH,
        RESOURCE_EXHAUSTED,
    }

    public static long search(Prog prog, Slice context, int start, int end, boolean anchored, Prog.MatchKind matchKind, boolean wantMatchBoundary)
    {
        requireNonNull(context, "context is null");
        return search(prog, context, 0, context.length(), start, end, anchored, matchKind, wantMatchBoundary);
    }

    static long search(
            Prog prog,
            Slice context,
            int contextStart,
            int contextEnd,
            int start,
            int end,
            boolean anchored,
            Prog.MatchKind matchKind,
            boolean wantMatchBoundary)
    {
        return search(
                prog,
                context,
                contextStart,
                contextEnd,
                start,
                end,
                anchored,
                matchKind,
                wantMatchBoundary,
                0);
    }

    static long searchReverseCandidate(
            Prog prog,
            Slice context,
            int contextStart,
            int contextEnd,
            int start,
            int end,
            int maximumBytes)
    {
        return searchReverseCandidate(prog, context, contextStart, contextEnd, start, end, maximumBytes, true);
    }

    /// As [#searchReverseCandidate], but in the earliest-match mode, which stops at the first
    /// start the reverse DFA reaches: a non-negative result says only that some start in
    /// `[start, end]` exists, and a program that can match the empty string always reports one.
    /// An [InnerLiteralSearch] count whose matches end at the literal's end needs no more.
    static long searchReverseCandidateExists(
            Prog prog,
            Slice context,
            int contextStart,
            int contextEnd,
            int start,
            int end,
            int maximumBytes)
    {
        return searchReverseCandidate(prog, context, contextStart, contextEnd, start, end, maximumBytes, false);
    }

    // The reverse candidate search, which runs back to the leftmost start with
    // `wantMatchBoundary`, and otherwise stops at the first start it reaches.
    private static long searchReverseCandidate(
            Prog prog,
            Slice context,
            int contextStart,
            int contextEnd,
            int start,
            int end,
            int maximumBytes,
            boolean wantMatchBoundary)
    {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        return search(
                prog,
                context,
                contextStart,
                contextEnd,
                start,
                end,
                true,
                Prog.MatchKind.LONGEST_MATCH,
                wantMatchBoundary,
                maximumBytes);
    }

    /// Runs the leftmost-first DFA of the forward `prog` anchored at `start` and returns the Slice
    /// offset of the match end, [#SEARCH_FAILED], or, when no match starts at `start`, the value
    /// [#anchoredStopPosition(long)] decodes to the Slice offset where the DFA died or the window
    /// ended. [InnerLiteralSearch] uses the stop to keep its candidate confirmations linear.
    @SuppressWarnings("UnusedException")
    static long searchAnchoredWithStop(Prog prog, Slice context, int contextStart, int contextEnd, int start, int end)
    {
        if (prog.reversed() || prog.start() == 0) {
            throw new IllegalArgumentException("expected a forward program that can match");
        }
        DfaInstance dfa = prog.getCachedDfa(DfaInstance.Kind.FIRST_MATCH);
        if (dfa == null || !dfa.ok()) {
            return SEARCH_FAILED;
        }
        int contextOffset = context.byteArrayOffset();
        boolean requiresExclusiveSearch = false;
        long observedCacheVersion = 0;
        while (true) {
            boolean exclusiveSearch = requiresExclusiveSearch;
            if (!exclusiveSearch) {
                observedCacheVersion = dfa.cacheVersion();
            }
            CacheTables tables = dfa.beginSearch(exclusiveSearch);
            if (exclusiveSearch && dfa.cacheVersion() != observedCacheVersion) {
                dfa.cancelExclusiveSearch();
                requiresExclusiveSearch = false;
                continue;
            }
            try {
                long result = searchAnchoredWithStop(
                        dfa,
                        tables,
                        context.byteArray(),
                        contextOffset + contextStart,
                        contextOffset + contextEnd,
                        contextOffset + start,
                        contextOffset + end);
                if (result >= 0) {
                    return result - contextOffset;
                }
                if (result == SEARCH_FAILED) {
                    return result;
                }
                return anchoredStopResult(anchoredStopPosition(result) - contextOffset);
            }
            catch (StartStateAllocationException | QuitSearchException ignored) {
                return SEARCH_FAILED;
            }
            catch (RetrySearchException ignored) {
                if (exclusiveSearch) {
                    throw new AssertionError("exclusive anchored DFA search requested a retry");
                }
                requiresExclusiveSearch = true;
            }
            finally {
                dfa.endSearch(exclusiveSearch);
            }
        }
    }

    // The stop position is never negative, so the encoding stays at or below -2, never
    // SEARCH_NO_MATCH or SEARCH_FAILED.
    private static long anchoredStopResult(int stopPosition)
    {
        return -2L - stopPosition;
    }

    static int anchoredStopPosition(long result)
    {
        return (int) (-2L - result);
    }

    /// Runs [#searchReverseCandidate] of `reverseProg` over `[start, literalStart)` and, when it
    /// finds a match start, [#searchAnchoredWithStop(Prog,Slice,int,int,int,int)] of `forwardProg`
    /// from there to `end`, using one captured cache root per DFA, without the exclusive lock.
    /// Returns the match's Slice offsets packed as `start << 32 | end`, [#SEARCH_NO_MATCH] when the
    /// reverse search finds no start, the forward search's stop for [#anchoredStopPosition(long)],
    /// or [#SEARCH_FAILED] when the caller must run the two searches separately: either needs a
    /// cache write, quits, fails, or falls back. The pair never takes the exclusive path; the separate searches do.
    @SuppressWarnings("UnusedException")
    static long searchReverseThenAnchoredWithStop(
            Prog reverseProg,
            Prog forwardProg,
            Slice context,
            int contextStart,
            int contextEnd,
            int start,
            int literalStart,
            int end,
            int maximumReverseBytes)
    {
        // The separate searches handle every case their preambles treat specially.
        if (contextStart < 0 || contextEnd < contextStart || contextEnd > context.length() ||
                start < contextStart || literalStart < start || end < literalStart || end > contextEnd || maximumReverseBytes <= 0 ||
                !reverseProg.reversed() || reverseProg.start() == 0 || reverseProg.anchorStart() || reverseProg.anchorEnd() ||
                !reverseProg.didFlatten() || reverseProg.bytemapRange() == 0 ||
                forwardProg.reversed() || forwardProg.start() == 0) {
            return SEARCH_FAILED;
        }
        DfaInstance reverseDfa = reverseProg.getCachedDfa(DfaInstance.Kind.LONGEST_MATCH);
        DfaInstance forwardDfa = forwardProg.getCachedDfa(DfaInstance.Kind.FIRST_MATCH);
        if (reverseDfa == null || !reverseDfa.ok() || forwardDfa == null || !forwardDfa.ok()) {
            return SEARCH_FAILED;
        }
        int contextOffset = context.byteArrayOffset();
        // Reader searches, so there is nothing to end.
        CacheTables reverseTables = reverseDfa.beginSearch(false);
        CacheTables forwardTables = forwardDfa.beginSearch(false);
        try {
            return searchReverseThenAnchoredWithStop(
                    reverseDfa,
                    reverseTables,
                    forwardDfa,
                    forwardTables,
                    context.byteArray(),
                    contextOffset,
                    contextOffset + contextStart,
                    contextOffset + contextEnd,
                    contextOffset + start,
                    contextOffset + literalStart,
                    contextOffset + end,
                    maximumReverseBytes);
        }
        catch (RetrySearchException | StartStateAllocationException | QuitSearchException ignored) {
            return SEARCH_FAILED;
        }
    }

    // The bodies of the reverse candidate search, as the general search runs it for a reversed
    // program with no anchors, and of the anchored search with stop. On this path neither search
    // writes a cache, since the pair never runs exclusive, so each reads only the tables its caller
    // loaded.
    private static long searchReverseThenAnchoredWithStop(
            DfaInstance reverseDfa,
            CacheTables reverseTables,
            DfaInstance forwardDfa,
            CacheTables forwardTables,
            byte[] bytes,
            int contextOffset,
            int contextBegin,
            int contextEnd,
            int textBegin,
            int literalBegin,
            int textEnd,
            int maximumReverseBytes)
    {
        StateData startData = reverseDfa.analyzeStart(reverseTables, bytes, contextBegin, contextEnd, textBegin, literalBegin, true, false);
        if (startData.offset() == T_DEAD) {
            return SEARCH_NO_MATCH;
        }
        if (startData.offset() == T_FULL_MATCH) {
            return SEARCH_FAILED;
        }
        long reverse = searchBackward(
                reverseDfa,
                reverseTables,
                bytes,
                textBegin,
                literalBegin,
                contextBegin,
                startData.offset(),
                (startData.flag() & FLAG_MATCH) != 0,
                false,
                false,
                maximumReverseBytes);
        if (reverse < 0) {
            return reverse == SEARCH_NO_MATCH ? SEARCH_NO_MATCH : SEARCH_FAILED;
        }
        int matchBegin = textBegin + (int) reverse;
        long forward = searchAnchoredWithStop(forwardDfa, forwardTables, bytes, contextBegin, contextEnd, matchBegin, textEnd);
        if (forward >= 0) {
            return ((long) (matchBegin - contextOffset) << 32) | (forward - contextOffset);
        }
        if (forward == SEARCH_FAILED) {
            return SEARCH_FAILED;
        }
        return anchoredStopResult(anchoredStopPosition(forward) - contextOffset);
    }

    // Shares searchAnchoredCandidate's anchored traversal: step the cached DFA from the start
    // position, record the last match boundary, scan the rest of a matching self-loop on the int
    // table, a scan that can compute the loop's exit bytes, taking the cache lock when they are not
    // cached, and stop when the state dies or the text ends. It differs: the window may end before
    // the context, so the last step reads the byte after the window; a match at the start is
    // accepted; there is no work budget or transition cap, so the self-loop scan runs to the end
    // of the window, since the caller bounds the work by never confirming a candidate inside a
    // span an earlier confirmation already read; and any other transition back into the current
    // state keeps its row. The fallback after a long check that reaches the end of the text
    // unmatched is the candidate check's alone.
    private static long searchAnchoredWithStop(DfaInstance dfa, CacheTables tables, byte[] bytes, int contextBegin, int contextEnd, int textBegin, int textEnd)
    {
        StateData startData = dfa.analyzeStart(tables, bytes, contextBegin, contextEnd, textBegin, textEnd, true, true);
        tables = dfa.tablesAfterWrite(tables);
        if (startData.offset() == T_DEAD) {
            return anchoredStopResult(textBegin);
        }
        if (startData.offset() == T_FULL_MATCH) {
            return textEnd;
        }

        int stateOffset = startData.offset();
        int[] transitions = tables.transitions;
        byte[] byteMap = dfa.bytemap;
        int nextSize = dfa.nextSize;
        Object[] stateRefs = dfa.stateReferences(tables, stateOffset);
        int lastMatchBoundary = (startData.flag() & FLAG_MATCH) != 0 ? textBegin : -1;

        for (int position = textBegin; position < textEnd; position++) {
            int byteClass = byteMap[bytes[position] & 0xFF] & 0xFF;
            Object nextReference = getStateReference(stateRefs, byteClass);
            if (nextReference != null) {
                stateRefs = (Object[]) nextReference;
                continue;
            }

            stateOffset = rowStateOffset(stateRefs, nextSize, transitions);
            int nextState = getTransition(transitions, stateOffset + byteClass);
            if (nextState == T_UNCOMPUTED) {
                nextState = dfa.computeTransition(tables, stateOffset, bytes[position] & 0xFF);
                tables = dfa.tablesAfterWrite(tables);
                transitions = tables.transitions;
                if (nextState == Integer.MIN_VALUE) {
                    return SEARCH_FAILED;
                }
            }
            if (nextState == T_DEAD) {
                return lastMatchBoundary >= 0 ? lastMatchBoundary : anchoredStopResult(position + 1);
            }
            if (nextState == T_FULL_MATCH) {
                return textEnd;
            }
            if ((nextState & T_MATCH_BIT) != 0) {
                lastMatchBoundary = position;
                nextState &= ~T_MATCH_BIT;
                // Object rows hold no match transitions, so each byte of a matching self-loop,
                // such as the trailing `\w+` of `\w+\s+Holmes\s+\w+`, would come back here;
                // scan the rest of the run on the int table instead.
                if (nextState == stateOffset) {
                    int matchingSelfLoopEnd = scanMatchingSelfLoopTransitions(dfa, tables, bytes, position + 1, textEnd, stateOffset);
                    // Computing the loop's exit bytes may have published grown tables.
                    tables = dfa.tablesAfterWrite(tables);
                    transitions = tables.transitions;
                    stateRefs = dfa.stateReferences(tables, stateOffset);
                    if (matchingSelfLoopEnd != position + 1) {
                        dfa.matchingSelfLoopScanCount++;
                    }
                    position = matchingSelfLoopEnd - 1;
                    lastMatchBoundary = position;
                    continue;
                }
            }
            // A transition back into the current state keeps the current row.
            if (nextState == stateOffset) {
                continue;
            }

            stateOffset = nextState;
            stateRefs = dfa.stateReferences(tables, stateOffset);
        }

        // Cached object transitions advance the row reference without updating the offset.
        stateOffset = rowStateOffset(stateRefs, nextSize, transitions);
        int lastByte = textEnd == contextEnd ? BYTE_END_TEXT : bytes[textEnd] & 0xFF;
        int finalState = getTransition(transitions, stateOffset + dfa.byteMap(lastByte));
        if (finalState == T_UNCOMPUTED) {
            finalState = dfa.computeTransition(tables, stateOffset, lastByte);
            if (finalState == Integer.MIN_VALUE) {
                return SEARCH_FAILED;
            }
        }
        if (finalState == T_FULL_MATCH || (finalState > 0 && (finalState & T_MATCH_BIT) != 0)) {
            return textEnd;
        }
        return lastMatchBoundary >= 0 ? lastMatchBoundary : anchoredStopResult(textEnd);
    }

    @SuppressWarnings("UnusedException")
    private static long search(
            Prog prog,
            Slice context,
            int contextStart,
            int contextEnd,
            int start,
            int end,
            boolean anchored,
            Prog.MatchKind matchKind,
            boolean wantMatchBoundary,
            int maximumReverseBytes)
    {
        requireNonNull(prog, "prog is null");
        requireNonNull(context, "context is null");
        requireNonNull(matchKind, "matchKind is null");
        if (contextStart < 0 || contextEnd < contextStart || contextEnd > context.length() ||
                start < contextStart || end < start || end > contextEnd) {
            return SEARCH_NO_MATCH;
        }
        int contextOffset = context.byteArrayOffset();
        int ctxBegin = contextOffset + contextStart;
        int ctxEnd = contextOffset + contextEnd;
        int textBegin = contextOffset + start;
        int textEnd = contextOffset + end;

        if (prog.start() == 0) {
            return SEARCH_NO_MATCH;
        }

        boolean anchorStart = prog.anchorStart();
        boolean anchorEnd = prog.anchorEnd();

        boolean caret = anchorStart;
        boolean dollar = anchorEnd;
        if (prog.reversed()) {
            boolean temporary = caret;
            caret = dollar;
            dollar = temporary;
        }
        if (caret && ctxBegin != textBegin) {
            return SEARCH_NO_MATCH;
        }
        if (dollar && ctxEnd != textEnd) {
            return SEARCH_NO_MATCH;
        }

        boolean runForward = !prog.reversed();
        boolean fullMatch = matchKind == Prog.MatchKind.FULL_MATCH;
        boolean endMatch = matchKind != Prog.MatchKind.MANY_MATCH && (anchorEnd || fullMatch);
        if (endMatch && matchKind != Prog.MatchKind.MANY_MATCH) {
            matchKind = Prog.MatchKind.LONGEST_MATCH;
        }

        // As in upstream, an earliest-match search that sees its match mid-text returns the match
        // there, without taking the end-of-text step.
        boolean wantEarliestMatch = !wantMatchBoundary && !endMatch;
        if (wantEarliestMatch && matchKind != Prog.MatchKind.MANY_MATCH) {
            matchKind = Prog.MatchKind.LONGEST_MATCH;
        }

        if (!prog.didFlatten()) {
            throw new IllegalStateException("expected flattened program");
        }
        if (prog.bytemapRange() == 0) {
            throw new IllegalStateException("bytemap not computed");
        }

        DfaInstance.Kind kind = switch (matchKind) {
            case FIRST_MATCH -> DfaInstance.Kind.FIRST_MATCH;
            case LONGEST_MATCH, FULL_MATCH -> DfaInstance.Kind.LONGEST_MATCH;
            case MANY_MATCH -> DfaInstance.Kind.MANY_MATCH;
        };
        DfaInstance dfa = prog.getCachedDfa(kind);

        if (dfa == null || !dfa.ok()) {
            return SEARCH_FAILED;
        }

        boolean anchoredEffective = anchored || anchorStart || fullMatch;
        Prog.FixedDistanceByteCandidates fixedDistanceCandidates =
                runForward &&
                        !anchoredEffective &&
                        textEnd - textBegin >= MIN_SELECTIVE_SCAN_SEARCH_BYTES ? dfa.fixedDistanceByteCandidates() : null;

        boolean requiresExclusiveSearch = false;
        long observedCacheVersion = 0;
        while (true) {
            boolean exclusiveSearch = requiresExclusiveSearch;
            if (!exclusiveSearch) {
                observedCacheVersion = dfa.cacheVersion();
            }
            CacheTables tables = dfa.beginSearch(exclusiveSearch);
            if (exclusiveSearch && dfa.cacheVersion() != observedCacheVersion) {
                dfa.cancelExclusiveSearch();
                requiresExclusiveSearch = false;
                continue;
            }
            try {
                StateData startData = dfa.analyzeStart(tables, context.byteArray(), ctxBegin, ctxEnd, textBegin, textEnd, anchoredEffective, runForward);
                tables = dfa.tablesAfterWrite(tables);
                if (startData.offset() == T_DEAD) {
                    return SEARCH_NO_MATCH;
                }
                if (startData.offset() == T_FULL_MATCH) {
                    if (!runForward && maximumReverseBytes > 0) {
                        return CANDIDATE_SEARCH_FALLBACK;
                    }
                    return (runForward == wantEarliestMatch) ? 0 : end - start;
                }

                int startOffset = startData.offset();
                boolean startIsMatch = (startData.flag() & FLAG_MATCH) != 0;
                byte[] bytes = context.byteArray();
                if (runForward && canReturnEmptyAtStart(dfa, bytes, textBegin, textEnd, startIsMatch, endMatch)) {
                    return 0;
                }
                boolean canPrefixAccel = runForward && prog.canPrefixAccel() && !anchoredEffective && isContextFreeStart(startData.flag());
                boolean canSingleBytePrefixAccel = canPrefixAccel && prog.canUseSingleBytePrefixAccelFastPath();
                byte[] requiredPrefix = prog.getRequiredPrefix();
                boolean canAnchoredLongPrefixIntPath = runForward &&
                        anchoredEffective &&
                        requiredPrefix != null &&
                        requiredPrefix.length >= 16 &&
                        !prog.isRequiredPrefixFoldCase();

                // Prefix accel is intentionally split:
                // - single-byte, case-sensitive prefix ("indexOf-style") has very high false-positive
                //   frequency on random text and tends to bounce start <-> prefix scan many times.
                //   That path is tuned around a fused int[] DFA loop.
                // - all other prefix accel modes (multi-byte FrontAndBack, folded-prefix candidate scanning) stay on
                //   the Object[] loop optimized for general DFA throughput.
                if (!runForward) {
                    if (!startIsMatch &&
                            maximumReverseBytes == 0 &&
                            !endMatch &&
                            !wantEarliestMatch &&
                            (textEnd - textBegin >= MIN_PAIRED_SEARCH_BYTES || dfa.shouldUseAbsolutePointersForShortSearch(tables))) {
                        tables = dfa.requestAbsolutePointerTransitions(tables);
                    }
                    return searchBackward(
                            dfa,
                            tables,
                            bytes,
                            textBegin,
                            textEnd,
                            ctxBegin,
                            startOffset,
                            startIsMatch,
                            endMatch,
                            wantEarliestMatch,
                            maximumReverseBytes);
                }
                if (canSingleBytePrefixAccel) {
                    return searchForwardPrefixAccelSingleByte(dfa, tables, prog, bytes, textBegin, textEnd, ctxEnd, startOffset, startIsMatch, endMatch, wantEarliestMatch);
                }
                if (canPrefixAccel) {
                    return searchForwardScanAcceleration(
                            dfa,
                            tables,
                            prog,
                            bytes,
                            textBegin,
                            textEnd,
                            ctxEnd,
                            startOffset,
                            startIsMatch,
                            endMatch,
                            wantEarliestMatch,
                            null,
                            0,
                            PREFIX_SCAN);
                }
                if (canAnchoredLongPrefixIntPath) {
                    return searchForwardAnchoredLongPrefixInt(dfa, tables, bytes, textBegin, textEnd, ctxEnd, startOffset, startIsMatch, endMatch, wantEarliestMatch);
                }
                boolean fixedDistanceScanRejected = false;
                if (fixedDistanceCandidates != null &&
                        canUseFixedDistanceByteAcceleration(anchoredEffective, true, startData.flag(), textEnd - textBegin)) {
                    fixedDistanceScanRejected = BYTE_SCAN_PRODUCTIVITY_CHECK_ENABLED && !isFixedDistanceByteScanProductive(
                            dfa, fixedDistanceCandidates.candidates(), bytes, textBegin + fixedDistanceCandidates.offset(), textEnd);
                    if (!fixedDistanceScanRejected) {
                        int fixedDistanceSmallByteSet = selectByteScanSet(
                                dfa.fixedDistanceSmallByteSet(),
                                fixedDistanceCandidates.candidates(),
                                bytes,
                                textBegin + fixedDistanceCandidates.offset(),
                                textEnd);
                        return searchForwardScanAcceleration(
                                dfa,
                                tables,
                                prog,
                                bytes,
                                textBegin,
                                textEnd,
                                ctxEnd,
                                startOffset,
                                startIsMatch,
                                endMatch,
                                wantEarliestMatch,
                                fixedDistanceCandidates.candidates(),
                                fixedDistanceSmallByteSet,
                                fixedDistanceCandidates.offset());
                    }
                }
                if (!startIsMatch &&
                        textEnd - textBegin < MIN_PAIRED_SEARCH_BYTES &&
                        dfa.shouldUseAbsolutePointersForShortSearch(tables)) {
                    tables = dfa.requestAbsolutePointerTransitions(tables);
                    if (dfa.hasAbsolutePointerTransitions(tables, startOffset)) {
                        return searchForward(dfa, tables, bytes, textBegin, textEnd, ctxEnd, startOffset, false, false, endMatch, wantEarliestMatch);
                    }
                }
                if (canUseStartByteAcceleration(dfa, anchoredEffective, true, startData.flag())) {
                    if (BYTE_SCAN_PRODUCTIVITY_CHECK_ENABLED &&
                            !dfa.isStartByteScanProductive(bytes, textBegin, textEnd)) {
                        dfa.byteScanFallbackCount++;
                    }
                    else {
                        int startByteScanSet = selectByteScanSet(
                                dfa.startByteScanSet,
                                dfa.startByteCandidates,
                                bytes,
                                textBegin,
                                textEnd);
                        return searchForwardScanAcceleration(
                                dfa,
                                tables,
                                prog,
                                bytes,
                                textBegin,
                                textEnd,
                                ctxEnd,
                                startOffset,
                                startIsMatch,
                                endMatch,
                                wantEarliestMatch,
                                dfa.startByteCandidates,
                                startByteScanSet,
                                0);
                    }
                }
                else if (fixedDistanceScanRejected) {
                    dfa.byteScanFallbackCount++;
                }
                if (!startIsMatch && textEnd - textBegin >= MIN_PAIRED_SEARCH_BYTES) {
                    tables = dfa.requestPairedTransitions(tables);
                    if (dfa.hasPairedTransitions(tables, startOffset)) {
                        return FLAT_PAIRED_ROWS
                                ? searchForwardPairsFlat(dfa, tables, bytes, textBegin, textBegin, textEnd, ctxEnd, startOffset, endMatch, wantEarliestMatch)
                                : searchForwardPairs(dfa, tables, bytes, textBegin, textBegin, textEnd, ctxEnd, startOffset, endMatch, wantEarliestMatch);
                    }
                }
                return searchForward(dfa, tables, bytes, textBegin, textEnd, ctxEnd, startOffset, startIsMatch, false, endMatch, wantEarliestMatch);
            }
            catch (StartStateAllocationException | QuitSearchException ignored) {
                return SEARCH_FAILED;
            }
            catch (RetrySearchException ignored) {
                if (exclusiveSearch) {
                    throw new AssertionError("exclusive DFA search requested a retry");
                }
                requiresExclusiveSearch = true;
            }
            finally {
                dfa.endSearch(exclusiveSearch);
            }
        }
    }

    static long countMatches(Prog prog, Slice context, Prog.MatchKind matchKind)
    {
        return countMatches(prog, context, context.length(), matchKind);
    }

    /// Counts the matches in the first `end` bytes of `context` as
    /// [#countMatches(Prog,Slice,Prog.MatchKind)] counts all of it, with assertions seeing those
    /// bytes as the whole context.
    static long countMatches(Prog prog, Slice context, int end, Prog.MatchKind matchKind)
    {
        long result = countMatches(prog, context, 0, end, matchKind, COUNT_EVERY_MATCH, null);
        return result < 0 ? result : countStopCount(result);
    }

    private static boolean countEveryMatch(int position)
    {
        return true;
    }

    /// Counts the matches from `start` to the end of `context` as
    /// [#countMatches(Prog,Slice,Prog.MatchKind)] counts all of it, with assertions seeing the
    /// whole context, and asks `continueCounting` at each match end whether to count on from
    /// there. Returns [#COUNT_UNSUPPORTED]; [#COUNT_BAILED] when a search returns [#SEARCH_FAILED]
    /// or the count resets too often, or when the call skips the DFA after a streak of such calls;
    /// or the count and the Slice offset where counting stopped, which is the context end when no
    /// match remains, packed for [#countStopCount(long)] and [#countStopPosition(long)]. A retried
    /// search resumes at the last match end, so the predicate sees each match end once. Reuses and
    /// records byte-scan sampling outcomes in `sampling` when it is not null. A caller that counts
    /// one text in several calls, each starting at or after where the previous one stopped, passes
    /// the same [CountByteScanSampling] to every call. Without one, a call with at least
    /// [#MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES] of text shares
    /// [CountByteScanSampling#forSingleCount()] among its own searches.
    static long countMatches(Prog prog, Slice context, int start, Prog.MatchKind matchKind, IntPredicate continueCounting, CountByteScanSampling sampling)
    {
        return countMatches(prog, context, start, context.length(), matchKind, continueCounting, sampling);
    }

    /// Counts the matches from `start` to `end` as
    /// [#countMatches(Prog,Slice,int,Prog.MatchKind,IntPredicate,CountByteScanSampling)] counts
    /// them to the context end, with assertions seeing the first `end` bytes of `context` as the
    /// whole context; the stop position is `end` when no match remains.
    static long countMatches(Prog prog, Slice context, int start, int end, Prog.MatchKind matchKind, IntPredicate continueCounting, CountByteScanSampling sampling)
    {
        requireNonNull(prog, "prog is null");
        requireNonNull(context, "context is null");
        requireNonNull(matchKind, "matchKind is null");
        requireNonNull(continueCounting, "continueCounting is null");
        if (matchKind != Prog.MatchKind.FIRST_MATCH && matchKind != Prog.MatchKind.LONGEST_MATCH) {
            throw new IllegalArgumentException("unsupported count match kind: " + matchKind);
        }
        if (end < 0 || end > context.length()) {
            throw new IllegalArgumentException("end is out of bounds");
        }
        if (start < 0 || start > end) {
            throw new IllegalArgumentException("start is out of bounds");
        }
        if (prog.start() == 0) {
            return countStopResult(0, end);
        }
        if (prog.anchorStart()) {
            return COUNT_UNSUPPORTED;
        }
        if (!prog.didFlatten()) {
            throw new IllegalStateException("expected flattened program");
        }
        if (prog.bytemapRange() == 0) {
            throw new IllegalStateException("bytemap not computed");
        }

        Prog.MatchKind effectiveMatchKind = prog.anchorEnd() ? Prog.MatchKind.LONGEST_MATCH : matchKind;
        DfaInstance.Kind kind = effectiveMatchKind == Prog.MatchKind.FIRST_MATCH
                ? DfaInstance.Kind.FIRST_MATCH
                : DfaInstance.Kind.LONGEST_MATCH;
        DfaInstance dfa = prog.getCachedDfa(kind);
        if (dfa == null || !dfa.ok()) {
            return COUNT_UNSUPPORTED;
        }
        // After a streak of bailing DFA counts, the next DFA counts skip the DFA, then one tries it
        // again.
        if (dfa.takeCountSkip()) {
            return COUNT_BAILED;
        }
        long result = countMatches(prog, context, start, end, continueCounting, sampling, dfa);
        if (result == COUNT_BAILED) {
            dfa.recordCountBail();
        }
        else {
            dfa.recordCountCompleted();
        }
        return result;
    }

    @SuppressWarnings("UnusedException")
    private static long countMatches(Prog prog, Slice context, int start, int end, IntPredicate continueCounting, CountByteScanSampling sampling, DfaInstance dfa)
    {
        byte[] bytes = context.byteArray();
        int contextBegin = context.byteArrayOffset();
        int contextEnd = contextBegin + end;
        int textBegin = contextBegin + start;
        Prog.FixedDistanceByteCandidates fixedDistanceCandidates = contextEnd - textBegin >= MIN_SELECTIVE_SCAN_SEARCH_BYTES
                ? dfa.fixedDistanceByteCandidates()
                : null;
        // Sample start-byte density from the count start. A vector choice is kept for the whole
        // count: samples taken at match boundaries can land on the literals of later matches and
        // fall back to the scalar scan for the long sparse span that follows. A scalar choice is
        // sampled again when a later search reaches the start-byte scan, until the vector scan is
        // selected; searches served by another scan do not sample. Resampling requires at least
        // 4 KiB remaining; below that the check reports the span as productive without sampling,
        // which would promote the vector scan for dense input. Prefix acceleration returns before
        // the start-byte scan, so the selection is made only when it can be read. This selection
        // keeps a start literal filter or a start class filter without the small-set sample; the
        // productivity sampling of long searches still samples the filter's own stops. A count of
        // every match, the three-argument form's `COUNT_EVERY_MATCH`, settles a borderline first
        // sample with a wider one; any other predicate keeps the 64-byte decision. A count that
        // carries sampling across calls keeps an earlier call's selection where it still holds. On
        // a long text, the count's own sampling, below, holds a later search's scalar choice for
        // the recheck distance.
        int startByteScanSet = 0;
        boolean resampleStartByteScanSet = false;
        if (dfa.canStartByteAcceleration() && !prog.canPrefixAccel()) {
            if (sampling != null) {
                startByteScanSet = sampling.selectStartByteScanSet(dfa, bytes, textBegin, contextEnd);
            }
            else {
                startByteScanSet = continueCounting == COUNT_EVERY_MATCH
                        ? selectEveryMatchCountByteScanSet(dfa.startByteScanSet, dfa.startByteCandidates, bytes, textBegin, contextEnd)
                        : selectByteScanSet(dfa.startByteScanSet, dfa.startByteCandidates, bytes, textBegin, contextEnd);
            }
            resampleStartByteScanSet = startByteScanSet == 0 && dfa.startByteScanSet != 0;
        }
        // The count runs one search from each match end, and each of them would sample the scans'
        // productivity again, over windows spread across the rest of the text, so a count of many
        // matches would pay a cold sample per match. A count with enough text for the check and
        // no sampling carried across calls shares one among its own searches, which holds only
        // the outcomes that stay safe past the matches around the position sampled.
        CountByteScanSampling searchSampling = sampling;
        if (searchSampling == null && contextEnd - textBegin >= MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES) {
            searchSampling = CountByteScanSampling.forSingleCount();
        }
        // The self-loop cutoff applies to one count of one text: this call, or every call that
        // shares `sampling`, such as the inner literal count's fallback counts, which each run
        // only a few searches. A new text may reach an eligible loop. The count keeps its value
        // in `sampling` between calls, so a count that ran in between, on another thread or
        // interleaved with this one, does not decide whether this one tries the route. The DFA
        // field is the working copy the searches update, a racy route hint that a concurrent count
        // can still change within this call. The guard skips writing a shared field that already
        // holds the value.
        int selfLoopUnproductiveCount = sampling == null ? 0 : sampling.selfLoopUnproductiveCount;
        if (dfa.countSelfLoopUnproductiveCount != selfLoopUnproductiveCount) {
            dfa.countSelfLoopUnproductiveCount = selfLoopUnproductiveCount;
        }
        long count = 0;
        boolean requiresExclusiveSearch = false;
        long observedCacheVersion = 0;
        // The reset count a lock-free count began with when it handed resets it saw to the lock,
        // or -1.
        int handedOffResetCount = -1;
        while (true) {
            boolean exclusiveSearch = requiresExclusiveSearch;
            if (!exclusiveSearch) {
                observedCacheVersion = dfa.cacheVersion();
            }
            CacheTables tables = dfa.beginSearch(exclusiveSearch);
            if (exclusiveSearch) {
                if (handedOffResetCount >= 0) {
                    // The cache changed with the resets handed over, so they are judged under
                    // the lock rather than forgiven. Giving the table back changes no tables, so
                    // the count searches again on the ones it just read.
                    if (dfa.countResetsTooOften(handedOffResetCount, true)) {
                        dfa.cancelExclusiveSearch();
                        return COUNT_BAILED;
                    }
                }
                else if (dfa.cacheVersion() != observedCacheVersion) {
                    dfa.cancelExclusiveSearch();
                    requiresExclusiveSearch = false;
                    continue;
                }
            }
            try {
                // A locked count that took over resets goes on counting from the same start.
                int initialResetCount = handedOffResetCount >= 0 ? handedOffResetCount : dfa.resetCount();
                while (textBegin <= contextEnd) {
                    long matchEnd = searchForwardForCount(
                            dfa,
                            tables,
                            prog,
                            bytes,
                            contextBegin,
                            contextEnd,
                            textBegin,
                            fixedDistanceCandidates,
                            startByteScanSet,
                            resampleStartByteScanSet,
                            searchSampling);
                    if (sampling != null) {
                        sampling.selfLoopUnproductiveCount = dfa.countSelfLoopUnproductiveCount;
                    }
                    // Reload, so a long exclusive count does not keep superseded tables reachable.
                    tables = dfa.tablesAfterWrite(tables);
                    if (matchEnd == SEARCH_FAILED) {
                        return COUNT_BAILED;
                    }
                    if (matchEnd == SEARCH_NO_MATCH) {
                        return countStopResult(count, end);
                    }
                    if (matchEnd <= 0) {
                        return COUNT_UNSUPPORTED;
                    }
                    boolean byteScanRejected = (matchEnd & COUNT_SCAN_REJECTED_MATCH_FLAG) != 0;
                    if ((matchEnd & COUNT_START_BYTE_SCAN_PROMOTED_FLAG) != 0) {
                        startByteScanSet = dfa.startByteScanSet;
                        resampleStartByteScanSet = false;
                    }
                    matchEnd &= COUNT_MATCH_LENGTH_MASK;
                    if (byteScanRejected && dfa.countResetsTooOften(initialResetCount, exclusiveSearch)) {
                        // A lock-free count cannot reset, so the resets it saw are other searches',
                        // and the pointer table may have caused them. Giving the table back needs
                        // the lock, so the count hands the decision to it, keeping the matches
                        // already counted; the locked count searches again from the previous match's
                        // end and finds this match again.
                        // It hands over only while the table is held or withheld and not given
                        // back, or when another search gave it back after these resets were judged;
                        // a DFA that never had one, or gave it back before, gives up here as before.
                        if (!exclusiveSearch && dfa.countHandsResetsToLock(initialResetCount)) {
                            handedOffResetCount = initialResetCount;
                            throw RETRY_SEARCH;
                        }
                        return COUNT_BAILED;
                    }
                    textBegin += (int) matchEnd;
                    count++;
                    if (!continueCounting.test(textBegin - contextBegin)) {
                        return countStopResult(count, textBegin - contextBegin);
                    }
                }
                return countStopResult(count, end);
            }
            catch (StartStateAllocationException | QuitSearchException ignored) {
                return COUNT_UNSUPPORTED;
            }
            catch (RetrySearchException ignored) {
                if (exclusiveSearch) {
                    throw new AssertionError("exclusive DFA count requested a retry");
                }
                requiresExclusiveSearch = true;
            }
            finally {
                dfa.endSearch(exclusiveSearch);
            }
        }
    }

    // Every match is non-empty, so neither the count nor the stop position exceeds the context length.
    private static long countStopResult(long count, int stopPosition)
    {
        return count << Integer.SIZE | stopPosition;
    }

    static long countStopCount(long result)
    {
        return result >>> Integer.SIZE;
    }

    static int countStopPosition(long result)
    {
        return (int) result;
    }

    static GroupZeroForwardCursor createGroupZeroForwardCursor(Prog prog)
    {
        requireNonNull(prog, "prog is null");
        if (prog.reversed() || prog.start() == 0 || !prog.didFlatten() || prog.bytemapRange() == 0) {
            return null;
        }
        DfaInstance dfa = prog.getCachedDfa(DfaInstance.Kind.FIRST_MATCH);
        if (dfa == null || !dfa.ok()) {
            return null;
        }
        return new GroupZeroForwardCursor(prog, dfa, dfa.fixedDistanceByteCandidates());
    }

    @SuppressWarnings("UnusedException")
    static long searchGroupZeroForward(
            GroupZeroForwardCursor cursor,
            Slice context,
            int contextStart,
            int contextEnd,
            int start)
    {
        requireNonNull(cursor, "cursor is null");
        requireNonNull(context, "context is null");
        if (contextStart < 0 || contextEnd < contextStart || contextEnd > context.length() ||
                start < contextStart || start > contextEnd) {
            return SEARCH_NO_MATCH;
        }

        int contextOffset = context.byteArrayOffset();
        int contextBegin = contextOffset + contextStart;
        int contextLimit = contextOffset + contextEnd;
        int textBegin = contextOffset + start;
        boolean requiresExclusiveSearch = false;
        long observedCacheVersion = 0;
        while (true) {
            boolean exclusiveSearch = requiresExclusiveSearch;
            if (!exclusiveSearch) {
                observedCacheVersion = cursor.dfa.cacheVersion();
            }
            CacheTables tables = cursor.dfa.beginSearch(exclusiveSearch);
            if (exclusiveSearch && cursor.dfa.cacheVersion() != observedCacheVersion) {
                cursor.dfa.cancelExclusiveSearch();
                requiresExclusiveSearch = false;
                continue;
            }
            try {
                return searchForwardForCount(
                        cursor,
                        cursor.dfa,
                        tables,
                        cursor.prog,
                        context.byteArray(),
                        contextBegin,
                        contextLimit,
                        textBegin,
                        cursor.fixedDistanceByteCandidates);
            }
            catch (StartStateAllocationException | QuitSearchException ignored) {
                return SEARCH_FAILED;
            }
            catch (RetrySearchException ignored) {
                if (exclusiveSearch) {
                    throw new AssertionError("exclusive group-zero forward search requested a retry");
                }
                requiresExclusiveSearch = true;
            }
            finally {
                cursor.dfa.endSearch(exclusiveSearch);
            }
        }
    }

    static CandidateStartCursor createCandidateStartCursor(Prog prog)
    {
        requireNonNull(prog, "prog is null");
        DfaInstance dfa = prog.getCachedDfa(DfaInstance.Kind.FIRST_MATCH);
        if (dfa == null || !dfa.ok() || !dfa.canStartByteAcceleration()) {
            return null;
        }
        return new CandidateStartCursor(dfa);
    }

    /// Returns a candidate cursor whose candidates are the occurrences of the program's
    /// transparent start literal (see [Prog#buildTransparentStartLiteral]), or `null` when the
    /// program has none or its DFA is unavailable. Unlike [#createCandidateStartCursor], it needs
    /// no start byte set, which a leading assertion leaves the program without. With a
    /// `foldedStartRun` behind the literal, which the pattern keeps, the cursor scans for the run
    /// instead, and its candidates are the starts each occurrence of the run allows that hold the
    /// literal.
    static CandidateStartCursor createLiteralCandidateStartCursor(Prog prog, Prog.FoldedStartRun foldedStartRun)
    {
        requireNonNull(prog, "prog is null");
        byte[] literal = prog.transparentStartLiteral();
        if (literal == null) {
            return null;
        }
        DfaInstance dfa = prog.getCachedDfa(DfaInstance.Kind.FIRST_MATCH);
        if (dfa == null || !dfa.ok()) {
            return null;
        }
        return new CandidateStartCursor(dfa, literal, prog.transparentStartLiteralSearch(), foldedStartRun);
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: this cursor amortizes per-search setup and
    // boundary discovery across dense repeated matches. Keep candidate scanning and
    // anchored verification in this concrete loop, and preserve the linear work budget.
    static long searchCandidateBoundary(
            CandidateStartCursor cursor,
            Slice context,
            int contextStart,
            int contextEnd,
            int start)
    {
        requireNonNull(cursor, "cursor is null");
        requireNonNull(context, "context is null");
        if (cursor.disabled ||
                contextStart < 0 || contextEnd < contextStart || contextEnd > context.length() ||
                start < contextStart || start > contextEnd) {
            return CANDIDATE_SEARCH_FALLBACK;
        }

        byte[] bytes = context.byteArray();
        int contextBegin = context.byteArrayOffset() + contextStart;
        int contextLimit = context.byteArrayOffset() + contextEnd;
        int searchBegin = context.byteArrayOffset() + start;
        boolean searchesLiteral = cursor.literal != null;
        if (searchesLiteral) {
            // A shorter window takes the matcher's other routes and leaves the cursor enabled for
            // longer ones.
            if (contextEnd - start < MINIMUM_LITERAL_CANDIDATE_SEARCH_BYTES) {
                return CANDIDATE_SEARCH_FALLBACK;
            }
        }
        else if (!cursor.isProductive(bytes, searchBegin, contextLimit)) {
            cursor.disabled = true;
            cursor.fallbackCount++;
            return CANDIDATE_SEARCH_FALLBACK;
        }

        boolean requiresExclusiveSearch = false;
        long observedCacheVersion = 0;
        while (true) {
            boolean exclusiveSearch = requiresExclusiveSearch;
            if (!exclusiveSearch) {
                observedCacheVersion = cursor.dfa.cacheVersion();
            }
            CacheTables tables = cursor.dfa.beginSearch(exclusiveSearch);
            if (exclusiveSearch && cursor.dfa.cacheVersion() != observedCacheVersion) {
                cursor.dfa.cancelExclusiveSearch();
                requiresExclusiveSearch = false;
                continue;
            }
            try {
                long result;
                if (!searchesLiteral) {
                    result = searchCandidateBoundary(cursor, tables, bytes, contextBegin, contextLimit, searchBegin);
                }
                else if (cursor.foldedStartRun == null) {
                    result = searchLiteralCandidateBoundary(cursor, tables, bytes, contextBegin, contextLimit, searchBegin);
                }
                else {
                    result = searchFoldedRunCandidateBoundary(cursor, tables, bytes, contextBegin, contextLimit, searchBegin);
                }
                if (result == CANDIDATE_SEARCH_FALLBACK) {
                    cursor.disabled = true;
                    cursor.fallbackCount++;
                }
                else if (result >= 0) {
                    cursor.routeCount++;
                }
                return result;
            }
            catch (StartStateAllocationException ignored) {
                cursor.disabled = true;
                cursor.fallbackCount++;
                return CANDIDATE_SEARCH_FALLBACK;
            }
            catch (QuitSearchException ignored) {
                // The text, not the cache, stopped the DFA, so the cursor stays enabled.
                cursor.fallbackCount++;
                return CANDIDATE_SEARCH_QUIT;
            }
            catch (RetrySearchException ignored) {
                if (exclusiveSearch) {
                    cursor.disabled = true;
                    cursor.fallbackCount++;
                    return CANDIDATE_SEARCH_FALLBACK;
                }
                requiresExclusiveSearch = true;
            }
            finally {
                cursor.dfa.endSearch(exclusiveSearch);
            }
        }
    }

    private static long searchCandidateBoundary(
            CandidateStartCursor cursor,
            CacheTables tables,
            byte[] bytes,
            int contextBegin,
            int contextEnd,
            int searchBegin)
    {
        int scanBegin = searchBegin;
        while (scanBegin < contextEnd) {
            int scanLength = cursor.workBoundedScanLength(contextEnd - scanBegin);
            if (scanLength == 0) {
                return CANDIDATE_SEARCH_FALLBACK;
            }
            int candidate = cursor.dfa.findStartByteCandidate(bytes, scanBegin, scanLength);
            int scanEnd = candidate < 0 ? scanBegin + scanLength : candidate + 1;
            if (!cursor.consumeWork(scanEnd - scanBegin)) {
                return CANDIDATE_SEARCH_FALLBACK;
            }
            if (candidate < 0) {
                return scanEnd == contextEnd ? SEARCH_NO_MATCH : CANDIDATE_SEARCH_FALLBACK;
            }
            if (!cursor.consumeWork()) {
                return CANDIDATE_SEARCH_FALLBACK;
            }

            int matchEnd = searchAnchoredCandidate(cursor, tables, bytes, contextBegin, contextEnd, candidate);
            tables = cursor.dfa.tablesAfterWrite(tables);
            if (matchEnd == CANDIDATE_FALLBACK) {
                return CANDIDATE_SEARCH_FALLBACK;
            }
            if (matchEnd >= 0) {
                int relativeStart = candidate - contextBegin;
                int relativeEnd = matchEnd - contextBegin;
                return ((long) relativeStart << 32) | (relativeEnd & 0xFFFF_FFFFL);
            }
            scanBegin = candidate + 1;
        }
        return SEARCH_NO_MATCH;
    }

    // The sibling of the start byte loop above for a cursor fed by the transparent start literal,
    // kept apart so that loop gains no branch. Every match begins with the literal, so the first
    // occurrence that verifies is the leftmost match, and it starts at the occurrence. The anchored
    // verification computes its start state from the byte before the occurrence, which judges a
    // leading assertion as a search that read every byte would. Work is charged as above.
    private static long searchLiteralCandidateBoundary(
            CandidateStartCursor cursor,
            CacheTables tables,
            byte[] bytes,
            int contextBegin,
            int contextEnd,
            int searchBegin)
    {
        int scanBegin = searchBegin;
        while (scanBegin < contextEnd) {
            int scanLength = cursor.workBoundedScanLength(contextEnd - scanBegin);
            if (scanLength == 0) {
                return CANDIDATE_SEARCH_FALLBACK;
            }
            int candidate = Prog.findLiteral(bytes, scanBegin, scanLength, cursor.literal, cursor.literalSearch);
            int scanEnd = candidate < 0 ? scanBegin + scanLength : candidate + 1;
            if (!cursor.consumeWork(scanEnd - scanBegin)) {
                return CANDIDATE_SEARCH_FALLBACK;
            }
            if (candidate < 0) {
                return scanEnd == contextEnd ? SEARCH_NO_MATCH : CANDIDATE_SEARCH_FALLBACK;
            }
            if (!cursor.consumeWork()) {
                return CANDIDATE_SEARCH_FALLBACK;
            }

            int matchEnd = searchAnchoredCandidate(cursor, tables, bytes, contextBegin, contextEnd, candidate);
            tables = cursor.dfa.tablesAfterWrite(tables);
            if (matchEnd == CANDIDATE_FALLBACK) {
                return CANDIDATE_SEARCH_FALLBACK;
            }
            if (matchEnd >= 0) {
                int relativeStart = candidate - contextBegin;
                int relativeEnd = matchEnd - contextBegin;
                return ((long) relativeStart << 32) | (relativeEnd & 0xFFFF_FFFFL);
            }
            scanBegin = candidate + 1;
        }
        return SEARCH_NO_MATCH;
    }

    // The sibling of the literal loop above for a cursor fed by a folded run behind the literal.
    // A match that starts at s holds the run at some h from s + minimumLead through
    // s + maximumLead, so the scan from searchBegin + minimumLead finds the run of every match
    // that starts at or after searchBegin, and a run found at h can belong only to a match that
    // starts from h - maximumLead through h - minimumLead. Every match also begins with the
    // literal, so a start that does not hold it is ruled out unverified: an anchored verification
    // there could only fail, and in a DFA that quits on a byte outside ASCII, one after such a byte
    // would quit and count toward the matcher's quit streak. The starts that hold the literal are
    // verified in ascending order, each at most once, so the first that verifies is the leftmost
    // match. As above, each verification computes its start state from the byte before its start.
    // Work is charged as above, one unit per verified start beyond the bytes scanned, and the
    // anchored verification charges its own transitions. The compares with the literal, of at most
    // 64 bytes, go uncharged: a lead's slack of at most eight bytes allows at most nine starts per
    // occurrence of the run.
    private static long searchFoldedRunCandidateBoundary(
            CandidateStartCursor cursor,
            CacheTables tables,
            byte[] bytes,
            int contextBegin,
            int contextEnd,
            int searchBegin)
    {
        cursor.foldedRunSearchCount++;
        Prog.FoldedStartRun run = cursor.foldedStartRun;
        int minimumLead = run.minimumLead();
        int maximumLead = run.maximumLead();
        byte[] literal = cursor.literal;
        // The first start neither verified nor ruled out.
        int nextStart = searchBegin;
        int scanBegin = searchBegin + minimumLead;
        while (scanBegin < contextEnd) {
            int scanLength = cursor.workBoundedScanLength(contextEnd - scanBegin);
            if (scanLength == 0) {
                return CANDIDATE_SEARCH_FALLBACK;
            }
            int found = run.find(bytes, scanBegin, scanLength);
            int scanEnd = found < 0 ? scanBegin + scanLength : found + 1;
            if (!cursor.consumeWork(scanEnd - scanBegin)) {
                return CANDIDATE_SEARCH_FALLBACK;
            }
            if (found < 0) {
                return scanEnd == contextEnd ? SEARCH_NO_MATCH : CANDIDATE_SEARCH_FALLBACK;
            }

            int lastStart = found - minimumLead;
            for (int candidate = Math.max(nextStart, found - maximumLead); candidate <= lastStart; candidate++) {
                if (candidate > contextEnd - literal.length || !Arrays.equals(bytes, candidate, candidate + literal.length, literal, 0, literal.length)) {
                    continue;
                }
                if (!cursor.consumeWork()) {
                    return CANDIDATE_SEARCH_FALLBACK;
                }
                int matchEnd = searchAnchoredCandidate(cursor, tables, bytes, contextBegin, contextEnd, candidate);
                tables = cursor.dfa.tablesAfterWrite(tables);
                if (matchEnd == CANDIDATE_FALLBACK) {
                    return CANDIDATE_SEARCH_FALLBACK;
                }
                if (matchEnd >= 0) {
                    int relativeStart = candidate - contextBegin;
                    int relativeEnd = matchEnd - contextBegin;
                    return ((long) relativeStart << 32) | (relativeEnd & 0xFFFF_FFFFL);
                }
            }
            nextStart = Math.max(nextStart, lastStart + 1);
            scanBegin = found + 1;
        }
        return SEARCH_NO_MATCH;
    }

    private static int searchAnchoredCandidate(
            CandidateStartCursor cursor,
            CacheTables tables,
            byte[] bytes,
            int contextBegin,
            int contextEnd,
            int candidate)
    {
        DfaInstance dfa = cursor.dfa;
        StateData startData = dfa.analyzeStart(tables, bytes, contextBegin, contextEnd, candidate, contextEnd, true, true);
        tables = dfa.tablesAfterWrite(tables);
        if (startData.offset() == T_DEAD) {
            return CANDIDATE_NO_MATCH;
        }
        if (startData.offset() == T_FULL_MATCH || (startData.flag() & FLAG_MATCH) != 0) {
            return CANDIDATE_FALLBACK;
        }

        int stateOffset = startData.offset();
        int[] transitions = tables.transitions;
        byte[] byteMap = dfa.bytemap;
        int nextSize = dfa.nextSize;
        Object[] stateRefs = dfa.stateReferences(tables, stateOffset);
        int transitionCount = 0;
        int lastMatchBoundary = CANDIDATE_NO_MATCH;

        // The step loop's body never assigns its position, so C2 keeps it a counted loop without a
        // safepoint poll per step. A matching self-loop breaks out to the scan below, and a fresh
        // step loop resumes after the run. Keep the outer `position < contextEnd` condition: without
        // it, C2 no longer counts the step loop.
        int position = candidate;
        while (position < contextEnd) {
            int stepBegin = position;
            int stepLimit = stepBegin + Math.min(contextEnd - stepBegin, MAX_CANDIDATE_START_TRANSITIONS - transitionCount);
            for (; position < stepLimit; position++) {
                if (!cursor.consumeWork()) {
                    return CANDIDATE_FALLBACK;
                }

                int byteClass = byteMap[bytes[position] & 0xFF] & 0xFF;
                Object nextReference = getStateReference(stateRefs, byteClass);
                if (nextReference != null) {
                    stateRefs = (Object[]) nextReference;
                    continue;
                }

                stateOffset = rowStateOffset(stateRefs, nextSize, transitions);
                int nextState = getTransition(transitions, stateOffset + byteClass);
                if (nextState == T_UNCOMPUTED) {
                    nextState = dfa.computeTransition(tables, stateOffset, bytes[position] & 0xFF);
                    tables = dfa.tablesAfterWrite(tables);
                    transitions = tables.transitions;
                    if (nextState == Integer.MIN_VALUE) {
                        return CANDIDATE_FALLBACK;
                    }
                }
                if (nextState == T_DEAD) {
                    return lastMatchBoundary;
                }
                if (nextState == T_FULL_MATCH) {
                    return contextEnd;
                }
                if ((nextState & T_MATCH_BIT) != 0) {
                    lastMatchBoundary = position;
                    nextState &= ~T_MATCH_BIT;
                    if (nextState == stateOffset) {
                        break;
                    }
                }

                stateOffset = nextState;
                stateRefs = dfa.stateReferences(tables, stateOffset);
            }
            if (position == stepLimit) {
                transitionCount += position - stepBegin;
                break;
            }

            // A matching state that loops on itself extends the match by every byte it loops on, so
            // scan that run and charge it as the byte loop would: one unit of work and one transition
            // per byte, within the remaining work and transitions.
            position++;
            transitionCount += position - stepBegin;
            int matchingSelfLoopLimit = position + cursor.workBoundedScanLength(Math.min(contextEnd - position, MAX_CANDIDATE_START_TRANSITIONS - transitionCount));
            int matchingSelfLoopEnd = scanMatchingSelfLoopTransitions(dfa, tables, bytes, position, matchingSelfLoopLimit, stateOffset);
            // Computing the loop's exit bytes may have published grown tables.
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;
            if (matchingSelfLoopEnd != position) {
                if (!cursor.consumeWork(matchingSelfLoopEnd - position)) {
                    return CANDIDATE_FALLBACK;
                }
                dfa.matchingSelfLoopScanCount++;
                transitionCount += matchingSelfLoopEnd - position;
                lastMatchBoundary = matchingSelfLoopEnd - 1;
                position = matchingSelfLoopEnd;
            }
            stateRefs = dfa.stateReferences(tables, stateOffset);
        }

        // Cached object transitions advance the row reference without updating the offset.
        stateOffset = rowStateOffset(stateRefs, nextSize, transitions);
        if (transitionCount == MAX_CANDIDATE_START_TRANSITIONS || !cursor.consumeWork()) {
            return CANDIDATE_FALLBACK;
        }
        int finalState = getTransition(transitions, stateOffset + dfa.bytemapRange);
        if (finalState == T_UNCOMPUTED) {
            finalState = dfa.computeTransition(tables, stateOffset, BYTE_END_TEXT);
            if (finalState == Integer.MIN_VALUE) {
                return CANDIDATE_FALLBACK;
            }
        }
        if (finalState == T_FULL_MATCH || (finalState > 0 && (finalState & T_MATCH_BIT) != 0)) {
            return contextEnd;
        }
        // Alive at the end of the text without a match, this check read every byte a later
        // candidate would, so a long one falls back to the matcher's forward search, which reads
        // those bytes once.
        if (lastMatchBoundary == CANDIDATE_NO_MATCH && contextEnd - candidate >= MINIMUM_UNMATCHED_CANDIDATE_FALLBACK_BYTES) {
            return CANDIDATE_FALLBACK;
        }
        return lastMatchBoundary;
    }

    // Keep fused-count routing separate from the group-zero cursor. The count result carries
    // private scan-rejection and start-byte promotion bits, while adding that result handling to
    // the cursor's generated code measurably regresses short repeated boundary operations.
    private static long searchForwardForCount(
            DfaInstance dfa,
            CacheTables tables,
            Prog prog,
            byte[] bytes,
            int contextBegin,
            int contextEnd,
            int textBegin,
            Prog.FixedDistanceByteCandidates fixedDistanceCandidates,
            int startByteScanSet,
            boolean resampleStartByteScanSet,
            CountByteScanSampling sampling)
    {
        if (prog.anchorStart() && contextBegin != textBegin) {
            return SEARCH_NO_MATCH;
        }

        boolean endMatch = prog.anchorEnd();
        StateData startData = dfa.analyzeStart(tables, bytes, contextBegin, contextEnd, textBegin, contextEnd, false, true);
        tables = dfa.tablesAfterWrite(tables);
        if (startData.offset() == T_DEAD) {
            return SEARCH_NO_MATCH;
        }
        if (startData.offset() == T_FULL_MATCH) {
            return (long) contextEnd - textBegin;
        }

        int startOffset = startData.offset();
        boolean startIsMatch = (startData.flag() & FLAG_MATCH) != 0;
        if (canReturnEmptyAtStart(dfa, bytes, textBegin, contextEnd, startIsMatch, endMatch)) {
            return 0;
        }

        boolean canPrefixAccel = prog.canPrefixAccel() && isContextFreeStart(startData.flag());
        if (canPrefixAccel && prog.canUseSingleBytePrefixAccelFastPath()) {
            return searchForwardPrefixAccelSingleByte(
                    dfa, tables, prog, bytes, textBegin, contextEnd, contextEnd, startOffset, startIsMatch, endMatch, false);
        }
        if (canPrefixAccel) {
            return searchForwardScanAcceleration(
                    dfa,
                    tables,
                    prog,
                    bytes,
                    textBegin,
                    contextEnd,
                    contextEnd,
                    startOffset,
                    startIsMatch,
                    endMatch,
                    false,
                    null,
                    0,
                    PREFIX_SCAN);
        }

        if (contextEnd - textBegin < MIN_SELECTIVE_SCAN_SEARCH_BYTES) {
            fixedDistanceCandidates = null;
        }
        boolean byteScanRejected = false;
        if (fixedDistanceCandidates != null &&
                canUseFixedDistanceByteAcceleration(false, true, startData.flag(), contextEnd - textBegin)) {
            byteScanRejected = BYTE_SCAN_PRODUCTIVITY_CHECK_ENABLED && !(sampling == null
                    ? isFixedDistanceByteScanProductive(dfa, fixedDistanceCandidates.candidates(), bytes, textBegin + fixedDistanceCandidates.offset(), contextEnd)
                    : sampling.isFixedDistanceByteScanProductive(dfa, fixedDistanceCandidates.candidates(), bytes, textBegin + fixedDistanceCandidates.offset(), contextEnd));
            if (!byteScanRejected) {
                int fixedDistanceSmallByteSet = selectByteScanSet(
                        dfa.fixedDistanceSmallByteSet(),
                        fixedDistanceCandidates.candidates(),
                        bytes,
                        textBegin + fixedDistanceCandidates.offset(),
                        contextEnd);
                return searchForwardScanAcceleration(
                        dfa,
                        tables,
                        prog,
                        bytes,
                        textBegin,
                        contextEnd,
                        contextEnd,
                        startOffset,
                        startIsMatch,
                        endMatch,
                        false,
                        fixedDistanceCandidates.candidates(),
                        fixedDistanceSmallByteSet,
                        fixedDistanceCandidates.offset());
            }
        }
        if (canUseStartByteAcceleration(dfa, false, true, startData.flag())) {
            if (BYTE_SCAN_PRODUCTIVITY_CHECK_ENABLED &&
                    !(sampling == null
                            ? dfa.isStartByteScanProductive(bytes, textBegin, contextEnd)
                            : sampling.isStartByteScanProductive(dfa, bytes, textBegin, contextEnd))) {
                byteScanRejected = true;
                dfa.byteScanFallbackCount++;
            }
            else {
                // A scalar choice is resampled only when a search reaches this scan, so searches
                // served by the fixed-distance scan do not pay for a density sample.
                boolean promoted = false;
                if (resampleStartByteScanSet && contextEnd - textBegin >= MIN_SMALL_BYTE_SET_PRODUCTIVITY_CHECK_BYTES) {
                    startByteScanSet = sampling == null
                            ? selectByteScanSet(dfa.startByteScanSet, dfa.startByteCandidates, bytes, textBegin, contextEnd)
                            : sampling.selectStartByteScanSet(dfa, bytes, textBegin, contextEnd);
                    promoted = startByteScanSet != 0;
                }
                long result = searchForwardScanAcceleration(
                        dfa,
                        tables,
                        prog,
                        bytes,
                        textBegin,
                        contextEnd,
                        contextEnd,
                        startOffset,
                        startIsMatch,
                        endMatch,
                        false,
                        dfa.startByteCandidates,
                        startByteScanSet,
                        0);
                if (promoted && result > 0) {
                    return result | COUNT_START_BYTE_SCAN_PROMOTED_FLAG;
                }
                return result;
            }
        }
        else if (byteScanRejected) {
            dfa.byteScanFallbackCount++;
        }

        if (!startIsMatch &&
                contextEnd - textBegin < MIN_PAIRED_SEARCH_BYTES &&
                dfa.shouldUseAbsolutePointersForShortSearch(tables)) {
            tables = dfa.requestAbsolutePointerTransitions(tables);
        }
        // The cutoff is a racy route hint within one count call: a concurrent count of the same DFA
        // can change it, and it only selects whether the equivalent ordinary route runs directly.
        boolean selfLoopScanNotApplicable = false;
        if (!startIsMatch &&
                !endMatch &&
                contextEnd - textBegin >= MIN_COUNT_SELF_LOOP_SCAN_BYTES &&
                VectorSupport.isAvailable() &&
                dfa.countSelfLoopUnproductiveCount < COUNT_SELF_LOOP_UNPRODUCTIVE_LIMIT) {
            long result = searchForwardCountSelfLoopScan(dfa, tables, bytes, textBegin, contextEnd, startOffset);
            if (result != COUNT_SELF_LOOP_SCAN_NOT_APPLICABLE) {
                return countMatchResult(result, byteScanRejected);
            }
            // An exclusive scan may have published grown tables whose states lie beyond these.
            tables = dfa.tablesAfterWrite(tables);
            selfLoopScanNotApplicable = true;
        }
        // The paired request and either search can retry this search, so it counts toward the
        // cutoff only once the search returns, and the retried search counts once.
        if (!startIsMatch && contextEnd - textBegin >= MIN_PAIRED_SEARCH_BYTES) {
            tables = dfa.requestPairedTransitions(tables);
            if (dfa.hasPairedTransitions(tables, startOffset)) {
                long result = FLAT_PAIRED_ROWS
                        ? searchForwardPairsFlat(dfa, tables, bytes, textBegin, textBegin, contextEnd, contextEnd, startOffset, endMatch, false)
                        : searchForwardPairs(dfa, tables, bytes, textBegin, textBegin, contextEnd, contextEnd, startOffset, endMatch, false);
                if (selfLoopScanNotApplicable) {
                    dfa.countSelfLoopUnproductiveCount++;
                }
                return countMatchResult(result, byteScanRejected);
            }
        }
        long result = searchForward(dfa, tables, bytes, textBegin, contextEnd, contextEnd, startOffset, startIsMatch, false, endMatch, false);
        if (selfLoopScanNotApplicable) {
            dfa.countSelfLoopUnproductiveCount++;
        }
        return countMatchResult(result, byteScanRejected);
    }

    // Count searches without an end anchor can skip a self-loop state whose exits are one to three
    // bytes, or every high byte plus up to three ASCII bytes, or whose staying bytes form one to
    // four ranges, using a vector byte-set scan. A matching self-loop extends the pending match
    // through every skipped byte. The start state carries context flags, and UTF-8 characters pass
    // through intermediate states, so a few bytes are stepped through the transition table to reach
    // an eligible loop. The remaining text goes to the ordinary search routes when no eligible
    // state follows, a transition is uncomputed, or repeated short scans show that exits are dense,
    // unless a search without a pending match whose scans have paid re-arms its scan, as the loop
    // below describes.
    private static long searchForwardCountSelfLoopScan(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int textBegin,
            int contextEnd,
            int startOffset)
    {
        byte[] byteMap = dfa.bytemap;
        int position = textBegin;
        int stateOffset = startOffset;
        int lastMatchBoundary = -1;
        long exitByteSet = dfa.countSelfLoopExitByteSet(tables, stateOffset);
        tables = dfa.tablesAfterWrite(tables);
        if (skipsShortStayRun(dfa, tables, bytes, position, contextEnd, stateOffset, exitByteSet)) {
            exitByteSet = 0;
        }
        for (int step = 0; exitByteSet == 0; step++) {
            if (step == COUNT_SELF_LOOP_MAX_STEPS || position == contextEnd) {
                return COUNT_SELF_LOOP_SCAN_NOT_APPLICABLE;
            }
            // Uncomputed, dead, and full-match sentinels are negative and use the ordinary routes.
            int nextState = getTransition(tables.transitions, stateOffset + (byteMap[bytes[position] & 0xFF] & 0xFF));
            if (nextState < 0) {
                return COUNT_SELF_LOOP_SCAN_NOT_APPLICABLE;
            }
            if ((nextState & T_MATCH_BIT) != 0) {
                lastMatchBoundary = position;
                nextState &= ~T_MATCH_BIT;
            }
            position++;
            stateOffset = nextState;
            exitByteSet = dfa.countSelfLoopExitByteSet(tables, stateOffset);
            tables = dfa.tablesAfterWrite(tables);
            if (skipsShortStayRun(dfa, tables, bytes, position, contextEnd, stateOffset, exitByteSet)) {
                exitByteSet = 0;
            }
        }

        // Whether a scan followed an exit-form loop or skipped a stay-form run of at least
        // SHORT_SCAN_BYTES. Stay-form loops such as the words and gaps of `[a-zA-Z]+ing` over prose
        // leave after a few bytes, and a count whose searches only scan such runs steps their
        // first bytes through this loop's slower transitions on every search.
        boolean productive = false;
        int shortScanCount = 0;
        // The stretch the last re-arm requested, zero before the first, the position where it
        // began, the bytes the scans have skipped since, and the bytes they have skipped in the
        // whole search.
        int rearmBytes = 0;
        int rearmPosition = textBegin;
        long skippedBytes = 0;
        long totalSkippedBytes = 0;
        scan:
        while (true) {
            int exitPosition = findSelfLoopExit(dfa, bytes, position, contextEnd, exitByteSet);
            if (exitPosition < 0) {
                exitPosition = contextEnd;
            }
            skippedBytes += exitPosition - position;
            totalSkippedBytes += exitPosition - position;
            dfa.countSelfLoopScanCount++;
            productive |= exitByteSet > 0 || exitPosition - position >= VectorByteSetScanner.SHORT_SCAN_BYTES;
            // Each skipped byte of a matching self-loop records a match ending before that byte.
            if (isMatchingSelfLoop(exitByteSet) && exitPosition > position) {
                lastMatchBoundary = exitPosition - 1;
            }
            shortScanCount = VectorByteSetScanner.shortScanCount(shortScanCount, exitPosition - position);
            position = exitPosition;
            if (position == contextEnd) {
                break;
            }

            // After the short-scan limit, or when the steps after an exit reach no eligible loop, a
            // search without a pending match whose scans have skipped at least half of its bytes,
            // with at least 256 bytes left, as the hand-offs need, steps a stretch of bytes and
            // re-arms the scan. A pending first match keeps the ordinary continuation loops, and a
            // search whose exits are dense the faster pointer or paired loops, so both hand off as
            // before.
            boolean rearm = shortScanCount == VectorByteSetScanner.SHORT_SCAN_LIMIT;
            exitByteSet = 0;
            for (int step = 0; exitByteSet == 0; step++) {
                if (position == contextEnd) {
                    break scan;
                }
                if (rearm || step == COUNT_SELF_LOOP_MAX_STEPS) {
                    if (lastMatchBoundary >= 0 ||
                            contextEnd - position < MIN_PAIRED_SEARCH_BYTES ||
                            2 * totalSkippedBytes < position - textBegin ||
                            (rearmBytes >= COUNT_SELF_LOOP_RECENT_REARM_BYTES && 2 * skippedBytes < position - rearmPosition)) {
                        break scan;
                    }
                    rearmPosition = position;
                    rearmBytes = rearmBytes == 0 || 4 * skippedBytes >= rearmBytes ? COUNT_SELF_LOOP_REARM_BYTES : Math.min(2 * rearmBytes, COUNT_SELF_LOOP_MAX_REARM_BYTES);
                    long stepped = stepSelfLoopRearmBytes(tables.transitions, byteMap, bytes, position, Math.min(contextEnd - position, rearmBytes), stateOffset);
                    position = (int) (stepped >>> 32);
                    stateOffset = (int) stepped;
                    dfa.countSelfLoopRearmCount++;
                    rearm = false;
                    step = 0;
                    shortScanCount = 0;
                    skippedBytes = 0;
                    if (position == contextEnd) {
                        break scan;
                    }
                }
                int nextState = getTransition(tables.transitions, stateOffset + (byteMap[bytes[position] & 0xFF] & 0xFF));
                if (nextState == T_UNCOMPUTED) {
                    break scan;
                }
                if (nextState == T_DEAD) {
                    recordCountSelfLoopScanProductivity(dfa, productive);
                    return finalizeResult(textBegin, contextEnd, false, lastMatchBoundary >= 0, lastMatchBoundary, true);
                }
                if (nextState == T_FULL_MATCH) {
                    recordCountSelfLoopScanProductivity(dfa, productive);
                    return finalizeResult(textBegin, contextEnd, false, true, contextEnd, true);
                }
                if ((nextState & T_MATCH_BIT) != 0) {
                    lastMatchBoundary = position;
                    nextState &= ~T_MATCH_BIT;
                }
                position++;
                stateOffset = nextState;
                if (position < contextEnd) {
                    exitByteSet = dfa.countSelfLoopExitByteSet(tables, stateOffset);
                    tables = dfa.tablesAfterWrite(tables);
                    if (skipsShortStayRun(dfa, tables, bytes, position, contextEnd, stateOffset, exitByteSet)) {
                        exitByteSet = 0;
                    }
                }
            }
        }

        // Without a pending match the search continues as a pending first match does. An existing
        // pointer table is used; only a productive search requests one, and an unproductive search
        // requests paired rows instead. A productive search takes paired rows only when the DFA
        // holds or has requested them or cannot allocate a pointer table: the scan computes every
        // transition of the states it visits, so a paired request from here selects rows the text
        // never exercised, and those rows block the pointer table, the faster loop on dense
        // multibyte text. Either request and every continuation search can retry this search, so
        // each hand-off records the search's productivity after its search returns, and the
        // retried search counts once.
        if (lastMatchBoundary < 0 && contextEnd - position >= MIN_PAIRED_SEARCH_BYTES) {
            if (productive && !dfa.hasAbsolutePointerTransitions(tables, stateOffset) && !countHandoffKeepsPairedRows(dfa, tables)) {
                tables = dfa.requestAbsolutePointerTransitions(tables);
            }
            if (dfa.hasAbsolutePointerTransitions(tables, stateOffset)) {
                long result = searchForwardAbsolutePointers(dfa, tables, bytes, textBegin, position, contextEnd, contextEnd, stateOffset, -1, false, false);
                recordCountSelfLoopScanProductivity(dfa, productive);
                dfa.countSelfLoopNoMatchPointerHandOffCount++;
                return result;
            }
            if (!productive || countHandoffKeepsPairedRows(dfa, tables)) {
                tables = dfa.requestPairedTransitions(tables);
                if (dfa.hasPairedTransitions(tables, stateOffset)) {
                    // Measure from the search start so the short-search hint sees the whole search.
                    long result = FLAT_PAIRED_ROWS
                            ? searchForwardPairsFlat(dfa, tables, bytes, textBegin, position, contextEnd, contextEnd, stateOffset, false, false)
                            : searchForwardPairs(dfa, tables, bytes, textBegin, position, contextEnd, contextEnd, stateOffset, false, false);
                    recordCountSelfLoopScanProductivity(dfa, productive);
                    return result;
                }
            }
        }
        // A pending first match continues through the absolute-pointer loop the ordinary search
        // uses. The compact loop exits its fast path on every match transition, which is every
        // transition of a matching loop. Paired rows block pointer-table allocation, so a DFA that
        // already holds or has requested paired rows keeps the paired after-match loop, as the
        // ordinary search does, and so does a DFA that cannot allocate a pointer table. As without a
        // pending match, an existing pointer table is used; only a productive search requests one,
        // and an unproductive search requests paired rows instead.
        if (lastMatchBoundary >= 0 &&
                dfa.kind == DfaInstance.Kind.FIRST_MATCH &&
                contextEnd - position >= MIN_PAIRED_SEARCH_BYTES) {
            if (productive && !dfa.hasAbsolutePointerTransitions(tables, stateOffset) && !countHandoffKeepsPairedRows(dfa, tables)) {
                tables = dfa.requestAbsolutePointerTransitions(tables);
            }
            if (dfa.hasAbsolutePointerTransitions(tables, stateOffset)) {
                long result = searchForwardAbsolutePointers(
                        dfa,
                        tables,
                        bytes,
                        textBegin,
                        position,
                        contextEnd,
                        contextEnd,
                        stateOffset,
                        lastMatchBoundary,
                        false,
                        false);
                recordCountSelfLoopScanProductivity(dfa, productive);
                dfa.countSelfLoopMatchHandOffCount++;
                return result;
            }
            if (!productive || countHandoffKeepsPairedRows(dfa, tables)) {
                tables = dfa.requestPairedTransitions(tables);
                if (dfa.hasPairedTransitions(tables, stateOffset)) {
                    long result = FLAT_PAIRED_ROWS
                            ? searchForwardPairsFlatFromState(dfa, tables, bytes, textBegin, position, contextEnd, contextEnd, stateOffset, lastMatchBoundary, false, false)
                            : searchForwardPairsFromState(dfa, tables, bytes, textBegin, position, contextEnd, contextEnd, stateOffset, lastMatchBoundary, false, false);
                    recordCountSelfLoopScanProductivity(dfa, productive);
                    dfa.countSelfLoopMatchHandOffCount++;
                    return result;
                }
            }
        }
        long result = searchForwardContinuation(
                dfa,
                tables,
                bytes,
                textBegin,
                position,
                contextEnd,
                contextEnd,
                stateOffset,
                false,
                lastMatchBoundary,
                false,
                false);
        recordCountSelfLoopScanProductivity(dfa, productive);
        return result;
    }

    // Steps at most `length` bytes from position through the transition table, from stateOffset,
    // for a count self-loop scan that re-arms, stopping before a transition that is uncomputed,
    // dead, full-match, or matching, which the caller's own steps handle. Returns the position and
    // state reached as position << 32 | state.
    private static long stepSelfLoopRearmBytes(int[] transitions, byte[] byteMap, byte[] bytes, int position, int length, int stateOffset)
    {
        int end = position + length;
        for (; position < end; position++) {
            int nextState = getTransition(transitions, stateOffset + (byteMap[bytes[position] & 0xFF] & 0xFF));
            if ((nextState & (Integer.MIN_VALUE | T_MATCH_BIT)) != 0) {
                break;
            }
            stateOffset = nextState;
        }
        return ((long) position << 32) | stateOffset;
    }

    // A productive search clears the count's cutoff, and an unproductive one counts toward it as a
    // search that reached no eligible loop does. The guard skips writing a shared field that is
    // already zero.
    private static void recordCountSelfLoopScanProductivity(DfaInstance dfa, boolean productive)
    {
        if (!productive) {
            dfa.countSelfLoopUnproductiveCount++;
        }
        else if (dfa.countSelfLoopUnproductiveCount != 0) {
            dfa.countSelfLoopUnproductiveCount = 0;
        }
    }

    // Whether the count scan steps past the eligible self-loop at stateOffset, as past an
    // ineligible one and within the same step limit, rather than scanning its run from position:
    // a loop in the stay form whose run is shorter than two bytes, since a transition or two costs
    // less than a scan's setup. The stay form makes eligible states whose runs are almost always
    // that short: the unanchored start state of a UTF-8 `.*` pattern, which stays only on newlines
    // and invalid bytes, so that a count over lines would start each search with a one-byte scan,
    // and the states inside a multibyte character. The exit form keeps scanning every run, whose
    // short scans trigger the density decision VectorByteSetScanner.SHORT_SCAN_LIMIT describes. An
    // accepted exit set means every transition of the state that can stay is computed.
    private static boolean skipsShortStayRun(DfaInstance dfa, CacheTables tables, byte[] bytes, int position, int contextEnd, int stateOffset, long exitByteSet)
    {
        if (exitByteSet >= 0 || position == contextEnd) {
            return false;
        }
        int[] transitions = tables.transitions;
        byte[] byteMap = dfa.bytemap;
        return (getTransition(transitions, stateOffset + (byteMap[bytes[position] & 0xFF] & 0xFF)) & ~T_MATCH_BIT) != stateOffset ||
                position + 1 == contextEnd ||
                (getTransition(transitions, stateOffset + (byteMap[bytes[position + 1] & 0xFF] & 0xFF)) & ~T_MATCH_BIT) != stateOffset;
    }

    // Whether a count self-loop scan hands off to paired rows rather than the absolute-pointer
    // loop: the DFA cannot allocate a pointer table or has requested paired rows, or these tables
    // hold paired rows, which block one.
    private static boolean countHandoffKeepsPairedRows(DfaInstance dfa, CacheTables tables)
    {
        return dfa.absolutePointerTransitionsUnavailable || dfa.pairedTransitionsRequested || tables.hasPairedRows();
    }

    private static long countMatchResult(long result, boolean byteScanRejected)
    {
        if (byteScanRejected && result > 0) {
            return result | COUNT_SCAN_REJECTED_MATCH_FLAG;
        }
        return result;
    }

    private static long searchForwardForCount(
            GroupZeroForwardCursor cursor,
            DfaInstance dfa,
            CacheTables tables,
            Prog prog,
            byte[] bytes,
            int contextBegin,
            int contextEnd,
            int textBegin,
            Prog.FixedDistanceByteCandidates fixedDistanceCandidates)
    {
        if (prog.anchorStart() && contextBegin != textBegin) {
            return SEARCH_NO_MATCH;
        }

        boolean endMatch = prog.anchorEnd();
        StateData startData = dfa.analyzeStart(tables, bytes, contextBegin, contextEnd, textBegin, contextEnd, false, true);
        tables = dfa.tablesAfterWrite(tables);
        if (startData.offset() == T_DEAD) {
            return SEARCH_NO_MATCH;
        }
        if (startData.offset() == T_FULL_MATCH) {
            return (long) contextEnd - textBegin;
        }

        int startOffset = startData.offset();
        boolean startIsMatch = (startData.flag() & FLAG_MATCH) != 0;
        if (canReturnEmptyAtStart(dfa, bytes, textBegin, contextEnd, startIsMatch, endMatch)) {
            return 0;
        }

        boolean canPrefixAccel = prog.canPrefixAccel() && isContextFreeStart(startData.flag());
        if (canPrefixAccel && prog.canUseSingleBytePrefixAccelFastPath()) {
            return searchForwardPrefixAccelSingleByte(
                    dfa, tables, prog, bytes, textBegin, contextEnd, contextEnd, startOffset, startIsMatch, endMatch, false);
        }
        if (canPrefixAccel) {
            return searchForwardScanAcceleration(
                    dfa,
                    tables,
                    prog,
                    bytes,
                    textBegin,
                    contextEnd,
                    contextEnd,
                    startOffset,
                    startIsMatch,
                    endMatch,
                    false,
                    null,
                    0,
                    PREFIX_SCAN);
        }

        if (contextEnd - textBegin < MIN_SELECTIVE_SCAN_SEARCH_BYTES) {
            fixedDistanceCandidates = null;
        }
        boolean fixedDistanceScanRejected = false;
        if (fixedDistanceCandidates != null &&
                canUseFixedDistanceByteAcceleration(false, true, startData.flag(), contextEnd - textBegin)) {
            fixedDistanceScanRejected = BYTE_SCAN_PRODUCTIVITY_CHECK_ENABLED && !(cursor == null
                    ? isFixedDistanceByteScanProductive(
                    dfa,
                    fixedDistanceCandidates.candidates(),
                    bytes,
                    textBegin + fixedDistanceCandidates.offset(),
                    contextEnd)
                    : cursor.isFixedDistanceByteScanProductive(
                    fixedDistanceCandidates.candidates(),
                    bytes,
                    textBegin + fixedDistanceCandidates.offset(),
                    contextEnd));
            if (!fixedDistanceScanRejected) {
                int fixedDistanceSmallByteSet = selectByteScanSet(
                        dfa.fixedDistanceSmallByteSet(),
                        fixedDistanceCandidates.candidates(),
                        bytes,
                        textBegin + fixedDistanceCandidates.offset(),
                        contextEnd);
                return searchForwardScanAcceleration(
                        dfa,
                        tables,
                        prog,
                        bytes,
                        textBegin,
                        contextEnd,
                        contextEnd,
                        startOffset,
                        startIsMatch,
                        endMatch,
                        false,
                        fixedDistanceCandidates.candidates(),
                        fixedDistanceSmallByteSet,
                        fixedDistanceCandidates.offset());
            }
        }
        if (canUseStartByteAcceleration(dfa, false, true, startData.flag())) {
            if (BYTE_SCAN_PRODUCTIVITY_CHECK_ENABLED &&
                    !(cursor == null
                            ? dfa.isStartByteScanProductive(bytes, textBegin, contextEnd)
                            : cursor.isStartByteScanProductive(bytes, textBegin, contextEnd))) {
                dfa.byteScanFallbackCount++;
            }
            else {
                int startByteScanSet = selectByteScanSet(
                        dfa.startByteScanSet,
                        dfa.startByteCandidates,
                        bytes,
                        textBegin,
                        contextEnd);
                return searchForwardScanAcceleration(
                        dfa,
                        tables,
                        prog,
                        bytes,
                        textBegin,
                        contextEnd,
                        contextEnd,
                        startOffset,
                        startIsMatch,
                        endMatch,
                        false,
                        dfa.startByteCandidates,
                        startByteScanSet,
                        0);
            }
        }
        else if (fixedDistanceScanRejected) {
            dfa.byteScanFallbackCount++;
        }

        if (!startIsMatch &&
                contextEnd - textBegin < MIN_PAIRED_SEARCH_BYTES &&
                dfa.shouldUseAbsolutePointersForShortSearch(tables)) {
            tables = dfa.requestAbsolutePointerTransitions(tables);
        }
        if (!startIsMatch && contextEnd - textBegin >= MIN_PAIRED_SEARCH_BYTES) {
            tables = dfa.requestPairedTransitions(tables);
            if (dfa.hasPairedTransitions(tables, startOffset)) {
                return FLAT_PAIRED_ROWS
                        ? searchForwardPairsFlat(dfa, tables, bytes, textBegin, textBegin, contextEnd, contextEnd, startOffset, endMatch, false)
                        : searchForwardPairs(dfa, tables, bytes, textBegin, textBegin, contextEnd, contextEnd, startOffset, endMatch, false);
            }
        }
        return searchForward(dfa, tables, bytes, textBegin, contextEnd, contextEnd, startOffset, startIsMatch, false, endMatch, false);
    }

    /// Whether a forward search may skip ahead from a start state with `startFlags`, by the prefix
    /// scan or a start-byte or fixed-distance byte scan. Each skip lands in the same start state at
    /// a later position, so the state must hold nothing about the position it was built for: no
    /// assertion it still needs, no assertion that held there, and no record of the byte before it.
    /// A start state that needs no assertion keeps only its match bit (see
    /// `workQueueToCachedState`), so the test is that no other bit is set. A start state that
    /// needs `\b` fails it wherever the search starts, so a skip never judges the boundary by the
    /// byte before the search rather than the byte before the landing. The test guards routes no
    /// current scan takes: a prefix scan is configured only for a prefix that crosses no assertion,
    /// and a program that meets an assertion before its first byte has no start-byte candidates
    /// (`Prog.buildStartByteCandidates`), which the fixed-distance candidates are built from.
    static boolean isContextFreeStart(int startFlags)
    {
        return (startFlags & ~FLAG_MATCH) == 0;
    }

    static boolean canUseStartByteAcceleration(DfaInstance dfa, boolean anchored, boolean runForward, int startFlags)
    {
        return runForward && !anchored && dfa.canStartByteAcceleration() && isContextFreeStart(startFlags);
    }

    static boolean canUseFixedDistanceByteAcceleration(boolean anchored, boolean runForward, int startFlags, int searchLength)
    {
        return searchLength >= MIN_SELECTIVE_SCAN_SEARCH_BYTES &&
                runForward &&
                !anchored &&
                isContextFreeStart(startFlags);
    }

    static int selectByteScanSet(int byteScanSet, byte[] candidates, byte[] bytes, int position, int textEnd)
    {
        // On long inputs the start-scan productivity check samples the filter's own candidates,
        // and a single-byte scan would stop at every start byte the filter skips.
        if (byteScanSet == START_LITERAL_FILTER_MARKER || byteScanSet == START_CLASS_FILTER_MARKER) {
            return byteScanSet;
        }
        if (byteScanSet != 0 &&
                VectorSupport.isAvailable() &&
                ((byteScanSet >>> 24) > 3 || VectorByteSetScanner.shouldSampleCandidateDensity()) &&
                !isByteCandidateScanProductive(
                        candidates,
                        null,
                        null,
                        bytes,
                        position,
                        textEnd,
                        MIN_SMALL_BYTE_SET_PRODUCTIVITY_CHECK_BYTES,
                        SMALL_BYTE_SET_SCAN_SAMPLE_CANDIDATE_LIMIT)) {
            return 0;
        }
        return byteScanSet;
    }

    // A vector choice made at the count start is kept for the whole count. Start bytes such as
    // digits cluster, and a 64-byte sample can land on one cluster and keep a sparse text on the
    // scalar scan for every byte, so a count of every match settles a borderline sample with a
    // wider one. A clearly dense sample keeps the scalar scan without it, since dense text pays
    // every extra sampled byte.
    static int selectEveryMatchCountByteScanSet(int byteScanSet, byte[] candidates, byte[] bytes, int position, int textEnd)
    {
        int selected = selectByteScanSet(byteScanSet, candidates, bytes, position, textEnd);
        if (selected != 0 ||
                byteScanSet == 0 ||
                !isByteCandidateScanProductive(
                        candidates,
                        null,
                        null,
                        bytes,
                        position,
                        textEnd,
                        MIN_SMALL_BYTE_SET_PRODUCTIVITY_CHECK_BYTES,
                        COUNT_START_BORDERLINE_CANDIDATE_LIMIT)) {
            return selected;
        }
        return countStartSampleCandidates(candidates, bytes, position, textEnd) < COUNT_START_SAMPLE_CANDIDATE_LIMIT ? byteScanSet : 0;
    }

    private static int countStartSampleCandidates(byte[] candidates, byte[] bytes, int position, int textEnd)
    {
        int windowSize = COUNT_START_SAMPLE_SIZE / COUNT_START_SAMPLE_WINDOWS;
        int windowRange = textEnd - position - windowSize;
        int candidateCount = 0;
        for (int window = 0; window < COUNT_START_SAMPLE_WINDOWS; window++) {
            int windowStart = position + (int) (((long) windowRange * window) / (COUNT_START_SAMPLE_WINDOWS - 1));
            for (int samplePosition = windowStart; samplePosition < windowStart + windowSize; samplePosition++) {
                if (candidates[bytes[samplePosition] & 0xFF] != 0 && ++candidateCount >= COUNT_START_SAMPLE_CANDIDATE_LIMIT) {
                    return candidateCount;
                }
            }
        }
        return candidateCount;
    }

    private static boolean isByteCandidateScanProductive(byte[] candidates, byte[] bytes, int position, int textEnd)
    {
        return isByteCandidateScanProductive(candidates, null, null, bytes, position, textEnd, MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES, BYTE_SCAN_SAMPLE_CANDIDATE_LIMIT);
    }

    // The productivity check samples the fixed-distance scan only from its minimum. Below the
    // sampling minimum, prefer a start literal or class filter when its estimated stop density on
    // English text is lower than the fixed-distance bytes' probability. The count sampling and the
    // group-zero cursor apply the same rule.
    private static boolean isFixedDistanceByteScanProductive(DfaInstance dfa, byte[] candidates, byte[] bytes, int position, int textEnd)
    {
        if (textEnd - position < MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES) {
            return !dfa.fixedDistanceByteScanDenserThanStartFilter;
        }
        return isByteCandidateScanProductive(candidates, bytes, position, textEnd);
    }

    // With a start literal filter or a start class filter, a sampled position counts when the
    // filter accepts it, not when its byte is in the candidate set. The literal-alternation
    // matcher samples its own filter's stops with this check.
    static boolean isByteCandidateScanProductive(
            byte[] candidates,
            StartLiteralFilter literalFilter,
            StartClassFilter classFilter,
            byte[] bytes,
            int position,
            int textEnd,
            int minimumSearchLength,
            int candidateLimit)
    {
        int searchLength = textEnd - position;
        if (searchLength < minimumSearchLength) {
            return true;
        }

        int windowSize = BYTE_SCAN_SAMPLE_SIZE / BYTE_SCAN_SAMPLE_WINDOWS;
        int windowRange = searchLength - windowSize;
        int candidateCount = 0;
        for (int window = 0; window < BYTE_SCAN_SAMPLE_WINDOWS; window++) {
            int windowStart = position + (int) (((long) windowRange * window) / (BYTE_SCAN_SAMPLE_WINDOWS - 1));
            int windowEnd = windowStart + windowSize;
            for (int samplePosition = windowStart; samplePosition < windowEnd; samplePosition++) {
                boolean candidate;
                if (literalFilter != null) {
                    candidate = literalFilter.accepts(bytes, samplePosition, textEnd);
                }
                else if (classFilter != null) {
                    candidate = classFilter.accepts(bytes, samplePosition, textEnd);
                }
                else {
                    candidate = candidates[bytes[samplePosition] & 0xFF] != 0;
                }
                if (candidate && ++candidateCount >= candidateLimit) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean canReturnEmptyAtStart(DfaInstance dfa, byte[] bytes, int position, int textEnd, boolean startIsMatch, boolean endMatch)
    {
        return startIsMatch &&
                !endMatch &&
                dfa.canUseNullableStartByteCheck() &&
                (position == textEnd || !dfa.isStartByteCandidate(bytes[position] & 0xFF));
    }

    public static boolean canReturnEmptyAtStart(Prog prog, Slice text, int start, Prog.MatchKind matchKind)
    {
        requireNonNull(text, "text is null");
        return canReturnEmptyAtStart(prog, text, 0, text.length(), start, matchKind);
    }

    static boolean canReturnEmptyAtStart(
            Prog prog,
            Slice text,
            int contextStart,
            int contextEnd,
            int start,
            Prog.MatchKind matchKind)
    {
        requireNonNull(prog, "prog is null");
        requireNonNull(text, "text is null");
        requireNonNull(matchKind, "matchKind is null");
        if (contextStart < 0 || contextEnd < contextStart || contextEnd > text.length() ||
                start < contextStart || start > contextEnd) {
            return false;
        }

        DfaInstance.Kind kind = switch (matchKind) {
            case FIRST_MATCH -> DfaInstance.Kind.FIRST_MATCH;
            case LONGEST_MATCH, FULL_MATCH -> DfaInstance.Kind.LONGEST_MATCH;
            case MANY_MATCH -> DfaInstance.Kind.MANY_MATCH;
        };
        DfaInstance dfa = prog.getCachedDfa(kind);
        if (dfa == null || !dfa.ok() || !dfa.canUseNullableStartByteCheck()) {
            return false;
        }
        return start == contextEnd || !dfa.isStartByteCandidate(text.getUnsignedByte(start));
    }

    public static long search(Prog prog, Slice text, boolean anchored, Prog.MatchKind matchKind, boolean wantMatchBoundary)
    {
        return search(prog, text, 0, text.length(), anchored, matchKind, wantMatchBoundary);
    }

    @SuppressWarnings("UnusedException")
    public static ManyMatchResult searchMany(Prog prog, Slice text, SparseSet matches)
    {
        requireNonNull(prog, "prog is null");
        requireNonNull(text, "text is null");
        if (matches == null) {
            long result = search(prog, text, 0, text.length(), true, Prog.MatchKind.MANY_MATCH, false);
            if (result >= 0) {
                return ManyMatchResult.MATCH;
            }
            return result == SEARCH_FAILED ? ManyMatchResult.RESOURCE_EXHAUSTED : ManyMatchResult.NO_MATCH;
        }
        if (prog.start() == 0) {
            return ManyMatchResult.NO_MATCH;
        }

        DfaInstance dfa = prog.getCachedDfa(DfaInstance.Kind.MANY_MATCH);
        if (dfa == null || !dfa.ok()) {
            return ManyMatchResult.RESOURCE_EXHAUSTED;
        }

        boolean requiresExclusiveSearch = false;
        long observedCacheVersion = 0;
        while (true) {
            boolean exclusiveSearch = requiresExclusiveSearch;
            if (!exclusiveSearch) {
                observedCacheVersion = dfa.cacheVersion();
            }
            CacheTables tables = dfa.beginSearch(exclusiveSearch);
            if (exclusiveSearch && dfa.cacheVersion() != observedCacheVersion) {
                dfa.cancelExclusiveSearch();
                requiresExclusiveSearch = false;
                continue;
            }
            try {
                int textBegin = text.byteArrayOffset();
                int textEnd = textBegin + text.length();
                // Clear what an earlier attempt recorded before it retried.
                matches.clear();
                StateData start = dfa.analyzeStart(tables, text, textBegin, textEnd, true, true);
                tables = dfa.tablesAfterWrite(tables);
                if (start.offset() == T_DEAD) {
                    return ManyMatchResult.NO_MATCH;
                }

                int startOffset = start.offset();
                int stateOffset = startOffset;
                boolean matched = (start.flag() & FLAG_MATCH) != 0;
                if (matched) {
                    recordMatches(start, matches);
                }

                byte[] bytes = text.byteArray();
                int position = textBegin;
                int[] transitions = tables.transitions;
                while (position < textEnd) {
                    int inputByte = bytes[position++] & 0xFF;
                    int transition = getTransition(transitions, stateOffset + (dfa.bytemap[inputByte] & 0xFF));
                    if (transition == T_UNCOMPUTED) {
                        transition = dfa.computeTransition(tables, stateOffset, inputByte);
                        tables = dfa.tablesAfterWrite(tables);
                        transitions = tables.transitions;
                    }
                    if (transition == Integer.MIN_VALUE) {
                        StateSaver savedStart = new StateSaver(dfa, startOffset);
                        StateSaver savedState = new StateSaver(dfa, stateOffset);
                        RestoredStates restoredStates = dfa.resetAndRestore(savedStart, savedState);
                        tables = dfa.tablesAfterWrite(tables);
                        transitions = tables.transitions;
                        startOffset = restoredStates.startOffset();
                        stateOffset = restoredStates.stateOffset();
                        if (startOffset < 0 || stateOffset < 0) {
                            matches.clear();
                            return ManyMatchResult.RESOURCE_EXHAUSTED;
                        }
                        transition = dfa.computeTransition(tables, stateOffset, inputByte);
                        tables = dfa.tablesAfterWrite(tables);
                        transitions = tables.transitions;
                        if (transition == Integer.MIN_VALUE) {
                            matches.clear();
                            return ManyMatchResult.RESOURCE_EXHAUSTED;
                        }
                    }
                    if (transition == T_DEAD) {
                        return matched ? ManyMatchResult.MATCH : ManyMatchResult.NO_MATCH;
                    }
                    if (transition == T_FULL_MATCH) {
                        return ManyMatchResult.MATCH;
                    }

                    boolean stateMatched = (transition & T_MATCH_BIT) != 0;
                    stateOffset = transition & ~T_MATCH_BIT;
                    if (stateMatched) {
                        matched = true;
                        recordMatches(dfa.stateData(tables, stateOffset), matches);
                    }
                }

                int transition = getTransition(transitions, stateOffset + dfa.bytemapRange);
                if (transition == T_UNCOMPUTED) {
                    transition = dfa.computeTransition(tables, stateOffset, BYTE_END_TEXT);
                    tables = dfa.tablesAfterWrite(tables);
                }
                if (transition == Integer.MIN_VALUE) {
                    StateSaver savedState = new StateSaver(dfa, stateOffset);
                    RestoredStates restoredStates = dfa.resetAndRestore(savedState, savedState);
                    tables = dfa.tablesAfterWrite(tables);
                    stateOffset = restoredStates.stateOffset();
                    if (stateOffset < 0) {
                        matches.clear();
                        return ManyMatchResult.RESOURCE_EXHAUSTED;
                    }
                    transition = dfa.computeTransition(tables, stateOffset, BYTE_END_TEXT);
                    tables = dfa.tablesAfterWrite(tables);
                    if (transition == Integer.MIN_VALUE) {
                        matches.clear();
                        return ManyMatchResult.RESOURCE_EXHAUSTED;
                    }
                }
                if (transition > 0 && (transition & T_MATCH_BIT) != 0) {
                    matched = true;
                    stateOffset = transition & ~T_MATCH_BIT;
                    recordMatches(dfa.stateData(tables, stateOffset), matches);
                }
                return matched ? ManyMatchResult.MATCH : ManyMatchResult.NO_MATCH;
            }
            catch (StartStateAllocationException | QuitSearchException ignored) {
                matches.clear();
                return ManyMatchResult.RESOURCE_EXHAUSTED;
            }
            catch (RetrySearchException ignored) {
                if (exclusiveSearch) {
                    throw new AssertionError("exclusive DFA search requested a retry");
                }
                requiresExclusiveSearch = true;
            }
            finally {
                dfa.endSearch(exclusiveSearch);
            }
        }
    }

    @SuppressWarnings("UnusedException")
    static int searchManyFull(Prog prog, Slice context, int contextStart, int contextEnd, int rangeStart, int rangeEnd)
    {
        requireNonNull(prog, "prog is null");
        requireNonNull(context, "context is null");
        if (contextStart < 0 || contextEnd < contextStart || contextEnd > context.length() ||
                rangeStart < contextStart || rangeEnd < rangeStart || rangeEnd > contextEnd || prog.start() == 0) {
            return -1;
        }

        DfaInstance dfa = prog.getCachedDfa(DfaInstance.Kind.MANY_MATCH);
        if (dfa == null || !dfa.ok()) {
            return -1;
        }

        boolean requiresExclusiveSearch = false;
        long observedCacheVersion = 0;
        while (true) {
            boolean exclusiveSearch = requiresExclusiveSearch;
            if (!exclusiveSearch) {
                observedCacheVersion = dfa.cacheVersion();
            }
            CacheTables tables = dfa.beginSearch(exclusiveSearch);
            if (exclusiveSearch && dfa.cacheVersion() != observedCacheVersion) {
                dfa.cancelExclusiveSearch();
                requiresExclusiveSearch = false;
                continue;
            }
            try {
                byte[] bytes = context.byteArray();
                int contextOffset = context.byteArrayOffset();
                int contextBegin = contextOffset + contextStart;
                int contextLimit = contextOffset + contextEnd;
                int textBegin = contextOffset + rangeStart;
                int textEnd = contextOffset + rangeEnd;
                StateData start = dfa.analyzeStart(tables, bytes, contextBegin, contextLimit, textBegin, textEnd, true, true);
                tables = dfa.tablesAfterWrite(tables);
                if (start.offset() == T_DEAD) {
                    return -1;
                }

                int startOffset = start.offset();
                int stateOffset = startOffset;
                int position = textBegin;
                int[] transitions = tables.transitions;
                while (position < textEnd) {
                    int inputByte = bytes[position++] & 0xFF;
                    int transition = getTransition(transitions, stateOffset + (dfa.bytemap[inputByte] & 0xFF));
                    if (transition == T_UNCOMPUTED) {
                        transition = dfa.computeTransition(tables, stateOffset, inputByte);
                        tables = dfa.tablesAfterWrite(tables);
                        transitions = tables.transitions;
                    }
                    if (transition == Integer.MIN_VALUE) {
                        StateSaver savedStart = new StateSaver(dfa, startOffset);
                        StateSaver savedState = new StateSaver(dfa, stateOffset);
                        RestoredStates restoredStates = dfa.resetAndRestore(savedStart, savedState);
                        tables = dfa.tablesAfterWrite(tables);
                        transitions = tables.transitions;
                        startOffset = restoredStates.startOffset();
                        stateOffset = restoredStates.stateOffset();
                        if (startOffset < 0 || stateOffset < 0) {
                            return -1;
                        }
                        transition = dfa.computeTransition(tables, stateOffset, inputByte);
                        tables = dfa.tablesAfterWrite(tables);
                        transitions = tables.transitions;
                        if (transition == Integer.MIN_VALUE) {
                            return -1;
                        }
                    }
                    if (transition == T_DEAD) {
                        return -1;
                    }
                    if (transition == T_FULL_MATCH) {
                        return -1;
                    }
                    stateOffset = transition & ~T_MATCH_BIT;
                }

                int boundaryByte = textEnd < contextLimit ? bytes[textEnd] & 0xFF : BYTE_END_TEXT;
                int transition = getTransition(transitions, stateOffset + dfa.byteMap(boundaryByte));
                if (transition == T_UNCOMPUTED) {
                    transition = dfa.computeTransition(tables, stateOffset, boundaryByte);
                    tables = dfa.tablesAfterWrite(tables);
                }
                if (transition == Integer.MIN_VALUE) {
                    StateSaver savedState = new StateSaver(dfa, stateOffset);
                    RestoredStates restoredStates = dfa.resetAndRestore(savedState, savedState);
                    tables = dfa.tablesAfterWrite(tables);
                    stateOffset = restoredStates.stateOffset();
                    if (stateOffset < 0) {
                        return -1;
                    }
                    transition = dfa.computeTransition(tables, stateOffset, boundaryByte);
                    tables = dfa.tablesAfterWrite(tables);
                    if (transition == Integer.MIN_VALUE) {
                        return -1;
                    }
                }
                if (transition <= 0 || (transition & T_MATCH_BIT) == 0) {
                    return -1;
                }
                stateOffset = transition & ~T_MATCH_BIT;
                return minimumMatchId(dfa.stateData(tables, stateOffset));
            }
            catch (StartStateAllocationException | QuitSearchException ignored) {
                return -1;
            }
            catch (RetrySearchException ignored) {
                if (exclusiveSearch) {
                    throw new AssertionError("exclusive DFA search requested a retry");
                }
                requiresExclusiveSearch = true;
            }
            finally {
                dfa.endSearch(exclusiveSearch);
            }
        }
    }

    private static int minimumMatchId(StateData state)
    {
        int[] instructions = state.inst();
        int separator = instructions.length - 1;
        while (separator >= 0 && instructions[separator] != MatchSep) {
            separator--;
        }
        if (separator < 0) {
            return -1;
        }
        int minimumMatchId = Integer.MAX_VALUE;
        for (int index = separator + 1; index < instructions.length; index++) {
            minimumMatchId = Math.min(minimumMatchId, instructions[index]);
        }
        return minimumMatchId == Integer.MAX_VALUE ? -1 : minimumMatchId;
    }

    private static void recordMatches(StateData state, SparseSet matches)
    {
        int[] instructions = state.inst();
        int separator = instructions.length - 1;
        while (separator >= 0 && instructions[separator] != MatchSep) {
            separator--;
        }
        if (separator < 0) {
            return;
        }
        for (int index = instructions.length - 1; index > separator; index--) {
            matches.insert(instructions[index]);
        }
    }

    private static int getTransition(int[] transitions, int index)
    {
        return transitions[index];
    }

    private static void setTransition(int[] transitions, int index, int value)
    {
        transitions[index] = value;
    }

    private static Object[] pairedStateReferences(Object[][] pairedStateRefArrays, int stateIndex)
    {
        // The table is built only when an exclusive search ends, so a state added since then has no
        // paired row, and once making room for a new state has released the table, no state has one.
        return pairedStateRefArrays != null && stateIndex < pairedStateRefArrays.length ? pairedStateRefArrays[stateIndex] : null;
    }

    private static int pairedRowOffset(int[] pairedRowOffsets, int stateIndex)
    {
        // As for pairedStateReferences, a state added since the table was built has no paired row,
        // nor does any state once the table was released.
        return pairedRowOffsets != null && stateIndex < pairedRowOffsets.length ? pairedRowOffsets[stateIndex] : NO_PAIRED_ROW;
    }

    // Reads the paired slot at `byteOffset`, a byte offset into the flat paired table.
    private static int getPairedSlot(MemorySegment flatPairedRows, int byteOffset)
    {
        return flatPairedRows.get(ValueLayout.JAVA_INT_UNALIGNED, byteOffset);
    }

    private static Object getStateReference(Object[] references, int index)
    {
        return references[index];
    }

    private static void setStateReference(Object[] references, int index, Object value)
    {
        references[index] = value;
    }

    // PERFORMANCE-SENSITIVE HOT LOOPS: C2 code shape, method boundaries, dispatch
    // placement, and dependent loads have all produced measurable target-host changes.
    // Do not perform readability-only refactors in these search methods. Any change must
    // retain direct path tests and pass focused Intel and Graviton benchmarks.
    private static long searchForward(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int textBegin,
            int textEnd,
            int ctxEnd,
            int startOffset,
            boolean startIsMatch,
            boolean pairedSearch,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        if (shouldSampleSelfLoopTransitions(endMatch, textEnd - textBegin, pairedSearch)) {
            return searchForwardSampledSelfLoops(
                    dfa,
                    tables,
                    bytes,
                    textBegin,
                    textEnd,
                    ctxEnd,
                    startOffset,
                    startIsMatch,
                    endMatch,
                    wantEarliestMatch);
        }
        if (!pairedSearch && !startIsMatch) {
            if (textEnd - textBegin >= MIN_PAIRED_SEARCH_BYTES) {
                tables = dfa.requestAbsolutePointerTransitions(tables);
            }
            if (dfa.hasAbsolutePointerTransitions(tables, startOffset)) {
                return searchForwardAbsolutePointers(
                        dfa,
                        tables,
                        bytes,
                        textBegin,
                        textBegin,
                        textEnd,
                        ctxEnd,
                        startOffset,
                        -1,
                        endMatch,
                        wantEarliestMatch);
            }
        }
        return searchForwardContinuation(
                dfa,
                tables,
                bytes,
                textBegin,
                textBegin,
                textEnd,
                ctxEnd,
                startOffset,
                startIsMatch,
                -1,
                endMatch,
                wantEarliestMatch);
    }

    private static long searchForwardAbsolutePointers(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int resultBegin,
            int position,
            int textEnd,
            int ctxEnd,
            int startOffset,
            int initialMatchBoundary,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        AbsolutePointerTransitions absolutePointers = tables.absolutePointerTransitions;
        long tableAddress = absolutePointers.baseAddress();
        long transitionRowAddress = tableAddress + ((long) startOffset * Long.BYTES);
        boolean matched = initialMatchBoundary >= 0;
        int lastMatchBoundary = initialMatchBoundary;
        PointerReaderProbe pointerReaderProbe = POINTER_READER_PROBE;
        if (pointerReaderProbe != null) {
            pointerReaderProbe.pauseIfTarget();
        }
        try {
            MemorySegment addressSpace = HEAP_POINTER_TABLES ? absolutePointers.segment() : AbsoluteAddressSpace.EVERYTHING;
            byte[] byteMap = dfa.bytemap;
            for (; position < textEnd; position++) {
                long transition = addressSpace.get(
                        ValueLayout.JAVA_LONG_UNALIGNED,
                        transitionRowAddress + ((long) (byteMap[bytes[position] & 0xFF] & 0xFF) * Long.BYTES));
                if (transition == 0) {
                    break;
                }
                long tag = transition & ABSOLUTE_POINTER_TAG_MASK;
                if (tag == 0) {
                    transitionRowAddress = transition;
                    continue;
                }
                if (tag == ABSOLUTE_POINTER_MATCH_TAG) {
                    matched = true;
                    lastMatchBoundary = position;
                    transitionRowAddress = transition & ~ABSOLUTE_POINTER_TAG_MASK;
                    if (wantEarliestMatch && !endMatch) {
                        return finalizeResult(resultBegin, textEnd, false, true, lastMatchBoundary, true);
                    }
                    continue;
                }
                if (tag == ABSOLUTE_POINTER_DEAD_TAG) {
                    return finalizeResult(resultBegin, textEnd, endMatch, matched, lastMatchBoundary, true);
                }
                if (tag == ABSOLUTE_POINTER_FULL_MATCH_TAG) {
                    return finalizeResult(resultBegin, textEnd, endMatch, true, textEnd, true);
                }
                throw new AssertionError("unexpected absolute-pointer transition tag");
            }

            if (position == textEnd) {
                int finalClass = textEnd == ctxEnd ? dfa.bytemapRange : byteMap[bytes[textEnd] & 0xFF] & 0xFF;
                long finalTransition = addressSpace.get(
                        ValueLayout.JAVA_LONG_UNALIGNED,
                        transitionRowAddress + ((long) finalClass * Long.BYTES));
                if (finalTransition != 0) {
                    long tag = finalTransition & ABSOLUTE_POINTER_TAG_MASK;
                    if (tag == ABSOLUTE_POINTER_MATCH_TAG || tag == ABSOLUTE_POINTER_FULL_MATCH_TAG) {
                        matched = true;
                        lastMatchBoundary = textEnd;
                    }
                    return finalizeResult(resultBegin, textEnd, endMatch, matched, lastMatchBoundary, true);
                }
            }
        }
        finally {
            // Raw native addresses do not keep their automatic-arena owner reachable.
            Reference.reachabilityFence(absolutePointers);
        }

        int stateOffset = toIntExact((transitionRowAddress - tableAddress) / Long.BYTES);
        return searchForwardContinuation(
                dfa,
                tables,
                bytes,
                resultBegin,
                position,
                textEnd,
                ctxEnd,
                stateOffset,
                false,
                lastMatchBoundary,
                endMatch,
                wantEarliestMatch);
    }

    private static long searchForwardSampledSelfLoops(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int textBegin,
            int textEnd,
            int ctxEnd,
            int startOffset,
            boolean startIsMatch,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        byte[] byteMap = dfa.bytemap;
        int nextSize = dfa.nextSize;
        int position = textBegin;
        int sampledSelfLoopCount = 0;
        Object[] stateRefs = dfa.stateReferences(tables, startOffset);

        for (int sampledTransitionCount = 0;
                position < textEnd && sampledTransitionCount < SELF_LOOP_SAMPLE_SIZE;
                sampledTransitionCount++, position++) {
            Object nextRef = getStateReference(stateRefs, byteMap[bytes[position] & 0xFF] & 0xFF);
            if (nextRef == stateRefs) {
                sampledSelfLoopCount++;
                continue;
            }
            if (nextRef == null) {
                break;
            }
            stateRefs = (Object[]) nextRef;
        }

        if (position - textBegin == SELF_LOOP_SAMPLE_SIZE &&
                sampledSelfLoopCount * 100 >= SELF_LOOP_SAMPLE_SIZE * SELF_LOOP_SAMPLE_PERCENTAGE) {
            long scanResult = scanSelfLoopTransitions(dfa, tables, bytes, position, textEnd, stateRefs);
            tables = dfa.tablesAfterWrite(tables);
            position = (int) (scanResult >>> 32);
            startOffset = (int) scanResult;
        }
        else {
            startOffset = rowStateOffset(stateRefs, nextSize, tables.transitions);
        }

        if (textEnd - position >= MIN_PAIRED_SEARCH_BYTES) {
            tables = dfa.requestAbsolutePointerTransitions(tables);
            if (dfa.hasAbsolutePointerTransitions(tables, startOffset)) {
                return searchForwardAbsolutePointers(
                        dfa,
                        tables,
                        bytes,
                        textBegin,
                        position,
                        textEnd,
                        ctxEnd,
                        startOffset,
                        startIsMatch ? textBegin : -1,
                        endMatch,
                        wantEarliestMatch);
            }
        }
        return searchForwardContinuation(
                dfa,
                tables,
                bytes,
                textBegin,
                position,
                textEnd,
                ctxEnd,
                startOffset,
                false,
                startIsMatch ? textBegin : -1,
                endMatch,
                wantEarliestMatch);
    }

    private static long searchForwardContinuation(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int resultBegin,
            int position,
            int textEnd,
            int ctxEnd,
            int startOffset,
            boolean startIsMatch,
            int initialMatchBoundary,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        int lastMatchBoundary = initialMatchBoundary;
        boolean matched = initialMatchBoundary >= 0;
        int resetPosition = -1;

        int stateOffset = startOffset;
        int[] transitions = tables.transitions;
        byte[] byteMap = dfa.bytemap;
        int nextSize = dfa.nextSize;
        boolean deadState = false;

        Object[] stateRefs = dfa.stateReferences(tables, stateOffset);

        if (startIsMatch) {
            matched = true;
            lastMatchBoundary = position;
            if (wantEarliestMatch && !endMatch) {
                return finalizeResult(resultBegin, textEnd, endMatch, true, lastMatchBoundary, true);
            }
        }

        scan:
        while (position < textEnd) {
            // null = any abnormal transition (uncomputed, dead, full-match, or match state).
            for (; position < textEnd; position++) {
                Object nextRef = getStateReference(stateRefs, byteMap[bytes[position] & 0xFF] & 0xFF);
                if (nextRef == null) {
                    break;
                }
                stateRefs = (Object[]) nextRef;
            }
            if (position >= textEnd) {
                // Recover int offset from stateRefs for end-of-text handling
                stateOffset = rowStateOffset(stateRefs, nextSize, transitions);
                break;
            }

            // Inner loop broke on null — consult int[] sidecard for details
            stateOffset = rowStateOffset(stateRefs, nextSize, transitions);
            int nextState = getTransition(transitions, stateOffset + (byteMap[bytes[position] & 0xFF] & 0xFF));

            // Sentinels are negative; matches are positive with T_MATCH_BIT set.
            if (nextState < 0) {
                if (nextState == T_DEAD) {
                    position++;
                    deadState = true;
                    break scan;
                }
                if (nextState == T_FULL_MATCH) {
                    return finalizeResult(resultBegin, textEnd, endMatch, true, textEnd, true);
                }

                // T_UNCOMPUTED: run slow path
                int inputByte = bytes[position] & 0xFF;
                nextState = dfa.computeTransition(tables, stateOffset, inputByte);
                tables = dfa.tablesAfterWrite(tables);
                transitions = tables.transitions;

                if (nextState != Integer.MIN_VALUE) {
                    // Slow path succeeded
                    if (nextState == T_DEAD) {
                        position++;
                        deadState = true;
                        break scan;
                    }
                    if (nextState == T_FULL_MATCH) {
                        return finalizeResult(resultBegin, textEnd, endMatch, true, textEnd, true);
                    }
                    stateOffset = nextState;
                    if ((stateOffset & T_MATCH_BIT) != 0) {
                        matched = true;
                        lastMatchBoundary = position;
                        stateOffset &= ~T_MATCH_BIT;
                        if (wantEarliestMatch && !endMatch) {
                            return finalizeResult(resultBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                        }
                    }
                    position++;
                    stateRefs = dfa.stateReferences(tables, stateOffset);
                    continue;
                }

                // Cache exhaustion: save/reset/restore
                position++;
                if (dfaShouldBailWhenSlow &&
                        resetPosition >= 0 &&
                        (position - resetPosition) < 10 * dfa.stateCount &&
                        !dfa.releaseAbsolutePointerTransitionsForSlowReset()) {
                    return SEARCH_FAILED;
                }
                resetPosition = position;
                StateSaver saveStart = new StateSaver(dfa, startOffset);
                StateSaver saveState = new StateSaver(dfa, stateOffset);
                RestoredStates restoredStates = dfa.resetAndRestore(saveStart, saveState);
                tables = dfa.tablesAfterWrite(tables);
                transitions = tables.transitions;
                startOffset = restoredStates.startOffset();
                stateOffset = restoredStates.stateOffset();
                if (startOffset < 0 || stateOffset < 0) {
                    return SEARCH_FAILED;
                }
                nextState = dfa.computeTransition(tables, stateOffset, inputByte);
                tables = dfa.tablesAfterWrite(tables);
                transitions = tables.transitions;
                if (nextState == Integer.MIN_VALUE) {
                    return SEARCH_FAILED;
                }
                if (nextState == T_DEAD) {
                    deadState = true;
                    break;
                }
                if (nextState == T_FULL_MATCH) {
                    return finalizeResult(resultBegin, textEnd, endMatch, true, textEnd, true);
                }
                stateOffset = nextState;
                if ((stateOffset & T_MATCH_BIT) != 0) {
                    matched = true;
                    lastMatchBoundary = position - 1;
                    stateOffset &= ~T_MATCH_BIT;
                    if (wantEarliestMatch && !endMatch) {
                        return finalizeResult(resultBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                    }
                }
                stateRefs = dfa.stateReferences(tables, stateOffset);
                continue;
            }

            // Normal non-match transition that was null (start self-loop for prefix accel)
            if ((nextState & T_MATCH_BIT) == 0) {
                stateOffset = nextState;
                position++;
                stateRefs = dfa.stateReferences(tables, stateOffset);
                continue;
            }

            // Match handling: nextState is positive with T_MATCH_BIT set
            matched = true;
            lastMatchBoundary = position;
            stateOffset = nextState & ~T_MATCH_BIT;
            if (wantEarliestMatch && !endMatch) {
                return finalizeResult(resultBegin, textEnd, endMatch, true, lastMatchBoundary, true);
            }
            position++;
            stateRefs = dfa.stateReferences(tables, stateOffset);
        }

        // End-of-text transition (skip if scan ended at dead state)
        if (!deadState) {
            int lastClass;
            int lastByte;
            if (textEnd == ctxEnd) {
                lastClass = dfa.bytemapRange;
                lastByte = BYTE_END_TEXT;
            }
            else {
                lastByte = bytes[textEnd] & 0xFF;
                lastClass = byteMap[lastByte] & 0xFF;
            }
            int finalState = getTransition(transitions, stateOffset + lastClass);
            if (finalState == T_UNCOMPUTED) {
                finalState = dfa.computeTransition(tables, stateOffset, lastByte);
                if (finalState == Integer.MIN_VALUE) {
                    finalState = computeEndOfTextTransitionAfterReset(dfa, tables, stateOffset, lastByte);
                    if (finalState == Integer.MIN_VALUE) {
                        return SEARCH_FAILED;
                    }
                }
            }
            if (finalState == T_FULL_MATCH || (finalState > 0 && (finalState & T_MATCH_BIT) != 0)) {
                matched = true;
                lastMatchBoundary = position;
            }
        }
        return finalizeResult(resultBegin, textEnd, endMatch, matched, lastMatchBoundary, true);
    }

    private static long scanSelfLoopTransitions(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int position,
            int textEnd,
            Object[] stateReferences)
    {
        // A stable self-loop keeps reference decoding and the array type check off the serial
        // transition dependency chain. Keeping this loop separate also preserves the old loop's
        // generated code when sampling rejects this path.
        long exitByteSet = VectorSupport.isAvailable() ? dfa.selfLoopExitByteSet(tables, stateReferences) : 0;
        tables = dfa.tablesAfterWrite(tables);
        if (exitByteSet != 0) {
            int exitPosition = findSelfLoopExit(dfa, bytes, position, textEnd, exitByteSet);
            dfa.selfLoopExitByteScanCount++;
            int stateOffset = rowStateOffset(stateReferences, dfa.nextSize, tables.transitions);
            return ((long) (exitPosition < 0 ? textEnd : exitPosition) << 32) | (stateOffset & 0xFFFF_FFFFL);
        }

        byte[] byteMap = dfa.bytemap;
        for (; position < textEnd; position++) {
            Object nextReference = getStateReference(stateReferences, byteMap[bytes[position] & 0xFF] & 0xFF);
            if (nextReference == stateReferences) {
                continue;
            }
            if (nextReference == null) {
                break;
            }
            stateReferences = (Object[]) nextReference;
        }
        int stateOffset = rowStateOffset(stateReferences, dfa.nextSize, tables.transitions);
        return ((long) position << 32) | (stateOffset & 0xFFFF_FFFFL);
    }

    static boolean shouldSampleSelfLoopTransitions(boolean endMatch, int remainingBytes, boolean pairedSearch)
    {
        return !pairedSearch && endMatch && remainingBytes >= MIN_SELF_LOOP_SAMPLE_SEARCH_BYTES;
    }

    // The result is measured from resultBegin, which precedes textBegin when the caller has
    // already consumed part of the search. Copied as `searchForwardPairsFlat`; keep the copy in
    // step. This object-row form and its helpers stay unchanged so x86 keeps its measured code.
    static long searchForwardPairs(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int resultBegin,
            int textBegin,
            int textEnd,
            int ctxEnd,
            int startOffset,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        Object[][] pairedStateRefArrays = tables.pairedStateRefArrays;
        byte[] byteMap = dfa.bytemap;
        int nextSize = dfa.nextSize;
        int pairedClassCount = nextSize * nextSize;
        Object[] stateRefs = pairedStateRefArrays[dfa.stateIndex(startOffset)];
        int position = textBegin;

        while (position + 1 < textEnd) {
            int pairedClass = ((byteMap[bytes[position] & 0xFF] & 0xFF) * nextSize) +
                    (byteMap[bytes[position + 1] & 0xFF] & 0xFF);
            Object nextRef = getStateReference(stateRefs, pairedClass);
            if (nextRef == null) {
                return searchForwardPairsAfterMiss(
                        dfa,
                        tables,
                        bytes,
                        resultBegin,
                        position,
                        textEnd,
                        ctxEnd,
                        stateRefs,
                        endMatch,
                        wantEarliestMatch);
            }
            stateRefs = (Object[]) nextRef;
            position += 2;
        }

        // Paired rows are complete when published, so their row metadata needs no check.
        int stateOffset = ((StateData) getStateReference(stateRefs, pairedClassCount)).offset();
        long result = searchForward(dfa, tables, bytes, position, textEnd, ctxEnd, stateOffset, false, true, endMatch, wantEarliestMatch);
        if (result >= 0) {
            return result + (position - resultBegin);
        }
        return result;
    }

    // Copied as `searchForwardPairsFlatAfterMiss`; keep the copy in step.
    private static long searchForwardPairsAfterMiss(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int resultBegin,
            int position,
            int textEnd,
            int ctxEnd,
            Object[] stateRefs,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        int nextSize = dfa.nextSize;
        int pairedClassCount = nextSize * nextSize;
        if (dfa.kind == DfaInstance.Kind.FIRST_MATCH &&
                !endMatch &&
                !wantEarliestMatch) {
            int stateOffset = ((StateData) getStateReference(stateRefs, pairedClassCount)).offset();
            byte[] byteMap = dfa.bytemap;
            long pairedTransition = decodePairedTransition(
                    tables,
                    stateOffset,
                    byteMap[bytes[position] & 0xFF] & 0xFF,
                    byteMap[bytes[position + 1] & 0xFF] & 0xFF);
            int outcome = (int) pairedTransition;
            if (outcome == PAIR_MATCH_FIRST || outcome == PAIR_MATCH_SECOND) {
                long result = searchForwardPairsAfterMatch(
                        dfa,
                        tables,
                        bytes,
                        resultBegin,
                        position,
                        textEnd,
                        ctxEnd,
                        pairedTransition,
                        endMatch,
                        wantEarliestMatch);
                if (result >= 0) {
                    dfa.recordPairedSearch((int) result);
                }
                return result;
            }
        }

        int stateOffset = ((StateData) getStateReference(stateRefs, pairedClassCount)).offset();
        long result = searchForward(dfa, tables, bytes, position, textEnd, ctxEnd, stateOffset, false, true, endMatch, wantEarliestMatch);
        if (result >= 0) {
            return result + (position - resultBegin);
        }
        return result;
    }

    // Copied as `searchForwardPairsFlatAfterMatch`; keep the copy in step.
    private static long searchForwardPairsAfterMatch(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int resultBegin,
            int position,
            int textEnd,
            int ctxEnd,
            long pairedTransition,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        int consumedBytes = (int) pairedTransition;
        return searchForwardPairsFromState(
                dfa,
                tables,
                bytes,
                resultBegin,
                position + consumedBytes,
                textEnd,
                ctxEnd,
                (int) (pairedTransition >>> 32),
                position + consumedBytes - 1,
                endMatch,
                wantEarliestMatch);
    }

    // Continues a first-match search from a state reached with a pending match, stepping two
    // bytes at a time through match transitions. The result is measured from resultBegin.
    // Copied as `searchForwardPairsFlatFromState`; keep the copy in step.
    private static long searchForwardPairsFromState(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int resultBegin,
            int position,
            int textEnd,
            int ctxEnd,
            int stateOffset,
            int lastMatchBoundary,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        int nextSize = dfa.nextSize;
        int pairedClassCount = nextSize * nextSize;
        long pairedTransition;
        Object[] stateRefs;

        while (true) {
            int matchingSelfLoopEnd = scanMatchingSelfLoopTransitions(dfa, tables, bytes, position, textEnd, stateOffset);
            // Computing the loop's exit bytes may have published grown tables.
            tables = dfa.tablesAfterWrite(tables);
            if (matchingSelfLoopEnd != position) {
                dfa.matchingSelfLoopScanCount++;
                position = matchingSelfLoopEnd;
                lastMatchBoundary = position - 1;
            }

            stateRefs = pairedStateReferences(tables.pairedStateRefArrays, dfa.stateIndex(stateOffset));
            if (stateRefs == null) {
                return searchForwardContinuation(
                        dfa,
                        tables,
                        bytes,
                        resultBegin,
                        position,
                        textEnd,
                        ctxEnd,
                        stateOffset,
                        false,
                        lastMatchBoundary,
                        endMatch,
                        wantEarliestMatch);
            }
            pairedTransition = 0;

            while (position + 1 < textEnd) {
                int firstClass = dfa.bytemap[bytes[position] & 0xFF] & 0xFF;
                int secondClass = dfa.bytemap[bytes[position + 1] & 0xFF] & 0xFF;
                int pairedClass = (firstClass * nextSize) + secondClass;
                Object nextRef = getStateReference(stateRefs, pairedClass);
                if (nextRef != null) {
                    stateRefs = (Object[]) nextRef;
                    position += 2;
                    continue;
                }

                stateOffset = ((StateData) getStateReference(stateRefs, pairedClassCount)).offset();
                pairedTransition = decodePairedTransition(tables, stateOffset, firstClass, secondClass);
                int outcome = (int) pairedTransition;
                switch (outcome) {
                    case PAIR_MATCH_FIRST, PAIR_MATCH_SECOND -> {}
                    case PAIR_DEAD -> {
                        return finalizeResult(resultBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                    }
                    case PAIR_FULL_MATCH -> {
                        return finalizeResult(resultBegin, textEnd, endMatch, true, textEnd, true);
                    }
                    case PAIR_NORMAL_FIRST, PAIR_NORMAL_SECOND -> {
                        stateOffset = (int) (pairedTransition >>> 32);
                        position += outcome == PAIR_NORMAL_FIRST ? 1 : 2;
                        stateRefs = pairedStateReferences(tables.pairedStateRefArrays, dfa.stateIndex(stateOffset));
                        if (stateRefs == null) {
                            return searchForwardContinuation(
                                    dfa,
                                    tables,
                                    bytes,
                                    resultBegin,
                                    position,
                                    textEnd,
                                    ctxEnd,
                                    stateOffset,
                                    false,
                                    lastMatchBoundary,
                                    endMatch,
                                    wantEarliestMatch);
                        }
                        pairedTransition = 0;
                        continue;
                    }
                    case PAIR_UNCOMPUTED -> {
                        return searchForwardContinuation(
                                dfa,
                                tables,
                                bytes,
                                resultBegin,
                                position,
                                textEnd,
                                ctxEnd,
                                stateOffset,
                                false,
                                lastMatchBoundary,
                                endMatch,
                                wantEarliestMatch);
                    }
                    default -> throw new AssertionError("Unexpected paired transition outcome: " + outcome);
                }
                break;
            }

            if (pairedTransition != 0) {
                int consumedBytes = (int) pairedTransition;
                stateOffset = (int) (pairedTransition >>> 32);
                lastMatchBoundary = position + consumedBytes - 1;
                position += consumedBytes;
                continue;
            }
            stateOffset = ((StateData) getStateReference(stateRefs, pairedClassCount)).offset();
            return searchForwardContinuation(
                    dfa,
                    tables,
                    bytes,
                    resultBegin,
                    position,
                    textEnd,
                    ctxEnd,
                    stateOffset,
                    false,
                    lastMatchBoundary,
                    endMatch,
                    wantEarliestMatch);
        }
    }

    // searchForwardPairs on the flat paired table that FLAT_PAIRED_ROWS selects. Each step adds
    // the pair's byte offset to the row's and loads, with no scaling on the chain from one load to
    // the next. Copied as `searchForwardPairs`; keep the copy in step.
    static long searchForwardPairsFlat(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int resultBegin,
            int textBegin,
            int textEnd,
            int ctxEnd,
            int startOffset,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        MemorySegment flatPairedRows = tables.flatPairedRows;
        byte[] byteMap = dfa.bytemap;
        int nextSize = dfa.nextSize;
        int pairedClassCount = nextSize * nextSize;
        int rowOffset = tables.pairedRowOffsets[dfa.stateIndex(startOffset)];
        int position = textBegin;

        while (position + 1 < textEnd) {
            int pairedClass = ((byteMap[bytes[position] & 0xFF] & 0xFF) * nextSize) +
                    (byteMap[bytes[position + 1] & 0xFF] & 0xFF);
            int nextRowOffset = getPairedSlot(flatPairedRows, rowOffset + (pairedClass * Integer.BYTES));
            if (nextRowOffset < 0) {
                return searchForwardPairsFlatAfterMiss(
                        dfa,
                        tables,
                        bytes,
                        resultBegin,
                        position,
                        textEnd,
                        ctxEnd,
                        rowOffset,
                        endMatch,
                        wantEarliestMatch);
            }
            rowOffset = nextRowOffset;
            position += 2;
        }

        // Paired rows are complete when published, so their state offsets need no check.
        int stateOffset = getPairedSlot(flatPairedRows, rowOffset + (pairedClassCount * Integer.BYTES));
        long result = searchForward(dfa, tables, bytes, position, textEnd, ctxEnd, stateOffset, false, true, endMatch, wantEarliestMatch);
        if (result >= 0) {
            return result + (position - resultBegin);
        }
        return result;
    }

    // Copied as `searchForwardPairsAfterMiss`; keep the copy in step.
    private static long searchForwardPairsFlatAfterMiss(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int resultBegin,
            int position,
            int textEnd,
            int ctxEnd,
            int rowOffset,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        int nextSize = dfa.nextSize;
        int stateOffset = getPairedSlot(tables.flatPairedRows, rowOffset + (nextSize * nextSize * Integer.BYTES));
        if (dfa.kind == DfaInstance.Kind.FIRST_MATCH &&
                !endMatch &&
                !wantEarliestMatch) {
            byte[] byteMap = dfa.bytemap;
            long pairedTransition = decodePairedTransition(
                    tables,
                    stateOffset,
                    byteMap[bytes[position] & 0xFF] & 0xFF,
                    byteMap[bytes[position + 1] & 0xFF] & 0xFF);
            int outcome = (int) pairedTransition;
            if (outcome == PAIR_MATCH_FIRST || outcome == PAIR_MATCH_SECOND) {
                long result = searchForwardPairsFlatAfterMatch(
                        dfa,
                        tables,
                        bytes,
                        resultBegin,
                        position,
                        textEnd,
                        ctxEnd,
                        pairedTransition,
                        endMatch,
                        wantEarliestMatch);
                if (result >= 0) {
                    dfa.recordPairedSearch((int) result);
                }
                return result;
            }
        }

        long result = searchForward(dfa, tables, bytes, position, textEnd, ctxEnd, stateOffset, false, true, endMatch, wantEarliestMatch);
        if (result >= 0) {
            return result + (position - resultBegin);
        }
        return result;
    }

    // Copied as `searchForwardPairsAfterMatch`; keep the copy in step.
    private static long searchForwardPairsFlatAfterMatch(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int resultBegin,
            int position,
            int textEnd,
            int ctxEnd,
            long pairedTransition,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        int consumedBytes = (int) pairedTransition;
        return searchForwardPairsFlatFromState(
                dfa,
                tables,
                bytes,
                resultBegin,
                position + consumedBytes,
                textEnd,
                ctxEnd,
                (int) (pairedTransition >>> 32),
                position + consumedBytes - 1,
                endMatch,
                wantEarliestMatch);
    }

    // searchForwardPairsFromState on the flat paired table that FLAT_PAIRED_ROWS selects. Copied
    // as `searchForwardPairsFromState`; keep the copy in step.
    private static long searchForwardPairsFlatFromState(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int resultBegin,
            int position,
            int textEnd,
            int ctxEnd,
            int stateOffset,
            int lastMatchBoundary,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        int nextSize = dfa.nextSize;
        int pairedClassCount = nextSize * nextSize;
        long pairedTransition;
        MemorySegment flatPairedRows;
        int rowOffset;

        while (true) {
            int matchingSelfLoopEnd = scanMatchingSelfLoopTransitions(dfa, tables, bytes, position, textEnd, stateOffset);
            // Computing the loop's exit bytes may have published grown tables.
            tables = dfa.tablesAfterWrite(tables);
            if (matchingSelfLoopEnd != position) {
                dfa.matchingSelfLoopScanCount++;
                position = matchingSelfLoopEnd;
                lastMatchBoundary = position - 1;
            }

            flatPairedRows = tables.flatPairedRows;
            rowOffset = pairedRowOffset(tables.pairedRowOffsets, dfa.stateIndex(stateOffset));
            if (rowOffset < 0) {
                return searchForwardContinuation(
                        dfa,
                        tables,
                        bytes,
                        resultBegin,
                        position,
                        textEnd,
                        ctxEnd,
                        stateOffset,
                        false,
                        lastMatchBoundary,
                        endMatch,
                        wantEarliestMatch);
            }
            pairedTransition = 0;

            while (position + 1 < textEnd) {
                int firstClass = dfa.bytemap[bytes[position] & 0xFF] & 0xFF;
                int secondClass = dfa.bytemap[bytes[position + 1] & 0xFF] & 0xFF;
                int pairedClass = (firstClass * nextSize) + secondClass;
                int nextRowOffset = getPairedSlot(flatPairedRows, rowOffset + (pairedClass * Integer.BYTES));
                if (nextRowOffset >= 0) {
                    rowOffset = nextRowOffset;
                    position += 2;
                    continue;
                }

                stateOffset = getPairedSlot(flatPairedRows, rowOffset + (pairedClassCount * Integer.BYTES));
                pairedTransition = decodePairedTransition(tables, stateOffset, firstClass, secondClass);
                int outcome = (int) pairedTransition;
                switch (outcome) {
                    case PAIR_MATCH_FIRST, PAIR_MATCH_SECOND -> {}
                    case PAIR_DEAD -> {
                        return finalizeResult(resultBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                    }
                    case PAIR_FULL_MATCH -> {
                        return finalizeResult(resultBegin, textEnd, endMatch, true, textEnd, true);
                    }
                    case PAIR_NORMAL_FIRST, PAIR_NORMAL_SECOND -> {
                        stateOffset = (int) (pairedTransition >>> 32);
                        position += outcome == PAIR_NORMAL_FIRST ? 1 : 2;
                        rowOffset = pairedRowOffset(tables.pairedRowOffsets, dfa.stateIndex(stateOffset));
                        if (rowOffset < 0) {
                            return searchForwardContinuation(
                                    dfa,
                                    tables,
                                    bytes,
                                    resultBegin,
                                    position,
                                    textEnd,
                                    ctxEnd,
                                    stateOffset,
                                    false,
                                    lastMatchBoundary,
                                    endMatch,
                                    wantEarliestMatch);
                        }
                        pairedTransition = 0;
                        continue;
                    }
                    case PAIR_UNCOMPUTED -> {
                        return searchForwardContinuation(
                                dfa,
                                tables,
                                bytes,
                                resultBegin,
                                position,
                                textEnd,
                                ctxEnd,
                                stateOffset,
                                false,
                                lastMatchBoundary,
                                endMatch,
                                wantEarliestMatch);
                    }
                    default -> throw new AssertionError("Unexpected paired transition outcome: " + outcome);
                }
                break;
            }

            if (pairedTransition != 0) {
                int consumedBytes = (int) pairedTransition;
                stateOffset = (int) (pairedTransition >>> 32);
                lastMatchBoundary = position + consumedBytes - 1;
                position += consumedBytes;
                continue;
            }
            stateOffset = getPairedSlot(flatPairedRows, rowOffset + (pairedClassCount * Integer.BYTES));
            return searchForwardContinuation(
                    dfa,
                    tables,
                    bytes,
                    resultBegin,
                    position,
                    textEnd,
                    ctxEnd,
                    stateOffset,
                    false,
                    lastMatchBoundary,
                    endMatch,
                    wantEarliestMatch);
        }
    }

    // Returns the end of the run of bytes before textEnd on which the matching state at stateOffset
    // loops on itself. When vectors are available and the state's exit set is accepted, a run that
    // continues after MATCHING_SELF_LOOP_STEPPED_BYTES stepped bytes is finished by a vector scan for
    // the exit bytes, which are exactly the bytes whose transitions leave the loop, including bytes
    // the DFA quits on. Otherwise the run keeps stepping. Either way the result is what stepping the
    // whole run returns.
    private static int scanMatchingSelfLoopTransitions(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int position,
            int textEnd,
            int stateOffset)
    {
        boolean vectorScan = VectorSupport.isAvailable() && textEnd - position > MATCHING_SELF_LOOP_STEPPED_BYTES;
        int steppedEnd = vectorScan ? position + MATCHING_SELF_LOOP_STEPPED_BYTES : textEnd;
        int runEnd = stepMatchingSelfLoopTransitions(dfa, tables, bytes, position, steppedEnd, stateOffset);
        if (runEnd != steppedEnd || !vectorScan) {
            return runEnd;
        }

        // The run's transitions carry the match bit, so an accepted set is a matching one.
        long exitByteSet = dfa.countSelfLoopExitByteSet(tables, stateOffset);
        if (exitByteSet == 0) {
            // Computing the loop's exit bytes may have published grown tables.
            tables = dfa.tablesAfterWrite(tables);
            return stepMatchingSelfLoopTransitions(dfa, tables, bytes, runEnd, textEnd, stateOffset);
        }
        int exitPosition = findSelfLoopExit(dfa, bytes, runEnd, textEnd, exitByteSet);
        dfa.matchingSelfLoopExitByteScanCount++;
        return exitPosition < 0 ? textEnd : exitPosition;
    }

    // Returns the first position in [position, end) whose byte leaves the self-loop that the
    // accepted exitByteSet describes, or -1: one of its exit bytes in the exit form, or a byte
    // outside its ranges in the stay form. Callers check VectorSupport.isAvailable() first.
    private static int findSelfLoopExit(DfaInstance dfa, byte[] bytes, int position, int end, long exitByteSet)
    {
        if (exitByteSet < 0) {
            dfa.selfLoopStayRangeScanCount++;
            int rangeCount = (int) (((exitByteSet >>> 15) & 1) | ((exitByteSet >>> 30) & 2)) + 1;
            return VectorByteSetScanner.findOutsideRanges(bytes, position, end - position, exitByteSet & SELF_LOOP_STAY_RANGES_MASK, rangeCount);
        }
        int exits = (int) exitByteSet;
        int exitByteCount = (exits >>> SELF_LOOP_EXIT_BYTE_COUNT_SHIFT) & SELF_LOOP_EXIT_BYTE_COUNT_MASK;
        if ((exits & SELF_LOOP_EXIT_BYTE_SET_HIGH) != 0) {
            return VectorByteSetScanner.findHighBitOr(bytes, position, end - position, (byte) exits, (byte) (exits >>> 8), (byte) (exits >>> 16), exitByteCount);
        }
        return VectorByteSetScanner.find(bytes, position, end - position, (byte) exits, (byte) (exits >>> 8), (byte) (exits >>> 16), exitByteCount);
    }

    private static boolean isMatchingSelfLoop(long exitByteSet)
    {
        return (exitByteSet & (exitByteSet < 0 ? SELF_LOOP_STAY_RANGES_MATCHING : SELF_LOOP_EXIT_BYTE_SET_MATCHING)) != 0;
    }

    private static int stepMatchingSelfLoopTransitions(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int position,
            int textEnd,
            int stateOffset)
    {
        int matchingSelfLoop = stateOffset | T_MATCH_BIT;
        int[] transitions = tables.transitions;
        byte[] byteMap = dfa.bytemap;
        for (; position < textEnd; position++) {
            if (getTransition(transitions, stateOffset + (byteMap[bytes[position] & 0xFF] & 0xFF)) != matchingSelfLoop) {
                break;
            }
        }
        return position;
    }

    private static int scanMatchingSelfLoopTransitionsBackward(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int scanBegin,
            int position,
            int stateOffset)
    {
        int matchingSelfLoop = stateOffset | T_MATCH_BIT;
        int[] transitions = tables.transitions;
        byte[] byteMap = dfa.bytemap;
        for (; position > scanBegin; position--) {
            if (getTransition(transitions, stateOffset + (byteMap[bytes[position - 1] & 0xFF] & 0xFF)) != matchingSelfLoop) {
                break;
            }
        }
        return position;
    }

    private static long decodePairedTransition(CacheTables tables, int stateOffset, int firstClass, int secondClass)
    {
        int firstTransition = getTransition(tables.transitions, stateOffset + firstClass);
        if (firstTransition == T_UNCOMPUTED) {
            return PAIR_UNCOMPUTED;
        }
        if (firstTransition == T_DEAD) {
            return PAIR_DEAD;
        }
        if (firstTransition == T_FULL_MATCH) {
            return PAIR_FULL_MATCH;
        }
        if (firstTransition > 0 && (firstTransition & T_MATCH_BIT) != 0) {
            return encodePairedTransition(firstTransition & ~T_MATCH_BIT, PAIR_MATCH_FIRST);
        }

        int secondTransition = getTransition(tables.transitions, firstTransition + secondClass);
        if (secondTransition == T_UNCOMPUTED) {
            return encodePairedTransition(firstTransition, PAIR_NORMAL_FIRST);
        }
        if (secondTransition == T_DEAD) {
            return PAIR_DEAD;
        }
        if (secondTransition == T_FULL_MATCH) {
            return PAIR_FULL_MATCH;
        }
        if (secondTransition > 0 && (secondTransition & T_MATCH_BIT) != 0) {
            return encodePairedTransition(secondTransition & ~T_MATCH_BIT, PAIR_MATCH_SECOND);
        }
        return encodePairedTransition(secondTransition, PAIR_NORMAL_SECOND);
    }

    private static long encodePairedTransition(int stateOffset, int outcome)
    {
        return ((long) stateOffset << 32) | outcome;
    }

    private static long searchForwardPrefixAccelSingleByte(
            DfaInstance dfa,
            CacheTables tables,
            Prog prog,
            byte[] bytes,
            int textBegin,
            int textEnd,
            int ctxEnd,
            int startOffset,
            boolean startIsMatch,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        int position = textBegin;
        int lastMatchBoundary = -1;
        boolean matched = false;
        int resetPosition = -1;

        int stateOffset = startOffset;
        int[] transitions = tables.transitions;
        byte[] byteMap = dfa.bytemap;
        boolean deadState = false;

        // Single-byte prefix path uses fused int[] transitions.
        // Rationale: this mode has high false-positive rate, so it frequently flips between
        // prefix scanning and a few DFA steps. Keeping start-state self-loops on the int[]
        // hot path avoids per-cycle Object[] null-break + sidecard handoff overhead.
        if (startIsMatch) {
            matched = true;
            lastMatchBoundary = position;
            if (wantEarliestMatch && !endMatch) {
                return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, true);
            }
        }

        scan:
        while (position < textEnd) {
            if (stateOffset == startOffset) {
                int next = prog.prefixAccelSingleByteNoFoldCase(bytes, position, textEnd - position);
                if (next < 0) {
                    position = textEnd;
                    break;
                }
                position = next;
            }

            int nextState = 0;
            for (; position < textEnd; position++) {
                nextState = getTransition(transitions, stateOffset + (byteMap[bytes[position] & 0xFF] & 0xFF));
                // Cold-path gate: sentinels are negative.
                if (nextState < 0) {
                    break;
                }
                stateOffset = nextState;
                if ((stateOffset & T_MATCH_BIT) != 0) {
                    matched = true;
                    lastMatchBoundary = position;
                    stateOffset &= ~T_MATCH_BIT;
                    if (wantEarliestMatch && !endMatch) {
                        return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                    }
                }
                if (stateOffset == startOffset) {
                    position++;
                    continue scan;
                }
            }
            if (position >= textEnd) {
                break;
            }

            if (nextState == T_DEAD) {
                position++;
                deadState = true;
                break scan;
            }
            if (nextState == T_FULL_MATCH) {
                return finalizeResult(textBegin, textEnd, endMatch, true, textEnd, true);
            }

            int inputByte = bytes[position] & 0xFF;
            nextState = dfa.computeTransition(tables, stateOffset, inputByte);
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;

            if (nextState != Integer.MIN_VALUE) {
                if (nextState == T_DEAD) {
                    position++;
                    deadState = true;
                    break scan;
                }
                if (nextState == T_FULL_MATCH) {
                    return finalizeResult(textBegin, textEnd, endMatch, true, textEnd, true);
                }
                stateOffset = nextState;
                if ((stateOffset & T_MATCH_BIT) != 0) {
                    matched = true;
                    lastMatchBoundary = position;
                    stateOffset &= ~T_MATCH_BIT;
                    if (wantEarliestMatch && !endMatch) {
                        return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                    }
                }
                position++;
                continue;
            }

            position++;
            if (dfaShouldBailWhenSlow &&
                    resetPosition >= 0 &&
                    (position - resetPosition) < 10 * dfa.stateCount &&
                    !dfa.releaseAbsolutePointerTransitionsForSlowReset()) {
                return SEARCH_FAILED;
            }
            resetPosition = position;
            StateSaver saveStart = new StateSaver(dfa, startOffset);
            StateSaver saveState = new StateSaver(dfa, stateOffset);
            RestoredStates restoredStates = dfa.resetAndRestore(saveStart, saveState);
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;
            startOffset = restoredStates.startOffset();
            stateOffset = restoredStates.stateOffset();
            if (startOffset < 0 || stateOffset < 0) {
                return SEARCH_FAILED;
            }
            nextState = dfa.computeTransition(tables, stateOffset, inputByte);
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;
            if (nextState == Integer.MIN_VALUE) {
                return SEARCH_FAILED;
            }
            if (nextState == T_DEAD) {
                deadState = true;
                break;
            }
            if (nextState == T_FULL_MATCH) {
                return finalizeResult(textBegin, textEnd, endMatch, true, textEnd, true);
            }
            stateOffset = nextState;
            if ((stateOffset & T_MATCH_BIT) != 0) {
                matched = true;
                lastMatchBoundary = position - 1;
                stateOffset &= ~T_MATCH_BIT;
                if (wantEarliestMatch && !endMatch) {
                    return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                }
            }
        }

        if (!deadState) {
            int lastClass;
            int lastByte;
            if (textEnd == ctxEnd) {
                lastClass = dfa.bytemapRange;
                lastByte = BYTE_END_TEXT;
            }
            else {
                lastByte = bytes[textEnd] & 0xFF;
                lastClass = byteMap[lastByte] & 0xFF;
            }
            int finalState = getTransition(transitions, stateOffset + lastClass);
            if (finalState == T_UNCOMPUTED) {
                finalState = dfa.computeTransition(tables, stateOffset, lastByte);
                if (finalState == Integer.MIN_VALUE) {
                    finalState = computeEndOfTextTransitionAfterReset(dfa, tables, stateOffset, lastByte);
                    if (finalState == Integer.MIN_VALUE) {
                        return SEARCH_FAILED;
                    }
                }
            }
            if (finalState == T_FULL_MATCH || (finalState > 0 && (finalState & T_MATCH_BIT) != 0)) {
                matched = true;
                lastMatchBoundary = position;
            }
        }
        return finalizeResult(textBegin, textEnd, endMatch, matched, lastMatchBoundary, true);
    }

    private static long searchForwardScanAcceleration(
            DfaInstance dfa,
            CacheTables tables,
            Prog prog,
            byte[] bytes,
            int textBegin,
            int textEnd,
            int ctxEnd,
            int startOffset,
            boolean startIsMatch,
            boolean endMatch,
            boolean wantEarliestMatch,
            byte[] byteCandidates,
            int byteScanSet,
            int byteCandidateOffset)
    {
        int position = textBegin;
        int lastMatchBoundary = -1;
        boolean matched = false;
        int resetPosition = -1;

        int stateOffset = startOffset;
        int[] transitions = tables.transitions;
        byte[] byteMap = dfa.bytemap;
        int nextSize = dfa.nextSize;
        boolean deadState = false;

        Object[] stateRefs = dfa.stateReferences(tables, stateOffset);
        Object[] startRefs = stateRefs;
        // A folded run behind a lead of varying width answers the prefix scans after its result
        // up to the lead's spread, as Prog.prefixAccel describes, so the search skips them. The
        // choice is made once here as its own scan kind, so a plain prefix scans exactly as it
        // would without the bound: no added test, and the bound is never written on its path.
        if (byteCandidateOffset == PREFIX_SCAN && prog.prefixAccelMaximumLead() != prog.prefixAccelMinimumLead()) {
            byteCandidateOffset = PREFIX_RUN_SCAN;
        }
        int prefixScanCoversThrough = -1;

        // Generic scan acceleration for sparse start bytes, start literal filters, start class
        // filters, and literal prefixes other than the single-byte fast path. Keep scanner control
        // local so later searches can still use the unchanged compact transition rows when sampling
        // rejects acceleration.

        if (startIsMatch) {
            matched = true;
            lastMatchBoundary = position;
            if (wantEarliestMatch && !endMatch) {
                return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, true);
            }
        }

        scan:
        while (position < textEnd) {
            // Skip to the first byte that can begin a match.
            if (stateRefs == startRefs) {
                int next;
                if (byteCandidateOffset == PREFIX_SCAN) {
                    next = prog.prefixAccel(bytes, position, textEnd - position);
                }
                else if (byteCandidateOffset == PREFIX_RUN_SCAN) {
                    if (position <= prefixScanCoversThrough) {
                        next = position;
                    }
                    else {
                        dfa.prefixRunScanCount++;
                        next = prog.prefixAccel(bytes, position, textEnd - position);
                        if (next > position) {
                            prefixScanCoversThrough = next + prog.prefixAccelMaximumLead() - prog.prefixAccelMinimumLead();
                        }
                    }
                }
                else {
                    if (byteCandidateOffset >= textEnd - position) {
                        next = -1;
                    }
                    else {
                        int candidatePosition = position + byteCandidateOffset;
                        int candidateBytePosition;
                        if (byteScanSet == 0) {
                            candidateBytePosition = findByteCandidate(byteCandidates, bytes, candidatePosition, textEnd - candidatePosition);
                        }
                        else {
                            candidateBytePosition = findByteSetCandidate(
                                    dfa,
                                    byteCandidates,
                                    byteScanSet,
                                    bytes,
                                    candidatePosition,
                                    textEnd - candidatePosition);
                        }
                        if (byteCandidateOffset == 0) {
                            next = candidateBytePosition;
                        }
                        else {
                            next = candidateBytePosition < 0 ? -1 : candidateBytePosition - byteCandidateOffset;
                        }
                    }
                }
                if (next < 0) {
                    position = textEnd;
                    break;
                }
                if (byteCandidateOffset > 0) {
                    if (next - position < MINIMUM_PRODUCTIVE_FIXED_DISTANCE_SKIP) {
                        dfa.fixedDistanceShortSkipFallbackCount++;
                        return searchForwardContinuation(
                                dfa,
                                tables,
                                bytes,
                                textBegin,
                                position,
                                textEnd,
                                ctxEnd,
                                startOffset,
                                false,
                                -1,
                                endMatch,
                                wantEarliestMatch);
                    }
                }
                position = next;
            }

            // Tight inner loop using Object[] refs — identical to searchForward.
            // A transition back to the start row resumes scanning at the next byte.
            for (; position < textEnd; position++) {
                Object nextRef = getStateReference(stateRefs, byteMap[bytes[position] & 0xFF] & 0xFF);
                if (nextRef == startRefs) {
                    stateRefs = (Object[]) nextRef;
                    stateOffset = startOffset;
                    position++;
                    continue scan;
                }
                if (nextRef == null) {
                    break;
                }
                stateRefs = (Object[]) nextRef;
            }
            if (position >= textEnd) {
                stateOffset = rowStateOffset(stateRefs, nextSize, transitions);
                break;
            }

            // Inner loop broke on null — consult int[] sidecard
            stateOffset = rowStateOffset(stateRefs, nextSize, transitions);
            int nextState = getTransition(transitions, stateOffset + (byteMap[bytes[position] & 0xFF] & 0xFF));

            // Normal non-match transition back to the start state.
            if (nextState > 0 && (nextState & T_MATCH_BIT) == 0) {
                stateOffset = nextState;
                stateRefs = dfa.stateReferences(tables, stateOffset);
                position++;
                continue; // -> prefix accel at top of outer loop
            }

            // Match state handling (positive with T_MATCH_BIT)
            if (nextState > 0 && (nextState & T_MATCH_BIT) != 0) {
                matched = true;
                lastMatchBoundary = position;
                stateOffset = nextState & ~T_MATCH_BIT;
                if (wantEarliestMatch && !endMatch) {
                    return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                }
                position++;
                stateRefs = dfa.stateReferences(tables, stateOffset);
                continue;
            }

            // Sentinel handling
            if (nextState == T_DEAD) {
                position++;
                deadState = true;
                break scan;
            }
            if (nextState == T_FULL_MATCH) {
                return finalizeResult(textBegin, textEnd, endMatch, true, textEnd, true);
            }

            // T_UNCOMPUTED: run slow path
            int inputByte = bytes[position] & 0xFF;
            nextState = dfa.computeTransition(tables, stateOffset, inputByte);
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;

            if (nextState != Integer.MIN_VALUE) {
                if (nextState == T_DEAD) {
                    position++;
                    deadState = true;
                    break scan;
                }
                if (nextState == T_FULL_MATCH) {
                    return finalizeResult(textBegin, textEnd, endMatch, true, textEnd, true);
                }
                stateOffset = nextState;
                if ((stateOffset & T_MATCH_BIT) != 0) {
                    matched = true;
                    lastMatchBoundary = position;
                    stateOffset &= ~T_MATCH_BIT;
                    if (wantEarliestMatch && !endMatch) {
                        return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                    }
                }
                position++;
                stateRefs = dfa.stateReferences(tables, stateOffset);
                startRefs = dfa.stateReferences(tables, startOffset);
                continue;
            }

            // Cache exhaustion
            position++;
            if (dfaShouldBailWhenSlow &&
                    resetPosition >= 0 &&
                    (position - resetPosition) < 10 * dfa.stateCount &&
                    !dfa.releaseAbsolutePointerTransitionsForSlowReset()) {
                return SEARCH_FAILED;
            }
            resetPosition = position;
            StateSaver saveStart = new StateSaver(dfa, startOffset);
            StateSaver saveState = new StateSaver(dfa, stateOffset);
            RestoredStates restoredStates = dfa.resetAndRestore(saveStart, saveState);
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;
            startOffset = restoredStates.startOffset();
            stateOffset = restoredStates.stateOffset();
            if (startOffset < 0 || stateOffset < 0) {
                return SEARCH_FAILED;
            }
            nextState = dfa.computeTransition(tables, stateOffset, inputByte);
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;
            if (nextState == Integer.MIN_VALUE) {
                return SEARCH_FAILED;
            }
            if (nextState == T_DEAD) {
                deadState = true;
                break;
            }
            if (nextState == T_FULL_MATCH) {
                return finalizeResult(textBegin, textEnd, endMatch, true, textEnd, true);
            }
            stateOffset = nextState;
            if ((stateOffset & T_MATCH_BIT) != 0) {
                matched = true;
                lastMatchBoundary = position - 1;
                stateOffset &= ~T_MATCH_BIT;
                if (wantEarliestMatch && !endMatch) {
                    return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                }
            }
            stateRefs = dfa.stateReferences(tables, stateOffset);
            startRefs = dfa.stateReferences(tables, startOffset);
            // The restored start state is new, so its references are already null.
        }

        // End-of-text transition (skip if scan ended at dead state)
        if (!deadState) {
            int lastClass;
            int lastByte;
            if (textEnd == ctxEnd) {
                lastClass = dfa.bytemapRange;
                lastByte = BYTE_END_TEXT;
            }
            else {
                lastByte = bytes[textEnd] & 0xFF;
                lastClass = byteMap[lastByte] & 0xFF;
            }
            int finalState = getTransition(transitions, stateOffset + lastClass);
            if (finalState == T_UNCOMPUTED) {
                finalState = dfa.computeTransition(tables, stateOffset, lastByte);
                if (finalState == Integer.MIN_VALUE) {
                    finalState = computeEndOfTextTransitionAfterReset(dfa, tables, stateOffset, lastByte);
                    if (finalState == Integer.MIN_VALUE) {
                        return SEARCH_FAILED;
                    }
                }
            }
            if (finalState == T_FULL_MATCH || (finalState > 0 && (finalState & T_MATCH_BIT) != 0)) {
                matched = true;
                lastMatchBoundary = position;
            }
        }
        return finalizeResult(textBegin, textEnd, endMatch, matched, lastMatchBoundary, true);
    }

    private static int findByteSetCandidate(DfaInstance dfa, byte[] candidates, int byteScanSet, byte[] data, int offset, int length)
    {
        // Either filter is built only when the Vector API is available.
        if (byteScanSet == START_LITERAL_FILTER_MARKER) {
            if (length >= MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES) {
                dfa.startLiteralFilterScanCount++;
                return VectorByteSetScanner.findLiteralSet(data, offset, length, dfa.startLiteralFilter);
            }
            // A filter taken for rare start bytes serves only vector searches; shorter ones check
            // each input byte against the start byte table rather than three with the filter.
            if (dfa.startLiteralFilterVectorOnly) {
                return findByteCandidate(candidates, data, offset, length);
            }
            dfa.startLiteralFilterScanCount++;
            return dfa.startLiteralFilter.find(data, offset, length);
        }
        // As for a literal filter taken for rare start bytes, shorter searches check each input
        // byte against the start byte table.
        if (byteScanSet == START_CLASS_FILTER_MARKER) {
            if (length >= MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES) {
                dfa.startClassFilterScanCount++;
                return VectorByteSetScanner.findClassSequence(data, offset, length, dfa.startClassFilter);
            }
            return findByteCandidate(candidates, data, offset, length);
        }
        if (VectorSupport.isAvailable() && length >= MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES) {
            if (byteScanSet == MIXED_BYTE_SET_MARKER) {
                dfa.mixedByteSetScanCount++;
            }
            else if (byteScanSet >>> 24 > 3) {
                dfa.contiguousRangeScanCount++;
            }
            else {
                dfa.smallByteSetScanCount++;
            }
            return findByteSet(data, offset, length, byteScanSet, dfa.startByteNibbleTables);
        }
        return findByteCandidate(candidates, data, offset, length);
    }

    /// Returns the position of the first byte of `data[offset, offset + length)` in the byte set
    /// that `byteScanSet`, from [#buildStartByteScanSet(byte[])] or [#MIXED_BYTE_SET_MARKER] for
    /// `nibbleTables`, selects, or `-1`. It runs the set's vector scan: the nibble tables of a
    /// mixed set, a contiguous range of four to 64 bytes, or two or three bytes. The forward DFA's
    /// start-byte skip and the literal set's root skip both call it, with the Vector API only.
    static int findByteSet(byte[] data, int offset, int length, int byteScanSet, byte[] nibbleTables)
    {
        if (byteScanSet == MIXED_BYTE_SET_MARKER) {
            return VectorByteSetScanner.findNibbleTable(data, offset, length, nibbleTables);
        }
        int candidateCount = byteScanSet >>> 24;
        if (candidateCount > 3) {
            return VectorByteSetScanner.findRange(
                    data,
                    offset,
                    length,
                    byteScanSet & 0xFF,
                    (byteScanSet >>> 8) & 0xFF);
        }
        return VectorByteSetScanner.find(
                data,
                offset,
                length,
                (byte) byteScanSet,
                (byte) (byteScanSet >>> 8),
                (byte) (byteScanSet >>> 16),
                candidateCount);
    }

    private static int findByteCandidate(byte[] candidates, byte[] data, int offset, int length)
    {
        int end = offset + length;
        for (int position = offset; position < end; position++) {
            if (candidates[data[position] & 0xFF] != 0) {
                return position;
            }
        }
        return -1;
    }

    private static int buildSmallByteSet(byte[] candidates)
    {
        if (candidates == null) {
            return 0;
        }

        int candidateCount = 0;
        int smallByteSet = 0;
        for (int value = 0; value < candidates.length; value++) {
            if (candidates[value] == 0) {
                continue;
            }
            if (candidateCount == 3) {
                return 0;
            }
            smallByteSet |= value << (candidateCount * Byte.SIZE);
            candidateCount++;
        }
        return candidateCount == 2 || candidateCount == 3 ? smallByteSet | (candidateCount << 24) : 0;
    }

    /// Returns the scan set of the bytes `candidates` marks for [#findByteSet]: two or three
    /// bytes, or a contiguous range of four to 64, with the count in the high byte; or `0` for any
    /// other set, which the nibble tables may take instead.
    static int buildStartByteScanSet(byte[] candidates)
    {
        int smallByteSet = buildSmallByteSet(candidates);
        if (smallByteSet != 0 || candidates == null) {
            return smallByteSet;
        }

        int lowerBound = -1;
        int upperBound = -1;
        int candidateCount = 0;
        for (int value = 0; value < candidates.length; value++) {
            if (candidates[value] == 0) {
                continue;
            }
            if (lowerBound < 0) {
                lowerBound = value;
            }
            upperBound = value;
            candidateCount++;
        }
        if (candidateCount < 4 || candidateCount > 64 || upperBound - lowerBound + 1 != candidateCount) {
            return 0;
        }
        // The high byte is the candidate count; the low bytes are inclusive unsigned bounds.
        return (candidateCount << 24) | (upperBound << 8) | lowerBound;
    }

    // Patterns whose literals begin with common bytes, such as the folded letters of
    // (?i)Sherlock|Watson or the few UTF-8 lead bytes shared by many Cyrillic or Chinese
    // characters, stop often on their start byte set; three bytes of each literal stop far less
    // often. The estimates use English frequencies whatever the pattern's byte frequencies.
    // Two kinds of rare start set still take the filter, on vector searches only. A set of more
    // than three start bytes that is not one range would take the mixed nibble-table scan, and
    // the filter's three-position scan, at similar vector cost, stops far less often. Other rare
    // sets keep their cheaper small-set and range scanners, except for a filter of two literals
    // where VectorByteSetScanner.shouldFilterRareStartsWithTwoLiterals says its scan is faster;
    // a start class filter may still replace those scanners. `startByteProbability` is the
    // English probability of the start bytes, and `groupBits` the filter's group width.
    private static StartLiteralFilterChoice buildStartLiteralFilter(Prog prog, int[] stack, byte[] startByteCandidates, int startByteScanSet, double startByteProbability, int groupBits)
    {
        if (startByteCandidates == null) {
            return null;
        }
        int startByteCount = 0;
        for (byte candidate : startByteCandidates) {
            if (candidate != 0) {
                startByteCount++;
            }
        }
        boolean rareStartBytes = startByteProbability < MINIMUM_START_LITERAL_FILTER_START_PROBABILITY;
        boolean mixedStartBytes = startByteCount > 3 && startByteScanSet == 0;
        boolean needsTwoLiterals = rareStartBytes && !mixedStartBytes;
        if (startByteCount > MAXIMUM_START_LITERAL_FILTER_START_BYTES || (needsTwoLiterals && !VectorByteSetScanner.shouldFilterRareStartsWithTwoLiterals())) {
            return null;
        }

        long[][] paths = prog.buildStartPositionByteSets(stack, StartLiteralFilter.PATH_POSITIONS);
        if (paths == null) {
            // A program that branches into more paths than the walk keeps by the last position, or
            // reaches an assertion before the last position, can still fit in the first six.
            paths = prog.buildStartPositionByteSets(stack, StartLiteralFilter.SHALLOW_PATH_POSITIONS);
            if (paths == null) {
                return null;
            }
        }
        StartLiteralFilter filter = StartLiteralFilter.build(paths, ByteFrequencies.englishText(), groupBits);
        // Wider groups can choose positions that split the literals into more than two groups
        // where groups of one bit keep two, so rare start bytes take the one-bit filter then.
        if (needsTwoLiterals && groupBits > 1 && filter != null && filter.values().length / 3 != 2) {
            filter = StartLiteralFilter.build(paths, ByteFrequencies.englishText(), 1);
        }
        if (filter == null || filter.density() * START_LITERAL_FILTER_NARROWING_FACTOR > startByteProbability) {
            return null;
        }
        if (needsTwoLiterals && filter.values().length / 3 != 2) {
            return null;
        }
        return new StartLiteralFilterChoice(filter, rareStartBytes);
    }

    // A filter taken for rare start bytes serves only vector searches.
    private record StartLiteralFilterChoice(StartLiteralFilter filter, boolean vectorOnly) {}

    // Returns the English probability of the candidate bytes, or zero for null. Both start filters
    // compare their estimates with it, whatever byte frequencies the pattern was compiled with.
    private static double englishProbability(byte[] candidates)
    {
        if (candidates == null) {
            return 0;
        }
        ByteFrequencies byteFrequencies = ByteFrequencies.englishText();
        double probability = 0;
        for (int value = 0; value < candidates.length; value++) {
            if (candidates[value] != 0) {
                probability += byteFrequencies.probability((byte) value);
            }
        }
        return probability;
    }

    // Patterns that begin with a few narrow classes, such as the IPv4 address of mariomka/ip,
    // whose matches begin with two digits and then a dot or a digit, stop the start scan at every
    // start byte in text with numbers, and the DFA fails again within a few bytes; the ranges of
    // two or three offsets together stop far less often. StartClassFilter.build decides from the
    // byte sets at those offsets whether a filter replaces start bytes of `startByteProbability`.
    // Start bytes rarer than StartClassFilter.MINIMUM_START_PROBABILITY skip the program walk.
    // The class filter replaces only a start set's own vector scan, the range or small-set scan of
    // `startByteScanSet` or the nibble tables of a mixed set, `nibbleTables`, so a set with
    // neither takes none.
    private static StartClassFilter buildStartClassFilter(Prog prog, int[] stack, int startByteScanSet, byte[] nibbleTables, double startByteProbability)
    {
        if ((startByteScanSet == 0 && nibbleTables == null) || startByteProbability < StartClassFilter.MINIMUM_START_PROBABILITY) {
            return null;
        }
        byte[][] byteSets = prog.buildStartOffsetByteSets(stack, StartClassFilter.MAXIMUM_SPAN);
        if (byteSets == null) {
            return null;
        }
        return StartClassFilter.build(byteSets, ByteFrequencies.englishText(), startByteProbability);
    }

    private static long searchForwardAnchoredLongPrefixInt(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int textBegin,
            int textEnd,
            int ctxEnd,
            int startOffset,
            boolean startIsMatch,
            boolean endMatch,
            boolean wantEarliestMatch)
    {
        int position = textBegin;
        int lastMatchBoundary = -1;
        boolean matched = false;
        int resetPosition = -1;

        int stateOffset = startOffset;
        int[] transitions = tables.transitions;
        byte[] byteMap = dfa.bytemap;
        boolean deadState = false;

        // Anchored long-literal-prefix patterns are dominated by straight-line DFA traversal.
        // Use the fused int[] loop to avoid Object[] indirection in this regime.
        if (startIsMatch) {
            matched = true;
            lastMatchBoundary = position;
            if (wantEarliestMatch && !endMatch) {
                return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, true);
            }
        }

        scan:
        while (position < textEnd) {
            int nextState = 0;
            for (; position < textEnd; position++) {
                nextState = getTransition(transitions, stateOffset + (byteMap[bytes[position] & 0xFF] & 0xFF));
                if (nextState < 0) {
                    break;
                }
                stateOffset = nextState;
                if ((stateOffset & T_MATCH_BIT) != 0) {
                    matched = true;
                    lastMatchBoundary = position;
                    stateOffset &= ~T_MATCH_BIT;
                    if (wantEarliestMatch && !endMatch) {
                        return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                    }
                }
            }
            if (position >= textEnd) {
                break;
            }

            if (nextState == T_DEAD) {
                position++;
                deadState = true;
                break scan;
            }
            if (nextState == T_FULL_MATCH) {
                return finalizeResult(textBegin, textEnd, endMatch, true, textEnd, true);
            }

            int inputByte = bytes[position] & 0xFF;
            nextState = dfa.computeTransition(tables, stateOffset, inputByte);
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;

            if (nextState != Integer.MIN_VALUE) {
                if (nextState == T_DEAD) {
                    position++;
                    deadState = true;
                    break scan;
                }
                if (nextState == T_FULL_MATCH) {
                    return finalizeResult(textBegin, textEnd, endMatch, true, textEnd, true);
                }
                stateOffset = nextState;
                if ((stateOffset & T_MATCH_BIT) != 0) {
                    matched = true;
                    lastMatchBoundary = position;
                    stateOffset &= ~T_MATCH_BIT;
                    if (wantEarliestMatch && !endMatch) {
                        return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                    }
                }
                position++;
                continue;
            }

            position++;
            if (dfaShouldBailWhenSlow &&
                    resetPosition >= 0 &&
                    (position - resetPosition) < 10 * dfa.stateCount &&
                    !dfa.releaseAbsolutePointerTransitionsForSlowReset()) {
                return SEARCH_FAILED;
            }
            resetPosition = position;
            StateSaver saveStart = new StateSaver(dfa, startOffset);
            StateSaver saveState = new StateSaver(dfa, stateOffset);
            RestoredStates restoredStates = dfa.resetAndRestore(saveStart, saveState);
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;
            startOffset = restoredStates.startOffset();
            stateOffset = restoredStates.stateOffset();
            if (startOffset < 0 || stateOffset < 0) {
                return SEARCH_FAILED;
            }
            nextState = dfa.computeTransition(tables, stateOffset, inputByte);
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;
            if (nextState == Integer.MIN_VALUE) {
                return SEARCH_FAILED;
            }
            if (nextState == T_DEAD) {
                deadState = true;
                break;
            }
            if (nextState == T_FULL_MATCH) {
                return finalizeResult(textBegin, textEnd, endMatch, true, textEnd, true);
            }
            stateOffset = nextState;
            if ((stateOffset & T_MATCH_BIT) != 0) {
                matched = true;
                lastMatchBoundary = position - 1;
                stateOffset &= ~T_MATCH_BIT;
                if (wantEarliestMatch && !endMatch) {
                    return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, true);
                }
            }
        }

        if (!deadState) {
            int lastClass;
            int lastByte;
            if (textEnd == ctxEnd) {
                lastClass = dfa.bytemapRange;
                lastByte = BYTE_END_TEXT;
            }
            else {
                lastByte = bytes[textEnd] & 0xFF;
                lastClass = byteMap[lastByte] & 0xFF;
            }
            int finalState = getTransition(transitions, stateOffset + lastClass);
            if (finalState == T_UNCOMPUTED) {
                finalState = dfa.computeTransition(tables, stateOffset, lastByte);
                if (finalState == Integer.MIN_VALUE) {
                    finalState = computeEndOfTextTransitionAfterReset(dfa, tables, stateOffset, lastByte);
                    if (finalState == Integer.MIN_VALUE) {
                        return SEARCH_FAILED;
                    }
                }
            }
            if (finalState == T_FULL_MATCH || (finalState > 0 && (finalState & T_MATCH_BIT) != 0)) {
                matched = true;
                lastMatchBoundary = position;
            }
        }
        return finalizeResult(textBegin, textEnd, endMatch, matched, lastMatchBoundary, true);
    }

    private static long searchBackward(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int textBegin,
            int textEnd,
            int ctxBegin,
            int startOffset,
            boolean startIsMatch,
            boolean endMatch,
            boolean wantEarliestMatch,
            int maximumBytes)
    {
        int position = textEnd;
        int stateOffset = startOffset;
        if (!startIsMatch &&
                maximumBytes == 0 &&
                !wantEarliestMatch &&
                textEnd - textBegin >= MIN_REVERSE_SELF_LOOP_SKIP_BYTES &&
                VectorSupport.isAvailable()) {
            long skip = skipReverseSelfLoop(dfa, tables, bytes, textBegin, textEnd, startOffset);
            // Computing the loop's exit bytes may have published grown tables.
            tables = dfa.tablesAfterWrite(tables);
            if (skip != REVERSE_SELF_LOOP_NO_SKIP) {
                position = (int) (skip >>> 32);
                stateOffset = (int) skip;
            }
        }

        if (!startIsMatch && maximumBytes == 0 && !endMatch && !wantEarliestMatch && dfa.hasAbsolutePointerTransitions(tables, stateOffset)) {
            long result = searchBackwardAbsolutePointers(dfa, tables, bytes, textBegin, position, ctxBegin, stateOffset);
            if (result != ABSOLUTE_POINTER_RETRY_OBJECT) {
                dfa.absolutePointerBackwardSearchCount++;
                return result;
            }
        }

        int scanBegin = maximumBytes == 0 ? textBegin : Math.max(textBegin, textEnd - maximumBytes);
        boolean bounded = scanBegin > textBegin;
        int lastMatchBoundary = -1;
        boolean matched = false;
        int resetPosition = -1;

        int[] transitions = tables.transitions;
        byte[] byteMap = dfa.bytemap;
        int nextSize = dfa.nextSize;
        boolean deadState = false;

        Object[] stateRefs = dfa.stateReferences(tables, stateOffset);

        if (startIsMatch) {
            matched = true;
            lastMatchBoundary = position;
            if (wantEarliestMatch && !endMatch) {
                return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, false);
            }
        }

        scan:
        while (position > scanBegin) {
            // Tight inner loop using Object[] refs (backward direction)
            for (; position > scanBegin; position--) {
                Object nextRef = getStateReference(stateRefs, byteMap[bytes[position - 1] & 0xFF] & 0xFF);
                if (nextRef == null) {
                    break;
                }
                stateRefs = (Object[]) nextRef;
            }
            if (position <= scanBegin) {
                stateOffset = rowStateOffset(stateRefs, nextSize, transitions);
                break;
            }

            // Inner loop broke on null — consult int[] sidecard
            stateOffset = rowStateOffset(stateRefs, nextSize, transitions);
            int nextState = getTransition(transitions, stateOffset + (byteMap[bytes[position - 1] & 0xFF] & 0xFF));

            // Match state handling (positive with T_MATCH_BIT)
            if (nextState > 0 && (nextState & T_MATCH_BIT) != 0) {
                matched = true;
                lastMatchBoundary = position;
                stateOffset = nextState & ~T_MATCH_BIT;
                if (wantEarliestMatch && !endMatch) {
                    return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, false);
                }
                position--;
                // Object rows hold no match transitions, so each byte of a matching self-loop,
                // such as the reversed `\w+` of `\w+\s+Holmes`, would leave the object loop. In
                // this longest-match reverse search, a program without a loop, such as the reversed
                // `\s[a-zA-Z]{0,12}` of `\s[a-zA-Z]{0,12}ing\s`, matches on a self-loop only
                // through an unanchored search's leading loop, which needs a program that can match
                // empty. Any other skips the probe, which would find nothing after each of its match
                // transitions.
                if (dfa.prog.hasLoop() || dfa.prog.canMatchEmpty()) {
                    int matchingSelfLoopBegin = scanMatchingSelfLoopTransitionsBackward(dfa, tables, bytes, scanBegin, position, stateOffset);
                    if (matchingSelfLoopBegin != position) {
                        dfa.matchingSelfLoopScanCount++;
                        position = matchingSelfLoopBegin;
                        lastMatchBoundary = position + 1;
                    }
                }
                stateRefs = dfa.stateReferences(tables, stateOffset);
                continue;
            }

            // Normal non-match transition that was null (start self-loop for prefix accel)
            if (nextState > 0) {
                stateOffset = nextState;
                position--;
                stateRefs = dfa.stateReferences(tables, stateOffset);
                continue;
            }

            // Sentinel handling
            if (nextState == T_DEAD) {
                position--;
                deadState = true;
                break scan;
            }
            if (nextState == T_FULL_MATCH) {
                if (bounded) {
                    return CANDIDATE_SEARCH_FALLBACK;
                }
                return finalizeResult(textBegin, textEnd, endMatch, true, textBegin, false);
            }

            // T_UNCOMPUTED: run slow path
            int inputByte = bytes[position - 1] & 0xFF;
            nextState = dfa.computeTransition(tables, stateOffset, inputByte);
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;

            if (nextState != Integer.MIN_VALUE) {
                if (nextState == T_DEAD) {
                    position--;
                    deadState = true;
                    break scan;
                }
                if (nextState == T_FULL_MATCH) {
                    if (bounded) {
                        return CANDIDATE_SEARCH_FALLBACK;
                    }
                    return finalizeResult(textBegin, textEnd, endMatch, true, textBegin, false);
                }
                stateOffset = nextState;
                if ((stateOffset & T_MATCH_BIT) != 0) {
                    matched = true;
                    lastMatchBoundary = position;
                    stateOffset &= ~T_MATCH_BIT;
                    if (wantEarliestMatch && !endMatch) {
                        return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, false);
                    }
                }
                position--;
                stateRefs = dfa.stateReferences(tables, stateOffset);
                continue;
            }

            // Cache exhaustion
            position--;
            if (dfaShouldBailWhenSlow &&
                    resetPosition >= 0 &&
                    Math.abs(position - resetPosition) < 10 * dfa.stateCount &&
                    !dfa.releaseAbsolutePointerTransitionsForSlowReset()) {
                return SEARCH_FAILED;
            }
            resetPosition = position;
            StateSaver saveStart = new StateSaver(dfa, startOffset);
            StateSaver saveState = new StateSaver(dfa, stateOffset);
            RestoredStates restoredStates = dfa.resetAndRestore(saveStart, saveState);
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;
            startOffset = restoredStates.startOffset();
            stateOffset = restoredStates.stateOffset();
            if (startOffset < 0 || stateOffset < 0) {
                return SEARCH_FAILED;
            }
            nextState = dfa.computeTransition(tables, stateOffset, inputByte);
            tables = dfa.tablesAfterWrite(tables);
            transitions = tables.transitions;
            if (nextState == Integer.MIN_VALUE) {
                return SEARCH_FAILED;
            }
            if (nextState == T_DEAD) {
                deadState = true;
                break;
            }
            if (nextState == T_FULL_MATCH) {
                if (bounded) {
                    return CANDIDATE_SEARCH_FALLBACK;
                }
                return finalizeResult(textBegin, textEnd, endMatch, true, textBegin, false);
            }
            stateOffset = nextState;
            if ((stateOffset & T_MATCH_BIT) != 0) {
                matched = true;
                lastMatchBoundary = position + 1;
                stateOffset &= ~T_MATCH_BIT;
                if (wantEarliestMatch && !endMatch) {
                    return finalizeResult(textBegin, textEnd, endMatch, true, lastMatchBoundary, false);
                }
            }
            stateRefs = dfa.stateReferences(tables, stateOffset);
        }

        if (bounded && !deadState && position <= scanBegin) {
            return CANDIDATE_SEARCH_FALLBACK;
        }

        // End-of-text transition
        if (!deadState) {
            int lastClass;
            int lastByte;
            if (textBegin == ctxBegin) {
                lastClass = dfa.bytemapRange;
                lastByte = BYTE_END_TEXT;
            }
            else {
                lastByte = bytes[textBegin - 1] & 0xFF;
                lastClass = byteMap[lastByte] & 0xFF;
            }
            int finalState = getTransition(transitions, stateOffset + lastClass);
            if (finalState == T_UNCOMPUTED) {
                finalState = dfa.computeTransition(tables, stateOffset, lastByte);
                if (finalState == Integer.MIN_VALUE) {
                    finalState = computeEndOfTextTransitionAfterReset(dfa, tables, stateOffset, lastByte);
                    if (finalState == Integer.MIN_VALUE) {
                        return SEARCH_FAILED;
                    }
                }
            }
            if (finalState == T_FULL_MATCH || (finalState > 0 && (finalState & T_MATCH_BIT) != 0)) {
                matched = true;
                lastMatchBoundary = position;
            }
        }
        return finalizeResult(textBegin, textEnd, endMatch, matched, lastMatchBoundary, false);
    }

    // Steps back through a few bytes from textEnd, and when they reach a non-matching state that
    // loops on itself with a small set of exit bytes, without a match on the way, returns the
    // position after the last exit byte before them, or textBegin, in the high half and that state
    // in the low half. The search continues from there as if it had stepped every skipped byte,
    // since each of them returns to the state without a match. Otherwise returns
    // REVERSE_SELF_LOOP_NO_SKIP, and the search starts at textEnd as before. Only transitions the
    // cache already holds are followed; computing the loop's exit bytes needs exclusive access
    // the first time, as for the forward self-loop scans.
    private static long skipReverseSelfLoop(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int textBegin,
            int textEnd,
            int startOffset)
    {
        int[] transitions = tables.transitions;
        byte[] byteMap = dfa.bytemap;
        int position = textEnd;
        int stateOffset = startOffset;
        for (int step = 0; step < REVERSE_SELF_LOOP_PROBE_STEPS; step++) {
            int nextState = getTransition(transitions, stateOffset + (byteMap[bytes[position - 1] & 0xFF] & 0xFF));
            // Uncomputed, dead, and full-match sentinels are negative and leave the search as it was,
            // as does a match, which the skipped bytes would have to record.
            if (nextState < 0 || (nextState & T_MATCH_BIT) != 0) {
                return REVERSE_SELF_LOOP_NO_SKIP;
            }
            position--;
            if (nextState != stateOffset) {
                stateOffset = nextState;
                continue;
            }

            // The state is not a match state: the transition into it carries no match bit. The
            // backward scans read only the exit form.
            long exitSet = dfa.countSelfLoopExitByteSet(tables, stateOffset);
            if (exitSet <= 0) {
                return REVERSE_SELF_LOOP_NO_SKIP;
            }
            int exitByteSet = (int) exitSet;
            int exitPosition;
            if ((exitByteSet & SELF_LOOP_EXIT_BYTE_SET_HIGH) != 0) {
                exitPosition = VectorByteSetScanner.findLastHighBitOr(
                        bytes,
                        textBegin,
                        position - textBegin,
                        (byte) exitByteSet,
                        (byte) (exitByteSet >>> 8),
                        (byte) (exitByteSet >>> 16),
                        (exitByteSet >>> SELF_LOOP_EXIT_BYTE_COUNT_SHIFT) & SELF_LOOP_EXIT_BYTE_COUNT_MASK);
            }
            else {
                exitPosition = VectorByteSetScanner.findLast(
                        bytes,
                        textBegin,
                        position - textBegin,
                        (byte) exitByteSet,
                        (byte) (exitByteSet >>> 8),
                        (byte) (exitByteSet >>> 16),
                        (exitByteSet >>> SELF_LOOP_EXIT_BYTE_COUNT_SHIFT) & SELF_LOOP_EXIT_BYTE_COUNT_MASK);
            }
            dfa.reverseSelfLoopExitByteScanCount++;
            int runBegin = exitPosition < 0 ? textBegin : exitPosition + 1;
            return ((long) runBegin << 32) | (stateOffset & 0xFFFF_FFFFL);
        }
        return REVERSE_SELF_LOOP_NO_SKIP;
    }

    // Searches backward from textEnd, which is either the end of the search or runBegin, the first
    // byte of the run the self-loop skip passed over, starting in the state at startOffset.
    private static long searchBackwardAbsolutePointers(
            DfaInstance dfa,
            CacheTables tables,
            byte[] bytes,
            int textBegin,
            int textEnd,
            int ctxBegin,
            int startOffset)
    {
        AbsolutePointerTransitions absolutePointers = tables.absolutePointerTransitions;
        long tableAddress = absolutePointers.baseAddress();
        long transitionRowAddress = tableAddress + ((long) startOffset * Long.BYTES);
        int position = textEnd;
        boolean matched = false;
        int lastMatchBoundary = -1;
        try {
            MemorySegment addressSpace = HEAP_POINTER_TABLES ? absolutePointers.segment() : AbsoluteAddressSpace.EVERYTHING;
            byte[] byteMap = dfa.bytemap;
            for (; position > textBegin; position--) {
                long transition = addressSpace.get(
                        ValueLayout.JAVA_LONG_UNALIGNED,
                        transitionRowAddress + ((long) (byteMap[bytes[position - 1] & 0xFF] & 0xFF) * Long.BYTES));
                if (transition == 0) {
                    return ABSOLUTE_POINTER_RETRY_OBJECT;
                }
                long tag = transition & ABSOLUTE_POINTER_TAG_MASK;
                if (tag == 0) {
                    transitionRowAddress = transition;
                    continue;
                }
                if (tag == ABSOLUTE_POINTER_MATCH_TAG) {
                    matched = true;
                    lastMatchBoundary = position;
                    transitionRowAddress = transition & ~ABSOLUTE_POINTER_TAG_MASK;
                    continue;
                }
                if (tag == ABSOLUTE_POINTER_DEAD_TAG) {
                    return finalizeResult(textBegin, textEnd, false, matched, lastMatchBoundary, false);
                }
                if (tag == ABSOLUTE_POINTER_FULL_MATCH_TAG) {
                    return finalizeResult(textBegin, textEnd, false, true, textBegin, false);
                }
                throw new AssertionError("unexpected absolute-pointer transition tag");
            }

            int finalClass = textBegin == ctxBegin ? dfa.bytemapRange : byteMap[bytes[textBegin - 1] & 0xFF] & 0xFF;
            long finalTransition = addressSpace.get(
                    ValueLayout.JAVA_LONG_UNALIGNED,
                    transitionRowAddress + ((long) finalClass * Long.BYTES));
            if (finalTransition == 0) {
                return ABSOLUTE_POINTER_RETRY_OBJECT;
            }
            long tag = finalTransition & ABSOLUTE_POINTER_TAG_MASK;
            if (tag == ABSOLUTE_POINTER_MATCH_TAG || tag == ABSOLUTE_POINTER_FULL_MATCH_TAG) {
                matched = true;
                lastMatchBoundary = position;
            }
            else if (tag != 0 && tag != ABSOLUTE_POINTER_DEAD_TAG) {
                throw new AssertionError("unexpected absolute-pointer transition tag");
            }
            return finalizeResult(textBegin, textEnd, false, matched, lastMatchBoundary, false);
        }
        finally {
            // Raw native addresses do not keep their automatic-arena owner reachable.
            Reference.reachabilityFence(absolutePointers);
        }
    }

    // Called when the end-of-text transition from the state at stateOffset does not fit the cache.
    // As in upstream, the search resets the cache and computes the transition again, without the
    // mid-text step's check for resetting too often, and fails only when the restored state or the
    // transition does not fit the reset cache either. Returns the transition or Integer.MIN_VALUE.
    private static int computeEndOfTextTransitionAfterReset(DfaInstance dfa, CacheTables tables, int stateOffset, int lastByte)
    {
        StateSaver savedState = new StateSaver(dfa, stateOffset);
        RestoredStates restoredStates = dfa.resetAndRestore(savedState, savedState);
        tables = dfa.tablesAfterWrite(tables);
        int restoredOffset = restoredStates.stateOffset();
        if (restoredOffset < 0) {
            return Integer.MIN_VALUE;
        }
        return dfa.computeTransition(tables, restoredOffset, lastByte);
    }

    private static long finalizeResult(int textBegin, int textEnd, boolean endMatch, boolean matched, int lastMatchBoundaryAbs, boolean runForward)
    {
        if (!matched || lastMatchBoundaryAbs < 0) {
            return SEARCH_NO_MATCH;
        }
        int expectedBoundary = runForward ? textEnd : textBegin;
        int end = lastMatchBoundaryAbs - textBegin;
        if (endMatch && lastMatchBoundaryAbs != expectedBoundary) {
            return SEARCH_NO_MATCH;
        }
        return end;
    }

    private static final int BYTE_END_TEXT = 256;
    // A state's flag word. Bits 0-13 are the assertions that hold at the state's position: the
    // first eight EmptyOp bits, then the DFA's own bits for the Rust CRLF anchors and word sides.
    // Rust's Unicode \b and \B and Java's and Trino's \b and \B use the EmptyOp word boundary
    // bits; dfaEmptyFlags maps each of them. Bit 14 marks a match, bits 15-17 describe the byte
    // before the position, and the bits from FLAG_NEED_SHIFT up are the assertions the state's
    // instructions need, numbered as bits 0-13.
    private static final int FLAG_CRLF_BEGIN_LINE = 1 << 8;
    private static final int FLAG_CRLF_END_LINE = 1 << 9;
    private static final int FLAG_WORD_BEFORE = 1 << 10;
    private static final int FLAG_NON_WORD_BEFORE = 1 << 11;
    private static final int FLAG_WORD_AFTER = 1 << 12;
    private static final int FLAG_NON_WORD_AFTER = 1 << 13;
    static final int FLAG_MATCH = 1 << 14;
    private static final int FLAG_EMPTY_MASK = FLAG_MATCH - 1;
    // The byte before the state's position: an ASCII word byte; the byte that starts a CR LF pair
    // in the direction of the search, CR forward and LF in reverse, whether or not the other byte
    // follows; or a byte outside ASCII.
    private static final int FLAG_LAST_WORD = 1 << 15;
    private static final int FLAG_HALF_CRLF = 1 << 16;
    private static final int FLAG_LAST_NON_ASCII = 1 << 17;
    private static final int FLAG_NEED_SHIFT = 18;

    // Transition table sentinel values (stored in transitions[])
    static final int T_UNCOMPUTED = -3;    // not yet computed (must have sign bit set for isAbnormal)
    static final int T_DEAD = -1;          // dead state
    static final int T_FULL_MATCH = -2;    // rest of string matches

    private static final long SELF_LOOP_EXIT_BYTE_SET_REJECTED = -1;
    private static final int SELF_LOOP_EXIT_BYTE_COUNT_SHIFT = 24;
    private static final int SELF_LOOP_EXIT_BYTE_COUNT_MASK = 0x7;
    private static final int SELF_LOOP_EXIT_BYTE_SET_MATCHING = 1 << 27;
    private static final int SELF_LOOP_EXIT_BYTE_SET_HIGH = 1 << 28;
    // A self-loop that leaves on more bytes than the exit form lists is described by the bytes that
    // stay in it: one to four ranges in the layout VectorByteSetScanner.findOutsideRanges reads,
    // range i in bits 16i to 16i + 14. The bit above each range is free there, and holds the
    // stay form's own flags: bit 63 marks the form, which makes the set negative; bit 47 marks a
    // matching self-loop; and bits 15 and 31 hold the range count less one, low bit first.
    private static final long SELF_LOOP_STAY_RANGES = 1L << 63;
    private static final long SELF_LOOP_STAY_RANGES_MATCHING = 1L << 47;
    private static final long SELF_LOOP_STAY_RANGES_MASK = 0x7FFF_7FFF_7FFF_7FFFL;
    private static final int SELF_LOOP_MAX_STAY_RANGES = 4;

    // Match bit: OR'd into transition values pointing to match states
    static final int T_MATCH_BIT = 1 << 30;

    private static final int PAIR_UNCOMPUTED = 0;
    private static final int PAIR_DEAD = -1;
    private static final int PAIR_FULL_MATCH = -2;
    private static final int PAIR_MATCH_FIRST = 1;
    private static final int PAIR_MATCH_SECOND = 2;
    private static final int PAIR_NORMAL_FIRST = 3;
    private static final int PAIR_NORMAL_SECOND = 4;

    // A flat paired row offset or slot holding no paired row, or no precomputed normal paired
    // transition.
    private static final int NO_PAIRED_ROW = -1;

    // Maximum transitions array length - ensures offsets fit below T_MATCH_BIT
    private static final int MAX_TRANSITIONS_LENGTH = 1 << 30;

    // Special instruction IDs for mark support (dfa.cc:357-361)
    private static final int Mark = -1;
    private static final int MatchSep = -2;

    private static boolean isWordChar(int c)
    {
        int b = c & 0xFF;
        return ('A' <= b && b <= 'Z') ||
                ('a' <= b && b <= 'z') ||
                ('0' <= b && b <= '9') ||
                b == '_';
    }

    /// Returns the DFA's assertion bits for an instruction's EmptyOp bits: the first eight EmptyOp
    /// bits unchanged, and each Rust assertion and Java or Trino word boundary as the DFA's
    /// evaluation of it. The other EmptyOp bits are dropped; programs that need them never reach
    /// the DFA.
    private static int dfaEmptyFlags(int empty)
    {
        int mappedAssertions = empty & (EmptyOp.RUST_ASSERTIONS | EmptyOp.JAVA_TRINO_WORD_BOUNDARIES);
        int flags = empty & 0xFF;
        while (mappedAssertions != 0) {
            int assertion = Integer.lowestOneBit(mappedAssertions);
            flags |= assertionFlag(assertion);
            mappedAssertions ^= assertion;
        }
        return flags;
    }

    // The DFA evaluates a word assertion that decodes the text only where each neighbor is an
    // ASCII byte or an edge of the text, and quits before evaluating it anywhere else. Between
    // those neighbors each byte is its own character, every side decodes, and the word characters
    // are the ASCII ones: Rust's and Trino's Unicode word characters, and Java's, whose
    // nonspacing-mark rule reads only a character outside ASCII. So each assertion is its ASCII
    // form. The valid-UTF-8 assertions map to nothing because the compiler always pairs one with
    // a non-word side in the same instruction, and that side carries the evaluation.
    private static int assertionFlag(int assertion)
    {
        return switch (assertion) {
            case EmptyOp.EMPTY_RUST_CRLF_BEGIN_LINE -> FLAG_CRLF_BEGIN_LINE;
            case EmptyOp.EMPTY_RUST_CRLF_END_LINE -> FLAG_CRLF_END_LINE;
            case EmptyOp.EMPTY_RUST_WORD_BEFORE, EmptyOp.EMPTY_RUST_UNICODE_WORD_BEFORE -> FLAG_WORD_BEFORE;
            case EmptyOp.EMPTY_RUST_NON_WORD_BEFORE, EmptyOp.EMPTY_RUST_UNICODE_NON_WORD_BEFORE -> FLAG_NON_WORD_BEFORE;
            case EmptyOp.EMPTY_RUST_WORD_AFTER, EmptyOp.EMPTY_RUST_UNICODE_WORD_AFTER -> FLAG_WORD_AFTER;
            case EmptyOp.EMPTY_RUST_NON_WORD_AFTER, EmptyOp.EMPTY_RUST_UNICODE_NON_WORD_AFTER -> FLAG_NON_WORD_AFTER;
            case EmptyOp.EMPTY_RUST_UNICODE_WORD_BOUNDARY,
                 EmptyOp.EMPTY_UNICODE_WORD_BOUNDARY,
                 EmptyOp.EMPTY_JAVA_WORD_BOUNDARY,
                 EmptyOp.EMPTY_JAVA_UNICODE_WORD_BOUNDARY -> EmptyOp.EMPTY_WORD_BOUNDARY;
            case EmptyOp.EMPTY_RUST_NO_UNICODE_WORD_BOUNDARY,
                 EmptyOp.EMPTY_NO_UNICODE_WORD_BOUNDARY,
                 EmptyOp.EMPTY_JAVA_NO_WORD_BOUNDARY,
                 EmptyOp.EMPTY_JAVA_NO_UNICODE_WORD_BOUNDARY -> EmptyOp.EMPTY_NO_WORD_BOUNDARY;
            case EmptyOp.EMPTY_RUST_UNICODE_VALID_BEFORE, EmptyOp.EMPTY_RUST_UNICODE_VALID_AFTER -> 0;
            default -> throw new IllegalArgumentException("not a DFA-mapped assertion: " + assertion);
        };
    }

    /// Runs the unanchored boolean search of [#search] with `wantMatchBoundary` false. Only a
    /// program with a position automaton, a cheap linear engine for a skipped search, takes
    /// part in the skip: its search judges the resets of the forward DFA across boolean
    /// searches, and after two slow resets in a row the next 65,536 boolean searches of the
    /// program return [#BOOLEAN_SEARCH_SKIPPED] without running the DFA, then one tries it
    /// again. The caller answers a skipped search from that automaton. "Count Fallback After
    /// Repeated Bails" in REGULATOR_DECISIONS.md gives the rule.
    static long searchJudgingSlowResets(Prog prog, Slice context, int contextStart, int contextEnd, int start, int end, Prog.MatchKind matchKind)
    {
        // This method sits after the search loops on purpose: javac numbers the constant pool in
        // method order, and placed before them, its new entries would push about a hundred of the
        // loops' constants past the one-byte `ldc` range and grow their bytecode.

        // An earliest-match search runs the longest-match DFA for every match kind a boolean
        // search passes, as search chooses it.
        DfaInstance dfa = prog.positionNfa() == null ? null : prog.cachedDfaIfPresent(DfaInstance.Kind.LONGEST_MATCH);
        if (dfa == null) {
            // A DFA this search builds starts from no resets, and the next search seeds the
            // allowance of a reset this one causes.
            return search(prog, context, contextStart, contextEnd, start, end, false, matchKind, false);
        }
        if (dfa.takeBooleanSearchSkip()) {
            return BOOLEAN_SEARCH_SKIPPED;
        }
        int resetsBefore = dfa.startBooleanSearch();
        long result = search(prog, context, contextStart, contextEnd, start, end, false, matchKind, false);
        dfa.judgeBooleanSearch(resetsBefore, result, end - start);
        return result;
    }

    static final class RetrySearchException
            extends RuntimeException
    {
        private RetrySearchException()
        {
            super(null, null, false, false);
        }
    }

    // A transition may evaluate a decoding word assertion next to a byte outside ASCII. The search
    // fails, and the caller reruns it on an engine that decodes the text.
    private static final class QuitSearchException
            extends RuntimeException
    {
        private QuitSearchException()
        {
            super(null, null, false, false);
        }
    }

    private static final class StartStateAllocationException
            extends RuntimeException
    {
        private StartStateAllocationException()
        {
            super(null, null, false, false);
        }
    }

    private static final class TestingFailureError
            extends Error
    {
        private TestingFailureError()
        {
            super(null, null, false, false);
        }
    }

    // A pointer table: a native segment owned by its automatic arena, or a heap segment over a
    // `long[]` with no arena and base address zero. `allocatedBytes` is its charge, the native bytes
    // or the array's size.
    private record AbsolutePointerTransitions(
            Arena arena,
            MemorySegment segment,
            long baseAddress,
            int entryCapacity,
            long allocatedBytes) {}

    // The enlarged pointer table a growth will allocate, and the charge it adds. An
    // `allocatedBytes` of zero means the native superseded-segment cap is full: the table is
    // withheld rather than enlarged, and `memoryDelta` refunds the current segment's charge.
    private record AbsolutePointerGrowthSize(int entryCapacity, long allocatedBytes, long memoryDelta)
    {
        boolean withheld()
        {
            return allocatedBytes == 0;
        }
    }

    // A growth whose transitions are null withholds the table instead: the enlarged segment was
    // not allocated, and the delta refunds the current segment's charge. `allocationFailed` tells
    // a failed allocation from a full native cap.
    private record AbsolutePointerGrowth(
            AbsolutePointerTransitions transitions,
            int transitionCount,
            long memoryDelta,
            boolean allocationFailed) {}

    // A reference to a native pointer table a reset or growth superseded. The collector enqueues
    // this reference once the superseded native table is unreachable; the DFA links the reference
    // until then, so it stays reachable, and releases the table's bytes from its count when it next
    // checks the cap. The native memory itself is freed by the automatic arena's cleaner in the
    // JDK; this reference releases only the DFA's count of it.
    private static final class SupersededAbsolutePointerReference
            extends PhantomReference<AbsolutePointerTransitions>
    {
        private final long allocatedBytes;
        private SupersededAbsolutePointerReference previous;
        private SupersededAbsolutePointerReference next;

        private SupersededAbsolutePointerReference(AbsolutePointerTransitions absolutePointers, ReferenceQueue<AbsolutePointerTransitions> queue)
        {
            super(absolutePointers, queue);
            this.allocatedBytes = absolutePointers.allocatedBytes();
        }
    }

    /// One generation of a DFA cache, published whole. A search reads the tables it began with and
    /// never sees them cleared: a reset publishes fresh tables and leaves these to their readers.
    /// Growth, paired rows, and the pointer table publish new tables that share the arrays they
    /// leave unchanged, and the writer fills slots of the current tables in place. Each slot is
    /// written at most once, so a reader sees it empty or final, and publication through the
    /// volatile field makes every array, and every slot written before it, visible with the tables.
    static final class CacheTables
    {
        // Flat transition table (hot path)
        final int[] transitions;
        // State metadata (cold path)
        final StateData[] stateData;
        final Object[][] stateRefArrays;  // parallel to stateData[], indexed by stateIndex
        // Zero is unknown, -1 is rejected, and accepted entries in the exit form pack up to three
        // exits below a three-bit count in their low 32 bits. Bit 27 marks a matching self-loop,
        // whose transitions carry the match bit. Bit 28 marks a row where every byte 0x80..0xFF
        // exits; the packed exits are then ASCII only. Negative accepted entries are in the stay
        // form that SELF_LOOP_STAY_RANGES describes.
        final long[] selfLoopExitByteSets;
        // Start state caches
        final StateData[][] startBeginText;
        final StateData[][] startBeginLine;
        final StateData[][] startTrinoLine;
        final StateData[][] startAfterWord;
        final StateData[][] startAfterNonWord;
        // Only programs with Rust CRLF anchors have the first, and only programs with a word
        // assertion that decodes the text have the second.
        final StateData[][] startAfterCarriageReturn;
        final StateData[][] startAfterNonAscii;
        // Paired rows in the form FLAT_PAIRED_ROWS selects; the other form's fields stay null. The
        // selected form is built whole before publication and never written after. The object rows,
        // pairedStateRefArrays, are indexed by stateIndex and sized at the last rebuild; unselected
        // rows are null. In the flat form, pairedRowOffsets is indexed the same way and holds the byte
        // offset of the state's row in flatPairedRows, or NO_PAIRED_ROW for an unselected state.
        // flatPairedRows, a segment over an int array, holds one row of `nextSize * nextSize + 1`
        // ints for each selected state: for each pair of byte classes the byte offset of the row that
        // pair reaches, or NO_PAIRED_ROW, then the state's offset in `transitions`. Byte offsets keep
        // the scaling off a step's chain of dependent loads.
        final Object[][] pairedStateRefArrays;
        final int[] pairedRowOffsets;
        final MemorySegment flatPairedRows;
        final AbsolutePointerTransitions absolutePointerTransitions;

        private CacheTables(
                int[] transitions,
                StateData[] stateData,
                Object[][] stateRefArrays,
                long[] selfLoopExitByteSets,
                StateData[][] startBeginText,
                StateData[][] startBeginLine,
                StateData[][] startTrinoLine,
                StateData[][] startAfterWord,
                StateData[][] startAfterNonWord,
                StateData[][] startAfterCarriageReturn,
                StateData[][] startAfterNonAscii,
                Object[][] pairedStateRefArrays,
                int[] pairedRowOffsets,
                MemorySegment flatPairedRows,
                AbsolutePointerTransitions absolutePointerTransitions)
        {
            this.transitions = transitions;
            this.stateData = stateData;
            this.stateRefArrays = stateRefArrays;
            this.selfLoopExitByteSets = selfLoopExitByteSets;
            this.startBeginText = startBeginText;
            this.startBeginLine = startBeginLine;
            this.startTrinoLine = startTrinoLine;
            this.startAfterWord = startAfterWord;
            this.startAfterNonWord = startAfterNonWord;
            this.startAfterCarriageReturn = startAfterCarriageReturn;
            this.startAfterNonAscii = startAfterNonAscii;
            this.pairedStateRefArrays = pairedStateRefArrays;
            this.pairedRowOffsets = pairedRowOffsets;
            this.flatPairedRows = flatPairedRows;
            this.absolutePointerTransitions = absolutePointerTransitions;
        }

        private boolean hasPairedRows()
        {
            return FLAT_PAIRED_ROWS ? flatPairedRows != null : pairedStateRefArrays != null;
        }

        // Empty tables sized for `stateCapacity` states: no states, start states, paired rows, or
        // pointer table.
        private static CacheTables create(
                int stateCapacity,
                int nextSize,
                boolean trinoLineStarts,
                boolean carriageReturnStarts,
                boolean nonAsciiStarts)
        {
            int[] transitions = new int[stateCapacity * nextSize];
            Arrays.fill(transitions, T_UNCOMPUTED);
            return new CacheTables(
                    transitions,
                    new StateData[stateCapacity],
                    new Object[stateCapacity][],
                    new long[stateCapacity],
                    new StateData[2][2],
                    new StateData[2][2],
                    trinoLineStarts ? new StateData[2][2] : null,
                    new StateData[2][2],
                    new StateData[2][2],
                    carriageReturnStarts ? new StateData[2][2] : null,
                    nonAsciiStarts ? new StateData[2][2] : null,
                    null,
                    null,
                    null,
                    null);
        }

        private CacheTables withStates(int[] transitions, StateData[] stateData, Object[][] stateRefArrays, long[] selfLoopExitByteSets)
        {
            return new CacheTables(
                    transitions,
                    stateData,
                    stateRefArrays,
                    selfLoopExitByteSets,
                    startBeginText,
                    startBeginLine,
                    startTrinoLine,
                    startAfterWord,
                    startAfterNonWord,
                    startAfterCarriageReturn,
                    startAfterNonAscii,
                    pairedStateRefArrays,
                    pairedRowOffsets,
                    flatPairedRows,
                    absolutePointerTransitions);
        }

        private CacheTables withPairedRows(Object[][] pairedStateRefArrays, int[] pairedRowOffsets, MemorySegment flatPairedRows)
        {
            return new CacheTables(
                    transitions,
                    stateData,
                    stateRefArrays,
                    selfLoopExitByteSets,
                    startBeginText,
                    startBeginLine,
                    startTrinoLine,
                    startAfterWord,
                    startAfterNonWord,
                    startAfterCarriageReturn,
                    startAfterNonAscii,
                    pairedStateRefArrays,
                    pairedRowOffsets,
                    flatPairedRows,
                    absolutePointerTransitions);
        }

        private CacheTables withAbsolutePointerTransitions(AbsolutePointerTransitions absolutePointerTransitions)
        {
            return new CacheTables(
                    transitions,
                    stateData,
                    stateRefArrays,
                    selfLoopExitByteSets,
                    startBeginText,
                    startBeginLine,
                    startTrinoLine,
                    startAfterWord,
                    startAfterNonWord,
                    startAfterCarriageReturn,
                    startAfterNonAscii,
                    pairedStateRefArrays,
                    pairedRowOffsets,
                    flatPairedRows,
                    absolutePointerTransitions);
        }
    }

    // The offset of the state an object row belongs to. Grown tables share rows with the tables
    // they replace, so a row reached through older tables may belong to a state beyond their
    // `transitions`; a row read before its sentinel is visible has none. Either retries.
    static int rowStateOffset(Object[] stateReferences, int nextSize, int[] transitions)
    {
        if (!(getStateReference(stateReferences, nextSize) instanceof StateData state) || state.offset() >= transitions.length) {
            throw RETRY_SEARCH;
        }
        return state.offset();
    }

    // Loaded only with native access, because `reinterpret` is restricted: heap pointer tables read
    // through their own segment and never initialize this holder.
    @SuppressWarnings("restricted")
    private static final class AbsoluteAddressSpace
    {
        private static final MemorySegment EVERYTHING = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

        private AbsoluteAddressSpace() {}
    }

    private static final class PointerReaderProbe
    {
        private ProbeSession session;

        synchronized void arm(Thread target)
        {
            if (session != null) {
                throw new IllegalStateException("pointer reader probe is already armed");
            }
            session = new ProbeSession(requireNonNull(target, "target is null"));
        }

        void pauseIfTarget()
        {
            ProbeSession currentSession;
            synchronized (this) {
                currentSession = session;
            }
            if (currentSession == null || currentSession.target() != Thread.currentThread() || !currentSession.triggered().compareAndSet(false, true)) {
                return;
            }
            currentSession.reached().countDown();
            try {
                currentSession.release().await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("pointer reader probe interrupted", e);
            }
        }

        boolean awaitReached(long timeout, TimeUnit unit)
                throws InterruptedException
        {
            ProbeSession currentSession;
            synchronized (this) {
                currentSession = session;
            }
            return currentSession != null && currentSession.reached().await(timeout, unit);
        }

        synchronized void release()
        {
            if (session == null) {
                throw new IllegalStateException("pointer reader probe is not armed");
            }
            session.release().countDown();
            session = null;
        }
    }

    private record ProbeSession(
            Thread target,
            AtomicBoolean triggered,
            CountDownLatch reached,
            CountDownLatch release)
    {
        private ProbeSession(Thread target)
        {
            this(target, new AtomicBoolean(), new CountDownLatch(1), new CountDownLatch(1));
        }
    }

    static void armPointerReaderProbeForTesting(Thread target)
    {
        requireNonNull(POINTER_READER_PROBE, "pointer reader probe is disabled").arm(target);
    }

    static boolean awaitPointerReaderProbeForTesting(long timeout, TimeUnit unit)
            throws InterruptedException
    {
        return requireNonNull(POINTER_READER_PROBE, "pointer reader probe is disabled").awaitReached(timeout, unit);
    }

    static void releasePointerReaderProbeForTesting()
    {
        requireNonNull(POINTER_READER_PROBE, "pointer reader probe is disabled").release();
    }

    record CacheSnapshot(
            int stateCount,
            int cacheEntries,
            int populatedStateData,
            int populatedStateReferences,
            int populatedStartStates,
            int pairedRows,
            long pairedTransitionMemory,
            long fixedDistanceByteCandidateMemory,
            long availableStateMemory,
            long stateBudget,
            int transitionCapacity,
            int stateCapacity,
            int stateReferenceCapacity,
            int resetCount) {}

    record StateMemorySnapshot(
            long totalBytes,
            long transitionBytes,
            long stateDataArrayBytes,
            long stateReferenceArrayBytes,
            long selfLoopExitByteSetBytes,
            long optionalStartStateCacheBytes,
            long stateReferenceRowBytes,
            long instructionBytes,
            long stateMetadataBytes,
            long cacheBytes,
            int cacheTableCapacity) {}

    record FailureAtomicitySnapshot(
            int transitionCapacity,
            int stateCapacity,
            int stateReferenceCapacity,
            int selfLoopCapacity,
            int nextFreeOffset,
            int stateCount,
            int cacheEntries,
            int cacheTableCapacity,
            Object cacheStorage,
            long cacheContentVersion,
            long availableStateMemory,
            long retainedStateMemory,
            Object pairedStorage,
            int pairedRows,
            long pairedTransitionMemory,
            long pairedContentVersion,
            Object absolutePointerStorage,
            int absolutePointerEntryCapacity,
            long absolutePointerMemory,
            int absolutePointerTransitionCount) {}

    /// Cold-path state metadata. Replaces the old per-state object with next[] array.
    /// The actual transitions live in the flat `CacheTables.transitions` table.
    record StateData(int[] inst, int flag, int offset) {}

    private record RestoredStates(int startOffset, int stateOffset) {}

    // Sentinel StateData values
    private static final StateData DEAD_DATA = new StateData(new int[0], 0, T_DEAD);
    private static final StateData FULL_MATCH_DATA = new StateData(new int[0], FLAG_MATCH, T_FULL_MATCH);

    private static final class StateKey
    {
        private final int flag;
        private final int[] inst;
        private final int hash;

        StateKey(int[] inst, int flag)
        {
            this.flag = flag;
            this.inst = inst;
            int h = Integer.hashCode(flag);
            for (int i = 0; i < inst.length; i++) {
                h = (31 * h) + inst[i];
            }
            this.hash = h;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (!(o instanceof StateKey other)) {
                return false;
            }
            if (flag != other.flag || inst.length != other.inst.length) {
                return false;
            }
            for (int i = 0; i < inst.length; i++) {
                if (inst[i] != other.inst[i]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode()
        {
            return hash;
        }
    }

    /// Append-only state lookup table. State construction is serialized, so insertion can stage all
    /// allocation before publishing either one empty slot or a fully populated replacement table.
    private static final class StateCache
    {
        private static final int INITIAL_CAPACITY = 16;

        private Object[] entries;
        private int size;

        StateData get(StateKey key)
        {
            Object[] entries = this.entries;
            if (entries == null) {
                return null;
            }
            int entryIndex = entryIndex(key, entries.length / 2);
            while (true) {
                StateKey existingKey = (StateKey) entries[entryIndex];
                if (existingKey == null) {
                    return null;
                }
                if (existingKey.equals(key)) {
                    return (StateData) entries[entryIndex + 1];
                }
                entryIndex = nextEntryIndex(entryIndex, entries.length);
            }
        }

        int size()
        {
            return size;
        }

        int capacity()
        {
            return entries == null ? 0 : entries.length / 2;
        }

        Object storage()
        {
            return entries;
        }

        long tableBytes()
        {
            return entries == null ? 0 : sizeOfObjectArray(entries.length);
        }

        int capacityAfterInsert()
        {
            int capacity = capacity();
            if (capacity != 0 && size + 1 <= (capacity * 3L) / 4) {
                return capacity;
            }
            return capacity == 0 ? INITIAL_CAPACITY : Math.multiplyExact(capacity, 2);
        }

        PreparedInsertion prepareInsert(StateKey key, StateData value, int newCapacity)
        {
            Object[] currentEntries = entries;
            int currentCapacity = capacity();
            if (newCapacity == currentCapacity) {
                return new PreparedInsertion(currentEntries, entryIndexForInsert(currentEntries, key), key, value, false);
            }

            Object[] replacement = new Object[Math.multiplyExact(newCapacity, 2)];
            if (currentEntries != null) {
                for (int index = 0; index < currentEntries.length; index += 2) {
                    StateKey existingKey = (StateKey) currentEntries[index];
                    if (existingKey != null) {
                        int replacementIndex = entryIndexForInsert(replacement, existingKey);
                        replacement[replacementIndex] = existingKey;
                        replacement[replacementIndex + 1] = currentEntries[index + 1];
                    }
                }
            }
            int replacementIndex = entryIndexForInsert(replacement, key);
            replacement[replacementIndex] = key;
            replacement[replacementIndex + 1] = value;
            return new PreparedInsertion(replacement, replacementIndex, key, value, true);
        }

        void commit(PreparedInsertion insertion)
        {
            if (insertion.replacement()) {
                entries = insertion.entries();
            }
            else {
                insertion.entries()[insertion.entryIndex()] = insertion.key();
                insertion.entries()[insertion.entryIndex() + 1] = insertion.value();
            }
            size++;
        }

        void clear()
        {
            if (entries != null) {
                Arrays.fill(entries, null);
            }
            size = 0;
        }

        private static int entryIndexForInsert(Object[] entries, StateKey key)
        {
            int entryIndex = entryIndex(key, entries.length / 2);
            while (entries[entryIndex] != null) {
                entryIndex = nextEntryIndex(entryIndex, entries.length);
            }
            return entryIndex;
        }

        private static int entryIndex(StateKey key, int capacity)
        {
            int hash = key.hashCode();
            hash ^= hash >>> 16;
            return (hash & (capacity - 1)) * 2;
        }

        private static int nextEntryIndex(int entryIndex, int entriesLength)
        {
            return (entryIndex + 2) & (entriesLength - 1);
        }

        private record PreparedInsertion(
                Object[] entries,
                int entryIndex,
                StateKey key,
                StateData value,
                boolean replacement) {}
    }

    /// Work queue for DFA state construction.
    /// Ported from dfa.cc:368-418.
    private static final class WorkQueue
    {
        private final SparseSet set;
        private final int n;
        private final int maxmark;
        private int nextmark;
        private boolean lastWasMark;

        WorkQueue(int n, int maxmark)
        {
            this.set = new SparseSet(n + maxmark);
            this.n = n;
            this.maxmark = maxmark;
            this.nextmark = n;
            this.lastWasMark = true;
        }

        boolean isMark(int i)
        {
            return i >= n;
        }

        int maxmark()
        {
            return maxmark;
        }

        void clear()
        {
            set.clear();
            nextmark = n;
        }

        void mark()
        {
            if (lastWasMark) {
                return;
            }
            lastWasMark = true;
            if (nextmark - n >= maxmark) {
                throw new AssertionError("work queue needs more than its " + maxmark + " mark slots");
            }
            set.insertNew(nextmark++);
        }

        boolean contains(int id)
        {
            return set.contains(id);
        }

        void insertNew(int id)
        {
            lastWasMark = false;
            set.insertNew(id);
        }

        int size()
        {
            return set.size();
        }

        int denseAt(int i)
        {
            return set.denseAt(i);
        }
    }

    /// Saves a state so it can be restored after cache reset.
    private static class StateSaver
    {
        private final int[] inst;
        private final int flag;
        private final int sentinelOffset;

        StateSaver(DfaInstance dfa, int stateOffset)
        {
            if (stateOffset == T_DEAD || stateOffset == T_FULL_MATCH) {
                this.inst = null;
                this.flag = 0;
                this.sentinelOffset = stateOffset;
            }
            else {
                // Saved only by an exclusive search, from the current tables.
                StateData sd = dfa.tables.stateData[dfa.stateIndex(stateOffset)];
                this.inst = sd.inst().clone();
                this.flag = sd.flag();
                this.sentinelOffset = 0;
            }
        }

        int restoreLocked(DfaInstance dfa)
        {
            if (inst == null) {
                return sentinelOffset;
            }
            StateData sd = dfa.restoreStateLocked(inst, flag);
            if (sd == null) {
                return -1;
            }
            return sd.offset();
        }
    }

    static final class GroupZeroForwardCursor
    {
        private final Prog prog;
        private final DfaInstance dfa;
        private final Prog.FixedDistanceByteCandidates fixedDistanceByteCandidates;

        private int fixedDistanceByteScanProductivityRejectedUntil;
        private int startByteScanProductivityRejectedUntil;
        private int fixedDistanceByteScanProductivityCheckCount;
        private int startByteScanProductivityCheckCount;

        private GroupZeroForwardCursor(
                Prog prog,
                DfaInstance dfa,
                Prog.FixedDistanceByteCandidates fixedDistanceByteCandidates)
        {
            this.prog = prog;
            this.dfa = dfa;
            this.fixedDistanceByteCandidates = fixedDistanceByteCandidates;
        }

        int byteScanProductivityCheckCount()
        {
            return fixedDistanceByteScanProductivityCheckCount + startByteScanProductivityCheckCount;
        }

        void reset()
        {
            fixedDistanceByteScanProductivityRejectedUntil = 0;
            startByteScanProductivityRejectedUntil = 0;
        }

        private boolean isFixedDistanceByteScanProductive(byte[] candidates, byte[] bytes, int position, int textEnd)
        {
            // Below the sampling minimum, apply the same start-filter rule as
            // Dfa.isFixedDistanceByteScanProductive.
            if (textEnd - position < MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES) {
                fixedDistanceByteScanProductivityRejectedUntil = 0;
                return !dfa.fixedDistanceByteScanDenserThanStartFilter;
            }
            if (position < fixedDistanceByteScanProductivityRejectedUntil) {
                return false;
            }

            fixedDistanceByteScanProductivityCheckCount++;
            boolean productive = isByteCandidateScanProductive(candidates, bytes, position, textEnd);
            fixedDistanceByteScanProductivityRejectedUntil = productive ? 0 : nextProductivityCheckPosition(position, textEnd);
            return productive;
        }

        private boolean isStartByteScanProductive(byte[] bytes, int position, int textEnd)
        {
            if (textEnd - position < MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES) {
                startByteScanProductivityRejectedUntil = 0;
                return true;
            }
            if (position < startByteScanProductivityRejectedUntil) {
                return false;
            }

            startByteScanProductivityCheckCount++;
            boolean productive = dfa.isStartByteScanProductive(bytes, position, textEnd);
            startByteScanProductivityRejectedUntil = productive ? 0 : nextProductivityCheckPosition(position, textEnd);
            return productive;
        }
    }

    // A sample spreads its windows over the whole remaining text, so a sample taken this close to
    // an earlier one reads nearly the same bytes.
    static int nextProductivityCheckPosition(int position, int textEnd)
    {
        return position + Math.min(BYTE_SCAN_PRODUCTIVITY_RECHECK_BYTES, textEnd - position);
    }

    /// Carries the byte-scan sampling outcomes shared by the searches of one count, either across
    /// several [Dfa#countMatches(Prog,Slice,int,Prog.MatchKind,IntPredicate,CountByteScanSampling)]
    /// calls, through the constructor, which holds every outcome, or within one call, through
    /// [#forSingleCount()], which holds fewer. Every sample spreads its windows over the whole
    /// remaining text, so a search that starts close to an earlier sample would read nearly the
    /// same, often cold, bytes again.
    /// Across calls, such as the inner literal count's fallback counts, each starting at or after
    /// where the previous one stopped, a vector start-byte scan selection holds for the rest of
    /// the count, as within one call; a scalar selection and each productivity result hold for
    /// [Dfa#BYTE_SCAN_PRODUCTIVITY_RECHECK_BYTES] past the position sampled. Within one call,
    /// [#forSingleCount()] holds the scan choices, a fixed-distance rejection, and a productive
    /// start-byte result, and samples the others again at each search. The outcomes only choose
    /// how candidates are found, never which candidates the DFA processes. One count owns the
    /// object, so it is never shared between threads. It also carries the count's self-loop
    /// cutoff, which its next call resumes.
    static final class CountByteScanSampling
    {
        /// With fewer bytes remaining, every byte-scan choice is made without sampling, so a
        /// count whose first call has fewer bytes remaining makes the same byte-scan choices when
        /// passed `null`. Its self-loop cutoff differs: without a sampling, each call starts its
        /// own.
        static final int MIN_SAMPLED_BYTES = Math.min(MIN_SMALL_BYTE_SET_PRODUCTIVITY_CHECK_BYTES, MIN_SELECTIVE_SCAN_SEARCH_BYTES);

        private final boolean holdsEveryOutcome;
        private boolean startByteVectorScanSelected;
        private int startByteScalarScanSelectedUntil;
        private boolean startByteScanProductive;
        private int startByteScanProductivityCheckedUntil;
        private boolean fixedDistanceByteScanProductive;
        private int fixedDistanceByteScanProductivityCheckedUntil;
        private int byteScanCheckCount;
        // The count's unproductive self-loop search streak after its last search, which its next
        // call resumes.
        private int selfLoopUnproductiveCount;

        CountByteScanSampling()
        {
            this(true);
        }

        private CountByteScanSampling(boolean holdsEveryOutcome)
        {
            this.holdsEveryOutcome = holdsEveryOutcome;
        }

        /// Sampling for the searches of one count call. Its searches start at match ends, so a
        /// held outcome can outlive the matches around the position sampled. A start-byte
        /// rejection taken among a cluster of matches would leave the search after the cluster
        /// walking the plain DFA to a far match, and a productive fixed-distance sample would
        /// send a search into a long, dense gap on that scan. Both are sampled again at each
        /// search; a fixed-distance rejection, a productive start-byte sample, and the scalar
        /// scan choice are held.
        static CountByteScanSampling forSingleCount()
        {
            return new CountByteScanSampling(false);
        }

        int byteScanCheckCount()
        {
            return byteScanCheckCount;
        }

        private int selectStartByteScanSet(DfaInstance dfa, byte[] bytes, int position, int textEnd)
        {
            if (startByteVectorScanSelected) {
                return dfa.startByteScanSet;
            }
            if (position < startByteScalarScanSelectedUntil) {
                return 0;
            }

            byteScanCheckCount++;
            int selected = selectByteScanSet(dfa.startByteScanSet, dfa.startByteCandidates, bytes, position, textEnd);
            if (selected != 0) {
                startByteVectorScanSelected = true;
            }
            else {
                startByteScalarScanSelectedUntil = nextProductivityCheckPosition(position, textEnd);
            }
            return selected;
        }

        private boolean isStartByteScanProductive(DfaInstance dfa, byte[] bytes, int position, int textEnd)
        {
            // Below the minimum the check reports the span as productive without sampling.
            if (textEnd - position < MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES) {
                return true;
            }
            if (position < startByteScanProductivityCheckedUntil) {
                return startByteScanProductive;
            }

            byteScanCheckCount++;
            startByteScanProductive = dfa.isStartByteScanProductive(bytes, position, textEnd);
            startByteScanProductivityCheckedUntil = startByteScanProductive || holdsEveryOutcome ? nextProductivityCheckPosition(position, textEnd) : 0;
            return startByteScanProductive;
        }

        private boolean isFixedDistanceByteScanProductive(DfaInstance dfa, byte[] candidates, byte[] bytes, int position, int textEnd)
        {
            // Below the sampling minimum, apply the same start-filter rule as
            // Dfa.isFixedDistanceByteScanProductive.
            if (textEnd - position < MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES) {
                return !dfa.fixedDistanceByteScanDenserThanStartFilter;
            }
            if (position < fixedDistanceByteScanProductivityCheckedUntil) {
                return fixedDistanceByteScanProductive;
            }

            byteScanCheckCount++;
            fixedDistanceByteScanProductive = isByteCandidateScanProductive(candidates, bytes, position, textEnd);
            fixedDistanceByteScanProductivityCheckedUntil = !fixedDistanceByteScanProductive || holdsEveryOutcome ? nextProductivityCheckPosition(position, textEnd) : 0;
            return fixedDistanceByteScanProductive;
        }
    }

    @SuppressWarnings("AccessingNonPublicFieldOfAnotherObject")
    static final class CandidateStartCursor
    {
        private final DfaInstance dfa;
        // Set for a cursor whose candidates are starts that hold the program's transparent start
        // literal, found by scanning for the literal or for the folded run behind it; null for one
        // that scans the DFA's start byte set.
        private final byte[] literal;
        private final LiteralSearchKernel literalSearch;
        // Set beside the literal for a cursor that scans for a folded run behind it, whose
        // candidates are the starts each occurrence of the run allows that hold the literal; null
        // otherwise.
        private final Prog.FoldedStartRun foldedStartRun;

        private long remainingWork;
        private boolean disabled;
        private boolean productivityChecked;
        private long routeCount;
        private long fallbackCount;
        private long productivityRejectionCount;
        private long foldedRunSearchCount;

        private CandidateStartCursor(DfaInstance dfa)
        {
            this.dfa = dfa;
            this.literal = null;
            this.literalSearch = null;
            this.foldedStartRun = null;
        }

        private CandidateStartCursor(DfaInstance dfa, byte[] literal, LiteralSearchKernel literalSearch, Prog.FoldedStartRun foldedStartRun)
        {
            this.dfa = requireNonNull(dfa, "dfa is null");
            this.literal = requireNonNull(literal, "literal is null");
            this.literalSearch = requireNonNull(literalSearch, "literalSearch is null");
            this.foldedStartRun = foldedStartRun;
        }

        void reset(int inputLength)
        {
            remainingWork = 3L * inputLength;
            disabled = false;
            productivityChecked = false;
        }

        boolean enabled()
        {
            return !disabled;
        }

        /// Whether the cursor's candidates hold the program's transparent start literal, whether
        /// it scans for the literal or for a folded run behind it.
        boolean searchesLiteral()
        {
            return literal != null;
        }

        boolean searchesFoldedStartRun()
        {
            return foldedStartRun != null;
        }

        /// Whether the cursor's DFA can quit on a byte outside ASCII, so its quits count toward a
        /// matcher's quit streak.
        boolean quitsOnNonAscii()
        {
            return dfa.quitsOnNonAscii;
        }

        long routeCount()
        {
            return routeCount;
        }

        long fallbackCount()
        {
            return fallbackCount;
        }

        long productivityRejectionCount()
        {
            return productivityRejectionCount;
        }

        /// The entries into the folded-run loop, a search's retries included.
        long foldedRunSearchCount()
        {
            return foldedRunSearchCount;
        }

        private boolean isProductive(byte[] bytes, int position, int textEnd)
        {
            if (productivityChecked) {
                return true;
            }
            productivityChecked = true;
            if (dfa.isStartByteScanProductive(bytes, position, textEnd)) {
                return true;
            }
            productivityRejectionCount++;
            return false;
        }

        int workBoundedScanLength(int requestedLength)
        {
            return (int) Math.min(remainingWork, requestedLength);
        }

        private boolean consumeWork()
        {
            return consumeWork(1);
        }

        private boolean consumeWork(long work)
        {
            if (work > remainingWork) {
                remainingWork = 0;
                return false;
            }
            remainingWork -= work;
            return true;
        }
    }

    @SuppressFBWarnings(
            value = {
                    "AT_NONATOMIC_64BIT_PRIMITIVE",
                    "AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE",
                    "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
                    "VO_VOLATILE_INCREMENT",
            },
            justification = "DFA cache mutation is serialized by the cache mutation protocol")
    public static final class DfaInstance
    {
        // Like upstream, a DFA initializes only when its state budget holds this many of its
        // largest possible states.
        private static final int MINIMUM_INITIAL_STATES = 20;
        // The most states the tables of a new or reset DFA hold before they grow. A DFA starts
        // with room for one state per instruction, at least 16 and at most what its budget holds,
        // but never more than this; one that starts here doubles its tables as states arrive,
        // each growth charged to the state budget, so a large program's DFA does not prepay
        // tables it may never fill.
        static final int MAXIMUM_INITIAL_STATE_CAPACITY = 256;
        // The most a forward DFA charges for its start-byte tables: the candidates, one entry per
        // byte value, and the nibble tables, the literal filter, or the class filter, which
        // exclude each other.
        static final long MAXIMUM_START_BYTE_TABLES_MEMORY = 16L + 256 + Math.max(
                Math.max(16L + VectorByteSetScanner.NIBBLE_TABLES_LENGTH, StartLiteralFilter.MAXIMUM_RETAINED_SIZE),
                StartClassFilter.INSTANCE_SIZE);
        // A streak of this many bailing DFA counts opens a window in which the next
        // COUNT_BAIL_SKIPPED_COUNTS DFA counts skip the DFA.
        private static final int COUNT_BAIL_STREAK_LIMIT = 2;
        private static final int COUNT_BAIL_SKIPPED_COUNTS = 16;
        // The slow resets in a row, judged across boolean searches by isSlowReset, after which
        // boolean searches skip the DFA.
        private static final int SLOW_RESET_STREAK_LIMIT = 2;
        // The boolean searches that skip the DFA after a streak of slow resets they judged. On
        // the motivating Leipzig grep workload, one boolean search read one line, and a DFA that
        // thrashed reset about every 7,000 lines, so the window holds about ten times that many.
        private static final int BOOLEAN_SEARCH_SKIPPED_SEARCHES = 1 << 16;

        private static final VarHandle TABLES;
        private static final VarHandle COUNT_BAIL_SKIPS_REMAINING;
        private static final VarHandle BOOLEAN_SEARCH_SKIPS_REMAINING;

        static {
            try {
                TABLES = MethodHandles.lookup().findVarHandle(DfaInstance.class, "tables", CacheTables.class);
                COUNT_BAIL_SKIPS_REMAINING = MethodHandles.lookup().findVarHandle(DfaInstance.class, "countBailSkipsRemaining", int.class);
                BOOLEAN_SEARCH_SKIPS_REMAINING = MethodHandles.lookup().findVarHandle(DfaInstance.class, "booleanSearchSkipsRemaining", int.class);
            }
            catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        enum TestingFailurePoint
        {
            EXCLUSIVE_ADMISSION,
            EXCLUSIVE_TEARDOWN,
            POINTER_TABLE_GROWTH,
            // Fails a growth's pointer table allocation as a memory shortage does, without throwing.
            POINTER_TABLE_GROWTH_ALLOCATION,
            // Fails the reset's pointer table allocation as a memory shortage does, without throwing.
            POINTER_TABLE_RESET,
            TRANSITION_ARRAY_GROWTH,
            STATE_DATA_ARRAY_GROWTH,
            STATE_REFERENCE_ARRAY_GROWTH,
            SELF_LOOP_ARRAY_GROWTH,
            STATE_REFERENCE_ROW_ALLOCATION,
            STATE_CACHE_INSERTION,
        }

        private final Prog prog;
        final byte[] bytemap;
        private final byte[] startByteCandidates;
        private final int startByteScanSet;
        private final byte[] startByteNibbleTables;
        private final StartLiteralFilter startLiteralFilter;
        private final boolean startLiteralFilterVectorOnly;
        private final StartClassFilter startClassFilter;
        private final boolean nullableStartByteCandidates;
        private final int trinoLineFlag;
        // Rust CRLF anchors read a CR LF pair in the direction of the search: its first byte is CR
        // forward and LF in reverse, and its second byte is the other.
        private final boolean hasCrlfLineAssertions;
        private final int crlfFirstByte;
        private final int crlfSecondByte;
        private final boolean hasRustWordAssertions;
        private final boolean quitsOnNonAscii;
        private volatile Prog.FixedDistanceByteCandidates fixedDistanceByteCandidates;
        private volatile int fixedDistanceSmallByteSet;
        // Set with the fixed-distance candidates, before they are published: whether a search too
        // short for the productivity sample takes the start literal or class filter in place of
        // the fixed-distance scan.
        private volatile boolean fixedDistanceByteScanDenserThanStartFilter;
        private long fixedDistanceByteCandidateMemory;
        final int bytemapRange;
        final int nextSize;
        private final int stateIndexShift;
        private final int stateIndexMultiplier;
        private final Kind kind;

        public enum Kind
        {
            FIRST_MATCH,
            LONGEST_MATCH,
            MANY_MATCH,
        }

        private final long stateBudget;
        private long availableStateMemory;
        private long retainedStateMemory;
        private final boolean initFailed;
        private final int maxTransitionsLength;

        // Only the holder of this lock writes the cache or publishes its tables.
        private final ReentrantLock cacheMutationLock = new ReentrantLock();
        private volatile long cacheVersion;

        private WorkQueue q0;
        private WorkQueue q1;
        private final int[] stack;

        // The current cache generation. A reader search reads it once and reads only those tables;
        // an exclusive search rereads it after each write that can publish (see `tablesAfterWrite`).
        private volatile CacheTables tables;
        // The state capacity of the tables a reset publishes.
        private final int initialStateCapacity;
        // One state per instruction, at least 16, at most what the budget's transitions hold. Growth
        // from a smaller initial capacity stops here once before it doubles on, so the tables never
        // exceed the size they would have had starting here.
        private final int programStateCapacity;
        int nextFreeOffset;
        private long pairedTransitionMemory;
        private volatile boolean pairedTransitionsRequested;
        private boolean pairedTransitionsRejected;
        private boolean pairedTransitionsDisabled;
        // Paired rows snapshot computed object transitions. Rebuild them only after successful
        // state or transition publication, not while unwinding a failed exclusive mutation.
        private long cacheContentVersion;
        private long pairedContentVersion = -1;
        private int shortPairedSearchCount;
        private boolean absolutePointerTransitionsRequested;
        // A pointer table has been published to the tables. It stays set through resets and the
        // release. A request whose first allocation failed leaves it clear, since no reset of that
        // DFA was the table's.
        private boolean absolutePointerTransitionsPublished;
        private boolean absolutePointerTransitionsUnavailable;
        // The requested pointer table is absent from the current tables: superseded native segments
        // still await collection, or its memory could not be allocated. It stays requested, and
        // paired rows stay disabled, until an exclusive search allocates it again or a search
        // about to give up gives it back, so a warm cache may run on object rows until its next
        // exclusive search or reset.
        private boolean absolutePointerTransitionsWithheld;
        // A pointer table allocation has failed since the last reset; none is attempted again until
        // the next one.
        private boolean absolutePointerTransitionAllocationFailed;
        // A search or count about to give up for resetting too often gave the pointer table back.
        // The table may still grow until the next reset drops it; nothing allocates one after that,
        // and paired transitions stay disabled.
        private boolean absolutePointerTransitionsReleased;
        // The reset count once the first reset after that release has run; from there the tables
        // hold no pointer table.
        private int releasedAbsolutePointerResetCount;
        // The first pointer table allocation's one wait, for a search that computed no transition,
        // is spent; later allocations, including after a reset, do not wait.
        private boolean absolutePointerTransitionDeferralUsed;
        // References to native pointer tables a reset or growth superseded, linked until the
        // collector enqueues them, and their native bytes not yet released from the count.
        // The list and count are guarded by the cache mutation lock.
        private final ReferenceQueue<AbsolutePointerTransitions> unreachableAbsolutePointerTables = new ReferenceQueue<>();
        private SupersededAbsolutePointerReference supersededAbsolutePointerReferences;
        private long supersededAbsolutePointerMemory;
        private int shortAbsolutePointerSearchCount;
        private volatile int absolutePointerTransitionCount;
        private int absolutePointerBackwardSearchCount;
        volatile int stateCount;

        private TestingFailurePoint testingFailurePoint;
        private FailureAtomicitySnapshot testingSnapshotBeforeFailure;
        private int testingStateAllocationFailures;

        // Cache: StateKey -> StateData
        private final StateCache cache = new StateCache();

        // The consecutive DFA counts that bailed, saturating at COUNT_BAIL_STREAK_LIMIT, and the
        // DFA counts left in the skip window. Every matcher and thread of the pattern shares them;
        // "Count Fallback After Repeated Bails" in REGULATOR_DECISIONS.md gives the rule and why
        // their races keep the window bounded.
        private int countBailStreak;
        // Accessed only through COUNT_BAIL_SKIPS_REMAINING, with opaque access or a compare-and-set.
        @SuppressWarnings("UnusedVariable")
        private int countBailSkipsRemaining;
        // The consecutive slow resets boolean searches judged, saturating at
        // SLOW_RESET_STREAK_LIMIT, and the boolean searches left in their skip window. They are
        // apart from the counts', so neither path's judgements move the other's window, and they
        // race as the counts' do.
        private int booleanSearchSlowResetStreak;
        // Accessed only through BOOLEAN_SEARCH_SKIPS_REMAINING, with opaque access or a
        // compare-and-set.
        @SuppressWarnings("UnusedVariable")
        private int booleanSearchSkipsRemaining;
        // For boolean searches, the last reset count they saw, and the bytes their DFA work must
        // still read before the next reset for it to be judged not slow. Plain fields that
        // boolean searches race on, as on their streak.
        private int booleanSearchResetCount;
        private int booleanSearchBytesUntilResetNotSlow;

        // The states the cache held when it last reset, written by the reset under the cache
        // mutation lock and read without it by the boolean searches that judge slow resets
        // across calls; see slowResetAllowance.
        private int statesAtLastReset;

        // Observability
        private volatile int resetCount;
        private int pairedRowSelectionCount;
        // Paired tables released to make room for a state, excluding clears at search end, rejection and reset.
        private int pairedRowReleaseCount;
        private int byteScanFallbackCount;
        private int smallByteSetScanCount;
        private int contiguousRangeScanCount;
        private int mixedByteSetScanCount;
        private int startLiteralFilterScanCount;
        private int startClassFilterScanCount;
        private int selfLoopExitByteScanCount;
        private int countSelfLoopScanCount;
        private int countSelfLoopMatchHandOffCount;
        private int countSelfLoopNoMatchPointerHandOffCount;
        private int countSelfLoopRearmCount;
        // The running count's unproductive self-loop search streak, the self-loop cutoff: a search
        // advances it when it reaches no eligible self-loop or scans only stay-form runs shorter
        // than VectorByteSetScanner.SHORT_SCAN_BYTES; any exit-form scan or a stay run of at least
        // that length clears it. Each count call loads it from its sampling, or zero, and stores it
        // back after each search.
        private int countSelfLoopUnproductiveCount;
        private int selfLoopStayRangeScanCount;
        private int matchingSelfLoopScanCount;
        private int matchingSelfLoopExitByteScanCount;
        private int reverseSelfLoopExitByteScanCount;
        private int fixedDistanceShortSkipFallbackCount;
        private int prefixRunScanCount;

        public DfaInstance(Prog prog, Kind kind, long maxMemory)
        {
            this.prog = requireNonNull(prog, "prog is null");
            this.bytemap = prog.bytemapArray();
            this.bytemapRange = prog.bytemapRange();
            this.kind = requireNonNull(kind, "kind is null");
            this.nextSize = prog.bytemapRange() + 1;
            this.stateIndexShift = Integer.numberOfTrailingZeros(nextSize);
            this.stateIndexMultiplier = stateIndexMultiplier(nextSize);
            int nmark = markSlots(prog, kind);
            this.trinoLineFlag = prog.reversed()
                    ? prog.textDependentAssertions() & EmptyOp.EMPTY_TRINO_END_LINE
                    : prog.textDependentAssertions() & EmptyOp.EMPTY_TRINO_BEGIN_LINE;
            int textDependentAssertions = prog.textDependentAssertions();
            this.hasCrlfLineAssertions = (textDependentAssertions & EmptyOp.RUST_CRLF_LINE) != 0;
            this.crlfFirstByte = prog.reversed() ? '\n' : '\r';
            this.crlfSecondByte = prog.reversed() ? '\r' : '\n';
            this.hasRustWordAssertions = (textDependentAssertions & EmptyOp.RUST_WORD_ASSERTIONS) != 0;
            this.quitsOnNonAscii = prog.dfaQuitsOnNonAscii();

            this.q0 = new WorkQueue(prog.size(), nmark);
            this.q1 = new WorkQueue(prog.size(), nmark);
            this.stack = new int[stackLength(prog, nmark)];
            StartByteTables startByteTables = StartByteTables.build(prog, kind, stack);
            this.startByteCandidates = startByteTables.candidates();
            this.nullableStartByteCandidates = startByteTables.nullableCandidates();
            this.startLiteralFilter = startByteTables.literalFilter();
            this.startLiteralFilterVectorOnly = startByteTables.literalFilterVectorOnly();
            this.startClassFilter = startByteTables.classFilter();
            this.startByteNibbleTables = startByteTables.nibbleTables();
            this.startByteScanSet = startByteTables.scanSet();

            if (maxMemory == 0) {
                this.stateBudget = Long.MAX_VALUE;
                this.availableStateMemory = Long.MAX_VALUE;
                this.initFailed = false;
                this.maxTransitionsLength = MAX_TRANSITIONS_LENGTH;
                this.programStateCapacity = programStateCapacity(prog, Integer.MAX_VALUE);
                this.initialStateCapacity = Math.min(programStateCapacity, MAXIMUM_INITIAL_STATE_CAPACITY);
                this.tables = newCacheTables(initialStateCapacity);
                this.retainedStateMemory = stateBackingMemory();
                this.nextFreeOffset = nextSize; // skip offset 0 (reserved)
                this.stateCount = 0;
                return;
            }

            long overhead = fixedOverhead(prog, nmark) + startByteTables.estimatedRetainedSize();

            long memoryBudget = maxMemory - overhead;
            if (memoryBudget < 0) {
                this.initFailed = true;
                this.stateBudget = 0;
                this.availableStateMemory = 0;
                this.maxTransitionsLength = 0;
                this.programStateCapacity = 0;
                this.initialStateCapacity = 0;
                this.tables = newCacheTables(0);
                return;
            }

            this.stateBudget = memoryBudget;

            // Upstream uses the largest possible state only for this initialization gate.
            // Actual cache growth, including backing-table growth, is charged separately.
            // Reset recovery needs at most three states; upstream reserves twenty for speed.
            long largestStateSize = largestStateSize(prog, kind, nmark);
            if (stateBudget / largestStateSize < MINIMUM_INITIAL_STATES) {
                this.initFailed = true;
                this.maxTransitionsLength = 0;
                this.programStateCapacity = 0;
                this.initialStateCapacity = 0;
                this.tables = newCacheTables(0);
                return;
            }

            this.initFailed = false;
            long maxStateCount = stateBudget / estimateStateSize(0);
            long maxTransitionStateCount = MAX_TRANSITIONS_LENGTH / nextSize;
            this.maxTransitionsLength = (int) (Math.min(maxStateCount + 1, maxTransitionStateCount) * nextSize);
            this.programStateCapacity = programStateCapacity(prog, maxTransitionsLength / nextSize);
            this.initialStateCapacity = Math.min(programStateCapacity, MAXIMUM_INITIAL_STATE_CAPACITY);
            this.tables = newCacheTables(initialStateCapacity);
            this.retainedStateMemory = stateBackingMemory();
            this.availableStateMemory = memoryBudget - retainedStateMemory;
            this.nextFreeOffset = nextSize;
            this.stateCount = 0;
        }

        private static int programStateCapacity(Prog prog, int stateLimit)
        {
            return Math.max(Math.min(prog.size(), stateLimit), 16);
        }

        // The table growth, not yet charged, from state tables of `stateCapacity` rows to
        // `programStateCapacity` rows. Tables that start there prepay it, so pointer-table sizing
        // reserves it: a pointer table is then no larger than such tables would leave room for.
        private long unpaidStateTableMemory(int stateCapacity)
        {
            if (stateCapacity >= programStateCapacity) {
                return 0;
            }
            return stateTableMemory(programStateCapacity) - stateTableMemory(stateCapacity);
        }

        // The most pointer rows, at most `rowLimit`, that fit in `memory` with the minimum Java
        // state behind every row past `paidRowCount`.
        private long compatiblePointerRowCount(long memory, long paidRowCount, long rowLimit)
        {
            long rowBytes = Math.multiplyExact((long) nextSize, Long.BYTES);
            long minimumStateMemory = stateObjectMemory(0);
            return Math.min(rowLimit, Math.addExact(memory, Math.multiplyExact(paidRowCount, minimumStateMemory)) / Math.addExact(rowBytes, minimumStateMemory));
        }

        // The charge for state tables of `stateCapacity` rows: transitions, state data, state
        // references, and self-loop exit byte sets.
        private long stateTableMemory(int stateCapacity)
        {
            return sizeOfIntArray(stateCapacity * nextSize) + 2 * sizeOfObjectArray(stateCapacity) + sizeOfLongArray(stateCapacity);
        }

        private CacheTables newCacheTables(int stateCapacity)
        {
            return CacheTables.create(
                    stateCapacity,
                    nextSize,
                    trinoLineFlag != 0 && !prog.reversed(),
                    hasCrlfLineAssertions,
                    quitsOnNonAscii);
        }

        CacheTables cacheTablesForTesting()
        {
            return tables;
        }

        // The tables an exclusive search reads after a call that may have published new ones.
        // Readers never publish, so they keep the tables they began with and skip the lock check
        // unless a writer published meanwhile. The plain read costs a reader no fence.
        CacheTables tablesAfterWrite(CacheTables searchTables)
        {
            CacheTables published = (CacheTables) TABLES.get(this);
            if (published == searchTables || !cacheMutationLock.isHeldByCurrentThread()) {
                return searchTables;
            }
            return published;
        }

        /// Returns a lower bound on the `maxMemory` with which the constructor initializes a DFA of
        /// `kind` over `prog`. [Prog#getCachedDfa] gives a reversed program's longest-match DFA the
        /// whole program DFA memory, so compare that against this value. It leaves out the
        /// start-byte tables, which this does not build, so for a forward program it can fall
        /// short of what the constructor charges; [#initializationMemory(Prog,Kind)] builds them
        /// and is exact. A reversed program builds none, so for it this is exact too.
        static long minimumMemory(Prog prog, Kind kind)
        {
            int nmark = markSlots(prog, kind);
            return fixedOverhead(prog, nmark) + MINIMUM_INITIAL_STATES * largestStateSize(prog, kind, nmark);
        }

        /// Returns the least `maxMemory` with which the constructor initializes a DFA of `kind` over
        /// `prog`: [#minimumMemory(Prog,Kind)] and the start-byte tables the constructor would
        /// charge, which this builds to measure. Unlike that lower bound, it is exact for a forward
        /// program too.
        static long initializationMemory(Prog prog, Kind kind)
        {
            int nmark = markSlots(prog, kind);
            return minimumMemory(prog, kind) + StartByteTables.build(prog, kind, new int[stackLength(prog, nmark)]).estimatedRetainedSize();
        }

        /// Returns whether a DFA of `kind` over `prog` searches for its start literal filter in
        /// place of its start bytes, which this selects as the start-byte tables do, building none
        /// of the tables that follow the selection except to try the start class filter when groups
        /// wider than one bit give a literal filter. A program whose folded run starts every match
        /// keeps the tables only in its first-match DFA, so for such a program this answers for
        /// that kind alone: a DFA of another kind keeps no filter.
        static boolean searchesStartLiteralFilter(Prog prog, Kind kind)
        {
            return selectStartLiteralFilter(prog, kind, StartLiteralFilter.GROUP_BITS) != null;
        }

        /// Returns whether groups of one bit, the two cases of a letter, give a DFA of `kind` over
        /// `prog` a start literal filter. Where they do, the DFA searches one, since wider groups
        /// only add positions. The routes measured against filters of one-bit groups, the
        /// fixed-width matcher's yield and the long-window required-literal check, ask this
        /// rather than [#searchesStartLiteralFilter], so a filter that only wider groups give,
        /// such as that of `[éê]x`, leaves them as they were.
        static boolean givesOneBitStartLiteralFilter(Prog prog, Kind kind)
        {
            return selectStartLiteralFilter(prog, kind, 1) != null;
        }

        /// Returns the start literal filter a DFA of `kind` over `prog` would search for if each
        /// group's positions could differ in at most `groupBits` bits, or null.
        static StartLiteralFilter selectStartLiteralFilter(Prog prog, Kind kind, int groupBits)
        {
            int nmark = markSlots(prog, kind);
            StartLiteralFilterChoice choice = StartLiteralFilterSelection.select(prog, kind, new int[stackLength(prog, nmark)], groupBits).literalFilterChoice();
            return choice == null ? null : choice.filter();
        }

        // The work queues, the stack, and fixed fields, without the start-byte tables.
        private static long fixedOverhead(Prog prog, int nmark)
        {
            long overhead = 64;
            overhead += CONSERVATIVE_REFERENCE_BYTES + Long.BYTES;
            overhead += CONSERVATIVE_REFERENCE_BYTES + Integer.BYTES;
            overhead += (prog.size() + (long) nmark) * 8L * 2;
            overhead += (long) stackLength(prog, nmark) * Integer.BYTES;
            // The current cache root; the arrays it holds are charged as state memory.
            overhead += CACHE_TABLES_BYTES;
            return overhead;
        }

        // The mark slots of each work queue. A longest-match queue marks only between priority
        // groups, and every group but the unanchored start's split begins at its own list head,
        // so a queue holds at most one mark per list and one more for that split: listCount + 1,
        // never more than upstream's bound, the program size. The bound counts the lists of a
        // flattened program. Other kinds use no marks.
        private static int markSlots(Prog prog, Kind kind)
        {
            if (kind != Kind.LONGEST_MATCH) {
                return 0;
            }
            if (!prog.didFlatten()) {
                throw new IllegalStateException("expected flattened program");
            }
            return Math.min(prog.size(), prog.listCount() + 1);
        }

        // A forward program's stack also serves the start-byte walks, which need three entries per
        // instruction. A reversed program builds no start-byte tables, so its stack serves only
        // addToQueue, which visits each instruction at most once per call: upstream's bound
        // (dfa.cc), one entry for each instruction that pushes a continuation, the start, and the
        // marks. Regulator's addToQueue also pushes for a FAIL that is not last in its list, and
        // twice for an ALT; flattened programs hold no ALT, but counting both keeps the bound
        // independent of the program's form.
        private static int stackLength(Prog prog, int nmark)
        {
            if (prog.reversed()) {
                return prog.getInstCount(InstOp.CAPTURE) +
                        prog.getInstCount(InstOp.EMPTY_WIDTH) +
                        prog.getInstCount(InstOp.NOP) +
                        prog.getInstCount(InstOp.FAIL) +
                        2 * prog.getInstCount(InstOp.ALT) +
                        nmark + 1;
            }
            return Math.max(16, prog.size() * 3 + 32 + nmark + 1);
        }

        // The start-byte tables a forward program's DFA builds and charges to its budget: the
        // bytes that can begin a match and, with the Vector API, the start literal filter, the
        // start class filter, or the nibble tables a search scans for them with. The scan set
        // selects that search, and a filter taken for rare start bytes serves only vector
        // searches. A literal filter of one-bit groups comes first; one that only wider groups give
        // yields to an admitted class filter. The class filter replaces only a start set's own
        // vector scan: a contiguous range, two or three bytes, or the nibble tables of a mixed set.
        //
        // A folded run that every match starts with takes the place of the start bytes in every
        // search but the candidate cursor's, which reads only a first-match DFA whose start bytes
        // carry a start literal filter; see CompiledPattern.createCandidateStartCursor. Every
        // other DFA of such a program keeps and charges no start-byte tables.
        private record StartByteTables(
                byte[] candidates,
                boolean nullableCandidates,
                int scanSet,
                StartLiteralFilter literalFilter,
                boolean literalFilterVectorOnly,
                StartClassFilter classFilter,
                byte[] nibbleTables)
        {
            private static final StartByteTables NONE = new StartByteTables(null, false, 0, null, false, null, null);

            static StartByteTables build(Prog prog, Kind kind, int[] stack)
            {
                boolean prefixRunStartsEveryMatch = prog.prefixRunStartsEveryMatch();
                if (prefixRunStartsEveryMatch && kind != Kind.FIRST_MATCH) {
                    return NONE;
                }
                StartLiteralFilterSelection selection = StartLiteralFilterSelection.select(prog, kind, stack, StartLiteralFilter.GROUP_BITS);
                StartLiteralFilterChoice literalFilterChoice = selection.literalFilterChoice();
                if (prefixRunStartsEveryMatch && literalFilterChoice == null) {
                    return NONE;
                }
                byte[] candidates = selection.candidates();
                int byteScanSet = selection.byteScanSet();
                boolean vectorScans = selection.vectorScans();
                StartLiteralFilter literalFilter = literalFilterChoice == null ? null : literalFilterChoice.filter();
                boolean literalFilterVectorOnly = literalFilterChoice != null && literalFilterChoice.vectorOnly();
                byte[] nibbleTables = literalFilter == null && byteScanSet == 0 && vectorScans ? VectorByteSetScanner.buildNibbleTables(candidates) : null;
                StartClassFilter classFilter = literalFilter == null && vectorScans
                        ? buildStartClassFilter(prog, stack, byteScanSet, nibbleTables, selection.startByteProbability())
                        : null;
                int scanSet;
                if (literalFilter != null) {
                    scanSet = START_LITERAL_FILTER_MARKER;
                }
                else if (classFilter != null) {
                    scanSet = START_CLASS_FILTER_MARKER;
                    nibbleTables = null;
                }
                else {
                    scanSet = nibbleTables == null ? byteScanSet : MIXED_BYTE_SET_MARKER;
                }
                return new StartByteTables(candidates, selection.nullableCandidates(), scanSet, literalFilter, literalFilterVectorOnly, classFilter, nibbleTables);
            }

            long estimatedRetainedSize()
            {
                long size = 0;
                if (candidates != null) {
                    size += 16L + candidates.length;
                }
                if (nibbleTables != null) {
                    size += 16L + nibbleTables.length;
                }
                if (literalFilter != null) {
                    size += literalFilter.estimatedRetainedSize();
                }
                if (classFilter != null) {
                    size += StartClassFilter.INSTANCE_SIZE;
                }
                return size;
            }
        }

        // The first step of the start-byte tables, which searchesStartLiteralFilter stops after:
        // the bytes that can begin a match, their scan set and English probability, whether a
        // search may take a vector scan or a filter for them, and the start literal filter.
        //
        // When groups wider than one bit give the literal filter, the selection also tries the
        // start class filter. Where one is admitted, the literal filter takes groups of one bit,
        // and leaves the start bytes to the class filter when they give none. For small classes
        // such as the a and b of [ab]*a[ab]{12}c, the wider groups give a filter that stops about
        // as often as the class filter but costs more per position: that pattern took 1.20-1.40x
        // the class filter's time on the literal filter. A filter of one-bit groups keeps its
        // precedence over the class filter, and wider groups, not measured against it, do not
        // replace that filter. Where no class filter is admitted, as for [a-g]{3}[0-9]+, whose
        // three ranges together are too common, the wider groups' filter replaces the start bytes.
        private record StartLiteralFilterSelection(
                byte[] candidates,
                boolean nullableCandidates,
                int byteScanSet,
                double startByteProbability,
                boolean vectorScans,
                StartLiteralFilterChoice literalFilterChoice)
        {
            static StartLiteralFilterSelection select(Prog prog, Kind kind, int[] stack, int groupBits)
            {
                byte[] candidates = prog.buildStartByteCandidates(stack, kind == Kind.FIRST_MATCH);
                boolean nullableCandidates = candidates != null && prog.canMatchEmpty();
                int byteScanSet = buildStartByteScanSet(candidates);
                double startByteProbability = englishProbability(candidates);
                boolean vectorScans = !nullableCandidates && VectorSupport.isAvailable();
                StartLiteralFilterChoice literalFilterChoice = vectorScans ? buildStartLiteralFilter(prog, stack, candidates, byteScanSet, startByteProbability, groupBits) : null;
                if (literalFilterChoice != null && groupBits > 1) {
                    byte[] nibbleTables = byteScanSet == 0 ? VectorByteSetScanner.buildNibbleTables(candidates) : null;
                    if (buildStartClassFilter(prog, stack, byteScanSet, nibbleTables, startByteProbability) != null) {
                        literalFilterChoice = buildStartLiteralFilter(prog, stack, candidates, byteScanSet, startByteProbability, 1);
                    }
                }
                return new StartLiteralFilterSelection(candidates, nullableCandidates, byteScanSet, startByteProbability, vectorScans, literalFilterChoice);
            }
        }

        // The state size the initialization gate counts. A state holds list heads and the marks
        // between them. A many-match state that matched also holds a separator and the match ID
        // of each MATCH instruction in the queue it left, which are not list heads of its own
        // queue, so its true bound is listCount + 1 + the MATCH count: patterns that match
        // together and go on together fill both, and a set of one-byte patterns reaches about
        // twice listCount. The gate counts the separator but not the IDs: each state is charged at
        // its real size when it is allocated, and the twenty states the gate reserves are a floor
        // for speed, not for correctness, so counting the IDs would only turn away sets that run.
        private static long largestStateSize(Prog prog, Kind kind, int nmark)
        {
            int manyMatchSeparator = kind == Kind.MANY_MATCH ? 1 : 0;
            return estimateStateSize(prog.bytemapRange() + 1, prog.listCount() + nmark + manyMatchSeparator);
        }

        /// Whether the constructor initializes a DFA of `kind` over `prog` with `budget`. The
        /// minimum leaves out the start-byte tables, so the exact memory lies between the minimum
        /// and the minimum plus the most those tables can charge. Only between those bounds does
        /// [#initializationMemory(Prog,Kind)], which builds the tables, decide.
        static boolean initializes(Prog prog, Kind kind, long budget)
        {
            long minimumMemory = minimumMemory(prog, kind);
            if (budget < minimumMemory) {
                return false;
            }
            if (budget >= minimumMemory + MAXIMUM_START_BYTE_TABLES_MEMORY) {
                return true;
            }
            return budget >= initializationMemory(prog, kind);
        }

        boolean canStartByteAcceleration()
        {
            return startByteCandidates != null && !nullableStartByteCandidates;
        }

        private boolean isStartByteScanProductive(byte[] bytes, int position, int textEnd)
        {
            return isByteCandidateScanProductive(
                    startByteCandidates,
                    startLiteralFilter,
                    startClassFilter,
                    bytes,
                    position,
                    textEnd,
                    MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES,
                    BYTE_SCAN_SAMPLE_CANDIDATE_LIMIT);
        }

        boolean canUseNullableStartByteCheck()
        {
            return nullableStartByteCandidates;
        }

        boolean canFixedDistanceByteAcceleration()
        {
            return fixedDistanceByteCandidates() != null;
        }

        boolean fixedDistanceByteCandidatesInitialized()
        {
            return fixedDistanceByteCandidates != null;
        }

        private Prog.FixedDistanceByteCandidates fixedDistanceByteCandidates()
        {
            Prog.FixedDistanceByteCandidates candidates = fixedDistanceByteCandidates;
            if (candidates == null) {
                cacheMutationLock.lock();
                try {
                    candidates = fixedDistanceByteCandidates;
                    if (candidates == null) {
                        candidates = prog.buildFixedDistanceByteCandidates(stack, startByteCandidates);
                        if (candidates != null && availableStateMemory >= FIXED_DISTANCE_BYTE_CANDIDATE_MEMORY) {
                            availableStateMemory -= FIXED_DISTANCE_BYTE_CANDIDATE_MEMORY;
                            fixedDistanceByteCandidateMemory = FIXED_DISTANCE_BYTE_CANDIDATE_MEMORY;
                        }
                        else if (candidates != null && stateBudget >= FIXED_DISTANCE_BYTE_CANDIDATE_MEMORY) {
                            candidates = RETRY_FIXED_DISTANCE_BYTE_CANDIDATES_AFTER_RESET;
                        }
                        else {
                            candidates = NO_FIXED_DISTANCE_BYTE_CANDIDATES;
                        }
                        boolean usable = candidates != NO_FIXED_DISTANCE_BYTE_CANDIDATES && candidates != RETRY_FIXED_DISTANCE_BYTE_CANDIDATES_AFTER_RESET;
                        fixedDistanceSmallByteSet = usable ? buildSmallByteSet(candidates.candidates()) : 0;
                        fixedDistanceByteScanDenserThanStartFilter = usable && isDenserThanStartFilter(candidates.candidates());
                        fixedDistanceByteCandidates = candidates;
                    }
                }
                finally {
                    cacheMutationLock.unlock();
                }
            }
            return candidates == NO_FIXED_DISTANCE_BYTE_CANDIDATES ||
                    candidates == RETRY_FIXED_DISTANCE_BYTE_CANDIDATES_AFTER_RESET ? null : candidates;
        }

        // Whether English text holds the fixed-distance bytes more often than the start literal or
        // class filter accepts a position, by the estimates the filters were admitted with.
        private boolean isDenserThanStartFilter(byte[] fixedDistanceCandidates)
        {
            double filterDensity;
            if (startLiteralFilter != null) {
                filterDensity = startLiteralFilter.density();
            }
            else if (startClassFilter != null) {
                filterDensity = startClassFilter.density(ByteFrequencies.englishText());
            }
            else {
                return false;
            }
            return englishProbability(fixedDistanceCandidates) > filterDensity;
        }

        private int fixedDistanceSmallByteSet()
        {
            return fixedDistanceByteCandidates() == null ? 0 : fixedDistanceSmallByteSet;
        }

        int fixedDistanceByteOffset()
        {
            Prog.FixedDistanceByteCandidates candidates = fixedDistanceByteCandidates();
            return candidates == null ? -1 : candidates.offset();
        }

        boolean isStartByteCandidate(int value)
        {
            return startByteCandidates[value] != 0;
        }

        int findStartByteCandidate(byte[] data, int offset, int length)
        {
            if (startByteScanSet == 0) {
                return findByteCandidate(startByteCandidates, data, offset, length);
            }
            return findByteSetCandidate(this, startByteCandidates, startByteScanSet, data, offset, length);
        }

        int findFixedDistanceByteCandidate(byte[] data, int offset, int length)
        {
            Prog.FixedDistanceByteCandidates candidates = fixedDistanceByteCandidates();
            if (candidates == null || candidates.offset() >= length) {
                return -1;
            }
            int candidateBytePosition;
            if (fixedDistanceSmallByteSet == 0) {
                candidateBytePosition = findByteCandidate(
                        candidates.candidates(),
                        data,
                        offset + candidates.offset(),
                        length - candidates.offset());
            }
            else {
                candidateBytePosition = findByteSetCandidate(
                        this,
                        candidates.candidates(),
                        fixedDistanceSmallByteSet,
                        data,
                        offset + candidates.offset(),
                        length - candidates.offset());
            }
            return candidateBytePosition < 0 ? -1 : candidateBytePosition - candidates.offset();
        }

        public boolean ok()
        {
            return !initFailed;
        }

        public int resetCount()
        {
            return resetCount;
        }

        // The bytes the work after the last reset must read before the next reset for that reset
        // to be judged not slow: ten for each state the cache held at the last reset, as upstream
        // RE2's search gives up on its DFA at a reset after fewer than ten bytes a state. Upstream
        // counts the states at the reset it judges, which a boolean search judging across calls
        // cannot read before it, so the allowance is the previous reset's: a cache that held far
        // fewer or far more states at the judged reset is judged against the wrong size. "Count
        // Fallback After Repeated Bails" in REGULATOR_DECISIONS.md describes their judgement.
        int slowResetAllowance()
        {
            return (int) Math.min(10L * statesAtLastReset, Integer.MAX_VALUE);
        }

        // Whether a reset whose interval had `allowance` bytes left of its slow-reset allowance
        // before the judging boolean search's `bytesRead` is slow. A test that turns off upstream's
        // slow-reset rule turns off this one too.
        private static boolean isSlowReset(int allowance, int bytesRead)
        {
            return dfaShouldBailWhenSlow && allowance > bytesRead;
        }

        // Takes one DFA count of the skip window, if one is left, and returns whether it did. A
        // DFA count that loses the compare-and-set to another runs the DFA instead.
        private boolean takeCountSkip()
        {
            int skips = (int) COUNT_BAIL_SKIPS_REMAINING.getOpaque(this);
            return skips > 0 && COUNT_BAIL_SKIPS_REMAINING.compareAndSet(this, skips, skips - 1);
        }

        // The streak stays at the limit, so a DFA count that bails after the skipped counts opens
        // the next window at once.
        private void recordCountBail()
        {
            int streak = countBailStreak;
            if (streak < COUNT_BAIL_STREAK_LIMIT) {
                streak++;
                countBailStreak = streak;
            }
            if (streak >= COUNT_BAIL_STREAK_LIMIT) {
                COUNT_BAIL_SKIPS_REMAINING.setOpaque(this, COUNT_BAIL_SKIPPED_COUNTS);
            }
        }

        // A DFA count that did not bail, including one the DFA could not run, ends the streak. It
        // leaves an open window, which keeps its skips. The guard skips writing a shared field that
        // is already zero.
        private void recordCountCompleted()
        {
            if (countBailStreak != 0) {
                countBailStreak = 0;
            }
        }

        // Returns the reset count a boolean search that runs the DFA judges against afterward. A
        // reset since the last one boolean searches saw came before this search started, from
        // another call: it seeds the allowance of the next reset, and is not judged.
        private int startBooleanSearch()
        {
            int resets = resetCount;
            if (resets != booleanSearchResetCount) {
                booleanSearchResetCount = resets;
                booleanSearchBytesUntilResetNotSlow = slowResetAllowance();
            }
            return resets;
        }

        // Judges the DFA work of a boolean search of `searchLength` bytes that started at
        // `resetsBefore` resets and returned `result`, by upstream's per-search rule applied
        // across boolean searches. The search is charged its returned progress: the match end,
        // relative to the search start; the whole window when it found no match; and nothing when
        // it failed, which a search that resets too often, a start state it could not allocate,
        // and a quit on a byte all return, so a failure is not itself a slow reset. A reset during
        // the search is taken as its own, and is slow when the charge leaves the allowance
        // positive. A slow reset extends the streak, and any other reset ends it; either way, the
        // reset seeds the next reset's allowance. Several resets during one search give at most
        // one judgement. Without a reset, the charge spends the allowance, and spending all of it
        // ends the streak. The search writes only at a reset or while the allowance is positive.
        // A reset is judged by the search whose starting reset count still owns the allowance: a
        // search whose reset count another search judged past, or a later search seeded past,
        // returns without charging. Under concurrent use, another thread's reset during the
        // search is taken as its own, a reset may go unjudged when searches start across it, and
        // an unjudged reset neither extends nor ends the streak, which delays or withholds the
        // window. The checks are plain reads, so two judges can pass one together, and a lost
        // charge can make a healthy reset look slow; "Count Fallback After Repeated Bails" in
        // REGULATOR_DECISIONS.md describes the race.
        private void judgeBooleanSearch(int resetsBefore, long result, int searchLength)
        {
            int bytesRead = result >= 0 ? (int) result : result == SEARCH_NO_MATCH ? searchLength : 0;
            int resets = resetCount;
            int bytesLeft = booleanSearchBytesUntilResetNotSlow;
            if (resets != resetsBefore) {
                if (booleanSearchResetCount != resetsBefore) {
                    return;
                }
                booleanSearchResetCount = resets;
                if (isSlowReset(bytesLeft, bytesRead)) {
                    recordBooleanSearchSlowReset();
                }
                else {
                    recordBooleanSearchStreakEnded();
                }
                booleanSearchBytesUntilResetNotSlow = slowResetAllowance();
            }
            else if (bytesLeft > 0 && bytesRead > 0) {
                bytesLeft -= bytesRead;
                booleanSearchBytesUntilResetNotSlow = bytesLeft;
                if (bytesLeft <= 0) {
                    recordBooleanSearchStreakEnded();
                }
            }
        }

        // Takes one boolean search of the skip window, if one is left, and returns whether it did.
        // A boolean search that loses the compare-and-set to another runs the DFA instead.
        private boolean takeBooleanSearchSkip()
        {
            int skips = (int) BOOLEAN_SEARCH_SKIPS_REMAINING.getOpaque(this);
            return skips > 0 && BOOLEAN_SEARCH_SKIPS_REMAINING.compareAndSet(this, skips, skips - 1);
        }

        // The streak stays at the limit, so a slow reset after the skipped searches opens the next
        // window at once.
        private void recordBooleanSearchSlowReset()
        {
            int streak = booleanSearchSlowResetStreak;
            if (streak < SLOW_RESET_STREAK_LIMIT) {
                streak++;
                booleanSearchSlowResetStreak = streak;
            }
            if (streak >= SLOW_RESET_STREAK_LIMIT) {
                BOOLEAN_SEARCH_SKIPS_REMAINING.setOpaque(this, BOOLEAN_SEARCH_SKIPPED_SEARCHES);
            }
        }

        // A reset that is not slow, or a boolean search that searched past the slow-reset
        // allowance, ends the streak. It leaves an open window, which keeps its skips. The guard
        // skips writing a shared field that is already zero.
        private void recordBooleanSearchStreakEnded()
        {
            if (booleanSearchSlowResetStreak != 0) {
                booleanSearchSlowResetStreak = 0;
            }
        }

        // The boolean searches left in their skip window.
        int booleanSearchSkipsRemaining()
        {
            return (int) BOOLEAN_SEARCH_SKIPS_REMAINING.getOpaque(this);
        }

        // The slow resets in a row boolean searches judged.
        int booleanSearchSlowResetStreak()
        {
            return booleanSearchSlowResetStreak;
        }

        int smallByteSetScanCount()
        {
            return smallByteSetScanCount;
        }

        int contiguousRangeScanCount()
        {
            return contiguousRangeScanCount;
        }

        int mixedByteSetScanCount()
        {
            return mixedByteSetScanCount;
        }

        int startLiteralFilterScanCount()
        {
            return startLiteralFilterScanCount;
        }

        /// Returns how many times forward searches called [Prog#prefixAccel] for a folded run
        /// behind a lead of varying width.
        int prefixRunScanCount()
        {
            return prefixRunScanCount;
        }

        StartLiteralFilter startLiteralFilter()
        {
            return startLiteralFilter;
        }

        int startClassFilterScanCount()
        {
            return startClassFilterScanCount;
        }

        StartClassFilter startClassFilter()
        {
            return startClassFilter;
        }

        int fixedDistanceShortSkipFallbackCount()
        {
            return fixedDistanceShortSkipFallbackCount;
        }

        private long selfLoopExitByteSet(CacheTables tables, Object[] stateReferences)
        {
            int stateOffset = rowStateOffset(stateReferences, nextSize, tables.transitions);
            long exitByteSet = countSelfLoopExitByteSet(tables, stateOffset);
            // The object-row scan follows only non-matching self-loops: in the exit form, one to
            // three exit bytes without the high-bit flag; in the stay form, any accepted ranges,
            // including those of a loop that leaves on every high byte.
            boolean highExits = exitByteSet > 0 && (exitByteSet & SELF_LOOP_EXIT_BYTE_SET_HIGH) != 0;
            return isMatchingSelfLoop(exitByteSet) || highExits ? 0 : exitByteSet;
        }

        // Returns zero unless every byte either stays in the state or is an exit, and the exits are
        // one to three bytes, or every high byte plus zero to three ASCII bytes, or the bytes that
        // stay form one to four ranges. The exit form is taken whenever it fits; the stay form
        // serves loops such as `[A-Z]*` or `\w*` that leave on most bytes. Accepted matching
        // self-loops carry SELF_LOOP_EXIT_BYTE_SET_MATCHING or SELF_LOOP_STAY_RANGES_MATCHING, and
        // all-high exits carry SELF_LOOP_EXIT_BYTE_SET_HIGH. UTF-8 dot loops exit on every lead and
        // invalid byte.
        private long countSelfLoopExitByteSet(CacheTables tables, int stateOffset)
        {
            int stateIndex = stateIndex(stateOffset);
            long exitByteSet = tables.selfLoopExitByteSets[stateIndex];
            if (exitByteSet != 0) {
                return acceptedSelfLoopExitByteSet(exitByteSet);
            }
            if (!cacheMutationLock.isHeldByCurrentThread()) {
                throw RETRY_SEARCH;
            }
            // Tables from before a growth may lack an entry the current ones hold.
            CacheTables currentTables = tablesAfterWrite(tables);
            exitByteSet = currentTables.selfLoopExitByteSets[stateIndex];
            if (exitByteSet != 0) {
                return acceptedSelfLoopExitByteSet(exitByteSet);
            }

            int stateFlag = currentTables.stateData[stateIndex].flag();
            for (int value = 0; value < 256; value++) {
                // A byte the DFA quits on stays uncomputed, so it leaves the self-loop, and a search
                // that reaches it quits there.
                if (quitsOnNonAscii && quits(stateFlag, value)) {
                    continue;
                }
                int nextState = computeTransition(currentTables, stateOffset, value);
                currentTables = tablesAfterWrite(currentTables);
                if (nextState == Integer.MIN_VALUE) {
                    currentTables.selfLoopExitByteSets[stateIndex] = SELF_LOOP_EXIT_BYTE_SET_REJECTED;
                    return 0;
                }
            }

            // Transitions into a state carry the match bit exactly when that state is a match state.
            boolean matching = (currentTables.stateData[stateIndex].flag() & FLAG_MATCH) != 0;
            int selfLoop = matching ? stateOffset | T_MATCH_BIT : stateOffset;
            int[] transitions = currentTables.transitions;
            boolean highExits = true;
            for (int value = 0x80; value < 256 && highExits; value++) {
                highExits = getTransition(transitions, stateOffset + (bytemap[value] & 0xFF)) != selfLoop;
            }
            int valueLimit = highExits ? 0x80 : 256;
            int exitByteCount = 0;
            int exits = 0;
            for (int value = 0; value < valueLimit; value++) {
                if (getTransition(transitions, stateOffset + (bytemap[value] & 0xFF)) == selfLoop) {
                    continue;
                }
                if (exitByteCount == 3) {
                    exitByteCount++;
                    break;
                }
                exits |= value << (exitByteCount * Byte.SIZE);
                exitByteCount++;
            }

            exitByteSet = (exitByteCount > 0 || highExits) && exitByteCount <= 3
                    ? exits |
                    (exitByteCount << SELF_LOOP_EXIT_BYTE_COUNT_SHIFT) |
                    (matching ? SELF_LOOP_EXIT_BYTE_SET_MATCHING : 0) |
                    (highExits ? SELF_LOOP_EXIT_BYTE_SET_HIGH : 0)
                    : selfLoopStayRanges(transitions, stateOffset, selfLoop, matching);
            currentTables.selfLoopExitByteSets[stateIndex] = exitByteSet;
            return acceptedSelfLoopExitByteSet(exitByteSet);
        }

        // The stay form of the self-loop at stateOffset, or SELF_LOOP_EXIT_BYTE_SET_REJECTED when
        // the bytes that stay do not form one to four ranges. A range holds at most 128 bytes, the
        // most its seven-bit width can describe, so a longer run of staying bytes takes two.
        private long selfLoopStayRanges(int[] transitions, int stateOffset, int selfLoop, boolean matching)
        {
            long ranges = 0;
            int rangeCount = 0;
            int value = 0;
            while (value < 256) {
                if (getTransition(transitions, stateOffset + (bytemap[value] & 0xFF)) != selfLoop) {
                    value++;
                    continue;
                }
                if (rangeCount == SELF_LOOP_MAX_STAY_RANGES) {
                    return SELF_LOOP_EXIT_BYTE_SET_REJECTED;
                }
                int lowest = value;
                while (value < 256 && value - lowest < 128 && getTransition(transitions, stateOffset + (bytemap[value] & 0xFF)) == selfLoop) {
                    value++;
                }
                ranges |= (long) (lowest | ((value - 1 - lowest) << Byte.SIZE)) << (rangeCount * Short.SIZE);
                rangeCount++;
            }
            if (rangeCount == 0) {
                return SELF_LOOP_EXIT_BYTE_SET_REJECTED;
            }
            int countLessOne = rangeCount - 1;
            return SELF_LOOP_STAY_RANGES | (matching ? SELF_LOOP_STAY_RANGES_MATCHING : 0) | ((long) (countLessOne & 1) << 15) | ((long) (countLessOne >>> 1) << 31) | ranges;
        }

        private static long acceptedSelfLoopExitByteSet(long exitByteSet)
        {
            return exitByteSet == SELF_LOOP_EXIT_BYTE_SET_REJECTED ? 0 : exitByteSet;
        }

        int cacheEntryCount()
        {
            return cache.size();
        }

        CacheSnapshot cacheSnapshot()
        {
            CacheTables tables = this.tables;
            int populatedStateData = 0;
            for (StateData state : tables.stateData) {
                if (state != null) {
                    populatedStateData++;
                }
            }

            int populatedStateReferences = 0;
            for (Object[] stateReferences : tables.stateRefArrays) {
                if (stateReferences != null) {
                    populatedStateReferences++;
                }
            }

            int populatedStartStates = countPopulatedStartStates(tables.startBeginText) +
                    countPopulatedStartStates(tables.startBeginLine) +
                    (tables.startTrinoLine == null ? 0 : countPopulatedStartStates(tables.startTrinoLine)) +
                    countPopulatedStartStates(tables.startAfterWord) +
                    countPopulatedStartStates(tables.startAfterNonWord) +
                    (tables.startAfterCarriageReturn == null ? 0 : countPopulatedStartStates(tables.startAfterCarriageReturn)) +
                    (tables.startAfterNonAscii == null ? 0 : countPopulatedStartStates(tables.startAfterNonAscii));

            return new CacheSnapshot(
                    stateCount,
                    cache.size(),
                    populatedStateData,
                    populatedStateReferences,
                    populatedStartStates,
                    pairedTransitionRowCount(),
                    pairedTransitionMemory,
                    fixedDistanceByteCandidateMemory,
                    availableStateMemory,
                    stateBudget,
                    tables.transitions.length,
                    tables.stateData.length,
                    tables.stateRefArrays.length,
                    resetCount);
        }

        StateMemorySnapshot stateMemorySnapshot()
        {
            CacheTables tables = this.tables;
            StateData[] stateData = tables.stateData;
            Object[][] stateRefArrays = tables.stateRefArrays;
            long transitionBytes = sizeOfIntArray(tables.transitions.length);
            long stateDataArrayBytes = sizeOfObjectArray(stateData.length);
            long stateReferenceArrayBytes = sizeOfObjectArray(stateRefArrays.length);
            long selfLoopExitByteSetBytes = sizeOfLongArray(tables.selfLoopExitByteSets.length);
            long optionalStartStateCacheBytes = optionalStartStateCacheMemory(tables);
            long stateReferenceRowBytes = 0;
            long instructionBytes = 0;
            int stateLimit = Math.min(nextFreeOffset / nextSize, stateData.length);
            for (int stateIndex = 0; stateIndex < stateLimit; stateIndex++) {
                StateData state = stateData[stateIndex];
                if (state == null) {
                    continue;
                }
                stateReferenceRowBytes += sizeOfObjectArray(stateRefArrays[stateIndex].length);
                instructionBytes += sizeOfIntArray(state.inst().length);
            }

            long stateMetadataBytes = (long) cache.size() *
                    (STATE_DATA_BYTES + STATE_KEY_BYTES);
            long cacheBytes = STATE_CACHE_BYTES +
                    cache.tableBytes();
            long totalBytes = transitionBytes +
                    stateDataArrayBytes +
                    stateReferenceArrayBytes +
                    selfLoopExitByteSetBytes +
                    optionalStartStateCacheBytes +
                    stateReferenceRowBytes +
                    instructionBytes +
                    stateMetadataBytes +
                    cacheBytes;
            return new StateMemorySnapshot(
                    totalBytes,
                    transitionBytes,
                    stateDataArrayBytes,
                    stateReferenceArrayBytes,
                    selfLoopExitByteSetBytes,
                    optionalStartStateCacheBytes,
                    stateReferenceRowBytes,
                    instructionBytes,
                    stateMetadataBytes,
                    cacheBytes,
                    cache.capacity());
        }

        private static int countPopulatedStartStates(StateData[][] startStates)
        {
            int count = 0;
            for (StateData[] startStateRow : startStates) {
                for (StateData startState : startStateRow) {
                    if (startState != null) {
                        count++;
                    }
                }
            }
            return count;
        }

        private long estimateStateSize(int instructionCount)
        {
            return estimateStateSize(nextSize, instructionCount);
        }

        private static long estimateStateSize(int nextSize, int instructionCount)
        {
            return stateObjectMemory(nextSize, instructionCount) +
                    ((long) nextSize * Integer.BYTES) +
                    (2 * REFERENCE_BYTES) +
                    (2 * REFERENCE_BYTES);
        }

        private StateData allocateCachedState(StateKey key, int[] instructions, int flag)
        {
            if (testingStateAllocationFailures > 0) {
                testingStateAllocationFailures--;
                return null;
            }
            beginStateAllocationForTesting();
            CacheTables tables = this.tables;
            int[] transitions = tables.transitions;
            long stateMemory = stateObjectMemory(instructions.length);
            long newTransitionsLength = transitions.length;
            int newStateCapacity = transitions.length / nextSize;
            if (nextFreeOffset + nextSize > transitions.length) {
                newTransitionsLength = Math.min((long) transitions.length * 2, maxTransitionsLength);
                long programTransitionsLength = (long) programStateCapacity * nextSize;
                if (transitions.length < programTransitionsLength) {
                    newTransitionsLength = Math.min(newTransitionsLength, programTransitionsLength);
                }
                if (newTransitionsLength <= transitions.length || newTransitionsLength < nextFreeOffset + nextSize) {
                    return null; // budget exhausted
                }
                newStateCapacity = toIntExact(newTransitionsLength / nextSize);
                stateMemory += stateTableMemory(newStateCapacity) - stateTableMemory(tables.stateData.length);
            }

            int newCacheTableCapacity = cache.capacityAfterInsert();
            stateMemory += sizeOfObjectArray(Math.multiplyExact(newCacheTableCapacity, 2)) - cache.tableBytes();

            AbsolutePointerGrowthSize absolutePointerGrowthSize = null;
            AbsolutePointerTransitions existingPointers = tables.absolutePointerTransitions;
            if (existingPointers != null && nextFreeOffset + nextSize > existingPointers.entryCapacity()) {
                absolutePointerGrowthSize = sizeAbsolutePointerTransitionGrowth(nextFreeOffset + nextSize, stateMemory, newStateCapacity);
                if (absolutePointerGrowthSize == null) {
                    return null;
                }
            }
            long absolutePointerMemory = absolutePointerGrowthSize == null ? 0 : absolutePointerGrowthSize.memoryDelta();
            boolean releasePairedTransitions = false;
            if (availableStateMemory - absolutePointerMemory < stateMemory) {
                releasePairedTransitions = tables.hasPairedRows() &&
                        availableStateMemory + pairedTransitionMemory - absolutePointerMemory >= stateMemory;
                if (!releasePairedTransitions) {
                    return null;
                }
            }

            int[] newTransitions = transitions;
            StateData[] newStateData = tables.stateData;
            Object[][] newStateRefArrays = tables.stateRefArrays;
            long[] newSelfLoopExitByteSets = tables.selfLoopExitByteSets;
            if (newTransitionsLength != transitions.length) {
                int oldLength = transitions.length;
                beginTestingFailurePoint(TestingFailurePoint.TRANSITION_ARRAY_GROWTH);
                newTransitions = Arrays.copyOf(transitions, toIntExact(newTransitionsLength));
                Arrays.fill(newTransitions, oldLength, newTransitions.length, T_UNCOMPUTED);
                endTestingFailurePoint(TestingFailurePoint.TRANSITION_ARRAY_GROWTH);
                beginTestingFailurePoint(TestingFailurePoint.STATE_DATA_ARRAY_GROWTH);
                newStateData = Arrays.copyOf(tables.stateData, newStateCapacity);
                endTestingFailurePoint(TestingFailurePoint.STATE_DATA_ARRAY_GROWTH);
                beginTestingFailurePoint(TestingFailurePoint.STATE_REFERENCE_ARRAY_GROWTH);
                newStateRefArrays = Arrays.copyOf(tables.stateRefArrays, newStateCapacity);
                endTestingFailurePoint(TestingFailurePoint.STATE_REFERENCE_ARRAY_GROWTH);
                beginTestingFailurePoint(TestingFailurePoint.SELF_LOOP_ARRAY_GROWTH);
                newSelfLoopExitByteSets = Arrays.copyOf(tables.selfLoopExitByteSets, newStateCapacity);
                endTestingFailurePoint(TestingFailurePoint.SELF_LOOP_ARRAY_GROWTH);
            }

            beginTestingFailurePoint(TestingFailurePoint.STATE_REFERENCE_ROW_ALLOCATION);
            Object[] stateReferences = new Object[nextSize + 1];
            endTestingFailurePoint(TestingFailurePoint.STATE_REFERENCE_ROW_ALLOCATION);
            StateData state = new StateData(instructions, flag, nextFreeOffset);
            setStateReference(stateReferences, nextSize, state);
            StateCache.PreparedInsertion cacheInsertion = cache.prepareInsert(key, state, newCacheTableCapacity);
            beginTestingFailurePoint(TestingFailurePoint.STATE_CACHE_INSERTION);
            endTestingFailurePoint(TestingFailurePoint.STATE_CACHE_INSERTION);
            AbsolutePointerGrowth absolutePointerGrowth = null;
            if (absolutePointerGrowthSize != null) {
                absolutePointerGrowth = allocateAbsolutePointerTransitionGrowth(absolutePointerGrowthSize, existingPointers, transitions);
                endTestingFailurePoint(TestingFailurePoint.POINTER_TABLE_GROWTH);
            }

            // Every allocation precedes the first write to the DFA, so a failure that propagates
            // leaves the cache and its charges as they were; a failed pointer-table allocation
            // withholds that table. The enlarged pointer segment is allocated last, after the Java
            // arrays and state; only small objects (new `CacheTables` and, for a native table, the
            // superseded reference) follow it, and an error in them abandons the segment uncounted.
            CacheTables newTables = tables;
            if (releasePairedTransitions) {
                newTables = newTables.withPairedRows(null, null, null);
            }
            if (absolutePointerGrowth != null) {
                newTables = newTables.withAbsolutePointerTransitions(absolutePointerGrowth.transitions());
            }
            if (newTransitions != transitions) {
                newTables = newTables.withStates(newTransitions, newStateData, newStateRefArrays, newSelfLoopExitByteSets);
            }
            if (absolutePointerGrowth != null) {
                supersedeAbsolutePointerTransitions(existingPointers);
            }

            // The new state's slots are empty in every published table, so they are filled in place;
            // grown arrays are filled before their tables are published.
            int stateIndex = stateIndex(nextFreeOffset);
            newStateData[stateIndex] = state;
            newStateRefArrays[stateIndex] = stateReferences;
            if (releasePairedTransitions) {
                availableStateMemory += pairedTransitionMemory;
                pairedTransitionMemory = 0;
                pairedContentVersion = -1;
                pairedRowReleaseCount++;
            }
            if (absolutePointerGrowth != null) {
                absolutePointerTransitionCount = absolutePointerGrowth.transitionCount();
                availableStateMemory -= absolutePointerGrowth.memoryDelta();
                if (absolutePointerGrowth.transitions() == null) {
                    absolutePointerTransitionsWithheld = true;
                    absolutePointerTransitionAllocationFailed |= absolutePointerGrowth.allocationFailed();
                }
            }
            if (newTables != tables) {
                this.tables = newTables;
            }
            nextFreeOffset += nextSize;
            stateCount++;
            availableStateMemory -= stateMemory;
            retainedStateMemory += stateMemory;
            cache.commit(cacheInsertion);
            cacheContentVersion++;
            return state;
        }

        boolean hasPairedTransitions(CacheTables tables, int stateOffset)
        {
            return FLAT_PAIRED_ROWS
                    ? pairedRowOffset(tables.pairedRowOffsets, stateIndex(stateOffset)) >= 0
                    : pairedStateReferences(tables.pairedStateRefArrays, stateIndex(stateOffset)) != null;
        }

        private void recordPairedSearch(int searchedBytes)
        {
            if (searchedBytes >= SHORT_PAIRED_SEARCH_BYTES) {
                shortPairedSearchCount = 0;
                return;
            }

            if (++shortPairedSearchCount < SHORT_PAIRED_SEARCH_LIMIT) {
                return;
            }

            // The hint is intentionally racy: concurrent readers may change when the
            // threshold is reached, but rejection only selects the equivalent compact path.
            // This search has already completed, and an exclusive search cannot retry, so the
            // next paired or pointer-table request releases the table under the exclusive
            // cache protocol.
            pairedTransitionsRejected = true;
            pairedTransitionsRequested = false;
        }

        // Returns the tables the search reads next: these, or the current ones after a release.
        CacheTables requestPairedTransitions(CacheTables tables)
        {
            if (pairedTransitionsRequested) {
                return tables;
            }
            if (!cacheMutationLock.isHeldByCurrentThread()) {
                throw RETRY_SEARCH;
            }
            if (pairedTransitionsRejected) {
                releaseRejectedPairedTransitions();
                return this.tables;
            }
            pairedTransitionsRequested = true;
            return tables;
        }

        // Returns the tables the search reads next: these, or the current ones when the request is
        // recorded under the lock.
        CacheTables requestAbsolutePointerTransitions(CacheTables tables)
        {
            // A withheld table is already requested, and the exclusive path allocates it again, so a
            // reader continues on object rows instead of retrying.
            if (tables.absolutePointerTransitions != null || absolutePointerTransitionsUnavailable || absolutePointerTransitionsWithheld) {
                return tables;
            }
            if (kind == Kind.MANY_MATCH || stateBudget == Long.MAX_VALUE) {
                absolutePointerTransitionsUnavailable = true;
                return tables;
            }
            if (!cacheMutationLock.isHeldByCurrentThread()) {
                throw RETRY_SEARCH;
            }
            // Rejected paired rows block the pointer table, and short searches never request
            // paired rows. Keeping them would retry every later short search exclusively.
            if (pairedTransitionsRejected) {
                releaseRejectedPairedTransitions();
            }
            absolutePointerTransitionsRequested = true;
            return this.tables;
        }

        private void releaseRejectedPairedTransitions()
        {
            clearPairedTransitions();
            pairedTransitionsRejected = false;
            pairedTransitionsDisabled = true;
            pairedTransitionsRequested = true;
        }

        boolean shouldUseAbsolutePointersForShortSearch(CacheTables tables)
        {
            if (tables.absolutePointerTransitions != null) {
                return true;
            }
            if (absolutePointerTransitionsUnavailable || absolutePointerTransitionsWithheld || pairedTransitionsRequested) {
                return false;
            }
            // This racy hint changes only when the equivalent pointer route is selected.
            return++shortAbsolutePointerSearchCount >= SHORT_ABSOLUTE_POINTER_SEARCH_LIMIT;
        }

        boolean hasAbsolutePointerTransitions(CacheTables tables, int stateOffset)
        {
            AbsolutePointerTransitions absolutePointers = tables.absolutePointerTransitions;
            return absolutePointers != null &&
                    stateOffset >= nextSize &&
                    stateOffset + nextSize <= absolutePointers.entryCapacity();
        }

        long absolutePointerTransitionMemory()
        {
            AbsolutePointerTransitions absolutePointers = tables.absolutePointerTransitions;
            return absolutePointers == null ? 0 : absolutePointers.allocatedBytes();
        }

        MemorySegment absolutePointerTransitionSegment()
        {
            AbsolutePointerTransitions absolutePointers = tables.absolutePointerTransitions;
            return absolutePointers == null ? null : absolutePointers.segment();
        }

        WeakReference<Object> absolutePointerOwnerReferenceForTesting()
        {
            return new WeakReference<>(tables.absolutePointerTransitions);
        }

        int absolutePointerTransitionCount()
        {
            return absolutePointerTransitionCount;
        }

        int absolutePointerBackwardSearchCount()
        {
            return absolutePointerBackwardSearchCount;
        }

        boolean absolutePointerTransitionsAvailable()
        {
            return tables.absolutePointerTransitions != null;
        }

        boolean absolutePointerTransitionsRequested()
        {
            return absolutePointerTransitionsRequested;
        }

        // The table is given up for good; a withheld table is neither available nor unavailable.
        boolean absolutePointerTransitionsUnavailable()
        {
            return absolutePointerTransitionsUnavailable;
        }

        long supersededAbsolutePointerMemory()
        {
            cacheMutationLock.lock();
            try {
                releaseUnreachableAbsolutePointerTables();
                return supersededAbsolutePointerMemory;
            }
            finally {
                cacheMutationLock.unlock();
            }
        }

        long pairedTransitionMemory()
        {
            return pairedTransitionMemory;
        }

        int pairedTransitionRowCount()
        {
            return pairedRowStateOffsets(tables).length;
        }

        int pairedTransitionCount()
        {
            int pairedClassCount = nextSize * nextSize;
            MemorySegment flatPairedRows = tables.flatPairedRows;
            if (flatPairedRows != null) {
                int pairedRowBytes = pairedRowBytes();
                int transitionCount = 0;
                for (int rowOffset = 0; rowOffset < flatPairedRows.byteSize(); rowOffset += pairedRowBytes) {
                    for (int pairedClass = 0; pairedClass < pairedClassCount; pairedClass++) {
                        if (getPairedSlot(flatPairedRows, rowOffset + (pairedClass * Integer.BYTES)) >= 0) {
                            transitionCount++;
                        }
                    }
                }
                return transitionCount;
            }
            Object[][] pairedStateRefArrays = tables.pairedStateRefArrays;
            if (pairedStateRefArrays == null) {
                return 0;
            }

            int transitionCount = 0;
            for (Object[] pairedStateRefArray : pairedStateRefArrays) {
                if (pairedStateRefArray == null) {
                    continue;
                }
                for (int pairedClass = 0; pairedClass < pairedClassCount; pairedClass++) {
                    if (pairedStateRefArray[pairedClass] != null) {
                        transitionCount++;
                    }
                }
            }
            return transitionCount;
        }

        int selfLoopTransitionCount()
        {
            int transitionCount = 0;
            Object[][] stateRefArrays = tables.stateRefArrays;
            for (Object[] stateRefs : stateRefArrays) {
                if (stateRefs == null) {
                    continue;
                }
                for (int byteClass = 0; byteClass < nextSize; byteClass++) {
                    if (stateRefs[byteClass] == stateRefs) {
                        transitionCount++;
                    }
                }
            }
            return transitionCount;
        }

        int pairedMatchContinuationCount()
        {
            if (kind != Kind.FIRST_MATCH) {
                return 0;
            }
            CacheTables tables = this.tables;
            int continuationCount = 0;
            for (int stateOffset : pairedRowStateOffsets(tables)) {
                for (int firstClass = 0; firstClass < nextSize; firstClass++) {
                    for (int secondClass = 0; secondClass < nextSize; secondClass++) {
                        int outcome = (int) decodePairedTransition(tables, stateOffset, firstClass, secondClass);
                        if (outcome == PAIR_MATCH_FIRST || outcome == PAIR_MATCH_SECOND) {
                            continuationCount++;
                        }
                    }
                }
            }
            return continuationCount;
        }

        // The offsets of the states with a paired row, in either form.
        private int[] pairedRowStateOffsets(CacheTables tables)
        {
            MemorySegment flatPairedRows = tables.flatPairedRows;
            if (flatPairedRows != null) {
                int pairedRowBytes = pairedRowBytes();
                int[] stateOffsets = new int[toIntExact(flatPairedRows.byteSize() / pairedRowBytes)];
                for (int row = 0; row < stateOffsets.length; row++) {
                    stateOffsets[row] = getPairedSlot(flatPairedRows, ((row + 1) * pairedRowBytes) - Integer.BYTES);
                }
                return stateOffsets;
            }
            Object[][] pairedStateRefArrays = tables.pairedStateRefArrays;
            if (pairedStateRefArrays == null) {
                return new int[0];
            }
            int[] stateOffsets = new int[pairedStateRefArrays.length];
            int rowCount = 0;
            for (Object[] pairedStateRefs : pairedStateRefArrays) {
                if (pairedStateRefs != null) {
                    stateOffsets[rowCount++] = ((StateData) pairedStateRefs[nextSize * nextSize]).offset();
                }
            }
            return Arrays.copyOf(stateOffsets, rowCount);
        }

        int matchingSelfLoopScanCount()
        {
            return matchingSelfLoopScanCount;
        }

        int matchingSelfLoopExitByteScanCount()
        {
            return matchingSelfLoopExitByteScanCount;
        }

        int selfLoopExitByteScanCount()
        {
            return selfLoopExitByteScanCount;
        }

        int reverseSelfLoopExitByteScanCount()
        {
            return reverseSelfLoopExitByteScanCount;
        }

        int countSelfLoopScanCount()
        {
            return countSelfLoopScanCount;
        }

        int countSelfLoopMatchHandOffCount()
        {
            return countSelfLoopMatchHandOffCount;
        }

        int countSelfLoopNoMatchPointerHandOffCount()
        {
            return countSelfLoopNoMatchPointerHandOffCount;
        }

        int countSelfLoopRearmCount()
        {
            return countSelfLoopRearmCount;
        }

        int countSelfLoopUnproductiveCount()
        {
            return countSelfLoopUnproductiveCount;
        }

        int selfLoopStayRangeScanCount()
        {
            return selfLoopStayRangeScanCount;
        }

        int pairedMatchContinuationToUnpairedCount()
        {
            if (kind != Kind.FIRST_MATCH) {
                return 0;
            }
            CacheTables tables = this.tables;
            int continuationCount = 0;
            for (int stateOffset : pairedRowStateOffsets(tables)) {
                for (int firstClass = 0; firstClass < nextSize; firstClass++) {
                    for (int secondClass = 0; secondClass < nextSize; secondClass++) {
                        long pairedTransition = decodePairedTransition(tables, stateOffset, firstClass, secondClass);
                        int outcome = (int) pairedTransition;
                        if ((outcome == PAIR_MATCH_FIRST || outcome == PAIR_MATCH_SECOND) &&
                                !hasPairedTransitions(tables, (int) (pairedTransition >>> 32))) {
                            continuationCount++;
                        }
                    }
                }
            }
            return continuationCount;
        }

        boolean pairedTransitionsDisabled()
        {
            return pairedTransitionsDisabled;
        }

        int pairedRowSelectionCount()
        {
            return pairedRowSelectionCount;
        }

        int pairedRowReleaseCount()
        {
            return pairedRowReleaseCount;
        }

        int byteScanFallbackCount()
        {
            return byteScanFallbackCount;
        }

        long estimatedPairedTransitionMemory()
        {
            int stateLimit = Math.min(nextFreeOffset / nextSize, tables.stateData.length);
            // Capped at the rows one array can hold, far beyond every paired cap, which keeps
            // `rowCount * pairedRowLength()` within an int.
            return pairedTransitionMemory(stateLimit, Math.min(stateCount, Integer.MAX_VALUE / pairedRowLength()));
        }

        long availableStateMemory()
        {
            return availableStateMemory;
        }

        long retainedStateMemory()
        {
            return retainedStateMemory;
        }

        long stateBudget()
        {
            return stateBudget;
        }

        int programStateCapacity()
        {
            return programStateCapacity;
        }

        private void rebuildPairedTransitions()
        {
            clearPairedTransitions();
            if (prog.reversed() || kind == Kind.MANY_MATCH || stateCount == 0) {
                return;
            }

            CacheTables tables = this.tables;
            int stateLimit = Math.min(nextFreeOffset / nextSize, tables.stateData.length);
            long maximumMemory = Math.min(MAX_PAIRED_TRANSITION_MEMORY, availableStateMemory);
            if (pairedTransitionMemory(stateLimit, 1) > maximumMemory) {
                return;
            }

            int maximumRows = maximumPairedRows(stateLimit, maximumMemory);
            boolean completeTable = maximumRows >= stateCount;
            if (!completeTable && pairedTransitionMemory(stateLimit, 2) > MAX_PARTIAL_PAIRED_TRANSITION_MEMORY) {
                return;
            }
            boolean[] selectedRows = selectPairedRows(tables, stateLimit, maximumRows);
            int selectedRowCount = 0;
            for (boolean selected : selectedRows) {
                if (selected) {
                    selectedRowCount++;
                }
            }
            if (selectedRowCount == 0) {
                return;
            }

            long memory = pairedTransitionMemory(stateLimit, selectedRowCount);
            // A partial table is useful only for the common entry-to-hot-loop shape.
            // Broader partial tables consume state budget and cache without reducing
            // the dependent-load chain consistently enough to justify their cost.
            if (!completeTable &&
                    (selectedRowCount != 2 ||
                            memory > MAX_PARTIAL_PAIRED_TRANSITION_MEMORY)) {
                return;
            }

            // Allocate the new tables before charging, so a failed allocation leaves the charge
            // unchanged; the rows are complete before the tables publish them.
            CacheTables pairedTables = FLAT_PAIRED_ROWS
                    ? buildPairedRowOffsets(tables, stateLimit, selectedRows, selectedRowCount)
                    : buildPairedStateRefArrays(tables, stateLimit, selectedRows);
            if (pairedTables == null) {
                return;
            }
            availableStateMemory -= memory;
            pairedTransitionMemory = memory;
            this.tables = pairedTables;
        }

        // Tables publishing object rows for the selected states, or null when no pair reaches a
        // selected row.
        private CacheTables buildPairedStateRefArrays(CacheTables tables, int stateLimit, boolean[] selectedRows)
        {
            StateData[] stateData = tables.stateData;
            Object[][] stateRefArrays = tables.stateRefArrays;
            int pairedClassCount = nextSize * nextSize;
            Object[][] pairedRows = new Object[stateLimit][];
            for (int stateIndex = 1; stateIndex < stateLimit; stateIndex++) {
                if (selectedRows[stateIndex]) {
                    pairedRows[stateIndex] = new Object[pairedClassCount + 1];
                    pairedRows[stateIndex][pairedClassCount] = stateData[stateIndex];
                }
            }

            int transitionCount = 0;

            for (int stateIndex = 1; stateIndex < stateLimit; stateIndex++) {
                Object[] source = stateRefArrays[stateIndex];
                Object[] sourcePairs = pairedRows[stateIndex];
                if (sourcePairs == null) {
                    continue;
                }
                for (int firstClass = 0; firstClass < nextSize; firstClass++) {
                    if (!(source[firstClass] instanceof Object[] intermediate)) {
                        continue;
                    }
                    for (int secondClass = 0; secondClass < nextSize; secondClass++) {
                        if (intermediate[secondClass] instanceof Object[] target) {
                            StateData targetData = (StateData) target[nextSize];
                            Object[] pairedTarget = pairedRows[stateIndex(targetData.offset())];
                            sourcePairs[(firstClass * nextSize) + secondClass] = pairedTarget;
                            if (pairedTarget != null) {
                                transitionCount++;
                            }
                        }
                    }
                }
            }
            return transitionCount == 0 ? null : tables.withPairedRows(pairedRows, null, null);
        }

        // Tables publishing the flat paired table for the selected states, or null when no pair
        // reaches a selected row.
        private CacheTables buildPairedRowOffsets(CacheTables tables, int stateLimit, boolean[] selectedRows, int selectedRowCount)
        {
            StateData[] stateData = tables.stateData;
            Object[][] stateRefArrays = tables.stateRefArrays;
            int pairedClassCount = nextSize * nextSize;
            int pairedRowLength = pairedRowLength();
            int[] pairedRowOffsets = new int[stateLimit];
            Arrays.fill(pairedRowOffsets, NO_PAIRED_ROW);
            int[] flatPairedRows = new int[selectedRowCount * pairedRowLength];
            Arrays.fill(flatPairedRows, NO_PAIRED_ROW);
            int nextRowStart = 0;
            for (int stateIndex = 1; stateIndex < stateLimit; stateIndex++) {
                if (selectedRows[stateIndex]) {
                    pairedRowOffsets[stateIndex] = nextRowStart * Integer.BYTES;
                    flatPairedRows[nextRowStart + pairedClassCount] = stateData[stateIndex].offset();
                    nextRowStart += pairedRowLength;
                }
            }

            int transitionCount = 0;

            for (int stateIndex = 1; stateIndex < stateLimit; stateIndex++) {
                Object[] source = stateRefArrays[stateIndex];
                int sourceRowOffset = pairedRowOffsets[stateIndex];
                if (sourceRowOffset < 0) {
                    continue;
                }
                for (int firstClass = 0; firstClass < nextSize; firstClass++) {
                    if (!(source[firstClass] instanceof Object[] intermediate)) {
                        continue;
                    }
                    for (int secondClass = 0; secondClass < nextSize; secondClass++) {
                        if (intermediate[secondClass] instanceof Object[] target) {
                            StateData targetData = (StateData) target[nextSize];
                            int targetRowOffset = pairedRowOffsets[stateIndex(targetData.offset())];
                            flatPairedRows[(sourceRowOffset / Integer.BYTES) + (firstClass * nextSize) + secondClass] = targetRowOffset;
                            if (targetRowOffset >= 0) {
                                transitionCount++;
                            }
                        }
                    }
                }
            }
            return transitionCount == 0 ? null : tables.withPairedRows(null, pairedRowOffsets, MemorySegment.ofArray(flatPairedRows));
        }

        // Allocates the requested pointer table, or allocates a withheld one again. Sizing that does
        // not fit the budget makes the table unavailable when it is first requested; a withheld table
        // was already requested and waits for a reset to free budget instead. The first allocation
        // may wait once for a search that computes a transition.
        private void allocateAbsolutePointerTransitions()
        {
            CacheTables tables = this.tables;
            if (!absolutePointerTransitionsRequested ||
                    tables.absolutePointerTransitions != null ||
                    absolutePointerTransitionsUnavailable ||
                    absolutePointerTransitionAllocationFailed ||
                    tables.hasPairedRows()) {
                return;
            }
            // A search that ended before computing a transition, such as one whose word boundary
            // met a byte outside ASCII at its first byte, gave pairing nothing to evaluate. The
            // request stays, so the next exclusive search chooses between paired rows and the
            // table. It waits only once, so a DFA that never computes a transition does not retry
            // every search exclusively.
            if (!absolutePointerTransitionDeferralUsed &&
                    pairedTransitionsRequested &&
                    !pairedTransitionsDisabled &&
                    computedNoTransition(tables.transitions)) {
                absolutePointerTransitionDeferralUsed = true;
                return;
            }

            long rowCapacity;
            long allocatedBytes;
            long tableGrowthMemory = unpaidStateTableMemory(tables.stateData.length);
            try {
                long currentRowCount = nextFreeOffset / nextSize;
                // Reserve enough budget for the Java states addressable by the sidecar, and for
                // the state tables' growth to one row per instruction, instead of eagerly consuming
                // the complete DFA budget for a small graph. A heap table's array header is charged
                // once, outside the per-row cost.
                rowCapacity = compatiblePointerRowCount(
                        availableStateMemory - tableGrowthMemory - absolutePointerTableBytes(0),
                        currentRowCount,
                        Math.min(maxTransitionsLength / nextSize, Math.max(2, currentRowCount * 2)));
                allocatedBytes = absolutePointerTableBytes(rowCapacity);
            }
            catch (ArithmeticException ignored) {
                if (!absolutePointerTransitionsWithheld) {
                    absolutePointerTransitionsUnavailable = true;
                }
                return;
            }
            if (rowCapacity < 2 ||
                    rowCapacity > Integer.MAX_VALUE ||
                    allocatedBytes > availableStateMemory - tableGrowthMemory ||
                    nextFreeOffset > rowCapacity * nextSize) {
                if (!absolutePointerTransitionsWithheld) {
                    absolutePointerTransitionsUnavailable = true;
                }
                return;
            }

            AbsolutePointerTransitions absolutePointers = null;
            if (supersededAbsolutePointerMemoryAllows(allocatedBytes, 0)) {
                absolutePointers = allocateAbsolutePointerTable(allocatedBytes, toIntExact(rowCapacity * nextSize));
                absolutePointerTransitionAllocationFailed = absolutePointers == null;
            }
            if (absolutePointers == null) {
                absolutePointerTransitionsWithheld = true;
                pairedTransitionsDisabled = true;
                return;
            }
            int transitionCount = backfillAbsolutePointerTransitions(tables.transitions, absolutePointers);
            CacheTables pointerTables = tables.withAbsolutePointerTransitions(absolutePointers);

            availableStateMemory -= allocatedBytes;
            absolutePointerTransitionCount = transitionCount;
            absolutePointerTransitionsPublished = true;
            this.tables = pointerTables;
            absolutePointerTransitionsWithheld = false;
            pairedTransitionsDisabled = true;
        }

        // Whether no state's row, after the reserved row 0, holds a computed transition.
        private boolean computedNoTransition(int[] transitions)
        {
            for (int transitionIndex = nextSize; transitionIndex < nextFreeOffset; transitionIndex++) {
                if (transitions[transitionIndex] != T_UNCOMPUTED) {
                    return false;
                }
            }
            return true;
        }

        // Returns the growth's size and charge, so the budget check sees it before the Java
        // allocations; a zero-byte size withholds the table when superseded native segments fill
        // their cap; null means the budget cannot hold the growth. `stateCapacity` is the state
        // tables' capacity once the allocation that needs the growth has grown them.
        private AbsolutePointerGrowthSize sizeAbsolutePointerTransitionGrowth(int requiredEntryCapacity, long reservedStateMemory, int stateCapacity)
        {
            AbsolutePointerTransitions existingPointers = tables.absolutePointerTransitions;
            if (existingPointers == null || requiredEntryCapacity <= existingPointers.entryCapacity()) {
                throw new IllegalStateException("pointer table growth is not required");
            }

            long requiredRowCount;
            long rowCapacity;
            long allocatedBytes;
            try {
                requiredRowCount = Math.addExact(requiredEntryCapacity, nextSize - 1L) / nextSize;
                long currentRowCount = existingPointers.entryCapacity() / nextSize;
                long maximumRowCount = maxTransitionsLength / nextSize;
                long remainingMemory = Math.max(0, availableStateMemory - reservedStateMemory - unpaidStateTableMemory(stateCapacity));
                // Preserve enough budget for the minimum Java state behind every additional
                // pointer row, and for the state tables' growth to one row per instruction. A heap
                // table's array header is charged once, outside the per-row cost. Failure means
                // ordinary DFA budget exhaustion, not an object-row representation change.
                rowCapacity = compatiblePointerRowCount(
                        Math.addExact(remainingMemory, existingPointers.allocatedBytes() - absolutePointerTableBytes(0)),
                        requiredRowCount,
                        Math.min(maximumRowCount, currentRowCount * 2));
                allocatedBytes = absolutePointerTableBytes(rowCapacity);
            }
            catch (ArithmeticException ignored) {
                return null;
            }
            if (rowCapacity < requiredRowCount || rowCapacity > Integer.MAX_VALUE) {
                return null;
            }

            // When superseded native segments fill their cap, the growth withholds the table and
            // the state is allocated on object rows.
            if (!supersededAbsolutePointerMemoryAllows(allocatedBytes, existingPointers.allocatedBytes())) {
                return new AbsolutePointerGrowthSize(0, 0, -existingPointers.allocatedBytes());
            }
            return new AbsolutePointerGrowthSize(
                    toIntExact(rowCapacity * nextSize),
                    allocatedBytes,
                    allocatedBytes - existingPointers.allocatedBytes());
        }

        // Allocates and backfills the segment a growth was sized for, or withholds the table. A
        // withheld table refunds the current segment's charge, which leaves more budget than the
        // sized charge the growth was checked against. The enlarged table is rebased into a new
        // segment and published with new tables; a search reading older tables keeps the superseded
        // segment reachable until it returns.
        private AbsolutePointerGrowth allocateAbsolutePointerTransitionGrowth(
                AbsolutePointerGrowthSize size,
                AbsolutePointerTransitions existingPointers,
                int[] transitions)
        {
            beginTestingFailurePoint(TestingFailurePoint.POINTER_TABLE_GROWTH);
            if (size.withheld()) {
                return new AbsolutePointerGrowth(null, 0, size.memoryDelta(), false);
            }
            AbsolutePointerTransitions expandedPointers = consumeTestingFailurePoint(TestingFailurePoint.POINTER_TABLE_GROWTH_ALLOCATION)
                    ? null
                    : allocateAbsolutePointerTable(size.allocatedBytes(), size.entryCapacity());
            if (expandedPointers == null) {
                return new AbsolutePointerGrowth(null, 0, -existingPointers.allocatedBytes(), true);
            }
            // The current transitions hold every computed entry: a grown array copies them and adds
            // only uncomputed ones.
            int transitionCount = backfillAbsolutePointerTransitions(transitions, expandedPointers);
            return new AbsolutePointerGrowth(expandedPointers, transitionCount, size.memoryDelta(), false);
        }

        // Whether a new pointer table of `allocatedBytes` may be allocated while one of
        // `replacedBytes` is superseded. Superseded native segments are freed only by the garbage
        // collector, which nothing here can wait for, so the counted ones may total at most twice
        // the new one, counting the one it replaces: with the new one, this DFA's counted native
        // memory stays within three times the newest segment. A segment is counted until the
        // collector finds it unreachable, and the JDK frees it later on its common cleaner, so the
        // native memory actually held stays within that bound only up to the cleaner's backlog. The
        // count is released here, so it may briefly over-count.
        private boolean supersededAbsolutePointerMemoryAllows(long allocatedBytes, long replacedBytes)
        {
            if (HEAP_POINTER_TABLES) {
                // A superseded heap table is ordinary garbage that only in-flight searches hold, like
                // superseded Java tables, so it is neither counted nor capped.
                return true;
            }
            releaseUnreachableAbsolutePointerTables();
            return supersededAbsolutePointerMemory + replacedBytes <= 2 * allocatedBytes;
        }

        // Counts a native pointer table that is no longer in the current tables until the collector
        // finds it unreachable. The reference is the only allocation, so a failed one leaves the
        // count as it was.
        private void supersedeAbsolutePointerTransitions(AbsolutePointerTransitions absolutePointers)
        {
            if (HEAP_POINTER_TABLES) {
                return;
            }
            SupersededAbsolutePointerReference reference = new SupersededAbsolutePointerReference(absolutePointers, unreachableAbsolutePointerTables);
            reference.next = supersededAbsolutePointerReferences;
            if (supersededAbsolutePointerReferences != null) {
                supersededAbsolutePointerReferences.previous = reference;
            }
            supersededAbsolutePointerReferences = reference;
            supersededAbsolutePointerMemory += reference.allocatedBytes;
        }

        // Releases the count of each superseded native pointer table the collector has found
        // unreachable, and unlinks its reference. Runs only under the cache mutation lock, never on
        // a search path.
        private void releaseUnreachableAbsolutePointerTables()
        {
            SupersededAbsolutePointerReference reference;
            while ((reference = (SupersededAbsolutePointerReference) unreachableAbsolutePointerTables.poll()) != null) {
                if (reference.previous == null) {
                    supersededAbsolutePointerReferences = reference.next;
                }
                else {
                    reference.previous.next = reference.next;
                }
                if (reference.next != null) {
                    reference.next.previous = reference.previous;
                }
                reference.previous = null;
                reference.next = null;
                supersededAbsolutePointerMemory -= reference.allocatedBytes;
            }
        }

        // The charge for a pointer table of `rowCount` rows: its native bytes, or the real size of its
        // heap array, header included.
        private long absolutePointerTableBytes(long rowCount)
        {
            long entryCount = Math.multiplyExact(rowCount, nextSize);
            if (HEAP_POINTER_TABLES) {
                return sizeOfLongArray(toIntExact(entryCount));
            }
            return Math.multiplyExact(entryCount, Long.BYTES);
        }

        // A zeroed pointer table, or null when its memory cannot be allocated.
        private static AbsolutePointerTransitions allocateAbsolutePointerTable(long allocatedBytes, int entryCapacity)
        {
            Arena arena = null;
            MemorySegment segment;
            try {
                if (HEAP_POINTER_TABLES) {
                    segment = MemorySegment.ofArray(new long[entryCapacity]);
                }
                else {
                    arena = Arena.ofAuto();
                    segment = arena.allocate(allocatedBytes, Long.BYTES);
                    segment.fill((byte) 0);
                }
            }
            catch (RuntimeException | OutOfMemoryError ignored) {
                return null;
            }
            return new AbsolutePointerTransitions(arena, segment, segment.address(), entryCapacity, allocatedBytes);
        }

        private int backfillAbsolutePointerTransitions(int[] transitions, AbsolutePointerTransitions absolutePointers)
        {
            int transitionCount = 0;
            int transitionLimit = Math.min(nextFreeOffset, absolutePointers.entryCapacity());
            for (int transitionIndex = nextSize; transitionIndex < transitionLimit; transitionIndex++) {
                int transition = transitions[transitionIndex];
                if (transition != T_UNCOMPUTED &&
                        setAbsolutePointerTransition(absolutePointers, transitionIndex, transition, nextSize)) {
                    transitionCount++;
                }
            }
            return transitionCount;
        }

        private static boolean setAbsolutePointerTransition(
                AbsolutePointerTransitions absolutePointers,
                int transitionIndex,
                int transition,
                int nextSize)
        {
            long encodedTransition;
            if (transition == T_DEAD) {
                encodedTransition = ABSOLUTE_POINTER_DEAD_TAG;
            }
            else if (transition == T_FULL_MATCH) {
                encodedTransition = ABSOLUTE_POINTER_FULL_MATCH_TAG;
            }
            else if (transition > 0) {
                int targetOffset = transition & ~T_MATCH_BIT;
                if (targetOffset + nextSize > absolutePointers.entryCapacity()) {
                    return false;
                }
                encodedTransition = absolutePointers.baseAddress() + ((long) targetOffset * Long.BYTES);
                if ((transition & T_MATCH_BIT) != 0) {
                    encodedTransition |= ABSOLUTE_POINTER_MATCH_TAG;
                }
            }
            else {
                return false;
            }
            absolutePointers.segment().set(
                    ValueLayout.JAVA_LONG_UNALIGNED,
                    (long) transitionIndex * Long.BYTES,
                    encodedTransition);
            return true;
        }

        private boolean[] selectPairedRows(CacheTables tables, int stateLimit, int maximumRows)
        {
            pairedRowSelectionCount++;
            boolean[] selected = new boolean[stateLimit];
            int selectedCount = 0;

            selectedCount = selectCachedStartRows(selected, tables.startBeginText[1], maximumRows, selectedCount);
            selectedCount = selectCachedStartRows(selected, tables.startBeginLine[1], maximumRows, selectedCount);
            if (tables.startTrinoLine != null) {
                selectedCount = selectCachedStartRows(selected, tables.startTrinoLine[1], maximumRows, selectedCount);
            }
            selectedCount = selectCachedStartRows(selected, tables.startAfterWord[1], maximumRows, selectedCount);
            selectedCount = selectCachedStartRows(selected, tables.startAfterNonWord[1], maximumRows, selectedCount);
            if (tables.startAfterCarriageReturn != null) {
                selectedCount = selectCachedStartRows(selected, tables.startAfterCarriageReturn[1], maximumRows, selectedCount);
            }
            if (tables.startAfterNonAscii != null) {
                selectedCount = selectCachedStartRows(selected, tables.startAfterNonAscii[1], maximumRows, selectedCount);
            }

            int[] selfLoopCounts = new int[stateLimit];
            for (int stateIndex = 1; stateIndex < stateLimit; stateIndex++) {
                Object[] state = tables.stateRefArrays[stateIndex];
                if (state == null) {
                    continue;
                }
                for (int byteClass = 0; byteClass < nextSize; byteClass++) {
                    if (state[byteClass] == state) {
                        selfLoopCounts[stateIndex]++;
                    }
                }
            }

            for (int selfLoopCount = nextSize; selfLoopCount > 0 && selectedCount < maximumRows; selfLoopCount--) {
                for (int stateIndex = 1; stateIndex < stateLimit && selectedCount < maximumRows; stateIndex++) {
                    if (!selected[stateIndex] && selfLoopCounts[stateIndex] == selfLoopCount) {
                        selected[stateIndex] = true;
                        selectedCount++;
                    }
                }
            }

            // Whole-graph pairing remains the default whenever it fits. Partial
            // pairing is deliberately limited to cached entry rows and states
            // with observed self-loops; expanding arbitrary cold rows recreates
            // the cache-footprint regression this cap prevents.
            if (maximumRows >= stateCount) {
                for (int stateIndex = 1; stateIndex < stateLimit; stateIndex++) {
                    if (tables.stateData[stateIndex] != null) {
                        selected[stateIndex] = true;
                    }
                }
            }
            return selected;
        }

        private int selectCachedStartRows(boolean[] selected, StateData[] startStates, int maximumRows, int selectedCount)
        {
            for (StateData startState : startStates) {
                if (startState == null || selectedCount >= maximumRows) {
                    continue;
                }
                int stateIndex = stateIndex(startState.offset());
                if (!selected[stateIndex]) {
                    selected[stateIndex] = true;
                    selectedCount++;
                }
            }
            return selectedCount;
        }

        // How many rows a paired table for `stateLimit` states can hold within `maximumMemory`, at
        // most every state's.
        private int maximumPairedRows(int stateLimit, long maximumMemory)
        {
            long rowMemory = FLAT_PAIRED_ROWS ? pairedRowBytes() : pairedRowMemory();
            int maximumRows = (int) Math.min(stateCount, (maximumMemory - pairedTransitionMemory(stateLimit, 0)) / rowMemory);
            // Aligning the flat table's size can add up to four bytes, less than one row; the object
            // rows' charge is exact.
            if (pairedTransitionMemory(stateLimit, maximumRows) > maximumMemory) {
                maximumRows--;
            }
            return maximumRows;
        }

        // The charge for a paired table with `rowCount` rows for `stateLimit` states: the flat
        // table's two int arrays, without the segment view over the second, as for the heap pointer
        // table, or the object rows counted with eight-byte references.
        private long pairedTransitionMemory(int stateLimit, int rowCount)
        {
            if (FLAT_PAIRED_ROWS) {
                return sizeOfIntArray(stateLimit) + sizeOfIntArray(rowCount * pairedRowLength());
            }
            return pairedOuterMemory(stateLimit) + (rowCount * pairedRowMemory());
        }

        // A paired row: one slot for each pair of byte classes, then the state, which the flat form
        // holds as its offset in `transitions`.
        private int pairedRowLength()
        {
            return (nextSize * nextSize) + 1;
        }

        private int pairedRowBytes()
        {
            return pairedRowLength() * Integer.BYTES;
        }

        private long pairedOuterMemory(int stateLimit)
        {
            return alignToEight(16L + (long) stateLimit * CONSERVATIVE_REFERENCE_BYTES);
        }

        private long pairedRowMemory()
        {
            int pairedClassCount = nextSize * nextSize;
            return alignToEight(16L + ((long) pairedClassCount + 1) * CONSERVATIVE_REFERENCE_BYTES);
        }

        private void clearPairedTransitions()
        {
            CacheTables tables = this.tables;
            if (tables.hasPairedRows()) {
                this.tables = tables.withPairedRows(null, null, null);
            }
            availableStateMemory += pairedTransitionMemory;
            pairedTransitionMemory = 0;
            pairedContentVersion = -1;
        }

        private static long alignToEight(long value)
        {
            return (value + 7) & ~7L;
        }

        StateData analyzeStart(CacheTables tables, Slice context, int textBegin, int textEnd, boolean anchored, boolean runForward)
        {
            int ctxBegin = context.byteArrayOffset();
            int ctxEnd = ctxBegin + context.length();
            return analyzeStart(tables, context.byteArray(), ctxBegin, ctxEnd, textBegin, textEnd, anchored, runForward);
        }

        /// Returns the start state for a search over `[textBegin, textEnd)`, building it when missing.
        /// `tables` is the caller's root; a write lands in the current tables, so an exclusive
        /// caller reloads with `tablesAfterWrite`.
        StateData analyzeStart(CacheTables tables, byte[] bytes, int ctxBegin, int ctxEnd, int textBegin, int textEnd, boolean anchored, boolean runForward)
        {
            // An exclusive search that reset the cache in an earlier step still holds the tables
            // it began with, and must not start in them.
            tables = tablesAfterWrite(tables);
            // The cache slot depends only on the position and the bytes around the start; the
            // empty-width flags are needed only to build a missing start state.
            int dirIndex = runForward ? 1 : 0;
            int anchorIndex = anchored ? 1 : 0;
            StateData existing = (StateData) getStateReference(startCache(tables, bytes, ctxBegin, ctxEnd, textBegin, textEnd, runForward)[dirIndex], anchorIndex);
            // Grown tables share the start caches, so a start state cached since these tables were
            // replaced may lie beyond them.
            if (existing != null && existing.offset() < tables.transitions.length) {
                return existing;
            }

            if (!cacheMutationLock.isHeldByCurrentThread()) {
                throw RETRY_SEARCH;
            }

            int flags = startFlags(bytes, ctxBegin, ctxEnd, textBegin, textEnd, runForward);
            q0.clear();
            addToQueue(q0, anchored ? prog.start() : prog.startUnanchored(), flags);
            StateData start = workQueueToCachedState(q0, flags);
            if (start == null) {
                resetCache();
                start = workQueueToCachedState(q0, flags);
                if (start == null) {
                    throw START_STATE_ALLOCATION_FAILED;
                }
            }
            // The reset above may have published new tables; store the start state in the current ones.
            setStateReference(startCache(this.tables, bytes, ctxBegin, ctxEnd, textBegin, textEnd, runForward)[dirIndex], anchorIndex, start);
            return start;
        }

        private StateData[][] startCache(CacheTables tables, byte[] bytes, int ctxBegin, int ctxEnd, int textBegin, int textEnd, boolean runForward)
        {
            if (runForward) {
                if (textBegin == ctxBegin) {
                    return tables.startBeginText;
                }
                if (bytes[textBegin - 1] == '\n') {
                    return textBegin < ctxEnd && trinoLineFlag != 0 ? tables.startTrinoLine : tables.startBeginLine;
                }
                if (isWordChar(bytes[textBegin - 1] & 0xFF)) {
                    return tables.startAfterWord;
                }
                if (bytes[textBegin - 1] == '\r' && hasCrlfLineAssertions) {
                    return tables.startAfterCarriageReturn;
                }
                if (bytes[textBegin - 1] < 0 && quitsOnNonAscii) {
                    return tables.startAfterNonAscii;
                }
                return tables.startAfterNonWord;
            }
            if (textEnd == ctxEnd) {
                return tables.startBeginText;
            }
            if (bytes[textEnd] == '\n') {
                return tables.startBeginLine;
            }
            if (isWordChar(bytes[textEnd] & 0xFF)) {
                return tables.startAfterWord;
            }
            if (bytes[textEnd] == '\r' && hasCrlfLineAssertions) {
                return tables.startAfterCarriageReturn;
            }
            if (bytes[textEnd] < 0 && quitsOnNonAscii) {
                return tables.startAfterNonAscii;
            }
            return tables.startAfterNonWord;
        }

        // The empty-width flags at a search start; keep these branches aligned with `startCache`.
        private int startFlags(byte[] bytes, int ctxBegin, int ctxEnd, int textBegin, int textEnd, boolean runForward)
        {
            if (runForward) {
                if (textBegin == ctxBegin) {
                    return EmptyOp.EMPTY_BEGIN_TEXT | EmptyOp.EMPTY_BEGIN_LINE | trinoLineFlag | crlfStartFlags(-1);
                }
                if (bytes[textBegin - 1] == '\n') {
                    int flags = EmptyOp.EMPTY_BEGIN_LINE | crlfStartFlags('\n');
                    return textBegin < ctxEnd && trinoLineFlag != 0 ? flags | trinoLineFlag : flags;
                }
                if (isWordChar(bytes[textBegin - 1] & 0xFF)) {
                    return FLAG_LAST_WORD;
                }
                if (bytes[textBegin - 1] == '\r' && hasCrlfLineAssertions) {
                    return crlfStartFlags('\r');
                }
                if (bytes[textBegin - 1] < 0 && quitsOnNonAscii) {
                    return FLAG_LAST_NON_ASCII;
                }
                return 0;
            }
            if (textEnd == ctxEnd) {
                return EmptyOp.EMPTY_BEGIN_TEXT | EmptyOp.EMPTY_BEGIN_LINE | crlfStartFlags(-1);
            }
            if (bytes[textEnd] == '\n') {
                return EmptyOp.EMPTY_BEGIN_LINE | crlfStartFlags('\n');
            }
            if (isWordChar(bytes[textEnd] & 0xFF)) {
                return FLAG_LAST_WORD;
            }
            if (bytes[textEnd] == '\r' && hasCrlfLineAssertions) {
                return crlfStartFlags('\r');
            }
            if (bytes[textEnd] < 0 && quitsOnNonAscii) {
                return FLAG_LAST_NON_ASCII;
            }
            return 0;
        }

        // The Rust CRLF flags for a search that starts after `previousByte`, or at the start of the
        // text when it is -1. A line starts after a pair's second byte. After its first byte, the
        // state records that byte, and the transition over the next byte decides the line start.
        private int crlfStartFlags(int previousByte)
        {
            if (!hasCrlfLineAssertions) {
                return 0;
            }
            if (previousByte == -1 || previousByte == crlfSecondByte) {
                return FLAG_CRLF_BEGIN_LINE;
            }
            return previousByte == crlfFirstByte ? FLAG_HALF_CRLF : 0;
        }

        // The row of the state at `stateOffset`, read racily. A row the writer has not yet made
        // visible, or an offset beyond these tables, read from a row or start cache they share with
        // grown tables, retries.
        Object[] stateReferences(CacheTables tables, int stateOffset)
        {
            int stateIndex = stateIndex(stateOffset);
            Object[][] stateRefArrays = tables.stateRefArrays;
            if (stateIndex >= stateRefArrays.length) {
                throw RETRY_SEARCH;
            }
            Object[] stateReferences = stateRefArrays[stateIndex];
            if (stateReferences == null) {
                throw RETRY_SEARCH;
            }
            return stateReferences;
        }

        // The metadata of the state at an offset read from `tables`, read racily. A state the
        // writer has not yet made visible, or an offset beyond these tables, retries.
        private StateData stateData(CacheTables tables, int stateOffset)
        {
            int stateIndex = stateIndex(stateOffset);
            StateData[] stateData = tables.stateData;
            if (stateIndex >= stateData.length) {
                throw RETRY_SEARCH;
            }
            StateData state = stateData[stateIndex];
            if (state == null) {
                throw RETRY_SEARCH;
            }
            return state;
        }

        /// Returns the row of the state at `stateOffset`, an untagged offset of a real state,
        /// never a sentinel.
        int stateIndex(int stateOffset)
        {
            return stateIndex(stateOffset, stateIndexShift, stateIndexMultiplier);
        }

        // Every state offset is an exact multiple of nextSize, so the shift drops only zero bits
        // and multiplying by the inverse of nextSize's odd part modulo 2^32 gives the exact
        // quotient. This keeps a signed divide off the per-byte sidecar step of the search loops.
        static int stateIndex(int stateOffset, int shift, int multiplier)
        {
            return (stateOffset >>> shift) * multiplier;
        }

        /// Returns the inverse modulo 2^32 of the odd part of `nextSize`.
        static int stateIndexMultiplier(int nextSize)
        {
            int oddPart = nextSize >>> Integer.numberOfTrailingZeros(nextSize);
            // An odd number is its own inverse to three bits, and each Newton step doubles the
            // correct bits: 3, 6, 12, 24, 48.
            int inverse = oddPart;
            for (int step = 0; step < 4; step++) {
                inverse *= 2 - (oddPart * inverse);
            }
            return inverse;
        }

        private int byteMap(int c)
        {
            if (c == BYTE_END_TEXT) {
                return bytemapRange;
            }
            return bytemap[c] & 0xFF;
        }

        /// Compute and store the transition for the given state and byte.
        /// Returns the transition value (with possible T_MATCH_BIT), T_DEAD, or T_FULL_MATCH.
        /// Returns Integer.MIN_VALUE if allocation fails (cache exhaustion).
        /// `tables` is the caller's root; a write lands in the current tables, so an exclusive
        /// caller reloads with `tablesAfterWrite`.
        int computeTransition(CacheTables tables, int stateOffset, int c)
        {
            int cls = byteMap(c);
            int existing = getTransition(tables.transitions, stateOffset + cls);
            if (existing != T_UNCOMPUTED) {
                return existing;
            }

            // A quit reads only the source state, so it is reported before taking exclusive
            // access and is never cached; every later search over the byte reaches it again.
            if (quitsOnNonAscii && quits(stateData(tables, stateOffset).flag(), c)) {
                throw QUIT_SEARCH;
            }
            if (!cacheMutationLock.isHeldByCurrentThread()) {
                throw RETRY_SEARCH;
            }
            return computeTransitionLocked(stateOffset, c, cls);
        }

        /// Returns whether the transition from a state with `flag` over `c` may evaluate a word
        /// assertion that decodes the text next to a byte outside ASCII, where either the byte
        /// before the state's position or `c` is outside ASCII. `c` is the next byte; at the end
        /// of a range that ends before its context, it is the byte after the range. Any assertion
        /// the state needs counts, because a line anchor that holds can lead to a word assertion
        /// that is not yet among the state's needs. Every malformed UTF-8 sequence and every
        /// position inside a character is next to a byte outside ASCII, so the DFA never evaluates
        /// a decoding rule. A byte class shares one cached transition, so this relies on the byte
        /// map never putting a byte outside ASCII in a class with an ASCII byte.
        private static boolean quits(int flag, int c)
        {
            return (flag >>> FLAG_NEED_SHIFT) != 0 && ((flag & FLAG_LAST_NON_ASCII) != 0 || (c >= 0x80 && c != BYTE_END_TEXT));
        }

        private int computeTransitionLocked(int stateOffset, int c, int cls)
        {
            // An exclusive search may find a transition uncomputed in older tables that the current
            // tables already hold; slots are written once, so the current value is final.
            CacheTables tables = this.tables;
            int existing = getTransition(tables.transitions, stateOffset + cls);
            if (existing != T_UNCOMPUTED) {
                return existing;
            }
            StateData state = tables.stateData[stateIndex(stateOffset)];

            // Convert state into work queue
            stateToWorkQueue(state, q0);

            int needflag = state.flag() >>> FLAG_NEED_SHIFT;
            int beforeflag = state.flag() & FLAG_EMPTY_MASK;
            int oldbeforeflag = beforeflag;
            int afterflag = 0;

            // A forward line boundary becomes a Trino multiline boundary only when another byte
            // exists. This excludes the boundary after a terminal LF.
            if (!prog.reversed() && c != BYTE_END_TEXT && trinoLineFlag != 0 &&
                    (beforeflag & EmptyOp.EMPTY_BEGIN_LINE) != 0) {
                beforeflag |= trinoLineFlag;
            }

            // In a reversed program the Trino end-line assertion represents the original ^.
            // Crossing LF establishes it except at the original context end, where the LF is
            // terminal. Reaching reversed end-of-text establishes the original context beginning.
            if (c == '\n') {
                beforeflag |= EmptyOp.EMPTY_END_LINE;
                if (prog.reversed() && (beforeflag & EmptyOp.EMPTY_BEGIN_TEXT) == 0) {
                    beforeflag |= trinoLineFlag;
                }
                afterflag |= EmptyOp.EMPTY_BEGIN_LINE;
            }
            if (c == BYTE_END_TEXT) {
                beforeflag |= EmptyOp.EMPTY_END_LINE | EmptyOp.EMPTY_END_TEXT;
                if (prog.reversed()) {
                    beforeflag |= trinoLineFlag;
                }
            }

            boolean isLastWord = (state.flag() & FLAG_LAST_WORD) != 0;
            boolean isWord = c != BYTE_END_TEXT && isWordChar(c);
            if (isWord == isLastWord) {
                beforeflag |= EmptyOp.EMPTY_NO_WORD_BOUNDARY;
            }
            else {
                beforeflag |= EmptyOp.EMPTY_WORD_BOUNDARY;
            }

            // A Rust CRLF line ends before a first byte, before a second byte that does not follow
            // a first byte, and at the end of the text. It starts after a second byte, after a
            // first byte that a second byte does not follow, and at the start of the text, which
            // the start state carries.
            if (hasCrlfLineAssertions) {
                boolean halfCrlf = (state.flag() & FLAG_HALF_CRLF) != 0;
                if (c == crlfFirstByte || c == BYTE_END_TEXT || (c == crlfSecondByte && !halfCrlf)) {
                    beforeflag |= FLAG_CRLF_END_LINE;
                }
                if (halfCrlf && c != crlfSecondByte) {
                    beforeflag |= FLAG_CRLF_BEGIN_LINE;
                }
                if (c == crlfSecondByte) {
                    afterflag |= FLAG_CRLF_BEGIN_LINE;
                }
            }
            if (hasRustWordAssertions) {
                beforeflag |= isLastWord ? FLAG_WORD_BEFORE : FLAG_NON_WORD_BEFORE;
                beforeflag |= isWord ? FLAG_WORD_AFTER : FLAG_NON_WORD_AFTER;
            }

            if ((beforeflag & ~oldbeforeflag & needflag) != 0) {
                runWorkQueueOnEmptyString(q0, q1, beforeflag);
                swapWorkQueue();
            }

            boolean ismatch = false;
            ismatch = runWorkQueueOnByte(q0, q1, c, afterflag, ismatch);
            swapWorkQueue();

            int flag = afterflag;
            if (ismatch) {
                flag |= FLAG_MATCH;
            }
            if (isWord) {
                flag |= FLAG_LAST_WORD;
            }
            if (hasCrlfLineAssertions && c == crlfFirstByte) {
                flag |= FLAG_HALF_CRLF;
            }
            if (quitsOnNonAscii && c >= 0x80 && c != BYTE_END_TEXT) {
                flag |= FLAG_LAST_NON_ASCII;
            }

            StateData ns;
            if (ismatch && kind == Kind.MANY_MATCH) {
                ns = workQueueToCachedState(q0, q1, flag);
            }
            else {
                ns = workQueueToCachedState(q0, null, flag);
            }

            if (ns == null) {
                // OOM - leave transition as T_UNCOMPUTED
                return Integer.MIN_VALUE;
            }

            int transValue = ns.offset();
            if (transValue > 0 && (ns.flag() & FLAG_MATCH) != 0) {
                transValue |= T_MATCH_BIT;
            }
            // Allocating the target state may have published grown tables.
            tables = this.tables;
            setTransition(tables.transitions, stateOffset + cls, transValue);

            // Populate Object[] ref for normal non-match transitions (inner loop fast path).
            // Sentinels and match states are left as null — inner loop breaks on null.
            if (transValue > 0 && (transValue & T_MATCH_BIT) == 0) {
                Object[][] stateRefArrays = tables.stateRefArrays;
                setStateReference(stateRefArrays[stateIndex(stateOffset)], cls, stateRefArrays[stateIndex(transValue)]);
            }
            AbsolutePointerTransitions absolutePointers = tables.absolutePointerTransitions;
            if (absolutePointers != null &&
                    stateOffset + cls < absolutePointers.entryCapacity() &&
                    setAbsolutePointerTransition(absolutePointers, stateOffset + cls, transValue, nextSize)) {
                absolutePointerTransitionCount++;
            }
            cacheContentVersion++;

            return transValue;
        }

        private void swapWorkQueue()
        {
            WorkQueue temporary = q0;
            q0 = q1;
            q1 = temporary;
        }

        private void stateToWorkQueue(StateData s, WorkQueue q)
        {
            q.clear();
            int[] inst = s.inst();
            for (int i = 0; i < inst.length; i++) {
                int instId = inst[i];
                if (instId == Mark) {
                    q.mark();
                }
                else if (instId == MatchSep) {
                    break;
                }
                else {
                    addToQueue(q, instId, s.flag() & FLAG_EMPTY_MASK);
                }
            }
        }

        private StateData workQueueToCachedState(WorkQueue q, WorkQueue mq, int flag)
        {
            int qsize = q.size();
            int mqSize = (mq != null) ? mq.size() : 0;
            int[] inst = new int[qsize + mqSize + 1];
            int n = 0;

            int needflags = 0;
            boolean sawMatch = false;
            boolean sawMark = false;
            boolean isFirstEntry = true;

            for (int it = 0; it < qsize; it++) {
                int id = q.denseAt(it);

                if (sawMatch && (kind == Kind.FIRST_MATCH || q.isMark(id))) {
                    break;
                }

                if (q.isMark(id)) {
                    if (n > 0 && inst[n - 1] != Mark) {
                        sawMark = true;
                        inst[n++] = Mark;
                    }
                    continue;
                }

                Prog.Inst instruction = prog.inst(id);
                InstOp op = instruction.opcode();

                if (op == InstOp.ALT_MATCH &&
                        kind != Kind.MANY_MATCH &&
                        (kind != Kind.FIRST_MATCH || (isFirstEntry && instruction.greedy(prog))) &&
                        (kind != Kind.LONGEST_MATCH || !sawMark) &&
                        (flag & FLAG_MATCH) != 0) {
                    return FULL_MATCH_DATA;
                }

                if (op == InstOp.EMPTY_WIDTH) {
                    needflags |= dfaEmptyFlags(instruction.empty());
                }

                if (id > 0 && prog.inst(id - 1).last()) {
                    inst[n++] = id;
                }

                if (op == InstOp.MATCH && !prog.anchorEnd()) {
                    sawMatch = true;
                }

                isFirstEntry = false;
            }

            if (n > 0 && inst[n - 1] == Mark) {
                n--;
            }

            if (needflags == 0) {
                flag &= FLAG_MATCH;
            }

            if (n == 0 && flag == 0) {
                return DEAD_DATA;
            }

            if (kind == Kind.LONGEST_MATCH) {
                int start = 0;
                for (int i = 0; i <= n; i++) {
                    if (i == n || inst[i] == Mark) {
                        if (i > start) {
                            Arrays.sort(inst, start, i);
                        }
                        start = i + 1;
                    }
                }
            }

            if (kind == Kind.MANY_MATCH) {
                Arrays.sort(inst, 0, n);
            }

            if (mq != null) {
                inst[n++] = MatchSep;
                for (int it = 0; it < mq.size(); it++) {
                    int id = mq.denseAt(it);
                    if (!mq.isMark(id)) {
                        Prog.Inst instruction = prog.inst(id);
                        if (instruction.opcode() == InstOp.MATCH) {
                            inst[n++] = instruction.matchId();
                        }
                    }
                }
            }

            flag |= (needflags << FLAG_NEED_SHIFT);

            int[] keyInst = Arrays.copyOf(inst, n);
            StateKey key = new StateKey(keyInst, flag);

            StateData existing = cache.get(key);
            if (existing != null) {
                return existing;
            }

            return allocateCachedState(key, keyInst, flag);
        }

        private StateData workQueueToCachedState(WorkQueue q, int flag)
        {
            return workQueueToCachedState(q, null, flag);
        }

        @SuppressFBWarnings(
                value = "UL_UNRELEASED_LOCK",
                justification = "Successful exclusive admission deliberately transfers lock ownership to endSearch")
        CacheTables beginSearch(boolean exclusiveSearch)
        {
            if (exclusiveSearch) {
                cacheMutationLock.lock();
                boolean admitted = false;
                try {
                    failForTesting(TestingFailurePoint.EXCLUSIVE_ADMISSION);
                    admitted = true;
                    return tables;
                }
                finally {
                    if (!admitted) {
                        cacheMutationLock.unlock();
                    }
                }
            }

            // A reader takes no lock and waits for no writer; its only stores are racy hints. Writers
            // never clear or recycle the tables it reads; they only fill empty slots, which the reader
            // checks.
            return tables;
        }

        void endSearch(boolean exclusiveSearch)
        {
            if (!exclusiveSearch) {
                return;
            }
            try {
                failForTesting(TestingFailurePoint.EXCLUSIVE_TEARDOWN);
                if (pairedTransitionsRequested &&
                        !pairedTransitionsDisabled &&
                        pairedContentVersion != cacheContentVersion) {
                    rebuildPairedTransitions();
                    pairedContentVersion = cacheContentVersion;
                }
                allocateAbsolutePointerTransitions();
            }
            finally {
                cacheVersion++;
                cacheMutationLock.unlock();
            }
        }

        long cacheVersion()
        {
            return cacheVersion;
        }

        void cancelExclusiveSearch()
        {
            cacheMutationLock.unlock();
        }

        void failNextForTesting(TestingFailurePoint failurePoint)
        {
            if (testingFailurePoint != null) {
                throw new IllegalStateException("a testing failure is already configured");
            }
            testingFailurePoint = requireNonNull(failurePoint, "failurePoint is null");
            testingSnapshotBeforeFailure = null;
        }

        void failStateAllocationsForTesting(int failureCount)
        {
            if (failureCount <= 0) {
                throw new IllegalArgumentException("failureCount must be positive");
            }
            testingStateAllocationFailures = failureCount;
        }

        FailureAtomicitySnapshot testingSnapshotBeforeFailure()
        {
            return testingSnapshotBeforeFailure;
        }

        FailureAtomicitySnapshot failureAtomicitySnapshot()
        {
            CacheTables tables = this.tables;
            AbsolutePointerTransitions absolutePointers = tables.absolutePointerTransitions;
            return new FailureAtomicitySnapshot(
                    tables.transitions.length,
                    tables.stateData.length,
                    tables.stateRefArrays.length,
                    tables.selfLoopExitByteSets.length,
                    nextFreeOffset,
                    stateCount,
                    cache.size(),
                    cache.capacity(),
                    cache.storage(),
                    cacheContentVersion,
                    availableStateMemory,
                    retainedStateMemory,
                    FLAT_PAIRED_ROWS ? tables.flatPairedRows : tables.pairedStateRefArrays,
                    pairedTransitionRowCount(),
                    pairedTransitionMemory,
                    pairedContentVersion,
                    absolutePointers == null ? null : absolutePointers.segment(),
                    absolutePointers == null ? 0 : absolutePointers.entryCapacity(),
                    absolutePointers == null ? 0 : absolutePointers.allocatedBytes(),
                    absolutePointerTransitionCount);
        }

        boolean cacheMutationLockedForTesting()
        {
            return cacheMutationLock.isLocked();
        }

        private void beginTestingFailurePoint(TestingFailurePoint failurePoint)
        {
            if (testingFailurePoint == failurePoint && testingSnapshotBeforeFailure == null) {
                testingSnapshotBeforeFailure = failureAtomicitySnapshot();
            }
        }

        private void beginStateAllocationForTesting()
        {
            if (testingFailurePoint == null) {
                return;
            }
            if (switch (testingFailurePoint) {
                case POINTER_TABLE_GROWTH,
                     TRANSITION_ARRAY_GROWTH,
                     STATE_DATA_ARRAY_GROWTH,
                     STATE_REFERENCE_ARRAY_GROWTH,
                     SELF_LOOP_ARRAY_GROWTH,
                     STATE_REFERENCE_ROW_ALLOCATION,
                     STATE_CACHE_INSERTION -> true;
                case EXCLUSIVE_ADMISSION, EXCLUSIVE_TEARDOWN, POINTER_TABLE_GROWTH_ALLOCATION, POINTER_TABLE_RESET -> false;
            }) {
                testingSnapshotBeforeFailure = failureAtomicitySnapshot();
            }
        }

        private void endTestingFailurePoint(TestingFailurePoint failurePoint)
        {
            failForTesting(failurePoint);
        }

        private void failForTesting(TestingFailurePoint failurePoint)
        {
            if (consumeTestingFailurePoint(failurePoint)) {
                throw TESTING_FAILURE;
            }
        }

        private boolean consumeTestingFailurePoint(TestingFailurePoint failurePoint)
        {
            if (testingFailurePoint != failurePoint) {
                return false;
            }
            testingFailurePoint = null;
            return true;
        }

        RestoredStates resetAndRestore(StateSaver start, StateSaver state)
        {
            if (!cacheMutationLock.isHeldByCurrentThread()) {
                throw new IllegalStateException("DFA cache reset requires an exclusive search");
            }
            resetCache();
            int startOffset = start.restoreLocked(this);
            int stateOffset = state.restoreLocked(this);
            return new RestoredStates(startOffset, stateOffset);
        }

        private StateData restoreStateLocked(int[] inst, int flag)
        {
            StateKey key = new StateKey(inst, flag);
            StateData existing = cache.get(key);
            if (existing != null) {
                return existing;
            }

            return allocateCachedState(key, inst, flag);
        }

        private long stateObjectMemory(int instructionCount)
        {
            return stateObjectMemory(nextSize, instructionCount);
        }

        private static long stateObjectMemory(int nextSize, int instructionCount)
        {
            return sizeOfObjectArray(nextSize + 1) +
                    sizeOfIntArray(instructionCount) +
                    STATE_DATA_BYTES +
                    STATE_KEY_BYTES;
        }

        private long stateBackingMemory()
        {
            CacheTables tables = this.tables;
            return sizeOfIntArray(tables.transitions.length) +
                    sizeOfObjectArray(tables.stateData.length) +
                    sizeOfObjectArray(tables.stateRefArrays.length) +
                    sizeOfLongArray(tables.selfLoopExitByteSets.length) +
                    optionalStartStateCacheMemory(tables) +
                    STATE_CACHE_BYTES +
                    cache.tableBytes();
        }

        private static long optionalStartStateCacheMemory(CacheTables tables)
        {
            return startStateCacheMemory(tables.startTrinoLine) + startStateCacheMemory(tables.startAfterCarriageReturn) + startStateCacheMemory(tables.startAfterNonAscii);
        }

        private static long startStateCacheMemory(StateData[][] startCache)
        {
            if (startCache == null) {
                return 0;
            }
            long memory = sizeOfObjectArray(startCache.length);
            for (StateData[] row : startCache) {
                memory += sizeOfObjectArray(row.length);
            }
            return memory;
        }

        // Publishes empty tables at the initial capacity. Searches still reading the superseded
        // tables keep them, unchanged, until they finish; the superseded tables are no longer
        // charged. A pointer table moves to a new segment of the same size, or is withheld when
        // superseded native segments fill their cap or the segment cannot be allocated; a table a
        // search gave back is dropped.
        private void resetCache()
        {
            // Every allocation precedes the first write to the DFA, so a failure that propagates
            // leaves the cache as it was; a failed pointer-table allocation withholds that table.
            // The pointer segment is allocated after the Java tables; only small objects (new
            // `CacheTables` and, for a native table, the superseded reference) follow it, and an
            // error in them abandons the segment uncounted.
            CacheTables resetTables = newCacheTables(initialStateCapacity);
            AbsolutePointerTransitions supersededPointers = tables.absolutePointerTransitions;
            AbsolutePointerTransitions absolutePointers = null;
            boolean allocationFailed = false;
            if (supersededPointers != null && !absolutePointerTransitionsReleased) {
                long allocatedBytes = supersededPointers.allocatedBytes();
                if (consumeTestingFailurePoint(TestingFailurePoint.POINTER_TABLE_RESET)) {
                    allocationFailed = true;
                }
                else if (supersededAbsolutePointerMemoryAllows(allocatedBytes, allocatedBytes)) {
                    absolutePointers = allocateAbsolutePointerTable(allocatedBytes, supersededPointers.entryCapacity());
                    allocationFailed = absolutePointers == null;
                }
            }
            if (absolutePointers != null) {
                resetTables = resetTables.withAbsolutePointerTransitions(absolutePointers);
            }
            if (supersededPointers != null) {
                supersedeAbsolutePointerTransitions(supersededPointers);
            }

            // The reset tables have no paired rows; the available memory is recomputed below.
            pairedTransitionMemory = 0;
            pairedContentVersion = -1;
            pairedTransitionsRejected = false;
            shortPairedSearchCount = 0;
            shortAbsolutePointerSearchCount = 0;
            countSelfLoopUnproductiveCount = 0;

            cache.clear();

            // A failed allocation, including this reset's, is retried only after a later reset.
            absolutePointerTransitionAllocationFailed = allocationFailed;
            if (supersededPointers != null) {
                absolutePointerTransitionsWithheld = absolutePointers == null && !absolutePointerTransitionsReleased;
            }
            pairedTransitionsDisabled = absolutePointers != null || absolutePointerTransitionsWithheld || absolutePointerTransitionsReleased;
            tables = resetTables;
            absolutePointerTransitionCount = 0;
            nextFreeOffset = nextSize;
            statesAtLastReset = stateCount;
            stateCount = 0;
            cacheContentVersion++;
            retainedStateMemory = stateBackingMemory();
            availableStateMemory = stateBudget -
                    retainedStateMemory -
                    fixedDistanceByteCandidateMemory -
                    absolutePointerTransitionMemory();
            if (fixedDistanceByteCandidates == RETRY_FIXED_DISTANCE_BYTE_CANDIDATES_AFTER_RESET) {
                fixedDistanceByteCandidates = null;
                fixedDistanceSmallByteSet = 0;
            }

            resetCount++;
        }

        // Called under the cache mutation lock by a search or an exclusive count that would give up
        // for resetting too often. A DFA that holds a pointer table, or has one withheld, gives the
        // table back instead, so later resets leave room for the states object rows hold, and the
        // search goes on; it gives up only if it resets that often again. Whether a native table is
        // held or withheld depends on when the collector finds superseded tables, and either form is
        // withheld when its allocation fails, so both give it back.
        // A DFA that never requested a table, or could not size one, has nothing to give back.
        // Returns whether the search goes on.
        boolean releaseAbsolutePointerTransitionsForSlowReset()
        {
            if (absolutePointerTransitionsReleased ||
                    (tables.absolutePointerTransitions == null && !absolutePointerTransitionsWithheld)) {
                return false;
            }
            absolutePointerTransitionsReleased = true;
            absolutePointerTransitionsUnavailable = true;
            absolutePointerTransitionsWithheld = false;
            releasedAbsolutePointerResetCount = resetCount + 1;
            return true;
        }

        // Whether a count that began at `initialResetCount` has seen the DFA reset too often. The
        // allowance counts only the resets after the one that drops a pointer table given back, by
        // a search or a count: the table stays in the current tables until that reset, and resets
        // with it come sooner because it fits fewer states. An exclusive count gives the table back
        // instead, and gives up only when it has nothing to give back.
        boolean countResetsTooOften(int initialResetCount, boolean exclusiveSearch)
        {
            return resetCount - Math.max(initialResetCount, releasedAbsolutePointerResetCount) >= 2 &&
                    (!exclusiveSearch || !releaseAbsolutePointerTransitionsForSlowReset());
        }

        // Whether a lock-free count that saw two resets hands them to the lock: while the DFA has
        // held a pointer table, now in its tables or withheld, and not given it back, or when
        // another search gave it back after the count judged its resets, leaving fewer than two
        // resets after the one that drops it. It reads the release once, and only through that
        // reset, so a release the count sees in part, or after its judgement, costs one locked
        // count rather than a give-up for good.
        boolean countHandsResetsToLock(int initialResetCount)
        {
            int releasedResetCount = releasedAbsolutePointerResetCount;
            return releasedResetCount == 0
                    ? absolutePointerTransitionsPublished
                    : resetCount - Math.max(initialResetCount, releasedResetCount) < 2;
        }

        // Read after `resetCount()`, which publishes it with the reset that drops the table.
        int releasedAbsolutePointerResetCount()
        {
            return releasedAbsolutePointerResetCount;
        }

        public void resetCacheExternal()
        {
            cacheMutationLock.lock();
            try {
                resetCache();
            }
            finally {
                cacheVersion++;
                cacheMutationLock.unlock();
            }
        }

        /// Adds id to the work queue, following empty arrows according to flag.
        /// Like upstream dfa.cc:837-919.
        private void addToQueue(WorkQueue q, int id, int flag)
        {
            int stackPointer = 0;
            stack[stackPointer++] = id;

            while (stackPointer > 0) {
                id = stack[--stackPointer];

                if (id == Mark) {
                    q.mark();
                    continue;
                }

                while (true) {
                    if (id == 0) {
                        break;
                    }
                    if (q.contains(id)) {
                        break;
                    }
                    q.insertNew(id);

                    Prog.Inst instruction = prog.inst(id);
                    InstOp op = instruction.opcode();
                    switch (op) {
                        case BYTE_RANGE, MATCH -> {
                            if (!instruction.last()) {
                                id = id + 1;
                                continue;
                            }
                        }
                        case CAPTURE, NOP -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = id + 1;
                            }
                            if (op == InstOp.NOP && q.maxmark() > 0 &&
                                    id == prog.startUnanchored() && id != prog.start()) {
                                stack[stackPointer++] = Mark;
                            }
                            id = instruction.out();
                            continue;
                        }
                        case EMPTY_WIDTH -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = id + 1;
                            }
                            if ((dfaEmptyFlags(instruction.empty()) & ~flag) != 0) {
                                break;
                            }
                            id = instruction.out();
                            continue;
                        }
                        case ALT -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = id + 1;
                            }
                            stack[stackPointer++] = instruction.out1();
                            id = instruction.out();
                            continue;
                        }
                        case ALT_MATCH -> {
                            id = id + 1;
                            continue;
                        }
                        case FAIL -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = id + 1;
                            }
                        }
                    }
                    break;
                }
            }
        }

        private void runWorkQueueOnEmptyString(WorkQueue oldq, WorkQueue newq, int flag)
        {
            newq.clear();
            for (int it = 0; it < oldq.size(); it++) {
                int id = oldq.denseAt(it);
                if (oldq.isMark(id)) {
                    addToQueue(newq, Mark, flag);
                }
                else {
                    addToQueue(newq, id, flag);
                }
            }
        }

        private boolean runWorkQueueOnByte(WorkQueue oldq, WorkQueue newq, int c, int flag, boolean ismatch)
        {
            newq.clear();
            for (int it = 0; it < oldq.size(); it++) {
                int id = oldq.denseAt(it);

                if (oldq.isMark(id)) {
                    if (ismatch) {
                        return ismatch;
                    }
                    newq.mark();
                    continue;
                }

                Prog.Inst instruction = prog.inst(id);
                switch (instruction.opcode()) {
                    case BYTE_RANGE -> {
                        if (!instruction.matches(c)) {
                            break;
                        }
                        addToQueue(newq, instruction.out(), flag);
                        if (instruction.hint() != 0) {
                            it += instruction.hint() - 1;
                        }
                        else {
                            Prog.Inst j = instruction;
                            int jid = id;
                            while (!j.last()) {
                                jid++;
                                j = prog.inst(jid);
                            }
                            it += jid - id;
                        }
                    }
                    case MATCH -> {
                        if (prog.anchorEnd() && c != BYTE_END_TEXT &&
                                kind != Kind.MANY_MATCH) {
                            break;
                        }
                        ismatch = true;
                        if (kind == Kind.FIRST_MATCH) {
                            return true;
                        }
                    }
                    default -> {}
                }
            }
            return ismatch;
        }

        // Starts a boolean search's judgement as a search does, for tests that replay searches in
        // flight together with judgeBooleanSearchForTesting. Production calls the private method,
        // which the JIT compiler binds statically. These two come last in the class, so their
        // constant pool entries follow every other method's.
        int startBooleanSearchForTesting()
        {
            return startBooleanSearch();
        }

        // Judges a boolean search started by startBooleanSearchForTesting, as a search does.
        void judgeBooleanSearchForTesting(int resetsBefore, long result, int searchLength)
        {
            judgeBooleanSearch(resetsBefore, result, searchLength);
        }
    }
}
