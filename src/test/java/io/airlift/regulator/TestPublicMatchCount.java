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
import java.util.List;
import java.util.regex.Pattern;

import static io.airlift.regulator.TestingPatternShapes.assertNoProgramSearch;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static org.assertj.core.api.Assertions.assertThat;

public class TestPublicMatchCount
{
    @Test
    public void testShortSingleByteBooleanAndCountDoNotBuildDfa()
    {
        for (String expression : List.of("[,;]", "[a-z]", "(?i)[ab]", "\\C")) {
            Slice input = utf8Slice("!a;💰,b?").slice(1, 8);
            Re2 pattern = Re2.compile(utf8Slice(expression));
            Re2 oracle = Re2.compile(utf8Slice(expression));
            RegexpMatcher generic = oracle.genericGroupZeroMatcherForDiagnostics(input);
            long expected = 0;
            while (generic.find()) {
                expected++;
            }
            // Counts checked with the pinned native RE2 public matcher.
            assertThat(expected).isEqualTo(expression.equals("\\C") ? 8 : 2);
            assertThat(pattern.find(input)).isTrue();
            assertThat(pattern.count(input)).isEqualTo(expected);
            assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
            RegexpMatcher matcher = pattern.matcher(input, 0);
            long actual = 0;
            while (matcher.find()) {
                actual++;
            }
            assertThat(actual).isEqualTo(expected);
        }
    }

    @Test
    public void testSingleByteTableIsSharedAndBudgetedAtCompile()
    {
        TrinoRegexp trino = TrinoRegexp.compile(utf8Slice("[,;]"));
        TrinoCompiledPattern pattern = trino.re2();
        long reservedDfaMemory = pattern.forwardProgramForDiagnostics().dfaMemory();
        SingleByteMatcher shared = pattern.sharedSingleByteMatcher();
        assertThat(shared).isNotNull();
        pattern.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(pattern.findInto(utf8Slice("a,b;c"), null)).isTrue();
        assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNotNull();
        assertThat(trino.count(utf8Slice("a,b;c"))).isEqualTo(2);
        assertThat(pattern.matcher(utf8Slice("a,b;c"), 0).singleByteMatcherForDiagnostics()).isSameAs(shared);
        assertThat(pattern.forwardProgramForDiagnostics().dfaMemory()).isEqualTo(reservedDfaMemory);

        for (String expression : List.of("a", "(a)")) {
            TrinoRegexp literal = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(literal.isSingleByteMatcherComputed()).isTrue();
            assertThat(literal.matcher(utf8Slice("a"), 0).find()).isTrue();
            assertThat(literal.re2().forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        }

        Re2 smallBudget = Re2.compile(utf8Slice("[,;]"), Re2.Options.defaults().setMaxMemory(4864));
        assertThat(smallBudget.sharedSingleByteMatcher()).isNull();
        assertThat(smallBudget.count(utf8Slice("a,b;c"))).isEqualTo(2);
    }

    @Test
    public void testSingleByteEligibilityAndRegions()
    {
        for (String expression : List.of("^([ab])", "[ab]$", "\\b[ab]", "[é💰]", "(?i)k", "a?", "[ab]+")) {
            Re2 pattern = Re2.compile(utf8Slice(expression));
            assertThat(pattern.sharedSingleByteMatcher()).as(expression).isNull();
        }
        Re2 latin1 = Re2.compile(utf8Slice("."), Re2.Options.latin1());
        assertThat(latin1.sharedSingleByteMatcher()).isNotNull();
        assertThat(latin1.count(wrappedBuffer(new byte[] {(byte) 0xE9, '\n', (byte) 0xFF}))).isEqualTo(2);

        Slice input = utf8Slice("!;a,b;c,?").slice(1, 7);
        RegexpMatcher matcher = Re2.compile(utf8Slice("[,;]")).matcher(input, 0).reset(input, 1, 6);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(2);
        assertThat(matcher.end()).isEqualTo(3);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(4);
        assertThat(matcher.find()).isFalse();
        assertThat(matcher.reset(utf8Slice(",")).find()).isTrue();
    }

    @Test
    public void testPublicMatchersFindOnSingleByteTable()
    {
        // A group-zero matcher of a one-byte pattern finds on the single-byte table in every
        // language, apart from a Rust search of a partial region, which reads the input around
        // it, with the generic matcher's spans in every region of a Slice view that begins inside
        // its byte array.
        byte[] bytes = utf8Slice("!!x,é;\t \r\n,💰;y\u000B,").getBytes();
        Slice input = wrappedBuffer(bytes, 2, bytes.length - 3);
        List<CompiledPattern> patterns = List.of(
                Re2.compile(utf8Slice("[,;]")),
                Re2.compile(utf8Slice("\\s")),
                JavaRegexp.compile(utf8Slice(",")),
                JavaRegexp.compile(utf8Slice("\\s")),
                RustRegexp.compile(utf8Slice("[,;]")),
                RustRegexp.compile(utf8Slice("(?-u:\\s)")),
                RustRegexp.compile(utf8Slice("(?-u:[\\x80-\\xFF])"), RustRegexp.Options.bytes()));
        for (CompiledPattern pattern : patterns) {
            String expression = pattern.pattern().toStringUtf8();
            assertThat(pattern.matcher(input).singleByteMatcherForDiagnostics()).as(expression).isSameAs(pattern.sharedSingleByteMatcher());
            assertThat(pattern.matcher(input, 0).singleByteMatcherForDiagnostics()).as(expression).isSameAs(pattern.sharedSingleByteMatcher());
            assertThat(findSpans(pattern.matcher(input))).as(expression).isNotEmpty();
            assertNoProgramSearch(pattern, expression);
            for (int regionStart = 0; regionStart <= input.length(); regionStart++) {
                for (int regionEnd = regionStart; regionEnd <= input.length(); regionEnd++) {
                    assertThat(findSpans(pattern.matcher(input).reset(input, regionStart, regionEnd)))
                            .as("pattern %s region %s-%s", expression, regionStart, regionEnd)
                            .isEqualTo(findSpans(pattern.genericGroupZeroMatcherForDiagnostics(input).reset(input, regionStart, regionEnd)));
                }
            }
        }

        // A matcher that retains a capture keeps the engine, which reports it.
        Re2 captures = Re2.compile(utf8Slice("([,;])"));
        assertThat(captures.matcher(input).singleByteMatcherForDiagnostics()).isNull();
        assertThat(captures.matcher(input, 0).singleByteMatcherForDiagnostics()).isNotNull();
    }

    private static List<String> findSpans(RegexpMatcher matcher)
    {
        List<String> spans = new ArrayList<>();
        while (matcher.find()) {
            spans.add(matcher.start() + "-" + matcher.end());
        }
        return spans;
    }

    @Test
    public void testLongSingleByteOperationsRetainDfaAcceleration()
    {
        for (int length : List.of(63, 64, 65, 4096, 32768)) {
            for (String suffix : List.of("x", ",")) {
                Slice input = utf8Slice("!" + "x".repeat(length - 1) + suffix + "?").slice(1, length);
                Re2 pattern = Re2.compile(utf8Slice("[,;]"));
                assertThat(pattern.find(input)).isEqualTo(suffix.equals(","));
                assertThat(pattern.count(input)).isEqualTo(suffix.equals(",") ? 1 : 0);
                if (length > 64) {
                    assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNotNull();
                }
                else {
                    assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
                }
            }
        }
    }

    @Test
    public void testLiteralCountDoesNotBuildDfa()
    {
        for (String literal : List.of("a", "aa", "literal", "é", "💰")) {
            Slice input = utf8Slice("!" + literal.repeat(3) + "?");
            input = input.slice(1, input.length() - 2);
            Re2 pattern = Re2.compile(utf8Slice(literal));
            assertThat(pattern.hasExactLiteralSpan()).isTrue();
            assertThat(pattern.count(input)).isEqualTo(3);
            assertThat(pattern.count(utf8Slice(""))).isZero();
            assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        }
        Re2 overlapping = Re2.compile(utf8Slice("aa"));
        assertThat(overlapping.count(utf8Slice("aaaaa"))).isEqualTo(2);
        assertThat(overlapping.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        Re2 latin1 = Re2.compile(wrappedBuffer(new byte[] {(byte) 0xE9}), Re2.Options.latin1());
        assertThat(latin1.count(wrappedBuffer(new byte[] {(byte) 0xE9, 0, (byte) 0xE9}))).isEqualTo(2);
        assertThat(latin1.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();

        for (String literal : List.of("needle", "é", "💰")) {
            Slice input = utf8Slice("!" + "needlf".repeat(6000) + literal.repeat(2) + "?");
            input = input.slice(1, input.length() - 2);
            Re2 pattern = Re2.compile(utf8Slice(literal));
            assertThat(pattern.count(input)).isEqualTo(2);
            assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        }
    }

    @Test
    public void testMatchesIndependentJdkCounts()
    {
        for (String expression : List.of("aa", "literal", "é", "💰", "(aa)", "(?i)aa", "a+", "(a)(b)?", "a|aa", "aa|a", "^a", "a$", "\\b[a-z]+\\b", "[a-z]{2,4}", "[é💰]+")) {
            Re2 re2 = Re2.compile(utf8Slice(expression));
            JavaRegexp java = JavaRegexp.compile(utf8Slice(expression));
            Pattern jdk = Pattern.compile(expression);
            for (String text : List.of("", "aaaa", "a ab abc", "one_two three four5 five", "é💰 a é", "b")) {
                var input = utf8Slice("padding" + text + "tail").slice(7, utf8Slice(text).length());
                long expected = jdk.matcher(text).results().count();
                for (int pass = 0; pass < 2; pass++) {
                    assertThat(re2.count(input)).as("RE2 %s / %s", expression, text).isEqualTo(expected);
                    assertThat(java.count(input)).as("Java %s / %s", expression, text).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    public void testEmptyMatchFallback()
    {
        for (String expression : List.of("", "a*", "a?", "a*?", "^", "$", "\\b")) {
            Re2 re2 = Re2.compile(utf8Slice(expression));
            JavaRegexp java = JavaRegexp.compile(utf8Slice(expression));
            for (String text : List.of("", "aaa", "a b", "bb")) {
                long expected = Pattern.compile(expression).matcher(text).results().count();
                assertThat(re2.count(utf8Slice(text))).isEqualTo(expected);
                assertThat(java.count(utf8Slice(text))).isEqualTo(expected);
            }
        }
        // Empty matches advance using the frontend's existing UTF-8 matcher semantics.
        assertThat(Re2.compile(utf8Slice("")).count(utf8Slice("é💰"))).isEqualTo(3);
        assertThat(JavaRegexp.compile(utf8Slice("")).count(utf8Slice("é💰"))).isEqualTo(3);
        assertThat(Re2.compile(utf8Slice(""), Re2.Options.latin1()).count(utf8Slice("é💰"))).isEqualTo(7);
    }

    @Test
    public void testLongestMatchAndSmallMemoryFallback()
    {
        assertThat(Re2.compile(utf8Slice("a|aa"), Re2.Options.posix()).count(utf8Slice("aa"))).isEqualTo(1);
        Re2 re2 = Re2.compile(utf8Slice("(a|b)+c"), Re2.Options.defaults().setMaxMemory(5120));
        assertThat(re2.count(utf8Slice("aac bbc nope"))).isEqualTo(2);
    }

    @Test
    public void testSpanOutsideMatchLengthsFailsBeforeAnyEngine()
    {
        // A match of a pattern of bounded length anchored at both ends spans its whole input but
        // for the final line terminator that the language's $ allows, so a longer input holds no
        // match. RE2 and Rust $ matches only at the end, Trino $ also before a final \n as Joni's
        // does, and Java $ also before a final line terminator as the JDK's does, or a final \n
        // under UNIX_LINES. Every input below that holds no match is outside these lengths, so
        // its search runs no engine: it builds no DFA or reverse program, and its matcher sets up
        // no OnePass, BitState, or NFA workspace. The Trino rows check only the DFA and the
        // reverse program, as Trino's matcher reports no workspaces. A start-anchored scan plan,
        // interpreted or generated, leaves no such trace, so the rows of ^[ab]{2,3}$ prove only
        // that its answers stay right.
        String expression = "^((aaa)|(aa))$";
        String planExpression = "^[ab]{2,3}$";
        for (boolean generate : List.of(false, true)) {
            Re2.Options re2Options = Re2.Options.defaults().setGenerateBytecode(generate);
            RustRegexp.Options rustOptions = RustRegexp.Options.defaults().setGenerateBytecode(generate);
            for (String text : List.of("aa", "aaa", "a", "aaaa", "aaa\n")) {
                boolean expected = text.length() == 2 || text.equals("aaa");
                for (String spanExpression : List.of(expression, planExpression)) {
                    assertSpanSearch(Re2.compile(utf8Slice(spanExpression), re2Options), text, expected);
                    assertSpanSearch(RustRegexp.compile(utf8Slice(spanExpression), rustOptions), text, expected);
                }
            }
            // Trino's count checks only the span maximum, as its plans read a lone malformed byte
            // as one character. Without captures, the matcher's find of ^(?:aaa|aa)$ takes the
            // general route.
            TrinoRegexp.Options trinoOptions = TrinoRegexp.Options.defaults().setGenerateBytecode(generate);
            for (String trinoExpression : List.of(expression, "^(?:aaa|aa)$", planExpression)) {
                for (String text : List.of("aaa\n", "aaaa\n", "aaa\r\n")) {
                    TrinoRegexp trino = TrinoRegexp.compile(utf8Slice(trinoExpression), trinoOptions);
                    Slice input = utf8Slice(text);
                    boolean expected = text.equals("aaa\n");
                    String label = "Trino " + trinoExpression + " " + escaped(text) + " generated " + generate;
                    assertThat(trino.count(input)).as(label).isEqualTo(expected ? 1 : 0);
                    assertThat(trino.contains(input)).as(label).isEqualTo(expected);
                    assertThat(trino.matcher(input).find()).as(label).isEqualTo(expected);
                    if (!expected) {
                        assertNoProgramSearch(trino.re2(), label);
                    }
                }
            }
            JavaRegexp.Options javaOptions = JavaRegexp.Options.defaults().setGenerateBytecode(generate);
            for (String javaExpression : List.of(expression, "(?d)" + expression, planExpression, "(?d)" + planExpression)) {
                Pattern jdk = Pattern.compile(javaExpression);
                for (String text : List.of("aaa\n", "aaa\r\n", "aa\u2028", "aaa\u2028", "a", "aaaa\u2028", "aaa\u2028\n")) {
                    assertSpanSearch(JavaRegexp.compile(utf8Slice(javaExpression), javaOptions), text, jdk.matcher(text).find());
                }
            }
        }
        // The plan's rows reach the start-anchored plan, and the generated class with generation on.
        assertThat(Re2.compile(utf8Slice(planExpression)).startAnchoredScanPlan()).isNotNull();
        assertThat(RustRegexp.compile(utf8Slice(planExpression)).startAnchoredScanPlan()).isNotNull();
        assertThat(JavaRegexp.compile(utf8Slice(planExpression)).startAnchoredScanPlan()).isNotNull();
        assertThat(Re2.compile(utf8Slice(planExpression), Re2.Options.defaults().setGenerateBytecode(true)).usesGeneratedBytecode()).isTrue();
        assertThat(RustRegexp.compile(utf8Slice(planExpression), RustRegexp.Options.defaults().setGenerateBytecode(true)).usesGeneratedBytecode()).isTrue();
        assertThat(JavaRegexp.compile(utf8Slice(planExpression), JavaRegexp.Options.defaults().setGenerateBytecode(true)).usesGeneratedBytecode()).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice(planExpression), TrinoRegexp.Options.defaults().setGenerateBytecode(true)).usesGeneratedBytecode()).isTrue();

        // A required prefix counts toward the span.
        assertSpanSearch(Re2.compile(utf8Slice("^abc((aaa)|(aa))$")), "abcaaa", true);
        assertSpanSearch(Re2.compile(utf8Slice("^abc((aaa)|(aa))$")), "abcaaaa", false);

        // A count of a span shorter than the shortest match runs no engine, anchored or not.
        Re2 unanchored = Re2.compile(utf8Slice("\\w+@\\w+\\.\\w+"));
        assertThat(unanchored.count(utf8Slice("a@b"))).isZero();
        assertNoProgramSearch(unanchored, "unanchored count");
        // A generated unanchored plan overrides the count and makes the same check. Its plan
        // leaves no trace, so this row proves only that the answer stays right.
        Re2 generatedUnanchored = Re2.compile(utf8Slice("x([^/]+)/"), Re2.Options.defaults().setGenerateBytecode(true));
        assertThat(generatedUnanchored.usesGeneratedBytecode()).isTrue();
        assertThat(generatedUnanchored.count(utf8Slice("x/"))).isZero();
        assertNoProgramSearch(generatedUnanchored, "generated unanchored count");

        // A Java region bounds the span as the JDK's anchoring bounds do. U+2028 takes three
        // bytes, so the byte offsets 1 and 7 mark the JDK's char offsets 1 and 5.
        Pattern jdk = Pattern.compile(expression);
        String fits = "xaaa\u2028y";
        assertThat(JavaRegexp.compile(utf8Slice(expression)).matcher(utf8Slice(fits)).reset(utf8Slice(fits), 1, 7).find())
                .isEqualTo(jdk.matcher(fits).region(1, 5).find())
                .isTrue();
        String tooLong = "xaaaa\u2028y";
        JavaRegexp java = JavaRegexp.compile(utf8Slice(expression));
        RegexpMatcher tooLongMatcher = java.matcher(utf8Slice(tooLong)).reset(utf8Slice(tooLong), 1, 8);
        assertThat(tooLongMatcher.find())
                .isEqualTo(jdk.matcher(tooLong).region(1, 6).find())
                .isFalse();
        assertNoProgramSearch(java, "Java region");
        assertThat(tooLongMatcher.onePassWorkspaceInitializedForDiagnostics()).isFalse();
        assertThat(tooLongMatcher.bitStateWorkspaceInitializedForDiagnostics()).isFalse();
        assertThat(tooLongMatcher.nfaWorkspaceInitializedForDiagnostics()).isFalse();
    }

    private static void assertSpanSearch(CompiledPattern pattern, String text, boolean expected)
    {
        Slice input = utf8Slice(text);
        String label = pattern.getClass().getSimpleName() + " " + pattern.pattern().toStringUtf8() + " " + escaped(text);
        assertThat(pattern.count(input)).as(label).isEqualTo(expected ? 1 : 0);
        assertThat(pattern.find(input)).as(label).isEqualTo(expected);
        RegexpMatcher matcher = pattern.matcher(input);
        assertThat(matcher.find()).as(label).isEqualTo(expected);
        if (!expected) {
            assertNoProgramSearch(pattern, label);
            assertThat(matcher.onePassWorkspaceInitializedForDiagnostics()).as(label).isFalse();
            assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).as(label).isFalse();
            assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).as(label).isFalse();
        }
    }

    private static String escaped(String text)
    {
        return text.replace("\r", "\\r").replace("\n", "\\n").replace("\u2028", "\\u2028");
    }

    @Test
    public void testMalformedBytesFollowMatcher()
    {
        var input = wrappedBuffer(new byte[] {'a', (byte) 0xFF, (byte) 0xC3, 'b'});
        for (String expression : List.of("", ".", "[a-z]+")) {
            Re2 re2 = Re2.compile(utf8Slice(expression));
            RegexpMatcher matcher = re2.matcher(input, 0);
            long expected = 0;
            while (matcher.find()) {
                expected++;
            }
            assertThat(re2.count(input)).isEqualTo(expected);
        }

        // Trino's start-anchored plans read a lone malformed byte as one character, generated or
        // not, so its count can match a span shorter than the pattern's shortest valid encoding.
        Slice malformed = wrappedBuffer(new byte[] {(byte) 0xFF});
        for (boolean generate : List.of(false, true)) {
            TrinoRegexp trino = TrinoRegexp.compile(utf8Slice("^[^\\x00-\\x7f]$"), TrinoRegexp.Options.defaults().setGenerateBytecode(generate));
            TrinoRegexpMatcher matcher = trino.matcher(malformed);
            long expected = 0;
            while (matcher.find()) {
                expected++;
            }
            assertThat(expected).as("generated %s", generate).isEqualTo(1);
            assertThat(trino.count(malformed)).as("generated %s", generate).isEqualTo(expected).isEqualTo(trino.contains(malformed) ? 1 : 0);
        }
    }
}
