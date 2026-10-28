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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// Generated from the pinned Rust regex crate by tools/rust-regex-golden.
public class TestRustRegexpGolden
{
    private static final String SYNTAX_FIXTURE = "io/airlift/regulator/rust-regex-syntax.jsonl";
    private static final String MATCH_FIXTURE = "io/airlift/regulator/rust-regex-match.jsonl";
    private static final String RANDOM_FIXTURE = "io/airlift/regulator/rust-regex-random.jsonl";
    private static final String UPSTREAM_FIXTURE = "io/airlift/regulator/rust-regex-upstream.jsonl";
    private static final String UPSTREAM_BYTES_FIXTURE = "io/airlift/regulator/rust-regex-upstream-bytes.jsonl";
    private static final List<String> FIXTURES = List.of(SYNTAX_FIXTURE, MATCH_FIXTURE, RANDOM_FIXTURE, UPSTREAM_FIXTURE, UPSTREAM_BYTES_FIXTURE);

    // Patterns the crate accepts that Regulator rejects by design, by fixture.
    private static final Map<String, Map<Gap, Integer>> GAP_COUNTS = Map.of(
            SYNTAX_FIXTURE, Map.of(Gap.REPETITION_COUNT, 1),
            MATCH_FIXTURE, Map.of(),
            RANDOM_FIXTURE, Map.of(),
            UPSTREAM_FIXTURE, Map.of(Gap.REPETITION_COUNT, 1),
            UPSTREAM_BYTES_FIXTURE, Map.of());

    // Cases naming a property or value that Regulator's Unicode 18.0 database holds and the crate's
    // Unicode 16.0 tables lack, by fixture and case ID, with the reason. The crate rejects each
    // name, and Regulator must still compile it.
    private static final Map<String, Map<String, String>> EXPECTED_DIFFERENCES = Map.of(
            SYNTAX_FIXTURE, Map.of("property_age_future", "Age 17.0 is in the database's Unicode 18.0 data"));

    // The crate records at most this many successive matches.
    private static final int RECORDED_MATCH_LIMIT = 64;

    private static final HexFormat HEX_FORMAT = HexFormat.of();

    // The Regulator error code for each regex-syntax error kind.
    private static final Map<String, RegexpParseErrorCode> ERROR_CODES = Map.ofEntries(
            entry("CaptureLimitExceeded", RegexpParseErrorCode.PATTERN_TOO_LARGE),
            entry("ClassEscapeInvalid", RegexpParseErrorCode.BAD_ESCAPE),
            entry("ClassRangeInvalid", RegexpParseErrorCode.BAD_CHAR_RANGE),
            entry("ClassRangeLiteral", RegexpParseErrorCode.BAD_CHAR_RANGE),
            entry("ClassUnclosed", RegexpParseErrorCode.MISSING_BRACKET),
            entry("DecimalInvalid", RegexpParseErrorCode.REPEAT_SIZE),
            entry("EscapeHexEmpty", RegexpParseErrorCode.BAD_ESCAPE),
            entry("EscapeHexInvalid", RegexpParseErrorCode.BAD_ESCAPE),
            entry("EscapeHexInvalidDigit", RegexpParseErrorCode.BAD_ESCAPE),
            entry("EscapeUnexpectedEof", RegexpParseErrorCode.BAD_ESCAPE),
            entry("EscapeUnrecognized", RegexpParseErrorCode.BAD_ESCAPE),
            entry("FlagDanglingNegation", RegexpParseErrorCode.BAD_PERL_OP),
            entry("FlagDuplicate", RegexpParseErrorCode.BAD_PERL_OP),
            entry("FlagRepeatedNegation", RegexpParseErrorCode.BAD_PERL_OP),
            entry("FlagUnexpectedEof", RegexpParseErrorCode.MISSING_PAREN),
            entry("FlagUnrecognized", RegexpParseErrorCode.BAD_PERL_OP),
            entry("GroupNameDuplicate", RegexpParseErrorCode.BAD_NAMED_CAPTURE),
            entry("GroupNameEmpty", RegexpParseErrorCode.BAD_NAMED_CAPTURE),
            entry("GroupNameInvalid", RegexpParseErrorCode.BAD_NAMED_CAPTURE),
            entry("GroupNameUnexpectedEof", RegexpParseErrorCode.BAD_NAMED_CAPTURE),
            entry("GroupUnclosed", RegexpParseErrorCode.MISSING_PAREN),
            entry("GroupUnopened", RegexpParseErrorCode.UNEXPECTED_PAREN),
            entry("InvalidUtf8", RegexpParseErrorCode.BAD_UTF8),
            entry("NestLimitExceeded", RegexpParseErrorCode.PATTERN_TOO_LARGE),
            entry("RepetitionCountDecimalEmpty", RegexpParseErrorCode.REPEAT_ARGUMENT),
            entry("RepetitionCountInvalid", RegexpParseErrorCode.REPEAT_ARGUMENT),
            entry("RepetitionCountUnclosed", RegexpParseErrorCode.MISSING_BRACKET),
            entry("RepetitionMissing", RegexpParseErrorCode.REPEAT_ARGUMENT),
            entry("SpecialWordBoundaryUnclosed", RegexpParseErrorCode.BAD_ESCAPE),
            entry("SpecialWordBoundaryUnrecognized", RegexpParseErrorCode.BAD_ESCAPE),
            entry("SpecialWordOrRepetitionUnexpectedEof", RegexpParseErrorCode.BAD_ESCAPE),
            entry("UnicodeClassInvalid", RegexpParseErrorCode.BAD_ESCAPE),
            entry("UnicodeNotAllowed", RegexpParseErrorCode.BAD_CHAR_CLASS),
            entry("UnicodePropertyNotFound", RegexpParseErrorCode.BAD_CHAR_RANGE),
            entry("UnicodePropertyValueNotFound", RegexpParseErrorCode.BAD_CHAR_RANGE),
            entry("UnsupportedBackreference", RegexpParseErrorCode.UNSUPPORTED_CONSTRUCT),
            entry("UnsupportedLookAround", RegexpParseErrorCode.UNSUPPORTED_CONSTRUCT));

    // A pattern the crate accepts that Regulator rejects by design.
    private enum Gap
    {
        REPETITION_COUNT;

        static Gap of(RegexpParseException exception)
        {
            String message = exception.getMessage();
            return switch (exception.errorCode()) {
                case REPEAT_SIZE -> message.contains("repetition count above 1000") ? REPETITION_COUNT : null;
                default -> null;
            };
        }
    }

    // A case's pattern compiled once: the compiled pattern, or Regulator's rejection of it.
    private record Compilation(Slice pattern, RustRegexp regexp, RegexpParseException rejection)
    {
        static Compilation of(GoldenJsonl.JsonObject testCase)
        {
            Slice pattern = decodeHex(testCase.getString("patternHex"));
            try {
                return new Compilation(pattern, RustRegexp.compile(pattern, options(testCase.getString("options"))), null);
            }
            catch (RegexpParseException e) {
                return new Compilation(pattern, null, e);
            }
        }

        // The gap that rejects a pattern the crate accepts, or null when there is none.
        Gap gap(GoldenJsonl.JsonObject testCase)
        {
            return testCase.getBoolean("ok") && rejection != null ? Gap.of(rejection) : null;
        }
    }

    @TestFactory
    public List<DynamicTest> testSyntaxCases()
    {
        return cases(SYNTAX_FIXTURE);
    }

    @TestFactory
    public List<DynamicTest> testMatchCases()
    {
        return cases(MATCH_FIXTURE);
    }

    @TestFactory
    public List<DynamicTest> testRandomCases()
    {
        return cases(RANDOM_FIXTURE);
    }

    @TestFactory
    public List<DynamicTest> testUpstreamCases()
    {
        return cases(UPSTREAM_FIXTURE);
    }

    @TestFactory
    public List<DynamicTest> testUpstreamBytesCases()
    {
        return cases(UPSTREAM_BYTES_FIXTURE);
    }

    @Test
    public void testGapCounts()
    {
        Map<String, Map<Gap, Integer>> gapCounts = new TreeMap<>();
        for (String fixture : FIXTURES) {
            Map<Gap, Integer> counts = new EnumMap<>(Gap.class);
            for (GoldenJsonl.JsonObject testCase : GoldenJsonl.readObjects(fixture)) {
                Gap gap = Compilation.of(testCase).gap(testCase);
                if (gap != null) {
                    counts.merge(gap, 1, Integer::sum);
                }
            }
            gapCounts.put(fixture, counts);
        }
        assertThat(gapCounts).isEqualTo(GAP_COUNTS);
    }

    // Cases that a gap rejects are left out; testGapCounts pins how many there are.
    private static List<DynamicTest> cases(String fixture)
    {
        Map<String, String> expectedDifferences = EXPECTED_DIFFERENCES.getOrDefault(fixture, Map.of());
        List<DynamicTest> tests = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (GoldenJsonl.JsonObject testCase : GoldenJsonl.readObjects(fixture)) {
            String id = testCase.getString("id");
            ids.add(id);
            Compilation compilation = Compilation.of(testCase);
            String difference = expectedDifferences.get(id);
            if (difference != null) {
                tests.add(DynamicTest.dynamicTest(id, () -> {
                    assertThat(testCase.getString("errorKind")).as("crate error").isEqualTo("UnicodePropertyValueNotFound");
                    assertThat(compilation.rejection()).as("expected difference: " + difference).isNull();
                }));
            }
            else if (compilation.gap(testCase) == null) {
                tests.add(DynamicTest.dynamicTest(id, () -> assertCase(testCase, compilation)));
            }
        }
        assertThat(ids).as("cases with expected differences").containsAll(expectedDifferences.keySet());
        return tests;
    }

    private static void assertCase(GoldenJsonl.JsonObject testCase, Compilation compilation)
    {
        if (!testCase.getBoolean("ok")) {
            assertRejection(testCase, compilation);
            return;
        }
        if (compilation.rejection() != null) {
            throw new AssertionError("rejected a pattern the crate accepts", compilation.rejection());
        }
        RustRegexp regexp = compilation.regexp();
        assertThat(regexp.capturingGroupCount() + 1)
                .as("group count")
                .isEqualTo(Integer.parseInt(testCase.getString("groupCount")));
        assertThat(groupNames(regexp)).as("group names").isEqualTo(testCase.getString("groupNames"));
        if (testCase.getString("textHex") != null) {
            assertMatches(testCase, regexp);
        }
    }

    private static void assertRejection(GoldenJsonl.JsonObject testCase, Compilation compilation)
    {
        String error = testCase.getString("error");
        RegexpParseException exception = compilation.rejection();
        if (exception == null) {
            fail("accepted a pattern the crate rejects with " + error + " " + testCase.getString("errorKind"));
        }
        if (error.equals("size_limit")) {
            assertThat(exception.errorCode()).as("error code").isEqualTo(RegexpParseErrorCode.REPEAT_SIZE);
            return;
        }
        String errorKind = testCase.getString("errorKind");
        assertThat(exception.errorCode()).as("error code for " + errorKind).isEqualTo(ERROR_CODES.get(errorKind));
        int errorStart = Integer.parseInt(testCase.getString("errorStart"));
        int errorEnd = Integer.parseInt(testCase.getString("errorEnd"));
        Slice pattern = compilation.pattern();
        assertThat(exception.byteOffset()).as("error offset for " + errorKind).isEqualTo(errorStart);
        assertThat(exception.getMessage()).as("error message for " + errorKind).endsWith(": " + errorKind + " at byte offset " + errorStart);
        assertThat(exception.invalidPatternSegment())
                .as("error segment for " + errorKind)
                .isEqualTo(errorEnd > errorStart ? pattern.slice(errorStart, errorEnd - errorStart) : null);
    }

    private static void assertMatches(GoldenJsonl.JsonObject testCase, RustRegexp regexp)
    {
        Slice text = decodeHex(testCase.getString("textHex"));
        boolean windowed = !testCase.getString("start").equals("-");
        int start = windowed ? Integer.parseInt(testCase.getString("start")) : 0;
        int end = windowed ? Integer.parseInt(testCase.getString("end")) : text.length();

        assertThat(windowed ? regexp.find(text, start, end) : regexp.find(text))
                .as("isMatch")
                .isEqualTo(testCase.getBoolean("isMatch"));
        assertThat(groups(windowed ? regexp.findResult(text, start, end) : regexp.findResult(text)))
                .as("captures")
                .isEqualTo(testCase.getString("captures"));
        assertThat(groups(windowed ? regexp.lookingAtResult(text, start, end) : regexp.lookingAtResult(text)))
                .as("anchoredCaptures")
                .isEqualTo(testCase.getString("anchoredCaptures"));
        if (isFullCapturesDefined(testCase)) {
            assertThat(groups(windowed ? regexp.matchesResult(text, start, end) : regexp.matchesResult(text)))
                    .as("fullCaptures")
                    .isEqualTo(testCase.getString("fullCaptures"));
        }

        RegexpMatcher matcher = regexp.matcher(text);
        if (windowed) {
            matcher.reset(text, start, end);
        }
        List<String> matches = new ArrayList<>();
        long matchCount = 0;
        while (matcher.find()) {
            matchCount++;
            if (matches.size() < RECORDED_MATCH_LIMIT) {
                matches.add(groups(matcher.toMatchResult()));
            }
        }
        assertThat(String.join(";", matches)).as("matches").isEqualTo(testCase.getString("matches"));
        assertThat(matchCount).as("matchCount").isEqualTo(Long.parseLong(testCase.getString("matchCount")));
        if (!windowed) {
            assertThat(regexp.count(text)).as("count").isEqualTo(matchCount);
        }
    }

    // Only a windowed record can leave the full match undefined.
    private static boolean isFullCapturesDefined(GoldenJsonl.JsonObject testCase)
    {
        return testCase.getString("start").equals("-") || testCase.getBoolean("fullDefined");
    }

    private static RustRegexp.Options options(String value)
    {
        RustRegexp.Options options = RustRegexp.Options.defaults();
        if (value.equals("-")) {
            return options;
        }
        for (String option : value.split(",", -1)) {
            if (option.equals("-u")) {
                options.setUnicode(false);
                continue;
            }
            if (option.equals("bytes")) {
                options.setBytes(true);
                continue;
            }
            if (option.startsWith("n")) {
                options.setNestLimit(Integer.parseInt(option.substring(1)));
                continue;
            }
            for (int index = 0; index < option.length(); index++) {
                switch (option.charAt(index)) {
                    case 'i' -> options.setCaseInsensitive(true);
                    case 'm' -> options.setMultiline(true);
                    case 's' -> options.setDotMatchesNewline(true);
                    case 'R' -> options.setCrlf(true);
                    case 'U' -> options.setSwapGreed(true);
                    case 'x' -> options.setComments(true);
                    case 'o' -> options.setOctal(true);
                    default -> throw new IllegalArgumentException("unknown option: " + value);
                }
            }
        }
        return options;
    }

    private static Slice decodeHex(String value)
    {
        return Slices.wrappedBuffer(value.equals("-") ? new byte[0] : HEX_FORMAT.parseHex(value));
    }

    private static String groupNames(RustRegexp regexp)
    {
        String[] names = new String[regexp.capturingGroupCount() + 1];
        Arrays.fill(names, "-");
        regexp.namedCapturingGroups().forEach((name, index) -> names[index] = name);
        return String.join(",", names);
    }

    private static String groups(MatchResult result)
    {
        if (result == null) {
            return null;
        }
        List<String> groups = new ArrayList<>();
        for (int group = 0; group <= result.groupCount(); group++) {
            groups.add(result.matched(group) ? result.start(group) + ":" + result.end(group) : "-1:-1");
        }
        return String.join(",", groups);
    }
}
