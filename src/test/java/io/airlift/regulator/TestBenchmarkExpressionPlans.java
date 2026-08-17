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

import org.junit.jupiter.api.Test;

import static io.airlift.regulator.Re2.BooleanPartialMatchStrategy.EXACT_LITERAL;
import static io.airlift.regulator.Re2.BooleanPlanKind.CONTAINS;
import static io.airlift.regulator.Re2.BooleanPlanKind.EQUALS;
import static io.airlift.regulator.Re2.BooleanPlanKind.GENERAL;
import static io.airlift.regulator.Re2.BooleanPlanKind.LITERAL_SEARCH;
import static io.airlift.regulator.Re2.BooleanPlanKind.STARTS_WITH;
import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkExpressionPlans
{
    @Test
    public void testWorkloadsUseEquivalentOperations()
    {
        BenchmarkExpressionPlans benchmark = new BenchmarkExpressionPlans();
        for (TestingExpressionPlanBenchmarkInputs.Workload workload : TestingExpressionPlanBenchmarkInputs.Workload.values()) {
            BenchmarkExpressionPlans.BenchmarkData data = new BenchmarkExpressionPlans.BenchmarkData();
            data.workload = workload;
            data.setup();

            assertThat(data.literal.booleanPartialMatchStrategyForDiagnostics()).isEqualTo(EXACT_LITERAL);
            assertThat(data.contains.booleanPartialMatchStrategyForDiagnostics())
                    .isEqualTo(Re2.BooleanPartialMatchStrategy.CONTAINS);
            assertThat(data.ordered.booleanPartialMatchStrategyForDiagnostics()).isEqualTo(data.orderedStrategy);

            assertThat(data.literal.findPlanForDiagnostics()).isEqualTo(LITERAL_SEARCH);
            assertThat(data.literal.lookingAtPlanForDiagnostics()).isEqualTo(STARTS_WITH);
            assertThat(data.literal.matchesPlanForDiagnostics()).isEqualTo(EQUALS);
            assertThat(data.contains.findPlanForDiagnostics()).isEqualTo(CONTAINS);
            assertThat(data.prefix.matchesPlanForDiagnostics()).isEqualTo(GENERAL);
            assertThat(data.suffix.matchesPlanForDiagnostics()).isEqualTo(GENERAL);
            assertThat(data.unsupported.findPlanForDiagnostics()).isEqualTo(GENERAL);
            assertThat(data.unsupported.lookingAtPlanForDiagnostics()).isEqualTo(GENERAL);
            assertThat(data.unsupported.matchesPlanForDiagnostics()).isEqualTo(GENERAL);

            assertThat(benchmark.findLiteral(data))
                    .as("literal find for %s", workload)
                    .isEqualTo(data.expected.findLiteral())
                    .isEqualTo(benchmark.findLiteralGeneral(data));
            assertThat(benchmark.findContains(data))
                    .as("contains find for %s", workload)
                    .isEqualTo(data.expected.findContains())
                    .isEqualTo(benchmark.findContainsGeneral(data));
            assertThat(benchmark.findOrdered(data))
                    .as("ordered find for %s", workload)
                    .isEqualTo(data.expected.findOrdered())
                    .isEqualTo(benchmark.findOrderedGeneral(data));
            assertThat(benchmark.findUnsupported(data)).isEqualTo(benchmark.findUnsupportedGeneral(data));
            assertThat(benchmark.lookingAtLiteral(data))
                    .as("literal lookingAt for %s", workload)
                    .isEqualTo(data.expected.lookingAtLiteral())
                    .isEqualTo(benchmark.lookingAtLiteralGeneral(data));
            assertThat(benchmark.lookingAtUnsupported(data)).isEqualTo(benchmark.lookingAtUnsupportedGeneral(data));
            assertThat(benchmark.matchesLiteral(data))
                    .as("literal matches for %s", workload)
                    .isEqualTo(data.expected.matchesLiteral())
                    .isEqualTo(benchmark.matchesLiteralGeneral(data));
            assertThat(benchmark.matchesPrefix(data))
                    .as("prefix matches for %s", workload)
                    .isEqualTo(data.expected.matchesPrefix())
                    .isEqualTo(benchmark.matchesPrefixGeneral(data));
            assertThat(benchmark.matchesSuffix(data))
                    .as("suffix matches for %s", workload)
                    .isEqualTo(data.expected.matchesSuffix())
                    .isEqualTo(benchmark.matchesSuffixGeneral(data));
            assertThat(benchmark.matchesUnsupported(data)).isEqualTo(benchmark.matchesUnsupportedGeneral(data));
        }
    }

    @Test
    public void testProtectedFindControlsUseEquivalentOperations()
    {
        BenchmarkExpressionPlans benchmark = new BenchmarkExpressionPlans();
        for (BenchmarkExpressionPlans.FindControl control : BenchmarkExpressionPlans.FindControl.values()) {
            BenchmarkExpressionPlans.FindControlData data = new BenchmarkExpressionPlans.FindControlData();
            data.control = control;
            data.setup();

            assertThat(benchmark.findProtectedControl(data))
                    .as("protected find control %s", control)
                    .isEqualTo(data.expected)
                    .isEqualTo(benchmark.findProtectedControlGeneral(data));
        }
    }

    @Test
    public void testProtectedCompleteControlsUseEquivalentOperations()
    {
        BenchmarkExpressionPlans benchmark = new BenchmarkExpressionPlans();
        for (BenchmarkExpressionPlans.CompleteControl control : BenchmarkExpressionPlans.CompleteControl.values()) {
            BenchmarkExpressionPlans.CompleteControlData data = new BenchmarkExpressionPlans.CompleteControlData();
            data.control = control;
            data.setup();

            assertThat(benchmark.matchesProtectedControl(data))
                    .as("protected complete control %s", control)
                    .isEqualTo(data.expected)
                    .isEqualTo(benchmark.matchesProtectedControlGeneral(data));
        }
    }
}
