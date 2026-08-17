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

import java.lang.management.ManagementFactory;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

public class TestRe2Allocations
{
    @Test
    public void testBooleanPlansDoNotAllocate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        Re2 contains = Re2.compile(Slices.utf8Slice("(?s:.*foo.*)"));
        Re2 literal = Re2.compile(Slices.utf8Slice("foo"));
        Re2 normalizedLiteral = Re2.compile(Slices.utf8Slice("foo{1}"));
        Re2 nullable = Re2.compile(Slices.utf8Slice("a*"));
        Re2 equals = Re2.compile(Slices.utf8Slice("^foo$"));
        Re2 startsWith = Re2.compile(Slices.utf8Slice("^foo"));
        Re2 endsWith = Re2.compile(Slices.utf8Slice("foo$"));
        Re2 prefix = Re2.compile(Slices.utf8Slice("^foo(?s:.*)$"), Re2.Options.latin1());
        Re2 suffix = Re2.compile(Slices.utf8Slice("(?s:.*)foo$"), Re2.Options.latin1());
        Slice containsInput = Slices.utf8Slice("xxfoo\nyy");
        Slice absentInput = Slices.utf8Slice("xxbar\nyy");
        Slice literalInput = Slices.utf8Slice("foo");
        Slice prefixInput = Slices.utf8Slice("foo\nyy");
        Slice suffixInput = Slices.utf8Slice("xx\nfoo");
        Slice malformedSuffixInput = Slices.wrappedBuffer(new byte[] {'f', 'o', 'o', (byte) 0xFF});
        Slice malformedPrefixInput = Slices.wrappedBuffer(new byte[] {(byte) 0xFF, 'f', 'o', 'o'});

        for (int iteration = 0; iteration < 20_000; iteration++) {
            assertThat(contains.find(containsInput)).isTrue();
            assertThat(contains.find(absentInput)).isFalse();
            assertThat(literal.find(absentInput)).isFalse();
            assertThat(normalizedLiteral.find(containsInput)).isTrue();
            assertThat(nullable.find(Slices.EMPTY_SLICE)).isTrue();
            assertThat(equals.find(literalInput)).isTrue();
            assertThat(startsWith.find(prefixInput)).isTrue();
            assertThat(endsWith.find(suffixInput)).isTrue();
            assertThat(literal.lookingAt(prefixInput)).isTrue();
            assertThat(literal.matches(literalInput)).isTrue();
            assertThat(prefix.matches(prefixInput)).isTrue();
            assertThat(prefix.matches(malformedSuffixInput)).isTrue();
            assertThat(suffix.matches(suffixInput)).isTrue();
            assertThat(suffix.matches(malformedPrefixInput)).isTrue();
        }

        long threadId = Thread.currentThread().threadId();
        threadBean.getThreadAllocatedBytes(threadId);
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int matchCount = 0;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            matchCount += contains.find(containsInput) ? 1 : 0;
            matchCount += !contains.find(absentInput) ? 1 : 0;
            matchCount += !literal.find(absentInput) ? 1 : 0;
            matchCount += normalizedLiteral.find(containsInput) ? 1 : 0;
            matchCount += nullable.find(Slices.EMPTY_SLICE) ? 1 : 0;
            matchCount += equals.find(literalInput) ? 1 : 0;
            matchCount += startsWith.find(prefixInput) ? 1 : 0;
            matchCount += endsWith.find(suffixInput) ? 1 : 0;
            matchCount += literal.lookingAt(prefixInput) ? 1 : 0;
            matchCount += literal.matches(literalInput) ? 1 : 0;
            matchCount += prefix.matches(prefixInput) ? 1 : 0;
            matchCount += prefix.matches(malformedSuffixInput) ? 1 : 0;
            matchCount += suffix.matches(suffixInput) ? 1 : 0;
            matchCount += suffix.matches(malformedPrefixInput) ? 1 : 0;
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(matchCount).isEqualTo(140_000);
        assertThat(allocatedBytes).isZero();
    }

    @Test
    public void testBooleanDfaSearchDoesNotAllocate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        Re2 pattern = Re2.compile(Slices.utf8Slice("([a-z]+)-([0-9]+)"));
        Slice source = Slices.utf8Slice("................................................................");

        for (int iteration = 0; iteration < 20_000; iteration++) {
            assertThat(pattern.find(source)).isFalse();
        }

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int matchCount = 0;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            if (pattern.find(source)) {
                matchCount++;
            }
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(matchCount).isZero();
        assertThat(allocatedBytes).isZero();
    }

    @Test
    public void testTrinoFinalLineBooleanProgramDoesNotAllocate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        TrinoRegexp pattern = TrinoRegexp.compile(Slices.utf8Slice("[a-z]+$"));
        byte[] absentBytes = new byte[32_768];
        Arrays.fill(absentBytes, (byte) '1');
        absentBytes[absentBytes.length - 1] = '\n';
        Slice absent = Slices.wrappedBuffer(absentBytes);
        Slice matched = Slices.utf8Slice("123abc\n");
        Slice anchored = Slices.utf8Slice("abc\n");

        for (int iteration = 0; iteration < 5; iteration++) {
            assertThat(runFinalLineBooleanOperations(pattern, absent, matched, anchored)).isEqualTo(30_000);
        }

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int matchCount = runFinalLineBooleanOperations(pattern, absent, matched, anchored);
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(matchCount).isEqualTo(30_000);
        assertThat(allocatedBytes).isZero();
    }

    private static int runFinalLineBooleanOperations(TrinoRegexp pattern, Slice absent, Slice matched, Slice anchored)
    {
        int matchCount = 0;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            matchCount += !pattern.contains(absent) ? 1 : 0;
            matchCount += pattern.contains(matched) ? 1 : 0;
            matchCount += pattern.pattern().lookingAt(anchored) ? 1 : 0;
        }
        return matchCount;
    }

    @Test
    public void testTrinoLikePlansDoNotAllocate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        TrinoLikePattern contains = TrinoLikePattern.compile(Slices.utf8Slice("%needle%"));
        TrinoLikePattern ordered = TrinoLikePattern.compile(Slices.utf8Slice("%alpha%omega%"));
        TrinoLikePattern wildcard = TrinoLikePattern.compile(Slices.utf8Slice("%alpha_omega%"));
        Slice containsInput = Slices.utf8Slice("xxneedleyy");
        Slice orderedInput = Slices.utf8Slice("xxalpha--omegayy");
        Slice wildcardInput = Slices.utf8Slice("xxalpha💰omegayy");

        for (int iteration = 0; iteration < 20_000; iteration++) {
            assertThat(contains.matches(containsInput)).isTrue();
            assertThat(ordered.matches(orderedInput)).isTrue();
            assertThat(wildcard.matches(wildcardInput)).isTrue();
        }

        long threadId = Thread.currentThread().threadId();
        threadBean.getThreadAllocatedBytes(threadId);
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int matchCount = 0;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            matchCount += contains.matches(containsInput) ? 1 : 0;
            matchCount += ordered.matches(orderedInput) ? 1 : 0;
            matchCount += wildcard.matches(wildcardInput) ? 1 : 0;
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(matchCount).isEqualTo(30_000);
        assertThat(allocatedBytes).isZero();
    }

    @Test
    public void testCallerBufferCaptureDoesNotAllocate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        Re2 pattern = Re2.compile(Slices.utf8Slice("([a-z]+)-([0-9]+)"));
        byte[] sourceBytes = new byte[32_768];
        Arrays.fill(sourceBytes, (byte) '.');
        byte[] match = Slices.utf8Slice("abc-123").getBytes();
        System.arraycopy(match, 0, sourceBytes, sourceBytes.length / 2, match.length);
        Slice source = Slices.wrappedBuffer(sourceBytes);
        int[] groups = new int[6];

        for (int iteration = 0; iteration < 20_000; iteration++) {
            assertThat(pattern.matchInto(source, Re2.Anchor.UNANCHORED, groups)).isTrue();
        }

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int matchCount = 0;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            if (pattern.matchInto(source, Re2.Anchor.UNANCHORED, groups)) {
                matchCount++;
            }
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(matchCount).isEqualTo(10_000);
        assertThat(groups).containsExactly(16_384, 16_391, 16_384, 16_387, 16_388, 16_391);
        assertThat(allocatedBytes).isZero();
    }

    @Test
    public void testTinyCallerBufferCaptureDoesNotAllocate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        Re2 pattern = Re2.compile(Slices.utf8Slice("([a-z]+)-([0-9]+)"));
        Slice source = Slices.utf8Slice("................abc-123.........................................");
        int[] groups = new int[6];

        for (int iteration = 0; iteration < 20_000; iteration++) {
            assertThat(pattern.matchInto(source, Re2.Anchor.UNANCHORED, groups)).isTrue();
        }

        long threadId = Thread.currentThread().threadId();
        threadBean.getThreadAllocatedBytes(threadId);
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int matchCount = 0;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            if (pattern.matchInto(source, Re2.Anchor.UNANCHORED, groups)) {
                matchCount++;
            }
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(matchCount).isEqualTo(10_000);
        assertThat(groups).containsExactly(16, 23, 16, 19, 20, 23);
        assertThat(allocatedBytes).isZero();
    }

    @Test
    public void testMatcherBitStateCaptureAllocatesOnlyInvocationState()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        Re2 pattern = Re2.compile(Slices.utf8Slice("(a)(b)(c)(d)(e)"));
        Slice source = Slices.utf8Slice("abcde");
        Re2Matcher matcher = pattern.matcher(source);

        for (int iteration = 0; iteration < 20_000; iteration++) {
            matcher.reset(source);
            assertThat(matcher.matches()).isTrue();
        }

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int matchCount = 0;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            matcher.reset(source);
            if (matcher.matches()) {
                matchCount++;
            }
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(matchCount).isEqualTo(10_000);
        assertThat(allocatedBytes).isLessThanOrEqualTo(128L * 10_000);
    }

    @Test
    public void testMatcherNfaCaptureAllocatesOnlyInvocationState()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        Re2 pattern = Re2.compile(Slices.utf8Slice("(a{100})(b{100})(c{100})(d{100})(e{100})(f{100})"));
        Slice source = Slices.utf8Slice(
                "a".repeat(100) +
                        "b".repeat(100) +
                        "c".repeat(100) +
                        "d".repeat(100) +
                        "e".repeat(100) +
                        "f".repeat(100));
        Re2Matcher matcher = pattern.matcher(source);

        for (int iteration = 0; iteration < 20_000; iteration++) {
            matcher.reset(source);
            assertThat(matcher.matches()).isTrue();
        }

        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int matchCount = 0;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            matcher.reset(source);
            if (matcher.matches()) {
                matchCount++;
            }
        }
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertThat(matchCount).isEqualTo(10_000);
        assertThat(allocatedBytes).isLessThanOrEqualTo(128L * 10_000);
    }
}
