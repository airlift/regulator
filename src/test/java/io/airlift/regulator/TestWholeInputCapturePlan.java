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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestWholeInputCapturePlan
{
    @Test
    public void testEligibility()
    {
        for (String expression : List.of("(?s)^((.*)()()($))", "(?s)\\A(()(.*)())\\z", "(?s)(^(()).*(())$)")) {
            assertThat(analyze(expression)).as(expression).isNotNull();
        }
        for (String expression : List.of("^((.*)()()($))", "(?s)^((.*?)()()($))", "(?sm)^((.*)()()($))", "(?s)(.*)$", "(?s)^(.*)", "(?s)^(.*)(.*)$", "(?s)^((.)*)$", "(?s)^(.*)x$", "(?s)^((.*)?)$", "(?s)^.*$")) {
            assertThat(analyze(expression)).as(expression).isNull();
        }
    }

    @Test
    public void testRegionsRetainedGroupsAndReset()
    {
        for (String expression : List.of("(?s)^((.*)()()($))", "(?s)\\A(()(.*)())\\z", "(?s)(^(()).*(())$)", "(?sd)^((.*)()()($))")) {
            JavaRegexp pattern = JavaRegexp.compile(utf8Slice(expression));
            Re2Matcher actual = pattern.matcher(utf8Slice(""));
            for (String text : List.of("", "abc", "abc\r\n", "δ💰\n", "a\u0085b\u2028\u2029")) {
                Matcher expected = Pattern.compile(expression).matcher(text);
                assertThat(expected.matches()).isTrue();
                Slice padded = utf8Slice("!@" + text + "#$");
                Slice logical = padded.slice(1, padded.length() - 2);
                actual.reset(logical, 1, logical.length() - 1);
                assertThat(actual.find()).isTrue();
                for (int group = 0; group <= expected.groupCount(); group++) {
                    assertThat(actual.start(group)).as("%s group %s", expression, group)
                            .isEqualTo(1 + utf8Slice(text.substring(0, expected.start(group))).length());
                    assertThat(actual.end(group)).isEqualTo(1 + utf8Slice(text.substring(0, expected.end(group))).length());
                }
                assertThat(actual.find()).isFalse();
                assertThat(actual.matches()).isTrue();
                assertThat(actual.lookingAt()).isTrue();
                for (int slots = 1; slots <= expected.groupCount() + 2; slots++) {
                    int[] offsets = new int[2 * slots];
                    assertThat(pattern.findInto(utf8Slice(text), offsets)).isTrue();
                    for (int group = 0; group < slots; group++) {
                        assertThat(offsets[2 * group]).isEqualTo(group > expected.groupCount() ? -1 : utf8Slice(text.substring(0, expected.start(group))).length());
                        assertThat(offsets[2 * group + 1]).isEqualTo(group > expected.groupCount() ? -1 : utf8Slice(text.substring(0, expected.end(group))).length());
                    }
                }
            }
        }
    }

    @Test
    public void testRetainedPlanUsesForwardBudget()
    {
        Slice pattern = utf8Slice("(?s)^((.*)()()($))");
        int flags = JavaRegexp.Options.defaults().parseFlags();
        ParseResult parsed = JavaRegexpParser.parse(pattern, flags);
        long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
        Prog control = Compiler.compile(parsed.regexp(), false, maxMemory - maxMemory / 3);
        WholeInputCapturePlan plan = WholeInputCapturePlan.analyze(parsed.regexp(), parsed.capturingGroupCount());
        Re2 compiled = Re2.compileParsed(pattern, parsed, flags, maxMemory);
        assertThat(compiled.forwardProgramForDiagnostics().dfaMemory()).isEqualTo(control.dfaMemory() - plan.estimatedRetainedSize());
    }

    private static WholeInputCapturePlan analyze(String expression)
    {
        ParseResult parsed = JavaRegexpParser.parse(utf8Slice(expression), JavaRegexp.Options.defaults().parseFlags());
        return WholeInputCapturePlan.analyze(parsed.regexp(), parsed.capturingGroupCount());
    }
}
