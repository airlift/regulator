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

import io.airlift.regulator.CompiledPattern.Shape;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Param;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.airlift.regulator.BenchmarkPatternDispatch.expression;
import static io.airlift.regulator.BenchmarkPatternDispatch.targets;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.CONTAINS;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.GENERAL;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.STARTS_WITH;
import static io.airlift.regulator.CompiledPattern.Shape.BYTE_TABLE;
import static io.airlift.regulator.CompiledPattern.Shape.CLASS_RUN;
import static io.airlift.regulator.CompiledPattern.Shape.ENGINE;
import static io.airlift.regulator.CompiledPattern.Shape.EXACT_LITERAL;
import static io.airlift.regulator.CompiledPattern.Shape.FIXED_WIDTH;
import static io.airlift.regulator.CompiledPattern.Shape.LITERAL_ALTERNATION;
import static io.airlift.regulator.CompiledPattern.Shape.LITERAL_KERNEL;
import static io.airlift.regulator.CompiledPattern.Shape.SCAN_PLAN;
import static io.airlift.regulator.TestingPatternShapes.shape;
import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestBenchmarkPatternDispatch
{
    // Targets whose scan plan has a generated class in every language, Trino included. No other
    // target has one.
    private static final Set<String> GENERATED_TARGETS = Set.of("ANCHORED_WWW_HOST", "UNANCHORED_URL", "UNANCHORED_SET");
    // The pollution patterns, in the benchmark's order. Each has a generated class in every language.
    private static final List<String> POLLUTION = List.of(
            "^https?://[^/]+/",
            "^https?://[^/]+/[^?]*\\?",
            "^(\\w+)-(\\d+)/",
            "id=(\\d+)&",
            "svc1://(?:www\\.)?([^/]+)/",
            "https?://(?:www\\.)?([^/]+)/.*$");

    @Test
    public void testBenchmarkResults()
    {
        BenchmarkPatternDispatch benchmark = new BenchmarkPatternDispatch();
        // A CompiledPattern, or a TrinoRegexp, which is none.
        Map<String, Object> interpreted = new HashMap<>();
        for (String language : List.of("RE2", "JAVA", "RUST", "TRINO")) {
            for (String target : targets()) {
                // The target, then every other target in order, then the pollution patterns.
                List<String> expressions = new ArrayList<>();
                expressions.add(expression(target));
                targets().stream()
                        .filter(other -> !other.equals(target))
                        .map(BenchmarkPatternDispatch::expression)
                        .forEach(expressions::add);
                expressions.addAll(POLLUTION);
                List<Object> sharedSite = expressions.stream()
                        .map(expression -> interpreted.computeIfAbsent(language + " " + expression, _ -> interpreted(language, expression)))
                        .toList();
                Object reference = sharedSite.getFirst();
                if (!GENERATED_TARGETS.contains(target)) {
                    assertThatThrownBy(() -> data(language, target, "SHORT", false, true))
                            .as("%s %s", language, target)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("no generated class");
                }
                for (String rows : List.of("SHORT", "LONG")) {
                    for (boolean generated : GENERATED_TARGETS.contains(target) ? new boolean[] {false, true} : new boolean[] {false}) {
                        for (boolean polluted : new boolean[] {false, true}) {
                            String description = "%s %s %s polluted=%s generated=%s".formatted(language, target, rows, polluted, generated);
                            BenchmarkPatternDispatch.BenchmarkData data = data(language, target, rows, polluted, generated);
                            assertPatterns(data.patterns, polluted ? expressions : expressions.subList(0, 1), generated, description);
                            // Trino routes are not pinned here.
                            if (compiledPatterns(data.patterns)[0] instanceof CompiledPattern compiled) {
                                assertRoute(target, compiled, (RegexpMatcher) targetMatcher(data.patterns), description);
                            }
                            for (Slice input : data.inputs) {
                                assertThat(input.byteArrayOffset()).as(description).isPositive();
                                if (rows.equals("LONG")) {
                                    assertThat(input.length()).as(description).isEqualTo(1024);
                                }
                                else {
                                    assertThat(input.length()).as(description).isBetween(16, 64);
                                }
                            }

                            int finds = 0;
                            long count = 0;
                            long iterated = 0;
                            for (Slice input : data.inputs) {
                                finds += find(reference, input) ? 1 : 0;
                                count += count(reference, input);
                                iterated += iterate(reference, input);
                            }
                            assertThat(benchmark.find(data)).as(description).isEqualTo(finds);
                            assertThat(benchmark.count(data)).as(description).isEqualTo(count);
                            assertThat(benchmark.iterate(data)).as(description).isEqualTo(iterated);
                            if (reference instanceof CompiledPattern compiled) {
                                int matches = 0;
                                int lookingAt = 0;
                                for (Slice input : data.inputs) {
                                    matches += compiled.matches(input) ? 1 : 0;
                                    lookingAt += compiled.lookingAt(input) ? 1 : 0;
                                }
                                assertThat(benchmark.matches(data)).as(description).isEqualTo(matches);
                                assertThat(benchmark.lookingAt(data)).as(description).isEqualTo(lookingAt);
                            }
                            else {
                                // Trino has no whole-input or anchored-prefix match.
                                assertThatThrownBy(() -> benchmark.matches(data))
                                        .as(description)
                                        .isInstanceOf(UnsupportedOperationException.class);
                                assertThatThrownBy(() -> benchmark.lookingAt(data))
                                        .as(description)
                                        .isInstanceOf(UnsupportedOperationException.class);
                            }
                        }

                        String description = "%s %s %s sharedSite generated=%s".formatted(language, target, rows, generated);
                        BenchmarkPatternDispatch.SharedSiteData data = sharedSiteData(language, target, rows, generated);
                        assertPatterns(data.patterns, expressions, generated, description);
                        int sharedSiteFinds = 0;
                        for (Slice input : data.inputs) {
                            for (Object pattern : sharedSite) {
                                sharedSiteFinds += find(pattern, input) ? 1 : 0;
                            }
                        }
                        assertThat(benchmark.sharedSite(data)).as(description).isEqualTo(sharedSiteFinds);
                    }
                }
            }
        }
    }

    @Test
    public void testSharedSiteRunsEveryTarget()
            throws NoSuchFieldException
    {
        // JMH reads the parameters of each state from its own annotations, so the shared-site state
        // repeats those of the benchmark data, all but `polluted`.
        for (String parameter : List.of("language", "target", "rows", "generated")) {
            assertThat(parameterValues(BenchmarkPatternDispatch.SharedSiteData.class, parameter))
                    .as(parameter)
                    .containsExactly(parameterValues(BenchmarkPatternDispatch.BenchmarkData.class, parameter));
        }
    }

    private static String[] parameterValues(Class<?> state, String parameter)
            throws NoSuchFieldException
    {
        return state.getDeclaredField(parameter).getAnnotation(Param.class).value();
    }

    /// Requires `patterns` to hold `expressions` in order, each with a generated class exactly
    /// when `generated` is set and the pattern is eligible for one.
    private static void assertPatterns(BenchmarkPatternDispatch.LanguagePatterns patterns, List<String> expressions, boolean generated, String description)
    {
        Object[] compiled = compiledPatterns(patterns);
        assertThat(Arrays.stream(compiled).map(TestBenchmarkPatternDispatch::expressionOf))
                .as(description)
                .containsExactlyElementsOf(expressions);
        for (Object pattern : compiled) {
            String expression = expressionOf(pattern);
            boolean eligible = POLLUTION.contains(expression) || GENERATED_TARGETS.stream()
                    .map(BenchmarkPatternDispatch::expression)
                    .anyMatch(expression::equals);
            assertThat(usesGeneratedBytecode(pattern)).as("%s %s", description, expression).isEqualTo(generated && eligible);
        }
    }

    /// Requires `compiled` to take the full-Slice `find` route that `target` names, and its
    /// benchmark matcher `matcher` the matcher route LITERAL_SET names.
    private static void assertRoute(String target, CompiledPattern compiled, RegexpMatcher matcher, String description)
    {
        Shape expected = switch (target) {
            case "ANCHORED_WWW_HOST", "UNANCHORED_URL", "UNANCHORED_SET", "FOLDED_PLAN" -> SCAN_PLAN;
            case "EXACT_LITERAL", "SINGLE_BYTE" -> EXACT_LITERAL;
            case "CONTAINS", "ANCHORED_LITERAL" -> LITERAL_KERNEL;
            // Java's dot also stops at `\r`, U+0085, U+2028, and U+2029, so its `.*timeout.*`
            // is no contained literal.
            case "DOT_STAR_LITERAL" -> compiled instanceof JavaRegexp ? ENGINE : LITERAL_KERNEL;
            case "CHARACTER_CLASS" -> CLASS_RUN;
            case "LITERAL_ALTERNATION" -> LITERAL_ALTERNATION;
            case "FIXED_WIDTH" -> FIXED_WIDTH;
            // A Rust `\s` is Unicode, so it is no byte run.
            case "BYTE_TABLE" -> compiled instanceof RustRegexp ? ENGINE : BYTE_TABLE;
            // `[Kk]` simplifies to a folded literal, which keeps FOLDED_PAIR_SEQUENCE off the
            // fixed-width route in every language, and ordered literals have no find plan.
            case "GENERAL", "FOLDED_PAIR_SEQUENCE", "ORDERED_LITERALS" -> ENGINE;
            // The literal set serves only the matcher's find.
            case "LITERAL_SET" -> ENGINE;
            // An inner literal of two or more bytes keeps the DFA's inner literal search instead
            // of the fixed-width matcher.
            case "INNER_LITERAL_FIXED_WIDTH" -> ENGINE;
            default -> throw new IllegalArgumentException("unknown target: " + target);
        };
        assertThat(shape(compiled)).as(description).isEqualTo(expected);
        // A shape class can hold more than one find route, so pin the one the target names.
        if (expected == SCAN_PLAN) {
            assertThat(compiled.usesScanPlanForFindForDiagnostics()).as(description).isTrue();
        }
        switch (target) {
            case "FOLDED_PLAN" -> assertThat(compiled.usesAsciiFoldedScanExecutorForDiagnostics()).as(description).isTrue();
            case "CONTAINS" -> assertThat(compiled.findPlanForDiagnostics()).as(description).isEqualTo(CONTAINS);
            case "DOT_STAR_LITERAL" -> assertThat(compiled.findPlanForDiagnostics()).as(description).isEqualTo(compiled instanceof JavaRegexp ? GENERAL : CONTAINS);
            case "SINGLE_BYTE" -> assertThat(compiled.supportsSingleByteMatcherForDiagnostics()).as(description).isTrue();
            case "LITERAL_SET" -> {
                assertThat(compiled.literalSetSpanMatcher()).as(description).isNotNull();
                assertThat(matcher.literalSetEnabledForDiagnostics()).as(description).isTrue();
            }
            case "ANCHORED_LITERAL" -> assertThat(compiled.findPlanForDiagnostics()).as(description).isEqualTo(STARTS_WITH);
            case "ORDERED_LITERALS" -> assertThat(compiled.findPlanForDiagnostics()).as(description).isEqualTo(GENERAL);
            case "INNER_LITERAL_FIXED_WIDTH" -> assertThat(compiled.innerLiteralSearchForDiagnostics()).as(description).isNotNull();
            case "GENERAL" -> {
                assertThat(compiled.supportsSingleByteMatcherForDiagnostics()).as(description).isFalse();
                assertThat(compiled.innerLiteralSearchForDiagnostics()).as(description).isNull();
            }
            default -> {}
        }
    }

    private static Object[] compiledPatterns(BenchmarkPatternDispatch.LanguagePatterns patterns)
    {
        return switch (patterns) {
            case BenchmarkPatternDispatch.Re2Patterns re2 -> re2.patterns;
            case BenchmarkPatternDispatch.JavaPatterns java -> java.patterns;
            case BenchmarkPatternDispatch.RustPatterns rust -> rust.patterns;
            case BenchmarkPatternDispatch.TrinoPatterns trino -> trino.patterns;
        };
    }

    // A RegexpMatcher, or a TrinoRegexpMatcher.
    private static Object targetMatcher(BenchmarkPatternDispatch.LanguagePatterns patterns)
    {
        return switch (patterns) {
            case BenchmarkPatternDispatch.Re2Patterns re2 -> re2.targetMatcher;
            case BenchmarkPatternDispatch.JavaPatterns java -> java.targetMatcher;
            case BenchmarkPatternDispatch.RustPatterns rust -> rust.targetMatcher;
            case BenchmarkPatternDispatch.TrinoPatterns trino -> trino.targetMatcher;
        };
    }

    private static Object interpreted(String language, String expression)
    {
        Slice pattern = utf8Slice(expression);
        return switch (language) {
            case "RE2" -> Re2.compile(pattern);
            case "JAVA" -> JavaRegexp.compile(pattern);
            case "RUST" -> RustRegexp.compile(pattern);
            case "TRINO" -> TrinoRegexp.compile(pattern);
            default -> throw new IllegalArgumentException("unknown language: " + language);
        };
    }

    // A Trino pattern's find is contains.
    private static boolean find(Object pattern, Slice input)
    {
        return switch (pattern) {
            case CompiledPattern compiled -> compiled.find(input);
            case TrinoRegexp trino -> trino.contains(input);
            default -> throw new IllegalArgumentException("unhandled compiled pattern");
        };
    }

    private static long count(Object pattern, Slice input)
    {
        return switch (pattern) {
            case CompiledPattern compiled -> compiled.count(input);
            case TrinoRegexp trino -> trino.count(input);
            default -> throw new IllegalArgumentException("unhandled compiled pattern");
        };
    }

    // The non-overlapping matches of a find loop over the whole input: the generic group-zero
    // matcher's, or a Trino pattern's count.
    private static long iterate(Object pattern, Slice input)
    {
        return switch (pattern) {
            case CompiledPattern compiled -> {
                RegexpMatcher matcher = compiled.genericGroupZeroMatcherForDiagnostics(input);
                long matches = 0;
                while (matcher.find()) {
                    matches++;
                }
                yield matches;
            }
            case TrinoRegexp trino -> trino.count(input);
            default -> throw new IllegalArgumentException("unhandled compiled pattern");
        };
    }

    private static String expressionOf(Object pattern)
    {
        return switch (pattern) {
            case CompiledPattern compiled -> compiled.pattern().toStringUtf8();
            case TrinoRegexp trino -> trino.pattern().toStringUtf8();
            default -> throw new IllegalArgumentException("unhandled compiled pattern");
        };
    }

    private static boolean usesGeneratedBytecode(Object pattern)
    {
        return switch (pattern) {
            case CompiledPattern compiled -> compiled.usesGeneratedBytecode();
            case TrinoRegexp trino -> trino.usesGeneratedBytecode();
            default -> throw new IllegalArgumentException("unhandled compiled pattern");
        };
    }

    private static BenchmarkPatternDispatch.BenchmarkData data(String language, String target, String rows, boolean polluted, boolean generated)
    {
        BenchmarkPatternDispatch.BenchmarkData data = new BenchmarkPatternDispatch.BenchmarkData();
        data.language = language;
        data.target = target;
        data.rows = rows;
        data.polluted = polluted;
        data.generated = generated;
        data.setup();
        return data;
    }

    private static BenchmarkPatternDispatch.SharedSiteData sharedSiteData(String language, String target, String rows, boolean generated)
    {
        BenchmarkPatternDispatch.SharedSiteData data = new BenchmarkPatternDispatch.SharedSiteData();
        data.language = language;
        data.target = target;
        data.rows = rows;
        data.generated = generated;
        data.setup();
        return data;
    }
}
