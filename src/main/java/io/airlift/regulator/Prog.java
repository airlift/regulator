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
import io.airlift.slice.SizeOf;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
    private static final long ANCHORED_BIT_STATE_BITMAP_MAXIMUM_BYTES = 256L * 1024L;
    // Upstream's visited bitmap bound, 256 Kibit, which caps unanchored BitState searches.
    private static final long UNANCHORED_BIT_STATE_BITMAP_MAXIMUM_BYTES = 32L * 1024L;
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
        // The vector pair scan of FUSED_VECTOR over the literals every match begins with, which
        // run past the prefix; see buildPrefixLiteralSet.
        FUSED_LITERAL_SET,
    }

    // VarHandles for SWAR byte scanning (8-byte and 4-byte at a time)
    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final int MAXIMUM_FIXED_DISTANCE_BYTE_OFFSET = 16;
    // The results of walkByteOffset other than an instruction count.
    private static final int BYTE_OFFSET_ASSERTION = -1;
    private static final int BYTE_OFFSET_MATCH = -2;
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

    // The probe pair a folded pair kernel tests and whether the dense kernel scans it; see
    // foldedPairScan.
    record FoldedPairScan(int firstOffset, int secondOffset, boolean dense) {}

    /// A folded run of three to nine bytes that every match holds after a lead of `minimumLead`
    /// through `maximumLead` bytes, as [Regexp#foldedRunsBehindStartLiteral] finds it behind a
    /// transparent start literal. The run scans with `runProgram`, a program that
    /// [#configurePrefixAccel] configures with the run as its folded prefix, so its SWAR scan is
    /// that program's [#findFoldCaseSwar]. The record keeps the run program's probe pair offsets
    /// and whether the vector scan verifies in its loop, for the vector scan, and its probe
    /// broadcasts, which [#findFoldCaseSwar] takes as arguments. The pattern keeps the record and
    /// leaves its own program untouched; [CompiledPattern]'s build, where the run is reserved,
    /// says why.
    record FoldedStartRun(
            Prog runProgram,
            byte[] run,
            int minimumLead,
            int maximumLead,
            int firstOffset,
            int secondOffset,
            boolean dense,
            long firstLowerBroadcast,
            long firstCaseBroadcast,
            long secondLowerBroadcast,
            long secondCaseBroadcast)
    {
        private static final long INSTANCE_SIZE = SizeOf.instanceSize(FoldedStartRun.class);
        private static final long PROGRAM_INSTANCE_SIZE = SizeOf.instanceSize(Prog.class);
        private static final long ATOMIC_REFERENCE_INSTANCE_SIZE = SizeOf.instanceSize(AtomicReference.class);
        private static final long ATOMIC_LONG_ARRAY_INSTANCE_SIZE = SizeOf.instanceSize(AtomicLongArray.class);

        /// Builds the run's scan on a program configured with the run as its folded prefix,
        /// probing the pair `byteFrequencies` chooses as it does for any folded prefix.
        static FoldedStartRun create(byte[] run, int minimumLead, int maximumLead, ByteFrequencies byteFrequencies)
        {
            if (run.length < 3 || run.length > MAX_FOLD_CASE_PREFIX_BYTES || minimumLead < 0 || maximumLead < minimumLead) {
                throw new IllegalArgumentException("folded start run must hold 3 to " + MAX_FOLD_CASE_PREFIX_BYTES + " bytes behind a lead of 0 <= minimum <= maximum: " +
                        run.length + " bytes, lead [" + minimumLead + "," + maximumLead + "]");
            }
            Prog runProgram = new Prog();
            runProgram.configurePrefixAccel(Slices.wrappedBuffer(run), true, byteFrequencies);
            return new FoldedStartRun(
                    runProgram,
                    runProgram.prefix,
                    minimumLead,
                    maximumLead,
                    runProgram.prefixFoldCaseFirstOffset,
                    runProgram.prefixFoldCaseSecondOffset,
                    runProgram.prefixFoldCaseDense,
                    runProgram.prefixFoldCaseFirstLowerBroadcast,
                    runProgram.prefixFoldCaseFirstCaseBroadcast,
                    runProgram.prefixFoldCaseSecondLowerBroadcast,
                    runProgram.prefixFoldCaseSecondCaseBroadcast);
        }

        /// Returns the absolute index in `data` of the first occurrence of the run in the range,
        /// or `-1`. From [#FUSED_PREFIX_VECTOR_MINIMUM_BYTES] bytes with the Vector API, the run
        /// scans with [VectorPrefixScanner#findFoldCaseDensePair] when the run program chose the
        /// dense kernel and otherwise with [VectorPrefixScanner#findFoldCasePair], each of which
        /// picks the platform's candidate-mask form. Below that, or without the Vector API, it
        /// scans with the run program's generic pair SWAR kernel, [#findFoldCaseSwar], for every
        /// pair, including a first-and-last pair, which [#prefixAccelFoldCase] scans with
        /// [#prefixAccelFoldCaseFrontAndBackSWAR] instead.
        int find(byte[] data, int offset, int length)
        {
            if (length < run.length) {
                return -1;
            }
            if (VectorSupport.isAvailable() && length >= FUSED_PREFIX_VECTOR_MINIMUM_BYTES) {
                if (dense) {
                    return VectorPrefixScanner.findFoldCaseDensePair(data, offset, length, run, run.length, firstOffset, secondOffset);
                }
                return VectorPrefixScanner.findFoldCasePair(data, offset, length, run, run.length, firstOffset, secondOffset);
            }
            return runProgram.findFoldCaseSwar(data, offset, length, firstOffset, secondOffset, firstLowerBroadcast, firstCaseBroadcast, secondLowerBroadcast, secondCaseBroadcast);
        }

        /// The record and its run program, which holds the run as its prefix, its fail
        /// instruction's word in its instruction word array, its per-opcode counts and bytemap,
        /// and its empty DFA and NFA references and failed DFA budgets.
        long estimatedRetainedSize()
        {
            return INSTANCE_SIZE +
                    PROGRAM_INSTANCE_SIZE +
                    SizeOf.sizeOf(run) +
                    SizeOf.sizeOf(runProgram.words) +
                    SizeOf.sizeOf(runProgram.instCount) +
                    SizeOf.sizeOf(runProgram.bytemap) +
                    4 * ATOMIC_REFERENCE_INSTANCE_SIZE +
                    ATOMIC_LONG_ARRAY_INSTANCE_SIZE +
                    SizeOf.sizeOfLongArray(runProgram.failedDfaBudgets.length());
        }
    }

    // An instruction is one packed word, the form upstream's `Prog::Inst` takes:
    // [0..2] opcode, [3] last, [4..31] output, [32..63] opcode-specific payload. ALT and ALT_MATCH
    // keep their second output in the payload's low 28 bits, and ALT_MATCH sets payload bit 31
    // when it is greedy, which getOrCreateCaptureInstructionWords marks once the program is
    // flattened. BYTE_RANGE keeps lo in payload bits 0..7, hi in 8..15, the hint in 16..30 and
    // fold-case in 31. CAPTURE, EMPTY_WIDTH and MATCH keep their int in the whole payload.
    // Nfa's capture search (NfaImpl.search, step and addToThreadq, and remapCaptureSlots) and
    // BitState.trySearch decode these fields by hand, so a layout change must update them too.
    private static final InstOp[] INSTRUCTION_OPCODES = InstOp.values();
    private static final long INSTRUCTION_OPCODE_MASK = 0b111;
    private static final long INSTRUCTION_LAST_BIT = 1L << 3;
    private static final int INSTRUCTION_OUTPUT_SHIFT = 4;
    private static final int INSTRUCTION_PAYLOAD_SHIFT = 32;
    private static final long INSTRUCTION_OUTPUT_BITS = (long) MAX_FLATTENED_INSTRUCTIONS << INSTRUCTION_OUTPUT_SHIFT;
    private static final long INSTRUCTION_SECOND_OUTPUT_BITS = (long) MAX_FLATTENED_INSTRUCTIONS << INSTRUCTION_PAYLOAD_SHIFT;
    private static final long INSTRUCTION_PAYLOAD_BITS = 0xFFFF_FFFFL << INSTRUCTION_PAYLOAD_SHIFT;
    private static final long INSTRUCTION_HINT_BITS = 0x7FFFL << 48;
    private static final long INSTRUCTION_HIGH_BIT = 1L << 63;
    private static final int MAXIMUM_HINT = 0x7FFF;

    static InstOp instOpcode(long word)
    {
        return INSTRUCTION_OPCODES[(int) (word & INSTRUCTION_OPCODE_MASK)];
    }

    static boolean instLast(long word)
    {
        return (word & INSTRUCTION_LAST_BIT) != 0;
    }

    static int instOut(long word)
    {
        return (int) ((word >>> INSTRUCTION_OUTPUT_SHIFT) & MAX_FLATTENED_INSTRUCTIONS);
    }

    static int instOut1(long word)
    {
        return (int) ((word >>> INSTRUCTION_PAYLOAD_SHIFT) & MAX_FLATTENED_INSTRUCTIONS);
    }

    static int instLo(long word)
    {
        return (int) (word >>> INSTRUCTION_PAYLOAD_SHIFT) & 0xFF;
    }

    static int instHi(long word)
    {
        return (int) (word >>> 40) & 0xFF;
    }

    static int instHint(long word)
    {
        return (int) (word >>> 48) & MAXIMUM_HINT;
    }

    static boolean instFoldCase(long word)
    {
        return (word & INSTRUCTION_HIGH_BIT) != 0;
    }

    static int instCap(long word)
    {
        return (int) (word >>> INSTRUCTION_PAYLOAD_SHIFT);
    }

    static int instEmpty(long word)
    {
        return (int) (word >>> INSTRUCTION_PAYLOAD_SHIFT);
    }

    static int instMatchId(long word)
    {
        return (int) (word >>> INSTRUCTION_PAYLOAD_SHIFT);
    }

    /// Whether the byte range `word` matches `c`.
    static boolean instMatches(long word, int c)
    {
        // Upstream Inst::Matches() expects an int byte value. The DFA uses a sentinel
        // "end text" byte (256) and NFA-style engines use -1 for "no byte".
        // Both must never match a byte range.
        if (c < 0 || c > 0xFF) {
            return false;
        }
        int b = c;
        if (instFoldCase(word) && 'A' <= b && b <= 'Z') {
            b += 'a' - 'A';
        }
        return instLo(word) <= b && b <= instHi(word);
    }

    private static long instWord(InstOp opcode, int out, long payload)
    {
        checkPackedInstructionOutput(out);
        return opcode.ordinal() | (long) out << INSTRUCTION_OUTPUT_SHIFT | payload << INSTRUCTION_PAYLOAD_SHIFT;
    }

    static long withInstOut(long word, int out)
    {
        checkPackedInstructionOutput(out);
        return (word & ~INSTRUCTION_OUTPUT_BITS) | (long) out << INSTRUCTION_OUTPUT_SHIFT;
    }

    static long withInstOut1(long word, int out1)
    {
        checkPackedInstructionOutput(out1);
        return (word & ~INSTRUCTION_SECOND_OUTPUT_BITS) | (long) out1 << INSTRUCTION_PAYLOAD_SHIFT;
    }

    static long withInstLast(long word)
    {
        return word | INSTRUCTION_LAST_BIT;
    }

    private static long withInstOpcode(long word, InstOp opcode)
    {
        return (word & ~INSTRUCTION_OPCODE_MASK) | opcode.ordinal();
    }

    private static long withInstPayload(long word, int payload)
    {
        return (word & ~INSTRUCTION_PAYLOAD_BITS) | Integer.toUnsignedLong(payload) << INSTRUCTION_PAYLOAD_SHIFT;
    }

    // Hints saturate at the largest encodable distance.
    private static long withInstHint(long word, int distance)
    {
        return (word & ~INSTRUCTION_HINT_BITS) | (long) Math.min(distance, MAXIMUM_HINT) << 48;
    }

    private static void checkPackedInstructionOutput(int output)
    {
        if (output < 0 || output > MAX_FLATTENED_INSTRUCTIONS) {
            throw new IllegalStateException("output exceeds packed instruction limit: " + output);
        }
    }

    static long altWord(int out, int out1)
    {
        checkPackedInstructionOutput(out1);
        return instWord(InstOp.ALT, out, out1);
    }

    static long altMatchWord(int out, int out1)
    {
        checkPackedInstructionOutput(out1);
        return instWord(InstOp.ALT_MATCH, out, out1);
    }

    static long byteRangeWord(int lo, int hi, boolean foldCase, int out)
    {
        return instWord(InstOp.BYTE_RANGE, out, (lo & 0xFF) | (long) (hi & 0xFF) << 8 | (foldCase ? 1L << 31 : 0));
    }

    static long captureWord(int cap, int out)
    {
        return instWord(InstOp.CAPTURE, out, Integer.toUnsignedLong(cap));
    }

    static long emptyWidthWord(int empty, int out)
    {
        return instWord(InstOp.EMPTY_WIDTH, out, Integer.toUnsignedLong(empty));
    }

    static long matchWord(int matchId)
    {
        return instWord(InstOp.MATCH, 0, Integer.toUnsignedLong(matchId));
    }

    static long nopWord(int out)
    {
        return instWord(InstOp.NOP, out, 0);
    }

    static long failWord()
    {
        return instWord(InstOp.FAIL, 0, 0);
    }

    // Whether the ALT_MATCH `word` is greedy: its first output reads a byte, directly or past one
    // no-op.
    boolean greedyAltMatch(long word)
    {
        long outWord = words[instOut(word)];
        if (instOpcode(outWord) == InstOp.BYTE_RANGE) {
            return true;
        }
        return instOpcode(outWord) == InstOp.NOP &&
                instOpcode(words[instOut(outWord)]) == InstOp.BYTE_RANGE;
    }

    static String dumpInst(long word)
    {
        int out = instOut(word);
        return switch (instOpcode(word)) {
            case ALT -> "alt -> " + out + " | " + instOut1(word);
            case ALT_MATCH -> "altmatch -> " + out + " | " + instOut1(word);
            case BYTE_RANGE -> "byte" +
                    (instFoldCase(word) ? "/i" : "") +
                    " [" + hex2(instLo(word)) + "-" + hex2(instHi(word)) + "] " +
                    instHint(word) + " -> " + out;
            case CAPTURE -> "capture " + instCap(word) + " -> " + out;
            case EMPTY_WIDTH -> "emptywidth " + hashHex(instEmpty(word)) + " -> " + out;
            case MATCH -> "match! " + instMatchId(word);
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

    // The packed instruction words; see instOpcode. Before flattening the array grows by doubling;
    // flattening leaves it exactly the instruction count.
    private long[] words = new long[16];
    private int instructionCount;

    private boolean didFlatten;
    private int start;
    private int startUnanchored;

    private boolean anchorStart;
    private boolean anchorEnd;
    private boolean canMatchEmpty;
    private boolean hasLoop = true;
    private boolean hasEmptyWidthAssertions;
    private int textDependentAssertions;
    private boolean hasFullCaseFold;
    private boolean keepsNullableLoopPriority;
    private boolean hasDirectionalRepetition;

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
    private int anchoredBitStateTextMaxSize;
    private int unanchoredBitStateTextMaxSize;
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
    // The fused pair scans test the bytes at prefixPairOffset and prefixPairSecondaryOffset. For
    // a periodic prefix they are the adjacent pair from adjacentPairOffset, when it finds one.
    // Otherwise the second is the last offset, and the first is 0, or, when the prefix starts with
    // a multibyte UTF-8 character that ends before the last offset, the offset of that character's
    // last byte: a lead byte names little more than the script, which most letters of a text
    // share, while the character's last byte tells its letters apart.
    private int prefixPairOffset;
    private int prefixPairSecondaryOffset;
    // The folded pair scans test the bytes at these two offsets of a folded prefix, chosen by
    // ByteFrequencies.foldedProbePair; see configurePrefixAccelBytes.
    private int prefixFoldCaseFirstOffset;
    private int prefixFoldCaseSecondOffset;
    // Whether the folded vector scan takes VectorPrefixScanner.findFoldCaseDensePair rather than
    // findFoldCasePair, chosen when the pair is configured; see usesDenseFoldCaseScan.
    private boolean prefixFoldCaseDense;
    // Whether the folded pair is the prefix's first and last bytes, which short windows scan with
    // prefixAccelFoldCaseFrontAndBackSWAR rather than findFoldCaseSwar, which serves pairs
    // elsewhere in the prefix; prefixAccelFoldCaseFrontAndBackSWAR's comment gives the
    // measurement.
    private boolean prefixFoldCaseEndPair;
    // The SWAR folded scans compare a candidate without calls: a data byte d matches a folded
    // prefix byte when d with the prefix byte's case bit ORed in equals the prefix byte with A-Z
    // lowered. The case bit is 0x20 when the lowered byte is an ASCII letter and 0 otherwise,
    // since 0x20 would alias pairs such as '@' and '`'. The lowered bytes and case bits of the
    // prefix's first eight bytes are packed low byte first, and those of its last byte, the
    // ninth of a nine-byte prefix, are kept apart.
    private long prefixFoldCaseLowerBytes;
    private long prefixFoldCaseCaseBits;
    private byte prefixFoldCaseLastLowerByte;
    private byte prefixFoldCaseLastCaseBit;
    // The lowered byte and case bit of each probe byte of the folded pair, in every byte of a
    // long: a byte of (word | case bits) ^ lowered bytes is zero where the word holds the probe.
    private long prefixFoldCaseFirstLowerBroadcast;
    private long prefixFoldCaseFirstCaseBroadcast;
    private long prefixFoldCaseSecondLowerBroadcast;
    private long prefixFoldCaseSecondCaseBroadcast;
    // A folded prefix may be a run that every match holds after a lead of prefixMinimumLead
    // through prefixMaximumLead bytes, rather than at its start; see configureFoldCasePrefixRun.
    // Both are 0 for a prefix.
    private int prefixMinimumLead;
    private int prefixMaximumLead;
    // Whether the folded run is one that every match starts with, configured because the program
    // has no folded prefix. A first-match DFA whose start bytes carry a start literal filter keeps
    // its start-byte tables beside such a run, so a find loop can keep verifying candidates from
    // them; see prefixAccelReplacesStartBytes.
    private boolean prefixRunStartsEveryMatch;
    // Whether the folded run stands in for no prefix, configured because the program has no
    // folded prefix or an empty one, so without the run it would have no prefix scan; see
    // prefixRunAddsPrefixScan.
    private boolean prefixRunAddsPrefixScan;
    // Whether the start bytes of the program without such a run would carry a start literal
    // filter that groups of one bit give; see prefixRunHidesOneBitStartLiteralFilter.
    private boolean prefixRunHidesOneBitStartLiteralFilter;
    // KMP shifts of a non-folded prefix of two or more bytes, for the KMP search a prefix scan
    // finishes with once its failed candidates pass LiteralSearchKernel's comparison budget.
    private int[] prefixShifts;
    // The literals every match begins with, when they run past a non-folded prefix and hold one
    // exact byte at the same offset past it, prefixLiteralSetBackOffset. The vector pair scan
    // tests that byte with the one at prefixPairOffset, in place of the one at
    // prefixPairSecondaryOffset; see buildPrefixLiteralSet. Sorted by length, shortest first.
    // Null otherwise.
    private byte[][] prefixLiteralSet;
    private int prefixLiteralSetBackOffset;
    // The part of the shift table's and literal set's size charged to dfaMemory, returned when the
    // prefix changes.
    private long prefixScanCharge;
    // Whether the decision on the literal set, and its charge, wait for decidePrefixLiteralSet.
    private boolean prefixLiteralSetDeferred;
    // 0x0101010101010101, read from a field so the SWAR word loops of the prefix scans hold it in
    // a register: on aarch64 C2 cannot encode its negation as an immediate, and rebuilt it with
    // four instructions every word, even from a local holding the constant. The constructor
    // assigns it, because an initializer would make it a compile-time constant that javac inlines.
    private final long lowBits;
    static final int MAX_FOLD_CASE_PREFIX_BYTES = 9;
    // The predicted share of vectors holding a folded pair candidate at and above which the
    // folded vector scan verifies candidates inside its vector loop; see usesDenseFoldCaseScan.
    static final double DENSE_FOLD_CASE_VECTOR_SHARE = 0.15;
    // The most literals a prefix literal set compares at each candidate, the same limit as the
    // start literal filter's eight groups.
    private static final int MAXIMUM_PREFIX_LITERALS = 8;

    // The literal every match begins with behind a leading assertion, and its search; see
    // buildTransparentStartLiteral. Both are null unless pattern compilation configures them.
    private byte[] transparentStartLiteral;
    private LiteralSearchKernel transparentStartLiteralSearch;
    // Whether an unanchored group-zero NFA search, or a position automaton search, with no live
    // thread or state skips to the next possible match start: with the prefix acceleration, or
    // with the transparent start literal of a program whose assertions the DFA cannot run. One
    // field keeps the NFA's test to a single load. The capture NFA and BitState still skip only
    // with the prefix acceleration.
    private boolean skipsToStartCandidate;
    // The bit-parallel automaton a group-zero search runs in place of the Pike NFA once the
    // forward DFA fails, and a boolean search once it skips a DFA that thrashed across calls, or
    // null; see configurePositionNfa.
    private PositionNfa positionNfa;

    // Required prefix for instant rejection (extracted via requiredPrefix()).
    // If pattern is "^literal..." and text doesn't contain the literal, reject immediately.
    private boolean requiredPrefixFoldCase;
    private byte[] requiredPrefix;

    // DFA caching with memory budgets (like upstream RE2).
    private long dfaMemory;  // Total DFA memory budget
    // Whether dfaMemory is what a positive maxMemory left the DFAs, rather than an unbudgeted
    // compile's default allowance; see configurePrefixAccelBytes. The prefix literal set is the
    // one optional plan kept or dropped by it: an explicit maxMemory must leave the forward DFAs
    // the memory they need to initialize, while an unbudgeted compile keeps the set under its
    // default allowance.
    private boolean dfaMemoryBudgeted;
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
    // Whether the greedy ALT_MATCH words carry their greedy bit, after which the capture engines
    // read the words; see getOrCreateCaptureInstructionWords.
    private volatile boolean instructionWordsFinished;
    // The capture NFA's plan, set once by pattern compilation before the program is published. A
    // program it never plans keeps these defaults and runs every capture search in one pass.
    private int singlePassCaptureSlots = Integer.MAX_VALUE;
    private int boundedPassCaptureSlots = Integer.MAX_VALUE;

    public Prog()
    {
        lowBits = 0x0101010101010101L;
        // Instruction #0 is always the fail instruction in upstream.
        add(failWord());
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
                long instruction = words[instructionId];
                switch (instOpcode(instruction)) {
                    case ALT -> throw new IllegalStateException("unhandled ALT in Prog.fanout");
                    case BYTE_RANGE -> {
                        if (!instLast(instruction)) {
                            reachable.insert(instructionId + 1);
                        }
                        count++;
                        if (!fanout.hasIndex(instOut(instruction))) {
                            fanout.setNew(instOut(instruction), 0);
                        }
                    }
                    case ALT_MATCH -> {
                        if (!instLast(instruction)) {
                            reachable.insert(instructionId + 1);
                        }
                    }
                    case CAPTURE, EMPTY_WIDTH, NOP -> {
                        if (!instLast(instruction)) {
                            reachable.insert(instructionId + 1);
                        }
                        reachable.insert(instOut(instruction));
                    }
                    case MATCH -> {
                        if (!instLast(instruction)) {
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
        words[last] = 0;
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

    /// Returns whether the compiled pattern holds an unbounded repetition, not counting an
    /// unanchored search's leading loop. True unless the compiler recorded that it has none.
    public boolean hasLoop()
    {
        return hasLoop;
    }

    public void setHasLoop(boolean hasLoop)
    {
        this.hasLoop = hasLoop;
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

    /// Returns whether this program holds a Java counted repetition for which what the pattern
    /// matches can depend on the reading direction, so it has no reverse program. See
    /// [Compiler.CompilerState#javaCountedLoop].
    public boolean hasDirectionalRepetition()
    {
        return hasDirectionalRepetition;
    }

    public void setHasDirectionalRepetition(boolean hasDirectionalRepetition)
    {
        this.hasDirectionalRepetition = hasDirectionalRepetition;
    }

    /// Sets whether flattening keeps the priority of a loop over a body that can match the
    /// empty string, as the Rust crate does. See [#markLoopRoots].
    public void setKeepsNullableLoopPriority(boolean keepsNullableLoopPriority)
    {
        this.keepsNullableLoopPriority = keepsNullableLoopPriority;
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

    /// The retained bytes of the prefix scan's tables: the KMP shifts and the literal set, which
    /// counts only once it is decided; see [#deferPrefixLiteralSet()].
    long prefixScanMemory()
    {
        long shiftsMemory = prefixShifts == null ? 0 : LiteralSearchKernel.prefixShiftsRetainedSize(prefixShifts);
        return prefixLiteralSetDeferred ? shiftsMemory : shiftsMemory + prefixLiteralSetMemory();
    }

    private long prefixLiteralSetMemory()
    {
        if (prefixLiteralSet == null) {
            return 0;
        }
        long memory = SizeOf.sizeOfObjectArray(prefixLiteralSet.length);
        for (byte[] literal : prefixLiteralSet) {
            memory += SizeOf.sizeOf(literal);
        }
        return memory;
    }

    long onePassMemory()
    {
        if (!isOnePass()) {
            return 0;
        }
        return onePassStateCount * onePassStateSize(ONEPASS_ACTION_BYTES, onePassActionCapture != null, onePassActionAssertions != null);
    }

    /// Whether this program is one-pass only because OnePass evaluates Java's `$` and `\Z` and
    /// Trino's `$`: it is one-pass and has one of those assertions. Compilation releases such a
    /// program's tables and restores them only where they fit beside the forward DFAs.
    boolean isOnePassOnlyForFinalLineEnds()
    {
        return isOnePass() && (textDependentAssertions & ONEPASS_FINAL_LINE_END_ASSERTIONS) != 0;
    }

    /// Takes this program's OnePass tables off it and returns their memory to its DFA budget, so
    /// the program is as it would be had it never been one-pass, and returns the tables for
    /// [#restoreOnePass].
    OnePassTables releaseOnePass()
    {
        OnePassTables tables = new OnePassTables(
                onePassStateCount,
                onePassMatchCond,
                onePassStep,
                onePassMatchCapture,
                onePassActionCapture,
                onePassMatchAssertions,
                onePassActionAssertions,
                onePassMemory());
        dfaMemory += tables.retainedSize();
        onePassStateCount = 0;
        onePassMatchCond = null;
        onePassStep = null;
        onePassMatchCapture = null;
        onePassActionCapture = null;
        onePassMatchAssertions = null;
        onePassActionAssertions = null;
        return tables;
    }

    /// Puts back the OnePass tables [#releaseOnePass()] took off this program. The caller charges
    /// their retained size to a DFA budget.
    void restoreOnePass(OnePassTables tables)
    {
        onePassStateCount = tables.stateCount();
        onePassMatchCond = tables.matchCondition();
        onePassStep = tables.step();
        onePassMatchCapture = tables.matchCapture();
        onePassActionCapture = tables.actionCapture();
        onePassMatchAssertions = tables.matchAssertions();
        onePassActionAssertions = tables.actionAssertions();
    }

    /// The OnePass tables of a program, as [#releaseOnePass()] takes them off it, with the bytes
    /// they retain.
    record OnePassTables(
            int stateCount,
            int[] matchCondition,
            long[] step,
            long[] matchCapture,
            long[] actionCapture,
            int[] matchAssertions,
            int[] actionAssertions,
            long retainedSize) {}

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

    /// Returns the longest text an anchored BitState search takes, such as the capture pass over a
    /// span the DFA found.
    public int anchoredBitStateTextMaxSize()
    {
        return anchoredBitStateTextMaxSize;
    }

    /// Returns the longest text an unanchored BitState search takes:
    /// [#anchoredBitStateTextMaxSize()], capped where upstream caps the visited bitmap, at 256
    /// Kibit. An unanchored search clears its bitmap over its whole window, and each find searches
    /// the rest of the text, so the bitmap words a find-all clears grow with the square of this
    /// window.
    int unanchoredBitStateTextMaxSize()
    {
        return unanchoredBitStateTextMaxSize;
    }

    /// Returns the jobs a BitState search may push for each position of its text; see
    /// [BitState#jobsPerPosition]. Meaningful only when [#canBitState()] is true, and only valid
    /// after [#flatten()] has been called.
    int bitStateJobsPerPosition()
    {
        return bitStateJobsPerPosition;
    }

    /// Lowers the anchored BitState text limit, and with it the unanchored one, so that a search's
    /// visited bitmap, capture slots, job stack, and objects fit in `allowance` bytes. Only valid
    /// after [#flatten()] has been called.
    void limitBitStateMemory(long allowance)
    {
        if (outputLists != null) {
            setAnchoredBitStateTextMaxSize(BitState.maxTextLength(this, anchoredBitStateTextMaxSize, allowance));
        }
    }

    // Sets the anchored BitState text limit and derives the unanchored limit from it.
    private void setAnchoredBitStateTextMaxSize(int textMaxSize)
    {
        anchoredBitStateTextMaxSize = textMaxSize;
        unanchoredBitStateTextMaxSize = Math.min(textMaxSize, textMaxSizeForBitmap(UNANCHORED_BIT_STATE_BITMAP_MAXIMUM_BYTES));
    }

    // BitState allocates a bitmap of listCount * (text.size()+1) bits.
    private int textMaxSizeForBitmap(long maximumBitmapBytes)
    {
        return (int) (maximumBitmapBytes * Byte.SIZE / Math.max(1, listCount) - 1);
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
        if (reversed || anchorStart || (nullable && anchorEnd) || prefixAccelReplacesStartBytes() || start() == 0) {
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

            long instruction = words[instructionId];
            switch (instOpcode(instruction)) {
                case BYTE_RANGE -> {
                    candidateCount += addStartByteRange(candidates, instruction);
                    if (!instLast(instruction)) {
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case CAPTURE, NOP -> {
                    if (!instLast(instruction)) {
                        stack[stackPointer++] = instructionId + 1;
                    }
                    stack[stackPointer++] = instOut(instruction);
                }
                case EMPTY_WIDTH -> {
                    // Skipping input would lose the previous-byte context needed by the assertion.
                    // A search for the buildTransparentStartLiteral literal instead verifies each
                    // occurrence from the byte before it.
                    return -1;
                }
                case ALT, ALT_MATCH -> {
                    if (!instLast(instruction)) {
                        stack[stackPointer++] = instructionId + 1;
                    }
                    stack[stackPointer++] = instOut1(instruction);
                    stack[stackPointer++] = instOut(instruction);
                }
                case MATCH -> {
                    if (!nullable) {
                        return -1;
                    }
                    // Leftmost-first prefers this match to the threads after it in the list;
                    // longest-match and many-match searches go on to them.
                    if (!firstMatch && !instLast(instruction)) {
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case FAIL -> {
                    if (!instLast(instruction)) {
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
            }
        }
        return candidateCount;
    }

    /// Returns a 256-entry table that is nonzero for each byte that can begin a non-empty match
    /// from [#start()], or null when the program can match the empty string, the walk reaches an
    /// assertion, or every byte can.
    byte[] boundaryBytes()
    {
        if (canMatchEmpty || start() == 0) {
            return null;
        }
        byte[] candidates = new byte[256];
        int count = walkStartBytes(new int[3 * size() + 1], candidates, false, true);
        return count > 0 && count < 256 ? candidates : null;
    }

    /// Returns the bytes each match from [#start()] can have at each of its first positions, for a
    /// reversed program the bytes before the position it starts from, nearest first, as a 256-bit
    /// set of four longs per position, low bytes first, or null when there is no such set. Like
    /// [#buildStartPositionByteSets], the walk takes every thread one byte at a time, but it
    /// unions every thread's bytes at each position, and like [#buildTransparentStartLiteral] it
    /// reads assertions, captures, and no-ops as matching the empty string. Both only widen a set,
    /// so each holds every byte a match can have there. The sets end before the first position
    /// where a thread can match first, since a shorter match has no byte there, before the first
    /// that admits every byte or none, and after `maximumPositions`. So every match has at least
    /// as many bytes as there are sets.
    long[] boundaryPositionByteSets(int maximumPositions)
    {
        if (canMatchEmpty || start() == 0) {
            return null;
        }
        long[] byteSets = new long[maximumPositions * 4];
        int[] threads = new int[size()];
        int threadCount = 0;
        threads[threadCount++] = start();
        int[] nextThreads = new int[size()];
        boolean[] visited = new boolean[size()];
        boolean[] nextVisited = new boolean[size()];
        int[] stack = new int[3 * size() + 1];
        int position = 0;
        while (position < maximumPositions) {
            Arrays.fill(visited, false);
            Arrays.fill(nextVisited, false);
            int nextThreadCount = 0;
            boolean matches = false;
            for (int threadIndex = 0; threadIndex < threadCount && !matches; threadIndex++) {
                int stackPointer = 0;
                stack[stackPointer++] = threads[threadIndex];
                while (stackPointer > 0 && !matches) {
                    int instructionId = stack[--stackPointer];
                    if (instructionId <= 0 || instructionId >= size() || visited[instructionId]) {
                        continue;
                    }
                    visited[instructionId] = true;

                    long instruction = words[instructionId];
                    switch (instOpcode(instruction)) {
                        case BYTE_RANGE -> {
                            addStartPositionBytes(byteSets, position, instruction);
                            int next = instOut(instruction);
                            if (next > 0 && next < size() && !nextVisited[next]) {
                                nextVisited[next] = true;
                                nextThreads[nextThreadCount++] = next;
                            }
                            if (!instLast(instruction)) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                        case CAPTURE, NOP, EMPTY_WIDTH -> {
                            if (!instLast(instruction)) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instOut(instruction);
                        }
                        case ALT, ALT_MATCH -> {
                            if (!instLast(instruction)) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instOut1(instruction);
                            stack[stackPointer++] = instOut(instruction);
                        }
                        case MATCH -> matches = true;
                        case FAIL -> {
                            if (!instLast(instruction)) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                    }
                }
            }
            int word = position * 4;
            long every = byteSets[word] & byteSets[word + 1] & byteSets[word + 2] & byteSets[word + 3];
            long any = byteSets[word] | byteSets[word + 1] | byteSets[word + 2] | byteSets[word + 3];
            if (matches || every == -1L || any == 0) {
                break;
            }
            position++;
            int[] swap = threads;
            threads = nextThreads;
            nextThreads = swap;
            threadCount = nextThreadCount;
        }
        return position == 0 ? null : Arrays.copyOf(byteSets, position * 4);
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
        if (prefixAccelReplacesStartBytes()) {
            return null;
        }
        return walkStartPositionByteSets(stack, positionCount);
    }

    // The start-position walk that buildStartPositionByteSets and buildPrefixLiteralSet share. The
    // literal set reads it even where prefix acceleration replaces the start bytes.
    private long[][] walkStartPositionByteSets(int[] stack, int positionCount)
    {
        if (reversed || anchorStart || canMatchEmpty || start() == 0) {
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

                    long instruction = words[instructionId];
                    switch (instOpcode(instruction)) {
                        case BYTE_RANGE -> {
                            addStartPositionRange(nextPaths, path.byteSets(), position, instruction);
                            if (!instLast(instruction)) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                        case CAPTURE, NOP -> {
                            if (!instLast(instruction)) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instOut(instruction);
                        }
                        case EMPTY_WIDTH -> {
                            // The walk does not evaluate assertions, so it keeps no filter rather
                            // than drop a path an assertion would allow.
                            return null;
                        }
                        case ALT, ALT_MATCH -> {
                            if (!instLast(instruction)) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instOut1(instruction);
                            stack[stackPointer++] = instOut(instruction);
                        }
                        case MATCH -> {
                            if (position == 0) {
                                return null;
                            }
                            long[] byteSets = path.byteSets().clone();
                            Arrays.fill(byteSets, position * 4, byteSets.length, -1L);
                            completedPaths.add(byteSets);
                            if (!instLast(instruction)) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                        case FAIL -> {
                            if (!instLast(instruction)) {
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
    private static void addStartPositionRange(List<StartPositionPath> paths, long[] previousByteSets, int position, long instruction)
    {
        long[] byteSets = null;
        for (StartPositionPath path : paths) {
            if (path.instructionId() == instOut(instruction) &&
                    Arrays.equals(path.byteSets(), 0, position * 4, previousByteSets, 0, position * 4)) {
                byteSets = path.byteSets();
                break;
            }
        }
        if (byteSets == null) {
            byteSets = previousByteSets.clone();
            paths.add(new StartPositionPath(byteSets, instOut(instruction)));
        }
        addStartPositionBytes(byteSets, position, instruction);
    }

    // Adds the bytes of the byte range `instruction` to the set at `position`. Like
    // addStartByteRange, a folding range reads an upper-case byte as its lower case.
    private static void addStartPositionBytes(long[] byteSets, int position, long instruction)
    {
        for (int value = instLo(instruction); value <= instHi(instruction); value++) {
            if (!instFoldCase(instruction) || value < 'A' || value > 'Z') {
                addStartPositionByte(byteSets, position, value);
            }
            if (instFoldCase(instruction) && 'a' <= value && value <= 'z') {
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

                    long instruction = words[instructionId];
                    switch (instOpcode(instruction)) {
                        case BYTE_RANGE -> {
                            // A folded range matches the upper case of a lower-case letter too.
                            boolean singleByte = instLo(instruction) == instHi(instruction) && (!instFoldCase(instruction) || instLo(instruction) < 'a' || instLo(instruction) > 'z');
                            if (!singleByte || (literalByte >= 0 && literalByte != instLo(instruction))) {
                                pathsEnd = true;
                                break;
                            }
                            literalByte = instLo(instruction);
                            nextInstructions[nextInstructionCount++] = instOut(instruction);
                            if (!instLast(instruction)) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                        }
                        case EMPTY_WIDTH -> {
                            assertionBeforeByte = true;
                            if (!instLast(instruction)) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instOut(instruction);
                        }
                        case CAPTURE, NOP -> {
                            if (!instLast(instruction)) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instOut(instruction);
                        }
                        case ALT, ALT_MATCH -> {
                            if (!instLast(instruction)) {
                                stack[stackPointer++] = instructionId + 1;
                            }
                            stack[stackPointer++] = instOut1(instruction);
                            stack[stackPointer++] = instOut(instruction);
                        }
                        case MATCH -> pathsEnd = true;
                        case FAIL -> {
                            if (!instLast(instruction)) {
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

    /// Retains the automaton [PositionNfa#build] built from this program, which pattern
    /// compilation charges to this program's DFA budget.
    void configurePositionNfa(PositionNfa positionNfa)
    {
        this.positionNfa = requireNonNull(positionNfa, "positionNfa is null");
    }

    /// Returns the bit-parallel automaton of this program, or null when compilation kept none.
    PositionNfa positionNfa()
    {
        return positionNfa;
    }

    /// Returns whether [#skipToStartCandidate] may skip the input an unanchored search with no
    /// live thread would otherwise step over.
    boolean canSkipToStartCandidate()
    {
        return skipsToStartCandidate;
    }

    /// Returns the absolute index in `data` of the first possible match start in the range, or
    /// `-1`: the next prefix acceleration candidate, or the next occurrence of the transparent
    /// start literal. Requires [#canSkipToStartCandidate]. A caller walking forward skips the
    /// calls [#prefixAccel] describes, which for the literal are none past the result.
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
    /// literal's call-free search. The vector scan recomputes the pair, from [#adjacentPairOffset]
    /// or [#pairOffset], on each call: at most four bytes for the pair offset and, for a periodic
    /// literal, at most one period past it, against a range, or with the overload below a window,
    /// of at least 1 KiB, so the literal needs no stored offsets.
    static int findLiteral(byte[] data, int offset, int length, byte[] literal, LiteralSearchKernel literalSearch)
    {
        return findLiteral(data, offset, length, literal, literalSearch, length);
    }

    /// Returns what [#findLiteral(byte\[\],int,int,byte\[\],LiteralSearchKernel)] returns for the
    /// range, with the scan chosen by `windowLength`, the length of the window whose end the range
    /// shares, in place of the range's own length. `RequiredBytes.handoffIndexIn` resumes a
    /// window's scan at the first hit of a shorter literal, and after an early hit the rest of a
    /// long window is often shorter than the vector scan's minimum. The vector scan takes a range
    /// of any length, testing whole vectors of candidates and then the rest one at a time. Scanning
    /// such rests with the call-free search, which reads a word at a time, made the handoff take
    /// 2.1 times as long on Graviton and 2.5 times as long on x86 as a vector scan from the line
    /// start, on 1 KiB lines; see REGULATOR_DECISIONS.md, Required Literal Absence On Long Windows.
    static int findLiteral(byte[] data, int offset, int length, byte[] literal, LiteralSearchKernel literalSearch, int windowLength)
    {
        if (VectorSupport.isAvailable() && windowLength >= FUSED_PREFIX_VECTOR_MINIMUM_BYTES) {
            int adjacentPairOffset = adjacentPairOffset(literal, literalSearch.smallestPeriod());
            int primaryOffset = adjacentPairOffset >= 0 ? adjacentPairOffset : pairOffset(literal);
            int secondaryOffset = adjacentPairOffset >= 0 ? adjacentPairOffset + 1 : literal.length - 1;
            int found = VectorPrefixScanner.find(data, offset, length, literal, primaryOffset, secondaryOffset);
            if (found < -1) {
                return literalSearch.findKmp(data, -2 - found, offset + length);
            }
            return found;
        }
        return literalSearch.findContains(data, offset, length);
    }

    /// Returns the absolute index in `data` of the last occurrence of `literal`, of at least two
    /// bytes, that lies wholly in the range, or `-1`, as [#findLiteral] finds the first: ranges of
    /// at least the fused vector scan's minimum take the vector scan from the range end, finished
    /// with the reverse KMP of `literalSearch` once its failed candidates pass the comparison
    /// budget, and shorter ones take `literalSearch`'s call-free search from the end, which a
    /// kernel from [LiteralSearchKernel#sharingWithLastOccurrence] provides.
    static int findLastLiteral(byte[] data, int offset, int length, byte[] literal, LiteralSearchKernel literalSearch)
    {
        if (VectorSupport.isAvailable() && length >= FUSED_PREFIX_VECTOR_MINIMUM_BYTES) {
            int found = VectorPrefixScanner.findLast(data, offset, length, literal, pairOffset(literal), literal.length - 1);
            if (found < -1) {
                return literalSearch.findLastKmp(data, offset, -2 - found);
            }
            return found;
        }
        return literalSearch.findLastContains(data, offset, length);
    }

    /// Returns the offset of the byte that the fused pair scans test together with the last byte
    /// of `literal`, of at least two bytes, when [#adjacentPairOffset] pairs no adjacent bytes:
    /// the last byte of a leading multibyte UTF-8 character that ends before the literal's last
    /// byte, or else 0.
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

    /// Returns the offset of the first of two adjacent bytes that the fused pair scans test in
    /// place of the [#pairOffset] byte and the last byte, or -1. Only a periodic `literal`, whose
    /// smallest `period` is at most half its length, pairs adjacent bytes: the first two at or
    /// after [#pairOffset] that differ, so a multibyte first character is paired with the byte
    /// after it rather than with its own continuation bytes. A periodic literal's first and last
    /// bytes are two bytes of its repeating unit at a distance the unit gives no meaning, so text
    /// can hold them about as often as it holds each byte, while an adjacent pair of the unit
    /// needs the two bytes together: over rebar's Rust source tools haystack, `aeaeaeaeae`'s `a`
    /// with the `e` nine bytes later occurs 13,509 times and `ae` 181 times. Other literals keep
    /// the far pair, which over rebar's English subtitles `Sherlock Holmes` holds 611 times and its
    /// first two bytes `Sh` 983 times. A literal of one repeated byte, whose period is 1, has no
    /// adjacent pair that differs and returns at once; for any other periodic literal the walk
    /// stops within one period of [#pairOffset], since a period of equal bytes would make the
    /// period 1.
    static int adjacentPairOffset(byte[] literal, int period)
    {
        if (period == 1 || period > literal.length / 2) {
            return -1;
        }
        for (int index = pairOffset(literal); index < literal.length - 1; index++) {
            if (literal[index] != literal[index + 1]) {
                return index;
            }
        }
        return -1;
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
            byte[] candidates = new byte[256];
            currentInstructionCount = walkByteOffset(stack, currentInstructions, currentInstructionCount, byteInstructions, visited, candidates);
            if (currentInstructionCount == BYTE_OFFSET_ASSERTION) {
                return null;
            }
            if (currentInstructionCount == BYTE_OFFSET_MATCH) {
                break;
            }
            int candidateCount = countCandidates(candidates);
            if (offset > 0 && candidateCount > 0 && candidateCount < bestCandidateCount) {
                bestCandidateCount = candidateCount;
                bestCandidates = candidates;
                bestOffset = offset;
            }
        }

        if (bestCandidates == null || bestCandidateCount > 64) {
            return null;
        }
        return new FixedDistanceByteCandidates(bestCandidates, bestOffset);
    }

    /// Returns the bytes at each of the first offsets of every match, for the start class filter
    /// of a forward DFA: one 256-entry membership table per offset; entry `b` of table `k` is
    /// nonzero when some path from [#start()] reads `b` there, the union over all paths. The
    /// tables cover the offsets before the shortest match ends, at most `maximumSpan` of them, so
    /// every match holds a byte of each table at its offset. Returns null when fewer than two
    /// offsets lie before every match end, when an offset reads no byte, when the bounded walk
    /// reaches an assertion, and for a reversed, start-anchored, or empty-matching program, one
    /// with prefix acceleration, or one with no start instruction. The walk is the one
    /// [#buildFixedDistanceByteCandidates] takes.
    byte[][] buildStartOffsetByteSets(int[] stack, int maximumSpan)
    {
        if (reversed || anchorStart || canMatchEmpty || canPrefixAccel() || start() == 0) {
            return null;
        }

        byte[][] byteSets = new byte[maximumSpan][];
        int[] currentInstructions = new int[size()];
        int currentInstructionCount = 1;
        currentInstructions[0] = start();
        int[] byteInstructions = new int[size()];
        boolean[] visited = new boolean[size()];

        int span = 0;
        while (span < maximumSpan) {
            byte[] candidates = new byte[256];
            currentInstructionCount = walkByteOffset(stack, currentInstructions, currentInstructionCount, byteInstructions, visited, candidates);
            if (currentInstructionCount == BYTE_OFFSET_ASSERTION || currentInstructionCount == 0) {
                return null;
            }
            if (currentInstructionCount == BYTE_OFFSET_MATCH) {
                break;
            }
            byteSets[span++] = candidates;
        }
        return span < 2 ? null : Arrays.copyOf(byteSets, span);
    }

    /// Walks one byte offset of the paths from [#start()]: from each of the first
    /// `currentInstructionCount` instructions of `currentInstructions`, follows the empty
    /// transitions and marks in `candidates` every byte a byte instruction reached reads. Returns
    /// [#BYTE_OFFSET_ASSERTION] when the walk reaches an assertion and otherwise
    /// [#BYTE_OFFSET_MATCH] when it reaches a match, so a match can end at this offset; else it
    /// replaces `currentInstructions` with the instructions after the byte instructions reached,
    /// which begin the next offset, and returns their count. `stack`, `byteInstructions`, and
    /// `visited` are scratch, the last two of [#size()] entries.
    private int walkByteOffset(int[] stack, int[] currentInstructions, int currentInstructionCount, int[] byteInstructions, boolean[] visited, byte[] candidates)
    {
        Arrays.fill(visited, false);
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

                long instruction = words[instructionId];
                switch (instOpcode(instruction)) {
                    case BYTE_RANGE -> {
                        addStartByteRange(candidates, instruction);
                        byteInstructions[byteInstructionCount++] = instructionId;
                        if (!instLast(instruction)) {
                            stack[stackPointer++] = instructionId + 1;
                        }
                    }
                    case CAPTURE, NOP -> {
                        if (!instLast(instruction)) {
                            stack[stackPointer++] = instructionId + 1;
                        }
                        stack[stackPointer++] = instOut(instruction);
                    }
                    case EMPTY_WIDTH -> {
                        return BYTE_OFFSET_ASSERTION;
                    }
                    case ALT, ALT_MATCH -> {
                        if (!instLast(instruction)) {
                            stack[stackPointer++] = instructionId + 1;
                        }
                        stack[stackPointer++] = instOut1(instruction);
                        stack[stackPointer++] = instOut(instruction);
                    }
                    case MATCH -> {
                        canMatchAtCurrentOffset = true;
                        if (!instLast(instruction)) {
                            stack[stackPointer++] = instructionId + 1;
                        }
                    }
                    case FAIL -> {
                        if (!instLast(instruction)) {
                            stack[stackPointer++] = instructionId + 1;
                        }
                    }
                }
            }
        }

        if (canMatchAtCurrentOffset) {
            return BYTE_OFFSET_MATCH;
        }
        for (int index = 0; index < byteInstructionCount; index++) {
            currentInstructions[index] = instOut(words[byteInstructions[index]]);
        }
        return byteInstructionCount;
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

    private static int addStartByteRange(byte[] candidates, long instruction)
    {
        int added = 0;
        for (int value = instLo(instruction); value <= instHi(instruction); value++) {
            if ((!instFoldCase(instruction) || value < 'A' || value > 'Z') && candidates[value] == 0) {
                candidates[value] = 1;
                added++;
            }
        }
        if (instFoldCase(instruction)) {
            for (int value = 'A'; value <= 'Z'; value++) {
                int foldedValue = value + ('a' - 'A');
                if (instLo(instruction) <= foldedValue && foldedValue <= instHi(instruction) && candidates[value] == 0) {
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
    /// [VectorPrefixScanner#findFoldCasePair] or [VectorPrefixScanner#findFoldCaseDensePair],
    /// under the same cutoff as [PrefixAccelStrategy#FUSED_VECTOR].
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
            return prefixLiteralSet != null ? PrefixAccelStrategy.FUSED_LITERAL_SET : PrefixAccelStrategy.FUSED_VECTOR;
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

    /// Returns the offset of the first of the two folded prefix bytes that the folded pair scans
    /// test, chosen by [ByteFrequencies#foldedProbePair].
    int prefixAccelFoldCaseFirstOffset()
    {
        return prefixFoldCaseFirstOffset;
    }

    /// Returns the offset of the second of the two folded prefix bytes that the folded pair
    /// scans test.
    int prefixAccelFoldCaseSecondOffset()
    {
        return prefixFoldCaseSecondOffset;
    }

    /// Returns whether the folded vector scan takes [VectorPrefixScanner#findFoldCaseDensePair]
    /// rather than [VectorPrefixScanner#findFoldCasePair], as [#usesDenseFoldCaseScan] chose it
    /// for this JVM's vectors. It is false without the Vector API.
    boolean prefixAccelFoldCaseDense()
    {
        return prefixFoldCaseDense;
    }

    /// Returns whether a folded vector scan whose probe pair occurs at a position with
    /// `pairProbability`, as [ByteFrequencies#foldedProbePairProbability] estimates it, should
    /// verify candidates inside its vector loop, given vectors of `lanes` bytes. The predicted
    /// share of vectors that hold a candidate is `1 - (1 - pairProbability)^lanes`. At
    /// [#DENSE_FOLD_CASE_VECTOR_SHARE] or more, the counted test loop of
    /// [VectorPrefixScanner#findFoldCase] would leave and re-enter on so many vectors that the
    /// verify-in-loop [VectorPrefixScanner#findFoldCaseDense] costs less. The rule is the same
    /// on every platform; the lane count is its hardware input.
    static boolean usesDenseFoldCaseScan(double pairProbability, int lanes)
    {
        return 1 - Math.pow(1 - pairProbability, lanes) >= DENSE_FOLD_CASE_VECTOR_SHARE;
    }

    /// Returns the probe pair of the folded pair kernels' scan for the first `size` bytes of
    /// `bytes`, which [ByteFrequencies#foldedProbePair] chooses under the caller's
    /// `byteFrequencies`, or under English text in place of the default uniform one, and whether
    /// the dense kernel scans it, as [#usesDenseFoldCaseScan] decides from how many vectors the
    /// same distribution predicts hold a candidate. The folded prefix scan and the folded inner
    /// literal search both choose here.
    static FoldedPairScan foldedPairScan(byte[] bytes, int size, ByteFrequencies byteFrequencies)
    {
        ByteFrequencies pairFrequencies = byteFrequencies.forFoldedPairs();
        ByteFrequencies.FoldedProbePair pair = pairFrequencies.foldedProbePair(bytes, size);
        boolean dense = VectorSupport.isAvailable() &&
                usesDenseFoldCaseScan(pairFrequencies.foldedProbePairProbability(bytes, pair), VectorSupport.preferredByteLanes());
        return new FoldedPairScan(pair.firstOffset(), pair.secondOffset(), dense);
    }

    /// Returns the folded bytes the prefix scan searches for, a prefix or a run behind a lead.
    byte[] prefixAccelFoldCaseBytesForDiagnostics()
    {
        return prefixFoldCase ? Arrays.copyOf(prefix, prefixSize) : null;
    }

    /// Returns the fewest bytes a match takes before the folded run the prefix scan searches for,
    /// 0 for a prefix.
    int prefixAccelMinimumLead()
    {
        return prefixMinimumLead;
    }

    /// Returns the most bytes a match takes before the folded run the prefix scan searches for,
    /// 0 for a prefix.
    int prefixAccelMaximumLead()
    {
        return prefixMaximumLead;
    }

    /// Returns whether the prefix scan searches for a folded run that every match starts with,
    /// configured because the program has no folded prefix. A first-match DFA whose start bytes
    /// carry a start literal filter keeps its start-byte tables beside such a run, and every other
    /// DFA keeps and charges none.
    boolean prefixRunStartsEveryMatch()
    {
        return prefixRunStartsEveryMatch;
    }

    /// Returns whether the prefix scan searches for a folded run that stands in for no prefix,
    /// configured because the program has no folded prefix or an empty one. The long-window
    /// required-literal check, which a program with a prefix scan takes only for a literal beside
    /// that scan, decides for such a program as for the program without the run: on a window
    /// without the literal, its one literal scan returns where the run's scan and the DFA would
    /// read the window.
    boolean prefixRunAddsPrefixScan()
    {
        return prefixRunAddsPrefixScan;
    }

    /// Returns whether the start bytes of the program without its folded run would carry a start
    /// literal filter that groups of one bit give, as
    /// [Dfa.DfaInstance#givesOneBitStartLiteralFilter] decides for the first-match DFA, or false
    /// unless [#prefixRunAddsPrefixScan]. A run behind a lead takes the place of the start-byte
    /// tables, so the long-window required-literal check, which counts only such a filter, reads
    /// this rather than the tables, which the DFA no longer builds.
    boolean prefixRunHidesOneBitStartLiteralFilter()
    {
        return prefixRunHidesOneBitStartLiteralFilter;
    }

    /// Whether the prefix scan takes the place of the start-byte tables, so the DFA builds none.
    /// Every prefix scan does except a folded run that every match starts with, beside which a
    /// first-match DFA whose start bytes carry a start literal filter keeps its start-byte tables:
    /// the tables' candidates are then match starts, which a find loop can verify without the
    /// reverse pass that a match found from the run needs to find its start.
    private boolean prefixAccelReplacesStartBytes()
    {
        return canPrefixAccel() && !prefixRunStartsEveryMatch;
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

    /// Returns the memory of the shift table [#configurePrefixAccel] keeps for `prefix`, which it
    /// takes from what the program's flatten charge leaves.
    static long prefixShiftsMemory(Slice prefix, boolean foldCase)
    {
        if (foldCase || prefix.length() <= 1) {
            return 0;
        }
        return LiteralSearchKernel.prefixShiftsRetainedSize(LiteralSearchKernel.computePrefixShifts(prefix.getBytes()));
    }

    /// Retains analysis-owned immutable bytes instead of copying a large literal again.
    void configurePrefixAccelShared(Slice prefix, boolean foldCase, ByteFrequencies byteFrequencies)
    {
        if (prefix.byteArrayOffset() != 0 || prefix.length() != prefix.byteArray().length) {
            throw new IllegalArgumentException("shared prefix must cover its complete backing array");
        }
        configurePrefixAccelBytes(prefix.byteArray(), foldCase, byteFrequencies);
    }

    /// Scans for a folded run of three to nine bytes that every match holds after a lead of
    /// `minimumLead` through `maximumLead` bytes, as [Regexp#foldedRunsForAccel] finds it. A
    /// match can start no earlier than `maximumLead` bytes before the first run at or after
    /// `minimumLead` bytes into the search, so [#prefixAccel] returns that position.
    /// `addsPrefixScan` tells whether the run stands in for no prefix, on a program with no
    /// prefix scan yet; see [#prefixRunAddsPrefixScan].
    void configureFoldCasePrefixRun(byte[] run, int minimumLead, int maximumLead, boolean addsPrefixScan, ByteFrequencies byteFrequencies)
    {
        if (minimumLead < 0 || maximumLead < minimumLead) {
            throw new IllegalArgumentException("folded run lead must satisfy 0 <= minimum <= maximum: [" + minimumLead + "," + maximumLead + "]");
        }
        // Decided before the run is configured, while the program still builds its start bytes.
        boolean hidesOneBitStartLiteralFilter = addsPrefixScan && Dfa.DfaInstance.givesOneBitStartLiteralFilter(this, Dfa.DfaInstance.Kind.FIRST_MATCH);
        configurePrefixAccelBytes(run, true, byteFrequencies);
        this.prefixMinimumLead = minimumLead;
        this.prefixMaximumLead = maximumLead;
        // A run behind no lead is chosen only when there is no folded prefix; see
        // Compiler.foldedRunForAccel.
        this.prefixRunStartsEveryMatch = maximumLead == 0;
        this.prefixRunAddsPrefixScan = addsPrefixScan;
        this.prefixRunHidesOneBitStartLiteralFilter = hidesOneBitStartLiteralFilter;
    }

    private void configurePrefixAccelBytes(byte[] bytes, boolean foldCase, ByteFrequencies byteFrequencies)
    {
        this.prefixProbeOffset = 0;
        this.prefixCheckOffset = 0;
        this.prefixProbeBroadcastMask = 0;
        this.prefixPairOffset = 0;
        this.prefixPairSecondaryOffset = 0;
        this.prefixFoldCaseFirstOffset = 0;
        this.prefixFoldCaseSecondOffset = 0;
        this.prefixFoldCaseDense = false;
        this.prefixFoldCaseEndPair = false;
        this.prefixFoldCaseLowerBytes = 0;
        this.prefixFoldCaseCaseBits = 0;
        this.prefixFoldCaseLastLowerByte = 0;
        this.prefixFoldCaseLastCaseBit = 0;
        this.prefixFoldCaseFirstLowerBroadcast = 0;
        this.prefixFoldCaseFirstCaseBroadcast = 0;
        this.prefixFoldCaseSecondLowerBroadcast = 0;
        this.prefixFoldCaseSecondCaseBroadcast = 0;
        this.prefixMinimumLead = 0;
        this.prefixMaximumLead = 0;
        this.prefixRunStartsEveryMatch = false;
        this.prefixRunAddsPrefixScan = false;
        this.prefixRunHidesOneBitStartLiteralFilter = false;
        this.prefixShifts = null;
        this.prefixLiteralSet = null;
        this.prefixLiteralSetBackOffset = 0;
        this.dfaMemory += prefixScanCharge;
        this.prefixScanCharge = 0;
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
            FoldedPairScan pair = foldedPairScan(bytes, prefixSize, byteFrequencies);
            this.prefixFoldCaseFirstOffset = pair.firstOffset();
            this.prefixFoldCaseSecondOffset = pair.secondOffset();
            this.prefixFoldCaseEndPair = pair.firstOffset() == 0 && pair.secondOffset() == prefixSize - 1;
            this.prefixFoldCaseDense = pair.dense();
            long lowerBytes = 0;
            long caseBits = 0;
            for (int index = 0; index < Math.min(prefixSize, Long.BYTES); index++) {
                int lowerCase = asciiLower(bytes[index] & 0xFF);
                lowerBytes |= (long) lowerCase << (index * Byte.SIZE);
                caseBits |= (long) foldCaseBit(lowerCase) << (index * Byte.SIZE);
            }
            this.prefixFoldCaseLowerBytes = lowerBytes;
            this.prefixFoldCaseCaseBits = caseBits;
            int lastLowerCase = asciiLower(bytes[prefixSize - 1] & 0xFF);
            this.prefixFoldCaseLastLowerByte = (byte) lastLowerCase;
            this.prefixFoldCaseLastCaseBit = (byte) foldCaseBit(lastLowerCase);
            this.prefixFoldCaseFirstLowerBroadcast = foldCaseLowerBroadcast(bytes[pair.firstOffset()]);
            this.prefixFoldCaseFirstCaseBroadcast = foldCaseBitBroadcast(bytes[pair.firstOffset()]);
            this.prefixFoldCaseSecondLowerBroadcast = foldCaseLowerBroadcast(bytes[pair.secondOffset()]);
            this.prefixFoldCaseSecondCaseBroadcast = foldCaseBitBroadcast(bytes[pair.secondOffset()]);
        }
        else if (bytes.length != 1) {
            this.prefixSize = bytes.length;
            this.prefixFrontBroadcastMask = (bytes[0] & 0xFFL) * 0x0101010101010101L;
            // A non-ASCII first byte keeps the probe at offset 0 and selects the fused strategies,
            // which pair the bytes at prefixPairOffset and prefixPairSecondaryOffset.
            int probeOffset = bytes[0] >= 0 ? byteFrequencies.probeOffset(bytes) : 0;
            int lastOffset = bytes.length - 1;
            this.prefixProbeOffset = probeOffset;
            this.prefixCheckOffset = probeOffset > lastOffset - probeOffset ? 0 : lastOffset;
            this.prefixProbeBroadcastMask = (bytes[probeOffset] & 0xFFL) * 0x0101010101010101L;
            this.prefixShifts = LiteralSearchKernel.computePrefixShifts(bytes);
            int adjacentPairOffset = adjacentPairOffset(bytes, LiteralSearchKernel.smallestPeriod(bytes, prefixShifts));
            this.prefixPairOffset = adjacentPairOffset >= 0 ? adjacentPairOffset : pairOffset(bytes);
            this.prefixPairSecondaryOffset = adjacentPairOffset >= 0 ? adjacentPairOffset + 1 : lastOffset;
            // Like the OnePass table, the retained shifts come out of the DFA allowance. A budgeted
            // compile fails when the shifts do not fit, so the clamp only keeps an unbudgeted
            // program's default allowance from going negative.
            this.prefixScanCharge = Math.min(dfaMemory, prefixScanMemory());
            this.dfaMemory -= prefixScanCharge;
            if (probeOffset == 0 && VectorSupport.isAvailable()) {
                buildPrefixLiteralSet(bytes);
                if (!prefixLiteralSetDeferred) {
                    chargePrefixLiteralSet(this);
                }
            }
        }
        else {
            // For single-byte non-foldCase, use simple memchr-style search
            this.prefixSize = 1;
            this.prefixFrontBroadcastMask = (bytes[0] & 0xFFL) * 0x0101010101010101L;
        }
        updateStartCandidateSkip();
    }

    /// Returns a budgeted program's literal set charge to its DFA allowance and leaves the set
    /// undecided until [#decidePrefixLiteralSet(Prog)], so that neither OnePass nor any plan or
    /// route judges its memory with the set's bytes taken. The set is still built for any prefix
    /// configured meanwhile, but not charged. An unbudgeted program keeps its charge.
    void deferPrefixLiteralSet()
    {
        if (!dfaMemoryBudgeted || prefixLiteralSetDeferred) {
            return;
        }
        this.prefixLiteralSetDeferred = true;
        // A budgeted program keeps the set only where its whole size was charged.
        long setMemory = prefixLiteralSetMemory();
        this.prefixScanCharge -= setMemory;
        this.dfaMemory += setMemory;
    }

    /// Decides the literal set [#deferPrefixLiteralSet()] left undecided against the DFA allowance
    /// of `allowance`, this program or the one that holds its forward DFA budget, after every
    /// other charge to that allowance.
    void decidePrefixLiteralSet(Prog allowance)
    {
        if (!prefixLiteralSetDeferred) {
            return;
        }
        this.prefixLiteralSetDeferred = false;
        chargePrefixLiteralSet(allowance);
    }

    /// Whether the literal set waits for [#decidePrefixLiteralSet(Prog)].
    boolean defersPrefixLiteralSet()
    {
        return prefixLiteralSetDeferred;
    }

    /// Charges the literal set to the DFA allowance of `allowance`. The set only saves work, so it
    /// is decided after every other charge and a budgeted allowance keeps it only where the rest
    /// still leaves the forward DFAs the memory they need to initialize: a budget that compiles
    /// without the Vector API then gives the same OnePass decision, routes, and DFA minimum with it.
    private void chargePrefixLiteralSet(Prog allowance)
    {
        long setMemory = prefixLiteralSetMemory();
        if (setMemory == 0) {
            return;
        }
        if (allowance.dfaMemoryBudgeted && !allowance.leavesDfaMinimum(setMemory)) {
            this.prefixLiteralSet = null;
            this.prefixLiteralSetBackOffset = 0;
            return;
        }
        long charge = Math.min(allowance.dfaMemory, setMemory);
        allowance.dfaMemory -= charge;
        if (allowance == this) {
            this.prefixScanCharge += charge;
        }
    }

    /// Sets [#prefixLiteralSet] for `prefix`, a non-folded prefix of two or more bytes whose probe
    /// stays at its first byte, so that its long searches take the fused pair scan, when the
    /// literals every match begins with run past it and share an exact byte at the same offset
    /// there. A short prefix such as `fn ` in `fn is_\w+|fn as_\w+` is common in text and is
    /// mostly followed by neither alternative, so the pair scan stops at most of its occurrences
    /// and the search resumes past each one. Testing the last byte the literals share, `_` here,
    /// with the prefix's paired byte, and comparing the literals at each candidate, stops only
    /// where an alternative can begin.
    ///
    /// The literals are read from [#walkStartPositionByteSets] over
    /// [StartLiteralFilter#SHALLOW_PATH_POSITIONS] bytes: each path's bytes up to its first position
    /// that accepts more than one byte, so every match begins with one of them. A folded letter
    /// accepts two bytes and ends its path's literal there. The set is kept only when the walk
    /// succeeds and its distinct literals number at most [#MAXIMUM_PREFIX_LITERALS], each starts
    /// with the prefix and runs past it, and some position past the prefix and before the end
    /// of the shortest literal holds one byte in all of them; the last such position is the back
    /// offset. Each literal is no longer than the walk, so the comparisons at a candidate are
    /// bounded, and the scan needs no comparison budget.
    private void buildPrefixLiteralSet(byte[] prefix)
    {
        if (prefix.length >= StartLiteralFilter.SHALLOW_PATH_POSITIONS) {
            return;
        }
        long[][] paths = walkStartPositionByteSets(new int[3 * size() + 1], StartLiteralFilter.SHALLOW_PATH_POSITIONS);
        if (paths == null) {
            return;
        }
        List<byte[]> literals = new ArrayList<>();
        for (long[] path : paths) {
            byte[] literal = pathLiteral(path);
            if (literal.length <= prefix.length || !Arrays.equals(literal, 0, prefix.length, prefix, 0, prefix.length)) {
                return;
            }
            if (literals.stream().noneMatch(existing -> Arrays.equals(existing, literal))) {
                if (literals.size() == MAXIMUM_PREFIX_LITERALS) {
                    return;
                }
                literals.add(literal);
            }
        }
        literals.sort(Comparator.comparingInt(literal -> literal.length));
        byte[] shortest = literals.getFirst();
        for (int offset = shortest.length - 1; offset >= prefix.length; offset--) {
            byte value = shortest[offset];
            int position = offset;
            if (literals.stream().allMatch(literal -> literal[position] == value)) {
                this.prefixLiteralSet = literals.toArray(byte[][]::new);
                this.prefixLiteralSetBackOffset = offset;
                return;
            }
        }
    }

    // The bytes of a path from walkStartPositionByteSets before its first position that accepts
    // other than exactly one byte.
    private static byte[] pathLiteral(long[] path)
    {
        int positionCount = path.length / 4;
        byte[] literal = new byte[positionCount];
        int length = 0;
        while (length < positionCount) {
            int base = length * 4;
            int count = Long.bitCount(path[base]) + Long.bitCount(path[base + 1]) + Long.bitCount(path[base + 2]) + Long.bitCount(path[base + 3]);
            if (count != 1) {
                break;
            }
            int index = 0;
            while (path[base + index] == 0) {
                index++;
            }
            literal[length++] = (byte) (index * 64 + Long.numberOfTrailingZeros(path[base + index]));
        }
        return Arrays.copyOf(literal, length);
    }

    /// Returns an absolute index `p` in `data` such that no match starts in `[offset, p)`, or `-1`
    /// if no match starts at or after `offset`. For a prefix, `p` is the first place the prefix
    /// matches, except under the literal set scan, where it is the first place one of the
    /// literals every match begins with matches, past occurrences of the prefix that begin none
    /// of them. For a folded run behind a lead, it is the first run found less the widest lead,
    /// never before `offset`, and can precede the match: `(?i)Sherlock Holmes` under Unicode
    /// folding scans for ` holme` 8 to 11 bytes in, so on `xxxxxxxxxxSherlock Holmes` it returns
    /// 7, while the match starts at 10. When `p` is past `offset`, a call from any position from
    /// `p` through `p` plus [#prefixAccelMaximumLead] less [#prefixAccelMinimumLead] finds the same
    /// run and returns the position it was given, so a caller walking forward skips those calls.
    ///
    /// Uses different strategies depending on configuration:
    /// - Masks of two folded prefix bytes for folded prefixes, a vector at a time on long windows;
    ///   see [ByteFrequencies#foldedProbePair]
    /// - Fused pair masks for non-folded multi-byte prefixes whose probe stays at the first byte:
    ///   the last byte with the first byte or, when the prefix starts with a multibyte UTF-8
    ///   character that ends before the last byte, with that character's last byte, or two
    ///   adjacent bytes of a periodic prefix, in Vector lanes for long searches and, for a
    ///   non-ASCII first byte, in SWAR words when the search is short or the Vector API is
    ///   unavailable
    /// - A probe-byte scan with a boundary-byte check for other non-folded multi-byte prefixes,
    ///   derived from C++ `Prog::PrefixAccel_FrontAndBack()`; see [ByteFrequencies]
    /// - Simple byte search for single-byte prefix
    /// - On long searches with the Vector API, when the literals every match begins with run
    ///   past the prefix and share a byte at the same offset there, fused pair masks of the
    ///   prefix's paired byte and the last shared byte, with each candidate compared with every
    ///   literal; see [#buildPrefixLiteralSet]. Each returned position begins one of the
    ///   literals, and so the prefix.
    ///
    /// The other non-folded multi-byte scans charge their failed candidates to
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
            if (prefixMaximumLead != 0) {
                return prefixAccelFoldCaseBehindLead(data, offset, length);
            }
            return prefixAccelFoldCase(data, offset, length);
        }

        // For single-byte prefix, just scan for that byte
        if (prefixSize == 1) {
            return indexOf(data, offset, length, prefix[0], prefixFoldCase);
        }

        int end = offset + length;
        PrefixAccelStrategy strategy = prefixAccelStrategy(length);
        if (strategy == PrefixAccelStrategy.FUSED_VECTOR) {
            return prefixAccelFusedVector(data, offset, length);
        }
        if (strategy == PrefixAccelStrategy.FUSED_LITERAL_SET) {
            return prefixAccelLiteralSet(data, offset, length);
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

    // The vector routes are methods of their own, as the folded routes are, so prefixAccel holds
    // only their dispatch and stays within C2's FreqInlineSize, as TestHotMethodSize checks. Each
    // is below MaxInlineSize, so C2 inlines it into prefixAccel whether or not the call is hot.
    private int prefixAccelFusedVector(byte[] data, int offset, int length)
    {
        return finishWithKmp(data, offset + length, VectorPrefixScanner.find(data, offset, length, prefix, prefixPairOffset, prefixPairSecondaryOffset));
    }

    private int prefixAccelLiteralSet(byte[] data, int offset, int length)
    {
        return VectorPrefixScanner.findLiteralSet(data, offset, length, prefixLiteralSet, prefixPairOffset, prefixLiteralSetBackOffset);
    }

    /// Returns the absolute index where [#prefixAccel] continues with KMP; only tests call it.
    /// Returns -1 when the search ends, with or without a match, before it switches to KMP, for a
    /// folded or one-byte prefix, and for the literal set scan, which never switches. It runs the
    /// scans [#prefixAccel] runs, except that the first-byte scan starts in
    /// [#continueFrontAndBack], whose handoff is the same because the scan charges nothing before
    /// its first failed candidate.
    int prefixAccelKmpHandoffForDiagnostics(byte[] data, int offset, int length)
    {
        if (prefixFoldCase || prefixSize <= 1 || length < prefixSize) {
            return -1;
        }
        int found = switch (prefixAccelStrategy(length)) {
            case FUSED_VECTOR -> VectorPrefixScanner.find(data, offset, length, prefix, prefixPairOffset, prefixPairSecondaryOffset);
            case FUSED_LITERAL_SET -> -1;
            case FUSED_SWAR -> prefixAccelNoFoldCaseFrontAndBackSwar(data, offset, length);
            case REPEATED_BYTE -> prefixProbeOffset != 0 ? prefixAccelProbe(data, offset, length) : continueFrontAndBack(data, offset, offset, offset + length);
        };
        return found < -1 ? -2 - found : -1;
    }

    /// Returns the offsets of the prefix bytes the fused pair scans test, in ascending order; only
    /// tests call it.
    int[] fusedProbeOffsetsForDiagnostics()
    {
        return new int[] {prefixPairOffset, prefixPairSecondaryOffset};
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
        long lowBits = this.lowBits;
        long highBits = 0x8080808080808080L;
        int candidateCount = length - prefixSize + 1;
        int wordEnd = offset + (candidateCount / Long.BYTES) * Long.BYTES;
        int primaryOffset = prefixPairOffset;
        int secondaryOffset = prefixPairSecondaryOffset;
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

    // Every match holds the folded run after a lead of prefixMinimumLead through
    // prefixMaximumLead bytes, so a match starting at or after offset puts the run at or after
    // offset + prefixMinimumLead, and the first run found there, at found, rules out every start
    // before found - prefixMaximumLead.
    private int prefixAccelFoldCaseBehindLead(byte[] data, int offset, int length)
    {
        int scanLength = length - prefixMinimumLead;
        if (scanLength < prefixSize) {
            return -1;
        }
        int found = prefixAccelFoldCase(data, offset + prefixMinimumLead, scanLength);
        return found < 0 ? -1 : Math.max(offset, found - prefixMaximumLead);
    }

    private int prefixAccelFoldCase(byte[] data, int offset, int length)
    {
        if (prefixSize == 1) {
            return indexOfFoldCase(data, offset, length, prefix[0]);
        }
        if (usesVectorForFoldCasePrefix(length)) {
            if (prefixFoldCaseDense) {
                return VectorPrefixScanner.findFoldCaseDensePair(data, offset, length, prefix, prefixSize, prefixFoldCaseFirstOffset, prefixFoldCaseSecondOffset);
            }
            return VectorPrefixScanner.findFoldCasePair(data, offset, length, prefix, prefixSize, prefixFoldCaseFirstOffset, prefixFoldCaseSecondOffset);
        }
        if (prefixFoldCaseEndPair) {
            return prefixAccelFoldCaseFrontAndBackSWAR(data, offset, length);
        }
        return findFoldCaseSwar(
                data,
                offset,
                length,
                prefixFoldCaseFirstOffset,
                prefixFoldCaseSecondOffset,
                prefixFoldCaseFirstLowerBroadcast,
                prefixFoldCaseFirstCaseBroadcast,
                prefixFoldCaseSecondLowerBroadcast,
                prefixFoldCaseSecondCaseBroadcast);
    }

    // Short windows whose folded pair is the prefix's first and last bytes; it scans as
    // findFoldCaseSwar does, with fixed front and back loads and the configured pair's
    // broadcasts. Scanning these pairs with findFoldCaseSwar, whose second offset is a
    // variable, moved short log lines on r8i by 0.92x to 1.08x, with user and error lines 1.05x
    // to 1.08x slower and login lines 0.93x.
    int prefixAccelFoldCaseFrontAndBackSWAR(byte[] data, int offset, int length)
    {
        int candidateCount = length - prefixSize + 1;
        int wordEnd = offset + ((candidateCount / Long.BYTES) * Long.BYTES);
        int end = offset + candidateCount;
        int backOffset = prefixSize - 1;
        long firstCaseBroadcast = prefixFoldCaseFirstCaseBroadcast;
        long firstLowerBroadcast = prefixFoldCaseFirstLowerBroadcast;
        long secondCaseBroadcast = prefixFoldCaseSecondCaseBroadcast;
        long secondLowerBroadcast = prefixFoldCaseSecondLowerBroadcast;
        long lowBits = this.lowBits;
        int position = offset;
        int candidateBase = offset;
        long candidates = 0;
        while (true) {
            while (candidates != 0) {
                int candidate = candidateBase + (Long.numberOfTrailingZeros(candidates) >>> 3);
                int index = 0;
                while (index < backOffset && (byte) (data[candidate + index] | (int) (prefixFoldCaseCaseBits >>> (index << 3))) == (byte) (prefixFoldCaseLowerBytes >>> (index << 3))) {
                    index++;
                }
                if (index == backOffset && (data[candidate + backOffset] | prefixFoldCaseLastCaseBit) == prefixFoldCaseLastLowerByte) {
                    return candidate;
                }
                candidates &= candidates - 1;
            }
            for (; position < wordEnd; position += Long.BYTES) {
                long front = ((long) LONG_HANDLE.get(data, position) | firstCaseBroadcast) ^ firstLowerBroadcast;
                long back = ((long) LONG_HANDLE.get(data, position + backOffset) | secondCaseBroadcast) ^ secondLowerBroadcast;
                candidates = ((front - lowBits) & ~front) & ((back - lowBits) & ~back) & 0x8080808080808080L;
                if (candidates != 0) {
                    break;
                }
            }
            candidateBase = position;
            if (position < wordEnd) {
                position += Long.BYTES;
            }
            else if (position < end) {
                candidates = 0x0101010101010101L >>> ((position + Long.BYTES - end) << 3);
                position = end;
            }
            else {
                return -1;
            }
        }
    }

    // Short windows, and JVMs that do not resolve jdk.incubator.vector or whose preferred byte
    // vector holds fewer than 16 or more than 64 bytes. Finds what
    // [VectorPrefixScanner#findFoldCase] finds: the first occurrence of the folded prefix, with
    // candidates that hold both probe bytes, at `firstOffset` and `secondOffset` within the
    // prefix, whose lowered bytes and case bits the four broadcasts hold, as
    // [#foldCaseLowerBroadcast] and [#foldCaseBitBroadcast] make them. Each word of starts yields
    // the starts where both probes' bytes of (word | case bits) ^ lowered bytes are zero; the
    // zero-byte test can also flag a start just above a true one, which the comparison rejects.
    // The few starts after the last word are all candidates.
    //
    // Each SWAR kernel compares a candidate in its own body, from the packed lowered bytes and
    // case bits, with no call. A counted word loop stops at the first word with candidates, and
    // that word's candidates and the starts after the last word share the one comparison, which
    // keeps each kernel inside FreqInlineSize. "Folded Prefix Candidate Scan" in
    // REGULATOR_DECISIONS.md records the slow mode a comparison helper gave.
    int findFoldCaseSwar(byte[] data, int offset, int length, int firstOffset, int secondOffset, long firstLowerBroadcast, long firstCaseBroadcast, long secondLowerBroadcast, long secondCaseBroadcast)
    {
        int candidateCount = length - prefixSize + 1;
        int wordEnd = offset + ((candidateCount / Long.BYTES) * Long.BYTES);
        int end = offset + candidateCount;
        int lastOffset = prefixSize - 1;
        long lowBits = this.lowBits;
        int position = offset;
        int candidateBase = offset;
        long candidates = 0;
        while (true) {
            while (candidates != 0) {
                int candidate = candidateBase + (Long.numberOfTrailingZeros(candidates) >>> 3);
                int index = 0;
                while (index < lastOffset && (byte) (data[candidate + index] | (int) (prefixFoldCaseCaseBits >>> (index << 3))) == (byte) (prefixFoldCaseLowerBytes >>> (index << 3))) {
                    index++;
                }
                if (index == lastOffset && (data[candidate + lastOffset] | prefixFoldCaseLastCaseBit) == prefixFoldCaseLastLowerByte) {
                    return candidate;
                }
                candidates &= candidates - 1;
            }
            for (; position < wordEnd; position += Long.BYTES) {
                long first = ((long) LONG_HANDLE.get(data, position + firstOffset) | firstCaseBroadcast) ^ firstLowerBroadcast;
                long second = ((long) LONG_HANDLE.get(data, position + secondOffset) | secondCaseBroadcast) ^ secondLowerBroadcast;
                candidates = ((first - lowBits) & ~first) & ((second - lowBits) & ~second) & 0x8080808080808080L;
                if (candidates != 0) {
                    break;
                }
            }
            candidateBase = position;
            if (position < wordEnd) {
                position += Long.BYTES;
            }
            else if (position < end) {
                candidates = 0x0101010101010101L >>> ((position + Long.BYTES - end) << 3);
                position = end;
            }
            else {
                return -1;
            }
        }
    }

    /// Returns absolute index or -1 if not found.
    private int indexOf(byte[] data, int offset, int length, byte b, boolean foldCase)
    {
        if (length <= 0) {
            return -1;
        }

        if (foldCase) {
            return indexOfFoldCase(data, offset, length, b);
        }

        // Use pre-computed broadcast mask when searching for the prefix front byte
        long broadcastMask = (prefix != null && b == prefix[0]) ? prefixFrontBroadcastMask : (b & 0xFFL) * 0x0101010101010101L;

        return indexOfSWAR(data, offset, length, broadcastMask);
    }

    /// Finds `b`, or either case of it when it is an ASCII letter: the vector byte-set search where
    /// [#usesVectorForSingleBytePrefix] holds, as [#prefixAccelSingleByteNoFoldCase] does, and SWAR
    /// elsewhere.
    private int indexOfFoldCase(byte[] data, int offset, int length, byte b)
    {
        int lowerCaseByte = asciiLower(b & 0xFF);
        if (lowerCaseByte < 'a' || lowerCaseByte > 'z') {
            if (usesVectorForSingleBytePrefix(length)) {
                return VectorByteSetScanner.find(data, offset, length, (byte) lowerCaseByte, (byte) 0, (byte) 0, 1);
            }
            long broadcastMask = (lowerCaseByte & 0xFFL) * 0x0101010101010101L;
            return indexOfSWAR(data, offset, length, broadcastMask);
        }

        byte lowerCase = (byte) lowerCaseByte;
        byte upperCase = (byte) (lowerCaseByte - ('a' - 'A'));
        if (usesVectorForSingleBytePrefix(length)) {
            return VectorByteSetScanner.find(data, offset, length, lowerCase, upperCase, (byte) 0, 2);
        }
        return indexOfEitherSWAR(data, offset, length, lowerCase, upperCase);
    }

    private int indexOfEitherSWAR(byte[] data, int offset, int length, byte first, byte second)
    {
        long lowBits = this.lowBits;
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
        long loMask = this.lowBits;
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

    private static int asciiLower(int b)
    {
        if ('A' <= b && b <= 'Z') {
            return b + ('a' - 'A');
        }
        return b;
    }

    private static int foldCaseBit(int lowerCase)
    {
        return lowerCase >= 'a' && lowerCase <= 'z' ? 0x20 : 0;
    }

    /// Returns a folded prefix byte with A-Z lowered, in every byte of a long.
    static long foldCaseLowerBroadcast(byte value)
    {
        return asciiLower(value & 0xFF) * 0x0101010101010101L;
    }

    /// Returns a folded prefix byte's case bit, 0x20 for an ASCII letter and 0 otherwise, in
    /// every byte of a long.
    static long foldCaseBitBroadcast(byte value)
    {
        return foldCaseBit(asciiLower(value & 0xFF)) * 0x0101010101010101L;
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

    /// Marks the DFA memory budget as what a positive `maxMemory` left, which the optional prefix
    /// literal set yields to.
    void setDfaMemoryBudgeted()
    {
        this.dfaMemoryBudgeted = true;
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

    /// Returns the program's instruction words, which the capture engines read, once each greedy
    /// ALT_MATCH word carries its greedy bit; see [#instOpcode]. Compilation marks them after
    /// flattening; this marks them for programs flattened elsewhere, as tests do.
    long[] getOrCreateCaptureInstructionWords()
    {
        if (instructionWordsFinished) {
            return words;
        }
        return finishInstructionWords();
    }

    private synchronized long[] finishInstructionWords()
    {
        if (instructionWordsFinished) {
            return words;
        }

        if (!didFlatten) {
            throw new IllegalStateException("program must be flattened before finalizing instruction words");
        }

        if (words.length != instructionCount) {
            words = Arrays.copyOf(words, instructionCount);
        }
        for (int instructionId = 0; instructionId < instructionCount; instructionId++) {
            long word = words[instructionId];
            if (instOpcode(word) == InstOp.ALT_MATCH && greedyAltMatch(word)) {
                words[instructionId] = word | INSTRUCTION_HIGH_BIT;
            }
        }
        instructionWordsFinished = true;
        return words;
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
                long inst = prog.word(id);
                InstOp opcode = instOpcode(inst);
                op[id] = (byte) opcode.ordinal();
                int outValue = instOut(inst);
                out[id] = outValue;
                out1[id] = instOut1(inst);
                hint[id] = instHint(inst);
                lo[id] = instLo(inst);
                hi[id] = instHi(inst);
                empty[id] = instEmpty(inst);
                outLast[id] = (outValue << 1) | (instLast(inst) ? 1 : 0);
                foldCase[id] = instFoldCase(inst);

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

    /// Appends the packed instruction `word` and returns its id.
    public int add(long word)
    {
        if (instructionCount == words.length) {
            words = Arrays.copyOf(words, words.length * 2);
        }
        words[instructionCount] = word;
        return instructionCount++;
    }

    void setInstOut(int instructionId, int out)
    {
        words[instructionId] = withInstOut(words[instructionId], out);
    }

    void setInstOut1(int instructionId, int out1)
    {
        words[instructionId] = withInstOut1(words[instructionId], out1);
    }

    /// Returns the packed word of instruction `instructionId`; see [#instOpcode].
    long word(int instructionId)
    {
        return words[instructionId];
    }

    /// Replaces the packed word of instruction `instructionId`.
    void setWord(int instructionId, long word)
    {
        words[instructionId] = word;
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
            long instruction = words[id];
            if (instOpcode(instruction) == InstOp.BYTE_RANGE) {
                int lo = instLo(instruction);
                int hi = instHi(instruction);
                builder.mark(lo, hi);
                if (instFoldCase(instruction) && lo <= 'z' && hi >= 'a') {
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
                if (!instLast(instruction) &&
                        id + 1 < size() &&
                        instOpcode(words[id + 1]) == InstOp.BYTE_RANGE &&
                        instOut(instruction) == instOut(words[id + 1])) {
                    continue;
                }
                builder.merge();
            }
            else if (instOpcode(instruction) == InstOp.EMPTY_WIDTH) {
                hasEmptyWidthAssertions = true;
                int empty = instEmpty(instruction);
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

        anchoredStartBytes = anchorStart && !reversed ? boundaryBytes() : null;
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
            long instruction = words[instructionId];
            if (instMatches(instruction, byteValue)) {
                nextInstructionIds[nextInstructionCount++] = instOut(instruction);
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

            long instruction = words[instructionId];
            switch (instOpcode(instruction)) {
                case ALT, ALT_MATCH -> {
                    stack = ensureCapacity(stack, stackPointer + 2);
                    stack[stackPointer++] = instOut(instruction);
                    stack[stackPointer++] = instOut1(instruction);
                    if (!instLast(instruction)) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case NOP, CAPTURE -> {
                    stack = ensureCapacity(stack, stackPointer + 1);
                    stack[stackPointer++] = instOut(instruction);
                    if (!instLast(instruction)) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case EMPTY_WIDTH -> {
                    if ((instEmpty(instruction) & emptyFlags) == instEmpty(instruction)) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instOut(instruction);
                    }
                    if (!instLast(instruction)) {
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
                    if (!instLast(instruction)) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case MATCH -> {
                    match = true;
                    if (!instLast(instruction)) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case FAIL -> {
                    if (!instLast(instruction)) {
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

            long instruction = words[instructionId];
            if (instOpcode(instruction) == InstOp.EMPTY_WIDTH) {
                return true;
            }

            // Follow epsilon transitions irrespective of whether EMPTY_WIDTH would pass;
            // we're only interested in whether the instruction is present.
            switch (instOpcode(instruction)) {
                case ALT, ALT_MATCH -> {
                    stack = ensureCapacity(stack, stackPointer + 2);
                    stack[stackPointer++] = instOut(instruction);
                    stack[stackPointer++] = instOut1(instruction);
                }
                case NOP, CAPTURE -> {
                    stack = ensureCapacity(stack, stackPointer + 1);
                    stack[stackPointer++] = instOut(instruction);
                }
                case BYTE_RANGE, MATCH, FAIL, EMPTY_WIDTH -> {
                    // no-op
                }
            }

            if (!instLast(instruction)) {
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

            long instruction = words[instructionId];

            switch (instOpcode(instruction)) {
                case ALT, ALT_MATCH -> {
                    stack = ensureCapacity(stack, stackPointer + 2);
                    stack[stackPointer++] = instOut(instruction);
                    stack[stackPointer++] = instOut1(instruction);
                    if (!instLast(instruction)) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case NOP, CAPTURE -> {
                    stack = ensureCapacity(stack, stackPointer + 1);
                    stack[stackPointer++] = instOut(instruction);
                    if (!instLast(instruction)) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instructionId + 1;
                    }
                }
                case EMPTY_WIDTH -> {
                    // Only "consume" EMPTY_WIDTH if it is satisfied by the initial flags.
                    if ((instEmpty(instruction) & ~initialFlags) == 0) {
                        stack = ensureCapacity(stack, stackPointer + 1);
                        stack[stackPointer++] = instOut(instruction);
                    }
                    else {
                        normalizedInstructionIds = ensureCapacity(normalizedInstructionIds, normalizedInstructionCount + 1);
                        normalizedInstructionIds[normalizedInstructionCount++] = instructionId;
                    }
                    if (!instLast(instruction)) {
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
            long instruction = words[id];
            if (instOpcode(instruction) == InstOp.EMPTY_WIDTH && instEmpty(instruction) == caret) {
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
            long instruction = words[reachable.denseAt(index)];
            addToQueue(reachable, instOut(instruction));
            if (instOpcode(instruction) == InstOp.ALT || instOpcode(instruction) == InstOp.ALT_MATCH) {
                addToQueue(reachable, instOut1(instruction));
            }
        }

        // Forward, the instructions that reach a match without consuming a byte; reversed, the
        // instructions the start reaches without consuming one.
        SparseSet emptyReach = new SparseSet(size() + 1);
        if (reversed) {
            addToQueue(emptyReach, start);
            for (int index = 0; index < emptyReach.size(); index++) {
                long instruction = words[emptyReach.denseAt(index)];
                switch (instOpcode(instruction)) {
                    case ALT, ALT_MATCH -> {
                        addToQueue(emptyReach, instOut(instruction));
                        addToQueue(emptyReach, instOut1(instruction));
                    }
                    case CAPTURE, EMPTY_WIDTH, NOP -> addToQueue(emptyReach, instOut(instruction));
                    case BYTE_RANGE, MATCH, FAIL -> {}
                }
            }
        }
        else {
            // Each reachable instruction adds at most two edges.
            PredecessorLists predecessors = new PredecessorLists(size(), 2 * reachable.size());
            for (int index = 0; index < reachable.size(); index++) {
                int id = reachable.denseAt(index);
                long instruction = words[id];
                switch (instOpcode(instruction)) {
                    case ALT, ALT_MATCH -> {
                        predecessors.add(instOut(instruction), id);
                        predecessors.add(instOut1(instruction), id);
                    }
                    case CAPTURE, EMPTY_WIDTH, NOP -> predecessors.add(instOut(instruction), id);
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
            long instruction = words[id];
            if (instOpcode(instruction) == InstOp.EMPTY_WIDTH && instEmpty(instruction) == caret && !emptyReach.contains(id)) {
                words[id] = withInstPayload(instruction, caret & ~javaLine);
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

            long instruction = words[id];
            if (nopTargets == null && isNopChain(instOut(instruction))) {
                nopTargets = new SparseIntArray(size());
            }
            instruction = withInstOut(instruction, skipNops(instOut(instruction), nopTargets));
            words[id] = instruction;
            addToQueue(queue, instOut(instruction));

            if (instOpcode(instruction) == InstOp.ALT) {
                if (nopTargets == null && isNopChain(instOut1(instruction))) {
                    nopTargets = new SparseIntArray(size());
                }
                instruction = withInstOut1(instruction, skipNops(instOut1(instruction), nopTargets));
                words[id] = instruction;
                addToQueue(queue, instOut1(instruction));
            }
        }

        // Insert ALT_MATCH instructions.
        SparseIntArray matchAnswers = null;
        queue.clear();
        addToQueue(queue, start);
        for (int queueIndex = 0; queueIndex < queue.size(); queueIndex++) {
            int id = queue.denseAt(queueIndex);
            long instruction = words[id];
            addToQueue(queue, instOut(instruction));
            if (instOpcode(instruction) == InstOp.ALT) {
                addToQueue(queue, instOut1(instruction));
            }

            if (instOpcode(instruction) == InstOp.ALT) {
                // Many loops can share one long chain of captures, so answers are remembered,
                // and the loop test comes first so loop-free programs never walk.
                long firstBranch = words[instOut(instruction)];
                long secondBranch = words[instOut1(instruction)];
                int matchBranchId;
                if (instOpcode(firstBranch) == InstOp.BYTE_RANGE && instOut(firstBranch) == id &&
                        instLo(firstBranch) == 0x00 && instHi(firstBranch) == 0xFF) {
                    matchBranchId = instOut1(instruction);
                }
                else if (instOpcode(secondBranch) == InstOp.BYTE_RANGE && instOut(secondBranch) == id &&
                        instLo(secondBranch) == 0x00 && instHi(secondBranch) == 0xFF) {
                    matchBranchId = instOut(instruction);
                }
                else {
                    continue;
                }
                InstOp matchBranchOpcode = instOpcode(words[matchBranchId]);
                if (matchAnswers == null && (matchBranchOpcode == InstOp.CAPTURE || matchBranchOpcode == InstOp.NOP)) {
                    matchAnswers = new SparseIntArray(size());
                }
                if (isMatch(matchBranchId, matchAnswers)) {
                    words[id] = withInstOpcode(instruction, InstOp.ALT_MATCH);
                }
            }
        }
    }

    public void flatten()
    {
        flatten(false, MAX_FLATTENED_INSTRUCTIONS, 0, false, false);
    }

    /// Flattens the program. Throws [RegexpCompileMemoryLimitException], reporting `maxMemory`,
    /// when the flattened program would hold more than `maxInstructionCount` instructions. A
    /// positive `maxMemory` must allow `maxInstructionCount` instructions; where
    /// `measuresRejection`, the exception then measures, when asked, a lower bound on the budget
    /// the flattened program needs. A caller that drops a program that does not fit passes
    /// `false`, so its rejection holds nothing to measure.
    void flatten(boolean skipDominatorPass, int maxInstructionCount, long maxMemory, boolean measuresRejection)
    {
        flatten(skipDominatorPass, maxInstructionCount, maxMemory, measuresRejection, false);
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
        return flatten(skipDominatorPass, MAX_FLATTENED_INSTRUCTIONS, 0, false, true);
    }

    private FlattenStructure flatten(boolean skipDominatorPass, int maxInstructionCount, long maxMemory, boolean measuresRejection, boolean recordStructure)
    {
        if (didFlatten) {
            return null;
        }
        didFlatten = true;

        // The first three passes hold the unflattened words and the scratch beside the lists they
        // emit. The fourth pass and the arrays the program keeps need only the lists and the root
        // map, so the scratch goes with the frame of emitLists, and the unflattened words are
        // dropped before those arrays are built; see Compiler.UNFLATTENED_INSTRUCTION_BYTES.
        EmittedLists emitted = emitLists(skipDominatorPass, maxInstructionCount, maxMemory, measuresRejection, recordStructure);
        words = null;
        InstList flattenedInstructions = emitted.instructions();
        int[] flattenedIndexesByRoot = emitted.flattenedIndexesByRoot();
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
        listCount = emitted.listCount();
        short[] outputLists = null;
        if (listCount <= Short.MAX_VALUE) {
            outputLists = new short[flattenedInstructions.size()];
        }

        // Fourth pass: record output list ids, remap outputs to flattened indexes, count
        // instructions by opcode, and record the capture slot count.
        for (int id = 0; id < flattenedInstructions.size(); id++) {
            long instruction = flattenedInstructions.get(id);
            InstOp opcode = instOpcode(instruction);
            if (outputLists != null) {
                outputLists[id] = switch (opcode) {
                    case BYTE_RANGE, CAPTURE, EMPTY_WIDTH, NOP -> (short) instOut(instruction);
                    case ALT, ALT_MATCH, MATCH, FAIL -> -1;
                };
            }
            if (opcode != InstOp.ALT_MATCH) {
                flattenedInstructions.set(id, withInstOut(instruction, flattenedIndexesByRoot[instOut(instruction)]));
            }
            instCount[opcode.ordinal()]++;
            if (opcode == InstOp.CAPTURE) {
                captureSlotCount = Math.max(captureSlotCount, (instCap(instruction) | 1) + 1);
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
        words = flattenedInstructions.retainedInstructions();
        instructionCount = flattenedInstructions.size();

        this.outputLists = null;
        startList = -1;
        anchoredBitStateTextMaxSize = 0;
        unanchoredBitStateTextMaxSize = 0;
        bitStateJobsPerPosition = 0;
        if (outputLists != null) {
            this.outputLists = outputLists;
            startList = startRootId;

            // The Java server implementation accepts a larger bitmap than upstream for anchored
            // searches, so BitState rather than the NFA captures a longer span. Unanchored
            // searches keep upstream's bound.
            setAnchoredBitStateTextMaxSize(textMaxSizeForBitmap(ANCHORED_BIT_STATE_BITMAP_MAXIMUM_BYTES));
            bitStateJobsPerPosition = BitState.jobsPerPosition(this);
        }

        return emitted.structure();
    }

    /// The lists the first three passes of flattening emit, the map from each list's root id to
    /// its first flattened instruction, the list count, and, when requested, the counts tests read.
    private record EmittedLists(InstList instructions, int[] flattenedIndexesByRoot, int listCount, FlattenStructure structure) {}

    // The first three passes of flattening. Throws RegexpCompileMemoryLimitException as flatten
    // does, leaving the unflattened program intact.
    private EmittedLists emitLists(boolean skipDominatorPass, int maxInstructionCount, long maxMemory, boolean measuresRejection, boolean recordStructure)
    {
        // Scratch structures reused by helper routines to avoid heap churn.
        SparseSet reachable = new SparseSet(size());
        IntStack stack = new IntStack(size());
        Bitmap256 hintSplits = new Bitmap256();
        int[] hintColors = new int[256];
        // First pass: mark successor roots and predecessors.
        SparseIntArray rootIdsByInstruction = new SparseIntArray(size());
        PredecessorLists predecessorLists = markSuccessors(rootIdsByInstruction, reachable, stack);
        int successorRootCount = rootIdsByInstruction.size();
        // Loop roots, for a program that keeps nullable loop priority.
        if (keepsNullableLoopPriority) {
            markLoopRoots(rootIdsByInstruction, successorRootCount, reachable, stack);
        }
        // Second pass: mark dominator roots.
        long dominatorEdgesScanned = 0;
        if (!skipDominatorPass) {
            // Visit the roots marked so far from the highest instruction id down to the FAIL
            // instruction's root, which is never visited. A root this pass marks has a root id past
            // the ones marked so far and is not visited either.
            int markedRootCount = rootIdsByInstruction.size();
            for (int id = size() - 1; id > 0; id--) {
                if (rootIdsByInstruction.hasIndex(id) && rootIdsByInstruction.getExisting(id) < markedRootCount && id != startUnanchored() && id != start()) {
                    dominatorEdgesScanned += markDominator(
                            id, rootIdsByInstruction, predecessorLists, reachable, stack);
                }
            }
        }
        int finalRootCount = rootIdsByInstruction.size();
        // Third pass: emit lists and map each root to its flattened instruction index. The map
        // borrows the predecessor lists' first edges, which hold an int per instruction and so
        // one per root, because the dominator pass is done with them.
        int[] flattenedIndexesByRoot = predecessorLists.borrowFirstEdges();
        InstList flattenedInstructions = new InstList(size(), maxInstructionCount);
        for (int rootIndex = 0; rootIndex < rootIdsByInstruction.size(); rootIndex++) {
            int rootInstructionId = rootIdsByInstruction.denseIndexAt(rootIndex);
            int rootId = rootIdsByInstruction.denseValueAt(rootIndex);
            flattenedIndexesByRoot[rootId] = flattenedInstructions.size();
            if (!emitList(rootInstructionId, rootIdsByInstruction, flattenedInstructions, reachable, stack)) {
                if (maxMemory <= 0 || !measuresRejection) {
                    throw new RegexpCompileMemoryLimitException(maxMemory);
                }
                // The unflattened program is still intact, and only a rejected compile counts it.
                throw new RegexpCompileMemoryLimitException(maxMemory, () -> flattenedSizeExceeded(rootIdsByInstruction, reachable, stack, maxInstructionCount, maxMemory));
            }
            int lastIndex = flattenedInstructions.size() - 1;
            flattenedInstructions.set(lastIndex, flattenedInstructions.get(lastIndex) | INSTRUCTION_LAST_BIT);
            computeHints(
                    flattenedInstructions,
                    flattenedIndexesByRoot[rootId],
                    flattenedInstructions.size(),
                    hintSplits,
                    hintColors);
        }
        return new EmittedLists(flattenedInstructions, flattenedIndexesByRoot, rootIdsByInstruction.size(), recordStructure ? new FlattenStructure(successorRootCount, finalRootCount, dominatorEdgesScanned) : null);
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
    // The text-dependent assertions OnePass evaluates: Rust's, Java's and Trino's word boundaries,
    // and Java's and Trino's $ that also matches before a final line terminator (Java's \Z too).
    // OnePass evaluates each through EmptyOp.contextFlags on the same context BitState and the NFA
    // read, and a one-pass program has a single path, so Java's priority rules cannot pick another
    // match. These assertions do not fit in a condition's six empty-width bits, so a condition
    // that needs one carries ONEPASS_EXTENDED_ASSERTIONS instead, which sends the search to
    // OnePass.satisfyAction or OnePass.satisfyMatch, and those evaluate the full EmptyOp mask from
    // the parallel assertion arrays. In a program with one of them, every match condition also
    // carries its node index above ONEPASS_INDEX_SHIFT, where an action keeps its next node. The
    // bit is never part of ONEPASS_IMPOSSIBLE, so a marked condition never reads as
    // ONEPASS_IMPOSSIBLE.
    private static final int ONEPASS_TEXT_DEPENDENT_ASSERTIONS = EmptyOp.RUST_ASSERTIONS |
            EmptyOp.JAVA_TRINO_WORD_BOUNDARIES |
            EmptyOp.EMPTY_JAVA_END_TEXT_OR_FINAL_TERMINATOR |
            EmptyOp.EMPTY_END_TEXT_OR_FINAL_NEWLINE;
    // Java's $ and \Z and Trino's $, whose one-pass programs' tables yield to the forward DFAs;
    // see isOnePassOnlyForFinalLineEnds.
    private static final int ONEPASS_FINAL_LINE_END_ASSERTIONS = EmptyOp.EMPTY_JAVA_END_TEXT_OR_FINAL_TERMINATOR | EmptyOp.EMPTY_END_TEXT_OR_FINAL_NEWLINE;
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
            switch (instOpcode(words[id])) {
                case BYTE_RANGE -> byteRangeCount++;
                case CAPTURE -> {
                    captureCount++;
                    maximumCaptureSlot = Math.max(maximumCaptureSlot, instCap(words[id]));
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
            long instruction = words[id];
            if (instOpcode(instruction) == InstOp.BYTE_RANGE && nodeById[instOut(instruction)] == 0) {
                nodeById[instOut(instruction)] = 1;
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
                    long instruction = words[id];
                    switch (instOpcode(instruction)) {
                        case ALT_MATCH -> {
                            // Upstream ignores ALT_MATCH's subtle semantics in IsOnePass; we do the same.
                            if (instLast(instruction)) {
                                return false;
                            }
                            if (!addOnePassQueue(workQueue, id + 1)) {
                                return false;
                            }
                            id = id + 1;
                        }

                        case BYTE_RANGE -> {
                            int nextInstructionId = instOut(instruction);
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
                            int lo = instLo(instruction);
                            int hi = instHi(instruction);
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

                            if (instFoldCase(instruction)) {
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

                            if (instLast(instruction)) {
                                id = 0;
                                break;
                            }
                            if (!addOnePassQueue(workQueue, id + 1)) {
                                return false;
                            }
                            id = id + 1;
                        }

                        case CAPTURE, EMPTY_WIDTH, NOP -> {
                            if (!instLast(instruction)) {
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

                            if (instOpcode(instruction) == InstOp.CAPTURE && instCap(instruction) < ONEPASS_MAX_CAP) {
                                condition |= 1 << (ONEPASS_CAP_SHIFT + instCap(instruction));
                            }
                            if (hasExtendedCaptures && instOpcode(instruction) == InstOp.CAPTURE) {
                                captureMask |= 1L << instCap(instruction);
                            }
                            if (instOpcode(instruction) == InstOp.EMPTY_WIDTH) {
                                int empty = instEmpty(instruction);
                                if ((empty & ONEPASS_TEXT_DEPENDENT_ASSERTIONS) == 0) {
                                    condition |= empty;
                                }
                                else {
                                    condition |= (empty & ~ONEPASS_TEXT_DEPENDENT_ASSERTIONS) | ONEPASS_EXTENDED_ASSERTIONS;
                                }
                                assertions |= empty;
                            }

                            if (!addOnePassQueue(workQueue, instOut(instruction))) {
                                return false;
                            }
                            id = instOut(instruction);
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

                            if (instLast(instruction)) {
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
        return instructionId != 0 && instOpcode(words[instructionId]) == InstOp.NOP &&
                instOut(words[instructionId]) != 0 && instOpcode(words[instOut(words[instructionId])]) == InstOp.NOP;
    }

    private int skipNops(int instructionId, SparseIntArray nopTargets)
    {
        int endId = instructionId;
        int targetId;
        while (true) {
            if (endId == 0 || instOpcode(words[endId]) != InstOp.NOP) {
                targetId = endId;
                break;
            }
            if (nopTargets != null && nopTargets.hasIndex(endId)) {
                targetId = nopTargets.getExisting(endId);
                break;
            }
            endId = instOut(words[endId]);
        }
        if (nopTargets != null) {
            for (int id = instructionId; id != endId; id = instOut(words[id])) {
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
            long instruction = words[endId];
            if (instOpcode(instruction) != InstOp.CAPTURE && instOpcode(instruction) != InstOp.NOP) {
                match = instOpcode(instruction) == InstOp.MATCH;
                break;
            }
            if (matchAnswers.hasIndex(endId)) {
                match = matchAnswers.getExisting(endId) != 0;
                break;
            }
            endId = instOut(instruction);
        }
        for (int id = instructionId; id != endId; id = instOut(words[id])) {
            matchAnswers.setNew(id, match ? 1 : 0);
        }
        return match;
    }

    /// Marks successor roots in the root map, and returns the predecessor lists of the
    /// instructions the unanchored start reaches. Those lists hold only the alternations' two
    /// edges each, added in the order the traversal reached the alternations.
    private PredecessorLists markSuccessors(SparseIntArray rootIdsByInstruction, SparseSet reachable, IntStack stack)
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
        int edgeCount = 0;
        while (!stack.isEmpty()) {
            int id = stack.pop();
            while (true) {
                if (reachable.contains(id)) {
                    break;
                }
                reachable.insertNew(id);

                long instruction = words[id];
                switch (instOpcode(instruction)) {
                    case ALT_MATCH, ALT -> {
                        edgeCount += 2;
                        stack.push(instOut1(instruction));
                        id = instOut(instruction);
                    }
                    case BYTE_RANGE, CAPTURE, EMPTY_WIDTH -> {
                        // Mark the out of this instruction as a "root".
                        if (!rootIdsByInstruction.hasIndex(instOut(instruction))) {
                            rootIdsByInstruction.setNew(instOut(instruction), rootIdsByInstruction.size());
                        }
                        id = instOut(instruction);
                    }
                    case NOP -> id = instOut(instruction);
                    case MATCH, FAIL -> {
                        id = 0;
                    }
                }
                if (id == 0) {
                    break;
                }
            }
        }

        // Count the edges first so the arrays are sized exactly. The reachable set holds the
        // instructions in the order the traversal reached them.
        PredecessorLists predecessorLists = new PredecessorLists(size(), edgeCount);
        for (int index = 0; index < reachable.size(); index++) {
            int id = reachable.denseAt(index);
            long instruction = words[id];
            if (instOpcode(instruction) == InstOp.ALT || instOpcode(instruction) == InstOp.ALT_MATCH) {
                // Mark this instruction as a predecessor of each out.
                predecessorLists.add(instOut(instruction), id);
                predecessorLists.add(instOut1(instruction), id);
            }
        }
        return predecessorLists;
    }

    private static final int LOOP_FRAME_COMPONENT_ROOT = 0b100;
    private static final int LOOP_FRAME_OUTPUT = 0b11;
    private static final int LOOP_COMPONENT_COMPLETE = Integer.MAX_VALUE;

    /// Marks as a root each alternation on a cycle of instructions that consume no input, the
    /// loop that a body able to match the empty string compiles to.
    ///
    /// A list is built as if entered fresh, but a search reaches it with the instructions it
    /// already visited on the way. Entering a loop's list from inside an iteration, after an
    /// earlier list started that iteration, visits the iteration's alternation again in the
    /// loop's list, and its lower-priority branch then runs ahead of the loop's own exit: in
    /// `a(?:|b+)*` on `ab`, a search would take `b` before stopping after `a`. With a list for
    /// each such alternation, every copy jumps to the one list, which the search skips once it
    /// has visited it, as the unflattened program skips the alternation. RE2 programs keep
    /// upstream's flattening without these roots.
    ///
    /// Finds the cycles as strongly connected components of the instructions that consume no
    /// input, by Pearce's single-index variant of Tarjan's algorithm, from each root that
    /// [#markSuccessors] marked; every instruction is reachable from one without consuming
    /// input. An instruction is a frame of the search while it is on the search's path and a
    /// stacked member once it is finished but its component is still open, never both, so the
    /// frames and members together fit the work stack's array, which holds one int per
    /// instruction: the frames grow from its start and the members down from its end, as in
    /// Pearce's algorithm. The indexes, one int per instruction, borrow the reachable set's
    /// array, so the pass allocates nothing.
    private void markLoopRoots(SparseIntArray rootIdsByInstruction, int successorRootCount, SparseSet reachable, IntStack stack)
    {
        // An instruction's index until its component is complete, then COMPLETE; zero until
        // the search visits it. The indexes borrow the reachable set's dense array, which holds
        // one int per instruction: markSuccessors is done with the set, and the passes after this
        // one clear it before they use it.
        int[] indexes = reachable.clearAndBorrowDense();
        Arrays.fill(indexes, 0, size(), 0);
        // The frames are pushed at the stack's start, and the instructions whose index a
        // successor lowered, which belong to an open component, at its end.
        int nextIndex = 1;
        stack.clear();
        for (int rootIndex = 0; rootIndex < successorRootCount; rootIndex++) {
            int rootInstructionId = rootIdsByInstruction.denseIndexAt(rootIndex);
            if (indexes[rootInstructionId] != 0) {
                continue;
            }
            indexes[rootInstructionId] = nextIndex++;
            // A frame is the instruction, whether no successor lowered its index, and the next
            // output to follow.
            stack.push(rootInstructionId << 3 | LOOP_FRAME_COMPONENT_ROOT);
            while (!stack.isEmpty()) {
                int frame = stack.pop();
                int id = frame >>> 3;
                boolean componentRoot = (frame & LOOP_FRAME_COMPONENT_ROOT) != 0;
                int output = frame & LOOP_FRAME_OUTPUT;
                if (output > 0) {
                    int previous = epsilonOutput(id, output - 1);
                    if (indexes[previous] < indexes[id]) {
                        indexes[id] = indexes[previous];
                        componentRoot = false;
                    }
                }
                int next = epsilonOutput(id, output);
                if (next >= 0) {
                    stack.push(id << 3 | (componentRoot ? LOOP_FRAME_COMPONENT_ROOT : 0) | (output + 1));
                    if (indexes[next] == 0) {
                        indexes[next] = nextIndex++;
                        stack.push(next << 3 | LOOP_FRAME_COMPONENT_ROOT);
                    }
                    continue;
                }
                if (!componentRoot) {
                    stack.pushEnd(id);
                    continue;
                }
                boolean cycle = false;
                while (!stack.isEndEmpty() && indexes[stack.peekEnd()] >= indexes[id]) {
                    int member = stack.popEnd();
                    indexes[member] = LOOP_COMPONENT_COMPLETE;
                    markLoopRoot(member, rootIdsByInstruction);
                    cycle = true;
                }
                indexes[id] = LOOP_COMPONENT_COMPLETE;
                if (cycle) {
                    markLoopRoot(id, rootIdsByInstruction);
                }
            }
        }
    }

    /// Returns an instruction's output that a search follows without consuming input, by
    /// position, or -1 past the last.
    private int epsilonOutput(int id, int output)
    {
        long instruction = words[id];
        return switch (instOpcode(instruction)) {
            case ALT, ALT_MATCH -> switch (output) {
                case 0 -> instOut(instruction);
                case 1 -> instOut1(instruction);
                default -> -1;
            };
            case CAPTURE, EMPTY_WIDTH, NOP -> output == 0 ? instOut(instruction) : -1;
            case BYTE_RANGE, MATCH, FAIL -> -1;
        };
    }

    private void markLoopRoot(int id, SparseIntArray rootIdsByInstruction)
    {
        InstOp opcode = instOpcode(words[id]);
        if ((opcode == InstOp.ALT || opcode == InstOp.ALT_MATCH) && !rootIdsByInstruction.hasIndex(id)) {
            rootIdsByInstruction.setNew(id, rootIdsByInstruction.size());
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

                long instruction = words[id];
                switch (instOpcode(instruction)) {
                    case ALT_MATCH, ALT -> {
                        stack.push(instOut1(instruction));
                        id = instOut(instruction);
                    }
                    case BYTE_RANGE, CAPTURE, EMPTY_WIDTH, MATCH, FAIL -> {
                        id = 0;
                    }
                    case NOP -> id = instOut(instruction);
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

    // Returns false when the list does not fit, leaving it partly emitted. countList mirrors
    // this walk to count without emitting; change both together.
    private boolean emitList(int rootInstructionId, SparseIntArray rootIdsByInstruction, InstList flattenedInstructions, SparseSet reachable, IntStack stack)
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
                    if (!flattenedInstructions.add(instWord(InstOp.NOP, rootIdsByInstruction.getExisting(id), 0))) {
                        return false;
                    }
                    break;
                }

                long instruction = words[id];
                switch (instOpcode(instruction)) {
                    case ALT_MATCH -> {
                        // The flattened alt-match leads to the two instructions after it. A list
                        // without room for all three rejects before the word is built, whose
                        // outputs past the last instruction a list may hold need not fit a word.
                        if (!flattenedInstructions.hasRoomFor(3)) {
                            return false;
                        }
                        int next = flattenedInstructions.size() + 1;
                        flattenedInstructions.add(instWord(InstOp.ALT_MATCH, next, next + 1));
                        // fall through to ALT handling
                        stack.push(instOut1(instruction));
                        id = instOut(instruction);
                    }
                    case ALT -> {
                        stack.push(instOut1(instruction));
                        id = instOut(instruction);
                    }
                    case BYTE_RANGE, CAPTURE, EMPTY_WIDTH -> {
                        if (!flattenedInstructions.add(withInstOut(instruction, rootIdsByInstruction.getExisting(instOut(instruction))))) {
                            return false;
                        }
                        id = 0;
                    }
                    case NOP -> id = instOut(instruction);
                    case MATCH, FAIL -> {
                        if (!flattenedInstructions.add(instruction)) {
                            return false;
                        }
                        id = 0;
                    }
                }
                if (id == 0) {
                    break;
                }
            }
        }
        return true;
    }

    /// Returns the exception for a flattened program of more than `maxInstructionCount`
    /// instructions, the limit of a positive `maxMemory`, counting the instructions the third pass
    /// of flattening emits without emitting them. Counting stops at the first list that ends past
    /// 16 times the limit, and the count is capped there, so the budget it reports is a lower
    /// bound. A count past [#MAX_FLATTENED_INSTRUCTIONS] fits no budget.
    private RegexpCompileMemoryLimitException flattenedSizeExceeded(SparseIntArray rootIdsByInstruction, SparseSet reachable, IntStack stack, int maxInstructionCount, long maxMemory)
    {
        long countLimit = Math.min(16L * maxInstructionCount, MAX_FLATTENED_INSTRUCTIONS + 1L);
        long count = 0;
        for (int rootIndex = 0; rootIndex < rootIdsByInstruction.size() && count < countLimit; rootIndex++) {
            count += countList(rootIdsByInstruction.denseIndexAt(rootIndex), rootIdsByInstruction, reachable, stack);
        }
        if (count > MAX_FLATTENED_INSTRUCTIONS) {
            return RegexpCompileMemoryLimitException.fitsNoBudget(maxMemory);
        }
        return new RegexpCompileMemoryLimitException(maxMemory, Compiler.flattenedProgramMemory(Math.min(count, countLimit)));
    }

    // Counts the instructions emitList emits for the list, walking it as emitList does.
    private int countList(int rootInstructionId, SparseIntArray rootIdsByInstruction, SparseSet reachable, IntStack stack)
    {
        int count = 0;
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
                    // The NOP that leads to another list.
                    count++;
                    break;
                }
                long instruction = words[id];
                switch (instOpcode(instruction)) {
                    case ALT_MATCH -> {
                        count++;
                        stack.push(instOut1(instruction));
                        id = instOut(instruction);
                    }
                    case ALT -> {
                        stack.push(instOut1(instruction));
                        id = instOut(instruction);
                    }
                    case BYTE_RANGE, CAPTURE, EMPTY_WIDTH, MATCH, FAIL -> {
                        count++;
                        id = 0;
                    }
                    case NOP -> id = instOut(instruction);
                }
                if (id == 0) {
                    break;
                }
            }
        }
        return count;
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
            if (instOpcode(flattenedInstructions.get(id)) != InstOp.BYTE_RANGE) {
                id--;
                continue;
            }
            int runEnd = id + 1;
            int runBegin = id;
            while (runBegin > begin && instOpcode(flattenedInstructions.get(runBegin - 1)) == InstOp.BYTE_RANGE) {
                runBegin--;
            }
            if (runBegin == id) {
                if (runEnd != end) {
                    setHint(flattenedInstructions, id, 1);
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

            long instruction = flattenedInstructions.get(id);
            int lo = instLo(instruction);
            int hi = instHi(instruction);
            first = recolorHints(splits, colors, id, lo, hi, first);
            if (instFoldCase(instruction) && lo <= 'z' && hi >= 'a') {
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
                setHint(flattenedInstructions, id, first - id);
            }
        }
    }

    private static void setHint(InstList flattenedInstructions, int id, int distance)
    {
        flattenedInstructions.set(id, withInstHint(flattenedInstructions.get(id), distance));
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

    /// Growable instruction list used while emitting flattened lists. The flattened size is not
    /// known until emission, so the list grows a block at a time and never copies a block. With
    /// the exact array the program keeps, the list holds each flattened instruction in two slots,
    /// its block's and the kept array's, and leaves less than one block unused, a block being a
    /// sixteenth of the unflattened size and at least 16 slots.
    private static final class InstList
    {
        private final int maxSize;
        private final int blockShift;
        private long[][] blocks = new long[16][];
        private int size;

        InstList(int unflattenedSize, int maxSize)
        {
            this.maxSize = maxSize;
            // The largest power of two at most a sixteenth of the unflattened size, and at least 16.
            blockShift = Math.max(4, 27 - Integer.numberOfLeadingZeros(unflattenedSize));
        }

        // Whether the list can take `count` more instructions.
        boolean hasRoomFor(int count)
        {
            return size <= maxSize - count;
        }

        // Returns false, adding nothing, when the list holds `maxSize` instructions.
        boolean add(long instruction)
        {
            if (size == maxSize) {
                return false;
            }
            int block = size >>> blockShift;
            if (size == block << blockShift) {
                if (block == blocks.length) {
                    blocks = Arrays.copyOf(blocks, block * 2);
                }
                blocks[block] = new long[1 << blockShift];
            }
            blocks[block][size - (block << blockShift)] = instruction;
            size++;
            return true;
        }

        long get(int index)
        {
            int block = index >>> blockShift;
            return blocks[block][index - (block << blockShift)];
        }

        void set(int index, long instruction)
        {
            int block = index >>> blockShift;
            blocks[block][index - (block << blockShift)] = instruction;
        }

        int size()
        {
            return size;
        }

        /// The instructions for the program to keep, in an array of exactly their count.
        long[] retainedInstructions()
        {
            long[] instructions = new long[size];
            int blockSize = 1 << blockShift;
            for (int begin = 0; begin < size; begin += blockSize) {
                System.arraycopy(blocks[begin >>> blockShift], 0, instructions, begin, Math.min(blockSize, size - begin));
            }
            return instructions;
        }
    }

    private static final class IntStack
    {
        private int[] values;
        private int size;
        // A second stack, which grows down from the end of the array.
        private int endSize;

        IntStack(int capacity)
        {
            values = new int[Math.max(1, capacity)];
        }

        void clear()
        {
            size = 0;
            endSize = 0;
        }

        boolean isEmpty()
        {
            return size == 0;
        }

        void push(int v)
        {
            if (size + endSize >= values.length) {
                grow();
            }
            values[size++] = v;
        }

        int pop()
        {
            return values[--size];
        }

        boolean isEndEmpty()
        {
            return endSize == 0;
        }

        void pushEnd(int v)
        {
            if (size + endSize >= values.length) {
                grow();
            }
            endSize++;
            values[values.length - endSize] = v;
        }

        int popEnd()
        {
            return values[values.length - endSize--];
        }

        int peekEnd()
        {
            return values[values.length - endSize];
        }

        private void grow()
        {
            int[] grown = Arrays.copyOf(values, values.length * 2);
            System.arraycopy(values, values.length - endSize, grown, grown.length - endSize, endSize);
            values = grown;
        }
    }

    private static final class PredecessorLists
    {
        private final int[] firstEdges;
        private final int[] predecessorInstructionIds;
        private final int[] nextEdges;
        private int edgeCount;

        /// Holds at most `edgeCapacity` edges, which the caller bounds or counts before adding them.
        PredecessorLists(int instructionCount, int edgeCapacity)
        {
            firstEdges = new int[instructionCount];
            Arrays.fill(firstEdges, -1);
            predecessorInstructionIds = new int[edgeCapacity];
            nextEdges = new int[edgeCapacity];
        }

        void add(int outputInstructionId, int predecessorInstructionId)
        {
            predecessorInstructionIds[edgeCount] = predecessorInstructionId;
            nextEdges[edgeCount] = firstEdges[outputInstructionId];
            firstEdges[outputInstructionId] = edgeCount;
            edgeCount++;
        }

        int firstEdge(int outputInstructionId)
        {
            return firstEdges[outputInstructionId];
        }

        /// Returns the first-edge array, an int per instruction whose contents are unspecified,
        /// for use as scratch. The caller must not use the lists afterwards.
        int[] borrowFirstEdges()
        {
            return firstEdges;
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
            long instruction = words[id];
            builder.append(id).append(". ").append(dumpInst(instruction)).append('\n');

            addToQueue(queue, instOut(instruction));
            if (instOpcode(instruction) == InstOp.ALT || instOpcode(instruction) == InstOp.ALT_MATCH) {
                addToQueue(queue, instOut1(instruction));
            }
        }
        return builder.toString();
    }

    private String flattenedProgToString(int start)
    {
        StringBuilder builder = new StringBuilder();
        for (int id = start; id < size(); id++) {
            long instruction = words[id];
            if (instLast(instruction)) {
                builder.append(id).append(". ").append(dumpInst(instruction)).append('\n');
            }
            else {
                builder.append(id).append("+ ").append(dumpInst(instruction)).append('\n');
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
