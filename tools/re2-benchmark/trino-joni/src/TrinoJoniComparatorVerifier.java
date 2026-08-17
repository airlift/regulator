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

import io.airlift.regulator.TestingTrinoRegexpBenchmarkInputs;
import io.airlift.slice.Slice;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class TrinoJoniComparatorVerifier
{
    private static final String EXPECTED_RESULT_SHA256 = "0f8f6827646ed8d05437b5d2f5519260b8f027f503ae3f48ca61201635692faf";

    private TrinoJoniComparatorVerifier() {}

    public static void main(String[] args)
    {
        BenchmarkTrinoJoniComparator benchmark = new BenchmarkTrinoJoniComparator();
        MessageDigest digest = sha256();
        int comparisonCount = 0;
        for (String workload : TestingTrinoRegexpBenchmarkInputs.workloads()) {
            for (int sourceLength : TestingTrinoRegexpBenchmarkInputs.sourceLengths()) {
                BenchmarkTrinoJoniComparator.BenchmarkData data = new BenchmarkTrinoJoniComparator.BenchmarkData();
                data.workload = workload;
                data.sourceLength = sourceLength;
                data.setup();
                JoniSliceOperationsVerifier.verify(data.source, JoniRegexpCasts.joniRegexp(data.pattern));

                update(digest, workload);
                update(digest, sourceLength);
                update(digest, data.pattern);
                update(digest, data.source);

                boolean contains = benchmark.containsRegulator(data);
                assertEqual(contains, benchmark.containsJoni(data), workload, sourceLength, "contains");
                update(digest, contains);

                long count = benchmark.countRegulator(data);
                assertEqual(count, benchmark.countJoni(data), workload, sourceLength, "count");
                update(digest, count);

                long position = benchmark.positionThirdRegulator(data);
                assertEqual(position, benchmark.positionThirdJoni(data), workload, sourceLength, "positionThird");
                update(digest, position);

                Slice extract = benchmark.extractRegulator(data);
                assertEqual(extract, benchmark.extractJoni(data), workload, sourceLength, "extract");
                update(digest, extract);

                List<Slice> extractAll = benchmark.extractAllRegulator(data);
                assertEqual(extractAll, benchmark.extractAllJoni(data), workload, sourceLength, "extractAll");
                update(digest, extractAll);

                List<Slice> split = benchmark.splitRegulator(data);
                assertEqual(split, benchmark.splitJoni(data), workload, sourceLength, "split");
                update(digest, split);

                Slice replace = benchmark.replaceRegulator(data);
                assertEqual(replace, benchmark.replaceJoni(data), workload, sourceLength, "replace");
                update(digest, replace);

                Slice replaceLambda = benchmark.replaceLambdaRegulator(data);
                assertEqual(replaceLambda, benchmark.replaceLambdaJoni(data), workload, sourceLength, "replaceLambda");
                update(digest, replaceLambda);

                comparisonCount += 8;
            }
        }

        String resultSha256 = HexFormat.of().formatHex(digest.digest());
        if (!resultSha256.equals(EXPECTED_RESULT_SHA256)) {
            throw new AssertionError("Comparator result digest changed: expected %s, actual %s"
                    .formatted(EXPECTED_RESULT_SHA256, resultSha256));
        }
        System.out.println("Verified %s paired Trino/Joni operation results (SHA-256 %s)"
                .formatted(comparisonCount, resultSha256));
    }

    private static void assertEqual(Object regulatorValue, Object joniValue, String workload, int sourceLength, String operation)
    {
        if (!Objects.equals(regulatorValue, joniValue)) {
            throw failure(workload, sourceLength, operation, regulatorValue, joniValue);
        }
    }

    private static AssertionError failure(String workload, int sourceLength, String operation, Object regulatorValue, Object joniValue)
    {
        return new AssertionError("%s/%s/%s: Regulator=%s, Joni=%s"
                .formatted(workload, sourceLength, operation, regulatorValue, joniValue));
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
