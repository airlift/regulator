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
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.CONTAINS;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.ENDS_WITH;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.EQUALS;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.GENERAL;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.LITERAL_SEARCH;
import static io.airlift.regulator.CompiledPattern.BooleanPlanKind.NULLABLE_START;
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
import static io.airlift.slice.SizeOf.instanceSize;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

public class TestRe2BooleanPlans
{
    @Test
    public void testInstanceLayout()
    {
        // The shape class fixes the routes, so a pattern stores no route of its own; only the
        // class-run shape adds its retained count DFA flag, and the scan-plan shape its count,
        // short-match, and literal find facts.
        assertThat(instanceSize(Re2.class)).isEqualTo(96);
        assertThat(instanceSize(Re2.ScanPlanRe2.class)).isEqualTo(104);
    }

    @Test
    public void testPlanSelection()
    {
        assertPlans("foo", LITERAL_SEARCH, STARTS_WITH, EQUALS);
        assertPlans("foo{1}", LITERAL_SEARCH, STARTS_WITH, EQUALS);
        assertPlans("(foo)", LITERAL_SEARCH, STARTS_WITH, EQUALS);
        assertPlans("^foo", STARTS_WITH, STARTS_WITH, EQUALS);
        assertPlans("foo$", ENDS_WITH, EQUALS, EQUALS);
        assertPlans("^foo$", EQUALS, EQUALS, EQUALS);

        assertPlans("(?s:.*foo.*)", CONTAINS, GENERAL, GENERAL);
        assertPlans("(?s:.*?foo.*?)", CONTAINS, GENERAL, GENERAL);
        assertPlans(".*foo.*", CONTAINS, GENERAL, GENERAL);
        assertPlans(".*?foo.*?", CONTAINS, GENERAL, GENERAL);
        assertPlans(".*(foo).*", CONTAINS, GENERAL, GENERAL);
        assertPlans(".*foo", CONTAINS, GENERAL, GENERAL);
        assertPlans("foo.*", CONTAINS, STARTS_WITH, GENERAL);
        assertPlans("^foo(?s:.*)$", GENERAL, GENERAL, GENERAL);
        assertPlans("(?s:.*)foo$", GENERAL, GENERAL, GENERAL);
        assertPlans("^foo\\C*$", GENERAL, STARTS_WITH, STARTS_WITH);

        assertPlans("foo.*", Re2.Options.defaults().setLiteral(true), LITERAL_SEARCH, STARTS_WITH, EQUALS);
        assertPlans("foo", Re2.Options.posix(), LITERAL_SEARCH, STARTS_WITH, EQUALS);
        assertPlans(
                "^foo.*$",
                Re2.Options.defaults().setDotMatchesNewline(true),
                GENERAL,
                GENERAL,
                GENERAL);
        assertPlans("^foo.*$", Re2.Options.defaults().setNeverNewline(true), GENERAL, GENERAL, GENERAL);
        assertPlans("(?m:^foo$)", Re2.Options.defaults().setOneLine(false), GENERAL, GENERAL, GENERAL);

        Re2 latin1Prefix = Re2.compile(Slices.utf8Slice("^foo(?s:.*)$"), Re2.Options.latin1());
        assertThat(latin1Prefix.matchesPlanForDiagnostics()).isEqualTo(STARTS_WITH);
        Re2 latin1Suffix = Re2.compile(Slices.utf8Slice("(?s:.*)foo$"), Re2.Options.latin1());
        assertThat(latin1Suffix.matchesPlanForDiagnostics()).isEqualTo(ENDS_WITH);
    }

    @Test
    public void testShapeSelection()
    {
        // Each row lists the shape the pattern takes in RE2, Java, Rust, and Trino, where no
        // Trino shape means Trino answers a literal alternation without a compiled pattern. Each
        // shape class declares the overrides its shape promises.
        record ShapeRow(String pattern, Shape re2, Shape java, Shape rust, Shape trino) {}

        for (ShapeRow row : List.of(
                new ShapeRow("(?i:foo)", ENGINE, ENGINE, ENGINE, ENGINE),
                new ShapeRow("literal", EXACT_LITERAL, EXACT_LITERAL, EXACT_LITERAL, EXACT_LITERAL),
                new ShapeRow("(foo)", EXACT_LITERAL, EXACT_LITERAL, EXACT_LITERAL, EXACT_LITERAL),
                new ShapeRow("foo{1}", EXACT_LITERAL, EXACT_LITERAL, EXACT_LITERAL, EXACT_LITERAL),
                new ShapeRow("^GET /", LITERAL_KERNEL, LITERAL_KERNEL, LITERAL_KERNEL, LITERAL_KERNEL),
                // A Java `$` matches before any final line terminator, so no literal kernel serves it.
                new ShapeRow("^foo$", LITERAL_KERNEL, ENGINE, LITERAL_KERNEL, LITERAL_KERNEL),
                new ShapeRow("(?s).*timeout.*", LITERAL_KERNEL, LITERAL_KERNEL, LITERAL_KERNEL, LITERAL_KERNEL),
                new ShapeRow("ERROR|WARN|FATAL", LITERAL_ALTERNATION, LITERAL_ALTERNATION, LITERAL_ALTERNATION, null),
                new ShapeRow("[a-z]{8}-[0-9]{4}", FIXED_WIDTH, FIXED_WIDTH, FIXED_WIDTH, FIXED_WIDTH),
                new ShapeRow("\\p{L}+", CLASS_RUN, CLASS_RUN, CLASS_RUN, CLASS_RUN),
                new ShapeRow("[,;]", BYTE_TABLE, BYTE_TABLE, BYTE_TABLE, BYTE_TABLE),
                new ShapeRow("https?://([^/]+)/", SCAN_PLAN, SCAN_PLAN, SCAN_PLAN, SCAN_PLAN),
                new ShapeRow("(?i)content-type:([^;]+);", SCAN_PLAN, SCAN_PLAN, SCAN_PLAN, SCAN_PLAN),
                // Patterns more than one shape could serve.
                // A Rust `\s` is Unicode, so it is no byte run.
                new ShapeRow("\\s+", BYTE_TABLE, BYTE_TABLE, ENGINE, BYTE_TABLE),
                // A literal led plan keeps literal kernels for find and lookingAt; its other
                // operations run the plan.
                new ShapeRow("(?s)abc(.*)", SCAN_PLAN, SCAN_PLAN, SCAN_PLAN, SCAN_PLAN),
                new ShapeRow("abc|def", LITERAL_ALTERNATION, LITERAL_ALTERNATION, LITERAL_ALTERNATION, null),
                // Patterns that can match empty, which only the engine shape accepts.
                new ShapeRow("", ENGINE, ENGINE, ENGINE, ENGINE),
                new ShapeRow("[,;]*", ENGINE, ENGINE, ENGINE, ENGINE),
                new ShapeRow("(?:foo)?", ENGINE, ENGINE, ENGINE, ENGINE),
                new ShapeRow(".*$", ENGINE, ENGINE, ENGINE, ENGINE))) {
            Slice pattern = utf8(row.pattern());
            assertShape(Re2.compile(pattern), row.re2(), "RE2: " + row.pattern());
            assertShape(JavaRegexp.compile(pattern), row.java(), "Java: " + row.pattern());
            assertShape(RustRegexp.compile(pattern), row.rust(), "Rust: " + row.pattern());
            TrinoCompiledPattern trino = TrinoRegexp.compile(pattern).re2();
            if (row.trino() == null) {
                assertThat(trino).as("Trino: %s", row.pattern()).isNull();
            }
            else {
                assertShape(trino, row.trino(), "Trino: " + row.pattern());
            }
        }
    }

    @Test
    public void testRejectedPlans()
    {
        assertPlans("", LITERAL_SEARCH, GENERAL, GENERAL);
        assertPlans("a*", NULLABLE_START, GENERAL, GENERAL);
        assertPlans("(?i:foo)", GENERAL, GENERAL, GENERAL);
        assertPlans("foo.*bar", GENERAL, GENERAL, GENERAL);
        assertPlans("foo.bar", GENERAL, GENERAL, GENERAL);
        assertPlans("foo.", GENERAL, GENERAL, GENERAL);
        assertPlans("foo[0-9]", GENERAL, GENERAL, GENERAL);
        assertPlans("foo(?s:.*)bar", GENERAL, GENERAL, GENERAL);
        assertPlans("(?m:^foo$)", GENERAL, GENERAL, GENERAL);
        assertPlans("\\bfoo\\b", GENERAL, GENERAL, GENERAL);
    }

    @Test
    public void testContainedLiteralUsesLiteralSearch()
    {
        // Every language finds a contained literal of two or more bytes with the literal search
        // and a one-byte literal with Slice.indexOf. In a run of the literal's first byte,
        // Slice.indexOf would compare half of the long literal at every position; the search
        // continues with KMP once its failed candidates pass the comparison budget. Here each
        // candidate compares 33 bytes and covers one, so the second passes the budget.
        String half = "a".repeat(LiteralSearchKernel.CANDIDATE_COMPARISON_BUDGET / 2);
        String literal = half + "b" + half;
        Slice input = utf8(half.repeat(64) + literal);
        for (String contained : List.of("b", "ab", literal)) {
            Slice pattern = utf8("(?s:.*" + contained + ".*)");
            for (CompiledPattern compiledPattern : List.of(Re2.compile(pattern), JavaRegexp.compile(pattern), RustRegexp.compile(pattern))) {
                assertThat(compiledPattern.findPlanForDiagnostics()).isEqualTo(CONTAINS);
                assertThat(compiledPattern.literalSearchForDiagnostics() != null).isEqualTo(contained.length() >= 2);
                if (contained.equals(literal)) {
                    assertThat(compiledPattern.literalSearchForDiagnostics().kmpHandoffOffsetForDiagnostics(input, 0, input.length())).isEqualTo(2);
                }
                assertThat(compiledPattern.find(input)).isTrue();
            }
        }
    }

    @Test
    public void testCaptureOperationsRemainOnGeneralEngine()
    {
        Re2 re2 = Re2.compile(utf8("(foo)"));
        assertThat(re2.findPlanForDiagnostics()).isEqualTo(LITERAL_SEARCH);

        int[] groups = new int[4];
        assertThat(re2.findInto(utf8("xxfooyy"), groups)).isTrue();
        assertThat(groups).containsExactly(2, 5, 2, 5);
        assertThat(re2.findResult(utf8("xxfooyy")).groupUtf8(1)).isEqualTo("foo");

        Re2 contains = Re2.compile(utf8("(.*)(foo)(.*)"));
        assertThat(contains.findPlanForDiagnostics()).isEqualTo(CONTAINS);
        assertThat(contains.findResult(utf8("xxfooyy")).groupUtf8(2)).isEqualTo("foo");
    }

    /// Asserts that `pattern` has `expected` shape and that its class declares the overrides of
    /// that shape, which a lost override would otherwise leave to the shared engine routes.
    private static void assertShape(CompiledPattern pattern, Shape expected, String description)
    {
        assertThat(shape(pattern)).as(description).isEqualTo(expected);
        if (expected == ENGINE) {
            // The language class itself, whose routes every shape class shares.
            return;
        }
        Set<String> overrides = switch (expected) {
            case ENGINE -> throw new IllegalArgumentException("the engine shape has no shape class");
            case EXACT_LITERAL -> Set.of("find", "countMatches", "matches", "lookingAt");
            case LITERAL_KERNEL -> Set.of("find", "matches", "lookingAt");
            case LITERAL_ALTERNATION, FIXED_WIDTH, BYTE_TABLE -> Set.of("find", "countMatches");
            case CLASS_RUN -> Set.of("find", "countMatches", "matchInternal");
            case SCAN_PLAN -> Set.of("find", "countMatches", "matches", "lookingAt", "matchInternal");
        };
        Set<String> routes = Set.of("find", "countMatches", "matches", "lookingAt");
        Set<String> declared = Arrays.stream(pattern.getClass().getDeclaredMethods())
                .filter(method -> (routes.contains(method.getName()) && Arrays.equals(method.getParameterTypes(), new Class<?>[] {Slice.class})) ||
                        (method.getName().equals("matchInternal") && method.getParameterCount() == 9))
                .map(Method::getName)
                .collect(toSet());
        assertThat(declared).as(description).isEqualTo(overrides);
    }

    private static void assertPlans(
            String pattern,
            CompiledPattern.BooleanPlanKind find,
            CompiledPattern.BooleanPlanKind lookingAt,
            CompiledPattern.BooleanPlanKind matches)
    {
        assertPlans(pattern, Re2.Options.defaults(), find, lookingAt, matches);
    }

    private static void assertPlans(
            String pattern,
            Re2.Options options,
            CompiledPattern.BooleanPlanKind find,
            CompiledPattern.BooleanPlanKind lookingAt,
            CompiledPattern.BooleanPlanKind matches)
    {
        Re2 re2 = Re2.compile(utf8(pattern), options);
        assertThat(re2.findPlanForDiagnostics()).as("find: %s", pattern).isEqualTo(find);
        assertThat(re2.lookingAtPlanForDiagnostics()).as("lookingAt: %s", pattern).isEqualTo(lookingAt);
        assertThat(re2.matchesPlanForDiagnostics()).as("matches: %s", pattern).isEqualTo(matches);
    }

    private static Slice utf8(String value)
    {
        return Slices.utf8Slice(value);
    }
}
