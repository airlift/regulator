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

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One-pass node tables start at the bound for small programs and grow on demand for large ones.
 * These programs need more nodes than the initial capacity, so the growth path must keep every
 * earlier node and action intact.
 */
public class TestOnePassNodeTables
{
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @Test
    public void testNodeTablesGrowForLongLiteral()
    {
        // 310 byte ranges over about 63 byte classes: the initial capacity of 130 nodes grows twice.
        String literal = ALPHANUMERIC.repeat(5);
        Prog program = compile("(" + literal + ")");
        assertThat(program.isOnePass()).isTrue();
        assertNodeTablesGrewTwice(program);

        int[] groups = new int[4];
        assertThat(OnePass.search(program, utf8Slice(literal), true, Prog.MatchKind.FULL_MATCH, groups)).isTrue();
        assertThat(groups).containsExactly(0, 310, 0, 310);
        assertThat(OnePass.search(program, utf8Slice(literal.substring(0, literal.length() - 1) + "!"), true, Prog.MatchKind.FULL_MATCH, groups)).isFalse();
        assertThat(OnePass.search(program, utf8Slice("!" + literal.substring(1)), true, Prog.MatchKind.FULL_MATCH, groups)).isFalse();
    }

    @Test
    public void testNodeTablesGrowWithExtendedCaptures()
    {
        // Twenty-five groups need 52 capture slots, more than a base one-pass action encodes, so
        // the extended-capture tables grow as well. Each group is a twelve-character chunk of the
        // alphabet; the start advances by twelve modulo 50 so every chunk fits in the 62 characters
        // and consecutive chunks differ.
        StringBuilder pattern = new StringBuilder();
        StringBuilder text = new StringBuilder();
        for (int group = 0; group < 25; group++) {
            String chunk = ALPHANUMERIC.substring((group * 12) % 50, (group * 12) % 50 + 12);
            pattern.append('(').append(chunk).append(')');
            text.append(chunk);
        }
        Prog program = compile(pattern.toString());
        assertThat(program.isOnePass()).isTrue();
        assertThat(program.supportsOnePassCaptureSlots(52)).isTrue();
        assertNodeTablesGrewTwice(program);

        int[] groups = new int[52];
        assertThat(OnePass.search(program, utf8Slice(text.toString()), true, Prog.MatchKind.FULL_MATCH, groups)).isTrue();
        assertThat(groups[0]).isEqualTo(0);
        assertThat(groups[1]).isEqualTo(300);
        for (int group = 1; group <= 25; group++) {
            assertThat(groups[2 * group]).as("group %s start", group).isEqualTo((group - 1) * 12);
            assertThat(groups[2 * group + 1]).as("group %s end", group).isEqualTo(group * 12);
        }
        assertThat(OnePass.search(program, utf8Slice(text.substring(0, text.length() - 1) + "!"), true, Prog.MatchKind.FULL_MATCH, groups)).isFalse();
    }

    // More states than two doublings of the initial capacity proves two growths; the retained
    // action table is trimmed to the final state count.
    private static void assertNodeTablesGrewTwice(Prog program)
    {
        int initialNodeCapacity = Prog.onePassInitialNodeCapacity(program.bytemapRange());
        assertThat(program.onePassStateCount()).isGreaterThan(2 * initialNodeCapacity);
        assertThat(program.onePassAction()).hasSize(program.onePassStateCount() * program.bytemapRange());
    }

    private static Prog compile(String pattern)
    {
        return Compiler.compile(RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp(), false, 0);
    }
}
