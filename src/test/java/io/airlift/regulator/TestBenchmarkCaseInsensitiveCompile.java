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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Locale;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkCaseInsensitiveCompile
{
    @ParameterizedTest
    @EnumSource(BenchmarkCaseInsensitiveCompile.Frontend.class)
    public void testFoldHeavyPatternsMatchOtherCase(BenchmarkCaseInsensitiveCompile.Frontend frontend)
    {
        BenchmarkCaseInsensitiveCompile benchmark = new BenchmarkCaseInsensitiveCompile();
        for (BenchmarkCaseInsensitiveCompile.FoldPattern pattern : BenchmarkCaseInsensitiveCompile.FoldPattern.values()) {
            BenchmarkCaseInsensitiveCompile.FoldHeavyState state = new BenchmarkCaseInsensitiveCompile.FoldHeavyState();
            state.frontend = frontend;
            state.pattern = pattern;
            state.setup();

            // Each pattern's other case matches. K matches the non-ASCII range only by folding to
            // U+212A KELVIN SIGN. Java does not fold \p{L}, so Σ checks only its membership.
            String otherCase = switch (pattern) {
                case LETTER_CLASS -> "Σ";
                case NON_ASCII_RANGE -> "K";
                default -> pattern.body().toUpperCase(Locale.ROOT);
            };
            Object compiled = benchmark.compileFoldHeavy(state);
            assertThat(contains(compiled, utf8Slice(otherCase)))
                    .as("%s %s", frontend, pattern)
                    .isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(BenchmarkCaseInsensitiveCompile.Frontend.class)
    public void testFirstUsePatternMatchesOtherCase(BenchmarkCaseInsensitiveCompile.Frontend frontend)
    {
        BenchmarkCaseInsensitiveCompile.FirstUseState state = new BenchmarkCaseInsensitiveCompile.FirstUseState();
        state.frontend = frontend;
        state.setup();

        Object compiled = new BenchmarkCaseInsensitiveCompile().compileFirstCaseInsensitive(state);
        assertThat(contains(compiled, utf8Slice("HELLO ΚΌΣΜΕ"))).as("%s", frontend).isTrue();
    }

    @ParameterizedTest
    @EnumSource(BenchmarkCaseInsensitiveCompile.CanonicalText.class)
    public void testCanonicalizeText(BenchmarkCaseInsensitiveCompile.CanonicalText text)
    {
        BenchmarkCaseInsensitiveCompile.CanonicalizeTextState state = new BenchmarkCaseInsensitiveCompile.CanonicalizeTextState();
        state.text = text;
        state.setup();

        assertThat(state.source.length()).isBetween(BenchmarkCaseInsensitiveCompile.CANONICAL_TEXT_LENGTH - 3, BenchmarkCaseInsensitiveCompile.CANONICAL_TEXT_LENGTH);
        String source = state.source.toStringUtf8();
        long nonAsciiCount = source.codePoints().filter(codePoint -> codePoint >= 0x80).count();
        switch (text) {
            case ASCII -> assertThat(nonAsciiCount).isZero();
            case MIXED -> assertThat(nonAsciiCount).isBetween(1L, source.length() / 10L);
            case NON_ASCII -> assertThat(source.codePoints().filter(Character::isLetter).allMatch(codePoint -> codePoint >= 0x80)).isTrue();
        }

        Slice canonical = new BenchmarkCaseInsensitiveCompile().canonicalizeText(state);
        assertThat(canonical.toStringUtf8()).isNotEqualTo(source);
        assertThat(state.filteredRe2.canonicalizeText(canonical)).isEqualTo(canonical);
    }

    private static boolean contains(Object compiled, Slice text)
    {
        return switch (compiled) {
            case Re2 re2 -> re2.find(text);
            case JavaRegexp javaRegexp -> javaRegexp.find(text);
            case TrinoRegexp trinoRegexp -> trinoRegexp.contains(text);
            default -> throw new IllegalArgumentException("unexpected compiled pattern: " + compiled);
        };
    }
}
