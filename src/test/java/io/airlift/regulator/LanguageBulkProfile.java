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

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Diagnostic execution of the exact bulk operation; elapsed time is not benchmark evidence.
 */
public final class LanguageBulkProfile
{
    private LanguageBulkProfile() {}

    public static void main(String[] args)
            throws Exception
    {
        if (args.length != 4) {
            throw new IllegalArgumentException("expected engine, workload, expected result, output recording");
        }
        BenchmarkLanguageBulk.BenchmarkData data = new BenchmarkLanguageBulk.BenchmarkData();
        data.engine = args[0];
        data.workloadFile = args[1];
        data.expectedResult = Long.parseLong(args[2]);
        data.setup();
        printRoute(data);
        long warmupIterations = execute(data, Duration.ofSeconds(10));
        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.setName("language-bulk-diagnostic");
            recording.setMaxSize(128L * 1024 * 1024);
            recording.start();
            long recordedIterations = execute(data, Duration.ofSeconds(20));
            recording.stop();
            recording.dump(Path.of(args[3]));
            System.out.println("diagnostic-only warmupIterations=" + warmupIterations +
                    " recordedIterations=" + recordedIterations + " expectedResult=" + data.expectedResult);
        }
    }

    private static void printRoute(BenchmarkLanguageBulk.BenchmarkData data)
            throws ReflectiveOperationException
    {
        Object pattern = field(data, "pattern");
        if (pattern instanceof JavaRegexp || pattern instanceof TrinoRegexp) {
            pattern = field(pattern, "pattern");
        }
        if (pattern instanceof Re2 re2) {
            Prog program = (Prog) field(re2, "partialProg");
            System.out.println("programSize=" + program.size() +
                    " textDependentAssertions=" + program.textDependentAssertions() +
                    " onePass=" + program.isOnePass() + " bitState=" + program.canBitState() +
                    " taggedAlternation=" + re2.usesTaggedAlternationForDiagnostics() +
                    " captureGroups=" + re2.capturingGroupCount());
        }
    }

    private static Object field(Object instance, String name)
            throws ReflectiveOperationException
    {
        // Diagnostics inspect private state without adding reflection or accessors to production paths.
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static long execute(BenchmarkLanguageBulk.BenchmarkData data, Duration duration)
    {
        long start = System.nanoTime();
        long iterations = 0;
        do {
            if (data.execute() != data.expectedResult) {
                throw new IllegalStateException("profiled operation disagrees with verified result");
            }
            iterations++;
        }
        while (System.nanoTime() - start < duration.toNanos());
        return iterations;
    }
}
