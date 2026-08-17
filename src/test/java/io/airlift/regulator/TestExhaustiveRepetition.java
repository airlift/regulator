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

import java.util.ArrayList;
import java.util.List;

import static io.airlift.regulator.TestingExhaustive.runExhaustiveTest;
import static java.nio.charset.StandardCharsets.UTF_8;

// Ported from upstream RE2: re2/testing/exhaustive1_test.cc.
public class TestExhaustiveRepetition
{
    private static List<Slice> repetitionOps()
    {
        List<Slice> ops = new ArrayList<>();
        for (String op : List.of(
                "%s{0}",
                "%s{0,}",
                "%s{1}",
                "%s{1,}",
                "%s{0,1}",
                "%s{0,2}",
                "%s{1,2}",
                "%s{2}",
                "%s{2,}",
                "%s{3,4}",
                "%s{4,5}",
                "%s*",
                "%s+",
                "%s?",
                "%s*?",
                "%s+?",
                "%s??")) {
            ops.add(Slices.wrappedBuffer(op.getBytes(UTF_8)));
        }
        return ops;
    }

    // Test simple repetition operators.
    @Test
    public void testRepetitionSimpleShortStrings()
    {
        runExhaustiveTest(
                List.of("a", "b", "c", "."),
                repetitionOps(),
                3,
                2,
                "ab",
                6,
                "(?:%s)");
    }

    @Test
    public void testRepetitionSimpleLongStrings()
    {
        runExhaustiveTest(
                List.of("a", "b", "c", "."),
                repetitionOps(),
                3,
                2,
                "a",
                40,
                "(?:%s)");
    }

    // Test capturing parens -- (a) -- inside repetition operators.
    @Test
    public void testRepetitionCapturingShortStrings()
    {
        runExhaustiveTest(
                List.of("a", "(a)", "b"),
                repetitionOps(),
                3,
                2,
                "ab",
                7,
                "(?:%s)");
    }

    @Test
    public void testRepetitionCapturingLongStrings()
    {
        runExhaustiveTest(
                List.of("a", "(a)"),
                repetitionOps(),
                3,
                2,
                "a",
                50,
                "(?:%s)");
    }
}
