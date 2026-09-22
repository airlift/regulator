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

import io.airlift.jcodings.specific.NonStrictUTF8Encoding;
import io.airlift.joni.Matcher;
import io.airlift.joni.Option;
import io.airlift.joni.Regex;
import io.airlift.joni.Region;
import io.airlift.joni.Syntax;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.airlift.slice.SliceUtf8.lengthOfCodePointFromStartByte;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTrinoRegexpMatcher
{
    @Test
    public void testIterationMatchesJoni()
    {
        for (String expression : List.of(
                "foo|foobar|bar",
                "foobar|foo|bar",
                "abc",
                "[ab]",
                "[ab]*",
                "a.*b",
                "(?s:.*)a(?s:.*)b(?s:.*)",
                "[a-z]{2}",
                "[0-9]{2}:",
                "[0-9]{4}-[0-9]{2}-[0-9]{2}",
                "(a)|(b*)",
                "(é+)(💰)?",
                "",
                "^|$",
                "a$",
                "(?m)^",
                "(?m)^$",
                "(?m)a\\n^",
                "(?m)^a$",
                "(?<word>a+)(b)?")) {
            Slice pattern = utf8Slice(expression);
            Regex joni = new Regex(pattern.getBytes(), 0, pattern.length(), Option.DEFAULT, NonStrictUTF8Encoding.INSTANCE, Syntax.Java);
            TrinoRegexp compiled = TrinoRegexp.compile(pattern);
            for (int retainedGroups : new int[] {0, joni.numberOfCaptures()}) {
                TrinoRegexpMatcher actual = compiled.matcher(EMPTY_SLICE, retainedGroups);
                for (String text : List.of("", "x", "foobarfoo", "abcab", "a\nb\na\n", "éé💰ab", "💰\n", "!12:34:?", "δ2026-09-12💰2025-01-01")) {
                    Slice source = utf8Slice("padding" + text).slice(7, utf8Slice(text).length());
                    actual.reset(source);
                    assertThat(actual.groupCount()).isEqualTo(retainedGroups);
                    int base = source.byteArrayOffset();
                    Matcher expected = joni.matcher(source.byteArray(), base, base + source.length());
                    int next = 0;
                    while (next <= source.length() && expected.search(base + next, base + source.length(), Option.DEFAULT) >= 0) {
                        assertThat(actual.find()).as("%s on %s at %s", expression, text, next).isTrue();
                        Region groups = expected.getEagerRegion();
                        for (int group = 0; group <= retainedGroups; group++) {
                            assertThat(actual.start(group)).isEqualTo(groups.beg[group]);
                            assertThat(actual.end(group)).isEqualTo(groups.end[group]);
                            assertThat(actual.matched(group)).isEqualTo(groups.beg[group] >= 0);
                            assertThat(actual.group(group)).isEqualTo(groups.beg[group] < 0 ? null :
                                    source.slice(groups.beg[group], groups.end[group] - groups.beg[group]));
                        }
                        assertThat(actual.start()).isEqualTo(expected.getBegin());
                        assertThat(actual.end()).isEqualTo(expected.getEnd());
                        assertThat(actual.group()).isEqualTo(actual.group(0));
                        next = expected.getEnd();
                        if (expected.getBegin() == next) {
                            next += next < source.length() ? lengthOfCodePointFromStartByte(source.getByte(next)) : 1;
                        }
                    }
                    assertThat(actual.find()).isFalse();
                    assertThat(actual.find()).isFalse();
                    assertThatThrownBy(actual::start).isInstanceOf(IllegalStateException.class);
                }
            }
        }
    }

    @Test
    public void testFixedWidthMatcherUsesRetainedKernel()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("[0-9]{2}:"));
        assertThat(regexp).isInstanceOf(TrinoRegexp.FixedWidthByteTrinoRegexp.class);
        Prog placeholder = regexp.pattern().forwardProgramForDiagnostics();
        assertThat(placeholder.start()).isZero();
        Slice input = utf8Slice("!12:34:?").slice(1, 6);
        TrinoRegexpMatcher matcher = regexp.matcher(input, 0);
        assertThat(matcher.groupCount()).isZero();
        assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isEqualTo(3);
        Slice first = matcher.group();
        assertThat(first).isEqualTo(utf8Slice("12:"));
        assertThat(matcher.matched(0)).isTrue();
        assertThatThrownBy(() -> matcher.start(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> matcher.end(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regexp.matcher(input, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).isEqualTo(utf8Slice("34:"));
        assertThat(matcher.find()).isFalse();
        assertThat(matcher.find()).isFalse();
        assertThatThrownBy(matcher::start).isInstanceOf(IllegalStateException.class);
        matcher.reset(utf8Slice("56:"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).isEqualTo(utf8Slice("56:"));
        matcher.reset(EMPTY_SLICE);
        assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
        assertThat(matcher.find()).isFalse();
        input.setByte(0, '9');
        assertThat(first).isEqualTo(utf8Slice("92:"));
        assertThat(regexp.pattern().forwardProgramForDiagnostics()).isSameAs(placeholder);
        assertThat(placeholder.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
    }

    @Test
    public void testLiteralAlternationDoesNotCompileGenericProgram()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("foo|bar"));
        assertThat(regexp.literalAlternationSpanMatcher()).isNotNull();
        assertThat(regexp.pattern()).isNull();
        TrinoRegexpMatcher matcher = regexp.matcher(utf8Slice("barfoo"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).isEqualTo(utf8Slice("bar"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).isEqualTo(utf8Slice("foo"));
        assertThat(matcher.find()).isFalse();
        assertThat(regexp.pattern()).isNull();
    }

    @Test
    public void testRetainedGroupsAndIndependentMatchers()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("(a)(b)?(c)?"));
        TrinoRegexpMatcher first = regexp.matcher(utf8Slice("ab"), 1);
        TrinoRegexpMatcher second = regexp.matcher(utf8Slice("ac"));
        assertThat(first.groupCount()).isEqualTo(1);
        assertThat(second.groupCount()).isEqualTo(3);
        assertThat(first.find()).isTrue();
        assertThat(second.find()).isTrue();
        assertThat(first.group()).isEqualTo(utf8Slice("ab"));
        assertThat(second.group(2)).isNull();
        assertThat(second.group(3)).isEqualTo(utf8Slice("c"));
        assertThatThrownBy(() -> first.group(2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testMatcherStateAndZeroCopyGroups()
    {
        for (String pattern : List.of("foo", "foo|bar")) {
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(pattern));
            Slice source = utf8Slice("foo");
            TrinoRegexpMatcher matcher = regexp.matcher(source);
            assertThatThrownBy(matcher::group).isInstanceOf(IllegalStateException.class);
            assertThat(matcher.find()).isTrue();
            assertThatThrownBy(() -> matcher.start(-1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> matcher.end(1)).isInstanceOf(IllegalArgumentException.class);
            Slice group = matcher.group();
            source.setByte(0, 'x');
            assertThat(group).isEqualTo(utf8Slice("xoo"));
            matcher.reset(EMPTY_SLICE);
            assertThat(group).isEqualTo(utf8Slice("xoo"));
            assertThatThrownBy(() -> matcher.matched(0)).isInstanceOf(IllegalStateException.class);
            assertThat(matcher.find()).isFalse();
            assertThatThrownBy(() -> matcher.reset(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> regexp.matcher(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> regexp.matcher(source, -1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> regexp.matcher(source, 1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    public void testMalformedInputProgressUsesExistingSafeAdvancement()
    {
        Slice input = wrappedBuffer(new byte[] {'!', (byte) 0xFF, 'a', (byte) 0xC3, '?'}).slice(1, 3);
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(""));
        TrinoRegexpMatcher matcher = regexp.matcher(input, 0);
        for (int offset = 0; offset <= input.length(); offset++) {
            assertThat(matcher.find()).isTrue();
            assertThat(matcher.start()).isEqualTo(offset);
            assertThat(matcher.end()).isEqualTo(offset);
        }
        assertThat(matcher.find()).isFalse();
        assertThat(regexp.extractAll(input)).hasSize(input.length() + 1);
    }
}
