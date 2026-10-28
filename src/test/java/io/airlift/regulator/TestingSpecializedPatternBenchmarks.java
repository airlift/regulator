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
import java.util.List;
import java.util.function.Supplier;

import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodHandles;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.INIT_NAME;

/// What every language's specialized-pattern benchmark shares: the rotation of a workload's
/// sources, the variants tests and verifiers call, the const-bound caller of the compiled variant,
/// and, for a language without a replacement call, the workloads each capture operation times.
public final class TestingSpecializedPatternBenchmarks
{
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

    abstract static class Rotation
    {
        Workload definition;
        private List<Source> sources;
        private int sourceIndex;

        void rotate(Workload definition)
        {
            this.definition = definition;
            sources = definition.sources();
            sourceIndex = 0;
        }

        public void setSourceIndex(int sourceIndex)
        {
            this.sourceIndex = sourceIndex;
        }

        Slice nextSource()
        {
            return sources.get(nextSourceIndex()).value();
        }

        /// Returns the index of the next source and advances the rotation past it.
        int nextSourceIndex()
        {
            int index = sourceIndex;
            sourceIndex = (sourceIndex + 1) & (sources.size() - 1);
            return index;
        }
    }

    /// One variant of a workload: its benchmark method and the state whose rotation that method
    /// advances.
    public static final class Variant
    {
        private final String name;
        private final Rotation state;
        private final Supplier<Object> benchmark;

        Variant(String name, Rotation state, Supplier<Object> benchmark)
        {
            this.name = name;
            this.state = state;
            this.benchmark = benchmark;
        }

        public String name()
        {
            return name;
        }

        /// The state whose rotation the benchmark method advances, which holds the compile it uses.
        Rotation state()
        {
            return state;
        }

        public void setSourceIndex(int sourceIndex)
        {
            state.setSourceIndex(sourceIndex);
        }

        /// Calls the benchmark method once, on the next source of the rotation.
        public Object invoke()
        {
            return benchmark.get();
        }

        /// Calls the benchmark method on the source at `sourceIndex`.
        public Object invoke(int sourceIndex)
        {
            setSourceIndex(sourceIndex);
            return invoke();
        }
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
