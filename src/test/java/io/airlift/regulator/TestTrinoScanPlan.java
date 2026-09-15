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
import java.util.HexFormat;
import java.util.List;
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

    private record LiteralKernelShape(String expression, Re2.BooleanPartialMatchStrategy strategy, String text, String group) {}

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
        for (String expression : List.of("(?s)^([^/]+)/.*$", "^([a-z]+):([^/:]{2,4})/$", "^(?:(a)(b))?ab([^/]+)/$")) {
            Slice pattern = utf8Slice(expression);
            ParseResult parsed = TrinoRegexpParser.parse(pattern, Regexp.LIKE_PERL);
            long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
            Prog control = Compiler.compileNormalized(Simplifier.simplify(parsed.regexp()), false, maxMemory - maxMemory / 3, Compiler.Dialect.TRINO);
            TrinoScanPlan plan = analyze(expression);
            assertThat(plan).isNotNull();
            assertThat(TrinoRegexp.compile(pattern).pattern().forwardProgramForDiagnostics().dfaMemory())
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

    private static String scanOperations(String expression)
    {
        return requireNonNull(analyze(expression)).operationsForDiagnostics();
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
                "https?://([^/]+)/.*$",
                "(?m)^([^/]+)/$",
                "(?i)^([^/]+)/$",
                "^([^/]+?)/$",
                "^([^/]+)/.*?$",
                "^([^é]+)é$",
                "^([a-z]+)abc$",
                "^([a-z]{2,4})a$",
                "^([a-z]+)(?:a)?/$",
                "^([a-z]*)$",
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

        // Find the first budget that fits the semantic program. Object layouts differ with
        // compressed references, so avoid baking a particular byte threshold into the test.
        for (int memory = 1024; memory <= 65536; memory += 32) {
            TrinoRegexp tiny;
            try {
                tiny = TrinoRegexp.compile(utf8Slice(URL), TrinoRegexp.Options.defaults().setMaxMemory(memory));
            }
            catch (RegexpCompileMemoryLimitException _) {
                continue;
            }
            assertThat(tiny.pattern().usesTrinoScanPlanForDiagnostics()).isFalse();
            assertThat(tiny.extract(utf8Slice("https://host/path"), 1)).isEqualTo(utf8Slice("host"));
            return;
        }
        throw new AssertionError("no budget admitted the semantic program");
    }

    @Test
    public void testBooleanTailOperations()
    {
        // A RUN may end a capture-free partial match only when everything after it is nullable.
        assertThat(booleanTails("^abc.*")).isEqualTo("1:RUN");
        assertThat(booleanTails("^https?://([^/]+)")).isEqualTo("3:RUN");
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
                "^abc(x)?")) {
            assertThat(booleanTails(expression)).as(expression).isEmpty();
        }
    }

    private static String booleanTails(String expression)
    {
        return requireNonNull(analyze(expression)).booleanTailOperationsForDiagnostics();
    }

    @Test
    public void testLiteralBooleanKernelsKeepPlanForBoundaries()
    {
        for (LiteralKernelShape shape : List.of(
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
        for (String expression : List.of("^abc.*", "(?s)^abc.*", "^abc.*$", "^abc(?:xy)?z*$", "^abc(?:(x+))?y*", "^abc(?:(xy))?z*")) {
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
        for (String expression : List.of("^https?://([^/]+)", "^https?://([^/]{3,})", "^https?://([^/]+)/", URL, "(?s)" + URL)) {
            verifyBooleanCallsAgainstJoni(expression, urls);
        }
        // Retained literal kernels, including final-newline equality.
        List<String> literals = List.of("", "abc", "abc\n", "abc\nx", "abc\n\n", "abcd", "xabc", "xxabcdef\nz", "ab");
        for (String expression : List.of("^(abc)", "^(abc)$", "^(abc)\\z")) {
            verifyBooleanCallsAgainstJoni(expression, literals);
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
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(anchoredExpression));
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
            assertThat(Re2.compile(utf8Slice(anchoredExpression)).find(input)).isFalse();
            assertThat(JavaRegexp.compile(utf8Slice(anchoredExpression)).find(input)).isFalse();
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
        return TrinoScanPlan.analyze(parsed.regexp(), parsed.capturingGroupCount());
    }

    private static Regex joniPattern(String expression)
    {
        byte[] pattern = expression.getBytes(UTF_8);
        return new Regex(pattern, 0, pattern.length, Option.DEFAULT, NonStrictUTF8Encoding.INSTANCE, Syntax.Java);
    }

    @Test
    public void testSharedPlanHasNoMutableMatchState()
            throws Exception
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(URL));
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int task = 0; task < 32; task++) {
                String host = "host" + task;
                tasks.add(() -> {
                    for (int iteration = 0; iteration < 100; iteration++) {
                        assertThat(regexp.extract(utf8Slice("https://www." + host + "/path"), 1)).isEqualTo(utf8Slice(host));
                        assertThat(regexp.extract(utf8Slice("https://www./path"), 1)).isEqualTo(utf8Slice("www."));
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
        // every value each selector takes over all rows, so they reach every input shape.
        // Workloads that select no plan are only checked to select none.
        for (String workload : BenchmarkTrinoScanPlan.workloads()) {
            boolean scanPlan = !workload.endsWith("FALLBACK") && !workload.equals("LITERAL_CONTROLS") && !workload.equals("DEEP_REJECTIONS") &&
                    !workload.contains("UNANCHORED_") && !workload.startsWith("FOLDED_") && !workload.equals("LITERAL_REPEATS");
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
        Re2 regexp = TrinoRegexp.compile(utf8Slice(expression)).pattern();
        Regex oracle = joniPattern(expression);
        Regex wholeOracle = joniPattern("\\A(?:" + expression + ")\\z");
        for (String text : inputs) {
            Slice source = utf8Slice("padding" + text + "outside").slice(7, utf8Slice(text).length());
            int base = source.byteArrayOffset();
            int end = base + source.length();
            String description = expression + " on " + text;
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
     * Compares every public operation with Joni.
     */
    private static void verifySourcesAgainstJoni(String expression, List<Slice> sources, boolean scanPlan)
    {
        Regex oracle = joniPattern(expression);
        verifyOperations(expression, TrinoRegexp.compile(utf8Slice(expression)), source -> joniMatches(oracle, source), sources, scanPlan);
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
