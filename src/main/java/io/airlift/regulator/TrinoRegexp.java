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
import io.airlift.regulator.Re2.ScanPlanMode;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceUtf8;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/// Trino SQL regular-expression operations over UTF-8 Slice inputs.
///
/// Function behavior follows Trino's shared regexp contract, including UTF-8 code-point positions,
/// empty-match iteration, trailing split fields, and Java-style replacement references. Patterns use
/// the supported Trino fork of Joni's Java-syntax language documented in
/// `docs/integrations/REGEXP_LANGUAGES.md`; unsupported constructs are rejected during compilation.
public sealed class TrinoRegexp
        permits TrinoRegexp.BeginLineTrinoRegexp,
                TrinoRegexp.CharacterClassTrinoRegexp,
                TrinoRegexp.CompiledScanTrinoRegexp,
                TrinoRegexp.DotStarLiteralTrinoRegexp,
                TrinoRegexp.ExactLiteralTrinoRegexp,
                TrinoRegexp.LiteralAlternationTrinoRegexp,
                TrinoRegexp.OrderedLiteralTrinoRegexp
{
    /// Mutable engine resource options. Compilation snapshots every option, so later changes do not
    /// affect an existing [TrinoRegexp]. Options instances are not thread-safe.
    public static final class Options
    {
        private long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
        private ByteFrequencies byteFrequencies = ByteFrequencies.uniform();
        private boolean generateBytecode;

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

        ByteFrequencies byteFrequencies()
        {
            return byteFrequencies;
        }

        /// Sets the byte-frequency distribution that chooses which byte of a literal a candidate
        /// scan probes. Compilation keeps only the chosen offsets, not the distribution. The default,
        /// [ByteFrequencies#uniform()], keeps first-byte scanning.
        public Options setByteFrequencies(ByteFrequencies byteFrequencies)
        {
            this.byteFrequencies = requireNonNull(byteFrequencies, "byteFrequencies is null");
            return this;
        }

        boolean generateBytecode()
        {
            return generateBytecode;
        }

        /// Experimental. When enabled, compilation generates a hidden class for an eligible pattern,
        /// so each operation that would run its specialized scan runs dedicated bytecode instead.
        /// Operations that take another route keep it. Patterns that are not eligible, or whose
        /// class cannot be generated, compile as they do without this option. Disabled by default.
        ///
        /// @see TrinoRegexp#usesGeneratedBytecode()
        public Options setGenerateBytecode(boolean generateBytecode)
        {
            this.generateBytecode = generateBytecode;
            return this;
        }
    }

    // TrinoRegexp.Options has no syntax settings, so parsing always starts from the same flags.
    private static final int PARSE_FLAGS = Regexp.LIKE_PERL;

    private final Re2 re2;
    private final boolean mayHaveSingleByteRepeatMatcher;
    private volatile SingleByteRepeatMatcher singleByteRepeatMatcher;
    private volatile TrinoReplacementPlan.CachedTemplate replacementTemplate;

    private TrinoRegexp()
    {
        this.re2 = null;
        this.mayHaveSingleByteRepeatMatcher = false;
    }

    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "The sealed hierarchy permits only private final nested implementations")
    private TrinoRegexp(Re2 re2)
    {
        this.re2 = requireNonNull(re2, "re2 is null");
        this.mayHaveSingleByteRepeatMatcher = re2.canMatchEmpty();
    }

    /// Validates a pattern and returns its capture metadata without constructing a matching
    /// engine. Parse errors and capture numbering are the same as for [#compile(Slice)];
    /// compilation memory limits are not checked.
    public static PatternInfo parse(Slice pattern)
    {
        return parse(pattern, Options.defaults());
    }

    /// Parses as [#parse(Slice)] does. [Options] has no syntax settings, so parsing always starts
    /// from the same flags and `options` does not change the result; the overload matches the
    /// other languages' parse methods.
    public static PatternInfo parse(Slice pattern, Options options)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        return TrinoRegexpParser.parse(pattern, PARSE_FLAGS).patternInfo();
    }

    public static TrinoRegexp compile(Slice pattern)
    {
        return compile(pattern, Options.defaults());
    }

    /// Compiles a copied snapshot of `pattern` and the current option values.
    public static TrinoRegexp compile(Slice pattern, Options options)
    {
        return compile(pattern, options, ScanPlanMode.DEFAULT);
    }

    /// Compiles `pattern` so that every unanchored scan-plan search continues on the
    /// ordinary engine before its first attempt, exercising that continuation on every input.
    static TrinoRegexp compileForcingScanPlanHandoffForTesting(Slice pattern)
    {
        return compileForcingScanPlanHandoffForTesting(pattern, Options.defaults());
    }

    static TrinoRegexp compileForcingScanPlanHandoffForTesting(Slice pattern, Options options)
    {
        return compile(pattern, options, ScanPlanMode.FORCE_HANDOFF);
    }

    /// Compiles `pattern` as [#compile(Slice)] does, but builds no scan plan, so the
    /// searches the plan would run use the ordinary engine.
    static TrinoRegexp compileWithoutScanPlanForTesting(Slice pattern)
    {
        return compile(pattern, Options.defaults(), ScanPlanMode.DISABLED);
    }

    private static TrinoRegexp compile(Slice pattern, Options options, ScanPlanMode scanPlanMode)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        Slice patternCopy = pattern.copy();
        ParseResult parsed = TrinoRegexpParser.parse(patternCopy, PARSE_FLAGS);
        LiteralAlternationSpanMatcher literalAlternationMatcher = LiteralAlternationSpanMatcher.analyze(
                parsed.regexp(),
                parsed.capturingGroupCount());
        long forwardMemory = Math.max(1, options.maxMemory() - Math.max(1, options.maxMemory() / 3));
        if (literalAlternationMatcher != null && literalAlternationMatcher.estimatedRetainedSize() <= forwardMemory) {
            return new LiteralAlternationTrinoRegexp(patternCopy, literalAlternationMatcher);
        }
        Re2 compiledPattern = Re2.compileParsedForTrino(patternCopy, parsed, PARSE_FLAGS, options.maxMemory(), options.byteFrequencies(), scanPlanMode);
        if (parsed.regexp().op() == RegexpOp.BEGIN_LINE &&
                (parsed.regexp().parseFlags() & Regexp.TRINO_LINE) != 0) {
            return new BeginLineTrinoRegexp(compiledPattern);
        }
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
        if (options.generateBytecode()) {
            TrinoRegexp generated = CompiledScanTrinoRegexp.create(compiledPattern);
            if (generated != null) {
                return generated;
            }
        }
        return new TrinoRegexp(compiledPattern);
    }

    /// Creates a mutable matcher retaining every capturing group. Matching uses the Trino language
    /// and existing regex execution machinery; it does not build extraction or replacement outputs.
    public TrinoRegexpMatcher matcher(Slice source)
    {
        return matcher(source, capturingGroupCount());
    }

    /// Creates a reusable matcher retaining group zero and the requested prefix of capturing groups.
    /// Use zero when only complete-match boundaries are required. This does not change the specialized
    /// execution routes used by the other Trino operations.
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
                re2.matcher(source, retainedCapturingGroupCount));
    }

    Re2 re2()
    {
        return re2;
    }

    /// Returns whether this compiled pattern has a class generated for it, which only
    /// [Options#setGenerateBytecode(boolean)] enables. Each operation of such a pattern that
    /// would run its specialized scan runs the generated class, and the `generated`
    /// accessors return its entries.
    public boolean usesGeneratedBytecode()
    {
        return false;
    }

    /// Returns a handle on the generated code of [#contains(Slice)], of type
    /// `(Slice)boolean`, or empty when this pattern runs another route for it. Like every
    /// generated handle, it checks arguments as the operation does, except that it requires
    /// non-null arguments but does not check them.
    public Optional<MethodHandle> generatedContains()
    {
        return Optional.empty();
    }

    /// Returns a handle on the generated code of [#count(Slice)], of type
    /// `(Slice)long`, or empty when this pattern runs another route for it.
    public Optional<MethodHandle> generatedCount()
    {
        return Optional.empty();
    }

    /// Returns a handle on the generated code of [#position(Slice,long,long)], of type
    /// `(Slice,long,long)long`, or empty when this pattern runs another route for it.
    public Optional<MethodHandle> generatedPosition()
    {
        return Optional.empty();
    }

    /// Returns a handle on the generated code of [#extract(Slice,int)] for `group`,
    /// of type `(Slice)Slice`, or empty when this pattern runs another route for it.
    ///
    /// @throws IllegalArgumentException if the pattern has no such group
    public Optional<MethodHandle> generatedExtract(int group)
    {
        validateHandleGroup(group);
        return Optional.empty();
    }

    /// Returns a handle on the generated code of [#extractAll(Slice,int)] for
    /// `group`, of type `(Slice)List`, or empty when this pattern runs another route
    /// for it.
    ///
    /// @throws IllegalArgumentException if the pattern has no such group
    public Optional<MethodHandle> generatedExtractAll(int group)
    {
        validateHandleGroup(group);
        return Optional.empty();
    }

    /// Returns a handle on the generated code of [#split(Slice)], of type
    /// `(Slice)List`, or empty when this pattern runs another route for it.
    public Optional<MethodHandle> generatedSplit()
    {
        return Optional.empty();
    }

    /// Returns a handle on the generated code of [#replace(Slice,Slice)], of type
    /// `(Slice,Slice)Slice`, or empty when this pattern runs another route for it.
    public Optional<MethodHandle> generatedReplace()
    {
        return Optional.empty();
    }

    /// Returns a handle on the generated code of [#replace(Slice,Function)], of type
    /// `(Slice,Function)Slice`, or empty when this pattern runs another route for it.
    public Optional<MethodHandle> generatedReplaceWithCallback()
    {
        return Optional.empty();
    }

    /// Rejects a group the pattern does not have, as [#extract(Slice,int)] does, for
    /// subclasses without a compiled pattern too.
    final void validateHandleGroup(int group)
    {
        if (group < 0) {
            throw new IllegalArgumentException("group cannot be negative");
        }
        if (group > capturingGroupCount()) {
            throw new IllegalArgumentException("pattern has " + capturingGroupCount() + " groups; cannot access group " + group);
        }
    }

    /// Returns the generated static method holding this pattern's scan attempt loop, with the
    /// signature of the plan executor it replaces, or `null` without a generated class.
    MethodHandle attemptHandleForDiagnostics()
    {
        return null;
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

    boolean usesDotStarLiteralSpanMatcherForDiagnostics()
    {
        return false;
    }

    boolean usesTrinoBeginLineCountForDiagnostics()
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
        return re2 != null && re2.isSingleByteMatcherComputed();
    }

    /// Returns a copy of the compiled pattern bytes.
    public Slice pattern()
    {
        return re2.pattern();
    }

    /// Returns the number of capturing groups, excluding group zero (the whole match).
    public int capturingGroupCount()
    {
        return re2.capturingGroupCount();
    }

    /// Returns named capturing groups mapped to their one-based group indexes.
    public Map<String, Integer> namedCapturingGroups()
    {
        return re2.namedCapturingGroups();
    }

    /// Returns an immutable mapping from one-based group indexes to capture names.
    public Map<Integer, String> capturingGroupNames()
    {
        return re2.capturingGroupNames();
    }

    /// Returns whether the source contains a match.
    public boolean contains(Slice source)
    {
        requireNonNull(source, "source is null");
        return re2.find(source);
    }

    private static final class BeginLineTrinoRegexp
            extends TrinoRegexp
    {
        private BeginLineTrinoRegexp(Re2 re2)
        {
            super(re2);
        }

        @Override
        boolean usesTrinoBeginLineCountForDiagnostics()
        {
            return true;
        }

        @Override
        public long count(Slice source)
        {
            requireNonNull(source, "source is null");
            byte[] bytes = source.byteArray();
            int start = source.byteArrayOffset();
            int scanEnd = start + source.length() - 1;
            long count = 1;
            for (int position = start; position < scanEnd; position++) {
                if (bytes[position] == '\n') {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class OrderedLiteralTrinoRegexp
            extends TrinoRegexp
    {
        private final OrderedLiteralMatcher matcher;

        private OrderedLiteralTrinoRegexp(Re2 re2, OrderedLiteralMatcher matcher)
        {
            super(re2);
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

    /// Returns the number of non-overlapping matches, including empty matches.
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

        long optimizedCount = re2.countMatches(source);
        if (optimizedCount >= 0) {
            return optimizedCount;
        }

        RegexpMatcher matcher = newGroupZeroMatcher(source);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /// Returns the one-based code-point position of the first match, or `-1`.
    public long position(Slice source)
    {
        return position(source, 1, 1);
    }

    /// Returns the one-based code-point position of the first match at or after `start`, or
    /// `-1`. The start position is one-based. As in Trino, a start past the last code point, as
    /// on an empty source, finds nothing, even where the pattern matches empty text, while an
    /// earlier start can still find an empty match at the end: `$` on `a` from 1 returns 2.
    public long position(Slice source, long start)
    {
        return position(source, start, 1);
    }

    /// Returns the one-based code-point position of the requested match, or `-1`.
    ///
    /// @throws IllegalArgumentException if `start` or `occurrence` is less than one
    public long position(Slice source, long start, long occurrence)
    {
        requireNonNull(source, "source is null");
        // The checks and offset conversion are copied as positionSearchStart; keep the copy in
        // step.
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

        RegexpMatcher matcher = newGroupZeroMatcher(source);
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

    /// Returns the byte offset where [#position(Slice,long,long)] starts searching, or
    /// `-1` when no match can follow `start`, with the same argument checks, for
    /// generated position entries.
    /// A copy of the checks and offset conversion of `position(Slice, long, long)`.
    ///
    /// @throws IllegalArgumentException if `start` or `occurrence` is less than one
    static int positionSearchStart(Slice source, long start, long occurrence)
    {
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
        return SliceUtf8.offsetOfCodePoint(source, toIntExact(codePointStart));
    }

    /// Returns a zero-copy view of the first complete match, or `null`.
    public Slice extract(Slice source)
    {
        return extract(source, 0);
    }

    /// Returns a zero-copy view of a group from the first match, or `null` when there is no
    /// match or the group did not participate. Group zero is the complete match.
    public Slice extract(Slice source, int group)
    {
        validateGroup(group);
        requireNonNull(source, "source is null");
        ScanPlan plan = startAnchoredExtractPlan(group);
        if (plan != null) {
            return extractWithStartAnchoredScanPlan(plan, source, group);
        }
        RegexpMatcher matcher = group == 0 ? newGroupZeroMatcher(source) : newMatcher(source);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(group);
    }

    /// Returns the start-anchored scan plan that [#extract(Slice,int)] runs without a
    /// matcher for `group`, or `null` when it builds one.
    private ScanPlan startAnchoredExtractPlan(int group)
    {
        // A capture matcher has no single-byte matcher, so it would run this plan directly.
        return group == 0 ? null : re2.startAnchoredScanPlan();
    }

    boolean extractsWithStartAnchoredScanPlanForDiagnostics(int group)
    {
        return startAnchoredExtractPlan(group) != null;
    }

    /// Returns what [#extract(Slice,int)] returns through a matcher, without building one.
    /// Groups above `group` are not retained.
    private Slice extractWithStartAnchoredScanPlan(ScanPlan plan, Slice source, int group)
    {
        int[] groups = new int[2 * group + 2];
        if (!re2.findWithStartAnchoredScanPlan(plan, source, 0, source.length(), groups)) {
            return null;
        }
        int start = groups[2 * group];
        return start < 0 ? null : source.slice(start, groups[2 * group + 1] - start);
    }

    /// Returns zero-copy views of every complete match.
    public List<Slice> extractAll(Slice source)
    {
        return extractAll(source, 0);
    }

    /// Returns zero-copy views of the requested group from every match. Unmatched groups are
    /// represented by `null`. Group zero is the complete match.
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

        RegexpMatcher matcher = group == 0 ? newGroupZeroMatcher(source) : newMatcher(source);
        List<Slice> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(matcher.group(group));
        }
        return Collections.unmodifiableList(matches);
    }

    /// Splits around non-overlapping matches and preserves trailing empty fields. Returned values are
    /// zero-copy views of `source`.
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

        RegexpMatcher matcher = newGroupZeroMatcher(source);
        List<Slice> parts = new ArrayList<>();
        int previousEnd = 0;
        while (matcher.find()) {
            parts.add(source.slice(previousEnd, matcher.start() - previousEnd));
            previousEnd = matcher.end();
        }
        parts.add(source.slice(previousEnd, source.length() - previousEnd));
        return Collections.unmodifiableList(parts);
    }

    /// Replaces every non-overlapping match using Trino replacement syntax. Numbered references use
    /// `$1`; named references use `${name}`; backslash escapes the following byte.
    ///
    /// @throws TrinoRegexpReplacementException if the replacement is malformed or names an unknown group
    public Slice replace(Slice source, Slice replacement)
    {
        requireNonNull(source, "source is null");
        requireNonNull(replacement, "replacement is null");

        TrinoReplacementPlan replacementPlan = newReplacementPlan(source, replacement);
        int maximumCapturingGroup = replacementPlan.maximumCapturingGroup();
        SingleByteRepeatMatcher.Cursor repeatCursor = newSingleByteRepeatCursor(source, maximumCapturingGroup != 0);
        if (repeatCursor != null) {
            while (repeatCursor.find()) {
                replacementPlan.addMatch(repeatCursor.start(), repeatCursor.end());
            }
            return replacementPlan.build();
        }

        // The loop is written out rather than calling replaceWithMatcher, so C2 compiles it into
        // this method instead of reaching it through a call it declines to inline.
        RegexpMatcher matcher = maximumCapturingGroup == 0
                ? newGroupZeroMatcher(source)
                : re2.matcher(source, maximumCapturingGroup);
        while (matcher.find()) {
            replacementPlan.addMatch(matcher);
        }
        return replacementPlan.build();
    }

    final TrinoReplacementPlan newReplacementPlan(Slice source, Slice replacement)
    {
        return new TrinoReplacementPlan(source, replacement, this);
    }

    /// Returns the most recently parsed well-formed replacement, or `null`.
    final TrinoReplacementPlan.CachedTemplate cachedReplacementTemplate()
    {
        return replacementTemplate;
    }

    final void cacheReplacementTemplate(TrinoReplacementPlan.CachedTemplate template)
    {
        replacementTemplate = template;
    }

    boolean cachesReplacementForDiagnostics(Slice replacement)
    {
        TrinoReplacementPlan.CachedTemplate template = replacementTemplate;
        return template != null && template.matches(replacement);
    }

    final Slice replaceWithMatcher(Slice source, TrinoReplacementPlan replacementPlan)
    {
        int maximumCapturingGroup = replacementPlan.maximumCapturingGroup();
        RegexpMatcher matcher = maximumCapturingGroup == 0
                ? newGroupZeroMatcher(source)
                : re2.matcher(source, maximumCapturingGroup);
        while (matcher.find()) {
            replacementPlan.addMatch(matcher);
        }
        return replacementPlan.build();
    }

    /// Replaces every non-overlapping match by invoking `replacement` with capturing groups
    /// one through [#capturingGroupCount()]. Unmatched groups are `null`; group zero is
    /// not included. Returns `null` immediately if the callback returns `null`.
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

        RegexpMatcher matcher = newMatcher(source);
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

    final void validateGroup(int group)
    {
        if (group < 0) {
            throw new IllegalArgumentException("group cannot be negative");
        }
        if (group > re2.capturingGroupCount()) {
            throw new IllegalArgumentException("pattern has " + re2.capturingGroupCount() +
                    " groups; cannot access group " + group);
        }
    }

    static non-sealed class DotStarLiteralTrinoRegexp
            extends TrinoRegexp
    {
        private final DotStarLiteralSpanMatcher spanMatcher;

        private DotStarLiteralTrinoRegexp(Re2 re2, DotStarLiteralSpanMatcher spanMatcher)
        {
            super(re2);
            this.spanMatcher = spanMatcher;
        }

        DotStarLiteralTrinoRegexp(Re2 re2)
        {
            super(re2);
            this.spanMatcher = null;
        }

        @Override
        boolean usesDotStarLiteralSpanMatcherForDiagnostics()
        {
            return spanMatcher != null;
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

            TrinoReplacementPlan replacementPlan = newReplacementPlan(source, replacement);
            if (replacementPlan.maximumCapturingGroup() != 0) {
                return replaceWithMatcher(source, replacementPlan);
            }

            TrinoReplacementPlan.GroupFreeReplacer replacer = replacementPlan.groupFreeReplacer();
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                replacer.addMatch(matchStart, matchEnd);
                searchStart = matchEnd;
            }
            return replacer.build();
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

        FixedWidthByteTrinoRegexp(Re2 re2, FixedWidthByteSpanMatcher spanMatcher)
        {
            super(re2);
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

            TrinoReplacementPlan replacementPlan = newReplacementPlan(source, replacement);
            if (replacementPlan.maximumCapturingGroup() != 0) {
                return replaceWithMatcher(source, replacementPlan);
            }

            TrinoReplacementPlan.GroupFreeReplacer replacer = replacementPlan.groupFreeReplacer();
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                replacer.addMatch(matchStart, matchEnd);
                searchStart = matchEnd;
            }
            return replacer.build();
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
        // This subtype has no compiled RE2 pattern to hold the source bytes, so it keeps them.
        private final Slice patternBytes;
        private final LiteralAlternationSpanMatcher spanMatcher;

        private LiteralAlternationTrinoRegexp(Slice patternBytes, LiteralAlternationSpanMatcher spanMatcher)
        {
            super();
            this.patternBytes = requireNonNull(patternBytes, "patternBytes is null");
            this.spanMatcher = requireNonNull(spanMatcher, "spanMatcher is null");
        }

        @Override
        LiteralAlternationSpanMatcher literalAlternationSpanMatcher()
        {
            return spanMatcher;
        }

        @Override
        public Slice pattern()
        {
            return patternBytes.copy();
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
        public Map<Integer, String> capturingGroupNames()
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

            TrinoReplacementPlan.GroupFreeReplacer replacer = newReplacementPlan(source, replacement).groupFreeReplacer();
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                replacer.addMatch(matchStart, matchEnd);
                searchStart = matchEnd;
            }
            return replacer.build();
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
        private ExactLiteralTrinoRegexp(Re2 re2)
        {
            super(re2);
        }

        @Override
        public long count(Slice source)
        {
            requireNonNull(source, "source is null");
            long count = 0;
            int searchStart = 0;
            while (searchStart <= source.length()) {
                long span = re2().findExactLiteralSpan(source, searchStart);
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

            long span = re2().findExactLiteralSpan(source, byteStart);
            for (long match = 1; match < occurrence && span >= 0; match++) {
                span = re2().findExactLiteralSpan(source, nextExactLiteralSearchStart(source, span));
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
            long span = re2().findExactLiteralSpan(source, 0);
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
                long span = re2().findExactLiteralSpan(source, searchStart);
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
                long span = re2().findExactLiteralSpan(source, searchStart);
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

            TrinoReplacementPlan replacementPlan = newReplacementPlan(source, replacement);
            if (replacementPlan.maximumCapturingGroup() != 0) {
                return replaceWithMatcher(source, replacementPlan);
            }

            TrinoReplacementPlan.GroupFreeReplacer replacer = replacementPlan.groupFreeReplacer();
            int searchStart = 0;
            while (searchStart <= source.length()) {
                long span = re2().findExactLiteralSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                replacer.addMatch(matchStart, matchEnd);
                searchStart = nextExactLiteralSearchStart(source, span);
            }
            return replacer.build();
        }

        @Override
        public Slice replace(Slice source, Function<List<Slice>, Slice> replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            if (re2().capturingGroupCount() != 0) {
                return super.replace(source, replacement);
            }

            DynamicSliceOutput output = new DynamicSliceOutput(source.length());
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            List<Slice> groups = List.of();
            while (searchStart <= source.length()) {
                long span = re2().findExactLiteralSpan(source, searchStart);
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
        private CharacterClassTrinoRegexp(Re2 re2)
        {
            super(re2);
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

            long span = re2().findCharacterClassSpan(source, byteStart);
            for (long match = 1; match < occurrence && span >= 0; match++) {
                span = re2().findCharacterClassSpan(source, spanEnd(span));
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
            long span = re2().findCharacterClassSpan(source, 0);
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
                long span = re2().findCharacterClassSpan(source, searchStart);
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
                long span = re2().findCharacterClassSpan(source, searchStart);
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

            TrinoReplacementPlan replacementPlan = newReplacementPlan(source, replacement);
            if (replacementPlan.maximumCapturingGroup() != 0) {
                return replaceWithMatcher(source, replacementPlan);
            }

            TrinoReplacementPlan.GroupFreeReplacer replacer = replacementPlan.groupFreeReplacer();
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = re2().findCharacterClassSpan(source, searchStart);
                if (span < 0) {
                    break;
                }
                int matchStart = spanStart(span);
                int matchEnd = spanEnd(span);
                replacer.addMatch(matchStart, matchEnd);
                searchStart = matchEnd;
            }
            return replacer.build();
        }

        @Override
        public Slice replace(Slice source, Function<List<Slice>, Slice> replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            if (re2().capturingGroupCount() != 0) {
                return super.replace(source, replacement);
            }

            DynamicSliceOutput output = new DynamicSliceOutput(source.length());
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            List<Slice> groups = List.of();
            while (searchStart < source.length()) {
                long span = re2().findCharacterClassSpan(source, searchStart);
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

    /// A pattern whose case-sensitive scan plan was compiled to its own hidden class. Each
    /// operation keeps the route it has without the class, decided once when the pattern compiles:
    /// one that would run the plan calls the generated class once, and any other runs the inherited
    /// Java route. Short boolean calls that the interpreter hands to the ordinary engine to avoid
    /// its operation dispatch also stay on the generated class. Callers that never enable
    /// [Options#setGenerateBytecode(boolean)] never load this class.
    private static final class CompiledScanTrinoRegexp
            extends TrinoRegexp
    {
        // Whether contains reaches the plan rather than a retained literal kernel.
        private final boolean contains;
        private final ScanKernelGenerator.CompiledTrinoKernel generated;
        private final CompiledTrinoScan scan;

        private CompiledScanTrinoRegexp(Re2 re2, boolean contains, ScanKernelGenerator.CompiledTrinoKernel generated)
        {
            super(re2);
            this.contains = contains;
            this.generated = generated;
            this.scan = generated.scan();
        }

        /// Returns `re2` with a generated class, or `null` when the pattern is not
        /// eligible or the class cannot be generated. The declared type keeps verification of the
        /// base class from loading this class.
        static TrinoRegexp create(Re2 re2)
        {
            ScanKernelGenerator.TrinoRoutes routes = ScanKernelGenerator.TrinoRoutes.of(re2);
            if (routes == null) {
                return null;
            }
            ScanKernelGenerator.CompiledTrinoKernel generated;
            try {
                generated = ScanKernelGenerator.generateCompiledTrinoScan(routes);
            }
            catch (RuntimeException | LinkageError ignored) {
                // Linking the generator fails here only if a consumer excluded the classfile
                // library, and defining the class can fail too; either leaves the pattern
                // interpreted.
                return null;
            }
            return new CompiledScanTrinoRegexp(re2, routes.contains(), generated);
        }

        @Override
        public TrinoRegexpMatcher matcher(Slice source, int retainedCapturingGroupCount)
        {
            requireNonNull(source, "source is null");
            if (retainedCapturingGroupCount < 0 || retainedCapturingGroupCount > capturingGroupCount()) {
                throw new IllegalArgumentException("capturingGroupCount out of range: " + retainedCapturingGroupCount);
            }
            return TrinoRegexpMatcher.compiledScan(scan, source, retainedCapturingGroupCount);
        }

        @Override
        public boolean usesGeneratedBytecode()
        {
            return true;
        }

        @Override
        public Optional<MethodHandle> generatedContains()
        {
            return Optional.ofNullable(generated.contains());
        }

        @Override
        public Optional<MethodHandle> generatedCount()
        {
            return Optional.of(generated.count());
        }

        @Override
        public Optional<MethodHandle> generatedPosition()
        {
            return Optional.of(generated.position());
        }

        @Override
        public Optional<MethodHandle> generatedExtract(int group)
        {
            validateGroup(group);
            return Optional.of(MethodHandles.insertArguments(generated.extract(), 1, group));
        }

        @Override
        public Optional<MethodHandle> generatedExtractAll(int group)
        {
            validateGroup(group);
            return Optional.of(MethodHandles.insertArguments(generated.extractAll(), 1, group));
        }

        @Override
        public Optional<MethodHandle> generatedSplit()
        {
            return Optional.of(generated.split());
        }

        @Override
        public Optional<MethodHandle> generatedReplace()
        {
            return Optional.of(MethodHandles.insertArguments(generated.replace(), 0, this));
        }

        @Override
        public Optional<MethodHandle> generatedReplaceWithCallback()
        {
            return Optional.of(generated.replaceWithCallback());
        }

        @Override
        MethodHandle attemptHandleForDiagnostics()
        {
            return ScanKernelGenerator.attemptHandleForDiagnostics(scan, re2().caseSensitiveScanPlan());
        }

        @Override
        public boolean contains(Slice source)
        {
            if (!contains) {
                return super.contains(source);
            }
            requireNonNull(source, "source is null");
            return scan.contains(source);
        }

        @Override
        public long count(Slice source)
        {
            requireNonNull(source, "source is null");
            return scan.count(source);
        }

        @Override
        public long position(Slice source, long start, long occurrence)
        {
            requireNonNull(source, "source is null");
            return scan.position(source, start, occurrence);
        }

        @Override
        public Slice extract(Slice source, int group)
        {
            validateGroup(group);
            requireNonNull(source, "source is null");
            return scan.extract(source, group);
        }

        @Override
        public List<Slice> extractAll(Slice source, int group)
        {
            validateGroup(group);
            requireNonNull(source, "source is null");
            return scan.extractAll(source, group);
        }

        @Override
        public List<Slice> split(Slice source)
        {
            requireNonNull(source, "source is null");
            return scan.split(source);
        }

        @Override
        public Slice replace(Slice source, Slice replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            return scan.replace(this, source, replacement);
        }

        @Override
        public Slice replace(Slice source, Function<List<Slice>, Slice> replacement)
        {
            requireNonNull(source, "source is null");
            requireNonNull(replacement, "replacement is null");
            return scan.replace(source, replacement);
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

    private RegexpMatcher newMatcher(Slice source)
    {
        if (re2.capturingGroupCount() != 0) {
            return re2.matcher(source);
        }
        SingleByteMatcher byteMatcher = singleByteMatcher();
        return re2.matcher(source, byteMatcher == SingleByteMatcher.unsupported() ? null : byteMatcher);
    }

    private RegexpMatcher newGroupZeroMatcher(Slice source)
    {
        SingleByteMatcher byteMatcher = singleByteMatcher();
        return re2.groupZeroMatcher(source, byteMatcher == SingleByteMatcher.unsupported() ? null : byteMatcher);
    }

    private SingleByteRepeatMatcher.Cursor newSingleByteRepeatCursor(Slice source, boolean capturingGroupsRequired)
    {
        if (!mayHaveSingleByteRepeatMatcher || (capturingGroupsRequired && re2.capturingGroupCount() != 0)) {
            return null;
        }
        SingleByteRepeatMatcher repeatMatcher = singleByteRepeatMatcher();
        return repeatMatcher == SingleByteRepeatMatcher.unsupported() ? null : repeatMatcher.matcher(source);
    }

    private SingleByteMatcher singleByteMatcher()
    {
        SingleByteMatcher byteMatcher = re2.sharedSingleByteMatcher();
        return byteMatcher == null ? SingleByteMatcher.unsupported() : byteMatcher;
    }

    private SingleByteRepeatMatcher singleByteRepeatMatcher()
    {
        if (!mayHaveSingleByteRepeatMatcher) {
            return SingleByteRepeatMatcher.unsupported();
        }
        SingleByteRepeatMatcher repeatMatcher = singleByteRepeatMatcher;
        if (repeatMatcher == null) {
            repeatMatcher = re2.createSingleByteRepeatMatcher();
            if (repeatMatcher == null) {
                repeatMatcher = SingleByteRepeatMatcher.unsupported();
            }
            singleByteRepeatMatcher = repeatMatcher;
        }
        return repeatMatcher;
    }
}
