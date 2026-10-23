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

import io.airlift.jcodings.Config;
import io.airlift.jcodings.IntHolder;
import io.airlift.jcodings.constants.CharacterType;
import io.airlift.jcodings.specific.NonStrictUTF8Encoding;
import io.airlift.joni.Matcher;
import io.airlift.joni.Option;
import io.airlift.joni.Regex;
import io.airlift.joni.Syntax;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTrinoRegexpLanguage
{
    @Test
    public void testFullCharacterClassesAndDotNewlineMatchJoni()
    {
        for (String atom : List.of("[\\s\\S]", "[\\d\\D]", "[\\w\\W]", "[^\\s\\S]", ".", "(?s:.)", "(?-s:.)")) {
            for (String prefix : List.of("", "(?s)")) {
                for (String repeat : List.of("", "{0,100}", "+")) {
                    Slice pattern = utf8Slice(prefix + "a(" + atom + repeat + ")b");
                    TrinoRegexp regexp = TrinoRegexp.compile(pattern);
                    Regex joni = joniPattern(pattern);
                    for (String input : List.of("ab", "a\nb", "a\rb", "a\r\nb", "a\u0085b", "a\u2028b", "a\u2029b", "aé💰b", "xa\nby")) {
                        Slice source = utf8Slice("padding" + input).slice(7, utf8Slice(input).length());
                        byte[] bytes = source.getBytes();
                        Matcher expected = joni.matcher(bytes);
                        boolean matched = expected.search(0, bytes.length, Option.DEFAULT) >= 0;
                        assertThat(regexp.contains(source)).as("%s %s", pattern.toStringUtf8(), input).isEqualTo(matched);
                        if (matched) {
                            assertThat(regexp.extract(source)).isEqualTo(source.slice(expected.getBegin(), expected.getEnd() - expected.getBegin()));
                            var groups = expected.getEagerRegion();
                            assertThat(regexp.extract(source, 1)).isEqualTo(source.slice(groups.beg[1], groups.end[1] - groups.beg[1]));
                        }
                        else {
                            assertThat(regexp.extract(source)).isNull();
                        }
                    }
                }
            }
        }
    }

    @Test
    public void testOrderedLiteralContainsUsesJoniSemantics()
    {
        Slice pattern = utf8Slice("(first)(?s:.*)(second)");
        Slice backing = rawBytes('!', 'x', 'f', 'i', 'r', 's', 't', 0xFF, '\n', 's', 'e', 'c', 'o', 'n', 'd', 'y', '?');
        Slice input = backing.slice(1, backing.length() - 2);
        TrinoRegexp regexp = TrinoRegexp.compile(pattern);

        assertThat(regexp.usesOrderedLiteralMatcherForDiagnostics()).isTrue();
        assertThat(regexp.contains(input)).isEqualTo(joniFind(pattern, input));

        assertJoniContainsRoute("first(?s:.*)second", "first\nsecond", true);
        assertJoniContainsRoute("first(?s:.*)second", "second first", true);
        assertJoniContainsRoute("(?s:.*)first(?s:.*)second(?s:.*)", "xfirst💰secondy", true);
        assertJoniContainsRoute("first.*second", "first\nsecond", false);
        assertJoniContainsRoute("^first(?s:.*)second", "xfirst-second", false);
        assertJoniContainsRoute("first(?s:.*)second$", "first-secondx", false);
        assertJoniContainsRoute("(?i:first)(?s:.*)second", "FIRST-second", false);
        assertJoniContainsRoute("first.{2}second", "first--second", false);
        assertJoniContainsRoute("first\\b(?s:.*)second", "first-second", false);

        Slice validInput = utf8Slice("xfirst\nsecondy");
        assertThat(regexp.extract(validInput, 1)).isEqualTo(utf8Slice("first"));
        assertThat(regexp.extract(validInput, 2)).isEqualTo(utf8Slice("second"));
    }

    private static void assertJoniContainsRoute(String pattern, String input, boolean orderedLiteralRoute)
    {
        Slice patternSlice = utf8Slice(pattern);
        Slice inputSlice = utf8Slice(input);
        TrinoRegexp regexp = TrinoRegexp.compile(patternSlice);
        assertThat(regexp.usesOrderedLiteralMatcherForDiagnostics()).as(pattern).isEqualTo(orderedLiteralRoute);
        assertThat(regexp.contains(inputSlice)).as(pattern).isEqualTo(joniFind(patternSlice, inputSlice));
    }

    @Test
    public void testCompiledPatternDoesNotRetainMutablePatternStorage()
    {
        Slice pattern = utf8Slice("(?<name>a)");
        TrinoRegexp regexp = TrinoRegexp.compile(pattern);

        pattern.setByte(3, 'X');

        assertThat(regexp.replace(utf8Slice("a"), utf8Slice("${name}")).toStringUtf8()).isEqualTo("a");
    }

    @Test
    public void testFinalLineDollar()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("a$"));

        assertThat(regexp.extract(utf8Slice("a\n"))).isEqualTo(utf8Slice("a"));
        assertThat(TrinoRegexp.compile(utf8Slice("$")).count(utf8Slice("a\n"))).isEqualTo(2);
        assertThat(regexp.contains(utf8Slice("a\r"))).isFalse();
        assertThat(regexp.contains(utf8Slice("a\r\n"))).isFalse();
        assertThat(regexp.contains(utf8Slice("a\n\n"))).isFalse();

        Slice offsetInput = utf8Slice("xa\nx").slice(1, 2);
        assertThat(regexp.extract(offsetInput)).isEqualTo(utf8Slice("a"));

        // Pinned Joni incorrectly misses this valid empty match after a multibyte character.
        TrinoRegexp nullable = TrinoRegexp.compile(utf8Slice("(a?)$"));
        assertThat(nullable.extract(utf8Slice("中"))).isEqualTo(utf8Slice(""));
        assertThat(nullable.count(utf8Slice("中"))).isEqualTo(1);
    }

    @Test
    public void testFinalLineDollarUsesLiteralPlans()
    {
        Re2 pattern = TrinoRegexp.compile(utf8Slice("a$")).pattern();
        assertThat(pattern.findPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.ENDS_WITH_FINAL_LINE);
        assertThat(pattern.lookingAtPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.EQUALS_FINAL_LINE);
        assertThat(pattern.matchesPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.EQUALS);
        assertThat(pattern.expressionAnalysisForDiagnostics().literalSequence().finalLineEnd()).isTrue();

        assertThat(pattern.find(utf8Slice("xa"))).isTrue();
        assertThat(pattern.find(utf8Slice("xa\n"))).isTrue();
        assertThat(pattern.find(utf8Slice("xa\nx"))).isFalse();
        assertThat(pattern.lookingAt(utf8Slice("a\n"))).isTrue();
        assertThat(pattern.lookingAt(utf8Slice("xa\n"))).isFalse();
        assertThat(pattern.matches(utf8Slice("a"))).isTrue();
        assertThat(pattern.matches(utf8Slice("a\n"))).isFalse();
        assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH)).isNull();

        Re2 newline = TrinoRegexp.compile(utf8Slice("\\n$")).pattern();
        assertThat(newline.findPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.ENDS_WITH_FINAL_LINE);
        assertThat(newline.find(utf8Slice("a\n"))).isTrue();

        Re2 anchored = TrinoRegexp.compile(utf8Slice("\\Aa$")).pattern();
        assertThat(anchored.findPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.EQUALS_FINAL_LINE);
        assertThat(anchored.find(utf8Slice("a"))).isTrue();
        assertThat(anchored.find(utf8Slice("a\n"))).isTrue();
        assertThat(anchored.find(utf8Slice("xa\n"))).isFalse();

        Re2 exactEnd = TrinoRegexp.compile(utf8Slice("a$\\z")).pattern();
        assertThat(exactEnd.findPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.ENDS_WITH);
        assertThat(exactEnd.find(utf8Slice("a"))).isTrue();
        assertThat(exactEnd.find(utf8Slice("a\n"))).isFalse();
    }

    @Test
    public void testTerminalFinalLineDollarUsesLoweredBooleanProgram()
    {
        Re2 pattern = TrinoRegexp.compile(utf8Slice("[a-z]+$")).pattern();
        assertThat(pattern.findPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.LOWERED_PROGRAM);
        assertThat(pattern.lookingAtPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.LOWERED_PROGRAM);
        assertThat(pattern.matchesPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.GENERAL);
        assertThat(pattern.forwardProgramForDiagnostics().hasTextDependentAssertions()).isTrue();
        assertThat(pattern.forwardProgramForDiagnostics().dfaMemory()).isZero();
        assertThat(pattern.booleanProgramForDiagnostics()).isNotNull();
        assertThat(pattern.booleanProgramForDiagnostics().hasTextDependentAssertions()).isFalse();
        assertThat(pattern.booleanProgramForDiagnostics().dfaMemory()).isPositive();
        long forwardMemory = (Re2.Options.DEFAULT_MAX_MEMORY / 3L) * 2;
        assertThat(
                Compiler.estimatedProgramMemory(pattern.forwardProgramForDiagnostics()) +
                        Compiler.estimatedProgramMemory(pattern.booleanProgramForDiagnostics()) +
                        pattern.booleanProgramForDiagnostics().dfaMemory())
                .isEqualTo(forwardMemory);
        assertThat(pattern.booleanProgramForDiagnostics().onePassMemory()).isPositive();
        assertThat(pattern.isReverseProgramComputed()).isFalse();

        assertThat(pattern.matchInto(utf8Slice("123abc\n"), Re2.Anchor.UNANCHORED, null)).isTrue();
        assertThat(pattern.isReverseProgramComputed()).isFalse();

        assertThat(pattern.find(utf8Slice("123abc\n"))).isTrue();
        assertThat(pattern.isReverseProgramComputed()).isTrue();
        assertThat(pattern.reverseProgramIfComputedForDiagnostics()).isNotNull();
        assertThat(pattern.reverseProgramIfComputedForDiagnostics().hasTextDependentAssertions()).isFalse();

        assertThat(pattern.find(utf8Slice("123\n"))).isFalse();
        assertThat(pattern.lookingAt(utf8Slice("abc\n"))).isTrue();
        assertThat(pattern.lookingAt(utf8Slice("123abc\n"))).isFalse();
        assertThat(pattern.matches(utf8Slice("abc"))).isTrue();
        assertThat(pattern.matches(utf8Slice("abc\n"))).isFalse();

        Re2 alternation = TrinoRegexp.compile(utf8Slice("(?:foo|bar)+$")).pattern();
        assertThat(alternation.find(utf8Slice("prefixfoobar\n"))).isTrue();
        assertThat(alternation.find(utf8Slice("prefixbaz\n"))).isFalse();
        assertThat(alternation.isReverseProgramComputed()).isTrue();
        assertThat(alternation.reverseProgramIfComputedForDiagnostics()).isNotNull();
        assertThat(alternation.reverseProgramIfComputedForDiagnostics().hasTextDependentAssertions()).isFalse();

        Re2 nonterminal = TrinoRegexp.compile(utf8Slice("a$\\n")).pattern();
        assertThat(nonterminal.booleanProgramForDiagnostics()).isNull();
        assertThat(nonterminal.find(utf8Slice("a\n"))).isTrue();

        Re2 repeatedAssertion = TrinoRegexp.compile(utf8Slice("(?:a$)+")).pattern();
        assertThat(repeatedAssertion.booleanProgramForDiagnostics()).isNull();
        assertThat(repeatedAssertion.find(utf8Slice("a\n"))).isTrue();

        Re2 unicodeBoundary = TrinoRegexp.compile(utf8Slice("\\ba$")).pattern();
        assertThat(unicodeBoundary.booleanProgramForDiagnostics()).isNull();
        assertThat(unicodeBoundary.find(utf8Slice(" a\n"))).isTrue();

        Re2 fullFold = TrinoRegexp.compile(utf8Slice("(?i)ss$")).pattern();
        assertThat(fullFold.booleanProgramForDiagnostics()).isNull();
        assertThat(fullFold.find(utf8Slice("SS\n"))).isTrue();

        Re2 nullable = TrinoRegexp.compile(utf8Slice("(?:a?)*$")).pattern();
        assertThat(nullable.findPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.LOWERED_PROGRAM);
        assertThat(nullable.lookingAtPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.LOWERED_PROGRAM);
        assertThat(nullable.find(utf8Slice("bbb\n"))).isTrue();

        Re2 finalLineEnd = TrinoRegexp.compile(utf8Slice("$")).pattern();
        assertThat(finalLineEnd.findPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.LOWERED_PROGRAM);
        assertThat(finalLineEnd.find(utf8Slice("abc\n"))).isTrue();
        assertThat(finalLineEnd.lookingAt(utf8Slice("\n"))).isTrue();
        assertThat(finalLineEnd.lookingAt(utf8Slice("abc\n"))).isFalse();
    }

    @Test
    public void testTerminalFinalLineDollarResultOperationsUseSemanticProgram()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("([a-z]+)$"));
        Slice source = utf8Slice("123abc\n");

        int[] groups = new int[4];
        assertThat(regexp.pattern().findInto(source, groups)).isTrue();
        assertThat(groups).containsExactly(3, 6, 3, 6);

        MatchResult result = regexp.pattern().findResult(source);
        assertThat(result.start(0)).isEqualTo(3);
        assertThat(result.end(0)).isEqualTo(6);
        assertThat(result.groupSlice(0)).isEqualTo(utf8Slice("abc"));
        assertThat(result.groupSlice(1)).isEqualTo(utf8Slice("abc"));

        MatchResult rangedResult = regexp.pattern().findResult(source, 3, source.length());
        assertThat(rangedResult.start(0)).isEqualTo(3);
        assertThat(rangedResult.end(0)).isEqualTo(6);

        MatchResult lookingAtResult = regexp.pattern().lookingAtResult(utf8Slice("abc\n"));
        assertThat(lookingAtResult.start(0)).isZero();
        assertThat(lookingAtResult.end(0)).isEqualTo(3);
        assertThat(regexp.pattern().matchesResult(utf8Slice("abc"))).isNotNull();
        assertThat(regexp.pattern().matchesResult(utf8Slice("abc\n"))).isNull();

        assertThat(regexp.count(source)).isEqualTo(1);
        assertThat(regexp.position(source)).isEqualTo(4);
        assertThat(regexp.extract(source)).isEqualTo(utf8Slice("abc"));
        assertThat(regexp.extract(source, 1)).isEqualTo(utf8Slice("abc"));
        assertThat(regexp.extractAll(source)).containsExactly(utf8Slice("abc"));
        assertThat(regexp.extractAll(source, 1)).containsExactly(utf8Slice("abc"));
        assertThat(regexp.split(source)).containsExactly(utf8Slice("123"), utf8Slice("\n"));
        assertThat(regexp.replace(source, utf8Slice("_"))).isEqualTo(utf8Slice("123_\n"));
        assertThat(regexp.replace(source, utf8Slice("$1X"))).isEqualTo(utf8Slice("123abcX\n"));

        TrinoRegexp dotAll = TrinoRegexp.compile(utf8Slice("(?s:.*)$"));
        assertThat(dotAll.extract(utf8Slice("a\n"))).isEqualTo(utf8Slice("a\n"));
    }

    @Test
    public void testTerminalFinalLineDollarResultOperationsUseLoweredCandidate()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("([a-z]+)$"));
        Re2 pattern = regexp.pattern();

        assertThat(pattern.isReverseProgramComputed()).isFalse();
        assertThat(regexp.extract(utf8Slice("1111111111111111abc\n"))).isEqualTo(utf8Slice("abc"));
        assertThat(pattern.isReverseProgramComputed()).isTrue();
    }

    @Test
    public void testTerminalFinalLineDollarCandidateSearchIsBounded()
    {
        Re2 pattern = TrinoRegexp.compile(utf8Slice("([a-z]+)$")).pattern();

        Slice shortMatch = utf8Slice("1111111111111111abc\n");
        assertThat(pattern.findLoweredCandidateStart(shortMatch, 0, shortMatch.length(), 0)).isEqualTo(16);

        Slice noMatch = utf8Slice("11111111111111111111\n");
        assertThat(pattern.findLoweredCandidateStart(noMatch, 0, noMatch.length(), 0)).isEqualTo(Dfa.SEARCH_NO_MATCH);

        Slice longMatch = utf8Slice("a".repeat(1_024) + "\n");
        assertThat(pattern.findLoweredCandidateStart(longMatch, 0, longMatch.length(), 0))
                .isEqualTo(Dfa.CANDIDATE_SEARCH_FALLBACK);
    }

    @Test
    public void testTerminalFinalLineDollarGroupZeroBoundaryUsesLoweredProgram()
    {
        Re2 pattern = TrinoRegexp.compile(utf8Slice("(?:foo|bar)+$")).pattern();
        Slice input = utf8Slice("foo".repeat(1_024) + "\n");

        assertThat(pattern.findLoweredCandidateStart(input, 0, input.length(), 0))
                .isEqualTo(Dfa.CANDIDATE_SEARCH_FALLBACK);
        assertThat(pattern.findLoweredGroupZeroBoundary(input, 0, input.length(), 0))
                .isEqualTo(input.length() - 1L);

        Re2Matcher matcher = pattern.matcher(input, 0);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isEqualTo(input.length() - 1);
        assertThat(matcher.find()).isFalse();
    }

    @Test
    public void testTerminalFinalLineDollarGroupZeroBoundaryFallsBackWhenNotEquivalent()
    {
        Re2 newlineConsuming = TrinoRegexp.compile(utf8Slice("(?s:.+)$")).pattern();
        assertThat(newlineConsuming.findLoweredGroupZeroBoundary(utf8Slice("a\n"), 0, 2, 0))
                .isEqualTo(Dfa.CANDIDATE_SEARCH_FALLBACK);

        Re2 nullable = TrinoRegexp.compile(utf8Slice("[a-z]*$")).pattern();
        assertThat(nullable.findLoweredGroupZeroBoundary(utf8Slice("a\n"), 0, 2, 0))
                .isEqualTo(Dfa.CANDIDATE_SEARCH_FALLBACK);
    }

    @Test
    public void testLoweredProgramMemoryFallback()
    {
        Slice patternSlice = utf8Slice("(?:a+?){2,}$");
        Slice source = utf8Slice("aaa");

        for (long maxMemory = 512; maxMemory <= 64 * 1024; maxMemory += 64) {
            TrinoRegexp regexp;
            try {
                regexp = TrinoRegexp.compile(
                        patternSlice,
                        TrinoRegexp.Options.defaults().setMaxMemory(maxMemory));
            }
            catch (RegexpCompileMemoryLimitException ignored) {
                continue;
            }

            Re2 pattern = regexp.pattern();
            if (pattern.booleanProgramForDiagnostics() == null) {
                assertThat(pattern.forwardProgramForDiagnostics().hasTextDependentAssertions()).isTrue();
                assertThat(regexp.contains(source)).isTrue();
                assertThat(regexp.count(source)).isEqualTo(1);
                assertThat(regexp.position(source)).isEqualTo(1);
                assertThat(regexp.extract(source)).isEqualTo(source);
                assertThat(regexp.extractAll(source)).containsExactly(source);
                assertThat(regexp.split(source)).containsExactly(utf8Slice(""), utf8Slice(""));
                assertThat(regexp.replace(source, utf8Slice("_"))).isEqualTo(utf8Slice("_"));
                assertThat(regexp.replace(source, groups -> {
                    assertThat(groups).isEmpty();
                    return utf8Slice("_");
                })).isEqualTo(utf8Slice("_"));
                return;
            }
        }
        throw new AssertionError("no memory budget retained only the semantic program");
    }

    @Test
    public void testFinalLineDollarMatchesJoni()
    {
        for (String pattern : new String[] {
                "$",
                "a$",
                "\\n$",
                "a*$",
                "a+$",
                "(?:a|aa)$",
                "(?:ab|b)$",
                ".$",
                ".*$",
                "(?s:.*)$",
                "(?s:.*?)$",
                "[a-z]+$",
                "(?:a?)*$",
                "a$|b",
                "b|a$",
                "a$|a\\n$",
                "a\\n$|a$",
                "\\Aa$",
                "$\\n",
                "a$\\n",
                "(?:a$\\n)|b",
        }) {
            Slice patternSlice = utf8Slice(pattern);
            TrinoRegexp regexp = TrinoRegexp.compile(patternSlice);
            for (String input : new String[] {
                    "",
                    "a",
                    "aa",
                    "b",
                    "ab",
                    "ba",
                    "\n",
                    "a\n",
                    "aa\n",
                    "ab\n",
                    "a\n\n",
                    "\n\n",
                    "a\nb\n",
                    "中",
                    "中\n",
            }) {
                Slice inputSlice = utf8Slice(input);
                assertThat(regexp.contains(inputSlice))
                        .as("%s against %s", pattern, input)
                        .isEqualTo(joniFind(patternSlice, inputSlice));
            }
        }
    }

    @Test
    public void testMultilineBeginLineMatchesJoni()
    {
        assertMultilineMatches("(?m)^", "", List.of(new MatchBoundary(0, 0)));
        assertMultilineMatches("(?m)^", "\n", List.of(new MatchBoundary(0, 0)));
        assertMultilineMatches("(?m)^", "a\n", List.of(new MatchBoundary(0, 0)));
        assertMultilineMatches("(?m)^", "a\nb", List.of(new MatchBoundary(0, 0), new MatchBoundary(2, 2)));
        assertMultilineMatches("(?m)^", "a\n\n", List.of(new MatchBoundary(0, 0), new MatchBoundary(2, 2)));
        assertMultilineMatches("(?m)^", "a\rb", List.of(new MatchBoundary(0, 0)));
        assertMultilineMatches("(?m)^", "a\r\nb", List.of(new MatchBoundary(0, 0), new MatchBoundary(3, 3)));
        assertMultilineMatches("(?m)^$", "a\n", List.of());
        assertMultilineMatches("(?m)a\n^", "a\n", List.of());
        assertMultilineMatches("(?m)^[a-z]+", "abc", List.of(new MatchBoundary(0, 3)));
        assertMultilineMatches("(?m)^a*", "a\naa\n", List.of(new MatchBoundary(0, 1), new MatchBoundary(2, 4)));
        assertMultilineMatches("(?m)^a*", "a\n", List.of(new MatchBoundary(0, 1)));
        assertMultilineMatches("(?m)^\\z", "", List.of(new MatchBoundary(0, 0)));
        assertMultilineMatches("(?m)^\\z", "abc", List.of());
        assertMultilineMatches("(?m)^a*\\z", "aaa", List.of(new MatchBoundary(0, 3)));
        assertMultilineMatches("(?m)^a*\\z", "a\n", List.of());
        assertMultilineMatches("(?m)^\\n\\z", "\n", List.of(new MatchBoundary(0, 1)));
        assertMultilineMatches("(?m)^\\n\\z", "a\n", List.of());
    }

    @Test
    public void testMultilineBeginLineCompilesForBothDirections()
    {
        ParseResult parsed = TrinoRegexpParser.parse(utf8Slice("(?m)^"), Regexp.LIKE_PERL);
        Regexp regexp = Simplifier.simplify(parsed.regexp(), parsed.caseFolding());

        assertThat(regexp.parseFlags() & Regexp.TRINO_LINE).isEqualTo(Regexp.TRINO_LINE);
        assertThat(emptyWidthAssertions(Compiler.compileNormalized(regexp, false, 1 << 20, Compiler.Dialect.TRINO, parsed.caseFolding())))
                .containsExactly(EmptyOp.EMPTY_TRINO_BEGIN_LINE);
        assertThat(emptyWidthAssertions(Compiler.compileNormalized(regexp, true, 1 << 20, Compiler.Dialect.TRINO, parsed.caseFolding())))
                .containsExactly(EmptyOp.EMPTY_TRINO_END_LINE);

        Slice context = utf8Slice("!\na\n?").slice(1, 3);
        assertThat(assertionMatches(context, 0, EmptyOp.EMPTY_TRINO_BEGIN_LINE)).isTrue();
        assertThat(assertionMatches(context, 1, EmptyOp.EMPTY_TRINO_BEGIN_LINE)).isTrue();
        assertThat(assertionMatches(context, 2, EmptyOp.EMPTY_TRINO_BEGIN_LINE)).isFalse();
        assertThat(assertionMatches(context, 3, EmptyOp.EMPTY_TRINO_BEGIN_LINE)).isFalse();
        assertThat(assertionMatches(context, 0, EmptyOp.EMPTY_TRINO_END_LINE)).isFalse();
        assertThat(assertionMatches(context, 1, EmptyOp.EMPTY_TRINO_END_LINE)).isFalse();
        assertThat(assertionMatches(context, 2, EmptyOp.EMPTY_TRINO_END_LINE)).isTrue();
        assertThat(assertionMatches(context, 3, EmptyOp.EMPTY_TRINO_END_LINE)).isTrue();
    }

    @Test
    public void testMultilineBeginLineReverseMatchesJoniExhaustively()
    {
        List<String> inputs = new ArrayList<>();
        addInputs(inputs, new StringBuilder(), 4);

        for (String pattern : List.of("(?m)^[ab]+", "(?m)^a*", "(?m)^a*\\z", "(?m)^\\n\\z")) {
            for (String input : inputs) {
                assertMultilineMatchesJoni(pattern, input);
            }
        }
    }

    @Test
    public void testMultilineBeginLineUsesForwardAndReverseDfa()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("(?m)^[a-z]+"));
        Re2 pattern = regexp.pattern();

        assertThat(regexp.extract(utf8Slice("!abc?").slice(1, 3))).isEqualTo(utf8Slice("abc"));
        assertThat(regexp.extract(utf8Slice("123\nneedle\n456"))).isEqualTo(utf8Slice("needle"));
        assertThat(Dfa.DfaInstance.Kind.values())
                .anySatisfy(kind -> assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(kind)).isNotNull());
        assertThat(pattern.reverseProgramIfComputedForDiagnostics()).isNotNull();
        assertThat(Dfa.DfaInstance.Kind.values())
                .anySatisfy(kind -> assertThat(pattern.reverseProgramIfComputedForDiagnostics().cachedDfaIfPresent(kind)).isNotNull());
    }

    @Test
    public void testMultilineBeginLineReverseDfaRanges()
    {
        Prog reverseBeginLine = compileTrinoProgram("(?m)^", true);
        Slice context = utf8Slice("!a\nb\n?").slice(1, 4);

        assertThat(Dfa.search(reverseBeginLine, context, 0, 4, 0, 0, true, Prog.MatchKind.LONGEST_MATCH, true)).isZero();
        assertThat(Dfa.search(reverseBeginLine, context, 0, 4, 1, 1, true, Prog.MatchKind.LONGEST_MATCH, true)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        assertThat(Dfa.search(reverseBeginLine, context, 0, 4, 2, 2, true, Prog.MatchKind.LONGEST_MATCH, true)).isZero();
        assertThat(Dfa.search(reverseBeginLine, context, 0, 4, 4, 4, true, Prog.MatchKind.LONGEST_MATCH, true)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        assertThat(Dfa.search(reverseBeginLine, context, 1, 4, 1, 1, true, Prog.MatchKind.LONGEST_MATCH, true)).isZero();
        assertThat(Dfa.search(reverseBeginLine, utf8Slice("!?").slice(1, 0), 0, 0, true, Prog.MatchKind.LONGEST_MATCH, true)).isZero();

        Slice crlf = utf8Slice("!a\r\nb?").slice(1, 4);
        assertThat(Dfa.search(reverseBeginLine, crlf, 0, 4, 2, 2, true, Prog.MatchKind.LONGEST_MATCH, true)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        assertThat(Dfa.search(reverseBeginLine, crlf, 0, 4, 3, 3, true, Prog.MatchKind.LONGEST_MATCH, true)).isZero();

        Prog reverseWord = compileTrinoProgram("(?m)^[a-z]+", true);
        Slice words = utf8Slice("!a\nabc\n?").slice(1, 6);
        assertThat(Dfa.search(reverseWord, words, 0, 6, 0, 1, true, Prog.MatchKind.LONGEST_MATCH, true)).isZero();
        assertThat(Dfa.search(reverseWord, words, 0, 6, 2, 5, true, Prog.MatchKind.LONGEST_MATCH, true)).isZero();

        Prog reverseTerminalNewline = compileTrinoProgram("(?m)^\\n\\z", true);
        assertThat(Dfa.search(reverseTerminalNewline, utf8Slice("a\n"), 0, 2, true, Prog.MatchKind.LONGEST_MATCH, true))
                .isEqualTo(Dfa.SEARCH_NO_MATCH);
    }

    @Test
    public void testMultilineBeginLineDfaStartStatesDoNotAlias()
    {
        Re2 pattern = TrinoRegexp.compile(utf8Slice("(?m)^")).pattern();

        assertThat(matchBoundaries(pattern.matcher(utf8Slice("a\nb"))))
                .containsExactly(new MatchBoundary(0, 0), new MatchBoundary(2, 2));
        assertThat(matchBoundaries(pattern.matcher(utf8Slice("a\n"))))
                .containsExactly(new MatchBoundary(0, 0));
        assertThat(matchBoundaries(pattern.matcher(utf8Slice(""))))
                .containsExactly(new MatchBoundary(0, 0));
    }

    @Test
    public void testFinalLineBooleanOperationsMatchJoniExhaustively()
    {
        List<String> inputs = new ArrayList<>();
        addInputs(inputs, new StringBuilder(), 4);

        for (String patternText : List.of(
                "$",
                "a$",
                "a*$",
                "a+$",
                "[ab]+$",
                "(?:a|b)+$",
                "(?:ab|b)$",
                "(a+)$",
                "(?:a?)*$",
                "a$|b",
                "b|a$",
                "a$|b$",
                "(?:a|b$)",
                "\\Aa*$",
                ".$",
                ".*$",
                "(?s:.*)$",
                "(?:a$)+",
                "a$\\n")) {
            Slice patternSlice = utf8Slice(patternText);
            TrinoRegexp regexp = TrinoRegexp.compile(patternSlice);
            Regex joniPattern = joniPattern(patternSlice);

            for (String inputText : inputs) {
                Slice input = utf8Slice(inputText);
                byte[] inputBytes = input.getBytes();

                assertThat(regexp.contains(input))
                        .as("find %s against %s", patternText, inputText)
                        .isEqualTo(joniPattern.matcher(inputBytes).search(0, inputBytes.length, Option.DEFAULT) >= 0);

                Matcher lookingAtMatcher = joniPattern.matcher(inputBytes);
                assertThat(regexp.pattern().lookingAt(input))
                        .as("lookingAt %s against %s", patternText, inputText)
                        .isEqualTo(lookingAtMatcher.match(0, inputBytes.length, Option.DEFAULT) >= 0);

                Matcher matchesMatcher = joniPattern.matcher(inputBytes);
                boolean joniMatches = matchesMatcher.match(0, inputBytes.length, Option.DEFAULT) >= 0 &&
                        matchesMatcher.getBegin() == 0 &&
                        matchesMatcher.getEnd() == inputBytes.length;
                assertThat(regexp.pattern().matches(input))
                        .as("matches %s against %s", patternText, inputText)
                        .isEqualTo(joniMatches);
            }
        }
    }

    @Test
    public void testFinalLineCandidateBoundariesMatchJoniExhaustively()
    {
        List<String> inputs = new ArrayList<>();
        addInputs(inputs, new StringBuilder(), 4);

        for (String patternText : List.of(
                "$",
                "a*$",
                "a+$",
                "[ab]+$",
                "(?:a|b)+$",
                "(?:ab|b)$",
                "(a+)$",
                "(?:a?)*$",
                "\\Aa*$",
                ".$",
                ".*$",
                "(?s:.*)$")) {
            Slice patternSlice = utf8Slice(patternText);
            Re2 pattern = TrinoRegexp.compile(patternSlice).pattern();
            Regex joniPattern = joniPattern(patternSlice);

            for (String inputText : inputs) {
                Slice input = utf8Slice(inputText);
                assertThat(matchBoundaries(pattern.matcher(input)))
                        .as("%s against %s", patternText, inputText)
                        .isEqualTo(joniMatchBoundaries(joniPattern, input));
            }
        }
    }

    @Test
    public void testUnicodeWordBoundaries()
    {
        assertThat(TrinoRegexp.compile(utf8Slice("\\b中\\b")).contains(utf8Slice("中"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("\\b١\\b")).contains(utf8Slice("١"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("\\b²\\b")).contains(utf8Slice("²"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("\\b①\\b")).contains(utf8Slice("①"))).isFalse();
        assertThat(TrinoRegexp.compile(utf8Slice("\\b\u0301\\b")).contains(utf8Slice("\u0301"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("\\b\u200C\\b")).contains(utf8Slice("\u200C"))).isFalse();
        assertThat(TrinoRegexp.compile(utf8Slice("\\B\u200C\\B")).contains(utf8Slice("\u200C"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("\\B")).contains(utf8Slice("中"))).isFalse();
        // Unicode 18.0 added the letter U+0558 MODIFIER LETTER ARMENIAN SMALL EH.
        assertThat(TrinoRegexp.compile(utf8Slice("\\b\u0558\\b")).contains(utf8Slice("\u0558"))).isTrue();

        Slice offsetInput = utf8Slice("x中x").slice(1, 3);
        assertThat(TrinoRegexp.compile(utf8Slice("\\b中\\b")).contains(offsetInput)).isTrue();
    }

    // Properties hold Unicode 18.0 data on every JVM. Unicode 18.0 added U+0558 MODIFIER LETTER
    // ARMENIAN SMALL EH, a letter, and the Seal script, which starts at U+3D000.
    @Test
    public void testUnicode18Properties()
    {
        assertThat(TrinoRegexp.compile(utf8Slice("\\p{L}")).contains(utf8Slice("\u0558"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("\\p{Seal}")).contains(utf8Slice("\uD8B4\uDC00"))).isTrue();
    }

    @Test
    public void testWordClassificationIncludesPinnedJoniWordCharacters()
    {
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (NonStrictUTF8Encoding.INSTANCE.isCodeCType(codePoint, CharacterType.WORD) &&
                    !EmptyOp.isUnicodeWord(codePoint)) {
                throw new AssertionError(String.format(Locale.ROOT, "missing pinned Joni word character U+%04X", codePoint));
            }
        }
    }

    @Test
    public void testMultiCodePointCaseFolding()
    {
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)\\Aß\\z")).contains(utf8Slice("SS"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)\\Ass\\z")).contains(utf8Slice("ß"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)\\Aﬀ\\z")).contains(utf8Slice("FF"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)\\AStraße\\z")).contains(utf8Slice("STRASSE"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)\\Aß+\\z")).contains(utf8Slice("SSSS"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)\\A[s][s]\\z")).contains(utf8Slice("ß"))).isFalse();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)\\Assssssssssssssss\\z"))
                .contains(utf8Slice("ßßßßßßßß")))
                .isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)s")).contains(utf8Slice("ſ"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)seek")).contains(utf8Slice("ſeek"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)kilo")).contains(utf8Slice("Kilo"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)\\Qß\\E")).contains(utf8Slice("SS"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)\\Qſ\\E")).contains(utf8Slice("S"))).isTrue();

        TrinoRegexp captured = TrinoRegexp.compile(utf8Slice("(?i)(ß)"));
        assertThat(captured.extract(utf8Slice("SS"), 1)).isEqualTo(utf8Slice("SS"));
        assertThat(captured.count(utf8Slice("SSßss"))).isEqualTo(3);
    }

    @Test
    public void testAlternationPreservesFullCaseFolding()
    {
        for (String expression : List.of("ß|b", "b|ß", "\\Qß\\E|b", "ßa|ßb", "ssa|ssb", "ﬁa|ﬁb", "ssa|sta", "ffi|ffa", "ssx|ssy", "[ßb]", "(ß)|b")) {
            for (String input : List.of("ss", "SS", "ssa", "ßa", "ßx", "fia", "ﬃ", "b", "x", "SSßss")) {
                assertFirstMatchMatchesJoni("(?i)" + expression, input);
                Slice pattern = utf8Slice("(?i)" + expression);
                Slice text = utf8Slice(input);
                TrinoRegexp regexp = TrinoRegexp.compile(pattern);
                List<MatchBoundary> expected = joniMatchBoundaries(joniPattern(pattern), text);
                assertThat(regexp.contains(text)).as(expression + " on " + input).isEqualTo(!expected.isEmpty());
                Slice first = expected.isEmpty() ? null : text.slice(expected.getFirst().start(), expected.getFirst().end() - expected.getFirst().start());
                assertThat(regexp.extract(text)).as(expression + " on " + input).isEqualTo(first);
                assertThat(regexp.count(text)).as(expression + " on " + input).isEqualTo(expected.size());
            }
        }
        Re2 ordinary = TrinoRegexp.compile(utf8Slice("(?i)hello|help")).pattern();
        assertThat(ordinary.forwardProgramForDiagnostics().hasFullCaseFold()).isFalse();
        assertThat(ordinary.reverseProgramSize()).isGreaterThan(0);
        assertThat(ordinary.regexp().op()).isEqualTo(RegexpOp.CONCAT);
        assertThat(ordinary.regexp().child(0).runes()).containsExactly('h', 'e', 'l');
        assertThat(ordinary.regexp().child(1).op()).isEqualTo(RegexpOp.ALTERNATE);

        for (String pattern : List.of("(?i)(ß|b)", "((?i:ß)|b)", "((?i:ssa)|sta)")) {
            Slice patternSlice = utf8Slice(pattern);
            for (String input : List.of("SS", "ßa", "b", "B", "STA", "x")) {
                Slice text = utf8Slice(input);
                Matcher matcher = joniPattern(patternSlice).matcher(text.getBytes());
                int match = matcher.search(0, text.length(), Option.DEFAULT);
                Slice expected = match < 0 ? null : text.slice(matcher.getRegion().beg[1], matcher.getRegion().end[1] - matcher.getRegion().beg[1]);
                assertThat(TrinoRegexp.compile(patternSlice).extract(text, 1)).as(pattern + " on " + input).isEqualTo(expected);
            }
        }
        assertThat(TrinoRegexp.compile(utf8Slice("ß|b")).contains(utf8Slice("ss"))).isFalse();
    }

    @Test
    public void testRepetitionPreservesFullCaseFolding()
    {
        for (String body : List.of("s{1}ss", "s+ss", "s?ss", "s*ss", "f{1}ff", "f+ff", "f?ff", "f*ff", "ff{1}|fx")) {
            for (String input : List.of("sß", "Sß", "sss", "SSS", "ß", "ss", "x", "fﬀ", "Fﬀ", "fff", "ﬀ")) {
                assertFirstMatchMatchesJoni("(?i:" + body + ")", input);
                assertFirstMatchMatchesJoni("(?i:(" + body + "))", input);
                Slice text = utf8Slice(input);
                Slice expression = utf8Slice("(?i:(" + body + "))");
                Matcher matcher = joniPattern(expression).matcher(text.getBytes());
                int match = matcher.search(0, text.length(), Option.DEFAULT);
                Slice expected = match < 0 ? null : text.slice(matcher.getRegion().beg[1], matcher.getRegion().end[1] - matcher.getRegion().beg[1]);
                assertThat(TrinoRegexp.compile(expression).extract(text, 1)).as(body + " on " + input).isEqualTo(expected);
            }
        }
        Re2 pattern = TrinoRegexp.compile(utf8Slice("(?i:s+ss)")).pattern();
        assertThat(pattern.forwardProgramForDiagnostics().hasFullCaseFold()).isTrue();
        assertThat(Nfa.fullMatch(pattern.forwardProgramForDiagnostics(), utf8Slice("sß"))).isTrue();
        assertFirstMatchMatchesJoni("(".repeat(270) + "(?i:s+ss)" + ")".repeat(270), "sß");
        assertNoMatchMatchesJoni("(".repeat(270) + "(?i)s{2}ss" + ")".repeat(270), "sßs");
        assertFirstMatchMatchesJoni("(?i:s{1})(?-i:ss)", "Sss");
        assertFirstMatchMatchesJoni("(?i:s{1})(?-i:ss)", "SSS");
        // Anchors expose the old required-prefix split; these inputs already matched without them.
        assertFirstMatchMatchesJoni("\\A(?i:ff{1})\\z", "ﬀ");
        assertFirstMatchMatchesJoni("\\A(?i:ff{1}?)\\z", "ﬀ");
        assertFirstMatchMatchesJoni("\\A(?i:ff{1,1})\\z", "ﬀ");
        assertThat(TrinoRegexp.compile(utf8Slice("(?i:a+aa)")).pattern().forwardProgramForDiagnostics().hasFullCaseFold()).isFalse();
        // Capital sharp S joined Unicode in 5.1; pinned Joni's fold tables are Unicode 4.1 data,
        // so it does not fold it. Regulator follows its pinned Unicode full case folding.
        assertThat(TrinoRegexp.compile(utf8Slice("(?i:ss)")).extract(utf8Slice("ẞ"))).isEqualTo(utf8Slice("ẞ"));
        assertThat(TrinoRegexp.compile(utf8Slice("(?i:s{1}ss)")).extract(utf8Slice("sẞ"))).isEqualTo(utf8Slice("sẞ"));
    }

    @Test
    public void testFullFoldSpansLiteralRuns()
    {
        // Literals written next to each other under the same case-insensitivity fold as one
        // sequence, through escapes, quotes, extended-mode whitespace and comments, {1}, other
        // flag changes, and the bounds of unquantified noncapturing groups, whatever those groups
        // contain. Pinned Joni ends a run where its tokenization splits the source.
        for (String[] example : List.of(
                new String[] {"(?i:s(?:s))", "ß"},
                new String[] {"(?i:sß)", "ßs"},
                new String[] {"(?i:f{1}f)", "ﬀ"},
                new String[] {"(?i:\\x66f)", "ﬀ"},
                new String[] {"(?i:\\Qf\\Ef)", "ﬀ"},
                new String[] {"(?i:f\\Q\\Ef)", "ﬀ"},
                new String[] {"(?i:f(?s)f)", "ﬀ"},
                new String[] {"(?i)aaİ", "aaİ"},
                // Anchors expose the old required-prefix split; this input already matched
                // without them.
                new String[] {"\\A(?i:f(?:f(?i)){1})\\z", "ﬀ"},
                new String[] {"(?i:f(?:f(?i)){1}|fx)", "ﬀ"},
                new String[] {"(?i)(?:[a]s)s", "aß"},
                new String[] {"(?i)s(?:s[a])", "ßa"},
                new String[] {"(?i)(?:x(?-i)(?i)s)s", "xß"})) {
            Slice input = utf8Slice(example[1]);
            assertThat(TrinoRegexp.compile(utf8Slice(example[0])).extract(input)).as(example[0]).isEqualTo(input);
            assertThat(joniMatchBoundaries(joniPattern(utf8Slice(example[0])), input)).as(example[0]).isEmpty();
        }
        assertFirstMatchBoundaryMatchesJoni("(?ix)f # comment\nf", "ﬀ", new MatchBoundary(0, 3));
        // Pinned Joni rewrites an exact repeat into one string, so its (?i)ß{2} matches sßs.
        Slice sharpSInput = utf8Slice("sßs");
        assertThat(TrinoRegexp.compile(utf8Slice("(?i)ß{2}")).extract(sharpSInput)).isNull();
        assertThat(joniMatchBoundaries(joniPattern(utf8Slice("(?i)ß{2}")), sharpSInput)).containsExactly(new MatchBoundary(0, 4));
        // Any other construct ends the run, even one that matches only the empty string or cannot
        // match at all, and so does any change of case-insensitivity.
        for (String[] example : List.of(
                new String[] {"(?i)s{2}ss", "sßs"},
                new String[] {"(?i)f{2}i", "fﬁ"},
                new String[] {"(?i)ff{0}f", "ﬀ"},
                new String[] {"(?i)f(?:)*f", "ﬀ"},
                new String[] {"(?i)f(?:f|[a&&b])", "ﬀ"},
                new String[] {"(?i)f\\Bf", "ﬀ"},
                new String[] {"(?i)s(?-i)(?i)s", "ß"},
                new String[] {"(?i)s(s)", "ß"})) {
            assertNoMatchMatchesJoni(example[0], example[1]);
        }
    }

    @Test
    public void testDocumentedDifferencesFromPinnedJoni()
    {
        for (MatchCase matchCase : List.of(
                // Trino's pinned Joni omits the nine ASCII symbols $+<=>^`|~ from bracket punct; modern Joni
                // includes them.
                new MatchCase("\\A[[:punct:]]+\\z", "$+<=>^`|~", true),
                new MatchCase("[[:^punct:]]", "$", false),
                // Trino's pinned Joni drops a dash after a completed class range, and reads a bracket after that
                // dash as a literal; modern Joni reads the dash as a literal and the bracket as a nested class.
                new MatchCase("[a-zA-Z0-9-_]", "-", true),
                new MatchCase("[a-c[x]-z]", "-", true),
                new MatchCase("[a-c-é]", "-", true),
                new MatchCase("[a-c-[x]]", "-", true),
                new MatchCase("[a-c-[x]]", "[", false),
                new MatchCase("(?i)[a-c-e]", "-", true),
                new MatchCase("[^a-c-e]", "-", false))) {
            Slice pattern = utf8Slice(matchCase.pattern());
            Slice input = utf8Slice(matchCase.input());
            assertThat(TrinoRegexp.compile(pattern).contains(input))
                    .as("%s against %s", matchCase.pattern(), matchCase.input())
                    .isEqualTo(matchCase.expected())
                    .isNotEqualTo(joniFind(pattern, input));
        }
        // Pinned Joni fails on a class escape after such a dash.
        assertThat(TrinoRegexp.compile(utf8Slice("[a-c-\\d]")).contains(utf8Slice("-"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8Slice("[a-c-\\d]")).contains(utf8Slice("5"))).isTrue();
        assertThatThrownBy(() -> joniPattern(utf8Slice("[a-c-\\d]"))).isInstanceOf(ArrayIndexOutOfBoundsException.class);

        // Pinned Joni reads \\u12 as U+0012 and [\\u] as U+0000; modern Joni rejects both.
        assertThatThrownBy(() -> TrinoRegexp.compile(utf8Slice("\\u12"))).isInstanceOf(RegexpParseException.class);
        assertThat(joniFind(utf8Slice("\\u12"), utf8Slice("\u0012"))).isTrue();
        assertThatThrownBy(() -> TrinoRegexp.compile(utf8Slice("[\\u]"))).isInstanceOf(RegexpParseException.class);
        assertThat(joniFind(utf8Slice("[\\u]"), utf8Slice("\u0000"))).isTrue();

        // Pinned Joni wraps an octal escape above \377 to a byte, so \400 matches NUL; modern Joni rejects it.
        assertThatThrownBy(() -> TrinoRegexp.compile(utf8Slice("\\400"))).isInstanceOf(RegexpParseException.class);
        assertThat(joniFind(utf8Slice("\\400"), utf8Slice("\u0000"))).isTrue();

        // Both Jonis wrap a decimal escape that overflows an int, so \\8589934593 is a backreference to group 1;
        // Oniguruma reads it as digits.
        assertThat(TrinoRegexp.compile(utf8Slice("(a)\\8589934593")).contains(utf8Slice("a8589934593"))).isTrue();
        assertThat(joniFind(utf8Slice("(a)\\8589934593"), utf8Slice("aa"))).isTrue();
    }

    @Test
    public void testCaseInsensitiveClassesMatchJoniFoldSpellings()
    {
        // Characters whose full fold is the same in Joni's Unicode 4.1 tables and the pinned
        // database, grouped by fold, so table differences such as capital sharp s stay out.
        Set<Integer> candidates = new TreeSet<>();
        for (UnicodeFullCaseFold.FoldToken token : CaseFolding.TRINO.multiCharacterTokens()) {
            addCodePoints(candidates, token.sourceRunes());
            for (int foldedRune : token.foldedRunes()) {
                addCodePoints(candidates, CaseFolding.TRINO.simpleFoldClass(foldedRune));
            }
        }
        Map<List<Integer>, List<Integer>> charactersByFold = new HashMap<>();
        List<Integer> sources = new ArrayList<>();
        for (int codePoint : candidates) {
            int[] fold = CaseFolding.TRINO.fold(new int[] {codePoint});
            if (Arrays.equals(fold, joniFold(codePoint))) {
                charactersByFold.computeIfAbsent(Arrays.stream(fold).boxed().toList(), _ -> new ArrayList<>()).add(codePoint);
                if (fold.length > 1) {
                    sources.add(codePoint);
                }
            }
        }
        // Guard against silently omitting Joni-compatible full-fold sources from this corpus.
        assertThat(sources).hasSize(103);

        for (int source : sources) {
            // Every whole-character spelling of the source's fold, and each without its last
            // character.
            Set<String> inputs = new LinkedHashSet<>();
            addFoldSpellings(inputs, charactersByFold, CaseFolding.TRINO.fold(new int[] {source}), 0, new StringBuilder());
            for (String spelling : List.copyOf(inputs)) {
                inputs.add(spelling.substring(0, spelling.offsetByCodePoints(spelling.length(), -1)));
            }
            String member = String.format(Locale.ROOT, "\\x{%x}", source);
            String nextMember = String.format(Locale.ROOT, "\\x{%x}", source + 1);
            for (String pattern : List.of("(?i)[" + member + "]", "(?i)[" + member + "-" + nextMember + "]", "(?i)[^" + member + "]")) {
                Slice patternSlice = utf8Slice(pattern);
                Re2 regexp = TrinoRegexp.compile(patternSlice).pattern();
                Regex joni = joniPattern(patternSlice);
                for (String input : inputs) {
                    Slice inputSlice = utf8Slice(input);
                    assertThat(matchBoundaries(regexp.matcher(inputSlice)))
                            .as("%s against %s", pattern, input.codePoints().mapToObj(codePoint -> String.format(Locale.ROOT, "U+%04X", codePoint)).toList())
                            .isEqualTo(joniMatchBoundaries(joni, inputSlice));
                }
            }
        }
    }

    @Test
    public void testCaseInsensitiveClassFoldOrderAndBoundaries()
    {
        // A positive class tries its characters first and then its members' multi-character
        // folds; a fold precedes any longer fold that begins with it. A fold never merges with
        // adjacent pattern literals, and a negated class consumes one character.
        assertFirstMatchBoundaryMatchesJoni("(?i)[sß]", "ss", new MatchBoundary(0, 1));
        assertFirstMatchBoundaryMatchesJoni("(?i)[fﬃ]", "ffi", new MatchBoundary(0, 1));
        assertFirstMatchBoundaryMatchesJoni("(?i)[ﬀﬃ]", "ffi", new MatchBoundary(0, 2));
        assertNoMatchMatchesJoni("(?i)\\A[^sß]\\z", "ss");
        assertNoMatchMatchesJoni("(?i)[ß]s", "sß");
        assertNoMatchMatchesJoni("(?i)s[ß]", "ßs");
        assertNoMatchMatchesJoni("(?i)[ß]{2}", "sßs");
        assertNoMatchMatchesJoni("(?i)[s]", "ß");
        assertNoMatchMatchesJoni("(?i)[ﬀ]", "ﬃ");
        assertNoMatchMatchesJoni("(?i)\\A[fﬁ]\\z", "ffi");
    }

    @Test
    public void testSpecialSemanticsOnlyDisableIncompatibleEngines()
    {
        Re2 finalLine = TrinoRegexp.compile(utf8Slice("a$")).pattern();
        assertThat(finalLine.forwardProgramForDiagnostics().hasTextDependentAssertions()).isTrue();
        assertThat(finalLine.forwardProgramForDiagnostics().isOnePass()).isFalse();
        assertThat(finalLine.reverseProgramSize()).isEqualTo(-1);

        Re2 fullFold = TrinoRegexp.compile(utf8Slice("(?i)ss")).pattern();
        assertThat(fullFold.forwardProgramForDiagnostics().hasFullCaseFold()).isTrue();
        assertThat(fullFold.reverseProgramSize()).isEqualTo(-1);

        Re2 simpleFold = TrinoRegexp.compile(utf8Slice("(?i)hello")).pattern();
        assertThat(simpleFold.forwardProgramForDiagnostics().hasFullCaseFold()).isFalse();
        assertThat(simpleFold.reverseProgramSize()).isGreaterThan(0);

        Re2 classFold = TrinoRegexp.compile(utf8Slice("(?i)[ﬃ]")).pattern();
        assertThat(classFold.forwardProgramForDiagnostics().hasFullCaseFold()).isFalse();
        assertThat(classFold.reverseProgramSize()).isGreaterThan(0);
        Re2 finalLineClassFold = TrinoRegexp.compile(utf8Slice("(?i)[ﬃ]$")).pattern();
        assertThat(finalLineClassFold.findPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.LOWERED_PROGRAM);
        assertThat(finalLineClassFold.lookingAtPlanForDiagnostics()).isEqualTo(Re2.BooleanPlanKind.LOWERED_PROGRAM);

        Re2 nonAsciiSimpleFold = TrinoRegexp.compile(utf8Slice("(?i)seek")).pattern();
        assertThat(nonAsciiSimpleFold.regexp().requiredPrefix(nonAsciiSimpleFold.caseFolding())).isNull();
        assertThat(nonAsciiSimpleFold.regexp().requiredPrefixForAccel(nonAsciiSimpleFold.caseFolding())).isNull();
    }

    @Test
    public void testFullCaseFoldProgramGrowthIsLinear()
    {
        // Each further 16 repetitions adds no more instructions than the previous 16. Comparing
        // increments removes the program's fixed size offset.
        int shortProgramSize = fullCaseFoldProgramSize(16);
        int mediumProgramSize = fullCaseFoldProgramSize(32);
        int longProgramSize = fullCaseFoldProgramSize(48);

        assertThat(longProgramSize - mediumProgramSize).isLessThanOrEqualTo(mediumProgramSize - shortProgramSize);
    }

    private static int fullCaseFoldProgramSize(int repetitions)
    {
        return TrinoRegexp.compile(utf8Slice("(?i)" + "ss".repeat(repetitions)))
                .pattern()
                .forwardProgramForDiagnostics()
                .size();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matchCases")
    public void testSupportedLanguageMatchesJoni(MatchCase matchCase)
    {
        Slice pattern = utf8Slice(matchCase.pattern());
        Slice input = utf8Slice(matchCase.input());

        TrinoRegexp regexp = TrinoRegexp.compile(pattern);

        assertThat(regexp.contains(input))
                .as("%s against %s", matchCase.pattern(), matchCase.input())
                .isEqualTo(matchCase.expected())
                .isEqualTo(joniFind(pattern, input));
    }

    @Test
    public void testUnbracketedCharacterClassIntersectionsMatchJoni()
    {
        assertFirstMatchBoundaryMatchesJoni("[ab&&bc]+", "abc", new MatchBoundary(1, 2));
        assertFirstMatchBoundaryMatchesJoni("[a-z&&m-p]+", "xmnopq", new MatchBoundary(1, 5));
        assertFirstMatchBoundaryMatchesJoni("[a-z&&m-z&&p-r]+", "opqrs", new MatchBoundary(1, 4));
        assertFirstMatchBoundaryMatchesJoni("[a-z&&[:lower:]]+", "ABCabc", new MatchBoundary(3, 6));
        assertFirstMatchBoundaryMatchesJoni("[a-z&&[m-p]q]+", "lmnopqr", new MatchBoundary(1, 6));
    }

    @Test
    public void testCommentsSeparatedNestedQuantifiersMatchJoni()
    {
        MatchBoundary greedyMatch = new MatchBoundary(0, 3);
        assertFirstMatchBoundaryMatchesJoni("(?x)a* ?", "aaa", greedyMatch);
        assertFirstMatchBoundaryMatchesJoni("(?x)a* # comment\n ?", "aaa", greedyMatch);
        assertFirstMatchBoundaryMatchesJoni("(?x)a+ *", "aaa", greedyMatch);
        assertFirstMatchBoundaryMatchesJoni("(?x)a? +", "aaa", greedyMatch);
        assertFirstMatchBoundaryMatchesJoni("(?x)a* \\Q\\E?", "aaa", greedyMatch);
        assertFirstMatchBoundaryMatchesJoni("(?x)a* # comment\n \\Q\\E?", "aaa", greedyMatch);
        assertFirstMatchBoundaryMatchesJoni("(?x)a*\\Q\\E\\Q\\E?", "aaa", greedyMatch);
        assertNoMatchMatchesJoni("(?x)a+\\Q\\E*", "");
        assertFirstMatchBoundaryMatchesJoni("(?x)a?\\Q\\E*", "aa", new MatchBoundary(0, 1));
        assertThat(TrinoRegexp.compile(utf8Slice("(?x)a+\\Q\\E*")).extract(utf8Slice(""))).isNull();
        assertThat(TrinoRegexp.compile(utf8Slice("(?x)a?\\Q\\E*")).extract(utf8Slice("aa")))
                .isEqualTo(utf8Slice("a"));
        assertFirstMatchBoundaryMatchesJoni("(?x)a+\\Q\\E+", "aaa", greedyMatch);
        assertFirstMatchBoundaryMatchesJoni("(?x)a+\\Q\\E{2}", "aaa", greedyMatch);
        assertFirstMatchBoundaryMatchesJoni("(?x)a+\\Qx\\E*", "aaaxx", new MatchBoundary(0, 5));
        assertFirstMatchBoundaryMatchesJoni("(?x)(?:ab)* ?", "abab", new MatchBoundary(0, 4));
        assertFirstMatchBoundaryMatchesJoni("(?x)(?:ab){1} *", "abab", new MatchBoundary(0, 4));

        assertFirstMatchBoundaryMatchesJoni("(?x)a*?", "aaa", new MatchBoundary(0, 0));
    }

    @Test
    public void testEmptyQuotedAtomQuantifiersMatchJoni()
    {
        for (String prefix : List.of("", "(?x)")) {
            List<String> separators = prefix.isEmpty() ? List.of("") : List.of("", " ", " # comment\n ");
            for (String separator : separators) {
                for (String precedingRepeat : List.of("*", "+", "?", "*?", "+?", "??")) {
                    for (String emptyRepeat : List.of("*", "+", "?", "*?", "+?", "??", "{2}", "{2}?")) {
                        String pattern = prefix + "a" + precedingRepeat + separator + "\\Q\\E" + emptyRepeat;
                        for (String input : List.of("", "a", "aa")) {
                            assertFirstMatchMatchesJoni(pattern, input);
                        }
                    }
                }
            }
        }
    }

    private static Stream<MatchCase> matchCases()
    {
        return Stream.of(
                new MatchCase("[a-z&&[^bc]]+", "ade", true),
                new MatchCase("[a-z&&[^bc]]+", "bc", false),
                new MatchCase("[a-d[m-p]]+", "acmp", true),
                new MatchCase("[a-d[m-p]]+", "z", false),
                new MatchCase("[a-z&&[^m-p]]+", "abcq", true),
                new MatchCase("[a-z&&[^m-p]]+", "mno", false),
                new MatchCase("[[:alpha:]]+", "é", true),
                new MatchCase("[[:word:]]+", "é", true),
                new MatchCase("[[:digit:]]+", "١", true),
                new MatchCase("\\C", "C", true),
                new MatchCase("\\C", "x", false),
                new MatchCase("[\\Q\\E]+", "QEEQ", true),
                new MatchCase("\\R", "R", true),
                new MatchCase("\\R", "\r\n", false),
                new MatchCase("\\h+", "hhh", true),
                new MatchCase("\\h+", " ", false),
                new MatchCase("\\H+", "HH", true),
                new MatchCase("\\v+", "\u000B\u000B", true),
                new MatchCase("\\V+", "VV", true),
                new MatchCase("\\e", "\u001B", true),
                new MatchCase("\\101", "A", true),
                new MatchCase("\\11", "\t", true),
                new MatchCase("\\cA", "\u0001", true),
                new MatchCase("\\x", "x", true),
                new MatchCase("\\u", "u", true),
                // Joni reads an unbraced hex escape without hex digits as U+0000 unless it ends the pattern.
                new MatchCase("[\\x]", "\u0000", true),
                new MatchCase("\\A\\xg\\z", "\u0000g", true),
                new MatchCase("\\k", "k", true),
                new MatchCase("[\\101]", "A", true),
                // Outside a class, a digit escape is a backreference when its number is at most 9 or at most the
                // groups before it. Otherwise, and always in a class, it is an octal escape or the digit 8 or 9.
                new MatchCase("(a)[\\1]", "a\u0001", true),
                new MatchCase("[\\8]", "8", true),
                new MatchCase("\\18", "\u00018", true),
                new MatchCase("\\81", "81", true),
                new MatchCase("(a)\\10", "a\u0008", true),
                new MatchCase("[\\cA]", "\u0001", true),
                // Joni keeps the low five bits after \c, maps \c? to DEL, and decodes an escape first.
                new MatchCase("\\ca", "\u0001", true),
                new MatchCase("\\c?", "\u007F", true),
                new MatchCase("\\c\\c?", "\u001F", true),
                new MatchCase("\\c-", "\r", true),
                new MatchCase("\\c\\n", "\n", true),
                new MatchCase("[\\c!-\\c&]", "\u0003", true),
                new MatchCase("[\\c!-\\c&]", "c", false),
                new MatchCase("\\q+", "qq", true),
                new MatchCase("\\X+", "XX", true),
                new MatchCase("(a)\\g<1>", "ag<1>", true),
                new MatchCase("\\E", "E", true),
                new MatchCase("(?x)a # comment\n b", "ab", true),
                new MatchCase("(?x)[a b]+", "ab", true),
                new MatchCase("(?x)[a b]+", " ", true),
                new MatchCase("(?x)[#]", "#", true),
                new MatchCase("(?:K|k)y", "ky", true),
                new MatchCase("(?:K|k)y", "Ky", true),
                new MatchCase("(?:S|s)y", "Sy", true),
                new MatchCase("[Kk]|x", "K", false),
                new MatchCase("[Ss]|x", "ſ", false),
                new MatchCase("(?i)k|x", "K", true),
                new MatchCase("(?i:(?-i:[Kk])|(?-i:[Xx]){1})", "K", false),
                new MatchCase("\\u0041+", "AAA", true),
                new MatchCase("a{01,003}", "aa", true),
                new MatchCase("(?'name'x)", "x", true),
                new MatchCase("(?m)^b$", "a\nb\nc", true),
                new MatchCase("a$", "a\n", true),
                new MatchCase("\\b中\\b", "中", true),
                new MatchCase("\\b١\\b", "١", true),
                new MatchCase("\\b²\\b", "²", true),
                new MatchCase("\\b①\\b", "①", false),
                new MatchCase("\\b\u0301\\b", "\u0301", true),
                new MatchCase("\\b\u200C\\b", "\u200C", false),
                new MatchCase("\\B\u200C\\B", "\u200C", true),
                new MatchCase("(?i)\\Aß\\z", "SS", true),
                new MatchCase("(?i)\\Ass\\z", "ß", true),
                new MatchCase("(?i)\\Aﬀ\\z", "FF", true),
                new MatchCase("(?i)\\AStraße\\z", "STRASSE", true),
                new MatchCase("(?i)\\Aß+\\z", "SSSS", true),
                new MatchCase("(?i)\\A[ﬃ]\\z", "fﬁ", true),
                new MatchCase("(?i)\\A[s][s]\\z", "ß", false),
                // Joni folds dotted capital I only to "i" with a combining dot above and gives dotless
                // small i no fold, as Unicode full case folding does outside its Turkic mappings.
                new MatchCase("(?i)\\Ai\\z", "\u0130", false),
                new MatchCase("(?i)\\AI\\z", "\u0130", false),
                new MatchCase("(?i)\\Ai\\z", "\u0131", false),
                new MatchCase("(?i)\\AI\\z", "\u0131", false),
                new MatchCase("(?i)\\A\u0131\\z", "i", false),
                new MatchCase("(?i)\\A\u0131\\z", "I", false),
                new MatchCase("(?i)\\A\u0131\\z", "\u0131", true),
                new MatchCase("(?i)\\A\u0130\\z", "i\u0307", true),
                new MatchCase("(?i)\\Ai\u0307\\z", "\u0130", true),
                // Joni folds a bracket class once, after its set operations, and does not fold
                // class and property escapes outside brackets. A negated class is folded before
                // its complement.
                new MatchCase("(?i)\\A\\w\\z", "\u017f", false),
                new MatchCase("(?i)\\A\\w\\z", "\u212a", false),
                new MatchCase("(?i)\\A[\\w]\\z", "\u017f", true),
                new MatchCase("(?i)\\A\\W\\z", "\u212a", true),
                new MatchCase("(?i)\\A[\\W]\\z", "k", true),
                new MatchCase("(?i)\\A\\p{Lu}\\z", "a", false),
                new MatchCase("(?i)\\A\\P{Lu}\\z", "a", true),
                new MatchCase("(?i)\\A[\\p{Lu}]\\z", "a", true),
                new MatchCase("(?i)\\A[\\P{Lu}]\\z", "a", true),
                new MatchCase("(?i)\\A[\\P{Lu}]\\z", "A", true),
                new MatchCase("(?i)\\A[\\P{Lu}]\\z", "\u00df", true),
                new MatchCase("(?i)\\A[[:^upper:]]\\z", "a", true),
                new MatchCase("(?i)\\A[[:^upper:]]\\z", "A", true),
                new MatchCase("(?i)\\A[[:^upper:]]\\z", "\u00df", true),
                new MatchCase("(?i)\\A[a-z&&[B]]\\z", "b", false),
                new MatchCase("(?i)\\A[a-z&&[B]]\\z", "B", false),
                new MatchCase("(?i)\\A[a-z&&[^B]]\\z", "b", true),
                new MatchCase("(?i)\\A[a-z&&[^B]]\\z", "B", true),
                new MatchCase("(?i)\\A[^\\P{Lu}]\\z", "a", false),
                new MatchCase("(?i)\\A[^\\P{Lu}]\\z", "A", false),
                new MatchCase("(?i)\\A[^[^a]]\\z", "a", false),
                new MatchCase("(?i)\\A[[^a]]\\z", "A", true),
                new MatchCase("(?i)\\A[^[^\ufb03]]\\z", "\ufb03", true),
                new MatchCase("(?i)\\A[^[^\ufb03]]\\z", "ffi", false),
                new MatchCase("\\p{Alphabetic}+", "abc", true),
                new MatchCase("\\p{lu}+", "ABC", true),
                new MatchCase("\\p{inBasicLatin}+", "abc", true),
                new MatchCase("\\p{JAVALOWERCASE}+", "abc", true),
                new MatchCase("\\p{OldItalic}", "\uD800\uDF00", true),
                new MatchCase("\\p{InBasicLatin}+", "abc", true),
                // Joni defines each POSIX name by one Unicode table, and \p{...} takes the name in any ASCII casing.
                new MatchCase("\\A\\p{Alpha}\\z", "\u0345", true),
                new MatchCase("\\A\\p{Alnum}\\z", "\u00AA", true),
                new MatchCase("\\A\\p{Lower}\\z", "\u00AA", true),
                new MatchCase("\\A\\p{Upper}\\z", "\u24B6", true),
                new MatchCase("\\A\\p{Blank}\\z", "\u00A0", true),
                new MatchCase("\\A\\p{Print}\\z", "\u00A0", true),
                new MatchCase("\\A\\p{Cntrl}\\z", "\u0080", true),
                new MatchCase("\\A\\p{Digit}\\z", "\u0660", true),
                new MatchCase("\\A\\p{Graph}\\z", "\u00A1", true),
                new MatchCase("\\A\\p{Graph}\\z", "\u0378", false),
                new MatchCase("\\A\\p{Punct}\\z", "\u00A1", true),
                new MatchCase("[[:punct:]]", "\u00A1", true),
                new MatchCase("\\A\\p{Space}\\z", "\u0085", true),
                new MatchCase("\\p{Punct}", "$", false),
                new MatchCase("\\A[\\p{Alpha}]\\z", "\u0345", true),
                new MatchCase("\\A\\P{Alpha}\\z", "\u0345", false),
                new MatchCase("\\A\\p{xdigit}+\\z", "0Af", true),
                new MatchCase("\\A\\p{xdigit}\\z", "\uFF21", false),
                new MatchCase("[[:xdigit:]]", "\u0660", false),
                new MatchCase("\\A[[:xdigit:]]\\z", "\uFF21", false),
                new MatchCase("\\p{HexDigit}", "\u0660", false),
                new MatchCase("\\A\\p{HexDigit}\\z", "\uFF21", true),
                new MatchCase("[[:word:]]", "\u200C", false),
                new MatchCase("\\p{WORD}", "\u200C", false),
                new MatchCase("\\p{Word}", "\u0300", true),
                new MatchCase("\\p{Word}", "\u203F", true),
                new MatchCase("\\A\\p{ascii}\\z", "a", true),
                // Joni reads a brace-less \p or \P inside a class as the literal letter; a braced one stays a property.
                new MatchCase("[\\pL]", "p", true),
                new MatchCase("[\\pL]", "a", false),
                new MatchCase("[^\\pL]", "a", true),
                new MatchCase("[\\PL]", "P", true),
                new MatchCase("[\\p^L]", "^", true),
                new MatchCase("[\\p^L]", "1", false),
                new MatchCase("[\\p-z]", "q", true),
                new MatchCase("[a-\\p]", "q", false),
                new MatchCase("(?i)[\\p]", "P", true),
                new MatchCase("[\\p{L}]", "a", true),
                // Without a later :], Joni reads [: as a nested class.
                new MatchCase("[[:a:b]]", "a", true),
                // A dash after a completed range, even with a nested class between them, is not a range
                // operator, but a second dash after it is.
                new MatchCase("[a-p-z]", "z", true),
                new MatchCase("[a-c-x-z]", "y", true),
                new MatchCase("[a-c[x]-z]", "z", true),
                new MatchCase("[a-c[x]--z]", "y", true),
                new MatchCase("[a-c-]", "-", true),
                // Pinned Joni reads the bracket here as a literal rather than a nested class, but both match x.
                new MatchCase("[a-c-[x]]", "x", true),
                // A dash that ends a class after a class item, or follows a leading nested class, is a literal.
                new MatchCase("[\\d-]", "-", true),
                new MatchCase("[[a]-z]", "-", true),
                new MatchCase("[[a]-z]", "b", false),
                // A dash before an intersection operator after a single character or a class item is a literal.
                new MatchCase("[!-&&!]", "#", false),
                new MatchCase("[a-&&-]", "-", true),
                new MatchCase("[\\d-&&-]", "-", true));
    }

    private static boolean joniFind(Slice pattern, Slice input)
    {
        Regex regex = joniPattern(pattern);
        byte[] inputBytes = input.getBytes();
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.DEFAULT) >= 0;
    }

    private static void assertFirstMatchBoundaryMatchesJoni(String patternText, String inputText, MatchBoundary expected)
    {
        Slice pattern = utf8Slice(patternText);
        Slice input = utf8Slice(inputText);

        MatchResult result = TrinoRegexp.compile(pattern).pattern().findResult(input);
        assertThat(result).isNotNull();
        assertThat(new MatchBoundary(result.start(), result.end())).as(patternText).isEqualTo(expected);

        byte[] inputBytes = input.getBytes();
        Matcher matcher = joniPattern(pattern).matcher(inputBytes);
        assertThat(matcher.search(0, inputBytes.length, Option.DEFAULT)).as(patternText).isGreaterThanOrEqualTo(0);
        assertThat(new MatchBoundary(matcher.getBegin(), matcher.getEnd())).as(patternText).isEqualTo(expected);
    }

    private static void assertNoMatchMatchesJoni(String patternText, String inputText)
    {
        Slice pattern = utf8Slice(patternText);
        Slice input = utf8Slice(inputText);

        assertThat(TrinoRegexp.compile(pattern).pattern().findResult(input)).as(patternText).isNull();

        byte[] inputBytes = input.getBytes();
        Matcher matcher = joniPattern(pattern).matcher(inputBytes);
        assertThat(matcher.search(0, inputBytes.length, Option.DEFAULT)).as(patternText).isLessThan(0);
    }

    private static void assertFirstMatchMatchesJoni(String patternText, String inputText)
    {
        Slice pattern = utf8Slice(patternText);
        Slice input = utf8Slice(inputText);

        MatchResult result = TrinoRegexp.compile(pattern).pattern().findResult(input);
        byte[] inputBytes = input.getBytes();
        Matcher matcher = joniPattern(pattern).matcher(inputBytes);
        int joniResult = matcher.search(0, inputBytes.length, Option.DEFAULT);
        if (joniResult < 0) {
            assertThat(result).as(patternText + " input=" + inputText).isNull();
            return;
        }

        assertThat(result).as(patternText + " input=" + inputText).isNotNull();
        assertThat(new MatchBoundary(result.start(), result.end()))
                .as(patternText + " input=" + inputText)
                .isEqualTo(new MatchBoundary(matcher.getBegin(), matcher.getEnd()));
    }

    private static Slice rawBytes(int... values)
    {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return wrappedBuffer(bytes);
    }

    // Adds every string of whole characters whose folds concatenate to fold[index..].
    private static void addFoldSpellings(Set<String> spellings, Map<List<Integer>, List<Integer>> charactersByFold, int[] fold, int index, StringBuilder prefix)
    {
        if (index == fold.length) {
            spellings.add(prefix.toString());
            return;
        }
        for (int end = index + 1; end <= fold.length; end++) {
            for (int character : charactersByFold.getOrDefault(Arrays.stream(fold, index, end).boxed().toList(), List.of())) {
                int length = prefix.length();
                prefix.appendCodePoint(character);
                addFoldSpellings(spellings, charactersByFold, fold, end, prefix);
                prefix.setLength(length);
            }
        }
    }

    private static void addCodePoints(Set<Integer> codePoints, CharClass characterClass)
    {
        for (RuneRange range : characterClass.ranges()) {
            for (int codePoint = range.low(); codePoint <= range.high(); codePoint++) {
                codePoints.add(codePoint);
            }
        }
    }

    private static int[] joniFold(int codePoint)
    {
        byte[] encoded = Character.toString(codePoint).getBytes(UTF_8);
        byte[] folded = new byte[Config.ENC_MBC_CASE_FOLD_MAXLEN];
        int length = NonStrictUTF8Encoding.INSTANCE.mbcCaseFold(Config.ENC_CASE_FOLD_DEFAULT, encoded, new IntHolder(), encoded.length, folded);
        return new String(folded, 0, length, UTF_8).codePoints().toArray();
    }

    private static Regex joniPattern(Slice pattern)
    {
        byte[] patternBytes = pattern.getBytes();
        return new Regex(
                patternBytes,
                0,
                patternBytes.length,
                Option.DEFAULT,
                NonStrictUTF8Encoding.INSTANCE,
                Syntax.Java,
                _ -> {});
    }

    private static List<MatchBoundary> matchBoundaries(Re2Matcher matcher)
    {
        List<MatchBoundary> boundaries = new ArrayList<>();
        while (matcher.find()) {
            boundaries.add(new MatchBoundary(matcher.start(), matcher.end()));
        }
        return boundaries;
    }

    private static List<Integer> emptyWidthAssertions(Prog program)
    {
        return program.insts().stream()
                .filter(instruction -> instruction.opcode() == InstOp.EMPTY_WIDTH)
                .map(Prog.Inst::empty)
                .toList();
    }

    private static boolean assertionMatches(Slice context, int position, int assertion)
    {
        int absolutePosition = context.byteArrayOffset() + position;
        return (EmptyOp.contextFlags(context, absolutePosition, assertion) & assertion) != 0;
    }

    private static Prog compileTrinoProgram(String pattern, boolean reversed)
    {
        ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL);
        Regexp regexp = Simplifier.simplify(parsed.regexp(), parsed.caseFolding());
        return Compiler.compileNormalized(regexp, reversed, 1 << 20, Compiler.Dialect.TRINO, parsed.caseFolding());
    }

    private static void assertMultilineMatches(String patternText, String inputText, List<MatchBoundary> expected)
    {
        Slice pattern = utf8Slice(patternText);
        Slice inputBytes = utf8Slice(inputText);
        Slice input = utf8Slice("!" + inputText + "?").slice(1, inputBytes.length());

        assertThat(matchBoundaries(TrinoRegexp.compile(pattern).pattern().matcher(input)))
                .as("Regulator %s against %s", patternText, inputText)
                .isEqualTo(expected);
        assertThat(joniMatchBoundaries(joniPattern(pattern), input))
                .as("Joni %s against %s", patternText, inputText)
                .isEqualTo(expected);
    }

    private static void assertMultilineMatchesJoni(String patternText, String inputText)
    {
        Slice pattern = utf8Slice(patternText);
        Slice inputBytes = utf8Slice(inputText);
        Slice input = utf8Slice("!" + inputText + "?").slice(1, inputBytes.length());

        assertThat(matchBoundaries(TrinoRegexp.compile(pattern).pattern().matcher(input)))
                .as("%s against %s", patternText, inputText)
                .isEqualTo(joniMatchBoundaries(joniPattern(pattern), input));
    }

    private static List<MatchBoundary> joniMatchBoundaries(Regex pattern, Slice input)
    {
        byte[] inputBytes = input.getBytes();
        Matcher matcher = pattern.matcher(inputBytes);
        List<MatchBoundary> boundaries = new ArrayList<>();
        int start = 0;
        while (start <= inputBytes.length && matcher.search(start, inputBytes.length, Option.DEFAULT) >= 0) {
            int matchStart = matcher.getBegin();
            int matchEnd = matcher.getEnd();
            boundaries.add(new MatchBoundary(matchStart, matchEnd));
            if (matchStart != matchEnd) {
                start = matchEnd;
            }
            else if (matchEnd == inputBytes.length) {
                break;
            }
            else {
                start = matchEnd + 1;
            }
        }
        return boundaries;
    }

    private static void addInputs(List<String> inputs, StringBuilder builder, int maximumLength)
    {
        inputs.add(builder.toString());
        if (builder.length() == maximumLength) {
            return;
        }
        for (char character : new char[] {'a', 'b', '\n'}) {
            builder.append(character);
            addInputs(inputs, builder, maximumLength);
            builder.setLength(builder.length() - 1);
        }
    }

    private record MatchCase(String pattern, String input, boolean expected) {}

    private record MatchBoundary(int start, int end) {}
}
