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
import io.airlift.joni.Region;
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static io.airlift.slice.SliceUtf8.lengthOfCodePoint;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

/// Shared bulk operations over pinned Rebar inputs, through each language's public API.
/// A workload may name the byte-frequency distribution the Regulator languages compile with;
/// the JDK and Joni comparators have no such option and ignore it.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(5)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
public class BenchmarkLanguageBulk
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"re2", "java", "trino", "rust", "jdk", "joni"})
        public String engine;

        @Param("required-workload-file")
        public String workloadFile;

        @Param("0")
        public long expectedResult;

        private RebarRunner.Benchmark input;
        private Slice patternBytes;
        private String patternText;
        private boolean rustBytes;
        private Object pattern;
        private RegexpMatcher sliceMatcher;
        private java.util.regex.Matcher jdkMatcher;
        private Operation operation;
        private Source[] sources;

        @Setup
        public void setup()
                throws Exception
        {
            initialize();
            if (!input.model().equals("compile") && execute() != expectedResult) {
                throw new IllegalStateException("bulk operation verification failed");
            }
        }

        void initialize()
                throws Exception
        {
            input = RebarRunner.Benchmark.read(Files.readAllBytes(Path.of(workloadFile)));
            patternBytes = input.pattern();
            if (engine.equals("trino") && input.caseInsensitive()) {
                patternBytes = utf8Slice("(?i)" + decode(patternBytes));
            }
            if (engine.equals("jdk")) {
                patternText = decode(patternBytes);
            }
            // As RE2 compiles Latin-1 when the source turns Unicode off, Rust uses byte mode then,
            // and for a haystack that is not UTF-8, matching the crate comparator's bytes API.
            rustBytes = engine.equals("rust") && (!input.unicode() || !isValidUtf8(input.haystack()));
            System.err.println("phase=compile");
            pattern = compilePattern();
            if (input.model().equals("compile")) {
                sources = new Source[0];
                return;
            }
            operation = switch (input.model()) {
                case "grep" -> Operation.FIND;
                case "count" -> Operation.COUNT;
                case "count-spans" -> Operation.SPANS;
                case "count-captures", "grep-captures" -> Operation.CAPTURES;
                default -> throw new IllegalArgumentException("unknown model: " + input.model());
            };
            boolean captures = operation == Operation.CAPTURES;
            sliceMatcher = operation == Operation.FIND ? null : switch (pattern) {
                case Re2 re2 -> captures ? re2.matcher(EMPTY_SLICE) : re2.matcher(EMPTY_SLICE, 0);
                case JavaRegexp java -> captures ? java.matcher(EMPTY_SLICE) : java.matcher(EMPTY_SLICE, 0);
                case RustRegexp rust -> captures ? rust.matcher(EMPTY_SLICE) : rust.matcher(EMPTY_SLICE, 0);
                default -> null;
            };
            if (pattern instanceof Pattern jdk) {
                jdkMatcher = jdk.matcher("");
            }
            List<Slice> bytes = input.model().startsWith("grep") ? lines(input.haystack()) : List.of(input.haystack());
            List<Source> sourceList = new ArrayList<>();
            for (Slice source : bytes) {
                sourceList.add(new Source(source, engine.equals("jdk") ? decode(source) : null));
            }
            // An array, so the timed per-source loop steps no iterator.
            sources = sourceList.toArray(Source[]::new);
        }

        Object compilePattern()
        {
            int flags = input.caseInsensitive() ? Pattern.CASE_INSENSITIVE : 0;
            if (input.unicode()) {
                flags |= Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
            }
            return switch (engine) {
                case "re2" -> input.compile();
                case "java" -> JavaRegexp.compile(patternBytes, JavaRegexp.Options.defaults()
                        .setCaseInsensitive(input.caseInsensitive())
                        .setUnicodeCase(input.unicode())
                        .setUnicodeCharacterClasses(input.unicode())
                        .setByteFrequencies(input.byteFrequencies()));
                case "trino" -> TrinoRegexp.compile(patternBytes, TrinoRegexp.Options.defaults().setByteFrequencies(input.byteFrequencies()));
                case "rust" -> RustRegexp.compile(patternBytes, RustRegexp.Options.defaults()
                        .setCaseInsensitive(input.caseInsensitive())
                        .setUnicode(input.unicode())
                        .setBytes(rustBytes)
                        .setByteFrequencies(input.byteFrequencies()));
                case "jdk" -> Pattern.compile(patternText, flags);
                case "joni" -> new Regex(
                        patternBytes.byteArray(),
                        patternBytes.byteArrayOffset(),
                        patternBytes.byteArrayOffset() + patternBytes.length(),
                        input.caseInsensitive() ? Option.IGNORECASE : Option.DEFAULT,
                        NonStrictUTF8Encoding.INSTANCE,
                        Syntax.Java);
                default -> throw new IllegalArgumentException("unknown engine: " + engine);
            };
        }

        // Chooses the loop once per invocation, so no model check runs per source or per
        // match, where it would be timed as matching.
        long execute()
        {
            if (operation == null) {
                throw new IllegalArgumentException("execute requires a matching workload, not compile");
            }
            return switch (pattern) {
                case Re2 re2 -> switch (operation) {
                    case FIND -> {
                        long result = 0;
                        for (Source source : sources) {
                            result += re2.find(source.bytes) ? 1 : 0;
                        }
                        yield result;
                    }
                    case COUNT -> {
                        long result = 0;
                        for (Source source : sources) {
                            result += re2.count(source.bytes);
                        }
                        yield result;
                    }
                    case SPANS -> matcherSpans(sliceMatcher, sources);
                    case CAPTURES -> matcherCaptures(sliceMatcher, sources);
                };
                case JavaRegexp java -> switch (operation) {
                    case FIND -> {
                        long result = 0;
                        for (Source source : sources) {
                            result += java.find(source.bytes) ? 1 : 0;
                        }
                        yield result;
                    }
                    case COUNT -> {
                        long result = 0;
                        for (Source source : sources) {
                            result += java.count(source.bytes);
                        }
                        yield result;
                    }
                    case SPANS -> matcherSpans(sliceMatcher, sources);
                    case CAPTURES -> matcherCaptures(sliceMatcher, sources);
                };
                case RustRegexp rust -> switch (operation) {
                    case FIND -> {
                        long result = 0;
                        for (Source source : sources) {
                            result += rust.find(source.bytes) ? 1 : 0;
                        }
                        yield result;
                    }
                    case COUNT -> {
                        long result = 0;
                        for (Source source : sources) {
                            result += rust.count(source.bytes);
                        }
                        yield result;
                    }
                    case SPANS -> matcherSpans(sliceMatcher, sources);
                    case CAPTURES -> matcherCaptures(sliceMatcher, sources);
                };
                case TrinoRegexp trino -> switch (operation) {
                    case FIND -> {
                        long result = 0;
                        for (Source source : sources) {
                            result += trino.contains(source.bytes) ? 1 : 0;
                        }
                        yield result;
                    }
                    case COUNT -> {
                        long result = 0;
                        for (Source source : sources) {
                            result += trino.count(source.bytes);
                        }
                        yield result;
                    }
                    case SPANS -> trinoSpans(trino, sources);
                    case CAPTURES -> trinoCaptures(trino, sources);
                };
                case Pattern _ -> switch (operation) {
                    case FIND -> jdkFind(jdkMatcher, sources);
                    case COUNT -> jdkCount(jdkMatcher, sources);
                    case SPANS -> jdkSpans(jdkMatcher, sources);
                    case CAPTURES -> jdkCaptures(jdkMatcher, sources);
                };
                case Regex joni -> switch (operation) {
                    case FIND -> joniFind(joni, sources);
                    case COUNT -> joniCount(joni, sources);
                    case SPANS -> joniSpans(joni, sources);
                    case CAPTURES -> joniCaptures(joni, sources);
                };
                default -> throw new IllegalStateException("unhandled compiled pattern: " + pattern.getClass().getName());
            };
        }

        void trace(BufferedWriter writer)
                throws IOException
        {
            if (input.model().equals("compile")) {
                writer.write("compiled\n");
                return;
            }
            boolean captures = input.model().endsWith("captures");
            for (Source source : sources) {
                writer.write("source\n");
                if (input.model().equals("grep") && pattern instanceof Re2 re2) {
                    writer.write(re2.find(source.bytes) ? "1\n" : "0\n");
                }
                else if (input.model().equals("grep") && pattern instanceof JavaRegexp java) {
                    writer.write(java.find(source.bytes) ? "1\n" : "0\n");
                }
                else if (input.model().equals("grep") && pattern instanceof RustRegexp rust) {
                    writer.write(rust.find(source.bytes) ? "1\n" : "0\n");
                }
                else if (pattern instanceof TrinoRegexp trino) {
                    if (input.model().equals("grep")) {
                        writer.write(trino.contains(source.bytes) ? "1\n" : "0\n");
                        continue;
                    }
                    TrinoRegexpMatcher matcher = trino.matcher(source.bytes, captures ? trino.capturingGroupCount() : 0);
                    while (matcher.find()) {
                        for (int group = 0; group <= matcher.groupCount(); group++) {
                            writeGroup(writer, source.bytes, matcher.start(group), matcher.end(group));
                        }
                        writer.newLine();
                    }
                }
                else if (sliceMatcher != null) {
                    sliceMatcher.reset(source.bytes);
                    while (sliceMatcher.find()) {
                        for (int group = 0; group <= (captures ? sliceMatcher.groupCount() : 0); group++) {
                            writeGroup(writer, source.bytes, sliceMatcher.start(group), sliceMatcher.end(group));
                        }
                        writer.newLine();
                    }
                }
                else if (jdkMatcher != null) {
                    jdkMatcher.reset(source.text);
                    if (input.model().equals("grep")) {
                        writer.write(jdkMatcher.find() ? "1\n" : "0\n");
                        continue;
                    }
                    while (jdkMatcher.find()) {
                        for (int group = 0; group <= (captures ? jdkMatcher.groupCount() : 0); group++) {
                            int start = jdkMatcher.start(group);
                            writeGroup(
                                    writer,
                                    source.bytes,
                                    start < 0 ? -1 : source.byteOffset(start),
                                    start < 0 ? -1 : source.byteOffset(jdkMatcher.end(group)));
                        }
                        writer.newLine();
                    }
                }
                else {
                    Regex joni = (Regex) pattern;
                    int base = source.bytes.byteArrayOffset();
                    io.airlift.joni.Matcher matcher = joni.matcher(source.bytes.byteArray(), base, base + source.bytes.length());
                    int next = 0;
                    boolean found = false;
                    while (next <= source.bytes.length() && matcher.search(base + next, base + source.bytes.length(), Option.DEFAULT) >= 0) {
                        found = true;
                        if (input.model().equals("grep")) {
                            break;
                        }
                        Region region = matcher.getEagerRegion();
                        for (int group = 0; group <= (captures ? joni.numberOfCaptures() : 0); group++) {
                            int start = region.beg[group];
                            writeGroup(writer, source.bytes, start, region.end[group]);
                        }
                        writer.newLine();
                        next = nextStart(source.bytes, matcher.getBegin(), matcher.getEnd());
                    }
                    if (input.model().equals("grep")) {
                        writer.write(found ? "1\n" : "0\n");
                    }
                }
            }
        }
    }

    /// What one workload model does with each source.
    private enum Operation
    {
        FIND,
        COUNT,
        SPANS,
        CAPTURES,
    }

    private static final class Source
    {
        private final Slice bytes;
        private final String text;
        private final int[] byteOffsets;

        private Source(Slice bytes, String text)
        {
            this.bytes = bytes;
            this.text = text;
            byteOffsets = text == null ? null : new int[text.length() + 1];
            if (text != null) {
                int offset = 0;
                for (int index = 0; index < text.length(); index++) {
                    byteOffsets[index] = offset;
                    int codePoint = text.codePointAt(index);
                    if (Character.isSupplementaryCodePoint(codePoint)) {
                        byteOffsets[++index] = -1;
                    }
                    offset += codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
                }
                byteOffsets[text.length()] = offset;
            }
        }

        private int byteOffset(int index)
        {
            if (byteOffsets[index] < 0) {
                throw new IllegalStateException("JDK boundary splits a supplementary code point");
            }
            return byteOffsets[index];
        }
    }

    @Benchmark
    public Object compile(BenchmarkData data)
    {
        return data.compilePattern();
    }

    @Benchmark
    public long execute(BenchmarkData data)
    {
        return data.execute();
    }

    /// Diagnostic for the RE2/Java/Rust count-by-iteration path. Unlike count(),
    /// this locates every match boundary even though only the count is consumed.
    @Benchmark
    public long iterateMatches(BenchmarkData data)
    {
        if (!data.input.model().equals("count") || data.sliceMatcher == null) {
            throw new IllegalArgumentException("iteration diagnostic requires RE2/Java/Rust count workload");
        }
        long count = 0;
        for (Source source : data.sources) {
            data.sliceMatcher.reset(source.bytes);
            while (data.sliceMatcher.find()) {
                count++;
            }
        }
        return count;
    }

    static List<Slice> lines(Slice input)
    {
        List<Slice> lines = new ArrayList<>();
        for (int start = 0; start < input.length(); ) {
            int end = start;
            while (end < input.length() && input.getByte(end) != '\n') {
                end++;
            }
            int length = end - start;
            if (length > 0 && input.getByte(end - 1) == '\r') {
                length--;
            }
            lines.add(input.slice(start, length));
            start = end + 1;
        }
        return lines;
    }

    private static long matcherSpans(RegexpMatcher matcher, Source[] sources)
    {
        long result = 0;
        for (Source source : sources) {
            matcher.reset(source.bytes);
            while (matcher.find()) {
                result += matcher.end() - matcher.start();
            }
        }
        return result;
    }

    private static long matcherCaptures(RegexpMatcher matcher, Source[] sources)
    {
        long result = 0;
        for (Source source : sources) {
            matcher.reset(source.bytes);
            while (matcher.find()) {
                for (int group = 0; group <= matcher.groupCount(); group++) {
                    result += matcher.matched(group) ? 1 : 0;
                }
            }
        }
        return result;
    }

    // Trino and Joni construct one matcher per source inside timing and reuse it for every
    // match in that source. Trino retains explicit groups only for capture workloads.
    private static long trinoSpans(TrinoRegexp pattern, Source[] sources)
    {
        long result = 0;
        for (Source source : sources) {
            TrinoRegexpMatcher matcher = pattern.matcher(source.bytes, 0);
            while (matcher.find()) {
                result += matcher.end() - matcher.start();
            }
        }
        return result;
    }

    private static long trinoCaptures(TrinoRegexp pattern, Source[] sources)
    {
        long result = 0;
        for (Source source : sources) {
            TrinoRegexpMatcher matcher = pattern.matcher(source.bytes, pattern.capturingGroupCount());
            while (matcher.find()) {
                for (int group = 0; group <= matcher.groupCount(); group++) {
                    result += matcher.matched(group) ? 1 : 0;
                }
            }
        }
        return result;
    }

    private static long jdkFind(java.util.regex.Matcher matcher, Source[] sources)
    {
        long result = 0;
        for (Source source : sources) {
            result += matcher.reset(source.text).find() ? 1 : 0;
        }
        return result;
    }

    private static long jdkCount(java.util.regex.Matcher matcher, Source[] sources)
    {
        long result = 0;
        for (Source source : sources) {
            matcher.reset(source.text);
            while (matcher.find()) {
                result++;
            }
        }
        return result;
    }

    private static long jdkSpans(java.util.regex.Matcher matcher, Source[] sources)
    {
        long result = 0;
        for (Source source : sources) {
            matcher.reset(source.text);
            while (matcher.find()) {
                result += source.byteOffset(matcher.end()) - source.byteOffset(matcher.start());
            }
        }
        return result;
    }

    private static long jdkCaptures(java.util.regex.Matcher matcher, Source[] sources)
    {
        long result = 0;
        for (Source source : sources) {
            matcher.reset(source.text);
            while (matcher.find()) {
                for (int group = 0; group <= matcher.groupCount(); group++) {
                    result += matcher.start(group) >= 0 ? 1 : 0;
                }
            }
        }
        return result;
    }

    private static long joniFind(Regex pattern, Source[] sources)
    {
        long result = 0;
        for (Source source : sources) {
            Slice bytes = source.bytes;
            int base = bytes.byteArrayOffset();
            io.airlift.joni.Matcher matcher = pattern.matcher(bytes.byteArray(), base, base + bytes.length());
            result += matcher.search(base, base + bytes.length(), Option.DEFAULT) >= 0 ? 1 : 0;
        }
        return result;
    }

    private static long joniCount(Regex pattern, Source[] sources)
    {
        long result = 0;
        for (Source source : sources) {
            Slice bytes = source.bytes;
            int base = bytes.byteArrayOffset();
            io.airlift.joni.Matcher matcher = pattern.matcher(bytes.byteArray(), base, base + bytes.length());
            int next = 0;
            while (next <= bytes.length() && matcher.search(base + next, base + bytes.length(), Option.DEFAULT) >= 0) {
                result++;
                next = nextStart(bytes, matcher.getBegin(), matcher.getEnd());
            }
        }
        return result;
    }

    private static long joniSpans(Regex pattern, Source[] sources)
    {
        long result = 0;
        for (Source source : sources) {
            Slice bytes = source.bytes;
            int base = bytes.byteArrayOffset();
            io.airlift.joni.Matcher matcher = pattern.matcher(bytes.byteArray(), base, base + bytes.length());
            int next = 0;
            while (next <= bytes.length() && matcher.search(base + next, base + bytes.length(), Option.DEFAULT) >= 0) {
                result += matcher.getEnd() - matcher.getBegin();
                next = nextStart(bytes, matcher.getBegin(), matcher.getEnd());
            }
        }
        return result;
    }

    private static long joniCaptures(Regex pattern, Source[] sources)
    {
        long result = 0;
        for (Source source : sources) {
            Slice bytes = source.bytes;
            int base = bytes.byteArrayOffset();
            io.airlift.joni.Matcher matcher = pattern.matcher(bytes.byteArray(), base, base + bytes.length());
            int next = 0;
            while (next <= bytes.length() && matcher.search(base + next, base + bytes.length(), Option.DEFAULT) >= 0) {
                Region region = matcher.getEagerRegion();
                for (int group = 0; group < region.numRegs; group++) {
                    result += region.beg[group] >= 0 ? 1 : 0;
                }
                next = nextStart(bytes, matcher.getBegin(), matcher.getEnd());
            }
        }
        return result;
    }

    private static int nextStart(Slice source, int begin, int end)
    {
        return begin != end ? end : end + (end == source.length() ? 1 : lengthOfCodePoint(source, end));
    }

    private static String decode(Slice bytes)
            throws CharacterCodingException
    {
        return UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.getBytes())).toString();
    }

    private static boolean isValidUtf8(Slice bytes)
    {
        try {
            decode(bytes);
            return true;
        }
        catch (CharacterCodingException e) {
            return false;
        }
    }

    private static void writeGroup(BufferedWriter writer, Slice source, int start, int end)
            throws IOException
    {
        writer.write(start < 0 ? "-;" : start + ":" + end + ":" + HexFormat.of().formatHex(source.getBytes(start, end - start)) + ";");
    }

    public static void main(String[] args)
            throws Exception
    {
        if (args.length != 3 || !(args[0].equals("--trace") || args[0].equals("--execute"))) {
            throw new IllegalArgumentException("usage: --trace|--execute <engine> <workload-file>");
        }
        System.err.println("native-access=" + Dfa.nativeAccessEnabled());
        BenchmarkData data = new BenchmarkData();
        data.engine = args[1];
        data.workloadFile = args[2];
        data.initialize();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, US_ASCII));
        if (data.input.model().equals("compile")) {
            writer.write("compiled\n");
        }
        else {
            System.err.println("phase=execution");
            if (args[0].equals("--trace")) {
                data.trace(writer);
            }
            else {
                writer.write(data.execute() + "\n");
            }
        }
        writer.flush();
    }
}
