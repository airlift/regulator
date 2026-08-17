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
import org.junit.jupiter.api.Test;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestRe2ParsedCompile
{
    @Test
    public void testParsedCompileMatchesNormalCompile()
    {
        for (String pattern : new String[] {
                "literal",
                "(?<first>[a-z]+)-(?<second>\\d+)",
                "(?i)folded",
                "^prefix.*suffix$",
                "(?:a|b){2,4}",
                "\\p{Letter}+",
        }) {
            Slice patternSlice = utf8Slice(pattern);
            ParseResult parsed = RegexpParser.parse(patternSlice, Regexp.LIKE_PERL);

            Re2 normal = Re2.compile(patternSlice);
            Re2 compiledFromParsed = Re2.compileParsed(patternSlice, parsed, Regexp.LIKE_PERL);

            assertThat(compiledFromParsed.regexp()).isEqualTo(normal.regexp());
            assertThat(compiledFromParsed.capturingGroupCount()).isEqualTo(normal.capturingGroupCount());
            assertThat(compiledFromParsed.namedCapturingGroups()).isEqualTo(normal.namedCapturingGroups());

            for (String input : new String[] {"", "literal", "abc-123", "FOLDED", "prefix middle suffix", "ab", "αβ"}) {
                Slice inputSlice = utf8Slice(input);
                assertThat(compiledFromParsed.find(inputSlice))
                        .as("pattern %s, input %s", pattern, input)
                        .isEqualTo(normal.find(inputSlice));
                assertThat(compiledFromParsed.matches(inputSlice))
                        .as("pattern %s, input %s", pattern, input)
                        .isEqualTo(normal.matches(inputSlice));
            }
        }
    }

    @Test
    public void testPatternIsCopied()
    {
        Slice pattern = utf8Slice("original");
        ParseResult parsed = RegexpParser.parse(pattern, Regexp.LIKE_PERL);

        Re2 compiled = Re2.compileParsed(pattern, parsed, Regexp.LIKE_PERL);
        pattern.setByte(0, 'X');

        assertThat(compiled.pattern()).isEqualTo(utf8Slice("original"));
    }

    @Test
    public void testEquivalentFrontendsProduceEquivalentPrograms()
    {
        for (String pattern : new String[] {
                "literal",
                "(?<name>[a-z]+)-[0-9]+",
                "a(?:b|c)+d",
                "\\A(?:ab|cd){2,4}\\z",
        }) {
            Slice patternSlice = utf8Slice(pattern);
            ParseResult re2Parsed = RegexpParser.parse(patternSlice, Regexp.LIKE_PERL);
            ParseResult trinoParsed = TrinoRegexpParser.parse(patternSlice, Regexp.LIKE_PERL);
            ParseResult javaParsed = JavaRegexpParser.parse(patternSlice, Regexp.LIKE_PERL);

            assertThat(trinoParsed.regexp()).as("Trino AST for %s", pattern).isEqualTo(re2Parsed.regexp());
            assertThat(javaParsed.regexp()).as("Java AST for %s", pattern).isEqualTo(re2Parsed.regexp());

            Re2 re2 = Re2.compileParsed(patternSlice, re2Parsed, Regexp.LIKE_PERL);
            Re2 trino = Re2.compileParsed(patternSlice, trinoParsed, Regexp.LIKE_PERL);
            Re2 java = Re2.compileParsed(patternSlice, javaParsed, Regexp.LIKE_PERL);

            assertThat(trino.forwardProgramForDiagnostics().dump())
                    .as("Trino program for %s", pattern)
                    .isEqualTo(re2.forwardProgramForDiagnostics().dump());
            assertThat(java.forwardProgramForDiagnostics().dump())
                    .as("Java program for %s", pattern)
                    .isEqualTo(re2.forwardProgramForDiagnostics().dump());
            assertThat(trino.forwardProgramForDiagnostics().dumpByteMap())
                    .as("Trino byte map for %s", pattern)
                    .isEqualTo(re2.forwardProgramForDiagnostics().dumpByteMap());
            assertThat(java.forwardProgramForDiagnostics().dumpByteMap())
                    .as("Java byte map for %s", pattern)
                    .isEqualTo(re2.forwardProgramForDiagnostics().dumpByteMap());
        }
    }
}
