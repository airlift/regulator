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

import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkTrinoScanKernelDispatch
{
    @Test
    public void testExecutorsAgreeThroughBothCallers()
            throws Throwable
    {
        BenchmarkTrinoScanKernelDispatch benchmark = new BenchmarkTrinoScanKernelDispatch();
        for (String target : new String[] {"PLAN_0", "PLAN_1", "PLAN_2", "PLAN_3", "UNANCHORED", "SHORT"}) {
            for (String operation : new String[] {"contains", "count", "extract", "position"}) {
                for (String executor : new String[] {"INTERPRETER", "GENERATED"}) {
                    // Interleaving trains every pattern of the workload; once per target suffices.
                    for (String profile : operation.equals("contains") ? new String[] {"ISOLATED", "INTERLEAVED"} : new String[] {"ISOLATED"}) {
                        String description = executor + " " + profile + " " + operation + " " + target;
                        BenchmarkTrinoScanKernelDispatch.BenchmarkData data = new BenchmarkTrinoScanKernelDispatch.BenchmarkData();
                        data.executor = executor;
                        data.profile = profile;
                        data.operation = operation;
                        data.target = target;
                        data.setup();
                        data.refresh();
                        assertThat(data.pattern.usesGeneratedBytecode()).as(description).isEqualTo(executor.equals("GENERATED"));

                        TrinoRegexp interpreter = TrinoRegexp.compile(data.expression);
                        // Both methods advance the shared cursor by one batch start.
                        for (int start = 0; start < 4; start += 2) {
                            assertThat(benchmark.publicApi(data)).as(description)
                                    .isEqualTo(BenchmarkTrinoScanKernelDispatch.expectedBatch(interpreter, operation, data.inputs, start));
                            assertThat(benchmark.boundCaller(data)).as(description)
                                    .isEqualTo(BenchmarkTrinoScanKernelDispatch.expectedBatch(interpreter, operation, data.inputs, start + 1));
                        }
                    }
                }
            }
        }
    }
}
