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

import static io.airlift.regulator.Regexp.FOLD_CASE;
import static io.airlift.regulator.Regexp.FULL_CASE_FOLD;
import static io.airlift.regulator.Regexp.LATIN1;
import static io.airlift.regulator.Regexp.NON_GREEDY;
import static io.airlift.regulator.RegexpOp.CAPTURE;
import static io.airlift.regulator.RegexpOp.CHAR_CLASS;
import static io.airlift.regulator.RegexpOp.PLUS;
import static io.airlift.regulator.RegexpOp.REPEAT;

final class RetainedCharacterClassCountDfa
{
    static final long MINIMUM_DFA_MEMORY = 128L << 10;

    private static final int MAXIMUM_UTF8_PATH_COST = 256;
    private static final int MAXIMUM_MATCHING_CODE_POINTS = 4096;
    private static final int MINIMUM_INPUT_BYTES = 1 << 20;

    private RetainedCharacterClassCountDfa() {}

    static boolean supports(Regexp regexp)
    {
        if ((regexp.parseFlags() & (LATIN1 | NON_GREEDY | FOLD_CASE | FULL_CASE_FOLD)) != 0) {
            return false;
        }

        long repeatExpansion;
        if (regexp.op() == PLUS) {
            repeatExpansion = 1;
        }
        else if (regexp.op() == REPEAT && regexp.min() > 0) {
            repeatExpansion = regexp.max() == -1 ? regexp.min() : regexp.max();
        }
        else {
            return false;
        }

        Regexp atom = regexp.child(0);
        while (atom.op() == CAPTURE) {
            atom = atom.child(0);
        }
        if (atom.op() != CHAR_CLASS || atom.charClass().isEmpty()) {
            return false;
        }

        CharClass characterClass = atom.charClass();
        if ((long) characterClass.runeCount() * repeatExpansion > MAXIMUM_MATCHING_CODE_POINTS) {
            return false;
        }

        long utf8PathCost = 0;
        for (RuneRange range : characterClass.ranges()) {
            int minimumWidth = utf8Width(range.low());
            int maximumWidth = utf8Width(range.high());
            for (int width = minimumWidth; width <= maximumWidth; width++) {
                utf8PathCost += width * repeatExpansion;
                if (utf8PathCost > MAXIMUM_UTF8_PATH_COST) {
                    return false;
                }
            }
        }
        return true;
    }

    static long count(BoundedCharacterClassCounter compactCounter, Prog countProgram, Slice input)
    {
        if (input.length() < MINIMUM_INPUT_BYTES || compactCounter.hasDenseSample(input)) {
            return compactCounter.count(input);
        }

        // A count whose DFA bails, or skips it after a streak of bails, counts on the class counter.
        long count = Dfa.countMatches(countProgram, input, Prog.MatchKind.FIRST_MATCH);
        if (count >= 0) {
            return count;
        }
        return compactCounter.count(input);
    }

    private static int utf8Width(int codePoint)
    {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }
}
