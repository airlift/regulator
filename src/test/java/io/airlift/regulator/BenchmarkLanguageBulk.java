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

/**
 * Shared bulk operations over pinned Rebar inputs, through each language's public API.
 */
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
        @Param({"re2", "java", "trino", "jdk", "joni"})
        public String engine;

        @Param("required-workload-file")
        public String workloadFile;

        @Param("0")
        public long expectedResult;

        private RebarRunner.Benchmark input;
        private Slice patternBytes;
        private String patternText;
        private Object pattern;
        private Re2Matcher sliceMatcher;
        private java.util.regex.Matcher jdkMatcher;
        private List<Source> sources;

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
            System.err.println("phase=compile");
            pattern = compilePattern();
            if (input.model().equals("compile")) {
                sources = List.of();
                return;
            }
            boolean captures = input.model().endsWith("captures");
            sliceMatcher = input.model().equals("grep") ? null : switch (pattern) {
                case Re2 re2 -> captures ? re2.matcher(EMPTY_SLICE) : re2.matcher(EMPTY_SLICE, 0);
                case JavaRegexp java -> captures ? java.matcher(EMPTY_SLICE) : java.matcher(EMPTY_SLICE, 0);
                default -> null;
            };
            if (pattern instanceof Pattern jdk) {
                jdkMatcher = jdk.matcher("");
            }
            List<Slice> bytes = input.model().startsWith("grep") ? lines(input.haystack()) : List.of(input.haystack());
            sources = new ArrayList<>();
            for (Slice source : bytes) {
                sources.add(new Source(source, engine.equals("jdk") ? decode(source) : null));
            }
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
                        .setUnicodeCharacterClasses(input.unicode()));
                case "trino" -> TrinoRegexp.compile(patternBytes);
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

        long execute()
        {
            long result = 0;
            for (Source source : sources) {
                if (input.model().equals("grep") && pattern instanceof Re2 re2) {
                    result += re2.find(source.bytes) ? 1 : 0;
                }
                else if (input.model().equals("grep") && pattern instanceof JavaRegexp java) {
                    result += java.find(source.bytes) ? 1 : 0;
                }
                else if (input.model().equals("count") && pattern instanceof Re2 re2) {
                    result += re2.count(source.bytes);
                }
                else if (input.model().equals("count") && pattern instanceof JavaRegexp java) {
                    result += java.count(source.bytes);
                }
                else if (pattern instanceof TrinoRegexp trino) {
                    result += switch (input.model()) {
                        case "count" -> trino.count(source.bytes);
                        case "grep" -> trino.contains(source.bytes) ? 1 : 0;
                        case "count-spans", "count-captures", "grep-captures" -> trinoResult(trino, source.bytes, input.model());
                        default -> throw new IllegalArgumentException("unknown model: " + input.model());
                    };
                }
                else if (sliceMatcher != null) {
                    sliceMatcher.reset(source.bytes);
                    while (sliceMatcher.find()) {
                        if (input.model().equals("count-spans")) {
                            result += sliceMatcher.end() - sliceMatcher.start();
                        }
                        else if (input.model().endsWith("captures")) {
                            for (int group = 0; group <= sliceMatcher.groupCount(); group++) {
                                result += sliceMatcher.matched(group) ? 1 : 0;
                            }
                        }
                        else {
                            result++;
                        }
                    }
                }
                else if (jdkMatcher != null) {
                    jdkMatcher.reset(source.text);
                    if (input.model().equals("grep")) {
                        result += jdkMatcher.find() ? 1 : 0;
                        continue;
                    }
                    while (jdkMatcher.find()) {
                        if (input.model().equals("count-spans")) {
                            result += source.byteOffset(jdkMatcher.end()) - source.byteOffset(jdkMatcher.start());
                        }
                        else if (input.model().endsWith("captures")) {
                            for (int group = 0; group <= jdkMatcher.groupCount(); group++) {
                                result += jdkMatcher.start(group) >= 0 ? 1 : 0;
                            }
                        }
                        else {
                            result++;
                        }
                    }
                }
                else {
                    result += joniResult((Regex) pattern, source.bytes, input.model());
                }
            }
            return result;
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

    /**
     * Diagnostic for the original RE2/Java count-by-iteration path. Unlike count(),
     * this locates every match boundary even though only the count is consumed.
     */
    @Benchmark
    public long iterateMatches(BenchmarkData data)
    {
        if (!data.input.model().equals("count") || data.sliceMatcher == null) {
            throw new IllegalArgumentException("iteration diagnostic requires RE2/Java count workload");
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

    private static long trinoResult(TrinoRegexp pattern, Slice source, String model)
    {
        boolean captures = model.endsWith("captures");
        // Both Trino and Joni construct one matcher per source inside timing and reuse it for
        // every match in that source. Only capture workloads retain explicit groups.
        TrinoRegexpMatcher matcher = pattern.matcher(source, captures ? pattern.capturingGroupCount() : 0);
        long result = 0;
        while (matcher.find()) {
            if (captures) {
                for (int group = 0; group <= matcher.groupCount(); group++) {
                    result += matcher.matched(group) ? 1 : 0;
                }
            }
            else {
                result += matcher.end() - matcher.start();
            }
        }
        return result;
    }

    private static long joniResult(Regex pattern, Slice source, String model)
    {
        int base = source.byteArrayOffset();
        io.airlift.joni.Matcher matcher = pattern.matcher(source.byteArray(), base, base + source.length());
        long result = 0;
        int next = 0;
        while (next <= source.length() && matcher.search(base + next, base + source.length(), Option.DEFAULT) >= 0) {
            if (model.equals("grep")) {
                return 1;
            }
            if (model.equals("count-spans")) {
                result += matcher.getEnd() - matcher.getBegin();
            }
            else if (model.endsWith("captures")) {
                Region region = matcher.getEagerRegion();
                for (int group = 0; group < region.numRegs; group++) {
                    result += region.beg[group] >= 0 ? 1 : 0;
                }
            }
            else {
                result++;
            }
            next = nextStart(source, matcher.getBegin(), matcher.getEnd());
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
