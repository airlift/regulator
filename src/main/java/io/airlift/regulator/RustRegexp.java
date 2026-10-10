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

/**
 * A compiled regular expression in the pattern language of the Rust {@code regex} crate, pinned at
 * regex 1.13.1, over UTF-8 Slice inputs.
 * <p>
 * Matching follows the crate's leftmost-first semantics on valid UTF-8 input. Searches skip an
 * empty match that splits a UTF-8 character, iteration skips an empty match adjacent to the
 * previous match, and a search of a range or matcher region sees the input outside it as
 * assertion context, as in the crate's {@code Input::range}. Unicode properties read Regulator's
 * Unicode database. Repetition counts above 1,000 fail during compilation. Match offsets are UTF-8
 * byte offsets, as in the crate.
 */
public final class RustRegexp
{
    /**
     * Mutable Rust-language compilation options, matching the crate's {@code RegexBuilder} flags.
     * Compilation snapshots every option, so later changes do not affect an existing
     * {@link RustRegexp}. Options instances are not thread-safe.
     */
    public static final class Options
    {
        private boolean caseInsensitive;
        private boolean multiline;
        private boolean dotMatchesNewline;
        private boolean crlf;
        private boolean swapGreed;
        private boolean comments;
        private boolean unicode = true;
        private long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
        private ByteFrequencies byteFrequencies = ByteFrequencies.uniform();

        private Options() {}

        public static Options defaults()
        {
            return new Options();
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

        /**
         * Sets whether, in multi-line mode, {@code ^} and {@code $} treat {@code \r\n} as a line
         * end, and {@code .} excludes {@code \r} unless it matches newlines; this is the {@code R} flag.
         */
        public Options setCrlf(boolean crlf)
        {
            this.crlf = crlf;
            return this;
        }

        /**
         * Sets whether greedy and lazy repetitions swap meaning, which is the {@code U} flag.
         */
        public Options setSwapGreed(boolean swapGreed)
        {
            this.swapGreed = swapGreed;
            return this;
        }

        /**
         * Sets whether whitespace and {@code #} comments in the pattern are ignored, which is the
         * crate's {@code ignore_whitespace} option and the {@code x} flag.
         */
        public Options setComments(boolean comments)
        {
            this.comments = comments;
            return this;
        }

        /**
         * Sets whether Unicode mode is enabled, which is the default. Without it, classes and case
         * folding are ASCII-only, and constructs that could match part of a UTF-8 character fail.
         */
        public Options setUnicode(boolean unicode)
        {
            this.unicode = unicode;
            return this;
        }

        /**
         * Returns the compilation and DFA-cache memory budget in bytes.
         */
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

        /**
         * Returns the byte-frequency distribution that chooses literal probe bytes.
         */
        ByteFrequencies byteFrequencies()
        {
            return byteFrequencies;
        }

        /**
         * Sets the byte-frequency distribution that chooses which byte of a literal a candidate
         * scan probes. Compilation keeps only the chosen offsets, not the distribution. The default,
         * {@link ByteFrequencies#uniform()}, keeps first-byte scanning.
         */
        public Options setByteFrequencies(ByteFrequencies byteFrequencies)
        {
            this.byteFrequencies = requireNonNull(byteFrequencies, "byteFrequencies is null");
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
            return flags;
        }
    }

    private final Re2 pattern;

    private RustRegexp(Re2 pattern)
    {
        this.pattern = requireNonNull(pattern, "pattern is null");
    }

    public static RustRegexp compile(Slice pattern)
    {
        return compile(pattern, Options.defaults());
    }

    /**
     * Compiles a copied snapshot of {@code pattern} and the current option values.
     */
    public static RustRegexp compile(Slice pattern, Options options)
    {
        requireNonNull(pattern, "pattern is null");
        requireNonNull(options, "options is null");
        Slice patternCopy = pattern.copy();
        ParseResult parsed = RustRegexpParser.parse(patternCopy, options.syntaxFlags());
        return new RustRegexp(Re2.compileParsed(patternCopy, parsed, Regexp.LIKE_PERL | Regexp.RUST_SEARCH, options.maxMemory(), options.byteFrequencies()));
    }

    Re2 pattern()
    {
        return pattern;
    }

    public boolean find(Slice input)
    {
        requireNonNull(input, "input is null");
        if (!pattern.canMatchEmpty()) {
            return pattern.find(input);
        }
        return search(input, 0, input.length(), null);
    }

    /**
     * Returns the number of non-overlapping matches, including empty matches.
     * As in the Rust crate, an empty match that ends where the previous match ended or that splits
     * a UTF-8 character is skipped. No capture values are returned.
     */
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

    /**
     * Returns whether the pattern has a match in {@code [start, end)}.
     */
    public boolean find(Slice input, int start, int end)
    {
        return search(requireNonNull(input, "input is null"), start, end, null);
    }

    /**
     * Returns whether the pattern matches at {@code start} within {@code [start, end)}.
     */
    public boolean lookingAt(Slice input, int start, int end)
    {
        return pattern.lookingAt(requireNonNull(input, "input is null"), start, end) && Utf8.isCharacterBoundary(input, start);
    }

    /**
     * Returns whether the pattern matches all of {@code [start, end)}.
     */
    public boolean matches(Slice input, int start, int end)
    {
        return pattern.matches(requireNonNull(input, "input is null"), start, end) && Utf8.isCharacterBoundary(input, start);
    }

    /**
     * Creates a mutable matcher retaining every capturing group.
     */
    public Re2Matcher matcher(Slice input)
    {
        return pattern.matcher(requireNonNull(input, "input is null"));
    }

    /**
     * Creates a reusable matcher retaining group zero and the requested prefix of capturing groups.
     */
    public Re2Matcher matcher(Slice input, int retainedCapturingGroupCount)
    {
        return pattern.matcher(requireNonNull(input, "input is null"), retainedCapturingGroupCount);
    }

    /**
     * Finds a match and writes byte-offset start/end pairs into caller-owned storage.
     */
    public boolean findInto(Slice input, int[] groups)
    {
        return findInto(requireNonNull(input, "input is null"), 0, input.length(), groups);
    }

    /**
     * Finds a match in {@code [start, end)} and writes byte-offset start/end pairs.
     */
    public boolean findInto(Slice input, int start, int end, int[] groups)
    {
        return search(requireNonNull(input, "input is null"), start, end, groups);
    }

    /**
     * Matches at the beginning of the input and writes byte-offset start/end pairs.
     */
    public boolean lookingAtInto(Slice input, int[] groups)
    {
        return pattern.lookingAtInto(requireNonNull(input, "input is null"), groups);
    }

    /**
     * Matches at {@code start} within {@code [start, end)} and writes byte-offset start/end pairs.
     */
    public boolean lookingAtInto(Slice input, int start, int end, int[] groups)
    {
        boolean matched = pattern.lookingAtInto(requireNonNull(input, "input is null"), start, end, groups);
        return anchoredAt(input, start, matched, groups);
    }

    /**
     * Matches the complete input and writes byte-offset start/end pairs.
     */
    public boolean matchesInto(Slice input, int[] groups)
    {
        return pattern.matchesInto(requireNonNull(input, "input is null"), groups);
    }

    /**
     * Matches all of {@code [start, end)} and writes byte-offset start/end pairs.
     */
    public boolean matchesInto(Slice input, int start, int end, int[] groups)
    {
        boolean matched = pattern.matchesInto(requireNonNull(input, "input is null"), start, end, groups);
        return anchoredAt(input, start, matched, groups);
    }

    /**
     * Returns the first match, or {@code null} when there is no match.
     */
    public MatchResult findResult(Slice input)
    {
        return findResult(requireNonNull(input, "input is null"), 0, input.length());
    }

    /**
     * Returns the first match in {@code [start, end)}, or {@code null} when there is no match.
     */
    public MatchResult findResult(Slice input, int start, int end)
    {
        requireNonNull(input, "input is null");
        int[] groups = new int[2 * (capturingGroupCount() + 1)];
        if (!search(input, start, end, groups)) {
            return null;
        }
        return new MatchResult(input, groups, namedCapturingGroups());
    }

    /**
     * Returns the match at the beginning of the input, or {@code null} when there is no match.
     */
    public MatchResult lookingAtResult(Slice input)
    {
        return pattern.lookingAtResult(requireNonNull(input, "input is null"));
    }

    /**
     * Returns the match at {@code start} within {@code [start, end)}, or {@code null}.
     */
    public MatchResult lookingAtResult(Slice input, int start, int end)
    {
        MatchResult result = pattern.lookingAtResult(requireNonNull(input, "input is null"), start, end);
        return Utf8.isCharacterBoundary(input, start) ? result : null;
    }

    /**
     * Returns the complete-input match, or {@code null} when there is no match.
     */
    public MatchResult matchesResult(Slice input)
    {
        return pattern.matchesResult(requireNonNull(input, "input is null"));
    }

    /**
     * Returns the complete-region match, or {@code null} when there is no match.
     */
    public MatchResult matchesResult(Slice input, int start, int end)
    {
        MatchResult result = pattern.matchesResult(requireNonNull(input, "input is null"), start, end);
        return Utf8.isCharacterBoundary(input, start) ? result : null;
    }

    public int capturingGroupCount()
    {
        return pattern.capturingGroupCount();
    }

    public Map<String, Integer> namedCapturingGroups()
    {
        return pattern.namedCapturingGroups();
    }

    // Every unanchored search of a range comes here. The crate skips an empty match that splits a
    // UTF-8 character and searches again one byte later.
    private boolean search(Slice input, int start, int end, int[] groups)
    {
        if (!pattern.canMatchEmpty()) {
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

    // An anchored search cannot skip ahead, so a match at a split UTF-8 character fails.
    private static boolean anchoredAt(Slice input, int start, boolean matched, int[] groups)
    {
        if (!matched || Utf8.isCharacterBoundary(input, start)) {
            return matched;
        }
        if (groups != null) {
            Arrays.fill(groups, -1);
        }
        return false;
    }
}
