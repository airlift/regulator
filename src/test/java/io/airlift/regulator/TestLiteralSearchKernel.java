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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

public class TestLiteralSearchKernel
{
    @Test
    public void testBoundedOffsetSearch()
    {
        Slice input = offsetSlice(new byte[] {'x', 'a', 'b', 'c', 'x', 'a', 'b', 'c', 'x'});
        LiteralSearchKernel kernel = kernel('a', 'b', 'c');

        assertSearch(kernel, input, 0, input.length(), -1, 1);
        assertSearch(kernel, input, 2, input.length() - 2, -1, 5);
        assertSearch(kernel, input, 0, 3, -1, -1);
        assertSearch(kernel, input, 1, 3, -1, 1);
        assertSearch(kernel, input, 2, 3, -1, -1);
        assertSearch(kernel, input, 5, 3, -1, 5);
        assertSearch(kernel, input, 6, 3, -1, -1);
        assertSearch(kernel, input, input.length(), 0, -1, -1);
        assertSearch(kernel, input, 0, 0, -1, -1);
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

        assertThat(kernel.findSingleByte(input, 0, input.length())).isZero();
        assertThat(kernel.findSingleByte(input, 1, input.length() - 1)).isEqualTo(15);
        assertThat(kernel.findSingleByte(input, 16, input.length() - 16)).isEqualTo(16);
        assertThat(kernel.findSingleByte(input, 17, input.length() - 17)).isEqualTo(40);
        assertThat(kernel.findSingleByte(input, 17, 23)).isNegative();
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
    public void testCandidateWorkBound()
    {
        // Dense candidates that fail on their second byte stay in the word scan
        String dense = "aabx".repeat(256);
        Slice denseInput = offsetSlice((dense + "bba").getBytes(US_ASCII));
        LiteralSearchKernel denseKernel = kernel('b', 'b', 'a');
        assertSearch(denseKernel, denseInput, 0, denseInput.length(), -1, dense.length());
        assertSearch(denseKernel, denseInput, 0, dense.length(), -1, -1);

        // Long partial matches switch to KMP early, counted from where the search starts: each
        // candidate compares eight bytes and covers one, so the tenth passes the budget
        String periodic = "a".repeat(1024);
        String periodicLiteral = "aaaaaaaba";
        Slice periodicInput = offsetSlice((periodic + periodicLiteral).getBytes(US_ASCII));
        LiteralSearchKernel periodicKernel = kernel(periodicLiteral.toCharArray());
        assertSearch(periodicKernel, periodicInput, 0, periodicInput.length(), 10, periodic.length());
        assertSearch(periodicKernel, periodicInput, 512, periodicInput.length() - 512, 522, periodic.length());

        // Text without candidates counts as covered, including the text the contains search skips
        // before it verifies its first candidate, so the 83rd candidate passes the budget
        String skipped = "x".repeat(512);
        Slice skippedInput = offsetSlice((skipped + periodic + periodicLiteral).getBytes(US_ASCII));
        assertSearch(periodicKernel, skippedInput, 0, skippedInput.length(), 595, skipped.length() + periodic.length());

        // A literal under 64 bytes whose first byte does not recur continues with KMP over the
        // border-free shift table: each candidate compares four bytes and covers three, so the
        // 62nd passes the budget
        String failedCandidates = "abb".repeat(63);
        Slice borderFreeInput = offsetSlice((failedCandidates + "abbcb").getBytes(US_ASCII));
        LiteralSearchKernel borderFreeKernel = kernel('a', 'b', 'b', 'c', 'b');
        assertSearch(borderFreeKernel, borderFreeInput, 0, borderFreeInput.length(), 184, failedCandidates.length());

        // Four possible starts, fewer than a word, exercise only the scan's one-start-at-a-time tail
        String tailLiteral = "a".repeat(38) + "ba";
        Slice tailInput = offsetSlice(("aaa" + tailLiteral).getBytes(US_ASCII));
        LiteralSearchKernel tailKernel = kernel(tailLiteral.toCharArray());
        assertSearch(tailKernel, tailInput, 0, tailInput.length(), 2, 3);
    }

    @Test
    public void testCandidatePrefixLength()
    {
        // A literal of "a"s with a "b" at the mismatch index, compared against "a"s, matches exactly
        // the bytes before that index; `mismatch == length` means no mismatch. Each array ends with
        // the compared bytes and has no padding.
        // The kernel's verification is observed through its KMP switch: after 512 covered bytes,
        // every candidate compares one byte past its prefix and covers one, so the switch point
        // differs for every prefix length. Only a mismatch inside the literal leaves a candidate's
        // first and last bytes matching, and only one at the end yields a match.
        int covered = 512;
        for (int length = 1; length <= 24; length++) {
            for (int mismatch = 0; mismatch <= length; mismatch++) {
                byte[] literal = new byte[length];
                Arrays.fill(literal, (byte) 'a');
                if (mismatch < length) {
                    literal[mismatch] = 'b';
                }
                byte[] candidateBytes = ("x" + "a".repeat(length)).getBytes(US_ASCII);
                assertThat(TrinoLikeKernelSupport.matchingPrefixLength(candidateBytes, 1, literal))
                        .as("length %s, mismatch %s", length, mismatch)
                        .isEqualTo(mismatch);

                LiteralSearchKernel kernel = new LiteralSearchKernel(Slices.wrappedBuffer(literal));
                if (mismatch == length && length >= 2) {
                    Slice input = Slices.wrappedBuffer(("x".repeat(covered) + "a".repeat(length)).getBytes(US_ASCII));
                    assertSearch(kernel, input, 0, input.length(), -1, covered);
                }
                else if (mismatch > 0 && mismatch < length - 1) {
                    Slice input = Slices.wrappedBuffer(("x".repeat(covered) + "a".repeat(1024)).getBytes(US_ASCII));
                    assertSearch(kernel, input, 0, input.length(), kmpHandoff(covered, mismatch), -1);
                }
            }
        }
    }

    @Test
    public void testMalformedUtf8IsSearchedAsBytes()
    {
        byte[] bytes = {(byte) 0xC0, (byte) 0xFF, 'x', (byte) 0xC0, (byte) 0xFF};
        Slice input = offsetSlice(bytes);
        LiteralSearchKernel kernel = new LiteralSearchKernel(Slices.wrappedBuffer(new byte[] {(byte) 0xC0, (byte) 0xFF}));

        assertThat(kernel.findContains(input, 0, input.length())).isZero();
        assertThat(kernel.findContains(input, 1, input.length() - 1)).isEqualTo(3);
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
            int actual = literalLength == 1 ? kernel.findSingleByte(input, offset, length) : kernel.findContains(input, offset, length);
            assertThat(actual)
                    .as("iteration %s, offset %s, length %s, literal length %s", iteration, offset, length, literalLength)
                    .isEqualTo(expected);
        }
    }

    /// Requires [LiteralSearchKernel#findContains] to switch to KMP at `kmpHandoff`, or
    /// not at all when it is -1, and to find the literal at `match`, or nowhere when it is -1.
    private static void assertSearch(LiteralSearchKernel kernel, Slice input, int offset, int length, int kmpHandoff, int match)
    {
        assertThat(kernel.kmpHandoffOffsetForDiagnostics(input, offset, length)).isEqualTo(kmpHandoff);
        assertThat(kernel.findContains(input, offset, length)).isEqualTo(match);
    }

    /// Returns where a contains search switches to KMP when every candidate, one per byte from
    /// `covered`, matches `prefixLength` bytes and then fails.
    private static int kmpHandoff(int covered, int prefixLength)
    {
        int comparisonEnd = -LiteralSearchKernel.CANDIDATE_COMPARISON_BUDGET;
        for (int candidate = covered; ; candidate++) {
            comparisonEnd += prefixLength + 1;
            if (comparisonEnd > candidate) {
                return candidate + 1;
            }
        }
    }

    private static void assertCandidateHeavySearch(byte[] literal, byte fill)
    {
        byte[] inputBytes = new byte[257];
        Arrays.fill(inputBytes, fill);
        System.arraycopy(literal, 0, inputBytes, inputBytes.length - literal.length, literal.length);
        Slice input = offsetSlice(inputBytes);

        assertThat(new LiteralSearchKernel(Slices.wrappedBuffer(literal)).findContains(input, 0, input.length()))
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
