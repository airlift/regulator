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
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.airlift.regulator.TestingExhaustive.runExhaustiveTest;
import static io.airlift.regulator.TestingExhaustive.runExhaustiveTestWithAlphabet;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

// Ported from upstream RE2: re2/testing/exhaustive3_test.cc.
public class TestExhaustiveCharClass
{
    private static final int RUNEMAX = 0x10FFFF;

    @Test
    public void testComplementaryClassesHaveIndependentExpectedResults()
    {
        // A class union its complement contains every character; its negation is empty.
        // Check that identity directly: agreement between engines sharing a compiler
        // cannot detect a simplification error. Native option goldens cover the boundaries.
        for (Re2.Options.Encoding encoding : Re2.Options.Encoding.values()) {
            List<Slice> characters = new ArrayList<>();
            characters.add(wrappedBuffer(new byte[] {0}));
            if (encoding == Re2.Options.Encoding.UTF8) {
                characters.addAll(generateInterestingUtf8());
            }
            else {
                for (int value = 1; value < 256; value++) {
                    characters.add(wrappedBuffer(new byte[] {(byte) value}));
                }
            }
            for (boolean dotNewline : List.of(false, true)) {
                for (boolean neverNewline : List.of(false, true)) {
                    Re2.Options options = Re2.Options.defaults().setEncoding(encoding)
                            .setDotMatchesNewline(dotNewline).setNeverNewline(neverNewline);
                    for (String members : List.of("\\s\\S", "\\d\\D", "\\w\\W")) {
                        for (boolean negated : List.of(false, true)) {
                            String atom = "[" + (negated ? "^" : "") + members + "]";
                            Re2 standalone = Re2.compile(utf8Slice(atom), options);
                            Re2 contextual = Re2.compile(utf8Slice("a(" + atom + ")b"), options);
                            for (Slice character : characters) {
                                boolean expected = !negated && !(neverNewline && character.length() == 1 && character.getByte(0) == '\n');
                                assertThat(standalone.matchInto(character, 0, character.length(), Re2.Anchor.ANCHOR_BOTH, null))
                                        .as("%s %s dotNewline=%s neverNewline=%s character=%s", atom, encoding, dotNewline, neverNewline, character)
                                        .isEqualTo(expected);
                                Slice text = wrappedBuffer(ByteArrays.concat(utf8Slice("paddinga"), character, utf8Slice("b"))).slice(7, character.length() + 2);
                                int[] groups = new int[4];
                                assertThat(contextual.matchInto(text, 0, text.length(), Re2.Anchor.ANCHOR_BOTH, groups))
                                        .as("context: %s %s dotNewline=%s neverNewline=%s", atom, encoding, dotNewline, neverNewline)
                                        .isEqualTo(expected);
                                if (expected) {
                                    assertThat(groups).containsExactly(0, text.length(), 1, 1 + character.length());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Test simple character classes by themselves.
    @Test
    public void testCharacterClasses()
    {
        runExhaustiveTest(
                List.of("[a]",
                        "[b]",
                        "[ab]",
                        "[^bc]",
                        "[b-d]",
                        "[^b-d]",
                        "[]a]",
                        "[-a]",
                        "[a-]",
                        "[^-a]",
                        "[a-b-c]",
                        "a",
                        "b",
                        "."),
                RegexpGenerator.egrepOps(),
                2,
                1,
                "ab",
                5,
                null);
    }

    @Test
    public void testCharacterClassesInsideContext()
    {
        runExhaustiveTest(
                List.of("[a]",
                        "[b]",
                        "[ab]",
                        "[^bc]",
                        "[b-d]",
                        "[^b-d]",
                        "[]a]",
                        "[-a]",
                        "[a-]",
                        "[^-a]",
                        "[a-b-c]",
                        "a",
                        "b",
                        "."),
                RegexpGenerator.egrepOps(),
                2,
                1,
                "ab",
                5,
                "a%sb");
    }

    // Test interesting UTF-8 characters against character classes.
    @Test
    public void testInterestingUtf8SingleOps()
    {
        List<String> atoms = List.of(
                ".",
                "^",
                "$",
                "\\a",
                "\\f",
                "\\n",
                "\\r",
                "\\t",
                "\\v",
                "\\d",
                "\\D",
                "\\s",
                "\\S",
                "\\w",
                "\\W",
                "\\b",
                "\\B",
                "[[:alnum:]]",
                "[[:alpha:]]",
                "[[:blank:]]",
                "[[:cntrl:]]",
                "[[:digit:]]",
                "[[:graph:]]",
                "[[:lower:]]",
                "[[:print:]]",
                "[[:punct:]]",
                "[[:space:]]",
                "[[:upper:]]",
                "[[:word:]]",
                "[[:xdigit:]]",
                "[\\s\\S]",
                "[\\d\\D]",
                "[^\\w\\W]",
                "[^\\d\\D]");

        List<Slice> noOps = List.of();
        List<Slice> interestingChars = generateInterestingUtf8();

        runExhaustiveTestWithAlphabet(
                atoms, noOps, 1, 0, interestingChars, 1, null);
    }

    // Test interesting UTF-8 characters against character classes,
    // but wrap everything inside AB.
    @Test
    public void testInterestingUtf8AB()
    {
        List<String> atoms = List.of(
                ".",
                "^",
                "$",
                "\\a",
                "\\f",
                "\\n",
                "\\r",
                "\\t",
                "\\v",
                "\\d",
                "\\D",
                "\\s",
                "\\S",
                "\\w",
                "\\W",
                "\\b",
                "\\B",
                "[[:alnum:]]",
                "[[:alpha:]]",
                "[[:blank:]]",
                "[[:cntrl:]]",
                "[[:digit:]]",
                "[[:graph:]]",
                "[[:lower:]]",
                "[[:print:]]",
                "[[:punct:]]",
                "[[:space:]]",
                "[[:upper:]]",
                "[[:word:]]",
                "[[:xdigit:]]",
                "[\\s\\S]",
                "[\\d\\D]",
                "[^\\w\\W]",
                "[^\\d\\D]");

        List<Slice> noOps = List.of();
        List<Slice> interestingChars = generateInterestingUtf8();

        // Wrap each character as "a" + char + "b".
        Slice aPrefix = Slices.wrappedBuffer("a".getBytes(UTF_8));
        Slice bSuffix = Slices.wrappedBuffer("b".getBytes(UTF_8));
        List<Slice> wrappedAlpha = new ArrayList<>();
        for (Slice ch : interestingChars) {
            wrappedAlpha.add(Slices.wrappedBuffer(ByteArrays.concat(aPrefix, ch, bSuffix)));
        }

        runExhaustiveTestWithAlphabet(
                atoms, noOps, 1, 0, wrappedAlpha, 1, "a%sb");
    }

    // Returns a list of "interesting" UTF-8 characters, matching upstream InterestingUTF8().
    // Unicode is too big to just return all, so we return a set likely to be good test cases.
    private static List<Slice> generateInterestingUtf8()
    {
        List<Slice> result = new ArrayList<>();

        // All the Latin-1 equivalents are interesting (code points 1-255).
        for (int i = 1; i < 256; i++) {
            result.add(Slices.wrappedBuffer(encodeUtf8(i)));
        }

        // After that, the codes near bit boundaries are
        // interesting, because they span byte sequence lengths.
        for (int j = 0; j < 8; j++) {
            result.add(Slices.wrappedBuffer(encodeUtf8(256 + j)));
        }
        for (int i = 512; i < RUNEMAX; i <<= 1) {
            for (int j = -8; j < 8; j++) {
                int codePoint = i + j;
                if (codePoint > 0 && codePoint <= RUNEMAX) {
                    result.add(Slices.wrappedBuffer(encodeUtf8(codePoint)));
                }
            }
        }

        // The codes near Runemax, including Runemax itself, are interesting.
        for (int j = -8; j <= 0; j++) {
            int codePoint = RUNEMAX + j;
            if (codePoint > 0) {
                result.add(Slices.wrappedBuffer(encodeUtf8(codePoint)));
            }
        }

        return result;
    }

    private static byte[] encodeUtf8(int rune)
    {
        if (rune < 0x80) {
            return new byte[] {(byte) rune};
        }
        if (rune < 0x800) {
            return new byte[] {
                    (byte) (0xC0 | (rune >>> 6)),
                    (byte) (0x80 | (rune & 0x3F)),
            };
        }
        if (rune < 0x10000) {
            return new byte[] {
                    (byte) (0xE0 | (rune >>> 12)),
                    (byte) (0x80 | ((rune >>> 6) & 0x3F)),
                    (byte) (0x80 | (rune & 0x3F)),
            };
        }
        return new byte[] {
                (byte) (0xF0 | (rune >>> 18)),
                (byte) (0x80 | ((rune >>> 12) & 0x3F)),
                (byte) (0x80 | ((rune >>> 6) & 0x3F)),
                (byte) (0x80 | (rune & 0x3F)),
        };
    }
}
