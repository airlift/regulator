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

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.airlift.regulator.TestingExhaustive.runExhaustiveTest;

// Ported from upstream RE2: re2/testing/exhaustive_test.cc.
public class TestExhaustiveAgreement
{
    // Test very simple expressions.
    @Test
    public void testEgrepLiteralsLowercase()
    {
        runExhaustiveEgrepTest(
                List.of("a", "b", "c", "."),
                3,
                2,
                "abc",
                3,
                null);
    }

    // Test mixed-case expressions.
    @Test
    public void testEgrepLiteralsMixedCase()
    {
        runExhaustiveEgrepTest(
                List.of("A", "a", "B", "b", "."),
                3,
                2,
                "AaBb",
                2,
                null);
    }

    // Test mixed-case in case-insensitive mode.
    @Test
    public void testEgrepLiteralsFoldCase()
    {
        // The punctuation characters surround A-Z and a-z
        // in the ASCII table.  This looks for bugs in the
        // bytemap range code in the DFA.
        runExhaustiveEgrepTest(
                List.of("a", "b", "A", "B", "."),
                3,
                2,
                "aBc@_~",
                2,
                "(?i:%s)");
    }

    // Test very simple expressions with UTF-8.
    @Test
    public void testEgrepLiteralsUtf8()
    {
        runExhaustiveEgrepTest(
                List.of("a", "b", "."),
                3,
                2,
                "a\u263A",
                4,
                null);
    }

    private static void runExhaustiveEgrepTest(
            List<String> atomStrings,
            int maxAtoms,
            int maxOps,
            String strAlphabet,
            int maxStrLen,
            String topWrapper)
    {
        runExhaustiveTest(atomStrings, RegexpGenerator.egrepOps(), maxAtoms, maxOps, strAlphabet, maxStrLen, topWrapper);
    }
}
