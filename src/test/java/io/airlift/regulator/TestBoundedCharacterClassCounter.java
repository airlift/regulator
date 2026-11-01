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

import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestBoundedCharacterClassCounter
{
    // Holds every non-ASCII character and 47 single ASCII ones, so it has enough ranges for the counter.
    private static final String ALL_NON_ASCII_CLASS = "[\\x{80}-\\x{10FFFF}!#%')+\\-/13579;=?ACEGIKMOQSUWY\\[\\]_acegikmoqsuwy{}]";

    @Test
    public void testSelectiveUnicodeClassUsesRetainedDfaOnlyForLargeCounts()
    {
        Re2 greek = Re2.compile(utf8Slice("\\p{Greek}+"));

        assertThat(greek.usesCompactBoundedCharacterClassForDiagnostics()).isTrue();
        Prog countProgram = greek.forwardProgramForDiagnostics();
        if (Dfa.nativeAccessEnabled()) {
            assertThat(countProgram.dfaMemory()).isPositive();
            assertThat(countProgram.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        }
        else {
            assertThat(countProgram.dfaMemory()).isZero();
        }

        Slice shortInput = utf8Slice("1αβγ!δε?");
        assertThat(greek.find(shortInput)).isTrue();
        assertThat(greek.countMatches(shortInput)).isEqualTo(2);
        assertThat(countProgram.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        assertThat(greek.forwardProgramForDiagnostics()).isSameAs(countProgram);

        Slice largeInput = largeSparseGreekInput();
        // Pinned native RE2 returns two non-overlapping Greek spans.
        assertThat(greek.countMatches(largeInput)).isEqualTo(2);
        if (Dfa.nativeAccessEnabled()) {
            assertThat(countProgram.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNotNull();
        }

        Re2 allLetters = Re2.compile(utf8Slice("\\p{L}+"));
        assertThat(allLetters.usesCompactBoundedCharacterClassForDiagnostics()).isTrue();
        assertThat(allLetters.forwardProgramForDiagnostics().dfaMemory()).isZero();
        assertThat(allLetters.countMatches(largeInput)).isEqualTo(2);
        assertThat(allLetters.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
    }

    @Test
    public void testSelectiveUnicodeClassDfaMemoryAccounting()
    {
        long maxMemory = 512L << 10;
        Re2 greek = Re2.compile(utf8Slice("\\p{Greek}+"), Re2.Options.defaults().setMaxMemory(maxMemory));
        BoundedCharacterClassCounter counter = greek.createBoundedCharacterClassCounter();
        Prog countProgram = greek.forwardProgramForDiagnostics();
        if (Dfa.nativeAccessEnabled()) {
            assertThat(countProgram.dfaMemory()).isGreaterThanOrEqualTo(128L << 10);
            assertThat(Compiler.estimatedProgramMemory(countProgram) +
                    counter.estimatedRetainedSize() +
                    countProgram.dfaMemory())
                    .isLessThanOrEqualTo(maxMemory - (maxMemory / 3));
        }
        else {
            assertThat(countProgram.dfaMemory()).isZero();
        }
        assertThat(greek.countMatches(largeSparseGreekInput())).isEqualTo(2);

        Re2 compactOnly = Re2.compile(utf8Slice("\\p{Greek}+"), Re2.Options.defaults().setMaxMemory(128L << 10));
        assertThat(compactOnly.usesCompactBoundedCharacterClassForDiagnostics()).isTrue();
        assertThat(compactOnly.forwardProgramForDiagnostics().dfaMemory()).isZero();
        assertThat(compactOnly.countMatches(largeSparseGreekInput())).isEqualTo(2);
    }

    @Test
    public void testSelectiveUnicodeClassKeepsCompactCounterForDenseMatches()
    {
        Re2 oneRun = Re2.compile(utf8Slice("\\p{Greek}+"));
        Slice oneRunInput = utf8Slice("α".repeat(1_100_000));
        Prog oneRunCountProgram = oneRun.forwardProgramForDiagnostics();

        // Pinned native RE2 returns one match for the complete run.
        assertThat(oneRun.countMatches(oneRunInput)).isEqualTo(1);
        assertThat(oneRunCountProgram.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();

        Re2 greek = Re2.compile(utf8Slice("\\p{Greek}+"));
        Slice denseInput = utf8Slice(("α".repeat(7) + ".").repeat(131_072));
        Prog countProgram = greek.forwardProgramForDiagnostics();

        // Pinned native RE2 returns one non-overlapping match for each seven-code-point run.
        assertThat(greek.countMatches(denseInput)).isEqualTo(131_072);
        assertThat(countProgram.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();

        Re2 delayedGreek = Re2.compile(utf8Slice("\\p{Greek}+"));
        Slice delayedDenseInput = utf8Slice(".".repeat(400_000) + ("α".repeat(7) + ".").repeat(80_000));
        Prog delayedCountProgram = delayedGreek.forwardProgramForDiagnostics();

        // Pinned native RE2 returns the same matches when the dense region follows a sparse prefix.
        assertThat(delayedGreek.countMatches(delayedDenseInput)).isEqualTo(80_000);
        assertThat(delayedCountProgram.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
    }

    @Test
    public void testSelectiveUnicodeClassConcurrentReuseAndCacheReset()
            throws Exception
    {
        Re2 greek = Re2.compile(utf8Slice("\\p{Greek}+"));
        Slice largeInput = largeSparseGreekInput();

        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int task = 0; task < 8; task++) {
                tasks.add(() -> greek.countMatches(largeInput));
            }
            List<Future<Long>> counts = executor.invokeAll(tasks);
            for (Future<Long> count : counts) {
                assertThat(count.get()).isEqualTo(2);
            }
        }

        Prog countProgram = greek.forwardProgramForDiagnostics();
        if (!Dfa.nativeAccessEnabled()) {
            assertThat(countProgram.dfaMemory()).isZero();
            return;
        }

        Dfa.DfaInstance dfa = countProgram.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(dfa).isNotNull();
        int resetCount = dfa.resetCount();
        dfa.resetCacheExternal();
        assertThat(greek.countMatches(largeInput)).isEqualTo(2);
        assertThat(greek.forwardProgramForDiagnostics()).isSameAs(countProgram);
        assertThat(dfa.resetCount()).isGreaterThan(resetCount);
    }

    private static Slice largeSparseGreekInput()
    {
        byte[] bytes = utf8Slice("!!" + ".".repeat(1024 * 1024) + "αβγ" + ".".repeat(1024 * 1024) + "δε??").getBytes();
        return Slices.wrappedBuffer(bytes, 2, bytes.length - 4);
    }

    @Test
    public void testLargeFixedUnicodeRepeatUsesCompactRoute()
    {
        Re2 re2 = Re2.compile(utf8Slice("\\p{L}{256}"));

        assertThat(re2.usesCompactBoundedCharacterClassForDiagnostics()).isTrue();
        assertThat(re2.forwardProgramForDiagnostics().dfaMemory()).isZero();

        Slice exactMatch = utf8Slice("δ".repeat(256));
        assertThat(re2.find(exactMatch)).isTrue();
        assertThat(re2.lookingAt(exactMatch)).isTrue();
        assertThat(re2.matches(exactMatch)).isTrue();

        int[] groups = new int[2];
        Slice twoMatches = utf8Slice("1" + "δ".repeat(300) + "!" + "a".repeat(256));
        assertThat(re2.findInto(twoMatches, groups)).isTrue();
        assertThat(groups).containsExactly(1, 513);

        RegexpMatcher matcher = re2.matcher(twoMatches, 0);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(513);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(602);
        assertThat(matcher.end()).isEqualTo(858);
        assertThat(matcher.find()).isFalse();

        Slice regionBacking = utf8Slice("!!" + "δ".repeat(256) + "??");
        assertThat(matcher.reset(regionBacking, 2, regionBacking.length() - 2).matches()).isTrue();
        assertThat(matcher.start()).isEqualTo(2);
        assertThat(matcher.end()).isEqualTo(regionBacking.length() - 2);

        Slice region = utf8Slice("!" + "δ".repeat(256) + "?");
        assertThat(re2.matches(region, 1, 513)).isTrue();
        assertThat(re2.findInto(region, 1, 513, groups)).isTrue();
        assertThat(groups).containsExactly(1, 513);
    }

    @Test
    public void testUnboundedUnicodeRepeatUsesCompactRoute()
    {
        Re2 re2 = Re2.compile(utf8Slice("\\p{L}+"));

        assertThat(re2.usesCompactBoundedCharacterClassForDiagnostics()).isTrue();
        assertThat(re2.forwardProgramForDiagnostics().dfaMemory()).isZero();

        Slice input = utf8Slice("1δé!abc?");
        int[] groups = new int[2];
        assertThat(re2.findInto(input, groups)).isTrue();
        assertThat(groups).containsExactly(1, 5);
        assertThat(re2.lookingAt(input)).isFalse();
        assertThat(re2.matches(input)).isFalse();
        assertThat(re2.matches(input, 1, 5)).isTrue();

        RegexpMatcher matcher = re2.matcher(input, 0);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(5);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(6);
        assertThat(matcher.end()).isEqualTo(9);
        assertThat(matcher.find()).isFalse();

        Re2 bounded = Re2.compile(utf8Slice("\\p{L}{2,4}"));
        assertThat(bounded.usesCompactBoundedCharacterClassForDiagnostics()).isTrue();
        assertThat(bounded.find(utf8Slice("1abcdef?"))).isTrue();
        assertThat(bounded.findInto(utf8Slice("1abcdef?"), groups)).isTrue();
        assertThat(groups).containsExactly(1, 5);

        Re2 openEnded = Re2.compile(utf8Slice("\\p{L}{2,}"));
        assertThat(openEnded.usesCompactBoundedCharacterClassForDiagnostics()).isTrue();
        assertThat(openEnded.findInto(utf8Slice("1abcdef?"), groups)).isTrue();
        assertThat(groups).containsExactly(1, 7);
    }

    @Test
    public void testClassCountersHonorMemoryLimit()
    {
        // The bitset ends at the last letter: about 32 KB.
        assertThatThrownBy(() -> Re2.compile(utf8Slice("\\p{L}{256}"), Re2.Options.defaults().setMaxMemory(32 * 1024)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
        assertThat(Re2.compile(utf8Slice("\\p{L}{256}"), Re2.Options.defaults().setMaxMemory(64 * 1024)).usesCompactBoundedCharacterClassForDiagnostics()).isTrue();

        // A single class's counter takes its bytes from the DFA budget; a budget too small for it
        // keeps the engine.
        assertThat(Re2.compile(utf8Slice("\\pL"), Re2.Options.defaults().setMaxMemory(32 * 1024)).classMatchCounter()).isNull();
        Slice pattern = utf8Slice("\\pL");
        int flags = Re2.Options.defaults().parseFlags();
        ParseResult parsed = RegexpParser.parse(pattern, flags);
        long maxMemory = 64 * 1024;
        Prog control = Compiler.compile(parsed.regexp(), false, maxMemory - maxMemory / 3);
        Re2 letters = Re2.compileParsed(pattern, parsed, flags, maxMemory);
        BoundedCharacterClassCounter counter = letters.classMatchCounter();
        assertThat(counter.estimatedRetainedSize()).isEqualTo(32_976);
        assertThat(letters.forwardProgramForDiagnostics().dfaMemory()).isEqualTo(control.dfaMemory() - counter.estimatedRetainedSize());
    }

    @Test
    public void testCompactRouteDoesNotReplaceOrdinaryPrograms()
    {
        assertThat(Re2.compile(utf8Slice("\\p{L}{8}"))
                .usesCompactBoundedCharacterClassForDiagnostics()).isFalse();
        assertThat(Re2.compile(utf8Slice("[a-z]{256}"))
                .usesCompactBoundedCharacterClassForDiagnostics()).isFalse();
        assertThat(Re2.compile(utf8Slice("(\\p{L}{256})"))
                .usesCompactBoundedCharacterClassForDiagnostics()).isFalse();
        assertThat(Re2.compile(utf8Slice("\\p{L}+?"))
                .usesCompactBoundedCharacterClassForDiagnostics()).isFalse();
        assertThat(Re2.compile(utf8Slice("\\p{L}+"), Re2.Options.defaults().setLongestMatch(true))
                .usesCompactBoundedCharacterClassForDiagnostics()).isFalse();
        assertThat(Re2.compile(utf8Slice("[a-z]{256}"), Re2.Options.latin1())
                .usesCompactBoundedCharacterClassForDiagnostics()).isFalse();
    }

    @Test
    public void testClassCounterMatcherMatchesEngineInEveryLanguage()
    {
        // ASCII, two-, three- and four-byte letters and non-letters, adjacent and three-byte Greek
        // letters, Arabic and Devanagari digits, three-byte capitals, gaps of lowercase text around
        // capitals whose lead bytes also start lowercase letters, then overlong C0 80 and C1 BF, a
        // lead cut short by ASCII, a bare continuation byte, a valid ж, a truncated three-byte
        // letter, and a two-byte lead at the end.
        byte[] text = "ab1 éж€中𝐀😀-Zя αβἀ ٣३ ＡḀ the Éclair, Жук and quiet tea ".getBytes(UTF_8);
        byte[] malformed = {
                (byte) 0xC0, (byte) 0x80, 'q', (byte) 0xC1, (byte) 0xBF, (byte) 0xC3, 'r', (byte) 0x80,
                (byte) 0xD0, (byte) 0xB6, (byte) 0xE4, (byte) 0xB8, 's', (byte) 0xC3,
        };
        byte[] bytes = Arrays.copyOf(text, text.length + malformed.length);
        System.arraycopy(malformed, 0, bytes, text.length, malformed.length);
        Slice input = Slices.wrappedBuffer(bytes);
        List<Slice> inputs = new ArrayList<>(randomFragmentInputs());
        inputs.addAll(longGapInputs());
        inputs.add(input);

        // Each language spells its own classes.
        for (String pattern : List.of("\\pL", "\\p{Ll}", "\\p{Lu}", "(?i)\\p{Lu}", "\\p{Greek}", "[\\pL\\x{3FFFF}]")) {
            assertCounterMatcherAgrees("RE2", Re2::compile, pattern, false, inputs);
        }
        for (String pattern : List.of("\\p{L}", "\\p{Lu}", "(?i)\\p{Lu}", "[\\p{L}\\x{3FFFF}]")) {
            assertCounterMatcherAgrees("Java", source -> JavaRegexp.compile(source).re2(), pattern, false, inputs);
        }
        for (String pattern : List.of("\\p{L}", "(?i)\\p{Lu}", "[\\p{L}\\x{3FFFF}]")) {
            assertCounterMatcherAgrees("Trino", source -> TrinoRegexp.compile(source).re2(), pattern, false, inputs);
        }
        for (String pattern : List.of("\\pL", "\\p{Lu}", "(?i)\\p{Lu}", "\\d", "[\\pL\\x{3FFFF}]")) {
            assertCounterMatcherAgrees("Rust", source -> RustRegexp.compile(source).re2(), pattern, false, inputs);
        }
        // Compact patterns find through the same counter.
        for (String pattern : List.of("\\p{Lu}+", "\\p{L}{2,3}", ALL_NON_ASCII_CLASS + "+")) {
            assertCounterMatcherAgrees("RE2", Re2::compile, pattern, true, inputs);
        }

        // The matrix above calls Re2 directly, which bypasses the Trino matcher wrapper.
        TrinoRegexpMatcher trino = TrinoRegexp.compile(utf8Slice("\\p{L}")).matcher(input, 0);
        TrinoRegexpMatcher trinoOrdinary = TrinoRegexp.compile(utf8Slice("(\\p{L})")).matcher(input, 0);
        while (trinoOrdinary.find()) {
            assertThat(trino.find()).isTrue();
            assertThat(trino.start()).isEqualTo(trinoOrdinary.start());
            assertThat(trino.end()).isEqualTo(trinoOrdinary.end());
        }
        assertThat(trino.find()).isFalse();
    }

    @Test
    public void testLastPlaneAndNullableClassesKeepEngine()
    {
        // Planes 4 through 13 hold no characters, so a class ending before U+40000 has a counter,
        // and one that reaches the last planes, as a negated class or one with plane-14 members
        // does, has none; its bitset would retain up to 136 KiB.
        List<Function<Slice, Re2>> compilers = List.of(
                Re2::compile,
                source -> JavaRegexp.compile(source).re2(),
                source -> TrinoRegexp.compile(source).re2(),
                source -> RustRegexp.compile(source).re2());
        for (Function<Slice, Re2> compiler : compilers) {
            Re2 belowLimit = compiler.apply(utf8Slice("[\\p{L}\\x{3FFFF}]"));
            assertThat(belowLimit.classMatchCounter().estimatedRetainedSize()).as(belowLimit.pattern().toStringUtf8()).isEqualTo(33_096);
            Re2 atLimit = compiler.apply(utf8Slice("[\\p{L}\\x{40000}]"));
            assertThat(atLimit.classMatchCounter()).as(atLimit.pattern().toStringUtf8()).isNull();
        }
        List<Re2> lastPlaneClasses = List.of(
                Re2.compile(utf8Slice("\\PL")),
                Re2.compile(utf8Slice(ALL_NON_ASCII_CLASS)),
                Re2.compile(utf8Slice("\\p{Mn}")),
                JavaRegexp.compile(utf8Slice("\\w"), JavaRegexp.Options.defaults().setUnicodeCharacterClasses(true)).re2(),
                RustRegexp.compile(utf8Slice("\\w")).re2(),
                RustRegexp.compile(utf8Slice("\\D")).re2());
        for (Re2 re2 : lastPlaneClasses) {
            assertThat(re2.classMatchCounter()).as(re2.pattern().toStringUtf8()).isNull();
        }

        // A pattern that can match empty has no counter; its matcher keeps the engine.
        for (String pattern : List.of("\\pL*", "\\pL?", "\\pL{0,3}", "(?:\\pL|)")) {
            assertThat(Re2.compile(utf8Slice(pattern)).classMatchCounter()).as(pattern).isNull();
            assertThat(RustRegexp.compile(utf8Slice(pattern)).re2().classMatchCounter()).as(pattern).isNull();
        }
    }

    @Test
    public void testOrdinaryMatcherFindsOnClassCounter()
    {
        Re2 letters = Re2.compile(utf8Slice("\\pL"));
        Slice input = utf8Slice("ab1 éж€中𝐀");
        RegexpMatcher matcher = letters.matcher(input, 0);
        assertThat(matcher.characterClassCounterEnabledForDiagnostics()).isTrue();
        Prog program = letters.forwardProgramForDiagnostics();
        assertThat(spans(matcher)).hasSize(6);
        assertThat(program.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();

        RegexpMatcher generic = letters.genericGroupZeroMatcherForDiagnostics(input);
        assertThat(generic.characterClassCounterEnabledForDiagnostics()).isFalse();
        assertThat(spans(generic)).hasSize(6);
        assertThat(program.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNotNull();

        // `\pL` has too many start bytes for the vector scan; a sparse class uses it when it can.
        assertThat(letters.classMatchCounter().scansMemberStartsWithVectorsForDiagnostics()).isFalse();
        for (Re2 sparse : List.of(Re2.compile(utf8Slice("\\p{Greek}")), RustRegexp.compile(utf8Slice("\\d")).re2())) {
            assertThat(sparse.classMatchCounter().scansMemberStartsWithVectorsForDiagnostics())
                    .as(sparse.pattern().toStringUtf8())
                    .isEqualTo(VectorSupport.isAvailable());
        }
    }

    @Test
    public void testCountsGreedyBoundedRuns()
    {
        assertCount("[a-z]{8,13}", "abcdefgh", 1);
        assertCount("[a-z]{8,13}", "abcdefghijklm", 1);
        assertCount("[a-z]{8,13}", "abcdefghijklmnopqrst", 1);
        assertCount("[a-z]{8,13}", "abcdefghijklmnopqrstu", 2);
        assertCount("[a-z]{8,13}", "abcdefghijklmnopqrstuvwxyz", 2);
        assertCount("(\\p{L}{8,13})", "абвгдежз 12 абвгдежзийклм", 2);

        Re2 re2 = Re2.compile(utf8Slice("[a-z]{2,5}"));
        BoundedCharacterClassCounter counter = re2.createBoundedCharacterClassCounter();
        for (int runLength = 0; runLength <= 30; runLength++) {
            Slice input = utf8Slice("a".repeat(runLength));
            assertThat(counter.count(input))
                    .as("run length %s", runLength)
                    .isEqualTo(countWithGeneralMatcher(re2, input));
        }
    }

    @Test
    public void testMatchesGeneralMatcher()
    {
        for (String pattern : List.of("[a-z]{2,4}", "[0-9]{1,3}", "\\p{L}{2,5}", "[^x]{3,7}", "\\p{L}+", "[a-z]{2,}")) {
            Re2 re2 = Re2.compile(utf8Slice(pattern));
            Re2 reference = Re2.compile(utf8Slice("(" + pattern + ")"));
            BoundedCharacterClassCounter matcher = re2.createBoundedCharacterClassCounter();
            assertThat(matcher).as(pattern).isNotNull();
            for (String input : List.of("", "a", "abc12defgh", "абв гдеёж 123", "xxxxxxxx")) {
                assertThat(matcher.count(utf8Slice(input)))
                        .as("pattern %s input %s", pattern, input)
                        .isEqualTo(countWithGeneralMatcher(reference, utf8Slice(input)));
            }
        }
    }

    @Test
    public void testMalformedUtf8MatchesGeneralMatcher()
    {
        // Overlong E0 80 80 and F0 80 80 80, encoded surrogates, F4 90 80 80 above U+10FFFF, a
        // truncated E2 82, a bare continuation byte, and FF, between valid characters.
        Slice malformed = Slices.wrappedBuffer(new byte[] {
                'a', (byte) 0xE0, (byte) 0x80, (byte) 0x80, 'x', (byte) 0xED, (byte) 0xA0, (byte) 0x80,
                (byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80, (byte) 0xC3, (byte) 0xA9, 'x',
                (byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0xE2, (byte) 0x82,
                (byte) 0xED, (byte) 0xBF, (byte) 0xBF, (byte) 0x80, (byte) 0xFF, 'b',
        });
        // Classes of every non-ASCII character, of some including all the surrogates, of only some
        // of the surrogates, and of some excluding them. A capture keeps the reference count on the
        // ordinary engine; without one, a class of 32 or more ranges counts through the compact
        // counter under test.
        for (String pattern : List.of("[^x]{1,3}", "[^\\x{100}]{1,3}", "[\\x{D800}-\\x{DBFF}]{1,3}", "\\p{L}{1,3}")) {
            BoundedCharacterClassCounter counter = Re2.compile(utf8Slice(pattern)).createBoundedCharacterClassCounter();
            assertThat(counter.count(malformed))
                    .as(pattern)
                    .isEqualTo(countWithGeneralMatcher(Re2.compile(utf8Slice("(" + pattern + ")")), malformed));
        }

        // An encoded surrogate cut off by the end of the input, though its backing bytes continue.
        Slice truncatedSurrogate = Slices.wrappedBuffer(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, 0, 2);
        BoundedCharacterClassCounter surrogateCounter = Re2.compile(utf8Slice("[^\\x{100}]{1,3}")).createBoundedCharacterClassCounter();
        assertThat(surrogateCounter.count(truncatedSurrogate))
                .isEqualTo(countWithGeneralMatcher(Re2.compile(utf8Slice("([^\\x{100}]{1,3})")), truncatedSurrogate))
                .isZero();

        // The compact route's class holds the surrogates. Pinned native RE2 also counts two matches
        // here; the spans are compared with the ordinary engine's.
        Re2 compact = Re2.compile(utf8Slice("\\P{L}+"));
        assertThat(compact.usesCompactBoundedCharacterClassForDiagnostics()).isTrue();
        assertThat(compact.count(malformed)).isEqualTo(2);
        RegexpMatcher compactMatcher = compact.matcher(malformed, 0);
        RegexpMatcher ordinaryMatcher = Re2.compile(utf8Slice("(\\P{L}+)")).groupZeroMatcher(malformed, null);
        while (ordinaryMatcher.find()) {
            assertThat(compactMatcher.find()).isTrue();
            assertThat(compactMatcher.start()).isEqualTo(ordinaryMatcher.start());
            assertThat(compactMatcher.end()).isEqualTo(ordinaryMatcher.end());
        }
        assertThat(compactMatcher.find()).isFalse();

        Re2 replacementCharacter = Re2.compile(utf8Slice("[^x]{1,3}"));
        BoundedCharacterClassCounter counter = replacementCharacter.createBoundedCharacterClassCounter();
        Slice validReplacementCharacter = utf8Slice("�");
        assertThat(counter.count(validReplacementCharacter))
                .isEqualTo(countWithGeneralMatcher(replacementCharacter, validReplacementCharacter));
    }

    @Test
    public void testLatin1MatchesGeneralMatcher()
    {
        Re2 re2 = Re2.compile(utf8Slice("[^x]{1,3}"), Re2.Options.latin1());
        BoundedCharacterClassCounter counter = re2.createBoundedCharacterClassCounter();
        Slice input = Slices.wrappedBuffer(new byte[] {'a', (byte) 0xFF, 'x', (byte) 0x80});

        assertThat(counter.count(input)).isEqualTo(countWithGeneralMatcher(re2, input));
    }

    @Test
    public void testAsciiClassReadsBytesInEveryFrontEnd()
    {
        // Every byte of a non-ASCII character, malformed or not, is at least 0x80, so a class of
        // only ASCII characters reads alike byte by byte, which Rust byte mode's ASCII classes
        // need, as they carry no Latin-1 flag.
        Slice input = Slices.wrappedBuffer(new byte[] {
                'a', 'b', (byte) 0xC3, (byte) 0xA9, 'c', 'd', 'e', (byte) 0xE2, (byte) 0x82, 'f', 'g',
                (byte) 0x80, 'h', 'i', 'j', 'k', 'l', (byte) 0xFF, 'm', 'n', (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80, 'o', 'p',
        });
        String pattern = "[A-Za-z]{2,4}";
        for (Re2 re2 : List.of(
                Re2.compile(utf8Slice(pattern)),
                JavaRegexp.compile(utf8Slice(pattern)).re2(),
                TrinoRegexp.compile(utf8Slice(pattern)).re2(),
                RustRegexp.compile(utf8Slice(pattern)).re2(),
                RustRegexp.compile(utf8Slice(pattern), RustRegexp.Options.bytes()).re2())) {
            BoundedCharacterClassCounter counter = re2.createBoundedCharacterClassCounter();
            assertThat(counter.readsBytesForDiagnostics()).isTrue();
            assertThat(counter.count(input)).isEqualTo(countWithGeneralMatcher(re2, input));
            assertThat(counter.findSpan(input, 0, input.length(), 2)).isEqualTo(packSpan(4, 7));
        }
        assertThat(Re2.compile(utf8Slice("[a-z\\x{80}]{2,4}")).createBoundedCharacterClassCounter().readsBytesForDiagnostics()).isFalse();
    }

    @Test
    public void testRejectsUnsupportedShapes()
    {
        for (String pattern : List.of("[a-z]*", "[a-z]{2,4}?", "^[a-z]{2,4}", "(?:ab){2,4}")) {
            assertThat(Re2.compile(utf8Slice(pattern)).createBoundedCharacterClassCounter())
                    .as(pattern)
                    .isNull();
        }
    }

    @Test
    public void testFindsNativeGreedySpans()
    {
        Slice input = utf8Slice("abéж 12 cdefgh");
        BoundedCharacterClassCounter bounded = Re2.compile(utf8Slice("\\p{L}{2,4}"))
                .createBoundedCharacterClassCounter();
        assertThat(bounded.findSpan(input, 0, input.length(), 0)).isEqualTo(packSpan(0, 6));
        assertThat(bounded.findSpan(input, 0, input.length(), 6)).isEqualTo(packSpan(10, 14));
        assertThat(bounded.findSpan(input, 0, input.length(), 14)).isEqualTo(packSpan(14, 16));
        assertThat(bounded.findSpan(input, 0, input.length(), 16)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        assertThat(bounded.contains(input)).isTrue();
        assertThat(bounded.contains(utf8Slice("a é 1 ж"))).isFalse();

        Slice regionBacking = utf8Slice("!éж  cdef?");
        Slice region = Slices.wrappedBuffer(regionBacking.byteArray(), regionBacking.byteArrayOffset() + 1, regionBacking.length() - 2);
        BoundedCharacterClassCounter unbounded = Re2.compile(utf8Slice("\\p{L}+"))
                .createBoundedCharacterClassCounter();
        assertThat(unbounded.findSpan(region, 0, region.length(), 0)).isEqualTo(packSpan(0, 4));
        assertThat(unbounded.findSpan(region, 0, region.length(), 4)).isEqualTo(packSpan(6, 10));
        assertThat(unbounded.contains(utf8Slice("a!").slice(1, 1))).isFalse();

        Slice nonzeroRegion = utf8Slice("!abcde?");
        BoundedCharacterClassCounter regionMatcher = Re2.compile(utf8Slice("[a-z]{2,3}"))
                .createBoundedCharacterClassCounter();
        assertThat(regionMatcher.findSpan(nonzeroRegion, 1, 6, 1)).isEqualTo(packSpan(0, 3));
        assertThat(regionMatcher.findSpan(nonzeroRegion, 1, 6, 4)).isEqualTo(packSpan(3, 5));

        BoundedCharacterClassCounter malformedMatcher = Re2.compile(utf8Slice("[^x]{1,3}"))
                .createBoundedCharacterClassCounter();
        Slice malformed = Slices.wrappedBuffer(new byte[] {'a', (byte) 0xFF, 'b'});
        assertThat(malformedMatcher.findSpan(malformed, 0, malformed.length(), 0)).isEqualTo(packSpan(0, 1));
        assertThat(malformedMatcher.findSpan(malformed, 0, malformed.length(), 1)).isEqualTo(packSpan(2, 3));
        assertThat(malformedMatcher.contains(malformed)).isTrue();
        assertThat(Re2.compile(utf8Slice("[^x]{2,3}")).createBoundedCharacterClassCounter().contains(malformed)).isFalse();

        BoundedCharacterClassCounter latin1Matcher = Re2.compile(utf8Slice("[^x]{1,3}"), Re2.Options.latin1())
                .createBoundedCharacterClassCounter();
        Slice latin1 = Slices.wrappedBuffer(new byte[] {'a', (byte) 0xFF, 'x', (byte) 0x80});
        assertThat(latin1Matcher.findSpan(latin1, 0, latin1.length(), 0)).isEqualTo(packSpan(0, 2));
        assertThat(latin1Matcher.findSpan(latin1, 0, latin1.length(), 2)).isEqualTo(packSpan(3, 4));
    }

    // A capture keeps the reference matcher on the engine.
    private static void assertCounterMatcherAgrees(String language, Function<Slice, Re2> compiler, String pattern, boolean compact, List<Slice> inputs)
    {
        String description = language + " " + pattern;
        Re2 counted = compiler.apply(utf8Slice(pattern));
        Re2 reference = compiler.apply(utf8Slice("(" + pattern + ")"));
        assertThat(counted.classMatchCounter()).as(description).isNotNull();
        assertThat(counted.usesCompactBoundedCharacterClassForDiagnostics()).as(description).isEqualTo(compact);
        assertThat(reference.classMatchCounter()).as(description).isNull();
        for (Slice input : inputs) {
            String hex = HexFormat.of().formatHex(input.getBytes());
            assertThat(spans(counted.matcher(input, 0)))
                    .as("%s on %s", description, hex)
                    .isEqualTo(spans(reference.matcher(input, 0)));
            if (input.length() >= 5) {
                assertThat(spans(counted.matcher(input, 0).reset(input, 3, input.length() - 2)))
                        .as("%s on a region of %s", description, hex)
                        .isEqualTo(spans(reference.matcher(input, 0).reset(input, 3, input.length() - 2)));
            }
        }
    }

    // Seeded strings of ASCII gaps, letters of each width, encoded surrogates, and malformed bytes:
    // overlong leads, bare continuations, truncated sequences, and bytes past U+10FFFF.
    private static List<Slice> randomFragmentInputs()
    {
        List<byte[]> fragments = new ArrayList<>();
        for (String text : List.of("a", "Z", " ", "1", "the ", "é", "É", "ж", "Ж", "α", "ἀ", "٣", "३", "Ａ", "Ḁ", "€", "中", "𝐀", "😀")) {
            fragments.add(text.getBytes(UTF_8));
        }
        int[][] malformed = {{0xC0, 0x80}, {0xC1}, {0x80}, {0xBF}, {0xC3}, {0xD0}, {0xE4, 0xB8}, {0xED, 0xA0, 0x80}, {0xF4, 0x90, 0x80, 0x80}, {0xF5}, {0xFF}};
        for (int[] values : malformed) {
            byte[] bytes = new byte[values.length];
            for (int i = 0; i < values.length; i++) {
                bytes[i] = (byte) values[i];
            }
            fragments.add(bytes);
        }
        Random random = new Random(14);
        List<Slice> inputs = new ArrayList<>();
        for (int inputIndex = 0; inputIndex < 300; inputIndex++) {
            DynamicSliceOutput output = new DynamicSliceOutput(64);
            int count = random.nextInt(12);
            for (int i = 0; i < count; i++) {
                output.writeBytes(fragments.get(random.nextInt(fragments.size())));
            }
            inputs.add(output.slice());
        }
        return inputs;
    }

    // Lowercase gaps of every length up to 160 bytes, past the counter's scalar prefix and two vector
    // steps of up to 64 bytes, with malformed bytes inside, ending at the input's end or at a Greek capital, a
    // Cyrillic letter, and a digit that fall anywhere in the last vector step.
    private static List<Slice> longGapInputs()
    {
        byte[] gap = "the quiet tea and clear air over the long road home, then back down to the sea again ".repeat(2).getBytes(UTF_8);
        gap[9] = (byte) 0xC3;
        gap[13] = (byte) 0x80;
        gap[17] = (byte) 0xFF;
        byte[] members = "Ωж7".getBytes(UTF_8);
        List<Slice> inputs = new ArrayList<>();
        for (int length = 0; length <= 160; length++) {
            byte[] bytes = Arrays.copyOf(gap, length + members.length);
            System.arraycopy(members, 0, bytes, length, members.length);
            inputs.add(Slices.wrappedBuffer(bytes));
            inputs.add(Slices.wrappedBuffer(Arrays.copyOf(gap, length)));
        }
        return inputs;
    }

    private static List<Long> spans(RegexpMatcher matcher)
    {
        List<Long> spans = new ArrayList<>();
        while (matcher.find()) {
            spans.add(packSpan(matcher.start(), matcher.end()));
        }
        return spans;
    }

    private static long packSpan(int start, int end)
    {
        return ((long) start << 32) | (end & 0xFFFF_FFFFL);
    }

    private static void assertCount(String pattern, String input, long expected)
    {
        BoundedCharacterClassCounter matcher = Re2.compile(utf8Slice(pattern)).createBoundedCharacterClassCounter();
        assertThat(matcher).isNotNull();
        assertThat(matcher.count(utf8Slice(input))).isEqualTo(expected);
    }

    private static long countWithGeneralMatcher(Re2 re2, Slice input)
    {
        RegexpMatcher matcher = re2.genericGroupZeroMatcherForDiagnostics(input);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
