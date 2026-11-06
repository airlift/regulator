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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.airlift.slice.Slices.utf8Slice;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/// Many threads search shared patterns, in whole texts and in matcher regions, while two other
/// threads reset the shared DFA caches, so readers run on tables a reset has superseded. Every
/// result must equal the result computed single-threaded before the stress. Duration from
/// `regulator.stress.seconds` (default 5).
public class TestDfaCopyOnResetStress
{
    private static final String[] PATTERNS = {
            "the",
            "\\w+\\s+Holmes",
            "\\w+\\s+Holmes\\s+\\w+",
            "\\s[a-zA-Z]{0,12}ing\\s",
            "(?:ASIA|AKIA|AROA|AIDA)[A-Z0-7]{16}",
            "\\b\\w{9,}\\b",
            "[a-z]+ing|Sherlock|Watson",
            "^[ \\t]*#.*?coding[:=][ \\t]*utf-?8",
            "([0-9][0-9]?)/([0-9][0-9]?)/([0-9][0-9]([0-9][0-9])?)",
            "[\"'][^\"']{0,30}[?!.][\"']",
    };
    // Enough capturing branches that RE2 picks a capture's branch with a searchMany DFA.
    private static final String TAGGED_ALTERNATION = Stream.concat(
                    Stream.of("Sherlock", "Holmes", "\\w+ing", "[ab]*a[ab]{12}c", "[ab]*b[ab]{12}d", "\\w+"),
                    IntStream.range(0, 90).mapToObj(index -> "unreachable" + index + "z"))
            .map(branch -> "(" + branch + ")")
            .collect(joining("|"));
    private static final long[] RUST_BUDGETS = {8L << 20, 64L << 10};
    private static final String[] WORDS = {
            "the", "The", "Holmes", "Sherlock", "Watson", "singing", "walking", "AKIAABCDEFGHIJKLMNOP", "said",
            "12/31/1999", "\"Why?\"", "'No!'", "# coding: utf-8", "extraordinarily", "\n", "  ", "a", "of", "ing",
    };

    @Test
    public void testReadersAgainstCacheResets()
            throws Exception
    {
        long seconds = Long.getLong("regulator.stress.seconds", 5);
        List<Slice> texts = texts();
        List<Case> cases = new ArrayList<>();
        // Programs whose caches a writer thread resets directly.
        List<Dfa.DfaInstance> resettable = new ArrayList<>();
        Map<Long, Integer> rustCasesByBudget = new TreeMap<>();
        for (String pattern : PATTERNS) {
            for (long maxMemory : RUST_BUDGETS) {
                RustRegexp regexp;
                try {
                    regexp = RustRegexp.compile(utf8Slice(pattern), RustRegexp.Options.defaults().setMaxMemory(maxMemory));
                }
                catch (RegexpCompileMemoryLimitException e) {
                    continue;
                }
                cases.add(Case.of("Rust " + pattern + " with " + maxMemory + " bytes", text -> apiResults(regexp, text), texts));
                rustCasesByBudget.merge(maxMemory, 1, Integer::sum);
                addCachedDfas(resettable, regexp.forwardProgramForDiagnostics());
                addCachedDfas(resettable, regexp.reverseProgramIfComputedForDiagnostics());
                // The inner literal search reads both of these DFAs in one pass.
                InnerLiteralSearch innerLiteralSearch = regexp.innerLiteralSearchForDiagnostics();
                if (innerLiteralSearch != null) {
                    resettable.add(innerLiteralSearch.reversePrefixProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH));
                    resettable.add(innerLiteralSearch.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH));
                }
            }
        }
        assertThat(rustCasesByBudget).as("Rust cases by budget").containsOnlyKeys(RUST_BUDGETS[0], RUST_BUDGETS[1]);
        assertThat(resettable).as("inner literal search DFAs").isNotEmpty();
        for (String pattern : PATTERNS) {
            Regexp regexp = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();
            Prog program = Compiler.compile(regexp, false, 0);
            Prog reversed = Compiler.compile(regexp, true, 0);
            cases.add(Case.of("RE2 program " + pattern, text -> searchResults(program, reversed, text), texts));
            addCachedDfas(resettable, program);
            addCachedDfas(resettable, reversed);
        }
        Re2 tagged = Re2.compile(utf8Slice(TAGGED_ALTERNATION));
        assertThat(tagged.usesTaggedAlternationForDiagnostics()).as("tagged alternation").isTrue();
        cases.add(Case.of("RE2 tagged alternation", text -> matcherResults(tagged.matcher(text), text), texts));
        // The capture's branch comes from the searchMany DFA, not from a capture engine fallback.
        Slice routeText = texts.get(9);
        RegexpMatcher route = tagged.matcher(routeText);
        matcherResults(route, routeText);
        assertThat(route.bitStateWorkspaceInitializedForDiagnostics()).as("tagged alternation BitState fallback").isFalse();
        assertThat(route.nfaWorkspaceInitializedForDiagnostics()).as("tagged alternation NFA fallback").isFalse();
        resettable.add(tagged.taggedAlternationProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.MANY_MATCH));
        addCachedDfas(resettable, tagged.forwardProgramForDiagnostics());
        addCachedDfas(resettable, tagged.reverseProgramIfComputedForDiagnostics());

        int readers = Math.max(4, Runtime.getRuntime().availableProcessors());
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong checks = new AtomicLong();
        AtomicLong resets = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(readers + 2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < readers; thread++) {
                long seed = thread;
                futures.add(executor.submit(() -> {
                    Random random = new Random(seed);
                    while (!stop.get()) {
                        Case testCase = cases.get(random.nextInt(cases.size()));
                        int textIndex = random.nextInt(texts.size());
                        testCase.verify(texts.get(textIndex), textIndex);
                        checks.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (int writer = 0; writer < 2; writer++) {
                long seed = 100L + writer;
                futures.add(executor.submit(() -> {
                    Random random = new Random(seed);
                    while (!stop.get()) {
                        resettable.get(random.nextInt(resettable.size())).resetCacheExternal();
                        resets.incrementAndGet();
                        Thread.onSpinWait();
                    }
                    return null;
                }));
            }
            Thread.sleep(Duration.ofSeconds(seconds));
            stop.set(true);
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        finally {
            stop.set(true);
            executor.shutdownNow();
        }
        System.err.printf("copy-on-reset stress: %d checks, %d external resets, %d cases%n", checks.get(), resets.get(), cases.size());
        assertThat(checks.get()).isPositive();
        assertThat(resets.get()).isPositive();
    }

    private static void addCachedDfas(List<Dfa.DfaInstance> resettable, Prog program)
    {
        if (program == null) {
            return;
        }
        for (Dfa.DfaInstance.Kind kind : new Dfa.DfaInstance.Kind[] {Dfa.DfaInstance.Kind.FIRST_MATCH, Dfa.DfaInstance.Kind.LONGEST_MATCH}) {
            Dfa.DfaInstance dfa = program.getCachedDfa(kind);
            if (dfa != null) {
                resettable.add(dfa);
            }
        }
    }

    private static List<Slice> texts()
    {
        Random random = new Random(42);
        List<Slice> texts = new ArrayList<>();
        for (int length : new int[] {0, 7, 40, 300, 5_000, 70_000}) {
            for (int copy = 0; copy < 3; copy++) {
                StringBuilder text = new StringBuilder();
                while (text.length() < length) {
                    text.append(WORDS[random.nextInt(WORDS.length)]).append(random.nextInt(4) == 0 ? "" : " ");
                }
                texts.add(utf8Slice(text.substring(0, Math.min(length, text.length()))));
            }
        }
        // Long runs that a many-state tagged branch matches end to end.
        for (int copy = 0; copy < 3; copy++) {
            StringBuilder text = new StringBuilder();
            while (text.length() < 5_000) {
                for (int run = 20 + random.nextInt(1_000); run > 0; run--) {
                    text.append(random.nextBoolean() ? 'a' : 'b');
                }
                text.append("cd ".charAt(random.nextInt(3)));
            }
            texts.add(utf8Slice(text.toString()));
        }
        return texts;
    }

    private static long[] apiResults(RustRegexp regexp, Slice text)
    {
        long[] matches = matcherResults(regexp.matcher(text, 0), text);
        long[] results = new long[matches.length + 2];
        results[0] = regexp.find(text) ? 1 : 0;
        results[1] = regexp.count(text);
        System.arraycopy(matches, 0, results, 2, matches.length);
        return results;
    }

    /// Every match in the text, then every match in the middle half of it as a matcher region.
    /// Each match records its bounds and its lowest participating retained group, or zero.
    private static long[] matcherResults(RegexpMatcher matcher, Slice text)
    {
        List<Long> values = new ArrayList<>();
        int length = text.length();
        for (int[] region : new int[][] {{0, length}, {length / 4, length - length / 4}}) {
            matcher.reset(text, region[0], region[1]);
            values.add(-1L);
            while (matcher.find()) {
                values.add((long) matcher.start() << 32 | matcher.end());
                int group = 1;
                while (group <= matcher.groupCount() && !matcher.matched(group)) {
                    group++;
                }
                values.add(group > matcher.groupCount() ? 0L : group);
            }
        }
        return values.stream().mapToLong(Long::longValue).toArray();
    }

    private static long[] searchResults(Prog program, Prog reversed, Slice text)
    {
        int length = text.length();
        int[] starts = {0, length / 3, length / 2, Math.max(0, length - 5)};
        long[] results = new long[starts.length * 4];
        int i = 0;
        for (int start : starts) {
            results[i++] = Dfa.search(program, text, 0, length, start, length, false, Prog.MatchKind.FIRST_MATCH, true);
            results[i++] = Dfa.search(program, text, 0, length, start, length, false, Prog.MatchKind.LONGEST_MATCH, true);
            results[i++] = Dfa.search(program, text, 0, length, start, length, true, Prog.MatchKind.LONGEST_MATCH, true);
            results[i++] = Dfa.search(reversed, text, 0, length, 0, length - start, false, Prog.MatchKind.LONGEST_MATCH, true);
        }
        return results;
    }

    private record Case(String label, Function<Slice, long[]> results, List<long[]> expected)
    {
        static Case of(String label, Function<Slice, long[]> results, List<Slice> texts)
        {
            return new Case(label, results, texts.stream().map(results).toList());
        }

        void verify(Slice text, int textIndex)
        {
            long[] actual = results.apply(text);
            assertThat(actual)
                    .as("%s on text %d", label, textIndex)
                    .isEqualTo(expected.get(textIndex));
        }
    }
}
