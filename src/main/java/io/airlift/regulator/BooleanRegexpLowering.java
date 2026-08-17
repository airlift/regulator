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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static java.util.Objects.requireNonNull;

final class BooleanRegexpLowering
{
    private BooleanRegexpLowering() {}

    /**
     * Lowers a terminal final-LF assertion to an equivalent consuming expression for boolean matching.
     * The returned expression generally must not be used when match boundaries or captures are
     * observable. A caller may recover group-zero boundaries when it can prove that the semantic
     * expression is nonempty and cannot consume LF.
     */
    static Regexp lowerTerminalFinalLineEnds(Regexp regexp)
    {
        requireNonNull(regexp, "regexp is null");

        Deque<Frame> stack = new ArrayDeque<>();
        stack.addLast(new Frame(regexp));
        while (true) {
            Frame frame = stack.getLast();
            Regexp child = frame.nextChild();
            if (child != null) {
                stack.addLast(new Frame(child));
                continue;
            }

            Regexp lowered = frame.build();
            stack.removeLast();
            if (stack.isEmpty()) {
                return lowered;
            }
            stack.getLast().acceptChild(lowered);
        }
    }

    private static Regexp lowerFinalLineEnd(Regexp regexp)
    {
        int parseFlags = regexp.parseFlags() & ~(Regexp.FINAL_LINE_END | Regexp.NON_GREEDY);
        Regexp optionalFinalLineFeed = Regexp.quest(parseFlags, Regexp.literal(parseFlags, '\n'));
        return Regexp.concat(parseFlags, List.of(optionalFinalLineFeed, Regexp.endText(parseFlags)));
    }

    private static final class Frame
    {
        private final Regexp regexp;
        private final int firstChildIndex;
        private int nextChildIndex;
        private int activeChildIndex = -1;
        private ArrayList<Regexp> loweredChildren;

        private Frame(Regexp regexp)
        {
            this.regexp = regexp;
            firstChildIndex = switch (regexp.op()) {
                case CAPTURE -> 0;
                case CONCAT -> regexp.childCount() - 1;
                case ALTERNATE -> 0;
                default -> regexp.childCount();
            };
            nextChildIndex = firstChildIndex;
        }

        private Regexp nextChild()
        {
            int childLimit = switch (regexp.op()) {
                case CAPTURE -> 1;
                case CONCAT -> regexp.childCount();
                case ALTERNATE -> regexp.childCount();
                default -> 0;
            };
            if (nextChildIndex >= childLimit) {
                return null;
            }
            activeChildIndex = nextChildIndex++;
            return regexp.child(activeChildIndex);
        }

        private void acceptChild(Regexp child)
        {
            Regexp originalChild = regexp.child(activeChildIndex);
            if (child == originalChild) {
                return;
            }
            if (loweredChildren == null) {
                loweredChildren = new ArrayList<>(regexp.children());
            }
            loweredChildren.set(activeChildIndex, child);
        }

        private Regexp build()
        {
            if (regexp.op() == RegexpOp.END_TEXT && (regexp.parseFlags() & Regexp.FINAL_LINE_END) != 0) {
                return lowerFinalLineEnd(regexp);
            }
            if (loweredChildren == null) {
                return regexp;
            }
            return switch (regexp.op()) {
                case CAPTURE -> Regexp.capture(
                        regexp.parseFlags(),
                        loweredChildren.getFirst(),
                        regexp.captureIndex(),
                        regexp.name());
                case CONCAT -> Regexp.concat(regexp.parseFlags(), loweredChildren);
                case ALTERNATE -> Regexp.alternate(regexp.parseFlags(), loweredChildren);
                default -> throw new IllegalStateException("unexpected lowered regexp: " + regexp.op());
            };
        }
    }
}
