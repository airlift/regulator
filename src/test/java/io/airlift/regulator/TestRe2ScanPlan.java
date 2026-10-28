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

import io.airlift.regulator.TestScanPlan.PatternWitness;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static io.airlift.regulator.TestScanPlan.disableOrdinaryEngine;
import static io.airlift.regulator.TestScanPlan.generatedSequence;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestRe2ScanPlan
{
    // RE2's classes of every non-ASCII character accept these sequences although they are not
    // valid UTF-8: overlong three- and four-byte forms, an encoded surrogate, and a four-byte form
    // above U+10FFFF. A class of only some non-ASCII characters rejects them, except an encoded
    // surrogate it holds.
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
    // Characters on the edges of the classes of only some non-ASCII characters that plans read:
    // U+0085 and U+2028, which Java's default dot excludes, and U+2027, which precedes U+2028.
    private static final List<byte[]> BOUNDARY_CHARACTERS = List.of(bytes(0xC2, 0x85), bytes(0xE2, 0x80, 0xA8), bytes(0xE2, 0x80, 0xA7));
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
            // A run of some non-ASCII characters, whose minimum rejects the U+0085 it excludes.
            new PlanCase("^x([^/\\x{85}]+)", "x\u0085/y"),
            new PlanCase("x([^/\\x{85}]+)", "x\u0085/y"),
            // A partial delimited capture, ASCII-folded when case-insensitive.
            new PlanCase("^abc([^:]+):", "ABCé:d"),
            // Explicit ASCII pairs, which match neither the long s nor the Kelvin sign.
            new PlanCase("x[Ss][Kk]([^/]+)/", "xſkb/xsKb/xSké/"),
            // One two-byte character under a minimum of two characters.
            new PlanCase("^x([^/]{2,})", "xé"),
            new PlanCase("^x([^/]{2,})/", "xé/"),
            new PlanCase("x([^/]{2,})/", "xé/"),
            // A run of some non-ASCII characters with a minimum of two, which the witness without
            // its last byte fails.
            new PlanCase("^x([é😀]{2,})", "xé😀"),
            // An unanchored run that stops at a rejected sequence before its delimiter.
            new PlanCase("[?&]([^=]+)=END", HexFormat.of().parseHex("3f61c080623d654e64")),
            // A run excluding four ASCII bytes, whose vector scan must stop at each of them; in
            // ascending byte order '#' is the first, ':' the third, and '?' the fourth.
            new PlanCase("^x([^/:?#]+)/$", "x" + "a".repeat(20) + "?" + "a".repeat(20) + "/"),
            new PlanCase("^x([^/:?#]+)/$", "x" + "a".repeat(20) + "#" + "a".repeat(20) + "/"),
            new PlanCase("^x([^/:?#]+)/$", "x" + "a".repeat(20) + ":" + "a".repeat(20) + "/"),
            // A run of some non-ASCII characters, whose vector scan must stop at its delimiter.
            new PlanCase("^x([^/\\x{85}]+)/", "x" + "a".repeat(20) + "/b"),
            // A run of every ASCII byte and some non-ASCII characters, whose scan must stop at the
            // U+0085 it excludes.
            new PlanCase("^x([^\\x{85}]+)$", "xa\u0085"),
            // A run of three ASCII members, whose vector scan must not stop at the third, 'c'.
            new PlanCase("^x([abc]+)/$", "x" + "c".repeat(40) + "/"),
            // Forked start-anchored plans, whose boolean find and count run on the ordinary engine
            // up to 48 bytes.
            new PlanCase("^(a)?(b)?abcd([0-9]+)/$", "aabcd" + "1".repeat(42) + "/"),
            new PlanCase("^(a)?(b)?abcd([0-9]+)/$", "aabcd" + "1".repeat(43) + "/"),
            new PlanCase("^(a)?(b)?abcd([^/]+)/", "aabcd" + "é".repeat(21) + "/"),
            // Inputs that hand off to the ordinary engine in the middle of a search.
            new PlanCase("x([^z]+)zq", "xa".repeat(1100) + "zrxbzqxczq"),
            new PlanCase("x([^z\\x{85}]+)zq", "xa".repeat(1100) + "zrxbzqxczq"),
            new PlanCase("([^/]+)/([^!]+)!x", "a/".repeat(1100) + "!y!x/b/c!x"),
            new PlanCase("[0-9]([0-9]*)zz", "1 2 3 4 5 6 7 8 9zz" + " ".repeat(256)),
            // Runs of some non-ASCII characters but not all, which read them by their exact
            // encodings: a delimited capture whose set excludes U+0085, and a Unicode class.
            new PlanCase("^x([^/\\x{85}]+)/", "xé\u2028b/"),
            new PlanCase("^([é😀]+):$", "é😀é:"),
            // Case-insensitive, an s folds to the long s, a k to the Kelvin sign, and [a-z] to
            // both.
            new PlanCase("^https?://([^/]+)/", "HTTPſ://hoſt/"),
            new PlanCase("^k([^/]+)/", "\u212Aab/"),
            new PlanCase("^x([a-z]+)/", "xaſ\u212Ab/"),
            // A failed attempt resumes at the end of a run of some non-ASCII characters that
            // contains the leading set.
            new PlanCase("([0-9][0-9é]*)=x", "1é=y 2é=x"),
            // Unanchored searches led by a set of some non-ASCII characters, whose candidates are
            // its ASCII members and lead bytes, such as the k of key= when case-insensitive. A
            // candidate lead byte that starts no member, such as the C3 of à or a lone C5 or E2
            // before a member with that lead, fails and resumes past it, and a run of every ASCII
            // byte but its delimiter stops at U+0085.
            new PlanCase("[éè]([a-z]+)=", "àa=é=èab="),
            new PlanCase("[sS\\x{17F}]([a-z]+)=", HexFormat.of().parseHex("c5c5bf613d")),
            new PlanCase("key=([^;]+);", HexFormat.of().parseHex("6b783be2e284aa65793d763b6b65793d773b")),
            new PlanCase("([^/\\x{85}]+)/", "a\u0085b/c/"));

    // Distributions under which random plans compile: the uniform one, which keeps the probe of
    // each leading literal on its first byte, and two that move it, the learned JSON-record
    // frequencies onto the = of été= and rare non-letters onto the : of https://.
    static final List<NamedDistribution> PROBE_DISTRIBUTIONS = List.of(
            new NamedDistribution("uniform", ByteFrequencies.uniform()),
            new NamedDistribution("JSON records", TestingByteFrequencies.learnedJsonRecords()),
            new NamedDistribution("rare non-letters", TestingByteFrequencies.rareNonLetters()));
    // Multi-byte literals that lead random unanchored plans, each of which one of those
    // distributions probes past its first byte.
    static final List<String> PROBED_LEADS = List.of("été=", "https://");

    private record NamedOptions(String name, Re2.Options options) {}

    record NamedDistribution(String name, ByteFrequencies frequencies) {}

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
        assertThat(pattern.usesScanPlanForDiagnostics()).isTrue();
        // Make ordinary program execution unable to succeed, so these calls pass only if they
        // run on the plan.
        disableOrdinaryEngine(pattern);
        Slice source = utf8Slice("https://höst/path");
        assertThat(pattern.find(source)).isTrue();
        assertThat(pattern.findResult(source).groupUtf8(1)).isEqualTo("höst");
        assertThat(pattern.replaceFirst(source, utf8Slice("\\1"))).isEqualTo(utf8Slice("höst/path"));
        RegexpMatcher matcher = pattern.matcher(source);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isEqualTo(utf8Slice("höst"));

        // A forced handoff continues every unanchored search on the ordinary engine before the
        // plan's first attempt, which would match here, so with that engine unable to succeed it
        // finds nothing.
        Slice handoffPattern = utf8Slice("x([^/]+)/");
        Slice handoffSource = utf8Slice("xéb/xc/");
        for (NamedOptions options : List.of(
                new NamedOptions("interpreted", Re2.Options.defaults()),
                new NamedOptions("generated", Re2.Options.defaults().setGenerateBytecode(true)))) {
            Re2 handoff = Re2.compileForcingScanPlanHandoffForTesting(handoffPattern, options.options());
            assertThat(handoff.usesScanPlanForDiagnostics()).as(options.name()).isTrue();
            assertThat(handoff.usesGeneratedBytecode()).as(options.name()).isEqualTo(options.options().generateBytecode());
            disableOrdinaryEngine(handoff);
            assertThat(handoff.find(handoffSource)).as(options.name()).isFalse();
            assertThat(handoff.count(handoffSource)).as(options.name()).isZero();
        }
    }

    @Test
    public void testGeneratedCallsBypassTheInterpretedPlan()
            throws ReflectiveOperationException
    {
        // A generated pattern answers each call that reaches its plan, anchored, forked, and
        // unanchored: boolean find, count, capturing find, and a matcher's find. The source is
        // short, so the forked pattern's boolean calls run the generated class where the
        // interpreter would hand them to the ordinary engine. Disabling the ordinary engine and
        // the interpreted plan leaves the generated class as the only engine that can answer.
        assertThat(Re2.compile(utf8Slice("^(a)?x(?P<name>[^/]+)/")).usesOrdinaryShortBooleanMatchForDiagnostics()).isTrue();
        for (String expression : List.of("^x(?P<name>[^/]+)/", "^(a)?x(?P<name>[^/]+)/", "x(?P<name>[^/]+)/")) {
            Re2 generated = Re2.compile(utf8Slice(expression), Re2.Options.defaults().setGenerateBytecode(true));
            assertThat(generated.usesGeneratedBytecode()).as(expression).isTrue();
            disableOrdinaryEngine(generated);
            disableInterpretedPlan(generated);
            Slice source = utf8Slice("xabc/xd/");
            assertThat(generated.find(source)).as(expression).isTrue();
            assertThat(generated.count(source)).as(expression).isEqualTo(expression.startsWith("^") ? 1 : 2);
            assertThat(generated.findResult(source).groupUtf8("name")).as(expression).isEqualTo("abc");
            RegexpMatcher generatedMatcher = generated.matcher(source);
            assertThat(generatedMatcher.find()).as(expression).isTrue();
            assertThat(generatedMatcher.group("name")).as(expression).isEqualTo(utf8Slice("abc"));
        }
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
            Re2 pattern = Re2.compile(utf8Slice(expression));
            assertThat(pattern.usesScanPlanForDiagnostics()).as(expression).isTrue();
            // Generated bytecode covers the case-sensitive plans, and only on request.
            Re2 generated = Re2.compile(utf8Slice(expression), Re2.Options.defaults().setGenerateBytecode(true));
            assertThat(generated.usesGeneratedBytecode()).as(expression).isEqualTo(!expression.startsWith("(?i)"));
            assertThat(pattern.usesGeneratedBytecode()).as(expression).isFalse();
        }
        // A generated run-leading ASCII plan keeps the handoff of dense boolean searches to the
        // ordinary engine.
        Re2 dense = Re2.compile(utf8Slice("[0-9]([0-9]*)zz"), Re2.Options.defaults().setGenerateBytecode(true));
        assertThat(dense.usesGeneratedBytecode()).isTrue();
        assertThat(dense.usesOrdinaryUnanchoredBooleanSearchForDiagnostics()).isTrue();
        String eligible = "^https?://([^/]+)/.*$";
        assertThat(Re2.compile(utf8Slice(eligible), Re2.Options.defaults().setLongestMatch(true)).usesScanPlanForDiagnostics()).isFalse();
        assertThat(Re2.compile(utf8Slice(eligible), Re2.Options.latin1()).usesScanPlanForDiagnostics()).isFalse();
        assertThat(Re2.compileWithoutScanPlanForTesting(utf8Slice(eligible), Re2.Options.defaults()).usesScanPlanForDiagnostics()).isFalse();
        // Lazy runs, lazy tails, nullable patterns, and an unanchored search led by a variable
        // run of some non-ASCII characters keep the ordinary engine, unless that run is unbounded
        // and holds every ASCII byte but the delimiter after it.
        for (String expression : List.of("^([^/]+?)/$", "^([^/]+)/.*?$", "^([a-z]*)$", "([éè]+)([a-z]+)=", "([^/:\\x{85}]+)/")) {
            assertThat(Re2.compile(utf8Slice(expression)).usesScanPlanForDiagnostics()).as(expression).isFalse();
            assertThat(Re2.compile(utf8Slice(expression), Re2.Options.defaults().setGenerateBytecode(true)).usesGeneratedBytecode()).as(expression).isFalse();
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
                assertThat(assertPlanMatchesOrdinaryEngine(description, planCase.expression(), options.options(), sources) != null).as(description).isEqualTo(planned);
            }
        }
        // The long cases hand off in the middle of a search: at the next candidate after the work
        // budget ends the first failed attempt, and after the eighth of eight dense failures of
        // boolean find by failure density. The bytes a run of some non-ASCII characters scans
        // count toward the budget in both executors.
        for (String expression : List.of("x([^z]+)zq", "x([^z\\x{85}]+)zq", "([^/]+)/([^!]+)!x")) {
            Slice witness = witness(expression);
            assertThat(plan(expression).search(witness, 0, witness.length(), 0, false, false, null)).as(expression).isEqualTo(1);
        }
        ScanPlan folded = plan("(?i)x([^z\\x{85}]+)zq");
        assertThat(folded.usesAsciiFoldedExecutor()).isTrue();
        Slice foldedWitness = utf8Slice("xA".repeat(1100) + "ZrxbZQxczq");
        assertThat(folded.searchAsciiFolded(foldedWitness, 0, foldedWitness.length(), 0, false, false, null)).isEqualTo(1);
        Slice dense = witness("[0-9]([0-9]*)zz");
        assertThat(plan("[0-9]([0-9]*)zz").searchBeforeHandoff(dense, 0, dense.length(), 0, false)).isEqualTo(dense.toStringUtf8().indexOf('8') + 1);
        // The four excluded ASCII bytes keep this a run rather than a delimited capture, so the
        // case reaches the run's vector scan.
        assertThat(plan("^x([^/:?#]+)/$").operationsForDiagnostics()).isEqualTo("LITERAL,RE2_RUN,LITERAL,END");
        // The U+0085 keeps this a run of some non-ASCII characters, so the case reaches its scan.
        assertThat(plan("^x([^/\\x{85}]+)/").operationsForDiagnostics()).isEqualTo("LITERAL,RE2_SET_RUN,LITERAL");
        // A failed attempt resumes past the run of some non-ASCII characters it read, in both
        // executors, so each run of digits is one candidate rather than one per digit.
        for (String expression : List.of("([0-9][0-9é]*)=x", "(?i)([0-9][0-9é]*)=x")) {
            assertThat(plan(expression).candidateAttemptsForDiagnostics(utf8Slice("123é=y 456é=x"))).as(expression).isEqualTo(2);
        }
    }

    @Test
    public void testRandomPlansMatchTheOrdinaryEngine()
    {
        int[] plansPerShape = new int[9];
        int plansWithRuns = 0;
        int plansWithDelimitedCaptures = 0;
        int plansWithSetRuns = 0;
        int plansWithMovedProbes = 0;
        for (long seed : new long[] {211, 223, 227}) {
            Random random = new Random(seed);
            for (int iteration = 0; iteration < 200; iteration++) {
                PatternWitness randomSequence = generatedSequence(random, 0);
                String sequence = randomSequence.pattern();
                String text = randomSequence.text();
                String flags = List.of("", "(?s)", "(?i)").get(random.nextInt(3));
                NamedDistribution distribution = PROBE_DISTRIBUTIONS.get(random.nextInt(PROBE_DISTRIBUTIONS.size()));
                Re2.Options options = Re2.Options.defaults().setByteFrequencies(distribution.frequencies());
                String lead = PROBED_LEADS.get(random.nextInt(PROBED_LEADS.size()));
                // End-anchored, partial, compacted with a tail, unanchored, led by a run, ending
                // in a captured run when anchored and unanchored, partial with a delimited capture
                // after the sequence, and unanchored led by a multi-byte literal.
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
                    byte[] witness = witnesses[shape].getBytes(UTF_8);
                    String description = expressions[shape] + " " + distribution.name();
                    Re2 planned = assertPlanMatchesOrdinaryEngine(description, expressions[shape], options, sources(random, witness));
                    if (planned == null) {
                        continue;
                    }
                    // Every planned pattern has a known match, not only random misses.
                    assertThat(planned.find(wrappedBuffer(witness))).as(description).isTrue();
                    plansPerShape[shape]++;
                    String operations = plan(expressions[shape]).operationsForDiagnostics();
                    plansWithRuns += operations.contains("RE2_RUN") ? 1 : 0;
                    plansWithDelimitedCaptures += operations.contains("RE2_DELIMITED_CAPTURE") ? 1 : 0;
                    plansWithSetRuns += operations.contains("RE2_SET_RUN") ? 1 : 0;
                    plansWithMovedProbes += movedProbe(planned) ? 1 : 0;
                }
            }
        }
        assertThat(Arrays.stream(plansPerShape).min().orElseThrow()).as("fewest plans of one shape").isGreaterThan(100);
        assertThat(plansWithRuns).as("plans with an RE2 run").isGreaterThan(1500);
        assertThat(plansWithDelimitedCaptures).as("plans with an RE2 delimited capture").isGreaterThan(150);
        assertThat(plansWithSetRuns).as("plans with an RE2 set run").isGreaterThan(400);
        assertThat(plansWithMovedProbes).as("plans probing a leading literal past its first byte").isGreaterThan(30);
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
                if (Re2.compile(pattern).usesScanPlanForDiagnostics()) {
                    planned.add(pattern.toStringUtf8());
                }
            }
        }
        assertThat(planned).containsExactly("(?i)^abcdef(.*)$", "([^/]+)/", "^a(?s).*$", "^a.*$", "^abcdef(.*)$", "^x([^/]+)/", "a((?s).*)", "a(.*)", "a(.*)$");
    }

    /// Returns the witness alone, doubled after a lead, and without its last byte, and with each
    /// malformed sequence and boundary character inserted at a random position, substituted for
    /// the byte before it, wrapped around the witness, and appended. Each is a view between
    /// continuation bytes, which would complete a truncated sequence at its end, so a trailing run
    /// that read past the view would change a result.
    static List<Slice> sources(Random random, byte[] witness)
    {
        List<byte[]> sources = new ArrayList<>();
        sources.add(witness);
        sources.add(concat("lead x".getBytes(UTF_8), witness, witness));
        sources.add(Arrays.copyOf(witness, witness.length - 1));
        for (List<byte[]> sequences : List.of(ACCEPTED_MALFORMED, REJECTED_MALFORMED, BOUNDARY_CHARACTERS)) {
            for (byte[] sequence : sequences) {
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

    /// Compares the RE2 pattern's compiles under `options` as the shared overload below does.
    /// Returns the planned pattern, or `null`, comparing nothing, when the pattern selects no
    /// plan.
    private static Re2 assertPlanMatchesOrdinaryEngine(String description, String expression, Re2.Options options, List<Slice> sources)
    {
        Slice pattern = utf8Slice(expression);
        Re2 actual = Re2.compile(pattern, options);
        if (!actual.usesScanPlanForDiagnostics()) {
            return null;
        }
        Re2.Options generating = options.copy().setGenerateBytecode(true);
        assertPlanMatchesOrdinaryEngine(
                description,
                Re2.compileWithoutScanPlanForTesting(pattern, options),
                actual,
                Re2.compileForcingScanPlanHandoffForTesting(pattern, options),
                Re2.compile(pattern, generating),
                Re2.compileForcingScanPlanHandoffForTesting(pattern, generating),
                sources);
        return actual;
    }

    /// Compares the operations of a pattern compiled with its plan, with its plan forced to hand
    /// off, and each of those with generated bytecode requested, with those of the same pattern
    /// compiled without its plan, `expected`, on each source. A frontend passes its own
    /// compilations, so its plans run under its own rules.
    static void assertPlanMatchesOrdinaryEngine(String description, Re2 expected, Re2 actual, Re2 handoff, Re2 generated, Re2 generatedHandoff, List<Slice> sources)
    {
        // Every case-sensitive plan generates.
        boolean generates = actual.caseSensitiveScanPlan() != null;
        assertThat(generated.usesGeneratedBytecode()).as(description).isEqualTo(generates);
        assertThat(generatedHandoff.usesGeneratedBytecode()).as(description).isEqualTo(generates);
        for (Slice source : sources) {
            String sourceDescription = description + " on " + HexFormat.of().formatHex(source.getBytes());
            List<String> expectedResults = operations(expected, source);
            assertThat(operations(actual, source)).as(sourceDescription).isEqualTo(expectedResults);
            assertThat(operations(handoff, source)).as("handoff " + sourceDescription).isEqualTo(expectedResults);
            if (generates) {
                assertThat(operations(generated, source)).as("generated " + sourceDescription).isEqualTo(expectedResults);
                assertThat(operations(generatedHandoff, source)).as("generated handoff " + sourceDescription).isEqualTo(expectedResults);
            }
        }
    }

    /// Returns whether the pattern's case-sensitive plan probes a byte of its leading literal past
    /// the first.
    static boolean movedProbe(Re2 pattern)
    {
        ScanPlan plan = pattern.caseSensitiveScanPlan();
        return plan != null && plan.leadingLiteralProbeOffsetForDiagnostics() > 0;
    }

    /// Returns the results of the public matching operations on `source`: boolean and
    /// capturing find, lookingAt, and matches, over the whole source and over ranges starting at
    /// 0, 1, and its middle and ending at or one byte before its end, with full, short, and null
    /// group buffers, count, rewrites, matcher loops over the source and a region, retaining every
    /// group or a prefix of them, and a matcher's lookingAt, matches, and find from 1.
    static List<String> operations(Re2 pattern, Slice source)
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
        RegexpMatcher matcher = pattern.matcher(source);
        results.add(matcher.lookingAt() + " " + matcher.matches());
        if (length > 0 && matcher.find(1)) {
            results.add(describe(matcher.toMatchResult()));
        }
        return results;
    }

    static String matches(RegexpMatcher matcher)
    {
        StringBuilder matches = new StringBuilder();
        while (matcher.find()) {
            matches.append(describe(matcher.toMatchResult()));
        }
        return matches.toString();
    }

    static String describe(MatchResult result)
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

    private static ScanPlan plan(String expression)
    {
        ParseResult parsed = RegexpParser.parse(utf8Slice(expression), Re2.Options.defaults().parseFlags());
        return ScanPlan.analyze(parsed.regexp(), parsed.capturingGroupCount(), ScanPlan.Language.RE2, false, parsed.caseFolding(), ByteFrequencies.uniform());
    }

    private static Slice witness(String expression)
    {
        return wrappedBuffer(PLAN_CASES.stream().filter(planCase -> planCase.expression().equals(expression)).findFirst().orElseThrow().witness());
    }

    /// Makes every search of the pattern's interpreted plan throw. A generated class holds the
    /// operations it needs, so it still answers.
    static void disableInterpretedPlan(Re2 pattern)
            throws ReflectiveOperationException
    {
        Field steps = ScanPlan.class.getDeclaredField("steps");
        steps.setAccessible(true);
        Arrays.fill((Object[]) steps.get(pattern.caseSensitiveScanPlan()), null);
    }

    private static byte[] concat(byte[]... parts)
    {
        DynamicSliceOutput output = new DynamicSliceOutput(16);
        for (byte[] part : parts) {
            output.writeBytes(part);
        }
        return output.slice().getBytes();
    }

    static byte[] bytes(int... values)
    {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }
}
