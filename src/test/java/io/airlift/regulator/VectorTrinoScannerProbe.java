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

import java.util.Arrays;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;

public final class VectorTrinoScannerProbe
{
    private static final String FILLER = "y".repeat(64);

    private VectorTrinoScannerProbe() {}

    public static void main(String[] arguments)
    {
        boolean expectedVectorApiAvailable = Boolean.parseBoolean(arguments[0]);
        check(VectorSupport.isAvailable() == expectedVectorApiAvailable, "unexpected Vector API availability");

        // Each pattern is a scan plan, and its text is longer than the 128-bit vector scans.
        // Anchored runs of one-byte sets stop with the run scan.
        verify("^abc(?:(x+))?y*$",
                "LITERAL,FORK,SAVE,RUN,SAVE,RUN,END",
                null,
                false,
                "abc" + "x".repeat(64) + FILLER,
                "x".repeat(64),
                1);
        verify("^([^/]{2,})/$",
                "RUN,LITERAL,END",
                null,
                false,
                "h".repeat(64) + "/",
                "h".repeat(64),
                1);
        // A literal-leading unanchored search finds its first byte.
        verify("https?://([^/]+)/",
                "LITERAL,OPTIONAL,LITERAL,SAVE,RUN,SAVE,LITERAL",
                null,
                false,
                FILLER + "https://" + "h".repeat(64) + "/" + FILLER,
                "h".repeat(64),
                1);
        // A complement run's candidate scan skips its one non-member byte.
        verify("([^/]+)/",
                "SAVE,RUN,SAVE,LITERAL",
                "/",
                false,
                "/".repeat(64) + "host/",
                "host",
                1);
        // The byte-set candidate scans: a small set, a range, and a nibble table.
        verify("[?&]([^=]+)=",
                "RUN,SAVE,RUN,SAVE,LITERAL",
                "=",
                false,
                FILLER + "?key=" + FILLER + "&other=",
                "key",
                2);
        verify("[a-e](\\d+)",
                "RUN,SAVE,RUN,SAVE",
                null,
                false,
                "x".repeat(64) + "c12" + "x".repeat(64),
                "12",
                1);
        verify("[aceg](\\d+)",
                "RUN,SAVE,RUN,SAVE",
                null,
                false,
                "x".repeat(64) + "e12" + "x".repeat(64),
                "12",
                1);
        // After the first failed attempt, a run-leading search checks for its required literal.
        verify("([^:]+):([0-9]+)",
                "SAVE,RUN,SAVE,LITERAL,SAVE,RUN,SAVE",
                ":",
                false,
                "abc:x" + FILLER + ":12",
                "x" + FILLER,
                1);
        verify("([^:]+):([0-9]+)",
                "SAVE,RUN,SAVE,LITERAL,SAVE,RUN,SAVE",
                ":",
                false,
                "abc:x" + FILLER,
                null,
                0);
        // The ASCII-folded executor finds a folded leading letter in either case.
        verify("(?i)content-type:([^;]+);",
                "ASCII_FOLDED_LITERAL,SAVE,RUN,SAVE,LITERAL",
                null,
                true,
                FILLER + "CONTENT-TYPE:text;" + FILLER + "content-type:html;",
                "text",
                2);

        System.out.printf("OK %s%n", expectedVectorApiAvailable ? "vector" : "scalar");
    }

    private static void verify(String expression, String operations, String requiredLiteral, boolean asciiFolded, String text, String group, int count)
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
        check(regexp.pattern().usesTrinoScanPlanForDiagnostics(), expression + ": no scan plan");
        check(regexp.pattern().usesAsciiFoldedTrinoScanExecutorForDiagnostics() == asciiFolded, expression + ": unexpected executor");
        ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(expression), Regexp.LIKE_PERL);
        TrinoScanPlan plan = TrinoScanPlan.analyze(parsed.regexp(), parsed.capturingGroupCount(), false);
        check(plan != null, expression + ": no plan");
        check(plan.operationsForDiagnostics().equals(operations), expression + ": unexpected operations " + plan.operationsForDiagnostics());
        byte[] required = plan.requiredLiteralForDiagnostics();
        check(requiredLiteral == null ? required == null : Arrays.equals(required, requiredLiteral.getBytes(UTF_8)), expression + ": unexpected required literal");

        Slice source = utf8Slice(text);
        check(regexp.contains(source) == (group != null), expression + ": contains failed");
        Slice extracted = regexp.extract(source, 1);
        check(group == null ? extracted == null : extracted != null && extracted.equals(utf8Slice(group)), expression + ": extract failed");
        check(regexp.count(source) == count, expression + ": count failed");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
