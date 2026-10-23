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

import com.sun.management.ThreadMXBean;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public class TestRegexpParser
{
    @Test
    public void testWildcardOnlySubsumesScalarAlternatives()
    {
        // RE2 parse.cc retains alternatives with captures, empty matches, or several scalars.
        for (String alternative : List.of("ab", "", "(a)", "a+", "a?", "\\C")) {
            for (String expression : List.of(alternative + "|.", ".|" + alternative)) {
                ParseResult result = RegexpParser.parse(Slices.utf8Slice(expression), Regexp.PERL_EXTENSIONS | Regexp.DOT_MATCHES_NEWLINE);
                assertThat(result.regexp().op()).as(expression).isEqualTo(RegexpOp.ALTERNATE);
            }
        }
        for (String expression : List.of("a|.", ".|a", "[a-z]|.", ".|[a-z]")) {
            ParseResult result = RegexpParser.parse(Slices.utf8Slice(expression), Regexp.PERL_EXTENSIONS | Regexp.DOT_MATCHES_NEWLINE);
            assertThat(result.regexp().op()).as(expression).isEqualTo(RegexpOp.ANY_CHAR);
        }
    }

    @Test
    public void testDeeplyNestedGroupsAreStackSafe()
    {
        String pattern = "(?:".repeat(10_000) + "a" + ")".repeat(10_000);
        assertThatCode(() -> RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(StandardCharsets.UTF_8)), Regexp.PERL_EXTENSIONS))
                .doesNotThrowAnyException();
    }

    @Test
    public void testDeeplyNestedClassesAreStackSafe()
    {
        Slice pattern = Slices.utf8Slice("[".repeat(100_000) + "a" + "]".repeat(100_000));
        Slice expected = Slices.utf8Slice("[a]");
        assertThat(TrinoRegexpParser.parse(pattern, Regexp.LIKE_PERL).regexp())
                .isEqualTo(TrinoRegexpParser.parse(expected, Regexp.LIKE_PERL).regexp());
        assertThat(JavaRegexpParser.parse(pattern, Regexp.LIKE_PERL).regexp())
                .isEqualTo(JavaRegexpParser.parse(expected, Regexp.LIKE_PERL).regexp());
    }

    @Test
    @Timeout(30)
    public void testCharacterClassesBuildInNLogNTime()
    {
        // About 1 MB: a property added to a large class again and again.
        assertParseAllocationPerByte("[" + separateRunes(0x10000, 2, 230_000) + "\\p{Lu}".repeat(20_000) + "]", 1_000, Frontend.values());
        // About 1 MB of runes in descending order. Only the timeout guards this case, since the old
        // cost was shifting the class's ranges, which allocates nothing.
        assertParsesInEveryFrontend("[" + new StringBuilder(separateRunes(0x10000, 2, 230_000)).reverse() + "]");
    }

    @Test
    public void testNestedClassSetsCombineWithoutCopying()
    {
        // About 200 KB each: a large set nested deeply, negated at every level, intersected again
        // and again, and negated at every level under case-insensitive matching.
        String runes = separateRunes(0x10000, 2, 30_000);
        for (String pattern : List.of(
                "[".repeat(20_000) + runes + "]".repeat(20_000),
                "[^".repeat(14_000) + runes + "]".repeat(14_000),
                "[" + runes + "&&[^a]".repeat(14_000) + "]",
                "(?i)" + "[^".repeat(14_000) + runes + "]".repeat(14_000))) {
            assertParseAllocationPerByte(pattern, 500, Frontend.TRINO, Frontend.JAVA, Frontend.RUST);
        }
        // Rust's symmetric difference, again and again with [^a], a nearly full set.
        assertParseAllocationPerByte("[" + runes + "~~[^a]".repeat(14_000) + "]", 500, Frontend.RUST);
    }

    @Test
    public void testParsedPatternsSimplifyWithoutRecopying()
    {
        // About 200 KB each: deeply nested concatenations, and two alternatives sharing a long
        // prefix, which factoring removes one class at a time.
        String prefix = "[ac]".repeat(25_000);
        for (String pattern : List.of(
                "a(?:".repeat(40_000) + "b" + ")".repeat(40_000),
                prefix + "x|" + prefix + "y")) {
            assertParseAllocationPerByte(pattern, 1_000, Frontend.values());
        }
    }

    @Test
    public void testNullableRepeatedCapturesCheckInLinearTime()
    {
        // About 40 KB: quantified groups nested in quantified groups, each starting with the one
        // below and holding the innermost capture, so every quantifier is checked.
        int depth = 7_000;
        assertParseAllocationPerByte("(?:".repeat(depth) + "(a.)+" + "a)+".repeat(depth), 1_000, Frontend.JAVA);
    }

    @Test
    @Timeout(30)
    public void testPosixClassTerminatorsFoundInLinearTime()
    {
        // About 1 MB: every "[:" could open a POSIX class, but no ":]" ever closes one. The old
        // scan for a terminator allocated nothing, so only the timeout guards this case.
        int count = 330_000;
        Slice pattern = Slices.utf8Slice("[" + "[:".repeat(count) + "x" + "]".repeat(count + 1));
        assertThatCode(() -> RegexpParser.parse(pattern, Regexp.LIKE_PERL)).doesNotThrowAnyException();
        assertThatCode(() -> TrinoRegexpParser.parse(pattern, Regexp.LIKE_PERL)).doesNotThrowAnyException();
    }

    @Test
    @Timeout(30)
    public void testPatternsWithOverAMillionNodesParse()
    {
        // More than a million nodes below the depth where simplification walks iteratively. The
        // groups capture, since Trino flattens noncapturing groups while parsing.
        assertParsesInEveryFrontend("(a".repeat(300) + ".".repeat(1_100_000) + ")".repeat(300));
    }

    @Test
    public void testMaximumCapturesAreStackSafe()
            throws InterruptedException
    {
        int captureCount = 10_000;
        String pattern = "(".repeat(captureCount) + "a" + ")".repeat(captureCount) + "{2}";
        AtomicReference<ParseResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread parserThread = new Thread(null, () -> {
            try {
                result.set(RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(StandardCharsets.UTF_8)), 0));
            }
            catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "regexp-parser-small-stack", 256 * 1024);

        parserThread.start();
        parserThread.join();

        assertThat(failure.get()).isNull();
        assertThat(result.get().capturingGroupCount()).isEqualTo(captureCount);
    }

    @Test
    public void testLongLiteralsAccumulateInEveryFrontend()
    {
        // Longer than the parsers' initial pending-rune buffer, so accumulation has to grow it.
        String literal = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int[] expected = literal.codePoints().toArray();
        Slice pattern = Slices.wrappedBuffer(literal.getBytes(StandardCharsets.UTF_8));

        for (Regexp regexp : List.of(
                RegexpParser.parse(pattern, Regexp.LIKE_PERL).regexp(),
                JavaRegexpParser.parse(pattern, Regexp.LIKE_PERL).regexp(),
                TrinoRegexpParser.parse(pattern, Regexp.LIKE_PERL).regexp())) {
            assertThat(regexp.op()).isEqualTo(RegexpOp.LITERAL_STRING);
            assertThat(regexp.runes()).containsExactly(expected);
        }

        // A literal that is interrupted ends with exactly the runes seen so far, and the literal
        // that follows starts a new string.
        Slice interrupted = Slices.wrappedBuffer("abc(d)ef".getBytes(StandardCharsets.UTF_8));
        for (Regexp regexp : List.of(
                RegexpParser.parse(interrupted, Regexp.LIKE_PERL).regexp(),
                JavaRegexpParser.parse(interrupted, Regexp.LIKE_PERL).regexp(),
                TrinoRegexpParser.parse(interrupted, Regexp.LIKE_PERL).regexp())) {
            assertThat(regexp.op()).isEqualTo(RegexpOp.CONCAT);
            assertThat(regexp.childCount()).isEqualTo(3);
            assertThat(regexp.child(0).op()).isEqualTo(RegexpOp.LITERAL_STRING);
            assertThat(regexp.child(0).runes()).containsExactly('a', 'b', 'c');
            assertThat(regexp.child(1).op()).isEqualTo(RegexpOp.CAPTURE);
            assertThat(regexp.child(2).op()).isEqualTo(RegexpOp.LITERAL_STRING);
            assertThat(regexp.child(2).runes()).containsExactly('e', 'f');
        }
    }

    @Test
    public void testParseLiteralString()
    {
        ParseResult result = RegexpParser.parse(Slices.wrappedBuffer("abc".getBytes(StandardCharsets.UTF_8)), 0);

        Regexp re = result.regexp();
        assertThat(re.op()).isEqualTo(RegexpOp.LITERAL_STRING);
        assertThat(re.runes()).containsExactly('a', 'b', 'c');
    }

    @Test
    public void testParseAlternation()
    {
        ParseResult result = RegexpParser.parse(Slices.wrappedBuffer("a|b".getBytes(StandardCharsets.UTF_8)), 0);

        Regexp re = result.regexp();
        // Parse() returns a simplified canonical form (like upstream RE2).
        assertThat(re.op()).isEqualTo(RegexpOp.CHAR_CLASS);
        assertThat(re.charClass().contains('a')).isTrue();
        assertThat(re.charClass().contains('b')).isTrue();
    }

    @Test
    public void testFactoringRemovesClassLeftByRemovedPrefix()
    {
        // Removing the shared a(?i:x) leaves [bc] first in each alternative, which the next round
        // removes in turn.
        Slice pattern = Slices.utf8Slice("(?:a(?i:x)b|a(?i:x)c)y|(?:a(?i:x)b|a(?i:x)c)z");
        for (Map.Entry<Slice, String> testCase : List.of(
                entry(pattern, "cat{lit{a}cat{litfold{x}cat{cc{0x62-0x63}cc{0x79-0x7a}}}}"),
                entry(Slices.utf8Slice("(?:a(?i:x)b|a(?i:x)c)y|(?:a(?i:x)b|a(?i:x)c)"), "cat{lit{a}cat{litfold{x}cat{cc{0x62-0x63}alt{lit{y}emp{}}}}}"))) {
            for (Frontend frontend : Frontend.values()) {
                assertThat(RegexpDump.dump(frontend.parse(testCase.getKey()))).as(frontend.name()).isEqualTo(testCase.getValue());
            }
        }
        // Compiling simplifies the parsed tree again in full mode; the program's size checks that
        // second pass.
        assertThat(Compiler.compile(RegexpParser.parse(pattern, Regexp.LIKE_PERL).regexp()).size()).isEqualTo(8);
    }

    @Test
    public void testParseNonCapturingGroup()
    {
        ParseResult result = RegexpParser.parse(Slices.wrappedBuffer("(?:a)".getBytes(StandardCharsets.UTF_8)), Regexp.PERL_EXTENSIONS);
        assertThat(result.capturingGroupCount()).isEqualTo(0);
        assertThat(result.regexp().op()).isEqualTo(RegexpOp.LITERAL);
        assertThat(result.regexp().rune()).isEqualTo('a');
    }

    @Test
    public void testParseCapturingGroup()
    {
        ParseResult result = RegexpParser.parse(Slices.wrappedBuffer("(a)".getBytes(StandardCharsets.UTF_8)), 0);
        assertThat(result.capturingGroupCount()).isEqualTo(1);
        assertThat(result.regexp().op()).isEqualTo(RegexpOp.CAPTURE);
        assertThat(result.regexp().captureIndex()).isEqualTo(1);
        assertThat(result.regexp().child(0).rune()).isEqualTo('a');
    }

    @Test
    public void testParseCharClassRange()
    {
        ParseResult result = RegexpParser.parse(Slices.wrappedBuffer("[a-z]".getBytes(StandardCharsets.UTF_8)), 0);

        Regexp re = result.regexp();
        assertThat(re.op()).isEqualTo(RegexpOp.CHAR_CLASS);
        assertThat(re.charClass().contains('a')).isTrue();
        assertThat(re.charClass().contains('z')).isTrue();
        assertThat(re.charClass().contains('A')).isFalse();
    }

    @Test
    public void testParseAnyByteEscape()
    {
        ParseResult result = RegexpParser.parse(Slices.wrappedBuffer("\\C".getBytes(StandardCharsets.UTF_8)), Regexp.PERL_EXTENSIONS);
        assertThat(result.regexp().op()).isEqualTo(RegexpOp.ANY_BYTE);
    }

    @Test
    public void testLatin1TreatsBytesAsRunes()
    {
        // UTF-8 encoding of U+00E2 is C3 A2.
        byte[] pattern = new byte[] {(byte) 0xC3, (byte) 0xA2};

        ParseResult utf8 = RegexpParser.parse(Slices.wrappedBuffer(pattern), 0);
        assertThat(utf8.regexp().op()).isEqualTo(RegexpOp.LITERAL);
        assertThat(utf8.regexp().rune()).isEqualTo(0x00E2);

        ParseResult latin1 = RegexpParser.parse(Slices.wrappedBuffer(pattern), Regexp.LATIN1);
        assertThat(latin1.regexp().op()).isEqualTo(RegexpOp.LITERAL_STRING);
        assertThat(latin1.regexp().runes()).containsExactly(0xC3, 0xA2);
    }

    @Test
    public void testLeadingZerosInRepeatTreatedAsLiteral()
    {
        // C++ RE2 treats {01,02} as literal characters, not a repeat operator
        // Pattern "a{01,02}" should match the literal string "a{01,02}"
        ParseResult result = RegexpParser.parse(Slices.wrappedBuffer("a{01,02}".getBytes(StandardCharsets.UTF_8)), 0);

        Regexp re = result.regexp();
        // Should parse as a literal string, not a repeat
        assertThat(re.op()).isEqualTo(RegexpOp.LITERAL_STRING);
        // "a{01,02}" as literal characters
        assertThat(re.runes()).containsExactly('a', '{', '0', '1', ',', '0', '2', '}');

        // Leading zero in second number should also be treated as literal
        ParseResult result2 = RegexpParser.parse(Slices.wrappedBuffer("a{1,02}".getBytes(StandardCharsets.UTF_8)), 0);
        Regexp re2 = result2.regexp();
        assertThat(re2.op()).isEqualTo(RegexpOp.LITERAL_STRING);
        assertThat(re2.runes()).containsExactly('a', '{', '1', ',', '0', '2', '}');

        // Single leading zero alone (e.g., {0,2}) should be valid - 0 is not a leading zero
        ParseResult result3 = RegexpParser.parse(Slices.wrappedBuffer("a{0,2}".getBytes(StandardCharsets.UTF_8)), 0);
        Regexp re3 = result3.regexp();
        // {0,2} is a valid repeat - should not be treated as literal
        assertThat(re3.op()).isNotEqualTo(RegexpOp.LITERAL_STRING);
    }

    @Test
    public void testSquashDuplicateRepeatOperators()
    {
        // ** squashes to * (same operator)
        ParseResult starStar = RegexpParser.parse(Slices.wrappedBuffer("a**".getBytes(StandardCharsets.UTF_8)), 0);
        assertThat(starStar.regexp().op()).isEqualTo(RegexpOp.STAR);
        assertThat(starStar.regexp().child(0).op()).isEqualTo(RegexpOp.LITERAL);

        // *+ squashes to * (mixed operators become STAR)
        ParseResult starPlus = RegexpParser.parse(Slices.wrappedBuffer("a*+".getBytes(StandardCharsets.UTF_8)), 0);
        assertThat(starPlus.regexp().op()).isEqualTo(RegexpOp.STAR);
        assertThat(starPlus.regexp().child(0).op()).isEqualTo(RegexpOp.LITERAL);

        // ?* squashes to * (mixed operators become STAR)
        ParseResult questStar = RegexpParser.parse(Slices.wrappedBuffer("a?*".getBytes(StandardCharsets.UTF_8)), 0);
        assertThat(questStar.regexp().op()).isEqualTo(RegexpOp.STAR);
        assertThat(questStar.regexp().child(0).op()).isEqualTo(RegexpOp.LITERAL);

        // ++ squashes to + (same operator)
        ParseResult plusPlus = RegexpParser.parse(Slices.wrappedBuffer("a++".getBytes(StandardCharsets.UTF_8)), 0);
        assertThat(plusPlus.regexp().op()).isEqualTo(RegexpOp.PLUS);
        assertThat(plusPlus.regexp().child(0).op()).isEqualTo(RegexpOp.LITERAL);
    }

    @Test
    public void testAlternationPrefixFactoring()
    {
        // hello|help should factor to hel(lo|p)
        // After parsing, we should see a CONCAT with "hel" prefix and alternation suffix
        ParseResult result = RegexpParser.parse(Slices.wrappedBuffer("hello|help".getBytes(StandardCharsets.UTF_8)), 0);

        Regexp re = result.regexp();
        // Should be a CONCAT of "hel" and alternation of "lo|p"
        assertThat(re.op()).isEqualTo(RegexpOp.CONCAT);
        assertThat(re.children().size()).isEqualTo(2);

        // First part should be "hel"
        Regexp prefix = re.child(0);
        assertThat(prefix.op()).isEqualTo(RegexpOp.LITERAL_STRING);
        assertThat(prefix.runes()).containsExactly('h', 'e', 'l');

        // Second part should be an alternation of "lo" and "p"
        Regexp alt = re.child(1);
        assertThat(alt.op()).isEqualTo(RegexpOp.ALTERNATE);
    }

    @Test
    public void testPosixAsciiCharClass()
    {
        // Test [[:ascii:]] matches bytes 0-127 (ASCII range)
        ParseResult result = RegexpParser.parse(Slices.wrappedBuffer("[[:ascii:]]".getBytes(StandardCharsets.UTF_8)), 0);

        Regexp re = result.regexp();
        assertThat(re.op()).isEqualTo(RegexpOp.CHAR_CLASS);

        // Verify ASCII bytes are included
        assertThat(re.charClass().contains(0)).isTrue();      // NUL
        assertThat(re.charClass().contains(0x20)).isTrue();   // space
        assertThat(re.charClass().contains('A')).isTrue();    // 0x41
        assertThat(re.charClass().contains('z')).isTrue();    // 0x7A
        assertThat(re.charClass().contains(0x7F)).isTrue();   // DEL

        // Verify non-ASCII bytes are excluded
        assertThat(re.charClass().contains(0x80)).isFalse();
        assertThat(re.charClass().contains(0xFF)).isFalse();
    }

    // These inputs expose repeated copying through allocation, so a bound on the bytes a parse
    // allocates per pattern byte catches a parse that has become superlinear without timing it.
    private static void assertParseAllocationPerByte(String pattern, int maxBytesPerPatternByte, Frontend... frontends)
    {
        Slice slice = Slices.utf8Slice(pattern);
        String description = pattern.substring(0, Math.min(pattern.length(), 40));
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        for (Frontend frontend : frontends) {
            long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
            frontend.parse(slice);
            long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
            assertThat(allocatedBytes).as("%s %s", frontend, description).isLessThan((long) maxBytesPerPatternByte * slice.length());
        }
    }

    private static void assertParsesInEveryFrontend(String pattern)
    {
        Slice slice = Slices.utf8Slice(pattern);
        String description = pattern.substring(0, Math.min(pattern.length(), 40));
        for (Frontend frontend : Frontend.values()) {
            assertThatCode(() -> frontend.parse(slice)).as("%s %s", frontend, description).doesNotThrowAnyException();
        }
    }

    private enum Frontend
    {
        RE2 {
            @Override
            Regexp parse(Slice pattern)
            {
                return RegexpParser.parse(pattern, Regexp.LIKE_PERL).regexp();
            }
        },
        TRINO {
            @Override
            Regexp parse(Slice pattern)
            {
                return TrinoRegexpParser.parse(pattern, Regexp.LIKE_PERL).regexp();
            }
        },
        JAVA {
            @Override
            Regexp parse(Slice pattern)
            {
                return JavaRegexpParser.parse(pattern, Regexp.LIKE_PERL).regexp();
            }
        },
        // With the nesting limit lifted for deeply nested patterns.
        RUST {
            @Override
            Regexp parse(Slice pattern)
            {
                return RustRegexpParser.parse(pattern, RustRegexp.Options.defaults().syntaxFlags(), Integer.MAX_VALUE).regexp();
            }
        };

        abstract Regexp parse(Slice pattern);
    }

    // Runes no two of which are adjacent, so each is its own range in a class.
    private static String separateRunes(int first, int step, int count)
    {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < count; index++) {
            builder.appendCodePoint(first + index * step);
        }
        return builder.toString();
    }
}
