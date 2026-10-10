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
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.airlift.regulator.Regexp.MAX_REPEAT;
import static io.airlift.regulator.RegexpParserSupport.addFoldedRangeLatin1;
import static io.airlift.regulator.RegexpParserSupport.decodeHexDigit;
import static io.airlift.regulator.RegexpParserSupport.makeLiteralString;
import static io.airlift.regulator.RegexpParserSupport.wouldExceedRepeatLimit;
import static java.util.Objects.requireNonNull;

/**
 * Parses the pattern language of the Rust {@code regex} crate, pinned at regex 1.13.1 with
 * regex-syntax 0.8.11.
 * <p>
 * The crate rejects a pattern in three ordered stages: syntax errors while parsing, then the
 * nesting limit over the whole syntax tree, then translation errors in a post-order walk. A
 * single pass cannot reproduce which error wins, so this parser follows the same stages: it
 * builds a private syntax tree shaped like the crate's, checks the nesting limit, and translates
 * the tree into a {@link Regexp}. Every phase walks the tree with an explicit stack, so nesting
 * depth does not consume thread stack.
 * <p>
 * Constructs the crate accepts but Regulator does not support are recorded during translation
 * and reported only after translation succeeds, so a later crate error still takes precedence.
 */
final class RustRegexpParser
{
    // Syntax options, kept separate from Regexp parse flags because the crate scopes them itself.
    static final int CASE_INSENSITIVE = 1;
    static final int MULTI_LINE = 1 << 1;
    static final int DOT_MATCHES_NEW_LINE = 1 << 2;
    static final int SWAP_GREED = 1 << 3;
    static final int UNICODE = 1 << 4;
    static final int CRLF = 1 << 5;
    static final int IGNORE_WHITESPACE = 1 << 6;
    private static final int ALL_SYNTAX_FLAGS = (1 << 7) - 1;

    private static final int NEST_LIMIT = 250;
    private static final int NODE_FLAGS = Regexp.LIKE_PERL;
    private static final int UNICODE_WORD_BOUNDARY_FLAGS = NODE_FLAGS | Regexp.JAVA_WORD_BOUNDARY | Regexp.JAVA_UNICODE_CHARACTER_CLASS;

    // The crate folds case with simple case folding, as RE2 does.
    private static final CaseFolding CASE_FOLDING = CaseFolding.RE2;

    private RustRegexpParser() {}

    static ParseResult parse(Slice pattern, int syntaxFlags)
    {
        requireNonNull(pattern, "pattern is null");
        if ((syntaxFlags & ~ALL_SYNTAX_FLAGS) != 0) {
            throw new RegexpParseException(RegexpParseErrorCode.INTERNAL_ERROR, null, -1);
        }
        // The crate parses Rust strings, so a pattern must be valid UTF-8 before parsing starts.
        int invalidOffset = Utf8.firstInvalidOffset(pattern);
        if (invalidOffset >= 0) {
            throw new RegexpParseException(RegexpParseErrorCode.BAD_UTF8, null, invalidOffset);
        }

        Parser parser = new Parser(pattern, syntaxFlags);
        Ast ast = parser.parse();
        parser.checkNestLimit(ast);
        Translator translator = new Translator(parser, syntaxFlags);
        Regexp regexp = translator.translate(ast);
        translator.throwUnsupported();
        Regexp simplifiedRegexp = Simplifier.simplifyForParse(regexp, CASE_FOLDING);
        // Simplification can remove a group that never matches, but the group keeps its name.
        Map<Integer, String> capturingGroupNames = parser.captureNames.isEmpty() ? Map.of() : regexp.captureNames();
        return new ParseResult(simplifiedRegexp, Translator.capturingGroupCount(regexp), capturingGroupNames, CASE_FOLDING);
    }

    // The crate's error kinds, each mapped to the closest Regulator error code.
    private enum ErrorKind
    {
        CAPTURE_LIMIT_EXCEEDED("CaptureLimitExceeded", RegexpParseErrorCode.PATTERN_TOO_LARGE),
        CLASS_ESCAPE_INVALID("ClassEscapeInvalid", RegexpParseErrorCode.BAD_ESCAPE),
        CLASS_RANGE_INVALID("ClassRangeInvalid", RegexpParseErrorCode.BAD_CHAR_RANGE),
        CLASS_RANGE_LITERAL("ClassRangeLiteral", RegexpParseErrorCode.BAD_CHAR_RANGE),
        CLASS_UNCLOSED("ClassUnclosed", RegexpParseErrorCode.MISSING_BRACKET),
        DECIMAL_INVALID("DecimalInvalid", RegexpParseErrorCode.REPEAT_SIZE),
        ESCAPE_HEX_EMPTY("EscapeHexEmpty", RegexpParseErrorCode.BAD_ESCAPE),
        ESCAPE_HEX_INVALID("EscapeHexInvalid", RegexpParseErrorCode.BAD_ESCAPE),
        ESCAPE_HEX_INVALID_DIGIT("EscapeHexInvalidDigit", RegexpParseErrorCode.BAD_ESCAPE),
        ESCAPE_UNEXPECTED_EOF("EscapeUnexpectedEof", RegexpParseErrorCode.BAD_ESCAPE),
        ESCAPE_UNRECOGNIZED("EscapeUnrecognized", RegexpParseErrorCode.BAD_ESCAPE),
        FLAG_DANGLING_NEGATION("FlagDanglingNegation", RegexpParseErrorCode.BAD_PERL_OP),
        FLAG_DUPLICATE("FlagDuplicate", RegexpParseErrorCode.BAD_PERL_OP),
        FLAG_REPEATED_NEGATION("FlagRepeatedNegation", RegexpParseErrorCode.BAD_PERL_OP),
        FLAG_UNEXPECTED_EOF("FlagUnexpectedEof", RegexpParseErrorCode.MISSING_PAREN),
        FLAG_UNRECOGNIZED("FlagUnrecognized", RegexpParseErrorCode.BAD_PERL_OP),
        GROUP_NAME_DUPLICATE("GroupNameDuplicate", RegexpParseErrorCode.BAD_NAMED_CAPTURE),
        GROUP_NAME_EMPTY("GroupNameEmpty", RegexpParseErrorCode.BAD_NAMED_CAPTURE),
        GROUP_NAME_INVALID("GroupNameInvalid", RegexpParseErrorCode.BAD_NAMED_CAPTURE),
        GROUP_NAME_UNEXPECTED_EOF("GroupNameUnexpectedEof", RegexpParseErrorCode.BAD_NAMED_CAPTURE),
        GROUP_UNCLOSED("GroupUnclosed", RegexpParseErrorCode.MISSING_PAREN),
        GROUP_UNOPENED("GroupUnopened", RegexpParseErrorCode.UNEXPECTED_PAREN),
        NEST_LIMIT_EXCEEDED("NestLimitExceeded", RegexpParseErrorCode.PATTERN_TOO_LARGE),
        REPETITION_COUNT_DECIMAL_EMPTY("RepetitionCountDecimalEmpty", RegexpParseErrorCode.REPEAT_ARGUMENT),
        REPETITION_COUNT_INVALID("RepetitionCountInvalid", RegexpParseErrorCode.REPEAT_ARGUMENT),
        REPETITION_COUNT_UNCLOSED("RepetitionCountUnclosed", RegexpParseErrorCode.MISSING_BRACKET),
        REPETITION_MISSING("RepetitionMissing", RegexpParseErrorCode.REPEAT_ARGUMENT),
        SPECIAL_WORD_BOUNDARY_UNCLOSED("SpecialWordBoundaryUnclosed", RegexpParseErrorCode.BAD_ESCAPE),
        SPECIAL_WORD_BOUNDARY_UNRECOGNIZED("SpecialWordBoundaryUnrecognized", RegexpParseErrorCode.BAD_ESCAPE),
        SPECIAL_WORD_OR_REPETITION_UNEXPECTED_EOF("SpecialWordOrRepetitionUnexpectedEof", RegexpParseErrorCode.BAD_ESCAPE),
        UNICODE_CLASS_INVALID("UnicodeClassInvalid", RegexpParseErrorCode.BAD_ESCAPE),
        UNSUPPORTED_BACKREFERENCE("UnsupportedBackreference", RegexpParseErrorCode.UNSUPPORTED_CONSTRUCT),
        UNSUPPORTED_LOOK_AROUND("UnsupportedLookAround", RegexpParseErrorCode.UNSUPPORTED_CONSTRUCT),
        // Translation errors.
        INVALID_UTF8("InvalidUtf8", RegexpParseErrorCode.BAD_UTF8),
        UNICODE_NOT_ALLOWED("UnicodeNotAllowed", RegexpParseErrorCode.BAD_CHAR_CLASS),
        UNICODE_PROPERTY_NOT_FOUND("UnicodePropertyNotFound", RegexpParseErrorCode.BAD_CHAR_RANGE),
        UNICODE_PROPERTY_VALUE_NOT_FOUND("UnicodePropertyValueNotFound", RegexpParseErrorCode.BAD_CHAR_RANGE);

        private final String detail;
        private final RegexpParseErrorCode code;

        ErrorKind(String detail, RegexpParseErrorCode code)
        {
            this.detail = detail;
            this.code = code;
        }
    }

    private enum AssertionKind
    {
        START_LINE("^"),
        END_LINE("$"),
        START_TEXT("\\A"),
        END_TEXT("\\z"),
        WORD_BOUNDARY("\\b"),
        NOT_WORD_BOUNDARY("\\B"),
        WORD_BOUNDARY_START("\\b{start}"),
        WORD_BOUNDARY_END("\\b{end}"),
        WORD_BOUNDARY_START_ANGLE("\\<"),
        WORD_BOUNDARY_END_ANGLE("\\>"),
        WORD_BOUNDARY_START_HALF("\\b{start-half}"),
        WORD_BOUNDARY_END_HALF("\\b{end-half}");

        private final String syntax;

        AssertionKind(String syntax)
        {
            this.syntax = syntax;
        }
    }

    private enum RepetitionKind
    {
        ZERO_OR_ONE,
        ZERO_OR_MORE,
        ONE_OR_MORE,
    }

    private enum PerlKind
    {
        DIGIT,
        SPACE,
        WORD,
    }

    private enum UnicodeKind
    {
        ONE_LETTER,
        NAMED,
        NAMED_VALUE,
    }

    private enum SetOperation
    {
        INTERSECTION,
        DIFFERENCE,
        SYMMETRIC_DIFFERENCE,
    }

    private enum AsciiKind
    {
        ALNUM("alnum", "09AZaz"),
        ALPHA("alpha", "AZaz"),
        ASCII("ascii", "\u0000\u007F"),
        BLANK("blank", "\t\t  "),
        CNTRL("cntrl", "\u0000\u001F\u007F\u007F"),
        DIGIT("digit", "09"),
        GRAPH("graph", "!~"),
        LOWER("lower", "az"),
        PRINT("print", " ~"),
        PUNCT("punct", "!/:@[`{~"),
        SPACE("space", "\t\r  "),
        UPPER("upper", "AZ"),
        WORD("word", "09AZ__az"),
        XDIGIT("xdigit", "09AFaf");

        private final String name;
        // Inclusive low and high characters of each range.
        private final String ranges;

        AsciiKind(String name, String ranges)
        {
            this.name = name;
            this.ranges = ranges;
        }

        static AsciiKind fromName(String name)
        {
            for (AsciiKind kind : values()) {
                if (kind.name.equals(name)) {
                    return kind;
                }
            }
            return null;
        }

        void addTo(CharClassBuilder builder)
        {
            for (int index = 0; index < ranges.length(); index += 2) {
                builder.addRange(ranges.charAt(index), ranges.charAt(index + 1));
            }
        }
    }

    // Syntax tree, mirroring regex-syntax's ast module. Positions are code point indexes.

    private sealed interface Ast
    {
        int start();

        int end();
    }

    private sealed interface ClassSet
    {
        int start();

        int end();
    }

    private sealed interface ClassSetItem
            extends ClassSet {}

    private record EmptyAst(int start, int end)
            implements Ast {}

    private record FlagsAst(int start, int end, Flags flags)
            implements Ast {}

    // A hexByte literal is a two-digit \x escape, which names a byte rather than a character
    // outside Unicode mode.
    private record LiteralAst(int start, int end, int codePoint, boolean hexByte)
            implements Ast, ClassSetItem {}

    private record DotAst(int start, int end)
            implements Ast {}

    private record AssertionAst(int start, int end, AssertionKind kind)
            implements Ast {}

    private record UnicodeClassAst(int start, int end, boolean negated, UnicodeKind kind, String name, String value, boolean notEqual)
            implements Ast, ClassSetItem
    {
        boolean isNegated()
        {
            return notEqual != negated;
        }
    }

    private record PerlClassAst(int start, int end, PerlKind kind, boolean negated)
            implements Ast, ClassSetItem {}

    private record BracketedAst(int start, int end, boolean negated, ClassSet kind)
            implements Ast, ClassSetItem {}

    // A max of -1 is unbounded.
    private record RepetitionAst(int start, int end, long min, long max, boolean greedy, Ast ast)
            implements Ast {}

    // captureIndex is zero for a non-capturing group, which alone may carry flags.
    private record GroupAst(int start, int end, int captureIndex, String name, Flags flags, Ast ast)
            implements Ast {}

    private record AlternationAst(int start, int end, List<Ast> asts)
            implements Ast {}

    private record ConcatAst(int start, int end, List<Ast> asts)
            implements Ast {}

    private record ClassEmpty(int start, int end)
            implements ClassSetItem {}

    private record ClassRange(int start, int end, LiteralAst first, LiteralAst last)
            implements ClassSetItem {}

    private record ClassAscii(int start, int end, AsciiKind kind, boolean negated)
            implements ClassSetItem {}

    private record ClassUnion(int start, int end, List<ClassSetItem> items)
            implements ClassSetItem {}

    private record ClassBinaryOp(int start, int end, SetOperation operation, ClassSet lhs, ClassSet rhs)
            implements ClassSet {}

    // Flag items in source order; '-' is a negation.
    private record Flags(int[] items)
    {
        // The state the items give the flag, or the current state when they do not name it.
        boolean state(int flag, boolean current)
        {
            boolean negated = false;
            for (int item : items) {
                if (item == '-') {
                    negated = true;
                }
                else if (item == flag) {
                    return !negated;
                }
            }
            return current;
        }

        int apply(int syntaxFlags)
        {
            boolean enable = true;
            for (int item : items) {
                int flag = switch (item) {
                    case '-' -> {
                        enable = false;
                        yield 0;
                    }
                    case 'i' -> CASE_INSENSITIVE;
                    case 'm' -> MULTI_LINE;
                    case 's' -> DOT_MATCHES_NEW_LINE;
                    case 'U' -> SWAP_GREED;
                    case 'u' -> UNICODE;
                    case 'R' -> CRLF;
                    // The parser alone owns ignore-whitespace.
                    default -> 0;
                };
                syntaxFlags = enable ? syntaxFlags | flag : syntaxFlags & ~flag;
            }
            return syntaxFlags;
        }
    }

    private static final class ConcatBuilder
    {
        private final int start;
        private final List<Ast> asts = new ArrayList<>();

        ConcatBuilder(int start)
        {
            this.start = start;
        }

        Ast intoAst(int end)
        {
            return switch (asts.size()) {
                case 0 -> new EmptyAst(start, end);
                case 1 -> asts.getFirst();
                default -> new ConcatAst(start, end, List.copyOf(asts));
            };
        }
    }

    private static final class UnionBuilder
    {
        private int start;
        private int end;
        private final List<ClassSetItem> items = new ArrayList<>();

        UnionBuilder(int position)
        {
            this.start = position;
            this.end = position;
        }

        void push(ClassSetItem item)
        {
            if (items.isEmpty()) {
                start = item.start();
            }
            end = item.end();
            items.add(item);
        }

        ClassSetItem intoItem()
        {
            return switch (items.size()) {
                case 0 -> new ClassEmpty(start, end);
                case 1 -> items.getFirst();
                default -> new ClassUnion(start, end, List.copyOf(items));
            };
        }
    }

    private sealed interface GroupStackEntry {}

    private record OpenGroup(ConcatBuilder concat, int start, int captureIndex, String name, Flags flags, boolean ignoreWhitespace)
            implements GroupStackEntry {}

    private record OpenAlternation(int start, List<Ast> asts)
            implements GroupStackEntry {}

    private sealed interface ClassStackEntry {}

    // The opening spans the '[' and the prefix after it: '^', leading '-' and ']' literals, and skipped space.
    private record OpenClass(UnionBuilder parentUnion, int start, int openingEnd, boolean negated)
            implements ClassStackEntry {}

    private record PendingOperation(SetOperation operation, ClassSet lhs)
            implements ClassStackEntry {}

    private record NestFrame(Object node, int depth) {}

    // A syntax node in translation: its children translate in order, then the node completes.
    private static final class TranslationFrame
    {
        private final Ast ast;
        private final int enclosingFlags;
        private final List<Ast> children;
        private final List<Regexp> childValues;
        private boolean childrenEmptyWidth = true;
        private int nextChildIndex;

        TranslationFrame(Ast ast, int enclosingFlags)
        {
            this.ast = ast;
            this.enclosingFlags = enclosingFlags;
            this.children = switch (ast) {
                case RepetitionAst repetition -> List.of(repetition.ast());
                case GroupAst group -> List.of(group.ast());
                case ConcatAst concat -> concat.asts();
                case AlternationAst alternation -> alternation.asts();
                default -> List.of();
            };
            this.childValues = new ArrayList<>(children.size());
        }
    }

    // A class node in translation: its operands translate in order, then the node completes.
    private static final class ClassFrame
    {
        private final ClassSet set;
        private final List<? extends ClassSet> children;
        private final List<CharClassBuilder> childValues;
        private int nextChildIndex;

        ClassFrame(ClassSet set)
        {
            this.set = set;
            this.children = switch (set) {
                case BracketedAst bracketed -> List.of(bracketed.kind());
                case ClassUnion union -> union.items();
                case ClassBinaryOp operation -> List.of(operation.lhs(), operation.rhs());
                default -> List.of();
            };
            this.childValues = new ArrayList<>(children.size());
        }
    }

    private static final class Parser
    {
        private final Slice pattern;
        private final int[] codePoints;
        private final int[] byteOffsets;
        private final int length;
        private int position;
        private boolean ignoreWhitespace;
        private int captureIndex;
        private final Set<String> captureNames = new HashSet<>();
        private final Deque<GroupStackEntry> groupStack = new ArrayDeque<>();
        private final Deque<ClassStackEntry> classStack = new ArrayDeque<>();

        Parser(Slice pattern, int syntaxFlags)
        {
            this.pattern = pattern;
            this.codePoints = pattern.toStringUtf8().codePoints().toArray();
            this.length = codePoints.length;
            this.byteOffsets = new int[length + 1];
            for (int index = 0; index < length; index++) {
                byteOffsets[index + 1] = byteOffsets[index] + Utf8.encodedLength(codePoints[index]);
            }
            this.ignoreWhitespace = (syntaxFlags & IGNORE_WHITESPACE) != 0;
        }

        RegexpParseException error(ErrorKind kind, int start, int end)
        {
            return error(kind.code, kind.detail, start, end);
        }

        RegexpParseException error(RegexpParseErrorCode code, String detail, int start, int end)
        {
            int byteStart = byteOffsets[start];
            int byteEnd = byteOffsets[end];
            Slice segment = byteEnd > byteStart ? pattern.slice(byteStart, byteEnd - byteStart) : null;
            return new RegexpParseException(code, segment, byteStart, detail);
        }

        private RegexpParseException errorAtCurrent(ErrorKind kind)
        {
            return error(kind, position, Math.min(position + 1, length));
        }

        private RegexpParseException errorAtPosition(ErrorKind kind)
        {
            return error(kind, position, position);
        }

        // Cursor primitives, following regex-syntax's ParserI.

        private boolean isEof()
        {
            return position == length;
        }

        private int current()
        {
            return codePoints[position];
        }

        // Advances one character and returns whether input remains.
        private boolean bump()
        {
            if (isEof()) {
                return false;
            }
            position++;
            return position < length;
        }

        private boolean bumpIf(String prefix)
        {
            if (position + prefix.length() > length) {
                return false;
            }
            for (int index = 0; index < prefix.length(); index++) {
                if (codePoints[position + index] != prefix.charAt(index)) {
                    return false;
                }
            }
            position += prefix.length();
            return true;
        }

        private boolean isLookAroundPrefix()
        {
            return bumpIf("?=") || bumpIf("?!") || bumpIf("?<=") || bumpIf("?<!");
        }

        private boolean bumpAndBumpSpace()
        {
            if (!bump()) {
                return false;
            }
            bumpSpace();
            return !isEof();
        }

        private void bumpSpace()
        {
            if (!ignoreWhitespace) {
                return;
            }
            while (!isEof()) {
                if (isWhitespace(current())) {
                    bump();
                }
                else if (current() == '#') {
                    bump();
                    while (!isEof()) {
                        int character = current();
                        bump();
                        if (character == '\n') {
                            break;
                        }
                    }
                }
                else {
                    break;
                }
            }
        }

        private int peek()
        {
            if (position + 1 >= length) {
                return -1;
            }
            return codePoints[position + 1];
        }

        // Mirrors the crate exactly, including that a comment ends at its first non-whitespace
        // character and that an all-whitespace remainder yields the character after the current one.
        private int peekSpace()
        {
            if (!ignoreWhitespace) {
                return peek();
            }
            if (isEof()) {
                return -1;
            }
            int next = position + 1;
            boolean inComment = false;
            for (int index = next; index < length; index++) {
                int character = codePoints[index];
                if (isWhitespace(character)) {
                    continue;
                }
                if (!inComment && character == '#') {
                    inComment = true;
                }
                else {
                    next = index;
                    break;
                }
            }
            return next < length ? codePoints[next] : -1;
        }

        Ast parse()
        {
            ConcatBuilder concat = new ConcatBuilder(position);
            while (true) {
                bumpSpace();
                if (isEof()) {
                    break;
                }
                switch (current()) {
                    case '(' -> concat = pushGroup(concat);
                    case ')' -> concat = popGroup(concat);
                    case '|' -> concat = pushAlternate(concat);
                    case '[' -> concat.asts.add(parseSetClass());
                    case '?' -> parseUncountedRepetition(concat, RepetitionKind.ZERO_OR_ONE);
                    case '*' -> parseUncountedRepetition(concat, RepetitionKind.ZERO_OR_MORE);
                    case '+' -> parseUncountedRepetition(concat, RepetitionKind.ONE_OR_MORE);
                    case '{' -> parseCountedRepetition(concat);
                    default -> concat.asts.add(parsePrimitive());
                }
            }
            return popGroupEnd(concat);
        }

        private ConcatBuilder pushAlternate(ConcatBuilder concat)
        {
            Ast branch = concat.intoAst(position);
            if (groupStack.peek() instanceof OpenAlternation alternation) {
                alternation.asts().add(branch);
            }
            else {
                List<Ast> asts = new ArrayList<>();
                asts.add(branch);
                groupStack.push(new OpenAlternation(concat.start, asts));
            }
            bump();
            return new ConcatBuilder(position);
        }

        private ConcatBuilder pushGroup(ConcatBuilder concat)
        {
            int openStart = position;
            bump();
            bumpSpace();
            if (isLookAroundPrefix()) {
                throw error(ErrorKind.UNSUPPORTED_LOOK_AROUND, openStart, position);
            }
            int innerStart = position;
            if (bumpIf("?P<") || bumpIf("?<")) {
                int index = nextCaptureIndex(openStart);
                String name = parseCaptureName();
                return openGroup(concat, openStart, index, name, null);
            }
            if (bumpIf("?")) {
                if (isEof()) {
                    throw error(ErrorKind.GROUP_UNCLOSED, openStart, openStart + 1);
                }
                Flags flags = parseFlags();
                int end = current();
                bump();
                if (end == ')') {
                    // Empty flags such as (?) read as a repetition operator missing its operand.
                    if (flags.items().length == 0) {
                        throw error(ErrorKind.REPETITION_MISSING, innerStart, innerStart);
                    }
                    ignoreWhitespace = flags.state('x', ignoreWhitespace);
                    concat.asts.add(new FlagsAst(openStart, position, flags));
                    return concat;
                }
                return openGroup(concat, openStart, 0, null, flags);
            }
            int index = nextCaptureIndex(openStart);
            return openGroup(concat, openStart, index, null, null);
        }

        private ConcatBuilder openGroup(ConcatBuilder concat, int start, int index, String name, Flags flags)
        {
            groupStack.push(new OpenGroup(concat, start, index, name, flags, ignoreWhitespace));
            if (flags != null) {
                ignoreWhitespace = flags.state('x', ignoreWhitespace);
            }
            return new ConcatBuilder(position);
        }

        private int nextCaptureIndex(int openStart)
        {
            if (captureIndex == Integer.MAX_VALUE) {
                throw error(ErrorKind.CAPTURE_LIMIT_EXCEEDED, openStart, openStart + 1);
            }
            captureIndex++;
            return captureIndex;
        }

        private ConcatBuilder popGroup(ConcatBuilder groupConcat)
        {
            GroupStackEntry entry = groupStack.poll();
            OpenGroup group;
            OpenAlternation alternation = null;
            if (entry instanceof OpenGroup openGroup) {
                group = openGroup;
            }
            else if (entry instanceof OpenAlternation openAlternation && groupStack.poll() instanceof OpenGroup openGroup) {
                group = openGroup;
                alternation = openAlternation;
            }
            else {
                throw errorAtCurrent(ErrorKind.GROUP_UNOPENED);
            }
            ignoreWhitespace = group.ignoreWhitespace();
            int concatEnd = position;
            bump();
            Ast inner;
            if (alternation != null) {
                alternation.asts().add(groupConcat.intoAst(concatEnd));
                inner = new AlternationAst(alternation.start(), concatEnd, List.copyOf(alternation.asts()));
            }
            else {
                inner = groupConcat.intoAst(concatEnd);
            }
            group.concat().asts.add(new GroupAst(group.start(), position, group.captureIndex(), group.name(), group.flags(), inner));
            return group.concat();
        }

        private Ast popGroupEnd(ConcatBuilder concat)
        {
            GroupStackEntry entry = groupStack.poll();
            Ast ast;
            if (entry == null) {
                ast = concat.intoAst(position);
            }
            else if (entry instanceof OpenAlternation alternation) {
                alternation.asts().add(concat.intoAst(position));
                ast = new AlternationAst(alternation.start(), position, List.copyOf(alternation.asts()));
            }
            else {
                throw groupUnclosed((OpenGroup) entry);
            }
            GroupStackEntry remaining = groupStack.poll();
            if (remaining instanceof OpenGroup group) {
                throw groupUnclosed(group);
            }
            return ast;
        }

        private RegexpParseException groupUnclosed(OpenGroup group)
        {
            return error(ErrorKind.GROUP_UNCLOSED, group.start(), group.start() + 1);
        }

        private void parseUncountedRepetition(ConcatBuilder concat, RepetitionKind kind)
        {
            Ast ast = repetitionOperand(concat);
            boolean greedy = true;
            if (bump() && current() == '?') {
                greedy = false;
                bump();
            }
            long min = kind == RepetitionKind.ONE_OR_MORE ? 1 : 0;
            long max = kind == RepetitionKind.ZERO_OR_ONE ? 1 : -1;
            concat.asts.add(new RepetitionAst(ast.start(), position, min, max, greedy, ast));
        }

        private Ast repetitionOperand(ConcatBuilder concat)
        {
            if (concat.asts.isEmpty()) {
                throw errorAtPosition(ErrorKind.REPETITION_MISSING);
            }
            Ast ast = concat.asts.removeLast();
            if (ast instanceof EmptyAst || ast instanceof FlagsAst) {
                throw errorAtPosition(ErrorKind.REPETITION_MISSING);
            }
            return ast;
        }

        private void parseCountedRepetition(ConcatBuilder concat)
        {
            int start = position;
            Ast ast = repetitionOperand(concat);
            if (!bumpAndBumpSpace()) {
                throw error(ErrorKind.REPETITION_COUNT_UNCLOSED, start, position);
            }
            Decimal countStart = parseDecimal();
            if (isEof()) {
                throw error(ErrorKind.REPETITION_COUNT_UNCLOSED, start, position);
            }
            long min;
            long max;
            boolean bounded = false;
            if (current() == ',') {
                if (!bumpAndBumpSpace()) {
                    throw error(ErrorKind.REPETITION_COUNT_UNCLOSED, start, position);
                }
                min = countStart.value();
                if (current() != '}') {
                    max = parseDecimal().value();
                    bounded = true;
                }
                else {
                    max = -1;
                }
            }
            else {
                min = countStart.value();
                max = min;
            }
            if (isEof() || current() != '}') {
                throw error(ErrorKind.REPETITION_COUNT_UNCLOSED, start, position);
            }
            boolean greedy = true;
            if (bumpAndBumpSpace() && current() == '?') {
                greedy = false;
                bump();
            }
            if (bounded && min > max) {
                throw error(ErrorKind.REPETITION_COUNT_INVALID, start, position);
            }
            concat.asts.add(new RepetitionAst(ast.start(), position, min, max, greedy, ast));
        }

        // A parsed u32 count, or the error the crate reports only if the count is needed.
        private record Decimal(long count, RegexpParseException error)
        {
            long value()
            {
                if (error != null) {
                    throw error;
                }
                return count;
            }
        }

        private Decimal parseDecimal()
        {
            // The crate skips whitespace around counts even without ignore-whitespace mode.
            while (!isEof() && isWhitespace(current())) {
                bump();
            }
            int start = position;
            int digits = 0;
            long value = 0;
            boolean overflow = false;
            while (!isEof() && current() >= '0' && current() <= '9') {
                digits++;
                if (!overflow) {
                    value = value * 10 + (current() - '0');
                    overflow = value > 0xFFFF_FFFFL;
                }
                bumpAndBumpSpace();
            }
            int end = position;
            while (!isEof() && isWhitespace(current())) {
                bumpAndBumpSpace();
            }
            if (digits == 0) {
                return new Decimal(0, error(ErrorKind.REPETITION_COUNT_DECIMAL_EMPTY, start, end));
            }
            if (overflow) {
                return new Decimal(0, error(ErrorKind.DECIMAL_INVALID, start, end));
            }
            return new Decimal(value, null);
        }

        private String parseCaptureName()
        {
            if (isEof()) {
                throw errorAtPosition(ErrorKind.GROUP_NAME_UNEXPECTED_EOF);
            }
            int start = position;
            while (current() != '>') {
                if (!isCaptureCharacter(current(), position == start)) {
                    throw errorAtCurrent(ErrorKind.GROUP_NAME_INVALID);
                }
                if (!bump()) {
                    break;
                }
            }
            int end = position;
            if (isEof()) {
                throw errorAtPosition(ErrorKind.GROUP_NAME_UNEXPECTED_EOF);
            }
            bump();
            if (end == start) {
                throw error(ErrorKind.GROUP_NAME_EMPTY, start, start);
            }
            String name = new String(codePoints, start, end - start);
            if (!captureNames.add(name)) {
                throw error(ErrorKind.GROUP_NAME_DUPLICATE, start, end);
            }
            return name;
        }

        private Flags parseFlags()
        {
            List<Integer> items = new ArrayList<>();
            int lastNegation = -1;
            while (current() != ':' && current() != ')') {
                int item = current();
                if (item == '-') {
                    lastNegation = position;
                }
                else {
                    lastNegation = -1;
                    if ("imsUuRx".indexOf(item) < 0) {
                        throw errorAtCurrent(ErrorKind.FLAG_UNRECOGNIZED);
                    }
                }
                if (items.contains(item)) {
                    throw errorAtCurrent(item == '-' ? ErrorKind.FLAG_REPEATED_NEGATION : ErrorKind.FLAG_DUPLICATE);
                }
                items.add(item);
                if (!bump()) {
                    throw errorAtPosition(ErrorKind.FLAG_UNEXPECTED_EOF);
                }
            }
            if (lastNegation >= 0) {
                throw error(ErrorKind.FLAG_DANGLING_NEGATION, lastNegation, lastNegation + 1);
            }
            return new Flags(items.stream().mapToInt(Integer::intValue).toArray());
        }

        private Ast parsePrimitive()
        {
            int start = position;
            int character = current();
            if (character == '\\') {
                return parseEscape();
            }
            bump();
            return switch (character) {
                case '.' -> new DotAst(start, position);
                case '^' -> new AssertionAst(start, position, AssertionKind.START_LINE);
                case '$' -> new AssertionAst(start, position, AssertionKind.END_LINE);
                default -> new LiteralAst(start, position, character, false);
            };
        }

        private Ast parseEscape()
        {
            int start = position;
            if (!bump()) {
                throw error(ErrorKind.ESCAPE_UNEXPECTED_EOF, start, position);
            }
            int character = current();
            if (character >= '0' && character <= '9') {
                throw error(ErrorKind.UNSUPPORTED_BACKREFERENCE, start, position + 1);
            }
            switch (character) {
                case 'x', 'u', 'U' -> {
                    return parseHex(start);
                }
                case 'p', 'P' -> {
                    return parseUnicodeClass(start);
                }
                case 'd', 's', 'w', 'D', 'S', 'W' -> {
                    bump();
                    PerlKind kind = switch (Character.toLowerCase(character)) {
                        case 'd' -> PerlKind.DIGIT;
                        case 's' -> PerlKind.SPACE;
                        default -> PerlKind.WORD;
                    };
                    return new PerlClassAst(start, position, kind, Character.isUpperCase(character));
                }
                default -> {}
            }

            bump();
            int end = position;
            if (isEscapeableCharacter(character)) {
                return new LiteralAst(start, end, character, false);
            }
            return switch (character) {
                case 'a' -> new LiteralAst(start, end, 0x07, false);
                case 'f' -> new LiteralAst(start, end, 0x0C, false);
                case 't' -> new LiteralAst(start, end, '\t', false);
                case 'n' -> new LiteralAst(start, end, '\n', false);
                case 'r' -> new LiteralAst(start, end, '\r', false);
                case 'v' -> new LiteralAst(start, end, 0x0B, false);
                case 'A' -> new AssertionAst(start, end, AssertionKind.START_TEXT);
                case 'z' -> new AssertionAst(start, end, AssertionKind.END_TEXT);
                case 'b' -> {
                    AssertionKind kind = AssertionKind.WORD_BOUNDARY;
                    if (!isEof() && current() == '{') {
                        AssertionKind special = maybeParseSpecialWordBoundary(start);
                        if (special != null) {
                            kind = special;
                            end = position;
                        }
                    }
                    yield new AssertionAst(start, end, kind);
                }
                case 'B' -> new AssertionAst(start, end, AssertionKind.NOT_WORD_BOUNDARY);
                case '<' -> new AssertionAst(start, end, AssertionKind.WORD_BOUNDARY_START_ANGLE);
                case '>' -> new AssertionAst(start, end, AssertionKind.WORD_BOUNDARY_END_ANGLE);
                default -> throw error(ErrorKind.ESCAPE_UNRECOGNIZED, start, end);
            };
        }

        // Returns null, with the cursor restored to '{', when the braces start a counted repetition.
        private AssertionKind maybeParseSpecialWordBoundary(int wordBoundaryStart)
        {
            int start = position;
            if (!bumpAndBumpSpace()) {
                throw error(ErrorKind.SPECIAL_WORD_OR_REPETITION_UNEXPECTED_EOF, wordBoundaryStart, position);
            }
            int contentsStart = position;
            if (!isSpecialWordBoundaryCharacter(current())) {
                position = start;
                return null;
            }
            StringBuilder name = new StringBuilder();
            while (!isEof() && isSpecialWordBoundaryCharacter(current())) {
                name.appendCodePoint(current());
                bumpAndBumpSpace();
            }
            if (isEof() || current() != '}') {
                throw error(ErrorKind.SPECIAL_WORD_BOUNDARY_UNCLOSED, start, position);
            }
            int end = position;
            bump();
            return switch (name.toString()) {
                case "start" -> AssertionKind.WORD_BOUNDARY_START;
                case "end" -> AssertionKind.WORD_BOUNDARY_END;
                case "start-half" -> AssertionKind.WORD_BOUNDARY_START_HALF;
                case "end-half" -> AssertionKind.WORD_BOUNDARY_END_HALF;
                default -> throw error(ErrorKind.SPECIAL_WORD_BOUNDARY_UNRECOGNIZED, contentsStart, end);
            };
        }

        private LiteralAst parseHex(int start)
        {
            int digits = switch (current()) {
                case 'x' -> 2;
                case 'u' -> 4;
                default -> 8;
            };
            if (!bumpAndBumpSpace()) {
                throw errorAtPosition(ErrorKind.ESCAPE_UNEXPECTED_EOF);
            }
            if (current() == '{') {
                return parseHexBrace(start);
            }
            return parseHexDigits(start, digits);
        }

        private LiteralAst parseHexDigits(int start, int digits)
        {
            int digitsStart = position;
            int value = 0;
            for (int index = 0; index < digits; index++) {
                if (index > 0 && !bumpAndBumpSpace()) {
                    throw errorAtPosition(ErrorKind.ESCAPE_UNEXPECTED_EOF);
                }
                int digit = decodeHexDigit(current());
                if (digit < 0) {
                    throw errorAtCurrent(ErrorKind.ESCAPE_HEX_INVALID_DIGIT);
                }
                value = (value << 4) | digit;
            }
            bumpAndBumpSpace();
            int end = position;
            // Eight digits can exceed the int range, which also makes the scalar value invalid.
            if (!isScalarValue(value)) {
                throw error(ErrorKind.ESCAPE_HEX_INVALID, digitsStart, end);
            }
            return new LiteralAst(start, end, value, digits == 2);
        }

        private LiteralAst parseHexBrace(int start)
        {
            int bracePosition = position;
            int digitsStart = position + 1;
            int digits = 0;
            long value = 0;
            while (bumpAndBumpSpace() && current() != '}') {
                int digit = decodeHexDigit(current());
                if (digit < 0) {
                    throw errorAtCurrent(ErrorKind.ESCAPE_HEX_INVALID_DIGIT);
                }
                digits++;
                if (value <= CharClass.RUNEMAX) {
                    value = (value << 4) | digit;
                }
            }
            if (isEof()) {
                throw error(ErrorKind.ESCAPE_UNEXPECTED_EOF, bracePosition, position);
            }
            int end = position;
            bumpAndBumpSpace();
            if (digits == 0) {
                throw error(ErrorKind.ESCAPE_HEX_EMPTY, bracePosition, position);
            }
            if (value > CharClass.RUNEMAX || !isScalarValue((int) value)) {
                throw error(ErrorKind.ESCAPE_HEX_INVALID, digitsStart, end);
            }
            return new LiteralAst(start, position, (int) value, false);
        }

        private UnicodeClassAst parseUnicodeClass(int start)
        {
            boolean negated = current() == 'P';
            if (!bumpAndBumpSpace()) {
                throw errorAtPosition(ErrorKind.ESCAPE_UNEXPECTED_EOF);
            }
            if (current() == '{') {
                StringBuilder contents = new StringBuilder();
                while (bumpAndBumpSpace() && current() != '}') {
                    contents.appendCodePoint(current());
                }
                if (isEof()) {
                    throw errorAtPosition(ErrorKind.ESCAPE_UNEXPECTED_EOF);
                }
                bump();
                String text = contents.toString();
                int separator = text.indexOf("!=");
                if (separator >= 0) {
                    return new UnicodeClassAst(start, position, negated, UnicodeKind.NAMED_VALUE, text.substring(0, separator), text.substring(separator + 2), true);
                }
                separator = text.indexOf(':');
                if (separator < 0) {
                    separator = text.indexOf('=');
                }
                if (separator >= 0) {
                    return new UnicodeClassAst(start, position, negated, UnicodeKind.NAMED_VALUE, text.substring(0, separator), text.substring(separator + 1), false);
                }
                return new UnicodeClassAst(start, position, negated, UnicodeKind.NAMED, text, null, false);
            }
            int letter = current();
            if (letter == '\\') {
                throw errorAtCurrent(ErrorKind.UNICODE_CLASS_INVALID);
            }
            bumpAndBumpSpace();
            return new UnicodeClassAst(start, position, negated, UnicodeKind.ONE_LETTER, Character.toString(letter), null, false);
        }

        private BracketedAst parseSetClass()
        {
            UnionBuilder union = new UnionBuilder(position);
            while (true) {
                bumpSpace();
                if (isEof()) {
                    throw unclosedClass();
                }
                switch (current()) {
                    case '[' -> {
                        // Only a nested '[' can start an ASCII class; a failed attempt backs up.
                        ClassAscii ascii = classStack.isEmpty() ? null : maybeParseAsciiClass();
                        if (ascii != null) {
                            union.push(ascii);
                        }
                        else {
                            union = pushClassOpen(union);
                        }
                    }
                    case ']' -> {
                        ClassSet kind = popClassOperation(union.intoItem());
                        OpenClass open = (OpenClass) classStack.pop();
                        bump();
                        BracketedAst closed = new BracketedAst(open.start(), position, open.negated(), kind);
                        if (classStack.isEmpty()) {
                            return closed;
                        }
                        union = open.parentUnion();
                        union.push(closed);
                    }
                    case '&' -> union = setOperationOrRange(union, '&', SetOperation.INTERSECTION);
                    case '-' -> union = setOperationOrRange(union, '-', SetOperation.DIFFERENCE);
                    case '~' -> union = setOperationOrRange(union, '~', SetOperation.SYMMETRIC_DIFFERENCE);
                    default -> union.push(parseSetClassRange());
                }
            }
        }

        private UnionBuilder setOperationOrRange(UnionBuilder union, int operator, SetOperation operation)
        {
            if (peek() != operator) {
                union.push(parseSetClassRange());
                return union;
            }
            position += 2;
            ClassSet lhs = popClassOperation(union.intoItem());
            classStack.push(new PendingOperation(operation, lhs));
            return new UnionBuilder(position);
        }

        private UnionBuilder pushClassOpen(UnionBuilder parentUnion)
        {
            int start = position;
            if (!bumpAndBumpSpace()) {
                throw error(ErrorKind.CLASS_UNCLOSED, start, position);
            }
            boolean negated = false;
            if (current() == '^') {
                negated = true;
                if (!bumpAndBumpSpace()) {
                    throw error(ErrorKind.CLASS_UNCLOSED, start, position);
                }
            }
            // Leading '-' characters are literals, and so is a ']' that would leave the class empty.
            UnionBuilder union = new UnionBuilder(position);
            while (current() == '-') {
                union.push(new LiteralAst(position, position + 1, '-', false));
                if (!bumpAndBumpSpace()) {
                    throw error(ErrorKind.CLASS_UNCLOSED, start, start);
                }
            }
            if (union.items.isEmpty() && current() == ']') {
                union.push(new LiteralAst(position, position + 1, ']', false));
                if (!bumpAndBumpSpace()) {
                    throw error(ErrorKind.CLASS_UNCLOSED, start, position);
                }
            }
            classStack.push(new OpenClass(parentUnion, start, position, negated));
            return union;
        }

        private ClassSet popClassOperation(ClassSet rhs)
        {
            if (classStack.peek() instanceof PendingOperation pending) {
                classStack.pop();
                return new ClassBinaryOp(pending.lhs().start(), rhs.end(), pending.operation(), pending.lhs(), rhs);
            }
            return rhs;
        }

        private RegexpParseException unclosedClass()
        {
            for (ClassStackEntry entry : classStack) {
                if (entry instanceof OpenClass open) {
                    return error(ErrorKind.CLASS_UNCLOSED, open.start(), open.openingEnd());
                }
            }
            throw new IllegalStateException("no open character class");
        }

        private ClassSetItem parseSetClassRange()
        {
            Ast first = parseSetClassItem();
            bumpSpace();
            if (isEof()) {
                throw unclosedClass();
            }
            if (current() != '-' || peekSpace() == ']' || peekSpace() == '-') {
                return intoClassSetItem(first);
            }
            if (!bumpAndBumpSpace()) {
                throw unclosedClass();
            }
            Ast last = parseSetClassItem();
            LiteralAst firstLiteral = intoClassLiteral(first);
            LiteralAst lastLiteral = intoClassLiteral(last);
            if (firstLiteral.codePoint() > lastLiteral.codePoint()) {
                throw error(ErrorKind.CLASS_RANGE_INVALID, first.start(), lastLiteral.end());
            }
            return new ClassRange(first.start(), lastLiteral.end(), firstLiteral, lastLiteral);
        }

        private Ast parseSetClassItem()
        {
            if (current() == '\\') {
                return parseEscape();
            }
            LiteralAst literal = new LiteralAst(position, position + 1, current(), false);
            bump();
            return literal;
        }

        private ClassSetItem intoClassSetItem(Ast primitive)
        {
            return switch (primitive) {
                case LiteralAst literal -> literal;
                case PerlClassAst perl -> perl;
                case UnicodeClassAst unicode -> unicode;
                default -> throw error(ErrorKind.CLASS_ESCAPE_INVALID, primitive.start(), primitive.end());
            };
        }

        private LiteralAst intoClassLiteral(Ast primitive)
        {
            if (primitive instanceof LiteralAst literal) {
                return literal;
            }
            throw error(ErrorKind.CLASS_RANGE_LITERAL, primitive.start(), primitive.end());
        }

        private ClassAscii maybeParseAsciiClass()
        {
            int start = position;
            if (!bump() || current() != ':') {
                position = start;
                return null;
            }
            if (!bump()) {
                position = start;
                return null;
            }
            boolean negated = false;
            if (current() == '^') {
                negated = true;
                if (!bump()) {
                    position = start;
                    return null;
                }
            }
            int nameStart = position;
            while (current() != ':' && bump()) {
                // Scan to the closing ':'.
            }
            if (isEof()) {
                position = start;
                return null;
            }
            String name = new String(codePoints, nameStart, position - nameStart);
            if (!bumpIf(":]")) {
                position = start;
                return null;
            }
            AsciiKind kind = AsciiKind.fromName(name);
            if (kind == null) {
                position = start;
                return null;
            }
            return new ClassAscii(start, position, kind, negated);
        }

        // Applies the crate's nesting limit in the order its visitor walks the tree.
        void checkNestLimit(Ast root)
        {
            Deque<NestFrame> pending = new ArrayDeque<>();
            pending.push(new NestFrame(root, 0));
            while (!pending.isEmpty()) {
                NestFrame frame = pending.pop();
                List<?> children = switch (frame.node()) {
                    case RepetitionAst repetition -> List.of(repetition.ast());
                    case GroupAst group -> List.of(group.ast());
                    case AlternationAst alternation -> alternation.asts();
                    case ConcatAst concat -> concat.asts();
                    case BracketedAst bracketed -> List.of(bracketed.kind());
                    case ClassUnion union -> union.items();
                    case ClassBinaryOp operation -> List.of(operation.lhs(), operation.rhs());
                    default -> null;
                };
                if (children == null) {
                    continue;
                }
                int depth = frame.depth() + 1;
                if (depth > NEST_LIMIT) {
                    if (frame.node() instanceof Ast ast) {
                        throw error(ErrorKind.NEST_LIMIT_EXCEEDED, ast.start(), ast.end());
                    }
                    ClassSet set = (ClassSet) frame.node();
                    throw error(ErrorKind.NEST_LIMIT_EXCEEDED, set.start(), set.end());
                }
                for (int index = children.size() - 1; index >= 0; index--) {
                    pending.push(new NestFrame(children.get(index), depth));
                }
            }
        }
    }

    // Translates the syntax tree with the crate's flag scoping and HIR error order.
    private static final class Translator
    {
        private final Parser parser;
        private int flags;
        private RegexpParseException unsupported;

        Translator(Parser parser, int syntaxFlags)
        {
            this.parser = parser;
            this.flags = syntaxFlags & ~IGNORE_WHITESPACE;
        }

        void throwUnsupported()
        {
            if (unsupported != null) {
                throw unsupported;
            }
        }

        // Records the first construct Regulator cannot express and keeps translating, because a
        // later crate error takes precedence.
        private void recordUnsupported(RegexpParseErrorCode code, String feature, int start, int end)
        {
            if (unsupported == null) {
                unsupported = parser.error(code, feature, start, end);
            }
        }

        private boolean enabled(int flag)
        {
            return (flags & flag) != 0;
        }

        // Translates in the crate's post-order with an explicit stack, so the first translation
        // error is the crate's and nesting depth does not consume thread stack.
        Regexp translate(Ast root)
        {
            Deque<TranslationFrame> stack = new ArrayDeque<>();
            stack.addLast(enter(root));
            while (true) {
                TranslationFrame frame = stack.getLast();
                if (frame.nextChildIndex < frame.children.size()) {
                    stack.addLast(enter(frame.children.get(frame.nextChildIndex++)));
                    continue;
                }
                stack.removeLast();
                Regexp regexp = exit(frame);
                if (stack.isEmpty()) {
                    return regexp;
                }
                TranslationFrame parent = stack.getLast();
                parent.childValues.add(regexp);
                if (!isEmptyWidth(frame, regexp)) {
                    parent.childrenEmptyWidth = false;
                }
            }
        }

        // A group's flags apply to its contents and end with it.
        private TranslationFrame enter(Ast ast)
        {
            TranslationFrame frame = new TranslationFrame(ast, flags);
            if (ast instanceof GroupAst group && group.flags() != null) {
                flags = group.flags().apply(flags);
            }
            return frame;
        }

        private Regexp exit(TranslationFrame frame)
        {
            List<Regexp> children = frame.childValues;
            return switch (frame.ast) {
                case EmptyAst _ -> Regexp.emptyMatch(NODE_FLAGS);
                case FlagsAst flagsAst -> {
                    flags = flagsAst.flags().apply(flags);
                    yield Regexp.emptyMatch(NODE_FLAGS);
                }
                case LiteralAst literal -> translateLiteral(literal);
                case DotAst dot -> translateDot(dot);
                case AssertionAst assertion -> translateAssertion(assertion);
                case PerlClassAst perl -> charClass(perlClass(perl));
                case UnicodeClassAst unicode -> charClass(unicodeClass(unicode));
                case BracketedAst bracketed -> charClass(bracketedClass(bracketed));
                case RepetitionAst repetition -> translateRepetition(repetition, children.getFirst(), frame.childrenEmptyWidth);
                case GroupAst group -> {
                    flags = frame.enclosingFlags;
                    yield translateGroup(group, children.getFirst());
                }
                case ConcatAst _ -> translateConcat(children);
                case AlternationAst _ -> Regexp.alternate(NODE_FLAGS, children);
            };
        }

        private static Regexp charClass(CharClassBuilder builder)
        {
            return Regexp.charClass(NODE_FLAGS, builder.toCharClass());
        }

        private Regexp translateLiteral(LiteralAst literal)
        {
            int codePoint = literal.codePoint();
            if (!enabled(UNICODE) && literal.hexByte() && codePoint > 0x7F) {
                throw parser.error(ErrorKind.INVALID_UTF8, literal.start(), literal.end());
            }
            if (enabled(CASE_INSENSITIVE)) {
                if (enabled(UNICODE) ? CASE_FOLDING.cycleFoldRune(codePoint) != codePoint : isAsciiLetter(codePoint)) {
                    CharClassBuilder builder = new CharClassBuilder();
                    builder.addRange(codePoint, codePoint);
                    return charClass(caseFold(builder));
                }
            }
            return Regexp.literal(NODE_FLAGS, codePoint);
        }

        private Regexp translateDot(DotAst dot)
        {
            // A byte-oriented dot can match part of a character, which the crate rejects in UTF-8 mode.
            if (!enabled(UNICODE)) {
                throw parser.error(ErrorKind.INVALID_UTF8, dot.start(), dot.end());
            }
            if (enabled(DOT_MATCHES_NEW_LINE)) {
                return Regexp.anyChar(NODE_FLAGS);
            }
            CharClassBuilder builder = new CharClassBuilder();
            builder.addRange('\n', '\n');
            if (enabled(CRLF)) {
                builder.addRange('\r', '\r');
            }
            builder.negate();
            return charClass(builder);
        }

        private Regexp translateAssertion(AssertionAst assertion)
        {
            return switch (assertion.kind()) {
                case START_LINE -> {
                    if (!enabled(MULTI_LINE)) {
                        yield Regexp.beginText(NODE_FLAGS);
                    }
                    yield enabled(CRLF) ? unsupportedAssertion(assertion, "CRLF multi-line ^") : Regexp.beginLine(NODE_FLAGS);
                }
                case END_LINE -> {
                    if (!enabled(MULTI_LINE)) {
                        yield Regexp.endText(NODE_FLAGS);
                    }
                    yield enabled(CRLF) ? unsupportedAssertion(assertion, "CRLF multi-line $") : Regexp.endLine(NODE_FLAGS);
                }
                case START_TEXT -> Regexp.beginText(NODE_FLAGS);
                case END_TEXT -> Regexp.endText(NODE_FLAGS);
                // The Java Unicode word boundary uses the same word characters as the crate's \w.
                case WORD_BOUNDARY -> Regexp.wordBoundary(enabled(UNICODE) ? UNICODE_WORD_BOUNDARY_FLAGS : NODE_FLAGS);
                case NOT_WORD_BOUNDARY -> Regexp.noWordBoundary(enabled(UNICODE) ? UNICODE_WORD_BOUNDARY_FLAGS : NODE_FLAGS);
                default -> unsupportedAssertion(assertion, assertion.kind().syntax);
            };
        }

        private Regexp unsupportedAssertion(AssertionAst assertion, String feature)
        {
            recordUnsupported(RegexpParseErrorCode.UNSUPPORTED_CONSTRUCT, feature, assertion.start(), assertion.start() + 1);
            return Regexp.emptyMatch(NODE_FLAGS);
        }

        private Regexp translateRepetition(RepetitionAst repetition, Regexp child, boolean childEmptyWidth)
        {
            long min = repetition.min();
            long max = repetition.max();
            // Like the crate's HIR, a repetition of an empty-width expression runs at most once,
            // and a repetition that runs zero times drops its expression and the captures in it.
            if (childEmptyWidth) {
                min = Math.min(min, 1);
                max = max < 0 ? 1 : Math.min(max, 1);
            }
            if (min == 0 && max == 0) {
                return Regexp.emptyMatch(NODE_FLAGS);
            }
            if (min == 1 && max == 1) {
                return child;
            }
            boolean greedy = repetition.greedy() != enabled(SWAP_GREED);
            int nodeFlags = greedy ? NODE_FLAGS : NODE_FLAGS | Regexp.NON_GREEDY;
            if (max < 0 && min <= 1) {
                return min == 0 ? Regexp.star(nodeFlags, child) : Regexp.plus(nodeFlags, child);
            }
            if (min == 0 && max == 1) {
                return Regexp.quest(nodeFlags, child);
            }
            if (min > MAX_REPEAT || max > MAX_REPEAT) {
                recordUnsupported(RegexpParseErrorCode.REPEAT_SIZE, "repetition count above " + MAX_REPEAT, repetition.start(), repetition.end());
                return child;
            }
            if (wouldExceedRepeatLimit(child, (int) min, (int) max)) {
                recordUnsupported(RegexpParseErrorCode.REPEAT_SIZE, "nested repetition count above " + MAX_REPEAT, repetition.start(), repetition.end());
                return child;
            }
            return Regexp.repeat(nodeFlags, child, (int) min, (int) max);
        }

        // Whether the translated node can match only the empty string, the crate's maximum length
        // of zero. Its children have already reported theirs, so no subtree is scanned twice.
        private static boolean isEmptyWidth(TranslationFrame frame, Regexp regexp)
        {
            return switch (frame.ast) {
                case EmptyAst _, FlagsAst _, AssertionAst _ -> true;
                // A repetition that runs zero times becomes an empty match.
                case RepetitionAst _ -> frame.childrenEmptyWidth || regexp.op() == RegexpOp.EMPTY_MATCH;
                case GroupAst _, ConcatAst _, AlternationAst _ -> frame.childrenEmptyWidth;
                default -> false;
            };
        }

        // The crate numbers groups up to the highest capture left after dropped repetitions.
        static int capturingGroupCount(Regexp regexp)
        {
            int count = 0;
            Deque<Regexp> pending = new ArrayDeque<>();
            pending.push(regexp);
            while (!pending.isEmpty()) {
                Regexp current = pending.pop();
                if (current.op() == RegexpOp.CAPTURE) {
                    count = Math.max(count, current.captureIndex());
                }
                current.children().forEach(pending::push);
            }
            return count;
        }

        private static Regexp translateGroup(GroupAst group, Regexp child)
        {
            if (group.captureIndex() == 0) {
                return child;
            }
            Slice name = group.name() == null ? null : Slices.utf8Slice(group.name());
            return Regexp.capture(NODE_FLAGS, child, group.captureIndex(), name);
        }

        private static Regexp translateConcat(List<Regexp> children)
        {
            List<Regexp> items = new ArrayList<>(children.size());
            int[] runes = new int[children.size()];
            int runeCount = 0;
            for (Regexp item : children) {
                if (item.op() == RegexpOp.EMPTY_MATCH) {
                    continue;
                }
                if (item.op() == RegexpOp.LITERAL && item.parseFlags() == NODE_FLAGS) {
                    runes[runeCount++] = item.rune();
                    continue;
                }
                if (runeCount > 0) {
                    items.add(makeLiteralString(NODE_FLAGS, runes, runeCount));
                    runeCount = 0;
                }
                items.add(item);
            }
            if (runeCount > 0) {
                items.add(makeLiteralString(NODE_FLAGS, runes, runeCount));
            }
            return switch (items.size()) {
                case 0 -> Regexp.emptyMatch(NODE_FLAGS);
                case 1 -> items.getFirst();
                default -> Regexp.concat(NODE_FLAGS, items);
            };
        }

        private CharClassBuilder perlClass(PerlClassAst perl)
        {
            CharClassBuilder builder = new CharClassBuilder();
            if (enabled(UNICODE)) {
                // The crate's Unicode Perl classes are Decimal_Number, White_Space, and the UTS #18
                // word class, which the database's groups hold; each is closed under case folding.
                CharClass characterClass = switch (perl.kind()) {
                    case DIGIT -> UnicodeGroups.generalCategory("Nd");
                    case SPACE -> UnicodeGroups.binaryPropertyValue("White_Space");
                    case WORD -> UnicodeGroups.word();
                };
                builder.addCharClass(requireNonNull(characterClass, () -> "missing Unicode group: " + perl.kind()), Regexp.CLASS_NEWLINE, CASE_FOLDING);
                if (perl.negated()) {
                    builder.negate();
                }
                return builder;
            }
            AsciiKind kind = switch (perl.kind()) {
                case DIGIT -> AsciiKind.DIGIT;
                case SPACE -> AsciiKind.SPACE;
                case WORD -> AsciiKind.WORD;
            };
            kind.addTo(builder);
            if (perl.negated()) {
                builder.negate();
            }
            requireAsciiBytes(builder, perl.start(), perl.end());
            return builder;
        }

        private CharClassBuilder unicodeClass(UnicodeClassAst unicode)
        {
            if (!enabled(UNICODE)) {
                throw parser.error(ErrorKind.UNICODE_NOT_ALLOWED, unicode.start(), unicode.end());
            }
            RustUnicodeProperties.Resolution resolution = unicode.kind() == UnicodeKind.NAMED_VALUE
                    ? RustUnicodeProperties.resolveValue(unicode.name(), unicode.value())
                    : RustUnicodeProperties.resolveName(unicode.name());
            CharClassBuilder builder = new CharClassBuilder();
            switch (resolution.status()) {
                case FOUND -> builder.addCharClass(resolution.charClass(), Regexp.CLASS_NEWLINE, CASE_FOLDING);
                case PROPERTY_NOT_FOUND -> throw parser.error(ErrorKind.UNICODE_PROPERTY_NOT_FOUND, unicode.start(), unicode.end());
                case PROPERTY_VALUE_NOT_FOUND -> throw parser.error(ErrorKind.UNICODE_PROPERTY_VALUE_NOT_FOUND, unicode.start(), unicode.end());
            }
            return foldAndNegate(builder, unicode.isNegated(), unicode.start(), unicode.end());
        }

        // Translates a class in the crate's post-order with an explicit stack, like the syntax tree.
        private CharClassBuilder bracketedClass(BracketedAst root)
        {
            Deque<ClassFrame> stack = new ArrayDeque<>();
            stack.addLast(new ClassFrame(root));
            while (true) {
                ClassFrame frame = stack.getLast();
                if (frame.nextChildIndex < frame.children.size()) {
                    stack.addLast(new ClassFrame(frame.children.get(frame.nextChildIndex++)));
                    continue;
                }
                stack.removeLast();
                CharClassBuilder builder = exitClass(frame);
                if (stack.isEmpty()) {
                    return builder;
                }
                stack.getLast().childValues.add(builder);
            }
        }

        private CharClassBuilder exitClass(ClassFrame frame)
        {
            List<CharClassBuilder> children = frame.childValues;
            return switch (frame.set) {
                case BracketedAst bracketed -> foldAndNegate(children.getFirst(), bracketed.negated(), bracketed.start(), bracketed.end());
                case ClassUnion _ -> {
                    CharClassBuilder builder = new CharClassBuilder();
                    for (CharClassBuilder member : children) {
                        builder.addCharClass(member);
                    }
                    yield builder;
                }
                case ClassBinaryOp operation -> setOperation(operation.operation(), children.get(0), children.get(1));
                case ClassEmpty _ -> new CharClassBuilder();
                case LiteralAst literal -> {
                    int codePoint = enabled(UNICODE) ? literal.codePoint() : classLiteralByte(literal);
                    CharClassBuilder builder = new CharClassBuilder();
                    builder.addRange(codePoint, codePoint);
                    yield builder;
                }
                case ClassRange range -> {
                    CharClassBuilder builder = new CharClassBuilder();
                    if (enabled(UNICODE)) {
                        builder.addRange(range.first().codePoint(), range.last().codePoint());
                    }
                    else {
                        int low = classLiteralByte(range.first());
                        int high = classLiteralByte(range.last());
                        builder.addRange(low, high);
                    }
                    yield builder;
                }
                case ClassAscii ascii -> {
                    CharClassBuilder builder = new CharClassBuilder();
                    ascii.kind().addTo(builder);
                    yield foldAndNegate(builder, ascii.negated(), ascii.start(), ascii.end());
                }
                case UnicodeClassAst unicode -> unicodeClass(unicode);
                case PerlClassAst perl -> perlClass(perl);
            };
        }

        private CharClassBuilder setOperation(SetOperation operation, CharClassBuilder lhs, CharClassBuilder rhs)
        {
            if (enabled(CASE_INSENSITIVE)) {
                rhs = caseFold(rhs);
                lhs = caseFold(lhs);
            }
            switch (operation) {
                case INTERSECTION -> lhs.retainAll(rhs);
                case DIFFERENCE -> {
                    CharClassBuilder excluded = rhs.copy();
                    excluded.negate();
                    lhs.retainAll(excluded);
                }
                case SYMMETRIC_DIFFERENCE -> {
                    CharClassBuilder common = lhs.copy();
                    common.retainAll(rhs);
                    common.negate();
                    lhs.addCharClass(rhs);
                    lhs.retainAll(common);
                }
            }
            return lhs;
        }

        // A byte class may only name ASCII bytes, because any other byte could split a character.
        private int classLiteralByte(LiteralAst literal)
        {
            int codePoint = literal.codePoint();
            if (codePoint <= 0x7F) {
                return codePoint;
            }
            if (literal.hexByte()) {
                throw parser.error(ErrorKind.INVALID_UTF8, literal.start(), literal.end());
            }
            throw parser.error(ErrorKind.UNICODE_NOT_ALLOWED, literal.start(), literal.end());
        }

        // Case folding precedes negation, so (?i)[^x] excludes both cases of x.
        private CharClassBuilder foldAndNegate(CharClassBuilder builder, boolean negated, int start, int end)
        {
            if (enabled(CASE_INSENSITIVE)) {
                builder = caseFold(builder);
            }
            if (negated) {
                builder.negate();
            }
            if (!enabled(UNICODE)) {
                requireAsciiBytes(builder, start, end);
            }
            return builder;
        }

        private void requireAsciiBytes(CharClassBuilder builder, int start, int end)
        {
            int rangeCount = builder.rangeCount();
            if (rangeCount > 0 && builder.range(rangeCount - 1).high() > 0x7F) {
                throw parser.error(ErrorKind.INVALID_UTF8, start, end);
            }
        }

        private CharClassBuilder caseFold(CharClassBuilder builder)
        {
            CharClassBuilder folded = builder.copy();
            for (int index = 0; index < builder.rangeCount(); index++) {
                RuneRange range = builder.range(index);
                if (enabled(UNICODE)) {
                    CASE_FOLDING.addOtherOrbitMembers(folded, range.low(), range.high());
                }
                else if (range.low() <= 0x7F) {
                    addFoldedRangeLatin1(folded, range.low(), Math.min(range.high(), 0x7F));
                }
            }
            return folded;
        }
    }

    private static boolean isAsciiLetter(int character)
    {
        return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    }

    // Rust's char::is_whitespace, the Unicode White_Space property.
    private static boolean isWhitespace(int character)
    {
        return (character >= '\t' && character <= '\r') ||
                character == ' ' ||
                character == 0x85 ||
                character == 0xA0 ||
                character == 0x1680 ||
                (character >= 0x2000 && character <= 0x200A) ||
                character == 0x2028 ||
                character == 0x2029 ||
                character == 0x202F ||
                character == 0x205F ||
                character == 0x3000;
    }

    // The crate's capture name characters: '_' or Rust's char::is_alphabetic, then also '.', '[',
    // ']', or char::is_numeric.
    private static boolean isCaptureCharacter(int character, boolean first)
    {
        if (character == '_' || isAsciiLetter(character)) {
            return true;
        }
        if (!first && (character == '.' || character == '[' || character == ']' || (character >= '0' && character <= '9'))) {
            return true;
        }
        if (character <= 0x7F) {
            return false;
        }
        return CaptureNameCharacters.ALPHABETIC.contains(character) || (!first && CaptureNameCharacters.NUMERIC.contains(character));
    }

    // Rust's char::is_alphabetic is the Alphabetic property, and char::is_numeric the N general
    // category. The classes are loaded lazily, when the first capture name that is not ASCII
    // needs them.
    private static final class CaptureNameCharacters
    {
        private static final CharClass ALPHABETIC = requireNonNull(UnicodeGroups.binaryPropertyValue("Alphabetic"), "missing Unicode group: Alphabetic");
        private static final CharClass NUMERIC = requireNonNull(UnicodeGroups.generalCategory("N"), "missing Unicode group: N");
    }

    // Meta characters and every other ASCII character except letters, digits, '<' and '>'.
    private static boolean isEscapeableCharacter(int character)
    {
        if (character > 0x7F) {
            return false;
        }
        return !(character >= '0' && character <= '9') && !isAsciiLetter(character) && character != '<' && character != '>';
    }

    private static boolean isSpecialWordBoundaryCharacter(int character)
    {
        return isAsciiLetter(character) || character == '-';
    }

    private static boolean isScalarValue(int value)
    {
        return value >= 0 && value <= CharClass.RUNEMAX && (value < 0xD800 || value > 0xDFFF);
    }
}
