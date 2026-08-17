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

import io.airlift.regulator.BenchmarkEverydayTrinoRegexp;
import io.airlift.regulator.TestingEverydayTrinoRegexpBenchmarkInputs;
import io.airlift.slice.Slice;
import org.openjdk.jmh.annotations.Param;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class EverydayTrinoJoniVerifier
{
    private static final String EXPECTED_RESULT_SHA256 = "42269e1c0de0bde84b47ef4770b40b71831eac7fb811edf7ecadb9fef99de3bd";

    private EverydayTrinoJoniVerifier() {}

    public static void main(String[] args)
            throws ReflectiveOperationException
    {
        verifyWorkloadParameters(BenchmarkEverydayTrinoRegexp.BenchmarkData.class);
        verifyWorkloadParameters(BenchmarkEverydayTrinoJoni.BenchmarkData.class);
        BenchmarkEverydayTrinoRegexp regulator = new BenchmarkEverydayTrinoRegexp();
        BenchmarkEverydayTrinoJoni joni = new BenchmarkEverydayTrinoJoni();
        MessageDigest digest = sha256();
        int comparisonCount = 0;

        for (String workloadId : TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds()) {
            TestingEverydayTrinoRegexpBenchmarkInputs.Input input =
                    TestingEverydayTrinoRegexpBenchmarkInputs.create(workloadId);
            BenchmarkEverydayTrinoRegexp.BenchmarkData regulatorData =
                    new BenchmarkEverydayTrinoRegexp.BenchmarkData();
            regulatorData.setWorkload(workloadId);
            regulatorData.setup();
            BenchmarkEverydayTrinoJoni.BenchmarkData joniData = new BenchmarkEverydayTrinoJoni.BenchmarkData();
            joniData.setWorkload(workloadId);
            joniData.setup();

            update(digest, workloadId);
            update(digest, input.family());
            update(digest, input.origin());
            update(digest, input.pattern());

            for (int sourceIndex = 0; sourceIndex < input.sources().size(); sourceIndex++) {
                TestingEverydayTrinoRegexpBenchmarkInputs.Source source = input.sources().get(sourceIndex);
                update(digest, source.sourceId());
                update(digest, source.value());
                update(digest, source.sliceOffset());
                update(digest, source.expectedMatchCount());
                JoniSliceOperationsVerifier.verify(source.value(), JoniRegexpCasts.joniRegexp(input.pattern()));

                regulatorData.setSourceIndex(sourceIndex);
                joniData.setSourceIndex(sourceIndex);
                boolean contains = regulator.contains(regulatorData);
                assertEqual(contains, joni.contains(joniData), workloadId, source.sourceId(), "contains");
                update(digest, contains);

                regulatorData.setSourceIndex(sourceIndex);
                joniData.setSourceIndex(sourceIndex);
                long count = regulator.count(regulatorData);
                assertEqual(count, joni.count(joniData), workloadId, source.sourceId(), "count");
                update(digest, count);

                regulatorData.setSourceIndex(sourceIndex);
                joniData.setSourceIndex(sourceIndex);
                long position = regulator.positionThird(regulatorData);
                assertEqual(position, joni.positionThird(joniData), workloadId, source.sourceId(), "positionThird");
                update(digest, position);

                regulatorData.setSourceIndex(sourceIndex);
                joniData.setSourceIndex(sourceIndex);
                Slice extract = regulator.extract(regulatorData);
                assertEqual(extract, joni.extract(joniData), workloadId, source.sourceId(), "extract");
                update(digest, extract);

                regulatorData.setSourceIndex(sourceIndex);
                joniData.setSourceIndex(sourceIndex);
                List<Slice> extractAll = regulator.extractAll(regulatorData);
                assertEqual(extractAll, joni.extractAll(joniData), workloadId, source.sourceId(), "extractAll");
                update(digest, extractAll);

                regulatorData.setSourceIndex(sourceIndex);
                joniData.setSourceIndex(sourceIndex);
                List<Slice> split = regulator.split(regulatorData);
                assertEqual(split, joni.split(joniData), workloadId, source.sourceId(), "split");
                update(digest, split);

                regulatorData.setSourceIndex(sourceIndex);
                joniData.setSourceIndex(sourceIndex);
                Slice replace = regulator.replace(regulatorData);
                assertEqual(replace, joni.replace(joniData), workloadId, source.sourceId(), "replace");
                update(digest, replace);

                regulatorData.setSourceIndex(sourceIndex);
                joniData.setSourceIndex(sourceIndex);
                Slice replaceLambda = regulator.replaceLambda(regulatorData);
                assertEqual(replaceLambda, joni.replaceLambda(joniData), workloadId, source.sourceId(), "replaceLambda");
                update(digest, replaceLambda);

                comparisonCount += 8;
            }
        }

        String resultSha256 = HexFormat.of().formatHex(digest.digest());
        if (!resultSha256.equals(EXPECTED_RESULT_SHA256)) {
            throw new AssertionError("Everyday comparator result digest changed: expected %s, actual %s"
                    .formatted(EXPECTED_RESULT_SHA256, resultSha256));
        }
        System.out.println("Verified %s everyday Regulator/Joni operation results (SHA-256 %s)"
                .formatted(comparisonCount, resultSha256));
    }

    private static void verifyWorkloadParameters(Class<?> benchmarkDataClass)
            throws ReflectiveOperationException
    {
        Field workload = benchmarkDataClass.getDeclaredField("workload");
        List<String> actual = Arrays.asList(workload.getAnnotation(Param.class).value());
        List<String> expected = TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds();
        if (!actual.equals(expected)) {
            throw new AssertionError("JMH workload parameters differ for %s: expected %s, actual %s"
                    .formatted(benchmarkDataClass.getName(), expected, actual));
        }
    }

    private static void assertEqual(Object regulatorValue, Object joniValue, String workloadId, String sourceId, String operation)
    {
        if (!Objects.equals(regulatorValue, joniValue)) {
            throw failure(workloadId, sourceId, operation, regulatorValue, joniValue);
        }
    }

    private static AssertionError failure(
            String workloadId,
            String sourceId,
            String operation,
            Object regulatorValue,
            Object joniValue)
    {
        return new AssertionError("%s/%s/%s: Regulator=%s, Joni=%s"
                .formatted(workloadId, sourceId, operation, regulatorValue, joniValue));
    }

    private static MessageDigest sha256()
    {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void update(MessageDigest digest, boolean value)
    {
        digest.update(value ? (byte) 1 : (byte) 0);
    }

    private static void update(MessageDigest digest, int value)
    {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void update(MessageDigest digest, long value)
    {
        for (int shift = Long.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
            digest.update((byte) (value >>> shift));
        }
    }

    private static void update(MessageDigest digest, String value)
    {
        byte[] bytes = value.getBytes(UTF_8);
        update(digest, bytes.length);
        digest.update(bytes);
    }

    private static void update(MessageDigest digest, Slice value)
    {
        if (value == null) {
            update(digest, -1);
            return;
        }
        update(digest, value.length());
        digest.update(value.byteArray(), value.byteArrayOffset(), value.length());
    }

    private static void update(MessageDigest digest, List<Slice> values)
    {
        update(digest, values.size());
        values.forEach(value -> update(digest, value));
    }
}
