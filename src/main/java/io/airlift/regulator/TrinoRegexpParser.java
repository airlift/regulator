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
import java.util.Locale;
import java.util.Set;

import static io.airlift.regulator.Regexp.MAX_REPEAT;
import static io.airlift.regulator.RegexpParserSupport.addFoldedRangeLatin1;
import static io.airlift.regulator.RegexpParserSupport.addGroup;
import static io.airlift.regulator.RegexpParserSupport.decodeHexDigit;
import static io.airlift.regulator.RegexpParserSupport.makeLiteralString;
import static io.airlift.regulator.RegexpParserSupport.wouldExceedRepeatLimit;
import static java.util.Objects.requireNonNull;

final class TrinoRegexpParser
{
    private TrinoRegexpParser() {}

    public static ParseResult parse(Slice pattern, int parseFlags)
    {
        requireNonNull(pattern, "pattern is null");

        Parser parser = new Parser(pattern, parseFlags);
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
        Regexp simplifiedRegexp = Simplifier.simplifyForParse(parsedRegexp);
        return new ParseResult(simplifiedRegexp, parser.capturingGroupCount);
    }

    @SuppressWarnings("CharUsedInArithmeticContext")
    private static final class Parser
    {
        private static final int COMMENTS = 1 << 13;

        // Capture names permit Lu, Ll, Lt, Lm, Lo, Nl, Mn, Mc, Nd, and Pc categories.
        private static final CharClass VALID_CAPTURE_NAME = buildValidCaptureNameCharClass();
        private static final CharClass PERL_DIGITS_CLASS = buildPerlDigitsCharClass();

        private final byte[] bytes;
        private final int start;
        private final int end;
        private int index;

        private int flags;
        private int capturingGroupCount;
        private boolean inQuote;
        private final Set<String> captureNames = new HashSet<>();

        Parser(Slice pattern, int flags)
        {
            this.bytes = pattern.byteArray();
            this.start = pattern.byteArrayOffset();
            this.end = start + pattern.length();
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
                expression.add(parseLiteral());
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
                    group.expression().add(parseRepeatSuffix(Regexp.emptyMatch(flags)));
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
                    if (groups.size() == 1) {
                        break;
                    }

                    consumeByte(')');
                    GroupFrame completed = groups.pop();
                    Regexp atom = completed.expression().build();
                    flags = completed.restoreFlags();
                    if (completed.capturing()) {
                        atom = Regexp.capture(completed.groupFlags(), atom, completed.captureIndex(), completed.name());
                    }
                    groups.peek().expression().add(parseRepeatSuffix(atom));
                    continue;
                }

                if (!inQuote && nextByte == '(') {
                    GroupFrame opened = parseGroupStart();
                    if (opened == null) {
                        group.expression().add(Regexp.emptyMatch(flags));
                    }
                    else {
                        groups.push(opened);
                    }
                    continue;
                }

                Regexp atom = parseRepeat();
                if (atom != null) {
                    group.expression().add(atom);
                }
            }

            if (groups.size() != 1) {
                throw error(RegexpParseErrorCode.MISSING_PAREN, errorArgAll());
            }
            return groups.pop().expression().build();
        }

        private static final class ExpressionBuilder
        {
            private final int expressionFlags;
            private int concatenationFlags;
            private final List<Regexp> alternatives = new ArrayList<>();
            private final List<Regexp> concatenation = new ArrayList<>();
            // Pending literal runes, flushed into one literal string node. Kept as a plain
            // array so accumulating a long literal does not box each rune.
            private int[] literalRunes = new int[16];
            private int literalRuneCount;
            private int literalFlags;

            ExpressionBuilder(int flags)
            {
                this.expressionFlags = flags;
                this.concatenationFlags = flags;
            }

            void add(Regexp atom)
            {
                if (atom.op() == RegexpOp.LITERAL) {
                    if (literalRuneCount > 0 && literalFlags != atom.parseFlags()) {
                        flushLiterals();
                    }
                    if (literalRuneCount == 0) {
                        literalFlags = atom.parseFlags();
                    }
                    if (literalRuneCount == literalRunes.length) {
                        literalRunes = Arrays.copyOf(literalRunes, literalRuneCount * 2);
                    }
                    literalRunes[literalRuneCount++] = atom.rune();
                    return;
                }

                flushLiterals();
                concatenation.add(atom);
            }

            void nextAlternative(int flags)
            {
                alternatives.add(buildConcatenation());
                concatenationFlags = flags;
            }

            Regexp build()
            {
                alternatives.add(buildConcatenation());
                if (alternatives.size() == 1) {
                    return alternatives.getFirst();
                }
                return Regexp.alternate(expressionFlags, alternatives);
            }

            private Regexp buildConcatenation()
            {
                flushLiterals();
                Regexp result;
                if (concatenation.isEmpty()) {
                    result = Regexp.emptyMatch(concatenationFlags);
                }
                else if (concatenation.size() == 1) {
                    result = concatenation.getFirst();
                }
                else {
                    result = Regexp.concat(concatenationFlags, List.copyOf(concatenation));
                }
                concatenation.clear();
                return result;
            }

            private void flushLiterals()
            {
                if (literalRuneCount == 0) {
                    return;
                }
                concatenation.add(makeLiteralString(literalFlags, literalRunes, literalRuneCount));
                literalRuneCount = 0;
            }
        }

        private record GroupFrame(
                ExpressionBuilder expression,
                int restoreFlags,
                int groupFlags,
                boolean capturing,
                int captureIndex,
                Slice name)
        {
            static GroupFrame root(int flags)
            {
                return new GroupFrame(new ExpressionBuilder(flags), flags, flags, false, -1, null);
            }
        }

        // repeat := atom (('*' | '+' | '?' | '{m,n}') '?'?)*
        private Regexp parseRepeat()
        {
            return parseRepeatSuffix(parseAtom());
        }

        private Regexp parseRepeatSuffix(Regexp atom)
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
                    atom = applyRepeat(atom);
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

        private Regexp applyRepeat(Regexp atom)
        {
            if (atom == null) {
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

        private GroupFrame parseGroupStart()
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
                    flags = parseInlineFlags(saved, groupStart);

                    if (atEnd()) {
                        throw error(RegexpParseErrorCode.MISSING_PAREN, errorArgAll());
                    }
                    if (peekByte() == ')') {
                        // Directive: update flags for remainder of the current parse scope.
                        consumeByte(')');
                        return null;
                    }
                    if (peekByte() != ':') {
                        throw error(RegexpParseErrorCode.BAD_PERL_OP, errorArgAtCurrent());
                    }
                    consumeByte(':');
                    return new GroupFrame(new ExpressionBuilder(flags), saved, groupFlags, false, -1, null);
                }
            }

            if (capturing) {
                capturingGroupCount++;
                captureIndex = capturingGroupCount;
            }
            return new GroupFrame(new ExpressionBuilder(flags), groupFlags, groupFlags, capturing, captureIndex, name);
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
                if (!isValidCaptureNameRune(rune) || (firstRune && Character.isDigit(rune))) {
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
            boolean negated = index + 1 < end && bytes[index + 1] == '^';
            CharClassBuilder characterClassBuilder = parseCharClassBuilder(nodeFlags);
            CharClass characterClass = characterClassBuilder.toCharClass();
            Regexp base = Regexp.charClass(nodeFlags & ~(Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD), characterClass);
            if (negated || (nodeFlags & Regexp.FOLD_CASE) == 0 || (nodeFlags & Regexp.LATIN1) != 0) {
                return base;
            }

            List<Regexp> alternatives = new ArrayList<>();
            alternatives.add(base);
            for (UnicodeFullCaseFold.FoldToken token : UnicodeFullCaseFold.multiCharacterTokens(characterClass)) {
                List<Regexp> sequence = new ArrayList<>(token.foldedRunes().length);
                for (int foldedRune : token.foldedRunes()) {
                    sequence.add(Regexp.charClass(
                            nodeFlags & ~(Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD),
                            UnicodeFullCaseFold.simpleFoldClass(foldedRune)));
                }
                alternatives.add(sequence.size() == 1
                        ? sequence.getFirst()
                        : Regexp.concat(nodeFlags, sequence));
            }
            return alternatives.size() == 1
                    ? base
                    : Regexp.alternate(nodeFlags, alternatives);
        }

        private CharClassBuilder parseCharClassBuilder(int nodeFlags)
        {
            int classStart = index;
            consumeByte('[');
            boolean negate = false;
            if (!atEnd() && peekByte() == '^') {
                consumeByte('^');
                negate = true;
            }

            CharClassBuilder characterClassBuilder = parseClassUnion(nodeFlags);
            while (isClassIntersection()) {
                index += 2;
                if (atEnd() || peekByte() == ']') {
                    throw unsupportedConstruct("empty character-class intersection operands", index - 2, index);
                }
                CharClassBuilder intersection = parseClassUnion(nodeFlags);
                characterClassBuilder.retainAll(intersection);
            }

            if (atEnd() || peekByte() != ']') {
                throw error(RegexpParseErrorCode.MISSING_BRACKET, Slices.wrappedBuffer(bytes, classStart, end - classStart));
            }
            consumeByte(']');

            if (negate) {
                characterClassBuilder.negate();
            }
            characterClassBuilder.removeAbove(Regexp.maxRune(nodeFlags));
            return characterClassBuilder;
        }

        private CharClassBuilder parseClassUnion(int nodeFlags)
        {
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            boolean first = true;
            while (!atEnd()) {
                int nextByte = peekByte();
                if (nextByte == ']' && !first) {
                    break;
                }
                if (first && isClassIntersection()) {
                    throw unsupportedConstruct("empty character-class intersection operands", index, index + 2);
                }
                if (!first && isClassIntersection()) {
                    break;
                }
                first = false;

                if (tryParsePosixCharClass(nodeFlags, characterClassBuilder)) {
                    continue;
                }

                if (nextByte == '[') {
                    characterClassBuilder.addCharClass(parseCharClassBuilder(nodeFlags));
                    continue;
                }

                if (tryParseClassEscape(nodeFlags, characterClassBuilder)) {
                    continue;
                }

                int low = parseClassAtomRune(nodeFlags);
                int high = low;

                if (!atEnd() && peekByte() == '-' && lookaheadIsRangeEnd()) {
                    consumeByte('-');
                    high = parseClassAtomRune(nodeFlags);
                    if (high < low) {
                        throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, null);
                    }
                }

                // For explicit singletons/ranges, do not implicitly filter '\n' out.
                characterClassBuilder.addRangeFlags(low, high, nodeFlags | Regexp.CLASS_NEWLINE);
            }

            return characterClassBuilder;
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

        private boolean tryParseClassEscape(int nodeFlags, CharClassBuilder characterClassBuilder)
        {
            if (peekByte() != '\\' || index + 1 >= end) {
                return false;
            }

            int escapedByte = bytes[index + 1] & 0xFF;
            if (isCompleteClassEscape(escapedByte) && index + 2 < end && bytes[index + 2] == '-') {
                throw unsupportedConstruct("character classes as range endpoints", index, index + 2);
            }
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
                addGroup(characterClassBuilder, characterClass, 1, nodeFlags);
                return true;
            }

            if (escapedByte == 'p' || escapedByte == 'P') {
                index += 2;
                parseUnicodeGroupInto(characterClassBuilder, (escapedByte == 'P') ? -1 : 1, nodeFlags, index - 2);
                return true;
            }
            return false;
        }

        private static CharClass buildValidCaptureNameCharClass()
        {
            CharClassBuilder captureNameClassBuilder = new CharClassBuilder();
            // As in upstream, these are added with NoParseFlags (0).
            for (String group : new String[] {"Lu", "Ll", "Lt", "Lm", "Lo", "Nl", "Mn", "Mc", "Nd", "Pc"}) {
                CharClass characterClass = UnicodeGroups.lookup(group);
                if (characterClass == null) {
                    throw new IllegalStateException("missing Unicode group: " + group);
                }
                addGroup(captureNameClassBuilder, characterClass, 1, 0);
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
            if (nameEnd == nameStart) {
                index = startIndex;
                return false;
            }

            if (atEnd() || peekByte() != ':' || index + 1 >= end || bytes[index + 1] != ']') {
                index = startIndex;
                return false;
            }

            consumeByte(':');
            consumeByte(']');

            String name = new String(bytes, nameStart, nameEnd - nameStart, StandardCharsets.US_ASCII);
            CharClass characterClass = posixCharClass(name);
            addGroup(characterClassBuilder, characterClass, negate ? -1 : 1, parseFlags);
            return true;
        }

        private static CharClass posixCharClass(String name)
        {
            String property = switch (name) {
                case "alnum" -> "Alnum";
                case "alpha" -> "Alphabetic";
                case "blank" -> "Blank";
                case "cntrl" -> "Control";
                case "digit" -> "Digit";
                case "graph" -> "Graph";
                case "lower" -> "Lowercase";
                case "print" -> "Print";
                case "punct" -> "Punctuation";
                case "space" -> "Whitespace";
                case "upper" -> "Uppercase";
                case "word" -> "Word";
                case "xdigit" -> "HexDigit";
                default -> null;
            };
            if (property != null) {
                CharClass characterClass = UnicodeGroups.lookup("Is" + property);
                if (characterClass != null) {
                    return characterClass;
                }
            }
            if (name.equals("ascii")) {
                CharClassBuilder characterClassBuilder = new CharClassBuilder();
                characterClassBuilder.addRange(0x00, 0x7F);
                return characterClassBuilder.toCharClass();
            }
            throw new RegexpParseException(RegexpParseErrorCode.BAD_CHAR_CLASS, null, -1);
        }

        private boolean lookaheadIsRangeEnd()
        {
            // Treat '-' as a range operator only if the following token is not ']' or end.
            if (index + 1 >= end) {
                return false;
            }
            int next = bytes[index + 1] & 0xFF;
            return next != ']';
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
                // Mirror upstream ParseEscape() behavior: accept escapes for known sequences,
                // accept escaped non-alnum ASCII as itself, otherwise error.
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
                    case '0' -> parseTrinoOctalEscape(escapedByte, flags, sequenceStart);
                    case '1', '2', '3', '4', '5', '6', '7' -> parseTrinoNumericEscape(
                            escapedByte,
                            flags,
                            sequenceStart,
                            false);
                    case 'd', 'D', 's', 'S', 'w', 'W', 'p', 'P' -> throw unsupportedConstruct("character classes as range endpoints", sequenceStart, index);
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
                    CharClassBuilder characterClassBuilder = new CharClassBuilder();
                    parseUnicodeGroupInto(characterClassBuilder, (escapedByte == 'P') ? -1 : 1, flags, index - 2);
                    characterClassBuilder.removeAbove(Regexp.maxRune(flags));
                    yield Regexp.charClass(flags & ~Regexp.FOLD_CASE, characterClassBuilder.toCharClass());
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
                case '0' -> literalFromRune(parseTrinoOctalEscape(escapedByte, flags, sequenceStart));
                case '1', '2', '3', '4', '5', '6', '7' -> literalFromRune(parseTrinoNumericEscape(
                        escapedByte,
                        flags,
                        sequenceStart,
                        true));
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
            // Supports \pL and \p{...} (with optional leading '^' to invert).
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

                if (nameEnd > nameStart && (bytes[nameStart] & 0xFF) == '^') {
                    sign = -sign;
                    nameStart++;
                }

                String name = new String(bytes, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8);
                CharClass group = lookupTrinoUnicodeGroup(name);
                if (group == null) {
                    throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
                addGroup(characterClassBuilder, group, sign, parseFlags);
                return;
            }

            int nameStart = index;
            int rune = readRune(parseFlags);
            int nameEnd = index;
            if (rune == '^') {
                sign = -sign;
                nameStart = index;
                if (atEnd()) {
                    throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
                readRune(parseFlags);
                nameEnd = index;
            }

            String name = new String(bytes, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8);
            CharClass group = lookupTrinoUnicodeGroup(name);
            if (group == null) {
                throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            addGroup(characterClassBuilder, group, sign, parseFlags);
        }

        private static CharClass lookupTrinoUnicodeGroup(String name)
        {
            if (name.indexOf('_') >= 0 ||
                    name.indexOf('=') >= 0 ||
                    name.startsWith("Is")) {
                return null;
            }
            if (name.regionMatches(true, 0, "In", 0, 2)) {
                CharClass block = UnicodeGroups.lookup("In" + name.substring(2));
                if (block != null) {
                    return block;
                }
            }
            String javaProperty = canonicalJavaProperty(name);
            if (javaProperty != null) {
                return UnicodeGroups.lookup(javaProperty);
            }
            CharClass group = UnicodeGroups.lookup(name);
            if (group != null) {
                return group;
            }
            if (name.length() == 1) {
                group = UnicodeGroups.lookup(name.toUpperCase(Locale.ROOT));
            }
            else if (name.length() == 2) {
                group = UnicodeGroups.lookup(
                        Character.toUpperCase(name.charAt(0)) +
                                name.substring(1).toLowerCase(Locale.ROOT));
            }
            if (group != null) {
                return group;
            }
            group = UnicodeGroups.lookup("Is" + name);
            if (group != null) {
                return group;
            }
            for (Character.UnicodeScript script : Character.UnicodeScript.values()) {
                if (script.name().replace("_", "").equalsIgnoreCase(name)) {
                    return UnicodeGroups.lookup(script.name());
                }
            }
            return null;
        }

        private static String canonicalJavaProperty(String name)
        {
            return switch (name.toLowerCase(Locale.ROOT)) {
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
                return 'x';
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
            if (digits == 0) {
                return 'u';
            }
            if (Character.isSurrogate((char) rune)) {
                throw unsupportedConstruct("surrogate escapes", sequenceStart, index);
            }
            if (rune > Regexp.maxRune(flags)) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            return rune;
        }

        private int parseTrinoOctalEscape(int escapedByte, int flags, int sequenceStart)
        {
            int rune = parseOctalEscape(escapedByte, flags, sequenceStart);
            if (rune > 0x7F) {
                throw unsupportedConstruct("non-ASCII byte escapes", sequenceStart, index);
            }
            return rune;
        }

        private int parseTrinoNumericEscape(int firstDigit, int flags, int sequenceStart, boolean rejectBackreference)
        {
            int group = firstDigit - '0';
            if (rejectBackreference && group <= capturingGroupCount) {
                throw unsupportedConstruct("backreferences", sequenceStart, index);
            }
            int rune = parseOctalEscape(firstDigit, flags, sequenceStart);
            if (rune > 0x7F) {
                throw unsupportedConstruct("non-ASCII byte escapes", sequenceStart, index);
            }
            return rune;
        }

        private int parseControlEscape(int sequenceStart)
        {
            if (atEnd()) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            int character = consumeByte();
            if (character >= 0x80) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            return character ^ 64;
        }

        private static boolean isCompleteClassEscape(int escapedByte)
        {
            return switch (escapedByte) {
                case 'd', 'D', 's', 'S', 'w', 'W', 'p', 'P' -> true;
                default -> false;
            };
        }

        private static boolean isJoniLiteralEscape(int escapedByte)
        {
            return escapedByte < 0x80 && !Character.isDigit((char) escapedByte);
        }

        private static Regexp perlDigits(int flags, boolean negate)
        {
            if (!negate) {
                return Regexp.charClass(flags & ~Regexp.FOLD_CASE, PERL_DIGITS_CLASS);
            }
            return perlCharClass(flags, negate, perlDigitsCharClass(flags, false));
        }

        private static Regexp perlSpaces(int flags, boolean negate)
        {
            return perlCharClass(flags, negate, perlSpacesCharClass(flags, false));
        }

        private static Regexp perlWord(int flags, boolean negate)
        {
            return perlCharClass(flags, negate, perlWordCharClass(flags, false));
        }

        private static Regexp perlCharClass(int flags, boolean negate, CharClass group)
        {
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            addGroup(characterClassBuilder, group, negate ? -1 : 1, flags);
            characterClassBuilder.removeAbove(Regexp.maxRune(flags));
            return Regexp.charClass(flags & ~Regexp.FOLD_CASE, characterClassBuilder.toCharClass());
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

        private int parseOctalEscape(int firstDigit, int flags, int sequenceStart)
        {
            // A single non-zero octal digit would be a backreference (unsupported here),
            // so require at least one more octal digit for '1'..'7'.
            if (firstDigit != '0') {
                if (atEnd() || peekByte() < '0' || peekByte() > '7') {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
            }

            int runeMax = Regexp.maxRune(flags);
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
            if (code > runeMax) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            return code;
        }

        private Regexp parseLiteral()
        {
            if ((flags & Regexp.LATIN1) == 0) {
                long decoded = Utf8.decode(bytes, index, end);
                int width = Utf8.decodedWidth(decoded);
                if (width == 1 &&
                        Utf8.decodedCodePoint(decoded) == Utf8.RUNE_ERROR &&
                        (bytes[index] & 0xFF) >= 0x80) {
                    int rawByte = consumeByte();
                    // Trino's non-strict Joni path preserves malformed literal bytes without
                    // changing the encoding of adjacent valid UTF-8 literals.
                    return Regexp.literal(
                            (flags | Regexp.LATIN1) & ~(Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD),
                            rawByte);
                }
            }
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
