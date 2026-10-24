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

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.airlift.regulator.TestRe2ScanPlan.describe;
import static io.airlift.regulator.TestRe2ScanPlan.matches;
import static io.airlift.regulator.TestRe2ScanPlan.operations;
import static io.airlift.regulator.TestRe2ScanPlan.sources;
import static io.airlift.regulator.TestTrinoScanPlan.disableOrdinaryEngine;
import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java patterns on the scan plans that {@link TestRe2ScanPlan} and {@link TestTrinoScanPlan}
 * cover: which Java constructs reach a plan, and that a planned pattern gives the JDK's results on
 * valid UTF-8 and the ordinary engine's on malformed UTF-8.
 */
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
            // Ending before a final LF under UNIX_LINES.
            new PlanCase("(?d)^x([a-z]+)$", "xab"),
            new PlanCase("(?d)x([^/]+)/\\Z", "x/xéb/"));
    // Each end that UNIX_LINES lets a plan's end follow or refuse.
    private static final List<String> LINE_ENDS = List.of("", "\n", "\r\n", "\r", "\u2028");

    private record PlanCase(String expression, String witness) {}

    private record NamedRegexp(String name, JavaRegexp regexp) {}

    @Test
    public void testRouteSelection()
    {
        for (PlanCase planCase : PLAN_CASES) {
            assertThat(JavaRegexp.compile(utf8Slice(planCase.expression())).pattern().usesTrinoScanPlanForDiagnostics()).as(planCase.expression()).isTrue();
        }
        // A plan ends at the end of input or before a final LF. Java's $ and \Z also end before
        // other final terminators, and the default dot excludes some non-ASCII characters, so
        // those patterns keep the ordinary engine, as do multiline anchors, word boundaries,
        // reluctant runs, and folds with a non-ASCII member, such as K and the Kelvin sign.
        for (String expression : List.of(
                "^x([^/]+)/$",
                "^x([^/]+)/\\Z",
                "x([^/]+)/$",
                "^x([^/]+)/.*\\z",
                "(?m)^x([^/]+)/",
                "(?m)x([^/]+)/$",
                "\\bx([^/]+)/",
                "^x([^/]+?)/",
                "(?iu)^k([^/]+)/")) {
            assertThat(JavaRegexp.compile(utf8Slice(expression)).pattern().usesTrinoScanPlanForDiagnostics()).as(expression).isFalse();
        }
        assertThat(JavaRegexp.compileWithoutScanPlanForTesting(utf8Slice(PLAN_CASES.getFirst().expression()), JavaRegexp.Options.defaults()).pattern().usesTrinoScanPlanForDiagnostics()).isFalse();
    }

    @Test
    public void testPublicCallsRunThePlan()
    {
        JavaRegexp regexp = JavaRegexp.compile(utf8Slice("^https?://([^/]+)"));
        // Make ordinary program execution unable to succeed, so these calls pass only if they
        // run on the plan.
        disableOrdinaryEngine(regexp.pattern());
        Slice source = utf8Slice("https://höst/path");
        assertThat(regexp.find(source)).isTrue();
        assertThat(regexp.findResult(source).groupUtf8(1)).isEqualTo("höst");
        Re2Matcher matcher = regexp.matcher(source);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isEqualTo(utf8Slice("höst"));

        // A forced handoff continues every unanchored search on the ordinary engine before the
        // plan's first attempt, so with that engine unable to succeed it finds nothing.
        JavaRegexp handoff = JavaRegexp.compileForcingScanPlanHandoffForTesting(utf8Slice("x([^/]+)/"), JavaRegexp.Options.defaults());
        disableOrdinaryEngine(handoff.pattern());
        Slice handoffSource = utf8Slice("x/xéb/xc/");
        assertThat(handoff.find(handoffSource)).isFalse();
        assertThat(handoff.count(handoffSource)).isZero();
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
    }

    @Test
    public void testPlansMatchTheOrdinaryEngineOnMalformedInput()
    {
        // The JDK reads text, not bytes, so the ordinary engine decides malformed UTF-8.
        for (PlanCase planCase : PLAN_CASES) {
            Slice pattern = utf8Slice(planCase.expression());
            Re2 expected = JavaRegexp.compileWithoutScanPlanForTesting(pattern, JavaRegexp.Options.defaults()).pattern();
            List<NamedRegexp> planned = planned(planCase.expression());
            for (Slice source : sources(new Random(planCase.expression().hashCode()), planCase.witness().getBytes(UTF_8))) {
                List<String> expectedResults = operations(expected, source);
                for (NamedRegexp regexp : planned) {
                    assertThat(operations(regexp.regexp().pattern(), source)).as("%s %s on %s", regexp.name(), planCase.expression(), HexFormat.of().formatHex(source.getBytes())).isEqualTo(expectedResults);
                }
            }
        }
    }

    /**
     * Returns the pattern compiled with its plan, and with its plan forced to hand every
     * unanchored search to the ordinary engine.
     */
    private static List<NamedRegexp> planned(String expression)
    {
        Slice pattern = utf8Slice(expression);
        return List.of(
                new NamedRegexp("plan", JavaRegexp.compile(pattern)),
                new NamedRegexp("handoff", JavaRegexp.compileForcingScanPlanHandoffForTesting(pattern, JavaRegexp.Options.defaults())));
    }

    /**
     * Returns the witness with each line end appended, after a lead and doubled, and without its
     * last character.
     */
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

    /**
     * Returns boolean find, lookingAt, and matches; the same with every group; the matches of a
     * matcher loop over the input and over a region without its first and last characters; and
     * count, with UTF-8 byte offsets.
     */
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
