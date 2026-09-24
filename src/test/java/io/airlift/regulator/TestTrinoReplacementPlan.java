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

import java.util.List;
import java.util.Map;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTrinoReplacementPlan
{
    @Test
    public void testManyGroupReplacementResolvesReferencesInConstantTime()
    {
        // A template referencing G distinct groups would compare about G * G / 2 entries if every
        // reference scanned the groups seen before it.
        int groupCount = 2048;
        StringBuilder template = new StringBuilder();
        for (int group = 1; group <= groupCount; group++) {
            template.append('$').append(group).append(',');
        }
        for (int group = groupCount; group >= 1; group--) {
            template.append('$').append(group);
        }
        int referenceCount = groupCount * 2;
        assertThat(TrinoReplacementPlan.groupComparisonsForDiagnostics(utf8Slice(template.toString()), groupCount))
                .isLessThanOrEqualTo(referenceCount * 8);
        // Short templates keep the linear scan.
        assertThat(TrinoReplacementPlan.groupComparisonsForDiagnostics(utf8Slice("$1$2$1$3$2"), 3)).isEqualTo(6);

        // References after the template switches to the group index, including repeated, named,
        // and two-digit references, resolve to the same groups.
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("(.)".repeat(19) + "(?<last>.)"));
        Slice source = utf8Slice("abcdefghijklmnopqrst");
        StringBuilder reversed = new StringBuilder();
        for (int group = 20; group >= 1; group--) {
            reversed.append('$').append(group);
        }
        assertThat(regexp.replace(source, utf8Slice(reversed + "-$1$10${last}$9")))
                .isEqualTo(utf8Slice("tsrqponmlkjihgfedcba-ajti"));
    }

    @Test
    public void testDenseReplacementMatchesRecordedReplacement()
    {
        int matchCount = TrinoReplacementPlan.MATCH_RECORD_LIMIT + 1;
        assertExactReplacementStorage("a", "a".repeat(matchCount), "b", "b".repeat(matchCount));
        assertExactReplacementStorage("a", "a".repeat(matchCount), "", "");
        assertExactReplacementStorage("", "ab".repeat(matchCount), "-", "-" + "a-b-".repeat(matchCount));
        assertExactReplacementStorage("x*", "ab".repeat(matchCount), "-", "-" + "a-b-".repeat(matchCount));
        assertExactReplacementStorage("(a)", "a".repeat(matchCount), "$1$1", "aa".repeat(matchCount));
        assertExactReplacementStorage("(a)|(b)", "ab".repeat(matchCount), "[$1|$2]", "[a|][|b]".repeat(matchCount));
        assertExactReplacementStorage("[a-c]", "a💰".repeat(matchCount), "<$0>", "<a>💰".repeat(matchCount));
        assertExactReplacementStorage("cat|dog", "catdog".repeat(matchCount), "_", "__".repeat(matchCount));
        assertExactReplacementStorage("[0-9]{2}:", "12:".repeat(matchCount), "é", "é".repeat(matchCount));
        assertExactReplacementStorage("\\d", "1a".repeat(matchCount), "<$0>", "<1>a".repeat(matchCount));
        assertExactReplacementStorage(".*x.*", "x\n".repeat(matchCount), "[$0]", "[x]\n".repeat(matchCount));
    }

    @Test
    public void testChunkedReplacementMatchesRecordedReplacement()
    {
        List<ReplacementCase> cases = List.of(
                new ReplacementCase("", "a💰é", "<$0>"),
                new ReplacementCase("a", "aabaa", ""),
                new ReplacementCase("a", "abab", "XYZ"),
                new ReplacementCase("(a)|(b)", "abcab", "[$1|$2]"),
                new ReplacementCase("(a)(b)?", "aabab", "$2$1$0"),
                new ReplacementCase("(?<n>é)", "éxé", "${n}${n}"),
                new ReplacementCase("x*", "axxb", "-"));
        for (ReplacementCase replacementCase : cases) {
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(replacementCase.expression()));
            Slice backing = utf8Slice("!" + replacementCase.source().repeat(40) + "?");
            Slice source = backing.slice(1, backing.length() - 2);
            Slice replacement = utf8Slice(replacementCase.replacement());
            Slice expected = replaceWithRecordLimit(regexp, source, replacement, Integer.MAX_VALUE, true);
            for (int matchRecordLimit : new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 16, 64}) {
                assertThat(replaceWithRecordLimit(regexp, source, replacement, matchRecordLimit, true))
                        .as("%s with %s at record limit %s", replacementCase.expression(), replacementCase.replacement(), matchRecordLimit)
                        .isEqualTo(expected);
                if (regexp.newReplacementPlan(source, replacement).maximumCapturingGroup() == 0) {
                    assertThat(replaceWithRecordLimit(regexp, source, replacement, matchRecordLimit, false))
                            .as("%s with %s at record limit %s by bounds", replacementCase.expression(), replacementCase.replacement(), matchRecordLimit)
                            .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    public void testGroupFreeReplacerMatchesPlanWrites()
    {
        List<ReplacementCase> cases = List.of(
                new ReplacementCase("", "a💰é", "<$0>"),
                new ReplacementCase("a", "aabaa", ""),
                new ReplacementCase("a", "abab", "XYZ"),
                new ReplacementCase("[a-c]", "a💰b", "\\$$0$0"),
                new ReplacementCase("cat|dog", "catdogcow", "_"),
                new ReplacementCase("[0-9]{2}:", "12:x34:", "_"),
                new ReplacementCase("b+", "abbbcb", "-".repeat(100)),
                new ReplacementCase("x*", "axxb", "-"));
        for (ReplacementCase replacementCase : cases) {
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(replacementCase.expression()));
            Slice replacement = utf8Slice(replacementCase.replacement());
            for (int repetitions : new int[] {1, 40}) {
                Slice backing = utf8Slice("!" + replacementCase.source().repeat(repetitions) + "?");
                Slice source = backing.slice(1, backing.length() - 2);
                TrinoReplacementPlan plan = new TrinoReplacementPlan(source, replacement, 0, Map.of(), Integer.MAX_VALUE);
                TrinoReplacementPlan.GroupFreeReplacer replacer = regexp.newReplacementPlan(source, replacement).groupFreeReplacer();
                TrinoRegexpMatcher matcher = regexp.matcher(source, 0);
                while (matcher.find()) {
                    plan.addMatch(matcher.start(), matcher.end());
                    replacer.addMatch(matcher.start(), matcher.end());
                }
                Slice result = replacer.build();
                assertThat(result)
                        .as("%s with %s repeated %s times", replacementCase.expression(), replacementCase.replacement(), repetitions)
                        .isEqualTo(plan.build());
                assertThat(result.byteArray()).hasSize(result.length());
            }
        }
    }

    @Test
    public void testGroupFreeReplacerParsesOnFirstMatch()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("(a)"));
        Slice source = utf8Slice("-a-");
        Slice malformed = utf8Slice("[\\");
        assertThat(regexp.newReplacementPlan(source, malformed).groupFreeReplacer().build()).isSameAs(source);
        TrinoReplacementPlan.GroupFreeReplacer failing = regexp.newReplacementPlan(source, malformed).groupFreeReplacer();
        assertThatThrownBy(() -> failing.addMatch(1, 2)).isInstanceOf(TrinoRegexpReplacementException.class);
        assertThat(regexp.cachesReplacementForDiagnostics(malformed)).isFalse();

        Slice replacement = utf8Slice("<$0>");
        TrinoReplacementPlan.GroupFreeReplacer replacer = regexp.newReplacementPlan(source, replacement).groupFreeReplacer();
        assertThat(regexp.cachesReplacementForDiagnostics(replacement)).isFalse();
        replacer.addMatch(1, 2);
        assertThat(regexp.cachesReplacementForDiagnostics(replacement)).isTrue();
        assertThatThrownBy(() -> replacer.addMatch(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(replacer.build()).isEqualTo(utf8Slice("-<a>-"));

        assertThatThrownBy(() -> regexp.newReplacementPlan(source, utf8Slice("$1")).groupFreeReplacer())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testMatchRecordCapacityGrowthNearIntegerLimit()
    {
        assertThat(TrinoReplacementPlan.matchRecordCapacity(2, 4, 4096)).isEqualTo(8);
        assertThat(TrinoReplacementPlan.matchRecordCapacity(8, 10, 4096)).isEqualTo(16);
        assertThat(TrinoReplacementPlan.matchRecordCapacity(3000, 3002, 4096)).isEqualTo(4096);
        assertThat(TrinoReplacementPlan.matchRecordCapacity(1 << 29, (1 << 29) + 2, Integer.MAX_VALUE)).isEqualTo(1 << 30);
        assertThat(TrinoReplacementPlan.matchRecordCapacity(1 << 30, (1 << 30) + 2, Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(TrinoReplacementPlan.matchRecordCapacity(1 << 30, (1 << 30) + 2, Integer.MAX_VALUE - 8)).isEqualTo(Integer.MAX_VALUE - 8);
        assertThat(TrinoReplacementPlan.matchRecordCapacity(Integer.MAX_VALUE - 4, Integer.MAX_VALUE - 2, Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(TrinoReplacementPlan.matchRecordCapacity(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> TrinoReplacementPlan.matchRecordCapacity(4096, 4098, 4096))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertExactReplacementStorage(String expression, String input, String replacement, String expected)
    {
        Slice backing = utf8Slice("!" + input + "?");
        Slice source = backing.slice(1, backing.length() - 2);
        Slice result = TrinoRegexp.compile(utf8Slice(expression)).replace(source, utf8Slice(replacement));
        assertThat(result).isEqualTo(utf8Slice(expected));
        assertThat(result.byteArrayOffset()).isZero();
        assertThat(result.byteArray()).hasSize(result.length());
    }

    private static Slice replaceWithRecordLimit(TrinoRegexp regexp, Slice source, Slice replacement, int matchRecordLimit, boolean useMatcher)
    {
        TrinoReplacementPlan plan = new TrinoReplacementPlan(
                source,
                replacement,
                regexp.capturingGroupCount(),
                regexp.namedCapturingGroups(),
                matchRecordLimit);
        if (useMatcher) {
            return regexp.replaceWithMatcher(source, plan);
        }
        Re2Matcher matcher = regexp.pattern().matcher(source, 0);
        while (matcher.find()) {
            plan.addMatch(matcher.start(), matcher.end());
        }
        return plan.build();
    }

    private record ReplacementCase(String expression, String source, String replacement) {}
}
