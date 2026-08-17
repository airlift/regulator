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

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * An immutable compiled set of patterns searched with a single many-match DFA traversal.
 * Pattern IDs are assigned by {@link Builder#add(Slice)} and remain stable after compilation.
 */
public final class Re2Set
{
    public enum MatchMode
    {
        /**
         * Find a match anywhere in the input.
         */
        FIND,
        /**
         * Match only at the beginning of the input.
         */
        LOOKING_AT,
        /**
         * Match the complete input.
         */
        FULL,
    }

    private final int size;
    private final Prog program;
    private final long maxMemoryBytes;

    private Re2Set(int size, Prog program, long maxMemoryBytes)
    {
        this.size = size;
        this.program = program;
        this.maxMemoryBytes = maxMemoryBytes;
    }

    /**
     * Creates a single-use builder. The supplied options are copied immediately.
     */
    public static Builder builder(Re2.Options options, MatchMode matchMode)
    {
        return new Builder(options, matchMode);
    }

    /**
     * Returns the number of compiled patterns.
     */
    public int size()
    {
        return size;
    }

    Dfa.DfaInstance dfaForDiagnostics()
    {
        return program == null ? null : program.cachedDfaIfPresent(Dfa.DfaInstance.Kind.MANY_MATCH);
    }

    /**
     * Returns whether at least one pattern matches without allocating result storage.
     *
     * @throws RegexpMatchMemoryLimitException if matching exhausts the configured memory budget
     */
    public boolean matchesAny(Slice text)
    {
        requireNonNull(text, "text is null");
        if (program == null) {
            return false;
        }

        Dfa.ManyMatchResult result = Dfa.searchMany(program, text, null);
        if (result == Dfa.ManyMatchResult.RESOURCE_EXHAUSTED) {
            throw new RegexpMatchMemoryLimitException(maxMemoryBytes);
        }
        return result == Dfa.ManyMatchResult.MATCH;
    }

    /**
     * Returns the IDs of every matching pattern. The order is unspecified.
     *
     * @throws RegexpMatchMemoryLimitException if matching exhausts the configured memory budget;
     *         no partial result is returned
     */
    public int[] matchingPatternIds(Slice text)
    {
        requireNonNull(text, "text is null");
        if (program == null) {
            return new int[0];
        }

        SparseSet matches = new SparseSet(size);
        Dfa.ManyMatchResult result = Dfa.searchMany(program, text, matches);
        if (result == Dfa.ManyMatchResult.RESOURCE_EXHAUSTED) {
            throw new RegexpMatchMemoryLimitException(maxMemoryBytes);
        }
        if (result == Dfa.ManyMatchResult.NO_MATCH || matches.isEmpty()) {
            return new int[0];
        }

        int[] patternIds = new int[matches.size()];
        for (int index = 0; index < matches.size(); index++) {
            patternIds[index] = matches.denseAt(index);
        }
        return patternIds;
    }

    public static final class Builder
    {
        private final Re2.Options options;
        private final MatchMode matchMode;
        private final List<Regexp> patterns = new ArrayList<>();
        private boolean built;

        private Builder(Re2.Options options, MatchMode matchMode)
        {
            this.options = requireNonNull(options, "options is null")
                    .copy()
                    .setNeverCapture(true);
            this.matchMode = requireNonNull(matchMode, "matchMode is null");
        }

        /**
         * Adds a copied pattern and returns its stable pattern ID. If validation or parsing fails,
         * the builder remains unchanged.
         */
        public int add(Slice pattern)
        {
            requireNotBuilt();
            requireNonNull(pattern, "pattern is null");

            int parseFlags = options.parseFlags();
            ParseResult result = RegexpParser.parse(pattern.copy(), parseFlags);
            int patternId = patterns.size();
            Regexp matchMarker = Regexp.haveMatch(parseFlags, patternId);
            Regexp regexp = result.regexp();

            if (regexp.op() == RegexpOp.CONCAT) {
                List<Regexp> subexpressions = new ArrayList<>(regexp.childCount() + 1);
                for (int i = 0; i < regexp.childCount(); i++) {
                    subexpressions.add(regexp.child(i));
                }
                subexpressions.add(matchMarker);
                patterns.add(Regexp.concat(parseFlags, subexpressions));
            }
            else {
                patterns.add(Regexp.concat(parseFlags, List.of(regexp, matchMarker)));
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
         * Compiles an immutable set and invalidates this builder.
         */
        public Re2Set build()
        {
            requireNotBuilt();
            built = true;

            int patternCount = patterns.size();
            if (patternCount == 0) {
                return new Re2Set(0, null, options.maxMemory());
            }

            int parseFlags = options.parseFlags();
            Regexp regexp = patternCount == 1
                    ? patterns.getFirst()
                    : Regexp.alternate(parseFlags, patterns);
            patterns.clear();

            Prog program = Compiler.compileSet(
                    regexp,
                    matchMode == MatchMode.FIND,
                    matchMode == MatchMode.FULL,
                    options.maxMemory());
            if (program.getCachedDfa(Dfa.DfaInstance.Kind.MANY_MATCH) == null) {
                throw new RegexpCompileMemoryLimitException(options.maxMemory());
            }
            return new Re2Set(patternCount, program, options.maxMemory());
        }

        private void requireNotBuilt()
        {
            if (built) {
                throw new IllegalStateException("Re2Set builder has already built a set");
            }
        }
    }
}
