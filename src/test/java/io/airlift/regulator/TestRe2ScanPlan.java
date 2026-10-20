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

import io.airlift.regulator.TestTrinoScanPlan.PatternWitness;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static io.airlift.regulator.TestTrinoScanPlan.disableOrdinaryEngine;
import static io.airlift.regulator.TestTrinoScanPlan.generatedSequence;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestRe2ScanPlan
{
    // RE2's classes accept these sequences although they are not valid UTF-8: overlong three- and
    // four-byte forms, an encoded surrogate, and a four-byte form above U+10FFFF.
    private static final List<byte[]> ACCEPTED_MALFORMED = List.of(bytes(0xE0, 0x80, 0x80), bytes(0xF0, 0x80, 0x80, 0x80), bytes(0xED, 0xA0, 0x80), bytes(0xF4, 0x90, 0x80, 0x80));
    // Every RE2 class rejects these: continuation bytes, overlong two-byte leads, leads above F4,
    // and truncated sequences.
    private static final List<byte[]> REJECTED_MALFORMED = List.of(
            bytes(0x80),
            bytes(0xBF),
            bytes(0xC0, 0x80),
            bytes(0xC1, 0xBF),
            bytes(0xF5, 0x80, 0x80, 0x80),
            bytes(0xFF),
            bytes(0xC2),
            bytes(0xE1, 0x80),
            bytes(0xF1, 0x80, 0x80));
    // Options that keep RE2's leftmost-first UTF-8 reading, under which patterns select plans.
    private static final List<NamedOptions> OPTIONS = List.of(
            new NamedOptions("defaults", Re2.Options.defaults()),
            new NamedOptions("case-insensitive", Re2.Options.defaults().setCaseSensitive(false)),
            new NamedOptions("never-newline", Re2.Options.defaults().setNeverNewline(true)),
            new NamedOptions("never-capture", Re2.Options.defaults().setNeverCapture(true)),
            new NamedOptions("posix one-line", Re2.Options.defaults().setPosixSyntax(true).setOneLine(true)));
    private static final List<PlanCase> PLAN_CASES = List.of(
            // Led by a run, which also finds the candidates.
            new PlanCase("([^/]+)/([^!]*)!", "ab/cé!"),
            new PlanCase("([^:]+):", "kéy:value"),
            // Ending in a captured run, whose minimum alone decides a boolean match. RE2 rejects
            // the 0x80.
            new PlanCase("^x([^/]+)", HexFormat.of().parseHex("78802f79")),
            new PlanCase("x([^/]+)", "x/xéb"),
            // A partial delimited capture, ASCII-folded when case-insensitive.
            new PlanCase("^abc([^:]+):", "ABCé:d"),
            // One two-byte character under a minimum of two characters.
            new PlanCase("^x([^/]{2,})", "xé"),
            new PlanCase("^x([^/]{2,})/", "xé/"),
            new PlanCase("x([^/]{2,})/", "xé/"),
            // An unanchored run that stops at a rejected sequence before its delimiter.
            new PlanCase("[?&]([^=]+)=END", HexFormat.of().parseHex("3f61c080623d654e64")),
            // Forked start-anchored plans.
            new PlanCase("^(a)?(b)?abcd([0-9]+)/$", "aabcd" + "1".repeat(42) + "/"),
            new PlanCase("^(a)?(b)?abcd([0-9]+)/$", "aabcd" + "1".repeat(43) + "/"),
            new PlanCase("^(a)?(b)?abcd([^/]+)/", "aabcd" + "é".repeat(21) + "/"),
            // Inputs that hand off to the ordinary engine in the middle of a search.
            new PlanCase("x([^z]+)zq", "xa".repeat(1100) + "zrxbzqxczq"),
            new PlanCase("([^/]+)/([^!]+)!x", "a/".repeat(1100) + "!y!x/b/c!x"),
            new PlanCase("[0-9]([0-9]*)zz", "1 2 3 4 5 6 7 8 9zz" + " ".repeat(256)));

    private record NamedOptions(String name, Re2.Options options) {}

    private record PlanCase(String expression, byte[] witness)
    {
        PlanCase(String expression, String witness)
        {
            this(expression, witness.getBytes(UTF_8));
        }
    }

    @Test
    public void testPublicCallsBypassTheSemanticProgram()
    {
        Re2 pattern = Re2.compile(utf8Slice("^https?://([^/]+)"));
        assertThat(pattern.usesTrinoScanPlanForDiagnostics()).isTrue();
        // Make ordinary program execution unable to succeed, so these calls pass only if they
        // run on the plan.
        disableOrdinaryEngine(pattern);
        Slice source = utf8Slice("https://höst/path");
        assertThat(pattern.find(source)).isTrue();
        assertThat(pattern.findResult(source).groupUtf8(1)).isEqualTo("höst");
        assertThat(pattern.replaceFirst(source, utf8Slice("\\1"))).isEqualTo(utf8Slice("höst/path"));
        Re2Matcher matcher = pattern.matcher(source);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isEqualTo(utf8Slice("höst"));
    }

    @Test
    public void testRouteSelection()
    {
        // These shapes select a plan: end-anchored with a tail, partial, unanchored, and their
        // ASCII-folded forms.
        for (String expression : List.of(
                "^https?://(?:www\\.)?([^/]+)/.*$",
                "(?s)^https?://(?:www\\.)?([^/]+)/.*\\z",
                "^https?://([^/]+)",
                "https?://([^/]+)/",
                "([^/]+)/",
                "(?i)^abc([^:]+):def$",
                "(?i)^abc([^:]+):",
                "(?i)content-type:([^;]+);")) {
            assertThat(Re2.compile(utf8Slice(expression)).usesTrinoScanPlanForDiagnostics()).as(expression).isTrue();
        }
        String eligible = "^https?://([^/]+)/.*$";
        assertThat(Re2.compile(utf8Slice(eligible), Re2.Options.defaults().setLongestMatch(true)).usesTrinoScanPlanForDiagnostics()).isFalse();
        assertThat(Re2.compile(utf8Slice(eligible), Re2.Options.latin1()).usesTrinoScanPlanForDiagnostics()).isFalse();
        assertThat(JavaRegexp.compile(utf8Slice(eligible)).pattern().usesTrinoScanPlanForDiagnostics()).isFalse();
        assertThat(Re2.compileWithoutScanPlanForTesting(utf8Slice(eligible), Re2.Options.defaults()).usesTrinoScanPlanForDiagnostics()).isFalse();
        // Shapes Trino excludes: lazy runs, Unicode classes, lazy tails, and nullable patterns.
        for (String expression : List.of("^([^/]+?)/$", "^([é😀]+):$", "^([^/]+)/.*?$", "^([a-z]*)$")) {
            assertThat(Re2.compile(utf8Slice(expression)).usesTrinoScanPlanForDiagnostics()).as(expression).isFalse();
        }
    }

    @Test
    public void testPlansMatchTheOrdinaryEngine()
    {
        // Hand-written shapes, each in a syntax that every option parses.
        for (NamedOptions options : OPTIONS) {
            for (PlanCase planCase : PLAN_CASES) {
                Random random = new Random(planCase.expression().hashCode());
                String description = planCase.expression() + " " + options.name();
                // Never-newline takes the newline out of a leading run, which then stops at more
                // than its delimiter, and never-capture leaves an unanchored pattern without the
                // capture its plan needs, so the ordinary engine keeps those patterns.
                int flags = options.options().parseFlags();
                boolean leadingRun = planCase.expression().startsWith("([^");
                boolean unanchored = !planCase.expression().startsWith("^");
                boolean planned = !((flags & Regexp.NEVER_NEWLINE) != 0 && leadingRun) && !((flags & Regexp.NEVER_CAPTURE) != 0 && unanchored);
                List<Slice> sources = sources(random, planCase.witness());
                assertThat(assertPlanMatchesOrdinaryEngine(description, planCase.expression(), options.options(), sources)).as(description).isEqualTo(planned);
            }
        }
        // The long cases hand off in the middle of a search: at the next candidate after the work
        // budget ends the first failed attempt, and after the eighth of eight dense failures of
        // boolean find by failure density.
        for (String expression : List.of("x([^z]+)zq", "([^/]+)/([^!]+)!x")) {
            Slice witness = witness(expression);
            assertThat(plan(expression).search(witness, 0, witness.length(), 0, false, false, null)).as(expression).isEqualTo(1);
        }
        Slice dense = witness("[0-9]([0-9]*)zz");
        assertThat(plan("[0-9]([0-9]*)zz").searchBeforeHandoff(dense, 0, dense.length(), 0, false)).isEqualTo(dense.toStringUtf8().indexOf('8') + 1);
    }

    @Test
    public void testRandomPlansMatchTheOrdinaryEngine()
    {
        int[] plansPerShape = new int[8];
        int plansWithRuns = 0;
        int plansWithDelimitedCaptures = 0;
        for (long seed : new long[] {211, 223, 227}) {
            Random random = new Random(seed);
            for (int iteration = 0; iteration < 200; iteration++) {
                PatternWitness randomSequence = generatedSequence(random, 0);
                String sequence = randomSequence.pattern();
                String text = randomSequence.text();
                String flags = List.of("", "(?s)", "(?i)").get(random.nextInt(3));
                // End-anchored, partial, compacted with a tail, unanchored, led by a run, ending
                // in a captured run when anchored and unanchored, and partial with a delimited
                // capture after the sequence.
                String[] expressions = {
                        flags + "^x(" + sequence + ")!" + (random.nextBoolean() ? "$" : "\\z"),
                        flags + "^x(" + sequence + ")",
                        flags + "^x" + sequence + "([^/]*)/.*$",
                        flags + "x(" + sequence + ")!" + (random.nextBoolean() ? "$" : ""),
                        flags + "([^:]+):(" + sequence + ")!",
                        flags + "^x(" + sequence + ")([^/]+)",
                        flags + "x(" + sequence + ")(.+)",
                        flags + "^abc" + sequence + "([^:]+):",
                };
                String[] witnesses = {
                        "x" + text + "!",
                        "x" + text,
                        "x" + text + "a/b",
                        "x" + text + "!",
                        "k:" + text + "!",
                        "x" + text + "ab",
                        "x" + text + "ab",
                        "abc" + text + "v:",
                };
                for (int shape = 0; shape < expressions.length; shape++) {
                    byte[] witness = witnesses[shape].getBytes(UTF_8);
                    if (!assertPlanMatchesOrdinaryEngine(expressions[shape], expressions[shape], Re2.Options.defaults(), sources(random, witness))) {
                        continue;
                    }
                    // Every planned pattern has a known match, not only random misses.
                    assertThat(Re2.compile(utf8Slice(expressions[shape])).find(wrappedBuffer(witness))).as(expressions[shape]).isTrue();
                    plansPerShape[shape]++;
                    String operations = plan(expressions[shape]).operationsForDiagnostics();
                    plansWithRuns += operations.contains("RE2_RUN") ? 1 : 0;
                    plansWithDelimitedCaptures += operations.contains("RE2_DELIMITED_CAPTURE") ? 1 : 0;
                }
            }
        }
        assertThat(Arrays.stream(plansPerShape).min().orElseThrow()).as("fewest plans of one shape").isGreaterThan(100);
        assertThat(plansWithRuns).as("plans with an RE2 run").isGreaterThan(1500);
        assertThat(plansWithDelimitedCaptures).as("plans with an RE2 delimited capture").isGreaterThan(150);
    }

    @Test
    public void testGoldenPatternsSelectingPlans()
    {
        // TestUpstreamPublicMatch runs these patterns on their plans against goldens from pinned
        // native RE2, malformed inputs included. The other malformed goldens, such as the bare dot, run,
        // and counted-run patterns, select no plan and check only the ordinary engine.
        TreeSet<String> planned = new TreeSet<>();
        for (GoldenJsonl.JsonObject testCase : GoldenJsonl.readObjects("io/airlift/regulator/upstream_public_match.jsonl")) {
            if (testCase.getString("encoding").equals("utf8") && !testCase.getBoolean("longest") && !testCase.getString("patternHex").equals("-")) {
                Slice pattern = wrappedBuffer(HexFormat.of().parseHex(testCase.getString("patternHex")));
                if (Re2.compile(pattern).usesTrinoScanPlanForDiagnostics()) {
                    planned.add(pattern.toStringUtf8());
                }
            }
        }
        assertThat(planned).containsExactly("(?i)^abcdef(.*)$", "([^/]+)/", "^a(?s).*$", "^a.*$", "^abcdef(.*)$", "^x([^/]+)/", "a((?s).*)", "a(.*)", "a(.*)$");
    }

    /**
     * Returns the witness alone, doubled after a lead, and without its last byte, and with each
     * malformed sequence inserted at a random position, substituted for the byte before it,
     * wrapped around the witness, and appended. Each is a view between continuation bytes, which
     * would complete a truncated sequence at its end, so a trailing run that read past the view
     * would change a result.
     */
    private static List<Slice> sources(Random random, byte[] witness)
    {
        List<byte[]> sources = new ArrayList<>();
        sources.add(witness);
        sources.add(concat("lead x".getBytes(UTF_8), witness, witness));
        sources.add(Arrays.copyOf(witness, witness.length - 1));
        for (List<byte[]> malformed : List.of(ACCEPTED_MALFORMED, REJECTED_MALFORMED)) {
            for (byte[] sequence : malformed) {
                int position = 1 + random.nextInt(witness.length);
                byte[] prefix = Arrays.copyOf(witness, position);
                byte[] suffix = Arrays.copyOfRange(witness, position, witness.length);
                sources.add(concat(prefix, sequence, suffix));
                sources.add(concat(Arrays.copyOf(prefix, position - 1), sequence, suffix));
                sources.add(concat(sequence, witness, sequence));
                sources.add(concat(witness, sequence));
            }
        }
        List<Slice> views = new ArrayList<>();
        for (byte[] source : sources) {
            DynamicSliceOutput output = new DynamicSliceOutput(source.length + 2);
            output.writeByte(0x80);
            output.writeBytes(source);
            output.writeByte(0x80);
            views.add(output.slice().slice(1, source.length));
        }
        return views;
    }

    /**
     * Compares the operations of the pattern's plan, and of its plan forced to hand off, with
     * those of the ordinary engine on each source. Returns false, comparing nothing, when the
     * pattern selects no plan.
     */
    private static boolean assertPlanMatchesOrdinaryEngine(String description, String expression, Re2.Options options, List<Slice> sources)
    {
        Slice pattern = utf8Slice(expression);
        Re2 actual = Re2.compile(pattern, options);
        if (!actual.usesTrinoScanPlanForDiagnostics()) {
            return false;
        }
        Re2 expected = Re2.compileWithoutScanPlanForTesting(pattern, options);
        Re2 handoff = Re2.compileForcingScanPlanHandoffForTesting(pattern, options);
        for (Slice source : sources) {
            String sourceDescription = description + " on " + HexFormat.of().formatHex(source.getBytes());
            List<String> expectedResults = operations(expected, source);
            assertThat(operations(actual, source)).as(sourceDescription).isEqualTo(expectedResults);
            assertThat(operations(handoff, source)).as("handoff " + sourceDescription).isEqualTo(expectedResults);
        }
        return true;
    }

    /**
     * Returns the results of the public matching operations on {@code source}: boolean and
     * capturing find, lookingAt, and matches, over the whole source and over ranges starting at
     * 0, 1, and its middle and ending at or one byte before its end, with full, short, and null
     * group buffers, count, rewrites, matcher loops over the source and a region, retaining every
     * group or a prefix of them, and a matcher's lookingAt, matches, and find from 1.
     */
    private static List<String> operations(Re2 pattern, Slice source)
    {
        List<String> results = new ArrayList<>();
        int length = source.length();
        Slice rewrite = utf8Slice(pattern.capturingGroupCount() == 0 ? "<\\0>" : "<\\1|\\0>");
        results.add(pattern.find(source) + " " + pattern.lookingAt(source) + " " + pattern.matches(source) + " " + pattern.count(source));
        // Compare bytes, since different malformed results can decode to the same string.
        results.add(HexFormat.of().formatHex(pattern.replaceFirst(source, rewrite).getBytes()));
        Re2.ReplaceAllResult replaced = pattern.replaceAll(source, rewrite);
        results.add(replaced.replacementCount() + " " + HexFormat.of().formatHex(replaced.result().getBytes()));
        Slice extracted = pattern.extract(source, rewrite);
        results.add(extracted == null ? "none" : HexFormat.of().formatHex(extracted.getBytes()));
        results.add(describe(pattern.findResult(source)) + describe(pattern.lookingAtResult(source)) + describe(pattern.matchesResult(source)));
        int[] groups = new int[2 * (pattern.capturingGroupCount() + 1)];
        results.add(pattern.findInto(source, groups) + Arrays.toString(groups));
        // A null buffer requests no positions, and a short one a prefix of the groups.
        int[] prefix = new int[2 * Math.max(1, pattern.capturingGroupCount())];
        results.add(pattern.findInto(source, null) + " " + pattern.findInto(source, prefix) + Arrays.toString(prefix) +
                pattern.lookingAtInto(source, prefix) + Arrays.toString(prefix) +
                pattern.matchesInto(source, prefix) + Arrays.toString(prefix));
        for (int start : new int[] {0, 1, length / 2}) {
            if (start > length) {
                continue;
            }
            for (int end : new int[] {length, length - 1}) {
                if (end < start) {
                    continue;
                }
                results.add(start + ".." + end + " " + pattern.find(source, start, end) + " " + pattern.lookingAt(source, start, end) + " " + pattern.matches(source, start, end));
                results.add(describe(pattern.findResult(source, start, end)) +
                        describe(pattern.lookingAtResult(source, start, end)) +
                        describe(pattern.matchesResult(source, start, end)));
                results.add(pattern.findInto(source, start, end, groups) + Arrays.toString(groups) +
                        pattern.lookingAtInto(source, start, end, groups) + Arrays.toString(groups) +
                        pattern.matchesInto(source, start, end, groups) + Arrays.toString(groups));
            }
        }
        results.add(matches(pattern.matcher(source)));
        results.add(matches(pattern.matcher(source, 0)) + matches(pattern.matcher(source, pattern.capturingGroupCount() / 2)));
        if (length > 1) {
            results.add(matches(pattern.matcher(source).reset(source, 1, length)));
        }
        Re2Matcher matcher = pattern.matcher(source);
        results.add(matcher.lookingAt() + " " + matcher.matches());
        if (length > 0 && matcher.find(1)) {
            results.add(describe(matcher.toMatchResult()));
        }
        return results;
    }

    private static String matches(Re2Matcher matcher)
    {
        StringBuilder matches = new StringBuilder();
        while (matcher.find()) {
            matches.append(describe(matcher.toMatchResult()));
        }
        return matches.toString();
    }

    private static String describe(MatchResult result)
    {
        if (result == null) {
            return "[none]";
        }
        StringBuilder description = new StringBuilder("[");
        for (int group = 0; group <= result.groupCount(); group++) {
            description.append(result.start(group)).append(':').append(result.end(group)).append(' ');
        }
        return description.append(']').toString();
    }

    private static TrinoScanPlan plan(String expression)
    {
        ParseResult parsed = RegexpParser.parse(utf8Slice(expression), Re2.Options.defaults().parseFlags());
        return TrinoScanPlan.analyze(parsed.regexp(), parsed.capturingGroupCount(), TrinoScanPlan.Language.RE2, false, parsed.caseFolding(), ByteFrequencies.uniform());
    }

    private static Slice witness(String expression)
    {
        return wrappedBuffer(PLAN_CASES.stream().filter(planCase -> planCase.expression().equals(expression)).findFirst().orElseThrow().witness());
    }

    private static byte[] concat(byte[]... parts)
    {
        DynamicSliceOutput output = new DynamicSliceOutput(16);
        for (byte[] part : parts) {
            output.writeBytes(part);
        }
        return output.slice().getBytes();
    }

    private static byte[] bytes(int... values)
    {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }
}
