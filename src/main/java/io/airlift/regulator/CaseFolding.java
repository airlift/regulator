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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.airlift.regulator.UnicodeDatabase.CodePointMappings;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static io.airlift.regulator.CharClass.RUNEMAX;
import static java.util.Objects.requireNonNull;

/// One case-folding rule and its data. Each compiled pattern carries the rule of its language,
/// and the language's front end decides which constructs fold with it.
///
/// A rule is a simple fold, which maps each code point to the code point of its case class, plus
/// an optional set of multi-character folds. Code points with the same simple fold form an orbit,
/// which [#cycleFoldRune(int)] visits in ascending order, wrapping from the largest member to
/// the smallest. Instances are shared and immutable. On first use, a rule builds its simple folds,
/// its orbit successors, and its sorted case-folded runes together from the sparse list of code
/// points its simple fold changes, which comes from the pinned [UnicodeDatabase]; each table
/// holds only the few dozen blocks of 256 code points that have a case partner. The
/// multi-character folds, and the closing folds of Java's case-insensitive ranges, are built
/// separately, on first use.
///
/// Each table field is plain rather than volatile, so the compiler's fold loops read it without a
/// fence. A thread that reads the field before another thread's write is visible sees null and
/// takes the synchronized path; one that sees the table sees it fully built, because every table
/// holds its data in final fields.
final class CaseFolding
{
    /// Trino's full case folding, CaseFolding.txt statuses C and F from the pinned
    /// [UnicodeDatabase], the rule Joni folds with. It shares the orbits and tables of the
    /// RE2 rule, because each status S fold joins code points whose status F folds are equal.
    static final CaseFolding TRINO = new CaseFolding("Unicode " + UnicodeDatabase.UNICODE_VERSION + " full case folding", () -> SimpleCaseFolding.TABLES, true);

    /// Java's case classes, `toLowerCase(toUpperCase(codePoint))` as `java.util.regex`
    /// folds them, computed from the simple case mappings of the pinned [UnicodeDatabase]. A
    /// literal character, alone or in a class, matches its whole case class; a range matches by
    /// the narrower rule of [#addJavaRangeCaseMembers(CharClassBuilder,int,int)].
    static final CaseFolding JAVA = new CaseFolding("Unicode " + UnicodeDatabase.UNICODE_VERSION + " case classes", () -> new FoldTables(javaSimpleFolds()), false);

    /// RE2's simple case folding, CaseFolding.txt statuses C and S from the pinned
    /// [UnicodeDatabase], as upstream RE2 folds with its own Unicode tables.
    static final CaseFolding RE2 = new CaseFolding("Unicode " + UnicodeDatabase.UNICODE_VERSION + " simple case folding", () -> SimpleCaseFolding.TABLES, false);

    private final String name;
    private final Supplier<FoldTables> tables;
    private final boolean multiCharacterFolds;
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "Each lazy table publishes through its final fields; a reader that sees null takes the synchronized path")
    private FoldTables foldTables;
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "Each lazy table publishes through its final fields; a reader that sees null takes the synchronized path")
    private UnicodeFullCaseFold fullCaseFold;

    private CaseFolding(String name, Supplier<FoldTables> tables, boolean multiCharacterFolds)
    {
        this.name = requireNonNull(name, "name is null");
        this.tables = requireNonNull(tables, "tables is null");
        this.multiCharacterFolds = multiCharacterFolds;
    }

    /// Returns the code point that every member of the orbit of `codePoint` folds to.
    int simpleFold(int codePoint)
    {
        if (codePoint < 0 || codePoint > RUNEMAX) {
            return codePoint;
        }
        return foldTables().simpleFolds.get(codePoint);
    }

    /// Returns the table of [#simpleFold(int)] for every code point from 0 through
    /// `RUNEMAX`, so callers can reuse the table instead of calling `simpleFold` for
    /// each code point.
    CodePointTable simpleFoldTable()
    {
        return foldTables().simpleFolds;
    }

    /// Returns [#simpleFold(int)] of each ASCII code point, indexed by code point. Every rule
    /// folds ASCII to ASCII. Callers must not modify the returned array.
    byte[] asciiSimpleFolds()
    {
        return foldTables().asciiSimpleFolds;
    }

    /// Returns the next larger member of the orbit of `rune`, wrapping to the smallest.
    int cycleFoldRune(int rune)
    {
        if (rune < 0 || rune > RUNEMAX) {
            return rune;
        }
        return foldTables().orbitSuccessors.get(rune);
    }

    /// Adds to `builder` the other members of the orbit of each rune from `low` through
    /// `high`, one single-rune range per member, in ascending rune and orbit order.
    void addOtherOrbitMembers(CharClassBuilder builder, int low, int high)
    {
        FoldTables foldTables = foldTables();
        CodePointTable orbitSuccessors = foldTables.orbitSuccessors;
        int[] caseFoldedRunes = foldTables.caseFoldedRunes;
        int index = Arrays.binarySearch(caseFoldedRunes, low);
        if (index < 0) {
            index = -index - 1;
        }
        for (; index < caseFoldedRunes.length && caseFoldedRunes[index] <= high; index++) {
            int rune = caseFoldedRunes[index];
            for (int foldedRune = orbitSuccessors.get(rune); foldedRune != rune; foldedRune = orbitSuccessors.get(foldedRune)) {
                builder.addRange(foldedRune, foldedRune);
            }
        }
    }

    /// Adds to `builder` the code points that a range from `low` through `high` matches
    /// case-insensitively with `UNICODE_CASE`, one single-rune range per code point. The caller
    /// adds the range itself.
    ///
    /// `Pattern` matches a character when it, its upper case, or the lower case of its upper
    /// case is in the range, or when its upper case or the lower case of its upper case is a
    /// closing character of the range: the target of a [closing fold][JavaRangeClosingFolds]
    /// whose source the range holds and whose target it does not. Unlike a literal character, a
    /// range need not match its members' whole case classes, so `[K-K]` does not match the
    /// Kelvin sign. Every character the rule matches is in the case class of a code point in the
    /// range or of a closing character, because a character's upper case and the lower case of
    /// that are in its own class, so only the members of those classes are tested.
    static void addJavaRangeCaseMembers(CharClassBuilder builder, int low, int high)
    {
        FoldTables foldTables = JAVA.foldTables();
        CodePointMappings uppercase = UnicodeDatabase.simpleUppercaseMappings();
        int[] caseFoldedRunes = foldTables.caseFoldedRunes;
        int index = Arrays.binarySearch(caseFoldedRunes, low);
        if (index < 0) {
            index = -index - 1;
        }
        for (; index < caseFoldedRunes.length && caseFoldedRunes[index] <= high; index++) {
            addJavaRangeClassMembers(builder, foldTables, uppercase, caseFoldedRunes[index], low, high);
        }

        int[] sources = JavaRangeClosingFolds.FOLDS.codePoints();
        int[] targets = JavaRangeClosingFolds.FOLDS.values();
        int foldIndex = Arrays.binarySearch(sources, low);
        if (foldIndex < 0) {
            foldIndex = -foldIndex - 1;
        }
        for (; foldIndex < sources.length && sources[foldIndex] <= high; foldIndex++) {
            // A closing character's class matches by the same rule, with the closing character
            // as the range.
            int target = targets[foldIndex];
            if (!isInRange(target, low, high)) {
                addJavaRangeClassMembers(builder, foldTables, uppercase, target, target, target);
            }
        }
    }

    int[] fold(int[] runes)
    {
        return fullCaseFold().fold(runes);
    }

    CharClass simpleFoldClass(int rune)
    {
        return fullCaseFold().simpleFoldClass(rune);
    }

    List<UnicodeFullCaseFold.FoldToken> multiCharacterTokens()
    {
        return fullCaseFold().multiCharacterTokens();
    }

    List<UnicodeFullCaseFold.FoldToken> multiCharacterTokensStartingWith(int foldedRune)
    {
        return fullCaseFold().multiCharacterTokensStartingWith(foldedRune);
    }

    List<UnicodeFullCaseFold.FoldToken> multiCharacterTokens(CharClass characterClass)
    {
        return fullCaseFold().multiCharacterTokens(characterClass);
    }

    /// Returns whether matching `runes` needs a multi-character fold. A folding without
    /// multi-character folds never does.
    boolean requiresFullCaseFold(int[] runes)
    {
        return multiCharacterFolds && fullCaseFold().requiresFullCaseFold(runes);
    }

    int maximumTokenLength()
    {
        return fullCaseFold().maximumTokenLength();
    }

    UnicodeFullCaseFold.ByteLength byteLength(int[] runes)
    {
        return fullCaseFold().byteLength(runes);
    }

    @Override
    public String toString()
    {
        return name;
    }

    private FoldTables foldTables()
    {
        FoldTables cached = foldTables;
        if (cached != null) {
            return cached;
        }
        return createFoldTables();
    }

    private synchronized FoldTables createFoldTables()
    {
        if (foldTables == null) {
            foldTables = tables.get();
        }
        return foldTables;
    }

    private UnicodeFullCaseFold fullCaseFold()
    {
        UnicodeFullCaseFold cached = fullCaseFold;
        if (cached != null) {
            return cached;
        }
        return createFullCaseFold();
    }

    private synchronized UnicodeFullCaseFold createFullCaseFold()
    {
        if (!multiCharacterFolds) {
            throw new IllegalStateException(name + " has no multi-character folds");
        }
        if (fullCaseFold == null) {
            fullCaseFold = UnicodeFullCaseFold.withMultiCharacterFolds(this, UnicodeDatabase.fullCaseFolding());
        }
        return fullCaseFold;
    }

    // toLowerCase(toUpperCase(codePoint)) differs from the code point only where one of the two
    // mappings changes it.
    private static CodePointMappings javaSimpleFolds()
    {
        CodePointMappings uppercase = UnicodeDatabase.simpleUppercaseMappings();
        CodePointMappings lowercase = UnicodeDatabase.simpleLowercaseMappings();
        int[] candidates = IntStream.concat(Arrays.stream(uppercase.codePoints()), Arrays.stream(lowercase.codePoints()))
                .sorted()
                .distinct()
                .toArray();
        int[] sources = new int[candidates.length];
        int[] targets = new int[candidates.length];
        int count = 0;
        for (int codePoint : candidates) {
            int target = lowercase.map(uppercase.map(codePoint));
            if (target != codePoint) {
                sources[count] = codePoint;
                targets[count] = target;
                count++;
            }
        }
        return new CodePointMappings(Arrays.copyOf(sources, count), Arrays.copyOf(targets, count));
    }

    // Adds to the builder the members of the code point's case class that a range from low
    // through high matches, starting with the code point itself. Every member of a class has the
    // same lower case of upper case, so when that is in the range, the range matches the whole
    // class; otherwise it matches each member whose upper case is in the range.
    private static void addJavaRangeClassMembers(CharClassBuilder builder, FoldTables foldTables, CodePointMappings uppercase, int codePoint, int low, int high)
    {
        boolean wholeClass = isInRange(foldTables.simpleFolds.get(codePoint), low, high);
        int member = codePoint;
        do {
            if (wholeClass || isInRange(uppercase.map(member), low, high)) {
                builder.addRange(member, member);
            }
            member = foldTables.orbitSuccessors.get(member);
        }
        while (member != codePoint);
    }

    private static boolean isInRange(int codePoint, int low, int high)
    {
        return low <= codePoint && codePoint <= high;
    }

    /// Holds the closing folds of Java's case-insensitive ranges, sorted by source, as the JDK's
    /// `GenerateCaseFolding` selects them for `jdk.internal.lang.CaseFolding`: each simple case
    /// fold, `CaseFolding.txt` status C, S, or T, whose target neither upper-cases nor
    /// lower-cases back to its source, such as the Kelvin sign's fold to k and final sigma's to
    /// sigma.
    private static final class JavaRangeClosingFolds
    {
        // CaseFolding.txt has two status T folds, I to dotless small i and dotted capital I to i,
        // and the database omits both. The generator selects dotted capital I to i. It drops I to
        // dotless small i, because dotless small i upper-cases back to I, and adds the reverse,
        // dotless small i to I, by hand.
        private static final int[] JDK_SOURCES = {0x0130, 0x0131};
        private static final int[] JDK_TARGETS = {0x0069, 0x0049};

        private static final CodePointMappings FOLDS;

        static {
            CodePointMappings simpleCaseFolding = UnicodeDatabase.simpleCaseFolding();
            CodePointMappings uppercase = UnicodeDatabase.simpleUppercaseMappings();
            CodePointMappings lowercase = UnicodeDatabase.simpleLowercaseMappings();
            int[] foldSources = simpleCaseFolding.codePoints();
            int[] foldTargets = simpleCaseFolding.values();
            long[] folds = new long[foldSources.length + JDK_SOURCES.length];
            int foldCount = 0;
            for (int index = 0; index < foldSources.length; index++) {
                int source = foldSources[index];
                int target = foldTargets[index];
                if (uppercase.map(target) != source && lowercase.map(target) != source) {
                    folds[foldCount++] = ((long) source << 32) | target;
                }
            }
            for (int index = 0; index < JDK_SOURCES.length; index++) {
                folds[foldCount++] = ((long) JDK_SOURCES[index] << 32) | JDK_TARGETS[index];
            }
            Arrays.sort(folds, 0, foldCount);

            int[] sources = new int[foldCount];
            int[] targets = new int[foldCount];
            for (int index = 0; index < foldCount; index++) {
                sources[index] = (int) (folds[index] >>> 32);
                targets[index] = (int) folds[index];
            }
            FOLDS = new CodePointMappings(sources, targets);
        }
    }

    // The RE2 and Trino rules share the tables of the database's simple case folding.
    private static final class SimpleCaseFolding
    {
        private static final FoldTables TABLES = new FoldTables(UnicodeDatabase.simpleCaseFolding());
    }

    /// The simple fold and orbit successor of every code point, the sorted runes whose orbit has
    /// another member, and the simple folds of ASCII.
    private static final class FoldTables
    {
        private final CodePointTable simpleFolds;
        private final CodePointTable orbitSuccessors;
        private final int[] caseFoldedRunes;
        private final byte[] asciiSimpleFolds;

        // The changed folds are the code points the simple fold changes, ascending, and the code
        // point each folds to; every other code point folds to itself.
        private FoldTables(CodePointMappings changedFolds)
        {
            int[] sources = changedFolds.codePoints();
            int[] targets = changedFolds.values();
            this.simpleFolds = new CodePointTable(sources, targets);

            // An orbit holds the sources that fold to one target, and the target itself unless it
            // folds elsewhere. Keying each member by its target sorts every orbit into one run in
            // ascending member order. A target that folds to itself is added once per source.
            long[] orbitMembers = new long[sources.length * 2];
            int orbitMemberCount = 0;
            for (int index = 0; index < sources.length; index++) {
                long orbit = (long) targets[index] << 32;
                orbitMembers[orbitMemberCount++] = orbit | sources[index];
                if (Arrays.binarySearch(sources, targets[index]) < 0) {
                    orbitMembers[orbitMemberCount++] = orbit | targets[index];
                }
            }
            Arrays.sort(orbitMembers, 0, orbitMemberCount);

            // Link each member of an orbit with another member to the next larger member, and the
            // largest to the smallest, keyed by member.
            long[] successors = new long[orbitMemberCount];
            int successorCount = 0;
            int index = 0;
            while (index < orbitMemberCount) {
                long orbit = orbitMembers[index] >>> 32;
                int firstMember = (int) orbitMembers[index];
                int previousMember = firstMember;
                for (index++; index < orbitMemberCount && orbitMembers[index] >>> 32 == orbit; index++) {
                    int member = (int) orbitMembers[index];
                    if (member != previousMember) {
                        successors[successorCount++] = ((long) previousMember << 32) | member;
                        previousMember = member;
                    }
                }
                if (previousMember != firstMember) {
                    successors[successorCount++] = ((long) previousMember << 32) | firstMember;
                }
            }
            Arrays.sort(successors, 0, successorCount);

            int[] caseFoldedRunes = new int[successorCount];
            int[] nextRunes = new int[successorCount];
            for (int successor = 0; successor < successorCount; successor++) {
                caseFoldedRunes[successor] = (int) (successors[successor] >>> 32);
                nextRunes[successor] = (int) successors[successor];
            }
            this.orbitSuccessors = new CodePointTable(caseFoldedRunes, nextRunes);
            this.caseFoldedRunes = caseFoldedRunes;

            byte[] asciiSimpleFolds = new byte[0x80];
            for (int codePoint = 0; codePoint < 0x80; codePoint++) {
                int folded = simpleFolds.get(codePoint);
                if (folded >= 0x80) {
                    throw new IllegalStateException("ASCII code point " + codePoint + " folds outside ASCII to " + folded);
                }
                asciiSimpleFolds[codePoint] = (byte) folded;
            }
            this.asciiSimpleFolds = asciiSimpleFolds;
        }
    }
}
