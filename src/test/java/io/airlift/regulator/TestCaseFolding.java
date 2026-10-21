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

import io.airlift.regulator.UnicodeDatabase.CodePointMappings;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.function.IntPredicate;

import static io.airlift.regulator.CharClass.RUNEMAX;

public class TestCaseFolding
{
    // For every code point of every rule, the fold is idempotent, and the orbit cycle visits
    // exactly the code points with the same fold before returning to the start.
    @Test
    public void testSimpleFoldsFormOrbits()
    {
        for (CaseFolding caseFolding : new CaseFolding[] {CaseFolding.RE2, CaseFolding.JAVA, CaseFolding.TRINO}) {
            int[] foldCount = new int[RUNEMAX + 1];
            for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
                int fold = caseFolding.simpleFold(codePoint);
                if (caseFolding.simpleFold(fold) != fold) {
                    throw new AssertionError(String.format(Locale.ROOT, "%s: fold of U+%04X folds again", caseFolding, codePoint));
                }
                foldCount[fold]++;
            }

            for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
                int fold = caseFolding.simpleFold(codePoint);
                int orbitSize = 0;
                int member = codePoint;
                do {
                    if (caseFolding.simpleFold(member) != fold) {
                        throw new AssertionError(String.format(Locale.ROOT, "%s: orbit of U+%04X holds U+%04X with another fold", caseFolding, codePoint, member));
                    }
                    orbitSize++;
                    if (orbitSize > foldCount[fold]) {
                        throw new AssertionError(String.format(Locale.ROOT, "%s: orbit of U+%04X does not close", caseFolding, codePoint));
                    }
                    member = caseFolding.cycleFoldRune(member);
                }
                while (member != codePoint);

                if (orbitSize != foldCount[fold]) {
                    throw new AssertionError(String.format(
                            Locale.ROOT,
                            "%s: orbit of U+%04X has %s members, but %s code points share its fold",
                            caseFolding,
                            codePoint,
                            orbitSize,
                            foldCount[fold]));
                }
            }
        }
    }

    // The simple uppercase and lowercase of every code point are in its orbit. Simple case folding
    // gives dotted capital I and dotless small i no fold, so RE2 and Trino keep them apart from the
    // I and i their case mappings name; Java joins them, as java.util.regex does.
    @Test
    public void testCaseMappingsStayInFoldOrbit()
    {
        for (CaseFolding caseFolding : new CaseFolding[] {CaseFolding.RE2, CaseFolding.JAVA, CaseFolding.TRINO}) {
            for (CodePointMappings mappings : new CodePointMappings[] {UnicodeDatabase.simpleUppercaseMappings(), UnicodeDatabase.simpleLowercaseMappings()}) {
                for (int index = 0; index < mappings.codePoints().length; index++) {
                    int codePoint = mappings.codePoints()[index];
                    if (caseFolding != CaseFolding.JAVA && (codePoint == 0x130 || codePoint == 0x131)) {
                        continue;
                    }
                    int mapping = mappings.values()[index];
                    if (caseFolding.simpleFold(mapping) != caseFolding.simpleFold(codePoint)) {
                        throw new AssertionError(String.format(Locale.ROOT, "%s: case mapping U+%04X of U+%04X is outside its orbit", caseFolding, mapping, codePoint));
                    }
                }
            }
        }
    }

    // A case-insensitive match never changes whether a character is a word character: the members
    // of each fold orbit agree, for Java's \w and \b with UNICODE_CHARACTER_CLASS and for Trino's
    // \b. The word characters of RE2, of Trino's \w, and of Java without UNICODE_CHARACTER_CLASS
    // are ASCII, which the Kelvin sign's orbit leaves.
    @Test
    public void testWordCharactersAreClosedUnderCaseFolding()
    {
        assertClosedUnderCaseFolding(CaseFolding.JAVA, JavaUnicodeProperties.lookup("IsWord")::contains, "Java \\w");
        assertClosedUnderCaseFolding(CaseFolding.JAVA, UnicodeGroups::isWord, "Java \\b");
        assertClosedUnderCaseFolding(CaseFolding.TRINO, EmptyOp::isUnicodeWord, "Trino \\b");
    }

    // Each orbit is a cycle, so comparing every code point with its successor compares the whole
    // orbit.
    private static void assertClosedUnderCaseFolding(CaseFolding caseFolding, IntPredicate wordCharacters, String name)
    {
        for (int codePoint = 0; codePoint <= RUNEMAX; codePoint++) {
            int next = caseFolding.cycleFoldRune(codePoint);
            if (wordCharacters.test(codePoint) != wordCharacters.test(next)) {
                throw new AssertionError(String.format(Locale.ROOT, "%s: %s holds only one of U+%04X and U+%04X", caseFolding, name, codePoint, next));
            }
        }
    }
}
