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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestBenchmarkTrinoScanKernelCost
{
    @Test
    public void testDistinctPatternsGenerateMatchingClasses()
    {
        List<ScanKernelGenerator.TrinoRoutes> routes = BenchmarkTrinoScanKernelCost.routes(8);
        int[] groups = new int[2];
        for (int index = 0; index < routes.size(); index++) {
            Slice input = BenchmarkTrinoScanKernelCost.input(index);
            Slice other = BenchmarkTrinoScanKernelCost.input(index + 1);
            CompiledTrinoScan scan = ScanKernelGenerator.generateCompiledTrinoScan(routes.get(index)).scan();
            assertThat(TrinoRegexp.compile(BenchmarkTrinoScanKernelCost.expression(index)).contains(input)).as("pattern %s", index).isTrue();
            assertThat(scan.find(input, 0, groups)).as("pattern %s", index).isTrue();
            assertThat(groups).as("pattern %s", index).containsExactly(0, input.length());
            // Each pattern carries its own index, so it rejects its neighbor's input.
            assertThat(scan.find(other, 0, groups)).as("pattern %s", index).isFalse();
        }
    }
}
