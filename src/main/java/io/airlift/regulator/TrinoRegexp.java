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
import io.airlift.regulator.CompiledPattern.ScanPlanMode;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.SizeOf;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceUtf8;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
                TrinoRegexp.FixedWidthByteTrinoRegexp,
                TrinoRegexp.FullCaseFoldAlternationTrinoRegexp,
                TrinoRegexp.LiteralAlternationTrinoRegexp,
                TrinoRegexp.LiteralSetTrinoRegexp,
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
        /// scan probes. Matching reads only the chosen offsets; the compiled pattern keeps the
        /// distribution only to compare it with other compiles. The default,
        /// [ByteFrequencies#uniform()], keeps first-byte scanning. The distribution also ranks the
        /// folded byte pairs and folded runs that case-insensitive prefix scans probe, decides
        /// whether a find scans for a folded run behind a leading assertion's literal in place of
        /// that literal, and decides which three-byte runs are rare enough to scan, with
        /// [ByteFrequencies#englishText()] in place of the default.
        public Options setByteFrequencies(ByteFrequencies byteFrequencies)
        {
            this.byteFrequencies = requireNonNull(byteFrequencies, "byteFrequencies is null");
            return this;
        }

        boolean generateBytecode()
        {
            return generateBytecode;
        }

        /// When enabled, compilation generates a hidden class for an eligible pattern, so each
        /// operation that would run its specialized scan runs dedicated bytecode instead.
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
    // The result of an extraction that finds no match without building a matcher: the list the
    // matcher loop returns then, which unlike List.of() answers contains(null), as a list with
    // unmatched groups does.
    private static final List<Slice> NO_MATCHES = Collections.unmodifiableList(new ArrayList<>());

    /// The inputs of a compile, which decide equality: the pattern bytes and every option that
    /// can change what the compile builds, including those that change only performance, such as
    /// the memory budget, so that a cache keyed on patterns never returns one compiled differently.
    /// Each subclass and generated class keeps the inputs of the compile that built it.
    record CompileInputs(Slice pattern, long maxMemory, ByteFrequencies byteFrequencies, boolean generateBytecode, ScanPlanMode scanPlanMode) {}

    // The compiled engine; null only in the literal-alternation and literal-set subclasses.
    private final TrinoCompiledPattern re2;
    private final CompileInputs compileInputs;
    // The hash of compileInputs, computed when the pattern compiles.
    private final int hash;
    // Whether a capture matcher's find over a whole input starts with the general route's checks
    // and the pattern keeps required bytes for them, so a capture search runs those checks before
    // it builds a matcher. See RegexpMatcher.captureFindRunsGeneralSearch.
    private final boolean checksBeforeCaptureMatcher;
    private volatile TrinoReplacementPlan.CachedTemplate replacementTemplate;

    private TrinoRegexp(CompileInputs compileInputs)
    {
        this.re2 = null;
        this.compileInputs = compileInputs;
        this.hash = compileInputs.hashCode();
        this.checksBeforeCaptureMatcher = false;
    }

    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "The sealed hierarchy permits only nested implementations; the non-sealed CompiledScanTrinoRegexp is extended only by its generated hidden class")
    private TrinoRegexp(TrinoCompiledPattern compiledPattern, CompileInputs compileInputs)
    {
        this.re2 = requireNonNull(compiledPattern, "compiledPattern is null");
        this.compileInputs = compileInputs;
        this.hash = compileInputs.hashCode();
        this.checksBeforeCaptureMatcher = compiledPattern.hasRequiredBytes() && RegexpMatcher.captureFindRunsGeneralSearch(compiledPattern);
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
        return compileWithoutScanPlanForTesting(pattern, Options.defaults());
    }

    static TrinoRegexp compileWithoutScanPlanForTesting(Slice pattern, Options options)
    {
        return compile(pattern, options, ScanPlanMode.DISABLED);
    }

    private static TrinoRegexp compile(Slice pattern, Options options, ScanPlanMode scanPlanMode)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        Slice patternCopy = pattern.copy();
        CompileInputs inputs = new CompileInputs(patternCopy, options.maxMemory(), options.byteFrequencies(), options.generateBytecode(), scanPlanMode);
        ParseResult parsed = TrinoRegexpParser.parse(patternCopy, PARSE_FLAGS);
        LiteralAlternationSpanMatcher literalAlternationMatcher = LiteralAlternationSpanMatcher.analyze(
                parsed.regexp(),
                parsed.capturingGroupCount());
        // CompiledPattern.withRequiredTrinoBudget must report what any route added here needs.
        long forwardMemory = CompiledPattern.forwardMemory(inputs.maxMemory());
        if (literalAlternationMatcher != null && literalAlternationMatcher.estimatedRetainedSize() <= forwardMemory) {
            return new LiteralAlternationTrinoRegexp(inputs, literalAlternationMatcher);
        }
        LiteralSetSpanMatcher literalSetMatcher = LiteralSetSpanMatcher.analyze(parsed.regexp(), parsed.capturingGroupCount(), forwardMemory);
        if (literalSetMatcher != null && literalSetMatcher.estimatedRetainedSize() <= forwardMemory) {
            return new LiteralSetTrinoRegexp(inputs, literalSetMatcher);
        }
        CompiledPattern.Analysis analysis;
        try {
            analysis = CompiledPattern.analyzeParsedForTrino(patternCopy, parsed, PARSE_FLAGS, inputs.maxMemory(), inputs.byteFrequencies(), scanPlanMode);
        }
        catch (RegexpCompileMemoryLimitException e) {
            // What each route needs is computed only after every route failed.
            throw CompiledPattern.withRequiredTrinoBudget(e, parsed, PARSE_FLAGS, inputs.maxMemory(), literalAlternationMatcher);
        }
        TrinoCompiledPattern compiledPattern = TrinoCompiledPattern.create(analysis);
        if (parsed.regexp().op() == RegexpOp.BEGIN_LINE &&
                (parsed.regexp().parseFlags() & Regexp.TRINO_LINE) != 0) {
            return new BeginLineTrinoRegexp(compiledPattern, inputs);
        }
        LiteralAlternationSpanMatcher.FullCaseFoldAlternation fullCaseFoldAlternation = literalAlternationMatcher == null
                ? LiteralAlternationSpanMatcher.analyzeFullCaseFold(parsed.regexp(), parsed.capturingGroupCount(), parsed.caseFolding())
                : null;
        // The folded literals answer beside the engine, so they take their memory from its forward
        // DFA; where they do not fit, the pattern keeps the route it takes without them.
        if (fullCaseFoldAlternation != null && compiledPattern.tryReserveForwardDfaMemory(fullCaseFoldAlternation.estimatedRetainedSize())) {
            return new FullCaseFoldAlternationTrinoRegexp(compiledPattern, inputs, fullCaseFoldAlternation);
        }
        OrderedLiteralMatcher orderedLiteralMatcher = compiledPattern.createOrderedLiteralMatcher();
        // The ordered matcher takes precedence over the required bytes.
        if (orderedLiteralMatcher != null && compiledPattern.tryReserveForwardDfaMemory(orderedLiteralMatcher.estimatedRetainedSize())) {
            return new OrderedLiteralTrinoRegexp(compiledPattern, inputs, orderedLiteralMatcher);
        }
        DotStarLiteralSpanMatcher dotStarLiteralSpanMatcher = compiledPattern.createDotStarLiteralSpanMatcher();
        if (dotStarLiteralSpanMatcher != null) {
            return new DotStarLiteralTrinoRegexp(compiledPattern, inputs, dotStarLiteralSpanMatcher);
        }
        if (compiledPattern.hasExactLiteralSpan()) {
            return new ExactLiteralTrinoRegexp(compiledPattern, inputs);
        }
        if (compiledPattern.mayHaveFixedWidthByteSpanMatcher()) {
            TrinoRegexp fixedWidthByteRegexp = FixedWidthByteSpanMatcher.createTrinoRegexp(compiledPattern, inputs, parsed.regexp());
            if (fixedWidthByteRegexp != null) {
                return fixedWidthByteRegexp;
            }
        }
        if (compiledPattern.hasCharacterClassSpan()) {
            return new CharacterClassTrinoRegexp(compiledPattern, inputs);
        }
        if (inputs.generateBytecode()) {
            TrinoRegexp generated = CompiledScanTrinoRegexp.create(compiledPattern, inputs);
            if (generated != null) {
                return generated;
            }
        }
        return new TrinoRegexp(compiledPattern, inputs);
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
        if (retainedCapturingGroupCount == 0) {
            return new TrinoRegexpMatcher(newGroupZeroMatcher(source));
        }
        if (checksBeforeCaptureMatcher) {
            return TrinoRegexpMatcher.deferredEngine(re2, source, retainedCapturingGroupCount);
        }
        return new TrinoRegexpMatcher(re2.matcher(source, retainedCapturingGroupCount));
    }

    TrinoCompiledPattern re2()
    {
        return re2;
    }

    /// Returns whether this compiled pattern has a class generated for it, which only
    /// [Options#setGenerateBytecode(boolean)] enables. Such a pattern is an instance of its
    /// generated class, and each operation that would run its specialized scan runs the
    /// generated class. A caller that holds the pattern as a constant can bind an operation to
    /// it, as `docs/reference/REGULATOR_API.md` describes, so that its call site sees one
    /// generated class.
    public boolean usesGeneratedBytecode()
    {
        return false;
    }

    /// Returns whether `other` is a Trino pattern compiled from equal pattern bytes with equal
    /// option values, including those that change only performance, such as the memory budget.
    /// Two such compiles are equal whichever class each compile built, generated or not.
    @Override
    public final boolean equals(Object other)
    {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TrinoRegexp that)) {
            return false;
        }
        return hash == that.hash && compileInputs.equals(that.compileInputs);
    }

    @Override
    public final int hashCode()
    {
        return hash;
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

    boolean usesLiteralSetMatcherForDiagnostics()
    {
        return false;
    }

    /// Returns the folded literals that a case-insensitive alternation of ASCII literals runs on
    /// inputs without its escapes, or `null`.
    LiteralAlternationSpanMatcher fullCaseFoldAlternationForDiagnostics()
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

    boolean isSingleByteMatcherComputed()
    {
        return re2 != null && re2.isSingleByteMatcherComputed();
    }

    /// Returns a copy of the compiled pattern bytes.
    public Slice pattern()
    {
        return compileInputs.pattern().copy();
    }

    /// Returns an estimate of the heap bytes this pattern retains now, plus the native memory of
    /// its live DFA pointer tables, as Trino's and Airlift's `getRetainedSizeInBytes` estimates:
    /// the pattern bytes, the inputs of its compile, its programs and planned matchers, the DFA
    /// caches its searches have built so far, and its cached replacement, each counted once however
    /// many routes share it. It reads the DFAs without locking, while other threads search. Rarely,
    /// when concurrent growth or resets replace a DFA's tables throughout a read, the read reports
    /// that DFA's whole state budget in place of what its cache holds: a conservative over-report,
    /// not a leak. It counts what [Re2#retainedSizeInBytes()] counts and leaves out what that
    /// leaves out, including the metaspace of a class [Options#setGenerateBytecode(boolean)]
    /// generates.
    public long retainedSizeInBytes()
    {
        long size = RetainedSizeSupport.instanceSize(getClass()) +
                RetainedSizes.COMPILE_INPUTS_INSTANCE_SIZE +
                compileInputs.byteFrequencies().estimatedRetainedSize() +
                shapeRetainedSize();
        // The compiled engine counts the pattern bytes it shares with the inputs.
        size += re2 == null ? compileInputs.pattern().getRetainedSize() : re2.estimatedRetainedSize();
        TrinoReplacementPlan.CachedTemplate template = replacementTemplate;
        if (template != null) {
            size += template.estimatedRetainedSize();
        }
        return size;
    }

    /// Returns the memory budget this pattern was compiled with, in bytes: the value of
    /// [Options#setMaxMemory(long)], and the budget a memory-limit exception for this compile
    /// reports as its `maxMemoryBytes()`.
    public long maxMemoryBytes()
    {
        return compileInputs.maxMemory();
    }

    /// The bytes a subclass retains beyond the fields of this class and its compiled engine.
    long shapeRetainedSize()
    {
        return 0;
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
        private BeginLineTrinoRegexp(TrinoCompiledPattern re2, CompileInputs compileInputs)
        {
            super(re2, compileInputs);
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

        private OrderedLiteralTrinoRegexp(TrinoCompiledPattern re2, CompileInputs compileInputs, OrderedLiteralMatcher matcher)
        {
            super(re2, compileInputs);
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
        long shapeRetainedSize()
        {
            return matcher.estimatedRetainedSize();
        }

        /// Searches the literals in order and reads the gaps between them as the engine's dot
        /// does. A gap holding bytes the dot rejects leaves the answer to the engine on the whole
        /// input, so contains agrees with the operations the engine answers.
        @Override
        public boolean contains(Slice source)
        {
            requireNonNull(source, "source is null");
            int decision = matcher.findWithCharacterGaps(source);
            if (decision == OrderedLiteralMatcher.UNDECIDED) {
                return super.contains(source);
            }
            return decision == OrderedLiteralMatcher.MATCH;
        }
    }

    /// Returns the number of non-overlapping matches, including empty matches.
    public long count(Slice source)
    {
        requireNonNull(source, "source is null");
        // ScanKernelGenerator.defineCount emits this check for a generated class; keep in step.
        int length = source.length();
        if (length < re2.minimumSpanLength() || length > re2.maximumSpanLength()) {
            return 0;
        }
        SingleByteMatcher byteMatcher = singleByteMatcher();
        if (byteMatcher != SingleByteMatcher.unsupported()) {
            return byteMatcher.count(source);
        }

        long optimizedCount = re2.countMatches(source);
        if (optimizedCount >= 0) {
            return optimizedCount;
        }

        RegexpMatcher matcher = newGroupZeroMatcher(source);
        if (optimizedCount == Dfa.COUNT_BAILED) {
            matcher.markSkipsForwardDfa();
        }
        long count = 0;
        matcher.beginAllMatchOperation();
        try {
            while (matcher.find()) {
                count++;
            }
        }
        finally {
            matcher.endAllMatchOperation();
        }
        return count;
    }

    /// Passes the span of each match of `source` to `consumer`, until there are no more or
    /// `consumer` returns `false`. The spans are the ones, in the same order, that a
    /// [TrinoRegexpMatcher#find()] loop of `matcher(source, 0)` gives, as byte offsets relative
    /// to `source`. `source` must not change during the call, so dense iteration can reuse one
    /// reverse pass, as an [#extractAll(Slice)] does. An exception `consumer` throws propagates
    /// after the operation releases its reservation.
    public void forEachMatch(Slice source, MatchSpanConsumer consumer)
    {
        requireNonNull(source, "source is null");
        requireNonNull(consumer, "consumer is null");
        TrinoRegexpMatcher matcher = matcher(source, 0);
        matcher.beginAllMatchOperation();
        try {
            while (matcher.find()) {
                if (!consumer.accept(matcher.start(), matcher.end())) {
                    break;
                }
            }
        }
        finally {
            matcher.endAllMatchOperation();
        }
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
        if (group != 0 && captureSearchFailsBeforeMatcher(source)) {
            return null;
        }
        RegexpMatcher matcher = group == 0 ? newGroupZeroMatcher(source) : newMatcher(source);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(group);
    }

    /// Whether the first find of a capture matcher over all of `source` would fail before any
    /// engine runs, so a capture search finds nothing without building the matcher.
    private boolean captureSearchFailsBeforeMatcher(Slice source)
    {
        return checksBeforeCaptureMatcher && RegexpMatcher.firstGeneralFindFailsBeforeEngine(re2, source);
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
        if (group != 0 && captureSearchFailsBeforeMatcher(source)) {
            return NO_MATCHES;
        }
        return extractAllMatches(source, group);
    }

    // The all-match operation of extractAll: the matcher, its loop, and the operation's cleanup.
    // Compiled with its matcher setup inlined, the operation is too large to inline, so it stays
    // out of extractAll, which then stays small enough to inline into its caller whichever of the
    // two C2 compiles first.
    private List<Slice> extractAllMatches(Slice source, int group)
    {
        RegexpMatcher matcher = group == 0 ? newGroupZeroMatcher(source) : newMatcher(source);
        List<Slice> matches = new ArrayList<>();
        matcher.beginAllMatchOperation();
        try {
            while (matcher.find()) {
                matches.add(matcher.group(group));
            }
        }
        finally {
            matcher.endAllMatchOperation();
        }
        return Collections.unmodifiableList(matches);
    }

    /// Splits around non-overlapping matches and preserves trailing empty fields. Returned values are
    /// zero-copy views of `source`.
    public List<Slice> split(Slice source)
    {
        requireNonNull(source, "source is null");
        RegexpMatcher matcher = newGroupZeroMatcher(source);
        List<Slice> parts = new ArrayList<>();
        int previousEnd = 0;
        matcher.beginAllMatchOperation();
        try {
            while (matcher.find()) {
                parts.add(source.slice(previousEnd, matcher.start() - previousEnd));
                previousEnd = matcher.end();
            }
        }
        finally {
            matcher.endAllMatchOperation();
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
        // The steps are written out rather than calling replaceWithMatcher, so C2 compiles them into
        // this method instead of reaching them through a call it declines to inline.
        RegexpMatcher matcher = maximumCapturingGroup == 0
                ? newGroupZeroMatcher(source)
                : re2.matcher(source, maximumCapturingGroup);
        matcher.beginAllMatchOperation();
        try {
            addMatches(matcher, replacementPlan);
        }
        finally {
            matcher.endAllMatchOperation();
        }
        return replacementPlan.build();
    }

    // The loop of a replacement, kept out of the method that runs it. A method whose own loop runs
    // hot is compiled on its own, before its caller, and once that code is too large to inline,
    // the caller calls it; with the loop here, a caller can inline replace.
    private static void addMatches(RegexpMatcher matcher, TrinoReplacementPlan replacementPlan)
    {
        while (matcher.find()) {
            replacementPlan.addMatch(matcher);
        }
    }

    final TrinoReplacementPlan newReplacementPlan(Slice source, Slice replacement)
    {
        return newReplacementPlan(source, replacement, capturingGroupCount(), namedCapturingGroups());
    }

    /// Returns a replacement plan that keeps its template cache on this pattern, for the given
    /// groups of this pattern. A generated class passes them as constants, so its replacement
    /// reads none of this pattern's fields before the cache.
    final TrinoReplacementPlan newReplacementPlan(Slice source, Slice replacement, int capturingGroupCount, Map<String, Integer> namedCapturingGroups)
    {
        return new TrinoReplacementPlan(source, replacement, capturingGroupCount, namedCapturingGroups, this);
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
        matcher.beginAllMatchOperation();
        try {
            addMatches(matcher, replacementPlan);
        }
        finally {
            matcher.endAllMatchOperation();
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
        RegexpMatcher matcher = newMatcher(source);
        DynamicSliceOutput output = new DynamicSliceOutput(source.length());
        int previousEnd = 0;
        boolean matched = false;
        matcher.beginAllMatchOperation();
        try {
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
        }
        finally {
            matcher.endAllMatchOperation();
        }
        if (!matched) {
            return source;
        }
        output.writeBytes(source, previousEnd, source.length() - previousEnd);
        return output.slice();
    }

    final void validateGroup(int group)
    {
        int capturingGroupCount = re2.capturingGroupCount();
        if (group < 0 || group > capturingGroupCount) {
            throw invalidGroup(capturingGroupCount, group);
        }
    }

    /// Rejects a group the pattern does not have, as [#validateGroup(int)] does, for
    /// subclasses without a compiled pattern too.
    final void validateCapturingGroup(int group)
    {
        if (group < 0 || group > capturingGroupCount()) {
            throw invalidGroup(capturingGroupCount(), group);
        }
    }

    /// Returns the exception for `group`, which is negative or above `capturingGroupCount`, as
    /// [#validateGroup(int)] throws it, for generated extraction entries too.
    static IllegalArgumentException invalidGroup(int capturingGroupCount, int group)
    {
        if (group < 0) {
            return new IllegalArgumentException("group cannot be negative");
        }
        return new IllegalArgumentException("pattern has " + capturingGroupCount + " groups; cannot access group " + group);
    }

    private static final class DotStarLiteralTrinoRegexp
            extends TrinoRegexp
    {
        private final DotStarLiteralSpanMatcher spanMatcher;

        private DotStarLiteralTrinoRegexp(TrinoCompiledPattern re2, CompileInputs compileInputs, DotStarLiteralSpanMatcher spanMatcher)
        {
            super(re2, compileInputs);
            this.spanMatcher = requireNonNull(spanMatcher, "spanMatcher is null");
        }

        @Override
        boolean usesDotStarLiteralSpanMatcherForDiagnostics()
        {
            return true;
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

    /// Answers every operation from the fixed-width matcher. Compiled RE2, Java, and Rust
    /// patterns retain this matcher too, and so does this subtype's compiled pattern, but
    /// contains and count call it directly here rather than through the compiled pattern's
    /// dispatch, which measured about 9% slower per call.
    static final class FixedWidthByteTrinoRegexp
            extends TrinoRegexp
    {
        private final FixedWidthByteSpanMatcher spanMatcher;

        FixedWidthByteTrinoRegexp(TrinoCompiledPattern re2, CompileInputs compileInputs, FixedWidthByteSpanMatcher spanMatcher)
        {
            super(re2, compileInputs);
            this.spanMatcher = requireNonNull(spanMatcher, "spanMatcher is null");
        }

        // The engine counts the matcher it retained, which this shares.
        @Override
        long shapeRetainedSize()
        {
            return re2().hasRetainedFixedWidthByteSpanMatcher() ? 0 : spanMatcher.estimatedRetainedSize();
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
            return spanMatcher.count(source);
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
        private final LiteralAlternationSpanMatcher spanMatcher;

        private LiteralAlternationTrinoRegexp(CompileInputs compileInputs, LiteralAlternationSpanMatcher spanMatcher)
        {
            super(compileInputs);
            this.spanMatcher = requireNonNull(spanMatcher, "spanMatcher is null");
        }

        @Override
        long shapeRetainedSize()
        {
            return spanMatcher.estimatedRetainedSize();
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
            return spanMatcher.count(source);
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

            long span = occurrenceSpan(source, byteStart, occurrence);
            if (span < 0) {
                return -1;
            }
            return SliceUtf8.countCodePoints(source, 0, spanStart(span)) + 1L;
        }

        /// Returns the span of the `occurrence`th match from `byteStart`, or `-1`.
        long occurrenceSpan(Slice source, int byteStart, long occurrence)
        {
            // One cursor carries the filter route from match to match, so the density check
            // samples at most once per 64 KiB rather than once per match, and only once while the
            // filter routes stay sparse.
            LiteralAlternationSpanMatcher.Cursor cursor = new LiteralAlternationSpanMatcher.Cursor();
            long span = spanMatcher.findSpan(source, byteStart, cursor);
            for (long match = 1; match < occurrence && span >= 0; match++) {
                span = spanMatcher.findSpan(source, spanEnd(span), cursor);
            }
            return span;
        }

        @Override
        public Slice extract(Slice source, int group)
        {
            validateCapturingGroup(group);
            requireNonNull(source, "source is null");
            long span = spanMatcher.findSpan(source, 0);
            return span < 0 ? null : source.slice(spanStart(span), spanEnd(span) - spanStart(span));
        }

        @Override
        public List<Slice> extractAll(Slice source, int group)
        {
            validateCapturingGroup(group);
            requireNonNull(source, "source is null");
            List<Slice> matches = new ArrayList<>();
            LiteralAlternationSpanMatcher.Cursor cursor = new LiteralAlternationSpanMatcher.Cursor();
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart, cursor);
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
            LiteralAlternationSpanMatcher.Cursor cursor = new LiteralAlternationSpanMatcher.Cursor();
            int previousEnd = 0;
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart, cursor);
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
            LiteralAlternationSpanMatcher.Cursor cursor = new LiteralAlternationSpanMatcher.Cursor();
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart, cursor);
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
            LiteralAlternationSpanMatcher.Cursor cursor = new LiteralAlternationSpanMatcher.Cursor();
            int previousEnd = 0;
            int searchStart = 0;
            boolean matched = false;
            List<Slice> groups = List.of();
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart, cursor);
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

    /// Runs a case-insensitive alternation of ASCII literals, such as `(?i)Sherlock|Holmes`, on
    /// the [LiteralAlternationTrinoRegexp] of its folded literals where the input holds none of
    /// its escapes, a conservative set of the characters outside ASCII that the literals' full
    /// case folding may match, and on the compiled engine otherwise, as
    /// [LiteralAlternationSpanMatcher.FullCaseFoldAlternation] explains. An operation that reads
    /// every match checks the whole input first. For a folded match starting at byte `s`, one that
    /// needs only its first matches, extract and position, checks escape starts in
    /// `[searchStart, min(source.length(), s + L))`, where `L` is the longest folded literal's
    /// byte length. Any earlier full-fold match would contain an escape beginning in this window.
    /// Position takes `s` from the last occurrence it needs, and where the folded literals miss,
    /// both check the rest of the input. Contains needs no check when they match, since each of
    /// their matches is the pattern's. An answer whose window holds an escape comes from the
    /// engine, so the two never mix in one answer; a matcher checks window by window as it finds,
    /// as [TrinoRegexpMatcher] describes.
    ///
    /// Where the engine's first-match DFA searches for its own start literal filter, it already
    /// reads an input once at vector speed, so the escape scan would be a second pass over it. A
    /// DFA left too little memory to initialize by the folded literals' charge searches none.
    /// Where the filter runs, an input shorter than [#SHORT_INPUT_BYTES] takes one check for bytes
    /// outside ASCII in place of the escape scan, since every escape is outside ASCII: an
    /// all-ASCII input runs on the folded literals and any other on the engine, for every
    /// operation. On a longer input, the operations that read every match run on the engine, and
    /// the first-match operations keep the window checks above.
    static final class FullCaseFoldAlternationTrinoRegexp
            extends TrinoRegexp
    {
        private static final long LITERAL_ALTERNATION_INSTANCE_SIZE = RetainedSizeSupport.instanceSize(LiteralAlternationTrinoRegexp.class);
        // Below this length the engine's start filter scans scalar, as the ASCII check does.
        static final int SHORT_INPUT_BYTES = Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES;

        private final LiteralAlternationSpanMatcher.FullCaseFoldAlternation alternation;
        private final LiteralAlternationTrinoRegexp admittedInputs;
        private final boolean engineFiltersStarts;

        private FullCaseFoldAlternationTrinoRegexp(TrinoCompiledPattern re2, CompileInputs compileInputs, LiteralAlternationSpanMatcher.FullCaseFoldAlternation alternation)
        {
            super(re2, compileInputs);
            this.alternation = requireNonNull(alternation, "alternation is null");
            this.admittedInputs = new LiteralAlternationTrinoRegexp(compileInputs, alternation.matcher());
            this.engineFiltersStarts = re2.searchesStartLiteralFilter();
        }

        /// Whether one check for bytes outside ASCII settles the route of `source`: the engine
        /// searches for its start literal filter, and `source` is shorter than
        /// [#SHORT_INPUT_BYTES].
        boolean routesByAscii(Slice source)
        {
            return engineFiltersStarts && source != null && source.length() < SHORT_INPUT_BYTES;
        }

        // A null source takes the literal-alternation shape, which throws what the others throw.
        private boolean admits(Slice source)
        {
            return source == null || alternation.admits(source, 0, source.length());
        }

        // Whether an operation that reads every match of `source` runs on the folded literals.
        private boolean answersEveryMatch(Slice source)
        {
            if (source == null || !engineFiltersStarts) {
                return admits(source);
            }
            return source.length() < SHORT_INPUT_BYTES && SliceUtf8.isAscii(source);
        }

        // Whether the folded literals' first match from `searchStart`, `span`, or their miss
        // where it is negative, is the program's.
        private boolean answers(Slice source, int searchStart, long span)
        {
            return alternation.admits(source, searchStart, span < 0 ? source.length() : alternation.windowEnd(source, spanStart(span)));
        }

        // The literal-alternation shape keeps its own replacement template.
        @Override
        long shapeRetainedSize()
        {
            TrinoReplacementPlan.CachedTemplate template = admittedInputs.cachedReplacementTemplate();
            return LITERAL_ALTERNATION_INSTANCE_SIZE + alternation.estimatedRetainedSize() + (template == null ? 0 : template.estimatedRetainedSize());
        }

        @Override
        LiteralAlternationSpanMatcher fullCaseFoldAlternationForDiagnostics()
        {
            return alternation.matcher();
        }

        LiteralAlternationSpanMatcher.FullCaseFoldAlternation alternation()
        {
            return alternation;
        }

        /// Returns a group-zero matcher of the compiled engine, which a matcher runs where the
        /// routing rule of this class hands its input to the engine.
        RegexpMatcher engineMatcher(Slice input)
        {
            return super.newGroupZeroMatcher(input);
        }

        @Override
        public TrinoRegexpMatcher matcher(Slice source, int retainedCapturingGroupCount)
        {
            requireNonNull(source, "source is null");
            if (retainedCapturingGroupCount != 0) {
                throw new IllegalArgumentException("capturingGroupCount out of range: " + retainedCapturingGroupCount);
            }
            return TrinoRegexpMatcher.fullCaseFoldAlternation(this, source);
        }

        @Override
        public boolean contains(Slice source)
        {
            if (routesByAscii(source)) {
                return SliceUtf8.isAscii(source) ? admittedInputs.contains(source) : super.contains(source);
            }
            return admittedInputs.contains(source) || (!admits(source) && super.contains(source));
        }

        @Override
        public long count(Slice source)
        {
            return answersEveryMatch(source) ? admittedInputs.count(source) : super.count(source);
        }

        // Each earlier occurrence is the program's where the window of the next one holds no
        // escape, so one window from the search start through the last occurrence's covers all.
        @Override
        public long position(Slice source, long start, long occurrence)
        {
            if (routesByAscii(source)) {
                return SliceUtf8.isAscii(source) ? admittedInputs.position(source, start, occurrence) : super.position(source, start, occurrence);
            }
            requireNonNull(source, "source is null");
            int byteStart = positionSearchStart(source, start, occurrence);
            if (byteStart < 0) {
                return -1;
            }
            long span = admittedInputs.occurrenceSpan(source, byteStart, occurrence);
            if (!answers(source, byteStart, span)) {
                return super.position(source, start, occurrence);
            }
            return span < 0 ? -1 : SliceUtf8.countCodePoints(source, 0, spanStart(span)) + 1L;
        }

        @Override
        public Slice extract(Slice source, int group)
        {
            if (routesByAscii(source)) {
                return SliceUtf8.isAscii(source) ? admittedInputs.extract(source, group) : super.extract(source, group);
            }
            validateCapturingGroup(group);
            requireNonNull(source, "source is null");
            long span = alternation.matcher().findSpan(source, 0);
            if (!answers(source, 0, span)) {
                return super.extract(source, group);
            }
            return span < 0 ? null : source.slice(spanStart(span), spanEnd(span) - spanStart(span));
        }

        @Override
        public List<Slice> extractAll(Slice source, int group)
        {
            return answersEveryMatch(source) ? admittedInputs.extractAll(source, group) : super.extractAll(source, group);
        }

        @Override
        public List<Slice> split(Slice source)
        {
            return answersEveryMatch(source) ? admittedInputs.split(source) : super.split(source);
        }

        @Override
        public Slice replace(Slice source, Slice replacement)
        {
            return answersEveryMatch(source) ? admittedInputs.replace(source, replacement) : super.replace(source, replacement);
        }

        @Override
        public Slice replace(Slice source, Function<List<Slice>, Slice> replacement)
        {
            return answersEveryMatch(source) ? admittedInputs.replace(source, replacement) : super.replace(source, replacement);
        }
    }

    /// Runs every operation on a [LiteralSetSpanMatcher], repeating [LiteralAlternationTrinoRegexp]
    /// with the automaton's span search, so a fix to either belongs in both.
    private static final class LiteralSetTrinoRegexp
            extends TrinoRegexp
    {
        private final LiteralSetSpanMatcher spanMatcher;

        private LiteralSetTrinoRegexp(CompileInputs compileInputs, LiteralSetSpanMatcher spanMatcher)
        {
            super(compileInputs);
            this.spanMatcher = requireNonNull(spanMatcher, "spanMatcher is null");
        }

        @Override
        long shapeRetainedSize()
        {
            return spanMatcher.estimatedRetainedSize();
        }

        @Override
        boolean usesLiteralSetMatcherForDiagnostics()
        {
            return true;
        }

        @Override
        public TrinoRegexpMatcher matcher(Slice source, int retainedCapturingGroupCount)
        {
            requireNonNull(source, "source is null");
            if (retainedCapturingGroupCount != 0) {
                throw new IllegalArgumentException("capturingGroupCount out of range: " + retainedCapturingGroupCount);
            }
            return TrinoRegexpMatcher.literalSet(spanMatcher, source);
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
            return spanMatcher.findSpan(source, 0, source.length()) >= 0;
        }

        @Override
        public long count(Slice source)
        {
            requireNonNull(source, "source is null");
            long count = 0;
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart, source.length());
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

            long span = spanMatcher.findSpan(source, byteStart, source.length());
            for (long match = 1; match < occurrence && span >= 0; match++) {
                span = spanMatcher.findSpan(source, spanEnd(span), source.length());
            }
            if (span < 0) {
                return -1;
            }
            return SliceUtf8.countCodePoints(source, 0, spanStart(span)) + 1L;
        }

        @Override
        public Slice extract(Slice source, int group)
        {
            validateCapturingGroup(group);
            requireNonNull(source, "source is null");
            long span = spanMatcher.findSpan(source, 0, source.length());
            return span < 0 ? null : source.slice(spanStart(span), spanEnd(span) - spanStart(span));
        }

        @Override
        public List<Slice> extractAll(Slice source, int group)
        {
            validateCapturingGroup(group);
            requireNonNull(source, "source is null");
            List<Slice> matches = new ArrayList<>();
            int searchStart = 0;
            while (searchStart < source.length()) {
                long span = spanMatcher.findSpan(source, searchStart, source.length());
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
                long span = spanMatcher.findSpan(source, searchStart, source.length());
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
                long span = spanMatcher.findSpan(source, searchStart, source.length());
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
                long span = spanMatcher.findSpan(source, searchStart, source.length());
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

    private static final class ExactLiteralTrinoRegexp
            extends TrinoRegexp
    {
        private ExactLiteralTrinoRegexp(TrinoCompiledPattern re2, CompileInputs compileInputs)
        {
            super(re2, compileInputs);
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
        private CharacterClassTrinoRegexp(TrinoCompiledPattern re2, CompileInputs compileInputs)
        {
            super(re2, compileInputs);
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

    /// A Trino pattern whose case-sensitive scan plan was compiled to its own hidden class,
    /// which extends this class, so the pattern a caller holds is an instance of that class. Each
    /// operation keeps the route it has without the class, decided once when the pattern compiles:
    /// the generated class declares each operation that would run the plan, and any other runs the
    /// inherited Java route. Short boolean calls that the interpreter hands to the ordinary engine
    /// to avoid its operation dispatch also stay on the generated class. The generated class adds
    /// no state. Callers that never enable [Options#setGenerateBytecode(boolean)] never load this
    /// class.
    abstract static non-sealed class CompiledScanTrinoRegexp
            extends TrinoRegexp
    {
        /// Wraps the compiled engine `re2`, compiled from `compileInputs`. Only the generated
        /// subclass calls this.
        CompiledScanTrinoRegexp(TrinoCompiledPattern re2, CompileInputs compileInputs)
        {
            super(re2, compileInputs);
        }

        /// Returns a Trino pattern over the compiled engine `re2`, compiled from `compileInputs`,
        /// with a generated class, or `null` when the pattern is not eligible or the class cannot
        /// be generated. The declared type keeps verification of the base class from loading this
        /// class.
        static TrinoRegexp create(TrinoCompiledPattern re2, CompileInputs compileInputs)
        {
            ScanKernelGenerator.TrinoRoutes routes = ScanKernelGenerator.TrinoRoutes.of(re2);
            if (routes == null) {
                return null;
            }
            try {
                return ScanKernelGenerator.generateCompiledTrinoPattern(routes, compileInputs);
            }
            catch (RuntimeException | LinkageError ignored) {
                // Linking the generator fails here only if a consumer excluded the classfile
                // library, and defining the class can fail too; either leaves the pattern
                // interpreted.
                return null;
            }
        }

        /// Finds the first match at or after byte offset `start`, writing the start and end of
        /// group zero and of each group that fits to `groups`. A matcher's finds run this hook. A
        /// scan plan never matches empty, so a matcher resumes each find at the previous match
        /// end.
        abstract boolean find(Slice source, int start, int[] groups);

        @Override
        public TrinoRegexpMatcher matcher(Slice source, int retainedCapturingGroupCount)
        {
            requireNonNull(source, "source is null");
            if (retainedCapturingGroupCount < 0 || retainedCapturingGroupCount > capturingGroupCount()) {
                throw new IllegalArgumentException("capturingGroupCount out of range: " + retainedCapturingGroupCount);
            }
            return TrinoRegexpMatcher.compiledScan(this, source, retainedCapturingGroupCount);
        }

        @Override
        public boolean usesGeneratedBytecode()
        {
            return true;
        }

        @Override
        MethodHandle attemptHandleForDiagnostics()
        {
            return ScanKernelGenerator.attemptHandleForDiagnostics(getClass(), re2().caseSensitiveScanPlan());
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

    private SingleByteMatcher singleByteMatcher()
    {
        SingleByteMatcher byteMatcher = re2.sharedSingleByteMatcher();
        return byteMatcher == null ? SingleByteMatcher.unsupported() : byteMatcher;
    }

    // Loaded only when a size is estimated: parsing loads this class and never sizes objects, and
    // TeaVM cannot.
    private static final class RetainedSizes
    {
        private static final int COMPILE_INPUTS_INSTANCE_SIZE = SizeOf.instanceSize(CompileInputs.class);

        private RetainedSizes() {}
    }
}
