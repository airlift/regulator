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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import java.util.concurrent.TimeUnit;

/// Measures case-insensitive compilation whose cost is dominated by case folding, for each regular
/// expression language. [#compileFoldHeavy] is the steady-state compile of patterns that fold
/// large classes or long non-ASCII literals. [#compileFirstCaseInsensitive] is the cold compile
/// of the first case-insensitive pattern in a fresh JVM, which includes building the case-folding
/// tables; each fork measures one compile. [#canonicalizeText] is the [FilteredRe2] text
/// canonicalization pass, which folds caller text with the same RE2 folding rule.
@SuppressWarnings("MethodMayBeStatic")
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(3)
@Warmup(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkCaseInsensitiveCompile
{
    static final String FIRST_USE_PATTERN = "hello κόσμε";
    static final int CANONICAL_TEXT_LENGTH = 4096;

    public enum Frontend
    {
        RE2,
        JAVA,
        TRINO;

        Object compile(Slice pattern)
        {
            return switch (this) {
                case RE2 -> Re2.compile(pattern);
                case JAVA -> JavaRegexp.compile(pattern);
                case TRINO -> TrinoRegexp.compile(pattern);
            };
        }

        /// Returns `body` with the frontend's flag for case-insensitive matching beyond ASCII.
        String caseInsensitive(String body)
        {
            // Java folds only ASCII under (?i) unless UNICODE_CASE is also set.
            return (this == JAVA ? "(?iu)" : "(?i)") + body;
        }
    }

    public enum FoldPattern
    {
        LETTER_CLASS("\\p{L}"),
        NON_ASCII_RANGE("[\\x{80}-\\x{10FFFF}]"),
        LATIN_LITERAL("Les élèves étudient l'économie européenne à Genève"),
        GREEK_LITERAL("Η γρήγορη καφέ αλεπού πηδάει πάνω από τον τεμπέλη σκύλο"),
        CYRILLIC_LITERAL("Съешь же ещё этих мягких французских булок да выпей чаю");

        private final String body;

        FoldPattern(String body)
        {
            this.body = body;
        }

        String body()
        {
            return body;
        }
    }

    /// Text for [#canonicalizeText]: one sentence repeated to about 4 KB of UTF-8.
    public enum CanonicalText
    {
        ASCII("The quick brown fox jumps over the lazy dog; ERROR 42 at Main.run(Main.java:17). "),
        MIXED("Order 1042 shipped to Café Zürich for Αλέξης; Status: DELIVERED, total 39.99 EUR. "),
        NON_ASCII("Η γρήγορη καφέ αλεπού πηδάει πάνω από τον τεμπέλη σκύλο. Съешь же ещё этих мягких французских булок. ");

        private final String sentence;

        CanonicalText(String sentence)
        {
            this.sentence = sentence;
        }

        Slice source()
        {
            int[] codePoints = sentence.codePoints().toArray();
            StringBuilder text = new StringBuilder();
            int length = 0;
            for (int index = 0; ; index = (index + 1) % codePoints.length) {
                int codePointLength = Utf8.encodedLength(codePoints[index]);
                if (length + codePointLength > CANONICAL_TEXT_LENGTH) {
                    return Slices.utf8Slice(text.toString());
                }
                text.appendCodePoint(codePoints[index]);
                length += codePointLength;
            }
        }
    }

    @State(Scope.Thread)
    public static class FoldHeavyState
    {
        @Param
        Frontend frontend;

        @Param
        FoldPattern pattern;

        Slice patternSlice;

        @Setup(Level.Trial)
        public void setup()
        {
            patternSlice = Slices.utf8Slice(frontend.caseInsensitive(pattern.body()));
        }
    }

    @State(Scope.Thread)
    public static class FirstUseState
    {
        @Param
        Frontend frontend;

        Slice patternSlice;

        @Setup(Level.Trial)
        public void setup()
        {
            // Building the slice touches no case-folding data, so the measured compile is the first use.
            patternSlice = Slices.utf8Slice(frontend.caseInsensitive(FIRST_USE_PATTERN));
        }
    }

    @State(Scope.Thread)
    public static class CanonicalizeTextState
    {
        @Param
        CanonicalText text;

        FilteredRe2 filteredRe2;
        Slice source;

        @Setup(Level.Trial)
        public void setup()
        {
            FilteredRe2.Builder builder = FilteredRe2.builder();
            builder.add(Slices.utf8Slice("(?i)prefix-[0-9]+"), Re2.Options.defaults());
            filteredRe2 = builder.build();
            source = text.source();
        }
    }

    @Benchmark
    public Object compileFoldHeavy(FoldHeavyState state)
    {
        return state.frontend.compile(state.patternSlice);
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(20)
    @Warmup(iterations = 0)
    @Measurement(iterations = 1)
    public Object compileFirstCaseInsensitive(FirstUseState state)
    {
        return state.frontend.compile(state.patternSlice);
    }

    @Benchmark
    public Slice canonicalizeText(CanonicalizeTextState state)
    {
        return state.filteredRe2.canonicalizeText(state.source);
    }

    public static void main(String[] args)
            throws Throwable
    {
        Options options = Re2BenchmarkRunner.buildOptions(BenchmarkCaseInsensitiveCompile.class, args);
        new Runner(options).run();
    }
}
