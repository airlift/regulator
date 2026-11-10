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
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static io.airlift.regulator.BenchmarkPatternDispatch.expression;
import static io.airlift.regulator.BenchmarkPatternDispatch.sharedSiteTargets;
import static io.airlift.regulator.BenchmarkPatternDispatch.targets;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.CONTAINS;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.GENERAL;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.ORDERED_LITERALS;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.STARTS_WITH;
import static io.airlift.regulator.CompiledPattern.Shape.BYTE_TABLE;
import static io.airlift.regulator.CompiledPattern.Shape.CLASS_RUN;
import static io.airlift.regulator.CompiledPattern.Shape.ENGINE;
import static io.airlift.regulator.CompiledPattern.Shape.EXACT_LITERAL;
import static io.airlift.regulator.CompiledPattern.Shape.FIXED_WIDTH;
import static io.airlift.regulator.CompiledPattern.Shape.LITERAL_ALTERNATION;
import static io.airlift.regulator.CompiledPattern.Shape.LITERAL_KERNEL;
import static io.airlift.regulator.CompiledPattern.Shape.SCAN_PLAN;
import static io.airlift.regulator.TestingPatternShapes.assertNoProgramSearch;
import static io.airlift.regulator.TestingPatternShapes.shape;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestBenchmarkPatternDispatch
{
    // Targets whose scan plan has a generated class in every language, Trino included. No other
    // target has one.
    private static final Set<String> GENERATED_TARGETS = Set.of("ANCHORED_WWW_HOST", "UNANCHORED_URL", "UNANCHORED_SET");
    // The languages with the byte mode that ORDERED_LITERALS_BYTES compiles in.
    private static final Set<String> BYTE_LANGUAGES = Set.of("RE2", "RUST");
    // The rows of DOT_STAR_LITERAL alone, in the benchmark's order.
    private static final List<String> LINE_TEXTS = List.of("LINES_ABSENT", "LINES_SPARSE", "LINES_DENSE", "LINES_SHORT_BEFORE_LITERAL");
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
        // A CompiledPattern, or a TrinoRegexp, which does not extend it.
        Map<String, Object> interpreted = new HashMap<>();
        for (String language : List.of("RE2", "JAVA", "RUST", "TRINO")) {
            for (String target : targets()) {
                // The byte target runs alone, in the byte mode that only RE2 and Rust have.
                boolean bytes = target.equals("ORDERED_LITERALS_BYTES");
                if (bytes && !BYTE_LANGUAGES.contains(language)) {
                    assertThatThrownBy(() -> data(language, target, "SHORT", false, false))
                            .as("%s %s", language, target)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("no byte mode");
                    continue;
                }
                if (bytes) {
                    assertThatThrownBy(() -> data(language, target, "SHORT", true, false))
                            .as("%s %s", language, target)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("no pollution set");
                }
                // The target, then every other target of the shared site in order, then the
                // pollution patterns.
                List<String> expressions = new ArrayList<>();
                expressions.add(expression(target));
                sharedSiteTargets().stream()
                        .filter(other -> !other.equals(target))
                        .map(BenchmarkPatternDispatch::expression)
                        .forEach(expressions::add);
                expressions.addAll(POLLUTION);
                List<Object> sharedSite = expressions.stream()
                        .map(expression -> interpreted.computeIfAbsent(language + " " + expression, _ -> interpreted(language, expression)))
                        .toList();
                Object reference = bytes ? bytePattern(language, expression(target)) : sharedSite.getFirst();
                if (!GENERATED_TARGETS.contains(target)) {
                    assertThatThrownBy(() -> data(language, target, "SHORT", false, true))
                            .as("%s %s", language, target)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("no generated class");
                }
                for (String rows : List.of("SHORT", "LONG")) {
                    for (boolean generated : GENERATED_TARGETS.contains(target) ? new boolean[] {false, true} : new boolean[] {false}) {
                        for (boolean polluted : bytes ? new boolean[] {false} : new boolean[] {false, true}) {
                            String description = "%s %s %s polluted=%s generated=%s".formatted(language, target, rows, polluted, generated);
                            BenchmarkPatternDispatch.BenchmarkData data = data(language, target, rows, polluted, generated);
                            assertPatterns(data.patterns, polluted ? expressions : List.of(expressionOf(reference)), generated, description);
                            // Of Trino's routes, only the control's is pinned here.
                            if (compiledPatterns(data.patterns)[0] instanceof CompiledPattern compiled) {
                                assertRoute(target, compiled, (RegexpMatcher) targetMatcher(data.patterns), description);
                            }
                            else if (target.equals("FOLDED_PAIR_SEQUENCE")) {
                                assertThat(compiledPatterns(data.patterns)[0]).as(description).isNotInstanceOf(TrinoRegexp.FixedWidthByteTrinoRegexp.class);
                            }
                            if (bytes) {
                                // Creating the benchmark's matcher builds a DFA, so a fresh pattern
                                // shows that full-Slice find over these rows runs the plan alone.
                                CompiledPattern fresh = bytePattern(language, expression(target));
                                for (Slice input : data.inputs) {
                                    assertThat(fresh.find(input)).as(description).isEqualTo(find(reference, input));
                                }
                                assertNoProgramSearch(fresh, description);
                            }
                            if (target.equals("DOT_STAR_LITERAL") && (reference instanceof Re2 || reference instanceof RustRegexp)) {
                                // A fresh pattern shows that the one-shot searches over these rows
                                // run the dot-star literal matcher and never the program.
                                CompiledPattern fresh = (CompiledPattern) interpreted(language, expression(target));
                                for (Slice input : data.inputs) {
                                    boolean found = find(reference, input);
                                    assertThat(fresh.find(input, 0, input.length())).as(description).isEqualTo(found);
                                    assertThat(fresh.findInto(input, new int[2])).as(description).isEqualTo(found);
                                    assertThat(fresh.findResult(input) != null).as(description).isEqualTo(found);
                                    if (fresh instanceof Re2 re2) {
                                        Re2 referenceRe2 = (Re2) reference;
                                        assertThat(re2.replaceAll(input, EMPTY_SLICE).result()).as(description).isEqualTo(referenceRe2.replaceAll(input, EMPTY_SLICE).result());
                                        assertThat(re2.extract(input, utf8Slice("\\0"))).as(description).isEqualTo(referenceRe2.extract(input, utf8Slice("\\0")));
                                    }
                                }
                                assertNoProgramSearch(fresh, description);
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
                            assertBenchmarkResults(benchmark, data, reference, description);
                        }
                        if (bytes) {
                            continue;
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
    public void testLineTexts()
    {
        // The line texts of DOT_STAR_LITERAL are rows of about 4 KiB of lines that hold `timeout`
        // in no line, in every 50th line from line `row % 30`, in every odd line, or only in a
        // last line without a line feed, after lines of one to eight bytes. Every method's results
        // are those of the interpreted pattern, and the line texts run only DOT_STAR_LITERAL, with
        // `polluted=false`.
        BenchmarkPatternDispatch benchmark = new BenchmarkPatternDispatch();
        for (String rows : LINE_TEXTS) {
            assertThatThrownBy(() -> data("RE2", "CONTAINS", rows, false, false))
                    .as(rows)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("runs only DOT_STAR_LITERAL");
            assertThatThrownBy(() -> data("RE2", "DOT_STAR_LITERAL", rows, true, false))
                    .as(rows)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no training rows");
            Slice[] inputs = BenchmarkPatternDispatch.inputs("DOT_STAR_LITERAL", rows);
            for (int row = 0; row < inputs.length; row++) {
                String description = "%s row %s".formatted(rows, row);
                String[] lines = inputs[row].toStringUtf8().split("\n");
                assertThat(inputs[row].length()).as(description).isBetween(4096, 4096 + 160);
                for (int line = 0; line < lines.length; line++) {
                    boolean literal = switch (rows) {
                        case "LINES_ABSENT" -> false;
                        case "LINES_SPARSE" -> line % 50 == row % 30;
                        case "LINES_DENSE" -> line % 2 == 1;
                        default -> line == lines.length - 1;
                    };
                    assertThat(lines[line].contains("timeout")).as("%s line %s", description, line).isEqualTo(literal);
                }
                if (rows.equals("LINES_SHORT_BEFORE_LITERAL")) {
                    assertThat(Arrays.stream(lines, 0, lines.length - 1)).as(description).allMatch(line -> !line.isEmpty() && line.length() <= 8);
                }
                else {
                    assertThat(lines).as(description).allMatch(line -> line.length() >= 40 && line.length() <= 130);
                }
            }
            for (String language : List.of("RE2", "JAVA", "RUST", "TRINO")) {
                String description = "%s DOT_STAR_LITERAL %s".formatted(language, rows);
                BenchmarkPatternDispatch.BenchmarkData data = data(language, "DOT_STAR_LITERAL", rows, false, false);
                assertThat(benchmark.find(data)).as(description).isEqualTo(rows.equals("LINES_ABSENT") ? 0 : data.inputs.length);
                assertBenchmarkResults(benchmark, data, interpreted(language, expression("DOT_STAR_LITERAL")), description);
            }
        }
    }

    /// Requires every method of the benchmark on `data` to return the results of `reference` on
    /// its rows: `find`, `count`, `iterate`, the one-shot searches, and `matches` and
    /// `lookingAt`, which throw in Trino.
    private static void assertBenchmarkResults(BenchmarkPatternDispatch benchmark, BenchmarkPatternDispatch.BenchmarkData data, Object reference, String description)
    {
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
        assertOneShotSearches(benchmark, data, reference, finds, description);
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
            assertThatThrownBy(() -> benchmark.matches(data)).as(description).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> benchmark.lookingAt(data)).as(description).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /// Requires each one-shot search of the benchmark, in the languages that have it, to return
    /// the sum that the spans of `reference`'s find loop give, the `finds` rows that `find` of
    /// `reference` matches for `rangedFind`, and to pass its setup check of each row's result,
    /// and to throw in the others, whose setup check of it then throws too. Setup's check throws
    /// for a name that is no benchmark method.
    private static void assertOneShotSearches(BenchmarkPatternDispatch benchmark, BenchmarkPatternDispatch.BenchmarkData data, Object reference, int finds, String description)
    {
        assertThatThrownBy(() -> data.checkOneShotSearch("renamedFindInto")).as(description).isInstanceOf(IllegalArgumentException.class);
        if (reference instanceof CompiledPattern) {
            assertThat(benchmark.rangedFind(data)).as(description).isEqualTo(finds);
            assertThat(benchmark.findInto(data)).as(description).isEqualTo(oneShotResult("findInto", data, reference));
            assertThat(benchmark.findResult(data)).as(description).isEqualTo(oneShotResult("findResult", data, reference));
            for (String operation : List.of("rangedFind", "findInto", "findResult")) {
                data.checkOneShotSearch(operation);
            }
        }
        else {
            for (String operation : List.of("rangedFind", "findInto", "findResult")) {
                assertThatThrownBy(() -> data.checkOneShotSearch(operation)).as("%s %s", description, operation).isInstanceOf(UnsupportedOperationException.class);
            }
        }
        if (reference instanceof Re2 || reference instanceof TrinoRegexp) {
            assertThat(benchmark.replaceAll(data)).as(description).isEqualTo(oneShotResult("replaceAll", data, reference));
            assertThat(benchmark.extract(data)).as(description).isEqualTo(oneShotResult("extract", data, reference));
            for (String operation : List.of("replaceAll", "extract")) {
                data.checkOneShotSearch(operation);
            }
        }
        else {
            for (String operation : List.of("replaceAll", "extract")) {
                assertThatThrownBy(() -> data.checkOneShotSearch(operation)).as("%s %s", description, operation).isInstanceOf(UnsupportedOperationException.class);
            }
        }
    }

    @Test
    public void testSharedSiteRunsEveryTarget()
            throws NoSuchFieldException
    {
        // JMH reads the parameters of each state from its own annotations, so the shared-site state
        // repeats those of the benchmark data, all but `polluted`, the byte target, which is no
        // pollution pattern, and the line texts, which have no training rows.
        for (String parameter : List.of("language", "generated")) {
            assertThat(parameterValues(BenchmarkPatternDispatch.SharedSiteData.class, parameter))
                    .as(parameter)
                    .containsExactly(parameterValues(BenchmarkPatternDispatch.BenchmarkData.class, parameter));
        }
        assertThat(parameterValues(BenchmarkPatternDispatch.SharedSiteData.class, "rows")).containsExactly("SHORT", "LONG");
        assertThat(parameterValues(BenchmarkPatternDispatch.BenchmarkData.class, "rows"))
                .containsExactly(Stream.concat(Stream.of("SHORT", "LONG"), LINE_TEXTS.stream()).toArray(String[]::new));
        assertThat(sharedSiteTargets())
                .containsExactlyElementsOf(targets().stream().filter(target -> !target.equals("ORDERED_LITERALS_BYTES")).toList());
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
        assertThat(Arrays.stream(compiled).map(TestBenchmarkPatternDispatch::expressionOf)).as(description).containsExactlyElementsOf(expressions);
        for (Object pattern : compiled) {
            String expression = expressionOf(pattern);
            boolean eligible = POLLUTION.contains(expression) || GENERATED_TARGETS.stream()
                    .map(BenchmarkPatternDispatch::expression)
                    .anyMatch(expression::equals);
            assertThat(usesGeneratedBytecode(pattern)).as("%s %s", description, expression).isEqualTo(generated && eligible);
        }
    }

    /// Requires `compiled` to take the full-Slice `find` route that `target` names, and its
    /// benchmark matcher `matcher` the matcher route that LITERAL_ALTERNATION, LITERAL_SET,
    /// FIXED_WIDTH, SINGLE_BYTE, or DOT_STAR_LITERAL names.
    private static void assertRoute(String target, CompiledPattern compiled, RegexpMatcher matcher, String description)
    {
        Shape expected = switch (target) {
            case "ANCHORED_WWW_HOST", "UNANCHORED_URL", "UNANCHORED_SET", "FOLDED_PLAN" -> SCAN_PLAN;
            case "EXACT_LITERAL", "SINGLE_BYTE" -> EXACT_LITERAL;
            case "CONTAINS", "ANCHORED_LITERAL", "ORDERED_LITERALS_BYTES" -> LITERAL_KERNEL;
            // Java's dot also stops at `\r`, U+0085, U+2028, and U+2029, so its `.*timeout.*`
            // is no contained literal.
            case "DOT_STAR_LITERAL" -> compiled instanceof JavaRegexp ? ENGINE : LITERAL_KERNEL;
            case "CHARACTER_CLASS" -> CLASS_RUN;
            case "LITERAL_ALTERNATION" -> LITERAL_ALTERNATION;
            case "FIXED_WIDTH" -> FIXED_WIDTH;
            // A Rust `\s` is Unicode, so it is no byte run.
            case "BYTE_TABLE" -> compiled instanceof RustRegexp ? ENGINE : BYTE_TABLE;
            // `[Kk]` simplifies to a folded literal, which keeps FOLDED_PAIR_SEQUENCE off the
            // fixed-width route in every language.
            case "GENERAL", "FOLDED_PAIR_SEQUENCE" -> ENGINE;
            // The literal set serves only the matcher's find.
            case "LITERAL_SET" -> ENGINE;
            // The ordered-literal find plan takes only gaps that match any bytes, and a UTF-8
            // `(?s:.)` matches no malformed UTF-8 in RE2, Java, or Rust, so these gaps keep the
            // general engine; ORDERED_LITERALS_BYTES reaches the plan.
            case "ORDERED_LITERALS" -> ENGINE;
            // An inner literal of two or more bytes keeps the DFA's inner literal search instead
            // of the fixed-width matcher, which answers only short counts and matcher finds.
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
            case "CONTAINS" -> {
                assertThat(compiled.findPlanForDiagnostics()).as(description).isEqualTo(CONTAINS);
                assertThat(compiled.literalSearchForDiagnostics()).as(description).isNotNull();
            }
            case "DOT_STAR_LITERAL" -> {
                assertThat(compiled.findPlanForDiagnostics()).as(description).isEqualTo(compiled instanceof JavaRegexp ? GENERAL : CONTAINS);
                // RE2 and Rust count the literal kernel's dot-star spans, and the matcher finds them.
                if (compiled instanceof JavaRegexp) {
                    assertThat(compiled.createDotStarLiteralSpanMatcher()).as(description).isNull();
                    assertThat(matcher.spanSearchForDiagnostics()).as(description).isNull();
                }
                else {
                    assertThat(compiled.createDotStarLiteralSpanMatcher()).as(description).isNotNull();
                    assertThat(matcher.spanSearchForDiagnostics()).as(description).isSameAs(compiled.createDotStarLiteralSpanMatcher());
                }
            }
            case "SINGLE_BYTE" -> {
                assertThat(compiled.sharedSingleByteMatcher()).as(description).isNotNull();
                assertThat(matcher.singleByteMatcherForDiagnostics()).as(description).isSameAs(compiled.sharedSingleByteMatcher());
                // A find with at least 64 bytes left, on LONG rows, runs the forward cursor.
                assertThat(matcher.groupZeroForwardCursorEnabledForDiagnostics()).as(description).isTrue();
            }
            case "LITERAL_ALTERNATION" -> {
                assertThat(compiled.literalAlternationSpanMatcherForDiagnostics()).as(description).isNotNull();
                assertThat(matcher.spanSearchForDiagnostics()).as(description).isSameAs(compiled.literalAlternationSpanMatcherForDiagnostics());
            }
            case "LITERAL_SET" -> {
                assertThat(compiled.literalSetSpanMatcherForDiagnostics()).as(description).isNotNull();
                assertThat(matcher.spanSearchForDiagnostics()).as(description).isSameAs(compiled.literalSetSpanMatcherForDiagnostics());
                // LONG rows skip the bytes that lead no word.
                assertThat(compiled.literalSetSpanMatcherForDiagnostics().skipsRootBytes(1024)).as(description).isEqualTo(VectorSupport.isAvailable());
            }
            case "FIXED_WIDTH" -> {
                assertThat(compiled.fixedWidthByteSpanMatcherForDiagnostics()).as(description).isNotNull();
                assertThat(matcher.spanSearchForDiagnostics()).as(description).isSameAs(compiled.fixedWidthByteSpanMatcherForDiagnostics());
            }
            case "ANCHORED_LITERAL" -> assertThat(compiled.findPlanForDiagnostics()).as(description).isEqualTo(STARTS_WITH);
            case "ORDERED_LITERALS" -> {
                assertThat(compiled.findPlanForDiagnostics()).as(description).isEqualTo(GENERAL);
                assertThat(compiled.orderedLiteralMatcherForDiagnostics()).as(description).isNull();
            }
            // Gaps that match any byte, in RE2's Latin-1 option and Rust's bytes API after
            // `(?-u)`, run the ordered-literal find plan.
            case "ORDERED_LITERALS_BYTES" -> assertThat(compiled.findPlanForDiagnostics()).as(description).isEqualTo(ORDERED_LITERALS);
            case "INNER_LITERAL_FIXED_WIDTH" -> {
                assertThat(compiled.innerLiteralSearchForDiagnostics()).as(description).isNotNull();
                assertThat(compiled.shortFixedWidthByteMatcher()).as(description).isNotNull();
            }
            case "GENERAL" -> {
                assertThat(compiled.sharedSingleByteMatcher()).as(description).isNull();
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

    // The byte target's pattern, in RE2's Latin-1 option or after `(?-u)` in Rust's bytes API.
    private static CompiledPattern bytePattern(String language, String expression)
    {
        return switch (language) {
            case "RE2" -> Re2.compile(utf8Slice(expression), Re2.Options.latin1());
            case "RUST" -> RustRegexp.compile(utf8Slice("(?-u)" + expression), RustRegexp.Options.bytes());
            default -> throw new IllegalArgumentException("no byte mode: " + language);
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

    // The sum that the loop of one-shot search `operation` returns over `data`'s rows, from the
    // spans of `reference`'s find loop.
    private static long oneShotResult(String operation, BenchmarkPatternDispatch.BenchmarkData data, Object reference)
    {
        long result = 0;
        for (Slice input : data.inputs) {
            result += BenchmarkPatternDispatch.oneShotRowResult(operation, input, spans(reference, input));
        }
        return result;
    }

    // The spans of a find loop over the whole input, each with its start in the high half and its
    // end in the low half: the generic group-zero matcher's, or a Trino pattern's matcher's.
    private static long[] spans(Object pattern, Slice input)
    {
        LongStream.Builder spans = LongStream.builder();
        switch (pattern) {
            case CompiledPattern compiled -> {
                RegexpMatcher matcher = compiled.genericGroupZeroMatcherForDiagnostics(input);
                while (matcher.find()) {
                    spans.add(((long) matcher.start() << 32) | matcher.end());
                }
            }
            case TrinoRegexp trino -> {
                TrinoRegexpMatcher matcher = trino.matcher(input, 0);
                while (matcher.find()) {
                    spans.add(((long) matcher.start() << 32) | matcher.end());
                }
            }
            default -> throw new IllegalArgumentException("unhandled compiled pattern");
        }
        return spans.build().toArray();
    }

    // The non-overlapping matches of a find loop over the whole input: the generic group-zero
    // matcher's, whose finds of DOT_STAR_LITERAL in RE2 and Rust still take the dot-star literal
    // matcher, as the benchmark's do, or a Trino pattern's count. A test that needs the engine's
    // spans runs the pattern with its dots inside a capture instead, as TestPublicEngineAgreement
    // does.
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
