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
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

@SuppressWarnings("CharUsedInArithmeticContext")
final class Simplifier
{
    private static final int MAX_RECURSIVE_DEPTH = 256;

    private Simplifier() {}

    static Regexp simplify(Regexp regexp, CaseFolding caseFolding)
    {
        requireNonNull(regexp, "regexp is null");
        requireNonNull(caseFolding, "caseFolding is null");
        // Upstream simplify.cc runs a coalescing pass before simplification.
        return simplifyInternal(Coalescer.coalesce(regexp, caseFolding), Mode.FULL, caseFolding);
    }

    /**
     * Parse-time canonicalization that intentionally preserves counted repetitions ({@code {n,m}}),
     * matching upstream RE2 parse.cc behavior (parse dumps still contain {@code rep{...}} nodes).
     * <p>
     * Compiler pipelines must call {@link #simplify(Regexp, CaseFolding)} (FULL) to eliminate counted repeats.
     */
    static Regexp simplifyForParse(Regexp regexp, CaseFolding caseFolding)
    {
        requireNonNull(regexp, "regexp is null");
        requireNonNull(caseFolding, "caseFolding is null");
        return simplifyInternal(regexp, Mode.PARSE, caseFolding);
    }

    private enum Mode
    {
        PARSE, FULL
    }

    private static Regexp simplifyInternal(Regexp regexp, Mode mode, CaseFolding caseFolding)
    {
        return simplifyRecursive(regexp, mode, caseFolding, 0);
    }

    private static Regexp simplifyRecursive(Regexp regexp, Mode mode, CaseFolding caseFolding, int depth)
    {
        if (depth == MAX_RECURSIVE_DEPTH) {
            return (Regexp) new SimplifyWalker(mode, caseFolding).walk(regexp, null);
        }

        int flags = regexp.parseFlags();

        return switch (regexp.op()) {
            case NO_MATCH,
                 EMPTY_MATCH,
                 ANY_CHAR,
                 ANY_BYTE,
                 BEGIN_LINE,
                 END_LINE,
                 WORD_BOUNDARY,
                 NO_WORD_BOUNDARY,
                 BEGIN_TEXT,
                 END_TEXT,
                 HAVE_MATCH,
                 LITERAL -> regexp;

            case LITERAL_STRING -> {
                int[] runes = regexp.runes();
                if (runes.length == 1) {
                    yield Regexp.literal(flags, runes[0]);
                }
                yield regexp;
            }

            case CAPTURE -> {
                Regexp child = simplifyRecursive(regexp.child(0), mode, caseFolding, depth + 1);
                yield child == regexp.child(0) ? regexp : Regexp.capture(flags, child, regexp.captureIndex(), regexp.name());
            }
            case STAR -> simplifyUnary(flags, simplifyRecursive(regexp.child(0), mode, caseFolding, depth + 1), UnaryOp.STAR, regexp);
            case PLUS -> simplifyUnary(flags, simplifyRecursive(regexp.child(0), mode, caseFolding, depth + 1), UnaryOp.PLUS, regexp);
            case QUEST -> simplifyUnary(flags, simplifyRecursive(regexp.child(0), mode, caseFolding, depth + 1), UnaryOp.QUEST, regexp);
            case REPEAT -> simplifyRepeat(mode, flags, regexp, simplifyRecursive(regexp.child(0), mode, caseFolding, depth + 1));
            case CONCAT -> {
                // Set up here rather than in a helper, so each nesting level takes no extra frame.
                ConcatBuilder builder = new ConcatBuilder(caseFolding);
                yield addConcatChildrenRecursive(builder, regexp, mode, caseFolding, depth) ? builder.build(flags, regexp) : Regexp.noMatch(flags);
            }
            case ALTERNATE -> simplifyAlternateRecursive(mode, caseFolding, flags, regexp, depth);
            case CHAR_CLASS -> simplifyCharClass(mode, flags, regexp.charClass(), regexp);
        };
    }

    // Values are simplified regexps, except that a concatenation directly inside another one
    // yields a NestedConcat, which the enclosing concatenation adds in place.
    private static final class SimplifyWalker
            extends RegexpWalker<Object>
    {
        private final Mode mode;
        private final CaseFolding caseFolding;

        private SimplifyWalker(Mode mode, CaseFolding caseFolding)
        {
            this.mode = mode;
            this.caseFolding = caseFolding;
        }

        @Override
        protected PreVisitResult<Object> preVisit(Regexp regexp, Object parentValue)
        {
            // Children receive their parent node as their parent value.
            return new PreVisitResult<>(regexp, false);
        }

        @Override
        protected Object shortVisit(Regexp regexp, Object parentValue)
        {
            throw new IllegalStateException("Simplifier should not short-visit");
        }

        @Override
        @SuppressWarnings("unchecked")
        protected Object postVisit(Regexp regexp, Object parentValue, Object preValue, List<Object> simplifiedChildren)
        {
            int flags = regexp.parseFlags();

            return switch (regexp.op()) {
                case NO_MATCH,
                     EMPTY_MATCH,
                     ANY_CHAR,
                     ANY_BYTE,
                     BEGIN_LINE,
                     END_LINE,
                     WORD_BOUNDARY,
                     NO_WORD_BOUNDARY,
                     BEGIN_TEXT,
                     END_TEXT,
                     HAVE_MATCH,
                     LITERAL -> regexp;

                case LITERAL_STRING -> {
                    int[] runes = regexp.runes();
                    if (runes.length == 1) {
                        yield Regexp.literal(flags, runes[0]);
                    }
                    yield regexp;
                }

                case CAPTURE -> {
                    Regexp child = (Regexp) simplifiedChildren.getFirst();
                    yield child == regexp.child(0) ? regexp : Regexp.capture(flags, child, regexp.captureIndex(), regexp.name());
                }
                case STAR -> simplifyUnary(flags, (Regexp) simplifiedChildren.getFirst(), UnaryOp.STAR, regexp);
                case PLUS -> simplifyUnary(flags, (Regexp) simplifiedChildren.getFirst(), UnaryOp.PLUS, regexp);
                case QUEST -> simplifyUnary(flags, (Regexp) simplifiedChildren.getFirst(), UnaryOp.QUEST, regexp);
                case REPEAT -> simplifyRepeat(mode, flags, regexp, (Regexp) simplifiedChildren.getFirst());
                case CONCAT -> {
                    if (parentValue instanceof Regexp parent && parent.op() == RegexpOp.CONCAT) {
                        yield new NestedConcat(simplifiedChildren);
                    }
                    yield simplifyConcat(flags, regexp, simplifiedChildren, caseFolding);
                }
                // Only a concatenation's children can be nested concatenations.
                case ALTERNATE -> simplifyAlternate(mode, caseFolding, flags, (List<Regexp>) (List<?>) simplifiedChildren);
                case CHAR_CLASS -> simplifyCharClass(mode, flags, regexp.charClass(), regexp);
            };
        }
    }

    // The simplified children of a concatenation inside another one, not yet added to it.
    private record NestedConcat(List<Object> simplifiedChildren) {}

    private enum UnaryOp
    {
        STAR, PLUS, QUEST
    }

    /**
     * Simplifies a unary operator over an already simplified child. Returns {@code original}, a node
     * with this operator and these flags, when rebuilding would produce an equal node.
     */
    private static Regexp simplifyUnary(int flags, Regexp childRegexp, UnaryOp operator, Regexp original)
    {
        // Simplify repetition over known-empty / known-impossible expressions.
        if (childRegexp.op() == RegexpOp.EMPTY_MATCH) {
            return Regexp.emptyMatch(flags);
        }
        if (childRegexp.op() == RegexpOp.NO_MATCH) {
            return switch (operator) {
                case STAR, QUEST -> Regexp.emptyMatch(flags);
                case PLUS -> Regexp.noMatch(flags);
            };
        }

        // Upstream simplify.cc only treats unary operators as idempotent when
        // the operator (and flags) are identical: **, ++, ??.
        if (childRegexp.op() == switch (operator) { case STAR -> RegexpOp.STAR; case PLUS -> RegexpOp.PLUS; case QUEST -> RegexpOp.QUEST; } &&
                childRegexp.parseFlags() == flags) {
            return childRegexp;
        }

        if (childRegexp == original.child(0)) {
            return original;
        }
        return switch (operator) {
            case STAR -> Regexp.rawUnary(RegexpOp.STAR, flags, childRegexp);
            case PLUS -> Regexp.rawUnary(RegexpOp.PLUS, flags, childRegexp);
            case QUEST -> Regexp.rawUnary(RegexpOp.QUEST, flags, childRegexp);
        };
    }

    private static Regexp simplifyRepeat(Mode mode, int flags, Regexp regexp, Regexp childRegexp)
    {
        int min = regexp.min();
        int max = regexp.max();

        if (mode == Mode.PARSE) {
            // Preserve counted repeats in parse results (upstream parse_test.cc expects rep{...}).
            return childRegexp == regexp.child(0) ? regexp : Regexp.repeat(flags, childRegexp, min, max);
        }
        // and SimplifyWalker::SimplifyRepeat().
        if (childRegexp.op() == RegexpOp.EMPTY_MATCH) {
            return childRegexp;
        }

        // For an empty-width op OR a concatenation or alternation of empty-width ops,
        // cap the repetition count at 1.
        if (isEmptyOp(childRegexp) || isConcatOrAlternateOfEmptyOps(childRegexp)) {
            min = Math.min(min, 1);
            max = Math.min(max, 1);
        }

        // x{n,} means at least n matches of x.
        if (max == -1) {
            if (min == 0) {
                return Regexp.star(flags, childRegexp);
            }
            if (min == 1) {
                return Regexp.plus(flags, childRegexp);
            }

            // General case: x{4,} is xxxx+
            List<Regexp> repeatedChildren = new ArrayList<>(min);
            for (int repeatedIndex = 0; repeatedIndex < min - 1; repeatedIndex++) {
                repeatedChildren.add(childRegexp);
            }
            repeatedChildren.add(Regexp.plus(flags, childRegexp));
            return Regexp.concat(flags, repeatedChildren);
        }

        // Special case: (x){0} matches only empty string.
        if (min == 0 && max == 0) {
            return Regexp.emptyMatch(flags);
        }

        // Special case: x{1} is just x.
        if (min == 1 && max == 1) {
            return childRegexp;
        }

        // General case: x{n,m} means n copies of x and m copies of x?.
        // Nest the final m copies:
        //   x{2,5} = xx(x(x(x)?)?)?
        Regexp result = null;
        if (min > 0) {
            List<Regexp> prefix = new ArrayList<>(min);
            for (int prefixIndex = 0; prefixIndex < min; prefixIndex++) {
                prefix.add(childRegexp);
            }
            result = (prefix.size() == 1) ? prefix.getFirst() : Regexp.concat(flags, prefix);
        }

        if (max > min) {
            Regexp suffix = Regexp.quest(flags, childRegexp);
            for (int repeatIndex = min + 1; repeatIndex < max; repeatIndex++) {
                suffix = Regexp.quest(flags, Regexp.concat(flags, List.of(childRegexp, suffix)));
            }
            result = (result == null) ? suffix : Regexp.concat(flags, List.of(result, suffix));
        }

        if (result == null) {
            // Degenerate cases (e.g., min > max) should be rejected by the parser.
            return Regexp.noMatch(flags);
        }
        return result;
    }

    private static boolean isEmptyOp(Regexp regexp)
    {
        return switch (regexp.op()) {
            case BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT -> true;
            default -> false;
        };
    }

    private static boolean isConcatOrAlternateOfEmptyOps(Regexp regexp)
    {
        if (regexp.op() != RegexpOp.CONCAT && regexp.op() != RegexpOp.ALTERNATE) {
            return false;
        }
        for (Regexp childRegexp : regexp.children()) {
            if (!isEmptyOp(childRegexp)) {
                return false;
            }
        }
        return true;
    }

    private static Regexp simplifyConcat(int flags, Regexp regexp, List<Object> simplifiedChildren, CaseFolding caseFolding)
    {
        ConcatBuilder builder = new ConcatBuilder(caseFolding);
        // Nested concatenations add their children in place, so a deep nesting is not flattened
        // again at every level.
        Deque<Iterator<Object>> enclosing = new ArrayDeque<>();
        Iterator<Object> children = simplifiedChildren.iterator();
        while (true) {
            if (!children.hasNext()) {
                if (enclosing.isEmpty()) {
                    break;
                }
                builder.endNested();
                children = enclosing.pop();
                continue;
            }
            Object child = children.next();
            if (child instanceof NestedConcat nested) {
                builder.beginNested();
                enclosing.push(children);
                children = nested.simplifiedChildren().iterator();
            }
            else if (!builder.add((Regexp) child)) {
                return Regexp.noMatch(flags);
            }
        }
        return builder.build(flags, regexp);
    }

    // Adds the simplified children of a concatenation. A concatenation child within the recursion
    // limit adds its own children in place, so a deep nesting is not flattened again at every level.
    private static boolean addConcatChildrenRecursive(ConcatBuilder builder, Regexp regexp, Mode mode, CaseFolding caseFolding, int depth)
    {
        for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
            Regexp child = regexp.child(childIndex);
            if (child.op() == RegexpOp.CONCAT && depth + 1 < MAX_RECURSIVE_DEPTH) {
                builder.beginNested();
                if (!addConcatChildrenRecursive(builder, child, mode, caseFolding, depth + 1)) {
                    return false;
                }
                builder.endNested();
            }
            else if (!builder.add(simplifyRecursive(child, mode, caseFolding, depth + 1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds a simplified concatenation from simplified children. Empty matches are dropped, a
     * child concatenation contributes its children, and adjacent literals with equal flags merge
     * into one literal string, so {@code (?:ab)(?:cd)} becomes {@code str{abcd}} as in upstream
     * parse_test.cc.
     * <p>
     * The Trino parser builds each run of fully folded literals, which full case folding matches
     * as one sequence. Two fully folded runs stay apart whenever their joined text contains a
     * multi-character fold, including one entirely within either run. Joining the copies of
     * {@code (?i)s{2}} would match {@code ß}.
     * <p>
     * Merging literals one at a time would recopy the growing string for each literal. Instead the
     * open run of literals collects its runes in one buffer and becomes a node once it closes.
     * <p>
     * A nested concatenation's children are added between {@link #beginNested()} and
     * {@link #endNested()}, with the same result as simplifying the nested concatenation and then
     * adding it: its first child does not merge with an earlier literal, but a lone remaining child
     * merges as any child does, and a later literal can merge with its last child.
     */
    private static final class ConcatBuilder
    {
        // Slots of an enclosing concatenation's frame in the enclosing stack.
        private static final int FRAME_NESTED_START = 0;
        private static final int FRAME_RUN_INDEX = 1;
        private static final int FRAME_RUN_NODES = 2;
        private static final int FRAME_RUN_START = 3;
        private static final int FRAME_RUN_END = 4;
        private static final int FRAME_SIZE = 5;

        private final CaseFolding caseFolding;
        private final List<Regexp> children = new ArrayList<>();

        // Runes of buffered literal runs. The open run, when buffered, ends the buffer, and each
        // buffered run of an enclosing concatenation precedes the runes buffered inside it.
        private int[] runes = new int[16];
        private int runeCount;
        // The open run is the last child, which a following literal with the same flags extends.
        // It holds its first literal node until a second literal joins it, or until it encloses
        // buffered runes; only then are its runes buffered.
        private int runIndex = -1;
        private int runNodes;
        private int runStart = -1;
        // Index of the current nested concatenation's first child; its children cannot merge
        // with anything before it.
        private int nestedStart;
        // Per enclosing concatenation: its first child, and its open run's index, node count,
        // buffer start and buffer end. The run's start is negative until it is buffered.
        private int[] enclosing = new int[0];
        private int enclosingCount;
        // The enclosing concatenations from this frame on may hold runs not yet buffered; those
        // before it hold none.
        private int firstUnbufferedFrame;

        private ConcatBuilder(CaseFolding caseFolding)
        {
            this.caseFolding = caseFolding;
        }

        // Returns false when the child never matches, which makes the whole concatenation a
        // no-match.
        boolean add(Regexp child)
        {
            switch (child.op()) {
                case NO_MATCH -> {
                    return false;
                }
                case EMPTY_MATCH -> {}
                case CONCAT -> {
                    closeOpenRun();
                    children.addAll(child.children());
                    if (isLiteralOrLiteralString(children.getLast())) {
                        openRun();
                    }
                }
                case LITERAL, LITERAL_STRING -> {
                    if (runIndex >= 0 && children.get(runIndex).parseFlags() == child.parseFlags() &&
                            !joinRequiresFullCaseFold(children.get(runIndex), runNodes, runStart, runeCount, child, 1, -1, -1)) {
                        bufferOpenRun();
                        appendRunes(child);
                        runNodes++;
                    }
                    else {
                        closeOpenRun();
                        children.add(child);
                        openRun();
                    }
                }
                default -> {
                    closeOpenRun();
                    children.add(child);
                }
            }
            return true;
        }

        void beginNested()
        {
            // The open run may absorb the nested concatenation's lone child. It stays unbuffered
            // until a merge or nested buffering first needs it, and bufferEnclosingRuns then copies
            // it ahead of the runes buffered inside.
            if (enclosingCount + FRAME_SIZE > enclosing.length) {
                enclosing = Arrays.copyOf(enclosing, Math.max(4 * FRAME_SIZE, enclosing.length * 2));
            }
            enclosing[enclosingCount + FRAME_NESTED_START] = nestedStart;
            enclosing[enclosingCount + FRAME_RUN_INDEX] = runIndex;
            enclosing[enclosingCount + FRAME_RUN_NODES] = runNodes;
            enclosing[enclosingCount + FRAME_RUN_START] = runStart;
            enclosing[enclosingCount + FRAME_RUN_END] = runeCount;
            enclosingCount += FRAME_SIZE;
            nestedStart = children.size();
            runIndex = -1;
            runNodes = 0;
            runStart = -1;
        }

        void endNested()
        {
            enclosingCount -= FRAME_SIZE;
            int enclosingRunIndex = enclosing[enclosingCount + FRAME_RUN_INDEX];
            int enclosingRunNodes = enclosing[enclosingCount + FRAME_RUN_NODES];
            int enclosingRunStart = enclosing[enclosingCount + FRAME_RUN_START];
            int enclosingRunEnd = enclosing[enclosingCount + FRAME_RUN_END];
            int nestedChildCount = children.size() - nestedStart;
            nestedStart = enclosing[enclosingCount + FRAME_NESTED_START];
            firstUnbufferedFrame = Math.min(firstUnbufferedFrame, enclosingCount / FRAME_SIZE);

            if (nestedChildCount == 0) {
                // Simplified to an empty match, which adds nothing.
                runIndex = enclosingRunIndex;
                runNodes = enclosingRunNodes;
                runStart = enclosingRunStart;
                return;
            }
            if (nestedChildCount == 1 && runIndex >= 0 && enclosingRunIndex >= 0 &&
                    children.get(enclosingRunIndex).parseFlags() == children.get(runIndex).parseFlags() &&
                    !joinRequiresFullCaseFold(
                            children.get(enclosingRunIndex),
                            enclosingRunNodes,
                            enclosingRunStart,
                            enclosingRunEnd,
                            children.get(runIndex),
                            runNodes,
                            runStart,
                            runeCount)) {
                // A lone literal merges into the enclosing run. A buffered lone run follows that
                // run's buffered runes directly, since buffering it buffered the enclosing runs
                // first and nothing else stayed buffered since. Otherwise the lone literal, and the
                // enclosing run if not yet buffered, are copied now.
                Regexp literal = children.removeLast();
                int loneNodes = runNodes;
                boolean loneBuffered = runStart >= 0;
                runIndex = enclosingRunIndex;
                runNodes = enclosingRunNodes;
                runStart = enclosingRunStart;
                if (!loneBuffered) {
                    bufferOpenRun();
                    appendRunes(literal);
                }
                runNodes += loneNodes;
                return;
            }
            // The enclosing run is no longer last. The nested concatenation's open run, if any,
            // stays open: a later literal merges with the last child of a flattened concatenation.
            closeRun(enclosingRunIndex, enclosingRunNodes, enclosingRunStart, enclosingRunEnd);
        }

        Regexp build(int flags, Regexp original)
        {
            closeOpenRun();
            if (children.isEmpty()) {
                return Regexp.emptyMatch(flags);
            }
            if (children.size() == 1) {
                return children.getFirst();
            }
            if (sameChildren(original, children)) {
                return original;
            }
            return Regexp.concat(flags, children);
        }

        // Whether joining two runs with equal flags would give text that needs full case folding.
        // A run is its first node, or, once buffered (start not negative), its complete runes in
        // the buffer from start to end.
        //
        // Checking the whole joined text at every join would cost the run's length per literal.
        // The check is exact without that: the joined text needs full folding exactly when the
        // left run does, the right run does, or a multi-character token crosses the boundary.
        // Folding maps each rune on its own, so the fold of the joined text is the left fold
        // followed by the right fold, and a crossing token of n folded runes reaches at most
        // n - 1 of them on either side. Each rune folds to at least one rune, so those lie within
        // the fold of the left run's last and the right run's first maximumTokenLength() - 1 runes,
        // and a token in the fold of those runes is in the fold of the joined text. A run of
        // several nodes was checked when it joined, so only a run of one node is checked whole,
        // which happens at most twice per node: once as the right run and once as the left. A run
        // no longer than the boundary window lies wholly within it and needs no check of its own.
        private boolean joinRequiresFullCaseFold(Regexp left, int leftNodes, int leftStart, int leftEnd, Regexp right, int rightNodes, int rightStart, int rightEnd)
        {
            if ((left.parseFlags() & Regexp.FULL_CASE_FOLD) == 0) {
                return false;
            }
            int reach = caseFolding.maximumTokenLength() - 1;
            int leftLength = leftStart < 0 ? literalLength(left) : leftEnd - leftStart;
            int rightLength = rightStart < 0 ? literalLength(right) : rightEnd - rightStart;
            if ((leftNodes == 1 && leftLength > reach && caseFolding.requiresFullCaseFold(literalRunes(left))) ||
                    (rightNodes == 1 && rightLength > reach && caseFolding.requiresFullCaseFold(literalRunes(right)))) {
                return true;
            }
            int leftCount = Math.min(reach, leftLength);
            int rightCount = Math.min(reach, rightLength);
            int[] boundary = new int[leftCount + rightCount];
            for (int offset = 0; offset < leftCount; offset++) {
                int index = leftLength - leftCount + offset;
                boundary[offset] = leftStart < 0 ? literalRune(left, index) : runes[leftStart + index];
            }
            for (int offset = 0; offset < rightCount; offset++) {
                boundary[leftCount + offset] = rightStart < 0 ? literalRune(right, offset) : runes[rightStart + offset];
            }
            return caseFolding.requiresFullCaseFold(boundary);
        }

        private void openRun()
        {
            runIndex = children.size() - 1;
            runNodes = 1;
            runStart = -1;
        }

        private void bufferOpenRun()
        {
            if (runStart < 0) {
                bufferEnclosingRuns();
                runStart = runeCount;
                appendRunes(children.get(runIndex));
            }
        }

        // Buffers the enclosing runs not yet buffered, outermost first, so that each precedes the
        // runes of the concatenations nested in it. Each run is copied once, when the first rune
        // after it is buffered; copying them after the nested runes would shift those at every
        // level of a deep nesting.
        private void bufferEnclosingRuns()
        {
            int frameCount = enclosingCount / FRAME_SIZE;
            for (int frame = firstUnbufferedFrame; frame < frameCount; frame++) {
                int frameStart = frame * FRAME_SIZE;
                int frameRunIndex = enclosing[frameStart + FRAME_RUN_INDEX];
                if (frameRunIndex >= 0 && enclosing[frameStart + FRAME_RUN_START] < 0) {
                    enclosing[frameStart + FRAME_RUN_START] = runeCount;
                    appendRunes(children.get(frameRunIndex));
                    enclosing[frameStart + FRAME_RUN_END] = runeCount;
                }
            }
            firstUnbufferedFrame = frameCount;
        }

        private void closeOpenRun()
        {
            closeRun(runIndex, runNodes, runStart, runeCount);
            runIndex = -1;
        }

        private void closeRun(int index, int nodes, int start, int end)
        {
            if (index < 0) {
                return;
            }
            if (nodes > 1) {
                Regexp first = children.get(index);
                children.set(index, Regexp.literalString(first.parseFlags(), Arrays.copyOfRange(runes, start, end)));
            }
            if (start >= 0 && end == runeCount) {
                runeCount = start;
            }
        }

        private static int[] literalRunes(Regexp literal)
        {
            return literal.op() == RegexpOp.LITERAL ? new int[] {literal.rune()} : literal.runes();
        }

        private static int literalLength(Regexp literal)
        {
            return literal.op() == RegexpOp.LITERAL ? 1 : literal.runes().length;
        }

        private static int literalRune(Regexp literal, int index)
        {
            return literal.op() == RegexpOp.LITERAL ? literal.rune() : literal.runes()[index];
        }

        private void appendRunes(Regexp literal)
        {
            if (literal.op() == RegexpOp.LITERAL) {
                ensureRuneCapacity(1);
                runes[runeCount++] = literal.rune();
                return;
            }
            int[] literalRunes = literal.runes();
            ensureRuneCapacity(literalRunes.length);
            System.arraycopy(literalRunes, 0, runes, runeCount, literalRunes.length);
            runeCount += literalRunes.length;
        }

        private void ensureRuneCapacity(int additional)
        {
            if (runeCount + additional > runes.length) {
                runes = Arrays.copyOf(runes, Math.max(runes.length * 2, runeCount + additional));
            }
        }
    }

    private static boolean sameChildren(Regexp regexp, List<Regexp> children)
    {
        if (children.size() != regexp.childCount()) {
            return false;
        }
        for (int childIndex = 0; childIndex < children.size(); childIndex++) {
            if (children.get(childIndex) != regexp.child(childIndex)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLiteralOrLiteralString(Regexp regexp)
    {
        return regexp.op() == RegexpOp.LITERAL || regexp.op() == RegexpOp.LITERAL_STRING;
    }

    private static Regexp simplifyAlternate(Mode mode, CaseFolding caseFolding, int flags, List<Regexp> children)
    {
        List<Regexp> simplifiedAlternatives = new ArrayList<>();
        for (Regexp simplifiedChild : children) {
            if (simplifiedChild.op() == RegexpOp.NO_MATCH) {
                continue;
            }
            if (simplifiedChild.op() == RegexpOp.ALTERNATE) {
                simplifiedAlternatives.addAll(simplifiedChild.children());
            }
            else {
                simplifiedAlternatives.add(simplifiedChild);
            }
        }

        return simplifyAlternatives(mode, caseFolding, flags, simplifiedAlternatives);
    }

    private static Regexp simplifyAlternatives(Mode mode, CaseFolding caseFolding, int flags, List<Regexp> simplifiedAlternatives)
    {
        if (simplifiedAlternatives.isEmpty()) {
            return Regexp.noMatch(flags);
        }
        if (simplifiedAlternatives.size() == 1) {
            return simplifiedAlternatives.getFirst();
        }

        if (mode == Mode.PARSE) {
            // Like RE2's parser, only discard capture-free scalar alternatives.
            // A byte wildcard is not a Unicode scalar wildcard, and a full fold
            // can consume several scalars even when its source is one literal.
            boolean haveAnyByte = false;
            boolean haveAnyChar = false;
            for (Regexp alternative : simplifiedAlternatives) {
                if (alternative.parseFlags() != flags) {
                    haveAnyByte = false;
                    haveAnyChar = false;
                    break;
                }
                haveAnyByte |= alternative.op() == RegexpOp.ANY_BYTE;
                haveAnyChar |= alternative.op() == RegexpOp.ANY_CHAR;
            }
            if (haveAnyByte || haveAnyChar) {
                boolean scalarAlternatives = true;
                boolean byteAlternatives = true;
                for (Regexp alternative : simplifiedAlternatives) {
                    byteAlternatives &= alternative.op() == RegexpOp.ANY_BYTE;
                    scalarAlternatives &= switch (alternative.op()) {
                        case LITERAL -> (flags & Regexp.FULL_CASE_FOLD) == 0 ||
                                !caseFolding.requiresFullCaseFold(new int[] {alternative.rune()});
                        case CHAR_CLASS -> (alternative.parseFlags() & Regexp.FULL_CASE_FOLD) == 0 ||
                                caseFolding.multiCharacterTokens(alternative.charClass()).isEmpty();
                        case ANY_CHAR -> true;
                        case ANY_BYTE -> (flags & Regexp.LATIN1) != 0;
                        default -> false;
                    };
                }
                if (haveAnyByte && (byteAlternatives || ((flags & Regexp.LATIN1) != 0 && scalarAlternatives))) {
                    return Regexp.anyByte(flags);
                }
                if (haveAnyChar && scalarAlternatives) {
                    return Regexp.anyChar(flags);
                }
            }

            List<Regexp> factored = factorAlternationForParse(simplifiedAlternatives, flags, caseFolding);
            if (factored.size() == 1) {
                return factored.getFirst();
            }
            return Regexp.alternate(flags, factored);
        }

        List<Regexp> merged = mergeAlternateCharClassesWherePossible(mode, caseFolding, flags, simplifiedAlternatives);
        if (merged.size() == 1) {
            return merged.getFirst();
        }

        return Regexp.alternate(flags, merged);
    }

    private static Regexp simplifyAlternateRecursive(Mode mode, CaseFolding caseFolding, int flags, Regexp regexp, int depth)
    {
        List<Regexp> simplifiedAlternatives = new ArrayList<>();
        for (Regexp child : regexp.children()) {
            Regexp simplifiedChild = simplifyRecursive(child, mode, caseFolding, depth + 1);
            if (simplifiedChild.op() == RegexpOp.NO_MATCH) {
                continue;
            }
            if (simplifiedChild.op() == RegexpOp.ALTERNATE) {
                simplifiedAlternatives.addAll(simplifiedChild.children());
            }
            else {
                simplifiedAlternatives.add(simplifiedChild);
            }
        }

        return simplifyAlternatives(mode, caseFolding, flags, simplifiedAlternatives);
    }

    /**
     * Simplifies a character class. Returns {@code original} when it is a node with these flags and
     * this class and rebuilding would produce an equal node; {@code original} may be null.
     */
    private static Regexp simplifyCharClass(Mode mode, int flags, CharClass charClass, Regexp original)
    {
        if (charClass.isEmpty()) {
            return Regexp.noMatch(flags);
        }
        if (mode == Mode.FULL) {
            // A full character class can be represented more compactly as '.' (ANY_CHAR)
            // or '\C' (ANY_BYTE) depending on the rune range in effect.
            if (charClass.rangeCount() == 1) {
                RuneRange runeRange = charClass.range(0);
                int maxRune = Regexp.maxRune(flags);
                if (runeRange.low() == 0 && runeRange.high() == maxRune) {
                    if (maxRune == 0xFF) {
                        return Regexp.anyByte(flags);
                    }
                    return Regexp.anyChar(flags);
                }
            }
        }

        // Parse-time canonicalization: fold ASCII letter-pair classes like [Aa]
        // into a single FoldCase literal. This is required for features like
        // RequiredPrefixForAccel(), which only considers literals/strings.
        if (charClass.foldsAscii() && charClass.runeCount() == 2 && charClass.rangeCount() == 2) {
            RuneRange firstRange = charClass.range(0);
            RuneRange secondRange = charClass.range(1);
            if (firstRange.low() == firstRange.high() && secondRange.low() == secondRange.high()) {
                int firstRune = firstRange.low();
                int secondRune = secondRange.low();
                int lower = -1;
                int upper = -1;
                if ('a' <= firstRune && firstRune <= 'z' && 'A' <= secondRune && secondRune <= 'Z') {
                    lower = firstRune;
                    upper = secondRune;
                }
                else if ('a' <= secondRune && secondRune <= 'z' && 'A' <= firstRune && firstRune <= 'Z') {
                    lower = secondRune;
                    upper = firstRune;
                }
                if (lower >= 0 && (upper + ('a' - 'A')) == lower) {
                    return Regexp.literal(flags | Regexp.FOLD_CASE, lower);
                }
            }
        }

        if (charClass.runeCount() == 1 && charClass.rangeCount() == 1) {
            RuneRange runeRange = charClass.range(0);
            if (runeRange.low() == runeRange.high()) {
                return Regexp.literal(flags, runeRange.low());
            }
        }
        return original != null ? original : Regexp.charClass(flags, charClass);
    }

    private static List<Regexp> mergeAlternateCharClassesWherePossible(Mode mode, CaseFolding caseFolding, int flags, List<Regexp> alternatives)
    {
        ArrayList<Regexp> mergedAlternatives = new ArrayList<>(alternatives.size());

        int flagsWithoutFoldCase = flags & ~Regexp.FOLD_CASE;
        int alternativeIndex = 0;
        while (alternativeIndex < alternatives.size()) {
            Regexp alternative = alternatives.get(alternativeIndex);

            if (!mergeableAlternateSub(alternative, flags, flagsWithoutFoldCase)) {
                mergedAlternatives.add(alternative);
                alternativeIndex++;
                continue;
            }

            CharClassBuilder mergedClassBuilder = new CharClassBuilder();
            int scanIndex = alternativeIndex;
            int count = 0;
            while (scanIndex < alternatives.size()) {
                Regexp scanAlternative = alternatives.get(scanIndex);
                if (!mergeableAlternateSub(scanAlternative, flags, flagsWithoutFoldCase)) {
                    break;
                }
                // Upstream RE2 has no such merge, so each member keeps the meaning it has alone. A
                // class already holds its folded ranges, whatever the alternation's flags.
                if (scanAlternative.op() == RegexpOp.LITERAL) {
                    addLiteral(mergedClassBuilder, scanAlternative, caseFolding);
                }
                else {
                    for (RuneRange runeRange : scanAlternative.charClass().ranges()) {
                        mergedClassBuilder.addRange(runeRange.low(), runeRange.high());
                    }
                }
                count++;
                scanIndex++;
            }

            if (count >= 2) {
                mergedClassBuilder.removeAbove(Regexp.maxRune(flags));
                mergedAlternatives.add(simplifyCharClass(mode, flags, mergedClassBuilder.toCharClass(), null));
            }
            else {
                mergedAlternatives.add(alternative);
            }
            alternativeIndex = scanIndex;
        }

        return mergedAlternatives;
    }

    private static boolean mergeableAlternateSub(Regexp alternative, int flags, int flagsWithoutFoldCase)
    {
        if (alternative.op() == RegexpOp.LITERAL) {
            return alternative.parseFlags() == flags;
        }
        if (alternative.op() == RegexpOp.CHAR_CLASS) {
            return alternative.parseFlags() == flags || alternative.parseFlags() == flagsWithoutFoldCase;
        }
        return false;
    }

    /**
     * Factors common prefixes out of an alternation in three rounds, as upstream parse.cc does:
     * literal prefixes, then leading regexps, then runs of literals and classes merged into one
     * class. Each prefix found factors its suffixes again, so a long shared prefix nests one
     * factoring inside another per prefix piece. The nested factorings wait on an explicit stack,
     * so their depth is not limited by the thread stack.
     */
    private static List<Regexp> factorAlternationForParse(List<Regexp> subs, int flags, CaseFolding caseFolding)
    {
        List<Alternative> alternatives = new ArrayList<>(subs.size());
        for (Regexp sub : subs) {
            alternatives.add(Alternative.of(sub));
        }
        Deque<FactorTask> waiting = new ArrayDeque<>();
        FactorTask task = new FactorTask(alternatives);
        while (true) {
            FactorTask suffixTask = task.secondRound ? factorAlternationRound2(task) : factorAlternationRound1(task, caseFolding);
            if (suffixTask != null) {
                waiting.push(task);
                task = suffixTask;
                continue;
            }
            if (!task.secondRound) {
                task.startSecondRound();
                continue;
            }
            List<Regexp> factored = factorAlternationRound3(task.factored, flags, caseFolding);
            if (waiting.isEmpty()) {
                return factored;
            }
            task = waiting.pop();
            Regexp suffix = alternateNoFactor(flags, factored);
            task.factored.add(Alternative.of(Regexp.concat(flags, List.of(task.prefix, suffix))));
        }
    }

    // One factoring of an alternation, paused while the suffixes of a prefix it found are factored.
    private static final class FactorTask
    {
        private List<Alternative> alternatives;
        private List<Alternative> factored;
        private boolean secondRound;
        private int index;
        // The prefix whose suffixes are being factored.
        private Regexp prefix;

        private FactorTask(List<Alternative> alternatives)
        {
            this.alternatives = alternatives;
            this.factored = new ArrayList<>(alternatives.size());
        }

        private void startSecondRound()
        {
            alternatives = factored;
            factored = new ArrayList<>(alternatives.size());
            secondRound = true;
            index = 0;
        }
    }

    // Round 1: factor out common literal prefixes. Returns the factoring of the next run's
    // suffixes, or null when the round is done.
    private static FactorTask factorAlternationRound1(FactorTask task, CaseFolding caseFolding)
    {
        List<Alternative> alternatives = task.alternatives;
        while (task.index < alternatives.size()) {
            int startIndex = task.index;
            Alternative alternative = alternatives.get(startIndex);
            LeadingString base = leadingString(alternative);
            if (base == null || base.length() == 0 || requiresFullCaseFold(base, caseFolding)) {
                task.factored.add(alternative);
                task.index++;
                continue;
            }

            // A run continues while the next leading string shares the first rune. Its common
            // prefix is then the longest prefix of base that every member shares. Most
            // alternatives start no run, so the members' leading strings are kept only once the
            // run is known.
            int endIndex = startIndex + 1;
            while (endIndex < alternatives.size() && continuesRun(base, alternatives.get(endIndex), caseFolding)) {
                endIndex++;
            }
            task.index = endIndex;
            if (endIndex == startIndex + 1) {
                task.factored.add(alternative);
                continue;
            }
            List<LeadingString> members = new ArrayList<>(endIndex - startIndex - 1);
            for (int memberIndex = startIndex + 1; memberIndex < endIndex; memberIndex++) {
                members.add(leadingString(alternatives.get(memberIndex)));
            }

            int commonLength = commonPrefixLength(base.runes(), base.start(), base.length(), members);
            int[] prefixRunes = Arrays.copyOfRange(base.runes(), base.start(), base.start() + commonLength);
            task.prefix = (prefixRunes.length == 1)
                    ? Regexp.literal(base.flags(), prefixRunes[0])
                    : Regexp.literalString(base.flags(), prefixRunes);

            List<Alternative> suffixes = new ArrayList<>(endIndex - startIndex);
            for (int suffixIndex = startIndex; suffixIndex < endIndex; suffixIndex++) {
                suffixes.add(removeLeadingString(alternatives.get(suffixIndex), commonLength));
            }
            return new FactorTask(suffixes);
        }
        return null;
    }

    private static boolean continuesRun(LeadingString base, Alternative alternative, CaseFolding caseFolding)
    {
        LeadingString next = leadingString(alternative);
        return next != null && next.flags() == base.flags() && !requiresFullCaseFold(next, caseFolding) &&
                next.length() > 0 && next.rune(0) == base.rune(0);
    }

    // Round 2: factor out common simple leading regexps. Returns the factoring of the next run's
    // suffixes, or null when the round is done.
    private static FactorTask factorAlternationRound2(FactorTask task)
    {
        List<Alternative> alternatives = task.alternatives;
        while (task.index < alternatives.size()) {
            int startIndex = task.index;
            Alternative alternative = alternatives.get(startIndex);
            Regexp firstPrefix = leadingRegexp(alternative);
            if (firstPrefix == null || !isRound2PrefixCandidate(firstPrefix)) {
                task.factored.add(alternative);
                task.index++;
                continue;
            }

            int endIndex = startIndex + 1;
            for (; endIndex < alternatives.size(); endIndex++) {
                Regexp nextPrefix = leadingRegexp(alternatives.get(endIndex));
                if (nextPrefix == null || !Objects.equals(firstPrefix, nextPrefix)) {
                    break;
                }
            }
            task.index = endIndex;
            if (endIndex - startIndex == 1) {
                task.factored.add(alternative);
                continue;
            }

            task.prefix = firstPrefix;
            List<Alternative> suffixes = new ArrayList<>(endIndex - startIndex);
            for (int suffixIndex = startIndex; suffixIndex < endIndex; suffixIndex++) {
                suffixes.add(removeLeadingRegexp(alternatives.get(suffixIndex)));
            }
            return new FactorTask(suffixes);
        }
        return null;
    }

    // Round 3: merge runs of literals and classes into one class. Returns the finished alternatives.
    private static List<Regexp> factorAlternationRound3(List<Alternative> alternatives, int flags, CaseFolding caseFolding)
    {
        ArrayList<Regexp> factoredAlternatives = new ArrayList<>(alternatives.size());
        int alternativeIndex = 0;
        while (alternativeIndex < alternatives.size()) {
            Alternative alternative = alternatives.get(alternativeIndex);
            if (!isRound3CharClassCandidate(alternative, flags, caseFolding)) {
                factoredAlternatives.add(alternative.toRegexp());
                alternativeIndex++;
                continue;
            }

            int startIndex = alternativeIndex;
            int endIndex = alternativeIndex + 1;
            while (endIndex < alternatives.size()) {
                Alternative scanAlternative = alternatives.get(endIndex);
                if (isRound3CharClassCandidate(scanAlternative, flags, caseFolding)) {
                    endIndex++;
                }
                else {
                    break;
                }
            }

            if ((endIndex - startIndex) > 1) {
                CharClass charClass = mergeAlternativesIntoCharClass(alternatives, startIndex, endIndex, caseFolding);
                factoredAlternatives.add(Regexp.charClass(flags & ~Regexp.FOLD_CASE, charClass));
            }
            else {
                factoredAlternatives.add(alternative.regexp());
            }
            alternativeIndex = endIndex;
        }
        return factoredAlternatives;
    }

    // A remainder of a literal string or concatenation is neither a literal nor a class.
    private static boolean isRound3CharClassCandidate(Alternative alternative, int flags, CaseFolding caseFolding)
    {
        return alternative.isWhole() && isRound3CharClassCandidate(alternative.regexp(), flags, caseFolding);
    }

    private static boolean isRound3CharClassCandidate(Regexp regexp, int flags, CaseFolding caseFolding)
    {
        return (regexp.op() == RegexpOp.LITERAL || regexp.op() == RegexpOp.CHAR_CLASS) &&
                ((regexp.parseFlags() ^ flags) & Regexp.LATIN1) == 0 &&
                (regexp.op() != RegexpOp.LITERAL || (regexp.parseFlags() & Regexp.FULL_CASE_FOLD) == 0 ||
                        !caseFolding.requiresFullCaseFold(new int[] {regexp.rune()}));
    }

    private static boolean requiresFullCaseFold(LeadingString leadingString, CaseFolding caseFolding)
    {
        // A remainder of a leading string that was checked when its prefix was removed: the fold
        // of a suffix is a suffix of the fold, so it cannot contain a multi-character token
        // either.
        if (leadingString.start() > 0) {
            return false;
        }
        return requiresFullCaseFold(leadingString.flags(), leadingString.runes(), caseFolding);
    }

    private static boolean requiresFullCaseFold(int flags, int[] runes, CaseFolding caseFolding)
    {
        // Full folds can consume multiple code points, including across a new factored
        // prefix boundary. Keep those literals intact; simple folding still factors.
        return (flags & Regexp.FULL_CASE_FOLD) != 0 && caseFolding.requiresFullCaseFold(runes);
    }

    private static CharClass mergeAlternativesIntoCharClass(List<Alternative> alternatives, int startIndex, int endIndex, CaseFolding caseFolding)
    {
        CharClassBuilder builder = new CharClassBuilder();
        for (int mergeIndex = startIndex; mergeIndex < endIndex; mergeIndex++) {
            Regexp mergeAlternative = alternatives.get(mergeIndex).regexp();
            if (mergeAlternative.op() == RegexpOp.CHAR_CLASS) {
                CharClass charClass = mergeAlternative.charClass();
                for (RuneRange runeRange : charClass.ranges()) {
                    builder.addRangeFlags(runeRange.low(), runeRange.high(), mergeAlternative.parseFlags(), caseFolding);
                }
            }
            else {
                addLiteral(builder, mergeAlternative, caseFolding);
            }
        }
        return builder.toCharClass();
    }

    // The compiler matches a literal folded without full case folding, the form [Kk] simplifies
    // to, as its ASCII letter pair, so it merges as that pair rather than its whole orbit.
    private static void addLiteral(CharClassBuilder builder, Regexp literal, CaseFolding caseFolding)
    {
        int literalFlags = literal.parseFlags();
        if ((literalFlags & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) == Regexp.FOLD_CASE) {
            literalFlags = (literalFlags & ~Regexp.FOLD_CASE) | Regexp.ASCII_FOLD_CASE;
        }
        builder.addRangeFlags(literal.rune(), literal.rune(), literalFlags, caseFolding);
    }

    private static Regexp alternateNoFactor(int flags, List<Regexp> alternatives)
    {
        if (alternatives.isEmpty()) {
            return Regexp.noMatch(flags);
        }
        if (alternatives.size() == 1) {
            return alternatives.getFirst();
        }
        return Regexp.alternate(flags, alternatives);
    }

    private static boolean isRound2PrefixCandidate(Regexp regexp)
    {
        return switch (regexp.op()) {
            case BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT, CHAR_CLASS, ANY_CHAR, ANY_BYTE -> true;
            case REPEAT -> regexp.min() == regexp.max() &&
                    (regexp.child(0).op() == RegexpOp.LITERAL ||
                            regexp.child(0).op() == RegexpOp.CHAR_CLASS ||
                            regexp.child(0).op() == RegexpOp.ANY_CHAR ||
                            regexp.child(0).op() == RegexpOp.ANY_BYTE);
            default -> false;
        };
    }

    /**
     * An alternative during factoring: a whole node, or what remains of one after factoring
     * removed a leading string or regexp. Removing a prefix makes a view instead of copying the
     * remaining runes or children, so removing prefix after prefix from one long alternative costs
     * time linear in its length. A view becomes a node once, when factoring is done with it.
     * <p>
     * A view of a literal string starts at rune {@code start} and keeps at least two runes. A
     * view of a concatenation starts at child {@code start} and keeps at least two children; a
     * non-null {@code head} replaces that child when part of its leading string was removed.
     * Smaller remainders are whole nodes.
     */
    private record Alternative(Regexp regexp, int start, Alternative head)
    {
        static Alternative of(Regexp regexp)
        {
            return new Alternative(regexp, 0, null);
        }

        boolean isWhole()
        {
            return start == 0 && head == null;
        }

        // The remaining children without their first, or the lone remaining child.
        Alternative withoutFirstChild()
        {
            if (regexp.childCount() - start == 2) {
                return of(regexp.child(start + 1));
            }
            return new Alternative(regexp, start + 1, null);
        }

        Regexp toRegexp()
        {
            if (isWhole()) {
                return regexp;
            }
            // Build the innermost replaced head first, so a chain of heads needs no recursion.
            List<Alternative> enclosing = new ArrayList<>();
            Alternative cursor = this;
            while (cursor.head != null) {
                enclosing.add(cursor);
                cursor = cursor.head;
            }
            Regexp result;
            if (cursor.start == 0) {
                result = cursor.regexp;
            }
            else if (cursor.regexp.op() == RegexpOp.LITERAL_STRING) {
                int[] runes = cursor.regexp.runes();
                result = Regexp.literalString(cursor.regexp.parseFlags(), Arrays.copyOfRange(runes, cursor.start, runes.length));
            }
            else {
                result = Regexp.concat(cursor.regexp.parseFlags(), cursor.regexp.children().subList(cursor.start, cursor.regexp.childCount()));
            }
            for (int index = enclosing.size() - 1; index >= 0; index--) {
                Alternative concat = enclosing.get(index);
                List<Regexp> children = new ArrayList<>(concat.regexp.childCount() - concat.start);
                children.add(result);
                children.addAll(concat.regexp.children().subList(concat.start + 1, concat.regexp.childCount()));
                result = Regexp.concat(concat.regexp.parseFlags(), children);
            }
            return result;
        }
    }

    // The leading literal runes of an alternative: runes[start, start + length).
    private record LeadingString(int[] runes, int start, int length, int flags)
    {
        int rune(int index)
        {
            return runes[start + index];
        }
    }

    private static LeadingString leadingString(Alternative alternative)
    {
        Alternative cursor = alternative;
        while (cursor.regexp().op() == RegexpOp.CONCAT && cursor.regexp().childCount() > 0) {
            cursor = (cursor.head() != null) ? cursor.head() : Alternative.of(cursor.regexp().child(cursor.start()));
        }
        Regexp regexp = cursor.regexp();
        int runeFlags = regexp.parseFlags() & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD | Regexp.LATIN1);
        if (regexp.op() == RegexpOp.LITERAL) {
            return new LeadingString(new int[] {regexp.rune()}, 0, 1, runeFlags);
        }
        if (regexp.op() == RegexpOp.LITERAL_STRING) {
            int[] runes = regexp.runes();
            return new LeadingString(runes, cursor.start(), runes.length - cursor.start(), runeFlags);
        }
        return null;
    }

    // Every member shares the first rune of the base string, runes[start, start + length).
    // Comparing all members one position at a time stops at the first position any of them
    // differs, so the cost is the runes the prefix removes from each member plus one comparison
    // each, and runes already removed are never compared again by the suffixes' factoring. The
    // base string comes as its parts because passing the record measured more allocation on
    // keyword lists.
    private static int commonPrefixLength(int[] runes, int start, int length, List<LeadingString> members)
    {
        int commonLength = 1;
        while (commonLength < length) {
            int rune = runes[start + commonLength];
            for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
                LeadingString member = members.get(memberIndex);
                if (commonLength >= member.length() || member.rune(commonLength) != rune) {
                    return commonLength;
                }
            }
            commonLength++;
        }
        return commonLength;
    }

    private static Regexp leadingRegexp(Alternative alternative)
    {
        Regexp regexp = alternative.regexp();
        if (!alternative.isWhole()) {
            // A replaced first child that is a whole node leads, as it does in the concatenation
            // the view stands for. A remainder of a literal string or of a leading string is never
            // a round 2 prefix, and no prefix equals one, so it is treated as having none.
            if (alternative.head() != null) {
                return alternative.head().isWhole() ? alternative.head().regexp() : null;
            }
            if (regexp.op() != RegexpOp.CONCAT) {
                return null;
            }
            Regexp first = regexp.child(alternative.start());
            return (first.op() == RegexpOp.EMPTY_MATCH) ? null : first;
        }
        if (regexp.op() == RegexpOp.EMPTY_MATCH) {
            return null;
        }
        if (regexp.op() == RegexpOp.CONCAT && regexp.childCount() >= 2) {
            Regexp first = regexp.child(0);
            if (first.op() == RegexpOp.EMPTY_MATCH) {
                return null;
            }
            return first;
        }
        return regexp;
    }

    // Removes the prefix that leadingRegexp returned.
    private static Alternative removeLeadingRegexp(Alternative alternative)
    {
        Regexp regexp = alternative.regexp();
        if (regexp.op() == RegexpOp.EMPTY_MATCH) {
            return alternative;
        }
        if (regexp.op() == RegexpOp.CONCAT && regexp.childCount() >= 2) {
            Regexp first = (alternative.head() != null) ? alternative.head().regexp() : regexp.child(alternative.start());
            if (first.op() == RegexpOp.EMPTY_MATCH) {
                return alternative;
            }
            return alternative.withoutFirstChild();
        }
        return Alternative.of(Regexp.emptyMatch(regexp.parseFlags()));
    }

    // Removes prefixLength runes from the leading string that leadingString returned.
    private static Alternative removeLeadingString(Alternative alternative, int prefixLength)
    {
        if (prefixLength <= 0) {
            return alternative;
        }

        // Descend through leading concatenations to the literal, then replace each
        // concatenation's first child on the way back up.
        List<Alternative> enclosing = new ArrayList<>();
        Alternative cursor = alternative;
        while (cursor.regexp().op() == RegexpOp.CONCAT) {
            enclosing.add(cursor);
            cursor = (cursor.head() != null) ? cursor.head() : Alternative.of(cursor.regexp().child(cursor.start()));
        }

        Regexp literal = cursor.regexp();
        Alternative result = switch (literal.op()) {
            case LITERAL -> Alternative.of(Regexp.emptyMatch(literal.parseFlags()));
            case LITERAL_STRING -> {
                int[] runes = literal.runes();
                int remaining = runes.length - cursor.start();
                if (prefixLength >= remaining) {
                    yield Alternative.of(Regexp.emptyMatch(literal.parseFlags()));
                }
                if (prefixLength == remaining - 1) {
                    yield Alternative.of(Regexp.literal(literal.parseFlags(), runes[runes.length - 1]));
                }
                yield new Alternative(literal, cursor.start() + prefixLength, null);
            }
            default -> cursor;
        };

        for (int index = enclosing.size() - 1; index >= 0; index--) {
            Alternative concat = enclosing.get(index);
            result = (result.regexp().op() == RegexpOp.EMPTY_MATCH)
                    ? concat.withoutFirstChild()
                    : new Alternative(concat.regexp(), concat.start(), result);
        }
        return result;
    }

    static final class Coalescer
    {
        private Coalescer() {}

        public static Regexp coalesce(Regexp regexp, CaseFolding caseFolding)
        {
            // Without an adjacent coalescible pair in any concatenation, every node's children are
            // unchanged, so the walk would return the input without rebuilding anything.
            if (!mayCoalesce(regexp, caseFolding, 0)) {
                return regexp;
            }
            return new CoalesceWalker(caseFolding).walk(regexp, null);
        }

        private static boolean mayCoalesce(Regexp regexp, CaseFolding caseFolding, int depth)
        {
            // The scan recurses only as deep as simplification does, so it stays within the small
            // thread stacks that compilation supports. Deeper trees are handed to the iterative walk.
            if (depth == MAX_RECURSIVE_DEPTH) {
                return true;
            }
            int childCount = regexp.childCount();
            for (int childIndex = 0; childIndex < childCount; childIndex++) {
                Regexp child = regexp.child(childIndex);
                if (regexp.op() == RegexpOp.CONCAT &&
                        childIndex + 1 < childCount &&
                        CoalesceWalker.canCoalesce(child, regexp.child(childIndex + 1), caseFolding)) {
                    return true;
                }
                if (mayCoalesce(child, caseFolding, depth + 1)) {
                    return true;
                }
            }
            return false;
        }

        private static final class CoalesceWalker
                extends RegexpWalker<Regexp>
        {
            private final CaseFolding caseFolding;

            private CoalesceWalker(CaseFolding caseFolding)
            {
                this.caseFolding = caseFolding;
            }

            @Override
            protected PreVisitResult<Regexp> preVisit(Regexp regexp, Regexp parentValue)
            {
                return new PreVisitResult<>(regexp, false);
            }

            @Override
            protected Regexp shortVisit(Regexp regexp, Regexp parentValue)
            {
                throw new IllegalStateException("Coalescer should not short-visit");
            }

            @Override
            protected Regexp postVisit(Regexp regexp, Regexp parentValue, Regexp preValue, List<Regexp> childValues)
            {
                if (regexp.childCount() == 0) {
                    return regexp;
                }

                if (regexp.op() != RegexpOp.CONCAT) {
                    if (!childArgsChanged(regexp, childValues)) {
                        return regexp;
                    }
                    return rebuildNonConcat(regexp, childValues);
                }

                boolean foundCoalescingPair = false;
                for (int pairIndex = 0; pairIndex + 1 < childValues.size(); pairIndex++) {
                    if (canCoalesce(childValues.get(pairIndex), childValues.get(pairIndex + 1), caseFolding)) {
                        foundCoalescingPair = true;
                        break;
                    }
                }

                if (!foundCoalescingPair) {
                    if (!childArgsChanged(regexp, childValues)) {
                        return regexp;
                    }
                    return rebuildConcat(regexp, childValues);
                }

                // Perform a single left-to-right coalescing sweep while emitting only live nodes.
                ArrayList<Regexp> coalescedChildren = new ArrayList<>(childValues.size());
                Regexp current = childValues.getFirst();
                for (int childIndex = 1; childIndex < childValues.size(); childIndex++) {
                    Regexp next = childValues.get(childIndex);
                    if (canCoalesce(current, next, caseFolding)) {
                        current = coalescePair(current, next, coalescedChildren);
                    }
                    else {
                        if (current.op() != RegexpOp.EMPTY_MATCH) {
                            coalescedChildren.add(current);
                        }
                        current = next;
                    }
                }
                if (current.op() != RegexpOp.EMPTY_MATCH) {
                    coalescedChildren.add(current);
                }

                if (coalescedChildren.isEmpty()) {
                    return Regexp.emptyMatch(regexp.parseFlags());
                }
                if (coalescedChildren.size() == 1) {
                    return coalescedChildren.getFirst();
                }
                return Regexp.concat(regexp.parseFlags(), coalescedChildren);
            }

            private static boolean childArgsChanged(Regexp regexp, List<Regexp> childValues)
            {
                for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
                    if (regexp.child(childIndex) != childValues.get(childIndex)) {
                        return true;
                    }
                }
                return false;
            }

            private static Regexp rebuildNonConcat(Regexp regexp, List<Regexp> childValues)
            {
                return switch (regexp.op()) {
                    case ALTERNATE -> {
                        if (childValues.size() == 1) {
                            yield childValues.getFirst();
                        }
                        yield Regexp.alternate(regexp.parseFlags(), childValues);
                    }
                    case CAPTURE -> Regexp.capture(regexp.parseFlags(), childValues.getFirst(), regexp.captureIndex(), regexp.name());
                    case STAR -> Regexp.rawUnary(RegexpOp.STAR, regexp.parseFlags(), childValues.getFirst());
                    case PLUS -> Regexp.rawUnary(RegexpOp.PLUS, regexp.parseFlags(), childValues.getFirst());
                    case QUEST -> Regexp.rawUnary(RegexpOp.QUEST, regexp.parseFlags(), childValues.getFirst());
                    case REPEAT -> Regexp.repeat(regexp.parseFlags(), childValues.getFirst(), regexp.min(), regexp.max());
                    case CONCAT -> throw new IllegalStateException("handled elsewhere");
                    default -> throw new IllegalStateException("unhandled op: " + regexp.op());
                };
            }

            private static Regexp rebuildConcat(Regexp regexp, List<Regexp> childValues)
            {
                if (childValues.isEmpty()) {
                    return Regexp.emptyMatch(regexp.parseFlags());
                }
                if (childValues.size() == 1) {
                    return childValues.getFirst();
                }
                return Regexp.concat(regexp.parseFlags(), childValues);
            }

            private static boolean canCoalesce(Regexp repeatedExpression, Regexp followingExpression, CaseFolding caseFolding)
            {
                // The left expression must be repetition over a literal/class/any-char/any-byte atom.
                if ((repeatedExpression.op() == RegexpOp.STAR || repeatedExpression.op() == RegexpOp.PLUS ||
                        repeatedExpression.op() == RegexpOp.QUEST || repeatedExpression.op() == RegexpOp.REPEAT)) {
                    Regexp repeatedAtom = repeatedExpression.child(0);
                    if (repeatedAtom.op() != RegexpOp.LITERAL && repeatedAtom.op() != RegexpOp.CHAR_CLASS &&
                            repeatedAtom.op() != RegexpOp.ANY_CHAR && repeatedAtom.op() != RegexpOp.ANY_BYTE) {
                        return false;
                    }

                    // ... followed by repetition over the same atom with matching greediness.
                    if ((followingExpression.op() == RegexpOp.STAR || followingExpression.op() == RegexpOp.PLUS ||
                            followingExpression.op() == RegexpOp.QUEST || followingExpression.op() == RegexpOp.REPEAT) &&
                            Objects.equals(repeatedAtom, followingExpression.child(0)) &&
                            ((repeatedExpression.parseFlags() & Regexp.NON_GREEDY) == (followingExpression.parseFlags() & Regexp.NON_GREEDY))) {
                        return true;
                    }

                    // ... or one direct occurrence of the same atom.
                    if (Objects.equals(repeatedAtom, followingExpression)) {
                        return true;
                    }

                    // ... or a literal string with that literal as its first rune.
                    if (repeatedAtom.op() == RegexpOp.LITERAL &&
                            followingExpression.op() == RegexpOp.LITERAL_STRING &&
                            followingExpression.runes().length > 0 &&
                            followingExpression.runes()[0] == repeatedAtom.rune() &&
                            repeatedAtom.parseFlags() == followingExpression.parseFlags() &&
                            !requiresFullCaseFold(followingExpression.parseFlags(), followingExpression.runes(), caseFolding)) {
                        return true;
                    }
                }
                return false;
            }

            private static Regexp coalescePair(Regexp repeatedExpression, Regexp followingExpression, ArrayList<Regexp> output)
            {
                Regexp repeatedAtom = repeatedExpression.child(0);

                int minRepetitions;
                int maxRepetitions;
                switch (repeatedExpression.op()) {
                    case STAR -> {
                        minRepetitions = 0;
                        maxRepetitions = -1;
                    }
                    case PLUS -> {
                        minRepetitions = 1;
                        maxRepetitions = -1;
                    }
                    case QUEST -> {
                        minRepetitions = 0;
                        maxRepetitions = 1;
                    }
                    case REPEAT -> {
                        minRepetitions = repeatedExpression.min();
                        maxRepetitions = repeatedExpression.max();
                    }
                    default -> throw new IllegalStateException("unexpected repeatedExpression op: " + repeatedExpression.op());
                }

                if (followingExpression.op() == RegexpOp.STAR) {
                    return Regexp.repeat(repeatedExpression.parseFlags(), repeatedAtom, minRepetitions, -1);
                }
                if (followingExpression.op() == RegexpOp.PLUS) {
                    minRepetitions++;
                    return Regexp.repeat(repeatedExpression.parseFlags(), repeatedAtom, minRepetitions, -1);
                }
                if (followingExpression.op() == RegexpOp.QUEST) {
                    if (maxRepetitions != -1) {
                        maxRepetitions++;
                    }
                    return Regexp.repeat(repeatedExpression.parseFlags(), repeatedAtom, minRepetitions, maxRepetitions);
                }
                if (followingExpression.op() == RegexpOp.REPEAT) {
                    minRepetitions += followingExpression.min();
                    if (followingExpression.max() == -1) {
                        maxRepetitions = -1;
                    }
                    else if (maxRepetitions != -1) {
                        maxRepetitions += followingExpression.max();
                    }
                    return Regexp.repeat(repeatedExpression.parseFlags(), repeatedAtom, minRepetitions, maxRepetitions);
                }
                if (followingExpression.op() == RegexpOp.LITERAL || followingExpression.op() == RegexpOp.CHAR_CLASS ||
                        followingExpression.op() == RegexpOp.ANY_CHAR || followingExpression.op() == RegexpOp.ANY_BYTE) {
                    minRepetitions++;
                    if (maxRepetitions != -1) {
                        maxRepetitions++;
                    }
                    return Regexp.repeat(repeatedExpression.parseFlags(), repeatedAtom, minRepetitions, maxRepetitions);
                }

                if (followingExpression.op() == RegexpOp.LITERAL_STRING) {
                    int repeatedRune = repeatedAtom.rune();
                    int[] literalStringRunes = followingExpression.runes();
                    int repeatedPrefixLength = 1;
                    while (repeatedPrefixLength < literalStringRunes.length && literalStringRunes[repeatedPrefixLength] == repeatedRune) {
                        repeatedPrefixLength++;
                    }
                    minRepetitions += repeatedPrefixLength;
                    if (maxRepetitions != -1) {
                        maxRepetitions += repeatedPrefixLength;
                    }

                    Regexp coalescedRepeat = Regexp.repeat(repeatedExpression.parseFlags(), repeatedAtom, minRepetitions, maxRepetitions);
                    if (repeatedPrefixLength == literalStringRunes.length) {
                        return coalescedRepeat;
                    }

                    int[] literalStringTail = Arrays.copyOfRange(literalStringRunes, repeatedPrefixLength, literalStringRunes.length);
                    output.add(coalescedRepeat);
                    return Regexp.literalString(followingExpression.parseFlags(), literalStringTail);
                }

                throw new IllegalStateException("unexpected followingExpression op: " + followingExpression.op());
            }
        }
    }
}
