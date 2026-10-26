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
package probe;

import io.airlift.regulator.JavaRegexp;
import io.airlift.regulator.PatternInfo;
import io.airlift.regulator.Re2;
import io.airlift.regulator.RegexpParseException;
import io.airlift.regulator.RustRegexp;
import io.airlift.regulator.TrinoRegexp;
import io.airlift.slice.Slice;

import java.util.Map;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;

public final class PatternParsingCheck
{
    private PatternParsingCheck() {}

    public static void main(String[] args)
    {
        Slice pattern = view("(?i)(?<letter>\\pL)(b)?");
        checkInfo("Rust", RustRegexp.parse(pattern), 2, Map.of("letter", 1));
        if (!pattern.copy().equals(pattern) || !pattern.slice(0, 4).toStringUtf8().equals("(?i)")) {
            throw new AssertionError("Incorrect Slice copy or view");
        }
        checkInfo("Rust options", RustRegexp.parse(utf8Slice("#(ignored)\n(a)"), RustRegexp.Options.defaults().setComments(true)), 1, Map.of());
        checkError("Rust", () -> RustRegexp.parse(utf8Slice("é(")), 2, "(");

        checkInfo("RE2", Re2.parse(view("(?i)(?P<letter>\\pL)(b)?")), 2, Map.of("letter", 1));
        checkInfo("RE2 options", Re2.parse(utf8Slice("(a)"), Re2.Options.defaults().setLiteral(true)), 0, Map.of());
        checkError("RE2", () -> Re2.parse(utf8Slice("é[a")), 4, "[a");

        checkInfo("Java", JavaRegexp.parse(view("(?iu)(?<letter>\\p{L})(b)?\\N{LATIN SMALL LETTER A}")), 2, Map.of("letter", 1));
        checkInfo("Java options", JavaRegexp.parse(utf8Slice("#(ignored)\n(a)"), JavaRegexp.Options.defaults().setComments(true)), 1, Map.of());
        checkError("Java", () -> JavaRegexp.parse(utf8Slice("é(?=a)")), 2, "(?=");

        checkInfo("Trino", TrinoRegexp.parse(view("(?i)(?<letter>\\p{L})(b)?")), 2, Map.of("letter", 1));
        checkInfo("Trino options", TrinoRegexp.parse(utf8Slice("(a)"), TrinoRegexp.Options.defaults().setMaxMemory(1)), 1, Map.of());
        checkError("Trino", () -> TrinoRegexp.parse(utf8Slice("é(?=a)")), 2, "(?=");

        System.out.println("TeaVM pattern parsing checks passed");
    }

    // A view with a nonzero offset into a larger array. The padding is unbalanced parentheses, so
    // a parser that reads the whole array instead of the view fails.
    private static Slice view(String pattern)
    {
        byte[] bytes = (")x" + pattern + "x(").getBytes(UTF_8);
        return wrappedBuffer(bytes, 2, bytes.length - 4);
    }

    private static void checkInfo(String check, PatternInfo info, int capturingGroupCount, Map<String, Integer> namedCapturingGroups)
    {
        if (info.capturingGroupCount() != capturingGroupCount || !info.namedCapturingGroups().equals(namedCapturingGroups)) {
            throw new AssertionError(check + ": incorrect capture metadata: " + info.capturingGroupCount() + " groups, names " + info.namedCapturingGroups());
        }
    }

    private static void checkError(String check, Runnable parse, int byteOffset, String invalidPatternSegment)
    {
        try {
            parse.run();
        }
        catch (RegexpParseException expected) {
            if (expected.byteOffset() != byteOffset || !expected.invalidPatternSegment().toStringUtf8().equals(invalidPatternSegment)) {
                throw new AssertionError(check + ": incorrect UTF-8 error span: " + expected);
            }
            return;
        }
        throw new AssertionError(check + ": invalid pattern was accepted");
    }
}
