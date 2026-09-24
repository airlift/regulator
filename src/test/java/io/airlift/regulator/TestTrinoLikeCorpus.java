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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.airlift.regulator.TestingTrinoLikeCorpus.Outcome.ERROR;
import static io.airlift.regulator.TestingTrinoLikeCorpus.Outcome.MATCH;
import static io.airlift.regulator.TrinoLikePattern.Plan.CONTAINS;
import static io.airlift.regulator.TrinoLikePattern.Plan.EMPTY;
import static io.airlift.regulator.TrinoLikePattern.Plan.ENDS_WITH;
import static io.airlift.regulator.TrinoLikePattern.Plan.EQUALS;
import static io.airlift.regulator.TrinoLikePattern.Plan.LITERAL_GAPS;
import static io.airlift.regulator.TrinoLikePattern.Plan.ORDERED_LITERALS;
import static io.airlift.regulator.TrinoLikePattern.Plan.STARTS_WITH;
import static io.airlift.regulator.TrinoLikePattern.Plan.TRINO_WILDCARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTrinoLikeCorpus
{
    @Test
    public void testCorpusShape()
    {
        List<TestingTrinoLikeCorpus.TestCase> cases = TestingTrinoLikeCorpus.load();
        assertThat(cases).hasSize(54);

        Set<String> names = new HashSet<>();
        for (TestingTrinoLikeCorpus.TestCase testCase : cases) {
            assertThat(names.add(testCase.name()))
                    .as("unique case name: %s", testCase.name())
                    .isTrue();
            assertThat(testCase.escapeCodePoint().isEmpty() || Character.isValidCodePoint(testCase.escapeCodePoint().orElseThrow()))
                    .as("valid escape code point: %s", testCase.name())
                    .isTrue();
        }

        assertThat(cases).anyMatch(testCase -> testCase.sqlOutcome() == ERROR);
        assertThat(cases).anyMatch(testCase -> testCase.sqlOutcome() != testCase.optimizedOutcome());
        assertThat(names).contains(
                "empty-empty",
                "redundant-percent",
                "ordered-dense-false",
                "escaped-mixed",
                "malformed-pattern-replacement-match",
                "underscore-invalid-continuation",
                "supplementary-nfa-quirk");
    }

    @Test
    public void testSqlCompatibilityCorpus()
    {
        for (TestingTrinoLikeCorpus.TestCase testCase : TestingTrinoLikeCorpus.load()) {
            if (testCase.name().equals("supplementary-nfa-quirk")) {
                continue;
            }
            if (testCase.sqlOutcome() == ERROR) {
                assertThatThrownBy(() -> compile(testCase))
                        .as(testCase.name())
                        .isInstanceOf(TrinoLikePatternSyntaxException.class);
                continue;
            }

            TrinoLikePattern pattern = compile(testCase);
            assertThat(pattern.matches(Slices.wrappedBuffer(testCase.input())))
                    .as(testCase.name())
                    .isEqualTo(testCase.sqlOutcome() == MATCH);
        }
    }

    @Test
    public void testNonZeroInputOffset()
    {
        TrinoLikePattern pattern = TrinoLikePattern.compile(Slices.utf8Slice("%needle%"));
        Slice input = Slices.utf8Slice("ignored-xxneedleyy-ignored").slice(8, 10);
        assertThat(pattern.matches(input)).isTrue();
    }

    @Test
    public void testDirectLiteralPlansWithMultibyteNonZeroInputOffset()
    {
        assertDirectLiteralPlan("π", "π", EQUALS, true);
        assertDirectLiteralPlan("π", "ρ", EQUALS, false);
        assertDirectLiteralPlan("π%", "π-tail", STARTS_WITH, true);
        assertDirectLiteralPlan("π%", "head-π", STARTS_WITH, false);
        assertDirectLiteralPlan("%π", "head-π", ENDS_WITH, true);
        assertDirectLiteralPlan("%π", "π-tail", ENDS_WITH, false);
        assertDirectLiteralPlan("%π%", "head-π-tail", CONTAINS, true);
        assertDirectLiteralPlan("%π%", "head-ρ-tail", CONTAINS, false);
    }

    @Test
    public void testSupplementaryLiteralBeforeWildcardUsesOneCodePoint()
    {
        TrinoLikePattern pattern = TrinoLikePattern.compile(Slices.utf8Slice("%😀_%"));

        assertThat(pattern.matches(Slices.utf8Slice("😀x"))).isTrue();
        assertThat(pattern.matches(Slices.utf8Slice("😀"))).isFalse();
    }

    @Test
    public void testPlanSelectionAndNormalization()
    {
        assertPlan("", EMPTY);
        assertPlan("needle", EQUALS);
        assertPlan("needle%%%", STARTS_WITH);
        assertPlan("%%%needle", ENDS_WITH);
        assertPlan("%%%needle%%%", CONTAINS);
        assertPlan("%alpha%%omega%", ORDERED_LITERALS);
        assertPlan("alpha%%omega", ORDERED_LITERALS);
        assertPlan("_", TRINO_WILDCARD);
        assertPlan("%alpha_omega%", LITERAL_GAPS);
        assertPlan("%a_b%", TRINO_WILDCARD);

        assertThat(TrinoLikePattern.compile(Slices.utf8Slice("\\%"), '\\').planForDiagnostics())
                .isEqualTo(EQUALS);
    }

    @Test
    public void testOrderedLiteralSharedSearchRoute()
    {
        TrinoLikePattern pattern = TrinoLikePattern.compile(Slices.utf8Slice("%alpha%omega%tail%"));
        assertThat(pattern.planForDiagnostics()).isEqualTo(ORDERED_LITERALS);
        assertThat(pattern.usesSharedOrderedLiteralSearchForDiagnostics()).isTrue();

        byte[] bytes = {'!', '!', (byte) 0xFF, 'a', 'l', 'p', 'h', 'a', '-', 'o', 'm', 'e', 'g', 'a', '-', 't', 'a', 'i', 'l', (byte) 0xC0, '!', '!'};
        Slice input = Slices.wrappedBuffer(bytes).slice(2, bytes.length - 4);
        assertThat(pattern.matches(input)).isTrue();
        assertThat(pattern.matches(Slices.utf8Slice("alpha-omega-miss"))).isFalse();
    }

    @Test
    public void testLiteralGapSharedSearchRoute()
    {
        TrinoLikePattern pattern = TrinoLikePattern.compile(Slices.utf8Slice("%alpha_bravo_charlie%"));
        assertThat(pattern.usesSharedLiteralGapSearchForDiagnostics()).isTrue();

        Slice backing = Slices.utf8Slice("!xxalpha💰bravo-charlieyy?");
        Slice input = backing.slice(1, backing.length() - 2);
        assertThat(pattern.matches(input)).isTrue();
        assertThat(pattern.matches(Slices.utf8Slice("alpha-bravocharlie"))).isFalse();
        assertThat(pattern.matches(Slices.utf8Slice("alpha-bravo-charlie"))).isTrue();
        assertThat(pattern.matches(Slices.utf8Slice("bravo-alpha-charlie"))).isFalse();

        TrinoLikePattern retry = TrinoLikePattern.compile(Slices.utf8Slice("%alpha_omega%"));
        assertThat(retry.matches(Slices.utf8Slice("alpha--alpha💰omega"))).isTrue();
        assertThat(retry.matches(Slices.utf8Slice("alpha\nomega"))).isTrue();

        TrinoLikePattern multipleCodePoints = TrinoLikePattern.compile(Slices.utf8Slice("%alpha__omega%"));
        assertThat(multipleCodePoints.usesSharedLiteralGapSearchForDiagnostics()).isTrue();
        assertThat(multipleCodePoints.matches(Slices.utf8Slice("alpha💰πomega"))).isTrue();
        assertThat(multipleCodePoints.matches(Slices.utf8Slice("alpha💰omega"))).isFalse();

        TrinoLikePattern denseSingleBytePrefix = TrinoLikePattern.compile(Slices.utf8Slice("%a_b%"));
        assertThat(denseSingleBytePrefix.usesSharedLiteralGapSearchForDiagnostics()).isFalse();
        assertThat(denseSingleBytePrefix.matches(Slices.utf8Slice("aaa"))).isFalse();
        assertThat(denseSingleBytePrefix.matches(Slices.utf8Slice("aaab"))).isTrue();
    }

    @Test
    public void testSimpleAsciiConstructionRoute()
    {
        assertSimpleAsciiConstruction("needle", true);
        assertSimpleAsciiConstruction("needle%", true);
        assertSimpleAsciiConstruction("%needle", true);
        assertSimpleAsciiConstruction("%needle%", true);

        assertSimpleAsciiConstruction("needle%%%", false);
        assertSimpleAsciiConstruction("%alpha%omega%", false);
        assertSimpleAsciiConstruction("_", false);
        assertSimpleAsciiConstruction("π", false);
    }

    @Test
    public void testCompiledSimplePatternDoesNotRetainCallerBuffer()
    {
        byte[] backing = "xxneedle%yy".getBytes(StandardCharsets.UTF_8);
        TrinoLikePattern pattern = TrinoLikePattern.compile(Slices.wrappedBuffer(backing).slice(2, 7));
        Arrays.fill(backing, (byte) 'x');

        assertThat(pattern.matches(Slices.utf8Slice("needle-value"))).isTrue();
        assertThat(pattern.matches(Slices.utf8Slice("xxxxxx-value"))).isFalse();
    }

    @Test
    public void testLiteralGapCountsFillTheGapArray()
    {
        // Elements: %, literal, then (any, literal) pairs; a gap is recorded for every pair, so the
        // array sized from the element count is exactly full when every element is used.
        List<TrinoLikeParser.Element> elements = TrinoLikeParser.parse(Slices.utf8Slice("%ab_cd__ef___gh"), OptionalInt.empty());
        assertThat(elements).hasSize(8);
        LiteralGapMatcher matcher = LiteralGapMatcher.analyze(elements, 0, elements.size() - 1, false);
        assertThat(matcher).isNotNull();
        assertThat(matcher.codePointGapsForDiagnostics()).containsExactly(1, 2, 3);
        assertThat(matches(matcher, "xabycdzzefzzzghx")).isTrue();
        assertThat(matches(matcher, "xabycdzzefzzghx")).isFalse();

        // A shorter element range records only the gaps inside it.
        LiteralGapMatcher shorter = LiteralGapMatcher.analyze(elements, 0, 5, false);
        assertThat(shorter).isNotNull();
        assertThat(shorter.codePointGapsForDiagnostics()).containsExactly(1, 2);
    }

    @Test
    public void testEscapedLiteralsDoNotAliasThePattern()
    {
        // An escape forces the buffered literal path; the compiled pattern must not observe later
        // writes to the caller's bytes, including bytes outside the pattern's slice.
        byte[] backing = "xxa\\%b_cyy".getBytes(StandardCharsets.UTF_8);
        Slice pattern = Slices.wrappedBuffer(backing, 2, backing.length - 4);
        TrinoLikePattern compiled = TrinoLikePattern.compile(pattern, '\\');
        assertThat(compiled.matches(Slices.utf8Slice("a%bxc"))).isTrue();
        assertThat(compiled.matches(Slices.utf8Slice("a%b\u00E9c"))).isTrue();
        assertThat(compiled.matches(Slices.utf8Slice("aXbxc"))).isFalse();
        assertThat(compiled.matches(Slices.utf8Slice("a%bc"))).isFalse();

        Arrays.fill(backing, (byte) 'z');
        assertThat(compiled.matches(Slices.utf8Slice("a%bxc"))).isTrue();
        assertThat(compiled.matches(Slices.utf8Slice("a%b\u00E9c"))).isTrue();
        assertThat(compiled.matches(Slices.utf8Slice("aXbxc"))).isFalse();
        assertThat(compiled.matches(Slices.utf8Slice("zzzzz"))).isFalse();
    }

    @Test
    public void testParsedLiteralsDecodeEscapesAndMalformedBytes()
    {
        byte[] storage = "xxab%c_d\\%e\\\\f".getBytes(StandardCharsets.UTF_8);
        Slice pattern = Slices.wrappedBuffer(storage, 2, storage.length - 2);
        List<TrinoLikeParser.Element> elements = TrinoLikeParser.parse(pattern, OptionalInt.of('\\'));
        assertThat(elements).hasSize(5);
        assertThat(literalText(elements.get(0))).isEqualTo("ab");
        assertThat(elements.get(1)).isEqualTo(TrinoLikeParser.ZeroOrMore.INSTANCE);
        assertThat(literalText(elements.get(2))).isEqualTo("c");
        assertThat(elements.get(3)).isEqualTo(new TrinoLikeParser.Any(1));
        assertThat(literalText(elements.get(4))).isEqualTo("d%e\\f");
        assertLiteralsCopied(elements, storage);

        Slice malformed = Slices.wrappedBuffer(new byte[] {'a', (byte) 0xFF, 'b', '%', (byte) 0xC3, (byte) 0xA9});
        List<TrinoLikeParser.Element> malformedElements = TrinoLikeParser.parse(malformed, OptionalInt.empty());
        assertThat(malformedElements).hasSize(3);
        assertThat(literalText(malformedElements.get(0))).isEqualTo("a\uFFFDb");
        assertThat(literalText(malformedElements.get(2))).isEqualTo("\u00E9");
    }

    @Test
    public void testEscapeErrors()
    {
        assertThatThrownBy(() -> TrinoLikePattern.compile(Slices.utf8Slice("abc\\"), '\\'))
                .isInstanceOf(TrinoLikePatternSyntaxException.class)
                .extracting("byteOffset")
                .isEqualTo(3);
        assertThatThrownBy(() -> TrinoLikePattern.compile(Slices.utf8Slice("abc\\x"), '\\'))
                .isInstanceOf(TrinoLikePatternSyntaxException.class)
                .extracting("byteOffset")
                .isEqualTo(4);
        TrinoLikePattern supplementaryEscape = TrinoLikePattern.compile(Slices.utf8Slice("😀%"), 0x1F600);
        assertThat(supplementaryEscape.matches(Slices.utf8Slice("%"))).isTrue();

        assertEscapeErrorOffset(new byte[] {(byte) 0xFF, '\\'}, 1);
        assertEscapeErrorOffset(new byte[] {(byte) 0xFF, '\\', 'x'}, 2);
        assertEscapeErrorOffset(new byte[] {'a', (byte) 0xC0, (byte) 0xAF, '\\', 'x'}, 4);

        byte[] backing = new byte[] {'!', '!', (byte) 0xFF, '\\', 'x', '!', '!'};
        Slice pattern = Slices.wrappedBuffer(backing).slice(2, 3);
        assertThatThrownBy(() -> TrinoLikePattern.compile(pattern, '\\'))
                .isInstanceOf(TrinoLikePatternSyntaxException.class)
                .extracting("byteOffset")
                .isEqualTo(2);
    }

    @Test
    public void testConcurrentMatching()
            throws Exception
    {
        TrinoLikePattern pattern = TrinoLikePattern.compile(Slices.utf8Slice("%alpha_omega%"));
        Slice match = Slices.utf8Slice("xxalpha💰omegayy");
        Slice miss = Slices.utf8Slice("xxalphaomegayy");
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int task = 0; task < 100; task++) {
                int taskId = task;
                futures.add(executor.submit(() -> pattern.matches((taskId & 1) == 0 ? match : miss)));
            }
            for (int task = 0; task < futures.size(); task++) {
                assertThat(futures.get(task).get()).isEqualTo((task & 1) == 0);
            }
        }
    }

    private static void assertPlan(String pattern, TrinoLikePattern.Plan expected)
    {
        assertThat(TrinoLikePattern.compile(Slices.utf8Slice(pattern)).planForDiagnostics())
                .as(pattern)
                .isEqualTo(expected);
    }

    private static void assertSimpleAsciiConstruction(String pattern, boolean expected)
    {
        assertThat(TrinoLikePattern.usesSimpleAsciiConstructionForDiagnostics(Slices.utf8Slice(pattern)))
                .as(pattern)
                .isEqualTo(expected);
    }

    private static void assertDirectLiteralPlan(String pattern, String input, TrinoLikePattern.Plan plan, boolean expected)
    {
        Slice logicalInput = Slices.utf8Slice(input);
        Slice offsetInput = Slices.utf8Slice("ignored-" + input + "-ignored").slice(8, logicalInput.length());
        TrinoLikePattern compiledPattern = TrinoLikePattern.compile(Slices.utf8Slice(pattern));

        assertThat(compiledPattern.planForDiagnostics()).as(pattern).isEqualTo(plan);
        assertThat(compiledPattern.matches(offsetInput)).as("%s against %s", pattern, input).isEqualTo(expected);
    }

    private static void assertEscapeErrorOffset(byte[] pattern, int byteOffset)
    {
        assertThatThrownBy(() -> TrinoLikePattern.compile(Slices.wrappedBuffer(pattern), '\\'))
                .isInstanceOf(TrinoLikePatternSyntaxException.class)
                .extracting("byteOffset")
                .isEqualTo(byteOffset);
    }

    private static TrinoLikePattern compile(TestingTrinoLikeCorpus.TestCase testCase)
    {
        Slice pattern = Slices.wrappedBuffer(testCase.pattern());
        if (testCase.escapeCodePoint().isPresent()) {
            return TrinoLikePattern.compile(pattern, testCase.escapeCodePoint().orElseThrow());
        }
        return TrinoLikePattern.compile(pattern);
    }

    private static String literalText(TrinoLikeParser.Element element)
    {
        return ((TrinoLikeParser.Literal) element).bytes().toStringUtf8();
    }

    // Every literal must be a copy, so later writes to the caller's array cannot reach it.
    private static void assertLiteralsCopied(List<TrinoLikeParser.Element> elements, byte[] callerArray)
    {
        for (TrinoLikeParser.Element element : elements) {
            if (element instanceof TrinoLikeParser.Literal literal) {
                assertThat(literal.bytes().byteArray()).isNotSameAs(callerArray);
            }
        }
    }

    private static boolean matches(LiteralGapMatcher matcher, String input)
    {
        Slice slice = Slices.utf8Slice(input);
        return matcher.matches(slice, 0, slice.length());
    }
}
