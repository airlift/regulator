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

import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * An immutable collection of patterns with an external literal-atom prefilter.
 * <p>
 * A caller searches the canonicalized input returned by {@link #canonicalizeText(Slice)} for the
 * byte strings in {@link #atoms()}, then supplies the IDs of the atoms found in the input. The
 * prefilter rejects impossible patterns before the remaining candidates are verified by RE2.
 */
public final class FilteredRe2
{
    private static final int DEFAULT_MINIMUM_ATOM_LENGTH = 3;

    private final List<Re2> patterns;
    private final PrefilterTree prefilterTree;
    private final List<Slice> atoms;
    private final Re2.Options.Encoding encoding;

    private FilteredRe2(
            List<Re2> patterns,
            PrefilterTree prefilterTree,
            List<Slice> atoms,
            Re2.Options.Encoding encoding)
    {
        this.patterns = List.copyOf(patterns);
        this.prefilterTree = requireNonNull(prefilterTree, "prefilterTree is null");
        this.atoms = atoms.stream()
                .map(Slice::copy)
                .toList();
        this.encoding = requireNonNull(encoding, "encoding is null");
    }

    /**
     * Creates a single-use builder with the default three-byte minimum atom length.
     */
    public static Builder builder()
    {
        return builder(DEFAULT_MINIMUM_ATOM_LENGTH);
    }

    /**
     * Creates a single-use builder with the supplied non-negative minimum atom length.
     */
    public static Builder builder(int minimumAtomLength)
    {
        return new Builder(minimumAtomLength);
    }

    /**
     * Returns the number of compiled patterns.
     */
    public int patternCount()
    {
        return patterns.size();
    }

    /**
     * Returns the compiled pattern with the supplied pattern ID.
     */
    public Re2 pattern(int patternId)
    {
        return patterns.get(patternId);
    }

    /**
     * Returns copies of the distinct canonical atoms that the caller must search for.
     * The atoms are matched literally against {@link #canonicalizeText(Slice)}.
     */
    public List<Slice> atoms()
    {
        return atoms.stream()
                .map(Slice::copy)
                .toList();
    }

    /**
     * Returns the encoding shared by every pattern in this collection.
     */
    public Re2.Options.Encoding encoding()
    {
        return encoding;
    }

    /**
     * Returns a canonical copy of {@code text} suitable for literal searches using {@link #atoms()}.
     * <p>
     * UTF-8 text uses {@code Character.toLowerCase(Character.toUpperCase(codePoint))} for each valid
     * Unicode code point. Invalid UTF-8 bytes are preserved. Latin-1 text lowercases ASCII letters
     * and preserves every other byte.
     */
    public Slice canonicalizeText(Slice text)
    {
        requireNonNull(text, "text is null");
        DynamicSliceOutput output = new DynamicSliceOutput(text.length());
        byte[] bytes = text.byteArray();
        int index = text.byteArrayOffset();
        int end = index + text.length();

        if (encoding == Re2.Options.Encoding.LATIN1) {
            for (; index < end; index++) {
                output.writeByte(canonicalizeLatin1Byte(bytes[index] & 0xFF));
            }
            return output.slice();
        }

        while (index < end) {
            long decoded = Utf8.decode(bytes, index, end);
            int width = Utf8.decodedWidth(decoded);
            int codePoint = Utf8.decodedCodePoint(decoded);
            if (width == 1 && codePoint == Utf8.RUNE_ERROR && (bytes[index] & 0x80) != 0) {
                output.writeByte(bytes[index] & 0xFF);
            }
            else {
                Utf8.encode(output, canonicalizeUnicodeCodePoint(codePoint));
            }
            index += width;
        }
        return output.slice();
    }

    /**
     * Canonicalizes one Unicode code point using the rule used to construct UTF-8 atoms.
     */
    public static int canonicalizeUnicodeCodePoint(int codePoint)
    {
        if (!Character.isValidCodePoint(codePoint) ||
                (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
            throw new IllegalArgumentException("invalid Unicode code point: " + codePoint);
        }
        return Character.toLowerCase(Character.toUpperCase(codePoint));
    }

    /**
     * Canonicalizes one unsigned Latin-1 byte using the rule used to construct Latin-1 atoms.
     */
    public static int canonicalizeLatin1Byte(int value)
    {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("not an unsigned byte: " + value);
        }
        if (value >= 'A' && value <= 'Z') {
            return value + ('a' - 'A');
        }
        return value;
    }

    /**
     * Returns ascending pattern IDs that may match based only on the supplied atom IDs.
     */
    public int[] potentialPatternIds(int[] matchedAtomIds)
    {
        requireNonNull(matchedAtomIds, "matchedAtomIds is null");
        return prefilterTree.regexpMatches(matchedAtomIds);
    }

    /**
     * Returns ascending IDs of every pattern that matches after atom prefiltering.
     */
    public int[] matchingPatternIds(Slice text, int[] matchedAtomIds)
    {
        requireNonNull(text, "text is null");
        int[] potentialPatternIds = potentialPatternIds(matchedAtomIds);
        int[] matchingPatternIds = new int[potentialPatternIds.length];
        int matchingPatternCount = 0;
        for (int patternId : potentialPatternIds) {
            if (patterns.get(patternId).find(text)) {
                matchingPatternIds[matchingPatternCount] = patternId;
                matchingPatternCount++;
            }
        }
        return Arrays.copyOf(matchingPatternIds, matchingPatternCount);
    }

    public int firstMatchingPatternId(Slice text, int[] matchedAtomIds)
    {
        requireNonNull(text, "text is null");
        for (int patternId : potentialPatternIds(matchedAtomIds)) {
            if (patterns.get(patternId).find(text)) {
                return patternId;
            }
        }
        return -1;
    }

    public static final class Builder
    {
        private final List<Re2> patterns = new ArrayList<>();
        private final PrefilterTree prefilterTree;
        private Re2.Options.Encoding encoding;
        private boolean built;

        private Builder(int minimumAtomLength)
        {
            if (minimumAtomLength < 0) {
                throw new IllegalArgumentException("minimumAtomLength is negative: " + minimumAtomLength);
            }
            prefilterTree = new PrefilterTree(minimumAtomLength);
        }

        /**
         * Compiles and adds a copied pattern, returning its stable pattern ID. If validation or
         * compilation fails, the builder remains unchanged.
         */
        public int add(Slice pattern, Re2.Options options)
        {
            requireNotBuilt();
            requireNonNull(pattern, "pattern is null");
            requireNonNull(options, "options is null");
            Re2.Options.Encoding patternEncoding = options.encoding();
            if (encoding != null && encoding != patternEncoding) {
                throw new IllegalArgumentException("all FilteredRe2 patterns must use the same encoding");
            }

            Re2 compiledPattern = Re2.compile(pattern, options);
            Prefilter prefilter = Prefilter.fromRe2(compiledPattern);

            int patternId = patterns.size();
            patterns.add(compiledPattern);
            prefilterTree.add(prefilter);
            if (encoding == null) {
                encoding = patternEncoding;
            }
            return patternId;
        }

        /**
         * Returns the number of patterns added so far.
         */
        public int size()
        {
            requireNotBuilt();
            return patterns.size();
        }

        /**
         * Compiles an immutable collection and invalidates this builder.
         */
        public FilteredRe2 build()
        {
            requireNotBuilt();
            built = true;
            List<Slice> atoms = new ArrayList<>();
            prefilterTree.compile(atoms);
            Re2.Options.Encoding collectionEncoding =
                    encoding == null ? Re2.Options.Encoding.UTF8 : encoding;
            return new FilteredRe2(patterns, prefilterTree, atoms, collectionEncoding);
        }

        private void requireNotBuilt()
        {
            if (built) {
                throw new IllegalStateException("FilteredRe2 builder has already built a collection");
            }
        }
    }
}
