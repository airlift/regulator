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

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkLiteralCorpus
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param
        Language language;

        private Re2 pattern;
        private Re2 caseInsensitivePattern;
        private Slice source;

        @Setup
        public void setup()
                throws IOException
        {
            pattern = Re2.compile(Slices.utf8Slice(language.literal()));
            caseInsensitivePattern = Re2.compile(
                    Slices.utf8Slice(language.literal()),
                    Re2.Options.defaults().setCaseSensitive(false));
            source = loadSource(language);
        }
    }

    public enum Language
    {
        ENGLISH("Sherlock Holmes", "en-sampled.txt.gzip"),
        RUSSIAN("Шерлок Холмс", "ru-sampled.txt.gzip"),
        CHINESE("夏洛克·福尔摩斯", "zh-sampled.txt.gzip");

        private final String literal;
        private final String fileName;

        Language(String literal, String fileName)
        {
            this.literal = literal;
            this.fileName = fileName;
        }

        String literal()
        {
            return literal;
        }

        String fileName()
        {
            return fileName;
        }
    }

    static Slice loadSource(Language language)
            throws IOException
    {
        String resource = "/io/airlift/regulator/literal-corpus/" + language.fileName();
        InputStream input = BenchmarkLiteralCorpus.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IOException("Literal benchmark corpus does not exist: " + resource);
        }
        try (GZIPInputStream gzipInput = new GZIPInputStream(input)) {
            return Slices.wrappedBuffer(gzipInput.readAllBytes());
        }
    }

    @Benchmark
    public long count(BenchmarkData data)
    {
        return data.pattern.countMatches(data.source);
    }

    @Benchmark
    public long countCaseInsensitive(BenchmarkData data)
    {
        return data.caseInsensitivePattern.countMatches(data.source);
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkLiteralCorpus.class, args);
        new Runner(options).run();
    }
}
