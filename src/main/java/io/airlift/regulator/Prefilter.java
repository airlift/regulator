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
import io.airlift.slice.Slices;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.Predicate;

import static io.airlift.regulator.Prefilter.PrefilterOp.ALL;
import static io.airlift.regulator.Prefilter.PrefilterOp.AND;
import static io.airlift.regulator.Prefilter.PrefilterOp.ATOM;
import static io.airlift.regulator.Prefilter.PrefilterOp.NONE;
import static io.airlift.regulator.Prefilter.PrefilterOp.OR;

/// Extracts byte-string guards from a regular expression.
final class Prefilter
{
    // Upstream's visit limit for building a regexp's guard information.
    private static final int MAX_VISITS = 100_000;

    enum PrefilterOp
    {
        ALL(0),
        NONE(1),
        ATOM(2),
        AND(3),
        OR(4);

        private final int canonicalOrder;

        PrefilterOp(int canonicalOrder)
        {
            this.canonicalOrder = canonicalOrder;
        }

        int canonicalOrder()
        {
            return canonicalOrder;
        }
    }

    private PrefilterOp op;
    private final Slice atom;
    private final Deque<Prefilter> children;

    private Prefilter(PrefilterOp op)
    {
        this(op, null);
    }

    private Prefilter(PrefilterOp op, Slice atom)
    {
        this.op = op;
        this.atom = atom;
        this.children = new ArrayDeque<>(2);
    }

    PrefilterOp op()
    {
        return op;
    }

    Slice atom()
    {
        return atom;
    }

    List<Prefilter> children()
    {
        return List.copyOf(children);
    }

    boolean retainChildren(Predicate<Prefilter> keepChild)
    {
        children.removeIf(child -> !keepChild.test(child));
        return !children.isEmpty();
    }

    /// Returns the prefilter of an RE2 pattern from `parsed`, the tree it was compiled from.
    /// Upstream's FromRE2 reads that tree from the compiled pattern, which here does not keep it.
    static Prefilter fromRegexp(Regexp parsed)
    {
        // Only RE2 patterns build prefilters, so atoms fold with RE2's rule, the rule
        // FilteredRe2.canonicalizeText applies to the searched text.
        Regexp regexp = Simplifier.simplify(parsed, CaseFolding.RE2);
        Info info = buildInfo(regexp);
        if (info == null) {
            return null;
        }
        return info.takeMatch();
    }

    private Prefilter simplify()
    {
        if (op != AND && op != OR) {
            return this;
        }
        if (children.isEmpty()) {
            op = (op == AND) ? ALL : NONE;
            return this;
        }
        if (children.size() == 1) {
            return children.getFirst().simplify();
        }
        return this;
    }

    private static Prefilter and(Prefilter left, Prefilter right)
    {
        return andOr(AND, left, right);
    }

    private static Prefilter or(Prefilter left, Prefilter right)
    {
        return andOr(OR, left, right);
    }

    private static Prefilter andOr(PrefilterOp operation, Prefilter left, Prefilter right)
    {
        left = left.simplify();
        right = right.simplify();

        if (left.op.canonicalOrder() > right.op.canonicalOrder()) {
            Prefilter swap = left;
            left = right;
            right = swap;
        }

        if (left.op == ALL || left.op == NONE) {
            if ((left.op == ALL && operation == AND) || (left.op == NONE && operation == OR)) {
                return right;
            }
            return left;
        }

        if (left.op == operation && right.op == operation) {
            // The left's children come first, as upstream. Upstream appends the right's to the
            // left's, which copies the whole growing side at each level when nested guards grow
            // on the right, so here the smaller list moves into the larger.
            if (left.children.size() >= right.children.size()) {
                left.children.addAll(right.children);
                return left;
            }
            for (Prefilter child : left.children.reversed()) {
                right.children.addFirst(child);
            }
            return right;
        }
        if (right.op == operation) {
            Prefilter swap = left;
            left = right;
            right = swap;
        }
        if (left.op == operation) {
            left.children.add(right);
            return left;
        }

        Prefilter result = new Prefilter(operation);
        result.children.add(left);
        result.children.add(right);
        return result;
    }

    private static Prefilter fromString(Slice atom)
    {
        return new Prefilter(ATOM, atom);
    }

    private static Prefilter orStrings(NavigableSet<Slice> strings)
    {
        simplifyStringSet(strings);
        Prefilter result = new Prefilter(NONE);
        for (Slice string : strings) {
            result = or(result, fromString(string));
        }
        return result;
    }

    private static void simplifyStringSet(NavigableSet<Slice> strings)
    {
        List<Slice> ordered = new ArrayList<>(strings);
        for (int currentIndex = 0; currentIndex < ordered.size(); currentIndex++) {
            Slice current = ordered.get(currentIndex);
            if (current.length() == 0) {
                continue;
            }
            for (int candidateIndex = currentIndex + 1; candidateIndex < ordered.size(); candidateIndex++) {
                Slice candidate = ordered.get(candidateIndex);
                if (candidate.length() > current.length() && candidate.indexOf(current) >= 0) {
                    strings.remove(candidate);
                }
            }
        }
    }

    /// Returns the guard information for a simplified regexp, or null when the walk takes more
    /// than [#MAX_VISITS] visits, which leaves the regexp unfiltered as in upstream. A shared
    /// node counts once per reference to it. The walk keeps its nesting on the heap, because
    /// deep group nesting and expanded repetition chains leave simplified regexps thousands of
    /// levels deep.
    private static Info buildInfo(Regexp regexp)
    {
        InfoWalker walker = new InfoWalker((regexp.parseFlags() & Regexp.LATIN1) != 0);
        Info info = walker.walkExponential(regexp, null, MAX_VISITS);
        if (walker.stoppedEarly) {
            return null;
        }
        return info;
    }

    private static final class InfoWalker
            extends RegexpWalker<Info>
    {
        private static final PreVisitResult<Info> CONTINUE = new PreVisitResult<>(null, false);

        private final boolean latin1;
        private boolean stoppedEarly;

        private InfoWalker(boolean latin1)
        {
            this.latin1 = latin1;
        }

        @Override
        protected PreVisitResult<Info> preVisit(Regexp regexp, Info parentValue)
        {
            return CONTINUE;
        }

        @Override
        protected Info postVisit(Regexp regexp, Info parentValue, Info preValue, List<Info> childInfos)
        {
            return switch (regexp.op()) {
                case NO_MATCH -> Info.noMatch();
                case EMPTY_MATCH, BEGIN_LINE, END_LINE, BEGIN_TEXT, END_TEXT, WORD_BOUNDARY, NO_WORD_BOUNDARY -> Info.emptyString();
                case LITERAL -> Info.literal(regexp.rune(), latin1);
                case LITERAL_STRING -> literalString(regexp.runes(), latin1);
                case CONCAT -> concat(childInfos);
                case ALTERNATE -> alternate(childInfos);
                case STAR -> Info.star(childInfos.getFirst());
                case QUEST -> Info.quest(childInfos.getFirst());
                case PLUS -> Info.plus(childInfos.getFirst());
                case ANY_CHAR, ANY_BYTE -> Info.anyMatch();
                case CHAR_CLASS -> Info.characterClass(regexp.charClass(), latin1);
                case CAPTURE -> childInfos.getFirst();
                case REPEAT, HAVE_MATCH -> Info.anyMatch();
            };
        }

        @Override
        protected Info shortVisit(Regexp regexp, Info parentValue)
        {
            stoppedEarly = true;
            return Info.anyMatch();
        }
    }

    private static Info literalString(int[] runes, boolean latin1)
    {
        if (runes.length == 0) {
            return Info.noMatch();
        }

        // Each rune yields one canonical string, so upstream's rune-by-rune concatenation yields
        // one string. It is built at once rather than by repeated concatenation, which copies the
        // whole string at each rune.
        return Info.exactString(runesToSlice(runes, latin1));
    }

    private static Info concat(List<Info> childInfos)
    {
        Info result = null;
        Info exact = null;
        // A child with one string appends the same suffix to every string of the exact run, which
        // leaves the run's size, and so the size check, unchanged. Such suffixes are joined when the
        // run's strings are next needed, rather than copying every string once per child.
        List<Slice> pendingSuffixes = new ArrayList<>();
        for (Info childInfo : childInfos) {
            if (!childInfo.isExact || (exact != null && !exactCrossProductFits(childInfo.exact.size(), exact.exact.size()))) {
                result = Info.and(result, Info.appendSuffixes(exact, pendingSuffixes));
                exact = null;
                result = Info.and(result, childInfo);
            }
            else if (exact != null && childInfo.exact.size() == 1) {
                pendingSuffixes.add(childInfo.exact.getFirst());
            }
            else {
                exact = Info.concat(Info.appendSuffixes(exact, pendingSuffixes), childInfo);
            }
        }
        return Info.and(result, Info.appendSuffixes(exact, pendingSuffixes));
    }

    // Whether two exact sets may form a cross product; long because huge sets would overflow int.
    static boolean exactCrossProductFits(int leftSize, int rightSize)
    {
        return (long) leftSize * rightSize <= 16;
    }

    private static Info alternate(List<Info> childInfos)
    {
        Info result = childInfos.getFirst();
        for (int childIndex = 1; childIndex < childInfos.size(); childIndex++) {
            result = Info.alternate(result, childInfos.get(childIndex));
        }
        return result;
    }

    private static Slice concatSlices(Slice left, Slice right)
    {
        Slice result = Slices.allocate(left.length() + right.length());
        result.setBytes(0, left);
        result.setBytes(left.length(), right);
        return result;
    }

    private static Slice runeToSlice(int rune, boolean latin1)
    {
        return runesToSlice(new int[] {rune}, latin1);
    }

    private static Slice runesToSlice(int[] runes, boolean latin1)
    {
        if (latin1) {
            byte[] bytes = new byte[runes.length];
            for (int runeIndex = 0; runeIndex < runes.length; runeIndex++) {
                bytes[runeIndex] = (byte) FilteredRe2.canonicalizeLatin1Byte(runes[runeIndex]);
            }
            return Slices.wrappedBuffer(bytes);
        }
        // Atoms apply only the simple fold; multi-character folds are not canonicalized.
        int length = 0;
        for (int rune : runes) {
            length += Utf8.encodedLength(FilteredRe2.canonicalizeUnicodeCodePoint(rune));
        }
        byte[] bytes = new byte[length];
        int offset = 0;
        for (int rune : runes) {
            offset = Utf8.encode(bytes, offset, FilteredRe2.canonicalizeUnicodeCodePoint(rune));
        }
        return Slices.wrappedBuffer(bytes);
    }

    private static NavigableSet<Slice> newStringSet()
    {
        return new TreeSet<>(Comparator.comparingInt(Slice::length).thenComparing(Slice::compareTo));
    }

    private static final class Info
    {
        private final NavigableSet<Slice> exact = newStringSet();
        private boolean isExact;
        private Prefilter match;

        private Prefilter takeMatch()
        {
            if (isExact) {
                match = orStrings(exact);
                isExact = false;
            }
            return match;
        }

        private static Info concat(Info left, Info right)
        {
            if (left == null) {
                return right;
            }

            Info result = new Info();
            for (Slice leftString : left.exact) {
                for (Slice rightString : right.exact) {
                    result.exact.add(concatSlices(leftString, rightString));
                }
            }
            result.isExact = true;
            return result;
        }

        // Appends the joined suffixes to every string of an exact run, consuming them.
        private static Info appendSuffixes(Info exact, List<Slice> suffixes)
        {
            if (suffixes.isEmpty()) {
                return exact;
            }

            int suffixLength = 0;
            for (Slice suffix : suffixes) {
                suffixLength += suffix.length();
            }
            Slice joinedSuffix = Slices.allocate(suffixLength);
            int offset = 0;
            for (Slice suffix : suffixes) {
                joinedSuffix.setBytes(offset, suffix);
                offset += suffix.length();
            }
            suffixes.clear();

            Info result = new Info();
            for (Slice string : exact.exact) {
                result.exact.add(concatSlices(string, joinedSuffix));
            }
            result.isExact = true;
            return result;
        }

        private static Info and(Info left, Info right)
        {
            if (left == null) {
                return right;
            }
            if (right == null) {
                return left;
            }

            Info result = new Info();
            result.match = Prefilter.and(left.takeMatch(), right.takeMatch());
            return result;
        }

        private static Info alternate(Info left, Info right)
        {
            if (left.isExact && right.isExact) {
                // As upstream, the larger set takes in the smaller, so a wide alternation does not
                // copy its whole set at each alternative. Each Info has one owner, which passes it
                // here and does not use it again, so the larger can be reused.
                Info larger = left.exact.size() >= right.exact.size() ? left : right;
                Info smaller = larger == left ? right : left;
                larger.exact.addAll(smaller.exact);
                return larger;
            }

            Info result = new Info();
            result.match = Prefilter.or(left.takeMatch(), right.takeMatch());
            return result;
        }

        private static Info quest(Info ignored)
        {
            return anyMatch();
        }

        private static Info star(Info info)
        {
            return quest(info);
        }

        private static Info plus(Info info)
        {
            Info result = new Info();
            result.match = info.takeMatch();
            return result;
        }

        private static Info literal(int rune, boolean latin1)
        {
            return exactString(runeToSlice(rune, latin1));
        }

        private static Info exactString(Slice string)
        {
            Info result = new Info();
            result.exact.add(string);
            result.isExact = true;
            return result;
        }

        private static Info characterClass(CharClass characterClass, boolean latin1)
        {
            if (characterClass.runeCount() > 10) {
                return anyMatch();
            }

            Info result = new Info();
            for (RuneRange range : characterClass.ranges()) {
                for (int rune = range.low(); rune <= range.high(); rune++) {
                    result.exact.add(runeToSlice(rune, latin1));
                }
            }
            result.isExact = true;
            return result;
        }

        private static Info noMatch()
        {
            Info result = new Info();
            result.match = new Prefilter(NONE);
            return result;
        }

        private static Info anyMatch()
        {
            Info result = new Info();
            result.match = new Prefilter(ALL);
            return result;
        }

        private static Info emptyString()
        {
            return exactString(Slices.EMPTY_SLICE);
        }
    }
}
