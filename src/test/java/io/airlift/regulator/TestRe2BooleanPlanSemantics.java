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

import java.util.List;

import static io.airlift.regulator.Re2.Anchor.ANCHOR_BOTH;
import static io.airlift.regulator.Re2.Anchor.ANCHOR_START;
import static io.airlift.regulator.Re2.Anchor.UNANCHORED;
import static org.assertj.core.api.Assertions.assertThat;

public class TestRe2BooleanPlanSemantics
{
    @Test
    public void testBooleanResultsAgreeWithGeneralEngine()
    {
        assertParity("foo", utf8("xxfooyy"));
        assertParity("(foo)", utf8("xxfooyy"));
        assertParity("^foo", utf8("foobar"));
        assertParity("foo$", utf8("xxfoo"));
        assertParity("^foo$", utf8("foo"));
        assertParity("(?s:.*foo.*)", utf8("xx\nfoo\nyy"));
        assertParity(".*foo.*", utf8("xx\nfoo\nyy"));
        assertParity(".*foo.*", utf8("xx\nbar\nyy"));
        assertParity("foo.*", utf8("foo\nbar"));
        assertParity("^foo(?s:.*)$", utf8("foo\n💰"));
        assertParity("(?s:.*)foo$", utf8("💰\nfoo"));
        assertParity("^foo\\C*$", Slices.wrappedBuffer(new byte[] {'f', 'o', 'o', (byte) 0xFF}));

        byte[] malformedSuffix = {'f', 'o', 'o', (byte) 0xFF};
        assertParity("^foo(?s:.*)$", Slices.wrappedBuffer(malformedSuffix));
        assertThat(Re2.compile(utf8("^foo(?s:.*)$")).matches(Slices.wrappedBuffer(malformedSuffix))).isFalse();

        byte[] malformedPrefix = {(byte) 0xFF, 'f', 'o', 'o'};
        assertParity("(?s:.*)foo$", Slices.wrappedBuffer(malformedPrefix));
        assertThat(Re2.compile(utf8("(?s:.*)foo$")).matches(Slices.wrappedBuffer(malformedPrefix))).isFalse();

        byte[] malformedAroundLiteral = {(byte) 0xFF, 'f', 'o', 'o', (byte) 0xFF};
        assertParity("(?s:.*foo.*)", Slices.wrappedBuffer(malformedAroundLiteral));
        assertThat(Re2.compile(utf8("(?s:.*foo.*)")).find(Slices.wrappedBuffer(malformedAroundLiteral))).isTrue();
    }

    @Test
    public void testNonZeroSliceOffset()
    {
        Slice backing = utf8("ignored-foo\n💰-ignored");
        Slice input = backing.slice(8, 8);
        assertParity("^foo(?s:.*)$", input);
        assertThat(Re2.compile(utf8("^foo(?s:.*)$")).matches(input)).isTrue();
    }

    @Test
    public void testNormalizedSpellingsAndOptionsAgreeWithGeneralEngine()
    {
        List<PatternCase> patterns = List.of(
                new PatternCase(utf8("foo{1}"), Re2.Options.defaults()),
                new PatternCase(utf8("(?:foo){1,1}"), Re2.Options.defaults()),
                new PatternCase(utf8("(foo)"), Re2.Options.defaults()),
                new PatternCase(utf8("(?s:.*?foo.*?)"), Re2.Options.defaults()),
                new PatternCase(utf8(".*?foo.*?"), Re2.Options.defaults()),
                new PatternCase(utf8("^foo(?s:.*?)$"), Re2.Options.defaults()),
                new PatternCase(utf8("(?s:.*?)foo$"), Re2.Options.defaults()),
                new PatternCase(utf8("foo.*"), Re2.Options.defaults().setLiteral(true)),
                new PatternCase(utf8("^foo.*$"), Re2.Options.defaults().setDotMatchesNewline(true)),
                new PatternCase(utf8("^foo.*$"), Re2.Options.defaults().setNeverNewline(true)),
                new PatternCase(utf8("(?m:^foo$)"), Re2.Options.defaults().setOneLine(false)),
                new PatternCase(utf8("foo"), Re2.Options.defaults().setLongestMatch(true)),
                new PatternCase(utf8("foo"), Re2.Options.posix()),
                new PatternCase(utf8("foo"), Re2.Options.latin1()),
                new PatternCase(Slices.wrappedBuffer(new byte[] {(byte) 0xE9}), Re2.Options.latin1()));

        Slice offsetInput = utf8("ignored-xxfoo\n💰-ignored").slice(8, 12);
        List<Slice> inputs = List.of(
                Slices.EMPTY_SLICE,
                utf8("foo"),
                utf8("xxfooyy"),
                utf8("foo\n💰"),
                utf8("💰\nfoo"),
                Slices.wrappedBuffer(new byte[] {(byte) 0xFF, 'f', 'o', 'o', (byte) 0xFF}),
                Slices.wrappedBuffer(new byte[] {(byte) 0xE9}),
                offsetInput);

        for (PatternCase pattern : patterns) {
            Re2 re2 = Re2.compile(pattern.pattern(), pattern.options());
            for (Slice input : inputs) {
                assertParity(re2, input);
            }
        }
    }

    @Test
    public void testOptionalDotGapsExhaustivelyAgreeWithGeneralEngine()
    {
        List<Re2> patterns = List.of(
                Re2.compile(utf8(".*foo.*")),
                Re2.compile(utf8(".*?foo.*?")),
                Re2.compile(utf8(".*foo")),
                Re2.compile(utf8("foo.*")));
        byte[] alphabet = {'f', 'o', 'x', '\n', (byte) 0xFF};

        int combinationCount = 1;
        for (int length = 0; length <= 5; length++) {
            for (int combination = 0; combination < combinationCount; combination++) {
                byte[] bytes = new byte[length];
                int value = combination;
                for (int position = 0; position < length; position++) {
                    bytes[position] = alphabet[value % alphabet.length];
                    value /= alphabet.length;
                }
                Slice input = Slices.wrappedBuffer(bytes);
                for (Re2 pattern : patterns) {
                    assertParity(pattern, input);
                }
            }
            combinationCount *= alphabet.length;
        }
    }

    private static void assertParity(String pattern, Slice input)
    {
        assertParity(Re2.compile(utf8(pattern)), input);
    }

    private static void assertParity(Re2 re2, Slice input)
    {
        String pattern = re2.pattern().toStringUtf8();
        assertThat(re2.find(input)).as("find: %s", pattern).isEqualTo(re2.matchInto(input, UNANCHORED, null));
        assertThat(re2.lookingAt(input)).as("lookingAt: %s", pattern).isEqualTo(re2.matchInto(input, ANCHOR_START, null));
        assertThat(re2.matches(input)).as("matches: %s", pattern).isEqualTo(re2.matchInto(input, ANCHOR_BOTH, null));
    }

    private static Slice utf8(String value)
    {
        return Slices.utf8Slice(value);
    }

    private record PatternCase(Slice pattern, Re2.Options options) {}
}
