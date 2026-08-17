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

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.airlift.regulator.TrinoRegexp;
import io.trino.type.JoniRegexp;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static io.trino.operator.scalar.JoniRegexpCasts.joniRegexp;
import static java.nio.charset.StandardCharsets.UTF_8;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkTrinoFinalLine
{
    static final List<String> WORKLOADS = List.of(
            "literalNoMatch",
            "literalLateMatch",
            "characterClassNoMatch",
            "characterClassLateMatch",
            "characterClassLongMatch",
            "captureNoMatch",
            "captureLateMatch",
            "captureLongMatch",
            "alternationNoMatch",
            "alternationLateMatch",
            "alternationLongMatch");

    static final List<Integer> SOURCE_LENGTHS = List.of(1_024, 32_768);
    private static final Slice REPLACEMENT = Slices.utf8Slice("_");

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({
                "literalNoMatch",
                "literalLateMatch",
                "characterClassNoMatch",
                "characterClassLateMatch",
                "characterClassLongMatch",
                "captureNoMatch",
                "captureLateMatch",
                "captureLongMatch",
                "alternationNoMatch",
                "alternationLateMatch",
                "alternationLongMatch"})
        String workload;

        @Param({"1024", "32768"})
        int sourceLength;

        private TrinoRegexp regulatorRegexp;
        private JoniRegexp joniRegexp;
        private Slice source;
        private Function<List<Slice>, Slice> regulatorLambdaReplacement;
        private Function<List<Slice>, Slice> joniLambdaReplacement;

        @Setup
        public void setup()
        {
            Input input = createInput(workload, sourceLength);
            regulatorRegexp = TrinoRegexp.compile(input.pattern());
            joniRegexp = joniRegexp(input.pattern());
            source = input.source();
            regulatorLambdaReplacement = groups -> groups.isEmpty() ? REPLACEMENT : groups.getFirst();
            joniLambdaReplacement = groups -> groups.isEmpty() ? REPLACEMENT : groups.getFirst();
        }
    }

    @Benchmark
    public boolean containsRegulator(BenchmarkData data)
    {
        return data.regulatorRegexp.contains(data.source);
    }

    @Benchmark
    public boolean containsJoni(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpLike(data.source, data.joniRegexp);
    }

    @Benchmark
    public long countRegulator(BenchmarkData data)
    {
        return data.regulatorRegexp.count(data.source);
    }

    @Benchmark
    public long countJoni(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpCount(data.source, data.joniRegexp);
    }

    @Benchmark
    public long positionRegulator(BenchmarkData data)
    {
        return data.regulatorRegexp.position(data.source, 1, 1);
    }

    @Benchmark
    public long positionJoni(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpPosition(data.source, data.joniRegexp, 1, 1);
    }

    @Benchmark
    public Slice extractRegulator(BenchmarkData data)
    {
        return data.regulatorRegexp.extract(data.source);
    }

    @Benchmark
    public Slice extractJoni(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpExtract(data.source, data.joniRegexp);
    }

    @Benchmark
    public List<Slice> extractAllRegulator(BenchmarkData data)
    {
        return data.regulatorRegexp.extractAll(data.source);
    }

    @Benchmark
    public List<Slice> extractAllJoni(BenchmarkData data)
    {
        return JoniSliceOperations.extractAll(data.source, data.joniRegexp);
    }

    @Benchmark
    public List<Slice> splitRegulator(BenchmarkData data)
    {
        return data.regulatorRegexp.split(data.source);
    }

    @Benchmark
    public List<Slice> splitJoni(BenchmarkData data)
    {
        return JoniSliceOperations.split(data.source, data.joniRegexp);
    }

    @Benchmark
    public Slice replaceRegulator(BenchmarkData data)
    {
        return data.regulatorRegexp.replace(data.source, REPLACEMENT);
    }

    @Benchmark
    public Slice replaceJoni(BenchmarkData data)
    {
        return JoniRegexpFunctions.regexpReplace(data.source, data.joniRegexp, REPLACEMENT);
    }

    @Benchmark
    public Slice replaceLambdaRegulator(BenchmarkData data)
    {
        return data.regulatorRegexp.replace(data.source, data.regulatorLambdaReplacement);
    }

    @Benchmark
    public Slice replaceLambdaJoni(BenchmarkData data)
    {
        return JoniSliceOperations.replace(data.source, data.joniRegexp, data.joniLambdaReplacement);
    }

    static Input createInput(String workload, int sourceLength)
    {
        byte[] sourceBytes = new byte[sourceLength];
        String pattern;
        byte[] match = null;

        switch (workload) {
            case "literalNoMatch" -> {
                pattern = "a$";
                Arrays.fill(sourceBytes, (byte) 'b');
            }
            case "literalLateMatch" -> {
                pattern = "a$";
                Arrays.fill(sourceBytes, (byte) 'b');
                match = new byte[] {'a'};
            }
            case "characterClassNoMatch" -> {
                pattern = "[a-z]+$";
                Arrays.fill(sourceBytes, (byte) '1');
            }
            case "characterClassLateMatch" -> {
                pattern = "[a-z]+$";
                Arrays.fill(sourceBytes, (byte) '1');
                match = "abc".getBytes(UTF_8);
            }
            case "characterClassLongMatch" -> {
                pattern = "[a-z]+$";
                Arrays.fill(sourceBytes, (byte) 'a');
            }
            case "captureNoMatch" -> {
                pattern = "([a-z]+)$";
                Arrays.fill(sourceBytes, (byte) '1');
            }
            case "captureLateMatch" -> {
                pattern = "([a-z]+)$";
                Arrays.fill(sourceBytes, (byte) '1');
                match = "abc".getBytes(UTF_8);
            }
            case "captureLongMatch" -> {
                pattern = "([a-z]+)$";
                Arrays.fill(sourceBytes, (byte) 'a');
            }
            case "alternationNoMatch" -> {
                pattern = "(?:foo|bar)+$";
                Arrays.fill(sourceBytes, (byte) '.');
            }
            case "alternationLateMatch" -> {
                pattern = "(?:foo|bar)+$";
                Arrays.fill(sourceBytes, (byte) '.');
                match = "foobar".getBytes(UTF_8);
            }
            case "alternationLongMatch" -> {
                pattern = "(?:foo|bar)+$";
                Arrays.fill(sourceBytes, (byte) '.');
                int matchStart = (sourceBytes.length - 1) % 3;
                for (int position = matchStart; position < sourceBytes.length - 1; position += 3) {
                    sourceBytes[position] = 'f';
                    sourceBytes[position + 1] = 'o';
                    sourceBytes[position + 2] = 'o';
                }
            }
            default -> throw new IllegalArgumentException("unknown workload: " + workload);
        }

        sourceBytes[sourceBytes.length - 1] = '\n';
        if (match != null) {
            System.arraycopy(match, 0, sourceBytes, sourceBytes.length - match.length - 1, match.length);
        }
        return new Input(Slices.utf8Slice(pattern), Slices.wrappedBuffer(sourceBytes));
    }

    record Input(Slice pattern, Slice source) {}
}
