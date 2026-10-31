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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.airlift.regulator.TrinoLikeParser.Any;
import io.airlift.regulator.TrinoLikeParser.Element;
import io.airlift.regulator.TrinoLikeParser.Literal;
import io.airlift.regulator.TrinoLikeParser.ZeroOrMore;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static java.util.Objects.requireNonNull;

/// A compiled Trino SQL LIKE pattern over UTF-8 bytes.
///
/// The pattern syntax and matching on valid UTF-8 follow Trino's SQL execution path, with one
/// exception: a supplementary literal always matches itself, so `%😀_%` matches
/// `😀x` here but not in Trino's SQL path. A pattern that is not valid UTF-8 throws
/// [TrinoLikePatternSyntaxException], while Trino accepts it. Malformed input matches as if
/// each maximal invalid subpart had been replaced by U+FFFD, so it is one character that only
/// `_` or `%` consumes. This is intentionally a separate frontend from [Re2];
/// LIKE is not treated as a regex dialect.
public sealed class TrinoLikePattern
        permits TrinoLikePattern.CompiledTrinoLikePattern
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
    private static final Slice PERCENT = Slices.utf8Slice("%");
    // Whether this JVM runs on aarch64. Generated LIKE picks how its literal searches hold their
    // constants by architecture, from the AWS measurements recorded in REGULATOR_DECISIONS.md.
    static final boolean AARCH64 = "aarch64".equals(System.getProperty("os.arch"));

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

    /// Mutable compilation options. Compilation snapshots every option, so later changes do not
    /// affect an existing [TrinoLikePattern]. Options instances are not thread-safe.
    public static final class Options
    {
        private OptionalInt escape = OptionalInt.empty();
        private boolean generateBytecode;
        private boolean aarch64 = AARCH64;

        private Options() {}

        public static Options defaults()
        {
            return new Options();
        }

        OptionalInt escape()
        {
            return escape;
        }

        /// Sets the escape character, which makes the `%`, `_`, or escape character after it
        /// literal. The escape must be a Unicode scalar value, a code point other than a
        /// surrogate, because no valid UTF-8 pattern can contain a surrogate. Only `%`, `_`, or
        /// the escape itself may follow the escape in a pattern; otherwise compile throws a
        /// [TrinoLikePatternSyntaxException]. A pattern has no escape character by default.
        public Options setEscape(int escapeCodePoint)
        {
            if (!Character.isValidCodePoint(escapeCodePoint) || (escapeCodePoint >= Character.MIN_SURROGATE && escapeCodePoint <= Character.MAX_SURROGATE)) {
                throw new IllegalArgumentException("escapeCodePoint must be a Unicode scalar value: " + escapeCodePoint);
            }
            escape = OptionalInt.of(escapeCodePoint);
            return this;
        }

        /// Removes the escape character, so `%` and `_` are always wildcards.
        public Options clearEscape()
        {
            escape = OptionalInt.empty();
            return this;
        }

        boolean generateBytecode()
        {
            return generateBytecode;
        }

        /// Experimental. When enabled, compilation generates a hidden class for the pattern, so
        /// [TrinoLikePattern#matches(Slice)] runs dedicated bytecode instead of the
        /// interpreted plan. A pattern whose class cannot be generated compiles as it does without
        /// this option. Disabled by default.
        ///
        /// @see TrinoLikePattern#usesGeneratedBytecode()
        public Options setGenerateBytecode(boolean generateBytecode)
        {
            this.generateBytecode = generateBytecode;
            return this;
        }

        boolean aarch64()
        {
            return aarch64;
        }

        /// Generates bytecode as on aarch64 when `aarch64` is true and as on every other
        /// architecture when it is false, whatever this JVM runs on; it takes effect only with
        /// [#setGenerateBytecode(boolean)], and only tests call it.
        Options setAarch64ForTesting(boolean aarch64)
        {
            this.aarch64 = aarch64;
            return this;
        }
    }

    // The private copy of the source, or null for a simple ASCII pattern, whose source
    // pattern() rebuilds from its literal and strategy.
    private final Slice pattern;
    private final int minimumSize;
    private final int maximumSize;
    private final Slice prefix;
    private final Slice suffix;
    private final Matcher matcher;
    private final byte strategy;
    // For a literal strategy's literal of at most 16 bytes, shortLiteralWidth is its word width,
    // and 0 otherwise, shortLiteralLastOffset is where its last word starts, and shortLiteralFirst
    // and shortLiteralLast are its first and last words; see LiteralMatchKernel.matchesShortLiteralAt.
    private final int shortLiteralWidth;
    private final int shortLiteralLastOffset;
    private final long shortLiteralFirst;
    private final long shortLiteralLast;

    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "The sealed hierarchy permits only a private final nested implementation")
    private TrinoLikePattern(
            Slice pattern,
            int minimumSize,
            int maximumSize,
            Slice prefix,
            Slice suffix,
            Matcher matcher,
            byte strategy)
    {
        this.pattern = pattern;
        this.minimumSize = minimumSize;
        this.maximumSize = maximumSize;
        this.prefix = requireNonNull(prefix, "prefix is null");
        this.suffix = requireNonNull(suffix, "suffix is null");
        this.matcher = matcher;
        Slice literal = strategy == ENDS_WITH ? suffix : prefix;
        if ((strategy == EQUALS || strategy == STARTS_WITH || strategy == ENDS_WITH) && literal.length() > 0 && literal.length() <= 16) {
            int width = LiteralMatchKernel.shortLiteralWidth(literal.length());
            shortLiteralWidth = width;
            shortLiteralLastOffset = literal.length() - width;
            shortLiteralFirst = LiteralMatchKernel.readShortLiteralWord(literal, 0, width);
            shortLiteralLast = LiteralMatchKernel.readShortLiteralWord(literal, shortLiteralLastOffset, width);
            this.strategy = switch (strategy) {
                case EQUALS -> SHORT_EQUALS;
                case STARTS_WITH -> SHORT_STARTS_WITH;
                case ENDS_WITH -> SHORT_ENDS_WITH;
                default -> throw new IllegalArgumentException("not a literal strategy");
            };
        }
        else {
            shortLiteralWidth = 0;
            shortLiteralLastOffset = 0;
            shortLiteralFirst = 0;
            shortLiteralLast = 0;
            this.strategy = strategy;
        }
    }

    /// Compiles a copied snapshot of `pattern` with [Options#defaults()].
    public static TrinoLikePattern compile(Slice pattern)
    {
        return compile(pattern, OptionalInt.empty());
    }

    /// Compiles a copied snapshot of `pattern` and the current option values.
    public static TrinoLikePattern compile(Slice pattern, Options options)
    {
        return compileWithOptions(pattern, options);
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

        Slice patternCopy = pattern.copy();
        List<Element> elements = TrinoLikeParser.parse(patternCopy, escapeCodePoint);
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
                patternCopy,
                minimumSize,
                unbounded ? -1 : maximumSize,
                prefix,
                suffix,
                matcher,
                encode(plan));
    }

    // A compiled result retains a copy of the literal alone, as pattern() rebuilds the rest.
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
        Slice prefix = Slices.EMPTY_SLICE;
        Slice suffix = Slices.EMPTY_SLICE;
        Matcher matcher = null;
        byte strategy;
        if (!leadingWildcard) {
            prefix = literal;
            strategy = trailingWildcard ? STARTS_WITH : EQUALS;
        }
        else if (!trailingWildcard) {
            suffix = literal;
            strategy = ENDS_WITH;
        }
        else {
            matcher = new ContainsMatcher(literal, false);
            strategy = CONTAINS;
        }
        return new TrinoLikePattern(null, literal.length(), strategy == EQUALS ? literal.length() : -1, prefix, suffix, matcher, strategy);
    }

    // TrinoLikeKernelGenerator emits a copy of this method, with matchesComplex, as the entry of a
    // generated pattern; keep the copy in step.
    public boolean matches(Slice input)
    {
        requireNonNull(input, "input is null");
        int length = input.length();
        if (length < minimumSize || (maximumSize >= 0 && length > maximumSize)) {
            return false;
        }
        if (shortLiteralWidth != 0) {
            return LiteralMatchKernel.matchesShortLiteralAt(
                    input,
                    strategy == SHORT_ENDS_WITH ? length - suffix.length() : 0,
                    shortLiteralWidth,
                    shortLiteralLastOffset,
                    shortLiteralFirst,
                    shortLiteralLast);
        }
        return switch (strategy) {
            case EMPTY -> true;
            case EQUALS, STARTS_WITH -> LiteralMatchKernel.matchesAt(input, 0, prefix);
            case ENDS_WITH -> LiteralMatchKernel.matchesAt(input, length - suffix.length(), suffix);
            case CONTAINS, ORDERED_LITERALS, TRINO_WILDCARD, LITERAL_GAPS -> matchesComplex(input, length);
            default -> throw new IllegalStateException("unknown Trino LIKE strategy: " + strategy);
        };
    }

    // TrinoLikeKernelGenerator emits a copy of this method in the entry of a generated pattern;
    // keep the copy in step.
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

    // Declared after matches because javac numbers constants in declaration order: declared
    // before it, this method's constants push a constant of matches out of the one-byte ldc
    // range, which grows matches.
    private static TrinoLikePattern compileWithOptions(Slice pattern, Options options)
    {
        requireNonNull(options, "options is null");
        TrinoLikePattern compiled = compile(pattern, options.escape());
        if (options.generateBytecode()) {
            TrinoLikePattern generated = CompiledTrinoLikePattern.create(compiled, options.aarch64());
            if (generated != null) {
                return generated;
            }
        }
        return compiled;
    }

    // Declared after matches for the same reason.
    private TrinoLikePattern(TrinoLikePattern pattern)
    {
        this.pattern = pattern.pattern;
        this.minimumSize = pattern.minimumSize;
        this.maximumSize = pattern.maximumSize;
        this.prefix = pattern.prefix;
        this.suffix = pattern.suffix;
        this.matcher = pattern.matcher;
        this.strategy = pattern.strategy;
        this.shortLiteralWidth = pattern.shortLiteralWidth;
        this.shortLiteralLastOffset = pattern.shortLiteralLastOffset;
        this.shortLiteralFirst = pattern.shortLiteralFirst;
        this.shortLiteralLast = pattern.shortLiteralLast;
    }

    /// Returns a copy of the compiled pattern bytes.
    public Slice pattern()
    {
        if (pattern != null) {
            return pattern.copy();
        }
        // A simple ASCII pattern is its literal with the wildcards its strategy implies.
        return switch (strategy) {
            case EQUALS, SHORT_EQUALS -> prefix.copy();
            case STARTS_WITH, SHORT_STARTS_WITH -> concat(Slices.EMPTY_SLICE, prefix, PERCENT);
            case ENDS_WITH, SHORT_ENDS_WITH -> concat(PERCENT, suffix, Slices.EMPTY_SLICE);
            case CONTAINS -> concat(PERCENT, ((ContainsMatcher) matcher).literal, PERCENT);
            default -> throw new IllegalStateException("not a simple ASCII strategy: " + strategy);
        };
    }

    private static Slice concat(Slice first, Slice second, Slice third)
    {
        Slice result = Slices.allocate(first.length() + second.length() + third.length());
        result.setBytes(0, first);
        result.setBytes(first.length(), second);
        result.setBytes(first.length() + second.length(), third);
        return result;
    }

    /// Returns whether this compiled pattern has a class generated for it, which only
    /// [Options#setGenerateBytecode(boolean)] enables. [#matches(Slice)] of such a
    /// pattern runs the generated class, and [#generatedMatches()] returns its entry.
    public boolean usesGeneratedBytecode()
    {
        return false;
    }

    /// Returns a handle on the generated code of [#matches(Slice)], of type
    /// `(Slice)boolean`, or empty when this pattern is interpreted. The handle checks its
    /// argument as [#matches(Slice)] does, except that it requires a non-null input but does
    /// not check it.
    public Optional<MethodHandle> generatedMatches()
    {
        return Optional.empty();
    }

    TrinoLikeKernelGenerator.Kernel kernelForDiagnostics()
    {
        return CompiledTrinoLikePattern.kernel(this);
    }

    Plan planForDiagnostics()
    {
        return decode(strategy);
    }

    boolean usesShortLiteralForDiagnostics()
    {
        return shortLiteralWidth != 0;
    }

    boolean usesSharedOrderedLiteralSearchForDiagnostics()
    {
        return matcher instanceof OrderedMatcher orderedMatcher && orderedMatcher.matcher().usesSharedLiteralSearchForDiagnostics();
    }

    boolean usesSharedLiteralGapSearchForDiagnostics()
    {
        return matcher instanceof LiteralGapPlanMatcher;
    }

    boolean usesContainsSearchForDiagnostics()
    {
        return matcher instanceof ContainsMatcher contains && contains.search != null;
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
        // Scans a literal of two or more bytes for its first and last bytes together; a one-byte
        // literal searches with Slice.indexOf.
        private final LiteralSearchKernel search;
        private final boolean exact;

        private ContainsMatcher(Slice literal, boolean exact)
        {
            this.literal = requireNonNull(literal, "literal is null");
            this.search = literal.length() > 1 ? new LiteralSearchKernel(literal) : null;
            this.exact = exact;
        }

        @Override
        public boolean matches(Slice input, int offset, int length)
        {
            int end = offset + length;
            if (offset == end) {
                return false;
            }
            int matchOffset = search == null ? LiteralMatchKernel.find(input, literal, offset) : search.findContains(input, offset, length);
            if (matchOffset < 0 || matchOffset + literal.length() > end) {
                return false;
            }
            return !exact || matchOffset + literal.length() == end;
        }
    }

    /// A pattern compiled to its own hidden class, which [#matches(Slice)] calls instead of
    /// the interpreted plan. Callers that never enable [Options#setGenerateBytecode(boolean)]
    /// never load this class.
    private static final class CompiledTrinoLikePattern
            extends TrinoLikePattern
    {
        private final TrinoLikeKernelGenerator.CompiledLike generated;
        private final CompiledTrinoLike like;

        private CompiledTrinoLikePattern(TrinoLikePattern pattern, TrinoLikeKernelGenerator.CompiledLike generated)
        {
            super(pattern);
            this.generated = generated;
            this.like = generated.like();
        }

        /// Returns `pattern` with a generated class, generated as on aarch64 when
        /// `aarch64` is true, or `null` when the class cannot be generated or a method
        /// of it would be too large for HotSpot to compile. The declared type keeps verification
        /// of the base class from loading this class.
        static TrinoLikePattern create(TrinoLikePattern pattern, boolean aarch64)
        {
            TrinoLikeKernelGenerator.CompiledLike generated;
            try {
                generated = TrinoLikeKernelGenerator.generate(kernel(pattern), aarch64);
            }
            catch (RuntimeException | LinkageError ignored) {
                // Linking the generator fails here only if a consumer excluded the classfile
                // library, and defining the class can fail too; either leaves the pattern
                // interpreted.
                return null;
            }
            if (generated == null) {
                return null;
            }
            return new CompiledTrinoLikePattern(pattern, generated);
        }

        static TrinoLikeKernelGenerator.Kernel kernel(TrinoLikePattern pattern)
        {
            TrinoLikeKernelGenerator.Region region = switch (pattern.matcher) {
                case null -> null;
                case ContainsMatcher contains -> new TrinoLikeKernelGenerator.OrderedLiterals(
                        List.of(contains.search == null ? new LiteralSearchKernel(contains.literal) : contains.search));
                case OrderedMatcher ordered -> new TrinoLikeKernelGenerator.OrderedLiterals(
                        ordered.matcher().literals());
                case LiteralGapPlanMatcher gaps -> new TrinoLikeKernelGenerator.LiteralGaps(
                        gaps.matcher().literals(),
                        gaps.matcher().codePointGaps());
                case WildcardMatcher wildcard -> new TrinoLikeKernelGenerator.Wildcard(
                        wildcard.matcher().positions(),
                        wildcard.matcher().loopback(),
                        wildcard.matcher().exact());
                default -> throw new IllegalStateException("unknown Trino LIKE matcher: " + pattern.matcher);
            };
            return new TrinoLikeKernelGenerator.Kernel(
                    decode(pattern.strategy),
                    pattern.minimumSize,
                    pattern.maximumSize,
                    pattern.prefix.getBytes(),
                    pattern.suffix.getBytes(),
                    region);
        }

        @Override
        public boolean matches(Slice input)
        {
            requireNonNull(input, "input is null");
            return like.matches(input);
        }

        @Override
        public boolean usesGeneratedBytecode()
        {
            return true;
        }

        @Override
        public Optional<MethodHandle> generatedMatches()
        {
            return Optional.of(generated.matches());
        }
    }
}
