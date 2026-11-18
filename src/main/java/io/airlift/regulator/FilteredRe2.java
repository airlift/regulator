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

/// An immutable collection of patterns with a literal-atom prefilter.
///
/// [#matchingPatternIds(Slice)] and [#firstMatchResult(Slice)] search the input for the atoms
/// themselves, with an atom search the collection builds. Alternatively, a caller searches the
/// canonicalized input returned by [#canonicalizeText(Slice)] for the byte strings in [#atoms()],
/// then supplies the IDs of the atoms found in the input. Either way, the prefilter rejects
/// impossible patterns before the remaining candidates are verified by RE2. When the atom search
/// exceeds the budget set by [Builder#setAtomSearchMaxMemory(long)], the collection builds without
/// it, and only the methods taking found atom IDs match.
public final class FilteredRe2
{
    /// A pattern ID and the match that pattern finds.
    public record PatternMatch(int patternId, MatchResult result)
    {
        public PatternMatch
        {
            requireNonNull(result, "result is null");
        }
    }

    private static final int DEFAULT_MINIMUM_ATOM_LENGTH = 3;
    // No atom search budget compiles the atoms.
    private static final long ATOM_SEARCH_FITS_NO_BUDGET = -1;
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(FilteredRe2.class);

    private final int minimumAtomLength;
    private final List<Re2> patterns;
    private final PrefilterTree prefilterTree;
    // What the prefilter tree retains, which never changes after the collection compiles.
    private final long prefilterTreeRetainedSize;
    private final List<Slice> atoms;
    private final Re2.Options.Encoding encoding;
    private final long atomSearchMaxMemory;
    // A FIND set whose member ID is each atom's index in atoms, or null when there are no atoms or
    // the set exceeded atomSearchMaxMemory. Null with atoms means the methods that search the atoms
    // themselves cannot run.
    private final Re2Set atomSearch;
    // The lower bound on the budget an atom search that exceeded atomSearchMaxMemory needs, as its
    // compile proved it; ATOM_SEARCH_FITS_NO_BUDGET when its program exceeds the compiler's
    // instruction limit, which no budget lifts; or 0 when no bound is known or the atom search was
    // built. Only the number is kept: the failed compile's exception can hold compiler state.
    private final long atomSearchRequiredMaxMemory;
    // The hash of the minimum atom length, the atom search budget, and the patterns, which decide
    // equality, computed when the collection compiles.
    private final int hash;

    private FilteredRe2(
            int minimumAtomLength,
            List<Re2> patterns,
            PrefilterTree prefilterTree,
            List<Slice> atoms,
            Re2.Options.Encoding encoding,
            long atomSearchMaxMemory,
            Re2Set atomSearch,
            long atomSearchRequiredMaxMemory)
    {
        this.minimumAtomLength = minimumAtomLength;
        this.patterns = List.copyOf(patterns);
        this.prefilterTree = requireNonNull(prefilterTree, "prefilterTree is null");
        this.prefilterTreeRetainedSize = prefilterTree.estimatedRetainedSize();
        this.atoms = atoms.stream()
                .map(Slice::copy)
                .toList();
        this.encoding = requireNonNull(encoding, "encoding is null");
        this.atomSearchMaxMemory = atomSearchMaxMemory;
        this.atomSearch = atomSearch;
        this.atomSearchRequiredMaxMemory = atomSearchRequiredMaxMemory;
        this.hash = 31 * (31 * minimumAtomLength + Long.hashCode(atomSearchMaxMemory)) + this.patterns.hashCode();
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

    /// Returns copies of the distinct canonical atoms that a caller supplying found atom IDs must
    /// search for. The atoms are matched literally against [#canonicalizeText(Slice)].
    public List<Slice> atoms()
    {
        return atoms.stream()
                .map(Slice::copy)
                .toList();
    }

    /// Returns an estimate of the heap bytes this collection retains now, plus the native memory of
    /// its live DFA pointer tables: each pattern's [Re2#retainedSizeInBytes()], with a byte
    /// distribution several patterns share counted once, the prefilter tree, the atoms, and the
    /// atom search's [Re2Set#retainedSizeInBytes()]. Each pattern keeps its own memory budget, which
    /// [Re2#maxMemoryBytes()] returns, and the atom search keeps the budget
    /// [Builder#setAtomSearchMaxMemory(long)] sets. Rarely, when
    /// concurrent growth or resets replace a DFA's tables throughout a read, the read reports that
    /// DFA's whole state budget in place of what its cache holds: a conservative over-report, not a
    /// leak.
    public long retainedSizeInBytes()
    {
        long size = INSTANCE_SIZE + RetainedSizeSupport.unmodifiableListSize(patterns) + prefilterTreeRetainedSize +
                (atomSearch == null ? 0 : atomSearch.retainedSizeInBytes());
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

    /// Returns ascending IDs of every pattern that matches `text`, searching the atoms itself.
    ///
    /// The atom search reads a [canonical copy][#canonicalizeText(Slice)] of `text`, and the
    /// candidates the prefilter accepts are verified against `text` itself, as
    /// [#matchingPatternIds(Slice,int\[\])] verifies them.
    ///
    /// @throws IllegalStateException if the collection's atom search exceeded its budget
    /// @throws RegexpMatchMemoryLimitException if the atom search exhausts its memory budget; no
    ///         partial result is returned
    public int[] matchingPatternIds(Slice text)
    {
        return matchingPatternIds(text, matchedAtomIds(text));
    }

    /// Returns the first pattern in ID order that matches `text`, with its match, or `null` when no
    /// pattern matches, searching the atoms itself.
    ///
    /// The atom search reads a [canonical copy][#canonicalizeText(Slice)] of `text`, and the
    /// candidates the prefilter accepts are searched in `text` itself, each once; the winner's
    /// [Re2#findResult(Slice)] is the returned match.
    ///
    /// @throws IllegalStateException if the collection's atom search exceeded its budget
    /// @throws RegexpMatchMemoryLimitException if the atom search exhausts its memory budget
    public PatternMatch firstMatchResult(Slice text)
    {
        for (int patternId : potentialPatternIds(matchedAtomIds(text))) {
            MatchResult result = patterns.get(patternId).findResult(text);
            if (result != null) {
                return new PatternMatch(patternId, result);
            }
        }
        return null;
    }

    // The IDs of the atoms found in the canonical copy of text, in no particular order; the
    // prefilter tree marks each atom's entries and needs no order.
    private int[] matchedAtomIds(Slice text)
    {
        requireNonNull(text, "text is null");
        // Checked first: a collection without its atom search must not report that no atom matched.
        if (atomSearch == null && !atoms.isEmpty()) {
            if (atomSearchRequiredMaxMemory == ATOM_SEARCH_FITS_NO_BUDGET) {
                throw new IllegalStateException("FilteredRe2 atom search over the collection's %s atoms exceeds the compiler's instruction limit, so no atomSearchMaxMemory compiles it; only the methods taking found atom IDs work".formatted(atoms.size()));
            }
            String required = atomSearchRequiredMaxMemory > 0 ? " and needs at least %s bytes".formatted(atomSearchRequiredMaxMemory) : "";
            throw new IllegalStateException("FilteredRe2 atom search over the collection's %s atoms exceeds atomSearchMaxMemory (%s bytes)%s; rebuild the collection with a larger FilteredRe2.Builder.setAtomSearchMaxMemory".formatted(atoms.size(), atomSearchMaxMemory, required));
        }
        if (atomSearch == null) {
            return new int[0];
        }
        return atomSearch.matchingPatternIds(canonicalizeText(text));
    }

    // The atom search, or null when there are no atoms or it exceeded its budget.
    Re2Set atomSearchForDiagnostics()
    {
        return atomSearch;
    }

    /// Returns whether `other` is a collection built with the same minimum atom length and atom
    /// search budget from equal patterns, added in the same order. Patterns compare as
    /// [Re2#equals(Object)] does, so each pattern's options take part, and the atoms and encoding
    /// follow from these inputs. The atom search budget takes part as an [Re2Set]'s budget does.
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
                atomSearchMaxMemory == that.atomSearchMaxMemory &&
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
        private long atomSearchMaxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
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

        /// Sets the positive compilation and DFA-cache memory budget, in bytes, of the atom search
        /// that [FilteredRe2#matchingPatternIds(Slice)] and [FilteredRe2#firstMatchResult(Slice)]
        /// run. The default is [Re2.Options#DEFAULT_MAX_MEMORY], a pattern's default budget. When
        /// the atom search exceeds this budget, the collection still builds without it, and the
        /// methods taking found atom IDs still work, but [FilteredRe2#matchingPatternIds(Slice)]
        /// and [FilteredRe2#firstMatchResult(Slice)] throw [IllegalStateException]. Rebuilding
        /// the collection with a larger budget repairs that, unless the atom search exceeds the
        /// compiler's instruction limit, which no budget lifts.
        public Builder setAtomSearchMaxMemory(long bytes)
        {
            requireNotBuilt();
            if (bytes <= 0) {
                throw new IllegalArgumentException("atomSearchMaxMemory must be greater than zero: " + bytes);
            }
            atomSearchMaxMemory = bytes;
            return this;
        }

        /// Compiles an immutable collection, with the atom search over its atoms, and invalidates
        /// this builder. When the atom search exceeds the budget set by
        /// [#setAtomSearchMaxMemory(long)], the collection builds without it: the methods taking
        /// found atom IDs still work, and the methods that search the atoms themselves throw
        /// [IllegalStateException]. Rebuilding the collection with a larger budget repairs that,
        /// unless the atom search exceeds the compiler's instruction limit, which no budget lifts.
        public FilteredRe2 build()
        {
            requireNotBuilt();
            built = true;
            List<Slice> atoms = new ArrayList<>();
            prefilterTree.compile(atoms);
            Re2.Options.Encoding collectionEncoding =
                    encoding == null ? Re2.Options.Encoding.UTF8 : encoding;
            Re2Set atomSearch = null;
            long atomSearchRequiredMaxMemory = 0;
            try {
                atomSearch = buildAtomSearch(atoms, collectionEncoding);
            }
            catch (RegexpCompileMemoryLimitException e) {
                // The methods taking found atom IDs need no atom search, so the collection builds
                // without it. Any other compile failure of quoted atoms is a bug, and propagates.
                atomSearchRequiredMaxMemory = e.fitsNoBudget() ? ATOM_SEARCH_FITS_NO_BUDGET : e.requiredMaxMemoryBytes().orElse(0);
            }
            return new FilteredRe2(minimumAtomLength, patterns, prefilterTree, atoms, collectionEncoding, atomSearchMaxMemory, atomSearch, atomSearchRequiredMaxMemory);
        }

        // A FIND set with each atom, quoted, as the member whose ID is the atom's index. The
        // atoms are canonical bytes matched literally: a Latin-1 set reads each byte as one
        // character, and a UTF-8 atom holds only whole code points, which a UTF-8 set reads as
        // their bytes.
        private Re2Set buildAtomSearch(List<Slice> atoms, Re2.Options.Encoding collectionEncoding)
        {
            if (atoms.isEmpty()) {
                return null;
            }
            Re2.Options options = collectionEncoding == Re2.Options.Encoding.LATIN1 ? Re2.Options.latin1() : Re2.Options.defaults();
            Re2Set.Builder builder = Re2Set.builder(options.setMaxMemory(atomSearchMaxMemory), Re2Set.MatchMode.FIND);
            for (Slice atom : atoms) {
                builder.add(Re2.quote(atom));
            }
            return builder.build();
        }

        private void requireNotBuilt()
        {
            if (built) {
                throw new IllegalStateException("FilteredRe2 builder has already built a collection");
            }
        }
    }
}
