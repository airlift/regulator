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

import io.airlift.slice.SizeOf;
import io.airlift.slice.Slice;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.Objects.requireNonNull;

/// The bytes every match of a pattern contains: a literal sequence that every match consumes, such
/// as `# noqa` in ruff's noqa comment pattern, or one of up to three bytes, such as `/` or `@` in
/// `[a-z]+://\S+|[^ @]+@[^ @]+`. A search window without them holds no match, so a short window,
/// such as one line of a line-by-line search, is rejected with one scan of its 8-byte words before
/// any engine runs, as the Rust crate's prefilter runs before its engines.
final class RequiredBytes
{
    /// Windows at least this long skip the check. Most lines of a line-by-line search are
    /// shorter, and there the scan costs less than the find and engine setup it skips. A window
    /// that holds the bytes is scanned twice, once here and once by the engine, and on a long
    /// window that second scan is no longer small beside the setup.
    static final int SHORT_WINDOW_LIMIT = 256;

    // A one-byte literal is left to the one-of-bytes shape. A case-insensitive literal needs this
    // many exact bytes before its first case-insensitive letter too, which keeps to the patterns
    // that were measured; whether a literal that starts with a case-insensitive letter, such as
    // the `noqa` of `\s+(?i:noqa)`, repays its masked scan is unmeasured.
    private static final int MINIMUM_LITERAL_LENGTH = 2;
    // Each of the bytes is compared on every word, so the set stays small and a larger one is
    // left out.
    private static final int ONE_OF_BYTES = 3;
    // The one-of analysis recurses over the expression; past this depth it finds nothing.
    private static final int MAX_RECURSIVE_DEPTH = 64;
    // A literal whose first byte has an English-text probability under this, 0.5%, so that fewer
    // than one 8-byte word in 25 holds it, loads its last byte's word only for a word that holds
    // the first byte.
    private static final double RARE_FIRST_BYTE_PROBABILITY = 0.005;

    private static final int INSTANCE_SIZE = SizeOf.instanceSize(RequiredBytes.class);
    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long LOW_BITS = 0x0101010101010101L;
    private static final long HIGH_BITS = 0x8080808080808080L;

    // Whether every match holds one of `bytes` rather than all of them in sequence.
    private final boolean oneOf;
    // The literal, its case-insensitive letters lowered, or the three bytes of the one-of-bytes
    // shape, the last repeated to fill three.
    private final byte[] bytes;
    // The literal's case masks: 0x20 at a letter that matches either case, which
    // `(byte | 0x20) == letter` tests, and 0 at an exact byte, as the first byte always is. Null
    // for the one-of-bytes shape.
    private final byte[] masks;
    // The broadcasts of the literal's first and last bytes, and the last byte's mask.
    private final long firstBroadcast;
    private final long lastBroadcast;
    private final long lastMask;
    // Whether the literal scan loads the last byte's word only for a word that holds the first
    // byte. That branch predicts well only when the first byte is rare: on a common first byte,
    // such as the `c` of `coding`, it mispredicted and cost `unnecessary-coding-comment` 14 to 15%
    // over testing both bytes on every word.
    private final boolean gatesLastByteOnFirst;
    // LOW_BITS, read from a field so the word loop holds it in a register, as in
    // LiteralSearchKernel: on aarch64 C2 cannot encode -LOW_BITS as an immediate.
    private final long lowBits;

    private RequiredBytes(boolean oneOf, byte[] bytes, byte[] masks)
    {
        this.oneOf = oneOf;
        this.bytes = requireNonNull(bytes, "bytes is null");
        this.masks = masks;
        if (oneOf) {
            this.firstBroadcast = 0;
            this.lastBroadcast = 0;
            this.lastMask = 0;
            this.gatesLastByteOnFirst = false;
        }
        else {
            int last = bytes.length - 1;
            this.firstBroadcast = broadcast(bytes[0]);
            this.lastBroadcast = broadcast(bytes[last]);
            this.lastMask = broadcast(masks[last]);
            this.gatesLastByteOnFirst = ByteFrequencies.englishText().probability(bytes[0]) < RARE_FIRST_BYTE_PROBABILITY;
        }
        this.lowBits = LOW_BITS;
    }

    /// Returns the bytes every match of the `normalized` expression holds, for the short-window
    /// check, or null: the first literal every match consumes, where there is one, or else up to
    /// three bytes of which every match holds one. `minimumMatchLength` counts any required
    /// prefix, and `hasOwnLiteralScan` says whether the pattern has an inner literal search or a
    /// prefix scan.
    static RequiredBytes analyze(Regexp normalized, int minimumMatchLength, boolean hasOwnLiteralScan)
    {
        // A pattern whose matches span at least SHORT_WINDOW_LIMIT bytes keeps none, since no
        // window the check reads can hold a match.
        if (minimumMatchLength >= SHORT_WINDOW_LIMIT) {
            return null;
        }
        RequiredBytes literal = analyzeLiteral(normalized);
        // A pattern with its own literal scan keeps no one-of check, since that scan already
        // rejects a window without its literal.
        if (literal != null || hasOwnLiteralScan) {
            return literal;
        }
        return analyzeOneOf(normalized);
    }

    /// Returns the first literal of at least two bytes that every match of the normalized
    /// `regexp` consumes, or null. The literal starts at an element of its top-level
    /// concatenation, groups looked through, as for [InnerLiteralSearch]: an ASCII literal
    /// element, or a run of ASCII literal and case-pair elements whose exact bytes before the
    /// first case-insensitive letter number at least two.
    private static RequiredBytes analyzeLiteral(Regexp regexp)
    {
        while (regexp.op() == RegexpOp.CAPTURE) {
            regexp = regexp.child(0);
        }
        if (regexp.op() != RegexpOp.CONCAT) {
            return null;
        }
        List<Regexp> elements = new ArrayList<>();
        InnerLiteralSearch.addConcatenationElements(regexp, elements, 0);
        for (int index = 0; index < elements.size(); index++) {
            InnerLiteralSearch.FoldedLiteral folded = InnerLiteralSearch.foldedLiteral(elements, index);
            if (folded != null && folded.head().length >= MINIMUM_LITERAL_LENGTH) {
                return new RequiredBytes(false, folded.bytes(), folded.masks());
            }
            byte[] literal = InnerLiteralSearch.asciiLiteral(elements.get(index));
            if (literal != null && literal.length >= MINIMUM_LITERAL_LENGTH) {
                return new RequiredBytes(false, literal, new byte[literal.length]);
            }
        }
        return null;
    }

    /// Returns up to three ASCII bytes of which every match of the normalized `regexp` holds at
    /// least one, or null. An alternation takes the union of its branches' bytes; a concatenation
    /// takes its rarest element's in English text. A case-insensitive letter gives no byte: one
    /// byte cannot cover both cases, and some letters, such as `k` with the Kelvin sign, also
    /// match a non-ASCII form.
    private static RequiredBytes analyzeOneOf(Regexp regexp)
    {
        byte[] bytes = oneOfBytes(regexp, 0);
        if (bytes == null) {
            return null;
        }
        // Repeat the last byte, so the scan always compares three.
        byte[] padded = new byte[ONE_OF_BYTES];
        for (int index = 0; index < ONE_OF_BYTES; index++) {
            padded[index] = bytes[Math.min(index, bytes.length - 1)];
        }
        return new RequiredBytes(true, padded, null);
    }

    private static byte[] oneOfBytes(Regexp regexp, int depth)
    {
        if (depth > MAX_RECURSIVE_DEPTH) {
            return null;
        }
        boolean foldCase = (regexp.parseFlags() & Regexp.FOLD_CASE) != 0;
        return switch (regexp.op()) {
            case LITERAL -> asciiByte(regexp.rune(), foldCase);
            case LITERAL_STRING -> {
                byte[] rarest = null;
                for (int rune : regexp.runes()) {
                    rarest = rarer(rarest, asciiByte(rune, foldCase));
                }
                yield rarest;
            }
            case CAPTURE, PLUS -> oneOfBytes(regexp.child(0), depth + 1);
            case REPEAT -> regexp.min() >= 1 ? oneOfBytes(regexp.child(0), depth + 1) : null;
            case CONCAT -> {
                byte[] rarest = null;
                for (Regexp child : regexp.children()) {
                    rarest = rarer(rarest, oneOfBytes(child, depth + 1));
                }
                yield rarest;
            }
            case ALTERNATE -> {
                byte[] union = new byte[0];
                for (Regexp child : regexp.children()) {
                    byte[] bytes = oneOfBytes(child, depth + 1);
                    if (bytes == null) {
                        yield null;
                    }
                    union = union(union, bytes);
                    if (union == null) {
                        yield null;
                    }
                }
                yield union;
            }
            default -> null;
        };
    }

    private static byte[] asciiByte(int rune, boolean foldCase)
    {
        if (rune < 0 || rune > 0x7F || (foldCase && Character.isLetter(rune))) {
            return null;
        }
        return new byte[] {(byte) rune};
    }

    // The union of two byte sets, or null when it holds more than ONE_OF_BYTES bytes.
    private static byte[] union(byte[] left, byte[] right)
    {
        byte[] result = left;
        for (byte value : right) {
            boolean present = false;
            for (byte existing : result) {
                present |= existing == value;
            }
            if (!present) {
                if (result.length == ONE_OF_BYTES) {
                    return null;
                }
                result = Arrays.copyOf(result, result.length + 1);
                result[result.length - 1] = value;
            }
        }
        return result;
    }

    // The set less likely in English text, or `current` on a tie.
    private static byte[] rarer(byte[] current, byte[] candidate)
    {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return probability(candidate) < probability(current) ? candidate : current;
    }

    private static double probability(byte[] bytes)
    {
        ByteFrequencies english = ByteFrequencies.englishText();
        double probability = 0;
        for (byte value : bytes) {
            probability += english.probability(value);
        }
        return probability;
    }

    private static long broadcast(byte value)
    {
        return (value & 0xFFL) * LOW_BITS;
    }

    /// Whether `[start, end)` of `text` holds the literal wholly, or one of the bytes.
    boolean occursIn(Slice text, int start, int end)
    {
        byte[] data = text.byteArray();
        int offset = text.byteArrayOffset() + start;
        if (oneOf) {
            return containsOneOf(data, offset, end - start);
        }
        return containsLiteral(data, offset, end - start);
    }

    // Tests the window's candidate starts eight per 8-byte word on the literal's first and last
    // bytes, the last byte's word only where the first byte is present when that byte is rare,
    // with a last word that overlaps the one before it in place of a byte tail; fewer than eight
    // candidates are compared byte by byte. Only presence matters, so the candidates need not be
    // tested in order, and each is verified whole.
    private boolean containsLiteral(byte[] data, int offset, int length)
    {
        int lastOffset = bytes.length - 1;
        int lastCandidate = offset + length - bytes.length;
        if (lastCandidate < offset) {
            return false;
        }
        if (lastCandidate - offset < Long.BYTES - 1) {
            for (int candidate = offset; candidate <= lastCandidate; candidate++) {
                if (matchesAt(data, candidate)) {
                    return true;
                }
            }
            return false;
        }
        long first = firstBroadcast;
        long last = lastBroadcast;
        long lastFold = lastMask;
        long low = lowBits;
        boolean gate = gatesLastByteOnFirst;
        int lastWord = lastCandidate - (Long.BYTES - 1);
        int position = offset;
        while (true) {
            long firstDifference = ((long) LONG_HANDLE.get(data, position)) ^ first;
            long candidates = (firstDifference - low) & ~firstDifference & HIGH_BITS;
            if (!gate || candidates != 0) {
                long lastDifference = (((long) LONG_HANDLE.get(data, position + lastOffset)) | lastFold) ^ last;
                candidates &= (lastDifference - low) & ~lastDifference;
            }
            while (candidates != 0) {
                if (matchesAt(data, position + (Long.numberOfTrailingZeros(candidates) >>> 3))) {
                    return true;
                }
                candidates &= candidates - 1;
            }
            if (position == lastWord) {
                return false;
            }
            position = Math.min(position + Long.BYTES, lastWord);
        }
    }

    private boolean matchesAt(byte[] data, int candidate)
    {
        for (int index = 0; index < bytes.length; index++) {
            if ((data[candidate + index] | masks[index]) != bytes[index]) {
                return false;
            }
        }
        return true;
    }

    // One word load and a zero-byte test against each of the three bytes per word, with a last
    // word that overlaps the one before it in place of a byte tail.
    private boolean containsOneOf(byte[] data, int offset, int length)
    {
        int end = offset + length;
        if (length < Long.BYTES) {
            for (int position = offset; position < end; position++) {
                byte value = data[position];
                if (value == bytes[0] || value == bytes[1] || value == bytes[2]) {
                    return true;
                }
            }
            return false;
        }
        long first = broadcast(bytes[0]);
        long second = broadcast(bytes[1]);
        long third = broadcast(bytes[2]);
        int lastWord = end - Long.BYTES;
        for (int position = offset; position < lastWord; position += Long.BYTES) {
            if (holdsAny((long) LONG_HANDLE.get(data, position), first, second, third)) {
                return true;
            }
        }
        return holdsAny((long) LONG_HANDLE.get(data, lastWord), first, second, third);
    }

    private static boolean holdsAny(long word, long first, long second, long third)
    {
        long firstDifference = word ^ first;
        long secondDifference = word ^ second;
        long thirdDifference = word ^ third;
        return ((((firstDifference - LOW_BITS) & ~firstDifference) |
                ((secondDifference - LOW_BITS) & ~secondDifference) |
                ((thirdDifference - LOW_BITS) & ~thirdDifference)) & HIGH_BITS) != 0;
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + SizeOf.sizeOf(bytes) + SizeOf.sizeOf(masks);
    }

    /// The literal, its case-insensitive letters lowered, or `one of` and the distinct bytes.
    @Override
    public String toString()
    {
        if (!oneOf) {
            return new String(bytes, ISO_8859_1);
        }
        StringBuilder text = new StringBuilder("one of ");
        for (int index = 0; index < bytes.length; index++) {
            if (index == 0 || bytes[index] != bytes[index - 1]) {
                text.append((char) (bytes[index] & 0xFF));
            }
        }
        return text.toString();
    }
}
