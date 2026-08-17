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

import static io.airlift.regulator.TestingExhaustive.runExhaustiveTestWithAlphabet;
import static java.nio.charset.StandardCharsets.UTF_8;

// Ported from upstream RE2: re2/testing/exhaustive2_test.cc.
public class TestExhaustiveEdgeCase
{
    // Test empty string matches (aka "(?:)").
    @Test
    public void testEmptyString()
    {
        runExhaustiveTest(
                List.of("(?:)", "a"),
                RegexpGenerator.egrepOps(),
                2,
                2,
                explodeString("ab"),
                5,
                null);
    }

    // Test escaped versions of regexp syntax.
    @Test
    public void testPunctuation()
    {
        List<String> escaped = new ArrayList<>();
        for (String ch : List.of("(", ")", "*", "+", "?", "{", "}", "[", "]", "\\", "^", "$", ".")) {
            escaped.add("\\" + ch);
        }

        List<String> alphabet = List.of("(", ")", "*", "+", "?", "{", "}", "[", "]", "\\", "^", "$", ".");

        runExhaustiveTest(
                escaped,
                RegexpGenerator.egrepOps(),
                1,
                1,
                alphabet,
                2,
                null);
    }

    // Test ^ $ . \A \z in presence of line endings.
    // Have to wrap the empty-width ones in (?:) so that
    // they can be repeated -- PCRE rejects ^* but allows (?:^)*.
    @Test
    public void testLineEnds()
    {
        runExhaustiveTest(
                List.of("(?:^)", "(?:$)", ".", "a", "\\n", "(?:\\A)", "(?:\\z)"),
                RegexpGenerator.egrepOps(),
                2,
                2,
                explodeString("ab\n"),
                4,
                null);
    }

    private static List<String> explodeString(String s)
    {
        List<Slice> chars = StringGenerator.explodeUtf8(Slices.wrappedBuffer(s.getBytes(UTF_8)));
        List<String> result = new ArrayList<>();
        for (Slice ch : chars) {
            result.add(new String(ch.byteArray(), ch.byteArrayOffset(), ch.length(), UTF_8));
        }
        return result;
    }

    private static void runExhaustiveTest(
            List<String> atomStrings,
            List<Slice> ops,
            int maxAtoms,
            int maxOps,
            List<String> strAlphabet,
            int maxStrLen,
            String topWrapper)
    {
        List<Slice> alphabet = new ArrayList<>();
        for (String ch : strAlphabet) {
            alphabet.add(Slices.wrappedBuffer(ch.getBytes(UTF_8)));
        }
        runExhaustiveTestWithAlphabet(atomStrings, ops, maxAtoms, maxOps, alphabet, maxStrLen, topWrapper);
    }
}
