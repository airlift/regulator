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
import java.util.List;
import java.util.Random;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

public class TestLiteralSearchKernel
{
    // A block of findLast's vector scan at the widest supported width: four vectors of 64 bytes.
    private static final int WIDEST_BLOCK_BYTES = 4 * 64;

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

    /// Ranges of every length up to 40 leave every remainder after the word loops, so the scans
    /// finish with every overlapping final word and every one-at-a-time tail, and each range holds
    /// the literal once at each position in turn, or nowhere. Each range is searched as an
    /// exact-length array, where a read outside it throws, and as a view whose neighbors are copies
    /// of the literal, where a read outside it can find a match the range does not hold.
    @Test
    public void testShortRangeTails()
    {
        for (String literalText : List.of("a", "ab", "abc", "abcdefghij")) {
            byte[] literal = literalText.getBytes(US_ASCII);
            LiteralSearchKernel kernel = new LiteralSearchKernel(Slices.wrappedBuffer(literal));
            for (int length = 0; length <= 40; length++) {
                for (int match = -1; match <= Math.max(-1, length - literal.length); match++) {
                    byte[] text = new byte[length];
                    Arrays.fill(text, (byte) 'x');
                    if (match >= 0) {
                        System.arraycopy(literal, 0, text, match, literal.length);
                    }
                    for (Slice input : List.of(Slices.wrappedBuffer(text), view(text, literal))) {
                        String description = "literal %s, length %s, match %s, array offset %s".formatted(literalText, length, match, input.byteArrayOffset());
                        if (literal.length == 1) {
                            assertThat(kernel.findSingleByte(input, 0, length)).as(description).isEqualTo(match);
                        }
                        else {
                            assertThat(kernel.findContains(input, 0, length)).as(description).isEqualTo(match);
                            assertThat(kernel.findContains(input.byteArray(), input.byteArrayOffset(), length))
                                    .as(description)
                                    .isEqualTo(match < 0 ? -1 : input.byteArrayOffset() + match);
                        }
                    }
                }
            }
        }

        // In "-------a`bab" the final word flags a false candidate at 8, a borrow above the partial
        // match at 7, before the match at 10; verification rejects it and goes on.
        byte[] falseCandidate = "-------a`bab".getBytes(US_ASCII);
        for (Slice input : List.of(Slices.wrappedBuffer(falseCandidate), offsetSlice(falseCandidate))) {
            assertSearch(kernel('a', 'b'), input, 0, input.length(), -1, 10);
        }
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

    /// The last-occurrence search over ranges of every length up to 40, so it finishes with every
    /// overlapping first word and every one-at-a-time tail of the word scan, and, where vectors are
    /// available, up to five whole vectors of candidates and one candidate past them, so the vector
    /// scan searches one to five vectors, a block of four and the vector below it, and the scalar
    /// tail. The literal sits at each position in turn, so in each vector of a block, and again at
    /// the range start where it does not overlap the match, so only the later one is the last, as
    /// an exact-length array and as a view whose neighbors are copies of the literal. Like every
    /// last-occurrence search, it takes literals of at least two bytes.
    @Test
    public void testLastOccurrenceShortRangeTails()
    {
        for (String literalText : List.of("ab", "abc", "abcdefghij")) {
            byte[] literal = literalText.getBytes(US_ASCII);
            LiteralSearchKernel kernel = lastOccurrenceKernel(literal);
            int maximumLength = VectorSupport.isAvailable() ? Math.max(40, literal.length + 5 * VectorSupport.preferredByteLanes()) : 40;
            for (int length = 0; length <= maximumLength; length++) {
                for (int match = -1; match <= Math.max(-1, length - literal.length); match++) {
                    byte[] text = new byte[length];
                    Arrays.fill(text, (byte) 'x');
                    if (match >= literal.length) {
                        System.arraycopy(literal, 0, text, 0, literal.length);
                    }
                    if (match >= 0) {
                        System.arraycopy(literal, 0, text, match, literal.length);
                    }
                    for (Slice input : List.of(Slices.wrappedBuffer(text), view(text, literal))) {
                        String description = "literal %s, length %s, match %s, array offset %s".formatted(literalText, length, match, input.byteArrayOffset());
                        assertLastSearch(kernel, literal, input, 0, length, match, description);
                    }
                }
            }
        }

        // Without its last two bytes, "aba`bxxxxxxxab" leaves the word at the range start, which
        // flags a false candidate at 3, a borrow above the partial match at 2, ahead of the match
        // at 0; verification rejects it and goes on down.
        byte[] falseCandidate = "aba`bxxxxxxxab".getBytes(US_ASCII);
        byte[] literal = {'a', 'b'};
        LiteralSearchKernel kernel = lastOccurrenceKernel(literal);
        for (Slice input : List.of(Slices.wrappedBuffer(falseCandidate), offsetSlice(falseCandidate))) {
            assertLastSearch(kernel, literal, input, 0, input.length(), 12, "false candidate");
            assertLastSearch(kernel, literal, input, 0, input.length() - 2, 0, "false candidate below the end");
        }
    }

    @Test
    public void testLastOccurrenceCandidateWorkBound()
    {
        // Long partial matches switch to the reverse KMP early, counted down from the last
        // candidate: each candidate compares eight bytes and covers one, so the tenth passes the
        // budget, and the search goes on over the bytes below it
        String periodic = "a".repeat(1024);
        String literal = "aaaaaaaba";
        byte[] literalBytes = literal.getBytes(US_ASCII);
        LiteralSearchKernel kernel = lastOccurrenceKernel(literalBytes);
        Slice periodicInput = offsetSlice((literal + periodic).getBytes(US_ASCII));
        int lastCandidate = periodicInput.length() - literal.length();
        assertLastKmpHandoff(kernel, literalBytes, periodicInput, 0, periodicInput.length(), lastKmpHandoff(lastCandidate, lastCandidate, 7, literal.length()), 0);
        assertLastKmpHandoff(kernel, literalBytes, periodicInput, 0, periodicInput.length() - 512, lastKmpHandoff(lastCandidate - 512, lastCandidate - 512, 7, literal.length()), 0);

        // Text without candidates counts as covered, including the text the scan skips before it
        // verifies its first candidate. Growing that text a byte at a time across a block of four
        // of the widest vectors moves the candidate that passes the budget through each vector
        // of a block of the vector scan.
        for (int skipped = 512; skipped <= 512 + WIDEST_BLOCK_BYTES; skipped++) {
            Slice skippedInput = offsetSlice((literal + periodic + "x".repeat(skipped)).getBytes(US_ASCII));
            assertLastKmpHandoff(kernel, literalBytes, skippedInput, 0, skippedInput.length(), lastKmpHandoff(skippedInput.length() - literal.length(), lastCandidate, 7, literal.length()), 0);
        }

        // A literal whose last byte recurs keeps its own reversed shift table, and the reverse KMP
        // finds an occurrence that overlaps the partial matches above it
        String recurring = "abaabaab";
        byte[] recurringBytes = recurring.getBytes(US_ASCII);
        LiteralSearchKernel recurringKernel = lastOccurrenceKernel(recurringBytes);
        Slice recurringInput = offsetSlice(("x" + recurring + "ab".repeat(600)).getBytes(US_ASCII));
        assertThat(recurringKernel.lastKmpHandoffEndForDiagnostics(recurringInput, 0, recurringInput.length())).isPositive();
        assertLastSearch(recurringKernel, recurringBytes, recurringInput, 0, recurringInput.length(), 1, "recurring last byte");

        // The retained size counts that table: `baa` reversed starts with a recurring byte, so it
        // has its own, while `abc` shares the border-free table, which counts nothing.
        Slice ownTable = Slices.utf8Slice("baa");
        assertThat(LiteralSearchKernel.sharingWithLastOccurrence(ownTable).estimatedRetainedSize()).isGreaterThan(LiteralSearchKernel.sharing(ownTable).estimatedRetainedSize());
        Slice sharedTable = Slices.utf8Slice("abc");
        assertThat(LiteralSearchKernel.sharingWithLastOccurrence(sharedTable).estimatedRetainedSize()).isEqualTo(LiteralSearchKernel.sharing(sharedTable).estimatedRetainedSize());
    }

    /// Random texts over small alphabets, so candidates and partial matches are dense, and
    /// periodic ones, searched in random ranges of Slices with nonzero array offsets, against a
    /// naive last index: the word scan, the vector scan with its reverse KMP finish, and the
    /// dispatch of [Prog#findLastLiteral], which takes the vector scan from 1 KiB.
    @Test
    public void testLastOccurrenceRandomizedAgainstNaiveSearch()
    {
        Random random = new Random(0x5EED);
        for (int iteration = 0; iteration < 4_000; iteration++) {
            int alphabet = 1 + random.nextInt(3);
            // The dispatch, like every last-occurrence search, takes literals of at least two
            // bytes.
            int literalLength = switch (random.nextInt(3)) {
                case 0 -> 2;
                case 1 -> 64;
                default -> 2 + random.nextInt(15);
            };
            byte[] literal = new byte[literalLength];
            for (int index = 0; index < literalLength; index++) {
                literal[index] = (byte) ('a' + random.nextInt(alphabet + 1));
            }
            byte[] bytes = new byte[random.nextInt(iteration % 4 == 0 ? 3000 : 200)];
            if (random.nextBoolean()) {
                for (int index = 0; index < bytes.length; index++) {
                    bytes[index] = (byte) ('a' + random.nextInt(alphabet));
                }
            }
            else {
                // A periodic text of the literal with one byte changed per period.
                for (int index = 0; index < bytes.length; index++) {
                    bytes[index] = index % literalLength == literalLength - 1 ? (byte) 'z' : literal[index % literalLength];
                }
            }
            for (int copies = random.nextInt(3); copies > 0 && bytes.length >= literalLength; copies--) {
                System.arraycopy(literal, 0, bytes, random.nextInt(bytes.length - literalLength + 1), literalLength);
            }
            Slice input = offsetSlice(bytes);
            int offset = random.nextInt(bytes.length + 1);
            int length = random.nextInt(bytes.length - offset + 1);
            int expected = naiveLastIndex(bytes, offset, length, literal);
            assertLastSearch(lastOccurrenceKernel(literal), literal, input, offset, length, expected, "iteration %s, offset %s, length %s, literal %s".formatted(iteration, offset, length, new String(literal, US_ASCII)));
        }
    }

    /// Requires the last-occurrence searches to find the last occurrence of `literal` wholly in
    /// the range at `match`, Slice-relative, or nowhere when it is -1: the word scan of
    /// [LiteralSearchKernel#findLastContains], the vector scan where vectors are available, each
    /// finished with the reverse KMP where it hands off, and [Prog#findLastLiteral].
    private static void assertLastSearch(LiteralSearchKernel kernel, byte[] literal, Slice input, int offset, int length, int match, String description)
    {
        int arrayOffset = input.byteArrayOffset();
        int expected = match < 0 ? -1 : arrayOffset + match;
        byte[] data = input.byteArray();
        assertThat(kernel.findLastContains(data, arrayOffset + offset, length)).as(description).isEqualTo(expected);
        assertThat(Prog.findLastLiteral(data, arrayOffset + offset, length, literal, kernel)).as(description).isEqualTo(expected);
        assertThat(kernel.findLastKmp(data, arrayOffset + offset, arrayOffset + offset + length)).as(description).isEqualTo(expected);
        if (VectorSupport.isAvailable()) {
            int found = VectorPrefixScanner.findLast(data, arrayOffset + offset, length, literal, Prog.pairOffset(literal), literal.length - 1);
            if (found < -1) {
                found = kernel.findLastKmp(data, arrayOffset + offset, -2 - found);
            }
            assertThat(found).as(description + " vector").isEqualTo(expected);
        }
    }

    /// Requires the word scan and, where vectors are available, the vector scan of
    /// [LiteralSearchKernel#findLastContains] and [Prog#findLastLiteral] to hand off to the reverse
    /// KMP at the Slice-relative `resumeEnd`, and the search to find the literal at `match`.
    private static void assertLastKmpHandoff(LiteralSearchKernel kernel, byte[] literal, Slice input, int offset, int length, int resumeEnd, int match)
    {
        assertThat(kernel.lastKmpHandoffEndForDiagnostics(input, offset, length)).isEqualTo(resumeEnd);
        if (VectorSupport.isAvailable()) {
            int arrayOffset = input.byteArrayOffset();
            int found = VectorPrefixScanner.findLast(input.byteArray(), arrayOffset + offset, length, literal, Prog.pairOffset(literal), literal.length - 1);
            assertThat(-2 - found - arrayOffset).as("vector handoff").isEqualTo(resumeEnd);
        }
        assertLastSearch(kernel, literal, input, offset, length, match, "handoff at " + resumeEnd);
    }

    /// Returns where a last-occurrence search whose last candidate is at `lastCandidate` hands off
    /// to the reverse KMP when every candidate, one per byte down from `firstCandidate`, matches
    /// `prefixLength` bytes and then fails: the end of the range that holds every occurrence below
    /// the failed candidate.
    private static int lastKmpHandoff(int lastCandidate, int firstCandidate, int prefixLength, int literalLength)
    {
        int comparisonEnd = -lastCandidate - LiteralSearchKernel.CANDIDATE_COMPARISON_BUDGET;
        for (int candidate = firstCandidate; ; candidate--) {
            comparisonEnd += prefixLength + 1;
            if (comparisonEnd > -candidate) {
                return candidate + literalLength - 1;
            }
        }
    }

    private static int naiveLastIndex(byte[] bytes, int offset, int length, byte[] literal)
    {
        for (int candidate = offset + length - literal.length; candidate >= offset; candidate--) {
            if (Arrays.equals(bytes, candidate, candidate + literal.length, literal, 0, literal.length)) {
                return candidate;
            }
        }
        return -1;
    }

    private static LiteralSearchKernel lastOccurrenceKernel(byte[] literal)
    {
        return LiteralSearchKernel.sharingWithLastOccurrence(Slices.wrappedBuffer(literal.clone()));
    }

    /// Requires [LiteralSearchKernel#findContains(Slice,int,int)] to switch to KMP at `kmpHandoff`,
    /// or not at all when it is -1, and both overloads to find the literal at `match`, or nowhere
    /// when it is -1. The byte-array overload takes and returns absolute array indexes.
    private static void assertSearch(LiteralSearchKernel kernel, Slice input, int offset, int length, int kmpHandoff, int match)
    {
        assertThat(kernel.kmpHandoffOffsetForDiagnostics(input, offset, length)).isEqualTo(kmpHandoff);
        assertThat(kernel.findContains(input, offset, length)).isEqualTo(match);
        int arrayOffset = input.byteArrayOffset();
        assertThat(kernel.findContains(input.byteArray(), arrayOffset + offset, length)).isEqualTo(match < 0 ? -1 : arrayOffset + match);
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

    /// Returns a view of `text` at a non-zero offset of a larger array whose neighbors are
    /// copies of `neighbor`: the bytes before the view end with it and the bytes after begin with it.
    private static Slice view(byte[] text, byte[] neighbor)
    {
        int padding = 2 * Long.BYTES;
        byte[] bytes = new byte[padding + text.length + padding];
        for (int index = 0; index < padding; index++) {
            bytes[padding - 1 - index] = neighbor[neighbor.length - 1 - index % neighbor.length];
            bytes[padding + text.length + index] = neighbor[index % neighbor.length];
        }
        System.arraycopy(text, 0, bytes, padding, text.length);
        return Slices.wrappedBuffer(bytes).slice(padding, text.length);
    }

    private static Slice offsetSlice(byte[] value)
    {
        byte[] bytes = new byte[value.length + 17];
        Arrays.fill(bytes, (byte) 0xFF);
        System.arraycopy(value, 0, bytes, 9, value.length);
        return Slices.wrappedBuffer(bytes).slice(9, value.length);
    }
}
