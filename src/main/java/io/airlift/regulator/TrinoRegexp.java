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
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceUtf8;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/**
 * Trino SQL regular-expression operations over UTF-8 Slice inputs.
 * <p>
 * Function behavior follows Trino's shared regexp contract, including UTF-8 code-point positions,
 * empty-match iteration, trailing split fields, and Java-style replacement references. Patterns use
 * the supported Trino fork of Joni's Java-syntax language documented in
 * {@code docs/integrations/REGEXP_LANGUAGES.md}; unsupported constructs are rejected during compilation.
 */
public sealed class TrinoRegexp
        permits TrinoRegexp.CharacterClassTrinoRegexp,
                TrinoRegexp.DotStarLiteralTrinoRegexp,
                TrinoRegexp.ExactLiteralTrinoRegexp,
                TrinoRegexp.LiteralAlternationTrinoRegexp,
                TrinoRegexp.OrderedLiteralTrinoRegexp
{
    /**
     * Mutable engine resource options. Compilation snapshots every option, so later changes do not
     * affect an existing {@link TrinoRegexp}. Options instances are not thread-safe.
     */
    public static final class Options
    {
        private long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;

        private Options() {}

        public static Options defaults()
        {
            return new Options();
        }

        long maxMemory()
        {
            return maxMemory;
        }

        public Options setMaxMemory(long maxMemory)
        {
            if (maxMemory <= 0) {
                throw new IllegalArgumentException("maxMemory must be greater than zero: " + maxMemory);
            }
            this.maxMemory = maxMemory;
            return this;
        }
    }

    private final Re2 pattern;
    private final boolean mayHaveSingleByteRepeatMatcher;
    private volatile SingleByteRepeatMatcher singleByteRepeatMatcher;

    private TrinoRegexp()
    {
        this.pattern = null;
        this.mayHaveSingleByteRepeatMatcher = false;
    }

    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "The sealed hierarchy permits only private final nested implementations")
    private TrinoRegexp(Re2 pattern)
    {
        this.pattern = requireNonNull(pattern, "pattern is null");
        this.mayHaveSingleByteRepeatMatcher = pattern.canMatchEmpty();
    }

    public static TrinoRegexp compile(Slice pattern)
    {
        return compile(pattern, Options.defaults());
    }

    /**
     * Compiles a copied snapshot of {@code pattern} and the current option values.
     */
    public static TrinoRegexp compile(Slice pattern, Options options)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        Slice patternCopy = pattern.copy();
        ParseResult parsed = TrinoRegexpParser.parse(patternCopy, Regexp.LIKE_PERL);
        LiteralAlternationSpanMatcher literalAlternationMatcher = LiteralAlternationSpanMatcher.analyze(
                parsed.regexp(),
                parsed.capturingGroupCount());
        long forwardMemory = Math.max(1, options.maxMemory() - Math.max(1, options.maxMemory() / 3));
        if (literalAlternationMatcher != null && literalAlternationMatcher.estimatedRetainedSize() <= forwardMemory) {
            return new LiteralAlternationTrinoRegexp(literalAlternationMatcher);
        }
        Re2 compiledPattern = Re2.compileParsedForTrino(patternCopy, parsed, Regexp.LIKE_PERL, options.maxMemory());
        OrderedLiteralMatcher orderedLiteralMatcher = compiledPattern.createOrderedLiteralMatcher();
        if (orderedLiteralMatcher != null && compiledPattern.tryReserveForwardDfaMemory(orderedLiteralMatcher.estimatedRetainedSize())) {
            return new OrderedLiteralTrinoRegexp(compiledPattern, orderedLiteralMatcher);
        }
        DotStarLiteralSpanMatcher dotStarLiteralSpanMatcher = compiledPattern.createDotStarLiteralSpanMatcher();
        if (dotStarLiteralSpanMatcher != null) {
            return new DotStarLiteralTrinoRegexp(compiledPattern, dotStarLiteralSpanMatcher);
        }
        if (compiledPattern.hasExactLiteralSpan()) {
            return new ExactLiteralTrinoRegexp(compiledPattern);
        }
        if (compiledPattern.mayHaveFixedWidthByteSpanMatcher()) {
            TrinoRegexp fixedWidthByteRegexp = FixedWidthByteSpanMatcher.createTrinoRegexp(compiledPattern);
            if (fixedWidthByteRegexp != null) {
                return fixedWidthByteRegexp;
            }
        }
        if (compiledPattern.hasCharacterClassSpan()) {
            return new CharacterClassTrinoRegexp(compiledPattern);
        }
        return new TrinoRegexp(compiledPattern);
    }

    /**
     * Creates a mutable matcher retaining every capturing group. Matching uses the Trino language
     * and existing regex execution machinery; it does not build extraction or replacement outputs.
     */
    public TrinoRegexpMatcher matcher(Slice source)
    {
        return matcher(source, capturingGroupCount());
    }

    /**
     * Creates a reusable matcher retaining group zero and the requested prefix of capturing groups.
     * Use zero when only complete-match boundaries are required. This does not change the specialized
     * execution routes used by the other Trino operations.
     */
    public TrinoRegexpMatcher matcher(Slice source, int retainedCapturingGroupCount)
    {
        requireNonNull(source, "source is null");
        if (retainedCapturingGroupCount < 0 || retainedCapturingGroupCount > capturingGroupCount()) {
            throw new IllegalArgumentException("capturingGroupCount out of range: " + retainedCapturingGroupCount);
        }
        LiteralAlternationSpanMatcher literalAlternation = literalAlternationSpanMatcher();
        if (literalAlternation != null) {
            return new TrinoRegexpMatcher(literalAlternation, source);
        }
        return new TrinoRegexpMatcher(retainedCapturingGroupCount == 0 ? newGroupZeroMatcher(source) :
                pattern.matcher(source, retainedCapturingGroupCount));
    }

    Re2 pattern()
    {
        return pattern;
    }

    LiteralAlternationSpanMatcher literalAlternationSpanMatcher()
    {
        return null;
    }

    boolean usesOrderedLiteralMatcherForDiagnostics()
    {
        return false;
    }

    boolean usesLiteralGapMatcherForDiagnostics()
    {
        return false;
    }

    long orderedLiteralMatcherRetainedSizeForDiagnostics()
    {
        return 0;
    }

    boolean isSingleByteRepeatMatcherComputed()
    {
        return singleByteRepeatMatcher != null;
    }

    boolean isSingleByteMatcherComputed()
    {
        return pattern != null && pattern.isSingleByteMatcherComputed();
    }

    /**
     * Returns the number of capturing groups.
     */
    public int capturingGroupCount()
    {
        return pattern.capturingGroupCount();
    }

    /**
     * Returns named capturing groups mapped to their one-based group indexes.
     */
    public Map<String, Integer> namedCapturingGroups()
    {
        return pattern.namedCapturingGroups();
    }

    /**
     * Returns whether the source contains a match.
     */
    public boolean contains(Slice source)
    {
        requireNonNull(source, "source is null");
        return pattern.find(source);
    }

    private static final class OrderedLiteralTrinoRegexp
            extends TrinoRegexp
    {
        private final OrderedLiteralMatcher matcher;

        private OrderedLiteralTrinoRegexp(Re2 pattern, OrderedLiteralMatcher matcher)
        {
            super(pattern);
            this.matcher = requireNonNull(matcher, "matcher is null");
        }

        @Override
        boolean usesOrderedLiteralMatcherForDiagnostics()
        {
            return true;
        }

        @Override
        long orderedLiteralMatcherRetainedSizeForDiagnostics()
        {
            return matcher.estimatedRetainedSize();
        }

        @Override
        public boolean contains(Slice source)
        {
            requireNonNull(source, "source is null");
            return matcher.matches(source, 0, source.length());
        }
    }

    /**
     * Returns the number of non-overlapping matches, including empty matches.
     */
    public long count(Slice source)
    {
        requireNonNull(source, "source is null");
        SingleByteMatcher byteMatcher = singleByteMatcher();
        if (byteMatcher != SingleByteMatcher.unsupported()) {
            return byteMatcher.count(source);
        }

        SingleByteRepeatMatcher repeatMatcher = singleByteRepeatMatcher();
        if (repeatMatcher != SingleByteRepeatMatcher.unsupported()) {
            return repeatMatcher.count(source);
        }

        long optimizedCount = pattern.countMatches(source);
        if (optimizedCount >= 0) {
            return optimizedCount;
        }

        Re2Matcher matcher = newGroupZeroMatcher(source);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Returns the one-based code-point position of the first match, or {@code -1}.
     */
    public long position(Slice source)
    {
        return position(source, 1, 1);
    }

    /**
     * Returns the one-based code-point position of the first match at or after {@code start}, or
     * {@code -1}. The start position is one-based.
     */
    public long position(Slice source, long start)
    {
        return position(source, start, 1);
    }

    /**
     * Returns the one-based code-point position of the requested match, or {@code -1}.
     *
     * @throws IllegalArgumentException if {@code start} or {@code occurrence} is less than one
     */
    public long position(Slice source, long start, long occurrence)
    {
        requireNonNull(source, "source is null");
        if (start < 1) {
            throw new IllegalArgumentException("start position cannot be smaller than 1");
        }
        if (occurrence < 1) {
            throw new IllegalArgumentException("occurrence cannot be smaller than 1");
        }

        long codePointStart = start - 1;
        if (codePointStart > source.length()) {
            return -1;
        }
        int byteStart = SliceUtf8.offsetOfCodePoint(source, toIntExact(codePointStart));
        if (byteStart < 0) {
            return -1;
        }

        SingleByteRepeatMatcher.Cursor repeatCursor = newSingleByteRepeatCursor(source, false);
        if (repeatCursor != null) {
            repeatCursor.find(byteStart);
            for (long match = 1; match < occurrence; match++) {
                if (!repeatCursor.find()) {
                    return -1;
                }
            }
            return SliceUtf8.countCodePoints(source, 0, repeatCursor.start()) + 1L;
        }

        Re2Matcher matcher = newGroupZeroMatcher(source);
        if (!matcher.find(byteStart)) {
            return -1;
        }
        for (long match = 1; match < occurrence; match++) {
            if (!matcher.find()) {
                return -1;
            }
        }
        return SliceUtf8.countCodePoints(source, 0, matcher.start()) + 1L;
    }

    /**
     * Returns a zero-copy view of the first complete match, or {@code null}.
     */
    public Slice extract(Slice source)
    {
        return extract(source, 0);
    }

    /**
     * Returns a zero-copy view of a group from the first match, or {@code null} when there is no
     * match or the group did not participate. Group zero is the complete match.
     */
    public Slice extract(Slice source, int group)
    {
        validateGroup(group);
        requireNonNull(source, "source is null");
        Re2Matcher matcher = group == 0 ? newGroupZeroMatcher(source) : newMatcher(source);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(group);
    }

    /**
     * Returns zero-copy views of every complete match.
     */
    public List<Slice> extractAll(Slice source)
    {
        return extractAll(source, 0);
    }

    /**
     * Returns zero-copy views of the requested group from every match. Unmatched groups are
     * represented by {@code null}. Group zero is the complete match.
     */
    public List<Slice> extractAll(Slice source, int group)
    {
        validateGroup(group);
        requireNonNull(source, "source is null");
        SingleByteRepeatMatcher.Cursor repeatCursor = newSingleByteRepeatCursor(source, group != 0);
        if (repeatCursor != null) {
            List<Slice> matches = new ArrayList<>();
            while (repeatCursor.find()) {
                matches.add(repeatCursor.group());
            }
            return Collections.unmodifiableList(matches);
        }

        Re2Matcher matcher = group == 0 ? newGroupZeroMatcher(source) : newMatcher(source);
        List<Slice> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(matcher.group(group));
        }
        return Collections.unmodifiableList(matches);
    }

    /**
     * Splits around non-overlapping matches and preserves trailing empty fields. Returned values are
     * zero-copy views of {@code source}.
     */
    public List<Slice> split(Slice source)
    {
        requireNonNull(source, "source is null");
        SingleByteRepeatMatcher.Cursor repeatCursor = newSingleByteRepeatCursor(source, false);
        if (repeatCursor != null) {
            List<Slice> parts = new ArrayList<>();
            int previousEnd = 0;
            while (repeatCursor.find()) {
                parts.add(source.slice(previousEnd, repeatCursor.start() - previousEnd));
                previousEnd = repeatCursor.end();
            }
            parts.add(source.slice(previousEnd, source.length() - previousEnd));
            return Collections.unmodifiableList(parts);
        }

        Re2Matcher matcher = newGroupZeroMatcher(source);
        List<Slice> parts = new ArrayList<>();
        int previousEnd = 0;
        while (matcher.find()) {
            parts.add(source.slice(previousEnd, matcher.start() - previousEnd));
            previousEnd = matcher.end();
        }
        parts.add(source.slice(previousEnd, source.length() - previousEnd));
        return Collections.unmodifiableList(parts);
    }

    /**
     * Replaces every non-overlapping match using Trino replacement syntax. Numbered references use
     * {@code $1}; named references use {@code ${name}}; backslash escapes the following byte.
     *
     * @throws TrinoRegexpReplacementException if the replacement is malformed or names an unknown group
     */
    public Slice replace(Slice source, Slice replacement)
    {
        requireNonNull(source, "source is null");
        requireNonNull(replacement, "replacement is null");

        boolean needsCapturingGroups = replacementNeedsCapturingGroups(replacement);
        SingleByteRepeatMatcher.Cursor repeatCursor = newSingleByteRepeatCursor(source, needsCapturingGroups);
        if (repeatCursor != null) {
            DynamicSliceOutput output = new DynamicSliceOutput(source.length() + replacement.length());
            int previousEnd = 0;
            while (repeatCursor.find()) {
                output.writeBytes(source, previousEnd, repeatCursor.start() - previousEnd);
                appendReplacement(output, replacement, source, repeatCursor.start(), repeatCursor.end());
                previousEnd = repeatCursor.end();
            }
            output.writeBytes(source, previousEnd, source.length() - previousEnd);
            return output.slice();
        }

        Re2Matcher matcher = needsCapturingGroups ? newMatcher(source) : newGroupZeroMatcher(source);
        DynamicSliceOutput output = new DynamicSliceOutput(source.length() + replacement.length());
        int previousEnd = 0;
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            output.writeBytes(source, previousEnd, matcher.start() - previousEnd);
            appendReplacement(output, replacement, matcher);
            previousEnd = matcher.end();
        }
        if (!matched) {
            return source;
        }
        output.writeBytes(source, previousEnd, source.length() - previousEnd);
        return output.slice();
    }

    boolean replacementNeedsCapturingGroups(Slice replacement)
    {
        return replacementNeedsCapturingGroups(replacement, pattern.capturingGroupCount());
    }

    private static boolean replacementNeedsCapturingGroups(Slice replacement, int capturingGroupCount)
    {
        for (int index = 0; index < replacement.length(); index++) {
            int current = replacement.getUnsignedByte(index);
            if (current == '\\') {
                if (++index == replacement.length()) {
                    return true;
                }
                continue;
            }
            if (current != '$') {
                continue;
            }
            if (++index == replacement.length()) {
                return true;
            }

            int next = replacement.getUnsignedByte(index);
            if (next == '{' || next < '0' || next > '9') {
                return true;
            }

            int group = next - '0';
            if (group > 0) {
                return true;
            }
            while (index + 1 < replacement.length()) {
                int digit = replacement.getUnsignedByte(index + 1) - '0';
                if (digit < 0 || digit > 9) {
                    break;
                }
                int candidate = group * 10 + digit;
                if (candidate > capturingGroupCount) {
                    break;
                }
                if (candidate > 0) {
                    return true;
                }
                group = candidate;
                index++;
            }
        }
        return false;
    }

    /**
     * Replaces every non-overlapping match by invoking {@code replacement} with capturing groups
     * one through {@link #capturingGroupCount()}. Unmatched groups are {@code null}; group zero is
     * not included. Returns {@code null} immediately if the callback returns {@code null}.
     */
    public Slice replace(Slice source, Function<List<Slice>, Slice> replacement)
    {
        requireNonNull(source, "source is null");
        requireNonNull(replacement, "replacement is null");
        SingleByteRepeatMatcher.Cursor repeatCursor = newSingleByteRepeatCursor(source, true);
        if (repeatCursor != null) {
            DynamicSliceOutput output = new DynamicSliceOutput(source.length());
            int previousEnd = 0;
            List<Slice> groups = List.of();
            while (repeatCursor.find()) {
                output.writeBytes(source, previousEnd, repeatCursor.start() - previousEnd);
                Slice replacementValue = replacement.apply(groups);
                if (replacementValue == null) {
                    return null;
                }
                output.appendBytes(replacementValue);
                previousEnd = repeatCursor.end();
            }
            output.writeBytes(source, previousEnd, source.length() - previousEnd);
            return output.slice();
        }

        Re2Matcher matcher = newMatcher(source);
        DynamicSliceOutput output = new DynamicSliceOutput(source.length());
        int previousEnd = 0;
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            output.writeBytes(source, previousEnd, matcher.start() - previousEnd);
            List<Slice> groups = new ArrayList<>(matcher.groupCount());
            for (int group = 1; group <= matcher.groupCount(); group++) {
                groups.add(matcher.group(group));
            }
            Slice replacementValue = replacement.apply(Collections.unmodifiableList(groups));
            if (replacementValue == null) {
                return null;
            }
            output.appendBytes(replacementValue);
            previousEnd = matcher.end();
        }
        if (!matched) {
            return source;
        }
        output.writeBytes(source, previousEnd, source.length() - previousEnd);
        return output.slice();
    }

    private void appendReplacement(DynamicSliceOutput output, Slice replacement, Re2Matcher matcher)
    {
        appendReplacement(output, replacement, matcher, null, 0, 0);
    }

    final void appendReplacement(DynamicSliceOutput output, Slice replacement, Slice source, int matchStart, int matchEnd)
    {
        appendReplacement(output, replacement, null, source, matchStart, matchEnd);
    }

    private void appendReplacement(DynamicSliceOutput output, Slice replacement, Re2Matcher matcher, Slice source, int matchStart, int matchEnd)
    {
        for (int index = 0; index < replacement.length(); index++) {
            int current = replacement.getUnsignedByte(index);
            if (current == '\\') {
                int escapeOffset = index;
                if (++index == replacement.length()) {
                    throw new TrinoRegexpReplacementException("backslash cannot be last in replacement", escapeOffset);
                }
                output.writeByte(replacement.getUnsignedByte(index));
                continue;
            }
            if (current != '$') {
                output.writeByte(current);
                continue;
            }

            int referenceOffset = index;
            if (++index == replacement.length()) {
                throw new TrinoRegexpReplacementException("dollar sign cannot be last in replacement", referenceOffset);
            }
            int next = replacement.getUnsignedByte(index);
            if (next == '{') {
                int nameStart = ++index;
                while (index < replacement.length() && replacement.getUnsignedByte(index) != '}') {
                    index++;
                }
                if (index == replacement.length() || index == nameStart) {
                    throw new TrinoRegexpReplacementException("invalid named group in replacement", referenceOffset);
                }
                String groupName = replacement.slice(nameStart, index - nameStart).toStringUtf8();
                Integer groupIndex = namedCapturingGroups().get(groupName);
                if (matcher == null || groupIndex == null) {
                    throw new TrinoRegexpReplacementException("unknown named group: " + groupName, referenceOffset);
                }
                Slice group = matcher.group(groupIndex);
                if (group != null) {
                    output.appendBytes(group);
                }
                continue;
            }

            if (next < '0' || next > '9') {
                throw new TrinoRegexpReplacementException(
                        "dollar sign must be followed by a digit or group name",
                        referenceOffset);
            }
            int group = next - '0';
            int groupCount = matcher == null ? 0 : matcher.groupCount();
            if (group > groupCount) {
                throw new TrinoRegexpReplacementException("unknown group: " + group, referenceOffset);
            }
            while (index + 1 < replacement.length()) {
                int digit = replacement.getUnsignedByte(index + 1) - '0';
                if (digit < 0 || digit > 9) {
                    break;
                }
                int candidate = group * 10 + digit;
                if (candidate > groupCount) {
                    break;
                }
                group = candidate;
                index++;
            }
            Slice groupValue = matcher == null ? source.slice(matchStart, matchEnd - matchStart) : matcher.group(group);
            if (groupValue != null) {
                output.appendBytes(groupValue);
            }
        }
    }

    final void validateGroup(int group)
    {
        if (group < 0) {
            throw new IllegalArgumentException("group cannot be negative");
        }
        if (group > pattern.capturingGroupCount()) {
            throw new IllegalArgumentException("pattern has " + pattern.capturingGroupCount() +
                    " groups; cannot access group " + group);
        }
    }

    static non-sealed class DotStarLiteralTrinoRegexp
            extends TrinoRegexp
    {
        private final DotStarLiteralSpanMatcher spanMatcher;

        private DotStarLiteralTrinoRegexp(Re2 pattern, DotStarLiteralSpanMatcher spanMatcher)
        {
            super(pattern);
            this.spanMatcher = spanMatcher;
        }

        DotStarLiteralTrinoRegexp(Re2 pattern)
        {
            super(pattern);
            this.spanMatcher = null;
        }

        @Override
        public long count(Slice source)
        {
            requireNonNull(source, "source is null");
            long count = 0;
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                count++;
                searchStart = spanEnd(span);
            }
            return count;
        }

        @Override
        public long position(Slice source, long start, long occurrence)
        {
            requireNonNull(source, "source is null");
            if (start < 1) {
                throw new IllegalArgumentException("start position cannot be smaller than 1");
            }
            if (occurrence < 1) {
                throw new IllegalArgumentException("occurrence cannot be smaller than 1");
            }

            long codePointStart = start - 1;
            if (codePointStart > source.length()) {
                return -1;
            }
            int byteStart = SliceUtf8.offsetOfCodePoint(source, toIntExact(codePointStart));
            if (byteStart < 0) {
                return -1;
            }

            long span = spanMatcher.findSpan(source, byteStart);
            for (long match = 1; match < occurrence && span >= 0; match++) {
                span = spanMatcher.findSpan(source, spanEnd(span));
            }
            if (span < 0) {
                return -1;
            }
            return SliceUtf8.countCodePoints(source, 0, spanStart(span)) + 1L;
        }

        @Override
        public Slice extract(Slice source, int group)
        {
            validateGroup(group);
            requireNonNull(source, "source is null");
            long span = spanMatcher.findSpan(source, 0);
            return span < 0 ? null : source.slice(spanStart(span), spanEnd(span) - spanStart(span));
        }

        @Override
        public List<Slice> extractAll(Slice source, int group)
        {
            validateGroup(group);
            requireNonNull(source, "source is null");
            List<Slice> matches = new ArrayList<>();
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matches.add(source.slice(spanStart(span), spanEnd(span) - spanStart(span)));
                searchStart = spanEnd(span);
            }
            return Collections.unmodifiableList(matches);
        }

        @Override
        public List<Slice> split(Slice source)
        {
            requireNonNull(source, "source is null");
            List<Slice> parts = new ArrayList<>();
            int previousEnd = 0;
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                parts.add(source.slice(previousEnd, spanStart(span) - previousEnd));
                previousEnd = spanEnd(span);
                searchStart = previousEnd;
            }
            parts.add(source.slice(previousEnd, source.length() - previousEnd));
            return Collections.unmodifiableList(parts);
        }

        @Override
        public Slice replace(Slice source, Slice replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            if (spanMatcher == null) {
                return super.replace(source, replacement);
            }
            if (replacementNeedsCapturingGroups(replacement)) {
                return super.replace(source, replacement);
            }

            DynamicSliceOutput output = new DynamicSliceOutput(source.length() + replacement.length());
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matched = true;
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                output.writeBytes(source, previousEnd, matchStart - previousEnd);
                appendReplacement(output, replacement, source, matchStart, matchEnd);
                previousEnd = matchEnd;
                searchStart = matchEnd;
            }
            if (!matched) {
                return source;
            }
            output.writeBytes(source, previousEnd, source.length() - previousEnd);
            return output.slice();
        }

        @Override
        public Slice replace(Slice source, Function<List<Slice>, Slice> replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            DynamicSliceOutput output = new DynamicSliceOutput(source.length());
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            List<Slice> groups = List.of();
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matched = true;
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                output.writeBytes(source, previousEnd, matchStart - previousEnd);
                Slice replacementValue = replacement.apply(groups);
                if (replacementValue == null) {
                    return null;
                }
                output.appendBytes(replacementValue);
                previousEnd = matchEnd;
                searchStart = matchEnd;
            }
            if (!matched) {
                return source;
            }
            output.writeBytes(source, previousEnd, source.length() - previousEnd);
            return output.slice();
        }
    }

    static final class FixedWidthByteTrinoRegexp
            extends DotStarLiteralTrinoRegexp
    {
        private final FixedWidthByteSpanMatcher spanMatcher;

        FixedWidthByteTrinoRegexp(Re2 pattern, FixedWidthByteSpanMatcher spanMatcher)
        {
            super(pattern);
            this.spanMatcher = requireNonNull(spanMatcher, "spanMatcher is null");
        }

        @Override
        public TrinoRegexpMatcher matcher(Slice source, int retainedCapturingGroupCount)
        {
            requireNonNull(source, "source is null");
            if (retainedCapturingGroupCount != 0) {
                throw new IllegalArgumentException("capturingGroupCount out of range: " + retainedCapturingGroupCount);
            }
            return new TrinoRegexpMatcher(spanMatcher, source);
        }

        @Override
        public boolean contains(Slice source)
        {
            requireNonNull(source, "source is null");
            return spanMatcher.findSpan(source, 0) >= 0;
        }

        @Override
        public long count(Slice source)
        {
            requireNonNull(source, "source is null");
            long count = 0;
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                count++;
                searchStart = spanEnd(span);
            }
            return count;
        }

        @Override
        public long position(Slice source, long start, long occurrence)
        {
            requireNonNull(source, "source is null");
            if (start < 1) {
                throw new IllegalArgumentException("start position cannot be smaller than 1");
            }
            if (occurrence < 1) {
                throw new IllegalArgumentException("occurrence cannot be smaller than 1");
            }

            long codePointStart = start - 1;
            if (codePointStart > source.length()) {
                return -1;
            }
            int byteStart = SliceUtf8.offsetOfCodePoint(source, toIntExact(codePointStart));
            if (byteStart < 0) {
                return -1;
            }

            long span = spanMatcher.findSpan(source, byteStart);
            for (long match = 1; match < occurrence && span >= 0; match++) {
                span = spanMatcher.findSpan(source, spanEnd(span));
            }
            if (span < 0) {
                return -1;
            }
            return SliceUtf8.countCodePoints(source, 0, spanStart(span)) + 1L;
        }

        @Override
        public Slice extract(Slice source, int group)
        {
            validateGroup(group);
            requireNonNull(source, "source is null");
            long span = spanMatcher.findSpan(source, 0);
            return span < 0 ? null : source.slice(spanStart(span), spanEnd(span) - spanStart(span));
        }

        @Override
        public List<Slice> extractAll(Slice source, int group)
        {
            validateGroup(group);
            requireNonNull(source, "source is null");
            List<Slice> matches = new ArrayList<>();
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matches.add(source.slice(spanStart(span), spanEnd(span) - spanStart(span)));
                searchStart = spanEnd(span);
            }
            return Collections.unmodifiableList(matches);
        }

        @Override
        public List<Slice> split(Slice source)
        {
            requireNonNull(source, "source is null");
            List<Slice> parts = new ArrayList<>();
            int previousEnd = 0;
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                parts.add(source.slice(previousEnd, spanStart(span) - previousEnd));
                previousEnd = spanEnd(span);
                searchStart = previousEnd;
            }
            parts.add(source.slice(previousEnd, source.length() - previousEnd));
            return Collections.unmodifiableList(parts);
        }

        @Override
        public Slice replace(Slice source, Slice replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            if (replacementNeedsCapturingGroups(replacement)) {
                return replaceWithCapturingGroups(source, replacement);
            }

            DynamicSliceOutput output = new DynamicSliceOutput(source.length() + replacement.length());
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matched = true;
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                output.writeBytes(source, previousEnd, matchStart - previousEnd);
                appendReplacement(output, replacement, source, matchStart, matchEnd);
                previousEnd = matchEnd;
                searchStart = matchEnd;
            }
            if (!matched) {
                return source;
            }
            output.writeBytes(source, previousEnd, source.length() - previousEnd);
            return output.slice();
        }

        private Slice replaceWithCapturingGroups(Slice source, Slice replacement)
        {
            if (!pattern().hasRetainedFixedWidthByteSpanMatcher()) {
                return super.replace(source, replacement);
            }
            long span = spanMatcher.findSpan(source, 0);
            if (span < 0) {
                return source;
            }
            appendReplacement(
                    new DynamicSliceOutput(replacement.length()),
                    replacement,
                    source,
                    spanStart(span),
                    spanEnd(span));
            throw new AssertionError("replacement requiring captures was accepted without captures");
        }

        @Override
        public Slice replace(Slice source, Function<List<Slice>, Slice> replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            DynamicSliceOutput output = new DynamicSliceOutput(source.length());
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            List<Slice> groups = List.of();
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matched = true;
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                output.writeBytes(source, previousEnd, matchStart - previousEnd);
                Slice replacementValue = replacement.apply(groups);
                if (replacementValue == null) {
                    return null;
                }
                output.appendBytes(replacementValue);
                previousEnd = matchEnd;
                searchStart = matchEnd;
            }
            if (!matched) {
                return source;
            }
            output.writeBytes(source, previousEnd, source.length() - previousEnd);
            return output.slice();
        }
    }

    private static final class LiteralAlternationTrinoRegexp
            extends TrinoRegexp
    {
        private final LiteralAlternationSpanMatcher spanMatcher;

        private LiteralAlternationTrinoRegexp(LiteralAlternationSpanMatcher spanMatcher)
        {
            super();
            this.spanMatcher = requireNonNull(spanMatcher, "spanMatcher is null");
        }

        @Override
        LiteralAlternationSpanMatcher literalAlternationSpanMatcher()
        {
            return spanMatcher;
        }

        @Override
        public int capturingGroupCount()
        {
            return 0;
        }

        @Override
        public Map<String, Integer> namedCapturingGroups()
        {
            return Map.of();
        }

        @Override
        public boolean contains(Slice source)
        {
            requireNonNull(source, "source is null");
            return spanMatcher.findSpan(source, 0) >= 0;
        }

        @Override
        public long count(Slice source)
        {
            requireNonNull(source, "source is null");
            long count = 0;
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                count++;
                searchStart = spanEnd(span);
            }
            return count;
        }

        @Override
        public long position(Slice source, long start, long occurrence)
        {
            requireNonNull(source, "source is null");
            if (start < 1) {
                throw new IllegalArgumentException("start position cannot be smaller than 1");
            }
            if (occurrence < 1) {
                throw new IllegalArgumentException("occurrence cannot be smaller than 1");
            }

            long codePointStart = start - 1;
            if (codePointStart > source.length()) {
                return -1;
            }
            int byteStart = SliceUtf8.offsetOfCodePoint(source, toIntExact(codePointStart));
            if (byteStart < 0) {
                return -1;
            }

            long span = spanMatcher.findSpan(source, byteStart);
            for (long match = 1; match < occurrence && span >= 0; match++) {
                span = spanMatcher.findSpan(source, spanEnd(span));
            }
            if (span < 0) {
                return -1;
            }
            return SliceUtf8.countCodePoints(source, 0, spanStart(span)) + 1L;
        }

        @Override
        public Slice extract(Slice source, int group)
        {
            validateCaptureFreeGroup(group);
            requireNonNull(source, "source is null");
            long span = spanMatcher.findSpan(source, 0);
            return span < 0 ? null : source.slice(spanStart(span), spanEnd(span) - spanStart(span));
        }

        @Override
        public List<Slice> extractAll(Slice source, int group)
        {
            validateCaptureFreeGroup(group);
            requireNonNull(source, "source is null");
            List<Slice> matches = new ArrayList<>();
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matches.add(source.slice(spanStart(span), spanEnd(span) - spanStart(span)));
                searchStart = spanEnd(span);
            }
            return Collections.unmodifiableList(matches);
        }

        @Override
        public List<Slice> split(Slice source)
        {
            requireNonNull(source, "source is null");
            List<Slice> parts = new ArrayList<>();
            int previousEnd = 0;
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                parts.add(source.slice(previousEnd, spanStart(span) - previousEnd));
                previousEnd = spanEnd(span);
                searchStart = previousEnd;
            }
            parts.add(source.slice(previousEnd, source.length() - previousEnd));
            return Collections.unmodifiableList(parts);
        }

        @Override
        public Slice replace(Slice source, Slice replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            if (TrinoRegexp.replacementNeedsCapturingGroups(replacement, 0)) {
                return replaceWithCapturingGroups(source, replacement);
            }

            DynamicSliceOutput output = new DynamicSliceOutput(source.length() + replacement.length());
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matched = true;
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                output.writeBytes(source, previousEnd, matchStart - previousEnd);
                appendReplacement(output, replacement, source, matchStart, matchEnd);
                previousEnd = matchEnd;
                searchStart = matchEnd;
            }
            if (!matched) {
                return source;
            }
            output.writeBytes(source, previousEnd, source.length() - previousEnd);
            return output.slice();
        }

        private Slice replaceWithCapturingGroups(Slice source, Slice replacement)
        {
            long span = spanMatcher.findSpan(source, 0);
            if (span < 0) {
                return source;
            }
            appendReplacement(
                    new DynamicSliceOutput(replacement.length()),
                    replacement,
                    source,
                    spanStart(span),
                    spanEnd(span));
            throw new AssertionError("replacement requiring captures was accepted without captures");
        }

        @Override
        public Slice replace(Slice source, Function<List<Slice>, Slice> replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            DynamicSliceOutput output = new DynamicSliceOutput(source.length());
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            List<Slice> groups = List.of();
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matched = true;
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                output.writeBytes(source, previousEnd, matchStart - previousEnd);
                Slice replacementValue = replacement.apply(groups);
                if (replacementValue == null) {
                    return null;
                }
                output.appendBytes(replacementValue);
                previousEnd = matchEnd;
                searchStart = matchEnd;
            }
            if (!matched) {
                return source;
            }
            output.writeBytes(source, previousEnd, source.length() - previousEnd);
            return output.slice();
        }

        private static void validateCaptureFreeGroup(int group)
        {
            if (group < 0) {
                throw new IllegalArgumentException("group cannot be negative");
            }
            if (group > 0) {
                throw new IllegalArgumentException("pattern has 0 groups; cannot access group " + group);
            }
        }
    }

    private static final class ExactLiteralTrinoRegexp
            extends TrinoRegexp
    {
        private ExactLiteralTrinoRegexp(Re2 pattern)
        {
            super(pattern);
        }

        @Override
        public long count(Slice source)
        {
            requireNonNull(source, "source is null");
            long count = 0;
            int searchStart = 0;
            while (searchStart <= source.length()) {
                long span = pattern().findExactLiteralSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                count++;
                searchStart = nextExactLiteralSearchStart(source, span);
            }
            return count;
        }

        @Override
        public long position(Slice source, long start, long occurrence)
        {
            requireNonNull(source, "source is null");
            if (start < 1) {
                throw new IllegalArgumentException("start position cannot be smaller than 1");
            }
            if (occurrence < 1) {
                throw new IllegalArgumentException("occurrence cannot be smaller than 1");
            }

            long codePointStart = start - 1;
            if (codePointStart > source.length()) {
                return -1;
            }
            int byteStart = SliceUtf8.offsetOfCodePoint(source, toIntExact(codePointStart));
            if (byteStart < 0) {
                return -1;
            }

            long span = pattern().findExactLiteralSpan(source, byteStart);
            for (long match = 1; match < occurrence && span >= 0; match++) {
                span = pattern().findExactLiteralSpan(source, nextExactLiteralSearchStart(source, span));
            }
            if (span < 0) {
                return -1;
            }
            return SliceUtf8.countCodePoints(source, 0, spanStart(span)) + 1L;
        }

        @Override
        public Slice extract(Slice source, int group)
        {
            validateGroup(group);
            requireNonNull(source, "source is null");
            if (group != 0) {
                return super.extract(source, group);
            }
            long span = pattern().findExactLiteralSpan(source, 0);
            return span < 0 ? null : source.slice(spanStart(span), spanEnd(span) - spanStart(span));
        }

        @Override
        public List<Slice> extractAll(Slice source, int group)
        {
            validateGroup(group);
            requireNonNull(source, "source is null");
            if (group != 0) {
                return super.extractAll(source, group);
            }

            List<Slice> matches = new ArrayList<>();
            int searchStart = 0;
            while (searchStart <= source.length()) {
                long span = pattern().findExactLiteralSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matches.add(source.slice(spanStart(span), spanEnd(span) - spanStart(span)));
                searchStart = nextExactLiteralSearchStart(source, span);
            }
            return Collections.unmodifiableList(matches);
        }

        @Override
        public List<Slice> split(Slice source)
        {
            requireNonNull(source, "source is null");
            List<Slice> parts = new ArrayList<>();
            int previousEnd = 0;
            int searchStart = 0;
            while (searchStart <= source.length()) {
                long span = pattern().findExactLiteralSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                parts.add(source.slice(previousEnd, spanStart(span) - previousEnd));
                previousEnd = spanEnd(span);
                searchStart = nextExactLiteralSearchStart(source, span);
            }
            parts.add(source.slice(previousEnd, source.length() - previousEnd));
            return Collections.unmodifiableList(parts);
        }

        @Override
        public Slice replace(Slice source, Slice replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            if (replacementNeedsCapturingGroups(replacement)) {
                return super.replace(source, replacement);
            }

            DynamicSliceOutput output = new DynamicSliceOutput(source.length() + replacement.length());
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            while (searchStart <= source.length()) {
                long span = pattern().findExactLiteralSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matched = true;
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                output.writeBytes(source, previousEnd, matchStart - previousEnd);
                appendReplacement(output, replacement, source, matchStart, matchEnd);
                previousEnd = matchEnd;
                searchStart = nextExactLiteralSearchStart(source, span);
            }
            if (!matched) {
                return source;
            }
            output.writeBytes(source, previousEnd, source.length() - previousEnd);
            return output.slice();
        }

        @Override
        public Slice replace(Slice source, Function<List<Slice>, Slice> replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            if (pattern().capturingGroupCount() != 0) {
                return super.replace(source, replacement);
            }

            DynamicSliceOutput output = new DynamicSliceOutput(source.length());
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            List<Slice> groups = List.of();
            while (searchStart <= source.length()) {
                long span = pattern().findExactLiteralSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matched = true;
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                output.writeBytes(source, previousEnd, matchStart - previousEnd);
                Slice replacementValue = replacement.apply(groups);
                if (replacementValue == null) {
                    return null;
                }
                output.appendBytes(replacementValue);
                previousEnd = matchEnd;
                searchStart = nextExactLiteralSearchStart(source, span);
            }
            if (!matched) {
                return source;
            }
            output.writeBytes(source, previousEnd, source.length() - previousEnd);
            return output.slice();
        }
    }

    private static final class CharacterClassTrinoRegexp
            extends TrinoRegexp
    {
        private CharacterClassTrinoRegexp(Re2 pattern)
        {
            super(pattern);
        }

        @Override
        public long position(Slice source, long start, long occurrence)
        {
            requireNonNull(source, "source is null");
            if (start < 1) {
                throw new IllegalArgumentException("start position cannot be smaller than 1");
            }
            if (occurrence < 1) {
                throw new IllegalArgumentException("occurrence cannot be smaller than 1");
            }

            long codePointStart = start - 1;
            if (codePointStart > source.length()) {
                return -1;
            }
            int byteStart = SliceUtf8.offsetOfCodePoint(source, toIntExact(codePointStart));
            if (byteStart < 0) {
                return -1;
            }

            long span = pattern().findCharacterClassSpan(source, byteStart);
            for (long match = 1; match < occurrence && span >= 0; match++) {
                span = pattern().findCharacterClassSpan(source, spanEnd(span));
            }
            if (span < 0) {
                return -1;
            }
            return SliceUtf8.countCodePoints(source, 0, spanStart(span)) + 1L;
        }

        @Override
        public Slice extract(Slice source, int group)
        {
            validateGroup(group);
            requireNonNull(source, "source is null");
            if (group != 0) {
                return super.extract(source, group);
            }
            long span = pattern().findCharacterClassSpan(source, 0);
            return span < 0 ? null : source.slice(spanStart(span), spanEnd(span) - spanStart(span));
        }

        @Override
        public List<Slice> extractAll(Slice source, int group)
        {
            validateGroup(group);
            requireNonNull(source, "source is null");
            if (group != 0) {
                return super.extractAll(source, group);
            }

            List<Slice> matches = new ArrayList<>();
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = pattern().findCharacterClassSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matches.add(source.slice(spanStart(span), spanEnd(span) - spanStart(span)));
                searchStart = spanEnd(span);
            }
            return Collections.unmodifiableList(matches);
        }

        @Override
        public List<Slice> split(Slice source)
        {
            requireNonNull(source, "source is null");
            List<Slice> parts = new ArrayList<>();
            int previousEnd = 0;
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = pattern().findCharacterClassSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                parts.add(source.slice(previousEnd, spanStart(span) - previousEnd));
                previousEnd = spanEnd(span);
                searchStart = previousEnd;
            }
            parts.add(source.slice(previousEnd, source.length() - previousEnd));
            return Collections.unmodifiableList(parts);
        }

        @Override
        public Slice replace(Slice source, Slice replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            if (replacementNeedsCapturingGroups(replacement)) {
                return super.replace(source, replacement);
            }

            DynamicSliceOutput output = new DynamicSliceOutput(source.length() + replacement.length());
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            while (searchStart < source.length()) {
                long span = pattern().findCharacterClassSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matched = true;
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                output.writeBytes(source, previousEnd, matchStart - previousEnd);
                appendReplacement(output, replacement, source, matchStart, matchEnd);
                previousEnd = matchEnd;
                searchStart = matchEnd;
            }
            if (!matched) {
                return source;
            }
            output.writeBytes(source, previousEnd, source.length() - previousEnd);
            return output.slice();
        }

        @Override
        public Slice replace(Slice source, Function<List<Slice>, Slice> replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            if (pattern().capturingGroupCount() != 0) {
                return super.replace(source, replacement);
            }

            DynamicSliceOutput output = new DynamicSliceOutput(source.length());
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            List<Slice> groups = List.of();
            while (searchStart < source.length()) {
                long span = pattern().findCharacterClassSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                matched = true;
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                output.writeBytes(source, previousEnd, matchStart - previousEnd);
                Slice replacementValue = replacement.apply(groups);
                if (replacementValue == null) {
                    return null;
                }
                output.appendBytes(replacementValue);
                previousEnd = matchEnd;
                searchStart = matchEnd;
            }
            if (!matched) {
                return source;
            }
            output.writeBytes(source, previousEnd, source.length() - previousEnd);
            return output.slice();
        }
    }

    private static int spanStart(long span)
    {
        return (int) (span >>> 32);
    }

    private static int spanEnd(long span)
    {
        return (int) span;
    }

    private static int nextExactLiteralSearchStart(Slice source, long span)
    {
        int matchStart = spanStart(span);
        int matchEnd = spanEnd(span);
        if (matchStart != matchEnd) {
            return matchEnd;
        }
        if (matchEnd == source.length()) {
            return source.length() + 1;
        }
        return matchEnd + SliceUtf8.lengthOfCodePointSafe(
                source.byteArray(),
                source.byteArrayOffset(),
                source.length(),
                matchEnd);
    }

    private Re2Matcher newMatcher(Slice source)
    {
        if (pattern.capturingGroupCount() != 0) {
            return pattern.matcher(source);
        }
        SingleByteMatcher byteMatcher = singleByteMatcher();
        return pattern.matcher(source, byteMatcher == SingleByteMatcher.unsupported() ? null : byteMatcher);
    }

    private Re2Matcher newGroupZeroMatcher(Slice source)
    {
        SingleByteMatcher byteMatcher = singleByteMatcher();
        return pattern.groupZeroMatcher(source, byteMatcher == SingleByteMatcher.unsupported() ? null : byteMatcher);
    }

    private SingleByteRepeatMatcher.Cursor newSingleByteRepeatCursor(Slice source, boolean capturingGroupsRequired)
    {
        if (!mayHaveSingleByteRepeatMatcher || (capturingGroupsRequired && pattern.capturingGroupCount() != 0)) {
            return null;
        }
        SingleByteRepeatMatcher repeatMatcher = singleByteRepeatMatcher();
        return repeatMatcher == SingleByteRepeatMatcher.unsupported() ? null : repeatMatcher.matcher(source);
    }

    private SingleByteMatcher singleByteMatcher()
    {
        SingleByteMatcher byteMatcher = pattern.sharedSingleByteMatcher();
        return byteMatcher == null ? SingleByteMatcher.unsupported() : byteMatcher;
    }

    private SingleByteRepeatMatcher singleByteRepeatMatcher()
    {
        if (!mayHaveSingleByteRepeatMatcher) {
            return SingleByteRepeatMatcher.unsupported();
        }
        SingleByteRepeatMatcher repeatMatcher = singleByteRepeatMatcher;
        if (repeatMatcher == null) {
            repeatMatcher = pattern.createSingleByteRepeatMatcher();
            if (repeatMatcher == null) {
                repeatMatcher = SingleByteRepeatMatcher.unsupported();
            }
            singleByteRepeatMatcher = repeatMatcher;
        }
        return repeatMatcher;
    }
}
