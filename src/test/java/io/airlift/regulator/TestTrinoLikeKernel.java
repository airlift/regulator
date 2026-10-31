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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.airlift.regulator.LiteralSearchKernel.CANDIDATE_COMPARISON_BUDGET;
import static io.airlift.regulator.TestScanKernel.classfile;
import static io.airlift.regulator.TestScanKernel.invokedMethods;
import static io.airlift.regulator.TrinoLikeKernelGenerator.HUGE_METHOD_LIMIT;
import static io.airlift.regulator.TrinoLikePattern.AARCH64;
import static io.airlift.regulator.TrinoLikePattern.Plan.CONTAINS;
import static io.airlift.regulator.TrinoLikePattern.Plan.EMPTY;
import static io.airlift.regulator.TrinoLikePattern.Plan.ENDS_WITH;
import static io.airlift.regulator.TrinoLikePattern.Plan.EQUALS;
import static io.airlift.regulator.TrinoLikePattern.Plan.LITERAL_GAPS;
import static io.airlift.regulator.TrinoLikePattern.Plan.ORDERED_LITERALS;
import static io.airlift.regulator.TrinoLikePattern.Plan.STARTS_WITH;
import static io.airlift.regulator.TrinoLikePattern.Plan.TRINO_WILDCARD;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTrinoLikeKernel
{
    private static final TrinoLikePattern.Options GENERATED = TrinoLikePattern.Options.defaults().setGenerateBytecode(true);
    private static final MethodType MATCHES = MethodType.methodType(boolean.class, Slice.class);
    // Every method of another class in this package that generated classes and their helpers
    // call. Generated code calls copies of the interpreter's helpers, because C2 compiles a
    // shared helper from the generated callers' profiles and the interpreter can then no longer
    // inline it. Methods outside this package belong to Slice or the JDK.
    private static final Set<String> GENERATED_CODE_CALLS = Set.of(
            "TrinoLikeKernelSupport.characterWidth",
            "TrinoLikeKernelSupport.findKmp",
            "TrinoLikeKernelSupport.matchingPrefixLength",
            "TrinoLikeKernelSupport.nonAsciiCodePoint",
            "TrinoLikeKernelSupport.readInt",
            "TrinoLikeKernelSupport.readLong",
            "TrinoLikeKernelSupport.readShort");
    private static final List<String> SYMBOLS = List.of("a", "b", "c", "x", "_", "%", "é", "€", "😀", "\u0000");
    private static final List<byte[]> MALFORMED_SEQUENCES = List.of(
            bytes(0xFF),
            bytes(0x80),
            bytes(0xC0, 0xAF),
            bytes(0xC2),
            bytes(0xE2, 0x82),
            bytes(0xED, 0xA0, 0x80),
            bytes(0xF0, 0x9F, 0x98));

    /// Patterns of every plan, with literals on either side of each inline comparison width, a
    /// literal longer than the inline comparisons, contained literals of one and of several bytes,
    /// gaps short enough to unroll and longer ones, and wildcards with and without non-ASCII code
    /// points. Each runs on a generated class whose handle has the type and result of
    /// [TrinoLikePattern#matches(Slice)], and the same pattern compiled without the option
    /// keeps no generated code. In this package, the generated classes and the helpers they call
    /// invoke only [#GENERATED_CODE_CALLS]. Each runs with both emitter variants, and each
    /// search of a literal of two or more bytes verifies its candidates in a separate method and
    /// reads its broadcasts from fields only as on aarch64.
    @Test
    public void testEveryPlanShape()
            throws Throwable
    {
        record Shape(String pattern, TrinoLikePattern.Plan plan) {}

        List<Shape> shapes = List.of(
                new Shape("", EMPTY),
                new Shape("%", CONTAINS),
                new Shape("a", EQUALS),
                new Shape("ab", EQUALS),
                new Shape("abc", EQUALS),
                new Shape("abcdefg", EQUALS),
                new Shape("abcdefgh", EQUALS),
                new Shape("abcdefghijklmnop", EQUALS),
                new Shape("abcdefghijklmnopq", EQUALS),
                new Shape("é😀", EQUALS),
                new Shape("ab%", STARTS_WITH),
                new Shape("abcdefghijk%", STARTS_WITH),
                new Shape("abcdefghijklmnopqrs%", STARTS_WITH),
                new Shape("%x", ENDS_WITH),
                new Shape("%xyzw", ENDS_WITH),
                new Shape("%abcdefghijklmnopqrs", ENDS_WITH),
                new Shape("%x%", CONTAINS),
                new Shape("%needle%", CONTAINS),
                new Shape("%abcdefghijklmnopqrs%", CONTAINS),
                new Shape("a%b%c", CONTAINS),
                new Shape("a%b", ORDERED_LITERALS),
                new Shape("%a%bc%x😀%", ORDERED_LITERALS),
                new Shape("ab%abcdefghijklmnopqrs%cd%", ORDERED_LITERALS),
                new Shape("%ab_c%", LITERAL_GAPS),
                new Shape("%ab__é_😀%", LITERAL_GAPS),
                new Shape("%ab_____cd%", LITERAL_GAPS),
                new Shape("%ab__________cd______é%", LITERAL_GAPS),
                new Shape("_", TRINO_WILDCARD),
                new Shape("____", TRINO_WILDCARD),
                new Shape("_____", TRINO_WILDCARD),
                new Shape("a_b", TRINO_WILDCARD),
                new Shape("%a%_b%", TRINO_WILDCARD),
                new Shape("_%é_😀%", TRINO_WILDCARD),
                new Shape("x%a_%b_c", TRINO_WILDCARD));
        Set<String> invoked = new LinkedHashSet<>(invokedMethods(classfile(TrinoLikeKernelSupport.class)));
        Random random = new Random(20261016);
        int splitSearches = 0;
        for (Shape shape : shapes) {
            String pattern = shape.pattern();
            TrinoLikePattern interpreted = TrinoLikePattern.compile(utf8Slice(pattern));
            assertThat(interpreted.planForDiagnostics()).as(pattern).isEqualTo(shape.plan());
            assertThat(interpreted.usesGeneratedBytecode()).as(pattern).isFalse();
            assertThat(interpreted.generatedMatches()).as(pattern).isEmpty();

            List<Slice> sources = sources(random, pattern, 200);
            for (boolean aarch64 : new boolean[] {false, true}) {
                TrinoLikePattern.Options options = TrinoLikePattern.Options.defaults().setGenerateBytecode(true).setAarch64ForTesting(aarch64);
                TrinoLikePattern generated = TrinoLikePattern.compile(utf8Slice(pattern), options);
                assertThat(generated.usesGeneratedBytecode()).as(pattern).isTrue();
                assertThat(generated.pattern()).as(pattern).isEqualTo(utf8Slice(pattern));
                MethodHandle handle = generated.generatedMatches().orElseThrow();
                assertThat(handle.type()).as(pattern).isEqualTo(MATCHES);
                byte[] classfile = TrinoLikeKernelGenerator.classfileForDiagnostics(generated.kernelForDiagnostics(), aarch64);
                invoked.addAll(invokedMethods(classfile));
                int patternSplitSearches = assertSplitSearches(pattern, classfile, generated.kernelForDiagnostics(), aarch64);
                splitSearches += patternSplitSearches;
                // The defined class declares broadcast fields exactly when the options ask for
                // aarch64 searches.
                Field[] fields = MethodHandles.reflectAs(Method.class, handle).getDeclaringClass().getDeclaredFields();
                assertThat(Arrays.stream(fields).anyMatch(field -> field.getName().startsWith("broadcast")))
                        .as("%s aarch64=%s", pattern, aarch64)
                        .isEqualTo(aarch64 && patternSplitSearches > 0);
                verifySameMatches(pattern, generated, interpreted, sources);
            }
        }
        assertThat(splitSearches).isPositive();
        assertThat(invoked.stream().filter(method -> !method.startsWith("java.") && !method.startsWith("jdk.") && !method.startsWith("io.airlift.slice.")))
                .containsExactlyInAnyOrderElementsOf(GENERATED_CODE_CALLS);
    }

    /// A generated pattern rejects a `null` input as the interpreter does; its handle checks
    /// nothing more than the input it reads.
    @Test
    public void testNullInput()
    {
        TrinoLikePattern generated = TrinoLikePattern.compile(utf8Slice("%a_b%"), GENERATED);
        assertThatThrownBy(() -> generated.matches(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("input is null");
        MethodHandle handle = generated.generatedMatches().orElseThrow();
        assertThatThrownBy(() -> {
            boolean _ = (boolean) handle.invokeExact((Slice) null);
        }).isInstanceOf(NullPointerException.class);
    }

    /// A wildcard automaton of more positions than one word holds carries its states between
    /// words. Patterns cross one and two word boundaries, with positions that loop and positions
    /// that match any character on either side of each boundary.
    @Test
    public void testMultiWordWildcard()
            throws Throwable
    {
        Random random = new Random(64);
        for (String pattern : List.of(
                "%" + "ab_%".repeat(22) + "c",
                "_%" + "é_%x".repeat(25) + "%",
                "%" + "a_%b".repeat(33) + "😀_",
                "x" + "_%ab".repeat(45) + "%")) {
            TrinoLikePattern interpreted = TrinoLikePattern.compile(utf8Slice(pattern));
            assertThat(interpreted.planForDiagnostics()).as(pattern).isEqualTo(TRINO_WILDCARD);
            TrinoLikePattern generated = TrinoLikePattern.compile(utf8Slice(pattern), GENERATED);
            assertThat(generated.usesGeneratedBytecode()).as(pattern).isTrue();
            assertThat(((TrinoLikeKernelGenerator.Wildcard) generated.kernelForDiagnostics().region()).positions().length)
                    .as(pattern)
                    .isGreaterThan(Long.SIZE);
            verifySameMatches(pattern, generated, interpreted, sources(random, pattern, 300));
        }
        int[] positions = ((TrinoLikeKernelGenerator.Wildcard) TrinoLikePattern.compile(utf8Slice("x" + "_%ab".repeat(45) + "%")).kernelForDiagnostics().region()).positions();
        assertThat(positions.length).isGreaterThan(2 * Long.SIZE);
    }

    /// A run of the first byte of a contained literal that also ends with that byte makes every
    /// position a candidate that compares several bytes before failing, so the search switches to
    /// KMP and continues from the next position. Generated kernels find the literal where the
    /// interpreter does. The test checks results only, not whether or where the generated copy
    /// switches; TestLiteralSearchKernel checks that for the interpreted copy. The run can overlap
    /// the literal. Each runs with both emitter variants.
    @Test
    public void testCandidateBudgetSwitchesToKmp()
            throws Throwable
    {
        record Search(String pattern, List<String> tails) {}

        String longRun = "a".repeat(38);
        List<Search> searches = List.of(
                new Search("%aaaaaaba%", List.of("", "aaaaaaba", "aaaaaab", "aaa-aaba")),
                new Search("%aaaaaaba%zz", List.of("zz", "aaaaaabazz", "aaaaaabzz", "aaaaaaba")),
                new Search("%aaaaaaaaaaaaaaaaaaba%", List.of("", "aaaaaaaaaaaaaaaaaaba", "aaaaaaaaaaaaaaaaaab")),
                new Search("%" + longRun + "ba%", List.of("", longRun + "ba", "aa" + longRun + "ba", longRun + "b")),
                new Search("%aaaba%aaba%", List.of("", "aaaba", "aaabaaaba", "aaaba-aaba", "aaabaa-ba")),
                new Search("%aaaba_aaba%", List.of("", "aaaba-aaba", "aaabaéaaba", "aaaba-a-ba")));
        for (Search search : searches) {
            String pattern = search.pattern();
            TrinoLikePattern interpreted = TrinoLikePattern.compile(utf8Slice(pattern));
            List<Slice> sources = new ArrayList<>();
            // Each run position compares at least two more bytes than it covers, so the longer
            // runs overrun the budget. For the 40-byte literal, the one-byte run and the
            // "aa" + literal tail make a 43-byte input with four possible starts, fewer than a
            // word, so the scan's one-start-at-a-time tail switches to KMP just before the match.
            for (int length : new int[] {1, 9, CANDIDATE_COMPARISON_BUDGET, 3 * CANDIDATE_COMPARISON_BUDGET}) {
                String run = "a".repeat(length);
                for (String tail : search.tails()) {
                    sources.add(view(run + tail));
                    sources.add(view(tail + run + tail));
                }
            }
            for (boolean aarch64 : new boolean[] {false, true}) {
                TrinoLikePattern.Options options = TrinoLikePattern.Options.defaults().setGenerateBytecode(true).setAarch64ForTesting(aarch64);
                TrinoLikePattern generated = TrinoLikePattern.compile(utf8Slice(pattern), options);
                assertThat(generated.usesGeneratedBytecode()).as(pattern).isTrue();
                verifySameMatches(pattern, generated, interpreted, sources);
            }
        }
    }

    /// A pattern whose class would have a method longer than HotSpot compiles stays interpreted,
    /// while a smaller pattern of the same shape has a class.
    @Test
    public void testHugeMethodFallsBackToInterpreter()
            throws Throwable
    {
        String large = wildcardOfDistinctCodePoints(0x4E00, 1_000);
        TrinoLikePattern interpreted = TrinoLikePattern.compile(utf8Slice(large));
        assertThat(TrinoLikeKernelGenerator.largestMethodBytesForDiagnostics(interpreted.kernelForDiagnostics(), AARCH64)).isGreaterThan(HUGE_METHOD_LIMIT);
        TrinoLikePattern fallback = TrinoLikePattern.compile(utf8Slice(large), GENERATED);
        assertThat(fallback.usesGeneratedBytecode()).isFalse();
        assertThat(fallback.generatedMatches()).isEmpty();
        assertThat(fallback.matches(utf8Slice("x" + large.substring(2)))).isTrue();

        String small = wildcardOfDistinctCodePoints(0x4E00, 200);
        TrinoLikePattern generated = TrinoLikePattern.compile(utf8Slice(small), GENERATED);
        assertThat(generated.usesGeneratedBytecode()).isTrue();
        assertThat(TrinoLikeKernelGenerator.largestMethodBytesForDiagnostics(generated.kernelForDiagnostics(), AARCH64)).isLessThanOrEqualTo(HUGE_METHOD_LIMIT);
        Slice match = view("x" + small.substring(2, small.length() - 1));
        assertThat(generated.matches(match)).isTrue();
        List<Slice> sources = new ArrayList<>(sources(new Random(200), small, 100));
        sources.add(match);
        verifySameMatches(small, generated, TrinoLikePattern.compile(utf8Slice(small)), sources);
    }

    /// The generator builds a wildcard automaton of 500 state words, the most that its size
    /// estimate admits, without exhausting a small thread stack, and the pattern, whose class is
    /// too large, matches as the interpreter does.
    @Test
    public void testLargestBuiltWildcardFitsSmallStack()
            throws InterruptedException
    {
        String pattern = "%" + "_".repeat(500 * Long.SIZE - 1) + "%";
        TrinoLikePattern interpreted = TrinoLikePattern.compile(utf8Slice(pattern));
        assertThat(TrinoLikeKernelGenerator.certainlyExceedsLimitForDiagnostics(interpreted.kernelForDiagnostics())).isFalse();
        String nextWord = "%" + "_".repeat(501 * Long.SIZE - 1) + "%";
        assertThat(TrinoLikeKernelGenerator.certainlyExceedsLimitForDiagnostics(TrinoLikePattern.compile(utf8Slice(nextWord)).kernelForDiagnostics())).isTrue();
        AtomicReference<Object> compiled = new AtomicReference<>();
        AtomicReference<Integer> methodBytes = new AtomicReference<>();
        Thread thread = new Thread(
                null,
                () -> {
                    try {
                        TrinoLikePattern generated = TrinoLikePattern.compile(utf8Slice(pattern), GENERATED);
                        methodBytes.set(TrinoLikeKernelGenerator.largestMethodBytesForDiagnostics(generated.kernelForDiagnostics(), AARCH64));
                        compiled.set(generated);
                    }
                    catch (Throwable e) {
                        compiled.set(e);
                    }
                },
                "small-stack",
                256 * 1024);
        thread.start();
        thread.join();
        assertThat(compiled.get()).isInstanceOf(TrinoLikePattern.class);
        assertThat(methodBytes.get()).isGreaterThan(HUGE_METHOD_LIMIT);
        TrinoLikePattern generated = (TrinoLikePattern) compiled.get();
        assertThat(generated.planForDiagnostics()).isEqualTo(TRINO_WILDCARD);
        assertThat(generated.usesGeneratedBytecode()).isFalse();
        for (String input : List.of("x".repeat(pattern.length() - 3), "x".repeat(pattern.length() - 2), "é".repeat(pattern.length() - 2))) {
            assertThat(generated.matches(utf8Slice(input))).isEqualTo(interpreted.matches(utf8Slice(input)));
        }
    }

    /// A wildcard of more distinct code points than its class could hold, whose masks would take
    /// a word per state for each code point, stays interpreted and matches as the interpreter does.
    @Test
    public void testOversizedWildcardStaysInterpreted()
    {
        String pattern = wildcardOfDistinctCodePoints(0x20000, 40_000);
        TrinoLikePattern generated = TrinoLikePattern.compile(utf8Slice(pattern), GENERATED);
        assertThat(generated.planForDiagnostics()).isEqualTo(TRINO_WILDCARD);
        assertThat(generated.usesGeneratedBytecode()).isFalse();
        String literal = pattern.substring(2, pattern.length() - 1);
        assertThat(generated.matches(utf8Slice("x" + literal))).isTrue();
        assertThat(generated.matches(utf8Slice(literal))).isFalse();
        assertThat(generated.matches(utf8Slice("x" + literal.substring(2)))).isFalse();
    }

    /// Returns a wildcard pattern of `count` distinct code points from `first` between
    /// a leading `_%` and a trailing `%`, so each code point selects its own mask.
    private static String wildcardOfDistinctCodePoints(int first, int count)
    {
        StringBuilder pattern = new StringBuilder("_%");
        for (int index = 0; index < count; index++) {
            pattern.appendCodePoint(first + index);
        }
        return pattern.append('%').toString();
    }

    /// Requires each search of a literal of two or more bytes in `classfile` to call the
    /// method that verifies its candidates, and the search and that method to read broadcast fields
    /// exactly when `aarch64` is true, and returns the number of such searches. A one-byte
    /// search has no candidates to verify.
    private static int assertSplitSearches(String pattern, byte[] classfile, TrinoLikeKernelGenerator.Kernel kernel, boolean aarch64)
    {
        ClassModel model = ClassFile.of().parse(classfile);
        List<LiteralSearchKernel> literals = searchedLiterals(kernel);
        int splitSearches = 0;
        for (int index = 0; index < literals.size(); index++) {
            if (literals.get(index).literalLength() < 2) {
                continue;
            }
            String description = "%s aarch64=%s".formatted(pattern, aarch64);
            MethodModel search = method(model, "find" + index);
            String verifyName = "verifyCandidates" + index;
            MethodModel verifyCandidates = method(model, verifyName);
            assertThat(code(search)
                    .filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast)
                    .anyMatch(invoke -> invoke.owner().equals(model.thisClass()) && invoke.name().equalsString(verifyName)))
                    .as("%s %s", description, search.methodName().stringValue())
                    .isTrue();
            for (MethodModel method : List.of(search, verifyCandidates)) {
                assertThat(code(method)
                        .filter(FieldInstruction.class::isInstance)
                        .map(FieldInstruction.class::cast)
                        .anyMatch(field -> field.opcode() == Opcode.GETSTATIC && field.name().stringValue().startsWith("broadcast")))
                        .as("%s %s", description, method.methodName().stringValue())
                        .isEqualTo(aarch64);
            }
            splitSearches++;
        }
        return splitSearches;
    }

    /// Returns the literals the generated class of `kernel` has a search method for, in the
    /// order of their numbers.
    private static List<LiteralSearchKernel> searchedLiterals(TrinoLikeKernelGenerator.Kernel kernel)
    {
        return switch (kernel.region()) {
            case TrinoLikeKernelGenerator.OrderedLiterals ordered -> ordered.literals();
            case TrinoLikeKernelGenerator.LiteralGaps gaps -> gaps.literals().subList(0, 1);
            case null, default -> List.of();
        };
    }

    private static MethodModel method(ClassModel model, String name)
    {
        return model.methods().stream()
                .filter(method -> method.methodName().equalsString(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("generated class lacks " + name));
    }

    private static Stream<CodeElement> code(MethodModel method)
    {
        return method.code().orElseThrow().elementStream();
    }

    /// Requires the generated pattern and its handle to give the interpreted result on each
    /// source.
    private static void verifySameMatches(String pattern, TrinoLikePattern generated, TrinoLikePattern interpreted, List<Slice> sources)
            throws Throwable
    {
        MethodHandle handle = generated.generatedMatches().orElseThrow();
        for (Slice source : sources) {
            String description = pattern + " on " + HexFormat.of().formatHex(source.getBytes());
            boolean expected = interpreted.matches(source);
            assertThat(generated.matches(source)).as(description).isEqualTo(expected);
            assertThat((boolean) handle.invokeExact(source)).as(description).isEqualTo(expected);
        }
    }

    /// Returns `count` sources built from `pattern`: each `%` becomes up to three
    /// random symbols or malformed sequences, each `_` one, and each other character itself
    /// or, occasionally, a random one. Each source appears as a view inside a larger array and as
    /// an exact-length copy, which has no readable byte past its end.
    private static List<Slice> sources(Random random, String pattern, int count)
    {
        List<Slice> sources = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            pattern.codePoints().forEach(codePoint -> {
                if (codePoint == '%') {
                    for (int repetition = random.nextInt(4); repetition > 0; repetition--) {
                        text.writeBytes(randomCharacter(random));
                    }
                }
                else if (codePoint == '_' || random.nextInt(16) == 0) {
                    text.writeBytes(randomCharacter(random));
                }
                else {
                    text.writeBytes(Character.toString(codePoint).getBytes(UTF_8));
                }
            });
            if (random.nextInt(4) == 0) {
                text.writeBytes(randomCharacter(random));
            }
            byte[] bytes = text.toByteArray();
            sources.add(view(bytes));
            sources.add(wrappedBuffer(bytes));
        }
        return sources;
    }

    private static byte[] randomCharacter(Random random)
    {
        if (random.nextInt(4) == 0) {
            return MALFORMED_SEQUENCES.get(random.nextInt(MALFORMED_SEQUENCES.size()));
        }
        return SYMBOLS.get(random.nextInt(SYMBOLS.size())).getBytes(UTF_8);
    }

    private static Slice view(String value)
    {
        return view(value.getBytes(UTF_8));
    }

    /// Returns a view of `text` at a non-zero offset of a larger array whose neighbors are
    /// continuation bytes, which a stray read would consume.
    private static Slice view(byte[] text)
    {
        byte[] bytes = new byte[text.length + 6];
        Arrays.fill(bytes, (byte) 0x80);
        System.arraycopy(text, 0, bytes, 3, text.length);
        return wrappedBuffer(bytes, 3, text.length);
    }

    private static byte[] bytes(int... values)
    {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }
}
