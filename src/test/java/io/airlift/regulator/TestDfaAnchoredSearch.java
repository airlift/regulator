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

import java.util.List;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDfaAnchoredSearch
{
    @Test
    public void testAnchoredEndBoundaryAgreesWithNfaForSimpleCases()
    {
        assertAnchoredEndBoundary("a", "a");
        assertAnchoredEndBoundary("a*", "");
        assertAnchoredEndBoundary("a*", "aaa");
        assertAnchoredEndBoundary("a*?", "aaa");
        assertAnchoredEndBoundary("a+?", "aaa");
        assertAnchoredEndBoundary("a$", "a");
    }

    @Test
    public void testAnchoredSearchWithStopScansMatchingSelfLoop()
    {
        // Object rows hold no match transitions, so the confirmation scans the rest of a matching
        // self-loop, such as the run of \w+, on the int table.
        Prog prog = Compiler.compile(RegexpParser.parse(Slices.utf8Slice("\\w+"), Regexp.LIKE_PERL).regexp(), false, 0);
        Slice text = Slices.utf8Slice("x".repeat(10_000) + " ");

        assertThat(Dfa.searchAnchoredWithStop(prog, text, 0, text.length(), 0, text.length())).isEqualTo(10_000);
        assertThat(prog.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).matchingSelfLoopScanCount()).isPositive();
    }

    @Test
    public void testConfirmationWithStopCrossesLongSelfLoops()
    {
        // The host, path, query, and fragment loops of this URI pattern are matching self-loops:
        // the host loop exits on whitespace, /, ? and #, the path loop on whitespace, ? and #, the
        // query loop on whitespace and #, and the fragment loop on whitespace.
        String uri = "[\\w]+://[^/\\s?#]+[^\\s?#]+(?:\\?[^\\s#]*)?(?:#[^\\s]*)?";
        String url = "http://" + "h".repeat(1_000) + ".com/" + "p/".repeat(500) + "?" + "q".repeat(1_000) + "#" + "f".repeat(1_000);
        String text = "x " + url + " tail";
        assertConfirmation(uri, text, 2, text.length(), 2 + url.length());
        // A window that ends inside a loop or just before an exit byte matches through its end.
        for (int end : List.of(500, text.indexOf(".com"), text.indexOf('?'), text.indexOf('?') + 500, text.indexOf('#'), text.length() - 100)) {
            assertConfirmation(uri, text, 2, end, end);
        }

        // Inside a quoted string the DFA is in a self-loop that is not a match state. It matches
        // through the closing quote, stops after a line break, where it dies, or stops at the
        // window end.
        for (String quoted : List.of("\"[^\"\\\\\\r\\n]*\"", "\"[^\"\\n]*\"")) {
            String body = "x \"" + "s".repeat(1_000);
            assertConfirmation(quoted, body + "\" y", 2, body.length() + 2, body.length() + 1);
            assertConfirmationStop(quoted, body + "\n\" y", 2, body.length() + 3, body.length() + 1);
            assertConfirmationStop(quoted, body + "\" y", 2, 500, 500);
        }
    }

    private static void assertConfirmation(String pattern, String text, int start, int end, int matchEnd)
    {
        assertThat(searchAnchoredWithStop(pattern, text, start, end))
                .as("%s [%s, %s)", pattern, start, end)
                .isEqualTo(matchEnd);
    }

    private static void assertConfirmationStop(String pattern, String text, int start, int end, int stop)
    {
        long result = searchAnchoredWithStop(pattern, text, start, end);
        assertThat(result)
                .as("%s [%s, %s)", pattern, start, end)
                .isLessThanOrEqualTo(-2);
        assertThat(Dfa.anchoredStopPosition(result))
                .as("%s [%s, %s) stop", pattern, start, end)
                .isEqualTo(stop);
    }

    private static long searchAnchoredWithStop(String pattern, String text, int start, int end)
    {
        Prog prog = RustRegexp.compile(utf8Slice(pattern), RustRegexp.Options.defaults().setBytes(true).setUnicode(false)).forwardProgramForDiagnostics();
        Slice input = utf8Slice(text);
        return Dfa.searchAnchoredWithStop(prog, input, 0, input.length(), start, end);
    }

    private static void assertAnchoredEndBoundary(String pattern, String text)
    {
        int flags = Regexp.LIKE_PERL | Regexp.LATIN1;
        ParseResult parsed = RegexpParser.parse(Slices.wrappedBuffer(pattern.getBytes(UTF_8)), flags);

        Prog prog = Compiler.compile(parsed.regexp(), false, 0);
        assertThat(prog).as("compile: %s", pattern).isNotNull();

        Slice input = Slices.wrappedBuffer(text.getBytes(UTF_8));

        long dfa = Dfa.search(prog, input, true, Prog.MatchKind.FIRST_MATCH, true);

        int[] submatch = new int[2];
        boolean nfaMatched = Nfa.search(prog, input, true, Prog.MatchKind.FIRST_MATCH, submatch);

        assertThat(dfa >= 0).as("dfa matched: %s / %s", pattern, text).isEqualTo(nfaMatched);
        if (dfa >= 0) {
            assertThat((int) dfa).as("dfa end boundary: %s / %s", pattern, text).isEqualTo(submatch[1]);
        }
    }
}
