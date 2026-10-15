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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodHandles;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;

/**
 * Measures one constant pattern the way a query engine binds it: a per-pattern hidden class that
 * invokes an operation handle held as a class-data constant. {@code INTERPRETER} binds the public
 * operation to the pattern, which is the constant-pattern path without generated code;
 * {@code GENERATED} binds the handle of the pattern's generated class. The {@code boundCaller}
 * method runs the bound handle under either executor. The {@code publicApi} method runs the same
 * operation through the public API, choosing the operation and extract group on every row, so
 * compare it across executors rather than against {@code boundCaller}.
 * <p>
 * Targets are four dot-all {@code OPTIONAL_MIXED} plans of {@link BenchmarkTrinoScanPlan}, an
 * unanchored {@code UNANCHORED_MIXED} plan, and the short-row {@code URL_SHORT} plan.
 * {@code ISOLATED} runs only the target pattern; {@code INTERLEAVED} first trains every pattern of
 * the target's workload through shared code and through their own callers.
 * <p>
 * The generated API is resolved reflectively, so this source also runs against a build without
 * it when given {@code -p executor=INTERPRETER}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkTrinoScanKernelDispatch
{
    private static final int BATCH = 256;
    // Each target cycles through this many of its rows, a power of two so that callers mask the
    // row index.
    static final int INPUTS = 16;
    private static final int INPUT_MASK = INPUTS - 1;
    private static volatile int trainingSink;

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"INTERPRETER", "GENERATED"})
        public String executor;

        @Param({"ISOLATED", "INTERLEAVED"})
        public String profile;

        @Param({"contains", "count", "extract", "position"})
        public String operation;

        @Param({"PLAN_0", "PLAN_1", "PLAN_2", "PLAN_3", "UNANCHORED", "SHORT"})
        public String target;

        Slice expression;
        TrinoRegexp pattern;
        Slice[] inputs;
        MethodHandle caller;
        private List<TrinoRegexp> trainingPatterns;
        private List<Slice[]> trainingInputs;
        private List<MethodHandle> otherCallers;
        private int cursor;

        @Setup(Level.Trial)
        public void setup()
                throws Throwable
        {
            BenchmarkTrinoScanPlan.BenchmarkData source = new BenchmarkTrinoScanPlan.BenchmarkData();
            source.workload = switch (target) {
                case "UNANCHORED" -> "UNANCHORED_MIXED";
                case "SHORT" -> "URL_SHORT";
                default -> "OPTIONAL_MIXED";
            };
            source.dotAll = true;
            source.setup();
            int count = source.expressions.length;
            int selected = target.startsWith("PLAN_") ? Integer.parseInt(target.substring("PLAN_".length())) : 0;
            boolean generated = executor.equals("GENERATED");
            trainingPatterns = new ArrayList<>();
            trainingInputs = new ArrayList<>();
            for (int plan = 0; plan < count; plan++) {
                if (profile.equals("ISOLATED") && plan != selected) {
                    continue;
                }
                TrinoRegexp compiled = compile(source.expressions[plan], generated);
                Slice[] rows = new Slice[INPUTS];
                for (int row = 0; row < INPUTS; row++) {
                    rows[row] = source.inputs[(row * count + plan) % source.inputs.length];
                }
                if (plan == selected) {
                    expression = source.expressions[plan];
                    pattern = compiled;
                    inputs = rows;
                }
                trainingPatterns.add(compiled);
                trainingInputs.add(rows);
            }
            // Populate shared method profiles before the pattern-specific caller exists.
            train(20_000);
            caller = batchCaller(handle(pattern, operation, generated), group(pattern), "Target");
            otherCallers = new ArrayList<>();
            if (profile.equals("INTERLEAVED")) {
                for (int index = 0; index < trainingPatterns.size(); index++) {
                    TrinoRegexp other = trainingPatterns.get(index);
                    otherCallers.add(batchCaller(handle(other, operation, generated), group(other), "Other" + index));
                }
            }
            if ((int) caller.invokeExact(inputs, 0) != expectedBatch(pattern, operation, inputs, 0)) {
                throw new IllegalStateException("Bound caller checksum mismatch");
            }
        }

        @Setup(Level.Iteration)
        public void refresh()
                throws Throwable
        {
            if (!profile.equals("ISOLATED")) {
                train(2_000);
            }
            for (int round = 0; round < 100; round++) {
                for (int index = 0; index < otherCallers.size(); index++) {
                    trainingSink = (int) otherCallers.get(index).invokeExact(trainingInputs.get(index), round);
                }
            }
        }

        private void train(int rounds)
        {
            int checksum = 0;
            for (int round = 0; round < rounds; round++) {
                for (int index = 0; index < trainingPatterns.size(); index++) {
                    checksum += evaluate(trainingPatterns.get(index), operation, trainingInputs.get(index)[round & INPUT_MASK]);
                }
            }
            trainingSink = checksum;
        }
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public int publicApi(BenchmarkData data)
    {
        return expectedBatch(data.pattern, data.operation, data.inputs, data.cursor++);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public int boundCaller(BenchmarkData data)
            throws Throwable
    {
        return (int) data.caller.invokeExact(data.inputs, data.cursor++);
    }

    static int expectedBatch(TrinoRegexp pattern, String operation, Slice[] inputs, int start)
    {
        int checksum = 0;
        for (int index = 0; index < BATCH; index++) {
            checksum += evaluate(pattern, operation, inputs[(start + index) & INPUT_MASK]);
        }
        return checksum;
    }

    /**
     * Runs {@code operation} through the public API, reduced to the value the bound caller
     * adds to its checksum.
     */
    static int evaluate(TrinoRegexp pattern, String operation, Slice input)
    {
        return switch (operation) {
            case "contains" -> pattern.contains(input) ? 1 : 0;
            case "count" -> (int) pattern.count(input);
            case "extract" -> checksum(pattern.extract(input, group(pattern)));
            case "position" -> (int) pattern.position(input, 1, 1);
            default -> throw new IllegalArgumentException("unknown operation: " + operation);
        };
    }

    static int checksum(Slice slice)
    {
        return slice == null ? 0 : slice.length() + 1;
    }

    static int group(TrinoRegexp pattern)
    {
        return pattern.capturingGroupCount() == 0 ? 0 : 1;
    }

    /**
     * Compiles {@code expression}, with generated bytecode when {@code generated} is set,
     * requiring the result to match that choice.
     */
    static TrinoRegexp compile(Slice expression, boolean generated)
            throws ReflectiveOperationException
    {
        if (!generated) {
            return TrinoRegexp.compile(expression);
        }
        TrinoRegexp.Options options = TrinoRegexp.Options.defaults();
        TrinoRegexp.Options.class.getMethod("setGenerateBytecode", boolean.class).invoke(options, true);
        TrinoRegexp compiled = TrinoRegexp.compile(expression, options);
        if (!(boolean) TrinoRegexp.class.getMethod("usesGeneratedBytecode").invoke(compiled)) {
            throw new IllegalStateException("Pattern has no generated class: " + expression.toStringUtf8());
        }
        return compiled;
    }

    /**
     * Returns the handle a caller binds for {@code operation}: the public method bound to the
     * pattern, or the pattern's generated handle. The caller supplies the extract group of the
     * public method and the start and occurrence of position.
     */
    static MethodHandle handle(TrinoRegexp pattern, String operation, boolean generated)
            throws ReflectiveOperationException
    {
        if (!generated) {
            MethodType type = switch (operation) {
                case "contains" -> MethodType.methodType(boolean.class, Slice.class);
                case "count" -> MethodType.methodType(long.class, Slice.class);
                case "extract" -> MethodType.methodType(Slice.class, Slice.class, int.class);
                case "position" -> MethodType.methodType(long.class, Slice.class, long.class, long.class);
                default -> throw new IllegalArgumentException("unknown operation: " + operation);
            };
            return MethodHandles.publicLookup().findVirtual(TrinoRegexp.class, operation, type).bindTo(pattern);
        }
        String accessor = "generated" + Character.toUpperCase(operation.charAt(0)) + operation.substring(1);
        Optional<?> handle = operation.equals("extract")
                ? (Optional<?>) TrinoRegexp.class.getMethod(accessor, int.class).invoke(pattern, group(pattern))
                : (Optional<?>) TrinoRegexp.class.getMethod(accessor).invoke(pattern);
        return (MethodHandle) handle.orElseThrow(() -> new IllegalStateException("Pattern runs " + operation + " without generated code"));
    }

    /**
     * Defines a hidden class whose {@code batch(Slice[], int)} adds up {@link #BATCH} results of
     * {@code operation}, starting at the given input index, through the handle held as a
     * class-data constant. The handle takes the input first; the class passes {@code group} for an
     * {@code int} parameter and 1 for each {@code long} parameter.
     */
    static MethodHandle batchCaller(MethodHandle operation, int group, String suffix)
            throws ReflectiveOperationException
    {
        MethodType type = operation.type();
        if (type.parameterCount() == 0 || type.parameterType(0) != Slice.class) {
            throw new IllegalArgumentException("operation does not take the input first: " + type);
        }
        ClassDesc owner = ClassDesc.of("io.airlift.regulator.ScanExpression" + suffix);
        ClassDesc slice = ClassDesc.of(Slice.class.getName());
        ClassDesc benchmark = ClassDesc.of(BenchmarkTrinoScanKernelDispatch.class.getName());
        DirectMethodHandleDesc bootstrap = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                CD_MethodHandles,
                "classDataAt",
                MethodTypeDesc.of(CD_Object, CD_MethodHandles_Lookup, CD_String, CD_Class, CD_int));
        DynamicConstantDesc<?> boundOperation = DynamicConstantDesc.ofNamed(bootstrap, "_", CD_MethodHandle, 0);
        MethodTypeDesc evaluateType = MethodTypeDesc.of(CD_int, slice);
        MethodTypeDesc operationType = type.describeConstable().orElseThrow();
        MethodTypeDesc batchType = MethodTypeDesc.of(CD_int, slice.arrayType(), CD_int);
        byte[] bytecode = ClassFile.of().build(owner, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withMethodBody("evaluate", evaluateType, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                code.loadConstant(boundOperation).aload(0);
                for (Class<?> parameter : type.parameterList().subList(1, type.parameterCount())) {
                    pushArgument(code, parameter, group);
                }
                code.invokevirtual(CD_MethodHandle, "invokeExact", operationType);
                Class<?> result = type.returnType();
                if (result == long.class) {
                    code.l2i();
                }
                else if (result == Slice.class) {
                    code.invokestatic(benchmark, "checksum", MethodTypeDesc.of(CD_int, slice));
                }
                else if (result != boolean.class) {
                    throw new IllegalArgumentException("unsupported operation result: " + type);
                }
                code.ireturn();
            });
            builder.withMethodBody("batch", batchType, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                var loop = code.newLabel();
                var end = code.newLabel();
                code.iconst_0().istore(2).iconst_0().istore(3).labelBinding(loop)
                        .iload(2).loadConstant(BATCH).if_icmpge(end)
                        .iload(3).aload(0).iload(1).iload(2).iadd().loadConstant(INPUT_MASK).iand().aaload()
                        .invokestatic(owner, "evaluate", evaluateType).iadd().istore(3)
                        .iinc(2, 1).goto_(loop).labelBinding(end).iload(3).ireturn();
            });
        });
        MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClassWithClassData(
                bytecode,
                List.of(operation),
                true,
                MethodHandles.Lookup.ClassOption.NESTMATE);
        return lookup.findStatic(lookup.lookupClass(), "batch", MethodType.methodType(int.class, Slice[].class, int.class));
    }

    private static void pushArgument(CodeBuilder code, Class<?> parameter, int group)
    {
        if (parameter == int.class) {
            code.loadConstant(group);
        }
        else if (parameter == long.class) {
            code.lconst_1();
        }
        else {
            throw new IllegalArgumentException("unsupported operation parameter: " + parameter);
        }
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkTrinoScanKernelDispatch.class, args);
        new Runner(options).run();
    }
}
