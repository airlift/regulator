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

/// Times Trino's case-insensitive literal alternations on inputs with and without a character
/// outside ASCII that the alternatives' full case folding may match, using only the public API.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkTrinoFoldedAlternation
{
    // rebar's curated/02-literal-alternate/sherlock-casei-en
    private static final String CASEI_EN = "(?i)Sherlock Holmes|John Watson|Irene Adler|Inspector Lestrade|Professor Moriarty";
    private static final String LEVELS = "(?i)error|warn|fatal";

    // Joni folds the long s (U+017F) to s, so (?i)Sherlock Holmes matches "ſherlock Holmes".
    private static final String CASEI_EN_ESCAPE = "ſ";
    // No character outside ASCII folds to one of error, warn, or fatal's letters alone; the ligature
    // ff (U+FB00) folds to "ff", so Joni's (?i)ff matches it, and it is a multi-character fold that
    // begins with fatal's f, which a conservative escape set for the alternation holds.
    private static final String LEVELS_ESCAPE = "ﬀ";

    // English-like filler holding no alternative of either pattern, even in part across a space.
    private static final String[] WORDS = {
            "the", "of", "and", "to", "in", "is", "was", "he", "that", "it", "his", "her", "with", "as",
            "had", "for", "at", "by", "on", "not", "be", "but", "from", "this", "which", "have", "you",
            "they", "one", "were", "all", "we", "when", "there", "been", "an", "my", "so", "me", "said",
            "what", "would", "no", "if", "up", "out", "them", "into", "could", "more", "time", "upon",
            "door", "room", "street", "house", "night", "letter", "morning", "little", "matter", "hand",
            "face", "window", "paper", "quite", "very", "should", "before", "about", "again", "nothing",
    };

    private static final int ROW_COUNT = 64;
    private static final int LONG_SOURCE_COUNT = 8;
    private static final int LONG_SOURCE_LENGTH = 64 * 1024;

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({
                "casei-en",
                "levels",
        })
        String pattern;

        @Param({
                "short-rows",
                "short-rows-escape",
                "long-early",
                "long-escape",
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
                case "casei-en" -> CASEI_EN;
                case "levels" -> LEVELS;
                default -> throw new IllegalArgumentException("unknown pattern: " + pattern);
            };
            String escape = pattern.equals("casei-en") ? CASEI_EN_ESCAPE : LEVELS_ESCAPE;
            List<String> alternatives = List.of(regex.substring("(?i)".length()).split("\\|"));
            sources = switch (input) {
                case "short-rows" -> shortRows(alternatives, null);
                case "short-rows-escape" -> shortRows(alternatives, escape);
                case "long-early" -> longSources(alternatives, null);
                case "long-escape" -> longSources(alternatives, escape);
                default -> throw new IllegalArgumentException("unknown input: " + input);
            };
            regexp = TrinoRegexp.compile(Slices.utf8Slice(regex));
            matcher = regexp.matcher(sources.getFirst(), 0);
            sourceIndex = 0;
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
        return matcher.find() ? matcher.start() : -1;
    }

    @Benchmark
    public Slice extract(BenchmarkData data)
    {
        return data.regexp.extract(data.nextSource());
    }

    // 64 rows of 60 to 120 bytes; every eighth row holds one alternative in mixed case, and with an
    // escape every row ends with it, so a scan for it reads the whole row.
    private static List<Slice> shortRows(List<String> alternatives, String escape)
    {
        Random random = new Random(271);
        List<Slice> rows = new ArrayList<>();
        for (int row = 0; row < ROW_COUNT; row++) {
            int target = 68 + random.nextInt(49);
            String alternative = row % 8 == 0 ? mixedCase(alternatives.get((row / 8) % alternatives.size()), random) : null;
            List<String> words = new ArrayList<>();
            int length = -1;
            int fill = alternative == null ? target : target - alternative.length() - 1;
            while (true) {
                String word = WORDS[random.nextInt(WORDS.length)];
                if (length + 1 + word.length() > fill) {
                    break;
                }
                words.add(word);
                length += 1 + word.length();
            }
            if (alternative != null) {
                words.add(random.nextInt(words.size() + 1), alternative);
            }
            if (escape != null) {
                words.add(escape);
            }
            rows.add(Slices.utf8Slice(String.join(" ", words)));
        }
        return rows;
    }

    // 8 sources of 64 KiB, each with one alternative in mixed case at about byte 32 and no other;
    // with an escape, each also holds it 64 bytes before its end.
    private static List<Slice> longSources(List<String> alternatives, String escape)
    {
        Random random = new Random(271);
        List<Slice> sources = new ArrayList<>();
        for (int index = 0; index < LONG_SOURCE_COUNT; index++) {
            StringBuilder text = new StringBuilder(LONG_SOURCE_LENGTH);
            appendWords(text, 31, random);
            text.append(' ').append(mixedCase(alternatives.get(index % alternatives.size()), random));
            if (escape != null) {
                appendWords(text, LONG_SOURCE_LENGTH - 64, random);
                text.append(' ').append(escape);
            }
            appendWords(text, LONG_SOURCE_LENGTH, random);
            sources.add(Slices.utf8Slice(text.toString()));
        }
        return sources;
    }

    // Appends space-separated words while the UTF-8 length stays within the limit, then pads with
    // spaces to it; the text so far is ASCII except for at most one escape.
    private static void appendWords(StringBuilder text, int limit, Random random)
    {
        int length = Slices.utf8Slice(text.toString()).length();
        while (true) {
            String word = WORDS[random.nextInt(WORDS.length)];
            if (length + 1 + word.length() > limit) {
                break;
            }
            if (!text.isEmpty()) {
                text.append(' ');
                length++;
            }
            text.append(word);
            length += word.length();
        }
        while (length < limit) {
            text.append(' ');
            length++;
        }
    }

    private static String mixedCase(String alternative, Random random)
    {
        StringBuilder mixed = new StringBuilder(alternative.length());
        for (int index = 0; index < alternative.length(); index++) {
            char character = alternative.charAt(index);
            mixed.append(random.nextBoolean() ? Character.toUpperCase(character) : Character.toLowerCase(character));
        }
        return mixed.toString();
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkTrinoFoldedAlternation.class, args);
        new Runner(options).run();
    }
}
