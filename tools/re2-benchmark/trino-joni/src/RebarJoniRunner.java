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
import io.airlift.joni.Matcher;
import io.airlift.joni.Option;
import io.airlift.joni.Regex;
import io.airlift.joni.Region;
import io.airlift.joni.Syntax;
import io.airlift.slice.Slice;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.airlift.slice.SliceUtf8.lengthOfCodePointFromStartByte;
import static java.nio.charset.StandardCharsets.US_ASCII;

public final class RebarJoniRunner
{
    private RebarJoniRunner() {}

    public static void main(String[] arguments)
            throws Exception
    {
        if (arguments.length == 1 && arguments[0].equals("--version")) {
            System.out.printf("Trino Joni 2.1.5.3 (%s %s)%n", System.getProperty("java.vm.name"), System.getProperty("java.vm.version"));
            return;
        }
        if (arguments.length == 1 && arguments[0].equals("--manifest")) {
            writeManifest(RebarRunner.Benchmark.read(System.in.readAllBytes()));
            return;
        }
        if (arguments.length == 1 && arguments[0].equals("--profile")) {
            profile(RebarRunner.Benchmark.read(System.in.readAllBytes()));
            return;
        }
        if (arguments.length != 0) {
            throw new IllegalArgumentException("usage: RebarJoniRunner [--manifest|--profile|--version]");
        }

        RebarRunner.Benchmark benchmark = RebarRunner.Benchmark.read(System.in.readAllBytes());
        List<Sample> samples = switch (benchmark.model()) {
            case "compile" -> benchmarkCompile(benchmark);
            case "count" -> benchmarkOperation(benchmark, compile(benchmark), Measurement.COUNT);
            case "count-spans" -> benchmarkOperation(benchmark, compile(benchmark), Measurement.SPAN_LENGTH);
            case "count-captures" -> benchmarkOperation(benchmark, compile(benchmark), Measurement.CAPTURES);
            case "grep" -> benchmarkGrep(benchmark, false);
            case "grep-captures" -> benchmarkGrep(benchmark, true);
            default -> throw new IllegalArgumentException("unsupported Rebar model: " + benchmark.model());
        };

        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, US_ASCII));
        for (Sample sample : samples) {
            writer.write(Long.toString(sample.durationNanos()));
            writer.write(',');
            writer.write(Long.toString(sample.result()));
            writer.newLine();
        }
        writer.flush();
    }

    private static void writeManifest(RebarRunner.Benchmark benchmark)
            throws Exception
    {
        Regex regex = compile(benchmark);
        FindAllTrace trace = traceFindAll(regex, benchmark.haystack());
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.US_ASCII));
        writer.write("name=" + benchmark.name());
        writer.newLine();
        writer.write("model=" + benchmark.model());
        writer.newLine();
        writer.write("groups=" + (regex.numberOfCaptures() + 1));
        writer.newLine();
        writer.write("matches=" + trace.matches());
        writer.newLine();
        writer.write("match_bytes=" + trace.matchBytes());
        writer.newLine();
        writer.write("winning_group_sum=" + trace.winningGroupSum());
        writer.newLine();
        writer.write("public_result=" + trace.result());
        writer.newLine();
        writer.write("winning_group_counts=" + Arrays.toString(trace.winningGroupCounts()));
        writer.newLine();
        writer.flush();
    }

    private static void profile(RebarRunner.Benchmark benchmark)
            throws Exception
    {
        Regex regex = compile(benchmark);
        Measurement measurement = measurement(benchmark.model());
        long expected = findAll(regex, benchmark.haystack(), measurement);
        long warmupStart = System.nanoTime();
        for (long iteration = 0; iteration < benchmark.maximumWarmupIterations(); iteration++) {
            if (findAll(regex, benchmark.haystack(), measurement) != expected) {
                throw new IllegalStateException("Joni profile result changed during warmup");
            }
            if (System.nanoTime() - warmupStart >= benchmark.maximumWarmupTimeNanos()) {
                break;
            }
        }

        long checksum = 1;
        for (long iteration = 0; iteration < benchmark.maximumIterations(); iteration++) {
            long result = findAll(regex, benchmark.haystack(), measurement);
            if (result != expected) {
                throw new IllegalStateException("Joni profile result changed");
            }
            checksum = checksum * 31 + result;
        }
        System.out.printf("iterations=%s,result=%s,checksum=%s%n", benchmark.maximumIterations(), expected, checksum);
    }

    private static Measurement measurement(String model)
    {
        return switch (model) {
            case "count" -> Measurement.COUNT;
            case "count-spans" -> Measurement.SPAN_LENGTH;
            case "count-captures" -> Measurement.CAPTURES;
            default -> throw new IllegalArgumentException("unsupported Joni profile model: " + model);
        };
    }

    private static List<Sample> benchmarkCompile(RebarRunner.Benchmark benchmark)
    {
        long warmupStart = System.nanoTime();
        for (long iteration = 0; iteration < benchmark.maximumWarmupIterations(); iteration++) {
            Regex regex = compile(benchmark);
            findAll(regex, benchmark.haystack(), Measurement.COUNT);
            if (System.nanoTime() - warmupStart >= benchmark.maximumWarmupTimeNanos()) {
                break;
            }
        }

        List<Sample> samples = new ArrayList<>();
        long runStart = System.nanoTime();
        for (long iteration = 0; iteration < benchmark.maximumIterations(); iteration++) {
            long start = System.nanoTime();
            Regex regex = compile(benchmark);
            long durationNanos = System.nanoTime() - start;
            long result = findAll(regex, benchmark.haystack(), Measurement.COUNT);
            samples.add(new Sample(durationNanos, result));
            if (System.nanoTime() - runStart >= benchmark.maximumTimeNanos()) {
                break;
            }
        }
        return samples;
    }

    private static List<Sample> benchmarkOperation(RebarRunner.Benchmark benchmark, Regex regex, Measurement measurement)
    {
        return measure(benchmark, () -> findAll(regex, benchmark.haystack(), measurement));
    }

    private static List<Sample> benchmarkGrep(RebarRunner.Benchmark benchmark, boolean captures)
    {
        Regex regex = compile(benchmark);
        return measure(benchmark, () -> {
            long result = 0;
            Slice haystack = benchmark.haystack();
            int lineStart = 0;
            while (lineStart < haystack.length()) {
                int lineEnd = lineStart;
                while (lineEnd < haystack.length() && haystack.getByte(lineEnd) != '\n') {
                    lineEnd++;
                }
                int contentEnd = lineEnd;
                if (contentEnd > lineStart && haystack.getByte(contentEnd - 1) == '\r') {
                    contentEnd--;
                }
                Slice line = haystack.slice(lineStart, contentEnd - lineStart);
                if (captures) {
                    result += findAll(regex, line, Measurement.CAPTURES);
                }
                else if (search(regex, line, 0) != null) {
                    result++;
                }
                lineStart = lineEnd + 1;
            }
            return result;
        });
    }

    private static List<Sample> measure(RebarRunner.Benchmark benchmark, Operation operation)
    {
        long warmupStart = System.nanoTime();
        for (long iteration = 0; iteration < benchmark.maximumWarmupIterations(); iteration++) {
            operation.run();
            if (System.nanoTime() - warmupStart >= benchmark.maximumWarmupTimeNanos()) {
                break;
            }
        }

        List<Sample> samples = new ArrayList<>();
        long runStart = System.nanoTime();
        for (long iteration = 0; iteration < benchmark.maximumIterations(); iteration++) {
            long start = System.nanoTime();
            long result = operation.run();
            samples.add(new Sample(System.nanoTime() - start, result));
            if (System.nanoTime() - runStart >= benchmark.maximumTimeNanos()) {
                break;
            }
        }
        return samples;
    }

    private static Regex compile(RebarRunner.Benchmark benchmark)
    {
        Slice pattern = benchmark.pattern();
        int options = benchmark.caseInsensitive() ? Option.DEFAULT | Option.IGNORECASE : Option.DEFAULT;
        return new Regex(
                pattern.byteArray(),
                pattern.byteArrayOffset(),
                pattern.byteArrayOffset() + pattern.length(),
                options,
                NonStrictUTF8Encoding.INSTANCE,
                Syntax.Java);
    }

    private static long findAll(Regex regex, Slice source, Measurement measurement)
    {
        Matcher matcher = matcher(regex, source);
        long result = 0;
        int nextStart = 0;
        while (search(matcher, source, nextStart)) {
            result += switch (measurement) {
                case COUNT -> 1;
                case SPAN_LENGTH -> matcher.getEnd() - matcher.getBegin();
                case CAPTURES -> participatingGroups(matcher.getEagerRegion());
            };
            nextStart = nextStart(source, matcher);
        }
        return result;
    }

    private static FindAllTrace traceFindAll(Regex regex, Slice source)
    {
        Matcher matcher = matcher(regex, source);
        long result = 0;
        long matches = 0;
        long matchBytes = 0;
        long winningGroupSum = 0;
        long[] winningGroupCounts = new long[regex.numberOfCaptures() + 1];
        int nextStart = 0;
        while (search(matcher, source, nextStart)) {
            Region region = matcher.getEagerRegion();
            int winningGroup = 0;
            for (int group = 0; group < region.numRegs; group++) {
                if (region.beg[group] >= 0) {
                    result++;
                    if (group > 0) {
                        winningGroup = group;
                    }
                }
            }
            matches++;
            matchBytes += matcher.getEnd() - matcher.getBegin();
            winningGroupSum += winningGroup;
            winningGroupCounts[winningGroup]++;
            nextStart = nextStart(source, matcher);
        }
        return new FindAllTrace(result, matches, matchBytes, winningGroupSum, winningGroupCounts);
    }

    private static Matcher search(Regex regex, Slice source, int at)
    {
        Matcher matcher = matcher(regex, source);
        return search(matcher, source, at) ? matcher : null;
    }

    private static boolean search(Matcher matcher, Slice source, int at)
    {
        int base = source.byteArrayOffset();
        try {
            return matcher.searchInterruptible(base + at, base + source.length(), Option.DEFAULT) >= 0;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Joni search interrupted", e);
        }
    }

    private static Matcher matcher(Regex regex, Slice source)
    {
        int base = source.byteArrayOffset();
        return regex.matcher(source.byteArray(), base, base + source.length());
    }

    private static int nextStart(Slice source, Matcher matcher)
    {
        if (matcher.getEnd() != matcher.getBegin()) {
            return matcher.getEnd();
        }
        if (matcher.getBegin() >= source.length()) {
            return matcher.getEnd() + 1;
        }
        return matcher.getEnd() + lengthOfCodePointFromStartByte(source.getByte(matcher.getBegin()));
    }

    private static int participatingGroups(Region region)
    {
        int count = 0;
        for (int group = 0; group < region.numRegs; group++) {
            if (region.beg[group] >= 0) {
                count++;
            }
        }
        return count;
    }

    private enum Measurement
    {
        COUNT,
        SPAN_LENGTH,
        CAPTURES
    }

    @FunctionalInterface
    private interface Operation
    {
        long run();
    }

    private record Sample(long durationNanos, long result) {}

    private record FindAllTrace(
            long result,
            long matches,
            long matchBytes,
            long winningGroupSum,
            long[] winningGroupCounts) {}
}
