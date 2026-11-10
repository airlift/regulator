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

import static io.airlift.regulator.Re2BenchmarkRunner.compileProg;
import static org.assertj.core.api.Assertions.assertThat;

public class TestBitStateWorkspace
{
    @Test
    public void testScratchStorageIsReusedAndReset()
            throws ReflectiveOperationException
    {
        Prog program = compileProg("(a+)(a)?");
        BitState.Workspace workspace = new BitState.Workspace();
        int[] groups = new int[6];

        assertThat(BitState.search(
                program,
                Slices.utf8Slice("aaa"),
                0,
                3,
                true,
                Prog.MatchKind.FULL_MATCH,
                groups,
                workspace)).isTrue();
        assertThat(groups).containsExactly(0, 3, 0, 3, -1, -1);

        Object visited = field("visited").get(workspace);
        Object captures = field("captures").get(workspace);
        Object jobs = field("jobs").get(workspace);
        Object firstJob = ((Object[]) jobs)[0];

        assertThat(BitState.search(
                program,
                Slices.utf8Slice("a"),
                0,
                1,
                true,
                Prog.MatchKind.FULL_MATCH,
                groups,
                workspace)).isTrue();
        assertThat(groups).containsExactly(0, 1, 0, 1, -1, -1);
        assertThat(field("visited").get(workspace)).isSameAs(visited);
        assertThat(field("captures").get(workspace)).isSameAs(captures);
        assertThat(field("jobs").get(workspace)).isSameAs(jobs);
        assertThat(((Object[]) field("jobs").get(workspace))[0]).isSameAs(firstJob);

        assertThat(BitState.search(
                program,
                Slices.utf8Slice("b"),
                0,
                1,
                true,
                Prog.MatchKind.FULL_MATCH,
                groups,
                workspace)).isFalse();
        assertThat(groups).containsExactly(-1, -1, -1, -1, -1, -1);
    }

    @Test
    public void testMatcherOwnsAndReusesWorkspace()
            throws ReflectiveOperationException
    {
        Slice input = Slices.utf8Slice("650-253-" + "0".repeat(10_000));
        RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("[0-9]+.(.*)")).matcher(input);
        Field workspaceField = RegexpMatcher.class.getDeclaredField("bitStateWorkspace");
        workspaceField.setAccessible(true);
        assertThat(workspaceField.get(matcher)).isNull();

        assertThat(matcher.find()).isTrue();
        BitState.Workspace workspace = (BitState.Workspace) workspaceField.get(matcher);
        assertThat(workspace).isNotNull();
        assertThat(matcherWorkspace("onePassWorkspace").get(matcher)).isNull();
        assertThat(matcherWorkspace("nfaWorkspace").get(matcher)).isNull();
        Object visited = field("visited").get(workspace);
        Object captures = field("captures").get(workspace);
        Object jobs = field("jobs").get(workspace);
        assertThat(visited).isNotNull();

        matcher.reset(input);
        assertThat(matcher.find()).isTrue();
        assertThat(field("visited").get(workspace)).isSameAs(visited);
        assertThat(field("captures").get(workspace)).isSameAs(captures);
        assertThat(field("jobs").get(workspace)).isSameAs(jobs);
    }

    @Test
    public void testLargeProgramExtractsCapturesWithBitState()
    {
        // Upstream gives only programs of at most 512 instructions list heads. The visited bitmap
        // grows with the list count and the span, not with the instruction count.
        Re2 pattern = Re2.compile(Slices.utf8Slice("(\\pL+)(\\pL*) ([0-9]+)"));
        assertThat(pattern.forwardProgramForDiagnostics().size()).isGreaterThan(512);
        assertThat(pattern.forwardProgramForDiagnostics().isOnePass()).isFalse();

        RegexpMatcher matcher = pattern.matcher(Slices.utf8Slice("see Genèse 12 for"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("Genèse");
        assertThat(matcher.group(2).toStringUtf8()).isEmpty();
        assertThat(matcher.group(3).toStringUtf8()).isEqualTo("12");
        assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).isTrue();
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isFalse();
    }

    @Test
    public void testUnanchoredSearchesKeepUpstreamBitmap()
    {
        // An unanchored search clears its visited bitmap over its whole window, and each find
        // searches the rest of the text, so the bitmap words a find-all clears grow with the square
        // of the window.
        // Unanchored searches take upstream's 256 Kibit bitmap, an eighth of the anchored one.
        Re2 direct = Re2.compile(Slices.utf8Slice("(a|ab)(c|bcd)?"));
        Prog directProgram = direct.forwardProgramForDiagnostics();
        assertThat(directProgram.listCount()).isEqualTo(12);
        assertThat(directProgram.anchoredBitStateTextMaxSize()).isGreaterThan(21_845);
        assertThat(directProgram.unanchoredBitStateTextMaxSize()).isEqualTo(21_844);

        // Past that window, a find with groups runs the DFA before BitState captures the span it
        // found, rather than the direct BitState capture route, which builds no reverse program.
        RegexpMatcher matcher = direct.matcher(Slices.utf8Slice("a".repeat(21_845)));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.end(1)).isEqualTo(1);
        assertThat(direct.isReverseProgramComputed()).isTrue();
        assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).isTrue();
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isFalse();

        // The submatch fallback, here for an assertion the DFA does not support, takes the NFA.
        JavaRegexp lines = JavaRegexp.compile(Slices.utf8Slice("(?m)^"));
        Prog linesProgram = lines.forwardProgramForDiagnostics();
        assertThat(linesProgram.hasDfaUnsupportedAssertions()).isTrue();
        assertThat(linesProgram.listCount()).isEqualTo(4);
        assertThat(linesProgram.unanchoredBitStateTextMaxSize()).isEqualTo(65_535);
        RegexpMatcher linesMatcher = lines.matcher(Slices.utf8Slice("\n".repeat(65_536)));
        assertThat(linesMatcher.find()).isTrue();
        assertThat(linesMatcher.start()).isZero();
        assertThat(linesMatcher.bitStateWorkspaceInitializedForDiagnostics()).isFalse();
        assertThat(linesMatcher.nfaWorkspaceInitializedForDiagnostics()).isTrue();
    }

    @Test
    public void testJobStackStartsAtSixteenAndGrownStackIsRetained()
            throws ReflectiveOperationException
    {
        int capturingGroupCount = 64;
        Prog program = compileProg("(a)".repeat(capturingGroupCount));
        Slice input = Slices.utf8Slice("a".repeat(capturingGroupCount));
        BitState.Workspace workspace = new BitState.Workspace();
        int[] groups = new int[2 * (capturingGroupCount + 1)];

        // A shallow search whose maximum exceeds the model's first capacity starts with sixteen
        // jobs, however large the program.
        assertThat(program.size()).isGreaterThan(64);
        assertThat(BitState.search(program, input, 0, 4, true, Prog.MatchKind.FULL_MATCH, groups, workspace)).isFalse();
        assertThat((Object[]) field("jobs").get(workspace)).hasSize(16);

        // A deep one doubles the stack until it holds the search's jobs.
        assertThat(BitState.search(
                program,
                input,
                0,
                input.length(),
                true,
                Prog.MatchKind.FULL_MATCH,
                groups,
                workspace)).isTrue();
        Object[] grownJobs = (Object[]) field("jobs").get(workspace);
        assertThat(grownJobs).hasSize(128);

        assertThat(BitState.search(
                program,
                input,
                0,
                input.length(),
                true,
                Prog.MatchKind.FULL_MATCH,
                groups,
                workspace)).isTrue();
        assertThat(field("jobs").get(workspace)).isSameAs(grownJobs);
    }

    private static Field field(String name)
            throws ReflectiveOperationException
    {
        Field field = BitState.Workspace.class.getDeclaredField(name);
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
