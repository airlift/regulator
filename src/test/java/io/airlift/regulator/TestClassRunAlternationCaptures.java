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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

public class TestClassRunAlternationCaptures
{
    // Holds 31 ASCII characters and U+0100: enough ranges for the compact route, and UTF-8 paths
    // short enough for its count DFA.
    private static final String SPARSE_CLASS = "[13579ACEGIKMOQSUWYacegikmoqsuwy\\x{100}]";

    private static final List<Function<String, CompiledPattern>> LANGUAGES = List.of(
            TestClassRunAlternationCaptures::re2,
            TestClassRunAlternationCaptures::java,
            TestClassRunAlternationCaptures::rust,
            TestClassRunAlternationCaptures::trino);

    // The rebar overlapping-words pattern, and smaller ones of the same shape: one of a smaller
    // class, one whose last branch is a single member, which the parser writes as the member
    // itself, one with named groups, one whose counter keeps a count DFA as its partial program,
    // one with more slots than the 64 the group copy takes, and one whose branches differ in
    // flags, admitted where the flag leaves the class as it is.
    private static final List<Expression> ELIGIBLE = List.of(
            new Expression(runs("\\p{L}", 14, 5)),
            new Expression(runs("\\p{Lu}", 3, 2)),
            new Expression(runs("\\pL", 2, 1)),
            new Expression("(?<long>\\p{L}{4})|(?<short>\\p{L}{3})"),
            new Expression(runs(SPARSE_CLASS, 3, 2)),
            new Expression(runs(SPARSE_CLASS, 33, 1)),
            new Expression("((?i:\\p{L}){3})|(\\p{L}{2})", List.of(TestClassRunAlternationCaptures::java, TestClassRunAlternationCaptures::trino)));

    private static final List<Slice> INPUTS = inputs();

    @Test
    public void testEligibility()
    {
        for (Expression eligible : ELIGIBLE) {
            for (Function<String, CompiledPattern> language : eligible.languages()) {
                CompiledPattern pattern = language.apply(eligible.expression());
                assertThat(pattern.capturesOnClassRun()).as(eligible.expression()).isTrue();
                // A matcher with groups finds on the counter as a matcher without them does.
                assertThat(pattern.matcher(utf8Slice("")).characterClassCounterEnabledForDiagnostics()).as(eligible.expression()).isTrue();
            }
        }
        for (Function<String, CompiledPattern> language : LANGUAGES) {
            assertThat(language.apply(runs(SPARSE_CLASS, 3, 2)).forwardProgramForDiagnostics().dfaMemory()).isPositive();
        }
        // Lengths that rise or skip a value, first or later; branches of a range of lengths;
        // lengths that change class; a branch that is not a group around a run, a group inside
        // one, or a group beside the runs; a last length of zero; a single branch; a class too
        // small for the compact route.
        String overlappingWords = ELIGIBLE.getFirst().expression();
        List<Expression> ineligible = List.of(
                new Expression("(\\p{L}{5})|(\\p{L}{6})"),
                new Expression("(\\p{L}{4})|(\\p{L}{3})|(\\p{L}{4})"),
                new Expression("(\\p{L}{7})|(\\p{L}{5})"),
                new Expression("(\\p{L}{4})|(\\p{L}{3})|(\\p{L}{1})"),
                new Expression("(\\p{L}{5,6})|(\\p{L}{4})"),
                new Expression("(\\p{L}{6})|(\\p{L}{4,5})"),
                new Expression("(\\p{L}{6})|(\\p{Lu}{5})"),
                new Expression("(\\p{L}{6})|\\p{L}{5}"),
                new Expression("((\\p{L}{6}))|(\\p{L}{5})"),
                new Expression("(\\p{L}{6})|((\\p{L}){5})"),
                new Expression("(\\p{L}{6})|(\\p{L}{5})|(\\b\\B)"),
                new Expression("(\\p{L}{2})|(\\p{L})|(\\p{L}{0})"),
                new Expression("(\\p{L}{6})"),
                new Expression("([A-Za-z]{6})|([A-Za-z]{5})"),
                // Longest match, Latin-1, a case-insensitive RE2 or Unicode-case Java pattern, and
                // Rust's swapped greed keep the program.
                new Expression(overlappingWords, List.of(expression -> Re2.compile(utf8Slice(expression), Re2.Options.defaults().setLongestMatch(true)))),
                new Expression(overlappingWords, List.of(expression -> Re2.compile(utf8Slice(expression), Re2.Options.latin1()))),
                new Expression(overlappingWords, List.of(expression -> Re2.compile(utf8Slice(expression), Re2.Options.defaults().setCaseSensitive(false)))),
                new Expression(overlappingWords, List.of(expression -> JavaRegexp.compile(utf8Slice(expression), JavaRegexp.Options.defaults().setCaseInsensitive(true).setUnicodeCase(true)))),
                new Expression(overlappingWords, List.of(expression -> RustRegexp.compile(utf8Slice(expression), RustRegexp.Options.defaults().setSwapGreed(true)))),
                // RE2 and Rust fold \\p{L} to a class with more members, such as U+0345, and Trino
                // keeps a folded class bracketed.
                new Expression("((?i:\\p{L}){3})|(\\p{L}{2})", List.of(TestClassRunAlternationCaptures::re2, TestClassRunAlternationCaptures::rust)),
                new Expression("(?i)([\\p{L}]{3})|([\\p{L}]{2})", List.of(TestClassRunAlternationCaptures::trino)));
        for (Expression expression : ineligible) {
            for (Function<String, CompiledPattern> language : expression.languages()) {
                assertThat(language.apply(expression.expression()).capturesOnClassRun()).as(expression.expression()).isFalse();
            }
        }
    }

    // The same alternation with one more branch that never matches keeps the program, and so
    // answers what the alternation's own groups should be.
    @Test
    public void testCapturesMatchProgram()
    {
        for (Expression eligible : ELIGIBLE) {
            String expression = eligible.expression();
            for (Function<String, CompiledPattern> language : eligible.languages()) {
                CompiledPattern pattern = language.apply(expression);
                CompiledPattern program = language.apply(expression + "|(\\b\\B)");
                assertThat(program.usesCompactBoundedCharacterClassForDiagnostics()).isFalse();
                assertThat(pattern.namedCapturingGroups()).isEqualTo(program.namedCapturingGroups());
                int groupCount = pattern.capturingGroupCount();
                // One matcher of each serves every input, with and without a region.
                RegexpMatcher actual = pattern.matcher(utf8Slice(""));
                RegexpMatcher expected = program.matcher(utf8Slice(""));
                for (Slice input : INPUTS) {
                    String label = "%s on %s".formatted(expression, input.toStringUtf8());
                    actual.reset(input);
                    expected.reset(input);
                    assertSameMatches(actual, expected, groupCount, label);
                    if (input.length() > 2) {
                        actual.reset(input, 1, input.length() - 1);
                        expected.reset(input, 1, input.length() - 1);
                        assertSameMatches(actual, expected, groupCount, label + " in a region");
                    }
                    actual.reset(input);
                    expected.reset(input);
                    boolean matches = expected.matches();
                    assertThat(actual.matches()).as(label).isEqualTo(matches);
                    if (matches) {
                        assertGroups(actual, expected, groupCount, label);
                    }
                    boolean lookingAt = expected.lookingAt();
                    assertThat(actual.lookingAt()).as(label).isEqualTo(lookingAt);
                    if (lookingAt) {
                        assertGroups(actual, expected, groupCount, label);
                    }
                    // Fewer retained groups, and offsets arrays shorter and longer than the groups.
                    for (int retained = 0; retained <= groupCount; retained++) {
                        assertSameMatches(pattern.matcher(input, retained), program.matcher(input, retained), retained, label + " retaining " + retained);
                    }
                    for (int slots = 1; slots <= groupCount + 2; slots++) {
                        int[] actualOffsets = new int[2 * slots];
                        int[] expectedOffsets = new int[2 * slots];
                        assertThat(pattern.findInto(input, actualOffsets)).as(label).isEqualTo(program.findInto(input, expectedOffsets));
                        assertThat(actualOffsets).as(label).isEqualTo(expectedOffsets);
                    }
                }
            }
        }
    }

    @Test
    public void testCapturesMatchJdk()
    {
        for (Expression eligible : ELIGIBLE) {
            String expression = eligible.expression();
            JavaRegexp pattern = JavaRegexp.compile(utf8Slice(expression));
            for (Slice input : INPUTS) {
                String text = input.toStringUtf8();
                if (!utf8Slice(text).equals(input)) {
                    continue;
                }
                Matcher expected = Pattern.compile(expression).matcher(text);
                RegexpMatcher actual = pattern.matcher(input);
                while (expected.find()) {
                    assertThat(actual.find()).isTrue();
                    for (int group = 0; group <= expected.groupCount(); group++) {
                        assertThat(actual.start(group)).as("%s group %s on %s", expression, group, text)
                                .isEqualTo(expected.start(group) < 0 ? -1 : utf8Slice(text.substring(0, expected.start(group))).length());
                        assertThat(actual.end(group)).isEqualTo(expected.end(group) < 0 ? -1 : utf8Slice(text.substring(0, expected.end(group))).length());
                    }
                }
                assertThat(actual.find()).isFalse();
            }
        }
    }

    @Test
    public void testTrinoFunctions()
    {
        for (Expression eligible : ELIGIBLE) {
            String expression = eligible.expression();
            TrinoRegexp pattern = TrinoRegexp.compile(utf8Slice(expression));
            TrinoRegexp program = TrinoRegexp.compile(utf8Slice(expression + "|(\\b\\B)"));
            for (Slice input : INPUTS) {
                for (int group = 0; group <= pattern.re2().capturingGroupCount(); group++) {
                    assertThat(pattern.extract(input, group)).isEqualTo(program.extract(input, group));
                    assertThat(pattern.extractAll(input, group)).isEqualTo(program.extractAll(input, group));
                }
                assertThat(pattern.replace(input, utf8Slice("<$1|$2>"))).isEqualTo(program.replace(input, utf8Slice("<$1|$2>")));
            }
        }
    }

    // The counter, about 34 KB, is all that the overlapping-words pattern retains beside its
    // placeholder program, so it compiles far below the default budget. A class whose bitset
    // reaches U+100000 costs more as a counter than as a program, so a budget too small for its
    // counter compiles the pattern's own program, the whole capturing alternation.
    @Test
    public void testCompilesOnCounterBudget()
    {
        String sparseHighRuns = runs("[13579ACEGIKMOQSUWYacegikmoqsuwy\\x{100000}]", 2, 1);
        for (BiFunction<String, Long, CompiledPattern> language : List.<BiFunction<String, Long, CompiledPattern>>of(
                (expression, maxMemory) -> Re2.compile(utf8Slice(expression), Re2.Options.defaults().setMaxMemory(maxMemory)),
                (expression, maxMemory) -> JavaRegexp.compile(utf8Slice(expression), JavaRegexp.Options.defaults().setMaxMemory(maxMemory)),
                (expression, maxMemory) -> RustRegexp.compile(utf8Slice(expression), RustRegexp.Options.defaults().setMaxMemory(maxMemory)),
                (expression, maxMemory) -> TrinoRegexp.compile(utf8Slice(expression), TrinoRegexp.Options.defaults().setMaxMemory(maxMemory)).re2())) {
            CompiledPattern pattern = language.apply(ELIGIBLE.getFirst().expression(), 128L * 1024);
            assertThat(pattern.capturesOnClassRun()).isTrue();
            RegexpMatcher matcher = pattern.matcher(utf8Slice("a  bcdefgh déjà-vu"));
            assertThat(matcher.find()).isTrue();
            // Seven letters: the branch of length 7, group 14 - 7 + 1.
            assertThat(matcher.start(8)).isEqualTo(3);
            assertThat(matcher.end(8)).isEqualTo(10);
            assertThat(matcher.start(1)).isEqualTo(-1);

            assertThat(language.apply(sparseHighRuns, (long) Re2.Options.DEFAULT_MAX_MEMORY).capturesOnClassRun()).isTrue();
            CompiledPattern program = language.apply(sparseHighRuns, 32L * 1024);
            assertThat(program.capturesOnClassRun()).isFalse();
            matcher = program.matcher(utf8Slice("AAA"));
            assertThat(matcher.find()).isTrue();
            assertThat(List.of(matcher.start(1), matcher.end(1), matcher.start(2), matcher.end(2))).containsExactly(0, 2, -1, -1);
            assertThat(matcher.find()).isTrue();
            assertThat(List.of(matcher.start(1), matcher.end(1), matcher.start(2), matcher.end(2))).containsExactly(-1, -1, 2, 3);
            assertThat(matcher.find()).isFalse();
        }
    }

    private static void assertSameMatches(RegexpMatcher actual, RegexpMatcher expected, int groupCount, String label)
    {
        while (true) {
            boolean found = expected.find();
            assertThat(actual.find()).as(label).isEqualTo(found);
            if (!found) {
                return;
            }
            assertGroups(actual, expected, groupCount, label);
        }
    }

    private static void assertGroups(RegexpMatcher actual, RegexpMatcher expected, int groupCount, String label)
    {
        for (int group = 0; group <= groupCount; group++) {
            assertThat(actual.start(group)).as("%s group %s", label, group).isEqualTo(expected.start(group));
            assertThat(actual.end(group)).as("%s group %s", label, group).isEqualTo(expected.end(group));
        }
    }

    // An expression and the languages, or compiles, that admit it or keep its program.
    private record Expression(String expression, List<Function<String, CompiledPattern>> languages)
    {
        Expression(String expression)
        {
            this(expression, LANGUAGES);
        }
    }

    private static CompiledPattern re2(String expression)
    {
        return Re2.compile(utf8Slice(expression));
    }

    private static CompiledPattern java(String expression)
    {
        return JavaRegexp.compile(utf8Slice(expression));
    }

    private static CompiledPattern rust(String expression)
    {
        return RustRegexp.compile(utf8Slice(expression));
    }

    private static CompiledPattern trino(String expression)
    {
        return TrinoRegexp.compile(utf8Slice(expression)).re2();
    }

    private static String runs(String characterClass, int longest, int shortest)
    {
        return IntStream.iterate(longest, length -> length >= shortest, length -> length - 1)
                .mapToObj(length -> "(%s{%s})".formatted(characterClass, length))
                .collect(joining("|"));
    }

    private static List<Slice> inputs()
    {
        List<Slice> inputs = new ArrayList<>();
        inputs.add(utf8Slice(""));
        inputs.add(utf8Slice("The quick brown fox jumped extraordinarily over incomprehensibilities, déjà-vu!"));
        inputs.add(utf8Slice("Съешь же ещё этих мягких французских булок, да выпей чаю: достопримечательность"));
        inputs.add(utf8Slice("αβγ δε ζηθικλμ 12345 abc1defghij été naïveté"));
        inputs.add(utf8Slice("1ĀĀ3 ĀĀĀĀ AceG 13579 kĀ"));
        for (String member : List.of("a", "я", "λ", "𝐀")) {
            StringBuilder text = new StringBuilder();
            for (int length = 1; length <= 31; length++) {
                text.append(member.repeat(length)).append(length % 3 == 0 ? "," : " ");
            }
            inputs.add(utf8Slice(text.toString()));
        }
        // Malformed UTF-8 inside and at the end of runs: a stray byte, a truncated two-byte
        // sequence, an overlong encoding, and a lone continuation byte.
        inputs.add(bytes("abcdefg", 0xFF, "hijklmnopqrs"));
        inputs.add(bytes("абвгдеж", 0xD0));
        inputs.add(bytes("abcdef", 0xC0, 0x80, "ghijklmnopqrstuvwxyz"));
        inputs.add(bytes("abc", 0x80, "defghijklmnop", 0xE2, 0x82));
        return List.copyOf(inputs);
    }

    private static Slice bytes(Object... parts)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object part : parts) {
            if (part instanceof String text) {
                out.writeBytes(utf8Slice(text).getBytes());
            }
            else {
                out.write((Integer) part);
            }
        }
        return wrappedBuffer(out.toByteArray());
    }
}
