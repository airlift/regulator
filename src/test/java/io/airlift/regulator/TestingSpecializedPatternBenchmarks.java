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

import io.airlift.regulator.TestingEverydayTrinoRegexpBenchmarkInputs.Source;
import io.airlift.regulator.TestingSpecializedPatternBenchmarkInputs.Operation;
import io.airlift.regulator.TestingSpecializedPatternBenchmarkInputs.Workload;
import io.airlift.slice.Slice;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodHandles;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.INIT_NAME;

/// What every language's specialized-pattern benchmark shares: the input array of a workload, the
/// null engine, the variants tests and verifiers call, the check of every pass, the const-bound
/// caller of the compiled variant, and, for a language without a replacement call, the workloads
/// each capture operation times. Every benchmark method is one pass over the workload's [#INPUTS]
/// inputs in order, which JMH counts as one operation per input, and consumes each input's
/// result, with the state it reads loaded into locals before the loop. The loop calls a static
/// operation on each input, and the method's variant applies the same operation to one input.
public final class TestingSpecializedPatternBenchmarks
{
    /// The inputs of every workload, and so the operations of one benchmark invocation.
    public static final int INPUTS = 8;

    private TestingSpecializedPatternBenchmarks() {}

    /// The workloads whose [capture operation][Workload#captureOperation()] is `operation`, which a
    /// language without a replacement call times in its `operation` benchmarks.
    static List<String> captureWorkloadIds(Operation operation)
    {
        return TestingSpecializedPatternBenchmarkInputs.workloadIds().stream()
                .filter(workloadId -> TestingSpecializedPatternBenchmarkInputs.create(workloadId).captureOperation() == operation)
                .toList();
    }

    static void requireCaptureOperation(Workload workload, Operation expected)
    {
        if (workload.captureOperation() != expected) {
            throw new IllegalStateException("%s captures with %s, not %s".formatted(workload.workloadId(), workload.captureOperation().benchmarkName(), expected.benchmarkName()));
        }
    }

    /// The input array every pass reads: the workload's sources in its order, which must be
    /// [#INPUTS], the operations of one invocation.
    public static Slice[] inputs(Workload workload)
    {
        if (workload.sources().size() != INPUTS) {
            throw new IllegalStateException("%s has %s inputs, not %s".formatted(workload.workloadId(), workload.sources().size(), INPUTS));
        }
        return workload.sources().stream().map(Source::value).toArray(Slice[]::new);
    }

    /// The null engine's operation: the input's byte length, with no matching, so its time per
    /// input is the harness's own cost.
    public static long nullOperation(Slice input)
    {
        return input.length();
    }

    /// A workload and the input array its passes read.
    abstract static class WorkloadInputs
    {
        Workload definition;
        Slice[] inputs;

        void readInputs(Workload definition)
        {
            this.definition = definition;
            inputs = inputs(definition);
        }
    }

    /// The inputs of any workload, for the null engine.
    @State(Scope.Thread)
    public static class NullData
            extends WorkloadInputs
    {
        @Param({"clickbenchHost", "urlHostPort", "queryParameterNames", "keyValue", "quotedField"})
        String workload;

        @Setup
        public void setup()
        {
            readInputs(TestingSpecializedPatternBenchmarkInputs.create(workload));
        }
    }

    /// One variant of a workload: the state its benchmark method takes, and the static operation
    /// that method calls on each input, applied with the state's values to the input at an index.
    public record Variant(String name, Object state, IntFunction<Object> operation)
    {
        /// Applies the operation to input `index`, as the benchmark method does in its pass.
        public Object invoke(int index)
        {
            return operation.apply(index);
        }
    }

    /// Sets up every variant of one workload, as a language's `variantsForTesting` does.
    @FunctionalInterface
    interface Variants
    {
        List<Variant> of(String workloadId)
                throws ReflectiveOperationException;
    }

    /// Checks every pass of `benchmark` with [TestingBenchmarkPasses#verifyPasses], over the
    /// states of every variant of every workload and the null engine's state of every workload.
    static void verifyPasses(Object benchmark, Variants variants)
            throws Exception
    {
        List<Object> states = new ArrayList<>();
        for (String workloadId : TestingSpecializedPatternBenchmarkInputs.workloadIds()) {
            variants.of(workloadId).forEach(variant -> states.add(variant.state()));
            NullData nullData = new NullData();
            nullData.workload = workloadId;
            nullData.setup();
            states.add(nullData);
        }
        TestingBenchmarkPasses.verifyPasses(benchmark, TestingBenchmarkPasses.byStateClass(states), state -> switch (state) {
            case WorkloadInputs workload -> workload.inputs;
            case BenchmarkSpecializedJavaRegexp.JdkData jdk -> jdk.inputs;
            default -> throw new IllegalArgumentException("state without inputs: " + state.getClass().getName());
        });
    }

    /// Returns an instance of a per-pattern hidden class implementing `type`, whose one
    /// method invokes `operation` held as a class-data constant, as
    /// `BenchmarkTrinoLikeTpch` binds a constant pattern. `operation` must have that
    /// method's type, which takes and returns only references.
    static <T> T constantCaller(Class<T> type, MethodHandle operation)
            throws ReflectiveOperationException
    {
        Method[] methods = type.getDeclaredMethods();
        if (methods.length != 1) {
            throw new IllegalArgumentException("caller interface does not have one method: " + type.getName());
        }
        Method method = methods[0];
        MethodType methodType = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
        if (methodType.hasPrimitives()) {
            throw new IllegalArgumentException("caller interface method has a primitive or void parameter or return: " + type.getName());
        }
        if (!operation.type().equals(methodType)) {
            throw new IllegalArgumentException("operation " + operation.type() + " does not implement " + type.getName());
        }
        ClassDesc owner = ClassDesc.of(TestingSpecializedPatternBenchmarks.class.getPackageName() + ".ConstantSpecializedCaller");
        DirectMethodHandleDesc bootstrap = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                CD_MethodHandles,
                "classDataAt",
                MethodTypeDesc.of(CD_Object, CD_MethodHandles_Lookup, CD_String, CD_Class, CD_int));
        DynamicConstantDesc<?> boundOperation = DynamicConstantDesc.ofNamed(bootstrap, "_", CD_MethodHandle, 0);
        MethodTypeDesc descriptor = methodType.describeConstable().orElseThrow();
        byte[] bytecode = ClassFile.of().build(owner, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withInterfaceSymbols(type.describeConstable().orElseThrow());
            builder.withMethodBody(INIT_NAME, MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC, code ->
                    code.aload(0).invokespecial(CD_Object, INIT_NAME, MethodTypeDesc.of(CD_void)).return_());
            builder.withMethodBody(method.getName(), descriptor, ClassFile.ACC_PUBLIC, code -> {
                code.loadConstant(boundOperation);
                for (int parameter = 0; parameter < descriptor.parameterCount(); parameter++) {
                    code.aload(parameter + 1);
                }
                code.invokevirtual(CD_MethodHandle, "invokeExact", descriptor).areturn();
            });
        });
        MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClassWithClassData(bytecode, List.of(operation), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        return type.cast(lookup.lookupClass().getConstructor().newInstance());
    }
}
