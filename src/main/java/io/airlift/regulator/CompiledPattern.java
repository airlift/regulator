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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
        // Two or more literals in order, with gaps that match every byte sequence between them.
        ORDERED_LITERALS,
        // A class run, a literal, and a class run, decided by the bytes beside the literal's hits
        // or else by the general engine. A find of at least the required bytes' window limit whose
        // required literal hands off to the inner literal search takes that handoff instead.
        LITERAL_NEIGHBORS,
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

    // Leave large inputs to the DFA's selective byte scans instead of a scalar table walk. A
    // 64-byte input still takes the table here, unlike MINIMUM_LONG_SEARCH_BYTES below: these
    // full-Slice byte-table routes were measured that way before the shared hand-off existed.
    private static final int MAX_DIRECT_BYTE_SCAN_BYTES = 64;
    // The length at which a search that has a scalar table route and a vector-skipping route
    // hands off between them: with fewer bytes left than this it reads the table, and with at
    // least this many it takes the route whose vector scan skips the bytes no match can start at,
    // or must stop at. On BenchmarkPatternDispatch's 16- to 64-byte rows the table routes won,
    // and on its 1024-byte rows each lost to the vector scan it had replaced; each gate names its
    // own measurement. A vector call's setup costs more than it saves on fewer bytes.
    static final int MINIMUM_LONG_SEARCH_BYTES = 64;
    // Boolean matches of a plan that allows it run on the ordinary engine up to this input
    // length. The forked OPTIONALS and OPTIONAL_RETRY plans lost to the ordinary engine on every
    // target host on rows of up to 40 bytes, and won on Graviton and broke even on Intel at 64
    // bytes.
    private static final int MAX_ORDINARY_SHORT_BOOLEAN_MATCH_BYTES = 48;

    // Internal construction metadata, stored outside the parser flag range to preserve the
    // pattern's compact object layout. It is consulted only by lazy reverse-program compilation.
    private static final int TRINO_COMPILER_DIALECT_FLAG = 1 << 31;
    // The forward share of the largest budget; no budget's share is larger.
    private static final long LARGEST_FORWARD_MEMORY = Long.MAX_VALUE - Long.MAX_VALUE / 3;

    // Above this size the existing rejection paths are already cheap, while loading
    // minimum-width metadata measurably affected the sub-nanosecond anchored path.
    private static final int MINIMUM_LENGTH_CHECK_LIMIT = 4 * 1024;
    private static final int DIRECT_BIT_STATE_CAPTURE_MINIMUM_LENGTH = 4 * 1024;
    // Up to this input length, a start-anchored search for submatches that can run OnePass skips
    // the DFA.
    private static final int ONE_PASS_SUBMATCH_MAXIMUM_LENGTH = 4096;
    // The largest visited bitmap, in bytes, that BitState clears for one window search of the
    // position automaton, a quarter of upstream's per-search bound: a window search runs once a
    // match, and on short matches a larger clear costs more than the Pike NFA it replaces.
    static final long POSITION_NFA_WINDOW_BIT_STATE_MAXIMUM_BYTES = 8L * 1024;
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
        // A pattern whose find uses a literal kernel: contains, prefix, suffix, equality, ordered
        // literals, or literal neighbors, which can leave a find to the ordinary engine.
        LITERAL_KERNEL,
        // An alternation of literals: short literals found by the literal-alternation span matcher,
        // or more than LiteralAlternationSpanMatcher.MAXIMUM_ALTERNATIVES found by the literal set
        // where the forward DFA that full-Slice find runs cannot initialize, or by the literal set
        // alone, which answers every operation, where the program did not fit the budget.
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
    /// @param singleByteMatcher the single-byte table, whose memory is reserved, or `null`
    /// @param retainedClassCountDfa whether a [Shape#CLASS_RUN] pattern counts on the retained
    ///         class count DFA rather than its counter
    /// @param ordinaryShortBooleanMatch whether unanchored boolean calls of a
    ///         [Shape#SCAN_PLAN] pattern's start-anchored plan run on the ordinary engine over
    ///         short inputs
    /// @param singleAttemptCount whether a [Shape#SCAN_PLAN] pattern's start-anchored
    ///         plan counts with one boolean attempt
    /// @param literalKernelFind whether a [Shape#SCAN_PLAN] pattern's full-Slice find
    ///         runs the literal kernel of its find plan rather than the plan
    /// @param literalSetCount whether a [Shape#LITERAL_ALTERNATION] pattern's count runs on its
    ///         literal set rather than the DFA count
    /// @param fixedWidthWithoutProgram whether a Trino [Shape#FIXED_WIDTH] pattern compiled no
    ///         program, so that every call of its placeholder forward program runs the matcher
    /// @param literalSetExtendingLiterals the extending literals a [Shape#LITERAL_ALTERNATION]
    ///         pattern's full match reads beside the literal set where the pattern compiled no
    ///         program, since its program did not fit the budget, so that every call of its
    ///         placeholder forward program runs the set; otherwise `null`
    /// @param foldedStartRun the folded run behind the partial program's transparent start
    ///         literal that its candidate cursor searches for instead, whose memory is reserved, or
    ///         `null`
    record Analysis(
            int flags,
            long maxMemory,
            Compiler.Dialect compilerDialect,
            boolean longestMatch,
            Slice pattern,
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
            SingleByteMatcher singleByteMatcher,
            boolean retainedClassCountDfa,
            boolean ordinaryShortBooleanMatch,
            boolean singleAttemptCount,
            boolean literalKernelFind,
            boolean literalSetCount,
            boolean fixedWidthWithoutProgram,
            LiteralSetSpanMatcher.ExtendingLiterals literalSetExtendingLiterals,
            RequiredBytes requiredBytes,
            Prog.FoldedStartRun foldedStartRun)
    {
        Analysis
        {
            requireNonNull(shape, "shape is null");
        }
    }

    private final int flags;
    private final long maxMemory;
    private final Slice pattern;
    private final ExpressionAnalysis expressionAnalysis;
    private final Prog partialProg;
    // A folded run behind the program's transparent start literal that the candidate cursor
    // searches for in the literal's place, or null; see Dfa.createLiteralCandidateStartCursor.
    private final Prog.FoldedStartRun foldedStartRun;
    private final boolean longestMatch;
    private final int capturingGroupCount;
    private final Map<Integer, String> capturingGroupNames;
    private volatile Map<String, Integer> namedCapturingGroups;

    // A compiled pattern keeps no parse tree. The reverse program's input, the normalized
    // semantic or lowered suffix, is kept until that program is compiled on first use, and only
    // where it fits the reverse share by itself; a pattern whose input does not fit, or whose
    // reverse program could never run, keeps none and has no reverse program. This pattern's
    // lock keeps the input, the program, and the computed flag consistent with each other.
    private Regexp normalizedReverseRegexp;
    // The input of Trino's lazily built repeat matcher, the members of a greedy zero-or-more
    // one-byte repeat, captured from a Trino pattern's suffix where its shape and the program
    // allow it, or null.
    private final SingleByteRepeatMatcher.Repeat singleByteRepeat;
    // Whether the suffix is a greedy class repeat that the class counter takes, where the
    // program allows it.
    private final boolean hasClassRepeat;
    // The class and bounds the lazily built class counter reads, or null.
    private final BoundedCharacterClassCounter.Repeat classRepeat;
    private final byte[] requiredPrefix;
    private final boolean requiredPrefixFoldCase;
    private final int matchLength;
    // The fewest bytes a searched span can have and still hold a match, and an upper bound on
    // them, from the whole expression: its minimum length, which counts a required prefix, and
    // ExpressionAnalysis.maximumSpanLength. A search of a span outside them fails before any
    // engine runs.
    private final int minimumSpanLength;
    private final int maximumSpanLength;
    // Every match is one character as the program reads it, as for \pL or (?s:.).
    private final boolean singleCharacterMatch;
    private final int exactLiteralLength;
    private final BooleanPlans booleanPlans;
    // Whether the ordinary engine's disjoint suffix-repeat, whole-input capture, or word-run
    // matcher exists, so the common call tests one field before the engine cascade.
    private final boolean hasPreEngineMatcher;
    // Whether the ordinary engine's full-Slice boolean find, which, for a pattern on the
    // literal-neighbor plan, runs only when that matcher leaves the find undecided, runs the inner
    // literal search itself, before the rest of the engine; see runsInnerLiteralSearchFirst. With
    // compressed references, the field takes the Re2, JavaRegexp, and TrinoCompiledPattern layouts
    // that had no padding left from 104 to 112 bytes; the other shapes and every RustRegexp layout
    // stay at 112.
    private final boolean findsInnerLiteralFirst;
    // Whether a matcher's search clears its groups before matching. Every shape class clears,
    // since a shape may override matchInternal to answer without the ordinary engine; the language
    // classes themselves need not, since the ordinary engine writes every slot of a match.
    private final boolean clearsGroupsBeforeMatch;
    // Whether a matcher's find over its whole input, from the input start with nothing matched
    // since reset, may take matchAnchoredSubmatch: an ordinary pattern without longest-match
    // semantics whose program can match, is start-anchored, never matches empty, runs one-pass or
    // BitState, and has no prefix, plan, or tagged alternation ahead of those engines.
    private final boolean hasAnchoredSubmatchRoute;
    // Off the compact route, the counter that finds a class pattern's matches: the single members
    // of one large Unicode class, or the runs of a greedy class repeat. Count shares a class
    // repeat's counter, and a single class's where count takes that class as a single member.
    private final BoundedCharacterClassCounter matcherClassCounter;
    // The single-byte table of a one-byte pattern, which route selection builds when its memory
    // fits, or null. Group-zero matchers, the byte-table shape, and Trino share it.
    private final SingleByteMatcher singleByteMatcher;
    // The bytes every match holds, checked on a short window before any engine runs, or null.
    // Where the compile chose a long-window search, a long window is checked too: for the exact
    // literal or the one-of bytes themselves, or for a literal beside another literal route. Not
    // final only so that a frontend route can take back their memory before the frontend's
    // pattern, whose final field publishes this one, is constructed.
    private RequiredBytes requiredBytes;

    private volatile Prog reverseProg;
    private volatile boolean reverseProgComputed;
    private volatile BoundedCharacterClassCounter boundedCharacterClassCounter;

    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "The sealed hierarchy permits only classes of this package; each scan-plan shape class permits only its non-sealed CompiledScan class, which only its generated hidden class extends")
    CompiledPattern(Analysis analysis)
    {
        Compiler.Dialect compilerDialect = requireNonNull(analysis.compilerDialect(), "compilerDialect is null");
        this.flags = compilerDialect == Compiler.Dialect.TRINO ? analysis.flags() | TRINO_COMPILER_DIALECT_FLAG : analysis.flags();
        this.maxMemory = analysis.maxMemory();
        this.longestMatch = analysis.longestMatch();
        this.pattern = requireNonNull(analysis.pattern(), "pattern is null");
        this.expressionAnalysis = requireNonNull(analysis.expressionAnalysis(), "expressionAnalysis is null");
        Regexp suffixRegexp = analysis.suffixRegexp();
        this.requiredPrefix = analysis.requiredPrefix();
        this.requiredPrefixFoldCase = analysis.requiredPrefixFoldCase();
        this.matchLength = analysis.matchLength();
        this.minimumSpanLength = expressionAnalysis.length().minimum();
        this.maximumSpanLength = expressionAnalysis.maximumSpanLength();
        this.singleCharacterMatch = requiredPrefix == null && matchesOneUtf8Character(suffixRegexp);
        this.exactLiteralLength = analysis.exactLiteralLength();
        this.partialProg = requireNonNull(analysis.partialProg(), "partialProg is null");
        this.foldedStartRun = analysis.foldedStartRun();
        this.booleanPlans = requireNonNull(analysis.booleanPlans(), "booleanPlans is null");
        this.hasPreEngineMatcher = keepsPreEngineMatcher(booleanPlans);
        this.clearsGroupsBeforeMatch = !isOrdinaryEngineClass(getClass());
        this.hasAnchoredSubmatchRoute = !clearsGroupsBeforeMatch && takesAnchoredSubmatchRoute();
        this.matcherClassCounter = analysis.matcherClassCounter();
        this.requiredBytes = analysis.requiredBytes();
        // A pattern that keeps required bytes keeps the find that checks them.
        this.findsInnerLiteralFirst = requiredBytes == null && runsInnerLiteralSearchFirst(booleanPlans, requiredPrefix, partialProg);
        this.singleByteMatcher = analysis.singleByteMatcher();
        this.capturingGroupCount = analysis.capturingGroupCount();
        this.capturingGroupNames = requireNonNull(analysis.capturingGroupNames(), "capturingGroupNames is null");
        // Count reads the class counter only where no anchor, assertion, or required prefix
        // reads beyond a match.
        boolean countsOnClassCounter = requiredPrefix == null &&
                !partialProg.anchorStart() &&
                !partialProg.anchorEnd() &&
                !partialProg.hasTextDependentAssertions();
        // One member of a dense class, any character, or any byte counts as a repeat of one.
        BoundedCharacterClassCounter.Repeat singleMember = countsOnClassCounter ? BoundedCharacterClassCounter.analyzeSingleMember(suffixRegexp) : null;
        // Count reuses a class repeat's matcher counter, and a single class's where count takes
        // that class as a single member; any other single class's counter serves matchers alone.
        boolean holdsClassRepeatCounter = matcherClassCounter != null && suffixRegexp.op() != RegexpOp.CHAR_CLASS;
        boolean countsOnMatcherCounter = holdsClassRepeatCounter || (matcherClassCounter != null && singleMember != null);
        this.boundedCharacterClassCounter = countsOnMatcherCounter ? matcherClassCounter : analysis.compactBoundedCharacterClass();
        // Only Trino builds the repeat matcher.
        this.singleByteRepeat = compilerDialect == Compiler.Dialect.TRINO && requiredPrefix == null && !partialProg.hasTextDependentAssertions()
                ? SingleByteRepeatMatcher.analyze(suffixRegexp, expressionAnalysis.caseFolding())
                : null;
        // A counter held from the start is never built lazily, so only a pattern without one
        // analyzes its suffix for the lazy counter's input.
        BoundedCharacterClassCounter.Repeat repeat = boundedCharacterClassCounter == null && countsOnClassCounter
                ? BoundedCharacterClassCounter.analyzeRepeat(suffixRegexp)
                : null;
        // Only count builds a single member's counter, so it is not a class repeat for the class
        // span routes.
        this.classRepeat = boundedCharacterClassCounter != null ? null : repeat != null ? repeat : singleMember;
        this.hasClassRepeat = holdsClassRepeatCounter || repeat != null;
        // The reverse DFA cannot run assertions the forward DFA cannot, or full case folding, and
        // a Java count whose match depends on the reading direction compiles no reverse program.
        Prog loweredProgram = booleanPlans.loweredProgram();
        Prog forwardProgram = loweredProgram == null ? partialProg : loweredProgram;
        Regexp reverseRegexp = forwardProgram.hasDfaUnsupportedAssertions() || forwardProgram.hasFullCaseFold() || forwardProgram.hasDirectionalRepetition()
                ? null
                : analysis.normalizedReverseRegexp();
        long reverseShare = reverseProgramMemory();
        if (reverseRegexp != null && reverseRegexp.estimatedRetainedSize(reverseShare) > reverseShare) {
            reverseRegexp = null;
        }
        this.normalizedReverseRegexp = reverseRegexp;
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
        // selectRoutes decides every prefix literal set it was left.
        if (partialProg.defersPrefixLiteralSet() || forwardProgram.defersPrefixLiteralSet()) {
            throw new IllegalStateException("a forward program's prefix literal set was left undecided");
        }
    }

    /// Copies every field of `pattern`, which the caller then discards, except the named
    /// group map, which is computed on first use.
    CompiledPattern(CompiledPattern pattern)
    {
        this.flags = pattern.flags;
        this.maxMemory = pattern.maxMemory;
        this.pattern = pattern.pattern;
        this.expressionAnalysis = pattern.expressionAnalysis;
        this.partialProg = pattern.partialProg;
        this.foldedStartRun = pattern.foldedStartRun;
        this.longestMatch = pattern.longestMatch;
        this.capturingGroupCount = pattern.capturingGroupCount;
        this.capturingGroupNames = pattern.capturingGroupNames;
        this.singleByteRepeat = pattern.singleByteRepeat;
        this.hasClassRepeat = pattern.hasClassRepeat;
        this.classRepeat = pattern.classRepeat;
        this.requiredPrefix = pattern.requiredPrefix;
        this.requiredPrefixFoldCase = pattern.requiredPrefixFoldCase;
        this.matchLength = pattern.matchLength;
        this.minimumSpanLength = pattern.minimumSpanLength;
        this.maximumSpanLength = pattern.maximumSpanLength;
        this.singleCharacterMatch = pattern.singleCharacterMatch;
        this.exactLiteralLength = pattern.exactLiteralLength;
        this.booleanPlans = pattern.booleanPlans;
        this.hasPreEngineMatcher = pattern.hasPreEngineMatcher;
        this.findsInnerLiteralFirst = pattern.findsInnerLiteralFirst;
        this.clearsGroupsBeforeMatch = !isOrdinaryEngineClass(getClass());
        this.hasAnchoredSubmatchRoute = !clearsGroupsBeforeMatch && pattern.hasAnchoredSubmatchRoute;
        // The reverse program's compile releases its input under the lock, so the copy takes the
        // input, program, and computed flag as one consistent state.
        synchronized (pattern) {
            this.normalizedReverseRegexp = pattern.normalizedReverseRegexp;
            this.reverseProg = pattern.reverseProg;
            this.reverseProgComputed = pattern.reverseProgComputed;
        }
        this.boundedCharacterClassCounter = pattern.boundedCharacterClassCounter;
        this.matcherClassCounter = pattern.matcherClassCounter;
        this.requiredBytes = pattern.requiredBytes;
        this.singleByteMatcher = pattern.singleByteMatcher;
    }

    private static boolean matchesOneUtf8Character(Regexp regexp)
    {
        while (regexp.op() == RegexpOp.CAPTURE) {
            regexp = regexp.child(0);
        }
        return (regexp.op() == RegexpOp.CHAR_CLASS || regexp.op() == RegexpOp.ANY_CHAR) && (regexp.parseFlags() & Regexp.LATIN1) == 0;
    }

    /// Analyzes a parsed pattern: an RE2 pattern compiled from its parse result, or a pattern that
    /// the Java or Rust frontend parsed. A Java pattern compiles in the Java dialect, a Rust pattern
    /// in the Rust dialect, and an RE2 pattern in the RE2 dialect. The caller passes a private
    /// snapshot that it never mutates. `scanPlanMode` decides whether the scan plan of an eligible
    /// shape is built, and how it runs.
    static Analysis analyzeParsed(Slice pattern, ParseResult parsed, int flags, long maxMemory, ByteFrequencies byteFrequencies, ScanPlan.Language language, ScanPlanMode scanPlanMode)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(parsed, "parsed is null");
        requireNonNull(byteFrequencies, "byteFrequencies is null");
        if (maxMemory <= 0) {
            throw new IllegalArgumentException("maxMemory must be greater than zero: " + maxMemory);
        }
        Compiler.Dialect compilerDialect = switch (language) {
            case JAVA -> Compiler.Dialect.JAVA;
            case RUST -> Compiler.Dialect.RUST;
            default -> Compiler.Dialect.RE2;
        };
        return build(pattern, parsed, flags, maxMemory, byteFrequencies, false, compilerDialect, language, scanPlanMode);
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

        Regexp entireRegexp = parsed.regexp();
        CaseFolding caseFolding = parsed.caseFolding();
        // withRequiredTrinoBudget must report what any route added here needs.
        FixedWidthByteSpanMatcher fixedWidthMatcher = shouldAnalyzeFixedWidthByteSpanForTrino(parsed)
                ? FixedWidthByteSpanMatcher.analyze(entireRegexp)
                : null;
        // A matcher that bails on dense candidates hands its search to the program, so its
        // pattern compiles one, and the full analysis decides it as in every language.
        Regexp normalizedRegexp = fixedWidthMatcher != null && !fixedWidthMatcher.bailsOnDenseCandidates()
                ? Simplifier.simplify(entireRegexp, caseFolding, Compiler.Dialect.TRINO, 0)
                : null;
        if (normalizedRegexp != null) {
            // Routes of the program can precede the matcher: a multi-byte inner literal search,
            // and for a matcher that scans a rare candidate or searches blocks, the routes it
            // yields to. Trino decides them on its program, as every language does, so such a
            // pattern compiles one. A fixed-width pattern has no assertion, so its expression
            // predicts, before any program compiles, whether find would try a multi-byte inner
            // literal search.
            if (fixedWidthMatcher.yieldsToEngineScans() ||
                    innerLiteralPrecedesFixedWidth(InnerLiteralSearch.predictMinimumLiteralLength(normalizedRegexp, caseFolding, byteFrequencies))) {
                try {
                    Analysis analysis = buildWithProgram(pattern, parsed, flags, maxMemory, byteFrequencies, false, Compiler.Dialect.TRINO, ScanPlan.Language.TRINO, scanPlanMode);
                    // The pattern takes the route without a program where the program fits but
                    // no route of it precedes the matcher. That route reserves the matcher's
                    // memory from the forward budget. A matcher that scans a rare candidate or
                    // searches blocks takes it where the matcher yields to no route of the program
                    // and find tries no multi-byte inner literal search. Any other matcher takes it
                    // where neither the inner literal search nor the matcher fits beside the
                    // program.
                    BooleanPlans booleanPlans = analysis.booleanPlans();
                    boolean withoutProgram = fixedWidthMatcher.yieldsToEngineScans()
                            ? !yieldsToEngineRoute(fixedWidthMatcher, entireRegexp, false, analysis.partialProg()) && !searchesMultiByteInnerLiteral(booleanPlans)
                            : booleanPlans.innerLiteralSearch() == null && booleanPlans.fixedWidthByteMatcher() == null;
                    if (withoutProgram) {
                        Analysis matcherOnly = analyzeFixedWidthWithoutProgram(pattern, entireRegexp, normalizedRegexp, caseFolding, fixedWidthMatcher, flags, maxMemory);
                        if (matcherOnly != null) {
                            return matcherOnly;
                        }
                    }
                    return analysis;
                }
                catch (RegexpCompileMemoryLimitException e) {
                    // The routes that precede the matcher need a program; without room for one,
                    // the matcher is the route, so the pattern compiles wherever the matcher alone
                    // fits.
                    Analysis analysis = analyzeFixedWidthWithoutProgram(pattern, entireRegexp, normalizedRegexp, caseFolding, fixedWidthMatcher, flags, maxMemory);
                    if (analysis == null) {
                        throw e;
                    }
                    return analysis;
                }
            }
            Analysis analysis = analyzeFixedWidthWithoutProgram(pattern, entireRegexp, normalizedRegexp, caseFolding, fixedWidthMatcher, flags, maxMemory);
            if (analysis != null) {
                return analysis;
            }
        }
        return buildWithProgram(pattern, parsed, flags, maxMemory, byteFrequencies, false, Compiler.Dialect.TRINO, ScanPlan.Language.TRINO, scanPlanMode);
    }

    /// Returns the analysis of a Trino fixed-width pattern that answers every operation from
    /// `fixedWidthMatcher` and compiles no program, or null when the matcher does not fit the
    /// forward budget.
    private static Analysis analyzeFixedWidthWithoutProgram(
            Slice pattern,
            Regexp entireRegexp,
            Regexp normalizedRegexp,
            CaseFolding caseFolding,
            FixedWidthByteSpanMatcher fixedWidthMatcher,
            int flags,
            long maxMemory)
    {
        long forwardMemory = forwardMemory(maxMemory);
        ExpressionAnalysis expressionAnalysis = ExpressionAnalysis.analyzeNormalized(normalizedRegexp, caseFolding);
        Prog placeholderProgram;
        try {
            placeholderProgram = Compiler.compileNormalized(Regexp.noMatch(flags), false, forwardMemory, Compiler.Dialect.TRINO, caseFolding);
        }
        catch (RegexpCompileMemoryLimitException ignored) {
            // A budget too small for an empty program holds no matcher either, and the program
            // route reports what the pattern needs.
            return null;
        }
        if (fixedWidthMatcher.estimatedRetainedSize() > placeholderProgram.dfaMemory()) {
            return null;
        }
        placeholderProgram.setDfaMemory(0);
        BooleanPlans booleanPlans = selectBooleanPlans(expressionAnalysis, -1, null, placeholderProgram).withFixedWidthByteMatcher(fixedWidthMatcher);
        return selectRoutes(
                flags,
                maxMemory,
                Compiler.Dialect.TRINO,
                false,
                pattern,
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
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                null);
    }

    static boolean shouldAnalyzeFixedWidthByteSpanForTrino(ParseResult parsed)
    {
        Regexp regexp = parsed.regexp();
        // Exact literals have a smaller dedicated route. Avoid allocating position masks for a
        // shape that cannot reach the fixed-width subtype.
        return parsed.capturingGroupCount() == 0 &&
                regexp.op() != RegexpOp.LITERAL &&
                regexp.op() != RegexpOp.LITERAL_STRING &&
                regexp.requiredPrefix(parsed.caseFolding()) == null;
    }

    /// Analyzes a parsed pattern of RE2, Java, or Rust. Where the program of a leftmost-first
    /// pattern does not fit the budget, a pattern that is a large capture-free literal set, such
    /// as a dictionary of words, compiles without one wherever the set fits, and every operation
    /// runs on the set, as Trino's large literal sets do (see [#analyzeLiteralSetWithoutProgram]).
    /// A program that fits is always kept, since its DFAs count and search faster than the set's
    /// automaton. Trino takes its set before it compiles anything, and analyzes its pattern in
    /// [#analyzeParsedForTrino]. A pattern that does not fit `maxMemory` throws
    /// [RegexpCompileMemoryLimitException] reporting a lower bound on the budget it needs by
    /// either route.
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
        try {
            return buildWithProgram(pattern, parsed, flags, maxMemory, byteFrequencies, longestMatch, compilerDialect, scanPlanLanguage, scanPlanMode);
        }
        catch (RegexpCompileMemoryLimitException e) {
            // A route added here needs a required budget beside requiredLiteralSetWithoutProgramBudget.
            boolean literalSetWithoutProgram = !longestMatch && parsed.capturingGroupCount() == 0;
            if (literalSetWithoutProgram) {
                // The rejected compile kept nothing: its programs and plans were its own, and the
                // parse it read is immutable.
                Analysis analysis = analyzeLiteralSetWithoutProgram(pattern, parsed, flags, maxMemory, compilerDialect);
                if (analysis != null) {
                    return analysis;
                }
            }
            long requiredBudget = requiredProgramBudget(e, parsed, maxMemory, longestMatch, compilerDialect);
            if (literalSetWithoutProgram) {
                requiredBudget = leastRequiredBudget(requiredBudget, requiredLiteralSetWithoutProgramBudget(parsed, flags, maxMemory, compilerDialect));
            }
            throw Compiler.withRequiredBudget(e, maxMemory, requiredBudget);
        }
    }

    /// Returns the exception a Trino compile with `maxMemory` throws when `rejection` rejected
    /// [#analyzeParsedForTrino], reporting a lower bound on the budget the pattern needs by any
    /// route: its literal alternation `literalAlternationMatcher`, which is `null` where the
    /// pattern has none, its large literal set, its fixed-width matcher, or its program.
    static RegexpCompileMemoryLimitException withRequiredTrinoBudget(
            RegexpCompileMemoryLimitException rejection,
            ParseResult parsed,
            int flags,
            long maxMemory,
            LiteralAlternationSpanMatcher literalAlternationMatcher)
    {
        long requiredBudget = requiredProgramBudget(rejection, parsed, maxMemory, false, Compiler.Dialect.TRINO);
        if (literalAlternationMatcher != null) {
            requiredBudget = leastRequiredBudget(requiredBudget, budgetForForwardMemory(literalAlternationMatcher.estimatedRetainedSize()));
        }
        if (parsed.capturingGroupCount() == 0) {
            long literalSetSize = LiteralSetSpanMatcher.leastRetainedSize(parsed.regexp(), forwardMemory(maxMemory));
            requiredBudget = leastRequiredBudget(requiredBudget, literalSetSize == Compiler.NO_BUDGET ? Compiler.NO_BUDGET : budgetForForwardMemory(literalSetSize));
        }
        FixedWidthByteSpanMatcher fixedWidthMatcher = shouldAnalyzeFixedWidthByteSpanForTrino(parsed) ? FixedWidthByteSpanMatcher.analyze(parsed.regexp()) : null;
        if (fixedWidthMatcher != null && !fixedWidthMatcher.bailsOnDenseCandidates()) {
            // The matcher takes what the placeholder program leaves of the forward budget.
            requiredBudget = leastRequiredBudget(requiredBudget, budgetForForwardMemory(placeholderProgramMemory(flags, Compiler.Dialect.TRINO, parsed.caseFolding(), fixedWidthMatcher.estimatedRetainedSize())));
        }
        return Compiler.withRequiredBudget(rejection, maxMemory, requiredBudget);
    }

    /// Returns a lower bound on the least budget with which [#buildWithProgram] compiles the
    /// pattern `rejection` rejected with `maxMemory`, or [Compiler#NO_BUDGET] where no budget
    /// does. The program it compiles is the suffix after a required literal prefix; a compact
    /// pattern's counter, which it takes in place of a program, measured exactly what it needs.
    private static long requiredProgramBudget(RegexpCompileMemoryLimitException rejection, ParseResult parsed, long maxMemory, boolean longestMatch, Compiler.Dialect compilerDialect)
    {
        long forwardMemory = forwardMemory(maxMemory);
        Regexp entireRegexp = parsed.regexp();
        if (parsed.capturingGroupCount() == 0 && !longestMatch && BoundedCharacterClassCounter.analyzeCompactRoute(entireRegexp) != null) {
            return Compiler.requiredMeasuredBudget(rejection, forwardMemory, CompiledPattern::budgetForForwardMemory);
        }
        Regexp.RequiredPrefixResult prefixResult = entireRegexp.requiredPrefix(parsed.caseFolding());
        Regexp programRegexp = prefixResult == null ? entireRegexp : prefixResult.suffix();
        return Compiler.requiredProgramBudget(rejection, forwardMemory, CompiledPattern::budgetForForwardMemory, programRegexp, parsed.caseFolding(), compilerDialect);
    }

    /// Returns a lower bound on the least budget with which [#analyzeLiteralSetWithoutProgram]
    /// compiles the pattern, or [Compiler#NO_BUDGET] where no budget does. The set's tries and
    /// extending literals take what the placeholder program leaves of the forward budget; the
    /// check that the set is the shape's route is left out, which only lowers the bound.
    private static long requiredLiteralSetWithoutProgramBudget(ParseResult parsed, int flags, long maxMemory, Compiler.Dialect compilerDialect)
    {
        long setMemory = LiteralSetSpanMatcher.leastMemoryWithExtendingLiterals(parsed.regexp(), forwardMemory(maxMemory));
        if (setMemory == Compiler.NO_BUDGET) {
            return Compiler.NO_BUDGET;
        }
        return budgetForForwardMemory(placeholderProgramMemory(flags, compilerDialect, parsed.caseFolding(), setMemory));
    }

    /// Returns the least forward budget with which the placeholder program, a no-match that holds
    /// only the program's own instructions, compiles and leaves `routeMemory` of its DFA
    /// allowance for the route that takes it.
    private static long placeholderProgramMemory(int flags, Compiler.Dialect compilerDialect, CaseFolding caseFolding, long routeMemory)
    {
        Prog placeholderProgram = Compiler.compileNormalized(Regexp.noMatch(flags), false, 0, compilerDialect, caseFolding);
        return Math.max(Compiler.unflattenedProgramMemory(Compiler.PROGRAM_INSTRUCTIONS), Compiler.estimatedProgramMemory(placeholderProgram) + routeMemory);
    }

    /// Returns the lesser of two required budgets, either of which may be [Compiler#NO_BUDGET].
    private static long leastRequiredBudget(long first, long second)
    {
        if (first == Compiler.NO_BUDGET) {
            return second;
        }
        if (second == Compiler.NO_BUDGET) {
            return first;
        }
        return Math.min(first, second);
    }

    /// Returns the forward share of `maxMemory`, what [#buildWithProgram] keeps for the forward
    /// program past the third it gives the reverse program.
    static long forwardMemory(long maxMemory)
    {
        return Math.max(1, maxMemory - Math.max(1, maxMemory / 3));
    }

    /// Returns the least budget whose [forward share][#forwardMemory(long)] is at least
    /// `forwardMemory`, or [Compiler#NO_BUDGET] where no budget's share is.
    static long budgetForForwardMemory(long forwardMemory)
    {
        if (forwardMemory <= 1) {
            return 1;
        }
        if (forwardMemory == 2) {
            return 3;
        }
        if (forwardMemory > LARGEST_FORWARD_MEMORY) {
            return Compiler.NO_BUDGET;
        }
        // From 3 up, budget b has share b - b / 3, which reaches f first at f + (f - 1) / 2.
        return forwardMemory + (forwardMemory - 1) / 2;
    }

    /// Returns the analysis of a large capture-free literal set that answers every operation from
    /// its literal set and compiles no program, for a pattern whose program does not fit the
    /// budget, or `null` where the expression is no such set, the set does not fit the forward
    /// budget, or its plans route find elsewhere. A full match also reads the set's extending
    /// literals, which hold the literals the automaton omits; they are charged first, so the skip
    /// table, the dense rows, and then the root's skip are built only where they fit beside them.
    /// The pattern keeps no reverse expression and no required bytes, and its placeholder program
    /// has no DFA memory, so no DFA or cursor of it ever runs; every call of it runs the set (see
    /// [#matchInternalOnLiteralSet]).
    private static Analysis analyzeLiteralSetWithoutProgram(Slice pattern, ParseResult parsed, int flags, long maxMemory, Compiler.Dialect compilerDialect)
    {
        long forwardMemory = forwardMemory(maxMemory);
        Regexp entireRegexp = parsed.regexp();
        CaseFolding caseFolding = parsed.caseFolding();
        Prog placeholderProgram;
        try {
            placeholderProgram = Compiler.compileNormalized(Regexp.noMatch(flags), false, forwardMemory, compilerDialect, caseFolding);
        }
        catch (RegexpCompileMemoryLimitException ignored) {
            // A budget too small for an empty program holds no set either.
            return null;
        }
        long availableMemory = placeholderProgram.dfaMemory();
        LiteralSetSpanMatcher.ExtendingLiterals extendingLiterals = LiteralSetSpanMatcher.ExtendingLiterals.analyze(entireRegexp, availableMemory);
        if (extendingLiterals == null) {
            return null;
        }
        long setMemory = availableMemory - extendingLiterals.estimatedRetainedSize();
        LiteralSetSpanMatcher literalSet = setMemory > 0 ? LiteralSetSpanMatcher.analyze(entireRegexp, 0, setMemory) : null;
        if (literalSet == null || literalSet.estimatedRetainedSize() > setMemory) {
            return null;
        }
        placeholderProgram.setDfaMemory(0);
        // As on the compact route, the expression is analyzed as parsed: a literal set holds no
        // repeat or other construct for the simplifier to rewrite.
        ExpressionAnalysis expressionAnalysis = ExpressionAnalysis.analyzeNormalized(entireRegexp, caseFolding);
        BooleanPlans booleanPlans = selectBooleanPlans(expressionAnalysis, -1, null, placeholderProgram).withLiteralSet(literalSet);
        Analysis analysis = selectRoutes(
                flags,
                maxMemory,
                compilerDialect,
                false,
                pattern,
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
                parsed.capturingGroupNames(),
                null,
                null,
                null,
                null,
                null,
                false,
                extendingLiterals,
                null);
        // Every operation runs on the set only where the set is the shape's route for find and
        // count; a pattern whose plans route find elsewhere needs its program.
        return analysis.shape() == Shape.LITERAL_ALTERNATION && analysis.literalSetCount() ? analysis : null;
    }

    private static Analysis buildWithProgram(
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
        long forwardMemory = forwardMemory(maxMemory);

        Regexp entireRegexp = parsed.regexp();
        int capturingGroupCount = parsed.capturingGroupCount();
        CaseFolding caseFolding = parsed.caseFolding();
        BoundedCharacterClassCounter compactBoundedCharacterClass = capturingGroupCount == 0 && !longestMatch
                ? BoundedCharacterClassCounter.analyzeCompactRoute(entireRegexp)
                : null;
        if (compactBoundedCharacterClass != null) {
            long counterMemory = compactBoundedCharacterClass.estimatedRetainedSize();
            // The counter shares the forward budget with the placeholder program, a no-match that
            // holds only the program's own instructions.
            long placeholderMemory = Compiler.unflattenedProgramMemory(Compiler.PROGRAM_INSTRUCTIONS);
            if (counterMemory > forwardMemory - placeholderMemory) {
                throw new RegexpCompileMemoryLimitException(forwardMemory, counterMemory + placeholderMemory);
            }

            ExpressionAnalysis expressionAnalysis = ExpressionAnalysis.analyzeNormalized(entireRegexp, caseFolding);
            long availableMemory = forwardMemory - counterMemory;
            Prog placeholderProgram = Compiler.compileNormalized(Regexp.noMatch(flags), false, availableMemory, compilerDialect, caseFolding);
            placeholderProgram.setDfaMemory(0);
            BooleanPlans booleanPlans = selectBooleanPlans(expressionAnalysis, -1, null, placeholderProgram);
            Prog partialProgram = placeholderProgram;
            if (RetainedCharacterClassCountDfa.supports(entireRegexp)) {
                try {
                    Regexp normalizedRegexp = Simplifier.simplify(entireRegexp, caseFolding, compilerDialect, availableMemory);
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
                    compactBoundedCharacterClass,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null);
        }

        // For "^literal...", compile only the suffix and check the required prefix before matching.
        // Retain the suffix AST so reverse-program construction also avoids the stripped literal.
        Regexp suffixRegexp;
        boolean prefixFoldCase = false;
        Regexp.RequiredPrefixResult prefixResult = entireRegexp.requiredPrefix(caseFolding);
        if (prefixResult != null) {
            prefixFoldCase = prefixResult.foldCase();
            suffixRegexp = prefixResult.suffix();
        }
        else {
            suffixRegexp = entireRegexp;
        }
        // The suffix is the program compiled below, so simplify it first, checking that its
        // expansion can fit before expanding anything.
        Regexp normalizedSuffixRegexp = Simplifier.simplify(suffixRegexp, caseFolding, compilerDialect, forwardMemory);
        // The program is compiled before the expression is analyzed: the check counts each copy of
        // a class as one instruction, and the analysis reads every range of the class at each
        // copy, so only the compiler's limits keep that reading proportional to the budget.
        // Its prefix literal set is decided in selectRoutes, after every plan and route.
        Prog semanticProgram = Compiler.compileNormalizedDeferringPrefixLiteralSet(normalizedSuffixRegexp, forwardMemory, compilerDialect, caseFolding, byteFrequencies);
        // The entire expression adds only its leading anchors and the stripped literal prefix to a
        // suffix whose expansion passed that check, so simplifying it needs no budget of its own.
        Regexp normalizedRegexp = suffixRegexp == entireRegexp ? normalizedSuffixRegexp : Simplifier.simplify(entireRegexp, caseFolding, compilerDialect, 0);
        ExpressionAnalysis expressionAnalysis = ExpressionAnalysis.analyzeNormalized(normalizedRegexp, caseFolding);
        byte[] requiredPrefix = prefixResult == null ? null : requiredPrefixBytes(prefixResult.prefix(), expressionAnalysis.literalSequence());
        // The whole-expression analysis describes the entire expression, so a split-off suffix
        // needs its own length.
        int encodedMatchLength = prefixResult != null ? MatchLength.analyze(suffixRegexp, caseFolding).encoded() : expressionAnalysis.length().encoded();

        // OnePass evaluates Java's `$` and `\Z` and Trino's `$`. The tables of a program that is
        // one-pass only because of them come off here, so no plan or route counts them, and return
        // in selectRoutes, after its routes and ahead of the required bytes, only if they fit
        // beside the forward DFAs.
        Prog.OnePassTables finalLineEndOnePass = semanticProgram.isOnePassOnlyForFinalLineEnds() ? semanticProgram.releaseOnePass() : null;
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
        Nfa.planCaptureSearches(semanticProgram, forwardMemory, taggedAlternationProgram == null ? null : taggedAlternationProgram.program());
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
        // The literal every match begins with behind a leading assertion, configured below. The
        // plans it depends on are settled here, so the window literal search can yield to it.
        byte[] transparentStartLiteral = searchesTransparentStartLiteral(booleanPlans, longestMatch, requiredPrefix, semanticProgram)
                ? semanticProgram.buildTransparentStartLiteral()
                : null;
        // A literal every match holds after its first element, searched before the DFA runs. It is
        // left out where another plan answers the searches it would serve, and for longest-match
        // patterns, whose matches the leftmost-first search does not report. It takes its memory
        // from the reverse budget, so the forward DFA keeps its whole budget, but leaves the full
        // reverse program, compiled on first use, what its DFA needs to initialize. That is
        // estimated from the forward program, compiled from the same expression: its memory and
        // the least memory of a longest-match DFA over it, and an eighth more, since reversed
        // UTF-8 sequences can take a few more instructions. The reverse prefix program takes half
        // the reverse budget, or less if the search would otherwise not fit beside that estimate,
        // and the search is left out unless its reverse prefix DFA can initialize. A pattern of the
        // dot-star literal matcher's shape below, such as `.*literal.*` in RE2, Trino, and Rust's
        // string API, is left to that matcher: only the class-run proof admits its prefix of dots,
        // so the search is left out, as it was before that proof.
        if (requiredPrefix == null && loweredBooleanProgram == null && !longestMatch && exactLiteral == null &&
                booleanPlans.scanPlan() == null && booleanPlans.wordRunMatcher() == null && booleanPlans.disjointSuffixRepeatMatcher() == null) {
            long forwardEstimate = Compiler.estimatedProgramMemory(semanticProgram) + Dfa.DfaInstance.minimumMemory(semanticProgram, Dfa.DfaInstance.Kind.LONGEST_MATCH);
            long fullReverseMemory = forwardEstimate + forwardEstimate / 8;
            long searchMemory = reverseMemory - fullReverseMemory;
            long prefixMemory = Math.max(1, reverseMemory / 2);
            boolean dotStarLiteralShape = DotStarLiteralSpanMatcher.analyze(expressionAnalysis, booleanPlans.literal(), null, (flags & Regexp.LATIN1) != 0) != null;
            InnerLiteralSearch innerLiteralSearch = dotStarLiteralShape ? null : InnerLiteralSearch.analyze(normalizedSuffixRegexp, semanticProgram, prefixMemory, searchMemory, compilerDialect, caseFolding, byteFrequencies);
            if (innerLiteralSearch != null) {
                booleanPlans = booleanPlans.withInnerLiteralSearch(innerLiteralSearch);
            }
            else {
                // A pattern without one, such as an alternation whose alternatives each hold the
                // literal after a bounded-width prefix, may search for a literal every match holds
                // within a bounded window of its start. Like the inner literal search, it takes its
                // small memory from the reverse share, so the forward DFA keeps its whole budget. A
                // pattern whose every match begins with a literal behind an assertion keeps the
                // candidate cursor's search for it instead.
                WindowLiteralSearch windowLiteralSearch = transparentStartLiteral == null
                        ? WindowLiteralSearch.analyze(normalizedSuffixRegexp, semanticProgram, searchMemory)
                        : null;
                if (windowLiteralSearch != null) {
                    booleanPlans = booleanPlans.withWindowLiteralSearch(windowLiteralSearch);
                }
            }
        }
        // The bytes every match holds, for the short- and long-window checks, come from the
        // semantic expression: the lowered expression adds only an optional final LF and an end
        // assertion.
        RequiredBytes requiredBytes = RequiredBytes.analyze(normalizedSuffixRegexp, MatchLength.minimum(expressionAnalysis.length().encoded()));
        // A call-free first-and-last-byte search for contains and dot-star spans, in every
        // language. Unlike Slice.indexOf, it bounds the bytes its failed candidates compare, so
        // long partial matches of a long literal do not cost the input times the literal. A Trino
        // search, and elsewhere a search for a literal longer than
        // LiteralSearchKernel.CANDIDATE_COMPARISON_BUDGET bytes, reserves its memory whenever it
        // fits. Any other search yields to the forward DFAs; see costsDfaInitialization.
        LiteralSearchKernel literalSearch = createLiteralSearch(booleanPlans);
        boolean reservesWheneverItFits = literalSearch != null &&
                (compilerDialect == Compiler.Dialect.TRINO || booleanPlans.literal().length() > LiteralSearchKernel.CANDIDATE_COMPARISON_BUDGET);
        if (reservesWheneverItFits) {
            if (literalSearch.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
                semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - literalSearch.estimatedRetainedSize());
                booleanPlans = booleanPlans.withLiteralSearch(literalSearch);
            }
        }
        else if (literalSearch != null && tryReserveBesideForwardDfas(semanticProgram, literalSearch.estimatedRetainedSize())) {
            booleanPlans = booleanPlans.withLiteralSearch(literalSearch);
        }
        // Every language finds the spans of a `.*literal.*` pattern with the contains search above.
        // The matcher holds the plans' literal, the contains search, which reserved its memory
        // above, and a shared width table, so it reserves only its own instance from the forward
        // DFA budget, in Trino too, and yields to the forward DFAs; see costsDfaInitialization.
        DotStarLiteralSpanMatcher dotStarLiteralMatcher = DotStarLiteralSpanMatcher.analyze(expressionAnalysis, booleanPlans.literal(), booleanPlans.literalSearch(), (flags & Regexp.LATIN1) != 0);
        if (dotStarLiteralMatcher != null && tryReserveBesideForwardDfas(semanticProgram, dotStarLiteralMatcher.estimatedRetainedSize())) {
            booleanPlans = booleanPlans.withDotStarLiteralMatcher(dotStarLiteralMatcher);
        }
        // The literal every match begins with behind a leading assertion. The candidate cursor
        // scans for it, or for a folded run behind it, and verifies, leftmost first, each start
        // that holds it, and when the DFA cannot run the assertions, the group-zero NFA skips to
        // it instead. Its search is built here, never on first match, and reserves its memory
        // from the program that holds the forward DFA budget: the lowered program, when there is
        // one. A pattern neither can serve keeps no literal, and its DFA keeps the memory. A
        // pattern whose reservation would leave that program's DFAs too little to initialize also
        // keeps no literal.
        Prog.FoldedStartRun foldedStartRun = null;
        if (transparentStartLiteral != null) {
            LiteralSearchKernel transparentStartLiteralSearch = new LiteralSearchKernel(Slices.wrappedBuffer(transparentStartLiteral));
            long transparentStartLiteralSize = transparentStartLiteralSearch.estimatedRetainedSize() + SizeOf.sizeOf(transparentStartLiteral);
            Prog budgetProgram = loweredBooleanProgram == null ? semanticProgram : loweredBooleanProgram.program();
            if (budgetProgram.leavesDfaMinimum(transparentStartLiteralSize)) {
                budgetProgram.setDfaMemory(budgetProgram.dfaMemory() - transparentStartLiteralSize);
                semanticProgram.configureTransparentStartLiteral(transparentStartLiteral, transparentStartLiteralSearch);
                // A folded run behind the literal whose pair is more than four times rarer than
                // the literal's (see rarerFoldedStartRun) feeds the candidate cursor in its place,
                // when the DFA runs the program's assertions, so the cursor can serve it. Its scan
                // reserves its memory as the literal's did. The literal stays configured, so every
                // other route decides as before. The pattern, not the program, keeps the run: the
                // program's fields and folded prefix scan are compiled into the DFA's scan loop,
                // and a change to them moved that loop's layout.
                Prog.FoldedStartRun rarerRun = semanticProgram.hasDfaUnsupportedAssertions()
                        ? null
                        : rarerFoldedStartRun(normalizedSuffixRegexp, transparentStartLiteral, transparentStartLiteralSearch, caseFolding, byteFrequencies);
                if (rarerRun != null) {
                    long rarerRunSize = rarerRun.estimatedRetainedSize();
                    if (budgetProgram.leavesDfaMinimum(rarerRunSize)) {
                        budgetProgram.setDfaMemory(budgetProgram.dfaMemory() - rarerRunSize);
                        foldedStartRun = rarerRun;
                    }
                }
            }
        }
        // The regex crate's Aho-Corasick route for large literal alternations, which the forward
        // DFA's candidate starts and the reverse DFA do not scale to. The automaton reports
        // leftmost-first matches only, so RE2's longest-match mode keeps the program. No Trino
        // analysis carries a set: TrinoRegexp.compile takes every set that fits its forward
        // budget before it compiles a program, and this analysis is bounded by the program's DFA
        // budget, which is that forward budget less the program, so a set Trino declined is
        // declined here too.
        LiteralSetSpanMatcher literalSet = !longestMatch ? LiteralSetSpanMatcher.analyze(entireRegexp, capturingGroupCount, semanticProgram.dfaMemory()) : null;
        // The root skip only speeds up the set's search, so it yields to the forward DFAs: a set
        // whose root skip would leave the first-match or longest-match DFA too little to
        // initialize drops it and is admitted as it was before it, whether or not the DFA would
        // initialize beside the set without it, since the root skip changes no result. The check
        // judges the set without its dense rows and skip table, which yield below.
        // Prog.leavesDfaMinimum is not enough here: its minimum leaves out the start-byte tables
        // the constructor builds for a forward program, 272 bytes per DFA for a 256-entry
        // candidate table, so a root skip it admitted could still cost a DFA. A set without the
        // root skip skips this check, which builds those tables to measure them.
        if (literalSet instanceof LiteralSetSpanMatcher.RootSkippingMatcher) {
            long sizeWithoutTables = literalSet.withoutSearchTables().estimatedRetainedSize();
            if (!(semanticProgram.leavesDfaInitialization(sizeWithoutTables, Dfa.DfaInstance.Kind.FIRST_MATCH) &&
                    semanticProgram.leavesDfaInitialization(sizeWithoutTables, Dfa.DfaInstance.Kind.LONGEST_MATCH))) {
                literalSet = literalSet.withoutRootSkipping();
            }
        }
        // The set's dense rows and skip table only speed up its search too, but they yield to the
        // forward DFAs only where they cost one (see costsDfaInitialization): a set whose tables
        // would leave the first-match or longest-match DFA too little to initialize, where the set
        // without them would leave it enough, drops them. Where a DFA cannot initialize beside the
        // set either way, the set keeps them, as for a dictionary of tens of thousands of words
        // under a 96 MiB budget, whose matchers search the set.
        if (literalSet != null) {
            LiteralSetSpanMatcher withoutTables = literalSet.withoutSearchTables();
            if (costsDfaInitialization(semanticProgram, literalSet.estimatedRetainedSize(), withoutTables.estimatedRetainedSize())) {
                literalSet = withoutTables;
            }
        }
        if (literalSet != null && literalSet.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - literalSet.estimatedRetainedSize());
            booleanPlans = booleanPlans.withLiteralSet(literalSet);
        }
        // Full-Slice boolean find of literals in order whose gaps read any bytes, such as RE2's
        // `a\C*b\C*c`, searches for each in turn after the previous one's match, in place of a
        // find the general engine would answer. The gaps decide, with no language branch; see
        // createByteGapOrderedLiteralMatcher. The matcher reserves its memory from the forward
        // DFA budget, as Trino's ordered-literal subtype does, but unlike that subtype, which
        // reserves whenever it fits, it yields to the forward DFAs; see costsDfaInitialization. No
        // Trino pattern takes this plan.
        if (booleanPlans.find() == BooleanPlanKind.GENERAL && booleanPlans.scanPlan() == null) {
            OrderedLiteralMatcher orderedLiteralMatcher = createByteGapOrderedLiteralMatcher(expressionAnalysis);
            if (orderedLiteralMatcher != null && mayReserveForwardDfaMemory(booleanPlans, orderedLiteralMatcher.estimatedRetainedSize()) &&
                    tryReserveBesideForwardDfas(semanticProgram, orderedLiteralMatcher.estimatedRetainedSize())) {
                booleanPlans = booleanPlans.withOrderedLiteralMatcher(orderedLiteralMatcher);
            }
        }
        // Full-Slice boolean find of a class run, a literal, and a class run, such as
        // `([^ @]+)@([^ @]+)`, reads one byte on each side of each hit of the literal in place of
        // the general engine, which it calls only for a hit beside a byte it cannot decide, or when
        // the density sample finds a one-byte literal dense; see LiteralNeighborMatcher. A long
        // find whose required literal hands off to the inner literal search keeps that handoff;
        // see findOnLiteralNeighbors. A pattern with a required prefix keeps the engine: the suffix
        // analyzed here leaves that prefix out. The matcher reserves its memory as the
        // ordered-literal matcher does.
        if (booleanPlans.find() == BooleanPlanKind.GENERAL && booleanPlans.scanPlan() == null && requiredPrefix == null) {
            LiteralNeighborMatcher literalNeighborMatcher = LiteralNeighborMatcher.analyze(normalizedSuffixRegexp);
            if (literalNeighborMatcher != null && mayReserveForwardDfaMemory(booleanPlans, literalNeighborMatcher.estimatedRetainedSize()) &&
                    tryReserveBesideForwardDfas(semanticProgram, literalNeighborMatcher.estimatedRetainedSize())) {
                booleanPlans = booleanPlans.withLiteralNeighborMatcher(literalNeighborMatcher);
            }
        }
        // Span matchers for full-Slice boolean find and count of a pattern that no literal plan,
        // lowered program, or scan plan answers. Each finds the general engine's leftmost-first
        // spans. The literal trie prefers the first alternative, so a longest-match pattern keeps
        // the general engine; a fixed-width pattern has at most one match at each start, so its
        // leftmost-longest and leftmost-first spans coincide.
        boolean generalFind = booleanPlans.find() == BooleanPlanKind.GENERAL && booleanPlans.scanPlan() == null;
        LiteralAlternationSpanMatcher literalAlternationMatcher = generalFind && !longestMatch
                ? LiteralAlternationSpanMatcher.analyze(entireRegexp, capturingGroupCount)
                : null;
        if (literalAlternationMatcher != null && literalAlternationMatcher.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - literalAlternationMatcher.estimatedRetainedSize());
            booleanPlans = booleanPlans.withLiteralAlternationMatcher(literalAlternationMatcher);
        }
        boolean fixedWidth = generalFind && literalAlternationMatcher == null &&
                mayHaveFixedWidthByteSpanMatcher(capturingGroupCount, expressionAnalysis);
        FixedWidthByteSpanMatcher fixedWidthByteMatcher = fixedWidth && !searchesMultiByteInnerLiteral(booleanPlans)
                ? FixedWidthByteSpanMatcher.analyze(entireRegexp, expressionAnalysis)
                : null;
        if (fixedWidthByteMatcher != null && yieldsToEngineRoute(fixedWidthByteMatcher, suffixRegexp, longestMatch, semanticProgram)) {
            fixedWidthByteMatcher = null;
        }
        if (fixedWidthByteMatcher != null && fixedWidthByteMatcher.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - fixedWidthByteMatcher.estimatedRetainedSize());
            booleanPlans = booleanPlans.withFixedWidthByteMatcher(fixedWidthByteMatcher);
        }
        // A pattern that keeps the inner literal search holds a second, short-search fixed-width
        // matcher. It answers, in place of the plain DFA, the counts and group-zero matcher finds
        // shorter than MINIMUM_LONG_SEARCH_BYTES, which do not run that search. On short Trino
        // rows, count through the ordinary engine measured 1.72-1.79x slower than with the
        // fixed-width matcher on r9g and 1.36-1.40x on r8i, and iteration 1.12-1.16x on r9g.
        // Full-Slice find keeps the engine, and the shape stays ENGINE: the engine's quick reject
        // of a row without the literal beat the matcher on short rows too. That reject was
        // measured when the inner literal search made it; a row too short for the search now gets
        // it from the required bytes check, where the pattern keeps one. The short-search matcher
        // follows the full matcher's order with the engine's routes.
        FixedWidthByteSpanMatcher shortFixedWidthByteMatcher = fixedWidth && searchesMultiByteInnerLiteral(booleanPlans)
                ? FixedWidthByteSpanMatcher.analyze(entireRegexp, expressionAnalysis)
                : null;
        if (shortFixedWidthByteMatcher != null && yieldsToEngineRoute(shortFixedWidthByteMatcher, suffixRegexp, longestMatch, semanticProgram)) {
            shortFixedWidthByteMatcher = null;
        }
        if (shortFixedWidthByteMatcher != null && shortFixedWidthByteMatcher.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - shortFixedWidthByteMatcher.estimatedRetainedSize());
            booleanPlans = booleanPlans.withShortFixedWidthByteMatcher(shortFixedWidthByteMatcher);
        }
        // A run of one-byte members, such as [\t\n\f\r ]+, exists exactly where one member does.
        SingleByteMatcher byteRunMatcher = generalFind && literalAlternationMatcher == null && fixedWidthByteMatcher == null && capturingGroupCount == 0
                ? SingleByteMatcher.analyzeRun(entireRegexp, caseFolding)
                : null;
        if (byteRunMatcher != null && SingleByteMatcher.estimatedRetainedSize() <= semanticProgram.dfaMemory()) {
            semanticProgram.setDfaMemory(semanticProgram.dfaMemory() - SingleByteMatcher.estimatedRetainedSize());
            booleanPlans = booleanPlans.withByteRunMatcher(byteRunMatcher);
        }
        // A one-of set of two or three distinct bytes beside an inner literal search checks only
        // the windows too short for that search, by the minimum searchesInnerLiteralWindow
        // applies, never shortened by a gated one-byte search's check of shorter windows: from
        // there the search scans each window for its literal before any engine runs, and the
        // one-of scan would repeat or replace that scan. A literal of two or more bytes, and a
        // single required byte, keep their check of every window shorter than
        // RequiredBytes.SHORT_WINDOW_LIMIT. The single byte's scan costs as little as the search's
        // single-byte scan, and a check that changed with line length cost 4 to 6% on naturally
        // mixed line lengths, although each length range alone tied: on lh3lh3's lines under 128
        // bytes, 1.06 times the time without required bytes on r9g and 1.04 to 1.06 times on r8i.
        if (requiredBytes != null && requiredBytes.isOneOf() && !requiredBytes.isSingleByte() && booleanPlans.innerLiteralSearch() != null) {
            int searchMinimum = booleanPlans.shortFixedWidthByteMatcher() != null ? MINIMUM_LONG_SEARCH_BYTES : booleanPlans.innerLiteralSearch().minimumSearchBytes();
            requiredBytes = requiredBytes.withWindowLimit(Math.min(searchMinimum, RequiredBytes.SHORT_WINDOW_LIMIT));
        }
        // A pattern whose full-Slice boolean find runs its gated one-byte search first keeps no
        // single required byte that is the search's own: the search scans for the byte at every
        // length before any engine runs, so its scan is the check. On a line that holds the byte,
        // a check ahead of the search never rejected and cost about 100 instructions: lh3lh3's
        // date lines of 64 to 127 bytes, which hold `/`, took 1.08 to 1.12 times their time
        // without the check. A matcher's find then enters the ordinary engine with no
        // required-bytes check; ranged find, findInto, and count never checked.
        if (requiredBytes != null && runsInnerLiteralSearchFirst(booleanPlans, requiredPrefix, semanticProgram) &&
                requiredBytes.singleByteValue() == booleanPlans.innerLiteralSearch().oneByteLiteral()) {
            requiredBytes = null;
        }
        // The bit-parallel automaton that the group-zero searches of a program with bounded
        // matches run where they would otherwise step the Pike NFA over the whole text, once the
        // forward DFA fails. It is built here, never on first match; route selection reserves it
        // from `partialProg`'s DFA budget after every route selection makes, and it yields to the
        // forward DFAs.
        PositionNfa positionNfa = PositionNfa.build(semanticProgram);

        return selectRoutes(
                flags,
                maxMemory,
                compilerDialect,
                longestMatch,
                pattern,
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
                null,
                requiredBytes,
                normalizedSuffixRegexp,
                finalLineEndOnePass,
                positionNfa,
                false,
                null,
                foldedStartRun);
    }

    /// Returns the bytes of `prefix`, sharing the literal sequence's first literal when it holds
    /// them, as it does for a prefix that a gap or class follows, so a pattern keeps one copy.
    private static byte[] requiredPrefixBytes(Slice prefix, ExpressionAnalysis.LiteralSequence literalSequence)
    {
        if (literalSequence != null && literalSequence.literalCount() != 0) {
            Slice literal = literalSequence.retainedLiteral(0);
            if (literal.equals(prefix)) {
                return literal.byteArray();
            }
        }
        return prefix.getBytes();
    }

    /// Returns the analysis of a pattern with its shape and routes selected. Selection reserves the
    /// memory of the matcher class counter and the single-byte table from `partialProg`'s DFA
    /// budget, then of the OnePass tables in `finalLineEndOnePass`, which it restores to
    /// `partialProg`, and of the required bytes from the program that holds the forward DFA
    /// budget, with `positionNfa` reserved between them from `partialProg`'s budget and
    /// configured on it, all before any DFA can snapshot a budget, and builds the table.
    private static Analysis selectRoutes(
            int flags,
            long maxMemory,
            Compiler.Dialect compilerDialect,
            boolean longestMatch,
            Slice pattern,
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
            RequiredBytes requiredBytes,
            Regexp requiredBytesRegexp,
            Prog.OnePassTables finalLineEndOnePass,
            PositionNfa positionNfa,
            boolean fixedWidthWithoutProgram,
            LiteralSetSpanMatcher.ExtendingLiterals literalSetExtendingLiterals,
            Prog.FoldedStartRun foldedStartRun)
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
                case CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, EQUALS_FINAL_LINE, ENDS_WITH_FINAL_LINE, ORDERED_LITERALS, LITERAL_NEIGHBORS -> Shape.LITERAL_KERNEL;
            };
        }
        // As on the compact route, a pattern with a capture keeps the engine. A pattern can retain
        // the counter beside a fixed-width matcher that does not yield to it, such as [0-9]{4} or
        // [jqxz]{3}, and both are charged: full-Slice find and count take the matcher, while the
        // group-zero find of RegexpMatcher stays on the counter (see groupZeroSpanSearch).
        BoundedCharacterClassCounter matcherClass = requiredPrefix == null && compactBoundedCharacterClass == null && capturingGroupCount == 0
                ? BoundedCharacterClassCounter.analyzeMatcherRoute(suffixRegexp, longestMatch)
                : null;
        if (matcherClass != null && matcherClass.estimatedRetainedSize() <= partialProg.dfaMemory()) {
            partialProg.setDfaMemory(partialProg.dfaMemory() - matcherClass.estimatedRetainedSize());
        }
        else {
            matcherClass = null;
        }
        // A group-zero matcher's find runs the table ahead of the scan plan, so a pattern with a
        // scan plan takes no table and keeps its plan. No one-byte pattern is known to take a
        // plan, so this guard is defensive.
        SingleByteMatcher singleByteMatcher = booleanPlans.scanPlan() == null &&
                mayHaveSingleByteMatcher(requiredPrefix, partialProg, matchLength) &&
                SingleByteMatcher.supports(suffixRegexp, expressionAnalysis.caseFolding()) &&
                SingleByteMatcher.estimatedRetainedSize() <= partialProg.dfaMemory()
                ? SingleByteMatcher.analyze(suffixRegexp, expressionAnalysis.caseFolding())
                : null;
        if (singleByteMatcher != null) {
            partialProg.setDfaMemory(partialProg.dfaMemory() - SingleByteMatcher.estimatedRetainedSize());
            if (general) {
                shape = Shape.BYTE_TABLE;
                general = false;
            }
        }
        // After the routes above, the OnePass tables build released and then the required bytes
        // take their memory from the program that holds the forward DFA budget, the lowered
        // program where there is one. Between them, the position automaton takes its memory from
        // `partialProg`, whose searches run it. Each is kept only where every forward DFA that
        // could initialize still can; see costsDfaInitialization. The tables and the automaton come
        // after every plan and route selection makes, so they change none of them, and the literal
        // set's DFA checks below give the same answers with or without them. A program that does
        // not keep its tables runs on BitState or the NFA, and one that does not keep its automaton
        // runs on the Pike NFA where it would have run the automaton. The required bytes only save
        // work, so they give way to the tables, and to the automaton too where both charge the same
        // program; the required bytes reject on every search, the automaton only after the DFA
        // fails. A pattern they do not fit keeps no check. A frontend route reserved later, Trino's
        // ordered-literal matcher, is charged ahead of the required bytes, and they are judged
        // again after it. A pattern with a single-byte table keeps none: its group-zero matchers
        // find on the table once fewer than MINIMUM_LONG_SEARCH_BYTES are left, and a check ahead
        // of the table would scan such a window twice. An exact literal, or the one-of bytes, of a
        // pattern with no other literal route takes its long-window search into the same charge,
        // with the search-end bound's search where the literal has one. The one-of bytes' search
        // keeps nothing beyond the bytes, so it does not raise the charge. A literal searched
        // beside another literal route takes its long-window search without the bound. If the full
        // charge does not fit, the bound is removed and the charge retried. If that fails, the
        // pattern keeps only the short-window check when eligible and affordable, and otherwise
        // keeps no check. An inner literal search is such a route unless only the class-run proof
        // admits it. An exact literal whose first hit full-Slice find hands to the inner literal
        // search takes the handoff's search into the charge where the handoff has a search of its
        // own. Each smaller step gives up the handoff, and the last, the short-window check alone,
        // also gives up a handoff's search of its own. The literal-neighbor find plan is not, since
        // the pattern's other operations keep the ordinary engine.
        Prog forwardBudgetProgram = booleanPlans.loweredProgram() == null ? partialProg : booleanPlans.loweredProgram();
        if (finalLineEndOnePass != null && tryReserveBesideForwardDfas(forwardBudgetProgram, finalLineEndOnePass.retainedSize())) {
            partialProg.restoreOnePass(finalLineEndOnePass);
        }
        if (positionNfa != null && tryReserveBesideForwardDfas(partialProg, positionNfa.estimatedRetainedSize())) {
            partialProg.configurePositionNfa(positionNfa);
        }
        RequiredBytes reservedRequiredBytes = null;
        if (requiredBytes != null && singleByteMatcher == null) {
            RequiredBytes preferred = null;
            if (mayCheckRequiredLiteralOnLongWindows(booleanPlans, requiredPrefix, exactLiteralLength, partialProg, compactBoundedCharacterClass, matcherClass)) {
                if (requiredBytes.admitsLongWindowSearch() && checksRequiredLiteralOnLongWindows(booleanPlans, partialProg)) {
                    preferred = boundsSearchEnd(partialProg, booleanPlans.loweredProgram())
                            ? requiredBytes.withLongWindowSearchAndBound()
                            : requiredBytes.withLongWindowSearch();
                }
                else {
                    // A pattern with another literal route searches long windows for its rarest
                    // exact literal that the route does not already search for, without the
                    // search-end bound. Required bytes that are neither an exact literal nor the
                    // one-of bytes, of a pattern with no such route, have no long-window search.
                    // The literal comes first, so a pattern without one never builds the
                    // start-byte tables that the route check may need. The short-window check
                    // keeps the required bytes either way.
                    byte[] literal = RequiredBytes.literalBesideRoutes(requiredBytesRegexp, routeLiterals(booleanPlans));
                    if (literal != null && (requiredBytes.admitsLongWindowSearch() || !checksRequiredLiteralOnLongWindows(booleanPlans, partialProg))) {
                        preferred = requiredBytes.withBesideRouteSearch(literal);
                    }
                }
            }
            // A literal of a pattern whose matches span at least the short-window limit serves
            // only the long-window check, so a pattern without that check keeps none.
            if (preferred == null && !requiredBytes.isLongWindowsOnly()) {
                preferred = requiredBytes.withoutLongWindowSearch();
            }
            if (preferred != null && handsOffRequiredLiteral(booleanPlans, requiredPrefix, partialProg, preferred)) {
                preferred = preferred.withInnerLiteralHandoff();
            }
            if (preferred != null) {
                reservedRequiredBytes = reserveRequiredBytes(forwardBudgetProgram, preferred);
            }
        }
        // The prefix literal sets come last, from the program that holds the forward DFA budget,
        // so no plan or route above judges its memory with their bytes taken.
        forwardBudgetProgram.decidePrefixLiteralSet(forwardBudgetProgram);
        partialProg.decidePrefixLiteralSet(forwardBudgetProgram);
        boolean ordinaryShortMatch = false;
        boolean singleAttemptCount = false;
        boolean literalKernelFind = false;
        boolean literalSetCount = false;
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
        // general engine. The literal set answers full-Slice find and count only where the
        // forward DFA each would run on cannot initialize: on text where most bytes lead a
        // literal, the set's walk takes several steps per byte to the DFA's one. A boolean find's
        // earliest-match search runs the longest-match DFA and count the first-match DFA, which
        // needs no more memory, so a set whose count cannot run on the DFA cannot find on it
        // either. A DFA count that bails returns no count, and the matcher counts on the set.
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
            else if (booleanPlans.literalSet() != null && !partialProg.leavesDfaInitialization(0, Dfa.DfaInstance.Kind.LONGEST_MATCH)) {
                shape = Shape.LITERAL_ALTERNATION;
                literalSetCount = !partialProg.leavesDfaInitialization(0, Dfa.DfaInstance.Kind.FIRST_MATCH);
            }
        }
        return new Analysis(
                flags,
                maxMemory,
                compilerDialect,
                longestMatch,
                pattern,
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
                literalKernelFind,
                literalSetCount,
                fixedWidthWithoutProgram,
                literalSetExtendingLiterals,
                reservedRequiredBytes,
                foldedStartRun);
    }

    /// Whether a window of [RequiredBytes#SHORT_WINDOW_LIMIT] bytes or more may be checked for a
    /// required literal before any engine runs, by the pattern's exact literal or one-of bytes or
    /// by a literal beside another literal route; [#checksRequiredLiteralOnLongWindows] tells
    /// which. A match lies inside the window, so a window without them holds none, in every
    /// language and search mode. The check is left to a pattern whose engine reads the whole of a
    /// window that holds no match. So the pattern has no required prefix, and neither its forward
    /// nor its lowered program is anchored at either end, where the engine can stop early. It also
    /// has none of the shapes whose routes answer without the ordinary engine: an exact literal, a
    /// literal kernel or search, a class counter, a literal set or alternation, or a span,
    /// byte-run, word-run, disjoint suffix-repeat, or whole-input capture matcher. A
    /// [LITERAL_NEIGHBORS][BooleanPlanKind#LITERAL_NEIGHBORS] find plan does not disqualify the
    /// check, since the pattern's other operations keep the ordinary engine: it answers
    /// full-Slice boolean find alone, and count, ranged find, and the `RegexpMatcher` searches
    /// run the engine as without it. A find whose required literal
    /// [hands off][#findFromRequiredLiteral] on a long window takes that handoff instead of the
    /// plan, as without it. Nor does a [window literal search][WindowLiteralSearch]:
    /// BitState's capture of groups and a find that skips the DFA never run it, and the check
    /// covers them as without the search. Where the window search does run, a window that holds
    /// the literal is scanned twice, by the check and by the search, as it is by the check and
    /// the engine without the search.
    ///
    /// Its inner literal search, if any, is for a literal that
    /// [begins every match][InnerLiteralSearch#literalBeginsEveryMatch], which lands where a
    /// match would start, as a prefix scan does, or one that
    /// [only the class-run proof adds][InnerLiteralSearch#addedByClassRunProof]. Any other inner
    /// literal search finds its literal inside a match, and already scans each window that
    /// reaches it for that literal. Where nearly every line holds the other literal near its end,
    /// a check for that literal beside the search doubles the scan and never rejects.
    private static boolean mayCheckRequiredLiteralOnLongWindows(
            BooleanPlans booleanPlans,
            byte[] requiredPrefix,
            int exactLiteralLength,
            Prog partialProg,
            BoundedCharacterClassCounter compactBoundedCharacterClass,
            BoundedCharacterClassCounter matcherClass)
    {
        BooleanPlanKind find = booleanPlans.find();
        Prog loweredProgram = booleanPlans.loweredProgram();
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        return requiredPrefix == null && exactLiteralLength <= 0 &&
                (find == BooleanPlanKind.GENERAL || find == BooleanPlanKind.LOWERED_PROGRAM || find == BooleanPlanKind.LITERAL_NEIGHBORS) &&
                compactBoundedCharacterClass == null && matcherClass == null &&
                booleanPlans.literalSearch() == null &&
                booleanPlans.literalSet() == null &&
                booleanPlans.literalAlternationMatcher() == null &&
                booleanPlans.fixedWidthByteMatcher() == null &&
                booleanPlans.shortFixedWidthByteMatcher() == null &&
                booleanPlans.byteRunMatcher() == null &&
                booleanPlans.wordRunMatcher() == null &&
                booleanPlans.disjointSuffixRepeatMatcher() == null &&
                booleanPlans.wholeInputCapturePlan() == null &&
                !partialProg.anchorStart() && !partialProg.anchorEnd() &&
                (loweredProgram == null || (!loweredProgram.anchorStart() && !loweredProgram.anchorEnd())) &&
                (innerLiteralSearch == null || innerLiteralSearch.addedByClassRunProof() || innerLiteralSearch.literalBeginsEveryMatch());
    }

    /// Whether a pattern that [may check long windows][#mayCheckRequiredLiteralOnLongWindows]
    /// has no other literal route that starts where a match starts, so that it checks long
    /// windows for its own exact required literal or one-of bytes. Such a route is a scan plan,
    /// an inner literal search that the class-run proof does not alone add, or, in the forward or
    /// lowered program, a prefix scan, a transparent start literal, or a start literal filter that
    /// its first-match DFA can run and that groups of one bit also give. A pattern with one of
    /// those checks long windows for a literal beside it instead; see
    /// [RequiredBytes#literalBesideRoutes]. The pattern decides as it would without a search that
    /// only the class-run proof adds, so that proof neither adds the check to a pattern nor takes
    /// it away. A filter that only wider groups give, such as that of the a to g of
    /// `[a-g]{3}://\S+|[a-g]{3}@[^ @]+`, does not count, for the reason the fixed-width matcher's
    /// yield does not count it: the check was measured against filters of one-bit groups. The
    /// check runs first, and the DFA keeps the wider filter inside the windows that pass.
    ///
    /// A program whose folded run stands in for no prefix is judged as without the run: the run
    /// is no prefix scan, and the start literal filter is the one its start bytes would carry;
    /// see [Prog#prefixRunAddsPrefixScan]. Whether the first-match DFA initializes is still
    /// judged on the program the search runs, whose start-byte tables the run replaces, so in the
    /// few hundred bytes of budget between the two programs' initialization charges the decision
    /// can differ from the program without the run. Such a program has no transparent start
    /// literal either way, since that needs an assertion before the first byte, where
    /// [Regexp#foldedRunsForAccel] stops. A pattern with a single-byte table is not checked here:
    /// the caller keeps no required bytes for it at all.
    private static boolean checksRequiredLiteralOnLongWindows(BooleanPlans booleanPlans, Prog partialProg)
    {
        Prog loweredProgram = booleanPlans.loweredProgram();
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        return booleanPlans.scanPlan() == null &&
                (innerLiteralSearch == null || innerLiteralSearch.addedByClassRunProof()) &&
                !scansForStartLiteral(partialProg) &&
                (loweredProgram == null || !scansForStartLiteral(loweredProgram)) &&
                // Last, since it builds the start-byte tables.
                !searchesOneBitStartLiteralFilter(partialProg) &&
                (loweredProgram == null || !searchesOneBitStartLiteralFilter(loweredProgram));
    }

    // The literals that a pattern's route searches for, which the long-window check beside it
    // does not search for again: the scan plan's literal, which is exact, and the literal of an
    // inner literal search for one literal, with its fold masks where it is case-insensitive,
    // unless only the class-run proof adds that search. A leading inner literal is usually among
    // the start bytes too, but their collection can stop before it, as at the assertion
    // alternation of `(?:\b|\B)Holmes.*?foobar`. The members of a literal set stay out: a search
    // for `the` or `and` does not make a check for `the` redundant, since a window may hold `and`
    // alone.
    private static List<RequiredBytes.RouteLiteral> routeLiterals(BooleanPlans booleanPlans)
    {
        List<RequiredBytes.RouteLiteral> literals = new ArrayList<>();
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        if (innerLiteralSearch != null && !innerLiteralSearch.addedByClassRunProof()) {
            byte[][] innerLiterals = innerLiteralSearch.literals();
            if (innerLiterals.length == 1) {
                literals.add(new RequiredBytes.RouteLiteral(innerLiterals[0], innerLiteralSearch.foldedMasks()));
            }
        }
        ScanPlan scanPlan = booleanPlans.scanPlan();
        if (scanPlan != null && scanPlan.requiredLiteral() != null) {
            literals.add(new RequiredBytes.RouteLiteral(scanPlan.requiredLiteral(), null));
        }
        return literals;
    }

    // Whether `program` has a start literal filter that its first-match DFA can run and that groups
    // of one bit also give; a filter that only wider groups give leaves the long-window check
    // armed, as checksRequiredLiteralOnLongWindows explains. A program whose assertions the DFA
    // cannot run never scans for the filter, and neither does one whose first-match DFA does not
    // initialize in its budget, since the longest-match DFA needs more. A search that skips the DFA
    // anyway, such as a longest-match search whose DFA alone does not fit or a find after a count
    // gave up on the DFA, reads the window as before. The required bytes are reserved later, and
    // only where they leave every DFA that initializes now able to, so the budget is judged without
    // them. The DFA kind decides the start bytes only for a program that can match empty, which has
    // no start literal filter, so one kind answers for every search, except that a program whose
    // folded run starts every match keeps them only in its first-match DFA, the kind asked here.
    // For a folded run that stands in for no prefix, the filter is the one the program's start
    // bytes would carry without the run.
    private static boolean searchesOneBitStartLiteralFilter(Prog program)
    {
        return !program.hasDfaUnsupportedAssertions() &&
                program.leavesDfaInitialization(0, Dfa.DfaInstance.Kind.FIRST_MATCH) &&
                (program.prefixRunAddsPrefixScan()
                        ? program.prefixRunHidesOneBitStartLiteralFilter()
                        : Dfa.DfaInstance.givesOneBitStartLiteralFilter(program, Dfa.DfaInstance.Kind.FIRST_MATCH));
    }

    /// Whether a pattern that checks long windows for its exact required literal may also bound a
    /// search's end by the last occurrence of the bytes every match ends with; see
    /// [RequiredBytes#lastIndexIn]. A match ends at or before that occurrence's end, and none of
    /// its assertions is read at its end. Truncating the window there changes no match, provided
    /// each assertion of a match reads no byte past the next one. The assertions the DFA runs
    /// read one byte ahead. Java's final-terminator `$` and `\Z` and Trino's final line end read
    /// further, so a program with assertions the DFA cannot run takes no bound. A lowered boolean
    /// program comes only from a final line end at the pattern's end, so its pattern has no such
    /// bytes, and its matches can end past them, after a final LF. A pattern with a lowered
    /// program takes no bound either.
    private static boolean boundsSearchEnd(Prog partialProg, Prog loweredProgram)
    {
        return loweredProgram == null && !partialProg.hasDfaUnsupportedAssertions();
    }

    private static boolean scansForStartLiteral(Prog program)
    {
        return (program.canPrefixAccel() && !program.prefixRunAddsPrefixScan()) || program.transparentStartLiteral() != null;
    }

    /// Creates a search for a CONTAINS plan whose literal is at least two bytes long, or returns
    /// null. Boolean contains and dot-star span matching share this search.
    private static LiteralSearchKernel createLiteralSearch(BooleanPlans booleanPlans)
    {
        if (booleanPlans.find() != BooleanPlanKind.CONTAINS || booleanPlans.literal().length() < 2) {
            return null;
        }
        return new LiteralSearchKernel(booleanPlans.literal());
    }

    /// Reserves `reservation` bytes from `program`'s DFA budget for a plan that yields to the
    /// forward DFAs, or returns false when the reservation does not fit or
    /// [#costsDfaInitialization] rejects it.
    private static boolean tryReserveBesideForwardDfas(Prog program, long reservation)
    {
        if (reservation > program.dfaMemory() || costsDfaInitialization(program, reservation)) {
            return false;
        }
        program.setDfaMemory(program.dfaMemory() - reservation);
        return true;
    }

    /// Reserves the required bytes beside `program`'s forward DFAs and returns those kept:
    /// `preferred` where its charge fits, or else, for a literal with its search-end bound, the
    /// same literal with its long-window search alone, or else, for a literal with its long-window
    /// search, or with a [handoff][RequiredBytes#withInnerLiteralHandoff] on a
    /// [search of its own][RequiredBytes#hasOwnHandoffSearch], that does not serve long windows
    /// only, the same literal without that search, for the short-window check alone, where each
    /// smaller charge fits, or else null. The bytes of each smaller step drop the handoff, so a
    /// handoff's search of its own is given up with the long-window search, in the last step.
    private static RequiredBytes reserveRequiredBytes(Prog program, RequiredBytes preferred)
    {
        if (tryReserveBesideForwardDfas(program, preferred.estimatedRetainedSize())) {
            return preferred;
        }
        if (preferred.boundsSearchEnd()) {
            RequiredBytes unbounded = preferred.withoutSearchEndBound();
            if (tryReserveBesideForwardDfas(program, unbounded.estimatedRetainedSize())) {
                return unbounded;
            }
        }
        if ((preferred.searchesLongWindows() || preferred.hasOwnHandoffSearch()) && !preferred.isLongWindowsOnly()) {
            RequiredBytes shortWindowOnly = preferred.withoutLongWindowSearch();
            if (tryReserveBesideForwardDfas(program, shortWindowOnly.estimatedRetainedSize())) {
                return shortWindowOnly;
            }
        }
        return null;
    }

    /// Whether reserving `reservation` bytes from `program`'s DFA budget would leave its
    /// first-match or longest-match DFA too little to initialize where that DFA initializes
    /// without the reservation. A plan this rejects yields to the forward DFAs: it is left out, so
    /// maxMemory decides which forward DFAs initialize as it would without the plan.
    /// [Prog#leavesDfaInitialization] counts the start-byte tables a forward DFA builds, which
    /// [Prog#leavesDfaMinimum] leaves out.
    private static boolean costsDfaInitialization(Prog program, long reservation)
    {
        return costsDfaInitialization(program, reservation, 0);
    }

    /// Whether reserving `reservation` bytes from `program`'s DFA budget would leave its
    /// first-match or longest-match DFA too little to initialize where that DFA initializes beside
    /// the smaller `alternative` reservation instead.
    private static boolean costsDfaInitialization(Prog program, long reservation, long alternative)
    {
        return costsDfaInitialization(program, reservation, alternative, Dfa.DfaInstance.Kind.FIRST_MATCH) || costsDfaInitialization(program, reservation, alternative, Dfa.DfaInstance.Kind.LONGEST_MATCH);
    }

    private static boolean costsDfaInitialization(Prog program, long reservation, long alternative, Dfa.DfaInstance.Kind kind)
    {
        return reservation > alternative && !program.leavesDfaInitialization(reservation, kind) && program.leavesDfaInitialization(alternative, kind);
    }

    /// Creates the matcher of an [ORDERED_LITERALS][BooleanPlanKind#ORDERED_LITERALS] find plan for
    /// literals in order, or returns null. The matcher reads nothing between the literals, so each
    /// gap between two of them must match every byte sequence: a gap of any bytes, such as RE2's
    /// `\C*` or Rust's `(?s-u:.*)` in the bytes API, or a gap of any characters in a Latin-1
    /// pattern. A gap of any characters in UTF-8 rejects malformed UTF-8 in the general engine of
    /// RE2, Java, and Rust alike, so `(?s:a.*b)` matches neither `a\xFFb` nor `a\x80b` there, and
    /// such a pattern keeps that engine. A leading or trailing gap can match empty, so it does not
    /// decide whether a match exists.
    private static OrderedLiteralMatcher createByteGapOrderedLiteralMatcher(ExpressionAnalysis analysis)
    {
        ExpressionAnalysis.LiteralSequence sequence = analysis.literalSequence();
        if (sequence == null) {
            return null;
        }
        for (int literalIndex = 0; literalIndex < sequence.literalCount() - 1; literalIndex++) {
            if (!canMatchUnrestrictedGapDirectly(sequence.gapAfter(literalIndex), analysis.latin1())) {
                return null;
            }
        }
        return OrderedLiteralMatcher.analyze(analysis);
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

        Regexp normalizedRegexp;
        Prog program;
        try {
            normalizedRegexp = Simplifier.simplify(loweredRegexp, caseFolding, compilerDialect, forwardMemory);
            program = Compiler.compileNormalizedDeferringPrefixLiteralSet(normalizedRegexp, forwardMemory, compilerDialect, caseFolding, byteFrequencies);
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
            // and its prefix's KMP shifts. Both programs' prefix literal sets wait for
            // selectRoutes.
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
        return new BooleanPlans(plans.literal(), find, lookingAt, plans.matches(), program, plans.taggedAlternationProgram(), plans.wordRunMatcher(), plans.wholeInputCapturePlan(), plans.disjointSuffixRepeatMatcher(), plans.scanPlan(), plans.scanBooleanPlan(), plans.literalSearch(), plans.literalSet(), plans.innerLiteralSearch(), plans.innerLiteralSearchMinimum(), plans.innerLiteralCountMinimum(), plans.windowLiteralSearch(), plans.literalAlternationMatcher(), plans.fixedWidthByteMatcher(), plans.shortFixedWidthByteMatcher(), plans.byteRunMatcher(), plans.dotStarLiteralMatcher(), plans.orderedLiteralMatcher(), plans.literalNeighborMatcher());
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
        if ((flags & TRINO_COMPILER_DIALECT_FLAG) != 0) {
            return Compiler.Dialect.TRINO;
        }
        // Every Java pattern is a JavaRegexp, a sealed root, so Java's dialect needs no flag bit;
        // the only free parse-flag bit stays free.
        return this instanceof JavaRegexp ? Compiler.Dialect.JAVA : Compiler.Dialect.RE2;
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

    /// Returns whether a [Shape#LITERAL_ALTERNATION] pattern's count runs on its literal set.
    boolean countsOnLiteralSetForDiagnostics()
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

    synchronized boolean hasReverseProgramInputForDiagnostics()
    {
        return normalizedReverseRegexp != null;
    }

    boolean hasRequiredPrefixForDiagnostics()
    {
        return requiredPrefix != null;
    }

    byte[] requiredPrefixForDiagnostics()
    {
        return requiredPrefix;
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

    /// Returns the automaton of an alternation of more than
    /// [LiteralAlternationSpanMatcher#MAXIMUM_ALTERNATIVES] literals, or `null`.
    /// [RegexpMatcher#find()] searches it through [#groupZeroSpanSearch()], and full-Slice find
    /// and count where the general engine would otherwise answer them on a forward DFA that
    /// cannot initialize.
    LiteralSetSpanMatcher literalSetSpanMatcherForDiagnostics()
    {
        return booleanPlans.literalSet();
    }

    FixedWidthByteSpanMatcher fixedWidthByteSpanMatcherForDiagnostics()
    {
        return booleanPlans.fixedWidthByteMatcher();
    }

    /// Returns the span search that a group-zero [RegexpMatcher#find()] runs in place of the
    /// program, or `null`: the pattern's literal-alternation matcher, literal set, dot-star literal
    /// matcher, fixed-width matcher, or short-search fixed-width matcher, in that order, the same
    /// one full-Slice count searches. It reads only what compilation fixed, so a pattern stores no
    /// route of its own, and a pattern with a single-byte table or a class counter, which answer
    /// the find themselves, has none. The span search is immutable and shared; a matcher keeps any
    /// search state it carries between finds.
    GroupZeroSpanSearch groupZeroSpanSearch()
    {
        if (singleByteMatcher != null || matcherClassCounter != null || usesCompactBoundedCharacterClassForDiagnostics()) {
            return null;
        }
        if (booleanPlans.literalAlternationMatcher() != null) {
            return booleanPlans.literalAlternationMatcher();
        }
        if (booleanPlans.literalSet() != null) {
            return booleanPlans.literalSet();
        }
        if (booleanPlans.dotStarLiteralMatcher() != null) {
            return booleanPlans.dotStarLiteralMatcher();
        }
        if (booleanPlans.fixedWidthByteMatcher() != null) {
            return booleanPlans.fixedWidthByteMatcher();
        }
        return booleanPlans.shortFixedWidthByteMatcher();
    }

    /// Returns the fixed-width matcher that answers the counts and matcher finds too short for the
    /// inner literal search beside it, shorter than [#MINIMUM_LONG_SEARCH_BYTES], or `null`.
    FixedWidthByteSpanMatcher shortFixedWidthByteMatcher()
    {
        return booleanPlans.shortFixedWidthByteMatcher();
    }

    boolean hasExactLiteralSpan()
    {
        return exactLiteralLength >= 0;
    }

    /// Returns the span matcher of a `.*literal.*` pattern that compilation built, or `null`.
    DotStarLiteralSpanMatcher createDotStarLiteralSpanMatcher()
    {
        return booleanPlans.dotStarLiteralMatcher();
    }

    /// Returns the ordered-literal matcher of Trino's contains, which reads any bytes between its
    /// literals under Trino's garbage-in, garbage-out rule, or `null`. The caller reserves its
    /// memory with [#tryReserveForwardDfaMemory]. Trino calls this once, as it compiles. A Trino
    /// pattern reads only UTF-8 and has no dot that matches any byte, so it never takes the
    /// [ORDERED_LITERALS][BooleanPlanKind#ORDERED_LITERALS] find plan, whose gaps must match every
    /// byte sequence. This matcher's reservation is therefore its only ordered-literal
    /// reservation.
    OrderedLiteralMatcher createOrderedLiteralMatcher()
    {
        return OrderedLiteralMatcher.analyze(expressionAnalysis);
    }

    /// Returns the matcher of the [ORDERED_LITERALS][BooleanPlanKind#ORDERED_LITERALS] find plan, or
    /// `null`.
    OrderedLiteralMatcher orderedLiteralMatcherForDiagnostics()
    {
        return booleanPlans.orderedLiteralMatcher();
    }

    /// Returns the matcher of the [LITERAL_NEIGHBORS][BooleanPlanKind#LITERAL_NEIGHBORS] find plan,
    /// or `null`.
    LiteralNeighborMatcher literalNeighborMatcherForDiagnostics()
    {
        return booleanPlans.literalNeighborMatcher();
    }

    /// Reserves `retainedSize` bytes of the forward DFA budget for a frontend route, or returns
    /// false and changes nothing. A frontend calls this for a route it prefers to the
    /// short-window check, before it constructs the pattern whose final field publishes this one.
    /// The route is charged first, against the allowance with the required bytes' memory handed
    /// back. The required bytes are then judged again by the rule that admitted them: they stay
    /// only if every forward DFA that initializes after the route's charge still does after
    /// theirs. If their full charge no longer fits, the search-end bound is removed and the
    /// charge retried. If that fails, they keep only the short-window check when eligible and
    /// affordable. Otherwise they keep no check.
    boolean tryReserveForwardDfaMemory(long retainedSize)
    {
        if (!mayReserveForwardDfaMemory(booleanPlans, retainedSize)) {
            return false;
        }
        // Without a lowered program, which the route also requires, the required bytes were
        // reserved from partialProg. Its prefix literal set hands its charge back too, and is
        // decided again last, as in selectRoutes; deciding it on the same allowance after a
        // refused route changes nothing.
        partialProg.deferPrefixLiteralSet();
        long released = requiredBytes == null ? 0 : requiredBytes.estimatedRetainedSize();
        long dfaMemory = partialProg.dfaMemory() + released;
        if (retainedSize > dfaMemory) {
            partialProg.decidePrefixLiteralSet(partialProg);
            return false;
        }
        partialProg.setDfaMemory(dfaMemory - retainedSize);
        if (requiredBytes != null) {
            requiredBytes = reserveRequiredBytes(partialProg, requiredBytes);
        }
        partialProg.decidePrefixLiteralSet(partialProg);
        return true;
    }

    /// Whether a matcher of `retainedSize` bytes that answers in place of the program may reserve
    /// forward DFA memory. A pattern with a lowered boolean program or a tagged alternation
    /// program reserves none.
    private static boolean mayReserveForwardDfaMemory(BooleanPlans booleanPlans, long retainedSize)
    {
        return retainedSize > 0 && booleanPlans.loweredProgram() == null && booleanPlans.taggedAlternationProgram() == null;
    }

    /// Returns the fixed-width matcher this pattern retains, or else one analyzed from `regexp`,
    /// the parse tree this pattern was compiled from, which it does not keep, or `null`.
    FixedWidthByteSpanMatcher createFixedWidthByteSpanMatcher(Regexp regexp)
    {
        FixedWidthByteSpanMatcher retainedMatcher = booleanPlans.fixedWidthByteMatcher();
        if (retainedMatcher != null) {
            return retainedMatcher;
        }
        if (!mayHaveFixedWidthByteSpanMatcher() || searchesMultiByteInnerLiteral(booleanPlans)) {
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
        return mayHaveFixedWidthByteSpanMatcher(capturingGroupCount, expressionAnalysis);
    }

    // A fixed-width pattern's matches hold the same number of characters, at most
    // FixedWidthByteSpanMatcher.MAXIMUM_WIDTH of at most four bytes each, so only a pattern of
    // bounded, nonzero length can have one; FixedWidthByteSpanMatcher.analyze decides.
    private static boolean mayHaveFixedWidthByteSpanMatcher(int capturingGroupCount, ExpressionAnalysis expressionAnalysis)
    {
        ExpressionAnalysis.Length length = expressionAnalysis.length();
        return capturingGroupCount == 0 &&
                length.canMatch() &&
                !length.isUnbounded() &&
                length.minimum() > 0 &&
                length.maximum() <= 4 * FixedWidthByteSpanMatcher.MAXIMUM_WIDTH;
    }

    // The order between the fixed-width matcher and the routes the ordinary engine has for a
    // pattern. An ASCII singleton pattern keeps the matcher. A pattern that scans a rare
    // candidate or searches blocks keeps the engine wherever the forward program skips to a literal
    // prefix or a forward DFA searches for a start literal filter that groups of one bit also give.
    // For (?i)Шерлок Холмс, the filter's three bytes of Шер stop far less often than the lead byte
    // of every Cyrillic letter, which the matcher scans for; a count on the matcher measured 89x
    // the engine's time on r9g and 234x on r8i. Such a pattern also keeps the engine where the
    // class-run counter counts it and the matcher's candidate holds a byte above ASCII, whose
    // density English text does not show. [а-я]{5} and [一-龥]{3}, whose lead bytes are dense in
    // Russian and Chinese text, took 2.9-3.1x and 1.32-1.34x the counter's time on the matcher.
    // [0-9]{4}, whose digits English text rates as it holds them, ran 9.4-10.9x faster on the
    // matcher than on the counter, and 3.8-4.6x on short rows. Neither a plain start-byte scan nor
    // a start class filter counts: every pattern the matcher takes has the scan, and the matcher's
    // rare candidate or block search replaces either.
    private static boolean yieldsToEngineRoute(FixedWidthByteSpanMatcher matcher, Regexp suffixRegexp, boolean longestMatch, Prog program)
    {
        // The DFA kind decides the start bytes only for a program that can match empty, and a
        // fixed-width pattern never matches empty, so one kind answers for every search, except
        // that a program whose folded run starts every match keeps them only in its first-match
        // DFA, the kind asked here. The yield rule was measured on filters whose groups differ in
        // one bit, so the matcher keeps a pattern, such as [éê]x, whose filter needs wider groups.
        return matcher.yieldsToEngineScans() &&
                (program.canSkipToStartCandidate() ||
                        (matcher.bailsOnDenseCandidates() && BoundedCharacterClassCounter.analyzeMatcherRoute(suffixRegexp, longestMatch) != null) ||
                        Dfa.DfaInstance.givesOneBitStartLiteralFilter(program, Dfa.DfaInstance.Kind.FIRST_MATCH));
    }

    private static boolean searchesMultiByteInnerLiteral(BooleanPlans booleanPlans)
    {
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        return innerLiteralSearch != null && innerLiteralPrecedesFixedWidth(innerLiteralSearch.minimumLiteralLength());
    }

    // The one rule, in every language, for whether a pattern whose find searches for an inner
    // literal keeps that search instead of the fixed-width matcher: prefer the inner literal
    // search when its shortest literal has at least two bytes. The matcher checks every
    // occurrence of one byte, such as the s of [a-z]shing, which English text holds far more often
    // than the inner literal shing. A one-byte inner literal, such as the - of an ISO date, is no
    // rarer than the matcher's byte.
    private static boolean innerLiteralPrecedesFixedWidth(int minimumLiteralLength)
    {
        return minimumLiteralLength >= 2;
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

    boolean findsInnerLiteralFirstForDiagnostics()
    {
        return findsInnerLiteralFirst;
    }

    WindowLiteralSearch windowLiteralSearchForDiagnostics()
    {
        return booleanPlans.windowLiteralSearch();
    }

    RequiredBytes requiredBytesForDiagnostics()
    {
        return requiredBytes;
    }

    Prog.FoldedStartRun foldedStartRunForDiagnostics()
    {
        return foldedStartRun;
    }

    long requiredBytesRetainedSizeForDiagnostics()
    {
        return requiredBytes == null ? 0 : requiredBytes.estimatedRetainedSize();
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

    // The constructor calls this after assigning every field it reads.
    private boolean takesAnchoredSubmatchRoute()
    {
        Prog prog = partialProg;
        return prog.anchorStart() && prog.start() != 0 && (prog.isOnePass() || prog.canBitState()) &&
                minimumMatchLength() > 0 &&
                !hasPreEngineMatcher && requiredPrefix == null && !longestMatch &&
                booleanPlans.scanPlan() == null && booleanPlans.literalSet() == null &&
                !(prog.hasTextDependentAssertions() && booleanPlans.taggedAlternationProgram() != null);
    }

    boolean hasAnchoredSubmatchRoute()
    {
        return hasAnchoredSubmatchRoute;
    }

    /// A matcher's find over its whole input, from the input start with nothing matched since
    /// reset, on a pattern with [#hasAnchoredSubmatchRoute()] and groups beyond group zero. It
    /// makes the checks [#matchOnOrdinaryEngine] makes for this case and calls the OnePass or
    /// BitState search that [#matchOnOrdinaryEngine] would reach, skipping its prologue; an input
    /// too long for those engines goes to [#matchOnOrdinaryEngine].
    final boolean matchAnchoredSubmatch(Slice text, int[] groups, RegexpMatcher matcher)
    {
        Prog prog = partialProg;
        int length = text.length();
        if ((length < MINIMUM_LENGTH_CHECK_LIMIT && length < minimumMatchLength()) || length > maximumSpanLength) {
            return false;
        }
        byte[] anchoredStartBytes = prog.anchoredStartBytes();
        if (anchoredStartBytes != null && anchoredStartBytes[text.byteArray()[text.byteArrayOffset()] & 0xFF] == 0) {
            return false;
        }
        Prog.MatchKind matchKind = prog.anchorEnd() ? Prog.MatchKind.FULL_MATCH : Prog.MatchKind.FIRST_MATCH;
        boolean canOnePass = prog.isOnePass() && prog.supportsOnePassCaptureSlots(groups.length);
        if (canOnePass && length <= ONE_PASS_SUBMATCH_MAXIMUM_LENGTH) {
            return OnePass.search(prog, text, 0, length, true, matchKind, groups, matcher.onePassWorkspace());
        }
        if (!canOnePass && prog.canBitState() && length <= prog.anchoredBitStateTextMaxSize()) {
            return BitState.search(prog, text, 0, length, true, matchKind, groups, matcher.bitStateWorkspace());
        }
        return matchOnOrdinaryEngine(prog, text, 0, length, 0, length, Anchor.UNANCHORED, groups, matcher);
    }

    /// Returns the fewest bytes any match of the compiled suffix spans, or 0 when it can be empty.
    /// It leaves out a stripped required prefix, which the whole expression's minimum counts.
    int minimumMatchLength()
    {
        return MatchLength.minimum(matchLength);
    }

    /// Returns the fewest bytes a searched span can have and still hold a match, counting a
    /// stripped required prefix.
    int minimumSpanLength()
    {
        return minimumSpanLength;
    }

    /// Returns an upper bound on the bytes a searched span can have and still hold a match, or
    /// [Integer#MAX_VALUE] where no maximum is known; see [ExpressionAnalysis#maximumSpanLength()].
    int maximumSpanLength()
    {
        return maximumSpanLength;
    }

    /// Whether `[start, end)` of `input` is shorter than the required bytes'
    /// [window limit][RequiredBytes#windowLimit()] and lacks the bytes every match holds, so it
    /// holds no match. Searches check this before any engine runs: a line search's many short
    /// lines that cannot match then skip the find and engine setup as well as the engine's scan.
    final boolean shortWindowLacksRequiredBytes(Slice input, int start, int end)
    {
        RequiredBytes bytes = requiredBytes;
        return bytes != null && end - start < bytes.windowLimit() && !bytes.occursIn(input, start, end);
    }

    /// Whether `[start, end)` of `text`, at least [RequiredBytes#SHORT_WINDOW_LIMIT] bytes, lacks
    /// the exact literal every match consumes, or every one of the one-of bytes, for a pattern
    /// whose long windows are checked for them, so it holds no match. A window that holds them is
    /// scanned only up to their first occurrence, which the engine reads too. Out of line, so the
    /// routes that call it stay small.
    final boolean longWindowLacksRequiredLiteral(Slice text, int start, int end)
    {
        RequiredBytes literal = requiredBytes;
        return literal != null && end - start >= RequiredBytes.SHORT_WINDOW_LIMIT && literal.searchesLongWindows() && literal.indexIn(text, start, end) < 0;
    }

    /// Returns the end an unanchored search of `[start, end)` of `text` needs, or -1 when the
    /// window holds no match. Only a window of at least [RequiredBytes#SHORT_WINDOW_LIMIT] bytes
    /// changes its end. For a pattern with the search-end bound, such a window ends at the end of
    /// the last occurrence of the bytes every match ends with, and holds no match without one;
    /// see [RequiredBytes#lastIndexIn]. Where those bytes do not hold the literal, the window up
    /// to that end is also checked for the literal, and holds no match without it. For a pattern
    /// that checks long windows for its literal or one-of bytes alone, such a window holds no
    /// match without them, as [#longWindowLacksRequiredLiteral] finds, and otherwise keeps its
    /// end. Out of line, so the routes that call it stay small.
    final int longWindowSearchEnd(Slice text, int start, int end)
    {
        RequiredBytes literal = requiredBytes;
        if (literal == null || end - start < RequiredBytes.SHORT_WINDOW_LIMIT || !literal.searchesLongWindows()) {
            return end;
        }
        if (!literal.boundsSearchEnd()) {
            return literal.indexIn(text, start, end) < 0 ? -1 : end;
        }
        int occurrence = literal.lastIndexIn(text, start, end);
        if (occurrence < 0) {
            return -1;
        }
        int boundEnd = occurrence + literal.suffixLength();
        return literal.suffixHoldsLiteral() || literal.indexIn(text, start, boundEnd) >= 0 ? boundEnd : -1;
    }

    /// The required literal, or one-of bytes, that a matcher checks the rest of a long region
    /// for, or null; see [#longWindowLacksRequiredLiteral].
    final RequiredBytes longWindowRequiredLiteral()
    {
        RequiredBytes literal = requiredBytes;
        return literal != null && literal.searchesLongWindows() ? literal : null;
    }

    boolean canUseDirectBitStateCapture(Prog prog, int searchLength, int captureCount)
    {
        return !longestMatch &&
                searchLength >= DIRECT_BIT_STATE_CAPTURE_MINIMUM_LENGTH &&
                captureCount > 1 &&
                !prog.isOnePass() &&
                prog.canBitState() &&
                searchLength <= prog.unanchoredBitStateTextMaxSize();
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

    /// The full-Slice find of the ordinary engine: the short-window rejects or the long-window
    /// literal reject, the nullable-start shortcut, then an ordinary search of the lowered boolean
    /// program where there is one, or else of the forward program. A pattern that
    /// [runs its inner literal search first][#runsInnerLiteralSearchFirst] runs that search before
    /// the engine instead, and keeps no required bytes to check. A long window of a pattern whose
    /// required literal [hands off][#findFromRequiredLiteral] to its inner literal search takes
    /// that route in place of the long-window literal reject and the engine.
    private boolean findOnEngine(Slice input)
    {
        requireNonNull(input, "input is null");
        if (findsInnerLiteralFirst) {
            return findOnInnerLiteralSearch(input);
        }
        Prog loweredProgram = booleanPlans.loweredProgram();
        Prog program = loweredProgram != null ? loweredProgram : partialProg;
        int length = input.length();
        RequiredBytes bytes = requiredBytes;
        if (bytes != null && length < bytes.windowLimit()) {
            // The engine's one-load reject of a start-anchored program's impossible first byte
            // costs less than the required bytes scan, so it goes first. A required prefix moves
            // the program's start past the input's first byte, so it takes no part.
            if (requiredPrefix == null && length > 0 && program.anchorStart()) {
                byte[] anchoredStartBytes = program.anchoredStartBytes();
                if (anchoredStartBytes != null && anchoredStartBytes[input.byteArray()[input.byteArrayOffset()] & 0xFF] == 0) {
                    return false;
                }
            }
            // The length reject costs less still, and comes before the scan, as in a matcher's
            // find.
            if (length < minimumMatchLength()) {
                return false;
            }
            if (!bytes.occursIn(input, 0, length)) {
                return false;
            }
        }
        else if (bytes != null && bytes.handsOffToInnerLiteralSearch()) {
            return findFromRequiredLiteral(input);
        }
        else if (bytes != null && longWindowLacksRequiredLiteral(input, 0, length)) {
            return false;
        }
        if (canReturnEmptyAtStartForBooleanMatch(input, 0)) {
            return true;
        }
        return matchOnOrdinaryEngine(program, input, 0, length, 0, length, Anchor.UNANCHORED, null, NO_MATCH_WORKSPACES);
    }

    /// The full-Slice find of a window of [RequiredBytes#SHORT_WINDOW_LIMIT] bytes or more, for a
    /// pattern whose required literal [hands off][#handsOffRequiredLiteral] to its inner literal
    /// search: the literal check's scan finds the literal's first occurrence, or rejects the
    /// window without one, and the inner literal search
    /// [starts from that occurrence][InnerLiteralSearch#searchFromCheckedHit] rather than
    /// scanning the window from its start again. Where the literal is the search's own, as for
    /// `(\w+)foobar(\d)`, the long-window reject would have scanned to the same occurrence. Where
    /// it is the search's literal and the one byte the search's gate admits after it, as for
    /// `\w+,\bz`, the window was not checked before, and one without `,z` is now rejected without
    /// the engine; the scan for `,z` starts at the search's first `,`, which a one-byte scan
    /// finds; see [RequiredBytes#handoffIndexIn]. A method of its own, so the one test is all
    /// that this route adds to [#findOnEngine].
    ///
    /// The answer is the engine's. Under [#handsOffRequiredLiteral], the engine's call
    /// `matchOnOrdinaryEngine(partialProg, input, 0, length, 0, length, UNANCHORED, null,
    /// NO_MATCH_WORKSPACES)` makes no deciding step before its length reject and the inner literal
    /// search, which runs on every such window, since its minimum is at most the window limit. A
    /// window without the literal holds no match, and the search would return no match on it too:
    /// it has no hit of the search's literal, or none that passes the gate after it. Otherwise
    /// the search from the occurrence returns what the engine's search returns, and after a
    /// fallback [#findAfterInnerLiteralFallback] makes the engine's remaining steps.
    private boolean findFromRequiredLiteral(Slice input)
    {
        int length = input.length();
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        int literalStart = requiredBytes.handoffIndexIn(input, 0, length, innerLiteralSearch);
        if (literalStart < 0) {
            return false;
        }
        if (length < MINIMUM_LENGTH_CHECK_LIMIT && length < minimumMatchLength()) {
            return false;
        }
        long span = innerLiteralSearch.searchFromCheckedHit(input, 0, length, 0, length, literalStart);
        if (span >= 0) {
            return true;
        }
        if (span == Dfa.SEARCH_NO_MATCH) {
            return false;
        }
        return findAfterInnerLiteralFallback(input);
    }

    /// The full-Slice find of a pattern that
    /// [runs its inner literal search first][#runsInnerLiteralSearchFirst]: the length reject and
    /// the inner literal search, which the ordinary engine would make first, answering where the
    /// search decides, and otherwise the steps the ordinary engine takes after its search. A
    /// window without a hit that passes the search's byte gates, most lines of a line search, is
    /// answered without the engine's call and setup. A method of its own, so the field test is all
    /// that this route adds to [#findOnEngine], which stays small enough to inline.
    ///
    /// The answer is the engine's. Under [#runsInnerLiteralSearchFirst], the engine's call
    /// `matchOnOrdinaryEngine(partialProg, input, 0, length, 0, length, UNANCHORED, null,
    /// NO_MATCH_WORKSPACES)` makes no deciding step before the length reject and the inner literal
    /// search, which this makes in the same order, on the same text, context, and window. A
    /// no-match or a span is then what the engine returns, as a boolean find needs no captures.
    ///
    /// After a fallback, [#findAfterInnerLiteralFallback] makes the engine's steps after its
    /// search, rather than entering the engine, which would run the search a second time: that
    /// took 1.08 to 1.11 times as long as the find without this route on email lines of 64 to 127
    /// bytes with a match near the start, where the search falls back.
    private boolean findOnInnerLiteralSearch(Slice input)
    {
        int length = input.length();
        if (length < MINIMUM_LENGTH_CHECK_LIMIT && length < minimumMatchLength()) {
            return false;
        }
        long span = booleanPlans.innerLiteralSearch().search(input, 0, length, 0, length);
        if (span >= 0) {
            return true;
        }
        if (span == Dfa.SEARCH_NO_MATCH) {
            return false;
        }
        return findAfterInnerLiteralFallback(input);
    }

    /// The rest of a full-Slice boolean find whose own inner literal search, in
    /// [#findOnInnerLiteralSearch] or [#findFromRequiredLiteral], fell back:
    /// [#matchOnOrdinaryEngine]'s continuation after its inner literal search falls back, made
    /// here because those routes run the search ahead of the engine, and entering the engine would
    /// run the search a second time. For the engine's call `matchOnOrdinaryEngine(partialProg,
    /// input, 0, length, 0, length, UNANCHORED, null, NO_MATCH_WORKSPACES)` the continuation is the
    /// boolean-search skip of a thrashing DFA, which after a streak of slow resets returns the
    /// answer of the program's position automaton, and otherwise the forward DFA over the window,
    /// judged for those resets, and, only where that DFA fails, the unanchored submatch engines
    /// over it. The call's arguments, with no captures and [#NO_MATCH_WORKSPACES], rule out the
    /// engine's other steps after the search: the reverse DFA for a match start, the
    /// tagged-alternation program, and the skip of the DFA after a matcher's DFA quit streak, a
    /// quit of its candidate cursor, or a failed count. The conditions of
    /// [#reachesInnerLiteralSearchFirst], which both routes require, rule out the rest: an end
    /// anchor, which sends the engine to the reverse DFA in place of the forward DFA, and an
    /// assertion the DFA cannot run, which skips the DFA. A change to that continuation must
    /// change these lines too; `TestInnerLiteralSearch` checks each route against the engine's own
    /// find on lines whose search falls back, where the forward DFA answers and where it quits, in
    /// `testFindsGatedOneByteLiteralFirst` and
    /// `testRequiredLiteralHandoffFindAgreesWithOrdinaryEngine`, and
    /// `TestPositionNfa.testInnerLiteralFirstLineSearchesSkipDfaAfterSlowResets` checks that the
    /// first route's boolean searches skip a thrashing DFA.
    private boolean findAfterInnerLiteralFallback(Slice input)
    {
        int length = input.length();
        Prog.MatchKind matchKind = longestMatch ? Prog.MatchKind.LONGEST_MATCH : Prog.MatchKind.FIRST_MATCH;
        long forward = Dfa.searchJudgingSlowResets(partialProg, input, 0, length, 0, length, matchKind);
        if (forward == Dfa.BOOLEAN_SEARCH_SKIPPED) {
            return searchOnPositionNfa(partialProg, input, 0, length, false, matchKind, null, NO_MATCH_WORKSPACES);
        }
        if (forward != Dfa.SEARCH_FAILED) {
            return forward != Dfa.SEARCH_NO_MATCH;
        }
        return runSubmatchEngine(partialProg, input, 0, length, false, matchKind, null, NO_MATCH_WORKSPACES);
    }

    /// Whether the ordinary engine's full-Slice boolean find, which, for a pattern on the
    /// literal-neighbor plan, runs only when that matcher leaves the find undecided,
    /// [runs the inner literal search first][#findOnInnerLiteralSearch]: the pattern's search is a
    /// gated one-byte search that
    /// [checks short windows][InnerLiteralSearch#checksShortWindows], and the ordinary engine's
    /// find of `partialProg` over a whole Slice
    /// [reaches that search first][#reachesInnerLiteralSearchFirst] on every input. The
    /// constructor also requires that the pattern keep no required bytes, which that find would
    /// check first. The search runs at every window length, since it checks short windows, and the
    /// length reject goes first in the engine and in [#findOnInnerLiteralSearch].
    private static boolean runsInnerLiteralSearchFirst(BooleanPlans booleanPlans, byte[] requiredPrefix, Prog partialProg)
    {
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        return innerLiteralSearch != null && innerLiteralSearch.checksShortWindows() && reachesInnerLiteralSearchFirst(booleanPlans, requiredPrefix, partialProg);
    }

    /// Whether full-Slice boolean find of a long window [hands the required literal's first
    /// hit][#findFromRequiredLiteral] to the inner literal search: the ordinary engine's find of
    /// `partialProg` over a whole Slice [reaches that search first][#reachesInnerLiteralSearchFirst],
    /// the search runs on every window of [RequiredBytes#SHORT_WINDOW_LIMIT] bytes or more, and
    /// `preferred`, the required bytes the pattern would keep,
    /// [admit the handoff][RequiredBytes#admitsInnerLiteralHandoff] to that search.
    private static boolean handsOffRequiredLiteral(BooleanPlans booleanPlans, byte[] requiredPrefix, Prog partialProg, RequiredBytes preferred)
    {
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        return innerLiteralSearch != null &&
                booleanPlans.innerLiteralSearchMinimum() <= RequiredBytes.SHORT_WINDOW_LIMIT &&
                reachesInnerLiteralSearchFirst(booleanPlans, requiredPrefix, partialProg) &&
                preferred.admitsInnerLiteralHandoff(innerLiteralSearch);
    }

    /// Whether the ordinary engine's find of `partialProg` over a whole Slice, which
    /// [#findOnEngine] calls with [#NO_MATCH_WORKSPACES], reaches the pattern's inner literal search
    /// after only its argument checks and length reject on every window the search runs on, and
    /// returns the search's no-match or span unchanged. The conditions keep every step of the
    /// engine, and of [#findOnEngine] after its required-bytes checks, from deciding ahead of the
    /// search:
    /// - no lowered boolean program, which findOnEngine would search in place of `partialProg`,
    ///   the program whose search is the inner literal search;
    /// - no disjoint suffix-repeat, whole-input capture, or word-run matcher, which answers first;
    /// - no required prefix, which the engine matches first and which anchors the rest;
    /// - no start anchor, with which the engine takes the anchored route, which never runs the
    ///   search;
    /// - no end anchor, which decides nothing ahead of the search, but after a fallback sends the
    ///   engine to the reverse DFA, which [#findAfterInnerLiteralFallback] does not run, and which
    ///   the search's analysis never admits, so that this rule does not depend on that analysis;
    /// - no assertion the DFA cannot run, since a search that skips the DFA skips this one too;
    /// - no empty match, so the nullable-start shortcut findOnEngine takes first never answers.
    ///
    /// The engine skips the DFA, and the search with it, after a streak of DFA quits or a quit of
    /// the candidate cursor, but only a matcher's workspaces hold either, so a call with
    /// [#NO_MATCH_WORKSPACES] never skips it. The inner literal search's own analysis already
    /// rejects most of these programs; each condition is tested here so that this rule does not
    /// depend on it.
    private static boolean reachesInnerLiteralSearchFirst(BooleanPlans booleanPlans, byte[] requiredPrefix, Prog partialProg)
    {
        return booleanPlans.loweredProgram() == null &&
                !keepsPreEngineMatcher(booleanPlans) &&
                requiredPrefix == null &&
                !partialProg.anchorStart() &&
                !partialProg.anchorEnd() &&
                !partialProg.hasDfaUnsupportedAssertions() &&
                !partialProg.canMatchEmpty();
    }

    private static boolean keepsPreEngineMatcher(BooleanPlans booleanPlans)
    {
        return booleanPlans.disjointSuffixRepeatMatcher() != null || booleanPlans.wholeInputCapturePlan() != null || booleanPlans.wordRunMatcher() != null;
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
    /// of the find plan's kind, the ordered-literal matcher, or the literal-neighbor matcher. The
    /// switch is this route's own, so the kinds of a pattern's `matches` and `lookingAt` stay out
    /// of its profile.
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
            case ORDERED_LITERALS -> booleanPlans.orderedLiteralMatcher().matches(input, 0, input.length());
            case LITERAL_NEIGHBORS -> findOnLiteralNeighbors(input);
            case GENERAL, LITERAL_SEARCH, NULLABLE_START, LOWERED_PROGRAM -> throw new IllegalStateException("not a literal find plan: " + booleanPlans.find());
        };
    }

    /// The full-Slice find of the [LITERAL_NEIGHBORS][BooleanPlanKind#LITERAL_NEIGHBORS] plan: the
    /// literal-neighbor matcher, or the ordinary engine where a byte beside a hit leaves the
    /// matcher undecided, or the density sample finds a one-byte literal dense. A find of at
    /// least the required bytes' [window limit][RequiredBytes#windowLimit()] whose required
    /// literal [hands off][#findFromRequiredLiteral] to the inner literal search takes the
    /// ordinary engine's route, that handoff, without running the matcher: the handoff came first,
    /// and which of the two is faster on such a find is unmeasured.
    private boolean findOnLiteralNeighbors(Slice input)
    {
        RequiredBytes bytes = requiredBytes;
        if (bytes != null && input.length() >= bytes.windowLimit() && bytes.handsOffToInnerLiteralSearch()) {
            return findOnEngine(input);
        }
        int decision = booleanPlans.literalNeighborMatcher().find(input);
        if (decision == LiteralNeighborMatcher.UNDECIDED) {
            return findOnEngine(input);
        }
        return decision == LiteralNeighborMatcher.MATCH;
    }

    /// The full-Slice find of a literal alternation. A short alternation searches the
    /// literal-alternation span matcher, and a long input whose filter stops are dense from its
    /// start runs the ordinary engine, whose DFA reads each byte once, as it would without the
    /// matcher. A decline reuses the compiled program and may build the DFA within what remains
    /// of the budget, which the matcher's size has already reduced. An alternation of more
    /// literals takes this shape only where the DFA cannot initialize, and searches the literal
    /// set.
    final boolean findOnLiteralAlternation(Slice input)
    {
        requireNonNull(input, "input is null");
        LiteralAlternationSpanMatcher matcher = booleanPlans.literalAlternationMatcher();
        if (matcher == null) {
            return booleanPlans.literalSet().findSpan(input, 0, input.length()) >= 0;
        }
        long span = matcher.findSpanOrDecline(input);
        if (span == LiteralAlternationSpanMatcher.DECLINED) {
            return findOnForwardProgram(input);
        }
        return span >= 0;
    }

    /// The full-Slice find of the fixed-width byte span matcher. A search that bails on dense
    /// candidates runs again from the input start on the ordinary engine, the find of the pattern
    /// without the matcher.
    final boolean findOnFixedWidth(Slice input)
    {
        requireNonNull(input, "input is null");
        long span = booleanPlans.fixedWidthByteMatcher().findSpanOrBail(input, 0, input.length());
        if (span == FixedWidthByteSpanMatcher.BAILED) {
            return findOnEngine(input);
        }
        return span >= 0;
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
        return (byteRunMatcher != null ? byteRunMatcher : singleByteMatcher).find(input, 0) >= 0;
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
    /// or not, as the plan's facts pick, after rejecting a context too long to hold a match of a
    /// pattern anchored at both ends. Like a failed executor, the rejection clears the groups.
    /// Each executor is too large for C2 to inline, so this selector keeps their calls out of the
    /// routes that use it.
    private boolean matchStartAnchoredScanPlan(ScanPlan plan, Slice text, int contextStart, int contextEnd, boolean fullMatch, int[] groups)
    {
        if (contextEnd - contextStart > maximumSpanLength) {
            if (groups != null) {
                Arrays.fill(groups, -1);
            }
            return false;
        }
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
            case GENERAL, LITERAL_SEARCH, NULLABLE_START, ORDERED_LITERALS, LITERAL_NEIGHBORS -> throw new IllegalStateException("plan cannot be executed directly: " + kind);
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
            case GENERAL, LITERAL_SEARCH, NULLABLE_START, CONTAINS, EQUALS_FINAL_LINE, ENDS_WITH_FINAL_LINE, ORDERED_LITERALS, LITERAL_NEIGHBORS, LOWERED_PROGRAM -> throw new IllegalStateException("not a literal matches plan: " + kind);
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
            case GENERAL, LITERAL_SEARCH, NULLABLE_START, CONTAINS, ENDS_WITH, ENDS_WITH_FINAL_LINE, ORDERED_LITERALS, LITERAL_NEIGHBORS -> throw new IllegalStateException("not a literal lookingAt plan: " + kind);
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
        return new RegexpMatcher(this, input, singleByteMatcher);
    }

    /// Creates a reusable matcher that retains group zero and the requested prefix of explicit
    /// capturing groups. Use zero when only complete-match boundaries are required.
    public RegexpMatcher matcher(Slice input, int retainedCapturingGroupCount)
    {
        requireInput(input);
        return new RegexpMatcher(this, input, singleByteMatcher, retainedCapturingGroupCount);
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
    /// decision: a pattern with a scan plan never matches empty and has no single-byte table,
    /// candidate-start or group-zero cursor, direct lowered search, or lowered program, so no
    /// [RegexpMatcher#find()] check runs ahead of the plan. A Rust matcher over a partial region runs
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
    /// either. A Java matcher region whose $ reads a CR before it runs on the NFA instead.
    ScanPlan caseSensitiveScanPlan()
    {
        ScanPlan plan = booleanPlans.scanPlan();
        return plan != null && !plan.usesAsciiFoldedExecutor() ? plan : null;
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

    /// Whether a search of `[start, end)` tries the inner literal, or the window literal, first, so
    /// a find loop skips its cursors. It does not when the window is
    /// [too short][#searchesInnerLiteralWindow] for the search, or when its first candidate sits
    /// near the window start, where the ordinary engine falls back. This approximates the gate in
    /// matchOnOrdinaryEngine, where a search that tries the literal can still fall back. It and
    /// the dispatch helpers TestHotMethodSize lists, BooleanPlans.hasLiteralSearch included, stay
    /// within C2's 35-byte limit for inlining a method at any call site, so the window literal
    /// search changes nothing on a find loop's path that has neither. The search methods they
    /// call, such as WindowLiteralSearch.fallsBackNearStart, are not held to that limit.
    boolean searchesInnerLiteral(Slice text, int start, int end)
    {
        return searchesInnerLiteralWindow(end - start) && !literalSearchFallsBackNearStart(text, start, end);
    }

    /// Whether the search of a pattern with an inner or window literal search falls back because
    /// its first candidate sits near the start of `[start, end)`.
    private boolean literalSearchFallsBackNearStart(Slice text, int start, int end)
    {
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        return innerLiteralSearch != null ? innerLiteralSearch.fallsBackNearStart(text, start, end) : windowLiteralSearchFallsBackNearStart(text, start, end);
    }

    // Out of line, so that literalSearchFallsBackNearStart stays within the inlining limit.
    private boolean windowLiteralSearchFallsBackNearStart(Slice text, int start, int end)
    {
        return booleanPlans.windowLiteralSearch().fallsBackNearStart(text, start, end);
    }

    /// Whether the pattern has an inner or window literal search and a find's search window of
    /// `length` bytes is long enough to run it, by the one minimum the compile resolves: the window
    /// literal search's [WindowLiteralSearch#MINIMUM_SEARCH_BYTES], or for an inner literal
    /// search at least its literal kind's
    /// [InnerLiteralSearch#minimumSearchBytes], where the alternative is the ordinary engine, or
    /// [#MINIMUM_LONG_SEARCH_BYTES] where a shorter search runs the short-search fixed-width
    /// matcher instead. That matcher took 2.7 to 5.8 times the inner search's time on 64- to
    /// 127-byte counts of `[a-z]shing` on r9g and r8i, so a fixed-width pattern keeps the 64-byte
    /// handoff it had before the per-kind minimums; a fixed-width class pair is unmeasured. A
    /// search that [checks short windows][InnerLiteralSearch#checksShortWindows] runs at every
    /// length.
    boolean searchesInnerLiteralWindow(int length)
    {
        return length >= booleanPlans.innerLiteralSearchMinimum() && booleanPlans.hasLiteralSearch();
    }

    /// Whether the pattern has an inner or window literal search and a whole-text count of
    /// `length` bytes is long enough to run it, as [#searchesInnerLiteralWindow] decides for a
    /// find, by the one minimum the compile resolves: the window literal search's
    /// [WindowLiteralSearch#MINIMUM_SEARCH_BYTES], or for an inner literal search
    /// [InnerLiteralSearch#minimumCountBytes] in place of the find's minimum.
    private boolean searchesInnerLiteralCount(int length)
    {
        return length >= booleanPlans.innerLiteralCountMinimum() && booleanPlans.hasLiteralSearch();
    }

    Dfa.CandidateStartCursor createCandidateStartCursor()
    {
        if (excludesCandidateStartCursor(booleanPlans, usesCompactBoundedCharacterClassForDiagnostics(), longestMatch, requiredPrefix, partialProg)) {
            return null;
        }
        // Every match of a program with a transparent start literal starts at an occurrence of it,
        // so the cursor needs neither a variable length nor a program free of assertions. The DFA
        // must run the assertions; otherwise the group-zero NFA skips to the literal instead.
        // Where matches have a longest length and the cursor searches the literal itself, not a
        // folded start run, a matcher settles a candidate whose check quits.
        if (partialProg.transparentStartLiteral() != null) {
            return partialProg.hasDfaUnsupportedAssertions() ? null : Dfa.createLiteralCandidateStartCursor(partialProg, foldedStartRun, maximumMatchLength() >= 0);
        }
        if (MatchLength.fixed(matchLength) >= 0 || partialProg.hasEmptyWidthAssertions()) {
            return null;
        }
        // A folded run that every match starts with takes the place of a start-byte scan, but not
        // of the start literal filter, whose candidates the cursor verifies into both boundaries
        // of a match where the run's match needs a reverse pass for its start. The DFA is the one
        // the cursor would read, built here if it is not yet, so the choice is the same whenever
        // a matcher makes it.
        if (partialProg.prefixRunStartsEveryMatch()) {
            Dfa.DfaInstance dfa = partialProg.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
            if (dfa == null || dfa.startLiteralFilter() == null) {
                return null;
            }
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

    /// Returns the scan of the folded run behind the transparent start literal `literal` that the
    /// candidate cursor searches for in the literal's place. Returns `null` when no run qualifies.
    ///
    /// A run qualifies when its probe pair, as [ByteFrequencies#foldedProbePair] chooses it, is
    /// rarer than the literal's pair by the margin of [ByteFrequencies#isRarerByProbeMargin], and
    /// the rarest qualifying run is chosen, the earliest on ties. The literal's pair is the one
    /// that `literalSearch`, the literal's search, probes on long windows with vectors, as
    /// [Prog#findLiteral] pairs them: the adjacent pair of [Prog#adjacentPairOffset] when it finds
    /// one in a periodic literal, and otherwise, as for a literal of one repeated byte, whose
    /// period is 1, the byte at [Prog#pairOffset(byte\[\])] and the last byte. Shorter windows,
    /// and searches without vectors, probe the literal's first and last bytes instead; those
    /// differ only for a periodic literal or one that begins with a multibyte character. Both
    /// pairs are rated under the distribution that chooses folded pairs, an ASCII letter of the
    /// run counting both its cases and a byte of the literal counting only itself. See
    /// REGULATOR_DECISIONS.md, Folded Run Behind A Start Literal, for why the margin is needed.
    private static Prog.FoldedStartRun rarerFoldedStartRun(Regexp regexp, byte[] literal, LiteralSearchKernel literalSearch, CaseFolding caseFolding, ByteFrequencies byteFrequencies)
    {
        ByteFrequencies pairFrequencies = byteFrequencies.forFoldedPairs();
        int adjacentPairOffset = Prog.adjacentPairOffset(literal, literalSearch.smallestPeriod());
        int firstOffset = adjacentPairOffset >= 0 ? adjacentPairOffset : Prog.pairOffset(literal);
        int secondOffset = adjacentPairOffset >= 0 ? adjacentPairOffset + 1 : literal.length - 1;
        double literalProbability = pairFrequencies.probability(literal[firstOffset]) * pairFrequencies.probability(literal[secondOffset]);
        Regexp.FoldedRun best = null;
        double bestProbability = 0;
        for (Regexp.FoldedRun run : regexp.foldedRunsBehindStartLiteral(caseFolding, byteFrequencies)) {
            byte[] runBytes = run.bytes();
            double probability = pairFrequencies.foldedProbePairProbability(runBytes, pairFrequencies.foldedProbePair(runBytes, runBytes.length));
            if (ByteFrequencies.isRarerByProbeMargin(probability, literalProbability) && (best == null || probability < bestProbability)) {
                best = run;
                bestProbability = probability;
            }
        }
        return best == null ? null : Prog.FoldedStartRun.create(best.bytes(), best.minimumLead(), best.maximumLead(), byteFrequencies);
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
                booleanPlans.wordRunMatcher() != null ||
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
        requireNonNull(input, "text is null");
        // ScanKernelGenerator.definePatternCount emits this check for a generated class; keep in
        // step.
        int length = input.length();
        if (length < minimumSpanLength || length > maximumSpanLength) {
            return 0;
        }
        long optimizedCount = countMatches(input);
        if (optimizedCount >= 0) {
            return optimizedCount;
        }

        RegexpMatcher matcher = matcher(input, 0);
        if (optimizedCount == Dfa.COUNT_BAILED) {
            matcher.markSkipsForwardDfa();
        }
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /// Returns the number of matches; `-1` when only a matcher can count them; or
    /// [Dfa#COUNT_BAILED] when a DFA count gave up, or skipped the DFA after a streak of DFA counts
    /// that gave up, so the matcher that counts them should skip the forward DFA.
    long countMatches(Slice text)
    {
        return countOnEngine(text);
    }

    /// The count of the ordinary engine: the disjoint suffix-repeat matcher, the single attempt of
    /// a start-anchored pattern, the class counter, or the DFA count. Returns `-1` when only a
    /// matcher can count, and [Dfa#COUNT_BAILED] when the DFA count bailed.
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
            return countOnClassCounter(characterClassCounter, text);
        }

        return countDfaMatches(text);
    }

    /// The count of the class counter, or, for single members that the counter's sample finds
    /// sparse in `text`, the DFA count, which skips to their start bytes; a DFA count that bails
    /// counts on the counter.
    private long countOnClassCounter(BoundedCharacterClassCounter characterClassCounter, Slice text)
    {
        if (characterClassCounter.hasSparseSingleMembers(text)) {
            long count = countDfaMatches(text);
            if (count >= 0) {
                return count;
            }
        }
        return characterClassCounter.count(text);
    }

    /// The count of a literal-kernel pattern: on its dot-star literal matcher where it has one, and
    /// otherwise the ordinary count.
    final long countOnLiteralKernel(Slice text)
    {
        requireNonNull(text, "text is null");
        DotStarLiteralSpanMatcher dotStarLiteral = booleanPlans.dotStarLiteralMatcher();
        return dotStarLiteral != null ? dotStarLiteral.count(text) : countOnEngine(text);
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

    /// The count of a literal alternation. A short alternation counts on the literal-alternation
    /// span matcher, and a declined count runs the DFA count, the route the ordinary count takes
    /// for this pattern without the matcher: no disjoint-suffix or class-counter route can be
    /// built beside a literal alternation. An alternation of more literals counts on the literal
    /// set where `literalSetCount` says its first-match DFA cannot initialize, and otherwise
    /// takes the ordinary count.
    final long countOnLiteralAlternation(Slice text, boolean literalSetCount)
    {
        requireNonNull(text, "text is null");
        LiteralAlternationSpanMatcher matcher = booleanPlans.literalAlternationMatcher();
        if (matcher == null) {
            return literalSetCount ? booleanPlans.literalSet().count(text) : countOnEngine(text);
        }
        long count = matcher.countOrDecline(text);
        return count != LiteralAlternationSpanMatcher.DECLINED ? count : countDfaMatches(text);
    }

    /// The count of the fixed-width byte span matcher. A count that bails on dense candidates
    /// keeps its matches and counts the rest of the text from the end of the last one, or from
    /// the text start, on the ordinary count, the count of the pattern without the matcher, and
    /// where that count bails, this count bails. The pattern reads no context, so the matches
    /// after a match end are those of the text that follows it.
    final long countOnFixedWidth(Slice text)
    {
        requireNonNull(text, "text is null");
        long count = booleanPlans.fixedWidthByteMatcher().countOrBail(text);
        if (count >= 0) {
            return count;
        }
        int resume = FixedWidthByteSpanMatcher.resumeAfterBail(count);
        long rest = countOnEngine(text.slice(resume, text.length() - resume));
        return rest < 0 ? rest : FixedWidthByteSpanMatcher.countBeforeBail(count) + rest;
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
    /// where the pattern has one, and otherwise on the DFA, which also counts single members that
    /// the counter's sample finds sparse unless its count bails. No byte-table pattern reaches the
    /// ordinary count's other routes, as the constructor checks, and this route skips their
    /// branches.
    final long countOnByteTable(Slice text)
    {
        requireNonNull(text, "text is null");
        if (booleanPlans.byteRunMatcher() == null && text.length() <= MAX_DIRECT_BYTE_SCAN_BYTES) {
            return singleByteMatcher.count(text);
        }
        BoundedCharacterClassCounter characterClassCounter = boundedCharacterClassCounter();
        if (characterClassCounter != BoundedCharacterClassCounter.unsupported()) {
            return countOnClassCounter(characterClassCounter, text);
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

    /// Counts the matches of an unanchored pattern on the DFA, starting from the inner or window
    /// literal where one is required and the text is long enough, or on the short-search
    /// fixed-width matcher where the pattern has one and the text is too short for the inner
    /// literal search. A long text that lacks the pattern's long-window literal or one-of bytes
    /// counts none before any of them runs, and the literal search or the DFA counts a long text
    /// only up to the end [#longWindowSearchEnd] bounds it at; the text's length still picks the
    /// route. It is out of line so that the count route methods stay small enough for C2 to inline
    /// into their callers.
    private long countDfaMatches(Slice text)
    {
        int end = longWindowSearchEnd(text, 0, text.length());
        if (end < 0) {
            return 0;
        }
        if (searchesInnerLiteralCount(text.length())) {
            return countLiteralMatches(text, end);
        }
        // The short-search matcher counts only texts shorter than the inner literal search's count
        // minimum, at most MINIMUM_LONG_SEARCH_BYTES, which the search-end bound never shortens.
        // It exists only beside the inner literal search.
        FixedWidthByteSpanMatcher shortFixedWidthByteMatcher = booleanPlans.shortFixedWidthByteMatcher();
        if (shortFixedWidthByteMatcher != null) {
            return shortFixedWidthByteMatcher.count(text);
        }

        Prog.MatchKind matchKind = longestMatch ? Prog.MatchKind.LONGEST_MATCH : Prog.MatchKind.FIRST_MATCH;
        return Dfa.countMatches(partialProg, text, end, matchKind);
    }

    /// Counts the matches found through the pattern's inner or window literal in the first
    /// `length` bytes of `text`. It is out of line, which keeps the count dispatch,
    /// countDfaMatches, within its 84-byte cap.
    private long countLiteralMatches(Slice text, int length)
    {
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        return innerLiteralSearch != null ? countInnerLiteralMatches(innerLiteralSearch, text, length) : countWindowLiteralMatches(booleanPlans.windowLiteralSearch(), text, length);
    }

    /// Counts the matches found through the inner literal. Where the next candidate sits near the
    /// text start or the previous match end, or a search falls back for another reason, the
    /// ordinary count takes over from there and hands back at the first match end whose next
    /// candidate is not near. Its start-byte scan selection then holds for the whole dense run,
    /// instead of being made again for each match, and where it bails, this count bails. The
    /// ordinary counts share their start-byte selection and productivity checks, so one that
    /// starts within 64 KiB of an earlier sample does not repeat them. Every match holds the
    /// literal, so none is empty and each step advances. Only match ends are read, so where every
    /// match provably ends at its literal's end, the search confirms a candidate without finding
    /// its start or running the forward DFA. The count reads the first `length` bytes of `text`,
    /// which its searches and assertions see as the whole text, as the DFA count does for the
    /// search-end bound. countWindowLiteralMatches copies this loop; keep the two in step.
    private long countInnerLiteralMatches(InnerLiteralSearch innerLiteralSearch, Slice text, int length)
    {
        IntPredicate continueCounting = next -> innerLiteralSearch.fallsBackNearStart(text, next, length);
        // The first fallback with enough text left to sample allocates the shared sampling, and
        // later ones reuse it. Positions only advance, so a short row never allocates it.
        Dfa.CountByteScanSampling sampling = null;
        long count = 0;
        int position = 0;
        while (position < length) {
            long span = innerLiteralSearch.searchForCount(text, 0, length, position, length);
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
            long counted = Dfa.countMatches(partialProg, text, position, length, Prog.MatchKind.FIRST_MATCH, continueCounting, sampling);
            if (counted < 0) {
                return counted;
            }
            count += Dfa.countStopCount(counted);
            position = Dfa.countStopPosition(counted);
        }
        return count;
    }

    /// Counts the matches found through the window literal, handing over to the ordinary count
    /// and back as [#countInnerLiteralMatches] does, in the first `length` bytes of `text`, which
    /// its searches and assertions see as the whole text. Every match holds the literal, so none
    /// is empty and each step advances. It copies the loop of countInnerLiteralMatches; keep the
    /// two in step.
    private long countWindowLiteralMatches(WindowLiteralSearch windowLiteralSearch, Slice text, int length)
    {
        IntPredicate continueCounting = next -> windowLiteralSearch.fallsBackNearStart(text, next, length);
        // The first fallback with enough text left to sample allocates the shared sampling, and
        // later ones reuse it. Positions only advance, so a short row never allocates it.
        Dfa.CountByteScanSampling sampling = null;
        long count = 0;
        int position = 0;
        while (position < length) {
            long span = windowLiteralSearch.search(text, 0, length, position, length);
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
            long counted = Dfa.countMatches(partialProg, text, position, length, Prog.MatchKind.FIRST_MATCH, continueCounting, sampling);
            if (counted < 0) {
                return counted;
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

    /// Returns the single-byte table that this pattern's group-zero matchers and byte-table routes
    /// share, or `null` when the pattern has none.
    SingleByteMatcher sharedSingleByteMatcher()
    {
        return singleByteMatcher;
    }

    /// Returns whether this pattern has a single-byte table, which compilation builds.
    boolean isSingleByteMatcherComputed()
    {
        return singleByteMatcher != null;
    }

    private static boolean mayHaveSingleByteMatcher(byte[] requiredPrefix, Prog partialProg, int matchLength)
    {
        return requiredPrefix == null &&
                !partialProg.hasTextDependentAssertions() &&
                MatchLength.fixed(matchLength) == 1;
    }

    SingleByteRepeatMatcher createSingleByteRepeatMatcher()
    {
        return singleByteRepeat == null ? null : SingleByteRepeatMatcher.create(singleByteRepeat);
    }

    BoundedCharacterClassCounter createBoundedCharacterClassCounter()
    {
        if (usesCompactBoundedCharacterClassForDiagnostics()) {
            return boundedCharacterClassCounter;
        }
        // A class repeat or a single member that count takes. A class repeat's matcher counter,
        // which this pattern holds in place of its input, is the counter that input builds.
        if (classRepeat != null) {
            return BoundedCharacterClassCounter.create(classRepeat);
        }
        return hasClassRepeat ? matcherClassCounter : null;
    }

    boolean hasCharacterClassSpan()
    {
        return usesCompactBoundedCharacterClassForDiagnostics() || hasClassRepeat;
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
        // The one-shot unanchored searches come here: find and findInto of a range, findInto,
        // findResult, RE2's replaceFirst and extract, and each retry of a Rust search after an
        // empty match that splits a character. A pattern with a required literal never makes
        // such a retry, since none of its matches is empty. None of these callers has checked
        // for the required literal yet. A matcher's searches take matchRegionInto after their own
        // check, and RE2's replaceAll takes matchIntoAfterLoopCheck after its loop's check. A
        // caller's own loop of range finds checks on every call, without the back-off, since
        // each call is one search and the pattern keeps no state between them. The capture
        // buffer is checked first, as the engine checks it before it reads any input. An
        // unanchored search of a long window ends where longWindowSearchEnd bounds it, and the
        // context keeps the whole text.
        if (groups != null) {
            checkGroupsLength(groups);
        }
        int searchEnd = end;
        if (anchor == Anchor.UNANCHORED) {
            searchEnd = longWindowSearchEnd(text, start, end);
            if (searchEnd < 0) {
                return failWithoutMatch(groups);
            }
        }
        return matchRegionInto(
                text,
                0,
                text.length(),
                start,
                searchEnd,
                anchor,
                groups,
                fixedMatchWorkspaces(onePassWorkspace, bitStateWorkspace, nfaWorkspace),
                true);
    }

    /// For a library loop that runs the one-shot unanchored search once per match, from the end of
    /// the last, as RE2's replaceAll does: checks `[start, end)` of `text` for the long-window
    /// literal or one-of bytes under a matcher's back-off, and returns the loop's count of scans
    /// in a row that found them near their start after this one, or -1 when the window lacks them
    /// and so holds no match. `nearScans` is that count before this search, 0 for the loop's
    /// first. Once it reaches [RegexpMatcher#NEAR_REQUIRED_LITERAL_SCAN_LIMIT], the loop's
    /// searches skip the check, as a matcher's finds do until a reset. Each match consumes the
    /// literal or one of the bytes, so the next search starts past the occurrence this one found,
    /// and no occurrence is kept. A pattern with the search-end bound whose suffix holds the literal makes no check:
    /// the loop ends its searches at [#loopSearchEnd], as that bound stands in for the check of a
    /// one-shot search. The loop then searches with [#matchIntoAfterLoopCheck].
    final int nearRequiredLiteralScansInLoop(Slice text, int start, int end, int nearScans)
    {
        RequiredBytes literal = requiredBytes;
        if (literal == null || !literal.searchesLongWindows() || (literal.boundsSearchEnd() && literal.suffixHoldsLiteral()) || end - start < RequiredBytes.SHORT_WINDOW_LIMIT || nearScans >= RegexpMatcher.NEAR_REQUIRED_LITERAL_SCAN_LIMIT) {
            return nearScans;
        }
        int occurrence = literal.indexIn(text, start, end);
        if (occurrence < 0) {
            return -1;
        }
        return occurrence - start < RegexpMatcher.NEAR_REQUIRED_LITERAL_BYTES ? nearScans + 1 : 0;
    }

    /// The end that every search of a library loop over all of `text`, such as RE2's replaceAll,
    /// needs, or -1 when the text holds no match: for a pattern with the search-end bound, where
    /// [#longWindowSearchEnd] bounds the whole text, before which every match lies, and otherwise
    /// the text's end. The loop checks each search with [#nearRequiredLiteralScansInLoop], except
    /// where that bound stands in for the check.
    final int loopSearchEnd(Slice text)
    {
        RequiredBytes literal = requiredBytes;
        return literal != null && literal.boundsSearchEnd() ? longWindowSearchEnd(text, 0, text.length()) : text.length();
    }

    /// The unanchored [#matchInto] of a range without its long-window check, for a library loop
    /// that has made the check with [#nearRequiredLiteralScansInLoop]. The search it runs checks
    /// the text and the capture buffer, so only the range is checked here.
    final boolean matchIntoAfterLoopCheck(Slice text, int start, int end, int[] groups)
    {
        validateRange(text, start, end);
        return matchRegionInto(
                text,
                0,
                text.length(),
                start,
                end,
                Anchor.UNANCHORED,
                groups,
                NO_MATCH_WORKSPACES,
                true);
    }

    /// Leaves caller groups, already checked, as a failed [#matchRegionInto] that clears them
    /// does: unset.
    private static boolean failWithoutMatch(int[] groups)
    {
        if (groups != null) {
            Arrays.fill(groups, -1);
        }
        return false;
    }

    private static void checkGroupsLength(int[] groups)
    {
        if ((groups.length % 2) != 0) {
            throw new IllegalArgumentException("groups length must be even: " + groups.length);
        }
        if (groups.length < 2) {
            throw new IllegalArgumentException("groups must include group 0 start/end");
        }
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
            checkGroupsLength(groups);
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

    /// Returns the most bytes a match of the whole expression spans, or -1 when matches have no
    /// longest length. Unlike [#minimumMatchLength()], which is the compiled suffix's, it reads
    /// the whole expression. The candidate cursor, which exists only without a required prefix,
    /// settles a candidate whose check quit over this many bytes; see [#matchQuitCandidateInto].
    int maximumMatchLength()
    {
        ExpressionAnalysis.Length length = expressionAnalysis.length();
        return length.canMatch() && !length.isUnbounded() ? length.maximum() : -1;
    }

    /// Finds group zero of the leftmost-first match that starts at `start`, for a matcher whose
    /// candidate cursor's DFA quit checking the candidate there, with groups relative to
    /// `contextStart`. The search skips the DFA, which would quit again, and runs on an engine
    /// that decodes the text: OnePass, BitState, or the NFA, as [#runSubmatchEngine] picks it.
    /// `end` is the nearer of the longest match's length from `start` and the context end, so no
    /// match starting at `start` ends past it; the assertions still read the whole context.
    boolean matchQuitCandidateInto(Slice text, int contextStart, int contextEnd, int start, int end, int[] groups, RegexpMatcher workspaceOwner)
    {
        Slice context = logicalContext(text, contextStart, contextEnd);
        int relativeStart = start - contextStart;
        boolean matched = runSubmatchEngine(partialProg, context, relativeStart, end - contextStart, true, Prog.MatchKind.FIRST_MATCH, groups, workspaceOwner);
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
    /// engine. The class-run and scan-plan shapes override it, as do Trino's fixed-width shape,
    /// which runs the matcher where the pattern compiled no program, and the literal-alternation
    /// class of a pattern that compiled no program, which runs the literal set.
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

    /// The [#matchInternal] route of a Trino fixed-width pattern. Where `fixedWidthWithoutProgram`
    /// is set, the pattern compiled no program, and every call of its placeholder forward program
    /// runs the fixed-width matcher, which reports group zero only, as a pattern without captures
    /// needs. Only a matcher that never bails on dense candidates allows compiling no program,
    /// since a matcher that can bail keeps its program to hand the search to. Every other call
    /// runs the ordinary engine.
    final boolean matchInternalOnFixedWidth(Prog prog, Slice text, int contextStart, int contextEnd, int start, int end, Anchor anchor, int[] groupOffsets, MatchWorkspaces workspaces, boolean fixedWidthWithoutProgram)
    {
        if (fixedWidthWithoutProgram && prog == partialProg) {
            return matchFixedWidthByteSpan(text, contextStart, start, end, anchor, groupOffsets);
        }
        return matchOnOrdinaryEngine(prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces);
    }

    /// The [#matchInternal] route of a literal-alternation pattern that compiled no program: every
    /// call of its placeholder forward program runs the literal set and `extendingLiterals`, which
    /// hold the literals the set omits. They report group zero only, as a pattern without
    /// captures needs. Every other call runs the ordinary engine.
    final boolean matchInternalOnLiteralSet(Prog prog, Slice text, int contextStart, int contextEnd, int start, int end, Anchor anchor, int[] groupOffsets, MatchWorkspaces workspaces, LiteralSetSpanMatcher.ExtendingLiterals extendingLiterals)
    {
        if (prog == partialProg) {
            return matchLiteralSet(text, contextStart, start, end, anchor, groupOffsets, extendingLiterals);
        }
        return matchOnOrdinaryEngine(prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces);
    }

    /// Matches `[start, end)` of `text` on the literal set, with groups relative to
    /// `contextStart`. The literals have no assertion, so nothing outside the region reaches the
    /// result. An unanchored call is the set's leftmost-first search, a start-anchored call the
    /// walk from the root that keeps the highest-priority literal starting at `start`, and a full
    /// match takes any literal spanning the region, which the trie holds or which extends another.
    private boolean matchLiteralSet(Slice text, int contextStart, int start, int end, Anchor anchor, int[] groups, LiteralSetSpanMatcher.ExtendingLiterals extendingLiterals)
    {
        LiteralSetSpanMatcher literalSet = booleanPlans.literalSet();
        int matchStart = start;
        int matchEnd = end;
        if (anchor == Anchor.UNANCHORED) {
            long span = literalSet.findSpan(text, start, end);
            if (span < 0) {
                return false;
            }
            matchStart = (int) (span >>> 32);
            matchEnd = (int) span;
        }
        else if (anchor == Anchor.ANCHOR_START) {
            matchEnd = literalSet.findPrefixEnd(text, start, end);
            if (matchEnd < 0) {
                return false;
            }
        }
        else if (literalSet.findPrefixEnd(text, start, end) != end && !extendingLiterals.contains(text, start, end)) {
            return false;
        }
        if (groups != null) {
            groups[0] = matchStart - contextStart;
            groups[1] = matchEnd - contextStart;
        }
        return true;
    }

    /// Matches `[start, end)` of `text` on the fixed-width matcher, with groups relative to
    /// `contextStart`. The pattern has no assertion, so nothing outside the region reaches the
    /// result. One start has at most one match, which is then the leftmost, so an anchored call
    /// searches no further past `start` than the matcher's upper bound on the bytes a match spans
    /// and checks where the match it finds starts.
    private boolean matchFixedWidthByteSpan(Slice text, int contextStart, int start, int end, Anchor anchor, int[] groups)
    {
        FixedWidthByteSpanMatcher matcher = booleanPlans.fixedWidthByteMatcher();
        int searchEnd = end;
        if (anchor != Anchor.UNANCHORED && end - start > matcher.maximumMatchBytes()) {
            if (anchor == Anchor.ANCHOR_BOTH) {
                return false;
            }
            searchEnd = start + matcher.maximumMatchBytes();
        }
        long span = matcher.findSpanOrBail(text, start, searchEnd);
        if (span < 0) {
            return false;
        }

        int matchStart = (int) (span >>> 32);
        int matchEnd = (int) span;
        if (anchor != Anchor.UNANCHORED && matchStart != start) {
            return false;
        }
        if (anchor == Anchor.ANCHOR_BOTH && matchEnd != end) {
            return false;
        }
        if (groups != null) {
            groups[0] = matchStart - contextStart;
            groups[1] = matchEnd - contextStart;
        }
        return true;
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
    /// matchers, then the DFA, OnePass, BitState, and position automaton or NFA cascade. Every
    /// route that leaves a plan or kernel for the ordinary engine calls this, as does the
    /// `matchInternal` that ScanKernelGenerator emits for a call off the plan.
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
        // Only a pattern anchored at both ends has a span bound, and it is checked only on an
        // anchored search: where the program records the start anchor, or a stripped prefix or the
        // caller anchors the search. An alternation whose branches each carry the anchor, such as
        // (^aa$)|(^aaa$), leaves the program unanchored, so its search falls through to the engine
        // with the same answer; count and matcher finds reject it earlier. The span's length
        // counts a stripped prefix.
        if (anchored && searchLength + strippedPrefixLength > maximumSpanLength) {
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

        // A bounded BitState search can extract all groups in one pass. For eligible windows, this
        // avoids forward and reverse DFA boundary searches followed by an anchored capture pass.
        if (anchorMode == Anchor.UNANCHORED &&
                mutableGroupOffsets != null &&
                hasBitStateWorkspace(workspaces) &&
                canUseDirectBitStateCapture(prog, searchLength, captureCount)) {
            // This route skips the inner literal search, whose minimum window is shorter than any
            // window here, so it scans for the search's literal instead: a pattern with a search
            // may have no long-window check. One that keeps the check, since only the class-run
            // proof gives it the search, may read the bytes before the literal twice, which the
            // BitState search after it outweighs.
            InnerLiteralSearch innerLiteralSearch = prog == partialProg ? booleanPlans.innerLiteralSearch() : null;
            if (innerLiteralSearch != null && innerLiteralSearch.windowLacksLiteral(text, start, end)) {
                return false;
            }
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
        // search with an inner or window literal search runs it first and falls back to the
        // two-phase DFA. A matcher counting after its count's DFA bailed skips both phases and the
        // literal search for group zero of partialProg, the program its count ran on.
        boolean dfaSearchSkippedOrFailed = prog.hasDfaUnsupportedAssertions() || (captureCount == 1 && prog == partialProg && skipsForwardDfa(workspaces));
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
            // A window shorter than the search's minimum skips the search, since there its literal
            // scan cost more than the DFA, unless the search only checks such a window for a hit
            // that passes its byte gates. Where the pattern keeps required bytes, a matcher's find
            // or a full-Slice boolean find shorter than their window limit has already been checked
            // for them before it gets here; a ranged find and findInto have not. That limit is 256
            // bytes for a literal and for a single required byte, and for a one-of set of two or
            // three distinct bytes this search's minimum where it is lower. A full-Slice boolean
            // find that runs the search itself, in findOnInnerLiteralSearch or, on a long window,
            // findFromRequiredLiteral, never comes here: after a fallback,
            // findAfterInnerLiteralFallback makes the steps below. For the inner literal search the
            // compile resolves the window decision to one minimum, so a window too short for it
            // costs one compare. A pattern has at most one of the two literal searches. For the
            // window literal search this branch tests the program and the search's own minimum
            // before calling it, and the search falls back as the inner literal search does. A
            // boolean find, which reports no captures, needs only whether a match exists, so it
            // runs the window search's earliest-match tries.
            InnerLiteralSearch innerLiteralSearch = prog == partialProg && searchLength >= booleanPlans.innerLiteralSearchMinimum() ? booleanPlans.innerLiteralSearch() : null;
            long literalSpan = InnerLiteralSearch.FALLBACK;
            if (!dfaSearchSkippedOrFailed) {
                if (innerLiteralSearch != null) {
                    literalSpan = innerLiteralSearch.search(text, contextStart, contextEnd, start, end);
                }
                else if (prog == partialProg && searchLength >= WindowLiteralSearch.MINIMUM_SEARCH_BYTES) {
                    WindowLiteralSearch windowLiteralSearch = booleanPlans.windowLiteralSearch();
                    // An open boolean-search skip takes this find in the forward step below; peek
                    // only: a search judges only its own resets, and window-try resets feed none.
                    if (windowLiteralSearch != null && !(captureCount == 0 && Dfa.skipsBooleanSearchDfa(prog))) {
                        literalSpan = captureCount == 0
                                ? windowLiteralSearch.searchExists(text, contextStart, contextEnd, start, end)
                                : windowLiteralSearch.search(text, contextStart, contextEnd, start, end);
                    }
                }
            }
            if (literalSpan == Dfa.SEARCH_NO_MATCH) {
                return false;
            }
            if (literalSpan >= 0) {
                if (captureCount == 0) {
                    return true;
                }
                matchStart = (int) (literalSpan >>> 32) - start;
                matchEnd = (int) literalSpan - start;
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
                    // A boolean search judges the DFA's resets across boolean searches, and after
                    // a streak of slow resets skips it, answering from the position automaton,
                    // which reads no context, so it searches only [start, end).
                    // findAfterInnerLiteralFallback makes this step and the submatch engines' for a
                    // full-Slice find whose own search falls back; keep in step.
                    long forward = captureCount == 0
                            ? Dfa.searchJudgingSlowResets(prog, text, contextStart, contextEnd, start, end, matchKind)
                            : Dfa.search(prog, text, contextStart, contextEnd, start, end, false, matchKind, true);
                    if (forward == Dfa.BOOLEAN_SEARCH_SKIPPED) {
                        return searchOnPositionNfa(prog, text, start, end, false, matchKind, null, workspaces);
                    }

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
            // A search that skips the DFA skips the inner literal search too, so it scans for the
            // search's literal instead: a pattern with a search may have no long-window check.
            // One that keeps the check, since only the class-run proof gives it the search, may
            // read the bytes before the literal twice, which the submatch engines after it
            // outweigh.
            else if (innerLiteralSearch != null && innerLiteralSearch.windowLacksLiteral(text, start, end)) {
                return false;
            }
        }

        // ===== Phase 3: Anchored Search Optimization =====
        // For anchored patterns, decide whether to use DFA or skip directly to submatch engines.
        else if (anchorMode == Anchor.ANCHOR_START || anchorMode == Anchor.ANCHOR_BOTH) {
            boolean canOnePass = prog.isOnePass() && prog.supportsOnePassCaptureSlots(captureCount * 2);
            boolean canBitState = prog.canBitState();
            int bitStateMaxSize = prog.anchoredBitStateTextMaxSize();

            // Skip DFA if we have fast engines and need submatches. takesAnchoredSubmatchRoute and
            // matchAnchoredSubmatch make the same engine choice for a start-anchored find with
            // groups; keep in step.
            boolean skipDfa = dfaSearchSkippedOrFailed;
            if (canOnePass && searchLength <= ONE_PASS_SUBMATCH_MAXIMUM_LENGTH && (captureCount > 1 || searchLength <= 16)) {
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
        // Extract capturing group positions using OnePass → BitState → position automaton or
        // NFA cascade.

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
                // A caller array that holds only group zero needs no clearing; both slots are written below.
                if (groupOffsets.length > 2) {
                    Arrays.fill(groupOffsets, -1);
                }
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
        return new BooleanPlans(literal, find, lookingAt, matches, null, null, null, null, null, null, null, null, null, null, Short.MAX_VALUE, Short.MAX_VALUE, null, null, null, null, null, null, null, null);
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
            // Scans for a contained literal of two or more bytes by its first and last bytes
            // together, for boolean contains and dot-star spans, when it fits beside the forward
            // DFAs; null leaves them on Slice.indexOf. Exact-literal spans never use it.
            LiteralSearchKernel literalSearch,
            // Searches an alternation of more than LiteralAlternationSpanMatcher.MAXIMUM_ALTERNATIVES
            // literals for the RE2, Java, and Rust RegexpMatcher.find() group-zero spans, and for
            // full-Slice boolean find and count where their forward DFA cannot initialize; null
            // leaves them on the program.
            LiteralSetSpanMatcher literalSet,
            InnerLiteralSearch innerLiteralSearch,
            // The fewest bytes of a find's search window, and of a whole-text count's text, that run
            // innerLiteralSearch, or else windowLiteralSearch, resolved here from them and
            // shortFixedWidthByteMatcher, so that a find or count decides with one load and
            // compare; see innerLiteralSearchMinimum below. Shorts, so that both fit where one int
            // fills the layout's padding.
            short innerLiteralSearchMinimum,
            short innerLiteralCountMinimum,
            // Searches the literal every match holds within a bounded window of its start, for a
            // pattern without an inner literal search; null for every pattern with one.
            WindowLiteralSearch windowLiteralSearch,
            // Answer full-Slice boolean find and count and the group-zero RegexpMatcher.find()
            // spans of a pattern that would otherwise run the general engine; null leaves them on
            // it. Trino's fixed-width subtype answers its other operations from the same
            // fixed-width matcher.
            LiteralAlternationSpanMatcher literalAlternationMatcher,
            FixedWidthByteSpanMatcher fixedWidthByteMatcher,
            // The fixed-width matcher of a pattern whose find keeps the inner literal search. The
            // matcher answers full-Slice count and the group-zero RegexpMatcher.find() spans only
            // on searches too short for that search; null leaves them on the general engine.
            FixedWidthByteSpanMatcher shortFixedWidthByteMatcher,
            // Answers full-Slice boolean find of a one-byte member run on inputs of at most 64
            // bytes; null leaves it on the general engine.
            SingleByteMatcher byteRunMatcher,
            // Finds the spans of a `.*literal.*` pattern, whose dots match any character but a line
            // feed, for the count of the literal-kernel shape, every language's group-zero
            // RegexpMatcher.find(), and Trino's span operations; null leaves them on the program.
            DotStarLiteralSpanMatcher dotStarLiteralMatcher,
            // Answers full-Slice boolean find of the ORDERED_LITERALS plan; null for every other
            // plan.
            OrderedLiteralMatcher orderedLiteralMatcher,
            // Decides full-Slice boolean find of the LITERAL_NEIGHBORS plan, or leaves it to the
            // ordinary engine, which also takes a long find whose required literal hands off to
            // the inner literal search; null for every other plan.
            LiteralNeighborMatcher literalNeighborMatcher)
    {
        private BooleanPlans withScanPlan(ScanPlan scanPlan)
        {
            ScanPlan scanBooleanPlan = scanPlan == null ? null : scanPlan.booleanPlan();
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withTaggedAlternationProgram(TaggedAlternationProgram taggedAlternationProgram)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withWordRunMatcher(WordRunMatcher wordRunMatcher)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withWholeInputCapturePlan(WholeInputCapturePlan wholeInputCapturePlan)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withDisjointSuffixRepeatMatcher(DisjointSuffixRepeatMatcher disjointSuffixRepeatMatcher)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withLiteralSearch(LiteralSearchKernel literalSearch)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withLiteralSet(LiteralSetSpanMatcher literalSet)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withInnerLiteralSearch(InnerLiteralSearch innerLiteralSearch)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum(innerLiteralSearch, windowLiteralSearch, shortFixedWidthByteMatcher), innerLiteralCountMinimum(innerLiteralSearch, windowLiteralSearch, shortFixedWidthByteMatcher), windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withWindowLiteralSearch(WindowLiteralSearch windowLiteralSearch)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum(innerLiteralSearch, windowLiteralSearch, shortFixedWidthByteMatcher), innerLiteralCountMinimum(innerLiteralSearch, windowLiteralSearch, shortFixedWidthByteMatcher), windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withLiteralAlternationMatcher(LiteralAlternationSpanMatcher literalAlternationMatcher)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withFixedWidthByteMatcher(FixedWidthByteSpanMatcher fixedWidthByteMatcher)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withShortFixedWidthByteMatcher(FixedWidthByteSpanMatcher shortFixedWidthByteMatcher)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum(innerLiteralSearch, windowLiteralSearch, shortFixedWidthByteMatcher), innerLiteralCountMinimum(innerLiteralSearch, windowLiteralSearch, shortFixedWidthByteMatcher), windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withByteRunMatcher(SingleByteMatcher byteRunMatcher)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        private BooleanPlans withDotStarLiteralMatcher(DotStarLiteralSpanMatcher dotStarLiteralMatcher)
        {
            return new BooleanPlans(literal, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        // The minimum CompiledPattern.searchesInnerLiteralWindow applies: none for a search that
        // checks short windows, MINIMUM_LONG_SEARCH_BYTES beside a short-search fixed-width
        // matcher, and otherwise the literal kind's minimum. Every kind's minimum is at least
        // MINIMUM_LONG_SEARCH_BYTES, so beside that matcher the search runs from there whatever the
        // kind, as when the matcher was tested only below the kind's minimum. A pattern with a
        // window literal search instead, which has no inner literal search, takes that search's
        // minimum. Without either it is Short.MAX_VALUE, and a longer window finds no search to
        // run.
        private static short innerLiteralSearchMinimum(InnerLiteralSearch innerLiteralSearch, WindowLiteralSearch windowLiteralSearch, FixedWidthByteSpanMatcher shortFixedWidthByteMatcher)
        {
            if (innerLiteralSearch == null) {
                return windowLiteralSearch != null ? WindowLiteralSearch.MINIMUM_SEARCH_BYTES : Short.MAX_VALUE;
            }
            if (innerLiteralSearch.checksShortWindows()) {
                return 0;
            }
            return (short) (shortFixedWidthByteMatcher != null ? MINIMUM_LONG_SEARCH_BYTES : innerLiteralSearch.minimumSearchBytes());
        }

        // The minimum CompiledPattern.searchesInnerLiteralCount applies, as for a find, but with the
        // literal kind's count minimum: MINIMUM_LONG_SEARCH_BYTES beside a short-search fixed-width
        // matcher, and otherwise that count minimum. Without an inner literal search it is the
        // window literal search's minimum or Short.MAX_VALUE.
        private static short innerLiteralCountMinimum(InnerLiteralSearch innerLiteralSearch, WindowLiteralSearch windowLiteralSearch, FixedWidthByteSpanMatcher shortFixedWidthByteMatcher)
        {
            if (innerLiteralSearch == null) {
                return windowLiteralSearch != null ? WindowLiteralSearch.MINIMUM_SEARCH_BYTES : Short.MAX_VALUE;
            }
            return (short) (shortFixedWidthByteMatcher != null ? MINIMUM_LONG_SEARCH_BYTES : innerLiteralSearch.minimumCountBytes());
        }

        /// Whether the pattern has an inner or a window literal search; it has at most one.
        private boolean hasLiteralSearch()
        {
            return innerLiteralSearch != null || windowLiteralSearch != null;
        }

        /// Returns these plans with find on `orderedLiteralMatcher`.
        private BooleanPlans withOrderedLiteralMatcher(OrderedLiteralMatcher orderedLiteralMatcher)
        {
            return new BooleanPlans(literal, BooleanPlanKind.ORDERED_LITERALS, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
        }

        /// Returns these plans with find on `literalNeighborMatcher`.
        private BooleanPlans withLiteralNeighborMatcher(LiteralNeighborMatcher literalNeighborMatcher)
        {
            return new BooleanPlans(literal, BooleanPlanKind.LITERAL_NEIGHBORS, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher, scanPlan, scanBooleanPlan, literalSearch, literalSet, innerLiteralSearch, innerLiteralSearchMinimum, innerLiteralCountMinimum, windowLiteralSearch, literalAlternationMatcher, fixedWidthByteMatcher, shortFixedWidthByteMatcher, byteRunMatcher, dotStarLiteralMatcher, orderedLiteralMatcher, literalNeighborMatcher);
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
        // An unanchored search clears its visited bitmap over the whole window, and each find
        // searches the rest of the text, so it keeps upstream's smaller window.
        int bitStateMaxSize = submatchAnchored ? prog.anchoredBitStateTextMaxSize() : prog.unanchoredBitStateTextMaxSize();
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
        boolean groupZeroOnly = groupOffsets == null || groupOffsets.length == 2;
        if (groupZeroOnly && prog.positionNfa() != null) {
            return searchOnPositionNfa(prog, context, start, end, submatchAnchored, matchKind, groupOffsets, workspaces);
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

    /// Finds group zero, or whether there is a match, with the program's position automaton, or,
    /// for an anchored search, with its length bound alone, as [#runSubmatchEngine] would with
    /// the Pike NFA. The program has no assertions, so the text outside a window cannot change
    /// what a search inside it finds, and every match is at most [PositionNfa#maxLength()] bytes
    /// long. An anchored match therefore lies in the first `maxLength` bytes, and a full match
    /// exists only on a text no longer than that; BitState or the Pike NFA searches that window.
    /// An unanchored search first finds E, the earliest end of any match. No match means the Pike
    /// NFA would find none either. Otherwise every match ends at or after E, the one ending at E
    /// starts before it, and the leftmost match starts no later than that one, so it starts in
    /// `[E - maxLength, E)` and ends by `E + maxLength`; the leftmost-first or leftmost-longest
    /// match found in that window is the one the whole text holds.
    private static boolean searchOnPositionNfa(
            Prog prog,
            Slice context,
            int start,
            int end,
            boolean anchored,
            Prog.MatchKind matchKind,
            int[] groupOffsets,
            MatchWorkspaces workspaces)
    {
        PositionNfa positionNfa = prog.positionNfa();
        int maxLength = positionNfa.maxLength();
        int windowStart = start;
        int windowEnd;
        if (matchKind == Prog.MatchKind.FULL_MATCH) {
            if (end - start > maxLength) {
                return false;
            }
            windowEnd = end;
        }
        else if (anchored) {
            windowEnd = start + Math.min(maxLength, end - start);
        }
        else {
            int base = context.byteArrayOffset();
            int earliestEnd = positionNfa.earliestEnd(prog, context.byteArray(), base + start, base + end, positionNfaWorkspace(positionNfa, workspaces));
            if (earliestEnd < 0) {
                return false;
            }
            if (groupOffsets == null) {
                return true;
            }
            int matchEnd = earliestEnd - base;
            windowStart = Math.max(start, matchEnd - maxLength);
            windowEnd = matchEnd + Math.min(maxLength, end - matchEnd);
        }

        boolean matched;
        // BitState searches the window where its visited bitmap is at most the cap; otherwise the
        // Pike NFA does.
        int bitStateMaxSize = anchored ? prog.anchoredBitStateTextMaxSize() : prog.unanchoredBitStateTextMaxSize();
        if (prog.canBitState() && windowEnd - windowStart <= bitStateMaxSize &&
                prog.listCount() * (windowEnd - windowStart + 1L) <= POSITION_NFA_WINDOW_BIT_STATE_MAXIMUM_BYTES * Byte.SIZE) {
            matched = BitState.search(prog, context, windowStart, windowEnd, anchored, matchKind, groupOffsets, bitStateWorkspace(workspaces));
        }
        else if (groupOffsets != null) {
            matched = Nfa.searchGroupZero(prog, context, windowStart, windowEnd, anchored, matchKind, groupOffsets, nfaWorkspace(workspaces));
        }
        else {
            matched = Nfa.search(prog, context, windowStart, windowEnd, anchored, matchKind, null, nfaWorkspace(workspaces));
        }
        if (matched && groupOffsets != null && windowStart != start) {
            shiftGroupOffsets(groupOffsets, windowStart - start);
        }
        return matched;
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

    // Only a matcher keeps the position automaton's workspace, and only for an automaton that uses
    // one; any other search of such an automaton allocates its own.
    private static PositionNfa.Workspace positionNfaWorkspace(PositionNfa positionNfa, MatchWorkspaces workspaces)
    {
        return positionNfa.usesWorkspace() && workspaces instanceof RegexpMatcher matcher ? matcher.positionNfaWorkspace() : null;
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

    // Only a matcher carries the mark of a count whose DFA count bailed.
    private static boolean skipsForwardDfa(MatchWorkspaces workspaces)
    {
        return workspaces instanceof RegexpMatcher matcher && matcher.skipsForwardDfa();
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
                // The program, or its failure, replaces its input.
                normalizedReverseRegexp = null;
                reverseProgComputed = true;
            }
        }
        return reverseProg;
    }

    /// Compile the reverse program with appropriate memory budget (1/3 of total, less what an inner
    /// or window literal search retains).
    ///
    /// Memory allocation strategy (matching upstream):
    /// - Forward program: 2/3 of maxMemory (more common, can have 2 DFAs: first-match and longest-match)
    /// - Reverse program: 1/3 of maxMemory (less frequent, only used in two-phase search), less
    ///   what an inner or window literal search retains
    ///
    /// The program replaces the retained input it is compiled from, which fit the share by
    /// itself, so the program alone must fit the share.
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
        if (normalizedReverseRegexp == null) {
            return null;
        }
        // Compile from the stored normalized suffix instead of re-parsing or re-simplifying.
        Prog prog;
        try {
            prog = Compiler.compileNormalized(normalizedReverseRegexp, true, reverseProgramMemory(), compilerDialect(), caseFolding());
        }
        catch (RegexpCompileMemoryLimitException ignored) {
            return null;
        }
        return prog;
    }

    /// Returns the reverse share of `maxMemory`, less what an inner or window literal search,
    /// which already holds part of it, retains.
    private long reverseProgramMemory()
    {
        long reverseMemory = Math.max(1, maxMemory / 3);
        InnerLiteralSearch innerLiteralSearch = booleanPlans.innerLiteralSearch();
        if (innerLiteralSearch != null) {
            reverseMemory = Math.max(1, reverseMemory - innerLiteralSearch.estimatedRetainedSize());
        }
        WindowLiteralSearch windowLiteralSearch = booleanPlans.windowLiteralSearch();
        if (windowLiteralSearch != null) {
            reverseMemory = Math.max(1, reverseMemory - windowLiteralSearch.estimatedRetainedSize());
        }
        return reverseMemory;
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
