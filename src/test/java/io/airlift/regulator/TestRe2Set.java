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

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

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

        assertThatThrownBy(() -> set.matchesAny(input))
                .isInstanceOf(RegexpMatchMemoryLimitException.class);
        assertThat(set.matchingPatternIds(input)).containsExactly(0);
        assertThat(set.dfaForDiagnostics().resetCount()).isPositive();
        assertThat(set.matchingPatternIds(input)).containsExactly(0);
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

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int matchCount = 0;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            if (set.matchesAny(input)) {
                matchCount++;
            }
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(matchCount).isEqualTo(10_000);
        assertThat(allocatedBytes / 10_000).isZero();
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
