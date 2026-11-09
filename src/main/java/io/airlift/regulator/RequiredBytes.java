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
import java.util.function.Predicate;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.Objects.requireNonNull;

/// The bytes every match of a pattern contains: a literal sequence that every match consumes, such
/// as `# noqa` in ruff's noqa comment pattern, or one of up to three bytes, such as `/` or `@` in
/// `[a-z]+://\S+|[^ @]+@[^ @]+`. A search window without them holds no match, so a short window,
/// such as one line of a line-by-line search, is rejected with one scan of its 8-byte words before
/// any engine runs, as the Rust crate's prefilter runs before its engines. An exact literal of a
/// pattern that has no other literal route also rejects a long window, with the literal search
/// of [#indexIn], as Hyperscan proves a literal absent before it runs its automaton. Where every
/// match also ends with exact bytes, the last occurrence of those bytes bounds a long window's
/// end, as Hyperscan bounds its scan; see [#lastIndexIn]. Where those bytes do not hold the
/// literal, the window is still checked for the literal before that end. A pattern that another
/// literal route keeps from that check searches long windows for its rarest exact literal
/// instead, where the route does not already search for it; see [#literalBesideRoutes].
final class RequiredBytes
{
    /// Windows at least this long skip the short-window check. Most lines of a line-by-line search
    /// are shorter, and there the scan costs less than the find and engine setup it skips. A window
    /// that holds the bytes is scanned twice, once here and once by the engine, and on a long
    /// window that second scan is no longer small beside the setup. Only a literal with its
    /// long-window search checks longer windows; see [#indexIn]. A one-of set of two or three
    /// distinct bytes beside an inner literal search stops sooner; see [#windowLimit()].
    static final int SHORT_WINDOW_LIMIT = 256;

    // A one-byte literal is left to the one-of-bytes shape. A case-insensitive literal needs this
    // many exact bytes before its first case-insensitive letter too, which keeps to the patterns
    // that were measured; whether a literal that starts with a case-insensitive letter, such as
    // the `noqa` of `\s+(?i:noqa)`, repays its masked scan is unmeasured.
    private static final int MINIMUM_LITERAL_LENGTH = 2;
    // A literal joined across elements keeps at most this many of its first bytes, which every
    // match consumes as well.
    private static final int MAXIMUM_JOINED_LITERAL_LENGTH = 64;
    // Bounds how many bytes one pattern's search for a literal beside its other literal routes
    // compares. One run compares a bounded number, because the start bytes and route literals are
    // bounded, but a pattern holds as many runs as its size allows. An 85,000-byte pattern of
    // 1,000 runs that each repeat its first 63 bytes compares about 64,000.
    private static final long MAXIMUM_BESIDE_ROUTE_COMPARISONS = 1L << 20;
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
    // Whether the fact is a single required byte, repeated to fill three, which takes the
    // single-byte scan.
    private final boolean singleByte;
    // The literal, its case-insensitive letters lowered, or the three bytes of the one-of-bytes
    // shape, the last repeated to fill three.
    private final byte[] bytes;
    // The literal's case masks: 0x20 at a letter that matches either case, which
    // `(byte | 0x20) == letter` tests, and 0 at an exact byte, as the first byte always is. Null
    // for the one-of-bytes shape.
    private final byte[] masks;
    // The broadcasts of the literal's first and last bytes, and the last byte's mask. A single
    // required byte keeps its broadcast as the first.
    private final long firstBroadcast;
    private final long lastBroadcast;
    private final long lastMask;
    // Whether the literal scan loads the last byte's word only for a word that holds the first
    // byte. That branch predicts well only when the first byte is rare: on a common first byte,
    // such as the `c` of `coding`, it mispredicted and cost `unnecessary-coding-comment` 14 to 15%
    // over testing both bytes on every word.
    private final boolean gatesLastByteOnFirst;
    // LOW_BITS, read from a field so the word loops hold it in a register, as in
    // LiteralSearchKernel: on aarch64 C2 cannot encode -LOW_BITS as an immediate.
    private final long lowBits;
    // The search of windows of SHORT_WINDOW_LIMIT bytes or more, for a pattern whose compile chose
    // the long-window check, or null: for the exact literal `bytes`, which it shares, or for the
    // literal beside a pattern's other literal route.
    private final LiteralSearchKernel longWindowSearch;
    // The short-window check runs on windows shorter than this: SHORT_WINDOW_LIMIT, or, for a
    // one-of set of two or three distinct bytes beside an inner literal search, that search's
    // minimum where it is lower. From that minimum the search scans each window for its literal
    // before any engine runs unless the find skips the DFA, and the one-of scan would repeat or
    // replace that scan. A single required byte keeps SHORT_WINDOW_LIMIT, with a scan as cheap as
    // the search's single-byte scan: a check that changed with line length cost more on naturally
    // mixed line lengths, as CompiledPattern.build records.
    private final int windowLimit;
    // Whether `suffix` holds the literal as a run of its bytes, for a literal with its search-end
    // bound, so that a window holding the suffix holds the literal too.
    private final boolean suffixHoldsLiteral;
    // The exact bytes every match ends with, from requiredSuffix, the literal's own array where
    // they are the literal, or null. Only the analysis and a literal whose compile chose the
    // search-end bound hold them.
    private final byte[] suffix;
    // The search for the last occurrence of `suffix`, for a pattern whose compile chose the
    // search-end bound, or null. Where the suffix is the literal, it is also longWindowSearch.
    private final LiteralSearchKernel suffixSearch;
    // Whether the literal serves the long-window check alone, for a pattern whose matches span at
    // least SHORT_WINDOW_LIMIT bytes.
    private final boolean longWindowsOnly;

    private RequiredBytes(boolean oneOf, byte[] bytes, byte[] masks, LiteralSearchKernel longWindowSearch, byte[] suffix, LiteralSearchKernel suffixSearch)
    {
        this(oneOf, bytes, masks, longWindowSearch, suffix, suffixSearch, false, SHORT_WINDOW_LIMIT);
    }

    private RequiredBytes(boolean oneOf, byte[] bytes, byte[] masks, LiteralSearchKernel longWindowSearch, byte[] suffix, LiteralSearchKernel suffixSearch, boolean longWindowsOnly, int windowLimit)
    {
        this.oneOf = oneOf;
        this.bytes = requireNonNull(bytes, "bytes is null");
        this.masks = masks;
        this.longWindowSearch = longWindowSearch;
        this.suffix = suffix;
        this.suffixSearch = suffixSearch;
        this.longWindowsOnly = longWindowsOnly;
        if (oneOf) {
            // The padding repeats the last byte, so the first equals the third only when there is
            // one byte.
            this.singleByte = bytes[0] == bytes[2];
            this.firstBroadcast = singleByte ? broadcast(bytes[0]) : 0;
            this.lastBroadcast = 0;
            this.lastMask = 0;
            this.gatesLastByteOnFirst = false;
        }
        else {
            int last = bytes.length - 1;
            this.singleByte = false;
            this.firstBroadcast = broadcast(bytes[0]);
            this.lastBroadcast = broadcast(bytes[last]);
            this.lastMask = broadcast(masks[last]);
            this.gatesLastByteOnFirst = ByteFrequencies.englishText().probability(bytes[0]) < RARE_FIRST_BYTE_PROBABILITY;
        }
        this.lowBits = LOW_BITS;
        this.windowLimit = windowLimit;
        this.suffixHoldsLiteral = suffixSearch != null && Slices.wrappedBuffer(suffix).indexOf(Slices.wrappedBuffer(bytes)) >= 0;
    }

    /// Returns the bytes every match of the `normalized` expression holds, for the short-window
    /// check and, when compilation chooses it, the long-window check, or null: a literal every
    /// match consumes, where there is one, with the exact bytes every match ends with, where
    /// there are any, for the search-end bound; or else up to three bytes of which every match
    /// holds one. `minimumMatchLength` counts any required prefix. A pattern whose matches span at
    /// least [#SHORT_WINDOW_LIMIT] bytes keeps only an exact literal, for the long-window check
    /// alone; see [#isLongWindowsOnly].
    static RequiredBytes analyze(Regexp normalized, int minimumMatchLength)
    {
        RequiredBytes literal = analyzeLiteral(normalized, requiredSuffix(normalized));
        if (minimumMatchLength >= SHORT_WINDOW_LIMIT) {
            return literal != null && literal.isExactLiteral() ? new RequiredBytes(false, literal.bytes, literal.masks, null, literal.suffix, null, true, SHORT_WINDOW_LIMIT) : null;
        }
        if (literal != null) {
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
    /// joined across elements; see [#joinedLiteral]. It keeps `suffix`, the literal's own bytes
    /// where they are equal.
    private static RequiredBytes analyzeLiteral(Regexp regexp, byte[] suffix)
    {
        List<Regexp> elements = elements(regexp);
        for (int index = 0; index < elements.size(); index++) {
            InnerLiteralSearch.FoldedLiteral folded = InnerLiteralSearch.foldedLiteral(elements, index);
            if (folded != null && folded.head().length >= MINIMUM_LITERAL_LENGTH) {
                return new RequiredBytes(false, folded.bytes(), folded.masks(), null, sharedSuffix(folded.bytes(), suffix), null);
            }
            byte[] literal = InnerLiteralSearch.asciiLiteral(elements.get(index));
            if (literal != null && literal.length >= MINIMUM_LITERAL_LENGTH) {
                return exactLiteral(literal, suffix);
            }
        }
        byte[] joined = joinedLiteral(elements);
        return joined == null ? null : exactLiteral(joined, suffix);
    }

    private static RequiredBytes exactLiteral(byte[] literal, byte[] suffix)
    {
        return new RequiredBytes(false, literal, new byte[literal.length], null, sharedSuffix(literal, suffix), null);
    }

    // The suffix, or the literal's own array where they are equal, so one search can serve both.
    private static byte[] sharedSuffix(byte[] literal, byte[] suffix)
    {
        return Arrays.equals(literal, suffix) ? literal : suffix;
    }

    /// Returns the exact ASCII bytes, at least two, that every match of the normalized `regexp`
    /// ends with, where no zero-width op follows the last of them in any match, or null; a longer
    /// run keeps its last [#MAXIMUM_JOINED_LITERAL_LENGTH] bytes. In a window, then, every match
    /// ends at or before the end of the bytes' last occurrence, and no assertion of a match is
    /// read at its end; see [#lastIndexIn]. `(.*?,){13}z` ends with `,z`, while `foo\b` and
    /// `,z$` end with none.
    static byte[] requiredSuffix(Regexp regexp)
    {
        ExactSuffix suffix = exactSuffix(regexp, 0);
        if (suffix == null || suffix.bytes().length < MINIMUM_LITERAL_LENGTH) {
            return null;
        }
        return suffix.bytes();
    }

    /// Returns the exact ASCII bytes every match of `regexp` ends with, which may be none, or null
    /// when a zero-width op can follow its last consumed byte: an assertion or an empty match
    /// itself, or one at the end of a group, repetition, or concatenation.
    ///
    /// - A literal gives its exact tail, as for [#exactAffix], and a group gives its child's.
    /// - A repetition of at least one gives its child's, not whole, since an earlier repetition
    ///   may come before them.
    /// - A concatenation gives its children's bytes from its end, up to and including the first
    ///   child that is not whole. A zero-width op between bytes ends them there.
    /// - Anything else gives none.
    ///
    /// The case-fold rules are those of [#exactBytes]. The bytes are at most the last
    /// [#MAXIMUM_JOINED_LITERAL_LENGTH], all that [#requiredSuffix] keeps. A longer literal gives
    /// its last that many. A concatenation stops once its children have given that many, and
    /// keeps their last that many. Either truncation clears `whole`, so no earlier bytes join
    /// them.
    private static ExactSuffix exactSuffix(Regexp regexp, int depth)
    {
        if (depth > MAX_RECURSIVE_DEPTH) {
            return null;
        }
        return switch (regexp.op()) {
            case LITERAL, LITERAL_STRING -> {
                int[] runes = literalRunes(regexp);
                int exactLength = Math.min(exactRuneCount(runes, foldsCase(regexp), true), MAXIMUM_JOINED_LITERAL_LENGTH);
                yield new ExactSuffix(asciiBytes(runes, runes.length - exactLength, exactLength), exactLength == runes.length);
            }
            case CAPTURE -> exactSuffix(regexp.child(0), depth + 1);
            case PLUS -> ended(exactSuffix(regexp.child(0), depth + 1));
            case REPEAT -> regexp.min() >= 1 ? ended(exactSuffix(regexp.child(0), depth + 1)) : ExactSuffix.NONE;
            case CONCAT -> {
                // The children's bytes, last child first, whose last MAXIMUM_JOINED_LITERAL_LENGTH
                // are joined in order once collected.
                List<byte[]> collected = new ArrayList<>();
                int length = 0;
                boolean whole = true;
                for (Regexp child : regexp.children().reversed()) {
                    if (length >= MAXIMUM_JOINED_LITERAL_LENGTH) {
                        whole = false;
                        break;
                    }
                    ExactSuffix childSuffix = exactSuffix(child, depth + 1);
                    if (childSuffix == null) {
                        // A zero-width op after every byte the concatenation ends with leaves
                        // none; one before them ends them.
                        if (length == 0) {
                            yield null;
                        }
                        whole = false;
                        break;
                    }
                    collected.add(childSuffix.bytes());
                    length += childSuffix.bytes().length;
                    if (!childSuffix.whole()) {
                        whole = false;
                        break;
                    }
                }
                int size = Math.min(length, MAXIMUM_JOINED_LITERAL_LENGTH);
                byte[] suffix = new byte[size];
                int end = size;
                for (byte[] bytes : collected) {
                    int count = Math.min(bytes.length, end);
                    end -= count;
                    System.arraycopy(bytes, bytes.length - count, suffix, end, count);
                }
                yield new ExactSuffix(suffix, whole && size == length);
            }
            case EMPTY_MATCH, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT, HAVE_MATCH -> null;
            default -> ExactSuffix.NONE;
        };
    }

    private static ExactSuffix ended(ExactSuffix suffix)
    {
        return suffix == null ? null : new ExactSuffix(suffix.bytes(), false);
    }

    /// The exact bytes every match of an expression ends with. `whole` is true when these bytes
    /// are the entire expression as one fixed string. Only then can earlier concatenation elements
    /// extend the suffix. Truncation clears `whole`.
    private record ExactSuffix(byte[] bytes, boolean whole)
    {
        static final ExactSuffix NONE = new ExactSuffix(new byte[0], false);
    }

    // The top-level concatenation's elements, groups looked through, as for InnerLiteralSearch. A
    // pattern that is not a concatenation, such as a single literal, is its own one element.
    private static List<Regexp> elements(Regexp regexp)
    {
        while (regexp.op() == RegexpOp.CAPTURE) {
            regexp = regexp.child(0);
        }
        List<Regexp> elements = new ArrayList<>();
        if (regexp.op() == RegexpOp.CONCAT) {
            InnerLiteralSearch.addConcatenationElements(regexp, elements, 0);
        }
        else {
            elements.add(regexp);
        }
        return elements;
    }

    /// Returns the exact literal that a pattern with another literal route searches long windows
    /// for beside that route, or null. It is the rarest in English text of the runs
    /// [#joinedLiteral] compares, which hold every exact literal element, among those the route
    /// does not already search for.
    ///
    /// A run is skipped only when the exact prefix, or a fixed position in a route literal,
    /// guarantees every byte of it. The exact prefix is the bytes every match of the normalized
    /// `regexp` starts with, which a prefix scan, a transparent start literal, or an inner literal
    /// search for a literal that begins every match finds first. The route literals are
    /// `routeLiterals`, which a scan plan or an inner literal search for a single literal
    /// searches for. A route literal guarantees a run only byte for byte: an occurrence of
    /// `foobar` holds no `FOO`, and since a case-insensitive route literal's folded letters match
    /// either case, one of `foo[Bb][Aa][Rr]` need not hold `bar`. So `Holmes.*?Watson`, whose
    /// prefix scan finds `Holmes`, searches long windows for `Watson`. A filter that searches
    /// alternatives, a start literal filter or an inner literal search for a literal set, does
    /// not guarantee any one run.
    ///
    /// Only the first [#MAXIMUM_JOINED_LITERAL_LENGTH] start bytes are compared, all that the
    /// first run keeps, so the comparisons grow with the runs alone. A later run that repeats
    /// start bytes past them may take a check the route makes redundant, which costs a scan but
    /// never rejects a window with a match. A pattern whose runs compare more than
    /// [#MAXIMUM_BESIDE_ROUTE_COMPARISONS] bytes in all searches for none, since the check is
    /// optional.
    static byte[] literalBesideRoutes(Regexp regexp, List<RouteLiteral> routeLiterals)
    {
        List<Regexp> elements = elements(regexp);
        byte[] start = startBytes(elements);
        BesideRouteComparisons comparisons = new BesideRouteComparisons();
        byte[] literal = joinedLiteral(elements, run -> {
            if (comparisons.contains(start, null, run)) {
                return false;
            }
            for (RouteLiteral routeLiteral : routeLiterals) {
                if (comparisons.contains(routeLiteral.bytes(), routeLiteral.masks(), run)) {
                    return false;
                }
            }
            return true;
        });
        return comparisons.spent() ? null : literal;
    }

    /// A literal that a pattern's route searches for, for [#literalBesideRoutes]: its bytes, and,
    /// for a case-insensitive literal, its masks, `0x20` at each letter whose case the route does
    /// not fix, which [InnerLiteralSearch] lowers in the bytes, or null for an exact literal.
    record RouteLiteral(byte[] bytes, byte[] masks) {}

    // The first MAXIMUM_JOINED_LITERAL_LENGTH of the exact bytes every match of the elements
    // starts with: those of the exact elements from the first, and the exact prefix of the
    // element after them.
    private static byte[] startBytes(List<Regexp> elements)
    {
        ByteArrayOutputStream start = new ByteArrayOutputStream();
        for (Regexp element : elements) {
            if (start.size() >= MAXIMUM_JOINED_LITERAL_LENGTH) {
                break;
            }
            byte[] exact = exactBytes(element, 0);
            if (exact == null) {
                start.writeBytes(exactAffix(element, false, 0));
                break;
            }
            start.writeBytes(exact);
        }
        byte[] bytes = start.toByteArray();
        return bytes.length > MAXIMUM_JOINED_LITERAL_LENGTH ? Arrays.copyOf(bytes, MAXIMUM_JOINED_LITERAL_LENGTH) : bytes;
    }

    /// The bytes one [#literalBesideRoutes] search may still compare, of
    /// [#MAXIMUM_BESIDE_ROUTE_COMPARISONS].
    private static final class BesideRouteComparisons
    {
        private long left = MAXIMUM_BESIDE_ROUTE_COMPARISONS;

        // Whether `text` holds `literal`, where a byte of `text` that `masks` marks matches no
        // byte, and `masks` may be null. False too once the comparisons are spent, as spent then
        // reports.
        boolean contains(byte[] text, byte[] masks, byte[] literal)
        {
            for (int start = 0; start + literal.length <= text.length && left >= 0; start++) {
                int index = 0;
                while (index < literal.length && text[start + index] == literal[index] && (masks == null || masks[start + index] == 0)) {
                    index++;
                }
                if (index == literal.length) {
                    left -= index;
                    return true;
                }
                left -= index + 1;
            }
            return false;
        }

        boolean spent()
        {
            return left < 0;
        }
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
        return joinedLiteral(elements, _ -> true);
    }

    // The rarest of the runs the one-argument joinedLiteral compares that `eligible` accepts, or
    // null.
    private static byte[] joinedLiteral(List<Regexp> elements, Predicate<byte[]> eligible)
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
            rarest = rarerLiteral(rarest, run.toByteArray(), eligible);
            run.reset();
            run.writeBytes(exactAffix(element, true, 0));
        }
        return rarerLiteral(rarest, run.toByteArray(), eligible);
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
    // MINIMUM_LITERAL_LENGTH is no literal. A run longer than MAXIMUM_JOINED_LITERAL_LENGTH is
    // judged by its first bytes. A candidate counts only if `eligible` accepts it. `run` is the
    // caller's own, so it is kept as is.
    private static byte[] rarerLiteral(byte[] current, byte[] run, Predicate<byte[]> eligible)
    {
        if (run.length < MINIMUM_LITERAL_LENGTH) {
            return current;
        }
        byte[] candidate = run.length > MAXIMUM_JOINED_LITERAL_LENGTH ? Arrays.copyOf(run, MAXIMUM_JOINED_LITERAL_LENGTH) : run;
        if (!eligible.test(candidate)) {
            return current;
        }
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
        return new RequiredBytes(true, padded, null, null, null, null);
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
        return new RequiredBytes(false, bytes, masks, LiteralSearchKernel.sharing(Slices.wrappedBuffer(bytes)), null, null, longWindowsOnly, SHORT_WINDOW_LIMIT);
    }

    /// Returns this exact literal with its long-window search and, where every match ends with
    /// exact bytes, the search-end bound: the search [#lastIndexIn] runs for those bytes. Where
    /// they are the literal, one search serves both.
    RequiredBytes withLongWindowSearchAndBound()
    {
        if (suffix == null) {
            return withLongWindowSearch();
        }
        if (!isExactLiteral()) {
            throw new IllegalStateException("not an exact literal: " + this);
        }
        LiteralSearchKernel lastOccurrence = LiteralSearchKernel.sharingWithLastOccurrence(Slices.wrappedBuffer(suffix));
        LiteralSearchKernel literalSearch = suffix == bytes ? lastOccurrence : LiteralSearchKernel.sharing(Slices.wrappedBuffer(bytes));
        return new RequiredBytes(false, bytes, masks, literalSearch, suffix, lastOccurrence, longWindowsOnly, SHORT_WINDOW_LIMIT);
    }

    /// Returns these bytes, for the short-window check, with the search [#indexIn] runs on long
    /// windows for `literal`, an exact literal every match consumes that another literal route
    /// of the pattern does not search for; see [#literalBesideRoutes]. Such a search takes no
    /// search-end bound.
    RequiredBytes withBesideRouteSearch(byte[] literal)
    {
        if (isExactLiteral() && Arrays.equals(literal, bytes)) {
            return withLongWindowSearch();
        }
        return new RequiredBytes(oneOf, bytes, masks, LiteralSearchKernel.sharing(Slices.wrappedBuffer(literal)), null, null, longWindowsOnly, windowLimit);
    }

    /// Returns this literal with its long-window search but without the search-end bound
    /// [#withLongWindowSearchAndBound] adds.
    RequiredBytes withoutSearchEndBound()
    {
        if (!boundsSearchEnd()) {
            throw new IllegalStateException("no search-end bound: " + this);
        }
        return withLongWindowSearch();
    }

    /// Returns these bytes without the long-window search and the search-end bound, for the
    /// short-window check alone: this, where they have neither. A literal that serves long windows
    /// only has no short-window check to keep.
    RequiredBytes withoutLongWindowSearch()
    {
        if (longWindowsOnly) {
            throw new IllegalStateException("long windows only: " + this);
        }
        if (longWindowSearch == null && suffix == null) {
            return this;
        }
        return new RequiredBytes(oneOf, bytes, masks, null, null, null, false, windowLimit);
    }

    /// Whether the literal serves the long-window check alone: every match of its pattern spans
    /// at least [#SHORT_WINDOW_LIMIT] bytes, so no short window holds one, and the pattern keeps
    /// the literal only where compilation chooses the long-window check. Every short-window hook
    /// rejects a window shorter than the pattern's fewest match bytes before it scans for the
    /// bytes, so the short-window check never runs for it. Without a required prefix, which the
    /// long-window check excludes, those fewest bytes are the ones the analysis counted.
    boolean isLongWindowsOnly()
    {
        return longWindowsOnly;
    }

    /// The literal the long-window search finds, or null.
    String longWindowLiteralForDiagnostics()
    {
        return longWindowSearch == null ? null : new String(longWindowSearch.literalBytes(), ISO_8859_1);
    }

    /// Whether the compile chose the long-window check for this literal; see [#indexIn].
    boolean searchesLongWindows()
    {
        return longWindowSearch != null;
    }

    /// Whether the compile chose the search-end bound for this literal; see [#lastIndexIn].
    boolean boundsSearchEnd()
    {
        return suffixSearch != null;
    }

    /// Whether the bytes every match ends with hold the literal, for a literal with its search-end
    /// bound. Where they do not, a window that holds them may still lack the literal, so a search
    /// checks for it too; see `CompiledPattern.longWindowSearchEnd`.
    boolean suffixHoldsLiteral()
    {
        return suffixHoldsLiteral;
    }

    /// The number of bytes every match ends with, for a literal with its search-end bound.
    int suffixLength()
    {
        return suffix.length;
    }

    /// The exact bytes every match ends with, from the analysis or for a literal with its
    /// search-end bound, or null; only tests call it.
    byte[] suffixForDiagnostics()
    {
        return suffix == null ? null : suffix.clone();
    }

    /// Returns the Slice offset of the long-window literal's first occurrence wholly inside
    /// `[start, end)` of `text`, or -1, for bytes with their long-window search. Where vectors
    /// are available, a window of at least the fused vector scan's minimum takes the vector pair
    /// scan; a shorter one, or any window without vectors, takes the call-free word-at-a-time
    /// search. See [Prog#findLiteral].
    int indexIn(Slice text, int start, int end)
    {
        int base = text.byteArrayOffset();
        LiteralSearchKernel search = longWindowSearch;
        int found = Prog.findLiteral(text.byteArray(), base + start, end - start, search.literalBytes(), search);
        return found < 0 ? -1 : found - base;
    }

    /// Returns the Slice offset of the last occurrence wholly inside `[start, end)` of `text` of
    /// the bytes every match ends with, or -1, for a literal with its search-end bound. Every
    /// match in the window ends at or before that occurrence's end, and with none, the window
    /// holds no match. The search mirrors [#indexIn]'s; see [Prog#findLastLiteral].
    int lastIndexIn(Slice text, int start, int end)
    {
        int base = text.byteArrayOffset();
        int found = Prog.findLastLiteral(text.byteArray(), base + start, end - start, suffix, suffixSearch);
        return found < 0 ? -1 : found - base;
    }

    /// Whether the fact is up to three bytes of which every match holds one.
    boolean isOneOf()
    {
        return oneOf;
    }

    /// Whether the fact is a single required byte, which takes the single-byte scan and keeps its
    /// check of every window shorter than [#SHORT_WINDOW_LIMIT]. A pattern drops such a fact where
    /// it is the byte of a gated one-byte search that full-Slice find runs first; see
    /// `CompiledPattern.runsInnerLiteralSearchFirst`.
    boolean isSingleByte()
    {
        return singleByte;
    }

    /// The single required byte, as an unsigned value, or `-1` for any other fact.
    int singleByteValue()
    {
        return singleByte ? bytes[0] & 0xFF : -1;
    }

    /// The length below which the short-window check reads a window: [#SHORT_WINDOW_LIMIT], or
    /// less for a one-of set of two or three distinct bytes beside an inner literal search; see
    /// [#withWindowLimit].
    int windowLimit()
    {
        return windowLimit;
    }

    /// Returns this one-of set of two or three distinct bytes with the short-window check limited
    /// to windows shorter than `limit`, for a pattern whose inner literal search runs on the longer
    /// ones.
    RequiredBytes withWindowLimit(int limit)
    {
        if (!oneOf || singleByte) {
            throw new IllegalStateException("not a one-of set of two or three distinct bytes: " + this);
        }
        return new RequiredBytes(true, bytes, masks, null, null, null, false, limit);
    }

    /// Whether `[start, end)` of `text` holds the literal wholly, or one of the bytes.
    boolean occursIn(Slice text, int start, int end)
    {
        byte[] data = text.byteArray();
        int offset = text.byteArrayOffset() + start;
        if (oneOf) {
            return singleByte ? containsByte(data, offset, end - start) : containsOneOf(data, offset, end - start);
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

    // The scan of LiteralSearchKernel.findSingleByte and its tail, for presence only: two words
    // and one zero-byte test per word each step, then up to two more words, the last ending at the
    // window end, where it can overlap bytes already tested; a window shorter than a word is
    // compared byte by byte. That method needs a kernel of its own for the byte and finds the
    // first hit's offset, which presence does not need.
    private boolean containsByte(byte[] data, int offset, int length)
    {
        int end = offset + length;
        if (length < Long.BYTES) {
            byte value = bytes[0];
            for (int position = offset; position < end; position++) {
                if (data[position] == value) {
                    return true;
                }
            }
            return false;
        }
        long broadcast = firstBroadcast;
        long low = lowBits;
        int wordEnd = offset + (length / (2 * Long.BYTES)) * (2 * Long.BYTES);
        int position = offset;
        for (; position < wordEnd; position += 2 * Long.BYTES) {
            long firstDifference = ((long) LONG_HANDLE.get(data, position)) ^ broadcast;
            long secondDifference = ((long) LONG_HANDLE.get(data, position + Long.BYTES)) ^ broadcast;
            if (((((firstDifference - low) & ~firstDifference) | ((secondDifference - low) & ~secondDifference)) & HIGH_BITS) != 0) {
                return true;
            }
        }
        if (end - position > Long.BYTES) {
            long difference = ((long) LONG_HANDLE.get(data, position)) ^ broadcast;
            if ((((difference - low) & ~difference) & HIGH_BITS) != 0) {
                return true;
            }
        }
        if (position < end) {
            long difference = ((long) LONG_HANDLE.get(data, end - Long.BYTES)) ^ broadcast;
            return (((difference - low) & ~difference) & HIGH_BITS) != 0;
        }
        return false;
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
        long low = lowBits;
        int lastWord = end - Long.BYTES;
        for (int position = offset; position < lastWord; position += Long.BYTES) {
            if (holdsAny((long) LONG_HANDLE.get(data, position), first, second, third, low)) {
                return true;
            }
        }
        return holdsAny((long) LONG_HANDLE.get(data, lastWord), first, second, third, low);
    }

    private static boolean holdsAny(long word, long first, long second, long third, long low)
    {
        long firstDifference = word ^ first;
        long secondDifference = word ^ second;
        long thirdDifference = word ^ third;
        return ((((firstDifference - low) & ~firstDifference) |
                ((secondDifference - low) & ~secondDifference) |
                ((thirdDifference - low) & ~thirdDifference)) & HIGH_BITS) != 0;
    }

    long estimatedRetainedSize()
    {
        // A long-window search for `bytes` shares that array, and the suffix search shares
        // `suffix`. Their own estimates count those arrays too, so each is counted once here.
        // Where the suffix is the literal, the two searches are one.
        long suffixSize = suffix == null || suffix == bytes ? 0 : SizeOf.sizeOf(suffix);
        long suffixSearchSize = suffixSearch == null || suffixSearch == longWindowSearch ? 0 : suffixSearch.estimatedRetainedSize() - suffixSize;
        long searchSize = longWindowSearch == null ? 0 : longWindowSearch.estimatedRetainedSize() - (longWindowSearch.literalBytes() == bytes ? SizeOf.sizeOf(bytes) : 0);
        return INSTANCE_SIZE + SizeOf.sizeOf(bytes) + SizeOf.sizeOf(masks) + suffixSize + suffixSearchSize + searchSize;
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
