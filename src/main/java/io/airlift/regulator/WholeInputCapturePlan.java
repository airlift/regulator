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

import io.airlift.slice.SizeOf;

import java.util.Arrays;

/**
 * Capture endpoints for one anchored, greedy run that accepts every valid UTF-8 code point.
 */
final class WholeInputCapturePlan
{
    private final boolean[] atEnd;

    private WholeInputCapturePlan(boolean[] atEnd)
    {
        this.atEnd = atEnd;
    }

    static WholeInputCapturePlan analyze(Regexp expression, int captureCount)
    {
        if (captureCount == 0 || captureCount > 1024 || (expression.parseFlags() & Regexp.LATIN1) != 0) {
            return null;
        }
        Analyzer analyzer = new Analyzer(captureCount);
        if (!analyzer.append(expression, 0) || !analyzer.begin || !analyzer.consumed || !analyzer.end) {
            return null;
        }
        analyzer.atEnd[1] = true;
        return new WholeInputCapturePlan(analyzer.atEnd);
    }

    long estimatedRetainedSize()
    {
        return SizeOf.instanceSize(WholeInputCapturePlan.class) + SizeOf.sizeOf(atEnd);
    }

    void materialize(int length, int[] groups)
    {
        if (groups == null) {
            return;
        }
        Arrays.fill(groups, -1);
        for (int index = 0; index < Math.min(groups.length, atEnd.length); index++) {
            groups[index] = atEnd[index] ? length : 0;
        }
    }

    private static final class Analyzer
    {
        private final boolean[] atEnd;
        private boolean begin;
        private boolean consumed;
        private boolean end;

        private Analyzer(int captureCount)
        {
            atEnd = new boolean[2 * (captureCount + 1)];
        }

        private boolean append(Regexp expression, int depth)
        {
            if (depth > 256) {
                return false;
            }
            return switch (expression.op()) {
                case EMPTY_MATCH -> true;
                case CONCAT -> {
                    boolean valid = true;
                    for (Regexp child : expression.children()) {
                        if (!append(child, depth + 1)) {
                            valid = false;
                            break;
                        }
                    }
                    yield valid;
                }
                case CAPTURE -> {
                    int index = expression.captureIndex() * 2;
                    if (index < 2 || index + 1 >= atEnd.length) {
                        yield false;
                    }
                    atEnd[index] = consumed;
                    boolean valid = append(expression.child(0), depth + 1);
                    atEnd[index + 1] = consumed;
                    yield valid;
                }
                case BEGIN_TEXT -> {
                    if (consumed) {
                        yield false;
                    }
                    begin = true;
                    yield true;
                }
                case END_TEXT -> {
                    if (!consumed) {
                        yield false;
                    }
                    end = true;
                    yield true;
                }
                case STAR -> {
                    if (!begin || consumed || end ||
                            (expression.parseFlags() & (Regexp.NON_GREEDY | Regexp.LATIN1)) != 0 ||
                            expression.child(0).op() != RegexpOp.ANY_CHAR) {
                        yield false;
                    }
                    // ANY_CHAR is already the parser's full code-point set. Non-DOTALL dots
                    // are narrower classes. With only endpoint assertions after this run,
                    // greedy priority selects the real end, even for Java's final-newline $.
                    consumed = true;
                    yield true;
                }
                default -> false;
            };
        }
    }
}
