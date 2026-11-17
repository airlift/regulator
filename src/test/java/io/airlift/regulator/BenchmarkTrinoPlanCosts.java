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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static java.nio.charset.StandardCharsets.UTF_8;

/// Times Trino scan plans whose delimited captures, line and dot-all tails, and negated class runs
/// read text with and without characters outside ASCII, using only the public API.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkTrinoPlanCosts
{
    // URL host: an anchored delimited capture.
    private static final String DELIMITED = "^https?://([^/]+)/";
    // First path segment, then the rest of the line.
    private static final String LINE_TAIL = "^([^/]+)/.*$";
    // First path segment, then the rest of the text, line terminators included.
    private static final String DOT_ALL_TAIL = "(?s)^([^/]+)/.*$";
    // A key's value: a negated class run between literals.
    private static final String NEGATED_RUN = "key=([^;]+);";

    // Filler holding none of /, ;, =, or a line terminator.
    private static final String[] WORDS = {
            "the", "of", "and", "to", "in", "is", "was", "he", "that", "it", "his", "her", "with", "as",
            "had", "for", "at", "by", "on", "not", "be", "but", "from", "this", "which", "have", "you",
            "they", "one", "were", "all", "we", "when", "there", "been", "an", "my", "so", "me", "said",
            "what", "would", "no", "if", "up", "out", "them", "into", "could", "more", "time", "upon",
            "door", "room", "street", "house", "night", "letter", "morning", "little", "matter", "hand",
            "face", "window", "paper", "quite", "very", "should", "before", "about", "again", "nothing",
    };
    // Accented Latin, CJK, and emoji words, all valid UTF-8 of two to four bytes a character.
    private static final String[] MIXED_WORDS = {
            "café", "naïve", "über", "señor", "größe", "élan", "façade", "smörgås",
            "東京", "日本語", "中文", "北京市", "서울", "한국어",
            "😀", "🚀", "🎉", "👍",
    };
    // The share of a mixed text's bytes that are outside ASCII.
    private static final double MIXED_SHARE = 0.30;

    private static final int ROW_COUNT = 64;
    private static final int LONG_SOURCE_COUNT = 8;
    private static final int LONG_SOURCE_LENGTH = 4 * 1024;

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({
                "delimited",
                "line-tail",
                "dotall-tail",
                "negated-run",
        })
        String pattern;

        @Param({
                "short-ascii",
                "short-mixed",
                "long-ascii",
                "long-mixed",
        })
        String input;

        private TrinoRegexp regexp;
        private TrinoRegexpMatcher matcher;
        private List<Slice> sources;
        private int sourceIndex;

        @Setup
        public void setup()
        {
            String regex = switch (pattern) {
                case "delimited" -> DELIMITED;
                case "line-tail" -> LINE_TAIL;
                case "dotall-tail" -> DOT_ALL_TAIL;
                case "negated-run" -> NEGATED_RUN;
                default -> throw new IllegalArgumentException("unknown pattern: " + pattern);
            };
            List<String> captures = new ArrayList<>();
            sources = switch (input) {
                case "short-ascii" -> sources(pattern, false, false, captures);
                case "short-mixed" -> sources(pattern, false, true, captures);
                case "long-ascii" -> sources(pattern, true, false, captures);
                case "long-mixed" -> sources(pattern, true, true, captures);
                default -> throw new IllegalArgumentException("unknown input: " + input);
            };
            regexp = TrinoRegexp.compile(Slices.utf8Slice(regex));
            matcher = regexp.matcher(sources.getFirst(), 0);
            sourceIndex = 0;
            verify(regex, captures, input.endsWith("-mixed"));
        }

        // Checks that the pattern runs on a scan plan, that each source holds exactly one match
        // whose group 1 is the generated capture, that the plan answers every operation as the
        // ordinary engine does, and that the inputs hold the intended share of non-ASCII bytes.
        private void verify(String regex, List<String> captures, boolean mixed)
        {
            if (!regexp.re2().usesScanPlanForDiagnostics()) {
                throw new IllegalStateException("pattern does not run on a scan plan: " + pattern);
            }
            TrinoRegexp reference = TrinoRegexp.compileWithoutScanPlanForTesting(Slices.utf8Slice(regex));
            if (reference.re2().usesScanPlanForDiagnostics()) {
                throw new IllegalStateException("reference pattern runs on a scan plan: " + pattern);
            }
            TrinoRegexpMatcher referenceMatcher = reference.matcher(sources.getFirst(), 0);
            long bytes = 0;
            long nonAsciiBytes = 0;
            for (int index = 0; index < sources.size(); index++) {
                Slice source = sources.get(index);
                Slice capture = Slices.utf8Slice(captures.get(index));
                TrinoRegexpMatcher found = matcher.reset(source);
                TrinoRegexpMatcher referenceFound = referenceMatcher.reset(source);
                if (!regexp.contains(source) || !reference.contains(source) ||
                        regexp.count(source) != 1 || reference.count(source) != 1 ||
                        !found.find() || !referenceFound.find() ||
                        found.start() != referenceFound.start() || found.end() != referenceFound.end() ||
                        !capture.equals(regexp.extract(source, 1)) || !capture.equals(reference.extract(source, 1))) {
                    throw new IllegalStateException("benchmark outcome mismatch for " + pattern + "/" + input + " source " + index);
                }
                bytes += source.length();
                for (int position = 0; position < source.length(); position++) {
                    if (source.getByte(position) < 0) {
                        nonAsciiBytes++;
                    }
                }
            }
            double share = (double) nonAsciiBytes / bytes;
            if (mixed ? Math.abs(share - MIXED_SHARE) > 0.05 : nonAsciiBytes != 0) {
                throw new IllegalStateException("unexpected non-ASCII share " + share + " for " + pattern + "/" + input);
            }
        }

        private Slice nextSource()
        {
            Slice source = sources.get(sourceIndex);
            sourceIndex = (sourceIndex + 1) & (sources.size() - 1);
            return source;
        }
    }

    @Benchmark
    public boolean contains(BenchmarkData data)
    {
        return data.regexp.contains(data.nextSource());
    }

    @Benchmark
    public long count(BenchmarkData data)
    {
        return data.regexp.count(data.nextSource());
    }

    @Benchmark
    public int find(BenchmarkData data)
    {
        TrinoRegexpMatcher matcher = data.matcher.reset(data.nextSource());
        return matcher.find() ? matcher.end() : -1;
    }

    @Benchmark
    public Slice extract(BenchmarkData data)
    {
        return data.regexp.extract(data.nextSource(), 1);
    }

    // Short inputs are 64 rows of 60 to 120 bytes; long inputs are 8 sources of 4 KiB with the
    // match within the first 64 bytes and the rest after it. Each source holds exactly one match,
    // and its capture is added to captures. Mixed text, captures included, is about 30% non-ASCII.
    static List<Slice> sources(String pattern, boolean longSources, boolean mixed, List<String> captures)
    {
        Random random = new Random(273);
        List<Slice> sources = new ArrayList<>();
        int count = longSources ? LONG_SOURCE_COUNT : ROW_COUNT;
        for (int index = 0; index < count; index++) {
            int length = longSources ? LONG_SOURCE_LENGTH : 60 + random.nextInt(61);
            int captureLength = 10 + random.nextInt(21);
            StringBuilder text = new StringBuilder(length);
            String capture;
            switch (pattern) {
                case "delimited" -> {
                    text.append("https://");
                    capture = text(captureLength, mixed, '.', false, random);
                    text.append(capture).append('/');
                    text.append(text(length - utf8Length(text), mixed, '/', false, random));
                }
                case "line-tail", "dotall-tail" -> {
                    capture = text(captureLength, mixed, '-', false, random);
                    text.append(capture).append('/');
                    text.append(text(length - utf8Length(text), mixed, ' ', pattern.equals("dotall-tail"), random));
                }
                case "negated-run" -> {
                    int prefixLength = longSources ? 32 : random.nextInt(length - captureLength - 7 + 1);
                    if (prefixLength > 0) {
                        text.append(text(prefixLength - 1, mixed, ' ', false, random)).append(' ');
                    }
                    capture = text(captureLength, mixed, ',', false, random);
                    text.append("key=").append(capture).append(';');
                    text.append(text(length - utf8Length(text), mixed, ' ', false, random));
                }
                default -> throw new IllegalArgumentException("unknown pattern: " + pattern);
            }
            if (utf8Length(text) != length) {
                throw new IllegalStateException("source is %s bytes, not %s".formatted(utf8Length(text), length));
            }
            captures.add(capture);
            sources.add(Slices.utf8Slice(text.toString()));
        }
        return sources;
    }

    // Returns exactly `length` bytes of words joined by the separator, or by a line feed every
    // eighth word when `lineFeeds` is set, padded with x; mixed text takes a word outside ASCII
    // whenever its non-ASCII share is below 30%.
    private static String text(int length, boolean mixed, char separator, boolean lineFeeds, Random random)
    {
        StringBuilder text = new StringBuilder(length);
        int bytes = 0;
        int nonAsciiBytes = 0;
        int words = 0;
        while (true) {
            boolean nonAscii = mixed && nonAsciiBytes < MIXED_SHARE * Math.max(bytes, 1);
            String word = nonAscii ? MIXED_WORDS[random.nextInt(MIXED_WORDS.length)] : WORDS[random.nextInt(WORDS.length)];
            int wordBytes = utf8Length(word);
            int separatorBytes = words == 0 ? 0 : 1;
            if (bytes + separatorBytes + wordBytes > length) {
                break;
            }
            if (separatorBytes != 0) {
                text.append(lineFeeds && words % 8 == 0 ? '\n' : separator);
            }
            text.append(word);
            bytes += separatorBytes + wordBytes;
            nonAsciiBytes += wordBytes - (int) word.chars().filter(character -> character < 0x80).count();
            words++;
        }
        while (bytes < length) {
            text.append('x');
            bytes++;
        }
        return text.toString();
    }

    private static int utf8Length(CharSequence text)
    {
        return text.toString().getBytes(UTF_8).length;
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkTrinoPlanCosts.class, args);
        new Runner(options).run();
    }
}
