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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestWildcardAlternation
{
    @Test
    public void testAlternativesKeepMatchesAndCaptures()
    {
        // Pinned RE2 parse.cc only subsumes scalar literals/classes, never arbitrary alternatives.
        for (String alternative : List.of("ab", "", "(a)", "^", "a+", "a?", "é", "[a-z]")) {
            for (String body : List.of(alternative + "|.", ".|" + alternative)) {
                String expression = "(?s:" + body + ")";
                for (String input : List.of("", "a", "ab", "aaa", "é", "\n", "x")) {
                    var expected = Pattern.compile(expression).matcher(input);
                    Re2 re2 = Re2.compile(utf8Slice(expression));
                    for (Re2Matcher actual : List.of(re2.matcher(utf8Slice(input)), JavaRegexp.compile(utf8Slice(expression)).matcher(utf8Slice(input)), TrinoRegexp.compile(utf8Slice(expression)).pattern().matcher(utf8Slice(input)))) {
                        assertThat(actual.matches()).as(expression + " on " + input).isEqualTo(expected.matches());
                        expected.reset();
                        actual.reset(utf8Slice(input));
                        boolean found = expected.find();
                        assertThat(actual.find()).as(expression + " on " + input).isEqualTo(found);
                        if (found) {
                            for (int group = 0; group <= expected.groupCount(); group++) {
                                assertThat(actual.group(group)).isEqualTo(expected.group(group) == null ? null : utf8Slice(expected.group(group)));
                            }
                        }
                    }
                }
            }
        }
        assertThat(Re2.compile(utf8Slice("(?s:.|ab)"), Re2.Options.defaults().setLongestMatch(true)).findResult(utf8Slice("ab")).groupSlice(0)).isEqualTo(utf8Slice("ab"));
        assertThat(Re2.compile(utf8Slice("ab|.")).matches(utf8Slice("\n"))).isFalse();
        Re2Matcher nested = Re2.compile(utf8Slice("(".repeat(270) + "(?s:ab|.)" + ")".repeat(270))).matcher(utf8Slice("ab"));
        assertThat(nested.matches()).isTrue();
        assertThat(nested.group(270)).isEqualTo(utf8Slice("ab"));
    }

    @Test
    public void testByteAndCharacterAlternativesRemainDistinct()
    {
        for (String expression : List.of("(?s:.|\\C)", "(?s:\\C|.)", "ab|\\C", "\\C|ab")) {
            String input = expression.contains("ab") ? "ab" : "é";
            assertThat(Re2.compile(utf8Slice(expression)).matches(utf8Slice(input))).isTrue();
        }
        for (String expression : List.of("(?is:ß|.)", "(?is:[ß]|.)")) {
            assertThat(TrinoRegexp.compile(utf8Slice(expression)).pattern().matches(utf8Slice("ss"))).isTrue();
        }
    }

    @Test
    public void testScalarWildcardProgramsStaySmall()
    {
        for (Re2.Options.Encoding encoding : Re2.Options.Encoding.values()) {
            var options = Re2.Options.defaults().setEncoding(encoding);
            Re2 wildcard = Re2.compile(utf8Slice("(?s:.)"), options);
            for (String expression : List.of("(?s:a|.)", "(?s:.|a)", "(?s:[a-z]|.)")) {
                Re2 actual = Re2.compile(utf8Slice(expression), options);
                assertThat(actual.forwardProgramForDiagnostics().dump()).isEqualTo(wildcard.forwardProgramForDiagnostics().dump());
                assertThat(actual.regexp().op()).isEqualTo(RegexpOp.ANY_CHAR);
            }
        }
    }
}
