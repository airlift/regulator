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

import static java.util.Objects.requireNonNull;

/// A compiled RE2 pattern.
///
/// Instances are immutable and thread-safe. Matching operates directly on [Slice] byte ranges;
/// offsets are byte offsets relative to the logical Slice, not its backing array.
@SuppressWarnings("CharUsedInArithmeticContext")
public sealed class Re2
        extends CompiledPattern
        permits Re2.ByteTableRe2,
                Re2.ClassRunRe2,
                Re2.ExactLiteralRe2,
                Re2.FixedWidthRe2,
                Re2.LiteralAlternationRe2,
                Re2.LiteralKernelRe2,
                Re2.LiteralSetRe2,
                Re2.ScanPlanRe2
{
    /// The result of replacing every non-overlapping match.
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP",
            justification = "Slice values are deliberate zero-copy results")
    public record ReplaceAllResult(int replacementCount, Slice result) {}

    /// Mutable compilation options. Compilation snapshots every option, so later changes do not
    /// affect an existing [Re2]. Options instances are not thread-safe.
    public static final class Options
    {
        public static final int DEFAULT_MAX_MEMORY = 32 << 20;

        public enum Encoding
        {
            UTF8,
            LATIN1,
        }

        private long maxMemory = DEFAULT_MAX_MEMORY;
        private ByteFrequencies byteFrequencies = ByteFrequencies.uniform();
        private boolean generateBytecode;
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

        /// Creates options for RE2's Perl-like UTF-8 syntax and leftmost-first matching.
        public static Options defaults()
        {
            return new Options();
        }

        /// Creates UTF-8 POSIX options with leftmost-longest matching.
        public static Options posix()
        {
            Options options = new Options();
            options.posixSyntax = true;
            options.longestMatch = true;
            return options;
        }

        /// Creates Perl-like options that interpret patterns and inputs as Latin-1 bytes.
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
            copy.byteFrequencies = byteFrequencies;
            copy.generateBytecode = generateBytecode;
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

        /// Sets the positive compilation, DFA-cache and capture-search memory budget in bytes.
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
        /// class cannot be generated, compile as they do without this option. Latin-1 and
        /// longest-match patterns have no specialized scan, so they are never eligible. Disabled by
        /// default.
        ///
        /// @see Re2#usesGeneratedBytecode()
        public Options setGenerateBytecode(boolean generateBytecode)
        {
            this.generateBytecode = generateBytecode;
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

    /// The inputs of a compile, which decide equality: the pattern bytes and every option that
    /// can change what the compile builds, including those that change only performance, such as
    /// the memory budget, so that a cache keyed on patterns never returns one compiled differently.
    /// Each shape class and generated class keeps the inputs of the compile that built it, which
    /// adds this object of about 40 bytes to what a pattern retains, and for equality also keeps
    /// alive a custom [ByteFrequencies] distribution, a table of 256 doubles of about 2 KB.
    private record CompileInputs(Slice pattern, int parseFlags, boolean longestMatch, long maxMemory, ByteFrequencies byteFrequencies, boolean generateBytecode, ScanPlanMode scanPlanMode) {}

    private final CompileInputs compileInputs;
    // The hash of compileInputs, computed when the pattern compiles.
    private final int hash;

    private Re2(Analysis analysis, CompileInputs compileInputs)
    {
        super(analysis);
        this.compileInputs = compileInputs;
        this.hash = compileInputs.hashCode();
    }

    private Re2(Re2 pattern)
    {
        super(pattern);
        this.compileInputs = pattern.compileInputs;
        this.hash = pattern.hash;
    }

    /// Constructs an instance of the analyzed pattern's shape class.
    private static Re2 create(Analysis analysis, CompileInputs compileInputs)
    {
        return switch (analysis.shape()) {
            case ENGINE -> new Re2(analysis, compileInputs);
            case EXACT_LITERAL -> new ExactLiteralRe2(analysis, compileInputs);
            case LITERAL_KERNEL -> new LiteralKernelRe2(analysis, compileInputs);
            case LITERAL_ALTERNATION -> analysis.literalSetExtendingLiterals() == null ? new LiteralAlternationRe2(analysis, compileInputs) : new LiteralSetRe2(analysis, compileInputs);
            case FIXED_WIDTH -> new FixedWidthRe2(analysis, compileInputs);
            case CLASS_RUN -> new ClassRunRe2(analysis, compileInputs);
            case BYTE_TABLE -> new ByteTableRe2(analysis, compileInputs);
            case SCAN_PLAN -> new ScanPlanRe2(analysis, compileInputs);
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
        return RegexpParser.parse(pattern, options.parseFlags()).patternInfo();
    }

    /// Compiles a copied snapshot of `pattern` with [Options#defaults()].
    public static Re2 compile(Slice pattern)
    {
        return compile(pattern, Options.defaults());
    }

    static Re2 compile(Slice pattern, int flags)
    {
        requireNonNull(pattern, "pattern is null");
        return compile(pattern.copy(), flags, Options.DEFAULT_MAX_MEMORY, ByteFrequencies.uniform(), false, ScanPlanMode.DEFAULT);
    }

    /// Compiles a copied snapshot of `pattern` and the current option values.
    public static Re2 compile(Slice pattern, Options options)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        return compile(pattern.copy(), options, ScanPlanMode.DEFAULT);
    }

    /// Compiles `pattern` as [#compile(Slice,Options)] does, but without a scan plan,
    /// so tests and benchmarks can compare a plan with the ordinary engine.
    static Re2 compileWithoutScanPlanForTesting(Slice pattern, Options options)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        return compile(pattern.copy(), options, ScanPlanMode.DISABLED);
    }

    /// Compiles `pattern` as [#compile(Slice,Options)] does, but a scan plan
    /// continues every unanchored search on the ordinary engine before its first attempt.
    static Re2 compileForcingScanPlanHandoffForTesting(Slice pattern, Options options)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        return compile(pattern.copy(), options, ScanPlanMode.FORCE_HANDOFF);
    }

    private static Re2 compile(Slice pattern, Options options, ScanPlanMode scanPlanMode)
    {
        int parseFlags = options.parseFlags();
        return compileParsed(pattern, RegexpParser.parse(pattern, parseFlags), parseFlags, options, scanPlanMode);
    }

    /// Compiles `parsed`, the parse of `pattern` under `parseFlags`, as
    /// [#compile(Slice,Options)] compiles `pattern`. A compiled pattern does not keep its parse
    /// tree, so FilteredRe2 parses the pattern itself and builds its prefilter from the tree. The
    /// caller passes a private snapshot that it never mutates.
    static Re2 compileParsed(Slice pattern, ParseResult parsed, int parseFlags, Options options)
    {
        return compileParsed(pattern, parsed, parseFlags, options, ScanPlanMode.DEFAULT);
    }

    private static Re2 compileParsed(Slice pattern, ParseResult parsed, int parseFlags, Options options, ScanPlanMode scanPlanMode)
    {
        CompileInputs inputs = new CompileInputs(pattern, parseFlags, options.longestMatch(), options.maxMemory(), options.byteFrequencies(), options.generateBytecode(), scanPlanMode);
        Re2 compiled = create(build(pattern, parsed, parseFlags, inputs.maxMemory(), inputs.byteFrequencies(), inputs.longestMatch(), Compiler.Dialect.RE2, ScanPlan.Language.RE2, scanPlanMode), inputs);
        if (inputs.generateBytecode()) {
            Re2 generated = CompiledScanRe2.create(compiled);
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

    /// Returns whether `other` is an RE2 pattern compiled from equal pattern bytes with equal
    /// effective option values, including those that change only performance, such as the memory
    /// budget. Two such compiles are equal whichever class each compile built, generated or not.
    @Override
    public final boolean equals(Object other)
    {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Re2 that)) {
            return false;
        }
        return hash == that.hash && compileInputs.equals(that.compileInputs);
    }

    @Override
    public final int hashCode()
    {
        return hash;
    }

    /// Replaces the first match using RE2 rewrite syntax (`\0` through `\9`).
    /// Returns `text` unchanged when there is no match.
    ///
    /// @throws RegexpRewriteException if the rewrite is invalid for this pattern
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

    /// Replaces every non-overlapping match using RE2 rewrite syntax.
    ///
    /// @throws RegexpRewriteException if the rewrite is invalid for this pattern
    public ReplaceAllResult replaceAll(Slice text, Slice rewrite)
    {
        return replaceAll(text, rewrite, null);
    }

    /// Runs [#replaceAll] and returns the loop's count, when it ended, of scans in a row that
    /// found the long-window literal or one-of bytes near their start, or -1 where a scan found
    /// none; see `CompiledPattern.nearRequiredLiteralScansInLoop`. On text where every scan finds
    /// them near its start, that count is every scan the call made, which the loop's back-off
    /// stops at `RegexpMatcher.NEAR_REQUIRED_LITERAL_SCAN_LIMIT`. Only tests call it.
    int replaceAllNearLiteralScansForDiagnostics(Slice text, Slice rewrite)
    {
        int[] nearScans = new int[1];
        replaceAll(text, rewrite, nearScans);
        return nearScans[0];
    }

    // A null `nearScansResult`, as every production call passes, records nothing.
    private ReplaceAllResult replaceAll(Slice text, Slice rewrite, int[] nearScansResult)
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
        // How many scans in a row found the long-window literal or one-of bytes near their start;
        // see nearRequiredLiteralScansInLoop.
        int nearScans = 0;
        DynamicSliceOutput out = new DynamicSliceOutput(text.length() + rewrite.length());
        // The bound of the whole text, computed once, ends every search, so no search computes
        // its own; see loopSearchEnd.
        int searchEnd = loopSearchEnd(text);

        while (position <= end) {
            int start = position - base;
            if (searchEnd < start) {
                break;
            }
            nearScans = nearRequiredLiteralScansInLoop(text, start, searchEnd, nearScans);
            if (nearScans < 0 || !matchIntoAfterLoopCheck(text, start, searchEnd, groupOffsets)) {
                break;
            }

            int matchStart = base + groupOffsets[0];
            int matchEnd = base + groupOffsets[1];
            if (position < matchStart) {
                appendBytes(out, bytes, position, matchStart - position);
            }

            if (matchStart == lastEnd && matchStart == matchEnd) {
                // Disallow empty match at end of last match: skip ahead by one rune/byte.
                int advance = advanceByRuneIfPossible(bytes, position, end, (flags() & Regexp.LATIN1) == 0, out);
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

        if (nearScansResult != null) {
            nearScansResult[0] = nearScans;
        }
        if (count == 0) {
            return new ReplaceAllResult(0, text);
        }
        if (position < end) {
            appendBytes(out, bytes, position, end - position);
        }
        return new ReplaceAllResult(count, out.slice());
    }

    /// Expands an RE2 rewrite against the first match, or returns `null` when no match exists.
    ///
    /// @throws RegexpRewriteException if the rewrite is invalid for this pattern
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

    /// Returns a pattern that matches the supplied bytes literally.
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

    private static Re2 compile(Slice pattern, int flags, long maxMemory, ByteFrequencies byteFrequencies, boolean longestMatch, ScanPlanMode scanPlanMode)
    {
        requireNonNull(pattern, "pattern is null");
        ParseResult parsed = RegexpParser.parse(pattern, flags);
        return create(build(pattern, parsed, flags, maxMemory, byteFrequencies, longestMatch, Compiler.Dialect.RE2, ScanPlan.Language.RE2, scanPlanMode), new CompileInputs(pattern, flags, longestMatch, maxMemory, byteFrequencies, false, scanPlanMode));
    }

    /// The caller passes a private snapshot that it never mutates.
    static Re2 compileParsed(Slice pattern, ParseResult parsed, int flags)
    {
        return compileParsed(pattern, parsed, flags, Options.DEFAULT_MAX_MEMORY);
    }

    /// The caller passes a private snapshot that it never mutates.
    static Re2 compileParsed(Slice pattern, ParseResult parsed, int flags, long maxMemory)
    {
        return compileParsed(pattern, parsed, flags, maxMemory, ByteFrequencies.uniform());
    }

    /// The caller passes a private snapshot that it never mutates.
    static Re2 compileParsed(Slice pattern, ParseResult parsed, int flags, long maxMemory, ByteFrequencies byteFrequencies)
    {
        return create(analyzeParsed(pattern, parsed, flags, maxMemory, byteFrequencies, ScanPlan.Language.RE2, ScanPlanMode.DISABLED), new CompileInputs(pattern, flags, false, maxMemory, byteFrequencies, false, ScanPlanMode.DISABLED));
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

    private static int advanceByRuneIfPossible(byte[] bytes, int position, int end, boolean utf8, DynamicSliceOutput out)
    {
        if (position >= end) {
            return 0;
        }
        int width = utf8 ? globalReplaceRuneWidth(bytes, position, end) : 1;
        appendBytes(out, bytes, position, width);
        return width;
    }

    /// Returns the width of the rune at `position`, which must be before `end`, as upstream
    /// `RE2::GlobalReplace` reads it to step past an empty match at the previous match end. It
    /// steps 1 byte where `fullrune` or `chartorune` from `util/rune.cc` reports a decoding error,
    /// or where its own check finds a value above U+10FFFF. That rejects what [Utf8#decode]
    /// rejects, except that an encoded surrogate is read as one rune.
    private static int globalReplaceRuneWidth(byte[] bytes, int position, int end)
    {
        if (Utf8.isEncodedSurrogate(bytes, position, end)) {
            return 3;
        }
        return Utf8.decodedWidth(Utf8.decode(bytes, position, end));
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

    /// A pattern of the [exact-literal shape][Shape#EXACT_LITERAL].
    static final class ExactLiteralRe2
            extends Re2
    {
        private ExactLiteralRe2(Analysis analysis, CompileInputs compileInputs)
        {
            super(analysis, compileInputs);
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
    static final class LiteralKernelRe2
            extends Re2
    {
        private LiteralKernelRe2(Analysis analysis, CompileInputs compileInputs)
        {
            super(analysis, compileInputs);
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

        @Override
        boolean matchInternal(Prog prog, Slice text, int contextStart, int contextEnd, int start, int end, Anchor anchor, int[] groupOffsets, MatchWorkspaces workspaces)
        {
            return matchInternalOnLiteralKernel(prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces);
        }

        @Override
        boolean matchInto(Slice text, int start, int end, Anchor anchor, int[] groups, OnePass.Workspace onePassWorkspace, BitState.Workspace bitStateWorkspace, Nfa.Workspace nfaWorkspace)
        {
            // The span search runs here, not in a shared helper; see oneShotDotStarLiteral.
            DotStarLiteralSpanMatcher dotStarLiteral = oneShotDotStarLiteral(text, start, end, anchor, groups);
            if (dotStarLiteral == null) {
                return super.matchInto(text, start, end, anchor, groups, onePassWorkspace, bitStateWorkspace, nfaWorkspace);
            }
            return setDotStarLiteralGroups(dotStarLiteral.findSpan(text, start, end, null), groups);
        }
    }

    /// A pattern of the [literal-alternation shape][Shape#LITERAL_ALTERNATION].
    static final class LiteralAlternationRe2
            extends Re2
    {
        private final boolean literalSetCount;

        private LiteralAlternationRe2(Analysis analysis, CompileInputs compileInputs)
        {
            super(analysis, compileInputs);
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
    static final class LiteralSetRe2
            extends Re2
    {
        // The literals that a full match reads beside the literal set.
        private final LiteralSetSpanMatcher.ExtendingLiterals extendingLiterals;

        private LiteralSetRe2(Analysis analysis, CompileInputs compileInputs)
        {
            super(analysis, compileInputs);
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
    static final class FixedWidthRe2
            extends Re2
    {
        private FixedWidthRe2(Analysis analysis, CompileInputs compileInputs)
        {
            super(analysis, compileInputs);
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
    static final class ClassRunRe2
            extends Re2
    {
        private final boolean retainedClassCountDfa;

        private ClassRunRe2(Analysis analysis, CompileInputs compileInputs)
        {
            super(analysis, compileInputs);
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
    static final class ByteTableRe2
            extends Re2
    {
        private ByteTableRe2(Analysis analysis, CompileInputs compileInputs)
        {
            super(analysis, compileInputs);
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
    /// through [CompiledScanRe2].
    static sealed class ScanPlanRe2
            extends Re2
            permits CompiledScanRe2
    {
        private final boolean ordinaryShortBooleanMatch;
        private final boolean singleAttemptCount;
        private final boolean literalKernelFind;

        private ScanPlanRe2(Analysis analysis, CompileInputs compileInputs)
        {
            super(analysis, compileInputs);
            this.ordinaryShortBooleanMatch = analysis.ordinaryShortBooleanMatch();
            this.singleAttemptCount = analysis.singleAttemptCount();
            this.literalKernelFind = analysis.literalKernelFind();
        }

        /// Copies `pattern`, which the caller then discards.
        private ScanPlanRe2(ScanPlanRe2 pattern)
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

    /// An RE2 pattern whose case-sensitive scan plan was compiled to its own hidden class,
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
    abstract static non-sealed class CompiledScanRe2
            extends ScanPlanRe2
    {
        /// Copies `pattern`, which the caller then discards. Only the generated subclass calls
        /// this.
        CompiledScanRe2(ScanPlanRe2 pattern)
        {
            super(pattern);
        }

        /// Returns a copy of `pattern` with a generated class, or `null` when the
        /// pattern is not eligible or the class cannot be generated. The declared type keeps
        /// verification of the base class from loading this class.
        static Re2 create(Re2 pattern)
        {
            if (!(pattern instanceof ScanPlanRe2 scanPlanPattern)) {
                return null;
            }
            return scanPlanPattern.generateScanPattern(CompiledScanRe2.class);
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
