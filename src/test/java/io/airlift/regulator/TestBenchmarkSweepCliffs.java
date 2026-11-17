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

import io.airlift.regulator.BenchmarkSweepCliffs.Workload;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestBenchmarkSweepCliffs
{
    private static final long DEFAULT_BUDGET = 32L << 20;
    private static final long SMALL_BUDGET = 1L << 20;

    // The workloads whose mechanism this branch switches off, whose setup must show that the
    // mechanism did not fire. Every other workload must show that it fired.
    private static final Set<Workload> SWITCHED_OFF = EnumSet.noneOf(Workload.class);

    /// Runs each workload at the smallest input and budget of the benchmark's parameters, or
    /// below them, at which its mechanism fires: setup checks the result against its reference,
    /// and its route facts show the mechanism fired.
    @Test
    public void testEveryWorkloadReachesItsMechanism()
    {
        BenchmarkSweepCliffs benchmark = new BenchmarkSweepCliffs();
        for (Workload workload : Workload.values()) {
            BenchmarkSweepCliffs.BenchmarkData data = setup(workload, inputBytes(workload), budget(workload));
            assertThat(data.hasReference()).as(data.diagnostic()).isTrue();
            assertThat(data.result()).as(data.diagnostic()).isPositive();
            assertThat(benchmark.run(data)).as(data.diagnostic()).isEqualTo(data.result());
            assertThat(data.mechanismFired()).as(data.diagnostic()).isEqualTo(!SWITCHED_OFF.contains(workload));
        }
    }

    @Test
    public void testLargeDictionariesNeedTheDefaultBudget()
    {
        for (Workload workload : new Workload[] {Workload.A2_10K, Workload.A2_50K}) {
            assertThatThrownBy(() -> setup(workload, 1 << 16, SMALL_BUDGET))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("runs only with budget=33554432");
        }
    }

    // At the 32 MiB default the short-words count resets the DFA without bailing at 64 KiB, as
    // do the line searches and the tagged alternation's searches, and they bail or judge their
    // resets slow at 1 MiB. The bounded repeats' counts bail at 64 KiB only at 1 MiB, where the
    // automaton's windows outgrow BitState's limit and run on the Pike NFA too, so their finds
    // show the automaton only at the default budget, from 128 KiB in Latin-1 and 256 KiB in UTF-8.
    // At 1 MiB the date pattern's DFA has no candidate start cursor.
    private static int inputBytes(Workload workload)
    {
        return switch (workload) {
            case E11_LATIN1 -> 128 << 10;
            case E11_UTF8 -> 256 << 10;
            default -> 64 << 10;
        };
    }

    private static long budget(Workload workload)
    {
        return switch (workload) {
            case E5_COUNT_STREAK, E5_LINE_GREP, E5_TAGGED -> SMALL_BUDGET;
            default -> DEFAULT_BUDGET;
        };
    }

    private static BenchmarkSweepCliffs.BenchmarkData setup(Workload workload, int inputBytes, long budget)
    {
        BenchmarkSweepCliffs.BenchmarkData data = new BenchmarkSweepCliffs.BenchmarkData();
        data.workload = workload.name();
        data.inputBytes = inputBytes;
        data.budget = budget;
        data.setup();
        return data;
    }
}
