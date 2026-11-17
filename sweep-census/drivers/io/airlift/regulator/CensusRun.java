package io.airlift.regulator;

import censusprobe.Probe;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Census driver: runs one JMH benchmark method untimed, as JMH would set it up, with the census
// agent counting method entries. Usage: CensusRun <class> <method> <k=v,k=v|-> <invocations>
// Output (stdout): S lines (static route state of each compiled pattern found), M/R lines per phase.
public final class CensusRun
{
    private final Map<Class<?>, Object> states = new LinkedHashMap<>();
    private final Map<String, String> params;

    private CensusRun(Map<String, String> params)
    {
        this.params = params;
    }

    public static void main(String[] args)
            throws Throwable
    {
        Class<?> benchmarkClass = Class.forName(args[0]);
        String methodName = args[1];
        Map<String, String> params = new LinkedHashMap<>();
        if (!args[2].equals("-")) {
            for (String kv : args[2].split(",")) {
                int eq = kv.indexOf('=');
                params.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        int invocations = Integer.parseInt(args[3]);
        PrintStream out = System.out;
        CensusRun run = new CensusRun(params);
        Method benchmark = null;
        for (Method m : benchmarkClass.getMethods()) {
            if (m.getName().equals(methodName) && m.isAnnotationPresent(Benchmark.class)) {
                benchmark = m;
            }
        }
        if (benchmark == null) {
            throw new IllegalArgumentException("no benchmark " + methodName);
        }
        Probe.reset();
        Object instance = run.state(benchmarkClass, methodName);
        Object[] arguments = new Object[benchmark.getParameterCount()];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = run.argument(benchmark.getParameterTypes()[i], methodName);
        }
        out.println("P\tagentFailures\t" + Probe.failures);
        Probe.dump("setup", out);
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        for (Object state : run.states.values()) {
            findPatterns("state." + state.getClass().getSimpleName(), state, out, seen, 0);
        }
        // Phase "first": the first invocation(s) after setup, cold. Phase "op": the same number of
        // invocations after `warmup` more, the steady state JMH measures (cross-call streaks,
        // pointer-table promotion and other adaptive decisions have had a chance to settle).
        int warmup = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        Probe.reset();
        Object result = null;
        for (int i = 0; i < invocations; i++) {
            result = benchmark.invoke(instance, arguments);
        }
        Probe.dump("first", out);
        for (int i = 0; i < warmup; i++) {
            benchmark.invoke(instance, arguments);
        }
        Probe.reset();
        for (int i = 0; i < invocations; i++) {
            result = benchmark.invoke(instance, arguments);
        }
        Probe.dump("op", out);
        if (result != null) {
            findPatterns("result", result, out, seen, 0);
        }
        out.println("P\tresult\t" + summarizeResult(result));
        out.flush();
    }

    static String summarizeResult(Object result)
    {
        if (result == null) {
            return "null";
        }
        if (result instanceof Number || result instanceof Boolean) {
            return result.toString();
        }
        if (result instanceof Collection<?> c) {
            return "coll" + c.size();
        }
        return result.getClass().getSimpleName();
    }

    private Object argument(Class<?> type, String methodName)
            throws Throwable
    {
        if (type == Blackhole.class) {
            return new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        }
        return state(type, methodName);
    }

    private Object state(Class<?> type, String methodName)
            throws Throwable
    {
        Object existing = states.get(type);
        if (existing != null) {
            return existing;
        }
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object state = constructor.newInstance();
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                Param p = f.getAnnotation(Param.class);
                if (p == null) {
                    continue;
                }
                String value = params.containsKey(f.getName()) ? params.get(f.getName()) : p.value()[0];
                f.setAccessible(true);
                f.set(state, convert(f.getType(), value));
            }
        }
        List<Method> setups = new ArrayList<>();
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            chain.addFirst(k);
        }
        for (Class<?> k : chain) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Setup.class)) {
                    setups.add(m);
                }
            }
        }
        for (Method m : setups) {
            m.setAccessible(true);
            Class<?>[] types = m.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean special = false;
            for (int i = 0; i < types.length; i++) {
                if (types[i].getName().equals("org.openjdk.jmh.infra.BenchmarkParams")) {
                    special = true;
                    break;
                }
                args[i] = argument(types[i], methodName);
            }
            if (special) {
                // BenchmarkLanguageComparison.BenchmarkData.setup(BenchmarkParams) delegates to setup(String operation).
                Method byName = type.getDeclaredMethod("setup", String.class);
                byName.setAccessible(true);
                byName.invoke(state, methodName);
                continue;
            }
            try {
                m.invoke(state, args);
            }
            catch (java.lang.reflect.InvocationTargetException e) {
                // BenchmarkLanguageBulk verifies its result against Rebar's RE2 count; a language whose
                // answer differs (marked not-compatible in the report) still has a route to record.
                Method initialize;
                try {
                    initialize = type.getDeclaredMethod("initialize");
                }
                catch (NoSuchMethodException missing) {
                    throw e;
                }
                System.out.println("P\tsetupFailed\t" + String.valueOf(e.getCause()).replace('\n', ' '));
                initialize.setAccessible(true);
                initialize.invoke(state);
            }
        }
        states.put(type, state);
        return state;
    }

    private static Object convert(Class<?> type, String value)
    {
        if (type == String.class) {
            return value;
        }
        if (type == int.class) {
            return Integer.parseInt(value);
        }
        if (type == long.class) {
            return Long.parseLong(value);
        }
        if (type == boolean.class) {
            return Boolean.parseBoolean(value);
        }
        if (type.isEnum()) {
            for (Object c : type.getEnumConstants()) {
                if (((Enum<?>) c).name().equals(value)) {
                    return c;
                }
            }
        }
        throw new IllegalArgumentException("param type " + type + " " + value);
    }

    static boolean isPattern(Object o)
    {
        return o instanceof Re2 || o instanceof JavaRegexp || o instanceof RustRegexp || o instanceof TrinoRegexp
                || o instanceof TrinoLikePattern || o instanceof CompiledPattern;
    }

    static void findPatterns(String path, Object o, PrintStream out, IdentityHashMap<Object, Boolean> seen, int depth)
    {
        if (o == null || depth > 3 || seen.put(o, Boolean.TRUE) != null) {
            return;
        }
        if (isPattern(o)) {
            StringBuilder b = new StringBuilder();
            try {
                RouteState.describe(o, b, 0);
            }
            catch (Throwable e) {
                b.append(";describeError=").append(e);
            }
            out.println("S\t" + path + "\t" + b.toString().replace('\n', ' ').replace('\t', ' '));
            return;
        }
        if (o instanceof Collection<?> c) {
            int i = 0;
            for (Object e : c) {
                if (i++ > 4) {
                    break;
                }
                findPatterns(path + "[]", e, out, seen, depth + 1);
            }
            return;
        }
        if (o.getClass().isArray()) {
            if (!o.getClass().getComponentType().isPrimitive()) {
                Object[] a = (Object[]) o;
                for (int i = 0; i < Math.min(a.length, 4); i++) {
                    findPatterns(path + "[]", a[i], out, seen, depth + 1);
                }
            }
            return;
        }
        String name = o.getClass().getName();
        if (!name.startsWith("io.airlift.regulator.")) {
            return;
        }
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    findPatterns(path + "." + f.getName(), f.get(o), out, seen, depth + 1);
                }
                catch (Throwable ignored) {
                }
            }
        }
    }
}
