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
import java.util.List;
import java.util.function.LongSupplier;

import static io.airlift.regulator.TrinoLikePattern.Plan.CONTAINS;
import static io.airlift.regulator.TrinoLikePattern.Plan.LITERAL_GAPS;
import static io.airlift.regulator.TrinoLikePattern.Plan.ORDERED_LITERALS;
import static io.airlift.regulator.TrinoLikePattern.Plan.TRINO_WILDCARD;
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

        long allocatedBytes = minimumAllocatedBytes(140_000, () -> {
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
            return matchCount;
        });

        assertThat(allocatedBytes / 10_000).isZero();
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

        long allocatedBytes = minimumAllocatedBytes(0, () -> {
            int matchCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                if (pattern.find(source)) {
                    matchCount++;
                }
            }
            return matchCount;
        });

        assertThat(allocatedBytes / 10_000).isZero();
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

        long allocatedBytes = minimumAllocatedBytes(30_000, () -> runFinalLineBooleanOperations(pattern, absent, matched, anchored));

        assertThat(allocatedBytes / 10_000).isZero();
    }

    @Test
    public void testTrinoBeginLineCountDoesNotAllocate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        TrinoRegexp pattern = TrinoRegexp.compile(Slices.utf8Slice("(?m)^[a-z]+"));
        TrinoRegexp emptyPattern = TrinoRegexp.compile(Slices.utf8Slice("(?m)^"));
        Slice source = Slices.utf8Slice("one\ntwo\n");

        for (int iteration = 0; iteration < 20_000; iteration++) {
            assertThat(pattern.count(source)).isEqualTo(2);
            assertThat(emptyPattern.count(source)).isEqualTo(2);
        }

        long allocatedBytes = minimumAllocatedBytes(40_000, () -> {
            long matchCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                matchCount += pattern.count(source);
                matchCount += emptyPattern.count(source);
            }
            return matchCount;
        });

        assertThat(allocatedBytes / 10_000).isZero();
    }

    @Test
    public void testTrinoReplaceWithoutMatchAllocatesNoTemplate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        TrinoRegexp pattern = TrinoRegexp.compile(Slices.utf8Slice("qqq"));
        Slice source = Slices.utf8Slice("The quick brown fox jumps over the lazy dog");
        Slice replacement = Slices.utf8Slice("<$0>");
        Slice malformedReplacement = Slices.utf8Slice("<$");

        for (int iteration = 0; iteration < 20_000; iteration++) {
            assertThat(pattern.replace(source, replacement)).isSameAs(source);
            assertThat(pattern.replace(source, malformedReplacement)).isSameAs(source);
        }

        long allocatedBytes = minimumAllocatedBytes(20_000, () -> {
            int unchangedCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                unchangedCount += pattern.replace(source, replacement) == source ? 1 : 0;
                unchangedCount += pattern.replace(source, malformedReplacement) == source ? 1 : 0;
            }
            return unchangedCount;
        });

        assertThat(allocatedBytes / 20_000).isLessThanOrEqualTo(128);
    }

    @Test
    public void testTrinoReplaceReusesParsedTemplate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        TrinoRegexp pattern = TrinoRegexp.compile(Slices.utf8Slice("[,;]"));
        Slice source = Slices.utf8Slice("alpha,beta");
        Slice replacement = Slices.utf8Slice("-".repeat(TrinoReplacementPlan.MAXIMUM_CACHED_REPLACEMENT_LENGTH));
        long outputLength = pattern.replace(source, replacement).length();
        for (int iteration = 0; iteration < 20_000; iteration++) {
            assertThat(pattern.replace(source, replacement).length()).isEqualTo(outputLength);
        }
        assertThat(pattern.cachesReplacementForDiagnostics(replacement)).isTrue();

        long allocatedBytes = minimumAllocatedBytes(outputLength * 10_000, () -> {
            long totalLength = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                totalLength += pattern.replace(source, replacement).length();
            }
            return totalLength;
        });

        // A cached template without group references writes each match straight to the output,
        // leaving the result and a few fixed-size plan objects, well under one replacement length
        // beyond the result. A reparse also copies the replacement's literal bytes, allocates parse
        // metadata, and builds a new template, which measures above two replacement lengths beyond
        // the result.
        assertThat(allocatedBytes / 10_000).isLessThan(outputLength + 2L * replacement.length());
    }

    @Test
    public void testTrinoDenseReplaceBoundsMatchRecords()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        Slice source = Slices.utf8Slice("a".repeat(1 << 18));
        long sourceLength = source.length();
        // Geometric output growth allocates under four times the result plus the bounded records.
        // Recording every match would allocate at least eight bytes per match on top of the output.
        long recordBytes = 64 * 1024;
        assertThat(allocatedBytesPerReplace("a", source, "b")).isLessThan(sourceLength * 4 + recordBytes);
        assertThat(allocatedBytesPerReplace("a", source, "")).isLessThan(recordBytes);
        assertThat(allocatedBytesPerReplace("(a)", source, "$1$1")).isLessThan(sourceLength * 2 * 4 + recordBytes);
        assertThat(allocatedBytesPerReplace("", source, "-")).isLessThan((sourceLength * 2 + 1) * 4 + recordBytes);
    }

    @Test
    public void testTrinoDenseReplaceOutputFollowsResultLength()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        // Each match in the dense prefix is written straight to the output buffer; the final match
        // then deletes the long tail, so the result is only the prefix's unmatched bytes.
        Slice source = Slices.utf8Slice("xa".repeat(2049) + "b".repeat(1 << 20));
        TrinoRegexp pattern = TrinoRegexp.compile(Slices.utf8Slice("a|b+"));
        long resultLength = pattern.replace(source, Slices.EMPTY_SLICE).length();
        assertThat(resultLength).isEqualTo(2049);

        // Projecting the result from the source consumed so far would allocate about half the tail.
        assertThat(allocatedBytesPerReplace("a|b+", source, "")).isLessThan(resultLength * 4 + 64 * 1024);
    }

    @Test
    public void testTrinoScanPlansDoNotAllocate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        Re2 anchored = TrinoRegexp.compile(Slices.utf8Slice("^https?://(?:www\\.)?([^/]+)/.*$")).re2();
        Re2 folded = TrinoRegexp.compile(Slices.utf8Slice("(?i)^content-type: ([^;]+);.*$")).re2();
        Re2 unanchored = TrinoRegexp.compile(Slices.utf8Slice("https?://([^/]+)/")).re2();
        // Every unanchored search on this plan continues on the ordinary engine.
        Re2 handoff = TrinoRegexp.compileForcingScanPlanHandoffForTesting(Slices.utf8Slice("https?://([^/]+)/")).re2();
        for (Re2 pattern : List.of(anchored, folded, unanchored, handoff)) {
            assertThat(pattern.usesScanPlanForDiagnostics()).as(pattern.pattern().toStringUtf8()).isTrue();
        }
        assertThat(folded.usesAsciiFoldedScanExecutorForDiagnostics()).isTrue();

        Slice url = Slices.utf8Slice("https://www.example.com/index.html");
        Slice header = Slices.utf8Slice("Content-Type: text/html; charset=utf-8");
        Slice link = Slices.utf8Slice("see http://example.com/ for details");
        int[] groups = new int[4];

        for (int iteration = 0; iteration < 5; iteration++) {
            assertThat(runScanPlanOperations(anchored, folded, unanchored, handoff, url, header, link, groups)).isEqualTo(120_000);
        }

        long allocatedBytes = minimumAllocatedBytes(120_000, () -> runScanPlanOperations(anchored, folded, unanchored, handoff, url, header, link, groups));

        assertThat(groups).containsExactly(4, 23, 11, 22);
        assertThat(allocatedBytes / 10_000).isZero();
    }

    @Test
    public void testScanPlanCountDoesNotAllocate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        // Rows of the everyday keyValue and specialized queryParameterNames workloads, a plan led
        // by a whole literal, and an ASCII-folded plan, which has no generated class. The key-value
        // and folded inputs hold a match start inside a value, which a count skips only by
        // resuming at the match end.
        String keyValueExpression = "([A-Za-z_][A-Za-z0-9_]*)=([^ ]+)";
        Slice keyValueSource = Slices.utf8Slice("node=other=7 state=ready attempts=2");
        Slice querySource = Slices.utf8Slice("https://example.org/search?q=regex&lang=en&page=2");
        Slice linkSource = Slices.utf8Slice("see http://one.example/ and https://two.example/ for details");
        Slice foldedSource = Slices.utf8Slice("?ID=xid=7&name=x&id=8");
        TrinoRegexp folded = TrinoRegexp.compile(Slices.utf8Slice("(?i)id=([^&]+)"));
        assertThat(folded.re2().usesAsciiFoldedScanExecutorForDiagnostics()).isTrue();

        for (boolean generateBytecode : new boolean[] {false, true}) {
            TrinoRegexp.Options options = TrinoRegexp.Options.defaults().setGenerateBytecode(generateBytecode);
            TrinoRegexp keyValue = TrinoRegexp.compile(Slices.utf8Slice(keyValueExpression), options);
            TrinoRegexp queryParameterNames = TrinoRegexp.compile(Slices.utf8Slice("[?&]([^=]+)="), options);
            TrinoRegexp link = TrinoRegexp.compile(Slices.utf8Slice("https?://([^/]+)/"), options);
            // An RE2 pattern's generated class counts with its own copy of the count loop.
            Re2 re2KeyValue = Re2.compile(Slices.utf8Slice(keyValueExpression), Re2.Options.defaults().setGenerateBytecode(generateBytecode));
            assertThat(List.of(keyValue, queryParameterNames, link, folded)).allMatch(pattern -> pattern.re2().usesScanPlanForDiagnostics());
            assertThat(link.re2().caseSensitiveScanPlan().hasWholeLiteralCandidates()).isTrue();
            assertThat(List.of(keyValue, queryParameterNames, link)).allMatch(pattern -> pattern.usesGeneratedBytecode() == generateBytecode);
            assertThat(re2KeyValue.usesScanPlanForDiagnostics()).isTrue();
            assertThat(re2KeyValue.usesGeneratedBytecode()).isEqualTo(generateBytecode);
            LongSupplier counts = () -> keyValue.count(keyValueSource) + queryParameterNames.count(querySource) + link.count(linkSource) +
                    folded.count(foldedSource) + re2KeyValue.count(keyValueSource);

            for (int iteration = 0; iteration < 20_000; iteration++) {
                assertThat(counts.getAsLong()).isEqualTo(13);
            }

            long allocatedBytes = minimumAllocatedBytes(130_000, () -> {
                long matchCount = 0;
                for (int iteration = 0; iteration < 10_000; iteration++) {
                    matchCount += counts.getAsLong();
                }
                return matchCount;
            });

            assertThat(allocatedBytes / 10_000).as("generateBytecode=%s", generateBytecode).isZero();
        }
    }

    @Test
    public void testScanPlanCountHandoffAllocatesOncePerCall()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        // Each of the three searches hands off to the ordinary engine, which reports the match end
        // in group storage. A count call allocates that storage once, an int[2] of 24 bytes with
        // compressed class pointers, rather than once per handoff.
        Slice expression = Slices.utf8Slice("https?://([^/]+)/");
        Slice source = Slices.utf8Slice("see http://one.example/ and https://two.example/ for details");
        for (boolean generateBytecode : new boolean[] {false, true}) {
            TrinoRegexp handoff = TrinoRegexp.compileForcingScanPlanHandoffForTesting(expression, TrinoRegexp.Options.defaults().setGenerateBytecode(generateBytecode));
            // An RE2 pattern's generated class counts with its own copy of the count loop.
            Re2 re2Handoff = Re2.compileForcingScanPlanHandoffForTesting(expression, Re2.Options.defaults().setGenerateBytecode(generateBytecode));
            assertThat(handoff.re2().usesScanPlanForDiagnostics()).isTrue();
            assertThat(handoff.re2().scanPlanCount()).isEqualTo(Re2.ScanPlanCount.SEARCH_LOOP);
            assertThat(handoff.usesGeneratedBytecode()).isEqualTo(generateBytecode);
            assertThat(re2Handoff.usesScanPlanForDiagnostics()).isTrue();
            assertThat(re2Handoff.scanPlanCount()).isEqualTo(Re2.ScanPlanCount.SEARCH_LOOP);
            assertThat(re2Handoff.usesGeneratedBytecode()).isEqualTo(generateBytecode);
            LongSupplier counts = () -> handoff.count(source) + re2Handoff.count(source);

            for (int iteration = 0; iteration < 20_000; iteration++) {
                assertThat(counts.getAsLong()).isEqualTo(4);
            }

            long allocatedBytes = minimumAllocatedBytes(40_000, () -> {
                long matchCount = 0;
                for (int iteration = 0; iteration < 10_000; iteration++) {
                    matchCount += counts.getAsLong();
                }
                return matchCount;
            });

            assertThat(allocatedBytes / 10_000).as("generateBytecode=%s", generateBytecode).isLessThanOrEqualTo(2 * 24);
        }
    }

    private static int runScanPlanOperations(Re2 anchored, Re2 folded, Re2 unanchored, Re2 handoff, Slice url, Slice header, Slice link, int[] groups)
    {
        int matchCount = 0;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            matchCount += anchored.find(url) ? 1 : 0;
            matchCount += anchored.matches(url) ? 1 : 0;
            matchCount += anchored.matchInto(url, Re2.Anchor.UNANCHORED, groups) ? 1 : 0;
            matchCount += folded.find(header) ? 1 : 0;
            matchCount += folded.matches(header) ? 1 : 0;
            matchCount += folded.matchInto(header, Re2.Anchor.UNANCHORED, groups) ? 1 : 0;
            matchCount += unanchored.find(link) ? 1 : 0;
            matchCount += !unanchored.matches(link) ? 1 : 0;
            matchCount += unanchored.matchInto(link, Re2.Anchor.UNANCHORED, groups) ? 1 : 0;
            matchCount += handoff.find(link) ? 1 : 0;
            matchCount += !handoff.matches(link) ? 1 : 0;
            matchCount += handoff.matchInto(link, Re2.Anchor.UNANCHORED, groups) ? 1 : 0;
        }
        return matchCount;
    }

    private static long allocatedBytesPerReplace(String expression, Slice source, String replacementText)
    {
        TrinoRegexp pattern = TrinoRegexp.compile(Slices.utf8Slice(expression));
        Slice replacement = Slices.utf8Slice(replacementText);
        long outputLength = pattern.replace(source, replacement).length();
        for (int iteration = 0; iteration < 20; iteration++) {
            assertThat(pattern.replace(source, replacement).length()).isEqualTo(outputLength);
        }

        long allocatedBytes = minimumAllocatedBytes(outputLength * 10, () -> {
            long totalLength = 0;
            for (int iteration = 0; iteration < 10; iteration++) {
                totalLength += pattern.replace(source, replacement).length();
            }
            return totalLength;
        });

        return allocatedBytes / 10;
    }

    /// Runs `operations` three times, checks each result, and returns the fewest bytes the
    /// current thread allocated in one run. An allocation per operation appears in every run. A
    /// one-time allocation does not: when a counter overflow requests a C2 compile, the compile
    /// broker loads the method's signature classes on the requesting thread, which can add tens of
    /// kilobytes to one run.
    private static long minimumAllocatedBytes(long expectedResult, LongSupplier operations)
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        long minimumAllocatedBytes = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
            long result = operations.getAsLong();
            long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
            assertThat(result).isEqualTo(expectedResult);
            minimumAllocatedBytes = Math.min(minimumAllocatedBytes, allocatedBytes);
        }
        return minimumAllocatedBytes;
    }

    private static int runFinalLineBooleanOperations(TrinoRegexp pattern, Slice absent, Slice matched, Slice anchored)
    {
        int matchCount = 0;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            matchCount += !pattern.contains(absent) ? 1 : 0;
            matchCount += pattern.contains(matched) ? 1 : 0;
            matchCount += pattern.re2().lookingAt(anchored) ? 1 : 0;
        }
        return matchCount;
    }

    @Test
    public void testTrinoLikePlansDoNotAllocate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        for (boolean generateBytecode : new boolean[] {false, true}) {
            TrinoLikePattern.Options options = TrinoLikePattern.Options.defaults().setGenerateBytecode(generateBytecode);
            TrinoLikePattern contains = TrinoLikePattern.compile(Slices.utf8Slice("%needle%"), options);
            TrinoLikePattern ordered = TrinoLikePattern.compile(Slices.utf8Slice("%alpha%omega%"), options);
            TrinoLikePattern gaps = TrinoLikePattern.compile(Slices.utf8Slice("%alpha_omega%"), options);
            TrinoLikePattern wildcard = TrinoLikePattern.compile(Slices.utf8Slice("%alpha%_omega%"), options);
            assertThat(List.of(contains, ordered, gaps, wildcard)).allMatch(pattern -> pattern.usesGeneratedBytecode() == generateBytecode);
            assertThat(List.of(contains, ordered, gaps, wildcard))
                    .extracting(TrinoLikePattern::planForDiagnostics)
                    .containsExactly(CONTAINS, ORDERED_LITERALS, LITERAL_GAPS, TRINO_WILDCARD);
            Slice containsInput = Slices.utf8Slice("xxneedleyy");
            Slice orderedInput = Slices.utf8Slice("xxalpha--omegayy");
            Slice gapsInput = Slices.utf8Slice("xxalpha💰omegayy");

            for (int iteration = 0; iteration < 20_000; iteration++) {
                assertThat(contains.matches(containsInput)).isTrue();
                assertThat(ordered.matches(orderedInput)).isTrue();
                assertThat(gaps.matches(gapsInput)).isTrue();
                assertThat(wildcard.matches(gapsInput)).isTrue();
            }

            long allocatedBytes = minimumAllocatedBytes(40_000, () -> {
                int matchCount = 0;
                for (int iteration = 0; iteration < 10_000; iteration++) {
                    matchCount += contains.matches(containsInput) ? 1 : 0;
                    matchCount += ordered.matches(orderedInput) ? 1 : 0;
                    matchCount += gaps.matches(gapsInput) ? 1 : 0;
                    matchCount += wildcard.matches(gapsInput) ? 1 : 0;
                }
                return matchCount;
            });

            assertThat(allocatedBytes / 10_000).as("generateBytecode=%s", generateBytecode).isZero();
        }
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

        long allocatedBytes = minimumAllocatedBytes(10_000, () -> {
            int matchCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                if (pattern.matchInto(source, Re2.Anchor.UNANCHORED, groups)) {
                    matchCount++;
                }
            }
            return matchCount;
        });

        assertThat(groups).containsExactly(16_384, 16_391, 16_384, 16_387, 16_388, 16_391);
        assertThat(allocatedBytes / 10_000).isZero();
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

        long allocatedBytes = minimumAllocatedBytes(10_000, () -> {
            int matchCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                if (pattern.matchInto(source, Re2.Anchor.UNANCHORED, groups)) {
                    matchCount++;
                }
            }
            return matchCount;
        });

        assertThat(groups).containsExactly(16, 23, 16, 19, 20, 23);
        assertThat(allocatedBytes / 10_000).isZero();
    }

    @Test
    public void testMatcherBitStateCaptureAllocatesOnlyInvocationState()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        Re2 pattern = Re2.compile(Slices.utf8Slice("(a)(b)(c)(d)(e)"));
        Slice source = Slices.utf8Slice("abcde");
        RegexpMatcher matcher = pattern.matcher(source);

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
        RegexpMatcher matcher = pattern.matcher(source);

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
