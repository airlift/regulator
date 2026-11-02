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
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestBoundedCharacterClassCounter
{
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

        Re2 compactOnly = Re2.compile(utf8Slice("\\p{Greek}+"), Re2.Options.defaults().setMaxMemory(256L << 10));
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
    public void testCompactRouteHonorsMemoryLimit()
    {
        assertThatThrownBy(() -> Re2.compile(
                utf8Slice("\\p{L}{256}"),
                Re2.Options.defaults().setMaxMemory(128 * 1024)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
        assertThat(Re2.compile(
                        utf8Slice("\\p{L}{256}"),
                        Re2.Options.defaults().setMaxMemory(256 * 1024))
                .usesCompactBoundedCharacterClassForDiagnostics()).isTrue();
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
            BoundedCharacterClassCounter matcher = re2.createBoundedCharacterClassCounter();
            assertThat(matcher).as(pattern).isNotNull();
            for (String input : List.of("", "a", "abc12defgh", "абв гдеёж 123", "xxxxxxxx")) {
                assertThat(matcher.count(utf8Slice(input)))
                        .as("pattern %s input %s", pattern, input)
                        .isEqualTo(countWithGeneralMatcher(re2, utf8Slice(input)));
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
        RegexpMatcher matcher = re2.groupZeroMatcher(input, null);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
