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

import io.airlift.regulator.PatternInfo;
import io.airlift.regulator.RegexpParseException;
import io.airlift.regulator.RustRegexp;
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
        byte[] bytes = "xx(?i)(?<letter>\\pL)(b)?yy".getBytes(UTF_8);
        Slice pattern = wrappedBuffer(bytes, 2, bytes.length - 4);
        PatternInfo info = RustRegexp.parse(pattern);
        if (info.capturingGroupCount() != 2 || !info.namedCapturingGroups().equals(Map.of("letter", 1))) {
            throw new AssertionError("Incorrect capture metadata: " + info);
        }

        if (!pattern.copy().equals(pattern) || !pattern.slice(0, 4).toStringUtf8().equals("(?i)")) {
            throw new AssertionError("Incorrect Slice copy or view");
        }

        PatternInfo comments = RustRegexp.parse(utf8Slice("#(ignored)\n(a)"), RustRegexp.Options.defaults().setComments(true));
        if (comments.capturingGroupCount() != 1) {
            throw new AssertionError("Syntax options were ignored");
        }

        try {
            RustRegexp.parse(utf8Slice("é("));
            throw new AssertionError("Invalid pattern was accepted");
        }
        catch (RegexpParseException expected) {
            if (expected.byteOffset() != 2 || !expected.invalidPatternSegment().toStringUtf8().equals("(")) {
                throw new AssertionError("Incorrect UTF-8 error span: " + expected);
            }
        }

        System.out.println("TeaVM pattern parsing checks passed");
    }
}
