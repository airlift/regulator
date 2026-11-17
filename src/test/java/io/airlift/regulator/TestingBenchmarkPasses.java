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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.openjdk.jmh.annotations.CompilerControl.Mode.DONT_INLINE;

/// Checks the timed passes of a JMH class under the pass protocol, for its tests and the Joni
/// verifiers. A benchmark method that takes a [Blackhole] is a pass: one invocation runs its
/// operation once on each of a workload's eight inputs, so it must declare that many operations
/// per invocation, and a method without a [Blackhole], such as a compile, must declare none. A
/// pass whose loop skipped an input, or handed its operation some other value, would still time
/// and still pass verification, which calls the operation outside the pass, so each pass runs
/// with each input in turn replaced by `null` and must throw [NullPointerException] from the
/// operation it handed that input to. The loop itself runs in a method that takes the
/// [Blackhole], the benchmark method or an adapter pass it delegates to, and the operation
/// never sees the [Blackhole], so the exception must not be thrown by such a method: a loop
/// that dereferenced its input without matching it would throw there. The frame the loop called
/// must be a method annotated `@CompilerControl(DONT_INLINE)`, and no frame it called may be, so
/// each input pays one compiled call, as each native engine pays one out-of-line call.
public final class TestingBenchmarkPasses
{
    /// The inputs of every workload under the pass protocol.
    private static final int INPUTS = 8;

    private static final Blackhole BLACKHOLE = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");

    private TestingBenchmarkPasses() {}

    /// The states each pass times, set up over a workload's real inputs.
    @FunctionalInterface
    public interface PassStates
    {
        List<?> states(Method pass)
                throws Exception;
    }

    /// The states of `states` whose class is the state class a pass takes.
    public static PassStates byStateClass(List<?> states)
    {
        return pass -> states.stream()
                .filter(state -> state.getClass() == pass.getParameterTypes()[0])
                .toList();
    }

    /// Checks every benchmark method of `benchmark`'s class. Each pass must declare eight
    /// operations per invocation and have at least one state; with each state, it must complete
    /// over the real inputs and throw [NullPointerException] from its operation with each input of
    /// the array `inputs` returns for the state replaced by `null` in turn, and the operation its
    /// loop called must be the only frame annotated `@CompilerControl(DONT_INLINE)`. The input is
    /// restored after each run.
    public static void verifyPasses(Object benchmark, PassStates states, Function<Object, Object[]> inputs)
            throws Exception
    {
        int passes = 0;
        for (Method method : benchmark.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Benchmark.class)) {
                continue;
            }
            String name = benchmark.getClass().getSimpleName() + "." + method.getName();
            OperationsPerInvocation operations = method.getAnnotation(OperationsPerInvocation.class);
            if (!Arrays.asList(method.getParameterTypes()).contains(Blackhole.class)) {
                if (operations != null) {
                    throw new AssertionError(name + " is not a pass but declares operations per invocation");
                }
                continue;
            }
            if (operations == null || operations.value() != INPUTS) {
                throw new AssertionError(name + " is a pass but does not declare " + INPUTS + " operations per invocation");
            }
            List<?> passStates = states.states(method);
            if (passStates.isEmpty()) {
                throw new AssertionError(name + " has no states to check");
            }
            for (Object state : passStates) {
                Object[] array = inputs.apply(state);
                if (array.length != INPUTS) {
                    throw new AssertionError("%s passes over %s inputs, not %s".formatted(name, array.length, INPUTS));
                }
                run(method, benchmark, state);
                for (int index = 0; index < INPUTS; index++) {
                    Object input = array[index];
                    array[index] = null;
                    try {
                        run(method, benchmark, state);
                        throw new AssertionError("%s completed a pass with input %s null, so it never handed that input to its operation".formatted(name, index));
                    }
                    catch (NullPointerException expected) {
                        StackTraceElement[] trace = expected.getStackTrace();
                        if (trace.length == 0) {
                            throw new AssertionError("%s threw NullPointerException without a stack trace with input %s null, so the frame that threw is unknown; run with -XX:-OmitStackTraceInFastThrow".formatted(name, index), expected);
                        }
                        int loop = 0;
                        while (loop < trace.length && !takesBlackhole(trace[loop])) {
                            loop++;
                        }
                        if (loop == 0) {
                            throw new AssertionError("%s threw NullPointerException in its own loop at %s with input %s null, so it never handed that input to its operation".formatted(name, trace[0], index), expected);
                        }
                        if (loop == trace.length) {
                            throw new AssertionError("%s threw NullPointerException with input %s null from no frame of a method that takes a Blackhole, so its loop is unknown".formatted(name, index), expected);
                        }
                        if (!dontInline(trace[loop - 1])) {
                            throw new AssertionError("%s handed input %s to %s, which is not annotated @CompilerControl(DONT_INLINE), so the JIT may inline the operation into the pass".formatted(name, index, trace[loop - 1]), expected);
                        }
                        for (int frame = 0; frame < loop - 1; frame++) {
                            if (dontInline(trace[frame])) {
                                throw new AssertionError("%s reached input %s through %s and then %s, both annotated @CompilerControl(DONT_INLINE), so it pays two calls per input".formatted(name, index, trace[loop - 1], trace[frame]), expected);
                            }
                        }
                    }
                    finally {
                        array[index] = input;
                    }
                    // The state recovers once the input is restored.
                    run(method, benchmark, state);
                }
            }
            passes++;
        }
        if (passes == 0) {
            throw new AssertionError(benchmark.getClass().getName() + " has no passes");
        }
    }

    /// Whether the method of `frame` takes a [Blackhole], as only a pass loop's method does.
    private static boolean takesBlackhole(StackTraceElement frame)
            throws ClassNotFoundException
    {
        return methods(frame).anyMatch(method -> Arrays.asList(method.getParameterTypes()).contains(Blackhole.class));
    }

    /// Whether the method of `frame` is annotated `@CompilerControl(DONT_INLINE)`. JMH's compiler
    /// hints name a method without its parameters, so an annotated method of that name keeps
    /// every method of that name out of line.
    private static boolean dontInline(StackTraceElement frame)
            throws ClassNotFoundException
    {
        return methods(frame)
                .map(method -> method.getAnnotation(CompilerControl.class))
                .anyMatch(control -> control != null && control.value() == DONT_INLINE);
    }

    /// The methods of the frame's class, which may be a nested class, with the frame's method name.
    private static Stream<Method> methods(StackTraceElement frame)
            throws ClassNotFoundException
    {
        return Arrays.stream(Class.forName(frame.getClassName(), false, TestingBenchmarkPasses.class.getClassLoader()).getDeclaredMethods())
                .filter(method -> method.getName().equals(frame.getMethodName()));
    }

    private static void run(Method pass, Object benchmark, Object state)
            throws Exception
    {
        try {
            pass.invoke(benchmark, state, BLACKHOLE);
        }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
