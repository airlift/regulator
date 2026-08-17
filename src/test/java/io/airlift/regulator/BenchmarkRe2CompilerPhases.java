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

import io.airlift.jcodings.specific.NonStrictUTF8Encoding;
import io.airlift.joni.Option;
import io.airlift.joni.Regex;
import io.airlift.joni.Syntax;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("MethodMayBeStatic")
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(3)
@Warmup(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkRe2CompilerPhases
{
    private static final String PARENS = "([ -~])*(A)(B)(C)(D)(E)(F)(G)(H)(I)(J)(K)(L)(M)(N)(O)(P)(Q)(R)(S)(T)(U)(V)(W)(X)(Y)(Z)$";

    @State(Scope.Thread)
    public static class CompilePatternState
    {
        @Param({
                "(.*)-(\\d+)-of-(\\d+)",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
                "[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
                PARENS,
                "[0-9]+.(.*)",
                "(?i)ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
                "[a-z]+-[0-9]+",
                "[a-z]{8}-[0-9]{4}",
        })
        String pattern;

        byte[] patternBytes;
        Slice patternSlice;
        Regexp preParsed;
        Regexp normalized;

        @Setup(Level.Trial)
        public void setup()
        {
            patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
            patternSlice = Slices.wrappedBuffer(patternBytes);
            ParseResult parsed = RegexpParser.parse(patternSlice, Regexp.LIKE_PERL);
            preParsed = parsed.regexp();
            normalized = Simplifier.simplify(preParsed);
        }
    }

    @Benchmark
    public Object parse(CompilePatternState state)
    {
        return RegexpParser.parse(state.patternSlice, Regexp.LIKE_PERL);
    }

    @Benchmark
    public Object simplify(CompilePatternState state)
    {
        return Simplifier.simplify(state.preParsed);
    }

    @Benchmark
    public Object analyzeExpression(CompilePatternState state)
    {
        return ExpressionAnalysis.analyzeNormalized(state.normalized);
    }

    @Benchmark
    public Object analyzeRequiredPrefix(CompilePatternState state)
    {
        return state.preParsed.requiredPrefix();
    }

    @Benchmark
    public Object analyzeMatchLength(CompilePatternState state)
    {
        return MatchLength.analyze(state.preParsed);
    }

    @Benchmark
    public Object compileNormalizedToRawProg(CompilePatternState state)
    {
        return Compiler.compileNormalizedForBenchmark(state.normalized, false, 0, Compiler.CompileStage.RAW);
    }

    @Benchmark
    public Object compileNormalizedToOptimizedProg(CompilePatternState state)
    {
        return Compiler.compileNormalizedForBenchmark(state.normalized, false, 0, Compiler.CompileStage.OPTIMIZED);
    }

    @Benchmark
    public Object compileNormalizedToFlattenedProg(CompilePatternState state)
    {
        return Compiler.compileNormalizedForBenchmark(state.normalized, false, 0, Compiler.CompileStage.FLATTENED);
    }

    @Benchmark
    public Object compileNormalizedToByteMapProg(CompilePatternState state)
    {
        return Compiler.compileNormalizedForBenchmark(state.normalized, false, 0, Compiler.CompileStage.BYTEMAP);
    }

    @Benchmark
    public Object compileNormalizedToOnePassProg(CompilePatternState state)
    {
        return Compiler.compileNormalized(state.normalized, false, 0);
    }

    @Benchmark
    public Object compileRe2(CompilePatternState state)
    {
        return Re2.compile(state.patternSlice);
    }

    @Benchmark
    public Object compileTrinoRegexp(CompilePatternState state)
    {
        return TrinoRegexp.compile(state.patternSlice);
    }

    @Benchmark
    public Object compileJoni(CompilePatternState state)
    {
        return new Regex(
                state.patternBytes,
                0,
                state.patternBytes.length,
                Option.DEFAULT,
                NonStrictUTF8Encoding.INSTANCE,
                Syntax.Java);
    }

    public static void main(String[] args)
            throws Throwable
    {
        Options options = Re2BenchmarkRunner.buildOptions(BenchmarkRe2CompilerPhases.class, args);
        new Runner(options).run();
    }
}
