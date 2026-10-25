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
package io.trino.operator.scalar;

import io.airlift.joni.Matcher;
import io.airlift.joni.Region;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceOutput;
import io.trino.type.JoniRegexp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static io.airlift.slice.SliceUtf8.lengthOfCodePointFromStartByte;
import static io.trino.operator.scalar.JoniRegexpFunctions.matcher;
import static io.trino.operator.scalar.JoniRegexpFunctions.search;
import static java.lang.Math.toIntExact;
import static java.util.Objects.checkIndex;

/**
 * Pinned Trino's Joni operation loops with Slice lists in place of SQL Blocks.
 * This benchmark adapter changes output representation, not matching, empty-match advancement,
 * or callback semantics. The verifier compares it with the unmodified pinned SQL functions.
 */
public final class JoniSliceOperations
{
    private JoniSliceOperations() {}

    public static List<Slice> extractAll(Slice source, JoniRegexp pattern)
    {
        return extractAll(source, pattern, 0);
    }

    /**
     * Returns capturing group {@code groupIndex} of every match, or null where that group did not
     * participate. An invalid {@code groupIndex} fails with a JDK exception rather than Trino's
     * SQL error; no workload passes one.
     */
    public static List<Slice> extractAll(Slice source, JoniRegexp pattern, long groupIndex)
    {
        Matcher matcher = matcher(source, pattern);
        int group = checkIndex(toIntExact(groupIndex), matcher.getEagerRegion().numRegs);
        List<Slice> output = new ArrayList<>();
        int nextStart = 0;
        while (search(matcher, source, nextStart) != -1) {
            nextStart = nextStart(source, matcher);
            Region region = matcher.getEagerRegion();
            int begin = region.beg[group];
            int end = region.end[group];
            output.add(begin == -1 || end == -1 ? null : source.slice(begin, end - begin));
        }
        return output;
    }

    public static List<Slice> split(Slice source, JoniRegexp pattern)
    {
        Matcher matcher = matcher(source, pattern);
        List<Slice> output = new ArrayList<>();
        int lastEnd = 0;
        int nextStart = 0;
        while (search(matcher, source, nextStart) != -1) {
            nextStart = nextStart(source, matcher);
            output.add(source.slice(lastEnd, matcher.getBegin() - lastEnd));
            lastEnd = matcher.getEnd();
        }
        output.add(source.slice(lastEnd, source.length() - lastEnd));
        return output;
    }

    public static Slice replace(Slice source, JoniRegexp pattern, Function<List<Slice>, Slice> replacement)
    {
        Matcher matcher = matcher(source, pattern);
        if (search(matcher, source, 0) == -1) {
            return source;
        }
        SliceOutput output = new DynamicSliceOutput(source.length());
        int groupCount = pattern.regex().numberOfCaptures();
        int appendPosition = 0;
        int nextStart;
        do {
            nextStart = nextStart(source, matcher);
            output.appendBytes(source.slice(appendPosition, matcher.getBegin() - appendPosition));
            appendPosition = matcher.getEnd();
            Region region = matcher.getEagerRegion();
            List<Slice> groups = new ArrayList<>(groupCount);
            for (int group = 1; group <= groupCount; group++) {
                groups.add(region.beg[group] < 0 || region.end[group] < 0 ? null :
                        source.slice(region.beg[group], region.end[group] - region.beg[group]));
            }
            Slice replaced = replacement.apply(Collections.unmodifiableList(groups));
            if (replaced == null) {
                return null;
            }
            output.appendBytes(replaced);
        }
        while (search(matcher, source, nextStart) != -1);
        output.writeBytes(source, appendPosition, source.length() - appendPosition);
        return output.slice();
    }

    private static int nextStart(Slice source, Matcher matcher)
    {
        if (matcher.getEnd() != matcher.getBegin()) {
            return matcher.getEnd();
        }
        return matcher.getEnd() + (matcher.getBegin() < source.length() ?
                lengthOfCodePointFromStartByte(source.getByte(matcher.getBegin())) : 1);
    }
}
