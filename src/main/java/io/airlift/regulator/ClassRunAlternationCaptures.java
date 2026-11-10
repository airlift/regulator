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

import java.util.Arrays;

import static io.airlift.regulator.RegexpOp.ALTERNATE;
import static io.airlift.regulator.RegexpOp.CAPTURE;
import static io.airlift.regulator.RegexpOp.REPEAT;

/// The captures of an alternation of runs of one class, each branch a group around a run of a
/// fixed length one less than the branch before it, as in
/// `(\p{L}{14})|(\p{L}{13})|...|(\p{L}{5})`.
///
/// Under leftmost-first priority the leftmost position where some branch matches is the leftmost
/// run of at least the last branch's length, and there the first branch that fits wins: the
/// longest one the run holds. So the alternation matches exactly what `C{a,b}` matches, with `a`
/// the last branch's length and `b` the first's, and the branch that matched a run of `m` members
/// is the one of length `m`, group `b - m + 1`. Every language's alternation is leftmost-first
/// unless the pattern compiles for longest match. Lengths that skip a value, or that do not fall
/// from branch to branch, make the alternation pick a shorter branch than the run holds, so they
/// keep the program. Leftmost-longest matching picks the same branch, but the class counter that
/// answers `C{a,b}` serves leftmost-first patterns only, so longest-match patterns keep the program
/// too.
final class ClassRunAlternationCaptures
{
    private ClassRunAlternationCaptures() {}

    /// Returns the run expression `C{a,b}` that matches what `expression` matches, where
    /// `expression` is an alternation of `capturingGroupCount` branches of the shape above, or
    /// `null` for any other expression. With as many groups as branches, every one a group, branch
    /// `i` is group `i + 1`, as [#materialize] assumes.
    static Regexp runExpression(Regexp expression, int capturingGroupCount)
    {
        if (expression.op() != ALTERNATE || expression.childCount() < 2 || capturingGroupCount != expression.childCount()) {
            return null;
        }
        Regexp firstBranch = expression.child(0);
        if (firstBranch.op() != CAPTURE || firstBranch.child(0).op() != REPEAT) {
            return null;
        }
        Regexp firstRepeat = firstBranch.child(0);
        Regexp member = firstRepeat.child(0);
        int maximum = firstRepeat.max();
        for (int index = 0; index < expression.childCount(); index++) {
            Regexp branch = expression.child(index);
            if (branch.op() != CAPTURE || runLength(branch.child(0), member) != maximum - index) {
                return null;
            }
        }
        int minimum = maximum - expression.childCount() + 1;
        return minimum < 1 ? null : Regexp.repeat(firstRepeat.parseFlags(), member, minimum, maximum);
    }

    // The fixed length of `run` as a run of `member`, or -1. The parser writes a run of one as
    // the member itself.
    private static int runLength(Regexp run, Regexp member)
    {
        if (run.equals(member)) {
            return 1;
        }
        if (run.op() != REPEAT || run.min() != run.max() || !run.child(0).equals(member)) {
            return -1;
        }
        return run.min();
    }

    /// Writes the groups of a match of `members` class members, `[matchStart, matchEnd)`, into
    /// `groups`, which already holds group zero: the winning branch's group spans the match and
    /// every other group is unset. A run expression's maximum is the first branch's length.
    static void materialize(int maximum, int members, int matchStart, int matchEnd, int[] groups)
    {
        Arrays.fill(groups, 2, groups.length, -1);
        int offset = 2 * (maximum - members + 1);
        if (offset + 1 < groups.length) {
            groups[offset] = matchStart;
            groups[offset + 1] = matchEnd;
        }
    }
}
