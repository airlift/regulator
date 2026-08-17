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

import static io.airlift.regulator.Regexp.LATIN1;
import static io.airlift.regulator.Regexp.LIKE_PERL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestRe2Api
{
    private static final int DEFAULT_FLAGS = LIKE_PERL;

    @Test
    public void testFindAndMatches()
    {
        Re2 re2 = Re2.compile(Slices.wrappedBuffer("abc".getBytes(StandardCharsets.UTF_8)), DEFAULT_FLAGS);

        assertThat(re2.find(Slices.wrappedBuffer("xxabczz".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(re2.lookingAt(Slices.wrappedBuffer("abczz".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(re2.lookingAt(Slices.wrappedBuffer("xabc".getBytes(StandardCharsets.UTF_8)))).isFalse();
        assertThat(re2.matches(Slices.wrappedBuffer("xxabczz".getBytes(StandardCharsets.UTF_8)))).isFalse();

        assertThat(re2.matches(Slices.wrappedBuffer("abc".getBytes(StandardCharsets.UTF_8)))).isTrue();
    }

    @Test
    public void testWordBoundaries()
    {
        Re2 re2 = Re2.compile(Slices.wrappedBuffer("\\babc\\b".getBytes(StandardCharsets.UTF_8)), DEFAULT_FLAGS);

        assertThat(re2.find(Slices.wrappedBuffer(" abc ".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(re2.find(Slices.wrappedBuffer("zabc ".getBytes(StandardCharsets.UTF_8)))).isFalse();
    }

    @Test
    public void testPerlCharacterClasses()
    {
        Re2 digit = Re2.compile(Slices.wrappedBuffer("\\d+".getBytes(StandardCharsets.UTF_8)), DEFAULT_FLAGS);
        assertThat(digit.find(Slices.wrappedBuffer("abc123def".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(digit.matches(Slices.wrappedBuffer("123".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(digit.find(Slices.wrappedBuffer("abcdef".getBytes(StandardCharsets.UTF_8)))).isFalse();

        Re2 digitInClass = Re2.compile(Slices.wrappedBuffer("[\\d]+".getBytes(StandardCharsets.UTF_8)), DEFAULT_FLAGS);
        assertThat(digitInClass.matches(Slices.wrappedBuffer("123".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(digitInClass.find(Slices.wrappedBuffer("a1b".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(digitInClass.matches(Slices.wrappedBuffer("abc".getBytes(StandardCharsets.UTF_8)))).isFalse();

        Re2 nonDigitInClass = Re2.compile(Slices.wrappedBuffer("[^\\d]+".getBytes(StandardCharsets.UTF_8)), DEFAULT_FLAGS);
        assertThat(nonDigitInClass.matches(Slices.wrappedBuffer("abc".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(nonDigitInClass.matches(Slices.wrappedBuffer("123".getBytes(StandardCharsets.UTF_8)))).isFalse();

        Re2 nonDigit = Re2.compile(Slices.wrappedBuffer("\\D+".getBytes(StandardCharsets.UTF_8)), DEFAULT_FLAGS);
        assertThat(nonDigit.matches(Slices.wrappedBuffer("abc".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(nonDigit.matches(Slices.wrappedBuffer("123".getBytes(StandardCharsets.UTF_8)))).isFalse();

        Re2 space = Re2.compile(Slices.wrappedBuffer("\\s+".getBytes(StandardCharsets.UTF_8)), DEFAULT_FLAGS);
        assertThat(space.matches(Slices.wrappedBuffer(" \t".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(space.find(Slices.wrappedBuffer("x \t y".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(space.matches(Slices.wrappedBuffer("x".getBytes(StandardCharsets.UTF_8)))).isFalse();
        assertThat(space.matches(Slices.wrappedBuffer("\u000b".getBytes(StandardCharsets.UTF_8)))).isFalse(); // vertical tab is not in \s

        Re2 nonSpace = Re2.compile(Slices.wrappedBuffer("\\S+".getBytes(StandardCharsets.UTF_8)), DEFAULT_FLAGS);
        assertThat(nonSpace.matches(Slices.wrappedBuffer("abc".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(nonSpace.find(Slices.wrappedBuffer(" \t abc".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(nonSpace.matches(Slices.wrappedBuffer(" \t".getBytes(StandardCharsets.UTF_8)))).isFalse();
        assertThat(nonSpace.matches(Slices.wrappedBuffer("\u000b".getBytes(StandardCharsets.UTF_8)))).isTrue();

        Re2 word = Re2.compile(Slices.wrappedBuffer("\\w+".getBytes(StandardCharsets.UTF_8)), DEFAULT_FLAGS);
        assertThat(word.matches(Slices.wrappedBuffer("Az_09".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(word.find(Slices.wrappedBuffer("--Az_09--".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(word.matches(Slices.wrappedBuffer("-".getBytes(StandardCharsets.UTF_8)))).isFalse();

        Re2 nonWord = Re2.compile(Slices.wrappedBuffer("\\W+".getBytes(StandardCharsets.UTF_8)), DEFAULT_FLAGS);
        assertThat(nonWord.matches(Slices.wrappedBuffer("-".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(nonWord.find(Slices.wrappedBuffer("a-b".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(nonWord.matches(Slices.wrappedBuffer("a".getBytes(StandardCharsets.UTF_8)))).isFalse();
    }

    @Test
    public void testDotDoesNotMatchNewlineByDefault()
    {
        Re2 dot = Re2.compile(Slices.wrappedBuffer(".".getBytes(StandardCharsets.UTF_8)), DEFAULT_FLAGS);
        assertThat(dot.matches(Slices.wrappedBuffer("a".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(dot.matches(Slices.wrappedBuffer("\n".getBytes(StandardCharsets.UTF_8)))).isFalse();

        Re2 dotLatin1 = Re2.compile(Slices.wrappedBuffer(".".getBytes(StandardCharsets.UTF_8)), LATIN1);
        assertThat(dotLatin1.matches(Slices.wrappedBuffer(new byte[] {(byte) 'a'}))).isTrue();
        assertThat(dotLatin1.matches(Slices.wrappedBuffer(new byte[] {(byte) '\n'}))).isFalse();
    }

    @Test
    public void testCallerOwnedCaptureBuffers()
    {
        Re2 re2 = Re2.compile(Slices.wrappedBuffer("a(b)c".getBytes(StandardCharsets.UTF_8)), DEFAULT_FLAGS);
        Slice text = Slices.wrappedBuffer("zzabczz".getBytes(StandardCharsets.UTF_8));

        int[] submatch = new int[4];
        assertThat(re2.findInto(text, submatch)).isTrue();
        assertThat(submatch[0]).isEqualTo(2);
        assertThat(submatch[1]).isEqualTo(5);
        assertThat(submatch[2]).isEqualTo(3);
        assertThat(submatch[3]).isEqualTo(4);

        assertThat(re2.matchesInto(text, 2, 5, submatch)).isTrue();
        assertThat(submatch[0]).isEqualTo(2);
        assertThat(submatch[1]).isEqualTo(5);

        assertThat(re2.lookingAtInto(text, 2, 5, submatch)).isTrue();
        assertThat(submatch[0]).isEqualTo(2);
        assertThat(submatch[1]).isEqualTo(5);
    }

    @Test
    public void testRangeOverloads()
    {
        Re2 re2 = Re2.compile(Slices.utf8Slice("a(b)c"));
        Slice text = Slices.utf8Slice("zzabczz");

        assertThat(re2.find(text, 2, 5)).isTrue();
        assertThat(re2.lookingAt(text, 2, 5)).isTrue();
        assertThat(re2.matches(text, 2, 5)).isTrue();
        assertThat(re2.matches(text, 1, 5)).isFalse();

        MatchResult findResult = re2.findResult(text, 2, 5);
        assertThat(findResult).isNotNull();
        assertThat(findResult.start()).isEqualTo(2);
        assertThat(findResult.end()).isEqualTo(5);
        assertThat(findResult.groupUtf8(1)).isEqualTo("b");

        assertThat(re2.lookingAtResult(text, 2, 5)).isNotNull();
        assertThat(re2.matchesResult(text, 2, 5)).isNotNull();
        assertThat(re2.matchesResult(text, 1, 5)).isNull();
    }

    @Test
    public void testInvalidRangesAreRejected()
    {
        Re2 re2 = Re2.compile(Slices.utf8Slice("abc"));
        Slice text = Slices.utf8Slice("abc");
        int[] groups = new int[2];

        assertThatThrownBy(() -> re2.find(text, -1, 1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> re2.lookingAtResult(text, 2, 1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> re2.matchesInto(text, 0, 4, groups))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }
}
