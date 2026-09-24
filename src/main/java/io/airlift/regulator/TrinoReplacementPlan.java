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

import java.util.Arrays;
import java.util.Map;

import static java.lang.Math.addExact;
import static java.lang.Math.multiplyExact;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

final class TrinoReplacementPlan
{
    // A template with group references keeps match records for exact-size output until they fill
    // this many ints, or one record if a record is larger. A denser replacement then writes its
    // recorded matches to an output buffer in chunks and reuses the records, so record storage
    // stays bounded instead of growing with the match count.
    static final int MATCH_RECORD_LIMIT = 4096;
    // Largest replacement whose parsed template a TrinoRegexp retains for its later calls.
    static final int MAXIMUM_CACHED_REPLACEMENT_LENGTH = 256;

    private static final int MALFORMED_REPLACEMENT = -1;
    private static final int GROUP_ZERO_OPERATION = -1;
    // A template that references more distinct groups than this indexes them by group number, so
    // finding each reference's slot stays constant-time without allocating for short templates.
    private static final int LINEAR_GROUP_LOOKUP_LIMIT = 8;
    private static final int MINIMUM_DIRECT_OUTPUT_LENGTH = 64;
    // Parse metadata starts with room for this many operations or groups and doubles as needed,
    // so a long literal template does not reserve metadata for every byte.
    private static final int INITIAL_PARSE_CAPACITY = 4;
    // Same limit as jdk.internal.util.ArraysSupport.SOFT_MAX_ARRAY_LENGTH.
    private static final int MAXIMUM_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

    private final Slice source;
    private final Slice replacement;
    private final int capturingGroupCount;
    private final Map<String, Integer> namedCapturingGroups;
    private final int maximumCapturingGroup;
    // Retains the parsed template for later calls; null when nothing is cached.
    private final TrinoRegexp templateOwner;

    // Parsed on the first match so a call without matches does no template work, and a
    // malformed replacement fails only when a match needs it.
    private ParsedReplacement template;
    private int recordSize;
    private int recordCapacityLimit;

    private int[] matches;
    private int matchCount;
    private long outputLength;

    // A template without group references writes here from its first match; any other template
    // allocates it only after the records first fill. Null means every match is still recorded.
    private byte[] output;
    private int outputPosition;
    private int previousEnd;

    TrinoReplacementPlan(Slice source, Slice replacement, TrinoRegexp templateOwner)
    {
        this(source, replacement, templateOwner.capturingGroupCount(), templateOwner.namedCapturingGroups(), MATCH_RECORD_LIMIT, templateOwner);
    }

    TrinoReplacementPlan(
            Slice source,
            Slice replacement,
            int capturingGroupCount,
            Map<String, Integer> namedCapturingGroups,
            int matchRecordLimit)
    {
        this(source, replacement, capturingGroupCount, namedCapturingGroups, matchRecordLimit, null);
    }

    private TrinoReplacementPlan(
            Slice source,
            Slice replacement,
            int capturingGroupCount,
            Map<String, Integer> namedCapturingGroups,
            int matchRecordLimit,
            TrinoRegexp templateOwner)
    {
        this.source = requireNonNull(source, "source is null");
        this.replacement = requireNonNull(replacement, "replacement is null");
        this.namedCapturingGroups = requireNonNull(namedCapturingGroups, "namedCapturingGroups is null");
        if (matchRecordLimit <= 0) {
            throw new IllegalArgumentException("matchRecordLimit must be positive");
        }
        this.capturingGroupCount = capturingGroupCount;
        this.recordCapacityLimit = matchRecordLimit;
        this.templateOwner = templateOwner;
        this.outputLength = source.length();

        // Replacements are usually constant across rows, so a template parsed by an earlier call
        // skips the pre-scan and the parse. Only a well-formed template is ever cached.
        CachedTemplate cached = templateOwner == null ? null : templateOwner.cachedReplacementTemplate();
        if (cached != null && cached.matches(replacement)) {
            this.maximumCapturingGroup = cached.maximumCapturingGroup;
            setTemplate(cached.template);
        }
        else {
            this.maximumCapturingGroup = uncachedMaximumCapturingGroup(replacement, capturingGroupCount);
        }
    }

    private static int uncachedMaximumCapturingGroup(Slice replacement, int capturingGroupCount)
    {
        // A malformed replacement fails on the first match, and every route finds the same first
        // match, so it takes the group-zero route rather than retaining capturing groups.
        int maximumReferencedGroup = maximumReferencedGroup(replacement, capturingGroupCount);
        return maximumReferencedGroup == MALFORMED_REPLACEMENT ? 0 : maximumReferencedGroup;
    }

    /**
     * Returns the highest capturing group the matcher must retain for this replacement.
     */
    int maximumCapturingGroup()
    {
        return maximumCapturingGroup;
    }

    // The per-match path for a template without group references stays small enough for C2 to
    // inline into the caller's match loop: template parsing, output growth, and invalid-argument
    // failures are out of line.
    void addMatch(Re2Matcher matcher)
    {
        requireNonNull(matcher, "matcher is null");
        ParsedReplacement template = this.template;
        if (template == null) {
            template = parseTemplate();
        }
        if (template.referencedGroups().length == 0) {
            writeMatch(template, matcher.start(), matcher.end());
            return;
        }
        recordMatch(matcher, template);
    }

    private void recordMatch(Re2Matcher matcher, ParsedReplacement template)
    {
        int recordOffset = beginMatch(matcher.start(), matcher.end());
        int[] referencedGroups = template.referencedGroups();
        int[] groupReferenceCounts = template.groupReferenceCounts();
        long replacementLength = addExact(
                template.literalLength(),
                multiplyExact((long) (matcher.end() - matcher.start()), template.groupZeroReferenceCount()));
        for (int index = 0; index < referencedGroups.length; index++) {
            int start = matcher.start(referencedGroups[index]);
            int end = matcher.end(referencedGroups[index]);
            matches[recordOffset + 2 + index * 2] = start;
            matches[recordOffset + 3 + index * 2] = end;
            if (start >= 0) {
                replacementLength = addExact(
                        replacementLength,
                        multiplyExact((long) (end - start), groupReferenceCounts[index]));
            }
        }
        addReplacementLength(recordOffset, replacementLength);
    }

    void addMatch(int start, int end)
    {
        ParsedReplacement template = this.template;
        if (template == null) {
            template = parseTemplate();
        }
        if (template.referencedGroups().length != 0) {
            throw capturingGroupsRequired();
        }
        writeMatch(template, start, end);
    }

    /**
     * Returns a replacer for this plan's replacement, which must not reference capturing groups.
     * The replacer takes over from this plan: a route that finds its own match bounds uses the
     * replacer alone, so none of this plan's match-record state is live across its search loop.
     */
    GroupFreeReplacer groupFreeReplacer()
    {
        if (maximumCapturingGroup != 0) {
            throw capturingGroupsRequired();
        }
        requireNonNull(templateOwner, "templateOwner is null");
        return new GroupFreeReplacer(source, replacement, templateOwner, template);
    }

    private static IllegalStateException capturingGroupsRequired()
    {
        return new IllegalStateException("capturing groups are required");
    }

    /**
     * Writes a match of a template without capturing-group references straight to the output.
     * Such a template needs only the match bounds, so recording the match and writing it in a
     * second pass would only add work per match.
     */
    private void writeMatch(ParsedReplacement template, int start, int end)
    {
        if (start < previousEnd || end < start || end > source.length()) {
            throw invalidMatchBounds(start, end);
        }
        long replacementLength = addExact(
                template.literalLength(),
                multiplyExact((long) (end - start), template.groupZeroReferenceCount()));
        outputLength = addExact(outputLength - (end - start), replacementLength);
        long writtenLength = outputPosition + (long) (start - previousEnd) + replacementLength;
        if (output == null || writtenLength > output.length) {
            growOutput(toIntExact(writtenLength));
        }

        byte[] output = this.output;
        int position = copySource(output, outputPosition, previousEnd, start);
        byte[] literalBytes = template.literalBytes();
        int[] operationValues = template.operationValues();
        int[] operationLengths = template.operationLengths();
        for (int operationIndex = 0; operationIndex < operationValues.length; operationIndex++) {
            int operation = operationValues[operationIndex];
            if (operation >= 0) {
                int length = operationLengths[operationIndex];
                System.arraycopy(literalBytes, operation, output, position, length);
                position += length;
            }
            else {
                position = copySource(output, position, start, end);
            }
        }
        outputPosition = position;
        previousEnd = end;
    }

    private void growOutput(int requiredLength)
    {
        // The written output is a prefix of the result, so growing geometrically from the written
        // length keeps allocation proportional to the result even when a later match deletes the
        // rest of the source. Each buffer stops at the result as it stands after this match when
        // that is close enough, so a row whose later matches keep its length needs no final copy;
        // growing by at least half keeps the growth geometric when later matches lengthen it.
        if (output == null) {
            long firstCapacity = Math.min(Math.max(requiredLength * 2L, MINIMUM_DIRECT_OUTPUT_LENGTH), outputLength);
            output = new byte[(int) Math.max(requiredLength, Math.min(firstCapacity, MAXIMUM_ARRAY_LENGTH))];
            return;
        }
        long capacity = output.length;
        long grownCapacity = Math.min(Math.max(outputLength, capacity + capacity / 2), capacity * 2);
        output = Arrays.copyOf(output, (int) Math.max(requiredLength, Math.min(grownCapacity, MAXIMUM_ARRAY_LENGTH)));
    }

    Slice build()
    {
        if (output != null) {
            return buildWritten();
        }
        if (matchCount == 0) {
            return source;
        }
        byte[] result = new byte[toIntExact(outputLength)];
        int resultPosition = writeMatches(result, 0);
        copySource(result, resultPosition, previousEnd, source.length());
        return Slices.wrappedBuffer(result);
    }

    private Slice buildWritten()
    {
        // The result length is now exact, so size the buffer to it once for the remaining matches
        // and the unmatched tail. The written prefix never exceeds the result.
        int resultLength = toIntExact(outputLength);
        if (output.length != resultLength) {
            output = Arrays.copyOf(output, resultLength);
        }
        if (matchCount != 0) {
            flushMatches();
        }
        copySource(output, outputPosition, previousEnd, source.length());
        return Slices.wrappedBuffer(output);
    }

    private ParsedReplacement parseTemplate()
    {
        ParsedReplacement template = parse(replacement, capturingGroupCount, namedCapturingGroups);
        setTemplate(template);
        if (templateOwner != null && replacement.length() <= MAXIMUM_CACHED_REPLACEMENT_LENGTH) {
            // A private copy: the caller may reuse the replacement's array for other content.
            templateOwner.cacheReplacementTemplate(new CachedTemplate(replacement.getBytes(), maximumCapturingGroup, template));
        }
        return template;
    }

    private void setTemplate(ParsedReplacement template)
    {
        // The record capacity limit is rounded to whole records when the records are first allocated.
        recordSize = addExact(2, multiplyExact(template.referencedGroups().length, 2));
        this.template = template;
    }

    private int beginMatch(int start, int end)
    {
        if (start < 0 || end < start || end > source.length()) {
            throw invalidMatchBounds(start, end);
        }
        int recordOffset = matchCount * recordSize;
        int[] matches = this.matches;
        if (matches == null || recordOffset > matches.length - recordSize) {
            recordOffset = reserveRecord(recordOffset);
            matches = this.matches;
        }
        matches[recordOffset] = start;
        matches[recordOffset + 1] = end;
        matchCount++;
        return recordOffset;
    }

    /**
     * Allocates the records, grows them, or flushes them when full, and returns the offset for
     * the next record.
     */
    private int reserveRecord(int recordOffset)
    {
        if (matches == null) {
            recordCapacityLimit = Math.max(recordSize, recordCapacityLimit - recordCapacityLimit % recordSize);
            // Room for a few matches: growing from one record would copy on every multi-match row.
            matches = new int[Math.min(recordCapacityLimit, recordSize * 4)];
            return recordOffset;
        }
        if (matches.length < recordCapacityLimit) {
            matches = Arrays.copyOf(
                    matches,
                    matchRecordCapacity(matches.length, recordOffset + recordSize, recordCapacityLimit));
            return recordOffset;
        }
        flushMatches();
        return 0;
    }

    private static IllegalArgumentException invalidMatchBounds(int start, int end)
    {
        return new IllegalArgumentException("invalid match bounds: " + start + ":" + end);
    }

    private void addReplacementLength(int recordOffset, long replacementLength)
    {
        int matchLength = matches[recordOffset + 1] - matches[recordOffset];
        outputLength = addExact(outputLength - matchLength, replacementLength);
    }

    private void flushMatches()
    {
        // The written output is a prefix of the result, so a prefix past the int range fails the
        // same toIntExact check that build() applies to the complete result.
        int processedSourceLength = matches[(matchCount - 1) * recordSize + 1];
        int writtenLength = toIntExact(outputLength - (source.length() - processedSourceLength));
        ensureOutputCapacity(writtenLength);
        outputPosition = writeMatches(output, outputPosition);
        matchCount = 0;
    }

    private void ensureOutputCapacity(int requiredLength)
    {
        int capacity = output == null ? 0 : output.length;
        if (output != null && requiredLength <= capacity) {
            return;
        }
        // Grow geometrically from the written length alone. The written output is a prefix of the
        // result, so total buffer allocation stays proportional to the result even when a later
        // match deletes the rest of the source.
        long grownCapacity = Math.max(capacity * 2L, 4096);
        int newCapacity = (int) Math.max(requiredLength, Math.min(grownCapacity, MAXIMUM_ARRAY_LENGTH));
        output = output == null ? new byte[newCapacity] : Arrays.copyOf(output, newCapacity);
    }

    private int writeMatches(byte[] result, int resultPosition)
    {
        byte[] literalBytes = template.literalBytes();
        int[] operationValues = template.operationValues();
        int[] operationLengths = template.operationLengths();
        int recordSize = this.recordSize;
        int previousEnd = this.previousEnd;
        for (int matchIndex = 0; matchIndex < matchCount; matchIndex++) {
            int recordOffset = matchIndex * recordSize;
            int matchStart = matches[recordOffset];
            int matchEnd = matches[recordOffset + 1];
            resultPosition = copySource(result, resultPosition, previousEnd, matchStart);

            for (int operationIndex = 0; operationIndex < operationValues.length; operationIndex++) {
                int operation = operationValues[operationIndex];
                if (operation >= 0) {
                    int length = operationLengths[operationIndex];
                    System.arraycopy(literalBytes, operation, result, resultPosition, length);
                    resultPosition += length;
                    continue;
                }

                int groupStart;
                int groupEnd;
                if (operation == GROUP_ZERO_OPERATION) {
                    groupStart = matchStart;
                    groupEnd = matchEnd;
                }
                else {
                    int groupSlot = -operation - 2;
                    groupStart = matches[recordOffset + 2 + groupSlot * 2];
                    groupEnd = matches[recordOffset + 3 + groupSlot * 2];
                }
                if (groupStart >= 0) {
                    resultPosition = copySource(result, resultPosition, groupStart, groupEnd);
                }
            }
            previousEnd = matchEnd;
        }
        this.previousEnd = previousEnd;
        return resultPosition;
    }

    private int copySource(byte[] result, int outputPosition, int start, int end)
    {
        // Match bounds were checked when recorded.
        int length = end - start;
        System.arraycopy(source.byteArray(), source.byteArrayOffset() + start, result, outputPosition, length);
        return outputPosition + length;
    }

    /**
     * Returns the capacity for a full match-record array, growing geometrically without exceeding
     * {@code capacityLimit}. Uses long arithmetic so capacities near the int limit clamp rather than
     * overflow.
     */
    static int matchRecordCapacity(int currentCapacity, int requiredCapacity, int capacityLimit)
    {
        if (currentCapacity < 0 || requiredCapacity <= currentCapacity || requiredCapacity > capacityLimit) {
            throw new IllegalArgumentException("invalid match record capacity: " + currentCapacity + ", " + requiredCapacity + ", " + capacityLimit);
        }
        long grownCapacity = Math.max(8L, (long) currentCapacity * 2);
        return (int) Math.max(requiredCapacity, Math.min(grownCapacity, capacityLimit));
    }

    /**
     * Returns the highest capturing group a replacement references, or {@link #MALFORMED_REPLACEMENT}
     * if {@link #parse} rejects it without resolving a name. This mirrors the parser's grammar,
     * including greedy multi-digit group numbers, but allocates nothing. A named reference counts
     * as every capturing group because resolving the name would allocate.
     */
    private static int maximumReferencedGroup(Slice replacement, int capturingGroupCount)
    {
        int maximum = 0;
        for (int index = 0; index < replacement.length(); index++) {
            int current = replacement.getUnsignedByte(index);
            if (current == '\\') {
                if (++index == replacement.length()) {
                    return MALFORMED_REPLACEMENT;
                }
                continue;
            }
            if (current != '$') {
                continue;
            }

            if (++index == replacement.length()) {
                return MALFORMED_REPLACEMENT;
            }
            int next = replacement.getUnsignedByte(index);
            if (next == '{') {
                int nameStart = ++index;
                while (index < replacement.length() && replacement.getUnsignedByte(index) != '}') {
                    index++;
                }
                // Every named group is a capturing group, so none exists without capturing groups.
                if (index == replacement.length() || index == nameStart || capturingGroupCount == 0) {
                    return MALFORMED_REPLACEMENT;
                }
                maximum = capturingGroupCount;
                continue;
            }

            if (next < '0' || next > '9') {
                return MALFORMED_REPLACEMENT;
            }
            int group = next - '0';
            if (group > capturingGroupCount) {
                return MALFORMED_REPLACEMENT;
            }
            while (index + 1 < replacement.length()) {
                int digit = replacement.getUnsignedByte(index + 1) - '0';
                if (digit < 0 || digit > 9) {
                    break;
                }
                long candidate = (long) group * 10 + digit;
                if (candidate > capturingGroupCount) {
                    break;
                }
                group = (int) candidate;
                index++;
            }
            maximum = Math.max(maximum, group);
        }
        return maximum;
    }

    /**
     * Returns how many referenced-group entries parsing the replacement compares to resolve its
     * group references.
     */
    static int groupComparisonsForDiagnostics(Slice replacement, int capturingGroupCount)
    {
        int[] groupComparisons = new int[1];
        parse(replacement, capturingGroupCount, Map.of(), groupComparisons);
        return groupComparisons[0];
    }

    private static ParsedReplacement parse(
            Slice replacement,
            int capturingGroupCount,
            Map<String, Integer> namedCapturingGroups)
    {
        return parse(replacement, capturingGroupCount, namedCapturingGroups, null);
    }

    private static ParsedReplacement parse(
            Slice replacement,
            int capturingGroupCount,
            Map<String, Integer> namedCapturingGroups,
            int[] groupComparisons)
    {
        byte[] literalBytes = new byte[replacement.length()];
        int[] operationValues = new int[INITIAL_PARSE_CAPACITY];
        int[] operationLengths = new int[INITIAL_PARSE_CAPACITY];
        int[] referencedGroups = new int[INITIAL_PARSE_CAPACITY];
        int[] groupReferenceCounts = new int[INITIAL_PARSE_CAPACITY];
        // Each referenced group's slot plus one, indexed by group number; null until the template
        // references more than LINEAR_GROUP_LOOKUP_LIMIT distinct groups.
        int[] groupSlots = null;
        int literalLength = 0;
        int literalRunStart = 0;
        int operationCount = 0;
        int referencedGroupCount = 0;
        int groupZeroReferenceCount = 0;

        for (int index = 0; index < replacement.length(); index++) {
            int current = replacement.getUnsignedByte(index);
            if (current == '\\') {
                int escapeOffset = index;
                if (++index == replacement.length()) {
                    throw new TrinoRegexpReplacementException("backslash cannot be last in replacement", escapeOffset);
                }
                literalBytes[literalLength++] = replacement.getByte(index);
                continue;
            }
            if (current != '$') {
                literalBytes[literalLength++] = replacement.getByte(index);
                continue;
            }

            // The literal run before this reference and the reference add at most two operations.
            if (operationCount + 2 > operationValues.length) {
                operationValues = grow(operationValues, operationCount + 2);
                operationLengths = grow(operationLengths, operationCount + 2);
            }
            if (literalLength > literalRunStart) {
                operationValues[operationCount] = literalRunStart;
                operationLengths[operationCount] = literalLength - literalRunStart;
                operationCount++;
            }

            int referenceOffset = index;
            if (++index == replacement.length()) {
                throw new TrinoRegexpReplacementException("dollar sign cannot be last in replacement", referenceOffset);
            }
            int next = replacement.getUnsignedByte(index);
            int group;
            if (next == '{') {
                int nameStart = ++index;
                while (index < replacement.length() && replacement.getUnsignedByte(index) != '}') {
                    index++;
                }
                if (index == replacement.length() || index == nameStart) {
                    throw new TrinoRegexpReplacementException("invalid named group in replacement", referenceOffset);
                }
                String groupName = replacement.slice(nameStart, index - nameStart).toStringUtf8();
                Integer namedGroup = namedCapturingGroups.get(groupName);
                if (namedGroup == null) {
                    throw new TrinoRegexpReplacementException("unknown named group: " + groupName, referenceOffset);
                }
                group = namedGroup;
            }
            else {
                if (next < '0' || next > '9') {
                    throw new TrinoRegexpReplacementException(
                            "dollar sign must be followed by a digit or group name",
                            referenceOffset);
                }
                group = next - '0';
                if (group > capturingGroupCount) {
                    throw new TrinoRegexpReplacementException("unknown group: " + group, referenceOffset);
                }
                while (index + 1 < replacement.length()) {
                    int digit = replacement.getUnsignedByte(index + 1) - '0';
                    if (digit < 0 || digit > 9) {
                        break;
                    }
                    long candidate = (long) group * 10 + digit;
                    if (candidate > capturingGroupCount) {
                        break;
                    }
                    group = (int) candidate;
                    index++;
                }
            }

            if (group == 0) {
                operationValues[operationCount] = GROUP_ZERO_OPERATION;
                groupZeroReferenceCount++;
            }
            else {
                int groupSlot;
                if (groupSlots != null) {
                    groupSlot = groupSlots[group] - 1;
                }
                else {
                    groupSlot = findGroup(referencedGroups, referencedGroupCount, group);
                    if (groupComparisons != null) {
                        groupComparisons[0] += groupSlot < 0 ? referencedGroupCount : groupSlot + 1;
                    }
                }
                if (groupSlot < 0) {
                    if (referencedGroupCount == referencedGroups.length) {
                        referencedGroups = grow(referencedGroups, referencedGroupCount + 1);
                        groupReferenceCounts = grow(groupReferenceCounts, referencedGroupCount + 1);
                    }
                    groupSlot = referencedGroupCount++;
                    referencedGroups[groupSlot] = group;
                    if (groupSlots != null) {
                        groupSlots[group] = groupSlot + 1;
                    }
                    else if (referencedGroupCount > LINEAR_GROUP_LOOKUP_LIMIT) {
                        groupSlots = new int[capturingGroupCount + 1];
                        for (int slot = 0; slot < referencedGroupCount; slot++) {
                            groupSlots[referencedGroups[slot]] = slot + 1;
                        }
                    }
                }
                groupReferenceCounts[groupSlot]++;
                operationValues[operationCount] = -groupSlot - 2;
            }
            operationLengths[operationCount] = 0;
            operationCount++;
            literalRunStart = literalLength;
        }

        if (literalLength > literalRunStart) {
            if (operationCount == operationValues.length) {
                operationValues = grow(operationValues, operationCount + 1);
                operationLengths = grow(operationLengths, operationCount + 1);
            }
            operationValues[operationCount] = literalRunStart;
            operationLengths[operationCount] = literalLength - literalRunStart;
            operationCount++;
        }

        return new ParsedReplacement(
                Arrays.copyOf(literalBytes, literalLength),
                Arrays.copyOf(operationValues, operationCount),
                Arrays.copyOf(operationLengths, operationCount),
                Arrays.copyOf(referencedGroups, referencedGroupCount),
                Arrays.copyOf(groupReferenceCounts, referencedGroupCount),
                groupZeroReferenceCount,
                literalLength);
    }

    private static int[] grow(int[] values, int requiredLength)
    {
        return Arrays.copyOf(values, (int) Math.max(requiredLength, Math.min(values.length * 2L, MAXIMUM_ARRAY_LENGTH)));
    }

    private static int findGroup(int[] groups, int groupCount, int group)
    {
        for (int index = 0; index < groupCount; index++) {
            if (groups[index] == group) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Writes each match of a replacement without capturing-group references straight to the
     * output. It produces the same output and allocation as the plan's own direct writes.
     * <p>
     * A route's search loop inlines {@link #addMatch} and, when this replacer does not escape,
     * keeps each of its fields in a register or stack slot for the whole loop. Calls the search
     * makes out of line preserve no registers, so every value live across the loop is spilled
     * and reloaded around them. This replacer therefore holds only the output state and what a
     * first-match parse needs, and its out-of-line helpers are static so that it never escapes.
     */
    static final class GroupFreeReplacer
    {
        private final Slice source;
        private final Slice replacement;
        private final TrinoRegexp templateOwner;
        // Parsed on the first match, as in the plan, unless the owner cached it.
        private ParsedReplacement template;

        // Null until the first match. The result length after each match is the written length
        // plus the unmatched source, so the plan's running result length is not tracked.
        private byte[] output;
        private int outputPosition;
        private int previousEnd;

        private GroupFreeReplacer(Slice source, Slice replacement, TrinoRegexp templateOwner, ParsedReplacement template)
        {
            this.source = source;
            this.replacement = replacement;
            this.templateOwner = templateOwner;
            this.template = template;
        }

        void addMatch(int start, int end)
        {
            ParsedReplacement template = this.template;
            if (template == null) {
                template = parseGroupFreeTemplate(replacement, templateOwner);
                this.template = template;
            }
            if (start < previousEnd || end < start || end > source.length()) {
                throw invalidMatchBounds(start, end);
            }
            long replacementLength = addExact(
                    template.literalLength(),
                    multiplyExact((long) (end - start), template.groupZeroReferenceCount()));
            long writtenLength = outputPosition + (long) (start - previousEnd) + replacementLength;
            byte[] output = this.output;
            if (output == null || writtenLength > output.length) {
                output = growGroupFreeOutput(output, writtenLength, writtenLength + (source.length() - end));
                this.output = output;
            }
            int position = copySourceRange(source, previousEnd, start, output, outputPosition);
            outputPosition = writeGroupFreeTemplate(template, source, start, end, output, position);
            previousEnd = end;
        }

        Slice build()
        {
            byte[] output = this.output;
            if (output == null) {
                return source;
            }
            // The result length is now exact. The written prefix never exceeds it.
            int resultLength = toIntExact(outputPosition + (long) (source.length() - previousEnd));
            if (output.length != resultLength) {
                output = Arrays.copyOf(output, resultLength);
            }
            copySourceRange(source, previousEnd, source.length(), output, outputPosition);
            return Slices.wrappedBuffer(output);
        }
    }

    private static ParsedReplacement parseGroupFreeTemplate(Slice replacement, TrinoRegexp templateOwner)
    {
        ParsedReplacement template = parse(replacement, templateOwner.capturingGroupCount(), templateOwner.namedCapturingGroups());
        if (template.referencedGroups().length != 0) {
            throw capturingGroupsRequired();
        }
        if (replacement.length() <= MAXIMUM_CACHED_REPLACEMENT_LENGTH) {
            // A private copy: the caller may reuse the replacement's array for other content.
            templateOwner.cacheReplacementTemplate(new CachedTemplate(replacement.getBytes(), 0, template));
        }
        return template;
    }

    /**
     * Returns an output buffer holding {@code output}'s written bytes with room for
     * {@code requiredLength}, sized as {@link #growOutput} sizes the plan's direct output.
     * {@code resultLength} is the result length if no further match changes it.
     */
    private static byte[] growGroupFreeOutput(byte[] output, long requiredLength, long resultLength)
    {
        int required = toIntExact(requiredLength);
        if (output == null) {
            long firstCapacity = Math.min(Math.max(required * 2L, MINIMUM_DIRECT_OUTPUT_LENGTH), resultLength);
            return new byte[(int) Math.max(required, Math.min(firstCapacity, MAXIMUM_ARRAY_LENGTH))];
        }
        long capacity = output.length;
        long grownCapacity = Math.min(Math.max(resultLength, capacity + capacity / 2), capacity * 2);
        return Arrays.copyOf(output, (int) Math.max(required, Math.min(grownCapacity, MAXIMUM_ARRAY_LENGTH)));
    }

    /**
     * Writes the replacement for the match {@code [start, end)} at {@code position} and returns
     * the position after it. The template must not reference capturing groups.
     */
    private static int writeGroupFreeTemplate(ParsedReplacement template, Slice source, int start, int end, byte[] output, int position)
    {
        byte[] literalBytes = template.literalBytes();
        int[] operationValues = template.operationValues();
        int[] operationLengths = template.operationLengths();
        for (int operationIndex = 0; operationIndex < operationValues.length; operationIndex++) {
            int operation = operationValues[operationIndex];
            if (operation >= 0) {
                int length = operationLengths[operationIndex];
                System.arraycopy(literalBytes, operation, output, position, length);
                position += length;
            }
            else {
                position = copySourceRange(source, start, end, output, position);
            }
        }
        return position;
    }

    private static int copySourceRange(Slice source, int from, int to, byte[] output, int outputPosition)
    {
        // Match bounds were checked when written.
        int length = to - from;
        System.arraycopy(source.byteArray(), source.byteArrayOffset() + from, output, outputPosition, length);
        return outputPosition + length;
    }

    /**
     * A well-formed replacement and its parsed template, retained by one TrinoRegexp. It is
     * immutable, so callers racing to publish different entries only cost a later reparse.
     */
    static final class CachedTemplate
    {
        private final byte[] replacement;
        private final int maximumCapturingGroup;
        private final ParsedReplacement template;

        private CachedTemplate(byte[] replacement, int maximumCapturingGroup, ParsedReplacement template)
        {
            this.replacement = replacement;
            this.maximumCapturingGroup = maximumCapturingGroup;
            this.template = template;
        }

        boolean matches(Slice replacement)
        {
            // Cached replacements are short, so a plain loop keeps this lookup's compiled code
            // small enough to inline into each caller.
            byte[] cached = this.replacement;
            if (cached.length != replacement.length()) {
                return false;
            }
            byte[] bytes = replacement.byteArray();
            int offset = replacement.byteArrayOffset();
            for (int index = 0; index < cached.length; index++) {
                if (cached[index] != bytes[offset + index]) {
                    return false;
                }
            }
            return true;
        }
    }

    private record ParsedReplacement(
            byte[] literalBytes,
            int[] operationValues,
            int[] operationLengths,
            int[] referencedGroups,
            int[] groupReferenceCounts,
            int groupZeroReferenceCount,
            int literalLength) {}
}
