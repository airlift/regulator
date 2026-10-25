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
import java.util.Map;

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
/// properties read Regulator's Unicode database. Repetition counts above 1,000 fail during
/// compilation. Match offsets are byte offsets, as in the crate.
public final class RustRegexp
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

    private final Re2 pattern;
    // The crate's bytes API, where an empty match may split a UTF-8 character.
    private final boolean bytes;

    private RustRegexp(Re2 pattern)
    {
        this.pattern = requireNonNull(pattern, "pattern is null");
        this.bytes = (pattern.flags() & Regexp.LATIN1) != 0;
    }

    public static RustRegexp compile(Slice pattern)
    {
        return compile(pattern, Options.defaults());
    }

    /// Compiles a copied snapshot of `pattern` and the current option values.
    public static RustRegexp compile(Slice pattern, Options options)
    {
        return compile(pattern, options, true, false);
    }

    /// Compiles `pattern` as [#compile(Slice,Options)] does, but without a scan plan,
    /// so tests and benchmarks can compare a plan with the ordinary engine.
    static RustRegexp compileWithoutScanPlanForTesting(Slice pattern, Options options)
    {
        return compile(pattern, options, false, false);
    }

    /// Compiles `pattern` as [#compile(Slice,Options)] does, but a scan plan
    /// continues every unanchored search on the ordinary engine before its first attempt.
    static RustRegexp compileForcingScanPlanHandoffForTesting(Slice pattern, Options options)
    {
        return compile(pattern, options, true, true);
    }

    private static RustRegexp compile(Slice pattern, Options options, boolean scanPlan, boolean forceScanPlanHandoff)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        Slice patternCopy = pattern.copy();
        ParseResult parsed = RustRegexpParser.parse(patternCopy, options.syntaxFlags(), options.nestLimit());
        int compileFlags = options.compileFlags();
        // Byte mode builds no scan plan. Only byte constructs carry the Latin-1 flag the analysis
        // checks, so the analysis alone would still admit an ASCII-only byte-mode pattern.
        Re2 compiled = scanPlan && (compileFlags & Regexp.LATIN1) == 0
                ? Re2.compileParsedForRust(patternCopy, parsed, compileFlags, options.maxMemory(), options.byteFrequencies(), forceScanPlanHandoff)
                : Re2.compileParsed(patternCopy, parsed, compileFlags, options.maxMemory(), options.byteFrequencies());
        return new RustRegexp(options.generateBytecode() ? Re2.withGeneratedBytecode(compiled) : compiled);
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
        requireNonNull(input, "input is null");
        if (!pattern.canMatchEmpty()) {
            return pattern.find(input);
        }
        return search(input, 0, input.length(), null);
    }

    /// Returns the number of non-overlapping matches, including empty matches.
    /// As in the Rust crate, an empty match that ends where the previous match ended is skipped,
    /// and outside byte mode, so is one that splits a UTF-8 character. No capture values are
    /// returned.
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
        return search(requireNonNull(input, "input is null"), start, end, null);
    }

    /// Returns whether the pattern matches at `start` within `[start, end)`.
    public boolean lookingAt(Slice input, int start, int end)
    {
        return pattern.lookingAt(requireNonNull(input, "input is null"), start, end) && isSearchStart(input, start);
    }

    /// Returns whether the pattern matches all of `[start, end)`.
    public boolean matches(Slice input, int start, int end)
    {
        return pattern.matches(requireNonNull(input, "input is null"), start, end) && isSearchStart(input, start);
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
        return findInto(requireNonNull(input, "input is null"), 0, input.length(), groups);
    }

    /// Finds a match in `[start, end)` and writes byte-offset start/end pairs.
    public boolean findInto(Slice input, int start, int end, int[] groups)
    {
        return search(requireNonNull(input, "input is null"), start, end, groups);
    }

    /// Matches at the beginning of the input and writes byte-offset start/end pairs.
    public boolean lookingAtInto(Slice input, int[] groups)
    {
        return pattern.lookingAtInto(requireNonNull(input, "input is null"), groups);
    }

    /// Matches at `start` within `[start, end)` and writes byte-offset start/end pairs.
    public boolean lookingAtInto(Slice input, int start, int end, int[] groups)
    {
        boolean matched = pattern.lookingAtInto(requireNonNull(input, "input is null"), start, end, groups);
        return anchoredAt(input, start, matched, groups);
    }

    /// Matches the complete input and writes byte-offset start/end pairs.
    public boolean matchesInto(Slice input, int[] groups)
    {
        return pattern.matchesInto(requireNonNull(input, "input is null"), groups);
    }

    /// Matches all of `[start, end)` and writes byte-offset start/end pairs.
    public boolean matchesInto(Slice input, int start, int end, int[] groups)
    {
        boolean matched = pattern.matchesInto(requireNonNull(input, "input is null"), start, end, groups);
        return anchoredAt(input, start, matched, groups);
    }

    /// Returns the first match, or `null` when there is no match.
    public MatchResult findResult(Slice input)
    {
        return findResult(requireNonNull(input, "input is null"), 0, input.length());
    }

    /// Returns the first match in `[start, end)`, or `null` when there is no match.
    public MatchResult findResult(Slice input, int start, int end)
    {
        requireNonNull(input, "input is null");
        int[] groups = new int[2 * (capturingGroupCount() + 1)];
        if (!search(input, start, end, groups)) {
            return null;
        }
        return new MatchResult(input, groups, namedCapturingGroups());
    }

    /// Returns the match at the beginning of the input, or `null` when there is no match.
    public MatchResult lookingAtResult(Slice input)
    {
        return pattern.lookingAtResult(requireNonNull(input, "input is null"));
    }

    /// Returns the match at `start` within `[start, end)`, or `null`.
    public MatchResult lookingAtResult(Slice input, int start, int end)
    {
        MatchResult result = pattern.lookingAtResult(requireNonNull(input, "input is null"), start, end);
        return isSearchStart(input, start) ? result : null;
    }

    /// Returns the complete-input match, or `null` when there is no match.
    public MatchResult matchesResult(Slice input)
    {
        return pattern.matchesResult(requireNonNull(input, "input is null"));
    }

    /// Returns the complete-region match, or `null` when there is no match.
    public MatchResult matchesResult(Slice input, int start, int end)
    {
        MatchResult result = pattern.matchesResult(requireNonNull(input, "input is null"), start, end);
        return isSearchStart(input, start) ? result : null;
    }

    public int capturingGroupCount()
    {
        return pattern.capturingGroupCount();
    }

    public Map<String, Integer> namedCapturingGroups()
    {
        return pattern.namedCapturingGroups();
    }

    // Every unanchored search of a range comes here. Outside byte mode, the crate skips an empty
    // match that splits a UTF-8 character and searches again one byte later.
    private boolean search(Slice input, int start, int end, int[] groups)
    {
        if (bytes || !pattern.canMatchEmpty()) {
            return pattern.findInto(input, start, end, groups);
        }
        int[] span = groups == null ? new int[2] : groups;
        int searchStart = start;
        while (pattern.findInto(input, searchStart, end, span)) {
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
}
