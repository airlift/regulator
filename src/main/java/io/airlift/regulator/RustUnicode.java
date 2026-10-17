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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

import static io.airlift.regulator.RustUnicodeAliases.ALIASES;

/**
 * Rust property spellings with Regulator's JVM-sourced Unicode data policy.
 */
final class RustUnicode
{
    private static final Map<String, CharClass> CACHE = new ConcurrentHashMap<>();

    private RustUnicode() {}

    static CharClass property(String name)
    {
        if (name.equals("perl:word")) {
            return UnicodeGroups.lookup("IsWord");
        }
        if (name.startsWith("ascii:")) {
            return CACHE.computeIfAbsent(name, RustUnicode::ascii);
        }
        String canonical = canonical(name);
        return canonical == null ? null : CACHE.computeIfAbsent(canonical, RustUnicode::resolve);
    }

    static boolean knownProperty(String name)
    {
        return canonical(name) != null;
    }

    private static String canonical(String name)
    {
        int equals = name.indexOf('=');
        String key = equals < 0 ? normalize(name) : normalize(name.substring(0, equals)) + "=" + normalize(name.substring(equals + 1));
        String canonical = ALIASES.get(key);
        return "General_Category=Surrogate".equals(canonical) ? null : canonical;
    }

    private static String normalize(String name)
    {
        // Rust strips only an ASCII "is" prefix, before dropping non-ASCII bytes.
        int start = name.length() >= 2 && (name.charAt(0) == 'i' || name.charAt(0) == 'I') &&
                (name.charAt(1) == 's' || name.charAt(1) == 'S') ? 2 : 0;
        StringBuilder result = new StringBuilder();
        for (int index = start; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character < 128 && character != ' ' && character != '_' && character != '-') {
                result.append(Character.toLowerCase(character));
            }
        }
        // Unicode's ISO_Comment alias must not become the general category C.
        return start == 2 && result.toString().equals("c") ? "isc" : result.toString();
    }

    private static CharClass resolve(String canonical)
    {
        int equals = canonical.indexOf('=');
        String family = canonical.substring(0, equals);
        String value = canonical.substring(equals + 1);
        CharClass result = switch (family) {
            case "Script" -> UnicodeGroups.lookup("sc=" + value);
            case "General_Category" -> switch (value) {
                case "Any" -> UnicodeGroups.lookup("Any");
                case "ASCII" -> UnicodeGroups.lookup("ASCII");
                case "Assigned" -> UnicodeGroups.lookup("IsAssigned");
                default -> UnicodeGroups.lookup("gc=" + value);
            };
            case "binary" -> switch (value) {
                case "ASCII_Hex_Digit" -> ascii("ascii:xdigit");
                case "Hex_Digit" -> hexDigit();
                case "Bidi_Mirrored" -> build(Character::isMirrored);
                case "Cased" -> build(rune -> Character.isLowerCase(rune) || Character.isUpperCase(rune) || Character.isTitleCase(rune));
                default -> UnicodeGroups.lookup("Is" + value);
            };
            default -> null;
        };
        if (result == null) {
            return null;
        }
        CharClassBuilder characters = new CharClassBuilder();
        characters.addCharClass(result, Regexp.CLASS_NEWLINE);
        removeRange(characters, 0xD800, 0xDFFF);
        return characters.toCharClass();
    }

    private static CharClass hexDigit()
    {
        // Rust includes only ASCII hexadecimal characters and their fullwidth forms.
        CharClassBuilder characters = new CharClassBuilder();
        characters.addCharClass(ascii("ascii:xdigit"), Regexp.CLASS_NEWLINE);
        characters.addRange(0xFF10, 0xFF19);
        characters.addRange(0xFF21, 0xFF26);
        characters.addRange(0xFF41, 0xFF46);
        return characters.toCharClass();
    }

    private static CharClass build(IntPredicate predicate)
    {
        CharClassBuilder characters = new CharClassBuilder();
        int start = -1;
        for (int rune = 0; rune <= Regexp.RUNEMAX; rune++) {
            if (predicate.test(rune)) {
                if (start < 0) {
                    start = rune;
                }
            }
            else if (start >= 0) {
                characters.addRange(start, rune - 1);
                start = -1;
            }
        }
        if (start >= 0) {
            characters.addRange(start, Regexp.RUNEMAX);
        }
        return characters.toCharClass();
    }

    private static CharClass ascii(String name)
    {
        if (name.equals("ascii:word")) {
            CharClassBuilder word = new CharClassBuilder();
            word.addRange('0', '9');
            word.addRange('A', 'Z');
            word.addRange('_', '_');
            word.addRange('a', 'z');
            return word.toCharClass();
        }
        String property = switch (name) {
            case "ascii:alnum" -> "Alnum";
            case "ascii:alpha" -> "Alpha";
            case "ascii:ascii" -> "ASCII";
            case "ascii:blank" -> "Blank";
            case "ascii:cntrl" -> "Cntrl";
            case "ascii:digit" -> "Digit";
            case "ascii:graph" -> "Graph";
            case "ascii:lower" -> "Lower";
            case "ascii:print" -> "Print";
            case "ascii:punct" -> "Punct";
            case "ascii:space" -> "Space";
            case "ascii:upper" -> "Upper";
            case "ascii:xdigit" -> "XDigit";
            default -> null;
        };
        return property == null ? null : UnicodeGroups.lookup(property);
    }

    static void fold(CharClassBuilder characters, boolean unicode)
    {
        CharClass original = characters.toCharClass();
        int flags = Regexp.CLASS_NEWLINE | (unicode ? Regexp.FOLD_CASE : Regexp.ASCII_FOLD_CASE);
        characters.addCharClass(original, flags);
    }

    static void subtract(CharClassBuilder left, CharClassBuilder right)
    {
        CharClassBuilder complement = right.copy();
        complement.negate();
        left.retainAll(complement);
    }

    static void removeRange(CharClassBuilder characters, int low, int high)
    {
        CharClassBuilder removed = new CharClassBuilder();
        removed.addRange(low, high);
        subtract(characters, removed);
    }
}
