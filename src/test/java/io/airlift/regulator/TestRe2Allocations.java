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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.airlift.regulator.TestingAllocations.MAXIMUM_WINDOW_RUNS;
import static io.airlift.regulator.TestingAllocations.assertAllocatesAtMostOnceCompiled;
import static io.airlift.regulator.TestingAllocations.assertAllocatesNothingOnceCompiled;
import static io.airlift.regulator.TestingPatternShapes.shape;
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
    public void testDfaBudgetBelowMinimumDoesNotBuildDfaPerSearch()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        // At 3,072 bytes the forward DFA gets less than its minimum. At the first budget that
        // reaches the minimum, which depends on the VM's layout, it gets its minimum but not its
        // start-byte tables, so the DFA fails to initialize. Either way counting runs on the NFA.
        // Building a DFA that could never be cached allocated its work queues on every search,
        // thousands of bytes per match.
        Slice pattern = Slices.utf8Slice("(a|b)+c");
        long windowMaxMemory = 3072;
        while (!reachesFirstMatchMinimum(Re2.compile(pattern, Re2.Options.defaults().setMaxMemory(windowMaxMemory)).forwardProgramForDiagnostics())) {
            windowMaxMemory += 8;
            assertThat(windowMaxMemory).as("maxMemory reaching the DFA minimum").isLessThan(65_536);
        }
        Slice source = Slices.utf8Slice("abc ".repeat(1_000));
        for (long maxMemory : List.of(3072L, windowMaxMemory)) {
            Re2 re2 = Re2.compile(pattern, Re2.Options.defaults().setMaxMemory(maxMemory));
            TrinoRegexp trino = TrinoRegexp.compile(pattern, TrinoRegexp.Options.defaults().setMaxMemory(maxMemory));
            for (Prog program : List.of(re2.forwardProgramForDiagnostics(), trino.re2().forwardProgramForDiagnostics())) {
                assertThat(reachesFirstMatchMinimum(program)).isEqualTo(maxMemory == windowMaxMemory);
                assertThat(new Dfa.DfaInstance(program, Dfa.DfaInstance.Kind.FIRST_MATCH, program.dfaMemory() / 2).ok()).isFalse();
                if (maxMemory == 3072) {
                    // Below its minimum, the first lookup rejects the DFA before it builds the
                    // work queues a failed DFA would rebuild on every search.
                    long threadId = Thread.currentThread().threadId();
                    long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
                    Dfa.DfaInstance dfa = program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
                    long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
                    assertThat(dfa).isNull();
                    assertThat(allocatedBytes).as("first DFA lookup").isLessThan(1024);
                }
            }

            for (int iteration = 0; iteration < 200; iteration++) {
                assertThat(re2.count(source)).isEqualTo(1_000);
                assertThat(trino.count(source)).isEqualTo(1_000);
            }

            long allocatedBytes = minimumAllocatedBytes(20_000, () -> {
                long matchCount = 0;
                for (int iteration = 0; iteration < 10; iteration++) {
                    matchCount += re2.count(source);
                    matchCount += trino.count(source);
                }
                return matchCount;
            });

            assertThat(allocatedBytes / 20_000).as("maxMemory %s", maxMemory).isLessThanOrEqualTo(256);
        }
    }

    @Test
    public void testLiteralSetFindDoesNotAllocate()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        // A dictionary of more than 64 words, under a budget too small for the longest-match DFA
        // beside its literal set, finds on the set. The ordinary engine would build a DFA that
        // cannot initialize on every call and then search without it, allocating both times.
        String dictionary = IntStream.range(0, 65).mapToObj(index -> "word" + index).collect(Collectors.joining("|"));
        Re2 pattern = Re2.compile(Slices.utf8Slice(dictionary), Re2.Options.defaults().setMaxMemory(8192));
        assertThat(shape(pattern)).isEqualTo(CompiledPattern.Shape.LITERAL_ALTERNATION);
        Slice matched = Slices.utf8Slice("see word17 and word64");
        Slice absent = Slices.utf8Slice("no dictionary words here");

        for (int iteration = 0; iteration < 20_000; iteration++) {
            assertThat(pattern.find(matched)).isTrue();
            assertThat(pattern.find(absent)).isFalse();
        }

        long allocatedBytes = minimumAllocatedBytes(20_000, () -> {
            int matchCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                matchCount += pattern.find(matched) ? 1 : 0;
                matchCount += !pattern.find(absent) ? 1 : 0;
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
        assertReplaceAllocatesLessThan("a", source, "b", sourceLength * 4 + recordBytes);
        assertReplaceAllocatesLessThan("a", source, "", recordBytes);
        assertReplaceAllocatesLessThan("(a)", source, "$1$1", sourceLength * 2 * 4 + recordBytes);
        assertReplaceAllocatesLessThan("", source, "-", (sourceLength * 2 + 1) * 4 + recordBytes);
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
        assertReplaceAllocatesLessThan("a|b+", source, "", resultLength * 4 + 64 * 1024);
    }

    @Test
    public void testTrinoScanPlansDoNotAllocate()
    {
        TrinoCompiledPattern anchored = TrinoRegexp.compile(Slices.utf8Slice("^https?://(?:www\\.)?([^/]+)/.*$")).re2();
        TrinoCompiledPattern folded = TrinoRegexp.compile(Slices.utf8Slice("(?i)^content-type: ([^;]+);.*$")).re2();
        TrinoCompiledPattern unanchored = TrinoRegexp.compile(Slices.utf8Slice("https?://([^/]+)/")).re2();
        // Every unanchored search on this plan continues on the ordinary engine.
        TrinoCompiledPattern handoff = TrinoRegexp.compileForcingScanPlanHandoffForTesting(Slices.utf8Slice("https?://([^/]+)/")).re2();
        for (TrinoCompiledPattern pattern : List.of(anchored, folded, unanchored, handoff)) {
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

        assertAllocatesNothingOnceCompiled("testTrinoScanPlansDoNotAllocate", MAXIMUM_WINDOW_RUNS, 120_000, () -> runScanPlanOperations(anchored, folded, unanchored, handoff, url, header, link, groups));

        assertThat(groups).containsExactly(4, 23, 11, 22);
    }

    @Test
    public void testScanPlanCountDoesNotAllocate()
    {
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

            assertAllocatesNothingOnceCompiled("testScanPlanCountDoesNotAllocate, generateBytecode=" + generateBytecode, MAXIMUM_WINDOW_RUNS, 130_000, () -> {
                long matchCount = 0;
                for (int iteration = 0; iteration < 10_000; iteration++) {
                    matchCount += counts.getAsLong();
                }
                return matchCount;
            });
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
            assertThat(handoff.re2().scanPlanCount()).isEqualTo(CompiledPattern.ScanPlanCount.SEARCH_LOOP);
            assertThat(handoff.usesGeneratedBytecode()).isEqualTo(generateBytecode);
            assertThat(re2Handoff.usesScanPlanForDiagnostics()).isTrue();
            assertThat(re2Handoff.scanPlanCount()).isEqualTo(CompiledPattern.ScanPlanCount.SEARCH_LOOP);
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

    @Test
    public void testSuffixBoundCountAllocatesOnlyFallbackPredicate()
            throws Exception
    {
        // rebar's bounded-strings-ending-z pattern over a 1 MiB row whose only ",z" sits about 600
        // bytes in, so each count's search end comes from a reverse scan over almost the whole
        // row, and no line holds 13 commas, so the count is 0. Each count may allocate only the
        // inner literal count's fallback predicate, a lambda of 24 bytes with compressed class
        // pointers. With SVE and 16-byte vectors, a helper that took the probe vectors on the
        // branch into the reverse scan's tail added 96 more: on a row this long, C2 compiled the
        // scan before the profile of that branch had matured, left the helper out of line, and
        // boxed both vectors once per count. On 64 KiB rows the profile matured first and nothing
        // was boxed, and a JVM whose earlier tests had already compiled the scan boxed nothing
        // either, so the counts run in a child JVM.
        ProbeResult result = runSuffixBoundCountProbe();
        System.out.print(result.output());
        assertThat(result.exitCode()).as(result.output()).isZero();
        // The child takes this JVM's candidate test, so it tests the form this JVM takes.
        assertThat(result.output()).contains("SVE_128 " + VectorSupport.SVE_128);
    }

    private static ProbeResult runSuffixBoundCountProbe()
            throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        if (Dfa.nativeAccessEnabled()) {
            command.add("--enable-native-access=ALL-UNNAMED");
        }
        // The child runs at this JVM's vector width, and so takes the same candidate test.
        command.addAll(TestingJvmArguments.vectorWidthOptions());
        command.add("--add-modules");
        command.add("jdk.incubator.vector");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(SuffixBoundCountAllocationProbe.class.getName());

        Path output = Files.createTempFile("regulator-suffix-bound-probe-", ".log");
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            processBuilder.environment().remove("JDK_JAVA_OPTIONS");
            Process process = processBuilder.start();
            try {
                assertThat(process.waitFor(Duration.ofSeconds(60)))
                        .as("suffix bound probe timed out: %s", Files.readString(output))
                        .isTrue();
                return new ProbeResult(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
            }
            finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(Duration.ofSeconds(10))).as("suffix bound probe did not terminate").isTrue();
                }
            }
        }
        finally {
            Files.deleteIfExists(output);
        }
    }

    private record ProbeResult(int exitCode, String output) {}

    private static int runScanPlanOperations(TrinoCompiledPattern anchored, TrinoCompiledPattern folded, TrinoCompiledPattern unanchored, TrinoCompiledPattern handoff, Slice url, Slice header, Slice link, int[] groups)
    {
        int matchCount = 0;
        for (int iteration = 0; iteration < 10_000; iteration++) {
            matchCount += anchored.find(url) ? 1 : 0;
            matchCount += anchored.matches(url) ? 1 : 0;
            matchCount += anchored.matchInto(url, CompiledPattern.Anchor.UNANCHORED, groups) ? 1 : 0;
            matchCount += folded.find(header) ? 1 : 0;
            matchCount += folded.matches(header) ? 1 : 0;
            matchCount += folded.matchInto(header, CompiledPattern.Anchor.UNANCHORED, groups) ? 1 : 0;
            matchCount += unanchored.find(link) ? 1 : 0;
            matchCount += !unanchored.matches(link) ? 1 : 0;
            matchCount += unanchored.matchInto(link, CompiledPattern.Anchor.UNANCHORED, groups) ? 1 : 0;
            matchCount += handoff.find(link) ? 1 : 0;
            matchCount += !handoff.matches(link) ? 1 : 0;
            matchCount += handoff.matchInto(link, CompiledPattern.Anchor.UNANCHORED, groups) ? 1 : 0;
        }
        return matchCount;
    }

    private static void assertReplaceAllocatesLessThan(String expression, Slice source, String replacementText, long bytesPerReplace)
    {
        TrinoRegexp pattern = TrinoRegexp.compile(Slices.utf8Slice(expression));
        Slice replacement = Slices.utf8Slice(replacementText);
        long outputLength = pattern.replace(source, replacement).length();
        for (int iteration = 0; iteration < 20; iteration++) {
            assertThat(pattern.replace(source, replacement).length()).isEqualTo(outputLength);
        }

        assertAllocatesAtMostOnceCompiled("replace '" + expression + "' with '" + replacementText + "'", outputLength * 10, bytesPerReplace * 10 - 1, () -> {
            long totalLength = 0;
            for (int iteration = 0; iteration < 10; iteration++) {
                totalLength += pattern.replace(source, replacement).length();
            }
            return totalLength;
        });
    }

    private static boolean reachesFirstMatchMinimum(Prog program)
    {
        return program.dfaMemory() / 2 >= Dfa.DfaInstance.minimumMemory(program, Dfa.DfaInstance.Kind.FIRST_MATCH);
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
        Re2 pattern = Re2.compile(Slices.utf8Slice("([a-z]+)-([0-9]+)"));
        byte[] sourceBytes = new byte[32_768];
        Arrays.fill(sourceBytes, (byte) '.');
        byte[] match = Slices.utf8Slice("abc-123").getBytes();
        System.arraycopy(match, 0, sourceBytes, sourceBytes.length / 2, match.length);
        Slice source = Slices.wrappedBuffer(sourceBytes);
        int[] groups = new int[6];

        for (int iteration = 0; iteration < 20_000; iteration++) {
            assertThat(pattern.matchInto(source, CompiledPattern.Anchor.UNANCHORED, groups)).isTrue();
        }

        assertAllocatesNothingOnceCompiled("testCallerBufferCaptureDoesNotAllocate", MAXIMUM_WINDOW_RUNS, 10_000, () -> {
            int matchCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                if (pattern.matchInto(source, CompiledPattern.Anchor.UNANCHORED, groups)) {
                    matchCount++;
                }
            }
            return matchCount;
        });

        assertThat(groups).containsExactly(16_384, 16_391, 16_384, 16_387, 16_388, 16_391);
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
            assertThat(pattern.matchInto(source, CompiledPattern.Anchor.UNANCHORED, groups)).isTrue();
        }

        long allocatedBytes = minimumAllocatedBytes(10_000, () -> {
            int matchCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                if (pattern.matchInto(source, CompiledPattern.Anchor.UNANCHORED, groups)) {
                    matchCount++;
                }
            }
            return matchCount;
        });

        assertThat(groups).containsExactly(16, 23, 16, 19, 20, 23);
        assertThat(allocatedBytes / 10_000).isZero();
    }

    @Test
    public void testOversizedCallerBufferTracksOnlyPatternGroups()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        // The capture engines sized their state by the caller's buffer, so about 200,000 extra pairs
        // past the pattern's one group cost megabytes of capture slots on every search.
        Re2 pattern = Re2.compile(Slices.utf8Slice("((?:a|ab)*b)"));
        Slice source = Slices.utf8Slice("xxaabxx");
        int[] groups = new int[400_002];

        for (int iteration = 0; iteration < 20; iteration++) {
            assertThat(pattern.findInto(source, groups)).isTrue();
        }

        long allocatedBytes = minimumAllocatedBytes(100, () -> {
            int matchCount = 0;
            for (int iteration = 0; iteration < 100; iteration++) {
                if (pattern.findInto(source, groups)) {
                    matchCount++;
                }
            }
            return matchCount;
        });

        int[] expected = new int[groups.length];
        Arrays.fill(expected, -1);
        expected[0] = 2;
        expected[1] = 5;
        expected[2] = 2;
        expected[3] = 5;
        assertThat(groups).containsExactly(expected);
        // The ceiling covers the scratch that the stateless BitState search allocates on each call.
        assertThat(allocatedBytes / 100).isLessThanOrEqualTo(2_048);

        // The engines pick their route by the buffer's length, so a buffer past the pattern's groups
        // turned OnePass away, and each search allocated another engine's scratch. The copy of the
        // pattern's six slots takes 40 bytes.
        Re2 onePass = Re2.compile(Slices.utf8Slice("([a-z]+)-([0-9]+)"));
        assertThat(onePass.forwardProgramForDiagnostics().isOnePass()).isTrue();
        Slice onePassSource = Slices.utf8Slice("..abc-123..");
        int[] onePassGroups = new int[128];

        for (int iteration = 0; iteration < 20_000; iteration++) {
            assertThat(onePass.findInto(onePassSource, onePassGroups)).isTrue();
        }

        long onePassAllocatedBytes = minimumAllocatedBytes(10_000, () -> {
            int matchCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                if (onePass.findInto(onePassSource, onePassGroups)) {
                    matchCount++;
                }
            }
            return matchCount;
        });

        int[] onePassExpected = new int[onePassGroups.length];
        Arrays.fill(onePassExpected, -1);
        onePassExpected[0] = 2;
        onePassExpected[1] = 9;
        onePassExpected[2] = 2;
        onePassExpected[3] = 5;
        onePassExpected[4] = 6;
        onePassExpected[5] = 9;
        assertThat(onePassGroups).containsExactly(onePassExpected);
        assertThat(onePassAllocatedBytes / 10_000).isLessThanOrEqualTo(64);

        // OnePass takes at most 64 slots, so a wider program searches the caller's buffer in
        // place, and a miss leaves every slot -1.
        int sentinel = 7;
        Re2 wide = Re2.compile(Slices.utf8Slice("(a)".repeat(40)));
        int[] wideGroups = new int[100];
        Arrays.fill(wideGroups, sentinel);
        assertThat(wide.findInto(Slices.utf8Slice("a".repeat(39) + "b"), wideGroups)).isFalse();
        assertThat(wideGroups).containsOnly(-1);

        // A removed group below a surviving one keeps its slots, so the copy for 4,000 removed
        // groups before (a) took 8,004 slots, 32,032 bytes per call against a 1,768-byte forward
        // share.
        long tightForwardMemory = 2_651 - (2_651 / 3);
        Re2 belowSurviving = Re2.compile(Slices.utf8Slice("(q){0}".repeat(4_000) + "(a)"), Re2.Options.defaults().setMaxMemory(2_651));
        int[] belowSurvivingGroups = new int[8_006];
        Arrays.fill(belowSurvivingGroups, sentinel);
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        boolean belowSurvivingMatched = belowSurviving.matchesInto(Slices.EMPTY_SLICE, belowSurvivingGroups);
        long belowSurvivingAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        assertThat(belowSurvivingMatched).isFalse();
        assertThat(belowSurvivingAllocatedBytes).isLessThanOrEqualTo(tightForwardMemory);
        assertThat(belowSurvivingGroups).containsOnly(-1);

        // The share bounds the memory a search holds at once, and bounded passes allocate per
        // pass, so a match compares against a buffer of exactly the program's slots.
        Slice belowSurvivingSource = Slices.utf8Slice("a");
        int[] exactGroups = new int[8_004];
        assertThat(belowSurviving.findInto(belowSurvivingSource, exactGroups)).isTrue();
        allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        boolean exactMatched = belowSurviving.findInto(belowSurvivingSource, exactGroups);
        long exactAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        assertThat(exactMatched).isTrue();
        Arrays.fill(belowSurvivingGroups, sentinel);
        allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        belowSurvivingMatched = belowSurviving.findInto(belowSurvivingSource, belowSurvivingGroups);
        belowSurvivingAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        assertThat(belowSurvivingMatched).isTrue();
        assertThat(belowSurvivingAllocatedBytes).isLessThanOrEqualTo(exactAllocatedBytes + 64);
        int[] belowSurvivingExpected = new int[8_006];
        Arrays.fill(belowSurvivingExpected, -1);
        belowSurvivingExpected[0] = 0;
        belowSurvivingExpected[1] = 1;
        belowSurvivingExpected[8_002] = 0;
        belowSurvivingExpected[8_003] = 1;
        assertThat(belowSurvivingGroups).containsExactly(belowSurvivingExpected);

        // The copy held every declared group's slots, so 4,000 groups the simplifier removed took
        // an 8,002-slot array per call against a 1,768-byte forward share, although the program
        // tracks only group zero.
        Re2 removedGroups = Re2.compile(Slices.utf8Slice("(a){0}".repeat(4_000) + "^"), Re2.Options.defaults().setMaxMemory(2_651));
        int[] removedGroupsOffsets = new int[8_004];
        allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        boolean removedGroupsFound = removedGroups.findInto(Slices.EMPTY_SLICE, removedGroupsOffsets);
        long removedGroupsAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        assertThat(removedGroupsFound).isTrue();
        assertThat(removedGroupsAllocatedBytes).isLessThanOrEqualTo(tightForwardMemory);
        int[] removedGroupsExpected = new int[8_004];
        Arrays.fill(removedGroupsExpected, -1);
        removedGroupsExpected[0] = 0;
        removedGroupsExpected[1] = 0;
        assertThat(removedGroupsOffsets).containsExactly(removedGroupsExpected);
    }

    @Test
    public void testCaptureSearchStaysWithinForwardShare()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        // (a?) repeated 2,000 times on 2,000 a's holds about 6,000 capture threads, each with all
        // 4,002 slots: about 96 MB of slots a search, past the default budget's 64 MiB forward
        // share. At 4,000 repetitions the forward DFA's cache churns, allocating 74 MB a search on
        // its own.
        int groups = 2_000;
        Slice pattern = Slices.utf8Slice("(a?)".repeat(groups));
        Slice text = Slices.utf8Slice("a".repeat(groups));
        int[] offsets = new int[2 * groups + 2];
        offsets[1] = groups;
        for (int group = 1; group <= groups; group++) {
            offsets[2 * group] = group - 1;
            offsets[2 * group + 1] = group;
        }

        Re2 re2 = Re2.compile(pattern);
        JavaRegexp java = JavaRegexp.compile(pattern);
        RustRegexp rust = RustRegexp.compile(pattern);
        RustRegexp rustBytes = RustRegexp.compile(pattern, RustRegexp.Options.bytes());
        TrinoRegexp trino = TrinoRegexp.compile(pattern);
        List<CaptureSearch> searches = List.of(
                new CaptureSearch("RE2 matcher find", re2.forwardProgramForDiagnostics(), () -> matcherOffsets(re2.matcher(text), groups), offsets),
                new CaptureSearch("Java matcher find", java.forwardProgramForDiagnostics(), () -> matcherOffsets(java.matcher(text), groups), offsets),
                new CaptureSearch("Rust findResult", rust.forwardProgramForDiagnostics(), () -> resultOffsets(rust.findResult(text), groups), offsets),
                new CaptureSearch("Rust bytes findResult", rustBytes.forwardProgramForDiagnostics(), () -> resultOffsets(rustBytes.findResult(text), groups), offsets),
                new CaptureSearch("Trino regexp_extract", trino.re2().forwardProgramForDiagnostics(), () -> trino.extract(text, groups), Slices.utf8Slice("a")));

        long forwardMemory = Re2.Options.DEFAULT_MAX_MEMORY - (Re2.Options.DEFAULT_MAX_MEMORY / 3);
        long threadId = Thread.currentThread().threadId();
        for (CaptureSearch search : searches) {
            assertThat(search.search().get()).as(search.name()).isEqualTo(search.expected());
            long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
            Object result = search.search().get();
            long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
            assertThat(result).as(search.name()).isEqualTo(search.expected());
            // The slack covers what the call allocates beside the capture search: the matcher or
            // result, its group array, and this test's copy of the offsets.
            assertThat(allocatedBytes).as(search.name()).isLessThanOrEqualTo(forwardMemory + 64L * search.program().size());
        }
    }

    @Test
    public void testBitStateStaysWithinForwardShare()
    {
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);

        // On a's, each position pushes five BitState jobs that stay on the stack until the match
        // ends. At the 419,429 bytes the bitmap alone allowed, a fresh matcher's direct BitState
        // capture took 67 MB, past the default budget's 64 MiB forward share.
        Slice pattern = Slices.utf8Slice("(?:(a)|a)*");
        long forwardMemory = Re2.Options.DEFAULT_MAX_MEMORY - (Re2.Options.DEFAULT_MAX_MEMORY / 3);
        long threadId = Thread.currentThread().threadId();

        // An anchored search still takes the longest text BitState accepts, and allocates within
        // the share. An unanchored search takes a shorter text.
        Re2 edge = Re2.compile(pattern);
        int edgeLength = edge.forwardProgramForDiagnostics().anchoredBitStateTextMaxSize();
        RegexpMatcher edgeMatcher = edge.matcher(Slices.utf8Slice("a".repeat(edgeLength)));
        long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        boolean edgeMatched = edgeMatcher.matches();
        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        assertThat(edgeMatched).isTrue();
        assertThat(edgeMatcher.bitStateWorkspaceInitializedForDiagnostics()).isTrue();
        assertThat(edge.isReverseProgramComputed()).isFalse();
        assertThat(allocatedBytes).isLessThanOrEqualTo(forwardMemory);

        // With the default object layout, BitState's charge for the longest text it accepts under a
        // 1,579,008-byte budget is exactly the 1,052,672-byte share. A fresh program's first search
        // used to build the program's 80 bytes of capture instruction words, which the charge leaves
        // out; the charge also counts a 40-byte region view that a whole-input search never makes,
        // so the search went 40 bytes past the share. Searching a second compile of the pattern
        // first does the JVM's one-time setup for this path.
        Re2.Options exactShareOptions = Re2.Options.defaults().setMaxMemory(1_579_008);
        long exactForwardMemory = 1_579_008 - (1_579_008 / 3);
        Re2 exactShare = Re2.compile(pattern, exactShareOptions);
        int exactShareLength = exactShare.forwardProgramForDiagnostics().anchoredBitStateTextMaxSize();
        Slice exactShareText = Slices.utf8Slice("a".repeat(exactShareLength));
        assertThat(exactShare.canUseDirectBitStateCapture(exactShare.forwardProgramForDiagnostics(), exactShareLength, 2)).isTrue();
        assertThat(exactShare.canUseDirectBitStateCapture(exactShare.forwardProgramForDiagnostics(), exactShareLength + 1, 2)).isFalse();
        assertThat(Re2.compile(pattern, exactShareOptions).matcher(exactShareText).find()).isTrue();
        RegexpMatcher exactShareMatcher = exactShare.matcher(exactShareText);
        allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        boolean exactShareFound = exactShareMatcher.find();
        allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        assertThat(exactShareFound).isTrue();
        assertThat(allocatedBytes).isLessThanOrEqualTo(exactForwardMemory);

        // A longer text takes the capture NFA, which reports the same groups.
        Re2 longer = Re2.compile(pattern);
        Slice text = Slices.utf8Slice("a".repeat(419_429));
        assertThat(longer.canUseDirectBitStateCapture(longer.forwardProgramForDiagnostics(), text.length(), 2)).isFalse();
        RegexpMatcher matcher = longer.matcher(text);
        allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        int[] offsets = matcherOffsets(matcher, 1);
        allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        assertThat(offsets).containsExactly(0, 419_429, 419_428, 419_429);
        assertThat(allocatedBytes).isLessThanOrEqualTo(forwardMemory);

        // Trailing groups the simplifier removes have no CAPTURE, so BitState tracks no slot for
        // them. It gave the 2,000 removed groups here a 16,032-byte capture array, past the
        // 5,462-byte share.
        long smallForwardMemory = 8_192 - (8_192 / 3);
        Re2 removedGroups = Re2.compile(Slices.utf8Slice("(?:(a)|a)*(?:" + "(b)".repeat(2_000) + "){0}"), Re2.Options.defaults().setMaxMemory(8_192));
        assertThat(removedGroups.forwardProgramForDiagnostics().captureSlotCount()).isEqualTo(4);
        RegexpMatcher removedGroupsMatcher = removedGroups.matcher(Slices.utf8Slice("a"));
        allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        boolean removedGroupsMatched = removedGroupsMatcher.matches();
        allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        assertThat(removedGroupsMatched).isTrue();
        assertThat(allocatedBytes).isLessThanOrEqualTo(smallForwardMemory);
        assertThat(removedGroupsMatcher.group(1)).isEqualTo(Slices.utf8Slice("a"));
        assertThat(removedGroupsMatcher.group(2_001)).isNull();

        // The same check for a two-slot program at 2,651 bytes, the smallest budget the plan is
        // proven at: a buffer that holds exactly the declared groups, all removed, took an
        // 8,002-slot array against a 1,768-byte share.
        long tightForwardMemory = 2_651 - (2_651 / 3);
        Re2 allRemoved = Re2.compile(Slices.utf8Slice("(a){0}".repeat(4_000) + "^"), Re2.Options.defaults().setMaxMemory(2_651));
        assertThat(allRemoved.forwardProgramForDiagnostics().captureSlotCount()).isEqualTo(2);
        int[] allRemovedGroups = new int[8_002];
        allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
        boolean allRemovedFound = allRemoved.findInto(Slices.EMPTY_SLICE, allRemovedGroups);
        allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        assertThat(allRemovedFound).isTrue();
        assertThat(allocatedBytes).isLessThanOrEqualTo(tightForwardMemory);
        int[] expectedGroups = new int[8_002];
        Arrays.fill(expectedGroups, -1);
        expectedGroups[0] = 0;
        expectedGroups[1] = 0;
        assertThat(allRemovedGroups).containsExactly(expectedGroups);
    }

    @Test
    public void testBitStateJobStackStaysWithinMemoryModel()
    {
        // The text limits follow the memory model, whose job stack starts at the program's size,
        // from 16 to 64 jobs, and doubles. keyValue's limit stays where that model sets it, 161,318
        // bytes with compressed class pointers. Turning them off, which JDK 25 and 26 deprecate,
        // together with compressed references makes its objects larger and its limit 83,739.
        if (TestCompilerAllocations.compressedClassPointers()) {
            Re2 keyValue = Re2.compile(TestingEverydayTrinoRegexpBenchmarkInputs.create("keyValue").pattern());
            assertThat(keyValue.forwardProgramForDiagnostics().anchoredBitStateTextMaxSize()).isEqualTo(161_318);
        }

        // This program has 48 instructions and pushes up to 19 jobs per position, so a search over
        // four bytes holds at most 96 jobs, and the model's stack takes 48 and then 96. A stack that
        // doubled from sixteen to 32 before taking the 96 allocated 16 bytes more than the model
        // with the default object layout, and a public search over a four-byte region more than
        // the search's model bytes.
        String regionPattern = "(?:" + "(".repeat(5) + "(a?)*" + ")".repeat(5) + "|" + "z".repeat(22) + ")*";
        Re2 region = Re2.compile(Slices.utf8Slice(regionPattern));
        Prog regionProgram = region.forwardProgramForDiagnostics();
        assertThat(regionProgram.size()).isEqualTo(48);
        assertThat(regionProgram.bitStateJobsPerPosition()).isEqualTo(19);
        long regionSearchBytes = BitState.searchBytes(regionProgram, regionProgram.captureSlotCount(), 4);
        Slice regionText = Slices.utf8Slice("zaaaaz");
        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        threadBean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        // The fewest bytes over three fresh matchers leaves out one-time allocation, such as the
        // tables a program's first search builds.
        long regionAllocatedBytes = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            RegexpMatcher regionMatcher = region.matcher(regionText).reset(regionText, 1, 5);
            long allocatedBefore = threadBean.getThreadAllocatedBytes(threadId);
            boolean regionFound = regionMatcher.find();
            regionAllocatedBytes = Math.min(regionAllocatedBytes, threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore);
            assertThat(regionFound).isTrue();
            assertThat(regionMatcher.bitStateWorkspaceInitializedForDiagnostics()).isTrue();
        }
        assertThat(regionAllocatedBytes).isLessThanOrEqualTo(regionSearchBytes);

        // A search's stack starts at sixteen jobs, or at its maximum when that is no more than the
        // model's first capacity, and grows within that model, at every text length up to the
        // limit, for the corpus patterns and that program at three budgets.
        Set<String> patterns = new LinkedHashSet<>();
        for (String resource : List.of("/io/airlift/regulator/everyday-trino-workloads.tsv", "/io/airlift/regulator/specialized-trino-workloads.tsv")) {
            for (TestingEverydayTrinoRegexpBenchmarkInputs.Input input : TestingEverydayTrinoRegexpBenchmarkInputs.loadInputs(resource).values()) {
                patterns.add(input.pattern().toStringUtf8());
            }
        }
        for (TsvTestData.Row row : TsvTestData.load("/io/airlift/regulator/trino-function-cases.tsv", 5, 109)) {
            patterns.add(row.field(1));
        }
        patterns.addAll(List.of(".*x.*", ".*(x|y).*", "(?:(a)|a)*", "(a)".repeat(64), "(\\pL+)(\\pL*) ([0-9]+)", regionPattern));
        for (long maxMemory : new long[] {Re2.Options.DEFAULT_MAX_MEMORY, 1L << 20, 1L << 16}) {
            for (String pattern : patterns) {
                Prog program;
                try {
                    program = Re2.compile(Slices.utf8Slice(pattern), Re2.Options.defaults().setMaxMemory(maxMemory)).forwardProgramForDiagnostics();
                }
                catch (RegexpCompileMemoryLimitException _) {
                    continue;
                }
                if (!program.canBitState()) {
                    continue;
                }
                long mostOverModel = Long.MIN_VALUE;
                for (int length = 0; length <= program.anchoredBitStateTextMaxSize(); length++) {
                    mostOverModel = Math.max(mostOverModel, BitState.jobStackBytesForTesting(program, length) - BitState.modelJobStackBytes(program, length));
                }
                assertThat(mostOverModel).as("%s at %s bytes", pattern, maxMemory).isLessThanOrEqualTo(0);
            }
        }
    }

    private record CaptureSearch(String name, Prog program, Supplier<Object> search, Object expected) {}

    // Returns null when the matcher finds no match.
    private static int[] matcherOffsets(RegexpMatcher matcher, int groups)
    {
        if (!matcher.find()) {
            return null;
        }
        int[] offsets = new int[2 * groups + 2];
        for (int group = 0; group <= groups; group++) {
            offsets[2 * group] = matcher.start(group);
            offsets[2 * group + 1] = matcher.end(group);
        }
        return offsets;
    }

    private static int[] resultOffsets(MatchResult result, int groups)
    {
        if (result == null) {
            return null;
        }
        int[] offsets = new int[2 * groups + 2];
        for (int group = 0; group <= groups; group++) {
            offsets[2 * group] = result.start(group);
            offsets[2 * group + 1] = result.end(group);
        }
        return offsets;
    }

    @Test
    public void testMatcherBitStateCaptureAllocatesOnlyInvocationState()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("(a)(b)(c)(d)(e)"));
        Slice source = Slices.utf8Slice("abcde");
        RegexpMatcher matcher = pattern.matcher(source);

        for (int iteration = 0; iteration < 20_000; iteration++) {
            matcher.reset(source);
            assertThat(matcher.matches()).isTrue();
        }

        assertAllocatesAtMostOnceCompiled("testMatcherBitStateCaptureAllocatesOnlyInvocationState", MAXIMUM_WINDOW_RUNS, 10_000, 128L * 10_000, () -> {
            int matchCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                matcher.reset(source);
                if (matcher.matches()) {
                    matchCount++;
                }
            }
            return matchCount;
        });
    }

    @Test
    public void testMatcherNfaCaptureAllocatesOnlyInvocationState()
    {
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

        assertAllocatesAtMostOnceCompiled("testMatcherNfaCaptureAllocatesOnlyInvocationState", MAXIMUM_WINDOW_RUNS, 10_000, 128L * 10_000, () -> {
            int matchCount = 0;
            for (int iteration = 0; iteration < 10_000; iteration++) {
                matcher.reset(source);
                if (matcher.matches()) {
                    matchCount++;
                }
            }
            return matchCount;
        });
    }
}
