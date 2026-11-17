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
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static org.openjdk.jmh.annotations.CompilerControl.Mode.DONT_INLINE;

/// Times each Trino operation on each everyday workload. Each operation method is one pass over
/// the workload's [#INPUTS] inputs in order, which JMH counts as one operation per input, and
/// consumes each input's result, with the state it reads loaded into locals before the loop. The
/// loop calls the operation's static method on each input, the one verification calls; the JIT
/// may not inline it into the pass, as each native engine is an out-of-line call. `nullEngine` is
/// that pass with no engine. The LIKE construction methods compile one pattern per operation.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkEverydayTrinoRegexp
{
    /// The inputs of every workload, and so the operations of one invocation.
    public static final int INPUTS = 8;

    private static final Slice LIKE_WILDCARD_CHAIN_PATTERN = Slices.utf8Slice("%alpha_bravo_charlie%");
    private static final Slice LIKE_UNICODE_WILDCARD_CHAIN_PATTERN = Slices.utf8Slice("%alpha_\u03C0_omega%");
    private static final Slice LIKE_ESCAPED_WILDCARD_CHAIN_PATTERN = Slices.utf8Slice("%alpha!__bravo_charlie%");

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({
                "logLevel",
                "isoDate",
                "uuid",
                "ipv4",
                "email",
                "keyValue",
                "quotedField",
                "whitespace",
                "delimiter",
                "unicodeLetters",
                "trinoPhone",
                "trinoLiteral",
                "trinoDotStarLiteral",
                "googlebotLine",
        })
        String workload;

        private TrinoRegexp regexp;
        Slice[] inputs;
        private Slice replacement;
        private Function<List<Slice>, Slice> lambdaReplacement;

        @Setup
        public void setup()
        {
            TestingEverydayTrinoRegexpBenchmarkInputs.Input input =
                    TestingEverydayTrinoRegexpBenchmarkInputs.create(workload);
            regexp = TrinoRegexp.compile(input.pattern());
            inputs = inputs(input);
            replacement = Slices.utf8Slice("_");
            lambdaReplacement = _ -> replacement;
        }

        public void setWorkload(String workload)
        {
            this.workload = workload;
        }

        // Each operation on input `index`, through the operation its benchmark method calls on each input, for tests and
        // the Joni verifier.

        public boolean contains(int index)
        {
            return containsOperation(regexp, inputs[index]);
        }

        public long count(int index)
        {
            return countOperation(regexp, inputs[index]);
        }

        public long positionThird(int index)
        {
            return positionThirdOperation(regexp, inputs[index]);
        }

        public Slice extract(int index)
        {
            return extractOperation(regexp, inputs[index]);
        }

        public List<Slice> extractAll(int index)
        {
            return extractAllOperation(regexp, inputs[index]);
        }

        public List<Slice> split(int index)
        {
            return splitOperation(regexp, inputs[index]);
        }

        public Slice replace(int index)
        {
            return replaceOperation(regexp, replacement, inputs[index]);
        }

        public Slice replaceLambda(int index)
        {
            return replaceLambdaOperation(regexp, lambdaReplacement, inputs[index]);
        }
    }

    /// The input array every pass reads: the workload's sources in its order, which must be
    /// [#INPUTS].
    public static Slice[] inputs(TestingEverydayTrinoRegexpBenchmarkInputs.Input input)
    {
        if (input.sources().size() != INPUTS) {
            throw new IllegalStateException("%s has %s inputs, not %s".formatted(input.workloadId(), input.sources().size(), INPUTS));
        }
        return input.sources().stream().map(TestingEverydayTrinoRegexpBenchmarkInputs.Source::value).toArray(Slice[]::new);
    }

    /// The null engine's operation: the input's byte length, with no matching, so its time per
    /// input is the harness's own cost.
    @CompilerControl(DONT_INLINE)
    public static long nullOperation(Slice input)
    {
        return input.length();
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void contains(BenchmarkData data, Blackhole blackhole)
    {
        TrinoRegexp regexp = data.regexp;
        for (Slice input : data.inputs) {
            blackhole.consume(containsOperation(regexp, input));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void count(BenchmarkData data, Blackhole blackhole)
    {
        TrinoRegexp regexp = data.regexp;
        for (Slice input : data.inputs) {
            blackhole.consume(countOperation(regexp, input));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void positionThird(BenchmarkData data, Blackhole blackhole)
    {
        TrinoRegexp regexp = data.regexp;
        for (Slice input : data.inputs) {
            blackhole.consume(positionThirdOperation(regexp, input));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void extract(BenchmarkData data, Blackhole blackhole)
    {
        TrinoRegexp regexp = data.regexp;
        for (Slice input : data.inputs) {
            blackhole.consume(extractOperation(regexp, input));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void extractAll(BenchmarkData data, Blackhole blackhole)
    {
        TrinoRegexp regexp = data.regexp;
        for (Slice input : data.inputs) {
            blackhole.consume(extractAllOperation(regexp, input));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void split(BenchmarkData data, Blackhole blackhole)
    {
        TrinoRegexp regexp = data.regexp;
        for (Slice input : data.inputs) {
            blackhole.consume(splitOperation(regexp, input));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void replace(BenchmarkData data, Blackhole blackhole)
    {
        TrinoRegexp regexp = data.regexp;
        Slice replacement = data.replacement;
        for (Slice input : data.inputs) {
            blackhole.consume(replaceOperation(regexp, replacement, input));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void replaceLambda(BenchmarkData data, Blackhole blackhole)
    {
        TrinoRegexp regexp = data.regexp;
        Function<List<Slice>, Slice> replacement = data.lambdaReplacement;
        for (Slice input : data.inputs) {
            blackhole.consume(replaceLambdaOperation(regexp, replacement, input));
        }
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void nullEngine(BenchmarkData data, Blackhole blackhole)
    {
        for (Slice input : data.inputs) {
            blackhole.consume(nullOperation(input));
        }
    }

    // Each operation on one input, as its benchmark method applies it to each input of a pass.

    @CompilerControl(DONT_INLINE)
    private static boolean containsOperation(TrinoRegexp regexp, Slice input)
    {
        return regexp.contains(input);
    }

    @CompilerControl(DONT_INLINE)
    private static long countOperation(TrinoRegexp regexp, Slice input)
    {
        return regexp.count(input);
    }

    @CompilerControl(DONT_INLINE)
    private static long positionThirdOperation(TrinoRegexp regexp, Slice input)
    {
        return regexp.position(input, 1, 3);
    }

    @CompilerControl(DONT_INLINE)
    private static Slice extractOperation(TrinoRegexp regexp, Slice input)
    {
        return regexp.extract(input);
    }

    @CompilerControl(DONT_INLINE)
    private static List<Slice> extractAllOperation(TrinoRegexp regexp, Slice input)
    {
        return regexp.extractAll(input);
    }

    @CompilerControl(DONT_INLINE)
    private static List<Slice> splitOperation(TrinoRegexp regexp, Slice input)
    {
        return regexp.split(input);
    }

    @CompilerControl(DONT_INLINE)
    private static Slice replaceOperation(TrinoRegexp regexp, Slice replacement, Slice input)
    {
        return regexp.replace(input, replacement);
    }

    @CompilerControl(DONT_INLINE)
    private static Slice replaceLambdaOperation(TrinoRegexp regexp, Function<List<Slice>, Slice> replacement, Slice input)
    {
        return regexp.replace(input, replacement);
    }

    @Benchmark
    public TrinoLikePattern constructLikeWildcardChain(BenchmarkData data)
    {
        return TrinoLikePattern.compile(LIKE_WILDCARD_CHAIN_PATTERN);
    }

    @Benchmark
    public TrinoLikePattern constructLikeUnicodeWildcardChain(BenchmarkData data)
    {
        return TrinoLikePattern.compile(LIKE_UNICODE_WILDCARD_CHAIN_PATTERN);
    }

    @Benchmark
    public TrinoLikePattern constructLikeEscapedWildcardChain(BenchmarkData data)
    {
        return TrinoLikePattern.compile(LIKE_ESCAPED_WILDCARD_CHAIN_PATTERN, TrinoLikePattern.Options.defaults().setEscape('!'));
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkEverydayTrinoRegexp.class, args);
        new Runner(options).run();
    }
}
