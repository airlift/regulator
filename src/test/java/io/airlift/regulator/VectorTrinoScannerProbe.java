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

import static io.airlift.slice.Slices.utf8Slice;

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
                "abc" + "x".repeat(64) + FILLER,
                "x".repeat(64),
                1);
        verify("^([^/]{2,})/$",
                "RUN,LITERAL,END",
                "h".repeat(64) + "/",
                "h".repeat(64),
                1);
        // A literal-leading unanchored search finds its first byte.
        verify("https?://([^/]+)/",
                "LITERAL,OPTIONAL,LITERAL,SAVE,RUN,SAVE,LITERAL",
                FILLER + "https://" + "h".repeat(64) + "/" + FILLER,
                "h".repeat(64),
                1);

        System.out.printf("OK %s%n", expectedVectorApiAvailable ? "vector" : "scalar");
    }

    private static void verify(String expression, String operations, String text, String group, int count)
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression));
        check(regexp.pattern().usesTrinoScanPlanForDiagnostics(), expression + ": no scan plan");
        ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(expression), Regexp.LIKE_PERL);
        TrinoScanPlan plan = TrinoScanPlan.analyze(parsed.regexp(), parsed.capturingGroupCount(), false);
        check(plan != null, expression + ": no plan");
        check(plan.operationsForDiagnostics().equals(operations), expression + ": unexpected operations " + plan.operationsForDiagnostics());

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
