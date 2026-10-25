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
import io.airlift.slice.Slice;

import static java.util.Objects.requireNonNull;

/// Reusable, non-overlapping match iteration for a compiled Trino regular expression.
/// Offsets are bytes relative to the logical input Slice; end offsets are exclusive.
///
/// Each matcher owns its mutable search and capture state and is not thread-safe. Separate matchers
/// may share a compiled pattern. Group values are zero-copy views: they survive reset but reflect
/// mutations to their original input storage. Do not mutate input while matching it.
public sealed class TrinoRegexpMatcher
        permits TrinoRegexpMatcher.CompiledScanMatcher
{
    private final Re2Matcher matcher;
    private final LiteralAlternationSpanMatcher literalAlternation;
    private final FixedWidthByteSpanMatcher fixedWidth;
    private Slice input;
    private long span = -1;
    private int nextStart;
    private boolean exhausted;

    /// For a subclass that keeps its own state; the base class's matching state is unused.
    private TrinoRegexpMatcher()
    {
        matcher = null;
        literalAlternation = null;
        fixedWidth = null;
    }

    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "The sealed hierarchy permits only a private final nested implementation")
    TrinoRegexpMatcher(Re2Matcher matcher)
    {
        this.matcher = requireNonNull(matcher, "matcher is null");
        literalAlternation = null;
        fixedWidth = null;
    }

    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "The sealed hierarchy permits only a private final nested implementation")
    TrinoRegexpMatcher(LiteralAlternationSpanMatcher literalAlternation, Slice input)
    {
        matcher = null;
        this.literalAlternation = requireNonNull(literalAlternation, "literalAlternation is null");
        fixedWidth = null;
        reset(input);
    }

    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "The sealed hierarchy permits only a private final nested implementation")
    TrinoRegexpMatcher(FixedWidthByteSpanMatcher fixedWidth, Slice input)
    {
        matcher = null;
        literalAlternation = null;
        this.fixedWidth = requireNonNull(fixedWidth, "fixedWidth is null");
        reset(input);
    }

    /// Resets iteration to the complete logical input and invalidates the current match.
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "The matcher retains caller-owned input for zero-copy group views")
    public TrinoRegexpMatcher reset(Slice input)
    {
        requireNonNull(input, "input is null");
        if (matcher != null) {
            matcher.reset(input);
        }
        else {
            this.input = input;
            span = -1;
            nextStart = 0;
            exhausted = false;
        }
        return this;
    }

    /// Finds the next non-overlapping match. Empty matches advance by one UTF-8 code point;
    /// a terminal empty match is returned once. Failure invalidates the current match.
    public boolean find()
    {
        if (matcher != null) {
            return matcher.find();
        }
        if (exhausted) {
            return false;
        }
        // Both retained span kernels exclude captures and empty matches.
        span = fixedWidth != null ? fixedWidth.findSpan(input, nextStart) : literalAlternation.findSpan(input, nextStart);
        if (span < 0) {
            exhausted = true;
            return false;
        }
        nextStart = (int) span;
        return true;
    }

    /// Returns the retained capturing-group count, excluding group zero.
    public int groupCount()
    {
        return matcher == null ? 0 : matcher.groupCount();
    }

    /// Returns whether a retained group participated in the current match.
    ///
    /// @throws IllegalStateException if no successful match is current
    public boolean matched(int group)
    {
        if (matcher != null) {
            return matcher.matched(group);
        }
        checkSpanGroup(group);
        return true;
    }

    public int start()
    {
        return start(0);
    }

    /// Returns a group's start byte offset, or `-1` when it did not participate.
    ///
    /// @throws IllegalStateException if no successful match is current
    public int start(int group)
    {
        if (matcher != null) {
            return matcher.start(group);
        }
        checkSpanGroup(group);
        return (int) (span >>> 32);
    }

    public int end()
    {
        return end(0);
    }

    /// Returns a group's end byte offset, or `-1` when it did not participate.
    ///
    /// @throws IllegalStateException if no successful match is current
    public int end(int group)
    {
        if (matcher != null) {
            return matcher.end(group);
        }
        checkSpanGroup(group);
        return (int) span;
    }

    public Slice group()
    {
        return group(0);
    }

    /// Returns a zero-copy group view, or `null` when it did not participate.
    ///
    /// @throws IllegalStateException if no successful match is current
    public Slice group(int group)
    {
        if (matcher != null) {
            return matcher.group(group);
        }
        int start = start(group);
        return input.slice(start, end(group) - start);
    }

    /// Returns a matcher retaining `retainedCapturingGroupCount` groups whose finds run the
    /// generated class of a compiled scan.
    static TrinoRegexpMatcher compiledScan(CompiledTrinoScan scan, Slice input, int retainedCapturingGroupCount)
    {
        return CompiledScanMatcher.create(scan, input, retainedCapturingGroupCount);
    }

    private void checkSpanGroup(int group)
    {
        if (span < 0) {
            throw new IllegalStateException("no successful match");
        }
        if (group != 0) {
            throw new IllegalArgumentException("group index out of range: " + group);
        }
    }

    /// A matcher whose finds run a generated [CompiledTrinoScan], with the results and
    /// exceptions of the interpreted matcher it replaces. A scan plan never matches empty, so each
    /// find resumes at the previous match end.
    private static final class CompiledScanMatcher
            extends TrinoRegexpMatcher
    {
        private final CompiledTrinoScan scan;
        private final int[] groups;
        private Slice input;
        private int nextStart;
        private boolean exhausted;
        private boolean hasMatch;

        private CompiledScanMatcher(CompiledTrinoScan scan, Slice input, int retainedCapturingGroupCount)
        {
            this.scan = requireNonNull(scan, "scan is null");
            this.groups = new int[2 * (retainedCapturingGroupCount + 1)];
            reset(input);
        }

        /// The declared type keeps verification of [TrinoRegexpMatcher] from loading this
        /// class.
        static TrinoRegexpMatcher create(CompiledTrinoScan scan, Slice input, int retainedCapturingGroupCount)
        {
            return new CompiledScanMatcher(scan, input, retainedCapturingGroupCount);
        }

        @Override
        public TrinoRegexpMatcher reset(Slice input)
        {
            this.input = requireNonNull(input, "input is null");
            nextStart = 0;
            exhausted = false;
            hasMatch = false;
            return this;
        }

        @Override
        public boolean find()
        {
            if (!exhausted && scan.find(input, nextStart, groups)) {
                hasMatch = true;
                nextStart = groups[1];
                return true;
            }
            exhausted = true;
            hasMatch = false;
            return false;
        }

        @Override
        public int groupCount()
        {
            return (groups.length / 2) - 1;
        }

        @Override
        public boolean matched(int group)
        {
            checkGroup(group);
            return groups[2 * group] >= 0;
        }

        @Override
        public int start(int group)
        {
            checkGroup(group);
            return groups[2 * group];
        }

        @Override
        public int end(int group)
        {
            checkGroup(group);
            return groups[2 * group + 1];
        }

        @Override
        public Slice group(int group)
        {
            int start = start(group);
            if (start < 0) {
                return null;
            }
            return input.slice(start, groups[2 * group + 1] - start);
        }

        private void checkGroup(int group)
        {
            if (!hasMatch) {
                throw new IllegalStateException("no successful match");
            }
            if (group < 0 || group > groupCount()) {
                throw new IllegalArgumentException("group index out of range: " + group);
            }
        }
    }
}
