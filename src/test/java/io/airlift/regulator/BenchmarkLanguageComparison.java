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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
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
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.airlift.slice.SliceUtf8.lengthOfCodePoint;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;

/// Times each language engine's compile, single-use, and reused operations on one lifecycle
/// workload. Every operation but `compile` is one pass over the workload's [#INPUTS] inputs in
/// order, which JMH counts as one operation per input, and consumes each input's result. Each
/// engine has its own adapter, which holds the inputs in the engine's own representation, a
/// `Slice` array or, for the JDK, a `String` array, and writes each pass out for the engine's own
/// type with the state it reads loaded into locals. The loop calls the adapter's static operation
/// on each input, the one verification calls, so the call in the loop is direct and the benchmark
/// method makes one interface call per pass. The `null` engine returns each input's byte length
/// in the reused operations, so its time per input is the harness's own cost.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(5)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
public class BenchmarkLanguageComparison
{
    /// What reusedFind returns when the source has no match.
    static final long NO_MATCH = -1;

    /// The inputs of every workload, and so the operations of one invocation.
    static final int INPUTS = 8;

    /// The engine that matches nothing and returns each input's byte length.
    static final String NULL_ENGINE = "null";

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"re2", "java", "trino", "rust", "jdk", "joni", NULL_ENGINE})
        public String engine;

        @Param("required-workload-file")
        public String workloadFile;

        private TestingLanguageBenchmarkInputs inputs;
        Compiled compiled;
        SingleUse singleUse;

        @Setup
        public void setup(BenchmarkParams parameters)
                throws Exception
        {
            String benchmark = parameters.getBenchmark();
            setup(benchmark.substring(benchmark.lastIndexOf('.') + 1));
        }

        void setup(String operation)
                throws Exception
        {
            initialize(operation);
            verifyOperation(this, operation);
        }

        private void initialize(String operation)
                throws Exception
        {
            inputs = TestingLanguageBenchmarkInputs.read(Path.of(workloadFile));
            if (inputs.sources().size() != INPUTS) {
                throw new IllegalArgumentException("language workload has %s inputs, not %s".formatted(inputs.sources().size(), INPUTS));
            }
            if (operation.startsWith("reused")) {
                compiled = compile(engine, inputs);
            }
            else if (engine.equals(NULL_ENGINE)) {
                throw new IllegalArgumentException("the null engine has only the reused operations, not " + operation);
            }
            else if (operation.startsWith("singleUse")) {
                singleUse = singleUse(engine, inputs);
            }
        }
    }

    @Benchmark
    public Object compile(BenchmarkData data)
    {
        return compilePattern(data.engine, data.inputs);
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void singleUseContains(BenchmarkData data, Blackhole blackhole)
    {
        data.singleUse.containsPass(blackhole);
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void singleUseCount(BenchmarkData data, Blackhole blackhole)
    {
        data.singleUse.countPass(blackhole);
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void reusedContains(BenchmarkData data, Blackhole blackhole)
    {
        data.compiled.containsPass(blackhole);
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void reusedCount(BenchmarkData data, Blackhole blackhole)
    {
        data.compiled.countPass(blackhole);
    }

    @Benchmark
    @OperationsPerInvocation(INPUTS)
    public void reusedFind(BenchmarkData data, Blackhole blackhole)
    {
        data.compiled.findPass(blackhole);
    }

    static Object compilePattern(String engine, TestingLanguageBenchmarkInputs inputs)
    {
        return switch (engine) {
            case "re2" -> Re2.compile(inputs.pattern());
            case "java" -> JavaRegexp.compile(inputs.pattern());
            case "trino" -> TrinoRegexp.compile(inputs.pattern());
            case "rust" -> RustRegexp.compile(inputs.pattern());
            case "jdk" -> Pattern.compile(inputs.patternText());
            case "joni" -> compileJoni(inputs.pattern());
            default -> throw new IllegalArgumentException("unknown language engine: " + engine);
        };
    }

    private static Regex compileJoni(Slice pattern)
    {
        return new Regex(
                pattern.byteArray(),
                pattern.byteArrayOffset(),
                pattern.byteArrayOffset() + pattern.length(),
                Option.DEFAULT,
                NonStrictUTF8Encoding.INSTANCE,
                Syntax.Java);
    }

    /// Compiles the pattern with `engine` for the reused operations, over every input of `inputs`.
    static Compiled compile(String engine, TestingLanguageBenchmarkInputs inputs)
    {
        if (engine.equals(NULL_ENGINE)) {
            return new NullCompiled(bytes(inputs));
        }
        return adapter(compilePattern(engine, inputs), inputs);
    }

    private static Compiled adapter(Object pattern, TestingLanguageBenchmarkInputs inputs)
    {
        Slice[] bytes = bytes(inputs);
        return switch (pattern) {
            case Re2 re2 -> new Re2Compiled(re2, bytes, re2.matcher(EMPTY_SLICE, 0), new int[2]);
            case JavaRegexp java -> new JavaCompiled(java, bytes, java.matcher(EMPTY_SLICE, 0), new int[2]);
            case TrinoRegexp trino -> new TrinoCompiled(trino, bytes, trino.matcher(EMPTY_SLICE, 0));
            case RustRegexp rust -> new RustCompiled(rust, bytes, rust.matcher(EMPTY_SLICE, 0), new int[2]);
            case Pattern jdk -> new JdkCompiled(jdk.matcher(""), texts(inputs));
            case Regex joni -> new JoniCompiled(joni, bytes);
            default -> throw new IllegalStateException("unhandled compiled pattern");
        };
    }

    static SingleUse singleUse(String engine, TestingLanguageBenchmarkInputs inputs)
    {
        return switch (engine) {
            case "re2" -> new Re2SingleUse(inputs.pattern(), bytes(inputs));
            case "java" -> new JavaSingleUse(inputs.pattern(), bytes(inputs));
            case "trino" -> new TrinoSingleUse(inputs.pattern(), bytes(inputs));
            case "rust" -> new RustSingleUse(inputs.pattern(), bytes(inputs));
            case "jdk" -> new JdkSingleUse(inputs.patternText(), texts(inputs));
            case "joni" -> new JoniSingleUse(inputs.pattern(), bytes(inputs));
            default -> throw new IllegalArgumentException("unknown language engine: " + engine);
        };
    }

    private static Slice[] bytes(TestingLanguageBenchmarkInputs inputs)
    {
        return inputs.sources().stream().map(TestingLanguageBenchmarkInputs.Source::bytes).toArray(Slice[]::new);
    }

    private static String[] texts(TestingLanguageBenchmarkInputs inputs)
    {
        return inputs.sources().stream().map(TestingLanguageBenchmarkInputs.Source::text).toArray(String[]::new);
    }

    /// The first match's bounds in one `long`, the start in the high 32 bits and the end in the low.
    static long bounds(int start, int end)
    {
        return ((long) start << 32) | end;
    }

    /// The null engine's operation: the input's byte length, with no matching.
    static long nullOperation(Slice input)
    {
        return input.length();
    }

    /// One engine's reused compile over the workload's inputs. Each pass method is one invocation of
    /// a reused operation, and calls the adapter's static operation on each input; the per-input
    /// methods call the same operation on one input, for verification outside timing.
    interface Compiled
    {
        /// The inputs every pass reads, in the engine's representation.
        Object[] inputs();

        void containsPass(Blackhole blackhole);

        void countPass(Blackhole blackhole);

        void findPass(Blackhole blackhole);

        /// 1 when input `index` has a match, and 0 when it has none.
        long contains(int index);

        long count(int index);

        /// The first match's bounds in input `index`, as [#bounds(int,int)] packs them, in the offsets the engine reports, or [#NO_MATCH].
        long find(int index);

        /// Converts what [#find] returned for input `index` to UTF-8 byte offsets of the input, outside timing.
        default long utf8Bounds(int index, long bounds)
        {
            return bounds;
        }

        List<String> matches(int index);
    }

    private record Re2Compiled(Re2 pattern, Slice[] inputs, RegexpMatcher matcher, int[] groups)
            implements Compiled
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            Re2 re2 = pattern;
            for (Slice input : inputs) {
                blackhole.consume(contains(re2, input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            Re2 re2 = pattern;
            for (Slice input : inputs) {
                blackhole.consume(count(re2, input));
            }
        }

        @Override
        public void findPass(Blackhole blackhole)
        {
            Re2 re2 = pattern;
            int[] groupZero = groups;
            for (Slice input : inputs) {
                blackhole.consume(find(re2, groupZero, input));
            }
        }

        @Override
        public long contains(int index)
        {
            return contains(pattern, inputs[index]) ? 1 : 0;
        }

        @Override
        public long count(int index)
        {
            return count(pattern, inputs[index]);
        }

        @Override
        public long find(int index)
        {
            return find(pattern, groups, inputs[index]);
        }

        @Override
        public List<String> matches(int index)
        {
            return matcherMatches(matcher, inputs[index]);
        }

        private static boolean contains(Re2 pattern, Slice input)
        {
            return pattern.find(input);
        }

        private static long count(Re2 pattern, Slice input)
        {
            return pattern.count(input);
        }

        private static long find(Re2 pattern, int[] groups, Slice input)
        {
            return pattern.findInto(input, groups) ? bounds(groups[0], groups[1]) : NO_MATCH;
        }
    }

    private record JavaCompiled(JavaRegexp pattern, Slice[] inputs, RegexpMatcher matcher, int[] groups)
            implements Compiled
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            JavaRegexp java = pattern;
            for (Slice input : inputs) {
                blackhole.consume(contains(java, input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            JavaRegexp java = pattern;
            for (Slice input : inputs) {
                blackhole.consume(count(java, input));
            }
        }

        @Override
        public void findPass(Blackhole blackhole)
        {
            JavaRegexp java = pattern;
            int[] groupZero = groups;
            for (Slice input : inputs) {
                blackhole.consume(find(java, groupZero, input));
            }
        }

        @Override
        public long contains(int index)
        {
            return contains(pattern, inputs[index]) ? 1 : 0;
        }

        @Override
        public long count(int index)
        {
            return count(pattern, inputs[index]);
        }

        @Override
        public long find(int index)
        {
            return find(pattern, groups, inputs[index]);
        }

        @Override
        public List<String> matches(int index)
        {
            return matcherMatches(matcher, inputs[index]);
        }

        private static boolean contains(JavaRegexp pattern, Slice input)
        {
            return pattern.find(input);
        }

        private static long count(JavaRegexp pattern, Slice input)
        {
            return pattern.count(input);
        }

        private static long find(JavaRegexp pattern, int[] groups, Slice input)
        {
            return pattern.findInto(input, groups) ? bounds(groups[0], groups[1]) : NO_MATCH;
        }
    }

    // Trino's API has no findInto; its matcher, retaining group zero only, is the call that reports a match's bounds.
    private record TrinoCompiled(TrinoRegexp pattern, Slice[] inputs, TrinoRegexpMatcher matcher)
            implements Compiled
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            TrinoRegexp trino = pattern;
            for (Slice input : inputs) {
                blackhole.consume(contains(trino, input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            TrinoRegexp trino = pattern;
            for (Slice input : inputs) {
                blackhole.consume(count(trino, input));
            }
        }

        @Override
        public void findPass(Blackhole blackhole)
        {
            TrinoRegexpMatcher groupZero = matcher;
            for (Slice input : inputs) {
                blackhole.consume(find(groupZero, input));
            }
        }

        @Override
        public long contains(int index)
        {
            return contains(pattern, inputs[index]) ? 1 : 0;
        }

        @Override
        public long count(int index)
        {
            return count(pattern, inputs[index]);
        }

        @Override
        public long find(int index)
        {
            return find(matcher, inputs[index]);
        }

        @Override
        public List<String> matches(int index)
        {
            return pattern.extractAll(inputs[index]).stream().map(BenchmarkLanguageComparison::hex).toList();
        }

        private static boolean contains(TrinoRegexp pattern, Slice input)
        {
            return pattern.contains(input);
        }

        private static long count(TrinoRegexp pattern, Slice input)
        {
            return pattern.count(input);
        }

        private static long find(TrinoRegexpMatcher groupZero, Slice input)
        {
            return groupZero.reset(input).find() ? bounds(groupZero.start(), groupZero.end()) : NO_MATCH;
        }
    }

    private record RustCompiled(RustRegexp pattern, Slice[] inputs, RegexpMatcher matcher, int[] groups)
            implements Compiled
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            RustRegexp rust = pattern;
            for (Slice input : inputs) {
                blackhole.consume(contains(rust, input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            RustRegexp rust = pattern;
            for (Slice input : inputs) {
                blackhole.consume(count(rust, input));
            }
        }

        @Override
        public void findPass(Blackhole blackhole)
        {
            RustRegexp rust = pattern;
            int[] groupZero = groups;
            for (Slice input : inputs) {
                blackhole.consume(find(rust, groupZero, input));
            }
        }

        @Override
        public long contains(int index)
        {
            return contains(pattern, inputs[index]) ? 1 : 0;
        }

        @Override
        public long count(int index)
        {
            return count(pattern, inputs[index]);
        }

        @Override
        public long find(int index)
        {
            return find(pattern, groups, inputs[index]);
        }

        @Override
        public List<String> matches(int index)
        {
            return matcherMatches(matcher, inputs[index]);
        }

        private static boolean contains(RustRegexp pattern, Slice input)
        {
            return pattern.find(input);
        }

        private static long count(RustRegexp pattern, Slice input)
        {
            return pattern.count(input);
        }

        private static long find(RustRegexp pattern, int[] groups, Slice input)
        {
            return pattern.findInto(input, groups) ? bounds(groups[0], groups[1]) : NO_MATCH;
        }
    }

    // The JDK matches the inputs decoded to strings before timing, with one matcher reset to each.
    private record JdkCompiled(Matcher matcher, String[] inputs)
            implements Compiled
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            Matcher jdk = matcher;
            for (String input : inputs) {
                blackhole.consume(contains(jdk, input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            Matcher jdk = matcher;
            for (String input : inputs) {
                blackhole.consume(count(jdk, input));
            }
        }

        @Override
        public void findPass(Blackhole blackhole)
        {
            Matcher jdk = matcher;
            for (String input : inputs) {
                blackhole.consume(find(jdk, input));
            }
        }

        @Override
        public long contains(int index)
        {
            return contains(matcher, inputs[index]) ? 1 : 0;
        }

        @Override
        public long count(int index)
        {
            return count(matcher, inputs[index]);
        }

        @Override
        public long find(int index)
        {
            return find(matcher, inputs[index]);
        }

        @Override
        public long utf8Bounds(int index, long bounds)
        {
            if (bounds == NO_MATCH) {
                return bounds;
            }
            String text = inputs[index];
            return bounds(utf8Slice(text.substring(0, (int) (bounds >>> 32))).length(), utf8Slice(text.substring(0, (int) bounds)).length());
        }

        @Override
        public List<String> matches(int index)
        {
            matcher.reset(inputs[index]);
            List<String> matches = new ArrayList<>();
            while (matcher.find()) {
                matches.add(hex(utf8Slice(matcher.group())));
            }
            return matches;
        }

        private static boolean contains(Matcher matcher, String input)
        {
            return matcher.reset(input).find();
        }

        private static long count(Matcher matcher, String input)
        {
            return countMatches(matcher.reset(input));
        }

        // UTF-16 offsets of the String; utf8Bounds converts them outside timing.
        private static long find(Matcher matcher, String input)
        {
            return matcher.reset(input).find() ? bounds(matcher.start(), matcher.end()) : NO_MATCH;
        }
    }

    // Joni builds a matcher for each input, as Trino's byte-array route does.
    private record JoniCompiled(Regex pattern, Slice[] inputs)
            implements Compiled
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            Regex joni = pattern;
            for (Slice input : inputs) {
                blackhole.consume(joniContains(joni, input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            Regex joni = pattern;
            for (Slice input : inputs) {
                blackhole.consume(joniCount(joni, input));
            }
        }

        @Override
        public void findPass(Blackhole blackhole)
        {
            Regex joni = pattern;
            for (Slice input : inputs) {
                blackhole.consume(joniFind(joni, input));
            }
        }

        @Override
        public long contains(int index)
        {
            return joniContains(pattern, inputs[index]) ? 1 : 0;
        }

        @Override
        public long count(int index)
        {
            return joniCount(pattern, inputs[index]);
        }

        @Override
        public long find(int index)
        {
            return joniFind(pattern, inputs[index]);
        }

        @Override
        public List<String> matches(int index)
        {
            List<String> matches = new ArrayList<>();
            joniFindAll(pattern, inputs[index], matches);
            return matches;
        }
    }

    private record NullCompiled(Slice[] inputs)
            implements Compiled
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            for (Slice input : inputs) {
                blackhole.consume(nullOperation(input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            for (Slice input : inputs) {
                blackhole.consume(nullOperation(input));
            }
        }

        @Override
        public void findPass(Blackhole blackhole)
        {
            for (Slice input : inputs) {
                blackhole.consume(nullOperation(input));
            }
        }

        @Override
        public long contains(int index)
        {
            return nullOperation(inputs[index]);
        }

        @Override
        public long count(int index)
        {
            return nullOperation(inputs[index]);
        }

        @Override
        public long find(int index)
        {
            return nullOperation(inputs[index]);
        }

        @Override
        public List<String> matches(int index)
        {
            throw new UnsupportedOperationException("the null engine does not match");
        }
    }

    /// One engine's single use over the workload's inputs: each pass compiles the pattern for each
    /// input and runs one operation on it, through the record's static operation. The per-input
    /// methods call the same operation on one input and return what the pass consumes, as 1 or 0
    /// for contains, for verification outside timing.
    interface SingleUse
    {
        /// The inputs every pass reads, in the engine's representation.
        Object[] inputs();

        void containsPass(Blackhole blackhole);

        void countPass(Blackhole blackhole);

        long contains(int index);

        long count(int index);
    }

    private record Re2SingleUse(Slice pattern, Slice[] inputs)
            implements SingleUse
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            Slice expression = pattern;
            for (Slice input : inputs) {
                blackhole.consume(contains(expression, input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            Slice expression = pattern;
            for (Slice input : inputs) {
                blackhole.consume(count(expression, input));
            }
        }

        @Override
        public long contains(int index)
        {
            return contains(pattern, inputs[index]) ? 1 : 0;
        }

        @Override
        public long count(int index)
        {
            return count(pattern, inputs[index]);
        }

        private static boolean contains(Slice pattern, Slice input)
        {
            return Re2.compile(pattern).find(input);
        }

        private static long count(Slice pattern, Slice input)
        {
            return Re2.compile(pattern).count(input);
        }
    }

    private record JavaSingleUse(Slice pattern, Slice[] inputs)
            implements SingleUse
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            Slice expression = pattern;
            for (Slice input : inputs) {
                blackhole.consume(contains(expression, input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            Slice expression = pattern;
            for (Slice input : inputs) {
                blackhole.consume(count(expression, input));
            }
        }

        @Override
        public long contains(int index)
        {
            return contains(pattern, inputs[index]) ? 1 : 0;
        }

        @Override
        public long count(int index)
        {
            return count(pattern, inputs[index]);
        }

        private static boolean contains(Slice pattern, Slice input)
        {
            return JavaRegexp.compile(pattern).find(input);
        }

        private static long count(Slice pattern, Slice input)
        {
            return JavaRegexp.compile(pattern).count(input);
        }
    }

    private record TrinoSingleUse(Slice pattern, Slice[] inputs)
            implements SingleUse
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            Slice expression = pattern;
            for (Slice input : inputs) {
                blackhole.consume(contains(expression, input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            Slice expression = pattern;
            for (Slice input : inputs) {
                blackhole.consume(count(expression, input));
            }
        }

        @Override
        public long contains(int index)
        {
            return contains(pattern, inputs[index]) ? 1 : 0;
        }

        @Override
        public long count(int index)
        {
            return count(pattern, inputs[index]);
        }

        private static boolean contains(Slice pattern, Slice input)
        {
            return TrinoRegexp.compile(pattern).contains(input);
        }

        private static long count(Slice pattern, Slice input)
        {
            return TrinoRegexp.compile(pattern).count(input);
        }
    }

    private record RustSingleUse(Slice pattern, Slice[] inputs)
            implements SingleUse
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            Slice expression = pattern;
            for (Slice input : inputs) {
                blackhole.consume(contains(expression, input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            Slice expression = pattern;
            for (Slice input : inputs) {
                blackhole.consume(count(expression, input));
            }
        }

        @Override
        public long contains(int index)
        {
            return contains(pattern, inputs[index]) ? 1 : 0;
        }

        @Override
        public long count(int index)
        {
            return count(pattern, inputs[index]);
        }

        private static boolean contains(Slice pattern, Slice input)
        {
            return RustRegexp.compile(pattern).find(input);
        }

        private static long count(Slice pattern, Slice input)
        {
            return RustRegexp.compile(pattern).count(input);
        }
    }

    private record JdkSingleUse(String pattern, String[] inputs)
            implements SingleUse
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            String expression = pattern;
            for (String input : inputs) {
                blackhole.consume(contains(expression, input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            String expression = pattern;
            for (String input : inputs) {
                blackhole.consume(count(expression, input));
            }
        }

        @Override
        public long contains(int index)
        {
            return contains(pattern, inputs[index]) ? 1 : 0;
        }

        @Override
        public long count(int index)
        {
            return count(pattern, inputs[index]);
        }

        private static boolean contains(String pattern, String input)
        {
            return Pattern.compile(pattern).matcher(input).find();
        }

        private static long count(String pattern, String input)
        {
            return countMatches(Pattern.compile(pattern).matcher(input));
        }
    }

    private record JoniSingleUse(Slice pattern, Slice[] inputs)
            implements SingleUse
    {
        @Override
        public void containsPass(Blackhole blackhole)
        {
            Slice expression = pattern;
            for (Slice input : inputs) {
                blackhole.consume(contains(expression, input));
            }
        }

        @Override
        public void countPass(Blackhole blackhole)
        {
            Slice expression = pattern;
            for (Slice input : inputs) {
                blackhole.consume(count(expression, input));
            }
        }

        @Override
        public long contains(int index)
        {
            return contains(pattern, inputs[index]) ? 1 : 0;
        }

        @Override
        public long count(int index)
        {
            return count(pattern, inputs[index]);
        }

        private static boolean contains(Slice pattern, Slice input)
        {
            return joniContains(compileJoni(pattern), input);
        }

        private static long count(Slice pattern, Slice input)
        {
            return joniCount(compileJoni(pattern), input);
        }
    }

    private static List<String> matcherMatches(RegexpMatcher matcher, Slice input)
    {
        matcher.reset(input);
        List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(hex(matcher.group()));
        }
        return matches;
    }

    private static boolean joniContains(Regex pattern, Slice input)
    {
        int base = input.byteArrayOffset();
        return pattern.matcher(input.byteArray(), base, base + input.length())
                .search(base, base + input.length(), Option.DEFAULT) >= 0;
    }

    // Joni reports a match's bounds relative to the start the matcher was given, the Slice's first byte.
    private static long joniFind(Regex pattern, Slice input)
    {
        int base = input.byteArrayOffset();
        io.airlift.joni.Matcher matcher = pattern.matcher(input.byteArray(), base, base + input.length());
        return matcher.search(base, base + input.length(), Option.DEFAULT) >= 0 ? bounds(matcher.getBegin(), matcher.getEnd()) : NO_MATCH;
    }

    private static long joniCount(Regex pattern, Slice input)
    {
        return joniFindAll(pattern, input, null);
    }

    private static long joniFindAll(Regex pattern, Slice input, List<String> matches)
    {
        int base = input.byteArrayOffset();
        io.airlift.joni.Matcher matcher = pattern.matcher(input.byteArray(), base, base + input.length());
        long count = 0;
        int next = 0;
        while (next <= input.length() && matcher.search(base + next, base + input.length(), Option.DEFAULT) >= 0) {
            count++;
            int begin = matcher.getBegin();
            int end = matcher.getEnd();
            if (matches != null) {
                matches.add(hex(input.slice(begin, end - begin)));
            }
            next = end;
            if (begin == end) {
                next += end == input.length() ? 1 : lengthOfCodePoint(input, end);
            }
        }
        return count;
    }

    /// Whether bounds, in UTF-8 offsets, hold the bytes of the first of the matches, or no match when there are none.
    static boolean holdsFirstMatch(TestingLanguageBenchmarkInputs.Source source, long bounds, List<String> matches)
    {
        if (bounds == NO_MATCH) {
            return matches.isEmpty();
        }
        int start = (int) (bounds >>> 32);
        int end = (int) bounds;
        return !matches.isEmpty() && 0 <= start && start <= end && end <= source.bytes().length()
                && hex(source.bytes().slice(start, end - start)).equals(matches.getFirst());
    }

    private static String hex(Slice source)
    {
        return HexFormat.of().formatHex(source.getBytes());
    }

    private static long countMatches(Matcher matcher)
    {
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    public static void main(String[] args)
            throws Exception
    {
        if (args.length == 4 && args[0].equals("--verify-operation")) {
            BenchmarkData data = new BenchmarkData();
            data.engine = args[1];
            data.workloadFile = args[2];
            String operation = args[3];
            System.err.println("native-access=" + Dfa.nativeAccessEnabled());
            System.err.println("phase=compile");
            data.initialize(operation);
            if (!operation.equals("compile")) {
                System.err.println("phase=execution");
            }
            System.out.print(verifyOperation(data, operation));
            return;
        }
        if (args.length != 3 || !args[0].equals("--verify")) {
            throw new IllegalArgumentException("usage: --verify <engine> <workload-file>");
        }
        TestingLanguageBenchmarkInputs inputs = TestingLanguageBenchmarkInputs.read(Path.of(args[2]));
        System.err.println("native-access=" + Dfa.nativeAccessEnabled());
        System.err.println("phase=compile");
        Object pattern = compilePattern(args[1], inputs);
        System.err.println("phase=execution");
        Compiled compiled = adapter(pattern, inputs);
        for (int index = 0; index < inputs.sources().size(); index++) {
            TestingLanguageBenchmarkInputs.Source source = inputs.sources().get(index);
            List<String> matches = compiled.matches(index);
            long count = compiled.count(index);
            boolean contains = compiled.contains(index) == 1;
            long bounds = compiled.utf8Bounds(index, compiled.find(index));
            if (count != source.expectedCount() || contains != (count != 0) || matches.size() != count || !holdsFirstMatch(source, bounds, matches)) {
                throw new IllegalStateException("verification failed for " + args[1]);
            }
            System.out.println(count + ":" + String.join(",", matches));
        }
    }

    static String verifyOperation(BenchmarkData data, String operation)
    {
        if (operation.equals("compile")) {
            if (new BenchmarkLanguageComparison().compile(data) == null) {
                throw new IllegalStateException("compilation returned null");
            }
            return "compiled\n";
        }
        StringBuilder trace = new StringBuilder();
        List<TestingLanguageBenchmarkInputs.Source> sources = data.inputs.sources();
        long[] firstPass = new long[sources.size()];
        boolean nullEngine = data.engine.equals(NULL_ENGINE);
        // Exercise exactly the selected operation, twice through every input in order, on the adapter whose pass the
        // benchmark times. A count timeout must not prevent compilation or contains from being qualified.
        for (int pass = 0; pass < 2; pass++) {
            for (int index = 0; index < sources.size(); index++) {
                TestingLanguageBenchmarkInputs.Source source = sources.get(index);
                long actual = switch (operation) {
                    case "singleUseContains" -> data.singleUse.contains(index);
                    case "singleUseCount" -> data.singleUse.count(index);
                    case "reusedContains" -> data.compiled.contains(index);
                    case "reusedCount" -> data.compiled.count(index);
                    case "reusedFind" -> data.compiled.utf8Bounds(index, data.compiled.find(index));
                    default -> throw new IllegalArgumentException("unknown operation: " + operation);
                };
                // The inputs record counts but no expected first-match bounds, so collection compares the first pass's
                // bounds across engines and with the trace. The second pass must repeat the first exactly, so reused
                // state that goes wrong on a later pass is rejected too. The null engine returns each input's byte length.
                boolean verified;
                if (nullEngine) {
                    verified = actual == source.bytes().length();
                }
                else if (operation.equals("reusedFind")) {
                    verified = (actual == NO_MATCH) == (source.expectedCount() == 0);
                }
                else {
                    verified = actual == (operation.endsWith("Contains") ? (source.expectedCount() == 0 ? 0 : 1) : source.expectedCount());
                }
                if (!verified || (pass > 0 && actual != firstPass[index])) {
                    throw new IllegalStateException("verification failed for " + data.engine + "/" + operation);
                }
                if (pass == 0) {
                    firstPass[index] = actual;
                    trace.append(actual).append('\n');
                }
            }
        }
        return trace.toString();
    }
}
