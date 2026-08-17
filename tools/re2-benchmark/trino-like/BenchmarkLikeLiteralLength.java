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
package io.airlift.regulator.benchmark;

import io.airlift.regulator.TrinoLikePattern;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.likematcher.LikeMatcher;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkLikeLiteralLength
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"EXACT", "PREFIX", "SUFFIX"})
        public String shape;

        @Param({"1", "2", "4", "6", "8", "12", "16", "24", "32", "64", "128", "512"})
        public int needleBytes;

        @Param({"MATCH", "FIRST", "MIDDLE", "LAST", "MIXED"})
        public String profile;

        @Param({"0"})
        public int byteOffset;

        @Param({"32768"})
        public int inputBytes;

        private TrinoLikePattern candidate;
        private LikeMatcher comparator;
        private final Slice[] slices = new Slice[16];
        private final byte[][] inputs = new byte[16][];
        private int cursor;
        private String fingerprint;
        private int inputLength;

        @Setup
        public void setup()
                throws Exception
        {
            byte[] literal = new byte[needleBytes];
            for (int index = 0; index < literal.length; index++) {
                literal[index] = (byte) ('a' + (index * 17 + 5) % 26);
            }
            if (needleBytes == 6) {
                literal = "needle".getBytes(US_ASCII);
            }
            String text = new String(literal, US_ASCII);
            String pattern = switch (shape) {
                case "EXACT" -> text;
                case "PREFIX" -> text + "%";
                case "SUFFIX" -> "%" + text;
                default -> throw new IllegalArgumentException(shape);
            };
            candidate = TrinoLikePattern.compile(Slices.utf8Slice(pattern));
            comparator = LikeMatcher.compile(pattern, Optional.empty(), false);
            LikeMatcher optimized = LikeMatcher.compile(pattern, Optional.empty(), true);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(pattern.getBytes(US_ASCII));
            inputLength = shape.equals("EXACT") ? needleBytes : Math.max(needleBytes, inputBytes);
            for (int index = 0; index < inputs.length; index++) {
                byte[] input = new byte[byteOffset + inputLength];
                Arrays.fill(input, (byte) ('A' + index));
                int start = byteOffset + (shape.equals("SUFFIX") ? inputLength - needleBytes : 0);
                System.arraycopy(literal, 0, input, start, needleBytes);
                String outcome = profile.equals("MIXED") ? switch ((index * 5 + 3) % 4) {
                    case 0 -> "MATCH";
                    case 1 -> "FIRST";
                    case 2 -> "MIDDLE";
                    default -> "LAST";
                } : profile;
                int mismatch = switch (outcome) {
                    case "MATCH" -> -1;
                    case "FIRST" -> 0;
                    case "MIDDLE" -> needleBytes / 2;
                    case "LAST" -> needleBytes - 1;
                    default -> throw new IllegalArgumentException(outcome);
                };
                if (mismatch >= 0) {
                    input[start + mismatch] = '!';
                }
                inputs[index] = input;
                slices[index] = Slices.wrappedBuffer(input, byteOffset, inputLength);
                boolean expected = mismatch < 0;
                if (candidate.matches(slices[index]) != expected || comparator.match(input, byteOffset, inputLength) != expected || optimized.match(input, byteOffset, inputLength) != expected) {
                    throw new IllegalStateException("Outcome mismatch: " + shape + "/" + needleBytes + "/" + profile + "/" + index);
                }
                digest.update(input);
            }
            fingerprint = HexFormat.of().formatHex(digest.digest());
        }
    }

    @Benchmark
    public boolean regulator(BenchmarkData data)
    {
        return data.candidate.matches(data.slices[data.cursor++ & 15]);
    }

    @Benchmark
    public boolean trino(BenchmarkData data)
    {
        return data.comparator.match(data.inputs[data.cursor++ & 15], data.byteOffset, data.inputLength);
    }

    public static void main(String[] args)
            throws Exception
    {
        int cases = 0;
        for (String shape : new String[] {"EXACT", "PREFIX", "SUFFIX"}) {
            for (int length : new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 24, 32, 64, 128, 512}) {
                for (String profile : new String[] {"MATCH", "FIRST", "MIDDLE", "LAST", "MIXED"}) {
                    BenchmarkData data = new BenchmarkData();
                    data.shape = shape;
                    data.needleBytes = length;
                    data.profile = profile;
                    for (int offset : new int[] {0, 3}) {
                        for (int inputBytes : new int[] {64, 32768}) {
                            data.byteOffset = offset;
                            data.inputBytes = inputBytes;
                            data.setup();
                            System.out.println(shape + "\t" + length + "\t" + profile + "\t" + offset + "\t" + inputBytes + "\t" + data.fingerprint);
                            cases++;
                        }
                    }
                }
            }
        }
        System.out.println("Verified " + cases + " cases, 16 inputs each, Regulator and both Trino settings");
    }
}
