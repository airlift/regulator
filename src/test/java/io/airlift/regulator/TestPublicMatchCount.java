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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

public class TestPublicMatchCount
{
    @Test
    public void testShortSingleByteBooleanAndCountDoNotBuildDfa()
    {
        for (String expression : List.of("[,;]", "[a-z]", "(?i)[ab]", "\\C")) {
            Slice input = utf8Slice("!a;💰,b?").slice(1, 8);
            Re2 pattern = Re2.compile(utf8Slice(expression));
            Re2 oracle = Re2.compile(utf8Slice(expression));
            Re2Matcher generic = oracle.genericGroupZeroMatcherForDiagnostics(input);
            long expected = 0;
            while (generic.find()) {
                expected++;
            }
            // Counts checked with the pinned native RE2 public matcher.
            assertThat(expected).isEqualTo(expression.equals("\\C") ? 8 : 2);
            assertThat(pattern.find(input)).isTrue();
            assertThat(pattern.count(input)).isEqualTo(expected);
            assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
            Re2Matcher matcher = pattern.matcher(input, 0);
            long actual = 0;
            while (matcher.find()) {
                actual++;
            }
            assertThat(actual).isEqualTo(expected);
        }
    }

    @Test
    public void testSingleByteCacheIsSharedAndBudgetedBeforeUse()
            throws Exception
    {
        TrinoRegexp trino = TrinoRegexp.compile(utf8Slice("[,;]"));
        Re2 pattern = trino.pattern();
        long reservedDfaMemory = pattern.forwardProgramForDiagnostics().dfaMemory();
        assertThat(pattern.isSingleByteMatcherComputed()).isFalse();
        pattern.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(pattern.findInto(utf8Slice("a,b;c"), null)).isTrue();
        assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNotNull();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = IntStream.range(0, 16)
                    .mapToObj(_ -> executor.submit(pattern::sharedSingleByteMatcher))
                    .toList();
            SingleByteMatcher shared = tasks.getFirst().get();
            assertThat(shared).isNotNull();
            for (var task : tasks) {
                assertThat(task.get()).isSameAs(shared);
            }
            assertThat(trino.count(utf8Slice("a,b;c"))).isEqualTo(2);
            assertThat(pattern.sharedSingleByteMatcher()).isSameAs(shared);
            assertThat(trino.isSingleByteMatcherComputed()).isTrue();
        }
        assertThat(pattern.forwardProgramForDiagnostics().dfaMemory()).isEqualTo(reservedDfaMemory);

        Re2 captures = Re2.compile(utf8Slice("([,;])"));
        assertThat(captures.matcher(utf8Slice(",")).find()).isTrue();
        assertThat(captures.isSingleByteMatcherComputed()).isFalse();

        for (String expression : List.of("a", "(a)")) {
            TrinoRegexp literal = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(literal.isSingleByteMatcherComputed()).isFalse();
            assertThat(literal.matcher(utf8Slice("a"), 0).find()).isTrue();
            assertThat(literal.isSingleByteMatcherComputed()).isTrue();
            assertThat(literal.pattern().forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        }

        Re2 smallBudget = Re2.compile(utf8Slice("[,;]"), Re2.Options.defaults().setMaxMemory(1024));
        assertThat(smallBudget.sharedSingleByteMatcher()).isNull();
        assertThat(smallBudget.count(utf8Slice("a,b;c"))).isEqualTo(2);
    }

    @Test
    public void testSingleByteEligibilityAndRegions()
    {
        for (String expression : List.of("^([ab])", "[ab]$", "\\b[ab]", "[é💰]", "(?i)k", "a?", "[ab]+")) {
            Re2 pattern = Re2.compile(utf8Slice(expression));
            assertThat(pattern.sharedSingleByteMatcher()).as(expression).isNull();
        }
        Re2 latin1 = Re2.compile(utf8Slice("."), Re2.Options.latin1());
        assertThat(latin1.sharedSingleByteMatcher()).isNotNull();
        assertThat(latin1.count(wrappedBuffer(new byte[] {(byte) 0xE9, '\n', (byte) 0xFF}))).isEqualTo(2);

        Slice input = utf8Slice("!;a,b;c,?").slice(1, 7);
        Re2Matcher matcher = Re2.compile(utf8Slice("[,;]")).matcher(input, 0).reset(input, 1, 6);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(2);
        assertThat(matcher.end()).isEqualTo(3);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(4);
        assertThat(matcher.find()).isFalse();
        assertThat(matcher.reset(utf8Slice(",")).find()).isTrue();
    }

    @Test
    public void testLongSingleByteOperationsRetainDfaAcceleration()
    {
        for (int length : List.of(63, 64, 65, 4096, 32768)) {
            for (String suffix : List.of("x", ",")) {
                Slice input = utf8Slice("!" + "x".repeat(length - 1) + suffix + "?").slice(1, length);
                Re2 pattern = Re2.compile(utf8Slice("[,;]"));
                assertThat(pattern.find(input)).isEqualTo(suffix.equals(","));
                assertThat(pattern.count(input)).isEqualTo(suffix.equals(",") ? 1 : 0);
                if (length > 64) {
                    assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNotNull();
                    assertThat(pattern.isSingleByteMatcherComputed()).isFalse();
                }
                else {
                    assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
                }
            }
        }
    }

    @Test
    public void testLiteralCountDoesNotBuildDfa()
    {
        for (String literal : List.of("a", "aa", "literal", "é", "💰")) {
            Slice input = utf8Slice("!" + literal.repeat(3) + "?");
            input = input.slice(1, input.length() - 2);
            Re2 pattern = Re2.compile(utf8Slice(literal));
            assertThat(pattern.hasExactLiteralSpan()).isTrue();
            assertThat(pattern.count(input)).isEqualTo(3);
            assertThat(pattern.count(utf8Slice(""))).isZero();
            assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        }
        Re2 overlapping = Re2.compile(utf8Slice("aa"));
        assertThat(overlapping.count(utf8Slice("aaaaa"))).isEqualTo(2);
        assertThat(overlapping.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        Re2 latin1 = Re2.compile(wrappedBuffer(new byte[] {(byte) 0xE9}), Re2.Options.latin1());
        assertThat(latin1.count(wrappedBuffer(new byte[] {(byte) 0xE9, 0, (byte) 0xE9}))).isEqualTo(2);
        assertThat(latin1.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();

        for (String literal : List.of("needle", "é", "💰")) {
            Slice input = utf8Slice("!" + "needlf".repeat(6000) + literal.repeat(2) + "?");
            input = input.slice(1, input.length() - 2);
            Re2 pattern = Re2.compile(utf8Slice(literal));
            assertThat(pattern.count(input)).isEqualTo(2);
            assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        }
    }

    @Test
    public void testMatchesIndependentJdkCounts()
    {
        for (String expression : List.of("aa", "literal", "é", "💰", "(aa)", "(?i)aa", "a+", "(a)(b)?", "a|aa", "aa|a", "^a", "a$", "\\b[a-z]+\\b", "[a-z]{2,4}", "[é💰]+")) {
            Re2 re2 = Re2.compile(utf8Slice(expression));
            JavaRegexp java = JavaRegexp.compile(utf8Slice(expression));
            Pattern jdk = Pattern.compile(expression);
            for (String text : List.of("", "aaaa", "a ab abc", "one_two three four5 five", "é💰 a é", "b")) {
                var input = utf8Slice("padding" + text + "tail").slice(7, utf8Slice(text).length());
                long expected = jdk.matcher(text).results().count();
                for (int pass = 0; pass < 2; pass++) {
                    assertThat(re2.count(input)).as("RE2 %s / %s", expression, text).isEqualTo(expected);
                    assertThat(java.count(input)).as("Java %s / %s", expression, text).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    public void testEmptyMatchFallback()
    {
        for (String expression : List.of("", "a*", "a?", "a*?", "^", "$", "\\b")) {
            Re2 re2 = Re2.compile(utf8Slice(expression));
            JavaRegexp java = JavaRegexp.compile(utf8Slice(expression));
            for (String text : List.of("", "aaa", "a b", "bb")) {
                long expected = Pattern.compile(expression).matcher(text).results().count();
                assertThat(re2.count(utf8Slice(text))).isEqualTo(expected);
                assertThat(java.count(utf8Slice(text))).isEqualTo(expected);
            }
        }
        // Empty matches advance using the frontend's existing UTF-8 matcher semantics.
        assertThat(Re2.compile(utf8Slice("")).count(utf8Slice("é💰"))).isEqualTo(3);
        assertThat(JavaRegexp.compile(utf8Slice("")).count(utf8Slice("é💰"))).isEqualTo(3);
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
        assertThatNullPointerException().isThrownBy(() -> JavaRegexp.compile(utf8Slice("a")).count(null));
    }
}
