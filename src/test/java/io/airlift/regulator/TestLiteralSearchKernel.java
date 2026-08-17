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

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

public class TestLiteralSearchKernel
{
    @Test
    public void testBoundedOffsetSearch()
    {
        Slice input = offsetSlice(new byte[] {'x', 'a', 'b', 'c', 'x', 'a', 'b', 'c', 'x'});
        LiteralSearchKernel kernel = kernel('a', 'b', 'c');

        assertThat(kernel.find(input, 0, input.length())).isEqualTo(1);
        assertThat(kernel.find(input, 2, input.length() - 2)).isEqualTo(5);
        assertThat(kernel.find(input, 0, 3)).isNegative();
        assertThat(kernel.find(input, 1, 3)).isEqualTo(1);
        assertThat(kernel.find(input, 2, 3)).isNegative();
        assertThat(kernel.find(input, 5, 3)).isEqualTo(5);
        assertThat(kernel.find(input, 6, 3)).isNegative();
        assertThat(kernel.find(input, input.length(), 0)).isNegative();
        assertThat(kernel.find(input, 0, 0)).isNegative();
    }

    @Test
    public void testSingleByteSearch()
    {
        byte[] bytes = new byte[41];
        Arrays.fill(bytes, (byte) 'x');
        bytes[0] = 'a';
        bytes[15] = 'a';
        bytes[16] = 'a';
        bytes[40] = 'a';
        Slice input = offsetSlice(bytes);
        LiteralSearchKernel kernel = kernel('a');

        assertThat(kernel.find(input, 0, input.length())).isZero();
        assertThat(kernel.find(input, 1, input.length() - 1)).isEqualTo(15);
        assertThat(kernel.find(input, 16, input.length() - 16)).isEqualTo(16);
        assertThat(kernel.find(input, 17, input.length() - 17)).isEqualTo(40);
        assertThat(kernel.find(input, 17, 23)).isNegative();
    }

    @Test
    public void testCandidateHeavySearch()
    {
        assertCandidateHeavySearch(new byte[] {'a', 'b', 'c', 'd'}, (byte) 'a');
        assertCandidateHeavySearch(new byte[] {'a', 'b', 'c', 'd'}, (byte) 'd');
        assertCandidateHeavySearch(new byte[] {'a', 'b', 'c', 'a'}, (byte) 'a');
        assertCandidateHeavySearch(new byte[] {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p'}, (byte) 'a');
    }

    @Test
    public void testMalformedUtf8IsSearchedAsBytes()
    {
        byte[] bytes = {(byte) 0xC0, (byte) 0xFF, 'x', (byte) 0xC0, (byte) 0xFF};
        Slice input = offsetSlice(bytes);
        LiteralSearchKernel kernel = new LiteralSearchKernel(Slices.wrappedBuffer(new byte[] {(byte) 0xC0, (byte) 0xFF}));

        assertThat(kernel.find(input, 0, input.length())).isZero();
        assertThat(kernel.find(input, 1, input.length() - 1)).isEqualTo(3);
    }

    @Test
    public void testRandomizedAgainstBoundedSliceSearch()
    {
        Random random = new Random(0xC0FFEE);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            byte[] bytes = new byte[random.nextInt(129)];
            random.nextBytes(bytes);
            Slice input = offsetSlice(bytes);

            int literalLength = 1 + random.nextInt(32);
            byte[] literalBytes = new byte[literalLength];
            random.nextBytes(literalBytes);
            if (bytes.length >= literalLength && random.nextBoolean()) {
                int sourceOffset = random.nextInt(bytes.length - literalLength + 1);
                System.arraycopy(bytes, sourceOffset, literalBytes, 0, literalLength);
            }
            Slice literal = Slices.wrappedBuffer(literalBytes);
            LiteralSearchKernel kernel = new LiteralSearchKernel(literal);

            int offset = random.nextInt(bytes.length + 1);
            int length = random.nextInt(bytes.length - offset + 1);
            int expected = input.slice(offset, length).indexOf(literal);
            if (expected >= 0) {
                expected += offset;
            }
            assertThat(kernel.find(input, offset, length))
                    .as("iteration %s, offset %s, length %s, literal length %s", iteration, offset, length, literalLength)
                    .isEqualTo(expected);
        }
    }

    private static void assertCandidateHeavySearch(byte[] literal, byte fill)
    {
        byte[] inputBytes = new byte[257];
        Arrays.fill(inputBytes, fill);
        System.arraycopy(literal, 0, inputBytes, inputBytes.length - literal.length, literal.length);
        Slice input = offsetSlice(inputBytes);

        assertThat(new LiteralSearchKernel(Slices.wrappedBuffer(literal)).find(input, 0, input.length()))
                .isEqualTo(input.length() - literal.length);
    }

    private static LiteralSearchKernel kernel(char... literal)
    {
        byte[] bytes = new byte[literal.length];
        for (int index = 0; index < literal.length; index++) {
            bytes[index] = (byte) literal[index];
        }
        return new LiteralSearchKernel(Slices.wrappedBuffer(bytes));
    }

    private static Slice offsetSlice(byte[] value)
    {
        byte[] bytes = new byte[value.length + 17];
        Arrays.fill(bytes, (byte) 0xFF);
        System.arraycopy(value, 0, bytes, 9, value.length);
        return Slices.wrappedBuffer(bytes).slice(9, value.length);
    }
}
