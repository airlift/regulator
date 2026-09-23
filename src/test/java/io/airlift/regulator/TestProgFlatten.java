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

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestProgFlatten
{
    @Test
    public void testFlattenAltOfTwoByteRanges()
    {
        Prog prog = new Prog();

        prog.add(Prog.Inst.createAlt(2, 3));                    // 1
        prog.add(Prog.Inst.createByteRange(0x61, 0x61, false, 4)); // 2
        prog.add(Prog.Inst.createByteRange(0x63, 0x63, false, 4)); // 3
        prog.add(Prog.Inst.createMatch(0));                     // 4

        prog.setStartUnanchored(1);
        prog.setStart(1);

        prog.flatten();

        assertThat(prog.dump()).isEqualTo(
                """
                1+ byte [61-61] 0 -> 3
                2. byte [63-63] 0 -> 3
                3. match! 0
                """);
    }

    @Test
    public void testInstCountTracking()
    {
        // Build a program manually to test instruction counting.
        Prog prog = new Prog();

        prog.add(Prog.Inst.createAlt(2, 3));                        // 1 - ALT
        prog.add(Prog.Inst.createByteRange(0x61, 0x61, false, 4));  // 2 - BYTE_RANGE
        prog.add(Prog.Inst.createByteRange(0x62, 0x62, false, 5));  // 3 - BYTE_RANGE
        prog.add(Prog.Inst.createCapture(0, 6));                    // 4 - CAPTURE
        prog.add(Prog.Inst.createEmptyWidth(EmptyOp.EMPTY_BEGIN_TEXT, 6)); // 5 - EMPTY_WIDTH
        prog.add(Prog.Inst.createMatch(0));                         // 6 - MATCH

        prog.setStartUnanchored(1);
        prog.setStart(1);

        // Before flatten, inst counts should be zero
        assertThat(prog.getInstCount(InstOp.BYTE_RANGE)).isEqualTo(0);
        assertThat(prog.getInstCount(InstOp.MATCH)).isEqualTo(0);

        prog.flatten();

        // After flatten, verify counts match the flattened program
        // The ALT is converted to list form (no longer exists as ALT)
        // FAIL at position 0 always exists
        int[] counts = prog.getInstCount();
        int total = 0;
        for (int count : counts) {
            total += count;
        }
        assertThat(total).isEqualTo(prog.size());

        // Verify we have at least one BYTE_RANGE and one MATCH
        assertThat(prog.getInstCount(InstOp.BYTE_RANGE)).isGreaterThanOrEqualTo(2);
        assertThat(prog.getInstCount(InstOp.MATCH)).isGreaterThanOrEqualTo(1);
        assertThat(prog.getInstCount(InstOp.FAIL)).isGreaterThanOrEqualTo(1);
    }

    @Test
    public void testByteRangeHintsMatchReference()
    {
        List<String> patterns = List.of(
                "abc",
                "a|b|c",
                "[a-c]|[b-d]x|z",
                "x*[ab]|[bc]|[cd]|q",
                "(?i)abc|ABD",
                "(?i)[a-k]|[j-z]",
                "(?i)[a-c]|[b-d]x|z",
                "(?i)k",
                "(?i)(?:ss|st)ra",
                "[^a]",
                "\\pL",
                "[0-9]+-[a-f]{2}",
                "(?s).*foo",
                "\\d{4}-\\d{2}-\\d{2}");
        int hints = 0;
        int foldCaseHints = 0;
        for (String pattern : patterns) {
            Prog program = Re2.compile(utf8Slice(pattern)).forwardProgramForDiagnostics();
            for (int id = 0; id < program.size(); id++) {
                Prog.Inst instruction = program.inst(id);
                if (instruction.opcode() != InstOp.BYTE_RANGE) {
                    continue;
                }
                assertThat(instruction.hint()).as("%s instruction %s", pattern, id).isEqualTo(referenceHint(program, id));
                if (instruction.hint() != 0) {
                    hints++;
                    if (instruction.foldCase()) {
                        foldCaseHints++;
                    }
                }
            }
        }
        // Zero hints everywhere would also match a broken reference, so require real ones.
        assertThat(hints).isPositive();
        assertThat(foldCaseHints).isPositive();
    }

    // A byte range's hint is the distance to the nearest later byte range in the same run of
    // consecutive byte ranges that can match a byte this one matches, or to the instruction
    // following the run, capped at 32767. There is no hint when that instruction would be the end
    // of the list.
    private static int referenceHint(Prog program, int id)
    {
        int end = id;
        while (!program.inst(end).last()) {
            end++;
        }
        end++;
        int runEnd = id + 1;
        while (runEnd < end && program.inst(runEnd).opcode() == InstOp.BYTE_RANGE) {
            runEnd++;
        }
        int first = end;
        for (int value = 0; value < 256; value++) {
            if (!matchesByte(program.inst(id), value)) {
                continue;
            }
            int next = id + 1;
            while (next < runEnd && !matchesByte(program.inst(next), value)) {
                next++;
            }
            first = Math.min(first, next);
        }
        return first == end ? 0 : Math.min(first - id, 32767);
    }

    private static boolean matchesByte(Prog.Inst instruction, int value)
    {
        if (instruction.lo() <= value && value <= instruction.hi()) {
            return true;
        }
        if (instruction.foldCase() && value >= 'A' && value <= 'Z') {
            int lower = value + ('a' - 'A');
            return instruction.lo() <= lower && lower <= instruction.hi();
        }
        return false;
    }
}
