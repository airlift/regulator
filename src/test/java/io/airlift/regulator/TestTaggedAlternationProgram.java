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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.airlift.regulator.Dfa.DfaInstance.Kind.MANY_MATCH;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

public class TestTaggedAlternationProgram
{
    @Test
    public void testTextDependentAlternationUsesScalarNfaWorkspace()
    {
        Slice expression = utf8Slice(largeAlternation("\\bfoo\\b", "foo", "."));
        Slice input = utf8Slice("foo foo");
        for (Re2Matcher matcher : List.of(
                JavaRegexp.compile(expression).matcher(input),
                TrinoRegexp.compile(expression).pattern().matcher(input))) {
            assertThat(matcher.find()).isTrue();
            assertMatch(matcher, 0, 3, 1);
            assertThat(matcher.find()).isTrue();
            assertMatch(matcher, 3, 4, 3);
            assertThat(matcher.find()).isTrue();
            assertMatch(matcher, 4, 7, 1);
            assertThat(matcher.find()).isFalse();
            // This shape has one participating branch, so no per-thread capture array is needed.
            assertThat(matcher.nfaWorkspace().groupZeroWorkspaceReuseCountForDiagnostics()).isGreaterThan(0);
        }
    }

    @Test
    public void testOrderedBranchCaptureUsesTaggedRoute()
    {
        // Pinned re2_golden reports 0:1,0:1,-1:-1,-1:-1 for the first match.
        Re2 pattern = Re2.compile(utf8Slice(largeAlternation("a", "ab", ".")));
        Re2Matcher matcher = pattern.matcher(utf8Slice("ab"));

        assertThat(pattern.usesTaggedAlternationForDiagnostics()).isTrue();
        assertThat(matcher.find()).isTrue();
        assertMatch(matcher, 0, 1, 1);
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isFalse();

        assertThat(matcher.find()).isTrue();
        assertMatch(matcher, 1, 2, 3);
        assertThat(matcher.find()).isFalse();
    }

    @Test
    public void testDuplicateAndEmptyBranchPrecedence()
    {
        // Pinned re2_golden chooses the first duplicate branch and the first empty branch.
        Re2 duplicatePattern = Re2.compile(utf8Slice(largeAlternation("a", "a")));
        Re2Matcher duplicateMatcher = duplicatePattern.matcher(utf8Slice("a"));
        assertThat(duplicateMatcher.find()).isTrue();
        assertMatch(duplicateMatcher, 0, 1, 1);

        Re2 emptyPattern = Re2.compile(utf8Slice(largeAlternation("", "a")));
        Re2Matcher emptyMatcher = emptyPattern.matcher(utf8Slice("a"));
        assertThat(emptyMatcher.find()).isTrue();
        assertMatch(emptyMatcher, 0, 0, 1);
    }

    @Test
    public void testUtf8AndLatin1()
    {
        // Pinned re2_golden reports group 1 at 0:4 for the two UTF-8 delta code points.
        Re2 utf8Pattern = Re2.compile(utf8Slice(largeAlternation("δ+", ".")));
        Re2Matcher utf8Matcher = utf8Pattern.matcher(utf8Slice("δδ!"));
        assertThat(utf8Matcher.find()).isTrue();
        assertMatch(utf8Matcher, 0, 4, 1);

        // Pinned re2_golden reports group 1 at 0:1 for the Latin-1 byte FF.
        Re2 latin1Pattern = Re2.compile(
                utf8Slice(largeAlternation("\\xFF", ".")),
                Re2.Options.latin1());
        Re2Matcher latin1Matcher = latin1Pattern.matcher(wrappedBuffer(new byte[] {(byte) 0xFF}));
        assertThat(latin1Matcher.find()).isTrue();
        assertMatch(latin1Matcher, 0, 1, 1);
    }

    @Test
    public void testNonzeroRegionAndRetainedCapturePrefix()
    {
        // Pinned re2_golden reports group 1 at 1:2 for this exact region.
        Re2 pattern = Re2.compile(utf8Slice(largeAlternation("a", ".")));
        Re2Matcher matcher = pattern.matcher(utf8Slice("!a?"), 1)
                .reset(utf8Slice("!a?"), 1, 2);

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(2);
        assertThat(matcher.matched(1)).isTrue();
        assertThat(matcher.group(1).toStringUtf8()).isEqualTo("a");
    }

    @Test
    public void testWordBoundaryUsesContextOutsideExactMatch()
    {
        Re2 pattern = Re2.compile(utf8Slice(largeAlternation("\\bfoo\\b", "foo", ".")));
        assertThat(pattern.usesTaggedAlternationForDiagnostics()).isTrue();

        // Pinned re2_golden selects the first branch in this region because both adjacent
        // context bytes are non-word bytes.
        Re2Matcher accepted = pattern.matcher(utf8Slice("!foo?"))
                .reset(utf8Slice("!foo?"), 1, 4);
        assertThat(accepted.find()).isTrue();
        assertMatch(accepted, 1, 4, 1);
        assertThat(accepted.nfaWorkspaceInitializedForDiagnostics()).isFalse();

        // Pinned re2_golden selects the second branch for the same search region when the
        // byte after the region is a word byte.
        Re2Matcher rejected = pattern.matcher(utf8Slice("!fooa?"))
                .reset(utf8Slice("!fooa?"), 1, 4);
        assertThat(rejected.find()).isTrue();
        assertMatch(rejected, 1, 4, 2);
        assertThat(rejected.nfaWorkspaceInitializedForDiagnostics()).isFalse();
    }

    @Test
    public void testUnsupportedShapesRetainCaptureEngineFallback()
    {
        assertFallback(largeAlternation("(a)", "."), utf8Slice("a"));
        assertFallback(largeAlternation("^foo$", "."), utf8Slice("foo!"));

        Re2 longestPattern = Re2.compile(
                utf8Slice(largeAlternation("a", "ab", ".")),
                Re2.Options.defaults().setLongestMatch(true));
        assertThat(longestPattern.usesTaggedAlternationForDiagnostics()).isFalse();
        Re2Matcher longestMatcher = longestPattern.matcher(utf8Slice("ab"));
        assertThat(longestMatcher.find()).isTrue();
        assertThat(longestMatcher.nfaWorkspaceInitializedForDiagnostics()).isTrue();
    }

    @Test
    public void testAuxiliaryProgramSharesForwardMemoryBudgetAndFallsBack()
    {
        Slice patternText = utf8Slice(largeAlternation("a", "ab", "."));
        Re2 pattern = Re2.compile(patternText);
        Prog semanticProgram = pattern.forwardProgramForDiagnostics();
        Prog taggedProgram = pattern.taggedAlternationProgramForDiagnostics();
        long forwardMemory = Re2.Options.DEFAULT_MAX_MEMORY - (Re2.Options.DEFAULT_MAX_MEMORY / 3);

        assertThat(taggedProgram).isNotNull();
        assertThat(taggedProgram.cachedDfaIfPresent(MANY_MATCH)).isNotNull();
        assertThat(Compiler.estimatedProgramMemory(semanticProgram) +
                Compiler.estimatedProgramMemory(taggedProgram) +
                semanticProgram.dfaMemory() +
                taggedProgram.dfaMemory())
                .isEqualTo(forwardMemory);

        Re2 memoryFallback = null;
        Re2 memoryAccepted = null;
        for (long maxMemory = 64L << 10; maxMemory <= 16L << 20; maxMemory *= 2) {
            try {
                Re2 candidate = Re2.compile(patternText, Re2.Options.defaults().setMaxMemory(maxMemory));
                if (candidate.usesTaggedAlternationForDiagnostics()) {
                    memoryAccepted = candidate;
                }
                else {
                    memoryFallback = candidate;
                }
            }
            catch (RegexpCompileMemoryLimitException ignored) {
                // The semantic program itself does not fit at this budget.
            }
        }

        assertThat(memoryFallback).isNotNull();
        assertThat(memoryAccepted).isNotNull();
        Re2Matcher fallbackMatcher = memoryFallback.matcher(utf8Slice("ab"));
        assertThat(fallbackMatcher.find()).isTrue();
        assertMatch(fallbackMatcher, 0, 1, 1);
        assertThat(fallbackMatcher.nfaWorkspaceInitializedForDiagnostics()).isTrue();
    }

    @Test
    public void testConcurrentMatchingAndCacheReset()
            throws Exception
    {
        Re2 pattern = Re2.compile(utf8Slice(largeAlternation("a", "ab", ".")));
        Dfa.DfaInstance taggedDfa = pattern.taggedAlternationProgramForDiagnostics().cachedDfaIfPresent(MANY_MATCH);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int task = 0; task < 5; task++) {
                tasks.add(() -> {
                    int result = 0;
                    Re2Matcher matcher = pattern.matcher(utf8Slice("ab"));
                    for (int iteration = 0; iteration < 200; iteration++) {
                        matcher.reset(utf8Slice("ab"));
                        while (matcher.find()) {
                            result += matcher.matched(1) || matcher.matched(3) ? 1 : 0;
                        }
                    }
                    return result;
                });
            }
            tasks.add(() -> {
                for (int iteration = 0; iteration < 200; iteration++) {
                    taggedDfa.resetCacheExternal();
                }
                return 0;
            });

            List<Future<Integer>> results = executor.invokeAll(tasks);
            for (int task = 0; task < 5; task++) {
                assertThat(results.get(task).get()).isEqualTo(400);
            }
            assertThat(results.getLast().get()).isZero();
        }
        finally {
            executor.shutdownNow();
        }
    }

    private static void assertFallback(String patternText, Slice input)
    {
        Re2 pattern = Re2.compile(utf8Slice(patternText));
        assertThat(pattern.usesTaggedAlternationForDiagnostics()).isFalse();

        Re2Matcher matcher = pattern.matcher(input);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isTrue();
    }

    private static void assertMatch(Re2Matcher matcher, int start, int end, int winningGroup)
    {
        assertThat(matcher.start()).isEqualTo(start);
        assertThat(matcher.end()).isEqualTo(end);
        for (int group = 1; group <= matcher.groupCount(); group++) {
            assertThat(matcher.matched(group))
                    .describedAs("group %s participation", group)
                    .isEqualTo(group == winningGroup);
        }
        assertThat(matcher.start(winningGroup)).isEqualTo(start);
        assertThat(matcher.end(winningGroup)).isEqualTo(end);
    }

    private static String largeAlternation(String... leadingBranches)
    {
        return Stream.concat(
                        Arrays.stream(leadingBranches),
                        IntStream.range(0, 90).mapToObj(index -> "unreachable" + index + "z"))
                .map(branch -> "(" + branch + ")")
                .collect(joining("|"));
    }
}
