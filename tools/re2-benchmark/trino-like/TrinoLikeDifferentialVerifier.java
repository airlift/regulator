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
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.airlift.regulator.TrinoLikePattern;
import io.trino.likematcher.LikeMatcher;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class TrinoLikeDifferentialVerifier
{
    private static final char[] PATTERN_ALPHABET = {'a', 'b', '%', '_'};
    private static final char[] ESCAPED_PATTERN_ALPHABET = {'a', 'b', '%', '_', '\\'};
    private static final byte[] INPUT_ALPHABET = {'a', 'b', '\n', (byte) 0xFF, (byte) 0xC0, (byte) 0xAF};

    private TrinoLikeDifferentialVerifier() {}

    public static void main(String[] args)
    {
        long comparisons = verify(PATTERN_ALPHABET, Optional.empty());
        System.out.println("Verified " + comparisons + " Trino LIKE comparisons");

        long escapedComparisons = verify(ESCAPED_PATTERN_ALPHABET, Optional.of('\\'));
        System.out.println("Verified " + escapedComparisons + " escaped Trino LIKE comparisons");

        long encodedComparisons = verifyEncodedPatterns();
        System.out.println("Verified " + encodedComparisons + " encoded Trino LIKE comparisons");
    }

    private static long verify(char[] patternAlphabet, Optional<Character> escape)
    {
        long comparisons = 0;
        for (int patternLength = 0; patternLength <= 4; patternLength++) {
            int patternCount = power(patternAlphabet.length, patternLength);
            for (int patternValue = 0; patternValue < patternCount; patternValue++) {
                String pattern = pattern(patternAlphabet, patternValue, patternLength);
                comparisons += verifyPattern(pattern.getBytes(UTF_8), escape);
            }
        }
        return comparisons;
    }

    private static long verifyEncodedPatterns()
    {
        byte[][] patterns = {
                "é%_".getBytes(UTF_8),
                "%💰_".getBytes(UTF_8),
                "a\\%é".getBytes(UTF_8),
                {(byte) 0xFF, '%', '_'},
                {'a', (byte) 0xE2, (byte) 0x82, '%'},
                {(byte) 0xFF, '\\', '%'},
                {(byte) 0xC0, (byte) 0xAF, '\\', '_'},
        };

        long comparisons = 0;
        for (byte[] pattern : patterns) {
            comparisons += verifyPattern(pattern, Optional.empty());
            comparisons += verifyPattern(pattern, Optional.of('\\'));
        }
        return comparisons;
    }

    private static long verifyPattern(byte[] patternBytes, Optional<Character> escape)
    {
        String pattern = new String(patternBytes, UTF_8);
        Slice patternSlice = paddedSlice(patternBytes);
        TrinoLikePattern candidate;
        LikeMatcher reference;
        try {
            candidate = escape.isPresent()
                    ? TrinoLikePattern.compile(patternSlice, escape.orElseThrow())
                    : TrinoLikePattern.compile(patternSlice);
        }
        catch (IllegalArgumentException candidateFailure) {
            try {
                LikeMatcher.compile(pattern, escape, false);
            }
            catch (IllegalArgumentException referenceFailure) {
                return 0;
            }
            throw new AssertionError("candidate rejected valid LIKE pattern: " + pattern, candidateFailure);
        }
        try {
            reference = LikeMatcher.compile(pattern, escape, false);
        }
        catch (IllegalArgumentException referenceFailure) {
            throw new AssertionError("candidate accepted invalid LIKE pattern: " + pattern, referenceFailure);
        }

        long comparisons = 0;
        for (int inputLength = 0; inputLength <= 4; inputLength++) {
            int inputCount = power(INPUT_ALPHABET.length, inputLength);
            for (int inputValue = 0; inputValue < inputCount; inputValue++) {
                byte[] input = input(inputValue, inputLength);
                byte[] padded = new byte[input.length + 6];
                Arrays.fill(padded, (byte) '!');
                System.arraycopy(input, 0, padded, 3, input.length);
                Slice slice = Slices.wrappedBuffer(padded).slice(3, input.length);
                boolean candidateResult = candidate.matches(slice);
                boolean referenceResult = reference.match(padded, 3, input.length);
                if (candidateResult != referenceResult) {
                    throw new AssertionError(
                            "LIKE mismatch: pattern=" + HexFormat.of().formatHex(patternBytes) +
                                    ", escape=" + escape +
                                    ", input=" + HexFormat.of().formatHex(input) +
                                    ", candidate=" + candidateResult +
                                    ", reference=" + referenceResult);
                }
                comparisons++;
            }
        }
        return comparisons;
    }

    private static Slice paddedSlice(byte[] value)
    {
        byte[] padded = new byte[value.length + 6];
        Arrays.fill(padded, (byte) '!');
        System.arraycopy(value, 0, padded, 3, value.length);
        return Slices.wrappedBuffer(padded).slice(3, value.length);
    }

    private static String pattern(char[] alphabet, int value, int length)
    {
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            result.append(alphabet[value % alphabet.length]);
            value /= alphabet.length;
        }
        return result.toString();
    }

    private static byte[] input(int value, int length)
    {
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            result[index] = INPUT_ALPHABET[value % INPUT_ALPHABET.length];
            value /= INPUT_ALPHABET.length;
        }
        return result;
    }

    private static int power(int base, int exponent)
    {
        int result = 1;
        for (int index = 0; index < exponent; index++) {
            result *= base;
        }
        return result;
    }
}
