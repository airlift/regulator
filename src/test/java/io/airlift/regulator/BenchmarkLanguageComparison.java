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
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static io.airlift.slice.SliceUtf8.lengthOfCodePoint;
import static io.airlift.slice.Slices.utf8Slice;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(5)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
public class BenchmarkLanguageComparison
{
    /// What reusedFind returns when the source has no match.
    static final long NO_MATCH = -1;

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"re2", "java", "trino", "rust", "jdk", "joni"})
        public String engine;

        @Param("required-workload-file")
        public String workloadFile;

        private TestingLanguageBenchmarkInputs inputs;
        Compiled compiled;
        private int sourceIndex;

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
            sourceIndex = 0;
            if (operation.startsWith("reused")) {
                compiled = compile(engine, inputs);
            }
        }

        private TestingLanguageBenchmarkInputs.Source nextSource()
        {
            TestingLanguageBenchmarkInputs.Source source = inputs.sources().get(sourceIndex);
            sourceIndex = (sourceIndex + 1) % inputs.sources().size();
            return source;
        }
    }

    @Benchmark
    public Object compile(BenchmarkData data)
    {
        return compilePattern(data.engine, data.inputs);
    }

    @Benchmark
    public boolean singleUseContains(BenchmarkData data)
    {
        TestingLanguageBenchmarkInputs.Source source = data.nextSource();
        return switch (compilePattern(data.engine, data.inputs)) {
            case Re2 re2 -> re2.find(source.bytes());
            case JavaRegexp java -> java.find(source.bytes());
            case TrinoRegexp trino -> trino.contains(source.bytes());
            case RustRegexp rust -> rust.find(source.bytes());
            case Pattern jdk -> jdk.matcher(source.text()).find();
            case Regex joni -> new JoniCompiled(joni).contains(source);
            default -> throw new IllegalStateException("unhandled compiled pattern");
        };
    }

    @Benchmark
    public long singleUseCount(BenchmarkData data)
    {
        TestingLanguageBenchmarkInputs.Source source = data.nextSource();
        return switch (compilePattern(data.engine, data.inputs)) {
            case Re2 re2 -> re2.count(source.bytes());
            case JavaRegexp java -> java.count(source.bytes());
            case TrinoRegexp trino -> trino.count(source.bytes());
            case RustRegexp rust -> rust.count(source.bytes());
            case Pattern jdk -> countMatches(jdk.matcher(source.text()));
            case Regex joni -> new JoniCompiled(joni).count(source);
            default -> throw new IllegalStateException("unhandled compiled pattern");
        };
    }

    @Benchmark
    public boolean reusedContains(BenchmarkData data)
    {
        return data.compiled.contains(data.nextSource());
    }

    @Benchmark
    public long reusedCount(BenchmarkData data)
    {
        return data.compiled.count(data.nextSource());
    }

    @Benchmark
    public long reusedFind(BenchmarkData data)
    {
        return data.compiled.find(data.nextSource());
    }

    static Object compilePattern(String engine, TestingLanguageBenchmarkInputs inputs)
    {
        return switch (engine) {
            case "re2" -> Re2.compile(inputs.pattern());
            case "java" -> JavaRegexp.compile(inputs.pattern());
            case "trino" -> TrinoRegexp.compile(inputs.pattern());
            case "rust" -> RustRegexp.compile(inputs.pattern());
            case "jdk" -> Pattern.compile(inputs.patternText());
            case "joni" -> new Regex(
                    inputs.pattern().byteArray(),
                    inputs.pattern().byteArrayOffset(),
                    inputs.pattern().byteArrayOffset() + inputs.pattern().length(),
                    Option.DEFAULT,
                    NonStrictUTF8Encoding.INSTANCE,
                    Syntax.Java);
            default -> throw new IllegalArgumentException("unknown language engine: " + engine);
        };
    }

    static Compiled compile(String engine, TestingLanguageBenchmarkInputs inputs)
    {
        return createMatcher(compilePattern(engine, inputs), inputs.sources().getFirst());
    }

    private static Compiled createMatcher(Object pattern, TestingLanguageBenchmarkInputs.Source source)
    {
        return switch (pattern) {
            case Re2 re2 -> new SliceCompiled(re2, null, re2.matcher(source.bytes(), 0), new int[2]);
            case JavaRegexp java -> new SliceCompiled(null, java, java.matcher(source.bytes(), 0), new int[2]);
            case TrinoRegexp trino -> new TrinoCompiled(trino, trino.matcher(source.bytes(), 0));
            case RustRegexp rust -> new RustCompiled(rust, rust.matcher(source.bytes(), 0), new int[2]);
            case Pattern jdk -> new JdkCompiled(jdk.matcher(source.text()));
            case Regex joni -> new JoniCompiled(joni);
            default -> throw new IllegalStateException("unhandled compiled pattern");
        };
    }

    /// The first match's bounds in one `long`, the start in the high 32 bits and the end in the low.
    static long bounds(int start, int end)
    {
        return ((long) start << 32) | end;
    }

    interface Compiled
    {
        boolean contains(TestingLanguageBenchmarkInputs.Source source);

        long count(TestingLanguageBenchmarkInputs.Source source);

        /// The first match's bounds, as [#bounds(int,int)] packs them, in the offsets the engine reports, or [#NO_MATCH].
        long find(TestingLanguageBenchmarkInputs.Source source);

        /// Converts what [#find] returned for the source to UTF-8 byte offsets of the source, outside timing.
        default long utf8Bounds(TestingLanguageBenchmarkInputs.Source source, long bounds)
        {
            return bounds;
        }

        List<String> matches(TestingLanguageBenchmarkInputs.Source source);
    }

    private record SliceCompiled(Re2 re2, JavaRegexp java, RegexpMatcher matcher, int[] groups)
            implements Compiled
    {
        @Override
        public boolean contains(TestingLanguageBenchmarkInputs.Source source)
        {
            return re2 != null ? re2.find(source.bytes()) : java.find(source.bytes());
        }

        @Override
        public long count(TestingLanguageBenchmarkInputs.Source source)
        {
            return re2 != null ? re2.count(source.bytes()) : java.count(source.bytes());
        }

        @Override
        public long find(TestingLanguageBenchmarkInputs.Source source)
        {
            boolean found = re2 != null ? re2.findInto(source.bytes(), groups) : java.findInto(source.bytes(), groups);
            return found ? bounds(groups[0], groups[1]) : NO_MATCH;
        }

        @Override
        public List<String> matches(TestingLanguageBenchmarkInputs.Source source)
        {
            matcher.reset(source.bytes());
            List<String> matches = new ArrayList<>();
            while (matcher.find()) {
                matches.add(hex(matcher.group()));
            }
            return matches;
        }
    }

    // Trino's API has no findInto; its matcher, retaining group zero only, is the call that reports a match's bounds.
    private record TrinoCompiled(TrinoRegexp pattern, TrinoRegexpMatcher matcher)
            implements Compiled
    {
        @Override
        public boolean contains(TestingLanguageBenchmarkInputs.Source source)
        {
            return pattern.contains(source.bytes());
        }

        @Override
        public long count(TestingLanguageBenchmarkInputs.Source source)
        {
            return pattern.count(source.bytes());
        }

        @Override
        public long find(TestingLanguageBenchmarkInputs.Source source)
        {
            return matcher.reset(source.bytes()).find() ? bounds(matcher.start(), matcher.end()) : NO_MATCH;
        }

        @Override
        public List<String> matches(TestingLanguageBenchmarkInputs.Source source)
        {
            return pattern.extractAll(source.bytes()).stream().map(BenchmarkLanguageComparison::hex).toList();
        }
    }

    private record RustCompiled(RustRegexp pattern, RegexpMatcher matcher, int[] groups)
            implements Compiled
    {
        @Override
        public boolean contains(TestingLanguageBenchmarkInputs.Source source)
        {
            return pattern.find(source.bytes());
        }

        @Override
        public long count(TestingLanguageBenchmarkInputs.Source source)
        {
            return pattern.count(source.bytes());
        }

        @Override
        public long find(TestingLanguageBenchmarkInputs.Source source)
        {
            return pattern.findInto(source.bytes(), groups) ? bounds(groups[0], groups[1]) : NO_MATCH;
        }

        @Override
        public List<String> matches(TestingLanguageBenchmarkInputs.Source source)
        {
            matcher.reset(source.bytes());
            List<String> matches = new ArrayList<>();
            while (matcher.find()) {
                matches.add(hex(matcher.group()));
            }
            return matches;
        }
    }

    private record JdkCompiled(java.util.regex.Matcher matcher)
            implements Compiled
    {
        @Override
        public boolean contains(TestingLanguageBenchmarkInputs.Source source)
        {
            return matcher.reset(source.text()).find();
        }

        @Override
        public long count(TestingLanguageBenchmarkInputs.Source source)
        {
            matcher.reset(source.text());
            return countMatches(matcher);
        }

        // UTF-16 offsets of the String; utf8Bounds converts them outside timing.
        @Override
        public long find(TestingLanguageBenchmarkInputs.Source source)
        {
            return matcher.reset(source.text()).find() ? bounds(matcher.start(), matcher.end()) : NO_MATCH;
        }

        @Override
        public long utf8Bounds(TestingLanguageBenchmarkInputs.Source source, long bounds)
        {
            if (bounds == NO_MATCH) {
                return bounds;
            }
            String text = source.text();
            return bounds(utf8Slice(text.substring(0, (int) (bounds >>> 32))).length(), utf8Slice(text.substring(0, (int) bounds)).length());
        }

        @Override
        public List<String> matches(TestingLanguageBenchmarkInputs.Source source)
        {
            matcher.reset(source.text());
            List<String> matches = new ArrayList<>();
            while (matcher.find()) {
                matches.add(hex(utf8Slice(matcher.group())));
            }
            return matches;
        }
    }

    private record JoniCompiled(Regex pattern)
            implements Compiled
    {
        @Override
        public boolean contains(TestingLanguageBenchmarkInputs.Source source)
        {
            Slice bytes = source.bytes();
            int base = bytes.byteArrayOffset();
            return pattern.matcher(bytes.byteArray(), base, base + bytes.length())
                    .search(base, base + bytes.length(), Option.DEFAULT) >= 0;
        }

        @Override
        public long count(TestingLanguageBenchmarkInputs.Source source)
        {
            return findAll(source.bytes(), null);
        }

        // Joni reports a match's bounds relative to the start the matcher was given, the Slice's first byte.
        @Override
        public long find(TestingLanguageBenchmarkInputs.Source source)
        {
            Slice bytes = source.bytes();
            int base = bytes.byteArrayOffset();
            io.airlift.joni.Matcher matcher = pattern.matcher(bytes.byteArray(), base, base + bytes.length());
            return matcher.search(base, base + bytes.length(), Option.DEFAULT) >= 0 ? bounds(matcher.getBegin(), matcher.getEnd()) : NO_MATCH;
        }

        @Override
        public List<String> matches(TestingLanguageBenchmarkInputs.Source source)
        {
            List<String> matches = new ArrayList<>();
            findAll(source.bytes(), matches);
            return matches;
        }

        private long findAll(Slice source, List<String> matches)
        {
            int base = source.byteArrayOffset();
            io.airlift.joni.Matcher matcher = pattern.matcher(source.byteArray(), base, base + source.length());
            long count = 0;
            int next = 0;
            while (next <= source.length() && matcher.search(base + next, base + source.length(), Option.DEFAULT) >= 0) {
                count++;
                int begin = matcher.getBegin();
                int end = matcher.getEnd();
                if (matches != null) {
                    matches.add(hex(source.slice(begin, end - begin)));
                }
                next = end;
                if (begin == end) {
                    next += end == source.length() ? 1 : lengthOfCodePoint(source, end);
                }
            }
            return count;
        }
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

    private static long countMatches(java.util.regex.Matcher matcher)
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
        Compiled compiled = createMatcher(pattern, inputs.sources().getFirst());
        for (TestingLanguageBenchmarkInputs.Source source : inputs.sources()) {
            List<String> matches = compiled.matches(source);
            long count = compiled.count(source);
            boolean contains = compiled.contains(source);
            long bounds = compiled.utf8Bounds(source, compiled.find(source));
            if (count != source.expectedCount() || contains != (count != 0) || matches.size() != count || !holdsFirstMatch(source, bounds, matches)) {
                throw new IllegalStateException("verification failed for " + args[1]);
            }
            System.out.println(count + ":" + String.join(",", matches));
        }
    }

    static String verifyOperation(BenchmarkData data, String operation)
    {
        BenchmarkLanguageComparison benchmark = new BenchmarkLanguageComparison();
        if (operation.equals("compile")) {
            if (benchmark.compile(data) == null) {
                throw new IllegalStateException("compilation returned null");
            }
            return "compiled\n";
        }
        StringBuilder trace = new StringBuilder();
        List<TestingLanguageBenchmarkInputs.Source> sources = data.inputs.sources();
        long[] firstPass = new long[sources.size()];
        // Exercise exactly the selected public operation, twice through the complete rotation.
        // A count timeout must not prevent compilation or contains from being qualified.
        for (int pass = 0; pass < 2; pass++) {
            for (int index = 0; index < sources.size(); index++) {
                TestingLanguageBenchmarkInputs.Source source = sources.get(index);
                long actual = switch (operation) {
                    case "singleUseContains" -> benchmark.singleUseContains(data) ? 1 : 0;
                    case "singleUseCount" -> benchmark.singleUseCount(data);
                    case "reusedContains" -> benchmark.reusedContains(data) ? 1 : 0;
                    case "reusedCount" -> benchmark.reusedCount(data);
                    case "reusedFind" -> data.compiled.utf8Bounds(source, benchmark.reusedFind(data));
                    default -> throw new IllegalArgumentException("unknown operation: " + operation);
                };
                // The inputs record counts but no expected first-match bounds, so collection compares the first pass's
                // bounds across engines and with the trace. The second pass must repeat the first exactly, so reused
                // state that goes wrong once the rotation wraps is rejected too.
                boolean verified = operation.equals("reusedFind") ? (actual == NO_MATCH) == (source.expectedCount() == 0)
                        : actual == (operation.endsWith("Contains") ? (source.expectedCount() == 0 ? 0 : 1) : source.expectedCount());
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
