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

import io.airlift.regulator.Re2.ScanPlanMode;
import io.airlift.slice.Slice;

import java.util.Map;

import static java.util.Objects.requireNonNull;

/// A compiled regular subset of the Java [java.util.regex.Pattern] language over UTF-8 Slice
/// inputs.
///
/// Constructs that require backtracking, stateful assertions, or unsupported Java assertion semantics
/// fail during compilation. Match offsets are UTF-8 byte offsets rather than the UTF-16 indexes returned
/// by [java.util.regex.Matcher].
public final class JavaRegexp
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

    private final Re2 pattern;

    private JavaRegexp(Re2 pattern)
    {
        this.pattern = requireNonNull(pattern, "pattern is null");
    }

    /// Validates a pattern and returns its capture metadata without constructing a matching
    /// engine. Parse errors and capture numbering are the same as for [#compile(Slice)];
    /// compilation memory limits are not checked.
    public static PatternInfo parse(Slice pattern)
    {
        return parse(pattern, Options.defaults());
    }

    /// Parses with the current syntax options without retaining the pattern or options.
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
        Re2 compiled = Re2.compileParsedForJava(patternCopy, parsed, parseFlags, options.maxMemory(), options.byteFrequencies(), scanPlanMode);
        return new JavaRegexp(options.generateBytecode() ? Re2.withGeneratedBytecode(compiled) : compiled);
    }

    Re2 pattern()
    {
        return pattern;
    }

    /// Returns whether this compiled pattern has a class generated for it, which only
    /// [Options#setGenerateBytecode(boolean)] enables. Each operation of such a pattern that
    /// would run its specialized scan runs the generated class.
    public boolean usesGeneratedBytecode()
    {
        return pattern.usesGeneratedBytecode();
    }

    public boolean find(Slice input)
    {
        return pattern.find(requireNonNull(input, "input is null"));
    }

    /// Returns the number of non-overlapping matches, including empty matches.
    /// Empty matches advance by one UTF-8 code point as in [Re2Matcher#find()].
    /// No capture values are returned.
    public long count(Slice input)
    {
        return pattern.count(input);
    }

    public boolean lookingAt(Slice input)
    {
        return pattern.lookingAt(requireNonNull(input, "input is null"));
    }

    public boolean matches(Slice input)
    {
        return pattern.matches(requireNonNull(input, "input is null"));
    }

    /// Returns whether the pattern has a match in `[start, end)`.
    public boolean find(Slice input, int start, int end)
    {
        return pattern.find(requireNonNull(input, "input is null"), start, end);
    }

    /// Returns whether the pattern matches at `start` within `[start, end)`.
    public boolean lookingAt(Slice input, int start, int end)
    {
        return pattern.lookingAt(requireNonNull(input, "input is null"), start, end);
    }

    /// Returns whether the pattern matches all of `[start, end)`.
    public boolean matches(Slice input, int start, int end)
    {
        return pattern.matches(requireNonNull(input, "input is null"), start, end);
    }

    /// Creates a mutable matcher retaining every capturing group.
    public Re2Matcher matcher(Slice input)
    {
        return pattern.matcher(requireNonNull(input, "input is null"));
    }

    /// Creates a reusable matcher retaining group zero and the requested prefix of capturing groups.
    public Re2Matcher matcher(Slice input, int retainedCapturingGroupCount)
    {
        return pattern.matcher(requireNonNull(input, "input is null"), retainedCapturingGroupCount);
    }

    /// Finds a match and writes byte-offset start/end pairs into caller-owned storage.
    public boolean findInto(Slice input, int[] groups)
    {
        return pattern.findInto(requireNonNull(input, "input is null"), groups);
    }

    /// Finds a match in `[start, end)` and writes byte-offset start/end pairs.
    public boolean findInto(Slice input, int start, int end, int[] groups)
    {
        return pattern.findInto(requireNonNull(input, "input is null"), start, end, groups);
    }

    /// Matches at the beginning of the input and writes byte-offset start/end pairs.
    public boolean lookingAtInto(Slice input, int[] groups)
    {
        return pattern.lookingAtInto(requireNonNull(input, "input is null"), groups);
    }

    /// Matches at `start` within `[start, end)` and writes byte-offset start/end pairs.
    public boolean lookingAtInto(Slice input, int start, int end, int[] groups)
    {
        return pattern.lookingAtInto(requireNonNull(input, "input is null"), start, end, groups);
    }

    /// Matches the complete input and writes byte-offset start/end pairs.
    public boolean matchesInto(Slice input, int[] groups)
    {
        return pattern.matchesInto(requireNonNull(input, "input is null"), groups);
    }

    /// Matches all of `[start, end)` and writes byte-offset start/end pairs.
    public boolean matchesInto(Slice input, int start, int end, int[] groups)
    {
        return pattern.matchesInto(requireNonNull(input, "input is null"), start, end, groups);
    }

    /// Returns the first match, or `null` when there is no match.
    public MatchResult findResult(Slice input)
    {
        return pattern.findResult(requireNonNull(input, "input is null"));
    }

    /// Returns the first match in `[start, end)`, or `null` when there is no match.
    public MatchResult findResult(Slice input, int start, int end)
    {
        return pattern.findResult(requireNonNull(input, "input is null"), start, end);
    }

    /// Returns the match at the beginning of the input, or `null` when there is no match.
    public MatchResult lookingAtResult(Slice input)
    {
        return pattern.lookingAtResult(requireNonNull(input, "input is null"));
    }

    /// Returns the match at `start` within `[start, end)`, or `null`.
    public MatchResult lookingAtResult(Slice input, int start, int end)
    {
        return pattern.lookingAtResult(requireNonNull(input, "input is null"), start, end);
    }

    /// Returns the complete-input match, or `null` when there is no match.
    public MatchResult matchesResult(Slice input)
    {
        return pattern.matchesResult(requireNonNull(input, "input is null"));
    }

    /// Returns the complete-region match, or `null` when there is no match.
    public MatchResult matchesResult(Slice input, int start, int end)
    {
        return pattern.matchesResult(requireNonNull(input, "input is null"), start, end);
    }

    public int capturingGroupCount()
    {
        return pattern.capturingGroupCount();
    }

    public Map<String, Integer> namedCapturingGroups()
    {
        return pattern.namedCapturingGroups();
    }
}
