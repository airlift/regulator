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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestProgInstructions
{
    @Test
    public void testInstructionAccessIsBoundedByCount()
    {
        Prog program = Compiler.compile(RegexpParser.parse(utf8Slice("abc"), Regexp.LIKE_PERL).regexp(), false, 0);
        int size = program.size();
        assertThat(size).isGreaterThan(2);
        assertThatThrownBy(() -> program.inst(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("instructionId out of range: -1");
        assertThatThrownBy(() -> program.inst(size))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("instructionId out of range: " + size);

        List<Prog.Inst> snapshot = program.insts();
        assertThat(snapshot).hasSize(size).doesNotContainNull();
        for (int id = 0; id < size; id++) {
            assertThat(snapshot.get(id)).isSameAs(program.inst(id));
        }

        // Removing the last instruction shrinks the count; the backing array may keep spare capacity.
        assertThatThrownBy(() -> program.removeLastInst(size - 2)).isInstanceOf(IllegalArgumentException.class);
        program.removeLastInst(size - 1);
        assertThat(program.size()).isEqualTo(size - 1);
        assertThat(program.insts()).hasSize(size - 1).doesNotContainNull();
        assertThatThrownBy(() -> program.inst(size - 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
