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

import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTrinoRegexpSyntax
{
    private static final String SYNTAX_CASES = "/io/airlift/regulator/trino-syntax-cases.tsv";

    @Test
    public void testMalformedUtf8InSyntaxIsRejected()
    {
        assertBadUtf8(new byte[] {'(', '?', '<', 'n', (byte) 0xFF, '>', 'x', ')'}, 4);
        assertBadUtf8(new byte[] {'[', (byte) 0xFF, ']'}, 1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("syntaxCases")
    public void testSyntaxContract(SyntaxCase syntaxCase)
    {
        switch (syntaxCase.expected()) {
            case ACCEPT -> assertThatCode(() -> TrinoRegexp.compile(Slices.utf8Slice(syntaxCase.pattern())))
                    .doesNotThrowAnyException();
            case REJECT -> assertThatThrownBy(() -> TrinoRegexp.compile(Slices.utf8Slice(syntaxCase.pattern())))
                    .isInstanceOf(RegexpParseException.class)
                    .extracting(exception -> ((RegexpParseException) exception).byteOffset())
                    .isNotEqualTo(-1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedFeatures")
    public void testUnsupportedFeatureMessages(UnsupportedFeature unsupportedFeature)
    {
        assertThatThrownBy(() -> TrinoRegexp.compile(Slices.utf8Slice(unsupportedFeature.pattern())))
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
                unsupported("\\G", "previous-match boundary \\G", 0),
                unsupported("\\Z", "final-terminator boundary \\Z", 0),
                unsupported("[a&&]", "empty character-class intersection operands", 2),
                unsupported("[&&a]", "empty character-class intersection operands", 1),
                unsupported("[a-\\d]", "character classes as range endpoints", 3),
                unsupported("\\xE9", "non-ASCII byte escapes", 0),
                unsupported("\\351", "non-ASCII byte escapes", 0),
                unsupported("\\uD800", "surrogate escapes", 0),
                unsupportedFlag("(?U)x", "U", 2),
                unsupportedFlag("(?u)x", "u", 2),
                unsupportedFlag("(?d)x", "d", 2));
    }

    private static UnsupportedFeature unsupported(String pattern, String feature, int byteOffset)
    {
        return new UnsupportedFeature(
                pattern,
                RegexpParseErrorCode.UNSUPPORTED_CONSTRUCT,
                "unsupported regular-expression construct: " + feature + " at byte offset " + byteOffset);
    }

    private static UnsupportedFeature unsupportedFlag(String pattern, String flag, int byteOffset)
    {
        return new UnsupportedFeature(
                pattern,
                RegexpParseErrorCode.UNSUPPORTED_FLAG,
                "unsupported regular-expression flag: " + flag + " at byte offset " + byteOffset);
    }

    private static void assertBadUtf8(byte[] pattern, int byteOffset)
    {
        assertThatThrownBy(() -> TrinoRegexp.compile(Slices.wrappedBuffer(pattern)))
                .isInstanceOfSatisfying(RegexpParseException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(RegexpParseErrorCode.BAD_UTF8);
                    assertThat(exception.byteOffset()).isEqualTo(byteOffset);
                });
    }

    private static List<SyntaxCase> syntaxCases()
    {
        return TsvTestData.load(SYNTAX_CASES, 3, 62).stream()
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
