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
import org.junit.jupiter.api.Timeout;

import java.util.Collections;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public class TestProgOptimize
{
    @Test
    public void testEliminateNopsInOuts()
    {
        Prog prog = new Prog();

        prog.add(Prog.altWord(2, 3));     // 1
        prog.add(Prog.nopWord(4));        // 2
        prog.add(Prog.nopWord(5));        // 3
        prog.add(Prog.matchWord(0));      // 4
        prog.add(Prog.matchWord(1));      // 5
        prog.setStart(1);

        prog.optimize();

        long alt = prog.word(1);
        assertThat(Prog.instOpcode(alt)).isEqualTo(InstOp.ALT);
        assertThat(Prog.instOut(alt)).isEqualTo(4);
        assertThat(Prog.instOut1(alt)).isEqualTo(5);
    }

    @Test
    public void testAltMatchInsertedGreedyForm()
    {
        Prog prog = new Prog();

        prog.add(Prog.altWord(2, 4));                 // 1
        prog.add(Prog.byteRangeWord(0x00, 0xFF, false, 1)); // 2
        prog.add(Prog.failWord());                    // 3 (unused)
        prog.add(Prog.matchWord(0));                  // 4
        prog.setStart(1);

        prog.optimize();

        assertThat(Prog.instOpcode(prog.word(1))).isEqualTo(InstOp.ALT_MATCH);
    }

    @Test
    public void testAltMatchInsertedNonGreedyForm()
    {
        Prog prog = new Prog();

        prog.add(Prog.altWord(4, 2));                 // 1
        prog.add(Prog.byteRangeWord(0x00, 0xFF, false, 1)); // 2
        prog.add(Prog.failWord());                    // 3 (unused)
        prog.add(Prog.matchWord(0));                  // 4
        prog.setStart(1);

        prog.optimize();

        assertThat(Prog.instOpcode(prog.word(1))).isEqualTo(InstOp.ALT_MATCH);
    }

    @Test
    @Timeout(30)
    public void testOptimizeStaysLinear()
    {
        // Nested repeated captures loop back through every capture inside them, lazy any-byte loops
        // under many groups share every closing capture, and many instructions share one nop chain,
        // as Trino's nested nullable loops build. The old walks allocated nothing, so only the
        // timeout guards these cases.
        int depth = 200_000;
        String nestedCaptures = "(".repeat(depth) + "a" + ")+".repeat(depth);
        assertOptimizes(nestedCaptures);

        int width = 200_000;
        String sharedClosingCaptures = "(".repeat(width) + "(?:" + String.join("|", Collections.nCopies(width, "(\\C*?)")) + ")" + ")".repeat(width);
        assertOptimizes(sharedClosingCaptures);

        int chainLength = 400_000;
        Prog sharedNops = new Prog();
        for (int alt = 1; alt <= chainLength; alt++) {
            sharedNops.add(Prog.altWord(chainLength + 1, alt < chainLength ? alt + 1 : 2 * chainLength + 1));
        }
        for (int nop = chainLength + 1; nop <= 2 * chainLength; nop++) {
            sharedNops.add(Prog.nopWord(nop + 1));
        }
        sharedNops.add(Prog.matchWord(0));
        sharedNops.setStart(1);
        assertThatCode(sharedNops::optimize).doesNotThrowAnyException();
    }

    private static void assertOptimizes(String pattern)
    {
        Regexp regexp = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();
        // The widest pattern compiles to 1.4 million instructions, which need 161 MiB.
        Prog prog = Compiler.compileForBenchmark(regexp, false, 161L << 20, Compiler.CompileStage.RAW);

        assertThatCode(prog::optimize).doesNotThrowAnyException();
    }
}
