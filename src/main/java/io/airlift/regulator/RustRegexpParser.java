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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.airlift.regulator.RegexpParserSupport.decodeHexDigit;
import static io.airlift.slice.Slices.utf8Slice;

/**
 * Rust regex syntax. Parser state never becomes shared-engine parse flags.
 */
final class RustRegexpParser
{
    static final int CASE_INSENSITIVE = 1;
    static final int MULTILINE = 1 << 1;
    static final int DOT_ALL = 1 << 2;
    static final int CRLF = 1 << 3;
    static final int SWAP_GREED = 1 << 4;
    static final int UNICODE = 1 << 5;
    static final int IGNORE_WHITESPACE = 1 << 6;

    private RustRegexpParser() {}

    static ParseResult parse(Slice pattern, RustRegexp.Options options)
    {
        int invalidOffset = Utf8.firstInvalidOffset(pattern);
        if (invalidOffset >= 0) {
            throw new RegexpParseException(RegexpParseErrorCode.BAD_UTF8, null, invalidOffset);
        }
        Parser parser = new Parser(pattern, options);
        Regexp regexp = parser.expression();
        if (parser.peek() != -1) {
            throw parser.error(RegexpParseErrorCode.UNEXPECTED_PAREN);
        }
        return new ParseResult(Simplifier.simplifyForParse(regexp), parser.capturingGroups);
    }

    @SuppressWarnings("CharUsedInArithmeticContext")
    private static final class Parser
    {
        private final int[] runes;
        private final int[] offsets;
        private final boolean octal;
        private final int nestLimit;
        private final Set<String> names = new HashSet<>();
        private int position;
        private int flags;
        private int depth;
        private int capturingGroups;

        Parser(Slice pattern, RustRegexp.Options options)
        {
            runes = pattern.toStringUtf8().codePoints().toArray();
            offsets = new int[runes.length + 1];
            for (int index = 0; index < runes.length; index++) {
                int rune = runes[index];
                offsets[index + 1] = offsets[index] + (rune < 0x80 ? 1 : rune < 0x800 ? 2 : rune < 0x10000 ? 3 : 4);
            }
            flags = options.flags();
            octal = options.octal();
            nestLimit = options.nestLimit();
        }

        private RegexpParseException error(RegexpParseErrorCode code)
        {
            return new RegexpParseException(code, null, offsets[position]);
        }

        private boolean enabled(int flag)
        {
            return (flags & flag) != 0;
        }

        private int raw()
        {
            return position == runes.length ? -1 : runes[position];
        }

        private int peek()
        {
            if (enabled(IGNORE_WHITESPACE)) {
                while (position < runes.length) {
                    if (isWhitespace(raw())) {
                        position++;
                    }
                    else if (raw() == '#') {
                        while (position < runes.length && raw() != '\n') {
                            position++;
                        }
                    }
                    else {
                        break;
                    }
                }
            }
            return raw();
        }

        private static boolean isWhitespace(int rune)
        {
            return (rune >= '\t' && rune <= '\r') || rune == ' ' || rune == 0x85 || rune == 0xA0 ||
                    rune == 0x1680 || (rune >= 0x2000 && rune <= 0x200A) || rune == 0x2028 ||
                    rune == 0x2029 || rune == 0x202F || rune == 0x205F || rune == 0x3000;
        }

        private boolean take(int expected)
        {
            if (peek() != expected) {
                return false;
            }
            position++;
            return true;
        }

        private boolean takeRaw(int expected)
        {
            if (raw() != expected) {
                return false;
            }
            position++;
            return true;
        }

        private void enter()
        {
            if (++depth > nestLimit) {
                throw error(RegexpParseErrorCode.PATTERN_TOO_LARGE);
            }
        }

        private Regexp expression()
        {
            List<Regexp> alternatives = new ArrayList<>();
            do {
                List<Regexp> sequence = new ArrayList<>();
                while (peek() != -1 && peek() != ')' && peek() != '|') {
                    Regexp atom = atom();
                    if (atom == null) {
                        continue;
                    }
                    while (peek() == '*' || peek() == '+' || peek() == '?' || peek() == '{') {
                        atom = repeat(atom);
                    }
                    sequence.add(atom);
                }
                alternatives.add(sequence.isEmpty() ? Regexp.emptyMatch(Regexp.LIKE_PERL) : sequence.size() == 1 ? sequence.getFirst() : Regexp.concat(Regexp.LIKE_PERL, sequence));
            }
            while (take('|'));
            return alternatives.size() == 1 ? alternatives.getFirst() : Regexp.alternate(Regexp.LIKE_PERL, alternatives);
        }

        private Regexp atom()
        {
            int rune = peek();
            position++;
            return switch (rune) {
                case '*', '+', '?', '{' -> throw error(RegexpParseErrorCode.REPEAT_ARGUMENT);
                case '(' -> group();
                case '[' -> classNode(characterClass());
                case '\\' -> escape(false);
                case '.' -> {
                    CharClassBuilder characters = universe();
                    if (!enabled(DOT_ALL)) {
                        RustUnicode.removeRange(characters, '\n', '\n');
                        if (enabled(CRLF)) {
                            RustUnicode.removeRange(characters, '\r', '\r');
                        }
                    }
                    yield classNode(characters);
                }
                case '^' -> enabled(MULTILINE)
                        ? Regexp.beginLine(enabled(CRLF) ? Regexp.RUST_ASSERTION : 0)
                        : Regexp.beginText(Regexp.LIKE_PERL);
                case '$' -> enabled(MULTILINE)
                        ? Regexp.endLine(enabled(CRLF) ? Regexp.RUST_ASSERTION : 0)
                        : Regexp.endText(Regexp.LIKE_PERL);
                default -> literal(rune);
            };
        }

        private Regexp group()
        {
            enter();
            int savedFlags = flags;
            boolean capture = true;
            Slice name = null;
            if (take('?')) {
                if (takeRaw(':')) {
                    capture = false;
                }
                else if (takeRaw('P')) {
                    if (!takeRaw('<')) {
                        throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE);
                    }
                    name = captureName();
                }
                else if (takeRaw('<')) {
                    name = captureName();
                }
                else {
                    capture = false;
                    inlineFlags();
                    if (takeRaw(')')) {
                        depth--;
                        return null;
                    }
                    if (!takeRaw(':')) {
                        throw error(RegexpParseErrorCode.BAD_PERL_OP);
                    }
                }
            }
            int captureIndex = capture ? ++capturingGroups : 0;
            Regexp child = expression();
            if (!takeRaw(')')) {
                throw error(RegexpParseErrorCode.MISSING_PAREN);
            }
            flags = savedFlags;
            depth--;
            return capture ? Regexp.capture(Regexp.LIKE_PERL, child, captureIndex, name) : child;
        }

        private Slice captureName()
        {
            int start = position;
            while (raw() != -1 && raw() != '>') {
                int rune = raw();
                boolean alphabetic = RustUnicode.property("Alphabetic").contains(rune);
                if (rune != '_' && !alphabetic &&
                        (position == start || (rune != '.' && rune != '[' && rune != ']' && !RustUnicode.property("N").contains(rune)))) {
                    throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE);
                }
                position++;
            }
            if (position == start || raw() != '>') {
                throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE);
            }
            String name = new String(runes, start, position - start);
            if (!names.add(name)) {
                throw error(RegexpParseErrorCode.BAD_NAMED_CAPTURE);
            }
            position++;
            return utf8Slice(name);
        }

        private void inlineFlags()
        {
            int seen = 0;
            boolean negate = false;
            boolean sawAfterMinus = false;
            while (raw() != ':' && raw() != ')') {
                int rune = raw();
                if (rune == '-') {
                    if (negate) {
                        throw error(RegexpParseErrorCode.BAD_PERL_OP);
                    }
                    negate = true;
                    position++;
                    continue;
                }
                int flag = switch (rune) {
                    case 'i' -> CASE_INSENSITIVE;
                    case 'm' -> MULTILINE;
                    case 's' -> DOT_ALL;
                    case 'R' -> CRLF;
                    case 'U' -> SWAP_GREED;
                    case 'u' -> UNICODE;
                    case 'x' -> IGNORE_WHITESPACE;
                    default -> throw error(RegexpParseErrorCode.BAD_PERL_OP);
                };
                if ((seen & flag) != 0) {
                    throw error(RegexpParseErrorCode.BAD_PERL_OP);
                }
                seen |= flag;
                flags = negate ? flags & ~flag : flags | flag;
                sawAfterMinus |= negate;
                position++;
            }
            if (seen == 0 || (negate && !sawAfterMinus)) {
                throw error(RegexpParseErrorCode.BAD_PERL_OP);
            }
        }

        private Regexp repeat(Regexp child)
        {
            int operator = peek();
            position++;
            int min = 0;
            int max = -1;
            if (operator == '{') {
                min = decimal();
                max = min;
                if (take(',')) {
                    max = peek() == '}' ? -1 : decimal();
                }
                if (!take('}') || (max >= 0 && min > max)) {
                    throw error(RegexpParseErrorCode.REPEAT_ARGUMENT);
                }
                // The shared compiler bounds counted expansion before allocating its program.
                if (min > Regexp.MAX_REPEAT || max > Regexp.MAX_REPEAT || RegexpParserSupport.wouldExceedRepeatLimit(child, min, max)) {
                    throw error(RegexpParseErrorCode.REPEAT_SIZE);
                }
            }
            boolean lazy = enabled(SWAP_GREED);
            // Rust skips verbose whitespace before the lazy suffix of counted repetitions only.
            if (operator == '{') {
                peek();
            }
            if (raw() == '?') {
                position++;
                lazy = !lazy;
            }
            int nodeFlags = Regexp.LIKE_PERL | (lazy ? Regexp.NON_GREEDY : 0);
            return switch (operator) {
                case '*' -> Regexp.star(nodeFlags, child);
                case '+' -> Regexp.plus(nodeFlags, child);
                case '?' -> Regexp.quest(nodeFlags, child);
                default -> Regexp.repeat(nodeFlags, child, min, max);
            };
        }

        private int decimal()
        {
            // Rust permits whitespace around counts even without verbose mode.
            while (isWhitespace(raw())) {
                position++;
            }
            if (raw() < '0' || raw() > '9') {
                throw error(RegexpParseErrorCode.REPEAT_ARGUMENT);
            }
            long value = 0;
            while (raw() >= '0' && raw() <= '9') {
                value = value * 10 + raw() - '0';
                if (value > Integer.MAX_VALUE) {
                    throw error(RegexpParseErrorCode.REPEAT_SIZE);
                }
                position++;
                peek();
            }
            while (isWhitespace(raw())) {
                position++;
            }
            return (int) value;
        }

        private Regexp escape(boolean inClass)
        {
            int rune = raw();
            if (rune == -1) {
                throw error(RegexpParseErrorCode.TRAILING_BACKSLASH);
            }
            position++;
            if (rune == 'p' || rune == 'P') {
                if (!enabled(UNICODE)) {
                    throw error(RegexpParseErrorCode.BAD_CHAR_CLASS);
                }
                boolean negate = rune == 'P';
                String property;
                if (take('{')) {
                    StringBuilder name = new StringBuilder();
                    while (peek() != -1 && peek() != '}') {
                        name.appendCodePoint(raw());
                        position++;
                    }
                    if (raw() != '}') {
                        throw error(RegexpParseErrorCode.BAD_CHAR_CLASS);
                    }
                    property = name.toString();
                    position++;
                }
                else {
                    if (raw() == -1) {
                        throw error(RegexpParseErrorCode.BAD_CHAR_CLASS);
                    }
                    property = new String(new int[] {raw()}, 0, 1);
                    position++;
                }
                int inequality = property.indexOf("!=");
                if (inequality >= 0) {
                    negate = !negate;
                    property = property.substring(0, inequality) + "=" + property.substring(inequality + 2);
                }
                String normalizedProperty = property.replace(':', '=');
                CharClass characters = RustUnicode.property(normalizedProperty);
                if (characters == null) {
                    throw new RegexpParseException(
                            RustUnicode.knownProperty(normalizedProperty) ? RegexpParseErrorCode.UNSUPPORTED_CONSTRUCT : RegexpParseErrorCode.BAD_CHAR_CLASS,
                            null,
                            offsets[position],
                            "Unicode property " + property);
                }
                CharClassBuilder result = folded(characters);
                if (negate) {
                    negate(result);
                }
                return inClass && enabled(UNICODE) ? Regexp.charClass(Regexp.CLASS_NEWLINE, result.toCharClass()) : classNode(result);
            }
            if ("dDsSwW".indexOf(rune) >= 0) {
                boolean negate = rune == 'D' || rune == 'S' || rune == 'W';
                String name = switch (rune) {
                    case 'd', 'D' -> enabled(UNICODE) ? "Nd" : "ascii:digit";
                    case 's', 'S' -> enabled(UNICODE) ? "White_Space" : "ascii:space";
                    default -> enabled(UNICODE) ? "perl:word" : "ascii:word";
                };
                CharClassBuilder result = folded(RustUnicode.property(name));
                if (negate) {
                    negate(result);
                }
                return inClass && enabled(UNICODE) ? Regexp.charClass(Regexp.CLASS_NEWLINE, result.toCharClass()) : classNode(result);
            }
            if (!inClass) {
                if (rune == 'A') {
                    return Regexp.beginText(Regexp.LIKE_PERL);
                }
                if (rune == 'z') {
                    return Regexp.endText(Regexp.LIKE_PERL);
                }
                if (rune == 'b' || rune == 'B' || rune == '<' || rune == '>') {
                    int kind = rune == '<' ? 1 : rune == '>' ? 2 : 0;
                    if (rune == 'b' && raw() == '{') {
                        int saved = position++;
                        int start = position;
                        while ((raw() >= 'a' && raw() <= 'z') || raw() == '-') {
                            position++;
                        }
                        String name = new String(runes, start, position - start);
                        if (raw() == '}' && !name.isEmpty()) {
                            kind = switch (name) {
                                case "start" -> 1;
                                case "end" -> 2;
                                case "start-half" -> 3;
                                case "end-half" -> 4;
                                default -> throw error(RegexpParseErrorCode.BAD_ESCAPE);
                            };
                            position++;
                        }
                        else {
                            position = saved;
                        }
                    }
                    int boundaryFlags = Regexp.RUST_ASSERTION | (kind << Regexp.RUST_BOUNDARY_SHIFT) |
                            (enabled(UNICODE) ? Regexp.RUST_UNICODE : 0);
                    return rune == 'B' ? Regexp.noWordBoundary(boundaryFlags) : Regexp.wordBoundary(boundaryFlags);
                }
            }
            boolean byteEscape = (rune == 'x' && peek() != '{') || (rune >= '0' && rune <= '7');
            int literal = switch (rune) {
                case 'a' -> 7;
                case 'f' -> '\f';
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 'v' -> 11;
                case 'x' -> hex(2);
                case 'u' -> hex(4);
                case 'U' -> hex(8);
                default -> {
                    if (octal && rune >= '0' && rune <= '7') {
                        int value = rune - '0';
                        for (int digit = 1; digit < 3 && raw() >= '0' && raw() <= '7'; digit++) {
                            value = (value << 3) | (raw() - '0');
                            position++;
                        }
                        yield value;
                    }
                    if (rune >= 128 || (rune >= '0' && rune <= '9') || (rune >= 'A' && rune <= 'Z') ||
                            (rune >= 'a' && rune <= 'z') || rune == '<' || rune == '>') {
                        throw error(RegexpParseErrorCode.BAD_ESCAPE);
                    }
                    yield rune;
                }
            };
            if (!enabled(UNICODE) && literal > 127 && (inClass || byteEscape)) {
                throw new RegexpParseException(RegexpParseErrorCode.BAD_CHAR_CLASS, null, offsets[position], "pattern can match invalid UTF-8");
            }
            return inClass ? Regexp.literal(Regexp.LIKE_PERL, literal) : literal(literal);
        }

        private int hex(int width)
        {
            boolean braced = peek() == '{';
            if (braced) {
                position++;
            }
            long value = 0;
            int digits = 0;
            while (braced ? peek() != '}' : digits < width) {
                peek();
                int digit = decodeHexDigit(raw());
                if (digit < 0) {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE);
                }
                value = (value << 4) | digit;
                if (value > 0x10FFFF) {
                    throw error(RegexpParseErrorCode.BAD_ESCAPE);
                }
                position++;
                digits++;
            }
            if (digits == 0 || (value >= 0xD800 && value <= 0xDFFF)) {
                throw error(RegexpParseErrorCode.BAD_ESCAPE);
            }
            if (braced) {
                position++;
            }
            return (int) value;
        }

        private CharClassBuilder characterClass()
        {
            enter();
            boolean negated = take('^');
            CharClassBuilder result = classUnion(true);
            while (isSetOperator()) {
                int operator = peek();
                position += 2;
                CharClassBuilder right = classUnion(false);
                if (operator == '&') {
                    result.retainAll(right);
                }
                else if (operator == '-') {
                    RustUnicode.subtract(result, right);
                }
                else {
                    CharClassBuilder intersection = copy(result.toCharClass());
                    intersection.retainAll(right);
                    result.addCharClass(right);
                    RustUnicode.subtract(result, intersection);
                }
            }
            if (!take(']')) {
                throw error(RegexpParseErrorCode.MISSING_BRACKET);
            }
            if (negated) {
                negate(result);
            }
            depth--;
            return result;
        }

        private boolean isSetOperator()
        {
            int rune = peek();
            return (rune == '&' || rune == '-' || rune == '~') && position + 1 < runes.length && runes[position + 1] == rune;
        }

        private CharClassBuilder classUnion(boolean first)
        {
            CharClassBuilder result = new CharClassBuilder();
            // A run of leading hyphens is literal, even when it has two or more members.
            while (take('-')) {
                result.addRange('-', '-');
                first = false;
            }
            while (peek() != -1 && (peek() != ']' || first) && !isSetOperator()) {
                int rune = peek();
                if (rune == '[') {
                    position++;
                    int savedPosition = position;
                    CharClassBuilder asciiClass = asciiClass();
                    if (asciiClass != null) {
                        result.addCharClass(asciiClass);
                    }
                    else {
                        position = savedPosition;
                        result.addCharClass(characterClass());
                    }
                }
                else {
                    Regexp atom;
                    position++;
                    if (rune == '\\') {
                        atom = escape(true);
                    }
                    else {
                        if (!enabled(UNICODE) && rune > 127) {
                            throw new RegexpParseException(RegexpParseErrorCode.BAD_CHAR_CLASS, null, offsets[position], "pattern can match invalid UTF-8");
                        }
                        atom = Regexp.literal(Regexp.LIKE_PERL, rune);
                    }
                    if (peek() == '-' && !isSetOperator()) {
                        int savedPosition = position++;
                        if (peek() == ']') {
                            position = savedPosition;
                            addClassAtom(result, atom);
                        }
                        else {
                            if (atom.op() != RegexpOp.LITERAL) {
                                throw error(RegexpParseErrorCode.BAD_CHAR_RANGE);
                            }
                            int high = peek();
                            if (high == -1) {
                                throw error(RegexpParseErrorCode.MISSING_BRACKET);
                            }
                            position++;
                            if (high == '\\') {
                                Regexp endpoint = escape(true);
                                if (endpoint.op() != RegexpOp.LITERAL) {
                                    throw error(RegexpParseErrorCode.BAD_CHAR_RANGE);
                                }
                                high = endpoint.rune();
                            }
                            if (!enabled(UNICODE) && high > 127) {
                                throw new RegexpParseException(RegexpParseErrorCode.BAD_CHAR_CLASS, null, offsets[position], "pattern can match invalid UTF-8");
                            }
                            if (high < atom.rune() || high == '[') {
                                throw error(RegexpParseErrorCode.BAD_CHAR_RANGE);
                            }
                            CharClassBuilder range = new CharClassBuilder();
                            range.addRange(atom.rune(), high);
                            result.addCharClass(folded(range.toCharClass()));
                        }
                    }
                    else {
                        addClassAtom(result, atom);
                    }
                }
                first = false;
            }
            return result;
        }

        // Rust falls back to a nested bracket class when [:name:] is not a known ASCII class.
        private CharClassBuilder asciiClass()
        {
            if (raw() != ':') {
                return null;
            }
            position++;
            boolean negated = raw() == '^';
            if (negated) {
                position++;
            }
            int start = position;
            while (raw() >= 'a' && raw() <= 'z') {
                position++;
            }
            String name = new String(runes, start, position - start);
            if (raw() != ':' || position + 1 >= runes.length || runes[position + 1] != ']') {
                return null;
            }
            CharClass characters = RustUnicode.property("ascii:" + name);
            if (characters == null) {
                return null;
            }
            position += 2;
            CharClassBuilder operand = folded(characters);
            if (negated) {
                negate(operand);
            }
            if (!enabled(UNICODE)) {
                classNode(operand);
            }
            return operand;
        }

        private void addClassAtom(CharClassBuilder result, Regexp atom)
        {
            if (atom.op() == RegexpOp.LITERAL) {
                CharClassBuilder single = new CharClassBuilder();
                single.addRange(atom.rune(), atom.rune());
                result.addCharClass(folded(single.toCharClass()));
            }
            else if (atom.op() == RegexpOp.CHAR_CLASS) {
                result.addCharClass(atom.charClass(), Regexp.CLASS_NEWLINE);
            }
            else if (atom.op() != RegexpOp.NO_MATCH) {
                throw error(RegexpParseErrorCode.BAD_CHAR_CLASS);
            }
        }

        private Regexp literal(int rune)
        {
            if (rune < 0 || (rune >= 0xD800 && rune <= 0xDFFF)) {
                throw new RegexpParseException(RegexpParseErrorCode.BAD_CHAR_CLASS, null, offsets[position], "pattern can match invalid UTF-8");
            }
            if (!enabled(CASE_INSENSITIVE)) {
                return Regexp.literal(Regexp.LIKE_PERL, rune);
            }
            CharClassBuilder single = new CharClassBuilder();
            single.addRange(rune, rune);
            return classNode(folded(single.toCharClass()));
        }

        private CharClassBuilder folded(CharClass characters)
        {
            CharClassBuilder result = copy(characters);
            if (enabled(CASE_INSENSITIVE)) {
                RustUnicode.fold(result, enabled(UNICODE));
            }
            return result;
        }

        private static CharClassBuilder copy(CharClass characters)
        {
            CharClassBuilder result = new CharClassBuilder();
            result.addCharClass(characters, Regexp.CLASS_NEWLINE);
            return result;
        }

        private CharClassBuilder universe()
        {
            CharClassBuilder result = new CharClassBuilder();
            result.addRange(0, enabled(UNICODE) ? 0x10FFFF : 255);
            RustUnicode.removeRange(result, 0xD800, 0xDFFF);
            return result;
        }

        private void negate(CharClassBuilder characters)
        {
            characters.negate();
            RustUnicode.removeRange(characters, 0xD800, 0xDFFF);
            characters.removeAbove(enabled(UNICODE) ? 0x10FFFF : 255);
        }

        private Regexp classNode(CharClassBuilder characters)
        {
            RustUnicode.removeRange(characters, 0xD800, 0xDFFF);
            if (!enabled(UNICODE) && characters.rangeCount() > 0 && characters.range(characters.rangeCount() - 1).high() > 127) {
                throw new RegexpParseException(RegexpParseErrorCode.BAD_CHAR_CLASS, null, offsets[position], "pattern can match invalid UTF-8");
            }
            return Regexp.charClass(Regexp.CLASS_NEWLINE, characters.toCharClass());
        }
    }
}
