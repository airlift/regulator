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
import io.airlift.slice.Slices;

import java.io.ByteArrayOutputStream;
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
/// any engine runs, as the Rust crate's prefilter runs before its engines. An exact literal of a
/// pattern that has no other literal route also rejects a long window, with the literal search
/// of [#indexIn], as Hyperscan proves a literal absent before it runs its automaton.
final class RequiredBytes
{
    /// Windows at least this long skip the short-window check. Most lines of a line-by-line
    /// search are shorter, and there the scan costs less than the find and engine setup it skips.
    /// A window that holds the bytes is scanned twice, once here and once by the engine, and on a
    /// long window that second scan is no longer small beside the setup. Only a literal with its
    /// long-window search checks longer windows; see [#indexIn].
    static final int SHORT_WINDOW_LIMIT = 256;

    // A one-byte literal is left to the one-of-bytes shape. A case-insensitive literal needs this
    // many exact bytes before its first case-insensitive letter too, which keeps to the patterns
    // that were measured; whether a literal that starts with a case-insensitive letter, such as
    // the `noqa` of `\s+(?i:noqa)`, repays its masked scan is unmeasured.
    private static final int MINIMUM_LITERAL_LENGTH = 2;
    // A literal joined across elements keeps at most this many of its first bytes, which every
    // match consumes as well.
    private static final int MAXIMUM_JOINED_LITERAL_LENGTH = 64;
    // Each of the bytes is compared on every word, so the set stays small and a larger one is
    // left out.
    private static final int ONE_OF_BYTES = 3;
    // The one-of and joined-literal analyses recurse over the expression; past this depth they
    // find nothing.
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
    // The exact literal's search of windows of SHORT_WINDOW_LIMIT bytes or more, for a pattern
    // whose compile chose the long-window check, or null.
    private final LiteralSearchKernel longWindowSearch;

    private RequiredBytes(boolean oneOf, byte[] bytes, byte[] masks, LiteralSearchKernel longWindowSearch)
    {
        this.oneOf = oneOf;
        this.bytes = requireNonNull(bytes, "bytes is null");
        this.masks = masks;
        this.longWindowSearch = longWindowSearch;
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
    /// check and, when compilation chooses it, the long-window check, or null: a literal every
    /// match consumes, where there is one, or else up to three bytes of which every match holds
    /// one. `minimumMatchLength` counts any required prefix, and `hasInnerLiteralSearch` says
    /// whether the pattern has an inner literal search.
    static RequiredBytes analyze(Regexp normalized, int minimumMatchLength, boolean hasInnerLiteralSearch)
    {
        // A pattern whose matches span at least SHORT_WINDOW_LIMIT bytes keeps none, since no
        // short window can hold a match, so such a pattern goes without the long-window check
        // too.
        if (minimumMatchLength >= SHORT_WINDOW_LIMIT) {
            return null;
        }
        RequiredBytes literal = analyzeLiteral(normalized);
        // A pattern with an inner literal search keeps no one-of check, since that search already
        // rejects a window without its literal before any engine setup. A prefix scan does not
        // count: it runs inside the DFA, after the find and engine setup the check saves.
        if (literal != null || hasInnerLiteralSearch) {
            return literal;
        }
        return analyzeOneOf(normalized);
    }

    /// Returns a literal of at least two bytes that every match of the normalized `regexp`
    /// consumes, or null. It is the first that starts at an element of the top-level
    /// concatenation, groups looked through, as for [InnerLiteralSearch], or, where the
    /// expression is not a concatenation, at the expression itself: an ASCII literal element, or
    /// a run of ASCII literal and case-pair elements whose exact bytes before the first
    /// case-insensitive letter number at least two. Without one, it is the rarest exact literal
    /// joined across elements; see [#joinedLiteral].
    private static RequiredBytes analyzeLiteral(Regexp regexp)
    {
        while (regexp.op() == RegexpOp.CAPTURE) {
            regexp = regexp.child(0);
        }
        // A pattern that is a single literal is its own one element.
        List<Regexp> elements = new ArrayList<>();
        if (regexp.op() == RegexpOp.CONCAT) {
            InnerLiteralSearch.addConcatenationElements(regexp, elements, 0);
        }
        else {
            elements.add(regexp);
        }
        for (int index = 0; index < elements.size(); index++) {
            InnerLiteralSearch.FoldedLiteral folded = InnerLiteralSearch.foldedLiteral(elements, index);
            if (folded != null && folded.head().length >= MINIMUM_LITERAL_LENGTH) {
                return new RequiredBytes(false, folded.bytes(), folded.masks(), null);
            }
            byte[] literal = InnerLiteralSearch.asciiLiteral(elements.get(index));
            if (literal != null && literal.length >= MINIMUM_LITERAL_LENGTH) {
                return new RequiredBytes(false, literal, new byte[literal.length], null);
            }
        }
        byte[] joined = joinedLiteral(elements);
        return joined == null ? null : new RequiredBytes(false, joined, new byte[joined.length], null);
    }

    /// Returns the rarest in English text of the exact runs of at least two bytes that every
    /// match consumes across `elements`, or null. A run is the exact suffix every match of an
    /// element ends with, the bytes of the elements after it that each match one exact string,
    /// and the exact prefix every match of the next element starts with. An assertion matches
    /// the empty string, so it joins the bytes on either side. Simplification copies a counted
    /// capture into the top-level concatenation, so `(.*?,){13}z` has the elements `.*?`, `,`,
    /// ..., `,`, `z`, which hold no literal of two bytes, but every match ends with `,z`; a
    /// counted group that does not capture stays one element, whose suffix joins what follows.
    private static byte[] joinedLiteral(List<Regexp> elements)
    {
        byte[] rarest = null;
        ByteArrayOutputStream run = new ByteArrayOutputStream();
        for (Regexp element : elements) {
            byte[] exact = exactBytes(element, 0);
            if (exact != null) {
                run.writeBytes(exact);
                continue;
            }
            run.writeBytes(exactAffix(element, false, 0));
            rarest = rarerLiteral(rarest, run.toByteArray());
            run.reset();
            run.writeBytes(exactAffix(element, true, 0));
        }
        return rarerLiteral(rarest, run.toByteArray());
    }

    /// Returns the ASCII bytes of the one string every match of `regexp` is, or null: an
    /// assertion's are empty. A case-insensitive letter is not exact.
    private static byte[] exactBytes(Regexp regexp, int depth)
    {
        if (depth > MAX_RECURSIVE_DEPTH) {
            return null;
        }
        return switch (regexp.op()) {
            case LITERAL, LITERAL_STRING -> {
                int[] runes = literalRunes(regexp);
                int exactLength = exactRuneCount(runes, foldsCase(regexp), false);
                yield exactLength == runes.length ? asciiBytes(runes, 0, exactLength) : null;
            }
            case CAPTURE -> exactBytes(regexp.child(0), depth + 1);
            case CONCAT -> {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                for (Regexp child : regexp.children()) {
                    byte[] childBytes = exactBytes(child, depth + 1);
                    if (childBytes == null) {
                        yield null;
                    }
                    bytes.writeBytes(childBytes);
                }
                yield bytes.toByteArray();
            }
            case EMPTY_MATCH, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT -> new byte[0];
            default -> null;
        };
    }

    /// Returns exact ASCII bytes that every match of `regexp` starts with, or with `suffix` ends
    /// with, which may be none. A literal gives its exact head or tail, and a group or a
    /// repetition of at least one its child's. A concatenation gives its exact children from its
    /// start, or with `suffix` from its end, up to the first child that is not exact, with that
    /// child's prefix after them, or with `suffix` its suffix before them. Anything else gives
    /// none.
    private static byte[] exactAffix(Regexp regexp, boolean suffix, int depth)
    {
        if (depth > MAX_RECURSIVE_DEPTH) {
            return new byte[0];
        }
        return switch (regexp.op()) {
            case LITERAL, LITERAL_STRING -> {
                int[] runes = literalRunes(regexp);
                int exactLength = exactRuneCount(runes, foldsCase(regexp), suffix);
                yield asciiBytes(runes, suffix ? runes.length - exactLength : 0, exactLength);
            }
            case CAPTURE, PLUS -> exactAffix(regexp.child(0), suffix, depth + 1);
            case REPEAT -> regexp.min() >= 1 ? exactAffix(regexp.child(0), suffix, depth + 1) : new byte[0];
            case CONCAT -> {
                // The exact children from the start, or with `suffix` from the end, up to the
                // first other one, whose affix the bytes then take.
                List<Regexp> children = regexp.children();
                byte[][] exact = new byte[children.size()][];
                int other = -1;
                for (int step = 0; step < children.size() && other < 0; step++) {
                    int index = suffix ? children.size() - 1 - step : step;
                    exact[index] = exactBytes(children.get(index), depth + 1);
                    if (exact[index] == null) {
                        other = index;
                    }
                }
                int first = suffix ? Math.max(other, 0) : 0;
                int last = suffix || other < 0 ? children.size() - 1 : other;
                ByteArrayOutputStream affix = new ByteArrayOutputStream();
                for (int index = first; index <= last; index++) {
                    affix.writeBytes(index == other ? exactAffix(children.get(index), suffix, depth + 1) : exact[index]);
                }
                yield affix.toByteArray();
            }
            default -> new byte[0];
        };
    }

    private static int[] literalRunes(Regexp regexp)
    {
        return regexp.op() == RegexpOp.LITERAL ? new int[] {regexp.rune()} : regexp.runes();
    }

    private static boolean foldsCase(Regexp regexp)
    {
        return (regexp.parseFlags() & (Regexp.FOLD_CASE | Regexp.FULL_CASE_FOLD)) != 0;
    }

    // The number of runes at the start of `runes`, or with `fromEnd` at their end, that each match
    // one ASCII byte.
    private static int exactRuneCount(int[] runes, boolean foldCase, boolean fromEnd)
    {
        int count = 0;
        while (count < runes.length && isExactAscii(runes[fromEnd ? runes.length - 1 - count : count], foldCase)) {
            count++;
        }
        return count;
    }

    private static byte[] asciiBytes(int[] runes, int start, int length)
    {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) runes[start + index];
        }
        return bytes;
    }

    // The literal less likely in English text, or `current` on a tie. A run shorter than
    // MINIMUM_LITERAL_LENGTH is no literal, and a longer one than MAXIMUM_JOINED_LITERAL_LENGTH
    // keeps its first bytes, which are judged. `run` is the caller's own, so it is kept as is.
    private static byte[] rarerLiteral(byte[] current, byte[] run)
    {
        if (run.length < MINIMUM_LITERAL_LENGTH) {
            return current;
        }
        byte[] candidate = run.length > MAXIMUM_JOINED_LITERAL_LENGTH ? Arrays.copyOf(run, MAXIMUM_JOINED_LITERAL_LENGTH) : run;
        if (current == null) {
            return candidate;
        }
        return literalLogProbability(candidate) < literalLogProbability(current) ? candidate : current;
    }

    // The logarithm of the English-text probability of the literal's bytes in sequence, taking
    // each byte as independent. A product of the probabilities underflows to zero well within
    // MAXIMUM_JOINED_LITERAL_LENGTH bytes of rare ones, which would tie distinct literals. The
    // terms are added in byte order, so literals with the same bytes in another order sum to the
    // same value and tie, rather than being ordered by rounding.
    private static double literalLogProbability(byte[] literal)
    {
        ByteFrequencies english = ByteFrequencies.englishText();
        byte[] sorted = literal.clone();
        Arrays.sort(sorted);
        double logProbability = 0;
        for (byte value : sorted) {
            logProbability += Math.log(english.probability(value));
        }
        return logProbability;
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
        return new RequiredBytes(true, padded, null, null);
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
        if (!isExactAscii(rune, foldCase)) {
            return null;
        }
        return new byte[] {(byte) rune};
    }

    // Whether the rune matches exactly its one ASCII byte: a case-insensitive letter does not.
    private static boolean isExactAscii(int rune, boolean foldCase)
    {
        return rune >= 0 && rune <= 0x7F && !(foldCase && Character.isLetter(rune));
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

    /// Whether the fact is a literal without case-insensitive letters, which the long-window
    /// check can search for: its search of [#indexIn] compares exact bytes.
    boolean isExactLiteral()
    {
        if (oneOf) {
            return false;
        }
        for (byte mask : masks) {
            if (mask != 0) {
                return false;
            }
        }
        return true;
    }

    /// Returns this exact literal with the search [#indexIn] runs on long windows.
    RequiredBytes withLongWindowSearch()
    {
        if (!isExactLiteral()) {
            throw new IllegalStateException("not an exact literal: " + this);
        }
        return new RequiredBytes(false, bytes, masks, LiteralSearchKernel.sharing(Slices.wrappedBuffer(bytes)));
    }

    /// Returns this literal without the search [#withLongWindowSearch] adds, for the
    /// short-window check alone.
    RequiredBytes withoutLongWindowSearch()
    {
        if (!searchesLongWindows()) {
            throw new IllegalStateException("no long-window search: " + this);
        }
        return new RequiredBytes(false, bytes, masks, null);
    }

    /// Whether the compile chose the long-window check for this literal; see [#indexIn].
    boolean searchesLongWindows()
    {
        return longWindowSearch != null;
    }

    /// Returns the Slice offset of the literal's first occurrence wholly inside `[start, end)` of
    /// `text`, or -1, for a literal with its long-window search. Where vectors are available, a
    /// window of at least the fused vector scan's minimum takes the vector pair scan; a shorter
    /// one, or any window without vectors, takes the call-free word-at-a-time search. See
    /// [Prog#findLiteral].
    int indexIn(Slice text, int start, int end)
    {
        int base = text.byteArrayOffset();
        int found = Prog.findLiteral(text.byteArray(), base + start, end - start, bytes, longWindowSearch);
        return found < 0 ? -1 : found - base;
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
        // The long-window search shares `bytes`, which its own estimate counts too.
        return INSTANCE_SIZE + SizeOf.sizeOf(bytes) + SizeOf.sizeOf(masks) + (longWindowSearch == null ? 0 : longWindowSearch.estimatedRetainedSize() - SizeOf.sizeOf(bytes));
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
