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
import static io.airlift.slice.Slices.wrappedBuffer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

public class TestPublicMatchCount
{
    @Test
    public void testMatchesIndependentJdkCounts()
    {
        for (String expression : List.of("a+", "(a)(b)?", "a|aa", "aa|a", "^a", "a$", "\\b[a-z]+\\b", "[a-z]{2,4}", "[é💰]+")) {
            Re2 re2 = Re2.compile(utf8Slice(expression));
            Pattern jdk = Pattern.compile(expression);
            for (String text : List.of("", "aaaa", "a ab abc", "one_two three four5 five", "é💰 a é", "b")) {
                var input = utf8Slice("padding" + text + "tail").slice(7, utf8Slice(text).length());
                long expected = jdk.matcher(text).results().count();
                for (int pass = 0; pass < 2; pass++) {
                    assertThat(re2.count(input)).as("RE2 %s / %s", expression, text).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    public void testEmptyMatchFallback()
    {
        for (String expression : List.of("", "a*", "a?", "a*?", "^", "$", "\\b")) {
            Re2 re2 = Re2.compile(utf8Slice(expression));
            for (String text : List.of("", "aaa", "a b", "bb")) {
                long expected = Pattern.compile(expression).matcher(text).results().count();
                assertThat(re2.count(utf8Slice(text))).isEqualTo(expected);
            }
        }
        // Empty matches advance using the frontend's existing UTF-8 matcher semantics.
        assertThat(Re2.compile(utf8Slice("")).count(utf8Slice("é💰"))).isEqualTo(3);
        assertThat(Re2.compile(utf8Slice(""), Re2.Options.latin1()).count(utf8Slice("é💰"))).isEqualTo(7);
    }

    @Test
    public void testLongestMatchAndSmallMemoryFallback()
    {
        assertThat(Re2.compile(utf8Slice("a|aa"), Re2.Options.posix()).count(utf8Slice("aa"))).isEqualTo(1);
        Re2 re2 = Re2.compile(utf8Slice("(a|b)+c"), Re2.Options.defaults().setMaxMemory(2048));
        assertThat(re2.count(utf8Slice("aac bbc nope"))).isEqualTo(2);
    }

    @Test
    public void testMalformedBytesFollowMatcher()
    {
        var input = wrappedBuffer(new byte[] {'a', (byte) 0xFF, (byte) 0xC3, 'b'});
        for (String expression : List.of("", ".", "[a-z]+")) {
            Re2 re2 = Re2.compile(utf8Slice(expression));
            Re2Matcher matcher = re2.matcher(input, 0);
            long expected = 0;
            while (matcher.find()) {
                expected++;
            }
            assertThat(re2.count(input)).isEqualTo(expected);
        }
    }

    @Test
    public void testNullInput()
    {
        assertThatNullPointerException().isThrownBy(() -> Re2.compile(utf8Slice("a")).count(null));
    }
}
