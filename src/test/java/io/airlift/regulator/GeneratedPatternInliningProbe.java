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

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.airlift.slice.Slices.utf8Slice;
import static java.lang.constant.ConstantDescs.CD_Boolean;
import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_Long;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_MethodHandles;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.INIT_NAME;

/// Runs [TestGeneratedPatternInlining]'s child JVM. For each language and operation it compiles
/// [#PATTERNS] patterns, each to its own generated class, runs all of them through one shared
/// call site, so every profile that generated patterns share sees many receivers, and then binds
/// each pattern as a class-data constant at a call site of its own: a hidden class named
/// `InliningCaller_<language>_<operation>_<index>`, which it calls [#CONSTANT_CALLS] times,
/// enough for C2 to compile it. It prints one `SITE` line per caller, naming the caller, the
/// language, the operation, the method the operation calls, the pattern's class, the pattern,
/// and the bytecode index of the replacement callback's call in the entry, `-1` for a site other
/// than the callback `replace`, and the parent reads the JVM's inlining output for that caller.
///
/// Each RE2, Java, Rust, and Trino operation that searches also gets one more site, of
/// [#HANDOFF_EXPRESSION], whose input hands its search off to the ordinary engine, so the parent
/// also requires the generated class's call of the engine's continuation.
public final class GeneratedPatternInliningProbe
{
    static final int PATTERNS = 32;
    // The handoff sites: find and count of each of RE2, Java, and Rust, and each of the eight
    // Trino operations. The regex languages' matches and LIKE matches make one anchored attempt,
    // so they never hand off.
    static final int HANDOFF_SITES = 3 * 2 + 8;
    // The sites of each of the PATTERNS patterns: find, count, and matches of each of RE2, Java,
    // and Rust, the eight Trino operations, and LIKE matches; then the handoff sites.
    static final int SITES = PATTERNS * (3 * 3 + 8 + 1) + HANDOFF_SITES;
    // Every x of the leading run starts an attempt that reads to the first z before it fails, so
    // the failed attempts overlap, their work passes the search's budget, and the search hands
    // off; the ordinary engine then finds both matches.
    static final String HANDOFF_EXPRESSION = "x([^z]+)zq";
    private static final String HANDOFF_WITNESS = "x".repeat(80) + "zrxbzqxczq";
    private static final int SHARED_CALLS = 2_000;
    private static final int CONSTANT_CALLS = 30_000;

    private static volatile int sink;

    private GeneratedPatternInliningProbe() {}

    /// The per-pattern caller: one call of the bound operation, its result boxed.
    public interface Caller
    {
        Object call(Slice input);
    }

    /// The replacement callback of the callback `replace` sites, which returns group one. The
    /// parent leaves unchecked only the entry's own call of the callback, at the bytecode index
    /// the `SITE` line gives, which C2 names as this class once it binds the callback through a
    /// type profile.
    static final class FirstGroup
            implements Function<List<Slice>, Slice>
    {
        @Override
        public Slice apply(List<Slice> groups)
        {
            return groups.getFirst();
        }
    }

    /// One operation of one generated pattern, which calls the public method `method`: the call
    /// site every pattern shares, the operation bound to the pattern, which takes only the input,
    /// inputs that match and that do not, and the bytecode index of the entry's call of the
    /// replacement callback, `-1` if the operation takes none.
    private record Site(String language, String operation, String method, Object pattern, String expression, Function<Slice, Object> shared, MethodHandle bound, List<Slice> inputs, int callbackBytecodeIndex)
    {
        private Site(String language, String operation, Object pattern, String expression, Function<Slice, Object> shared, MethodHandle bound, List<Slice> inputs)
        {
            this(language, operation, operation, pattern, expression, shared, bound, inputs, -1);
        }
    }

    /// How the probe compiles a pattern of an RE2-dialect language and calls each operation at
    /// the shared site.
    private record Language<T>(
            Function<String, T> compile,
            BiFunction<T, Slice, Object> find,
            BiFunction<T, Slice, Object> count,
            BiFunction<T, Slice, Object> matches) {}

    public static void main(String[] arguments)
            throws Throwable
    {
        List<List<Site>> groups = new ArrayList<>();
        groups.addAll(regexpSites("re2", Re2.class, new Language<>(
                expression -> Re2.compile(utf8Slice(expression), Re2.Options.defaults().setGenerateBytecode(true)),
                Re2::find,
                Re2::count,
                Re2::matches)));
        groups.addAll(regexpSites("java", JavaRegexp.class, new Language<>(
                expression -> JavaRegexp.compile(utf8Slice(expression), JavaRegexp.Options.defaults().setGenerateBytecode(true)),
                JavaRegexp::find,
                JavaRegexp::count,
                JavaRegexp::matches)));
        groups.addAll(regexpSites("rust", RustRegexp.class, new Language<>(
                expression -> RustRegexp.compile(utf8Slice(expression), RustRegexp.Options.defaults().setGenerateBytecode(true)),
                RustRegexp::find,
                RustRegexp::count,
                RustRegexp::matches)));
        groups.addAll(trinoSites());
        groups.add(likeSites());

        for (List<Site> group : groups) {
            // Every pattern of the group runs through the one call site of its shared function.
            for (int round = 0; round < SHARED_CALLS; round++) {
                for (Site site : group) {
                    consume(site.shared().apply(site.inputs().get(round & 1)));
                }
            }
        }
        for (List<Site> group : groups) {
            for (int index = 0; index < group.size(); index++) {
                Site site = group.get(index);
                String name = "InliningCaller_" + site.language() + "_" + site.operation() + "_" + index;
                Caller caller = constantCaller(name, site.bound());
                System.out.println(String.join(
                        "\t",
                        "SITE",
                        GeneratedPatternInliningProbe.class.getPackageName() + "." + name,
                        site.language(),
                        site.operation(),
                        site.method(),
                        site.pattern().getClass().getName(),
                        site.expression(),
                        String.valueOf(site.callbackBytecodeIndex())));
                System.out.flush();
                for (int call = 0; call < CONSTANT_CALLS; call++) {
                    consume(caller.call(site.inputs().get(call & 1)));
                }
            }
        }
        System.out.println("DONE");
    }

    /// The `find`, `count`, and `matches` sites of RE2, Java, or Rust patterns, half with an
    /// unanchored plan and half with a start-anchored one, each reaching the plan, and the
    /// handoff pattern's `find` and `count` sites.
    private static <T extends CompiledPattern> List<List<Site>> regexpSites(String language, Class<T> type, Language<T> operations)
            throws ReflectiveOperationException
    {
        List<Site> find = new ArrayList<>();
        List<Site> count = new ArrayList<>();
        List<Site> matches = new ArrayList<>();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle findHandle = lookup.findVirtual(type, "find", MethodType.methodType(boolean.class, Slice.class));
        MethodHandle countHandle = lookup.findVirtual(type, "count", MethodType.methodType(long.class, Slice.class));
        MethodHandle matchesHandle = lookup.findVirtual(type, "matches", MethodType.methodType(boolean.class, Slice.class));
        for (int index = 0; index <= PATTERNS; index++) {
            boolean handoff = index == PATTERNS;
            String expression = handoff ? HANDOFF_EXPRESSION : regexpExpression(index);
            T pattern = operations.compile().apply(expression);
            check(pattern.usesGeneratedBytecode(), language + " " + expression + ": no generated class");
            List<Slice> inputs = handoff ? handoffInputs(language, pattern.caseSensitiveScanPlan()) : regexpInputs(index);
            find.add(new Site(language, "find", pattern, expression, input -> operations.find().apply(pattern, input), findHandle.bindTo(pattern), inputs));
            count.add(new Site(language, "count", pattern, expression, input -> operations.count().apply(pattern, input), countHandle.bindTo(pattern), inputs));
            // An anchored match makes one attempt and never hands off.
            if (!handoff) {
                matches.add(new Site(language, "matches", pattern, expression, input -> operations.matches().apply(pattern, input), matchesHandle.bindTo(pattern), inputs));
            }
        }
        return List.of(find, count, matches);
    }

    /// The sites of every public Trino operation that the generated class declares: `contains`,
    /// `count`, `position` from the first code point, `split`, group-one `extract` and
    /// `extractAll`, and both `replace` overloads, one with a template that references the group
    /// and one with a callback. Each operation also has a site of the handoff pattern.
    private static List<List<Site>> trinoSites()
            throws ReflectiveOperationException
    {
        List<Site> contains = new ArrayList<>();
        List<Site> count = new ArrayList<>();
        List<Site> position = new ArrayList<>();
        List<Site> split = new ArrayList<>();
        List<Site> extract = new ArrayList<>();
        List<Site> extractAll = new ArrayList<>();
        List<Site> replace = new ArrayList<>();
        List<Site> replaceWithCallback = new ArrayList<>();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle containsHandle = lookup.findVirtual(TrinoRegexp.class, "contains", MethodType.methodType(boolean.class, Slice.class));
        MethodHandle countHandle = lookup.findVirtual(TrinoRegexp.class, "count", MethodType.methodType(long.class, Slice.class));
        MethodHandle positionHandle = lookup.findVirtual(TrinoRegexp.class, "position", MethodType.methodType(long.class, Slice.class, long.class, long.class));
        MethodHandle splitHandle = lookup.findVirtual(TrinoRegexp.class, "split", MethodType.methodType(List.class, Slice.class));
        MethodHandle extractHandle = lookup.findVirtual(TrinoRegexp.class, "extract", MethodType.methodType(Slice.class, Slice.class, int.class));
        MethodHandle extractAllHandle = lookup.findVirtual(TrinoRegexp.class, "extractAll", MethodType.methodType(List.class, Slice.class, int.class));
        MethodHandle replaceHandle = lookup.findVirtual(TrinoRegexp.class, "replace", MethodType.methodType(Slice.class, Slice.class, Slice.class));
        MethodHandle replaceWithCallbackHandle = lookup.findVirtual(TrinoRegexp.class, "replace", MethodType.methodType(Slice.class, Slice.class, Function.class));
        Slice template = utf8Slice("[$1]");
        Function<List<Slice>, Slice> callback = new FirstGroup();
        for (int index = 0; index <= PATTERNS; index++) {
            boolean handoff = index == PATTERNS;
            String expression = handoff ? HANDOFF_EXPRESSION : regexpExpression(index);
            TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression), TrinoRegexp.Options.defaults().setGenerateBytecode(true));
            check(pattern.usesGeneratedBytecode(), "trino " + expression + ": no generated class");
            List<Slice> inputs = handoff ? handoffInputs("trino", pattern.re2().caseSensitiveScanPlan()) : regexpInputs(index);
            contains.add(new Site("trino", "contains", pattern, expression, pattern::contains, containsHandle.bindTo(pattern), inputs));
            count.add(new Site("trino", "count", pattern, expression, pattern::count, countHandle.bindTo(pattern), inputs));
            position.add(new Site("trino", "position", pattern, expression, input -> pattern.position(input, 1, 1), MethodHandles.insertArguments(positionHandle.bindTo(pattern), 1, 1L, 1L), inputs));
            split.add(new Site("trino", "split", pattern, expression, pattern::split, splitHandle.bindTo(pattern), inputs));
            extract.add(new Site("trino", "extract", pattern, expression, input -> pattern.extract(input, 1), MethodHandles.insertArguments(extractHandle.bindTo(pattern), 1, 1), inputs));
            extractAll.add(new Site("trino", "extractAll", pattern, expression, input -> pattern.extractAll(input, 1), MethodHandles.insertArguments(extractAllHandle.bindTo(pattern), 1, 1), inputs));
            replace.add(new Site("trino", "replace", pattern, expression, input -> pattern.replace(input, template), MethodHandles.insertArguments(replaceHandle.bindTo(pattern), 1, template), inputs));
            replaceWithCallback.add(new Site("trino", "replaceWithCallback", "replace", pattern, expression, input -> pattern.replace(input, callback), MethodHandles.insertArguments(replaceWithCallbackHandle.bindTo(pattern), 1, callback), inputs, callbackBytecodeIndex(expression, pattern)));
        }
        return List.of(contains, count, position, split, extract, extractAll, replace, replaceWithCallback);
    }

    /// The bytecode index of the replacement callback's call in the callback `replace` of the
    /// generated class of `pattern`: its one `Function.apply` call, which the probe proves is the
    /// callback parameter's own call. The call must directly follow, with no branch target among
    /// them, a load of the parameter's slot, a load of the group list, and the
    /// `Collections.unmodifiableList` call that wraps the list, and no instruction of the method may
    /// store to the parameter's slot, so the call's receiver is the caller's callback. Generation is
    /// deterministic, so the class generated again for diagnostics has the same code as the
    /// pattern's own.
    private static int callbackBytecodeIndex(String expression, TrinoRegexp pattern)
    {
        byte[] classfile = ScanKernelGenerator.trinoClassfileForDiagnostics(ScanKernelGenerator.TrinoRoutes.of(pattern.re2()));
        ClassDesc slice = ClassDesc.of(Slice.class.getName());
        ClassDesc function = ClassDesc.of(Function.class.getName());
        ClassDesc list = ClassDesc.of(List.class.getName());
        MethodTypeDesc descriptor = MethodTypeDesc.of(slice, slice, function);
        MethodModel replace = ClassFile.of().parse(classfile).methods().stream()
                .filter(method -> method.methodName().equalsString("replace") && method.methodTypeSymbol().equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("trino " + expression + ": the generated class has no callback replace"));
        // Slot 0 holds this, and the parameters before the callback take the slots after it.
        int callbackSlot = 1;
        for (ClassDesc parameter : descriptor.parameterList().subList(0, descriptor.parameterList().indexOf(function))) {
            callbackSlot += TypeKind.from(parameter).slotSize();
        }
        List<Integer> calls = new ArrayList<>();
        List<Integer> callbackStores = new ArrayList<>();
        // The instructions since the last branch target, and the last three before the call.
        List<Instruction> straight = new ArrayList<>();
        List<Instruction> beforeCall = List.of();
        int bytecodeIndex = 0;
        for (CodeElement element : replace.code().orElseThrow()) {
            if (element instanceof LabelTarget) {
                straight.clear();
            }
            else if (element instanceof Instruction instruction) {
                if (instruction instanceof StoreInstruction store && store.slot() == callbackSlot) {
                    callbackStores.add(bytecodeIndex);
                }
                if (instruction instanceof InvokeInstruction invoke &&
                        invoke.opcode() == Opcode.INVOKEINTERFACE &&
                        invoke.owner().asSymbol().equals(function) &&
                        invoke.name().equalsString("apply")) {
                    calls.add(bytecodeIndex);
                    beforeCall = List.copyOf(straight.subList(Math.max(0, straight.size() - 3), straight.size()));
                }
                straight.add(instruction);
                bytecodeIndex += instruction.sizeInBytes();
            }
        }
        check(calls.size() == 1, "trino " + expression + ": the callback replace calls Function.apply at bytecode indexes " + calls + ", not once");
        check(callbackStores.isEmpty(), "trino " + expression + ": the callback replace stores to the replacement parameter's slot " + callbackSlot + " at bytecode indexes " + callbackStores);
        boolean callbackCall = beforeCall.size() == 3 &&
                beforeCall.get(0) instanceof LoadInstruction receiver &&
                receiver.typeKind() == TypeKind.REFERENCE &&
                receiver.slot() == callbackSlot &&
                beforeCall.get(1) instanceof LoadInstruction argument &&
                argument.typeKind() == TypeKind.REFERENCE &&
                beforeCall.get(2) instanceof InvokeInstruction wrap &&
                wrap.opcode() == Opcode.INVOKESTATIC &&
                wrap.owner().asSymbol().equals(ClassDesc.of(Collections.class.getName())) &&
                wrap.name().equalsString("unmodifiableList") &&
                wrap.typeSymbol().equals(MethodTypeDesc.of(list, list));
        check(callbackCall, "trino " + expression + ": the callback replace's Function.apply at bytecode index " + calls.getFirst() +
                " directly follows " + beforeCall + " since the last branch target, not a load of the replacement parameter's slot " + callbackSlot +
                ", a load of the group list, and Collections.unmodifiableList");
        return calls.getFirst();
    }

    /// The `matches` sites of LIKE patterns of every plan: empty, equals, starts with, ends with,
    /// contains, ordered literals, literal gaps, and the wildcard matcher.
    private static List<Site> likeSites()
            throws ReflectiveOperationException
    {
        List<Site> matches = new ArrayList<>();
        MethodHandle matchesHandle = MethodHandles.publicLookup().findVirtual(TrinoLikePattern.class, "matches", MethodType.methodType(boolean.class, Slice.class));
        for (int index = 0; index < PATTERNS; index++) {
            String literal = "needle" + index;
            String expression = switch (index % 8) {
                case 0 -> "";
                case 1 -> literal;
                case 2 -> literal + "%";
                case 3 -> "%" + literal;
                case 4 -> "%" + literal + "%";
                case 5 -> "%" + literal + "%tail%";
                case 6 -> "%" + literal + "_x%";
                default -> "%" + literal + "%t_il%";
            };
            TrinoLikePattern pattern = TrinoLikePattern.compile(utf8Slice(expression), TrinoLikePattern.Options.defaults().setGenerateBytecode(true));
            check(pattern.usesGeneratedBytecode(), "like " + expression + ": no generated class");
            String witness = expression.replace("_", "y").replace("%", "ab");
            check(pattern.matches(utf8Slice(witness)), "like " + expression + ": witness does not match");
            List<Slice> inputs = List.of(utf8Slice(witness), utf8Slice("ab-no-match-here-" + index));
            matches.add(new Site("like", "matches", pattern, expression, pattern::matches, matchesHandle.bindTo(pattern), inputs));
        }
        return matches;
    }

    private static String regexpExpression(int index)
    {
        return (index % 2 == 0 ? "" : "^") + "k" + index + "x([^/]+)/";
    }

    private static List<Slice> regexpInputs(int index)
    {
        return List.of(utf8Slice("k" + index + "xvalue/"), utf8Slice("k" + index + "y-no/match-here"));
    }

    /// The inputs of the handoff pattern: the witness, after checking that the search of `plan`,
    /// which the generated search mirrors, hands it off to the ordinary engine, and an input
    /// without a match.
    private static List<Slice> handoffInputs(String language, ScanPlan plan)
    {
        Slice witness = utf8Slice(HANDOFF_WITNESS);
        int result = plan.search(witness, 0, witness.length(), 0, false, false, null);
        check(result < 0 && result != ScanPlan.NO_MATCH, language + " " + HANDOFF_EXPRESSION + ": the witness does not hand off");
        return List.of(witness, utf8Slice("xb/no-match-here"));
    }

    /// Returns an instance of a hidden class named `name` whose `call` invokes `operation`,
    /// a handle of one [Slice] argument, held as a class-data constant, boxing a primitive
    /// result.
    private static Caller constantCaller(String name, MethodHandle operation)
            throws ReflectiveOperationException
    {
        Class<?> returnType = operation.type().returnType();
        ClassDesc slice = ClassDesc.of(Slice.class.getName());
        MethodTypeDesc descriptor = operation.type().describeConstable().orElseThrow();
        DirectMethodHandleDesc bootstrap = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                CD_MethodHandles,
                "classDataAt",
                MethodTypeDesc.of(CD_Object, CD_MethodHandles_Lookup, CD_String, CD_Class, CD_int));
        DynamicConstantDesc<?> boundOperation = DynamicConstantDesc.ofNamed(bootstrap, "_", CD_MethodHandle, 0);
        byte[] bytecode = ClassFile.of().build(ClassDesc.of(GeneratedPatternInliningProbe.class.getPackageName() + "." + name), builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withInterfaceSymbols(ClassDesc.of(Caller.class.getName()));
            builder.withMethodBody(INIT_NAME, MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC, code ->
                    code.aload(0).invokespecial(CD_Object, INIT_NAME, MethodTypeDesc.of(CD_void)).return_());
            builder.withMethodBody("call", MethodTypeDesc.of(CD_Object, slice), ClassFile.ACC_PUBLIC, code -> {
                code.loadConstant(boundOperation).aload(1).invokevirtual(CD_MethodHandle, "invokeExact", descriptor);
                if (returnType == boolean.class) {
                    code.invokestatic(CD_Boolean, "valueOf", MethodTypeDesc.of(CD_Boolean, CD_boolean));
                }
                else if (returnType == long.class) {
                    code.invokestatic(CD_Long, "valueOf", MethodTypeDesc.of(CD_Long, CD_long));
                }
                code.areturn();
            });
        });
        MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClassWithClassData(bytecode, List.of(operation), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        return (Caller) lookup.lookupClass().getConstructor().newInstance();
    }

    private static void consume(Object value)
    {
        sink += value == null ? 1 : value.hashCode();
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
