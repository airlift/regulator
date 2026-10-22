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

import io.airlift.regulator.TestTrinoScanPlan.PatternWitness;
import io.airlift.regulator.TestTrinoScanPlan.QuadraticSearch;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;

import static io.airlift.regulator.TestTrinoScanPlan.MALFORMED_SEQUENCES;
import static io.airlift.regulator.TestTrinoScanPlan.QUADRATIC_SEARCHES;
import static io.airlift.regulator.TestTrinoScanPlan.URL;
import static io.airlift.regulator.TestTrinoScanPlan.generatedSequence;
import static io.airlift.regulator.TestTrinoScanPlan.randomSources;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class TestTrinoScanKernel
{
    private static final String OPTIONAL_RETRIES = "^((a)?(b)?(c)?(d)?)abcd([^/]+)/$";
    private static final MethodType BOOLEAN_ATTEMPT = MethodType.methodType(boolean.class, Slice.class, int.class, int.class, boolean.class, int[].class);
    private static final MethodType UNANCHORED_ATTEMPT = MethodType.methodType(long.class, byte[].class, int.class, int.class, int.class, boolean.class, int[].class);
    private static final List<String> SYMBOLS = List.of("a", "b", "c", "d", "1", "x", "/", ":", "=", "&", "?", "!", ".", "\n", "\u0000", "é", "😀", "abcd", "https://", "www.");
    // The replacement callbacks: one that builds each replacement, one that ends replacement
    // early, and one that fails.
    private static final List<Function<List<Slice>, Slice>> CALLBACKS = List.of(
            TestTrinoScanPlan::callbackReplacement,
            _ -> null,
            groups -> {
                throw new IllegalStateException("callback failed with " + groups.size() + " groups");
            });
    // Every method of another class in this package that generated classes and their helpers
    // call. Generated code calls copies of the interpreter's scan helpers, because C2 compiles a
    // shared helper from the generated callers' profiles and the interpreter can then no longer
    // inline it. Methods outside this package belong to Slice or the JDK.
    private static final Set<String> GENERATED_CODE_CALLS = Set.of(
            "Re2.continueTrinoScanSearch",
            "TrinoRegexp.newReplacementPlan",
            "TrinoRegexp.positionSearchStart",
            "TrinoReplacementPlan.addMatch",
            "TrinoReplacementPlan.build",
            "TrinoReplacementPlan.maximumCapturingGroup",
            "TrinoScanKernelSupport.containsRequiredLiteral",
            "TrinoScanKernelSupport.decode",
            "TrinoScanKernelSupport.findByte",
            "TrinoScanKernelSupport.findLiteral",
            "TrinoScanKernelSupport.literalMatches",
            "TrinoScanKernelSupport.re2RunMatch",
            "TrinoScanKernelSupport.re2RunMatchBefore",
            "TrinoScanKernelSupport.re2RunMatchMinimum",
            "TrinoScanKernelSupport.readInt",
            "TrinoScanKernelSupport.readLong",
            "TrinoScanKernelSupport.readShort",
            "TrinoScanKernelSupport.runFindCandidate",
            "TrinoScanKernelSupport.runMatch",
            "TrinoScanKernelSupport.runMatchMinimum",
            "TrinoScanPlan$Step.first",
            "TrinoScanPlan$Step.last",
            "TrinoScanPlan$Step.lastOffset",
            "TrinoScanPlan$Step.width",
            "TrinoScanPlanRun.candidateNibbleTables",
            "TrinoScanPlanRun.candidateScan",
            "TrinoScanPlanRun.highestMember",
            "TrinoScanPlanRun.isInverted",
            "TrinoScanPlanRun.lowestMember",
            "TrinoScanPlanRun.matchesNonAscii",
            "TrinoScanPlanRun.maximum",
            "TrinoScanPlanRun.members",
            "TrinoScanPlanRun.minimum",
            "TrinoScanPlanRun.needles",
            "Utf8.decodedWidth",
            "VectorSupport.isAvailable",
            "VectorTrinoScanKernelSupport.containsLiteral",
            "VectorTrinoScanKernelSupport.findByte",
            "VectorTrinoScanKernelSupport.findByteSet",
            "VectorTrinoScanKernelSupport.findNibbleTable",
            "VectorTrinoScanKernelSupport.findRange",
            "VectorTrinoScanKernelSupport.findRe2RunStop",
            "VectorTrinoScanKernelSupport.findRunCandidate",
            "VectorTrinoScanKernelSupport.findRunStop");

    /**
     * A pattern compiled with generated bytecode, the attempt it generates, and whether contains
     * reaches the generated class rather than the route it takes without it.
     */
    private record Route(String expression, MethodType attempt, boolean contains) {}

    // Executor routes for the ClickBench URL extraction, a partial scan, two run-led unanchored
    // scans, and the four-optional retry stack, with every operation on the generated class. The
    // last two keep the literal kernel that answers contains without generated code; every other
    // operation of theirs runs the plan.
    private static final List<Route> ROUTES = List.of(
            new Route(URL, BOOLEAN_ATTEMPT, true),
            new Route("^https?://[^/]+/", BOOLEAN_ATTEMPT, true),
            new Route("[?&]([^=]+)=", UNANCHORED_ATTEMPT, true),
            new Route("([^/]+)/([^!]+)!x", UNANCHORED_ATTEMPT, true),
            new Route(OPTIONAL_RETRIES, BOOLEAN_ATTEMPT, true),
            new Route("^(a)", BOOLEAN_ATTEMPT, false),
            new Route("^(😀)$", BOOLEAN_ATTEMPT, false));

    /**
     * An eligible pattern runs on the generated class, with the attempt of its plan's shape and a
     * handle for each operation that reaches the plan, while the same pattern compiled without
     * the option keeps no generated code. Patterns that have no case-sensitive scan plan or
     * compile to another subclass keep the class they have without the option.
     */
    @Test
    public void testRouteSelection()
    {
        for (Route route : ROUTES) {
            String expression = route.expression();
            TrinoRegexp generated = generated(expression);
            assertThat(generated.usesGeneratedBytecode()).as(expression).isTrue();
            assertThat(generated.attemptHandleForDiagnostics().type()).as(expression).isEqualTo(route.attempt());
            assertThat(generated.generatedContains().isPresent()).as(expression).isEqualTo(route.contains());
            assertThat(generated.generatedCount()).as(expression).isPresent();
            assertThat(generated.generatedPosition()).as(expression).isPresent();
            assertThat(generated.generatedSplit()).as(expression).isPresent();
            assertThat(generated.generatedReplace()).as(expression).isPresent();
            assertThat(generated.generatedReplaceWithCallback()).as(expression).isPresent();
            for (int group = 0; group <= generated.capturingGroupCount(); group++) {
                assertThat(generated.generatedExtract(group)).as(expression).isPresent();
                assertThat(generated.generatedExtractAll(group)).as(expression).isPresent();
            }

            TrinoRegexp interpreted = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(interpreted.getClass()).as(expression).isEqualTo(TrinoRegexp.class);
            assertNoGeneratedCode(expression, interpreted);
        }

        // ASCII-folded plans, patterns without a scan plan, with or without a semantic program,
        // and ordered literals keep the class they have without generated code.
        for (String expression : List.of("(?i)^ABC([^:]+):DEF$", "x(?:ab|cd)*y", "foo|bar", "first(?s:.*)second(?s:.*)third")) {
            TrinoRegexp generated = generated(expression);
            assertThat(generated.getClass()).as(expression).isEqualTo(TrinoRegexp.compile(utf8Slice(expression)).getClass());
            assertNoGeneratedCode(expression, generated);
        }
        assertThat(generated("first(?s:.*)second(?s:.*)third").usesOrderedLiteralMatcherForDiagnostics()).isTrue();
    }

    private static void assertNoGeneratedCode(String expression, TrinoRegexp regexp)
    {
        assertThat(regexp.usesGeneratedBytecode()).as(expression).isFalse();
        assertThat(regexp.attemptHandleForDiagnostics()).as(expression).isNull();
        List<Optional<MethodHandle>> handles = new ArrayList<>(List.of(
                regexp.generatedContains(),
                regexp.generatedCount(),
                regexp.generatedPosition(),
                regexp.generatedSplit(),
                regexp.generatedReplace(),
                regexp.generatedReplaceWithCallback()));
        for (int group = 0; group <= regexp.capturingGroupCount(); group++) {
            handles.add(regexp.generatedExtract(group));
            handles.add(regexp.generatedExtractAll(group));
        }
        assertThat(handles).as(expression).allSatisfy(handle -> assertThat(handle).isEmpty());
    }

    /**
     * Each pattern exercises one executor route of {@link #ROUTES} or one operation shape:
     * literal widths on either side of each comparison, a literal longer than the inline
     * comparisons, optional literals of each comparison, delimited captures with and without a
     * minimum, both tails, END with and without the final line terminator, capture-free boolean
     * tails, saves, forks that clear skipped captures, retries late in the plan, and the range,
     * byte-set, and nibble-table candidate scans of a leading run, on views and exact-length
     * copies. In this package, the generated Trino and RE2 classes and the helpers they call
     * invoke only {@link #GENERATED_CODE_CALLS}.
     */
    @Test
    public void testEveryOperationShape()
            throws Throwable
    {
        // Thirteen pairs take more symbols than a random source holds, so these sources reach the
        // late optional retries; the last puts a candidate past the vector width of each
        // byte-set candidate scan.
        String lateRetries = "^" + "[ab]{1}:".repeat(13) + "(?:w)?(?:x)?(?:y)?(?:z)?([w-z]+):$";
        List<Slice> longSources = List.of(
                view("a:".repeat(13) + "wxyz:"),
                view("b:".repeat(13) + "z:\n"),
                view(concat(("a:".repeat(13) + "wx").getBytes(UTF_8), MALFORMED_SEQUENCES.getFirst(), "z:".getBytes(UTF_8))),
                view("-".repeat(100) + "c12!"));
        List<String> expressions = List.of(
                URL,
                "^https?://[^/]+/",
                "^(a)",
                "^(😀)$",
                "^a([^/]+)/$",
                "^ab([^/]+)/xy$",
                "^abc([^/]+)/xyz\\z",
                "^abcd([0-9]+)wxyz$",
                "^abcdefg([a-z]+)/$",
                "^abcdefgh([^/]+)/stuvwxyz$",
                "^abcdefghijk([^/]+)/pqrstuvwxyz$",
                "^abcdefghijklmnop([^/]+)/$",
                "^abcdefghijklmnopq([^/]+)/klmnopqrstuvwxyzé$",
                "^x(?:ab)?(?:abcdefghij)?(?:abcdefghijklmnopqrs)?([a-z]+)!$",
                "^([^/]*)/([^:]+):(.*)$",
                "(?s)^([^/]*)/([^:]+):.*$",
                "^([^/]+)/.*\\z",
                "^([^/]+)/(?:x)?$",
                "^x([a-z]*)",
                "^x(?:ab)?([0-9]+)",
                "^(?:([a-z]+):)?end$",
                "^(?:([a-z]+):)?(?:([0-9]+);)?([a-z]+)$",
                "^x(?:abc)?(?:abcd)?(?:abcdef)?(?:abcdefgh)?([a-z]+)!$",
                OPTIONAL_RETRIES,
                lateRetries,
                "abcdefghijklmnopq([^/]+)/",
                "x([a-z]+)!",
                "x(?:ab)?([a-z]*)",
                "x([0-9]+)$",
                "x([0-9]+)\\z",
                "x(?:([a-z]+):)?([0-9]+)!",
                "[a-z]([0-9]*)!",
                "[abc]([0-9]*)!",
                "\\w([0-9]*)!",
                "é([a-z]+)😀",
                "[a-z]([a-z0-9]*)!",
                "([^/]+)/([^!]+)!x",
                "[?&]([^=]+)=");
        Set<String> kinds = new LinkedHashSet<>();
        Set<String> invoked = new LinkedHashSet<>();
        invoked.addAll(invokedMethods(classfile(TrinoScanKernelSupport.class)));
        invoked.addAll(invokedMethods(classfile(VectorTrinoScanKernelSupport.class)));
        int index = 0;
        for (String expression : expressions) {
            TrinoRegexp generated = generated(expression);
            assertThat(generated.usesGeneratedBytecode()).as(expression).isTrue();
            invoked.addAll(invokedMethods(TrinoScanKernelGenerator.classfileForDiagnostics(TrinoScanKernelGenerator.Routes.of(generated.pattern()))));
            kinds.addAll(List.of(generated.pattern().caseSensitiveTrinoScanPlan().operationsForDiagnostics().split(",")));
            List<String> symbols = new ArrayList<>(SYMBOLS);
            symbols.addAll(witnessParts(expression));
            List<Slice> sources = new ArrayList<>(randomSources(200 + index++, symbols, MALFORMED_SEQUENCES));
            sources.addAll(longSources);
            // An exact-length copy has no readable byte past its end.
            sources.addAll(sources.stream().map(source -> wrappedBuffer(source.getBytes())).toList());
            verifySameOperations(expression, generated, TrinoRegexp.compile(utf8Slice(expression)), sources, true);
        }
        assertThat(kinds).contains("LITERAL", "OPTIONAL", "DELIMITED_CAPTURE", "DOT_ALL_TAIL", "LINE_TAIL", "END", "RUN", "SAVE", "FORK");
        // The RE2 executor class of each attempt kind, whose run steps read characters as RE2 does.
        // TestRe2ScanPlan compares their results with the ordinary engine.
        Set<String> re2Kinds = new LinkedHashSet<>();
        for (String expression : List.of("^https?://([^/]+)/.*$", "^https?://([^/]+)/", "^x([^/]+)", "x([^/]+)", "([^:]+):")) {
            Re2 re2 = Re2.compile(utf8Slice(expression), Re2.Options.defaults().setGenerateBytecode(true));
            assertThat(re2.usesGeneratedBytecode()).as(expression).isTrue();
            boolean ordinaryHandoff = re2.usesTrinoScanPlanForFindForDiagnostics() && re2.usesOrdinaryUnanchoredBooleanSearchForDiagnostics();
            invoked.addAll(invokedMethods(TrinoScanKernelGenerator.re2ClassfileForDiagnostics(re2.caseSensitiveTrinoScanPlan(), ordinaryHandoff)));
            re2Kinds.addAll(List.of(re2.caseSensitiveTrinoScanPlan().operationsForDiagnostics().split(",")));
        }
        assertThat(re2Kinds).contains("RE2_RUN", "RE2_DELIMITED_CAPTURE");
        assertThat(leadingRun("[a-z]([0-9]*)!").candidateScan()).isEqualTo(TrinoScanPlanRun.CANDIDATE_SCAN_RANGE);
        TrinoScanPlanRun threeBytes = leadingRun("[abc]([0-9]*)!");
        assertThat(threeBytes.candidateScan()).isEqualTo(TrinoScanPlanRun.CANDIDATE_SCAN_SMALL_SET);
        assertThat(threeBytes.needles()).hasSize(3);
        assertThat(leadingRun("\\w([0-9]*)!").candidateScan()).isEqualTo(TrinoScanPlanRun.CANDIDATE_SCAN_NIBBLE_TABLE);
        assertThat(invoked.stream().filter(method -> !method.startsWith("java.") && !method.startsWith("jdk.") && !method.startsWith("io.airlift.slice.")))
                .containsExactlyInAnyOrderElementsOf(GENERATED_CODE_CALLS);
    }

    private static TrinoScanPlanRun leadingRun(String expression)
    {
        TrinoScanPlan plan = TrinoRegexp.compile(utf8Slice(expression)).pattern().caseSensitiveTrinoScanPlan();
        return plan.step(plan.leadingOperation()).run();
    }

    /**
     * A caller-supplied distribution and a preset move the probe of a leading literal off its
     * first byte. The generated candidate scan probes the byte the interpreter probes, so it
     * finds the same candidates and hands off where the interpreter does.
     */
    @Test
    public void testLiteralProbeAgainstInterpreter()
            throws Throwable
    {
        record ProbeCase(String expression, ByteFrequencies frequencies, int probe) {}

        List<ProbeCase> cases = List.of(
                new ProbeCase("https://(?:www\\.)?([^/]+)/", TestingByteFrequencies.rareNonLetters(), 5),
                new ProbeCase("user=(\\w*)", ByteFrequencies.englishText(), 4));
        for (ProbeCase probeCase : cases) {
            String expression = probeCase.expression();
            TrinoRegexp interpreted = TrinoRegexp.compile(utf8Slice(expression), TrinoRegexp.Options.defaults().setByteFrequencies(probeCase.frequencies()));
            TrinoRegexp compiled = TrinoRegexp.compile(utf8Slice(expression), TrinoRegexp.Options.defaults().setByteFrequencies(probeCase.frequencies()).setGenerateBytecode(true));
            assertThat(interpreted.pattern().caseSensitiveTrinoScanPlan().leadingLiteralProbeOffsetForDiagnostics()).as(expression).isEqualTo(probeCase.probe());
            assertThat(compiled.usesGeneratedBytecode()).as(expression).isTrue();
            byte[] classfile = TrinoScanKernelGenerator.classfileForDiagnostics(TrinoScanKernelGenerator.Routes.of(compiled.pattern()));
            assertThat(probeOffsets(classfile)).as(expression).isNotEmpty().containsOnly(probeCase.probe());
            List<String> symbols = new ArrayList<>(SYMBOLS);
            symbols.addAll(witnessParts(expression));
            verifySameOperations(expression, compiled, interpreted, randomSources(expression.length(), symbols, MALFORMED_SEQUENCES), true);
        }
    }

    /**
     * Compares generated and interpreted patterns over the seeded patterns of
     * {@link TestTrinoScanPlan}: end-anchored, partial, compacted, and unanchored shapes, on their
     * witnesses, mutations, random text, and malformed UTF-8.
     */
    @Test
    public void testGeneratedPatternsAgainstInterpreter()
            throws Throwable
    {
        int compared = 0;
        for (long seed : new long[] {139, 163}) {
            Random random = new Random(seed);
            for (int iteration = 0; iteration < 120; iteration++) {
                PatternWitness generated = generatedSequence(random, 0);
                String flags = random.nextBoolean() ? "(?s)" : "";
                String witness = "x" + generated.text() + "!";
                List<String> expressions = List.of(
                        flags + "^x(" + generated.pattern() + ")!" + (random.nextBoolean() ? "$" : "\\z"),
                        flags + "^x(" + generated.pattern() + ")",
                        flags + "^x" + generated.pattern() + "([^/]*)/.*$",
                        flags + "x(" + generated.pattern() + ")!" + (random.nextBoolean() ? "$" : ""));
                for (String expression : expressions) {
                    TrinoRegexp interpreted = TrinoRegexp.compile(utf8Slice(expression));
                    TrinoRegexp compiled = generated(expression);
                    boolean eligible = interpreted.getClass() == TrinoRegexp.class && TrinoScanKernelGenerator.Routes.of(interpreted.pattern()) != null;
                    assertThat(compiled.usesGeneratedBytecode()).as(expression).isEqualTo(eligible);
                    if (!eligible) {
                        continue;
                    }
                    compared++;
                    List<Slice> sources = new ArrayList<>();
                    for (String text : mutations(witness, generated.text())) {
                        sources.add(view(text));
                    }
                    sources.addAll(randomSources(seed * 1000 + iteration, List.of("x", "!", "/", "a", "b", "1", "é", "😀", "\n", generated.text()), MALFORMED_SEQUENCES).subList(0, 40));
                    verifySameOperations(expression, compiled, interpreted, sources, true);
                }
            }
        }
        assertThat(compared).isGreaterThan(300);
    }

    /**
     * A generated search that hands off to the ordinary engine gives the interpreter's results,
     * including for a leading literal whose probe the supplied distribution moves off its first
     * byte.
     */
    @Test
    public void testForcedHandoffWithGeneratedCode()
            throws Throwable
    {
        for (String expression : List.of("[?&]([^=]+)=", "([^/]+)/([^!]+)!x", "x(?:([a-z]+):)?([0-9]+)!", "abcdefghijklmnopq([^/]+)/", "https://(?:www\\.)?([^/]+)/")) {
            TrinoRegexp.Options options = TrinoRegexp.Options.defaults().setByteFrequencies(TestingByteFrequencies.rareNonLetters());
            TrinoRegexp interpreted = TrinoRegexp.compileForcingScanPlanHandoffForTesting(utf8Slice(expression), options);
            TrinoRegexp compiled = TrinoRegexp.compileForcingScanPlanHandoffForTesting(utf8Slice(expression), options.setGenerateBytecode(true));
            assertThat(compiled.usesGeneratedBytecode()).as(expression).isTrue();
            List<String> symbols = new ArrayList<>(SYMBOLS);
            symbols.addAll(witnessParts(expression));
            verifySameOperations(expression + " after handoff", compiled, interpreted, randomSources(expression.length(), symbols, MALFORMED_SEQUENCES), true);
        }
    }

    /**
     * Repeated failed attempts long enough to cross the density and work-budget limits of the
     * candidate search, with malformed UTF-8 before the failure, hand off where the interpreter
     * does.
     */
    @Test
    public void testLongSourcesHandOffWithInterpreter()
            throws Throwable
    {
        for (QuadraticSearch search : QUADRATIC_SEARCHES) {
            String expression = search.expression();
            TrinoRegexp compiled = generated(expression);
            TrinoRegexp interpreted = TrinoRegexp.compile(utf8Slice(expression));
            if (interpreted.pattern().usesAsciiFoldedTrinoScanExecutorForDiagnostics()) {
                assertThat(compiled.usesGeneratedBytecode()).as(expression).isFalse();
                continue;
            }
            assertThat(compiled.usesGeneratedBytecode()).as(expression).isTrue();
            List<Slice> sources = new ArrayList<>();
            for (int units : new int[] {8, 70, 300, 1500}) {
                sources.add(search.input(units));
                for (byte[] malformed : MALFORMED_SEQUENCES) {
                    sources.add(view(concat(search.unit().repeat(units).getBytes(UTF_8), malformed, (search.failure() + search.matches()).getBytes(UTF_8))));
                }
            }
            verifySameOperations(expression + " on long sources", compiled, interpreted, sources, false);
        }
    }

    static byte[] classfile(Class<?> type)
    {
        try (InputStream input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return requireNonNull(input, type.getName()).readAllBytes();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns each method of another class that {@code classfile} invokes, as its owner's binary
     * name, without the package for an owner in this package, a dot, and the method name.
     */
    static Set<String> invokedMethods(byte[] classfile)
    {
        String packagePrefix = TestTrinoScanKernel.class.getPackageName() + ".";
        ClassModel model = ClassFile.of().parse(classfile);
        Set<String> methods = new LinkedHashSet<>();
        for (MethodModel method : model.methods()) {
            method.code().ifPresent(code -> code.elementStream()
                    .filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast)
                    .filter(invoke -> !invoke.owner().equals(model.thisClass()))
                    .forEach(invoke -> {
                        String owner = invoke.owner().asInternalName().replace('/', '.');
                        if (owner.startsWith(packagePrefix)) {
                            owner = owner.substring(packagePrefix.length());
                        }
                        methods.add(owner + "." + invoke.name().stringValue());
                    }));
        }
        return methods;
    }

    /**
     * Returns the constant probe offset passed to each probing whole-literal scan in
     * {@code classfile}.
     */
    private static List<Object> probeOffsets(byte[] classfile)
    {
        List<Object> offsets = new ArrayList<>();
        for (MethodModel method : ClassFile.of().parse(classfile).methods()) {
            CodeModel code = method.code().orElse(null);
            if (code == null) {
                continue;
            }
            CodeElement previous = null;
            for (CodeElement element : code) {
                if (element instanceof InvokeInstruction invoke && invoke.name().equalsString("findLiteralAtProbe")) {
                    offsets.add(previous instanceof ConstantInstruction constant ? constant.constantValue() : previous);
                }
                previous = element;
            }
        }
        return offsets;
    }

    private static TrinoRegexp generated(String expression)
    {
        return TrinoRegexp.compile(utf8Slice(expression), TrinoRegexp.Options.defaults().setGenerateBytecode(true));
    }

    /**
     * Returns literal pieces of {@code expression} that random sources combine into matches.
     */
    private static List<String> witnessParts(String expression)
    {
        List<String> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (int index = 0; index < expression.length(); index++) {
            char value = expression.charAt(index);
            if (Character.isLetterOrDigit(value) || value > 0x7F || "/:=&?!".indexOf(value) >= 0) {
                literal.append(value);
            }
            else if (!literal.isEmpty()) {
                parts.add(literal.toString());
                literal.setLength(0);
            }
        }
        if (!literal.isEmpty()) {
            parts.add(literal.toString());
        }
        return parts;
    }

    private static List<String> mutations(String witness, String body)
    {
        int removed = witness.offsetByCodePoints(0, Math.min(2, witness.codePointCount(0, witness.length())));
        return List.of(
                witness,
                witness + "\n",
                witness + "\n\n",
                witness.substring(0, witness.length() - 1),
                witness.substring(0, witness.length() - 1) + "?",
                witness.substring(0, 1) + witness.substring(removed),
                "x\n" + body + "!",
                "lead" + witness + " " + witness,
                witness + "rest/more\n",
                "x!x" + witness,
                "x!",
                "");
    }

    private static byte[] concat(byte[]... parts)
    {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static Slice view(String value)
    {
        return view(value.getBytes(UTF_8));
    }

    /**
     * Returns a view of {@code text} at a non-zero offset of a larger array.
     */
    private static Slice view(byte[] text)
    {
        byte[] bytes = new byte[text.length + 6];
        System.arraycopy(text, 0, bytes, 3, text.length);
        bytes[0] = 'x';
        bytes[bytes.length - 1] = '!';
        return wrappedBuffer(bytes, 3, text.length);
    }

    /**
     * Requires every public operation, every generated handle {@code actual} has, and its
     * matchers with no groups and with every group, to give exactly the result of
     * {@code expected} on each source. Positions start at the first two code points and at the
     * end, and replacement templates reference no group, the first, and all of them. With
     * {@code attempts}, the generated attempt must also give the interpreter's raw result at
     * every start.
     */
    private static void verifySameOperations(String description, TrinoRegexp actual, TrinoRegexp expected, List<Slice> sources, boolean attempts)
            throws Throwable
    {
        int groupCount = expected.capturingGroupCount();
        assertThat(actual.capturingGroupCount()).isEqualTo(groupCount);
        StringBuilder allGroups = new StringBuilder("<");
        for (int group = 0; group <= groupCount; group++) {
            allGroups.append('$').append(group).append('|');
        }
        List<Slice> replacements = List.of(utf8Slice("<>"), utf8Slice(groupCount == 0 ? "<$0>" : "<$1>"), utf8Slice(allGroups.append('>').toString()));
        Handles handles = new Handles(actual);
        for (Slice source : sources) {
            String sourceDescription = description + " on " + HexFormat.of().formatHex(source.getBytes());
            assertThat(actual.contains(source)).as(sourceDescription).isEqualTo(expected.contains(source));
            long count = expected.count(source);
            assertThat(actual.count(source)).as(sourceDescription).isEqualTo(count);
            for (long start : positionStarts(source)) {
                for (long occurrence = 1; occurrence <= count + 1; occurrence++) {
                    assertThat(actual.position(source, start, occurrence)).as(sourceDescription).isEqualTo(expected.position(source, start, occurrence));
                }
            }
            for (int group = 0; group <= groupCount; group++) {
                assertThat(actual.extract(source, group)).as(sourceDescription).isEqualTo(expected.extract(source, group));
                assertThat(actual.extractAll(source, group)).as(sourceDescription).isEqualTo(expected.extractAll(source, group));
            }
            assertThat(actual.split(source)).as(sourceDescription).isEqualTo(expected.split(source));
            for (Slice replacement : replacements) {
                assertThat(actual.replace(source, replacement)).as(sourceDescription).isEqualTo(expected.replace(source, replacement));
            }
            for (Function<List<Slice>, Slice> callback : CALLBACKS) {
                assertThat(replaceOutcome(() -> actual.replace(source, callback))).as(sourceDescription).isEqualTo(replaceOutcome(() -> expected.replace(source, callback)));
            }
            for (int retained : new int[] {0, groupCount}) {
                assertThat(matcherTrace(actual, retained, source)).as(sourceDescription).containsExactlyElementsOf(matcherTrace(expected, retained, source));
            }
            handles.verify(sourceDescription, expected, source, count, replacements);
            if (attempts) {
                verifySameAttempts(sourceDescription, actual.attemptHandleForDiagnostics(), expected.pattern().caseSensitiveTrinoScanPlan(), source, groupCount);
            }
        }
    }

    private static long[] positionStarts(Slice source)
    {
        return new long[] {1, 2, source.length() + 1L};
    }

    private interface Replacement
    {
        Slice replace()
                throws Throwable;
    }

    /**
     * Returns the hex of what {@code replacement} returns, or the class and message of what it
     * throws.
     */
    private static String replaceOutcome(Replacement replacement)
    {
        try {
            Slice result = replacement.replace();
            return result == null ? "null" : HexFormat.of().formatHex(result.getBytes());
        }
        catch (Throwable throwable) {
            return throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
    }

    /**
     * The generated handles of one pattern, each compared on every source with the public
     * operation of the interpreted pattern.
     */
    private static final class Handles
    {
        private final MethodHandle contains;
        private final MethodHandle count;
        private final MethodHandle position;
        private final List<MethodHandle> extract = new ArrayList<>();
        private final List<MethodHandle> extractAll = new ArrayList<>();
        private final MethodHandle split;
        private final MethodHandle replace;
        private final MethodHandle replaceWithCallback;

        Handles(TrinoRegexp regexp)
        {
            contains = regexp.generatedContains().orElse(null);
            count = regexp.generatedCount().orElse(null);
            position = regexp.generatedPosition().orElse(null);
            for (int group = 0; group <= regexp.capturingGroupCount(); group++) {
                extract.add(regexp.generatedExtract(group).orElse(null));
                extractAll.add(regexp.generatedExtractAll(group).orElse(null));
            }
            split = regexp.generatedSplit().orElse(null);
            replace = regexp.generatedReplace().orElse(null);
            replaceWithCallback = regexp.generatedReplaceWithCallback().orElse(null);
        }

        void verify(String description, TrinoRegexp expected, Slice source, long matches, List<Slice> replacements)
                throws Throwable
        {
            if (contains != null) {
                assertThat((boolean) contains.invokeExact(source)).as(description).isEqualTo(expected.contains(source));
            }
            if (count != null) {
                assertThat((long) count.invokeExact(source)).as(description).isEqualTo(matches);
            }
            if (position != null) {
                for (long start : positionStarts(source)) {
                    for (long occurrence = 1; occurrence <= matches + 1; occurrence++) {
                        assertThat((long) position.invokeExact(source, start, occurrence)).as(description).isEqualTo(expected.position(source, start, occurrence));
                    }
                }
            }
            for (int group = 0; group < extract.size(); group++) {
                if (extract.get(group) != null) {
                    assertThat((Slice) extract.get(group).invokeExact(source)).as(description).isEqualTo(expected.extract(source, group));
                }
                if (extractAll.get(group) != null) {
                    assertThat((List<?>) extractAll.get(group).invokeExact(source)).as(description).isEqualTo(expected.extractAll(source, group));
                }
            }
            if (split != null) {
                assertThat((List<?>) split.invokeExact(source)).as(description).isEqualTo(expected.split(source));
            }
            if (replace != null) {
                for (Slice replacement : replacements) {
                    assertThat((Slice) replace.invokeExact(source, replacement)).as(description).isEqualTo(expected.replace(source, replacement));
                }
            }
            if (replaceWithCallback != null) {
                for (Function<List<Slice>, Slice> callback : CALLBACKS) {
                    assertThat(replaceOutcome(() -> (Slice) replaceWithCallback.invokeExact(source, callback))).as(description).isEqualTo(replaceOutcome(() -> expected.replace(source, callback)));
                }
            }
        }
    }

    /**
     * Returns what a matcher retaining {@code retained} groups reports: each group's start, end,
     * and text, or the exception it throws, before the first find, after each find, after the
     * last, and again after a reset.
     */
    private static List<String> matcherTrace(TrinoRegexp regexp, int retained, Slice source)
    {
        TrinoRegexpMatcher matcher = regexp.matcher(source, retained);
        List<String> trace = new ArrayList<>();
        trace.add("groupCount " + matcher.groupCount());
        traceGroups(trace, matcher, retained);
        for (int round = 0; round < 2; round++) {
            while (true) {
                boolean found = matcher.find();
                trace.add("find " + found);
                traceGroups(trace, matcher, retained);
                if (!found) {
                    break;
                }
            }
            trace.add("find after end " + matcher.find());
            matcher.reset(source);
            trace.add("reset");
        }
        return trace;
    }

    private static void traceGroups(List<String> trace, TrinoRegexpMatcher matcher, int retained)
    {
        for (int group = -1; group <= retained + 1; group++) {
            int index = group;
            trace.add(group + " matched " + outcome(() -> matcher.matched(index)));
            trace.add(group + " start " + outcome(() -> matcher.start(index)));
            trace.add(group + " end " + outcome(() -> matcher.end(index)));
            trace.add(group + " group " + outcome(() -> {
                Slice value = matcher.group(index);
                return value == null ? null : HexFormat.of().formatHex(value.getBytes());
            }));
        }
        trace.add("start " + outcome(matcher::start));
        trace.add("end " + outcome(matcher::end));
        trace.add("group " + outcome(() -> matcher.group() == null ? null : HexFormat.of().formatHex(matcher.group().getBytes())));
    }

    private static String outcome(Callable<?> call)
    {
        try {
            return String.valueOf(call.call());
        }
        catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Compares raw attempt results, which the public operations partly normalize: the boolean
     * or encoded attempt result, and every capture slot, including slots the attempt leaves
     * untouched and slots past a short group array.
     */
    private static void verifySameAttempts(String description, MethodHandle actual, TrinoScanPlan expected, Slice source, int groupCount)
            throws Throwable
    {
        int length = source.length();
        byte[] bytes = source.byteArray();
        int offset = source.byteArrayOffset();
        for (int groupsLength : new int[] {-1, 2, 4, 2 * (groupCount + 1), 2 * (groupCount + 2)}) {
            for (boolean fullMatch : new boolean[] {false, true}) {
                if (!expected.isAnchoredStart()) {
                    for (int start = 0; start <= length; start++) {
                        int[] actualGroups = groups(groupsLength);
                        int[] expectedGroups = groups(groupsLength);
                        assertThat((long) actual.invokeExact(bytes, offset, offset + length, offset + start, fullMatch, actualGroups))
                                .as("%s, attempt at %s", description, start)
                                .isEqualTo(expected.unanchoredAttemptForDiagnostics(source, start, fullMatch, expectedGroups));
                        assertThat(actualGroups).as("%s, attempt at %s", description, start).isEqualTo(expectedGroups);
                    }
                    continue;
                }
                for (int[] context : new int[][] {{0, length}, {Math.min(1, length), length}, {0, Math.max(0, length - 1)}}) {
                    int[] actualGroups = groups(groupsLength);
                    int[] expectedGroups = groups(groupsLength);
                    boolean matched = expected.isPartialMatch()
                            ? expected.matchPartial(source, context[0], context[1], fullMatch, expectedGroups)
                            : expected.matchEndAnchored(source, context[0], context[1], fullMatch, expectedGroups);
                    assertThat((boolean) actual.invokeExact(source, context[0], context[1], fullMatch, actualGroups))
                            .as("%s, context [%s, %s)", description, context[0], context[1])
                            .isEqualTo(matched);
                    assertThat(actualGroups).as("%s, context [%s, %s)", description, context[0], context[1]).isEqualTo(expectedGroups);
                }
            }
        }
    }

    /**
     * Returns null for a negative length, or an array whose slots start at a value no attempt
     * writes, so a slot an attempt leaves untouched is compared too.
     */
    private static int[] groups(int length)
    {
        if (length < 0) {
            return null;
        }
        int[] groups = new int[length];
        Arrays.fill(groups, 99);
        return groups;
    }
}
