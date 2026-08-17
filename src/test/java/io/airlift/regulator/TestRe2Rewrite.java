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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Ported from upstream RE2: re2/testing/re2_test.cc.
public class TestRe2Rewrite
{
    private static final int DEFAULT_FLAGS = Regexp.LIKE_PERL;

    private record ReplaceCase(String regexp, String rewrite, String original, String single, String global, int count) {}

    @Test
    public void testReplaceAndGlobalReplace()
    {
        List<ReplaceCase> cases = List.of(
                new ReplaceCase(
                        "(qu|[b-df-hj-np-tv-z]*)([a-z]+)",
                        "\\2\\1ay",
                        "the quick brown fox jumps over the lazy dogs.",
                        "ethay quick brown fox jumps over the lazy dogs.",
                        "ethay ickquay ownbray oxfay umpsjay overay ethay azylay ogsday.",
                        9),
                new ReplaceCase(
                        "\\w+",
                        "\\0-NOSPAM",
                        "abcd.efghi@google.com",
                        "abcd-NOSPAM.efghi@google.com",
                        "abcd-NOSPAM.efghi-NOSPAM@google-NOSPAM.com-NOSPAM",
                        4),
                new ReplaceCase(
                        "^",
                        "(START)",
                        "foo",
                        "(START)foo",
                        "(START)foo",
                        1),
                new ReplaceCase(
                        "^",
                        "(START)",
                        "",
                        "(START)",
                        "(START)",
                        1),
                new ReplaceCase(
                        "$",
                        "(END)",
                        "",
                        "(END)",
                        "(END)",
                        1),
                new ReplaceCase(
                        "b",
                        "bb",
                        "ababababab",
                        "abbabababab",
                        "abbabbabbabbabb",
                        5),
                new ReplaceCase(
                        "b",
                        "bb",
                        "bbbbbb",
                        "bbbbbbb",
                        "bbbbbbbbbbbb",
                        6),
                new ReplaceCase(
                        "b+",
                        "bb",
                        "bbbbbb",
                        "bb",
                        "bb",
                        1),
                new ReplaceCase(
                        "b*",
                        "bb",
                        "bbbbbb",
                        "bb",
                        "bb",
                        1),
                new ReplaceCase(
                        "b*",
                        "bb",
                        "aaaaa",
                        "bbaaaaa",
                        "bbabbabbabbabbabb",
                        6),
                new ReplaceCase(
                        "a.*a",
                        "(\\0)",
                        "aba\naba",
                        "(aba)\naba",
                        "(aba)\n(aba)",
                        2));

        for (ReplaceCase c : cases) {
            Re2 re2 = Re2.compile(utf8(c.regexp()), DEFAULT_FLAGS);
            Slice single = re2.replaceFirst(utf8(c.original()), utf8(c.rewrite()));
            assertThat(toString(single)).as("replace result: %s", c.regexp()).isEqualTo(c.single());

            Re2.ReplaceAllResult global = re2.replaceAll(utf8(c.original()), utf8(c.rewrite()));
            assertThat(global.replacementCount()).as("global count: %s", c.regexp()).isEqualTo(c.count());
            assertThat(toString(global.result())).as("global result: %s", c.regexp()).isEqualTo(c.global());
        }
    }

    @Test
    public void testCheckRewriteString()
    {
        assertValidRewrite("abc", "foo");
        assertInvalidRewrite("abc", "foo\\");
        assertValidRewrite("abc", "foo\\0bar");

        assertValidRewrite("a(b)c", "foo");
        assertValidRewrite("a(b)c", "foo\\0bar");
        assertValidRewrite("a(b)c", "foo\\1bar");
        assertInvalidRewrite("a(b)c", "foo\\2bar");
        assertValidRewrite("a(b)c", "f\\\\2o\\1o");

        assertValidRewrite("a(b)(c)", "foo\\12");
        assertValidRewrite("a(b)(c)", "f\\2o\\1o");
        assertInvalidRewrite("a(b)(c)", "f\\oo\\1");
    }

    @Test
    public void testExtract()
    {
        Slice s = Re2.compile(utf8("(.*)@([^.]*)"), DEFAULT_FLAGS).extract(utf8("boris@kremvax.ru"), utf8("\\2!\\1"));
        assertThat(s).isNotNull();
        assertThat(toString(s)).isEqualTo("kremvax!boris");

        s = Re2.compile(utf8(".*"), DEFAULT_FLAGS).extract(utf8("foo"), utf8("'\\0'"));
        assertThat(s).isNotNull();
        assertThat(toString(s)).isEqualTo("'foo'");

        s = Re2.compile(utf8("bar"), DEFAULT_FLAGS).extract(utf8("baz"), utf8("'\\0'"));
        assertThat(s).isNull();
    }

    @Test
    public void testMaxSubmatchTooLarge()
    {
        Re2 re2 = Re2.compile(utf8("f(o+)"), DEFAULT_FLAGS);
        assertThatThrownBy(() -> re2.extract(utf8("foo"), utf8("\\1\\2")))
                .isInstanceOf(RegexpRewriteException.class);
        assertThatThrownBy(() -> re2.replaceFirst(utf8("foo"), utf8("\\1\\2")))
                .isInstanceOf(RegexpRewriteException.class);
        assertThatThrownBy(() -> re2.replaceAll(utf8("foo"), utf8("\\1\\2")))
                .isInstanceOf(RegexpRewriteException.class);
    }

    private static void assertValidRewrite(String regexp, String rewrite)
    {
        Re2 re2 = Re2.compile(utf8(regexp), DEFAULT_FLAGS);
        assertThat(re2.replaceFirst(utf8("abc"), utf8(rewrite))).isNotNull();
    }

    private static void assertInvalidRewrite(String regexp, String rewrite)
    {
        Re2 re2 = Re2.compile(utf8(regexp), DEFAULT_FLAGS);
        assertThatThrownBy(() -> re2.replaceFirst(utf8("abc"), utf8(rewrite)))
                .isInstanceOf(RegexpRewriteException.class);
    }

    private static Slice utf8(String value)
    {
        return Slices.wrappedBuffer(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String toString(Slice slice)
    {
        return slice.toStringUtf8();
    }
}
