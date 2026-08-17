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
package io.trino.operator.scalar;

import io.airlift.regulator.TrinoRegexp;
import io.airlift.slice.Slice;
import io.trino.spi.block.Block;
import io.trino.type.JoniRegexp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.operator.scalar.JoniRegexpCasts.joniRegexp;
import static io.trino.spi.type.VarcharType.VARCHAR;

public final class JoniSliceOperationsVerifier
{
    private JoniSliceOperationsVerifier() {}

    public static void main(String[] args)
    {
        for (String expression : List.of("", "a*", "(a)(b)?()", "(é+)(💰)?", "a$", "(?m)^a$", "foo|bar", "(a)".repeat(32))) {
            Slice pattern = utf8Slice(expression);
            JoniRegexp joni = joniRegexp(pattern);
            TrinoRegexp regulator = TrinoRegexp.compile(pattern);
            for (String text : List.of("", "no match", "a ab", "éé💰a\n", "foofoobar", "a".repeat(32), "a\r\na\n")) {
                Slice source = utf8Slice("padding" + text).slice(7, utf8Slice(text).length());
                verify(source, joni);
                equal(regulator.extractAll(source), JoniSliceOperations.extractAll(source, joni), "Regulator extractAll");
                equal(regulator.split(source), JoniSliceOperations.split(source, joni), "Regulator split");
                for (Function<List<Slice>, Slice> callback : callbacks()) {
                    equal(regulator.replace(source, callback), JoniSliceOperations.replace(source, joni, callback), "Regulator callback replacement");
                }
            }
        }
        System.out.println("Verified Slice adapter outputs and callback traces against pinned Trino");
    }

    public static void verify(Slice source, JoniRegexp pattern)
    {
        equal(JoniSliceOperations.extractAll(source, pattern), values(JoniRegexpFunctions.regexpExtractAll(source, pattern)), "extractAll");
        equal(JoniSliceOperations.split(source, pattern), values(JoniRegexpFunctions.regexpSplit(source, pattern)), "split");
        for (Function<List<Slice>, Slice> callback : callbacks()) {
            List<List<Slice>> actualGroups = new ArrayList<>();
            Slice actual = JoniSliceOperations.replace(source, pattern, groups -> {
                actualGroups.add(new ArrayList<>(groups));
                return callback.apply(groups);
            });
            List<List<Slice>> expectedGroups = new ArrayList<>();
            Slice expected = new JoniRegexpReplaceLambdaFunction().regexpReplace(source, pattern, block -> {
                List<Slice> groups = values((Block) block);
                expectedGroups.add(groups);
                return callback.apply(groups);
            });
            equal(actual, expected, "replacement output");
            equal(actualGroups, expectedGroups, "ordered callback captures");
        }
    }

    private static List<Function<List<Slice>, Slice>> callbacks()
    {
        return List.of(_ -> utf8Slice("_"), groups -> groups.isEmpty() ? utf8Slice("_") : groups.getFirst(), _ -> null);
    }

    private static List<Slice> values(Block block)
    {
        List<Slice> result = new ArrayList<>();
        for (int position = 0; position < block.getPositionCount(); position++) {
            result.add(block.isNull(position) ? null : VARCHAR.getSlice(block, position));
        }
        return result;
    }

    private static void equal(Object actual, Object expected, String operation)
    {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(operation + ": adapter=" + actual + ", pinned=" + expected);
        }
    }
}
