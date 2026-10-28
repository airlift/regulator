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

import io.airlift.regulator.TestRe2ScanPlan.NamedDistribution;
import io.airlift.regulator.TestScanPlan.PatternWitness;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import static io.airlift.regulator.TestRe2ScanPlan.PROBED_LEADS;
import static io.airlift.regulator.TestRe2ScanPlan.PROBE_DISTRIBUTIONS;
import static io.airlift.regulator.TestRe2ScanPlan.describe;
import static io.airlift.regulator.TestRe2ScanPlan.disableInterpretedPlan;
import static io.airlift.regulator.TestRe2ScanPlan.matches;
import static io.airlift.regulator.TestRe2ScanPlan.movedProbe;
import static io.airlift.regulator.TestRe2ScanPlan.sources;
import static io.airlift.regulator.TestScanPlan.disableOrdinaryEngine;
import static io.airlift.regulator.TestScanPlan.generatedSequence;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// Rust patterns on the scan plans that [TestRe2ScanPlan] and [TestScanPlan]
/// cover: which Rust constructs reach a plan, and that a planned pattern gives the results of the
/// same pattern compiled without its plan, on valid and malformed UTF-8. The pinned crate goldens
/// of [TestRustRegexpGolden], which run on plans by default, specify those results.
public class TestRustScanPlan
{
    // Each plan shape in Rust syntax, with a witness that it matches.
    private static final List<PlanCase> PLAN_CASES = List.of(
            // End-anchored, with a line tail whose dot excludes only LF, and with a dot-all tail.
            new PlanCase("^https?://(?:www\\.)?([^/]+)/.*$", "https://www.höst/päth"),
            new PlanCase("(?s)\\Ahttps?://([^/]+)/(.*)\\z", "http://höst/a\nb"),
            // Partial, ending in a captured run. A matcher region one character in starts at the
            // second x, where Rust's ^ still refers to the input start.
            new PlanCase("^x([^/]+)", "xxéb/c"),
            // One two-byte character under a minimum of two characters, anchored and unanchored.
            new PlanCase("^x([^/]{2,})/", "xéé/"),
            new PlanCase("x([^/]{2,})/", "xé/xéé/"),
            // Unanchored and led by a literal, with a named capture.
            new PlanCase("https?://(?:www\\.)?(?P<host>[^/]+)/", "see http://hôst/ and https://www.b/"),
            new PlanCase("x([^/]+)/", "x/xéb/xc/"),
            // Led by a run, and by a one-byte set before a delimited capture.
            new PlanCase("([^/]+)/([^!]*)!", "ab/cé!d/!"),
            new PlanCase("[?&]([^=]+)=", "?a=b&ké=v"),
            // Forked optionals, with an ASCII Perl class.
            new PlanCase("^(a)?(b)?abcd((?-u:\\d)+)/$", "aabcd12/"),
            // ASCII-folded.
            new PlanCase("(?i)^abc([^:]+):def$", "ABCé:DeF"),
            new PlanCase("(?i)content-type:([^;]+);", "Content-Type:tëxt;"),
            // Swapped greed, which makes a lazy run greedy, and a CRLF dot, which also excludes CR.
            new PlanCase("(?U)^x([^/]+?)/", "xéb/c/"),
            new PlanCase("(?R)^x(.+)$", "xéb"),
            // Unicode Perl classes, which hold some non-ASCII characters but not all, anchored
            // and unanchored.
            new PlanCase("^x(\\w+)/", "xé\u0663b/"),
            new PlanCase("^(\\w+)-(\\d+)/", "ké-\u0663\u0664/"),
            new PlanCase("id=(\\d+)&", "id=a& id=1\u0663&"),
            new PlanCase("^x(\\s+)/", "x \u2028\u3000/"),
            // Partial, so the run's minimum alone decides a boolean match, which must reject a
            // U+0085 right after the x, as testPlansMatchTheOrdinaryEngine checks.
            new PlanCase("^x(\\w+)", "xé\u0085"),
            // A fold to the Kelvin sign, and a range folded to it and the long s.
            new PlanCase("(?i)^k([^/]+)/", "\u212Aab/"),
            new PlanCase("(?i)^x([a-z]+)/", "xaſ\u212Ab/"),
            // Unanchored searches led by a set of some non-ASCII characters, whose candidates are
            // its ASCII members and lead bytes.
            new PlanCase("\\d([0-9]+)=", "\u0663=1\u0663=\u066312="),
            new PlanCase("(?i)key=([^;]+);", "kx;\u212Aey=v;"),
            new PlanCase("(?i)s([a-z]+)=", "ſ=Sſab="));
    // Suffixes appended to each witness: none, LF, CR LF, CR, and U+2028. Rust's $ matches only
    // at the input end.
    private static final List<String> LINE_ENDS = List.of("", "\n", "\r\n", "\r", "\u2028");
    // Rust's Perl classes, each with a character it matches, which join the shared atoms of
    // random plans: the Unicode classes hold some non-ASCII characters but not all, so they become
    // set runs, and their ASCII forms hold none.
    private static final List<PatternWitness> PERL_CLASSES = List.of(
            new PatternWitness("\\w", "é"),
            new PatternWitness("\\d", "\u0663"),
            new PatternWitness("\\s", "\u3000"),
            new PatternWitness("(?-u:\\w)", "a"),
            new PatternWitness("(?-u:\\d)", "1"),
            new PatternWitness("(?-u:\\s)", " "));
    // A Unicode Perl class: one outside the (?-u:...) of its ASCII form.
    private static final Pattern UNICODE_PERL_CLASS = Pattern.compile("(?<!-u:)\\\\[dsw]");
    // Case-insensitive plans whose every fold is a set of some non-ASCII characters, so they have
    // no ASCII-folded operation.
    private static final Set<String> GENERATED_FOLDS = Set.of("(?i)^k([^/]+)/", "(?i)s([a-z]+)=");
    private static final RustRegexp.Options GENERATING = RustRegexp.Options.defaults().setGenerateBytecode(true);

    private record PlanCase(String expression, String witness) {}

    private record NamedRegexp(String name, RustRegexp regexp) {}

    @Test
    public void testRouteSelection()
    {
        for (PlanCase planCase : PLAN_CASES) {
            String expression = planCase.expression();
            RustRegexp regexp = RustRegexp.compile(utf8Slice(expression));
            assertThat(regexp.pattern().usesScanPlanForDiagnostics()).as(expression).isTrue();
            // Generated bytecode covers plans with no ASCII-folded operation, and only on request.
            boolean generates = !expression.startsWith("(?i") || GENERATED_FOLDS.contains(expression);
            assertThat(RustRegexp.compile(utf8Slice(expression), GENERATING).usesGeneratedBytecode()).as(expression).isEqualTo(generates);
            assertThat(RustRegexp.compileForcingScanPlanHandoffForTesting(utf8Slice(expression), GENERATING).usesGeneratedBytecode()).as(expression).isEqualTo(generates);
            assertThat(regexp.usesGeneratedBytecode()).as(expression).isFalse();
        }
        // A plan anchors only at the input end, so multiline anchors, CRLF ones included, keep the
        // ordinary engine, as do word boundaries, Rust's start and end boundaries included,
        // reluctant runs, and an unanchored search led by a variable run of a Unicode Perl class,
        // each of whose candidates would rescan the rest of the run.
        for (String expression : List.of(
                "(?m)^x([^/]+)/",
                "(?m)x([^/]+)/$",
                "(?mR)^x([^/]+)/",
                "(?mR)x([^/]+)/$",
                "\\bx([^/]+)/",
                "(?-u:\\b)x([^/]+)/",
                "\\b{start}x([^/]+)/",
                "^x([^/]+?)/",
                "(\\d+)zz")) {
            assertThat(RustRegexp.compile(utf8Slice(expression)).pattern().usesScanPlanForDiagnostics()).as(expression).isFalse();
            assertThat(RustRegexp.compile(utf8Slice(expression), GENERATING).usesGeneratedBytecode()).as(expression).isFalse();
        }
        // Byte mode builds no scan plan, even for an ASCII pattern that builds one outside byte
        // mode. Only byte constructs carry the Latin-1 flag the analysis checks, so the analysis
        // alone would still admit an ASCII-only byte-mode pattern.
        for (String expression : List.of(PLAN_CASES.getFirst().expression(), "^x(?-u:[a-z]+)/")) {
            assertThat(RustRegexp.compile(utf8Slice(expression)).pattern().usesScanPlanForDiagnostics()).as(expression).isTrue();
            assertThat(RustRegexp.compile(utf8Slice(expression), RustRegexp.Options.bytes()).pattern().usesScanPlanForDiagnostics()).as(expression).isFalse();
            assertThat(RustRegexp.compile(utf8Slice(expression), RustRegexp.Options.bytes().setGenerateBytecode(true)).usesGeneratedBytecode()).as(expression).isFalse();
        }
        assertThat(RustRegexp.compileWithoutScanPlanForTesting(utf8Slice(PLAN_CASES.getFirst().expression()), RustRegexp.Options.defaults()).pattern().usesScanPlanForDiagnostics()).isFalse();
    }

    @Test
    public void testPublicCallsRunThePlan()
            throws ReflectiveOperationException
    {
        for (RustRegexp.Options options : List.of(RustRegexp.Options.defaults(), GENERATING)) {
            boolean generate = options.generateBytecode();
            String name = generate ? "generated" : "interpreted";
            RustRegexp anchored = RustRegexp.compile(utf8Slice("^https?://([^/]+)"), options);
            RustRegexp unanchored = RustRegexp.compile(utf8Slice("x([^/]+)/"), options);
            assertThat(anchored.usesGeneratedBytecode()).as(name).isEqualTo(generate);
            assertThat(unanchored.usesGeneratedBytecode()).as(name).isEqualTo(generate);
            // Make ordinary program execution unable to succeed, and a generated pattern's
            // interpreted plan too, so these calls pass only if they run on the plan or its class.
            for (RustRegexp regexp : List.of(anchored, unanchored)) {
                disableOrdinaryEngine(regexp.pattern());
                if (generate) {
                    disableInterpretedPlan(regexp.pattern());
                }
            }
            Slice source = utf8Slice("https://höst/path");
            int[] groups = new int[4];
            assertThat(anchored.find(source)).as(name).isTrue();
            assertThat(anchored.find(source, 0, source.length())).as(name).isTrue();
            assertThat(anchored.findResult(source).groupUtf8(1)).as(name).isEqualTo("höst");
            assertThat(anchored.findInto(source, groups)).as(name).isTrue();
            assertThat(groups).as(name).containsExactly(0, 13, 8, 13);
            assertThat(anchored.lookingAt(source)).as(name).isTrue();
            assertThat(anchored.lookingAtResult(source).groupUtf8(1)).as(name).isEqualTo("höst");
            assertThat(anchored.matches(utf8Slice("https://höst"))).as(name).isTrue();
            assertThat(anchored.count(source)).as(name).isEqualTo(1);
            RegexpMatcher matcher = anchored.matcher(source);
            assertThat(matcher.find()).as(name).isTrue();
            assertThat(matcher.group(1)).as(name).isEqualTo(utf8Slice("höst"));

            Slice unanchoredSource = utf8Slice("x/xéb/xc/");
            assertThat(unanchored.find(unanchoredSource)).as(name).isTrue();
            assertThat(unanchored.find(unanchoredSource, 1, unanchoredSource.length())).as(name).isTrue();
            assertThat(unanchored.findResult(unanchoredSource).groupUtf8(1)).as(name).isEqualTo("éb");
            assertThat(unanchored.count(unanchoredSource)).as(name).isEqualTo(2);
            assertThat(matches(unanchored.matcher(unanchoredSource))).as(name).isEqualTo("[2:7 3:6 ][7:10 8:9 ]");

            // A forced handoff continues every unanchored search on the ordinary engine before the
            // plan's first attempt, which would match here, so with that engine unable to succeed
            // it finds nothing.
            RustRegexp handoff = RustRegexp.compileForcingScanPlanHandoffForTesting(utf8Slice("x([^/]+)/"), options);
            assertThat(handoff.usesGeneratedBytecode()).as(name).isEqualTo(generate);
            disableOrdinaryEngine(handoff.pattern());
            Slice handoffSource = utf8Slice("xéb/xc/");
            assertThat(handoff.find(handoffSource)).as(name).isFalse();
            assertThat(handoff.count(handoffSource)).as(name).isZero();
        }
    }

    @Test
    public void testPlansMatchTheOrdinaryEngine()
    {
        // No JDK oracle exists for Rust, so the pattern compiled without its plan decides.
        for (PlanCase planCase : PLAN_CASES) {
            RustRegexp expected = RustRegexp.compileWithoutScanPlanForTesting(utf8Slice(planCase.expression()), RustRegexp.Options.defaults());
            assertThat(expected.find(utf8Slice(planCase.witness()))).as(planCase.expression()).isTrue();
            List<NamedRegexp> planned = planned(planCase.expression());
            for (String input : inputs(planCase.witness())) {
                Slice source = utf8Slice(input);
                List<String> expectedResults = results(expected, source);
                for (NamedRegexp regexp : planned) {
                    assertThat(results(regexp.regexp(), source)).as("%s %s on [%s]", regexp.name(), planCase.expression(), input).isEqualTo(expectedResults);
                }
            }
        }
        // A partial run's minimum rejects a U+0085 right after the x, which no input above puts
        // there.
        String partial = "^x(\\w+)";
        Slice rejected = utf8Slice("x\u0085é");
        RustRegexp expected = RustRegexp.compileWithoutScanPlanForTesting(utf8Slice(partial), RustRegexp.Options.defaults());
        assertThat(expected.find(rejected)).isFalse();
        for (NamedRegexp regexp : planned(partial)) {
            assertThat(results(regexp.regexp(), rejected)).as(regexp.name()).isEqualTo(results(expected, rejected));
        }
    }

    @Test
    public void testPlanCountsSharedMemberTableOnce()
    {
        // Both runs of \w read one member table, which the plan's size counts once. The ASCII
        // classes give the same operations without a table.
        ScanPlan unicode = RustRegexp.compile(utf8Slice("^(\\w+):(\\w+)/$")).pattern().caseSensitiveScanPlan();
        ScanPlan ascii = RustRegexp.compile(utf8Slice("^((?-u:\\w)+):((?-u:\\w)+)/$")).pattern().caseSensitiveScanPlan();
        assertThat(unicode.operationsForDiagnostics()).isEqualTo("RE2_SET_RUN,LITERAL,RE2_SET_RUN,LITERAL,END");
        assertThat(ascii.operationCount()).isEqualTo(unicode.operationCount());
        Utf8MemberTable table = unicode.step(0).run().nonAsciiMembers();
        assertThat(table).isNotNull();
        assertThat(unicode.step(2).run().nonAsciiMembers()).isSameAs(table);
        assertThat(unicode.estimatedRetainedSize() - runSizes(unicode) - (ascii.estimatedRetainedSize() - runSizes(ascii))).isEqualTo(table.estimatedRetainedSize());
    }

    private static long runSizes(ScanPlan plan)
    {
        long size = 0;
        for (int operation = 0; operation < plan.operationCount(); operation++) {
            ScanPlanRun run = plan.step(operation).run();
            if (run != null) {
                size += run.estimatedRetainedSize();
            }
        }
        return size;
    }

    @Test
    public void testPlansMatchTheOrdinaryEngineOnMalformedInput()
    {
        // Outside byte mode, results on malformed UTF-8 are unspecified, but a plan must still
        // report what the ordinary engine reports.
        for (PlanCase planCase : PLAN_CASES) {
            RustRegexp expected = RustRegexp.compileWithoutScanPlanForTesting(utf8Slice(planCase.expression()), RustRegexp.Options.defaults());
            List<NamedRegexp> planned = planned(planCase.expression());
            for (Slice source : sources(new Random(planCase.expression().hashCode()), planCase.witness().getBytes(UTF_8))) {
                List<String> expectedResults = results(expected, source);
                for (NamedRegexp regexp : planned) {
                    assertThat(results(regexp.regexp(), source)).as("%s %s on %s", regexp.name(), planCase.expression(), HexFormat.of().formatHex(source.getBytes())).isEqualTo(expectedResults);
                }
            }
        }
    }

    @Test
    public void testRandomPlansMatchTheOrdinaryEngine()
    {
        // The shapes of TestRe2ScanPlan's random plans, where Rust's $ is \z, with Rust's Perl
        // classes among the atoms. The pattern compiled without its plan reads Unicode classes
        // slowly, so there are half as many iterations.
        int[] plansPerShape = new int[9];
        int plansWithUnicodeSetRuns = 0;
        int plansWithMovedProbes = 0;
        for (long seed : new long[] {241, 251, 257}) {
            Random random = new Random(seed);
            for (int iteration = 0; iteration < 100; iteration++) {
                PatternWitness randomSequence = generatedSequence(random, 0, PERL_CLASSES);
                String sequence = randomSequence.pattern();
                String text = randomSequence.text();
                String flags = List.of("", "(?s)", "(?i)").get(random.nextInt(3));
                NamedDistribution distribution = PROBE_DISTRIBUTIONS.get(random.nextInt(PROBE_DISTRIBUTIONS.size()));
                String lead = PROBED_LEADS.get(random.nextInt(PROBED_LEADS.size()));
                String[] expressions = {
                        flags + "^x(" + sequence + ")!" + (random.nextBoolean() ? "$" : "\\z"),
                        flags + "^x(" + sequence + ")",
                        flags + "^x" + sequence + "([^/]*)/.*$",
                        flags + "x(" + sequence + ")!" + (random.nextBoolean() ? "$" : ""),
                        flags + "([^:]+):(" + sequence + ")!",
                        flags + "^x(" + sequence + ")([^/]+)",
                        flags + "x(" + sequence + ")(.+)",
                        flags + "^abc" + sequence + "([^:]+):",
                        flags + lead + "(" + sequence + ")!",
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
                        lead + text + "!",
                };
                for (int shape = 0; shape < expressions.length; shape++) {
                    String expression = expressions[shape];
                    byte[] witness = witnesses[shape].getBytes(UTF_8);
                    String description = expression + " " + distribution.name();
                    Re2 planned = assertPlanMatchesOrdinaryEngine(description, expression, distribution.frequencies(), sources(random, witness));
                    if (planned == null) {
                        continue;
                    }
                    // Every planned pattern has a known match, not only random misses.
                    assertThat(planned.find(wrappedBuffer(witness))).as(description).isTrue();
                    plansPerShape[shape]++;
                    // A Unicode Perl class supplies a set run when the plan has more set runs than
                    // the plan, if any, of the expression with each such class in its ASCII form.
                    String asciiClasses = UNICODE_PERL_CLASS.matcher(expression).replaceAll("(?-u:$0)");
                    RustRegexp.Options options = RustRegexp.Options.defaults().setByteFrequencies(distribution.frequencies());
                    plansWithUnicodeSetRuns += setRuns(planned) > setRuns(RustRegexp.compile(utf8Slice(asciiClasses), options).pattern()) ? 1 : 0;
                    plansWithMovedProbes += movedProbe(planned) ? 1 : 0;
                }
            }
        }
        assertThat(Arrays.stream(plansPerShape).min().orElseThrow()).as("fewest plans of one shape").isGreaterThan(80);
        assertThat(plansWithUnicodeSetRuns).as("plans with a set run from a Unicode Perl class").isGreaterThan(300);
        assertThat(plansWithMovedProbes).as("plans probing a leading literal past its first byte").isGreaterThan(20);
    }

    /// Compares the Rust pattern's compiles under `frequencies` as the shared overload in
    /// [TestRe2ScanPlan] does. Returns the planned pattern, or `null`, comparing nothing, when the
    /// pattern selects no plan.
    private static Re2 assertPlanMatchesOrdinaryEngine(String description, String expression, ByteFrequencies frequencies, List<Slice> sources)
    {
        Slice pattern = utf8Slice(expression);
        RustRegexp.Options options = RustRegexp.Options.defaults().setByteFrequencies(frequencies);
        RustRegexp.Options generating = RustRegexp.Options.defaults().setByteFrequencies(frequencies).setGenerateBytecode(true);
        Re2 actual = RustRegexp.compile(pattern, options).pattern();
        if (!actual.usesScanPlanForDiagnostics()) {
            return null;
        }
        TestRe2ScanPlan.assertPlanMatchesOrdinaryEngine(
                description,
                RustRegexp.compileWithoutScanPlanForTesting(pattern, options).pattern(),
                actual,
                RustRegexp.compileForcingScanPlanHandoffForTesting(pattern, options).pattern(),
                RustRegexp.compile(pattern, generating).pattern(),
                RustRegexp.compileForcingScanPlanHandoffForTesting(pattern, generating).pattern(),
                sources);
        return actual;
    }

    /// Returns the number of runs of some non-ASCII characters in the pattern's case-sensitive
    /// plan, or 0 when it has none.
    private static long setRuns(Re2 pattern)
    {
        ScanPlan plan = pattern.caseSensitiveScanPlan();
        return plan == null ? 0 : Arrays.stream(plan.operationsForDiagnostics().split(",")).filter("RE2_SET_RUN"::equals).count();
    }

    /// Returns the pattern compiled with its plan, and with its plan forced to hand every
    /// unanchored search to the ordinary engine, each with and without generated bytecode.
    private static List<NamedRegexp> planned(String expression)
    {
        Slice pattern = utf8Slice(expression);
        List<NamedRegexp> planned = List.of(
                new NamedRegexp("plan", RustRegexp.compile(pattern)),
                new NamedRegexp("handoff", RustRegexp.compileForcingScanPlanHandoffForTesting(pattern, RustRegexp.Options.defaults())),
                new NamedRegexp("generated", RustRegexp.compile(pattern, GENERATING)),
                new NamedRegexp("generated handoff", RustRegexp.compileForcingScanPlanHandoffForTesting(pattern, GENERATING)));
        for (NamedRegexp regexp : planned) {
            assertThat(regexp.regexp().pattern().usesScanPlanForDiagnostics()).as("%s %s", regexp.name(), expression).isTrue();
        }
        return planned;
    }

    /// Returns the witness with each line end appended, after a lead and doubled, and without its
    /// last character.
    private static List<String> inputs(String witness)
    {
        List<String> inputs = new ArrayList<>();
        for (String lineEnd : LINE_ENDS) {
            inputs.add(witness + lineEnd);
        }
        inputs.add("lead x" + witness + witness);
        inputs.add(witness.substring(0, witness.offsetByCodePoints(witness.length(), -1)));
        return inputs;
    }

    /// Returns the public Rust operations on `source`: boolean and capturing find, lookingAt,
    /// and matches, over the whole source and over ranges starting at 0, 1, and its middle and
    /// ending at or one byte before its end; count and a matcher loop retaining only group zero,
    /// over the whole source; a matcher loop retaining every group, over the source and over
    /// regions without its first byte, its last byte, or both; and a matcher's lookingAt and
    /// matches.
    private static List<String> results(RustRegexp regexp, Slice source)
    {
        List<String> results = new ArrayList<>();
        int length = source.length();
        results.add(regexp.find(source) + " " + regexp.lookingAt(source) + " " + regexp.matches(source) + " " + regexp.count(source));
        results.add(describe(regexp.findResult(source)) + describe(regexp.lookingAtResult(source)) + describe(regexp.matchesResult(source)));
        int[] groups = new int[2 * (regexp.capturingGroupCount() + 1)];
        results.add(regexp.findInto(source, groups) + Arrays.toString(groups) +
                regexp.lookingAtInto(source, groups) + Arrays.toString(groups) +
                regexp.matchesInto(source, groups) + Arrays.toString(groups));
        for (int start : new int[] {0, 1, length / 2}) {
            for (int end : new int[] {length, length - 1}) {
                if (start > end) {
                    continue;
                }
                results.add(start + ".." + end + " " + regexp.find(source, start, end) + " " + regexp.lookingAt(source, start, end) + " " + regexp.matches(source, start, end));
                results.add(describe(regexp.findResult(source, start, end)) +
                        describe(regexp.lookingAtResult(source, start, end)) +
                        describe(regexp.matchesResult(source, start, end)));
                results.add(regexp.findInto(source, start, end, groups) + Arrays.toString(groups) +
                        regexp.lookingAtInto(source, start, end, groups) + Arrays.toString(groups) +
                        regexp.matchesInto(source, start, end, groups) + Arrays.toString(groups));
            }
        }
        results.add(matches(regexp.matcher(source)) + matches(regexp.matcher(source, 0)));
        if (length > 1) {
            results.add(matches(regexp.matcher(source).reset(source, 1, length)) +
                    matches(regexp.matcher(source).reset(source, 0, length - 1)) +
                    matches(regexp.matcher(source).reset(source, 1, length - 1)));
        }
        RegexpMatcher matcher = regexp.matcher(source);
        results.add(matcher.lookingAt() + " " + matcher.matches());
        return results;
    }
}
