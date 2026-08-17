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

import java.util.List;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestFixedWidthByteSpanMatcher
{
    @Test
    public void testTrinoCompactAnalysisEligibility()
    {
        assertThat(Re2.shouldAnalyzeFixedWidthByteSpanForTrino(
                TrinoRegexpParser.parse(utf8Slice("literal"), Regexp.LIKE_PERL))).isFalse();
        assertThat(Re2.shouldAnalyzeFixedWidthByteSpanForTrino(
                TrinoRegexpParser.parse(utf8Slice("[0-9]{2}-[0-9]{2}"), Regexp.LIKE_PERL))).isTrue();
        assertThat(Re2.shouldAnalyzeFixedWidthByteSpanForTrino(
                TrinoRegexpParser.parse(utf8Slice("([0-9]{2}-[0-9]{2})"), Regexp.LIKE_PERL))).isFalse();
    }

    @Test
    public void testTrinoFixedWidthRouteDoesNotRetainUnusedSemanticProgram()
    {
        Slice expression = utf8Slice("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        TrinoRegexp regexp = TrinoRegexp.compile(expression);
        Re2 pattern = regexp.pattern();

        // Pinned re2_golden reports group zero at 1:37 for this unanchored search.
        Slice input = utf8Slice("!123e4567-e89b-12d3-a456-426614174000?");
        assertThat(regexp.contains(input)).isTrue();
        assertThat(regexp.extract(input)).isEqualTo(input.slice(1, 36));
        assertThat(regexp.count(input)).isEqualTo(1);
        assertThat(regexp.replace(input, utf8Slice("<$0>")))
                .isEqualTo(utf8Slice("!<123e4567-e89b-12d3-a456-426614174000>?"));
        assertThatThrownBy(() -> regexp.replace(input, utf8Slice("$1")))
                .isInstanceOf(TrinoRegexpReplacementException.class);
        assertThat(regexp.contains(utf8Slice("not a uuid"))).isFalse();

        Slice repeated = utf8Slice("!123e4567-e89b-12d3-a456-426614174000?123e4567-e89b-12d3-a456-426614174001.");
        assertThat(regexp.count(repeated)).isEqualTo(2);
        assertThat(regexp.position(repeated, 1, 2)).isEqualTo(39);
        assertThat(regexp.extractAll(repeated)).containsExactly(
                utf8Slice("123e4567-e89b-12d3-a456-426614174000"),
                utf8Slice("123e4567-e89b-12d3-a456-426614174001"));
        assertThat(regexp.split(repeated)).containsExactly(utf8Slice("!"), utf8Slice("?"), utf8Slice("."));
        assertThat(regexp.replace(repeated, _ -> utf8Slice("X"))).isEqualTo(utf8Slice("!X?X."));

        // The fixed-width Trino subtype handles every public operation from the retained
        // byte predicates. Its semantic program must therefore be the compact sentinel rather
        // than the otherwise unused 37-state OnePass program.
        assertThat(pattern.hasRetainedFixedWidthByteSpanMatcher()).isTrue();
        assertThat(pattern.forwardProgramForDiagnostics().isOnePass()).isFalse();

        // Keep the optimization isolated to Trino's compiled fixed-width route.
        assertThat(Re2.compile(expression).forwardProgramForDiagnostics().isOnePass()).isTrue();
    }

    @Test
    public void testCompactTrinoRouteMemoryAccounting()
    {
        Slice expression = utf8Slice("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        long maxMemory = 4 * 1024;
        Re2 pattern = TrinoRegexp.compile(expression, TrinoRegexp.Options.defaults().setMaxMemory(maxMemory)).pattern();
        FixedWidthByteSpanMatcher matcher = pattern.createFixedWidthByteSpanMatcher();
        assertThat(pattern.hasRetainedFixedWidthByteSpanMatcher()).isTrue();
        assertThat(Compiler.estimatedProgramMemory(pattern.forwardProgramForDiagnostics()) + matcher.estimatedRetainedSize())
                .isLessThanOrEqualTo(maxMemory - (maxMemory / 3));

        assertThatThrownBy(() -> TrinoRegexp.compile(expression, TrinoRegexp.Options.defaults().setMaxMemory(512)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    @Test
    public void testPinnedNativeBoundaries()
    {
        FixedWidthByteSpanMatcher dates = matcher("[0-9]{4}-[0-9]{2}-[0-9]{2}");
        Slice dateInput = utf8Slice("bad 2026/08/10 then 2025-12-09 and 2024-01-31");
        assertSpan(dates, dateInput, 0, 20, 30);
        assertSpan(dates, dateInput, 30, 35, 45);

        FixedWidthByteSpanMatcher phones = matcher("\\d{3}/\\d{3}/\\d{4}");
        Slice phoneInput = utf8Slice("bad 415-555-0100 then 212/555/0101 or 650/555/0142");
        assertSpan(phones, phoneInput, 4, 22, 34);
        assertSpan(phones, phoneInput, 34, 38, 50);
    }

    @Test
    public void testFalseCandidatesLogicalOffsetsAndAdjacentMatches()
    {
        FixedWidthByteSpanMatcher matcher = matcher("[0-9]{2}-[0-9]{2}");
        Slice backing = utf8Slice("!xx-yy 12-x4 12-3412-34?");
        Slice input = Slices.wrappedBuffer(backing.byteArray(), backing.byteArrayOffset() + 1, backing.length() - 2);
        assertSpan(matcher, input, 0, 12, 17);
        assertSpan(matcher, input, 17, 17, 22);
        assertThat(matcher.findSpan(input, 22)).isEqualTo(Dfa.SEARCH_NO_MATCH);
    }

    @Test
    public void testMalformedUtf8AndLatin1()
    {
        FixedWidthByteSpanMatcher matcher = matcher("[0-9]{2}-[0-9]{2}");
        Slice malformed = wrappedBuffer(new byte[] {
                '1', (byte) 0xFF, '-', '3', '4', ' ', '1', '2', '-', '3', '4',
        });
        assertSpan(matcher, malformed, 0, 6, 11);

        Re2 latin1Pattern = Re2.compile(utf8Slice("[0-9]{2}-[0-9]{2}"), Re2.Options.latin1());
        FixedWidthByteSpanMatcher latin1 = latin1Pattern.createFixedWidthByteSpanMatcher();
        assertThat(latin1).isNotNull();
        assertSpan(latin1, wrappedBuffer(new byte[] {(byte) 0xFF, '1', '2', '-', '3', '4'}), 0, 1, 6);
    }

    @Test
    public void testEligibility()
    {
        assertThat(optionalMatcher("[0-9]{4}-[0-9]{2}-[0-9]{2}")).isNotNull();
        assertThat(optionalMatcher("\\d{3}/\\d{3}/\\d{4}")).isNotNull();
        assertThat(optionalMatcher("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}")).isNotNull();
        assertThat(optionalMatcher("literal")).isNull();
        assertThat(optionalMatcher("[0-9]{4}")).isNull();
        assertThat(optionalMatcher("[,;]")).isNull();
        assertThat(optionalMatcher("[0-9]+-[0-9]+")).isNull();
        assertThat(optionalMatcher("\"([^\"]*)\"")).isNull();
        assertThat(optionalMatcher("([0-9]{2}-[0-9]{2})")).isNull();
        assertThat(optionalMatcher("^[0-9]{2}-[0-9]{2}")).isNull();
        assertThat(optionalMatcher("[0-9]{2}-[0-9]{2}$")).isNull();
        assertThat(optionalMatcher("(?i:[a-f]{2}-[a-f]{2})")).isNull();
        assertThat(optionalMatcher("[é]{2}-[0-9]{2}")).isNull();
        assertThat(optionalMatcher("(?:[0-9]{2}|[a-z]{2})-[0-9]{2}")).isNull();
    }

    @Test
    public void testGeneralEngineAgreement()
    {
        List<String> patterns = List.of(
                "[0-9]{4}-[0-9]{2}-[0-9]{2}",
                "\\d{3}/\\d{3}/\\d{4}",
                "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}");
        List<Slice> inputs = List.of(
                utf8Slice(""),
                utf8Slice("ordinary text"),
                utf8Slice("2025-12-09"),
                utf8Slice("bad 2026/08/10 then 2025-12-09 and 2024-01-31"),
                utf8Slice("212/555/0101 or 650/555/0142"),
                utf8Slice("01234567-89ab"),
                utf8Slice("é💰2025-12-09ж終"),
                wrappedBuffer(new byte[] {'1', (byte) 0xFF, '-', '3', '4'}));

        for (String expression : patterns) {
            Re2 pattern = Re2.compile(utf8Slice(expression));
            FixedWidthByteSpanMatcher matcher = pattern.createFixedWidthByteSpanMatcher();
            assertThat(matcher).as(expression).isNotNull();
            for (Slice input : inputs) {
                for (int start = 0; start <= input.length(); start++) {
                    Re2Matcher generalMatcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
                    long span = matcher.findSpan(input, start);
                    if (generalMatcher.find(start)) {
                        assertThat(span)
                                .as("pattern %s input %s start %s", expression, input, start)
                                .isEqualTo(((long) generalMatcher.start() << 32) | (generalMatcher.end() & 0xFFFF_FFFFL));
                    }
                    else {
                        assertThat(span)
                                .as("pattern %s input %s start %s", expression, input, start)
                                .isEqualTo(Dfa.SEARCH_NO_MATCH);
                    }
                }
            }
        }
    }

    private static FixedWidthByteSpanMatcher matcher(String pattern)
    {
        FixedWidthByteSpanMatcher matcher = optionalMatcher(pattern);
        assertThat(matcher).isNotNull();
        return matcher;
    }

    private static FixedWidthByteSpanMatcher optionalMatcher(String pattern)
    {
        return Re2.compile(utf8Slice(pattern)).createFixedWidthByteSpanMatcher();
    }

    private static void assertSpan(FixedWidthByteSpanMatcher matcher, Slice input, int searchStart, int expectedStart, int expectedEnd)
    {
        long span = matcher.findSpan(input, searchStart);
        assertThat((int) (span >>> 32)).isEqualTo(expectedStart);
        assertThat((int) span).isEqualTo(expectedEnd);
    }
}
