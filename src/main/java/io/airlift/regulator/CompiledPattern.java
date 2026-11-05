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

import java.util.Arrays;
import java.util.Map;
import java.util.function.IntPredicate;

import static java.util.Objects.requireNonNull;

/// The matching engine of a compiled pattern: its analysis, programs, and routes, and the
/// matching operations of the public pattern class that extends it. RE2, Java, and Rust patterns
/// extend the engine, and a Trino pattern holds one when it needs one.
///
/// Instances are immutable and thread-safe. Matching operates directly on [Slice] byte ranges;
/// offsets are byte offsets relative to the logical Slice, not its backing array.
@SuppressWarnings("CharUsedInArithmeticContext")
abstract sealed class CompiledPattern
        permits JavaRegexp,
                Re2,
                RustRegexp,
                TrinoCompiledPattern
{
    enum BooleanPlanKind
    {
        GENERAL,
        LITERAL_SEARCH,
        NULLABLE_START,
        CONTAINS,
        EQUALS,
        STARTS_WITH,
        ENDS_WITH,
        EQUALS_FINAL_LINE,
        ENDS_WITH_FINAL_LINE,
        LOWERED_PROGRAM,
    }

    /// How [#countMatches] counts a pattern with a case-sensitive scan plan. See
    /// [#scanPlanCount()].
    enum ScanPlanCount
    {
        // countMatches loops over the search of an unanchored plan.
        SEARCH_LOOP,
        // countMatches makes one start-anchored boolean attempt, which runs on the ordinary
        // engine over a short input when the plan allows it.
        SINGLE_ATTEMPT,
        // countMatches answers without the plan.
        OTHER,
    }

    /// Selects the scan plan an RE2, Trino, Java, or Rust compile builds. Only tests request
    /// [#FORCE_HANDOFF].
    enum ScanPlanMode
    {
        // Builds the plan for every eligible pattern.
        DEFAULT,
        // Builds a plan that continues every unanchored search on the ordinary engine before its
        // first attempt.
        FORCE_HANDOFF,
        // Builds no plan.
        DISABLED,
    }

    // Leave large inputs to the DFA's selective byte scans instead of a scalar table walk.
    private static final int MAX_DIRECT_BYTE_SCAN_BYTES = 64;
    // Boolean matches of a plan that allows it run on the ordinary engine up to this input
    // length. The forked OPTIONALS and OPTIONAL_RETRY plans lost to the ordinary engine on every
    // target host on rows of up to 40 bytes, and won on Graviton and broke even on Intel at 64
    // bytes.
    private static final int MAX_ORDINARY_SHORT_BOOLEAN_MATCH_BYTES = 48;

    // Internal construction metadata, stored outside the parser flag range to preserve the
    // pattern's compact object layout. It is consulted only by lazy reverse-program compilation.
    private static final int TRINO_COMPILER_DIALECT_FLAG = 1 << 31;

    // Above this size the existing rejection paths are already cheap, while loading
    // minimum-width metadata measurably affected the sub-nanosecond anchored path.
    private static final int MINIMUM_LENGTH_CHECK_LIMIT = 4 * 1024;
    private static final int DIRECT_BIT_STATE_CAPTURE_MINIMUM_LENGTH = 4 * 1024;
    private static final int LOWERED_CANDIDATE_SEARCH_BYTES = 64;
    // After this many consecutive unanchored searches of one matcher's input whose DFA failed, in
    // a program whose DFA can quit on a byte outside ASCII, the matcher's later searches of that
    // input skip the DFA. Text that makes such a DFA quit in one search usually does so in the
    // next, and each quit costs a DFA start before the fallback engines run.
    static final int DFA_QUIT_STREAK_LIMIT = 4;

    // The first-byte prefix scan restarts its word scan after each false candidate, which on rows
    // shorter than this many bytes costs more than the substring search.
    private static final int EXACT_LITERAL_SPAN_PREFIX_SCAN_MINIMUM_BYTES = 64;

    // The results of matchOnPreEngineMatcher.
    private static final int PRE_ENGINE_NO_MATCH = 0;
    private static final int PRE_ENGINE_MATCH = 1;
    private static final int PRE_ENGINE_CONTINUE = 2;

    interface MatchWorkspaces {}

    private record FixedMatchWorkspaces(
            OnePass.Workspace onePassWorkspace,
            BitState.Workspace bitStateWorkspace,
            Nfa.Workspace nfaWorkspace)
            implements MatchWorkspaces {}

    private static final MatchWorkspaces NO_MATCH_WORKSPACES = new FixedMatchWorkspaces(null, null, null);

    enum Anchor
    {
        UNANCHORED,
        ANCHOR_START,
        ANCHOR_BOTH,
    }

    record FanoutResult(int maxBucket, int[] histogram) {}

    /// The class a pattern compiles to within its language, which fixes the routes of its
    /// full-Slice `find`, `count`, `matches`, and `lookingAt`. Every shape other than
    /// [#ENGINE] requires a pattern that cannot match empty.
    enum Shape
    {
        // The ordinary engine, on the lowered boolean program where there is one, with the literal
        // matches and lookingAt plans.
        ENGINE,
        // A nonempty literal anywhere in the input, found by the program's prefix scan.
        EXACT_LITERAL,
        // A pattern whose find uses a literal kernel: contains, prefix, suffix, or equality.
        LITERAL_KERNEL,
        // An alternation of short literals, found by the literal-alternation span matcher.
        LITERAL_ALTERNATION,
        // A fixed-width pattern, found by the fixed-width byte span matcher.
        FIXED_WIDTH,
        // A large Unicode class repeat, found by the compact class counter, and counted on the
        // counter or on an optional retained count DFA.
        CLASS_RUN,
        // A one-byte member or member run, found by the single-byte table on short inputs.
        BYTE_TABLE,
        // A pattern with a scan plan, whose captures and full-Slice operations run the plan,
        // apart from a find, matches, or lookingAt that a literal kernel answers, and the
        // short-input and count routes that leave the plan for the ordinary engine.
        SCAN_PLAN,
    }

    /// The analysis, programs, and plans that a compile builds for one pattern, with the shape and
    /// routes [#selectRoutes] chose for it, which the pattern class's constructor receives. Route
    /// selection reserves memory from `partialProg`'s DFA budget, so one analysis constructs one
    /// pattern.
    ///
    /// @param matcherClassCounter off the compact route, the counter that finds a class pattern's
    ///         matches, whose memory is reserved, or `null`
    /// @param singleByteMatcherReserved whether the single-byte table's memory is reserved
    /// @param retainedClassCountDfa whether a [Shape#CLASS_RUN] pattern counts on the retained
    ///         class count DFA rather than its counter
    /// @param ordinaryShortBooleanMatch whether unanchored boolean calls of a
    ///         [Shape#SCAN_PLAN] pattern's start-anchored plan run on the ordinary engine over
    ///         short inputs
    /// @param singleAttemptCount whether a [Shape#SCAN_PLAN] pattern's start-anchored
    ///         plan counts with one boolean attempt
    /// @param literalKernelFind whether a [Shape#SCAN_PLAN] pattern's full-Slice find
    ///         runs the literal kernel of its find plan rather than the plan
    record Analysis(
            int flags,
            long maxMemory,
            Compiler.Dialect compilerDialect,
            boolean longestMatch,
            Slice pattern,
            Regexp regexp,
            ExpressionAnalysis expressionAnalysis,
            Regexp suffixRegexp,
            Regexp normalizedReverseRegexp,
            byte[] requiredPrefix,
            boolean requiredPrefixFoldCase,
            int matchLength,
            int exactLiteralLength,
            Prog partialProg,
            BooleanPlans booleanPlans,
            int capturingGroupCount,
            Map<Integer, String> capturingGroupNames,
            BoundedCharacterClassCounter compactBoundedCharacterClass,
            Shape shape,
            BoundedCharacterClassCounter matcherClassCounter,
            boolean singleByteMatcherReserved,
            boolean retainedClassCountDfa,
            boolean ordinaryShortBooleanMatch,
            boolean singleAttemptCount,
            boolean literalKernelFind)
    {
        Analysis
        {
            requireNonNull(shape, "shape is null");
        }
    }

    private final int flags;
    private final long maxMemory;
    private final Slice pattern;
    private final Regexp regexp;
    private final ExpressionAnalysis expressionAnalysis;
    private final Prog partialProg;
    private final boolean longestMatch;
    private final int capturingGroupCount;
    private final Map<Integer, String> capturingGroupNames;
    private volatile Map<String, Integer> namedCapturingGroups;

    // AST-specialized analyzers retain the semantic suffix. Reverse compilation separately
    // retains the normalized semantic or lowered form.
    private final Regexp suffixRegexp;
    private final Regexp normalizedReverseRegexp;
    private final byte[] requiredPrefix;
    private final boolean requiredPrefixFoldCase;
    private final int matchLength;
    // Every match is one character as the program reads it, as for \pL or (?s:.).
    private final boolean singleCharacterMatch;
    private final int exactLiteralLength;
    private final BooleanPlans booleanPlans;
    // Whether the ordinary engine's disjoint suffix-repeat, whole-input capture, or word-run
    // matcher exists, so the common call tests one field before the engine cascade.
    private final boolean hasPreEngineMatcher;
    // Whether a matcher's search clears its groups before matching. Every shape class clears,
    // since a shape may override matchInternal to answer without the ordinary engine; the language
    // classes themselves need not, since the ordinary engine writes every slot of a match.
    private final boolean clearsGroupsBeforeMatch;
    // Off the compact route, the counter that finds a class pattern's matches: the single members
    // of one large Unicode class, or the runs of a greedy class repeat. Count shares a class
    // repeat's counter.
    private final BoundedCharacterClassCounter matcherClassCounter;

    private volatile Prog reverseProg;
    private volatile boolean reverseProgComputed;
    private volatile BoundedCharacterClassCounter boundedCharacterClassCounter;
    private volatile SingleByteMatcher singleByteMatcher;

    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "The sealed hierarchy permits only classes of this package; each scan-plan shape class permits only its non-sealed CompiledScan class, which only its generated hidden class extends")
    CompiledPattern(Analysis analysis)
    {
        Compiler.Dialect compilerDialect = requireNonNull(analysis.compilerDialect(), "compilerDialect is null");
        this.flags = compilerDialect == Compiler.Dialect.TRINO ? analysis.flags() | TRINO_COMPILER_DIALECT_FLAG : analysis.flags();
        this.maxMemory = analysis.maxMemory();
        this.longestMatch = analysis.longestMatch();
        this.pattern = requireNonNull(analysis.pattern(), "pattern is null");
        this.regexp = analysis.regexp();
        this.expressionAnalysis = requireNonNull(analysis.expressionAnalysis(), "expressionAnalysis is null");
        this.suffixRegexp = analysis.suffixRegexp();
        this.normalizedReverseRegexp = analysis.normalizedReverseRegexp();
        this.requiredPrefix = analysis.requiredPrefix();
        this.requiredPrefixFoldCase = analysis.requiredPrefixFoldCase();
        this.matchLength = analysis.matchLength();
        this.singleCharacterMatch = requiredPrefix == null && matchesOneUtf8Character(suffixRegexp);
        this.exactLiteralLength = analysis.exactLiteralLength();
        this.partialProg = requireNonNull(analysis.partialProg(), "partialProg is null");
        this.booleanPlans = requireNonNull(analysis.booleanPlans(), "booleanPlans is null");
        this.hasPreEngineMatcher = booleanPlans.disjointSuffixRepeatMatcher() != null || booleanPlans.wholeInputCapturePlan() != null || booleanPlans.wordRunMatcher() != null;
        this.clearsGroupsBeforeMatch = !isOrdinaryEngineClass(getClass());
        this.matcherClassCounter = analysis.matcherClassCounter();
        if (!analysis.singleByteMatcherReserved()) {
            singleByteMatcher = SingleByteMatcher.unsupported();
        }
        this.capturingGroupCount = analysis.capturingGroupCount();
        this.capturingGroupNames = requireNonNull(analysis.capturingGroupNames(), "capturingGroupNames is null");
        // Count reuses a class repeat's matcher counter; its own analysis takes repeats only, so a
        // single class's counter serves matchers alone.
        this.boundedCharacterClassCounter = matcherClassCounter != null && suffixRegexp.op() != RegexpOp.CHAR_CLASS ? matcherClassCounter : analysis.compactBoundedCharacterClass();
        // Every shape other than the engine answers its operations on the assumption that no
        // match is empty.
        if (analysis.shape() != Shape.ENGINE && canMatchEmpty()) {
            throw new IllegalStateException("a pattern that can match empty has no shape other than the engine: " + analysis.shape());
        }
        // An exact literal answers matches and lookingAt by equality and prefix, as its plans say.
        if (analysis.shape() == Shape.EXACT_LITERAL && (exactLiteralLength <= 0 ||
                booleanPlans.matches() != BooleanPlanKind.EQUALS ||
                booleanPlans.lookingAt() != BooleanPlanKind.STARTS_WITH)) {
            throw new IllegalStateException("an exact literal's plans disagree with its shape: length " + exactLiteralLength +
                    ", matches " + booleanPlans.matches() + ", lookingAt " + booleanPlans.lookingAt());
        }
        // A byte table counts past its table on the class counter or the DFA, the only routes of
        // the ordinary count that a one-byte member or member run reaches.
        if (analysis.shape() == Shape.BYTE_TABLE && (booleanPlans.disjointSuffixRepeatMatcher() != null ||
                partialProg.hasDfaUnsupportedAssertions() ||
                requiredPrefix != null ||
                partialProg.anchorStart())) {
            throw new IllegalStateException("a byte table's count disagrees with its shape: disjoint suffix repeat " + (booleanPlans.disjointSuffixRepeatMatcher() != null) +
                    ", DFA-unsupported assertions " + partialProg.hasDfaUnsupportedAssertions() +
                    ", required prefix " + (requiredPrefix != null) +
                    ", anchored start " + partialProg.anchorStart());
        }
    }

    /// Copies every field of `pattern`, which the caller then discards, except the named
    /// group map, which is computed on first use.
    CompiledPattern(CompiledPattern pattern)
    {
        this.flags = pattern.flags;
        this.maxMemory = pattern.maxMemory;
        this.pattern = pattern.pattern;
        this.regexp = pattern.regexp;
        this.expressionAnalysis = pattern.expressionAnalysis;
        this.partialProg = pattern.partialProg;
        this.longestMatch = pattern.longestMatch;
        this.capturingGroupCount = pattern.capturingGroupCount;
        this.capturingGroupNames = pattern.capturingGroupNames;
        this.suffixRegexp = pattern.suffixRegexp;
        this.normalizedReverseRegexp = pattern.normalizedReverseRegexp;
        this.requiredPrefix = pattern.requiredPrefix;
        this.requiredPrefixFoldCase = pattern.requiredPrefixFoldCase;
        this.matchLength = pattern.matchLength;
        this.singleCharacterMatch = pattern.singleCharacterMatch;
        this.exactLiteralLength = pattern.exactLiteralLength;
        this.booleanPlans = pattern.booleanPlans;
        this.hasPreEngineMatcher = pattern.hasPreEngineMatcher;
        this.clearsGroupsBeforeMatch = !isOrdinaryEngineClass(getClass());
        this.reverseProg = pattern.reverseProg;
        this.reverseProgComputed = pattern.reverseProgComputed;
        this.boundedCharacterClassCounter = pattern.boundedCharacterClassCounter;
        this.matcherClassCounter = pattern.matcherClassCounter;
        this.singleByteMatcher = pattern.singleByteMatcher;
    }

    private static boolean matchesOneUtf8Character(Regexp regexp)
    {
        while (regexp.op() == RegexpOp.CAPTURE) {
            regexp = regexp.child(0);
        }
        return (regexp.op() == RegexpOp.CHAR_CLASS || regexp.op() == RegexpOp.ANY_CHAR) && (regexp.parseFlags() & Regexp.LATIN1) == 0;
    }

    /// Analyzes a parsed pattern that compiles in the RE2 dialect: an RE2 pattern compiled from
    /// its parse result, or a pattern that the Java or Rust frontend parsed. The caller passes a
    /// private snapshot that it never mutates. `scanPlanMode` decides whether the scan plan of an
    /// eligible shape is built, and how it runs.
    static Analysis analyzeParsed(Slice pattern, ParseResult parsed, int flags, long maxMemory, ByteFrequencies byteFrequencies, ScanPlan.Language language, ScanPlanMode scanPlanMode)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(parsed, "parsed is null");
        requireNonNull(byteFrequencies, "byteFrequencies is null");
        if (maxMemory <= 0) {
            throw new IllegalArgumentException("maxMemory must be greater than zero: " + maxMemory);
        }
        return build(pattern, parsed, flags, maxMemory, byteFrequencies, false, Compiler.Dialect.RE2, language, scanPlanMode);
    }

    /// Analyzes a pattern parsed by the Trino frontend. The caller passes a private snapshot that
    /// it never mutates. `scanPlanMode` selects the scan plan to build.
    static Analysis analyzeParsedForTrino(Slice pattern, ParseResult parsed, int flags, long maxMemory, ByteFrequencies byteFrequencies, ScanPlanMode scanPlanMode)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(parsed, "parsed is null");
        requireNonNull(byteFrequencies, "byteFrequencies is null");
        if (maxMemory <= 0) {
            throw new IllegalArgumentException("maxMemory must be greater than zero: " + maxMemory);
        }

        long forwardMemory = Math.max(1, maxMemory - Math.max(1, maxMemory / 3));
        Regexp entireRegexp = parsed.regexp();
        CaseFolding caseFolding = parsed.caseFolding();
        FixedWidthByteSpanMatcher fixedWidthMatcher = shouldAnalyzeFixedWidthByteSpanForTrino(parsed)
                ? FixedWidthByteSpanMatcher.analyze(entireRegexp)
                : null;
        if (fixedWidthMatcher != null) {
            Regexp normalizedRegexp = Simplifier.simplify(entireRegexp, caseFolding);
            ExpressionAnalysis expressionAnalysis = ExpressionAnalysis.analyzeNormalized(normalizedRegexp, caseFolding);
            Prog placeholderProgram = Compiler.compileNormalized(Regexp.noMatch(flags), false, forwardMemory, Compiler.Dialect.TRINO, caseFolding);
            if (fixedWidthMatcher.estimatedRetainedSize() <= placeholderProgram.dfaMemory()) {
                placeholderProgram.setDfaMemory(0);
                BooleanPlans booleanPlans = selectBooleanPlans(expressionAnalysis, -1, null, placeholderProgram).withFixedWidthByteMatcher(fixedWidthMatcher);
                return selectRoutes(
                        flags,
                        maxMemory,
                        Compiler.Dialect.TRINO,
                        false,
                        pattern,
                        entireRegexp,
                        expressionAnalysis,
                        entireRegexp,
                        null,
                        null,
                        false,
                        expressionAnalysis.length().encoded(),
                        -1,
                        placeholderProgram,
                        booleanPlans,
                        0,
                        Map.of(),
                        null);
            }
        }
        return build(pattern, parsed, flags, maxMemory, byteFrequencies, false, Compiler.Dialect.TRINO, ScanPlan.Language.TRINO, scanPlanMode);
    }

    static boolean shouldAnalyzeFixedWidthByteSpanForTrino(ParseResult parsed)
    {
        Regexp regexp = parsed.regexp();
        // Exact literals have a smaller dedicated route. Avoid allocating byte predicates for a
        // shape that cannot reach the fixed-width subtype.
        return parsed.capturingGroupCount() == 0 &&
                regexp.op() != RegexpOp.LITERAL &&
                regexp.op() != RegexpOp.LITERAL_STRING &&
                regexp.requiredPrefix(parsed.caseFolding()) == null;
    }

    static Analysis build(
            Slice pattern,
            ParseResult parsed,
            int flags,
            long maxMemory,
            ByteFrequencies byteFrequencies,
            boolean longestMatch,
            Compiler.Dialect compilerDialect,
            ScanPlan.Language scanPlanLanguage,
            ScanPlanMode scanPlanMode)
    {
        long reverseMemory = Math.max(1, maxMemory / 3);
        long forwardMemory = Math.max(1, maxMemory - reverseMemory);

        Regexp entireRegexp = parsed.regexp();
        int capturingGroupCount = parsed.capturingGroupCount();
        CaseFolding caseFolding = parsed.caseFolding();
        BoundedCharacterClassCounter compactBoundedCharacterClass = capturingGroupCount == 0 && !longestMatch
                ? BoundedCharacterClassCounter.analyzeCompactRoute(entireRegexp)
                : null;
        if (compactBoundedCharacterClass != null) {
            long counterMemory = compactBoundedCharacterClass.estimatedRetainedSize();
            if (counterMemory >= forwardMemory) {
                throw new RegexpCompileMemoryLimitException(maxMemory);
            }

            ExpressionAnalysis expressionAnalysis = ExpressionAnalysis.analyzeNormalized(entireRegexp, caseFolding);
            long availableMemory = forwardMemory - counterMemory;
            Prog placeholderProgram = Compiler.compileNormalized(Regexp.noMatch(flags), false, availableMemory, compilerDialect, caseFolding);
            placeholderProgram.setDfaMemory(0);
            BooleanPlans booleanPlans = selectBooleanPlans(expressionAnalysis, -1, null, placeholderProgram);
            Prog partialProgram = placeholderProgram;
            if (Dfa.nativeAccessEnabled() && RetainedCharacterClassCountDfa.supports(entireRegexp)) {
                try {
                    Regexp normalizedRegexp = Simplifier.simplify(entireRegexp, caseFolding);
                    Prog countProgram = Compiler.compileNormalizedForDfa(normalizedRegexp, false, availableMemory, compilerDialect, caseFolding, byteFrequencies);
                    if (countProgram.dfaMemory() >= RetainedCharacterClassCountDfa.MINIMUM_DFA_MEMORY) {
                        partialProgram = countProgram;
                    }
                }
                catch (RegexpCompileException ignored) {
                    // The optional DFA could not fit; the compact counter remains the fallback.
                }
            }
            return selectRoutes(
                    flags,
                    maxMemory,
                    compilerDialect,
                    false,
                    pattern,
                    entireRegexp,
                    expressionAnalysis,
                    entireRegexp,
                    null,
                    null,
                    false,
                    expressionAnalysis.length().encoded(),
                    -1,
                    partialProgram,
                    booleanPlans,
                    0,
                    Map.of(),
                    compactBoundedCharacterClass);
        }

        Regexp normalizedRegexp = Simplifier.simplify(entireRegexp, caseFolding);
        ExpressionAnalysis expressionAnalysis = ExpressionAnalysis.analyzeNormalized(normalizedRegexp, caseFolding);

        // For "^literal...", compile only the suffix and check the required prefix before matching.
        // Retain the suffix AST so reverse-program construction also avoids the stripped literal.
        Regexp suffixRegexp;
        int encodedMatchLength;
        byte[] requiredPrefix = null;
        boolean prefixFoldCase = false;
        Regexp.RequiredPrefixResult prefixResult = entireRegexp.requiredPrefix(caseFolding);
        if (prefixResult != null) {
            requiredPrefix = prefixResult.prefix().getBytes();
            prefixFoldCase = prefixResult.foldCase();
            suffixRegexp = prefixResult.suffix();
            // The whole-expression analysis describes the entire expression, so the split-off
            // suffix needs its own length.
            encodedMatchLength = MatchLength.analyze(suffixRegexp, caseFolding).encoded();
        }
        else {
            suffixRegexp = entireRegexp;
            encodedMatchLength = expressionAnalysis.length().encoded();
        }
        Regexp normalizedSuffixRegexp = suffixRegexp == entireRegexp ? normalizedRegexp : Simplifier.simplify(suffixRegexp, caseFolding);

        // Compute captures from the entire regexp (named groups come from the full pattern).
        Prog semanticProgram = Compiler.compileNormalized(normalizedSuffixRegexp, false, forwardMemory, compilerDialect, caseFolding, byteFrequencies);
        ExpressionAnalysis.LiteralSequence literalSequence = expressionAnalysis.literalSequence();
        Slice exactLiteral = literalSequence == null || literalSequence.anchoredAtStart() || literalSequence.anchoredAtEnd()
                ? null
                : literalSequence.retainedExactLiteral();
        if (exactLiteral != null && exactLiteral.length() != 0) {
            semanticProgram.configurePrefixAccelShared(exactLiteral, false, byteFrequencies);
            Compiler.requireProgramFits(semanticProgram, forwardMemory);
        }
        BooleanPlans booleanPlans = selectBooleanPlans(
                expressionAnalysis,
                exactLiteral == null ? -1 : exactLiteral.length(),
                requiredPrefix,
                semanticProgram);
        // The frontend chooses the plan's language and mode. A plan reports leftmost-first
        // matches, so a longest-match pattern has none.
        ScanPlan scanPlan = scanPlanMode != ScanPlanMode.DISABLED && !longestMatch && !expressionAnalysis.canMatchEmpty()
                ? ScanPlan.analyze(entireRegexp, capturingGroupCount, scanPlanLanguage, scanPlanMode == ScanPlanMode.FORCE_HANDOFF, caseFolding, byteFrequencies)
                : null;
        // Anchored and literal-led plans take precedence over a lowered boolean program. A run-led
        // unanchored plan yields to one, which keeps the lowered final-line route.
        boolean scanPlanPrecedesLoweredProgram = scanPlan != null && (scanPlan.isAnchoredStart() || scanPlan.isLiteralLeading());
        long scanPlanSize = scanPlan == null ? 0 : scanPlan.estimatedRetainedSize();
        if (scanPlanPrecedesLoweredProgram && scanPlanSize <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - scanPlanSize);
            booleanPlans = booleanPlans.withScanPlan(scanPlan);
        }
        LoweredBooleanProgram loweredBooleanProgram = null;
        if (booleanPlans.scanPlan() == null) {
            loweredBooleanProgram = compileLoweredBooleanProgram(
                    suffixRegexp,
                    semanticProgram,
                    booleanPlans,
                    forwardMemory,
                    compilerDialect,
                    caseFolding,
                    byteFrequencies);
        }
        if (loweredBooleanProgram != null) {
            booleanPlans = selectLoweredBooleanPlans(booleanPlans, loweredBooleanProgram.program());
        }
        if (scanPlan != null && !scanPlanPrecedesLoweredProgram &&
                loweredBooleanProgram == null && scanPlanSize <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - scanPlanSize);
            booleanPlans = booleanPlans.withScanPlan(scanPlan);
        }
        TaggedAlternationProgram taggedAlternationProgram = requiredPrefix == null && loweredBooleanProgram == null
                ? TaggedAlternationProgram.compile(entireRegexp, capturingGroupCount, longestMatch, semanticProgram, compilerDialect, caseFolding)
                : null;
        booleanPlans = booleanPlans.withTaggedAlternationProgram(taggedAlternationProgram);
        // A capture search gets the whole forward share as its own allowance, beside the DFA caches'.
        Nfa.planCaptureSearches(semanticProgram, forwardMemory, taggedAlternationProgram == null ? null : taggedAlternationProgram.program(), maxMemory);
        WordRunMatcher wordRunMatcher = requiredPrefix == null && loweredBooleanProgram == null
                ? WordRunMatcher.analyze(entireRegexp, semanticProgram)
                : null;
        if (wordRunMatcher != null && wordRunMatcher.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - wordRunMatcher.estimatedRetainedSize());
            booleanPlans = booleanPlans.withWordRunMatcher(wordRunMatcher);
        }
        WholeInputCapturePlan wholeInputCapturePlan = requiredPrefix == null && loweredBooleanProgram == null
                ? WholeInputCapturePlan.analyze(entireRegexp, capturingGroupCount)
                : null;
        if (wholeInputCapturePlan != null && wholeInputCapturePlan.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - wholeInputCapturePlan.estimatedRetainedSize());
            booleanPlans = booleanPlans.withWholeInputCapturePlan(wholeInputCapturePlan);
        }
        DisjointSuffixRepeatMatcher disjointSuffixRepeatMatcher = requiredPrefix == null && loweredBooleanProgram == null && capturingGroupCount == 0
                ? DisjointSuffixRepeatMatcher.analyze(entireRegexp)
                : null;
        if (disjointSuffixRepeatMatcher != null && disjointSuffixRepeatMatcher.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - disjointSuffixRepeatMatcher.estimatedRetainedSize());
            booleanPlans = booleanPlans.withDisjointSuffixRepeatMatcher(disjointSuffixRepeatMatcher);
        }
        // A literal every match holds after its first element, searched before the DFA runs. It is
        // left out where another plan answers the searches it would serve, and for longest-match
        // patterns, whose matches the leftmost-first search does not report. It takes its memory
        // from the reverse budget, so the forward DFA keeps its whole budget, but leaves the full
        // reverse program, compiled on first use, what its DFA needs to initialize. That is
        // estimated from the forward program, compiled from the same expression: its memory and
        // the least memory of a longest-match DFA over it, and an eighth more, since reversed
        // UTF-8 sequences can take a few more instructions. The reverse prefix program takes half
        // the reverse budget, or less if the search would otherwise not fit beside that estimate,
        // and the search is left out unless its reverse prefix DFA can initialize.
        if (requiredPrefix == null && loweredBooleanProgram == null && !longestMatch && exactLiteral == null &&
                booleanPlans.scanPlan() == null && booleanPlans.wordRunMatcher() == null && booleanPlans.disjointSuffixRepeatMatcher() == null) {
            long forwardEstimate = Compiler.estimatedProgramMemory(semanticProgram) + Dfa.DfaInstance.minimumMemory(semanticProgram, Dfa.DfaInstance.Kind.LONGEST_MATCH);
            long fullReverseMemory = forwardEstimate + forwardEstimate / 8;
            long searchMemory = reverseMemory - fullReverseMemory;
            long prefixMemory = Math.max(1, reverseMemory / 2);
            InnerLiteralSearch innerLiteralSearch = InnerLiteralSearch.analyze(normalizedSuffixRegexp, semanticProgram, prefixMemory, searchMemory, compilerDialect, caseFolding);
            if (innerLiteralSearch != null) {
                booleanPlans = booleanPlans.withInnerLiteralSearch(innerLiteralSearch);
            }
        }
        // A call-free first-and-last-byte search for contains and dot-star spans. Only Trino rows
        // were measured, so other languages' contains keeps Slice.indexOf for a literal of at most
        // LiteralSearchKernel.CANDIDATE_COMPARISON_BUDGET bytes. Slice.indexOf puts no limit on
        // the bytes its failed candidates compare, so for a longer literal, long partial matches
        // cost the input times the literal.
        LiteralSearchKernel literalSearch = createLiteralSearch(booleanPlans, compilerDialect == Compiler.Dialect.TRINO);
        if (literalSearch != null && literalSearch.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - literalSearch.estimatedRetainedSize());
            booleanPlans = booleanPlans.withLiteralSearch(literalSearch);
        }
        // The literal every match begins with behind a leading assertion. The candidate cursor
        // searches for it before verifying each occurrence, and when the DFA cannot run the
        // assertions, the group-zero NFA skips to it instead. Its search is built here, never on
        // first match, and reserves its memory from the program that holds the forward DFA
        // budget: the lowered program, when there is one. A pattern neither can serve keeps no
        // literal, and its DFA keeps the memory. A pattern whose reservation would leave that
        // program's DFAs too little to initialize also keeps no literal.
        byte[] transparentStartLiteral = searchesTransparentStartLiteral(booleanPlans, longestMatch, requiredPrefix, semanticProgram)
                ? semanticProgram.buildTransparentStartLiteral()
                : null;
        if (transparentStartLiteral != null) {
            LiteralSearchKernel transparentStartLiteralSearch = new LiteralSearchKernel(Slices.wrappedBuffer(transparentStartLiteral));
            long transparentStartLiteralSize = transparentStartLiteralSearch.estimatedRetainedSize() + SizeOf.sizeOf(transparentStartLiteral);
            Prog budgetProgram = loweredBooleanProgram == null ? semanticProgram : loweredBooleanProgram.program();
            if (budgetProgram.leavesDfaMinimum(transparentStartLiteralSize)) {
                budgetProgram.setDfaMemory(budgetProgram.dfaMemory() - transparentStartLiteralSize);
                semanticProgram.configureTransparentStartLiteral(transparentStartLiteral, transparentStartLiteralSearch);
            }
        }
        // The regex crate's Aho-Corasick route for large literal alternations, which the forward
        // DFA's candidate starts and the reverse DFA do not scale to. Trino routes every literal
        // alternation itself, and the automaton reports leftmost-first matches only.
        LiteralSetSpanMatcher literalSet = compilerDialect != Compiler.Dialect.TRINO && !longestMatch
                ? LiteralSetSpanMatcher.analyze(entireRegexp, capturingGroupCount, semanticProgram.dfaMemory())
                : null;
        if (literalSet != null && literalSet.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - literalSet.estimatedRetainedSize());
            booleanPlans = booleanPlans.withLiteralSet(literalSet);
        }
        // Span matchers for full-Slice boolean find and count of a pattern that no literal plan,
        // lowered program, or scan plan answers. Each finds the general engine's leftmost-first
        // spans. The literal trie prefers the first alternative, so a longest-match pattern keeps
        // the general engine; every fixed-width match has the same width, so its leftmost-longest
        // and leftmost-first spans coincide.
        boolean generalFind = booleanPlans.find() == BooleanPlanKind.GENERAL && booleanPlans.scanPlan() == null;
        LiteralAlternationSpanMatcher literalAlternationMatcher = generalFind && !longestMatch
                ? LiteralAlternationSpanMatcher.analyze(entireRegexp, capturingGroupCount)
                : null;
        if (literalAlternationMatcher != null && literalAlternationMatcher.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - literalAlternationMatcher.estimatedRetainedSize());
            booleanPlans = booleanPlans.withLiteralAlternationMatcher(literalAlternationMatcher);
        }
        FixedWidthByteSpanMatcher fixedWidthByteMatcher = generalFind && literalAlternationMatcher == null &&
                mayHaveFixedWidthByteSpanMatcher(capturingGroupCount, encodedMatchLength, expressionAnalysis) &&
                !searchesMultiByteInnerLiteral(booleanPlans)
                ? FixedWidthByteSpanMatcher.analyze(entireRegexp, expressionAnalysis)
                : null;
        if (fixedWidthByteMatcher != null && fixedWidthByteMatcher.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - fixedWidthByteMatcher.estimatedRetainedSize());
            booleanPlans = booleanPlans.withFixedWidthByteMatcher(fixedWidthByteMatcher);
        }
        // A run of one-byte members, such as [\t\n\f\r ]+, exists exactly where one member does.
        SingleByteMatcher byteRunMatcher = generalFind && literalAlternationMatcher == null && fixedWidthByteMatcher == null && capturingGroupCount == 0
                ? SingleByteMatcher.analyzeRun(entireRegexp, caseFolding)
                : null;
        if (byteRunMatcher != null && SingleByteMatcher.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - SingleByteMatcher.estimatedRetainedSize());
            booleanPlans = booleanPlans.withByteRunMatcher(byteRunMatcher);
        }

        return selectRoutes(
                flags,
                maxMemory,
                compilerDialect,
                longestMatch,
                pattern,
                entireRegexp,
                expressionAnalysis,
                suffixRegexp,
                loweredBooleanProgram == null ? normalizedSuffixRegexp : loweredBooleanProgram.normalizedRegexp(),
                requiredPrefix,
                prefixFoldCase,
                encodedMatchLength,
                exactLiteral == null ? -1 : exactLiteral.length(),
                semanticProgram,
                booleanPlans,
                capturingGroupCount,
                parsed.capturingGroupNames(),
                null);
    }

    /// Returns the analysis of a pattern with its shape and routes selected. Selection reserves the
    /// memory of the matcher class counter and the single-byte table from `partialProg`'s DFA
    /// budget, before any DFA can snapshot it; the table itself stays lazy.
    private static Analysis selectRoutes(
            int flags,
            long maxMemory,
            Compiler.Dialect compilerDialect,
            boolean longestMatch,
            Slice pattern,
            Regexp regexp,
            ExpressionAnalysis expressionAnalysis,
            Regexp suffixRegexp,
            Regexp normalizedReverseRegexp,
            byte[] requiredPrefix,
            boolean requiredPrefixFoldCase,
            int matchLength,
            int exactLiteralLength,
            Prog partialProg,
            BooleanPlans booleanPlans,
            int capturingGroupCount,
            Map<Integer, String> capturingGroupNames,
            BoundedCharacterClassCounter compactBoundedCharacterClass)
    {
        // The shape full-Slice find takes, in the order each route was added. A pattern whose find
        // runs the ordinary engine on the forward program alone is general, and only a general
        // pattern takes the single-byte, short ordinary, span, or byte-run route.
        Shape shape;
        boolean general = false;
        boolean retainedClassCountDfa = false;
        if (compactBoundedCharacterClass != null) {
            shape = Shape.CLASS_RUN;
            retainedClassCountDfa = partialProg.dfaMemory() >= RetainedCharacterClassCountDfa.MINIMUM_DFA_MEMORY;
        }
        else {
            shape = switch (booleanPlans.find()) {
                case GENERAL -> {
                    general = true;
                    yield Shape.ENGINE;
                }
                // Only Trino's fixed-width route passes no exact literal with a literal search.
                case LITERAL_SEARCH -> exactLiteralLength > 0 ? Shape.EXACT_LITERAL : Shape.ENGINE;
                case NULLABLE_START, LOWERED_PROGRAM -> Shape.ENGINE;
                case CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, EQUALS_FINAL_LINE, ENDS_WITH_FINAL_LINE -> Shape.LITERAL_KERNEL;
            };
        }
        // As on the compact route, a pattern with a capture keeps the engine.
        BoundedCharacterClassCounter matcherClass = requiredPrefix == null && compactBoundedCharacterClass == null && capturingGroupCount == 0
                ? BoundedCharacterClassCounter.analyzeMatcherRoute(suffixRegexp, longestMatch)
                : null;
        if (matcherClass != null && matcherClass.estimatedRetainedSize() <= partialProg.dfaMemory()) {
            partialProg.setDfaMemory(partialProg.dfaMemory() - matcherClass.estimatedRetainedSize());
        }
        else {
            matcherClass = null;
        }
        boolean singleByteMatcher = mayHaveSingleByteMatcher(requiredPrefix, partialProg, matchLength) &&
                SingleByteMatcher.supports(suffixRegexp, expressionAnalysis.caseFolding()) &&
                SingleByteMatcher.estimatedRetainedSize() <= partialProg.dfaMemory();
        if (singleByteMatcher) {
            partialProg.setDfaMemory(partialProg.dfaMemory() - SingleByteMatcher.estimatedRetainedSize());
            if (general) {
                shape = Shape.BYTE_TABLE;
                general = false;
            }
        }
        boolean ordinaryShortMatch = false;
        boolean singleAttemptCount = false;
        boolean literalKernelFind = false;
        ScanPlan scanPlan = booleanPlans.scanPlan();
        if (scanPlan != null) {
            if (scanPlan.isAnchoredStart()) {
                // The short route is the ordinary engine's general search, which must run the
                // DFA. A Trino final-line end or a Java final-terminator end, which the DFA cannot
                // evaluate, keeps the plan.
                ordinaryShortMatch = scanPlan.allowsOrdinaryShortBooleanMatch() && general && !partialProg.hasDfaUnsupportedAssertions();
                // A start-anchored pattern matches at most once, and the attempt evaluates the
                // ends the DFA cannot.
                singleAttemptCount = requiredPrefix != null || partialProg.anchorStart();
            }
            // The contains, prefix, equality, and final-line equality kernels answer existence
            // without reading past the literal; full-Slice find keeps them. The other operations
            // reach the plan, apart from literal matches and lookingAt plans and the short-input
            // and count routes that leave it for the ordinary engine.
            BooleanPlanKind find = booleanPlans.find();
            literalKernelFind = shape == Shape.LITERAL_KERNEL && (find == BooleanPlanKind.CONTAINS ||
                    find == BooleanPlanKind.STARTS_WITH ||
                    find == BooleanPlanKind.EQUALS ||
                    find == BooleanPlanKind.EQUALS_FINAL_LINE);
            shape = Shape.SCAN_PLAN;
        }
        // A retained span or byte-run matcher answers full-Slice find only in place of the
        // general engine.
        else if (general) {
            if (booleanPlans.literalAlternationMatcher() != null) {
                shape = Shape.LITERAL_ALTERNATION;
            }
            else if (booleanPlans.fixedWidthByteMatcher() != null) {
                shape = Shape.FIXED_WIDTH;
            }
            else if (booleanPlans.byteRunMatcher() != null) {
                shape = Shape.BYTE_TABLE;
            }
        }
        return new Analysis(
                flags,
                maxMemory,
                compilerDialect,
                longestMatch,
                pattern,
                regexp,
                expressionAnalysis,
                suffixRegexp,
                normalizedReverseRegexp,
                requiredPrefix,
                requiredPrefixFoldCase,
                matchLength,
                exactLiteralLength,
                partialProg,
                booleanPlans,
                capturingGroupCount,
                capturingGroupNames,
                compactBoundedCharacterClass,
                shape,
                matcherClass,
                singleByteMatcher,
                retainedClassCountDfa,
                ordinaryShortMatch,
                singleAttemptCount,
                literalKernelFind);
    }

    /// Creates a search for a CONTAINS plan whose literal is at least two bytes long and, outside
    /// Trino, longer than [LiteralSearchKernel#CANDIDATE_COMPARISON_BUDGET] bytes, or returns
    /// null. Boolean contains and dot-star span matching share this search.
    private static LiteralSearchKernel createLiteralSearch(BooleanPlans booleanPlans, boolean trino)
    {
        if (booleanPlans.find() != BooleanPlanKind.CONTAINS || booleanPlans.literal().length() < 2) {
            return null;
        }
        if (!trino && booleanPlans.literal().length() <= LiteralSearchKernel.CANDIDATE_COMPARISON_BUDGET) {
            return null;
        }
        return new LiteralSearchKernel(booleanPlans.literal());
    }

    private static LoweredBooleanProgram compileLoweredBooleanProgram(
            Regexp suffixRegexp,
            Prog semanticProgram,
            BooleanPlans booleanPlans,
            long forwardMemory,
            Compiler.Dialect compilerDialect,
            CaseFolding caseFolding,
            ByteFrequencies byteFrequencies)
    {
        if (!requiresProgramExecution(booleanPlans.find()) &&
                booleanPlans.lookingAt() != BooleanPlanKind.GENERAL) {
            return null;
        }

        Regexp loweredRegexp = BooleanRegexpLowering.lowerTerminalFinalLineEnds(suffixRegexp);
        if (loweredRegexp == suffixRegexp) {
            return null;
        }

        Regexp normalizedRegexp = Simplifier.simplify(loweredRegexp, caseFolding);
        Prog program;
        try {
            program = Compiler.compileNormalized(normalizedRegexp, false, forwardMemory, compilerDialect, caseFolding, byteFrequencies);
        }
        catch (RegexpCompileException ignored) {
            // An optional boolean optimization must not reject a valid semantic program.
            return null;
        }
        if (program.hasTextDependentAssertions() || program.hasFullCaseFold()) {
            return null;
        }

        if (forwardMemory > 0) {
            long semanticProgramMemory = Compiler.estimatedProgramMemory(semanticProgram);
            // Compilation has already charged the lowered program, any retained OnePass table,
            // and its prefix's KMP shifts.
            if (semanticProgramMemory > program.dfaMemory()) {
                return null;
            }
            program.setDfaMemory(program.dfaMemory() - semanticProgramMemory);
        }
        // The retained final-line assertion already prevents semantic DFA execution.
        // Charge both programs to the forward budget and give its DFA remainder to the lowered program.
        semanticProgram.setDfaMemory(0);
        return new LoweredBooleanProgram(program, normalizedRegexp);
    }

    private static BooleanPlans selectLoweredBooleanPlans(BooleanPlans plans, Prog program)
    {
        BooleanPlanKind find = requiresProgramExecution(plans.find())
                ? BooleanPlanKind.LOWERED_PROGRAM
                : plans.find();
        BooleanPlanKind lookingAt = plans.lookingAt() == BooleanPlanKind.GENERAL
                ? BooleanPlanKind.LOWERED_PROGRAM
                : plans.lookingAt();
        return new BooleanPlans(plans.literal(), find, lookingAt, plans.matches(), program, plans.taggedAlternationProgram(), plans.wordRunMatcher(), plans.wholeInputCapturePlan(), plans.disjointSuffixRepeatMatcher(), plans.scanPlan(), plans.scanBooleanPlan(), plans.literalSearch(), plans.literalSet(), plans.innerLiteralSearch(), plans.literalAlternationMatcher(), plans.fixedWidthByteMatcher(), plans.byteRunMatcher());
    }

    private static boolean requiresProgramExecution(BooleanPlanKind plan)
    {
        return plan == BooleanPlanKind.GENERAL || plan == BooleanPlanKind.NULLABLE_START;
    }

    int flags()
    {
        return flags & ~TRINO_COMPILER_DIALECT_FLAG;
    }

    private Compiler.Dialect compilerDialect()
    {
        return (flags & TRINO_COMPILER_DIALECT_FLAG) == 0 ? Compiler.Dialect.RE2 : Compiler.Dialect.TRINO;
    }

    /// Returns whether this compiled pattern has a class generated for it. Each language
    /// documents the option that enables it.
    public abstract boolean usesGeneratedBytecode();

    /// Returns a copy of the compiled pattern bytes.
    public Slice pattern()
    {
        return pattern.copy();
    }

    /// Returns the number of capturing groups, excluding group zero (the whole match).
    public int capturingGroupCount()
    {
        return capturingGroupCount;
    }

    /// Returns an immutable mapping from capture names to group numbers.
    public Map<String, Integer> namedCapturingGroups()
    {
        Map<String, Integer> cachedNamedCapturingGroups = namedCapturingGroups;
        if (cachedNamedCapturingGroups != null) {
            return cachedNamedCapturingGroups;
        }
        cachedNamedCapturingGroups = ParseResult.namedCapturingGroups(capturingGroupNames);
        namedCapturingGroups = cachedNamedCapturingGroups;
        return cachedNamedCapturingGroups;
    }

    boolean isNamedCapturingGroupsComputed()
    {
        return namedCapturingGroups != null;
    }

    /// Returns an immutable mapping from group numbers to capture names.
    public Map<Integer, String> capturingGroupNames()
    {
        return capturingGroupNames;
    }

    /// Package-private accessor for prefilter extraction (not for general use).
    /// Matches upstream RE2::Regexp() const.
    Regexp regexp()
    {
        return regexp;
    }

    /// Returns the case folding of the pattern language. The expression analysis carries it, which
    /// keeps it off the compiled-pattern layout.
    CaseFolding caseFolding()
    {
        return expressionAnalysis.caseFolding();
    }

    ExpressionAnalysis expressionAnalysisForDiagnostics()
    {
        return expressionAnalysis;
    }

    boolean retainsPatternForDiagnostics(Slice expectedPattern)
    {
        return pattern == expectedPattern;
    }

    int programSize()
    {
        return partialProg.size();
    }

    int reverseProgramSize()
    {
        Prog prog = reverseProg();
        if (prog == null) {
            return -1;
        }
        return prog.size();
    }

    boolean canMatchEmpty()
    {
        return requiredPrefix == null && partialProg.canMatchEmpty();
    }

    boolean isReverseProgramComputed()
    {
        return reverseProgComputed;
    }

    boolean isBoundedCharacterClassCounterComputed()
    {
        return boundedCharacterClassCounter != null;
    }

    BoundedCharacterClassCounter boundedCharacterClassCounterIfComputedForDiagnostics()
    {
        return boundedCharacterClassCounter;
    }

    /// Returns whether the compact class counter answers this pattern's operations, which only
    /// the class-run shape's classes do.
    boolean usesCompactBoundedCharacterClassForDiagnostics()
    {
        return false;
    }

    boolean hasRetainedFixedWidthByteSpanMatcher()
    {
        return booleanPlans.fixedWidthByteMatcher() != null;
    }

    boolean usesScanPlanForDiagnostics()
    {
        return booleanPlans.scanPlan() != null;
    }

    boolean usesPartialScanPlanForDiagnostics()
    {
        ScanPlan plan = booleanPlans.scanPlan();
        return plan != null && plan.isAnchoredStart() && plan.isPartialMatch();
    }

    boolean usesAsciiFoldedScanExecutorForDiagnostics()
    {
        ScanPlan plan = booleanPlans.scanPlan();
        return plan != null && plan.usesAsciiFoldedExecutor();
    }

    /// Returns whether full-Slice [#find(Slice)] dispatches to the scan plan rather than a
    /// retained literal kernel. Only the scan-plan shape's classes can.
    boolean usesScanPlanForFindForDiagnostics()
    {
        return false;
    }

    /// Returns whether unanchored boolean searches may continue on the ordinary engine although
    /// calls that report match boundaries use the unanchored scan plan.
    boolean usesOrdinaryUnanchoredBooleanSearchForDiagnostics()
    {
        ScanPlan plan = booleanPlans.scanPlan();
        return plan != null && plan.allowsOrdinaryBooleanSearch();
    }

    /// Returns whether unanchored boolean calls of a start-anchored plan run on the ordinary
    /// engine over short inputs although calls that report positions use the plan. Only the
    /// scan-plan shape's classes can.
    boolean usesOrdinaryShortBooleanMatchForDiagnostics()
    {
        return false;
    }

    boolean usesTaggedAlternationForDiagnostics()
    {
        return booleanPlans.taggedAlternationProgram() != null;
    }

    Prog taggedAlternationProgramForDiagnostics()
    {
        TaggedAlternationProgram taggedAlternationProgram = booleanPlans.taggedAlternationProgram();
        return taggedAlternationProgram == null ? null : taggedAlternationProgram.program();
    }

    Prog forwardProgramForDiagnostics()
    {
        return partialProg;
    }

    Prog booleanProgramForDiagnostics()
    {
        return booleanPlans.loweredProgram();
    }

    Prog reverseProgramIfComputedForDiagnostics()
    {
        return reverseProg;
    }

    boolean hasRequiredPrefixForDiagnostics()
    {
        return requiredPrefix != null;
    }

    Slice exactLiteralForDiagnostics()
    {
        return regexp.exactLiteral();
    }

    LiteralSearchKernel literalSearchForDiagnostics()
    {
        return booleanPlans.literalSearch();
    }

    /// Returns the matcher that full-Slice boolean find and count search for an alternation of
    /// at most [LiteralAlternationSpanMatcher#MAXIMUM_ALTERNATIVES] literals, or `null`.
    LiteralAlternationSpanMatcher literalAlternationSpanMatcherForDiagnostics()
    {
        return booleanPlans.literalAlternationMatcher();
    }

    /// Returns the matcher that [RegexpMatcher#find()] searches for an alternation of more than
    /// [LiteralAlternationSpanMatcher#MAXIMUM_ALTERNATIVES] literals, or `null`.
    LiteralSetSpanMatcher literalSetSpanMatcher()
    {
        return booleanPlans.literalSet();
    }

    boolean hasExactLiteralSpan()
    {
        return exactLiteralLength >= 0;
    }

    DotStarLiteralSpanMatcher createDotStarLiteralSpanMatcher()
    {
        return DotStarLiteralSpanMatcher.analyze(expressionAnalysis, booleanPlans.literalSearch());
    }

    OrderedLiteralMatcher createOrderedLiteralMatcher()
    {
        return OrderedLiteralMatcher.analyze(expressionAnalysis);
    }

    boolean tryReserveForwardDfaMemory(long retainedSize)
    {
        if (retainedSize <= 0 ||
                booleanPlans.loweredProgram() != null ||
                booleanPlans.taggedAlternationProgram() != null ||
                retainedSize > partialProg.dfaMemory()) {
            return false;
        }
        partialProg.setDfaMemory(partialProg.dfaMemory() - retainedSize);
        return true;
    }

    FixedWidthByteSpanMatcher createFixedWidthByteSpanMatcher()
    {
        FixedWidthByteSpanMatcher retainedMatcher = booleanPlans.fixedWidthByteMatcher();
        if (retainedMatcher != null) {
            return retainedMatcher;
        }
        if (!mayHaveFixedWidthByteSpanMatcher()) {
            return null;
        }
        return FixedWidthByteSpanMatcher.analyze(regexp, expressionAnalysis);
    }

    boolean mayHaveFixedWidthByteSpanMatcher()
    {
        // Trino compilation can retain a matcher analyzed from the parsed tree even when the
        // simplified tree does not qualify, such as a [Kk] class rewritten to a folded literal.
        if (hasRetainedFixedWidthByteSpanMatcher()) {
            return true;
        }
        return mayHaveFixedWidthByteSpanMatcher(capturingGroupCount, matchLength, expressionAnalysis);
    }

    private static boolean mayHaveFixedWidthByteSpanMatcher(int capturingGroupCount, int encodedMatchLength, ExpressionAnalysis expressionAnalysis)
    {
        ExpressionAnalysis.LiteralSequence literalSequence = expressionAnalysis.literalSequence();
        return capturingGroupCount == 0 &&
                MatchLength.fixed(encodedMatchLength) >= 0 &&
                literalSequence != null &&
                literalSequence.literalCount() != 0;
    }

    // The fixed-width matcher checks every occurrence of one byte, such as the s of [a-z]shing,
    // which English text holds far more often than the inner literal shing the DFA searches for
    // instead. A one-byte inner literal, such as the - of an ISO date, is no rarer than the
    // matcher's byte.
    private static boolean searchesMultiByteInnerLiteral(BooleanPlans booleanPlans)
    {
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        return innerLiteralSearch != null && innerLiteralSearch.minimumLiteralLength() >= 2;
    }

    long findExactLiteralSpan(Slice input, int start)
    {
        requireNonNull(input, "input is null");
        if (exactLiteralLength < 0) {
            return Dfa.SEARCH_FAILED;
        }
        if (start < 0 || start > input.length()) {
            return Dfa.SEARCH_NO_MATCH;
        }
        if (exactLiteralLength == 0) {
            return ((long) start << 32) | (start & 0xFFFF_FFFFL);
        }

        if (exactLiteralSpanUsesPrefixScan(input.length() - start)) {
            return findExactLiteralSpanWithPrefixScan(input, start);
        }
        int matchStart = LiteralMatchKernel.find(input, booleanPlans.literal(), start);
        if (matchStart < 0) {
            return Dfa.SEARCH_NO_MATCH;
        }
        int matchEnd = matchStart + exactLiteralLength;
        return ((long) matchStart << 32) | (matchEnd & 0xFFFF_FFFFL);
    }

    /// Returns whether [#findExactLiteralSpan(Slice,int)] searches `remainingBytes`
    /// with the prefix scan rather than the substring search: when at least
    /// {@value #EXACT_LITERAL_SPAN_PREFIX_SCAN_MINIMUM_BYTES} bytes remain, or when the byte
    /// frequencies chose a probe byte other than the literal's first byte.
    boolean exactLiteralSpanUsesPrefixScan(int remainingBytes)
    {
        return remainingBytes >= EXACT_LITERAL_SPAN_PREFIX_SCAN_MINIMUM_BYTES || partialProg.prefixAccelProbeOffset() != 0;
    }

    /// Answers [#findExactLiteralSpan(Slice,int)] with the prefix scan. It is out of line so
    /// that the short-row substring search stays small enough for C2 to inline into per-row callers
    /// without pulling the prefix scan in with it.
    private long findExactLiteralSpanWithPrefixScan(Slice input, int start)
    {
        int arrayMatchStart = findExactLiteral(input.byteArray(), input.byteArrayOffset() + start, input.length() - start);
        if (arrayMatchStart < 0) {
            return Dfa.SEARCH_NO_MATCH;
        }
        int matchStart = arrayMatchStart - input.byteArrayOffset();
        int matchEnd = matchStart + exactLiteralLength;
        return ((long) matchStart << 32) | (matchEnd & 0xFFFF_FFFFL);
    }

    /// Returns the array position of the first occurrence of the nonempty exact literal in
    /// `[offset, offset + length)` of `bytes`, or `-1` when none occurs.
    /// Boolean find and count of an exact-literal pattern search with this prefix scan, whose
    /// probe byte the compile-time byte frequencies chose. Span searches use it when
    /// [#exactLiteralSpanUsesPrefixScan(int)] says so.
    private int findExactLiteral(byte[] bytes, int offset, int length)
    {
        return partialProg.prefixAccel(bytes, offset, length);
    }

    InnerLiteralSearch innerLiteralSearchForDiagnostics()
    {
        return booleanPlans.innerLiteralSearch();
    }

    BooleanPlanKind findPlanForDiagnostics()
    {
        return booleanPlans.find();
    }

    BooleanPlanKind lookingAtPlanForDiagnostics()
    {
        return booleanPlans.lookingAt();
    }

    BooleanPlanKind matchesPlanForDiagnostics()
    {
        return booleanPlans.matches();
    }

    // The language classes themselves run every program call on the ordinary engine; their
    // shape subclasses may not.
    private static boolean isOrdinaryEngineClass(Class<?> type)
    {
        return type == Re2.class || type == RustRegexp.class || type == JavaRegexp.class || type == TrinoCompiledPattern.class;
    }

    /// Returns the fewest bytes any match spans, or 0 when it can be empty.
    int minimumMatchLength()
    {
        return MatchLength.minimum(matchLength);
    }

    boolean canUseDirectBitStateCapture(Prog prog, int searchLength, int captureCount)
    {
        return !longestMatch &&
                searchLength >= DIRECT_BIT_STATE_CAPTURE_MINIMUM_LENGTH &&
                captureCount > 1 &&
                !prog.isOnePass() &&
                prog.canBitState() &&
                searchLength <= prog.bitStateTextMaxSize();
    }

    FanoutResult programFanout()
    {
        return fanout(partialProg);
    }

    FanoutResult reverseProgramFanout()
    {
        Prog prog = reverseProg();
        if (prog == null) {
            return new FanoutResult(-1, new int[0]);
        }
        return fanout(prog);
    }

    /// Returns whether the pattern has a match anywhere in `input` without allocating capture storage.
    public boolean find(Slice input)
    {
        return findOnEngine(input);
    }

    /// The full-Slice find of the ordinary engine: the nullable-start shortcut, then an ordinary
    /// search of the lowered boolean program where there is one, or else of the forward program.
    private boolean findOnEngine(Slice input)
    {
        requireNonNull(input, "input is null");
        if (canReturnEmptyAtStartForBooleanMatch(input, 0)) {
            return true;
        }
        Prog loweredProgram = booleanPlans.loweredProgram();
        int length = input.length();
        return matchOnOrdinaryEngine(loweredProgram != null ? loweredProgram : partialProg, input, 0, length, 0, length, Anchor.UNANCHORED, null, NO_MATCH_WORKSPACES);
    }

    /// Searches all of `input` on the ordinary engine without reporting positions.
    private boolean findOnForwardProgram(Slice input)
    {
        int length = input.length();
        return matchOnOrdinaryEngine(partialProg, input, 0, length, 0, length, Anchor.UNANCHORED, null, NO_MATCH_WORKSPACES);
    }

    /// The full-Slice find of a nonempty exact literal: the program's prefix scan, whose probe
    /// byte the compile-time byte frequencies chose.
    final boolean findOnExactLiteral(Slice input)
    {
        requireNonNull(input, "input is null");
        return findExactLiteral(input.byteArray(), input.byteArrayOffset(), input.length()) >= 0;
    }

    /// The full-Slice find of a literal plan: the contains, prefix, suffix, or equality kernel
    /// of the find plan's kind. The switch is this route's own, so the kinds of a pattern's
    /// `matches` and `lookingAt` stay out of its profile.
    final boolean findOnLiteralKernel(Slice input)
    {
        requireNonNull(input, "input is null");
        Slice literal = booleanPlans.literal();
        return switch (booleanPlans.find()) {
            case CONTAINS -> containsBooleanPlanLiteral(input, literal);
            case EQUALS -> LiteralMatchKernel.equals(input, literal);
            case STARTS_WITH -> LiteralMatchKernel.startsWith(input, literal);
            case ENDS_WITH -> LiteralMatchKernel.endsWith(input, literal);
            case EQUALS_FINAL_LINE -> equalsFinalLineBooleanPlanLiteral(input, literal);
            case ENDS_WITH_FINAL_LINE -> endsWithFinalLineBooleanPlanLiteral(input, literal);
            case GENERAL, LITERAL_SEARCH, NULLABLE_START, LOWERED_PROGRAM -> throw new IllegalStateException("not a literal find plan: " + booleanPlans.find());
        };
    }

    /// The full-Slice find of the literal-alternation span matcher. A long input whose filter
    /// stops are dense from its start runs the ordinary engine, whose DFA reads each byte once, as
    /// it would without the matcher. A decline reuses the compiled program and may build the DFA
    /// within what remains of the budget, which the matcher's size has already reduced.
    final boolean findOnLiteralAlternation(Slice input)
    {
        requireNonNull(input, "input is null");
        long span = booleanPlans.literalAlternationMatcher().findSpanOrDecline(input);
        if (span == LiteralAlternationSpanMatcher.DECLINED) {
            return findOnForwardProgram(input);
        }
        return span >= 0;
    }

    /// The full-Slice find of the fixed-width byte span matcher.
    final boolean findOnFixedWidth(Slice input)
    {
        requireNonNull(input, "input is null");
        return booleanPlans.fixedWidthByteMatcher().findSpan(input, 0) >= 0;
    }

    /// The full-Slice find of the compact class counter, which stops at the first run reaching
    /// its minimum.
    final boolean findOnClassRun(Slice input)
    {
        requireNonNull(input, "input is null");
        return boundedCharacterClassCounter.contains(input);
    }

    /// The full-Slice find of a one-byte member, or of a run of one-byte members, which exists
    /// exactly where one member does: the byte-run table or the single-byte table over at most
    /// {@value #MAX_DIRECT_BYTE_SCAN_BYTES} bytes, and the ordinary engine's selective byte scans
    /// over longer inputs.
    final boolean findOnByteTable(Slice input)
    {
        requireNonNull(input, "input is null");
        if (input.length() > MAX_DIRECT_BYTE_SCAN_BYTES) {
            return findOnForwardProgram(input);
        }
        SingleByteMatcher byteRunMatcher = booleanPlans.byteRunMatcher();
        return (byteRunMatcher != null ? byteRunMatcher : sharedSingleByteMatcher()).find(input, 0) >= 0;
    }

    /// The full-Slice find of a scan plan. A pattern whose find keeps the literal kernel of its
    /// find plan, when `literalKernelFind` is set, runs that kernel, as [#findOnLiteralKernel]
    /// does. Otherwise a short input of a plan that allows it runs on the ordinary engine, and
    /// any other runs the plan's [ScanPlan#booleanPlan()], resolved at compile time, with the
    /// executor the plan's facts pick. Calls that report positions never come here; the
    /// single-attempt count of [#countOnScanPlan] does.
    final boolean findOnScanPlan(Slice input, boolean ordinaryShortBooleanMatch, boolean literalKernelFind)
    {
        // ScanKernelGenerator.definePatternFind emits the plan route for a generated class; keep
        // in step.
        if (literalKernelFind) {
            return findOnLiteralKernel(input);
        }
        requireNonNull(input, "input is null");
        int length = input.length();
        if (ordinaryShortBooleanMatch && length <= MAX_ORDINARY_SHORT_BOOLEAN_MATCH_BYTES) {
            return findOnForwardProgram(input);
        }
        ScanPlan plan = booleanPlans.scanBooleanPlan();
        if (plan.isAnchoredStart()) {
            return matchStartAnchoredScanPlan(plan, input, 0, length, false, null);
        }
        if (plan.allowsOrdinaryBooleanSearch()) {
            return findWithOrdinaryHandoff(input, plan);
        }
        return finishScanSearch(input, 0, length, searchScanPlan(plan, input, 0, length, 0, false, false, null), Anchor.UNANCHORED, null, NO_MATCH_WORKSPACES);
    }

    /// Searches with the unanchored plan until its failed attempts are dense or exceed its work
    /// budget, then continues on the ordinary engine, which reaches this plan's matches without
    /// visiting each candidate.
    private boolean findWithOrdinaryHandoff(Slice input, ScanPlan plan)
    {
        int result = plan.searchBeforeHandoff(input, 0, input.length(), 0, plan.usesAsciiFoldedExecutor());
        return finishScanSearch(input, 0, input.length(), result, Anchor.UNANCHORED, null, NO_MATCH_WORKSPACES);
    }

    /// Runs the attempt of a start-anchored plan: the partial or end-anchored executor, ASCII-folded
    /// or not, as the plan's facts pick. Each executor is too large for C2 to inline, so this
    /// static selector keeps their calls out of the routes that use it.
    private static boolean matchStartAnchoredScanPlan(ScanPlan plan, Slice text, int contextStart, int contextEnd, boolean fullMatch, int[] groups)
    {
        if (plan.usesAsciiFoldedExecutor()) {
            return plan.isPartialMatch()
                    ? plan.matchAsciiFoldedPartial(text, contextStart, contextEnd, fullMatch, groups)
                    : plan.matchAsciiFoldedEndAnchored(text, contextStart, contextEnd, fullMatch, groups);
        }
        return plan.isPartialMatch()
                ? plan.matchPartial(text, contextStart, contextEnd, fullMatch, groups)
                : plan.matchEndAnchored(text, contextStart, contextEnd, fullMatch, groups);
    }

    /// Selects the ASCII-folded or case-sensitive search for an unanchored plan; `anchored`
    /// restricts it to `start`.
    private static int searchScanPlan(ScanPlan plan, Slice text, int contextStart, int contextEnd, int start, boolean anchored, boolean fullMatch, int[] groups)
    {
        return plan.usesAsciiFoldedExecutor()
                ? plan.searchAsciiFolded(text, contextStart, contextEnd, start, anchored, fullMatch, groups)
                : plan.search(text, contextStart, contextEnd, start, anchored, fullMatch, groups);
    }

    /// Returns whether a scan-plan search that returned `result` matched. A nonnegative result
    /// or [ScanPlan#NO_MATCH] is final. Any other result is the complement of the offset where
    /// the search handed off; the search then finishes on the ordinary engine with the context,
    /// anchor, and context-relative capture offsets the plan received. No match starts between
    /// the plan's search start and that offset. This route skips the plan, so the search cannot
    /// hand off again.
    final boolean finishScanSearch(
            Slice text,
            int contextStart,
            int contextEnd,
            int result,
            Anchor anchor,
            int[] groupOffsets,
            MatchWorkspaces workspaces)
    {
        if (result >= 0) {
            return true;
        }
        if (result == ScanPlan.NO_MATCH) {
            return false;
        }
        return matchOnOrdinaryEngine(partialProg, text, contextStart, contextEnd, ~result, contextEnd, anchor, groupOffsets, workspaces);
    }

    /// Returns whether the pattern has a match in `[start, end)`, retaining the complete Slice as assertion context.
    public boolean find(Slice input, int start, int end)
    {
        requireInput(input);
        return matchInto(input, start, end, Anchor.UNANCHORED, null);
    }

    /// Returns whether the pattern matches at the beginning of `input` without allocating capture storage.
    public boolean lookingAt(Slice input)
    {
        requireInput(input);
        BooleanPlanKind plan = booleanPlans.lookingAt();
        if (plan != BooleanPlanKind.GENERAL) {
            requireNonNull(input, "input is null");
            return matchLiteralPlan(input, plan);
        }
        return matchInto(input, Anchor.ANCHOR_START, null);
    }

    /// Returns whether the pattern matches at `start` within `[start, end)`, retaining the complete Slice as assertion context.
    public boolean lookingAt(Slice input, int start, int end)
    {
        requireInput(input);
        return matchInto(input, start, end, Anchor.ANCHOR_START, null);
    }

    /// Returns whether the pattern matches all of `input` without allocating capture storage.
    public boolean matches(Slice input)
    {
        requireInput(input);
        BooleanPlanKind plan = booleanPlans.matches();
        if (plan != BooleanPlanKind.GENERAL) {
            requireNonNull(input, "input is null");
            return matchLiteralPlan(input, plan);
        }
        return matchInto(input, Anchor.ANCHOR_BOTH, null);
    }

    /// Answers a boolean lookingAt or matches of a literal plan of `kind` over all of `input`,
    /// by the kernel of the kind, or by the lowered boolean program for a lookingAt that needs
    /// it.
    private boolean matchLiteralPlan(Slice input, BooleanPlanKind kind)
    {
        Slice literal = booleanPlans.literal();
        return switch (kind) {
            case CONTAINS -> containsBooleanPlanLiteral(input, literal);
            case EQUALS -> LiteralMatchKernel.equals(input, literal);
            case STARTS_WITH -> LiteralMatchKernel.startsWith(input, literal);
            case ENDS_WITH -> LiteralMatchKernel.endsWith(input, literal);
            case EQUALS_FINAL_LINE -> equalsFinalLineBooleanPlanLiteral(input, literal);
            case ENDS_WITH_FINAL_LINE -> endsWithFinalLineBooleanPlanLiteral(input, literal);
            case LOWERED_PROGRAM -> matchLoweredBooleanProgram(input, Anchor.ANCHOR_START);
            case GENERAL, LITERAL_SEARCH, NULLABLE_START -> throw new IllegalStateException("plan cannot be executed directly: " + kind);
        };
    }

    /// The full-Slice `matches` of a nonempty exact literal.
    final boolean matchesOnExactLiteral(Slice input)
    {
        requireNonNull(input, "input is null");
        return LiteralMatchKernel.equals(input, booleanPlans.literal());
    }

    /// The full-Slice `lookingAt` of a nonempty exact literal.
    final boolean lookingAtOnExactLiteral(Slice input)
    {
        requireNonNull(input, "input is null");
        return LiteralMatchKernel.startsWith(input, booleanPlans.literal());
    }

    /// The full-Slice `matches` of a pattern whose find is a literal kernel: the equality,
    /// prefix, or suffix kernel of the matches plan's kind, or the ordinary engine when the plan
    /// is general. As for [#findOnLiteralKernel], the switch is this route's own.
    final boolean matchesOnLiteralKernel(Slice input)
    {
        requireInput(input);
        BooleanPlanKind kind = booleanPlans.matches();
        if (kind == BooleanPlanKind.GENERAL) {
            return matchInto(input, Anchor.ANCHOR_BOTH, null);
        }
        requireNonNull(input, "input is null");
        Slice literal = booleanPlans.literal();
        return switch (kind) {
            case EQUALS -> LiteralMatchKernel.equals(input, literal);
            case STARTS_WITH -> LiteralMatchKernel.startsWith(input, literal);
            case ENDS_WITH -> LiteralMatchKernel.endsWith(input, literal);
            case GENERAL, LITERAL_SEARCH, NULLABLE_START, CONTAINS, EQUALS_FINAL_LINE, ENDS_WITH_FINAL_LINE, LOWERED_PROGRAM -> throw new IllegalStateException("not a literal matches plan: " + kind);
        };
    }

    /// The full-Slice `lookingAt` of a pattern whose find is a literal kernel: the prefix,
    /// equality, or final-line equality kernel of the lookingAt plan's kind, the lowered boolean
    /// program, or the ordinary engine when the plan is general. As for [#findOnLiteralKernel],
    /// the switch is this route's own.
    final boolean lookingAtOnLiteralKernel(Slice input)
    {
        requireInput(input);
        BooleanPlanKind kind = booleanPlans.lookingAt();
        if (kind == BooleanPlanKind.GENERAL) {
            return matchInto(input, Anchor.ANCHOR_START, null);
        }
        requireNonNull(input, "input is null");
        Slice literal = booleanPlans.literal();
        return switch (kind) {
            case EQUALS -> LiteralMatchKernel.equals(input, literal);
            case STARTS_WITH -> LiteralMatchKernel.startsWith(input, literal);
            case EQUALS_FINAL_LINE -> equalsFinalLineBooleanPlanLiteral(input, literal);
            case LOWERED_PROGRAM -> matchLoweredBooleanProgram(input, Anchor.ANCHOR_START);
            case GENERAL, LITERAL_SEARCH, NULLABLE_START, CONTAINS, ENDS_WITH, ENDS_WITH_FINAL_LINE -> throw new IllegalStateException("not a literal lookingAt plan: " + kind);
        };
    }

    /// The full-Slice `matches` of a scan plan; see [#matchOnScanPlan].
    final boolean matchesOnScanPlan(Slice input)
    {
        return matchOnScanPlan(input, booleanPlans.matches(), Anchor.ANCHOR_BOTH);
    }

    /// The full-Slice `lookingAt` of a scan plan; see [#matchOnScanPlan].
    final boolean lookingAtOnScanPlan(Slice input)
    {
        return matchOnScanPlan(input, booleanPlans.lookingAt(), Anchor.ANCHOR_START);
    }

    /// The full-Slice `matches`, or `lookingAt` when `anchor` is [Anchor#ANCHOR_START], of a
    /// scan plan: the literal kernel of a literal `kind`, or else the plan route of
    /// [#matchInternalOnScanPlan] for an anchored call over all of `input`, which is the attempt
    /// of a start-anchored plan or one anchored search of an unanchored one.
    private boolean matchOnScanPlan(Slice input, BooleanPlanKind kind, Anchor anchor)
    {
        // ScanKernelGenerator.defineAnchoredMatch emits the general plan's route for a generated
        // class; keep in step.
        requireInput(input);
        if (kind != BooleanPlanKind.GENERAL) {
            requireNonNull(input, "input is null");
            return matchLiteralPlan(input, kind);
        }
        // A literal plan names a null input as the engine's does; the plan route, as matchInternal.
        requireNonNull(input, "text is null");
        ScanPlan plan = booleanPlans.scanPlan();
        int length = input.length();
        boolean fullMatch = anchor == Anchor.ANCHOR_BOTH;
        if (plan.isAnchoredStart()) {
            return matchStartAnchoredScanPlan(plan, input, 0, length, fullMatch, null);
        }
        return finishScanSearch(input, 0, length, searchScanPlan(plan, input, 0, length, 0, true, fullMatch, null), anchor, null, NO_MATCH_WORKSPACES);
    }

    private boolean matchLoweredBooleanProgram(Slice input, Anchor anchor)
    {
        return matchOnOrdinaryEngine(
                booleanPlans.loweredProgram(),
                input,
                0,
                input.length(),
                0,
                input.length(),
                anchor,
                null,
                NO_MATCH_WORKSPACES);
    }

    private boolean containsBooleanPlanLiteral(Slice input, Slice literal)
    {
        LiteralSearchKernel literalSearch = booleanPlans.literalSearch();
        return literalSearch == null
                ? LiteralMatchKernel.contains(input, literal)
                : literalSearch.findContains(input, 0, input.length()) >= 0;
    }

    private static boolean equalsFinalLineBooleanPlanLiteral(Slice input, Slice literal)
    {
        return LiteralMatchKernel.equals(input, literal) ||
                (input.length() == literal.length() + 1 &&
                        input.getByte(input.length() - 1) == '\n' &&
                        LiteralMatchKernel.matchesAt(input, 0, literal));
    }

    private static boolean endsWithFinalLineBooleanPlanLiteral(Slice input, Slice literal)
    {
        if (LiteralMatchKernel.endsWith(input, literal)) {
            return true;
        }
        int finalLineEnd = input.length() - 1;
        return finalLineEnd >= literal.length() &&
                input.getByte(finalLineEnd) == '\n' &&
                LiteralMatchKernel.matchesAt(input, finalLineEnd - literal.length(), literal);
    }

    /// Returns whether the pattern matches all of `[start, end)`, retaining the complete Slice as assertion context.
    public boolean matches(Slice input, int start, int end)
    {
        requireInput(input);
        return matchInto(input, start, end, Anchor.ANCHOR_BOTH, null);
    }

    /// Creates a mutable matcher retaining every capturing group.
    public RegexpMatcher matcher(Slice input)
    {
        return new RegexpMatcher(this, input);
    }

    /// Creates a reusable matcher that retains group zero and the requested prefix of explicit
    /// capturing groups. Use zero when only complete-match boundaries are required.
    public RegexpMatcher matcher(Slice input, int retainedCapturingGroupCount)
    {
        requireInput(input);
        return new RegexpMatcher(this, input, null, retainedCapturingGroupCount);
    }

    RegexpMatcher matcher(Slice input, SingleByteMatcher singleByteMatcher)
    {
        return new RegexpMatcher(this, input, singleByteMatcher);
    }

    RegexpMatcher groupZeroMatcher(Slice input, SingleByteMatcher singleByteMatcher)
    {
        return new RegexpMatcher(this, input, singleByteMatcher, 0);
    }

    /// Creates a group-zero matcher without the class counter or the group-zero forward cursor.
    /// The candidate-start cursor, lowered search, and scan plans stay, and a compact pattern
    /// still reaches its counter through [#matchRegionInto].
    RegexpMatcher genericGroupZeroMatcherForDiagnostics(Slice input)
    {
        return new RegexpMatcher(this, input, null, 0, false);
    }

    /// Returns the start-anchored scan plan that an unanchored capture search may run directly
    /// through [#findWithStartAnchoredScanPlan], or `null`. The compile-time plan is the whole
    /// decision: a pattern with a scan plan never matches empty and has no candidate-start or
    /// group-zero cursor, direct lowered search, or lowered program, so no
    /// [RegexpMatcher#find()] check runs ahead of the plan. Only a caller's single-byte matcher
    /// can, and such a caller keeps the ordinary route. A Rust matcher over a partial region runs
    /// its Rust search instead, which reaches the plan only when the region ends at the input end.
    /// A Java matcher region whose $ reads a CR before it runs the ordinary search instead.
    ScanPlan startAnchoredScanPlan()
    {
        ScanPlan plan = booleanPlans.scanPlan();
        return plan != null && plan.isAnchoredStart() ? plan : null;
    }

    /// Returns what [#matchRegionInto] returns, with the same groups, for an unanchored
    /// search of `[contextStart, contextEnd)` that starts at `contextStart`. The
    /// `plan` is this pattern's [#startAnchoredScanPlan()], passed in so callers
    /// that retain it load nothing through this pattern's plans. The call skips the argument checks
    /// and route of [#matchInternalOnScanPlan], which are too large for C2 to inline.
    boolean findWithStartAnchoredScanPlan(ScanPlan plan, Slice text, int contextStart, int contextEnd, int[] groups)
    {
        Arrays.fill(groups, -1);
        return matchStartAnchoredScanPlan(plan, text, contextStart, contextEnd, false, groups);
    }

    /// Returns the case-sensitive scan plan that every capture search of this pattern runs, or
    /// `null`. As for [#startAnchoredScanPlan()], the compile-time plan is the whole decision,
    /// so no [#matchRegionInto] check runs ahead of the plan route of [#matchInternalOnScanPlan]
    /// either.
    /// Only a caller's single-byte matcher can still answer first, and a Java matcher region whose
    /// $ reads a CR before it runs on the NFA instead.
    ScanPlan caseSensitiveScanPlan()
    {
        ScanPlan plan = booleanPlans.scanPlan();
        return plan != null && !plan.usesAsciiFoldedExecutor() ? plan : null;
    }

    /// Returns whether group-zero matchers of this pattern use a single-byte matcher, without
    /// loading it.
    boolean hasSingleByteMatcher()
    {
        return singleByteMatcher != SingleByteMatcher.unsupported();
    }

    /// Returns how [#countMatches] counts this pattern, for a pattern with a
    /// [#caseSensitiveScanPlan()], which only the scan-plan shape's classes have. This base
    /// method, for every other class, returns [ScanPlanCount#OTHER].
    ScanPlanCount scanPlanCount()
    {
        return ScanPlanCount.OTHER;
    }

    /// Returns how [#countOnScanPlan] counts this pattern, following its checks in order, when
    /// `singleAttemptCount` is its argument.
    final ScanPlanCount countRouteOnScanPlan(boolean singleAttemptCount)
    {
        // ScanKernelGenerator defines a generated count for each route this returns other than
        // OTHER; keep in step.
        if (!booleanPlans.scanPlan().isAnchoredStart()) {
            return ScanPlanCount.SEARCH_LOOP;
        }
        if (singleAttemptCount) {
            return ScanPlanCount.SINGLE_ATTEMPT;
        }
        return ScanPlanCount.OTHER;
    }

    /// Continues on the ordinary engine an unanchored search of all of `text` that a
    /// scan-plan search handed off when it returned `result`, as [#finishScanSearch] does.
    final boolean continueScanSearch(Slice text, int result, int[] groups)
    {
        return matchOnOrdinaryEngine(partialProg, text, 0, text.length(), ~result, text.length(), Anchor.UNANCHORED, groups, NO_MATCH_WORKSPACES);
    }

    /// Whether a search of `[start, end)` tries the inner literal first, so a find loop skips its
    /// cursors. A search whose literal sits near its start keeps them, as the ordinary engine does,
    /// and a short window without the literal has no match. This approximates the gate in
    /// matchOnOrdinaryEngine, where a search that tries the literal can still fall back.
    boolean searchesInnerLiteral(Slice text, int start, int end)
    {
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        return innerLiteralSearch != null && !innerLiteralSearch.fallsBackNearStart(text, start, end);
    }

    Dfa.CandidateStartCursor createCandidateStartCursor()
    {
        if (excludesCandidateStartCursor(booleanPlans, usesCompactBoundedCharacterClassForDiagnostics(), longestMatch, requiredPrefix, partialProg)) {
            return null;
        }
        // Every match of a program with a transparent start literal starts at an occurrence of it,
        // so the cursor needs neither a variable length nor a program free of assertions. The DFA
        // must run the assertions; otherwise the group-zero NFA skips to the literal instead.
        if (partialProg.transparentStartLiteral() != null) {
            return partialProg.hasDfaUnsupportedAssertions() ? null : Dfa.createLiteralCandidateStartCursor(partialProg);
        }
        if (MatchLength.fixed(matchLength) >= 0 || partialProg.hasEmptyWidthAssertions()) {
            return null;
        }
        return Dfa.createCandidateStartCursor(partialProg);
    }

    /// Whether the pattern's plans or options rule out every candidate cursor for `program`.
    private static boolean excludesCandidateStartCursor(BooleanPlans booleanPlans, boolean compactBoundedCharacterClass, boolean longestMatch, byte[] requiredPrefix, Prog program)
    {
        return booleanPlans.disjointSuffixRepeatMatcher() != null || compactBoundedCharacterClass ||
                booleanPlans.scanPlan() != null ||
                longestMatch ||
                requiredPrefix != null ||
                program.anchorStart() ||
                program.anchorEnd() ||
                program.canMatchEmpty();
    }

    /// Whether a search can use the program's transparent start literal: the candidate cursor,
    /// when the DFA runs the program's assertions, or else the group-zero NFA's skip. Only an
    /// unanchored search takes that skip, and a required prefix anchors every search. [#build],
    /// its only caller, returns before this for a pattern the compact class counter answers.
    private static boolean searchesTransparentStartLiteral(BooleanPlans booleanPlans, boolean longestMatch, byte[] requiredPrefix, Prog program)
    {
        if (program.hasDfaUnsupportedAssertions()) {
            return requiredPrefix == null;
        }
        return !excludesCandidateStartCursor(booleanPlans, false, longestMatch, requiredPrefix, program);
    }

    Dfa.GroupZeroForwardCursor createGroupZeroForwardCursor()
    {
        if (booleanPlans.disjointSuffixRepeatMatcher() != null || usesCompactBoundedCharacterClassForDiagnostics() ||
                booleanPlans.scanPlan() != null ||
                longestMatch ||
                requiredPrefix != null ||
                partialProg.anchorStart() ||
                partialProg.anchorEnd() ||
                partialProg.hasTextDependentAssertions()) {
            return null;
        }
        return Dfa.createGroupZeroForwardCursor(partialProg);
    }

    boolean canUseGroupZeroForwardCursor(int groupOffsetCount)
    {
        return groupOffsetCount == 2 || booleanPlans.taggedAlternationProgram() != null;
    }

    boolean materializeTaggedAlternation(
            Slice context,
            int contextStart,
            int contextEnd,
            int matchStart,
            int matchEnd,
            int[] groupOffsets)
    {
        TaggedAlternationProgram taggedAlternationProgram = booleanPlans.taggedAlternationProgram();
        return taggedAlternationProgram != null &&
                taggedAlternationProgram.materialize(context, contextStart, contextEnd, matchStart, matchEnd, groupOffsets);
    }

    long findGroupZeroBoundary(
            Dfa.GroupZeroForwardCursor cursor,
            Slice input,
            int contextStart,
            int contextEnd,
            int start)
    {
        long forward = Dfa.searchGroupZeroForward(cursor, input, contextStart, contextEnd, start);
        if (forward < 0) {
            return forward;
        }

        int matchEnd = start + (int) forward;
        int fixedMatchLength = MatchLength.fixed(matchLength);
        int matchStart = -1;
        if (forward == 0) {
            matchStart = start;
        }
        else if (fixedMatchLength >= 0) {
            matchStart = matchEnd - fixedMatchLength;
        }
        else if (partialProg.canMatchEmpty() && !partialProg.hasEmptyWidthAssertions()) {
            // The pattern matches empty at every position, so the leftmost match starts where the
            // search did, and the reverse pass that finds a start would return it.
            matchStart = start;
        }
        else if (singleCharacterMatch) {
            // A one-character match that ends in a valid UTF-8 character starts at its lead byte.
            // Any other ending takes the reverse pass.
            int offset = input.byteArrayOffset();
            int lead = Utf8.previousRuneStart(input.byteArray(), offset + start, offset + matchEnd);
            if (lead >= 0) {
                matchStart = lead - offset;
            }
        }
        if (matchStart < 0) {
            Prog reverseProgram = reverseProg();
            if (reverseProgram == null) {
                return Dfa.SEARCH_FAILED;
            }
            long reverse = Dfa.search(
                    reverseProgram,
                    input,
                    contextStart,
                    contextEnd,
                    start,
                    matchEnd,
                    true,
                    Prog.MatchKind.LONGEST_MATCH,
                    true);
            if (reverse < 0) {
                return reverse;
            }
            matchStart = start + (int) reverse;
        }
        return ((long) (matchStart - contextStart) << 32) |
                ((matchEnd - contextStart) & 0xFFFF_FFFFL);
    }

    boolean hasLoweredCandidateSearch()
    {
        return booleanPlans.loweredProgram() != null;
    }

    boolean hasDirectLoweredGroupZeroSearch()
    {
        // As in route selection, a class-run pattern or a pattern with a plan takes its own route.
        return booleanPlans.scanPlan() == null &&
                !usesCompactBoundedCharacterClassForDiagnostics() &&
                canUseDirectLoweredGroupZeroSearch(partialProg, booleanPlans.loweredProgram(), expressionAnalysis, requiredPrefix);
    }

    long findLoweredCandidateStart(Slice input, int contextStart, int contextEnd, int start)
    {
        Prog loweredProgram = booleanPlans.loweredProgram();
        if (loweredProgram == null || !loweredProgram.anchorEnd()) {
            return Dfa.SEARCH_FAILED;
        }

        Prog reverseProgram = reverseProg();
        if (reverseProgram == null) {
            return Dfa.SEARCH_FAILED;
        }
        return Dfa.searchReverseCandidate(
                reverseProgram,
                input,
                contextStart,
                contextEnd,
                start,
                contextEnd,
                LOWERED_CANDIDATE_SEARCH_BYTES);
    }

    /// Finds the group-zero boundary of the next match on the lowered program. Only a pattern
    /// whose [#hasDirectLoweredGroupZeroSearch()] holds may call it; a matcher checks that once
    /// when it is created rather than on every find.
    long findLoweredGroupZeroBoundary(Slice input, int contextStart, int contextEnd, int start)
    {
        Prog reverseProgram = reverseProg();
        if (reverseProgram == null) {
            return Dfa.CANDIDATE_SEARCH_FALLBACK;
        }
        long reverse = Dfa.search(
                reverseProgram,
                input,
                contextStart,
                contextEnd,
                start,
                contextEnd,
                true,
                Prog.MatchKind.LONGEST_MATCH,
                true);
        if (reverse < 0) {
            return reverse;
        }

        int relativeStart = start - contextStart + (int) reverse;
        int relativeEnd = contextEnd - contextStart;
        if (relativeEnd > 0 && input.getByte(contextEnd - 1) == '\n') {
            relativeEnd--;
        }
        return ((long) relativeStart << 32) | (relativeEnd & 0xFFFF_FFFFL);
    }

    private static boolean canUseDirectLoweredGroupZeroSearch(
            Prog semanticProgram,
            Prog loweredProgram,
            ExpressionAnalysis expressionAnalysis,
            byte[] requiredPrefix)
    {
        if (loweredProgram == null ||
                !loweredProgram.anchorEnd() ||
                requiredPrefix != null ||
                semanticProgram.anchorStart() ||
                semanticProgram.canMatchEmpty() ||
                expressionAnalysis.canConsumeLineFeed()) {
            return false;
        }
        return true;
    }

    boolean canReturnEmptyAtStart(Slice input, int start)
    {
        return canReturnEmptyAtStart(input, 0, input.length(), start);
    }

    boolean canReturnEmptyAtStartForBooleanMatch(Slice input, int start)
    {
        requireNonNull(input, "input is null");
        if (!canMatchEmpty() || partialProg.hasTextDependentAssertions()) {
            return false;
        }
        Prog.MatchKind matchKind = longestMatch ? Prog.MatchKind.LONGEST_MATCH : Prog.MatchKind.FIRST_MATCH;
        return Dfa.canReturnEmptyAtStart(partialProg, input, start, matchKind);
    }

    boolean canReturnEmptyAtStart(Slice input, int contextStart, int contextEnd, int start)
    {
        if (!canMatchEmpty() || capturingGroupCount() != 0 || partialProg.hasTextDependentAssertions()) {
            return false;
        }
        Prog.MatchKind matchKind = longestMatch ? Prog.MatchKind.LONGEST_MATCH : Prog.MatchKind.FIRST_MATCH;
        return Dfa.canReturnEmptyAtStart(partialProg, input, contextStart, contextEnd, start, matchKind);
    }

    /// Returns the number of non-overlapping matches, including empty matches.
    /// Empty matches advance as in [RegexpMatcher#find()], by one code point
    /// in UTF-8 mode or one byte in Latin-1 mode. No capture values are returned.
    public long count(Slice input)
    {
        long optimizedCount = countMatches(input);
        if (optimizedCount >= 0) {
            return optimizedCount;
        }

        RegexpMatcher matcher = matcher(input, 0);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /// Returns the number of matches, or `-1` when only a matcher can count them.
    long countMatches(Slice text)
    {
        return countOnEngine(text);
    }

    /// The count of the ordinary engine, or `-1` when only a matcher can count: the disjoint
    /// suffix-repeat matcher, the single attempt of a start-anchored pattern, the class counter,
    /// or the DFA count.
    private long countOnEngine(Slice text)
    {
        requireNonNull(text, "text is null");
        if (booleanPlans.disjointSuffixRepeatMatcher() != null) {
            return booleanPlans.disjointSuffixRepeatMatcher().count(text);
        }
        if (canMatchEmpty() || partialProg.hasDfaUnsupportedAssertions()) {
            return -1;
        }

        // A required prefix is stripped from the forward program, and a globally
        // anchored program can produce at most one non-empty match.
        if (requiredPrefix != null || partialProg.anchorStart()) {
            return matchInternal(partialProg, text, 0, text.length(), Anchor.UNANCHORED, null, NO_MATCH_WORKSPACES) ? 1 : 0;
        }

        BoundedCharacterClassCounter characterClassCounter = boundedCharacterClassCounter();
        if (characterClassCounter != BoundedCharacterClassCounter.unsupported()) {
            return characterClassCounter.count(text);
        }

        return countDfaMatches(text);
    }

    /// The count of a nonempty exact literal, which advances by the literal's length.
    final long countOnExactLiteral(Slice text)
    {
        requireNonNull(text, "text is null");
        long count = 0;
        byte[] bytes = text.byteArray();
        int start = text.byteArrayOffset();
        int end = start + text.length();
        while (start < end) {
            int matchStart = findExactLiteral(bytes, start, end - start);
            if (matchStart < 0) {
                break;
            }
            count++;
            start = matchStart + exactLiteralLength;
        }
        return count;
    }

    /// The count of the literal-alternation span matcher. A declined count runs the DFA count,
    /// the route the ordinary count takes for this pattern without the matcher: no
    /// disjoint-suffix or class-counter route can be built beside a literal alternation.
    final long countOnLiteralAlternation(Slice text)
    {
        requireNonNull(text, "text is null");
        long count = booleanPlans.literalAlternationMatcher().countOrDecline(text);
        return count != LiteralAlternationSpanMatcher.DECLINED ? count : countDfaMatches(text);
    }

    /// The count of the fixed-width byte span matcher.
    final long countOnFixedWidth(Slice text)
    {
        requireNonNull(text, "text is null");
        return booleanPlans.fixedWidthByteMatcher().count(text);
    }

    /// The count of the compact class counter, on the retained count DFA when it has one.
    final long countOnClassRun(Slice text, boolean retainedClassCountDfa)
    {
        requireNonNull(text, "text is null");
        BoundedCharacterClassCounter counter = boundedCharacterClassCounter;
        return retainedClassCountDfa ? RetainedCharacterClassCountDfa.count(counter, partialProg, text) : counter.count(text);
    }

    /// The count of a one-byte member: the single-byte table over at most
    /// {@value #MAX_DIRECT_BYTE_SCAN_BYTES} bytes. A longer input counts where the ordinary count
    /// would take it, as does a run of one-byte members at any length: on the class counter
    /// where the pattern has one, and otherwise on the DFA. No byte-table pattern reaches the ordinary count's
    /// other routes, as the constructor checks, and this route skips their branches.
    final long countOnByteTable(Slice text)
    {
        requireNonNull(text, "text is null");
        if (booleanPlans.byteRunMatcher() == null && text.length() <= MAX_DIRECT_BYTE_SCAN_BYTES) {
            return sharedSingleByteMatcher().count(text);
        }
        BoundedCharacterClassCounter characterClassCounter = boundedCharacterClassCounter();
        if (characterClassCounter != BoundedCharacterClassCounter.unsupported()) {
            return characterClassCounter.count(text);
        }
        return countDfaMatches(text);
    }

    /// The count of a scan plan: the search loop of an unanchored plan, or for a start-anchored
    /// one, which matches at most once, the capture-free boolean attempt of [#findOnScanPlan],
    /// so a plan with capture saves runs its save-free plan. A start-anchored plan that
    /// `singleAttemptCount` does not cover takes the ordinary count. An unanchored count stays on
    /// the plan so that count agrees with find: a Trino plan gives malformed UTF-8 bytes the
    /// membership find gives them, which the DFA count would reject, and an RE2, Java, or Rust
    /// plan agrees with the ordinary engine.
    final long countOnScanPlan(Slice text, boolean ordinaryShortBooleanMatch, boolean singleAttemptCount)
    {
        // ScanKernelGenerator.definePatternCount emits this route for a generated class; keep in
        // step.
        requireNonNull(text, "text is null");
        ScanPlan plan = booleanPlans.scanPlan();
        if (!plan.isAnchoredStart()) {
            return countUnanchoredScanMatches(text, plan);
        }
        if (singleAttemptCount) {
            return findOnScanPlan(text, ordinaryShortBooleanMatch, false) ? 1 : 0;
        }
        return countOnEngine(text);
    }

    /// Counts the matches of an unanchored pattern on the DFA, starting from the inner literal
    /// where one is required and the text is long enough. It is out of line so that the count
    /// route methods stay small enough for C2 to inline into their callers.
    private long countDfaMatches(Slice text)
    {
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        if (innerLiteralSearch != null && text.length() >= InnerLiteralSearch.MINIMUM_TEXT_BYTES) {
            return countInnerLiteralMatches(innerLiteralSearch, text);
        }

        Prog.MatchKind matchKind = longestMatch ? Prog.MatchKind.LONGEST_MATCH : Prog.MatchKind.FIRST_MATCH;
        return Dfa.countMatches(partialProg, text, matchKind);
    }

    /// Counts the matches found through the inner literal. Where the next literal sits near the
    /// text start or the previous match end, or a search falls back for another reason, the
    /// ordinary count takes over from there and hands back at the first match end whose next
    /// literal is not near. Its start-byte scan selection then holds for the whole dense run,
    /// instead of being made again for each match, and it records its own failures. The ordinary
    /// counts share their start-byte selection and productivity checks, so one that starts within
    /// 64 KiB of an earlier sample does not repeat them. Every match holds the literal, so none is
    /// empty and each step advances.
    private long countInnerLiteralMatches(InnerLiteralSearch innerLiteralSearch, Slice text)
    {
        // As in the ordinary count, a forward DFA that failed a count leaves later counts to the
        // matcher.
        if (Dfa.countSearchBailedWhenSlow(partialProg, Prog.MatchKind.FIRST_MATCH)) {
            return -1;
        }
        int length = text.length();
        IntPredicate continueCounting = next -> innerLiteralSearch.fallsBackNearStart(text, next, length);
        // The first fallback with enough text left to sample allocates the shared sampling, and
        // later ones reuse it. Positions only advance, so a short row never allocates it.
        Dfa.CountByteScanSampling sampling = null;
        long count = 0;
        int position = 0;
        while (position < length) {
            long span = innerLiteralSearch.search(text, 0, length, position, length);
            if (span == Dfa.SEARCH_NO_MATCH) {
                return count;
            }
            if (span >= 0) {
                count++;
                position = (int) span;
                continue;
            }
            if (sampling == null && length - position >= Dfa.CountByteScanSampling.MIN_SAMPLED_BYTES) {
                sampling = new Dfa.CountByteScanSampling();
            }
            long counted = Dfa.countMatches(partialProg, text, position, Prog.MatchKind.FIRST_MATCH, continueCounting, sampling);
            if (counted < 0) {
                return -1;
            }
            count += Dfa.countStopCount(counted);
            position = Dfa.countStopPosition(counted);
        }
        return count;
    }

    /// Counts the group-zero matches [RegexpMatcher#find()] would return. A plan never
    /// matches empty, so each search resumes at the previous match end. As in a find loop, each
    /// search starts on the plan with its own work budget, and one that hands off finds its
    /// match on the ordinary engine.
    private long countUnanchoredScanMatches(Slice text, ScanPlan plan)
    {
        // The count that ScanKernelGenerator emits copies this loop; keep in step.
        boolean asciiFolded = plan.usesAsciiFoldedExecutor();
        int length = text.length();
        long count = 0;
        int start = 0;
        // A search that hands off finishes on the ordinary engine, which reports the end in group
        // storage. The first handoff allocates it, and later ones in this call reuse it.
        int[] handoffGroups = null;
        while (start < length) {
            int matchEnd = asciiFolded
                    ? plan.searchAsciiFolded(text, 0, length, start, false, false, ScanPlan.END_ONLY_GROUPS)
                    : plan.search(text, 0, length, start, false, false, ScanPlan.END_ONLY_GROUPS);
            if (matchEnd < 0) {
                if (matchEnd == ScanPlan.NO_MATCH) {
                    break;
                }
                if (handoffGroups == null) {
                    handoffGroups = new int[2];
                }
                if (!continueScanSearch(text, matchEnd, handoffGroups)) {
                    break;
                }
                matchEnd = handoffGroups[1];
            }
            count++;
            start = matchEnd;
        }
        return count;
    }

    SingleByteMatcher createSingleByteMatcher()
    {
        if (!mayHaveSingleByteMatcher()) {
            return null;
        }
        return SingleByteMatcher.analyze(suffixRegexp, caseFolding());
    }

    SingleByteMatcher sharedSingleByteMatcher()
    {
        SingleByteMatcher matcher = singleByteMatcher;
        if (matcher == SingleByteMatcher.unsupported()) {
            return null;
        }
        return matcher != null ? matcher : loadSingleByteMatcher();
    }

    boolean isSingleByteMatcherComputed()
    {
        SingleByteMatcher matcher = singleByteMatcher;
        return matcher != null && matcher != SingleByteMatcher.unsupported();
    }

    private synchronized SingleByteMatcher loadSingleByteMatcher()
    {
        if (singleByteMatcher == null) {
            singleByteMatcher = requireNonNull(createSingleByteMatcher(), "selected single-byte matcher is unsupported");
        }
        return singleByteMatcher;
    }

    boolean mayHaveSingleByteMatcher()
    {
        return mayHaveSingleByteMatcher(requiredPrefix, partialProg, matchLength);
    }

    private static boolean mayHaveSingleByteMatcher(byte[] requiredPrefix, Prog partialProg, int matchLength)
    {
        return requiredPrefix == null &&
                !partialProg.hasTextDependentAssertions() &&
                MatchLength.fixed(matchLength) == 1;
    }

    boolean supportsSingleByteMatcherForDiagnostics()
    {
        return mayHaveSingleByteMatcher() && SingleByteMatcher.supports(suffixRegexp, caseFolding());
    }

    SingleByteRepeatMatcher createSingleByteRepeatMatcher()
    {
        if (requiredPrefix != null || partialProg.hasTextDependentAssertions()) {
            return null;
        }
        return SingleByteRepeatMatcher.analyze(suffixRegexp, caseFolding());
    }

    BoundedCharacterClassCounter createBoundedCharacterClassCounter()
    {
        if (usesCompactBoundedCharacterClassForDiagnostics()) {
            return boundedCharacterClassCounter;
        }
        if (requiredPrefix != null ||
                partialProg.anchorStart() ||
                partialProg.anchorEnd() ||
                partialProg.hasTextDependentAssertions()) {
            return null;
        }
        return BoundedCharacterClassCounter.analyze(suffixRegexp);
    }

    boolean hasCharacterClassSpan()
    {
        return usesCompactBoundedCharacterClassForDiagnostics() ||
                (requiredPrefix == null &&
                        !partialProg.anchorStart() &&
                        !partialProg.anchorEnd() &&
                        !partialProg.hasTextDependentAssertions() &&
                        BoundedCharacterClassCounter.supports(suffixRegexp));
    }

    /// Returns the counter that finds this pattern's group-zero matches directly: the compact
    /// counter, or the counter of a pattern that is one large Unicode class or a greedy class
    /// repeat. Otherwise `null`.
    BoundedCharacterClassCounter classMatchCounter()
    {
        return usesCompactBoundedCharacterClassForDiagnostics() ? boundedCharacterClassCounter : matcherClassCounter;
    }

    long findCharacterClassSpan(Slice input, int start)
    {
        return boundedCharacterClassCounter().findSpan(input, 0, input.length(), start);
    }

    private BoundedCharacterClassCounter boundedCharacterClassCounter()
    {
        BoundedCharacterClassCounter counter = boundedCharacterClassCounter;
        if (counter == null) {
            counter = loadBoundedCharacterClassCounter();
        }
        return counter;
    }

    private synchronized BoundedCharacterClassCounter loadBoundedCharacterClassCounter()
    {
        BoundedCharacterClassCounter counter = boundedCharacterClassCounter;
        if (counter == null) {
            counter = createBoundedCharacterClassCounter();
            if (counter == null) {
                counter = BoundedCharacterClassCounter.unsupported();
            }
            boundedCharacterClassCounter = counter;
        }
        return counter;
    }

    /// Finds a match and writes byte-offset start/end pairs into caller-owned storage.
    /// The buffer contains group zero followed by a prefix of capturing groups. Unmatched and
    /// unavailable groups are set to `-1, -1`. A `null` buffer requests no captures.
    public boolean findInto(Slice text, int[] groups)
    {
        requireInput(text);
        return matchInto(text, Anchor.UNANCHORED, groups);
    }

    /// Matches at the beginning of the input and writes byte-offset start/end pairs.
    public boolean lookingAtInto(Slice text, int[] groups)
    {
        requireInput(text);
        return matchInto(text, Anchor.ANCHOR_START, groups);
    }

    /// Matches the complete input and writes byte-offset start/end pairs.
    public boolean matchesInto(Slice text, int[] groups)
    {
        requireInput(text);
        return matchInto(text, Anchor.ANCHOR_BOTH, groups);
    }

    /// Finds a match in `[start, end)` while retaining the complete Slice as assertion context.
    /// Reported offsets are relative to the complete logical Slice.
    public boolean findInto(Slice text, int start, int end, int[] groups)
    {
        requireInput(text);
        return matchInto(text, start, end, Anchor.UNANCHORED, groups);
    }

    /// Matches at `start` within `[start, end)` while retaining the complete Slice as assertion context.
    /// Reported offsets are relative to the complete logical Slice.
    public boolean lookingAtInto(Slice text, int start, int end, int[] groups)
    {
        requireInput(text);
        return matchInto(text, start, end, Anchor.ANCHOR_START, groups);
    }

    /// Matches all of `[start, end)` while retaining the complete Slice as assertion context.
    /// Reported offsets are relative to the complete logical Slice.
    public boolean matchesInto(Slice text, int start, int end, int[] groups)
    {
        requireInput(text);
        return matchInto(text, start, end, Anchor.ANCHOR_BOTH, groups);
    }

    // A Java or Rust pattern names a null input `input` in every operation but count, and
    // checks it before any other argument. An RE2 pattern or a Trino engine names it `text`
    // where its own checks reach it first. Of the entries a generated class declares, `matches`
    // and `lookingAt` call this check too.
    final void requireInput(Slice input)
    {
        if (input == null && (this instanceof JavaRegexp || this instanceof RustRegexp)) {
            throw new NullPointerException("input is null");
        }
    }

    boolean matchInto(Slice text, Anchor anchor, int[] groups)
    {
        requireNonNull(text, "text is null");
        return matchInto(text, 0, text.length(), anchor, groups);
    }

    boolean matchInto(Slice text, int start, int end, Anchor anchor, int[] groups)
    {
        return matchInto(text, start, end, anchor, groups, null, null, null);
    }

    boolean matchInto(
            Slice text,
            int start,
            int end,
            Anchor anchor,
            int[] groups,
            BitState.Workspace bitStateWorkspace,
            Nfa.Workspace nfaWorkspace)
    {
        return matchInto(text, start, end, anchor, groups, null, bitStateWorkspace, nfaWorkspace);
    }

    boolean matchInto(
            Slice text,
            int start,
            int end,
            Anchor anchor,
            int[] groups,
            OnePass.Workspace onePassWorkspace,
            BitState.Workspace bitStateWorkspace,
            Nfa.Workspace nfaWorkspace)
    {
        requireNonNull(text, "text is null");
        validateRange(text, start, end);
        return matchRegionInto(
                text,
                0,
                text.length(),
                start,
                end,
                anchor,
                groups,
                fixedMatchWorkspaces(onePassWorkspace, bitStateWorkspace, nfaWorkspace),
                true);
    }

    private static void validateRange(Slice text, int start, int end)
    {
        if (start < 0 || end < start || end > text.length()) {
            throw new IndexOutOfBoundsException("range out of bounds: [" + start + ", " + end + ")");
        }
    }

    boolean matchRegionInto(
            Slice text,
            int contextStart,
            int contextEnd,
            int start,
            int end,
            Anchor anchor,
            int[] groups,
            OnePass.Workspace onePassWorkspace,
            BitState.Workspace bitStateWorkspace,
            Nfa.Workspace nfaWorkspace)
    {
        return matchRegionInto(
                text,
                contextStart,
                contextEnd,
                start,
                end,
                anchor,
                groups,
                fixedMatchWorkspaces(onePassWorkspace, bitStateWorkspace, nfaWorkspace),
                true);
    }

    boolean matchRegionInto(
            Slice text,
            int contextStart,
            int contextEnd,
            int start,
            int end,
            Anchor anchor,
            int[] groups,
            RegexpMatcher workspaceOwner)
    {
        return matchRegionInto(
                text,
                contextStart,
                contextEnd,
                start,
                end,
                anchor,
                groups,
                requireNonNull(workspaceOwner, "workspaceOwner is null"),
                clearsGroupsBeforeMatch);
    }

    private boolean matchRegionInto(
            Slice text,
            int contextStart,
            int contextEnd,
            int start,
            int end,
            Anchor anchor,
            int[] groups,
            MatchWorkspaces workspaces,
            boolean clearGroups)
    {
        requireNonNull(text, "text is null");
        int[] searchGroups = groups;
        if (groups != null) {
            if ((groups.length % 2) != 0) {
                throw new IllegalArgumentException("groups length must be even: " + groups.length);
            }
            if (groups.length < 2) {
                throw new IllegalArgumentException("groups must include group 0 start/end");
            }
            int patternSlotCount = 2 * (capturingGroupCount + 1);
            int copySlotCount = Math.min(patternSlotCount, partialProg.captureSlotCount());
            if (groups.length > patternSlotCount && copySlotCount <= Long.SIZE) {
                // OnePass picks its route by the buffer's length, so a buffer past the pattern's
                // groups would turn it away: on small patterns that cost 584 to 1,120 bytes per
                // call, where this copy of the program's slots costs 24 to 40. OnePass takes at
                // most Long.SIZE slots, and the other engines track at most the program's slots
                // whatever the buffer, so a wider program searches the caller's buffer.
                // The program's slots leave out trailing groups the simplifier removed. No engine
                // writes the pairs past them, and callers read them, so they are set to -1 here
                // whether or not the caller asked for a clear; the copy back writes the rest.
                searchGroups = new int[copySlotCount];
                Arrays.fill(searchGroups, -1);
                Arrays.fill(groups, copySlotCount, groups.length, -1);
            }
            else if (clearGroups) {
                // A matcher reads its groups only after a match, which the ordinary engine writes
                // in full; every other caller sees cleared groups after a failure.
                Arrays.fill(groups, -1);
            }
        }
        boolean matched = matchInternal(partialProg, text, contextStart, contextEnd, start, end, anchor, searchGroups, workspaces);
        if (searchGroups != groups) {
            System.arraycopy(searchGroups, 0, groups, 0, searchGroups.length);
        }
        return matched;
    }

    /// Returns whether a matcher region must read a CR before its start: the JDK's $ and \Z refuse
    /// the LF of a CR LF even at the start of a region that excludes the CR. A required prefix puts
    /// every Java $ or \Z assertion after the region start, so only a pattern without one reads it.
    /// Excluding a required prefix also guarantees that `partialProg` is the whole pattern, as
    /// [#matchRegionAfterCarriageReturnInto] needs. The test is deliberately conservative: a
    /// pattern such as `a$`, which never tests $ at the region start, still takes that route.
    boolean readsCarriageReturnBeforeRegion()
    {
        return requiredPrefix == null && (partialProg.textDependentAssertions() & EmptyOp.JAVA_END_ASSERTIONS) != 0;
    }

    /// Matches a matcher region that starts on the LF of a CR LF, for a pattern that
    /// [reads that CR][#readsCarriageReturnBeforeRegion], with the anchors and region-relative
    /// groups of [#matchRegionInto]. The region alone cannot show the CR, so the semantic
    /// program runs on the NFA told that a CR precedes the region. The case is rare, so it skips
    /// every faster engine.
    boolean matchRegionAfterCarriageReturnInto(Slice text, int contextStart, int contextEnd, int start, Anchor anchor, int[] groups, RegexpMatcher workspaceOwner)
    {
        Slice context = logicalContext(text, contextStart, contextEnd);
        int relativeStart = start - contextStart;
        boolean anchored = anchor != Anchor.UNANCHORED;
        // Java searches are leftmost-first.
        Prog.MatchKind matchKind = anchor == Anchor.ANCHOR_BOTH ? Prog.MatchKind.FULL_MATCH : Prog.MatchKind.FIRST_MATCH;
        Nfa.Workspace workspace = workspaceOwner.nfaWorkspace();
        boolean matched = groups.length == 2
                ? Nfa.searchGroupZero(partialProg, context, relativeStart, context.length(), anchored, matchKind, groups, workspace, true)
                : Nfa.search(partialProg, context, relativeStart, context.length(), anchored, matchKind, groups, workspace, true);
        if (matched) {
            shiftGroupOffsets(groups, relativeStart);
        }
        return matched;
    }

    private boolean matchCompactBoundedCharacterClass(
            Slice text,
            int contextStart,
            int end,
            int start,
            Anchor anchor,
            int[] groups)
    {
        // An anchored match fails at once unless a run begins at `start`, rather than searching
        // past it.
        if (anchor != Anchor.UNANCHORED && !boundedCharacterClassCounter.beginsRun(text, contextStart, end, start)) {
            return false;
        }
        long span = boundedCharacterClassCounter.findSpan(text, contextStart, end, start);
        if (span < 0) {
            return false;
        }

        int matchStart = (int) (span >>> 32);
        int matchEnd = (int) span;
        int relativeStart = start - contextStart;
        if (anchor != Anchor.UNANCHORED && matchStart != relativeStart) {
            return false;
        }
        if (anchor == Anchor.ANCHOR_BOTH && matchEnd != end - contextStart) {
            return false;
        }
        if (groups != null) {
            groups[0] = matchStart;
            groups[1] = matchEnd;
        }
        return true;
    }

    /// Returns an immutable snapshot of the first match, or `null` when no match exists.
    public MatchResult findResult(Slice input)
    {
        requireInput(input);
        return matchResult(input, Anchor.UNANCHORED);
    }

    /// Returns an immutable snapshot of the first match in `[start, end)`, or `null`.
    public MatchResult findResult(Slice input, int start, int end)
    {
        requireInput(input);
        return matchResult(input, start, end, Anchor.UNANCHORED);
    }

    /// Returns an immutable snapshot when the pattern matches the complete input, or `null`.
    public MatchResult matchesResult(Slice input)
    {
        requireInput(input);
        return matchResult(input, Anchor.ANCHOR_BOTH);
    }

    /// Returns an immutable snapshot when the pattern matches all of `[start, end)`, or `null`.
    public MatchResult matchesResult(Slice input, int start, int end)
    {
        requireInput(input);
        return matchResult(input, start, end, Anchor.ANCHOR_BOTH);
    }

    /// Returns an immutable snapshot of a match at the beginning of the input, or `null`.
    public MatchResult lookingAtResult(Slice input)
    {
        requireInput(input);
        return matchResult(input, Anchor.ANCHOR_START);
    }

    /// Returns an immutable snapshot of a match at `start` within `[start, end)`, or `null`.
    public MatchResult lookingAtResult(Slice input, int start, int end)
    {
        requireInput(input);
        return matchResult(input, start, end, Anchor.ANCHOR_START);
    }

    MatchResult matchResult(Slice text, Anchor anchor)
    {
        requireNonNull(text, "text is null");
        return matchResult(text, 0, text.length(), anchor);
    }

    MatchResult matchResult(Slice text, int start, int end, Anchor anchor)
    {
        requireNonNull(text, "text is null");
        int[] groups = new int[2 * (capturingGroupCount() + 1)];
        if (!matchInto(text, start, end, anchor, groups)) {
            return null;
        }
        return new MatchResult(text, groups, namedCapturingGroups());
    }

    private boolean matchInternal(
            Prog prog,
            Slice text,
            int start,
            int end,
            Anchor anchor,
            int[] groupOffsets,
            MatchWorkspaces workspaces)
    {
        return matchInternal(
                prog,
                text,
                0,
                text.length(),
                start,
                end,
                anchor,
                groupOffsets,
                workspaces);
    }

    /// The engine hook through which every call of a program over a region runs: the ordinary
    /// engine. The class-run and scan-plan shapes override it.
    boolean matchInternal(
            Prog prog,
            Slice text,
            int contextStart,
            int contextEnd,
            int start,
            int end,
            Anchor anchor,
            int[] groupOffsets,
            MatchWorkspaces workspaces)
    {
        return matchOnOrdinaryEngine(prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces);
    }

    /// The [#matchInternal] route of a pattern that the compact class counter answers: every
    /// call of the forward program runs the counter, which reports group zero only, as a
    /// pattern without captures needs.
    final boolean matchInternalOnClassRun(Prog prog, Slice text, int contextStart, int contextEnd, int start, int end, Anchor anchor, int[] groupOffsets, MatchWorkspaces workspaces)
    {
        if (prog == partialProg) {
            return matchCompactBoundedCharacterClass(text, contextStart, end, start, anchor, groupOffsets);
        }
        return matchOnOrdinaryEngine(prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces);
    }

    /// The [#matchInternal] route of a pattern with a scan plan. A call of the forward program
    /// over a region that ends at the context end checks its arguments and runs the plan. A
    /// start-anchored plan makes its attempt, except for an unanchored boolean call over at most
    /// {@value #MAX_ORDINARY_SHORT_BOOLEAN_MATCH_BYTES} bytes when `ordinaryShortBooleanMatch` is
    /// set. An unanchored plan runs its search, except for an unanchored boolean search that the
    /// plan allows on the ordinary engine. Every other call runs the ordinary engine, which checks
    /// the arguments itself. The `matchInternal` that ScanKernelGenerator emits for a generated
    /// class makes the same choices without the short-input route; keep in step.
    final boolean matchInternalOnScanPlan(
            Prog prog,
            Slice text,
            int contextStart,
            int contextEnd,
            int start,
            int end,
            Anchor anchor,
            int[] groupOffsets,
            MatchWorkspaces workspaces,
            boolean ordinaryShortBooleanMatch)
    {
        if (reachesScanPlan(prog, contextEnd, end)) {
            if (!acceptsScanPlanArguments(text, contextStart, contextEnd, start, end, groupOffsets)) {
                return false;
            }
            Anchor anchorMode = (anchor == null) ? Anchor.UNANCHORED : anchor;
            ScanPlan plan = booleanPlans.scanPlan();
            if (plan.isAnchoredStart()) {
                boolean ordinaryShortMatch = ordinaryShortBooleanMatch && groupOffsets == null && anchorMode == Anchor.UNANCHORED &&
                        contextEnd - start <= MAX_ORDINARY_SHORT_BOOLEAN_MATCH_BYTES;
                if (!ordinaryShortMatch) {
                    return start == contextStart && matchStartAnchoredScanPlan(plan, text, contextStart, contextEnd, anchorMode == Anchor.ANCHOR_BOTH, groupOffsets);
                }
            }
            else if (groupOffsets != null || anchorMode != Anchor.UNANCHORED || !plan.allowsOrdinaryBooleanSearch()) {
                int result = searchScanPlan(plan, text, contextStart, contextEnd, start, anchorMode != Anchor.UNANCHORED, anchorMode == Anchor.ANCHOR_BOTH, groupOffsets);
                return finishScanSearch(text, contextStart, contextEnd, result, anchorMode, groupOffsets, workspaces);
            }
        }
        return matchOnOrdinaryEngine(prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces);
    }

    // PERFORMANCE-SENSITIVE ENGINE DISPATCH: phase boundaries, temporary capture storage, and
    // engine-selection branches affect the generated hot path. Do not apply readability-only
    // changes without direct path tests and focused Intel and Graviton benchmarks.

    /// The ordinary engine: the disjoint suffix-repeat, whole-input capture, and word-run
    /// matchers, then the DFA, OnePass, BitState, and NFA cascade. Every route that leaves a plan
    /// or kernel for the ordinary engine calls this, as does the `matchInternal` that
    /// ScanKernelGenerator emits for a call off the plan.
    final boolean matchOnOrdinaryEngine(Prog prog, Slice text, int contextStart, int contextEnd, int start, int end, Anchor anchor, int[] groupOffsets, MatchWorkspaces workspaces)
    {
        // acceptsScanPlanArguments makes the same checks; keep in step.
        requireNonNull(text, "text is null");
        if (contextStart < 0 || contextEnd < contextStart || contextEnd > text.length() ||
                start < contextStart || end < start || end > contextEnd) {
            return false;
        }
        if (groupOffsets != null && (groupOffsets.length % 2) != 0) {
            throw new IllegalArgumentException("groupOffsets length must be even: " + groupOffsets.length);
        }

        Anchor anchorMode = (anchor == null) ? Anchor.UNANCHORED : anchor;

        if (hasPreEngineMatcher && prog == partialProg) {
            int preEngineResult = matchOnPreEngineMatcher(text, contextStart, contextEnd, start, end, anchorMode, groupOffsets);
            if (preEngineResult != PRE_ENGINE_CONTINUE) {
                return preEngineResult == PRE_ENGINE_MATCH;
            }
        }

        int strippedPrefixLength = 0;
        if (requiredPrefix != null) {
            if (start != contextStart || !matchesRequiredPrefix(text, start, end - start)) {
                return false;
            }
            strippedPrefixLength = requiredPrefix.length;
            start += strippedPrefixLength;
            if (anchorMode != Anchor.ANCHOR_BOTH) {
                anchorMode = Anchor.ANCHOR_START;
            }
        }

        // If regexp is anchored explicitly, it cannot match a middle slice.
        if (prog.anchorStart() && start != contextStart) {
            return false;
        }
        if (prog.anchorEnd() && end != contextEnd) {
            return false;
        }

        // If regexp is anchored explicitly, update anchor mode
        // so that we can potentially fall into a faster case below.
        if (prog.anchorStart() && prog.anchorEnd()) {
            anchorMode = Anchor.ANCHOR_BOTH;
        }
        else if (prog.anchorStart() && anchorMode != Anchor.ANCHOR_BOTH) {
            anchorMode = Anchor.ANCHOR_START;
        }

        boolean anchored = anchorMode != Anchor.UNANCHORED;
        Prog.MatchKind matchKind = anchorMode == Anchor.ANCHOR_BOTH
                ? Prog.MatchKind.FULL_MATCH
                : (longestMatch ? Prog.MatchKind.LONGEST_MATCH : Prog.MatchKind.FIRST_MATCH);

        int searchLength = end - start;
        // Keep the metadata load off long searches whose existing rejection path is already constant time.
        if (searchLength < MINIMUM_LENGTH_CHECK_LIMIT && searchLength < MatchLength.minimum(matchLength)) {
            return false;
        }
        // With one table load before any engine setup, a start-anchored program rejects a first
        // byte that cannot begin a match; the submatch engines would otherwise pay their setup to
        // find the same failure.
        if (anchored && searchLength > 0) {
            byte[] anchoredStartBytes = prog.anchoredStartBytes();
            if (anchoredStartBytes != null && anchoredStartBytes[text.byteArray()[text.byteArrayOffset() + start] & 0xFF] == 0) {
                return false;
            }
        }

        int[] mutableGroupOffsets = groupOffsets;
        boolean writingCallerGroups = groupOffsets != null;
        int captureCount = (mutableGroupOffsets == null) ? 0 : (mutableGroupOffsets.length / 2);

        // A bounded BitState search can extract all groups in one pass. For eligible long inputs,
        // this avoids forward and reverse DFA boundary searches followed by an NFA capture pass.
        if (anchorMode == Anchor.UNANCHORED &&
                mutableGroupOffsets != null &&
                hasBitStateWorkspace(workspaces) &&
                canUseDirectBitStateCapture(prog, searchLength, captureCount)) {
            Slice logicalContext = logicalContext(text, contextStart, contextEnd);
            boolean matched = BitState.search(
                    prog,
                    logicalContext,
                    start - contextStart,
                    end - contextStart,
                    false,
                    matchKind,
                    mutableGroupOffsets,
                    bitStateWorkspace(workspaces));
            if (matched && start > contextStart) {
                shiftGroupOffsets(mutableGroupOffsets, start - contextStart);
            }
            return matched;
        }

        // ===== Phase 1-2: DFA Forward + Reverse Search (Unanchored) =====
        // Use two-phase DFA to find match boundaries without extracting submatches. An unanchored
        // search with an inner literal search runs it first and falls back to the two-phase DFA.
        boolean dfaSearchSkippedOrFailed = prog.hasDfaUnsupportedAssertions() ||
                (captureCount == 1 && Dfa.countSearchBailedWhenSlow(prog, matchKind));
        int matchStart = -1;
        int matchEnd = -1;
        // Patterns such as \C* and Latin1 (?s).* accept every byte sequence.
        if (anchorMode == Anchor.ANCHOR_BOTH && prog.matchesAnyByteString() && captureCount <= 1) {
            if (mutableGroupOffsets != null && mutableGroupOffsets.length >= 2) {
                mutableGroupOffsets[0] = start - contextStart - strippedPrefixLength;
                mutableGroupOffsets[1] = end - contextStart;
            }
            return true;
        }

        if (anchorMode == Anchor.UNANCHORED) {
            // A matcher counts the searches whose DFA failed in a program whose DFA can quit. The
            // count is cleared before the DFA runs, so a search that completes ends the streak,
            // and a failure below sets it from the count read here. A search whose candidate
            // cursor quit has already counted, so it skips the DFA.
            boolean dfaCanQuit = prog.dfaQuitsOnNonAscii();
            int previousDfaQuits = 0;
            if (dfaCanQuit && !dfaSearchSkippedOrFailed) {
                previousDfaQuits = dfaQuitStreak(workspaces);
                if (previousDfaQuits >= DFA_QUIT_STREAK_LIMIT || candidateStartCursorQuit(workspaces)) {
                    dfaSearchSkippedOrFailed = true;
                }
                else {
                    setDfaQuitStreak(workspaces, 0);
                }
            }
            InnerLiteralSearch innerLiteralSearch = prog == partialProg ? booleanPlans.innerLiteralSearch() : null;
            long innerLiteralSpan = innerLiteralSearch != null && !dfaSearchSkippedOrFailed
                    ? innerLiteralSearch.search(text, contextStart, contextEnd, start, end)
                    : InnerLiteralSearch.FALLBACK;
            if (innerLiteralSpan == Dfa.SEARCH_NO_MATCH) {
                return false;
            }
            if (innerLiteralSpan >= 0) {
                if (captureCount == 0) {
                    return true;
                }
                matchStart = (int) (innerLiteralSpan >>> 32) - start;
                matchEnd = (int) innerLiteralSpan - start;
                if (captureCount == 1) {
                    mutableGroupOffsets[0] = matchStart + start - contextStart;
                    mutableGroupOffsets[1] = matchEnd + start - contextStart;
                    return true;
                }
            }
            else if (!dfaSearchSkippedOrFailed) {
                if (prog.anchorEnd()) {
                    // The match must end with the search window, so only the reverse DFA is needed.
                    Prog reverseProgram = reverseProg();
                    if (reverseProgram != null) {
                        long reverse = Dfa.search(
                                reverseProgram,
                                text,
                                contextStart,
                                contextEnd,
                                start,
                                end,
                                true,
                                Prog.MatchKind.LONGEST_MATCH,
                                true);

                        if (reverse == Dfa.SEARCH_FAILED) {
                            dfaSearchSkippedOrFailed = true;
                            if (dfaCanQuit) {
                                setDfaQuitStreak(workspaces, previousDfaQuits + 1);
                            }
                        }
                        else if (reverse == Dfa.SEARCH_NO_MATCH) {
                            return false;
                        }
                        else {
                            if (captureCount == 0) {
                                return true;
                            }
                            matchStart = (int) reverse;
                            matchEnd = searchLength;
                            if (captureCount == 1) {
                                mutableGroupOffsets[0] = matchStart + start - contextStart;
                                mutableGroupOffsets[1] = matchEnd + start - contextStart;
                                return true;
                            }
                        }
                    }
                    else {
                        dfaSearchSkippedOrFailed = true;
                    }
                }
                else {
                    long forward = Dfa.search(prog, text, contextStart, contextEnd, start, end, false, matchKind, captureCount > 0);

                    if (forward == Dfa.SEARCH_FAILED) {
                        dfaSearchSkippedOrFailed = true;
                        if (dfaCanQuit) {
                            setDfaQuitStreak(workspaces, previousDfaQuits + 1);
                        }
                    }
                    else if (forward == Dfa.SEARCH_NO_MATCH) {
                        return false;
                    }
                    else {
                        matchEnd = (int) forward;
                        if (captureCount == 0) {
                            return true;
                        }
                        if (captureCount == 1 && matchEnd == 0) {
                            mutableGroupOffsets[0] = start - contextStart;
                            mutableGroupOffsets[1] = start - contextStart;
                            return true;
                        }

                        int fixedMatchLength = MatchLength.fixed(matchLength);
                        if (fixedMatchLength >= 0) {
                            matchStart = matchEnd - fixedMatchLength;
                            if (captureCount == 1) {
                                mutableGroupOffsets[0] = matchStart + start - contextStart;
                                mutableGroupOffsets[1] = matchEnd + start - contextStart;
                                return true;
                            }
                        }

                        if (matchStart < 0) {
                            Prog reverseProgram = reverseProg();
                            if (reverseProgram != null) {
                                long reverse = Dfa.search(
                                        reverseProgram,
                                        text,
                                        contextStart,
                                        contextEnd,
                                        start,
                                        start + matchEnd,
                                        true,
                                        Prog.MatchKind.LONGEST_MATCH,
                                        true);

                                if (reverse == Dfa.SEARCH_FAILED) {
                                    dfaSearchSkippedOrFailed = true;
                                    if (dfaCanQuit) {
                                        setDfaQuitStreak(workspaces, previousDfaQuits + 1);
                                    }
                                }
                                else if (reverse == Dfa.SEARCH_NO_MATCH) {
                                    return false;
                                }
                                else {
                                    matchStart = (int) reverse;
                                    if (captureCount == 1) {
                                        mutableGroupOffsets[0] = matchStart + start - contextStart;
                                        mutableGroupOffsets[1] = matchEnd + start - contextStart;
                                        return true;
                                    }
                                }
                            }
                            else {
                                dfaSearchSkippedOrFailed = true;
                            }
                        }
                    }
                }
            }
        }

        // ===== Phase 3: Anchored Search Optimization =====
        // For anchored patterns, decide whether to use DFA or skip directly to submatch engines.
        else if (anchorMode == Anchor.ANCHOR_START || anchorMode == Anchor.ANCHOR_BOTH) {
            boolean canOnePass = prog.isOnePass() && prog.supportsOnePassCaptureSlots(captureCount * 2);
            boolean canBitState = prog.canBitState();
            int bitStateMaxSize = prog.bitStateTextMaxSize();

            // Skip DFA if we have fast engines and need submatches
            boolean skipDfa = dfaSearchSkippedOrFailed;
            if (canOnePass && searchLength <= 4096 && (captureCount > 1 || searchLength <= 16)) {
                skipDfa = true;
            }
            else if (canBitState && searchLength <= bitStateMaxSize && captureCount > 1) {
                skipDfa = true;
            }

            if (!skipDfa) {
                // Try DFA for anchored search
                long result = Dfa.search(prog, text, contextStart, contextEnd, start, end, true, matchKind, writingCallerGroups);

                if (result == Dfa.SEARCH_FAILED) {
                    dfaSearchSkippedOrFailed = true;
                }
                else if (result == Dfa.SEARCH_NO_MATCH) {
                    return false;
                }
                else {
                    matchStart = 0;
                    matchEnd = (int) result;

                    // If no submatch capture requested, return immediately.
                    // Use writingCallerGroups (not mutableGroupOffsets.length) because we may have created
                    // a temporary group array for ANCHOR_BOTH validation, but user doesn't need it.
                    if (!writingCallerGroups) {
                        return true;
                    }
                    // Have match range, need submatches → continue to engines
                }
            }
            else {
                dfaSearchSkippedOrFailed = true;
            }
        }

        // ===== Phase 4: Submatch Engine Cascade =====
        // Extract capturing group positions using OnePass → BitState → NFA cascade.

        boolean hasExactDfaRange = !dfaSearchSkippedOrFailed && matchStart >= 0 && matchEnd >= 0;
        int submatchStart = hasExactDfaRange ? start + matchStart : start;
        int submatchEnd = hasExactDfaRange ? start + matchEnd : end;
        boolean submatchAnchored = hasExactDfaRange || anchored;
        Prog.MatchKind submatchKind = hasExactDfaRange ? Prog.MatchKind.FULL_MATCH : matchKind;

        Slice submatchContext = logicalContext(text, contextStart, contextEnd);
        boolean matched;
        if (captureCount > 1 && prog.hasTextDependentAssertions() && booleanPlans.taggedAlternationProgram() != null) {
            matched = booleanPlans.taggedAlternationProgram().search(
                    submatchContext,
                    submatchStart - contextStart,
                    submatchEnd - contextStart,
                    submatchAnchored,
                    submatchKind,
                    mutableGroupOffsets,
                    nfaWorkspace(workspaces));
        }
        else {
            matched = runSubmatchEngine(
                    prog,
                    submatchContext,
                    submatchStart - contextStart,
                    submatchEnd - contextStart,
                    submatchAnchored,
                    submatchKind,
                    mutableGroupOffsets,
                    workspaces);
        }

        if (!matched) {
            return false;
        }

        // ===== Phase 5: Result Adjustment =====
        // Adjust positions for narrowed search range and start offset.

        // First, adjust for narrowed search range (if DFA was used)
        if (!dfaSearchSkippedOrFailed && matchStart > 0 && mutableGroupOffsets != null) {
            shiftGroupOffsets(mutableGroupOffsets, matchStart);
        }

        // Finally, adjust for start offset
        if (writingCallerGroups) {
            shiftGroupOffsets(mutableGroupOffsets, start - contextStart);
            if (strippedPrefixLength > 0 && mutableGroupOffsets[0] >= 0) {
                mutableGroupOffsets[0] -= strippedPrefixLength;
            }
        }

        return true;
    }

    /// The disjoint suffix-repeat, whole-input capture, and word-run matchers of the forward
    /// program, out of line so that the ordinary engine tests one field for all three.
    private int matchOnPreEngineMatcher(Slice text, int contextStart, int contextEnd, int start, int end, Anchor anchorMode, int[] groupOffsets)
    {
        if (booleanPlans.disjointSuffixRepeatMatcher() != null) {
            long span = booleanPlans.disjointSuffixRepeatMatcher().search(text, start, end, anchorMode);
            if (span == Dfa.SEARCH_NO_MATCH) {
                return PRE_ENGINE_NO_MATCH;
            }
            if (groupOffsets != null && groupOffsets.length >= 2) {
                Arrays.fill(groupOffsets, -1);
                groupOffsets[0] = (int) (span >>> 32) - contextStart;
                groupOffsets[1] = (int) span - contextStart;
            }
            return PRE_ENGINE_MATCH;
        }

        if (booleanPlans.wholeInputCapturePlan() != null &&
                start == contextStart && end == contextEnd &&
                booleanPlans.wholeInputCapturePlan().matches(logicalContext(text, contextStart, contextEnd))) {
            booleanPlans.wholeInputCapturePlan().materialize(end - start, groupOffsets);
            return PRE_ENGINE_MATCH;
        }

        if (booleanPlans.wordRunMatcher() != null) {
            long span = booleanPlans.wordRunMatcher().search(text, contextStart, contextEnd, start, end, anchorMode);
            if (span == Dfa.SEARCH_NO_MATCH) {
                return PRE_ENGINE_NO_MATCH;
            }
            if (groupOffsets != null && groupOffsets.length >= 2) {
                Arrays.fill(groupOffsets, -1);
                groupOffsets[0] = (int) (span >>> 32);
                groupOffsets[1] = (int) span;
            }
            return PRE_ENGINE_MATCH;
        }
        return PRE_ENGINE_CONTINUE;
    }

    private static Slice logicalContext(Slice text, int contextStart, int contextEnd)
    {
        if (contextStart == 0 && contextEnd == text.length()) {
            return text;
        }
        return text.slice(contextStart, contextEnd - contextStart);
    }

    private static BooleanPlans selectBooleanPlans(
            ExpressionAnalysis analysis,
            int exactLiteralLength,
            byte[] requiredPrefix,
            Prog partialProg)
    {
        ExpressionAnalysis.LiteralSequence sequence = analysis.literalSequence();
        Slice literal = null;
        BooleanPlanKind find = BooleanPlanKind.GENERAL;
        BooleanPlanKind lookingAt = BooleanPlanKind.GENERAL;
        BooleanPlanKind matches = BooleanPlanKind.GENERAL;
        if (sequence != null && sequence.literalCount() == 1) {
            literal = sequence.retainedLiteral(0);
            if (literal.length() != 0) {
                ExpressionAnalysis.Gap leadingGap = sequence.leadingGap();
                ExpressionAnalysis.Gap trailingGap = sequence.gapAfter(0);
                find = selectFindPlan(sequence, leadingGap, trailingGap);
                lookingAt = selectLookingAtPlan(sequence, leadingGap, trailingGap, analysis.latin1());
                matches = selectCompletePlan(leadingGap, trailingGap, analysis.latin1());
            }
        }

        // A pattern that is exactly a literal searches for it, and one that can match empty at its
        // start answers find there first.
        if (find == BooleanPlanKind.GENERAL) {
            if (exactLiteralLength >= 0) {
                find = BooleanPlanKind.LITERAL_SEARCH;
            }
            else if (requiredPrefix == null && partialProg.canMatchEmpty()) {
                find = BooleanPlanKind.NULLABLE_START;
            }
        }
        return new BooleanPlans(literal, find, lookingAt, matches, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static BooleanPlanKind selectFindPlan(
            ExpressionAnalysis.LiteralSequence sequence,
            ExpressionAnalysis.Gap leadingGap,
            ExpressionAnalysis.Gap trailingGap)
    {
        if (leadingGap == ExpressionAnalysis.Gap.NONE && trailingGap == ExpressionAnalysis.Gap.NONE) {
            if (sequence.anchoredAtStart() && sequence.anchoredAtEnd()) {
                return sequence.finalLineEnd() ? BooleanPlanKind.EQUALS_FINAL_LINE : BooleanPlanKind.EQUALS;
            }
            if (sequence.anchoredAtStart()) {
                return BooleanPlanKind.STARTS_WITH;
            }
            if (sequence.anchoredAtEnd()) {
                return sequence.finalLineEnd() ? BooleanPlanKind.ENDS_WITH_FINAL_LINE : BooleanPlanKind.ENDS_WITH;
            }
            return BooleanPlanKind.LITERAL_SEARCH;
        }
        if (!sequence.anchoredAtStart() &&
                !sequence.anchoredAtEnd() &&
                isOptionalGap(leadingGap) &&
                isOptionalGap(trailingGap)) {
            return BooleanPlanKind.CONTAINS;
        }
        return BooleanPlanKind.GENERAL;
    }

    private static BooleanPlanKind selectLookingAtPlan(
            ExpressionAnalysis.LiteralSequence sequence,
            ExpressionAnalysis.Gap leadingGap,
            ExpressionAnalysis.Gap trailingGap,
            boolean latin1)
    {
        if (leadingGap != ExpressionAnalysis.Gap.NONE) {
            return BooleanPlanKind.GENERAL;
        }
        if (!sequence.anchoredAtEnd()) {
            return isOptionalGap(trailingGap) ? BooleanPlanKind.STARTS_WITH : BooleanPlanKind.GENERAL;
        }
        if (sequence.finalLineEnd()) {
            return leadingGap == ExpressionAnalysis.Gap.NONE && trailingGap == ExpressionAnalysis.Gap.NONE
                    ? BooleanPlanKind.EQUALS_FINAL_LINE
                    : BooleanPlanKind.GENERAL;
        }
        return selectCompletePlan(leadingGap, trailingGap, latin1);
    }

    private static BooleanPlanKind selectCompletePlan(
            ExpressionAnalysis.Gap leadingGap,
            ExpressionAnalysis.Gap trailingGap,
            boolean latin1)
    {
        if (leadingGap == ExpressionAnalysis.Gap.NONE && trailingGap == ExpressionAnalysis.Gap.NONE) {
            return BooleanPlanKind.EQUALS;
        }
        if (leadingGap == ExpressionAnalysis.Gap.NONE && canMatchUnrestrictedGapDirectly(trailingGap, latin1)) {
            return BooleanPlanKind.STARTS_WITH;
        }
        if (canMatchUnrestrictedGapDirectly(leadingGap, latin1) && trailingGap == ExpressionAnalysis.Gap.NONE) {
            return BooleanPlanKind.ENDS_WITH;
        }
        return BooleanPlanKind.GENERAL;
    }

    private static boolean isOptionalGap(ExpressionAnalysis.Gap gap)
    {
        return gap == ExpressionAnalysis.Gap.NONE ||
                gap == ExpressionAnalysis.Gap.ZERO_OR_MORE_BYTES ||
                gap == ExpressionAnalysis.Gap.ZERO_OR_MORE_CODE_POINTS ||
                gap == ExpressionAnalysis.Gap.ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS;
    }

    private static boolean canMatchUnrestrictedGapDirectly(ExpressionAnalysis.Gap gap, boolean latin1)
    {
        return gap == ExpressionAnalysis.Gap.ZERO_OR_MORE_BYTES ||
                (latin1 && gap == ExpressionAnalysis.Gap.ZERO_OR_MORE_CODE_POINTS);
    }

    private record BooleanPlans(
            Slice literal,
            BooleanPlanKind find,
            BooleanPlanKind lookingAt,
            BooleanPlanKind matches,
            Prog loweredProgram,
            TaggedAlternationProgram taggedAlternationProgram,
            WordRunMatcher wordRunMatcher,
            WholeInputCapturePlan wholeInputCapturePlan,
            DisjointSuffixRepeatMatcher disjointSuffixRepeatMatcher,
            ScanPlan scanPlan,
            // The scan plan's boolean plan, resolved here so that full-Slice boolean find loads
            // it as directly as it would load the plan.
            ScanPlan scanBooleanPlan,
            // Scans for a literal of two or more bytes by its first and last bytes together, when
            // it fits the forward-DFA allowance: for boolean contains and dot-star spans in Trino,
            // and in the other languages for contains of a literal longer than
            // LiteralSearchKernel.CANDIDATE_COMPARISON_BUDGET bytes; null leaves them on
            // Slice.indexOf. Exact-literal spans never use it.
            LiteralSearchKernel literalSearch,
            // Searches an alternation of more than LiteralAlternationSpanMatcher.MAXIMUM_ALTERNATIVES
            // literals for the RE2, Java, and Rust RegexpMatcher.find() group-zero spans; every
            // other operation keeps the program.
            LiteralSetSpanMatcher literalSet,
            InnerLiteralSearch innerLiteralSearch,
            // Answer full-Slice boolean find and count of a pattern that would otherwise run the
            // general engine; null leaves them on it. Trino's fixed-width subtype answers its
            // other operations from the same fixed-width matcher.
            LiteralAlternationSpanMatcher literalAlternationMatcher,
            FixedWidthByteSpanMatcher fixedWidthByteMatcher,
            // Answers full-Slice boolean find of a one-byte member run on inputs of at most 64
            // bytes; null leaves it on the general engine.
            SingleByteMatcher byteRunMatcher)
    {
        private BooleanPlans withScanPlan(ScanPlan scanPlan)
        {
            ScanPlan scanBooleanPlan = scanPlan == null ? null : scanPlan.booleanPlan();
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, byteRunMatcher);
        }

        private BooleanPlans withTaggedAlternationProgram(TaggedAlternationProgram taggedAlternationProgram)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, byteRunMatcher);
        }

        private BooleanPlans withWordRunMatcher(WordRunMatcher wordRunMatcher)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, byteRunMatcher);
        }

        private BooleanPlans withWholeInputCapturePlan(WholeInputCapturePlan wholeInputCapturePlan)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, byteRunMatcher);
        }

        private BooleanPlans withDisjointSuffixRepeatMatcher(DisjointSuffixRepeatMatcher disjointSuffixRepeatMatcher)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, byteRunMatcher);
        }

        private BooleanPlans withLiteralSearch(LiteralSearchKernel literalSearch)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, byteRunMatcher);
        }

        private BooleanPlans withLiteralSet(LiteralSetSpanMatcher literalSet)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, byteRunMatcher);
        }

        private BooleanPlans withInnerLiteralSearch(InnerLiteralSearch innerLiteralSearch)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, byteRunMatcher);
        }

        private BooleanPlans withLiteralAlternationMatcher(LiteralAlternationSpanMatcher literalAlternationMatcher)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, byteRunMatcher);
        }

        private BooleanPlans withFixedWidthByteMatcher(FixedWidthByteSpanMatcher fixedWidthByteMatcher)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, byteRunMatcher);
        }

        private BooleanPlans withByteRunMatcher(SingleByteMatcher byteRunMatcher)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, byteRunMatcher);
        }
    }

    private record LoweredBooleanProgram(Prog program, Regexp normalizedRegexp) {}

    private static boolean runSubmatchEngine(
            Prog prog,
            Slice context,
            int start,
            int end,
            boolean submatchAnchored,
            Prog.MatchKind matchKind,
            int[] groupOffsets,
            MatchWorkspaces workspaces)
    {
        boolean canOnePass = prog.isOnePass() &&
                submatchAnchored &&
                (groupOffsets == null || prog.supportsOnePassCaptureSlots(groupOffsets.length));
        if (canOnePass) {
            if (groupOffsets != null && groupOffsets.length == 2) {
                return OnePass.searchGroupZero(prog, context, start, end, matchKind, groupOffsets);
            }
            return OnePass.search(prog, context, start, end, true, matchKind, groupOffsets, onePassWorkspace(workspaces));
        }

        boolean canBitState = prog.canBitState();
        int bitStateMaxSize = prog.bitStateTextMaxSize();
        if (canBitState && end - start <= bitStateMaxSize) {
            return BitState.search(
                    prog,
                    context,
                    start,
                    end,
                    submatchAnchored,
                    matchKind,
                    groupOffsets,
                    bitStateWorkspace(workspaces));
        }
        if (groupOffsets != null && groupOffsets.length == 2) {
            return Nfa.searchGroupZero(
                    prog,
                    context,
                    start,
                    end,
                    submatchAnchored,
                    matchKind,
                    groupOffsets,
                    nfaWorkspace(workspaces));
        }
        return Nfa.search(
                prog,
                context,
                start,
                end,
                submatchAnchored,
                matchKind,
                groupOffsets,
                nfaWorkspace(workspaces));
    }

    private static MatchWorkspaces fixedMatchWorkspaces(
            OnePass.Workspace onePassWorkspace,
            BitState.Workspace bitStateWorkspace,
            Nfa.Workspace nfaWorkspace)
    {
        if (onePassWorkspace == null && bitStateWorkspace == null && nfaWorkspace == null) {
            return NO_MATCH_WORKSPACES;
        }
        return new FixedMatchWorkspaces(onePassWorkspace, bitStateWorkspace, nfaWorkspace);
    }

    private static boolean hasBitStateWorkspace(MatchWorkspaces workspaces)
    {
        return workspaces instanceof RegexpMatcher || ((FixedMatchWorkspaces) workspaces).bitStateWorkspace() != null;
    }

    private static OnePass.Workspace onePassWorkspace(MatchWorkspaces workspaces)
    {
        if (workspaces instanceof RegexpMatcher matcher) {
            return matcher.onePassWorkspace();
        }
        return ((FixedMatchWorkspaces) workspaces).onePassWorkspace();
    }

    private static BitState.Workspace bitStateWorkspace(MatchWorkspaces workspaces)
    {
        if (workspaces instanceof RegexpMatcher matcher) {
            return matcher.bitStateWorkspace();
        }
        return ((FixedMatchWorkspaces) workspaces).bitStateWorkspace();
    }

    private static Nfa.Workspace nfaWorkspace(MatchWorkspaces workspaces)
    {
        if (workspaces instanceof RegexpMatcher matcher) {
            return matcher.nfaWorkspace();
        }
        return ((FixedMatchWorkspaces) workspaces).nfaWorkspace();
    }

    // Only a matcher searches its input repeatedly, so only a matcher keeps a quit streak.
    private static int dfaQuitStreak(MatchWorkspaces workspaces)
    {
        return workspaces instanceof RegexpMatcher matcher ? matcher.dfaQuitStreak() : 0;
    }

    private static void setDfaQuitStreak(MatchWorkspaces workspaces, int dfaQuitStreak)
    {
        if (workspaces instanceof RegexpMatcher matcher) {
            matcher.setDfaQuitStreak(dfaQuitStreak);
        }
    }

    private static boolean candidateStartCursorQuit(MatchWorkspaces workspaces)
    {
        return workspaces instanceof RegexpMatcher matcher && matcher.candidateStartCursorQuit();
    }

    private static void shiftGroupOffsets(int[] groupOffsets, int delta)
    {
        for (int pairIndex = 0; pairIndex < (groupOffsets.length / 2); pairIndex++) {
            int groupOffsetIndex = 2 * pairIndex;
            if (groupOffsets[groupOffsetIndex] >= 0) {
                groupOffsets[groupOffsetIndex] += delta;
                groupOffsets[groupOffsetIndex + 1] += delta;
            }
        }
    }

    private boolean matchesRequiredPrefix(Slice text, int start, int length)
    {
        if (length < requiredPrefix.length) {
            return false;
        }

        byte[] bytes = text.byteArray();
        int offset = text.byteArrayOffset() + start;
        if (!requiredPrefixFoldCase) {
            return Arrays.mismatch(bytes, offset, offset + requiredPrefix.length, requiredPrefix, 0, requiredPrefix.length) < 0;
        }

        for (int index = 0; index < requiredPrefix.length; index++) {
            int actual = bytes[offset + index] & 0xFF;
            int expected = requiredPrefix[index] & 0xFF;
            actual = asciiLower(actual);
            expected = asciiLower(expected);
            if (actual != expected) {
                return false;
            }
        }
        return true;
    }

    private static int asciiLower(int value)
    {
        if ('A' <= value && value <= 'Z') {
            return value + ('a' - 'A');
        }
        return value;
    }

    /// Get or lazily compile the reverse program for this regex.
    ///
    /// Used in `matchInto()` for:
    /// - Anchor-end quick rejection: when `prog.anchorEnd()`, runs reverse DFA anchored
    ///   from text end for constant-time rejection of non-matching text
    /// - Two-phase DFA search: forward DFA finds match end, reverse DFA finds match start
    ///
    /// **Memory Budget:**
    /// Reverse program gets 1/3 of `maxMemory`, forward gets 2/3.
    /// This split matches upstream RE2::Init behavior. An inner literal search shares the reverse
    /// third.
    ///
    /// @return the reverse program, or null if compilation failed
    private Prog reverseProg()
    {
        if (reverseProgComputed) {
            return reverseProg;
        }
        synchronized (this) {
            if (!reverseProgComputed) {
                reverseProg = compileReverse();
                reverseProgComputed = true;
            }
        }
        return reverseProg;
    }

    /// Compile the reverse program with appropriate memory budget (1/3 of total, less what an inner
    /// literal search retains).
    ///
    /// Memory allocation strategy (matching upstream):
    /// - Forward program: 2/3 of maxMemory (more common, can have 2 DFAs: first-match and longest-match)
    /// - Reverse program: 1/3 of maxMemory (less frequent, only used in two-phase search), less
    ///   what an inner literal search retains
    ///
    /// The `reversed=true` flag causes:
    /// - Right-to-left byte scanning in Dfa.search
    /// - Anchor flag swapping (^ becomes $, $ becomes ^)
    /// - Program marked with `prog.setReversed(true)`
    ///
    /// **Optimization:** Uses the retained normalized reverse expression instead of re-parsing
    /// or re-simplifying the pattern.
    ///
    /// @return compiled reverse program, or null if compilation failed
    private Prog compileReverse()
    {
        Prog loweredProgram = booleanPlans.loweredProgram();
        Prog forwardProgram = loweredProgram == null ? partialProg : loweredProgram;
        if (normalizedReverseRegexp == null ||
                forwardProgram.hasDfaUnsupportedAssertions() ||
                forwardProgram.hasFullCaseFold()) {
            return null;
        }
        long reverseMemory = Math.max(1, maxMemory / 3);
        // An inner literal search already holds part of the reverse budget.
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        if (innerLiteralSearch != null) {
            reverseMemory = Math.max(1, reverseMemory - innerLiteralSearch.estimatedRetainedSize());
        }
        // Compile from the stored normalized suffix instead of re-parsing or re-simplifying.
        Prog prog;
        try {
            prog = Compiler.compileNormalized(normalizedReverseRegexp, true, reverseMemory, compilerDialect(), caseFolding());
        }
        catch (RegexpCompileMemoryLimitException ignored) {
            return null;
        }
        return prog;
    }

    private static FanoutResult fanout(Prog prog)
    {
        SparseIntArray fanout = new SparseIntArray(prog.size());
        prog.fanout(fanout);

        int[] buckets = new int[32];
        int size = 0;
        for (int i = 0; i < fanout.size(); i++) {
            int value = fanout.denseValueAt(i);
            if (value == 0) {
                continue;
            }
            int bucket = mostSignificantBit(value);
            if ((value & (value - 1)) != 0) {
                bucket++;
            }
            buckets[bucket]++;
            size = Math.max(size, bucket + 1);
        }
        int[] histogram = new int[size];
        System.arraycopy(buckets, 0, histogram, 0, size);
        return new FanoutResult(size - 1, histogram);
    }

    private static int mostSignificantBit(int value)
    {
        if (value <= 0) {
            throw new IllegalArgumentException("value must be > 0: " + value);
        }
        return 31 - Integer.numberOfLeadingZeros(value);
    }

    /// Returns a copy of this pattern as an instance of a class generated for its case-sensitive
    /// scan plan, or `null` when the pattern has no such plan or the class cannot be generated.
    /// The caller keeps the copy and discards this pattern. The class extends `superclass`, the
    /// language's `CompiledScan…` class.
    final <T extends CompiledPattern> T generateScanPattern(Class<T> superclass)
    {
        if (caseSensitiveScanPlan() == null) {
            return null;
        }
        try {
            return ScanKernelGenerator.generateCompiledScanPattern(this, superclass);
        }
        catch (RuntimeException | LinkageError ignored) {
            // Linking the generator fails here only if a consumer excluded the classfile
            // library, and defining the class can fail too; either leaves the pattern
            // interpreted.
            return null;
        }
    }

    /// Returns whether a [#matchInternal] call of `prog` over a region ending at `end` of a
    /// context ending at `contextEnd` takes the plan route of a pattern with a scan plan: a call
    /// of the forward program over a region that ends at the context end. The caller still keeps
    /// an unanchored boolean search that the plan allows on the ordinary engine there. It leaves
    /// out the interpreter's short boolean route on purpose: a short boolean call that the
    /// interpreter sends to the ordinary engine stays on the generated class. The plan route of
    /// [#matchInternalOnScanPlan] and of a generated class's override sends every other call to
    /// [#matchOnOrdinaryEngine].
    final boolean reachesScanPlan(Prog prog, int contextEnd, int end)
    {
        return prog == partialProg && end == contextEnd;
    }

    /// Makes the argument checks of [#matchOnOrdinaryEngine] for a plan route, and returns
    /// whether the region lies within the context and the input; keep in step.
    final boolean acceptsScanPlanArguments(Slice text, int contextStart, int contextEnd, int start, int end, int[] groupOffsets)
    {
        requireNonNull(text, "text is null");
        if (contextStart < 0 || contextEnd < contextStart || contextEnd > text.length() || start < contextStart || end < start || end > contextEnd) {
            return false;
        }
        if (groupOffsets != null && (groupOffsets.length % 2) != 0) {
            throw new IllegalArgumentException("groupOffsets length must be even: " + groupOffsets.length);
        }
        return true;
    }
}
