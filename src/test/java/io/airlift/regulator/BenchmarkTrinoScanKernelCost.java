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

import io.airlift.classfile.CompilationReport;
import io.airlift.slice.Slice;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.airlift.slice.Slices.utf8Slice;

/// Reports what generated scan classes cost for 1, 100, and 10,000 distinct patterns, or for the
/// counts given as arguments: generation, definition, and instantiation time per class, emitted
/// method bytecode and classfile size per class, and growth of metaspace, compressed class space,
/// and code cache, after definition and again after each class has found matches often enough to
/// be compiled. Memory pools are shared with everything else in the JVM, so run each count in its
/// own JVM for figures that are not cumulative.
///
/// The figures are cold first-use costs: the timer and the memory baseline start just before the
/// first class is generated, so in a fresh JVM they include loading the generator and the
/// classfile library. The code-cache figures are snapshots taken immediately after defining and
/// after exercising the classes, without waiting for background compilation.
public final class BenchmarkTrinoScanKernelCost
{
    private static final int[] DEFAULT_COUNTS = {1, 100, 10_000};
    // Enough calls for the JIT to compile each class's find entry and attempt loop.
    private static final int EXERCISE_CALLS = 20_000;

    private BenchmarkTrinoScanKernelCost() {}

    public static void main(String[] arguments)
    {
        int[] counts = arguments.length == 0 ? DEFAULT_COUNTS : Arrays.stream(arguments).mapToInt(Integer::parseInt).toArray();
        System.out.println("patterns\tbuildMicrosPerClass\tcodeBytesPerClass\tclassfileBytesPerClass\t" +
                "metaspaceDefined\tclassSpaceDefined\tcodeCacheDefined\tmetaspaceRun\tclassSpaceRun\tcodeCacheRun");
        for (int count : counts) {
            report(count);
        }
    }

    private static void report(int count)
    {
        List<TrinoScanKernelGenerator.Routes> routes = routes(count);
        List<Slice> inputs = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            inputs.add(input(index));
        }

        System.gc();
        Usage before = Usage.current();
        List<CompiledTrinoScan> scans = new ArrayList<>(count);
        long codeBytes = 0;
        long classfileBytes = 0;
        long start = System.nanoTime();
        for (TrinoScanKernelGenerator.Routes route : routes) {
            TrinoScanKernelGenerator.ReportedScan generated = TrinoScanKernelGenerator.generateCompiledScanForDiagnostics(route);
            scans.add(generated.compiled().scan());
            for (CompilationReport.ClassInfo classInfo : generated.report().classes()) {
                classfileBytes += classInfo.classfileBytes();
                for (CompilationReport.MethodInfo method : classInfo.methods()) {
                    codeBytes += method.codeBytes();
                }
            }
        }
        long elapsed = System.nanoTime() - start;
        Usage defined = Usage.current();

        int matches = 0;
        int[] groups = new int[2];
        for (int index = 0; index < count; index++) {
            CompiledTrinoScan scan = scans.get(index);
            Slice input = inputs.get(index);
            for (int call = 0; call < EXERCISE_CALLS; call++) {
                matches += scan.find(input, 0, groups) ? 1 : 0;
            }
        }
        if (matches != count * EXERCISE_CALLS) {
            throw new IllegalStateException("Generated class rejected its input");
        }
        Usage run = Usage.current();

        System.out.println(count + "\t" +
                elapsed / 1_000 / count + "\t" +
                codeBytes / count + "\t" +
                classfileBytes / count + "\t" +
                (defined.metaspace() - before.metaspace()) + "\t" +
                (defined.classSpace() - before.classSpace()) + "\t" +
                (defined.codeCache() - before.codeCache()) + "\t" +
                (run.metaspace() - before.metaspace()) + "\t" +
                (run.classSpace() - before.classSpace()) + "\t" +
                (run.codeCache() - before.codeCache()));
    }

    /// Returns the routes of `count` distinct end-anchored patterns, compiled without
    /// generated code, that cycle through the four `OPTIONAL_MIXED` shapes of
    /// [BenchmarkTrinoScanPlan].
    static List<TrinoScanKernelGenerator.Routes> routes(int count)
    {
        List<TrinoScanKernelGenerator.Routes> routes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            TrinoScanKernelGenerator.Routes route = TrinoScanKernelGenerator.Routes.of(TrinoRegexp.compile(expression(index)).pattern());
            if (route == null || TrinoScanKernelGenerator.Attempt.of(route.plan()) != TrinoScanKernelGenerator.Attempt.END_ANCHORED) {
                throw new IllegalStateException("Pattern " + index + " has no end-anchored scan plan to generate");
            }
            routes.add(route);
        }
        return routes;
    }

    static Slice expression(int index)
    {
        return utf8Slice("(?s)^svc" + index + ":" + switch (index % 4) {
            case 0 -> "(?:www\\.)?([^/]+)/.*$";
            case 1 -> "(www\\.)?([^/:]+)(?::([0-9]{1,5}))?/.*$";
            case 2 -> "(?:(?<key>[a-z]+):(?<value>[^/]+))?/end$";
            default -> "((a)?(b)?(c)?(d)?)abcd([^/]+)/$";
        });
    }

    static Slice input(int index)
    {
        return utf8Slice("svc" + index + ":" + switch (index % 4) {
            case 0 -> "www.host" + index + ".example.com/path";
            case 1 -> "host" + index + ".example.com:42/path";
            case 2 -> "key:value/end";
            default -> "abcdabcdhost" + index + "/";
        });
    }

    private record Usage(long metaspace, long classSpace, long codeCache)
    {
        static Usage current()
        {
            long metaspace = 0;
            long classSpace = 0;
            long codeCache = 0;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() != MemoryType.NON_HEAP) {
                    continue;
                }
                long used = pool.getUsage().getUsed();
                String name = pool.getName();
                if (name.equals("Metaspace")) {
                    metaspace += used;
                }
                else if (name.equals("Compressed Class Space")) {
                    classSpace += used;
                }
                else if (name.startsWith("CodeHeap") || name.equals("CodeCache")) {
                    codeCache += used;
                }
            }
            return new Usage(metaspace, classSpace, codeCache);
        }
    }
}
