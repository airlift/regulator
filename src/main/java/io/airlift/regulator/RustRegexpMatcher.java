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

/**
 * A reusable Rust matcher. Empty matches never overlap the end of the preceding match.
 * Captures are zero-copy views of the input, and positions are UTF-8 byte offsets.
 * Instances are mutable and not thread-safe.
 */
public final class RustRegexpMatcher
{
    private final Re2Matcher matcher;
    private Slice input;
    private int regionStart;
    private int regionEnd;
    private int previousEnd = -1;

    RustRegexpMatcher(Re2Matcher matcher, Slice input)
    {
        this.matcher = requireNonNull(matcher, "matcher is null");
        reset(input);
    }

    public RustRegexpMatcher reset(Slice input)
    {
        requireNonNull(input, "input is null");
        return reset(input, 0, input.length());
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "The matcher intentionally retains the caller's Slice for zero-copy matching")
    public RustRegexpMatcher reset(Slice input, int start, int end)
    {
        RustRegexp.checkInput(input, start, end);
        matcher.reset(input, start, end);
        this.input = input;
        regionStart = start;
        regionEnd = end;
        previousEnd = -1;
        return this;
    }

    public boolean find()
    {
        boolean found = matcher.find();
        if (found && matcher.start() == previousEnd && matcher.end() == previousEnd) {
            found = matcher.find();
        }
        return remember(found);
    }

    public boolean find(int start)
    {
        if (start < regionStart || start > regionEnd) {
            throw new IndexOutOfBoundsException("start out of bounds: " + start);
        }
        // Rust string searches never expose an empty match that splits a UTF-8 code point.
        int boundary = start;
        while (boundary < regionEnd && (input.getByte(boundary) & 0xC0) == 0x80) {
            boundary++;
        }
        previousEnd = -1;
        return remember(matcher.find(boundary));
    }

    public boolean lookingAt()
    {
        return remember(matcher.lookingAt());
    }

    public boolean matches()
    {
        return remember(matcher.matches());
    }

    private boolean remember(boolean found)
    {
        previousEnd = found ? matcher.end() : -1;
        return found;
    }

    public int groupCount()
    {
        return matcher.groupCount();
    }

    public boolean matched(int group)
    {
        return matcher.matched(group);
    }

    public int start()
    {
        return matcher.start();
    }

    public int start(int group)
    {
        return matcher.start(group);
    }

    public int end()
    {
        return matcher.end();
    }

    public int end(int group)
    {
        return matcher.end(group);
    }

    public Slice group()
    {
        return matcher.group();
    }

    public Slice group(int group)
    {
        return matcher.group(group);
    }

    public Slice group(String groupName)
    {
        return matcher.group(groupName);
    }

    public MatchResult toMatchResult()
    {
        return matcher.toMatchResult();
    }
}
