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
import io.airlift.slice.Slices;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2Set.MatchMode.FIND;
import static java.nio.charset.StandardCharsets.UTF_8;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(5)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
public class BenchmarkPatternCollections
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"16", "128"})
        int patternCount;

        @Param({"1024", "32768"})
        int sourceLength;

        private int[] allAtomIds;
        private FilteredRe2 filteredRe2;
        private Slice[] patterns;
        private Re2Set re2Set;
        private Slice source;

        @Setup
        public void setup()
        {
            patterns = buildPatterns(patternCount);

            byte[] bytes = new byte[sourceLength];
            Arrays.fill(bytes, (byte) '.');
            byte[] match = ("prefix" + (patternCount - 1) + "-123").getBytes(UTF_8);
            System.arraycopy(match, 0, bytes, sourceLength - match.length, match.length);
            source = Slices.wrappedBuffer(bytes);

            re2Set = buildRe2Set(patterns);
            filteredRe2 = buildFilteredRe2(patterns);
            allAtomIds = new int[filteredRe2.atoms().size()];
            for (int index = 0; index < allAtomIds.length; index++) {
                allAtomIds[index] = index;
            }
        }
    }

    @State(Scope.Thread)
    public static class MatchDenseData
    {
        @Param({"16", "128"})
        int patternCount;

        @Param({"1024", "32768"})
        int sourceLength;

        private Re2Set re2Set;
        Slice source;

        @Setup
        public void setup()
        {
            Slice[] patterns = buildPatterns(patternCount);

            // Matches of the patterns in ID order, cycled until the text is full, so the search keeps
            // entering matching states. At 128 patterns, 1,024 bytes end inside pattern 79's text,
            // so only patterns 0 to 78 match.
            StringBuilder text = new StringBuilder();
            for (int index = 0; text.length() < sourceLength; index = (index + 1) % patternCount) {
                text.append("prefix").append(index).append("-123 ");
            }
            text.setLength(sourceLength);
            source = Slices.utf8Slice(text.toString());

            re2Set = buildRe2Set(patterns);
        }
    }

    @Benchmark
    public Re2Set compileRe2Set(BenchmarkData data)
    {
        return buildRe2Set(data.patterns);
    }

    @Benchmark
    public boolean matchesAny(BenchmarkData data)
    {
        return data.re2Set.matchesAny(data.source);
    }

    @Benchmark
    public int[] matchingSetPatternIds(BenchmarkData data)
    {
        return data.re2Set.matchingPatternIds(data.source);
    }

    @Benchmark
    public int[] matchingSetPatternIdsMatchDense(MatchDenseData data)
    {
        return data.re2Set.matchingPatternIds(data.source);
    }

    @Benchmark
    public FilteredRe2 compileFilteredRe2(BenchmarkData data)
    {
        return buildFilteredRe2(data.patterns);
    }

    @Benchmark
    public int[] potentialPatternIds(BenchmarkData data)
    {
        return data.filteredRe2.potentialPatternIds(data.allAtomIds);
    }

    @Benchmark
    public int firstMatchingPatternId(BenchmarkData data)
    {
        return data.filteredRe2.firstMatchingPatternId(data.source, data.allAtomIds);
    }

    @Benchmark
    public int[] matchingFilteredPatternIds(BenchmarkData data)
    {
        return data.filteredRe2.matchingPatternIds(data.source, data.allAtomIds);
    }

    private static Slice[] buildPatterns(int patternCount)
    {
        Slice[] patterns = new Slice[patternCount];
        for (int index = 0; index < patternCount; index++) {
            patterns[index] = Slices.utf8Slice("prefix" + index + "-[0-9]+");
        }
        return patterns;
    }

    private static Re2Set buildRe2Set(Slice[] patterns)
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), FIND);
        for (Slice pattern : patterns) {
            builder.add(pattern);
        }
        return builder.build();
    }

    private static FilteredRe2 buildFilteredRe2(Slice[] patterns)
    {
        FilteredRe2.Builder builder = FilteredRe2.builder();
        for (Slice pattern : patterns) {
            builder.add(pattern, Re2.Options.defaults());
        }
        return builder.build();
    }
}
