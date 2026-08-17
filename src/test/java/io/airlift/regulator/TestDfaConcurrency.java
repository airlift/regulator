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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

// Project-authored concurrency coverage extending the ported upstream DFA tests.
public class TestDfaConcurrency
{
    private static final int THREADS = 8;

    @Test
    public void testMultiplePatterns()
            throws InterruptedException, ExecutionException
    {
        String[] patterns = {
                "hello",
                "world",
                "[a-z]+",
                "\\d+",
                "(foo|bar)+",
                "a*b+c?",
        };

        Prog[] programs = new Prog[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            ParseResult parsed = RegexpParser.parse(
                    Slices.wrappedBuffer(patterns[i].getBytes(UTF_8)),
                    Regexp.LIKE_PERL);
            programs[i] = Compiler.compile(parsed.regexp(), false, 0);
        }

        String[] texts = {"hello world", "foobarfoo", "abc123xyz", "aaabbc"};
        Slice[] textSlices = new Slice[texts.length];
        for (int i = 0; i < texts.length; i++) {
            textSlices[i] = Slices.wrappedBuffer(texts[i].getBytes(UTF_8));
        }

        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(THREADS * 2);
        try {
            List<Future<?>> futures = new ArrayList<>();

            // Submit many concurrent searches
            for (int i = 0; i < 100; i++) {
                int patternIndex = i % patterns.length;
                int textIndex = i % textSlices.length;
                Prog program = programs[patternIndex];
                Slice text = textSlices[textIndex];

                futures.add(executor.submit(() -> {
                    Dfa.search(program, text, false, Prog.MatchKind.FIRST_MATCH, true);
                    successCount.incrementAndGet();
                }));
            }

            // Wait for all to complete
            for (Future<?> future : futures) {
                future.get();
            }

            assertThat(successCount.get()).isEqualTo(100);
        }
        finally {
            executor.shutdown();
        }
    }

    @Test
    public void testConcurrentCacheReset()
            throws Exception
    {
        String pattern = "0[01]{8}$";
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(UTF_8)), Regexp.LIKE_PERL);
        Prog program = Compiler.compile(parsed.regexp(), false, 0);

        Slice match = Slices.wrappedBuffer((deBruijnString(8) + "0").getBytes(UTF_8));
        Slice noMatch = Slices.wrappedBuffer(deBruijnString(8).getBytes(UTF_8));
        assertSearchResults(program, match, noMatch);

        Dfa.DfaInstance dfaInstance = program.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS + 1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                futures.add(executor.submit(() -> {
                    for (int iteration = 0; iteration < 100; iteration++) {
                        assertSearchResults(program, match, noMatch);
                    }
                }));
            }
            futures.add(executor.submit(() -> {
                for (int iteration = 0; iteration < 100; iteration++) {
                    dfaInstance.resetCacheExternal();
                }
            }));

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testVirtualThreadSearchesDuringCacheReset()
            throws Exception
    {
        String pattern = "0[01]{8}$";
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(UTF_8)), Regexp.LIKE_PERL);
        Prog program = Compiler.compile(parsed.regexp(), false, 0);

        Slice match = Slices.wrappedBuffer((deBruijnString(8) + "0").getBytes(UTF_8));
        Slice noMatch = Slices.wrappedBuffer(deBruijnString(8).getBytes(UTF_8));
        assertSearchResults(program, match, noMatch);

        Dfa.DfaInstance dfaInstance = program.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH);
        int initialResetCount = dfaInstance.resetCount();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int task = 0; task < 5_000; task++) {
                if (task % 100 == 0) {
                    futures.add(executor.submit(dfaInstance::resetCacheExternal));
                    continue;
                }

                boolean shouldMatch = (task & 1) == 0;
                Slice text = shouldMatch ? match : noMatch;
                futures.add(executor.submit(() -> {
                    boolean matched = Dfa.search(program, text, false, Prog.MatchKind.FIRST_MATCH, true) >= 0;
                    if (matched != shouldMatch) {
                        throw new AssertionError("unexpected virtual-thread search result");
                    }
                }));
            }

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(dfaInstance.resetCount()).isGreaterThan(initialResetCount);
    }

    @Test
    public void testConcurrentFusedPrefixSearch()
            throws Exception
    {
        try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
            assertConcurrentFusedPrefixSearch(executor, 256);
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertConcurrentFusedPrefixSearch(executor, 1_000);
        }
    }

    private static void assertConcurrentFusedPrefixSearch(ExecutorService executor, int taskCount)
            throws Exception
    {
        String prefix = "Шерлок Холмс";
        String rejectedCandidate = prefix + "x ";
        String leadingText = "x".repeat(1_100) + rejectedCandidate;
        Slice matchingText = Slices.utf8Slice(leadingText + prefix + "123");
        Slice nonMatchingText = Slices.utf8Slice(leadingText + prefix + "x");
        int expectedStart = leadingText.getBytes(UTF_8).length;
        int expectedEnd = matchingText.length();

        Re2 firstMatch = Re2.compile(Slices.utf8Slice(prefix + "([0-9]+)"));
        Re2 longestMatch = Re2.compile(
                Slices.utf8Slice(prefix + "([0-9]+)"),
                Re2.Options.defaults().setLongestMatch(true));

        List<Future<?>> futures = new ArrayList<>();
        for (int task = 0; task < taskCount; task++) {
            int taskNumber = task;
            futures.add(executor.submit(() -> {
                boolean shouldMatch = (taskNumber & 1) == 0;
                Re2 pattern = (taskNumber & 2) == 0 ? firstMatch : longestMatch;
                MatchResult result = pattern.findResult(shouldMatch ? matchingText : nonMatchingText);
                if (!shouldMatch) {
                    assertThat(result).isNull();
                    return;
                }
                assertThat(result).isNotNull();
                assertThat(result.start(0)).isEqualTo(expectedStart);
                assertThat(result.end(0)).isEqualTo(expectedEnd);
                assertThat(result.groupSlice(1).toStringUtf8()).isEqualTo("123");
            }));
        }

        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
    }

    private static void assertSearchResults(Prog program, Slice match, Slice noMatch)
    {
        for (int i = 0; i < 2; i++) {
            long matchResult = Dfa.search(program, match, false, Prog.MatchKind.FIRST_MATCH, true);
            assertThat(matchResult >= 0).as("should match").isTrue();

            long noMatchResult = Dfa.search(program, noMatch, false, Prog.MatchKind.FIRST_MATCH, true);
            assertThat(noMatchResult >= 0).as("should not match").isFalse();
        }
    }

    /**
     * Generates a De Bruijn string for the binary alphabet {0, 1}.
     * The De Bruijn string B(2,n) contains every n-bit binary string as a substring exactly once.
     *
     * Ported from RE2 re2/testing/string_generator.cc DeBruijnString().
     */
    private static String deBruijnString(int n)
    {
        if (n < 1 || n > 29) {
            throw new IllegalArgumentException("n must be between 1 and 29");
        }

        int size = 1 << n;
        int mask = size - 1;
        boolean[] did = new boolean[size];

        StringBuilder s = new StringBuilder(n + size);

        // Start with n-1 zeros
        for (int i = 0; i < n - 1; i++) {
            s.append('0');
        }

        int bits = 0;
        for (int i = 0; i < size; i++) {
            bits <<= 1;
            bits &= mask;
            if (!did[bits | 1]) {
                bits |= 1;
                s.append('1');
            }
            else {
                s.append('0');
            }
            if (did[bits]) {
                throw new AssertionError("De Bruijn invariant violated");
            }
            did[bits] = true;
        }

        if (s.length() != (n - 1) + size) {
            throw new AssertionError("De Bruijn string length mismatch");
        }

        return s.toString();
    }
}
