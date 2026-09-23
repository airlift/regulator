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
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
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

/**
 * Measures the complete setup cost (parse, simplify, analyze, compile, and plan
 * selection) of one pass over a fixed mixed corpus for each pattern language.
 * One operation compiles every pattern in the corpus once. The LIKE corpus always
 * supplies an escape character.
 */
@SuppressWarnings("MethodMayBeStatic")
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(3)
@Warmup(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkCompileCorpus
{
    private static final String PARENS = "([ -~])*(A)(B)(C)(D)(E)(F)(G)(H)(I)(J)(K)(L)(M)(N)(O)(P)(Q)(R)(S)(T)(U)(V)(W)(X)(Y)(Z)$";

    /**
     * Patterns accepted by the RE2, Trino, and Java frontends with the same source text.
     */
    static final List<String> SHARED_REGEXP_CORPUS = List.of(
            "(.*)-(\\d+)-of-(\\d+)",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
            "[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
            PARENS,
            "[0-9]+.(.*)",
            "(?i)ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
            "([a-z]+)-([0-9]+)",
            "[a-z]{8}-[0-9]{4}",
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
            "(?:[0-9]{1,3}\\.){3}[0-9]{1,3}",
            "[0-9]{4}-[0-9]{2}-[0-9]{2}",
            "([A-Za-z_][A-Za-z0-9_]*)=([^ ]+)",
            "\"([^\"]*)\"",
            "ERROR|WARN|FATAL",
            "\\s+",
            "\\p{L}+",
            ".*coolfunctionname.*",
            "literal",
            "\\d{3}/\\d{3}/\\d{4}",
            "https?://[^\\s/$.?#][^\\s]*",
            "^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2}),(\\d{3}) \\[(\\w+)\\] (\\w+) (.*)$",
            "(?i)(select|insert|update|delete)\\s+",
            "\\b(foo|bar|baz|qux)\\b",
            "^[a-zA-Z_][a-zA-Z0-9_]*$",
            "(\\w+)@(\\w+)\\.com",
            "[\\p{Lu}\\p{Ll}]+\\d*",
            "(?s).*?<title>(.*?)</title>",
            "a{2,5}b{3}c?d*",
            "\\d{1,3}(,\\d{3})*(\\.\\d+)?",
            "(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), \\d{2} (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4}",
            "[^\\x00-\\x7F]+",
            "x$",
            "[,;]",
            ";",
            "\\bX",
            "(?i)hello world",
            "^(?:GET|POST|PUT|DELETE) /[^ ]* HTTP/1\\.[01]$",
            "[0-9]{3}-[0-9]{2}-[0-9]{4}",
            "(a|b|c|d|e|f|g|h|i|j|k|l|m|n|o|p)+",
            "[[:alpha:]][[:alnum:]]*",
            "(?:\\d+\\.){3}\\d+:\\d{1,5}",
            "\\$[0-9]+(\\.[0-9]{2})?",
            "(?i)\\.(jpe?g|png|gif|webp)$");

    static final List<String> LIKE_CORPUS = List.of(
            "%needle%",
            "prefix%",
            "%suffix",
            "exact match",
            "_",
            "%",
            "a_b_c",
            "%foo%bar%",
            "%a__b%",
            "prefix%middle%suffix",
            "___%",
            "%x",
            "%alpha%omega%",
            "%😀%",
            "http://%.example.com/%",
            "ERROR%",
            "%2024-__-__%",
            "abc",
            "%a%b%c%d%e%",
            "user\\_%",
            "%needle",
            "%éè%",
            "____-__-__",
            "%.jpg");

    /**
     * One frontend per corpus language. The measured loop calls the same method as the tests, so a
     * wrong frontend or a dropped LIKE escape is visible to the test.
     */
    public enum Frontend
    {
        RE2,
        TRINO,
        JAVA,
        LIKE;

        Object compile(Slice pattern)
        {
            return switch (this) {
                case RE2 -> Re2.compile(pattern);
                case TRINO -> TrinoRegexp.compile(pattern);
                case JAVA -> JavaRegexp.compile(pattern);
                case LIKE -> TrinoLikePattern.compile(pattern, '\\');
            };
        }

        List<String> corpus()
        {
            return switch (this) {
                case RE2, TRINO, JAVA -> SHARED_REGEXP_CORPUS;
                case LIKE -> LIKE_CORPUS;
            };
        }
    }

    @State(Scope.Thread)
    public static class CorpusState
    {
        @Param
        Frontend frontend;

        Slice[] patterns;

        @Setup(Level.Trial)
        public void setup()
        {
            patterns = frontend.corpus().stream().map(Slices::utf8Slice).toArray(Slice[]::new);
        }
    }

    @Benchmark
    public void compileCorpus(CorpusState state, Blackhole blackhole)
    {
        Frontend frontend = state.frontend;
        for (Slice pattern : state.patterns) {
            blackhole.consume(frontend.compile(pattern));
        }
    }

    public static void main(String[] args)
            throws Throwable
    {
        Options options = Re2BenchmarkRunner.buildOptions(BenchmarkCompileCorpus.class, args);
        new Runner(options).run();
    }
}
