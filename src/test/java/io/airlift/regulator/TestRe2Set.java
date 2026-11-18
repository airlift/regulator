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

import com.sun.management.ThreadMXBean;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Ported from upstream RE2: re2/testing/set_test.cc.
public class TestRe2Set
{
    private static Slice toSlice(String value)
    {
        return Slices.utf8Slice(value);
    }

    @Test
    public void testUnanchored()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);

        assertThat(builder.size()).isEqualTo(0);
        assertThat(builder.add(Slices.utf8Slice("foo"))).isEqualTo(0);
        assertThat(builder.size()).isEqualTo(1);
        assertThatThrownBy(() -> builder.add(Slices.utf8Slice("(")))
                .isInstanceOf(RegexpParseException.class);
        assertThat(builder.size()).isEqualTo(1);
        assertThat(builder.add(Slices.utf8Slice("bar"))).isEqualTo(1);
        assertThat(builder.size()).isEqualTo(2);
        Re2Set s = builder.build();
        assertThat(s.size()).isEqualTo(2);

        assertThat(match(s, toSlice("foobar"))).isTrue();
        assertThat(match(s, toSlice("fooba"))).isTrue();
        assertThat(match(s, toSlice("oobar"))).isTrue();

        List<Integer> v = new ArrayList<>();
        assertThat(match(s, toSlice("foobar"), v)).isTrue();
        assertThat(v).hasSize(2);
        assertThat(v).containsExactlyInAnyOrder(0, 1);

        assertThat(match(s, toSlice("fooba"), v)).isTrue();
        assertThat(v).hasSize(1);
        assertThat(v).containsExactly(0);

        assertThat(match(s, toSlice("oobar"), v)).isTrue();
        assertThat(v).hasSize(1);
        assertThat(v).containsExactly(1);
    }

    @Test
    public void testUnanchoredFactored()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);

        assertThat(builder.add(Slices.utf8Slice("foo"))).isEqualTo(0);
        assertThatThrownBy(() -> builder.add(Slices.utf8Slice("(")))
                .isInstanceOf(RegexpParseException.class);
        assertThat(builder.add(Slices.utf8Slice("foobar"))).isEqualTo(1);
        Re2Set s = builder.build();

        assertThat(match(s, toSlice("foobar"))).isTrue();
        assertThat(match(s, toSlice("obarfoobaroo"))).isTrue();
        assertThat(match(s, toSlice("fooba"))).isTrue();
        assertThat(match(s, toSlice("oobar"))).isFalse();

        List<Integer> v = new ArrayList<>();
        assertThat(match(s, toSlice("foobar"), v)).isTrue();
        assertThat(v).hasSize(2);
        assertThat(v).containsExactlyInAnyOrder(0, 1);

        assertThat(match(s, toSlice("obarfoobaroo"), v)).isTrue();
        assertThat(v).hasSize(2);
        assertThat(v).containsExactlyInAnyOrder(0, 1);

        assertThat(match(s, toSlice("fooba"), v)).isTrue();
        assertThat(v).hasSize(1);
        assertThat(v).containsExactly(0);

        assertThat(match(s, toSlice("oobar"), v)).isFalse();
        assertThat(v).isEmpty();
    }

    @Test
    public void testUnanchoredDollar()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);

        assertThat(builder.add(Slices.utf8Slice("foo$"))).isEqualTo(0);
        Re2Set s = builder.build();

        assertThat(match(s, toSlice("foo"))).isTrue();
        assertThat(match(s, toSlice("foobar"))).isFalse();

        List<Integer> v = new ArrayList<>();
        assertThat(match(s, toSlice("foo"), v)).isTrue();
        assertThat(v).hasSize(1);
        assertThat(v).containsExactly(0);

        assertThat(match(s, toSlice("foobar"), v)).isFalse();
        assertThat(v).isEmpty();
    }

    @Test
    public void testUnanchoredWordBoundary()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);

        assertThat(builder.add(Slices.utf8Slice("foo\\b"))).isEqualTo(0);
        Re2Set s = builder.build();

        assertThat(match(s, toSlice("foo"))).isTrue();
        assertThat(match(s, toSlice("foobar"))).isFalse();
        assertThat(match(s, toSlice("foo bar"))).isTrue();

        List<Integer> v = new ArrayList<>();
        assertThat(match(s, toSlice("foo"), v)).isTrue();
        assertThat(v).hasSize(1);
        assertThat(v).containsExactly(0);

        assertThat(match(s, toSlice("foobar"), v)).isFalse();
        assertThat(v).isEmpty();

        assertThat(match(s, toSlice("foo bar"), v)).isTrue();
        assertThat(v).hasSize(1);
        assertThat(v).containsExactly(0);
    }

    @Test
    public void testAnchored()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FULL);

        assertThat(builder.add(Slices.utf8Slice("foo"))).isEqualTo(0);
        assertThatThrownBy(() -> builder.add(Slices.utf8Slice("(")))
                .isInstanceOf(RegexpParseException.class);
        assertThat(builder.add(Slices.utf8Slice("bar"))).isEqualTo(1);
        Re2Set s = builder.build();

        assertThat(match(s, toSlice("foobar"))).isFalse();
        assertThat(match(s, toSlice("fooba"))).isFalse();
        assertThat(match(s, toSlice("oobar"))).isFalse();
        assertThat(match(s, toSlice("foo"))).isTrue();
        assertThat(match(s, toSlice("bar"))).isTrue();

        List<Integer> v = new ArrayList<>();
        assertThat(match(s, toSlice("foobar"), v)).isFalse();
        assertThat(v).isEmpty();

        assertThat(match(s, toSlice("fooba"), v)).isFalse();
        assertThat(v).isEmpty();

        assertThat(match(s, toSlice("oobar"), v)).isFalse();
        assertThat(v).isEmpty();

        assertThat(match(s, toSlice("foo"), v)).isTrue();
        assertThat(v).hasSize(1);
        assertThat(v).containsExactly(0);

        assertThat(match(s, toSlice("bar"), v)).isTrue();
        assertThat(v).hasSize(1);
        assertThat(v).containsExactly(1);
    }

    @Test
    public void testLookingAt()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.LOOKING_AT);
        assertThat(builder.add(Slices.utf8Slice("foo"))).isEqualTo(0);
        Re2Set set = builder.build();

        assertThat(set.matchesAny(toSlice("foobar"))).isTrue();
        assertThat(set.matchesAny(toSlice("xfoo"))).isFalse();
        assertThat(set.matchingPatternIds(toSlice("foobar"))).containsExactly(0);
        assertThat(set.matchingPatternIds(toSlice("xfoo"))).isEmpty();
    }

    @Test
    public void testEmptyUnanchored()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);

        Re2Set s = builder.build();

        assertThat(match(s, toSlice(""))).isFalse();
        assertThat(match(s, toSlice("foobar"))).isFalse();

        List<Integer> v = new ArrayList<>();
        assertThat(match(s, toSlice(""), v)).isFalse();
        assertThat(v).isEmpty();

        assertThat(match(s, toSlice("foobar"), v)).isFalse();
        assertThat(v).isEmpty();
    }

    @Test
    public void testEmptyAnchored()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FULL);

        Re2Set s = builder.build();

        assertThat(match(s, toSlice(""))).isFalse();
        assertThat(match(s, toSlice("foobar"))).isFalse();

        List<Integer> v = new ArrayList<>();
        assertThat(match(s, toSlice(""), v)).isFalse();
        assertThat(v).isEmpty();

        assertThat(match(s, toSlice("foobar"), v)).isFalse();
        assertThat(v).isEmpty();
    }

    @Test
    public void testPrefix()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FULL);

        assertThat(builder.add(Slices.utf8Slice("/prefix/\\d*"))).isEqualTo(0);
        Re2Set s = builder.build();

        assertThat(match(s, toSlice("/prefix"))).isFalse();
        assertThat(match(s, toSlice("/prefix/"))).isTrue();
        assertThat(match(s, toSlice("/prefix/42"))).isTrue();

        List<Integer> v = new ArrayList<>();
        assertThat(match(s, toSlice("/prefix"), v)).isFalse();
        assertThat(v).isEmpty();

        assertThat(match(s, toSlice("/prefix/"), v)).isTrue();
        assertThat(v).hasSize(1);
        assertThat(v).containsExactly(0);

        assertThat(match(s, toSlice("/prefix/42"), v)).isTrue();
        assertThat(v).hasSize(1);
        assertThat(v).containsExactly(0);
    }

    @Test
    public void testConstructorDoesNotMutateOptions()
    {
        Re2.Options options = Re2.Options.defaults();
        assertThat(Re2.compile(Slices.utf8Slice("(a)"), options).capturingGroupCount()).isEqualTo(1);

        Re2Set.Builder builder = Re2Set.builder(options, Re2Set.MatchMode.FIND);
        assertThat(builder.add(Slices.utf8Slice("(a)"))).isEqualTo(0);
        assertThat(Re2.compile(Slices.utf8Slice("(a)"), options).capturingGroupCount()).isEqualTo(1);

        Re2Set set = builder.build();
        List<Integer> matches = new ArrayList<>();
        assertThat(match(set, toSlice("a"), matches)).isTrue();
        assertThat(matches).containsExactly(0);
    }

    @Test
    public void testEqualsComparesPatternsAndOptions()
    {
        Re2Set set = buildSet(Re2.Options.defaults(), Re2Set.MatchMode.FIND, "foo", "ba+r");
        Re2Set rebuilt = buildSet(Re2.Options.defaults(), Re2Set.MatchMode.FIND, "foo", "ba+r");
        assertThat(rebuilt).isEqualTo(set).hasSameHashCodeAs(set);

        // The patterns compare in ID order, and the match mode and every option the set compiles
        // with take part, including the memory budget.
        assertThat(buildSet(Re2.Options.defaults(), Re2Set.MatchMode.FIND, "ba+r", "foo")).isNotEqualTo(set);
        assertThat(buildSet(Re2.Options.defaults(), Re2Set.MatchMode.FULL, "foo", "ba+r")).isNotEqualTo(set);
        assertThat(buildSet(Re2.Options.defaults().setCaseSensitive(false), Re2Set.MatchMode.FIND, "foo", "ba+r")).isNotEqualTo(set);
        assertThat(buildSet(Re2.Options.defaults().setMaxMemory(16 << 20), Re2Set.MatchMode.FIND, "foo", "ba+r")).isNotEqualTo(set);
    }

    @Test
    public void testBuilderCannotBeReused()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);
        assertThat(builder.add(Slices.utf8Slice("foo"))).isEqualTo(0);
        builder.build();
        assertThatThrownBy(() -> builder.add(Slices.utf8Slice("bar")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already built");
        assertThatThrownBy(builder::size)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already built");
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already built");
    }

    @Test
    public void testCompileFailureThrows()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults().setMaxMemory(1), Re2Set.MatchMode.FIND);
        builder.add(Slices.utf8Slice("foo"));

        assertThatThrownBy(builder::build)
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    @Test
    public void testMatchesAnyReportsDfaMemoryExhaustion()
    {
        Slice input = Slices.utf8Slice(deBruijnString(22) + "0");
        assertThat(Re2.compile(Slices.utf8Slice("0[01]{22}$")).find(input)).isTrue();
        Re2Set set = createExhaustingSet();

        assertThatThrownBy(() -> set.matchesAny(input))
                .isInstanceOfSatisfying(RegexpMatchMemoryLimitException.class, exception ->
                        assertThat(exception.maxMemoryBytes()).isEqualTo(Re2.Options.DEFAULT_MAX_MEMORY));
    }

    @Test
    public void testMatchesAnyStopsAtFirstMatchInFullCache()
    {
        Re2Set set = buildSet(Re2.Options.defaults(), Re2Set.MatchMode.FIND, "a", "ab");
        // This search caches the DFA state that 'a' enters, which holds the match of `a`.
        assertThat(set.matchingPatternIds(Slices.utf8Slice("a"))).containsExactly(0);
        Dfa.DfaInstance dfa = set.dfaForDiagnostics();
        int resetCount = dfa.resetCount();

        // The DFA sees the match when it enters that state and returns there, so it does not need
        // the new state that 'b' would enter, and a cache with no room for one is not reset.
        dfa.failStateAllocationsForTesting(1);
        assertThat(set.matchesAny(Slices.utf8Slice("ab"))).isTrue();
        assertThat(dfa.resetCount()).isEqualTo(resetCount);
    }

    @Test
    public void testMatchesAnyResetsFullCacheAtEndOfText()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);
        builder.add(Slices.utf8Slice("a+$"));
        Re2Set set = builder.build();
        // This search caches the DFA transition for 'a', but not the end-of-text step after it.
        assertThat(set.matchesAny(Slices.utf8Slice("ab"))).isFalse();
        Dfa.DfaInstance dfa = set.dfaForDiagnostics();
        int resetCount = dfa.resetCount();

        // The member's `$` keeps the states that upstream RE2 builds, so the DFA sees the match that
        // ends at the last byte only in its end-of-text step, whose new state does not fit the
        // cache. The search resets the cache once and sees the match.
        dfa.failStateAllocationsForTesting(1);
        assertThat(set.matchesAny(Slices.utf8Slice("a"))).isTrue();
        assertThat(dfa.resetCount()).isEqualTo(resetCount + 1);
    }

    @Test
    public void testMatchingPatternIdsSurvivesDefaultBudgetCacheResets()
    {
        Slice input = Slices.utf8Slice(deBruijnString(22) + "0");
        Re2Set set = createExhaustingSet();

        assertThat(set.matchingPatternIds(input)).containsExactly(0);
        assertThat(set.dfaForDiagnostics().resetCount()).isPositive();
    }

    @Test
    public void testMatchingPatternIdsSurvivesCustomBudgetCacheResets()
    {
        Slice input = Slices.utf8Slice(deBruijnString(18) + "0");
        Re2.Options options = Re2.Options.defaults().setMaxMemory(64L << 10);
        Re2Set.Builder builder = Re2Set.builder(options, Re2Set.MatchMode.FIND);
        builder.add(Slices.utf8Slice("0[01]{18}$"));
        Re2Set set = builder.build();

        assertThat(set.maxMemoryBytes()).isEqualTo(64L << 10);
        assertThatThrownBy(() -> set.matchesAny(input))
                .isInstanceOfSatisfying(RegexpMatchMemoryLimitException.class, exception ->
                        assertThat(exception.maxMemoryBytes()).isEqualTo(set.maxMemoryBytes()));
        assertThat(set.matchingPatternIds(input)).containsExactly(0);
        assertThat(set.dfaForDiagnostics().resetCount()).isPositive();
        assertThat(set.matchingPatternIds(input)).containsExactly(0);
    }

    @Test
    public void testMatchingPatternIdsRecordsScannedMatchesBeforeCacheReset()
    {
        for (int repeats : new int[] {Dfa.MANY_MATCH_SCAN_STATES / 2, Dfa.MANY_MATCH_SCAN_STATES}) {
            Re2Set set = buildSet(Re2.Options.defaults(), Re2Set.MatchMode.FIND, "a", "b", "c", "d");
            // The search records the leading 'a' in its own step, where it allocates the scan
            // buffer, so the scans below start at the repetitions.
            Slice text = toSlice("a" + "ab".repeat(repeats) + "dc");
            // This search caches every state but the one 'c' enters.
            assertThat(set.matchingPatternIds(text.slice(0, text.length() - 1))).containsExactlyInAnyOrder(0, 1, 3);
            Dfa.DfaInstance dfa = set.dfaForDiagnostics();
            int resetCount = dfa.resetCount();

            // At half the capacity in repetitions, the cached scan fills before the unique 'd',
            // which the caller must record. At the full capacity, a later scan buffers 'd' before
            // the uncomputed 'c' resets the cache, so those matches must be drained first.
            dfa.failStateAllocationsForTesting(1);
            assertThat(set.matchingPatternIds(text)).containsExactlyInAnyOrder(0, 1, 2, 3);
            assertThat(dfa.resetCount()).isEqualTo(resetCount + 1);
        }
    }

    @Test
    public void testMatchingPatternIdsPreservesLargeMatchPayload()
    {
        int patternCount = 512;
        Re2.Options options = Re2.Options.defaults().setMaxMemory(1L << 20);
        Re2Set.Builder builder = Re2Set.builder(options, Re2Set.MatchMode.FIND);
        for (int patternId = 0; patternId < patternCount; patternId++) {
            builder.add(Slices.utf8Slice("a"));
        }
        Re2Set set = builder.build();

        assertThat(set.matchingPatternIds(Slices.utf8Slice("a")))
                .containsExactlyInAnyOrder(IntStream.range(0, patternCount).toArray());
    }

    @Test
    public void testLiteralSetStatesAreItsFrontiers()
    {
        Re2Set set = buildSet(Re2.Options.defaults(), Re2Set.MatchMode.FIND, "a", "b", "ab", "ba");

        // Without empty-width assertions, each DFA state holds the matches of its own frontier
        // rather than those of the frontier one byte earlier, so the text reaches one state for
        // each frontier: no member begun, after 'a', after 'b', after "ab" and after "ba". Keyed
        // by the previous frontier's matches as well, as upstream RE2 keys them, it reaches 11.
        assertThat(set.matchingPatternIds(toSlice("abba aab bba ab"))).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(set.dfaForDiagnostics().cacheSnapshot().stateCount()).isEqualTo(5);
    }

    @Test
    public void testEmptyWidthSetsKeepPreviousByteMatches()
    {
        // An assertion can depend on the byte after a match, so a set with one in any member keys
        // each DFA state, as upstream RE2 does, by its frontier and the members that matched before
        // the byte that entered it.
        assertEmptyWidthSet("a", List.of("a$", "b"), 3);
        assertEmptyWidthSet("foo bar xfoo barfoo", List.of("\\bfoo", "bar"), 12);
        assertEmptyWidthSet("xyxy yx", List.of("^x", "y"), 6);
        assertEmptyWidthSet("foo bar foobar", List.of("foo", "bar$"), 10);
        assertEmptyWidthSet("ab bab ab", List.of("\\bab", "a", "b"), 9);
    }

    @Test
    public void testMatchesEachMemberSearchedAlone()
    {
        Random random = new Random(20261117);
        for (Re2.Options.Encoding encoding : Re2.Options.Encoding.values()) {
            for (Re2Set.MatchMode matchMode : Re2Set.MatchMode.values()) {
                for (int setIndex = 0; setIndex < 100; setIndex++) {
                    List<String> patterns = randomSetPatterns(random);
                    for (int textIndex = 0; textIndex < 12; textIndex++) {
                        Slice text = randomText(random);
                        int primedLength = random.nextBoolean() ? random.nextInt(text.length() + 1) : -1;
                        assertMatchesEachMemberSearchedAlone(patterns, encoding, matchMode, text, primedLength);
                    }
                }
            }
        }
    }

    @Test
    public void testMatchesAnyDoesNotAllocateAfterWarmup()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);
        builder.add(Slices.utf8Slice("foo"));
        builder.add(Slices.utf8Slice("bar"));
        Re2Set set = builder.build();
        Slice input = Slices.utf8Slice("prefix foo suffix");

        for (int iteration = 0; iteration < 20_000; iteration++) {
            assertThat(set.matchesAny(input)).isTrue();
        }

        long allocatedBytes = minimumAllocatedBytes(10_000, () -> {
            int matchCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                if (set.matchesAny(input)) {
                    matchCount++;
                }
            }
            return matchCount;
        });

        assertThat(allocatedBytes / 10_000).isZero();
    }

    /// Runs `operations` three times, checks each result, and returns the fewest bytes the
    /// current thread allocated in one run. An allocation per operation appears in every run. A
    /// one-time allocation does not: when a counter overflow requests a C2 compile, the compile
    /// broker loads the method's signature classes on the requesting thread, which can add tens of
    /// kilobytes to one run.
    private static long minimumAllocatedBytes(long expectedResult, LongSupplier operations)
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        long minimumAllocatedBytes = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
            long result = operations.getAsLong();
            long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
            assertThat(result).isEqualTo(expectedResult);
            minimumAllocatedBytes = Math.min(minimumAllocatedBytes, allocatedBytes);
        }
        return minimumAllocatedBytes;
    }

    @Test
    public void testNullInputRejected()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);
        builder.add(Slices.utf8Slice("foo"));
        Re2Set set = builder.build();

        assertThatThrownBy(() -> set.matchesAny(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("text is null");
        assertThatThrownBy(() -> set.matchingPatternIds(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("text is null");
    }

    private static Re2Set buildSet(Re2.Options options, Re2Set.MatchMode matchMode, String... patterns)
    {
        Re2Set.Builder builder = Re2Set.builder(options, matchMode);
        for (String pattern : patterns) {
            builder.add(toSlice(pattern));
        }
        return builder.build();
    }

    private static void assertEmptyWidthSet(String text, List<String> patterns, int expectedStates)
    {
        Re2Set set = buildSet(Re2.Options.defaults(), Re2Set.MatchMode.FIND, patterns.toArray(String[]::new));
        assertMatchesEachMemberSearchedAlone(patterns, Re2.Options.Encoding.UTF8, Re2Set.MatchMode.FIND, toSlice(text), -1);
        set.matchingPatternIds(toSlice(text));
        assertThat(set.dfaForDiagnostics().cacheSnapshot().stateCount())
                .as("states of %s on \"%s\"", patterns, text)
                .isEqualTo(expectedStates);
    }

    /// Checks that the set of `patterns` reports the members that, compiled alone with the same
    /// options, match `text` in `matchMode`. With a `primedLength` of zero or more, each search
    /// first runs over that many leading bytes, and the search of the whole text then finds no room
    /// for the first new DFA state it needs past them, if it needs one, so it resets the cache and
    /// carries on from the states it restores.
    private static void assertMatchesEachMemberSearchedAlone(List<String> patterns, Re2.Options.Encoding encoding, Re2Set.MatchMode matchMode, Slice text, int primedLength)
    {
        Re2.Options options = Re2.Options.defaults().setEncoding(encoding);
        List<Integer> expected = new ArrayList<>();
        Re2Set.Builder builder = Re2Set.builder(options, matchMode);
        for (int patternId = 0; patternId < patterns.size(); patternId++) {
            Slice pattern = patternSlice(patterns.get(patternId), encoding);
            builder.add(pattern);
            Re2 member = Re2.compile(pattern, options);
            boolean matches = switch (matchMode) {
                case FIND -> member.find(text);
                case LOOKING_AT -> member.lookingAt(text);
                case FULL -> member.matches(text);
            };
            if (matches) {
                expected.add(patternId);
            }
        }
        Re2Set set = builder.build();

        String description = "%s %s %s on %s".formatted(encoding, matchMode, patterns, Arrays.toString(text.getBytes()));
        Dfa.DfaInstance dfa = set.dfaForDiagnostics();
        if (primedLength >= 0) {
            set.matchingPatternIds(text.slice(0, primedLength));
            dfa.failStateAllocationsForTesting(1);
        }
        assertThat(set.matchingPatternIds(text)).as(description)
                .containsExactlyInAnyOrder(expected.stream().mapToInt(Integer::intValue).toArray());
        if (primedLength >= 0) {
            dfa.resetCacheExternal();
            set.matchesAny(text.slice(0, primedLength));
            dfa.failStateAllocationsForTesting(1);
        }
        assertThat(set.matchesAny(text)).as(description).isEqualTo(!expected.isEmpty());
    }

    private static Slice patternSlice(String pattern, Re2.Options.Encoding encoding)
    {
        return switch (encoding) {
            case UTF8 -> Slices.utf8Slice(pattern);
            case LATIN1 -> Slices.wrappedBuffer(pattern.getBytes(ISO_8859_1));
        };
    }

    /// Returns one to four members: literals over a small alphabet, including the empty literal,
    /// and short shapes with classes, repeats and alternations, which overlap and abut one
    /// another. One set in five also gets one more member, with an empty-width assertion, at a
    /// random position.
    private static List<String> randomSetPatterns(Random random)
    {
        String[] shapes = {"ba[rz]+", "x.y", "[0-9]{2,4}", "a+", "(?:ab|ba|c)", "[ab]c?", "é+"};
        String[] emptyWidthMembers = {"a$", "^b", "\\bab", "c\\B"};
        String literalCharacters = "abcé";
        int memberCount = 1 + random.nextInt(4);
        List<String> patterns = new ArrayList<>(memberCount + 1);
        for (int member = 0; member < memberCount; member++) {
            if (random.nextBoolean()) {
                patterns.add(shapes[random.nextInt(shapes.length)]);
                continue;
            }
            StringBuilder literal = new StringBuilder();
            int length = random.nextInt(4);
            for (int index = 0; index < length; index++) {
                literal.append(literalCharacters.charAt(random.nextInt(literalCharacters.length())));
            }
            patterns.add(literal.toString());
        }
        if (random.nextInt(5) == 0) {
            patterns.add(random.nextInt(patterns.size() + 1), emptyWidthMembers[random.nextInt(emptyWidthMembers.length)]);
        }
        return patterns;
    }

    /// Returns a text of the bytes the random members use, `é` in UTF-8 and Latin-1, and bytes
    /// that are invalid UTF-8, viewed at a random offset into a larger array. One text in ten is a
    /// few hundred bytes long, for longer runs through the DFA's states.
    private static Slice randomText(Random random)
    {
        byte[][] pieces = {
                {'a'}, {'b'}, {'c'}, {'r'}, {'z'}, {'x'}, {'y'}, {'0'}, {'7'}, {' '},
                {(byte) 0xC3, (byte) 0xA9}, {(byte) 0xE9}, {(byte) 0xC3}, {(byte) 0xFF}, {0},
        };
        int pieceCount = random.nextInt(10) == 0 ? 300 + random.nextInt(100) : random.nextInt(12);
        int prefixLength = random.nextInt(3);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int index = 0; index < prefixLength; index++) {
            bytes.write('a');
        }
        for (int index = 0; index < pieceCount; index++) {
            bytes.writeBytes(pieces[random.nextInt(pieces.length)]);
        }
        int textLength = bytes.size() - prefixLength;
        bytes.write('b');
        return Slices.wrappedBuffer(bytes.toByteArray(), prefixLength, textLength);
    }

    private static Re2Set createExhaustingSet()
    {
        Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);
        builder.add(Slices.utf8Slice("0[01]{22}$"));
        return builder.build();
    }

    private static String deBruijnString(int substringLength)
    {
        int size = 1 << substringLength;
        int mask = size - 1;
        boolean[] visited = new boolean[size];
        StringBuilder result = new StringBuilder(substringLength + size);
        result.repeat("0", substringLength - 1);

        int bits = 0;
        for (int i = 0; i < size; i++) {
            bits = (bits << 1) & mask;
            if (!visited[bits | 1]) {
                bits |= 1;
                result.append('1');
            }
            else {
                result.append('0');
            }
            if (visited[bits]) {
                throw new AssertionError("De Bruijn invariant violated");
            }
            visited[bits] = true;
        }
        return result.toString();
    }

    private static boolean match(Re2Set set, Slice text)
    {
        return set.matchesAny(text);
    }

    private static boolean match(Re2Set set, Slice text, List<Integer> matches)
    {
        matches.clear();
        for (int patternId : set.matchingPatternIds(text)) {
            matches.add(patternId);
        }
        return !matches.isEmpty();
    }
}
