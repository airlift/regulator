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

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.airlift.regulator.Regexp.MAX_REPEAT;
import static io.airlift.regulator.RegexpParserSupport.addFoldedRangeLatin1;
import static io.airlift.regulator.RegexpParserSupport.addGroup;
import static io.airlift.regulator.RegexpParserSupport.decodeHexDigit;
import static io.airlift.regulator.RegexpParserSupport.wouldExceedRepeatLimit;
import static io.airlift.regulator.UnicodeGroups.binary;
import static io.airlift.regulator.UnicodeGroups.cached;
import static io.airlift.regulator.UnicodeGroups.codePoints;
import static io.airlift.regulator.UnicodeGroups.generalCategory;
import static io.airlift.regulator.UnicodeGroups.union;
import static java.util.Objects.requireNonNull;

final class TrinoRegexpParser
{
    private TrinoRegexpParser() {}

    public static ParseResult parse(Slice pattern, int parseFlags)
    {
        requireNonNull(pattern, "pattern is null");
        if ((parseFlags & Regexp.LATIN1) == 0) {
            int invalidOffset = Utf8.firstInvalidOffset(pattern);
            if (invalidOffset >= 0) {
                throw new RegexpParseException(RegexpParseErrorCode.BAD_UTF8, null, invalidOffset);
            }
        }

        Parser parser = new Parser(pattern, parseFlags, CaseFolding.TRINO);
        Regexp parsedRegexp = parser.parseRegexp();
        if (!parser.atEnd()) {
            // Unconsumed input at the top-level is typically a stray ')'.
            if (parser.peekByte() == ')') {
                throw parser.error(RegexpParseErrorCode.UNEXPECTED_PAREN, parser.errorArgAll());
            }
            throw parser.error(RegexpParseErrorCode.INTERNAL_ERROR, parser.errorArgAtCurrent());
        }
        // Parse-time simplification preserves counted repetitions. The compiler performs repeat
        // elimination and named-class expansion after the frontend has established Trino semantics.
        Regexp simplifiedRegexp = Simplifier.simplifyForParse(parsedRegexp, CaseFolding.TRINO);
        // Simplification can remove a group that never matches, but the group keeps its name.
        Map<Integer, String> capturingGroupNames = parser.captureNames.isEmpty() ? Map.of() : parsedRegexp.captureNames();
        return new ParseResult(simplifiedRegexp, parser.capturingGroupCount, capturingGroupNames, CaseFolding.TRINO);
    }

    @SuppressWarnings("CharUsedInArithmeticContext")
    private static final class Parser
    {
        private static final int COMMENTS = 1 << 13;
        // Joni's scan limit for the name in a POSIX class such as [:alpha:].
        private static final int POSIX_BRACKET_NAME_LIMIT = 20;
        // Joni's largest backreference number; a larger digit escape is never a backreference.
        private static final int BACKREFERENCE_NUMBER_LIMIT = 1000;

        // Capture names permit Lu, Ll, Lt, Lm, Lo, Nl, Mn, Mc, Nd, and Pc categories.
        private static final CharClass VALID_CAPTURE_NAME = buildValidCaptureNameCharClass();
        // A capture name must not start with a decimal digit, Nd.
        private static final CharClass DECIMAL_DIGIT = requireNonNull(JavaUnicodeProperties.lookup("Nd"), "missing Unicode group: Nd");
        private static final CharClass PERL_DIGITS_CLASS = buildPerlDigitsCharClass();

        private final byte[] bytes;
        private final int start;
        private final int end;
        private final CaseFolding caseFolding;
        private int index;

        private int flags;
        // Incremented at each change of case-insensitivity; a literal run never spans two scopes.
        private int foldCaseScope;
        private int capturingGroupCount;
        private boolean inQuote;
        private final Set<String> captureNames = new HashSet<>();

        Parser(Slice pattern, int flags, CaseFolding caseFolding)
        {
            this.bytes = pattern.byteArray();
            this.start = pattern.byteArrayOffset();
            this.end = start + pattern.length();
            this.caseFolding = caseFolding;
            this.index = start;
            this.flags = flags;

            if ((flags & ~Regexp.ALL_PARSE_FLAGS) != 0) {
                throw error(RegexpParseErrorCode.INTERNAL_ERROR, null);
            }
        }

        boolean atEnd()
        {
            return index >= end;
        }

        private Slice errorArgAtCurrent()
        {
            if (index >= end) {
                return null;
            }
            int width = runeByteWidthAt(index);
            if (width <= 0) {
                return null;
            }
            return Slices.wrappedBuffer(bytes, index, width);
        }

        private Slice errorArgAll()
        {
            return Slices.wrappedBuffer(bytes, start, end - start);
        }

        private int runeByteWidthAt(int offset)
        {
            if (offset >= end) {
                return 0;
            }
            if ((flags & Regexp.LATIN1) != 0) {
                return 1;
            }
            long decoded = Utf8.decode(bytes, offset, end);
            int width = Utf8.decodedWidth(decoded);
            return Math.max(width, 0);
        }

        private RegexpParseException error(RegexpParseErrorCode errorCode, Slice invalidPatternSegment)
        {
            return new RegexpParseException(errorCode, invalidPatternSegment, Math.max(index - start, 0));
        }

        private RegexpParseException unsupportedConstruct(String construct, int constructStart, int constructEnd)
        {
            return new RegexpParseException(
                    RegexpParseErrorCode.UNSUPPORTED_CONSTRUCT,
                    Slices.wrappedBuffer(bytes, constructStart, constructEnd - constructStart),
                    constructStart - start,
                    construct);
        }

        private RegexpParseException unsupportedFlag(int flag, int flagPosition)
        {
            return new RegexpParseException(
                    RegexpParseErrorCode.UNSUPPORTED_FLAG,
                    Slices.wrappedBuffer(bytes, flagPosition, 1),
                    flagPosition - start,
                    Character.toString(flag));
        }

        Regexp parseRegexp()
        {
            if ((flags & Regexp.LITERAL) != 0) {
                return parseLiteralString();
            }
            return parseExpression();
        }

        private Regexp parseLiteralString()
        {
            if (atEnd()) {
                return Regexp.emptyMatch(flags);
            }
            ExpressionBuilder expression = new ExpressionBuilder(flags);
            while (!atEnd()) {
                expression.add(parseLiteral(), foldCaseScope);
            }
            return expression.build();
        }

        private Regexp parseExpression()
        {
            Deque<GroupFrame> groups = new ArrayDeque<>();
            groups.push(GroupFrame.root(flags));

            while (!atEnd()) {
                skipIgnored();
                if (atEnd()) {
                    break;
                }
                GroupFrame group = groups.peek();
                if (tryConsumeEmptyQuoteAtom()) {
                    group.expression().add(parseRepeatSuffix(Regexp.emptyMatch(flags)), foldCaseScope);
                    continue;
                }
                if (tryConsumeQuoteDirective()) {
                    continue;
                }
                int nextByte = peekByte();

                if (!inQuote && nextByte == '|') {
                    consumeByte('|');
                    group.expression().nextAlternative(flags);
                    continue;
                }

                if (!inQuote && nextByte == ')') {
                    closeIsolatedOptions(groups);
                    if (groups.size() == 1) {
                        break;
                    }

                    consumeByte(')');
                    GroupFrame completed = groups.pop();
                    ExpressionBuilder content = completed.expression();
                    setFlags(completed.restoreFlags());
                    Regexp atom;
                    if (!completed.capturing() && !content.hasAlternation()) {
                        // A noncapturing group without alternation only groups, so its contents
                        // act as if written in its place. Its elements are already in the
                        // enclosing sequence and stay there unless a quantifier other than {1}
                        // follows.
                        atom = parseRepeatSuffix(null, content);
                    }
                    else {
                        atom = content.build();
                        if (completed.capturing()) {
                            atom = Regexp.capture(completed.groupFlags(), atom, completed.captureIndex(), completed.name());
                        }
                        atom = parseRepeatSuffix(atom);
                    }
                    if (atom != null) {
                        groups.peek().expression().add(atom, foldCaseScope);
                    }
                    continue;
                }

                if (!inQuote && nextByte == '(') {
                    groups.push(parseGroupStart(group.expression()));
                    continue;
                }

                Regexp atom = parseRepeat();
                if (atom != null) {
                    group.expression().add(atom, foldCaseScope);
                }
            }

            closeIsolatedOptions(groups);
            if (groups.size() != 1) {
                throw error(RegexpParseErrorCode.MISSING_PAREN, errorArgAll());
            }
            return groups.pop().expression().build();
        }

        // Ends the groups opened by isolated options, such as (?i), at the end of their enclosing
        // group or of the pattern. They close without restoring flags, because the enclosing
        // group's ) restores them, or the pattern ends. Without alternation, their contents already
        // stay in the enclosing sequence, so within a fully folded literal run only a change of
        // case-insensitivity ends the run. A group holding alternation becomes one alternation,
        // which ends the run.
        private void closeIsolatedOptions(Deque<GroupFrame> groups)
        {
            while (groups.peek().isolatedOption()) {
                ExpressionBuilder content = groups.pop().expression();
                if (content.hasAlternation()) {
                    groups.peek().expression().add(content.build(), foldCaseScope);
                }
            }
        }

        // The alternatives of one group, or of the whole pattern, written into a sequence. A
        // noncapturing group shares the sequence of the expression around it and marks where its
        // content starts, so it takes that content out into one atom only when it must: at its
        // alternation, or when a quantifier other than {1} follows it.
        private static final class ExpressionBuilder
        {
            private final Sequence sequence;
            private final int elementMark;
            private final int runeMark;
            private final int expressionFlags;
            private int concatenationFlags;
            private List<Regexp> alternatives;

            ExpressionBuilder(int flags)
            {
                this(new Sequence(), flags);
            }

            // A noncapturing group shares the enclosing sequence and marks where its content
            // begins.
            ExpressionBuilder(ExpressionBuilder enclosing, int flags)
            {
                this(enclosing.sequence, flags);
            }

            private ExpressionBuilder(Sequence sequence, int flags)
            {
                this.sequence = sequence;
                this.elementMark = sequence.elementCount();
                this.runeMark = sequence.runeCount();
                this.expressionFlags = flags;
                this.concatenationFlags = flags;
            }

            void add(Regexp atom, int foldCaseScope)
            {
                sequence.add(atom, foldCaseScope);
            }

            // Whether the expression has alternation; without it, its elements can stay in an
            // enclosing sequence.
            boolean hasAlternation()
            {
                return alternatives != null;
            }

            void nextAlternative(int flags)
            {
                if (alternatives == null) {
                    alternatives = new ArrayList<>();
                }
                alternatives.add(sequence.removeFrom(elementMark, runeMark, concatenationFlags));
                concatenationFlags = flags;
            }

            Regexp build()
            {
                Regexp concatenation = sequence.removeFrom(elementMark, runeMark, concatenationFlags);
                if (alternatives == null) {
                    return concatenation;
                }
                alternatives.add(concatenation);
                return Regexp.alternate(expressionFlags, alternatives);
            }
        }

        // The elements of one capture, or of the whole pattern, including those of the
        // noncapturing groups inside it. A literal run stays a range of pending runes until its
        // elements are removed into a node, so each rune and element is materialized once, however
        // deeply groups nest.
        private static final class Sequence
        {
            // A null element is the literal run of runes [runStarts[i], runEnds[i]), written in
            // fold-case scope runScopes[i].
            private final List<Regexp> elements = new ArrayList<>();
            private int[] runStarts = new int[16];
            private int[] runEnds = new int[16];
            private int[] runScopes = new int[16];
            // Each rune keeps the flags of the literal it came from. Kept as plain arrays so
            // accumulating a long literal does not box each rune.
            private int[] runes = new int[16];
            private int[] runeFlags = new int[16];
            private int runeCount;

            int elementCount()
            {
                return elements.size();
            }

            int runeCount()
            {
                return runeCount;
            }

            // Literals written next to each other, including through escapes, quotes, {1}, and the
            // boundaries of noncapturing groups with no alternation and no quantifier other than
            // {1}, form one run, which full case folding matches as one sequence. Such a group's
            // contents act as if written in its place. Only the parser builds these runs; the
            // simplifier joins two only when no fold can cross their boundary.
            void add(Regexp atom, int foldCaseScope)
            {
                if (atom.op() == RegexpOp.EMPTY_MATCH) {
                    // An empty quote consumes no text, so it does not end a run.
                    return;
                }
                if (atom.op() != RegexpOp.LITERAL) {
                    elements.add(atom);
                    return;
                }

                int last = elements.size() - 1;
                if (last < 0 || elements.get(last) != null || runScopes[last] != foldCaseScope || !sameLiteralFlags(runeFlags[runStarts[last]], atom.parseFlags())) {
                    last++;
                    if (last >= runStarts.length) {
                        int capacity = Math.max(last + 1, runStarts.length * 2);
                        runStarts = Arrays.copyOf(runStarts, capacity);
                        runEnds = Arrays.copyOf(runEnds, capacity);
                        runScopes = Arrays.copyOf(runScopes, capacity);
                    }
                    elements.add(null);
                    runStarts[last] = runeCount;
                    runScopes[last] = foldCaseScope;
                }
                appendRune(atom.rune(), atom.parseFlags());
                runEnds[last] = runeCount;
            }

            // Removes the elements and runes written since the marks into one node. A literal run
            // that crosses the rune mark keeps its earlier runes.
            Regexp removeFrom(int elementMark, int runeMark, int concatenationFlags)
            {
                List<Regexp> removed = new ArrayList<>(elements.size() - elementMark + 1);
                int crossing = elementMark - 1;
                if (crossing >= 0 && elements.get(crossing) == null) {
                    if (runEnds[crossing] > runeMark) {
                        removed.add(literal(runeMark, runEnds[crossing]));
                    }
                    runEnds[crossing] = runeMark;
                }
                for (int i = elementMark; i < elements.size(); i++) {
                    Regexp element = elements.get(i);
                    removed.add(element == null ? literal(runStarts[i], runEnds[i]) : element);
                }
                elements.subList(elementMark, elements.size()).clear();
                runeCount = runeMark;

                if (removed.isEmpty()) {
                    return Regexp.emptyMatch(concatenationFlags);
                }
                if (removed.size() == 1) {
                    return removed.getFirst();
                }
                return Regexp.concat(concatenationFlags, removed);
            }

            private Regexp literal(int start, int end)
            {
                if (end - start == 1) {
                    return Regexp.literal(runeFlags[start], runes[start]);
                }
                return Regexp.literalString(runeFlags[start], Arrays.copyOfRange(runes, start, end));
            }

            private void appendRune(int rune, int flags)
            {
                if (runeCount == runes.length) {
                    runes = Arrays.copyOf(runes, runeCount * 2);
                    runeFlags = Arrays.copyOf(runeFlags, runeCount * 2);
                }
                runes[runeCount] = rune;
                runeFlags[runeCount] = flags;
                runeCount++;
            }

            // Within one fold-case scope, only full-fold literals may ignore other flag changes.
            private static boolean sameLiteralFlags(int runFlags, int atomFlags)
            {
                return runFlags == atomFlags || (runFlags & atomFlags & Regexp.FULL_CASE_FOLD) != 0;
            }
        }

        private record GroupFrame(
                ExpressionBuilder expression,
                int restoreFlags,
                int groupFlags,
                boolean capturing,
                int captureIndex,
                Slice name,
                boolean isolatedOption)
        {
            static GroupFrame root(int flags)
            {
                return new GroupFrame(new ExpressionBuilder(flags), flags, flags, false, -1, null, false);
            }
        }

        // repeat := atom (('*' | '+' | '?' | '{m,n}') '?'?)*
        private Regexp parseRepeat()
        {
            return parseRepeatSuffix(parseAtom());
        }

        private Regexp parseRepeatSuffix(Regexp atom)
        {
            return parseRepeatSuffix(atom, null);
        }

        // When grouping is non-null, atom is null and the group's contents remain in the enclosing
        // sequence. Return null unless a quantifier other than an exact repeat of one turns those
        // contents into an atom. When grouping is null, apply quantifiers to the supplied atom and
        // return the result.
        private Regexp parseRepeatSuffix(Regexp atom, ExpressionBuilder grouping)
        {
            boolean appliedRepeat = false;
            boolean separatedFromPreviousRepeat = false;

            while (!atEnd()) {
                int indexBeforeIgnoredText = index;
                skipIgnored();
                separatedFromPreviousRepeat |= index != indexBeforeIgnoredText;
                if (atEnd()) {
                    break;
                }
                // An immediate \Q\E is an empty atom, so its following quantifier must not
                // modify the preceding atom. Let the expression parser consume that atom.
                if (isEmptyQuoteAtom()) {
                    break;
                }
                if (tryConsumeQuoteDirective()) {
                    // \Q and \E are syntax; they should not block repetition parsing.
                    continue;
                }
                if (inQuote) {
                    break;
                }
                int nextByte = peekByte();
                if (nextByte == '*' || nextByte == '+' || nextByte == '?' || nextByte == '{') {
                    if (nextByte == '{' && !isValidRepeatBrace()) {
                        break;
                    }

                    // Stacked repetition operators are a syntax error in Perl extensions mode.
                    // In non-Perl extensions mode, they are allowed and will be simplified later.
                    if (appliedRepeat && !separatedFromPreviousRepeat && (flags & Regexp.PERL_EXTENSIONS) != 0) {
                        throw error(RegexpParseErrorCode.BAD_REPEAT_OP, errorArgAtCurrent());
                    }
                    atom = applyRepeat(atom, grouping);
                    if (atom != null) {
                        grouping = null;
                    }
                    appliedRepeat = true;
                    separatedFromPreviousRepeat = false;
                    continue;
                }
                break;
            }

            return atom;
        }

        private boolean isValidRepeatBrace()
        {
            // Joni treats braces as repetition syntax only when the complete interval is valid.
            // Returns false if the syntax is invalid, causing '{' to be treated as literal.
            int position = index + 1;

            // First number must exist and start with digit
            if (position >= end) {
                return false;
            }
            int firstByte = bytes[position] & 0xFF;
            if (firstByte < '0' || firstByte > '9') {
                return false;
            }

            // Skip first number digits
            while (position < end && (bytes[position] & 0xFF) >= '0' && (bytes[position] & 0xFF) <= '9') {
                position++;
            }

            if (position >= end) {
                return false;
            }

            int separatorByte = bytes[position] & 0xFF;
            if (separatorByte == ',') {
                position++;
                if (position >= end) {
                    return false;
                }
                int secondNumberByte = bytes[position] & 0xFF;
                separatorByte = secondNumberByte;
                if (secondNumberByte != '}') {
                    // Second number must exist and be valid
                    if (secondNumberByte < '0' || secondNumberByte > '9') {
                        return false;
                    }
                    // Skip second number digits
                    while (position < end && (bytes[position] & 0xFF) >= '0' && (bytes[position] & 0xFF) <= '9') {
                        position++;
                    }
                    if (position >= end) {
                        return false;
                    }
                    separatorByte = bytes[position] & 0xFF;
                }
            }

            return separatorByte == '}';
        }

        // When grouping is non-null, atom is null and the group's contents remain in the enclosing
        // sequence. Return null if the quantifier is an exact repeat of one, which leaves them
        // there; otherwise turn those contents into the repeated atom. When grouping is null,
        // apply the quantifier to the supplied atom and return the result.
        private Regexp applyRepeat(Regexp atom, ExpressionBuilder grouping)
        {
            if (atom == null && grouping == null) {
                throw error(RegexpParseErrorCode.REPEAT_ARGUMENT, errorArgAtCurrent());
            }

            int op = consumeByte();
            boolean greedyByDefault = (flags & Regexp.NON_GREEDY) == 0;

            int min = 0;
            int max = -1;
            RepetitionOperator repetitionOperator;

            if (op == '*') {
                repetitionOperator = RepetitionOperator.STAR;
            }
            else if (op == '+') {
                repetitionOperator = RepetitionOperator.PLUS;
                min = 1;
            }
            else if (op == '?') {
                repetitionOperator = RepetitionOperator.QUEST;
                max = 1;
            }
            else if (op == '{') {
                repetitionOperator = RepetitionOperator.REPEAT;
                int[] repetitionCounts = parseRepetitionCounts();
                min = repetitionCounts[0];
                max = repetitionCounts[1];
            }
            else {
                throw error(RegexpParseErrorCode.INTERNAL_ERROR, null);
            }

            boolean nonGreedy = !greedyByDefault;
            // Non-greedy operators (e.g. "*?") are only supported under Perl extensions.
            if ((flags & Regexp.PERL_EXTENSIONS) != 0 && !atEnd() && peekByte() == '?') {
                consumeByte('?');
                nonGreedy = greedyByDefault;
            }
            else if ((flags & Regexp.PERL_EXTENSIONS) != 0 && !atEnd() && peekByte() == '+') {
                throw unsupportedConstruct("possessive quantifiers", index, index + 1);
            }

            // A literal, an empty atom, or a group's contents repeated exactly once stays part of
            // its literal run, so full case folding sees the whole sequence: (?i:ff{1}) matches
            // U+FB00.
            if (repetitionOperator == RepetitionOperator.REPEAT && min == 1 && max == 1 &&
                    (grouping != null || atom.op() == RegexpOp.LITERAL || atom.op() == RegexpOp.EMPTY_MATCH)) {
                return atom;
            }
            if (grouping != null) {
                atom = grouping.build();
            }

            int nodeFlags = nonGreedy ? (flags | Regexp.NON_GREEDY) : (flags & ~Regexp.NON_GREEDY);

            Regexp repetition = switch (repetitionOperator) {
                case STAR -> Regexp.rawUnary(RegexpOp.STAR, nodeFlags, atom);
                case PLUS -> Regexp.rawUnary(RegexpOp.PLUS, nodeFlags, atom);
                case QUEST -> Regexp.rawUnary(RegexpOp.QUEST, nodeFlags, atom);
                case REPEAT -> {
                    // Match upstream: reject nested repetition whose worst-case expansion would exceed MAX_REPEAT.
                    // See re2/parse.cc PushRepetition() and RepetitionWalker.
                    if (wouldExceedRepeatLimit(atom, min, max)) {
                        throw error(RegexpParseErrorCode.REPEAT_SIZE, null);
                    }
                    yield Regexp.repeat(nodeFlags, atom, min, max);
                }
            };
            return reduceNestedPopularRepetition(repetition);
        }

        private enum RepetitionOperator
        {
            STAR, PLUS, QUEST, REPEAT
        }

        private enum RepetitionReduction
        {
            KEEP,
            REMOVE_PARENT,
            GREEDY_STAR,
            RELUCTANT_STAR,
            RELUCTANT_QUEST,
            GREEDY_PLUS_RELUCTANT_QUEST,
            RELUCTANT_PLUS_GREEDY_QUEST,
        }

        // Rows are child ?, *, +, ??, *?, +?; columns are the enclosing quantifier in the same
        // order. Joni applies this table only while parsing directly nested popular quantifiers.
        private static final RepetitionReduction[][] NESTED_POPULAR_REPETITION_REDUCTIONS = {
                {RepetitionReduction.REMOVE_PARENT, RepetitionReduction.GREEDY_STAR, RepetitionReduction.GREEDY_STAR, RepetitionReduction.RELUCTANT_QUEST, RepetitionReduction.RELUCTANT_STAR, RepetitionReduction.KEEP},
                {RepetitionReduction.REMOVE_PARENT, RepetitionReduction.REMOVE_PARENT, RepetitionReduction.REMOVE_PARENT, RepetitionReduction.GREEDY_PLUS_RELUCTANT_QUEST, RepetitionReduction.GREEDY_PLUS_RELUCTANT_QUEST, RepetitionReduction.REMOVE_PARENT},
                {RepetitionReduction.GREEDY_STAR, RepetitionReduction.GREEDY_STAR, RepetitionReduction.REMOVE_PARENT, RepetitionReduction.KEEP, RepetitionReduction.GREEDY_PLUS_RELUCTANT_QUEST, RepetitionReduction.REMOVE_PARENT},
                {RepetitionReduction.REMOVE_PARENT, RepetitionReduction.RELUCTANT_STAR, RepetitionReduction.RELUCTANT_STAR, RepetitionReduction.REMOVE_PARENT, RepetitionReduction.RELUCTANT_STAR, RepetitionReduction.RELUCTANT_STAR},
                {RepetitionReduction.REMOVE_PARENT, RepetitionReduction.REMOVE_PARENT, RepetitionReduction.REMOVE_PARENT, RepetitionReduction.REMOVE_PARENT, RepetitionReduction.REMOVE_PARENT, RepetitionReduction.REMOVE_PARENT},
                {RepetitionReduction.KEEP, RepetitionReduction.RELUCTANT_PLUS_GREEDY_QUEST, RepetitionReduction.REMOVE_PARENT, RepetitionReduction.RELUCTANT_STAR, RepetitionReduction.RELUCTANT_STAR, RepetitionReduction.REMOVE_PARENT},
        };

        private static Regexp reduceNestedPopularRepetition(Regexp parent)
        {
            int parentQuantifier = popularQuantifierIndex(parent);
            Regexp child = parent.child(0);
            int childQuantifier = popularQuantifierIndex(child);
            if (parentQuantifier < 0 || childQuantifier < 0) {
                return parent;
            }

            Regexp repeatedExpression = child.child(0);
            return switch (NESTED_POPULAR_REPETITION_REDUCTIONS[childQuantifier][parentQuantifier]) {
                case KEEP -> parent;
                case REMOVE_PARENT -> child;
                case GREEDY_STAR -> unary(RegexpOp.STAR, parent.parseFlags(), false, repeatedExpression);
                case RELUCTANT_STAR -> unary(RegexpOp.STAR, parent.parseFlags(), true, repeatedExpression);
                case RELUCTANT_QUEST -> unary(RegexpOp.QUEST, parent.parseFlags(), true, repeatedExpression);
                case GREEDY_PLUS_RELUCTANT_QUEST -> unary(
                        RegexpOp.QUEST,
                        parent.parseFlags(),
                        true,
                        unary(RegexpOp.PLUS, child.parseFlags(), false, repeatedExpression));
                case RELUCTANT_PLUS_GREEDY_QUEST -> unary(
                        RegexpOp.QUEST,
                        parent.parseFlags(),
                        false,
                        unary(RegexpOp.PLUS, child.parseFlags(), true, repeatedExpression));
            };
        }

        private static int popularQuantifierIndex(Regexp regexp)
        {
            boolean reluctant = (regexp.parseFlags() & Regexp.NON_GREEDY) != 0;
            RepetitionOperator operator = switch (regexp.op()) {
                case QUEST -> RepetitionOperator.QUEST;
                case STAR -> RepetitionOperator.STAR;
                case PLUS -> RepetitionOperator.PLUS;
                case REPEAT -> {
                    if (regexp.min() == 0 && regexp.max() == 1) {
                        yield RepetitionOperator.QUEST;
                    }
                    if (regexp.min() == 0 && regexp.max() == -1) {
                        yield RepetitionOperator.STAR;
                    }
                    if (regexp.min() == 1 && regexp.max() == -1) {
                        yield RepetitionOperator.PLUS;
                    }
                    yield RepetitionOperator.REPEAT;
                }
                default -> RepetitionOperator.REPEAT;
            };
            return switch (operator) {
                case QUEST -> reluctant ? 3 : 0;
                case STAR -> reluctant ? 4 : 1;
                case PLUS -> reluctant ? 5 : 2;
                case REPEAT -> -1;
            };
        }

        private static Regexp unary(RegexpOp operator, int flags, boolean reluctant, Regexp child)
        {
            int normalizedFlags = reluctant ? flags | Regexp.NON_GREEDY : flags & ~Regexp.NON_GREEDY;
            return Regexp.rawUnary(operator, normalizedFlags, child);
        }

        private int[] parseRepetitionCounts()
        {
            int minRepetitions = parseDecimal();
            int maxRepetitions = minRepetitions;

            if (atEnd()) {
                throw error(RegexpParseErrorCode.MISSING_BRACKET, Slices.wrappedBuffer(bytes, index - 1, 1));
            }

            if (peekByte() == ',') {
                consumeByte(',');
                if (peekByte() == '}') {
                    maxRepetitions = -1;
                }
                else {
                    maxRepetitions = parseDecimal();
                }
            }

            if (atEnd() || consumeByte() != '}') {
                throw error(RegexpParseErrorCode.MISSING_BRACKET, errorArgAtCurrent());
            }

            if (minRepetitions < 0 || maxRepetitions < -1) {
                throw error(RegexpParseErrorCode.REPEAT_ARGUMENT, null);
            }
            if (maxRepetitions != -1 && maxRepetitions < minRepetitions) {
                throw error(RegexpParseErrorCode.REPEAT_ARGUMENT, null);
            }

            if (minRepetitions > MAX_REPEAT || maxRepetitions > MAX_REPEAT) {
                throw error(RegexpParseErrorCode.REPEAT_SIZE, null);
            }

            return new int[] {minRepetitions, maxRepetitions};
        }

        // atom := '(' ... ')' | '[' ... ']' | '.' | '^' | '$' | '\' escape | literal
        private Regexp parseAtom()
        {
            // Perl quoted literals: \Q...\E
            // These are syntax directives; they do not correspond to regexp operations themselves.
            do {
                skipIgnored();
                if (atEnd()) {
                    return null;
                }
            }
            while (tryConsumeQuoteDirective());

            int nextByte = peekByte();
            if (inQuote) {
                return parseLiteral();
            }

            // Repetition operators are not valid in atom position.
            // See upstream re2/parse.cc PushRepeatOp()/PushRepetition().
            if (nextByte == '*' || nextByte == '+' || nextByte == '?' || (nextByte == '{' && isValidRepeatBrace())) {
                throw error(RegexpParseErrorCode.REPEAT_ARGUMENT, Slices.wrappedBuffer(bytes, index, 1));
            }

            if (nextByte == '[') {
                return parseCharClass();
            }
            if (nextByte == '.') {
                consumeByte('.');
                if ((flags & Regexp.DOT_MATCHES_NEWLINE) != 0 && (flags & Regexp.NEVER_NEWLINE) == 0) {
                    return Regexp.anyChar(flags);
                }
                CharClassBuilder classBuilder = new CharClassBuilder();
                int maxRune = Regexp.maxRune(flags);
                if (maxRune < '\n') {
                    classBuilder.addRange(0, maxRune);
                }
                else {
                    if ('\n' > 0) {
                        classBuilder.addRange(0, '\n' - 1);
                    }
                    if ('\n' + 1 <= maxRune) {
                        classBuilder.addRange('\n' + 1, maxRune);
                    }
                }
                return Regexp.charClass(flags, classBuilder.toCharClass());
            }
            if (nextByte == '^') {
                consumeByte('^');
                return ((flags & Regexp.ONE_LINE) != 0) ? Regexp.beginText(flags) : Regexp.beginLine(flags | Regexp.TRINO_LINE);
            }
            if (nextByte == '$') {
                consumeByte('$');
                if ((flags & Regexp.ONE_LINE) != 0) {
                    return Regexp.endText(flags | Regexp.WAS_DOLLAR | Regexp.FINAL_LINE_END);
                }
                return Regexp.endLine(flags);
            }
            if (nextByte == '\\') {
                consumeByte('\\');
                return parseEscape();
            }

            return parseLiteral();
        }

        private boolean tryConsumeQuoteDirective()
        {
            if ((flags & Regexp.PERL_EXTENSIONS) == 0) {
                return false;
            }
            if (peekByte() != '\\' || index + 1 >= end) {
                return false;
            }
            int next = bytes[index + 1] & 0xFF;
            if (!inQuote && next == 'Q') {
                index += 2;
                inQuote = true;
                return true;
            }
            if (inQuote && next == 'E') {
                index += 2;
                inQuote = false;
                return true;
            }
            return false;
        }

        private boolean tryConsumeEmptyQuoteAtom()
        {
            if (!isEmptyQuoteAtom()) {
                return false;
            }
            index += 4;
            return true;
        }

        private boolean isEmptyQuoteAtom()
        {
            return (flags & Regexp.PERL_EXTENSIONS) != 0 &&
                    !inQuote &&
                    index + 3 < end &&
                    bytes[index] == '\\' &&
                    bytes[index + 1] == 'Q' &&
                    bytes[index + 2] == '\\' &&
                    bytes[index + 3] == 'E';
        }

        private GroupFrame parseGroupStart(ExpressionBuilder enclosing)
        {
            int groupFlags = flags;
            int groupStart = index;
            consumeByte('(');
            boolean capturing = (flags & Regexp.NEVER_CAPTURE) == 0;
            Slice name = null;
            int captureIndex = -1;

            if (!atEnd() && peekByte() == '?') {
                if ((flags & Regexp.PERL_EXTENSIONS) == 0) {
                    // Perl-style group syntax is only available under Perl extensions.
                    throw error(RegexpParseErrorCode.BAD_PERL_OP, Slices.wrappedBuffer(bytes, groupStart, 2));
                }
                consumeByte('?');
                if (!atEnd()) {
                    int c = peekByte();
                    if (c == '=' || c == '!') {
                        throw unsupportedConstruct("lookahead", groupStart, index + 1);
                    }
                    if (c == '<' && index + 1 < end) {
                        int c2 = bytes[index + 1] & 0xFF;
                        if (c2 == '=' || c2 == '!') {
                            throw unsupportedConstruct("lookbehind", groupStart, index + 2);
                        }
                    }
                    if (c == '>') {
                        throw unsupportedConstruct("atomic groups", groupStart, index + 1);
                    }
                }
                if (!atEnd() && peekByte() == ':') {
                    consumeByte(':');
                    capturing = false;
                }
                else if (!atEnd() && peekByte() == 'P') {
                    throw error(RegexpParseErrorCode.BAD_PERL_OP, Slices.wrappedBuffer(bytes, groupStart, index - groupStart + 1));
                }
                else if (!atEnd() && peekByte() == '<') {
                    // (?<name>...)
                    consumeByte('<');
                    name = parseCaptureName(groupStart, '>');
                }
                else if (!atEnd() && peekByte() == '\'') {
                    // (?'name'...)
                    consumeByte('\'');
                    name = parseCaptureName(groupStart, '\'');
                }
                else {
                    // Inline flags like (?m), (?-m), (?s:...), (?U:...), ...
                    int saved = flags;
                    setFlags(parseInlineFlags(saved, groupStart));

                    if (atEnd()) {
                        throw error(RegexpParseErrorCode.MISSING_PAREN, errorArgAll());
                    }
                    if (peekByte() == ')') {
                        // An isolated option opens a noncapturing group that runs to the end of
                        // its enclosing group or the pattern, so a later | separates alternatives
                        // inside it, after the text before the flag: ab(?i)c|def is ab(?i:c|def).
                        consumeByte(')');
                        return new GroupFrame(new ExpressionBuilder(enclosing, flags), saved, groupFlags, false, -1, null, true);
                    }
                    if (peekByte() != ':') {
                        throw error(RegexpParseErrorCode.BAD_PERL_OP, errorArgAtCurrent());
                    }
                    consumeByte(':');
                    return new GroupFrame(new ExpressionBuilder(enclosing, flags), saved, groupFlags, false, -1, null, false);
                }
            }

            if (capturing) {
                capturingGroupCount++;
                captureIndex = capturingGroupCount;
            }
            ExpressionBuilder expression = capturing ? new ExpressionBuilder(flags) : new ExpressionBuilder(enclosing, flags);
            return new GroupFrame(expression, groupFlags, groupFlags, capturing, captureIndex, name, false);
        }

        private int parseInlineFlags(int base, int groupStart)
        {
            int inlineFlags = base;
            boolean clearing = false;

            while (!atEnd()) {
                int c = peekByte();
                if (c == ')' || c == ':') {
                    break;
                }
                if (c == '-') {
                    consumeByte('-');
                    clearing = true;
                    continue;
                }
                int rune = readRune(flags);

                switch (rune) {
                    case 'i' -> inlineFlags = clearing ? (inlineFlags & ~Regexp.FOLD_CASE) : (inlineFlags | Regexp.FOLD_CASE);
                    case 's' -> inlineFlags = clearing ? (inlineFlags & ~Regexp.DOT_MATCHES_NEWLINE) : (inlineFlags | Regexp.DOT_MATCHES_NEWLINE);
                    case 'x' -> inlineFlags = clearing ? (inlineFlags & ~COMMENTS) : (inlineFlags | COMMENTS);
                    // RE2 uses "OneLine" with inverted sense: -m means OneLine, +m means !OneLine.
                    case 'm' -> inlineFlags = clearing ? (inlineFlags | Regexp.ONE_LINE) : (inlineFlags & ~Regexp.ONE_LINE);
                    case 'd', 'u', 'U' -> throw unsupportedFlag(rune, index - 1);
                    default -> throw error(RegexpParseErrorCode.BAD_PERL_OP, Slices.wrappedBuffer(bytes, groupStart, index - groupStart));
                }
            }

            return inlineFlags;
        }

        private void setFlags(int newFlags)
        {
            if (((flags ^ newFlags) & Regexp.FOLD_CASE) != 0) {
                foldCaseScope++;
            }
            flags = newFlags;
        }

        private Slice parseCaptureName(int groupStart, int endDelimiter)
        {
            int nameStart = index;
            int nameEnd = index;
            while (nameEnd < end && (bytes[nameEnd] & 0xFF) != endDelimiter) {
                nameEnd++;
            }
            if (nameEnd >= end) {
                // Match upstream: error_arg is the remaining "(?P<name" / "(?<name" substring.
                throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE, Slices.wrappedBuffer(bytes, groupStart, end - groupStart));
            }

            int nameLength = nameEnd - nameStart;
            if (nameLength <= 0) {
                throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE, null);
            }

            // Validate name runes now that we know it's syntactically terminated by the delimiter.
            index = nameStart;
            boolean firstRune = true;
            while (index < nameEnd) {
                int rune = readUtf8RuneInName();
                if (!isValidCaptureNameRune(rune) || (firstRune && DECIMAL_DIGIT.contains(rune))) {
                    // Match upstream: error_arg is the full "(?P<...>" / "(?<...>" capture header.
                    throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE, Slices.wrappedBuffer(bytes, groupStart, (nameEnd - groupStart) + 1));
                }
                firstRune = false;
            }
            // Consume the delimiter.
            index = nameEnd + 1;
            Slice name = Slices.wrappedBuffer(bytes, nameStart, nameLength);
            if (!captureNames.add(name.toStringUtf8())) {
                throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE, Slices.wrappedBuffer(bytes, groupStart, index - groupStart));
            }
            return name;
        }

        private int readUtf8RuneInName()
        {
            long decoded = Utf8.decode(bytes, index, end);
            int width = Utf8.decodedWidth(decoded);
            if (width == 0) {
                throw error(RegexpParseErrorCode.BAD_UTF8, null);
            }
            int codePoint = Utf8.decodedCodePoint(decoded);
            if (width == 1 && codePoint == Utf8.RUNE_ERROR && (bytes[index] & 0xFF) >= 0x80) {
                throw error(RegexpParseErrorCode.BAD_UTF8, null);
            }
            index += width;
            return codePoint;
        }

        private static boolean isValidCaptureNameRune(int rune)
        {
            return VALID_CAPTURE_NAME.contains(rune);
        }

        private Regexp parseCharClass()
        {
            int nodeFlags = flags;
            int classFlags = nodeFlags & ~(Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD);
            int classStart = index;
            consumeByte('[');
            boolean negated = !atEnd() && peekByte() == '^';
            if (negated) {
                consumeByte('^');
            }

            // Joni evaluates the members, nested classes, and set operations of a bracket class
            // without case folding, then folds the finished class once. A negated class is folded
            // before its complement, so (?i)[^a] rejects A.
            CharClassBuilder characterClassBuilder = parseClassBody(classFlags, classStart);
            boolean foldCase = (nodeFlags & Regexp.FOLD_CASE) != 0;
            CharClass members = null;
            if (foldCase) {
                members = characterClassBuilder.toCharClass();
                characterClassBuilder.addCharClass(members, nodeFlags, caseFolding);
            }
            if (negated) {
                characterClassBuilder.negate();
            }
            characterClassBuilder.removeAbove(Regexp.maxRune(nodeFlags));
            Regexp base = Regexp.charClass(classFlags, characterClassBuilder.toCharClass());
            if (negated || !foldCase || (nodeFlags & Regexp.LATIN1) != 0) {
                return base;
            }

            List<Regexp> alternatives = new ArrayList<>();
            alternatives.add(base);
            // As in Joni, a positive class also matches each member's multi-character fold, after
            // its single characters and in token order, where a fold precedes any longer fold that
            // begins with it.
            for (UnicodeFullCaseFold.FoldToken token : caseFolding.multiCharacterTokens(members)) {
                alternatives.add(foldSpellings(token.foldedRunes(), 0, nodeFlags, classFlags));
            }
            return alternatives.size() == 1
                    ? base
                    : Regexp.alternate(nodeFlags, alternatives);
        }

        // Parses the members and intersections of a class through its closing bracket. Nested
        // classes keep their state on an explicit stack, as groups do in parseExpression, so
        // nesting depth is not limited by the Java stack.
        private CharClassBuilder parseClassBody(int classFlags, int classStart)
        {
            Deque<ClassFrame> classes = new ArrayDeque<>();
            // The root frame is not negated: parseCharClass negates the class after folding it.
            classes.push(new ClassFrame(classStart, false));
            while (true) {
                ClassFrame frame = classes.peek();
                if (parseClassUnion(classFlags, frame)) {
                    // A nested class is parsed as a plain set; only the outermost class folds.
                    int nestedStart = index;
                    consumeByte('[');
                    boolean negated = !atEnd() && peekByte() == '^';
                    if (negated) {
                        consumeByte('^');
                    }
                    classes.push(new ClassFrame(nestedStart, negated));
                    continue;
                }

                frame.finishUnion();
                if (isClassIntersection()) {
                    index += 2;
                    if (atEnd() || peekByte() == ']') {
                        throw unsupportedConstruct("empty character-class intersection operands", index - 2, index);
                    }
                    frame.startIntersectionOperand();
                    continue;
                }

                if (atEnd() || peekByte() != ']') {
                    throw error(RegexpParseErrorCode.MISSING_BRACKET, Slices.wrappedBuffer(bytes, frame.classStart, end - frame.classStart));
                }
                consumeByte(']');
                classes.pop();
                CharClassBuilder characterClassBuilder = frame.characterClassBuilder;
                if (classes.isEmpty()) {
                    return characterClassBuilder;
                }
                if (frame.negated) {
                    characterClassBuilder.negate();
                }
                characterClassBuilder.removeAbove(Regexp.maxRune(classFlags));
                ClassFrame parent = classes.peek();
                parent.union.addCharClass(characterClassBuilder);
                // Modern Joni keeps the state of the item before a nested class, so a dash after the
                // nested class continues that item: it is a literal after a range and starts a range
                // otherwise. The dash is also literal when no other item precedes the nested class or
                // when it ends the operand.
                if (parent.rejectDashAfterNestedClass) {
                    rejectDashAfterClassItem(frame.classStart);
                }
            }
        }

        // Adds members to the frame's current union until the union ends, or returns true at the
        // '[' of a nested class.
        private boolean parseClassUnion(int classFlags, ClassFrame frame)
        {
            CharClassBuilder characterClassBuilder = frame.union;
            while (!atEnd()) {
                int nextByte = peekByte();
                if (nextByte == ']' && !frame.first) {
                    break;
                }
                if (frame.first && isClassIntersection()) {
                    throw unsupportedConstruct("empty character-class intersection operands", index, index + 2);
                }
                if (!frame.first && isClassIntersection()) {
                    break;
                }
                frame.first = false;

                if (tryParsePosixCharClass(classFlags, characterClassBuilder)) {
                    frame.rejectDashAfterNestedClass = true;
                    continue;
                }

                if (nextByte == '[') {
                    return true;
                }
                frame.rejectDashAfterNestedClass = true;

                if (tryParseClassEscape(classFlags, characterClassBuilder)) {
                    continue;
                }

                int low = parseClassAtomRune(classFlags);
                int high = low;

                if (isRangeDash()) {
                    consumeByte('-');
                    // Joni drops a bracket after a range dash.
                    if (peekByte() == '[') {
                        throw unsupportedConstruct("character classes as range endpoints", index, index + 1);
                    }
                    high = parseClassAtomRune(classFlags);
                    if (high < low) {
                        throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, null);
                    }
                    // As in modern Joni, a dash after a range is not a range operator; the next item reads it
                    // as a literal, even after a nested class.
                    frame.rejectDashAfterNestedClass = false;
                }

                // For explicit singletons/ranges, do not implicitly filter '\n' out.
                characterClassBuilder.addRangeFlags(low, high, classFlags | Regexp.CLASS_NEWLINE, caseFolding);
            }
            return false;
        }

        // One open bracket level: the intersection of its finished operands and the union of
        // the operand being read.
        private static final class ClassFrame
        {
            private final int classStart;
            private final boolean negated;
            private CharClassBuilder characterClassBuilder;
            private CharClassBuilder union = new CharClassBuilder();
            private boolean first = true;
            private boolean rejectDashAfterNestedClass;

            ClassFrame(int classStart, boolean negated)
            {
                this.classStart = classStart;
                this.negated = negated;
            }

            void finishUnion()
            {
                if (characterClassBuilder == null) {
                    characterClassBuilder = union;
                }
                else {
                    characterClassBuilder.retainAll(union);
                }
            }

            void startIntersectionOperand()
            {
                union = new CharClassBuilder();
                first = true;
                rejectDashAfterNestedClass = false;
            }
        }

        // Matches whole-character spellings of the remaining folded suffix, except the complete
        // sequence's single-character sources, which the caller's base alternative handles. Folds
        // are at most three runes long, so the expansion is bounded. See
        // Compiler.fullCaseFoldLiteral for the corresponding literal expansion.
        private Regexp foldSpellings(int[] folded, int position, int nodeFlags, int classFlags)
        {
            List<Regexp> branches = new ArrayList<>();
            branches.add(spellingStep(caseFolding.simpleFoldClass(folded[position]), folded, position + 1, nodeFlags, classFlags));
            for (UnicodeFullCaseFold.FoldToken token : caseFolding.multiCharacterTokensStartingWith(folded[position])) {
                int next = position + token.foldedRunes().length;
                boolean wholeSequence = position == 0 && next == folded.length;
                if (token.matches(folded, position) && !wholeSequence) {
                    branches.add(spellingStep(token.sourceRunes(), folded, next, nodeFlags, classFlags));
                }
            }
            return branches.size() == 1
                    ? branches.getFirst()
                    : Regexp.alternate(nodeFlags, branches);
        }

        private Regexp spellingStep(CharClass characters, int[] folded, int next, int nodeFlags, int classFlags)
        {
            Regexp step = Regexp.charClass(classFlags, characters);
            if (next == folded.length) {
                return step;
            }
            return Regexp.concat(nodeFlags, List.of(step, foldSpellings(folded, next, nodeFlags, classFlags)));
        }

        private void skipIgnored()
        {
            if (inQuote || (flags & COMMENTS) == 0) {
                return;
            }
            while (!atEnd()) {
                int current = peekByte();
                if (isAsciiPatternWhitespace(current)) {
                    consumeByte();
                    continue;
                }
                if (current != '#') {
                    return;
                }
                do {
                    consumeByte();
                }
                while (!atEnd() && peekByte() != '\n' && peekByte() != '\r');
            }
        }

        private static boolean isAsciiPatternWhitespace(int value)
        {
            return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f';
        }

        private boolean isClassIntersection()
        {
            return index + 1 < end && bytes[index] == '&' && bytes[index + 1] == '&';
        }

        private boolean isRangeDash()
        {
            // A dash that continues a range rather than ending the class or an intersection operand.
            return !atEnd() && peekByte() == '-' && !dashEndsClassOperand();
        }

        private boolean dashEndsClassOperand()
        {
            // Joni reads a dash before the closing bracket or an intersection operator as a literal; a dash
            // that ends the pattern is left for the missing-bracket error.
            return index + 1 >= end ||
                    bytes[index + 1] == ']' ||
                    (index + 2 < end && bytes[index + 1] == '&' && bytes[index + 2] == '&');
        }

        private boolean isClassEscape()
        {
            if (peekByte() != '\\' || index + 1 >= end) {
                return false;
            }
            // Only a braced \p or \P is a property here; Joni reads a brace-less one as the literal letter.
            return switch (bytes[index + 1] & 0xFF) {
                case 'd', 'D', 's', 'S', 'w', 'W' -> true;
                case 'p', 'P' -> index + 2 < end && bytes[index + 2] == '{';
                default -> false;
            };
        }

        private boolean tryParseClassEscape(int nodeFlags, CharClassBuilder characterClassBuilder)
        {
            if (!isClassEscape()) {
                return false;
            }

            int escapeStart = index;
            int escapedByte = bytes[index + 1] & 0xFF;
            CharClass characterClass = switch (escapedByte) {
                case 'd' -> perlDigitsCharClass(nodeFlags, false);
                case 'D' -> perlDigitsCharClass(nodeFlags, true);
                case 's' -> perlSpacesCharClass(nodeFlags, false);
                case 'S' -> perlSpacesCharClass(nodeFlags, true);
                case 'w' -> perlWordCharClass(nodeFlags, false);
                case 'W' -> perlWordCharClass(nodeFlags, true);
                default -> null;
            };
            if (characterClass != null) {
                index += 2;
                addGroup(characterClassBuilder, characterClass, 1, nodeFlags, caseFolding);
                rejectDashAfterClassItem(escapeStart);
                return true;
            }

            // Otherwise isClassEscape found a braced \p or \P.
            index += 2;
            parseUnicodeGroupInto(characterClassBuilder, (escapedByte == 'P') ? -1 : 1, nodeFlags, escapeStart);
            rejectDashAfterClassItem(escapeStart);
            return true;
        }

        private void rejectDashAfterClassItem(int itemStart)
        {
            // Joni reads a dash after a class escape, property, POSIX class, or a nested class that follows
            // one of those or a single character as a range from the last single character before it, or
            // from U+0000, unless the dash ends the class or an intersection operand.
            if (isRangeDash()) {
                throw unsupportedConstruct("character classes as range endpoints", itemStart, index);
            }
        }

        private static CharClass buildValidCaptureNameCharClass()
        {
            CharClassBuilder captureNameClassBuilder = new CharClassBuilder();
            // As in upstream, these are added with NoParseFlags (0).
            for (String group : new String[] {"Lu", "Ll", "Lt", "Lm", "Lo", "Nl", "Mn", "Mc", "Nd", "Pc"}) {
                CharClass characterClass = JavaUnicodeProperties.lookup(group);
                if (characterClass == null) {
                    throw new IllegalStateException("missing Unicode group: " + group);
                }
                addGroup(captureNameClassBuilder, characterClass, 1, 0, CaseFolding.TRINO);
            }
            return captureNameClassBuilder.toCharClass();
        }

        private boolean tryParsePosixCharClass(int parseFlags, CharClassBuilder characterClassBuilder)
        {
            if (peekByte() != '[' || index + 1 >= end || bytes[index + 1] != ':') {
                return false;
            }

            int startIndex = index;
            index += 2; // "[:"

            boolean negate = false;
            if (!atEnd() && peekByte() == '^') {
                consumeByte('^');
                negate = true;
            }

            int nameStart = index;
            while (!atEnd()) {
                int c = peekByte();
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                    consumeByte();
                    continue;
                }
                break;
            }
            int nameEnd = index;
            if (nameEnd == nameStart || atEnd() || peekByte() != ':' || index + 1 >= end || bytes[index + 1] != ']') {
                // When an unescaped :] comes before the next unescaped ], Joni reads [: as a POSIX bracket and
                // anything but a class name and :] as an error or a literal [; otherwise [: opens a nested class.
                if (hasPosixBracketEnd(startIndex + 2)) {
                    throw unsupportedConstruct("malformed POSIX classes", startIndex, startIndex + 2);
                }
                index = startIndex;
                return false;
            }

            consumeByte(':');
            consumeByte(']');

            // Joni stops looking for the end of an unknown name longer than the limit and reads a literal [.
            if (nameEnd - nameStart > POSIX_BRACKET_NAME_LIMIT) {
                throw unsupportedConstruct("malformed POSIX classes", startIndex, index);
            }

            String name = new String(bytes, nameStart, nameEnd - nameStart, StandardCharsets.US_ASCII);
            CharClass characterClass = posixCharClass(name);
            if (characterClass == null) {
                throw error(RegexpParseErrorCode.BAD_CHAR_CLASS, Slices.wrappedBuffer(bytes, startIndex, index - startIndex));
            }
            addGroup(characterClassBuilder, characterClass, negate ? -1 : 1, parseFlags, caseFolding);
            rejectDashAfterClassItem(startIndex);
            return true;
        }

        private boolean hasPosixBracketEnd(int position)
        {
            for (int current = position; current < end; current++) {
                int value = bytes[current];
                if (value == ':' && current + 1 < end && bytes[current + 1] == ']') {
                    return true;
                }
                if (value == ']') {
                    return false;
                }
                if (value == '\\') {
                    current++;
                }
            }
            return false;
        }

        private static CharClass posixCharClass(String name)
        {
            // A POSIX bracket takes only the lowercase names of Joni's POSIX brackets. Its punct adds
            // the nine ASCII symbols Trino's pinned Joni omits, a bug modern Joni fixed in 2.1.14.
            return switch (name) {
                case "punct" -> cached("trino:BracketPunct", () -> union(
                        generalCategory("P"),
                        codePoints('$', '$', '+', '+', '<', '>', '^', '^', '`', '`', '|', '|', '~', '~')));
                case "hexdigit" -> null;
                default -> posixProperty(name);
            };
        }

        // Joni's definitions of its POSIX names by lowercase name, including the hexdigit that only
        // \p{...} takes. Java's sets for the same names do not apply.
        private static CharClass posixProperty(String name)
        {
            return switch (name) {
                case "alnum" -> cached("trino:Alnum", () -> union(binary("Alphabetic"), generalCategory("Nd")));
                case "alpha" -> binary("Alphabetic");
                case "blank" -> cached("trino:Blank", () -> union(generalCategory("Zs"), codePoints(0x09, 0x09)));
                case "cntrl" -> generalCategory("Cc");
                case "digit" -> generalCategory("Nd");
                case "graph" -> posixGraph();
                case "lower" -> binary("Lowercase");
                case "print" -> cached("trino:Print", () -> union(posixGraph(), generalCategory("Zs")));
                case "punct" -> generalCategory("P");
                case "space" -> binary("White_Space");
                case "upper" -> binary("Uppercase");
                // Oniguruma's word characters, without the Join_Control that Joni 2.2.7 adds.
                case "word" -> cached("trino:Word", () -> union(binary("Alphabetic"), generalCategory("M"), generalCategory("Nd"), generalCategory("Pc")));
                case "xdigit" -> cached("trino:XDigit", () -> codePoints('0', '9', 'A', 'F', 'a', 'f'));
                case "hexdigit" -> binary("Hex_Digit");
                case "ascii" -> cached("trino:ASCII", () -> codePoints(0x00, 0x7F));
                default -> null;
            };
        }

        // Every code point that is not White_Space, Cc, Cs, or Cn.
        private static CharClass posixGraph()
        {
            return cached("trino:Graph", () -> {
                CharClassBuilder builder = UnicodeGroups.builder(union(binary("White_Space"), generalCategory("Cc"), generalCategory("Cs"), generalCategory("Cn")));
                builder.negate();
                return builder.toCharClass();
            });
        }

        private int parseClassAtomRune(int flags)
        {
            if (atEnd()) {
                throw error(RegexpParseErrorCode.BAD_CHAR_CLASS, null);
            }
            if (peekByte() == '\\') {
                int sequenceStart = index;
                consumeByte('\\');
                int escapedByte = consumeByte();
                // Follow Joni: decode known escape sequences, read any other escaped ASCII character as itself,
                // and reject the rest. No backreference can appear here, so every octal digit starts an octal
                // escape, and \8 and \9 are the digits.
                return switch (escapedByte) {
                    case 'b' -> '\b';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'f' -> '\f';
                    case 'v' -> 0x0B;
                    case 'a' -> 0x07;
                    case 'e' -> 0x1B;
                    case 'C', 'E', 'H', 'Q', 'R', 'V', 'X', 'g', 'h', 'q' -> escapedByte;
                    case 'c' -> parseControlEscape(sequenceStart);
                    case 'x' -> parseTrinoHexEscape(flags, sequenceStart);
                    case 'u' -> parseTrinoUnicodeEscape(flags, sequenceStart);
                    case '0', '1', '2', '3', '4', '5', '6', '7' -> parseTrinoOctalEscape(escapedByte, sequenceStart);
                    case '8', '9' -> escapedByte;
                    case 'p', 'P' -> {
                        // Only a braced \p is a property; Joni reads a brace-less one as the letter.
                        if (!atEnd() && peekByte() == '{') {
                            throw unsupportedConstruct("character classes as range endpoints", sequenceStart, index);
                        }
                        yield escapedByte;
                    }
                    case 'd', 'D', 's', 'S', 'w', 'W' -> throw unsupportedConstruct("character classes as range endpoints", sequenceStart, index);
                    default -> {
                        if (isJoniLiteralEscape(escapedByte)) {
                            yield escapedByte;
                        }
                        int width = runeByteWidthAt(index - 1);
                        int length = 1 + (width > 0 ? width : 1);
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, length));
                    }
                };
            }
            return readRune(flags);
        }

        private Regexp parseEscape()
        {
            if (atEnd()) {
                throw error(RegexpParseErrorCode.TRAILING_BACKSLASH, null);
            }
            int sequenceStart = index - 1; // the backslash was already consumed by the caller
            int escapedByte = consumeByte();
            return switch (escapedByte) {
                case 'A' -> {
                    if ((flags & Regexp.PERL_EXTENSIONS) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    yield Regexp.beginText(flags);
                }
                case 'z' -> {
                    if ((flags & Regexp.PERL_EXTENSIONS) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    yield Regexp.endText(flags);
                }
                case 'C' -> {
                    if ((flags & Regexp.PERL_EXTENSIONS) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    yield literalFromRune('C');
                }
                case 'R' -> literalFromRune('R');
                case 'G' -> throw unsupportedConstruct("previous-match boundary \\G", sequenceStart, index);
                case 'Z' -> throw unsupportedConstruct("final-terminator boundary \\Z", sequenceStart, index);
                case 'b' -> {
                    if ((flags & Regexp.PERL_WORD_BOUNDARY) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    yield Regexp.wordBoundary(flags | Regexp.UNICODE_WORD_BOUNDARY);
                }
                case 'B' -> {
                    if ((flags & Regexp.PERL_WORD_BOUNDARY) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    yield Regexp.noWordBoundary(flags | Regexp.UNICODE_WORD_BOUNDARY);
                }
                case 'd' -> {
                    if ((flags & Regexp.PERL_CLASSES) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    yield perlDigits(flags, false);
                }
                case 'D' -> {
                    if ((flags & Regexp.PERL_CLASSES) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    yield perlDigits(flags, true);
                }
                case 's' -> {
                    if ((flags & Regexp.PERL_CLASSES) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    yield perlSpaces(flags, false);
                }
                case 'S' -> {
                    if ((flags & Regexp.PERL_CLASSES) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    yield perlSpaces(flags, true);
                }
                case 'w' -> {
                    if ((flags & Regexp.PERL_CLASSES) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    yield perlWord(flags, false);
                }
                case 'W' -> {
                    if ((flags & Regexp.PERL_CLASSES) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    yield perlWord(flags, true);
                }
                case 'h' -> literalFromRune('h');
                case 'H' -> literalFromRune('H');
                case 'v' -> literalFromRune(0x0B);
                case 'V' -> literalFromRune('V');
                case 'p', 'P' -> {
                    if ((flags & Regexp.UNICODE_GROUPS) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    // Joni does not fold a property outside brackets: (?i)\p{Lu} rejects a.
                    int classFlags = flags & ~Regexp.FOLD_CASE;
                    CharClassBuilder characterClassBuilder = new CharClassBuilder();
                    parseUnicodeGroupInto(characterClassBuilder, (escapedByte == 'P') ? -1 : 1, classFlags, index - 2);
                    characterClassBuilder.removeAbove(Regexp.maxRune(flags));
                    yield Regexp.charClass(classFlags, characterClassBuilder.toCharClass());
                }
                case 'E' -> literalFromRune('E');
                case 'Q' -> throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                case 'n' -> literalFromRune('\n');
                case 'r' -> literalFromRune('\r');
                case 't' -> literalFromRune('\t');
                case 'f' -> literalFromRune('\f');
                case 'a' -> literalFromRune(0x07);
                case 'e' -> literalFromRune(0x1B);
                case 'c' -> literalFromRune(parseControlEscape(sequenceStart));
                case 'x' -> literalFromRune(parseTrinoHexEscape(flags, sequenceStart));
                case 'u' -> literalFromRune(parseTrinoUnicodeEscape(flags, sequenceStart));
                case '0' -> literalFromRune(parseTrinoOctalEscape(escapedByte, sequenceStart));
                case '1', '2', '3', '4', '5', '6', '7', '8', '9' -> literalFromRune(parseTrinoNumericEscape(escapedByte, sequenceStart));
                case 'k' -> {
                    if (!atEnd() && (peekByte() == '<' || peekByte() == '\'')) {
                        throw unsupportedConstruct("backreferences", sequenceStart, index);
                    }
                    yield literalFromRune('k');
                }
                default -> {
                    if (isJoniLiteralEscape(escapedByte)) {
                        yield literalFromRune(escapedByte);
                    }
                    int width = runeByteWidthAt(index - 1);
                    int length = 1 + (width > 0 ? width : 1);
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, length));
                }
            };
        }

        private void parseUnicodeGroupInto(CharClassBuilder characterClassBuilder, int sign, int parseFlags, int sequenceStart)
        {
            // Supports \pL and \p{...}; inside a character class only braced names reach here. Joni
            // has no caret inversion, so \p^ and any braced name containing ^ are unknown property
            // names.
            if (!atEnd() && peekByte() == '{') {
                consumeByte('{');
                int nameStart = index;
                while (!atEnd() && peekByte() != '}') {
                    consumeByte();
                }
                if (atEnd() || consumeByte() != '}') {
                    throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, Slices.wrappedBuffer(bytes, sequenceStart, end - sequenceStart));
                }
                int nameEnd = index - 1;

                String name = new String(bytes, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8);
                CharClass group = lookupTrinoUnicodeGroup(name);
                if (group == null) {
                    throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
                addGroup(characterClassBuilder, group, sign, parseFlags, caseFolding);
                return;
            }

            int nameStart = index;
            readRune(parseFlags);

            String name = new String(bytes, nameStart, index - nameStart, StandardCharsets.UTF_8);
            CharClass group = lookupTrinoUnicodeGroup(name);
            if (group == null) {
                throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            addGroup(characterClassBuilder, group, sign, parseFlags, caseFolding);
        }

        private static CharClass lookupTrinoUnicodeGroup(String name)
        {
            // Joni rejects these spellings, and any property name with a character outside ASCII.
            if (name.indexOf('_') >= 0 ||
                    name.indexOf('=') >= 0 ||
                    name.startsWith("Is")) {
                return null;
            }
            for (int i = 0; i < name.length(); i++) {
                if (name.charAt(i) >= 0x80) {
                    return null;
                }
            }
            String lowerCaseName = UnicodeCase.toLowerCase(name);
            // Joni finds its POSIX names in any ASCII casing.
            CharClass posixClass = posixProperty(lowerCaseName);
            if (posixClass != null) {
                return posixClass;
            }
            if (name.length() >= 2 && UnicodeCase.equalsIgnoreCase(name.substring(0, 2), "In")) {
                CharClass block = JavaUnicodeProperties.lookup("In" + name.substring(2));
                if (block != null) {
                    return block;
                }
            }
            String javaProperty = canonicalJavaProperty(lowerCaseName);
            if (javaProperty != null) {
                return JavaUnicodeProperties.lookup(javaProperty);
            }
            CharClass group = JavaUnicodeProperties.lookup(name);
            if (group != null) {
                return group;
            }
            if (name.length() == 1) {
                group = JavaUnicodeProperties.lookup(UnicodeCase.toUpperCase(name));
            }
            else if (name.length() == 2) {
                group = JavaUnicodeProperties.lookup(new StringBuilder()
                        .appendCodePoint(UnicodeCase.toUpperCase(name.charAt(0)))
                        .append(UnicodeCase.toLowerCase(name.substring(1)))
                        .toString());
            }
            if (group != null) {
                return group;
            }
            group = JavaUnicodeProperties.lookup("Is" + name);
            if (group != null) {
                return group;
            }
            for (String script : JavaUnicodeProperties.scriptNames()) {
                if (UnicodeCase.equalsIgnoreCase(script.replace("_", ""), name)) {
                    return JavaUnicodeProperties.lookup("sc=" + script);
                }
            }
            return null;
        }

        private static String canonicalJavaProperty(String lowerCaseName)
        {
            return switch (lowerCaseName) {
                case "javalowercase" -> "javaLowerCase";
                case "javauppercase" -> "javaUpperCase";
                case "javaalphabetic" -> "javaAlphabetic";
                case "javaideographic" -> "javaIdeographic";
                case "javatitlecase" -> "javaTitleCase";
                case "javadigit" -> "javaDigit";
                case "javadefined" -> "javaDefined";
                case "javaletter" -> "javaLetter";
                case "javaletterordigit" -> "javaLetterOrDigit";
                case "javajavaidentifierstart" -> "javaJavaIdentifierStart";
                case "javajavaidentifierpart" -> "javaJavaIdentifierPart";
                case "javaunicodeidentifierstart" -> "javaUnicodeIdentifierStart";
                case "javaunicodeidentifierpart" -> "javaUnicodeIdentifierPart";
                case "javaidentifierignorable" -> "javaIdentifierIgnorable";
                case "javaspacechar" -> "javaSpaceChar";
                case "javawhitespace" -> "javaWhitespace";
                case "javaisocontrol" -> "javaISOControl";
                case "javamirrored" -> "javaMirrored";
                default -> null;
            };
        }

        private int parseTrinoHexEscape(int flags, int sequenceStart)
        {
            boolean braced = !atEnd() && peekByte() == '{';
            if (braced) {
                return parseHexEscape(flags, sequenceStart);
            }
            int rune = 0;
            int digits = 0;
            while (digits < 2 && !atEnd()) {
                int digit = decodeHexDigit(peekByte());
                if (digit < 0) {
                    break;
                }
                consumeByte();
                rune = (rune << 4) | digit;
                digits++;
            }
            if (digits == 0) {
                // Joni reads an escape without digits as U+0000, or as the letter when it ends the pattern.
                return atEnd() ? 'x' : 0;
            }
            if (rune > 0x7F) {
                throw unsupportedConstruct("non-ASCII byte escapes", sequenceStart, index);
            }
            return rune;
        }

        private int parseTrinoUnicodeEscape(int flags, int sequenceStart)
        {
            int rune = 0;
            int digits = 0;
            while (digits < 4 && !atEnd()) {
                int digit = decodeHexDigit(peekByte());
                if (digit < 0) {
                    break;
                }
                consumeByte();
                rune = (rune << 4) | digit;
                digits++;
            }
            if (digits == 0 && atEnd()) {
                // Joni reads the escape as the letter when it is bare and ends the pattern.
                return 'u';
            }
            if (digits < 4) {
                // As in modern Joni, the escape otherwise needs four hex digits. Report the character
                // that stopped it, as the braced \x form does.
                if (!atEnd()) {
                    readRune(flags);
                }
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            if (Character.isSurrogate((char) rune)) {
                throw unsupportedConstruct("surrogate escapes", sequenceStart, index);
            }
            if (rune > Regexp.maxRune(flags)) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            return rune;
        }

        private int parseTrinoOctalEscape(int firstDigit, int sequenceStart)
        {
            int code = firstDigit - '0';
            for (int i = 0; i < 2; i++) {
                if (atEnd()) {
                    break;
                }
                int c = peekByte();
                if (c < '0' || c > '7') {
                    break;
                }
                consumeByte();
                code = (code * 8) + (c - '0');
            }
            if (code > 0xFF) {
                // As in modern Joni; Trino's pinned Joni wraps the value to a byte.
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            if (code > 0x7F) {
                throw unsupportedConstruct("non-ASCII byte escapes", sequenceStart, index);
            }
            return code;
        }

        private int parseTrinoNumericEscape(int firstDigit, int sequenceStart)
        {
            // As in Joni, read the whole decimal number. It is a backreference when it is at most 9 or at most
            // the number of groups opened so far.
            int firstDigitEnd = index;
            int number = firstDigit - '0';
            while (!atEnd() && peekByte() >= '0' && peekByte() <= '9') {
                int digit = consumeByte() - '0';
                if (number <= BACKREFERENCE_NUMBER_LIMIT) {
                    number = (number * 10) + digit;
                }
            }
            if (number <= BACKREFERENCE_NUMBER_LIMIT && number <= capturingGroupCount) {
                throw unsupportedConstruct("backreferences", sequenceStart, index);
            }
            if (number <= 9) {
                // A backreference to a group that does not exist, which Joni rejects.
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            // Otherwise the escape starts over at its first digit: an 8 or 9 is that digit, and the digits after it
            // are ordinary characters, while any other digit starts an octal escape.
            index = firstDigitEnd;
            if (firstDigit == '8' || firstDigit == '9') {
                return firstDigit;
            }
            return parseTrinoOctalEscape(firstDigit, sequenceStart);
        }

        private int parseControlEscape(int sequenceStart)
        {
            // Joni maps an unescaped \c? to DEL and otherwise keeps the low five bits of the next character.
            // It decodes an escaped character first, reading a control-character escape such as \n as that
            // character and any other as itself. A nested \c is decoded by the loop, and masking again would
            // change nothing, so the result is masked once.
            boolean escaped = false;
            while (true) {
                if (atEnd()) {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
                int character = consumeByte();
                if (character == '\\') {
                    if (atEnd()) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    escaped = true;
                    character = consumeByte();
                    if (character == 'c') {
                        continue;
                    }
                    character = switch (character) {
                        case 'b' -> '\b';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'f' -> '\f';
                        case 'v' -> 0x0B;
                        case 'a' -> 0x07;
                        case 'e' -> 0x1B;
                        default -> character;
                    };
                }
                if (character >= 0x80) {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
                if (character == '?' && !escaped) {
                    return 0x7F;
                }
                return character & 0x9F;
            }
        }

        private static boolean isJoniLiteralEscape(int escapedByte)
        {
            return escapedByte < 0x80 && !Character.isDigit((char) escapedByte);
        }

        private Regexp perlDigits(int flags, boolean negate)
        {
            if (!negate) {
                return Regexp.charClass(flags & ~Regexp.FOLD_CASE, PERL_DIGITS_CLASS);
            }
            return perlCharClass(flags, negate, perlDigitsCharClass(flags, false));
        }

        private Regexp perlSpaces(int flags, boolean negate)
        {
            return perlCharClass(flags, negate, perlSpacesCharClass(flags, false));
        }

        private Regexp perlWord(int flags, boolean negate)
        {
            return perlCharClass(flags, negate, perlWordCharClass(flags, false));
        }

        // Joni does not fold a class escape outside brackets: (?i)\w rejects the long s and
        // Kelvin sign.
        private Regexp perlCharClass(int flags, boolean negate, CharClass group)
        {
            int classFlags = flags & ~Regexp.FOLD_CASE;
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            addGroup(characterClassBuilder, group, negate ? -1 : 1, classFlags, caseFolding);
            characterClassBuilder.removeAbove(Regexp.maxRune(flags));
            return Regexp.charClass(classFlags, characterClassBuilder.toCharClass());
        }

        private static CharClass perlDigitsCharClass(int flags, boolean negate)
        {
            if (!negate) {
                return PERL_DIGITS_CLASS;
            }
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            characterClassBuilder.addRange('0', '9');
            if (negate) {
                characterClassBuilder.negate();
                characterClassBuilder.removeAbove(Regexp.maxRune(flags));
            }
            return characterClassBuilder.toCharClass();
        }

        private static CharClass buildPerlDigitsCharClass()
        {
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            characterClassBuilder.addRange('0', '9');
            return characterClassBuilder.toCharClass();
        }

        private static CharClass perlSpacesCharClass(int flags, boolean negate)
        {
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            characterClassBuilder.addRange(' ', ' ');
            characterClassBuilder.addRange('\t', '\r');
            if (negate) {
                characterClassBuilder.negate();
                characterClassBuilder.removeAbove(Regexp.maxRune(flags));
            }
            return characterClassBuilder.toCharClass();
        }

        private static CharClass perlWordCharClass(int flags, boolean negate)
        {
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            characterClassBuilder.addRange('0', '9');
            characterClassBuilder.addRange('A', 'Z');
            characterClassBuilder.addRange('a', 'z');
            characterClassBuilder.addRange('_', '_');
            if (negate) {
                characterClassBuilder.negate();
                characterClassBuilder.removeAbove(Regexp.maxRune(flags));
            }
            return characterClassBuilder.toCharClass();
        }

        private int parseHexEscape(int flags, int sequenceStart)
        {
            // Supports \xNN and \x{...} with any number of hex digits (>= 1).
            int runeMax = Regexp.maxRune(flags);

            if (!atEnd() && peekByte() == '{') {
                consumeByte('{');
                int value = 0;
                int digits = 0;
                while (!atEnd() && peekByte() != '}') {
                    int rune = readRune(flags);
                    int digitValue = decodeHexDigit(rune);
                    if (digitValue < 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    digits++;
                    value = (value << 4) | digitValue;
                    if (value > runeMax) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                }
                if (digits == 0 || atEnd() || consumeByte() != '}') {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
                return value;
            }

            // \xNN
            if (atEnd()) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            int firstRune = readRune(flags);
            if (atEnd()) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            int secondRune = readRune(flags);
            int firstDigit = decodeHexDigit(firstRune);
            int secondDigit = decodeHexDigit(secondRune);
            if (firstDigit < 0 || secondDigit < 0) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            int value = (firstDigit << 4) | secondDigit;
            if (value > runeMax) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            return value;
        }

        private Regexp parseLiteral()
        {
            int rune = readRune(flags);
            return literalFromRune(rune);
        }

        private Regexp literalFromRune(int rune)
        {
            // Under case folding, expand into a character class and then rely on
            // parse canonicalization (simplifyForParse) to rewrite [Aa] back
            // into a FoldCase literal where possible.
            if ((flags & Regexp.FOLD_CASE) != 0) {
                if ((flags & Regexp.LATIN1) != 0) {
                    if (('A' <= rune && rune <= 'Z') || ('a' <= rune && rune <= 'z')) {
                        CharClassBuilder characterClassBuilder = new CharClassBuilder();
                        addFoldedRangeLatin1(characterClassBuilder, rune, rune);
                        return Regexp.charClass(flags & ~Regexp.FOLD_CASE, characterClassBuilder.toCharClass());
                    }
                }
                else {
                    if ((flags & Regexp.NEVER_NEWLINE) != 0 && rune == '\n') {
                        return Regexp.noMatch(flags);
                    }
                    return Regexp.literal(flags | Regexp.FULL_CASE_FOLD, rune);
                }
            }

            // Exclude newline if applicable.
            if (((flags & Regexp.NEVER_NEWLINE) != 0) && rune == '\n') {
                return Regexp.noMatch(flags);
            }

            return Regexp.literal(flags, rune);
        }

        private int readRune(int flags)
        {
            if ((flags & Regexp.LATIN1) != 0) {
                return consumeByte() & 0xFF;
            }

            long decoded = Utf8.decode(bytes, index, end);
            int width = Utf8.decodedWidth(decoded);
            if (width == 0) {
                throw error(RegexpParseErrorCode.BAD_UTF8, null);
            }
            int codePoint = Utf8.decodedCodePoint(decoded);
            if (width == 1 && codePoint == Utf8.RUNE_ERROR && (bytes[index] & 0xFF) >= 0x80) {
                throw error(RegexpParseErrorCode.BAD_UTF8, null);
            }
            index += width;
            return codePoint;
        }

        private int parseDecimal()
        {
            if (atEnd()) {
                throw error(RegexpParseErrorCode.REPEAT_ARGUMENT, null);
            }

            int firstDigit = peekByte();
            if (firstDigit < '0' || firstDigit > '9') {
                throw error(RegexpParseErrorCode.REPEAT_ARGUMENT, null);
            }

            int value = 0;
            boolean overflow = false;
            while (!atEnd()) {
                int nextByte = peekByte();
                if (nextByte < '0' || nextByte > '9') {
                    break;
                }
                consumeByte();
                if (!overflow) {
                    value = value * 10 + (nextByte - '0');
                    if (value > MAX_REPEAT) {
                        overflow = true;
                    }
                }
            }

            return value;
        }

        private int peekByte()
        {
            return bytes[index] & 0xFF;
        }

        private int consumeByte()
        {
            if (atEnd()) {
                return -1;
            }
            return bytes[index++] & 0xFF;
        }

        private void consumeByte(int expected)
        {
            int got = consumeByte();
            if (got != expected) {
                throw error(RegexpParseErrorCode.INTERNAL_ERROR, null);
            }
        }
    }
}
