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

import java.lang.reflect.Field;

import static io.airlift.regulator.Re2.BooleanPlanKind.CONTAINS;
import static io.airlift.regulator.Re2.BooleanPlanKind.ENDS_WITH;
import static io.airlift.regulator.Re2.BooleanPlanKind.EQUALS;
import static io.airlift.regulator.Re2.BooleanPlanKind.GENERAL;
import static io.airlift.regulator.Re2.BooleanPlanKind.LITERAL_SEARCH;
import static io.airlift.regulator.Re2.BooleanPlanKind.NULLABLE_START;
import static io.airlift.regulator.Re2.BooleanPlanKind.STARTS_WITH;
import static io.airlift.slice.SizeOf.instanceSize;
import static org.assertj.core.api.Assertions.assertThat;

public class TestRe2BooleanPlans
{
    @Test
    public void testFindStrategyAndSharedCacheLayout()
            throws ReflectiveOperationException
    {
        Field strategy = Re2.class.getDeclaredField("booleanFindStrategy");
        assertThat(strategy.getType()).isEqualTo(byte.class);
        assertThat(instanceSize(Re2.class)).isEqualTo(96);
    }

    @Test
    public void testPlanSelection()
    {
        assertPlans("foo", LITERAL_SEARCH, STARTS_WITH, EQUALS);
        assertPlans("foo{1}", LITERAL_SEARCH, STARTS_WITH, EQUALS);
        assertThat(Re2.compile(utf8("foo{1}")).booleanPartialMatchStrategyForDiagnostics())
                .isEqualTo(Re2.BooleanPartialMatchStrategy.EXACT_LITERAL);
        assertPlans("(foo)", LITERAL_SEARCH, STARTS_WITH, EQUALS);
        assertPlans("^foo", STARTS_WITH, STARTS_WITH, EQUALS);
        assertPlans("foo$", ENDS_WITH, EQUALS, EQUALS);
        assertPlans("^foo$", EQUALS, EQUALS, EQUALS);
        assertThat(Re2.compile(utf8("^foo$")).booleanPartialMatchStrategyForDiagnostics())
                .isEqualTo(Re2.BooleanPartialMatchStrategy.EQUALS);

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

    private static void assertPlans(
            String pattern,
            Re2.BooleanPlanKind find,
            Re2.BooleanPlanKind lookingAt,
            Re2.BooleanPlanKind matches)
    {
        assertPlans(pattern, Re2.Options.defaults(), find, lookingAt, matches);
    }

    private static void assertPlans(
            String pattern,
            Re2.Options options,
            Re2.BooleanPlanKind find,
            Re2.BooleanPlanKind lookingAt,
            Re2.BooleanPlanKind matches)
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
