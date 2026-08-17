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

import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

final class Regexp
{
    // Parse flags (re2/regexp.h). Keep bit values in sync with pinned upstream.
    // Single-bit flags.
    static final int FOLD_CASE = 1 << 0;
    static final int LITERAL = 1 << 1;
    static final int CLASS_NEWLINE = 1 << 2;
    static final int DOT_MATCHES_NEWLINE = 1 << 3;
    static final int ONE_LINE = 1 << 4;
    static final int LATIN1 = 1 << 5;
    static final int NON_GREEDY = 1 << 6;
    static final int PERL_CLASSES = 1 << 7;
    static final int PERL_WORD_BOUNDARY = 1 << 8;
    static final int PERL_EXTENSIONS = 1 << 9;
    static final int UNICODE_GROUPS = 1 << 10;
    static final int NEVER_NEWLINE = 1 << 11;
    static final int NEVER_CAPTURE = 1 << 12;

    // Frontend-only parser state; this is not an upstream RE2 flag.
    static final int ASCII_FOLD_CASE = 1 << 14;
    static final int WAS_DOLLAR = 1 << 15;
    static final int FINAL_LINE_END = 1 << 16;
    static final int UNICODE_WORD_BOUNDARY = 1 << 17;
    static final int FULL_CASE_FOLD = 1 << 18;
    static final int JAVA_UNIX_LINES = 1 << 19;
    static final int JAVA_UNICODE_CASE = 1 << 20;
    static final int JAVA_UNICODE_CHARACTER_CLASS = 1 << 21;
    static final int JAVA_COMMENTS = 1 << 22;
    static final int JAVA_FINAL_END = 1 << 23;
    static final int JAVA_LINE = 1 << 24;
    static final int JAVA_WORD_BOUNDARY = 1 << 25;

    // Composite flag groups and masks.
    static final int MATCH_NEWLINE = CLASS_NEWLINE | DOT_MATCHES_NEWLINE;
    static final int LIKE_PERL = CLASS_NEWLINE | ONE_LINE | PERL_CLASSES | PERL_WORD_BOUNDARY | PERL_EXTENSIONS | UNICODE_GROUPS;
    static final int ALL_PARSE_FLAGS = (1 << 26) - 1;

    // util/utf.h Runemax
    static final int RUNEMAX = 0x10FFFF;

    private final RegexpOp op;
    private final int parseFlags;
    private final List<Regexp> children;

    // Op-specific payload
    private final int rune;              // LITERAL
    private final int[] runes;           // LITERAL_STRING
    private final CharClass charClass;   // CHAR_CLASS
    private final int captureIndex;               // CAPTURE
    private final Slice name;        // CAPTURE (optional)
    private final int min;               // REPEAT
    private final int max;               // REPEAT (-1 = unbounded)
    private final int matchId;           // HAVE_MATCH

    record RequiredPrefixResult(Slice prefix, boolean foldCase, Regexp suffix) {}

    record RequiredPrefixForAccelResult(Slice prefix, boolean foldCase) {}

    private Regexp(
            RegexpOp op,
            int parseFlags,
            List<Regexp> children,
            int rune,
            int[] runes,
            CharClass charClass,
            int captureIndex,
            Slice name,
            int min,
            int max,
            int matchId)
    {
        this.op = requireNonNull(op, "op is null");
        this.parseFlags = parseFlags;
        this.children = List.copyOf(requireNonNull(children, "children is null"));
        this.rune = rune;
        this.runes = runes;
        this.charClass = charClass;
        this.captureIndex = captureIndex;
        this.name = name;
        this.min = min;
        this.max = max;
        this.matchId = matchId;
    }

    RegexpOp op()
    {
        return op;
    }

    int parseFlags()
    {
        return parseFlags;
    }

    /**
     * Determines whether matches must be anchored with a fixed string prefix
     * (i.e., the regexp is of the form {@code ^literal...} in one-line mode).
     * If so, returns the prefix (as bytes), whether it is ASCII-case-folded,
     * and the remaining regexp suffix.
     */
    RequiredPrefixResult requiredPrefix()
    {
        // The regexp must be of the form:
        // 1. some number of BEGIN_TEXT anchors
        // 2. a literal char or string
        // 3. the rest
        if (op != RegexpOp.CONCAT) {
            return null;
        }

        int childIndex = 0;
        while (childIndex < children.size() && children.get(childIndex).op == RegexpOp.BEGIN_TEXT) {
            childIndex++;
        }
        if (childIndex == 0 || childIndex >= children.size()) {
            return null;
        }

        Regexp regexp = children.get(childIndex);
        if (regexp.op != RegexpOp.LITERAL && regexp.op != RegexpOp.LITERAL_STRING) {
            return null;
        }
        if ((regexp.parseFlags & FULL_CASE_FOLD) != 0 && !supportsAsciiFoldPrefix(regexp)) {
            return null;
        }
        childIndex++;

        Regexp suffix;
        if (childIndex < children.size()) {
            List<Regexp> suffixSubs = children.subList(childIndex, children.size());
            if (suffixSubs.size() == 1) {
                suffix = suffixSubs.getFirst();
            }
            else {
                suffix = Regexp.concat(parseFlags, suffixSubs);
            }
        }
        else {
            suffix = Regexp.emptyMatch(parseFlags);
        }

        boolean latin1 = (regexp.parseFlags & LATIN1) != 0;
        byte[] prefixBytes = convertRunesToBytes(latin1, regexp.op == RegexpOp.LITERAL ? new int[] {regexp.rune} : regexp.runes);
        boolean foldCase = (regexp.parseFlags & FOLD_CASE) != 0;
        return new RequiredPrefixResult(Slices.wrappedBuffer(prefixBytes), foldCase, suffix);
    }

    /**
     * Determines whether matches must be unanchored with a fixed string prefix.
     * This "sees through" capturing groups but does not try to glue multiple
     * prefix fragments together.
     */
    RequiredPrefixForAccelResult requiredPrefixForAccel()
    {
        // The regexp must either begin with or be a literal char or string.
        Regexp regexp = (op == RegexpOp.CONCAT && !children.isEmpty()) ? children.getFirst() : this;
        while (regexp.op == RegexpOp.CAPTURE) {
            regexp = regexp.children.getFirst();
            if (regexp.op == RegexpOp.CONCAT && !regexp.children.isEmpty()) {
                regexp = regexp.children.getFirst();
            }
        }
        if (regexp.op != RegexpOp.LITERAL && regexp.op != RegexpOp.LITERAL_STRING) {
            return null;
        }
        if ((regexp.parseFlags & FULL_CASE_FOLD) != 0 && !supportsAsciiFoldPrefix(regexp)) {
            return null;
        }

        boolean latin1 = (regexp.parseFlags & LATIN1) != 0;
        byte[] prefixBytes = convertRunesToBytes(latin1, regexp.op == RegexpOp.LITERAL ? new int[] {regexp.rune} : regexp.runes);
        boolean foldCase = (regexp.parseFlags & FOLD_CASE) != 0;
        return new RequiredPrefixForAccelResult(Slices.wrappedBuffer(prefixBytes), foldCase);
    }

    private static boolean supportsAsciiFoldPrefix(Regexp regexp)
    {
        int[] literalRunes = regexp.op == RegexpOp.LITERAL ? new int[] {regexp.rune} : regexp.runes;
        for (int literalRune : literalRunes) {
            int foldedRune = literalRune;
            do {
                if (foldedRune > 0x7F) {
                    return false;
                }
                foldedRune = UnicodeCaseFold.cycleFoldRune(foldedRune);
            }
            while (foldedRune != literalRune);
        }
        return !UnicodeFullCaseFold.requiresFullCaseFold(literalRunes);
    }

    /**
     * Returns the complete encoded literal matched by this expression, or {@code null} when the
     * expression contains non-literal behavior or case folding. Captures are transparent because
     * callers use this only when capture offsets are not requested.
     */
    Slice exactLiteral()
    {
        DynamicSliceOutput literal = new DynamicSliceOutput(16);
        Deque<Regexp> stack = new ArrayDeque<>();
        stack.addLast(this);

        while (!stack.isEmpty()) {
            Regexp regexp = stack.removeLast();
            switch (regexp.op) {
                case EMPTY_MATCH -> {}
                case CAPTURE -> stack.addLast(regexp.children.getFirst());
                case CONCAT -> stack.addAll(regexp.children.reversed());
                case LITERAL -> {
                    if ((regexp.parseFlags & FOLD_CASE) != 0) {
                        return null;
                    }
                    literal.writeBytes(convertRunesToBytes(
                            (regexp.parseFlags & LATIN1) != 0,
                            new int[] {regexp.rune}));
                }
                case LITERAL_STRING -> {
                    if ((regexp.parseFlags & FOLD_CASE) != 0) {
                        return null;
                    }
                    literal.writeBytes(convertRunesToBytes(
                            (regexp.parseFlags & LATIN1) != 0,
                            regexp.runes));
                }
                default -> {
                    return null;
                }
            }
        }
        return literal.slice();
    }

    /**
     * Returns named capturing groups keyed by group name.
     */
    Map<String, Integer> namedCaptures()
    {
        HashMap<String, Integer> namedCaptures = null;
        Deque<Regexp> stack = new ArrayDeque<>();
        stack.addLast(this);

        while (!stack.isEmpty()) {
            Regexp regexp = stack.removeLast();
            if (regexp.op == RegexpOp.CAPTURE && regexp.name != null) {
                if (namedCaptures == null) {
                    namedCaptures = new HashMap<>();
                }
                namedCaptures.putIfAbsent(regexp.name.toStringUtf8(), regexp.captureIndex);
            }

            // Push children in reverse so stack pop order visits them left-to-right.
            stack.addAll(regexp.children.reversed());
        }

        return (namedCaptures == null) ? Map.of() : Map.copyOf(namedCaptures);
    }

    /**
     * Returns capturing group names keyed by capture number.
     */
    Map<Integer, String> captureNames()
    {
        HashMap<Integer, String> captureNames = null;
        ArrayDeque<Regexp> stack = new ArrayDeque<>();
        stack.addLast(this);

        while (!stack.isEmpty()) {
            Regexp regexp = stack.removeLast();
            if (regexp.op == RegexpOp.CAPTURE && regexp.name != null) {
                if (captureNames == null) {
                    captureNames = new HashMap<>();
                }
                captureNames.put(regexp.captureIndex, regexp.name.toStringUtf8());
            }

            // Push children in reverse so stack pop order visits them left-to-right.
            stack.addAll(regexp.children.reversed());
        }

        return (captureNames == null) ? Map.of() : Map.copyOf(captureNames);
    }

    static byte[] convertRunesToBytes(boolean latin1, int[] runes)
    {
        if (latin1) {
            byte[] bytes = new byte[runes.length];
            for (int runeIndex = 0; runeIndex < runes.length; runeIndex++) {
                bytes[runeIndex] = (byte) runes[runeIndex];
            }
            return bytes;
        }

        // UTF-8: allocate worst-case and then shrink.
        byte[] encodedBytes = new byte[runes.length * 4];
        int end = 0;
        for (int rune : runes) {
            end = Utf8.encode(encodedBytes, end, rune);
        }
        if (end == encodedBytes.length) {
            return encodedBytes;
        }
        byte[] bytes = new byte[end];
        System.arraycopy(encodedBytes, 0, bytes, 0, end);
        return bytes;
    }

    int childCount()
    {
        return children.size();
    }

    Regexp child(int childIndex)
    {
        return children.get(childIndex);
    }

    List<Regexp> children()
    {
        return children;
    }

    int rune()
    {
        if (op != RegexpOp.LITERAL) {
            throw new IllegalStateException("not a literal");
        }
        return rune;
    }

    int[] runes()
    {
        if (op != RegexpOp.LITERAL_STRING) {
            throw new IllegalStateException("not a literal string");
        }
        return runes;
    }

    CharClass charClass()
    {
        if (op != RegexpOp.CHAR_CLASS) {
            throw new IllegalStateException("not a char class");
        }
        return charClass;
    }

    int captureIndex()
    {
        if (op != RegexpOp.CAPTURE) {
            throw new IllegalStateException("not a capture");
        }
        return captureIndex;
    }

    Slice name()
    {
        if (op != RegexpOp.CAPTURE) {
            throw new IllegalStateException("not a capture");
        }
        return name;
    }

    int min()
    {
        if (op != RegexpOp.REPEAT) {
            throw new IllegalStateException("not a repeat");
        }
        return min;
    }

    int max()
    {
        if (op != RegexpOp.REPEAT) {
            throw new IllegalStateException("not a repeat");
        }
        return max;
    }

    int matchId()
    {
        if (op != RegexpOp.HAVE_MATCH) {
            throw new IllegalStateException("not have-match");
        }
        return matchId;
    }

    static Regexp noMatch(int parseFlags)
    {
        return new Regexp(RegexpOp.NO_MATCH, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp emptyMatch(int parseFlags)
    {
        return new Regexp(RegexpOp.EMPTY_MATCH, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp anyChar(int parseFlags)
    {
        return new Regexp(RegexpOp.ANY_CHAR, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp anyByte(int parseFlags)
    {
        return new Regexp(RegexpOp.ANY_BYTE, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp beginLine(int parseFlags)
    {
        return new Regexp(RegexpOp.BEGIN_LINE, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp endLine(int parseFlags)
    {
        return new Regexp(RegexpOp.END_LINE, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp wordBoundary(int parseFlags)
    {
        return new Regexp(RegexpOp.WORD_BOUNDARY, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp noWordBoundary(int parseFlags)
    {
        return new Regexp(RegexpOp.NO_WORD_BOUNDARY, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp beginText(int parseFlags)
    {
        return new Regexp(RegexpOp.BEGIN_TEXT, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp endText(int parseFlags)
    {
        return new Regexp(RegexpOp.END_TEXT, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp literal(int parseFlags, int rune)
    {
        return new Regexp(RegexpOp.LITERAL, parseFlags, List.of(), rune, null, null, 0, null, 0, 0, 0);
    }

    static Regexp literalString(int parseFlags, int[] runes)
    {
        requireNonNull(runes, "runes is null");
        return new Regexp(RegexpOp.LITERAL_STRING, parseFlags, List.of(), 0, runes.clone(), null, 0, null, 0, 0, 0);
    }

    static Regexp charClass(int parseFlags, CharClass charClass)
    {
        return new Regexp(RegexpOp.CHAR_CLASS, parseFlags, List.of(), 0, null, requireNonNull(charClass, "charClass is null"), 0, null, 0, 0, 0);
    }

    static Regexp capture(int parseFlags, Regexp child, int captureIndex, Slice name)
    {
        requireNonNull(child, "child is null");
        if (captureIndex <= 0) {
            throw new IllegalArgumentException("captureIndex must be > 0");
        }
        return new Regexp(RegexpOp.CAPTURE, parseFlags, List.of(child), 0, null, null, captureIndex, name, 0, 0, 0);
    }

    static Regexp star(int parseFlags, Regexp child)
    {
        requireNonNull(child, "child is null");
        return starPlusOrQuest(RegexpOp.STAR, parseFlags, child);
    }

    static Regexp plus(int parseFlags, Regexp child)
    {
        requireNonNull(child, "child is null");
        return starPlusOrQuest(RegexpOp.PLUS, parseFlags, child);
    }

    static Regexp quest(int parseFlags, Regexp child)
    {
        requireNonNull(child, "child is null");
        return starPlusOrQuest(RegexpOp.QUEST, parseFlags, child);
    }

    /**
     * Constructs a unary node without applying {@link #starPlusOrQuest} squashing rules.
     * <p>
     * This is needed for fidelity with upstream {@code simplify.cc}, which builds unary
     * regexps directly (instead of calling {@code Regexp::Star/Plus/Quest}) so that
     * simplification preserves forms like {@code (?:a+)*} rather than rewriting them to {@code a*}.
     */
    static Regexp rawUnary(RegexpOp operator, int parseFlags, Regexp child)
    {
        requireNonNull(operator, "operator is null");
        requireNonNull(child, "child is null");
        if (operator != RegexpOp.STAR && operator != RegexpOp.PLUS && operator != RegexpOp.QUEST) {
            throw new IllegalArgumentException("operator must be STAR/PLUS/QUEST");
        }
        return new Regexp(operator, parseFlags, List.of(child), 0, null, null, 0, null, 0, 0, 0);
    }

    private static Regexp starPlusOrQuest(RegexpOp operator, int parseFlags, Regexp child)
    {
        // Squash **, ++ and ??.
        if (operator == child.op() && parseFlags == child.parseFlags()) {
            return child;
        }

        // Squash *+, *?, +*, +?, ?* and ?+. They all squash to *.
        if ((child.op() == RegexpOp.STAR || child.op() == RegexpOp.PLUS || child.op() == RegexpOp.QUEST) &&
                parseFlags == child.parseFlags()) {
            if (child.op() == RegexpOp.STAR) {
                return child;
            }

            // Rewrite child to STAR.
            return new Regexp(RegexpOp.STAR, parseFlags, List.of(child.child(0)), 0, null, null, 0, null, 0, 0, 0);
        }

        return new Regexp(operator, parseFlags, List.of(child), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp repeat(int parseFlags, Regexp child, int min, int max)
    {
        requireNonNull(child, "child is null");
        if (min < 0) {
            throw new IllegalArgumentException("min must be >= 0");
        }
        if (max != -1 && max < min) {
            throw new IllegalArgumentException("max must be -1 or >= min");
        }
        return new Regexp(RegexpOp.REPEAT, parseFlags, List.of(child), 0, null, null, 0, null, min, max, 0);
    }

    static Regexp concat(int parseFlags, List<Regexp> children)
    {
        requireNonNull(children, "children is null");
        if (children.size() < 2) {
            throw new IllegalArgumentException("concat requires >= 2 children");
        }
        return new Regexp(RegexpOp.CONCAT, parseFlags, children, 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp alternate(int parseFlags, List<Regexp> children)
    {
        requireNonNull(children, "children is null");
        if (children.size() < 2) {
            throw new IllegalArgumentException("alternate requires >= 2 children");
        }
        return new Regexp(RegexpOp.ALTERNATE, parseFlags, children, 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp haveMatch(int parseFlags, int matchId)
    {
        if (matchId < 0) {
            throw new IllegalArgumentException("matchId must be >= 0");
        }
        return new Regexp(RegexpOp.HAVE_MATCH, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, matchId);
    }

    static final int MAX_REPEAT = 1000;

    static int maxRune(int parseFlags)
    {
        return (parseFlags & LATIN1) == 0 ? RUNEMAX : 0xFF;
    }

    @Override
    public boolean equals(Object other)
    {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Regexp right)) {
            return false;
        }
        if (op != right.op) {
            return false;
        }
        if (equalityFlags() != right.equalityFlags()) {
            return false;
        }

        return switch (op) {
            case NO_MATCH, EMPTY_MATCH, ANY_CHAR, ANY_BYTE, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT -> true;
            case LITERAL -> rune == right.rune;
            case LITERAL_STRING -> Arrays.equals(runes, right.runes);
            case CHAR_CLASS -> Objects.equals(charClass, right.charClass);
            case CAPTURE -> captureIndex == right.captureIndex && Objects.equals(name, right.name) && Objects.equals(child(0), right.child(0));
            case REPEAT -> min == right.min && max == right.max && Objects.equals(child(0), right.child(0));
            case HAVE_MATCH -> matchId == right.matchId;
            case STAR, PLUS, QUEST -> Objects.equals(child(0), right.child(0));
            case CONCAT, ALTERNATE -> children.equals(right.children);
        };
    }

    @Override
    public int hashCode()
    {
        int result = 31 * op.hashCode() + equalityFlags();

        return switch (op) {
            case NO_MATCH, EMPTY_MATCH, ANY_CHAR, ANY_BYTE, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT -> result;
            case LITERAL -> 31 * result + rune;
            case LITERAL_STRING -> 31 * result + Arrays.hashCode(runes);
            case CHAR_CLASS -> 31 * result + Objects.hashCode(charClass);
            case CAPTURE -> {
                result = 31 * result + captureIndex;
                result = 31 * result + Objects.hashCode(name);
                yield 31 * result + Objects.hashCode(child(0));
            }
            case REPEAT -> {
                result = 31 * result + min;
                result = 31 * result + max;
                yield 31 * result + Objects.hashCode(child(0));
            }
            case HAVE_MATCH -> 31 * result + matchId;
            case STAR, PLUS, QUEST -> 31 * result + Objects.hashCode(child(0));
            case CONCAT, ALTERNATE -> 31 * result + children.hashCode();
        };
    }

    private int equalityFlags()
    {
        return switch (op) {
            case BEGIN_LINE -> (parseFlags & JAVA_LINE) == 0 ? 0 : parseFlags & (JAVA_LINE | JAVA_UNIX_LINES);
            case END_LINE -> parseFlags & JAVA_LINE;
            case END_TEXT -> parseFlags & (WAS_DOLLAR | FINAL_LINE_END | JAVA_FINAL_END);
            case WORD_BOUNDARY, NO_WORD_BOUNDARY -> parseFlags &
                    (UNICODE_WORD_BOUNDARY | JAVA_WORD_BOUNDARY | JAVA_UNICODE_CHARACTER_CLASS);
            case LITERAL, LITERAL_STRING -> parseFlags & (FOLD_CASE | FULL_CASE_FOLD | LATIN1);
            case STAR, PLUS, QUEST, REPEAT -> parseFlags & NON_GREEDY;
            default -> 0;
        };
    }
}
