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

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static io.airlift.regulator.CompiledPattern.Anchor.UNANCHORED;
import static io.airlift.regulator.Re2BenchmarkRunner.compileProg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestNfaWorkspace
{
    @Test
    public void testScratchStorageIsReusedAndReset()
            throws ReflectiveOperationException
    {
        Prog program = compileProg("(a+)(b)?");
        Nfa.Workspace workspace = new Nfa.Workspace();
        int[] groups = new int[6];

        Arrays.fill(groups, -1);
        assertThat(Nfa.search(
                program,
                Slices.utf8Slice("aaab"),
                0,
                4,
                true,
                Prog.MatchKind.FULL_MATCH,
                groups,
                workspace)).isTrue();
        assertThat(groups).containsExactly(0, 4, 0, 3, 3, 4);

        Object firstQueue = field("firstQueue").get(workspace);
        Object match = field("match").get(workspace);
        Object threadCaptures = field("threadCaptures").get(workspace);

        assertThat(Nfa.search(
                program,
                Slices.utf8Slice("a"),
                0,
                1,
                true,
                Prog.MatchKind.FULL_MATCH,
                groups,
                workspace)).isTrue();
        assertThat(groups).containsExactly(0, 1, 0, 1, -1, -1);
        assertThat(field("firstQueue").get(workspace)).isSameAs(firstQueue);
        assertThat(field("match").get(workspace)).isSameAs(match);
        assertThat(field("threadCaptures").get(workspace)).isSameAs(threadCaptures);

        Arrays.fill(groups, -1);
        assertThat(Nfa.search(
                program,
                Slices.utf8Slice("x"),
                0,
                1,
                true,
                Prog.MatchKind.FULL_MATCH,
                groups,
                workspace)).isFalse();
        assertThat(groups).containsExactly(-1, -1, -1, -1, -1, -1);
        assertThat((int[]) field("threadReferences").get(workspace)).containsOnly(0);

        // Groups the simplifier removed have no CAPTURE, so the search tracks only the program's
        // six slots. A match still sets every pair of the buffer, including the removed groups'
        // pairs, which the caller need not fill with -1 beforehand, in one pass and in bounded
        // passes, which take two passes here.
        Prog removedGroups = compileProg("(a+)(a*)(?:(b)(c)){0}");
        assertThat(removedGroups.captureSlotCount()).isEqualTo(6);
        int[] allGroups = new int[10];
        Arrays.fill(allGroups, 7);
        assertThat(Nfa.search(removedGroups, Slices.utf8Slice("aa"), 0, 2, true, Prog.MatchKind.FULL_MATCH, allGroups, workspace)).isTrue();
        assertThat(allGroups).containsExactly(0, 2, 0, 2, 2, 2, -1, -1, -1, -1);
        assertThat((int[]) field("match").get(workspace)).hasSize(removedGroups.captureSlotCount());
        Arrays.fill(allGroups, 7);
        assertThat(Nfa.searchInBoundedPassesForTesting(removedGroups, Slices.utf8Slice("aa"), 0, 2, true, Prog.MatchKind.FULL_MATCH, allGroups, 4)).isTrue();
        assertThat(allGroups).containsExactly(0, 2, 0, 2, 2, 2, -1, -1, -1, -1);
    }

    @Test
    public void testMatcherOwnsAndReusesWorkspace()
            throws ReflectiveOperationException
    {
        String patternText = "((?:a|aa){1000})(b{1000})(c{1000})(d{1000})(e{1000})(f{1000})";
        Slice input = Slices.utf8Slice("a".repeat(1000) + "b".repeat(1000) + "c".repeat(1000) + "d".repeat(1000) + "e".repeat(1000) + "f".repeat(1000));
        Re2 pattern = Re2.compile(Slices.utf8Slice(patternText));
        assertThat(pattern.forwardProgramForDiagnostics().isOnePass()).isFalse();
        assertThat(input.length()).isGreaterThan(pattern.forwardProgramForDiagnostics().bitStateTextMaxSize());

        RegexpMatcher matcher = pattern.matcher(input);
        Field workspaceField = RegexpMatcher.class.getDeclaredField("nfaWorkspace");
        workspaceField.setAccessible(true);
        assertThat(workspaceField.get(matcher)).isNull();

        assertThat(matcher.find()).isTrue();
        Nfa.Workspace workspace = (Nfa.Workspace) workspaceField.get(matcher);
        assertThat(workspace).isNotNull();
        assertThat(matcherWorkspace("onePassWorkspace").get(matcher)).isNull();
        assertThat(matcherWorkspace("bitStateWorkspace").get(matcher)).isNull();
        Object firstQueue = field("firstQueue").get(workspace);
        Object match = field("match").get(workspace);
        assertThat(firstQueue).isNotNull();

        matcher.reset(input);
        assertThat(matcher.find()).isTrue();
        assertThat(field("firstQueue").get(workspace)).isSameAs(firstQueue);
        assertThat(field("match").get(workspace)).isSameAs(match);
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isEqualTo(input.length());
    }

    @Test
    public void testCaptureThreadsStayWithinBound()
    {
        // Bounded passes size their thread tables by Nfa.captureThreadBound up front, so no search
        // may hold more threads. Twenty (a?) groups hold 60 threads against a bound of 63.
        assertCaptureThreads("(a?)".repeat(20), "a".repeat(20), 60, 63);
        assertCaptureThreads("(?:(a)|b)*", "abababab", 4, 7);
        assertCaptureThreads("((a)|(b))*c", "ababababx", 6, 12);
        assertCaptureThreads("(?:(a)|(a)|(a))*", "aaaa", 8, 12);
        assertCaptureThreads("x*(a?)(a?)(a?)(a?)y", "xxxaaaay", 11, 17);
    }

    @Test
    public void testDifferentBackingOffsets()
    {
        Prog program = compileProg("(ab)");
        Nfa.Workspace workspace = new Nfa.Workspace();
        int[] groups = new int[4];
        Slice input = Slices.utf8Slice("xxabyy").slice(2, 2);

        assertThat(Nfa.search(
                program,
                input,
                0,
                input.length(),
                true,
                Prog.MatchKind.FULL_MATCH,
                groups,
                workspace)).isTrue();
        assertThat(groups).containsExactly(0, 2, 0, 2);

        Re2 pattern = Re2.compile(Slices.utf8Slice("(ab)"));
        assertThat(pattern.matchInto(input, UNANCHORED, groups)).isTrue();
        assertThat(groups).containsExactly(0, 2, 0, 2);
    }

    @Test
    public void testExceptionInvalidatesWorkspaceBeforeReuse()
            throws ReflectiveOperationException
    {
        Prog program = compileProg("(a+)");
        Nfa.Workspace workspace = new Nfa.Workspace();
        int[] groups = new int[4];
        Slice input = Slices.utf8Slice("aaa");

        assertThat(Nfa.search(program, input, 0, input.length(), true, Prog.MatchKind.FULL_MATCH, groups, workspace)).isTrue();
        field("addInstructionIdStack").set(workspace, new int[0]);
        field("addRestoreThreadStack").set(workspace, new int[0]);

        assertThatThrownBy(() -> Nfa.search(program, input, 0, input.length(), true, Prog.MatchKind.FULL_MATCH, groups, workspace))
                .isInstanceOf(ArrayIndexOutOfBoundsException.class);
        assertThat(field("program").get(workspace)).isNull();
        assertThat(field("firstQueue").get(workspace)).isNull();
        assertThat(field("threadCaptures").get(workspace)).isNull();

        assertThat(Nfa.search(program, input, 0, input.length(), true, Prog.MatchKind.FULL_MATCH, groups, workspace)).isTrue();
        assertThat(groups).containsExactly(0, 3, 0, 3);
    }

    private static void assertCaptureThreads(String patternText, String text, int threads, int bound)
    {
        Prog program = compileProg(patternText);
        assertThat(Nfa.captureThreadBound(program)).isEqualTo(bound);
        for (Prog.MatchKind matchKind : List.of(Prog.MatchKind.FIRST_MATCH, Prog.MatchKind.LONGEST_MATCH)) {
            Nfa.Workspace workspace = new Nfa.Workspace();
            Nfa.search(program, Slices.utf8Slice(text), 0, text.length(), false, matchKind, new int[42], workspace);
            assertThat(workspace.captureThreadCountForDiagnostics()).isEqualTo(threads);
        }
    }

    private static Field field(String name)
            throws ReflectiveOperationException
    {
        Field field = Nfa.Workspace.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Field matcherWorkspace(String name)
            throws NoSuchFieldException
    {
        Field field = RegexpMatcher.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
