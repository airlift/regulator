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
        assertThat(countProgram.dfaMemory()).isPositive();
        assertThat(countProgram.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();

        Slice shortInput = utf8Slice("1αβγ!δε?");
        assertThat(greek.find(shortInput)).isTrue();
        assertThat(greek.countMatches(shortInput)).isEqualTo(2);
        assertThat(countProgram.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        assertThat(greek.forwardProgramForDiagnostics()).isSameAs(countProgram);

        Slice largeInput = largeSparseGreekInput();
        // Pinned native RE2 returns two non-overlapping Greek spans.
        assertThat(greek.countMatches(largeInput)).isEqualTo(2);
        assertThat(countProgram.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNotNull();

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
        assertThat(countProgram.dfaMemory()).isGreaterThanOrEqualTo(128L << 10);
        assertThat(Compiler.estimatedProgramMemory(countProgram) +
                counter.estimatedRetainedSize() +
                countProgram.dfaMemory())
                .isLessThanOrEqualTo(maxMemory - (maxMemory / 3));
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

        // A single class's or a class repeat's counter takes its bytes from the DFA budget; a
        // budget too small for it keeps the engine.
        assertThat(Re2.compile(utf8Slice("\\p{Greek}"), Re2.Options.defaults().setMaxMemory(32 * 1024)).classMatchCounter()).isNull();
        int flags = Re2.Options.defaults().parseFlags();
        long maxMemory = 292 * 1024;
        for (String source : List.of("\\pL", "[а-яё]+")) {
            Slice pattern = utf8Slice(source);
            ParseResult parsed = RegexpParser.parse(pattern, flags);
            Prog control = Compiler.compile(parsed.regexp(), false, maxMemory - maxMemory / 3);
            Re2 re2 = Re2.compileParsed(pattern, parsed, flags, maxMemory);
            assertThat(re2.forwardProgramForDiagnostics().dfaMemory()).as(source).isEqualTo(control.dfaMemory() - re2.classMatchCounter().estimatedRetainedSize());
        }
        assertThat(Re2.compile(utf8Slice("\\pL")).classMatchCounter().estimatedRetainedSize()).isEqualTo(33_008);
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
        for (String pattern : List.of("\\pL", "\\PL", "\\p{Ll}", "\\p{Lu}", "(?i)\\p{Lu}", "\\p{Greek}", "[\\pL\\x{3FFFF}]", ALL_NON_ASCII_CLASS)) {
            assertCounterMatcherAgrees("RE2", Re2::compile, pattern, false, inputs);
        }
        for (String pattern : List.of("\\p{L}", "\\p{Lu}", "(?i)\\p{Lu}", "[\\p{L}\\x{3FFFF}]")) {
            assertCounterMatcherAgrees("Java", JavaRegexp::compile, pattern, false, inputs);
        }
        for (String pattern : List.of("\\p{L}", "(?i)\\p{Lu}", "[\\p{L}\\x{3FFFF}]")) {
            assertCounterMatcherAgrees("Trino", source -> TrinoRegexp.compile(source).re2(), pattern, false, inputs);
        }
        for (String pattern : List.of("\\pL", "\\p{Lu}", "(?i)\\p{Lu}", "\\d", "\\D", "[\\pL\\x{3FFFF}]")) {
            assertCounterMatcherAgrees("Rust", RustRegexp::compile, pattern, false, inputs);
        }
        // Compact patterns find through the same counter, and match through it in every language.
        for (String pattern : List.of("\\p{Lu}+", "\\p{L}{2,3}", ALL_NON_ASCII_CLASS + "+")) {
            assertCounterMatcherAgrees("RE2", Re2::compile, pattern, true, inputs);
        }
        assertCounterMatcherAgrees("Java", JavaRegexp::compile, "\\p{L}{2,3}", true, inputs);
        assertCounterMatcherAgrees("Trino", source -> TrinoRegexp.compile(source).re2(), "\\p{L}+", true, inputs);
        assertCounterMatcherAgrees("Rust", RustRegexp::compile, ALL_NON_ASCII_CLASS + "+", true, inputs);

        // The matrix above calls the engine directly, which bypasses the Trino matcher wrapper.
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
    public void testClassRunMatcherMatchesEngineInEveryLanguage()
    {
        // The large-class matrix's generated inputs, then runs of eight and fourteen letters,
        // overlong three- and four-byte sequences with the negated classes' delimiters, and the
        // first and last characters past U+40000. Then the scalar values at each width's and the
        // surrogates' edges, followed, between ASCII, by encoded surrogates, overlong sequences, a
        // value past U+10FFFF, a lead byte past F4, a four-byte character cut short by a letter,
        // and, at the end, one cut short by the end. Last, a three- and a four-byte character that
        // the slice's end cuts short, although the bytes after it complete them.
        List<Slice> inputs = new ArrayList<>(randomFragmentInputs());
        inputs.addAll(longGapInputs());
        inputs.add(Slices.wrappedBuffer(new byte[] {'a', 'b', (byte) 0xC3, 'c', (byte) 0x80, '_', '9', (byte) 0xFF, 'd', (byte) 0xC3, (byte) 0xA9, 'e'}));
        inputs.add(utf8Slice("abcdefgh ABCDEFGHIJKLMN"));
        inputs.add(Slices.wrappedBuffer(new byte[] {(byte) 0xE0, (byte) 0x80, (byte) 0x80, 'a', ',', (byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80, 'b', '"', 'c', '\n', 'd', '\r', 'e'}));
        inputs.add(utf8Slice("a\uD8C0\uDC00b,\uDBFF\uDFFF"));
        DynamicSliceOutput edges = new DynamicSliceOutput(64);
        edges.writeBytes(utf8Slice("a\u007F\u0080\u07FF\u0800\uD7FF\uE000\uFFFD\uFFFF\uD800\uDC00\uDBFF\uDFFF"));
        edges.writeBytes(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80, 'b', (byte) 0xE0, (byte) 0x9F, (byte) 0xBF, 'c', (byte) 0xF0, (byte) 0x8F, (byte) 0xBF, (byte) 0xBF});
        edges.writeBytes(new byte[] {'d', (byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80, 'e', (byte) 0xED, (byte) 0xBF, (byte) 0xBF, 'f', '"', 'g', '7', 'h', (byte) 0xF8, (byte) 0x90, (byte) 0x80, (byte) 0x80, 'i'});
        edges.writeBytes(utf8Slice("\uD83D\uDE00"));
        edges.writeBytes(new byte[] {'j', (byte) 0xF0, (byte) 0x9F, (byte) 0x98, 'k'});
        edges.writeBytes(new byte[] {(byte) 0xF0, (byte) 0x9F, (byte) 0x98});
        inputs.add(edges.slice());
        inputs.add(Slices.wrappedBuffer(new byte[] {'a', 'b', (byte) 0xE2, (byte) 0x82, (byte) 0xAC}, 0, 4));
        inputs.add(Slices.wrappedBuffer(new byte[] {'a', 'b', (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80}, 0, 5));

        // A repeat of an ASCII or small class, of a class holding every non-ASCII character, or of
        // `[^\x{100}]`, whose tail starts at U+0101, finds each run on the counter, cut at the
        // maximum.
        for (String pattern : List.of("\\w+", "[A-Za-z]{8,13}", "[0-9]{2,3}", "[a-z]{2,}", "\\d+", "[а-яё]+", "[^,]+", "(?s:.)+", ".{3,}", "[^\"]{2,5}", "[^\\x00-\\x7F]+", "[^\\x{100}]+", "[^\\x{100}]{2,4}")) {
            assertCounterMatcherAgrees("RE2", Re2::compile, pattern, false, inputs);
        }
        // Java and Trino spell an ASCII \w by default, and Java's dot leaves out the Unicode line
        // terminators.
        for (String pattern : List.of("\\w+", "[a-z]{2,4}", "\\d+", "[^,]+", ".+", "(?s).{2,}")) {
            assertCounterMatcherAgrees("Java", JavaRegexp::compile, pattern, false, inputs);
        }
        for (String pattern : List.of("\\w+", "[0-9]+", "[a-z]{3}", ".+", "[^,]{2,}")) {
            assertCounterMatcherAgrees("Trino", source -> TrinoRegexp.compile(source).re2(), pattern, false, inputs);
        }
        for (String pattern : List.of("(?-u:\\w)+", "[0-9]+", "[a-z]{2,4}", ".+", "(?s:.){3,}", "[^,]+")) {
            assertCounterMatcherAgrees("Rust", RustRegexp::compile, pattern, false, inputs);
        }
        // Byte classes read bytes, including those at or past 0x80.
        for (String pattern : List.of("\\w+", "[^,]+", "[\\x80-\\xFF]{1,2}", "(?s).+")) {
            assertCounterMatcherAgrees("Latin-1", source -> Re2.compile(source, Re2.Options.latin1()), pattern, false, inputs);
        }
        // In Rust bytes mode, a Unicode class holds only scalar values, so an encoded surrogate
        // ends its run.
        for (String pattern : List.of("(?-u:\\w)+", "(?-u:[^,])+", "(?-u:[\\x80-\\xFF]){1,2}", "(?s-u:.)+", "(?s:.)+", "(?s:.){2,}", ".+", "[^\"]+", "\\S{2,5}")) {
            assertCounterMatcherAgrees("Rust bytes", source -> RustRegexp.compile(source, RustRegexp.Options.bytes()), pattern, false, inputs);
        }
        // Rust's Unicode `\D` has enough ranges for the compact route.
        assertCounterMatcherAgrees("Rust", RustRegexp::compile, "\\D+", true, inputs);
        assertCounterMatcherAgrees("Rust bytes", source -> RustRegexp.compile(source, RustRegexp.Options.bytes()), "\\D+", true, inputs);

        // Past the minimum, an unbounded repeat of a UTF-8 class of every character, or of every
        // scalar value, skips the rest of its run. A dot's repeats also reach count, including a
        // Rust bytes dot, and Trino's class-span functions.
        assertThat(Re2.compile(utf8Slice("(?s:.)+")).classMatchCounter().skipsEveryCharacterRunsForDiagnostics()).isTrue();
        assertThat(Re2.compile(utf8Slice("[^,]+")).classMatchCounter().skipsEveryCharacterRunsForDiagnostics()).isFalse();
        assertThat(RustRegexp.compile(utf8Slice("(?s:.)+"), RustRegexp.Options.bytes()).classMatchCounter().skipsEveryCharacterRunsForDiagnostics()).isTrue();
        // A control on the existing route: outside bytes mode, Rust's dot holds every non-ASCII
        // character.
        assertThat(RustRegexp.compile(utf8Slice("(?s:.)+")).classMatchCounter().skipsEveryCharacterRunsForDiagnostics()).isTrue();
        assertThat(RustRegexp.compile(utf8Slice(".+"), RustRegexp.Options.bytes()).classMatchCounter().skipsEveryCharacterRunsForDiagnostics()).isFalse();
        assertThat(RustRegexp.compile(utf8Slice("(?s-u:.)+"), RustRegexp.Options.bytes()).createBoundedCharacterClassCounter()).isNotNull();
        assertThat(TrinoRegexp.compile(utf8Slice("(?s).+")).re2().hasCharacterClassSpan()).isTrue();

        // A matcher finds a run of every scalar value on the counter, without the DFA.
        RustRegexp scalarValues = RustRegexp.compile(utf8Slice("(?s:.)+"), RustRegexp.Options.bytes());
        Slice text = utf8Slice("the quiet tea éж€中");
        RegexpMatcher scalarValueMatcher = scalarValues.matcher(text, 0);
        assertThat(scalarValueMatcher.find()).isTrue();
        assertThat(scalarValueMatcher.end()).isEqualTo(text.length());
        assertThat(scalarValues.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
    }

    @Test
    public void testLastPlaneAndNullableClassesKeepEngine()
    {
        // Planes 4 through 13 hold no characters, so a class ending before U+40000 has a counter,
        // and one with members in the last planes, such as `\p{Mn}`, has none; its bitset would
        // retain up to 136 KiB. A class whose last range runs to U+10FFFF keeps that range as a
        // tail, so its bitset ends at the range before the tail, which must end before U+40000, or
        // at U+07FF, the last two-byte character, whichever is later, as for a negated ASCII class.
        List<Function<Slice, CompiledPattern>> compilers = List.of(
                Re2::compile,
                JavaRegexp::compile,
                source -> TrinoRegexp.compile(source).re2(),
                RustRegexp::compile);
        for (Function<Slice, CompiledPattern> compiler : compilers) {
            CompiledPattern belowLimit = compiler.apply(utf8Slice("[\\p{L}\\x{3FFFF}]"));
            assertThat(belowLimit.classMatchCounter().estimatedRetainedSize()).as(belowLimit.pattern().toStringUtf8()).isEqualTo(33_128);
            CompiledPattern atLimit = compiler.apply(utf8Slice("[\\p{L}\\x{40000}]"));
            assertThat(atLimit.classMatchCounter()).as(atLimit.pattern().toStringUtf8()).isNull();
            CompiledPattern allNonAscii = compiler.apply(utf8Slice("[^,]+"));
            assertThat(allNonAscii.classMatchCounter().estimatedRetainedSize()).as(allNonAscii.pattern().toStringUtf8()).isEqualTo(616);
            CompiledPattern beforeTailBelowLimit = compiler.apply(utf8Slice("[\\x{3FFFF}\\x{50000}-\\x{10FFFF}]+"));
            assertThat(beforeTailBelowLimit.classMatchCounter()).as(beforeTailBelowLimit.pattern().toStringUtf8()).isNotNull();
            CompiledPattern beforeTailAtLimit = compiler.apply(utf8Slice("[\\x{40000}\\x{50000}-\\x{10FFFF}]+"));
            assertThat(beforeTailAtLimit.classMatchCounter()).as(beforeTailAtLimit.pattern().toStringUtf8()).isNull();
        }
        // A Rust bytes-mode class of every scalar value retains a bitset that ends at U+D7FF, below
        // the surrogates.
        assertThat(RustRegexp.compile(utf8Slice("(?s:.)+"), RustRegexp.Options.bytes()).classMatchCounter().estimatedRetainedSize()).isEqualTo(7_272);
        List<CompiledPattern> lastPlaneClasses = List.of(
                Re2.compile(utf8Slice("\\p{Mn}")),
                JavaRegexp.compile(utf8Slice("\\w"), JavaRegexp.Options.defaults().setUnicodeCharacterClasses(true)),
                RustRegexp.compile(utf8Slice("\\w")));
        for (CompiledPattern compiledPattern : lastPlaneClasses) {
            assertThat(compiledPattern.classMatchCounter()).as(compiledPattern.pattern().toStringUtf8()).isNull();
        }

        // A pattern that can match empty has no counter; its matcher keeps the engine.
        for (String pattern : List.of("\\pL*", "\\pL?", "\\pL{0,3}", "(?:\\pL|)", "[a-z]*", "[a-z]{0,3}")) {
            assertThat(Re2.compile(utf8Slice(pattern)).classMatchCounter()).as(pattern).isNull();
            assertThat(RustRegexp.compile(utf8Slice(pattern)).classMatchCounter()).as(pattern).isNull();
        }
        // A lazy repeat, a repeat compiled for longest match, and a pattern with a capture build no
        // counter and keep the engine, as on the compact route.
        Slice words = utf8Slice("the quiet tea");
        for (Re2 re2 : List.of(Re2.compile(utf8Slice("\\w+?")), Re2.compile(utf8Slice("\\w+"), Re2.Options.defaults().setLongestMatch(true)), Re2.compile(utf8Slice("(\\w)+")), Re2.compile(utf8Slice("(?<w>\\w)+")))) {
            assertThat(re2.classMatchCounter()).as(re2.pattern().toStringUtf8()).isNull();
            assertThat(re2.matcher(words, 0).characterClassCounterEnabledForDiagnostics()).as(re2.pattern().toStringUtf8()).isFalse();
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

        // A class repeat finds its runs the same way, and count shares its counter rather than
        // build its own.
        Re2 words = Re2.compile(utf8Slice("\\w+"));
        assertThat(words.boundedCharacterClassCounterIfComputedForDiagnostics()).isSameAs(words.classMatchCounter());
        RegexpMatcher wordMatcher = words.matcher(utf8Slice("the quiet tea, and_3 more"), 0);
        assertThat(wordMatcher.characterClassCounterEnabledForDiagnostics()).isTrue();
        assertThat(spans(wordMatcher)).hasSize(5);
        assertThat(words.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();

        // `\pL` has too many start bytes for the vector scan; a sparse class uses it when it can.
        assertThat(letters.classMatchCounter().scansMemberStartsWithVectorsForDiagnostics()).isFalse();
        for (CompiledPattern sparse : List.of(Re2.compile(utf8Slice("\\p{Greek}")), RustRegexp.compile(utf8Slice("\\d")))) {
            assertThat(sparse.classMatchCounter().scansMemberStartsWithVectorsForDiagnostics())
                    .as(sparse.pattern().toStringUtf8())
                    .isEqualTo(VectorSupport.isAvailable());
        }
    }

    @Test
    public void testFindsLongByteRunsWithVectorScan()
    {
        // A byte class that fits in four ranges of at most 128 bytes each reads a run past its
        // stepped prefix with a vector scan for the first nonmember. The runs end at a byte just
        // outside the class, or at the input end, at every length through 72 and at selected
        // lengths through 300, past a run cut at 256 bytes, after a gap that varies with the length.
        List<Slice> wordRuns = longRunInputs("aZ0_z9AbY8_m".getBytes(UTF_8), new byte[] {'/', ':', '@', '[', '`', '{', (byte) 0xC3, (byte) 0xFF});
        for (String pattern : List.of("[0-9A-Za-z_]{256}", "[0-9A-Za-z_]{40,200}", "\\w+")) {
            assertCounterMatcherAgrees("RE2", Re2::compile, pattern, false, wordRuns);
        }
        // Members from each of the four ranges, and stops between them.
        List<Slice> printableRuns = longRunInputs(new byte[] {'\t', 'a', 0x0B, ' ', '\r', '~', '0'}, new byte[] {'\n', '\f', 0x1F, 0x7F, (byte) 0xC3});
        assertCounterMatcherAgrees("RE2", Re2::compile, "[\\t\\x0B\\r -~]+", false, printableRuns);
        assertCounterMatcherAgrees("Java", JavaRegexp::compile, "\\w{130,}", false, wordRuns);
        assertCounterMatcherAgrees("Trino", source -> TrinoRegexp.compile(source).re2(), "[0-9A-Za-z_]{256}", false, wordRuns);
        assertCounterMatcherAgrees("Rust", source -> RustRegexp.compile(source, RustRegexp.Options.bytes().setUnicode(false)), "[0-9A-Za-z_]{256}", false, wordRuns);
        // A Latin-1 class reaches past ASCII, and two ranges of 128 bytes hold every byte.
        byte[] highMembers = {(byte) 0x80, (byte) 0xE9, (byte) 0xFF, (byte) 0xA0, (byte) 0xC3, (byte) 0x8D, (byte) 0xFE};
        List<Slice> highRuns = longRunInputs(highMembers, new byte[] {'\n', '\r', ',', 0x7F, 'a'});
        for (String pattern : List.of("[0-9A-Za-z_]{256}", "[^,]+", "[^\\r\\n]+")) {
            assertCounterMatcherAgrees("Latin-1", source -> Re2.compile(source, Re2.Options.latin1()), pattern, false, wordRuns);
        }
        for (String pattern : List.of("[^,]+", "[^\\r\\n]+", "[\\x80-\\xFF]+", "(?s).{2,}")) {
            assertCounterMatcherAgrees("Latin-1", source -> Re2.compile(source, Re2.Options.latin1()), pattern, false, highRuns);
        }
        assertCounterMatcherAgrees("Rust bytes", source -> RustRegexp.compile(source, RustRegexp.Options.bytes()), "(?-u:[^\\r\\n])+", false, highRuns);
        assertCounterMatcherAgrees("Rust bytes", source -> RustRegexp.compile(source, RustRegexp.Options.bytes()), "(?s-u:.)+", false, highRuns);

        for (CompiledPattern pattern : List.of(
                Re2.compile(utf8Slice("[0-9A-Za-z_]{256}")),
                Re2.compile(utf8Slice("[0-9A-Za-z_]{256}"), Re2.Options.latin1()),
                Re2.compile(utf8Slice("[\\t\\x0B\\r -~]+")),
                JavaRegexp.compile(utf8Slice("\\w+")),
                TrinoRegexp.compile(utf8Slice("\\w+")).re2(),
                RustRegexp.compile(utf8Slice("[0-9A-Za-z_]{256}"), RustRegexp.Options.bytes().setUnicode(false)))) {
            assertThat(pattern.classMatchCounter().scansRunEndsWithVectorsForDiagnostics()).as(pattern.pattern().toStringUtf8()).isEqualTo(VectorSupport.isAvailable());
        }
        // Five ranges, members past ASCII in UTF-8, or a maximum within the stepped prefix keep the
        // table loop.
        assertThat(Re2.compile(utf8Slice("[\\-0-9A-Za-z_]+")).classMatchCounter().scansRunEndsWithVectorsForDiagnostics()).isFalse();
        assertThat(Re2.compile(utf8Slice("\\pL+")).classMatchCounter().scansRunEndsWithVectorsForDiagnostics()).isFalse();
        assertThat(Re2.compile(utf8Slice("[A-Za-z]{8,13}")).classMatchCounter().scansRunEndsWithVectorsForDiagnostics()).isFalse();
    }

    // Runs of the members, repeated in order, at every length up to 72 and at selected lengths
    // up to 300, each ended by every stop byte or by the input end, after a gap of spaces.
    private static List<Slice> longRunInputs(byte[] members, byte[] stops)
    {
        List<Slice> inputs = new ArrayList<>();
        for (int runLength = 0; runLength <= 300; runLength++) {
            if (runLength > 72 && runLength % 31 != 0 && Math.abs(runLength - 256) > 3 && Math.abs(runLength - 128) > 2) {
                continue;
            }
            int gap = runLength % 17;
            for (int stop = -1; stop < stops.length; stop++) {
                byte[] bytes = new byte[gap + runLength + (stop == -1 ? 0 : 1 + runLength)];
                Arrays.fill(bytes, 0, gap, (byte) ' ');
                for (int index = 0; index < runLength; index++) {
                    bytes[gap + index] = members[index % members.length];
                    if (stop != -1) {
                        bytes[gap + runLength + 1 + index] = members[index % members.length];
                    }
                }
                if (stop != -1) {
                    bytes[gap + runLength] = stops[stop];
                }
                inputs.add(Slices.wrappedBuffer(bytes));
            }
        }
        return inputs;
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

        // A UTF-8 class of two-byte members counts runs a 32- or 64-byte block at a time, so each
        // run of every length up to 70 characters, cut or unbounded, starts at every offset of a
        // block and crosses into the next block or more; a byte class, counted byte by byte, takes
        // the same runs. A second run ends at the end of the input.
        for (String pattern : List.of("[a-z]{2,5}", "[a-z]{3,}", "\\p{L}{2,5}", "\\p{L}{3,}")) {
            BoundedCharacterClassCounter counter = Re2.compile(utf8Slice(pattern)).createBoundedCharacterClassCounter();
            Re2 reference = Re2.compile(utf8Slice("(" + pattern + ")"));
            String member = counter.readsBytesForDiagnostics() ? "a" : "ж";
            for (int offset = 0; offset <= Long.SIZE; offset++) {
                for (int runLength = 0; runLength <= 70; runLength++) {
                    Slice input = utf8Slice(" ".repeat(offset) + member.repeat(runLength) + " " + member.repeat(runLength));
                    assertThat(counter.count(input))
                            .as("pattern %s offset %s run length %s", pattern, offset, runLength)
                            .isEqualTo(countWithGeneralMatcher(reference, input));
                }
            }
        }
    }

    @Test
    public void testMatchesGeneralMatcher()
    {
        for (String pattern : List.of("[a-z]{2,4}", "[0-9]{1,3}", "\\p{L}{2,5}", "[^x]{3,7}", "\\p{L}+", "[a-z]{2,}", "(?s:.){2,3}")) {
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

        // A bare continuation byte that begins a block's second chunk, after a first chunk ending in
        // ASCII, is malformed and ends the run rather than continuing a character.
        byte[] chunkBoundary = new byte[1 + 64];
        Arrays.fill(chunkBoundary, (byte) 'g');
        chunkBoundary[0] = (byte) 0x80;
        chunkBoundary[1 + 32] = (byte) 0x80;
        Slice continuationAfterChunk = Slices.wrappedBuffer(chunkBoundary, 1, 64);
        assertThat(Re2.compile(utf8Slice("\\p{L}+")).createBoundedCharacterClassCounter().count(continuationAfterChunk))
                .isEqualTo(countWithGeneralMatcher(Re2.compile(utf8Slice("(\\p{L}+)")), continuationAfterChunk))
                .isEqualTo(2);

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
    public void testTwoByteTextMatchesGeneralMatcherAtEveryPosition()
    {
        assertTwoByteTextMatchesGeneralMatcherAtEveryPosition();
    }

    // Also run by BoundedCharacterClassCounterFormProbe, once for each form of `countUtf8`.
    static void assertTwoByteTextMatchesGeneralMatcherAtEveryPosition()
    {
        // Cyrillic and ASCII text counts without decoding, a chunk at a time. Each nonempty insert,
        // at every position, puts another character or a malformed sequence in some chunk, or
        // splits a two-byte character: U+0100, the one character `[^\x{100}]` lacks, a three-byte
        // and a four-byte character, a lone continuation byte, the overlong leads C0 and C1, and a
        // lead byte, which at the end is truncated; the empty insert checks the text alone.
        byte[] text = "Съешь же ещё этих мягких французских булок, да выпей чаю. ".getBytes(UTF_8);
        List<byte[]> inserts = List.of(
                new byte[0],
                "Ā".getBytes(UTF_8),
                "€".getBytes(UTF_8),
                "😀".getBytes(UTF_8),
                new byte[] {(byte) 0x80},
                new byte[] {(byte) 0xC0, (byte) 0x80},
                new byte[] {(byte) 0xC1, (byte) 0xBF},
                new byte[] {(byte) 0xD0});
        // Classes whose bitset reaches past U+07FF, ends below it and is extended, holds every
        // non-ASCII character, or has a tail starting at U+0101, with runs cut at the maximum or
        // unbounded.
        for (String pattern : List.of("\\p{L}{2,5}", "[а-яё]{2,4}", "[^x]{3,7}", "\\p{L}+", ALL_NON_ASCII_CLASS + "{1,3}", "[^\\x{100}]{2,4}")) {
            BoundedCharacterClassCounter counter = Re2.compile(utf8Slice(pattern)).createBoundedCharacterClassCounter();
            Re2 reference = Re2.compile(utf8Slice("(" + pattern + ")"));
            for (byte[] insert : inserts) {
                for (int position = 0; position <= text.length; position++) {
                    // A leading continuation byte outside the slice keeps the backing array's offset nonzero.
                    byte[] bytes = new byte[1 + text.length + insert.length];
                    bytes[0] = (byte) 0x80;
                    System.arraycopy(text, 0, bytes, 1, position);
                    System.arraycopy(insert, 0, bytes, 1 + position, insert.length);
                    System.arraycopy(text, position, bytes, 1 + position + insert.length, text.length - position);
                    Slice input = Slices.wrappedBuffer(bytes, 1, bytes.length - 1);
                    assertThat(counter.count(input))
                            .as("pattern %s insert %s at %s", pattern, HexFormat.of().formatHex(insert), position)
                            .isEqualTo(countWithGeneralMatcher(reference, input));
                }
            }
        }
    }

    @Test
    public void testLongerCharactersMatchGeneralMatcherAcrossDecodeStretches()
    {
        // Text of three- and four-byte characters fails every chunk check, so it decodes in
        // stretches that double until the rest of the input is handed to one decoding loop, here
        // with Cyrillic text after it. An emoji every 84 bytes of Latin text fails a check and then
        // passes one, starting the stretches over. Cyrillic before CJK enters the stretches from
        // blocks of two-byte characters. Runs cross every stretch boundary.
        String cjk = "汉字测试文本 ".repeat(150);
        String cyrillic = "Съешь же ещё этих мягких французских булок. ".repeat(20);
        String latin = ("the quick brown fox jumps over the lazy dog, then naps in the sun all afternoon " + "😀").repeat(40);
        for (String pattern : List.of("\\p{L}{2,5}", "\\p{L}+", "[^x]{3,7}")) {
            BoundedCharacterClassCounter counter = Re2.compile(utf8Slice(pattern)).createBoundedCharacterClassCounter();
            Re2 reference = Re2.compile(utf8Slice("(" + pattern + ")"));
            List<String> texts = List.of(cjk + cyrillic, latin, cyrillic + cjk + latin);
            for (int textIndex = 0; textIndex < texts.size(); textIndex++) {
                for (int offset = 0; offset < 4; offset++) {
                    Slice input = utf8Slice("a".repeat(offset) + texts.get(textIndex));
                    assertThat(counter.count(input))
                            .as("pattern %s text %s offset %s", pattern, textIndex, offset)
                            .isEqualTo(countWithGeneralMatcher(reference, input));
                }
            }
        }
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
        for (CompiledPattern compiledPattern : List.of(
                Re2.compile(utf8Slice(pattern)),
                JavaRegexp.compile(utf8Slice(pattern)),
                TrinoRegexp.compile(utf8Slice(pattern)).re2(),
                RustRegexp.compile(utf8Slice(pattern)),
                RustRegexp.compile(utf8Slice(pattern), RustRegexp.Options.bytes()))) {
            BoundedCharacterClassCounter counter = compiledPattern.createBoundedCharacterClassCounter();
            assertThat(counter.readsBytesForDiagnostics()).isTrue();
            assertThat(counter.count(input)).isEqualTo(countWithGeneralMatcher(compiledPattern, input));
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
    private static void assertCounterMatcherAgrees(String language, Function<Slice, CompiledPattern> compiler, String pattern, boolean compact, List<Slice> inputs)
    {
        String description = language + " " + pattern;
        CompiledPattern counted = compiler.apply(utf8Slice(pattern));
        CompiledPattern reference = compiler.apply(utf8Slice("(" + pattern + ")"));
        assertThat(counted.classMatchCounter()).as(description).isNotNull();
        assertThat(counted.usesCompactBoundedCharacterClassForDiagnostics()).as(description).isEqualTo(compact);
        assertThat(reference.classMatchCounter()).as(description).isNull();
        for (Slice input : inputs) {
            String hex = HexFormat.of().formatHex(input.getBytes());
            assertThat(spans(counted.matcher(input, 0)))
                    .as("%s on %s", description, hex)
                    .isEqualTo(spans(reference.matcher(input, 0)));
            if (compact) {
                assertThat(anchoredSpans(counted, input, 0, input.length()))
                        .as("%s anchored on %s", description, hex)
                        .isEqualTo(anchoredSpans(reference, input, 0, input.length()));
            }
            if (input.length() >= 5) {
                assertThat(spans(counted.matcher(input, 0).reset(input, 3, input.length() - 2)))
                        .as("%s on a region of %s", description, hex)
                        .isEqualTo(spans(reference.matcher(input, 0).reset(input, 3, input.length() - 2)));
                if (compact) {
                    assertThat(anchoredSpans(counted, input, 3, input.length() - 2))
                            .as("%s anchored on a region of %s", description, hex)
                            .isEqualTo(anchoredSpans(reference, input, 3, input.length() - 2));
                }
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

    // The lookingAt and matches answers over `[start, end)`: the boolean routes over the region
    // sliced out, then the matcher's spans over the region, or -1 for no match.
    private static List<Object> anchoredSpans(CompiledPattern pattern, Slice input, int start, int end)
    {
        Slice region = input.slice(start, end - start);
        RegexpMatcher matcher = pattern.matcher(input, 0).reset(input, start, end);
        long lookingAt = matcher.lookingAt() ? packSpan(matcher.start(), matcher.end()) : -1;
        long matches = matcher.matches() ? packSpan(matcher.start(), matcher.end()) : -1;
        return List.of(pattern.lookingAt(region), pattern.matches(region), lookingAt, matches);
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

    private static long countWithGeneralMatcher(CompiledPattern compiledPattern, Slice input)
    {
        RegexpMatcher matcher = compiledPattern.genericGroupZeroMatcherForDiagnostics(input);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
