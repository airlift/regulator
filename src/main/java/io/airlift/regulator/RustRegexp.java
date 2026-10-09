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

import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * A compiled Rust regex pattern over UTF-8 Slice inputs. Compiled patterns are thread-safe;
 * matchers are mutable. Match offsets are UTF-8 byte offsets.
 */
public final class RustRegexp
{
    /**
     * Compilation options, copied when compiling a pattern.
     */
    public static final class Options
    {
        private int flags = RustRegexpParser.UNICODE;
        private boolean octal;
        private int nestLimit = 250;
        private long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;

        private Options() {}

        public static Options defaults()
        {
            return new Options();
        }

        public Options setCaseInsensitive(boolean enabled)
        {
            flags = enabled ? flags | RustRegexpParser.CASE_INSENSITIVE : flags & ~RustRegexpParser.CASE_INSENSITIVE;
            return this;
        }

        public Options setMultiline(boolean enabled)
        {
            flags = enabled ? flags | RustRegexpParser.MULTILINE : flags & ~RustRegexpParser.MULTILINE;
            return this;
        }

        public Options setDotMatchesNewline(boolean enabled)
        {
            flags = enabled ? flags | RustRegexpParser.DOT_ALL : flags & ~RustRegexpParser.DOT_ALL;
            return this;
        }

        public Options setCrlf(boolean enabled)
        {
            flags = enabled ? flags | RustRegexpParser.CRLF : flags & ~RustRegexpParser.CRLF;
            return this;
        }

        public Options setSwapGreed(boolean enabled)
        {
            flags = enabled ? flags | RustRegexpParser.SWAP_GREED : flags & ~RustRegexpParser.SWAP_GREED;
            return this;
        }

        public Options setUnicode(boolean enabled)
        {
            flags = enabled ? flags | RustRegexpParser.UNICODE : flags & ~RustRegexpParser.UNICODE;
            return this;
        }

        public Options setIgnoreWhitespace(boolean enabled)
        {
            flags = enabled ? flags | RustRegexpParser.IGNORE_WHITESPACE : flags & ~RustRegexpParser.IGNORE_WHITESPACE;
            return this;
        }

        public Options setOctal(boolean octal)
        {
            this.octal = octal;
            return this;
        }

        public Options setNestLimit(int nestLimit)
        {
            if (nestLimit < 0 || nestLimit > 250) {
                throw new IllegalArgumentException("nestLimit must be between 0 and 250: " + nestLimit);
            }
            this.nestLimit = nestLimit;
            return this;
        }

        public Options setMaxMemory(long maxMemory)
        {
            if (maxMemory <= 0) {
                throw new IllegalArgumentException("maxMemory must be greater than zero: " + maxMemory);
            }
            this.maxMemory = maxMemory;
            return this;
        }

        int flags()
        {
            return flags;
        }

        boolean octal()
        {
            return octal;
        }

        int nestLimit()
        {
            return nestLimit;
        }

        long maxMemory()
        {
            return maxMemory;
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
        int parseFlags = Regexp.LIKE_PERL;
        ParseResult parsed = RustRegexpParser.parse(patternCopy, options);
        return new RustRegexp(Re2.compileParsed(patternCopy, parsed, parseFlags, options.maxMemory()));
    }

    public boolean find(Slice input)
    {
        checkInput(input);
        return pattern.find(requireNonNull(input, "input is null"));
    }

    /**
     * Returns the number of non-overlapping matches, including empty matches.
     * Empty matches advance by one UTF-8 code point and do not overlap the preceding match.
     * No capture values are returned.
     */
    public long count(Slice input)
    {
        long count = 0;
        RustRegexpMatcher matcher = matcher(input);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    public boolean lookingAt(Slice input)
    {
        checkInput(input);
        return pattern.lookingAt(requireNonNull(input, "input is null"));
    }

    public boolean matches(Slice input)
    {
        checkInput(input);
        return pattern.matches(requireNonNull(input, "input is null"));
    }

    /**
     * Returns whether the pattern has a match in {@code [start, end)}.
     */
    public boolean find(Slice input, int start, int end)
    {
        checkInput(input, start, end);
        return pattern.find(requireNonNull(input, "input is null"), start, end);
    }

    /**
     * Returns whether the pattern matches at {@code start} within {@code [start, end)}.
     */
    public boolean lookingAt(Slice input, int start, int end)
    {
        checkInput(input, start, end);
        return pattern.lookingAt(requireNonNull(input, "input is null"), start, end);
    }

    /**
     * Returns whether the pattern matches all of {@code [start, end)}.
     */
    public boolean matches(Slice input, int start, int end)
    {
        checkInput(input, start, end);
        return pattern.matches(requireNonNull(input, "input is null"), start, end);
    }

    /**
     * Creates a mutable matcher retaining every capturing group.
     */
    public RustRegexpMatcher matcher(Slice input)
    {
        return new RustRegexpMatcher(pattern.matcher(input), input);
    }

    /**
     * Creates a reusable matcher retaining group zero and the requested prefix of capturing groups.
     */
    public RustRegexpMatcher matcher(Slice input, int retainedCapturingGroupCount)
    {
        return new RustRegexpMatcher(pattern.matcher(input, retainedCapturingGroupCount), input);
    }

    /**
     * Finds a match and writes byte-offset start/end pairs into caller-owned storage.
     */
    public boolean findInto(Slice input, int[] groups)
    {
        checkInput(input);
        return pattern.findInto(requireNonNull(input, "input is null"), groups);
    }

    /**
     * Finds a match in {@code [start, end)} and writes byte-offset start/end pairs.
     */
    public boolean findInto(Slice input, int start, int end, int[] groups)
    {
        checkInput(input, start, end);
        return pattern.findInto(requireNonNull(input, "input is null"), start, end, groups);
    }

    /**
     * Matches at the beginning of the input and writes byte-offset start/end pairs.
     */
    public boolean lookingAtInto(Slice input, int[] groups)
    {
        checkInput(input);
        return pattern.lookingAtInto(requireNonNull(input, "input is null"), groups);
    }

    /**
     * Matches at {@code start} within {@code [start, end)} and writes byte-offset start/end pairs.
     */
    public boolean lookingAtInto(Slice input, int start, int end, int[] groups)
    {
        checkInput(input, start, end);
        return pattern.lookingAtInto(requireNonNull(input, "input is null"), start, end, groups);
    }

    /**
     * Matches the complete input and writes byte-offset start/end pairs.
     */
    public boolean matchesInto(Slice input, int[] groups)
    {
        checkInput(input);
        return pattern.matchesInto(requireNonNull(input, "input is null"), groups);
    }

    /**
     * Matches all of {@code [start, end)} and writes byte-offset start/end pairs.
     */
    public boolean matchesInto(Slice input, int start, int end, int[] groups)
    {
        checkInput(input, start, end);
        return pattern.matchesInto(requireNonNull(input, "input is null"), start, end, groups);
    }

    /**
     * Returns the first match, or {@code null} when there is no match.
     */
    public MatchResult findResult(Slice input)
    {
        checkInput(input);
        return pattern.findResult(requireNonNull(input, "input is null"));
    }

    /**
     * Returns the first match in {@code [start, end)}, or {@code null} when there is no match.
     */
    public MatchResult findResult(Slice input, int start, int end)
    {
        checkInput(input, start, end);
        return pattern.findResult(requireNonNull(input, "input is null"), start, end);
    }

    /**
     * Returns the match at the beginning of the input, or {@code null} when there is no match.
     */
    public MatchResult lookingAtResult(Slice input)
    {
        checkInput(input);
        return pattern.lookingAtResult(requireNonNull(input, "input is null"));
    }

    /**
     * Returns the match at {@code start} within {@code [start, end)}, or {@code null}.
     */
    public MatchResult lookingAtResult(Slice input, int start, int end)
    {
        checkInput(input, start, end);
        return pattern.lookingAtResult(requireNonNull(input, "input is null"), start, end);
    }

    /**
     * Returns the complete-input match, or {@code null} when there is no match.
     */
    public MatchResult matchesResult(Slice input)
    {
        checkInput(input);
        return pattern.matchesResult(requireNonNull(input, "input is null"));
    }

    /**
     * Returns the complete-region match, or {@code null} when there is no match.
     */
    public MatchResult matchesResult(Slice input, int start, int end)
    {
        checkInput(input, start, end);
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

    static void checkInput(Slice input)
    {
        requireNonNull(input, "input is null");
        if (Utf8.firstInvalidOffset(input) >= 0) {
            throw new IllegalArgumentException("input is not valid UTF-8");
        }
    }

    static void checkInput(Slice input, int start, int end)
    {
        requireNonNull(input, "input is null");
        if (start < 0 || end < start || end > input.length()) {
            throw new IndexOutOfBoundsException("region out of bounds: [" + start + ", " + end + ")");
        }
        checkInput(input);
        if ((start < input.length() && (input.getByte(start) & 0xC0) == 0x80) ||
                (end < input.length() && (input.getByte(end) & 0xC0) == 0x80)) {
            throw new IllegalArgumentException("region boundary splits a UTF-8 code point");
        }
    }
}
