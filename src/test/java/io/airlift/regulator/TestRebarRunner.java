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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRebarRunner
{
    @Test
    void testCompile()
            throws Exception
    {
        assertCounts(run("compile", bytes("a+"), true, bytes("baaa ca")), 2, 2);
        // A measurement that max-time cuts short still takes the minimum samples, and max-iters 0
        // takes none.
        assertCounts(run(input("compile", bytes("a+"), true, bytes("baaa ca"), 10, 0)), 2, 2, 2);
        assertCounts(run(input("compile", bytes("a+"), true, bytes("baaa ca"), 0, 0)));
        // With no warm-up and no samples, the settle run still compiles, so an invalid pattern fails.
        assertThatThrownBy(() -> run(input("test", "compile", bytes("("), true, bytes("a"), 0, Long.MAX_VALUE, 0, Long.MAX_VALUE)))
                .isInstanceOf(RegexpParseException.class);
    }

    @Test
    void testCount()
            throws Exception
    {
        assertCounts(run("count", bytes("a+"), true, bytes("baaa ca")), 2, 2);
        // max-time 0 has passed after every sample, and Long.MAX_VALUE never passes, so these
        // depend only on max-iters and the minimum of three samples.
        assertCounts(run(input("count", bytes("a+"), true, bytes("baaa ca"), 10, 0)), 2, 2, 2);
        assertCounts(run(input("count", bytes("a+"), true, bytes("baaa ca"), 2, 0)), 2, 2);
        assertCounts(run(input("count", bytes("a+"), true, bytes("baaa ca"), 0, 0)));
        assertCounts(run(input("count", bytes("a+"), true, bytes("baaa ca"), 5, Long.MAX_VALUE)), 2, 2, 2, 2, 2);
    }

    @Test
    void testSettleRunFollowsWarmup()
    {
        // Each call returns its call number, so the first sample's count shows how many untimed
        // calls came before it: the warm-up's, then exactly one settle call, whatever the limits.
        assertThat(measuredCalls(2, Long.MAX_VALUE)).containsExactly(4L, 5L, 6L);
        assertThat(measuredCalls(5, 0)).containsExactly(3L, 4L, 5L);
        assertThat(measuredCalls(0, Long.MAX_VALUE)).containsExactly(2L, 3L, 4L);
    }

    @Test
    void testCountDoesNotComputeMatchStarts()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("a+b"));

        assertThat(RebarRunner.countMatches(pattern, Slices.utf8Slice("aaab ab"))).isEqualTo(2);
        assertThat(pattern.isReverseProgramComputed()).isFalse();
    }

    @Test
    void testCountUsesSpecializedCounter()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("\\p{L}{8,13}"));

        assertThat(RebarRunner.countMatches(pattern, Slices.utf8Slice("abcdefgh abcdefghijk 123"))).isEqualTo(2);
        assertThat(pattern.isBoundedCharacterClassCounterComputed()).isTrue();
        assertThat(pattern.isReverseProgramComputed()).isFalse();
    }

    @Test
    void testCountSpans()
            throws Exception
    {
        assertCounts(run("count-spans", bytes("a+"), true, bytes("baaa ca")), 4, 4);
    }

    @Test
    void testCountCaptures()
            throws Exception
    {
        assertCounts(run("count-captures", bytes("(a)(b)?"), true, bytes("a ab")), 5, 5);
    }

    @Test
    void testGrep()
            throws Exception
    {
        assertCounts(run("grep", bytes("^a"), true, bytes("a\nba\r\na2\n")), 2, 2);
    }

    @Test
    void testGrepCaptures()
            throws Exception
    {
        assertCounts(run("grep-captures", bytes("(a)"), true, bytes("a\nba\n")), 4, 4);
    }

    @Test
    void testGrepSplitsLinesAtEveryWordOffset()
            throws Exception
    {
        // The runner searches for each terminator two eight-byte words at a time and the last
        // bytes of the haystack one at a time. Lines of 0 to 40 bytes, each followed by a second
        // line of 0x0B alone or 0x0B and sixteen 'b', put the terminator at every offset 0 to 15 of
        // the two-word step and 0 to 13 of the byte-at-a-time tail, with a "\r\n" straddling each
        // word boundary. The lines hold 0x8A and 0xFF, which share bits with '\n', and each
        // terminator is followed by 0x0B, the byte a word search's borrow can falsely mark after a
        // '\n'. None may end a line.
        byte[] highBitBytes = {'a', (byte) 0x8A, (byte) 0xFF};
        for (int length = 0; length <= 40; length++) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int index = 0; index < length; index++) {
                line.write(highBitBytes[index % highBitBytes.length]);
            }
            for (String terminator : List.of("\n", "\r\n")) {
                for (int fillerLength : new int[] {0, 16}) {
                    ByteArrayOutputStream haystack = new ByteArrayOutputStream();
                    haystack.writeBytes(line.toByteArray());
                    haystack.writeBytes(bytes(terminator));
                    haystack.write(0x0B);
                    haystack.writeBytes(bytes("b".repeat(fillerLength)));
                    byte[] input = haystack.toByteArray();

                    // The empty pattern matches every line, so the count is the number of engine calls.
                    assertCounts(run("grep", bytes(""), false, input), 2, 2);
                    assertCounts(run("grep", bytes("^[a\\x8A\\xFF]{" + length + "}$"), false, input), 1, 1);
                    assertCounts(run("grep", bytes("^\\x0Bb*$"), false, input), 1, 1);
                    assertCounts(run("grep-captures", bytes("^[a\\x8A\\xFF]{" + length + "}$|^(\\x0Bb*)$"), false, input), 3, 3);
                }
            }
        }
    }

    @Test
    void testGrepCountsEveryLine()
            throws Exception
    {
        assertCounts(run("grep", bytes(""), false, bytes("a")), 1, 1);
        assertCounts(run("grep", bytes(""), false, bytes("a\nb")), 2, 2);
        assertCounts(run("grep", bytes(""), false, bytes("\n\n\na\n\n")), 5, 5);
        assertCounts(run("grep", bytes("^$"), false, bytes("\n\n\na\n\n")), 4, 4);
        // Sixteen newlines put several in one two-word step, so the search must pick the earliest.
        assertCounts(run("grep", bytes("^$"), false, bytes("\n".repeat(16))), 16, 16);
        assertCounts(run("grep", bytes("^a\rb$"), false, bytes("a\rb\r\n")), 1, 1);
        // Only a "\r\n" terminator loses its '\r'; a final line without a '\n' keeps it.
        assertCounts(run("grep", bytes("\r$"), false, bytes("a\r\nb\r")), 1, 1);
        assertCounts(run("grep-captures", bytes("(\r)$"), false, bytes("a\r\nb\r")), 2, 2);
        // Lines longer than a two-word step make later lines' searches enter the two-word loop from a nonzero start.
        String longLines = "0123456789abcdefghij\r\n0123456789abcdefghijklmnopq\n0123456789\n";
        assertCounts(run("grep", bytes(""), false, bytes(longLines.repeat(3))), 9, 9);
        assertCounts(run("grep", bytes("^[0-9a-z]{27}$"), false, bytes(longLines.repeat(3))), 3, 3);
    }

    @Test
    void testGrepStopsAtTheEndOfTheBackingArray()
    {
        // A haystack that fills its backing array has no byte after it to read. Lengths 1 and 17
        // end in the byte-at-a-time tail, before and after a two-word step.
        for (int length : new int[] {1, 17}) {
            Slice haystack = Slices.wrappedBuffer(bytes("a".repeat(length)));
            RegexpMatcher matcher = Re2.compile(Slices.utf8Slice("(a)")).matcher(haystack);
            assertThat(RebarRunner.grepCapturesResult(matcher, haystack, haystack.byteArray(), haystack.byteArrayOffset())).isEqualTo(2L * length);
        }
    }

    @Test
    void testLatin1Haystack()
            throws Exception
    {
        assertCounts(run("count", bytes("\\xFF"), false, new byte[] {(byte) 0xFF}), 1, 1);
    }

    @Test
    void testMatchModelsRequestOnlyRequiredCaptures()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("(a)(b)?"));
        Slice haystack = Slices.utf8Slice("a ab");

        assertThat(RebarRunner.MatchMeasurement.COUNT.createMatcher(pattern, haystack).groupCount()).isZero();
        assertThat(RebarRunner.MatchMeasurement.SPAN_LENGTH.createMatcher(pattern, haystack).groupCount()).isZero();
        assertThat(RebarRunner.MatchMeasurement.CAPTURES.createMatcher(pattern, haystack).groupCount()).isEqualTo(2);
    }

    @Test
    void testManifest()
            throws Exception
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RebarRunner.writeManifest(
                new ByteArrayInputStream(input("count", bytes("a+"), true, bytes("baaa ca"))),
                output);

        List<String> fields = parseCsv(output.toString(StandardCharsets.UTF_8).strip());
        assertThat(fields)
                .hasSize(28)
                .containsExactly(
                        "test",
                        "count",
                        "false",
                        "true",
                        "2",
                        "85e200cddd8f0561cb88c3dcdf9baf7fe1b363679c1749ed58b8aad551e9a233",
                        "7",
                        "42be0b25b4655c360bb29b18997d5186e238ca5aa8be80035c8d17bf48f1b93f",
                        "2",
                        "match-count",
                        Boolean.toString(Dfa.nativeAccessEnabled()),
                        "false",
                        "false",
                        "true",
                        "true",
                        "FIRST_MATCH",
                        fields.get(16),
                        fields.get(17),
                        fields.get(18),
                        fields.get(19),
                        "0",
                        "false",
                        "",
                        "0",
                        "0",
                        "0",
                        "0",
                        "0");
        assertThat(Long.parseLong(fields.get(16))).isNotNegative();
        assertThat(Integer.parseInt(fields.get(17))).isNotNegative();
        assertThat(Long.parseLong(fields.get(18))).isNotNegative();
        assertThat(Integer.parseInt(fields.get(19))).isNotNegative();
    }

    @Test
    void testRebarBenchmarkRunsAtItsListedBudget()
    {
        // Rebar's input names no budget, so a benchmark the budget list names compiles at its RE2
        // budget, and any other keeps no budget of its own, which is Regulator's default.
        Function<String, RebarRunner.Benchmark> rebar = name -> RebarRunner.Benchmark.readRebar(
                input(name, "count", bytes("a"), true, bytes("a"), 2, 1_000_000_000, 1, 1_000_000_000));
        long budget = RebarRunner.memoryBudget("unicode/overlapping-words/english", "re2");
        assertThat(budget).isPositive();
        assertThat(rebar.apply("unicode/overlapping-words/english").maxMemory()).isEqualTo(budget);
        assertThat(rebar.apply("unicode/overlapping-words/ascii").maxMemory()).isZero();
        // A budget applies only in its languages: Trino compiles english-10 as a literal set. The
        // compile sibling takes its row's budget.
        assertThat(RebarRunner.memoryBudget("dictionary/search/english-10", "re2")).isPositive();
        assertThat(RebarRunner.memoryBudget("dictionary/search/english-10", "trino")).isZero();
        assertThat(RebarRunner.memoryBudget("dictionary/compile/english-10", "rust"))
                .isEqualTo(RebarRunner.memoryBudget("dictionary/search/english-10", "rust"));
    }

    @Test
    void testMemoryBudgetListParsesStrictly()
    {
        String header = "# A comment\nrow_id\tlanguages\tbudget_mib\n";
        assertThat(RebarRunner.parseMemoryBudgets(header + "a\tre2,rust\t72\n# Another\nb\tjava\t8796093022207\n"))
                .isEqualTo(Map.of("a", Map.of("re2", 72L << 20, "rust", 72L << 20), "b", Map.of("java", 8796093022207L << 20)));
        // The same cases as mappings.py's loader test.
        for (String rows : List.of(
                "a\tre2\t96\n\nb\tre2\t96\n",
                "a\tre2\t96\na\tjava\t96\n",
                "a\tgo\t96\n",
                "a\tre2,re2\t96\n",
                "a\t\t96\n",
                "a\tre2\n",
                "a\tre2\t9_6\n",
                "a\tre2\t 96\n",
                "a\tre2\t96 \n",
                "a\tre2\t0\n",
                "a\tre2\t096\n",
                "a\tre2\t17592186044416\n",
                "a\tre2\t8796093022208\n",
                "a\tre2\t96\r\n")) {
            assertThatThrownBy(() -> RebarRunner.parseMemoryBudgets(header + rows)).as(rows)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("memory budget list line ");
        }
        assertThatThrownBy(() -> RebarRunner.parseMemoryBudgets("# A comment\na\tre2\t96\n"))
                .hasMessageContaining("expected the header row");
        assertThatThrownBy(() -> RebarRunner.parseMemoryBudgets("# A comment\n"))
                .hasMessage("memory budget list has no header row");
    }

    @Test
    void testRebarRunnerVersionPinsTheBudgetList()
            throws Exception
    {
        byte[] list;
        try (InputStream input = TestRebarRunner.class.getResourceAsStream("/io/airlift/regulator/benchmark-memory-budgets.tsv")) {
            assertThat(input).isNotNull();
            list = input.readAllBytes();
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(list)))
                .as("the memory budget list changed: copy it to Rebar's engines/regulator, bump its runner version (now %s), "
                        + "then update this digest and version in RebarRunner", RebarRunner.REBAR_RUNNER_VERSION)
                .isEqualTo(RebarRunner.MEMORY_BUDGETS_SHA256);
    }

    /// Returns the counts of three samples of an operation that returns its call number.
    private static List<Long> measuredCalls(long maximumWarmupIterations, long maximumWarmupTimeNanos)
    {
        RebarRunner.Benchmark benchmark = RebarRunner.Benchmark.read(
                input("test", "count", bytes("a"), true, bytes("a"), 3, 1_000_000_000, maximumWarmupIterations, maximumWarmupTimeNanos));
        long[] calls = new long[1];
        RebarRunner.Samples samples = RebarRunner.benchmarkOperation(benchmark, () -> ++calls[0]);

        List<Long> counts = new ArrayList<>();
        for (int index = 0; index < samples.size(); index++) {
            counts.add(samples.count(index));
        }
        return counts;
    }

    private static List<String> run(String model, byte[] pattern, boolean unicode, byte[] haystack)
            throws Exception
    {
        return run(input(model, pattern, unicode, haystack));
    }

    private static List<String> run(byte[] input)
            throws Exception
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RebarRunner.run(new ByteArrayInputStream(input), output);
        return output.toString(StandardCharsets.US_ASCII).lines().toList();
    }

    private static byte[] input(String model, byte[] pattern, boolean unicode, byte[] haystack)
    {
        return input(model, pattern, unicode, haystack, 2, 1_000_000_000);
    }

    private static byte[] input(String model, byte[] pattern, boolean unicode, byte[] haystack, long maximumIterations, long maximumTimeNanos)
    {
        return input("test", model, pattern, unicode, haystack, maximumIterations, maximumTimeNanos, 1, 1_000_000_000);
    }

    private static byte[] input(
            String name,
            String model,
            byte[] pattern,
            boolean unicode,
            byte[] haystack,
            long maximumIterations,
            long maximumTimeNanos,
            long maximumWarmupIterations,
            long maximumWarmupTimeNanos)
    {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        write(input, "name", bytes(name));
        write(input, "model", bytes(model));
        write(input, "case-insensitive", bytes("false"));
        write(input, "unicode", bytes(Boolean.toString(unicode)));
        write(input, "max-iters", bytes(Long.toString(maximumIterations)));
        write(input, "max-warmup-iters", bytes(Long.toString(maximumWarmupIterations)));
        write(input, "max-time", bytes(Long.toString(maximumTimeNanos)));
        write(input, "max-warmup-time", bytes(Long.toString(maximumWarmupTimeNanos)));
        write(input, "pattern", pattern);
        write(input, "haystack", haystack);
        return input.toByteArray();
    }

    private static List<String> parseCsv(String line)
    {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                }
                else {
                    quoted = !quoted;
                }
            }
            else if (character == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            }
            else {
                field.append(character);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static void assertCounts(List<String> samples, long... expectedCounts)
    {
        assertThat(samples)
                .hasSize(expectedCounts.length);
        for (int index = 0; index < expectedCounts.length; index++) {
            String[] fields = samples.get(index).split(",");
            // A sample of a few bytes can finish within one tick of a coarse clock, so its duration may be zero.
            assertThat(Long.parseLong(fields[0])).isNotNegative();
            assertThat(Long.parseLong(fields[1])).isEqualTo(expectedCounts[index]);
        }
    }

    private static void write(ByteArrayOutputStream output, String key, byte[] value)
    {
        output.writeBytes(bytes(key + ":" + value.length + ":"));
        output.writeBytes(value);
        output.write('\n');
    }

    private static byte[] bytes(String value)
    {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
