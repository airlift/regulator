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

final class JavaRegexpParser
{
    private JavaRegexpParser() {}

    public static ParseResult parse(Slice pattern, int parseFlags)
    {
        requireNonNull(pattern, "pattern is null");
        if ((parseFlags & Regexp.LATIN1) == 0) {
            int invalidOffset = Utf8.firstInvalidOffset(pattern);
            if (invalidOffset >= 0) {
                throw new RegexpParseException(RegexpParseErrorCode.BAD_UTF8, null, invalidOffset);
            }
        }

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
        // elimination and named-class expansion after the frontend has established Java semantics.
        Regexp simplifiedRegexp = Simplifier.simplifyForParse(parsedRegexp);
        return new ParseResult(simplifiedRegexp, parser.capturingGroupCount);
    }

    @SuppressWarnings("CharUsedInArithmeticContext")
    private static final class Parser
    {
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
                expression.add(literalFromRune(readRune(flags)));
            }
            return expression.build();
        }

        private Regexp parseExpression()
        {
            Deque<GroupFrame> groups = new ArrayDeque<>();
            groups.push(GroupFrame.root(flags));

            while (!atEnd()) {
                skipIgnoredPatternCharacters();
                if (atEnd()) {
                    break;
                }
                GroupFrame group = groups.peek();
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
            private final List<Integer> literalRunes = new ArrayList<>();
            private Integer literalFlags;

            ExpressionBuilder(int flags)
            {
                this.expressionFlags = flags;
                this.concatenationFlags = flags;
            }

            void add(Regexp atom)
            {
                if (atom.op() == RegexpOp.LITERAL) {
                    if (literalFlags != null && literalFlags != atom.parseFlags()) {
                        flushLiterals();
                    }
                    if (literalFlags == null) {
                        literalFlags = atom.parseFlags();
                    }
                    literalRunes.add(atom.rune());
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
                if (literalRunes.isEmpty()) {
                    return;
                }
                concatenation.add(makeLiteralString(literalFlags == null ? concatenationFlags : literalFlags, literalRunes));
                literalRunes.clear();
                literalFlags = null;
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

            while (!atEnd()) {
                skipIgnoredPatternCharacters();
                if (atEnd()) {
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
                        throw error(RegexpParseErrorCode.REPEAT_ARGUMENT, errorArgAtCurrent());
                    }

                    // Stacked repetition operators are a syntax error in Perl extensions mode.
                    // In non-Perl extensions mode, they are allowed and will be simplified later.
                    if (appliedRepeat && (flags & Regexp.PERL_EXTENSIONS) != 0) {
                        throw error(RegexpParseErrorCode.BAD_REPEAT_OP, errorArgAtCurrent());
                    }
                    atom = applyRepeat(atom);
                    appliedRepeat = true;
                    continue;
                }
                break;
            }

            return atom;
        }

        private boolean isValidRepeatBrace()
        {
            // Lookahead validation for repeat braces, matching C++ MaybeParseRepetition behavior.
            // Returns false if the syntax is invalid, causing '{' to be treated as literal.
            // C++ RE2 disallows leading zeros in repeat counts (e.g., {01} or {1,02}).
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
            position = ignoredPatternEnd(position);

            if (position >= end) {
                return false;
            }

            int separatorByte = bytes[position] & 0xFF;
            if (separatorByte == ',') {
                position++;
                position = ignoredPatternEnd(position);
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
                    position = ignoredPatternEnd(position);
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

            int repetitionStart = index;
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
            skipIgnoredPatternCharacters();
            if ((flags & Regexp.PERL_EXTENSIONS) != 0 && !atEnd() && peekByte() == '?') {
                consumeByte('?');
                nonGreedy = greedyByDefault;
            }
            else if ((flags & Regexp.PERL_EXTENSIONS) != 0 && !atEnd() && peekByte() == '+') {
                throw unsupportedConstruct("possessive quantifiers", index, index + 1);
            }

            int nodeFlags = nonGreedy ? (flags | Regexp.NON_GREEDY) : (flags & ~Regexp.NON_GREEDY);
            if (max != 1) {
                ExpressionAnalysis analysis = ExpressionAnalysis.analyzeNormalized(atom);
                if (analysis.hasCaptures() && analysis.canMatchEmpty()) {
                    throw unsupportedConstruct("nullable repeated captures", repetitionStart, index);
                }
            }

            return switch (repetitionOperator) {
                case STAR -> Regexp.star(nodeFlags, atom);
                case PLUS -> Regexp.plus(nodeFlags, atom);
                case QUEST -> Regexp.quest(nodeFlags, atom);
                case REPEAT -> {
                    // Match upstream: reject nested repetition whose worst-case expansion would exceed MAX_REPEAT.
                    // See re2/parse.cc PushRepetition() and RepetitionWalker.
                    if (wouldExceedRepeatLimit(atom, min, max)) {
                        throw error(RegexpParseErrorCode.REPEAT_SIZE, null);
                    }
                    yield Regexp.repeat(nodeFlags, atom, min, max);
                }
            };
        }

        private enum RepetitionOperator
        {
            STAR, PLUS, QUEST, REPEAT
        }

        private int[] parseRepetitionCounts()
        {
            int minRepetitions = parseDecimal();
            int maxRepetitions = minRepetitions;
            skipIgnoredPatternCharacters();

            if (atEnd()) {
                throw error(RegexpParseErrorCode.MISSING_BRACKET, Slices.wrappedBuffer(bytes, index - 1, 1));
            }

            if (peekByte() == ',') {
                consumeByte(',');
                skipIgnoredPatternCharacters();
                if (peekByte() == '}') {
                    maxRepetitions = -1;
                }
                else {
                    maxRepetitions = parseDecimal();
                    skipIgnoredPatternCharacters();
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
                skipIgnoredPatternCharacters();
                if (atEnd()) {
                    return null;
                }
            }
            while (tryConsumeQuoteDirective());

            int nextByte = peekByte();
            if (inQuote) {
                return literalFromRune(readRune(flags));
            }

            // Repetition operators are not valid in atom position.
            // See upstream re2/parse.cc PushRepeatOp()/PushRepetition().
            if (nextByte == '*' || nextByte == '+' || nextByte == '?' || nextByte == '{') {
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
                classBuilder.addRange(0, Regexp.maxRune(flags));
                int[] lineTerminators = (flags & Regexp.JAVA_UNIX_LINES) != 0
                        ? new int[] {'\n'}
                        : new int[] {'\n', '\r', 0x0085, 0x2028, 0x2029};
                for (int lineTerminator : lineTerminators) {
                    CharClassBuilder excluded = new CharClassBuilder();
                    excluded.addRange(lineTerminator, lineTerminator);
                    excluded.negate();
                    classBuilder.retainAll(excluded);
                }
                return Regexp.charClass(flags, classBuilder.toCharClass());
            }
            if (nextByte == '^') {
                consumeByte('^');
                if ((flags & Regexp.ONE_LINE) != 0) {
                    return Regexp.beginText(flags);
                }
                return Regexp.beginLine(flags | Regexp.JAVA_LINE);
            }
            if (nextByte == '$') {
                consumeByte('$');
                if ((flags & Regexp.ONE_LINE) == 0) {
                    int nodeFlags = (flags & Regexp.JAVA_UNIX_LINES) != 0 ? flags : flags | Regexp.JAVA_LINE;
                    return Regexp.endLine(nodeFlags);
                }
                int nodeFlags = flags | Regexp.WAS_DOLLAR;
                nodeFlags |= (flags & Regexp.JAVA_UNIX_LINES) != 0 ? Regexp.FINAL_LINE_END : Regexp.JAVA_FINAL_END;
                return Regexp.endText(nodeFlags);
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
                if ((inlineFlags & Regexp.JAVA_COMMENTS) != 0) {
                    int savedFlags = flags;
                    flags = inlineFlags;
                    skipIgnoredPatternCharacters();
                    flags = savedFlags;
                    if (atEnd()) {
                        break;
                    }
                }
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
                    case 'i' -> inlineFlags = setCaseInsensitive(inlineFlags, !clearing);
                    case 'd' -> inlineFlags = setFlag(inlineFlags, Regexp.JAVA_UNIX_LINES, !clearing);
                    case 'm' -> inlineFlags = setFlag(inlineFlags, Regexp.ONE_LINE, clearing);
                    case 's' -> inlineFlags = clearing ? (inlineFlags & ~Regexp.DOT_MATCHES_NEWLINE) : (inlineFlags | Regexp.DOT_MATCHES_NEWLINE);
                    case 'u' -> inlineFlags = setUnicodeCase(inlineFlags, !clearing);
                    case 'U' -> inlineFlags = setUnicodeCharacterClasses(inlineFlags, !clearing);
                    case 'x' -> inlineFlags = setFlag(inlineFlags, Regexp.JAVA_COMMENTS, !clearing);
                    default -> throw error(RegexpParseErrorCode.BAD_PERL_OP, Slices.wrappedBuffer(bytes, groupStart, index - groupStart));
                }
            }

            return inlineFlags;
        }

        private static int setCaseInsensitive(int flags, boolean enabled)
        {
            if (!enabled) {
                return flags & ~(Regexp.ASCII_FOLD_CASE | Regexp.FOLD_CASE);
            }
            if ((flags & Regexp.JAVA_UNICODE_CASE) != 0) {
                return (flags | Regexp.FOLD_CASE) & ~Regexp.ASCII_FOLD_CASE;
            }
            return (flags | Regexp.ASCII_FOLD_CASE) & ~Regexp.FOLD_CASE;
        }

        private static int setUnicodeCase(int flags, boolean enabled)
        {
            boolean caseInsensitive = (flags & (Regexp.ASCII_FOLD_CASE | Regexp.FOLD_CASE)) != 0;
            flags = setFlag(flags, Regexp.JAVA_UNICODE_CASE, enabled);
            return setCaseInsensitive(flags, caseInsensitive);
        }

        private static int setUnicodeCharacterClasses(int flags, boolean enabled)
        {
            if (enabled) {
                flags |= Regexp.JAVA_UNICODE_CHARACTER_CLASS;
                return setUnicodeCase(flags, true);
            }
            flags &= ~Regexp.JAVA_UNICODE_CHARACTER_CLASS;
            return setUnicodeCase(flags, false);
        }

        private static int setFlag(int flags, int flag, boolean enabled)
        {
            return enabled ? flags | flag : flags & ~flag;
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

            if (!isAsciiLetter(bytes[nameStart] & 0xFF)) {
                throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE, Slices.wrappedBuffer(bytes, groupStart, (nameEnd - groupStart) + 1));
            }
            for (int nameIndex = nameStart + 1; nameIndex < nameEnd; nameIndex++) {
                int character = bytes[nameIndex] & 0xFF;
                if (!isAsciiLetter(character) && (character < '0' || character > '9')) {
                    throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE, Slices.wrappedBuffer(bytes, groupStart, (nameEnd - groupStart) + 1));
                }
            }

            String captureName = new String(bytes, nameStart, nameLength, StandardCharsets.US_ASCII);
            if (!captureNames.add(captureName)) {
                throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE, Slices.wrappedBuffer(bytes, groupStart, (nameEnd - groupStart) + 1));
            }

            index = nameEnd + 1;
            return Slices.wrappedBuffer(bytes, nameStart, nameLength);
        }

        private static boolean isAsciiLetter(int character)
        {
            return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
        }

        private Regexp parseCharClass()
        {
            int nodeFlags = flags;
            CharClassBuilder characterClassBuilder = parseCharClassBuilder(nodeFlags);
            return Regexp.charClass(nodeFlags & ~Regexp.FOLD_CASE, characterClassBuilder.toCharClass());
        }

        private CharClassBuilder parseCharClassBuilder(int nodeFlags)
        {
            int classStart = index;
            consumeByte('[');
            ClassAtomCursor atomCursor = new ClassAtomCursor();
            boolean negate = false;
            if (atomCursor.tryConsumeNegation()) {
                negate = true;
            }

            ClassUnionResult firstUnion = parseClassUnion(nodeFlags, false, atomCursor);
            CharClassBuilder characterClassBuilder = firstUnion.characterClassBuilder();
            boolean hasClassAtom = firstUnion.hasClassAtom();
            boolean hasIntersectionBase = hasClassAtom;
            while (atomCursor.isIntersection()) {
                atomCursor.consumeIntersection();
                if (atomCursor.atEnd() || atomCursor.isClassEnd(false, true)) {
                    break;
                }
                ClassUnionResult intersectionUnion = parseClassUnion(nodeFlags, true, atomCursor);
                CharClassBuilder intersection = intersectionUnion.characterClassBuilder();
                hasClassAtom |= intersectionUnion.hasClassAtom();
                if (!intersectionUnion.hasClassAtom()) {
                    continue;
                }
                if (!hasIntersectionBase) {
                    characterClassBuilder.addCharClass(intersection);
                    hasIntersectionBase = true;
                }
                else {
                    characterClassBuilder.retainAll(intersection);
                }
            }

            if (atomCursor.atEnd() || !atomCursor.isClassEnd(false, false)) {
                throw error(RegexpParseErrorCode.MISSING_BRACKET, Slices.wrappedBuffer(bytes, classStart, end - classStart));
            }
            consumeByte(']');

            if (!hasClassAtom) {
                throw error(RegexpParseErrorCode.BAD_CHAR_CLASS, Slices.wrappedBuffer(bytes, classStart, index - classStart));
            }
            if (negate) {
                characterClassBuilder.negate();
            }
            characterClassBuilder.removeAbove(Regexp.maxRune(nodeFlags));
            return characterClassBuilder;
        }

        private ClassUnionResult parseClassUnion(int nodeFlags, boolean intersectionOperand, ClassAtomCursor atomCursor)
        {
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            boolean first = true;
            boolean hasClassAtom = false;
            while (!atomCursor.atEnd()) {
                if (atomCursor.isClassEnd(first, intersectionOperand)) {
                    break;
                }
                if (atomCursor.isIntersection()) {
                    break;
                }
                first = false;

                if (atomCursor.isNestedClass()) {
                    characterClassBuilder.addCharClass(parseCharClassBuilder(nodeFlags));
                    hasClassAtom = true;
                    continue;
                }

                if (!atomCursor.isQuoted() && tryParseClassEscape(nodeFlags, characterClassBuilder)) {
                    hasClassAtom = true;
                    continue;
                }

                int low = atomCursor.parseLiteralRune(nodeFlags);
                int high = low;
                hasClassAtom = true;

                if (atomCursor.tryConsumeRangeOperator()) {
                    high = atomCursor.parseLiteralRune(nodeFlags);
                    if (high < low) {
                        throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, null);
                    }
                }

                // For explicit singletons/ranges, do not implicitly filter '\n' out.
                characterClassBuilder.addRangeFlags(low, high, nodeFlags | Regexp.CLASS_NEWLINE);
            }

            return new ClassUnionResult(characterClassBuilder, hasClassAtom);
        }

        private final class ClassAtomCursor
        {
            // Java quote directives are zero-width class syntax, but every code point inside a
            // quote remains an independent literal atom. Keep that state local to this class.
            private boolean quoted;

            private boolean atEnd()
            {
                skipZeroWidthSyntax();
                return Parser.this.atEnd();
            }

            private boolean tryConsumeNegation()
            {
                skipQuoteDirectives();
                if (quoted || Parser.this.atEnd() || peekByte() != '^') {
                    return false;
                }
                consumeByte('^');
                return true;
            }

            private boolean isClassEnd(boolean first, boolean intersectionOperand)
            {
                skipZeroWidthSyntax();
                return !quoted && !Parser.this.atEnd() && peekByte() == ']' && (!first || intersectionOperand);
            }

            private boolean isIntersection()
            {
                int savedIndex = index;
                boolean savedQuoted = quoted;
                boolean intersection = tryConsumeIntersection();
                index = savedIndex;
                quoted = savedQuoted;
                return intersection;
            }

            private void consumeIntersection()
            {
                if (!tryConsumeIntersection()) {
                    throw new IllegalStateException("not at a character class intersection");
                }
            }

            private boolean tryConsumeIntersection()
            {
                skipZeroWidthSyntax();
                if (quoted || Parser.this.atEnd() || peekByte() != '&') {
                    return false;
                }

                index++;
                skipZeroWidthSyntax();
                if (quoted || Parser.this.atEnd() || peekByte() != '&') {
                    return false;
                }
                index++;
                return true;
            }

            private boolean isNestedClass()
            {
                skipZeroWidthSyntax();
                return !quoted && !Parser.this.atEnd() && peekByte() == '[';
            }

            private boolean isQuoted()
            {
                skipZeroWidthSyntax();
                return quoted;
            }

            private int parseLiteralRune(int flags)
            {
                skipZeroWidthSyntax();
                if (quoted) {
                    return readRune(flags);
                }
                return parseClassAtomRune(flags);
            }

            private boolean tryConsumeRangeOperator()
            {
                skipZeroWidthSyntax();
                if (quoted || Parser.this.atEnd() || peekByte() != '-') {
                    return false;
                }

                int rangeOperatorIndex = index;
                index++;
                // Quote directives do not separate '-' from its endpoint. COMMENTS text does:
                // Java commits to range parsing and rejects the class if no endpoint follows it.
                skipQuoteDirectives();
                if (Parser.this.atEnd() || (!quoted && (peekByte() == ']' || peekByte() == '['))) {
                    index = rangeOperatorIndex;
                    quoted = false;
                    return false;
                }
                skipZeroWidthSyntax();
                return true;
            }

            private void skipZeroWidthSyntax()
            {
                while (!Parser.this.atEnd()) {
                    if (!quoted) {
                        index = ignoredPatternEnd(index);
                        if (Parser.this.atEnd()) {
                            return;
                        }
                    }

                    int directiveIndex = index;
                    skipQuoteDirectives();
                    if (index == directiveIndex) {
                        return;
                    }
                }
            }

            private void skipQuoteDirectives()
            {
                while (index + 1 < end && bytes[index] == '\\') {
                    if (!quoted && bytes[index + 1] == 'Q') {
                        quoted = true;
                        index += 2;
                    }
                    else if (quoted && bytes[index + 1] == 'E') {
                        quoted = false;
                        index += 2;
                    }
                    else {
                        return;
                    }
                }
            }
        }

        private record ClassUnionResult(CharClassBuilder characterClassBuilder, boolean hasClassAtom) {}

        private boolean tryParseClassEscape(int nodeFlags, CharClassBuilder characterClassBuilder)
        {
            if (peekByte() != '\\' || index + 1 >= end) {
                return false;
            }

            int escapedByte = bytes[index + 1] & 0xFF;
            if (escapedByte == 'v' && index + 2 < end && bytes[index + 2] == '-') {
                return false;
            }
            CharClass characterClass = switch (escapedByte) {
                case 'd' -> digitsCharClass(nodeFlags, false);
                case 'D' -> digitsCharClass(nodeFlags, true);
                case 's' -> spacesCharClass(nodeFlags, false);
                case 'S' -> spacesCharClass(nodeFlags, true);
                case 'w' -> wordCharClass(nodeFlags, false);
                case 'W' -> wordCharClass(nodeFlags, true);
                case 'h' -> horizontalWhitespace(nodeFlags, false);
                case 'H' -> horizontalWhitespace(nodeFlags, true);
                case 'v' -> verticalWhitespace(nodeFlags, false);
                case 'V' -> verticalWhitespace(nodeFlags, true);
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

        private static CharClass posixCharClass(String name)
        {
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            switch (name) {
                case "alnum" -> {
                    characterClassBuilder.addRange('0', '9');
                    characterClassBuilder.addRange('A', 'Z');
                    characterClassBuilder.addRange('a', 'z');
                }
                case "alpha" -> {
                    characterClassBuilder.addRange('A', 'Z');
                    characterClassBuilder.addRange('a', 'z');
                }
                case "blank" -> {
                    characterClassBuilder.addRange('\t', '\t');
                    characterClassBuilder.addRange(' ', ' ');
                }
                case "cntrl" -> {
                    characterClassBuilder.addRange(0x00, 0x1F);
                    characterClassBuilder.addRange(0x7F, 0x7F);
                }
                case "digit" -> characterClassBuilder.addRange('0', '9');
                case "graph" -> characterClassBuilder.addRange('!', '~');
                case "lower" -> characterClassBuilder.addRange('a', 'z');
                case "print" -> characterClassBuilder.addRange(' ', '~');
                case "punct" -> {
                    characterClassBuilder.addRange('!', '/');
                    characterClassBuilder.addRange(':', '@');
                    characterClassBuilder.addRange('[', '`');
                    characterClassBuilder.addRange('{', '~');
                }
                case "space" -> {
                    characterClassBuilder.addRange('\t', '\r'); // \t \n \v \f \r
                    characterClassBuilder.addRange(' ', ' ');
                }
                case "upper" -> characterClassBuilder.addRange('A', 'Z');
                case "xdigit" -> {
                    characterClassBuilder.addRange('0', '9');
                    characterClassBuilder.addRange('A', 'F');
                    characterClassBuilder.addRange('a', 'f');
                }
                case "ascii" -> characterClassBuilder.addRange(0x00, 0x7F);
                default -> throw new RegexpParseException(RegexpParseErrorCode.BAD_CHAR_CLASS, null, -1);
            }
            return characterClassBuilder.toCharClass();
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
                    case 'H', 'V', 'h' -> escapedByte;
                    case 'c' -> parseControlEscape(sequenceStart);
                    case 'N' -> parseNamedCodePoint(sequenceStart);
                    case 'x' -> parseHexEscape(flags, sequenceStart);
                    case 'u' -> parseUnicodeEscape(flags, sequenceStart);
                    case '0' -> parseOctalEscape(escapedByte, flags, sequenceStart);
                    default -> {
                        if (escapedByte < 0x80 && !Character.isLetterOrDigit((char) escapedByte)) {
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
                case 'Z' -> {
                    int nodeFlags = flags;
                    nodeFlags |= (flags & Regexp.JAVA_UNIX_LINES) != 0 ? Regexp.FINAL_LINE_END : Regexp.JAVA_FINAL_END;
                    yield Regexp.endText(nodeFlags);
                }
                case 'b' -> Regexp.wordBoundary(flags | Regexp.JAVA_WORD_BOUNDARY);
                case 'B' -> Regexp.noWordBoundary(flags | Regexp.JAVA_WORD_BOUNDARY);
                case 'G' -> throw unsupportedConstruct("previous-match boundary \\G", sequenceStart, index);
                case 'R' -> throw unsupportedConstruct("linebreak escape \\R", sequenceStart, index);
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
                case 'h' -> Regexp.charClass(flags & ~Regexp.FOLD_CASE, horizontalWhitespace(flags, false));
                case 'H' -> Regexp.charClass(flags & ~Regexp.FOLD_CASE, horizontalWhitespace(flags, true));
                case 'v' -> Regexp.charClass(flags & ~Regexp.FOLD_CASE, verticalWhitespace(flags, false));
                case 'V' -> Regexp.charClass(flags & ~Regexp.FOLD_CASE, verticalWhitespace(flags, true));
                case 'p', 'P' -> {
                    if ((flags & Regexp.UNICODE_GROUPS) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                    }
                    CharClassBuilder characterClassBuilder = new CharClassBuilder();
                    parseUnicodeGroupInto(characterClassBuilder, (escapedByte == 'P') ? -1 : 1, flags, index - 2);
                    characterClassBuilder.removeAbove(Regexp.maxRune(flags));
                    yield Regexp.charClass(flags & ~Regexp.FOLD_CASE, characterClassBuilder.toCharClass());
                }
                case 'Q', 'E' -> throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                case 'n' -> literalFromRune('\n');
                case 'r' -> literalFromRune('\r');
                case 't' -> literalFromRune('\t');
                case 'f' -> literalFromRune('\f');
                case 'a' -> literalFromRune(0x07);
                case 'e' -> literalFromRune(0x1B);
                case 'c' -> literalFromRune(parseControlEscape(sequenceStart));
                case 'N' -> literalFromRune(parseNamedCodePoint(sequenceStart));
                case 'x' -> literalFromRune(parseHexEscape(flags, sequenceStart));
                case 'u' -> literalFromRune(parseUnicodeEscape(flags, sequenceStart));
                case '0' -> literalFromRune(parseOctalEscape(escapedByte, flags, sequenceStart));
                case '1', '2', '3', '4', '5', '6', '7', '8', '9' -> throw unsupportedConstruct("backreferences", sequenceStart, index);
                case 'k' -> {
                    if (!atEnd() && peekByte() == '<') {
                        throw unsupportedConstruct("backreferences", sequenceStart, index);
                    }
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
                default -> {
                    if (escapedByte < 0x80 && !Character.isLetterOrDigit((char) escapedByte)) {
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
                CharClass group = lookupJavaUnicodeGroup(name, parseFlags);
                if (group == null) {
                    throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
                addJavaUnicodeGroup(characterClassBuilder, name, group, sign, parseFlags);
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
            CharClass group = lookupJavaUnicodeGroup(name, parseFlags);
            if (group == null) {
                throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            addJavaUnicodeGroup(characterClassBuilder, name, group, sign, parseFlags);
        }

        private static void addJavaUnicodeGroup(CharClassBuilder builder, String name, CharClass group, int sign, int flags)
        {
            // Java applies property-specific case predicates before complement/intersection,
            // not the fold closure used for literal ranges. Other properties remain unchanged.
            if ((flags & (Regexp.ASCII_FOLD_CASE | Regexp.FOLD_CASE)) != 0) {
                group = caseInsensitiveJavaProperty(name, group, flags);
            }
            addGroup(builder, group, sign, flags & ~(Regexp.ASCII_FOLD_CASE | Regexp.FOLD_CASE));
        }

        private static CharClass caseInsensitiveJavaProperty(String name, CharClass group, int flags)
        {
            String property = name;
            int equals = name.indexOf('=');
            if (equals >= 0) {
                String family = name.substring(0, equals).toLowerCase(Locale.ROOT);
                if (!family.equals("gc") && !family.equals("general_category")) {
                    return group;
                }
                property = name.substring(equals + 1);
            }
            else if (name.startsWith("Is")) {
                property = name.substring(2);
                if (switch (property.toUpperCase(Locale.ROOT)) {
                    case "LOWER", "UPPER", "LOWERCASE", "UPPERCASE", "TITLECASE" -> true;
                    default -> false;
                }) {
                    return CasedPropertyHolder.CHARACTER_CLASS;
                }
            }

            return switch (property) {
                case "Lu", "Ll", "Lt", "Uppercase_Letter", "Lowercase_Letter", "Titlecase_Letter" -> UnicodeGroups.lookup("LC");
                case "javaLowerCase", "javaUpperCase", "javaTitleCase" -> CasedPropertyHolder.CHARACTER_CLASS;
                case "Lower", "Upper" -> (equals < 0 && (flags & Regexp.JAVA_UNICODE_CHARACTER_CLASS) != 0)
                        ? CasedPropertyHolder.CHARACTER_CLASS
                        : posixCharClass("alpha");
                default -> group;
            };
        }

        private static final class CasedPropertyHolder
        {
            private static final CharClass CHARACTER_CLASS = casedProperty();

            private static CharClass casedProperty()
            {
                CharClassBuilder builder = new CharClassBuilder();
                builder.addCharClass(UnicodeGroups.lookup("javaLowerCase"), 0);
                builder.addCharClass(UnicodeGroups.lookup("javaUpperCase"), 0);
                builder.addCharClass(UnicodeGroups.lookup("javaTitleCase"), 0);
                return builder.toCharClass();
            }
        }

        private static CharClass horizontalWhitespace(int flags, boolean negate)
        {
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            characterClassBuilder.addRange('\t', '\t');
            characterClassBuilder.addRange(' ', ' ');
            characterClassBuilder.addRange(0x00A0, 0x00A0);
            characterClassBuilder.addRange(0x1680, 0x1680);
            characterClassBuilder.addRange(0x180E, 0x180E);
            characterClassBuilder.addRange(0x2000, 0x200A);
            characterClassBuilder.addRange(0x202F, 0x202F);
            characterClassBuilder.addRange(0x205F, 0x205F);
            characterClassBuilder.addRange(0x3000, 0x3000);
            if (negate) {
                characterClassBuilder.negate();
            }
            characterClassBuilder.removeAbove(Regexp.maxRune(flags));
            return characterClassBuilder.toCharClass();
        }

        private static CharClass lookupJavaUnicodeGroup(String name, int flags)
        {
            if (name.equals("Any")) {
                return null;
            }
            String originalName = name;
            int equals = name.indexOf('=');
            if (equals >= 0) {
                String family = name.substring(0, equals).toLowerCase(Locale.ROOT);
                if (family.equals("gc") || family.equals("general_category")) {
                    // Pattern.family resolves these through forProperty, without
                    // the Unicode POSIX widening used for bare property names.
                    name = name.substring(equals + 1);
                    flags &= ~Regexp.JAVA_UNICODE_CHARACTER_CLASS;
                    if (name.startsWith("java")) {
                        return UnicodeGroups.lookup(name);
                    }
                }
            }
            if (name.equals("L1") || (equals < 0 && name.equals("IsL1"))) {
                return Latin1PropertyHolder.CHARACTER_CLASS;
            }
            if ((flags & Regexp.JAVA_UNICODE_CHARACTER_CLASS) != 0 && !name.equals("ASCII") && !name.equals("IsASCII")) {
                String unicodeName = switch (name) {
                    case "Lower" -> "IsLowercase";
                    case "Upper" -> "IsUppercase";
                    case "Alpha" -> "IsAlphabetic";
                    case "Digit" -> "IsDigit";
                    case "Alnum" -> "IsAlnum";
                    case "Punct" -> "IsPunctuation";
                    case "Graph" -> "IsGraph";
                    case "Print" -> "IsPrint";
                    case "Blank" -> "IsBlank";
                    case "Cntrl" -> "IsControl";
                    case "XDigit" -> "IsHexDigit";
                    case "Space" -> "IsWhiteSpace";
                    default -> null;
                };
                if (unicodeName != null) {
                    return UnicodeGroups.lookup(unicodeName);
                }
            }
            String posixName = switch (name) {
                case "Lower" -> "lower";
                case "Upper" -> "upper";
                case "ASCII" -> "ascii";
                case "IsASCII" -> equals < 0 ? "ascii" : null;
                case "Alpha" -> "alpha";
                case "Digit" -> "digit";
                case "Alnum" -> "alnum";
                case "Punct" -> "punct";
                case "Graph" -> "graph";
                case "Print" -> "print";
                case "Blank" -> "blank";
                case "Cntrl" -> "cntrl";
                case "XDigit" -> "xdigit";
                case "Space" -> "space";
                default -> null;
            };
            if (posixName != null) {
                return posixCharClass(posixName);
            }
            if (!name.startsWith("Is") && name.indexOf('=') < 0) {
                String normalizedName = name.replace("_", "");
                for (Character.UnicodeScript script : Character.UnicodeScript.values()) {
                    if (script.name().replace("_", "").equalsIgnoreCase(normalizedName)) {
                        return null;
                    }
                }
            }
            return UnicodeGroups.lookup(originalName);
        }

        private static final class Latin1PropertyHolder
        {
            private static final CharClass CHARACTER_CLASS = new CharClass(false, 256, new RuneRange[] {new RuneRange(0, 0xFF)});
        }

        private static CharClass verticalWhitespace(int flags, boolean negate)
        {
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            characterClassBuilder.addRange('\n', '\r');
            characterClassBuilder.addRange(0x0085, 0x0085);
            characterClassBuilder.addRange(0x2028, 0x2029);
            if (negate) {
                characterClassBuilder.negate();
            }
            characterClassBuilder.removeAbove(Regexp.maxRune(flags));
            return characterClassBuilder.toCharClass();
        }

        private static Regexp perlDigits(int flags, boolean negate)
        {
            if ((flags & Regexp.JAVA_UNICODE_CHARACTER_CLASS) != 0) {
                return unicodePredefinedClass(flags, "IsDigit", negate);
            }
            if (!negate) {
                return Regexp.charClass(flags & ~Regexp.FOLD_CASE, PERL_DIGITS_CLASS);
            }
            return perlCharClass(flags, negate, perlDigitsCharClass(flags, false));
        }

        private static Regexp perlSpaces(int flags, boolean negate)
        {
            if ((flags & Regexp.JAVA_UNICODE_CHARACTER_CLASS) != 0) {
                return unicodePredefinedClass(flags, "IsWhiteSpace", negate);
            }
            return perlCharClass(flags, negate, perlSpacesCharClass(flags, false));
        }

        private static Regexp perlWord(int flags, boolean negate)
        {
            if ((flags & Regexp.JAVA_UNICODE_CHARACTER_CLASS) != 0) {
                return unicodePredefinedClass(flags, "IsWord", negate);
            }
            return perlCharClass(flags, negate, perlWordCharClass(flags, false));
        }

        private static Regexp unicodePredefinedClass(int flags, String name, boolean negate)
        {
            CharClass group = requireNonNull(UnicodeGroups.lookup(name), "Unicode group is missing: " + name);
            return perlCharClass(flags, negate, group);
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

        private static CharClass digitsCharClass(int flags, boolean negate)
        {
            return predefinedCharClass(flags, negate, "IsDigit", perlDigitsCharClass(flags, false));
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

        private static CharClass spacesCharClass(int flags, boolean negate)
        {
            return predefinedCharClass(flags, negate, "IsWhiteSpace", perlSpacesCharClass(flags, false));
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

        private static CharClass wordCharClass(int flags, boolean negate)
        {
            return predefinedCharClass(flags, negate, "IsWord", perlWordCharClass(flags, false));
        }

        private static CharClass predefinedCharClass(int flags, boolean negate, String unicodeName, CharClass asciiClass)
        {
            CharClass group = (flags & Regexp.JAVA_UNICODE_CHARACTER_CLASS) != 0
                    ? requireNonNull(UnicodeGroups.lookup(unicodeName), "Unicode group is missing: " + unicodeName)
                    : asciiClass;
            if (!negate) {
                return group;
            }
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            addGroup(characterClassBuilder, group, -1, flags);
            characterClassBuilder.removeAbove(Regexp.maxRune(flags));
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

        private int parseUnicodeEscape(int flags, int sequenceStart)
        {
            int value = parseFourHexDigits(sequenceStart);
            if (Character.isHighSurrogate((char) value)) {
                if (index + 2 > end || bytes[index] != '\\' || bytes[index + 1] != 'u') {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
                index += 2;
                int lowSurrogate = parseFourHexDigits(sequenceStart);
                if (!Character.isLowSurrogate((char) lowSurrogate)) {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
                return Character.toCodePoint((char) value, (char) lowSurrogate);
            }
            if (Character.isLowSurrogate((char) value) || value > Regexp.maxRune(flags)) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            return value;
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

        @SuppressWarnings("UnusedException")
        private int parseNamedCodePoint(int sequenceStart)
        {
            if (atEnd() || consumeByte() != '{') {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            int nameStart = index;
            while (!atEnd() && peekByte() != '}') {
                consumeByte();
            }
            if (nameStart == index || atEnd()) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
            String name = new String(bytes, nameStart, index - nameStart, StandardCharsets.UTF_8);
            consumeByte('}');
            try {
                return Character.codePointOf(name);
            }
            catch (IllegalArgumentException ignored) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }
        }

        private int parseFourHexDigits(int sequenceStart)
        {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                if (atEnd()) {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
                int digit = decodeHexDigit(consumeByte());
                if (digit < 0) {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
                }
                value = (value << 4) | digit;
            }
            return value;
        }

        private int parseOctalEscape(int firstDigit, int flags, int sequenceStart)
        {
            if (firstDigit != '0' || atEnd() || peekByte() < '0' || peekByte() > '7') {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, Slices.wrappedBuffer(bytes, sequenceStart, index - sequenceStart));
            }

            int runeMax = Regexp.maxRune(flags);
            int firstOctalDigit = peekByte();
            int maximumDigits = firstOctalDigit <= '3' ? 3 : 2;
            int code = 0;
            for (int i = 0; i < maximumDigits; i++) {
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
            int rune = readRune(flags);
            return literalFromRune(rune);
        }

        private Regexp literalFromRune(int rune)
        {
            if ((flags & Regexp.ASCII_FOLD_CASE) != 0 &&
                    ((rune >= 'A' && rune <= 'Z') || (rune >= 'a' && rune <= 'z'))) {
                CharClassBuilder characterClassBuilder = new CharClassBuilder();
                characterClassBuilder.addRangeFlags(rune, rune, flags);
                return Regexp.charClass(flags & ~Regexp.ASCII_FOLD_CASE, characterClassBuilder.toCharClass());
            }

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
                else if (UnicodeCaseFold.cycleFoldRune(rune) != rune) {
                    CharClassBuilder characterClassBuilder = new CharClassBuilder();
                    int initialRune = rune;
                    do {
                        if ((flags & Regexp.NEVER_NEWLINE) == 0 || rune != '\n') {
                            characterClassBuilder.addRange(rune, rune);
                        }
                        rune = UnicodeCaseFold.cycleFoldRune(rune);
                    }
                    while (rune != initialRune);
                    return Regexp.charClass(flags & ~Regexp.FOLD_CASE, characterClassBuilder.toCharClass());
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

        private void skipIgnoredPatternCharacters()
        {
            if (inQuote || (flags & Regexp.JAVA_COMMENTS) == 0) {
                return;
            }
            index = ignoredPatternEnd(index);
        }

        private int ignoredPatternEnd(int position)
        {
            if (inQuote || (flags & Regexp.JAVA_COMMENTS) == 0) {
                return position;
            }

            while (position < end) {
                int nextByte = bytes[position] & 0xFF;
                if (isAsciiPatternWhitespace(nextByte)) {
                    position++;
                    continue;
                }
                if (nextByte != '#') {
                    return position;
                }

                position++;
                while (position < end) {
                    long decoded = Utf8.decode(bytes, position, end);
                    int codePoint = Utf8.decodedCodePoint(decoded);
                    int width = Utf8.decodedWidth(decoded);
                    position += width;
                    if (codePoint == '\n' ||
                            ((flags & Regexp.JAVA_UNIX_LINES) == 0 &&
                                    (codePoint == '\r' || codePoint == 0x0085 || codePoint == 0x2028 || codePoint == 0x2029))) {
                        break;
                    }
                }
            }
            return position;
        }

        private static boolean isAsciiPatternWhitespace(int value)
        {
            return value == ' ' ||
                    value == '\t' ||
                    value == '\n' ||
                    value == '\r' ||
                    value == '\f' ||
                    value == 0x0B;
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
