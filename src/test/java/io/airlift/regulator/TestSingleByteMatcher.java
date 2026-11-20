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
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.airlift.regulator.TestingPatternShapes.shape;
import static org.assertj.core.api.Assertions.assertThat;

public class TestSingleByteMatcher
{
    @Test
    public void testSupportedMatchersAgreeWithRegularMatching()
    {
        List<PatternCase> patterns = List.of(
                new PatternCase("a", Re2.Options.defaults()),
                new PatternCase("[\\x00-\\x7f]", Re2.Options.defaults()),
                new PatternCase("[,;]", Re2.Options.defaults()),
                new PatternCase("a|b", Re2.Options.defaults()),
                new PatternCase("(?i)a", Re2.Options.defaults()),
                new PatternCase("(a)", Re2.Options.defaults()),
                new PatternCase("a{1}", Re2.Options.defaults()),
                new PatternCase("\\C", Re2.Options.defaults()),
                new PatternCase(".", Re2.Options.latin1()),
                new PatternCase("(?s:.)", Re2.Options.latin1()));

        byte[] bytes = new byte[260];
        bytes[0] = 'x';
        bytes[1] = 'x';
        for (int value = 0; value < 256; value++) {
            bytes[value + 2] = (byte) value;
        }
        bytes[258] = 'x';
        bytes[259] = 'x';
        Slice input = Slices.wrappedBuffer(bytes, 2, 256);

        for (PatternCase patternCase : patterns) {
            Re2 re2 = Re2.compile(Slices.utf8Slice(patternCase.pattern()), patternCase.options());
            SingleByteMatcher singleByteMatcher = re2.sharedSingleByteMatcher();
            assertThat(singleByteMatcher).as(patternCase.pattern()).isNotNull();

            RegexpMatcher regularMatcher = re2.genericGroupZeroMatcherForDiagnostics(input);
            int nextStart = 0;
            long count = 0;
            while (regularMatcher.find()) {
                assertThat(regularMatcher.end() - regularMatcher.start()).as(patternCase.pattern()).isEqualTo(1);
                assertThat(singleByteMatcher.find(input, nextStart)).as(patternCase.pattern()).isEqualTo(regularMatcher.start());
                nextStart = regularMatcher.end();
                count++;
            }
            assertThat(singleByteMatcher.find(input, nextStart)).as(patternCase.pattern()).isEqualTo(-1);
            assertThat(singleByteMatcher.count(input)).as(patternCase.pattern()).isEqualTo(count);
        }
    }

    @Test
    public void testUnsupportedMatchersAreRejected()
    {
        List<String> patterns = List.of(
                "",
                "a*",
                "a?",
                "a+",
                "ab",
                "a|",
                "^a",
                "a$",
                "\\ba\\b",
                ".",
                "é",
                "[\\x{80}-\\x{ff}]",
                "^ab[,;]");

        for (String pattern : patterns) {
            Re2 re2 = Re2.compile(Slices.utf8Slice(pattern));
            assertThat(re2.sharedSingleByteMatcher()).as(pattern).isNull();
        }
    }

    @Test
    public void testOneByteRunFindAgreesWithRegularMatching()
    {
        // Boolean find of a run of one-byte members tests for one member, in every language.
        List<CompiledPattern> patterns = List.of(
                Re2.compile(Slices.utf8Slice("\\s+")),
                Re2.compile(Slices.utf8Slice("[,;]{1,}")),
                Re2.compile(Slices.utf8Slice("[\\x80-\\xff]+"), Re2.Options.latin1()),
                JavaRegexp.compile(Slices.utf8Slice("\\s+")),
                TrinoRegexp.compile(Slices.utf8Slice("\\s+")).re2(),
                RustRegexp.compile(Slices.utf8Slice("[\\t\\n\\f\\r ]+")),
                RustRegexp.compile(Slices.utf8Slice("(?-u)[\\x80-\\xff]+"), RustRegexp.Options.bytes()));
        List<Slice> inputs = List.of(
                Slices.EMPTY_SLICE,
                Slices.utf8Slice("no_whitespace_here"),
                Slices.utf8Slice("東京 München;"),
                Slices.utf8Slice("x".repeat(70) + " ,"),
                Slices.utf8Slice("x".repeat(70)),
                Slices.wrappedBuffer(new byte[] {'z', (byte) 0xFF, (byte) 0xC2, ' ', 'a'}));

        for (CompiledPattern compiledPattern : patterns) {
            assertThat(shape(compiledPattern)).as(compiledPattern.pattern().toStringUtf8()).isEqualTo(CompiledPattern.Shape.BYTE_TABLE);
            // A short find answers from the byte table without building the general engine's DFA.
            compiledPattern.find(inputs.get(2));
            assertThat(compiledPattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH)).as(compiledPattern.pattern().toStringUtf8()).isNull();
            for (Slice input : inputs) {
                assertThat(compiledPattern.find(input)).as("pattern %s, input %s", compiledPattern.pattern().toStringUtf8(), input).isEqualTo(compiledPattern.genericGroupZeroMatcherForDiagnostics(input).find());
            }
        }
        // Rust's Unicode \s also matches non-ASCII spaces, so it keeps the general engine.
        assertThat(shape(RustRegexp.compile(Slices.utf8Slice("\\s+")))).isEqualTo(CompiledPattern.Shape.ENGINE);
    }

    private record PatternCase(String pattern, Re2.Options options) {}
}
