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

import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TestWindowLiteralSearch
{
    private static final String TOM_RIVER = "Tom.{10,25}river|river.{10,25}Tom";
    private static final String HOLMES_WATSON = "Holmes.{0,25}Watson|Watson.{0,25}Holmes";
    // Every match begins with one of three literals, Tom, Huc, or riv, so the start literal filter
    // holds three literals, not one or two, and the search is kept at every vector width.
    private static final String TOM_HUCK_RIVER = "(?:Tom|Huck).{10,25}river|river.{10,25}(?:Tom|Huck)";
    // Enough for the widest window here, 103 bytes, and the minimum skip before it; also used as
    // trailing padding.
    private static final String FILLER = "-".repeat(200);
    // Bytes before a Slice's start in its backing array, more than the widest window, so that an
    // offset read from the array's start lands in them.
    private static final int PADDING = WindowLiteralSearch.MAXIMUM_WINDOW_BYTES + 45;
    // Where a boolean find's text puts its match, far enough past the start for the window search.
    private static final int BOOLEAN_FIND_MATCH_START = 400;

    // Each pattern with the literal its window search scans for.
    private static final Map<String, String> PATTERNS = Map.of(
            TOM_RIVER, "river",
            "\\bTom.{10,25}river|river.{10,25}Tom\\b", "river",
            "(?s)Tom.{10,25}river|river.{10,25}Tom", "river",
            "(?:xy|z){0,2}xyz", "xyz",
            "(?:aa|ab){0,2}aaa", "aaa",
            "(?:ab|c){0,2}aaa\\d", "aaa",
            "(Tom).{2,5}(river)|(river)[^x]{1,3}?(Tom)", "river",
            "(?:Tom|Huck)\\s.{0,8}river\\w*|river.{0,8}(?:Tom|Huck)", "river",
            "(?:é|ü)x.{10,25}river|river.{10,25}(?:é|ü)x", "river");

    @Test
    public void testSelectsLiteralAndWindow()
    {
        // Every alternative holds river and Tom, and river is the rarer. Its window is the widest
        // the prefix Tom.{10,25} consumes as each language compiles it: a dot is one byte in a
        // Latin-1 program and for Rust bytes without Unicode, and up to four bytes in UTF-8.
        for (Language language : Language.values()) {
            CompiledPattern pattern = language.compiledPattern(TOM_RIVER);
            WindowLiteralSearch search = pattern.windowLiteralSearchForDiagnostics();
            if (keepsOneOrTwoLiteralStartFilter(pattern)) {
                assertThat(search).as(language.name()).isNull();
                continue;
            }
            assertThat(search).as(language.name()).isNotNull();
            assertThat(new String(search.literalForDiagnostics(), UTF_8)).as(language.name()).isEqualTo("river");
            assertThat(search.windowBytesForDiagnostics()).as(language.name()).isEqualTo(language.byteDot() ? 3 + 25 : 3 + 25 * 4);
        }
        // A concatenation whose prefix has a bounded width but none of the inner literal search's
        // proofs: the prefix can consume every byte of xyz and has no fixed length.
        assertThat(Re2.compile(utf8Slice("(?:xy|z){0,2}xyz")).innerLiteralSearchForDiagnostics()).isNull();
        assertThat(windowBytes(Language.RE2, "(?:xy|z){0,2}xyz")).isEqualTo(4);
        // A class's width is its largest rune's UTF-8 length, and a non-ASCII literal's its own.
        // The last two lines check the widths of the dots.
        assertThat(windowBytes(Language.RE2, "[a-zé]{2}éxyz|xyz")).isEqualTo(2 * 2 + 2);
        assertThat(windowBytes(Language.RE2, "[a-z\\x{800}]xyz|xyz")).isEqualTo(3);
        assertThat(windowBytes(Language.RE2, "(?s:.)xyz|xyz")).isEqualTo(4);
        assertThat(windowBytes(Language.RUST_BYTES, "(?-u:.)xyz|xyz")).isEqualTo(1);

        // No literal every match holds within a bounded window: an unbounded prefix, an
        // alternative without the literal, a case-insensitive literal, and a window past the limit,
        // whether a prefix or the literal's own offset in a longer literal makes it; the last case
        // repeats the one before it with the window inside a capture group. Nor is the search kept
        // for a pattern anchored at its start or its end, or a prefix that Trino's full case
        // folding can widen.
        for (String pattern : List.of(
                "Tom.*river|river.*Tom",
                "(?:xy|z)*xyz",
                "Tom.{10,25}river|Huck",
                "(?i:Tom.{10,25}river)|river.{10,25}Tom",
                ".{256}xyz|xyz",
                "a".repeat(256) + "xyz|xyz",
                "(" + "a".repeat(256) + "xyz)|xyz",
                "^Tom.{10,25}river|^river.{10,25}Tom",
                "(?:Tom.{10,25}river|river.{10,25}Tom)$")) {
            for (Language language : Language.values()) {
                assertThat(language.compiledPattern(pattern).windowLiteralSearchForDiagnostics()).as("%s %s", language, pattern).isNull();
            }
        }
        assertThat(Language.TRINO.compiledPattern("(?i:ß)xyz|xyz").windowLiteralSearchForDiagnostics()).isNull();
        // The limit itself is a window the search keeps, except with 64-byte vectors, where the
        // pattern's two literals make a start literal filter the search steps aside for.
        CompiledPattern limit = Language.RE2.compiledPattern("a".repeat(WindowLiteralSearch.MAXIMUM_WINDOW_BYTES) + "xyz|xyz");
        WindowLiteralSearch limitSearch = limit.windowLiteralSearchForDiagnostics();
        if (keepsOneOrTwoLiteralStartFilter(limit)) {
            assertThat(limitSearch).isNull();
        }
        else {
            assertThat(limitSearch).isNotNull();
            assertThat(limitSearch.windowBytesForDiagnostics()).isEqualTo(WindowLiteralSearch.MAXIMUM_WINDOW_BYTES);
        }
        // Where another route applies, the search is left out: a literal prefix, an inner literal,
        // a folded run behind a start literal, and RE2's longest match.
        assertThat(Re2.compile(utf8Slice("Tom.{10,25}river")).windowLiteralSearchForDiagnostics()).isNull();
        CompiledPattern inner = Re2.compile(utf8Slice("\\bTom.{10,25}river"));
        assertThat(inner.innerLiteralSearchForDiagnostics()).isNotNull();
        assertThat(inner.windowLiteralSearchForDiagnostics()).isNull();
        CompiledPattern foldedStartRun = Re2.compile(utf8Slice("\\bthe (?i)Sherlock Holmes"));
        assertThat(foldedStartRun.foldedStartRunForDiagnostics()).isNotNull();
        assertThat(foldedStartRun.windowLiteralSearchForDiagnostics()).isNull();
        assertThat(Re2.compile(utf8Slice(TOM_RIVER), Re2.Options.defaults().setLongestMatch(true)).windowLiteralSearchForDiagnostics()).isNull();
    }

    @Test
    public void testSearchWithoutLiteralSkipsDfa()
    {
        // Count reaches the search, which finds no river, so the forward DFA never starts; find
        // and matcher iteration take it too.
        Slice text = utf8Slice("Tom and Huck went down to the Mississippi with Tom's raft; ".repeat(40));
        for (Language language : Language.values()) {
            CompiledPattern pattern = language.compiledPattern(TOM_HUCK_RIVER);
            assertThat(pattern.count(text)).as(language.name()).isZero();
            assertThat(pattern.find(text)).as(language.name()).isFalse();
            assertThat(pattern.matcher(text).find()).as(language.name()).isFalse();
            assertThat(forwardStateCount(pattern)).as(language.name()).isZero();
        }
    }

    @Test
    public void testFallsBackWhenWindowIsNearStart()
    {
        // A first occurrence whose window starts fewer than the minimum skip past the search start
        // falls back, and the matcher keeps its candidate cursor; one past that does not, and the
        // window search finds the match's start, so a fresh pattern never builds a reverse DFA.
        CompiledPattern pattern = Language.RUST_BYTES_ASCII.compiledPattern(TOM_HUCK_RIVER);
        WindowLiteralSearch search = pattern.windowLiteralSearchForDiagnostics();
        int near = search.windowBytesForDiagnostics() + WindowLiteralSearch.MINIMUM_SKIPPED_BYTES;
        String match = "river is long, said Tom";
        for (int offset : List.of(0, near - 1, near, near + 1)) {
            Slice text = utf8Slice("-".repeat(offset) + match + FILLER);
            boolean nearStart = offset < near;
            long span = search.search(text, 0, text.length(), 0, text.length());
            assertThat(span).as("%s", offset).isEqualTo(nearStart ? WindowLiteralSearch.FALLBACK : ((long) offset << 32) | (offset + match.length()));
            assertThat(search.fallsBackNearStart(text, 0, text.length())).as("%s", offset).isEqualTo(nearStart);
            assertThat(pattern.searchesInnerLiteral(text, 0, text.length())).as("%s", offset).isEqualTo(!nearStart);
            RegexpMatcher matcher = pattern.groupZeroMatcher(text, null);
            assertThat(matcher.find()).as("%s", offset).isTrue();
            assertThat(List.of(matcher.start(), matcher.end())).as("%s", offset).isEqualTo(List.of(offset, offset + match.length()));
            assertThat(matcher.candidateStartRouteCountForDiagnostics()).as("%s", offset).isEqualTo(nearStart ? 1 : 0);
            if (!nearStart) {
                CompiledPattern fresh = Language.RUST_BYTES_ASCII.compiledPattern(TOM_HUCK_RIVER);
                assertThat(fresh.groupZeroMatcher(text, null).find()).as("%s", offset).isTrue();
                assertThat(reverseStateCount(fresh)).as("%s", offset).isZero();
            }
        }
    }

    @Test
    public void testAgreesWithOrdinaryEngine()
    {
        List<Slice> texts = texts();
        for (Map.Entry<String, String> entry : PATTERNS.entrySet()) {
            String pattern = entry.getKey();
            // An alternative without the literal leaves the window search out of the expected
            // engine.
            String ordinary = "(?:" + pattern + ")|\\b\\B";
            for (Language language : Language.values()) {
                String description = "%s %s".formatted(language, pattern);
                CompiledPattern compiled = language.compiledPattern(pattern);
                WindowLiteralSearch search = compiled.windowLiteralSearchForDiagnostics();
                if (keepsOneOrTwoLiteralStartFilter(compiled)) {
                    assertThat(search).as(description).isNull();
                }
                else {
                    assertThat(search).as(description).isNotNull();
                    assertThat(new String(search.literalForDiagnostics(), UTF_8)).as(description).isEqualTo(entry.getValue());
                }
                assertThat(language.compiledPattern(ordinary).windowLiteralSearchForDiagnostics()).as(description).isNull();
                Engine actual = language.compile(pattern);
                Engine expected = language.compile(ordinary);
                for (Slice text : texts) {
                    String textDescription = description + " " + text.toStringUtf8();
                    assertThat(actual.spans(text)).as(textDescription).isEqualTo(expected.spans(text));
                    assertThat(actual.count(text)).as(textDescription).isEqualTo(expected.count(text));
                    assertThat(actual.find(text)).as(textDescription).isEqualTo(expected.find(text));
                }
            }
        }
        // A region inside a Slice that starts inside its backing array, for a ranged find, whose
        // context is the whole Slice, and for a matcher region, which is its own context.
        byte[] backing = ("!".repeat(PADDING) + "--" + FILLER + "Tom0123456789river" + FILLER).getBytes(UTF_8);
        Slice text = wrappedBuffer(backing, PADDING, backing.length - PADDING);
        int start = 2;
        int end = text.length() - 2;
        for (Language language : Language.values()) {
            CompiledPattern pattern = language.compiledPattern(TOM_HUCK_RIVER);
            CompiledPattern ordinary = language.compiledPattern("(?:" + TOM_HUCK_RIVER + ")|\\b\\B");
            int[] actual = new int[2];
            int[] expected = new int[2];
            assertThat(pattern.findInto(text, start, end, actual)).as(language.name()).isTrue();
            assertThat(ordinary.findInto(text, start, end, expected)).as(language.name()).isTrue();
            assertThat(actual).as(language.name()).isEqualTo(expected);
            RegexpMatcher matcher = pattern.matcher(text).reset(text, start, end);
            RegexpMatcher expectedMatcher = ordinary.matcher(text).reset(text, start, end);
            assertThat(matcher.find()).as(language.name()).isTrue();
            assertThat(expectedMatcher.find()).as(language.name()).isTrue();
            assertThat(List.of(matcher.start(), matcher.end())).as(language.name()).isEqualTo(List.of(expectedMatcher.start(), expectedMatcher.end()));
        }
    }

    @Test
    public void testFallsBackOnDenseFailedOccurrences()
    {
        // Lines of back-to-back rivers with no Tom or Huck: every occurrence's tries fail, and the
        // try that starts at the occurrence reads to the end of the dot's reach, about 31 bytes for
        // each 5-byte occurrence. The search falls back within the first 64 bytes of such lines,
        // and the ordinary engine counts the rest.
        CompiledPattern pattern = Language.RUST_BYTES_ASCII.compiledPattern(TOM_HUCK_RIVER);
        WindowLiteralSearch search = pattern.windowLiteralSearchForDiagnostics();
        String lead = "-".repeat(search.windowBytesForDiagnostics() + WindowLiteralSearch.MINIMUM_SKIPPED_BYTES);
        Slice text = utf8Slice(lead + ("river".repeat(60) + "\n").repeat(4) + "Tom went down the river");
        assertThat(search.search(text, 0, text.length(), 0, lead.length() + 64)).isEqualTo(WindowLiteralSearch.FALLBACK);
        assertThat(search.search(text, 0, text.length(), 0, text.length())).isEqualTo(WindowLiteralSearch.FALLBACK);
        assertThat(pattern.count(text)).isEqualTo(1);
    }

    @Test
    public void testKeepsSearchOnSparseFailedOccurrences()
    {
        // Prose in which river turns up once in about a kilobyte, never near Tom or Huck, as in
        // the tom-river benchmark text, where it turns up once in about 13 KB: the tries before
        // each occurrence fail, and the search reaches the match at the end without falling back.
        String prose = "The raft drifted on at night, and nobody on the shore saw it pass by. ".repeat(14);
        String miss = "Then the river took them past the town and the long bank of trees. ";
        String match = "Tom went down the river";
        String text = (prose + miss).repeat(16) + match;
        int matchStart = text.length() - match.length();
        Slice slice = utf8Slice(text);
        for (Language language : Language.values()) {
            WindowLiteralSearch search = language.compiledPattern(TOM_HUCK_RIVER).windowLiteralSearchForDiagnostics();
            assertThat(search.search(slice, 0, slice.length(), 0, slice.length())).as(language.name()).isEqualTo(((long) matchStart << 32) | text.length());
        }
    }

    @Test
    public void testTriesEachStartOnce()
    {
        // Each river's window overlaps the one before it; this fixture relies on a retry of a
        // start already tried tripping the work guard.
        Slice text = utf8Slice("-".repeat(200) + ("river\n" + "-".repeat(74)).repeat(20) + "Tom0123456789river");
        WindowLiteralSearch search = Language.RUST.compiledPattern(TOM_HUCK_RIVER).windowLiteralSearchForDiagnostics();
        assertThat(search.search(text, 0, text.length(), 0, text.length())).isEqualTo((1800L << 32) | 1818);
    }

    @Test
    public void testBooleanFindCapsFailingTry()
    {
        // No match starts at Tom: the first alternative needs an XYZ the text lacks, and its .*
        // stays alive to the end. The window search's try from there reads to where the guard
        // trips, past an X planted 100 bytes on, which the ordinary engine alone, stopping at
        // river Tom, never reads; it stops well before one planted 1024 bytes on, and the search
        // falls back to the ordinary engine.
        String pattern = "(?s)(?:Tom|Huck).{0,5}river.*XYZ|river.{0,5}(?:Tom|Huck)";
        assertBooleanFindReads(pattern, "Tom river Tom", WindowLiteralSearch.FALLBACK, "X", 100, true);
        assertBooleanFindReads(pattern, "Tom river Tom", WindowLiteralSearch.FALLBACK, "X", 1024, false);
    }

    @Test
    public void testBooleanFindStopsAtFirstAccept()
    {
        // The try from x matches xyz, but its leftmost-first span waits on the first
        // alternative's .*QR to the end of the text. A boolean find's try stops at the xyz match,
        // before a Q planted 100 bytes on, inside the guard's cap, which leaves .*QR waiting for
        // R. Nor does the find read a zzz planted in the lead, which the ordinary engine's start
        // literal filter would stop at and its DFA read on the way to the match.
        String pattern = "(?:x|zz){0,4}xyz.*QR|xyz";
        long span = ((long) BOOLEAN_FIND_MATCH_START << 32) | (BOOLEAN_FIND_MATCH_START + 3);
        assertBooleanFindReads(pattern, "xyz", span, "Q", 100, false);
        assertBooleanFindReads(pattern, "xyz", span, "zzz", -300, false);
    }

    // A boolean find over a text of `match`, after a lead, and a long tail, in which `plant`
    // replaces the bytes from `offset` bytes past the match's end. The window search's
    // searchExists returns `exists` on the text, and the find reads the planted bytes if `read`:
    // planted bytes that any DFA read build a state the plain text never builds.
    private static void assertBooleanFindReads(String pattern, String match, long exists, String plant, int offset, boolean read)
    {
        String lead = "-".repeat(BOOLEAN_FIND_MATCH_START) + match;
        byte[] text = (lead + "a".repeat(4096)).getBytes(UTF_8);
        byte[] planted = text.clone();
        byte[] plantBytes = plant.getBytes(UTF_8);
        System.arraycopy(plantBytes, 0, planted, lead.length() + offset, plantBytes.length);
        for (Language language : Language.values()) {
            // The direct search runs on a pattern of its own, so the states its tries build stay
            // out of the counts compared.
            CompiledPattern direct = language.compiledPattern(pattern);
            WindowLiteralSearch search = direct.windowLiteralSearchForDiagnostics();
            if (keepsOneOrTwoLiteralStartFilter(direct)) {
                assertThat(search).as(language.name()).isNull();
                continue;
            }
            assertThat(search).as(language.name()).isNotNull();
            assertThat(search.searchExists(wrappedBuffer(text), 0, text.length, 0, text.length)).as(language.name()).isEqualTo(exists);
            CompiledPattern control = language.compiledPattern(pattern);
            CompiledPattern probe = language.compiledPattern(pattern);
            assertThat(control.find(wrappedBuffer(text))).as(language.name()).isTrue();
            assertThat(probe.find(wrappedBuffer(planted))).as(language.name()).isTrue();
            long controlStates = forwardStateCount(control) + reverseStateCount(control);
            assertThat(forwardStateCount(probe) + reverseStateCount(probe) != controlStates).as("%s %s at %s", language, plant, offset).isEqualTo(read);
        }
    }

    @Test
    public void testBooleanTryCapKeepsAssertionContext()
    {
        // A boolean find's try from Tom ends one byte past what the guard allows, at `cap`, and
        // its last step reads the byte there as context, so an assertion at the cap sees the
        // text's next byte, not an end of text. Each case puts the end of the a run, where the
        // assertion decides, one byte before the cap, at it, and one byte after it, followed by a
        // byte that satisfies the assertion or one that fails it, and compares every route's
        // answer with the ordinary engine's. In RE2 a match decided at or before the cap is the
        // search's own; one decided after it falls back. With 64-byte vectors the pattern's
        // {Tom, riv} start literal filter leaves the search out, and only the answers are compared.
        int tom = 400;
        int literalStart = tom + "Tom ".length();
        int cap = tom + literalStart / 2 + WindowLiteralSearch.ANCHORED_WORK_ALLOWANCE_BYTES - WindowLiteralSearch.ANCHORED_TRY_BYTES + 1;
        String lead = "-".repeat(tom) + "Tom riverZ";
        String tail = "-".repeat(500);
        Map<String, List<String>> assertions = Map.of(
                "\\b", List.of("-" + tail, "b" + tail),
                "(?m:$)", List.of("\n" + tail, "b" + tail),
                "\\z", List.of("", "b" + tail));
        for (Map.Entry<String, List<String>> entry : assertions.entrySet()) {
            String pattern = "Tom.{0,5}riverZa*" + entry.getKey() + "|river.{0,5}Huck";
            String ordinary = "(?:" + pattern + ")|\\b\\B";
            for (int runEnd = cap - 1; runEnd <= cap + 1; runEnd++) {
                for (String after : entry.getValue()) {
                    Slice text = utf8Slice(lead + "a".repeat(runEnd - lead.length()) + after);
                    boolean matches = after.equals(entry.getValue().getFirst());
                    String description = "%s at cap %s, %s".formatted(pattern, runEnd - cap, matches ? "satisfied" : "failed");
                    CompiledPattern direct = Language.RE2.compiledPattern(pattern);
                    WindowLiteralSearch search = direct.windowLiteralSearchForDiagnostics();
                    if (keepsOneOrTwoLiteralStartFilter(direct)) {
                        assertThat(search).as(description).isNull();
                    }
                    else {
                        assertThat(search).as(description).isNotNull();
                        long span = search.searchExists(text, 0, text.length(), 0, text.length());
                        if (matches) {
                            assertThat(span).as(description).isEqualTo(runEnd <= cap ? ((long) tom << 32) | runEnd : WindowLiteralSearch.FALLBACK);
                        }
                        else {
                            assertThat(span).as(description).isLessThan(0);
                        }
                    }
                    for (Language language : Language.values()) {
                        assertThat(language.compile(pattern).find(text)).as("%s %s", language, description).isEqualTo(language.compile(ordinary).find(text)).isEqualTo(matches);
                    }
                }
            }
        }
    }

    @Test
    public void testLeavesTwoLiteralStartFilterToWideVectors()
    {
        // tom-river's DFA scans for the start literal filter {Tom, riv} at every vector width, and
        // Holmes|Watson's start bytes are rare enough that it takes {Hol, Wat} only with 64-byte
        // vectors. There the two-literal scan is the fast one, so the search is left out; on
        // narrower vectors it replaces the filter, or the start-byte scan.
        boolean wideVectors = VectorSupport.isAvailable() && VectorSupport.preferredByteLanes() >= 64;
        if (VectorSupport.isAvailable()) {
            assertThat(scannerWidth(Language.RE2.compiledPattern(TOM_RIVER))).isEqualTo(2);
        }
        for (String pattern : List.of(TOM_RIVER, HOLMES_WATSON)) {
            for (Language language : Language.values()) {
                CompiledPattern compiled = language.compiledPattern(pattern);
                String description = "%s %s".formatted(language, pattern);
                assertThat(compiled.windowLiteralSearchForDiagnostics() == null).as(description).isEqualTo(wideVectors);
                if (wideVectors) {
                    assertThat(scannerWidth(compiled)).as(description).isEqualTo(2);
                }
            }
        }
        // The second bytes of é and ê differ in two bits, so only the wider groups that the DFA's
        // filter takes give it {[éê]x, riv}, and the search steps aside for that filter too.
        CompiledPattern wideGroups = Language.RE2.compiledPattern("[éê]x.{10,25}river|river.{10,25}[éê]x");
        Prog wideGroupsProgram = wideGroups.forwardProgramForDiagnostics();
        assertThat(Dfa.DfaInstance.givesOneBitStartLiteralFilter(wideGroupsProgram, Dfa.DfaInstance.Kind.FIRST_MATCH)).isFalse();
        assertThat(Dfa.DfaInstance.searchesStartLiteralFilter(wideGroupsProgram, Dfa.DfaInstance.Kind.FIRST_MATCH)).isEqualTo(VectorSupport.isAvailable());
        assertThat(wideGroups.windowLiteralSearchForDiagnostics() == null).isEqualTo(wideVectors);
        if (VectorSupport.isAvailable()) {
            assertThat(scannerWidth(wideGroups)).isEqualTo(2);
        }
        // A filter of three literals keeps the search at every width.
        for (Language language : Language.values()) {
            assertThat(language.compiledPattern(TOM_HUCK_RIVER).windowLiteralSearchForDiagnostics()).as(language.name()).isNotNull();
        }
    }

    @Test
    public void testStartFilterScannerWidthCountsSlots()
    {
        // The yield to a wide start filter counts the slots its scanner pads the literals to, so
        // a filter of one literal, [Tt]om, one masked filter group, takes two slots as {Tom, riv}
        // does, and {Tom, Huc, riv} takes four.
        assumeTrue(VectorSupport.isAvailable(), "requires the vector scanners");
        assertThat(analyzedScannerWidth("(?:[Tt]om\\s.{0,6}|[Tt]omas.{0,3}|){1,2}[Tt]omriver")).isEqualTo(2);
        assertThat(analyzedScannerWidth(TOM_RIVER)).isEqualTo(2);
        assertThat(analyzedScannerWidth(TOM_HUCK_RIVER)).isEqualTo(4);
    }

    @Test
    public void testAnalysisMeasuresNestedPrefixesOnce()
    {
        // Each level wraps the expression below it as (?:below|q)w, so every concatenation's
        // first element holds every level beneath it. The walk for each candidate literal measures
        // the width of each enclosing prefix, and without the widths kept from deeper levels it
        // measured every node beneath each level again, once per level and candidate.
        int depth = 120;
        StringBuilder pattern = new StringBuilder("(?:".repeat(depth)).append("(?:");
        for (int index = 0; index < 500; index++) {
            pattern.append(index == 0 ? "" : "|").append((char) ('0' + index % 10)).append((char) ('A' + index / 10 % 26)).append((char) ('a' + index / 260));
        }
        pattern.append(")").append("|q)w".repeat(depth));
        ParseResult parsed = RegexpParser.parse(utf8Slice(pattern.toString()), Regexp.LIKE_PERL);
        Regexp regexp = Simplifier.simplify(parsed.regexp(), parsed.caseFolding());
        // The walks cover the first 16 literals of the innermost alternation, the candidate cap,
        // and visit each node a few times at most.
        assertThat(WindowLiteralSearch.selectionVisitsForDiagnostics(regexp)).isLessThan(16L * 4 * nodeCount(regexp));
    }

    private static List<Slice> texts()
    {
        List<byte[]> pieces = new ArrayList<>();
        for (String text : List.of(
                // Occurrences closer than the window, a failed window followed by an occurrence
                // whose window overlaps it, and matches with 10 and 25 characters between.
                "river river Tom river 0123456789Tom river",
                "Tom 012 river ab river 0123456789Tom",
                "Tom0123456789river Tom0123456789012345678901234river riverabcdefghijklmnopqrstuvwxyTom",
                // The match from Tom runs past the first river and ends at a later one.
                "Tom0123456789river01234river",
                // A word boundary needed before Tom and after the final Tom.
                "xTom0123456789river aTom river0123456789Toms river0123456789Tom.",
                // Overlapping occurrences, where the start at the first fails and the start at the
                // next, inside the first, matches.
                "aaaaaaa abaaaa aaabaaa ab ababaaa zxyxyz xyzxyz zzxyz xyxyxyz",
                "aaaa1 caaaa2 abaaaab3 aaaaa4",
                // Line feeds, which only the (?s) dot matches.
                "Tom01234\n56789river river\n0123456789Tom",
                // A match that starts on a byte of 128 or more.
                "éx0123456789river")) {
            pieces.add(text.getBytes(UTF_8));
        }
        // 10 and 25 characters of two-, three-, and four-byte UTF-8 between Tom and river, which
        // a UTF-8 dot reads as one character each and a byte dot as several bytes.
        for (String character : List.of("é", "€", "😀")) {
            for (int count : List.of(9, 10, 25, 26)) {
                String gap = character.repeat(count);
                pieces.add(("Tom" + gap + "river and river" + gap + "Tom").getBytes(UTF_8));
            }
        }
        // Malformed UTF-8 inside the window: a bare continuation byte, a truncated sequence, a
        // byte that never starts one, and an overlong encoding.
        for (byte[] malformed : List.of(new byte[] {(byte) 0x80}, new byte[] {(byte) 0xE2, (byte) 0x82}, new byte[] {(byte) 0xFF}, new byte[] {(byte) 0xC0, (byte) 0xAF})) {
            DynamicSliceOutput output = new DynamicSliceOutput(64);
            output.writeBytes("Tom".getBytes(UTF_8));
            for (int index = 0; index < 10; index++) {
                output.writeBytes(malformed);
            }
            output.writeBytes("river xyz".getBytes(UTF_8));
            output.writeBytes(malformed);
            output.writeBytes("xyz river".getBytes(UTF_8));
            for (int index = 0; index < 12; index++) {
                output.writeBytes(malformed);
            }
            output.writeBytes("Tom".getBytes(UTF_8));
            pieces.add(output.slice().getBytes());
        }
        String[] tokens = {"Tom", "river", "Huck", "x", "xy", "z", "aa", "ab", "a", " ", "\n", "-", "0", "é", "€", "😀", "\t"};
        Random random = new Random(20261108);
        for (int textIndex = 0; textIndex < 200; textIndex++) {
            DynamicSliceOutput output = new DynamicSliceOutput(256);
            int length = random.nextInt(240);
            while (output.size() < length) {
                if (random.nextInt(30) == 0) {
                    output.writeByte(random.nextBoolean() ? 0x80 : 0xE2);
                }
                else {
                    output.writeBytes(tokens[random.nextInt(tokens.length)].getBytes(UTF_8));
                }
            }
            pieces.add(output.slice().getBytes());
        }
        // Each text also follows filler, so that its first occurrence lies past the near-start
        // fallback and the search runs. The filled text is a Slice that starts inside its backing
        // array, past padding no match can start in, as is one long enough for the vector scan.
        List<Slice> texts = new ArrayList<>();
        for (byte[] piece : pieces) {
            texts.add(wrappedBuffer(piece));
            texts.add(paddedView(FILLER.getBytes(UTF_8), piece));
        }
        texts.add(paddedView(("-".repeat(2048) + "Tom0123456789river").getBytes(UTF_8), FILLER.getBytes(UTF_8)));
        return texts;
    }

    // A Slice of `first` then `second` that starts PADDING bytes into its backing array.
    private static Slice paddedView(byte[] first, byte[] second)
    {
        byte[] backing = new byte[PADDING + first.length + second.length];
        Arrays.fill(backing, 0, PADDING, (byte) '!');
        System.arraycopy(first, 0, backing, PADDING, first.length);
        System.arraycopy(second, 0, backing, PADDING + first.length, second.length);
        return wrappedBuffer(backing, PADDING, first.length + second.length);
    }

    private static int windowBytes(Language language, String pattern)
    {
        WindowLiteralSearch search = language.compiledPattern(pattern).windowLiteralSearchForDiagnostics();
        assertThat(search).as(pattern).isNotNull();
        return search.windowBytesForDiagnostics();
    }

    // Whether the pattern's first-match DFA scans a start literal filter of one or two literals,
    // two scanner slots, with 64-byte vectors, where the window literal search is left out.
    private static boolean keepsOneOrTwoLiteralStartFilter(CompiledPattern pattern)
    {
        return VectorSupport.isAvailable() && VectorSupport.preferredByteLanes() >= 64 && scannerWidth(pattern) == 2;
    }

    private static int scannerWidth(CompiledPattern pattern)
    {
        StartLiteralFilter filter = pattern.forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).startLiteralFilter();
        return filter == null ? 0 : filter.values().length / 3;
    }

    // The scanner width the window literal search's yield reads, selected without the DFA.
    private static int analyzedScannerWidth(String pattern)
    {
        return Dfa.DfaInstance.startLiteralFilterScannerWidth(Re2.compile(utf8Slice(pattern)).forwardProgramForDiagnostics(), Dfa.DfaInstance.Kind.FIRST_MATCH);
    }

    private static long nodeCount(Regexp regexp)
    {
        long count = 1;
        for (int childIndex = 0; childIndex < regexp.childCount(); childIndex++) {
            count += nodeCount(regexp.child(childIndex));
        }
        return count;
    }

    private static long forwardStateCount(CompiledPattern pattern)
    {
        return stateCount(pattern.forwardProgramForDiagnostics());
    }

    private static long reverseStateCount(CompiledPattern pattern)
    {
        Prog reverse = pattern.reverseProgramIfComputedForDiagnostics();
        return reverse == null ? 0 : stateCount(reverse);
    }

    private static long stateCount(Prog program)
    {
        long states = 0;
        for (Dfa.DfaInstance.Kind kind : Dfa.DfaInstance.Kind.values()) {
            Dfa.DfaInstance dfa = program.cachedDfaIfPresent(kind);
            if (dfa != null) {
                states += dfa.cacheSnapshot().stateCount();
            }
        }
        return states;
    }

    private enum Language
    {
        RE2, RE2_LATIN1, JAVA, TRINO, RUST, RUST_BYTES, RUST_BYTES_ASCII;

        boolean byteDot()
        {
            return this == RE2_LATIN1 || this == RUST_BYTES_ASCII;
        }

        Engine compile(String pattern)
        {
            return this == TRINO ? trinoEngine(TrinoRegexp.compile(utf8Slice(pattern))) : regexpEngine(compiledPattern(pattern));
        }

        CompiledPattern compiledPattern(String pattern)
        {
            Slice slice = utf8Slice(pattern);
            return switch (this) {
                case RE2 -> Re2.compile(slice);
                case RE2_LATIN1 -> Re2.compile(slice, Re2.Options.latin1());
                case JAVA -> JavaRegexp.compile(slice);
                case TRINO -> TrinoRegexp.compile(slice).re2();
                case RUST -> RustRegexp.compile(slice);
                case RUST_BYTES -> RustRegexp.compile(slice, RustRegexp.Options.defaults().setBytes(true));
                case RUST_BYTES_ASCII -> RustRegexp.compile(slice, RustRegexp.Options.defaults().setBytes(true).setUnicode(false));
            };
        }
    }

    private interface Engine
    {
        List<String> spans(Slice text);

        long count(Slice text);

        boolean find(Slice text);
    }

    private static Engine regexpEngine(CompiledPattern pattern)
    {
        return new Engine()
        {
            @Override
            public List<String> spans(Slice text)
            {
                List<String> spans = new ArrayList<>();
                RegexpMatcher boundaries = pattern.matcher(text, 0);
                while (boundaries.find()) {
                    spans.add(boundaries.start() + "-" + boundaries.end());
                }
                RegexpMatcher groups = pattern.matcher(text);
                while (groups.find()) {
                    for (int group = 0; group <= groups.groupCount(); group++) {
                        spans.add(group + ":" + groups.start(group) + "-" + groups.end(group));
                    }
                }
                return spans;
            }

            @Override
            public long count(Slice text)
            {
                return pattern.count(text);
            }

            @Override
            public boolean find(Slice text)
            {
                return pattern.find(text);
            }
        };
    }

    private static Engine trinoEngine(TrinoRegexp pattern)
    {
        return new Engine()
        {
            @Override
            public List<String> spans(Slice text)
            {
                List<String> spans = new ArrayList<>();
                TrinoRegexpMatcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    for (int group = 0; group <= matcher.groupCount(); group++) {
                        spans.add(group + ":" + matcher.start(group) + "-" + matcher.end(group));
                    }
                }
                return spans;
            }

            @Override
            public long count(Slice text)
            {
                return pattern.count(text);
            }

            @Override
            public boolean find(Slice text)
            {
                return pattern.contains(text);
            }
        };
    }
}
