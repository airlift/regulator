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

import static io.airlift.slice.SizeOf.instanceSize;
import static io.airlift.slice.SizeOf.sizeOfIntArray;
import static io.airlift.slice.SizeOf.sizeOfLongArray;
import static io.airlift.slice.SizeOf.sizeOfObjectArray;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

final class BitState
{
    private BitState() {}

    // The deepest job stack on everyday capture searches is about ten jobs, so a search usually
    // starts with sixteen and grows the stack on a deeper one, within the memory model; see
    // initialJobCapacity and nextJobCapacity.
    private static final int INITIAL_JOB_CAPACITY = 16;
    private static final long JOB_BYTES = instanceSize(Job.class);
    // The search, its workspace, and the Slice view of the context that a region search passes.
    private static final long SEARCH_BYTES = (long) instanceSize(BitStateImpl.class) + instanceSize(Workspace.class) + instanceSize(Slice.class);

    /// Returns the jobs a search may push for each position of its text.
    ///
    /// The visited bitmap lets a search enter each list at each position once, across all of its
    /// start positions. An entry walks the list's instructions in order, each at most once: a
    /// popped job resumes the walk past the instruction that pushed it, and ALT_MATCH only jumps
    /// ahead in the list. On that walk a BYTE_RANGE with a hint, or a CAPTURE, EMPTY_WIDTH, or
    /// NOP that is not last, pushes one job, and a CAPTURE pushes one more to undo itself. So a
    /// start position's stack holds at most its own job and these pushes for each of the text's
    /// positions.
    static int jobsPerPosition(Prog prog)
    {
        int jobsPerPosition = 0;
        for (int id = 0; id < prog.size(); id++) {
            Prog.Inst instruction = prog.inst(id);
            switch (instruction.opcode()) {
                case BYTE_RANGE -> jobsPerPosition += instruction.hint() != 0 ? 1 : 0;
                case CAPTURE -> jobsPerPosition += instruction.last() ? 1 : 2;
                case EMPTY_WIDTH, NOP -> jobsPerPosition += instruction.last() ? 0 : 1;
                default -> {}
            }
        }
        return jobsPerPosition;
    }

    /// Returns the longest text, at most `textMaxSize`, whose search keeps its visited bitmap,
    /// capture slots, job stack, and objects within `allowance` bytes, or -1 when no text fits.
    /// The job stack counts as [#modelJobStackBytes].
    static int maxTextLength(Prog prog, int textMaxSize, long allowance)
    {
        // A search tracks at most the program's capture slots, whatever the caller's buffer.
        int slotCount = prog.captureSlotCount();
        if (searchBytes(prog, slotCount, textMaxSize) <= allowance) {
            return textMaxSize;
        }
        int low = -1;
        int high = textMaxSize;
        while (low < high - 1) {
            int middle = (low + high) >>> 1;
            if (searchBytes(prog, slotCount, middle) <= allowance) {
                low = middle;
            }
            else {
                high = middle;
            }
        }
        return low;
    }

    static long searchBytes(Prog prog, int slotCount, int textLength)
    {
        long positions = textLength + 1L;
        long visitedWords = (prog.listCount() * positions + 63) / 64;
        long stackBytes = modelJobStackBytes(prog, textLength);
        if (stackBytes == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return SEARCH_BYTES + sizeOfLongArray((int) visitedWords) + sizeOfIntArray(slotCount) + stackBytes;
    }

    /// Returns the job stack bytes that the memory model counts for a search over `textLength`
    /// bytes: each array of a stack that doubles from [#modelJobCapacity] until it holds the
    /// search's maximum job count, and a job for each slot of the last. Unlike the capture NFA's
    /// plan, which counts the memory live at a search's peak, this counts the search's allocation
    /// total, which its allocation test holds to the share with no slack. The search's own stack
    /// grows within it; see [#nextJobCapacity].
    static long modelJobStackBytes(Prog prog, int textLength)
    {
        long jobs = maxJobCount(prog, textLength);
        long capacity = modelJobCapacity(prog.size());
        long stackBytes = sizeOfObjectArray((int) capacity);
        while (capacity < jobs) {
            capacity *= 2;
            if (capacity > Integer.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            stackBytes += sizeOfObjectArray((int) capacity);
        }
        return stackBytes + capacity * JOB_BYTES;
    }

    /// Returns the job stack bytes a fresh search over `textLength` bytes allocates when its
    /// stack grows to the search's maximum job count.
    static long jobStackBytesForTesting(Prog prog, int textLength)
    {
        long jobs = maxJobCount(prog, textLength);
        int capacity = initialJobCapacity(prog, textLength);
        long stackBytes = sizeOfObjectArray(capacity);
        while (capacity < jobs) {
            capacity = nextJobCapacity(prog, textLength, capacity);
            stackBytes += sizeOfObjectArray(capacity);
        }
        return stackBytes + capacity * JOB_BYTES;
    }

    /// Returns the capacity the memory model's job stack starts at, which sets the text limits.
    static int modelJobCapacity(int programSize)
    {
        return Math.max(16, Math.min(64, programSize));
    }

    /// Returns the capacity a search's job stack starts at: sixteen, or `max(16, maximum)` when
    /// the search's maximum job count fits the model's first stack. Growing from sixteen to such a
    /// maximum would allocate the sixteen-job array beside the maximum's, more than the model's one
    /// array.
    private static int initialJobCapacity(Prog prog, int textLength)
    {
        long maximum = maxJobCount(prog, textLength);
        return maximum <= modelJobCapacity(prog.size()) ? toIntExact(Math.max(INITIAL_JOB_CAPACITY, maximum)) : INITIAL_JOB_CAPACITY;
    }

    /// Returns the most jobs a search over `textLength` bytes holds at once; see [#jobsPerPosition].
    private static long maxJobCount(Prog prog, int textLength)
    {
        return 1 + (textLength + 1L) * prog.bitStateJobsPerPosition();
    }

    /// Returns the length a full job stack of `length` jobs grows to, in a search over
    /// `textLength` bytes that holds at most `maximum` jobs, its [#maxJobCount], and whose memory
    /// model starts at `modelCapacity`, its [#modelJobCapacity]. The stack doubles, and takes the
    /// maximum once the doubled array would pass `(maximum - modelCapacity) / 2`, so it holds at
    /// most the maximum's jobs, no more than the model's last stack. A stack already full at the
    /// maximum, which the maximum's bound rules out, still doubles, as the stack did before,
    /// rather than overrunning its array.
    private static int nextJobCapacity(Prog prog, int textLength, int length)
    {
        long maximum = maxJobCount(prog, textLength);
        int modelCapacity = modelJobCapacity(prog.size());
        long doubled = 2L * length;
        if (length < maximum && doubled > (maximum - modelCapacity) / 2) {
            // The model's arrays total at least 2 * maximum - modelCapacity slots. Below
            // modelCapacity + 32 jobs, sixteen jumps straight to the maximum. From there on, the
            // last doubled array is at most (maximum - modelCapacity) / 2, so with the smaller
            // arrays before it and the maximum's, the stack's arrays total at most 2 * maximum -
            // modelCapacity - 16 slots, which leaves 16 slots for the headers of the extra arrays.
            return toIntExact(maximum);
        }
        return toIntExact(doubled);
    }

    // PERFORMANCE-SENSITIVE HOT LOOPS: job-stack layout, visited-state checks, and capture
    // handling are benchmark-sensitive. Do not perform readability-only refactors below without
    // direct path tests and focused Intel and Graviton benchmarks.

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
        requireNonNull(prog, "prog is null");
        requireNonNull(context, "context is null");
        requireNonNull(matchKind, "matchKind is null");

        if (!prog.canBitState()) {
            return false;
        }
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

        if (prog.anchorStart() && ctxBegin != textBegin) {
            return false;
        }
        if (prog.anchorEnd() && ctxEnd != textEnd) {
            return false;
        }

        boolean anchoredSearch = anchored || prog.anchorStart() || matchKind == Prog.MatchKind.FULL_MATCH;
        boolean longestSearch = matchKind != Prog.MatchKind.FIRST_MATCH || prog.anchorEnd();
        boolean endMatch = prog.anchorEnd() || matchKind == Prog.MatchKind.FULL_MATCH;

        int submatchCount = (submatch == null) ? 0 : (submatch.length / 2);
        if (submatchCount > 0) {
            for (int i = 0; i < submatch.length; i++) {
                submatch[i] = -1;
            }
        }

        BitStateImpl b = new BitStateImpl(
                prog,
                context,
                textBegin,
                textEnd,
                anchoredSearch,
                longestSearch,
                endMatch,
                submatch,
                submatchCount,
                workspace);
        return b.search();
    }

    public static boolean search(Prog prog, Slice text, boolean anchored, Prog.MatchKind matchKind, int[] submatch)
    {
        return search(prog, text, 0, text.length(), anchored, matchKind, submatch);
    }

    private static final class Job
    {
        int id;
        int runLength;
        int position;
    }

    static final class Workspace
    {
        private long[] visited;
        private int[] captures;
        private Job[] jobs;

        private long[] prepareVisited(int wordCount)
        {
            if (visited == null || visited.length < wordCount) {
                visited = new long[wordCount];
            }
            else {
                Arrays.fill(visited, 0, wordCount, 0);
            }
            return visited;
        }

        private int[] prepareCaptures(int slotCount)
        {
            if (captures == null || captures.length < slotCount) {
                captures = new int[slotCount];
            }
            Arrays.fill(captures, 0, slotCount, -1);
            return captures;
        }

        private Job[] prepareJobs(int initialCapacity)
        {
            if (jobs == null) {
                jobs = BitStateImpl.createJobs(initialCapacity);
            }
            return jobs;
        }

        private void updateJobs(Job[] jobs)
        {
            this.jobs = jobs;
        }
    }

    private static final class BitStateImpl
    {
        private static final int VISITED_BITS = 64;
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
        private final Slice context;
        private final boolean anchored;
        private final boolean longest;
        private final boolean endMatch;
        private final int[] submatch;
        // The pairs the search tracks: the caller's, up to the program's capture slots. The pairs
        // past them keep the -1 that the static search filled in.
        private final int submatchCount;

        private final byte[] bytes;
        private final int textBegin;
        private final int textEnd;

        private final short[] outputLists;
        // The list of prog.start(), where every attempt begins.
        private final int startList;
        private final long[] visited;
        private final int[] cap;
        private final Workspace workspace;

        private Job[] jobs;
        private int jobCount;

        private BitStateImpl(
                Prog prog,
                Slice context,
                int textBegin,
                int textEnd,
                boolean anchored,
                boolean longest,
                boolean endMatch,
                int[] submatch,
                int submatchCount,
                Workspace workspace)
        {
            this.prog = requireNonNull(prog, "prog is null");
            this.context = requireNonNull(context, "context is null");
            this.anchored = anchored;
            this.longest = longest;
            this.endMatch = endMatch;
            this.submatch = submatch;
            this.submatchCount = Math.min(submatchCount, prog.captureSlotCount() / 2);
            this.workspace = workspace;

            this.bytes = context.byteArray();
            this.textBegin = textBegin;
            this.textEnd = textEnd;

            this.outputLists = requireNonNull(prog.outputLists(), "prog.outputLists() is null");
            this.startList = prog.startList();

            int visitedBitCount = prog.listCount() * ((textEnd - textBegin) + 1);
            int visitedWordCount = (visitedBitCount + VISITED_BITS - 1) / VISITED_BITS;
            int captureSlotCount = Math.max(2, 2 * this.submatchCount);
            if (workspace == null) {
                this.visited = new long[visitedWordCount];
                this.cap = new int[captureSlotCount];
                Arrays.fill(this.cap, -1);
                this.jobs = createJobs(initialJobCapacity(prog, textEnd - textBegin));
            }
            else {
                this.visited = workspace.prepareVisited(visitedWordCount);
                this.cap = workspace.prepareCaptures(captureSlotCount);
                this.jobs = workspace.prepareJobs(initialJobCapacity(prog, textEnd - textBegin));
            }
            this.jobCount = 0;
        }

        private static Job[] createJobs(int capacity)
        {
            Job[] jobs = new Job[capacity];
            for (int i = 0; i < jobs.length; i++) {
                jobs[i] = new Job();
            }
            return jobs;
        }

        private boolean search()
        {
            // Anchored search must start at text.begin().
            if (anchored) {
                cap[0] = textBegin;
                return trySearch(textBegin);
            }

            // Unanchored search from each possible text position.
            for (int position = textBegin; position <= textEnd; position++) {
                if (position < textEnd && prog.canPrefixAccel()) {
                    int found = prog.prefixAccel(bytes, position, textEnd - position);
                    if (found < 0) {
                        position = textEnd;
                    }
                    else {
                        position = found;
                    }
                }

                cap[0] = position;
                if (trySearch(position)) {
                    return true;
                }
            }

            return false;
        }

        private static boolean shouldVisit(int textLen, long[] visited, int listId, int positionOffset)
        {
            int visitedIndex = listId * (textLen + 1) + positionOffset;
            int word = visitedIndex / VISITED_BITS;
            long bit = 1L << (visitedIndex & (VISITED_BITS - 1));
            if ((visited[word] & bit) != 0) {
                return false;
            }
            visited[word] |= bit;
            return true;
        }

        private void push(int id, int position)
        {
            if (jobCount >= jobs.length) {
                growStack();
            }

            if (id >= 0 && jobCount > 0) {
                Job top = jobs[jobCount - 1];
                if (id == top.id &&
                        position == top.position + top.runLength + 1 &&
                        top.runLength < Integer.MAX_VALUE) {
                    top.runLength++;
                    return;
                }
            }

            Job top = jobs[jobCount++];
            top.id = id;
            top.runLength = 0;
            top.position = position;
        }

        private void growStack()
        {
            int oldLength = jobs.length;
            jobs = Arrays.copyOf(jobs, nextJobCapacity(prog, textEnd - textBegin, oldLength));
            for (int i = oldLength; i < jobs.length; i++) {
                jobs[i] = new Job();
            }
            if (workspace != null) {
                workspace.updateJobs(jobs);
            }
        }

        @SuppressFBWarnings(value = "SF_SWITCH_NO_DEFAULT", justification = "The switch exhaustively handles encoded instruction opcodes")
        private boolean trySearch(int startPosition)
        {
            long[] instructionWords = prog.getOrCreateCaptureInstructionWords();
            short[] outputLists = this.outputLists;
            boolean matched = false;
            jobCount = 0;

            int textLen = textEnd - textBegin;
            if (shouldVisit(textLen, visited, startList, startPosition - textBegin)) {
                push(prog.start(), startPosition);
            }

            while (jobCount > 0) {
                Job job = jobs[--jobCount];
                int id = job.id;
                int runLength = job.runLength;
                int position = job.position;

                if (id < 0) {
                    // Undo the Capture.
                    int capIndex = (int) (instructionWords[-id] >>> 32);
                    if (0 <= capIndex && capIndex < cap.length) {
                        cap[capIndex] = position;
                    }
                    continue;
                }

                if (runLength > 0) {
                    position += runLength;
                    job.runLength = runLength - 1;
                    jobCount++;
                }

                while (true) {
                    long instructionWord = instructionWords[id];
                    int output = (int) ((instructionWord >>> 4) & OUTPUT_MASK);
                    boolean last = (instructionWord & (1L << 3)) != 0;
                    int payload = (int) (instructionWord >>> 32);
                    switch ((int) (instructionWord & 0b111)) {
                        case OP_FAIL -> {
                            id = 0;
                        }
                        case OP_ALT_MATCH -> {
                            if (payload < 0) {
                                id = payload & (int) OUTPUT_MASK;
                                position = textEnd;
                                continue;
                            }
                            if (longest) {
                                id = output;
                                position = textEnd;
                                continue;
                            }
                            id = nextInList(last, id);
                        }
                        case OP_BYTE_RANGE -> {
                            int c = (position < textEnd) ? (bytes[position] & 0xFF) : -1;
                            if (payload < 0 && 'A' <= c && c <= 'Z') {
                                c += 'a' - 'A';
                            }
                            int lowerBound = payload & 0xFF;
                            int upperBound = (payload >>> 8) & 0xFF;
                            if (c < lowerBound || c > upperBound) {
                                id = nextInList(last, id);
                                break;
                            }

                            int hint = (payload >>> 16) & 0x7FFF;
                            if (hint != 0) {
                                push(id + hint, position);
                            }
                            int outputList = outputLists[id];
                            id = output;
                            position++;
                            if (!checkAndLoop(textLen, id, outputList, position)) {
                                id = 0;
                            }
                        }
                        case OP_CAPTURE -> {
                            if (!last) {
                                push(id + 1, position);
                            }
                            if (0 <= payload && payload < cap.length) {
                                push(-id, cap[payload]);
                                cap[payload] = position;
                            }
                            int outputList = outputLists[id];
                            id = output;
                            if (!checkAndLoop(textLen, id, outputList, position)) {
                                id = 0;
                            }
                        }
                        case OP_EMPTY_WIDTH -> {
                            int empty = EmptyOp.contextFlags(context, position, payload);
                            if ((payload & ~empty) != 0) {
                                id = nextInList(last, id);
                                break;
                            }
                            if (!last) {
                                push(id + 1, position);
                            }
                            int outputList = outputLists[id];
                            id = output;
                            if (!checkAndLoop(textLen, id, outputList, position)) {
                                id = 0;
                            }
                        }
                        case OP_NOP -> {
                            if (!last) {
                                push(id + 1, position);
                            }
                            int outputList = outputLists[id];
                            id = output;
                            if (!checkAndLoop(textLen, id, outputList, position)) {
                                id = 0;
                            }
                        }
                        case OP_MATCH -> {
                            if (endMatch && position != textEnd) {
                                id = nextInList(last, id);
                                break;
                            }

                            if (submatchCount == 0) {
                                return true;
                            }

                            matched = true;
                            cap[1] = position;

                            boolean haveBest = submatch[0] >= 0;
                            int bestEnd = haveBest ? (textBegin + submatch[1]) : -1;
                            if (!haveBest || (longest && position > bestEnd)) {
                                for (int i = 0; i < submatchCount; i++) {
                                    int a = cap[2 * i];
                                    int b = cap[2 * i + 1];
                                    int o = 2 * i;
                                    if (a < 0 || b < 0) {
                                        submatch[o] = -1;
                                        submatch[o + 1] = -1;
                                    }
                                    else {
                                        submatch[o] = a - textBegin;
                                        submatch[o + 1] = b - textBegin;
                                    }
                                }
                            }

                            if (!longest) {
                                return true;
                            }
                            if (position == textEnd) {
                                return true;
                            }

                            id = nextInList(last, id);
                        }
                        case OP_ALT -> {
                            // Unreachable in flattened programs.
                            id = 0;
                        }
                    }

                    if (id == 0) {
                        break;
                    }
                }
            }

            return matched;
        }

        private int nextInList(boolean last, int id)
        {
            if (!last) {
                return id + 1;
            }
            return 0;
        }

        private boolean checkAndLoop(int textLen, int id, int listId, int p)
        {
            if (id == 0) {
                return false;
            }

            return shouldVisit(textLen, visited, listId, p - textBegin);
        }
    }
}
