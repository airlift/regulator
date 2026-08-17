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

import static org.assertj.core.api.Assertions.assertThat;

public class TestDfaReverseSearch
{
    @Test
    public void testReverseSearchFindsMatchStart()
    {
        byte[] patternBytes = "ab".getBytes(StandardCharsets.UTF_8);
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer(patternBytes), Regexp.LIKE_PERL | Regexp.LATIN1);

        Prog forward = Compiler.compile(parsed.regexp(), false, 0);
        Prog reverse = Compiler.compile(parsed.regexp(), true, 0);
        assertThat(forward).isNotNull();
        assertThat(reverse).isNotNull();

        byte[] textBytes = "zzabyy".getBytes(StandardCharsets.UTF_8);
        Slice text = Slices.wrappedBuffer(textBytes);

        long forwardResult = Dfa.search(forward, text, false, Prog.MatchKind.FIRST_MATCH, true);
        assertThat(forwardResult >= 0).isTrue();

        int matchEnd = (int) forwardResult;
        Slice prefix = Slices.wrappedBuffer(textBytes, 0, matchEnd);
        long reverseResult = Dfa.search(reverse, text, 0, prefix.length(), true, Prog.MatchKind.LONGEST_MATCH, true);
        assertThat(reverseResult >= 0).isTrue();
        assertThat((int) reverseResult).isEqualTo(2);
    }

    @Test
    public void testReverseMatchingSelfLoopUsesDirectScan()
    {
        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice("(?s).*"), Regexp.LIKE_PERL);
        Prog reverse = Compiler.compile(parsed.regexp(), true, 0);
        Slice text = Slices.utf8Slice("x".repeat(10_000));

        assertThat(Dfa.search(reverse, text, 0, text.length(), true, Prog.MatchKind.LONGEST_MATCH, true))
                .isZero();
        assertThat(reverse.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH).matchingSelfLoopScanCount())
                .isPositive();
    }

    @Test
    public void testReverseMatchingSelfLoopStopsAtNewline()
    {
        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice(".*"), Regexp.LIKE_PERL);
        Prog reverse = Compiler.compile(parsed.regexp(), true, 0);
        Slice text = Slices.utf8Slice("before\nsuffix");

        assertThat(Dfa.search(reverse, text, 0, text.length(), true, Prog.MatchKind.LONGEST_MATCH, true))
                .isEqualTo("before\n".length());
        assertThat(reverse.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH).matchingSelfLoopScanCount())
                .isPositive();
    }

    @Test
    public void testReverseMatchingSelfLoopRequiresNullableProgram()
    {
        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice("(?s:.)+"), Regexp.LIKE_PERL);
        Prog reverse = Compiler.compile(parsed.regexp(), true, 0);
        Slice text = Slices.utf8Slice("x".repeat(10_000));

        assertThat(Dfa.search(reverse, text, 0, text.length(), true, Prog.MatchKind.LONGEST_MATCH, true))
                .isZero();
        assertThat(reverse.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH).matchingSelfLoopScanCount())
                .isZero();
    }
}
