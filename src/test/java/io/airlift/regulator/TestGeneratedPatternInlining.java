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

import io.airlift.regulator.GeneratedPatternInliningProbe.FirstGroup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.airlift.regulator.GeneratedPatternInliningProbe.HANDOFF_EXPRESSION;
import static io.airlift.regulator.GeneratedPatternInliningProbe.SITES;
import static org.assertj.core.api.Assertions.assertThat;

/// Checks that a caller holding a generated pattern as a constant reaches the pattern's generated
/// code with no dispatch, for every language, after many generated patterns of the language have
/// run through one shared call site. A child JVM runs [GeneratedPatternInliningProbe] with C2's
/// inlining output on, and the test reads the C2 compile of each per-pattern caller. The probe
/// covers `find`, `count`, and `matches` of RE2, Java, and Rust patterns, every public operation
/// that a Trino pattern's generated class declares, and LIKE `matches` of every plan.
///
/// A site's checked trees are the generated class's entry with every call below it in the
/// caller's compile, and the C2 compile of each method of the generated class that C2 calls
/// rather than inlines. C2 inlines the entry or calls it directly: whether it inlines a large
/// entry depends on its size heuristics, so a refusal for size, such as "already compiled into a
/// big method", is a direct call, and the entry's own last C2 compile is checked in its place. A
/// method of the generated class that C2 leaves out of line, such as its static search, is
/// checked in its own last C2 compile. No call in a checked tree, the entry's included, may be a
/// virtual call, a megamorphic site, a call through a type profile, or a call whose target C2
/// could not bind. A call into shared hand-written code that C2 leaves out of line is a direct
/// call into the shared engine, outside the pattern's own code: the test trusts it and does not
/// look inside it, as C2 decides such calls by sizes and call frequencies that depend on what
/// else the JVM ran. Shared code that C2 inlines into a checked tree is still checked for
/// dispatch. Each checked compile, the caller's or one in its place, must still be entrant at the
/// end: one the JVM made not entrant, or that C2 skipped, fails. The probe's replacement callback
/// is the caller's code, not the pattern's, so the callback `replace` entry's own call of it, the
/// call at the bytecode index the probe reads from the generated class, is not checked; every
/// other call of the entry is. The probe proves that call is the callback parameter's own: its
/// receiver is loaded straight from the parameter, which the entry never reassigns.
///
/// The probe's handoff sites hand an RE2, Java, Rust, or Trino search off to the ordinary
/// engine, so their checked trees must include the generated class's call of the engine's
/// continuation. A LIKE pattern has no ordinary engine, and `matches` makes one anchored
/// attempt, so neither hands off.
public class TestGeneratedPatternInlining
{
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private static final String CALLER_PREFIX = GeneratedPatternInliningProbe.class.getPackageName() + ".InliningCaller_";
    // A C2 compile line, as PrintCompilation prints it: the timestamp, the compile id, the
    // attribute flags, the tier, and the method with its size. An on-stack-replacement compile,
    // flagged %, prints its bytecode index after the method, so it does not match.
    private static final String C2_COMPILE_LINE = "^\\s*\\d+\\s+(\\d+)\\s+[%sbn! ]*\\s4\\s+(\\S+)::(\\S+) \\((\\d+) bytes\\)";
    private static final Pattern C2_COMPILE = Pattern.compile(C2_COMPILE_LINE + "$");
    // A later notice on a C2 compile, printed as its compile line followed by the notice: the
    // JVM made the code not entrant, or C2 skipped the compile, and why.
    private static final Pattern C2_NOTICE = Pattern.compile(C2_COMPILE_LINE + "   (made not entrant|COMPILE SKIPPED): (.*)$");
    private static final String CONTINUATION = "continueScanSearch";
    // One call of an inlining tree, as PrintInlining prints it: the method attribute flags, the
    // indent that gives the call's depth, the bytecode index, the callee, and the decision.
    private static final Pattern INLINE_LINE = Pattern.compile("^([ sm!]*)@ (\\d+)\\s+(\\S+)::(\\S+) \\((?:(\\d+) bytes|not loaded)\\)\\s*(.*)$");
    private static final Pattern COMPILE_LINE = Pattern.compile("^\\s*\\d+\\s+\\d+\\s");
    private static final Pattern CALL_SITE = Pattern.compile("@ \\d+");
    private static final List<String> DISPATCH_MARKERS = List.of(
            "virtual call",
            "megamorphic",
            "TypeProfile",
            "statically bindable",
            "no static binding",
            "not constant");
    private static final List<String> SIZE_REFUSALS = List.of(
            "already compiled into a big method",
            "already compiled into a medium method",
            "too big",
            "callee is too large",
            "size > DesiredMethodLimit");

    @Test
    public void testConstantCallersReachGeneratedPatternsDirectly()
            throws Exception
    {
        String output = runProbe();
        List<String> lines = output.lines().toList();
        assertThat(lines).as(tail(lines)).contains("DONE");
        assertThat(sites(lines)).hasSize(SITES);

        List<String> failures = failures(lines);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
    }

    @Test
    public void testInliningOutputParser()
    {
        String entry = "io.airlift.regulator.UnanchoredCompiledScanRe2/0x1::find";
        String search = "io.airlift.regulator.UnanchoredCompiledScanRe2/0x1::search (208 bytes)";
        String caller = CALLER_PREFIX + "re2_find_0/0x2::call (7 bytes)";
        List<String> callerCompile = List.of(
                site("re2_find_0", entry, -1),
                c2Compile(6541, caller),
                call(0, "", "java.lang.invoke.Invokers$Holder::invokeExact_MT (23 bytes)", "force inline by annotation"));
        String callbackEntry = "io.airlift.regulator.UnanchoredCompiledScanTrinoRegexp/0x3::replace";
        List<String> callbackCallerCompile = List.of(
                site("trino_replaceWithCallback_0", callbackEntry, 145),
                c2Compile(6544, CALLER_PREFIX + "trino_replaceWithCallback_0/0x4::call (7 bytes)"),
                call(0, "", "java.lang.invoke.Invokers$Holder::invokeExact_MT (23 bytes)", "force inline by annotation"),
                call(1, "", callbackEntry + " (220 bytes)", "inline (hot)"),
                call(2, 40, "", "io.airlift.regulator.UnanchoredCompiledScanTrinoRegexp/0x3::findFrom (45 bytes)", "inline (hot)"),
                call(2, 145, "", "java.util.function.Function::apply (0 bytes)", "failed to inline: virtual call"),
                call(3, 5, "", "java.util.List::getFirst (0 bytes)", "failed to inline: virtual call"));
        List<LogCase> cases = List.of(
                new LogCase(
                        "a flagged line under the entry, then a virtual call below it",
                        concat(callerCompile, List.of(
                                call(1, "", entry + " (45 bytes)", "inline (hot)"),
                                call(2, "!", "io.airlift.regulator.CompiledPattern::finishScanSearch (60 bytes)", "inline (hot)"),
                                call(3, "", "io.airlift.regulator.CompiledPattern::matchOnOrdinaryEngine (90 bytes)", "failed to inline: virtual call"))),
                        "dispatch under the entry"),
                new LogCase(
                        "an entry inlined through a type profile",
                        concat(callerCompile, List.of(
                                call(1, "", entry + " (45 bytes)", "inline (hot)    \\-> TypeProfile (10/10 counts) = io/airlift/regulator/UnanchoredCompiledScanRe2"),
                                call(2, "", "io.airlift.regulator.ScanKernelSupport::findByte (36 bytes)", "inline (hot)"))),
                        "dispatch to the entry"),
                new LogCase(
                        "a statically bound out-of-line hand-written call under the entry",
                        concat(callerCompile, List.of(
                                call(1, "", entry + " (45 bytes)", "inline (hot)"),
                                call(2, "", "io.airlift.regulator.CompiledPattern::matchOnOrdinaryEngine (1793 bytes)", "failed to inline: hot method too big"))),
                        null),
                new LogCase(
                        "a Function call under an entry other than the callback replace",
                        concat(callerCompile, List.of(
                                call(1, "", entry + " (45 bytes)", "inline (hot)"),
                                call(2, "", "java.util.function.Function::apply (0 bytes)", "failed to inline: virtual call"))),
                        "dispatch under the entry"),
                new LogCase(
                        "the callback replace entry's own call of the callback, with a call under it",
                        callbackCallerCompile,
                        null),
                new LogCase(
                        "a second Function call of the callback replace entry",
                        concat(callbackCallerCompile, List.of(
                                call(2, 13, "", "java.util.function.Function::apply (0 bytes)", "failed to inline: virtual call"))),
                        "dispatch under the entry"),
                new LogCase(
                        "a caller whose compile was made not entrant",
                        concat(callerCompile, List.of(
                                call(1, "", entry + " (45 bytes)", "inline (hot)"),
                                call(2, "", "io.airlift.regulator.ScanKernelSupport::findByte (36 bytes)", "inline (hot)"),
                                notEntrant(6541, caller))),
                        "was made not entrant"),
                new LogCase(
                        "a caller whose compile C2 skipped",
                        concat(callerCompile, List.of(
                                call(1, "", entry + " (45 bytes)", "inline (hot)"),
                                skipped(6541, caller))),
                        "was skipped"),
                new LogCase(
                        "a handoff site whose checked trees never call the continuation",
                        List.of(
                                site("re2_find_32", HANDOFF_EXPRESSION, entry, -1),
                                c2Compile(6545, CALLER_PREFIX + "re2_find_32/0x5::call (7 bytes)"),
                                call(0, "", "java.lang.invoke.Invokers$Holder::invokeExact_MT (23 bytes)", "force inline by annotation"),
                                call(1, "", entry + " (45 bytes)", "inline (hot)"),
                                call(2, "", search, "failed to inline: already compiled into a big method"),
                                c2Compile(6546, search),
                                call(0, "", "io.airlift.regulator.ScanKernelSupport::findByte (36 bytes)", "inline (hot)")),
                        "never call the engine's continuation"),
                new LogCase(
                        "an unreadable call line",
                        concat(callerCompile, List.of(
                                call(1, "", entry + " (45 bytes)", "inline (hot)"),
                                " ".repeat(32) + "@ 9   an unreadable call")),
                        "unreadable inlining line"),
                new LogCase(
                        "a big-method entry whose own compile is clean",
                        concat(callerCompile, List.of(
                                call(1, "", entry + " (45 bytes)", "failed to inline: already compiled into a big method"),
                                c2Compile(6542, entry + " (45 bytes)"),
                                call(0, "", search, "failed to inline: already compiled into a big method"),
                                c2Compile(6543, search),
                                call(0, "", "io.airlift.regulator.ScanKernelSupport::findByte (36 bytes)", "inline (hot)"))),
                        null),
                new LogCase(
                        "a big-method entry whose own compile was made not entrant",
                        concat(callerCompile, List.of(
                                call(1, "", entry + " (45 bytes)", "failed to inline: already compiled into a big method"),
                                c2Compile(6542, entry + " (45 bytes)"),
                                call(0, "", "io.airlift.regulator.ScanKernelSupport::findByte (36 bytes)", "inline (hot)"),
                                notEntrant(6542, entry + " (45 bytes)"))),
                        "was made not entrant"),
                new LogCase(
                        "a big-method entry whose own compile dispatches",
                        concat(callerCompile, List.of(
                                call(1, "", entry + " (45 bytes)", "failed to inline: already compiled into a big method"),
                                c2Compile(6542, entry + " (45 bytes)"),
                                call(0, "", "io.airlift.regulator.CompiledPattern::continueScanSearch (80 bytes)", "failed to inline: virtual call"))),
                        "dispatch under the entry"));
        for (LogCase logCase : cases) {
            List<String> failures = failures(logCase.log());
            if (logCase.failure() == null) {
                assertThat(failures).as(logCase.description()).isEmpty();
            }
            else {
                assertThat(failures).as(logCase.description()).singleElement().asString().contains(logCase.failure());
            }
        }
    }

    /// The sites in the probe's output, by caller.
    private static Map<String, Site> sites(List<String> lines)
    {
        Map<String, Site> sites = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.startsWith("SITE\t")) {
                String[] values = line.split("\t", -1);
                sites.put(values[1], new Site(values[2], values[3], values[4], values[5], values[6], Integer.parseInt(values[7])));
            }
        }
        return sites;
    }

    /// The failures of every site in the probe's output: C2 did not compile the site's caller,
    /// the caller does not call the pattern's entry, C2 declined to inline the entry for a reason
    /// other than its size, C2 did not compile a method of the generated class that it calls, a
    /// checked compile is no longer entrant, a call in a checked tree dispatches, a handoff site's
    /// checked trees never call the engine's continuation, or an inlining line is unreadable.
    private static List<String> failures(List<String> lines)
    {
        Map<String, Compile> callerCompiles = new HashMap<>();
        Map<String, Compile> compiles = new HashMap<>();
        // Why each compile that has a notice is no longer entrant, by compile id and method.
        Map<String, String> notices = new HashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher compile = C2_COMPILE.matcher(line);
            if (compile.matches()) {
                // The last C2 compile of a method is the code it runs, unless a notice ends it.
                String method = methodKey(compile.group(2), compile.group(3), compile.group(4));
                Compile last = new Compile(compile.group(1), method, index);
                compiles.put(method, last);
                if (compile.group(2).startsWith(CALLER_PREFIX) && compile.group(3).equals("call")) {
                    callerCompiles.put(compile.group(2).substring(0, compile.group(2).indexOf('/')), last);
                }
                continue;
            }
            Matcher notice = C2_NOTICE.matcher(line);
            if (notice.matches()) {
                String method = methodKey(notice.group(2), notice.group(3), notice.group(4));
                notices.put(notice.group(1) + " " + method, (notice.group(5).equals("made not entrant") ? "made not entrant: " : "skipped: ") + notice.group(6));
            }
        }

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Site> entry : sites(lines).entrySet()) {
            Site site = entry.getValue();
            Compile callerCompile = callerCompiles.get(entry.getKey());
            if (callerCompile == null) {
                failures.add(site + ": C2 did not compile the caller");
                continue;
            }
            try {
                failures.addAll(new SiteCheck(site, lines, compiles, notices).check(callerCompile));
            }
            catch (IllegalArgumentException e) {
                failures.add(site + ": " + e.getMessage());
            }
        }
        return failures;
    }

    /// Checks the checked trees of one site, from the entry in its caller's inlining tree.
    private static final class SiteCheck
    {
        private final Site site;
        private final List<String> lines;
        private final Map<String, Compile> compiles;
        private final Map<String, String> notices;
        private final Set<String> checkedCompiles = new HashSet<>();
        private final List<String> failures = new ArrayList<>();
        private boolean continuation;

        private SiteCheck(Site site, List<String> lines, Map<String, Compile> compiles, Map<String, String> notices)
        {
            this.site = site;
            this.lines = lines;
            this.compiles = compiles;
            this.notices = notices;
        }

        private List<String> check(Compile callerCompile)
        {
            if (entrant(callerCompile)) {
                checkCaller(callerCompile);
            }
            if (site.handoff() && failures.isEmpty() && !continuation) {
                failures.add(site + ": the handoff site's checked trees never call the engine's continuation, " + CONTINUATION);
            }
            return failures;
        }

        private void checkCaller(Compile callerCompile)
        {
            List<Call> callerTree = inliningTree(lines, callerCompile.line());
            for (int index = 0; index < callerTree.size(); index++) {
                Call entry = callerTree.get(index);
                if (!entry.owner().equals(site.patternClass()) || !entry.method().equals(site.method())) {
                    continue;
                }
                if (DISPATCH_MARKERS.stream().anyMatch(entry.line()::contains)) {
                    failures.add(site + ": dispatch to the entry: " + entry.line().strip());
                    return;
                }
                if (!entry.inlined()) {
                    if (SIZE_REFUSALS.stream().noneMatch(entry.decision()::contains)) {
                        failures.add(site + ": entry not inlined: " + entry.line().strip());
                        return;
                    }
                    // C2 calls an entry it declines to inline for size directly.
                    checkCompiled(entry, true);
                    return;
                }
                checkCalls(callerTree.subList(index + 1, callerTree.size()), entry.depth(), true);
                return;
            }
            failures.add(site + ": entry " + site.patternClass() + "::" + site.method() + " is not in the caller's inlining tree:\n    " +
                    String.join("\n    ", callerTree.stream().map(Call::line).toList()));
        }

        /// Checks the calls of a tree that are deeper than `depth`, up to the first that is not. The
        /// calls are those of the site's entry when `entry` is set. The callback `replace` entry's
        /// own call of the replacement callback, at the bytecode index the site records, and the
        /// calls below it are the caller's code and are skipped.
        private void checkCalls(List<Call> tree, int depth, boolean entry)
        {
            // The entry's own calls are the first call's and those at its depth, and their
            // bytecode indexes are the entry's, whether C2 inlined the entry or compiled it alone.
            int callDepth = tree.isEmpty() ? depth : tree.getFirst().depth();
            boolean callbackEntry = entry && site.operation().equals("replaceWithCallback");
            int skipDepth = Integer.MAX_VALUE;
            for (Call call : tree) {
                if (call.depth() <= depth) {
                    return;
                }
                if (call.depth() > skipDepth) {
                    continue;
                }
                skipDepth = Integer.MAX_VALUE;
                if (callbackEntry && call.depth() == callDepth && call.callsCallback() && call.bytecodeIndex() == site.callbackBytecodeIndex()) {
                    skipDepth = call.depth();
                    continue;
                }
                if (call.method().equals(CONTINUATION)) {
                    continuation = true;
                }
                if (DISPATCH_MARKERS.stream().anyMatch(call.line()::contains)) {
                    failures.add(site + ": dispatch under the entry: " + call.line().strip());
                }
                else if (!call.inlined() && call.owner().equals(site.patternClass())) {
                    checkCompiled(call, false);
                }
                // A call into shared hand-written code that C2 leaves out of line is a direct
                // call into the shared engine, whose body the test trusts.
            }
        }

        /// Checks the last C2 compile of a method of the generated class that C2 calls rather than
        /// inlines, the site's entry when `entry` is set.
        private void checkCompiled(Call call, boolean entry)
        {
            if (!checkedCompiles.add(call.key())) {
                return;
            }
            Compile compile = compiles.get(call.key());
            if (compile == null) {
                failures.add(site + ": C2 did not compile " + call.key() + ", which it calls: " + call.line().strip());
                return;
            }
            if (entrant(compile)) {
                checkCalls(inliningTree(lines, compile.line()), -1, entry);
            }
        }

        /// Whether the code of `compile` is still entrant at the end of the output: neither made
        /// not entrant nor skipped by C2. Records a failure when it is not.
        private boolean entrant(Compile compile)
        {
            String notice = notices.get(compile.id() + " " + compile.method());
            if (notice == null) {
                return true;
            }
            failures.add(site + ": C2 compile " + compile.id() + " of " + compile.method() + " was " + notice);
            return false;
        }
    }

    /// The calls of the inlining tree that follows a compile line, past the notices C2 prints when
    /// the new code replaces older code.
    ///
    /// @throws IllegalArgumentException if a line names a call site but is not a call the parser
    ///         reads
    private static List<Call> inliningTree(List<String> lines, int compile)
    {
        List<Call> tree = new ArrayList<>();
        for (String line : lines.subList(compile + 1, lines.size())) {
            Matcher call = INLINE_LINE.matcher(line);
            if (call.matches()) {
                tree.add(new Call(line, call.group(1).length(), Integer.parseInt(call.group(2)), call.group(3), call.group(4), call.group(5), call.group(6)));
            }
            else if (COMPILE_LINE.matcher(line).lookingAt()) {
                if (!line.contains("made not entrant")) {
                    break;
                }
            }
            else if (CALL_SITE.matcher(line).find()) {
                throw new IllegalArgumentException("unreadable inlining line: " + line.strip());
            }
            else {
                break;
            }
        }
        return tree;
    }

    private static String runProbe()
            throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        command.add("--add-modules");
        command.add("jdk.incubator.vector");
        // Compile in the foreground so each compile's inlining output follows its compile line,
        // and keep the probe's driver interpreted so C2 compiles each caller on its own rather
        // than into the driver's loop.
        command.add("-Xbatch");
        command.add("-XX:+UnlockDiagnosticVMOptions");
        command.add("-XX:+PrintCompilation");
        command.add("-XX:+PrintInlining");
        command.add("-XX:CompileCommand=quiet");
        command.add("-XX:CompileCommand=exclude," + GeneratedPatternInliningProbe.class.getName() + "::main");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(GeneratedPatternInliningProbe.class.getName());

        Path output = Files.createTempFile("regulator-inlining-probe-", ".log");
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
            processBuilder.environment().remove("JDK_JAVA_OPTIONS");
            Process process = processBuilder.start();
            try {
                boolean finished = process.waitFor(TIMEOUT);
                String result = Files.readString(output, StandardCharsets.UTF_8);
                assertThat(finished).as("inlining probe timed out:\n%s", tail(result.lines().toList())).isTrue();
                assertThat(process.exitValue()).as(tail(result.lines().toList())).isZero();
                return result;
            }
            finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(Duration.ofSeconds(10))).as("inlining probe did not terminate").isTrue();
                }
            }
        }
        finally {
            Files.deleteIfExists(output);
        }
    }

    private static String tail(List<String> lines)
    {
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    /// A probe `SITE` line of the caller `InliningCaller_<caller>`, whose name gives the language
    /// and the operation, of the pattern entry `entry`, whose call of the replacement callback is at
    /// bytecode index `callbackBytecodeIndex`.
    private static String site(String caller, String entry, int callbackBytecodeIndex)
    {
        return site(caller, "k0x([^/]+)/", entry, callbackBytecodeIndex);
    }

    /// A probe `SITE` line as [#site(String,String,int)] writes it, of a pattern of `expression`.
    private static String site(String caller, String expression, String entry, int callbackBytecodeIndex)
    {
        String[] parts = caller.split("_");
        String patternClass = entry.substring(0, entry.indexOf("::"));
        String method = entry.substring(entry.indexOf("::") + 2);
        return String.join("\t", "SITE", CALLER_PREFIX + caller, parts[0], parts[1], method, patternClass, expression, String.valueOf(callbackBytecodeIndex));
    }

    /// A PrintCompilation line of a normal blocking C2 compile, numbered `id`, of `method`.
    private static String c2Compile(int id, String method)
    {
        return "   9023 " + id + "    b  4       " + method;
    }

    /// The PrintCompilation line that makes the code of C2 compile `id` of `method` not entrant.
    private static String notEntrant(int id, String method)
    {
        return "  14349 " + id + "       4       " + method + "   made not entrant: marked for deoptimization";
    }

    /// The PrintCompilation line of C2 skipping compile `id` of `method`.
    private static String skipped(int id, String method)
    {
        return "  14350 " + id + "       4       " + method + "   COMPILE SKIPPED: out of nodes during split";
    }

    /// A PrintInlining line of a call at inlining level `level`, laid out as HotSpot lays it out,
    /// with the method attribute flags `flags`.
    private static String call(int level, String flags, String callee, String decision)
    {
        return call(level, 7, flags, callee, decision);
    }

    /// A PrintInlining line of a call at inlining level `level` and bytecode index
    /// `bytecodeIndex`, laid out as HotSpot lays it out, with the method attribute flags `flags`.
    private static String call(int level, int bytecodeIndex, String flags, String callee, String decision)
    {
        return " ".repeat(14) + "%-3s".formatted(flags) + " ".repeat(13) + "  ".repeat(level) + "@ " + bytecodeIndex + "   " + callee + "   " + decision;
    }

    private static List<String> concat(List<String> first, List<String> second)
    {
        List<String> lines = new ArrayList<>(first);
        lines.addAll(second);
        return lines;
    }

    /// One call of an inlining tree, at the depth of the column of its `@`, made at bytecode index
    /// `bytecodeIndex` of the method that contains it.
    private record Call(String line, int depth, int bytecodeIndex, String owner, String method, String size, String decision)
    {
        boolean inlined()
        {
            return !decision.contains("failed to inline");
        }

        /// Whether the call names the probe's replacement callback, either through its interface
        /// or as its class. Only the call at the callback's own bytecode index is the callback's;
        /// another call of `Function.apply` is the pattern's.
        boolean callsCallback()
        {
            return method.equals("apply") && (owner.equals(Function.class.getName()) || owner.equals(FirstGroup.class.getName()));
        }

        /// The method's key among the compiles.
        String key()
        {
            return methodKey(owner, method, size);
        }
    }

    /// The key of a method among the compiles: its class, its name, and its size, as
    /// PrintCompilation names it. Overloads of the same size would share a key.
    private static String methodKey(String owner, String method, String size)
    {
        return owner + "::" + method + " (" + size + " bytes)";
    }

    /// A C2 compile, numbered `id`, of `method`, at index `line` of the output.
    private record Compile(String id, String method, int line) {}

    private record Site(String language, String operation, String method, String patternClass, String expression, int callbackBytecodeIndex)
    {
        /// Whether the site's input hands its search off to the ordinary engine.
        boolean handoff()
        {
            return expression.equals(HANDOFF_EXPRESSION);
        }

        @Override
        public String toString()
        {
            return language + " " + operation + " " + expression;
        }
    }

    private record LogCase(String description, List<String> log, String failure) {}
}
