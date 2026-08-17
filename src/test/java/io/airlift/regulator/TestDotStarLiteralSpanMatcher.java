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
import io.airlift.slice.SliceUtf8;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDotStarLiteralSpanMatcher
{
    @Test
    public void testNativeBoundaries()
    {
        DotStarLiteralSpanMatcher matcher = matcher(".*coolfunctionname.*");

        assertSpan(matcher, utf8Slice("prefix-coolfunctionname-suffix"), 0, 0, 30);
        assertSpan(matcher, utf8Slice("coolfunctionname-middle-coolfunctionname"), 0, 0, 40);
        assertSpan(matcher, utf8Slice("before\nxxcoolfunctionnameyy\nafter"), 0, 7, 27);
        assertSpan(matcher, utf8Slice("abc--coolfunctionname--tail"), 3, 3, 27);
        assertSpan(matcher, utf8Slice("skip\nxxcoolfunctionnameyy\nafter"), 2, 5, 25);
        assertSpan(matcher, utf8Slice("é💰coolfunctionnameж終"), 0, 0, 27);
        assertThat(matcher.findSpan(utf8Slice("ordinaryFunction"), 0)).isEqualTo(Dfa.SEARCH_NO_MATCH);
    }

    @Test
    public void testMalformedUtf8Boundaries()
    {
        DotStarLiteralSpanMatcher matcher = matcher(".*coolfunctionname.*");
        assertSpan(
                matcher,
                wrappedBuffer(new byte[] {
                        'a', ' ', (byte) 0xFF,
                        'c', 'o', 'o', 'l', 'f', 'u', 'n', 'c', 't', 'i', 'o', 'n', 'n', 'a', 'm', 'e', ' ', 'z',
                }),
                0,
                3,
                21);
        assertSpan(
                matcher,
                wrappedBuffer(new byte[] {
                        'a', ' ',
                        'c', 'o', 'o', 'l', 'f', 'u', 'n', 'c', 't', 'i', 'o', 'n', 'n', 'a', 'm', 'e',
                        (byte) 0xFF, ' ', 'z',
                }),
                0,
                0,
                18);
    }

    @Test
    public void testLatin1TreatsHighBytesAsCharacters()
    {
        Re2 pattern = Re2.compile(
                wrappedBuffer(new byte[] {'.', '*', (byte) 0xE9, '.', '*'}),
                Re2.Options.latin1());
        DotStarLiteralSpanMatcher matcher = pattern.createDotStarLiteralSpanMatcher();
        assertThat(matcher).isNotNull();
        assertSpan(matcher, wrappedBuffer(new byte[] {'a', (byte) 0xFF, (byte) 0xE9, 'z'}), 0, 0, 4);
    }

    @Test
    public void testEligibility()
    {
        assertThat(matcher(".*literal.*")).isNotNull();
        assertThat(optionalMatcher("(?s:.*literal.*)")).isNull();
        assertThat(optionalMatcher("(.*literal.*)")).isNull();
        assertThat(optionalMatcher("^.*literal.*")).isNull();
        assertThat(optionalMatcher(".*literal.*$")).isNull();
        assertThat(optionalMatcher(".*foo\\nbar.*")).isNull();
        assertThat(optionalMatcher("literal.*")).isNull();
        assertThat(optionalMatcher(".*literal")).isNull();
        assertThat(optionalMatcher("(?i:.*literal.*)")).isNull();
        assertThat(optionalMatcher(".*?literal.*")).isNull();
        assertThat(optionalMatcher(".*literal.*?")).isNull();
    }

    @Test
    public void testGeneralEngineAgreement()
    {
        List<Slice> inputs = List.of(
                utf8Slice(""),
                utf8Slice("literal"),
                utf8Slice("before literal after"),
                utf8Slice("before\nliteral\nafter"),
                utf8Slice("literal one literal two"),
                utf8Slice("é💰literalж終"),
                wrappedBuffer(new byte[] {'a', (byte) 0xFF, 'l', 'i', 't', 'e', 'r', 'a', 'l', 'z'}),
                wrappedBuffer(new byte[] {'a', 'l', 'i', 't', 'e', 'r', 'a', 'l', (byte) 0xFF, 'z'}));
        Re2 pattern = Re2.compile(utf8Slice(".*literal.*"));
        DotStarLiteralSpanMatcher matcher = pattern.createDotStarLiteralSpanMatcher();

        for (Slice input : inputs) {
            int start = 0;
            while (true) {
                Re2Matcher generalMatcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
                long span = matcher.findSpan(input, start);
                if (generalMatcher.find(start)) {
                    assertThat(span)
                            .as("input %s start %s", input, start)
                            .isEqualTo(((long) generalMatcher.start() << 32) | (generalMatcher.end() & 0xFFFF_FFFFL));
                }
                else {
                    assertThat(span).as("input %s start %s", input, start).isEqualTo(Dfa.SEARCH_NO_MATCH);
                }

                if (start == input.length()) {
                    break;
                }
                start += SliceUtf8.lengthOfCodePointSafe(
                        input.byteArray(),
                        input.byteArrayOffset(),
                        input.length(),
                        start);
            }
        }
    }

    private static DotStarLiteralSpanMatcher matcher(String pattern)
    {
        return Re2.compile(utf8Slice(pattern)).createDotStarLiteralSpanMatcher();
    }

    private static DotStarLiteralSpanMatcher optionalMatcher(String pattern)
    {
        return Re2.compile(utf8Slice(pattern)).createDotStarLiteralSpanMatcher();
    }

    private static void assertSpan(DotStarLiteralSpanMatcher matcher, Slice input, int searchStart, int expectedStart, int expectedEnd)
    {
        long span = matcher.findSpan(input, searchStart);
        assertThat((int) (span >>> 32)).isEqualTo(expectedStart);
        assertThat((int) span).isEqualTo(expectedEnd);
    }
}
