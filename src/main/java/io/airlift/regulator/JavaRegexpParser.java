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
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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

        Parser parser = ((parseFlags & Regexp.LITERAL) == 0)
                ? QuoteRewrite.parser(pattern, parseFlags)
                : new Parser(pattern, pattern, null, parseFlags, CaseFolding.JAVA);
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
        Regexp simplifiedRegexp = Simplifier.simplifyForParse(parsedRegexp, CaseFolding.JAVA);
        // Simplification can remove a group that never matches, but the group keeps its name.
        Map<Integer, String> capturingGroupNames = parser.captureNames.isEmpty() ? Map.of() : parsedRegexp.captureNames();
        return new ParseResult(simplifiedRegexp, parser.capturingGroupCount, capturingGroupNames, CaseFolding.JAVA);
    }

    /// The JDK's quote rewrite, which runs before parsing unless the pattern is literal. Mirrors
    /// `Pattern.RemoveQEQuoting`, which rewrites every `\Q...\E` into ordinary syntax, knowing
    /// nothing of classes or comments. Inside a quote, a backslash becomes an escaped backslash
    /// unless `E` follows it, and every other ASCII character that is not a letter or digit gets a
    /// backslash. Letters and non-ASCII characters are copied unchanged. A digit at the start of a
    /// quote gets a `\x3` prefix, so that it cannot continue an escape before the quote; other
    /// digits are copied unchanged. An empty quote contributes no bytes, so an unfinished escape
    /// before it continues after it. Outside a quote, a backslash and the character after it are
    /// copied. Copying non-ASCII bytes one at a time copies each code point.
    private static final class QuoteRewrite
    {
        private final byte[] rewritten;
        // The original offset of each rewritten byte, then the original length at rewrittenLength.
        private final int[] originalOffsets;
        private int rewrittenLength;

        private QuoteRewrite(int capacity)
        {
            rewritten = new byte[capacity];
            originalOffsets = new int[capacity + 1];
        }

        static Parser parser(Slice pattern, int parseFlags)
        {
            byte[] source = pattern.byteArray();
            int sourceStart = pattern.byteArrayOffset();
            int length = pattern.length();
            int position = 0;
            while (position < length - 1) {
                if (source[sourceStart + position] != '\\') {
                    position++;
                }
                else if (source[sourceStart + position + 1] != 'Q') {
                    position += 2;
                }
                else {
                    break;
                }
            }
            if (position >= length - 1) {
                return new Parser(pattern, pattern, null, parseFlags, CaseFolding.JAVA);
            }

            int prefixLength = position;
            position += 2;
            QuoteRewrite rewrite;
            // Use the JDK's conservative capacity bound: quoted punctuation can double, and a quote's
            // first digit becomes four bytes.
            try {
                rewrite = new QuoteRewrite(Math.addExact(prefixLength + 2, Math.multiplyExact(3, length - position)));
            }
            catch (ArithmeticException _) {
                throw new OutOfMemoryError("Required pattern length too large");
            }
            for (int offset = 0; offset < prefixLength; offset++) {
                rewrite.append(source[sourceStart + offset], offset);
            }

            boolean inQuote = true;
            boolean beginQuote = true;
            while (position < length) {
                int originalOffset = position;
                byte character = source[sourceStart + position++];
                if (character < 0 || isAsciiLetter(character)) {
                    rewrite.append(character, originalOffset);
                }
                else if (isAsciiDigit(character)) {
                    if (beginQuote) {
                        rewrite.append((byte) '\\', originalOffset);
                        rewrite.append((byte) 'x', originalOffset);
                        rewrite.append((byte) '3', originalOffset);
                    }
                    rewrite.append(character, originalOffset);
                }
                else if (character != '\\') {
                    if (inQuote) {
                        rewrite.append((byte) '\\', originalOffset);
                    }
                    rewrite.append(character, originalOffset);
                }
                else {
                    int next = (position < length) ? source[sourceStart + position] : 0;
                    if (inQuote) {
                        if (next == 'E') {
                            position++;
                            inQuote = false;
                        }
                        else {
                            rewrite.append((byte) '\\', originalOffset);
                            rewrite.append((byte) '\\', originalOffset);
                        }
                    }
                    else if (next == 'Q') {
                        position++;
                        inQuote = true;
                        beginQuote = true;
                        continue;
                    }
                    else {
                        rewrite.append(character, originalOffset);
                        if (position != length) {
                            rewrite.append(source[sourceStart + position], position);
                            position++;
                        }
                    }
                }
                beginQuote = false;
            }
            rewrite.originalOffsets[rewrite.rewrittenLength] = length;
            Slice rewritten = Slices.wrappedBuffer(rewrite.rewritten, 0, rewrite.rewrittenLength);
            return new Parser(rewritten, pattern, rewrite.originalOffsets, parseFlags, CaseFolding.JAVA);
        }

        private void append(byte value, int originalOffset)
        {
            originalOffsets[rewrittenLength] = originalOffset;
            rewritten[rewrittenLength++] = value;
        }
    }

    private static boolean isAsciiLetter(int character)
    {
        return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    }

    private static boolean isAsciiDigit(int value)
    {
        return value >= '0' && value <= '9';
    }

    @SuppressWarnings("CharUsedInArithmeticContext")
    private static final class Parser
    {
        private static final CharClass PERL_DIGITS_CLASS = buildPerlDigitsCharClass();

        private final byte[] bytes;
        private final int start;
        private final int end;
        // Errors report the pattern as written, which differs from the parsed bytes when the
        // quote rewrite changed them; see QuoteRewrite.
        private final Slice originalPattern;
        // Maps rewritten byte offsets to original offsets; null when no rewrite was needed.
        private final int[] originalOffsets;
        private final CaseFolding caseFolding;
        private int index;

        private int flags;
        private int capturingGroupCount;
        private final Set<String> captureNames = new HashSet<>();
        // The repetitions with a minimum of one or more whose atom a check found cannot match the
        // empty string, so that later checks need not walk them again; see canMatchEmpty.
        private Set<Regexp> nonEmptyRepetitions;

        Parser(Slice pattern, Slice originalPattern, int[] originalOffsets, int flags, CaseFolding caseFolding)
        {
            this.bytes = pattern.byteArray();
            this.start = pattern.byteArrayOffset();
            this.end = start + pattern.length();
            this.originalPattern = originalPattern;
            this.originalOffsets = originalOffsets;
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
            return patternSegment(index, width);
        }

        private Slice errorArgAll()
        {
            return originalPattern;
        }

        // The original pattern bytes from which the length parsed bytes starting at offset were
        // rewritten. A segment that reaches the end of the parsed pattern reaches the end of the
        // original.
        private Slice patternSegment(int offset, int length)
        {
            if (originalOffsets == null) {
                return Slices.wrappedBuffer(bytes, offset, length);
            }
            int segmentStart = originalOffset(offset);
            int segmentEnd;
            if (offset + length == end) {
                segmentEnd = originalPattern.length();
            }
            else {
                // Each parsed byte comes from exactly one original byte.
                segmentEnd = originalOffset(offset + length - 1) + 1;
            }
            return originalPattern.slice(segmentStart, segmentEnd - segmentStart);
        }

        // The original byte offset of a parsed byte offset.
        private int originalOffset(int offset)
        {
            int parsedOffset = Math.max(offset - start, 0);
            return (originalOffsets == null) ? parsedOffset : originalOffsets[parsedOffset];
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
            return new RegexpParseException(errorCode, invalidPatternSegment, originalOffset(index));
        }

        private RegexpParseException unsupportedConstruct(String construct, int constructStart, int constructEnd)
        {
            return new RegexpParseException(
                    RegexpParseErrorCode.UNSUPPORTED_CONSTRUCT,
                    patternSegment(constructStart, constructEnd - constructStart),
                    originalOffset(constructStart),
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
                int nextByte = peekByte();

                if (nextByte == '|') {
                    consumeByte('|');
                    group.expression().nextAlternative(flags);
                    continue;
                }

                if (nextByte == ')') {
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
                    boolean atomHasCaptures = capturingGroupCount > completed.capturesBefore();
                    groups.peek().expression().add(parseRepeatSuffix(atom, atomHasCaptures));
                    continue;
                }

                if (nextByte == '(') {
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

        // capturesBefore is the number of capturing groups opened before this group.
        private record GroupFrame(
                ExpressionBuilder expression,
                int restoreFlags,
                int groupFlags,
                boolean capturing,
                int captureIndex,
                Slice name,
                int capturesBefore)
        {
            static GroupFrame root(int flags)
            {
                return new GroupFrame(new ExpressionBuilder(flags), flags, flags, false, -1, null, 0);
            }
        }

        // repeat := atom (('*' | '+' | '?' | '{m,n}') '?'?)*, where the atom is empty when a count
        // stands in atom position, including right after another repetition.
        private Regexp parseRepeat()
        {
            // Only a group can hold a capture.
            return parseRepeatSuffix(parseAtom(), false);
        }

        private Regexp parseRepeatSuffix(Regexp atom, boolean atomHasCaptures)
        {
            boolean appliedRepeat = false;

            while (!atEnd()) {
                skipIgnoredPatternCharacters();
                if (atEnd()) {
                    break;
                }
                int nextByte = peekByte();
                if (nextByte == '{' && appliedRepeat) {
                    // As in the JDK, a count after a repetition repeats an empty atom of its own.
                    break;
                }
                if (nextByte == '*' || nextByte == '+' || nextByte == '?' || nextByte == '{') {
                    // A '*', '+', or '?' after a repetition is a syntax error in Perl extensions mode.
                    // In non-Perl extensions mode, it is allowed and will be simplified later.
                    if (appliedRepeat && (flags & Regexp.PERL_EXTENSIONS) != 0) {
                        throw error(RegexpParseErrorCode.BAD_REPEAT_OP, errorArgAtCurrent());
                    }
                    atom = applyRepeat(atom, atomHasCaptures);
                    appliedRepeat = true;
                    continue;
                }
                break;
            }

            return atom;
        }

        private Regexp applyRepeat(Regexp atom, boolean atomHasCaptures)
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
                int[] repetitionCounts = parseRepetitionCounts(repetitionStart);
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
            boolean checked = max != 1 && atomHasCaptures;
            if (checked && canMatchEmpty(atom)) {
                throw unsupportedConstruct("nullable repeated captures", repetitionStart, index);
            }

            Regexp repetition = switch (repetitionOperator) {
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
            if (checked && min > 0) {
                // The atom cannot match the empty string, so neither can the repetition.
                if (nonEmptyRepetitions == null) {
                    nonEmptyRepetitions = Collections.newSetFromMap(new IdentityHashMap<>());
                }
                nonEmptyRepetitions.add(repetition);
            }
            return repetition;
        }

        private enum RepetitionOperator
        {
            STAR, PLUS, QUEST, REPEAT
        }

        /// Whether a repeated atom that holds a capture can match the empty string, which makes the
        /// repetition a nullable repeated capture. Must agree with ExpressionAnalysis.canMatchEmpty
        /// for parse trees. The walk reads each node's children in order up to the first that
        /// decides it, and stops at the repetitions earlier checks recorded. Every walk starts at
        /// the atom of a repetition being checked, and once checked that repetition is recorded or
        /// matches the empty string, so a later walk stops there and never enters a node an earlier
        /// walk entered. The checks therefore stay linear in the pattern.
        private boolean canMatchEmpty(Regexp atom)
        {
            // The nodes whose value waits on a child, with the index of that child.
            Regexp[] path = new Regexp[8];
            int[] childIndexes = new int[8];
            int depth = 0;
            Regexp node = atom;
            while (true) {
                EmptyMatch emptyMatch = emptyMatchWithoutChildren(node);
                if (emptyMatch == EmptyMatch.DEPENDS_ON_CHILDREN) {
                    if (depth == path.length) {
                        path = Arrays.copyOf(path, depth * 2);
                        childIndexes = Arrays.copyOf(childIndexes, depth * 2);
                    }
                    path[depth] = node;
                    childIndexes[depth] = 0;
                    depth++;
                    node = node.child(0);
                    continue;
                }
                // A decided child decides its parent with the same value, unless the parent
                // needs its next child.
                boolean matchesEmpty = emptyMatch == EmptyMatch.CAN_MATCH_EMPTY;
                while (true) {
                    if (depth == 0) {
                        return matchesEmpty;
                    }
                    Regexp parent = path[depth - 1];
                    int nextChildIndex = childIndexes[depth - 1] + 1;
                    boolean needsNextChild = nextChildIndex < parent.childCount() &&
                            ((parent.op() == RegexpOp.CONCAT && matchesEmpty) || (parent.op() == RegexpOp.ALTERNATE && !matchesEmpty));
                    if (needsNextChild) {
                        childIndexes[depth - 1] = nextChildIndex;
                        node = parent.child(nextChildIndex);
                        break;
                    }
                    depth--;
                }
            }
        }

        private enum EmptyMatch
        {
            CAN_MATCH_EMPTY, CANNOT_MATCH_EMPTY, DEPENDS_ON_CHILDREN
        }

        // Whether a node can match the empty string, as far as the node alone decides it.
        private EmptyMatch emptyMatchWithoutChildren(Regexp regexp)
        {
            return switch (regexp.op()) {
                case NO_MATCH, LITERAL, LITERAL_STRING, CHAR_CLASS, ANY_CHAR, ANY_BYTE -> EmptyMatch.CANNOT_MATCH_EMPTY;
                case EMPTY_MATCH, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT, HAVE_MATCH -> EmptyMatch.CAN_MATCH_EMPTY;
                case STAR, QUEST -> EmptyMatch.CAN_MATCH_EMPTY;
                case CONCAT -> (regexp.childCount() == 0) ? EmptyMatch.CAN_MATCH_EMPTY : EmptyMatch.DEPENDS_ON_CHILDREN;
                case ALTERNATE -> (regexp.childCount() == 0) ? EmptyMatch.CANNOT_MATCH_EMPTY : EmptyMatch.DEPENDS_ON_CHILDREN;
                case CAPTURE -> EmptyMatch.DEPENDS_ON_CHILDREN;
                case PLUS -> nonEmptyRepetitionEmptyMatch(regexp);
                case REPEAT -> (regexp.min() == 0) ? EmptyMatch.CAN_MATCH_EMPTY : nonEmptyRepetitionEmptyMatch(regexp);
            };
        }

        private EmptyMatch nonEmptyRepetitionEmptyMatch(Regexp repetition)
        {
            return (nonEmptyRepetitions != null && nonEmptyRepetitions.contains(repetition)) ? EmptyMatch.CANNOT_MATCH_EMPTY : EmptyMatch.DEPENDS_ON_CHILDREN;
        }

        // Reads the counts after '{' as the JDK's closure does: the character right after the brace
        // as written, but in comments mode every later character past whitespace and comments, so
        // that (?x)a{1 2} is a{12} while (?x)a{ 1} is an error. A malformed count is an error rather
        // than literal text.
        private int[] parseRepetitionCounts(int braceStart)
        {
            if (atEnd() || !isAsciiDigit(peekByte())) {
                throw error(RegexpParseErrorCode.REPEAT_ARGUMENT, patternSegment(braceStart, index - braceStart));
            }
            int minRepetitions = parseRepetitionCount();
            int maxRepetitions = minRepetitions;
            if (!atEnd() && peekByte() == ',') {
                consumeByte(',');
                skipIgnoredPatternCharacters();
                maxRepetitions = (!atEnd() && peekByte() == '}') ? -1 : parseRepetitionCount();
            }
            if (atEnd() || peekByte() != '}') {
                throw error(RegexpParseErrorCode.REPEAT_ARGUMENT, patternSegment(braceStart, index - braceStart));
            }
            consumeByte('}');

            if (maxRepetitions != -1 && maxRepetitions < minRepetitions) {
                throw error(RegexpParseErrorCode.REPEAT_ARGUMENT, null);
            }

            if (minRepetitions > MAX_REPEAT || maxRepetitions > MAX_REPEAT) {
                throw error(RegexpParseErrorCode.REPEAT_SIZE, null);
            }

            return new int[] {minRepetitions, maxRepetitions};
        }

        // atom := '(' ... ')' | '[' ... ']' | '.' | '^' | '$' | '\' escape | literal | empty before '{'
        private Regexp parseAtom()
        {
            skipIgnoredPatternCharacters();
            if (atEnd()) {
                return null;
            }

            int nextByte = peekByte();

            // As in the JDK's atom(), a count with no atom before it gets the empty atom, which the
            // caller then repeats; '*', '+', and '?' with no atom before them are errors.
            if (nextByte == '{') {
                return Regexp.emptyMatch(flags);
            }
            if (nextByte == '*' || nextByte == '+' || nextByte == '?') {
                throw error(RegexpParseErrorCode.REPEAT_ARGUMENT, patternSegment(index, 1));
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

        private GroupFrame parseGroupStart()
        {
            int groupFlags = flags;
            int groupStart = index;
            consumeByte('(');
            boolean capturing = (flags & Regexp.NEVER_CAPTURE) == 0;
            Slice name = null;
            int captureIndex = -1;

            // As in the JDK's group0, in comments mode whitespace and comments may separate '(' from
            // '?', but the character after '?' is read as written. The name of (?<name>...) and a
            // lookbehind's '=' or '!' are read past whitespace and comments again.
            skipIgnoredPatternCharacters();
            if (!atEnd() && peekByte() == '?') {
                if ((flags & Regexp.PERL_EXTENSIONS) == 0) {
                    // Perl-style group syntax is only available under Perl extensions.
                    throw error(RegexpParseErrorCode.BAD_PERL_OP, patternSegment(groupStart, index - groupStart + 1));
                }
                consumeByte('?');
                if (!atEnd()) {
                    int c = peekByte();
                    if (c == '=' || c == '!') {
                        throw unsupportedConstruct("lookahead", groupStart, index + 1);
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
                    throw error(RegexpParseErrorCode.BAD_PERL_OP, patternSegment(groupStart, index - groupStart + 1));
                }
                else if (!atEnd() && peekByte() == '<') {
                    consumeByte('<');
                    skipIgnoredPatternCharacters();
                    if (!atEnd() && (peekByte() == '=' || peekByte() == '!')) {
                        throw unsupportedConstruct("lookbehind", groupStart, index + 1);
                    }
                    name = parseCaptureName(groupStart);
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
                    return new GroupFrame(new ExpressionBuilder(flags), saved, groupFlags, false, -1, null, capturingGroupCount);
                }
            }

            int capturesBefore = capturingGroupCount;
            if (capturing) {
                capturingGroupCount++;
                captureIndex = capturingGroupCount;
            }
            return new GroupFrame(new ExpressionBuilder(flags), groupFlags, groupFlags, capturing, captureIndex, name, capturesBefore);
        }

        private int parseInlineFlags(int base, int groupStart)
        {
            int inlineFlags = base;
            boolean clearing = false;
            // Track 'c' only within this flag header; a completed header that leaves it enabled is
            // rejected below.
            boolean canonicalEquivalence = false;

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
                    if (clearing) {
                        // As in the JDK's addFlag, the flags after '-' end at a second '-'.
                        break;
                    }
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
                    case 'c' -> canonicalEquivalence = !clearing;
                    default -> throw error(RegexpParseErrorCode.BAD_PERL_OP, patternSegment(groupStart, index - groupStart));
                }
            }

            if (canonicalEquivalence && !atEnd() && (peekByte() == ')' || peekByte() == ':')) {
                throw unsupportedConstruct("canonical equivalence", groupStart, index + 1);
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

        // Reads a capture name and its '>' as the JDK's groupname does: an ASCII letter, then ASCII
        // letters and digits. In comments mode the caller skips whitespace and comments before the
        // first letter, and this method skips them after each character of the name.
        private Slice parseCaptureName(int groupStart)
        {
            StringBuilder captureName = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE, patternSegment(groupStart, end - groupStart));
                }
                int character = peekByte();
                boolean valid = isAsciiLetter(character) || (!captureName.isEmpty() && isAsciiDigit(character));
                if (!valid) {
                    if (character == '>' && !captureName.isEmpty()) {
                        break;
                    }
                    throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE, patternSegment(groupStart, index + runeByteWidthAt(index) - groupStart));
                }
                captureName.append((char) consumeByte());
                skipIgnoredPatternCharacters();
            }
            String name = captureName.toString();
            if (!captureNames.add(name)) {
                throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE, patternSegment(groupStart, index + 1 - groupStart));
            }
            consumeByte('>');
            return Slices.utf8Slice(name);
        }

        private Regexp parseCharClass()
        {
            int nodeFlags = flags;
            CharClassBuilder characterClassBuilder = parseCharClassBuilder(nodeFlags);
            return Regexp.charClass(nodeFlags & ~Regexp.FOLD_CASE, characterClassBuilder.toCharClass());
        }

        // Nested classes keep their state on an explicit stack, as groups do in parseExpression,
        // so nesting depth is not limited by the Java stack. The levels follow the JDK's clazz and
        // range, including which single characters collect in a level's bits.
        private CharClassBuilder parseCharClassBuilder(int nodeFlags)
        {
            Deque<ClassFrame> classes = new ArrayDeque<>();
            classes.push(openClass());
            while (true) {
                ClassFrame frame = classes.peek();
                skipIgnoredPatternCharacters();
                if (atEnd()) {
                    throw error(RegexpParseErrorCode.MISSING_BRACKET, patternSegment(frame.classStart, end - frame.classStart));
                }
                int nextByte = peekByte();
                if (frame.readingRightOperand) {
                    // The right operand of '&&' is a union of nested classes and of classes without
                    // brackets. A class without brackets ends at a ']', which it leaves for the
                    // class around it.
                    if (nextByte == ']' || nextByte == '&') {
                        if (!frame.finishIntersection()) {
                            throw error(RegexpParseErrorCode.BAD_CHAR_CLASS, patternSegment(frame.classStart, index + 1 - frame.classStart));
                        }
                    }
                    else if (nextByte == '[') {
                        classes.push(openClass());
                    }
                    else {
                        classes.push(new ClassFrame(frame.classStart, false, false));
                    }
                    continue;
                }

                if (nextByte == '[') {
                    classes.push(openClass());
                    continue;
                }
                if (nextByte == '&') {
                    if (tryConsumeIntersection()) {
                        frame.startIntersection();
                        continue;
                    }
                    skipIgnoredPatternCharacters();
                    if (atEnd()) {
                        throw error(RegexpParseErrorCode.MISSING_BRACKET, patternSegment(frame.classStart, end - frame.classStart));
                    }
                }
                else if (nextByte == ']' && frame.hasMembers()) {
                    if (frame.bracketed) {
                        consumeByte(']');
                    }
                    classes.pop();
                    ClassFrame parent = classes.peek();
                    if (parent == null) {
                        CharClassBuilder characterClassBuilder = frame.toCharClassBuilder();
                        if (frame.negated) {
                            characterClassBuilder.negate();
                        }
                        characterClassBuilder.removeAbove(Regexp.maxRune(nodeFlags));
                        return characterClassBuilder;
                    }
                    RuneRangeSet characterClass = frame.toRuneRangeSet();
                    if (frame.negated) {
                        characterClass.negate();
                    }
                    characterClass.removeAbove(Regexp.maxRune(nodeFlags));
                    if (parent.readingRightOperand) {
                        parent.addRightOperand(ClassExpression.constant(characterClass));
                    }
                    else {
                        parent.addMember(ClassExpression.constant(characterClass));
                    }
                    continue;
                }
                parseClassRange(nodeFlags, frame);
            }
        }

        private ClassFrame openClass()
        {
            int classStart = index;
            consumeByte('[');
            return new ClassFrame(classStart, true, tryConsumeNegation());
        }

        // Reads one member as the JDK's range does: a class escape, a range, or a single character.
        // A single character below U+0100 joins the level's bits, except, under Unicode case
        // folding, the characters with case partners outside Latin-1, which isUnicodeCaseSingle
        // lists.
        private void parseClassRange(int nodeFlags, ClassFrame frame)
        {
            if (peekByte() == '\\') {
                CharClassBuilder escapeClass = tryParseClassEscape(nodeFlags);
                if (escapeClass != null) {
                    frame.addMember(ClassExpression.constant(RuneRangeSet.of(escapeClass)));
                    return;
                }
            }

            int low = parseLiteralRune(nodeFlags);
            int high = low;
            if (tryConsumeRangeOperator()) {
                high = parseLiteralRune(nodeFlags);
                if (high < low) {
                    throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, null);
                }
            }
            else if (low < 0x100 && !((nodeFlags & Regexp.FOLD_CASE) != 0 && isUnicodeCaseSingle(low))) {
                frame.addBit(low, nodeFlags, caseFolding);
                return;
            }

            // For explicit singletons/ranges, do not implicitly filter '\n' out.
            CharClassBuilder member = new CharClassBuilder();
            member.addRangeFlags(low, high, nodeFlags | Regexp.CLASS_NEWLINE, caseFolding);
            frame.addMember(ClassExpression.constant(RuneRangeSet.of(member)));
        }

        private static boolean isUnicodeCaseSingle(int rune)
        {
            return switch (rune) {
                case 0xFF, 0xB5, 'I', 'i', 'S', 's', 'K', 'k', 0xC5, 0xE5, 0xDF -> true;
                default -> false;
            };
        }

        // One level of the JDK's clazz: a bracketed class, or an intersection operand that the
        // JDK reads as a class without brackets. The JDK adds most single characters below U+0100
        // to the level's bits, and the expressions that refer to the bits read them only once the
        // level closes, so a character added after an intersection can change that intersection.
        private static final class ClassFrame
        {
            private enum Current
            {
                // The JDK's curr is null: the class has no members yet, or the last member joined
                // the bits.
                NONE,
                // The JDK's curr is the last member, held in last.
                LAST,
                // The JDK's curr is the operand of the intersection just before, which the class
                // lies within.
                INTERSECTED,
            }

            private final int classStart;
            private final boolean bracketed;
            private final boolean negated;
            // The JDK's prev is the union of previous and last.
            private ClassExpression previous;
            private ClassExpression last;
            private Current current = Current.NONE;
            private CharClassBuilder bits;
            // The bits still need to join the current left operand; the builder outlives that.
            private boolean hasBits;
            // An '&&' has started and its right operand is being collected.
            private boolean readingRightOperand;
            // The union of completed classes in that right operand, or null.
            private ClassExpression right;

            ClassFrame(int classStart, boolean bracketed, boolean negated)
            {
                this.classStart = classStart;
                this.bracketed = bracketed;
                this.negated = negated;
            }

            boolean hasPrevious()
            {
                return previous != null || last != null;
            }

            boolean hasMembers()
            {
                return hasPrevious() || hasBits;
            }

            void addMember(ClassExpression member)
            {
                previous = takePrevious();
                last = member;
                current = Current.LAST;
            }

            void addBit(int rune, int nodeFlags, CaseFolding caseFolding)
            {
                previous = takePrevious();
                if (bits == null) {
                    bits = new CharClassBuilder();
                }
                // As for explicit ranges, an explicit newline stays a member.
                bits.addRangeFlags(rune, rune, nodeFlags | Regexp.CLASS_NEWLINE, caseFolding);
                hasBits = true;
                current = Current.NONE;
            }

            void addRightOperand(ClassExpression operand)
            {
                right = (right == null) ? operand : right.union(operand);
            }

            void startIntersection()
            {
                readingRightOperand = true;
            }

            // Ends the right operand of an intersection at a ']' or '&', as the JDK's clazz does. An
            // empty right operand intersects the class with the JDK's curr instead: its last
            // member, the operand of the intersection just before, or, when the left operand is
            // only bits, those bits. Returns false when there is no curr, because the class has no
            // members or its last member joined the bits after other members.
            boolean finishIntersection()
            {
                ClassExpression operand = right;
                right = null;
                readingRightOperand = false;
                if (hasBits) {
                    // The bits join the left operand. When they are all of it, they are the JDK's
                    // curr, which an empty right operand here leaves in place.
                    hasBits = false;
                    if (!hasPrevious()) {
                        previous = ClassExpression.bits();
                        current = Current.INTERSECTED;
                    }
                    else if (previous == null) {
                        previous = ClassExpression.bits();
                    }
                    else {
                        previous = previous.withEveryBit();
                    }
                }

                if (operand != null) {
                    ClassExpression left = takePrevious();
                    previous = (left == null) ? operand : left.intersection(operand);
                    current = Current.INTERSECTED;
                    return true;
                }
                switch (current) {
                    case NONE -> {
                        return false;
                    }
                    // The JDK intersects the class with its last member, which the class contains.
                    case LAST -> {
                        previous = last;
                        last = null;
                        current = Current.INTERSECTED;
                    }
                    // The class already lies within the operand of the intersection just before.
                    case INTERSECTED -> {}
                }
                return true;
            }

            ClassExpression takePrevious()
            {
                ClassExpression union = previous;
                if (last != null) {
                    union = (union == null) ? last : union.union(last);
                    last = null;
                }
                previous = null;
                return union;
            }

            RuneRangeSet toRuneRangeSet()
            {
                return finish().evaluate(bits);
            }

            CharClassBuilder toCharClassBuilder()
            {
                if (!hasPrevious()) {
                    return bits;
                }
                return toRuneRangeSet().toCharClassBuilder();
            }

            private ClassExpression finish()
            {
                ClassExpression union = takePrevious();
                if (union == null) {
                    return ClassExpression.bits();
                }
                return hasBits ? union.withEveryBit() : union;
            }
        }

        // A union or intersection of constant sets and a class level's bits, whose members the
        // JDK reads only when the level closes. Every such expression equals fixed ∪ (bits ∩
        // filter), with the part of fixed below U+0100 in filter. Operations consume their inputs.
        // A constant always has fixed members, an expression that uses the bits is evaluated with
        // the level's bits builder, and the operand of a union or intersection is always a
        // constant.
        private static final class ClassExpression
        {
            // The bits only hold characters below U+0100: parseClassRange adds no other single
            // character to them, and under Unicode case folding leaves out every character whose
            // case partners lie beyond. A filter therefore matters only up to this rune.
            private static final int MAX_BIT = 0xFF;

            // The members whatever the bits hold, or null for none.
            private final RuneRangeSet fixed;
            // The bits that are members, or null for all of them. A constant uses the part of
            // fixed up to MAX_BIT, so a filter holds at most 128 ranges.
            private final RuneRangeSet filter;
            private final boolean constant;

            private ClassExpression(RuneRangeSet fixed, RuneRangeSet filter, boolean constant)
            {
                this.fixed = fixed;
                this.filter = filter;
                this.constant = constant;
            }

            static ClassExpression constant(RuneRangeSet members)
            {
                return new ClassExpression(requireNonNull(members, "members is null"), null, true);
            }

            static ClassExpression bits()
            {
                return new ClassExpression(null, null, false);
            }

            ClassExpression withEveryBit()
            {
                return new ClassExpression(fixed, null, false);
            }

            ClassExpression union(ClassExpression other)
            {
                if (constant) {
                    return constant(fixed.union(other.fixed));
                }
                RuneRangeSet unionFilter = (filter == null) ? null : filter.union(other.fixed.copyAtOrBelow(MAX_BIT));
                return new ClassExpression((fixed == null) ? other.fixed : fixed.union(other.fixed), unionFilter, false);
            }

            ClassExpression intersection(ClassExpression other)
            {
                if (constant) {
                    return constant(fixed.intersection(other.fixed));
                }
                RuneRangeSet otherFilter = other.fixed.copyAtOrBelow(MAX_BIT);
                RuneRangeSet intersectionFilter = (filter == null) ? otherFilter : filter.intersection(otherFilter);
                return new ClassExpression((fixed == null) ? null : fixed.intersection(other.fixed), intersectionFilter, false);
            }

            // Consumes the expression and the level's bits, which an expression that uses them
            // always has.
            RuneRangeSet evaluate(CharClassBuilder bits)
            {
                if (constant) {
                    return fixed;
                }
                RuneRangeSet members = RuneRangeSet.of(bits);
                if (filter != null) {
                    members = members.intersection(filter);
                }
                return (fixed == null) ? members : members.union(fixed);
            }
        }

        private boolean tryConsumeNegation()
        {
            if (atEnd() || peekByte() != '^') {
                return false;
            }
            consumeByte('^');
            return true;
        }

        // At an '&', consumes the '&&' of an intersection, or leaves the class at an atom. In
        // comments mode the JDK's clazz reads past the whitespace and comments after an '&', and
        // when the next character is not '&', backs up one code point and reads a class atom from
        // there, even ']' or '['. That code point is usually whitespace or a newline that ends a
        // comment, which is skipped, so the '&' is dropped. When a comment ends at a terminator
        // that is not whitespace, such as U+2028, the terminator becomes a member; if the comment
        // has text after '#', the class resumes at its last character, which becomes a member
        // too. Without whitespace or comments after it, '&' is a literal.
        private boolean tryConsumeIntersection()
        {
            int next = ignoredPatternEnd(index + 1);
            if (next < end && bytes[next] == '&') {
                index = next + 1;
                return true;
            }
            if (next != index + 1) {
                index = next - 1;
                while ((bytes[index] & 0xC0) == 0x80) {
                    index--;
                }
            }
            return false;
        }

        private int parseLiteralRune(int flags)
        {
            skipIgnoredPatternCharacters();
            return parseClassAtomRune(flags);
        }

        private boolean tryConsumeRangeOperator()
        {
            skipIgnoredPatternCharacters();
            if (atEnd() || peekByte() != '-') {
                return false;
            }

            // As in the JDK, the byte right after '-' decides whether a range starts; once it
            // does, the endpoint is read past comments-mode text, even if it is ']' or '['.
            if (index + 1 >= end || bytes[index + 1] == ']' || bytes[index + 1] == '[') {
                return false;
            }
            index++;
            return true;
        }

        // At a '\', reads a class escape such as \d or \p{L}, or returns null for any other
        // escape.
        private CharClassBuilder tryParseClassEscape(int nodeFlags)
        {
            if (index + 1 >= end) {
                return null;
            }

            int escapedByte = bytes[index + 1] & 0xFF;
            if (escapedByte == 'v' && index + 2 < end && bytes[index + 2] == '-') {
                return null;
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
                CharClassBuilder characterClassBuilder = new CharClassBuilder();
                addUnfoldedGroup(characterClassBuilder, characterClass, 1, nodeFlags);
                return characterClassBuilder;
            }

            if (escapedByte == 'p' || escapedByte == 'P') {
                index += 2;
                CharClassBuilder characterClassBuilder = new CharClassBuilder();
                parseUnicodeGroupInto(characterClassBuilder, (escapedByte == 'P') ? -1 : 1, nodeFlags, index - 2);
                return characterClassBuilder;
            }
            return null;
        }

        private int parseClassAtomRune(int flags)
        {
            if (atEnd()) {
                throw error(RegexpParseErrorCode.BAD_CHAR_CLASS, null);
            }
            if (peekByte() == '\\') {
                int sequenceStart = index;
                consumeByte('\\');
                if (atEnd()) {
                    throw error(RegexpParseErrorCode.MISSING_BRACKET, patternSegment(sequenceStart, end - sequenceStart));
                }
                int escapedByte = consumeByte();
                // As in the JDK's escape(), accept the known escapes and any character other than an
                // ASCII letter or digit as itself. A class escape such as \d reaches here only as the
                // end of a range, which the JDK rejects.
                return switch (escapedByte) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'f' -> '\f';
                    case 'v' -> 0x0B;
                    case 'a' -> 0x07;
                    case 'e' -> 0x1B;
                    case 'd', 'D', 'h', 'H', 's', 'S', 'V', 'w', 'W' -> throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, patternSegment(sequenceStart, index - sequenceStart));
                    case 'c' -> parseControlEscape(flags, sequenceStart);
                    case 'N' -> parseNamedCodePoint(sequenceStart);
                    case 'x' -> parseHexEscape(flags, sequenceStart);
                    case 'u' -> parseUnicodeEscape(flags, sequenceStart);
                    case '0' -> parseOctalEscape(escapedByte, flags, sequenceStart);
                    default -> {
                        if (escapedByte >= 0x80) {
                            index--;
                            yield readRune(flags);
                        }
                        if (!Character.isLetterOrDigit((char) escapedByte)) {
                            yield escapedByte;
                        }
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
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
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                    }
                    yield Regexp.beginText(flags);
                }
                case 'z' -> {
                    if ((flags & Regexp.PERL_EXTENSIONS) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                    }
                    yield Regexp.endText(flags);
                }
                case 'Z' -> {
                    int nodeFlags = flags;
                    nodeFlags |= (flags & Regexp.JAVA_UNIX_LINES) != 0 ? Regexp.FINAL_LINE_END : Regexp.JAVA_FINAL_END;
                    yield Regexp.endText(nodeFlags);
                }
                case 'b' -> {
                    // As in the JDK's escape(), \b{g} is the grapheme form only when g follows the brace as
                    // written; any other brace is left to the repetition parser, so \b{2} repeats \b.
                    int braceOpen = ignoredPatternEnd(index);
                    if (braceOpen + 1 < end && bytes[braceOpen] == '{' && bytes[braceOpen + 1] == 'g') {
                        int braceClose = ignoredPatternEnd(braceOpen + 2);
                        if (braceClose < end && bytes[braceClose] == '}') {
                            throw unsupportedConstruct("grapheme cluster boundary \\b{g}", sequenceStart, braceClose + 1);
                        }
                        index = braceClose;
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, braceClose + runeByteWidthAt(braceClose) - sequenceStart));
                    }
                    yield Regexp.wordBoundary(flags | Regexp.JAVA_WORD_BOUNDARY);
                }
                case 'B' -> Regexp.noWordBoundary(flags | Regexp.JAVA_WORD_BOUNDARY);
                case 'X' -> throw unsupportedConstruct("grapheme cluster \\X", sequenceStart, index);
                case 'G' -> throw unsupportedConstruct("previous-match boundary \\G", sequenceStart, index);
                case 'R' -> throw unsupportedConstruct("linebreak escape \\R", sequenceStart, index);
                case 'd' -> {
                    if ((flags & Regexp.PERL_CLASSES) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                    }
                    yield perlDigits(flags, false);
                }
                case 'D' -> {
                    if ((flags & Regexp.PERL_CLASSES) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                    }
                    yield perlDigits(flags, true);
                }
                case 's' -> {
                    if ((flags & Regexp.PERL_CLASSES) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                    }
                    yield perlSpaces(flags, false);
                }
                case 'S' -> {
                    if ((flags & Regexp.PERL_CLASSES) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                    }
                    yield perlSpaces(flags, true);
                }
                case 'w' -> {
                    if ((flags & Regexp.PERL_CLASSES) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                    }
                    yield perlWord(flags, false);
                }
                case 'W' -> {
                    if ((flags & Regexp.PERL_CLASSES) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                    }
                    yield perlWord(flags, true);
                }
                case 'h' -> Regexp.charClass(flags & ~Regexp.FOLD_CASE, horizontalWhitespace(flags, false));
                case 'H' -> Regexp.charClass(flags & ~Regexp.FOLD_CASE, horizontalWhitespace(flags, true));
                case 'v' -> Regexp.charClass(flags & ~Regexp.FOLD_CASE, verticalWhitespace(flags, false));
                case 'V' -> Regexp.charClass(flags & ~Regexp.FOLD_CASE, verticalWhitespace(flags, true));
                case 'p', 'P' -> {
                    if ((flags & Regexp.UNICODE_GROUPS) == 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                    }
                    CharClassBuilder characterClassBuilder = new CharClassBuilder();
                    parseUnicodeGroupInto(characterClassBuilder, (escapedByte == 'P') ? -1 : 1, flags, index - 2);
                    characterClassBuilder.removeAbove(Regexp.maxRune(flags));
                    yield Regexp.charClass(flags & ~Regexp.FOLD_CASE, characterClassBuilder.toCharClass());
                }
                case 'Q', 'E' -> throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                case 'n' -> literalFromRune('\n');
                case 'r' -> literalFromRune('\r');
                case 't' -> literalFromRune('\t');
                case 'f' -> literalFromRune('\f');
                case 'a' -> literalFromRune(0x07);
                case 'e' -> literalFromRune(0x1B);
                case 'c' -> literalFromRune(parseControlEscape(flags, sequenceStart));
                case 'N' -> literalFromRune(parseNamedCodePoint(sequenceStart));
                case 'x' -> literalFromRune(parseHexEscape(flags, sequenceStart));
                case 'u' -> literalFromRune(parseUnicodeEscape(flags, sequenceStart));
                case '0' -> literalFromRune(parseOctalEscape(escapedByte, flags, sequenceStart));
                case '1', '2', '3', '4', '5', '6', '7', '8', '9' -> throw unsupportedConstruct("backreferences", sequenceStart, index);
                case 'k' -> {
                    int nameOpen = ignoredPatternEnd(index);
                    if (nameOpen < end && bytes[nameOpen] == '<') {
                        throw unsupportedConstruct("backreferences", sequenceStart, index);
                    }
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                }
                default -> {
                    // As in the JDK's escape(), any character other than an ASCII letter or digit
                    // escapes itself.
                    if (escapedByte >= 0x80) {
                        index--;
                        yield literalFromRune(readRune(flags));
                    }
                    if (!Character.isLetterOrDigit((char) escapedByte)) {
                        yield literalFromRune(escapedByte);
                    }
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                }
            };
        }

        private void parseUnicodeGroupInto(CharClassBuilder characterClassBuilder, int sign, int parseFlags, int sequenceStart)
        {
            // Supports \pL and \p{...}. Java has no caret inversion, so \p^ and any braced name
            // containing ^ are unknown property names. In comments mode the JDK skips whitespace and
            // comments before the name or its brace and after the brace, but takes the rest of a
            // braced name as written.
            skipIgnoredPatternCharacters();
            if (atEnd()) {
                throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, patternSegment(sequenceStart, index - sequenceStart));
            }
            if (peekByte() == '{') {
                consumeByte('{');
                skipIgnoredPatternCharacters();
                int nameStart = index;
                while (!atEnd() && peekByte() != '}') {
                    consumeByte();
                }
                if (atEnd() || consumeByte() != '}') {
                    throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, patternSegment(sequenceStart, end - sequenceStart));
                }
                int nameEnd = index - 1;

                String name = new String(bytes, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8);
                CharClass group = lookupJavaUnicodeGroup(name, parseFlags);
                if (group == null) {
                    throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, patternSegment(sequenceStart, index - sequenceStart));
                }
                addUnfoldedGroup(characterClassBuilder, group, sign, parseFlags);
                return;
            }

            int nameStart = index;
            readRune(parseFlags);

            String name = new String(bytes, nameStart, index - nameStart, StandardCharsets.UTF_8);
            CharClass group = lookupJavaUnicodeGroup(name, parseFlags);
            if (group == null) {
                throw error(RegexpParseErrorCode.BAD_CHAR_RANGE, patternSegment(sequenceStart, index - sequenceStart));
            }
            addUnfoldedGroup(characterClassBuilder, group, sign, parseFlags);
        }

        private void addUnfoldedGroup(CharClassBuilder builder, CharClass group, int sign, int flags)
        {
            // Java never folds a predefined class or a property. A property applies the
            // property-specific case predicates that the lookup resolved.
            addGroup(builder, group, sign, flags & ~(Regexp.ASCII_FOLD_CASE | Regexp.FOLD_CASE), caseFolding);
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
            return JavaUnicodeProperties.lookup(
                    name,
                    (flags & Regexp.JAVA_UNICODE_CHARACTER_CLASS) != 0,
                    (flags & (Regexp.ASCII_FOLD_CASE | Regexp.FOLD_CASE)) != 0);
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

        private Regexp perlDigits(int flags, boolean negate)
        {
            if ((flags & Regexp.JAVA_UNICODE_CHARACTER_CLASS) != 0) {
                return unicodePredefinedClass(flags, "IsDigit", negate);
            }
            if (!negate) {
                return Regexp.charClass(flags & ~Regexp.FOLD_CASE, PERL_DIGITS_CLASS);
            }
            return perlCharClass(flags, negate, perlDigitsCharClass(flags, false));
        }

        private Regexp perlSpaces(int flags, boolean negate)
        {
            if ((flags & Regexp.JAVA_UNICODE_CHARACTER_CLASS) != 0) {
                return unicodePredefinedClass(flags, "IsWhiteSpace", negate);
            }
            return perlCharClass(flags, negate, perlSpacesCharClass(flags, false));
        }

        private Regexp perlWord(int flags, boolean negate)
        {
            if ((flags & Regexp.JAVA_UNICODE_CHARACTER_CLASS) != 0) {
                return unicodePredefinedClass(flags, "IsWord", negate);
            }
            return perlCharClass(flags, negate, perlWordCharClass(flags, false));
        }

        private Regexp unicodePredefinedClass(int flags, String name, boolean negate)
        {
            CharClass group = requireNonNull(JavaUnicodeProperties.lookup(name), "Unicode group is missing: " + name);
            return perlCharClass(flags, negate, group);
        }

        private Regexp perlCharClass(int flags, boolean negate, CharClass group)
        {
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            addUnfoldedGroup(characterClassBuilder, group, negate ? -1 : 1, flags);
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

        private CharClass digitsCharClass(int flags, boolean negate)
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

        private CharClass spacesCharClass(int flags, boolean negate)
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

        private CharClass wordCharClass(int flags, boolean negate)
        {
            return predefinedCharClass(flags, negate, "IsWord", perlWordCharClass(flags, false));
        }

        private CharClass predefinedCharClass(int flags, boolean negate, String unicodeName, CharClass asciiClass)
        {
            CharClass group = (flags & Regexp.JAVA_UNICODE_CHARACTER_CLASS) != 0
                    ? requireNonNull(JavaUnicodeProperties.lookup(unicodeName), "Unicode group is missing: " + unicodeName)
                    : asciiClass;
            if (!negate) {
                return group;
            }
            CharClassBuilder characterClassBuilder = new CharClassBuilder();
            addUnfoldedGroup(characterClassBuilder, group, -1, flags);
            characterClassBuilder.removeAbove(Regexp.maxRune(flags));
            return characterClassBuilder.toCharClass();
        }

        private int parseHexEscape(int flags, int sequenceStart)
        {
            // Supports \xNN and \x{...} with any number of hex digits (>= 1). In comments mode the
            // JDK skips whitespace and comments before each digit and brace.
            int runeMax = Regexp.maxRune(flags);

            skipIgnoredPatternCharacters();
            if (!atEnd() && peekByte() == '{') {
                consumeByte('{');
                int value = 0;
                int digits = 0;
                while (true) {
                    skipIgnoredPatternCharacters();
                    if (atEnd() || peekByte() == '}') {
                        break;
                    }
                    int rune = readRune(flags);
                    int digitValue = decodeHexDigit(rune);
                    if (digitValue < 0) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                    }
                    digits++;
                    value = (value << 4) | digitValue;
                    if (value > runeMax) {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                    }
                }
                if (digits == 0 || atEnd() || consumeByte() != '}') {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                }
                if (value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE) {
                    throw unsupportedConstruct("surrogate escapes", sequenceStart, index);
                }
                return value;
            }

            // \xNN
            if (atEnd()) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
            }
            int firstRune = readRune(flags);
            skipIgnoredPatternCharacters();
            if (atEnd()) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
            }
            int secondRune = readRune(flags);
            int firstDigit = decodeHexDigit(firstRune);
            int secondDigit = decodeHexDigit(secondRune);
            if (firstDigit < 0 || secondDigit < 0) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
            }
            int value = (firstDigit << 4) | secondDigit;
            if (value > runeMax) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
            }
            return value;
        }

        private int parseUnicodeEscape(int flags, int sequenceStart)
        {
            int value = parseFourHexDigits(sequenceStart);
            if (Character.isHighSurrogate((char) value)) {
                // As in the JDK, the next escape of this form must have four hex digits, but it joins
                // this one only when it is a low surrogate. In comments mode the JDK skips whitespace
                // and comments before its backslash and before its u.
                int escapeEnd = index;
                skipIgnoredPatternCharacters();
                if (!atEnd() && peekByte() == '\\') {
                    consumeByte('\\');
                    skipIgnoredPatternCharacters();
                    if (!atEnd() && peekByte() == 'u') {
                        consumeByte('u');
                        int lowSurrogate = parseFourHexDigits(sequenceStart);
                        if (Character.isLowSurrogate((char) lowSurrogate)) {
                            return Character.toCodePoint((char) value, (char) lowSurrogate);
                        }
                    }
                }
                index = escapeEnd;
            }
            if (Character.isSurrogate((char) value)) {
                // The JDK accepts a lone surrogate escape, which no UTF-8 text can match.
                throw unsupportedConstruct("surrogate escapes", sequenceStart, index);
            }
            if (value > Regexp.maxRune(flags)) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
            }
            return value;
        }

        private int parseControlEscape(int flags, int sequenceStart)
        {
            // In comments mode the JDK skips whitespace and comments before the character.
            skipIgnoredPatternCharacters();
            if (atEnd()) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
            }
            // As in the JDK, flip bit 6 of the whole next code point, which keeps it a scalar value.
            return readRune(flags) ^ 64;
        }

        private int parseNamedCodePoint(int sequenceStart)
        {
            // In comments mode the JDK skips whitespace and comments before the brace, but takes the
            // name as written.
            skipIgnoredPatternCharacters();
            if (atEnd() || consumeByte() != '{') {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
            }
            int nameStart = index;
            while (!atEnd() && peekByte() != '}') {
                consumeByte();
            }
            if (nameStart == index || atEnd()) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
            }
            String name = new String(bytes, nameStart, index - nameStart, StandardCharsets.UTF_8);
            consumeByte('}');
            int codePoint = JavaCharacterNames.codePointOf(name);
            if (codePoint < 0) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
            }
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                throw unsupportedConstruct("surrogate escapes", sequenceStart, index);
            }
            return codePoint;
        }

        private int parseFourHexDigits(int sequenceStart)
        {
            // In comments mode the JDK skips whitespace and comments before each digit.
            int value = 0;
            for (int i = 0; i < 4; i++) {
                skipIgnoredPatternCharacters();
                if (atEnd()) {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                }
                int digit = decodeHexDigit(consumeByte());
                if (digit < 0) {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
                }
                value = (value << 4) | digit;
            }
            return value;
        }

        private int parseOctalEscape(int firstDigit, int flags, int sequenceStart)
        {
            // In comments mode the JDK skips whitespace and comments before each digit.
            skipIgnoredPatternCharacters();
            if (firstDigit != '0' || atEnd() || peekByte() < '0' || peekByte() > '7') {
                throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
            }

            int runeMax = Regexp.maxRune(flags);
            int firstOctalDigit = peekByte();
            int maximumDigits = firstOctalDigit <= '3' ? 3 : 2;
            int code = 0;
            for (int i = 0; i < maximumDigits; i++) {
                skipIgnoredPatternCharacters();
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
                throw error(RegexpParseErrorCode.BAD_ESCAPE, patternSegment(sequenceStart, index - sequenceStart));
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
                characterClassBuilder.addRangeFlags(rune, rune, flags, caseFolding);
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
                else if (caseFolding.cycleFoldRune(rune) != rune) {
                    CharClassBuilder characterClassBuilder = new CharClassBuilder();
                    int initialRune = rune;
                    do {
                        if ((flags & Regexp.NEVER_NEWLINE) == 0 || rune != '\n') {
                            characterClassBuilder.addRange(rune, rune);
                        }
                        rune = caseFolding.cycleFoldRune(rune);
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
            index = ignoredPatternEnd(index);
        }

        private int ignoredPatternEnd(int position)
        {
            if ((flags & Regexp.JAVA_COMMENTS) == 0) {
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

                // As in the JDK, a comment ends before NUL or a line terminator of the current line mode:
                // only a newline under UNIX_LINES, otherwise also a carriage return, U+0085, U+2028, or
                // U+2029. The outer loop then skips a newline or carriage return as whitespace; any other
                // terminator is a pattern character.
                position++;
                while (position < end) {
                    long decoded = Utf8.decode(bytes, position, end);
                    int codePoint = Utf8.decodedCodePoint(decoded);
                    if (codePoint == 0 || codePoint == '\n' ||
                            ((flags & Regexp.JAVA_UNIX_LINES) == 0 &&
                                    (codePoint == '\r' || codePoint == 0x0085 || codePoint == 0x2028 || codePoint == 0x2029))) {
                        break;
                    }
                    position += Utf8.decodedWidth(decoded);
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

        // Reads the digits of a repetition count and the whitespace and comments after each one.
        // A count above MAX_REPEAT reads as MAX_REPEAT + 1.
        private int parseRepetitionCount()
        {
            int value = 0;
            while (!atEnd() && isAsciiDigit(peekByte())) {
                value = Math.min(value * 10 + (consumeByte() - '0'), MAX_REPEAT + 1);
                skipIgnoredPatternCharacters();
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
