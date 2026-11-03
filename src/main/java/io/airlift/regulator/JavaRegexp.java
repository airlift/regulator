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
        permits JavaRegexp.CompiledScanJavaRegexp
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

        /// Returns the compilation and DFA-cache memory budget in bytes.
        long maxMemory()
        {
            return maxMemory;
        }

        /// Sets the positive compilation and DFA-cache memory budget in bytes.
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

        /// Experimental. When enabled, compilation generates a hidden class for an eligible pattern,
        /// so each operation that would run its specialized scan runs dedicated bytecode instead.
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
        JavaRegexp compiled = new JavaRegexp(analyzeParsed(patternCopy, parsed, parseFlags, options.maxMemory(), options.byteFrequencies(), ScanPlan.Language.JAVA, scanPlanMode));
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

    /// A Java pattern whose case-sensitive scan plan runs a class generated for it.
    private static final class CompiledScanJavaRegexp
            extends JavaRegexp
    {
        private final CompiledRe2Scan scan;
        // Whether the plan is start-anchored, so its attempt runs alone; otherwise it searches.
        private final boolean anchored;
        // Whether full-Slice find hands off to the ordinary engine, so it runs the capture-free
        // search before the handoff.
        private final boolean ordinaryHandoff;

        private CompiledScanJavaRegexp(JavaRegexp pattern, CompiledRe2Scan scan)
        {
            super(pattern);
            this.scan = scan;
            this.anchored = pattern.caseSensitiveScanPlan().isAnchoredStart();
            this.ordinaryHandoff = pattern.generatedFindHandsOff();
        }

        /// Returns a copy of `pattern` with a generated class, or `null` when the
        /// pattern is not eligible or the class cannot be generated. The declared type keeps
        /// verification of the base class from loading this class.
        static JavaRegexp create(JavaRegexp pattern)
        {
            CompiledRe2Scan scan = pattern.generateScanExecutor();
            return scan == null ? null : new CompiledScanJavaRegexp(pattern, scan);
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

        @Override
        boolean findWithScanPlan(Slice input, byte strategy)
        {
            return findWithGeneratedScan(scan, anchored, ordinaryHandoff, input);
        }

        @Override
        long countUnanchoredScanMatches(Slice text, boolean asciiFolded)
        {
            return countWithGeneratedScan(scan, text);
        }

        @Override
        boolean findWithStartAnchoredScanPlan(ScanPlan plan, Slice text, int contextStart, int contextEnd, int[] groups)
        {
            return findWithGeneratedStartAnchoredScan(scan, text, contextStart, contextEnd, groups);
        }

        @Override
        boolean matchInternal(Prog prog, Slice text, int contextStart, int contextEnd, int start, int end, Anchor anchor, int[] groupOffsets, MatchWorkspaces workspaces)
        {
            return matchInternalWithGeneratedScan(scan, anchored, prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces);
        }
    }
}
