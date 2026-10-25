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

import java.util.Map;

import static io.airlift.regulator.CharClass.RUNEMAX;

/// The character names of `Character.getName` and `Character.codePointOf`, which
/// Java's `\N{name}` reads, from the pinned Unicode database.
///
/// The JDK names a code point by its Name in UnicodeData.txt; a control code point by its
/// Unicode_1_Name, with the exceptions below; and any other assigned code point by its block's
/// identifier, with spaces for underscores, a space, and the code point in uppercase hexadecimal
/// without leading zeros, such as CJK UNIFIED IDEOGRAPHS 4E00. It accepts neither the aliases of
/// NameAliases.txt nor the names that Unicode derives for ranges, such as CJK UNIFIED
/// IDEOGRAPH-4E00.
final class JavaCharacterNames
{
    // The JDK build tool that writes the JDK's names,
    // make/jdk/src/classes/build/tools/generatecharacter/CharacterName.java, names U+0007 BEL
    // rather than its Unicode_1_Name BELL, which is the Name of U+1F514, and names three control
    // code points without a Unicode_1_Name by the figments of NameAliases.txt.
    private static final int BEL = 0x07;
    private static final Map<Integer, String> CONTROL_FIGMENTS = Map.of(
            0x80, "PADDING CHARACTER",
            0x81, "HIGH OCTET PRESET",
            0x99, "SINGLE GRAPHIC CHARACTER INTRODUCER");

    private JavaCharacterNames() {}

    /// Returns the name of `codePoint`, from 0 through `RUNEMAX`, as
    /// `Character.getName` does, or null if the code point is unassigned.
    static String getName(int codePoint)
    {
        String name = UnicodeDatabase.names().name(codePoint);
        if (name != null) {
            return name;
        }
        if (UnicodeGroups.generalCategory("Cc").contains(codePoint)) {
            name = controlName(codePoint);
            if (name != null) {
                return name;
            }
        }
        if (UnicodeGroups.generalCategory("Cn").contains(codePoint)) {
            return null;
        }
        String block = JavaUnicodeProperties.javaBlockIdentifier(codePoint);
        String hex = UnicodeCase.toUpperCase(Integer.toHexString(codePoint));
        return block == null ? hex : block.replace('_', ' ') + " " + hex;
    }

    /// Returns the code point that `Character.codePointOf(name)` returns, or -1 where it
    /// throws: the code point whose name is `name` without leading and trailing characters
    /// up to U+0020, as `String.trim` removes, and uppercased, as
    /// `toUpperCase(Locale.ROOT)` does.
    static int codePointOf(String name)
    {
        int start = 0;
        int end = name.length();
        while (start < end && name.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && name.charAt(end - 1) <= ' ') {
            end--;
        }
        String key = UnicodeCase.toUpperCase(name.substring(start, end));

        int codePoint = UnicodeDatabase.names().codePoint(key);
        if (codePoint >= 0) {
            return codePoint;
        }
        for (RuneRange range : UnicodeGroups.generalCategory("Cc").rangeView()) {
            for (int control = range.low(); control <= range.high(); control++) {
                if (key.equals(controlName(control))) {
                    return control;
                }
            }
        }

        // A name of a block and a code point, such as CJK UNIFIED IDEOGRAPHS 4E00.
        int space = key.lastIndexOf(' ');
        if (space < 0 || key.length() - space - 1 > 6) {
            return -1;
        }
        codePoint = 0;
        for (int index = space + 1; index < key.length(); index++) {
            char digit = key.charAt(index);
            if (digit >= '0' && digit <= '9') {
                codePoint = codePoint << 4 | (digit - '0');
            }
            else if (digit >= 'A' && digit <= 'F') {
                codePoint = codePoint << 4 | (digit - 'A' + 10);
            }
            else {
                return -1;
            }
        }
        return space + 1 < key.length() && codePoint <= RUNEMAX && key.equals(getName(codePoint)) ? codePoint : -1;
    }

    private static String controlName(int codePoint)
    {
        if (codePoint == BEL) {
            return "BEL";
        }
        String name = UnicodeDatabase.controlNames().name(codePoint);
        return name != null ? name : CONTROL_FIGMENTS.get(codePoint);
    }
}
