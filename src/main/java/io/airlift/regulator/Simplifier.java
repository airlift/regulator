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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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

    /// Parse-time canonicalization that intentionally preserves counted repetitions (`{n,m}`),
    /// matching upstream RE2 parse.cc behavior (parse dumps still contain `rep{...}` nodes).
    ///
    /// Compiler pipelines must call [#simplify(Regexp,CaseFolding)] (FULL) to eliminate counted repeats.
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
            return toRegexp(new SimplifyWalker(mode, caseFolding).walk(regexp, null));
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
            case ALTERNATE -> (mode == Mode.PARSE)
                    ? toRegexp(simplifyAlternativeForParse(regexp, caseFolding, depth, false))
                    : simplifyAlternateRecursive(caseFolding, flags, regexp, depth);
            case CHAR_CLASS -> simplifyCharClass(mode, flags, regexp.charClass(), regexp);
        };
    }

    // Simplifies an alternative of a parse-mode alternation. The result may be pending (see
    // PendingAlternation): an alternation leaves its factored alternatives unbuilt for the
    // enclosing alternation, and so does a concatenation whose one pending child has only empty
    // matches around it, directly or through nested concatenations.
    private static Object simplifyAlternativeForParse(Regexp regexp, CaseFolding caseFolding, int depth, boolean pending)
    {
        if (depth == MAX_RECURSIVE_DEPTH) {
            return new SimplifyWalker(Mode.PARSE, caseFolding).walk(regexp, pending ? SimplifyWalker.PENDING_PARENT : null);
        }
        int flags = regexp.parseFlags();
        if (regexp.op() == RegexpOp.ALTERNATE) {
            List<Object> children = new ArrayList<>(regexp.childCount());
            for (Regexp child : regexp.children()) {
                children.add(simplifyAlternativeForParse(child, caseFolding, depth + 1, true));
            }
            return simplifyAlternateForParse(caseFolding, flags, children, pending);
        }
        if (regexp.op() == RegexpOp.CONCAT) {
            PendingConcat concat = new PendingConcat(caseFolding);
            if (!addConcatChildrenForParse(concat, regexp, caseFolding, depth)) {
                return Regexp.noMatch(flags);
            }
            return concat.build(flags, regexp);
        }
        return simplifyRecursive(regexp, Mode.PARSE, caseFolding, depth + 1);
    }

    // Adds the simplified children of a concatenation in an alternative, as
    // addConcatChildrenRecursive does, except that alternations and concatenations may stay
    // pending.
    private static boolean addConcatChildrenForParse(PendingConcat concat, Regexp regexp, CaseFolding caseFolding, int depth)
    {
        for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
            Regexp child = regexp.child(childIndex);
            if (child.op() == RegexpOp.CONCAT && depth + 1 < MAX_RECURSIVE_DEPTH) {
                concat.builder.beginNested();
                if (!addConcatChildrenForParse(concat, child, caseFolding, depth + 1)) {
                    return false;
                }
                concat.builder.endNested();
            }
            else if (!concat.add((child.op() == RegexpOp.ALTERNATE || child.op() == RegexpOp.CONCAT)
                    ? simplifyAlternativeForParse(child, caseFolding, depth + 1, true)
                    : simplifyRecursive(child, Mode.PARSE, caseFolding, depth + 1))) {
                return false;
            }
        }
        return true;
    }

    // A concatenation in an alternative, built from simplified children that may be pending.
    // While every child added so far is an empty match except one pending value, that value stays
    // unbuilt, so a concatenation of it and empty matches stays pending as concatOfPending
    // describes. Only empty matches come before it, so the builder adds it later, before the next
    // child that is not an empty match, with the same result as in its place.
    private static final class PendingConcat
    {
        private final ConcatBuilder builder;
        private Object pending;
        private boolean builderStarted;

        private PendingConcat(CaseFolding caseFolding)
        {
            this.builder = new ConcatBuilder(caseFolding);
        }

        // Returns false when the child never matches.
        boolean add(Object child)
        {
            if (child instanceof Regexp regexp && regexp.op() == RegexpOp.EMPTY_MATCH) {
                return true;
            }
            if (!builderStarted && pending == null && !(child instanceof Regexp)) {
                pending = child;
                return true;
            }
            builderStarted = true;
            if (pending != null && !builder.add(toRegexp(pending))) {
                return false;
            }
            pending = null;
            return builder.add(toRegexp(child));
        }

        Object build(int flags, Regexp original)
        {
            return (pending != null) ? concatOfPending(flags, pending) : builder.build(flags, original);
        }
    }

    // Values are simplified regexps, except that a concatenation directly inside another one
    // yields a NestedConcat, which the enclosing concatenation adds in place, and that parse-mode
    // alternations and their alternatives may yield pending values (see PendingAlternation).
    private static final class SimplifyWalker
            extends RegexpWalker<Object>
    {
        // The parent value of a root whose caller takes pending alternations.
        private static final Object PENDING_PARENT = new Object();
        // The lone value of a concatenation of empty matches.
        private static final Object EMPTY = new Object();

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
                    Regexp child = toRegexp(simplifiedChildren.getFirst());
                    yield child == regexp.child(0) ? regexp : Regexp.capture(flags, child, regexp.captureIndex(), regexp.name());
                }
                case STAR -> simplifyUnary(flags, toRegexp(simplifiedChildren.getFirst()), UnaryOp.STAR, regexp);
                case PLUS -> simplifyUnary(flags, toRegexp(simplifiedChildren.getFirst()), UnaryOp.PLUS, regexp);
                case QUEST -> simplifyUnary(flags, toRegexp(simplifiedChildren.getFirst()), UnaryOp.QUEST, regexp);
                case REPEAT -> simplifyRepeat(mode, flags, regexp, toRegexp(simplifiedChildren.getFirst()));
                case CONCAT -> {
                    Object lone = loneValue(simplifiedChildren);
                    if (parentValue instanceof Regexp parent && parent.op() == RegexpOp.CONCAT) {
                        yield new NestedConcat(simplifiedChildren, lone);
                    }
                    if (lone != null && lone != EMPTY) {
                        yield concatOfPending(flags, lone);
                    }
                    yield simplifyConcat(flags, regexp, simplifiedChildren, caseFolding);
                }
                // Only a concatenation's children can be nested concatenations, and full mode never
                // produces pending values, so the cast is safe.
                case ALTERNATE -> (mode == Mode.PARSE)
                        ? simplifyAlternateForParse(caseFolding, flags, simplifiedChildren, takesPending(parentValue))
                        : simplifyAlternate(caseFolding, flags, (List<Regexp>) (List<?>) simplifiedChildren);
                case CHAR_CLASS -> simplifyCharClass(mode, flags, regexp.charClass(), regexp);
            };
        }

        // An alternation stays pending where an enclosing alternation may take it over, directly
        // or through a concatenation that passes it through.
        private static boolean takesPending(Object parentValue)
        {
            return parentValue == PENDING_PARENT ||
                    (parentValue instanceof Regexp parent && (parent.op() == RegexpOp.ALTERNATE || parent.op() == RegexpOp.CONCAT));
        }

        // Returns EMPTY when every child is an empty match, the lone pending value when every
        // other child is an empty match, and otherwise null. A nested concatenation counts as its
        // own lone value.
        private static Object loneValue(List<Object> simplifiedChildren)
        {
            Object lone = EMPTY;
            for (Object child : simplifiedChildren) {
                Object value = switch (child) {
                    case Regexp regexp -> (regexp.op() == RegexpOp.EMPTY_MATCH) ? EMPTY : null;
                    case NestedConcat nested -> nested.lone();
                    default -> child;
                };
                if (value == null || (value != EMPTY && lone != EMPTY)) {
                    return null;
                }
                if (value != EMPTY) {
                    lone = value;
                }
            }
            return lone;
        }
    }

    // The simplified children of a concatenation inside another one, not yet added to it, and
    // their lone value (see SimplifyWalker.loneValue).
    private record NestedConcat(List<Object> simplifiedChildren, Object lone) {}

    private enum UnaryOp
    {
        STAR, PLUS, QUEST
    }

    /// Simplifies a unary operator over an already simplified child. Returns `original`, a node
    /// with this operator and these flags, when rebuilding would produce an equal node.
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
            else if (!builder.add(toRegexp(child))) {
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

    /// Builds a simplified concatenation from simplified children. Empty matches are dropped, a
    /// child concatenation contributes its children, and adjacent literals with equal flags merge
    /// into one literal string, so `(?:ab)(?:cd)` becomes `str{abcd}` as in upstream
    /// parse_test.cc.
    ///
    /// The Trino parser builds each run of fully folded literals, which full case folding matches
    /// as one sequence. Two fully folded runs stay apart whenever their joined text contains a
    /// multi-character fold, including one entirely within either run. Joining the copies of
    /// `(?i)s{2}` would match `ß`.
    ///
    /// Merging literals one at a time would recopy the growing string for each literal. Instead the
    /// open run of literals collects its runes in one buffer and becomes a node once it closes.
    ///
    /// A nested concatenation's children are added between [#beginNested()] and
    /// [#endNested()], with the same result as simplifying the nested concatenation and then
    /// adding it: its first child does not merge with an earlier literal, but a lone remaining child
    /// merges as any child does, and a later literal can merge with its last child.
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

    private static Regexp simplifyAlternate(CaseFolding caseFolding, int flags, List<Regexp> children)
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

        return simplifyAlternatives(caseFolding, flags, simplifiedAlternatives);
    }

    private static Regexp simplifyAlternatives(CaseFolding caseFolding, int flags, List<Regexp> simplifiedAlternatives)
    {
        if (simplifiedAlternatives.isEmpty()) {
            return Regexp.noMatch(flags);
        }
        if (simplifiedAlternatives.size() == 1) {
            return simplifiedAlternatives.getFirst();
        }

        List<Regexp> merged = mergeAlternateCharClassesWherePossible(caseFolding, flags, simplifiedAlternatives);
        if (merged.size() == 1) {
            return merged.getFirst();
        }

        return Regexp.alternate(flags, merged);
    }

    private static Regexp simplifyAlternateRecursive(CaseFolding caseFolding, int flags, Regexp regexp, int depth)
    {
        List<Regexp> simplifiedAlternatives = new ArrayList<>();
        for (Regexp child : regexp.children()) {
            Regexp simplifiedChild = simplifyRecursive(child, Mode.FULL, caseFolding, depth + 1);
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

        return simplifyAlternatives(caseFolding, flags, simplifiedAlternatives);
    }

    /// Simplifies a parse-mode alternation: flattens child alternations, collapses scalar
    /// alternatives into a character or byte wildcard among them, and factors the rest. When
    /// pending, the factored alternatives stay pending (see PendingAlternation), so an enclosing
    /// alternation takes them over without copying them.
    private static Object simplifyAlternateForParse(CaseFolding caseFolding, int flags, List<Object> children, boolean pending)
    {
        // Each part is one alternative, or a child alternation's pending alternatives.
        List<Object> parts = new ArrayList<>(children.size());
        int count = 0;
        PendingAlternation largest = null;
        for (Object child : children) {
            switch (child) {
                case Regexp regexp when regexp.op() == RegexpOp.NO_MATCH -> {}
                case Regexp regexp when regexp.op() == RegexpOp.ALTERNATE -> {
                    for (Regexp alternative : regexp.children()) {
                        parts.add(Alternative.of(alternative));
                    }
                    count += regexp.childCount();
                }
                case Regexp regexp -> {
                    parts.add(Alternative.of(regexp));
                    count++;
                }
                case PendingAlternation alternation when ((alternation.flags ^ flags) & Regexp.LATIN1) != 0 -> {
                    // No parse changes LATIN1 between levels, but a list factored under the other
                    // LATIN1 setting had other round 3 members, so its alternatives are factored
                    // again in full.
                    alternation.addAlternativesTo(parts);
                    count += alternation.size;
                }
                case PendingAlternation alternation -> {
                    parts.add(alternation);
                    count += alternation.size;
                    if (largest == null || alternation.size > largest.size) {
                        largest = alternation;
                    }
                }
                case PendingClass _, PendingProduct _ -> {
                    parts.add(child);
                    count++;
                }
                default -> throw new IllegalArgumentException("not a simplified alternative: " + child);
            }
        }
        if (count == 0) {
            return Regexp.noMatch(flags);
        }
        if (count == 1) {
            return value((Factorable) parts.getFirst());
        }

        // Like RE2's parser, only discard capture-free scalar alternatives. A byte wildcard is not
        // a Unicode scalar wildcard, and a full fold can consume several scalars even when its
        // source is one literal. The direct alternatives are counted until one rules the collapse
        // out. The collapse and a pending result also need the child lists' counts: the other
        // lists' counts are added into the largest list's summary, which the result reuses.
        CollapseSummary directSummary = new CollapseSummary();
        boolean mayCollapse = true;
        for (Object part : parts) {
            if (!(part instanceof PendingAlternation)) {
                directSummary.add((Factorable) part, caseFolding, 1);
                mayCollapse = directSummary.mayCollapse(flags);
                if (!mayCollapse) {
                    break;
                }
            }
        }
        CollapseSummary listSummary = null;
        if (mayCollapse || pending) {
            listSummary = (largest != null) ? largest.summary(caseFolding) : new CollapseSummary();
            for (Object part : parts) {
                if (part instanceof PendingAlternation alternation && alternation != largest) {
                    listSummary.addAll(alternation.summary(caseFolding));
                }
            }
        }
        if (mayCollapse) {
            Regexp collapsed = CollapseSummary.collapse(flags, listSummary, directSummary);
            if (collapsed != null) {
                return collapsed;
            }
        }

        List<Factorable> window;
        int untouched = 0;
        if (largest == null) {
            // With no child list to take over, every part is an alternative, so the parts are the
            // window.
            @SuppressWarnings("unchecked")
            List<Factorable> alternatives = (List<Factorable>) (List<?>) parts;
            window = alternatives;
        }
        else {
            window = new ArrayList<>();
            for (Object part : parts) {
                if (part instanceof PendingAlternation alternation) {
                    untouched += alternation.size - alternation.addChangeableTo(window, pending ? listSummary : null, caseFolding);
                }
                else {
                    window.add((Factorable) part);
                }
            }
        }
        List<Integer> mergedIndexes = new ArrayList<>();
        List<Factorable> factored = factorAlternationForParse(window, flags, caseFolding, mergedIndexes);
        int size = untouched;
        for (Factorable alternative : factored) {
            if (!(alternative instanceof Gap)) {
                size++;
            }
        }
        if (size == 1) {
            return value(factored.getFirst());
        }
        if (!pending) {
            List<Regexp> alternatives = new ArrayList<>(size);
            for (Factorable alternative : factored) {
                if (alternative instanceof Gap gap) {
                    for (Node node = gap.first(); ; node = node.next) {
                        alternatives.add(build(node.alternative));
                        if (node == gap.last()) {
                            break;
                        }
                    }
                }
                else {
                    alternatives.add(build(alternative));
                }
            }
            return Regexp.alternate(flags, alternatives);
        }
        // The list's counts are the untouched alternatives' until an enclosing alternation adds
        // the factored ones.
        return PendingAlternation.splice(flags, factored, mergedIndexes, size, listSummary);
    }

    /// The factored alternatives of a parse-mode alternation, kept as a linked list rather than
    /// built into a Regexp node, so that an enclosing alternation can take them over.
    ///
    /// An alternation takes its child alternations' alternatives as its own and factors them. In a
    /// chain of nested alternations, flattening child alternations and factoring them again would
    /// copy and refactor every level below at each level. Refactoring a factored list F changes
    /// little. Each round replaces maximal runs of neighbors: round 1 those whose leading literals
    /// share flags and first rune, where a whole literal that full case folding needs intact has
    /// no key, round 2 those with equal candidate leading regexps, and round 3 literals and
    /// classes. In F:
    /// - No neighbors share a round 1 key. Round 1 left none, its products keep their run's key,
    ///   and those of rounds 2 and 3 have none.
    /// - No neighbors share a round 2 key unless one is a class F's round 3 merged. Round 2 left
    ///   none, its products keep their run's key, round 1 keys are never round 2 keys, and round 3
    ///   only adds such classes.
    /// - No neighbors are both round 3 members. Round 3 left none, and its classes stand
    ///   between non-members.
    ///
    /// No key depends on a level's flags, and round 3 membership depends on them only through
    /// LATIN1, which a parse never changes. So when an enclosing alternation factors F among other
    /// alternatives, a run of two or more can only take in F's two ends, the classes F's round 3
    /// merged, each with its two neighbors, and alternatives from outside F. The rest of F passes
    /// through every round untouched, since what each round makes of a neighbor keeps the key, or
    /// the lack of membership, that kept the two apart. The enclosing alternation therefore factors
    /// only the alternatives a run can take in, with a gap that no run joins for each untouched
    /// stretch, and splices the result into the list. The classes its own round 3 merged are the
    /// next level's to revisit; the earlier ones have been compared with their neighbors.
    ///
    /// A level thus works on its own alternatives, two ends per child list, and three alternatives
    /// per class the child's round 3 merged, which that merge paid for. The collapse to any
    /// character needs every alternative's flags and kind, so the list keeps counts of them, which
    /// each level updates for the alternatives it replaces.
    private static final class PendingAlternation
    {
        private final int flags;
        private final Node first;
        private final Node last;
        private final int size;
        // The classes the last round 3 merged, in list order.
        private final List<Node> merged;
        private final CollapseSummary summary;
        // The alternatives the summary does not count yet.
        private List<Factorable> unsummarized;

        private PendingAlternation(int flags, Node first, Node last, int size, List<Node> merged, CollapseSummary summary, List<Factorable> unsummarized)
        {
            this.flags = flags;
            this.first = first;
            this.last = last;
            this.size = size;
            this.merged = merged;
            this.summary = summary;
            this.unsummarized = unsummarized;
        }

        CollapseSummary summary(CaseFolding caseFolding)
        {
            if (unsummarized != null) {
                for (Factorable alternative : unsummarized) {
                    if (!(alternative instanceof Gap)) {
                        summary.add(alternative, caseFolding, 1);
                    }
                }
                unsummarized = null;
            }
            return summary;
        }

        // Builds the list from factored alternatives, linking each gap's stretch back in place.
        static PendingAlternation splice(int flags, List<Factorable> factored, List<Integer> mergedIndexes, int size, CollapseSummary summary)
        {
            Node first = null;
            Node last = null;
            List<Node> merged = new ArrayList<>(mergedIndexes.size());
            int mergedIndex = 0;
            for (int index = 0; index < factored.size(); index++) {
                Factorable alternative = factored.get(index);
                Node start;
                Node end;
                if (alternative instanceof Gap gap) {
                    start = gap.first();
                    end = gap.last();
                }
                else {
                    start = new Node(alternative);
                    end = start;
                    if (mergedIndex < mergedIndexes.size() && mergedIndexes.get(mergedIndex) == index) {
                        merged.add(start);
                        mergedIndex++;
                    }
                }
                if (last == null) {
                    first = start;
                }
                else {
                    last.next = start;
                }
                start.previous = last;
                last = end;
            }
            last.next = null;
            return new PendingAlternation(flags, first, last, size, merged, summary, factored);
        }

        // Adds to window the alternatives that a run can take in, with a gap for each stretch
        // between them, and removes them from summary, when not null. Returns how many it added.
        int addChangeableTo(List<Factorable> window, CollapseSummary summary, CaseFolding caseFolding)
        {
            // In list order, apart from repeats.
            List<Node> changeable = new ArrayList<>(3 * merged.size() + 2);
            changeable.add(first);
            for (Node node : merged) {
                changeable.add(node.previous);
                changeable.add(node);
                changeable.add(node.next);
            }
            changeable.add(last);

            Node previous = null;
            int added = 0;
            for (Node node : changeable) {
                if (node == null || node.inWindow) {
                    continue;
                }
                if (previous != null && node.previous != previous) {
                    window.add(new Gap(previous.next, node.previous));
                }
                node.inWindow = true;
                window.add(node.alternative);
                if (summary != null) {
                    summary.add(node.alternative, caseFolding, -1);
                }
                previous = node;
                added++;
            }
            return added;
        }

        void addAlternativesTo(List<Object> parts)
        {
            for (Node node = first; node != null; node = node.next) {
                parts.add(node.alternative);
            }
        }

        Regexp toRegexp()
        {
            List<Regexp> alternatives = new ArrayList<>(size);
            for (Node node = first; node != null; node = node.next) {
                alternatives.add(build(node.alternative));
            }
            return Regexp.alternate(flags, alternatives);
        }
    }

    private static final class Node
    {
        private final Factorable alternative;
        private Node previous;
        private Node next;
        // Whether a factoring took this alternative, which then replaces the node.
        private boolean inWindow;

        private Node(Factorable alternative)
        {
            this.alternative = alternative;
        }
    }

    // An untouched stretch of a pending alternation's list, from first to last, during factoring.
    private record Gap(Node first, Node last)
            implements Factorable {}

    // Counts the flags and kinds of a list's alternatives, which decide whether an alternation of
    // them collapses to any character or any byte.
    private static final class CollapseSummary
    {
        // Most alternations share one flags value, which needs no map.
        private int mainFlags;
        private int mainCount;
        private Map<Integer, Integer> otherFlags;
        private int count;
        private int anyBytes;
        private int anyChars;
        // Alternatives that match one scalar, judged under their own flags; the collapse needs all
        // the alternatives to have the alternation's flags anyway.
        private int scalars;

        void add(Factorable alternative, CaseFolding caseFolding, int sign)
        {
            int flags;
            RegexpOp op;
            boolean scalar;
            switch (alternative) {
                case Alternative view -> {
                    Regexp regexp = view.regexp();
                    flags = regexp.parseFlags();
                    op = regexp.op();
                    scalar = switch (op) {
                        case LITERAL -> (flags & Regexp.FULL_CASE_FOLD) == 0 ||
                                !caseFolding.requiresFullCaseFold(new int[] {regexp.rune()});
                        case CHAR_CLASS -> (flags & Regexp.FULL_CASE_FOLD) == 0 ||
                                caseFolding.multiCharacterTokens(regexp.charClass()).isEmpty();
                        case ANY_CHAR -> true;
                        case ANY_BYTE -> (flags & Regexp.LATIN1) != 0;
                        default -> false;
                    };
                }
                case PendingClass pendingClass -> {
                    flags = pendingClass.flags;
                    op = RegexpOp.CHAR_CLASS;
                    scalar = (flags & Regexp.FULL_CASE_FOLD) == 0 ||
                            caseFolding.multiCharacterTokens(pendingClass.toCharClass()).isEmpty();
                }
                case PendingProduct product -> {
                    flags = product.flags();
                    op = RegexpOp.CONCAT;
                    scalar = false;
                }
                case Gap _ -> throw new IllegalArgumentException("gap is not an alternative");
            }
            addFlags(flags, sign);
            count += sign;
            if (op == RegexpOp.ANY_BYTE) {
                anyBytes += sign;
            }
            if (op == RegexpOp.ANY_CHAR) {
                anyChars += sign;
            }
            if (scalar) {
                scalars += sign;
            }
        }

        void addAll(CollapseSummary other)
        {
            addFlags(other.mainFlags, other.mainCount);
            if (other.otherFlags != null) {
                other.otherFlags.forEach(this::addFlags);
            }
            count += other.count;
            anyBytes += other.anyBytes;
            anyChars += other.anyChars;
            scalars += other.scalars;
        }

        private void addFlags(int flags, int flagCount)
        {
            if (flagCount == 0) {
                return;
            }
            if (mainCount == 0 && (otherFlags == null || otherFlags.isEmpty())) {
                mainFlags = flags;
            }
            if (flags == mainFlags) {
                mainCount += flagCount;
                return;
            }
            if (otherFlags == null) {
                otherFlags = new HashMap<>();
            }
            if (otherFlags.merge(flags, flagCount, Integer::sum) == 0) {
                otherFlags.remove(flags);
            }
        }

        private boolean allHaveFlags(int flags)
        {
            if (count == 0) {
                return true;
            }
            if (flags == mainFlags) {
                return mainCount == count;
            }
            return otherFlags != null && otherFlags.getOrDefault(flags, 0) == count;
        }

        // Whether more alternatives could still let these collapse: all have the flags, and all
        // are any bytes or all are scalars.
        boolean mayCollapse(int flags)
        {
            return allHaveFlags(flags) && (anyBytes == count || scalars == count);
        }

        // Returns the alternation of both summaries' alternatives collapsed, or null when it does
        // not collapse.
        static Regexp collapse(int flags, CollapseSummary first, CollapseSummary second)
        {
            if (!first.allHaveFlags(flags) || !second.allHaveFlags(flags)) {
                return null;
            }
            int count = first.count + second.count;
            int anyBytes = first.anyBytes + second.anyBytes;
            int scalars = first.scalars + second.scalars;
            if (anyBytes > 0 && (anyBytes == count || ((flags & Regexp.LATIN1) != 0 && scalars == count))) {
                return Regexp.anyByte(flags);
            }
            if (first.anyChars + second.anyChars > 0 && scalars == count) {
                return Regexp.anyChar(flags);
            }
            return null;
        }
    }

    /// A class that round 3 merged, kept as a set of ranges until a Regexp node is needed. A list's
    /// end class can take in another literal at each enclosing level, and building a Regexp node
    /// each time would copy the class at each level. The set keeps its rune count and ASCII letters
    /// current, so comparing it with a class of another size costs nothing, and with one of its size
    /// no more than that class's ranges.
    private static final class PendingClass
            implements Factorable
    {
        // The letter bitmasks mirror CharClassBuilder.updateAsciiBitmaps, and the runes added for
        // a member mirror CharClassBuilder.addRangeFlags, so the class compares as its node would.
        private static final int ALPHA_MASK = (1 << 26) - 1;

        // Non-overlapping, non-adjacent ranges, low to high.
        private final TreeMap<Integer, Integer> ranges = new TreeMap<>();
        private int runeCount;
        // Changes whenever the runes do, so a remembered comparison holds while it is unchanged.
        private int version;
        private ClassComparison lastComparison;
        // ASCII A-Z and a-z coverage bitmasks (bit 0 = 'A'/'a').
        private int upper;
        private int lower;
        private int flags;

        int rangeCount()
        {
            return ranges.size();
        }

        // Adds what CharClassBuilder.addRangeFlags would add for a literal or class member under
        // the member's flags, with a literal's flags adjusted by literalMergeFlags.
        void addRunes(Regexp member, CaseFolding caseFolding)
        {
            int memberFlags = (member.op() == RegexpOp.LITERAL) ? literalMergeFlags(member) : member.parseFlags();
            if ((memberFlags & (Regexp.ASCII_FOLD_CASE | Regexp.FOLD_CASE)) == 0) {
                boolean excludeNewline = excludesNewline(memberFlags);
                if (member.op() == RegexpOp.LITERAL) {
                    addRange(member.rune(), member.rune(), excludeNewline);
                }
                else {
                    for (RuneRange runeRange : member.charClass().rangeView()) {
                        addRange(runeRange.low(), runeRange.high(), excludeNewline);
                    }
                }
                return;
            }
            CharClassBuilder builder = new CharClassBuilder();
            if (member.op() == RegexpOp.LITERAL) {
                builder.addRangeFlags(member.rune(), member.rune(), memberFlags, caseFolding);
            }
            else {
                for (RuneRange runeRange : member.charClass().rangeView()) {
                    builder.addRangeFlags(runeRange.low(), runeRange.high(), memberFlags, caseFolding);
                }
            }
            for (RuneRange runeRange : builder.toCharClass().rangeView()) {
                addRange(runeRange.low(), runeRange.high());
            }
        }

        // Adds what CharClassBuilder.addRangeFlags would add for another pending class under its
        // flags.
        void addRunes(PendingClass other, CaseFolding caseFolding)
        {
            CharClassBuilder builder = new CharClassBuilder();
            other.ranges.forEach((low, high) -> builder.addRangeFlags(low, high, other.flags, caseFolding));
            for (RuneRange runeRange : builder.toCharClass().rangeView()) {
                addRange(runeRange.low(), runeRange.high());
            }
        }

        // Merging this class again adds what CharClassBuilder.addRangeFlags would add for its own
        // ranges under its flags. Round 3 clears FOLD_CASE from them, so that is the class without
        // a newline it may not hold, and under ASCII_FOLD_CASE with the other case of its letters.
        void addOwnRunesUnderFlags()
        {
            if (excludesNewline(flags)) {
                removeNewline();
            }
            if ((flags & Regexp.ASCII_FOLD_CASE) != 0) {
                int missingLower = upper & ~lower;
                int missingUpper = lower & ~upper;
                for (int letter = 0; letter < 26; letter++) {
                    if ((missingLower & (1 << letter)) != 0) {
                        addRange('a' + letter, 'a' + letter);
                    }
                    if ((missingUpper & (1 << letter)) != 0) {
                        addRange('A' + letter, 'A' + letter);
                    }
                }
            }
        }

        boolean mayHaveSameRunes(CharClass charClass)
        {
            return runeCount == charClass.runeCount() && ranges.size() == charClass.rangeCount() && foldsAscii() == charClass.foldsAscii();
        }

        boolean mayHaveSameRunes(PendingClass other)
        {
            return runeCount == other.runeCount && ranges.size() == other.ranges.size();
        }

        boolean sameRunes(CharClass charClass)
        {
            if (!mayHaveSameRunes(charClass)) {
                return false;
            }
            int index = 0;
            for (Map.Entry<Integer, Integer> entry : ranges.entrySet()) {
                RuneRange runeRange = charClass.range(index++);
                if (runeRange.low() != entry.getKey() || runeRange.high() != entry.getValue()) {
                    return false;
                }
            }
            return true;
        }

        boolean sameRunes(PendingClass other)
        {
            if (!mayHaveSameRunes(other)) {
                return false;
            }
            Iterator<Map.Entry<Integer, Integer>> otherRanges = other.ranges.entrySet().iterator();
            for (Map.Entry<Integer, Integer> entry : ranges.entrySet()) {
                if (!entry.equals(otherRanges.next())) {
                    return false;
                }
            }
            return true;
        }

        CharClass toCharClass()
        {
            CharClassBuilder builder = new CharClassBuilder();
            ranges.forEach(builder::addRange);
            return builder.toCharClass();
        }

        Regexp toRegexp()
        {
            return Regexp.charClass(flags, toCharClass());
        }

        private boolean foldsAscii()
        {
            return ((upper ^ lower) & ALPHA_MASK) == 0;
        }

        private static boolean excludesNewline(int flags)
        {
            return ((flags & Regexp.CLASS_NEWLINE) == 0) || ((flags & Regexp.NEVER_NEWLINE) != 0);
        }

        private void addRange(int low, int high, boolean excludeNewline)
        {
            if (excludeNewline && low <= '\n' && '\n' <= high) {
                if (low < '\n') {
                    addRange(low, '\n' - 1);
                }
                if (high > '\n') {
                    addRange('\n' + 1, high);
                }
                return;
            }
            addRange(low, high);
        }

        private void addRange(int low, int high)
        {
            Map.Entry<Integer, Integer> floor = ranges.floorEntry(low);
            if (floor != null && floor.getValue() >= high) {
                return;
            }
            int mergedLow = low;
            int mergedHigh = high;
            if (floor != null && floor.getValue() >= low - 1) {
                mergedLow = floor.getKey();
                removeRange(floor);
            }
            while (true) {
                Map.Entry<Integer, Integer> next = ranges.ceilingEntry(mergedLow);
                if (next == null || next.getKey() > mergedHigh + 1) {
                    break;
                }
                mergedHigh = Math.max(mergedHigh, next.getValue());
                removeRange(next);
            }
            putRange(mergedLow, mergedHigh);
            // As CharClassBuilder.updateAsciiBitmaps does.
            int upperLow = Math.max(mergedLow, 'A');
            int upperHigh = Math.min(mergedHigh, 'Z');
            if (upperLow <= upperHigh) {
                upper |= letterBits(upperLow - 'A', upperHigh - 'A');
            }
            int lowerLow = Math.max(mergedLow, 'a');
            int lowerHigh = Math.min(mergedHigh, 'z');
            if (lowerLow <= lowerHigh) {
                lower |= letterBits(lowerLow - 'a', lowerHigh - 'a');
            }
        }

        private void removeNewline()
        {
            Map.Entry<Integer, Integer> floor = ranges.floorEntry((int) '\n');
            if (floor == null || floor.getValue() < '\n') {
                return;
            }
            removeRange(floor);
            if (floor.getKey() < '\n') {
                putRange(floor.getKey(), '\n' - 1);
            }
            if (floor.getValue() > '\n') {
                putRange('\n' + 1, floor.getValue());
            }
        }

        private void putRange(int low, int high)
        {
            ranges.put(low, high);
            runeCount += high - low + 1;
            version++;
        }

        private void removeRange(Map.Entry<Integer, Integer> range)
        {
            ranges.remove(range.getKey());
            runeCount -= range.getValue() - range.getKey() + 1;
            version++;
        }

        private static int letterBits(int from, int to)
        {
            return ((1 << (to - from + 1)) - 1) << from;
        }
    }

    /// A round 1 or round 2 product whose suffix is a pending class or product, standing for the
    /// concatenation of prefix and suffix with these flags. An enclosing factoring that removes the
    /// whole prefix extends the suffix. A remainder has lost part of its prefix's leading string to
    /// an enclosing round 1 factoring. What is left of the string leads it, which is never a round 2
    /// prefix, so it is treated as having none.
    private record PendingProduct(Alternative prefix, boolean remainder, int flags, Factorable suffix)
            implements Factorable {}

    // An alternative during factoring, or a gap standing for an untouched stretch of them.
    private sealed interface Factorable
            permits Alternative,
                    Gap,
                    PendingClass,
                    PendingProduct {}

    // The simplified value of a factored alternative.
    private static Object value(Factorable alternative)
    {
        return (alternative instanceof Alternative view) ? view.toRegexp() : alternative;
    }

    // Builds a simplified value into a Regexp node.
    private static Regexp toRegexp(Object value)
    {
        return switch (value) {
            case Regexp regexp -> regexp;
            case PendingAlternation alternation -> alternation.toRegexp();
            case Factorable alternative -> build(alternative);
            default -> throw new IllegalArgumentException("not a simplified value: " + value);
        };
    }

    // Builds an alternative into a Regexp node. A product's suffix chain is built from its end, so
    // a long chain needs no recursion.
    private static Regexp build(Factorable alternative)
    {
        List<PendingProduct> products = new ArrayList<>();
        Factorable cursor = alternative;
        while (cursor instanceof PendingProduct product) {
            products.add(product);
            cursor = product.suffix();
        }
        Regexp result = switch (cursor) {
            case Alternative view -> view.toRegexp();
            case PendingClass pendingClass -> pendingClass.toRegexp();
            case PendingProduct _, Gap _ -> throw new IllegalArgumentException("not an alternative: " + cursor);
        };
        for (int index = products.size() - 1; index >= 0; index--) {
            PendingProduct product = products.get(index);
            result = Regexp.concat(product.flags(), List.of(product.prefix().toRegexp(), result));
        }
        return result;
    }

    // A concatenation of empty matches and one pending value is what a ConcatBuilder makes of the
    // value's node: an alternation or class itself, and a product's two children in a
    // concatenation with the concatenation's flags.
    private static Object concatOfPending(int flags, Object pending)
    {
        if (pending instanceof PendingProduct product) {
            PendingProduct whole = (PendingProduct) whole(product);
            return new PendingProduct(whole.prefix(), false, flags, whole.suffix());
        }
        return pending;
    }

    /// Simplifies a character class. Returns `original` when it is a node with these flags and
    /// this class and rebuilding would produce an equal node; `original` may be null.
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

    private static List<Regexp> mergeAlternateCharClassesWherePossible(CaseFolding caseFolding, int flags, List<Regexp> alternatives)
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
                mergedAlternatives.add(simplifyCharClass(Mode.FULL, flags, mergedClassBuilder.toCharClass(), null));
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

    /// Factors common prefixes out of an alternation in three rounds, as upstream parse.cc does:
    /// literal prefixes, then leading regexps, then runs of literals and classes merged into one
    /// class. Each prefix found factors its suffixes again, so a long shared prefix nests one
    /// factoring inside another per prefix piece. The nested factorings wait on an explicit stack,
    /// so their depth is not limited by the thread stack. The classes that the outermost round 3
    /// merged have their indexes in the returned list added to `mergedIndexes`.
    private static List<Factorable> factorAlternationForParse(List<Factorable> alternatives, int flags, CaseFolding caseFolding, List<Integer> mergedIndexes)
    {
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
            // The list goes to every round 3 and only the outermost records into it. It is never
            // replaced by null for the others: JDK 25's C2 miscompiles that merge of a fresh list
            // with null, throwing a NullPointerException on the list, which
            // -XX:-ReduceAllocationMerges hides.
            boolean outermost = waiting.isEmpty();
            List<Factorable> factored = factorAlternationRound3(task.factored, flags, caseFolding, mergedIndexes, outermost);
            if (outermost) {
                return factored;
            }
            task = waiting.pop();
            task.factored.add(product(flags, task.prefix, factored));
        }
    }

    // One factoring of an alternation, paused while the suffixes of a prefix it found are factored.
    private static final class FactorTask
    {
        private List<Factorable> alternatives;
        private List<Factorable> factored;
        private boolean secondRound;
        private int index;
        // The prefix whose suffixes are being factored.
        private Regexp prefix;

        private FactorTask(List<Factorable> alternatives)
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
        List<Factorable> alternatives = task.alternatives;
        while (task.index < alternatives.size()) {
            int startIndex = task.index;
            Factorable alternative = alternatives.get(startIndex);
            LeadingString base = leadingString(alternative);
            if (base == null || base.length() == 0 || requiresFullCaseFold(alternative, base, caseFolding)) {
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

            List<Factorable> suffixes = new ArrayList<>(endIndex - startIndex);
            for (int suffixIndex = startIndex; suffixIndex < endIndex; suffixIndex++) {
                suffixes.add(removeLeadingString(alternatives.get(suffixIndex), commonLength));
            }
            return new FactorTask(suffixes);
        }
        return null;
    }

    private static boolean continuesRun(LeadingString base, Factorable alternative, CaseFolding caseFolding)
    {
        LeadingString next = leadingString(alternative);
        return next != null && next.flags() == base.flags() && !requiresFullCaseFold(alternative, next, caseFolding) &&
                next.length() > 0 && next.rune(0) == base.rune(0);
    }

    // Round 2: factor out common simple leading regexps. Returns the factoring of the next run's
    // suffixes, or null when the round is done.
    private static FactorTask factorAlternationRound2(FactorTask task)
    {
        List<Factorable> alternatives = task.alternatives;
        while (task.index < alternatives.size()) {
            int startIndex = task.index;
            Factorable alternative = alternatives.get(startIndex);
            Object firstPrefix = leadingRegexp(alternative);
            if (!isRound2PrefixCandidate(firstPrefix)) {
                task.factored.add(alternative);
                task.index++;
                continue;
            }

            int endIndex = startIndex + 1;
            for (; endIndex < alternatives.size(); endIndex++) {
                Object nextPrefix = leadingRegexp(alternatives.get(endIndex));
                if (nextPrefix == null || !sameLeadingRegexp(alternative, firstPrefix, nextPrefix)) {
                    break;
                }
            }
            task.index = endIndex;
            if (endIndex - startIndex == 1) {
                task.factored.add(alternative);
                continue;
            }

            // A class that becomes a prefix grows no further, so it can be built.
            task.prefix = (firstPrefix instanceof PendingClass pendingClass) ? pendingClass.toRegexp() : (Regexp) firstPrefix;
            List<Factorable> suffixes = new ArrayList<>(endIndex - startIndex);
            for (int suffixIndex = startIndex; suffixIndex < endIndex; suffixIndex++) {
                suffixes.add(removeLeadingRegexp(alternatives.get(suffixIndex)));
            }
            return new FactorTask(suffixes);
        }
        return null;
    }

    // Round 3: merge runs of literals and classes into one class. Returns the finished alternatives,
    // with no views left, and adds the indexes of the merged classes to mergedIndexes when
    // recordMerges is set. mergedIndexes is never null; see factorAlternationForParse.
    private static List<Factorable> factorAlternationRound3(List<Factorable> alternatives, int flags, CaseFolding caseFolding, List<Integer> mergedIndexes, boolean recordMerges)
    {
        ArrayList<Factorable> factoredAlternatives = new ArrayList<>(alternatives.size());
        int alternativeIndex = 0;
        while (alternativeIndex < alternatives.size()) {
            Factorable alternative = alternatives.get(alternativeIndex);
            if (!isRound3CharClassCandidate(alternative, flags, caseFolding)) {
                factoredAlternatives.add(whole(alternative));
                alternativeIndex++;
                continue;
            }

            int startIndex = alternativeIndex;
            int endIndex = alternativeIndex + 1;
            while (endIndex < alternatives.size()) {
                Factorable scanAlternative = alternatives.get(endIndex);
                if (isRound3CharClassCandidate(scanAlternative, flags, caseFolding)) {
                    endIndex++;
                }
                else {
                    break;
                }
            }

            if ((endIndex - startIndex) > 1) {
                if (recordMerges) {
                    mergedIndexes.add(factoredAlternatives.size());
                }
                factoredAlternatives.add(mergeAlternativesIntoCharClass(alternatives, startIndex, endIndex, flags, caseFolding));
            }
            else {
                factoredAlternatives.add(alternative);
            }
            alternativeIndex = endIndex;
        }
        return factoredAlternatives;
    }

    // A remainder of a literal string or concatenation is neither a literal nor a class.
    private static boolean isRound3CharClassCandidate(Factorable alternative, int flags, CaseFolding caseFolding)
    {
        return switch (alternative) {
            case Alternative view -> view.isWhole() && isRound3CharClassCandidate(view.regexp(), flags, caseFolding);
            case PendingClass pendingClass -> ((pendingClass.flags ^ flags) & Regexp.LATIN1) == 0;
            case PendingProduct _, Gap _ -> false;
        };
    }

    private static boolean isRound3CharClassCandidate(Regexp regexp, int flags, CaseFolding caseFolding)
    {
        return (regexp.op() == RegexpOp.LITERAL || regexp.op() == RegexpOp.CHAR_CLASS) &&
                ((regexp.parseFlags() ^ flags) & Regexp.LATIN1) == 0 &&
                (regexp.op() != RegexpOp.LITERAL || (regexp.parseFlags() & Regexp.FULL_CASE_FOLD) == 0 ||
                        !caseFolding.requiresFullCaseFold(new int[] {regexp.rune()}));
    }

    // leadingString is leadingString(alternative): the alternative's, or its product's prefix's.
    private static boolean requiresFullCaseFold(Factorable alternative, LeadingString leadingString, CaseFolding caseFolding)
    {
        // A remainder of a leading string that was checked when its prefix was removed: the fold
        // of a suffix is a suffix of the fold, so it cannot contain a multi-character token
        // either.
        if (leadingString.start() > 0) {
            return false;
        }
        Alternative view = (alternative instanceof PendingProduct product) ? product.prefix() : (Alternative) alternative;
        if (view.leadingStringRequiresFullCaseFold == null) {
            view.leadingStringRequiresFullCaseFold = requiresFullCaseFold(leadingString.flags(), leadingString.runes(), caseFolding);
        }
        return view.leadingStringRequiresFullCaseFold;
    }

    private static boolean requiresFullCaseFold(int flags, int[] runes, CaseFolding caseFolding)
    {
        // Full folds can consume multiple code points, including across a new factored
        // prefix boundary. Keep those literals intact; simple folding still factors.
        return (flags & Regexp.FULL_CASE_FOLD) != 0 && caseFolding.requiresFullCaseFold(runes);
    }

    // The class adds each member's runes as CharClassBuilder.addRangeFlags would under the
    // member's flags, with a literal's flags adjusted by literalMergeFlags. The members' order
    // does not change the union, so the largest pending member takes in the others, and a class
    // that grows at each level is not copied at each.
    private static PendingClass mergeAlternativesIntoCharClass(List<Factorable> alternatives, int startIndex, int endIndex, int flags, CaseFolding caseFolding)
    {
        PendingClass merged = null;
        for (int mergeIndex = startIndex; mergeIndex < endIndex; mergeIndex++) {
            if (alternatives.get(mergeIndex) instanceof PendingClass pendingClass && (merged == null || pendingClass.rangeCount() > merged.rangeCount())) {
                merged = pendingClass;
            }
        }
        if (merged == null) {
            merged = new PendingClass();
        }
        else {
            merged.addOwnRunesUnderFlags();
        }
        for (int mergeIndex = startIndex; mergeIndex < endIndex; mergeIndex++) {
            Factorable mergeAlternative = alternatives.get(mergeIndex);
            if (mergeAlternative instanceof PendingClass pendingClass) {
                if (pendingClass != merged) {
                    merged.addRunes(pendingClass, caseFolding);
                }
            }
            else {
                merged.addRunes(((Alternative) mergeAlternative).regexp(), caseFolding);
            }
        }
        merged.flags = flags & ~Regexp.FOLD_CASE;
        return merged;
    }

    private static void addLiteral(CharClassBuilder builder, Regexp literal, CaseFolding caseFolding)
    {
        builder.addRangeFlags(literal.rune(), literal.rune(), literalMergeFlags(literal), caseFolding);
    }

    // The compiler matches a literal folded without full case folding, the form [Kk] simplifies
    // to, as its ASCII letter pair, so it merges as that pair rather than its whole orbit.
    private static int literalMergeFlags(Regexp literal)
    {
        int literalFlags = literal.parseFlags();
        if ((literalFlags & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) == Regexp.FOLD_CASE) {
            literalFlags = (literalFlags & ~Regexp.FOLD_CASE) | Regexp.ASCII_FOLD_CASE;
        }
        return literalFlags;
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

    private static boolean isRound2PrefixCandidate(Object prefix)
    {
        if (!(prefix instanceof Regexp regexp)) {
            return prefix instanceof PendingClass;
        }
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

    /// An alternative during factoring: a whole node, or what remains of one after factoring
    /// removed a leading string or regexp. Removing a prefix makes a view instead of copying the
    /// remaining runes or children, so removing prefix after prefix from one long alternative costs
    /// time linear in its length. A view becomes a node once, when factoring is done with it.
    ///
    /// A view of a literal string starts at rune `start` and keeps at least two runes. A
    /// view of a concatenation starts at child `start` and keeps at least two children; a
    /// non-null `head` replaces that child when part of its leading string was removed.
    /// Smaller remainders are whole nodes.
    private static final class Alternative
            implements Factorable
    {
        private final Regexp regexp;
        private final int start;
        private final Alternative head;
        // Whether a whole leading string needs full case folding, once checked. The check reads
        // the whole string, and an alternative at an end of a pending alternation is factored
        // again at every enclosing level.
        private Boolean leadingStringRequiresFullCaseFold;
        private ClassComparison lastComparison;

        private Alternative(Regexp regexp, int start, Alternative head)
        {
            this.regexp = regexp;
            this.start = start;
            this.head = head;
        }

        static Alternative of(Regexp regexp)
        {
            return new Alternative(regexp, 0, null);
        }

        Regexp regexp()
        {
            return regexp;
        }

        int start()
        {
            return start;
        }

        Alternative head()
        {
            return head;
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

    private static LeadingString leadingString(Factorable alternative)
    {
        return switch (alternative) {
            case Alternative view -> leadingString(view);
            case PendingProduct product -> leadingString(product.prefix());
            case PendingClass _, Gap _ -> null;
        };
    }

    // A regexp, or a pending class, which compares by its runes.
    private static Object leadingRegexp(Factorable alternative)
    {
        return switch (alternative) {
            case Alternative view -> leadingRegexp(view);
            case PendingClass pendingClass -> pendingClass;
            case PendingProduct product -> product.remainder() ? null : product.prefix().regexp();
            case Gap _ -> null;
        };
    }

    /// Whether the leading regexps of two alternatives in round 2 are the same. Classes compare by
    /// their runes alone, as Regexp.equals compares them.
    ///
    /// Comparing two classes of one size and range count may read both in full. The same two classes
    /// can meet at every enclosing level: a class that round 3 merged returns to the next level's
    /// window with its neighbors even when the merge added no runes, and a list's last alternative
    /// always returns. So the first alternative remembers its last such comparison by the identity
    /// of both operands and the version of a pending class, which changes with its runes, and a
    /// comparison that meets the same operands again reuses the outcome. Each such comparison
    /// that reads classes again then follows a change in runes, which round 3 pays for. Most
    /// alternatives make one such comparison, so the first comparison only marks the alternative,
    /// and the second allocates the memory for the outcome.
    private static boolean sameLeadingRegexp(Factorable firstAlternative, Object first, Object second)
    {
        if (!mayReadClassesInFull(first, second)) {
            return sameLeadingRegexp(first, second);
        }
        // A null holder means the first alternative is a pending class.
        Alternative holder = switch (firstAlternative) {
            case Alternative view -> view;
            case PendingProduct product -> product.prefix();
            case PendingClass _ -> null;
            case Gap _ -> throw new IllegalArgumentException("gap has no leading regexp");
        };
        ClassComparison comparison = (holder != null) ? holder.lastComparison : ((PendingClass) firstAlternative).lastComparison;
        if (comparison != null && comparison.matches(first, second)) {
            return comparison.same;
        }
        boolean same = sameLeadingRegexp(first, second);
        if (comparison == null) {
            comparison = ClassComparison.COMPARED_ONCE;
        }
        else {
            if (comparison == ClassComparison.COMPARED_ONCE) {
                comparison = new ClassComparison();
            }
            comparison.record(first, second, same);
        }
        if (holder != null) {
            holder.lastComparison = comparison;
        }
        else {
            ((PendingClass) firstAlternative).lastComparison = comparison;
        }
        return same;
    }

    private static boolean sameLeadingRegexp(Object first, Object second)
    {
        if (first instanceof PendingClass pendingClass) {
            return sameRunes(pendingClass, second);
        }
        if (second instanceof PendingClass pendingClass) {
            return sameRunes(pendingClass, first);
        }
        return Objects.equals(first, second);
    }

    // Whether comparing the two leading regexps may read two classes in full: both are or repeat
    // classes, and the classes agree in size and range count, and in ASCII folding unless both
    // are pending classes.
    private static boolean mayReadClassesInFull(Object first, Object second)
    {
        if (first instanceof PendingClass pendingClass) {
            return mayHaveSameRunes(pendingClass, second);
        }
        if (second instanceof PendingClass pendingClass) {
            return mayHaveSameRunes(pendingClass, first);
        }
        CharClass firstClass = comparedClass((Regexp) first);
        CharClass secondClass = comparedClass((Regexp) second);
        return firstClass != null && secondClass != null && first != second &&
                firstClass.runeCount() == secondClass.runeCount() &&
                firstClass.rangeCount() == secondClass.rangeCount() &&
                firstClass.foldsAscii() == secondClass.foldsAscii();
    }

    private static boolean mayHaveSameRunes(PendingClass pendingClass, Object other)
    {
        return switch (other) {
            case PendingClass otherClass -> otherClass != pendingClass && pendingClass.mayHaveSameRunes(otherClass);
            case Regexp regexp when regexp.op() == RegexpOp.CHAR_CLASS -> pendingClass.mayHaveSameRunes(regexp.charClass());
            default -> false;
        };
    }

    // The class that Regexp.equals compares for a round 2 prefix, or null.
    private static CharClass comparedClass(Regexp prefix)
    {
        return switch (prefix.op()) {
            case CHAR_CLASS -> prefix.charClass();
            case REPEAT -> (prefix.child(0).op() == RegexpOp.CHAR_CLASS) ? prefix.child(0).charClass() : null;
            default -> null;
        };
    }

    // An alternative's last round 2 comparison that read two classes in full.
    private static final class ClassComparison
    {
        // Marks an alternative that made one such comparison and remembers nothing of it. It
        // matches no operands.
        private static final ClassComparison COMPARED_ONCE = new ClassComparison();

        private Object first;
        private int firstVersion;
        private Object second;
        private int secondVersion;
        private boolean same;

        boolean matches(Object first, Object second)
        {
            return this.first == first && this.second == second && firstVersion == version(first) && secondVersion == version(second);
        }

        void record(Object first, Object second, boolean same)
        {
            this.first = first;
            this.firstVersion = version(first);
            this.second = second;
            this.secondVersion = version(second);
            this.same = same;
        }

        private static int version(Object operand)
        {
            return (operand instanceof PendingClass pendingClass) ? pendingClass.version : 0;
        }
    }

    private static boolean sameRunes(PendingClass pendingClass, Object other)
    {
        return switch (other) {
            case PendingClass otherClass -> pendingClass.sameRunes(otherClass);
            case Regexp regexp when regexp.op() == RegexpOp.CHAR_CLASS -> pendingClass.sameRunes(regexp.charClass());
            default -> false;
        };
    }

    private static Factorable removeLeadingRegexp(Factorable alternative)
    {
        return switch (alternative) {
            case Alternative view -> removeLeadingRegexp(view);
            case PendingClass pendingClass -> Alternative.of(Regexp.emptyMatch(pendingClass.flags));
            case PendingProduct product -> product.suffix();
            case Gap _ -> throw new IllegalArgumentException("gap has no leading regexp");
        };
    }

    private static Factorable removeLeadingString(Factorable alternative, int prefixLength)
    {
        return switch (alternative) {
            case Alternative view -> removeLeadingString(view, prefixLength);
            case PendingProduct product -> {
                Alternative prefix = removeLeadingString(product.prefix(), prefixLength);
                yield (prefix.regexp().op() == RegexpOp.EMPTY_MATCH)
                        ? product.suffix()
                        : new PendingProduct(prefix, true, product.flags(), product.suffix());
            }
            case PendingClass _, Gap _ -> throw new IllegalArgumentException("no leading string: " + alternative);
        };
    }

    // An alternative that factoring is done with: a view becomes a node, and so does the prefix
    // of a remainder.
    private static Factorable whole(Factorable alternative)
    {
        return switch (alternative) {
            case Alternative view -> view.isWhole() ? view : Alternative.of(view.toRegexp());
            case PendingProduct product when product.remainder() -> new PendingProduct(Alternative.of(product.prefix().toRegexp()), false, product.flags(), product.suffix());
            case PendingProduct _, PendingClass _, Gap _ -> alternative;
        };
    }

    // The concatenation of a prefix with the alternation of its factored suffixes. A lone pending
    // suffix stays pending, so an enclosing alternation can still extend it.
    private static Factorable product(int flags, Regexp prefix, List<Factorable> suffixes)
    {
        if (suffixes.size() == 1 && !(suffixes.getFirst() instanceof Alternative)) {
            return new PendingProduct(Alternative.of(prefix), false, flags, suffixes.getFirst());
        }
        List<Regexp> built = new ArrayList<>(suffixes.size());
        for (Factorable suffix : suffixes) {
            built.add(build(suffix));
        }
        return Alternative.of(Regexp.concat(flags, List.of(prefix, alternateNoFactor(flags, built))));
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
