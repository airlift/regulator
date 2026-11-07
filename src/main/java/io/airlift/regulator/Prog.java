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
import io.airlift.slice.Slices;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

@SuppressFBWarnings(
        value = {"AT_NONATOMIC_64BIT_PRIMITIVE", "AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE", "AT_STALE_THREAD_WRITE_OF_PRIMITIVE"},
        justification = "Prog is mutated during single-threaded compilation and safely published before matching")
final class Prog
{
    private static final long BIT_STATE_BITMAP_MAXIMUM_BYTES = 256L * 1024L;
    // The most instructions a flattened program may hold: a packed capture instruction word keeps
    // an output in 28 bits, as upstream's `Prog::Inst` does.
    static final int MAX_FLATTENED_INSTRUCTIONS = (1 << 28) - 1;
    private static final int UPSTREAM_LIST_HEAD_MAXIMUM_INSTRUCTIONS = 512;
    static final int FUSED_PREFIX_VECTOR_MINIMUM_BYTES = 1024;

    public enum MatchKind
    {
        FIRST_MATCH,
        LONGEST_MATCH,
        FULL_MATCH,
        MANY_MATCH,
    }

    enum PrefixAccelStrategy
    {
        REPEATED_BYTE,
        FUSED_SWAR,
        FUSED_VECTOR,
    }

    // VarHandles for SWAR byte scanning (8-byte and 4-byte at a time)
    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final int MAXIMUM_FIXED_DISTANCE_BYTE_OFFSET = 16;
    // Bounds the work of the start literal filter's path walk, before paths merge into groups.
    private static final int MAXIMUM_START_POSITION_PATHS = 128;
    // The literal search needs at least two bytes; a one-byte literal keeps ordinary matching.
    private static final int MINIMUM_TRANSPARENT_START_LITERAL_BYTES = 2;
    // Each overlapping occurrence of a longer literal re-reads it, as in InnerLiteralSearch.
    private static final int MAXIMUM_TRANSPARENT_START_LITERAL_BYTES = 64;

    private static final int[] WORD_RANGES = {48, 57, 65, 90, 95, 95, 97, 122};
    private static final int[] NON_WORD_RANGES = {0, 47, 58, 64, 91, 94, 96, 96, 123, 255};

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP",
            justification = "Slice values are deliberate zero-copy result views")
    public record PossibleMatchRangeResult(Slice min, Slice max) {}

    record FixedDistanceByteCandidates(byte[] candidates, int offset) {}

    // Single instruction in regexp program.
    public static final class Inst
    {
        private InstOp opcode;

        private int out;
        private int out1;

        private int lo;
        private int hi;
        private int hintFoldCase;

        private int cap;
        private int empty;
        private int matchId;

        private boolean last;

        private Inst(InstOp opcode)
        {
            this.opcode = requireNonNull(opcode, "opcode is null");
        }

        public static Inst createAlt(int out, int out1)
        {
            Inst inst = new Inst(InstOp.ALT);
            inst.out = out;
            inst.out1 = out1;
            return inst;
        }

        public static Inst createAltMatch(int out, int out1)
        {
            Inst inst = new Inst(InstOp.ALT_MATCH);
            inst.out = out;
            inst.out1 = out1;
            return inst;
        }

        public static Inst createByteRange(int lo, int hi, boolean foldCase, int out)
        {
            Inst inst = new Inst(InstOp.BYTE_RANGE);
            inst.lo = lo & 0xFF;
            inst.hi = hi & 0xFF;
            inst.hintFoldCase = foldCase ? 1 : 0;
            inst.out = out;
            return inst;
        }

        public static Inst createCapture(int cap, int out)
        {
            Inst inst = new Inst(InstOp.CAPTURE);
            inst.cap = cap;
            inst.out = out;
            return inst;
        }

        public static Inst createEmptyWidth(int empty, int out)
        {
            Inst inst = new Inst(InstOp.EMPTY_WIDTH);
            inst.empty = empty;
            inst.out = out;
            return inst;
        }

        public static Inst createMatch(int matchId)
        {
            Inst inst = new Inst(InstOp.MATCH);
            inst.matchId = matchId;
            return inst;
        }

        public static Inst createNop(int out)
        {
            Inst inst = new Inst(InstOp.NOP);
            inst.out = out;
            return inst;
        }

        public static Inst createFail()
        {
            return new Inst(InstOp.FAIL);
        }

        public InstOp opcode()
        {
            return opcode;
        }

        public int out()
        {
            return out;
        }

        public void setOut(int out)
        {
            this.out = out;
        }

        public int out1()
        {
            return out1;
        }

        public void setOut1(int out1)
        {
            this.out1 = out1;
        }

        public void setOpcode(InstOp opcode)
        {
            this.opcode = requireNonNull(opcode, "opcode is null");
        }

        public void setEmpty(int empty)
        {
            this.empty = empty;
        }

        Inst copy()
        {
            Inst inst = new Inst(opcode);
            inst.out = out;
            inst.out1 = out1;
            inst.lo = lo;
            inst.hi = hi;
            inst.hintFoldCase = hintFoldCase;
            inst.cap = cap;
            inst.empty = empty;
            inst.matchId = matchId;
            inst.last = last;
            return inst;
        }

        public int lo()
        {
            return lo;
        }

        public int hi()
        {
            return hi;
        }

        public boolean foldCase()
        {
            return (hintFoldCase & 1) != 0;
        }

        public int hint()
        {
            return hintFoldCase >>> 1;
        }

        public int cap()
        {
            return cap;
        }

        public int empty()
        {
            return empty;
        }

        public int matchId()
        {
            return matchId;
        }

        public boolean last()
        {
            return last;
        }

        public void setLast()
        {
            this.last = true;
        }

        public boolean matches(int c)
        {
            if (opcode != InstOp.BYTE_RANGE) {
                throw new IllegalStateException("not a byte range inst: " + opcode);
            }
            // Upstream Inst::Matches() expects an int byte value. The DFA uses a sentinel
            // "end text" byte (256) and NFA-style engines use -1 for "no byte".
            // Both must never match a byte range.
            if (c < 0 || c > 0xFF) {
                return false;
            }
            int b = c;
            if (foldCase() && 'A' <= b && b <= 'Z') {
                b += 'a' - 'A';
            }
            return lo <= b && b <= hi;
        }

        public boolean greedy(Prog p)
        {
            requireNonNull(p, "p is null");
            if (opcode != InstOp.ALT_MATCH) {
                throw new IllegalStateException("not an altmatch inst: " + opcode);
            }

            Inst outInst = p.inst(out());
            if (outInst.opcode() == InstOp.BYTE_RANGE) {
                return true;
            }
            return outInst.opcode() == InstOp.NOP &&
                    p.inst(outInst.out()).opcode() == InstOp.BYTE_RANGE;
        }

        public String dump()
        {
            return switch (opcode) {
                case ALT -> "alt -> " + out + " | " + out1;
                case ALT_MATCH -> "altmatch -> " + out + " | " + out1;
                case BYTE_RANGE -> "byte" +
                        (foldCase() ? "/i" : "") +
                        " [" + hex2(lo) + "-" + hex2(hi) + "] " +
                        hint() + " -> " + out;
                case CAPTURE -> "capture " + cap + " -> " + out;
                case EMPTY_WIDTH -> "emptywidth " + hashHex(empty) + " -> " + out;
                case MATCH -> "match! " + matchId;
                case NOP -> "nop -> " + out;
                case FAIL -> "fail";
            };
        }

        private static String hex2(int v)
        {
            return String.format(Locale.ROOT, "%02x", v & 0xFF);
        }

        private static String hashHex(int v)
        {
            // C's "%#x" formatting: 0 => "0", otherwise "0x..." (lowercase).
            if (v == 0) {
                return "0";
            }
            return "0x" + Integer.toHexString(v);
        }
    }

    private Inst[] insts = new Inst[16];
    private int instructionCount;

    private boolean didFlatten;
    private int start;
    private int startUnanchored;

    private boolean anchorStart;
    private boolean anchorEnd;
    private boolean canMatchEmpty;
    private boolean hasEmptyWidthAssertions;
    private int textDependentAssertions;
    private boolean hasFullCaseFold;

    /// Whether this program is compiled for reverse (right-to-left) search.
    ///
    /// When `true`:
    /// - [io.airlift.regulator.Dfa#search] scans bytes right-to-left (backward)
    /// - Anchor flags are swapped from pattern semantics: `^` becomes `$`, `$` becomes `^`
    /// - Used in Phase 2 of two-phase search to find match start after forward DFA found end
    /// - Memory budget typically 1/3 of total (vs 2/3 for forward)
    ///
    /// **Two-Phase Search Pattern:**
    /// 1. Forward program (`reversed=false`) finds match end
    /// 2. Reverse program (`reversed=true`) finds match start by searching backward
    ///
    /// See `REVERSE_DFA.md` for usage examples and complete documentation.
    ///
    /// @see io.airlift.regulator.Compiler#compile for reverse compilation
    /// @see io.airlift.regulator.Dfa#search for search execution
    private boolean reversed;

    // Enables a full-match shortcut only when invalid UTF-8 is also accepted.
    private boolean matchesAnyByteString;

    // Prepared once for the OnePass engine.
    private boolean onePassPrepared;
    private int onePassStateCount;
    private int[] onePassMatchCond;
    // Flattened [stateCount * bytemapRange] steps. The low 32 bits hold the action, encoded like
    // upstream; the high 32 bits hold its next state times bytemapRange, the offset of that state's
    // steps, so one load gives the action and the offset, and the loops chain a load, a shift and
    // an add per byte instead of a multiply.
    private long[] onePassStep;
    // Only programs exceeding upstream's capture-bit capacity allocate these parallel masks.
    private long[] onePassMatchCapture;
    private long[] onePassActionCapture;
    // Only programs with ONEPASS_TEXT_DEPENDENT_ASSERTIONS allocate these parallel EmptyOp masks;
    // see ONEPASS_EXTENDED_ASSERTIONS.
    private int[] onePassMatchAssertions;
    private int[] onePassActionAssertions;

    // Prepared by flatten() for BitState execution.
    private int listCount;
    // Each instruction's output list id, so BitState can index its visited set without decoding
    // the instruction first. Byte-range, capture, empty-width and no-op instructions hold the list
    // they step to; -1 marks the others (alt-match, match, fail), whose outputs BitState never
    // enters through a visited check. Null when the program has too many lists for BitState.
    private short[] outputLists;
    // The list id of the start instruction, or -1 when the program cannot run BitState.
    private int startList;
    private int bitStateTextMaxSize;
    private int bitStateJobsPerPosition;

    // Instruction counts by opcode, populated by flatten().
    private final int[] instCount = new int[InstOp.values().length];
    // The slots the program's CAPTURE instructions write, populated by flatten().
    private int captureSlotCount = 2;

    private int bytemapRange;
    private final byte[] bytemap = new byte[256];

    // Set by computeByteMap(): the bytes that can begin a match of a start-anchored program. Null
    // when the program is reversed or not start-anchored, can match empty, never matches
    // (start() == 0), or reaches an assertion first, and when no byte or every byte can begin a
    // match.
    private byte[] anchoredStartBytes;

    // Prefix acceleration configuration.
    private boolean prefixFoldCase;
    private int prefixSize;
    private byte[] prefix;
    private long prefixFrontBroadcastMask;  // Pre-computed SWAR broadcast mask for first prefix byte
    // The REPEATED_BYTE strategy scans for the prefix byte at prefixProbeOffset, chosen by the
    // compile-time ByteFrequencies, and checks the byte at prefixCheckOffset, the first or last
    // byte farther from the probe, before comparing the whole prefix.
    private int prefixProbeOffset;
    private int prefixCheckOffset;
    private long prefixProbeBroadcastMask;
    // The fused pair scans test the bytes at prefixPairOffset and at the last offset. It is 0,
    // or, when the prefix starts with a multibyte UTF-8 character that ends before the last
    // offset, the offset of that character's last byte: a lead byte names little more than the
    // script, which most letters of a text share, while the character's last byte tells its
    // letters apart.
    private int prefixPairOffset;
    // KMP shifts of a non-folded prefix of two or more bytes, for the KMP search a prefix scan
    // finishes with once its failed candidates pass LiteralSearchKernel's comparison budget.
    private int[] prefixShifts;
    // The part of the shift table's size charged to dfaMemory, returned when the prefix changes.
    private long prefixShiftsCharge;
    private static final int MAX_FOLD_CASE_PREFIX_BYTES = 9;

    // The literal every match begins with behind a leading assertion, and its search; see
    // buildTransparentStartLiteral. Both are null unless pattern compilation configures them.
    private byte[] transparentStartLiteral;
    private LiteralSearchKernel transparentStartLiteralSearch;
    // Whether an unanchored group-zero NFA search with no live thread skips to the next possible
    // match start: with the prefix acceleration, or with the transparent start literal of a
    // program whose assertions the DFA cannot run. One field keeps the NFA's test to a single
    // load. The capture NFA and BitState still skip only with the prefix acceleration.
    private boolean skipsToStartCandidate;

    // Required prefix for instant rejection (extracted via requiredPrefix()).
    // If pattern is "^literal..." and text doesn't contain the literal, reject immediately.
    private boolean requiredPrefixFoldCase;
    private byte[] requiredPrefix;

    // DFA caching with memory budgets (like upstream RE2).
    private long dfaMemory;  // Total DFA memory budget
    private final AtomicReference<Dfa.DfaInstance> dfaFirstMatch = new AtomicReference<>();
    private final AtomicReference<Dfa.DfaInstance> dfaLongestMatch = new AtomicReference<>();
    private final AtomicReference<Dfa.DfaInstance> dfaManyMatch = new AtomicReference<>();
    // The budget with which each kind's DFA failed to initialize, or 0. A failed DFA is not
    // cached, so without this every search at that budget would build it again. Searches share
    // it without locking, and the race is benign: a search that has not yet seen a recorded
    // budget builds the failing DFA once more, and only a budget whose DFA failed is ever
    // recorded, so a DFA that would initialize is never skipped.
    private final AtomicLongArray failedDfaBudgets = new AtomicLongArray(Dfa.DfaInstance.Kind.values().length);
    // Immutable flattened instruction tables for NFA no-submatch fast path.
    private final AtomicReference<NoSubmatchTables> noSubmatchNfaTables = new AtomicReference<>();
    // Compact immutable instructions shared by capture engines. Compilation builds them after
    // flattening; the getter builds them for programs flattened elsewhere, as tests do.
    private volatile long[] captureInstructionWords;
    // The capture NFA's plan, set once by pattern compilation before the program is published. A
    // program it never plans keeps these defaults and runs every capture search in one pass.
    private int singlePassCaptureSlots = Integer.MAX_VALUE;
    private int boundedPassCaptureSlots = Integer.MAX_VALUE;

    public Prog()
    {
        // Instruction #0 is always the fail instruction in upstream.
        add(Inst.createFail());
    }

    public int size()
    {
        return instructionCount;
    }

    /// For each instruction reachable from start, computes the number of byte-range
    /// instructions reachable by following only empty transitions.
    public void fanout(SparseIntArray fanout)
    {
        requireNonNull(fanout, "fanout is null");
        if (fanout.maxSize() != size()) {
            fanout.resize(size());
        }

        SparseSet reachable = new SparseSet(size());
        fanout.clear();
        fanout.setNew(start(), 0);

        for (int fanoutIndex = 0; fanoutIndex < fanout.size(); fanoutIndex++) {
            int id = fanout.denseIndexAt(fanoutIndex);
            int count = fanout.denseValueAt(fanoutIndex);
            reachable.clear();
            reachable.insert(id);
            for (int reachableIndex = 0; reachableIndex < reachable.size(); reachableIndex++) {
                int instructionId = reachable.denseAt(reachableIndex);
                Inst instruction = inst(instructionId);
                switch (instruction.opcode()) {
                    case ALT -> throw new IllegalStateException("unhandled ALT in Prog.fanout");
                    case BYTE_RANGE -> {
                        if (!instruction.last()) {
                            reachable.insert(instructionId + 1);
                        }
                        count++;
                        if (!fanout.hasIndex(instruction.out())) {
                            fanout.setNew(instruction.out(), 0);
                        }
                    }
                    case ALT_MATCH -> {
                        if (!instruction.last()) {
                            reachable.insert(instructionId + 1);
                        }
                    }
                    case CAPTURE, EMPTY_WIDTH, NOP -> {
                        if (!instruction.last()) {
                            reachable.insert(instructionId + 1);
                        }
                        reachable.insert(instruction.out());
                    }
                    case MATCH -> {
                        if (!instruction.last()) {
                            reachable.insert(instructionId + 1);
                        }
                    }
                    case FAIL -> {}
                }
            }
            fanout.setExisting(id, count);
        }
    }

    /// Removes the most recently added instruction.
    ///
    /// This is an internal compiler helper used during UTF-8 rune range trie factoring,
    /// mirroring upstream RE2 behavior that frees an instruction when it would otherwise
    /// become unreachable.
    public void removeLastInst(int expectedId)
    {
        int last = instructionCount - 1;
        if (expectedId != last) {
            throw new IllegalArgumentException("expected last id " + last + " but got " + expectedId);
        }
        insts[last] = null;
        instructionCount = last;
    }

    public int start()
    {
        return start;
    }

    public void setStart(int start)
    {
        this.start = start;
    }

    public int startUnanchored()
    {
        return startUnanchored;
    }

    public void setStartUnanchored(int startUnanchored)
    {
        this.startUnanchored = startUnanchored;
    }

    public boolean anchorStart()
    {
        return anchorStart;
    }

    public void setAnchorStart(boolean anchorStart)
    {
        this.anchorStart = anchorStart;
    }

    public boolean anchorEnd()
    {
        return anchorEnd;
    }

    public void setAnchorEnd(boolean anchorEnd)
    {
        this.anchorEnd = anchorEnd;
    }

    public boolean canMatchEmpty()
    {
        return canMatchEmpty;
    }

    public void setCanMatchEmpty(boolean canMatchEmpty)
    {
        this.canMatchEmpty = canMatchEmpty;
    }

    /// Returns whether any instruction is an empty-width assertion, such as `\b` or `(?m)^`. An
    /// anchor the compiler records in [#anchorStart()] or [#anchorEnd()] leaves none. Set by
    /// [#computeByteMap()].
    public boolean hasEmptyWidthAssertions()
    {
        return hasEmptyWidthAssertions;
    }

    public boolean hasTextDependentAssertions()
    {
        return textDependentAssertions != 0;
    }

    public boolean hasDfaUnsupportedAssertions()
    {
        int dfaSupported = EmptyOp.EMPTY_TRINO_BEGIN_LINE | EmptyOp.EMPTY_TRINO_END_LINE | EmptyOp.RUST_ASSERTIONS | EmptyOp.JAVA_TRINO_WORD_BOUNDARIES;
        return (textDependentAssertions & ~dfaSupported) != 0;
    }

    /// Returns whether this program's DFA can quit on a byte outside ASCII, as one with a Rust
    /// Unicode word assertion or a Java or Trino word boundary does, so that an engine that
    /// decodes the text runs the search.
    public boolean dfaQuitsOnNonAscii()
    {
        return (textDependentAssertions & EmptyOp.DECODING_WORD_ASSERTIONS) != 0;
    }

    public int textDependentAssertions()
    {
        return textDependentAssertions;
    }

    public boolean hasFullCaseFold()
    {
        return hasFullCaseFold;
    }

    public void setHasFullCaseFold(boolean hasFullCaseFold)
    {
        this.hasFullCaseFold = hasFullCaseFold;
    }

    /// Returns whether this program is compiled for reverse (right-to-left) search.
    ///
    /// @return true if program scans backward, false if forward
    /// @see #reversed field for complete documentation
    public boolean reversed()
    {
        return reversed;
    }

    /// Sets whether this program should execute reverse (right-to-left) search.
    ///
    /// **NOTE:** Typically set during compilation by [io.airlift.regulator.Compiler#compile].
    /// Manually changing this after compilation may produce incorrect results if
    /// anchor flags weren't swapped appropriately.
    ///
    /// @param reversed true for backward search, false for forward
    public void setReversed(boolean reversed)
    {
        this.reversed = reversed;
    }

    /// Returns whether this pattern matches every byte sequence, including invalid UTF-8.
    public boolean matchesAnyByteString()
    {
        return matchesAnyByteString;
    }

    /// Sets whether this pattern matches every byte sequence.
    public void setMatchesAnyByteString(boolean matchesAnyByteString)
    {
        this.matchesAnyByteString = matchesAnyByteString;
    }

    public boolean isOnePass()
    {
        return onePassMatchCond != null;
    }

    void prepareOnePass()
    {
        if (onePassPrepared) {
            return;
        }
        onePassPrepared = true;
        computeOnePass();
    }

    public int onePassStateCount()
    {
        return onePassStateCount;
    }

    public int[] onePassMatchCond()
    {
        return onePassMatchCond;
    }

    long[] onePassStep()
    {
        return onePassStep;
    }

    long prefixShiftsMemory()
    {
        return prefixShifts == null ? 0 : LiteralSearchKernel.prefixShiftsRetainedSize(prefixShifts);
    }

    long onePassMemory()
    {
        if (!isOnePass()) {
            return 0;
        }
        return onePassStateCount * onePassStateSize(ONEPASS_ACTION_BYTES, onePassActionCapture != null, onePassActionAssertions != null);
    }

    /// Returns the bytes one one-pass state retains when each action takes `actionBytes`.
    private long onePassStateSize(int actionBytes, boolean extendedCaptures, boolean extendedAssertions)
    {
        long stateSize = Integer.BYTES + ((long) bytemapRange() * actionBytes);
        if (extendedCaptures) {
            stateSize += Long.BYTES + ((long) bytemapRange() * Long.BYTES);
        }
        if (extendedAssertions) {
            stateSize += Integer.BYTES + ((long) bytemapRange() * Integer.BYTES);
        }
        return stateSize;
    }

    public boolean supportsOnePassCaptureSlots(int captureSlotCount)
    {
        return captureSlotCount <= ONEPASS_MAX_CAP ||
                (captureSlotCount <= Long.SIZE && onePassActionCapture != null);
    }

    public long[] onePassMatchCapture()
    {
        return onePassMatchCapture;
    }

    public long[] onePassActionCapture()
    {
        return onePassActionCapture;
    }

    int[] onePassMatchAssertions()
    {
        return onePassMatchAssertions;
    }

    int[] onePassActionAssertions()
    {
        return onePassActionAssertions;
    }

    public boolean canBitState()
    {
        return outputLists != null;
    }

    /// Returns whether upstream RE2 would give this program list heads, which it charges to the
    /// compile memory budget. It reads the final instruction count, so it is only valid after
    /// [#flatten()] has been called.
    boolean hasUpstreamListHeads()
    {
        return instructionCount <= UPSTREAM_LIST_HEAD_MAXIMUM_INSTRUCTIONS;
    }

    public int listCount()
    {
        return listCount;
    }

    /// Returns, for each instruction, the list id of its output, which BitState uses to index its
    /// visited set. Byte-range, capture, empty-width and no-op instructions hold the list they step
    /// to; -1 marks the others (alt-match, match, fail), whose outputs BitState never enters
    /// through a visited check. Null when the program cannot run BitState.
    short[] outputLists()
    {
        return outputLists;
    }

    /// Returns the list id of the start instruction, or -1 when the program cannot run BitState.
    int startList()
    {
        return startList;
    }

    public int bitStateTextMaxSize()
    {
        return bitStateTextMaxSize;
    }

    /// Returns the jobs a BitState search may push for each position of its text; see
    /// [BitState#jobsPerPosition]. Meaningful only when [#canBitState()] is true, and only valid
    /// after [#flatten()] has been called.
    int bitStateJobsPerPosition()
    {
        return bitStateJobsPerPosition;
    }

    /// Lowers the BitState text limit so that a search's visited bitmap, capture slots, job stack,
    /// and objects fit in `allowance` bytes. Only valid after [#flatten()] has been called.
    void limitBitStateMemory(long allowance)
    {
        if (outputLists != null) {
            bitStateTextMaxSize = BitState.maxTextLength(this, bitStateTextMaxSize, allowance);
        }
    }

    /// Returns a 256-entry table that is nonzero for each byte that can begin a match of this
    /// start-anchored program, or null. An anchored search cannot match when its first byte has a
    /// zero entry. Set by [#computeByteMap()].
    byte[] anchoredStartBytes()
    {
        return anchoredStartBytes;
    }

    /// Returns instruction counts by opcode, indexed by [InstOp#ordinal()].
    /// Only valid after [#flatten()] has been called.
    public int[] getInstCount()
    {
        return instCount.clone();
    }

    /// Returns instruction count for a specific opcode.
    /// Only valid after [#flatten()] has been called.
    public int getInstCount(InstOp op)
    {
        return instCount[op.ordinal()];
    }

    /// Returns the slots the program's CAPTURE instructions write: every slot through the highest
    /// surviving CAPTURE's, rounded up to a pair, and at least two. Slots past it belong to
    /// trailing groups the simplifier removed, which every match leaves unset; a removed group
    /// below a surviving one keeps its slots. Only valid after [#flatten()] has been called.
    int captureSlotCount()
    {
        return captureSlotCount;
    }

    public boolean canPrefixAccel()
    {
        return prefixSize != 0;
    }

    /// Builds a conservative set of bytes that can begin a non-empty match.
    /// Non-nullable searches may scan past other bytes while the DFA remains in
    /// its start state. Nullable searches may return the current empty match when
    /// the current byte is outside the set. Assertions and dense sets deliberately
    /// use the normal DFA. `firstMatch` is set for the leftmost-first DFA, where a match
    /// outranks the threads after it; longest-match and many-match DFAs must also consider them.
    byte[] buildStartByteCandidates(int[] stack, boolean firstMatch)
    {
        boolean nullable = canMatchEmpty;
        if (reversed || anchorStart || (nullable && anchorEnd) || canPrefixAccel() || start() == 0) {
            return null;
        }

        byte[] candidates = new byte[256];
        int candidateCount = walkStartBytes(stack, candidates, nullable, firstMatch);
        // Dense candidate sets spend more time handing off to the DFA than they save scanning.
        if (candidateCount >= 0 && candidateCount <= 64 && (candidateCount > 0 || nullable)) {
            return candidates;
        }
        return null;
    }

    /// Marks in `candidates` every byte that can begin a non-empty match from [#start()] and
    /// returns how many it marked, or -1 when the walk reaches an assertion, or reaches a match
    /// while `nullable` is false. With `firstMatch`, it skips the threads a match outranks;
    /// the flag matters only when `nullable` is true.
    private int walkStartBytes(int[] stack, byte[] candidates, boolean nullable, boolean firstMatch)
    {
        boolean[] visited = new boolean[size()];
        int stackPointer = 0;
        stack[stackPointer++] = start();
        int candidateCount = 0;

        while (stackPointer > 0) {
            int instructionId = stack[--stackPointer];
            if (instructionId <= 0 || instructionId >= size() || visited[instructionId]) {
                continue;
            }
            visited[instructionId] = true;

            Inst instruction = inst(instructionId);
            switch (instruction.opcode()) {
                case BYTE_RANGE -> {
                    candidateCount += addStartByteRange(candidates, instruction);
                    if (!instruction.last()) {
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case CAPTURE, NOP -> {
                    if (!instruction.last()) {
                        stack[stackPointer++] = instructionId + 1;
                    }
                    stack[stackPointer++] = instruction.out();
                }
                case EMPTY_WIDTH -> {
                    // Skipping input would lose the previous-byte context needed by the assertion.
                    // A search for the buildTransparentStartLiteral literal instead verifies each
                    // occurrence from the byte before it.
                    return -1;
                }
                case ALT, ALT_MATCH -> {
                    if (!instruction.last()) {
                        stack[stackPointer++] = instructionId + 1;
                    }
                    stack[stackPointer++] = instruction.out1();
                    stack[stackPointer++] = instruction.out();
                }
                case MATCH -> {
                    if (!nullable) {
                        return -1;
                    }
                    // Leftmost-first prefers this match to the threads after it in the list;
                    // longest-match and many-match searches go on to them.
                    if (!firstMatch && !instruction.last()) {
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case FAIL -> {
                    if (!instruction.last()) {
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
            }
        }
        return candidateCount;
    }

    /// Builds the byte sets at the first `positionCount` positions of every match path, for the
    /// start literal filter a search scans instead of the start byte set. Each path is
    /// `positionCount` 256-bit sets of four longs. A path that matches before its last position
    /// accepts every byte at the remaining positions, and paths that share their earlier sets
    /// and their continuation are merged, so the sets stay a superset of the bytes that begin
    /// every match. Returns null for nullable programs, for the other programs
    /// [#buildStartByteCandidates] rejects before its walk, for any assertion the walk reaches,
    /// and past [#MAXIMUM_START_POSITION_PATHS] paths.
    long[][] buildStartPositionByteSets(int[] stack, int positionCount)
    {
        if (reversed || anchorStart || canMatchEmpty || canPrefixAccel() || start() == 0) {
            return null;
        }

        List<StartPositionPath> paths = List.of(new StartPositionPath(new long[positionCount * 4], start()));
        List<long[]> completedPaths = new ArrayList<>();
        boolean[] visited = new boolean[size()];
        for (int position = 0; position < positionCount; position++) {
            List<StartPositionPath> nextPaths = new ArrayList<>();
            for (StartPositionPath path : paths) {
                Arrays.fill(visited, false);
                int stackPointer = 0;
                stack[stackPointer++] = path.instructionId();
                while (stackPointer > 0) {
                    int instructionId = stack[--stackPointer];
                    if (instructionId <= 0 || instructionId >= size() || visited[instructionId]) {
                        continue;
                    }
                    visited[instructionId] = true;

                    Inst instruction = inst(instructionId);
                    switch (instruction.opcode()) {
                        case BYTE_RANGE -> {
                            addStartPositionRange(nextPaths, path.byteSets(), position, instruction);
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                        case CAPTURE, NOP -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instruction.out();
                        }
                        case EMPTY_WIDTH -> {
                            // The walk does not evaluate assertions, so it keeps no filter rather
                            // than drop a path an assertion would allow.
                            return null;
                        }
                        case ALT, ALT_MATCH -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instruction.out1();
                            stack[stackPointer++] = instruction.out();
                        }
                        case MATCH -> {
                            if (position == 0) {
                                return null;
                            }
                            long[] byteSets = path.byteSets().clone();
                            Arrays.fill(byteSets, position * 4, byteSets.length, -1L);
                            completedPaths.add(byteSets);
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                        case FAIL -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                    }
                    if (nextPaths.size() + completedPaths.size() > MAXIMUM_START_POSITION_PATHS) {
                        return null;
                    }
                }
            }
            paths = nextPaths;
        }
        for (StartPositionPath path : paths) {
            completedPaths.add(path.byteSets());
        }
        return completedPaths.toArray(long[][]::new);
    }

    // Paths that share their earlier sets and continue at the same instruction accept the same
    // suffixes, so their sets at this position merge without widening the filter.
    private static void addStartPositionRange(List<StartPositionPath> paths, long[] previousByteSets, int position, Inst instruction)
    {
        long[] byteSets = null;
        for (StartPositionPath path : paths) {
            if (path.instructionId() == instruction.out() &&
                    Arrays.equals(path.byteSets(), 0, position * 4, previousByteSets, 0, position * 4)) {
                byteSets = path.byteSets();
                break;
            }
        }
        if (byteSets == null) {
            byteSets = previousByteSets.clone();
            paths.add(new StartPositionPath(byteSets, instruction.out()));
        }
        // Like addStartByteRange: a folding range reads an upper-case byte as its lower case.
        for (int value = instruction.lo(); value <= instruction.hi(); value++) {
            if (!instruction.foldCase() || value < 'A' || value > 'Z') {
                addStartPositionByte(byteSets, position, value);
            }
            if (instruction.foldCase() && 'a' <= value && value <= 'z') {
                addStartPositionByte(byteSets, position, value - ('a' - 'A'));
            }
        }
    }

    private static void addStartPositionByte(long[] byteSets, int position, int value)
    {
        byteSets[position * 4 + (value >>> 6)] |= 1L << value;
    }

    private record StartPositionPath(long[] byteSets, int instructionId) {}

    /// Returns the literal of [#MINIMUM_TRANSPARENT_START_LITERAL_BYTES] to
    /// [#MAXIMUM_TRANSPARENT_START_LITERAL_BYTES] bytes that begins every match of a program with
    /// an assertion before its first byte, or `null`. The walk reads
    /// assertions, captures, and no-ops as matching the empty string, as the Rust crate's literal
    /// extraction reads its look-arounds. Every path must consume the same unfolded byte at each
    /// step before it can match; the literal ends where the paths disagree, where one can match,
    /// or at the maximum. Assertions take no width, so every match starts at an occurrence of the
    /// literal, and a search that verifies each occurrence from a start state computed from the
    /// byte before it loses none of the context [#buildStartByteCandidates] keeps by refusing to
    /// skip input. The early return adds a check for any assertion to the guard of
    /// [#buildStartPositionByteSets], and a program without an assertion before its first byte is
    /// rejected after the first step, so it keeps the start byte machinery.
    byte[] buildTransparentStartLiteral()
    {
        // A program without assertions returns before the walk allocates its program-sized arrays.
        if (reversed || anchorStart || canMatchEmpty || canPrefixAccel() || start() == 0 || getInstCount(InstOp.EMPTY_WIDTH) == 0) {
            return null;
        }

        byte[] literal = new byte[MAXIMUM_TRANSPARENT_START_LITERAL_BYTES];
        int literalLength = 0;
        int[] currentInstructions = new int[size()];
        int currentInstructionCount = 1;
        currentInstructions[0] = start();
        int[] nextInstructions = new int[size()];
        boolean[] visited = new boolean[size()];
        int[] stack = new int[size() * 3 + 1];
        while (literalLength < MAXIMUM_TRANSPARENT_START_LITERAL_BYTES) {
            Arrays.fill(visited, false);
            int literalByte = -1;
            int nextInstructionCount = 0;
            boolean assertionBeforeByte = false;
            boolean pathsEnd = false;
            for (int currentInstructionIndex = 0; currentInstructionIndex < currentInstructionCount && !pathsEnd; currentInstructionIndex++) {
                int stackPointer = 0;
                stack[stackPointer++] = currentInstructions[currentInstructionIndex];
                while (stackPointer > 0 && !pathsEnd) {
                    int instructionId = stack[--stackPointer];
                    if (instructionId <= 0 || instructionId >= size() || visited[instructionId]) {
                        continue;
                    }
                    visited[instructionId] = true;

                    Inst instruction = inst(instructionId);
                    switch (instruction.opcode()) {
                        case BYTE_RANGE -> {
                            // A folded range matches the upper case of a lower-case letter too.
                            boolean singleByte = instruction.lo() == instruction.hi() && (!instruction.foldCase() || instruction.lo() < 'a' || instruction.lo() > 'z');
                            if (!singleByte || (literalByte >= 0 && literalByte != instruction.lo())) {
                                pathsEnd = true;
                                break;
                            }
                            literalByte = instruction.lo();
                            nextInstructions[nextInstructionCount++] = instruction.out();
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                        case EMPTY_WIDTH -> {
                            assertionBeforeByte = true;
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instruction.out();
                        }
                        case CAPTURE, NOP -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instruction.out();
                        }
                        case ALT, ALT_MATCH -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instruction.out1();
                            stack[stackPointer++] = instruction.out();
                        }
                        case MATCH -> pathsEnd = true;
                        case FAIL -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                    }
                }
            }
            if (literalLength == 0 && !assertionBeforeByte) {
                return null;
            }
            if (pathsEnd || literalByte < 0) {
                break;
            }
            literal[literalLength++] = (byte) literalByte;
            int[] instructions = currentInstructions;
            currentInstructions = nextInstructions;
            nextInstructions = instructions;
            currentInstructionCount = nextInstructionCount;
        }
        return literalLength < MINIMUM_TRANSPARENT_START_LITERAL_BYTES ? null : Arrays.copyOf(literal, literalLength);
    }

    /// Retains the literal [#buildTransparentStartLiteral] returned and its search, for the
    /// candidate cursor of a DFA search and, when the DFA cannot run this program's assertions,
    /// the group-zero NFA's skip to the next possible match start.
    void configureTransparentStartLiteral(byte[] literal, LiteralSearchKernel literalSearch)
    {
        this.transparentStartLiteral = requireNonNull(literal, "literal is null");
        this.transparentStartLiteralSearch = requireNonNull(literalSearch, "literalSearch is null");
        updateStartCandidateSkip();
    }

    byte[] transparentStartLiteral()
    {
        return transparentStartLiteral;
    }

    LiteralSearchKernel transparentStartLiteralSearch()
    {
        return transparentStartLiteralSearch;
    }

    /// Returns whether [#skipToStartCandidate] may skip the input an unanchored search with no
    /// live thread would otherwise step over.
    boolean canSkipToStartCandidate()
    {
        return skipsToStartCandidate;
    }

    /// Returns the absolute index in `data` of the first possible match start in the range, or
    /// `-1`: the next prefix acceleration candidate, or the next occurrence of the transparent
    /// start literal. Requires [#canSkipToStartCandidate].
    int skipToStartCandidate(byte[] data, int offset, int length)
    {
        if (canPrefixAccel()) {
            return prefixAccel(data, offset, length);
        }
        return findLiteral(data, offset, length, transparentStartLiteral, transparentStartLiteralSearch);
    }

    /// Returns the absolute index in `data` of the first occurrence of `literal`, of at least two
    /// bytes, in the range, or `-1`. Ranges of at least the fused vector scan's minimum take the
    /// vector scan of [PrefixAccelStrategy#FUSED_VECTOR], finished with KMP once its failed
    /// candidates pass the comparison budget, and shorter ones take `literalSearch`, the
    /// literal's call-free search. The vector scan recomputes [#pairOffset] on each call, a few
    /// byte reads against a range of at least 1 KiB, so the literal needs no stored offset.
    static int findLiteral(byte[] data, int offset, int length, byte[] literal, LiteralSearchKernel literalSearch)
    {
        if (VectorSupport.isAvailable() && length >= FUSED_PREFIX_VECTOR_MINIMUM_BYTES) {
            int found = VectorPrefixScanner.find(data, offset, length, literal, pairOffset(literal), literal.length - 1);
            if (found < -1) {
                return literalSearch.findKmp(data, -2 - found, offset + length);
            }
            return found;
        }
        return literalSearch.findContains(data, offset, length);
    }

    /// Returns the offset of the byte that the fused pair scans test together with the last byte
    /// of `literal`, of at least two bytes: the last byte of a leading multibyte UTF-8 character
    /// that ends before the literal's last byte, or else 0.
    static int pairOffset(byte[] literal)
    {
        int lead = literal[0] & 0xFF;
        int width = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
        if (width == 1 || width >= literal.length) {
            return 0;
        }
        for (int index = 1; index < width; index++) {
            if ((literal[index] & 0xC0) != 0x80) {
                return 0;
            }
        }
        return width - 1;
    }

    private void updateStartCandidateSkip()
    {
        skipsToStartCandidate = canPrefixAccel() || (transparentStartLiteral != null && hasDfaUnsupportedAssertions());
    }

    /// Finds a selective byte set at one fixed offset from every possible match start.
    /// The bounded walk deliberately rejects assertions and variable-distance candidates.
    FixedDistanceByteCandidates buildFixedDistanceByteCandidates(int[] stack, byte[] startByteCandidates)
    {
        if (reversed || anchorStart || canMatchEmpty || canPrefixAccel() || startByteCandidates == null || start() == 0) {
            return null;
        }

        int bestCandidateCount = countCandidates(startByteCandidates);
        byte[] bestCandidates = null;
        int bestOffset = -1;

        int[] currentInstructions = new int[size()];
        int currentInstructionCount = 1;
        currentInstructions[0] = start();
        int[] byteInstructions = new int[size()];
        boolean[] visited = new boolean[size()];

        for (int offset = 0; offset <= MAXIMUM_FIXED_DISTANCE_BYTE_OFFSET && currentInstructionCount > 0; offset++) {
            Arrays.fill(visited, false);
            byte[] candidates = new byte[256];
            int candidateCount = 0;
            int byteInstructionCount = 0;
            boolean canMatchAtCurrentOffset = false;
            for (int currentInstructionIndex = 0; currentInstructionIndex < currentInstructionCount; currentInstructionIndex++) {
                int stackPointer = 0;
                stack[stackPointer++] = currentInstructions[currentInstructionIndex];

                while (stackPointer > 0) {
                    int instructionId = stack[--stackPointer];
                    if (instructionId <= 0 || instructionId >= size() || visited[instructionId]) {
                        continue;
                    }
                    visited[instructionId] = true;

                    Inst instruction = inst(instructionId);
                    switch (instruction.opcode()) {
                        case BYTE_RANGE -> {
                            candidateCount += addStartByteRange(candidates, instruction);
                            byteInstructions[byteInstructionCount++] = instructionId;
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                        case CAPTURE, NOP -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instruction.out();
                        }
                        case EMPTY_WIDTH -> {
                            return null;
                        }
                        case ALT, ALT_MATCH -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instruction.out1();
                            stack[stackPointer++] = instruction.out();
                        }
                        case MATCH -> {
                            canMatchAtCurrentOffset = true;
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                        case FAIL -> {
                            if (!instruction.last()) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                    }
                }
            }

            if (canMatchAtCurrentOffset) {
                break;
            }
            if (offset > 0 && candidateCount > 0 && candidateCount < bestCandidateCount) {
                bestCandidateCount = candidateCount;
                bestCandidates = candidates;
                bestOffset = offset;
            }

            currentInstructionCount = 0;
            for (int index = 0; index < byteInstructionCount; index++) {
                currentInstructions[currentInstructionCount++] = inst(byteInstructions[index]).out();
            }
        }

        if (bestCandidates == null || bestCandidateCount > 64) {
            return null;
        }
        return new FixedDistanceByteCandidates(bestCandidates, bestOffset);
    }

    private static int countCandidates(byte[] candidates)
    {
        if (candidates == null) {
            return Integer.MAX_VALUE;
        }
        int count = 0;
        for (byte candidate : candidates) {
            if (candidate != 0) {
                count++;
            }
        }
        return count;
    }

    private static int addStartByteRange(byte[] candidates, Inst instruction)
    {
        int added = 0;
        for (int value = instruction.lo(); value <= instruction.hi(); value++) {
            if ((!instruction.foldCase() || value < 'A' || value > 'Z') && candidates[value] == 0) {
                candidates[value] = 1;
                added++;
            }
        }
        if (instruction.foldCase()) {
            for (int value = 'A'; value <= 'Z'; value++) {
                int foldedValue = value + ('a' - 'A');
                if (instruction.lo() <= foldedValue && foldedValue <= instruction.hi() && candidates[value] == 0) {
                    candidates[value] = 1;
                    added++;
                }
            }
        }
        return added;
    }

    /// True when prefix acceleration is a single-byte, case-sensitive search.
    /// This is the highest-frequency false-positive regime and can use a
    /// dedicated DFA path without affecting other prefix strategies.
    boolean canUseSingleBytePrefixAccelFastPath()
    {
        return prefixSize == 1 && !prefixFoldCase;
    }

    boolean usesVectorForSingleBytePrefix(int length)
    {
        return VectorSupport.isAvailable() && length >= FUSED_PREFIX_VECTOR_MINIMUM_BYTES;
    }

    boolean usesFoldCasePrefixCandidateScan()
    {
        return prefixFoldCase;
    }

    /// True when a folded multi-byte prefix scans `length` bytes with
    /// [VectorPrefixScanner#findFoldCase], under the same cutoff as
    /// [PrefixAccelStrategy#FUSED_VECTOR].
    boolean usesVectorForFoldCasePrefix(int length)
    {
        return prefixFoldCase && prefixSize > 1 && VectorSupport.isAvailable() && length >= FUSED_PREFIX_VECTOR_MINIMUM_BYTES;
    }

    PrefixAccelStrategy prefixAccelStrategy(int length)
    {
        if (prefixFoldCase || prefixSize <= 1 || prefixProbeOffset != 0) {
            return PrefixAccelStrategy.REPEATED_BYTE;
        }
        if (VectorSupport.isAvailable() && length >= FUSED_PREFIX_VECTOR_MINIMUM_BYTES) {
            return PrefixAccelStrategy.FUSED_VECTOR;
        }
        // Short ASCII-led searches, and long ones without the Vector API, keep the repeated
        // first-byte scan, whose single load per word costs less than the fused SWAR pair's two.
        return prefix[0] >= 0 ? PrefixAccelStrategy.REPEATED_BYTE : PrefixAccelStrategy.FUSED_SWAR;
    }

    /// Returns the offset of the prefix byte that the [PrefixAccelStrategy#REPEATED_BYTE]
    /// strategy scans for, chosen by [ByteFrequencies#probeOffset].
    int prefixAccelProbeOffset()
    {
        return prefixProbeOffset;
    }

    /// Specialized prefix scan for single-byte, case-sensitive prefix acceleration.
    /// Keeps the scan path branch-free with respect to foldCase/prefix mode.
    int prefixAccelSingleByteNoFoldCase(byte[] data, int offset, int length)
    {
        if (length <= 0) {
            return -1;
        }
        if (!canUseSingleBytePrefixAccelFastPath()) {
            throw new IllegalStateException("single-byte prefix accel fast path not configured");
        }
        if (usesVectorForSingleBytePrefix(length)) {
            return VectorByteSetScanner.find(data, offset, length, prefix[0], (byte) 0, (byte) 0, 1);
        }
        return indexOfSWAR(data, offset, length, prefixFrontBroadcastMask);
    }

    public void configurePrefixAccel(Slice prefix, boolean foldCase)
    {
        configurePrefixAccel(prefix, foldCase, ByteFrequencies.uniform());
    }

    void configurePrefixAccel(Slice prefix, boolean foldCase, ByteFrequencies byteFrequencies)
    {
        if (prefix == null || prefix.length() == 0) {
            configurePrefixAccelBytes(null, foldCase, byteFrequencies);
            return;
        }

        byte[] bytes = new byte[prefix.length()];
        System.arraycopy(prefix.byteArray(), prefix.byteArrayOffset(), bytes, 0, prefix.length());
        configurePrefixAccelBytes(bytes, foldCase, byteFrequencies);
    }

    /// Retains analysis-owned immutable bytes instead of copying a large literal again.
    void configurePrefixAccelShared(Slice prefix, boolean foldCase, ByteFrequencies byteFrequencies)
    {
        if (prefix.byteArrayOffset() != 0 || prefix.length() != prefix.byteArray().length) {
            throw new IllegalArgumentException("shared prefix must cover its complete backing array");
        }
        configurePrefixAccelBytes(prefix.byteArray(), foldCase, byteFrequencies);
    }

    private void configurePrefixAccelBytes(byte[] bytes, boolean foldCase, ByteFrequencies byteFrequencies)
    {
        this.prefixProbeOffset = 0;
        this.prefixCheckOffset = 0;
        this.prefixProbeBroadcastMask = 0;
        this.prefixPairOffset = 0;
        this.prefixShifts = null;
        this.dfaMemory += prefixShiftsCharge;
        this.prefixShiftsCharge = 0;
        if (bytes == null || bytes.length == 0) {
            this.prefix = null;
            this.prefixSize = 0;
            this.prefixFoldCase = false;
            this.prefixFrontBroadcastMask = 0;
            updateStartCandidateSkip();
            return;
        }
        this.prefix = bytes;
        this.prefixFoldCase = foldCase;

        if (foldCase) {
            // Limit to the same nine-byte prefix used by upstream ShiftDFA.
            this.prefixSize = Math.min(bytes.length, MAX_FOLD_CASE_PREFIX_BYTES);
            this.prefixFrontBroadcastMask = 0;
        }
        else if (bytes.length != 1) {
            this.prefixSize = bytes.length;
            this.prefixFrontBroadcastMask = (bytes[0] & 0xFFL) * 0x0101010101010101L;
            // A non-ASCII first byte keeps the probe at offset 0 and selects the fused strategies,
            // which pair the byte at prefixPairOffset with the last byte.
            int probeOffset = bytes[0] >= 0 ? byteFrequencies.probeOffset(bytes) : 0;
            int lastOffset = bytes.length - 1;
            this.prefixProbeOffset = probeOffset;
            this.prefixCheckOffset = probeOffset > lastOffset - probeOffset ? 0 : lastOffset;
            this.prefixProbeBroadcastMask = (bytes[probeOffset] & 0xFFL) * 0x0101010101010101L;
            this.prefixPairOffset = pairOffset(bytes);
            this.prefixShifts = LiteralSearchKernel.computePrefixShifts(bytes);
            // Like the OnePass table, the retained shifts come out of the DFA allowance. A budgeted
            // compile fails when they do not fit, so the clamp only keeps an unbudgeted program's
            // default allowance from going negative.
            this.prefixShiftsCharge = Math.min(dfaMemory, prefixShiftsMemory());
            this.dfaMemory -= prefixShiftsCharge;
        }
        else {
            // For single-byte non-foldCase, use simple memchr-style search
            this.prefixSize = 1;
            this.prefixFrontBroadcastMask = (bytes[0] & 0xFFL) * 0x0101010101010101L;
        }
        updateStartCandidateSkip();
    }

    /// Returns the absolute index in `data` of the first possible prefix match,
    /// or `-1` if no match is found.
    ///
    /// Uses different strategies depending on configuration:
    /// - First-byte and last-byte masks for folded prefixes, a vector at a time on long windows
    /// - Fused pair masks for non-folded multi-byte prefixes whose probe stays at the first byte:
    ///   the last byte with the first byte or, when the prefix starts with a multibyte UTF-8
    ///   character that ends before the last byte, with that character's last byte, in Vector
    ///   lanes for long searches and, for a non-ASCII first byte, in SWAR words when the search
    ///   is short or the Vector API is unavailable
    /// - A probe-byte scan with a boundary-byte check for other non-folded multi-byte prefixes,
    ///   derived from C++ `Prog::PrefixAccel_FrontAndBack()`; see [ByteFrequencies]
    /// - Simple byte search for single-byte prefix
    ///
    /// The non-folded multi-byte scans charge their failed candidates to
    /// [LiteralSearchKernel]'s comparison budget and finish with KMP once they pass it, so
    /// candidates with long partial matches cannot make the search quadratic.
    public int prefixAccel(byte[] data, int offset, int length)
    {
        if (!canPrefixAccel()) {
            throw new IllegalStateException("prefix acceleration not configured");
        }

        // Handle edge cases
        if (length < prefixSize) {
            return -1;
        }

        if (prefixFoldCase) {
            return prefixAccelFoldCaseFrontAndBack(data, offset, length);
        }

        // For single-byte prefix, just scan for that byte
        if (prefixSize == 1) {
            return indexOf(data, offset, length, prefix[0], prefixFoldCase);
        }

        int end = offset + length;
        PrefixAccelStrategy strategy = prefixAccelStrategy(length);
        if (strategy == PrefixAccelStrategy.FUSED_VECTOR) {
            return finishWithKmp(data, end, VectorPrefixScanner.find(data, offset, length, prefix, prefixPairOffset, prefixSize - 1));
        }
        if (strategy == PrefixAccelStrategy.FUSED_SWAR) {
            return finishWithKmp(data, end, prefixAccelNoFoldCaseFrontAndBackSwar(data, offset, length));
        }

        // A probe moved off the first byte runs its own loop, so a first-byte probe keeps the
        // upstream loop below.
        if (prefixProbeOffset != 0) {
            return finishWithKmp(data, end, prefixAccelProbe(data, offset, length));
        }

        // FrontAndBack optimization: use first and last bytes to filter candidates
        byte prefixFront = prefix[0];
        byte prefixBack = prefix[prefixSize - 1];
        int lastStart = end - prefixSize;

        // Search for prefix_front, then check prefix_back before full match. The first failed
        // candidate hands the scan to continueFrontAndBack, so this loop keeps no budget state.
        int position = offset;
        while (position <= lastStart) {
            // Find next occurrence of first byte
            int next = indexOf(data, position, (lastStart + 1) - position, prefixFront, prefixFoldCase);
            if (next < 0) {
                return -1;  // First byte not found
            }

            position = next;

            // Check last byte before checking middle bytes
            if (byteMatches(data[position + prefixSize - 1], prefixBack, prefixFoldCase)) {
                // Front and back match - check full prefix
                if (prefixMatchesAt(data, position)) {
                    return position;
                }
                return finishWithKmp(data, end, continueFrontAndBack(data, offset, position, end));
            }

            position++;  // Move to next position
        }

        return -1;
    }

    /// Returns the absolute index where [#prefixAccel] continues with KMP; only tests call it.
    /// Returns -1 when the search ends, with or without a match, before it switches to KMP, and
    /// for a folded or one-byte prefix. It runs the scans [#prefixAccel] runs, except that the
    /// first-byte scan starts in [#continueFrontAndBack], whose handoff is the same because the
    /// scan charges nothing before its first failed candidate.
    int prefixAccelKmpHandoffForDiagnostics(byte[] data, int offset, int length)
    {
        if (prefixFoldCase || prefixSize <= 1 || length < prefixSize) {
            return -1;
        }
        int found = switch (prefixAccelStrategy(length)) {
            case FUSED_VECTOR -> VectorPrefixScanner.find(data, offset, length, prefix, prefixPairOffset, prefixSize - 1);
            case FUSED_SWAR -> prefixAccelNoFoldCaseFrontAndBackSwar(data, offset, length);
            case REPEATED_BYTE -> prefixProbeOffset != 0 ? prefixAccelProbe(data, offset, length) : continueFrontAndBack(data, offset, offset, offset + length);
        };
        return found < -1 ? -2 - found : -1;
    }

    /// Returns `found`, the result of a scan that ends at `end`, or continues the scan with KMP
    /// when `found` is `-2 - resume`.
    private int finishWithKmp(byte[] data, int end, int found)
    {
        if (found < -1) {
            return LiteralSearchKernel.findKmp(data, -2 - found, end, prefix, prefixShifts);
        }
        return found;
    }

    /// Continues the first-byte scan of [#prefixAccel] from `position`, charging failed
    /// candidates to the comparison budget from `offset`. Returns what [#prefixAccel] returns,
    /// or `-2 - resume` when the search must continue with KMP at `resume`. Out of line so the
    /// scan up to the first failed candidate keeps no budget state.
    private int continueFrontAndBack(byte[] data, int offset, int position, int end)
    {
        byte prefixFront = prefix[0];
        byte prefixBack = prefix[prefixSize - 1];
        int lastStart = end - prefixSize;
        int comparisonEnd = LiteralSearchKernel.initialComparisonEnd(offset);
        while (position <= lastStart) {
            int next = indexOf(data, position, (lastStart + 1) - position, prefixFront, false);
            if (next < 0) {
                return -1;
            }

            position = next;

            if (data[position + prefixSize - 1] == prefixBack) {
                int matchedLength = prefixMatchingLength(data, position);
                if (matchedLength == prefixSize) {
                    return position;
                }
                comparisonEnd = LiteralSearchKernel.chargeFailedCandidate(comparisonEnd, matchedLength);
                if (LiteralSearchKernel.comparisonBudgetSpent(comparisonEnd, position)) {
                    return -2 - (position + 1);
                }
            }

            position++;
        }

        return -1;
    }

    private int prefixAccelProbe(byte[] data, int offset, int length)
    {
        // Upstream FrontAndBack scans for the first byte and checks the last. This scans for the
        // probe byte, which ByteFrequencies moved off a common first byte, and checks the first
        // or last byte farther from it. The scan range is shifted by the probe offset, so every
        // candidate starts in [offset, lastStart] and a probe byte of an occurrence that starts
        // before offset is never seen.
        int probeOffset = prefixProbeOffset;
        int checkOffset = prefixCheckOffset;
        byte checkByte = prefix[checkOffset];
        int end = offset + length;
        int lastStart = end - prefixSize;
        int comparisonEnd = LiteralSearchKernel.initialComparisonEnd(offset);

        int position = offset;
        while (position <= lastStart) {
            int next = indexOfSWAR(data, position + probeOffset, (lastStart + 1) - position, prefixProbeBroadcastMask);
            if (next < 0) {
                return -1;
            }

            position = next - probeOffset;

            if (data[position + checkOffset] == checkByte) {
                int matchedLength = prefixMatchingLength(data, position);
                if (matchedLength == prefixSize) {
                    return position;
                }
                comparisonEnd = LiteralSearchKernel.chargeFailedCandidate(comparisonEnd, matchedLength);
                if (LiteralSearchKernel.comparisonBudgetSpent(comparisonEnd, position)) {
                    return -2 - (position + 1);
                }
            }

            position++;
        }

        return -1;
    }

    // PERFORMANCE-SENSITIVE HOT LOOP: changes here require target-host assembly
    // and benchmark evidence. The two loads dominate this loop.
    private int prefixAccelNoFoldCaseFrontAndBackSwar(byte[] data, int offset, int length)
    {
        long lowBits = 0x0101010101010101L;
        long highBits = 0x8080808080808080L;
        int candidateCount = length - prefixSize + 1;
        int wordEnd = offset + (candidateCount / Long.BYTES) * Long.BYTES;
        int primaryOffset = prefixPairOffset;
        int secondaryOffset = prefixSize - 1;
        long primaryBroadcast = (prefix[primaryOffset] & 0xFFL) * lowBits;
        long secondaryBroadcast = (prefix[secondaryOffset] & 0xFFL) * lowBits;
        int comparisonEnd = LiteralSearchKernel.initialComparisonEnd(offset);
        int position = offset;

        for (; position < wordEnd; position += Long.BYTES) {
            long primaryDifference = ((long) LONG_HANDLE.get(data, position + primaryOffset)) ^ primaryBroadcast;
            long secondaryDifference = ((long) LONG_HANDLE.get(data, position + secondaryOffset)) ^ secondaryBroadcast;
            long candidates = ((primaryDifference - lowBits) & ~primaryDifference & highBits) &
                    ((secondaryDifference - lowBits) & ~secondaryDifference & highBits);
            while (candidates != 0) {
                int candidate = position + (Long.numberOfTrailingZeros(candidates) >>> 3);
                int matchedLength = prefixMatchingLength(data, candidate);
                if (matchedLength == prefixSize) {
                    return candidate;
                }
                comparisonEnd = LiteralSearchKernel.chargeFailedCandidate(comparisonEnd, matchedLength);
                if (LiteralSearchKernel.comparisonBudgetSpent(comparisonEnd, candidate)) {
                    return -2 - (candidate + 1);
                }
                candidates &= candidates - 1;
            }
        }

        int end = offset + candidateCount;
        for (; position < end; position++) {
            if (data[position + primaryOffset] == prefix[primaryOffset] &&
                    data[position + secondaryOffset] == prefix[secondaryOffset]) {
                int matchedLength = prefixMatchingLength(data, position);
                if (matchedLength == prefixSize) {
                    return position;
                }
                comparisonEnd = LiteralSearchKernel.chargeFailedCandidate(comparisonEnd, matchedLength);
                if (LiteralSearchKernel.comparisonBudgetSpent(comparisonEnd, position)) {
                    return -2 - (position + 1);
                }
            }
        }
        return -1;
    }

    private int prefixAccelFoldCaseFrontAndBack(byte[] data, int offset, int length)
    {
        if (prefixSize == 1) {
            return indexOfFoldCase(data, offset, length, prefix[0]);
        }
        if (usesVectorForFoldCasePrefix(length)) {
            return VectorPrefixScanner.findFoldCase(data, offset, length, prefix, prefixSize);
        }
        return prefixAccelFoldCaseFrontAndBackSWAR(data, offset, length);
    }

    // Short windows, and JVMs that do not resolve jdk.incubator.vector or whose preferred byte
    // vector holds fewer than 16 or more than 64 bytes.
    private int prefixAccelFoldCaseFrontAndBackSWAR(byte[] data, int offset, int length)
    {
        int candidateCount = length - prefixSize + 1;
        int wordEnd = offset + ((candidateCount / Long.BYTES) * Long.BYTES);
        int backOffset = prefixSize - 1;
        int position = offset;

        for (; position < wordEnd; position += Long.BYTES) {
            long frontBytes = (long) LONG_HANDLE.get(data, position);
            long backBytes = (long) LONG_HANDLE.get(data, position + backOffset);
            long candidates = foldCaseMatches(frontBytes, prefix[0]) &
                    foldCaseMatches(backBytes, prefix[backOffset]);
            while (candidates != 0) {
                int candidate = position + (Long.numberOfTrailingZeros(candidates) >>> 3);
                if (foldCasePrefixMatchesAt(data, candidate)) {
                    return candidate;
                }
                candidates &= candidates - 1;
            }
        }

        int end = offset + candidateCount;
        for (; position < end; position++) {
            if (byteMatches(data[position], prefix[0], true) &&
                    byteMatches(data[position + backOffset], prefix[backOffset], true) &&
                    foldCasePrefixMatchesAt(data, position)) {
                return position;
            }
        }
        return -1;
    }

    private static long foldCaseMatches(long data, byte expected)
    {
        long lowBits = 0x0101010101010101L;
        long highBits = 0x8080808080808080L;
        int lowerCaseByte = asciiLower(expected & 0xFF);
        long lowerCaseBroadcast = (lowerCaseByte & 0xFFL) * lowBits;
        long xor = data ^ lowerCaseBroadcast;
        long matches = (xor - lowBits) & ~xor & highBits;
        if (lowerCaseByte >= 'a' && lowerCaseByte <= 'z') {
            long upperCaseBroadcast = (lowerCaseByte - ('a' - 'A')) * lowBits;
            xor = data ^ upperCaseBroadcast;
            matches |= (xor - lowBits) & ~xor & highBits;
        }
        return matches;
    }

    /// Returns absolute index or -1 if not found.
    private int indexOf(byte[] data, int offset, int length, byte b, boolean foldCase)
    {
        if (length <= 0) {
            return -1;
        }

        // Case-insensitive is complex for vectorization, fall back to byte-by-byte
        if (foldCase) {
            return indexOfFoldCase(data, offset, length, b);
        }

        // Use pre-computed broadcast mask when searching for the prefix front byte
        long broadcastMask = (prefix != null && b == prefix[0]) ? prefixFrontBroadcastMask : (b & 0xFFL) * 0x0101010101010101L;

        return indexOfSWAR(data, offset, length, broadcastMask);
    }

    /// Case-insensitive byte search using the SWAR path.
    private int indexOfFoldCase(byte[] data, int offset, int length, byte b)
    {
        int lowerCaseByte = asciiLower(b & 0xFF);
        if (lowerCaseByte < 'a' || lowerCaseByte > 'z') {
            long broadcastMask = (lowerCaseByte & 0xFFL) * 0x0101010101010101L;
            return indexOfSWAR(data, offset, length, broadcastMask);
        }

        byte lowerCase = (byte) lowerCaseByte;
        byte upperCase = (byte) (lowerCaseByte - ('a' - 'A'));
        return indexOfEitherSWAR(data, offset, length, lowerCase, upperCase);
    }

    private int indexOfEitherSWAR(byte[] data, int offset, int length, byte first, byte second)
    {
        long lowBits = 0x0101010101010101L;
        long highBits = 0x8080808080808080L;
        long firstBroadcast = (first & 0xFFL) * lowBits;
        long secondBroadcast = (second & 0xFFL) * lowBits;
        int end = offset + length;
        int vectorEnd = offset + ((length / 16) * 16);

        for (int position = offset; position < vectorEnd; position += 16) {
            long firstWord = (long) LONG_HANDLE.get(data, position);
            long secondWord = (long) LONG_HANDLE.get(data, position + Long.BYTES);
            long firstXor = firstWord ^ firstBroadcast;
            long secondXor = firstWord ^ secondBroadcast;
            long firstMatches = ((firstXor - lowBits) & ~firstXor & highBits) |
                    ((secondXor - lowBits) & ~secondXor & highBits);

            firstXor = secondWord ^ firstBroadcast;
            secondXor = secondWord ^ secondBroadcast;
            long secondMatches = ((firstXor - lowBits) & ~firstXor & highBits) |
                    ((secondXor - lowBits) & ~secondXor & highBits);

            if ((firstMatches | secondMatches) != 0) {
                if (firstMatches != 0) {
                    return position + (Long.numberOfTrailingZeros(firstMatches) >>> 3);
                }
                return position + Long.BYTES + (Long.numberOfTrailingZeros(secondMatches) >>> 3);
            }
        }

        for (int position = vectorEnd; position < end; position++) {
            byte value = data[position];
            if (value == first || value == second) {
                return position;
            }
        }
        return -1;
    }

    /// SWAR (SIMD Within A Register) byte scanning using Long operations.
    /// Processes 16 bytes (2 longs) per iteration with minimal branching.
    /// Throughput: ~33.7 GB/s on 16MB buffers (5.5x faster than byte-by-byte).
    private int indexOfSWAR(byte[] data, int offset, int length, long broadcastMask)
    {
        long loMask = 0x0101010101010101L;
        long hiMask = 0x8080808080808080L;

        int end = offset + length;
        // Process 16 bytes (2 longs) at a time - reduces branch frequency
        int end16 = offset + ((length / 16) * 16);

        for (int i = offset; i < end16; i += 16) {
            // Load 2 longs (16 bytes) - no branches in load/compute
            long c0 = (long) LONG_HANDLE.get(data, i);
            long c1 = (long) LONG_HANDLE.get(data, i + 8);

            // XOR with broadcast mask - matching bytes become 0x00
            long x0 = c0 ^ broadcastMask;
            long x1 = c1 ^ broadcastMask;

            // Detect zero bytes using SWAR technique
            long z0 = (x0 - loMask) & ~x0 & hiMask;
            long z1 = (x1 - loMask) & ~x1 & hiMask;

            // Combine - only ONE branch per 16 bytes
            long anyMatch = z0 | z1;

            if (anyMatch != 0) {
                // Find which chunk and byte position
                if (z0 != 0) {
                    return i + (Long.numberOfTrailingZeros(z0) >>> 3);
                }
                if (z1 != 0) {
                    return i + 8 + (Long.numberOfTrailingZeros(z1) >>> 3);
                }
            }
        }

        // Tail: byte-by-byte for remaining bytes
        byte b = (byte) broadcastMask;
        for (int i = end16; i < end; i++) {
            if (data[i] == b) {
                return i;
            }
        }

        return -1;
    }

    /// Check if two bytes match, accounting for optional case folding.
    private boolean byteMatches(byte a, byte b, boolean foldCase)
    {
        if (!foldCase) {
            return a == b;
        }
        return asciiLower(a & 0xFF) == asciiLower(b & 0xFF);
    }

    /// Returns whether a non-folded prefix occurs at `position`. The first-byte loop of
    /// [#prefixAccel] needs no matched length, and this check compiles smaller than
    /// [#prefixMatchingLength].
    private boolean prefixMatchesAt(byte[] data, int position)
    {
        for (int index = 0; index < prefixSize; index++) {
            if (data[position + index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    /// Returns how many leading bytes of a non-folded prefix occur at `position`.
    private int prefixMatchingLength(byte[] data, int position)
    {
        for (int index = 0; index < prefixSize; index++) {
            if (data[position + index] != prefix[index]) {
                return index;
            }
        }
        return prefixSize;
    }

    private boolean foldCasePrefixMatchesAt(byte[] data, int i)
    {
        // ASCII case-insensitive comparison (matches RE2's byte-oriented foldCase hint).
        for (int j = 0; j < prefixSize; j++) {
            int a = asciiLower(data[i + j] & 0xFF);
            int b = asciiLower(prefix[j] & 0xFF);
            if (a != b) {
                return false;
            }
        }
        return true;
    }

    private static int asciiLower(int b)
    {
        if ('A' <= b && b <= 'Z') {
            return b + ('a' - 'A');
        }
        return b;
    }

    /// Check if pattern has a required prefix (for instant rejection).
    /// Required prefix means pattern is "^literal..." and can be rejected if text doesn't match.
    public boolean hasRequiredPrefix()
    {
        return requiredPrefix != null && requiredPrefix.length > 0;
    }

    /// Get the required prefix bytes (null if none).
    public byte[] getRequiredPrefix()
    {
        return requiredPrefix;
    }

    /// Check if required prefix uses case-insensitive matching.
    public boolean isRequiredPrefixFoldCase()
    {
        return requiredPrefixFoldCase;
    }

    /// Configure the required prefix extracted from the regexp AST.
    /// Called by Compiler after compilation.
    public void configureRequiredPrefix(Slice prefix, boolean foldCase)
    {
        if (prefix == null || prefix.length() == 0) {
            this.requiredPrefix = null;
            this.requiredPrefixFoldCase = false;
            return;
        }

        byte[] bytes = new byte[prefix.length()];
        System.arraycopy(prefix.byteArray(), prefix.byteArrayOffset(), bytes, 0, prefix.length());

        this.requiredPrefix = bytes;
        this.requiredPrefixFoldCase = foldCase;
    }

    /// Check if text starts with the required prefix.
    public boolean textMatchesRequiredPrefix(byte[] data, int offset, int length)
    {
        if (!hasRequiredPrefix()) {
            throw new IllegalStateException("required prefix not configured");
        }

        // If text is shorter than prefix, can't match
        if (length < requiredPrefix.length) {
            return false;
        }

        if (!requiredPrefixFoldCase) {
            for (int j = 0; j < requiredPrefix.length; j++) {
                if (data[offset + j] != requiredPrefix[j]) {
                    return false;
                }
            }
            return true;
        }

        // ASCII case-insensitive comparison
        for (int j = 0; j < requiredPrefix.length; j++) {
            int a = asciiLower(data[offset + j] & 0xFF);
            int b = asciiLower(requiredPrefix[j] & 0xFF);
            if (a != b) {
                return false;
            }
        }
        return true;
    }

    /// Set the DFA memory budget (called from CompiledPattern.build()).
    /// Like upstream Prog::set_dfa_mem().
    public void setDfaMemory(long dfaMemory)
    {
        this.dfaMemory = dfaMemory;
    }

    /// Get the DFA memory budget.
    /// Like upstream Prog::dfa_mem().
    public long dfaMemory()
    {
        return dfaMemory;
    }

    /// Sets the capture NFA's plan; see [Nfa#planCaptureSearches].
    void setCapturePlan(int singlePassCaptureSlots, int boundedPassCaptureSlots)
    {
        this.singlePassCaptureSlots = singlePassCaptureSlots;
        this.boundedPassCaptureSlots = boundedPassCaptureSlots;
    }

    /// Returns the most slots a capture NFA search tracks in one ordinary pass.
    int singlePassCaptureSlots()
    {
        return singlePassCaptureSlots;
    }

    /// Returns the most slots each bounded capture NFA pass tracks.
    int boundedPassCaptureSlots()
    {
        return boundedPassCaptureSlots;
    }

    /// Get or create cached DFA with memory budget.
    /// Like upstream Prog::GetDFA().
    public Dfa.DfaInstance getCachedDfa(Dfa.DfaInstance.Kind kind)
    {
        requireNonNull(kind, "kind is null");
        AtomicReference<Dfa.DfaInstance> ref = dfaReference(kind);

        // Fast path - already cached
        Dfa.DfaInstance cached = ref.get();
        if (cached != null) {
            return cached;
        }

        // Lazy initialization with memory budget
        long budget = dfaBudget(dfaMemory, kind);
        if (budget <= 0) {
            return null;
        }
        // A DFA that cannot initialize is not cached, so reject it before it builds its work
        // queues, which are sized by the program and would otherwise be rebuilt by every search.
        if (budget < Dfa.DfaInstance.minimumMemory(this, kind) || failedDfaBudgets.get(kind.ordinal()) == budget) {
            return null;
        }
        Dfa.DfaInstance newDfa = new Dfa.DfaInstance(this, kind, budget);

        if (!newDfa.ok()) {
            // The minimum leaves out a forward program's start-byte tables, so a budget that
            // passes it can still fail here.
            failedDfaBudgets.set(kind.ordinal(), budget);
            return null;
        }

        // CAS to cache
        if (ref.compareAndSet(null, newDfa)) {
            return newDfa;
        }
        // Lost race - use winner's DFA
        return ref.get();
    }

    /// Whether taking `reservation` bytes from the DFA memory budget leaves the first-match and
    /// longest-match DFAs, which this program's searches build, each the memory it needs to
    /// initialize. A program whose assertions the DFA cannot run builds neither.
    boolean leavesDfaMinimum(long reservation)
    {
        if (reservation > dfaMemory) {
            return false;
        }
        if (hasDfaUnsupportedAssertions()) {
            return true;
        }
        long remaining = dfaMemory - reservation;
        return dfaBudget(remaining, Dfa.DfaInstance.Kind.FIRST_MATCH) >= Dfa.DfaInstance.minimumMemory(this, Dfa.DfaInstance.Kind.FIRST_MATCH) &&
                dfaBudget(remaining, Dfa.DfaInstance.Kind.LONGEST_MATCH) >= Dfa.DfaInstance.minimumMemory(this, Dfa.DfaInstance.Kind.LONGEST_MATCH);
    }

    /// Whether taking `reservation` bytes from the DFA memory budget leaves the DFA of `kind` at
    /// least the memory its constructor charges to initialize, the start-byte tables of a forward
    /// program included. [#leavesDfaMinimum(long)] compares against a lower bound that leaves
    /// those tables out. This builds them to measure them only when the budget lies within their
    /// largest charge above that bound. A program whose assertions the DFA cannot run builds no
    /// DFA, so for it this returns true whenever the reservation fits the DFA memory budget.
    boolean leavesDfaInitialization(long reservation, Dfa.DfaInstance.Kind kind)
    {
        if (reservation > dfaMemory) {
            return false;
        }
        if (hasDfaUnsupportedAssertions()) {
            return true;
        }
        return Dfa.DfaInstance.initializes(this, kind, dfaBudget(dfaMemory - reservation, kind));
    }

    private long dfaBudget(long memory, Dfa.DfaInstance.Kind kind)
    {
        return switch (kind) {
            case MANY_MATCH -> memory;
            case FIRST_MATCH -> memory / 2;
            case LONGEST_MATCH -> reversed ? memory : memory / 2;
        };
    }

    Dfa.DfaInstance cachedDfaIfPresent(Dfa.DfaInstance.Kind kind)
    {
        requireNonNull(kind, "kind is null");
        return dfaReference(kind).get();
    }

    private AtomicReference<Dfa.DfaInstance> dfaReference(Dfa.DfaInstance.Kind kind)
    {
        return switch (kind) {
            case FIRST_MATCH -> dfaFirstMatch;
            case LONGEST_MATCH -> dfaLongestMatch;
            case MANY_MATCH -> dfaManyMatch;
        };
    }

    NoSubmatchTables getOrCreateNoSubmatchTables()
    {
        NoSubmatchTables cached = noSubmatchNfaTables.get();
        if (cached != null) {
            return cached;
        }

        NoSubmatchTables created = NoSubmatchTables.build(this);
        if (noSubmatchNfaTables.compareAndSet(null, created)) {
            return created;
        }
        return noSubmatchNfaTables.get();
    }

    long[] getOrCreateCaptureInstructionWords()
    {
        long[] cached = captureInstructionWords;
        if (cached != null) {
            return cached;
        }
        return createCaptureInstructionWords();
    }

    private synchronized long[] createCaptureInstructionWords()
    {
        if (captureInstructionWords != null) {
            return captureInstructionWords;
        }

        if (!didFlatten) {
            throw new IllegalStateException("program must be flattened before packing capture instructions");
        }

        captureInstructionWords = buildCaptureInstructionWords();
        return captureInstructionWords;
    }

    private long[] buildCaptureInstructionWords()
    {
        // [0..2] opcode, [3] last, [4..31] output, [32..63] opcode-specific payload.
        // ALT_MATCH uses payload bit 31 for greedy and BYTE_RANGE uses it for fold-case.
        long[] words = new long[size()];
        int outputMask = MAX_FLATTENED_INSTRUCTIONS;
        for (int instructionId = 0; instructionId < words.length; instructionId++) {
            Inst instruction = inst(instructionId);
            checkPackedInstructionOutput(instruction.out(), outputMask);
            long payload = switch (instruction.opcode()) {
                case ALT -> {
                    checkPackedInstructionOutput(instruction.out1(), outputMask);
                    yield instruction.out1();
                }
                case ALT_MATCH -> {
                    checkPackedInstructionOutput(instruction.out1(), outputMask);
                    yield instruction.out1() |
                            (instruction.greedy(this) ? 1L << 31 : 0);
                }
                case BYTE_RANGE -> instruction.lo() |
                        (long) instruction.hi() << 8 |
                        (long) instruction.hint() << 16 |
                        (instruction.foldCase() ? 1L << 31 : 0);
                case CAPTURE -> Integer.toUnsignedLong(instruction.cap());
                case EMPTY_WIDTH -> Integer.toUnsignedLong(instruction.empty());
                case MATCH -> Integer.toUnsignedLong(instruction.matchId());
                case NOP, FAIL -> 0;
            };
            words[instructionId] = instruction.opcode().ordinal() |
                    (instruction.last() ? 1L << 3 : 0) |
                    (long) instruction.out() << 4 |
                    payload << 32;
        }
        return words;
    }

    private static void checkPackedInstructionOutput(int output, int outputMask)
    {
        if (output < 0 || output > outputMask) {
            throw new IllegalStateException("output exceeds packed instruction limit: " + output);
        }
    }

    static final class NoSubmatchTables
    {
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
        private final int addStackSize;

        private NoSubmatchTables(
                byte[] op,
                int[] out,
                int[] out1,
                int[] hint,
                int[] lo,
                int[] hi,
                int[] empty,
                int[] outLast,
                boolean[] foldCase,
                boolean[] altMatchGreedy,
                boolean hasCaptureOps,
                int addStackSize)
        {
            this.op = op;
            this.out = out;
            this.out1 = out1;
            this.hint = hint;
            this.lo = lo;
            this.hi = hi;
            this.empty = empty;
            this.outLast = outLast;
            this.foldCase = foldCase;
            this.altMatchGreedy = altMatchGreedy;
            this.hasCaptureOps = hasCaptureOps;
            this.addStackSize = addStackSize;
        }

        private static NoSubmatchTables build(Prog prog)
        {
            int progSize = prog.size();
            byte[] op = new byte[progSize];
            int[] out = new int[progSize];
            int[] out1 = new int[progSize];
            int[] hint = new int[progSize];
            int[] lo = new int[progSize];
            int[] hi = new int[progSize];
            int[] empty = new int[progSize];
            int[] outLast = new int[progSize];
            boolean[] foldCase = new boolean[progSize];
            boolean[] altMatchGreedy = new boolean[progSize];

            int captures = 0;
            for (int id = 0; id < progSize; id++) {
                Inst inst = prog.inst(id);
                InstOp opcode = inst.opcode();
                op[id] = (byte) opcode.ordinal();
                int outValue = inst.out();
                out[id] = outValue;
                out1[id] = inst.out1();
                hint[id] = inst.hint();
                lo[id] = inst.lo();
                hi[id] = inst.hi();
                empty[id] = inst.empty();
                outLast[id] = (outValue << 1) | (inst.last() ? 1 : 0);
                foldCase[id] = inst.foldCase();

                if (opcode == InstOp.CAPTURE) {
                    captures++;
                }
            }

            byte opAltMatch = (byte) InstOp.ALT_MATCH.ordinal();
            byte opByteRange = (byte) InstOp.BYTE_RANGE.ordinal();
            byte opNop = (byte) InstOp.NOP.ordinal();
            for (int id = 0; id < progSize; id++) {
                if (op[id] != opAltMatch) {
                    continue;
                }
                int outId = out[id];
                byte outOp = op[outId];
                altMatchGreedy[id] = outOp == opByteRange ||
                        (outOp == opNop && op[out[outId]] == opByteRange);
            }

            boolean hasCaptureOps = captures != 0;
            int addStackSize = Nfa.groupZeroStackSize(prog);

            return new NoSubmatchTables(
                    op,
                    out,
                    out1,
                    hint,
                    lo,
                    hi,
                    empty,
                    outLast,
                    foldCase,
                    altMatchGreedy,
                    hasCaptureOps,
                    addStackSize);
        }

        byte[] op()
        {
            return op;
        }

        int[] out()
        {
            return out;
        }

        int[] out1()
        {
            return out1;
        }

        int[] hint()
        {
            return hint;
        }

        int[] lo()
        {
            return lo;
        }

        int[] hi()
        {
            return hi;
        }

        int[] empty()
        {
            return empty;
        }

        int[] outLast()
        {
            return outLast;
        }

        boolean[] foldCase()
        {
            return foldCase;
        }

        boolean[] altMatchGreedy()
        {
            return altMatchGreedy;
        }

        boolean hasCaptureOps()
        {
            return hasCaptureOps;
        }

        int addStackSize()
        {
            return addStackSize;
        }
    }

    public boolean didFlatten()
    {
        return didFlatten;
    }

    public void setDidFlatten(boolean didFlatten)
    {
        this.didFlatten = didFlatten;
    }

    public int bytemapRange()
    {
        return bytemapRange;
    }

    public void setBytemapRange(int bytemapRange)
    {
        this.bytemapRange = bytemapRange;
    }

    public void setBytemap(int c, int cls)
    {
        if (c < 0 || c > 255) {
            throw new IllegalArgumentException("byte out of range: " + c);
        }
        bytemap[c] = (byte) cls;
    }

    public int bytemap(int c)
    {
        if (c < 0 || c > 255) {
            throw new IllegalArgumentException("byte out of range: " + c);
        }
        return bytemap[c] & 0xFF;
    }

    public byte[] bytemapArray()
    {
        return bytemap;
    }

    public int add(Inst instruction)
    {
        requireNonNull(instruction, "instruction is null");
        if (instructionCount == insts.length) {
            insts = Arrays.copyOf(insts, insts.length * 2);
        }
        insts[instructionCount] = instruction;
        return instructionCount++;
    }

    public Inst inst(int instructionId)
    {
        if (instructionId < 0 || instructionId >= instructionCount) {
            throw new IllegalArgumentException("instructionId out of range: " + instructionId);
        }
        return insts[instructionId];
    }

    public List<Inst> insts()
    {
        return List.of(Arrays.copyOf(insts, instructionCount));
    }

    public String dump()
    {
        if (didFlatten) {
            return flattenedProgToString(start);
        }

        SparseSet queue = new SparseSet(size() + 1);
        addToQueue(queue, start);
        return progToString(queue);
    }

    public String dumpUnanchored()
    {
        if (didFlatten) {
            return flattenedProgToString(startUnanchored);
        }

        SparseSet queue = new SparseSet(size() + 1);
        addToQueue(queue, startUnanchored);
        return progToString(queue);
    }

    public String dumpByteMap()
    {
        StringBuilder builder = new StringBuilder();
        for (int c = 0; c < 256; c++) {
            int byteClass = bytemap(c);
            int low = c;
            while (c < 255 && bytemap(c + 1) == byteClass) {
                c++;
            }
            int high = c;
            builder.append(String.format(Locale.ROOT, "[%02x-%02x] -> %d%n", low, high, byteClass));
        }
        return builder.toString();
    }

    public void computeByteMap()
    {
        // Fill in bytemap with byte classes for the program.
        // Ranges of bytes that are treated indistinguishably
        // will be mapped to a single byte class.
        ByteMapBuilder builder = new ByteMapBuilder();

        boolean markedLineBoundaries = false;
        boolean markedWordBoundaries = false;
        boolean markedCarriageReturn = false;
        boolean markedNonAscii = false;

        for (int id = 0; id < size(); id++) {
            Inst instruction = inst(id);
            if (instruction.opcode() == InstOp.BYTE_RANGE) {
                int lo = instruction.lo();
                int hi = instruction.hi();
                builder.mark(lo, hi);
                if (instruction.foldCase() && lo <= 'z' && hi >= 'a') {
                    int foldlo = lo;
                    int foldhi = hi;
                    if (foldlo < 'a') {
                        foldlo = 'a';
                    }
                    if (foldhi > 'z') {
                        foldhi = 'z';
                    }
                    if (foldlo <= foldhi) {
                        foldlo += 'A' - 'a';
                        foldhi += 'A' - 'a';
                        builder.mark(foldlo, foldhi);
                    }
                }

                // If this Inst is not the last Inst in its list AND the next Inst is
                // also a ByteRange AND the Insts have the same out, defer the merge.
                if (!instruction.last() &&
                        id + 1 < size() &&
                        inst(id + 1).opcode() == InstOp.BYTE_RANGE &&
                        instruction.out() == inst(id + 1).out()) {
                    continue;
                }
                builder.merge();
            }
            else if (instruction.opcode() == InstOp.EMPTY_WIDTH) {
                hasEmptyWidthAssertions = true;
                int empty = instruction.empty();
                if ((empty & EmptyOp.TEXT_DEPENDENT) != 0) {
                    textDependentAssertions |= empty & EmptyOp.TEXT_DEPENDENT;
                }
                if ((empty & (EmptyOp.EMPTY_BEGIN_LINE |
                        EmptyOp.EMPTY_END_LINE |
                        EmptyOp.EMPTY_TRINO_BEGIN_LINE |
                        EmptyOp.EMPTY_TRINO_END_LINE |
                        EmptyOp.RUST_CRLF_LINE)) != 0 && !markedLineBoundaries) {
                    builder.mark('\n', '\n');
                    builder.merge();
                    markedLineBoundaries = true;
                }
                // The DFA evaluates a Rust CRLF anchor from CR and LF separately, and a word
                // assertion from whether each neighboring byte is an ASCII word byte. In a program
                // with a word assertion that decodes the text, it quits on a byte outside ASCII,
                // which a byte class can carry only if the bytes 0x80-0xFF never share it with an
                // ASCII byte.
                if ((empty & EmptyOp.RUST_CRLF_LINE) != 0 && !markedCarriageReturn) {
                    builder.mark('\r', '\r');
                    builder.merge();
                    markedCarriageReturn = true;
                }
                if ((empty & EmptyOp.DECODING_WORD_ASSERTIONS) != 0 && !markedNonAscii) {
                    builder.mark(0x80, 0xFF);
                    builder.merge();
                    markedNonAscii = true;
                }
                if ((empty & (EmptyOp.EMPTY_WORD_BOUNDARY | EmptyOp.EMPTY_NO_WORD_BOUNDARY | EmptyOp.RUST_WORD_ASSERTIONS | EmptyOp.JAVA_TRINO_WORD_BOUNDARIES)) != 0 && !markedWordBoundaries) {
                    // Keep the original two-phase order:
                    // first mark word ranges, then mark non-word ranges.
                    markRanges(builder, WORD_RANGES);
                    builder.merge();
                    markRanges(builder, NON_WORD_RANGES);
                    builder.merge();
                    markedWordBoundaries = true;
                }
            }
        }

        int[] outRange = new int[1];
        builder.build(bytemap, outRange);
        bytemapRange = outRange[0];

        anchoredStartBytes = null;
        if (anchorStart && !reversed && !canMatchEmpty && start() != 0) {
            byte[] candidates = new byte[256];
            int count = walkStartBytes(new int[3 * size() + 1], candidates, false, true);
            if (count > 0 && count < 256) {
                anchoredStartBytes = candidates;
            }
        }
    }

    /// Computes a lexicographic range `[min, max]` such that any string `s` that is a
    /// full (anchored) match for this program satisfies `min <= s && s <= max`.
    ///
    /// The returned `min`/`max` are truncated to at most `maxLength` bytes. When the
    /// underlying language has no maximum element (e.g., matches all non-empty strings in Latin-1),
    /// this method returns `null`.
    ///
    /// Note: Like upstream, this only considers the first copy of an infinitely repeated element.
    public PossibleMatchRangeResult possibleMatchRange(int maxLength)
    {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must be >= 0");
        }

        // Start "before" the first byte: no previous byte (-1).
        int[] startInstructionIds = normalizeStartInstructions(start(), EmptyOp.EMPTY_BEGIN_TEXT | EmptyOp.EMPTY_BEGIN_LINE);
        DfaState startState = new DfaState(startInstructionIds, -1, false, stateNeedsContext(startInstructionIds));

        // Build minimum string.
        StateVisitMap seen = new StateVisitMap();
        byte[] minimumBuffer = new byte[maxLength];
        int minimumLength = 0;
        DfaState state = startState;
        for (int i = 0; i < maxLength; i++) {
            if (seen.get(state) > 0) {
                break;
            }
            seen.increment(state);

            if (endTextIsMatch(state)) {
                break;
            }

            boolean extended = false;
            for (int byteValue = 0; byteValue < 256; byteValue++) {
                NextStep step = runOnByte(state, byteValue);
                if (step != null) {
                    DfaState nextState = new DfaState(
                            step.instructionIds(),
                            step.previousByte(),
                            step.match(),
                            stateNeedsContext(step.instructionIds()));
                    minimumBuffer[minimumLength++] = (byte) byteValue;
                    state = nextState;
                    extended = true;
                    break;
                }
            }
            if (!extended) {
                break;
            }
        }

        // Build maximum string.
        seen.clear();
        byte[] maximumBuffer = new byte[maxLength];
        int maximumLength = 0;
        state = startState;
        for (int i = 0; i < maxLength; i++) {
            if (seen.get(state) > 0) {
                break;
            }
            seen.increment(state);

            boolean extended = false;
            for (int byteValue = 255; byteValue >= 0; byteValue--) {
                NextStep step = runOnByte(state, byteValue);
                if (step != null) {
                    DfaState nextState = new DfaState(
                            step.instructionIds(),
                            step.previousByte(),
                            step.match(),
                            stateNeedsContext(step.instructionIds()));
                    maximumBuffer[maximumLength++] = (byte) byteValue;
                    state = nextState;
                    extended = true;
                    break;
                }
            }
            if (!extended) {
                return new PossibleMatchRangeResult(
                        Slices.wrappedBuffer(copyOfBytes(minimumBuffer, minimumLength)),
                        Slices.wrappedBuffer(copyOfBytes(maximumBuffer, maximumLength)));
            }
        }

        // Stopped while still adding to max - round up to the next prefix successor.
        maximumLength = prefixSuccessor(maximumBuffer, maximumLength);
        if (maximumLength == 0) {
            return null;
        }

        return new PossibleMatchRangeResult(
                Slices.wrappedBuffer(copyOfBytes(minimumBuffer, minimumLength)),
                Slices.wrappedBuffer(copyOfBytes(maximumBuffer, maximumLength)));
    }

    private static byte[] copyOfBytes(byte[] buffer, int length)
    {
        if (length == 0) {
            return new byte[0];
        }
        byte[] result = new byte[length];
        System.arraycopy(buffer, 0, result, 0, length);
        return result;
    }

    private static int[] copyOfInts(int[] buffer, int length)
    {
        if (length == 0) {
            return new int[0];
        }
        int[] result = new int[length];
        System.arraycopy(buffer, 0, result, 0, length);
        return result;
    }

    private boolean endTextIsMatch(DfaState state)
    {
        EpsilonClosure closure = epsilonClosure(state, -1);
        return closure.match;
    }

    private NextStep runOnByte(DfaState state, int byteValue)
    {
        EpsilonClosure closure = epsilonClosure(state, byteValue);
        if (closure.byteRangeInstructionIds.length == 0) {
            return null;
        }

        int[] nextInstructionIds = new int[closure.byteRangeInstructionIds.length];
        int nextInstructionCount = 0;
        for (int instructionId : closure.byteRangeInstructionIds) {
            Inst instruction = inst(instructionId);
            if (instruction.matches(byteValue)) {
                nextInstructionIds[nextInstructionCount++] = instruction.out();
            }
        }
        if (nextInstructionCount == 0) {
            return null;
        }

        nextInstructionIds = uniqueSorted(nextInstructionIds, nextInstructionCount);
        // Match is delayed by one byte, so this flag represents a match ending immediately
        // before the byte currently being processed.
        return new NextStep(nextInstructionIds, byteValue & 0xFF, closure.match);
    }

    private EpsilonClosure epsilonClosure(DfaState state, int nextByte)
    {
        // Evaluate empty-width operations at the boundary around nextByte.
        int emptyFlags = state.needsContext ? emptyFlags(state.previousByte, nextByte) : 0;

        boolean[] visited = new boolean[size()];
        int[] stack = new int[Math.max(16, state.instructionIds.length)];
        int stackPointer = 0;
        for (int instructionId : state.instructionIds) {
            if (instructionId <= 0 || instructionId >= size()) {
                // Instruction zero is FAIL; out-of-range values are treated as failures.
                continue;
            }
            stack = ensureCapacity(stack, stackPointer + 1);
            stack[stackPointer++] = instructionId;
        }

        int[] byteRangeInstructionIds = new int[16];
        int byteRangeCount = 0;
        boolean match = false;

        while (stackPointer != 0) {
            int instructionId = stack[--stackPointer];
            if (instructionId <= 0 || instructionId >= size()) {
                continue;
            }
            if (visited[instructionId]) {
                continue;
            }
            visited[instructionId] = true;

            Inst instruction = inst(instructionId);
            switch (instruction.opcode()) {
                case ALT, ALT_MATCH -> {
                    stack = ensureCapacity(stack, stackPointer + 2);
                    stack[stackPointer++] = instruction.out();
                    stack[stackPointer++] = instruction.out1();
                    if (!instruction.last()) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case NOP, CAPTURE -> {
                    stack = ensureCapacity(stack, stackPointer + 1);
                    stack[stackPointer++] = instruction.out();
                    if (!instruction.last()) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case EMPTY_WIDTH -> {
                    if ((instruction.empty() & emptyFlags) == instruction.empty()) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instruction.out();
                    }
                    if (!instruction.last()) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case BYTE_RANGE -> {
                    if (byteRangeCount == byteRangeInstructionIds.length) {
                        int[] expandedInstructionIds = new int[byteRangeInstructionIds.length * 2];
                        System.arraycopy(byteRangeInstructionIds, 0, expandedInstructionIds, 0, byteRangeInstructionIds.length);
                        byteRangeInstructionIds = expandedInstructionIds;
                    }
                    byteRangeInstructionIds[byteRangeCount++] = instructionId;
                    if (!instruction.last()) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case MATCH -> {
                    match = true;
                    if (!instruction.last()) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case FAIL -> {
                    if (!instruction.last()) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
            }
        }

        return new EpsilonClosure(copyOfInts(byteRangeInstructionIds, byteRangeCount), match);
    }

    private static int[] ensureCapacity(int[] stack, int requiredCapacity)
    {
        if (requiredCapacity <= stack.length) {
            return stack;
        }
        int newLength = stack.length;
        while (newLength < requiredCapacity) {
            newLength *= 2;
        }
        int[] expandedStack = new int[newLength];
        System.arraycopy(stack, 0, expandedStack, 0, stack.length);
        return expandedStack;
    }

    private static int[] uniqueSorted(int[] values, int count)
    {
        Arrays.sort(values, 0, count);
        int writeIndex = 0;
        int lastValue = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            int value = values[i];
            if (value != lastValue) {
                values[writeIndex++] = value;
                lastValue = value;
            }
        }
        if (writeIndex == count) {
            int[] result = new int[count];
            System.arraycopy(values, 0, result, 0, count);
            return result;
        }
        int[] result = new int[writeIndex];
        System.arraycopy(values, 0, result, 0, writeIndex);
        return result;
    }

    private static int emptyFlags(int previousByte, int nextByte)
    {
        boolean atBeginText = previousByte < 0;
        boolean atEndText = nextByte < 0;

        boolean atBeginLine = atBeginText || previousByte == '\n';
        boolean atEndLine = atEndText || nextByte == '\n';

        boolean previousIsWord = !atBeginText && isWordChar(previousByte);
        boolean nextIsWord = !atEndText && isWordChar(nextByte);
        boolean boundary = previousIsWord != nextIsWord;

        int flags = 0;
        if (atBeginText) {
            flags |= EmptyOp.EMPTY_BEGIN_TEXT;
        }
        if (atEndText) {
            flags |= EmptyOp.EMPTY_END_TEXT;
        }
        if (atBeginLine) {
            flags |= EmptyOp.EMPTY_BEGIN_LINE;
        }
        if (atEndLine) {
            flags |= EmptyOp.EMPTY_END_LINE;
        }
        if (boundary) {
            flags |= EmptyOp.EMPTY_WORD_BOUNDARY;
        }
        else {
            flags |= EmptyOp.EMPTY_NO_WORD_BOUNDARY;
        }
        return flags;
    }

    private static int prefixSuccessor(byte[] buffer, int length)
    {
        int i = length - 1;
        while (i >= 0 && (buffer[i] & 0xFF) == 0xFF) {
            i--;
        }
        if (i < 0) {
            return 0;
        }
        buffer[i] = (byte) ((buffer[i] & 0xFF) + 1);
        return i + 1;
    }

    private record EpsilonClosure(int[] byteRangeInstructionIds, boolean match) {}

    private record NextStep(int[] instructionIds, int previousByte, boolean match) {}

    private static final class DfaState
    {
        final int[] instructionIds;
        final int previousByte; // -1 means beginning-of-text
        final boolean match;
        final boolean needsContext;

        DfaState(int[] instructionIds, int previousByte, boolean match, boolean needsContext)
        {
            this.instructionIds = requireNonNull(instructionIds, "instructionIds is null");
            this.previousByte = previousByte;
            this.match = match;
            this.needsContext = needsContext;
        }

        @Override
        public boolean equals(Object object)
        {
            if (this == object) {
                return true;
            }
            if (!(object instanceof DfaState other)) {
                return false;
            }
            if (!needsContext && !other.needsContext) {
                return match == other.match && Arrays.equals(instructionIds, other.instructionIds);
            }
            return needsContext == other.needsContext &&
                    previousByte == other.previousByte &&
                    match == other.match &&
                    Arrays.equals(instructionIds, other.instructionIds);
        }

        @Override
        public int hashCode()
        {
            int hash = Arrays.hashCode(instructionIds);
            hash = 31 * hash + (match ? 1 : 0);
            if (needsContext) {
                hash = 31 * hash + previousByte;
                hash = 31 * hash + 1;
            }
            return hash;
        }
    }

    private boolean stateNeedsContext(int[] instructionIds)
    {
        // Context flags matter when the state has an empty-width instruction waiting to execute.
        boolean[] visited = new boolean[size()];
        int[] stack = new int[Math.max(16, instructionIds.length)];
        int stackPointer = 0;
        for (int instructionId : instructionIds) {
            if (instructionId <= 0 || instructionId >= size()) {
                continue;
            }
            stack = ensureCapacity(stack, stackPointer + 1);
            stack[stackPointer++] = instructionId;
        }

        while (stackPointer != 0) {
            int instructionId = stack[--stackPointer];
            if (instructionId <= 0 || instructionId >= size()) {
                continue;
            }
            if (visited[instructionId]) {
                continue;
            }
            visited[instructionId] = true;

            Inst instruction = inst(instructionId);
            if (instruction.opcode() == InstOp.EMPTY_WIDTH) {
                return true;
            }

            // Follow epsilon transitions irrespective of whether EMPTY_WIDTH would pass;
            // we're only interested in whether the instruction is present.
            switch (instruction.opcode()) {
                case ALT, ALT_MATCH -> {
                    stack = ensureCapacity(stack, stackPointer + 2);
                    stack[stackPointer++] = instruction.out();
                    stack[stackPointer++] = instruction.out1();
                }
                case NOP, CAPTURE -> {
                    stack = ensureCapacity(stack, stackPointer + 1);
                    stack[stackPointer++] = instruction.out();
                }
                case BYTE_RANGE, MATCH, FAIL, EMPTY_WIDTH -> {
                    // no-op
                }
            }

            if (!instruction.last()) {
                stack = ensureCapacity(stack, stackPointer + 1);
                stack[stackPointer++] = instructionId + 1;
            }
        }
        return false;
    }

    private int[] normalizeStartInstructions(int startInstructionId, int initialFlags)
    {
        // Build the initial work queue by following epsilon edges that are valid under the
        // initial begin-text and begin-line flags. This consumes anchors such as \A at the
        // starting position, matching AnalyzeSearchHelper/AddToQueue.
        if (startInstructionId <= 0 || startInstructionId >= size()) {
            return new int[] {startInstructionId};
        }

        boolean[] visited = new boolean[size()];
        int[] stack = new int[32];
        int stackPointer = 0;
        stack[stackPointer++] = startInstructionId;

        int[] normalizedInstructionIds = new int[32];
        int normalizedInstructionCount = 0;

        while (stackPointer != 0) {
            int instructionId = stack[--stackPointer];
            if (instructionId <= 0 || instructionId >= size()) {
                continue;
            }
            if (visited[instructionId]) {
                continue;
            }
            visited[instructionId] = true;

            Inst instruction = inst(instructionId);

            switch (instruction.opcode()) {
                case ALT, ALT_MATCH -> {
                    stack = ensureCapacity(stack, stackPointer + 2);
                    stack[stackPointer++] = instruction.out();
                    stack[stackPointer++] = instruction.out1();
                    if (!instruction.last()) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case NOP, CAPTURE -> {
                    stack = ensureCapacity(stack, stackPointer + 1);
                    stack[stackPointer++] = instruction.out();
                    if (!instruction.last()) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case EMPTY_WIDTH -> {
                    // Only "consume" EMPTY_WIDTH if it is satisfied by the initial flags.
                    if ((instruction.empty() & ~initialFlags) == 0) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instruction.out();
                    }
                    else {
                        normalizedInstructionIds = ensureCapacity(normalizedInstructionIds, normalizedInstructionCount + 1);
                        normalizedInstructionIds[normalizedInstructionCount++] = instructionId;
                    }
                    if (!instruction.last()) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case BYTE_RANGE, MATCH, FAIL -> {
                    normalizedInstructionIds = ensureCapacity(normalizedInstructionIds, normalizedInstructionCount + 1);
                    normalizedInstructionIds[normalizedInstructionCount++] = instructionId;
                }
            }
        }

        return uniqueSorted(normalizedInstructionIds, normalizedInstructionCount);
    }

    private static final class StateVisitMap
    {
        private final HashMap<DfaState, Integer> visitCounts = new HashMap<>();

        int get(DfaState key)
        {
            Integer count = visitCounts.get(key);
            return count == null ? 0 : count;
        }

        void increment(DfaState key)
        {
            visitCounts.put(key, get(key) + 1);
        }

        void clear()
        {
            visitCounts.clear();
        }
    }

    /// Drops the end-of-input check from each Java `UNIX_LINES` multiline caret after which every
    /// path to a match consumes a byte. That caret is RE2's begin-line that also fails at the end
    /// of the input, and a caret with a byte still to match is never there, so it becomes RE2's
    /// begin-line and no longer keeps the program off the DFA and OnePass. A reversed program
    /// holds the caret as an end-line and matches the bytes after it before reaching it, so there
    /// the check is dropped when no path from the start reaches the caret without consuming a byte.
    void dropImpliedJavaCaretExclusions()
    {
        int javaLine = reversed ? EmptyOp.EMPTY_JAVA_END_LINE : EmptyOp.EMPTY_JAVA_BEGIN_LINE;
        int caret = (reversed ? EmptyOp.EMPTY_END_LINE : EmptyOp.EMPTY_BEGIN_LINE) | javaLine;
        boolean hasCaret = false;
        for (int id = 0; id < size(); id++) {
            Inst instruction = inst(id);
            if (instruction.opcode() == InstOp.EMPTY_WIDTH && instruction.empty() == caret) {
                hasCaret = true;
                break;
            }
        }
        if (!hasCaret) {
            return;
        }

        // Only the edges of instructions the start reaches are followed, because a fragment the
        // compiler drops for a never-matching operand, such as `[a&&b]`, still holds patch-list
        // entries in place of instruction ids.
        SparseSet reachable = new SparseSet(size() + 1);
        addToQueue(reachable, start);
        for (int index = 0; index < reachable.size(); index++) {
            Inst instruction = inst(reachable.denseAt(index));
            addToQueue(reachable, instruction.out());
            if (instruction.opcode() == InstOp.ALT || instruction.opcode() == InstOp.ALT_MATCH) {
                addToQueue(reachable, instruction.out1());
            }
        }

        // Forward, the instructions that reach a match without consuming a byte; reversed, the
        // instructions the start reaches without consuming one.
        SparseSet emptyReach = new SparseSet(size() + 1);
        if (reversed) {
            addToQueue(emptyReach, start);
            for (int index = 0; index < emptyReach.size(); index++) {
                Inst instruction = inst(emptyReach.denseAt(index));
                switch (instruction.opcode()) {
                    case ALT, ALT_MATCH -> {
                        addToQueue(emptyReach, instruction.out());
                        addToQueue(emptyReach, instruction.out1());
                    }
                    case CAPTURE, EMPTY_WIDTH, NOP -> addToQueue(emptyReach, instruction.out());
                    case BYTE_RANGE, MATCH, FAIL -> {}
                }
            }
        }
        else {
            PredecessorLists predecessors = new PredecessorLists(size());
            for (int index = 0; index < reachable.size(); index++) {
                int id = reachable.denseAt(index);
                Inst instruction = inst(id);
                switch (instruction.opcode()) {
                    case ALT, ALT_MATCH -> {
                        predecessors.add(instruction.out(), id);
                        predecessors.add(instruction.out1(), id);
                    }
                    case CAPTURE, EMPTY_WIDTH, NOP -> predecessors.add(instruction.out(), id);
                    case MATCH -> emptyReach.insert(id);
                    case BYTE_RANGE, FAIL -> {}
                }
            }
            for (int index = 0; index < emptyReach.size(); index++) {
                for (int edge = predecessors.firstEdge(emptyReach.denseAt(index)); edge >= 0; edge = predecessors.nextEdge(edge)) {
                    emptyReach.insert(predecessors.predecessorInstructionId(edge));
                }
            }
        }

        for (int index = 0; index < reachable.size(); index++) {
            int id = reachable.denseAt(index);
            Inst instruction = inst(id);
            if (instruction.opcode() == InstOp.EMPTY_WIDTH && instruction.empty() == caret && !emptyReach.contains(id)) {
                instruction.setEmpty(caret & ~javaLine);
            }
        }
    }

    public void optimize()
    {
        SparseSet queue = new SparseSet(size() + 1);

        // Eliminate nops. Most are taken out during compilation
        // but a few are hard to avoid.
        // Many instructions can share one chain, so targets are remembered.
        SparseIntArray nopTargets = null;
        queue.clear();
        addToQueue(queue, start);
        for (int queueIndex = 0; queueIndex < queue.size(); queueIndex++) {
            int id = queue.denseAt(queueIndex);

            Inst instruction = inst(id);
            if (nopTargets == null && isNopChain(instruction.out())) {
                nopTargets = new SparseIntArray(size());
            }
            instruction.setOut(skipNops(instruction.out(), nopTargets));
            addToQueue(queue, instruction.out());

            if (instruction.opcode() == InstOp.ALT) {
                if (nopTargets == null && isNopChain(instruction.out1())) {
                    nopTargets = new SparseIntArray(size());
                }
                instruction.setOut1(skipNops(instruction.out1(), nopTargets));
                addToQueue(queue, instruction.out1());
            }
        }

        // Insert ALT_MATCH instructions.
        SparseIntArray matchAnswers = null;
        queue.clear();
        addToQueue(queue, start);
        for (int queueIndex = 0; queueIndex < queue.size(); queueIndex++) {
            int id = queue.denseAt(queueIndex);
            Inst instruction = inst(id);
            addToQueue(queue, instruction.out());
            if (instruction.opcode() == InstOp.ALT) {
                addToQueue(queue, instruction.out1());
            }

            if (instruction.opcode() == InstOp.ALT) {
                // Many loops can share one long chain of captures, so answers are remembered,
                // and the loop test comes first so loop-free programs never walk.
                Inst firstBranch = inst(instruction.out());
                Inst secondBranch = inst(instruction.out1());
                int matchBranchId;
                if (firstBranch.opcode() == InstOp.BYTE_RANGE && firstBranch.out() == id &&
                        firstBranch.lo() == 0x00 && firstBranch.hi() == 0xFF) {
                    matchBranchId = instruction.out1();
                }
                else if (secondBranch.opcode() == InstOp.BYTE_RANGE && secondBranch.out() == id &&
                        secondBranch.lo() == 0x00 && secondBranch.hi() == 0xFF) {
                    matchBranchId = instruction.out();
                }
                else {
                    continue;
                }
                InstOp matchBranchOpcode = inst(matchBranchId).opcode();
                if (matchAnswers == null && (matchBranchOpcode == InstOp.CAPTURE || matchBranchOpcode == InstOp.NOP)) {
                    matchAnswers = new SparseIntArray(size());
                }
                if (isMatch(matchBranchId, matchAnswers)) {
                    instruction.setOpcode(InstOp.ALT_MATCH);
                }
            }
        }
    }

    public void flatten()
    {
        flatten(false, MAX_FLATTENED_INSTRUCTIONS, 0, false);
    }

    /// Flattens the program. Throws [RegexpCompileMemoryLimitException], reporting `maxMemory`,
    /// when the flattened program would hold more than `maxInstructionCount` instructions.
    void flatten(boolean skipDominatorPass, int maxInstructionCount, long maxMemory)
    {
        flatten(skipDominatorPass, maxInstructionCount, maxMemory, false);
    }

    FlattenStructure flattenForTesting()
    {
        return flattenForTesting(false);
    }

    FlattenStructure flattenForTesting(boolean skipDominatorPass)
    {
        if (didFlatten) {
            throw new IllegalStateException("program is already flattened");
        }
        return flatten(skipDominatorPass, MAX_FLATTENED_INSTRUCTIONS, 0, true);
    }

    private FlattenStructure flatten(boolean skipDominatorPass, int maxInstructionCount, long maxMemory, boolean recordStructure)
    {
        if (didFlatten) {
            return null;
        }
        didFlatten = true;

        // Scratch structures reused by helper routines to avoid heap churn.
        SparseSet reachable = new SparseSet(size());
        IntStack stack = new IntStack(size());
        Bitmap256 hintSplits = new Bitmap256();
        int[] hintColors = new int[256];
        // First pass: mark successor roots and predecessors.
        SparseIntArray rootIdsByInstruction = new SparseIntArray(size());
        PredecessorLists predecessorLists = new PredecessorLists(size());
        markSuccessors(rootIdsByInstruction, predecessorLists, reachable, stack);
        int successorRootCount = rootIdsByInstruction.size();
        // Second pass: mark dominator roots.
        long dominatorEdgesScanned = 0;
        if (!skipDominatorPass) {
            int[] sortedRootInstructionIds = new int[rootIdsByInstruction.size()];
            for (int rootIndex = 0; rootIndex < rootIdsByInstruction.size(); rootIndex++) {
                sortedRootInstructionIds[rootIndex] = rootIdsByInstruction.denseIndexAt(rootIndex);
            }
            Arrays.sort(sortedRootInstructionIds);
            for (int rootIndex = sortedRootInstructionIds.length - 1; rootIndex > 0; rootIndex--) {
                int id = sortedRootInstructionIds[rootIndex];
                if (id != startUnanchored() && id != start()) {
                    dominatorEdgesScanned += markDominator(
                            id, rootIdsByInstruction, predecessorLists, reachable, stack);
                }
            }
        }
        int finalRootCount = rootIdsByInstruction.size();
        // Third pass: emit lists and map each root to its flattened instruction index.
        int[] flattenedIndexesByRoot = new int[rootIdsByInstruction.size()];
        InstList flattenedInstructions = new InstList(size(), maxInstructionCount, maxMemory);
        for (int rootIndex = 0; rootIndex < rootIdsByInstruction.size(); rootIndex++) {
            int rootInstructionId = rootIdsByInstruction.denseIndexAt(rootIndex);
            int rootId = rootIdsByInstruction.denseValueAt(rootIndex);
            flattenedIndexesByRoot[rootId] = flattenedInstructions.size();
            emitList(rootInstructionId, rootIdsByInstruction, flattenedInstructions, reachable, stack);
            flattenedInstructions.getLast().setLast();
            computeHints(
                    flattenedInstructions,
                    flattenedIndexesByRoot[rootId],
                    flattenedInstructions.size(),
                    hintSplits,
                    hintColors);
        }
        // Reset instruction counts and the capture slot count before fourth pass.
        Arrays.fill(instCount, 0);
        captureSlotCount = 2;

        // Prepare the output list ids used by the BitState engine. Upstream gives list heads only
        // to programs of at most 512 instructions, which sends a large program's captures to the
        // NFA even for a short match. The visited bitmap grows with the list count, not the
        // instruction count, so the bitmap bound below limits the text, as the Rust crate bounds
        // its backtracker, and compilation lowers that limit for the job stack with
        // limitBitStateMemory. BitState indexes its visited set by the list each step enters,
        // which is the list of the instruction's output. Before the fourth pass remaps it, an
        // output is the root id of that list, and a list's id is its root id.
        listCount = rootIdsByInstruction.size();
        short[] outputLists = null;
        if (listCount <= Short.MAX_VALUE) {
            outputLists = new short[flattenedInstructions.size()];
        }

        // Fourth pass: record output list ids, remap outputs to flattened indexes, count
        // instructions by opcode, and record the capture slot count.
        for (int id = 0; id < flattenedInstructions.size(); id++) {
            Inst instruction = flattenedInstructions.get(id);
            if (outputLists != null) {
                outputLists[id] = switch (instruction.opcode()) {
                    case BYTE_RANGE, CAPTURE, EMPTY_WIDTH, NOP -> (short) instruction.out();
                    case ALT, ALT_MATCH, MATCH, FAIL -> -1;
                };
            }
            if (instruction.opcode() != InstOp.ALT_MATCH) {
                instruction.setOut(flattenedIndexesByRoot[instruction.out()]);
            }
            instCount[instruction.opcode().ordinal()]++;
            if (instruction.opcode() == InstOp.CAPTURE) {
                captureSlotCount = Math.max(captureSlotCount, (instruction.cap() | 1) + 1);
            }
        }

        // Remap start_unanchored and start.
        int startRootId;
        if (startUnanchored() == 0) {
            // Leave both as zero.
            startRootId = 0;
        }
        else if (startUnanchored() == start()) {
            startRootId = 1;
            setStartUnanchored(flattenedIndexesByRoot[1]);
            setStart(flattenedIndexesByRoot[1]);
        }
        else {
            startRootId = 2;
            setStartUnanchored(flattenedIndexesByRoot[1]);
            setStart(flattenedIndexesByRoot[2]);
        }

        // Replace old instructions with flattened ones.
        insts = flattenedInstructions.retainedInstructions();
        instructionCount = flattenedInstructions.size();

        this.outputLists = null;
        startList = -1;
        bitStateTextMaxSize = 0;
        bitStateJobsPerPosition = 0;
        if (outputLists != null) {
            this.outputLists = outputLists;
            startList = startRootId;

            // BitState allocates a bitmap of size listCount * (text.size()+1). The Java server
            // implementation accepts a larger fixed bound than upstream when that avoids three
            // complete capture passes over a long input.
            long maximumBitmapBits = BIT_STATE_BITMAP_MAXIMUM_BYTES * Byte.SIZE;
            bitStateTextMaxSize = (int) (maximumBitmapBits / Math.max(1, listCount) - 1);
            bitStateJobsPerPosition = BitState.jobsPerPosition(this);
        }

        if (!recordStructure) {
            return null;
        }
        return new FlattenStructure(
                successorRootCount,
                finalRootCount,
                dominatorEdgesScanned);
    }

    record FlattenStructure(
            int successorRootCount,
            int finalRootCount,
            long dominatorEdgesScanned) {}

    // The base action encoding remains compatible with RE2's onepass.cc layout.
    private static final int ONEPASS_INDEX_SHIFT = 16; // bits below next-state index
    private static final int ONEPASS_EMPTY_SHIFT = 6;  // number of empty flags (kEmptyAllFlags is (1<<6)-1)
    private static final int ONEPASS_REAL_CAP_SHIFT = ONEPASS_EMPTY_SHIFT + 1;
    private static final int ONEPASS_REAL_MAX_CAP = ((ONEPASS_INDEX_SHIFT - ONEPASS_REAL_CAP_SHIFT) / 2) * 2;
    private static final int ONEPASS_CAP_SHIFT = ONEPASS_REAL_CAP_SHIFT - 2; // skip cap[0], cap[1]
    private static final int ONEPASS_MAX_CAP = ONEPASS_REAL_MAX_CAP + 2;

    private static final int ONEPASS_MATCH_WINS = 1 << ONEPASS_EMPTY_SHIFT;
    private static final int ONEPASS_IMPOSSIBLE = EmptyOp.EMPTY_WORD_BOUNDARY | EmptyOp.EMPTY_NO_WORD_BOUNDARY;
    // The text-dependent assertions OnePass evaluates: Rust's, and Java's and Trino's word
    // boundaries. They do not fit in a condition's six empty-width bits. A condition that needs one
    // carries ONEPASS_EXTENDED_ASSERTIONS instead, which sends the search to OnePass.satisfyAction
    // or OnePass.satisfyMatch, and those evaluate the full EmptyOp mask from the parallel assertion
    // arrays. In a program with one of them, every match condition also carries its node
    // index above ONEPASS_INDEX_SHIFT, where an action keeps its next node. The bit is never part
    // of ONEPASS_IMPOSSIBLE, so a marked condition never reads as ONEPASS_IMPOSSIBLE.
    private static final int ONEPASS_TEXT_DEPENDENT_ASSERTIONS = EmptyOp.RUST_ASSERTIONS | EmptyOp.JAVA_TRINO_WORD_BOUNDARIES;
    private static final int ONEPASS_EXTENDED_ASSERTIONS = EmptyOp.EMPTY_BEGIN_TEXT;
    // The bytes each action retains: one step, the action packed with its next-state offset.
    private static final int ONEPASS_ACTION_BYTES = Long.BYTES;
    // Action entries allocated before one-pass analysis starts growing node storage.
    private static final int ONEPASS_INITIAL_ACTIONS = 8192;

    // The initial tables hold ONEPASS_INITIAL_ACTIONS actions, or 16 nodes, whichever holds more nodes.
    static int onePassInitialNodeCapacity(int byteClassCount)
    {
        return Math.max(16, ONEPASS_INITIAL_ACTIONS / byteClassCount);
    }

    private boolean computeOnePass()
    {
        // OnePass evaluates some text-dependent assertions from parallel masks; the others keep it
        // off.
        if ((textDependentAssertions & ~ONEPASS_TEXT_DEPENDENT_ASSERTIONS) != 0) {
            return false;
        }
        boolean hasExtendedAssertions = textDependentAssertions != 0;

        // This computation assumes the program is in flattened list form.
        flatten();
        if (!didFlatten) {
            return false;
        }
        if (start() == 0) {
            return false;
        }
        if (bytemapRange() <= 0) {
            return false;
        }

        int byteRangeCount = 0;
        int captureCount = 0;
        int maximumCaptureSlot = -1;
        int emptyWidthCount = 0;
        int noOpCount = 0;
        for (int id = 0; id < size(); id++) {
            switch (inst(id).opcode()) {
                case BYTE_RANGE -> byteRangeCount++;
                case CAPTURE -> {
                    captureCount++;
                    maximumCaptureSlot = Math.max(maximumCaptureSlot, inst(id).cap());
                }
                case EMPTY_WIDTH -> emptyWidthCount++;
                case NOP -> noOpCount++;
                default -> {}
            }
        }

        // Upstream's limit decides which programs are one-pass: tables with four-byte actions take
        // at most one quarter of the DFA budget, and the node index must fit the 16-bit field of
        // the OnePass action encoding. The tables are charged at their real size, eight bytes per
        // action, at most twice upstream's charge. The extra comes out of the DFA's share, so the
        // same programs are one-pass and the charge never exceeds half the budget.
        int maximumNodeCount = 2 + byteRangeCount;
        boolean hasExtendedCaptures = maximumCaptureSlot >= ONEPASS_MAX_CAP && maximumCaptureSlot < Long.SIZE;
        long upstreamStateSize = onePassStateSize(Integer.BYTES, hasExtendedCaptures, hasExtendedAssertions);
        if (maximumNodeCount >= 65000 || dfaMemory / 4 / upstreamStateSize < maximumNodeCount) {
            return false;
        }
        long stateSize = onePassStateSize(ONEPASS_ACTION_BYTES, hasExtendedCaptures, hasExtendedAssertions);

        int maximumStackSize = captureCount + emptyWidthCount + noOpCount + 1; // +1 for start instruction
        int[] stackInstructionIds = new int[Math.max(8, maximumStackSize)];
        int[] stackConditions = new int[Math.max(8, maximumStackSize)];
        long[] stackCaptureMasks = hasExtendedCaptures ? new long[Math.max(8, maximumStackSize)] : null;
        int[] stackAssertions = hasExtendedAssertions ? new int[Math.max(8, maximumStackSize)] : null;

        // Every node but the start is the target of a byte range, so the distinct targets bound
        // the node count far more tightly than the node limit does: a large Unicode class has
        // several byte ranges for each shared suffix. Count the distinct targets by marking
        // nodeById, which is then cleared for the walk. The unanchored start is not counted: only
        // the loop of its unanchored prefix returns to it, and the walk from the anchored start
        // never reaches it.
        int[] nodeById = new int[size()];
        nodeById[start()] = 1;
        nodeById[startUnanchored()] = 1;
        int nodeBound = 1;
        for (int id = 0; id < size(); id++) {
            Inst instruction = inst(id);
            if (instruction.opcode() == InstOp.BYTE_RANGE && nodeById[instruction.out()] == 0) {
                nodeById[instruction.out()] = 1;
                nodeBound++;
            }
        }
        Arrays.fill(nodeById, -1);

        // Like upstream, start node storage small when the bound is large, as programs that stop
        // being one-pass usually do so after a few nodes. Storage doubles on overflow, capped at
        // the bound rather than the node limit, so no step allocates more than doubling to the
        // limit would, and a one-pass program ends exactly at the bound with no trimming copy;
        // only the step table is then packed from the finished actions, once. Small tables are
        // allocated at their bound, because growing them costs more than zeroing them.
        int byteClassCount = bytemapRange();
        int nodeCapacity = Math.min(nodeBound, onePassInitialNodeCapacity(byteClassCount));
        int[] nodeMatchCondition = new int[nodeCapacity];
        int[] nodeAction = new int[nodeCapacity * byteClassCount];
        long[] nodeMatchCapture = hasExtendedCaptures ? new long[nodeCapacity] : null;
        long[] nodeActionCapture = hasExtendedCaptures ? new long[nodeCapacity * byteClassCount] : null;
        int[] nodeMatchAssertions = hasExtendedAssertions ? new int[nodeCapacity] : null;
        int[] nodeActionAssertions = hasExtendedAssertions ? new int[nodeCapacity * byteClassCount] : null;

        SparseSet toVisit = new SparseSet(size());
        SparseSet workQueue = new SparseSet(size());

        addOnePassQueue(toVisit, start());
        nodeById[start()] = 0;

        int allocatedNodeCount = 1;
        for (int visitIndex = 0; visitIndex < toVisit.size(); visitIndex++) {
            int startInstructionId = toVisit.denseAt(visitIndex);
            int nodeIndex = nodeById[startInstructionId];
            if (nodeIndex < 0) {
                continue;
            }

            int actionBase = nodeIndex * byteClassCount;
            Arrays.fill(nodeAction, actionBase, actionBase + byteClassCount, ONEPASS_IMPOSSIBLE);
            nodeMatchCondition[nodeIndex] = ONEPASS_IMPOSSIBLE;
            if (hasExtendedAssertions) {
                Arrays.fill(nodeActionAssertions, actionBase, actionBase + byteClassCount, ONEPASS_IMPOSSIBLE);
                nodeMatchAssertions[nodeIndex] = ONEPASS_IMPOSSIBLE;
            }

            workQueue.clear();
            boolean foundMatch = false;
            int stackSize = 0;
            stackInstructionIds[stackSize] = startInstructionId;
            stackConditions[stackSize] = 0;
            if (hasExtendedCaptures) {
                stackCaptureMasks[stackSize] = 0;
            }
            if (hasExtendedAssertions) {
                stackAssertions[stackSize] = 0;
            }
            stackSize++;

            while (stackSize > 0) {
                stackSize--;
                int id = stackInstructionIds[stackSize];
                int condition = stackConditions[stackSize];
                long captureMask = hasExtendedCaptures ? stackCaptureMasks[stackSize] : 0;
                int assertions = hasExtendedAssertions ? stackAssertions[stackSize] : 0;

                while (id != 0) {
                    Inst instruction = inst(id);
                    switch (instruction.opcode()) {
                        case ALT_MATCH -> {
                            // Upstream ignores ALT_MATCH's subtle semantics in IsOnePass; we do the same.
                            if (instruction.last()) {
                                return false;
                            }
                            if (!addOnePassQueue(workQueue, id + 1)) {
                                return false;
                            }
                            id = id + 1;
                        }

                        case BYTE_RANGE -> {
                            int nextInstructionId = instruction.out();
                            int nextIndex = nodeById[nextInstructionId];
                            if (nextIndex == -1) {
                                if (allocatedNodeCount >= nodeBound) {
                                    return false;
                                }
                                if (allocatedNodeCount == nodeCapacity) {
                                    nodeCapacity = Math.min(nodeBound, nodeCapacity * 2);
                                    nodeMatchCondition = Arrays.copyOf(nodeMatchCondition, nodeCapacity);
                                    nodeAction = Arrays.copyOf(nodeAction, nodeCapacity * byteClassCount);
                                    if (hasExtendedCaptures) {
                                        nodeMatchCapture = Arrays.copyOf(nodeMatchCapture, nodeCapacity);
                                        nodeActionCapture = Arrays.copyOf(nodeActionCapture, nodeCapacity * byteClassCount);
                                    }
                                    if (hasExtendedAssertions) {
                                        nodeMatchAssertions = Arrays.copyOf(nodeMatchAssertions, nodeCapacity);
                                        nodeActionAssertions = Arrays.copyOf(nodeActionAssertions, nodeCapacity * byteClassCount);
                                    }
                                }
                                nextIndex = allocatedNodeCount++;
                                addOnePassQueue(toVisit, nextInstructionId);
                                nodeById[nextInstructionId] = nextIndex;
                            }

                            // Fill each action for the byte classes touched by this instruction.
                            int lo = instruction.lo();
                            int hi = instruction.hi();
                            for (int c = lo; c <= hi; c++) {
                                int byteClass = bytemap(c);
                                while (c < 255 && bytemap(c + 1) == byteClass) {
                                    c++;
                                }
                                int action = nodeAction[actionBase + byteClass];
                                int newAction = (nextIndex << ONEPASS_INDEX_SHIFT) | condition;
                                if (foundMatch) {
                                    newAction |= ONEPASS_MATCH_WINS;
                                }
                                if (action == ONEPASS_IMPOSSIBLE) {
                                    nodeAction[actionBase + byteClass] = newAction;
                                    if (hasExtendedCaptures) {
                                        nodeActionCapture[actionBase + byteClass] = captureMask;
                                    }
                                    if (hasExtendedAssertions) {
                                        nodeActionAssertions[actionBase + byteClass] = assertions;
                                    }
                                }
                                else if (action != newAction ||
                                        (hasExtendedCaptures && nodeActionCapture[actionBase + byteClass] != captureMask) ||
                                        (hasExtendedAssertions && nodeActionAssertions[actionBase + byteClass] != assertions)) {
                                    return false;
                                }
                            }

                            if (instruction.foldCase()) {
                                int uppercaseLow = Math.max(lo, 'a') + ('A' - 'a');
                                int uppercaseHigh = Math.min(hi, 'z') + ('A' - 'a');
                                for (int c = uppercaseLow; c <= uppercaseHigh; c++) {
                                    int byteClass = bytemap(c);
                                    while (c < 255 && bytemap(c + 1) == byteClass) {
                                        c++;
                                    }
                                    int action = nodeAction[actionBase + byteClass];
                                    int newAction = (nextIndex << ONEPASS_INDEX_SHIFT) | condition;
                                    if (foundMatch) {
                                        newAction |= ONEPASS_MATCH_WINS;
                                    }
                                    if (action == ONEPASS_IMPOSSIBLE) {
                                        nodeAction[actionBase + byteClass] = newAction;
                                        if (hasExtendedCaptures) {
                                            nodeActionCapture[actionBase + byteClass] = captureMask;
                                        }
                                        if (hasExtendedAssertions) {
                                            nodeActionAssertions[actionBase + byteClass] = assertions;
                                        }
                                    }
                                    else if (action != newAction ||
                                            (hasExtendedCaptures && nodeActionCapture[actionBase + byteClass] != captureMask) ||
                                            (hasExtendedAssertions && nodeActionAssertions[actionBase + byteClass] != assertions)) {
                                        return false;
                                    }
                                }
                            }

                            if (instruction.last()) {
                                id = 0;
                                break;
                            }
                            if (!addOnePassQueue(workQueue, id + 1)) {
                                return false;
                            }
                            id = id + 1;
                        }

                        case CAPTURE, EMPTY_WIDTH, NOP -> {
                            if (!instruction.last()) {
                                if (!addOnePassQueue(workQueue, id + 1)) {
                                    return false;
                                }
                                stackInstructionIds[stackSize] = id + 1;
                                stackConditions[stackSize] = condition;
                                if (hasExtendedCaptures) {
                                    stackCaptureMasks[stackSize] = captureMask;
                                }
                                if (hasExtendedAssertions) {
                                    stackAssertions[stackSize] = assertions;
                                }
                                stackSize++;
                            }

                            if (instruction.opcode() == InstOp.CAPTURE && instruction.cap() < ONEPASS_MAX_CAP) {
                                condition |= 1 << (ONEPASS_CAP_SHIFT + instruction.cap());
                            }
                            if (hasExtendedCaptures && instruction.opcode() == InstOp.CAPTURE) {
                                captureMask |= 1L << instruction.cap();
                            }
                            if (instruction.opcode() == InstOp.EMPTY_WIDTH) {
                                int empty = instruction.empty();
                                if ((empty & ONEPASS_TEXT_DEPENDENT_ASSERTIONS) == 0) {
                                    condition |= empty;
                                }
                                else {
                                    condition |= (empty & ~ONEPASS_TEXT_DEPENDENT_ASSERTIONS) | ONEPASS_EXTENDED_ASSERTIONS;
                                }
                                assertions |= empty;
                            }

                            if (!addOnePassQueue(workQueue, instruction.out())) {
                                return false;
                            }
                            id = instruction.out();
                        }

                        case MATCH -> {
                            if (foundMatch) {
                                return false;
                            }
                            foundMatch = true;
                            nodeMatchCondition[nodeIndex] = condition;
                            if (hasExtendedCaptures) {
                                nodeMatchCapture[nodeIndex] = captureMask;
                            }
                            if (hasExtendedAssertions) {
                                nodeMatchCondition[nodeIndex] |= nodeIndex << ONEPASS_INDEX_SHIFT;
                                nodeMatchAssertions[nodeIndex] = assertions;
                            }

                            if (instruction.last()) {
                                id = 0;
                                break;
                            }
                            if (!addOnePassQueue(workQueue, id + 1)) {
                                return false;
                            }
                            id = id + 1;
                        }

                        case FAIL -> id = 0;

                        case ALT -> {
                            // ALT instructions do not appear in flattened list form.
                            return false;
                        }
                    }
                }
            }
        }

        // The walk normally reaches every counted target, so a one-pass program ends with its
        // tables exactly sized. The trim is a safety net for a counted target the walk never
        // reached.
        if (allocatedNodeCount < nodeCapacity) {
            nodeMatchCondition = Arrays.copyOf(nodeMatchCondition, allocatedNodeCount);
            nodeAction = Arrays.copyOf(nodeAction, allocatedNodeCount * byteClassCount);
            if (hasExtendedCaptures) {
                nodeMatchCapture = Arrays.copyOf(nodeMatchCapture, allocatedNodeCount);
                nodeActionCapture = Arrays.copyOf(nodeActionCapture, allocatedNodeCount * byteClassCount);
            }
            if (hasExtendedAssertions) {
                nodeMatchAssertions = Arrays.copyOf(nodeMatchAssertions, allocatedNodeCount);
                nodeActionAssertions = Arrays.copyOf(nodeActionAssertions, allocatedNodeCount * byteClassCount);
            }
        }
        onePassStateCount = allocatedNodeCount;
        onePassMatchCond = nodeMatchCondition;
        // Each step packs the action's next-state row offset above the action; see onePassStep.
        long[] step = new long[nodeAction.length];
        for (int index = 0; index < nodeAction.length; index++) {
            int nextOffset = (nodeAction[index] >>> ONEPASS_INDEX_SHIFT) * byteClassCount;
            step[index] = ((long) nextOffset << 32) | (nodeAction[index] & 0xFFFF_FFFFL);
        }
        onePassStep = step;
        onePassMatchCapture = nodeMatchCapture;
        onePassActionCapture = nodeActionCapture;
        onePassMatchAssertions = nodeMatchAssertions;
        onePassActionAssertions = nodeActionAssertions;
        dfaMemory -= allocatedNodeCount * stateSize;
        return true;
    }

    private static boolean addOnePassQueue(SparseSet queue, int id)
    {
        if (id == 0) {
            return true;
        }
        if (queue.contains(id)) {
            return false;
        }
        queue.insertNew(id);
        return true;
    }

    private static boolean isWordChar(int c)
    {
        int b = c & 0xFF;
        return ('A' <= b && b <= 'Z') ||
                ('a' <= b && b <= 'z') ||
                ('0' <= b && b <= '9') ||
                b == '_';
    }

    private static void markRanges(ByteMapBuilder builder, int[] ranges)
    {
        for (int i = 0; i < ranges.length; i += 2) {
            builder.mark(ranges[i], ranges[i + 1]);
        }
    }

    // Computes the empty-width assertions at a position in the logical Slice.
    public static int emptyFlags(Slice text, int p)
    {
        return EmptyOp.contextFlags(text, p);
    }

    private boolean isNopChain(int instructionId)
    {
        return instructionId != 0 && inst(instructionId).opcode() == InstOp.NOP &&
                inst(instructionId).out() != 0 && inst(inst(instructionId).out()).opcode() == InstOp.NOP;
    }

    private int skipNops(int instructionId, SparseIntArray nopTargets)
    {
        int endId = instructionId;
        int targetId;
        while (true) {
            if (endId == 0 || inst(endId).opcode() != InstOp.NOP) {
                targetId = endId;
                break;
            }
            if (nopTargets != null && nopTargets.hasIndex(endId)) {
                targetId = nopTargets.getExisting(endId);
                break;
            }
            endId = inst(endId).out();
        }
        if (nopTargets != null) {
            for (int id = instructionId; id != endId; id = inst(id).out()) {
                nopTargets.setNew(id, targetId);
            }
        }
        return targetId;
    }

    private boolean isMatch(int instructionId, SparseIntArray matchAnswers)
    {
        int endId = instructionId;
        boolean match;
        while (true) {
            Inst instruction = inst(endId);
            if (instruction.opcode() != InstOp.CAPTURE && instruction.opcode() != InstOp.NOP) {
                match = instruction.opcode() == InstOp.MATCH;
                break;
            }
            if (matchAnswers.hasIndex(endId)) {
                match = matchAnswers.getExisting(endId) != 0;
                break;
            }
            endId = instruction.out();
        }
        for (int id = instructionId; id != endId; id = inst(id).out()) {
            matchAnswers.setNew(id, match ? 1 : 0);
        }
        return match;
    }

    private void markSuccessors(
            SparseIntArray rootIdsByInstruction,
            PredecessorLists predecessorLists,
            SparseSet reachable,
            IntStack stack)
    {
        // Mark the FAIL instruction.
        rootIdsByInstruction.setNew(0, rootIdsByInstruction.size());

        // Mark the start_unanchored and start instructions.
        if (!rootIdsByInstruction.hasIndex(startUnanchored())) {
            rootIdsByInstruction.setNew(startUnanchored(), rootIdsByInstruction.size());
        }
        if (!rootIdsByInstruction.hasIndex(start())) {
            rootIdsByInstruction.setNew(start(), rootIdsByInstruction.size());
        }

        reachable.clear();
        stack.clear();
        stack.push(startUnanchored());
        while (!stack.isEmpty()) {
            int id = stack.pop();
            while (true) {
                if (reachable.contains(id)) {
                    break;
                }
                reachable.insertNew(id);

                Inst instruction = inst(id);
                switch (instruction.opcode()) {
                    case ALT_MATCH, ALT -> {
                        // Mark this instruction as a predecessor of each out.
                        predecessorLists.add(instruction.out(), id);
                        predecessorLists.add(instruction.out1(), id);
                        stack.push(instruction.out1());
                        id = instruction.out();
                    }
                    case BYTE_RANGE, CAPTURE, EMPTY_WIDTH -> {
                        // Mark the out of this instruction as a "root".
                        if (!rootIdsByInstruction.hasIndex(instruction.out())) {
                            rootIdsByInstruction.setNew(instruction.out(), rootIdsByInstruction.size());
                        }
                        id = instruction.out();
                    }
                    case NOP -> id = instruction.out();
                    case MATCH, FAIL -> {
                        id = 0;
                    }
                }
                if (id == 0) {
                    break;
                }
            }
        }
    }

    /// Returns the number of predecessor edges scanned, which [#flattenForTesting()] reports.
    private int markDominator(
            int rootInstructionId,
            SparseIntArray rootIdsByInstruction,
            PredecessorLists predecessorLists,
            SparseSet reachable,
            IntStack stack)
    {
        reachable.clear();
        stack.clear();
        stack.push(rootInstructionId);
        while (!stack.isEmpty()) {
            int id = stack.pop();
            while (true) {
                if (reachable.contains(id)) {
                    break;
                }
                reachable.insertNew(id);

                if (id != rootInstructionId && rootIdsByInstruction.hasIndex(id)) {
                    // We reached another "tree" via epsilon transition.
                    break;
                }

                Inst instruction = inst(id);
                switch (instruction.opcode()) {
                    case ALT_MATCH, ALT -> {
                        stack.push(instruction.out1());
                        id = instruction.out();
                    }
                    case BYTE_RANGE, CAPTURE, EMPTY_WIDTH, MATCH, FAIL -> {
                        id = 0;
                    }
                    case NOP -> id = instruction.out();
                }
                if (id == 0) {
                    break;
                }
            }
        }

        int predecessorEdges = 0;
        for (int reachableIndex = 0; reachableIndex < reachable.size(); reachableIndex++) {
            int id = reachable.denseAt(reachableIndex);
            if (rootIdsByInstruction.hasIndex(id)) {
                // id is already a "root", so its predecessors cannot change anything.
                // Rescanning a shared root with many predecessors from every tree that reaches it
                // is quadratic.
                continue;
            }
            for (int edge = predecessorLists.firstEdge(id); edge >= 0; edge = predecessorLists.nextEdge(edge)) {
                predecessorEdges++;
                int predecessorInstructionId = predecessorLists.predecessorInstructionId(edge);
                if (!reachable.contains(predecessorInstructionId)) {
                    // id has a predecessor that cannot be reached from root!
                    // Therefore, id must be a "root" too - mark it as such.
                    rootIdsByInstruction.setNew(id, rootIdsByInstruction.size());
                    break;
                }
            }
        }
        return predecessorEdges;
    }

    private void emitList(
            int rootInstructionId,
            SparseIntArray rootIdsByInstruction,
            InstList flattenedInstructions,
            SparseSet reachable,
            IntStack stack)
    {
        reachable.clear();
        stack.clear();
        stack.push(rootInstructionId);
        while (!stack.isEmpty()) {
            int id = stack.pop();
            while (true) {
                if (reachable.contains(id)) {
                    break;
                }
                reachable.insertNew(id);

                if (id != rootInstructionId && rootIdsByInstruction.hasIndex(id)) {
                    // We reached another "tree" via epsilon transition. Emit a NOP
                    // instruction so that the Prog does not become quadratically larger.
                    Inst noOp = new Inst(InstOp.NOP);
                    noOp.setOut(rootIdsByInstruction.getExisting(id));
                    flattenedInstructions.add(noOp);
                    break;
                }

                Inst instruction = inst(id);
                switch (instruction.opcode()) {
                    case ALT_MATCH -> {
                        Inst altMatch = new Inst(InstOp.ALT_MATCH);
                        flattenedInstructions.add(altMatch);
                        int next = flattenedInstructions.size();
                        altMatch.setOut(next);
                        altMatch.setOut1(next + 1);
                        // fall through to ALT handling
                        stack.push(instruction.out1());
                        id = instruction.out();
                    }
                    case ALT -> {
                        stack.push(instruction.out1());
                        id = instruction.out();
                    }
                    case BYTE_RANGE, CAPTURE, EMPTY_WIDTH -> {
                        Inst copy = instruction.copy();
                        copy.setOut(rootIdsByInstruction.getExisting(instruction.out()));
                        flattenedInstructions.add(copy);
                        id = 0;
                    }
                    case NOP -> id = instruction.out();
                    case MATCH, FAIL -> {
                        flattenedInstructions.add(instruction.copy());
                        id = 0;
                    }
                }
                if (id == 0) {
                    break;
                }
            }
        }
    }

    private static void computeHints(
            InstList flattenedInstructions,
            int begin,
            int end,
            Bitmap256 splits,
            int[] colors)
    {
        // A hint is the distance from a byte range to the nearest later byte range in its run of
        // consecutive BYTE_RANGE instructions that can match a byte it matches, or else to the
        // instruction after the run; a run that ends the list yields no hint. Hints are computed
        // per run, scanning from the end of the list. A run of one byte range needs no byte-class
        // bookkeeping: its hint is always one, the following instruction. Runs of two or more byte
        // ranges use the split bitmap below.
        int id = end - 1;
        while (id >= begin) {
            if (flattenedInstructions.get(id).opcode() != InstOp.BYTE_RANGE) {
                id--;
                continue;
            }
            int runEnd = id + 1;
            int runBegin = id;
            while (runBegin > begin && flattenedInstructions.get(runBegin - 1).opcode() == InstOp.BYTE_RANGE) {
                runBegin--;
            }
            if (runBegin == id) {
                if (runEnd != end) {
                    setHint(flattenedInstructions.get(id), 1);
                }
            }
            else {
                computeRunHints(flattenedInstructions, runBegin, runEnd, end, splits, colors);
            }
            id = runBegin - 1;
        }
    }

    private static void computeRunHints(
            InstList flattenedInstructions,
            int runBegin,
            int runEnd,
            int end,
            Bitmap256 splits,
            int[] colors)
    {
        // This is upstream's hint loop restricted to one run: seeding all 256 bytes with the color
        // runEnd bounds each hint by the instruction after the run, and a run that ends the list
        // (runEnd == end) gets none.
        splits.clear();
        splits.set(255);
        colors[255] = runEnd;
        for (int id = runEnd - 1; id >= runBegin; id--) {
            int first = end;

            Inst instruction = flattenedInstructions.get(id);
            int lo = instruction.lo();
            int hi = instruction.hi();
            first = recolorHints(splits, colors, id, lo, hi, first);
            if (instruction.foldCase() && lo <= 'z' && hi >= 'a') {
                int foldlo = lo;
                int foldhi = hi;
                if (foldlo < 'a') {
                    foldlo = 'a';
                }
                if (foldhi > 'z') {
                    foldhi = 'z';
                }
                if (foldlo <= foldhi) {
                    foldlo += 'A' - 'a';
                    foldhi += 'A' - 'a';
                    first = recolorHints(splits, colors, id, foldlo, foldhi, first);
                }
            }

            if (first != end) {
                setHint(instruction, first - id);
            }
        }
    }

    // Hints are stored above the fold-case bit and saturate at the largest encodable distance.
    private static void setHint(Inst instruction, int distance)
    {
        instruction.hintFoldCase |= Math.min(distance, 32767) << 1;
    }

    private static int recolorHints(Bitmap256 splits, int[] colors, int id, int lo, int hi, int first)
    {
        // Like ByteMapBuilder, split at lo-1 and at hi.
        lo--;

        if (0 <= lo && !splits.test(lo)) {
            splits.set(lo);
            int next = splits.findNextSetBit(lo + 1);
            colors[lo] = colors[next];
        }
        if (!splits.test(hi)) {
            splits.set(hi);
            int next = splits.findNextSetBit(hi + 1);
            colors[hi] = colors[next];
        }

        int c = lo + 1;
        while (c < 256) {
            int next = splits.findNextSetBit(c);
            first = Math.min(first, colors[next]);
            colors[next] = id;
            if (next == hi) {
                break;
            }
            c = next + 1;
        }
        return first;
    }

    // Builds the equivalence classes used to reduce the DFA alphabet.
    static final class ByteMapBuilder
    {
        // Small programs rarely repeat a range group, so groups are neither recorded nor looked
        // up during this many initial merges.
        private static final int GROUP_DEDUPLICATION_MERGES = 32;

        // The most live colors there can be, one for each split.
        private static final int MAXIMUM_LIVE_COLORS = 256;

        private final Bitmap256 splits = new Bitmap256();
        private final int[] colors = new int[256];
        // Colors are numbered from 0, and a merge gives each new color the next number. Upstream
        // never reuses a number and looks colors up in a per-merge list. The remap tables here
        // are indexed by color instead, so once a merge takes the next number past
        // MAXIMUM_LIVE_COLORS, the live colors are renumbered densely. A merge creates at most one
        // color for each live one, so the tables never need more than twice MAXIMUM_LIVE_COLORS
        // entries, where a large Unicode class would otherwise number thousands of colors.
        // build() numbers byte classes by first appearance, so no color number reaches the byte
        // map.
        private int nextColor = 1;

        private int[] colorRemap = new int[64];
        private int[] colorRemapGeneration = new int[64];
        private int remapGeneration = 1;

        private int[] rangeLo = new int[16];
        private int[] rangeHi = new int[16];
        private int rangeSize;

        private int mergeCount;
        // Open-addressing set of packed range groups that have already been merged.
        private long[] mergedGroups;
        private int mergedGroupCount;

        ByteMapBuilder()
        {
            // Initial state: the [0-255] range has color 0.
            splits.set(255);
        }

        void mark(int lo, int hi)
        {
            if (lo < 0 || lo > 255 || hi < 0 || hi > 255 || lo > hi) {
                throw new IllegalArgumentException("range must be within [0,255]: [" + lo + "," + hi + "]");
            }

            // Ignore any [0-255] ranges.
            if (lo == 0 && hi == 255) {
                return;
            }

            if (rangeSize == rangeLo.length) {
                rangeLo = Arrays.copyOf(rangeLo, rangeLo.length * 2);
                rangeHi = Arrays.copyOf(rangeHi, rangeHi.length * 2);
            }
            rangeLo[rangeSize] = lo;
            rangeHi[rangeSize] = hi;
            rangeSize++;
        }

        void merge()
        {
            // After a group is merged, every color lies entirely inside or outside it, and later
            // merges only refine colors. Merging the same group again adds no split and only
            // renames colors, which build() renumbers by first appearance, so skip it.
            mergeCount++;
            if (mergeCount > GROUP_DEDUPLICATION_MERGES && !addMergedGroup(groupKey())) {
                rangeSize = 0;
                return;
            }
            for (int i = 0; i < rangeSize; i++) {
                int lo = rangeLo[i] - 1;
                int hi = rangeHi[i];

                if (0 <= lo && !splits.test(lo)) {
                    splits.set(lo);
                    int next = splits.findNextSetBit(lo + 1);
                    colors[lo] = colors[next];
                }
                if (!splits.test(hi)) {
                    splits.set(hi);
                    int next = splits.findNextSetBit(hi + 1);
                    colors[hi] = colors[next];
                }

                int c = lo + 1;
                while (c < 256) {
                    int next = splits.findNextSetBit(c);
                    colors[next] = recolor(colors[next]);
                    if (next == hi) {
                        break;
                    }
                    c = next + 1;
                }
            }
            nextRemapGeneration();
            if (nextColor > MAXIMUM_LIVE_COLORS) {
                renumberColors();
            }
            rangeSize = 0;
        }

        // Renumbers the live colors from 0 in order of first appearance. Only the colors at
        // splits are ever read. Relies on a fresh remap generation, so every color starts
        // unmapped; the constructor, merge() and this method each leave one.
        private void renumberColors()
        {
            int liveColorCount = 0;
            int c = 0;
            while (c < 256) {
                int next = splits.findNextSetBit(c);
                int color = colors[next];
                if (colorRemapGeneration[color] == remapGeneration) {
                    colors[next] = colorRemap[color];
                }
                else {
                    colorRemap[color] = liveColorCount;
                    colorRemapGeneration[color] = remapGeneration;
                    colors[next] = liveColorCount;
                    liveColorCount++;
                }
                c = next + 1;
            }
            nextColor = liveColorCount;
            nextRemapGeneration();
        }

        private void nextRemapGeneration()
        {
            remapGeneration++;
            if (remapGeneration == 0) {
                Arrays.fill(colorRemapGeneration, 0);
                remapGeneration = 1;
            }
        }

        private long groupKey()
        {
            // Packs up to three ranges and their count; larger groups are always merged.
            if (rangeSize == 0 || rangeSize > 3) {
                return 0;
            }
            long key = rangeSize;
            for (int i = 0; i < rangeSize; i++) {
                key = (key << 16) | ((long) rangeLo[i] << 8) | rangeHi[i];
            }
            return key;
        }

        private boolean addMergedGroup(long key)
        {
            if (key == 0) {
                return true;
            }
            if (mergedGroups == null) {
                mergedGroups = new long[64];
            }
            if (!addWithoutResize(key)) {
                return false;
            }
            mergedGroupCount++;
            if (mergedGroupCount * 2 > mergedGroups.length) {
                long[] oldGroups = mergedGroups;
                mergedGroups = new long[oldGroups.length * 2];
                for (long oldKey : oldGroups) {
                    if (oldKey != 0) {
                        addWithoutResize(oldKey);
                    }
                }
            }
            return true;
        }

        // Returns false when the key was already present.
        private boolean addWithoutResize(long key)
        {
            int mask = mergedGroups.length - 1;
            int index = SeededHash.hash(key) & mask;
            while (mergedGroups[index] != 0) {
                if (mergedGroups[index] == key) {
                    return false;
                }
                index = (index + 1) & mask;
            }
            mergedGroups[index] = key;
            return true;
        }

        /// Returns where the merged groups ended up in their set, which holds the distinct
        /// groups merged after the first `GROUP_DEDUPLICATION_MERGES` merges.
        SeededHash.Placement mergedGroupPlacementForTesting()
        {
            return SeededHash.Placement.of(mergedGroups.length, slot -> mergedGroups[slot] != 0, slot -> mergedGroups[slot]);
        }

        void build(byte[] bytemap, int[] bytemapRangeOut)
        {
            requireNonNull(bytemap, "bytemap is null");
            if (bytemap.length != 256) {
                throw new IllegalArgumentException("bytemap must have length 256");
            }
            requireNonNull(bytemapRangeOut, "bytemapRangeOut is null");
            if (bytemapRangeOut.length != 1) {
                throw new IllegalArgumentException("bytemapRangeOut must have length 1");
            }

            // Byte classes are the live colors numbered from 0 in order of first appearance.
            renumberColors();
            int c = 0;
            while (c < 256) {
                int next = splits.findNextSetBit(c);
                Arrays.fill(bytemap, c, next + 1, (byte) colors[next]);
                c = next + 1;
            }

            bytemapRangeOut[0] = nextColor;
        }

        private int recolor(int oldColor)
        {
            if (colorRemapGeneration[oldColor] == remapGeneration) {
                return colorRemap[oldColor];
            }

            int newColor = nextColor;
            nextColor++;

            if (newColor >= colorRemap.length) {
                int newSize = colorRemap.length * 2;
                colorRemap = Arrays.copyOf(colorRemap, newSize);
                colorRemapGeneration = Arrays.copyOf(colorRemapGeneration, newSize);
            }

            colorRemap[oldColor] = newColor;
            colorRemapGeneration[oldColor] = remapGeneration;
            // Map the new color to itself so a later range in the same batch does not recolor an
            // already recolored segment, as upstream Recolor does.
            colorRemap[newColor] = newColor;
            colorRemapGeneration[newColor] = remapGeneration;
            return newColor;
        }
    }

    // Growable instruction array used while emitting flattened lists.
    private static final class InstList
    {
        private final int maxSize;
        private final long maxMemory;
        private Inst[] instructions;
        private int size;

        InstList(int capacity, int maxSize, long maxMemory)
        {
            this.maxSize = maxSize;
            this.maxMemory = maxMemory;
            instructions = new Inst[Math.max(1, capacity)];
        }

        void add(Inst instruction)
        {
            if (size == maxSize) {
                throw new RegexpCompileMemoryLimitException(maxMemory);
            }
            if (size == instructions.length) {
                instructions = Arrays.copyOf(instructions, (int) Math.min(size * 2L, maxSize));
            }
            instructions[size++] = instruction;
        }

        Inst get(int index)
        {
            return instructions[index];
        }

        Inst getLast()
        {
            return instructions[size - 1];
        }

        int size()
        {
            return size;
        }

        /// The instructions for the program to keep. The array starts at the unflattened size, and
        /// a program that flattens to less than half of that would keep more slots than the
        /// flattened charge's doubling allowance covers, so it gets an exact copy instead.
        Inst[] retainedInstructions()
        {
            if (instructions.length > 2L * size) {
                return Arrays.copyOf(instructions, size);
            }
            return instructions;
        }
    }

    private static final class IntStack
    {
        private int[] values;
        private int size;

        IntStack(int capacity)
        {
            values = new int[Math.max(1, capacity)];
        }

        void clear()
        {
            size = 0;
        }

        boolean isEmpty()
        {
            return size == 0;
        }

        void push(int v)
        {
            if (size >= values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = v;
        }

        int pop()
        {
            return values[--size];
        }
    }

    private static final class PredecessorLists
    {
        private final int[] firstEdges;
        private int[] predecessorInstructionIds;
        private int[] nextEdges;
        private int edgeCount;

        PredecessorLists(int instructionCount)
        {
            firstEdges = new int[instructionCount];
            Arrays.fill(firstEdges, -1);
            predecessorInstructionIds = new int[Math.max(4, instructionCount)];
            nextEdges = new int[predecessorInstructionIds.length];
        }

        void add(int outputInstructionId, int predecessorInstructionId)
        {
            if (edgeCount == predecessorInstructionIds.length) {
                int newSize = predecessorInstructionIds.length * 2;
                predecessorInstructionIds = Arrays.copyOf(predecessorInstructionIds, newSize);
                nextEdges = Arrays.copyOf(nextEdges, newSize);
            }
            predecessorInstructionIds[edgeCount] = predecessorInstructionId;
            nextEdges[edgeCount] = firstEdges[outputInstructionId];
            firstEdges[outputInstructionId] = edgeCount;
            edgeCount++;
        }

        int firstEdge(int outputInstructionId)
        {
            return firstEdges[outputInstructionId];
        }

        int nextEdge(int edge)
        {
            return nextEdges[edge];
        }

        int predecessorInstructionId(int edge)
        {
            return predecessorInstructionIds[edge];
        }
    }

    private String progToString(SparseSet queue)
    {
        StringBuilder builder = new StringBuilder();
        for (int queueIndex = 0; queueIndex < queue.size(); queueIndex++) {
            int id = queue.denseAt(queueIndex);
            Inst instruction = inst(id);
            builder.append(id).append(". ").append(instruction.dump()).append('\n');

            addToQueue(queue, instruction.out());
            if (instruction.opcode() == InstOp.ALT || instruction.opcode() == InstOp.ALT_MATCH) {
                addToQueue(queue, instruction.out1());
            }
        }
        return builder.toString();
    }

    private String flattenedProgToString(int start)
    {
        StringBuilder builder = new StringBuilder();
        for (int id = start; id < size(); id++) {
            Inst instruction = inst(id);
            if (instruction.last()) {
                builder.append(id).append(". ").append(instruction.dump()).append('\n');
            }
            else {
                builder.append(id).append("+ ").append(instruction.dump()).append('\n');
            }
        }
        return builder.toString();
    }

    private static void addToQueue(SparseSet queue, int id)
    {
        if (id != 0) {
            queue.insert(id);
        }
    }
}
