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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Project-authored direct coverage for the NFA full-match entry point.
public class TestNfaFullMatch
{
    private record FullMatchCase(String pattern, String text, int flags, boolean expected) {}

    @Test
    public void testFullMatch()
    {
        List<FullMatchCase> cases = List.of(
                new FullMatchCase("a", "a", Regexp.LIKE_PERL, true),
                new FullMatchCase("a", "b", Regexp.LIKE_PERL, false),
                new FullMatchCase("a*", "", Regexp.LIKE_PERL, true),
                new FullMatchCase("a*", "aaaa", Regexp.LIKE_PERL, true),
                new FullMatchCase("a*", "aaab", Regexp.LIKE_PERL, false),
                new FullMatchCase("a*hello", "hello", Regexp.LIKE_PERL, true),
                new FullMatchCase("a*hello", "aahello", Regexp.LIKE_PERL, true),
                new FullMatchCase("a*hello", "ahell", Regexp.LIKE_PERL, false),
                new FullMatchCase("(ab|x)?(c|z)?", "", Regexp.LIKE_PERL | Regexp.LATIN1, true),
                new FullMatchCase("(ab|x)?(c|z)?", "abc", Regexp.LIKE_PERL | Regexp.LATIN1, true),
                new FullMatchCase("(ab|x)?(c|z)?", "xz", Regexp.LIKE_PERL | Regexp.LATIN1, true),
                new FullMatchCase("(ab|x)?(c|z)?", "abz", Regexp.LIKE_PERL | Regexp.LATIN1, true),
                new FullMatchCase("(ab|x)?(c|z)?", "abcz", Regexp.LIKE_PERL | Regexp.LATIN1, false),
                new FullMatchCase("\\Aa*hello", "hello", Regexp.LIKE_PERL, true),
                new FullMatchCase("\\Aa*hello", "xhello", Regexp.LIKE_PERL, false));

        for (FullMatchCase testCase : cases) {
            Prog program = compile(testCase.pattern, testCase.flags);
            Slice input = Slices.wrappedBuffer(testCase.text.getBytes(StandardCharsets.ISO_8859_1));
            assertThat(Nfa.fullMatch(program, input))
                    .as("pattern=%s text=%s", testCase.pattern, testCase.text)
                    .isEqualTo(testCase.expected);
        }
    }

    private static Prog compile(String pattern, int flags)
    {
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(StandardCharsets.ISO_8859_1)), flags);
        Prog program = Compiler.compile(parsed.regexp(), false, 0);
        assertThat(program).as("compile: %s", pattern).isNotNull();
        return program;
    }
}
