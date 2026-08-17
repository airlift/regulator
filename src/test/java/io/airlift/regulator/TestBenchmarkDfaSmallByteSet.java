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

public class TestBenchmarkDfaSmallByteSet
{
    @Test
    public void testBenchmarkInputs()
    {
        BenchmarkDfaSmallByteSet benchmark = new BenchmarkDfaSmallByteSet();
        for (BenchmarkDfaSmallByteSet.Consumer consumer : BenchmarkDfaSmallByteSet.Consumer.values()) {
            for (BenchmarkDfaSmallByteSet.InputShape inputShape : BenchmarkDfaSmallByteSet.InputShape.values()) {
                BenchmarkDfaSmallByteSet.BenchmarkData data = new BenchmarkDfaSmallByteSet.BenchmarkData();
                data.consumer = consumer;
                data.inputShape = inputShape;
                data.sourceLength = 4_096;
                data.setup();

                assertThat(benchmark.find(data))
                        .as("%s %s", consumer, inputShape)
                        .isEqualTo(data.expectedCount() > 0);
                assertThat(benchmark.countMatches(data))
                        .as("%s %s", consumer, inputShape)
                        .isEqualTo(data.expectedCount());
            }
        }
    }
}
