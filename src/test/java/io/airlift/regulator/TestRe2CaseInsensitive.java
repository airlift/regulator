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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Adapted from upstream RE2: re2/testing/re2_test.cc.
public class TestRe2CaseInsensitive
{
    @Test
    public void testQuotedCaseInsensitiveLiterals()
    {
        // Expected matches verified with the pinned native RE2 golden runner.
        for (String literal : List.of("K", "k", "é", "É")) {
            String folded = switch (literal) {
                case "K" -> "k";
                case "k" -> "K";
                case "é" -> "É";
                default -> "é";
            };
            for (String suffix : List.of("", "+", "{2}")) {
                Slice input = utf8(suffix.equals("{2}") ? folded.repeat(2) : folded);
                Re2 quoted = Re2.compile(utf8("(?i)\\Q" + literal + "\\E" + suffix));
                assertThat(quoted.matches(input)).as(literal + suffix).isTrue();
                assertThat(quoted.findResult(input).groupSlice(0)).isEqualTo(input);
                assertThat(Re2.compile(utf8("\\Q" + literal + "\\E" + suffix),
                        Re2.Options.defaults().setCaseSensitive(false)).matches(input)).isTrue();
            }
        }
        for (String literal : List.of("K", "k")) {
            Re2 latin1 = Re2.compile(
                    utf8("(?i)\\Q" + literal + "\\E+"),
                    Re2.Options.defaults().setEncoding(Re2.Options.Encoding.LATIN1));
            assertThat(latin1.matches(utf8("kK"))).isTrue();
        }
        assertThat(Re2.compile(utf8("(?i)\\Qé\\E")).matches(utf8("e"))).isFalse();
        assertThat(Re2.compile(utf8("(?i)\\QK\\E(?-i:k)")).matches(utf8("kk"))).isTrue();
        assertThat(Re2.compile(utf8("(?i)\\QK\\E(?-i:k)")).matches(utf8("kK"))).isFalse();
    }

    @Test
    public void testMatchAndMatcher()
    {
        Slice text = utf8("A fish named *Wanda*");
        Re2 re = Re2.compile(utf8("(?i)([wand]{5})"), Re2.Options.defaults());

        MatchResult result = re.findResult(text);
        assertThat(result).isNotNull();
        assertThat(toString(result.groupSlice(1))).isEqualTo("Wanda");

        Re2Matcher matcher = re.matcher(Slices.wrappedBuffer(text.byteArray(), text.byteArrayOffset(), text.length()));
        assertThat(matcher.find()).isTrue();
        assertThat(toString(matcher.group(1))).isEqualTo("Wanda");
    }

    private static Slice utf8(String value)
    {
        return Slices.wrappedBuffer(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String toString(Slice value)
    {
        return value.toStringUtf8();
    }
}
