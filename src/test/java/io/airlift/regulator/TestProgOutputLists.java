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

import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

public class TestProgOutputLists
{
    @Test
    public void testOutputListsArePopulatedForSmallPrograms()
    {
        // This pattern covers every opcode a flattened program holds.
        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice("(a+)\\b(?s:.*)"), Regexp.LIKE_PERL | Regexp.LATIN1);

        Prog prog = Compiler.compile(parsed.regexp(), false, 0);
        assertThat(prog).isNotNull();
        assertThat(prog.didFlatten()).isTrue();
        assertThat(prog.canBitState()).isTrue();
        assertThat(prog.anchoredBitStateTextMaxSize()).isGreaterThan(0);
        assertOutputLists(prog);
    }

    @Test
    public void testOutputListsUpToMaximumListCount()
    {
        // A list id is a short, so a program of at most 32,767 lists gets output list ids at any
        // size.
        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice("[ab]".repeat(32764)), Regexp.LIKE_PERL | Regexp.LATIN1);

        Prog prog = Compiler.compile(parsed.regexp(), false, 0);
        assertThat(prog.listCount()).isEqualTo(Short.MAX_VALUE);
        assertThat(prog.canBitState()).isTrue();
        assertOutputLists(prog);
    }

    private static void assertOutputLists(Prog prog)
    {
        // A flattened program lays its lists out in order, each beginning at instruction 0 or after
        // an instruction marked last.
        int[] lists = new int[prog.size()];
        Arrays.fill(lists, -1);
        int listCount = 0;
        for (int id = 0; id < prog.size(); id++) {
            if (id == 0 || prog.inst(id - 1).last()) {
                lists[id] = listCount++;
            }
        }
        assertThat(prog.listCount()).isEqualTo(listCount);

        // Each step BitState takes enters the list of its instruction's output, and an attempt
        // begins in the start instruction's list. Alt, alt-match, match and fail instructions get
        // -1, since BitState never enters their outputs through a visited check.
        short[] outputLists = prog.outputLists();
        assertThat(outputLists).hasSize(prog.size());
        assertThat(prog.startList()).isEqualTo(lists[prog.start()]).isNotNegative();
        for (int id = 0; id < prog.size(); id++) {
            Prog.Inst instruction = prog.inst(id);
            int expected = switch (instruction.opcode()) {
                case BYTE_RANGE, CAPTURE, EMPTY_WIDTH, NOP -> {
                    int list = lists[instruction.out()];
                    assertThat(list).as("instruction %s", id).isNotNegative();
                    yield list;
                }
                case ALT, ALT_MATCH, MATCH, FAIL -> -1;
            };
            assertThat((int) outputLists[id]).as("instruction %s", id).isEqualTo(expected);
        }
    }
}
