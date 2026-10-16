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

import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;

import static io.airlift.regulator.CharClass.RUNEMAX;
import static java.util.Objects.requireNonNull;

/**
 * One case-folding rule and its data. Each compiled pattern carries the rule of its language,
 * and every case-insensitive construct of the pattern is folded with it.
 * <p>
 * A rule is a simple fold, which maps each code point to the code point of its case class, plus
 * an optional set of multi-character folds. Code points with the same simple fold form an orbit,
 * which {@link #cycleFoldRune(int)} visits in ascending order, wrapping from the largest member to
 * the smallest. Instances are shared and immutable; their tables are built lazily, once.
 * <p>
 * Each table field is plain rather than volatile, so the compiler's fold loops read it without a
 * fence. A thread that reads the field before another thread's write is visible sees null and
 * takes the synchronized path; one that sees the table sees it fully built, because every table
 * holds its data in final fields.
 */
final class CaseFolding
{
    /**
     * The JVM's case classes, {@code Character.toLowerCase(Character.toUpperCase(codePoint))}, and its
     * multi-character {@code String} case mappings. Every language folds with this one rule, so its
     * tables are shared.
     */
    private static final CaseFolding JVM = new CaseFolding("JVM case folding", CaseFolding::jvmSimpleFold, true);

    /**
     * The RE2 language default.
     */
    static final CaseFolding RE2 = JVM;

    /**
     * The Java language default.
     */
    static final CaseFolding JAVA = JVM;

    /**
     * The Trino language default, whose patterns also use the multi-character folds.
     */
    static final CaseFolding TRINO = JVM;

    private final String name;
    private final IntUnaryOperator simpleFold;
    private final boolean multiCharacterFolds;
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "Each lazy table publishes through its final fields; a reader that sees null takes the synchronized path")
    private Orbits orbits;
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "Each lazy table publishes through its final fields; a reader that sees null takes the synchronized path")
    private UnicodeFullCaseFold fullCaseFold;

    private CaseFolding(String name, IntUnaryOperator simpleFold, boolean multiCharacterFolds)
    {
        this.name = requireNonNull(name, "name is null");
        this.simpleFold = requireNonNull(simpleFold, "simpleFold is null");
        this.multiCharacterFolds = multiCharacterFolds;
    }

    /**
     * Returns the code point that every member of the orbit of {@code codePoint} folds to.
     */
    int simpleFold(int codePoint)
    {
        if (codePoint < 0 || codePoint > RUNEMAX) {
            return codePoint;
        }
        return simpleFold.applyAsInt(codePoint);
    }

    /**
     * Returns the next larger member of the orbit of {@code rune}, wrapping to the smallest.
     */
    int cycleFoldRune(int rune)
    {
        if (rune < 0 || rune > RUNEMAX) {
            return rune;
        }
        return orbits().next[rune];
    }

    /**
     * Adds to {@code builder} the other members of the orbit of each rune from {@code low} through
     * {@code high}, one single-rune range per member, in ascending rune and orbit order.
     */
    void addOtherOrbitMembers(CharClassBuilder builder, int low, int high)
    {
        Orbits orbits = orbits();
        int[] next = orbits.next;
        int[] caseFoldedRunes = orbits.caseFoldedRunes;
        int index = Arrays.binarySearch(caseFoldedRunes, low);
        if (index < 0) {
            index = -index - 1;
        }
        for (; index < caseFoldedRunes.length && caseFoldedRunes[index] <= high; index++) {
            int rune = caseFoldedRunes[index];
            for (int foldedRune = next[rune]; foldedRune != rune; foldedRune = next[foldedRune]) {
                builder.addRange(foldedRune, foldedRune);
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

    boolean requiresFullCaseFold(int[] runes)
    {
        return fullCaseFold().requiresFullCaseFold(runes);
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

    private Orbits orbits()
    {
        Orbits cached = orbits;
        if (cached != null) {
            return cached;
        }
        return createOrbits();
    }

    private synchronized Orbits createOrbits()
    {
        if (orbits == null) {
            orbits = new Orbits(simpleFold);
        }
        return orbits;
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
            fullCaseFold = UnicodeFullCaseFold.withJvmMultiCharacterFolds(this);
        }
        return fullCaseFold;
    }

    private static int jvmSimpleFold(int codePoint)
    {
        return Character.toLowerCase(Character.toUpperCase(codePoint));
    }

    /**
     * Dense orbit successors and the sorted runes whose orbit has another member.
     */
    private static final class Orbits
    {
        private final int[] next;
        private final int[] caseFoldedRunes;

        private Orbits(IntUnaryOperator simpleFold)
        {
            int[] first = new int[RUNEMAX + 1];
            int[] last = new int[RUNEMAX + 1];
            int[] next = new int[RUNEMAX + 1];
            Arrays.fill(first, -1);

            for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
                int foldedCodePoint = simpleFold.applyAsInt(codePoint);
                if (first[foldedCodePoint] < 0) {
                    first[foldedCodePoint] = codePoint;
                }
                else {
                    next[last[foldedCodePoint]] = codePoint;
                }
                last[foldedCodePoint] = codePoint;
            }

            for (int foldedCodePoint = 0; foldedCodePoint <= RUNEMAX; foldedCodePoint++) {
                if (first[foldedCodePoint] >= 0) {
                    next[last[foldedCodePoint]] = first[foldedCodePoint];
                }
            }

            int caseFoldedRuneCount = 0;
            for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
                if (next[codePoint] != codePoint) {
                    caseFoldedRuneCount++;
                }
            }
            int[] caseFoldedRunes = new int[caseFoldedRuneCount];
            int index = 0;
            for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
                if (next[codePoint] != codePoint) {
                    caseFoldedRunes[index++] = codePoint;
                }
            }
            this.next = next;
            this.caseFoldedRunes = caseFoldedRunes;
        }
    }
}
