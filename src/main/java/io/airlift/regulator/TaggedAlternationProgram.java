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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.airlift.regulator.Dfa.DfaInstance.Kind.MANY_MATCH;
import static io.airlift.regulator.RegexpOp.ALTERNATE;
import static io.airlift.regulator.RegexpOp.CAPTURE;

final class TaggedAlternationProgram
{
    private final Prog program;
    private final int branchCount;

    private TaggedAlternationProgram(Prog program, int branchCount)
    {
        this.program = program;
        this.branchCount = branchCount;
    }

    static TaggedAlternationProgram compile(
            Regexp expression,
            int capturingGroupCount,
            boolean longestMatch,
            Prog semanticProgram)
    {
        if (longestMatch ||
                semanticProgram.isOnePass() ||
                semanticProgram.canBitState() ||
                expression.op() != ALTERNATE ||
                expression.childCount() < 2 ||
                capturingGroupCount != expression.childCount()) {
            return null;
        }

        int parseFlags = expression.parseFlags();
        List<Regexp> taggedBranches = new ArrayList<>(expression.childCount());
        int expectedCaptureIndex = 1;
        for (Regexp branch : expression.children()) {
            if (branch.op() != CAPTURE ||
                    branch.captureIndex() != expectedCaptureIndex ||
                    containsCaptureOrUnsupportedAssertion(branch.child(0))) {
                return null;
            }
            taggedBranches.add(Regexp.concat(
                    parseFlags,
                    List.of(branch.child(0), Regexp.haveMatch(parseFlags, expectedCaptureIndex - 1))));
            expectedCaptureIndex++;
        }

        long availableForwardMemory = semanticProgram.dfaMemory();
        Prog taggedProgram;
        try {
            taggedProgram = Compiler.compileSet(
                    Regexp.alternate(parseFlags, taggedBranches),
                    false,
                    false,
                    availableForwardMemory);
        }
        catch (RegexpCompileException ignored) {
            // This program is optional; the semantic capture engines remain the fallback.
            return null;
        }
        // The semantic program may retain first-match and longest-match DFAs, while this program
        // retains one many-match DFA. Give each possible cache an equal share of the remainder.
        long sharedDfaMemory = taggedProgram.dfaMemory();
        long taggedDfaMemory = sharedDfaMemory / 3;
        long semanticDfaMemory = sharedDfaMemory - taggedDfaMemory;
        if (taggedDfaMemory <= 0) {
            return null;
        }
        taggedProgram.setDfaMemory(taggedDfaMemory);
        if (taggedProgram.getCachedDfa(MANY_MATCH) == null) {
            return null;
        }
        semanticProgram.setDfaMemory(semanticDfaMemory);
        return new TaggedAlternationProgram(taggedProgram, taggedBranches.size());
    }

    private static boolean containsCaptureOrUnsupportedAssertion(Regexp expression)
    {
        ArrayDeque<Regexp> pending = new ArrayDeque<>();
        pending.addLast(expression);
        while (!pending.isEmpty()) {
            Regexp current = pending.removeLast();
            switch (current.op()) {
                case CAPTURE, BEGIN_LINE, END_LINE, BEGIN_TEXT, END_TEXT -> {
                    return true;
                }
                default -> pending.addAll(current.children());
            }
        }
        return false;
    }

    boolean materialize(
            Slice text,
            int matchStart,
            int matchEnd,
            int contextStart,
            int[] groupOffsets)
    {
        int winningBranch = Dfa.searchManyFull(program, text, matchStart, matchEnd);
        if (winningBranch < 0 || winningBranch >= branchCount) {
            return false;
        }

        Arrays.fill(groupOffsets, -1);
        int relativeStart = matchStart - contextStart;
        int relativeEnd = matchEnd - contextStart;
        groupOffsets[0] = relativeStart;
        groupOffsets[1] = relativeEnd;
        int captureIndex = winningBranch + 1;
        int captureOffsetIndex = captureIndex * 2;
        if (captureOffsetIndex + 1 < groupOffsets.length) {
            groupOffsets[captureOffsetIndex] = relativeStart;
            groupOffsets[captureOffsetIndex + 1] = relativeEnd;
        }
        return true;
    }

    int branchCount()
    {
        return branchCount;
    }

    boolean search(
            Slice context,
            int start,
            int end,
            boolean anchored,
            Prog.MatchKind matchKind,
            int[] groupOffsets,
            Nfa.Workspace workspace)
    {
        Arrays.fill(groupOffsets, -1);
        int winningBranch = Nfa.searchTagged(program, context, start, end, anchored, matchKind, groupOffsets, workspace);
        if (winningBranch < 0) {
            return false;
        }
        if (winningBranch >= branchCount) {
            throw new IllegalStateException("tagged match has an invalid branch ID");
        }
        // Each eligible branch is one capture around its entire body. Ordered NFA execution
        // chooses the branch; no per-thread capture histories or second search are necessary.
        int captureOffsetIndex = (winningBranch + 1) * 2;
        if (captureOffsetIndex + 1 < groupOffsets.length) {
            groupOffsets[captureOffsetIndex] = groupOffsets[0];
            groupOffsets[captureOffsetIndex + 1] = groupOffsets[1];
        }
        return true;
    }

    Prog program()
    {
        return program;
    }
}
