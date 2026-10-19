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

import io.airlift.classfile.BytecodeExpression;
import io.airlift.classfile.ClassCompiler;
import io.airlift.classfile.ClassDefinition;
import io.airlift.classfile.ClassModel;
import io.airlift.classfile.CodeBlock;
import io.airlift.classfile.CodeLabel;
import io.airlift.classfile.CompilationReport;
import io.airlift.classfile.CompiledUnit;
import io.airlift.classfile.DefinedUnit;
import io.airlift.classfile.HiddenClassDefiner;
import io.airlift.classfile.IfStatement;
import io.airlift.classfile.MethodDefinition;
import io.airlift.classfile.Parameter;
import io.airlift.classfile.SwitchStatement;
import io.airlift.classfile.Variable;
import io.airlift.slice.Slice;

import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.airlift.classfile.BytecodeExpressions.boundConstant;
import static io.airlift.classfile.BytecodeExpressions.constantFalse;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantTrue;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.Parameter.arg;
import static io.airlift.regulator.LiteralSearchKernel.CANDIDATE_COMPARISON_BUDGET;
import static io.airlift.regulator.TrinoLikeWildcardMatcher.MATCH_ANY;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.util.Objects.requireNonNull;

/**
 * Compiles one {@link TrinoLikePattern} to a hidden class in this package, with every literal,
 * length bound, and wildcard position as a constant. Every plan has a class. Its public static
 * {@code matchesEntry} checks the length bounds, the anchored prefix and suffix with constant word
 * comparisons, or {@code Arrays.equals} for one of more than 16 bytes, and calls the method of the
 * pattern's middle: a copy of the literal search for contained and ordered literals, a literal
 * search with constant character gaps, or a bit-parallel automaton for other wildcard patterns.
 * The class implements {@link CompiledTrinoLike} by delegating to {@code matchesEntry}, so a caller
 * can also bind the entry directly.
 * <p>
 * Every generated method must stay within HotSpot's huge-method limit, because HotSpot never
 * compiles a larger method. A pattern whose class would exceed it has no class.
 */
final class TrinoLikeKernelGenerator
{
    // HotSpot does not JIT-compile a method of more than HugeMethodLimit bytecode bytes, which is
    // 8,000 by default.
    static final int HUGE_METHOD_LIMIT = 8_000;
    // Literals of up to this many bytes compare with at most two overlapping reads, and the
    // candidate verification of a search compares them byte by byte without a loop.
    private static final int INLINE_LITERAL_BYTES = 2 * Long.BYTES;
    // A gap of up to this many characters steps over each character without a loop.
    private static final int UNROLLED_GAP_CHARACTERS = 4;
    private static final long LOW_BITS = 0x0101010101010101L;
    private static final long HIGH_BITS = 0x8080808080808080L;

    private TrinoLikeKernelGenerator() {}

    /**
     * What one compiled LIKE pattern matches, in the terms of its interpreted plan: input length
     * bounds, with {@code maximumSize} negative when unbounded, an anchored prefix and suffix,
     * either of which may be empty, and the matcher of the remaining middle, or {@code null} when
     * the prefix and suffix decide the match.
     */
    record Kernel(TrinoLikePattern.Plan plan, int minimumSize, int maximumSize, byte[] prefix, byte[] suffix, Region region)
    {
        Kernel
        {
            requireNonNull(plan, "plan is null");
            requireNonNull(prefix, "prefix is null");
            requireNonNull(suffix, "suffix is null");
        }
    }

    /**
     * The matcher of the middle of a pattern, between its prefix and suffix.
     */
    sealed interface Region
            permits LiteralGaps,
                    OrderedLiterals,
                    Wildcard {}

    /**
     * Literals that occur in order, each searched after the end of the previous one, as
     * {@link OrderedLiteralMatcher} searches them; one literal is a contains search.
     */
    record OrderedLiterals(List<LiteralSearchKernel> literals)
            implements Region
    {
        OrderedLiterals
        {
            literals = List.copyOf(literals);
            if (literals.isEmpty()) {
                throw new IllegalArgumentException("literals is empty");
            }
        }
    }

    /**
     * Literals separated by fixed numbers of characters, as {@link LiteralGapMatcher} finds them.
     */
    record LiteralGaps(List<LiteralSearchKernel> literals, int[] codePointGaps)
            implements Region
    {
        LiteralGaps
        {
            literals = List.copyOf(literals);
            requireNonNull(codePointGaps, "codePointGaps is null");
            if (codePointGaps.length != literals.size() - 1) {
                throw new IllegalArgumentException("a gap must separate each pair of literals");
            }
        }
    }

    /**
     * The automaton of {@link TrinoLikeWildcardMatcher}: what each position matches, a code point
     * or {@link TrinoLikeWildcardMatcher#MATCH_ANY}, and which states loop on any character.
     */
    record Wildcard(int[] positions, boolean[] loopback, boolean exact)
            implements Region
    {
        Wildcard
        {
            requireNonNull(positions, "positions is null");
            requireNonNull(loopback, "loopback is null");
            if (loopback.length != positions.length + 1) {
                throw new IllegalArgumentException("loopback must have one entry per state");
            }
        }
    }

    /**
     * A generated class for one pattern: its {@link CompiledTrinoLike} instance and a handle on its
     * static entry.
     */
    record CompiledLike(CompiledTrinoLike like, MethodHandle matches)
    {
        CompiledLike
        {
            requireNonNull(like, "like is null");
            requireNonNull(matches, "matches is null");
        }
    }

    /**
     * Generates and defines the class for {@code kernel}, or returns {@code null} when a method of
     * the class would exceed {@link #HUGE_METHOD_LIMIT}. Other failures propagate.
     */
    static CompiledLike generate(Kernel kernel)
    {
        // Building the class of a large pattern costs time and memory that grow with the
        // pattern, so a pattern certain to exceed the limit is rejected before it is built.
        if (certainlyExceedsLimit(kernel.region())) {
            return null;
        }
        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = compile(definer, kernel);
        if (largestMethodBytes(unit.report()) > HUGE_METHOD_LIMIT) {
            return null;
        }
        DefinedUnit defined = definer.defineUnit(unit);
        Class<? extends CompiledTrinoLike> likeClass = defined.primaryClass(CompiledTrinoLike.class);
        CompiledTrinoLike like;
        try {
            like = likeClass.getConstructor().newInstance();
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot instantiate generated LIKE class", e);
        }
        MethodHandle matches;
        try {
            matches = MethodHandles.lookup().findStatic(likeClass, "matchesEntry", MethodType.methodType(boolean.class, Slice.class));
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("generated LIKE class lacks matchesEntry", e);
        }
        return new CompiledLike(like, matches);
    }

    /**
     * Returns the class file generated for {@code kernel}, without defining it or checking its
     * method sizes.
     */
    static byte[] classfileForDiagnostics(Kernel kernel)
    {
        CompiledUnit unit = compile(HiddenClassDefiner.builder(MethodHandles.lookup()).build(), kernel);
        return unit.classfile(unit.primaryType());
    }

    /**
     * Returns the size in bytecode bytes of the largest method generated for {@code kernel}.
     */
    static int largestMethodBytesForDiagnostics(Kernel kernel)
    {
        return largestMethodBytes(compile(HiddenClassDefiner.builder(MethodHandles.lookup()).build(), kernel).report());
    }

    /**
     * Returns true when the size estimate rejects {@code kernel} before its class is built.
     */
    static boolean certainlyExceedsLimitForDiagnostics(Kernel kernel)
    {
        return certainlyExceedsLimit(kernel.region());
    }

    private static boolean certainlyExceedsLimit(Region region)
    {
        return minimumMiddleBytes(region) > HUGE_METHOD_LIMIT;
    }

    /**
     * A lower bound on the bytecode bytes of the method of {@code region}: every literal of
     * ordered literals and every gap of literal gaps emits at least 16 bytes of checks, every
     * state word of a wildcard automaton at least 16 bytes of state update, and every distinct
     * code point of a wildcard at least a 4-byte switch entry. An exact run of {@code _} steps
     * over its characters in constant code.
     */
    private static long minimumMiddleBytes(Region region)
    {
        return switch (region) {
            case null -> 0;
            case OrderedLiterals orderedLiterals -> 16L * orderedLiterals.literals().size();
            case LiteralGaps literalGaps -> 16L * literalGaps.codePointGaps().length;
            case Wildcard wildcard -> {
                if (isExactUnderscoreRun(wildcard)) {
                    yield 0;
                }
                long words = (long) wildcard.positions().length / Long.SIZE + 1;
                long distinctCodePoints = Arrays.stream(wildcard.positions())
                        .filter(position -> position != MATCH_ANY)
                        .distinct()
                        .count();
                yield 16L * words + 4L * distinctCodePoints;
            }
        };
    }

    /**
     * Returns true when {@code region} is an exact run of {@code _}, which matches a fixed number
     * of characters.
     */
    private static boolean isExactUnderscoreRun(Wildcard region)
    {
        for (int position : region.positions()) {
            if (position != MATCH_ANY) {
                return false;
            }
        }
        for (boolean loops : region.loopback()) {
            if (loops) {
                return false;
            }
        }
        return region.exact();
    }

    private static CompiledUnit compile(HiddenClassDefiner definer, Kernel kernel)
    {
        return ClassCompiler.forTarget(definer.compilationTarget())
                .compileUnit(new LikeEmitter(kernel).define());
    }

    private static int largestMethodBytes(CompilationReport report)
    {
        int largest = 0;
        for (CompilationReport.ClassInfo classInfo : report.classes()) {
            for (CompilationReport.MethodInfo method : classInfo.methods()) {
                largest = Math.max(largest, method.codeBytes());
            }
        }
        return largest;
    }

    /**
     * Emits the class of one pattern: {@code matchesEntry}, the method of its middle, and a search
     * method for each literal the middle searches for.
     */
    private static final class LikeEmitter
    {
        private final Kernel kernel;
        private ClassDefinition definition;
        private int labels;

        private LikeEmitter(Kernel kernel)
        {
            this.kernel = requireNonNull(kernel, "kernel is null");
        }

        private ClassModel define()
        {
            definition = ClassDefinition.define(ClassDesc.of(className(kernel.plan())))
                    .access(PUBLIC, FINAL)
                    .addInterface(CompiledTrinoLike.class);
            definition.defaultConstructor().access(PUBLIC);

            MethodDefinition middle = switch (kernel.region()) {
                case null -> null;
                case OrderedLiterals orderedLiterals -> defineOrderedLiterals(orderedLiterals);
                case LiteralGaps literalGaps -> defineLiteralGaps(literalGaps);
                case Wildcard wildcard -> defineWildcard(wildcard);
            };
            MethodDefinition entry = defineEntry(middle);

            Parameter input = arg("input", Slice.class);
            definition.method("matches", boolean.class, input)
                    .access(PUBLIC)
                    .body()
                    .ret(invokeStatic(entry, input));
            return definition.build();
        }

        private static String className(TrinoLikePattern.Plan plan)
        {
            String classPrefix = switch (plan) {
                case EMPTY -> "Empty";
                case EQUALS -> "Equals";
                case STARTS_WITH -> "StartsWith";
                case ENDS_WITH -> "EndsWith";
                case CONTAINS -> "Contains";
                case ORDERED_LITERALS -> "OrderedLiterals";
                case TRINO_WILDCARD -> "TrinoWildcard";
                case LITERAL_GAPS -> "LiteralGaps";
            };
            return CompiledTrinoLike.class.getPackageName() + "." + classPrefix + "CompiledTrinoLike";
        }

        /**
         * The steps of {@code TrinoLikePattern.matches}: the length bounds, the prefix at the
         * start, the suffix at the end, and the middle between them.
         */
        private MethodDefinition defineEntry(MethodDefinition middle)
        {
            Parameter input = arg("input", Slice.class);
            MethodDefinition method = definition.method("matchesEntry", boolean.class, input)
                    .access(PUBLIC, STATIC);
            CodeBlock.Builder body = method.body();
            Variable length = body.declare("length", input.invoke("length", int.class));
            int minimumSize = kernel.minimumSize();
            int maximumSize = kernel.maximumSize();
            if (minimumSize == maximumSize) {
                body.append(ifReturnFalse(length.notEqual(constantInt(minimumSize))));
            }
            else {
                if (minimumSize > 0) {
                    body.append(ifReturnFalse(length.lessThan(constantInt(minimumSize))));
                }
                if (maximumSize >= 0) {
                    body.append(ifReturnFalse(length.greaterThan(constantInt(maximumSize))));
                }
            }
            byte[] prefix = kernel.prefix();
            byte[] suffix = kernel.suffix();
            if (prefix.length == 0 && suffix.length == 0 && middle == null) {
                body.ret(constantTrue());
                return method;
            }

            Variable bytes = body.declare("bytes", input.invoke("byteArray", byte[].class));
            Variable base = body.declare("base", input.invoke("byteArrayOffset", int.class));
            // The length bounds leave room for the prefix and suffix.
            if (prefix.length > 0) {
                body.append(ifReturnFalse(literalDiffers(bytes, base, prefix)));
            }
            if (suffix.length > 0) {
                Variable suffixStart = body.declare("suffixStart", base.add(length).subtract(constantInt(suffix.length)));
                body.append(ifReturnFalse(literalDiffers(bytes, suffixStart, suffix)));
            }
            if (middle == null) {
                body.ret(constantTrue());
            }
            else {
                body.ret(invokeStatic(
                        middle,
                        bytes,
                        base.add(constantInt(prefix.length)),
                        base.add(length).subtract(constantInt(suffix.length))));
            }
            return method;
        }

        /**
         * A copy of {@code OrderedLiteralMatcher.matches} over the middle {@code [start, end)} of
         * the backing array.
         */
        private MethodDefinition defineOrderedLiterals(OrderedLiterals region)
        {
            List<MethodDefinition> searches = defineSearches(region.literals());
            Parameter bytes = arg("bytes", byte[].class);
            Parameter start = arg("start", int.class);
            Parameter end = arg("end", int.class);
            MethodDefinition method = definition.method("matchesMiddle", boolean.class, bytes, start, end)
                    .access(PRIVATE, STATIC);
            CodeBlock.Builder body = method.body();
            Variable searchOffset = body.declare("searchOffset", start);
            Variable match = body.declare(int.class, "match");
            for (int literalIndex = 0; literalIndex < searches.size(); literalIndex++) {
                body.append(ifReturnFalse(searchOffset.equal(end)));
                body.append(match.set(invokeStatic(searches.get(literalIndex), bytes, searchOffset, end)));
                body.append(ifReturnFalse(match.lessThan(constantInt(0))));
                body.append(searchOffset.set(match.add(constantInt(region.literals().get(literalIndex).literalLength()))));
            }
            body.ret(constantTrue());
            return method;
        }

        /**
         * A copy of {@code LiteralGapMatcher.matches} over the middle {@code [start, end)} of the
         * backing array: each candidate of the first literal, then each gap and literal after it,
         * retrying from the next candidate when one fails.
         */
        private MethodDefinition defineLiteralGaps(LiteralGaps region)
        {
            List<LiteralSearchKernel> literals = region.literals();
            MethodDefinition firstSearch = defineSearches(literals.subList(0, 1)).getFirst();
            Parameter bytes = arg("bytes", byte[].class);
            Parameter start = arg("start", int.class);
            Parameter end = arg("end", int.class);
            MethodDefinition method = definition.method("matchesMiddle", boolean.class, bytes, start, end)
                    .access(PRIVATE, STATIC);
            CodeBlock.Builder body = method.body();
            Variable searchOffset = body.declare("searchOffset", start);
            Variable match = body.declare(int.class, "match");
            Variable position = body.declare(int.class, "position");
            Variable remaining = null;
            for (int gap : region.codePointGaps()) {
                if (gap > UNROLLED_GAP_CHARACTERS && remaining == null) {
                    remaining = body.declare(int.class, "remaining");
                }
            }
            CodeLabel candidate = body.label("candidate");
            CodeLabel retry = body.label("retry");

            int firstLength = literals.getFirst().literalLength();
            body.mark(candidate);
            body.append(ifReturnFalse(searchOffset.greaterThan(end.subtract(constantInt(firstLength)))));
            body.append(match.set(invokeStatic(firstSearch, bytes, searchOffset, end)));
            body.append(ifReturnFalse(match.lessThan(constantInt(0))));
            body.append(position.set(match.add(constantInt(firstLength))));
            for (int literalIndex = 1; literalIndex < literals.size(); literalIndex++) {
                int gap = region.codePointGaps()[literalIndex - 1];
                if (gap <= UNROLLED_GAP_CHARACTERS) {
                    for (int character = 0; character < gap; character++) {
                        body.append(ifThenJump(position.greaterThanOrEqual(end), retry));
                        body.append(stepCharacter(bytes, position, end));
                    }
                }
                else {
                    CodeLabel step = body.label(nextLabel("step"));
                    CodeLabel stepped = body.label(nextLabel("stepped"));
                    body.append(remaining.set(constantInt(gap)));
                    body.mark(step);
                    body.append(ifThenJump(remaining.equal(constantInt(0)), stepped));
                    body.append(ifThenJump(position.greaterThanOrEqual(end), retry));
                    body.append(stepCharacter(bytes, position, end));
                    body.append(remaining.set(remaining.subtract(constantInt(1))));
                    body.jump(step);
                    body.mark(stepped);
                }
                byte[] literal = literals.get(literalIndex).literal();
                body.append(ifThenJump(position.greaterThan(end.subtract(constantInt(literal.length))), retry));
                body.append(ifThenJump(literalDiffers(bytes, position, literal), retry));
                body.append(position.set(position.add(constantInt(literal.length))));
            }
            body.ret(constantTrue());

            body.mark(retry);
            body.append(searchOffset.set(match.add(constantInt(1))));
            body.jump(candidate);
            return method;
        }

        /**
         * A bit-parallel shift-and automaton equivalent to {@code TrinoLikeWildcardMatcher.matches}
         * over the middle {@code [start, end)} of the backing array. Bit {@code s} of the state
         * words is set when the matcher can be in state {@code s}; state 0 starts, and the state
         * after the last position accepts. Each character keeps the states that loop on any
         * character and advances each state whose position matches the character by one bit,
         * carrying between words. A position matches its code point, and a {@code _} position
         * matches any character; a malformed character has no code point, so it matches only
         * {@code _} positions. The match fails once no state remains, succeeds at the first
         * accepting character unless the middle must end at the accepting state, and otherwise
         * succeeds when the middle ends in it.
         */
        private MethodDefinition defineWildcard(Wildcard region)
        {
            int[] positions = region.positions();
            boolean[] loopback = region.loopback();
            Parameter bytes = arg("bytes", byte[].class);
            Parameter start = arg("start", int.class);
            Parameter end = arg("end", int.class);
            MethodDefinition method = definition.method("matchesMiddle", boolean.class, bytes, start, end)
                    .access(PRIVATE, STATIC);
            CodeBlock.Builder body = method.body();
            if (isExactUnderscoreRun(region)) {
                if (positions.length == 1) {
                    // One _ is one character, without a loop, as TrinoLikeWildcardMatcher.matches
                    // checks it: with one profiled character width, C2 makes the loop below a
                    // counted loop, and its range check setup costs more than the character.
                    body.ret(start.lessThan(end).and(start.add(invokeStatic(TrinoLikeKernelSupport.class, "characterWidth", int.class, bytes, start, end)).equal(end)));
                    return method;
                }
                Variable position = body.declare("position", start);
                // A longer run of _ steps over its characters as a gap of LiteralGaps does: up to
                // UNROLLED_GAP_CHARACTERS without a loop, for the same reason as one _.
                if (positions.length <= UNROLLED_GAP_CHARACTERS) {
                    for (int character = 0; character < positions.length; character++) {
                        body.append(ifReturnFalse(position.greaterThanOrEqual(end)));
                        body.append(stepCharacter(bytes, position, end));
                    }
                }
                else {
                    Variable remaining = body.declare("remaining", constantInt(positions.length));
                    CodeLabel step = body.label("step");
                    CodeLabel stepped = body.label("stepped");
                    body.mark(step);
                    body.append(ifThenJump(remaining.equal(constantInt(0)), stepped));
                    body.append(ifReturnFalse(position.greaterThanOrEqual(end)));
                    body.append(stepCharacter(bytes, position, end));
                    body.append(remaining.set(remaining.subtract(constantInt(1))));
                    body.jump(step);
                    body.mark(stepped);
                }
                body.ret(position.equal(end));
                return method;
            }

            int acceptState = positions.length;
            int words = acceptState / Long.SIZE + 1;
            long[] anyMasks = new long[words];
            long[] loopMasks = new long[words];
            Map<Integer, long[]> codePointMasks = new LinkedHashMap<>();
            for (int state = 0; state < positions.length; state++) {
                if (positions[state] == MATCH_ANY) {
                    anyMasks[state / Long.SIZE] |= 1L << state;
                }
            }
            for (int state = 0; state < positions.length; state++) {
                if (positions[state] != MATCH_ANY) {
                    codePointMasks.computeIfAbsent(positions[state], _ -> anyMasks.clone())[state / Long.SIZE] |= 1L << state;
                }
            }
            for (int state = 0; state <= acceptState; state++) {
                if (loopback[state]) {
                    loopMasks[state / Long.SIZE] |= 1L << state;
                }
            }
            boolean nonAsciiCodePoints = codePointMasks.keySet().stream().anyMatch(codePoint -> codePoint >= 0x80);

            Variable position = body.declare("position", start);
            Variable[] states = new Variable[words];
            Variable[] masks = new Variable[words];
            for (int word = 0; word < words; word++) {
                states[word] = body.declare("states" + word, constantLong(word == 0 ? 1 : 0));
                masks[word] = body.declare(long.class, "mask" + word);
            }
            Variable advanced = body.declare(long.class, "advanced");
            Variable carry = words > 1 ? body.declare(long.class, "carry") : null;
            // The class compiler plans an expression recursively, so one expression ORing every
            // word would nest as deeply as there are words; the words accumulate here instead.
            Variable anyState = words > 1 ? body.declare(long.class, "anyState") : null;
            Variable codePoint = body.declare(int.class, "codePoint");
            Variable width = body.declare(int.class, "width");
            CodeLabel character = body.label("character");
            CodeLabel finish = body.label("finish");

            body.mark(character);
            body.append(ifThenJump(position.greaterThanOrEqual(end), finish));
            // An ASCII byte is its own code point. Without a non-ASCII code point in the pattern,
            // the negative byte that leads any other character selects no code point.
            body.append(codePoint.set(bytes.getElement(position).cast(int.class)));
            CodeBlock.Builder nonAscii = CodeBlock.blockBuilder()
                    .append(width.set(invokeStatic(TrinoLikeKernelSupport.class, "characterWidth", int.class, bytes, position, end)));
            if (nonAsciiCodePoints) {
                nonAscii.append(codePoint.set(invokeStatic(TrinoLikeKernelSupport.class, "nonAsciiCodePoint", int.class, bytes, position, width)));
            }
            nonAscii.append(position.set(position.add(width)));
            body.append(IfStatement.builder()
                    .condition(codePoint.greaterThanOrEqual(constantInt(0)))
                    .then(position.set(position.add(constantInt(1))))
                    .otherwise(nonAscii.build())
                    .build());

            for (int word = 0; word < words; word++) {
                body.append(masks[word].set(constantLong(anyMasks[word])));
            }
            if (!codePointMasks.isEmpty()) {
                SwitchStatement.Builder select = SwitchStatement.builder()
                        .description("positions that match the character")
                        .expression(codePoint);
                codePointMasks.forEach((value, codePointMask) -> {
                    CodeBlock.Builder assign = CodeBlock.blockBuilder();
                    for (int word = 0; word < words; word++) {
                        if (codePointMask[word] != anyMasks[word]) {
                            assign.append(masks[word].set(constantLong(codePointMask[word])));
                        }
                    }
                    select.caseValue(value, assign.build());
                });
                body.append(select.build());
            }

            for (int word = 0; word < words; word++) {
                Variable state = states[word];
                body.append(advanced.set(state.bitwiseAnd(masks[word])));
                BytecodeExpression next = advanced.shiftLeft(constantInt(1));
                if (word > 0) {
                    next = next.bitwiseOr(carry.shiftRightUnsigned(constantInt(Long.SIZE - 1)));
                }
                if (loopMasks[word] != 0) {
                    next = state.bitwiseAnd(constantLong(loopMasks[word])).bitwiseOr(next);
                }
                body.append(state.set(next));
                if (word < words - 1) {
                    body.append(carry.set(advanced));
                }
                if (anyState != null) {
                    body.append(anyState.set(word == 0 ? state : anyState.bitwiseOr(state)));
                }
            }
            body.append(ifReturnFalse((anyState == null ? states[0] : anyState).equal(constantLong(0))));
            BytecodeExpression accepted = states[acceptState / Long.SIZE]
                    .bitwiseAnd(constantLong(1L << acceptState))
                    .notEqual(constantLong(0));
            if (!region.exact()) {
                body.append(IfStatement.builder()
                        .condition(accepted)
                        .then(constantTrue().ret())
                        .build());
            }
            body.jump(character);

            body.mark(finish);
            body.ret(region.exact() ? accepted : constantFalse());
            return method;
        }

        /**
         * Defines a search method for each of {@code literals}, numbered in order, each a copy of
         * {@code LiteralSearchKernel.find} for its literal. A search takes the backing array and
         * the absolute range to search, and returns the absolute position of the first
         * occurrence or {@code -1}.
         */
        private List<MethodDefinition> defineSearches(List<LiteralSearchKernel> literals)
        {
            List<MethodDefinition> searches = new ArrayList<>();
            for (LiteralSearchKernel literal : literals) {
                Parameter bytes = arg("bytes", byte[].class);
                Parameter start = arg("start", int.class);
                Parameter end = arg("end", int.class);
                MethodDefinition method = definition.method("find" + searches.size(), int.class, bytes, start, end)
                        .access(PRIVATE, STATIC);
                CodeBlock.Builder body = method.body();
                byte[] literalBytes = literal.literal();
                body.append(IfStatement.builder()
                        .condition(end.subtract(start).lessThan(constantInt(literalBytes.length)))
                        .then(constantInt(-1).ret())
                        .build());
                if (literalBytes.length == 1) {
                    emitFindSingleByte(body, bytes, start, end, literalBytes[0]);
                }
                else {
                    emitFindFrontAndBack(body, bytes, start, end, literalBytes, literal.prefixShifts());
                }
                searches.add(method);
            }
            return searches;
        }

        /**
         * A copy of {@code LiteralSearchKernel.findSingleByte}, two words at a time.
         */
        private static void emitFindSingleByte(CodeBlock.Builder body, Parameter bytes, Parameter start, Parameter end, byte target)
        {
            long broadcast = broadcast(target);
            Variable position = body.declare("position", start);
            Variable wordEnd = body.declare("wordEnd", start.add(end.subtract(start)
                    .divide(constantInt(2 * Long.BYTES))
                    .multiply(constantInt(2 * Long.BYTES))));
            Variable firstMatches = body.declare(long.class, "firstMatches");
            Variable secondMatches = body.declare(long.class, "secondMatches");
            Variable difference = body.declare(long.class, "difference");
            CodeLabel word = body.label("word");
            CodeLabel tail = body.label("tail");

            body.mark(word);
            body.append(ifThenJump(position.greaterThanOrEqual(wordEnd), tail));
            body.append(difference.set(readLong(bytes, position).bitwiseXor(constantLong(broadcast))));
            body.append(firstMatches.set(zeroByteMask(difference)));
            body.append(difference.set(readLong(bytes, position.add(constantInt(Long.BYTES))).bitwiseXor(constantLong(broadcast))));
            body.append(secondMatches.set(zeroByteMask(difference)));
            body.append(IfStatement.builder()
                    .condition(firstMatches.bitwiseOr(secondMatches).notEqual(constantLong(0)))
                    .then(CodeBlock.block(
                            IfStatement.builder()
                                    .condition(firstMatches.notEqual(constantLong(0)))
                                    .then(position.add(firstByte(firstMatches)).ret())
                                    .build(),
                            position.add(constantInt(Long.BYTES)).add(firstByte(secondMatches)).ret()))
                    .build());
            body.append(position.set(position.add(constantInt(2 * Long.BYTES))));
            body.jump(word);

            body.mark(tail);
            body.append(IfStatement.builder()
                    .condition(position.greaterThanOrEqual(end))
                    .then(constantInt(-1).ret())
                    .build());
            body.append(IfStatement.builder()
                    .condition(bytes.getElement(position).cast(int.class).equal(constantInt(target)))
                    .then(position.ret())
                    .build());
            body.append(position.set(position.add(constantInt(1))));
            body.jump(tail);
        }

        /**
         * A copy of {@code LiteralSearchKernel.findFrontAndBack}: candidates whose first and last
         * bytes match, verified in order, with the same KMP handoff rule.
         */
        private void emitFindFrontAndBack(CodeBlock.Builder body, Parameter bytes, Parameter start, Parameter end, byte[] literal, int[] prefixShifts)
        {
            int length = literal.length;
            int backOffset = length - 1;
            Variable position = body.declare("position", start);
            Variable wordEnd = body.declare("wordEnd", start.add(end.subtract(start).subtract(constantInt(backOffset))
                    .divide(constantInt(Long.BYTES))
                    .multiply(constantInt(Long.BYTES))));
            Variable comparisonEnd = body.declare("comparisonEnd", start.subtract(constantInt(CANDIDATE_COMPARISON_BUDGET)));
            Variable candidates = body.declare(long.class, "candidates");
            Variable frontDifference = body.declare(long.class, "frontDifference");
            Variable backDifference = body.declare(long.class, "backDifference");
            Variable candidate = body.declare(int.class, "candidate");
            Variable matchedLength = body.declare(int.class, "matchedLength");
            CodeLabel word = body.label("word");
            CodeLabel nextCandidate = body.label("nextCandidate");
            CodeLabel nextWord = body.label("nextWord");
            CodeLabel tail = body.label("tail");
            CodeLabel tailCandidate = body.label("tailCandidate");
            CodeLabel nextTailCandidate = body.label("nextTailCandidate");

            body.mark(word);
            body.append(ifThenJump(position.greaterThanOrEqual(wordEnd), tail));
            body.append(frontDifference.set(readLong(bytes, position).bitwiseXor(constantLong(broadcast(literal[0])))));
            body.append(backDifference.set(readLong(bytes, position.add(constantInt(backOffset))).bitwiseXor(constantLong(broadcast(literal[backOffset])))));
            body.append(candidates.set(bothZeroByteMask(frontDifference, backDifference)));
            body.mark(nextCandidate);
            body.append(ifThenJump(candidates.equal(constantLong(0)), nextWord));
            body.append(candidate.set(position.add(firstByte(candidates))));
            emitVerifyCandidate(body, bytes, end, candidate, matchedLength, comparisonEnd, literal, prefixShifts);
            body.append(candidates.set(candidates.bitwiseAnd(candidates.subtract(constantLong(1)))));
            body.jump(nextCandidate);
            body.mark(nextWord);
            body.append(position.set(position.add(constantInt(Long.BYTES))));
            body.jump(word);

            body.mark(tail);
            Variable candidateEnd = body.declare("candidateEnd", end.subtract(constantInt(backOffset)));
            body.mark(tailCandidate);
            body.append(IfStatement.builder()
                    .condition(position.greaterThanOrEqual(candidateEnd))
                    .then(constantInt(-1).ret())
                    .build());
            body.append(ifThenJump(bytes.getElement(position).cast(int.class).notEqual(constantInt(literal[0])), nextTailCandidate));
            body.append(ifThenJump(bytes.getElement(position.add(constantInt(backOffset))).cast(int.class).notEqual(constantInt(literal[backOffset])), nextTailCandidate));
            body.append(candidate.set(position));
            emitVerifyCandidate(body, bytes, end, candidate, matchedLength, comparisonEnd, literal, prefixShifts);
            body.mark(nextTailCandidate);
            body.append(position.set(position.add(constantInt(1))));
            body.jump(tailCandidate);
        }

        /**
         * Returns {@code candidate} when the whole literal occurs there. Otherwise adds the matched
         * bytes and the mismatch to {@code comparisonEnd} and, once that passes the candidate,
         * returns the KMP search from the next position.
         */
        private void emitVerifyCandidate(
                CodeBlock.Builder body,
                Parameter bytes,
                Parameter end,
                Variable candidate,
                Variable matchedLength,
                Variable comparisonEnd,
                byte[] literal,
                int[] prefixShifts)
        {
            int length = literal.length;
            if (length <= INLINE_LITERAL_BYTES) {
                CodeLabel compared = body.label(nextLabel("compared"));
                for (int index = 0; index < length; index++) {
                    body.append(matchedLength.set(constantInt(index)));
                    body.append(ifThenJump(
                            bytes.getElement(candidate.add(constantInt(index))).cast(int.class).notEqual(constantInt(literal[index])),
                            compared));
                }
                body.append(matchedLength.set(constantInt(length)));
                body.mark(compared);
            }
            else {
                body.append(matchedLength.set(invokeStatic(
                        TrinoLikeKernelSupport.class,
                        "matchingPrefixLength",
                        int.class,
                        bytes,
                        candidate,
                        boundConstant(literal, byte[].class))));
            }
            body.append(IfStatement.builder()
                    .condition(matchedLength.equal(constantInt(length)))
                    .then(candidate.ret())
                    .build());
            body.append(comparisonEnd.set(comparisonEnd.add(matchedLength).add(constantInt(1))));
            body.append(IfStatement.builder()
                    .condition(comparisonEnd.greaterThan(candidate))
                    .then(invokeStatic(
                            TrinoLikeKernelSupport.class,
                            "findKmp",
                            int.class,
                            bytes,
                            candidate.add(constantInt(1)),
                            end,
                            boundConstant(literal, byte[].class),
                            boundConstant(prefixShifts, int[].class)).ret())
                    .build());
        }

        private String nextLabel(String name)
        {
            return name + labels++;
        }
    }

    /**
     * Returns true when {@code literal} does not occur at {@code position}, which leaves room for
     * it, with the comparisons of {@code LiteralMatchKernel.ShortLiteral} for a short literal.
     */
    private static BytecodeExpression literalDiffers(BytecodeExpression bytes, Variable position, byte[] literal)
    {
        int length = literal.length;
        if (length > INLINE_LITERAL_BYTES) {
            return invokeStatic(
                    Arrays.class,
                    "equals",
                    boolean.class,
                    bytes,
                    position,
                    position.add(constantInt(length)),
                    boundConstant(literal, byte[].class),
                    constantInt(0),
                    constantInt(length))
                    .not();
        }
        int width = Math.min(Integer.highestOneBit(length), Long.BYTES);
        int lastOffset = length - width;
        if (width == 1) {
            return bytes.getElement(position).cast(int.class).notEqual(constantInt(literal[0]));
        }
        String read = switch (width) {
            case 2 -> "readShort";
            case 4 -> "readInt";
            case 8 -> "readLong";
            default -> throw new IllegalStateException("unexpected literal width: " + width);
        };
        boolean wide = width == Long.BYTES;
        Class<?> type = wide ? long.class : int.class;
        BytecodeExpression first = wide ? constantLong(readLong(literal, 0)) : constantInt(readInt(literal, 0, width));
        BytecodeExpression zero = wide ? constantLong(0) : constantInt(0);
        BytecodeExpression firstRead = invokeStatic(TrinoLikeKernelSupport.class, read, type, bytes, position);
        if (lastOffset == 0) {
            return firstRead.notEqual(first);
        }
        BytecodeExpression last = wide ? constantLong(readLong(literal, lastOffset)) : constantInt(readInt(literal, lastOffset, width));
        BytecodeExpression lastRead = invokeStatic(TrinoLikeKernelSupport.class, read, type, bytes, position.add(constantInt(lastOffset)));
        return firstRead.bitwiseXor(first).bitwiseOr(lastRead.bitwiseXor(last)).notEqual(zero);
    }

    private static int readInt(byte[] literal, int offset, int width)
    {
        return width == Short.BYTES ? TrinoLikeKernelSupport.readShort(literal, offset) : TrinoLikeKernelSupport.readInt(literal, offset);
    }

    private static long readLong(byte[] literal, int offset)
    {
        return TrinoLikeKernelSupport.readLong(literal, offset);
    }

    /**
     * Steps {@code position} over one character of the width {@code LiteralGapMatcher} reads with
     * {@code Utf8.characterWidth}, stepping an ASCII byte inline rather than by that call.
     */
    private static IfStatement stepCharacter(BytecodeExpression bytes, Variable position, BytecodeExpression end)
    {
        return IfStatement.builder()
                .condition(bytes.getElement(position).cast(int.class).greaterThanOrEqual(constantInt(0)))
                .then(position.set(position.add(constantInt(1))))
                .otherwise(position.set(position.add(invokeStatic(TrinoLikeKernelSupport.class, "characterWidth", int.class, bytes, position, end))))
                .build();
    }

    private static BytecodeExpression readLong(BytecodeExpression bytes, BytecodeExpression position)
    {
        return invokeStatic(TrinoLikeKernelSupport.class, "readLong", long.class, bytes, position);
    }

    private static long broadcast(byte value)
    {
        return (value & 0xFFL) * LOW_BITS;
    }

    /**
     * The high bit of each zero byte of {@code value}, as {@code LiteralSearchKernel.zeroByteMask}
     * computes it, including the bits it sets above the lowest zero byte.
     * <p>
     * Emitted as {@code (value - LOW_BITS) & ~(value | ~HIGH_BITS)}, which has the same bits. The
     * scans XOR a constant into {@code value}, and C2 folds {@code ~(input ^ constant)} into a
     * second XOR, which costs an extra instruction per word and loses the and-not; this form
     * keeps it.
     */
    private static BytecodeExpression zeroByteMask(Variable value)
    {
        return value.subtract(constantLong(LOW_BITS))
                .bitwiseAnd(value.bitwiseOr(constantLong(~HIGH_BITS)).bitwiseXor(constantLong(-1)));
    }

    /**
     * {@code zeroByteMask(first) & zeroByteMask(second)}, the bytes that are zero in both,
     * including the bits each sets above its lowest zero byte, in one expression with a single
     * and-not, for the same reason as {@link #zeroByteMask}.
     */
    private static BytecodeExpression bothZeroByteMask(Variable first, Variable second)
    {
        return first.subtract(constantLong(LOW_BITS))
                .bitwiseAnd(second.subtract(constantLong(LOW_BITS)))
                .bitwiseAnd(first.bitwiseOr(second).bitwiseXor(constantLong(-1)))
                .bitwiseAnd(constantLong(HIGH_BITS));
    }

    /**
     * The byte index of the lowest set bit of a little-endian mask.
     */
    private static BytecodeExpression firstByte(Variable mask)
    {
        return invokeStatic(Long.class, "numberOfTrailingZeros", int.class, mask).shiftRightUnsigned(constantInt(3));
    }

    private static IfStatement ifReturnFalse(BytecodeExpression condition)
    {
        return IfStatement.builder()
                .condition(condition)
                .then(constantFalse().ret())
                .build();
    }

    private static CodeBlock ifThenJump(BytecodeExpression condition, CodeLabel target)
    {
        return CodeBlock.block(IfStatement.builder()
                .condition(condition)
                .then(CodeBlock.blockBuilder().jump(target).build())
                .build());
    }
}
