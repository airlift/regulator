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
import io.airlift.regulator.TrinoScanPlan.Branch;
import io.airlift.regulator.TrinoScanPlan.Kind;
import io.airlift.regulator.TrinoScanPlan.Step;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceUtf8;

import java.io.IOException;
import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.CRC32;

import static io.airlift.classfile.BytecodeExpressions.boundConstant;
import static io.airlift.classfile.BytecodeExpressions.constantFalse;
import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static io.airlift.classfile.BytecodeExpressions.constantLong;
import static io.airlift.classfile.BytecodeExpressions.constantNull;
import static io.airlift.classfile.BytecodeExpressions.constantString;
import static io.airlift.classfile.BytecodeExpressions.constantTrue;
import static io.airlift.classfile.BytecodeExpressions.inlineIf;
import static io.airlift.classfile.BytecodeExpressions.invokeStatic;
import static io.airlift.classfile.BytecodeExpressions.newArray;
import static io.airlift.classfile.Parameter.arg;
import static io.airlift.regulator.TrinoScanPlan.RETRY_OPERATION_BITS;
import static io.airlift.regulator.TrinoScanPlan.RETRY_OPERATION_MASK;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PRIVATE;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static java.util.Objects.requireNonNull;

/// Compiles the case-sensitive [TrinoScanPlan] of one pattern to a hidden class in this
/// package. The attempt loop becomes straight-line code with a label per operation, with the plan's
/// literals, slots, and branch targets as constants; retries keep the interpreter's stack encoding
/// in locals and resume through a switch over the saved operation index. Around it, the class
/// holds the candidate search of an unanchored plan and a public static entry for each Trino
/// operation the pattern routes to the plan. The class implements [CompiledTrinoScan] by
/// delegating to those entries, so a caller can also bind an entry directly.
///
/// An RE2, Java, or Rust pattern, a [Re2] compiled in the RE2 dialect, gets a smaller class
/// from the same attempt loop: an RE2 executor class, which implements [CompiledRe2Scan] with
/// the plan methods it replaces, while the pattern keeps its own routing.
///
/// Setting the system property {@value #DUMP_DIRECTORY_PROPERTY} writes each generated class to
/// that directory before it is defined.
final class TrinoScanKernelGenerator
{
    private static final String DUMP_DIRECTORY_PROPERTY = "io.airlift.regulator.scan-kernel.dump-directory";

    private static final String DUMP_DIRECTORY = System.getProperty(DUMP_DIRECTORY_PROPERTY);
    // Step.matches compares literals of up to this many bytes with two overlapping reads.
    private static final int INLINE_LITERAL_BYTES = 2 * Long.BYTES;

    private TrinoScanKernelGenerator() {}

    enum Attempt
    {
        END_ANCHORED("matchEndAnchored", "EndAnchored"),
        PARTIAL("matchPartial", "Partial"),
        UNANCHORED("matchUnanchoredAt", "Unanchored");

        private final String name;
        private final String className;
        private final String re2ClassName;

        Attempt(String name, String classPrefix)
        {
            this.name = name;
            this.className = CompiledTrinoScan.class.getPackageName() + "." + classPrefix + "CompiledTrinoScan";
            this.re2ClassName = CompiledRe2Scan.class.getPackageName() + "." + classPrefix + "CompiledRe2Scan";
        }

        static Attempt of(TrinoScanPlan plan)
        {
            if (!plan.isAnchoredStart()) {
                return UNANCHORED;
            }
            return plan.isPartialMatch() ? PARTIAL : END_ANCHORED;
        }

        MethodType type()
        {
            if (this == UNANCHORED) {
                return MethodType.methodType(long.class, byte[].class, int.class, int.class, int.class, boolean.class, int[].class);
            }
            return MethodType.methodType(boolean.class, Slice.class, int.class, int.class, boolean.class, int[].class);
        }
    }

    /// Where each Trino operation of a pattern runs, decided once when the pattern compiles. An
    /// operation runs on the generated class exactly when [TrinoRegexp] would run it on the
    /// case-sensitive scan plan, or would hand a short boolean `contains` or `count` to
    /// the ordinary engine (see [Re2#usesOrdinaryShortBooleanMatchForDiagnostics()]), a route
    /// that avoids the interpreter's per-operation dispatch, which the generated class does not
    /// have. Only `contains` can keep another route, a retained literal kernel; every other
    /// operation reaches the plan.
    ///
    /// @param contains whether full-Slice find reaches the plan rather than a retained literal kernel
    /// @param count how count reaches the plan
    record Routes(Re2 pattern, TrinoScanPlan plan, boolean contains, Re2.TrinoScanPlanCount count)
    {
        Routes
        {
            requireNonNull(pattern, "pattern is null");
            requireNonNull(plan, "plan is null");
            requireNonNull(count, "count is null");
        }

        /// Returns the routes of a pattern compiled to the base [TrinoRegexp], or `null`
        /// when it has no case-sensitive scan plan or an operation other than `contains`
        /// would skip it.
        static Routes of(Re2 pattern)
        {
            TrinoScanPlan plan = pattern.caseSensitiveTrinoScanPlan();
            if (plan == null) {
                return null;
            }
            // A pattern with a scan plan has neither a single-byte matcher, which group-zero
            // operations ask before the pattern, nor a count that skips the plan. A pattern that
            // breaks either rule keeps the interpreter rather than reach the generated class.
            Re2.TrinoScanPlanCount count = pattern.trinoScanPlanCount();
            if (pattern.hasSingleByteMatcher() || count == Re2.TrinoScanPlanCount.OTHER) {
                return null;
            }
            return new Routes(pattern, plan, pattern.usesTrinoScanPlanForFindForDiagnostics(), count);
        }

        boolean ordinaryHandoff()
        {
            return contains && pattern.usesOrdinaryUnanchoredBooleanSearchForDiagnostics();
        }

        int capturingGroupCount()
        {
            return pattern.capturingGroupCount();
        }
    }

    /// A generated class for one pattern: its [CompiledTrinoScan] instance and a handle on
    /// each static entry, with `contains` `null` when the routes do not reach it. The
    /// replace handle takes the owning [TrinoRegexp] first, and the extraction handles take
    /// the group last.
    record CompiledScan(
            CompiledTrinoScan scan,
            MethodHandle contains,
            MethodHandle count,
            MethodHandle position,
            MethodHandle extract,
            MethodHandle extractAll,
            MethodHandle split,
            MethodHandle replace,
            MethodHandle replaceWithCallback)
    {
        CompiledScan
        {
            requireNonNull(scan, "scan is null");
            requireNonNull(count, "count is null");
            requireNonNull(position, "position is null");
            requireNonNull(extract, "extract is null");
            requireNonNull(extractAll, "extractAll is null");
            requireNonNull(split, "split is null");
            requireNonNull(replace, "replace is null");
            requireNonNull(replaceWithCallback, "replaceWithCallback is null");
        }
    }

    /// A generated class and the classfile library's report on compiling it.
    record ReportedScan(CompiledScan compiled, CompilationReport report)
    {
        ReportedScan
        {
            requireNonNull(compiled, "compiled is null");
            requireNonNull(report, "report is null");
        }
    }

    /// Generates and defines the class for a pattern with `routes`. Failures propagate.
    static CompiledScan generateCompiledScan(Routes routes)
    {
        return generate(routes).compiled();
    }

    /// Generates and defines the class for a pattern with `routes` as
    /// [#generateCompiledScan] does, also returning the compilation report.
    static ReportedScan generateCompiledScanForDiagnostics(Routes routes)
    {
        return generate(routes);
    }

    private static ReportedScan generate(Routes routes)
    {
        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = compile(definer, ScanEmitter.forTrino(routes));
        Class<? extends CompiledTrinoScan> scanClass = define(definer, unit, CompiledTrinoScan.class);
        CompiledTrinoScan scan = newInstance(scanClass);
        MethodType extract = MethodType.methodType(Slice.class, Slice.class, int.class);
        MethodType extractAll = MethodType.methodType(List.class, Slice.class, int.class);
        CompiledScan compiled = new CompiledScan(
                scan,
                routes.contains() ? findStatic(scanClass, "containsEntry", MethodType.methodType(boolean.class, Slice.class)) : null,
                findStatic(scanClass, "countEntry", MethodType.methodType(long.class, Slice.class)),
                findStatic(scanClass, "positionEntry", MethodType.methodType(long.class, Slice.class, long.class, long.class)),
                findStatic(scanClass, "extractEntry", extract),
                findStatic(scanClass, "extractAllEntry", extractAll),
                findStatic(scanClass, "splitEntry", MethodType.methodType(List.class, Slice.class)),
                findStatic(scanClass, "replaceEntry", MethodType.methodType(Slice.class, TrinoRegexp.class, Slice.class, Slice.class)),
                findStatic(scanClass, "replaceEntry", MethodType.methodType(Slice.class, Slice.class, Function.class)));
        return new ReportedScan(compiled, unit.report());
    }

    /// Returns the attempt loop of `scan`, generated for `plan`, with the signature of
    /// the plan executor it replaces.
    static MethodHandle attemptHandleForDiagnostics(CompiledTrinoScan scan, TrinoScanPlan plan)
    {
        Attempt attempt = Attempt.of(plan);
        return findStatic(scan.getClass(), attempt.name, attempt.type());
    }

    /// Returns the class file generated for a pattern with `routes`, without defining it.
    static byte[] classfileForDiagnostics(Routes routes)
    {
        CompiledUnit unit = compile(HiddenClassDefiner.builder(MethodHandles.lookup()).build(), ScanEmitter.forTrino(routes));
        return unit.classfile(unit.primaryType());
    }

    /// Generates and defines the executor class of an RE2, Java, or Rust pattern for its
    /// case-sensitive scan `plan`, with a `searchBeforeHandoff` entry when
    /// `ordinaryHandoff` says that full-Slice find hands off to the ordinary engine. Failures
    /// propagate.
    static CompiledRe2Scan generateCompiledRe2Scan(TrinoScanPlan plan, boolean ordinaryHandoff)
    {
        HiddenClassDefiner definer = HiddenClassDefiner.builder(MethodHandles.lookup()).build();
        CompiledUnit unit = compile(definer, ScanEmitter.forRe2(plan, ordinaryHandoff));
        return newInstance(define(definer, unit, CompiledRe2Scan.class));
    }

    /// Returns the RE2 executor class file that [#generateCompiledRe2Scan] would define.
    static byte[] re2ClassfileForDiagnostics(TrinoScanPlan plan, boolean ordinaryHandoff)
    {
        CompiledUnit unit = compile(HiddenClassDefiner.builder(MethodHandles.lookup()).build(), ScanEmitter.forRe2(plan, ordinaryHandoff));
        return unit.classfile(unit.primaryType());
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

    private static <T> T newInstance(Class<? extends T> scanClass)
    {
        try {
            return scanClass.getConstructor().newInstance();
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
        private final TrinoScanPlan plan;
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

        private KernelEmitter(TrinoScanPlan plan, Attempt attempt)
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
                    BytecodeExpression run = boundConstant(step.run(), TrinoScanPlanRun.class);
                    BytecodeExpression delimiter = constantInt(step.delimiter()).cast(byte.class);
                    body.append(stop.set(invokeStatic(TrinoScanKernelSupport.class, "re2RunMatchBefore", int.class, run, bytes, cursor, end, delimiter)));
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
                                .and(invokeStatic(TrinoScanKernelSupport.class, "endsBeforeJavaFinalTerminator", boolean.class, bytes, base, end, cursor)));
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

        /// Emits a run that calls the [TrinoScanKernelSupport] methods named `match` and
        /// `matchMinimum`: `runMatch` and `runMatchMinimum` for a `RUN`, and
        /// `re2RunMatch` and `re2RunMatchMinimum`, which read characters as RE2 does,
        /// for an `RE2_RUN`. An `RE2_SET_RUN` calls `re2SetRunMatch` and
        /// `re2SetRunMatchMinimum`, which read characters by the run's member encodings.
        private void emitRun(int index, Step step, String match, String matchMinimum)
        {
            BytecodeExpression run = boundConstant(step.run(), TrinoScanPlanRun.class);
            if (attempt != Attempt.END_ANCHORED && plan.isBooleanTail(index)) {
                // The continuation cannot fail, so the run's minimum decides this path.
                CodeBlock.Builder tail = CodeBlock.blockBuilder();
                BytecodeExpression minimum = invokeStatic(TrinoScanKernelSupport.class, matchMinimum, int.class, run, bytes, cursor, end);
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
            body.append(stop.set(invokeStatic(TrinoScanKernelSupport.class, match, int.class, run, bytes, cursor, end)));
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
            BytecodeExpression firstRead = invokeStatic(TrinoScanKernelSupport.class, read, type, bytes, cursor);
            if (step.lastOffset() == 0) {
                return firstRead.notEqual(first);
            }
            BytecodeExpression last = wide ? constantLong(step.last()) : constantInt((int) step.last());
            BytecodeExpression lastRead = invokeStatic(TrinoScanKernelSupport.class, read, type, bytes, lastPosition);
            return firstRead.bitwiseXor(first).bitwiseOr(lastRead.bitwiseXor(last)).notEqual(zero);
        }

        private BytecodeExpression findByte(byte target)
        {
            return invokeStatic(TrinoScanKernelSupport.class, "findByte", int.class, bytes, cursor, end, constantInt(target).cast(byte.class));
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
                case END_ANCHORED -> body.throwObject(BytecodeExpressions.newInstance(IllegalStateException.class, constantString("scan kernel ran past END")));
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
                resume.defaultCase(BytecodeExpressions.newInstance(IllegalStateException.class, constantString("corrupt scan kernel retry stack")).throwObject());
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
                            .condition(groups.isNotNull())
                            .then(CodeBlock.block(
                                    groups.setElement(constantInt(0), matchStart.subtract(base)),
                                    groups.setElement(constantInt(1), cursor.subtract(base))))
                            .build(),
                    cursor.cast(long.class).ret());
        }
    }

    /// Emits the class of one pattern. The class of a Trino pattern holds the attempt loop, the
    /// search driver of an unanchored plan, and a static entry for each operation that reaches the
    /// plan, named for the operation with an `Entry` suffix; it implements
    /// [CompiledTrinoScan] with one-line delegates to those entries. An RE2 executor class
    /// holds the attempt loop and the searches of an unanchored plan, and implements
    /// [CompiledRe2Scan]. A plan never matches empty, so every match loop resumes at the
    /// previous match end, as a matcher does. Scans call the copies in
    /// [TrinoScanKernelSupport], not the interpreter's helpers; other shared code is reached
    /// only through static calls or calls on class-data constants.
    private static final class ScanEmitter
    {
        // Whether this emits an RE2 executor class rather than the class of a Trino pattern.
        private final boolean re2;
        // Null for an RE2 executor class.
        private final Routes routes;
        private final TrinoScanPlan plan;
        private final boolean ordinaryHandoff;
        private final Attempt attempt;
        private final boolean anchored;
        private final Map<String, MethodDefinition> entries = new HashMap<>();

        private ClassDefinition definition;
        private MethodDefinition attemptLoop;
        private MethodDefinition search;
        private MethodDefinition searchBeforeHandoff;
        private MethodDefinition find;

        /// The context and attempt arguments of a region search. A search without one covers the
        /// whole input, unanchored and without a full-match requirement.
        private record Region(Parameter contextStart, Parameter contextEnd, Parameter anchored, Parameter fullMatch) {}

        private ScanEmitter(boolean re2, Routes routes, TrinoScanPlan plan, boolean ordinaryHandoff)
        {
            this.re2 = re2;
            this.routes = routes;
            this.plan = requireNonNull(plan, "plan is null");
            this.ordinaryHandoff = ordinaryHandoff;
            this.attempt = Attempt.of(plan);
            this.anchored = attempt != Attempt.UNANCHORED;
        }

        private static ScanEmitter forTrino(Routes routes)
        {
            return new ScanEmitter(false, routes, routes.plan(), routes.ordinaryHandoff());
        }

        private static ScanEmitter forRe2(TrinoScanPlan plan, boolean ordinaryHandoff)
        {
            return new ScanEmitter(true, null, plan, ordinaryHandoff);
        }

        private ClassModel define()
        {
            return re2 ? defineRe2() : defineTrino();
        }

        private void defineClass(String className, Class<?> type)
        {
            if (plan.usesAsciiFoldedExecutor()) {
                throw new IllegalArgumentException("ASCII-folded scan plans have no generated class");
            }
            definition = ClassDefinition.define(ClassDesc.of(className)).access(PUBLIC, FINAL).addInterface(type);
            definition.defaultConstructor().access(PUBLIC);
            attemptLoop = new KernelEmitter(plan, attempt).defineAttempt(definition);
        }

        /// The RE2 executor class: the attempt loop of a start-anchored plan, or the region search
        /// of an unanchored one, each with the signature of the plan method it replaces. The pattern
        /// keeps its own routing and finishes a search that handed off.
        private ClassModel defineRe2()
        {
            defineClass(attempt.re2ClassName, CompiledRe2Scan.class);
            if (anchored) {
                entries.put(key("match", attempt.type()), attemptLoop);
            }
            else {
                defineRegionSearch();
                if (ordinaryHandoff) {
                    defineSearchBeforeHandoff();
                }
            }
            defineInterface(CompiledRe2Scan.class);
            return definition.build();
        }

        private ClassModel defineTrino()
        {
            defineClass(attempt.className, CompiledTrinoScan.class);
            if (!anchored) {
                search = defineSearch();
                if (ordinaryHandoff) {
                    searchBeforeHandoff = defineSearchBeforeHandoff();
                }
            }
            defineFind();
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
            defineInterface(CompiledTrinoScan.class);
            return definition.build();
        }

        /// The unanchored search of [TrinoScanPlan#search] over the whole input, with the
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

        /// The search of [TrinoScanPlan#search] over a region, with the same arguments and
        /// results, as the `search` entry of an RE2 executor class.
        private void defineRegionSearch()
        {
            Parameter input = arg("input", Slice.class);
            Parameter contextStart = arg("contextStart", int.class);
            Parameter contextEnd = arg("contextEnd", int.class);
            Parameter start = arg("start", int.class);
            Parameter anchoredSearch = arg("anchored", boolean.class);
            Parameter fullMatch = arg("fullMatch", boolean.class);
            Parameter groups = arg("groups", int[].class);
            MethodType type = MethodType.methodType(int.class, Slice.class, int.class, int.class, int.class, boolean.class, boolean.class, int[].class);
            MethodDefinition method = entry("search", type, input, contextStart, contextEnd, start, anchoredSearch, fullMatch, groups);
            emitCandidateLoop(method.body(), input, new Region(contextStart, contextEnd, anchoredSearch, fullMatch), start, groups, false);
        }

        /// The capture-free search of [TrinoScanPlan#searchBeforeHandoff] over the whole
        /// input, with the same results. An RE2 executor class makes it the
        /// `searchBeforeHandoff` entry.
        private MethodDefinition defineSearchBeforeHandoff()
        {
            Parameter input = arg("input", Slice.class);
            MethodDefinition method = re2
                    ? entry("searchBeforeHandoff", MethodType.methodType(int.class, Slice.class), input)
                    : definition.method("searchBeforeHandoff", int.class, input).access(PRIVATE, STATIC);
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

            if (region != null) {
                // An anchored search makes at most one attempt and never hands off.
                body.append(IfStatement.builder()
                        .condition(region.anchored())
                        .then(CodeBlock.block(
                                IfStatement.builder()
                                        .condition(candidate.lessThan(end).and(attemptAtCandidate.greaterThanOrEqual(constantLong(0))))
                                        .then(constantInt(TrinoScanPlan.SEARCH_MATCHED).ret())
                                        .build(),
                                CodeBlock.blockBuilder().jump(done).build()))
                        .build());
            }
            body.mark(loop);
            body.append(ifThenJump(candidate.greaterThanOrEqual(end), done));
            BytecodeExpression handOff = failedWork
                    .subtract(constantLong(TrinoScanPlan.WORK_BUDGET_FACTOR).multiply(candidate.subtract(searchStart).cast(long.class)))
                    .greaterThan(constantLong(plan.workBudgetBytes()));
            if (beforeHandoff) {
                handOff = handOff.or(failures.notEqual(constantInt(0))
                        .and(failures.remainder(constantInt(TrinoScanPlan.HANDOFF_ATTEMPTS)).equal(constantInt(0)))
                        .and(candidate.subtract(searchStart).cast(long.class)
                                .lessThan(failures.cast(long.class).multiply(constantLong(TrinoScanPlan.HANDOFF_BYTES))))
                        .and(end.subtract(candidate).greaterThanOrEqual(constantInt(TrinoScanPlan.HANDOFF_REMAINING_BYTES))));
            }
            CodeBlock.Builder handOffBlock = CodeBlock.blockBuilder();
            if (!beforeHandoff) {
                handOffBlock.append(fillIfNotNull(groups));
            }
            handOffBlock.ret(candidate.subtract(offset));
            body.append(IfStatement.builder()
                    .condition(handOff)
                    .then(handOffBlock.build())
                    .build());

            emitNextCandidate(body, wholeLiteral, bytes, candidate, end, done);
            body.append(result.set(attemptAtCandidate));
            body.append(IfStatement.builder()
                    .condition(result.greaterThanOrEqual(constantLong(0)))
                    .then(constantInt(TrinoScanPlan.SEARCH_MATCHED).ret())
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
                        TrinoScanKernelSupport.class,
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
            body.ret(constantInt(TrinoScanPlan.NO_MATCH));
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
                        ? invokeStatic(TrinoScanKernelSupport.class, "findLiteralAtProbe", int.class, bytes, candidate, end, literal, constantInt(probeOffset))
                        : invokeStatic(TrinoScanKernelSupport.class, "findLiteral", int.class, bytes, candidate, end, literal);
            }
            else if (leading.kind() == Kind.LITERAL) {
                next = invokeStatic(TrinoScanKernelSupport.class, "findByte", int.class, bytes, candidate, end, constantInt(leading.literal[0]).cast(byte.class));
            }
            else if (TrinoScanPlan.isRun(leading) && leading.run().minimum() > 0) {
                next = invokeStatic(TrinoScanKernelSupport.class, "runFindCandidate", int.class, boundConstant(leading.run(), TrinoScanPlanRun.class), bytes, candidate, end);
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
            if (TrinoScanPlan.isRun(plan.step(plan.leadingOperation()))) {
                // A failed minimum-width check must not retry from a continuation byte of
                // otherwise valid UTF-8.
                BytecodeExpression decoded = invokeStatic(TrinoScanKernelSupport.class, "decode", long.class, bytes, candidate, end);
                return candidate.add(invokeStatic(Utf8.class, "decodedWidth", int.class, decoded));
            }
            return candidate.add(constantInt(1));
        }

        private void defineFind()
        {
            Parameter input = arg("input", Slice.class);
            Parameter start = arg("start", int.class);
            Parameter groups = arg("groups", int[].class);
            find = entry("find", MethodType.methodType(boolean.class, Slice.class, int.class, int[].class), input, start, groups);
            CodeBlock.Builder body = find.body();
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
            body.ret(finishSearch(body, input, result, groups));
        }

        /// Returns whether an unanchored search that returned `result` matched, continuing
        /// one that handed off on the ordinary engine.
        private BytecodeExpression finishSearch(CodeBlock.Builder body, Parameter input, Variable result, BytecodeExpression groups)
        {
            body.append(IfStatement.builder()
                    .condition(result.lessThan(constantInt(0)))
                    .then(result.equal(constantInt(TrinoScanPlan.SEARCH_MATCHED)).ret())
                    .build());
            return boundConstant(routes.pattern(), Re2.class)
                    .invoke("continueTrinoScanSearch", boolean.class, input, result, groups);
        }

        private void defineContains()
        {
            Parameter input = arg("input", Slice.class);
            CodeBlock.Builder body = entry("contains", MethodType.methodType(boolean.class, Slice.class), input).body();
            BytecodeExpression noGroups = constantNull(int[].class);
            if (anchored) {
                body.ret(invokeStatic(attemptLoop, input, constantInt(0), length(input), constantFalse(), noGroups));
                return;
            }
            Variable result = body.declare("result", searchBeforeHandoff != null
                    ? invokeStatic(searchBeforeHandoff, input)
                    : invokeStatic(search, input, constantInt(0), noGroups));
            body.ret(finishSearch(body, input, result, noGroups));
        }

        private void defineCount()
        {
            Parameter input = arg("input", Slice.class);
            CodeBlock.Builder body = entry("count", MethodType.methodType(long.class, Slice.class), input).body();
            if (routes.count() == Re2.TrinoScanPlanCount.SINGLE_ATTEMPT) {
                if (!anchored) {
                    throw new IllegalStateException("an unanchored plan counts by searching");
                }
                body.ret(inlineIf(
                        invokeStatic(attemptLoop, input, constantInt(0), length(input), constantFalse(), constantNull(int[].class)),
                        constantLong(1),
                        constantLong(0)));
                return;
            }
            Variable groups = body.declare("groups", newArray(int[].class, 2));
            Variable length = body.declare("length", length(input));
            Variable count = body.declare("count", constantLong(0));
            Variable start = body.declare("start", constantInt(0));
            CodeLabel loop = body.label("loop");
            CodeLabel done = body.label("done");
            body.mark(loop);
            body.append(ifThenJump(start.greaterThanOrEqual(length), done));
            body.append(ifThenJump(invokeStatic(find, input, start, groups).not(), done));
            body.append(count.set(count.add(constantLong(1))));
            body.append(start.set(groups.getElement(1)));
            body.jump(loop);
            body.mark(done);
            body.ret(count);
        }

        private void definePosition()
        {
            Parameter input = arg("input", Slice.class);
            Parameter start = arg("start", long.class);
            Parameter occurrence = arg("occurrence", long.class);
            CodeBlock.Builder body = entry("position", MethodType.methodType(long.class, Slice.class, long.class, long.class), input, start, occurrence).body();
            Variable byteStart = body.declare("byteStart", invokeStatic(TrinoRegexp.class, "positionSearchStart", int.class, input, start, occurrence));
            body.append(IfStatement.builder()
                    .condition(byteStart.lessThan(constantInt(0)))
                    .then(constantLong(-1).ret())
                    .build());
            Variable groups = body.declare("groups", newArray(int[].class, 2));
            body.append(IfStatement.builder()
                    .condition(invokeStatic(find, input, byteStart, groups).not())
                    .then(constantLong(-1).ret())
                    .build());
            Variable match = body.declare("match", constantLong(1));
            CodeLabel loop = body.label("loop");
            CodeLabel done = body.label("done");
            body.mark(loop);
            body.append(ifThenJump(match.greaterThanOrEqual(occurrence), done));
            body.append(IfStatement.builder()
                    .condition(invokeStatic(find, input, groups.getElement(1), groups).not())
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
            CodeBlock.Builder body = entry("extract", MethodType.methodType(Slice.class, Slice.class, int.class), input, group).body();
            BytecodeExpression captureLength = anchored
                    ? group.multiply(constantInt(2)).add(constantInt(2))
                    : constantInt(2 * routes.capturingGroupCount() + 2);
            Variable groups = body.declare("groups", newArray(int[].class, inlineIf(group.equal(constantInt(0)), constantInt(2), captureLength)));
            body.append(IfStatement.builder()
                    .condition(invokeStatic(find, input, constantInt(0), groups).not())
                    .then(constantNull(Slice.class).ret())
                    .build());
            Variable start = body.declare(int.class, "start");
            body.ret(groupSlice(body, input, groups, group, start));
        }

        private void defineExtractAll()
        {
            Parameter input = arg("input", Slice.class);
            Parameter group = arg("group", int.class);
            CodeBlock.Builder body = entry("extractAll", MethodType.methodType(List.class, Slice.class, int.class), input, group).body();
            Variable groups = body.declare("groups", newArray(int[].class, inlineIf(
                    group.equal(constantInt(0)),
                    constantInt(2),
                    constantInt(2 * routes.capturingGroupCount() + 2))));
            Variable matches = body.declare(List.class, "matches");
            body.append(matches.set(BytecodeExpressions.newInstance(ArrayList.class)));
            Variable start = body.declare("start", constantInt(0));
            Variable groupStart = body.declare(int.class, "groupStart");
            CodeLabel loop = body.label("loop");
            CodeLabel done = body.label("done");
            body.mark(loop);
            body.append(ifThenJump(invokeStatic(find, input, start, groups).not(), done));
            body.append(matches.invoke(method(List.class, "add", Object.class), groupSlice(body, input, groups, group, groupStart)));
            body.append(start.set(groups.getElement(1)));
            body.jump(loop);
            body.mark(done);
            body.ret(invokeStatic(Collections.class, "unmodifiableList", List.class, matches));
        }

        private void defineSplit()
        {
            Parameter input = arg("input", Slice.class);
            CodeBlock.Builder body = entry("split", MethodType.methodType(List.class, Slice.class), input).body();
            Variable groups = body.declare("groups", newArray(int[].class, 2));
            Variable parts = body.declare(List.class, "parts");
            body.append(parts.set(BytecodeExpressions.newInstance(ArrayList.class)));
            Variable previousEnd = body.declare("previousEnd", constantInt(0));
            CodeLabel loop = body.label("loop");
            CodeLabel done = body.label("done");
            body.mark(loop);
            body.append(ifThenJump(invokeStatic(find, input, previousEnd, groups).not(), done));
            body.append(parts.invoke(method(List.class, "add", Object.class), slice(input, previousEnd, groups.getElement(0))));
            body.append(previousEnd.set(groups.getElement(1)));
            body.jump(loop);
            body.mark(done);
            body.append(parts.invoke(method(List.class, "add", Object.class), slice(input, previousEnd, length(input))));
            body.ret(invokeStatic(Collections.class, "unmodifiableList", List.class, parts));
        }

        /// Replacement keeps its plan and template cache on the owning [TrinoRegexp].
        private void defineReplace()
        {
            Parameter owner = arg("owner", TrinoRegexp.class);
            Parameter input = arg("input", Slice.class);
            Parameter replacement = arg("replacement", Slice.class);
            CodeBlock.Builder body = entry("replace", MethodType.methodType(Slice.class, TrinoRegexp.class, Slice.class, Slice.class), owner, input, replacement).body();
            Variable replacementPlan = body.declare("replacementPlan", owner.invoke("newReplacementPlan", TrinoReplacementPlan.class, input, replacement));
            Variable maximumGroup = body.declare("maximumGroup", replacementPlan.invoke("maximumCapturingGroup", int.class));
            Variable groups = body.declare("groups", newArray(int[].class, maximumGroup.multiply(constantInt(2)).add(constantInt(2))));
            Variable start = body.declare("start", constantInt(0));
            CodeLabel loop = body.label("loop");
            CodeLabel done = body.label("done");
            body.mark(loop);
            body.append(ifThenJump(invokeStatic(find, input, start, groups).not(), done));
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
            CodeBlock.Builder body = entry("replace", MethodType.methodType(Slice.class, Slice.class, Function.class), input, replacement).body();
            int groupCount = routes.capturingGroupCount();
            Variable groups = body.declare("groups", newArray(int[].class, 2 * groupCount + 2));
            Variable output = body.declare("output", BytecodeExpressions.newInstance(DynamicSliceOutput.class, length(input)));
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
            body.append(ifThenJump(invokeStatic(find, input, previousEnd, groups).not(), done));
            body.append(matched.set(constantTrue()));
            body.append(writeBytes(output, input, previousEnd, groups.getElement(0)));
            body.append(values.set(BytecodeExpressions.newInstance(ArrayList.class, constantInt(groupCount))));
            body.append(group.set(constantInt(1)));
            body.mark(nextGroup);
            body.append(ifThenJump(group.greaterThan(constantInt(groupCount)), groupsDone));
            body.append(values.invoke(method(List.class, "add", Object.class), groupSlice(body, input, groups, group, groupStart)));
            body.append(group.increment());
            body.jump(nextGroup);
            body.mark(groupsDone);
            BytecodeExpression unmodifiable = invokeStatic(Collections.class, "unmodifiableList", List.class, values);
            body.append(value.set(replacement.invoke(method(Function.class, "apply", Object.class), unmodifiable).cast(Slice.class)));
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

        private void defineInterface(Class<?> type)
        {
            Method[] methods = type.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::toString));
            for (Method method : methods) {
                Class<?>[] types = method.getParameterTypes();
                Parameter[] parameters = new Parameter[types.length];
                for (int index = 0; index < types.length; index++) {
                    parameters[index] = arg("argument" + index, types[index]);
                }
                MethodDefinition delegate = definition.method(method.getName(), method.getReturnType(), parameters)
                        .access(PUBLIC);
                MethodDefinition target = entries.get(key(method.getName(), MethodType.methodType(method.getReturnType(), types)));
                if (target == null) {
                    delegate.body().throwObject(BytecodeExpressions.newInstance(UnsupportedOperationException.class, constantString(method.getName())));
                }
                else {
                    delegate.body().ret(invokeStatic(target, parameters));
                }
            }
        }

        /// Defines the public static entry of `operation`, which the interface method of the
        /// same name and type delegates to.
        private MethodDefinition entry(String operation, MethodType type, Parameter... parameters)
        {
            MethodDefinition method = definition.method(operation + "Entry", type.returnType(), parameters)
                    .access(PUBLIC, STATIC);
            entries.put(key(operation, type), method);
            return method;
        }

        private static String key(String operation, MethodType type)
        {
            return operation + type.toMethodDescriptorString();
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

    private static Method method(Class<?> type, String name, Class<?>... parameterTypes)
    {
        try {
            return type.getMethod(name, parameterTypes);
        }
        catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CodeBlock ifThenJump(BytecodeExpression condition, CodeLabel target)
    {
        return CodeBlock.block(IfStatement.builder()
                .condition(condition)
                .then(CodeBlock.blockBuilder().jump(target).build())
                .build());
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
