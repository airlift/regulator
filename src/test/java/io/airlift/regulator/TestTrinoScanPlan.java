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

import io.airlift.jcodings.specific.NonStrictUTF8Encoding;
import io.airlift.joni.Matcher;
import io.airlift.joni.Option;
import io.airlift.joni.Regex;
import io.airlift.joni.Region;
import io.airlift.joni.Syntax;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static io.airlift.slice.SliceUtf8.countCodePoints;
import static io.airlift.slice.SliceUtf8.lengthOfCodePointFromStartByte;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTrinoScanPlan
{
    private static final String URL = "^https?://(?:www\\.)?([^/]+)/.*$";
    // One admitted pattern for each scan executor. The template placeholder is capture group one.
    private static final List<ExecutorRoute> EXECUTOR_ROUTES = List.of(
            new ExecutorRoute(URL, "https://www.%s/path", true, false, false),
            new ExecutorRoute("^https?://([^/]+)/", "https://%s/path", true, true, false),
            new ExecutorRoute("https?://([^/]+)/", "x https://%s/ y", false, false, false),
            new ExecutorRoute("(?i)^ABC([^:]+):DEF$", "AbC%s:dEf", true, false, true),
            new ExecutorRoute("(?i)^ABC([^:]+):", "abc%s:rest", true, true, true),
            new ExecutorRoute("(?i)content-type:([^;]+);", "x Content-Type:%s; y", false, false, true));

    // Each failed attempt over the repeated units reads to the failure. Two matches follow it.
    private static final List<QuadraticSearch> QUADRATIC_SEARCHES = List.of(
            new QuadraticSearch("id=([^&]+)&x", "id=a", "&y", "id=b&xid=c&x"),
            new QuadraticSearch("x([^z]+)zq", "xa", "zr", "xbzqxczq"),
            new QuadraticSearch("(?i)x([^z]+)zq", "Xa", "zr", "xbZqXcZQ"),
            new QuadraticSearch("([^/]+)/([^!]+)!x", "a/", "!y!x", "/b/c!xd/e!x"));

    // Lone continuation bytes, truncated sequences, and a byte that never occurs in UTF-8.
    private static final List<byte[]> MALFORMED_SEQUENCES = List.of(
            new byte[] {(byte) 0x80},
            new byte[] {(byte) 0xBF},
            new byte[] {(byte) 0xC3},
            new byte[] {(byte) 0xE2, (byte) 0x82},
            new byte[] {(byte) 0xF0, (byte) 0x9F, (byte) 0x98},
            new byte[] {(byte) 0xFF});

    private record ExecutorRoute(String expression, String template, boolean anchoredStart, boolean partial, boolean folded)
    {
        String text(String group)
        {
            return template.formatted(group);
        }
    }

    private record QuadraticSearch(String expression, String unit, String failure, String matches)
    {
        Slice input(int units)
        {
            return view(unit.repeat(units) + failure + matches);
        }
    }

    private record ExpressionInput(String expression, String input) {}

    private record ExpressionTemplates(String expression, List<String> templates) {}

    private record PlanAdmission(String expression, boolean admitted) {}

    private record BooleanTailShape(String expression, String text, int attemptEnd) {}

    private record LiteralKernelShape(String expression, Re2.BooleanPartialMatchStrategy strategy, String text, String group) {}

    private record MalformedView(String expression, String template, boolean scanPlan) {}

    @Test
    public void testPublicCallsBypassTheSemanticProgram()
    {
        for (String expression : List.of(URL, "(?s)" + URL)) {
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(regexp.pattern().usesTrinoScanPlanForDiagnostics()).isTrue();
            // Make ordinary program execution unable to succeed; retaining a plan alone would
            // not make these public calls pass this test.
            disableOrdinaryEngine(regexp);
            Slice source = utf8Slice("https://www./path");
            assertThat(regexp.contains(source)).isTrue();
            assertThat(regexp.extract(source, 1)).isEqualTo(utf8Slice("www."));
            assertThat(regexp.replace(source, utf8Slice("$1"))).isEqualTo(utf8Slice("www."));
            TrinoRegexpMatcher matcher = regexp.matcher(source);
            assertThat(matcher.find()).isTrue();
            assertThat(matcher.group(1)).isEqualTo(utf8Slice("www."));
            TrinoRegexpMatcher boundaries = regexp.matcher(source, 0);
            assertThat(boundaries.find()).isTrue();
            assertThat(boundaries.end()).isEqualTo(source.length());
        }
    }

    @Test
    public void testLiteralWidthsAndPartialCaptureRetention()
    {
        for (int length : new int[] {1, 2, 3, 4, 7, 8, 9, 15, 16, 17, 255, 256}) {
            String prefix = "x".repeat(length - 1) + "y";
            verifyAgainstJoni("^" + prefix + "([^/]+)/$", List.of(prefix + "host/", prefix + "/", "x".repeat(length) + "host/"));
        }
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("^([^:]+):([^|]+)\\|([^/]+)/$"));
        TrinoRegexpMatcher matcher = regexp.matcher(utf8Slice("a:b|c/"), 1);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.groupCount()).isEqualTo(1);
        assertThat(matcher.group(1)).isEqualTo(utf8Slice("a"));
        assertThatThrownBy(() -> matcher.group(2)).isInstanceOf(IllegalArgumentException.class);
        Slice finalLf = utf8Slice("https://host/path\n");
        int[] groups = new int[4];
        // A range ending before the context end takes the ordinary engine; it is the control.
        assertThat(TrinoRegexp.compile(utf8Slice(URL)).pattern().findInto(finalLf, 0, finalLf.length() - 1, groups)).isTrue();
        assertThat(groups).containsExactly(0, finalLf.length() - 1, 8, 12);
        // The complete range reaches the plan, which must stop before the final LF.
        Re2 planOnly = TrinoRegexp.compile(utf8Slice(URL)).pattern();
        disableOrdinaryEngine(planOnly);
        assertThat(planOnly.findInto(finalLf, 0, finalLf.length(), groups)).isTrue();
        assertThat(groups).containsExactly(0, finalLf.length() - 1, 8, 12);
    }

    @Test
    public void testPlanUsesTheForwardMemoryBudget()
    {
        // The unanchored cases cover the literal-leading reservation and the run-leading
        // reservation that happens only after the lowered program is rejected.
        for (String expression : List.of("(?s)^([^/]+)/.*$", "^([a-z]+):([^/:]{2,4})/$", "^(?:(a)(b))?ab([^/]+)/$", "https?://([^/]+)/", "([^/]+)/")) {
            Slice pattern = utf8Slice(expression);
            ParseResult parsed = TrinoRegexpParser.parse(pattern, Regexp.LIKE_PERL);
            long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
            Prog control = Compiler.compileNormalized(Simplifier.simplify(parsed.regexp()), false, maxMemory - maxMemory / 3, Compiler.Dialect.TRINO);
            TrinoScanPlan plan = analyze(expression);
            assertThat(plan).isNotNull();
            Re2 compiled = TrinoRegexp.compile(pattern).pattern();
            assertThat(compiled.usesTrinoScanPlanForDiagnostics()).as(expression).isTrue();
            assertThat(compiled.forwardProgramForDiagnostics().dfaMemory())
                    .as(expression)
                    .isEqualTo(control.dfaMemory() - plan.estimatedRetainedSize());
        }
    }

    @Test
    public void testUrlUsesScanPlan()
    {
        for (String expression : List.of(URL, "(?s)" + URL)) {
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(regexp.pattern().usesTrinoScanPlanForDiagnostics()).isTrue();
            assertThat(regexp.extract(utf8Slice("https://www./path"), 1)).isEqualTo(utf8Slice("www."));
            assertThat(regexp.contains(utf8Slice("https://example.com/path"))).isTrue();
            assertThat(regexp.pattern().reverseProgramIfComputedForDiagnostics()).isNull();
            assertThat(regexp.pattern().forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).isNull();
        }
    }

    @Test
    public void testClickHouseAnchoredPartialPatternsUseScanPlan()
    {
        List<String> inputs = List.of(
                "http://example.com/path",
                "https://www.example.com/path?key=value",
                "https://例え.テスト/道?名前=値",
                "https://example.com",
                "https:///path",
                "ftp://example.com/path",
                "");
        for (String expression : List.of(
                "^https?://[^/]+/",
                "^https?://[^/]+/[^?]*\\?",
                "^https?://([^/]+)",
                "^https?://(?:www\\.)?([^/]+)/")) {
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(regexp.pattern().usesPartialTrinoScanPlanForDiagnostics()).as(expression).isTrue();
            verifyAgainstJoni(expression, inputs);
        }
        assertThat(TrinoRegexp.compile(utf8Slice(URL)).pattern().usesPartialTrinoScanPlanForDiagnostics()).isFalse();

        TrinoScanPlan plan = analyze("^https?://([^/]+)");
        assertThat(plan).isNotNull();
        assertThat(plan.isPartialMatch()).isTrue();
        assertThat(plan.operationsForDiagnostics()).isEqualTo("LITERAL,OPTIONAL,LITERAL,RUN");
    }

    @Test
    public void testAsciiCaseFoldEligibility()
    {
        List<String> eligible = List.of(
                "(?i)^ABC([a-h]+):DEF$",
                "(?i)^colou?r:([a-h]+)$",
                "(?i)content-type:([^;]+);",
                "(?i)^([^/]+)/$");
        List<String> inputs = List.of(
                "abcbag:def",
                "ABCBAG:DEF",
                "color:abc",
                "Colour:BAG",
                "prefix CONTENT-TYPE:text/plain; suffix",
                "content-type:例;",
                "Host/",
                "Kelvin/",
                "ſymbol/",
                "miss",
                "");
        for (String expression : eligible) {
            assertThat(TrinoRegexp.compile(utf8Slice(expression)).pattern().usesTrinoScanPlanForDiagnostics())
                    .as(expression).isTrue();
            verifyAgainstJoni(expression, inputs);
        }

        assertThat(scanOperations(eligible.getFirst()))
                .isEqualTo("ASCII_FOLDED_LITERAL,RUN,ASCII_FOLDED_LITERAL,END");
        assertThat(scanOperations(eligible.get(1)))
                .isEqualTo("ASCII_FOLDED_LITERAL,ASCII_FOLDED_OPTIONAL,ASCII_FOLDED_LITERAL,RUN,END");

        String alphabet = "abcdegh";
        for (int length : new int[] {1, 2, 4, 8, 9, 16, 17, 32, 128}) {
            StringBuilder literal = new StringBuilder(length);
            for (int index = 0; index < length; index++) {
                literal.append(alphabet.charAt(index % alphabet.length()));
            }
            String expression = "(?i)^" + literal + "([^/]+)/$";
            assertThat(TrinoRegexp.compile(utf8Slice(expression)).pattern().usesTrinoScanPlanForDiagnostics())
                    .as(expression).isTrue();
            verifyAgainstJoni(expression, List.of(literal.toString().toUpperCase(Locale.ROOT) + "Host/", literal + "例/", literal + "/", ""));
        }

        for (String expression : List.of(
                "(?i)^K([a-h]+):$",
                "(?i)^S([a-h]+):$",
                "(?i)^é([a-h]+):$",
                "(?i)^([a-z]+):$")) {
            assertThat(TrinoRegexp.compile(utf8Slice(expression)).pattern().usesTrinoScanPlanForDiagnostics())
                    .as(expression).isFalse();
            verifyAgainstJoni(expression, inputs, false);
        }
        verifyAgainstJoni("(?i)^K([a-h]+):$", List.of("Kabc:", "kBAG:", "Kabc:"), false);
        verifyAgainstJoni("(?i)^S([a-h]+):$", List.of("Sabc:", "sBAG:", "ſabc:"), false);
    }

    @Test
    public void testAsciiCaseFoldUsesSeparateExecutors()
    {
        for (String expression : List.of(
                "(?i)^ABC([a-h]+):DEF$",
                "(?i)^ABC([a-h]+)",
                "(?i)content-type:([^;]+);")) {
            assertThat(TrinoRegexp.compile(utf8Slice(expression)).pattern().usesAsciiFoldedTrinoScanExecutorForDiagnostics())
                    .as(expression)
                    .isTrue();
        }

        for (String expression : List.of(
                "^ABC([a-h]+):DEF$",
                "^ABC([a-h]+)",
                "content-type:([^;]+);",
                "(?i)^([a-h]+):$")) {
            assertThat(TrinoRegexp.compile(utf8Slice(expression)).pattern().usesAsciiFoldedTrinoScanExecutorForDiagnostics())
                    .as(expression)
                    .isFalse();
        }
    }

    @Test
    public void testAsciiFoldedPartialPlansAgainstJoni()
    {
        List<String> inputs = List.of(
                "abcbag",
                "ABCBAG:rest",
                "AbCdEfGhI",
                "abcbag\nabc",
                "abc",
                "abci",
                "xabcbag",
                "ABC例",
                "color:bag",
                "COLOUR:ABCdef!",
                "CoLoUr:",
                "colouur:abc",
                "colr:abc",
                "color:ab\n",
                "abch",
                "ABABch",
                "abababCHEF",
                "ababababch",
                "abab",
                "ab",
                "");
        for (String expression : List.of(
                "(?i)^ABC([a-h]+)",
                "(?i)^colou?r:([a-h]+)")) {
            Re2 pattern = TrinoRegexp.compile(utf8Slice(expression)).pattern();
            assertThat(pattern.usesPartialTrinoScanPlanForDiagnostics()).as(expression).isTrue();
            assertThat(pattern.usesAsciiFoldedTrinoScanExecutorForDiagnostics()).as(expression).isTrue();
            verifyAgainstJoni(expression, inputs);
        }

        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("(?i)^ABC([a-h]+)"));
        disableOrdinaryEngine(regexp);
        Slice source = utf8Slice("!aBcBaG:rest?").slice(1, "aBcBaG:rest".length());
        assertThat(regexp.contains(source)).isTrue();
        assertThat(regexp.extract(source, 1)).isEqualTo(utf8Slice("BaG"));
        assertThat(regexp.replace(source, utf8Slice("$1"))).isEqualTo(utf8Slice("BaG:rest"));
        TrinoRegexpMatcher matcher = regexp.matcher(source);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.end()).isEqualTo("aBcBaG".length());
        assertThat(matcher.find()).isFalse();
        assertThat(regexp.pattern().matches(utf8Slice("abcbag"))).isTrue();
        assertThat(regexp.pattern().matches(source)).isFalse();
        assertThat(regexp.pattern().lookingAt(source)).isTrue();
    }

    private static String scanOperations(String expression)
    {
        return requireNonNull(analyze(expression)).operationsForDiagnostics();
    }

    @Test
    public void testGeneratedAsciiCaseFoldPatternsAgainstJoni()
    {
        String alphabet = "abcdefghijlmnopqrtuvwxyz";
        for (long seed : new long[] {179, 181, 191}) {
            Random random = new Random(seed);
            int eligible = 0;
            for (int iteration = 0; iteration < 200; iteration++) {
                int prefixLength = 1 + random.nextInt(24);
                StringBuilder prefix = new StringBuilder(prefixLength);
                for (int index = 0; index < prefixLength; index++) {
                    prefix.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                boolean anchored = random.nextBoolean();
                boolean optional = random.nextBoolean();
                String expression = "(?i)" + (anchored ? "^" : "") + prefix + (optional ? "u?" : "") + "([a-h]{1,8}):END$";
                TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
                if (!regexp.pattern().usesTrinoScanPlanForDiagnostics()) {
                    continue;
                }
                eligible++;
                String witness = prefix + (optional ? "u" : "") + "bag:end";
                String alternatingCase = alternatingAsciiCase(witness);
                verifyAgainstJoni(expression, List.of(
                        witness,
                        witness.toUpperCase(Locale.ROOT),
                        alternatingCase,
                        anchored ? "lead" + alternatingCase : "x".repeat(64) + alternatingCase,
                        witness.substring(0, witness.length() - 1),
                        prefix + ":end",
                        ""));
            }
            assertThat(eligible).as("eligible folded patterns for seed %s", seed).isGreaterThan(100);
        }
    }

    private static String alternatingAsciiCase(String value)
    {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            result.append((index & 1) == 0 ? Character.toUpperCase(character) : Character.toLowerCase(character));
        }
        return result.toString();
    }

    @Test
    public void testAnchoredPartialMatchModes()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("^https?://([^/]+)/"));
        Re2Matcher matcher = regexp.pattern().matcher(utf8Slice("https://host/path"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isZero();
        assertThat(matcher.end()).isEqualTo("https://host/".length());
        assertThat(matcher.group(1)).isEqualTo(utf8Slice("host"));

        assertThat(matcher.reset(utf8Slice("https://host/path")).lookingAt()).isTrue();
        assertThat(matcher.end()).isEqualTo("https://host/".length());
        assertThat(matcher.reset(utf8Slice("https://host/path")).matches()).isFalse();
        assertThat(matcher.reset(utf8Slice("https://host/")).matches()).isTrue();

        Slice regionInput = utf8Slice("!https://host/path?");
        assertThat(matcher.reset(regionInput, 1, regionInput.length() - 1).find()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.end()).isEqualTo(1 + "https://host/".length());
        assertThat(matcher.reset(regionInput, 1, regionInput.length() - 2).lookingAt()).isTrue();

        TrinoRegexp optionalRegexp = TrinoRegexp.compile(utf8Slice("^(a)?a"));
        assertThat(optionalRegexp.pattern().usesPartialTrinoScanPlanForDiagnostics()).isTrue();
        Re2Matcher optionalMatcher = optionalRegexp.pattern().matcher(utf8Slice("a"));
        assertThat(optionalMatcher.matches()).isTrue();
        assertThat(optionalMatcher.start(1)).isEqualTo(-1);
        assertThat(optionalMatcher.end(1)).isEqualTo(-1);
        assertThat(optionalMatcher.reset(utf8Slice("aX")).find()).isTrue();
        assertThat(optionalMatcher.end()).isEqualTo(1);
        assertThat(optionalMatcher.reset(utf8Slice("aX")).matches()).isFalse();

        TrinoRegexp lineRegexp = TrinoRegexp.compile(utf8Slice("^x.*"));
        assertThat(lineRegexp.pattern().usesPartialTrinoScanPlanForDiagnostics()).isTrue();
        Re2Matcher lineMatcher = lineRegexp.pattern().matcher(utf8Slice("xabc\nrest"));
        assertThat(lineMatcher.find()).isTrue();
        assertThat(lineMatcher.end()).isEqualTo(4);
        assertThat(lineMatcher.reset(utf8Slice("xabc\nrest")).matches()).isFalse();

        Re2Matcher dotAllMatcher = TrinoRegexp.compile(utf8Slice("(?s)^x.*"))
                .pattern()
                .matcher(utf8Slice("xabc\nrest"));
        assertThat(dotAllMatcher.find()).isTrue();
        assertThat(dotAllMatcher.end()).isEqualTo("xabc\nrest".length());
    }

    @Test
    public void testUnanchoredSearchPlans()
    {
        List<String> inputs = List.of(
                "https://host/path",
                "prefix https://host/path suffix",
                "prefix http://www.example.com/ suffix",
                "prefix https://例え.テスト/道 suffix",
                "99a",
                "99b99a",
                "prefix 99a suffix",
                "99a99a",
                "99b 99a 1a",
                "https://one/ http://two/",
                "https:/ http:// https://host/",
                "9".repeat(4096),
                "https://missing-slash",
                "ftp://host/path",
                "");
        for (String expression : List.of(
                "https?://([^/]+)/",
                "https?://([^/]+)/.*$")) {
            verifyAgainstJoni(expression, inputs);
        }
        // A leading run longer than one character keeps the ordinary engine.
        verifyAgainstJoni("([0-9]+)a", inputs, false);
        verifyAgainstJoni("([0-9]+)a$", inputs, false);

        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("https?://([^/]+)/"));
        assertThat(regexp.pattern().usesTrinoScanPlanForDiagnostics()).isTrue();
        Re2Matcher matcher = regexp.pattern().matcher(utf8Slice("x https://one/ y http://two/ z"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(2);
        assertThat(matcher.group(1)).isEqualTo(utf8Slice("one"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(17);
        assertThat(matcher.group(1)).isEqualTo(utf8Slice("two"));
        assertThat(matcher.find()).isFalse();

        assertThat(regexp.pattern().lookingAt(utf8Slice("x https://host/"))).isFalse();
        assertThat(regexp.pattern().lookingAt(utf8Slice("https://host/ path"))).isTrue();
        assertThat(regexp.pattern().matches(utf8Slice("https://host/"))).isTrue();
        assertThat(regexp.pattern().matches(utf8Slice("https://host/path"))).isFalse();
    }

    @Test
    public void testRunLeadingUnanchoredAdmission()
    {
        // An unanchored plan led by a run longer than one character is admitted only when the
        // run is unbounded and holds every byte except the one-byte literal that follows it.
        List<PlanAdmission> admissions = List.of(
                new PlanAdmission("(\\w+)@(\\w+)", false),
                new PlanAdmission("([0-9]+)a", false),
                new PlanAdmission("(\\d+)zz", false),
                new PlanAdmission("(\\d+) ", false),
                new PlanAdmission("([a-z]+)-([0-9]+)", false),
                new PlanAdmission("([^/x]+)/", false),
                new PlanAdmission("(?i)([a-h]+)x", false),
                // The run stops at the delimiter, but the literal is longer than that byte.
                new PlanAdmission("([^/]+)/foo", false),
                new PlanAdmission("(?i)([^/]+)/x", false),
                // Never admitted: no capture, a bounded leading run, and a folded class with
                // non-ASCII members.
                new PlanAdmission("\\d+x", false),
                new PlanAdmission("([0-9]{1,5})a", false),
                new PlanAdmission("(?i)([a-z]+)x", false),
                new PlanAdmission("([^/]+)/", true),
                new PlanAdmission("([^/]*)/", true),
                new PlanAdmission("(?s)([^/]+)/", true),
                new PlanAdmission("([^:]+):([0-9]+)", true),
                new PlanAdmission("([^,]{2,}),", true),
                new PlanAdmission("(?i)([^/]+)/(x)", true),
                new PlanAdmission("https?://(?:www\\.)?([^/]+)/", true),
                new PlanAdmission("(?i)content-type:([^;]+);", true),
                new PlanAdmission("^(\\d+)zz", true));
        // The complement runs hold line feeds and non-ASCII text.
        List<String> inputs = List.of(
                "x@y ab@cd",
                "77a 1 22zz",
                "12 x",
                "ab-12 c-",
                "a\nb/c",
                "é例/x/foo",
                "/x/",
                "host:80 例え:12 a:",
                "ab,,c,é\n,",
                "?k=v&é=1",
                "x https://www.例え/ y",
                "Content-Type:a; CONTENT-TYPE:b\n;",
                "12zz",
                "abcX 12x");
        for (PlanAdmission admission : admissions) {
            assertThat(analyze(admission.expression()) != null).as(admission.expression()).isEqualTo(admission.admitted());
            verifyAgainstJoni(admission.expression(), inputs, admission.admitted());
        }
    }

    @Test
    public void testUnanchoredSearchBypassesSemanticProgram()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("https?://([^/]+)/"));
        assertThat(regexp.pattern().usesTrinoScanPlanForDiagnostics()).isTrue();
        assertThat(regexp.pattern().createCandidateStartCursor()).isNull();
        assertThat(regexp.pattern().createGroupZeroForwardCursor()).isNull();
        disableOrdinaryEngine(regexp);

        Slice source = utf8Slice("!prefix https://host/path?").slice(1, "prefix https://host/path".length());
        assertThat(regexp.contains(source)).isTrue();
        assertThat(regexp.extract(source, 1)).isEqualTo(utf8Slice("host"));
        assertThat(regexp.replace(source, utf8Slice("$1"))).isEqualTo(utf8Slice("prefix hostpath"));
        TrinoRegexpMatcher matcher = regexp.matcher(source);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo("prefix ".length());
        assertThat(matcher.end()).isEqualTo("prefix https://host/".length());
        assertThat(matcher.group(1)).isEqualTo(utf8Slice("host"));

        TrinoRegexpMatcher boundaries = regexp.matcher(source, 0);
        assertThat(boundaries.find()).isTrue();
        assertThat(boundaries.start()).isEqualTo("prefix ".length());
        assertThat(boundaries.end()).isEqualTo("prefix https://host/".length());
    }

    @Test
    public void testUnanchoredSearchDoesNotStartInsideUtf8CodePoint()
    {
        // Non-ASCII member bytes start candidates, including after ASCII bytes the scan skips.
        // The other leading runs keep the ordinary engine.
        List<PlanAdmission> admissions = List.of(
                new PlanAdmission("([^/]{2,})/", true),
                new PlanAdmission("(?i)([^/]{2,})/(a)", true),
                new PlanAdmission("([^\\x00-\\x7F]{2,})a", false),
                new PlanAdmission("([^\\x00-\\x7F]+)a", false),
                new PlanAdmission("([^/]{2,})/a", false),
                new PlanAdmission("(?i)([^/]{2,})/a", false));
        for (PlanAdmission admission : admissions) {
            verifyAgainstJoni(admission.expression(), List.of("€a", "😀a", "1€a", "é€a", "1é€a", "/€a", "//😀/a", "é/A", "1/例え/a"), admission.admitted());
        }
    }

    @Test
    public void testLiteralLeadingSearchSkipsToWholeLiteral()
    {
        // Each other h would otherwise start its own failed attempt. The candidate scan finds
        // the whole leading literal, so only the two http occurrences are attempted.
        String input = "hhh: the thatch hath http hash, which https heath had h";
        assertThat(input.chars().filter(value -> value == 'h').count()).isGreaterThan(10);
        assertThat(candidateAttempts("https?://(?:www\\.)?([^/]+)/", input)).isEqualTo(2);
        assertThat(candidateAttempts("https?://([^/]+)/", input)).isEqualTo(2);
        // The ASCII-folded executor finds a case-sensitive leading literal the same way.
        String folded = "https?://(?i:www\\.)?([^/]+)/";
        assertThat(TrinoRegexp.compile(utf8Slice(folded)).pattern().usesAsciiFoldedTrinoScanExecutorForDiagnostics()).isTrue();
        assertThat(candidateAttempts(folded, input)).isEqualTo(2);
        // Overlapping occurrences each start an attempt, and a partial literal at the end starts none.
        assertThat(candidateAttempts("aab([0-9]+)", "aaab aab aaa aa")).isEqualTo(2);
    }

    @Test
    public void testLiteralLeadingSearchAgainstJoni()
    {
        // Overlapping and partial literal occurrences, literals that straddle a Slice or matcher
        // region boundary, multibyte literals and text, and malformed bytes.
        List<String> expressions = List.of(
                "https?://(?:www\\.)?([^/]+)/",
                "https?://([^/]+)/",
                "https?://(?i:www\\.)?([^/]+)/",
                "aab([0-9]*)",
                "(aa)b([0-9]*)",
                "例え([^/]+)/",
                "é([a-z]+)");
        List<Slice> sources = new ArrayList<>(randomSources(
                233,
                List.of("a", "aa", "aab", "b", "h", "t", "p", "s", "http", "https://", "://", "www.", "WWW.", "/", "0", "9", "é", "例", "え", "例え"),
                // A lone continuation byte, truncated sequences of the text's own characters, and
                // a byte that never occurs in UTF-8.
                List.of(new byte[] {(byte) 0x80}, new byte[] {(byte) 0xC3}, new byte[] {(byte) 0xE4, (byte) 0xBE}, new byte[] {(byte) 0xFF})));
        // Each cut leaves a literal straddling the end of one Slice and the start of the other.
        List<String> texts = List.of("aaab aab9 aa", "x http://h/ https://www.h/ htt", "例え例/ é例え/x/ 例", "éab éé éz");
        for (String text : texts) {
            Slice whole = utf8Slice(text);
            for (int cut = 0; cut <= whole.length(); cut++) {
                sources.add(whole.slice(0, cut));
                sources.add(whole.slice(cut, whole.length() - cut));
            }
        }
        for (String expression : expressions) {
            verifySourcesAgainstJoni(expression, sources, true);
            for (String text : texts) {
                verifyRegionsAgainstJoni(expression, utf8Slice(text));
            }
        }
    }

    /**
     * Compares {@link Re2Matcher#find()} over every region of {@code source} that starts and ends
     * on a character boundary with Joni over the same bytes. Each region is also searched with
     * a plan that continues every unanchored search on the ordinary engine, whose context must
     * be the region.
     */
    private static void verifyRegionsAgainstJoni(String expression, Slice source)
    {
        Slice pattern = utf8Slice(expression);
        Re2Matcher planMatcher = TrinoRegexp.compile(pattern).pattern().matcher(EMPTY_SLICE);
        Re2Matcher handoffMatcher = TrinoRegexp.compileForcingScanPlanHandoffForTesting(pattern).pattern().matcher(EMPTY_SLICE);
        Regex oracle = joniPattern(expression);
        for (int start = 0; start <= source.length(); start++) {
            for (int end = start; end <= source.length(); end++) {
                if ((start < source.length() && (source.getByte(start) & 0xC0) == 0x80) || (end < source.length() && (source.getByte(end) & 0xC0) == 0x80)) {
                    continue;
                }
                String description = "%s on [%s, %s) of %s".formatted(expression, start, end, source.toStringUtf8());
                verifyRegionAgainstJoni(oracle, planMatcher, source, start, end, description);
                verifyRegionAgainstJoni(oracle, handoffMatcher, source, start, end, description + " after handoff");
            }
        }
    }

    /**
     * Compares {@link Re2Matcher#find()} over {@code [start, end)} of {@code input} with Joni over
     * the same bytes, on the plan and on a plan that continues every unanchored search on the
     * ordinary engine.
     */
    private static void verifyRegionAgainstJoni(String expression, Slice input, int start, int end)
    {
        Slice pattern = utf8Slice(expression);
        Regex oracle = joniPattern(expression);
        String description = "%s on [%s, %s)".formatted(expression, start, end);
        verifyRegionAgainstJoni(oracle, TrinoRegexp.compile(pattern).pattern().matcher(input), input, start, end, description);
        verifyRegionAgainstJoni(oracle, TrinoRegexp.compileForcingScanPlanHandoffForTesting(pattern).pattern().matcher(input), input, start, end, description + " after handoff");
    }

    /**
     * Compares every match {@code matcher} finds in {@code [start, end)} of {@code input}, with
     * the bounds of every group, to Joni's matches over the same bytes.
     */
    private static void verifyRegionAgainstJoni(Regex oracle, Re2Matcher matcher, Slice input, int start, int end, String description)
    {
        matcher.reset(input, start, end);
        for (int[] match : joniMatches(oracle, input.slice(start, end - start))) {
            assertThat(matcher.find()).as(description).isTrue();
            for (int group = 0; group <= oracle.numberOfCaptures(); group++) {
                assertThat(matcher.start(group)).as("%s, group %s", description, group).isEqualTo(match[2 * group] < 0 ? -1 : start + match[2 * group]);
                assertThat(matcher.end(group)).as("%s, group %s", description, group).isEqualTo(match[2 * group + 1] < 0 ? -1 : start + match[2 * group + 1]);
            }
        }
        assertThat(matcher.find()).as(description).isFalse();
    }

    @Test
    public void testRunLeadingUnanchoredPlansAgainstJoni()
    {
        List<String> inputs = new ArrayList<>(List.of(
                "The quick brown fox jumps over the lazy dog 12345 again ok",
                "77zz",
                "a77zz",
                "7z7zz",
                "1 22 333zz",
                "12345",
                "zz",
                "",
                "99a",
                "é99a",
                "😀1a2a",
                "例え123a",
                "9b9 9a",
                "x@y",
                "ab@cd ef@",
                "@x",
                "x@",
                "é@x",
                "例@え",
                "a@b@c",
                "ab:",
                "a:ab:",
                "abc",
                "a:",
                "cab:é:",
                "c:bb:",
                "host/",
                "例え/道/",
                "/",
                "//a/",
                "é😀/",
                "éx",
                "é😀x",
                "aéx",
                "例えx",
                "xx",
                "é",
                "😀😀😀x",
                "77ZZ",
                "1Zz",
                "ABx",
                "aBX",
                "é/X",
                "HOST/x"));
        // Short member runs, runs shorter than the minimum, and multibyte text between them.
        String[] symbols = {"1", "7", "a", "b", "c", "z", "Z", "x", "X", ":", "@", "/", " ", "é", "😀", "例", "\n"};
        Random random = new Random(181);
        for (int index = 0; index < 12; index++) {
            StringBuilder noise = new StringBuilder();
            for (int length = random.nextInt(48); length > 0; length--) {
                noise.append(symbols[random.nextInt(symbols.length)]);
            }
            inputs.add(noise.toString());
        }
        for (String expression : List.of(
                "([^/]+)/",
                "([^/]{2,})/")) {
            verifyAgainstJoni(expression, inputs);
        }
        // Other leading runs keep the ordinary engine.
        for (String expression : List.of(
                "(\\d+)zz",
                "([0-9]+)a",
                "(\\w+)@(\\w+)",
                "([a-c]{2,}):",
                "([^\\x00-\\x7F]+)x",
                "([^\\x00-\\x7F]{2,})x",
                "(?i)(\\d+)zz",
                "(?i)([a-c]{2,})x",
                "(?i)([^/]+)/x")) {
            verifyAgainstJoni(expression, inputs, false);
        }
        // Arbitrary non-ASCII sets have no byte-set run and keep the ordinary engine.
        verifyAgainstJoni("([é-ü]+)x", inputs, false);
    }

    private static int candidateAttempts(String expression, String input)
    {
        Re2 pattern = TrinoRegexp.compile(utf8Slice(expression)).pattern();
        assertThat(pattern.usesTrinoScanPlanForDiagnostics()).as(expression).isTrue();
        return requireNonNull(analyze(expression)).candidateAttemptsForDiagnostics(utf8Slice(input));
    }

    @Test
    public void testCaptureFreeLiteralPatternsKeepExistingRoute()
    {
        for (String expression : List.of("^foo", "^foo$", "^foo(?:bar)?")) {
            assertThat(TrinoRegexp.compile(utf8Slice(expression)).pattern().usesTrinoScanPlanForDiagnostics())
                    .as(expression)
                    .isFalse();
        }

        verifyAgainstJoni("^foo[0-9]+$", List.of("foo1", "foo123", "foo", "foo12x", "foo1\n"));
    }

    @Test
    public void testLinearPlansUseBuilderCompaction()
    {
        for (String expression : List.of(URL, "(?s)" + URL)) {
            TrinoScanPlan plan = analyze(expression);
            assertThat(plan).isNotNull();
            assertThat(plan.operationsForDiagnostics()).isEqualTo(expression.startsWith("(?s)")
                    ? "LITERAL,OPTIONAL,LITERAL,OPTIONAL,DELIMITED_CAPTURE,DOT_ALL_TAIL,END"
                    : "LITERAL,OPTIONAL,LITERAL,OPTIONAL,DELIMITED_CAPTURE,LINE_TAIL,END");
        }
        TrinoScanPlan plan = analyze("^[a-z]?:([^/]+)/$");
        assertThat(plan).isNotNull();
        assertThat(plan.operationsForDiagnostics()).isEqualTo("RUN,LITERAL,DELIMITED_CAPTURE,END");
        verifyAgainstJoni("^([^/]+)/(?:([a-z]+):)?end$", List.of("host/value:end", "host/end", "host/value!end", "/end"));
    }

    @Test
    public void testUrlAgainstJoni()
    {
        List<String> inputs = new ArrayList<>();
        for (String scheme : List.of("http://", "https://", "ftp://", "", "https:/")) {
            for (String host : List.of("example.com", "www.example.com", "www.", "", "例え.テスト", "a\nb", "a\u0000b")) {
                for (String path : List.of("", "/", "/path", "/a\nb", "/path\n", "/\n\n", "/道/😀", "/\r", "/\u0085\u2028\u2029")) {
                    inputs.add(scheme + host + path);
                }
            }
        }
        inputs.add("https://" + "a".repeat(4096) + "/path");
        inputs.add("https://host/" + "a".repeat(4096));
        for (String expression : List.of(URL, "(?s)" + URL, URL.substring(0, URL.length() - 1) + "\\z")) {
            verifyAgainstJoni(expression, inputs);
        }
    }

    @Test
    public void testRetriesAndMultipleCaptures()
    {
        List<String> expressions = List.of(
                "^(?:a)?(?:ab)?([^/]+)/end$",
                "^(?:a)?([^/]+)/z$",
                "^(?:a)?(?:b)?(?:c)?(?:d)?([^/]+)/end$",
                "^(?:aa)?([^/]*)/END$",
                "^(?<first>[^:]*)::(?:x)?(?<second>[^|]+)\\|(?<third>[^/]*)/$",
                "^é(?:😀)?([^/]+)/.*$",
                "^" + "x".repeat(256) + "([^/]+)/$",
                "^([^\u0000]+)\u0000$");
        List<String> inputs = new ArrayList<>(List.of(
                "a/end",
                "ab/end",
                "abcd/end",
                "abc/end",
                "aa/END",
                "/END",
                "aa/wrong",
                "a/z",
                "a/no",
                "x::xx|z/",
                "::x|/",
                "::x|/\n",
                "é😀host/path",
                "éhost/\n",
                "é😀/path",
                "é\n/",
                "x".repeat(256) + "host/",
                "host\u0000"));
        Random random = new Random(119);
        String[] symbols = {"a", "b", "c", "d", "x", "/", "\n", ":", "|", "é", "😀", "\u0000"};
        for (int iteration = 0; iteration < 1000; iteration++) {
            StringBuilder input = new StringBuilder();
            int length = random.nextInt(30);
            for (int index = 0; index < length; index++) {
                input.append(symbols[random.nextInt(symbols.length)]);
            }
            inputs.add(input + "/end");
        }
        for (String expression : expressions) {
            verifyAgainstJoni(expression, inputs);
        }
    }

    @Test
    public void testByteSetsAndBoundedRepetitions()
    {
        List<String> expressions = List.of(
                "^([a-z]+):$",
                "^([ab]*):$",
                "^([^/:]+):.*$",
                "(?s)^([^/:]{2,4}):.*$",
                "^([a-z0-9_]{2,8}):$",
                "^([0-9]{2})[0-9]{2}:$",
                "^[A-Z]{2,4}:([0-9]+);$",
                "^x([a-z]*)$",
                "^x([^/:]{2,})$",
                "^x([^/:]{0,3})$",
                "^([^/:]{0}):$",
                "^x([\\x00-\\x7F]+)$",
                "^x([\\x00-\\x7F]{2,4})$",
                "^(?:ab)?([abc]{1,4}):([^/]+)/$",
                "^x([^\\n]{1,3})$",
                "^x([\\s\\S]{1,3})$",
                "^([a-z]+):[^/]{2,4}/$",
                "^a{2,4}:([bc]+);$");
        List<String> inputs = new ArrayList<>(List.of(
                "a:",
                "ab:",
                ":",
                "abcd:",
                "abcdefgh:",
                "abcdefghi:",
                "1234:",
                "AB:123;",
                "ABCDE:123;",
                "ABCD:1;",
                "aa:bc;",
                "aaaaa:bc;",
                "abc:host/",
                "ababc:host/",
                "abc:é😀/",
                "abc:例え😀/",
                "ab:path",
                "é😀:path",
                "é:tail",
                "例え😀界:tail",
                "例え😀界道:tail",
                "x",
                "xa",
                "xabc",
                "xabcd",
                "xé",
                "xé😀",
                "x例え😀",
                "x例え😀界",
                "x\n",
                "xé\n",
                "xé😀\n",
                "xé😀\n\n",
                "xabc\n",
                "xa\u0000b",
                "x\u007F"));
        Random random = new Random(127);
        String[] symbols = {"a", "b", "c", "x", "1", "/", ":", ";", "\n", "é", "😀", "\u0000"};
        for (int iteration = 0; iteration < 500; iteration++) {
            StringBuilder input = new StringBuilder();
            for (int remaining = random.nextInt(24); remaining > 0; remaining--) {
                input.append(symbols[random.nextInt(symbols.length)]);
            }
            inputs.add(input + ":");
            inputs.add("x" + input);
        }
        for (String expression : expressions) {
            verifyAgainstJoni(expression, inputs);
            verifyAgainstJoni(expression.substring(0, expression.length() - 1) + "\\z", inputs);
        }
    }

    @Test
    public void testByteSetVectorBoundariesAndCharacterCounts()
    {
        for (String set : List.of("[a]", "[ab]", "[abc]", "[abcd]", "[a-z0-9_]", "[^/]", "[^/:]", "[^/:?]", "[^/:?#]", "[^/:?#;]")) {
            for (int length : new int[] {0, 1, 2, 3, 4, 15, 16, 17, 31, 32, 33, 999, 1000, 1001}) {
                List<String> inputs = List.of(
                        "x" + "a".repeat(length) + "/",
                        "x" + "a".repeat(length) + "//",
                        "x" + "é".repeat(length) + "/",
                        "x" + "😀".repeat(length) + "/",
                        "x" + "a".repeat(length) + "\n");
                verifyAgainstJoni("^x(" + set + "{2,1000})/$", inputs);
                verifyAgainstJoni("^x(" + set + "*)/$", inputs);
            }
        }
        verifyAgainstJoni("^x([a-z]?)$", List.of("x", "xa", "xab", "x\n"));
        verifyAgainstJoni("^[a-z]?:([^/]+)/$", List.of(":host/", "a:host/", "ab:host/"));
        verifyAgainstJoni("^([^/]+)/.+$", List.of("host/a", "host/", "host/é\n", "host/a\nb"));
        verifyAgainstJoni("^([^/]+)/[^x]*$", List.of("host/a", "host/", "host/é\n", "host/x"));
    }

    @Test
    public void testByteSetRegionsAndMalformedInput()
    {
        String expression = "^([a-z]{2,4}):([^/:]{2,4})/$";
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
        Slice input = utf8Slice("!abc:é😀/\n?");
        Re2Matcher matcher = regexp.pattern().matcher(input).reset(input, 1, input.length() - 1);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(2)).isEqualTo(utf8Slice("é😀"));
        assertThat(matcher.find()).isFalse();
        assertThat(matcher.reset(input, 1, input.length() - 1).matches()).isFalse();
        assertThat(matcher.reset(input, 1, input.length() - 2).matches()).isTrue();
        for (int badByte = 128; badByte < 256; badByte++) {
            for (int length : new int[] {1, 2, 3, 15, 16, 17, 32}) {
                byte[] bytes = utf8Slice("!abc:" + "a".repeat(length) + "/?").getBytes();
                bytes[bytes.length - 3] = (byte) badByte;
                Slice source = wrappedBuffer(bytes, 1, bytes.length - 2);
                verifyMalformedLifecycle(regexp, source, expression + " on " + HexFormat.of().formatHex(source.getBytes()));
                TrinoRegexpMatcher reusable = regexp.matcher(source);
                reusable.find();
                assertThat(reusable.find()).isFalse();
            }
        }
    }

    @Test
    public void testByteSetPublicCallsBypassTheSemanticProgram()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("^([a-z0-9_]{2,8}):([^/:]{2,4})/$"));
        assertThat(regexp.pattern().usesTrinoScanPlanForDiagnostics()).isTrue();
        disableOrdinaryEngine(regexp);
        Slice input = utf8Slice("abc_1:é😀/");
        assertThat(regexp.contains(input)).isTrue();
        assertThat(regexp.extract(input, 2)).isEqualTo(utf8Slice("é😀"));
        assertThat(regexp.replace(input, utf8Slice("$2"))).isEqualTo(utf8Slice("é😀"));
        assertThat(regexp.matcher(input, 0).find()).isTrue();
        assertThat(regexp.contains(utf8Slice("abc_1:é/"))).isFalse();
        assertThat(regexp.contains(utf8Slice("abc_1:é😀界道語/"))).isFalse();
    }

    @Test
    public void testRichCaptureRunsKeepBoundaryOperations()
    {
        String expression = "^(?:(?<key>[a-z]+):(?<value>[^/]+))?/end$";
        TrinoScanPlan plan = analyze(expression);
        assertThat(plan).isNotNull();
        assertThat(plan.operationCountForDiagnostics()).isEqualTo(10);
        assertThat(plan.operationsForDiagnostics()).isEqualTo("FORK,SAVE,RUN,SAVE,LITERAL,SAVE,RUN,SAVE,LITERAL,END");
        verifyAgainstJoni(expression, List.of("key:value/end", "/end", "key:/end", "key:例え😀/end", "key:value/end\n"));
        verifyAgainstJoni("^x([a-z]?)a$", List.of("xa", "xaa", "xba", "xbba", "xa\n"));
        verifyAgainstJoni("^x([a-z]{0,1})a$", List.of("xa", "xaa", "xba", "xbba", "xa\n"));
        verifyAgainstJoni("^x(?:(?<host>[^/]{2,4})/)?end$", List.of("xend", "xé😀/end", "xé/end", "x例え😀界/end", "x例え😀界道/end"));
    }

    @Test
    public void testRunCapturesAfterFailedAttempts()
    {
        // The optional run can fail its minimum, or finish and fail at the colon.
        // Skipping it must clear both bounds, including inside an enclosing capture.
        for (String expression : List.of(
                "^x(?:([a-z]{2,4}):)?([a-z]+);$",
                "^x(?:(([a-z]{2,4}):))?([a-z]+);$",
                "^x(?:([a-z]*):)?([a-z]+);$",
                "^x(?:([^/:;]{2,4}):)?([^/:;]+);$")) {
            verifyAgainstJoni(expression, List.of("xab:cd;", "xa;", "xab;", "x:ab;", "xab!", "xé😀;", "xé😀:界;", "xa;\n"));
        }
        // Retrying an optional prefix revisits the same run at a different cursor.
        verifyAgainstJoni("^(?:ab)?([a-z]{2,4}):$", List.of("abcd:", "abc:", "ab:", "a:", "abc!"));

        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("^x(?:([a-z]{2,4}):)?([a-z]+);$"));
        assertThat(regexp.pattern().usesTrinoScanPlanForDiagnostics()).isTrue();
        disableOrdinaryEngine(regexp);
        for (int retainedGroups = 0; retainedGroups <= 2; retainedGroups++) {
            int[] groups = new int[2 * (retainedGroups + 1)];
            for (int iteration = 0; iteration < 3; iteration++) {
                for (String text : List.of("xab:cd;", "xa;", "xab;", "xab!")) {
                    Slice input = utf8Slice("!" + text + "?").slice(1, text.length());
                    boolean found = regexp.pattern().findInto(input, groups);
                    if (text.equals("xab!")) {
                        assertThat(found).isFalse();
                        assertThat(groups).containsOnly(-1);
                        continue;
                    }
                    assertThat(found).isTrue();
                    assertThat(groups[0]).isZero();
                    assertThat(groups[1]).isEqualTo(text.length());
                    boolean optional = text.equals("xab:cd;");
                    if (retainedGroups >= 1) {
                        assertThat(groups[2]).isEqualTo(optional ? 1 : -1);
                        assertThat(groups[3]).isEqualTo(optional ? 3 : -1);
                    }
                    if (retainedGroups == 2) {
                        assertThat(groups[4]).isEqualTo(optional ? 4 : 1);
                        assertThat(groups[5]).isEqualTo(text.length() - 1);
                    }
                }
            }
        }
    }

    @Test
    public void testOptionalCaptureRoutingAndClearing()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("^(?:(a)(b))?ab([^/]+)/$"));
        assertThat(regexp.pattern().usesTrinoScanPlanForDiagnostics()).isTrue();
        disableOrdinaryEngine(regexp);
        Slice input = utf8Slice("abhost/");
        assertThat(regexp.contains(input)).isTrue();
        TrinoRegexpMatcher matcher = regexp.matcher(input);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isNull();
        assertThat(matcher.group(2)).isNull();
        assertThat(matcher.group(3)).isEqualTo(utf8Slice("host"));
        assertThat(regexp.extract(input, 1)).isNull();
        assertThat(regexp.replace(input, utf8Slice("$1-$2-$3"))).isEqualTo(utf8Slice("--host"));
        assertThat(regexp.matcher(input, 0).find()).isTrue();
        assertThat(matcher.reset(utf8Slice("ababhost/")).find()).isTrue();
        assertThat(matcher.group(1)).isEqualTo(utf8Slice("a"));
        assertThat(matcher.group(2)).isEqualTo(utf8Slice("b"));
        assertThat(matcher.reset(input).find()).isTrue();
        assertThat(matcher.start(1)).isEqualTo(-1);
        assertThat(matcher.end(2)).isEqualTo(-1);
    }

    @Test
    public void testOptionalBodiesAndNestedCapturesAgainstJoni()
    {
        List<String> expressions = List.of(
                "^https?://(www\\.)?([^/:]+)(?::([0-9]{1,5}))?/.*$",
                "^((?:a(b)?c)?)([^/]+)/$",
                "^(?:(a)(b))?ab([^/]+)/$",
                "^(?:(a)(?:(b)(c))?)?abc([^/]+)/$",
                "^(?<outer>pre(?:(?<key>[a-z]+):(?<value>[^/]+))?)/end$",
                "^x(?:(a?)())?$",
                "^(?:a(?:b(?:c(d)?)?)?)?([^/]+)/$",
                "^(?:(é)(😀))?é([^/]+)/$",
                "^(([^/]+))/$",
                "^(x)?([^/]+)/$",
                "^(?:a(?:b)?)?([^/]+)/$",
                "^([^/]+)/([^/]+)/([^/]+)/([^/]+)/$");
        List<String> inputs = new ArrayList<>(List.of(
                "https://www.host:123/path",
                "http://host/path",
                "https://www./path",
                "https://host:123456/path",
                "https://例え.テスト:42/道",
                "https://host:12/path\n",
                "a/",
                "ac/",
                "abc/",
                "abcd/",
                "abhost/",
                "ababhost/",
                "abchost/",
                "aabchost/",
                "abcabchost/",
                "abcdhost/",
                "pre/end",
                "prekey:value/end",
                "prex:/end",
                "prekey:é😀/end",
                "x",
                "xa",
                "x\n",
                "xa\n",
                "éhost/",
                "é😀éhost/",
                "a/b/c/d/",
                "a/b/c/d/\n",
                "xhost/",
                "host/",
                "/",
                ""));
        Random random = new Random(131);
        String[] symbols = {"a", "b", "c", "d", "x", "/", ":", "\n", "é", "😀", "\u0000"};
        for (int iteration = 0; iteration < 500; iteration++) {
            StringBuilder input = new StringBuilder();
            for (int remaining = random.nextInt(20); remaining > 0; remaining--) {
                input.append(symbols[random.nextInt(symbols.length)]);
            }
            inputs.add(input + "/");
        }
        for (String expression : expressions) {
            verifyAgainstJoni(expression, inputs);
            verifyAgainstJoni("(?s)" + expression, inputs);
            verifyAgainstJoni(expression.substring(0, expression.length() - 1) + "\\z", inputs);
        }
    }

    @Test
    public void testOptionalRegionsRetentionAndBoundedRetries()
    {
        String expression = "^https?://(www\\.)?([^/:]+)(?::([0-9]{1,5}))?/.*$";
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
        Slice input = utf8Slice("!https://www.host:42/path\n?");
        Re2Matcher region = regexp.pattern().matcher(input).reset(input, 1, input.length() - 1);
        assertThat(region.find()).isTrue();
        assertThat(region.group(1)).isEqualTo(utf8Slice("www."));
        assertThat(region.group(2)).isEqualTo(utf8Slice("host"));
        assertThat(region.group(3)).isEqualTo(utf8Slice("42"));
        assertThat(region.find()).isFalse();
        assertThat(region.reset(input, 1, input.length() - 1).matches()).isFalse();
        assertThat(region.reset(input, 1, input.length() - 2).matches()).isTrue();
        TrinoRegexpMatcher retained = regexp.matcher(utf8Slice("https://www.host:42/path"), 1);
        assertThat(retained.find()).isTrue();
        assertThat(retained.group(1)).isEqualTo(utf8Slice("www."));
        assertThat(retained.reset(utf8Slice("https://host/path")).find()).isTrue();
        assertThat(retained.group(1)).isNull();
        verifyAgainstJoni("^((a)?(b)?(c)?(d)?)abcd$", List.of("abcd", "aabcd", "abcdabcd", "dabc", "abcd\n"));
        verifyAgainstJoni("^x(a){0,1}b$", List.of("xb", "xab", "xaab", "xab\n"));
        verifyAgainstJoni("^" + "(".repeat(12) + "a" + ")".repeat(12) + "$", List.of("a", "aa", "a\n"));
        for (String rejected : List.of(
                "^(?:(a)(b))?$",
                "^x(a)?(b)?(c)?(d)?(e)?$",
                "^" + "(".repeat(16) + "a" + ")".repeat(16) + "$",
                "^x(?:(a)(b)){2}$",
                "^([a-z]+)(?::([0-9]+))?a$",
                "^([a-z]+)(?:([a-z]+):)?/$")) {
            assertThat(TrinoRegexp.compile(utf8Slice(rejected)).pattern().usesTrinoScanPlanForDiagnostics())
                    .as(rejected).isFalse();
        }
        for (int value = 128; value < 256; value++) {
            byte[] bytes = utf8Slice("!https://www.host:42/path?").getBytes();
            bytes[15] = (byte) value;
            Slice malformed = wrappedBuffer(bytes, 1, bytes.length - 2);
            verifyMalformedLifecycle(regexp, malformed, expression + " on " + HexFormat.of().formatHex(malformed.getBytes()));
        }
    }

    @Test
    public void testUnsupportedShapesKeepExistingRoute()
    {
        for (String expression : List.of(
                "(?:www\\.)?([^/]+)/",
                "([0-9]{2,4})a",
                "[0-9]+a",
                "(?m)^([^/]+)/$",
                "^([^/]+?)/$",
                "^([^/]+)/.*?$",
                "^([^é]+)é$",
                "^([a-z]+)abc$",
                "^([a-z]{2,4})a$",
                "^([a-z]+)(?:a)?/$",
                "^([a-z]*)$",
                "([a-z]*)",
                "^([é😀]+):$",
                "^([a-z]+?):$",
                "^([a-z]{2,4}?):$",
                "^([a-z]+)[a-z]+:$",
                "^(?:a)?(?:b)?(?:c)?(?:d)?(?:e)?([^/]+)/$",
                "^" + "x".repeat(257) + "([^/]+)/$")) {
            assertThat(TrinoRegexp.compile(utf8Slice(expression)).pattern().usesTrinoScanPlanForDiagnostics())
                    .as(expression).isFalse();
        }
        assertThat(Re2.compile(utf8Slice("(?s)" + URL)).usesTrinoScanPlanForDiagnostics()).isFalse();
    }

    @Test
    public void testRegionsMemoryAndFailureStorage()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(URL));
        Slice input = utf8Slice("!https://www.host/path\n?");
        Re2Matcher matcher = regexp.pattern().matcher(input).reset(input, 1, input.length() - 1);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isEqualTo(utf8Slice("host"));
        assertThat(matcher.find()).isFalse();
        assertThat(matcher.reset(input, 1, input.length() - 1).matches()).isFalse();
        assertThat(matcher.reset(input, 1, input.length() - 2).matches()).isTrue();
        assertThat(matcher.lookingAt()).isTrue();
        int[] groups = new int[8];
        assertThat(regexp.pattern().findInto(utf8Slice("https://host/path"), groups)).isTrue();
        assertThat(groups).containsExactly(0, 17, 8, 12, -1, -1, -1, -1);
        assertThat(regexp.pattern().findInto(utf8Slice("https://host/a\nb"), groups)).isFalse();
        assertThat(groups).containsOnly(-1);
        // A range ending before the context end takes the ordinary engine; it is the control.
        assertThat(regexp.pattern().findInto(input, 1, input.length() - 1, groups)).isFalse();
        assertThat(groups).containsOnly(-1);
        // The complete range reaches the plan. A failure after a match must clear stale offsets.
        Re2 planOnly = TrinoRegexp.compile(utf8Slice(URL)).pattern();
        disableOrdinaryEngine(planOnly);
        assertThat(planOnly.findInto(utf8Slice("https://host/path"), groups)).isTrue();
        assertThat(planOnly.findInto(input, 1, input.length(), groups)).isFalse();
        assertThat(groups).containsOnly(-1);
        assertThat(planOnly.findInto(utf8Slice("https://host/path"), groups)).isTrue();
        assertThat(planOnly.findInto(utf8Slice("https://host/a\nb"), 0, "https://host/a\nb".length(), groups)).isFalse();
        assertThat(groups).containsOnly(-1);

        // Anchored and literal-leading plans reserve before the lowered program is considered;
        // run-leading unanchored plans reserve only when no lowered program was built.
        assertSmallestBudgetKeepsOrdinaryRoute(URL, "https://host/path", "host");
        assertSmallestBudgetKeepsOrdinaryRoute("https?://([^/]+)/", "x https://host/path", "host");
        assertSmallestBudgetKeepsOrdinaryRoute("([^/]+)/", "x/ab/c", "x");
    }

    private static void assertSmallestBudgetKeepsOrdinaryRoute(String expression, String input, String group)
    {
        // The default budget admits the plan, so a smaller budget exercises its fallback.
        assertThat(TrinoRegexp.compile(utf8Slice(expression)).pattern().usesTrinoScanPlanForDiagnostics()).as(expression).isTrue();
        // Find the first budget that fits the semantic program. Object layouts differ with
        // compressed references, so avoid baking a particular byte threshold into the test.
        for (int memory = 1024; memory <= 65536; memory += 32) {
            TrinoRegexp tiny;
            try {
                tiny = TrinoRegexp.compile(utf8Slice(expression), TrinoRegexp.Options.defaults().setMaxMemory(memory));
            }
            catch (RegexpCompileMemoryLimitException _) {
                continue;
            }
            assertThat(tiny.pattern().usesTrinoScanPlanForDiagnostics()).as(expression).isFalse();
            assertThat(tiny.extract(utf8Slice(input), 1)).as(expression).isEqualTo(utf8Slice(group));
            return;
        }
        throw new AssertionError("no budget admitted the semantic program for " + expression);
    }

    @Test
    public void testBooleanTailOperations()
    {
        // A RUN may end a capture-free partial match only when everything after it is nullable.
        assertThat(booleanTails("abc(.*)")).isEqualTo("2:RUN");
        assertThat(booleanTails("(abc).*")).isEqualTo("3:RUN");
        assertThat(booleanTails("^abc.*")).isEqualTo("1:RUN");
        assertThat(booleanTails("^https?://([^/]+)")).isEqualTo("3:RUN");
        assertThat(booleanTails("(x)[^/]+")).isEqualTo("3:RUN");
        assertThat(booleanTails("(?i)^ABC([^:]+)")).isEqualTo("1:RUN");
        assertThat(booleanTails("(?i)content-type:(.*)")).isEqualTo("2:RUN");
        assertThat(booleanTails("x([0-9]+)y?")).isEqualTo("2:RUN");
        assertThat(booleanTails("^a([^/]{2,4})")).isEqualTo("1:RUN");
        // Both the optional body and its skip arm reach a nullable run.
        assertThat(scanOperations("^abc(?:(x+))?y*")).isEqualTo("LITERAL,FORK,SAVE,RUN,SAVE,RUN");
        assertThat(booleanTails("^abc(?:(x+))?y*")).isEqualTo("3:RUN,5:RUN");

        // End anchors, delimiters, and trailing literals can still fail after the run.
        for (String expression : List.of(
                "^abc.*$",
                "^abc(?:xy)?z*$",
                URL,
                "(?s)" + URL,
                "^https?://([^/]+)/",
                "https?://([^/]+)/",
                "https?://(?:www\\.)?([^/]+)/.*$",
                "(?i)^ABC([^:]+):",
                "(?i)^ABC([^:]+):DEF$",
                "^abc(x)?")) {
            assertThat(booleanTails(expression)).as(expression).isEmpty();
        }
    }

    private static String booleanTails(String expression)
    {
        return requireNonNull(analyze(expression)).booleanTailOperationsForDiagnostics();
    }

    @Test
    public void testBooleanTailAttemptStopsAfterRunMinimum()
    {
        String tail = "y".repeat(64 * 1024);
        for (BooleanTailShape shape : List.of(
                new BooleanTailShape("abc(.*)", "abc", 3),
                new BooleanTailShape("(abc).*", "abc", 3),
                new BooleanTailShape("(x)[^/]+", "x", 2),
                new BooleanTailShape("x([^/]{3,})", "xé😀", 8),
                new BooleanTailShape("(?i)content-type:(.*)", "Content-Type:", 13))) {
            TrinoScanPlan plan = requireNonNull(analyze(shape.expression()));
            assertThat(plan.isAnchoredStart()).as(shape.expression()).isFalse();
            Slice input = utf8Slice("padding" + shape.text() + tail).slice(7, utf8Slice(shape.text() + tail).length());
            assertThat(plan.booleanAttemptEndForDiagnostics(input, 0)).as(shape.expression()).isEqualTo(shape.attemptEnd());
        }
        TrinoScanPlan plan = requireNonNull(analyze("(x)[^/]+"));
        assertThat(plan.booleanAttemptEndForDiagnostics(utf8Slice("x/" + tail), 0)).isEqualTo(-1);

        // Boolean calls on these shapes reach the executors that contain the early return.
        for (String expression : List.of("^abc.*", "^https?://([^/]+)", "(?i)^ABC([^:]+)")) {
            Re2 pattern = TrinoRegexp.compile(utf8Slice(expression)).pattern();
            assertThat(pattern.usesPartialTrinoScanPlanForDiagnostics()).as(expression).isTrue();
            assertThat(pattern.usesTrinoScanPlanForFindForDiagnostics()).as(expression).isTrue();
        }
        Re2 unanchored = TrinoRegexp.compile(utf8Slice("(x)[^/]+")).pattern();
        assertThat(unanchored.usesPartialTrinoScanPlanForDiagnostics()).isFalse();
        assertThat(unanchored.usesTrinoScanPlanForFindForDiagnostics()).isTrue();
    }

    @Test
    public void testLiteralBooleanKernelsKeepPlanForBoundaries()
    {
        for (LiteralKernelShape shape : List.of(
                new LiteralKernelShape("abc(.*)", Re2.BooleanPartialMatchStrategy.CONTAINS, "xxabcdef\nz", "def"),
                new LiteralKernelShape("(abc).*", Re2.BooleanPartialMatchStrategy.CONTAINS, "xxabcdef\nz", "abc"),
                new LiteralKernelShape("^(abc)", Re2.BooleanPartialMatchStrategy.STARTS_WITH, "abcdef", "abc"),
                new LiteralKernelShape("^(abc)\\z", Re2.BooleanPartialMatchStrategy.EQUALS, "abc", "abc"),
                new LiteralKernelShape("^(abc)$", Re2.BooleanPartialMatchStrategy.EQUALS_FINAL_LINE, "abc\n", "abc"))) {
            String expression = shape.expression();
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
            Re2 pattern = regexp.pattern();
            assertThat(pattern.booleanPartialMatchStrategyForDiagnostics()).as(expression).isEqualTo(shape.strategy());
            assertThat(pattern.usesTrinoScanPlanForDiagnostics()).as(expression).isTrue();
            assertThat(pattern.usesTrinoScanPlanForFindForDiagnostics()).as(expression).isFalse();

            // Disable the semantic program: every call below must be answered by the literal
            // kernel or the retained plan.
            disableOrdinaryEngine(pattern);
            Slice source = utf8Slice(shape.text());
            int matchStart = shape.text().indexOf("abc");
            assertThat(regexp.contains(source)).as(expression).isTrue();
            assertThat(regexp.extract(source, 1)).as(expression).isEqualTo(utf8Slice(shape.group()));
            assertThat(regexp.position(source)).as(expression).isEqualTo(matchStart + 1);
            TrinoRegexpMatcher matcher = regexp.matcher(source);
            assertThat(matcher.find()).as(expression).isTrue();
            assertThat(matcher.group(1)).as(expression).isEqualTo(utf8Slice(shape.group()));
            assertThat(pattern.find(source, 0, source.length())).as(expression).isTrue();
            assertThat(pattern.lookingAt(source, matchStart, source.length())).as(expression).isTrue();
            Re2Matcher region = pattern.matcher(source).reset(source, matchStart, source.length());
            assertThat(region.lookingAt()).as(expression).isTrue();
            assertThat(region.start(1)).as(expression).isEqualTo(shape.text().indexOf(shape.group()));
        }
    }

    @Test
    public void testBooleanTailsAgainstJoni()
    {
        String longTail = "y".repeat(4096);
        List<String> common = List.of(
                "",
                "abc",
                "xxabc",
                "ab",
                "abc\n",
                "ab\nabc",
                "abcx\ny",
                "abcq",
                "abcx",
                "abcxxyy",
                "abcxy",
                "abcxyzz",
                "abc" + longTail,
                "abc" + longTail + "\n" + longTail,
                "zz\nabc" + longTail);
        for (String expression : List.of("abc(.*)", "(abc).*", "^abc.*", "(?s)^abc.*", "^abc.*$", "^abc(?:xy)?z*$", "^abc(?:(x+))?y*", "^abc(?:(xy))?z*", "(abc)(?:x|y)*")) {
            verifyBooleanCallsAgainstJoni(expression, common);
        }
        // Minimum-one hosts: `/` absent, an empty host, UTF-8 hosts, and the URL controls.
        List<String> urls = List.of(
                "",
                "https://",
                "http://",
                "https://h",
                "https:///x",
                "https://例",
                "https://😀/",
                "http://host\n/x",
                "https://host/path",
                "https://www.host/path",
                "https://www./path",
                "ftp://host",
                "https://host" + longTail,
                "https://" + longTail + "/");
        for (String expression : List.of("^https?://([^/]+)", "^https?://([^/]{3,})", "^https?://([^/]+)/", "https?://([^/]+)/", URL, "(?s)" + URL)) {
            verifyBooleanCallsAgainstJoni(expression, urls);
        }
        // Unanchored run shapes and repeated matches.
        List<String> runs = List.of("", "x", "xy", "x/", "/x/y", "xx\nx", "x/x/x", "xé😀/x例え", "x😀", "xé", "x1x22yx333y", "yyy" + longTail, "x" + longTail, "/x" + longTail + "/x");
        for (String expression : List.of("(x)[^/]+", "(x)[^/]{2,}", "x([^/]{3,})", "x([0-9]+)y?", "(x)[^/]*")) {
            verifyBooleanCallsAgainstJoni(expression, runs);
        }
        // Folded partial and unanchored plans.
        List<String> folded = List.of("", "abc", "ABCdef", "aBc:x", "abc\ndef", "Content-Type:", "x CONTENT-TYPE:text\nz", "content-typ", "ABC" + longTail);
        for (String expression : List.of("(?i)^ABC([^:]+)", "(?i)^ABC(.*)", "(?i)content-type:(.*)", "(?i)^ABC([^:]+):")) {
            verifyBooleanCallsAgainstJoni(expression, folded);
        }
        // Retained literal kernels, including final-newline equality.
        List<String> literals = List.of("", "abc", "abc\n", "abc\nx", "abc\n\n", "abcd", "xabc", "xxabcdef\nz", "ab");
        for (String expression : List.of("^(abc)", "^(abc)$", "^(abc)\\z")) {
            verifyBooleanCallsAgainstJoni(expression, literals);
        }
    }

    @Test
    public void testExecutorRoutes()
    {
        for (ExecutorRoute route : EXECUTOR_ROUTES) {
            Re2 pattern = TrinoRegexp.compile(utf8Slice(route.expression())).pattern();
            assertThat(pattern.usesTrinoScanPlanForDiagnostics()).as(route.expression()).isTrue();
            assertThat(pattern.usesPartialTrinoScanPlanForDiagnostics()).as(route.expression()).isEqualTo(route.partial());
            assertThat(pattern.usesAsciiFoldedTrinoScanExecutorForDiagnostics()).as(route.expression()).isEqualTo(route.folded());
            assertThat(requireNonNull(analyze(route.expression())).isAnchoredStart())
                    .as(route.expression())
                    .isEqualTo(route.anchoredStart());
            verifyAgainstJoni(route.expression(), List.of(route.text("host"), route.text("例え😀"), route.text("host") + route.text("other"), "miss", ""));
        }
    }

    @Test
    public void testRegionsForEveryExecutor()
    {
        for (ExecutorRoute route : EXECUTOR_ROUTES) {
            Re2 regexp = TrinoRegexp.compile(utf8Slice(route.expression())).pattern();
            Regex oracle = joniPattern(route.expression());
            String complete = route.text("host");
            for (String text : List.of(
                    complete,
                    route.text("é😀"),
                    route.text("one") + " " + route.text("two"),
                    complete.substring(0, complete.length() - 1),
                    complete + "\n",
                    "miss")) {
                // The region, not the Slice, is the matching context. The bytes around it must not
                // satisfy anchors or extend runs.
                Slice input = utf8Slice("é!" + text + "\n?" + complete);
                int regionStart = utf8Slice("é!").length();
                int regionEnd = regionStart + utf8Slice(text).length();
                Slice region = input.slice(regionStart, regionEnd - regionStart);
                String description = route.expression() + " on " + text;
                Re2Matcher matcher = regexp.matcher(input);
                verifyRegionAgainstJoni(oracle, matcher, input, regionStart, regionEnd, description);

                // Compare anchored modes with the same text as a complete Slice.
                Re2Matcher control = regexp.matcher(region);
                boolean lookingAt = control.lookingAt();
                assertThat(matcher.reset(input, regionStart, regionEnd).lookingAt()).as(description).isEqualTo(lookingAt);
                if (lookingAt) {
                    assertThat(matcher.end()).as(description).isEqualTo(regionStart + control.end());
                    assertThat(matcher.start(1)).as(description).isEqualTo(control.start(1) < 0 ? -1 : regionStart + control.start(1));
                }
                boolean fullMatch = control.reset(region).matches();
                assertThat(matcher.reset(input, regionStart, regionEnd).matches()).as(description).isEqualTo(fullMatch);
                if (fullMatch) {
                    assertThat(matcher.end(1)).as(description).isEqualTo(regionStart + control.end(1));
                }
            }
        }
    }

    @Test
    public void testRetryStackNearOperationLimit()
    {
        String prefix = "[ab]{1}:".repeat(13);
        String expression = "^" + prefix + "(?:w)?(?:x)?(?:y)?(?:z)?([w-z]+):$";
        TrinoScanPlan plan = analyze(expression);
        assertThat(plan).isNotNull();
        assertThat(plan.operationCountForDiagnostics()).isEqualTo(33);
        String inputPrefix = "a:".repeat(13);
        verifyAgainstJoni(expression, List.of(inputPrefix + "wxyz:", inputPrefix + "w:", inputPrefix + "wxyz?", inputPrefix + ":"));
        assertThat(TrinoRegexp.compile(utf8Slice("^[ab]{1}:" + expression.substring(1))).pattern().usesTrinoScanPlanForDiagnostics()).isFalse();
        assertThat(TrinoRegexp.compile(utf8Slice("^" + prefix + "(?:v)?(?:w)?(?:x)?(?:y)?(?:z)?([w-z]+):$")).pattern().usesTrinoScanPlanForDiagnostics()).isFalse();
    }

    @Test
    public void testCompactionPreservesOperationLimitEligibility()
    {
        String expression = "^" + "[ab]{1}:".repeat(14) + "([^/]+)/([^;]+);([^|]+)\\|$";
        TrinoScanPlan plan = analyze(expression);
        assertThat(plan).isNotNull();
        assertThat(plan.operationCountForDiagnostics()).isEqualTo(32);
        verifyAgainstJoni(expression, List.of("a:".repeat(14) + "first/second;third|", "b:".repeat(14) + "//;|"));
    }

    @Test
    public void testScanMatchModesWithFinalLineEnd()
    {
        for (String expression : List.of("^([a-z]+):$", "^(?:([a-z]+):)?end$", "^([^/]+)/.*$")) {
            String text = expression.contains("end") ? "key:end" : expression.contains("/") ? "host/path" : "key:";
            for (boolean absoluteEnd : new boolean[] {false, true}) {
                String anchored = absoluteEnd ? expression.substring(0, expression.length() - 1) + "\\z" : expression;
                TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(anchored));
                assertThat(regexp.pattern().usesTrinoScanPlanForDiagnostics()).isTrue();
                disableOrdinaryEngine(regexp);
                Slice source = utf8Slice("padding!" + text + "\n?outside").slice(7, text.length() + 3);
                Re2Matcher matcher = regexp.pattern().matcher(source);
                assertThat(matcher.reset(source, 1, source.length() - 1).lookingAt()).isEqualTo(!absoluteEnd);
                if (!absoluteEnd) {
                    assertThat(matcher.end()).isEqualTo(1 + text.length());
                }
                assertThat(matcher.reset(source, 1, source.length() - 1).matches()).isFalse();
                assertThat(matcher.reset(source, 1, source.length() - 2).matches()).isTrue();
                assertThat(matcher.group(1)).isEqualTo(utf8Slice(text.substring(0, text.contains(":") ? text.indexOf(':') : text.indexOf('/'))));
            }
        }
    }

    @Test
    public void testGeneratedPatternsAndWitnessesAgainstJoni()
    {
        int partialPlans = 0;
        int delimitedCaptures = 0;
        int lineTails = 0;
        int dotAllTails = 0;
        for (long seed : new long[] {139, 149, 157}) {
            Random random = new Random(seed);
            int eligible = 0;
            for (int iteration = 0; iteration < 200; iteration++) {
                PatternWitness generated = generatedSequence(random, 0);
                String flags = random.nextBoolean() ? "(?s)" : "";
                String expression = flags + "^x(" + generated.pattern() + ")!" + (random.nextBoolean() ? "$" : "\\z");
                String witness = "x" + generated.text() + "!";

                // Without a trailing anchor the plan is partial. A final delimited capture and
                // tail make the compacted operations reachable; that variant omits the outer
                // capture because compaction requires every capture to hold a single run. Neither
                // variant draws from the random sequence, so the end-anchored cases stay unchanged.
                String prefix = "x" + generated.text();
                String partial = flags + "^x(" + generated.pattern() + ")";
                if (TrinoRegexp.compile(utf8Slice(partial)).pattern().usesTrinoScanPlanForDiagnostics()) {
                    assertThat(TrinoRegexp.compile(utf8Slice(partial)).pattern().usesPartialTrinoScanPlanForDiagnostics()).as(partial).isTrue();
                    partialPlans++;
                    verifyAgainstJoni(partial, List.of(
                            prefix,
                            prefix + "!",
                            prefix + "rest/more\n",
                            prefix.substring(0, prefix.length() - 1),
                            "x\n" + generated.text(),
                            "lead" + prefix,
                            "x",
                            ""));
                }
                String compacted = flags + "^x" + generated.pattern() + "([^/]*)/.*$";
                if (TrinoRegexp.compile(utf8Slice(compacted)).pattern().usesTrinoScanPlanForDiagnostics()) {
                    String operations = scanOperations(compacted);
                    delimitedCaptures += operations.contains("DELIMITED_CAPTURE") ? 1 : 0;
                    lineTails += operations.contains("LINE_TAIL") ? 1 : 0;
                    dotAllTails += operations.contains("DOT_ALL_TAIL") ? 1 : 0;
                    verifyAgainstJoni(compacted, List.of(
                            prefix + "host/path",
                            prefix + "/",
                            prefix + "é😀/a\nb",
                            prefix + "host/path\n",
                            prefix + "host/path\n\n",
                            prefix + "host",
                            prefix + "ho\nst/path",
                            ""));
                }

                TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
                if (!regexp.pattern().usesTrinoScanPlanForDiagnostics()) {
                    continue;
                }
                eligible++;
                // Every accepted pattern has a known match, not just independently random nonmatches.
                assertThat(regexp.contains(utf8Slice(witness))).as("seed %s, pattern %s", seed, expression).isTrue();
                int removed = witness.offsetByCodePoints(0, Math.min(2, witness.codePointCount(0, witness.length())));
                List<String> inputs = new ArrayList<>(List.of(
                        witness,
                        witness + "\n",
                        witness + "\n\n",
                        witness.substring(0, witness.length() - 1),
                        witness.substring(0, witness.length() - 1) + "?",
                        witness.substring(0, 1) + witness.substring(removed),
                        "x\n" + generated.text() + "!",
                        "x!",
                        "prefix" + witness,
                        ""));
                String[] symbols = {"a", "b", "c", "1", "/", ":", "!", "\n", "\u0000", "é", "😀"};
                StringBuilder noise = new StringBuilder("x");
                for (int index = 0; index < 60; index++) {
                    noise.append(symbols[random.nextInt(symbols.length)]);
                }
                inputs.add(noise + "!");
                verifyAgainstJoni(expression, inputs);
            }
            assertThat(eligible).as("eligible patterns for seed %s", seed).isGreaterThan(50);
        }
        assertThat(partialPlans).isGreaterThan(200);
        assertThat(delimitedCaptures).isGreaterThan(50);
        assertThat(lineTails).isGreaterThan(20);
        assertThat(dotAllTails).isGreaterThan(20);
    }

    @Test
    public void testGeneratedUnanchoredPatternsAgainstJoni()
    {
        for (long seed : new long[] {163, 167, 173}) {
            Random random = new Random(seed);
            int eligible = 0;
            for (int iteration = 0; iteration < 200; iteration++) {
                PatternWitness generated = generatedSequence(random, 0);
                String expression = (random.nextBoolean() ? "(?s)" : "") + "x(" + generated.pattern() + ")!" + (random.nextBoolean() ? "$" : "");
                String witness = "x" + generated.text() + "!";
                TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
                if (!regexp.pattern().usesTrinoScanPlanForDiagnostics()) {
                    continue;
                }
                eligible++;
                List<String> inputs = new ArrayList<>(List.of(
                        witness,
                        "lead" + witness,
                        "é😀" + witness,
                        "lead" + witness + (expression.endsWith("$") ? "\n" : "suffix"),
                        witness.substring(0, witness.length() - 1),
                        // Two matches, and failed candidates before a match.
                        witness + witness,
                        "lead" + witness + " " + witness,
                        witness.substring(0, witness.length() - 1) + witness,
                        "x!x" + witness,
                        "x!",
                        "lead",
                        ""));
                // Include the leading literal so noise also starts failed candidates.
                String[] symbols = {"a", "b", "c", "1", "/", ":", "!", "\n", "\u0000", "é", "😀", "x"};
                StringBuilder noise = new StringBuilder("lead");
                for (int index = 0; index < 60; index++) {
                    noise.append(symbols[random.nextInt(symbols.length)]);
                }
                inputs.add(noise + witness);
                verifyAgainstJoni(expression, inputs);
            }
            assertThat(eligible).as("eligible unanchored patterns for seed %s", seed).isGreaterThan(40);
        }
    }

    private static PatternWitness generatedSequence(Random random, int depth)
    {
        StringBuilder pattern = new StringBuilder();
        StringBuilder text = new StringBuilder();
        int count = 1 + random.nextInt(3);
        for (int index = 0; index < count; index++) {
            PatternWitness item;
            if (depth < 2 && random.nextInt(4) == 0) {
                item = generatedSequence(random, depth + 1);
            }
            else if (random.nextInt(4) == 0) {
                String literal = List.of("ab", "é", "😀", "/", ":").get(random.nextInt(5));
                item = new PatternWitness(literal, literal);
            }
            else {
                String[] atoms = {"[a-z]", "[ab]", "[0-9]", "[^/:!]", "[^/!]", "[^\\n!]", "[\\x00-\\x7F]", ".", "[é😀]"};
                String[] symbols = {"a", "b", "1", "é", "😀", "é", "\u0000", "😀", "é"};
                String[] quantifiers = {"", "?", "*", "+", "{2}", "{1,3}", "{2,}", "{0,2}", "{0}"};
                int atom = random.nextInt(atoms.length);
                int quantifier = random.nextInt(quantifiers.length);
                int repetitions = switch (quantifier) {
                    case 0 -> 1;
                    case 1 -> random.nextInt(2);
                    case 2 -> random.nextInt(34);
                    case 3 -> 1 + random.nextInt(33);
                    case 4 -> 2;
                    case 5 -> 1 + random.nextInt(3);
                    case 6 -> 2 + random.nextInt(32);
                    case 7 -> random.nextInt(3);
                    default -> 0;
                };
                item = new PatternWitness(atoms[atom] + quantifiers[quantifier], symbols[atom].repeat(repetitions));
            }
            if (random.nextBoolean()) {
                item = new PatternWitness("(" + item.pattern() + ")", item.text());
            }
            if (random.nextInt(4) == 0) {
                item = new PatternWitness("(?:" + item.pattern() + ")?", random.nextBoolean() ? item.text() : "");
            }
            pattern.append(item.pattern()).append('/');
            text.append(item.text()).append('/');
        }
        return new PatternWitness(pattern.toString(), text.toString());
    }

    private record PatternWitness(String pattern, String text) {}

    @Test
    public void testMalformedInputDoesNotRequireRe2Agreement()
    {
        String anchoredExpression = "(?s)" + URL;
        String unanchoredExpression = "(?s)https?://([^/]+)/.*$";
        String foldedExpression = "(?i)content-type:([^;]+);";
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(anchoredExpression));
        TrinoRegexp unanchored = TrinoRegexp.compile(utf8Slice(unanchoredExpression));
        TrinoRegexp folded = TrinoRegexp.compile(utf8Slice(foldedExpression));
        for (int badByte : new int[] {0x80, 0xBF, 0xC0, 0xED, 0xF5, 0xFF}) {
            byte[] bytes = utf8Slice("!https://host/path?").getBytes();
            bytes[bytes.length - 2] = (byte) badByte;
            Slice input = wrappedBuffer(bytes, 1, bytes.length - 2);
            String hex = HexFormat.of().formatHex(input.getBytes());
            // No exact malformed-input result is promised. Still check that each route's
            // operations agree with each other and stay within the view, and keep RE2's anchored
            // UTF-8 behavior unchanged.
            verifyMalformedLifecycle(regexp, input, anchoredExpression + " on " + hex);
            TrinoRegexpMatcher matcher = regexp.matcher(input);
            matcher.find();
            assertThat(matcher.find()).isFalse();
            verifyMalformedLifecycle(unanchored, input, unanchoredExpression + " on " + hex);
            TrinoRegexpMatcher unanchoredMatcher = unanchored.matcher(input);
            unanchoredMatcher.find();
            assertThat(unanchoredMatcher.find()).isFalse();

            byte[] foldedBytes = utf8Slice("!CONTENT-TYPE:value;?").getBytes();
            foldedBytes[foldedBytes.length - 3] = (byte) badByte;
            Slice foldedInput = wrappedBuffer(foldedBytes, 1, foldedBytes.length - 2);
            verifyMalformedLifecycle(folded, foldedInput, foldedExpression + " on " + HexFormat.of().formatHex(foldedInput.getBytes()));
            TrinoRegexpMatcher foldedMatcher = folded.matcher(foldedInput);
            foldedMatcher.find();
            assertThat(foldedMatcher.find()).isFalse();
            assertThat(Re2.compile(utf8Slice(anchoredExpression)).find(input)).isFalse();
            assertThat(JavaRegexp.compile(utf8Slice(anchoredExpression)).find(input)).isFalse();
        }
    }

    @Test
    public void testMalformedViewsEndingAtArrayEnd()
    {
        List<MalformedView> views = new ArrayList<>();
        for (ExecutorRoute route : EXECUTOR_ROUTES) {
            views.add(new MalformedView(route.expression(), route.template(), true));
        }
        views.addAll(List.of(
                new MalformedView("(?s)" + URL, "https://www.%s/path", true),
                // A leading run longer than one character keeps the ordinary engine, checked the same way.
                new MalformedView("([0-9]+)a", "x99%s1a", false)));
        byte[][] tails = {
                {(byte) 0x80}, {(byte) 0xBF}, {(byte) 0xC0}, {(byte) 0xC3}, {(byte) 0xE2}, {(byte) 0xED}, {(byte) 0xF0},
                {(byte) 0xF4}, {(byte) 0xF5}, {(byte) 0xFF}, {(byte) 0xE2, (byte) 0x82}, {(byte) 0xF0, (byte) 0x9F}, {(byte) 0xF0, (byte) 0x9F, (byte) 0x98},
        };
        for (MalformedView view : views) {
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(view.expression()));
            assertThat(regexp.pattern().usesTrinoScanPlanForDiagnostics()).as(view.expression()).isEqualTo(view.scanPlan());
            String template = view.template();
            String before = template.substring(0, template.indexOf("%s")) + "ho";
            String after = template.substring(template.indexOf("%s") + 2);
            for (byte[] tail : tails) {
                // End the view at the array end, both inside a run and after a complete match.
                for (String suffix : List.of("", "st" + after)) {
                    byte[] prefix = utf8Slice("!" + before).getBytes();
                    byte[] trailing = utf8Slice(suffix).getBytes();
                    byte[] bytes = new byte[prefix.length + tail.length + trailing.length];
                    System.arraycopy(prefix, 0, bytes, 0, prefix.length);
                    System.arraycopy(tail, 0, bytes, prefix.length, tail.length);
                    System.arraycopy(trailing, 0, bytes, prefix.length + tail.length, trailing.length);
                    for (Slice input : List.of(wrappedBuffer(bytes, 1, bytes.length - 1), wrappedBuffer(bytes, 1, bytes.length - 1 - trailing.length))) {
                        verifyMalformedLifecycle(regexp, input, view.expression() + " on " + HexFormat.of().formatHex(input.getBytes()));
                    }
                }
            }
        }
    }

    private static void verifyMalformedLifecycle(TrinoRegexp regexp, Slice input, String description)
    {
        // No exact malformed-input result is promised. The scan plan's find operations must
        // agree with each other, stay within the view, and terminate.
        boolean found = regexp.contains(input);
        TrinoRegexpMatcher matcher = regexp.matcher(input);
        assertThat(matcher.find()).as(description).isEqualTo(found);
        if (found) {
            for (int group = 0; group <= matcher.groupCount(); group++) {
                assertThat(matcher.start(group)).as(description).isBetween(-1, input.length());
                assertThat(matcher.end(group)).as(description).isBetween(-1, input.length());
                assertThat(regexp.extract(input, group)).as(description).isEqualTo(matcher.group(group));
            }
        }
        else {
            assertThat(regexp.extract(input, 1)).as(description).isNull();
        }
        long matches = found ? 1 : 0;
        while (matcher.find()) {
            matches++;
            assertThat(matches).as(description).isLessThanOrEqualTo(input.length() + 1L);
        }
        assertThat(regexp.count(input)).as(description).isEqualTo(matches);
        assertThat(regexp.extractAll(input, 1)).as(description).hasSize((int) matches);
        assertThat(regexp.split(input)).as(description).hasSize((int) matches + 1);
        regexp.replace(input, utf8Slice("$1"));
    }

    @Test
    public void testUnanchoredCountAgreesWithFindOnMalformedInput()
    {
        // No malformed-input result is promised, but count must reach the same plan as find.
        // A member byte follows each malformed sequence, so Joni's lead-byte widths stay in the run.
        List<ExpressionTemplates> cases = List.of(
                new ExpressionTemplates("https?://([^/]+)/", List.of("x https://ho%sst/ y", "http://a/ https://b%sc/ http://d/")),
                new ExpressionTemplates("([^/]+)/", List.of("ho%sst/ y", "%sa/b%sc/")),
                new ExpressionTemplates("a([^:]+):", List.of("za%sb: y", "a%sb:a%sc:a:")),
                new ExpressionTemplates("(?i)a([^:]+):", List.of("zAb%sc: y", "A%sb:a%sC:")),
                new ExpressionTemplates("([^/]{2,})/", List.of("x%sy/", "/x%sz/a/bc%sd/")));
        for (ExpressionTemplates entry : cases) {
            String expression = entry.expression();
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(regexp.pattern().usesTrinoScanPlanForDiagnostics()).as(expression).isTrue();
            assertThat(regexp.pattern().usesPartialTrinoScanPlanForDiagnostics()).as(expression).isFalse();
            Regex oracle = joniPattern(expression);
            for (String template : entry.templates()) {
                for (byte[] bytes : MALFORMED_SEQUENCES) {
                    Slice input = malformedText(template, bytes);
                    String description = expression + " on " + HexFormat.of().formatHex(input.getBytes());
                    TrinoRegexpMatcher matcher = regexp.matcher(input);
                    long found = 0;
                    while (matcher.find()) {
                        found++;
                    }
                    assertThat(found).as(description).isPositive();
                    assertThat(regexp.count(input)).as(description).isEqualTo(found);
                    assertThat(regexp.count(input)).as(description).isEqualTo(joniMatches(oracle, input).size());
                }
            }
        }
    }

    @Test
    public void testWorkBudgetHandsOffQuadraticSearches()
    {
        // The first attempt reads the whole repeated prefix and fails, and every later candidate
        // would read it again. The work budget ends the plan's search after that first attempt,
        // at the next candidate, and the ordinary engine finds both later matches.
        int units = 1 << 16;
        for (QuadraticSearch search : QUADRATIC_SEARCHES) {
            String expression = search.expression();
            Slice input = search.input(units);
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
            TrinoScanPlan plan = requireNonNull(analyze(expression), expression);
            boolean folded = plan.usesAsciiFoldedExecutor();
            int[] groups = new int[2 * (regexp.capturingGroupCount() + 1)];
            for (int[] searchGroups : Arrays.asList(null, groups)) {
                int resume = folded
                        ? plan.searchAsciiFolded(input, 0, input.length(), 0, false, false, searchGroups)
                        : plan.search(input, 0, input.length(), 0, false, false, searchGroups);
                assertThat(resume).as(expression).isEqualTo(1);
            }
            assertThat(groups).as(expression).containsOnly(-1);
            // An anchored search makes one attempt and never hands off.
            int anchored = folded
                    ? plan.searchAsciiFolded(input, 0, input.length(), 0, true, false, groups)
                    : plan.search(input, 0, input.length(), 0, true, false, groups);
            assertThat(anchored).as(expression).isEqualTo(TrinoScanPlan.NO_MATCH);

            // A search that hands off finds nothing when the ordinary engine cannot match, so
            // every operation must have handed off. Its results otherwise equal the ordinary
            // engine's, which here runs every search from its start.
            TrinoRegexp handoff = TrinoRegexp.compileForcingScanPlanHandoffForTesting(utf8Slice(expression));
            List<int[]> expected = matcherMatches(handoff, input);
            assertThat(expected).as(expression).hasSize(2);
            verifyOperations(expression, regexp, _ -> expected, List.of(input), true);
            verifyOperations(expression + " without the ordinary engine", withoutOrdinaryEngine(expression), _ -> List.of(), List.of(input), true);
            verifySourcesAgainstJoni(expression, List.of(search.input(1500)), true);
        }
    }

    @Test
    public void testWorkBudgetKeepsLinearSearchesOnPlan()
    {
        // Dense matches and short or sparse failed attempts stay within the budget: with an
        // ordinary engine that cannot match, every operation still reports Joni's matches.
        StringBuilder urls = new StringBuilder();
        StringBuilder paths = new StringBuilder();
        StringBuilder headers = new StringBuilder();
        for (int index = 0; index < 1000; index++) {
            urls.append("see https://host").append(index).append(".example.com/path ");
            paths.append("segment").append(index).append('/');
            headers.append("Content-Type:text/plain; ");
        }
        List<ExpressionInput> rows = List.of(
                new ExpressionInput("https?://([^/]+)/", urls.toString()),
                new ExpressionInput("([^/]+)/", paths.toString()),
                new ExpressionInput("(?i)content-type:([^;]+);", headers.toString()),
                // Each failed attempt reads only to the next 'z', before the next candidate.
                new ExpressionInput("x([^z]+)zq", "xaaazr".repeat(20000) + "xbzq"));
        for (ExpressionInput row : rows) {
            String expression = row.expression();
            Regex oracle = joniPattern(expression);
            Slice input = view(row.input());
            List<int[]> expected = joniMatches(oracle, input);
            assertThat(expected).as(expression).isNotEmpty();
            verifyOperations(expression + " without the ordinary engine", withoutOrdinaryEngine(expression), _ -> expected, List.of(input), true);
        }
    }

    @Test
    public void testNaturalHandoffKeepsContextAndCaptures()
    {
        // The first match is found on the plan. The next search fails one long attempt and
        // continues on the ordinary engine, which must report the same captures, Slice-relative
        // positions, and region context as Joni.
        String expression = "([^/]+)/([^!]+)!x";
        Regex oracle = joniPattern(expression);
        String text = "p/q!x " + "a/".repeat(2048) + "!y!x/b/c!x tail";
        Slice input = view(text);
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
        Re2 re2 = regexp.pattern();
        assertThat(re2.usesTrinoScanPlanForDiagnostics()).isTrue();

        TrinoScanPlan plan = requireNonNull(analyze(expression));
        int[] groups = new int[6];
        assertThat(plan.search(input, 0, input.length(), 0, false, false, groups)).isEqualTo(TrinoScanPlan.SEARCH_MATCHED);
        assertThat(groups).containsExactly(0, 5, 0, 1, 2, 3);
        // The attempt at the space reads to the first '!' and fails; the next candidate follows its leading run.
        assertThat(plan.search(input, 0, input.length(), 5, false, false, groups)).isEqualTo(7);

        List<int[]> matches = joniMatches(oracle, input);
        assertThat(matches).hasSize(2);
        verifySourcesAgainstJoni(expression, List.of(input), true);
        TrinoRegexpMatcher planOnly = withoutOrdinaryEngine(expression).matcher(input);
        assertThat(planOnly.find()).isTrue();
        assertThat(planOnly.group(2)).isEqualTo(utf8Slice("q"));
        assertThat(planOnly.find()).isFalse();

        // Regions are the context of the plan and of its continuation.
        Re2Matcher matcher = re2.matcher(input);
        for (int[] range : new int[][] {{2, input.length()}, {6, input.length() - 5}, {7, input.length() - 6}, {0, input.length() - 6}}) {
            verifyRegionAgainstJoni(oracle, matcher, input, range[0], range[1], expression + " on [" + range[0] + ", " + range[1] + ")");
        }

        // find(int) starts a search inside the repeated prefix of the complete Slice.
        int base = input.byteArrayOffset();
        for (int start : new int[] {5, 6, 100, text.indexOf('!', 10) - 1}) {
            Matcher joni = oracle.matcher(input.byteArray(), base, base + input.length());
            assertThat(joni.search(base + start, base + input.length(), Option.DEFAULT)).isNotNegative();
            Region region = joni.getEagerRegion();
            assertThat(matcher.reset(input).find(start)).isTrue();
            for (int group = 0; group <= 2; group++) {
                // Joni reports positions relative to the matcher's start.
                assertThat(matcher.start(group)).as("from %s", start).isEqualTo(region.beg[group]);
                assertThat(matcher.end(group)).as("from %s", start).isEqualTo(region.end[group]);
            }
            assertThat(re2.find(input, start, input.length())).isTrue();
        }
        assertThat(regexp.count(input)).isEqualTo(2);
        assertThat(re2.count(input)).isEqualTo(2);

        // An end assertion must see the region end, not the Slice end, after a handoff.
        String anchoredEnd = "x([^z]+)zq$";
        assertThat(TrinoRegexp.compile(utf8Slice(anchoredEnd)).pattern().usesTrinoScanPlanForDiagnostics()).isTrue();
        Slice tailed = view("xa".repeat(2048) + "zrxbzqTAIL");
        verifyRegionAgainstJoni(anchoredEnd, tailed, 0, tailed.length() - 4);
        verifyRegionAgainstJoni(anchoredEnd, tailed, 0, tailed.length());
        verifyRegionAgainstJoni(expression, input, 3, input.length() - 3);
    }

    @Test
    public void testOperationsAgreeOnMalformedInputAfterHandoff()
    {
        // Past a handoff, malformed input may get the ordinary engine's answer instead of the
        // plan's. Every operation searches through the same budgeted loop, in both the ordinary
        // and the ASCII-folded executor, so all of them must still report the matcher's match
        // sequence.
        List<ExpressionTemplates> cases = List.of(
                new ExpressionTemplates("([^/]+)/([^!]+)!x", List.of(
                        "a/".repeat(1500) + "!y%s!x/b/c!x",
                        "%s" + "a/".repeat(1500) + "!y!x/b%s/c!x d/e!x",
                        "a%s/".repeat(1500) + "!y!x/b/c!x",
                        "a/".repeat(1500) + "%s!y!x/%s/c!x%s/!x")),
                new ExpressionTemplates("x([^z]+)zq", List.of(
                        "xa".repeat(1500) + "z%srxbzq",
                        "x%sa".repeat(1500) + "zrx%szq")),
                new ExpressionTemplates("(?i)x([^z]+)zq", List.of(
                        "Xa".repeat(1500) + "z%srXbZq",
                        "x%sA".repeat(1500) + "ZrX%szQ")),
                new ExpressionTemplates("id=([^&]+)&x", List.of(
                        "id=%s".repeat(1500) + "&yid=b%s&x")));
        for (ExpressionTemplates entry : cases) {
            String expression = entry.expression();
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(regexp.pattern().usesAsciiFoldedTrinoScanExecutorForDiagnostics()).as(expression).isEqualTo(expression.startsWith("(?i)"));
            for (String template : entry.templates()) {
                for (byte[] bytes : MALFORMED_SEQUENCES) {
                    Slice input = malformedText(template, bytes);
                    String description = expression + " on " + HexFormat.of().formatHex(bytes) + " in " + template.substring(0, 12);
                    verifyOperations(description, regexp, source -> matcherMatches(regexp, source), List.of(input), true);
                    assertThat(regexp.pattern().find(input, 0, input.length())).as(description).isEqualTo(regexp.contains(input));
                }
            }
        }
    }

    @Test
    public void testForcedHandoffSkipsUnanchoredPlanSearch()
    {
        // The test-only plan continues every unanchored search on the ordinary engine before
        // its first attempt, so an ordinary engine that cannot match finds nothing. Anchored
        // plans never hand off.
        Slice input = utf8Slice("x https://host/ Content-Type:text; 12zz");
        for (String expression : List.of("https?://([^/]+)/", "([^/]+)/", "(?i)content-type:([^;]+);")) {
            TrinoRegexp handoff = TrinoRegexp.compileForcingScanPlanHandoffForTesting(utf8Slice(expression));
            assertThat(handoff.pattern().usesTrinoScanPlanForDiagnostics()).as(expression).isTrue();
            assertThat(handoff.contains(input)).as(expression).isTrue();
            assertThat(handoff.extract(input, 1)).as(expression).isNotNull();
            TrinoRegexp forced = TrinoRegexp.compileForcingScanPlanHandoffForTesting(utf8Slice(expression));
            disableOrdinaryEngine(forced);
            assertThat(forced.contains(input)).as(expression).isFalse();
            assertThat(forced.extract(input, 1)).as(expression).isNull();
            assertThat(forced.count(input)).as(expression).isZero();
        }
        TrinoRegexp anchored = TrinoRegexp.compileForcingScanPlanHandoffForTesting(utf8Slice(URL));
        disableOrdinaryEngine(anchored);
        assertThat(anchored.extract(utf8Slice("https://www.host/path"), 1)).isEqualTo(utf8Slice("host"));
    }

    /**
     * Compiles {@code expression} with an ordinary engine that matches nothing, so only the
     * scan plan can report a match.
     */
    private static TrinoRegexp withoutOrdinaryEngine(String expression)
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
        disableOrdinaryEngine(regexp);
        return regexp;
    }

    private static void disableOrdinaryEngine(TrinoRegexp regexp)
    {
        disableOrdinaryEngine(regexp.pattern());
    }

    private static void disableOrdinaryEngine(Re2 pattern)
    {
        // Instruction zero is FAIL.
        Prog semantic = pattern.forwardProgramForDiagnostics();
        semantic.setStart(0);
        semantic.setStartUnanchored(0);
    }

    /**
     * Returns the scan plan for {@code expression}, or null when the plan does not admit it.
     */
    private static TrinoScanPlan analyze(String expression)
    {
        ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(expression), Regexp.LIKE_PERL);
        return TrinoScanPlan.analyze(parsed.regexp(), parsed.capturingGroupCount(), false);
    }

    private static Regex joniPattern(String expression)
    {
        byte[] pattern = expression.getBytes(UTF_8);
        return new Regex(pattern, 0, pattern.length, Option.DEFAULT, NonStrictUTF8Encoding.INSTANCE, Syntax.Java);
    }

    /**
     * Returns 400 views, each of up to 23 symbols drawn from {@code text} and then
     * {@code malformed}, with a byte on either side outside the view.
     */
    private static List<Slice> randomSources(long seed, List<String> text, List<byte[]> malformed)
    {
        List<byte[]> symbols = new ArrayList<>();
        for (String value : text) {
            symbols.add(value.getBytes(UTF_8));
        }
        symbols.addAll(malformed);
        Random random = new Random(seed);
        List<Slice> sources = new ArrayList<>();
        for (int index = 0; index < 400; index++) {
            DynamicSliceOutput output = new DynamicSliceOutput(64);
            output.writeByte('!');
            for (int length = random.nextInt(24); length > 0; length--) {
                output.writeBytes(symbols.get(random.nextInt(symbols.size())));
            }
            output.writeByte('!');
            Slice written = output.slice();
            sources.add(written.slice(1, written.length() - 2));
        }
        return sources;
    }

    /**
     * Returns every match a capturing matcher finds, as start/end pairs for each group.
     */
    private static List<int[]> matcherMatches(TrinoRegexp regexp, Slice source)
    {
        TrinoRegexpMatcher matcher = regexp.matcher(source);
        List<int[]> matches = new ArrayList<>();
        while (matcher.find()) {
            int[] match = new int[2 * (regexp.capturingGroupCount() + 1)];
            for (int group = 0; group <= regexp.capturingGroupCount(); group++) {
                match[2 * group] = matcher.start(group);
                match[2 * group + 1] = matcher.end(group);
            }
            matches.add(match);
        }
        return matches;
    }

    private static Slice view(String value)
    {
        byte[] text = value.getBytes(UTF_8);
        byte[] bytes = new byte[text.length + 8];
        Arrays.fill(bytes, (byte) '9');
        System.arraycopy(text, 0, bytes, 5, text.length);
        return wrappedBuffer(bytes, 5, text.length);
    }

    private static Slice malformedText(String template, byte[] malformed)
    {
        DynamicSliceOutput output = new DynamicSliceOutput(template.length() + 8);
        output.writeByte('!');
        String[] parts = template.split("%s", -1);
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) {
                output.writeBytes(malformed);
            }
            output.writeBytes(utf8Slice(parts[index]));
        }
        output.writeByte('!');
        Slice written = output.slice();
        return written.slice(1, written.length() - 2);
    }

    @Test
    public void testSharedPlanHasNoMutableMatchState()
            throws Exception
    {
        List<TrinoRegexp> regexps = new ArrayList<>();
        for (ExecutorRoute route : EXECUTOR_ROUTES) {
            regexps.add(TrinoRegexp.compile(utf8Slice(route.expression())));
        }
        TrinoRegexp regexp = regexps.getFirst();
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int task = 0; task < 32; task++) {
                String host = "host" + task;
                tasks.add(() -> {
                    for (int iteration = 0; iteration < 100; iteration++) {
                        assertThat(regexp.extract(utf8Slice("https://www." + host + "/path"), 1)).isEqualTo(utf8Slice(host));
                        assertThat(regexp.extract(utf8Slice("https://www./path"), 1)).isEqualTo(utf8Slice("www."));
                        // Interleave every executor so each shared plan sees concurrent use.
                        for (int route = 0; route < EXECUTOR_ROUTES.size(); route++) {
                            Slice input = utf8Slice(EXECUTOR_ROUTES.get(route).text(host));
                            TrinoRegexp shared = regexps.get(route);
                            assertThat(shared.extract(input, 1)).isEqualTo(utf8Slice(host));
                            TrinoRegexpMatcher matcher = shared.matcher(input);
                            assertThat(matcher.find()).isTrue();
                            assertThat(matcher.group(1)).isEqualTo(utf8Slice(host));
                            assertThat(matcher.find()).isFalse();
                        }
                    }
                    return null;
                });
            }
            for (var future : executor.invokeAll(tasks)) {
                future.get();
            }
        }
    }

    @Test
    public void testBenchmarkWorkloadsAgainstJoni()
            throws ReflectiveOperationException
    {
        // Every input selector varies with row % k for k of at most 8 or 32, (row / 4) % 8,
        // (row / 8) % 4, (row / 4) % 6, or (row / count) % k for k of at most 8, where row % count
        // picks the plan. The first max(count * 8, 32) rows give each plan at least eight rows and
        // every value each selector takes over all rows, so they reach every input shape. A
        // forced handoff exercises only unanchored plans, and workloads that select no plan are
        // only checked to select none.
        for (String workload : BenchmarkTrinoScanPlan.workloads()) {
            // The UNANCHORED_RUN workloads lead with a run longer than one character.
            boolean scanPlan = !workload.endsWith("FALLBACK") && !workload.equals("LITERAL_CONTROLS") && !workload.equals("DEEP_REJECTIONS") &&
                    !workload.contains("UNANCHORED_RUN") && !workload.equals("LITERAL_REPEATS") && !workload.equals("UNANCHORED_SET");
            for (boolean dotAll : new boolean[] {false, true}) {
                BenchmarkTrinoScanPlan.BenchmarkData data = new BenchmarkTrinoScanPlan.BenchmarkData();
                data.workload = workload;
                data.dotAll = dotAll;
                data.setup();
                int count = data.expressions.length;
                int rows = Math.min(data.inputs.length, Math.max(count * 8, 32));
                for (int plan = 0; plan < count; plan++) {
                    String expression = data.expressions[plan].toStringUtf8();
                    TrinoRegexp regexp = TrinoRegexp.compile(data.expressions[plan]);
                    if (!scanPlan) {
                        assertThat(regexp.pattern().usesTrinoScanPlanForDiagnostics()).as(expression).isFalse();
                        continue;
                    }
                    List<Slice> sources = new ArrayList<>();
                    for (int row = plan; row < rows; row += count) {
                        sources.add(data.inputs[row]);
                    }
                    Regex oracle = joniPattern(expression);
                    verifyOperations(expression, regexp, source -> joniMatches(oracle, source), sources, true);
                    if (!requireNonNull(analyze(expression), expression).isAnchoredStart()) {
                        TrinoRegexp handoff = TrinoRegexp.compileForcingScanPlanHandoffForTesting(data.expressions[plan]);
                        verifyOperations(expression + " after handoff", handoff, source -> joniMatches(oracle, source), sources, true);
                    }
                }
            }
        }
    }

    /**
     * Adds anchored and bounded boolean calls to {@link #verifyAgainstJoni}. Ranges ending at
     * the input end reach the scan plan; a matcher region is compared with the same bytes as a
     * complete Slice, while a range keeps the complete Slice as assertion context.
     */
    private static void verifyBooleanCallsAgainstJoni(String expression, List<String> inputs)
    {
        verifyAgainstJoni(expression, inputs);
        Slice pattern = utf8Slice(expression);
        verifyBooleanCallsAgainstJoni(expression, TrinoRegexp.compile(pattern).pattern(), inputs, "");
        verifyBooleanCallsAgainstJoni(expression, TrinoRegexp.compileForcingScanPlanHandoffForTesting(pattern).pattern(), inputs, " after handoff");
    }

    private static void verifyBooleanCallsAgainstJoni(String expression, Re2 regexp, List<String> inputs, String route)
    {
        Regex oracle = joniPattern(expression);
        Regex wholeOracle = joniPattern("\\A(?:" + expression + ")\\z");
        for (String text : inputs) {
            Slice source = utf8Slice("padding" + text + "outside").slice(7, utf8Slice(text).length());
            int base = source.byteArrayOffset();
            int end = base + source.length();
            String description = expression + " on " + text + route;
            assertThat(regexp.find(source)).as(description).isEqualTo(oracle.matcher(source.byteArray(), base, end).search(base, end, Option.DEFAULT) >= 0);
            assertThat(regexp.lookingAt(source)).as(description).isEqualTo(oracle.matcher(source.byteArray(), base, end).match(base, end, Option.DEFAULT) >= 0);
            assertThat(regexp.matches(source)).as(description).isEqualTo(wholeOracle.matcher(source.byteArray(), base, end).match(base, end, Option.DEFAULT) >= 0);
            Re2Matcher matcher = regexp.matcher(source);
            int lastStart = source.length() > 64 ? 1 : source.length();
            for (int start = 0; start <= lastStart; start++) {
                if (start < source.length() && (source.getByte(start) & 0xC0) == 0x80) {
                    continue;
                }
                String rangeDescription = description + " from " + start;
                Matcher joni = oracle.matcher(source.byteArray(), base, end);
                assertThat(regexp.find(source, start, source.length())).as(rangeDescription).isEqualTo(joni.search(base + start, end, Option.DEFAULT) >= 0);
                assertThat(regexp.lookingAt(source, start, source.length())).as(rangeDescription).isEqualTo(joni.match(base + start, end, Option.DEFAULT) >= 0);

                Matcher regionJoni = oracle.matcher(source.byteArray(), base + start, end);
                Matcher regionWholeJoni = wholeOracle.matcher(source.byteArray(), base + start, end);
                assertThat(matcher.reset(source, start, source.length()).lookingAt()).as(rangeDescription).isEqualTo(regionJoni.match(base + start, end, Option.DEFAULT) >= 0);
                assertThat(matcher.reset(source, start, source.length()).matches()).as(rangeDescription).isEqualTo(regionWholeJoni.match(base + start, end, Option.DEFAULT) >= 0);
            }
        }
    }

    private static void verifyAgainstJoni(String expression, List<String> inputs)
    {
        verifyAgainstJoni(expression, inputs, true);
    }

    private static void verifyAgainstJoni(String expression, List<String> inputs, boolean scanPlan)
    {
        List<Slice> sources = new ArrayList<>();
        for (String text : inputs) {
            sources.add(utf8Slice("padding" + text + "outside").slice(7, utf8Slice(text).length()));
        }
        verifySourcesAgainstJoni(expression, sources, scanPlan);
    }

    /**
     * Compares every public operation with Joni. On valid UTF-8 the operations are compared
     * again with a plan that continues every unanchored search on the ordinary engine before its
     * first attempt. Malformed sources are excluded from that run: past a handoff they may get
     * the ordinary engine's answer rather than the plan's.
     */
    private static void verifySourcesAgainstJoni(String expression, List<Slice> sources, boolean scanPlan)
    {
        Slice pattern = utf8Slice(expression);
        Regex oracle = joniPattern(expression);
        verifyOperations(expression, TrinoRegexp.compile(pattern), source -> joniMatches(oracle, source), sources, scanPlan);
        List<Slice> validSources = sources.stream()
                .filter(source -> Utf8.firstInvalidOffset(source) < 0)
                .toList();
        verifyOperations(expression + " after handoff", TrinoRegexp.compileForcingScanPlanHandoffForTesting(pattern), source -> joniMatches(oracle, source), validSources, scanPlan);
    }

    /**
     * Compares every public operation of {@code regexp} on each source with the matches, as
     * start/end pairs for each group, that {@code expectedMatches} returns for it.
     */
    private static void verifyOperations(String description, TrinoRegexp regexp, Function<Slice, List<int[]>> expectedMatches, List<Slice> sources, boolean scanPlan)
    {
        assertThat(regexp.pattern().usesTrinoScanPlanForDiagnostics()).as(description).isEqualTo(scanPlan);
        int groupCount = regexp.capturingGroupCount();
        int replacementGroup = groupCount == 0 ? 0 : 1;
        Slice replacementPattern = utf8Slice("$" + replacementGroup);
        TrinoRegexpMatcher reusable = regexp.matcher(EMPTY_SLICE);
        TrinoRegexpMatcher groupZero = regexp.matcher(EMPTY_SLICE, 0);
        for (Slice source : sources) {
            String sourceDescription = description + " on " + (Utf8.firstInvalidOffset(source) < 0 ? source.toStringUtf8() : HexFormat.of().formatHex(source.getBytes()));
            List<int[]> matches = expectedMatches.apply(source);
            assertThat(regexp.contains(source)).as(sourceDescription).isEqualTo(!matches.isEmpty());
            assertThat(regexp.count(source)).as(sourceDescription).isEqualTo(matches.size());
            for (int occurrence = 1; occurrence <= matches.size() + 1; occurrence++) {
                long position = occurrence > matches.size() ? -1 : countCodePoints(source, 0, matches.get(occurrence - 1)[0]) + 1L;
                assertThat(regexp.position(source, 1, occurrence)).as("%s, occurrence %s", sourceDescription, occurrence).isEqualTo(position);
            }

            reusable.reset(source);
            groupZero.reset(source);
            for (int[] match : matches) {
                assertThat(reusable.find()).as(sourceDescription).isTrue();
                assertThat(groupZero.find()).as(sourceDescription).isTrue();
                assertThat(groupZero.start()).as(sourceDescription).isEqualTo(match[0]);
                assertThat(groupZero.end()).as(sourceDescription).isEqualTo(match[1]);
                for (int group = 0; group <= groupCount; group++) {
                    assertThat(reusable.start(group)).as(sourceDescription).isEqualTo(match[2 * group]);
                    assertThat(reusable.end(group)).as(sourceDescription).isEqualTo(match[2 * group + 1]);
                    assertThat(reusable.group(group)).as("%s, group %s", sourceDescription, group).isEqualTo(group(source, match, group));
                }
            }
            assertThat(reusable.find()).as(sourceDescription).isFalse();
            assertThatThrownBy(reusable::start).isInstanceOf(IllegalStateException.class);
            assertThat(groupZero.find()).as(sourceDescription).isFalse();

            for (int group = 0; group <= groupCount; group++) {
                Slice first = matches.isEmpty() ? null : group(source, matches.getFirst(), group);
                assertThat(regexp.extract(source, group)).as("%s, group %s", sourceDescription, group).isEqualTo(first);
                List<Slice> all = new ArrayList<>();
                for (int[] match : matches) {
                    all.add(group(source, match, group));
                }
                assertThat(regexp.extractAll(source, group)).as("%s, group %s", sourceDescription, group).isEqualTo(all);
            }

            List<Slice> parts = new ArrayList<>();
            DynamicSliceOutput replaced = new DynamicSliceOutput(source.length());
            DynamicSliceOutput replacedByCallback = new DynamicSliceOutput(source.length());
            int previousEnd = 0;
            for (int[] match : matches) {
                parts.add(source.slice(previousEnd, match[0] - previousEnd));
                replaced.writeBytes(source, previousEnd, match[0] - previousEnd);
                replacedByCallback.writeBytes(source, previousEnd, match[0] - previousEnd);
                Slice replacementValue = group(source, match, replacementGroup);
                replaced.writeBytes(replacementValue == null ? EMPTY_SLICE : replacementValue);
                List<Slice> groups = new ArrayList<>();
                for (int group = 1; group <= groupCount; group++) {
                    groups.add(group(source, match, group));
                }
                replacedByCallback.writeBytes(callbackReplacement(groups));
                previousEnd = match[1];
            }
            parts.add(source.slice(previousEnd, source.length() - previousEnd));
            replaced.writeBytes(source, previousEnd, source.length() - previousEnd);
            replacedByCallback.writeBytes(source, previousEnd, source.length() - previousEnd);
            assertThat(regexp.split(source)).as(sourceDescription).isEqualTo(parts);
            if (matches.isEmpty()) {
                assertThat(regexp.replace(source, replacementPattern)).as(sourceDescription).isSameAs(source);
            }
            else {
                assertThat(regexp.replace(source, replacementPattern)).as(sourceDescription).isEqualTo(replaced.slice());
            }
            assertThat(regexp.replace(source, TestTrinoScanPlan::callbackReplacement)).as(sourceDescription).isEqualTo(replacedByCallback.slice());
        }
    }

    /**
     * Returns every non-overlapping Joni match as start/end pairs for each group, searching again
     * from the previous match end as Trino's Joni functions do.
     */
    private static List<int[]> joniMatches(Regex oracle, Slice source)
    {
        int base = source.byteArrayOffset();
        Matcher matcher = oracle.matcher(source.byteArray(), base, base + source.length());
        List<int[]> matches = new ArrayList<>();
        int next = 0;
        while (next <= source.length() && matcher.search(base + next, base + source.length(), Option.DEFAULT) >= 0) {
            Region region = matcher.getEagerRegion();
            int[] match = new int[2 * (oracle.numberOfCaptures() + 1)];
            for (int group = 0; group <= oracle.numberOfCaptures(); group++) {
                match[2 * group] = region.beg[group];
                match[2 * group + 1] = region.end[group];
            }
            matches.add(match);
            next = matcher.getEnd();
            if (matcher.getBegin() == next) {
                next += next < source.length() ? lengthOfCodePointFromStartByte(source.getByte(next)) : 1;
            }
        }
        return matches;
    }

    private static Slice group(Slice source, int[] match, int group)
    {
        int start = match[2 * group];
        return start < 0 ? null : source.slice(start, match[2 * group + 1] - start);
    }

    private static Slice callbackReplacement(List<Slice> groups)
    {
        DynamicSliceOutput output = new DynamicSliceOutput(16);
        output.writeByte('<');
        for (Slice group : groups) {
            if (group == null) {
                output.writeByte('-');
            }
            else {
                output.writeBytes(group);
            }
            output.writeByte('|');
        }
        output.writeByte('>');
        return output.slice();
    }
}
