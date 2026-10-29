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
import io.airlift.regulator.UnicodeDatabase.MultiCharacterMappings;

/// The locale-independent case conversions of [String], from the pinned Unicode database, with
/// which the parsers normalize property and character names as the JDK does. Property and
/// character names are ASCII, but a name in a pattern can hold any text, and the JDK's
/// conversions take some non-ASCII characters to ASCII, such as the long s to S and the Kelvin
/// sign to k.
final class UnicodeCase
{
    private UnicodeCase() {}

    /// Returns `value` uppercased, as `value.toUpperCase(Locale.ROOT)` does: by the
    /// unconditional uppercase mappings of SpecialCasing.txt, such as sharp s to SS, and otherwise
    /// by the simple mappings.
    static String toUpperCase(String value)
    {
        return convert(value, UnicodeDatabase.fullUppercaseMappings(), UnicodeDatabase.simpleUppercaseMappings());
    }

    /// Returns `value` lowercased, as `value.toLowerCase(Locale.ROOT)` does: by the
    /// unconditional lowercase mappings of SpecialCasing.txt, such as capital I with dot above to
    /// i and a combining dot above, and otherwise by the simple mappings. It omits the one
    /// condition that applies without a locale, Final_Sigma, which chooses between the final and
    /// other small sigmas; every caller compares the result with ASCII names, which neither
    /// matches.
    static String toLowerCase(String value)
    {
        return convert(value, UnicodeDatabase.fullLowercaseMappings(), UnicodeDatabase.simpleLowercaseMappings());
    }

    private static String convert(String value, MultiCharacterMappings fullMappings, CodePointMappings simpleMappings)
    {
        StringBuilder converted = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            index += codePoint > 0xFFFF ? 2 : 1;
            int[] full = fullMappings.mapping(codePoint);
            if (full != null) {
                for (int mapped : full) {
                    converted.appendCodePoint(mapped);
                }
            }
            else {
                converted.appendCodePoint(simpleMappings.map(codePoint));
            }
        }
        return converted.toString();
    }
}
