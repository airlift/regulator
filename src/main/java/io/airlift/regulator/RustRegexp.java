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

import java.util.Arrays;

import static java.util.Objects.requireNonNull;

/// A compiled regular expression in the pattern language of the Rust `regex` crate, pinned at
/// regex 1.13.1, over Slice inputs.
///
/// Matching follows the crate's leftmost-first semantics. By default this is the crate's
/// `regex::Regex`, which matches valid UTF-8 input, and searches skip an empty match that
/// splits a UTF-8 character. With [byte mode][Options#setBytes], this is the crate's
/// `regex::bytes::Regex`, which matches arbitrary bytes. In both modes, iteration skips an
/// empty match adjacent to the previous match, and a search of a range or matcher region sees the
/// input outside it as assertion context, as in the crate's `Input::range`. Unicode
/// properties read Regulator's Unicode database. As in the crate, repetition counts above 1,000
/// are accepted; compilation throws [RegexpCompileMemoryLimitException] when the expansion does not
/// fit the memory budget, and rejects most oversized expansions before building them. Match
/// offsets are byte offsets, as in the crate.
public sealed class RustRegexp
        extends CompiledPattern
        permits RustRegexp.ByteTableRustRegexp,
                RustRegexp.ClassRunRustRegexp,
                RustRegexp.ExactLiteralRustRegexp,
                RustRegexp.FixedWidthRustRegexp,
                RustRegexp.LiteralAlternationRustRegexp,
                RustRegexp.LiteralKernelRustRegexp,
                RustRegexp.ScanPlanRustRegexp
{
    /// Mutable Rust-language compilation options. Compilation snapshots every option, so later
    /// changes do not affect an existing [RustRegexp]. Options instances are not thread-safe.
    public static final class Options
    {
        private boolean caseInsensitive;
        private boolean multiline;
        private boolean dotMatchesNewline;
        private boolean crlf;
        private boolean swapGreed;
        private boolean comments;
        private boolean unicode = true;
        private boolean octal;
        private boolean bytes;
        private int nestLimit = RustRegexpParser.DEFAULT_NEST_LIMIT;
        private long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
        private ByteFrequencies byteFrequencies = ByteFrequencies.uniform();
        private boolean generateBytecode;

        private Options() {}

        public static Options defaults()
        {
            return new Options();
        }

        /// Creates the default options in byte mode.
        ///
        /// @see #setBytes(boolean)
        public static Options bytes()
        {
            Options options = new Options();
            options.bytes = true;
            return options;
        }

        public Options setCaseInsensitive(boolean caseInsensitive)
        {
            this.caseInsensitive = caseInsensitive;
            return this;
        }

        public Options setMultiline(boolean multiline)
        {
            this.multiline = multiline;
            return this;
        }

        public Options setDotMatchesNewline(boolean dotMatchesNewline)
        {
            this.dotMatchesNewline = dotMatchesNewline;
            return this;
        }

        /// Sets whether, in multi-line mode, `^` and `$` treat `\r\n` as a line
        /// end, and `.` excludes `\r` unless it matches newlines; this is the `R` flag.
        public Options setCrlf(boolean crlf)
        {
            this.crlf = crlf;
            return this;
        }

        /// Sets whether greedy and lazy repetitions swap meaning, which is the `U` flag.
        public Options setSwapGreed(boolean swapGreed)
        {
            this.swapGreed = swapGreed;
            return this;
        }

        /// Sets whether whitespace and `#` comments in the pattern are ignored, which is the
        /// crate's `ignore_whitespace` option and the `x` flag.
        public Options setComments(boolean comments)
        {
            this.comments = comments;
            return this;
        }

        /// Sets whether Unicode mode is enabled, which is the default. Without it, `\d`,
        /// `\s`, `\w`, and case folding use ASCII rules, a class can name a non-ASCII
        /// byte only in [byte mode][#setBytes], and outside byte mode constructs that could
        /// match part of a UTF-8 character fail.
        public Options setUnicode(boolean unicode)
        {
            this.unicode = unicode;
            return this;
        }

        /// Sets whether an escaped digit is an octal code point of up to three digits, such as
        /// `\141` for `a`. Without it, which is the default, an escaped digit is a
        /// backreference and fails.
        public Options setOctal(boolean octal)
        {
            this.octal = octal;
            return this;
        }

        /// Sets whether the pattern is the crate's `regex::bytes::Regex`, which matches
        /// arbitrary bytes. In byte mode, with Unicode mode off, the dot, character classes, and
        /// two-digit `\xNN` escapes, such as `(?-u:.)` or `(?-u:\xFF)`, match a
        /// single byte even when it is not part of valid UTF-8. Outside a class, a non-ASCII literal,
        /// a braced `\x{...}` or Unicode escape, and an enabled octal escape keep their UTF-8
        /// encoding, and a construct in Unicode mode still matches only UTF-8. An empty match may
        /// split a UTF-8 character. Disabled by default.
        public Options setBytes(boolean bytes)
        {
            this.bytes = bytes;
            return this;
        }

        /// Sets the crate's limit on syntax nesting depth, which defaults to 250 and cannot be
        /// negative.
        public Options setNestLimit(int nestLimit)
        {
            if (nestLimit < 0) {
                throw new IllegalArgumentException("nestLimit must not be negative: " + nestLimit);
            }
            this.nestLimit = nestLimit;
            return this;
        }

        int nestLimit()
        {
            return nestLimit;
        }

        /// Returns the compilation, DFA-cache and capture-search memory budget in bytes.
        long maxMemory()
        {
            return maxMemory;
        }

        /// Sets the positive compilation, DFA-cache and capture-search memory budget in bytes.
        public Options setMaxMemory(long maxMemory)
        {
            if (maxMemory <= 0) {
                throw new IllegalArgumentException("maxMemory must be greater than zero: " + maxMemory);
            }
            this.maxMemory = maxMemory;
            return this;
        }

        /// Returns the byte-frequency distribution that chooses literal probe bytes.
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

        /// When enabled, compilation generates a hidden class for an eligible pattern, so each
        /// operation that would run its specialized scan runs dedicated bytecode instead.
        /// Operations that take another route keep it. Patterns that are not eligible, or whose
        /// class cannot be generated, compile as they do without this option. Disabled by default.
        ///
        /// @see RustRegexp#usesGeneratedBytecode()
        public Options setGenerateBytecode(boolean generateBytecode)
        {
            this.generateBytecode = generateBytecode;
            return this;
        }

        int syntaxFlags()
        {
            int flags = 0;
            if (caseInsensitive) {
                flags |= RustRegexpParser.CASE_INSENSITIVE;
            }
            if (multiline) {
                flags |= RustRegexpParser.MULTI_LINE;
            }
            if (dotMatchesNewline) {
                flags |= RustRegexpParser.DOT_MATCHES_NEW_LINE;
            }
            if (crlf) {
                flags |= RustRegexpParser.CRLF;
            }
            if (swapGreed) {
                flags |= RustRegexpParser.SWAP_GREED;
            }
            if (comments) {
                flags |= RustRegexpParser.IGNORE_WHITESPACE;
            }
            if (unicode) {
                flags |= RustRegexpParser.UNICODE;
            }
            if (octal) {
                flags |= RustRegexpParser.OCTAL;
            }
            if (bytes) {
                flags |= RustRegexpParser.BYTES;
            }
            return flags;
        }

        int compileFlags()
        {
            // Byte mode sets the pattern-level Latin-1 flag, so a search advances one byte past an
            // empty match, and the matcher lets an empty match split a UTF-8 character.
            int flags = Regexp.LIKE_PERL | Regexp.RUST_SEARCH;
            if (bytes) {
                flags |= Regexp.LATIN1;
            }
            return flags;
        }
    }

    // The crate's bytes API, where an empty match may split a UTF-8 character.
    private final boolean bytes;

    private RustRegexp(Analysis analysis)
    {
        super(analysis);
        this.bytes = (flags() & Regexp.LATIN1) != 0;
    }

    private RustRegexp(RustRegexp pattern)
    {
        super(pattern);
        this.bytes = pattern.bytes;
    }

    /// Constructs an instance of the analyzed pattern's shape class.
    private static RustRegexp create(Analysis analysis)
    {
        return switch (analysis.shape()) {
            case ENGINE -> new RustRegexp(analysis);
            case EXACT_LITERAL -> new ExactLiteralRustRegexp(analysis);
            case LITERAL_KERNEL -> new LiteralKernelRustRegexp(analysis);
            case LITERAL_ALTERNATION -> new LiteralAlternationRustRegexp(analysis);
            case FIXED_WIDTH -> new FixedWidthRustRegexp(analysis);
            case CLASS_RUN -> new ClassRunRustRegexp(analysis);
            case BYTE_TABLE -> new ByteTableRustRegexp(analysis);
            case SCAN_PLAN -> new ScanPlanRustRegexp(analysis);
        };
    }

    /// Validates a pattern and returns its capture metadata without constructing a matching
    /// engine. Parse errors and capture numbering are the same as for [#compile(Slice)];
    /// compilation memory limits are not checked.
    public static PatternInfo parse(Slice pattern)
    {
        return parse(pattern, Options.defaults());
    }

    /// Parses as [#parse(Slice)] does, using the syntax settings in `options` at the time of the
    /// call. Settings that apply only to compilation or matching, such as the memory budget, have
    /// no effect. Neither the pattern nor `options` is retained.
    public static PatternInfo parse(Slice pattern, Options options)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        return RustRegexpParser.parse(pattern, options.syntaxFlags(), options.nestLimit()).patternInfo();
    }

    public static RustRegexp compile(Slice pattern)
    {
        return compile(pattern, Options.defaults());
    }

    /// Compiles a copied snapshot of `pattern` and the current option values.
    public static RustRegexp compile(Slice pattern, Options options)
    {
        return compile(pattern, options, ScanPlanMode.DEFAULT);
    }

    /// Compiles `pattern` as [#compile(Slice,Options)] does, but without a scan plan,
    /// so tests and benchmarks can compare a plan with the ordinary engine.
    static RustRegexp compileWithoutScanPlanForTesting(Slice pattern, Options options)
    {
        return compile(pattern, options, ScanPlanMode.DISABLED);
    }

    /// Compiles `pattern` as [#compile(Slice,Options)] does, but a scan plan
    /// continues every unanchored search on the ordinary engine before its first attempt.
    static RustRegexp compileForcingScanPlanHandoffForTesting(Slice pattern, Options options)
    {
        return compile(pattern, options, ScanPlanMode.FORCE_HANDOFF);
    }

    private static RustRegexp compile(Slice pattern, Options options, ScanPlanMode scanPlanMode)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        Slice patternCopy = pattern.copy();
        ParseResult parsed = RustRegexpParser.parse(patternCopy, options.syntaxFlags(), options.nestLimit());
        int compileFlags = options.compileFlags();
        // Byte mode keeps its scan plan. A plan never matches empty, so byte mode's one-byte advance
        // past an empty match never applies, and the analysis declines every byte construct that
        // can match a byte above 0x7F: byte classes and literals by their Latin-1 node flag, and
        // the any-byte dot, which has no such flag, by its kind.
        RustRegexp compiled = create(analyzeParsed(patternCopy, parsed, compileFlags, options.maxMemory(), options.byteFrequencies(), ScanPlan.Language.RUST, scanPlanMode));
        if (options.generateBytecode()) {
            RustRegexp generated = CompiledScanRustRegexp.create(compiled);
            if (generated != null) {
                return generated;
            }
        }
        return compiled;
    }

    /// Returns whether this compiled pattern has a class generated for it, which only
    /// [Options#setGenerateBytecode(boolean)] enables. Each operation of such a pattern that
    /// would run its specialized scan runs the generated class.
    @Override
    public boolean usesGeneratedBytecode()
    {
        return false;
    }

    /// Returns whether the pattern has a match anywhere in `input` without allocating capture
    /// storage. Outside byte mode, a pattern that can match empty allocates storage for the
    /// match span, to skip an empty match that splits a UTF-8 character. The shape classes,
    /// which never match empty, bypass this `find`.
    @Override
    public boolean find(Slice input)
    {
        requireNonNull(input, "input is null");
        if (!canMatchEmpty()) {
            return super.find(input);
        }
        return search(input, 0, input.length(), null);
    }

    /// Returns the number of non-overlapping matches, including empty matches.
    /// As in the Rust crate, an empty match that ends where the previous match ended is skipped,
    /// and outside byte mode, so is one that splits a UTF-8 character. No capture values are
    /// returned.
    @Override
    public long count(Slice input)
    {
        return super.count(input);
    }

    /// Returns whether the pattern has a match in `[start, end)`.
    @Override
    public boolean find(Slice input, int start, int end)
    {
        return search(requireNonNull(input, "input is null"), start, end, null);
    }

    /// Returns whether the pattern matches at `start` within `[start, end)`.
    @Override
    public boolean lookingAt(Slice input, int start, int end)
    {
        return super.lookingAt(input, start, end) && isSearchStart(input, start);
    }

    /// Returns whether the pattern matches all of `[start, end)`.
    @Override
    public boolean matches(Slice input, int start, int end)
    {
        return super.matches(input, start, end) && isSearchStart(input, start);
    }

    /// Finds a match and writes byte-offset start/end pairs into caller-owned storage.
    @Override
    public boolean findInto(Slice input, int[] groups)
    {
        return findInto(requireNonNull(input, "input is null"), 0, input.length(), groups);
    }

    /// Finds a match in `[start, end)` and writes byte-offset start/end pairs.
    @Override
    public boolean findInto(Slice input, int start, int end, int[] groups)
    {
        return search(requireNonNull(input, "input is null"), start, end, groups);
    }

    /// Matches at `start` within `[start, end)` and writes byte-offset start/end pairs.
    @Override
    public boolean lookingAtInto(Slice input, int start, int end, int[] groups)
    {
        boolean matched = super.lookingAtInto(input, start, end, groups);
        return anchoredAt(input, start, matched, groups);
    }

    /// Matches all of `[start, end)` and writes byte-offset start/end pairs.
    @Override
    public boolean matchesInto(Slice input, int start, int end, int[] groups)
    {
        boolean matched = super.matchesInto(input, start, end, groups);
        return anchoredAt(input, start, matched, groups);
    }

    /// Returns the first match, or `null` when there is no match.
    @Override
    public MatchResult findResult(Slice input)
    {
        return findResult(requireNonNull(input, "input is null"), 0, input.length());
    }

    /// Returns the first match in `[start, end)`, or `null` when there is no match.
    @Override
    public MatchResult findResult(Slice input, int start, int end)
    {
        requireNonNull(input, "input is null");
        int[] groups = new int[2 * (capturingGroupCount() + 1)];
        if (!search(input, start, end, groups)) {
            return null;
        }
        return new MatchResult(input, groups, namedCapturingGroups());
    }

    /// Returns the match at `start` within `[start, end)`, or `null`.
    @Override
    public MatchResult lookingAtResult(Slice input, int start, int end)
    {
        MatchResult result = super.lookingAtResult(input, start, end);
        return isSearchStart(input, start) ? result : null;
    }

    /// Returns the complete-region match, or `null` when there is no match.
    @Override
    public MatchResult matchesResult(Slice input, int start, int end)
    {
        MatchResult result = super.matchesResult(input, start, end);
        return isSearchStart(input, start) ? result : null;
    }

    // Every unanchored search of a range comes here. Outside byte mode, the crate skips an empty
    // match that splits a UTF-8 character and searches again one byte later.
    private boolean search(Slice input, int start, int end, int[] groups)
    {
        if (bytes || !canMatchEmpty()) {
            return super.findInto(input, start, end, groups);
        }
        int[] span = groups == null ? new int[2] : groups;
        int searchStart = start;
        while (super.findInto(input, searchStart, end, span)) {
            if (span[0] != span[1] || Utf8.isCharacterBoundary(input, span[0])) {
                return true;
            }
            if (span[0] == end) {
                Arrays.fill(span, -1);
                return false;
            }
            searchStart = span[0] + 1;
        }
        return false;
    }

    // An anchored search cannot skip ahead, so outside byte mode a match at a split UTF-8
    // character fails.
    private boolean anchoredAt(Slice input, int start, boolean matched, int[] groups)
    {
        if (!matched || isSearchStart(input, start)) {
            return matched;
        }
        if (groups != null) {
            Arrays.fill(groups, -1);
        }
        return false;
    }

    private boolean isSearchStart(Slice input, int start)
    {
        return bytes || Utf8.isCharacterBoundary(input, start);
    }

    /// A pattern of the [exact-literal shape][Shape#EXACT_LITERAL].
    static final class ExactLiteralRustRegexp
            extends RustRegexp
    {
        private ExactLiteralRustRegexp(Analysis analysis)
        {
            super(analysis);
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnExactLiteral(input);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnExactLiteral(text);
        }

        @Override
        public boolean matches(Slice input)
        {
            return matchesOnExactLiteral(input);
        }

        @Override
        public boolean lookingAt(Slice input)
        {
            return lookingAtOnExactLiteral(input);
        }
    }

    /// A pattern of the [literal-kernel shape][Shape#LITERAL_KERNEL].
    static final class LiteralKernelRustRegexp
            extends RustRegexp
    {
        private LiteralKernelRustRegexp(Analysis analysis)
        {
            super(analysis);
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnLiteralKernel(input);
        }

        @Override
        public boolean matches(Slice input)
        {
            return matchesOnLiteralKernel(input);
        }

        @Override
        public boolean lookingAt(Slice input)
        {
            return lookingAtOnLiteralKernel(input);
        }
    }

    /// A pattern of the [literal-alternation shape][Shape#LITERAL_ALTERNATION].
    static final class LiteralAlternationRustRegexp
            extends RustRegexp
    {
        private final boolean literalSetCount;

        private LiteralAlternationRustRegexp(Analysis analysis)
        {
            super(analysis);
            this.literalSetCount = analysis.literalSetCount();
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnLiteralAlternation(input);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnLiteralAlternation(text, literalSetCount);
        }

        @Override
        boolean countsOnLiteralSetForDiagnostics()
        {
            return literalSetCount;
        }
    }

    /// A pattern of the [fixed-width shape][Shape#FIXED_WIDTH].
    static final class FixedWidthRustRegexp
            extends RustRegexp
    {
        private FixedWidthRustRegexp(Analysis analysis)
        {
            super(analysis);
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnFixedWidth(input);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnFixedWidth(text);
        }
    }

    /// A pattern of the [class-run shape][Shape#CLASS_RUN].
    static final class ClassRunRustRegexp
            extends RustRegexp
    {
        private final boolean retainedClassCountDfa;

        private ClassRunRustRegexp(Analysis analysis)
        {
            super(analysis);
            this.retainedClassCountDfa = analysis.retainedClassCountDfa();
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnClassRun(input);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnClassRun(text, retainedClassCountDfa);
        }

        @Override
        boolean matchInternal(Prog prog, Slice text, int contextStart, int contextEnd, int start, int end, Anchor anchor, int[] groupOffsets, MatchWorkspaces workspaces)
        {
            return matchInternalOnClassRun(prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces);
        }

        @Override
        boolean usesCompactBoundedCharacterClassForDiagnostics()
        {
            return true;
        }
    }

    /// A pattern of the [byte-table shape][Shape#BYTE_TABLE].
    static final class ByteTableRustRegexp
            extends RustRegexp
    {
        private ByteTableRustRegexp(Analysis analysis)
        {
            super(analysis);
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnByteTable(input);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnByteTable(text);
        }
    }

    /// A pattern of the [scan-plan shape][Shape#SCAN_PLAN]. A generated class extends this
    /// through [CompiledScanRustRegexp].
    static sealed class ScanPlanRustRegexp
            extends RustRegexp
            permits CompiledScanRustRegexp
    {
        private final boolean ordinaryShortBooleanMatch;
        private final boolean singleAttemptCount;
        private final boolean literalKernelFind;

        private ScanPlanRustRegexp(Analysis analysis)
        {
            super(analysis);
            this.ordinaryShortBooleanMatch = analysis.ordinaryShortBooleanMatch();
            this.singleAttemptCount = analysis.singleAttemptCount();
            this.literalKernelFind = analysis.literalKernelFind();
        }

        /// Copies `pattern`, which the caller then discards.
        private ScanPlanRustRegexp(ScanPlanRustRegexp pattern)
        {
            super(pattern);
            this.ordinaryShortBooleanMatch = pattern.ordinaryShortBooleanMatch;
            this.singleAttemptCount = pattern.singleAttemptCount;
            this.literalKernelFind = pattern.literalKernelFind;
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnScanPlan(input, ordinaryShortBooleanMatch, literalKernelFind);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnScanPlan(text, ordinaryShortBooleanMatch, singleAttemptCount);
        }

        @Override
        public boolean matches(Slice input)
        {
            return matchesOnScanPlan(input);
        }

        @Override
        public boolean lookingAt(Slice input)
        {
            return lookingAtOnScanPlan(input);
        }

        @Override
        boolean matchInternal(Prog prog, Slice text, int contextStart, int contextEnd, int start, int end, Anchor anchor, int[] groupOffsets, MatchWorkspaces workspaces)
        {
            return matchInternalOnScanPlan(prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces, ordinaryShortBooleanMatch);
        }

        @Override
        ScanPlanCount scanPlanCount()
        {
            return countRouteOnScanPlan(singleAttemptCount);
        }

        @Override
        boolean usesOrdinaryShortBooleanMatchForDiagnostics()
        {
            return ordinaryShortBooleanMatch;
        }

        @Override
        boolean usesScanPlanForFindForDiagnostics()
        {
            return !literalKernelFind;
        }
    }

    /// A Rust pattern whose case-sensitive scan plan was compiled to its own hidden class,
    /// which extends this class, so the pattern a caller holds is an instance of that class. The
    /// generated class declares each full-Slice `find`, `count`, `matches`, and `lookingAt` that
    /// reaches the plan, and the engine hooks used by other calls that reach the plan, each with
    /// its route fixed when the pattern compiles; any other call runs the inherited route. Short
    /// boolean calls that the interpreter hands to the ordinary engine to avoid its operation
    /// dispatch stay on the generated class. For boolean calls of an unanchored plan with capture
    /// saves or a start-anchored plan with a fork, the generated class runs the full plan rather
    /// than the save-free boolean plan, since it guards each save with one branch on whether the
    /// caller asked for that group. The generated class adds no state.
    /// Callers that never request generated bytecode never load this class.
    abstract static non-sealed class CompiledScanRustRegexp
            extends ScanPlanRustRegexp
    {
        /// Copies `pattern`, which the caller then discards. Only the generated subclass calls
        /// this.
        CompiledScanRustRegexp(ScanPlanRustRegexp pattern)
        {
            super(pattern);
        }

        /// Returns a copy of `pattern` with a generated class, or `null` when the
        /// pattern is not eligible or the class cannot be generated. The declared type keeps
        /// verification of the base class from loading this class.
        static RustRegexp create(RustRegexp pattern)
        {
            if (!(pattern instanceof ScanPlanRustRegexp scanPlanPattern)) {
                return null;
            }
            return scanPlanPattern.generateScanPattern(CompiledScanRustRegexp.class);
        }

        @Override
        public boolean usesGeneratedBytecode()
        {
            return true;
        }

        // Short boolean calls run the generated class rather than the ordinary engine.
        @Override
        boolean usesOrdinaryShortBooleanMatchForDiagnostics()
        {
            return false;
        }
    }
}
