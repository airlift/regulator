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

import io.airlift.regulator.BenchmarkSpecializedTrinoRegexp;
import io.airlift.regulator.TestingEverydayTrinoRegexpBenchmarkInputs.Source;
import io.airlift.regulator.TestingSpecializedPatternBenchmarkInputs;
import io.airlift.regulator.TestingSpecializedPatternBenchmarkInputs.Operation;
import io.airlift.regulator.TestingSpecializedPatternBenchmarkInputs.Workload;
import io.airlift.regulator.TestingSpecializedPatternBenchmarks.Variant;
import io.airlift.slice.Slice;
import io.trino.type.JoniRegexp;
import org.openjdk.jmh.annotations.Param;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

import static java.nio.charset.StandardCharsets.UTF_8;

/// Checks that the specialized engine, the regular engine, and the compiled variant of every
/// specialized-pattern workload each return pinned Joni's result for every input.
public final class SpecializedTrinoJoniVerifier
{
    private static final String EXPECTED_RESULT_SHA256 = "86cb18b04c07ddea5e2d4bf7152c585e9bac81d3bd9a60c940ec1143eb7ad1a3";

    private SpecializedTrinoJoniVerifier() {}

    public static void main(String[] args)
            throws ReflectiveOperationException
    {
        verifyWorkloadParameters(Operation.REPLACE, BenchmarkSpecializedTrinoRegexp.ReplaceData.class, BenchmarkSpecializedTrinoJoni.ReplaceData.class);
        verifyWorkloadParameters(Operation.EXTRACT, BenchmarkSpecializedTrinoRegexp.ExtractData.class, BenchmarkSpecializedTrinoJoni.ExtractData.class);
        verifyWorkloadParameters(Operation.EXTRACT_ALL, BenchmarkSpecializedTrinoRegexp.ExtractAllData.class, BenchmarkSpecializedTrinoJoni.ExtractAllData.class);
        BenchmarkSpecializedTrinoJoni joni = new BenchmarkSpecializedTrinoJoni();
        MessageDigest digest = sha256();
        int comparisonCount = 0;

        for (String workloadId : TestingSpecializedPatternBenchmarkInputs.workloadIds()) {
            Workload workload = TestingSpecializedPatternBenchmarkInputs.create(workloadId);
            List<Variant> variants = BenchmarkSpecializedTrinoRegexp.variantsForTesting(workloadId);
            IntFunction<Object> expected = switch (workload.operation()) {
                case REPLACE -> {
                    BenchmarkSpecializedTrinoJoni.ReplaceData joniData = new BenchmarkSpecializedTrinoJoni.ReplaceData();
                    joniData.setWorkload(workloadId);
                    joniData.setup();
                    yield index -> {
                        joniData.setSourceIndex(index);
                        return joni.replace(joniData);
                    };
                }
                case EXTRACT -> {
                    BenchmarkSpecializedTrinoJoni.ExtractData joniData = new BenchmarkSpecializedTrinoJoni.ExtractData();
                    joniData.setWorkload(workloadId);
                    joniData.setup();
                    yield index -> {
                        joniData.setSourceIndex(index);
                        return joni.extract(joniData);
                    };
                }
                case EXTRACT_ALL -> {
                    BenchmarkSpecializedTrinoJoni.ExtractAllData joniData = new BenchmarkSpecializedTrinoJoni.ExtractAllData();
                    joniData.setWorkload(workloadId);
                    joniData.setup();
                    yield index -> {
                        joniData.setSourceIndex(index);
                        return joni.extractAll(joniData);
                    };
                }
            };
            JoniRegexp joniRegexp = JoniRegexpCasts.joniRegexp(workload.pattern());

            update(digest, workloadId);
            update(digest, workload.pattern());
            update(digest, workload.operation().benchmarkName());
            update(digest, workload.group());
            update(digest, workload.replacement());
            for (int sourceIndex = 0; sourceIndex < workload.sources().size(); sourceIndex++) {
                Source source = workload.sources().get(sourceIndex);
                update(digest, source.sourceId());
                update(digest, source.value());
                update(digest, source.sliceOffset());
                update(digest, source.expectedMatchCount());
                JoniSliceOperationsVerifier.verify(source.value(), joniRegexp);
                Object joniResult = expected.apply(sourceIndex);
                for (Variant variant : variants) {
                    Object regulatorResult = variant.invoke(sourceIndex);
                    if (!Objects.equals(regulatorResult, joniResult)) {
                        throw new AssertionError("%s/%s/%s: Regulator=%s, Joni=%s"
                                .formatted(workloadId, source.sourceId(), variant.name(), regulatorResult, joniResult));
                    }
                    comparisonCount++;
                }
                update(digest, joniResult);
            }
        }

        String resultSha256 = HexFormat.of().formatHex(digest.digest());
        if (!resultSha256.equals(EXPECTED_RESULT_SHA256)) {
            throw new AssertionError("Specialized comparator result digest changed: expected %s, actual %s"
                    .formatted(EXPECTED_RESULT_SHA256, resultSha256));
        }
        System.out.println("Verified %s specialized Regulator/Joni operation results (SHA-256 %s)"
                .formatted(comparisonCount, resultSha256));
    }

    private static void verifyWorkloadParameters(Operation operation, Class<?>... states)
            throws ReflectiveOperationException
    {
        List<String> expected = TestingSpecializedPatternBenchmarkInputs.workloadIds(operation);
        for (Class<?> state : states) {
            List<String> actual = parameters(state);
            if (!actual.equals(expected)) {
                throw new AssertionError("JMH workload parameters differ for %s: expected %s, actual %s"
                        .formatted(state.getName(), expected, actual));
            }
        }
    }

    private static List<String> parameters(Class<?> state)
            throws ReflectiveOperationException
    {
        return List.of(state.getDeclaredField("workload").getAnnotation(Param.class).value());
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

    private static void update(MessageDigest digest, Object value)
    {
        switch (value) {
            case null -> update(digest, -1);
            case Slice slice -> {
                update(digest, slice.length());
                digest.update(slice.byteArray(), slice.byteArrayOffset(), slice.length());
            }
            case List<?> values -> {
                update(digest, values.size());
                values.forEach(item -> update(digest, item));
            }
            default -> throw new AssertionError("unexpected result type: " + value.getClass());
        }
    }
}
