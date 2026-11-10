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

import static java.util.Objects.requireNonNull;

/// A compiled regular subset of the Java [java.util.regex.Pattern] language over UTF-8 Slice
/// inputs.
///
/// Constructs that require backtracking, stateful assertions, or unsupported Java assertion semantics
/// fail during compilation. Match offsets are UTF-8 byte offsets rather than the UTF-16 indexes returned
/// by [java.util.regex.Matcher].
public sealed class JavaRegexp
        extends CompiledPattern
        permits JavaRegexp.ByteTableJavaRegexp,
                JavaRegexp.ClassRunJavaRegexp,
                JavaRegexp.ExactLiteralJavaRegexp,
                JavaRegexp.FixedWidthJavaRegexp,
                JavaRegexp.LiteralAlternationJavaRegexp,
                JavaRegexp.LiteralKernelJavaRegexp,
                JavaRegexp.LiteralSetJavaRegexp,
                JavaRegexp.ScanPlanJavaRegexp
{
    /// Mutable Java-language compilation options. Compilation snapshots every option, so later
    /// changes do not affect an existing [JavaRegexp]. Options instances are not thread-safe.
    public static final class Options
    {
        private boolean unixLines;
        private boolean caseInsensitive;
        private boolean comments;
        private boolean multiline;
        private boolean literal;
        private boolean dotMatchesNewline;
        private boolean unicodeCase;
        private boolean unicodeCharacterClasses;
        private long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
        private ByteFrequencies byteFrequencies = ByteFrequencies.uniform();
        private boolean generateBytecode;

        private Options() {}

        public static Options defaults()
        {
            return new Options();
        }

        public Options setUnixLines(boolean unixLines)
        {
            this.unixLines = unixLines;
            return this;
        }

        public Options setCaseInsensitive(boolean caseInsensitive)
        {
            this.caseInsensitive = caseInsensitive;
            return this;
        }

        public Options setComments(boolean comments)
        {
            this.comments = comments;
            return this;
        }

        public Options setMultiline(boolean multiline)
        {
            this.multiline = multiline;
            return this;
        }

        public Options setLiteral(boolean literal)
        {
            this.literal = literal;
            return this;
        }

        public Options setDotMatchesNewline(boolean dotMatchesNewline)
        {
            this.dotMatchesNewline = dotMatchesNewline;
            return this;
        }

        public Options setUnicodeCase(boolean unicodeCase)
        {
            this.unicodeCase = unicodeCase;
            return this;
        }

        public Options setUnicodeCharacterClasses(boolean unicodeCharacterClasses)
        {
            this.unicodeCharacterClasses = unicodeCharacterClasses;
            return this;
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
        /// @see JavaRegexp#usesGeneratedBytecode()
        public Options setGenerateBytecode(boolean generateBytecode)
        {
            this.generateBytecode = generateBytecode;
            return this;
        }

        int parseFlags()
        {
            int flags = Regexp.LIKE_PERL;
            if (unixLines) {
                flags |= Regexp.JAVA_UNIX_LINES;
            }
            if (comments) {
                flags |= Regexp.JAVA_COMMENTS;
            }
            if (multiline) {
                flags &= ~Regexp.ONE_LINE;
            }
            if (literal) {
                flags |= Regexp.LITERAL;
            }
            if (dotMatchesNewline) {
                flags |= Regexp.DOT_MATCHES_NEWLINE;
            }
            if (unicodeCase || unicodeCharacterClasses) {
                flags |= Regexp.JAVA_UNICODE_CASE;
            }
            if (unicodeCharacterClasses) {
                flags |= Regexp.JAVA_UNICODE_CHARACTER_CLASS;
            }
            if (caseInsensitive) {
                flags |= (flags & Regexp.JAVA_UNICODE_CASE) != 0 ? Regexp.FOLD_CASE : Regexp.ASCII_FOLD_CASE;
            }
            return flags;
        }
    }

    private JavaRegexp(Analysis analysis)
    {
        super(analysis);
    }

    private JavaRegexp(JavaRegexp pattern)
    {
        super(pattern);
    }

    /// Constructs an instance of the analyzed pattern's shape class.
    private static JavaRegexp create(Analysis analysis)
    {
        return switch (analysis.shape()) {
            case ENGINE -> new JavaRegexp(analysis);
            case EXACT_LITERAL -> new ExactLiteralJavaRegexp(analysis);
            case LITERAL_KERNEL -> new LiteralKernelJavaRegexp(analysis);
            case LITERAL_ALTERNATION -> analysis.literalSetExtendingLiterals() == null ? new LiteralAlternationJavaRegexp(analysis) : new LiteralSetJavaRegexp(analysis);
            case FIXED_WIDTH -> new FixedWidthJavaRegexp(analysis);
            case CLASS_RUN -> new ClassRunJavaRegexp(analysis);
            case BYTE_TABLE -> new ByteTableJavaRegexp(analysis);
            case SCAN_PLAN -> new ScanPlanJavaRegexp(analysis);
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
        return JavaRegexpParser.parse(pattern, options.parseFlags()).patternInfo();
    }

    public static JavaRegexp compile(Slice pattern)
    {
        return compile(pattern, Options.defaults());
    }

    /// Compiles a copied snapshot of `pattern` and the current option values.
    public static JavaRegexp compile(Slice pattern, Options options)
    {
        return compile(pattern, options, ScanPlanMode.DEFAULT);
    }

    /// Compiles `pattern` as [#compile(Slice,Options)] does, but without a scan plan,
    /// so tests and benchmarks can compare a plan with the ordinary engine.
    static JavaRegexp compileWithoutScanPlanForTesting(Slice pattern, Options options)
    {
        return compile(pattern, options, ScanPlanMode.DISABLED);
    }

    /// Compiles `pattern` as [#compile(Slice,Options)] does, but a scan plan
    /// continues every unanchored search on the ordinary engine before its first attempt.
    static JavaRegexp compileForcingScanPlanHandoffForTesting(Slice pattern, Options options)
    {
        return compile(pattern, options, ScanPlanMode.FORCE_HANDOFF);
    }

    private static JavaRegexp compile(Slice pattern, Options options, ScanPlanMode scanPlanMode)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        Slice patternCopy = pattern.copy();
        int parseFlags = options.parseFlags();
        ParseResult parsed = JavaRegexpParser.parse(patternCopy, parseFlags);
        JavaRegexp compiled = create(analyzeParsed(patternCopy, parsed, parseFlags, options.maxMemory(), options.byteFrequencies(), ScanPlan.Language.JAVA, scanPlanMode));
        if (options.generateBytecode()) {
            JavaRegexp generated = CompiledScanJavaRegexp.create(compiled);
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

    /// Returns the number of non-overlapping matches, including empty matches.
    /// Empty matches advance by one UTF-8 code point as in [RegexpMatcher#find()].
    /// No capture values are returned.
    @Override
    public long count(Slice input)
    {
        return super.count(input);
    }

    /// A pattern of the [exact-literal shape][Shape#EXACT_LITERAL].
    static final class ExactLiteralJavaRegexp
            extends JavaRegexp
    {
        private ExactLiteralJavaRegexp(Analysis analysis)
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
    static final class LiteralKernelJavaRegexp
            extends JavaRegexp
    {
        private LiteralKernelJavaRegexp(Analysis analysis)
        {
            super(analysis);
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnLiteralKernel(input);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnLiteralKernel(text);
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
    static final class LiteralAlternationJavaRegexp
            extends JavaRegexp
    {
        private final boolean literalSetCount;

        private LiteralAlternationJavaRegexp(Analysis analysis)
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

    /// A pattern of the [literal-alternation shape][Shape#LITERAL_ALTERNATION] whose program did
    /// not fit the budget, so it compiled none: every operation runs the literal set.
    static final class LiteralSetJavaRegexp
            extends JavaRegexp
    {
        // The literals that a full match reads beside the literal set.
        private final LiteralSetSpanMatcher.ExtendingLiterals extendingLiterals;

        private LiteralSetJavaRegexp(Analysis analysis)
        {
            super(analysis);
            this.extendingLiterals = requireNonNull(analysis.literalSetExtendingLiterals(), "literalSetExtendingLiterals is null");
        }

        @Override
        public boolean find(Slice input)
        {
            return findOnLiteralAlternation(input);
        }

        @Override
        long countMatches(Slice text)
        {
            return countOnLiteralAlternation(text, true);
        }

        @Override
        boolean countsOnLiteralSetForDiagnostics()
        {
            return true;
        }

        @Override
        boolean matchInternal(Prog prog, Slice text, int contextStart, int contextEnd, int start, int end, Anchor anchor, int[] groupOffsets, MatchWorkspaces workspaces)
        {
            return matchInternalOnLiteralSet(prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces, extendingLiterals);
        }
    }

    /// A pattern of the [fixed-width shape][Shape#FIXED_WIDTH].
    static final class FixedWidthJavaRegexp
            extends JavaRegexp
    {
        private FixedWidthJavaRegexp(Analysis analysis)
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
    static final class ClassRunJavaRegexp
            extends JavaRegexp
    {
        private final boolean retainedClassCountDfa;

        private ClassRunJavaRegexp(Analysis analysis)
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
    static final class ByteTableJavaRegexp
            extends JavaRegexp
    {
        private ByteTableJavaRegexp(Analysis analysis)
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
    /// through [CompiledScanJavaRegexp].
    static sealed class ScanPlanJavaRegexp
            extends JavaRegexp
            permits CompiledScanJavaRegexp
    {
        private final boolean ordinaryShortBooleanMatch;
        private final boolean singleAttemptCount;
        private final boolean literalKernelFind;

        private ScanPlanJavaRegexp(Analysis analysis)
        {
            super(analysis);
            this.ordinaryShortBooleanMatch = analysis.ordinaryShortBooleanMatch();
            this.singleAttemptCount = analysis.singleAttemptCount();
            this.literalKernelFind = analysis.literalKernelFind();
        }

        /// Copies `pattern`, which the caller then discards.
        private ScanPlanJavaRegexp(ScanPlanJavaRegexp pattern)
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

    /// A Java pattern whose case-sensitive scan plan was compiled to its own hidden class,
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
    abstract static non-sealed class CompiledScanJavaRegexp
            extends ScanPlanJavaRegexp
    {
        /// Copies `pattern`, which the caller then discards. Only the generated subclass calls
        /// this.
        CompiledScanJavaRegexp(ScanPlanJavaRegexp pattern)
        {
            super(pattern);
        }

        /// Returns a copy of `pattern` with a generated class, or `null` when the
        /// pattern is not eligible or the class cannot be generated. The declared type keeps
        /// verification of the base class from loading this class.
        static JavaRegexp create(JavaRegexp pattern)
        {
            if (!(pattern instanceof ScanPlanJavaRegexp scanPlanPattern)) {
                return null;
            }
            return scanPlanPattern.generateScanPattern(CompiledScanJavaRegexp.class);
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
