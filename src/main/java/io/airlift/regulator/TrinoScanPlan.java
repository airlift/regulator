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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A Trino scan over literals, deterministic runs, and bounded optional captures. This byte executor
 * preserves valid UTF-8 behavior; malformed input has Trino's garbage-in, garbage-out contract.
 * It must not be selected by the RE2 or Java frontends.
 */
final class TrinoScanPlan
{
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(TrinoScanPlan.class);
    private static final int UNSUPPORTED_ATOM_FLAGS = Regexp.FOLD_CASE | Regexp.LATIN1;
    private static final int UNSUPPORTED_FLAGS = UNSUPPORTED_ATOM_FLAGS | Regexp.NON_GREEDY;
    private static final int MAX_RECURSIVE_DEPTH = 32;
    private static final int RETRY_OPERATION_BITS = 6;
    private static final int RETRY_OPERATION_MASK = (1 << RETRY_OPERATION_BITS) - 1;
    private static final int MAX_OPERATIONS = 32;
    private static final int MAX_OPTIONALS = 4;
    private static final int MAX_LITERAL_BYTES = 256;
    static final int SEARCH_MATCHED = -1;
    static final int NO_MATCH = -2;
    // An unanchored search hands off to the ordinary engine once the bytes its failed attempts
    // examined, each measured from the attempt's start, exceed WORK_BUDGET_BYTES plus
    // WORK_BUDGET_FACTOR times the distance searched. Each search then stays linear in its input.
    // Lets ordinary rows absorb a few long failed attempts without paying the ordinary engine's setup.
    private static final int WORK_BUDGET_BYTES = 2048;
    // Bounded retries may reexamine bytes, so failed attempts that never overlap stay within budget.
    private static final int WORK_BUDGET_FACTOR = 4;
    private static final VarHandle SHORT = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.nativeOrder());
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    static {
        // Saved positions occupy two longs; saved operation indices occupy one int.
        // A retry can resume at END, whose index is MAX_OPERATIONS.
        if (MAX_OPTIONALS > 2 * Long.BYTES / Integer.BYTES ||
                MAX_OPTIONALS * RETRY_OPERATION_BITS > Integer.SIZE || MAX_OPERATIONS > RETRY_OPERATION_MASK) {
            throw new IllegalStateException("scan limits exceed retry stack capacity");
        }
    }

    private enum Kind
    {
        LITERAL,
        OPTIONAL,
        DELIMITED_CAPTURE,
        DOT_ALL_TAIL,
        LINE_TAIL,
        END,
        RUN,
        SAVE,
        FORK,
    }

    private enum StartKind
    {
        EMPTY,
        ANCHORED,
        LITERAL,
        UNBOUNDED_RUN,
        UNSUPPORTED,
    }

    private enum EndKind
    {
        EMPTY,
        FINAL_LINE,
        OTHER,
    }

    private final Step[] steps;
    // Indexed by operation. A set entry marks a RUN whose continuation succeeds on every
    // suffix, so a capture-free partial match is proven once the run's minimum is present.
    private final boolean[] booleanTails;
    private final boolean finalLineEnd;
    private final boolean partialMatch;
    private final boolean anchoredStart;
    private final int leadingOperation;
    // Set when an unanchored search starts with a case-sensitive literal of two or more bytes.
    // Such a search checks the whole literal at each first-byte candidate in its own loop, so
    // the candidate loop of every other plan has no whole-literal check.
    private final boolean wholeLiteralCandidates;
    // WORK_BUDGET_BYTES, or a negative value with which every unanchored search hands off
    // before its first attempt. Only tests select the negative value.
    private final int workBudgetBytes;

    private TrinoScanPlan(List<Step> steps, boolean finalLineEnd, boolean partialMatch, boolean anchoredStart, int workBudgetBytes)
    {
        this.steps = steps.toArray(Step[]::new);
        this.booleanTails = partialMatch ? findBooleanTails(this.steps) : new boolean[this.steps.length];
        this.finalLineEnd = finalLineEnd;
        this.partialMatch = partialMatch;
        this.anchoredStart = anchoredStart;
        this.leadingOperation = findLeadingOperation(this.steps);
        Step leading = this.steps[leadingOperation];
        this.wholeLiteralCandidates = !anchoredStart && leading.kind == Kind.LITERAL && leading.literal.length > 1;
        this.workBudgetBytes = workBudgetBytes;
    }

    private static int findLeadingOperation(Step[] steps)
    {
        for (int index = 0; index < steps.length; index++) {
            if (steps[index].kind != Kind.SAVE) {
                return index;
            }
        }
        throw new IllegalStateException("scan plan has only capture saves");
    }

    /**
     * Marks each RUN whose continuation in the final partial operation list is nullable.
     * Nullable means some continuation succeeds on every suffix: literal and delimiter
     * operations can fail, while a skipped optional or fork arm resumes on a nullable path.
     */
    private static boolean[] findBooleanTails(Step[] steps)
    {
        boolean[] nullable = new boolean[steps.length + 1];
        nullable[steps.length] = true;
        boolean[] booleanTails = new boolean[steps.length];
        for (int index = steps.length - 1; index >= 0; index--) {
            Step step = steps[index];
            nullable[index] = switch (step.kind) {
                case LITERAL, DELIMITED_CAPTURE -> false;
                case SAVE, OPTIONAL -> nullable[index + 1];
                case RUN -> step.run.minimum() == 0 && nullable[index + 1];
                case FORK -> nullable[index + 1] || nullable[step.branch.target()];
                case DOT_ALL_TAIL, LINE_TAIL, END -> throw new IllegalStateException("partial scan contains " + step.kind);
            };
            booleanTails[index] = step.kind == Kind.RUN && nullable[index + 1];
        }
        return booleanTails;
    }

    /**
     * Analyzes {@code expression}. A plan built with {@code forceHandoff} hands every unanchored
     * search to the ordinary engine before its first attempt, so tests can run the continuation
     * on every input.
     */
    static TrinoScanPlan analyze(Regexp expression, int captureCount, boolean forceHandoff)
    {
        StartKind startKind = startKind(expression, 0);
        if (startKind != StartKind.ANCHORED && startKind != StartKind.LITERAL && startKind != StartKind.UNBOUNDED_RUN) {
            return null;
        }
        if (startKind == StartKind.UNBOUNDED_RUN && endKind(expression, 0) == EndKind.FINAL_LINE) {
            return null;
        }
        return Builder.analyze(expression, captureCount, forceHandoff ? -1 : WORK_BUDGET_BYTES);
    }

    private static StartKind startKind(Regexp expression, int depth)
    {
        if (depth > MAX_RECURSIVE_DEPTH || (expression.parseFlags() & UNSUPPORTED_FLAGS) != 0) {
            return StartKind.UNSUPPORTED;
        }
        return switch (expression.op()) {
            case EMPTY_MATCH -> StartKind.EMPTY;
            case BEGIN_TEXT -> StartKind.ANCHORED;
            case LITERAL, LITERAL_STRING -> StartKind.LITERAL;
            case STAR, PLUS -> StartKind.UNBOUNDED_RUN;
            case REPEAT -> expression.max() < 0 ? StartKind.UNBOUNDED_RUN : StartKind.UNSUPPORTED;
            case CAPTURE -> startKind(expression.child(0), depth + 1);
            case CONCAT -> {
                StartKind result = StartKind.EMPTY;
                for (Regexp child : expression.children()) {
                    result = startKind(child, depth + 1);
                    if (result != StartKind.EMPTY) {
                        break;
                    }
                }
                yield result;
            }
            default -> StartKind.UNSUPPORTED;
        };
    }

    private static EndKind endKind(Regexp expression, int depth)
    {
        if (depth > MAX_RECURSIVE_DEPTH) {
            return EndKind.OTHER;
        }
        return switch (expression.op()) {
            case EMPTY_MATCH -> EndKind.EMPTY;
            case END_TEXT -> (expression.parseFlags() & Regexp.FINAL_LINE_END) != 0 ? EndKind.FINAL_LINE : EndKind.OTHER;
            case CAPTURE -> endKind(expression.child(0), depth + 1);
            case CONCAT -> {
                EndKind result = EndKind.EMPTY;
                for (int index = expression.childCount() - 1; index >= 0; index--) {
                    result = endKind(expression.child(index), depth + 1);
                    if (result != EndKind.EMPTY) {
                        break;
                    }
                }
                yield result;
            }
            default -> EndKind.OTHER;
        };
    }

    boolean isPartialMatch()
    {
        return partialMatch;
    }

    boolean isAnchoredStart()
    {
        return anchoredStart;
    }

    boolean isLiteralLeading()
    {
        return steps[leadingOperation].kind == Kind.LITERAL;
    }

    long estimatedRetainedSize()
    {
        long size = INSTANCE_SIZE + SizeOf.sizeOf(steps) + SizeOf.sizeOf(booleanTails);
        for (Step step : steps) {
            size += Step.INSTANCE_SIZE + SizeOf.sizeOf(step.literal);
            if (step.run != null) {
                size += step.run.estimatedRetainedSize();
            }
            if (step.branch != null) {
                size += Branch.INSTANCE_SIZE;
            }
        }
        return size;
    }

    int operationCountForDiagnostics()
    {
        return steps.length;
    }

    String operationsForDiagnostics()
    {
        return String.join(",", Arrays.stream(steps)
                .map(step -> step.kind.name())
                .toList());
    }

    String booleanTailOperationsForDiagnostics()
    {
        List<String> operations = new ArrayList<>();
        for (int index = 0; index < steps.length; index++) {
            if (booleanTails[index]) {
                operations.add(index + ":" + steps[index].kind.name());
            }
        }
        return String.join(",", operations);
    }

    /**
     * Returns the number of capture-free attempts at the candidates an unanchored plan selects
     * over the whole input, resuming after each failed attempt where the searches resume. This
     * models candidate traversal alone: it applies no work budget or handoff, so a search may make
     * fewer attempts.
     */
    int candidateAttemptsForDiagnostics(Slice input)
    {
        byte[] bytes = input.byteArray();
        int base = input.byteArrayOffset();
        int end = base + input.length();
        Step leading = steps[leadingOperation];
        int attempts = 0;
        int candidate = base;
        while (candidate < end) {
            if (wholeLiteralCandidates) {
                candidate = findLiteral(bytes, candidate, end, leading.literal);
            }
            else {
                candidate = nextCandidate(bytes, candidate, end, leading);
            }
            if (candidate < 0) {
                break;
            }
            attempts++;
            long result = matchUnanchoredAt(bytes, base, end, candidate, false, null);
            if (result >= 0) {
                break;
            }
            candidate = resumeAfterFailedAttempt(bytes, candidate, end, (int) ~result, leading);
        }
        return attempts;
    }

    /**
     * Returns the position at which one capture-free partial attempt at {@code start} stopped
     * reading, relative to the input, or a negative value when the attempt failed.
     */
    int booleanAttemptEndForDiagnostics(Slice input, int start)
    {
        int offset = input.byteArrayOffset();
        int end = offset + input.length();
        long result = matchUnanchoredAt(input.byteArray(), offset, end, offset + start, false, null);
        return result < 0 ? -1 : (int) result - offset;
    }

    boolean matchEndAnchored(Slice input, int contextStart, int contextEnd, boolean fullMatch, int[] groups)
    {
        byte[] bytes = input.byteArray();
        int base = input.byteArrayOffset() + contextStart;
        int end = input.byteArrayOffset() + contextEnd;
        int cursor = base;
        int operation = 0;
        int retries = 0;
        // Four optional groups need at most four saved positions and four six-bit operation
        // indices. Keep them in locals so boolean matching needs no allocated workspace.
        long retryPositionsLow = 0;
        long retryPositionsHigh = 0;
        int retryOperations = 0;
        while (true) {
            Step step = steps[operation++];
            boolean failed = false;
            switch (step.kind) {
                case LITERAL -> {
                    if (step.matches(bytes, cursor, end)) {
                        cursor += step.literal.length;
                    }
                    else {
                        failed = true;
                    }
                }
                case OPTIONAL -> {
                    if (step.matches(bytes, cursor, end)) {
                        retryPositionsHigh = (retryPositionsHigh << 32) | (retryPositionsLow >>> 32);
                        retryPositionsLow = (retryPositionsLow << 32) | (cursor & 0xFFFF_FFFFL);
                        retryOperations = (retryOperations << RETRY_OPERATION_BITS) | operation;
                        retries++;
                        cursor += step.literal.length;
                    }
                }
                case DELIMITED_CAPTURE -> {
                    int stop = findByte(bytes, cursor, end, step.delimiter);
                    if (stop < 0 || stop - cursor < step.minimum) {
                        failed = true;
                        break;
                    }
                    if (groups != null && step.slot + 1 < groups.length) {
                        groups[step.slot] = cursor - base;
                        groups[step.slot + 1] = stop - base;
                    }
                    cursor = stop + 1;
                }
                case DOT_ALL_TAIL -> {
                    return success(groups, end - base);
                }
                case LINE_TAIL -> {
                    int newline = findByte(bytes, cursor, end, (byte) '\n');
                    if (newline < 0) {
                        return success(groups, end - base);
                    }
                    if (finalLineEnd && !fullMatch && newline == end - 1) {
                        return success(groups, newline - base);
                    }
                    failed = true;
                }
                case END -> {
                    if (cursor == end || (finalLineEnd && !fullMatch && cursor == end - 1 && bytes[cursor] == '\n')) {
                        return success(groups, cursor - base);
                    }
                    failed = true;
                }
                case RUN -> {
                    int stop = step.run.match(bytes, cursor, end);
                    if (stop < 0) {
                        failed = true;
                        break;
                    }
                    if (groups != null && step.slot > 0 && step.slot + 1 < groups.length) {
                        groups[step.slot] = cursor - base;
                        groups[step.slot + 1] = stop - base;
                    }
                    cursor = stop;
                }
                case SAVE -> {
                    if (groups != null && step.slot < groups.length) {
                        groups[step.slot] = cursor - base;
                    }
                }
                case FORK -> {
                    retryPositionsHigh = (retryPositionsHigh << 32) | (retryPositionsLow >>> 32);
                    retryPositionsLow = (retryPositionsLow << 32) | (cursor & 0xFFFF_FFFFL);
                    retryOperations = (retryOperations << RETRY_OPERATION_BITS) | operation;
                    retries++;
                }
            }
            if (failed) {
                if (retries == 0) {
                    if (groups != null) {
                        Arrays.fill(groups, -1);
                    }
                    return false;
                }
                // Captures inside a skipped optional are cleared. Every later capture on a
                // successful path is visited again; enclosing captures retain their start.
                // No group is revisited by repetition, so capture snapshots are unnecessary.
                retries--;
                cursor = (int) retryPositionsLow;
                retryPositionsLow = (retryPositionsLow >>> 32) | (retryPositionsHigh << 32);
                retryPositionsHigh >>>= 32;
                operation = retryOperations & RETRY_OPERATION_MASK;
                retryOperations >>>= RETRY_OPERATION_BITS;
                Branch branch = steps[operation - 1].branch;
                if (branch != null) {
                    operation = branch.target();
                    if (groups != null) {
                        int mask = branch.captureMask();
                        while (mask != 0) {
                            int slot = 2 * (Integer.numberOfTrailingZeros(mask) + 1);
                            if (slot + 1 < groups.length) {
                                groups[slot] = -1;
                                groups[slot + 1] = -1;
                            }
                            mask &= mask - 1;
                        }
                    }
                }
            }
        }
    }

    @SuppressFBWarnings(value = "SF_SWITCH_NO_DEFAULT", justification = "Partial plans have no END or tail, which the constructor checks")
    @SuppressWarnings("MissingCasesInEnumSwitch")
    boolean matchPartial(Slice input, int contextStart, int contextEnd, boolean fullMatch, int[] groups)
    {
        byte[] bytes = input.byteArray();
        int base = input.byteArrayOffset() + contextStart;
        int end = input.byteArrayOffset() + contextEnd;
        int cursor = base;
        int operation = 0;
        int retries = 0;
        // Four optional groups need at most four saved positions and four six-bit operation
        // indices. Keep them in locals so boolean matching needs no allocated workspace.
        long retryPositionsLow = 0;
        long retryPositionsHigh = 0;
        int retryOperations = 0;
        while (true) {
            boolean failed = false;
            if (operation == steps.length) {
                if (!fullMatch || cursor == end) {
                    return success(groups, cursor - base);
                }
                failed = true;
            }
            else {
                Step step = steps[operation++];
                switch (step.kind) {
                    case LITERAL -> {
                        if (step.matches(bytes, cursor, end)) {
                            cursor += step.literal.length;
                        }
                        else {
                            failed = true;
                        }
                    }
                    case OPTIONAL -> {
                        if (step.matches(bytes, cursor, end)) {
                            retryPositionsHigh = (retryPositionsHigh << 32) | (retryPositionsLow >>> 32);
                            retryPositionsLow = (retryPositionsLow << 32) | (cursor & 0xFFFF_FFFFL);
                            retryOperations = (retryOperations << RETRY_OPERATION_BITS) | operation;
                            retries++;
                            cursor += step.literal.length;
                        }
                    }
                    case DELIMITED_CAPTURE -> {
                        int stop = findByte(bytes, cursor, end, step.delimiter);
                        if (stop < 0 || stop - cursor < step.minimum) {
                            failed = true;
                            break;
                        }
                        if (groups != null && step.slot + 1 < groups.length) {
                            groups[step.slot] = cursor - base;
                            groups[step.slot + 1] = stop - base;
                        }
                        cursor = stop + 1;
                    }
                    case RUN -> {
                        if (groups == null && !fullMatch && booleanTails[operation - 1]) {
                            // The continuation cannot fail, so the run's minimum decides this path.
                            if (step.run.matchMinimum(bytes, cursor, end) >= 0) {
                                return true;
                            }
                            failed = true;
                            break;
                        }
                        int stop = step.run.match(bytes, cursor, end);
                        if (stop < 0) {
                            failed = true;
                            break;
                        }
                        if (groups != null && step.slot > 0 && step.slot + 1 < groups.length) {
                            groups[step.slot] = cursor - base;
                            groups[step.slot + 1] = stop - base;
                        }
                        cursor = stop;
                    }
                    case SAVE -> {
                        if (groups != null && step.slot < groups.length) {
                            groups[step.slot] = cursor - base;
                        }
                    }
                    case FORK -> {
                        retryPositionsHigh = (retryPositionsHigh << 32) | (retryPositionsLow >>> 32);
                        retryPositionsLow = (retryPositionsLow << 32) | (cursor & 0xFFFF_FFFFL);
                        retryOperations = (retryOperations << RETRY_OPERATION_BITS) | operation;
                        retries++;
                    }
                }
            }
            if (failed) {
                if (retries == 0) {
                    if (groups != null) {
                        Arrays.fill(groups, -1);
                    }
                    return false;
                }
                // Captures inside a skipped optional are cleared. Every later capture on a
                // successful path is visited again; enclosing captures retain their start.
                // No group is revisited by repetition, so capture snapshots are unnecessary.
                retries--;
                cursor = (int) retryPositionsLow;
                retryPositionsLow = (retryPositionsLow >>> 32) | (retryPositionsHigh << 32);
                retryPositionsHigh >>>= 32;
                operation = retryOperations & RETRY_OPERATION_MASK;
                retryOperations >>>= RETRY_OPERATION_BITS;
                Branch branch = steps[operation - 1].branch;
                if (branch != null) {
                    operation = branch.target();
                    if (groups != null) {
                        int mask = branch.captureMask();
                        while (mask != 0) {
                            int slot = 2 * (Integer.numberOfTrailingZeros(mask) + 1);
                            if (slot + 1 < groups.length) {
                                groups[slot] = -1;
                                groups[slot + 1] = -1;
                            }
                            mask &= mask - 1;
                        }
                    }
                }
            }
        }
    }

    /**
     * Searches for a match that starts in {@code [start, contextEnd)}, or only at {@code start}
     * when {@code anchored}. Returns {@link #SEARCH_MATCHED}, {@link #NO_MATCH}, or the
     * Slice-relative offset where the search must continue on the ordinary engine, with the same
     * context, because its failed attempts exceeded the work budget. No match starts between
     * {@code start} and that offset. Groups are relative to {@code contextStart} and are cleared
     * unless the search matched. The budget belongs to this call, so every search starts afresh.
     */
    int search(Slice input, int contextStart, int contextEnd, int start, boolean anchored, boolean fullMatch, int[] groups)
    {
        if (wholeLiteralCandidates && !anchored) {
            return searchWholeLiteralCandidates(input, contextStart, contextEnd, start, fullMatch, groups);
        }
        byte[] bytes = input.byteArray();
        int offset = input.byteArrayOffset();
        int base = offset + contextStart;
        int end = offset + contextEnd;
        int searchStart = offset + start;
        int candidate = searchStart;
        Step leading = steps[leadingOperation];
        // An anchored search makes one attempt and never hands off.
        long workLimit = anchored ? Long.MAX_VALUE : workBudgetBytes;
        long failedWork = 0;
        while (candidate < end) {
            if (failedWork - WORK_BUDGET_FACTOR * (long) (candidate - searchStart) > workLimit) {
                return handOff(groups, candidate - offset);
            }
            if (!anchored) {
                candidate = nextCandidate(bytes, candidate, end, leading);
                if (candidate < 0) {
                    break;
                }
            }
            long result = matchUnanchoredAt(bytes, base, end, candidate, fullMatch, groups);
            if (result >= 0) {
                return SEARCH_MATCHED;
            }
            if (anchored) {
                break;
            }
            long failure = ~result;
            failedWork += (int) (failure >>> 32) - candidate;
            candidate = resumeAfterFailedAttempt(bytes, candidate, end, (int) failure, leading);
        }
        if (groups != null) {
            Arrays.fill(groups, -1);
        }
        return NO_MATCH;
    }

    /**
     * The unanchored search of {@link #search} for a plan led by a case-sensitive literal of two
     * or more bytes. Each first-byte candidate is checked for the rest of the literal before an
     * attempt, so an attempt starts only where the whole literal occurs. The loop is separate so that the candidate loop of every other plan has no
     * whole-literal check. A literal-led plan has no separate required literal.
     */
    private int searchWholeLiteralCandidates(Slice input, int contextStart, int contextEnd, int start, boolean fullMatch, int[] groups)
    {
        byte[] bytes = input.byteArray();
        int offset = input.byteArrayOffset();
        int base = offset + contextStart;
        int end = offset + contextEnd;
        int searchStart = offset + start;
        int candidate = searchStart;
        Step leading = steps[leadingOperation];
        long failedWork = 0;
        while (candidate < end) {
            if (failedWork - WORK_BUDGET_FACTOR * (long) (candidate - searchStart) > workBudgetBytes) {
                return handOff(groups, candidate - offset);
            }
            candidate = findLiteral(bytes, candidate, end, leading.literal);
            if (candidate < 0) {
                break;
            }
            long result = matchUnanchoredAt(bytes, base, end, candidate, fullMatch, groups);
            if (result >= 0) {
                return SEARCH_MATCHED;
            }
            long failure = ~result;
            failedWork += (int) (failure >>> 32) - candidate;
            candidate = resumeAfterFailedAttempt(bytes, candidate, end, (int) failure, leading);
        }
        if (groups != null) {
            Arrays.fill(groups, -1);
        }
        return NO_MATCH;
    }

    /**
     * Ends a search that continues on the ordinary engine at {@code resume}, leaving the groups
     * as a search without a match does.
     */
    private static int handOff(int[] groups, int resume)
    {
        if (groups != null) {
            Arrays.fill(groups, -1);
        }
        return resume;
    }

    /**
     * Returns the first position at or after {@code candidate} where the leading operation can
     * match, or {@code -1} when none remains. A leading run is attempted at every position.
     */
    private static int nextCandidate(byte[] bytes, int candidate, int end, Step leading)
    {
        if (leading.kind == Kind.LITERAL) {
            return findByte(bytes, candidate, end, leading.literal[0]);
        }
        return candidate;
    }

    /**
     * Returns the first position at or after {@code candidate} where the whole {@code literal}
     * occurs, or {@code -1} when none remains. A literal that does not fit before {@code end} is
     * never a candidate, so no byte at or past {@code end} is read.
     */
    private static int findLiteral(byte[] bytes, int candidate, int end, byte[] literal)
    {
        // A start after this limit leaves too few bytes for the literal.
        int limit = end - literal.length + 1;
        while ((candidate = findByte(bytes, candidate, limit, literal[0])) >= 0) {
            int index = 1;
            while (index < literal.length && bytes[candidate + index] == literal[index]) {
                index++;
            }
            if (index == literal.length) {
                return candidate;
            }
            candidate++;
        }
        return -1;
    }

    private static int resumeAfterFailedAttempt(byte[] bytes, int candidate, int end, int failedRunEnd, Step leading)
    {
        if (failedRunEnd > candidate) {
            return failedRunEnd;
        }
        if (leading.kind == Kind.RUN) {
            // A failed minimum-width check must not retry from a continuation byte of
            // otherwise valid UTF-8. Malformed input retains Trino's unspecified behavior.
            return candidate + Utf8.decodedWidth(Utf8.decode(bytes, candidate, end));
        }
        return candidate + 1;
    }

    // The unanchored executors run only plans without a start anchor, which compaction never
    // rewrites into delimited captures or tails.
    @SuppressFBWarnings(value = "SF_SWITCH_NO_DEFAULT", justification = "Delimited captures and tails occur only in start-anchored plans")
    @SuppressWarnings("MissingCasesInEnumSwitch")
    private long matchUnanchoredAt(byte[] bytes, int base, int end, int matchStart, boolean fullMatch, int[] groups)
    {
        if (groups != null) {
            Arrays.fill(groups, -1);
        }
        int cursor = matchStart;
        int operation = 0;
        int retries = 0;
        int leadingRunEnd = matchStart;
        // The furthest byte any unbounded scan of this attempt reached, across retries.
        int scanned = matchStart;
        long retryPositionsLow = 0;
        long retryPositionsHigh = 0;
        int retryOperations = 0;
        while (true) {
            boolean failed = false;
            if (operation == steps.length) {
                if (partialMatch && (!fullMatch || cursor == end)) {
                    success(groups, matchStart - base, cursor - base);
                    return cursor;
                }
                failed = true;
            }
            else {
                int stepIndex = operation;
                Step step = steps[operation++];
                switch (step.kind) {
                    case LITERAL -> {
                        if (step.matches(bytes, cursor, end)) {
                            cursor += step.literal.length;
                        }
                        else {
                            failed = true;
                        }
                    }
                    case OPTIONAL -> {
                        if (step.matches(bytes, cursor, end)) {
                            retryPositionsHigh = (retryPositionsHigh << 32) | (retryPositionsLow >>> 32);
                            retryPositionsLow = (retryPositionsLow << 32) | (cursor & 0xFFFF_FFFFL);
                            retryOperations = (retryOperations << RETRY_OPERATION_BITS) | operation;
                            retries++;
                            cursor += step.literal.length;
                        }
                    }
                    case END -> {
                        if (cursor == end || (finalLineEnd && !fullMatch && cursor == end - 1 && bytes[cursor] == '\n')) {
                            success(groups, matchStart - base, cursor - base);
                            return cursor;
                        }
                        failed = true;
                    }
                    case RUN -> {
                        if (groups == null && !fullMatch && booleanTails[stepIndex]) {
                            // The continuation cannot fail, so the run's minimum decides this path.
                            // Boolean callers observe only a nonnegative result, not its end.
                            int stop = step.run.matchMinimum(bytes, cursor, end);
                            if (stop >= 0) {
                                return stop;
                            }
                            failed = true;
                            break;
                        }
                        int stop = step.run.match(bytes, cursor, end);
                        if (stop < 0) {
                            failed = true;
                            break;
                        }
                        scanned = Math.max(scanned, stop);
                        if (stepIndex == leadingOperation) {
                            leadingRunEnd = stop;
                        }
                        if (groups != null && step.slot > 0 && step.slot + 1 < groups.length) {
                            groups[step.slot] = cursor - base;
                            groups[step.slot + 1] = stop - base;
                        }
                        cursor = stop;
                    }
                    case SAVE -> {
                        if (groups != null && step.slot < groups.length) {
                            groups[step.slot] = cursor - base;
                        }
                    }
                    case FORK -> {
                        retryPositionsHigh = (retryPositionsHigh << 32) | (retryPositionsLow >>> 32);
                        retryPositionsLow = (retryPositionsLow << 32) | (cursor & 0xFFFF_FFFFL);
                        retryOperations = (retryOperations << RETRY_OPERATION_BITS) | operation;
                        retries++;
                    }
                }
            }
            if (failed) {
                if (retries == 0) {
                    return failedAttempt(leadingRunEnd, scanned);
                }
                retries--;
                cursor = (int) retryPositionsLow;
                retryPositionsLow = (retryPositionsLow >>> 32) | (retryPositionsHigh << 32);
                retryPositionsHigh >>>= 32;
                operation = retryOperations & RETRY_OPERATION_MASK;
                retryOperations >>>= RETRY_OPERATION_BITS;
                Branch branch = steps[operation - 1].branch;
                if (branch != null) {
                    operation = branch.target();
                    if (groups != null) {
                        int mask = branch.captureMask();
                        while (mask != 0) {
                            int slot = 2 * (Integer.numberOfTrailingZeros(mask) + 1);
                            if (slot + 1 < groups.length) {
                                groups[slot] = -1;
                                groups[slot + 1] = -1;
                            }
                            mask &= mask - 1;
                        }
                    }
                }
            }
        }
    }

    /**
     * Encodes a failed unanchored attempt as a negative value: the complement of a long holding
     * the furthest position any of its unbounded scans reached in the high word and the run end
     * where the next attempt may start in the low word. A successful attempt returns its
     * nonnegative end.
     * The search loop measures its work from the first and resumes from the second.
     */
    private static long failedAttempt(int resumeRunEnd, int scanned)
    {
        return ~(((long) scanned << 32) | resumeRunEnd);
    }

    private static boolean success(int[] groups, int end)
    {
        if (groups != null) {
            groups[0] = 0;
            groups[1] = end;
        }
        return true;
    }

    private static void success(int[] groups, int start, int end)
    {
        if (groups != null) {
            groups[0] = start;
            groups[1] = end;
        }
    }

    private static int findByte(byte[] bytes, int cursor, int end, byte target)
    {
        if (VectorSupport.isAvailable()) {
            return VectorTrinoScanner.findByte(bytes, cursor, end, target);
        }
        for (; cursor < end; cursor++) {
            if (bytes[cursor] == target) {
                return cursor;
            }
        }
        return -1;
    }

    private record Branch(int target, int captureMask)
    {
        private static final int INSTANCE_SIZE = SizeOf.instanceSize(Branch.class);
    }

    /**
     * Lowers an expression to scan operations. A start-anchored plan with at most three simple
     * captured runs and no forks is then compacted into delimited captures and tails.
     */
    private static final class Builder
    {
        private static final int MAX_EMIT_DEPTH = 16;
        private static final int FIRST_BYTE_WORDS = 4;
        private static final int MAX_GROUPS = 16;
        private static final int MAX_ANALYSIS_OPERATIONS = MAX_OPERATIONS + MAX_GROUPS;

        private final List<Step> steps = new ArrayList<>();
        private int captures;
        private int optionals;

        private static TrinoScanPlan analyze(Regexp expression, int captureCount, int workBudgetBytes)
        {
            if (captureCount > MAX_GROUPS) {
                return null;
            }
            List<Regexp> sequence = new ArrayList<>();
            if (!flatten(expression, sequence, 0) || sequence.isEmpty()) {
                return null;
            }
            boolean anchoredStart = sequence.getFirst().op() == RegexpOp.BEGIN_TEXT;
            boolean anchoredEnd = sequence.getLast().op() == RegexpOp.END_TEXT;
            int bodyStart = anchoredStart ? 1 : 0;
            int bodyEnd = anchoredEnd ? sequence.size() - 1 : sequence.size();
            if (bodyStart >= bodyEnd) {
                return null;
            }
            Builder builder = new Builder();
            for (int index = bodyStart; index < bodyEnd; index++) {
                if (!builder.emit(sequence.get(index), 0)) {
                    return null;
                }
            }
            // Literal-only expressions already have cheaper direct prefix and equality routes.
            if (captureCount == 0 && !builder.hasRun()) {
                return null;
            }
            if (builder.captures != (1 << captureCount) - 1) {
                return null;
            }
            builder.steps.add(Step.end());
            if (!builder.verifyContinuations()) {
                return null;
            }
            if (anchoredStart) {
                builder.compact(captureCount, anchoredEnd);
            }
            // MAX_OPERATIONS counts executable operations. END may occupy the
            // following slot, which the retry stack explicitly supports.
            if (builder.steps.size() > MAX_OPERATIONS + 1) {
                return null;
            }
            if (!anchoredEnd) {
                builder.steps.removeLast();
            }
            if (!anchoredStart && (captureCount == 0 || !builder.hasVariableRun() || !builder.canSearchUnanchored())) {
                return null;
            }
            return new TrinoScanPlan(
                    builder.steps,
                    anchoredEnd && (sequence.getLast().parseFlags() & Regexp.FINAL_LINE_END) != 0,
                    !anchoredEnd,
                    anchoredStart,
                    workBudgetBytes);
        }

        private static boolean flatten(Regexp expression, List<Regexp> sequence, int depth)
        {
            if (depth > MAX_RECURSIVE_DEPTH || sequence.size() > 2 * MAX_OPERATIONS ||
                    (expression.parseFlags() & UNSUPPORTED_FLAGS) != 0) {
                return false;
            }
            if (expression.op() == RegexpOp.CONCAT) {
                for (Regexp child : expression.children()) {
                    if (!flatten(child, sequence, depth + 1)) {
                        return false;
                    }
                }
            }
            else if (expression.op() != RegexpOp.EMPTY_MATCH) {
                sequence.add(expression);
            }
            return true;
        }

        /**
         * Returns whether an unanchored search may attempt a match at each candidate of the
         * leading operation, past capture saves. That operation must be a literal. After a
         * leading run, the number of attempts that fail depends on the input, since each
         * candidate inside one run rescans the rest of it, so the ordinary engine keeps those
         * patterns. The exception is an unbounded run of every byte except the one-byte
         * case-sensitive literal that follows it: the run stops only at that byte, which the
         * literal then matches.
         */
        private boolean canSearchUnanchored()
        {
            for (int index = 0; index < steps.size(); index++) {
                Step step = steps.get(index);
                if (step.kind == Kind.SAVE) {
                    continue;
                }
                if (step.kind == Kind.LITERAL) {
                    return true;
                }
                if (step.kind != Kind.RUN) {
                    return false;
                }
                return step.run.isUnbounded() && stopsOnlyAtFollowingByte(step.run, index + 1);
            }
            return false;
        }

        private boolean stopsOnlyAtFollowingByte(TrinoScanPlanRun run, int start)
        {
            for (int index = start; index < steps.size(); index++) {
                Step step = steps.get(index);
                if (step.kind == Kind.SAVE) {
                    continue;
                }
                return step.kind == Kind.LITERAL && step.literal.length == 1 && run.isComplementOf(step.literal[0]);
            }
            return false;
        }

        private boolean emit(Regexp expression, int depth)
        {
            if (depth > MAX_EMIT_DEPTH || steps.size() >= MAX_ANALYSIS_OPERATIONS ||
                    (expression.parseFlags() & UNSUPPORTED_FLAGS) != 0) {
                return false;
            }
            switch (expression.op()) {
                case EMPTY_MATCH -> {}
                case CONCAT -> {
                    for (Regexp child : expression.children()) {
                        if (!emit(child, depth + 1)) {
                            return false;
                        }
                    }
                }
                case LITERAL, LITERAL_STRING -> {
                    byte[] literal = literalBytes(expression);
                    if (literal == null) {
                        return false;
                    }
                    steps.add(Step.literal(literal));
                }
                case CAPTURE -> {
                    int group = expression.captureIndex();
                    if (group < 1 || group > MAX_GROUPS || (captures & (1 << (group - 1))) != 0) {
                        return false;
                    }
                    captures |= 1 << (group - 1);
                    Regexp body = expression.child(0);
                    steps.add(Step.save(2 * group));
                    if (!emit(body, depth + 1)) {
                        return false;
                    }
                    steps.add(Step.save(2 * group + 1));
                }
                case QUEST -> {
                    if (!emitOptional(expression, depth)) {
                        return false;
                    }
                }
                case REPEAT -> {
                    if (expression.min() == 0 && expression.max() == 1) {
                        if (!emitOptional(expression, depth)) {
                            return false;
                        }
                    }
                    else if (!emitRun(expression)) {
                        return false;
                    }
                }
                case STAR, PLUS, CHAR_CLASS, ANY_CHAR -> {
                    if (!emitRun(expression)) {
                        return false;
                    }
                }
                default -> {
                    return false;
                }
            }
            return steps.size() <= MAX_ANALYSIS_OPERATIONS;
        }

        private static byte[] literalBytes(Regexp expression)
        {
            if ((expression.parseFlags() & UNSUPPORTED_ATOM_FLAGS) != 0 ||
                    (expression.op() != RegexpOp.LITERAL && expression.op() != RegexpOp.LITERAL_STRING)) {
                return null;
            }
            int[] runes = expression.op() == RegexpOp.LITERAL ? new int[] {expression.rune()} : expression.runes();
            if (runes.length == 0 || runes.length > MAX_LITERAL_BYTES) {
                return null;
            }
            int length = 0;
            for (int rune : runes) {
                if (rune >= 0xD800 && rune <= 0xDFFF) {
                    return null;
                }
                length += Utf8.encodedLength(rune);
            }
            if (length > MAX_LITERAL_BYTES) {
                return null;
            }
            byte[] bytes = new byte[length];
            int offset = 0;
            for (int rune : runes) {
                Utf8.encode(bytes, offset, rune);
                offset += Utf8.encodedLength(rune);
            }
            return bytes;
        }

        private boolean hasRun()
        {
            for (Step step : steps) {
                if (step.kind == Kind.RUN) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasVariableRun()
        {
            for (Step step : steps) {
                if (step.kind == Kind.RUN && step.run.isVariable()) {
                    return true;
                }
            }
            return false;
        }

        private boolean emitRun(Regexp expression)
        {
            TrinoScanPlanRun run = TrinoScanPlanRun.analyze(expression);
            if (run == null) {
                return false;
            }
            steps.add(Step.run(0, run));
            return true;
        }

        private boolean emitOptional(Regexp expression, int depth)
        {
            Regexp body = expression.child(0);
            byte[] literal = literalBytes(body);
            if (literal != null) {
                if (++optionals > MAX_OPTIONALS) {
                    return false;
                }
                steps.add(Step.optionalLiteral(literal));
                return true;
            }
            // A top-level optional character atom is lowered to one bounded run
            // without a retry checkpoint.
            if (depth == 0) {
                TrinoScanPlanRun run = TrinoScanPlanRun.analyze(expression);
                if (run != null) {
                    steps.add(Step.run(0, run));
                    return true;
                }
            }
            if (++optionals > MAX_OPTIONALS) {
                return false;
            }
            int fork = steps.size();
            int previousCaptures = captures;
            steps.add(Step.fork(0, 0));
            if (!emit(body, depth + 1)) {
                return false;
            }
            steps.set(fork, Step.fork(steps.size(), captures & ~previousCaptures));
            return true;
        }

        private boolean verifyContinuations()
        {
            // The graph only moves forward. Propagate possible first bytes through capture
            // boundaries and both arms of every optional, then check each variable run against
            // its complete continuation. Unicode membership is uniform for all admitted runs.
            long[] first = new long[steps.size() * FIRST_BYTE_WORDS];
            boolean[] nullable = new boolean[steps.size()];
            for (int index = steps.size() - 1; index >= 0; index--) {
                Step step = steps.get(index);
                switch (step.kind) {
                    case END -> nullable[index] = true;
                    case SAVE -> {
                        merge(first, index, index + 1);
                        nullable[index] = nullable[index + 1];
                    }
                    case LITERAL -> add(first, index, step.literal[0] & 0xFF);
                    case OPTIONAL -> {
                        merge(first, index, index + 1);
                        add(first, index, step.literal[0] & 0xFF);
                        nullable[index] = nullable[index + 1];
                    }
                    case FORK -> {
                        merge(first, index, index + 1);
                        merge(first, index, step.branch.target());
                        nullable[index] = nullable[index + 1] || nullable[step.branch.target()];
                    }
                    case RUN -> {
                        if (!step.run.canStopBefore(first, (index + 1) * FIRST_BYTE_WORDS)) {
                            return false;
                        }
                        step.run.addFirstBytes(first, index * FIRST_BYTE_WORDS);
                        if (step.run.minimum() == 0) {
                            merge(first, index, index + 1);
                            nullable[index] = nullable[index + 1];
                        }
                    }
                    default -> throw new IllegalStateException("unexpected builder operation: " + step.kind);
                }
            }
            return !nullable[0];
        }

        private static void add(long[] sets, int set, int value)
        {
            sets[set * FIRST_BYTE_WORDS + (value >>> 6)] |= 1L << (value & 63);
        }

        private void compact(int captureCount, boolean anchoredEnd)
        {
            // Compaction applies only to plans with at most three simple captured
            // runs and no forks; richer plans keep the builder representation.
            if (captureCount > 3 || steps.stream().anyMatch(step -> step.kind == Kind.FORK)) {
                return;
            }
            List<Step> compacted = new ArrayList<>(steps.size());
            for (int index = 0; index < steps.size(); index++) {
                Step step = steps.get(index);
                Step following = index + 1 < steps.size() ? steps.get(index + 1) : null;
                if (step.kind == Kind.SAVE) {
                    if (index + 2 >= steps.size() || following.kind != Kind.RUN || following.slot != 0) {
                        return;
                    }
                    Step captureEnd = steps.get(index + 2);
                    if (captureEnd.kind != Kind.SAVE || captureEnd.slot != step.slot + 1) {
                        return;
                    }
                    Step afterCapture = index + 3 < steps.size() ? steps.get(index + 3) : null;
                    int terminator = following.run.unboundedScanTerminator();
                    if (terminator >= 0 && terminator < TrinoScanPlanRun.MATCHES_EVERY_BYTE && afterCapture != null && afterCapture.kind == Kind.LITERAL &&
                            (afterCapture.literal[0] & 0xFF) == terminator) {
                        compacted.add(Step.delimitedCapture(terminator, step.slot, following.run.minimum()));
                        if (afterCapture.literal.length > 1) {
                            compacted.add(Step.literal(Arrays.copyOfRange(afterCapture.literal, 1, afterCapture.literal.length)));
                        }
                        index += 3;
                    }
                    else {
                        compacted.add(Step.run(step.slot, following.run));
                        index += 2;
                    }
                    continue;
                }
                if (anchoredEnd && step.kind == Kind.RUN && following != null) {
                    int terminator = step.run.unboundedScanTerminator();
                    if (step.run.minimum() == 0 && following.kind == Kind.END) {
                        if (terminator == TrinoScanPlanRun.MATCHES_EVERY_BYTE) {
                            compacted.add(Step.dotAllTail());
                            continue;
                        }
                        if (terminator == '\n') {
                            compacted.add(Step.lineTail());
                            continue;
                        }
                    }
                }
                compacted.add(step);
            }
            steps.clear();
            steps.addAll(compacted);
        }

        private static void merge(long[] sets, int target, int source)
        {
            int targetOffset = target * FIRST_BYTE_WORDS;
            int sourceOffset = source * FIRST_BYTE_WORDS;
            sets[targetOffset] |= sets[sourceOffset];
            sets[targetOffset + 1] |= sets[sourceOffset + 1];
            sets[targetOffset + 2] |= sets[sourceOffset + 2];
            sets[targetOffset + 3] |= sets[sourceOffset + 3];
        }
    }

    private static final class Step
    {
        private static final int INSTANCE_SIZE = SizeOf.instanceSize(Step.class);

        private final Kind kind;
        private final byte[] literal;
        private final byte delimiter;
        private final int slot;
        private final int minimum;
        private final int width;
        private final int lastOffset;
        private final long first;
        private final long last;
        private final TrinoScanPlanRun run;
        private final Branch branch;

        private static Step literal(byte[] bytes)
        {
            return new Step(Kind.LITERAL, bytes, 0, 0, 0, null, null);
        }

        private static Step optionalLiteral(byte[] bytes)
        {
            return new Step(Kind.OPTIONAL, bytes, 0, 0, 0, null, null);
        }

        private static Step delimitedCapture(int delimiter, int slot, int minimum)
        {
            return new Step(Kind.DELIMITED_CAPTURE, null, delimiter, slot, minimum, null, null);
        }

        private static Step dotAllTail()
        {
            return new Step(Kind.DOT_ALL_TAIL, null, 0, 0, 0, null, null);
        }

        private static Step lineTail()
        {
            return new Step(Kind.LINE_TAIL, null, 0, 0, 0, null, null);
        }

        private static Step end()
        {
            return new Step(Kind.END, null, 0, 0, 0, null, null);
        }

        private static Step run(int slot, TrinoScanPlanRun run)
        {
            return new Step(Kind.RUN, null, 0, slot, 0, run, null);
        }

        private static Step save(int slot)
        {
            return new Step(Kind.SAVE, null, 0, slot, 0, null, null);
        }

        private static Step fork(int target, int captureMask)
        {
            return new Step(Kind.FORK, null, 0, 0, 0, null, new Branch(target, captureMask));
        }

        private Step(Kind kind, byte[] literal, int delimiter, int slot, int minimum, TrinoScanPlanRun run, Branch branch)
        {
            this.kind = kind;
            this.literal = literal;
            this.run = run;
            this.branch = branch;
            this.delimiter = (byte) delimiter;
            this.slot = slot;
            this.minimum = minimum;
            this.width = literal == null ? 0 : Math.min(Integer.highestOneBit(literal.length), Long.BYTES);
            this.lastOffset = literal == null ? 0 : literal.length - width;
            this.first = literal == null ? 0 : read(literal, 0, width);
            this.last = literal == null ? 0 : read(literal, lastOffset, width);
        }

        private boolean matches(byte[] input, int cursor, int end)
        {
            if (literal.length > end - cursor) {
                return false;
            }
            if (literal.length > 2 * Long.BYTES) {
                return Arrays.mismatch(literal, 0, literal.length, input, cursor, cursor + literal.length) < 0;
            }
            return switch (width) {
                case 1 -> input[cursor] == first;
                case 2 -> (((short) SHORT.get(input, cursor) ^ first) | ((short) SHORT.get(input, cursor + lastOffset) ^ last)) == 0;
                case 4 -> (((int) INT.get(input, cursor) ^ first) | ((int) INT.get(input, cursor + lastOffset) ^ last)) == 0;
                case 8 -> (((long) LONG.get(input, cursor) ^ first) | ((long) LONG.get(input, cursor + lastOffset) ^ last)) == 0;
                default -> throw new IllegalStateException("unexpected literal width: " + width);
            };
        }

        private static long read(byte[] bytes, int offset, int width)
        {
            return switch (width) {
                case 1 -> bytes[offset];
                case 2 -> (short) SHORT.get(bytes, offset);
                case 4 -> (int) INT.get(bytes, offset);
                case 8 -> (long) LONG.get(bytes, offset);
                default -> throw new IllegalStateException("unexpected literal width: " + width);
            };
        }
    }
}
