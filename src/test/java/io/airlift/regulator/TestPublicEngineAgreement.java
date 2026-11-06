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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.airlift.regulator.TestingPatternShapes.assertNoProgramSearch;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestPublicEngineAgreement
{
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    @Test
    public void testByteAndBoundaryCases()
    {
        List<String> patterns = List.of(
                ".",
                "(?s:.)",
                ".*",
                "\\C",
                "a.*b",
                "(?:a|)",
                "(a)?b",
                "^a$",
                "a\\z",
                "\\b",
                "[^\\n]");
        List<Slice> texts = List.of(
                bytes(""),
                bytes("00"),
                bytes("0a"),
                bytes("61"),
                bytes("6162"),
                bytes("80"),
                bytes("c0af"),
                bytes("e282"),
                bytes("f0808080"),
                bytes("c3a9"),
                bytes("61ff62"));

        List<String> failures = new ArrayList<>();
        for (String pattern : patterns) {
            Tester tester = new Tester(Slices.wrappedBuffer(pattern.getBytes(UTF_8)), Tester.Config.fullMatrix());
            for (Slice text : texts) {
                if (!tester.testInput(text)) {
                    failures.add("pattern=" + pattern + " text=" + HEX_FORMAT.formatHex(text.byteArray(), text.byteArrayOffset(), text.byteArrayOffset() + text.length()) + " " + tester.failureMessage());
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    public void testSpanSearchRegions()
    {
        // A group-zero matcher of a literal alternation, a literal set, or a fixed-width pattern
        // finds on the pattern's span search in every language, and its spans are the generic
        // matcher's in every region of a Slice view that begins inside its byte array: regions
        // whose end cuts a match, which leaves `ab` of `abc|x|ab` and `abc|ab` and no fixed-width
        // match, and regions that start inside a match.
        String literalSet = IntStream.range(0, 65).mapToObj(index -> "w" + index + "q").collect(Collectors.joining("|")) + "|abc|ab";
        Map<String, Class<? extends GroupZeroSpanSearch>> expressions = Map.of(
                "abc|x|ab|ERROR|βeta", LiteralAlternationSpanMatcher.class,
                literalSet, LiteralSetSpanMatcher.class,
                "[0-9]{2}-[0-9]{2}", FixedWidthByteSpanMatcher.class);
        // Malformed UTF-8 at the #, between sentinel bytes outside the view.
        byte[] bytes = utf8Slice("!!abcab ERRORβeta w7q12-3456-78 w64qabéERRO#12-34abc?").getBytes();
        bytes[bytes.length - 10] = (byte) 0xFF;
        Slice input = wrappedBuffer(bytes, 2, bytes.length - 3);
        for (Map.Entry<String, Class<? extends GroupZeroSpanSearch>> entry : expressions.entrySet()) {
            String expression = entry.getKey();
            List<CompiledPattern> patterns = List.of(
                    Re2.compile(utf8Slice(expression)),
                    JavaRegexp.compile(utf8Slice(expression)),
                    RustRegexp.compile(utf8Slice(expression)),
                    RustRegexp.compile(utf8Slice("(?-u)" + expression), RustRegexp.Options.bytes()));
            for (CompiledPattern pattern : patterns) {
                String label = pattern.getClass().getSimpleName() + " " + pattern.pattern().toStringUtf8();
                assertThat(pattern.groupZeroSpanSearch()).as(label).isInstanceOf(entry.getValue());
                assertThat(pattern.matcher(input).spanSearchForDiagnostics()).as(label).isSameAs(pattern.groupZeroSpanSearch());
                assertThat(pattern.matcher(input, 0).spanSearchForDiagnostics()).as(label).isSameAs(pattern.groupZeroSpanSearch());
                assertThat(findSpans(pattern.matcher(input))).as(label).isNotEmpty();
                assertNoProgramSearch(pattern, label);
                assertThat(pattern.genericGroupZeroMatcherForDiagnostics(input).spanSearchForDiagnostics()).as(label).isNull();
                RegexpMatcher matcher = pattern.matcher(input, 0);
                RegexpMatcher generic = pattern.genericGroupZeroMatcherForDiagnostics(input);
                for (int regionStart = 0; regionStart <= input.length(); regionStart++) {
                    for (int regionEnd = regionStart; regionEnd <= input.length(); regionEnd++) {
                        assertThat(findSpans(matcher.reset(input, regionStart, regionEnd)))
                                .as("%s region %s-%s", label, regionStart, regionEnd)
                                .isEqualTo(findSpans(generic.reset(input, regionStart, regionEnd)));
                    }
                }
            }
        }
    }

    private static List<String> findSpans(RegexpMatcher matcher)
    {
        List<String> spans = new ArrayList<>();
        while (matcher.find()) {
            spans.add(matcher.start() + "-" + matcher.end());
        }
        return spans;
    }

    private static Slice bytes(String hex)
    {
        return Slices.wrappedBuffer(hex.isEmpty() ? new byte[0] : HEX_FORMAT.parseHex(hex));
    }
}
