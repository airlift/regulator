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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestJavaRegexpSyntax
{
    private static final String SYNTAX_CASES = "/io/airlift/regulator/java-syntax-cases.tsv";

    @Test
    public void testMalformedUtf8IsRejected()
    {
        assertBadUtf8(new byte[] {'a', (byte) 0xE2, (byte) 0x82, 'b'}, 1);
        assertBadUtf8(new byte[] {'a', 'b', (byte) 0xED, (byte) 0xA0, (byte) 0x80}, 2);
        assertBadUtf8(new byte[] {(byte) 0xFF}, 0);
    }

    @Test
    public void testErrorsReportThePatternAsWritten()
    {
        // The quote rewrite parses [a\. from [\Qa.\E, but the error reports the original bytes and offset.
        byte[] bytes = "xx[\\Qa.\\E".getBytes(UTF_8);
        assertError(Slices.wrappedBuffer(bytes, 2, bytes.length - 2), RegexpParseErrorCode.MISSING_BRACKET, 7, "[\\Qa.\\E");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("syntaxCases")
    public void testSyntaxContract(SyntaxCase syntaxCase)
    {
        switch (syntaxCase.expected()) {
            case ACCEPT -> assertThatCode(() -> JavaRegexp.compile(Slices.utf8Slice(syntaxCase.pattern())))
                    .doesNotThrowAnyException();
            case REJECT -> assertThatThrownBy(() -> JavaRegexp.compile(Slices.utf8Slice(syntaxCase.pattern())))
                    .isInstanceOf(RegexpParseException.class)
                    .extracting(exception -> ((RegexpParseException) exception).byteOffset())
                    .isNotEqualTo(-1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedFeatures")
    public void testUnsupportedFeatureMessages(UnsupportedFeature unsupportedFeature)
    {
        assertThatThrownBy(() -> JavaRegexp.compile(Slices.utf8Slice(unsupportedFeature.pattern())))
                .isInstanceOfSatisfying(RegexpParseException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(unsupportedFeature.errorCode());
                    assertThat(exception).hasMessage(unsupportedFeature.message());
                });
    }

    private static List<UnsupportedFeature> unsupportedFeatures()
    {
        return List.of(
                unsupported("(?=x)", "lookahead", 0),
                unsupported("(?!x)", "lookahead", 0),
                unsupported("(?<=x)", "lookbehind", 0),
                unsupported("(?<!x)", "lookbehind", 0),
                unsupported("(?>x)", "atomic groups", 0),
                unsupported("x++", "possessive quantifiers", 2),
                unsupported("x*+", "possessive quantifiers", 2),
                unsupported("x?+", "possessive quantifiers", 2),
                unsupported("x{1,2}+", "possessive quantifiers", 6),
                unsupported("(x)\\1", "backreferences", 3),
                unsupported("(?<name>x)\\k<name>", "backreferences", 10),
                unsupported("(?x)(?<name>x)\\k <name>", "backreferences", 14),
                unsupported("\\G", "previous-match boundary \\G", 0),
                // The offset is in the pattern as written, not the rewritten a\.\G that is parsed.
                unsupported("\\Qa.\\E\\G", "previous-match boundary \\G", 6),
                unsupported("\\R", "linebreak escape \\R", 0),
                unsupported("\\uD800", "surrogate escapes", 0),
                unsupported("\\uDC00", "surrogate escapes", 0),
                unsupported("\\uD800\\u0041", "surrogate escapes", 0),
                unsupported("\\N{HIGH SURROGATES D800}", "surrogate escapes", 0),
                unsupported("\\x{D800}", "surrogate escapes", 0),
                unsupported("[\\x{DFFF}]", "surrogate escapes", 1),
                unsupported("(a?)*", "nullable repeated captures", 4),
                unsupported("((a)*)+", "nullable repeated captures", 6),
                unsupported("(a{0,2}){2}", "nullable repeated captures", 8),
                unsupported("(?:x|(a)?)+", "nullable repeated captures", 10));
    }

    private static UnsupportedFeature unsupported(String pattern, String feature, int byteOffset)
    {
        return new UnsupportedFeature(
                pattern,
                RegexpParseErrorCode.UNSUPPORTED_CONSTRUCT,
                "unsupported regular-expression construct: " + feature + " at byte offset " + byteOffset);
    }

    private static void assertError(Slice pattern, RegexpParseErrorCode errorCode, int byteOffset, String invalidPatternSegment)
    {
        assertThatThrownBy(() -> JavaRegexp.compile(pattern))
                .isInstanceOfSatisfying(RegexpParseException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(errorCode);
                    assertThat(exception.byteOffset()).isEqualTo(byteOffset);
                    assertThat(exception.invalidPatternSegment()).isEqualTo(Slices.utf8Slice(invalidPatternSegment));
                });
    }

    private static void assertBadUtf8(byte[] pattern, int byteOffset)
    {
        assertThatThrownBy(() -> JavaRegexp.compile(Slices.wrappedBuffer(pattern)))
                .isInstanceOfSatisfying(RegexpParseException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(RegexpParseErrorCode.BAD_UTF8);
                    assertThat(exception.byteOffset()).isEqualTo(byteOffset);
                });
    }

    private static List<SyntaxCase> syntaxCases()
    {
        return TsvTestData.load(SYNTAX_CASES, 3, 57).stream()
                .map(row -> new SyntaxCase(row.field(1), Expected.valueOf(row.field(0)), row.field(2)))
                .toList();
    }

    private enum Expected
    {
        ACCEPT,
        REJECT,
    }

    private record SyntaxCase(String pattern, Expected expected, String reason)
    {
        @Override
        public String toString()
        {
            return expected + ": " + pattern + " (" + reason + ")";
        }
    }

    private record UnsupportedFeature(String pattern, RegexpParseErrorCode errorCode, String message)
    {
        @Override
        public String toString()
        {
            return pattern + ": " + message;
        }
    }
}
