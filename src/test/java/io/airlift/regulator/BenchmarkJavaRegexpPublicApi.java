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

import static java.nio.charset.StandardCharsets.UTF_8;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(5)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
public class BenchmarkJavaRegexpPublicApi
{
    @State(Scope.Thread)
    public static class CompileData
    {
        private Slice pattern;

        @Setup
        public void setup()
        {
            pattern = Slices.utf8Slice("(?<word>[a-z]+)-(?<number>[0-9]+)");
        }
    }

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"64", "32768"})
        int sourceLength;

        @Param({"match", "noMatch"})
        String outcome;

        private int[] groups;
        private JavaRegexp regexp;
        private Slice source;

        @Setup
        public void setup()
        {
            Slice pattern = Slices.utf8Slice("(?<word>[a-z]+)-(?<number>[0-9]+)");
            byte[] bytes = new byte[sourceLength];
            Arrays.fill(bytes, (byte) '.');
            if (outcome.equals("match")) {
                byte[] match = "abc-123".getBytes(UTF_8);
                System.arraycopy(match, 0, bytes, sourceLength / 2, match.length);
            }
            source = Slices.wrappedBuffer(bytes);
            regexp = JavaRegexp.compile(pattern);
            groups = new int[6];
        }
    }

    @Benchmark
    public JavaRegexp compile(CompileData data)
    {
        return JavaRegexp.compile(data.pattern);
    }

    @Benchmark
    public boolean find(BenchmarkData data)
    {
        return data.regexp.find(data.source);
    }

    @Benchmark
    public boolean lookingAt(BenchmarkData data)
    {
        return data.regexp.lookingAt(data.source);
    }

    @Benchmark
    public boolean matches(BenchmarkData data)
    {
        return data.regexp.matches(data.source);
    }

    @Benchmark
    public boolean findInRange(BenchmarkData data)
    {
        return data.regexp.find(data.source, 1, data.source.length() - 1);
    }

    @Benchmark
    public boolean findInto(BenchmarkData data)
    {
        return data.regexp.findInto(data.source, data.groups);
    }

    @Benchmark
    public MatchResult findResult(BenchmarkData data)
    {
        return data.regexp.findResult(data.source);
    }

    @Benchmark
    public boolean matcherFind(BenchmarkData data)
    {
        return data.regexp.matcher(data.source).find();
    }
}
