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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Objects.requireNonNull;

final class Compiler
{
    private Compiler() {}

    enum CompileStage
    {
        RAW,
        OPTIMIZED,
        FLATTENED,
        BYTEMAP,
    }

    enum Dialect
    {
        RE2,
        TRINO,
    }

    private static final int RUNES_SELF = 0x80;
    private static final int UTF_MAX = 4;
    private static final int MAX_INSTRUCTIONS = 1 << 24;
    private static final int CPP_INSTRUCTION_BYTES = 8;
    private static final int CPP_LIST_HEAD_BYTES = 2;
    private static final int CPP_PROG_OVERHEAD_BYTES = 512;
    private static final int DEFAULT_DFA_MEMORY = 1 << 20;
    private static final int DOMINATOR_ELISION_MINIMUM_INSTRUCTIONS = 1024;
    private static final int DOMINATOR_ELISION_MAXIMUM_VISITS = 1_000_000;

    static Prog compile(Regexp regexp)
    {
        requireNonNull(regexp, "regexp is null");
        return compile(regexp, false, 0);
    }

    /**
     * Compile a Regexp to a Prog, optionally in reverse mode for backward searching.
     * <p>
     * <b>Reverse Mode ({@code reversed=true}):</b>
     * When compiling in reverse mode, two transformations occur:
     * <ol>
     * <li><b>Scan Direction:</b> Resulting program scans bytes right-to-left instead of left-to-right
     * <li><b>Anchor Swapping:</b> Start/end anchor flags are swapped:
     *     <ul>
     *     <li>Pattern {@code ^abc} compiles to {@code program.anchorEnd=true, anchorStart=false}
     *     <li>Pattern {@code abc$} compiles to {@code program.anchorStart=true, anchorEnd=false}
     *     </ul>
     * </ol>
     * <p>
     * This makes anchors semantically correct when scanning backward. For example, {@code ^}
     * (begin-text) becomes the end anchor in a reversed program because when scanning right-to-left,
     * the "beginning" of the text is reached at the end of the backward scan.
     * <p>
     * <b>Usage in Two-Phase Search:</b>
     * <pre>{@code
     * // Compile both directions with appropriate memory budgets
     * Prog forward = Compiler.compile(regexp, false, maxMemory * 2/3);  // Normal left-to-right
     * Prog reverse = Compiler.compile(regexp, true, maxMemory / 3);     // Reverse right-to-left
     * // Phase 1: Forward finds match end
     * long matchEnd = Dfa.search(forward, text, text, false, Prog.MatchKind.FIRST_MATCH, true);
     * // Phase 2: Reverse finds match start
     * Slice prefix = Slices.wrappedBuffer(text.byteArray(), 0, (int) matchEnd);
     * long matchStart = Dfa.search(reverse, prefix, text, true, Prog.MatchKind.LONGEST_MATCH, true);
     * }</pre>
     * <p>
     * See {@code REVERSE_DFA.md} for complete documentation.
     *
     * @param regexp the parsed regexp to compile
     * @param reversed if true, compile for reverse (right-to-left) search
     * @param maxMemory memory budget in bytes (0 = unlimited)
     * @return compiled Prog
     * @throws RegexpCompileMemoryLimitException if memory budget is insufficient
     * @throws RegexpCompileException if compilation fails for a non-memory reason
     */
    static Prog compile(Regexp regexp, boolean reversed, long maxMemory)
    {
        return compile(regexp, reversed, maxMemory, false, Dialect.RE2);
    }

    static Prog compileNormalized(Regexp regexp, boolean reversed, long maxMemory)
    {
        return compile(regexp, reversed, maxMemory, true, Dialect.RE2);
    }

    static Prog compileNormalized(Regexp regexp, boolean reversed, long maxMemory, Dialect dialect)
    {
        return compile(regexp, reversed, maxMemory, true, dialect);
    }

    static Prog compileNormalizedForDfa(Regexp regexp, boolean reversed, long maxMemory)
    {
        return compileInternal(regexp, reversed, maxMemory, CompileStage.BYTEMAP, true, true, Dialect.RE2);
    }

    static Prog compileNormalizedForDfa(Regexp regexp, boolean reversed, long maxMemory, Dialect dialect)
    {
        return compileInternal(regexp, reversed, maxMemory, CompileStage.BYTEMAP, true, true, dialect);
    }

    static long estimatedProgramMemory(Prog program)
    {
        long memory = CPP_PROG_OVERHEAD_BYTES + ((long) program.size() * CPP_INSTRUCTION_BYTES);
        if (program.canBitState()) {
            memory += (long) program.size() * CPP_LIST_HEAD_BYTES;
        }
        return memory + program.onePassMemory();
    }

    private static Prog compile(Regexp regexp, boolean reversed, long maxMemory, boolean normalized, Dialect dialect)
    {
        Prog program = compileInternal(regexp, reversed, maxMemory, CompileStage.BYTEMAP, true, normalized, dialect);
        if (!reversed) {
            program.prepareOnePass();
        }
        return program;
    }

    static Prog compileSet(Regexp regexp, boolean unanchored, boolean anchorBoth, long maxMemory)
    {
        return compileSet(regexp, unanchored, anchorBoth, maxMemory, Dialect.RE2);
    }

    static Prog compileSet(Regexp regexp, boolean unanchored, boolean anchorBoth, long maxMemory, Dialect dialect)
    {
        requireNonNull(regexp, "regexp is null");
        CompilerState compiler = new CompilerState();
        compiler.setup(regexp.parseFlags(), maxMemory, dialect);
        compiler.setAnchorBoth = anchorBoth;

        Regexp simplifiedRegexp = Simplifier.simplify(regexp);
        Fragment compiledFragment = compiler.compileTree(simplifiedRegexp, 2 * compiler.maxInstructions);

        compiler.program.setAnchorStart(true);
        compiler.program.setAnchorEnd(true);
        if (unanchored) {
            compiledFragment = compiler.concatenate(compiler.dotStar(), compiledFragment);
        }
        compiler.program.setCanMatchEmpty(compiledFragment.nullable);
        compiler.program.setStart(compiledFragment.begin);
        compiler.program.setStartUnanchored(compiledFragment.begin);
        return compiler.finish(CompileStage.BYTEMAP);
    }

    static Prog compileForBenchmark(Regexp regexp, boolean reversed, long maxMemory, CompileStage stage)
    {
        // Benchmark-only hook to isolate Compiler pipeline stages.
        return compileInternal(regexp, reversed, maxMemory, stage, false, false, Dialect.RE2);
    }

    static Prog compileNormalizedForBenchmark(Regexp regexp, boolean reversed, long maxMemory, CompileStage stage)
    {
        // Benchmark-only hook to isolate stages after AST normalization.
        return compileInternal(regexp, reversed, maxMemory, stage, false, true, Dialect.RE2);
    }

    private static Prog compileInternal(
            Regexp regexp,
            boolean reversed,
            long maxMemory,
            CompileStage stage,
            boolean configureForwardMetadata,
            boolean normalized,
            Dialect dialect)
    {
        requireNonNull(regexp, "regexp is null");
        requireNonNull(stage, "stage is null");
        CompilerState compiler = new CompilerState();
        compiler.setup(regexp.parseFlags(), maxMemory, dialect);
        compiler.reversed = reversed;

        // Match upstream compile.cc: compiler runs Regexp::Simplify() first,
        // primarily to eliminate counted repetitions (and later to expand named classes).
        Regexp simplifiedRegexp = normalized ? regexp : Simplifier.simplify(regexp);

        // Match upstream compile.cc: record whether the compiled program is anchored by
        // BeginText/EndText ops and remove those anchors from the AST for optimization.
        // This is conservative and has a small recursion depth bound.
        AnchorStripResult startStrip = stripAnchorStart(simplifiedRegexp, 0);
        simplifiedRegexp = startStrip.regexp();
        boolean isAnchorStart = startStrip.anchored();

        AnchorStripResult endStrip = stripAnchorEnd(simplifiedRegexp, 0);
        simplifiedRegexp = endStrip.regexp();
        boolean isAnchorEnd = endStrip.anchored();

        // Generate fragment for entire regexp.
        Fragment compiledFragment = compiler.compileTree(simplifiedRegexp, 2 * compiler.maxInstructions);
        boolean canMatchEmpty = compiledFragment.nullable;

        // Success: append Match at end and record start.
        compiler.reversed = false;
        compiledFragment = compiler.concatenate(compiledFragment, compiler.match(0));

        compiler.program.setReversed(reversed);

        // Apply anchor flags, SWAPPING them for reversed programs.
        // This makes anchors work correctly when searching backward:
        // - "^abc" in reverse: match must end at text beginning (anchorEnd=true)
        // - "abc$" in reverse: match must start at text end (anchorStart=true)
        if (compiler.program.reversed()) {
            compiler.program.setAnchorStart(isAnchorEnd);  // Swap: end becomes start
            compiler.program.setAnchorEnd(isAnchorStart);  // Swap: start becomes end
        }
        else {
            compiler.program.setAnchorStart(isAnchorStart);
            compiler.program.setAnchorEnd(isAnchorEnd);
        }

        compiler.program.setStart(compiledFragment.begin);
        if (!compiler.program.anchorStart()) {
            // Unanchored entry: prepend .*? loop.
            compiledFragment = compiler.concatenate(compiler.dotStar(), compiledFragment);
        }
        compiler.program.setStartUnanchored(compiledFragment.begin);
        compiler.program.setCanMatchEmpty(canMatchEmpty);
        compiler.skipDominatorPass = stage.ordinal() >= CompileStage.FLATTENED.ordinal() &&
                shouldSkipDominatorPass(simplifiedRegexp, reversed, compiler.program.size());

        Prog compiledProgram = compiler.finish(stage);

        // Match upstream: configure prefix acceleration on forward programs.
        if (configureForwardMetadata && !reversed) {
            Regexp.RequiredPrefixForAccelResult requiredPrefixForAccel = regexp.requiredPrefixForAccel();
            if (requiredPrefixForAccel != null) {
                compiledProgram.configurePrefixAccel(requiredPrefixForAccel.prefix(), requiredPrefixForAccel.foldCase());
            }

            // Configure required prefix for instant rejection.
            // Pattern must be "^literal..." for this optimization.
            Regexp.RequiredPrefixResult requiredPrefix = regexp.requiredPrefix();
            if (requiredPrefix != null) {
                compiledProgram.configureRequiredPrefix(requiredPrefix.prefix(), requiredPrefix.foldCase());
            }

            compiledProgram.setMatchesAnyByteString(matchesAnyByteString(regexp));
        }

        return compiledProgram;
    }

    static boolean shouldSkipDominatorPass(Regexp regexp, boolean reversed, int instructionCount)
    {
        return instructionCount >= DOMINATOR_ELISION_MINIMUM_INSTRUCTIONS &&
                DominatorElisionAnalysis.canSkipDominatorPass(regexp, reversed);
    }

    /**
     * Detects patterns that match every possible byte sequence, such as {@code \C*}.
     * <p>
     * UTF-8 {@code (?s).*} is deliberately excluded because it rejects invalid UTF-8.
     */
    private static boolean matchesAnyByteString(Regexp regexp)
    {
        if (regexp.op() != RegexpOp.STAR) {
            return false;
        }
        Regexp operand = regexp.child(0);
        return operand.op() == RegexpOp.ANY_BYTE ||
                (operand.op() == RegexpOp.ANY_CHAR && (operand.parseFlags() & Regexp.LATIN1) != 0);
    }

    // These checks mirror compile.cc IsAnchorStart/IsAnchorEnd so anchors can be removed
    // without changing the language accepted by the compiled program.
    // Conservative detection (bounded recursion depth) that also removes the anchor ops
    // by rewriting them to empty literal strings (str{}), which compile as nops.
    private record AnchorStripResult(boolean anchored, Regexp regexp) {}

    private static AnchorStripResult stripAnchorStart(Regexp regexp, int depth)
    {
        if (regexp == null || depth >= 4) {
            return new AnchorStripResult(false, regexp);
        }

        return switch (regexp.op()) {
            case CONCAT -> {
                if (regexp.childCount() == 0) {
                    yield new AnchorStripResult(false, regexp);
                }
                AnchorStripResult strippedFirstChild = stripAnchorStart(regexp.child(0), depth + 1);
                if (!strippedFirstChild.anchored()) {
                    yield new AnchorStripResult(false, regexp);
                }
                ArrayList<Regexp> updatedChildren = new ArrayList<>(regexp.childCount());
                updatedChildren.add(strippedFirstChild.regexp());
                for (int childIndex = 1; childIndex < regexp.childCount(); childIndex++) {
                    updatedChildren.add(regexp.child(childIndex));
                }
                yield new AnchorStripResult(true, Regexp.concat(regexp.parseFlags(), updatedChildren));
            }
            case CAPTURE -> {
                AnchorStripResult strippedCaptureChild = stripAnchorStart(regexp.child(0), depth + 1);
                if (!strippedCaptureChild.anchored()) {
                    yield new AnchorStripResult(false, regexp);
                }
                yield new AnchorStripResult(true, Regexp.capture(regexp.parseFlags(), strippedCaptureChild.regexp(), regexp.captureIndex(), regexp.name()));
            }
            case BEGIN_TEXT -> new AnchorStripResult(true, Regexp.literalString(regexp.parseFlags(), new int[0]));
            default -> new AnchorStripResult(false, regexp);
        };
    }

    private static AnchorStripResult stripAnchorEnd(Regexp regexp, int depth)
    {
        if (regexp == null || depth >= 4) {
            return new AnchorStripResult(false, regexp);
        }

        return switch (regexp.op()) {
            case CONCAT -> {
                if (regexp.childCount() == 0) {
                    yield new AnchorStripResult(false, regexp);
                }
                int lastChildIndex = regexp.childCount() - 1;
                AnchorStripResult strippedLastChild = stripAnchorEnd(regexp.child(lastChildIndex), depth + 1);
                if (!strippedLastChild.anchored()) {
                    yield new AnchorStripResult(false, regexp);
                }
                ArrayList<Regexp> updatedChildren = new ArrayList<>(regexp.childCount());
                for (int childIndex = 0; childIndex < lastChildIndex; childIndex++) {
                    updatedChildren.add(regexp.child(childIndex));
                }
                updatedChildren.add(strippedLastChild.regexp());
                yield new AnchorStripResult(true, Regexp.concat(regexp.parseFlags(), updatedChildren));
            }
            case CAPTURE -> {
                AnchorStripResult strippedCaptureChild = stripAnchorEnd(regexp.child(0), depth + 1);
                if (!strippedCaptureChild.anchored()) {
                    yield new AnchorStripResult(false, regexp);
                }
                yield new AnchorStripResult(true, Regexp.capture(regexp.parseFlags(), strippedCaptureChild.regexp(), regexp.captureIndex(), regexp.name()));
            }
            case END_TEXT -> (regexp.parseFlags() & (Regexp.FINAL_LINE_END | Regexp.JAVA_FINAL_END)) == 0
                    ? new AnchorStripResult(true, Regexp.literalString(regexp.parseFlags(), new int[0]))
                    : new AnchorStripResult(false, regexp);
            default -> new AnchorStripResult(false, regexp);
        };
    }

    private static final class DominatorElisionAnalysis
    {
        private DominatorElisionAnalysis() {}

        private static boolean canSkipDominatorPass(Regexp regexp, boolean reversed)
        {
            if (regexp.childCount() == 0) {
                return false;
            }

            Regexp[] regexpStack = new Regexp[32];
            int[] nextChildStack = new int[32];
            boolean[] nullableStack = new boolean[32];
            boolean[] hasAlternationStack = new boolean[32];

            int depth = 0;
            int visits = 1;
            regexpStack[0] = regexp;
            if (!initialize(regexp, nullableStack, hasAlternationStack, 0)) {
                return false;
            }

            while (true) {
                Regexp current = regexpStack[depth];
                int childIndex = nextChildStack[depth];
                if (childIndex < current.childCount()) {
                    if (++visits > DOMINATOR_ELISION_MAXIMUM_VISITS) {
                        return false;
                    }
                    nextChildStack[depth] = childIndex + 1;
                    depth++;
                    if (depth == regexpStack.length) {
                        int newSize = regexpStack.length * 2;
                        regexpStack = Arrays.copyOf(regexpStack, newSize);
                        nextChildStack = Arrays.copyOf(nextChildStack, newSize);
                        nullableStack = Arrays.copyOf(nullableStack, newSize);
                        hasAlternationStack = Arrays.copyOf(hasAlternationStack, newSize);
                    }

                    Regexp child = current.child(childIndex);
                    regexpStack[depth] = child;
                    nextChildStack[depth] = 0;
                    if (!initialize(child, nullableStack, hasAlternationStack, depth)) {
                        return false;
                    }
                    continue;
                }

                boolean nullable = nullableStack[depth];
                boolean hasAlternation = hasAlternationStack[depth];
                regexpStack[depth] = null;
                if (depth == 0) {
                    return !nullable && hasAlternation;
                }

                depth--;
                Regexp parent = regexpStack[depth];
                hasAlternationStack[depth] |= hasAlternation;
                switch (parent.op()) {
                    case ALTERNATE -> nullableStack[depth] |= nullable;
                    case CONCAT -> {
                        int completedChild = nextChildStack[depth] - 1;
                        int terminalChild = reversed ? 0 : parent.childCount() - 1;
                        if (nullable && completedChild != terminalChild) {
                            return false;
                        }
                        nullableStack[depth] &= nullable;
                    }
                    default -> throw new IllegalStateException("unexpected parent operation: " + parent.op());
                }
            }
        }

        private static boolean initialize(
                Regexp regexp,
                boolean[] nullableStack,
                boolean[] hasAlternationStack,
                int depth)
        {
            hasAlternationStack[depth] = false;
            switch (regexp.op()) {
                case NO_MATCH, LITERAL, CHAR_CLASS -> nullableStack[depth] = false;
                case EMPTY_MATCH -> nullableStack[depth] = true;
                case LITERAL_STRING -> nullableStack[depth] = regexp.runes().length == 0;
                case ALTERNATE -> {
                    if (regexp.childCount() == 0) {
                        return false;
                    }
                    nullableStack[depth] = false;
                    hasAlternationStack[depth] = true;
                }
                case CONCAT -> {
                    if (regexp.childCount() == 0) {
                        return false;
                    }
                    nullableStack[depth] = true;
                }
                default -> {
                    return false;
                }
            }

            return (regexp.op() != RegexpOp.LITERAL && regexp.op() != RegexpOp.LITERAL_STRING) ||
                    (regexp.parseFlags() & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) == 0;
        }
    }

    private enum Encoding
    {
        UTF8,
        LATIN1,
    }

    private static final class CompilerState
    {
        final Prog program = new Prog();
        // Rune-range compilation state shared while building one program.
        private final RuneCache runeCache = new RuneCache();
        private final Fragment runeRange = new Fragment();

        boolean reversed;
        boolean setAnchorBoth;
        boolean skipDominatorPass;
        Dialect dialect;

        Encoding encoding = Encoding.UTF8;
        int maxInstructions;
        long maxMemory;

        void setup(int flags, long maxMemory, Dialect dialect)
        {
            this.dialect = requireNonNull(dialect, "dialect is null");
            this.maxMemory = maxMemory;
            if ((flags & Regexp.LATIN1) != 0) {
                encoding = Encoding.LATIN1;
            }
            if (maxMemory <= 0) {
                maxInstructions = 100_000;  // more than enough
                return;
            }
            if (maxMemory <= CPP_PROG_OVERHEAD_BYTES) {
                throw new RegexpCompileMemoryLimitException(maxMemory);
            }

            long availableInstructionCount = (maxMemory - CPP_PROG_OVERHEAD_BYTES) / CPP_INSTRUCTION_BYTES;
            if (availableInstructionCount >= MAX_INSTRUCTIONS) {
                availableInstructionCount = MAX_INSTRUCTIONS;
            }
            maxInstructions = (int) availableInstructionCount;
        }

        /**
         * Compiles every node after its children, in the same order and with the same visit limit as
         * {@link RegexpWalker#walkExponential}, without allocating per-node walker state. When a node
         * is compiled, its children's fragments are the top {@code childCount} entries of the
         * fragment stack.
         */
        private Fragment compileTree(Regexp root, int maxVisits)
        {
            Regexp[] nodes = new Regexp[16];
            int[] nextChildIndexes = new int[16];
            Fragment[] childFragments = new Fragment[16];
            int depth = 1;
            int childFragmentCount = 0;
            int visitCount = 1;
            nodes[0] = root;
            while (true) {
                Regexp node = nodes[depth - 1];
                int childIndex = nextChildIndexes[depth - 1];
                if (childIndex < node.childCount()) {
                    nextChildIndexes[depth - 1] = childIndex + 1;
                    if (++visitCount > maxVisits) {
                        throw new RegexpCompileException("regexp compilation exceeded walkExponential limit");
                    }
                    if (depth == nodes.length) {
                        nodes = Arrays.copyOf(nodes, depth * 2);
                        nextChildIndexes = Arrays.copyOf(nextChildIndexes, depth * 2);
                    }
                    nodes[depth] = node.child(childIndex);
                    nextChildIndexes[depth] = 0;
                    depth++;
                    continue;
                }

                int childCount = node.childCount();
                int firstChildIndex = childFragmentCount - childCount;
                Fragment fragment = compileNode(node, childFragments, firstChildIndex, childCount);
                Arrays.fill(childFragments, firstChildIndex, childFragmentCount, null);
                childFragmentCount = firstChildIndex;
                nodes[--depth] = null;
                if (depth == 0) {
                    return fragment;
                }
                if (childFragmentCount == childFragments.length) {
                    childFragments = Arrays.copyOf(childFragments, childFragmentCount * 2);
                }
                childFragments[childFragmentCount++] = fragment;
            }
        }

        private Fragment compileNode(Regexp regexp, Fragment[] childFragments, int firstChildIndex, int childCount)
        {
            return switch (regexp.op()) {
                case NO_MATCH -> noMatch();
                case EMPTY_MATCH -> noOperation();
                case HAVE_MATCH -> {
                    Fragment fragment = match(regexp.matchId());
                    if (setAnchorBoth) {
                        fragment = concatenate(emptyWidth(EmptyOp.EMPTY_END_TEXT), fragment);
                    }
                    yield fragment;
                }
                case CONCAT -> {
                    Fragment fragment = childFragments[firstChildIndex];
                    for (int childIndex = 1; childIndex < childCount; childIndex++) {
                        fragment = concatenate(fragment, childFragments[firstChildIndex + childIndex]);
                    }
                    yield fragment;
                }
                case ALTERNATE -> {
                    Fragment fragment = childFragments[firstChildIndex];
                    for (int childIndex = 1; childIndex < childCount; childIndex++) {
                        fragment = alternate(fragment, childFragments[firstChildIndex + childIndex]);
                    }
                    yield fragment;
                }
                case STAR -> repeatUnbounded(childFragments[firstChildIndex], (regexp.parseFlags() & Regexp.NON_GREEDY) != 0, true);
                case PLUS -> repeatUnbounded(childFragments[firstChildIndex], (regexp.parseFlags() & Regexp.NON_GREEDY) != 0, false);
                case QUEST -> quest(childFragments[firstChildIndex], (regexp.parseFlags() & Regexp.NON_GREEDY) != 0);
                case REPEAT -> throw new RegexpCompileException("REPEAT must be simplified before compilation");
                case CAPTURE -> capture(childFragments[firstChildIndex], regexp.captureIndex());
                case LITERAL -> (regexp.parseFlags() & Regexp.FULL_CASE_FOLD) != 0
                        ? fullCaseFoldLiteral(new int[] {regexp.rune()})
                        : literal(
                        regexp.rune(),
                        (regexp.parseFlags() & Regexp.FOLD_CASE) != 0,
                        (regexp.parseFlags() & Regexp.LATIN1) != 0);
                case LITERAL_STRING -> (regexp.parseFlags() & Regexp.FULL_CASE_FOLD) != 0
                        ? fullCaseFoldLiteral(regexp.runes())
                        : literalString(
                        regexp.runes(),
                        (regexp.parseFlags() & Regexp.FOLD_CASE) != 0,
                        (regexp.parseFlags() & Regexp.LATIN1) != 0);
                case ANY_CHAR -> anyChar();
                case ANY_BYTE -> byteRange(0x00, 0xFF, false);
                case CHAR_CLASS -> charClass(regexp.charClass());
                case BEGIN_LINE -> emptyWidth(beginLineAssertion(regexp, reversed));
                case END_LINE -> emptyWidth((regexp.parseFlags() & Regexp.JAVA_LINE) != 0
                        ? (reversed ? EmptyOp.EMPTY_JAVA_BEGIN_LINE : EmptyOp.EMPTY_JAVA_END_LINE)
                        : (reversed ? EmptyOp.EMPTY_BEGIN_LINE : EmptyOp.EMPTY_END_LINE));
                case BEGIN_TEXT -> emptyWidth(reversed ? EmptyOp.EMPTY_END_TEXT : EmptyOp.EMPTY_BEGIN_TEXT);
                case END_TEXT -> emptyWidth(endTextAssertion(regexp, reversed));
                case WORD_BOUNDARY -> emptyWidth(wordBoundaryAssertion(regexp, false));
                case NO_WORD_BOUNDARY -> emptyWidth(wordBoundaryAssertion(regexp, true));
            };
        }

        private static int beginLineAssertion(Regexp regexp, boolean reversed)
        {
            int line = reversed ? EmptyOp.EMPTY_END_LINE : EmptyOp.EMPTY_BEGIN_LINE;
            if ((regexp.parseFlags() & Regexp.TRINO_LINE) != 0) {
                return reversed ? EmptyOp.EMPTY_TRINO_END_LINE : EmptyOp.EMPTY_TRINO_BEGIN_LINE;
            }
            if ((regexp.parseFlags() & Regexp.JAVA_LINE) == 0) {
                return line;
            }
            int javaLine = reversed ? EmptyOp.EMPTY_JAVA_END_LINE : EmptyOp.EMPTY_JAVA_BEGIN_LINE;
            // Java multiline caret excludes end-of-input even with UNIX_LINES.
            // Intersecting the existing assertions restricts it to LF boundaries.
            return (regexp.parseFlags() & Regexp.JAVA_UNIX_LINES) != 0 ? line | javaLine : javaLine;
        }

        private static int endTextAssertion(Regexp regexp, boolean reversed)
        {
            if ((regexp.parseFlags() & Regexp.JAVA_FINAL_END) != 0) {
                return EmptyOp.EMPTY_JAVA_END_TEXT_OR_FINAL_TERMINATOR;
            }
            if ((regexp.parseFlags() & Regexp.FINAL_LINE_END) != 0) {
                return EmptyOp.EMPTY_END_TEXT_OR_FINAL_NEWLINE;
            }
            return reversed ? EmptyOp.EMPTY_BEGIN_TEXT : EmptyOp.EMPTY_END_TEXT;
        }

        private static int wordBoundaryAssertion(Regexp regexp, boolean negate)
        {
            if ((regexp.parseFlags() & Regexp.JAVA_WORD_BOUNDARY) != 0) {
                boolean unicodeCharacterClass = (regexp.parseFlags() & Regexp.JAVA_UNICODE_CHARACTER_CLASS) != 0;
                if (unicodeCharacterClass) {
                    return negate ? EmptyOp.EMPTY_JAVA_NO_UNICODE_WORD_BOUNDARY : EmptyOp.EMPTY_JAVA_UNICODE_WORD_BOUNDARY;
                }
                return negate ? EmptyOp.EMPTY_JAVA_NO_WORD_BOUNDARY : EmptyOp.EMPTY_JAVA_WORD_BOUNDARY;
            }
            if ((regexp.parseFlags() & Regexp.UNICODE_WORD_BOUNDARY) != 0) {
                return negate ? EmptyOp.EMPTY_NO_UNICODE_WORD_BOUNDARY : EmptyOp.EMPTY_UNICODE_WORD_BOUNDARY;
            }
            return negate ? EmptyOp.EMPTY_NO_WORD_BOUNDARY : EmptyOp.EMPTY_WORD_BOUNDARY;
        }

        private Prog finish(CompileStage stage)
        {
            if (stage.ordinal() >= CompileStage.OPTIMIZED.ordinal()) {
                program.optimize();
            }
            if (stage.ordinal() >= CompileStage.FLATTENED.ordinal()) {
                if (skipDominatorPass) {
                    program.flatten(true);
                }
                else {
                    program.flatten();
                }
            }
            if (stage.ordinal() >= CompileStage.BYTEMAP.ordinal()) {
                program.computeByteMap();
            }
            if (stage == CompileStage.BYTEMAP) {
                long dfaMemory = DEFAULT_DFA_MEMORY;
                if (maxMemory > 0) {
                    dfaMemory = maxMemory - CPP_PROG_OVERHEAD_BYTES - ((long) program.size() * CPP_INSTRUCTION_BYTES);
                    if (program.canBitState()) {
                        dfaMemory -= (long) program.size() * CPP_LIST_HEAD_BYTES;
                    }
                    dfaMemory = Math.max(0, dfaMemory);
                }
                program.setDfaMemory(dfaMemory);
            }
            return program;
        }

        private int add(Prog.Inst instruction)
        {
            if (program.size() + 1 > maxInstructions) {
                throw new RegexpCompileMemoryLimitException(maxMemory);
            }
            return program.add(instruction);
        }

        private Fragment noMatch()
        {
            return new Fragment();
        }

        private static boolean isNoMatch(Fragment fragment)
        {
            return fragment.begin == 0;
        }

        private Fragment concatenate(Fragment left, Fragment right)
        {
            if (isNoMatch(left) || isNoMatch(right)) {
                return noMatch();
            }

            // Elide no-op.
            Prog.Inst begin = program.inst(left.begin);
            if (begin.opcode() == InstOp.NOP &&
                    PatchList.head(left.end) == (left.begin << 1) &&
                    begin.out() == 0) {
                PatchList.patch(program, left.end, right.begin);
                return right;
            }

            if (reversed) {
                PatchList.patch(program, right.end, left.begin);
                return new Fragment(right.begin, left.end, right.nullable && left.nullable);
            }

            PatchList.patch(program, left.end, right.begin);
            return new Fragment(left.begin, right.end, left.nullable && right.nullable);
        }

        private Fragment alternate(Fragment left, Fragment right)
        {
            if (isNoMatch(left)) {
                return right;
            }
            if (isNoMatch(right)) {
                return left;
            }

            int id = add(Prog.Inst.createAlt(left.begin, right.begin));
            if (id < 0) {
                return noMatch();
            }

            return new Fragment(id, PatchList.append(program, left.end, right.end), left.nullable || right.nullable);
        }

        private Fragment repeatUnbounded(Fragment fragment, boolean nonGreedy, boolean zeroIterationsAllowed)
        {
            if (dialect == Dialect.TRINO && fragment.nullable && !nonGreedy) {
                return trinoGreedyNullableLoop(fragment, zeroIterationsAllowed);
            }
            return zeroIterationsAllowed ? star(fragment, nonGreedy) : plus(fragment, nonGreedy);
        }

        private Fragment trinoGreedyNullableLoop(Fragment fragment, boolean zeroIterationsAllowed)
        {
            Fragment consumedExit = noOperation();
            PatchList.patch(program, fragment.end, consumedExit.begin);

            Fragment emptyExit = noOperation();
            int originalInstructionLimit = program.size();
            int[] clones = new int[originalInstructionLimit];
            clones[consumedExit.begin] = emptyExit.begin;
            SparseSet pending = new SparseSet(originalInstructionLimit);
            int loopEntry = clonePreConsumptionInstruction(
                    fragment.begin,
                    consumedExit.begin,
                    emptyExit.begin,
                    clones,
                    pending,
                    originalInstructionLimit);

            for (int pendingIndex = 0; pendingIndex < pending.size(); pendingIndex++) {
                int originalId = pending.denseAt(pendingIndex);
                Prog.Inst original = program.inst(originalId);
                Prog.Inst clone = program.inst(clones[originalId]);
                switch (original.opcode()) {
                    case ALT -> {
                        clone.setOut(clonePreConsumptionInstruction(
                                original.out(),
                                consumedExit.begin,
                                emptyExit.begin,
                                clones,
                                pending,
                                originalInstructionLimit));
                        clone.setOut1(clonePreConsumptionInstruction(
                                original.out1(),
                                consumedExit.begin,
                                emptyExit.begin,
                                clones,
                                pending,
                                originalInstructionLimit));
                    }
                    case CAPTURE, EMPTY_WIDTH, NOP -> clone.setOut(clonePreConsumptionInstruction(
                            original.out(),
                            consumedExit.begin,
                            emptyExit.begin,
                            clones,
                            pending,
                            originalInstructionLimit));
                    case BYTE_RANGE -> throw new AssertionError("byte instructions are not pending");
                    case ALT_MATCH, MATCH, FAIL -> throw new RegexpCompileException("invalid nullable-loop instruction: " + original.opcode());
                }
            }

            Fragment optionalIteration = quest(new Fragment(loopEntry, emptyExit.end, true), false);
            PatchList.patch(program, consumedExit.end, optionalIteration.begin);
            if (zeroIterationsAllowed) {
                return optionalIteration;
            }
            // PLUS must enter the body once, but every boundary after a consuming iteration may
            // stop if the structurally nullable body cannot match at the new input position.
            return new Fragment(loopEntry, optionalIteration.end, true);
        }

        private int clonePreConsumptionInstruction(
                int originalId,
                int consumedExit,
                int emptyExit,
                int[] clones,
                SparseSet pending,
                int originalInstructionLimit)
        {
            if (originalId == consumedExit) {
                return emptyExit;
            }
            if (originalId <= 0 || originalId >= originalInstructionLimit) {
                throw new RegexpCompileException("invalid nullable-loop instruction: " + originalId);
            }
            int existing = clones[originalId];
            if (existing != 0) {
                return existing;
            }

            Prog.Inst original = program.inst(originalId);
            Prog.Inst clone = original.copy();
            switch (original.opcode()) {
                case BYTE_RANGE -> {
                    // The cloned first byte enters the original graph after making progress.
                }
                case ALT -> {
                    clone.setOut(0);
                    clone.setOut1(0);
                    pending.insertNew(originalId);
                }
                case CAPTURE, EMPTY_WIDTH, NOP -> {
                    clone.setOut(0);
                    pending.insertNew(originalId);
                }
                case ALT_MATCH, MATCH, FAIL -> throw new RegexpCompileException("invalid nullable-loop instruction: " + original.opcode());
            }
            int cloneId = add(clone);
            clones[originalId] = cloneId;
            return cloneId;
        }

        private Fragment plus(Fragment fragment, boolean nonGreedy)
        {
            int id = add(Prog.Inst.createAlt(0, 0));
            if (id < 0) {
                return noMatch();
            }

            long patchList;
            if (nonGreedy) {
                program.inst(id).setOut(0);
                program.inst(id).setOut1(fragment.begin);
                patchList = PatchList.create(id << 1);
            }
            else {
                program.inst(id).setOut(fragment.begin);
                program.inst(id).setOut1(0);
                patchList = PatchList.create((id << 1) | 1);
            }
            PatchList.patch(program, fragment.end, id);
            return new Fragment(fragment.begin, patchList, fragment.nullable);
        }

        private Fragment star(Fragment fragment, boolean nonGreedy)
        {
            if (fragment.nullable) {
                return quest(plus(fragment, nonGreedy), nonGreedy);
            }

            int id = add(Prog.Inst.createAlt(0, 0));
            if (id < 0) {
                return noMatch();
            }

            long patchList;
            if (nonGreedy) {
                program.inst(id).setOut(0);
                program.inst(id).setOut1(fragment.begin);
                patchList = PatchList.create(id << 1);
            }
            else {
                program.inst(id).setOut(fragment.begin);
                program.inst(id).setOut1(0);
                patchList = PatchList.create((id << 1) | 1);
            }
            PatchList.patch(program, fragment.end, id);
            return new Fragment(id, patchList, true);
        }

        private Fragment quest(Fragment fragment, boolean nonGreedy)
        {
            if (isNoMatch(fragment)) {
                return noOperation();
            }

            int id = add(Prog.Inst.createAlt(0, 0));
            if (id < 0) {
                return noMatch();
            }

            long patchList;
            if (nonGreedy) {
                program.inst(id).setOut(0);
                program.inst(id).setOut1(fragment.begin);
                patchList = PatchList.create(id << 1);
            }
            else {
                program.inst(id).setOut(fragment.begin);
                program.inst(id).setOut1(0);
                patchList = PatchList.create((id << 1) | 1);
            }
            return new Fragment(id, PatchList.append(program, patchList, fragment.end), true);
        }

        private Fragment capture(Fragment fragment, int captureIndex)
        {
            if (isNoMatch(fragment)) {
                return noMatch();
            }

            if (program.size() + 2 > maxInstructions) {
                throw new RegexpCompileMemoryLimitException(maxMemory);
            }

            int startId = program.add(Prog.Inst.createCapture(2 * captureIndex, fragment.begin));
            int endId = program.add(Prog.Inst.createCapture(2 * captureIndex + 1, 0));

            PatchList.patch(program, fragment.end, endId);
            return new Fragment(startId, PatchList.create(endId << 1), fragment.nullable);
        }

        private Fragment match(int matchId)
        {
            int id = add(Prog.Inst.createMatch(matchId));
            if (id < 0) {
                return noMatch();
            }
            return new Fragment(id, PatchList.EMPTY, false);
        }

        private Fragment noOperation()
        {
            int id = add(Prog.Inst.createNop(0));
            if (id < 0) {
                return noMatch();
            }
            return new Fragment(id, PatchList.create(id << 1), true);
        }

        private Fragment emptyWidth(int empty)
        {
            int id = add(Prog.Inst.createEmptyWidth(empty, 0));
            if (id < 0) {
                return noMatch();
            }
            return new Fragment(id, PatchList.create(id << 1), true);
        }

        private Fragment byteRange(int low, int high, boolean foldCase)
        {
            int id = add(Prog.Inst.createByteRange(low, high, foldCase, 0));
            if (id < 0) {
                return noMatch();
            }
            return new Fragment(id, PatchList.create(id << 1), false);
        }

        private Fragment literal(int rune, boolean foldCase, boolean latin1)
        {
            if (latin1 || rune < 0x80) {
                return byteRange(rune, rune, foldCase);
            }
            byte[] utf8 = encodeUtf8(rune);
            Fragment fragment = byteRange(utf8[0] & 0xFF, utf8[0] & 0xFF, false);
            for (int i = 1; i < utf8.length; i++) {
                fragment = concatenate(fragment, byteRange(utf8[i] & 0xFF, utf8[i] & 0xFF, false));
            }
            return fragment;
        }

        private Fragment literalString(int[] runes, boolean foldCase, boolean latin1)
        {
            if (runes.length == 0) {
                return noOperation();
            }
            Fragment fragment = literal(runes[0], foldCase, latin1);
            for (int i = 1; i < runes.length; i++) {
                fragment = concatenate(fragment, literal(runes[i], foldCase, latin1));
            }
            return fragment;
        }

        private Fragment fullCaseFoldLiteral(int[] runes)
        {
            if (!UnicodeFullCaseFold.requiresFullCaseFold(runes)) {
                Fragment fragment = charClass(UnicodeFullCaseFold.simpleFoldClass(runes[0]));
                for (int index = 1; index < runes.length; index++) {
                    fragment = concatenate(fragment, charClass(UnicodeFullCaseFold.simpleFoldClass(runes[index])));
                }
                return fragment;
            }
            if (reversed) {
                throw new RegexpCompileException("full case-fold literals do not support reverse compilation");
            }
            program.setHasFullCaseFold(true);

            int[] foldedRunes = UnicodeFullCaseFold.fold(runes);
            Fragment terminal = noOperation();
            Fragment[] suffixes = new Fragment[foldedRunes.length + 1];
            suffixes[foldedRunes.length] = terminal;

            for (int index = foldedRunes.length - 1; index >= 0; index--) {
                List<Integer> branchStarts = new ArrayList<>();
                addFullCaseFoldBranch(
                        branchStarts,
                        UnicodeFullCaseFold.simpleFoldClass(foldedRunes[index]),
                        suffixes[index + 1]);

                for (UnicodeFullCaseFold.FoldToken token : UnicodeFullCaseFold.multiCharacterTokensStartingWith(foldedRunes[index])) {
                    if (token.matches(foldedRunes, index)) {
                        addFullCaseFoldBranch(
                                branchStarts,
                                token.sourceRunes(),
                                suffixes[index + token.foldedRunes().length]);
                    }
                }

                int begin = branchStarts.getFirst();
                for (int branchIndex = 1; branchIndex < branchStarts.size(); branchIndex++) {
                    begin = add(Prog.Inst.createAlt(begin, branchStarts.get(branchIndex)));
                }
                suffixes[index] = new Fragment(begin, terminal.end, false);
            }
            return suffixes[0];
        }

        private void addFullCaseFoldBranch(List<Integer> branchStarts, CharClass sourceRunes, Fragment suffix)
        {
            Fragment source = charClass(sourceRunes);
            PatchList.patch(program, source.end, suffix.begin);
            branchStarts.add(source.begin);
        }

        private Fragment dotStar()
        {
            return star(byteRange(0x00, 0xFF, false), true);
        }

        private Fragment anyChar()
        {
            // Parsers already represent newline-excluding dots as character classes.
            // ANY_CHAR also comes from full classes, whose meaning is independent of dot flags.
            beginRange();
            addRuneRange(0, Regexp.RUNEMAX, false);
            return endRange();
        }

        private Fragment charClass(CharClass characterClass)
        {
            if (characterClass.isEmpty()) {
                throw new RegexpCompileException("empty char class must be simplified before compilation");
            }

            boolean foldsAscii = characterClass.foldsAscii();

            beginRange();
            for (RuneRange runeRange : characterClass.ranges()) {
                if (foldsAscii && 'A' <= runeRange.low() && runeRange.high() <= 'Z') {
                    continue;
                }

                boolean shouldFold = foldsAscii;
                if ((runeRange.low() <= 'A' && 'z' <= runeRange.high()) || runeRange.high() < 'A' || 'z' < runeRange.low() ||
                        ('Z' < runeRange.low() && runeRange.high() < 'a')) {
                    shouldFold = false;
                }

                addRuneRange(runeRange.low(), runeRange.high(), shouldFold);
            }
            return endRange();
        }

        private void addRuneRange(int low, int high, boolean foldCase)
        {
            if (encoding == Encoding.LATIN1) {
                addRuneRangeLatin1(low, high, foldCase);
                return;
            }
            addRuneRangeUtf8(low, high, foldCase);
        }

        private void addRuneRangeLatin1(int low, int high, boolean foldCase)
        {
            if (low > high || low > 0xFF) {
                return;
            }
            if (high > 0xFF) {
                high = 0xFF;
            }
            addSuffix(uncachedRuneByteSuffix(low, high, foldCase, 0));
        }

        // Keep suffix caching and trie factoring aligned with compile.cc AddRuneRangeUTF8.
        private void addRuneRangeUtf8(int low, int high, boolean foldCase)
        {
            if (low > high) {
                return;
            }

            // Pick off 80-10FFFF as a common special case.
            if (low == 0x80 && high == Regexp.RUNEMAX) {
                add80To10ffff();
                return;
            }

            // Split range into same-length sized ranges.
            for (int i = 1; i < UTF_MAX; i++) {
                int max = maxRune(i);
                if (low <= max && max < high) {
                    addRuneRangeUtf8(low, max, foldCase);
                    addRuneRangeUtf8(max + 1, high, foldCase);
                    return;
                }
            }

            // ASCII range is always a special case.
            if (high < RUNES_SELF) {
                addSuffix(uncachedRuneByteSuffix(low, high, foldCase, 0));
                return;
            }

            // Split range into sections that agree on leading bytes.
            for (int i = 1; i < UTF_MAX; i++) {
                int mask = (1 << (6 * i)) - 1;  // last i bytes of a UTF-8 sequence
                if ((low & ~mask) != (high & ~mask)) {
                    if ((low & mask) != 0) {
                        addRuneRangeUtf8(low, low | mask, foldCase);
                        addRuneRangeUtf8((low | mask) + 1, high, foldCase);
                        return;
                    }
                    if ((high & mask) != mask) {
                        addRuneRangeUtf8(low, (high & ~mask) - 1, foldCase);
                        addRuneRangeUtf8(high & ~mask, high, foldCase);
                        return;
                    }
                }
            }

            // Finally, generate the byte-matching equivalent for the range.
            byte[] encodedLow = encodeUtf8(low);
            byte[] encodedHigh = encodeUtf8(high);
            if (encodedLow.length != encodedHigh.length) {
                throw new RegexpCompileException("invalid UTF-8 rune range split");
            }

            int id = 0;
            if (reversed) {
                for (int i = 0; i < encodedLow.length; i++) {
                    // In reverse UTF-8 mode: cache the leading byte; don't cache the last
                    // continuation byte; cache anything else iff it's a single byte (XX-XX).
                    int byteLow = encodedLow[i] & 0xFF;
                    int byteHigh = encodedHigh[i] & 0xFF;
                    if (i == 0 || (byteLow == byteHigh && i != encodedLow.length - 1)) {
                        id = cachedRuneByteSuffix(byteLow, byteHigh, false, id);
                    }
                    else {
                        id = uncachedRuneByteSuffix(byteLow, byteHigh, false, id);
                    }
                }
            }
            else {
                for (int i = encodedLow.length - 1; i >= 0; i--) {
                    // In forward UTF-8 mode: don't cache the leading byte; cache the last
                    // continuation byte; cache anything else iff it's a byte range (XX-YY).
                    int byteLow = encodedLow[i] & 0xFF;
                    int byteHigh = encodedHigh[i] & 0xFF;
                    if (i == encodedLow.length - 1 || (byteLow < byteHigh && i != 0)) {
                        id = cachedRuneByteSuffix(byteLow, byteHigh, false, id);
                    }
                    else {
                        id = uncachedRuneByteSuffix(byteLow, byteHigh, false, id);
                    }
                }
            }
            addSuffix(id);
        }

        // This decomposition follows compile.cc Add_80_10ffff to preserve its compact trie shape.
        private void add80To10ffff()
        {
            int id;
            if (reversed) {
                // Prefix factoring matters, but we don't have to handle it here
                // because the rune range trie logic takes care of that already.
                id = uncachedRuneByteSuffix(0xC2, 0xDF, false, 0);
                id = uncachedRuneByteSuffix(0x80, 0xBF, false, id);
                addSuffix(id);

                id = uncachedRuneByteSuffix(0xE0, 0xEF, false, 0);
                id = uncachedRuneByteSuffix(0x80, 0xBF, false, id);
                id = uncachedRuneByteSuffix(0x80, 0xBF, false, id);
                addSuffix(id);

                id = uncachedRuneByteSuffix(0xF0, 0xF4, false, 0);
                id = uncachedRuneByteSuffix(0x80, 0xBF, false, id);
                id = uncachedRuneByteSuffix(0x80, 0xBF, false, id);
                id = uncachedRuneByteSuffix(0x80, 0xBF, false, id);
                addSuffix(id);
            }
            else {
                // Suffix factoring matters - and we do have to handle it here.
                int oneContinuationByte = uncachedRuneByteSuffix(0x80, 0xBF, false, 0);
                id = uncachedRuneByteSuffix(0xC2, 0xDF, false, oneContinuationByte);
                addSuffix(id);

                int twoContinuationBytes = uncachedRuneByteSuffix(0x80, 0xBF, false, oneContinuationByte);
                id = uncachedRuneByteSuffix(0xE0, 0xEF, false, twoContinuationBytes);
                addSuffix(id);

                int threeContinuationBytes = uncachedRuneByteSuffix(0x80, 0xBF, false, twoContinuationBytes);
                id = uncachedRuneByteSuffix(0xF0, 0xF4, false, threeContinuationBytes);
                addSuffix(id);
            }
        }

        // Rune-range compilation helpers.
        private void beginRange()
        {
            runeCache.clear();
            runeRange.begin = 0;
            runeRange.end = PatchList.EMPTY;
            runeRange.nullable = false;
        }

        private Fragment endRange()
        {
            // Important: runeRange is compiler scratch state reused across range compilations.
            // Return a copy so subsequent beginRange()/addSuffix() calls can't mutate previously
            // returned fragments (which would corrupt PatchLists and can create cycles).
            return new Fragment(runeRange.begin, runeRange.end, runeRange.nullable);
        }

        private int uncachedRuneByteSuffix(int low, int high, boolean foldCase, int next)
        {
            Fragment fragment = byteRange(low, high, foldCase);
            if (isNoMatch(fragment)) {
                return 0;
            }
            if (next != 0) {
                PatchList.patch(program, fragment.end, next);
            }
            else {
                runeRange.end = PatchList.append(program, runeRange.end, fragment.end);
            }
            return fragment.begin;
        }

        private static long makeRuneCacheKey(int low, int high, boolean foldCase, int next)
        {
            // Match compile.cc MakeRuneCacheKey so equivalent suffixes share instructions.
            return ((long) next << 17) |
                    ((long) (low & 0xFF) << 9) |
                    ((long) (high & 0xFF) << 1) |
                    (foldCase ? 1L : 0L);
        }

        private int cachedRuneByteSuffix(int low, int high, boolean foldCase, int next)
        {
            long key = makeRuneCacheKey(low, high, foldCase, next);
            int existing = runeCache.get(key);
            if (existing != 0) {
                return existing;
            }
            int id = uncachedRuneByteSuffix(low, high, foldCase, next);
            runeCache.put(key, id);
            return id;
        }

        private boolean isCachedRuneByteSuffix(int id)
        {
            Prog.Inst instruction = program.inst(id);
            long key = makeRuneCacheKey(instruction.lo(), instruction.hi(), instruction.foldCase(), instruction.out());
            return runeCache.containsKey(key);
        }

        private void addSuffix(int id)
        {
            if (id == 0) {
                throw new RegexpCompileException("invalid suffix fragment");
            }

            if (runeRange.begin == 0) {
                runeRange.begin = id;
                return;
            }

            if (encoding == Encoding.UTF8) {
                runeRange.begin = addSuffixRecursive(runeRange.begin, id);
                return;
            }

            int alternateId = add(Prog.Inst.createAlt(runeRange.begin, id));
            runeRange.begin = alternateId;
        }

        private boolean byteRangeEqual(int leftId, int rightId)
        {
            Prog.Inst left = program.inst(leftId);
            Prog.Inst right = program.inst(rightId);
            return left.lo() == right.lo() && left.hi() == right.hi() && left.foldCase() == right.foldCase();
        }

        private Fragment findByteRange(int root, int id)
        {
            Prog.Inst rootInstruction = program.inst(root);
            if (rootInstruction.opcode() == InstOp.BYTE_RANGE) {
                if (byteRangeEqual(root, id)) {
                    return new Fragment(root, PatchList.EMPTY, false);
                }
                return noMatch();
            }

            while (rootInstruction.opcode() == InstOp.ALT) {
                int alternateInstructionId = rootInstruction.out1();
                if (byteRangeEqual(alternateInstructionId, id)) {
                    return new Fragment(root, PatchList.create((root << 1) | 1), false);
                }

                // CharClass is a sorted list of ranges, so if the alternate branch wasn't
                // what we're looking for, then we can stop immediately. Unfortunately, we
                // can't short-circuit the search in reverse mode.
                if (!reversed) {
                    return noMatch();
                }

                int nextInstructionId = rootInstruction.out();
                Prog.Inst nextInstruction = program.inst(nextInstructionId);
                if (nextInstruction.opcode() == InstOp.ALT) {
                    root = nextInstructionId;
                    rootInstruction = nextInstruction;
                    continue;
                }
                if (byteRangeEqual(nextInstructionId, id)) {
                    return new Fragment(root, PatchList.create(root << 1), false);
                }
                return noMatch();
            }

            throw new RegexpCompileException("invalid rune suffix trie state");
        }

        private static final class RuneCache
        {
            private static final int INITIAL_CAPACITY = 16;

            private long[] keys;
            private int[] instructionIds;
            private int mask;
            private int maximumSize;
            private int size;

            public int get(long key)
            {
                if (instructionIds == null) {
                    return 0;
                }
                int index = hash(key) & mask;
                while (instructionIds[index] != 0) {
                    if (keys[index] == key) {
                        return instructionIds[index];
                    }
                    index = (index + 1) & mask;
                }
                return 0;
            }

            public boolean containsKey(long key)
            {
                return get(key) != 0;
            }

            public void put(long key, int instructionId)
            {
                if (instructionIds == null) {
                    keys = new long[INITIAL_CAPACITY];
                    instructionIds = new int[INITIAL_CAPACITY];
                    mask = INITIAL_CAPACITY - 1;
                    maximumSize = maximumSize(INITIAL_CAPACITY);
                }
                if (size >= maximumSize) {
                    resize();
                }
                putWithoutResize(key, instructionId);
            }

            public void clear()
            {
                if (instructionIds != null) {
                    Arrays.fill(instructionIds, 0);
                }
                size = 0;
            }

            private void resize()
            {
                long[] oldKeys = keys;
                int[] oldInstructionIds = instructionIds;

                int capacity = oldInstructionIds.length * 2;
                keys = new long[capacity];
                instructionIds = new int[capacity];
                mask = capacity - 1;
                maximumSize = maximumSize(capacity);
                size = 0;

                for (int index = 0; index < oldInstructionIds.length; index++) {
                    int instructionId = oldInstructionIds[index];
                    if (instructionId != 0) {
                        putWithoutResize(oldKeys[index], instructionId);
                    }
                }
            }

            private void putWithoutResize(long key, int instructionId)
            {
                int index = hash(key) & mask;
                while (instructionIds[index] != 0) {
                    if (keys[index] == key) {
                        instructionIds[index] = instructionId;
                        return;
                    }
                    index = (index + 1) & mask;
                }
                keys[index] = key;
                instructionIds[index] = instructionId;
                size++;
            }

            private static int maximumSize(int capacity)
            {
                return capacity - (capacity >>> 2);
            }

            private static int hash(long key)
            {
                key ^= key >>> 33;
                key *= 0xFF51AFD7ED558CCDL;
                key ^= key >>> 33;
                key *= 0xC4CEB9FE1A85EC53L;
                key ^= key >>> 33;
                return (int) key;
            }
        }

        private int addSuffixRecursive(int root, int id)
        {
            Prog.Inst rootInstruction = program.inst(root);
            if (rootInstruction.opcode() != InstOp.ALT &&
                    rootInstruction.opcode() != InstOp.BYTE_RANGE) {
                throw new RegexpCompileException("invalid rune suffix trie root");
            }

            Fragment matchingRange = findByteRange(root, id);
            if (isNoMatch(matchingRange)) {
                return add(Prog.Inst.createAlt(root, id));
            }

            int byteRangeId;
            int matchingPatchPointer = PatchList.head(matchingRange.end);
            if (matchingPatchPointer == 0) {
                byteRangeId = root;
            }
            else if ((matchingPatchPointer & 1) != 0) {
                byteRangeId = program.inst(matchingRange.begin).out1();
            }
            else {
                byteRangeId = program.inst(matchingRange.begin).out();
            }

            if (isCachedRuneByteSuffix(byteRangeId)) {
                // We can't fiddle with cached suffixes, so make a clone of the head.
                Prog.Inst head = program.inst(byteRangeId);
                int clone = add(Prog.Inst.createByteRange(head.lo(), head.hi(), head.foldCase(), head.out()));

                byteRangeId = clone;
                if (matchingPatchPointer == 0) {
                    root = byteRangeId;
                }
                else if ((matchingPatchPointer & 1) != 0) {
                    program.inst(matchingRange.begin).setOut1(byteRangeId);
                }
                else {
                    program.inst(matchingRange.begin).setOut(byteRangeId);
                }
            }

            int suffixInstructionId = program.inst(id).out();
            if (!isCachedRuneByteSuffix(id)) {
                // Free the head instead of leaving it unreachable.
                program.removeLastInst(id);
            }

            suffixInstructionId = addSuffixRecursive(program.inst(byteRangeId).out(), suffixInstructionId);
            program.inst(byteRangeId).setOut(suffixInstructionId);
            return root;
        }

        private static int maxRune(int length)
        {
            // Maximum rune encoded by a UTF-8 sequence of the specified length.
            int bits;
            if (length == 1) {
                bits = 7;
            }
            else {
                bits = 8 - (length + 1) + 6 * (length - 1);
            }
            return (1 << bits) - 1;
        }

        private static byte[] encodeUtf8(int rune)
        {
            if (rune < 0 || rune > Regexp.RUNEMAX) {
                throw new RegexpCompileException("invalid rune: " + rune);
            }
            byte[] bytes = new byte[Utf8.encodedLength(rune)];
            Utf8.encode(bytes, 0, rune);
            return bytes;
        }
    }

    /**
     * Packs the head and tail output pointers into one primitive compiler value.
     */
    private static final class PatchList
    {
        static final long EMPTY = 0;

        private PatchList() {}

        static long create(int patchPointer)
        {
            return pack(patchPointer, patchPointer);
        }

        static int head(long patchList)
        {
            return (int) (patchList >>> Integer.SIZE);
        }

        static void patch(Prog program, long patchList, int target)
        {
            int patchPointer = head(patchList);
            while (patchPointer != 0) {
                Prog.Inst instruction = program.inst(patchPointer >>> 1);
                if ((patchPointer & 1) != 0) {
                    patchPointer = instruction.out1();
                    instruction.setOut1(target);
                }
                else {
                    patchPointer = instruction.out();
                    instruction.setOut(target);
                }
            }
        }

        static long append(Prog program, long left, long right)
        {
            int leftHead = head(left);
            if (leftHead == 0) {
                return right;
            }
            int rightHead = head(right);
            if (rightHead == 0) {
                return left;
            }

            int leftTail = tail(left);
            Prog.Inst instruction = program.inst(leftTail >>> 1);
            if ((leftTail & 1) != 0) {
                instruction.setOut1(rightHead);
            }
            else {
                instruction.setOut(rightHead);
            }
            return pack(leftHead, tail(right));
        }

        private static int tail(long patchList)
        {
            return (int) patchList;
        }

        private static long pack(int head, int tail)
        {
            return ((long) head << Integer.SIZE) | (tail & 0xFFFF_FFFFL);
        }
    }

    private static final class Fragment
    {
        int begin;
        long end;
        boolean nullable;

        Fragment()
        {
            this(0, PatchList.EMPTY, false);
        }

        Fragment(int begin, long end, boolean nullable)
        {
            this.begin = begin;
            this.end = end;
            this.nullable = nullable;
        }
    }
}
