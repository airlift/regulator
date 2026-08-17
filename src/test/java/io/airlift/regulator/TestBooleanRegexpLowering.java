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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestBooleanRegexpLowering
{
    @Test
    public void testLowersOnlyTerminalFinalLineEnds()
    {
        int flags = Regexp.LIKE_PERL | Regexp.FINAL_LINE_END;
        Regexp finalLineEnd = Regexp.endText(flags);
        Regexp terminal = Regexp.concat(flags, List.of(Regexp.literal(flags, 'a'), finalLineEnd));

        Regexp lowered = BooleanRegexpLowering.lowerTerminalFinalLineEnds(terminal);
        assertThat(lowered).isNotSameAs(terminal);
        assertThat(lowered.child(0)).isSameAs(terminal.child(0));
        assertOptionalFinalLineFeedAndExactEnd(lowered.child(1));

        Regexp nonterminal = Regexp.concat(flags, List.of(finalLineEnd, Regexp.literal(flags, '\n')));
        assertThat(BooleanRegexpLowering.lowerTerminalFinalLineEnds(nonterminal)).isSameAs(nonterminal);
    }

    @Test
    public void testLowersEveryTerminalAlternationBranch()
    {
        int flags = Regexp.LIKE_PERL | Regexp.FINAL_LINE_END;
        Regexp first = Regexp.capture(flags, Regexp.endText(flags), 1, null);
        Regexp second = Regexp.concat(flags, List.of(Regexp.literal(flags, 'a'), Regexp.endText(flags)));
        Regexp alternate = Regexp.alternate(flags, List.of(first, second));

        Regexp lowered = BooleanRegexpLowering.lowerTerminalFinalLineEnds(alternate);
        assertOptionalFinalLineFeedAndExactEnd(lowered.child(0).child(0));
        assertOptionalFinalLineFeedAndExactEnd(lowered.child(1).child(1));
    }

    @Test
    public void testDeepCaptureTreeIsStackSafe()
    {
        int flags = Regexp.LIKE_PERL | Regexp.FINAL_LINE_END;
        int captureCount = 20_000;
        Regexp regexp = Regexp.endText(flags);
        for (int capture = 1; capture <= captureCount; capture++) {
            regexp = Regexp.capture(flags, regexp, capture, null);
        }

        Regexp lowered = BooleanRegexpLowering.lowerTerminalFinalLineEnds(regexp);
        for (int capture = 0; capture < captureCount; capture++) {
            assertThat(lowered.op()).isEqualTo(RegexpOp.CAPTURE);
            lowered = lowered.child(0);
        }
        assertOptionalFinalLineFeedAndExactEnd(lowered);
    }

    private static void assertOptionalFinalLineFeedAndExactEnd(Regexp regexp)
    {
        assertThat(regexp.op()).isEqualTo(RegexpOp.CONCAT);
        assertThat(regexp.child(0).op()).isEqualTo(RegexpOp.QUEST);
        assertThat(regexp.child(0).child(0).op()).isEqualTo(RegexpOp.LITERAL);
        assertThat(regexp.child(0).child(0).rune()).isEqualTo('\n');
        assertThat(regexp.child(1).op()).isEqualTo(RegexpOp.END_TEXT);
        assertThat(regexp.child(1).parseFlags() & Regexp.FINAL_LINE_END).isZero();
    }
}
