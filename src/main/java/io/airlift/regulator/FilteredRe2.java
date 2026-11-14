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
import io.airlift.slice.SizeOf;
import io.airlift.slice.Slice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/// An immutable collection of patterns with an external literal-atom prefilter.
///
/// A caller searches the canonicalized input returned by [#canonicalizeText(Slice)] for the
/// byte strings in [#atoms()], then supplies the IDs of the atoms found in the input. The
/// prefilter rejects impossible patterns before the remaining candidates are verified by RE2.
public final class FilteredRe2
{
    private static final int DEFAULT_MINIMUM_ATOM_LENGTH = 3;
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(FilteredRe2.class);

    private final int minimumAtomLength;
    private final List<Re2> patterns;
    private final PrefilterTree prefilterTree;
    // What the prefilter tree retains, which never changes after the collection compiles.
    private final long prefilterTreeRetainedSize;
    private final List<Slice> atoms;
    private final Re2.Options.Encoding encoding;
    // The hash of the minimum atom length and the patterns, which decide equality, computed when
    // the collection compiles.
    private final int hash;

    private FilteredRe2(
            int minimumAtomLength,
            List<Re2> patterns,
            PrefilterTree prefilterTree,
            List<Slice> atoms,
            Re2.Options.Encoding encoding)
    {
        this.minimumAtomLength = minimumAtomLength;
        this.patterns = List.copyOf(patterns);
        this.prefilterTree = requireNonNull(prefilterTree, "prefilterTree is null");
        this.prefilterTreeRetainedSize = prefilterTree.estimatedRetainedSize();
        this.atoms = atoms.stream()
                .map(Slice::copy)
                .toList();
        this.encoding = requireNonNull(encoding, "encoding is null");
        this.hash = 31 * minimumAtomLength + this.patterns.hashCode();
    }

    /// Creates a single-use builder with the default three-byte minimum atom length.
    public static Builder builder()
    {
        return builder(DEFAULT_MINIMUM_ATOM_LENGTH);
    }

    /// Creates a single-use builder with the supplied non-negative minimum atom length.
    public static Builder builder(int minimumAtomLength)
    {
        return new Builder(minimumAtomLength);
    }

    /// Returns the number of compiled patterns.
    public int patternCount()
    {
        return patterns.size();
    }

    /// Returns the compiled pattern with the supplied pattern ID.
    public Re2 pattern(int patternId)
    {
        return patterns.get(patternId);
    }

    /// Returns copies of the distinct canonical atoms that the caller must search for.
    /// The atoms are matched literally against [#canonicalizeText(Slice)].
    public List<Slice> atoms()
    {
        return atoms.stream()
                .map(Slice::copy)
                .toList();
    }

    /// Returns an estimate of the heap bytes this collection retains now, plus the native memory of
    /// its live DFA pointer tables: each pattern's [Re2#retainedSizeInBytes()], with a byte
    /// distribution several patterns share counted once, the prefilter tree, and the atoms. Each
    /// pattern keeps its own memory budget, which [Re2#maxMemoryBytes()] returns. Rarely, when
    /// concurrent growth or resets replace a DFA's tables throughout a read, the read reports that
    /// DFA's whole state budget in place of what its cache holds: a conservative over-report, not a
    /// leak.
    public long retainedSizeInBytes()
    {
        long size = INSTANCE_SIZE + RetainedSizeSupport.unmodifiableListSize(patterns) + prefilterTreeRetainedSize;
        // Each pattern counts its custom byte distribution, which patterns added with the same
        // one share, so a shared distribution is counted once.
        Set<ByteFrequencies> countedFrequencies = null;
        for (Re2 pattern : patterns) {
            size += pattern.retainedSizeInBytes();
            ByteFrequencies frequencies = pattern.byteFrequencies();
            long frequenciesSize = frequencies.estimatedRetainedSize();
            if (frequenciesSize != 0) {
                if (countedFrequencies == null) {
                    countedFrequencies = Collections.newSetFromMap(new IdentityHashMap<>());
                }
                if (!countedFrequencies.add(frequencies)) {
                    size -= frequenciesSize;
                }
            }
        }
        // The atoms are a list from Stream.toList, which holds an array of exactly their count.
        if (!atoms.isEmpty()) {
            size += RetainedSizeSupport.instanceSize(atoms.getClass()) + SizeOf.sizeOfObjectArray(atoms.size());
        }
        for (Slice atom : atoms) {
            size += atom.getRetainedSize();
        }
        return size;
    }

    /// Returns the encoding shared by every pattern in this collection.
    public Re2.Options.Encoding encoding()
    {
        return encoding;
    }

    /// Returns a canonical copy of `text` suitable for literal searches using [#atoms()].
    ///
    /// UTF-8 text replaces each valid Unicode code point with its simple case fold under RE2's rule,
    /// Unicode 18.0.0 CaseFolding.txt statuses C and S. Invalid UTF-8 bytes are preserved. Latin-1
    /// text lowercases ASCII letters and preserves every other byte.
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

        // ASCII folds to ASCII, so an ASCII byte is replaced without decoding. Any other byte that
        // decodes alone is invalid and preserved; every longer decode is a valid scalar value, which
        // indexes the table directly.
        byte[] asciiSimpleFolds = CaseFolding.RE2.asciiSimpleFolds();
        CodePointTable simpleFolds = CaseFolding.RE2.simpleFoldTable();
        while (index < end) {
            byte value = bytes[index];
            if (value >= 0) {
                output.writeByte(asciiSimpleFolds[value]);
                index++;
                continue;
            }
            long decoded = Utf8.decode(bytes, index, end);
            int width = Utf8.decodedWidth(decoded);
            if (width == 1) {
                output.writeByte(value);
            }
            else {
                Utf8.encode(output, simpleFolds.get(Utf8.decodedCodePoint(decoded)));
            }
            index += width;
        }
        return output.slice();
    }

    /// Canonicalizes one Unicode code point using the rule used to construct UTF-8 atoms.
    public static int canonicalizeUnicodeCodePoint(int codePoint)
    {
        if (!Character.isValidCodePoint(codePoint) ||
                (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
            throw new IllegalArgumentException("invalid Unicode code point: " + codePoint);
        }
        return CaseFolding.RE2.simpleFold(codePoint);
    }

    /// Canonicalizes one unsigned Latin-1 byte using the rule used to construct Latin-1 atoms.
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

    /// Returns ascending pattern IDs that may match based only on the supplied atom IDs.
    public int[] potentialPatternIds(int[] matchedAtomIds)
    {
        requireNonNull(matchedAtomIds, "matchedAtomIds is null");
        return prefilterTree.regexpMatches(matchedAtomIds);
    }

    /// Returns ascending IDs of every pattern that matches after atom prefiltering.
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

    /// Returns whether `other` is a collection built with the same minimum atom length from
    /// equal patterns, added in the same order. Patterns compare as [Re2#equals(Object)] does, so
    /// each pattern's options take part, and the atoms and encoding follow from these inputs.
    @Override
    public boolean equals(Object other)
    {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FilteredRe2 that)) {
            return false;
        }
        return hash == that.hash &&
                minimumAtomLength == that.minimumAtomLength &&
                patterns.equals(that.patterns);
    }

    @Override
    public int hashCode()
    {
        return hash;
    }

    public static final class Builder
    {
        private final int minimumAtomLength;
        private final List<Re2> patterns = new ArrayList<>();
        private final PrefilterTree prefilterTree;
        private Re2.Options.Encoding encoding;
        private boolean built;

        private Builder(int minimumAtomLength)
        {
            if (minimumAtomLength < 0) {
                throw new IllegalArgumentException("minimumAtomLength is negative: " + minimumAtomLength);
            }
            this.minimumAtomLength = minimumAtomLength;
            prefilterTree = new PrefilterTree(minimumAtomLength);
        }

        /// Compiles and adds a copied pattern, returning its stable pattern ID. If validation or
        /// compilation fails, the builder remains unchanged.
        public int add(Slice pattern, Re2.Options options)
        {
            requireNotBuilt();
            requireNonNull(pattern, "pattern is null");
            requireNonNull(options, "options is null");
            Re2.Options.Encoding patternEncoding = options.encoding();
            if (encoding != null && encoding != patternEncoding) {
                throw new IllegalArgumentException("all FilteredRe2 patterns must use the same encoding");
            }

            Slice patternCopy = pattern.copy();
            int parseFlags = options.parseFlags();
            ParseResult parsed = RegexpParser.parse(patternCopy, parseFlags);
            Re2 compiledPattern = Re2.compileParsed(patternCopy, parsed, parseFlags, options);
            Prefilter prefilter = Prefilter.fromRegexp(parsed.regexp());

            int patternId = patterns.size();
            patterns.add(compiledPattern);
            prefilterTree.add(prefilter);
            if (encoding == null) {
                encoding = patternEncoding;
            }
            return patternId;
        }

        /// Returns the number of patterns added so far.
        public int size()
        {
            requireNotBuilt();
            return patterns.size();
        }

        /// Compiles an immutable collection and invalidates this builder.
        public FilteredRe2 build()
        {
            requireNotBuilt();
            built = true;
            List<Slice> atoms = new ArrayList<>();
            prefilterTree.compile(atoms);
            Re2.Options.Encoding collectionEncoding =
                    encoding == null ? Re2.Options.Encoding.UTF8 : encoding;
            return new FilteredRe2(minimumAtomLength, patterns, prefilterTree, atoms, collectionEncoding);
        }

        private void requireNotBuilt()
        {
            if (built) {
                throw new IllegalStateException("FilteredRe2 builder has already built a collection");
            }
        }
    }
}
