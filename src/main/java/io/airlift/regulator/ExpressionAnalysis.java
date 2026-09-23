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

import java.util.ArrayList;
import java.util.List;

import static io.airlift.regulator.Regexp.FOLD_CASE;
import static io.airlift.regulator.Regexp.LATIN1;
import static io.airlift.regulator.Regexp.NON_GREEDY;
import static java.util.Objects.requireNonNull;

/**
 * Immutable operation-independent facts derived from one normalized expression walk.
 * Direction-specific start-byte and fixed-distance candidates remain program metadata because
 * they are derived from compiled instructions rather than expression semantics.
 */
final class ExpressionAnalysis
{
    private static final int MAX_RECURSIVE_DEPTH = 256;
    private static final int UNBOUNDED = -1;
    private static final int IMPOSSIBLE = -2;

    enum Gap
    {
        NONE,
        ONE_BYTE,
        ONE_CODE_POINT,
        ONE_NON_NEWLINE_CODE_POINT,
        ZERO_OR_MORE_BYTES,
        ZERO_OR_MORE_CODE_POINTS,
        ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS,
        REQUIRES_GENERAL_ENGINE,
    }

    /**
     * @param encoded the length in the {@link MatchLength} encoding, from which the minimum and
     *         the fixed length are derived
     */
    record Length(int maximum, int encoded)
    {
        int minimum()
        {
            return MatchLength.minimum(encoded);
        }

        boolean canMatch()
        {
            return maximum != IMPOSSIBLE;
        }

        boolean isUnbounded()
        {
            return maximum == UNBOUNDED;
        }

        int fixed()
        {
            return MatchLength.fixed(encoded);
        }
    }

    static final class LiteralSequence
    {
        private final List<byte[]> literals;
        private final List<Gap> gaps;
        private final boolean anchoredAtStart;
        private final boolean anchoredAtEnd;
        private final boolean finalLineEnd;

        private LiteralSequence(List<byte[]> literals, List<Gap> gaps, boolean anchoredAtStart, boolean anchoredAtEnd, boolean finalLineEnd)
        {
            List<byte[]> literalCopies = new ArrayList<>(literals.size());
            for (byte[] literal : literals) {
                literalCopies.add(literal.clone());
            }
            this.literals = List.copyOf(literalCopies);
            this.gaps = List.copyOf(gaps);
            this.anchoredAtStart = anchoredAtStart;
            this.anchoredAtEnd = anchoredAtEnd;
            this.finalLineEnd = finalLineEnd;
        }

        int literalCount()
        {
            return literals.size();
        }

        Slice literal(int index)
        {
            return Slices.wrappedBuffer(literals.get(index).clone());
        }

        /**
         * Returns the analysis-owned literal for immutable compiled-pattern metadata.
         * The returned bytes must not be modified.
         */
        Slice retainedLiteral(int index)
        {
            return Slices.wrappedBuffer(literals.get(index));
        }

        Gap leadingGap()
        {
            return gaps.getFirst();
        }

        Gap gapAfter(int literalIndex)
        {
            return gaps.get(literalIndex + 1);
        }

        boolean anchoredAtStart()
        {
            return anchoredAtStart;
        }

        boolean anchoredAtEnd()
        {
            return anchoredAtEnd;
        }

        boolean finalLineEnd()
        {
            return finalLineEnd;
        }

        Slice exactLiteral()
        {
            Slice exactLiteral = retainedExactLiteral();
            return exactLiteral == null ? null : exactLiteral.copy();
        }

        Slice retainedExactLiteral()
        {
            for (Gap gap : gaps) {
                if (gap != Gap.NONE) {
                    return null;
                }
            }
            if (literals.isEmpty()) {
                return Slices.EMPTY_SLICE;
            }
            if (literals.size() != 1) {
                return null;
            }
            return retainedLiteral(0);
        }

        Slice requiredPrefix()
        {
            if (literals.isEmpty() || leadingGap() != Gap.NONE) {
                return null;
            }
            return literal(0);
        }

        Slice requiredSuffix()
        {
            if (literals.isEmpty() || gaps.getLast() != Gap.NONE) {
                return null;
            }
            return literal(literals.size() - 1);
        }
    }

    private final Length length;
    private final boolean latin1;
    private final boolean hasCaptures;
    private final boolean hasFoldCaseLiteral;
    private final boolean hasNonGreedyRepetition;
    private final boolean hasUnsupportedPositionAssertions;
    private final boolean canConsumeLineFeed;
    private final LiteralSequence literalSequence;

    private ExpressionAnalysis(Regexp regexp, Node node)
    {
        this.length = new Length(node.maximumLength, node.encodedLength);
        this.latin1 = (regexp.parseFlags() & LATIN1) != 0;
        this.hasCaptures = node.hasCaptures;
        this.hasFoldCaseLiteral = node.hasFoldCaseLiteral;
        this.hasNonGreedyRepetition = node.hasNonGreedyRepetition;
        this.hasUnsupportedPositionAssertions = node.hasUnsupportedPositionAssertions;
        this.canConsumeLineFeed = node.canConsumeLineFeed;
        this.literalSequence = node.elements == null
                ? null
                : buildLiteralSequence(node.elements);
    }

    static ExpressionAnalysis analyzeNormalized(Regexp normalizedRegexp)
    {
        requireNonNull(normalizedRegexp, "normalizedRegexp is null");
        Node node = analyze(normalizedRegexp, 0);
        return new ExpressionAnalysis(normalizedRegexp, node);
    }

    private static Node analyze(Regexp regexp, int depth)
    {
        if (depth >= MAX_RECURSIVE_DEPTH) {
            return new Analyzer().walk(regexp, null);
        }

        List<Node> children;
        if (regexp.childCount() == 0) {
            children = List.of();
        }
        else if (regexp.childCount() == 1) {
            children = List.of(analyze(regexp.child(0), depth + 1));
        }
        else {
            children = new ArrayList<>(regexp.childCount());
            for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
                children.add(analyze(regexp.child(childIndex), depth + 1));
            }
        }
        return Analyzer.analyzeNode(regexp, children);
    }

    Length length()
    {
        return length;
    }

    boolean canMatchEmpty()
    {
        return length.canMatch() && length.minimum() == 0;
    }

    boolean latin1()
    {
        return latin1;
    }

    boolean hasCaptures()
    {
        return hasCaptures;
    }

    boolean hasFoldCaseLiteral()
    {
        return hasFoldCaseLiteral;
    }

    boolean hasNonGreedyRepetition()
    {
        return hasNonGreedyRepetition;
    }

    boolean hasUnsupportedPositionAssertions()
    {
        return hasUnsupportedPositionAssertions;
    }

    boolean canConsumeLineFeed()
    {
        return canConsumeLineFeed;
    }

    LiteralSequence literalSequence()
    {
        return literalSequence;
    }

    private static List<Element> elements(Regexp regexp, List<Node> children)
    {
        return switch (regexp.op()) {
            case EMPTY_MATCH -> List.of();
            case BEGIN_TEXT -> List.of(new AssertionElement(Assertion.BEGIN_TEXT));
            case END_TEXT -> {
                if ((regexp.parseFlags() & Regexp.JAVA_FINAL_END) != 0) {
                    yield null;
                }
                yield (regexp.parseFlags() & Regexp.FINAL_LINE_END) == 0
                        ? List.of(new AssertionElement(Assertion.END_TEXT))
                        : List.of(new AssertionElement(Assertion.FINAL_LINE_END));
            }
            case LITERAL -> literalElements(regexp, new int[] {regexp.rune()});
            case LITERAL_STRING -> literalElements(regexp, regexp.runes());
            case CAPTURE -> children.getFirst().elements;
            case CONCAT -> concatenateElements(children);
            case STAR -> repeatedGap(children.getFirst());
            case ANY_BYTE -> List.of(new GapElement(Gap.ONE_BYTE));
            case ANY_CHAR -> List.of(new GapElement(Gap.ONE_CODE_POINT));
            case CHAR_CLASS -> singleCharacterGap(regexp);
            default -> null;
        };
    }

    private static List<Element> literalElements(Regexp regexp, int[] runes)
    {
        if ((regexp.parseFlags() & FOLD_CASE) != 0) {
            return null;
        }
        boolean latin1 = (regexp.parseFlags() & LATIN1) != 0;
        return List.of(new LiteralElement(Regexp.convertRunesToBytes(latin1, runes)));
    }

    private static List<Element> concatenateElements(List<Node> children)
    {
        List<Element> result = new ArrayList<>();
        for (Node child : children) {
            if (child.elements == null) {
                result.add(UnsupportedElement.INSTANCE);
                continue;
            }
            result.addAll(child.elements);
        }
        return List.copyOf(result);
    }

    private static List<Element> repeatedGap(Node child)
    {
        if (child.elements == null || child.elements.size() != 1 || !(child.elements.getFirst() instanceof GapElement gapElement)) {
            return null;
        }
        Gap gap = switch (gapElement.gap) {
            case ONE_BYTE -> Gap.ZERO_OR_MORE_BYTES;
            case ONE_CODE_POINT -> Gap.ZERO_OR_MORE_CODE_POINTS;
            case ONE_NON_NEWLINE_CODE_POINT -> Gap.ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS;
            default -> null;
        };
        return gap == null ? null : List.of(new GapElement(gap));
    }

    private static List<Element> singleCharacterGap(Regexp regexp)
    {
        if (!isNonNewlineCharacterClass(regexp)) {
            return null;
        }
        return List.of(new GapElement(Gap.ONE_NON_NEWLINE_CODE_POINT));
    }

    private static boolean isNonNewlineCharacterClass(Regexp regexp)
    {
        CharClass characterClass = regexp.charClass();
        int maximumRune = Regexp.maxRune(regexp.parseFlags());
        return characterClass.rangeCount() == 2 &&
                characterClass.range(0).equals(new RuneRange(0, '\n' - 1)) &&
                characterClass.range(1).equals(new RuneRange('\n' + 1, maximumRune));
    }

    private static LiteralSequence buildLiteralSequence(List<Element> elements)
    {
        int start = 0;
        int end = elements.size();
        boolean anchoredAtStart = false;
        boolean anchoredAtEnd = false;
        boolean hasExactEnd = false;
        boolean hasFinalLineEnd = false;
        while (start < end && elements.get(start) instanceof AssertionElement assertion && assertion.assertion == Assertion.BEGIN_TEXT) {
            anchoredAtStart = true;
            start++;
        }
        while (end > start && elements.get(end - 1) instanceof AssertionElement assertion) {
            if (assertion.assertion == Assertion.END_TEXT) {
                anchoredAtEnd = true;
                hasExactEnd = true;
                end--;
                continue;
            }
            if (assertion.assertion == Assertion.FINAL_LINE_END) {
                anchoredAtEnd = true;
                hasFinalLineEnd = true;
                end--;
                continue;
            }
            break;
        }

        List<byte[]> literals = new ArrayList<>();
        List<Gap> gaps = new ArrayList<>();
        Gap pendingGap = Gap.NONE;
        for (int elementIndex = start; elementIndex < end; elementIndex++) {
            Element element = elements.get(elementIndex);
            if (element instanceof AssertionElement) {
                pendingGap = mergeGap(pendingGap, Gap.REQUIRES_GENERAL_ENGINE);
                continue;
            }
            if (element instanceof UnsupportedElement) {
                pendingGap = mergeGap(pendingGap, Gap.REQUIRES_GENERAL_ENGINE);
                continue;
            }
            if (element instanceof GapElement gapElement) {
                pendingGap = mergeGap(pendingGap, gapElement.gap);
                continue;
            }

            byte[] bytes = ((LiteralElement) element).bytes;
            if (literals.isEmpty()) {
                gaps.add(pendingGap);
                literals.add(bytes);
            }
            else if (pendingGap == Gap.NONE) {
                literals.set(literals.size() - 1, concatenate(literals.getLast(), bytes));
            }
            else {
                gaps.add(pendingGap);
                literals.add(bytes);
            }
            pendingGap = Gap.NONE;
        }

        gaps.add(pendingGap);
        return new LiteralSequence(literals, gaps, anchoredAtStart, anchoredAtEnd, hasFinalLineEnd && !hasExactEnd);
    }

    private static Gap mergeGap(Gap left, Gap right)
    {
        if (left == Gap.NONE) {
            return right;
        }
        if (right == Gap.NONE) {
            return left;
        }
        return Gap.REQUIRES_GENERAL_ENGINE;
    }

    private static byte[] concatenate(byte[] left, byte[] right)
    {
        byte[] result = new byte[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private static boolean isUnsupportedPositionAssertion(Regexp regexp)
    {
        return switch (regexp.op()) {
            case BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY -> true;
            case END_TEXT -> (regexp.parseFlags() & (Regexp.FINAL_LINE_END | Regexp.JAVA_FINAL_END)) != 0;
            default -> false;
        };
    }

    private static boolean canConsumeLineFeed(Regexp regexp, boolean childCanConsumeLineFeed)
    {
        return switch (regexp.op()) {
            case LITERAL -> regexp.rune() == '\n';
            case LITERAL_STRING -> containsLineFeed(regexp.runes());
            case CHAR_CLASS -> regexp.charClass().contains('\n');
            case ANY_CHAR, ANY_BYTE -> true;
            default -> childCanConsumeLineFeed;
        };
    }

    private static boolean containsLineFeed(int[] runes)
    {
        for (int rune : runes) {
            if (rune == '\n') {
                return true;
            }
        }
        return false;
    }

    private static int maximumLength(Regexp regexp, List<Node> children)
    {
        return switch (regexp.op()) {
            case NO_MATCH -> IMPOSSIBLE;
            case EMPTY_MATCH, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT, HAVE_MATCH -> 0;
            case LITERAL -> maximumLiteralLength(regexp, new int[] {regexp.rune()});
            case LITERAL_STRING -> maximumLiteralLength(regexp, regexp.runes());
            case CONCAT -> addMaximums(children);
            case ALTERNATE -> alternateMaximum(children);
            case STAR -> repeatMaximum(children.getFirst().maximumLength, 0, -1);
            case PLUS -> repeatMaximum(children.getFirst().maximumLength, 1, -1);
            case QUEST -> repeatMaximum(children.getFirst().maximumLength, 0, 1);
            case REPEAT -> repeatMaximum(children.getFirst().maximumLength, regexp.min(), regexp.max());
            case CAPTURE -> children.getFirst().maximumLength;
            case ANY_CHAR -> (regexp.parseFlags() & LATIN1) != 0 ? 1 : 4;
            case ANY_BYTE -> 1;
            case CHAR_CLASS -> maximumCharacterClassLength(regexp);
        };
    }

    private static int maximumLiteralLength(Regexp regexp, int[] runes)
    {
        if ((regexp.parseFlags() & Regexp.FULL_CASE_FOLD) != 0) {
            return UnicodeFullCaseFold.byteLength(runes).maximum();
        }

        boolean latin1 = (regexp.parseFlags() & LATIN1) != 0;
        int length = 0;
        for (int rune : runes) {
            int runeLength = latin1 ? 1 : Utf8.encodedLength(rune);
            if ((regexp.parseFlags() & FOLD_CASE) != 0) {
                int foldedRune = UnicodeCaseFold.cycleFoldRune(rune);
                while (foldedRune != rune) {
                    runeLength = Math.max(runeLength, latin1 ? 1 : Utf8.encodedLength(foldedRune));
                    foldedRune = UnicodeCaseFold.cycleFoldRune(foldedRune);
                }
            }
            length = saturatedAdd(length, runeLength);
        }
        return length;
    }

    private static int maximumCharacterClassLength(Regexp regexp)
    {
        if ((regexp.parseFlags() & LATIN1) != 0) {
            return 1;
        }
        int maximum = IMPOSSIBLE;
        for (int rangeIndex = 0; rangeIndex < regexp.charClass().rangeCount(); rangeIndex++) {
            RuneRange range = regexp.charClass().range(rangeIndex);
            maximum = Math.max(maximum, Utf8.encodedLength(range.high()));
        }
        return maximum;
    }

    private static int addMaximums(List<Node> children)
    {
        int result = 0;
        for (Node child : children) {
            if (child.maximumLength == IMPOSSIBLE) {
                return IMPOSSIBLE;
            }
            if (child.maximumLength == UNBOUNDED) {
                return UNBOUNDED;
            }
            result = saturatedAdd(result, child.maximumLength);
        }
        return result;
    }

    private static int alternateMaximum(List<Node> children)
    {
        int result = IMPOSSIBLE;
        for (Node child : children) {
            if (child.maximumLength == UNBOUNDED) {
                return UNBOUNDED;
            }
            result = Math.max(result, child.maximumLength);
        }
        return result;
    }

    private static int repeatMaximum(int childMaximum, int minimumCount, int maximumCount)
    {
        if (maximumCount == 0 || childMaximum == 0) {
            return 0;
        }
        if (childMaximum == IMPOSSIBLE) {
            return minimumCount == 0 ? 0 : IMPOSSIBLE;
        }
        if (childMaximum == UNBOUNDED || maximumCount < 0) {
            return UNBOUNDED;
        }
        return saturatedMultiply(childMaximum, maximumCount);
    }

    private static int saturatedAdd(int left, int right)
    {
        if (left > Integer.MAX_VALUE - right) {
            return Integer.MAX_VALUE;
        }
        return left + right;
    }

    private static int saturatedMultiply(int value, int count)
    {
        if (value != 0 && count > Integer.MAX_VALUE / value) {
            return Integer.MAX_VALUE;
        }
        return value * count;
    }

    sealed interface Element
            permits AssertionElement,
                    GapElement,
                    LiteralElement,
                    UnsupportedElement {}

    private record AssertionElement(Assertion assertion)
            implements Element {}

    private record GapElement(Gap gap)
            implements Element {}

    private record LiteralElement(byte[] bytes)
            implements Element {}

    private enum UnsupportedElement
            implements Element
    {
        INSTANCE
    }

    private enum Assertion
    {
        BEGIN_TEXT,
        END_TEXT,
        FINAL_LINE_END,
    }

    private static final class Analyzer
            extends RegexpWalker<Node>
    {
        private static final PreVisitResult<Node> CONTINUE = new PreVisitResult<>(null, false);
        private static final int[] NO_CHILD_LENGTHS = {};

        @Override
        protected PreVisitResult<Node> preVisit(Regexp regexp, Node parentArgument)
        {
            return CONTINUE;
        }

        @Override
        protected Node postVisit(Regexp regexp, Node parentArgument, Node preArgument, List<Node> children)
        {
            return analyzeNode(regexp, children);
        }

        private static Node analyzeNode(Regexp regexp, List<Node> children)
        {
            int[] childLengths = children.isEmpty() ? NO_CHILD_LENGTHS : new int[children.size()];
            boolean hasCaptures = regexp.op() == RegexpOp.CAPTURE;
            boolean hasFoldCaseLiteral = (regexp.op() == RegexpOp.LITERAL || regexp.op() == RegexpOp.LITERAL_STRING) &&
                    (regexp.parseFlags() & FOLD_CASE) != 0;
            boolean hasNonGreedyRepetition = switch (regexp.op()) {
                case STAR, PLUS, QUEST, REPEAT -> (regexp.parseFlags() & NON_GREEDY) != 0;
                default -> false;
            };
            boolean hasUnsupportedPositionAssertions = isUnsupportedPositionAssertion(regexp);
            boolean childCanConsumeLineFeed = false;
            for (int childIndex = 0; childIndex < children.size(); childIndex++) {
                Node child = children.get(childIndex);
                childLengths[childIndex] = child.encodedLength;
                hasCaptures |= child.hasCaptures;
                hasFoldCaseLiteral |= child.hasFoldCaseLiteral;
                hasNonGreedyRepetition |= child.hasNonGreedyRepetition;
                hasUnsupportedPositionAssertions |= child.hasUnsupportedPositionAssertions;
                childCanConsumeLineFeed |= child.canConsumeLineFeed;
            }

            int encodedLength = MatchLength.combine(regexp, childLengths);
            int maximumLength = maximumLength(regexp, children);

            return new Node(
                    encodedLength,
                    maximumLength,
                    hasCaptures,
                    hasFoldCaseLiteral,
                    hasNonGreedyRepetition,
                    hasUnsupportedPositionAssertions,
                    canConsumeLineFeed(regexp, childCanConsumeLineFeed),
                    elements(regexp, children));
        }

        @Override
        protected Node shortVisit(Regexp regexp, Node parentArgument)
        {
            throw new AssertionError("short visit is not used");
        }
    }

    record Node(
            int encodedLength,
            int maximumLength,
            boolean hasCaptures,
            boolean hasFoldCaseLiteral,
            boolean hasNonGreedyRepetition,
            boolean hasUnsupportedPositionAssertions,
            boolean canConsumeLineFeed,
            List<Element> elements) {}
}
