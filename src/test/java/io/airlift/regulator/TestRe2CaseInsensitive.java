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

        RegexpMatcher matcher = re.matcher(Slices.wrappedBuffer(text.byteArray(), text.byteArrayOffset(), text.length()));
        assertThat(matcher.find()).isTrue();
        assertThat(toString(matcher.group(1))).isEqualTo("Wanda");
    }

    @Test
    public void testLatin1FoldsOnlyAsciiLetters()
    {
        // Upstream RE2 folds only ASCII letters in Latin-1 mode, so (?i)Ñ does not match ñ.
        Re2 re2 = Re2.compile(latin1("(?i)Ñ"), Re2.Options.latin1());
        assertThat(re2.find(latin1("Ñ"))).isTrue();
        assertThat(re2.count(latin1("Ñ"))).isEqualTo(1);
        assertThat(re2.find(latin1("ñ"))).isFalse();
        assertThat(re2.count(latin1("ñ"))).isZero();
    }

    @Test
    public void testAsciiLetterPairsStayPairsInAlternations()
    {
        // Upstream RE2's alternation factoring folds a merged [Kk] by its whole orbit, so there
        // [Kk]|x matches the Kelvin sign. Regulator merges an explicit pair as written.
        assertThat(Re2.compile(utf8("[Kk]|x")).matches(utf8("\u212A"))).isFalse();
        assertThat(Re2.compile(utf8("[Ss]|x")).matches(utf8("\u017F"))).isFalse();
        // An impossible alternative leaves the explicit pair unchanged.
        assertThat(Re2.compile(utf8("[Kk]|\\P{Any}")).matches(utf8("\u212A"))).isFalse();
        assertThat(Re2.compile(utf8("(?i)k|x")).matches(utf8("\u212A"))).isTrue();
        assertThat(Re2.compile(utf8("(?i:k)|[Kk]")).matches(utf8("\u212A"))).isTrue();
    }

    private static Slice latin1(String value)
    {
        return Slices.wrappedBuffer(value.getBytes(StandardCharsets.ISO_8859_1));
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
