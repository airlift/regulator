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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Exhaustive checks that the DFA and OnePass agree with the NFA and BitState on short texts, for
/// programs whose text-dependent assertions those engines evaluate.
final class TestingEngineAgreement
{
    private TestingEngineAgreement() {}

    /// Returns every text of up to four bytes over `alphabet`.
    static List<byte[]> shortTexts(byte... alphabet)
    {
        List<byte[]> texts = new ArrayList<>();
        addTexts(texts, alphabet, new byte[0], 4);
        return List.copyOf(texts);
    }

    /// Searches every text, as a view between bytes outside ASCII, over every range with the view
    /// as context: the forward DFA finds what the NFA finds and the reverse DFA the leftmost start
    /// of a match that ends at the range end, or either quits, which it may do only on text outside
    /// ASCII in a program whose DFA quits on it.
    static void assertDfaAgreesWithNfa(String pattern, Prog forward, Prog reverse, List<byte[]> texts, String label)
    {
        for (byte[] text : texts) {
            Slice context = paddedView(text);
            String description = "%s on %s, %s".formatted(pattern, HexFormat.of().formatHex(text), label);
            boolean mayQuit = forward.dfaQuitsOnNonAscii() && !isAscii(text);
            for (int start = 0; start <= text.length; start++) {
                for (int end = start; end <= text.length; end++) {
                    assertDfaAgreesWithNfa(description, forward, reverse, context, start, end, mayQuit);
                }
            }
        }
    }

    /// Searches every text, as a view between bytes outside ASCII, over every range: OnePass
    /// reports what BitState reports, for a boolean search, group zero, and `captureSlotCount`
    /// slots.
    static void assertOnePassAgreesWithBitState(String pattern, Prog program, int captureSlotCount, List<byte[]> texts, String label)
    {
        for (byte[] text : texts) {
            Slice context = paddedView(text);
            String hex = HexFormat.of().formatHex(text);
            for (int start = 0; start <= text.length; start++) {
                for (int end = start; end <= text.length; end++) {
                    for (Prog.MatchKind matchKind : List.of(Prog.MatchKind.FIRST_MATCH, Prog.MatchKind.LONGEST_MATCH, Prog.MatchKind.FULL_MATCH)) {
                        for (int slots : new int[] {0, 2, captureSlotCount}) {
                            int[] onePass = slots == 0 ? null : new int[slots];
                            int[] bitState = slots == 0 ? null : new int[slots];
                            boolean expected = BitState.search(program, context, start, end, true, matchKind, bitState);
                            String description = "%s on %s [%s, %s) %s, %s slots, %s".formatted(pattern, hex, start, end, matchKind, slots, label);
                            assertThat(OnePass.search(program, context, start, end, true, matchKind, onePass)).as(description).isEqualTo(expected);
                            if (expected && slots != 0) {
                                assertThat(onePass).as(description).containsExactly(bitState);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void assertDfaAgreesWithNfa(String description, Prog forward, Prog reverse, Slice context, int start, int end, boolean mayQuit)
    {
        int[] groups = new int[2];
        long expectedEnd = Nfa.search(forward, context, start, end, false, Prog.MatchKind.FIRST_MATCH, groups) ? groups[1] : Dfa.SEARCH_NO_MATCH;
        long forwardEnd = Dfa.search(forward, context, start, end, false, Prog.MatchKind.FIRST_MATCH, true);
        if (forwardEnd == Dfa.SEARCH_FAILED) {
            assertThat(mayQuit).as("forward quit: %s [%s, %s)", description, start, end).isTrue();
        }
        else {
            assertThat(forwardEnd).as("forward: %s [%s, %s)", description, start, end).isEqualTo(expectedEnd);
        }

        // The reverse program finds the leftmost start of a match that ends at the range end.
        int matchStart = start;
        while (matchStart <= end && !Nfa.search(forward, context, matchStart, end, true, Prog.MatchKind.FULL_MATCH, null)) {
            matchStart++;
        }
        long expectedStart = matchStart <= end ? matchStart - start : Dfa.SEARCH_NO_MATCH;
        long reverseStart = Dfa.search(reverse, context, start, end, true, Prog.MatchKind.LONGEST_MATCH, true);
        if (reverseStart == Dfa.SEARCH_FAILED) {
            assertThat(mayQuit).as("reverse quit: %s [%s, %s)", description, start, end).isTrue();
        }
        else {
            assertThat(reverseStart).as("reverse: %s [%s, %s)", description, start, end).isEqualTo(expectedStart);
        }
    }

    private static void addTexts(List<byte[]> texts, byte[] alphabet, byte[] prefix, int remaining)
    {
        texts.add(prefix);
        if (remaining == 0) {
            return;
        }
        for (byte value : alphabet) {
            byte[] text = Arrays.copyOf(prefix, prefix.length + 1);
            text[prefix.length] = value;
            addTexts(texts, alphabet, text, remaining - 1);
        }
    }

    // A view of `text` between the two bytes of é, so a search that reads past the view decodes a
    // word character.
    private static Slice paddedView(byte[] text)
    {
        byte[] padded = new byte[text.length + 2];
        padded[0] = (byte) 0xC3;
        System.arraycopy(text, 0, padded, 1, text.length);
        padded[padded.length - 1] = (byte) 0xA9;
        return Slices.wrappedBuffer(padded, 1, text.length);
    }

    private static boolean isAscii(byte[] text)
    {
        for (byte value : text) {
            if (value < 0) {
                return false;
            }
        }
        return true;
    }
}
