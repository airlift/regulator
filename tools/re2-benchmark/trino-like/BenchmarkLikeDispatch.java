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
import io.airlift.slice.Slices;
import io.trino.likematcher.LikeMatcher;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodHandles;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_int;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class BenchmarkLikeDispatch
{
    public static final int BATCH = 256;
    private static volatile int trainingSink;

    @State(Scope.Thread)
    public static class Data
    {
        @Param({"REGULATOR", "TRINO", "TRINO_DFA"})
        public String engine;

        @Param({"ISOLATED", "MIXED_FIRST", "INTERLEAVED", "DYNAMIC"})
        public String profile;

        @Param({"EXACT6", "PREFIX6", "SUFFIX6", "EXACT16", "EXACT17", "EXACT128", "EXACT512", "PREFIX512", "SUFFIX512", "MIXED_LATE"})
        public String target;

        @Param({"MATCH", "MIXED"})
        public String outcomes;

        private Workload selected;
        private List<Workload> training;
        private MethodHandle caller;
        private List<MethodHandle> otherCallers;
        private int cursor;

        @Setup(Level.Trial)
        public void setup() throws Throwable
        {
            selected = workload(target, outcomes);
            training = profile.equals("ISOLATED") ? List.of(selected) : trainingWorkloads();
            verifyWorkloads(training);
            verifyWorkloads(List.of(selected));
            // Populate shared method profiles before the expression-specific class exists.
            train(20_000);
            caller = generatedCaller(engine, selected, "Target");
            otherCallers = new ArrayList<>();
            if (profile.equals("INTERLEAVED")) {
                for (int index = 0; index < training.size(); index++) {
                    otherCallers.add(generatedCaller(engine, training.get(index), "Other" + index));
                }
            }
            int actual = (int) caller.invokeExact(selected.inputs(), 0);
            if (actual != expectedBatch(selected, 0)) {
                throw new IllegalStateException("Generated caller checksum mismatch");
            }
        }

        @Setup(Level.Iteration)
        public void refresh() throws Throwable
        {
            if (!profile.equals("ISOLATED")) {
                train(2_000);
            }
            for (int round = 0; round < 100; round++) {
                for (int index = 0; index < otherCallers.size(); index++) {
                    trainingSink = (int) otherCallers.get(index).invokeExact(training.get(index).inputs(), round);
                }
            }
        }

        private void train(int rounds)
        {
            int checksum = 0;
            for (int round = 0; round < rounds; round++) {
                for (Workload workload : training) {
                    checksum += execute(engine, workload, round & 15) ? 1 : 0;
                }
            }
            trainingSink = checksum;
        }
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public int generated(Data data) throws Throwable
    {
        return (int) data.caller.invokeExact(data.selected.inputs(), data.cursor++);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public int dynamic(Data data)
    {
        int checksum = 0;
        for (int index = 0; index < BATCH; index++) {
            Workload workload = data.training.get((data.cursor + index) % data.training.size());
            checksum += execute(data.engine, workload, ((data.cursor + index) / data.training.size()) & 15) ? 1 : 0;
        }
        data.cursor = (data.cursor + 1) & 0xffff;
        return checksum;
    }

    public static boolean regulator(Slice input, TrinoLikePattern pattern)
    {
        return pattern.matches(input);
    }

    public static boolean trino(Slice input, LikeMatcher pattern)
    {
        return pattern.match(input.byteArray(), input.byteArrayOffset(), input.length());
    }

    private static boolean execute(String engine, Workload workload, int input)
    {
        return switch (engine) {
            case "REGULATOR" -> regulator(workload.inputs()[input], workload.regulator());
            case "TRINO" -> trino(workload.inputs()[input], workload.trino());
            case "TRINO_DFA" -> trino(workload.inputs()[input], workload.trinoDfa());
            default -> throw new IllegalArgumentException(engine);
        };
    }

    private record Workload(String name, String pattern, Slice[] inputs, boolean[] expected,
            TrinoLikePattern regulator, LikeMatcher trino, LikeMatcher trinoDfa) {}

    private static Workload workload(String name, String outcomes)
    {
        String pattern;
        String input;
        if (name.startsWith("EXACT") || name.startsWith("PREFIX") || name.startsWith("SUFFIX")) {
            String shape = name.replaceAll("[0-9]", "");
            int length = Integer.parseInt(name.substring(shape.length()));
            String literal = length == 6 ? "needle" : "abcdefghijklmnop".repeat(32).substring(0, length);
            pattern = (shape.equals("SUFFIX") ? "%" : "") + literal + (shape.equals("PREFIX") ? "%" : "");
            input = switch (shape) {
                case "EXACT" -> literal;
                case "PREFIX" -> literal + "x".repeat(32768 - length);
                case "SUFFIX" -> "x".repeat(32768 - length) + literal;
                default -> throw new IllegalArgumentException(shape);
            };
        }
        else {
            String[] pair = switch (name) {
                case "EMPTY" -> new String[] {"", ""};
                case "ANY" -> new String[] {"%", "abc"};
                case "ANY_ASCII" -> new String[] {"_", "x"};
                case "ANY_MULTIBYTE" -> new String[] {"_", "💰"};
                case "CONTAINS" -> new String[] {"%needle%", "x".repeat(1024) + "needle"};
                case "ORDERED" -> new String[] {"%alpha%omega%", "x".repeat(1024) + "alpha middle omega"};
                case "MIXED_LATE" -> new String[] {"%alpha_omega%", "x".repeat(32700) + "alpha💰omega" + "x".repeat(54)};
                case "GAPS" -> new String[] {"%alpha_omega%", "prefix alpha💰omega suffix"};
                case "WILDCARD" -> new String[] {"_%a%_", "zaq"};
                case "UNICODE" -> new String[] {"π😀%", "π😀text"};
                default -> throw new IllegalArgumentException(name);
            };
            pattern = pair[0];
            input = pair[1];
        }
        Slice[] inputs = new Slice[16];
        boolean[] expected = new boolean[16];
        TrinoLikePattern regulator = TrinoLikePattern.compile(Slices.utf8Slice(pattern));
        LikeMatcher trino = LikeMatcher.compile(pattern, Optional.empty(), false);
        LikeMatcher trinoDfa = LikeMatcher.compile(pattern, Optional.empty(), true);
        for (int index = 0; index < inputs.length; index++) {
            byte[] bytes = Slices.utf8Slice(input).getBytes();
            if (outcomes.equals("MIXED") && index % 4 != 0) {
                if (name.startsWith("EXACT") || name.startsWith("PREFIX") || name.startsWith("SUFFIX")) {
                    int literalLength = Integer.parseInt(name.replaceAll("[^0-9]", ""));
                    int start = name.startsWith("SUFFIX") ? bytes.length - literalLength : 0;
                    int mismatch = switch (index % 4) {
                        case 1 -> 0;
                        case 2 -> literalLength / 2;
                        default -> literalLength - 1;
                    };
                    bytes[start + mismatch] = '!';
                }
                else {
                    Arrays.fill(bytes, (byte) 'x');
                }
            }
            // Offset-backed inputs and changing outcomes remain runtime data.
            byte[] backing = new byte[bytes.length + 3];
            System.arraycopy(bytes, 0, backing, 3, bytes.length);
            inputs[index] = Slices.wrappedBuffer(backing, 3, bytes.length);
            expected[index] = trino.match(backing, 3, bytes.length);
        }
        return new Workload(name, pattern, inputs, expected, regulator, trino, trinoDfa);
    }

    private static List<Workload> trainingWorkloads()
    {
        List<Workload> workloads = new ArrayList<>();
        for (String name : List.of("EMPTY", "ANY", "EXACT1", "EXACT3", "EXACT6", "PREFIX6", "SUFFIX6", "EXACT16", "PREFIX16", "SUFFIX16",
                "EXACT17", "PREFIX17", "SUFFIX17", "CONTAINS", "ORDERED", "GAPS", "WILDCARD", "UNICODE", "ANY_ASCII", "ANY_MULTIBYTE")) {
            Workload workload = workload(name, "MIXED");
            // Shared training must exercise length rejection as well as the
            // selected comparison. Target timing inputs remain unchanged.
            workload.inputs()[14] = Slices.EMPTY_SLICE;
            workload.inputs()[15] = Slices.utf8Slice("arbitrary text exceeding a short literal");
            for (int index : new int[] {14, 15}) {
                Slice input = workload.inputs()[index];
                workload.expected()[index] = trino(input, workload.trino());
            }
            workloads.add(workload);
        }
        return workloads;
    }

    private static void verifyWorkloads(List<Workload> workloads)
    {
        for (Workload workload : workloads) {
            for (int input = 0; input < workload.inputs().length; input++) {
                for (String engine : List.of("REGULATOR", "TRINO", "TRINO_DFA")) {
                    if (execute(engine, workload, input) != workload.expected()[input]) {
                        throw new IllegalStateException("Outcome mismatch: " + workload.name() + "/" + input + "/" + engine);
                    }
                }
            }
        }
    }

    private static int expectedBatch(Workload workload, int start)
    {
        int count = 0;
        for (int index = 0; index < BATCH; index++) {
            count += workload.expected()[(start + index) & 15] ? 1 : 0;
        }
        return count;
    }

    private static MethodHandle generatedCaller(String engine, Workload workload, String suffix) throws Throwable
    {
        Class<?> patternType = engine.equals("REGULATOR") ? TrinoLikePattern.class : LikeMatcher.class;
        Object pattern = switch (engine) {
            case "REGULATOR" -> workload.regulator();
            case "TRINO" -> workload.trino();
            case "TRINO_DFA" -> workload.trinoDfa();
            default -> throw new IllegalArgumentException(engine);
        };
        MethodHandle matcher = MethodHandles.lookup().findStatic(BenchmarkLikeDispatch.class,
                engine.equals("REGULATOR") ? "regulator" : "trino",
                MethodType.methodType(boolean.class, Slice.class, patternType));
        ClassDesc owner = ClassDesc.of("io.airlift.regulator.LikeExpression" + suffix);
        ClassDesc slice = ClassDesc.of(Slice.class.getName());
        ClassDesc patternDesc = ClassDesc.of(patternType.getName());
        DirectMethodHandleDesc bootstrap = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
                CD_MethodHandles, "classDataAt", MethodTypeDesc.of(CD_Object, CD_MethodHandles_Lookup, CD_String, CD_Class, CD_int));
        DynamicConstantDesc<?> boundPattern = DynamicConstantDesc.ofNamed(bootstrap, "_", patternDesc, 0);
        DynamicConstantDesc<?> boundMatcher = DynamicConstantDesc.ofNamed(bootstrap, "_", CD_MethodHandle, 1);
        MethodTypeDesc evaluateType = MethodTypeDesc.of(CD_boolean, slice);
        MethodTypeDesc batchType = MethodTypeDesc.of(CD_int, slice.arrayType(), CD_int);
        byte[] bytecode = ClassFile.of().build(owner, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withMethodBody("evaluate", evaluateType, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code ->
                    code.loadConstant(boundMatcher).aload(0).loadConstant(boundPattern)
                            .invokevirtual(CD_MethodHandle, "invokeExact", MethodTypeDesc.of(CD_boolean, slice, patternDesc)).ireturn());
            builder.withMethodBody("batch", batchType, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                var loop = code.newLabel();
                var end = code.newLabel();
                code.iconst_0().istore(2).iconst_0().istore(3).labelBinding(loop)
                        .iload(2).loadConstant(BATCH).if_icmpge(end)
                        .iload(3).aload(0).iload(1).iload(2).iadd().loadConstant(15).iand().aaload()
                        .invokestatic(owner, "evaluate", evaluateType).iadd().istore(3)
                        .iinc(2, 1).goto_(loop).labelBinding(end).iload(3).ireturn();
            });
        });
        MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClassWithClassData(bytecode,
                List.of(pattern, matcher), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        return lookup.findStatic(lookup.lookupClass(), "batch", MethodType.methodType(int.class, Slice[].class, int.class));
    }

    public static void main(String[] args) throws Throwable
    {
        List<Workload> workloads = trainingWorkloads();
        EnumSet<TrinoLikePattern.Plan> plans = EnumSet.noneOf(TrinoLikePattern.Plan.class);
        for (Workload workload : workloads) {
            plans.add(workload.regulator().planForDiagnostics());
            System.out.println(workload.name() + "\t" + workload.regulator().planForDiagnostics());
        }
        if (!plans.equals(EnumSet.allOf(TrinoLikePattern.Plan.class))) {
            throw new IllegalStateException("Missing training plans: " + plans);
        }
        verifyWorkloads(workloads);
        Data dynamicData = new Data();
        dynamicData.training = workloads;
        for (String engine : List.of("REGULATOR", "TRINO", "TRINO_DFA")) {
            dynamicData.engine = engine;
            for (int start : new int[] {0, 1, 7, 31}) {
                dynamicData.cursor = start;
                int expected = 0;
                for (int index = 0; index < BATCH; index++) {
                    Workload workload = workloads.get((start + index) % workloads.size());
                    expected += workload.expected()[((start + index) / workloads.size()) & 15] ? 1 : 0;
                }
                if (new BenchmarkLikeDispatch().dynamic(dynamicData) != expected) {
                    throw new IllegalStateException("Dynamic input schedule mismatch");
                }
            }
        }
        for (String target : List.of("EXACT6", "PREFIX6", "SUFFIX6", "EXACT16", "EXACT17", "EXACT128", "EXACT512", "PREFIX512", "SUFFIX512", "MIXED_LATE")) {
            for (String outcomes : List.of("MATCH", "MIXED")) {
                Workload workload = workload(target, outcomes);
                verifyWorkloads(List.of(workload));
                for (String engine : List.of("REGULATOR", "TRINO", "TRINO_DFA")) {
                    MethodHandle caller = generatedCaller(engine, workload, "Verify");
                    for (int start : new int[] {0, 1, 7}) {
                        int actual = (int) caller.invokeExact(workload.inputs(), start);
                        if (actual != expectedBatch(workload, start)) {
                            throw new IllegalStateException("Generated result mismatch: " + target + "/" + engine);
                        }
                    }
                }
            }
        }
        System.out.println("Verified all plans, three engines, generated callers and runtime inputs");
    }
}
