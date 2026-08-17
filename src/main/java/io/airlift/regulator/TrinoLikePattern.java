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

import io.airlift.regulator.TrinoLikeParser.Any;
import io.airlift.regulator.TrinoLikeParser.Element;
import io.airlift.regulator.TrinoLikeParser.Literal;
import io.airlift.regulator.TrinoLikeParser.ZeroOrMore;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static java.util.Objects.requireNonNull;

/**
 * A compiled Trino SQL LIKE pattern over UTF-8 bytes.
 * <p>
 * The pattern syntax and malformed UTF-8 behavior match Trino's SQL execution path. This is
 * intentionally a separate frontend from {@link Re2}; LIKE is not treated as a regex dialect.
 */
public final class TrinoLikePattern
{
    private static final byte EMPTY = 0;
    private static final byte EQUALS = 1;
    private static final byte STARTS_WITH = 2;
    private static final byte ENDS_WITH = 3;
    private static final byte CONTAINS = 4;
    private static final byte ORDERED_LITERALS = 5;
    private static final byte TRINO_WILDCARD = 6;
    private static final byte LITERAL_GAPS = 7;
    private static final byte SHORT_EQUALS = 8;
    private static final byte SHORT_STARTS_WITH = 9;
    private static final byte SHORT_ENDS_WITH = 10;

    enum Plan
    {
        EMPTY,
        EQUALS,
        STARTS_WITH,
        ENDS_WITH,
        CONTAINS,
        ORDERED_LITERALS,
        TRINO_WILDCARD,
        LITERAL_GAPS,
    }

    private final int minimumSize;
    private final int maximumSize;
    private final Slice prefix;
    private final Slice suffix;
    private final Matcher matcher;
    private final byte strategy;
    private final LiteralMatchKernel.ShortLiteral shortLiteral;

    private TrinoLikePattern(
            int minimumSize,
            int maximumSize,
            Slice prefix,
            Slice suffix,
            Matcher matcher,
            byte strategy)
    {
        this.minimumSize = minimumSize;
        this.maximumSize = maximumSize;
        this.prefix = requireNonNull(prefix, "prefix is null");
        this.suffix = requireNonNull(suffix, "suffix is null");
        this.matcher = matcher;
        Slice literal = strategy == ENDS_WITH ? suffix : prefix;
        if ((strategy == EQUALS || strategy == STARTS_WITH || strategy == ENDS_WITH) && literal.length() > 0 && literal.length() <= 16) {
            shortLiteral = new LiteralMatchKernel.ShortLiteral(literal);
            this.strategy = switch (strategy) {
                case EQUALS -> SHORT_EQUALS;
                case STARTS_WITH -> SHORT_STARTS_WITH;
                case ENDS_WITH -> SHORT_ENDS_WITH;
                default -> throw new IllegalArgumentException("not a literal strategy");
            };
        }
        else {
            shortLiteral = null;
            this.strategy = strategy;
        }
    }

    public static TrinoLikePattern compile(Slice pattern)
    {
        return compile(pattern, OptionalInt.empty());
    }

    public static TrinoLikePattern compile(Slice pattern, int escapeCodePoint)
    {
        return compile(pattern, OptionalInt.of(escapeCodePoint));
    }

    private static TrinoLikePattern compile(Slice pattern, OptionalInt escapeCodePoint)
    {
        requireNonNull(pattern, "pattern is null");
        if (escapeCodePoint.isEmpty()) {
            TrinoLikePattern simplePattern = tryCompileSimpleAscii(pattern);
            if (simplePattern != null) {
                return simplePattern;
            }
        }

        List<Element> elements = TrinoLikeParser.parse(pattern, escapeCodePoint);
        int minimumSize = 0;
        int maximumSize = 0;
        boolean unbounded = false;
        boolean hasAny = false;
        for (Element element : elements) {
            switch (element) {
                case Literal literal -> {
                    minimumSize += literal.bytes().length();
                    maximumSize += literal.bytes().length();
                }
                case Any any -> {
                    hasAny = true;
                    minimumSize += any.count();
                    maximumSize += any.count() * 4;
                }
                case ZeroOrMore _ -> unbounded = true;
            }
        }

        Slice prefix = Slices.EMPTY_SLICE;
        Slice suffix = Slices.EMPTY_SLICE;
        int patternStart = 0;
        int patternEnd = elements.size() - 1;
        if (!elements.isEmpty() && elements.getFirst() instanceof Literal literal) {
            prefix = literal.bytes();
            patternStart++;
        }
        if (elements.size() > 1 && elements.getLast() instanceof Literal literal) {
            suffix = literal.bytes();
            patternEnd--;
        }

        boolean exact = true;
        if (patternStart <= patternEnd && elements.get(patternEnd) == ZeroOrMore.INSTANCE) {
            exact = false;
            patternEnd--;
        }

        Matcher matcher = null;
        Plan plan = simplePlan(elements, prefix, suffix);
        if (patternStart <= patternEnd) {
            if (hasAny) {
                LiteralGapMatcher literalGapMatcher = LiteralGapMatcher.analyze(elements, patternStart, patternEnd, exact);
                if (literalGapMatcher != null) {
                    matcher = new LiteralGapPlanMatcher(literalGapMatcher);
                    plan = Plan.LITERAL_GAPS;
                }
                else {
                    matcher = new WildcardMatcher(new TrinoLikeWildcardMatcher(elements, patternStart, patternEnd, exact));
                    plan = Plan.TRINO_WILDCARD;
                }
            }
            else {
                List<Slice> literals = new ArrayList<>();
                for (int elementIndex = patternStart; elementIndex <= patternEnd; elementIndex++) {
                    if (elements.get(elementIndex) instanceof Literal literal) {
                        literals.add(literal.bytes());
                    }
                }
                if (!literals.isEmpty()) {
                    Slice[] literalArray = literals.toArray(Slice[]::new);
                    matcher = literals.size() == 1
                            ? new ContainsMatcher(literalArray[0], exact)
                            : new OrderedMatcher(new OrderedLiteralMatcher(literalArray, exact));
                    plan = literals.size() == 1 ? Plan.CONTAINS : Plan.ORDERED_LITERALS;
                }
            }
        }
        return new TrinoLikePattern(
                minimumSize,
                unbounded ? -1 : maximumSize,
                prefix,
                suffix,
                matcher,
                encode(plan));
    }

    private static TrinoLikePattern tryCompileSimpleAscii(Slice pattern)
    {
        int patternLength = pattern.length();
        if (patternLength == 0) {
            return null;
        }

        byte[] bytes = pattern.byteArray();
        int offset = pattern.byteArrayOffset();
        boolean leadingWildcard = bytes[offset] == '%';
        boolean trailingWildcard = bytes[offset + patternLength - 1] == '%';
        int literalStart = leadingWildcard ? 1 : 0;
        int literalEnd = trailingWildcard ? patternLength - 1 : patternLength;
        if (literalStart >= literalEnd) {
            return null;
        }

        for (int index = 0; index < patternLength; index++) {
            byte value = bytes[offset + index];
            if (value < 0 || value == '_' || (value == '%' && index != 0 && index != patternLength - 1)) {
                return null;
            }
        }

        Slice literal = pattern.copy(literalStart, literalEnd - literalStart);
        if (!leadingWildcard && !trailingWildcard) {
            return new TrinoLikePattern(
                    literal.length(),
                    literal.length(),
                    literal,
                    Slices.EMPTY_SLICE,
                    null,
                    EQUALS);
        }
        if (!leadingWildcard) {
            return new TrinoLikePattern(
                    literal.length(),
                    -1,
                    literal,
                    Slices.EMPTY_SLICE,
                    null,
                    STARTS_WITH);
        }
        if (!trailingWildcard) {
            return new TrinoLikePattern(
                    literal.length(),
                    -1,
                    Slices.EMPTY_SLICE,
                    literal,
                    null,
                    ENDS_WITH);
        }
        return new TrinoLikePattern(
                literal.length(),
                -1,
                Slices.EMPTY_SLICE,
                Slices.EMPTY_SLICE,
                new ContainsMatcher(literal, false),
                CONTAINS);
    }

    public boolean matches(Slice input)
    {
        requireNonNull(input, "input is null");
        int length = input.length();
        if (length < minimumSize || (maximumSize >= 0 && length > maximumSize)) {
            return false;
        }
        if (shortLiteral != null) {
            return shortLiteral.matchesAt(input, strategy == SHORT_ENDS_WITH ? length - suffix.length() : 0);
        }
        return switch (strategy) {
            case EMPTY -> true;
            case EQUALS, STARTS_WITH -> LiteralMatchKernel.matchesAt(input, 0, prefix);
            case ENDS_WITH -> LiteralMatchKernel.matchesAt(input, length - suffix.length(), suffix);
            case CONTAINS, ORDERED_LITERALS, TRINO_WILDCARD, LITERAL_GAPS -> matchesComplex(input, length);
            default -> throw new IllegalStateException("unknown Trino LIKE strategy: " + strategy);
        };
    }

    private boolean matchesComplex(Slice input, int length)
    {
        if (!startsWith(input, 0, prefix) || !startsWith(input, length - suffix.length(), suffix)) {
            return false;
        }
        if (matcher == null) {
            return true;
        }
        return matcher.matches(input, prefix.length(), length - prefix.length() - suffix.length());
    }

    Plan planForDiagnostics()
    {
        return decode(strategy);
    }

    boolean usesShortLiteralForDiagnostics()
    {
        return shortLiteral != null;
    }

    boolean usesSharedOrderedLiteralSearchForDiagnostics()
    {
        return matcher instanceof OrderedMatcher orderedMatcher && orderedMatcher.matcher().usesSharedLiteralSearchForDiagnostics();
    }

    boolean usesSharedLiteralGapSearchForDiagnostics()
    {
        return matcher instanceof LiteralGapPlanMatcher;
    }

    static boolean usesSimpleAsciiConstructionForDiagnostics(Slice pattern)
    {
        requireNonNull(pattern, "pattern is null");
        return tryCompileSimpleAscii(pattern) != null;
    }

    private static Plan simplePlan(List<Element> elements, Slice prefix, Slice suffix)
    {
        if (elements.isEmpty()) {
            return Plan.EMPTY;
        }
        if (elements.size() == 1 && elements.getFirst() instanceof Literal) {
            return Plan.EQUALS;
        }
        if (prefix.length() != 0 && suffix.length() == 0) {
            return Plan.STARTS_WITH;
        }
        if (prefix.length() == 0 && suffix.length() != 0) {
            return Plan.ENDS_WITH;
        }
        if (prefix.length() != 0) {
            return Plan.ORDERED_LITERALS;
        }
        return Plan.CONTAINS;
    }

    private static boolean startsWith(Slice input, int offset, Slice literal)
    {
        return literal.length() == 0 || LiteralMatchKernel.matchesAt(input, offset, literal);
    }

    private static byte encode(Plan plan)
    {
        return switch (plan) {
            case EMPTY -> EMPTY;
            case EQUALS -> EQUALS;
            case STARTS_WITH -> STARTS_WITH;
            case ENDS_WITH -> ENDS_WITH;
            case CONTAINS -> CONTAINS;
            case ORDERED_LITERALS -> ORDERED_LITERALS;
            case TRINO_WILDCARD -> TRINO_WILDCARD;
            case LITERAL_GAPS -> LITERAL_GAPS;
        };
    }

    private static Plan decode(byte strategy)
    {
        return switch (strategy) {
            case EMPTY -> Plan.EMPTY;
            case EQUALS, SHORT_EQUALS -> Plan.EQUALS;
            case STARTS_WITH, SHORT_STARTS_WITH -> Plan.STARTS_WITH;
            case ENDS_WITH, SHORT_ENDS_WITH -> Plan.ENDS_WITH;
            case CONTAINS -> Plan.CONTAINS;
            case ORDERED_LITERALS -> Plan.ORDERED_LITERALS;
            case TRINO_WILDCARD -> Plan.TRINO_WILDCARD;
            case LITERAL_GAPS -> Plan.LITERAL_GAPS;
            default -> throw new IllegalArgumentException("unknown Trino LIKE strategy: " + strategy);
        };
    }

    private interface Matcher
    {
        boolean matches(Slice input, int offset, int length);
    }

    private record WildcardMatcher(TrinoLikeWildcardMatcher matcher)
            implements Matcher
    {
        @Override
        public boolean matches(Slice input, int offset, int length)
        {
            return matcher.matches(input, offset, length);
        }
    }

    private record OrderedMatcher(OrderedLiteralMatcher matcher)
            implements Matcher
    {
        @Override
        public boolean matches(Slice input, int offset, int length)
        {
            return matcher.matches(input, offset, length);
        }
    }

    private record LiteralGapPlanMatcher(LiteralGapMatcher matcher)
            implements Matcher
    {
        @Override
        public boolean matches(Slice input, int offset, int length)
        {
            return matcher.matches(input, offset, length);
        }
    }

    private static final class ContainsMatcher
            implements Matcher
    {
        private final Slice literal;
        private final boolean exact;

        private ContainsMatcher(Slice literal, boolean exact)
        {
            this.literal = requireNonNull(literal, "literal is null");
            this.exact = exact;
        }

        @Override
        public boolean matches(Slice input, int offset, int length)
        {
            int end = offset + length;
            if (offset == end) {
                return false;
            }
            int matchOffset = LiteralMatchKernel.find(input, literal, offset);
            if (matchOffset < 0 || matchOffset + literal.length() > end) {
                return false;
            }
            return !exact || matchOffset + literal.length() == end;
        }
    }
}
