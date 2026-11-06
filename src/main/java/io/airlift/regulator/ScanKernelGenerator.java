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
import io.airlift.classfile.BytecodeExpressions;
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
import io.airlift.classfile.Statement;
import io.airlift.classfile.SwitchStatement;
import io.airlift.classfile.Variable;
import io.airlift.regulator.ScanPlan.Branch;
import io.airlift.regulator.ScanPlan.Kind;
import io.airlift.regulator.ScanPlan.Step;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceUtf8;

import java.io.IOException;
import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.zip.CRC32;

import static io.airlift.classfile.BytecodeExpressions.boundConstant;
import static io.airlift.classfile.BytecodeExpressions.constantBoolean;
import static io.airlift.classfile.BytecodeExpressions.constantFalse;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantNull;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.constantTrue;
import static io.airlift.classfile.BytecodeExpressions.getStatic;
import static io.airlift.classfile.BytecodeExpressions.inlineIf;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.newArray;
import static io.airlift.classfile.Parameter.arg;
import static io.airlift.regulator.ScanPlan.RETRY_OPERATION_BITS;
import static io.airlift.regulator.ScanPlan.RETRY_OPERATION_MASK;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.util.Objects.requireNonNull;

/// Compiles the case-sensitive [ScanPlan] of one pattern to a hidden class in this
/// package. The attempt loop becomes straight-line code with a label per operation, with the plan's
/// literals, slots, and branch targets as constants; retries keep the interpreter's stack encoding
/// in locals and resume through a switch over the saved operation index. Around it, the class
/// holds the candidate search of an unanchored plan. The class of a Trino pattern extends
/// [TrinoRegexp.CompiledScanTrinoRegexp] and declares a public entry for each operation the
/// pattern routes to the plan, so the pattern a caller holds is an instance of it.
///
/// An RE2, Java, or Rust pattern, compiled in the RE2 dialect, gets a smaller class from the same
/// attempt loop: a pattern class, which extends its language's `CompiledScan…` class, such as
/// [Re2.CompiledScanRe2]. It declares full-Slice `find`, `count`, `matches`, and `lookingAt`
/// for the operations that reach the plan and overrides the engine hooks that every other plan
/// call runs through, each with its route fixed when the class is generated. The instance of that
/// class is the pattern itself, so a caller's call site sees the generated class as its receiver.
///
/// Setting the system property {@value #DUMP_DIRECTORY_PROPERTY} writes each generated class to
/// that directory before it is defined.
final class ScanKernelGenerator
{
    private static final String DUMP_DIRECTORY_PROPERTY = "io.airlift.regulator.scan-kernel.dump-directory";

    private static final String DUMP_DIRECTORY = System.getProperty(DUMP_DIRECTORY_PROPERTY);
    // The type of the constructor of TrinoRegexp.CompiledScanTrinoRegexp and of its generated
    // subclass, which takes the compiled engine.
    private static final MethodType TRINO_CONSTRUCTOR_TYPE = MethodType.methodType(void.class, TrinoCompiledPattern.class);
    // Step.matches compares literals of up to this many bytes with two overlapping reads.
    private static final int INLINE_LITERAL_BYTES = 2 * Long.BYTES;

    private ScanKernelGenerator() {}

    enum Attempt
    {
        END_ANCHORED("matchEndAnchored", "EndAnchored"),
        PARTIAL("matchPartial", "Partial"),
        UNANCHORED("matchUnanchoredAt", "Unanchored");

        private static final MethodType UNANCHORED_TYPE = MethodType.methodType(long.class, byte[].class, int.class, int.class, int.class, boolean.class, int[].class);
        private static final MethodType ANCHORED_TYPE = MethodType.methodType(boolean.class, Slice.class, int.class, int.class, boolean.class, int[].class);

        private final String name;
        private final String classPrefix;

        Attempt(String name, String classPrefix)
        {
            this.name = name;
            this.classPrefix = classPrefix;
        }

        /// The name of the pattern class that extends `superclass`.
        String patternClassName(Class<?> superclass)
        {
            return superclass.getPackageName() + "." + classPrefix + superclass.getSimpleName();
        }

        static Attempt of(ScanPlan plan)
        {
            if (!plan.isAnchoredStart()) {
                return UNANCHORED;
            }
            return plan.isPartialMatch() ? PARTIAL : END_ANCHORED;
        }

        MethodType type()
        {
            return this == UNANCHORED ? UNANCHORED_TYPE : ANCHORED_TYPE;
        }
    }

    /// Where each Trino operation of a pattern runs, decided once when the pattern compiles. An
    /// operation runs on the generated class exactly when [TrinoRegexp] would run it on the
    /// case-sensitive scan plan, or would hand a short boolean `contains` or `count` to the
    /// ordinary engine (see [CompiledPattern#usesOrdinaryShortBooleanMatchForDiagnostics()]), a
    /// route that avoids the interpreter's per-operation dispatch, which the generated class does
    /// not have. Only `contains` can keep another route, a retained literal kernel; every other
    /// operation reaches the plan.
    ///
    /// @param contains whether full-Slice find reaches the plan rather than a retained literal kernel
    /// @param count how count reaches the plan
    record TrinoRoutes(TrinoCompiledPattern pattern, ScanPlan plan, boolean contains, CompiledPattern.ScanPlanCount count)
    {
        TrinoRoutes
        {
            requireNonNull(pattern, "pattern is null");
            requireNonNull(plan, "plan is null");
            requireNonNull(count, "count is null");
        }

        /// Returns the routes of a pattern compiled to the base [TrinoRegexp], or `null`
        /// when it has no case-sensitive scan plan, can match empty, or an operation other than
        /// `contains` would skip it.
        static TrinoRoutes of(TrinoCompiledPattern pattern)
        {
            ScanPlan plan = pattern.caseSensitiveScanPlan();
            // No pattern with a plan can match empty, and the generated entries rely on it, as
            // each match loop resumes its search at the previous match end.
            if (plan == null || pattern.canMatchEmpty()) {
                return null;
            }
            // A pattern with a scan plan has neither a single-byte matcher, which group-zero
            // operations ask before the pattern, nor a count that skips the plan. A pattern that
            // breaks either rule keeps the interpreter rather than reach the generated class.
            CompiledPattern.ScanPlanCount count = pattern.scanPlanCount();
            if (pattern.hasSingleByteMatcher() || count == CompiledPattern.ScanPlanCount.OTHER) {
                return null;
            }
            return new TrinoRoutes(pattern, plan, pattern.usesScanPlanForFindForDiagnostics(), count);
        }

        /// Whether `contains` runs the capture-free search before its handoff to the ordinary
        /// engine.
        boolean ordinaryHandoff()
        {
            return contains && plan.allowsOrdinaryBooleanSearch();
        }

        int capturingGroupCount()
        {
            return pattern.capturingGroupCount();
        }
    }

    /// Records each full-Slice operation's route at compile time, using the facts read by the
    /// inherited scan-plan routes, for an RE2, Java, or Rust pattern. The generated class
    /// declares the operations among `find`, `count`, `matches`, and `lookingAt` that reach the
    /// plan; the others keep the inherited route. Other calls that reach the plan go through the
    /// generated class's `matchInternal`, except a matcher's start-anchored first search, which
    /// uses `findWithStartAnchoredScanPlan`.
    ///
    /// @param find whether full-Slice find reaches the plan rather than a retained literal kernel
    /// @param count how count reaches the plan
    /// @param matches whether full-Slice matches reaches the plan, which it does through
    ///         [CompiledPattern#matchesOnScanPlan] when its boolean plan is general
    /// @param lookingAt whether full-Slice lookingAt reaches the plan, as for `matches`
    record PatternRoutes(
            CompiledPattern pattern,
            ScanPlan plan,
            boolean find,
            CompiledPattern.ScanPlanCount count,
            boolean matches,
            boolean lookingAt)
    {
        PatternRoutes
        {
            requireNonNull(pattern, "pattern is null");
            requireNonNull(plan, "plan is null");
            requireNonNull(count, "count is null");
        }

        /// Returns the routes of `pattern`, which must have a case-sensitive scan plan.
        ///
        /// @throws IllegalArgumentException if the pattern can match empty, which no pattern with
        ///         a plan can: the generated entries rely on it, as count resumes each search at
        ///         the previous match end and a generated find skips the empty-match rule of the
        ///         Rust search
        static PatternRoutes of(CompiledPattern pattern)
        {
            if (pattern.canMatchEmpty()) {
                throw new IllegalArgumentException("a pattern that can match empty has no generated class");
            }
            ScanPlan plan = requireNonNull(pattern.caseSensitiveScanPlan(), "pattern has no case-sensitive scan plan");
            return new PatternRoutes(
                    pattern,
                    plan,
                    pattern.usesScanPlanForFindForDiagnostics(),
                    pattern.scanPlanCount(),
                    pattern.matchesPlanForDiagnostics() == CompiledPattern.BooleanPlanKind.GENERAL,
                    pattern.lookingAtPlanForDiagnostics() == CompiledPattern.BooleanPlanKind.GENERAL);
        }

        /// Whether full-Slice find runs the capture-free search before its handoff to the
        /// ordinary engine.
        boolean ordinaryHandoff()
        {
            return find && plan.allowsOrdinaryBooleanSearch();
        }
    }

    /// A generated Trino pattern and the classfile library's report on compiling its class.
    record ReportedTrinoPattern(TrinoRegexp.CompiledScanTrinoRegexp pattern, CompilationReport report)
    {
        ReportedTrinoPattern
        {
            requireNonNull(pattern, "pattern is null");
            requireNonNull(report, "report is null");
        }
    }

    /// Generates and defines the class of a Trino pattern with `routes`, and returns the pattern
    /// that is an instance of it, over the routes' compiled engine. The class extends
    /// [TrinoRegexp.CompiledScanTrinoRegexp], whose constructor takes the compiled engine; the
    /// generated constructor has the same type and passes its argument to it. Failures propagate.
    static TrinoRegexp.CompiledScanTrinoRegexp generateCompiledTrinoPattern(TrinoRoutes routes)
    {
        return generate(routes).pattern();
    }

    /// Generates and defines the class of a Trino pattern with `routes` as
    /// [#generateCompiledTrinoPattern] does, also returning the compilation report.
    static ReportedTrinoPattern generateCompiledTrinoPatternForDiagnostics(TrinoRoutes routes)
    {
        return generate(routes);
    }

    private static ReportedTrinoPattern generate(TrinoRoutes routes)
    {
        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = compile(definer, ScanEmitter.forTrino(routes));
        Class<? extends TrinoRegexp.CompiledScanTrinoRegexp> patternClass = define(definer, unit, TrinoRegexp.CompiledScanTrinoRegexp.class);
        TrinoRegexp.CompiledScanTrinoRegexp pattern = newInstance(patternClass, TRINO_CONSTRUCTOR_TYPE, routes.pattern());
        return new ReportedTrinoPattern(pattern, unit.report());
    }

    /// Returns the attempt loop of `patternClass`, a class generated for `plan`, with the
    /// signature of the plan executor it replaces, less the unanchored executor's `fullAttempt`:
    /// a generated attempt always runs every operation.
    static MethodHandle attemptHandleForDiagnostics(Class<?> patternClass, ScanPlan plan)
    {
        Attempt attempt = Attempt.of(plan);
        return findStatic(patternClass, attempt.name, attempt.type());
    }

    /// Returns the class file generated for a pattern with `routes`, without defining it.
    static byte[] trinoClassfileForDiagnostics(TrinoRoutes routes)
    {
        CompiledUnit unit = compile(HiddenClassDefiner.builder(MethodHandles.lookup()).build(), ScanEmitter.forTrino(routes));
        return unit.classfile(unit.primaryType());
    }

    /// Generates and defines the pattern class of an RE2, Java, or Rust `pattern` for its
    /// case-sensitive scan plan, and returns the copy of `pattern` that is an instance of it. The
    /// class extends `superclass`, the language's `CompiledScan…` class, whose copy constructor
    /// takes the language's scan-plan shape class, the superclass of `superclass`; the generated
    /// constructor has the same type and passes its argument to it. Its entries follow the
    /// pattern's [PatternRoutes]. Failures propagate, including the refusal of a pattern that can
    /// match empty.
    static <T extends CompiledPattern> T generateCompiledScanPattern(CompiledPattern pattern, Class<T> superclass)
    {
        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = compile(definer, ScanEmitter.forPattern(PatternRoutes.of(pattern), superclass));
        return newInstance(define(definer, unit, superclass), copyConstructorType(superclass), pattern);
    }

    /// Returns the class file of the pattern class that [#generateCompiledScanPattern] would
    /// define for a pattern with `routes`, without defining it.
    static byte[] patternClassfileForDiagnostics(PatternRoutes routes, Class<? extends CompiledPattern> superclass)
    {
        CompiledUnit unit = compile(HiddenClassDefiner.builder(MethodHandles.lookup()).build(), ScanEmitter.forPattern(routes, superclass));
        return unit.classfile(unit.primaryType());
    }

    /// The type of the copy constructor of a `CompiledScan…` class, and of its generated
    /// subclass: it takes the language's scan-plan shape class, which `superclass` extends.
    private static MethodType copyConstructorType(Class<? extends CompiledPattern> superclass)
    {
        return MethodType.methodType(void.class, superclass.getSuperclass());
    }

    private static CompiledUnit compile(HiddenClassDefiner definer, ScanEmitter emitter)
    {
        return ClassCompiler.forTarget(definer.compilationTarget()).compileUnit(emitter.define());
    }

    private static <T> Class<? extends T> define(HiddenClassDefiner definer, CompiledUnit unit, Class<T> type)
    {
        if (DUMP_DIRECTORY != null) {
            dump(unit);
        }
        DefinedUnit defined = definer.defineUnit(unit);
        return defined.primaryClass(type);
    }

    private static <T> T newInstance(Class<? extends T> scanClass, MethodType constructorType, Object... arguments)
    {
        try {
            return scanClass.getConstructor(constructorType.parameterArray()).newInstance(arguments);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot instantiate generated scan class", e);
        }
    }

    private static MethodHandle findStatic(Class<?> type, String name, MethodType methodType)
    {
        try {
            return MethodHandles.lookup().findStatic(type, name, methodType);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("generated scan class lacks " + name + methodType, e);
        }
    }

    /// Writes each class of `unit` to the dump directory. A dump that cannot be written
    /// costs only the dump, so the class is still defined.
    private static void dump(CompiledUnit unit)
    {
        try {
            Path directory = Path.of(DUMP_DIRECTORY);
            Files.createDirectories(directory);
            for (ClassDesc type : unit.types()) {
                byte[] classfile = unit.classfile(type);
                CRC32 checksum = new CRC32();
                checksum.update(classfile);
                // Named by content, so a rerun overwrites the same files.
                String name = type.displayName() + "-" + HexFormat.of().toHexDigits((int) checksum.getValue()) + ".class";
                Files.write(directory.resolve(name), classfile);
            }
        }
        catch (IOException | InvalidPathException ignored) {
            // The dump is a diagnostic; generation proceeds without it.
        }
    }

    /// Emits the attempt method of one kernel class. Locals and labels mirror the interpreter:
    /// `cursor`, the retry stack, and, for unanchored attempts, `resumeRunEnd` and
    /// `scanned`. The label of each operation index starts that operation, and the label one
    /// past the last operation runs the end-of-operations check of partial and unanchored plans.
    private static final class KernelEmitter
    {
        private static final Constructor<?> NEW_ILLEGAL_STATE_EXCEPTION = constructor(IllegalStateException.class, String.class);

        private final ScanPlan plan;
        private final Attempt attempt;
        private final int operations;
        private final boolean hasRetries;

        private CodeBlock.Builder body;
        private Parameter fullMatch;
        private Parameter groups;
        private Parameter matchStart;
        private BytecodeExpression bytes;
        private BytecodeExpression base;
        private BytecodeExpression end;
        private Variable cursor;
        private Variable stop;
        private Variable retries;
        private Variable retryPositionsLow;
        private Variable retryPositionsHigh;
        private Variable retryOperations;
        private Variable resumeRunEnd;
        private Variable scanned;
        private CodeLabel[] operationLabels;
        private CodeLabel fail;

        private KernelEmitter(ScanPlan plan, Attempt attempt)
        {
            this.plan = plan;
            this.attempt = attempt;
            this.operations = plan.operationCount();
            boolean pushes = false;
            for (int index = 0; index < operations; index++) {
                Kind kind = plan.step(index).kind();
                pushes |= kind == Kind.OPTIONAL || kind == Kind.FORK;
                validate(index);
            }
            this.hasRetries = pushes;
        }

        private void validate(int index)
        {
            Step step = plan.step(index);
            boolean supported = switch (step.kind()) {
                case LITERAL, OPTIONAL, RUN, RE2_RUN, RE2_SET_RUN, SAVE, FORK -> true;
                case END -> attempt != Attempt.PARTIAL;
                case DELIMITED_CAPTURE, RE2_DELIMITED_CAPTURE -> attempt != Attempt.UNANCHORED;
                case DOT_ALL_TAIL, LINE_TAIL -> attempt == Attempt.END_ANCHORED;
                case ASCII_FOLDED_LITERAL, ASCII_FOLDED_OPTIONAL -> false;
            };
            if (!supported) {
                throw new IllegalArgumentException("%s scan kernel cannot run %s".formatted(attempt, step.kind()));
            }
            if (step.branch() != null && (step.branch().target() <= index || step.branch().target() > operations)) {
                throw new IllegalArgumentException("unsupported branch target at operation " + index);
            }
            if (attempt == Attempt.END_ANCHORED && index == operations - 1) {
                // The end-anchored interpreter has no end-of-operations check; its last
                // operation always returns or fails.
                if (step.kind() != Kind.END && step.kind() != Kind.DOT_ALL_TAIL && step.kind() != Kind.LINE_TAIL) {
                    throw new IllegalArgumentException("end-anchored scan plan does not end with END or a tail");
                }
                if (step.branch() != null) {
                    throw new IllegalArgumentException("end-anchored scan plan ends with a fork");
                }
            }
        }

        /// Adds the attempt loop to `definition` as a public static method named for its
        /// attempt, with the signature of [Attempt#type()].
        private MethodDefinition defineAttempt(ClassDefinition definition)
        {
            fullMatch = arg("fullMatch", boolean.class);
            groups = arg("groups", int[].class);
            MethodDefinition method;
            if (attempt == Attempt.UNANCHORED) {
                Parameter bytes = arg("bytes", byte[].class);
                Parameter base = arg("base", int.class);
                Parameter end = arg("end", int.class);
                matchStart = arg("matchStart", int.class);
                method = definition.method(attempt.name, attempt.type().returnType(), bytes, base, end, matchStart, fullMatch, groups)
                        .access(PUBLIC, STATIC);
                body = method.body();
                emitUnanchoredLocals(bytes, base, end);
            }
            else {
                Parameter input = arg("input", Slice.class);
                Parameter contextStart = arg("contextStart", int.class);
                Parameter contextEnd = arg("contextEnd", int.class);
                method = definition.method(attempt.name, attempt.type().returnType(), input, contextStart, contextEnd, fullMatch, groups)
                        .access(PUBLIC, STATIC);
                body = method.body();
                emitAnchoredLocals(input, contextStart, contextEnd);
            }
            emitKernel();
            return method;
        }

        private void emitKernel()
        {
            stop = body.declare(int.class, "stop");
            if (hasRetries) {
                retries = body.declare("retries", constantInt(0));
                retryPositionsLow = body.declare("retryPositionsLow", constantLong(0));
                retryPositionsHigh = body.declare("retryPositionsHigh", constantLong(0));
                retryOperations = body.declare("retryOperations", constantInt(0));
            }

            operationLabels = new CodeLabel[operations + 1];
            for (int index = 0; index <= operations; index++) {
                operationLabels[index] = body.label("operation" + index);
            }
            fail = body.label("fail");

            for (int index = 0; index < operations; index++) {
                body.mark(operationLabels[index]);
                emitOperation(index);
            }
            body.mark(operationLabels[operations]);
            emitEndOfOperations();
            body.mark(fail);
            emitFailure();
        }

        private void emitAnchoredLocals(Parameter input, Parameter contextStart, Parameter contextEnd)
        {
            bytes = body.declare("bytes", input.invoke("byteArray", byte[].class));
            Variable offset = body.declare("offset", input.invoke("byteArrayOffset", int.class));
            base = body.declare("base", offset.add(contextStart));
            end = body.declare("end", offset.add(contextEnd));
            cursor = body.declare("cursor", base);
        }

        private void emitUnanchoredLocals(Parameter bytes, Parameter base, Parameter end)
        {
            this.bytes = bytes;
            this.base = base;
            this.end = end;
            body.append(fillIfNotNull(groups));
            cursor = body.declare("cursor", matchStart);
            resumeRunEnd = body.declare("resumeRunEnd", matchStart);
            scanned = body.declare("scanned", matchStart);
        }

        private void emitOperation(int index)
        {
            Step step = plan.step(index);
            switch (step.kind()) {
                case LITERAL -> {
                    emitLiteralCheck(step, fail);
                    body.append(cursor.set(cursor.add(constantInt(step.literal.length))));
                }
                case OPTIONAL -> {
                    emitLiteralCheck(step, operationLabels[index + 1]);
                    emitPush(index);
                    body.append(cursor.set(cursor.add(constantInt(step.literal.length))));
                }
                case DELIMITED_CAPTURE -> {
                    body.append(stop.set(findByte(step.delimiter())));
                    body.append(ifThenJump(stop.lessThan(constantInt(0)), fail));
                    if (step.minimum() > 0) {
                        body.append(ifThenJump(stop.subtract(cursor).lessThan(constantInt(step.minimum())), fail));
                    }
                    emitCapture(step.slot());
                    body.append(cursor.set(stop.add(constantInt(1))));
                }
                case RE2_DELIMITED_CAPTURE -> {
                    // The run validates the characters it skips, and the minimum counts characters.
                    BytecodeExpression run = boundConstant(step.run(), ScanPlanRun.class);
                    BytecodeExpression delimiter = constantInt(step.delimiter()).cast(byte.class);
                    body.append(stop.set(invokeStatic(ScanKernelSupport.class, "re2RunMatchBefore", int.class, run, bytes, cursor, end, delimiter)));
                    body.append(ifThenJump(stop.lessThan(constantInt(0)), fail));
                    emitCapture(step.slot());
                    body.append(cursor.set(stop.add(constantInt(1))));
                }
                case DOT_ALL_TAIL -> body.append(anchoredSuccess(end.subtract(base)));
                case LINE_TAIL -> {
                    body.append(stop.set(findByte((byte) '\n')));
                    body.append(IfStatement.builder()
                            .condition(stop.lessThan(constantInt(0)))
                            .then(anchoredSuccess(end.subtract(base)))
                            .build());
                    if (plan.hasFinalLineEnd()) {
                        body.append(IfStatement.builder()
                                .condition(fullMatch.not().and(stop.equal(end.subtract(constantInt(1)))))
                                .then(anchoredSuccess(stop.subtract(base)))
                                .build());
                    }
                    body.jump(fail);
                }
                case END -> {
                    BytecodeExpression atEnd = cursor.equal(end);
                    if (plan.hasFinalLineEnd()) {
                        atEnd = atEnd.or(fullMatch.not()
                                .and(cursor.equal(end.subtract(constantInt(1))))
                                .and(bytes.getElement(cursor).cast(int.class).equal(constantInt('\n'))));
                    }
                    if (plan.hasJavaFinalEnd()) {
                        atEnd = atEnd.or(fullMatch.not()
                                .and(invokeStatic(ScanKernelSupport.class, "endsBeforeJavaFinalTerminator", boolean.class, bytes, base, end, cursor)));
                    }
                    body.append(IfStatement.builder()
                            .condition(atEnd)
                            .then(attempt == Attempt.UNANCHORED ? unanchoredSuccess() : anchoredSuccess(cursor.subtract(base)))
                            .build());
                    body.jump(fail);
                }
                case RUN -> emitRun(index, step, "runMatch", "runMatchMinimum");
                case RE2_RUN -> emitRun(index, step, "re2RunMatch", "re2RunMatchMinimum");
                case RE2_SET_RUN -> emitRun(index, step, "re2SetRunMatch", "re2SetRunMatchMinimum");
                case SAVE -> body.append(IfStatement.builder()
                        .condition(groups.isNotNull().and(constantInt(step.slot()).lessThan(groups.length())))
                        .then(groups.setElement(constantInt(step.slot()), cursor.subtract(base)))
                        .build());
                case FORK -> emitPush(index);
                case ASCII_FOLDED_LITERAL, ASCII_FOLDED_OPTIONAL -> throw new IllegalStateException("unexpected " + step.kind());
            }
        }

        /// Emits a run that calls the [ScanKernelSupport] methods named `match` and
        /// `matchMinimum`: `runMatch` and `runMatchMinimum` for a `RUN`, and
        /// `re2RunMatch` and `re2RunMatchMinimum`, which read characters as RE2 does,
        /// for an `RE2_RUN`. An `RE2_SET_RUN` calls `re2SetRunMatch` and
        /// `re2SetRunMatchMinimum`, which read characters by the run's member encodings.
        private void emitRun(int index, Step step, String match, String matchMinimum)
        {
            BytecodeExpression run = boundConstant(step.run(), ScanPlanRun.class);
            if (attempt != Attempt.END_ANCHORED && plan.isBooleanTail(index)) {
                // The continuation cannot fail, so the run's minimum decides this path.
                CodeBlock.Builder tail = CodeBlock.blockBuilder();
                BytecodeExpression minimum = invokeStatic(ScanKernelSupport.class, matchMinimum, int.class, run, bytes, cursor, end);
                if (attempt == Attempt.PARTIAL) {
                    tail.append(IfStatement.builder()
                            .condition(minimum.greaterThanOrEqual(constantInt(0)))
                            .then(constantTrue().ret())
                            .build());
                }
                else {
                    tail.append(stop.set(minimum));
                    tail.append(IfStatement.builder()
                            .condition(stop.greaterThanOrEqual(constantInt(0)))
                            .then(stop.cast(long.class).ret())
                            .build());
                }
                tail.jump(fail);
                body.append(IfStatement.builder()
                        .condition(groups.isNull().and(fullMatch.not()))
                        .then(tail.build())
                        .build());
            }
            body.append(stop.set(invokeStatic(ScanKernelSupport.class, match, int.class, run, bytes, cursor, end)));
            body.append(ifThenJump(stop.lessThan(constantInt(0)), fail));
            if (attempt == Attempt.UNANCHORED) {
                body.append(scanned.set(invokeStatic(Math.class, "max", int.class, scanned, stop)));
                if (index == plan.failureResumeOperation()) {
                    body.append(resumeRunEnd.set(stop));
                }
            }
            if (step.slot() > 0) {
                emitCapture(step.slot());
            }
            body.append(cursor.set(stop));
        }

        private void emitCapture(int slot)
        {
            body.append(IfStatement.builder()
                    .condition(groups.isNotNull().and(constantInt(slot + 1).lessThan(groups.length())))
                    .then(CodeBlock.block(
                            groups.setElement(constantInt(slot), cursor.subtract(base)),
                            groups.setElement(constantInt(slot + 1), stop.subtract(base))))
                    .build());
        }

        /// Jumps to `mismatch` unless the literal of `step` occurs at the cursor,
        /// with the comparisons of [Step#matches].
        private void emitLiteralCheck(Step step, CodeLabel mismatch)
        {
            int length = step.literal.length;
            body.append(ifThenJump(constantInt(length).greaterThan(end.subtract(cursor)), mismatch));
            if (length > INLINE_LITERAL_BYTES) {
                BytecodeExpression mismatchIndex = invokeStatic(
                        Arrays.class,
                        "mismatch",
                        int.class,
                        boundConstant(step.literal, byte[].class),
                        constantInt(0),
                        constantInt(length),
                        bytes,
                        cursor,
                        cursor.add(constantInt(length)));
                body.append(ifThenJump(mismatchIndex.greaterThanOrEqual(constantInt(0)), mismatch));
                return;
            }
            BytecodeExpression last = cursor.add(constantInt(step.lastOffset()));
            BytecodeExpression differs = switch (step.width()) {
                case 1 -> bytes.getElement(cursor).cast(int.class).notEqual(constantInt((int) step.first()));
                case 2 -> differs("readShort", step, last);
                case 4 -> differs("readInt", step, last);
                case 8 -> differs("readLong", step, last);
                default -> throw new IllegalStateException("unexpected literal width: " + step.width());
            };
            body.append(ifThenJump(differs, mismatch));
        }

        private BytecodeExpression differs(String read, Step step, BytecodeExpression lastPosition)
        {
            boolean wide = step.width() == Long.BYTES;
            Class<?> type = wide ? long.class : int.class;
            BytecodeExpression first = wide ? constantLong(step.first()) : constantInt((int) step.first());
            BytecodeExpression zero = wide ? constantLong(0) : constantInt(0);
            BytecodeExpression firstRead = invokeStatic(ScanKernelSupport.class, read, type, bytes, cursor);
            if (step.lastOffset() == 0) {
                return firstRead.notEqual(first);
            }
            BytecodeExpression last = wide ? constantLong(step.last()) : constantInt((int) step.last());
            BytecodeExpression lastRead = invokeStatic(ScanKernelSupport.class, read, type, bytes, lastPosition);
            return firstRead.bitwiseXor(first).bitwiseOr(lastRead.bitwiseXor(last)).notEqual(zero);
        }

        private BytecodeExpression findByte(byte target)
        {
            return invokeStatic(ScanKernelSupport.class, "findByte", int.class, bytes, cursor, end, constantInt(target).cast(byte.class));
        }

        private void emitPush(int index)
        {
            body.append(retryPositionsHigh.set(retryPositionsHigh.shiftLeft(constantInt(32))
                    .bitwiseOr(retryPositionsLow.shiftRightUnsigned(constantInt(32)))));
            body.append(retryPositionsLow.set(retryPositionsLow.shiftLeft(constantInt(32))
                    .bitwiseOr(cursor.cast(long.class).bitwiseAnd(constantLong(0xFFFF_FFFFL)))));
            body.append(retryOperations.set(retryOperations.shiftLeft(constantInt(RETRY_OPERATION_BITS))
                    .bitwiseOr(constantInt(index + 1))));
            body.append(retries.set(retries.add(constantInt(1))));
        }

        private void emitEndOfOperations()
        {
            switch (attempt) {
                // The last operation of an end-anchored plan always returns or fails.
                case END_ANCHORED -> body.throwObject(BytecodeExpressions.newInstance(NEW_ILLEGAL_STATE_EXCEPTION, constantString("scan kernel ran past END")));
                case PARTIAL -> body.append(IfStatement.builder()
                                .condition(fullMatch.not().or(cursor.equal(end)))
                                .then(anchoredSuccess(cursor.subtract(base)))
                                .build())
                        .jump(fail);
                case UNANCHORED -> {
                    if (plan.isPartialMatch()) {
                        body.append(IfStatement.builder()
                                .condition(fullMatch.not().or(cursor.equal(end)))
                                .then(unanchoredSuccess())
                                .build());
                    }
                    body.jump(fail);
                }
            }
        }

        /// Pops the newest retry and resumes at the operation after the one that pushed it, or at
        /// its branch target after clearing the captures the branch skips. An attempt without
        /// retries fails.
        private void emitFailure()
        {
            if (hasRetries) {
                CodeLabel noRetry = body.label("noRetry");
                body.append(ifThenJump(retries.equal(constantInt(0)), noRetry));
                body.append(retries.set(retries.subtract(constantInt(1))));
                body.append(cursor.set(retryPositionsLow.cast(int.class)));
                body.append(retryPositionsLow.set(retryPositionsLow.shiftRightUnsigned(constantInt(32))
                        .bitwiseOr(retryPositionsHigh.shiftLeft(constantInt(32)))));
                body.append(retryPositionsHigh.set(retryPositionsHigh.shiftRightUnsigned(constantInt(32))));
                Variable operation = body.declare("operation", retryOperations.bitwiseAnd(constantInt(RETRY_OPERATION_MASK)));
                body.append(retryOperations.set(retryOperations.shiftRightUnsigned(constantInt(RETRY_OPERATION_BITS))));
                SwitchStatement.Builder resume = SwitchStatement.builder()
                        .description("resume after the pushing operation")
                        .expression(operation);
                for (int index = 0; index < operations; index++) {
                    Step step = plan.step(index);
                    if (step.kind() == Kind.OPTIONAL || step.kind() == Kind.FORK) {
                        resume.caseValue(index + 1, resumeBlock(step.branch(), index + 1));
                    }
                }
                resume.defaultCase(BytecodeExpressions.newInstance(NEW_ILLEGAL_STATE_EXCEPTION, constantString("corrupt scan kernel retry stack")).throwObject());
                body.append(resume.build());
                body.mark(noRetry);
            }
            if (attempt == Attempt.UNANCHORED) {
                body.ret(scanned.cast(long.class).shiftLeft(constantInt(32))
                        .bitwiseOr(resumeRunEnd.cast(long.class))
                        .bitwiseXor(constantLong(-1)));
            }
            else {
                body.append(fillIfNotNull(groups));
                body.ret(constantFalse());
            }
        }

        private CodeBlock resumeBlock(Branch branch, int next)
        {
            CodeBlock.Builder block = CodeBlock.blockBuilder();
            if (branch == null) {
                return block.jump(operationLabels[next]).build();
            }
            List<Statement> clears = new ArrayList<>();
            int mask = branch.captureMask();
            while (mask != 0) {
                int slot = 2 * (Integer.numberOfTrailingZeros(mask) + 1);
                clears.add(IfStatement.builder()
                        .condition(constantInt(slot + 1).lessThan(groups.length()))
                        .then(CodeBlock.block(
                                groups.setElement(constantInt(slot), constantInt(-1)),
                                groups.setElement(constantInt(slot + 1), constantInt(-1))))
                        .build());
                mask &= mask - 1;
            }
            if (!clears.isEmpty()) {
                block.append(IfStatement.builder()
                        .condition(groups.isNotNull())
                        .then(CodeBlock.block(clears.toArray(Statement[]::new)))
                        .build());
            }
            return block.jump(operationLabels[branch.target()]).build();
        }

        private CodeBlock anchoredSuccess(BytecodeExpression matchEnd)
        {
            return CodeBlock.block(
                    IfStatement.builder()
                            .condition(groups.isNotNull())
                            .then(CodeBlock.block(
                                    groups.setElement(constantInt(0), constantInt(0)),
                                    groups.setElement(constantInt(1), matchEnd)))
                            .build(),
                    constantTrue().ret());
        }

        private CodeBlock unanchoredSuccess()
        {
            return CodeBlock.block(
                    IfStatement.builder()
                            // ScanPlan.END_ONLY_GROUPS records nothing.
                            .condition(groups.isNotNull().and(groups.length().notEqual(constantInt(0))))
                            .then(CodeBlock.block(
                                    groups.setElement(constantInt(0), matchStart.subtract(base)),
                                    groups.setElement(constantInt(1), cursor.subtract(base))))
                            .build(),
                    cursor.cast(long.class).ret());
        }
    }

    /// Emits the class of one pattern. The class of a Trino pattern extends
    /// [TrinoRegexp.CompiledScanTrinoRegexp] and holds the attempt loop, the search driver of an
    /// unanchored plan, the static `findFrom` over them, and a public instance entry for each
    /// operation that reaches the plan, which checks its arguments as the operation does. A
    /// matcher's finds reach `findFrom` through the instance hook `find`. The class of an RE2,
    /// Java, or Rust pattern extends its language's `CompiledScan…` class and holds the attempt
    /// loop, the searches of an unanchored plan, and an instance body for each full-Slice
    /// operation and engine hook that reaches the plan, each with its route fixed by the
    /// pattern's [PatternRoutes]. A plan never matches empty, so every match loop resumes at the
    /// previous match end, as a matcher does. Scans call the copies in [ScanKernelSupport], not
    /// the interpreter's helpers; other shared code is reached only through static calls, calls
    /// on class-data constants, and final engine methods of the pattern itself, including the
    /// ordinary engine that the generated hook calls for a call off the plan.
    private static final class ScanEmitter
    {
        // Reflective lookups the emitters would otherwise repeat for every pattern.
        private static final Method LIST_ADD = method(List.class, "add", Object.class);
        private static final Method FUNCTION_APPLY = method(Function.class, "apply", Object.class);
        private static final Method REQUIRE_NON_NULL = method(Objects.class, "requireNonNull", Object.class, String.class);
        private static final Constructor<?> NEW_ARRAY_LIST = constructor(ArrayList.class);
        private static final Constructor<?> NEW_ARRAY_LIST_WITH_CAPACITY = constructor(ArrayList.class, int.class);
        private static final Constructor<?> NEW_DYNAMIC_SLICE_OUTPUT = constructor(DynamicSliceOutput.class, int.class);
        private static final Method NEW_REPLACEMENT_PLAN = declaredMethod(TrinoRegexp.class, "newReplacementPlan", Slice.class, Slice.class, int.class, Map.class);

        // The final engine methods the generated classes call on a pattern.
        private static final Method CONTINUE_SCAN_SEARCH = engineMethod("continueScanSearch");
        private static final Method REQUIRE_INPUT = engineMethod("requireInput");
        private static final Method REACHES_SCAN_PLAN = engineMethod("reachesScanPlan");
        private static final Method ACCEPTS_SCAN_PLAN_ARGUMENTS = engineMethod("acceptsScanPlanArguments");
        private static final Method FINISH_SCAN_SEARCH = engineMethod("finishScanSearch");
        private static final Method MATCH_ON_ORDINARY_ENGINE = engineMethod("matchOnOrdinaryEngine");

        // Null for the class of an RE2, Java, or Rust pattern.
        private final TrinoRoutes routes;
        // Null for the class of a Trino pattern.
        private final PatternRoutes patternRoutes;
        // The superclass of the class of an RE2, Java, or Rust pattern; null for a Trino pattern.
        private final Class<? extends CompiledPattern> superclass;
        private final ScanPlan plan;
        private final boolean ordinaryHandoff;
        private final Attempt attempt;
        private final boolean anchored;

        private ClassDefinition definition;
        private MethodDefinition attemptLoop;
        private MethodDefinition search;
        private MethodDefinition regionSearch;
        private MethodDefinition searchBeforeHandoff;
        private MethodDefinition findFrom;

        /// The context and attempt arguments of a region search. A search without one covers the
        /// whole input, unanchored and without a full-match requirement.
        private record Region(Parameter contextStart, Parameter contextEnd, Parameter anchored, Parameter fullMatch) {}

        private ScanEmitter(TrinoRoutes routes, PatternRoutes patternRoutes, ScanPlan plan, boolean ordinaryHandoff, Class<? extends CompiledPattern> superclass)
        {
            this.routes = routes;
            this.patternRoutes = patternRoutes;
            this.superclass = superclass;
            this.plan = requireNonNull(plan, "plan is null");
            this.ordinaryHandoff = ordinaryHandoff;
            this.attempt = Attempt.of(plan);
            this.anchored = attempt != Attempt.UNANCHORED;
        }

        private static ScanEmitter forTrino(TrinoRoutes routes)
        {
            return new ScanEmitter(routes, null, routes.plan(), routes.ordinaryHandoff(), null);
        }

        private static ScanEmitter forPattern(PatternRoutes routes, Class<? extends CompiledPattern> superclass)
        {
            return new ScanEmitter(null, routes, routes.plan(), routes.ordinaryHandoff(), requireNonNull(superclass, "superclass is null"));
        }

        private ClassModel define()
        {
            return patternRoutes != null ? definePattern() : defineTrino();
        }

        private void defineClass(String className)
        {
            if (plan.usesAsciiFoldedExecutor()) {
                throw new IllegalArgumentException("ASCII-folded scan plans have no generated class");
            }
            definition = ClassDefinition.define(ClassDesc.of(className)).access(PUBLIC, FINAL);
        }

        /// The class of an RE2, Java, or Rust pattern, with a constructor of the type of its
        /// superclass's copy constructor. It overrides `find`, `count`, `matches`, and
        /// `lookingAt` where its routes reach the plan, and the engine hooks used by other calls
        /// that reach the plan.
        private ClassModel definePattern()
        {
            defineClass(attempt.patternClassName(superclass));
            definition.superClass(superclass);
            Parameter pattern = arg("pattern", copyConstructorType(superclass).parameterType(0));
            definition.constructor(pattern)
                    .access(PUBLIC)
                    .body()
                    .invokeSuperConstructor(pattern)
                    .ret();
            attemptLoop = new KernelEmitter(plan, attempt).defineAttempt(definition);
            if (!anchored) {
                search = defineSearch();
                regionSearch = defineRegionSearch();
                if (ordinaryHandoff) {
                    searchBeforeHandoff = defineSearchBeforeHandoff();
                }
            }
            if (patternRoutes.find()) {
                definePatternFind();
            }
            if (patternRoutes.count() != CompiledPattern.ScanPlanCount.OTHER) {
                definePatternCount();
            }
            if (patternRoutes.matches()) {
                defineAnchoredMatch("matches", true);
            }
            if (patternRoutes.lookingAt()) {
                defineAnchoredMatch("lookingAt", false);
            }
            defineMatchInternal();
            if (anchored) {
                defineFindWithStartAnchoredScanPlan();
            }
            return definition.build();
        }

        /// The class of a Trino pattern, with a constructor of the type of its superclass's
        /// constructor. It declares `contains` only where its routes reach the plan, and every
        /// other operation that [TrinoRegexp.CompiledScanTrinoRegexp] runs on the plan.
        private ClassModel defineTrino()
        {
            defineClass(attempt.patternClassName(TrinoRegexp.CompiledScanTrinoRegexp.class));
            definition.superClass(TrinoRegexp.CompiledScanTrinoRegexp.class);
            Parameter pattern = arg("pattern", TRINO_CONSTRUCTOR_TYPE.parameterType(0));
            definition.constructor(pattern).access(PUBLIC).body().invokeSuperConstructor(pattern).ret();
            attemptLoop = new KernelEmitter(plan, attempt).defineAttempt(definition);
            if (!anchored) {
                search = defineSearch();
                if (ordinaryHandoff) {
                    searchBeforeHandoff = defineSearchBeforeHandoff();
                }
            }
            defineFind();
            defineFindHook();
            if (routes.contains()) {
                defineContains();
            }
            defineCount();
            definePosition();
            defineSplit();
            defineExtract();
            defineExtractAll();
            defineReplace();
            defineReplaceWithCallback();
            return definition.build();
        }

        /// The unanchored search of [ScanPlan#search] over the whole input, with the
        /// same results.
        private MethodDefinition defineSearch()
        {
            Parameter input = arg("input", Slice.class);
            Parameter start = arg("start", int.class);
            Parameter groups = arg("groups", int[].class);
            MethodDefinition method = definition.method("search", int.class, input, start, groups)
                    .access(PRIVATE, STATIC);
            emitCandidateLoop(method.body(), input, null, start, groups, false);
            return method;
        }

        /// The search of [ScanPlan#search] over a region, with the same arguments and
        /// results.
        private MethodDefinition defineRegionSearch()
        {
            Parameter input = arg("input", Slice.class);
            Parameter contextStart = arg("contextStart", int.class);
            Parameter contextEnd = arg("contextEnd", int.class);
            Parameter start = arg("start", int.class);
            Parameter anchoredSearch = arg("anchored", boolean.class);
            Parameter fullMatch = arg("fullMatch", boolean.class);
            Parameter groups = arg("groups", int[].class);
            MethodDefinition method = definition.method("regionSearch", int.class, input, contextStart, contextEnd, start, anchoredSearch, fullMatch, groups)
                    .access(PRIVATE, STATIC);
            emitCandidateLoop(method.body(), input, new Region(contextStart, contextEnd, anchoredSearch, fullMatch), start, groups, false);
            return method;
        }

        /// The capture-free search of [ScanPlan#searchBeforeHandoff] over the whole
        /// input, with the same results.
        private MethodDefinition defineSearchBeforeHandoff()
        {
            Parameter input = arg("input", Slice.class);
            MethodDefinition method = definition.method("searchBeforeHandoff", int.class, input).access(PRIVATE, STATIC);
            emitCandidateLoop(method.body(), input, null, null, constantNull(int[].class), true);
            return method;
        }

        private void emitCandidateLoop(CodeBlock.Builder body, Parameter input, Region region, Parameter start, BytecodeExpression groups, boolean beforeHandoff)
        {
            Variable bytes = body.declare("bytes", input.invoke("byteArray", byte[].class));
            Variable offset = body.declare("offset", input.invoke("byteArrayOffset", int.class));
            BytecodeExpression base = region == null ? offset : body.declare("base", offset.add(region.contextStart()));
            Variable end = body.declare("end", offset.add(region == null ? input.invoke("length", int.class) : region.contextEnd()));
            BytecodeExpression fullMatch = region == null ? constantFalse() : region.fullMatch();
            Variable searchStart = body.declare("searchStart", start == null ? offset : offset.add(start));
            Variable candidate = body.declare("candidate", searchStart);
            // A plan led by a whole literal has no separate required literal.
            boolean wholeLiteral = !beforeHandoff && plan.hasWholeLiteralCandidates();
            Variable checkRequiredLiteral = !wholeLiteral && plan.requiredLiteralOperation() >= 0
                    ? body.declare("checkRequiredLiteral", constantTrue())
                    : null;
            Variable failedWork = body.declare("failedWork", constantLong(0));
            Variable failures = beforeHandoff ? body.declare("failures", constantInt(0)) : null;
            Variable result = body.declare(long.class, "result");
            Variable failure = body.declare(long.class, "failure");
            Variable failedRunEnd = body.declare(int.class, "failedRunEnd");
            CodeLabel loop = body.label("loop");
            CodeLabel done = body.label("done");
            BytecodeExpression attemptAtCandidate = invokeStatic(attemptLoop, bytes, base, end, candidate, fullMatch, groups);
            // A successful attempt returns its end when the caller passes groups or
            // ScanPlan.END_ONLY_GROUPS, and the search returns it Slice-relative. A boolean search
            // without groups may return any nonnegative value for a match.
            BytecodeExpression matchEnd = result.cast(int.class).subtract(offset);

            if (region != null) {
                // An anchored search makes at most one attempt and never hands off.
                body.append(IfStatement.builder()
                        .condition(region.anchored())
                        .then(CodeBlock.block(
                                ifThenJump(candidate.greaterThanOrEqual(end), done),
                                result.set(attemptAtCandidate),
                                IfStatement.builder()
                                        .condition(result.greaterThanOrEqual(constantLong(0)))
                                        .then(matchEnd.ret())
                                        .build(),
                                CodeBlock.blockBuilder().jump(done).build()))
                        .build());
            }
            body.mark(loop);
            body.append(ifThenJump(candidate.greaterThanOrEqual(end), done));
            BytecodeExpression handOff = failedWork
                    .subtract(constantLong(ScanPlan.WORK_BUDGET_FACTOR).multiply(candidate.subtract(searchStart).cast(long.class)))
                    .greaterThan(constantLong(plan.workBudgetBytes()));
            if (beforeHandoff) {
                handOff = handOff.or(failures.notEqual(constantInt(0))
                        .and(failures.remainder(constantInt(ScanPlan.HANDOFF_ATTEMPTS)).equal(constantInt(0)))
                        .and(candidate.subtract(searchStart).cast(long.class)
                                .lessThan(failures.cast(long.class).multiply(constantLong(ScanPlan.HANDOFF_BYTES))))
                        .and(end.subtract(candidate).greaterThanOrEqual(constantInt(ScanPlan.HANDOFF_REMAINING_BYTES))));
            }
            CodeBlock.Builder handOffBlock = CodeBlock.blockBuilder();
            if (!beforeHandoff) {
                handOffBlock.append(fillIfNotNull(groups));
            }
            handOffBlock.ret(candidate.subtract(offset).bitwiseXor(constantInt(-1)));
            body.append(IfStatement.builder()
                    .condition(handOff)
                    .then(handOffBlock.build())
                    .build());

            emitNextCandidate(body, wholeLiteral, bytes, candidate, end, done);
            body.append(result.set(attemptAtCandidate));
            body.append(IfStatement.builder()
                    .condition(result.greaterThanOrEqual(constantLong(0)))
                    .then(matchEnd.ret())
                    .build());
            body.append(failure.set(result.bitwiseXor(constantLong(-1))));
            body.append(failedWork.set(failedWork.add(failure.shiftRightUnsigned(constantInt(32)).cast(int.class).subtract(candidate).cast(long.class))));
            body.append(failedRunEnd.set(failure.cast(int.class)));
            body.append(candidate.set(inlineIf(
                    failedRunEnd.greaterThan(candidate),
                    failedRunEnd,
                    resumeAfterFailedAttempt(bytes, candidate, end))));
            if (checkRequiredLiteral != null) {
                // Every later match lies in [candidate, end) and consumes the required literal.
                BytecodeExpression containsRequiredLiteral = invokeStatic(
                        ScanKernelSupport.class,
                        "containsRequiredLiteral",
                        boolean.class,
                        bytes,
                        candidate,
                        end,
                        boundConstant(plan.step(plan.requiredLiteralOperation()), Step.class));
                body.append(IfStatement.builder()
                        .condition(checkRequiredLiteral)
                        .then(CodeBlock.block(
                                ifThenJump(containsRequiredLiteral.not(), done),
                                checkRequiredLiteral.set(constantFalse())))
                        .build());
            }
            if (failures != null) {
                body.append(failures.increment());
            }
            body.jump(loop);

            body.mark(done);
            if (!beforeHandoff) {
                body.append(fillIfNotNull(groups));
            }
            body.ret(constantInt(ScanPlan.NO_MATCH));
        }

        /// Moves `candidate` to the next position where an attempt can match, as the plan's
        /// candidate scan does, or jumps to `done` when none remains. A candidate of a
        /// `wholeLiteral` scan must hold the leading literal. Every candidate scan of the
        /// generated class is emitted here.
        private void emitNextCandidate(CodeBlock.Builder body, boolean wholeLiteral, Variable bytes, Variable candidate, Variable end, CodeLabel done)
        {
            Step leading = plan.step(plan.leadingOperation());
            BytecodeExpression next;
            if (wholeLiteral) {
                BytecodeExpression literal = boundConstant(leading.literal, byte[].class);
                int probeOffset = plan.leadingProbeOffset();
                // As in the plan, a probe moved off the first byte runs its own loop.
                next = probeOffset != 0
                        ? invokeStatic(ScanKernelSupport.class, "findLiteralAtProbe", int.class, bytes, candidate, end, literal, constantInt(probeOffset))
                        : invokeStatic(ScanKernelSupport.class, "findLiteral", int.class, bytes, candidate, end, literal);
            }
            else if (leading.kind() == Kind.LITERAL) {
                next = invokeStatic(ScanKernelSupport.class, "findByte", int.class, bytes, candidate, end, constantInt(leading.literal[0]).cast(byte.class));
            }
            else if (ScanPlan.isRun(leading) && leading.run().minimum() > 0) {
                next = invokeStatic(ScanKernelSupport.class, "runFindCandidate", int.class, boundConstant(leading.run(), ScanPlanRun.class), bytes, candidate, end);
            }
            else {
                return;
            }
            body.append(candidate.set(next));
            body.append(ifThenJump(candidate.lessThan(constantInt(0)), done));
        }

        /// The resume position after an attempt at `candidate` failed without passing it.
        private BytecodeExpression resumeAfterFailedAttempt(Variable bytes, Variable candidate, Variable end)
        {
            if (ScanPlan.isRun(plan.step(plan.leadingOperation()))) {
                // A failed minimum-width check must not retry from a continuation byte of
                // otherwise valid UTF-8.
                BytecodeExpression decoded = invokeStatic(ScanKernelSupport.class, "decode", long.class, bytes, candidate, end);
                return candidate.add(invokeStatic(Utf8.class, "decodedWidth", int.class, decoded));
            }
            return candidate.add(constantInt(1));
        }

        private void defineFind()
        {
            Parameter input = arg("input", Slice.class);
            Parameter start = arg("start", int.class);
            Parameter groups = arg("groups", int[].class);
            findFrom = definition.method("findFrom", boolean.class, input, start, groups).access(PRIVATE, STATIC);
            CodeBlock.Builder body = findFrom.body();
            if (anchored) {
                // As a matcher's start-anchored route: only the first search of the input can match.
                body.append(fill(groups));
                body.append(IfStatement.builder()
                        .condition(start.notEqual(constantInt(0)))
                        .then(constantFalse().ret())
                        .build());
                body.ret(invokeStatic(attemptLoop, input, constantInt(0), length(input), constantFalse(), groups));
                return;
            }
            Variable result = body.declare("result", invokeStatic(search, input, start, groups));
            body.ret(finishSearch(body, input, result, groups, trinoPattern()));
        }

        /// The instance hook of [TrinoRegexp.CompiledScanTrinoRegexp#find] that a matcher's finds
        /// run, which calls the static `findFrom`.
        private void defineFindHook()
        {
            Parameter input = arg("input", Slice.class);
            Parameter start = arg("start", int.class);
            Parameter groups = arg("groups", int[].class);
            definition.method("find", boolean.class, input, start, groups).body().ret(invokeStatic(findFrom, input, start, groups));
        }

        /// Returns whether an unanchored search that returned `result` matched, continuing
        /// one that handed off on the ordinary engine of `pattern`.
        private static BytecodeExpression finishSearch(CodeBlock.Builder body, Parameter input, Variable result, BytecodeExpression groups, BytecodeExpression pattern)
        {
            body.append(IfStatement.builder()
                    .condition(result.greaterThanOrEqual(constantInt(0)))
                    .then(constantTrue().ret())
                    .build());
            body.append(IfStatement.builder()
                    .condition(result.equal(constantInt(ScanPlan.NO_MATCH)))
                    .then(constantFalse().ret())
                    .build());
            return pattern.invoke(CONTINUE_SCAN_SEARCH, input, result, groups);
        }

        private void defineContains()
        {
            Parameter input = arg("input", Slice.class);
            CodeBlock.Builder body = definition.method("contains", boolean.class, input).access(PUBLIC).body();
            body.append(nullCheck(input, "source is null"));
            emitBooleanFind(body, input, trinoPattern());
        }

        /// Emits code that returns whether `input` holds a match: the attempt of a start-anchored
        /// plan, or the search of an unanchored one, before its handoff where it hands off,
        /// finished on the ordinary engine of `pattern`.
        private void emitBooleanFind(CodeBlock.Builder body, Parameter input, BytecodeExpression pattern)
        {
            BytecodeExpression noGroups = constantNull(int[].class);
            if (anchored) {
                body.ret(invokeStatic(attemptLoop, input, constantInt(0), length(input), constantFalse(), noGroups));
                return;
            }
            Variable result = body.declare("result", searchBeforeHandoff != null
                    ? invokeStatic(searchBeforeHandoff, input)
                    : invokeStatic(search, input, constantInt(0), noGroups));
            body.ret(finishSearch(body, input, result, noGroups, pattern));
        }

        private void defineCount()
        {
            Parameter input = arg("input", Slice.class);
            CodeBlock.Builder body = definition.method("count", long.class, input).access(PUBLIC).body();
            body.append(nullCheck(input, "source is null"));
            emitCount(body, input, routes.count(), trinoPattern());
        }

        // The loop of CompiledPattern.countUnanchoredScanMatches; keep in step.
        private void emitCount(CodeBlock.Builder body, Parameter input, CompiledPattern.ScanPlanCount route, BytecodeExpression pattern)
        {
            if (route == CompiledPattern.ScanPlanCount.SINGLE_ATTEMPT && !anchored) {
                throw new IllegalStateException("an unanchored plan counts by searching");
            }
            if (anchored) {
                // Only the first find of a start-anchored plan can match, so a search loop counts
                // what the single attempt counts.
                body.ret(inlineIf(
                        invokeStatic(attemptLoop, input, constantInt(0), length(input), constantFalse(), constantNull(int[].class)),
                        constantLong(1),
                        constantLong(0)));
                return;
            }
            Variable length = body.declare("length", length(input));
            Variable count = body.declare("count", constantLong(0));
            Variable start = body.declare("start", constantInt(0));
            Variable matchEnd = body.declare(int.class, "matchEnd");
            // A search that hands off finishes on the ordinary engine, which reports the end in
            // group storage. The first handoff allocates it, and later ones in this call reuse it.
            Variable handoffGroups = body.declare("handoffGroups", constantNull(int[].class));
            CodeLabel loop = body.label("loop");
            CodeLabel done = body.label("done");
            body.mark(loop);
            body.append(ifThenJump(start.greaterThanOrEqual(length), done));
            body.append(matchEnd.set(invokeStatic(search, input, start, boundConstant(ScanPlan.END_ONLY_GROUPS, int[].class))));
            body.append(IfStatement.builder()
                    .condition(matchEnd.lessThan(constantInt(0)))
                    .then(CodeBlock.block(
                            ifThenJump(matchEnd.equal(constantInt(ScanPlan.NO_MATCH)), done),
                            IfStatement.builder()
                                    .condition(handoffGroups.isNull())
                                    .then(handoffGroups.set(newArray(int[].class, 2)))
                                    .build(),
                            ifThenJump(pattern.invoke(CONTINUE_SCAN_SEARCH, input, matchEnd, handoffGroups).not(), done),
                            matchEnd.set(handoffGroups.getElement(1))))
                    .build());
            body.append(count.set(count.add(constantLong(1))));
            body.append(start.set(matchEnd));
            body.jump(loop);
            body.mark(done);
            body.ret(count);
        }

        /// The pattern a Trino class finishes its handed-off searches on, as a class-data constant.
        private BytecodeExpression trinoPattern()
        {
            return boundConstant(routes.pattern(), CompiledPattern.class);
        }

        /// Full-Slice find on the plan route of [CompiledPattern#findOnScanPlan], without its
        /// short-input route.
        private void definePatternFind()
        {
            Parameter input = arg("input", Slice.class);
            MethodDefinition method = definition.method("find", boolean.class, input).access(PUBLIC);
            CodeBlock.Builder body = method.body();
            body.append(nullCheck(input, "input is null"));
            emitBooleanFind(body, input, method.thisVariable());
        }

        /// Full-Slice count of a pattern whose count reaches the plan, as
        /// [CompiledPattern#count(Slice)] counts it. A start-anchored plan whose count the engine
        /// declines gets no generated `count`; the inherited one counts with a matcher, which
        /// reaches the plan once.
        private void definePatternCount()
        {
            Parameter input = arg("input", Slice.class);
            MethodDefinition method = definition.method("count", long.class, input).access(PUBLIC);
            CodeBlock.Builder body = method.body();
            body.append(nullCheck(input, "text is null"));
            emitCount(body, input, patternRoutes.count(), method.thisVariable());
        }

        /// Full-Slice `matches`, or `lookingAt` when `fullMatch` is false, of a pattern whose
        /// inherited entry reaches the plan through [CompiledPattern#matchesOnScanPlan] or
        /// [CompiledPattern#lookingAtOnScanPlan] because its boolean plan is general. The generated
        /// entry runs that entry's plan route for an anchored call over all of the input.
        private void defineAnchoredMatch(String name, boolean fullMatch)
        {
            Parameter input = arg("input", Slice.class);
            MethodDefinition method = definition.method(name, boolean.class, input).access(PUBLIC);
            CodeBlock.Builder body = method.body();
            // The checks of the inherited entry, which name a null input as the language does.
            body.append(method.thisVariable().invoke(REQUIRE_INPUT, input));
            body.append(nullCheck(input, "text is null"));
            BytecodeExpression noGroups = constantNull(int[].class);
            BytecodeExpression fullMatchConstant = constantBoolean(fullMatch);
            if (anchored) {
                body.ret(invokeStatic(attemptLoop, input, constantInt(0), length(input), fullMatchConstant, noGroups));
                return;
            }
            // An anchored search makes at most one attempt and never hands off.
            body.ret(invokeStatic(regionSearch, input, constantInt(0), length(input), constantInt(0), constantTrue(), fullMatchConstant, noGroups)
                    .greaterThanOrEqual(constantInt(0)));
        }

        /// The engine hook through which other calls that reach the plan run, except a matcher's
        /// start-anchored first search, which uses `findWithStartAnchoredScanPlan`: the plan route
        /// of [CompiledPattern#matchInternalOnScanPlan], with the attempt or search fixed by the
        /// plan. A call off that route, including an unanchored boolean search that the plan
        /// allows on the ordinary engine, runs [CompiledPattern#matchOnOrdinaryEngine].
        private void defineMatchInternal()
        {
            Parameter prog = arg("prog", Prog.class);
            Parameter text = arg("text", Slice.class);
            Parameter contextStart = arg("contextStart", int.class);
            Parameter contextEnd = arg("contextEnd", int.class);
            Parameter start = arg("start", int.class);
            Parameter end = arg("end", int.class);
            Parameter anchor = arg("anchor", CompiledPattern.Anchor.class);
            Parameter groupOffsets = arg("groupOffsets", int[].class);
            Parameter workspaces = arg("workspaces", CompiledPattern.MatchWorkspaces.class);
            MethodDefinition method = definition.method("matchInternal", boolean.class, prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces);
            Variable self = method.thisVariable();
            CodeBlock.Builder body = method.body();
            BytecodeExpression unanchoredMode = getStatic(CompiledPattern.Anchor.class, "UNANCHORED");
            Variable anchorMode = body.declare("anchorMode", inlineIf(anchor.isNull(), unanchoredMode, anchor));
            BytecodeExpression offPlan = self.invoke(REACHES_SCAN_PLAN, prog, contextEnd, end).not();
            if (!anchored && plan.allowsOrdinaryBooleanSearch()) {
                offPlan = offPlan.or(groupOffsets.isNull().and(anchorMode.equal(unanchoredMode)));
            }
            body.append(IfStatement.builder()
                    .condition(offPlan)
                    .then(self.invoke(MATCH_ON_ORDINARY_ENGINE, prog, text, contextStart, contextEnd, start, end, anchor, groupOffsets, workspaces).ret())
                    .build());
            body.append(IfStatement.builder()
                    .condition(self.invoke(ACCEPTS_SCAN_PLAN_ARGUMENTS, text, contextStart, contextEnd, start, end, groupOffsets).not())
                    .then(constantFalse().ret())
                    .build());
            BytecodeExpression fullMatch = anchorMode.equal(getStatic(CompiledPattern.Anchor.class, "ANCHOR_BOTH"));
            if (anchored) {
                body.ret(start.equal(contextStart).and(invokeStatic(attemptLoop, text, contextStart, contextEnd, fullMatch, groupOffsets)));
                return;
            }
            Variable result = body.declare("result", invokeStatic(regionSearch, text, contextStart, contextEnd, start, anchorMode.notEqual(unanchoredMode), fullMatch, groupOffsets));
            body.ret(self.invoke(FINISH_SCAN_SEARCH, text, contextStart, contextEnd, result, anchorMode, groupOffsets, workspaces));
        }

        /// The capture find of [CompiledPattern#findWithStartAnchoredScanPlan] on the attempt.
        private void defineFindWithStartAnchoredScanPlan()
        {
            Parameter scanPlan = arg("plan", ScanPlan.class);
            Parameter text = arg("text", Slice.class);
            Parameter contextStart = arg("contextStart", int.class);
            Parameter contextEnd = arg("contextEnd", int.class);
            Parameter groups = arg("groups", int[].class);
            CodeBlock.Builder body = definition.method("findWithStartAnchoredScanPlan", boolean.class, scanPlan, text, contextStart, contextEnd, groups).body();
            body.append(fill(groups));
            body.ret(invokeStatic(attemptLoop, text, contextStart, contextEnd, constantFalse(), groups));
        }

        private void definePosition()
        {
            Parameter input = arg("input", Slice.class);
            Parameter start = arg("start", long.class);
            Parameter occurrence = arg("occurrence", long.class);
            CodeBlock.Builder body = definition.method("position", long.class, input, start, occurrence).access(PUBLIC).body();
            body.append(nullCheck(input, "source is null"));
            Variable byteStart = body.declare("byteStart", invokeStatic(TrinoRegexp.class, "positionSearchStart", int.class, input, start, occurrence));
            body.append(IfStatement.builder()
                    .condition(byteStart.lessThan(constantInt(0)))
                    .then(constantLong(-1).ret())
                    .build());
            Variable groups = body.declare("groups", newArray(int[].class, 2));
            body.append(IfStatement.builder()
                    .condition(invokeStatic(findFrom, input, byteStart, groups).not())
                    .then(constantLong(-1).ret())
                    .build());
            Variable match = body.declare("match", constantLong(1));
            CodeLabel loop = body.label("loop");
            CodeLabel done = body.label("done");
            body.mark(loop);
            body.append(ifThenJump(match.greaterThanOrEqual(occurrence), done));
            body.append(IfStatement.builder()
                    .condition(invokeStatic(findFrom, input, groups.getElement(1), groups).not())
                    .then(constantLong(-1).ret())
                    .build());
            body.append(match.set(match.add(constantLong(1))));
            body.jump(loop);
            body.mark(done);
            BytecodeExpression codePoints = invokeStatic(SliceUtf8.class, "countCodePoints", int.class, input, constantInt(0), groups.getElement(0));
            body.ret(codePoints.cast(long.class).add(constantLong(1)));
        }

        /// Extraction retains the groups its interpreted route retains: an unanchored capture
        /// search keeps every group, so a search that hands off continues with the same captures.
        private void defineExtract()
        {
            Parameter input = arg("input", Slice.class);
            Parameter group = arg("group", int.class);
            CodeBlock.Builder body = definition.method("extract", Slice.class, input, group).access(PUBLIC).body();
            body.append(groupCheck(group));
            body.append(nullCheck(input, "source is null"));
            BytecodeExpression captureLength = anchored
                    ? group.multiply(constantInt(2)).add(constantInt(2))
                    : constantInt(2 * routes.capturingGroupCount() + 2);
            Variable groups = body.declare("groups", newArray(int[].class, inlineIf(group.equal(constantInt(0)), constantInt(2), captureLength)));
            body.append(IfStatement.builder()
                    .condition(invokeStatic(findFrom, input, constantInt(0), groups).not())
                    .then(constantNull(Slice.class).ret())
                    .build());
            Variable start = body.declare(int.class, "start");
            body.ret(groupSlice(body, input, groups, group, start));
        }

        private void defineExtractAll()
        {
            Parameter input = arg("input", Slice.class);
            Parameter group = arg("group", int.class);
            CodeBlock.Builder body = definition.method("extractAll", List.class, input, group).access(PUBLIC).body();
            body.append(groupCheck(group));
            body.append(nullCheck(input, "source is null"));
            Variable groups = body.declare("groups", newArray(int[].class, inlineIf(
                    group.equal(constantInt(0)),
                    constantInt(2),
                    constantInt(2 * routes.capturingGroupCount() + 2))));
            Variable matches = body.declare(List.class, "matches");
            body.append(matches.set(BytecodeExpressions.newInstance(NEW_ARRAY_LIST)));
            Variable start = body.declare("start", constantInt(0));
            Variable groupStart = body.declare(int.class, "groupStart");
            CodeLabel loop = body.label("loop");
            CodeLabel done = body.label("done");
            body.mark(loop);
            body.append(ifThenJump(invokeStatic(findFrom, input, start, groups).not(), done));
            body.append(matches.invoke(LIST_ADD, groupSlice(body, input, groups, group, groupStart)));
            body.append(start.set(groups.getElement(1)));
            body.jump(loop);
            body.mark(done);
            body.ret(invokeStatic(Collections.class, "unmodifiableList", List.class, matches));
        }

        private void defineSplit()
        {
            Parameter input = arg("input", Slice.class);
            CodeBlock.Builder body = definition.method("split", List.class, input).access(PUBLIC).body();
            body.append(nullCheck(input, "source is null"));
            Variable groups = body.declare("groups", newArray(int[].class, 2));
            Variable parts = body.declare(List.class, "parts");
            body.append(parts.set(BytecodeExpressions.newInstance(NEW_ARRAY_LIST)));
            Variable previousEnd = body.declare("previousEnd", constantInt(0));
            CodeLabel loop = body.label("loop");
            CodeLabel done = body.label("done");
            body.mark(loop);
            body.append(ifThenJump(invokeStatic(findFrom, input, previousEnd, groups).not(), done));
            body.append(parts.invoke(LIST_ADD, slice(input, previousEnd, groups.getElement(0))));
            body.append(previousEnd.set(groups.getElement(1)));
            body.jump(loop);
            body.mark(done);
            body.append(parts.invoke(LIST_ADD, slice(input, previousEnd, length(input))));
            body.ret(invokeStatic(Collections.class, "unmodifiableList", List.class, parts));
        }

        /// Replacement builds its plan through [TrinoRegexp], which keeps the template cache. The
        /// pattern's group count and names are constants, computed when the class is generated.
        private void defineReplace()
        {
            Parameter input = arg("input", Slice.class);
            Parameter replacement = arg("replacement", Slice.class);
            MethodDefinition method = definition.method("replace", Slice.class, input, replacement).access(PUBLIC);
            CodeBlock.Builder body = method.body();
            body.append(nullCheck(input, "source is null"));
            body.append(nullCheck(replacement, "replacement is null"));
            BytecodeExpression namedCapturingGroups = boundConstant(routes.pattern().namedCapturingGroups(), Map.class);
            BytecodeExpression newReplacementPlan = method.thisVariable().invoke(NEW_REPLACEMENT_PLAN, input, replacement, constantInt(routes.capturingGroupCount()), namedCapturingGroups);
            Variable replacementPlan = body.declare("replacementPlan", newReplacementPlan);
            Variable maximumGroup = body.declare("maximumGroup", replacementPlan.invoke("maximumCapturingGroup", int.class));
            Variable groups = body.declare("groups", newArray(int[].class, maximumGroup.multiply(constantInt(2)).add(constantInt(2))));
            Variable start = body.declare("start", constantInt(0));
            CodeLabel loop = body.label("loop");
            CodeLabel done = body.label("done");
            body.mark(loop);
            body.append(ifThenJump(invokeStatic(findFrom, input, start, groups).not(), done));
            body.append(replacementPlan.invoke("addMatch", void.class, groups));
            body.append(start.set(groups.getElement(1)));
            body.jump(loop);
            body.mark(done);
            body.ret(replacementPlan.invoke("build", Slice.class));
        }

        private void defineReplaceWithCallback()
        {
            Parameter input = arg("input", Slice.class);
            Parameter replacement = arg("replacement", Function.class);
            CodeBlock.Builder body = definition.method("replace", Slice.class, input, replacement).access(PUBLIC).body();
            body.append(nullCheck(input, "source is null"));
            body.append(nullCheck(replacement, "replacement is null"));
            int groupCount = routes.capturingGroupCount();
            Variable groups = body.declare("groups", newArray(int[].class, 2 * groupCount + 2));
            Variable output = body.declare("output", BytecodeExpressions.newInstance(NEW_DYNAMIC_SLICE_OUTPUT, length(input)));
            Variable previousEnd = body.declare("previousEnd", constantInt(0));
            Variable matched = body.declare("matched", constantFalse());
            Variable values = body.declare(List.class, "values");
            Variable group = body.declare(int.class, "group");
            Variable groupStart = body.declare(int.class, "groupStart");
            Variable value = body.declare(Slice.class, "value");
            CodeLabel loop = body.label("loop");
            CodeLabel nextGroup = body.label("nextGroup");
            CodeLabel groupsDone = body.label("groupsDone");
            CodeLabel done = body.label("done");

            body.mark(loop);
            body.append(ifThenJump(invokeStatic(findFrom, input, previousEnd, groups).not(), done));
            body.append(matched.set(constantTrue()));
            body.append(writeBytes(output, input, previousEnd, groups.getElement(0)));
            body.append(values.set(BytecodeExpressions.newInstance(NEW_ARRAY_LIST_WITH_CAPACITY, constantInt(groupCount))));
            body.append(group.set(constantInt(1)));
            body.mark(nextGroup);
            body.append(ifThenJump(group.greaterThan(constantInt(groupCount)), groupsDone));
            body.append(values.invoke(LIST_ADD, groupSlice(body, input, groups, group, groupStart)));
            body.append(group.increment());
            body.jump(nextGroup);
            body.mark(groupsDone);
            BytecodeExpression unmodifiable = invokeStatic(Collections.class, "unmodifiableList", List.class, values);
            body.append(value.set(replacement.invoke(FUNCTION_APPLY, unmodifiable).cast(Slice.class)));
            body.append(IfStatement.builder()
                    .condition(value.isNull())
                    .then(constantNull(Slice.class).ret())
                    .build());
            body.append(output.invoke("appendBytes", DynamicSliceOutput.class, value));
            body.append(previousEnd.set(groups.getElement(1)));
            body.jump(loop);

            body.mark(done);
            body.append(IfStatement.builder()
                    .condition(matched.not())
                    .then(input.ret())
                    .build());
            body.append(writeBytes(output, input, previousEnd, length(input)));
            body.ret(output.invoke("slice", Slice.class));
        }

        /// Throws the exception of [TrinoRegexp#validateGroup(int)] when the pattern has no
        /// group `group`, with the pattern's group count as a constant.
        private Statement groupCheck(Parameter group)
        {
            BytecodeExpression groupCount = constantInt(routes.capturingGroupCount());
            return IfStatement.builder()
                    .condition(group.lessThan(constantInt(0)).or(group.greaterThan(groupCount)))
                    .then(invokeStatic(TrinoRegexp.class, "invalidGroup", IllegalArgumentException.class, groupCount, group).throwObject())
                    .build();
        }

        /// Returns the final engine method named `name`; being final, a call to it on a pattern
        /// binds without a type check.
        private static Method engineMethod(String name)
        {
            List<Method> methods = Arrays.stream(CompiledPattern.class.getDeclaredMethods())
                    .filter(method -> method.getName().equals(name))
                    .toList();
            if (methods.size() != 1 || !Modifier.isFinal(methods.getFirst().getModifiers())) {
                throw new IllegalStateException("not one final engine method: " + name);
            }
            return methods.getFirst();
        }

        /// Throws [NullPointerException] with `message` when `input` is null, as
        /// [java.util.Objects#requireNonNull(Object,String)] does.
        private static BytecodeExpression nullCheck(Parameter input, String message)
        {
            return invokeStatic(REQUIRE_NON_NULL, input, constantString(message)).pop();
        }

        /// Returns a view of `group` of the current match, or `null` when it did not
        /// participate, as a matcher's group does. The group start is kept in `start`.
        private static BytecodeExpression groupSlice(CodeBlock.Builder body, Parameter input, Variable groups, BytecodeExpression group, Variable start)
        {
            body.append(start.set(groups.getElement(group.multiply(constantInt(2)))));
            return inlineIf(
                    start.lessThan(constantInt(0)),
                    constantNull(Slice.class),
                    slice(input, start, groups.getElement(group.multiply(constantInt(2)).add(constantInt(1)))));
        }

        private static BytecodeExpression slice(BytecodeExpression input, BytecodeExpression start, BytecodeExpression end)
        {
            return input.invoke("slice", Slice.class, start, end.subtract(start));
        }

        private static BytecodeExpression writeBytes(Variable output, BytecodeExpression input, BytecodeExpression start, BytecodeExpression end)
        {
            return output.invoke("writeBytes", void.class, input, start, end.subtract(start));
        }

        private static BytecodeExpression length(BytecodeExpression input)
        {
            return input.invoke("length", int.class);
        }
    }

    private static Constructor<?> constructor(Class<?> type, Class<?>... parameterTypes)
    {
        try {
            return type.getConstructor(parameterTypes);
        }
        catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method declaredMethod(Class<?> type, String name, Class<?>... parameterTypes)
    {
        try {
            return type.getDeclaredMethod(name, parameterTypes);
        }
        catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameterTypes)
    {
        try {
            return type.getMethod(name, parameterTypes);
        }
        catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IfStatement ifThenJump(BytecodeExpression condition, CodeLabel target)
    {
        return IfStatement.builder()
                .condition(condition)
                .then(CodeBlock.blockBuilder().jump(target).build())
                .build();
    }

    private static BytecodeExpression fill(BytecodeExpression groups)
    {
        return invokeStatic(Arrays.class, "fill", void.class, groups, constantInt(-1));
    }

    private static Statement fillIfNotNull(BytecodeExpression groups)
    {
        return IfStatement.builder()
                .condition(groups.isNotNull())
                .then(fill(groups))
                .build();
    }
}
