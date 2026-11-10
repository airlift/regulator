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

import java.util.Arrays;

import static java.util.Objects.requireNonNull;

final class Nfa
{
    private Nfa() {}

    private static final int INITIAL_THREAD_CAPACITY = 16;
    // Bytes the capture search's objects take, with 16-byte headers and 8-byte references.
    private static final long WORKSPACE_BYTES = 144;
    private static final long NFA_BYTES = 160;
    private static final long QUEUE_BYTES = 48;
    // The Slice view of the context that a region search passes.
    private static final long SLICE_BYTES = 48;

    // PERFORMANCE-SENSITIVE HOT LOOPS: queue representation, loop boundaries, and capture
    // handling have measured effects. Do not perform readability-only refactors in the search
    // implementations below without direct path tests and focused Intel and Graviton benchmarks.

    /// Searches for a match in `text`.
    ///
    /// This is a faithful port of upstream `re2/nfa.cc` and therefore:
    /// - runs in time linear in the length of the input (no catastrophic backtracking)
    /// - tracks capture groups (submatches)
    /// - notices a match only once it is one byte past it
    ///
    /// If `anchored` is true, only considers matches starting at the beginning of `text`.
    /// Otherwise, finds the leftmost match at or after the beginning of `text`.
    ///
    /// `matchKind` selects leftmost-first, leftmost-longest, or full-match behavior.
    ///
    /// On success, writes up to `submatchCount = submatch.length / 2` pairs of offsets into
    /// `submatch`, in bytes, relative to the beginning of `text`. The 0th pair is the
    /// entire match. Unmatched groups are reported as `(-1,-1)`.
    public static boolean search(Prog prog, Slice context, int start, int end, boolean anchored, Prog.MatchKind matchKind, int[] submatch)
    {
        return search(prog, context, start, end, anchored, matchKind, submatch, null);
    }

    static boolean search(
            Prog prog,
            Slice context,
            int start,
            int end,
            boolean anchored,
            Prog.MatchKind matchKind,
            int[] submatch,
            Workspace workspace)
    {
        return search(prog, context, start, end, anchored, matchKind, submatch, workspace, false);
    }

    /// Searches as [#search(Prog,Slice,int,int,boolean,Prog.MatchKind,int\[\])] does. When
    /// `carriageReturnBeforeContext` is set, a CR precedes `context`, so the Java $
    /// assertions do not match at the context start when the context begins with an LF, as the
    /// JDK's $ does not at the start of a region that splits a CR LF.
    static boolean search(
            Prog prog,
            Slice context,
            int start,
            int end,
            boolean anchored,
            Prog.MatchKind matchKind,
            int[] submatch,
            Workspace workspace,
            boolean carriageReturnBeforeContext)
    {
        return search(prog, context, start, end, anchored, matchKind, submatch, workspace, carriageReturnBeforeContext, 0);
    }

    private static boolean search(
            Prog prog,
            Slice context,
            int start,
            int end,
            boolean anchored,
            Prog.MatchKind matchKind,
            int[] submatch,
            Workspace workspace,
            boolean carriageReturnBeforeContext,
            int passSlotCountForTesting)
    {
        requireNonNull(prog, "prog is null");
        requireNonNull(context, "context is null");
        requireNonNull(matchKind, "matchKind is null");

        if (submatch != null && (submatch.length % 2) != 0) {
            throw new IllegalArgumentException("submatch length must be even: " + submatch.length);
        }

        if (prog.start() == 0) {
            return false;
        }

        int ctxBegin = context.byteArrayOffset();
        int ctxEnd = ctxBegin + context.length();
        if (start < 0 || end < start || end > context.length()) {
            return false;
        }
        int textBegin = ctxBegin + start;
        int textEnd = ctxBegin + end;

        // Enforce anchor constraints against the supplied context/text slices.
        if (prog.anchorStart() && ctxBegin != textBegin) {
            return false;
        }
        if (prog.anchorEnd() && ctxEnd != textEnd) {
            return false;
        }

        anchored |= prog.anchorStart() || matchKind == Prog.MatchKind.FULL_MATCH;
        boolean longest = matchKind != Prog.MatchKind.FIRST_MATCH;
        boolean endMatch = prog.anchorEnd() || matchKind == Prog.MatchKind.FULL_MATCH;

        byte[] bytes = context.byteArray();
        int submatchCount = (submatch == null) ? 0 : (submatch.length / 2);
        if (submatchCount == 0) {
            NoSubmatchNfaImpl nfa = new NoSubmatchNfaImpl(
                    bytes,
                    prog,
                    prog.start(),
                    textBegin,
                    textEnd,
                    ctxBegin,
                    ctxEnd,
                    anchored,
                    longest,
                    endMatch,
                    null,
                    carriageReturnBeforeContext);
            return nfa.search();
        }

        try {
            // Pairs past the program's capture slots belong to groups the simplifier removed, which
            // no instruction writes. The search tracks only the program's slots, and a match
            // reports the other pairs unset.
            int searchSubmatchCount = Math.min(submatchCount, prog.captureSlotCount() / 2);
            int passSlotCount = passSlotCountForTesting;
            if (passSlotCount == 0 && 2 * searchSubmatchCount > prog.singlePassCaptureSlots()) {
                passSlotCount = prog.boundedPassCaptureSlots();
            }
            boolean matched;
            if (passSlotCount != 0) {
                matched = searchInBoundedPasses(
                        bytes,
                        prog,
                        textBegin,
                        textEnd,
                        ctxBegin,
                        ctxEnd,
                        submatch,
                        searchSubmatchCount,
                        anchored,
                        longest,
                        endMatch,
                        workspace,
                        carriageReturnBeforeContext,
                        passSlotCount,
                        passSlotCountForTesting != 0);
            }
            else {
                NfaImpl nfa = new NfaImpl(
                        bytes,
                        prog,
                        prog.start(),
                        textBegin,
                        textEnd,
                        ctxBegin,
                        ctxEnd,
                        searchSubmatchCount,
                        anchored,
                        longest,
                        endMatch,
                        workspace,
                        carriageReturnBeforeContext,
                        prog.getOrCreateCaptureInstructionWords(),
                        INITIAL_THREAD_CAPACITY);
                matched = nfa.search();
                if (matched) {
                    nfa.writeSubmatch(submatch);
                }
                nfa.releaseWorkspace();
            }
            if (matched) {
                Arrays.fill(submatch, 2 * searchSubmatchCount, submatch.length, -1);
            }
            return matched;
        }
        catch (RuntimeException | Error failure) {
            if (workspace != null) {
                workspace.invalidate();
            }
            throw failure;
        }
    }

    /// Searches as [#search(Prog,Slice,int,int,boolean,Prog.MatchKind,int\[\])] does, but in
    /// bounded passes of `passSlotCount` slots whatever the program's plan, and fails when a pass
    /// holds more threads than the program's thread bound or a pass after the first finds a
    /// different group zero.
    static boolean searchInBoundedPassesForTesting(Prog prog, Slice context, int start, int end, boolean anchored, Prog.MatchKind matchKind, int[] submatch, int passSlotCount)
    {
        if (passSlotCount < 4 || (passSlotCount % 2) != 0) {
            throw new IllegalArgumentException("passSlotCount must be even and at least four: " + passSlotCount);
        }
        return search(prog, context, start, end, anchored, matchKind, submatch, null, false, passSlotCount);
    }

    // The capture NFA's choice of match is the order of its threads, which is the queue order,
    // and, in leftmost-longest mode, each thread's slot 0 against the best match's slots 0 and 1;
    // NfaImpl.step uses no other slot to choose the winning match. A pass that tracks group zero
    // and a window of the other slots therefore follows the same threads to the same match as a
    // pass that tracks every slot, and passes over successive windows, with the same text,
    // context, and flags, together report exactly the slots one pass reports. Upstream's
    // nsubmatch argument relies on the same property.
    //
    // Each pass runs the unchanged search over a copy of the program's packed instruction words
    // in which CAPTURE keeps slots 0 and 1, numbers the window's slots from 2, and moves every
    // other slot past the pass's slot count, where the search's slot-count test skips it. One
    // copy per workspace is rewritten for each pass, and the passes share the workspace's queues,
    // stacks, and thread arena, whose tables are sized for the program's thread bound up front.
    // A search that fits one bounded pass keeps the program's own words.
    private static boolean searchInBoundedPasses(
            byte[] bytes,
            Prog prog,
            int textBegin,
            int textEnd,
            int ctxBegin,
            int ctxEnd,
            int[] submatch,
            int submatchCount,
            boolean anchored,
            boolean longest,
            boolean endMatch,
            Workspace workspace,
            boolean carriageReturnBeforeContext,
            int passSlotCount,
            boolean checkPasses)
    {
        // The plan leaves no room for cached group-zero queues beside a bounded pass.
        if (workspace != null) {
            workspace.releaseGroupZero();
        }
        int threadCapacity = captureThreadBound(prog) + 1;
        long[] instructionWords = prog.getOrCreateCaptureInstructionWords();
        int slotCount = 2 * submatchCount;
        if (slotCount <= passSlotCount) {
            NfaImpl nfa = new NfaImpl(
                    bytes,
                    prog,
                    prog.start(),
                    textBegin,
                    textEnd,
                    ctxBegin,
                    ctxEnd,
                    submatchCount,
                    anchored,
                    longest,
                    endMatch,
                    workspace,
                    carriageReturnBeforeContext,
                    instructionWords,
                    threadCapacity);
            boolean matched = nfa.search();
            if (matched) {
                nfa.writeSubmatch(submatch);
            }
            nfa.releaseWorkspace();
            return matched;
        }

        // A search without a workspace makes one for its passes.
        Workspace passWorkspace = (workspace == null) ? new Workspace() : workspace;
        long[] passInstructionWords = passWorkspace.preparePassInstructionWords(instructionWords.length);
        int windowSlotCount = passSlotCount - 2;
        int endSlot;
        for (int firstSlot = 2; firstSlot < slotCount; firstSlot = endSlot) {
            endSlot = firstSlot + Math.min(windowSlotCount, slotCount - firstSlot);
            remapCaptureSlots(instructionWords, passInstructionWords, firstSlot, endSlot);
            NfaImpl nfa = new NfaImpl(
                    bytes,
                    prog,
                    prog.start(),
                    textBegin,
                    textEnd,
                    ctxBegin,
                    ctxEnd,
                    passSlotCount / 2,
                    anchored,
                    longest,
                    endMatch,
                    passWorkspace,
                    carriageReturnBeforeContext,
                    passInstructionWords,
                    threadCapacity);
            boolean matched = nfa.search();
            if (checkPasses && nfa.threadCount >= threadCapacity) {
                throw new IllegalStateException("capture pass held " + nfa.threadCount + " threads, past the bound " + (threadCapacity - 1));
            }
            if (checkPasses && firstSlot > 2 && !nfa.matchesGroupZero(submatch)) {
                throw new IllegalStateException("capture pass for slots " + firstSlot + " to " + endSlot + " found a different group zero");
            }
            if (!matched) {
                nfa.releaseWorkspace();
                return false;
            }
            nfa.writeSubmatchWindow(submatch, firstSlot, endSlot);
            nfa.releaseWorkspace();
        }
        return true;
    }

    private static void remapCaptureSlots(long[] instructionWords, long[] passInstructionWords, int firstSlot, int endSlot)
    {
        for (int id = 0; id < instructionWords.length; id++) {
            long instructionWord = instructionWords[id];
            if ((instructionWord & 0b111) == NfaImpl.OP_CAPTURE) {
                int slot = (int) (instructionWord >>> 32);
                if (slot >= 2) {
                    int passSlot = (slot >= firstSlot && slot < endSlot) ? slot - firstSlot + 2 : Integer.MAX_VALUE;
                    instructionWord = (instructionWord & 0xFFFF_FFFFL) | ((long) passSlot << 32);
                }
            }
            passInstructionWords[id] = instructionWord;
        }
    }

    public static boolean search(Prog prog, Slice text, boolean anchored, Prog.MatchKind matchKind, int[] submatch)
    {
        return search(prog, text, 0, text.length(), anchored, matchKind, submatch);
    }

    static boolean searchGroupZero(
            Prog prog,
            Slice context,
            int start,
            int end,
            boolean anchored,
            Prog.MatchKind matchKind,
            int[] groupZero)
    {
        return searchGroupZero(prog, context, start, end, anchored, matchKind, groupZero, null);
    }

    static boolean searchGroupZero(
            Prog prog,
            Slice context,
            int start,
            int end,
            boolean anchored,
            Prog.MatchKind matchKind,
            int[] groupZero,
            Workspace workspace)
    {
        return searchGroupZero(prog, context, start, end, anchored, matchKind, groupZero, workspace, false);
    }

    /// Finds group zero as [#search(Prog,Slice,int,int,boolean,Prog.MatchKind,int\[\],Workspace,boolean)]
    /// does, with the same `carriageReturnBeforeContext` flag.
    static boolean searchGroupZero(
            Prog prog,
            Slice context,
            int start,
            int end,
            boolean anchored,
            Prog.MatchKind matchKind,
            int[] groupZero,
            Workspace workspace,
            boolean carriageReturnBeforeContext)
    {
        requireNonNull(prog, "prog is null");
        requireNonNull(context, "context is null");
        requireNonNull(matchKind, "matchKind is null");
        requireNonNull(groupZero, "groupZero is null");
        if (groupZero.length != 2) {
            throw new IllegalArgumentException("groupZero length must be two: " + groupZero.length);
        }
        if (prog.start() == 0) {
            return false;
        }

        int contextBegin = context.byteArrayOffset();
        int contextEnd = contextBegin + context.length();
        if (start < 0 || end < start || end > context.length()) {
            return false;
        }
        int textBegin = contextBegin + start;
        int textEnd = contextBegin + end;
        if (prog.anchorStart() && contextBegin != textBegin) {
            return false;
        }
        if (prog.anchorEnd() && contextEnd != textEnd) {
            return false;
        }

        anchored |= prog.anchorStart() || matchKind == Prog.MatchKind.FULL_MATCH;
        boolean longest = matchKind != Prog.MatchKind.FIRST_MATCH;
        boolean endMatch = prog.anchorEnd() || matchKind == Prog.MatchKind.FULL_MATCH;
        try {
            NoSubmatchNfaImpl nfa = new NoSubmatchNfaImpl(
                    context.byteArray(),
                    prog,
                    prog.start(),
                    textBegin,
                    textEnd,
                    contextBegin,
                    contextEnd,
                    anchored,
                    longest,
                    endMatch,
                    workspace,
                    carriageReturnBeforeContext);
            boolean matched = nfa.search();
            if (matched) {
                nfa.writeGroupZero(groupZero);
            }
            return matched;
        }
        catch (RuntimeException | Error failure) {
            if (workspace != null) {
                workspace.invalidate();
            }
            throw failure;
        }
    }

    static int searchTagged(
            Prog prog,
            Slice context,
            int start,
            int end,
            boolean anchored,
            Prog.MatchKind matchKind,
            int[] groupZero,
            Workspace workspace)
    {
        if (prog.start() == 0 || start < 0 || end < start || end > context.length()) {
            return -1;
        }
        int contextBegin = context.byteArrayOffset();
        // compileSet's anchor flags describe DFA set execution, not the source expression.
        // TaggedAlternationProgram excludes source anchors and supplies the actual search mode.
        try {
            NoSubmatchNfaImpl nfa = new NoSubmatchNfaImpl(
                    context.byteArray(),
                    prog,
                    prog.start(),
                    contextBegin + start,
                    contextBegin + end,
                    contextBegin,
                    contextBegin + context.length(),
                    anchored || matchKind == Prog.MatchKind.FULL_MATCH,
                    matchKind != Prog.MatchKind.FIRST_MATCH,
                    matchKind == Prog.MatchKind.FULL_MATCH,
                    workspace,
                    false);
            if (!nfa.search()) {
                return -1;
            }
            nfa.writeGroupZero(groupZero);
            return Prog.instMatchId(prog.word(nfa.matchInstruction));
        }
        catch (RuntimeException | Error failure) {
            if (workspace != null) {
                workspace.invalidate();
            }
            throw failure;
        }
    }

    /// Anchored full match (equivalent to RE2 `ANCHOR_BOTH`): the regexp must match
    /// starting at the beginning of `text` and ending at the end of `text`.
    public static boolean fullMatch(Prog prog, Slice text)
    {
        requireNonNull(prog, "prog is null");
        requireNonNull(text, "text is null");

        return search(prog, text, 0, text.length(), true, Prog.MatchKind.FULL_MATCH, null);
    }

    /// Unanchored partial match (equivalent to RE2 `ANCHOR_NONE` + `kFirstMatch`):
    /// finds a leftmost-first match anywhere in `text`.
    public static boolean partialMatch(Prog prog, Slice text)
    {
        requireNonNull(prog, "prog is null");
        requireNonNull(text, "text is null");
        return search(prog, text, 0, text.length(), false, Prog.MatchKind.FIRST_MATCH, null);
    }

    static int captureStackSize(Prog prog)
    {
        int stackSize = 2 * prog.getInstCount(InstOp.CAPTURE) +
                prog.getInstCount(InstOp.EMPTY_WIDTH) +
                prog.getInstCount(InstOp.NOP) + 1;
        return Math.max(8, stackSize);
    }

    static int groupZeroStackSize(Prog prog)
    {
        int stackSize = prog.getInstCount(InstOp.CAPTURE) +
                prog.getInstCount(InstOp.EMPTY_WIDTH) +
                prog.getInstCount(InstOp.NOP) + 1;
        return Math.max(8, stackSize);
    }

    /// Returns the most capture threads one search holds at once. A queue holds a thread only at
    /// a byte range, alternate-match, or match entry, so the queue being stepped holds at most
    /// one thread per such instruction. Every other thread in the queue being filled was
    /// allocated during the fill: one for the search start, and one at each CAPTURE, which the
    /// fill visits at most once.
    static int captureThreadBound(Prog prog)
    {
        long bound = (long) prog.getInstCount(InstOp.BYTE_RANGE) + prog.getInstCount(InstOp.ALT_MATCH) + prog.getInstCount(InstOp.MATCH) + prog.getInstCount(InstOp.CAPTURE) + 1;
        // Callers add one to the bound for the best match's slots, which must not overflow.
        return (int) Math.min(Integer.MAX_VALUE - 1, bound);
    }

    /// Plans the capture searches of `prog`, which track at most the program's
    /// [capture slots][Prog#captureSlotCount], so that a search's capture state stays within
    /// `allowance` bytes, and stores the plan in `prog`. A search that fits keeps the ordinary
    /// single pass. A wider one runs bounded passes; see [#searchInBoundedPasses].
    /// `groupZeroProgram`, when not null, is the other program whose group-zero queues a
    /// matcher's workspace may cache. Throws [RegexpCompileMemoryLimitException], reporting the
    /// least allowance with a plan, when no plan fits.
    static void planCaptureSearches(Prog prog, long allowance, Prog groupZeroProgram)
    {
        int slotCount = prog.captureSlotCount();
        long threadSlots = threadSlotBytes(prog, slotCount);
        long singlePass = threadSlots + singlePassBytes(prog, groupZeroProgram, false);
        if (singlePass <= allowance) {
            return;
        }
        long boundedPass = threadSlots + boundedPassBytes(prog, false);
        if (boundedPass <= allowance) {
            prog.setCapturePlan(largestSlotCount(prog, singlePassBytes(prog, groupZeroProgram, false), allowance), slotCount);
            return;
        }
        int boundedPassSlots = largestSlotCount(prog, boundedPassBytes(prog, true), allowance);
        if (boundedPassSlots < 4) {
            // The compiler admits a flattened program of P instructions only when the forward
            // share, which is the allowance, holds 2,688 + 49P bytes. Instruction 0 is FAIL, so
            // the thread bound is at most P and the stack at most max(8, 2P - 1) slots, and an
            // int[n] takes at most 20 + 4n bytes. A pass of four slots then takes at most
            // max(96P + 720, 80P + 784) bytes: 32P + 32 of slots, 448 of objects, 24P + 120 of
            // queues, max(96, 16P + 32) of stacks, 16P + 72 of thread tables, and 8P + 16 of
            // rewritten words. That fits the share for P <= 41, so no program the compiler admits
            // of at most 41 instructions reaches this rejection. A program of two slots needs one
            // pass of them without rewritten words, which is smaller still, so it reaches this
            // rejection only when that pass does not fit. A larger program's pass can outgrow the
            // share, as the slots, queues and stacks take more per instruction than the flatten
            // charge, and this rejection then keeps its capture tables within the share; it sets
            // the least budget of such a pattern, as for 40 copies of (a).
            // The least allowance with a plan is the least of the three checks: a single pass, one
            // bounded pass of every slot, or bounded passes of four slots.
            long boundedPassesOfFour = threadSlotBytes(prog, 4) + boundedPassBytes(prog, true);
            throw new RegexpCompileMemoryLimitException(allowance, Math.min(singlePass, Math.min(boundedPass, boundedPassesOfFour)));
        }
        prog.setCapturePlan(largestSlotCount(prog, singlePassBytes(prog, groupZeroProgram, true), allowance), boundedPassSlots);
    }

    // Each live thread, and the best match, holds one int[] of the slots.
    private static long threadSlotBytes(Prog prog, int slotCount)
    {
        return (captureThreadBound(prog) + 1L) * intArrayBytes(slotCount);
    }

    // The largest even slot count whose threads fit beside `otherBytes`.
    private static int largestSlotCount(Prog prog, long otherBytes, long allowance)
    {
        long threads = captureThreadBound(prog) + 1L;
        long perThread = (allowance - otherBytes) / threads;
        if (perThread < intArrayBytes(0)) {
            return 0;
        }
        long slots = (perThread - intArrayBytes(0)) / Integer.BYTES;
        return (int) Math.min(Integer.MAX_VALUE - 1, slots & ~1L);
    }

    // The ordinary pass, apart from the threads' slots: thread tables that grow by doubling from
    // INITIAL_THREAD_CAPACITY, with the outgrown tables live during the last copy, and the
    // workspace's cached group-zero queues for whichever program it last ran. A matcher's
    // workspace keeps the tables the search started with until the search ends, so once the
    // tables double twice those are live beside both; they are at most a quarter of the final
    // length, which a stateless search, holding no starting tables, overcounts. The plan counts
    // the memory live at a search's peak, not every array the search allocates on the way.
    private static long singlePassBytes(Prog prog, Prog groupZeroProgram, boolean rewritesInstructionWords)
    {
        long threads = captureThreadBound(prog);
        long tableLength = INITIAL_THREAD_CAPACITY;
        while (tableLength <= threads) {
            tableLength *= 2;
        }
        long tables = threadTableBytes(tableLength);
        if (tableLength > INITIAL_THREAD_CAPACITY) {
            tables += threadTableBytes(tableLength / 2);
        }
        if (tableLength >= 4 * INITIAL_THREAD_CAPACITY) {
            tables += threadTableBytes(tableLength / 4);
        }
        long groupZero = groupZeroBytes(prog);
        if (groupZeroProgram != null) {
            groupZero = Math.max(groupZero, groupZeroBytes(groupZeroProgram));
        }
        return captureSearchBytes(prog, rewritesInstructionWords) + tables + groupZero;
    }

    // A bounded pass, apart from the threads' slots: thread tables sized for the thread bound up
    // front, and no group-zero queues.
    private static long boundedPassBytes(Prog prog, boolean rewritesInstructionWords)
    {
        return captureSearchBytes(prog, rewritesInstructionWords) + threadTableBytes(captureThreadBound(prog) + 1L);
    }

    // The workspace, the search, a region's context view, its two queues and two stacks, and the
    // rewritten instruction words when the passes need them.
    private static long captureSearchBytes(Prog prog, boolean rewritesInstructionWords)
    {
        long bytes = WORKSPACE_BYTES + NFA_BYTES + SLICE_BYTES + 2 * (QUEUE_BYTES + 3 * intArrayBytes(prog.size())) + 2 * intArrayBytes(captureStackSize(prog));
        if (rewritesInstructionWords) {
            bytes += referenceArrayBytes(prog.size());
        }
        return bytes;
    }

    private static long groupZeroBytes(Prog prog)
    {
        return 2 * (QUEUE_BYTES + 3 * intArrayBytes(prog.size())) + intArrayBytes(groupZeroStackSize(prog));
    }

    private static long threadTableBytes(long length)
    {
        return 2 * intArrayBytes(length) + referenceArrayBytes(length);
    }

    private static long intArrayBytes(long length)
    {
        return (16 + 4 * length + 7) & ~7L;
    }

    // Also the size of a long[] of the same length.
    private static long referenceArrayBytes(long length)
    {
        return 16 + 8 * length;
    }

    // Sparse queue specialized for NFA hot loops. Membership is tracked with sparse positions
    // and validated against dense ids to keep clear() O(1) without clearing backing arrays.
    private static final class NfaThreadQueue
    {
        private int size;
        private final int[] sparsePos;
        private final int[] denseId;
        // Encoded start position payload (startPos + 1). Zero means placeholder/non-thread entry.
        private final int[] denseThread;

        private NfaThreadQueue(int maxId)
        {
            this.sparsePos = new int[maxId];
            this.denseId = new int[maxId];
            this.denseThread = new int[maxId];
            this.size = 0;
        }

        private void clear()
        {
            size = 0;
        }

        private boolean isEmpty()
        {
            return size == 0;
        }

        private int size()
        {
            return size;
        }

        private int denseIdAt(int pos)
        {
            return denseId[pos];
        }

        private int denseThreadAt(int pos)
        {
            return denseThread[pos];
        }

        // Returns dense position for a newly inserted id; -1 if id is already present.
        private int markVisitedIfAbsent(int id)
        {
            int pos = sparsePos[id];
            if (pos < size && denseId[pos] == id) {
                return -1;
            }
            int newPos = size;
            sparsePos[id] = newPos;
            denseId[newPos] = id;
            denseThread[newPos] = 0;
            size = newPos + 1;
            return newPos;
        }

        private void setThreadAtPos(int pos, int encodedStartPos)
        {
            denseThread[pos] = encodedStartPos;
        }
    }

    private static final class NoSubmatchNfaImpl
    {
        // InstOp ordinals encoded as literals so they can be used in switch labels.
        private static final byte OP_ALT = 0;
        private static final byte OP_ALT_MATCH = 1;
        private static final byte OP_BYTE_RANGE = 2;
        private static final byte OP_CAPTURE = 3;
        private static final byte OP_EMPTY_WIDTH = 4;
        private static final byte OP_MATCH = 5;
        private static final byte OP_NOP = 6;
        private static final byte OP_FAIL = 7;
        private final Prog prog;
        private final byte[] bytes;

        private final int start;

        private final int ctxBegin;
        private final int ctxEnd;

        private final int textBegin;
        private final int textEnd;

        private final boolean anchored;
        private final boolean longest;
        private final boolean endMatch;
        private final boolean carriageReturnBeforeContext;

        private final NfaThreadQueue q0;
        private final NfaThreadQueue q1;

        private final byte[] op;
        private final int[] out;
        private final int[] out1;
        private final int[] hint;
        private final int[] lo;
        private final int[] hi;
        private final int[] empty;
        private final int[] outLast;
        private final boolean[] foldCase;
        private final boolean[] altMatchGreedy;
        private final boolean hasCaptureOps;

        // AddToThreadq stack.
        private final int[] addIdStack;

        private boolean matched;
        private int matchStart;
        private int matchEnd;
        private int matchInstruction;

        private NoSubmatchNfaImpl(
                byte[] bytes,
                Prog prog,
                int start,
                int textBegin,
                int textEnd,
                int ctxBegin,
                int ctxEnd,
                boolean anchored,
                boolean longest,
                boolean endMatch,
                Workspace workspace,
                boolean carriageReturnBeforeContext)
        {
            this.bytes = requireNonNull(bytes, "bytes is null");
            this.prog = requireNonNull(prog, "prog is null");

            this.start = start;
            this.textBegin = textBegin;
            this.textEnd = textEnd;
            this.ctxBegin = ctxBegin;
            this.ctxEnd = ctxEnd;
            this.anchored = anchored;
            this.longest = longest;
            this.endMatch = endMatch;
            this.carriageReturnBeforeContext = carriageReturnBeforeContext;

            this.matched = false;
            this.matchStart = -1;
            this.matchEnd = -1;

            Prog.NoSubmatchTables tables = prog.getOrCreateNoSubmatchTables();
            int progSize = tables.op().length;
            if (workspace == null) {
                this.q0 = new NfaThreadQueue(progSize);
                this.q1 = new NfaThreadQueue(progSize);
                this.addIdStack = new int[tables.addStackSize()];
            }
            else {
                workspace.prepareGroupZero(prog, tables.addStackSize());
                this.q0 = workspace.firstGroupZeroQueue;
                this.q1 = workspace.secondGroupZeroQueue;
                this.addIdStack = workspace.groupZeroAddInstructionIdStack;
            }
            this.op = tables.op();
            this.out = tables.out();
            this.out1 = tables.out1();
            this.hint = tables.hint();
            this.lo = tables.lo();
            this.hi = tables.hi();
            this.empty = tables.empty();
            this.outLast = tables.outLast();
            this.foldCase = tables.foldCase();
            this.altMatchGreedy = tables.altMatchGreedy();
            this.hasCaptureOps = tables.hasCaptureOps();
        }

        private boolean search()
        {
            NfaThreadQueue runQueue = q0;
            NfaThreadQueue nextQueue = q1;
            runQueue.clear();
            nextQueue.clear();
            // A folded run behind a lead of varying width answers the skips after its result up
            // to the lead's spread, as Prog.prefixAccel describes, so the search skips them. A
            // plain prefix or literal, with no spread, never tests or writes the bound.
            int leadSpread = prog.prefixAccelMaximumLead() - prog.prefixAccelMinimumLead();
            int skipCoversThrough = -1;

            for (int position = textBegin; ; position++) {
                int c = (position < textEnd) ? (bytes[position] & 0xFF) : -1;

                int shortcut = step(runQueue, nextQueue, c, position);

                NfaThreadQueue temporary = runQueue;
                runQueue = nextQueue;
                nextQueue = temporary;
                nextQueue.clear();

                if (shortcut != 0) {
                    int id = shortcut;
                    for (; ; ) {
                        byte opcode = op[id];
                        switch (opcode) {
                            case OP_CAPTURE, OP_NOP -> id = outLast[id] >>> 1;
                            case OP_MATCH -> {
                                matched = true;
                                matchEnd = textEnd;
                                matchInstruction = id;
                                id = 0;
                            }
                            default -> id = 0;
                        }
                        if (id == 0) {
                            break;
                        }
                    }
                    break;
                }

                if (position > textEnd) {
                    break;
                }

                if (!matched && (!anchored || position == textBegin)) {
                    if (!anchored && runQueue.isEmpty() && position < textEnd && prog.canSkipToStartCandidate() &&
                            (leadSpread == 0 || position > skipCoversThrough)) {
                        int found = prog.skipToStartCandidate(bytes, position, textEnd - position);
                        if (found < 0) {
                            position = textEnd;
                        }
                        else {
                            if (leadSpread != 0 && found > position) {
                                skipCoversThrough = found + leadSpread;
                            }
                            position = found;
                        }
                        c = (position < textEnd) ? (bytes[position] & 0xFF) : -1;
                    }

                    addToThreadq(runQueue, start, c, position, position);
                }

                if (runQueue.isEmpty()) {
                    break;
                }
            }
            runQueue.clear();

            return matched;
        }

        private void writeGroupZero(int[] groupZero)
        {
            groupZero[0] = matchStart - textBegin;
            groupZero[1] = matchEnd - textBegin;
        }

        private int step(NfaThreadQueue runQueue, NfaThreadQueue nextQueue, int c, int position)
        {
            nextQueue.clear();

            for (int it = 0; it < runQueue.size(); it++) {
                int encodedStartPos = runQueue.denseThreadAt(it);
                if (encodedStartPos == 0) {
                    continue;
                }
                int startPos = encodedStartPos - 1;

                if (longest && matched && matchStart >= 0 && matchStart < startPos) {
                    continue;
                }

                int id = runQueue.denseIdAt(it);
                byte opcode = op[id];
                switch (opcode) {
                    case OP_BYTE_RANGE -> addToThreadq(nextQueue, out[id], c, position, startPos);
                    case OP_ALT_MATCH -> {
                        if (it != 0) {
                            break;
                        }
                        boolean greedy = altMatchGreedy[id];
                        if (greedy || longest) {
                            matched = true;
                            matchStart = startPos;
                            matchEnd = position - 1;

                            runQueue.clear();
                            if (greedy) {
                                return out1[id];
                            }
                            return out[id];
                        }
                    }
                    case OP_MATCH -> {
                        if (endMatch && (position - 1) != textEnd) {
                            break;
                        }

                        int endPos = position - 1;
                        if (longest) {
                            if (!matched ||
                                    startPos < matchStart ||
                                    (startPos == matchStart && endPos > matchEnd)) {
                                matched = true;
                                matchStart = startPos;
                                matchEnd = endPos;
                                matchInstruction = id;
                            }
                        }
                        else {
                            matched = true;
                            matchStart = startPos;
                            matchEnd = endPos;
                            matchInstruction = id;

                            runQueue.clear();
                            return 0;
                        }
                    }
                    default -> {
                        // Unexpected in flattened programs (runQueue should contain only ByteRange/AltMatch/Match).
                    }
                }
            }
            runQueue.clear();
            return 0;
        }

        @SuppressFBWarnings(value = "SF_SWITCH_NO_DEFAULT", justification = "The switch exhaustively handles encoded instruction opcodes")
        private void addToThreadq(NfaThreadQueue q, int id0, int c, int position, int startPos)
        {
            if (hasCaptureOps) {
                addToThreadqWithIndexedCaptureTables(q, id0, c, position, startPos);
                return;
            }

            if (id0 == 0) {
                return;
            }

            int stackPointer = 0;
            addIdStack[stackPointer] = id0;
            stackPointer++;

            while (stackPointer > 0) {
                stackPointer--;
                int id = addIdStack[stackPointer];

                for (; ; ) {
                    if (id == 0) {
                        break;
                    }

                    int pos = q.markVisitedIfAbsent(id);
                    if (pos < 0) {
                        break;
                    }

                    byte opcode = op[id];
                    switch (opcode) {
                        case OP_FAIL -> id = 0;
                        case OP_ALT_MATCH -> {
                            q.setThreadAtPos(pos, startPos + 1);
                            if ((outLast[id] & 1) != 0) {
                                id = 0;
                                break;
                            }
                            id = id + 1;
                        }
                        case OP_NOP -> {
                            int outLastValue = outLast[id];
                            if ((outLastValue & 1) == 0) {
                                pushAddState(id + 1, stackPointer++);
                            }
                            id = outLastValue >>> 1;
                        }
                        case OP_CAPTURE -> {
                            int outLastValue = outLast[id];
                            if ((outLastValue & 1) == 0) {
                                pushAddState(id + 1, stackPointer++);
                            }
                            id = outLastValue >>> 1;
                        }
                        case OP_BYTE_RANGE -> {
                            if (!matchesByteRange(id, c)) {
                                if ((outLast[id] & 1) != 0) {
                                    id = 0;
                                }
                                else {
                                    id = id + 1;
                                }
                                break;
                            }

                            q.setThreadAtPos(pos, startPos + 1);
                            int nextHint = hint[id];
                            if (nextHint == 0) {
                                id = 0;
                                break;
                            }
                            id = id + nextHint;
                        }
                        case OP_MATCH -> {
                            q.setThreadAtPos(pos, startPos + 1);
                            if ((outLast[id] & 1) != 0) {
                                id = 0;
                            }
                            else {
                                id = id + 1;
                            }
                        }
                        case OP_EMPTY_WIDTH -> {
                            int outLastValue = outLast[id];
                            if ((outLastValue & 1) == 0) {
                                pushAddState(id + 1, stackPointer++);
                            }

                            int emptyFlags = emptyFlags(position);
                            if ((empty[id] & ~emptyFlags) == 0) {
                                id = outLastValue >>> 1;
                            }
                            else {
                                id = 0;
                            }
                        }
                        case OP_ALT -> {
                            pushAddState(out1[id], stackPointer++);
                            id = out[id];
                        }
                    }
                    if (id == 0) {
                        break;
                    }
                }
            }
        }

        // Capture-heavy no-submatch path: preserves instruction semantics, but reads decoded
        // tables instead of packed words.
        @SuppressFBWarnings(value = "SF_SWITCH_NO_DEFAULT", justification = "The switch exhaustively handles encoded instruction opcodes")
        private void addToThreadqWithIndexedCaptureTables(NfaThreadQueue q, int id0, int c, int position, int startPos)
        {
            if (id0 == 0) {
                return;
            }

            int encodedStartPos = startPos + 1;
            int cachedEmptyFlags = Integer.MIN_VALUE;
            int stackPointer = 0;
            addIdStack[stackPointer] = id0;
            stackPointer++;

            while (stackPointer > 0) {
                stackPointer--;
                int id = addIdStack[stackPointer];

                for (; ; ) {
                    if (id == 0) {
                        break;
                    }

                    int pos = q.markVisitedIfAbsent(id);
                    if (pos < 0) {
                        break;
                    }

                    int outLastValue = outLast[id];
                    int outValue = outLastValue >>> 1;
                    boolean last = (outLastValue & 1) != 0;
                    switch (op[id]) {
                        case OP_FAIL -> id = 0;
                        case OP_ALT_MATCH -> {
                            q.setThreadAtPos(pos, encodedStartPos);
                            if (last) {
                                id = 0;
                                break;
                            }
                            id = id + 1;
                        }
                        case OP_NOP, OP_CAPTURE -> {
                            if (!last) {
                                pushAddState(id + 1, stackPointer++);
                            }
                            id = outValue;
                        }
                        case OP_BYTE_RANGE -> {
                            if (!matchesByteRange(id, c)) {
                                if (last) {
                                    id = 0;
                                }
                                else {
                                    id = id + 1;
                                }
                                break;
                            }

                            q.setThreadAtPos(pos, encodedStartPos);
                            int nextHint = hint[id];
                            if (nextHint == 0) {
                                id = 0;
                                break;
                            }
                            id = id + nextHint;
                        }
                        case OP_MATCH -> {
                            q.setThreadAtPos(pos, encodedStartPos);
                            if (last) {
                                id = 0;
                            }
                            else {
                                id = id + 1;
                            }
                        }
                        case OP_EMPTY_WIDTH -> {
                            if (!last) {
                                pushAddState(id + 1, stackPointer++);
                            }

                            if (cachedEmptyFlags == Integer.MIN_VALUE) {
                                cachedEmptyFlags = emptyFlags(position);
                            }
                            if ((empty[id] & ~cachedEmptyFlags) == 0) {
                                id = outValue;
                            }
                            else {
                                id = 0;
                            }
                        }
                        case OP_ALT -> {
                            pushAddState(out1[id], stackPointer++);
                            id = outValue;
                        }
                    }
                    if (id == 0) {
                        break;
                    }
                }
            }
        }

        private boolean matchesByteRange(int id, int c)
        {
            if (c < 0 || c > 0xFF) {
                return false;
            }
            int b = c;
            if (foldCase[id] && 'A' <= b && b <= 'Z') {
                b += 'a' - 'A';
            }
            return lo[id] <= b && b <= hi[id];
        }

        private void pushAddState(int id, int pos)
        {
            if (pos >= addIdStack.length) {
                throw new IllegalStateException("AddToThreadq stack overflow (pos=" + pos + " capacity=" + addIdStack.length + ")");
            }
            addIdStack[pos] = id;
        }

        private int emptyFlags(int position)
        {
            int flags = EmptyOp.contextFlags(bytes, ctxBegin, ctxEnd, position, prog.textDependentAssertions());
            if (carriageReturnBeforeContext) {
                flags &= ~EmptyOp.javaEndFlagsRefusedAfterCarriageReturn(bytes, ctxBegin, ctxEnd, position);
            }
            return flags;
        }
    }

    private static final class CaptureThreadQueue
    {
        private int size;
        private final int[] sparsePosition;
        private final int[] denseInstructionId;
        private final int[] denseThread;

        private CaptureThreadQueue(int programSize)
        {
            sparsePosition = new int[programSize];
            denseInstructionId = new int[programSize];
            denseThread = new int[programSize];
        }

        private void clear()
        {
            size = 0;
        }

        private boolean isEmpty()
        {
            return size == 0;
        }

        private int size()
        {
            return size;
        }

        private int instructionIdAt(int position)
        {
            return denseInstructionId[position];
        }

        private int threadAt(int position)
        {
            return denseThread[position];
        }

        private int markVisitedIfAbsent(int instructionId)
        {
            int position = sparsePosition[instructionId];
            if (position < size && denseInstructionId[position] == instructionId) {
                return -1;
            }

            position = size;
            sparsePosition[instructionId] = position;
            denseInstructionId[position] = instructionId;
            denseThread[position] = 0;
            size = position + 1;
            return position;
        }

        private void setThreadAt(int position, int thread)
        {
            denseThread[position] = thread;
        }
    }

    static final class Workspace
    {
        private Prog program;
        private int captureSlotCount;
        private CaptureThreadQueue firstQueue;
        private CaptureThreadQueue secondQueue;
        private int[] addInstructionIdStack;
        private int[] addRestoreThreadStack;
        private int[] threadReferences;
        private int[] threadNext;
        private int[][] threadCaptures;
        private int[] match;
        private int threadCount;
        private int freeList;
        private Prog groupZeroProgram;
        private NfaThreadQueue firstGroupZeroQueue;
        private NfaThreadQueue secondGroupZeroQueue;
        private int[] groupZeroAddInstructionIdStack;
        private int groupZeroWorkspaceReuseCount;
        private long[] passInstructionWords;

        private void prepare(Prog program, int captureSlotCount, int threadCapacity)
        {
            if (this.program == program && this.captureSlotCount == captureSlotCount) {
                firstQueue.clear();
                secondQueue.clear();
                Arrays.fill(match, -1);
                // An ordinary pass with the same slot count may have left tables smaller than a
                // bounded pass needs.
                if (threadReferences.length < threadCapacity) {
                    threadReferences = Arrays.copyOf(threadReferences, threadCapacity);
                    threadNext = Arrays.copyOf(threadNext, threadCapacity);
                    threadCaptures = Arrays.copyOf(threadCaptures, threadCapacity);
                }
                return;
            }

            int programSize = program.size();
            CaptureThreadQueue firstQueue = new CaptureThreadQueue(programSize);
            CaptureThreadQueue secondQueue = new CaptureThreadQueue(programSize);
            int stackSize = captureStackSize(program);
            int[] addInstructionIdStack = new int[stackSize];
            int[] addRestoreThreadStack = new int[stackSize];
            int[] threadReferences = new int[threadCapacity];
            int[] threadNext = new int[threadCapacity];
            int[][] threadCaptures = new int[threadCapacity][];
            int[] match = new int[captureSlotCount];
            Arrays.fill(match, -1);

            // Publish a complete workspace only after every allocation succeeds.
            this.program = program;
            this.captureSlotCount = captureSlotCount;
            this.firstQueue = firstQueue;
            this.secondQueue = secondQueue;
            this.addInstructionIdStack = addInstructionIdStack;
            this.addRestoreThreadStack = addRestoreThreadStack;
            this.threadReferences = threadReferences;
            this.threadNext = threadNext;
            this.threadCaptures = threadCaptures;
            this.match = match;
            this.threadCount = 0;
            this.freeList = 0;
        }

        private void updateArena(NfaImpl nfa)
        {
            threadReferences = nfa.threadRef;
            threadNext = nfa.threadNext;
            threadCaptures = nfa.threadCapture;
            threadCount = nfa.threadCount;
            freeList = nfa.freeList;
        }

        private void prepareGroupZero(Prog program, int stackSize)
        {
            if (groupZeroProgram == program) {
                firstGroupZeroQueue.clear();
                secondGroupZeroQueue.clear();
                groupZeroWorkspaceReuseCount++;
                return;
            }

            int programSize = program.size();
            NfaThreadQueue firstQueue = new NfaThreadQueue(programSize);
            NfaThreadQueue secondQueue = new NfaThreadQueue(programSize);
            int[] addInstructionIdStack = new int[stackSize];

            groupZeroProgram = program;
            firstGroupZeroQueue = firstQueue;
            secondGroupZeroQueue = secondQueue;
            groupZeroAddInstructionIdStack = addInstructionIdStack;
            groupZeroWorkspaceReuseCount = 0;
        }

        int groupZeroWorkspaceReuseCountForDiagnostics()
        {
            return groupZeroWorkspaceReuseCount;
        }

        /// Returns how many capture threads the workspace's arena holds: the most that were live
        /// at once in a search since the arena was initialized for this program and slot count.
        int captureThreadCountForDiagnostics()
        {
            return threadCount;
        }

        private void releaseGroupZero()
        {
            groupZeroProgram = null;
            firstGroupZeroQueue = null;
            secondGroupZeroQueue = null;
            groupZeroAddInstructionIdStack = null;
            groupZeroWorkspaceReuseCount = 0;
        }

        private long[] preparePassInstructionWords(int programSize)
        {
            if (passInstructionWords == null || passInstructionWords.length != programSize) {
                passInstructionWords = new long[programSize];
            }
            return passInstructionWords;
        }

        private void invalidate()
        {
            program = null;
            captureSlotCount = 0;
            firstQueue = null;
            secondQueue = null;
            addInstructionIdStack = null;
            addRestoreThreadStack = null;
            threadReferences = null;
            threadNext = null;
            threadCaptures = null;
            match = null;
            threadCount = 0;
            freeList = 0;
            releaseGroupZero();
            passInstructionWords = null;
        }
    }

    private static final class NfaImpl
    {
        private static final int OP_ALT = 0;
        private static final int OP_ALT_MATCH = 1;
        private static final int OP_BYTE_RANGE = 2;
        private static final int OP_CAPTURE = 3;
        private static final int OP_EMPTY_WIDTH = 4;
        private static final int OP_MATCH = 5;
        private static final int OP_NOP = 6;
        private static final int OP_FAIL = 7;
        private static final long OUTPUT_MASK = (1L << 28) - 1;

        private final Prog prog;
        private final long[] instructionWords;
        private final byte[] bytes;

        private final int start;

        private final int ctxBegin;
        private final int ctxEnd;

        private final int textBegin;
        private final int textEnd;

        private final int submatchCount;
        private final int ncapture;
        private final boolean anchored;
        private final boolean longest;
        private final boolean endMatch;
        private final boolean carriageReturnBeforeContext;

        private final CaptureThreadQueue q0;
        private final CaptureThreadQueue q1;

        // AddToThreadq stack.
        private final int[] addIdStack;
        private final int[] addRestoreThreadStack;

        // Thread arena/pool (1-based ids; 0 means null).
        private int threadCount;
        private int freeList;
        private int[] threadRef;
        private int[] threadNext;
        private int[][] threadCapture;

        private final int[] match; // best match so far (absolute byte[] indices)
        private boolean matched;
        private final Workspace workspace;

        private NfaImpl(
                byte[] bytes,
                Prog prog,
                int start,
                int textBegin,
                int textEnd,
                int ctxBegin,
                int ctxEnd,
                int submatchCount,
                boolean anchored,
                boolean longest,
                boolean endMatch,
                Workspace workspace,
                boolean carriageReturnBeforeContext,
                long[] instructionWords,
                int threadCapacity)
        {
            this.bytes = requireNonNull(bytes, "bytes is null");
            this.prog = requireNonNull(prog, "prog is null");
            this.instructionWords = requireNonNull(instructionWords, "instructionWords is null");

            this.start = start;
            this.textBegin = textBegin;
            this.textEnd = textEnd;
            this.ctxBegin = ctxBegin;
            this.ctxEnd = ctxEnd;
            this.submatchCount = submatchCount;
            this.anchored = anchored;
            this.longest = longest;
            this.endMatch = endMatch;
            this.carriageReturnBeforeContext = carriageReturnBeforeContext;

            // We always track at least $0 (two slots), even if the caller requested no submatches.
            this.ncapture = (submatchCount == 0) ? 2 : 2 * submatchCount;
            this.workspace = workspace;
            this.matched = false;

            if (workspace == null) {
                this.match = new int[ncapture];
                Arrays.fill(this.match, -1);
                int programSize = prog.size();
                this.q0 = new CaptureThreadQueue(programSize);
                this.q1 = new CaptureThreadQueue(programSize);
                int stackSize = captureStackSize(prog);
                this.addIdStack = new int[stackSize];
                this.addRestoreThreadStack = new int[stackSize];
                this.threadRef = new int[threadCapacity];
                this.threadNext = new int[threadCapacity];
                this.threadCapture = new int[threadCapacity][];
                this.threadCount = 0;
                this.freeList = 0;
            }
            else {
                workspace.prepare(prog, ncapture, threadCapacity);
                this.match = workspace.match;
                this.q0 = workspace.firstQueue;
                this.q1 = workspace.secondQueue;
                this.addIdStack = workspace.addInstructionIdStack;
                this.addRestoreThreadStack = workspace.addRestoreThreadStack;
                this.threadRef = workspace.threadReferences;
                this.threadNext = workspace.threadNext;
                this.threadCapture = workspace.threadCaptures;
                this.threadCount = workspace.threadCount;
                this.freeList = workspace.freeList;
            }
        }

        private void releaseWorkspace()
        {
            if (workspace != null) {
                workspace.updateArena(this);
            }
        }

        private boolean search()
        {
            CaptureThreadQueue runQueue = q0;
            CaptureThreadQueue nextQueue = q1;
            runQueue.clear();
            nextQueue.clear();
            // A folded run behind a lead of varying width answers the prefix scans after its
            // result up to the lead's spread, as Prog.prefixAccel describes, so the search skips
            // them. A plain prefix, with no spread, never tests or writes the bound.
            int prefixLeadSpread = prog.prefixAccelMaximumLead() - prog.prefixAccelMinimumLead();
            int prefixScanCoversThrough = -1;

            // Loop over the text, stepping the machine.
            // position is an absolute index into the underlying byte[]; like upstream, we run one step past textEnd.
            for (int position = textBegin; ; position++) {
                int c = (position < textEnd) ? (bytes[position] & 0xFF) : -1;

                int shortcut = step(runQueue, nextQueue, c, position);

                // swap(runQueue, nextQueue)
                CaptureThreadQueue temporary = runQueue;
                runQueue = nextQueue;
                nextQueue = temporary;
                nextQueue.clear();

                if (shortcut != 0) {
                    // We're done: full match ahead.
                    position = textEnd;
                    int id = shortcut;
                    for (; ; ) {
                        long instructionWord = instructionWords[id];
                        switch ((int) (instructionWord & 0b111)) {
                            case OP_CAPTURE -> {
                                int captureIndex = (int) (instructionWord >>> 32);
                                if (captureIndex < ncapture) {
                                    match[captureIndex] = position;
                                }
                                id = (int) ((instructionWord >>> 4) & OUTPUT_MASK);
                            }
                            case OP_NOP -> id = (int) ((instructionWord >>> 4) & OUTPUT_MASK);
                            case OP_MATCH -> {
                                match[1] = position;
                                matched = true;
                                id = 0;
                            }
                            default -> {
                                // Unexpected opcode in short-circuit path.
                                id = 0;
                            }
                        }
                        if (id == 0) {
                            break;
                        }
                    }
                    break;
                }

                if (position > textEnd) {
                    break;
                }

                // Start a new thread if there have not been any matches.
                if (!matched && (!anchored || position == textBegin)) {
                    if (!anchored && runQueue.isEmpty() && position < textEnd && prog.canPrefixAccel() &&
                            (prefixLeadSpread == 0 || position > prefixScanCoversThrough)) {
                        int found = prog.prefixAccel(bytes, position, textEnd - position);
                        if (found < 0) {
                            position = textEnd;
                        }
                        else {
                            if (prefixLeadSpread != 0 && found > position) {
                                prefixScanCoversThrough = found + prefixLeadSpread;
                            }
                            position = found;
                        }
                        c = (position < textEnd) ? (bytes[position] & 0xFF) : -1;
                    }

                    int t = allocThread();
                    copyCapture(threadCapture[t], match);
                    threadCapture[t][0] = position;
                    addToThreadq(runQueue, start, c, position, t);
                    decref(t);
                }

                if (runQueue.isEmpty()) {
                    break;
                }
            }

            // Free remaining threads.
            for (int i = 0; i < runQueue.size(); i++) {
                int t = runQueue.threadAt(i);
                if (t != 0) {
                    decref(t);
                }
            }
            runQueue.clear();

            return matched;
        }

        private void writeSubmatch(int[] out)
        {
            for (int i = 0; i < submatchCount; i++) {
                writePair(out, 2 * i, 2 * i);
            }
        }

        // Writes group zero and a bounded pass's window of slots [firstSlot, endSlot), which the
        // pass tracked from slot 2.
        private void writeSubmatchWindow(int[] out, int firstSlot, int endSlot)
        {
            writePair(out, 0, 0);
            for (int slot = firstSlot; slot < endSlot; slot += 2) {
                writePair(out, slot, slot - firstSlot + 2);
            }
        }

        private void writePair(int[] out, int outSlot, int matchSlot)
        {
            int a = match[matchSlot];
            int b = match[matchSlot + 1];
            if (a < 0 || b < 0) {
                out[outSlot] = -1;
                out[outSlot + 1] = -1;
            }
            else {
                out[outSlot] = a - textBegin;
                out[outSlot + 1] = b - textBegin;
            }
        }

        private boolean matchesGroupZero(int[] out)
        {
            return matched && match[0] - textBegin == out[0] && match[1] - textBegin == out[1];
        }

        private int step(CaptureThreadQueue runQueue, CaptureThreadQueue nextQueue, int c, int position)
        {
            nextQueue.clear();

            for (int it = 0; it < runQueue.size(); it++) {
                int t = runQueue.threadAt(it);
                if (t == 0) {
                    continue;
                }

                if (longest) {
                    if (matched && match[0] >= 0 && match[0] < threadCapture[t][0]) {
                        decref(t);
                        continue;
                    }
                }

                int id = runQueue.instructionIdAt(it);
                long instructionWord = instructionWords[id];
                switch ((int) (instructionWord & 0b111)) {
                    case OP_BYTE_RANGE -> addToThreadq(nextQueue, (int) ((instructionWord >>> 4) & OUTPUT_MASK), c, position, t);
                    case OP_ALT_MATCH -> {
                        if (it != 0) {
                            break;
                        }
                        int payload = (int) (instructionWord >>> 32);
                        boolean greedy = payload < 0;
                        if (greedy || longest) {
                            copyCapture(match, threadCapture[t]);
                            matched = true;

                            decref(t);
                            for (int j = it + 1; j < runQueue.size(); j++) {
                                int other = runQueue.threadAt(j);
                                if (other != 0) {
                                    decref(other);
                                }
                            }
                            runQueue.clear();
                            if (greedy) {
                                return payload & (int) OUTPUT_MASK;
                            }
                            return (int) ((instructionWord >>> 4) & OUTPUT_MASK);
                        }
                    }
                    case OP_MATCH -> {
                        if (endMatch && (position - 1) != textEnd) {
                            break;
                        }

                        if (longest) {
                            int startPos = threadCapture[t][0];
                            int endPos = position - 1;
                            if (!matched ||
                                    startPos < match[0] ||
                                    (startPos == match[0] && endPos > match[1])) {
                                copyCapture(match, threadCapture[t]);
                                match[1] = endPos;
                                matched = true;
                            }
                        }
                        else {
                            copyCapture(match, threadCapture[t]);
                            match[1] = position - 1;
                            matched = true;

                            decref(t);
                            for (int j = it + 1; j < runQueue.size(); j++) {
                                int other = runQueue.threadAt(j);
                                if (other != 0) {
                                    decref(other);
                                }
                            }
                            runQueue.clear();
                            return 0;
                        }
                    }
                    default -> {
                        // Unexpected in flattened programs (runQueue should contain only ByteRange/AltMatch/Match).
                    }
                }

                decref(t);
            }
            runQueue.clear();
            return 0;
        }

        @SuppressFBWarnings(value = "SF_SWITCH_NO_DEFAULT", justification = "The switch exhaustively handles encoded instruction opcodes")
        private void addToThreadq(CaptureThreadQueue queue, int id0, int c, int position, int t0)
        {
            if (id0 == 0) {
                return;
            }

            int stackPointer = 0;
            addIdStack[stackPointer] = id0;
            addRestoreThreadStack[stackPointer] = 0;
            stackPointer++;

            while (stackPointer > 0) {
                stackPointer--;
                int id = addIdStack[stackPointer];
                int restore = addRestoreThreadStack[stackPointer];

                if (restore != 0) {
                    decref(t0);
                    t0 = restore;
                }

                for (; ; ) {
                    if (id == 0) {
                        break;
                    }

                    int queuePosition = queue.markVisitedIfAbsent(id);
                    if (queuePosition < 0) {
                        break;
                    }
                    long instructionWord = instructionWords[id];
                    int output = (int) ((instructionWord >>> 4) & OUTPUT_MASK);
                    boolean last = (instructionWord & (1L << 3)) != 0;
                    int payload = (int) (instructionWord >>> 32);

                    switch ((int) (instructionWord & 0b111)) {
                        case OP_FAIL -> {
                            id = 0;
                        }
                        case OP_ALT_MATCH -> {
                            queue.setThreadAt(queuePosition, incref(t0));
                            if (last) {
                                id = 0;
                                break;
                            }
                            id = id + 1;
                        }
                        case OP_NOP -> {
                            if (!last) {
                                pushAddState(id + 1, 0, stackPointer++);
                            }
                            id = output;
                        }
                        case OP_CAPTURE -> {
                            if (!last) {
                                pushAddState(id + 1, 0, stackPointer++);
                            }

                            if (payload < ncapture) {
                                // Push a dummy whose only job is to restore t0.
                                pushAddState(0, t0, stackPointer++);

                                int t = allocThread();
                                copyCapture(threadCapture[t], threadCapture[t0]);
                                threadCapture[t][payload] = position;
                                t0 = t;
                            }

                            id = output;
                        }
                        case OP_BYTE_RANGE -> {
                            int value = c;
                            if (payload < 0 && 'A' <= value && value <= 'Z') {
                                value += 'a' - 'A';
                            }
                            int lowerBound = payload & 0xFF;
                            int upperBound = (payload >>> 8) & 0xFF;
                            if (value < lowerBound || value > upperBound) {
                                // Does not match; fall through to next in the list.
                                if (last) {
                                    id = 0;
                                }
                                else {
                                    id = id + 1;
                                }
                                break;
                            }

                            queue.setThreadAt(queuePosition, incref(t0));
                            int hint = (payload >>> 16) & 0x7FFF;
                            if (hint == 0) {
                                id = 0;
                                break;
                            }
                            id = id + hint;
                        }
                        case OP_MATCH -> {
                            queue.setThreadAt(queuePosition, incref(t0));
                            if (last) {
                                id = 0;
                            }
                            else {
                                id = id + 1;
                            }
                        }
                        case OP_EMPTY_WIDTH -> {
                            if (!last) {
                                pushAddState(id + 1, 0, stackPointer++);
                            }

                            int empty = emptyFlags(position);
                            if ((payload & ~empty) == 0) {
                                id = output;
                            }
                            else {
                                id = 0;
                            }
                        }
                        case OP_ALT -> {
                            // Unreachable in flattened programs but safe to handle.
                            pushAddState(payload & (int) OUTPUT_MASK, 0, stackPointer++);
                            id = output;
                        }
                    }
                    if (id == 0) {
                        break;
                    }
                }
            }
        }

        private void pushAddState(int id, int restoreThread, int pos)
        {
            if (pos >= addIdStack.length) {
                throw new IllegalStateException("AddToThreadq stack overflow (pos=" + pos + " capacity=" + addIdStack.length + ")");
            }
            addIdStack[pos] = id;
            addRestoreThreadStack[pos] = restoreThread;
        }

        private int allocThread()
        {
            int id = freeList;
            if (id != 0) {
                freeList = threadNext[id];
                threadRef[id] = 1;
                return id;
            }

            id = ++threadCount;
            ensureThreadCapacity(id);
            threadRef[id] = 1;
            if (threadCapture[id] == null || threadCapture[id].length != ncapture) {
                threadCapture[id] = new int[ncapture];
            }
            return id;
        }

        private void ensureThreadCapacity(int id)
        {
            if (id < threadRef.length) {
                return;
            }
            int newLen = threadRef.length;
            while (newLen <= id) {
                newLen *= 2;
            }
            threadRef = Arrays.copyOf(threadRef, newLen);
            threadNext = Arrays.copyOf(threadNext, newLen);
            threadCapture = Arrays.copyOf(threadCapture, newLen);
        }

        private int incref(int t)
        {
            threadRef[t]++;
            return t;
        }

        private void decref(int t)
        {
            threadRef[t]--;
            if (threadRef[t] > 0) {
                return;
            }
            threadNext[t] = freeList;
            freeList = t;
        }

        private void copyCapture(int[] dst, int[] src)
        {
            System.arraycopy(src, 0, dst, 0, ncapture);
        }

        private int emptyFlags(int position)
        {
            int flags = EmptyOp.contextFlags(bytes, ctxBegin, ctxEnd, position, prog.textDependentAssertions());
            if (carriageReturnBeforeContext) {
                flags &= ~EmptyOp.javaEndFlagsRefusedAfterCarriageReturn(bytes, ctxBegin, ctxEnd, position);
            }
            return flags;
        }
    }
}
