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

import java.util.Arrays;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * A compiled RE2 pattern.
 * <p>
 * Instances are immutable and thread-safe. Matching operates directly on {@link Slice} byte ranges;
 * offsets are byte offsets relative to the logical Slice, not its backing array.
 */
@SuppressWarnings("CharUsedInArithmeticContext")
public final class Re2
{
    enum BooleanPartialMatchStrategy
    {
        GENERAL,
        EXACT_LITERAL,
        NULLABLE_START,
        CONTAINS,
        EQUALS,
        STARTS_WITH,
        ENDS_WITH,
        EQUALS_FINAL_LINE,
        ENDS_WITH_FINAL_LINE,
        LOWERED_PROGRAM,
    }

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

    private static final byte BOOLEAN_FIND_GENERAL = 0;
    private static final byte BOOLEAN_FIND_EXACT_LITERAL = 1;
    private static final byte BOOLEAN_FIND_NULLABLE_START = 2;
    private static final byte BOOLEAN_FIND_CONTAINS = 3;
    private static final byte BOOLEAN_FIND_EQUALS = 4;
    private static final byte BOOLEAN_FIND_STARTS_WITH = 5;
    private static final byte BOOLEAN_FIND_ENDS_WITH = 6;
    private static final byte BOOLEAN_FIND_EQUALS_FINAL_LINE = 7;
    private static final byte BOOLEAN_FIND_ENDS_WITH_FINAL_LINE = 8;
    private static final byte BOOLEAN_FIND_LOWERED_PROGRAM = 9;
    private static final byte BOOLEAN_FIND_LOWERED_PROGRAM_DIRECT_GROUP_ZERO = 10;
    private static final byte BOOLEAN_FIND_COMPACT_BOUNDED_CHARACTER_CLASS = 11;
    private static final byte BOOLEAN_FIND_RETAINED_CHARACTER_CLASS_COUNT_DFA = 12;
    private static final byte BOOLEAN_FIND_SINGLE_BYTE = 13;

    // Leave large inputs to the DFA's selective byte scans instead of a scalar table walk.
    private static final int MAX_DIRECT_BYTE_SCAN_BYTES = 64;

    // Internal construction metadata, stored outside the parser flag range to preserve Re2's
    // compact object layout. It is consulted only by lazy reverse-program compilation.
    private static final int TRINO_COMPILER_DIALECT_FLAG = 1 << 31;

    // Above this size the existing rejection paths are already cheap, while loading
    // minimum-width metadata measurably affected the sub-nanosecond anchored path.
    private static final int MINIMUM_LENGTH_CHECK_LIMIT = 4 * 1024;
    private static final int DIRECT_BIT_STATE_CAPTURE_MINIMUM_LENGTH = 4 * 1024;
    private static final int LOWERED_CANDIDATE_SEARCH_BYTES = 64;

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

    /**
     * The result of replacing every non-overlapping match.
     */
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP",
            justification = "Slice values are deliberate zero-copy results")
    public record ReplaceAllResult(int replacementCount, Slice result) {}

    record FanoutResult(int maxBucket, int[] histogram) {}

    /**
     * Mutable compilation options. Compilation snapshots every option, so later changes do not
     * affect an existing {@link Re2}. Options instances are not thread-safe.
     */
    public static final class Options
    {
        public static final int DEFAULT_MAX_MEMORY = 96 << 20;

        public enum Encoding
        {
            UTF8,
            LATIN1,
        }

        private long maxMemory = DEFAULT_MAX_MEMORY;
        private Encoding encoding = Encoding.UTF8;
        private boolean posixSyntax;
        private boolean longestMatch;
        private boolean literal;
        private boolean neverNewline;
        private boolean dotMatchesNewline;
        private boolean neverCapture;
        private boolean caseSensitive = true;
        private boolean perlClasses;
        private boolean wordBoundary;
        private boolean oneLine;

        private Options() {}

        /**
         * Creates options for RE2's Perl-like UTF-8 syntax and leftmost-first matching.
         */
        public static Options defaults()
        {
            return new Options();
        }

        /**
         * Creates UTF-8 POSIX options with leftmost-longest matching.
         */
        public static Options posix()
        {
            Options options = new Options();
            options.posixSyntax = true;
            options.longestMatch = true;
            return options;
        }

        /**
         * Creates Perl-like options that interpret patterns and inputs as Latin-1 bytes.
         */
        public static Options latin1()
        {
            Options options = new Options();
            options.encoding = Encoding.LATIN1;
            return options;
        }

        Options copy()
        {
            Options copy = new Options();
            copy.maxMemory = maxMemory;
            copy.encoding = encoding;
            copy.posixSyntax = posixSyntax;
            copy.longestMatch = longestMatch;
            copy.literal = literal;
            copy.neverNewline = neverNewline;
            copy.dotMatchesNewline = dotMatchesNewline;
            copy.neverCapture = neverCapture;
            copy.caseSensitive = caseSensitive;
            copy.perlClasses = perlClasses;
            copy.wordBoundary = wordBoundary;
            copy.oneLine = oneLine;
            return copy;
        }

        long maxMemory()
        {
            return maxMemory;
        }

        /**
         * Sets the positive compilation and DFA-cache memory budget in bytes.
         */
        public Options setMaxMemory(long maxMemory)
        {
            if (maxMemory <= 0) {
                throw new IllegalArgumentException("maxMemory must be greater than zero: " + maxMemory);
            }
            this.maxMemory = maxMemory;
            return this;
        }

        Encoding encoding()
        {
            return encoding;
        }

        public Options setEncoding(Encoding encoding)
        {
            this.encoding = requireNonNull(encoding, "encoding is null");
            return this;
        }

        public Options setPosixSyntax(boolean posixSyntax)
        {
            this.posixSyntax = posixSyntax;
            return this;
        }

        boolean longestMatch()
        {
            return longestMatch;
        }

        public Options setLongestMatch(boolean longestMatch)
        {
            this.longestMatch = longestMatch;
            return this;
        }

        public Options setLiteral(boolean literal)
        {
            this.literal = literal;
            return this;
        }

        public Options setNeverNewline(boolean neverNewline)
        {
            this.neverNewline = neverNewline;
            return this;
        }

        public Options setDotMatchesNewline(boolean dotMatchesNewline)
        {
            this.dotMatchesNewline = dotMatchesNewline;
            return this;
        }

        public Options setNeverCapture(boolean neverCapture)
        {
            this.neverCapture = neverCapture;
            return this;
        }

        public Options setCaseSensitive(boolean caseSensitive)
        {
            this.caseSensitive = caseSensitive;
            return this;
        }

        public Options setPerlClasses(boolean perlClasses)
        {
            this.perlClasses = perlClasses;
            return this;
        }

        public Options setWordBoundary(boolean wordBoundary)
        {
            this.wordBoundary = wordBoundary;
            return this;
        }

        public Options setOneLine(boolean oneLine)
        {
            this.oneLine = oneLine;
            return this;
        }

        int parseFlags()
        {
            int flags = Regexp.CLASS_NEWLINE;
            if (encoding == Encoding.LATIN1) {
                flags |= Regexp.LATIN1;
            }
            if (!posixSyntax) {
                flags |= Regexp.LIKE_PERL;
            }
            if (literal) {
                flags |= Regexp.LITERAL;
            }
            if (neverNewline) {
                flags |= Regexp.NEVER_NEWLINE;
            }
            if (dotMatchesNewline) {
                flags |= Regexp.DOT_MATCHES_NEWLINE;
            }
            if (neverCapture) {
                flags |= Regexp.NEVER_CAPTURE;
            }
            if (!caseSensitive) {
                flags |= Regexp.FOLD_CASE;
            }
            if (perlClasses) {
                flags |= Regexp.PERL_CLASSES;
            }
            if (wordBoundary) {
                flags |= Regexp.PERL_WORD_BOUNDARY;
            }
            if (oneLine) {
                flags |= Regexp.ONE_LINE;
            }
            return flags;
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
    private volatile Map<String, Integer> namedCapturingGroups;
    private volatile Map<Integer, String> capturingGroupNames;

    // AST-specialized analyzers retain the semantic suffix. The compact fixed-width Trino route
    // stores its byte matcher in the same cold slot because its subtype owns every public match
    // operation. Reverse compilation separately retains the normalized semantic or lowered form.
    private final Object suffixRegexpOrFixedWidthMatcher;
    private final Regexp normalizedReverseRegexp;
    private final byte[] requiredPrefix;
    private final boolean requiredPrefixFoldCase;
    private final int matchLength;
    private final int exactLiteralLength;
    private final BooleanPlans booleanPlans;
    // Cached directly on Re2 because an extra dependent load measurably affects tiny find operations.
    private final byte booleanFindStrategy;

    private volatile Prog reverseProg;
    private volatile boolean reverseProgComputed;
    private volatile BoundedCharacterClassCounter boundedCharacterClassCounter;
    private volatile SingleByteMatcher singleByteMatcher;

    private Re2(
            int flags,
            long maxMemory,
            Compiler.Dialect compilerDialect,
            boolean longestMatch,
            Slice pattern,
            Regexp regexp,
            ExpressionAnalysis expressionAnalysis,
            Object suffixRegexpOrFixedWidthMatcher,
            Regexp normalizedReverseRegexp,
            byte[] requiredPrefix,
            boolean requiredPrefixFoldCase,
            int matchLength,
            int exactLiteralLength,
            Prog partialProg,
            BooleanPlans booleanPlans,
            int capturingGroupCount,
            Map<String, Integer> namedCapturingGroups,
            Map<Integer, String> capturingGroupNames,
            BoundedCharacterClassCounter compactBoundedCharacterClass)
    {
        requireNonNull(compilerDialect, "compilerDialect is null");
        this.flags = compilerDialect == Compiler.Dialect.TRINO ? flags | TRINO_COMPILER_DIALECT_FLAG : flags;
        this.maxMemory = maxMemory;
        this.longestMatch = longestMatch;
        this.pattern = requireNonNull(pattern, "pattern is null");
        this.regexp = regexp;
        this.expressionAnalysis = requireNonNull(expressionAnalysis, "expressionAnalysis is null");
        this.suffixRegexpOrFixedWidthMatcher = suffixRegexpOrFixedWidthMatcher;
        this.normalizedReverseRegexp = normalizedReverseRegexp;
        this.requiredPrefix = requiredPrefix;
        this.requiredPrefixFoldCase = requiredPrefixFoldCase;
        this.matchLength = matchLength;
        this.exactLiteralLength = exactLiteralLength;
        this.partialProg = requireNonNull(partialProg, "partialProg is null");
        this.booleanPlans = requireNonNull(booleanPlans, "booleanPlans is null");
        byte findStrategy;
        if (compactBoundedCharacterClass == null) {
            findStrategy = encodeBooleanFindStrategy(booleanPlans.partialMatchStrategy());
        }
        else if (partialProg.dfaMemory() >= RetainedCharacterClassCountDfa.MINIMUM_DFA_MEMORY) {
            findStrategy = BOOLEAN_FIND_RETAINED_CHARACTER_CLASS_COUNT_DFA;
        }
        else {
            findStrategy = BOOLEAN_FIND_COMPACT_BOUNDED_CHARACTER_CLASS;
        }
        if (compactBoundedCharacterClass == null && canUseDirectLoweredGroupZeroSearch(
                partialProg,
                booleanPlans.loweredProgram(),
                expressionAnalysis,
                requiredPrefix)) {
            findStrategy = BOOLEAN_FIND_LOWERED_PROGRAM_DIRECT_GROUP_ZERO;
        }
        if (supportsSingleByteMatcher() &&
                SingleByteMatcher.estimatedRetainedSize() <= partialProg.dfaMemory()) {
            // Reserve before any DFA can snapshot its budget. The table itself stays lazy.
            partialProg.setDfaMemory(partialProg.dfaMemory() - SingleByteMatcher.estimatedRetainedSize());
            if (findStrategy == BOOLEAN_FIND_GENERAL) {
                findStrategy = BOOLEAN_FIND_SINGLE_BYTE;
            }
        }
        else {
            singleByteMatcher = SingleByteMatcher.unsupported();
        }
        this.booleanFindStrategy = findStrategy;
        this.capturingGroupCount = capturingGroupCount;
        this.namedCapturingGroups = namedCapturingGroups;
        this.capturingGroupNames = capturingGroupNames;
        this.boundedCharacterClassCounter = compactBoundedCharacterClass;
    }

    /**
     * Compiles a copied snapshot of {@code pattern} with {@link Options#defaults()}.
     */
    public static Re2 compile(Slice pattern)
    {
        return compile(pattern, Options.defaults());
    }

    static Re2 compile(Slice pattern, int flags)
    {
        requireNonNull(pattern, "pattern is null");
        return compile(pattern.copy(), flags, Options.DEFAULT_MAX_MEMORY, false);
    }

    /**
     * Compiles a copied snapshot of {@code pattern} and the current option values.
     */
    public static Re2 compile(Slice pattern, Options options)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        return compile(pattern.copy(), options.parseFlags(), options.maxMemory(), options.longestMatch());
    }

    /**
     * Replaces the first match using RE2 rewrite syntax ({@code \0} through {@code \9}).
     * Returns {@code text} unchanged when there is no match.
     *
     * @throws RegexpRewriteException if the rewrite is invalid for this pattern
     */
    public Slice replaceFirst(Slice text, Slice rewrite)
    {
        requireNonNull(text, "text is null");
        int captureSlotCount = validateRewrite(rewrite);

        int[] groupOffsets = new int[2 * captureSlotCount];
        if (!matchInto(text, Anchor.UNANCHORED, groupOffsets)) {
            return text;
        }

        int matchStart = groupOffsets[0];
        int matchEnd = groupOffsets[1];
        if (matchStart < 0 || matchEnd < matchStart) {
            throw new IllegalStateException("match engine returned invalid group zero boundaries");
        }

        DynamicSliceOutput out = new DynamicSliceOutput(text.length() + rewrite.length());
        appendBytes(out, text, 0, matchStart);
        rewrite(out, rewrite, text, groupOffsets, captureSlotCount);
        appendBytes(out, text, matchEnd, text.length() - matchEnd);
        return out.slice();
    }

    /**
     * Replaces every non-overlapping match using RE2 rewrite syntax.
     *
     * @throws RegexpRewriteException if the rewrite is invalid for this pattern
     */
    public ReplaceAllResult replaceAll(Slice text, Slice rewrite)
    {
        requireNonNull(text, "text is null");
        int captureSlotCount = validateRewrite(rewrite);

        int[] groupOffsets = new int[2 * captureSlotCount];
        byte[] bytes = text.byteArray();
        int base = text.byteArrayOffset();
        int end = base + text.length();
        int position = base;
        int lastEnd = -1;
        int count = 0;
        DynamicSliceOutput out = new DynamicSliceOutput(text.length() + rewrite.length());

        while (position <= end) {
            int start = position - base;
            if (!matchInto(text, start, text.length(), Anchor.UNANCHORED, groupOffsets)) {
                break;
            }

            int matchStart = base + groupOffsets[0];
            int matchEnd = base + groupOffsets[1];
            if (position < matchStart) {
                appendBytes(out, bytes, position, matchStart - position);
            }

            if (matchStart == lastEnd && matchStart == matchEnd) {
                // Disallow empty match at end of last match: skip ahead by one rune/byte.
                int advance = advanceByRuneIfPossible(bytes, position, end, (flags & Regexp.LATIN1) == 0, out);
                if (advance == 0) {
                    break;
                }
                position += advance;
                continue;
            }

            rewrite(out, rewrite, text, groupOffsets, captureSlotCount);
            position = matchEnd;
            lastEnd = position;
            count++;
        }

        if (count == 0) {
            return new ReplaceAllResult(0, text);
        }
        if (position < end) {
            appendBytes(out, bytes, position, end - position);
        }
        return new ReplaceAllResult(count, out.slice());
    }

    /**
     * Expands an RE2 rewrite against the first match, or returns {@code null} when no match exists.
     *
     * @throws RegexpRewriteException if the rewrite is invalid for this pattern
     */
    public Slice extract(Slice text, Slice rewrite)
    {
        requireNonNull(text, "text is null");
        int captureSlotCount = validateRewrite(rewrite);
        int[] groupOffsets = new int[2 * captureSlotCount];
        if (!matchInto(text, Anchor.UNANCHORED, groupOffsets)) {
            return null;
        }

        DynamicSliceOutput out = new DynamicSliceOutput(rewrite.length());
        rewrite(out, rewrite, text, groupOffsets, captureSlotCount);
        return out.slice();
    }

    private int validateRewrite(Slice rewrite)
    {
        requireNonNull(rewrite, "rewrite is null");
        int maximumCaptureReference = 0;
        byte[] bytes = rewrite.byteArray();
        int start = rewrite.byteArrayOffset();
        int end = start + rewrite.length();
        for (int i = start; i < end; i++) {
            int current = bytes[i] & 0xFF;
            if (current != '\\') {
                continue;
            }
            if (++i >= end) {
                throw new RegexpRewriteException("rewrite cannot end with '\\\\'");
            }
            current = bytes[i] & 0xFF;
            if (current == '\\') {
                continue;
            }
            if (current < '0' || current > '9') {
                throw new RegexpRewriteException("'\\\\' in a rewrite must be followed by a digit or '\\\\'");
            }
            int captureReference = current - '0';
            if (maximumCaptureReference < captureReference) {
                maximumCaptureReference = captureReference;
            }
        }

        if (maximumCaptureReference > capturingGroupCount()) {
            throw new RegexpRewriteException("rewrite references capturing group " + maximumCaptureReference +
                    ", but the pattern has only " + capturingGroupCount() + " capturing groups");
        }
        return maximumCaptureReference + 1;
    }

    /**
     * Returns a pattern that matches the supplied bytes literally.
     */
    public static Slice quote(Slice unquoted)
    {
        requireNonNull(unquoted, "unquoted is null");
        byte[] bytes = unquoted.byteArray();
        int start = unquoted.byteArrayOffset();
        int end = start + unquoted.length();

        DynamicSliceOutput out = new DynamicSliceOutput(unquoted.length() * 2);
        for (int i = start; i < end; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) {
                out.writeByte('\\');
                out.writeByte('x');
                out.writeByte('0');
                out.writeByte('0');
                continue;
            }
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || b == '_' || (b & 0x80) != 0) {
                out.writeByte(b);
                continue;
            }
            out.writeByte('\\');
            out.writeByte(b);
        }
        return out.slice();
    }

    private static Re2 compile(Slice pattern, int flags, long maxMemory, boolean longestMatch)
    {
        requireNonNull(pattern, "pattern is null");
        ParseResult parsed = RegexpParser.parse(pattern, flags);
        return build(pattern, parsed, flags, maxMemory, longestMatch);
    }

    static Re2 compileParsed(Slice pattern, ParseResult parsed, int flags)
    {
        return compileParsed(pattern, parsed, flags, Options.DEFAULT_MAX_MEMORY);
    }

    static Re2 compileParsed(Slice pattern, ParseResult parsed, int flags, long maxMemory)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(parsed, "parsed is null");
        if (maxMemory <= 0) {
            throw new IllegalArgumentException("maxMemory must be greater than zero: " + maxMemory);
        }
        return build(pattern.copy(), parsed, flags, maxMemory, false);
    }

    static Re2 compileParsedForTrino(Slice pattern, ParseResult parsed, int flags, long maxMemory)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(parsed, "parsed is null");
        if (maxMemory <= 0) {
            throw new IllegalArgumentException("maxMemory must be greater than zero: " + maxMemory);
        }

        long forwardMemory = Math.max(1, maxMemory - Math.max(1, maxMemory / 3));
        Regexp entireRegexp = parsed.regexp();
        FixedWidthByteSpanMatcher fixedWidthMatcher = shouldAnalyzeFixedWidthByteSpanForTrino(parsed)
                ? FixedWidthByteSpanMatcher.analyze(entireRegexp)
                : null;
        if (fixedWidthMatcher != null) {
            Regexp normalizedRegexp = Simplifier.simplify(entireRegexp);
            ExpressionAnalysis expressionAnalysis = ExpressionAnalysis.analyzeNormalized(normalizedRegexp);
            Prog placeholderProgram = Compiler.compileNormalized(Regexp.noMatch(flags), false, forwardMemory, Compiler.Dialect.TRINO);
            if (fixedWidthMatcher.estimatedRetainedSize() <= placeholderProgram.dfaMemory()) {
                placeholderProgram.setDfaMemory(0);
                BooleanPlans booleanPlans = selectBooleanPlans(expressionAnalysis, -1, null, placeholderProgram);
                return new Re2(
                        flags,
                        maxMemory,
                        Compiler.Dialect.TRINO,
                        false,
                        pattern.copy(),
                        entireRegexp,
                        expressionAnalysis,
                        fixedWidthMatcher,
                        null,
                        null,
                        false,
                        MatchLength.analyze(entireRegexp).encoded(),
                        -1,
                        placeholderProgram,
                        booleanPlans,
                        0,
                        null,
                        null,
                        null);
            }
        }
        return build(pattern.copy(), parsed, flags, maxMemory, false, Compiler.Dialect.TRINO);
    }

    static boolean shouldAnalyzeFixedWidthByteSpanForTrino(ParseResult parsed)
    {
        Regexp regexp = parsed.regexp();
        // Exact literals have a smaller dedicated route. Avoid allocating byte predicates for a
        // shape that cannot reach the fixed-width subtype.
        return parsed.capturingGroupCount() == 0 &&
                regexp.op() != RegexpOp.LITERAL &&
                regexp.op() != RegexpOp.LITERAL_STRING &&
                regexp.requiredPrefix() == null;
    }

    private static Re2 build(Slice pattern, ParseResult parsed, int flags, long maxMemory, boolean longestMatch)
    {
        return build(pattern, parsed, flags, maxMemory, longestMatch, Compiler.Dialect.RE2);
    }

    private static Re2 build(
            Slice pattern,
            ParseResult parsed,
            int flags,
            long maxMemory,
            boolean longestMatch,
            Compiler.Dialect compilerDialect)
    {
        long reverseMemory = Math.max(1, maxMemory / 3);
        long forwardMemory = Math.max(1, maxMemory - reverseMemory);

        Regexp entireRegexp = parsed.regexp();
        int capturingGroupCount = parsed.capturingGroupCount();
        BoundedCharacterClassCounter compactBoundedCharacterClass = capturingGroupCount == 0 && !longestMatch
                ? BoundedCharacterClassCounter.analyzeCompactRoute(entireRegexp)
                : null;
        if (compactBoundedCharacterClass != null) {
            long counterMemory = compactBoundedCharacterClass.estimatedRetainedSize();
            if (counterMemory >= forwardMemory) {
                throw new RegexpCompileMemoryLimitException(maxMemory);
            }

            ExpressionAnalysis expressionAnalysis = ExpressionAnalysis.analyzeNormalized(entireRegexp);
            long availableMemory = forwardMemory - counterMemory;
            Prog placeholderProgram = Compiler.compileNormalized(Regexp.noMatch(flags), false, availableMemory, compilerDialect);
            placeholderProgram.setDfaMemory(0);
            BooleanPlans booleanPlans = selectBooleanPlans(expressionAnalysis, -1, null, placeholderProgram);
            Prog partialProgram = placeholderProgram;
            if (Dfa.nativeAccessEnabled() && RetainedCharacterClassCountDfa.supports(entireRegexp)) {
                try {
                    Regexp normalizedRegexp = Simplifier.simplify(entireRegexp);
                    Prog countProgram = Compiler.compileNormalizedForDfa(normalizedRegexp, false, availableMemory, compilerDialect);
                    if (countProgram.dfaMemory() >= RetainedCharacterClassCountDfa.MINIMUM_DFA_MEMORY) {
                        partialProgram = countProgram;
                    }
                }
                catch (RegexpCompileException ignored) {
                    // The optional DFA could not fit; the compact counter remains the fallback.
                }
            }
            return new Re2(
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
                    MatchLength.analyze(entireRegexp).encoded(),
                    -1,
                    partialProgram,
                    booleanPlans,
                    0,
                    null,
                    null,
                    compactBoundedCharacterClass);
        }

        Regexp normalizedRegexp = Simplifier.simplify(entireRegexp);
        ExpressionAnalysis expressionAnalysis = ExpressionAnalysis.analyzeNormalized(normalizedRegexp);

        // For "^literal...", compile only the suffix and check the required prefix before matching.
        // Retain the suffix AST so reverse-program construction also avoids the stripped literal.
        Regexp suffixRegexp;
        byte[] requiredPrefix = null;
        boolean prefixFoldCase = false;
        Regexp.RequiredPrefixResult prefixResult = entireRegexp.requiredPrefix();
        if (prefixResult != null) {
            requiredPrefix = prefixResult.prefix().getBytes();
            prefixFoldCase = prefixResult.foldCase();
            suffixRegexp = prefixResult.suffix();
        }
        else {
            suffixRegexp = entireRegexp;
        }
        Regexp normalizedSuffixRegexp = suffixRegexp == entireRegexp ? normalizedRegexp : Simplifier.simplify(suffixRegexp);

        // Compute captures from the entire regexp (named groups come from the full pattern).
        Prog semanticProgram = Compiler.compileNormalized(normalizedSuffixRegexp, false, forwardMemory, compilerDialect);
        MatchLength.Analysis matchLength = MatchLength.analyze(suffixRegexp);
        ExpressionAnalysis.LiteralSequence literalSequence = expressionAnalysis.literalSequence();
        Slice exactLiteral = literalSequence == null || literalSequence.anchoredAtStart() || literalSequence.anchoredAtEnd()
                ? null
                : literalSequence.retainedExactLiteral();
        if (exactLiteral != null && exactLiteral.length() != 0) {
            semanticProgram.configurePrefixAccelShared(exactLiteral, false);
        }
        BooleanPlans booleanPlans = selectBooleanPlans(
                expressionAnalysis,
                exactLiteral == null ? -1 : exactLiteral.length(),
                requiredPrefix,
                semanticProgram);
        LoweredBooleanProgram loweredBooleanProgram = compileLoweredBooleanProgram(
                suffixRegexp,
                semanticProgram,
                booleanPlans,
                forwardMemory,
                compilerDialect);
        if (loweredBooleanProgram != null) {
            booleanPlans = selectLoweredBooleanPlans(booleanPlans, loweredBooleanProgram.program());
        }
        TaggedAlternationProgram taggedAlternationProgram = requiredPrefix == null && loweredBooleanProgram == null
                ? TaggedAlternationProgram.compile(entireRegexp, capturingGroupCount, longestMatch, semanticProgram, compilerDialect)
                : null;
        booleanPlans = booleanPlans.withTaggedAlternationProgram(taggedAlternationProgram);
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

        return new Re2(
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
                matchLength.encoded(),
                exactLiteral == null ? -1 : exactLiteral.length(),
                semanticProgram,
                booleanPlans,
                capturingGroupCount,
                null,
                null,
                null);
    }

    private static LoweredBooleanProgram compileLoweredBooleanProgram(
            Regexp suffixRegexp,
            Prog semanticProgram,
            BooleanPlans booleanPlans,
            long forwardMemory,
            Compiler.Dialect compilerDialect)
    {
        if (!requiresProgramExecution(booleanPlans.find()) &&
                booleanPlans.lookingAt() != BooleanPlanKind.GENERAL) {
            return null;
        }

        Regexp loweredRegexp = BooleanRegexpLowering.lowerTerminalFinalLineEnds(suffixRegexp);
        if (loweredRegexp == suffixRegexp) {
            return null;
        }

        Regexp normalizedRegexp = Simplifier.simplify(loweredRegexp);
        Prog program;
        try {
            program = Compiler.compileNormalized(normalizedRegexp, false, forwardMemory, compilerDialect);
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
            // Compilation has already charged the lowered program and any retained OnePass table.
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
        BooleanPartialMatchStrategy partialMatchStrategy = find == BooleanPlanKind.LOWERED_PROGRAM
                ? BooleanPartialMatchStrategy.LOWERED_PROGRAM
                : plans.partialMatchStrategy();
        return new BooleanPlans(plans.literal(), partialMatchStrategy, find, lookingAt, plans.matches(), program, plans.taggedAlternationProgram(), plans.wordRunMatcher(), plans.wholeInputCapturePlan(), plans.disjointSuffixRepeatMatcher());
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

    /**
     * Returns a copy of the compiled pattern bytes.
     */
    public Slice pattern()
    {
        return pattern.copy();
    }

    public int capturingGroupCount()
    {
        return capturingGroupCount;
    }

    /**
     * Returns an immutable mapping from capture names to group numbers.
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "The returned map is immutable")
    public Map<String, Integer> namedCapturingGroups()
    {
        Map<String, Integer> cachedNamedCapturingGroups = namedCapturingGroups;
        if (cachedNamedCapturingGroups != null) {
            return cachedNamedCapturingGroups;
        }
        cachedNamedCapturingGroups = regexp.namedCaptures();
        namedCapturingGroups = cachedNamedCapturingGroups;
        return cachedNamedCapturingGroups;
    }

    boolean isNamedCapturingGroupsComputed()
    {
        return namedCapturingGroups != null;
    }

    /**
     * Returns an immutable mapping from group numbers to capture names.
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "The returned map is immutable")
    public Map<Integer, String> capturingGroupNames()
    {
        Map<Integer, String> cachedCapturingGroupNames = capturingGroupNames;
        if (cachedCapturingGroupNames != null) {
            return cachedCapturingGroupNames;
        }
        cachedCapturingGroupNames = regexp.captureNames();
        capturingGroupNames = cachedCapturingGroupNames;
        return cachedCapturingGroupNames;
    }

    /**
     * Package-private accessor for prefilter extraction (not for general use).
     * Matches upstream RE2::Regexp() const.
     */
    Regexp regexp()
    {
        return regexp;
    }

    ExpressionAnalysis expressionAnalysisForDiagnostics()
    {
        return expressionAnalysis;
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

    boolean usesCompactBoundedCharacterClassForDiagnostics()
    {
        return booleanFindStrategy == BOOLEAN_FIND_COMPACT_BOUNDED_CHARACTER_CLASS ||
                booleanFindStrategy == BOOLEAN_FIND_RETAINED_CHARACTER_CLASS_COUNT_DFA;
    }

    boolean hasRetainedFixedWidthByteSpanMatcher()
    {
        return fixedWidthByteSpanMatcher() != null;
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

    boolean usesExactLiteralPartialMatchForDiagnostics()
    {
        return booleanFindStrategy == BOOLEAN_FIND_EXACT_LITERAL;
    }

    boolean hasExactLiteralSpan()
    {
        return exactLiteralLength >= 0;
    }

    DotStarLiteralSpanMatcher createDotStarLiteralSpanMatcher()
    {
        return DotStarLiteralSpanMatcher.analyze(expressionAnalysis);
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
        FixedWidthByteSpanMatcher retainedMatcher = fixedWidthByteSpanMatcher();
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
        ExpressionAnalysis.LiteralSequence literalSequence = expressionAnalysis.literalSequence();
        return capturingGroupCount == 0 &&
                MatchLength.fixed(matchLength) >= 0 &&
                literalSequence != null &&
                literalSequence.literalCount() != 0;
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

        int matchStart = LiteralMatchKernel.find(input, booleanPlans.literal(), start);
        if (matchStart < 0) {
            return Dfa.SEARCH_NO_MATCH;
        }
        int matchEnd = matchStart + exactLiteralLength;
        return ((long) matchStart << 32) | (matchEnd & 0xFFFF_FFFFL);
    }

    BooleanPartialMatchStrategy booleanPartialMatchStrategyForDiagnostics()
    {
        return decodeBooleanFindStrategy(booleanFindStrategy);
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

    boolean canUseDirectBitStateCapture(int searchLength, int captureCount)
    {
        return !longestMatch &&
                searchLength >= DIRECT_BIT_STATE_CAPTURE_MINIMUM_LENGTH &&
                captureCount > 1 &&
                !partialProg.isOnePass() &&
                partialProg.canBitState() &&
                searchLength <= partialProg.bitStateTextMaxSize();
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

    /**
     * Returns whether the pattern has a match anywhere in {@code input} without allocating capture storage.
     */
    public boolean find(Slice input)
    {
        requireNonNull(input, "input is null");
        byte strategy = booleanFindStrategy;
        if (strategy != BOOLEAN_FIND_GENERAL) {
            if (strategy == BOOLEAN_FIND_SINGLE_BYTE) {
                if (input.length() > MAX_DIRECT_BYTE_SCAN_BYTES) {
                    return matchInto(input, Anchor.UNANCHORED, null);
                }
                return sharedSingleByteMatcher().find(input, 0) >= 0;
            }
            if (strategy == BOOLEAN_FIND_COMPACT_BOUNDED_CHARACTER_CLASS ||
                    strategy == BOOLEAN_FIND_RETAINED_CHARACTER_CLASS_COUNT_DFA) {
                return boundedCharacterClassCounter.findSpan(input, 0, input.length(), 0) >= 0;
            }
            return optimizedPartialMatch(input, strategy);
        }
        return matchInto(input, Anchor.UNANCHORED, null);
    }

    /**
     * Returns whether the pattern has a match in {@code [start, end)}, retaining the complete Slice as assertion context.
     */
    public boolean find(Slice input, int start, int end)
    {
        return matchInto(input, start, end, Anchor.UNANCHORED, null);
    }

    /**
     * Returns whether the pattern matches at the beginning of {@code input} without allocating capture storage.
     */
    public boolean lookingAt(Slice input)
    {
        BooleanPlanKind plan = booleanPlans.lookingAt();
        if (plan != BooleanPlanKind.GENERAL) {
            requireNonNull(input, "input is null");
            return executeBooleanPlan(input, plan);
        }
        return matchInto(input, Anchor.ANCHOR_START, null);
    }

    /**
     * Returns whether the pattern matches at {@code start} within {@code [start, end)}, retaining the complete Slice as assertion context.
     */
    public boolean lookingAt(Slice input, int start, int end)
    {
        return matchInto(input, start, end, Anchor.ANCHOR_START, null);
    }

    private boolean optimizedPartialMatch(Slice input, byte strategy)
    {
        if (strategy == BOOLEAN_FIND_LOWERED_PROGRAM ||
                strategy == BOOLEAN_FIND_LOWERED_PROGRAM_DIRECT_GROUP_ZERO) {
            return matchLoweredBooleanProgram(input, Anchor.UNANCHORED);
        }
        if (strategy == BOOLEAN_FIND_EXACT_LITERAL) {
            if (exactLiteralLength == 0) {
                return true;
            }
            return partialProg.prefixAccel(input.byteArray(), input.byteArrayOffset(), input.length()) >= 0;
        }
        if (canReturnEmptyAtStartForBooleanMatch(input, 0)) {
            return true;
        }
        if (strategy == BOOLEAN_FIND_NULLABLE_START) {
            return matchInto(input, Anchor.UNANCHORED, null);
        }
        Slice literal = booleanPlans.literal();
        return switch (strategy) {
            case BOOLEAN_FIND_CONTAINS -> LiteralMatchKernel.contains(input, literal);
            case BOOLEAN_FIND_EQUALS -> LiteralMatchKernel.equals(input, literal);
            case BOOLEAN_FIND_STARTS_WITH -> startsWithBooleanPlanLiteral(input, literal);
            case BOOLEAN_FIND_ENDS_WITH -> endsWithBooleanPlanLiteral(input, literal);
            case BOOLEAN_FIND_EQUALS_FINAL_LINE -> equalsFinalLineBooleanPlanLiteral(input, literal);
            case BOOLEAN_FIND_ENDS_WITH_FINAL_LINE -> endsWithFinalLineBooleanPlanLiteral(input, literal);
            default -> throw new IllegalStateException("strategy cannot be executed directly: " + strategy);
        };
    }

    /**
     * Returns whether the pattern matches all of {@code input} without allocating capture storage.
     */
    public boolean matches(Slice input)
    {
        BooleanPlanKind plan = booleanPlans.matches();
        if (plan != BooleanPlanKind.GENERAL) {
            requireNonNull(input, "input is null");
            return executeBooleanPlan(input, plan);
        }
        return matchInto(input, Anchor.ANCHOR_BOTH, null);
    }

    private boolean executeBooleanPlan(Slice input, BooleanPlanKind plan)
    {
        Slice literal = booleanPlans.literal();
        return switch (plan) {
            case LITERAL_SEARCH, NULLABLE_START -> optimizedPartialMatch(input, booleanFindStrategy);
            case CONTAINS -> LiteralMatchKernel.contains(input, literal);
            case EQUALS -> LiteralMatchKernel.equals(input, literal);
            case STARTS_WITH -> startsWithBooleanPlanLiteral(input, literal);
            case ENDS_WITH -> endsWithBooleanPlanLiteral(input, literal);
            case EQUALS_FINAL_LINE -> equalsFinalLineBooleanPlanLiteral(input, literal);
            case ENDS_WITH_FINAL_LINE -> endsWithFinalLineBooleanPlanLiteral(input, literal);
            case LOWERED_PROGRAM -> matchLoweredBooleanProgram(input, Anchor.ANCHOR_START);
            case GENERAL -> throw new IllegalStateException("general plan cannot be executed directly");
        };
    }

    private boolean matchLoweredBooleanProgram(Slice input, Anchor anchor)
    {
        return matchInternal(
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

    private static boolean startsWithBooleanPlanLiteral(Slice input, Slice literal)
    {
        return LiteralMatchKernel.startsWith(input, literal);
    }

    private static boolean endsWithBooleanPlanLiteral(Slice input, Slice literal)
    {
        return LiteralMatchKernel.endsWith(input, literal);
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
        if (endsWithBooleanPlanLiteral(input, literal)) {
            return true;
        }
        int finalLineEnd = input.length() - 1;
        return finalLineEnd >= literal.length() &&
                input.getByte(finalLineEnd) == '\n' &&
                LiteralMatchKernel.matchesAt(input, finalLineEnd - literal.length(), literal);
    }

    /**
     * Returns whether the pattern matches all of {@code [start, end)}, retaining the complete Slice as assertion context.
     */
    public boolean matches(Slice input, int start, int end)
    {
        return matchInto(input, start, end, Anchor.ANCHOR_BOTH, null);
    }

    /**
     * Creates a mutable matcher retaining every capturing group.
     */
    public Re2Matcher matcher(Slice input)
    {
        return new Re2Matcher(this, input);
    }

    /**
     * Creates a reusable matcher that retains group zero and the requested prefix of explicit
     * capturing groups. Use zero when only complete-match boundaries are required.
     */
    public Re2Matcher matcher(Slice input, int retainedCapturingGroupCount)
    {
        return new Re2Matcher(this, input, null, retainedCapturingGroupCount);
    }

    Re2Matcher matcher(Slice input, SingleByteMatcher singleByteMatcher)
    {
        return new Re2Matcher(this, input, singleByteMatcher);
    }

    Re2Matcher groupZeroMatcher(Slice input, SingleByteMatcher singleByteMatcher)
    {
        return new Re2Matcher(this, input, singleByteMatcher, 0);
    }

    Re2Matcher genericGroupZeroMatcherForDiagnostics(Slice input)
    {
        return new Re2Matcher(this, input, null, 0, false);
    }

    Dfa.CandidateStartCursor createCandidateStartCursor()
    {
        if (booleanPlans.disjointSuffixRepeatMatcher() != null || usesCompactBoundedCharacterClassForDiagnostics() ||
                longestMatch ||
                requiredPrefix != null ||
                partialProg.anchorStart() ||
                partialProg.anchorEnd() ||
                partialProg.canMatchEmpty() ||
                MatchLength.fixed(matchLength) >= 0) {
            return null;
        }
        for (int instructionId = 0; instructionId < partialProg.size(); instructionId++) {
            if (partialProg.inst(instructionId).opcode() == InstOp.EMPTY_WIDTH) {
                return null;
            }
        }
        return Dfa.createCandidateStartCursor(partialProg);
    }

    Dfa.GroupZeroForwardCursor createGroupZeroForwardCursor()
    {
        if (booleanPlans.disjointSuffixRepeatMatcher() != null || usesCompactBoundedCharacterClassForDiagnostics() ||
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
            Slice input,
            int matchStart,
            int matchEnd,
            int contextStart,
            int[] groups)
    {
        TaggedAlternationProgram taggedAlternationProgram = booleanPlans.taggedAlternationProgram();
        return taggedAlternationProgram != null &&
                taggedAlternationProgram.materialize(input, matchStart, matchEnd, contextStart, groups);
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
        int matchStart;
        if (forward == 0) {
            matchStart = start;
        }
        else if (fixedMatchLength >= 0) {
            matchStart = matchEnd - fixedMatchLength;
        }
        else {
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
        return booleanFindStrategy == BOOLEAN_FIND_LOWERED_PROGRAM_DIRECT_GROUP_ZERO;
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

    long findLoweredGroupZeroBoundary(Slice input, int contextStart, int contextEnd, int start)
    {
        if (!hasDirectLoweredGroupZeroSearch()) {
            return Dfa.CANDIDATE_SEARCH_FALLBACK;
        }

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

    /**
     * Returns the number of non-overlapping matches, including empty matches.
     * Empty matches advance as in {@link Re2Matcher#find()}, by one code point
     * in UTF-8 mode or one byte in Latin-1 mode. No capture values are returned.
     */
    public long count(Slice input)
    {
        long optimizedCount = countMatches(input);
        if (optimizedCount >= 0) {
            return optimizedCount;
        }

        Re2Matcher matcher = matcher(input, 0);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    long countMatches(Slice text)
    {
        requireNonNull(text, "text is null");
        if (exactLiteralLength > 0) {
            long count = 0;
            byte[] bytes = text.byteArray();
            int start = text.byteArrayOffset();
            int end = start + text.length();
            while (start < end) {
                int matchStart = partialProg.prefixAccel(bytes, start, end - start);
                if (matchStart < 0) {
                    break;
                }
                count++;
                start = matchStart + exactLiteralLength;
            }
            return count;
        }
        if (booleanPlans.disjointSuffixRepeatMatcher() != null) {
            return booleanPlans.disjointSuffixRepeatMatcher().count(text);
        }
        if (booleanFindStrategy == BOOLEAN_FIND_SINGLE_BYTE && text.length() <= MAX_DIRECT_BYTE_SCAN_BYTES) {
            return sharedSingleByteMatcher().count(text);
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
            if (booleanFindStrategy == BOOLEAN_FIND_RETAINED_CHARACTER_CLASS_COUNT_DFA) {
                return RetainedCharacterClassCountDfa.count(characterClassCounter, partialProg, text);
            }
            return characterClassCounter.count(text);
        }

        Prog.MatchKind matchKind = longestMatch ? Prog.MatchKind.LONGEST_MATCH : Prog.MatchKind.FIRST_MATCH;
        return Dfa.countMatches(partialProg, text, matchKind);
    }

    SingleByteMatcher createSingleByteMatcher()
    {
        if (!mayHaveSingleByteMatcher()) {
            return null;
        }
        return SingleByteMatcher.analyze(suffixRegexp());
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
        return requiredPrefix == null &&
                !partialProg.hasTextDependentAssertions() &&
                MatchLength.fixed(matchLength) == 1;
    }

    boolean supportsSingleByteMatcher()
    {
        return mayHaveSingleByteMatcher() && SingleByteMatcher.supports(suffixRegexp());
    }

    SingleByteRepeatMatcher createSingleByteRepeatMatcher()
    {
        if (requiredPrefix != null || partialProg.hasTextDependentAssertions()) {
            return null;
        }
        return SingleByteRepeatMatcher.analyze(suffixRegexp());
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
        return BoundedCharacterClassCounter.analyze(suffixRegexp());
    }

    boolean hasCharacterClassSpan()
    {
        return usesCompactBoundedCharacterClassForDiagnostics() ||
                (requiredPrefix == null &&
                        !partialProg.anchorStart() &&
                        !partialProg.anchorEnd() &&
                        !partialProg.hasTextDependentAssertions() &&
                        BoundedCharacterClassCounter.supports(suffixRegexp()));
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

    /**
     * Finds a match and writes byte-offset start/end pairs into caller-owned storage.
     * The buffer contains group zero followed by a prefix of capturing groups. Unmatched and
     * unavailable groups are set to {@code -1, -1}. A {@code null} buffer requests no captures.
     */
    public boolean findInto(Slice text, int[] groups)
    {
        return matchInto(text, Anchor.UNANCHORED, groups);
    }

    /**
     * Matches at the beginning of the input and writes byte-offset start/end pairs.
     */
    public boolean lookingAtInto(Slice text, int[] groups)
    {
        return matchInto(text, Anchor.ANCHOR_START, groups);
    }

    /**
     * Matches the complete input and writes byte-offset start/end pairs.
     */
    public boolean matchesInto(Slice text, int[] groups)
    {
        return matchInto(text, Anchor.ANCHOR_BOTH, groups);
    }

    /**
     * Finds a match in {@code [start, end)} while retaining the complete Slice as assertion context.
     * Reported offsets are relative to the complete logical Slice.
     */
    public boolean findInto(Slice text, int start, int end, int[] groups)
    {
        return matchInto(text, start, end, Anchor.UNANCHORED, groups);
    }

    /**
     * Matches at {@code start} within {@code [start, end)} while retaining the complete Slice as assertion context.
     * Reported offsets are relative to the complete logical Slice.
     */
    public boolean lookingAtInto(Slice text, int start, int end, int[] groups)
    {
        return matchInto(text, start, end, Anchor.ANCHOR_START, groups);
    }

    /**
     * Matches all of {@code [start, end)} while retaining the complete Slice as assertion context.
     * Reported offsets are relative to the complete logical Slice.
     */
    public boolean matchesInto(Slice text, int start, int end, int[] groups)
    {
        return matchInto(text, start, end, Anchor.ANCHOR_BOTH, groups);
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
                fixedMatchWorkspaces(onePassWorkspace, bitStateWorkspace, nfaWorkspace));
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
                fixedMatchWorkspaces(onePassWorkspace, bitStateWorkspace, nfaWorkspace));
    }

    boolean matchRegionInto(
            Slice text,
            int contextStart,
            int contextEnd,
            int start,
            int end,
            Anchor anchor,
            int[] groups,
            Re2Matcher workspaceOwner)
    {
        return matchRegionInto(
                text,
                contextStart,
                contextEnd,
                start,
                end,
                anchor,
                groups,
                (MatchWorkspaces) requireNonNull(workspaceOwner, "workspaceOwner is null"));
    }

    private boolean matchRegionInto(
            Slice text,
            int contextStart,
            int contextEnd,
            int start,
            int end,
            Anchor anchor,
            int[] groups,
            MatchWorkspaces workspaces)
    {
        requireNonNull(text, "text is null");
        if (groups != null) {
            if ((groups.length % 2) != 0) {
                throw new IllegalArgumentException("groups length must be even: " + groups.length);
            }
            if (groups.length < 2) {
                throw new IllegalArgumentException("groups must include group 0 start/end");
            }
            Arrays.fill(groups, -1);
        }
        if (usesCompactBoundedCharacterClassForDiagnostics()) {
            return matchCompactBoundedCharacterClass(text, contextStart, end, start, anchor, groups);
        }
        return matchInternal(
                partialProg,
                text,
                contextStart,
                contextEnd,
                start,
                end,
                anchor,
                groups,
                workspaces);
    }

    private boolean matchCompactBoundedCharacterClass(
            Slice text,
            int contextStart,
            int end,
            int start,
            Anchor anchor,
            int[] groups)
    {
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

    private FixedWidthByteSpanMatcher fixedWidthByteSpanMatcher()
    {
        return suffixRegexpOrFixedWidthMatcher instanceof FixedWidthByteSpanMatcher matcher ? matcher : null;
    }

    private Regexp suffixRegexp()
    {
        return (Regexp) suffixRegexpOrFixedWidthMatcher;
    }

    /**
     * Returns an immutable snapshot of the first match, or {@code null} when no match exists.
     */
    public MatchResult findResult(Slice input)
    {
        return matchResult(input, Anchor.UNANCHORED);
    }

    /**
     * Returns an immutable snapshot of the first match in {@code [start, end)}, or {@code null}.
     */
    public MatchResult findResult(Slice input, int start, int end)
    {
        return matchResult(input, start, end, Anchor.UNANCHORED);
    }

    /**
     * Returns an immutable snapshot when the pattern matches the complete input, or {@code null}.
     */
    public MatchResult matchesResult(Slice input)
    {
        return matchResult(input, Anchor.ANCHOR_BOTH);
    }

    /**
     * Returns an immutable snapshot when the pattern matches all of {@code [start, end)}, or {@code null}.
     */
    public MatchResult matchesResult(Slice input, int start, int end)
    {
        return matchResult(input, start, end, Anchor.ANCHOR_BOTH);
    }

    /**
     * Returns an immutable snapshot of a match at the beginning of the input, or {@code null}.
     */
    public MatchResult lookingAtResult(Slice input)
    {
        return matchResult(input, Anchor.ANCHOR_START);
    }

    /**
     * Returns an immutable snapshot of a match at {@code start} within {@code [start, end)}, or {@code null}.
     */
    public MatchResult lookingAtResult(Slice input, int start, int end)
    {
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

    // PERFORMANCE-SENSITIVE ENGINE DISPATCH: phase boundaries, temporary capture storage, and
    // engine-selection branches affect the generated hot path. Do not apply readability-only
    // changes without direct path tests and focused Intel and Graviton benchmarks.
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

    private boolean matchInternal(
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
        requireNonNull(text, "text is null");
        if (contextStart < 0 || contextEnd < contextStart || contextEnd > text.length() ||
                start < contextStart || end < start || end > contextEnd) {
            return false;
        }
        if (groupOffsets != null && (groupOffsets.length % 2) != 0) {
            throw new IllegalArgumentException("groupOffsets length must be even: " + groupOffsets.length);
        }

        Anchor anchorMode = (anchor == null) ? Anchor.UNANCHORED : anchor;

        if (prog == partialProg && booleanPlans.disjointSuffixRepeatMatcher() != null) {
            long span = booleanPlans.disjointSuffixRepeatMatcher().search(text, start, end, anchorMode);
            if (span == Dfa.SEARCH_NO_MATCH) {
                return false;
            }
            if (groupOffsets != null && groupOffsets.length >= 2) {
                Arrays.fill(groupOffsets, -1);
                groupOffsets[0] = (int) (span >>> 32) - contextStart;
                groupOffsets[1] = (int) span - contextStart;
            }
            return true;
        }

        if (prog == partialProg && booleanPlans.wholeInputCapturePlan() != null &&
                start == contextStart && end == contextEnd &&
                Utf8.firstInvalidOffset(logicalContext(text, contextStart, contextEnd)) < 0) {
            booleanPlans.wholeInputCapturePlan().materialize(end - start, groupOffsets);
            return true;
        }

        if (prog == partialProg && booleanPlans.wordRunMatcher() != null) {
            long span = booleanPlans.wordRunMatcher().search(text, contextStart, contextEnd, start, end, anchorMode);
            if (span == Dfa.SEARCH_NO_MATCH) {
                return false;
            }
            if (groupOffsets != null && groupOffsets.length >= 2) {
                Arrays.fill(groupOffsets, -1);
                groupOffsets[0] = (int) (span >>> 32);
                groupOffsets[1] = (int) span;
            }
            return true;
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

        int[] mutableGroupOffsets = groupOffsets;
        boolean writingCallerGroups = groupOffsets != null;
        int captureCount = (mutableGroupOffsets == null) ? 0 : (mutableGroupOffsets.length / 2);

        // A bounded BitState search can extract all groups in one pass. For eligible long inputs,
        // this avoids forward and reverse DFA boundary searches followed by an NFA capture pass.
        if (anchorMode == Anchor.UNANCHORED &&
                mutableGroupOffsets != null &&
                hasBitStateWorkspace(workspaces) &&
                canUseDirectBitStateCapture(searchLength, captureCount)) {
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
        // Use two-phase DFA to find match boundaries without extracting submatches.
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
            if (!dfaSearchSkippedOrFailed) {
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

        BooleanPartialMatchStrategy partialMatchStrategy = selectBooleanPartialMatchStrategy(
                find,
                exactLiteralLength,
                requiredPrefix,
                partialProg);
        find = switch (partialMatchStrategy) {
            case GENERAL -> BooleanPlanKind.GENERAL;
            case EXACT_LITERAL -> BooleanPlanKind.LITERAL_SEARCH;
            case NULLABLE_START -> BooleanPlanKind.NULLABLE_START;
            case CONTAINS -> BooleanPlanKind.CONTAINS;
            case EQUALS -> BooleanPlanKind.EQUALS;
            case STARTS_WITH -> BooleanPlanKind.STARTS_WITH;
            case ENDS_WITH -> BooleanPlanKind.ENDS_WITH;
            case EQUALS_FINAL_LINE -> BooleanPlanKind.EQUALS_FINAL_LINE;
            case ENDS_WITH_FINAL_LINE -> BooleanPlanKind.ENDS_WITH_FINAL_LINE;
            case LOWERED_PROGRAM -> BooleanPlanKind.LOWERED_PROGRAM;
        };
        return new BooleanPlans(literal, partialMatchStrategy, find, lookingAt, matches, null, null, null, null, null);
    }

    private static BooleanPartialMatchStrategy selectBooleanPartialMatchStrategy(
            BooleanPlanKind findPlan,
            int exactLiteralLength,
            byte[] requiredPrefix,
            Prog partialProg)
    {
        return switch (findPlan) {
            case LITERAL_SEARCH -> BooleanPartialMatchStrategy.EXACT_LITERAL;
            case NULLABLE_START -> BooleanPartialMatchStrategy.NULLABLE_START;
            case CONTAINS -> BooleanPartialMatchStrategy.CONTAINS;
            case EQUALS -> BooleanPartialMatchStrategy.EQUALS;
            case STARTS_WITH -> BooleanPartialMatchStrategy.STARTS_WITH;
            case ENDS_WITH -> BooleanPartialMatchStrategy.ENDS_WITH;
            case EQUALS_FINAL_LINE -> BooleanPartialMatchStrategy.EQUALS_FINAL_LINE;
            case ENDS_WITH_FINAL_LINE -> BooleanPartialMatchStrategy.ENDS_WITH_FINAL_LINE;
            case LOWERED_PROGRAM -> BooleanPartialMatchStrategy.LOWERED_PROGRAM;
            case GENERAL -> exactLiteralLength >= 0
                    ? BooleanPartialMatchStrategy.EXACT_LITERAL
                    : (requiredPrefix == null && partialProg.canMatchEmpty()
                               ? BooleanPartialMatchStrategy.NULLABLE_START
                               : BooleanPartialMatchStrategy.GENERAL);
        };
    }

    private static byte encodeBooleanFindStrategy(BooleanPartialMatchStrategy strategy)
    {
        return switch (strategy) {
            case GENERAL -> BOOLEAN_FIND_GENERAL;
            case EXACT_LITERAL -> BOOLEAN_FIND_EXACT_LITERAL;
            case NULLABLE_START -> BOOLEAN_FIND_NULLABLE_START;
            case CONTAINS -> BOOLEAN_FIND_CONTAINS;
            case EQUALS -> BOOLEAN_FIND_EQUALS;
            case STARTS_WITH -> BOOLEAN_FIND_STARTS_WITH;
            case ENDS_WITH -> BOOLEAN_FIND_ENDS_WITH;
            case EQUALS_FINAL_LINE -> BOOLEAN_FIND_EQUALS_FINAL_LINE;
            case ENDS_WITH_FINAL_LINE -> BOOLEAN_FIND_ENDS_WITH_FINAL_LINE;
            case LOWERED_PROGRAM -> BOOLEAN_FIND_LOWERED_PROGRAM;
        };
    }

    private static BooleanPartialMatchStrategy decodeBooleanFindStrategy(byte strategy)
    {
        return switch (strategy) {
            case BOOLEAN_FIND_GENERAL -> BooleanPartialMatchStrategy.GENERAL;
            case BOOLEAN_FIND_EXACT_LITERAL -> BooleanPartialMatchStrategy.EXACT_LITERAL;
            case BOOLEAN_FIND_NULLABLE_START -> BooleanPartialMatchStrategy.NULLABLE_START;
            case BOOLEAN_FIND_CONTAINS -> BooleanPartialMatchStrategy.CONTAINS;
            case BOOLEAN_FIND_EQUALS -> BooleanPartialMatchStrategy.EQUALS;
            case BOOLEAN_FIND_STARTS_WITH -> BooleanPartialMatchStrategy.STARTS_WITH;
            case BOOLEAN_FIND_ENDS_WITH -> BooleanPartialMatchStrategy.ENDS_WITH;
            case BOOLEAN_FIND_EQUALS_FINAL_LINE -> BooleanPartialMatchStrategy.EQUALS_FINAL_LINE;
            case BOOLEAN_FIND_ENDS_WITH_FINAL_LINE -> BooleanPartialMatchStrategy.ENDS_WITH_FINAL_LINE;
            case BOOLEAN_FIND_LOWERED_PROGRAM, BOOLEAN_FIND_LOWERED_PROGRAM_DIRECT_GROUP_ZERO -> BooleanPartialMatchStrategy.LOWERED_PROGRAM;
            case BOOLEAN_FIND_COMPACT_BOUNDED_CHARACTER_CLASS, BOOLEAN_FIND_RETAINED_CHARACTER_CLASS_COUNT_DFA, BOOLEAN_FIND_SINGLE_BYTE -> BooleanPartialMatchStrategy.GENERAL;
            default -> throw new IllegalArgumentException("unknown boolean find strategy: " + strategy);
        };
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
            BooleanPartialMatchStrategy partialMatchStrategy,
            BooleanPlanKind find,
            BooleanPlanKind lookingAt,
            BooleanPlanKind matches,
            Prog loweredProgram,
            TaggedAlternationProgram taggedAlternationProgram,
            WordRunMatcher wordRunMatcher,
            WholeInputCapturePlan wholeInputCapturePlan,
            DisjointSuffixRepeatMatcher disjointSuffixRepeatMatcher)
    {
        private BooleanPlans withTaggedAlternationProgram(TaggedAlternationProgram taggedAlternationProgram)
        {
            return new BooleanPlans(literal, partialMatchStrategy, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher);
        }

        private BooleanPlans withWordRunMatcher(WordRunMatcher wordRunMatcher)
        {
            return new BooleanPlans(literal, partialMatchStrategy, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher);
        }

        private BooleanPlans withWholeInputCapturePlan(WholeInputCapturePlan wholeInputCapturePlan)
        {
            return new BooleanPlans(literal, partialMatchStrategy, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher);
        }

        private BooleanPlans withDisjointSuffixRepeatMatcher(DisjointSuffixRepeatMatcher disjointSuffixRepeatMatcher)
        {
            return new BooleanPlans(literal, partialMatchStrategy, find, lookingAt, matches, loweredProgram, taggedAlternationProgram, wordRunMatcher, wholeInputCapturePlan, disjointSuffixRepeatMatcher);
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
        return workspaces instanceof Re2Matcher || ((FixedMatchWorkspaces) workspaces).bitStateWorkspace() != null;
    }

    private static OnePass.Workspace onePassWorkspace(MatchWorkspaces workspaces)
    {
        if (workspaces instanceof Re2Matcher matcher) {
            return matcher.onePassWorkspace();
        }
        return ((FixedMatchWorkspaces) workspaces).onePassWorkspace();
    }

    private static BitState.Workspace bitStateWorkspace(MatchWorkspaces workspaces)
    {
        if (workspaces instanceof Re2Matcher matcher) {
            return matcher.bitStateWorkspace();
        }
        return ((FixedMatchWorkspaces) workspaces).bitStateWorkspace();
    }

    private static Nfa.Workspace nfaWorkspace(MatchWorkspaces workspaces)
    {
        if (workspaces instanceof Re2Matcher matcher) {
            return matcher.nfaWorkspace();
        }
        return ((FixedMatchWorkspaces) workspaces).nfaWorkspace();
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

    /**
     * Get or lazily compile the reverse program for this regex.
     * <p>
     * Used in {@code matchInto()} for:
     * <ul>
     * <li>Anchor-end quick rejection: when {@code prog.anchorEnd()}, runs reverse DFA anchored
     *     from text end for constant-time rejection of non-matching text
     * <li>Two-phase DFA search: forward DFA finds match end, reverse DFA finds match start
     * </ul>
     * <p>
     * <b>Memory Budget:</b>
     * Reverse program gets 1/3 of {@code maxMemory}, forward gets 2/3.
     * This split matches upstream RE2::Init behavior.
     *
     * @return the reverse program, or null if compilation failed
     */
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

    /**
     * Compile the reverse program with appropriate memory budget (1/3 of total).
     * <p>
     * Memory allocation strategy (matching upstream):
     * <ul>
     * <li>Forward program: 2/3 of maxMemory (more common, can have 2 DFAs: first-match and longest-match)
     * <li>Reverse program: 1/3 of maxMemory (less frequent, only used in two-phase search)
     * </ul>
     * <p>
     * The {@code reversed=true} flag causes:
     * <ul>
     * <li>Right-to-left byte scanning in Dfa.search
     * <li>Anchor flag swapping (^ becomes $, $ becomes ^)
     * <li>Program marked with {@code prog.setReversed(true)}
     * </ul>
     * <p>
     * <b>Optimization:</b> Uses the retained normalized reverse expression instead of re-parsing
     * or re-simplifying the pattern.
     *
     * @return compiled reverse program, or null if compilation failed
     */
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
        // Compile from the stored normalized suffix instead of re-parsing or re-simplifying.
        Prog prog;
        try {
            prog = Compiler.compileNormalized(normalizedReverseRegexp, true, reverseMemory, compilerDialect());
        }
        catch (RegexpCompileMemoryLimitException ignored) {
            return null;
        }
        return prog;
    }

    private static boolean rewrite(DynamicSliceOutput out, Slice rewrite, Slice text, int[] groupOffsets, int captureSlotCount)
    {
        byte[] bytes = rewrite.byteArray();
        int start = rewrite.byteArrayOffset();
        int end = start + rewrite.length();
        for (int i = start; i < end; i++) {
            int c = bytes[i] & 0xFF;
            if (c != '\\') {
                out.writeByte(c);
                continue;
            }
            if (++i >= end) {
                return false;
            }
            c = bytes[i] & 0xFF;
            if (c == '\\') {
                out.writeByte('\\');
                continue;
            }
            if (c < '0' || c > '9') {
                return false;
            }
            int n = c - '0';
            if (n >= captureSlotCount) {
                return false;
            }
            int groupStart = groupOffsets[2 * n];
            int groupEnd = groupOffsets[2 * n + 1];
            if (groupStart >= 0 && groupEnd >= groupStart) {
                appendBytes(out, text, groupStart, groupEnd - groupStart);
            }
        }
        return true;
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

    private static int advanceByRuneIfPossible(byte[] bytes, int position, int end, boolean utf8, DynamicSliceOutput out)
    {
        if (position >= end) {
            return 0;
        }
        if (utf8) {
            long decoded = Utf8.decode(bytes, position, end);
            int width = Utf8.decodedWidth(decoded);
            int cp = Utf8.decodedCodePoint(decoded);
            if (width > 0) {
                if (!(width == 1 && cp == Utf8.RUNE_ERROR && (bytes[position] & 0xFF) >= 0x80)) {
                    appendBytes(out, bytes, position, width);
                    return width;
                }
            }
        }
        appendBytes(out, bytes, position, 1);
        return 1;
    }

    private static void appendBytes(DynamicSliceOutput out, Slice slice, int offset, int length)
    {
        if (length <= 0) {
            return;
        }
        out.writeBytes(slice.byteArray(), slice.byteArrayOffset() + offset, length);
    }

    private static void appendBytes(DynamicSliceOutput out, byte[] bytes, int offset, int length)
    {
        if (length <= 0) {
            return;
        }
        out.writeBytes(bytes, offset, length);
    }
}
