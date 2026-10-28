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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.airlift.regulator.TestRe2ScanPlan.PROBED_LEADS;
import static io.airlift.regulator.TestRe2ScanPlan.PROBE_DISTRIBUTIONS;
import static io.airlift.regulator.TestRe2ScanPlan.bytes;
import static io.airlift.regulator.TestRe2ScanPlan.describe;
import static io.airlift.regulator.TestRe2ScanPlan.disableInterpretedPlan;
import static io.airlift.regulator.TestRe2ScanPlan.matches;
import static io.airlift.regulator.TestRe2ScanPlan.movedProbe;
import static io.airlift.regulator.TestRe2ScanPlan.operations;
import static io.airlift.regulator.TestRe2ScanPlan.sources;
import static io.airlift.regulator.TestScanPlan.disableOrdinaryEngine;
import static io.airlift.regulator.TestScanPlan.generatedSequence;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// Java patterns on the scan plans that [TestRe2ScanPlan] and [TestScanPlan]
/// cover: which Java constructs reach a plan, and that a planned pattern gives the JDK's results on
/// valid UTF-8 and the ordinary engine's on malformed UTF-8.
public class TestJavaScanPlan
{
    // Each plan shape in Java syntax, with a witness that the JDK matches.
    private static final List<PlanCase> PLAN_CASES = List.of(
            // End-anchored, with a line tail whose dot excludes only LF under UNIX_LINES, and with
            // a dot-all tail.
            new PlanCase("(?d)^https?://(?:www\\.)?([^/]+)/.*\\z", "https://www.höst/päth"),
            new PlanCase("(?s)^https?://([^/]+)/(.*)\\z", "http://höst/a\nb"),
            // Partial, ending in a captured run.
            new PlanCase("^x([^/]+)", "xéb/c"),
            // One two-byte character under a minimum of two characters, anchored and unanchored.
            new PlanCase("^x([^/]{2,})/", "xéé/"),
            new PlanCase("x([^/]{2,})/", "xé/xéé/"),
            // Unanchored and led by a literal.
            new PlanCase("https?://(?:www\\.)?([^/]+)/", "see http://hôst/ and https://www.b/"),
            new PlanCase("x([^/]+)/", "x/xéb/xc/"),
            // Led by a run, and by a one-byte set before a delimited capture.
            new PlanCase("([^/]+)/([^!]*)!", "ab/cé!d/!"),
            new PlanCase("[?&]([^=]+)=", "?a=b&ké=v"),
            // Forked optionals.
            new PlanCase("^(a)?(b)?abcd([0-9]+)/\\z", "aabcd12/"),
            // ASCII-folded under ASCII and Unicode case.
            new PlanCase("(?i)^abc([^:]+):def\\z", "ABCé:DeF"),
            new PlanCase("(?iu)content-type:([^;]+);", "Content-Type:tëxt;"),
            // ASCII case folds s, k, and i to their ASCII pair, not to the long s, the Kelvin
            // sign, or the dotless i, in literals and in runs.
            new PlanCase("(?i)^https?://([^/]+)/", "HTTPS://hoſt/"),
            new PlanCase("(?i)https?://([^/]+)/", "HTTPſ://bad/ HTTPS://host/"),
            new PlanCase("(?i)^xi([^/]+)/", "XIab/x\u0131b/"),
            new PlanCase("(?i)k([^/]+)/", "Kx/Kéy/"),
            new PlanCase("(?i)x(s+)(k+):", "xſK:xs\u212A:xSsKk:"),
            // Ending before a final LF under UNIX_LINES.
            new PlanCase("(?d)^x([a-z]+)$", "xab"),
            new PlanCase("(?d)x([^/]+)/\\Z", "x/xéb/"),
            // Java's $ ending before one final line terminator after a run admitting no
            // terminator, unbounded and bounded.
            new PlanCase("^x([a-z]+)$", "xab"),
            new PlanCase("^x([a-z]{1,3})$", "xab"),
            // After an unbounded run admitting every terminator.
            new PlanCase("(?s)^https?://([^/]+)/(.*)$", "http://höst/a\nb"),
            // After a literal, with \Z anchored and $ unanchored.
            new PlanCase("^x([^/]+)/\\Z", "xéb/"),
            new PlanCase("x([^/]+)/$", "x/xéb/"),
            // After a fixed run, a forked optional, and an ASCII-folded literal.
            new PlanCase("(?s)^x(.{2})$", "xa\r"),
            new PlanCase("^x([a-z]+)(?:\\r)?$", "xab"),
            new PlanCase("(?i)^abc([a-z]+)$", "ABCxY"),
            // The default dot, which excludes U+0085, U+2028, and U+2029 as well as LF and CR, so
            // it admits no terminator: unbounded and bounded before $, and unbounded before \z.
            new PlanCase("^https?://([^/]+)/.*$", "https://höst/päth"),
            new PlanCase("^x(.+)$", "xéb"),
            new PlanCase("^x(.{1,3})$", "xéb"),
            new PlanCase("^x([^/]+)/.*\\z", "xéb/päth"),
            // Partial, so the run's minimum alone decides a boolean match, which must reject a
            // terminator right after the x, as testPlansMatchJdk checks.
            new PlanCase("^x(.+)", "xé\u2028"),
            // Unicode case folds k to the Kelvin sign, and [a-z] also to the long s and the
            // dotless i.
            new PlanCase("(?iu)^k([^/]+)/", "\u212Aab/"),
            new PlanCase("(?iu)^x([a-z]+)/", "xaſ\u212A\u0131b/"),
            // Unanchored searches led by a set of some non-ASCII characters, whose candidates are
            // its ASCII members and lead bytes.
            new PlanCase("(?iu)key=([^;]+);", "kx;\u212Aey=v;"),
            new PlanCase("(?iu)s([a-z]+)=", "ſ=Sſab="),
            new PlanCase("[éè]([a-z]+)=", "àa=é=èab="));
    // Each line end after the witness: none, every Java line terminator, CR LF, and two
    // terminators, which a Java end does not precede.
    private static final List<String> LINE_ENDS = List.of("", "\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029", "\n\n");
    // Endings that a Java end reads as the ordinary engine does: truncated, lone, and overlong
    // terminator bytes, those after a CR, and malformed bytes before a terminator.
    private static final List<byte[]> MALFORMED_ENDINGS = List.of(
            bytes(0xE2, 0x80),
            bytes(0xE2),
            bytes(0xC2),
            bytes(0x85),
            bytes(0xA8),
            bytes(0xC0, 0x8A),
            bytes(0xC1, 0x8D),
            bytes(0xE0, 0x80, 0x8A),
            bytes(0xE0, 0x82, 0x85),
            bytes(0xF0, 0x82, 0x80, 0xA8),
            bytes(0x0D, 0xE2, 0x80),
            bytes(0x0D, 0x85),
            bytes(0xE2, 0x0A),
            bytes(0xC2, 0x0D, 0x0A),
            bytes(0xED, 0xA0, 0x80, 0x0A));
    // Runs that end random plans right before `$` or `\Z`, each with a text it matches. At a Java
    // end the JDK gives back run characters to end before a final line terminator, so a plan
    // keeps a variable run there only if it admits none of the terminators, or every one and is
    // unbounded: these admit none, some, and every terminator, and the dot admits none by
    // default, every one under (?s), and all but LF under (?d), where `$` and `\Z` end before a
    // final LF only, which is not a Java end.
    private static final List<PatternWitness> FINAL_RUNS = List.of(
            new PatternWitness("[a-z]+", "ab"),
            new PatternWitness("[é😀]{1,3}", "é"),
            new PatternWitness("[^\\n/]*", "a\r"),
            new PatternWitness("[\\x00-\\x7F]+", "a"),
            new PatternWitness("[^/]+", "é"),
            new PatternWitness("[^/]{1,3}", "éa"),
            new PatternWitness(".*", "é"),
            new PatternWitness(".{1,3}", "éa"));
    // Case-insensitive plans whose every fold is a set of some non-ASCII characters, so they have
    // no ASCII-folded operation.
    private static final Set<String> GENERATED_FOLDS = Set.of("(?iu)^k([^/]+)/", "(?iu)s([a-z]+)=");
    private static final JavaRegexp.Options GENERATING = JavaRegexp.Options.defaults().setGenerateBytecode(true);

    private record PlanCase(String expression, String witness) {}

    private record NamedRegexp(String name, JavaRegexp regexp) {}

    @Test
    public void testRouteSelection()
    {
        for (PlanCase planCase : PLAN_CASES) {
            String expression = planCase.expression();
            JavaRegexp regexp = JavaRegexp.compile(utf8Slice(expression));
            assertThat(regexp.re2().usesScanPlanForDiagnostics()).as(expression).isTrue();
            // Generated bytecode covers plans with no ASCII-folded operation, and only on request.
            boolean generates = !expression.startsWith("(?i") || GENERATED_FOLDS.contains(expression);
            assertThat(JavaRegexp.compile(utf8Slice(expression), GENERATING).usesGeneratedBytecode()).as(expression).isEqualTo(generates);
            assertThat(JavaRegexp.compileForcingScanPlanHandoffForTesting(utf8Slice(expression), GENERATING).usesGeneratedBytecode()).as(expression).isEqualTo(generates);
            assertThat(regexp.usesGeneratedBytecode()).as(expression).isFalse();
        }
        // The JDK gives back run characters to end before a final terminator, which a plan's run
        // never does, so a run that can reach Java's $ or \Z must admit no terminator, or every
        // terminator and be unbounded. Patterns whose run breaks that rule keep the ordinary
        // engine, as do multiline anchors, word boundaries, and reluctant runs.
        for (String expression : List.of(
                "^x([^\\n]+)$",
                "^x([^\\r\\n]+)$",
                "^x([a-z\\r]+)$",
                "(?s)^x(.{1,3})$",
                "(?m)^x([^/]+)/",
                "(?m)x([^/]+)/$",
                "\\bx([^/]+)/",
                "^x([^/]+?)/")) {
            assertThat(JavaRegexp.compile(utf8Slice(expression)).re2().usesScanPlanForDiagnostics()).as(expression).isFalse();
            assertThat(JavaRegexp.compile(utf8Slice(expression), GENERATING).usesGeneratedBytecode()).as(expression).isFalse();
        }
        assertThat(JavaRegexp.compileWithoutScanPlanForTesting(utf8Slice(PLAN_CASES.getFirst().expression()), JavaRegexp.Options.defaults()).re2().usesScanPlanForDiagnostics()).isFalse();
    }

    @Test
    public void testPublicCallsRunThePlan()
            throws ReflectiveOperationException
    {
        // The second pattern's Java end matches before the final CR LF.
        for (PlanCase planCase : List.of(new PlanCase("^https?://([^/]+)", "https://höst/path"), new PlanCase("^x([a-z]+)$", "xab\r\n"))) {
            Slice pattern = utf8Slice(planCase.expression());
            JavaRegexp interpreted = JavaRegexp.compile(pattern);
            JavaRegexp generated = JavaRegexp.compile(pattern, GENERATING);
            assertThat(generated.usesGeneratedBytecode()).as(planCase.expression()).isTrue();
            // Make ordinary program execution unable to succeed, and the generated pattern's
            // interpreted plan too, so these calls pass only if they run on the plan or its class.
            disableOrdinaryEngine(interpreted.re2());
            disableOrdinaryEngine(generated.re2());
            disableInterpretedPlan(generated.re2());
            Matcher jdk = Pattern.compile(planCase.expression()).matcher(planCase.witness());
            assertThat(jdk.find()).as(planCase.expression()).isTrue();
            Slice source = utf8Slice(planCase.witness());
            for (NamedRegexp regexp : List.of(new NamedRegexp("interpreted", interpreted), new NamedRegexp("generated", generated))) {
                String description = regexp.name() + " " + planCase.expression();
                assertThat(regexp.regexp().find(source)).as(description).isTrue();
                assertThat(regexp.regexp().count(source)).as(description).isEqualTo(1);
                assertThat(regexp.regexp().findResult(source).groupUtf8(1)).as(description).isEqualTo(jdk.group(1));
                RegexpMatcher matcher = regexp.regexp().matcher(source);
                assertThat(matcher.find()).as(description).isTrue();
                assertThat(matcher.group(1)).as(description).isEqualTo(utf8Slice(jdk.group(1)));
            }
        }

        // A forced handoff continues every unanchored search on the ordinary engine before the
        // plan's first attempt, which would match here, so with that engine unable to succeed it
        // finds nothing.
        Slice handoffPattern = utf8Slice("x([^/]+)/");
        Slice handoffSource = utf8Slice("xéb/xc/");
        for (JavaRegexp.Options options : List.of(JavaRegexp.Options.defaults(), GENERATING)) {
            String name = options.generateBytecode() ? "generated" : "interpreted";
            JavaRegexp handoff = JavaRegexp.compileForcingScanPlanHandoffForTesting(handoffPattern, options);
            assertThat(handoff.usesGeneratedBytecode()).as(name).isEqualTo(options.generateBytecode());
            disableOrdinaryEngine(handoff.re2());
            assertThat(handoff.find(handoffSource)).as(name).isFalse();
            assertThat(handoff.count(handoffSource)).as(name).isZero();
        }
    }

    @Test
    public void testPlansMatchJdk()
    {
        for (PlanCase planCase : PLAN_CASES) {
            Pattern jdk = Pattern.compile(planCase.expression());
            assertThat(jdk.matcher(planCase.witness()).find()).as(planCase.expression()).isTrue();
            for (NamedRegexp regexp : planned(planCase.expression())) {
                for (String input : inputs(planCase.witness())) {
                    assertThat(results(regexp.regexp(), input)).as("%s %s on [%s]", regexp.name(), planCase.expression(), input).isEqualTo(results(jdk, input));
                }
            }
        }
        // A partial run's minimum rejects a terminator right after the x, which no input above
        // puts there.
        String partial = "^x(.+)";
        String rejected = "x\u2028é";
        Pattern jdk = Pattern.compile(partial);
        assertThat(jdk.matcher(rejected).find()).isFalse();
        for (NamedRegexp regexp : planned(partial)) {
            assertThat(results(regexp.regexp(), rejected)).as(regexp.name()).isEqualTo(results(jdk, rejected));
        }
    }

    @Test
    public void testPlansMatchTheOrdinaryEngineOnMalformedInput()
    {
        // The JDK reads text, not bytes, so the ordinary engine decides malformed UTF-8.
        for (PlanCase planCase : PLAN_CASES) {
            Slice pattern = utf8Slice(planCase.expression());
            Re2 expected = JavaRegexp.compileWithoutScanPlanForTesting(pattern, JavaRegexp.Options.defaults()).re2();
            List<NamedRegexp> planned = planned(planCase.expression());
            byte[] witness = planCase.witness().getBytes(UTF_8);
            List<Slice> sources = new ArrayList<>(sources(new Random(planCase.expression().hashCode()), witness));
            for (byte[] ending : MALFORMED_ENDINGS) {
                byte[] padded = Arrays.copyOf(witness, witness.length + ending.length + 1);
                System.arraycopy(ending, 0, padded, witness.length, ending.length);
                // Hide a byte after the view; after an E2 80 ending it would complete U+2028.
                padded[padded.length - 1] = (byte) 0xA8;
                sources.add(wrappedBuffer(padded).slice(0, padded.length - 1));
            }
            for (Slice source : sources) {
                List<String> expectedResults = operations(expected, source);
                for (NamedRegexp regexp : planned) {
                    assertThat(operations(regexp.regexp().re2(), source)).as("%s %s on %s", regexp.name(), planCase.expression(), HexFormat.of().formatHex(source.getBytes())).isEqualTo(expectedResults);
                }
            }
        }
    }

    @Test
    public void testRandomPlansMatchTheOrdinaryEngine()
    {
        // The shapes of TestRe2ScanPlan's random plans, ending in Java's $ or \Z, either of which
        // also matches before a final line terminator, and one more: a Java end right after a
        // final run.
        int[] plansPerShape = new int[10];
        int plansWithJavaEnds = 0;
        int plansWithJavaEndsAfterRuns = 0;
        int declinedJavaEndsAfterRuns = 0;
        int plansWithMovedProbes = 0;
        for (long seed : new long[] {229, 233, 239}) {
            Random random = new Random(seed);
            for (int iteration = 0; iteration < 200; iteration++) {
                PatternWitness randomSequence = generatedSequence(random, 0);
                String sequence = randomSequence.pattern();
                String text = randomSequence.text();
                String flags = List.of("", "(?s)", "(?d)", "(?i)").get(random.nextInt(4));
                NamedDistribution distribution = PROBE_DISTRIBUTIONS.get(random.nextInt(PROBE_DISTRIBUTIONS.size()));
                String lead = PROBED_LEADS.get(random.nextInt(PROBED_LEADS.size()));
                PatternWitness finalRun = FINAL_RUNS.get(random.nextInt(FINAL_RUNS.size()));
                String beforeEnd = flags + (random.nextBoolean() ? "^x(" : "x(") + sequence + ")(" + finalRun.pattern() + ")";
                String endAfterRun = beforeEnd + (random.nextBoolean() ? "$" : "\\Z");
                String[] expressions = {
                        flags + "^x(" + sequence + ")!" + (random.nextBoolean() ? "$" : "\\Z"),
                        flags + "^x(" + sequence + ")",
                        flags + "^x" + sequence + "([^/]*)/.*" + (random.nextBoolean() ? "$" : "\\Z"),
                        flags + "x(" + sequence + ")!" + (random.nextBoolean() ? "$" : ""),
                        flags + "([^:]+):(" + sequence + ")!",
                        flags + "^x(" + sequence + ")([^/]+)",
                        flags + "x(" + sequence + ")(.+)",
                        flags + "^abc" + sequence + "([^:]+):",
                        endAfterRun,
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
                        "x" + text + finalRun.text(),
                        lead + text + "!",
                };
                for (int shape = 0; shape < expressions.length; shape++) {
                    String expression = expressions[shape];
                    byte[] witness = witnesses[shape].getBytes(UTF_8);
                    List<Slice> sources = new ArrayList<>(sources(random, witness));
                    for (String lineEnd : LINE_ENDS.subList(1, LINE_ENDS.size())) {
                        sources.add(utf8Slice(witnesses[shape] + lineEnd));
                    }
                    String description = expression + " " + distribution.name();
                    Re2 planned = assertPlanMatchesOrdinaryEngine(description, expression, distribution.frequencies(), sources);
                    if (planned == null) {
                        // A pattern ending in a final run that selects no plan with its Java end
                        // but one with \z in its place is declined by the Java end rule alone.
                        // Under (?d) its end is not a Java end, so it is not counted.
                        boolean javaEndAfterRun = expression.equals(endAfterRun) && !flags.equals("(?d)");
                        JavaRegexp.Options options = JavaRegexp.Options.defaults().setByteFrequencies(distribution.frequencies());
                        if (javaEndAfterRun && JavaRegexp.compile(utf8Slice(beforeEnd + "\\z"), options).re2().usesScanPlanForDiagnostics()) {
                            declinedJavaEndsAfterRuns++;
                        }
                        continue;
                    }
                    // Every planned pattern has a known match, not only random misses.
                    assertThat(planned.find(wrappedBuffer(witness))).as(description).isTrue();
                    plansPerShape[shape]++;
                    ScanPlan plan = planned.caseSensitiveScanPlan();
                    if (plan != null && plan.hasJavaFinalEnd()) {
                        plansWithJavaEnds++;
                        plansWithJavaEndsAfterRuns += expression.equals(endAfterRun) ? 1 : 0;
                    }
                    plansWithMovedProbes += movedProbe(planned) ? 1 : 0;
                }
            }
        }
        assertThat(Arrays.stream(plansPerShape).min().orElseThrow()).as("fewest plans of one shape").isGreaterThan(100);
        assertThat(plansWithJavaEnds).as("plans with a Java end").isGreaterThan(300);
        assertThat(plansWithJavaEndsAfterRuns).as("plans with a Java end after a final run").isGreaterThan(50);
        assertThat(declinedJavaEndsAfterRuns).as("patterns the Java end rule declines").isGreaterThan(50);
        assertThat(plansWithMovedProbes).as("plans probing a leading literal past its first byte").isGreaterThan(30);
    }

    /// Compares the Java pattern's compiles under `frequencies` as the shared overload in
    /// [TestRe2ScanPlan] does. Returns the planned pattern, or `null`, comparing nothing, when the
    /// pattern selects no plan.
    private static Re2 assertPlanMatchesOrdinaryEngine(String description, String expression, ByteFrequencies frequencies, List<Slice> sources)
    {
        Slice pattern = utf8Slice(expression);
        JavaRegexp.Options options = JavaRegexp.Options.defaults().setByteFrequencies(frequencies);
        JavaRegexp.Options generating = JavaRegexp.Options.defaults().setByteFrequencies(frequencies).setGenerateBytecode(true);
        Re2 actual = JavaRegexp.compile(pattern, options).re2();
        if (!actual.usesScanPlanForDiagnostics()) {
            return null;
        }
        TestRe2ScanPlan.assertPlanMatchesOrdinaryEngine(
                description,
                JavaRegexp.compileWithoutScanPlanForTesting(pattern, options).re2(),
                actual,
                JavaRegexp.compileForcingScanPlanHandoffForTesting(pattern, options).re2(),
                JavaRegexp.compile(pattern, generating).re2(),
                JavaRegexp.compileForcingScanPlanHandoffForTesting(pattern, generating).re2(),
                sources);
        return actual;
    }

    /// Returns the pattern compiled with its plan, and with its plan forced to hand every
    /// unanchored search to the ordinary engine, each with and without generated bytecode.
    private static List<NamedRegexp> planned(String expression)
    {
        Slice pattern = utf8Slice(expression);
        return List.of(
                new NamedRegexp("plan", JavaRegexp.compile(pattern)),
                new NamedRegexp("handoff", JavaRegexp.compileForcingScanPlanHandoffForTesting(pattern, JavaRegexp.Options.defaults())),
                new NamedRegexp("generated", JavaRegexp.compile(pattern, GENERATING)),
                new NamedRegexp("generated handoff", JavaRegexp.compileForcingScanPlanHandoffForTesting(pattern, GENERATING)));
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

    /// Returns boolean find, lookingAt, and matches; the same with every group; the matches of a
    /// matcher loop over the input and over a region without its first and last characters; and
    /// count, with UTF-8 byte offsets.
    private static List<String> results(JavaRegexp regexp, String input)
    {
        Slice source = utf8Slice(input);
        List<String> results = new ArrayList<>();
        results.add(regexp.find(source) + " " + regexp.lookingAt(source) + " " + regexp.matches(source));
        results.add(describe(regexp.findResult(source)) + describe(regexp.lookingAtResult(source)) + describe(regexp.matchesResult(source)));
        results.add(matches(regexp.matcher(source)));
        int regionStart = utf8Offset(input, input.offsetByCodePoints(0, 1));
        int regionEnd = utf8Offset(input, input.offsetByCodePoints(input.length(), -1));
        results.add(regionStart <= regionEnd ? matches(regexp.matcher(source).reset(source, regionStart, regionEnd)) : "");
        results.add(String.valueOf(regexp.count(source)));
        return results;
    }

    private static List<String> results(Pattern jdk, String input)
    {
        List<String> results = new ArrayList<>();
        Matcher find = jdk.matcher(input);
        Matcher lookingAt = jdk.matcher(input);
        Matcher matches = jdk.matcher(input);
        boolean found = find.find();
        boolean lookedAt = lookingAt.lookingAt();
        boolean matched = matches.matches();
        results.add(found + " " + lookedAt + " " + matched);
        results.add(describeJdk(found ? find : null, input) + describeJdk(lookedAt ? lookingAt : null, input) + describeJdk(matched ? matches : null, input));
        Matcher loop = jdk.matcher(input);
        StringBuilder loopMatches = new StringBuilder();
        long count = 0;
        while (loop.find()) {
            loopMatches.append(describeJdk(loop, input));
            count++;
        }
        results.add(loopMatches.toString());
        int regionStart = input.offsetByCodePoints(0, 1);
        int regionEnd = input.offsetByCodePoints(input.length(), -1);
        StringBuilder regionMatches = new StringBuilder();
        if (regionStart <= regionEnd) {
            Matcher region = jdk.matcher(input).region(regionStart, regionEnd);
            while (region.find()) {
                regionMatches.append(describeJdk(region, input));
            }
        }
        results.add(regionMatches.toString());
        results.add(String.valueOf(count));
        return results;
    }

    private static String describeJdk(java.util.regex.MatchResult result, String input)
    {
        if (result == null) {
            return "[none]";
        }
        StringBuilder description = new StringBuilder("[");
        for (int group = 0; group <= result.groupCount(); group++) {
            description.append(utf8Offset(input, result.start(group))).append(':').append(utf8Offset(input, result.end(group))).append(' ');
        }
        return description.append(']').toString();
    }

    private static int utf8Offset(String input, int utf16Offset)
    {
        if (utf16Offset < 0) {
            return -1;
        }
        return input.substring(0, utf16Offset).getBytes(UTF_8).length;
    }
}
