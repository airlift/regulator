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

import io.airlift.joni.Regex;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.airlift.regulator.OnePass.CaptureFinalization.DIRECT_REBASE;
import static io.airlift.regulator.OnePass.CaptureFinalization.DIRECT_ZERO_ORIGIN;
import static io.airlift.regulator.OnePass.CaptureFinalization.SCRATCH_COPY;
import static io.airlift.regulator.Re2BenchmarkRunner.compileProg;
import static io.airlift.regulator.TestingTrinoJoni.joniMatches;
import static io.airlift.regulator.TestingTrinoJoni.joniPattern;
import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestOnePassCaptureFinalization
{
    @Test
    public void testCaptureFinalizationModes()
    {
        int[] oneCaptureGroup = new int[4];
        int[] threeCaptureGroups = new int[8];
        assertThat(OnePass.captureFinalization(oneCaptureGroup, Prog.MatchKind.FULL_MATCH, 0)).isEqualTo(DIRECT_REBASE);
        assertThat(OnePass.captureFinalization(threeCaptureGroups, Prog.MatchKind.FULL_MATCH, 0)).isEqualTo(DIRECT_ZERO_ORIGIN);
        assertThat(OnePass.captureFinalization(threeCaptureGroups, Prog.MatchKind.FULL_MATCH, 2)).isEqualTo(DIRECT_REBASE);
        assertThat(OnePass.captureFinalization(threeCaptureGroups, Prog.MatchKind.FIRST_MATCH, 0)).isEqualTo(SCRATCH_COPY);
        assertThat(OnePass.captureFinalization(null, Prog.MatchKind.FULL_MATCH, 0)).isEqualTo(SCRATCH_COPY);
    }

    @Test
    public void testDirectCaptureRebasesNonZeroSearchOrigin()
    {
        Prog program = compileProg("([0-9]+)");
        Slice context = utf8Slice("xx123yy");
        int[] groups = new int[4];
        assertThat(OnePass.search(program, context, 2, 5, true, Prog.MatchKind.FULL_MATCH, groups)).isTrue();
        assertThat(groups).containsExactly(0, 3, 0, 3);
    }

    /// Programs with more capture slots than a base action encodes take the extended loop, whose
    /// full-match form carries no per-byte match bookkeeping. It must agree with the NFA on
    /// matches, misses, unset optional groups, assertions, and a nonzero search origin.
    @Test
    public void testExtendedFullMatchAgreesWithNfa()
    {
        String alphabet = "AZ09;;YNabcdxyz-\t ";
        Random random = new Random(42);
        for (ExtendedCase testCase : List.of(
                new ExtendedCase("([A-Z0-9]+);([^;]+);([^;]*);([0-9]*);([YN]);([^;]*)", List.of("0041;A;Lu;0;Y;", "00A0;NBSP;;12;N;x", "X;;Lu;0;Y;", "0041;A;Lu;0;Y;x;")),
                new ExtendedCase("([0-9]{4})-([0-9]{2})\\t(\\w+)\\t(\\w+)(?:\\t(.+))?", List.of("2022-07\tinfo\ttls", "2022-07\tinfo\ttls\t{x}", "2022-07\t\ttls", "2022-07\tinfo\ttls\njunk")),
                new ExtendedCase("(a)(b)?(c)\\b(-)?(x|y)(z)?", List.of("ac-x", "abc-yz", "acx", "abc dx", "ac-y", "ac-xzz")))) {
            String pattern = testCase.pattern();
            Prog program = compileProg(pattern);
            assertThat(program.isOnePass()).as(pattern).isTrue();
            int slots = 2 * (Re2.parse(utf8Slice(pattern)).capturingGroupCount() + 1);
            assertThat(program.supportsOnePassCaptureSlots(slots)).as(pattern).isTrue();
            assertThat(program.onePassActionCapture()).as(pattern).isNotNull();
            int matches = 0;
            for (int trial = 0; trial < 4000; trial++) {
                String sample = trial % 3 == 0
                        ? testCase.samples().get(random.nextInt(testCase.samples().size()))
                        : randomText(random, alphabet);
                Slice context = utf8Slice("##" + sample);
                int[] expected = new int[slots];
                int[] actual = new int[slots];
                boolean nfaMatched = Nfa.search(program, context, 2, context.length(), true, Prog.MatchKind.FULL_MATCH, expected);
                boolean onePassMatched = OnePass.search(program, context, 2, context.length(), true, Prog.MatchKind.FULL_MATCH, actual);
                assertThat(onePassMatched).as("%s on %s", pattern, sample).isEqualTo(nfaMatched);
                if (nfaMatched) {
                    matches++;
                    assertThat(actual).as("%s on %s", pattern, sample).containsExactly(expected);
                }
            }
            assertThat(matches).as(pattern).isPositive();
        }
    }

    /// The Unicode character data line pattern ends with an assertion that also matches before a
    /// final line terminator: Java's `$` or `\Z`, Java's `$` under `UNIX_LINES`, or Trino's `$`.
    /// On a line that ends in no such terminator, a fresh matcher's find runs OnePass as a full
    /// match, which allocates no capture scratch, even when the input is a view with a terminator
    /// after it. On a line that ends in one, the assertion can hold before it, so the find runs a
    /// first match. Either way the captures are the authority's.
    @Test
    public void testFinalTerminatorEndRunsFullMatchOnLineWithoutTerminator()
            throws ReflectiveOperationException
    {
        String pattern = "^" + TestingEngineAgreement.UNICODE_DATA_LINE_FIELDS;
        String line = "0041;LATIN CAPITAL LETTER A;Lu;0;L;;;;;N;;;;0061;";
        List<JavaCase> javaCases = List.of(
                JavaCase.compile(pattern, false),
                JavaCase.compile(pattern.substring(0, pattern.length() - 1) + "\\Z", false),
                JavaCase.compile("(?d)" + pattern, true));
        TrinoRegexp trino = TrinoRegexp.compile(utf8Slice(pattern));
        Regex joni = joniPattern(pattern);
        assertThat(trino.re2().hasAnchoredSubmatchRoute()).isTrue();
        Field trinoCoreMatcher = field(TrinoRegexpMatcher.class, "matcher");
        for (String end : List.of("", "\n", "\r\n", "\r", "\u0085", "\u2028")) {
            String text = line + end;
            for (Slice input : List.of(utf8Slice(text), utf8Slice(text + "\n").slice(0, utf8Slice(text).length()))) {
                String ending = "on a line ending in %s".formatted(end.codePoints().mapToObj("U+%04X"::formatted).toList());

                for (JavaCase javaCase : javaCases) {
                    String description = javaCase.pattern() + " " + ending;
                    Matcher expected = javaCase.jdk().matcher(text);
                    assertThat(expected.find()).as(description).isTrue();
                    RegexpMatcher javaMatcher = javaCase.regexp().matcher(input);
                    assertThat(javaMatcher.find()).as(description).isTrue();
                    for (int group = 0; group <= expected.groupCount(); group++) {
                        assertThat(javaMatcher.start(group)).as(description).isEqualTo(utf8Offset(text, expected.start(group)));
                        assertThat(javaMatcher.end(group)).as(description).isEqualTo(utf8Offset(text, expected.end(group)));
                    }
                    boolean javaScratch = javaCase.unixLines() ? end.endsWith("\n") : !end.isEmpty();
                    assertThat(hasCaptureScratch(javaMatcher)).as(description).isEqualTo(javaScratch);
                }

                String description = pattern + " " + ending;
                List<int[]> expected = joniMatches(joni, input);
                assertThat(expected).as(description).hasSize(1);
                TrinoRegexpMatcher trinoMatcher = trino.matcher(input);
                assertThat(trinoMatcher.find()).as(description).isTrue();
                for (int group = 0; group <= trino.capturingGroupCount(); group++) {
                    assertThat(trinoMatcher.start(group)).as(description).isEqualTo(expected.getFirst()[2 * group]);
                    assertThat(trinoMatcher.end(group)).as(description).isEqualTo(expected.getFirst()[2 * group + 1]);
                }
                assertThat(hasCaptureScratch((RegexpMatcher) trinoCoreMatcher.get(trinoMatcher))).as(description).isEqualTo(end.endsWith("\n"));
            }
        }
    }

    // Whether the matcher's OnePass searches allocated their capture scratch, which only a search
    // other than a full match does.
    private static boolean hasCaptureScratch(RegexpMatcher matcher)
            throws ReflectiveOperationException
    {
        Object workspace = field(RegexpMatcher.class, "onePassWorkspace").get(matcher);
        assertThat(workspace).isNotNull();
        Object captures = field(OnePass.Workspace.class, "captures").get(workspace);
        Object matchCaptures = field(OnePass.Workspace.class, "matchCaptures").get(workspace);
        assertThat(matchCaptures == null).isEqualTo(captures == null);
        return captures != null;
    }

    private static int utf8Offset(String input, int utf16Offset)
    {
        if (utf16Offset < 0) {
            return -1;
        }
        return utf8Slice(input.substring(0, utf16Offset)).length();
    }

    private static Field field(Class<?> type, String name)
            throws NoSuchFieldException
    {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String randomText(Random random, String alphabet)
    {
        StringBuilder text = new StringBuilder();
        int length = random.nextInt(20);
        for (int index = 0; index < length; index++) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }

    private record ExtendedCase(String pattern, List<String> samples) {}

    private record JavaCase(String pattern, JavaRegexp regexp, Pattern jdk, boolean unixLines)
    {
        static JavaCase compile(String pattern, boolean unixLines)
        {
            JavaRegexp regexp = JavaRegexp.compile(utf8Slice(pattern));
            assertThat(regexp.hasAnchoredSubmatchRoute()).as(pattern).isTrue();
            return new JavaCase(pattern, regexp, Pattern.compile(pattern), unixLines);
        }
    }
}
