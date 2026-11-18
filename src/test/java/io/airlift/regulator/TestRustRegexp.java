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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.airlift.regulator.CompiledPattern.DFA_QUIT_STREAK_LIMIT;
import static io.airlift.regulator.TestRe2Api.assertNullInputFailures;
import static io.airlift.regulator.TestingPatternShapes.compileAtSmallestBudget;
import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestRustRegexp
{
    private static final List<String> DFA_ROUTE_PATTERNS = List.of(
            "\\bfoo\\b",
            "\\B",
            "(?mR)^\\w+$",
            "\\b{start}\\w+\\b{end}",
            "\\b{start-half}",
            "\\b{end-half}",
            "\\<",
            "\\>",
            "(?-u)\\b{start}");
    // Every text of up to four bytes over an ASCII word byte, an ASCII non-word byte, CR, LF, both
    // bytes of é, and a byte that is never UTF-8.
    private static final List<byte[]> SHORT_TEXTS = TestingEngineAgreement.shortTexts((byte) 'a', (byte) ' ', (byte) '\r', (byte) '\n', (byte) 0xC3, (byte) 0xA9, (byte) 0xFF);
    // Every text of up to four bytes over an ASCII word byte, an ASCII non-word byte, the lead of
    // Cyrillic letters, which are all word characters, the lead E2, whose characters include both
    // classes, and three continuation bytes: ж, Д, —, and U+2D80 among them.
    private static final List<byte[]> LEAD_TEXTS = TestingEngineAgreement.shortTexts((byte) 'a', (byte) ' ', (byte) 0xD0, (byte) 0xB6, (byte) 0xE2, (byte) 0x80, (byte) 0x94);
    // One match of the Cyrillic dense-operation pattern and the space after it.
    private static final String DENSE_CYRILLIC_WORD = "аааааббббббаааааббббббаааа ";

    @Test
    public void testParse()
    {
        PatternInfo plain = RustRegexp.parse(utf8Slice("plain"));
        assertThat(plain.capturingGroupCount()).isZero();
        assertThat(plain.namedCapturingGroups()).isEmpty();

        Slice pattern = utf8Slice("(?<word>\\w+)(b)?(?P<never>[^\\s\\S])");
        PatternInfo info = RustRegexp.parse(pattern);
        assertThat(info.capturingGroupCount()).isEqualTo(3);
        assertThat(info.namedCapturingGroups()).isEqualTo(Map.of("word", 1, "never", 3));
        assertThat(info.capturingGroupNames()).isEqualTo(Map.of(1, "word", 3, "never"));

        RustRegexp compiled = RustRegexp.compile(pattern);
        assertThat(info.capturingGroupCount()).isEqualTo(compiled.capturingGroupCount());
        assertThat(info.namedCapturingGroups()).isEqualTo(compiled.namedCapturingGroups());
        assertThat(info.capturingGroupNames()).isEqualTo(compiled.capturingGroupNames());
    }

    @Test
    public void testParseOptionsAndErrors()
    {
        PatternInfo comments = RustRegexp.parse(utf8Slice("#(ignored)\n(a)"), RustRegexp.Options.defaults().setComments(true));
        assertThat(comments.capturingGroupCount()).isEqualTo(1);
        assertThat(comments.namedCapturingGroups()).isEmpty();

        PatternInfo tinyMemory = RustRegexp.parse(utf8Slice("a"), RustRegexp.Options.defaults().setMaxMemory(1));
        assertThat(tinyMemory.capturingGroupCount()).isZero();
        assertThat(tinyMemory.namedCapturingGroups()).isEmpty();

        assertThatThrownBy(() -> RustRegexp.parse(utf8Slice("(?=a)")))
                .isInstanceOf(RegexpParseException.class)
                .hasMessage("unsupported regular-expression construct: UnsupportedLookAround at byte offset 0");

        assertThatThrownBy(() -> RustRegexp.parse(utf8Slice("(a)"), RustRegexp.Options.defaults().setNestLimit(0)))
                .isInstanceOf(RegexpParseException.class)
                .hasMessage("pattern exceeds the supported parser limit: NestLimitExceeded at byte offset 0");
    }

    @Test
    public void testRepetitionCountsAboveOneThousand()
    {
        // As in the crate, a count above 1,000 compiles when its expansion fits the memory budget.
        RustRegexp regexp = RustRegexp.compile(utf8Slice("a{20,1024}"));
        int[] groups = new int[2];
        assertThat(regexp.findInto(utf8Slice("a".repeat(1030)), groups)).isTrue();
        assertThat(groups).containsExactly(0, 1024);
        assertThat(regexp.find(utf8Slice("a".repeat(19)))).isFalse();
    }

    @Test
    public void testParseErrorDoesNotRetainMutablePatternStorage()
    {
        Slice pattern = utf8Slice("é(");
        assertThatThrownBy(() -> RustRegexp.parse(pattern))
                .isInstanceOfSatisfying(RegexpParseException.class, exception -> {
                    pattern.setByte(2, '[');
                    assertThat(exception.invalidPatternSegment()).isEqualTo(utf8Slice("("));
                    assertThat(exception.byteOffset()).isEqualTo(2);
                });
    }

    @Test
    public void testPublicApiMatchesJavaRegexp()
    {
        assertThat(instanceMethods(RustRegexp.class)).isEqualTo(instanceMethods(JavaRegexp.class));
    }

    @Test
    public void testCompiledPatternDoesNotRetainMutablePatternStorage()
    {
        Slice pattern = utf8Slice("(?<name>a)");
        RustRegexp regexp = RustRegexp.compile(pattern);

        pattern.setByte(3, 'X');

        assertThat(regexp.namedCapturingGroups()).containsOnlyKeys("name");
        assertThat(regexp.pattern()).isEqualTo(utf8Slice("(?<name>a)"));
    }

    @Test
    public void testNullArguments()
    {
        assertThatThrownBy(() -> RustRegexp.compile(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("pattern is null");
        assertThatThrownBy(() -> RustRegexp.compile(utf8Slice("a"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("options is null");
        assertThatThrownBy(() -> RustRegexp.parse(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("pattern is null");
        assertThatThrownBy(() -> RustRegexp.parse(utf8Slice("a"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("options is null");
        // Every operation but count names a null input `input`, and checks it first, as do the
        // entries of a generated class.
        RustRegexp generated = RustRegexp.compile(utf8Slice("x([^/]+)/"), RustRegexp.Options.defaults().setGenerateBytecode(true));
        assertThat(generated.usesGeneratedBytecode()).isTrue();
        for (RustRegexp pattern : List.of(RustRegexp.compile(utf8Slice("a*")), RustRegexp.compile(utf8Slice("(a)(b)?")), RustRegexp.compile(utf8Slice("abc")), generated)) {
            assertNullInputFailures(pattern, operation ->
                    operation.equals("count") ? "NullPointerException: text is null" : "NullPointerException: input is null");
        }
    }

    @Test
    public void testMatcherAndRangeApis()
    {
        RustRegexp regexp = RustRegexp.compile(utf8Slice("(?<value>a)(b)?"));
        Slice input = utf8Slice("xxa");

        assertThat(regexp.find(input, 2, 3)).isTrue();
        assertThat(regexp.lookingAt(input, 2, 3)).isTrue();
        assertThat(regexp.matches(input, 2, 3)).isTrue();

        int[] groups = new int[6];
        assertThat(regexp.findInto(input, 2, 3, groups)).isTrue();
        assertThat(groups).containsExactly(2, 3, 2, 3, -1, -1);

        MatchResult result = regexp.findResult(input, 2, 3);
        assertThat(result.groupUtf8("value")).isEqualTo("a");
        assertThat(regexp.lookingAtResult(input, 2, 3)).isNotNull();
        assertThat(regexp.matchesResult(input, 2, 3)).isNotNull();

        RegexpMatcher matcher = regexp.matcher(input);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group("value").toStringUtf8()).isEqualTo("a");

        assertThatThrownBy(() -> regexp.find(input, -1, input.length()))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    public void testAlternationIsLeftmostFirstOverSharedPrefix()
    {
        // The crate lifts the a* both alternatives share and matches all of "ab"; Regulator keeps
        // leftmost-first (REGULATOR_DECISIONS.md, Rust Frontend).
        int[] groups = new int[2];
        assertThat(RustRegexp.compile(utf8Slice("a*a|a*b")).findInto(utf8Slice("ab"), 0, 2, groups)).isTrue();
        assertThat(groups).containsExactly(0, 1);
    }

    @Test
    public void testRangeStartingInsideCharacter()
    {
        // The crate's Regex never reports an empty match that splits a character, and an
        // anchored search cannot move past one.
        RustRegexp regexp = RustRegexp.compile(utf8Slice(""));
        Slice input = utf8Slice("𝛃");
        int[] groups = new int[2];

        assertThat(regexp.find(input, 1, 3)).isFalse();
        assertThat(regexp.findInto(input, 1, 3, groups)).isFalse();
        assertThat(groups).containsExactly(-1, -1);
        assertThat(regexp.findInto(input, 1, 4, groups)).isTrue();
        assertThat(groups).containsExactly(4, 4);

        assertThat(regexp.lookingAt(input, 1, 4)).isFalse();
        assertThat(regexp.lookingAtInto(input, 1, 4, groups)).isFalse();
        assertThat(groups).containsExactly(-1, -1);
        assertThat(regexp.matches(input, 1, 1)).isFalse();
        assertThat(regexp.matchesInto(input, 1, 1, groups)).isFalse();

        RegexpMatcher matcher = regexp.matcher(input).reset(input, 1, 4);
        assertThat(matcher.lookingAt()).isFalse();
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(4);
        assertThat(matcher.find()).isFalse();
    }

    @Test
    public void testBytesRangeStartingInsideCharacter()
    {
        // The crate's bytes::Regex reports an empty match that splits a character, so an anchored
        // search succeeds there.
        RustRegexp regexp = RustRegexp.compile(utf8Slice(""), RustRegexp.Options.bytes());
        Slice input = utf8Slice("𝛃");
        int[] groups = new int[2];

        assertThat(regexp.find(input, 1, 4)).isTrue();
        assertThat(regexp.findInto(input, 1, 4, groups)).isTrue();
        assertThat(groups).containsExactly(1, 1);

        assertThat(regexp.lookingAt(input, 1, 4)).isTrue();
        assertThat(regexp.lookingAtInto(input, 1, 4, groups)).isTrue();
        assertThat(groups).containsExactly(1, 1);
        assertThat(regexp.matches(input, 1, 1)).isTrue();
        assertThat(regexp.matchesInto(input, 1, 1, groups)).isTrue();
        assertThat(groups).containsExactly(1, 1);

        RegexpMatcher matcher = regexp.matcher(input).reset(input, 1, 4);
        assertThat(matcher.lookingAt()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
        assertThat(matcher.reset(input, 1, 1).matches()).isTrue();
        assertThat(matcher.start()).isEqualTo(1);
    }

    @Test
    public void testPatternMustBeUtf8()
    {
        // Even the crate's bytes API, which matches bytes that are not UTF-8, takes its patterns
        // as strings.
        Slice pattern = Slices.wrappedBuffer(new byte[] {'a', (byte) 0xFF});
        for (RustRegexp.Options options : List.of(RustRegexp.Options.defaults(), RustRegexp.Options.bytes())) {
            assertThatThrownBy(() -> RustRegexp.compile(pattern, options))
                    .isInstanceOfSatisfying(RegexpParseException.class, exception -> {
                        assertThat(exception.errorCode()).isEqualTo(RegexpParseErrorCode.BAD_UTF8);
                        assertThat(exception.byteOffset()).isEqualTo(1);
                    });
        }
    }

    // Property names come from the Unicode 18.0 database, so they include versions and scripts
    // newer than the crate's Unicode 16.0 tables, such as Jurchen, added in 18.0.
    @Test
    public void testUnicodePropertiesFollowDatabase()
    {
        Slice jurchen = utf8Slice(Character.toString(0x18E00));
        assertThat(RustRegexp.compile(utf8Slice("\\p{Age=18.0}")).matches(jurchen)).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("\\p{Age=17.0}")).matches(jurchen)).isFalse();
        assertThat(RustRegexp.compile(utf8Slice("\\p{Jurchen}")).matches(jurchen)).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("\\p{scx=Jurc}")).matches(jurchen)).isTrue();

        // Age holds every character assigned in its version or earlier, and Script_Extensions
        // reads a character's extension list: U+0640 ARABIC TATWEEL is Common, and its list
        // names Arabic.
        assertThat(RustRegexp.compile(utf8Slice("\\p{Age=6.0}")).matches(utf8Slice("A"))).isTrue();
        Slice tatweel = utf8Slice(Character.toString(0x640));
        assertThat(RustRegexp.compile(utf8Slice("\\p{scx=Arab}")).matches(tatweel)).isTrue();
        assertThat(RustRegexp.compile(utf8Slice("\\p{scx=Common}")).matches(tatweel)).isFalse();
    }

    @Test
    public void testRustAssertionsAgreeInBoundedEngines()
    {
        // These engines run the searches the DFA and OnePass do not, including a DFA search that
        // quits, so both must evaluate the assertions.
        assertBoundedEngines("(?mR)^é$", "\r\né\r\n", 2, 4);
        assertBoundedEngines("\\b{start}é\\b{end}", "!é!", 1, 3);
        assertBoundedEngines("(?-u)\\b{start}a\\b{end}", "!a!", 1, 2);
        assertBoundedEngines("(?-u)\\b{start}é", "é", -1, -1);
        assertBoundedEngines("\\b{end-half}", "ab", 2, 2);
        assertBoundedEngines("\\<é", "aé é", 4, 6);
        // Different assertions in one program stay distinct.
        assertBoundedEngines("\\b{start}a|\\b{end}b", "b", -1, -1);
        assertBoundedEngines("\\b\\b{start}", "a b", 0, 0);

        RegexpMatcher matcher = RustRegexp.compile(utf8Slice("\\b\\b{start}")).matcher(utf8Slice("a b"));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(0);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(2);
        assertThat(matcher.find()).isFalse();
        assertThat(matcher.find()).isFalse();
    }

    @Test
    public void testRustAssertionsUseForwardAndReverseDfa()
    {
        Slice text = utf8Slice("x foo bar\r\nbaz");
        for (RustRegexp.Options options : List.of(RustRegexp.Options.defaults(), RustRegexp.Options.bytes())) {
            for (String pattern : DFA_ROUTE_PATTERNS) {
                RustRegexp compiled = RustRegexp.compile(utf8Slice(pattern), options);
                Prog forward = compiled.forwardProgramForDiagnostics();
                assertThat(forward.hasDfaUnsupportedAssertions()).as(pattern).isFalse();
                assertThat(compiled.findInto(text, new int[2])).as(pattern).isTrue();
                assertThat(forward.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).as(pattern).isNotNull();
            }

            // A match of variable length takes its start from the reverse program.
            RustRegexp words = RustRegexp.compile(utf8Slice("\\b{start}\\w+\\b{end}"), options);
            int[] groups = new int[2];
            assertThat(words.findInto(utf8Slice("!! foo"), groups)).isTrue();
            assertThat(groups).containsExactly(3, 6);
            Prog reverse = words.reverseProgramIfComputedForDiagnostics();
            assertThat(reverse).isNotNull();
            assertThat(reverse.cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH)).isNotNull();
        }
    }

    @Test
    public void testRustAssertionDfaAgreesWithNfaExhaustively()
    {
        // Every short text, as a view between bytes outside ASCII, searched over every range with
        // the view as context: the DFA finds what the NFA finds, or quits, which it may do only on
        // malformed text in a program with a Unicode word assertion.
        List<String> patterns = new ArrayList<>(DFA_ROUTE_PATTERNS);
        patterns.replaceAll(pattern -> pattern.replace("foo", "a"));
        // In (?mR)^\b, the word boundary is reached only after the line start holds, so the state
        // that evaluates the line start must quit before it needs the boundary; \b(?mR)$ does the
        // same in the reverse program.
        patterns.addAll(List.of("a\\b", "(?mR)^\\b", "\\b(?mR)$", "(?mR)$", "(?mR)^"));

        for (boolean bytes : new boolean[] {false, true}) {
            RustRegexp.Options options = RustRegexp.Options.defaults().setBytes(bytes);
            for (String pattern : patterns) {
                Prog forward = compileRustProgram(pattern, options, false);
                Prog reverse = compileRustProgram(pattern, options, true);
                TestingEngineAgreement.assertDfaAgreesWithNfa(pattern, forward, reverse, SHORT_TEXTS, "bytes " + bytes);
                TestingEngineAgreement.assertDfaAgreesWithNfa(pattern, forward, reverse, LEAD_TEXTS, "bytes " + bytes);
            }
        }
    }

    @Test
    public void testUnicodeWordAssertionsDecideOnDfa()
    {
        // The forward DFA decides a Unicode word assertion beside a character outside ASCII from
        // the character's lead byte, or from the text where the lead holds characters of both
        // classes, and the reverse DFA from the text, so both find what the crate finds without
        // quitting on valid text. Each pattern is compiled once, so a transition cached for one
        // text serves the next.
        List<Map.Entry<String, List<String>>> cases = List.of(
                Map.entry("\\b\\w+\\b", List.of("жж жж", "0-4", "5-9")),
                // A combining mark, an Arabic-Indic digit, a connector, and ZWNJ are word characters.
                Map.entry("\\b\\w+\\b", List.of("a\u0301 ١ a‿b a\u200Cb", "0-3", "4-6", "7-12", "13-18")),
                Map.entry("\\bfoo\\b", List.of("☃foo☃", "3-6")),
                Map.entry("\\bfoo\\b", List.of("foo—", "0-3")),
                Map.entry("foo\\b", List.of("foo☃", "0-3")),
                Map.entry("foo\\b", List.of("fooж")),
                Map.entry("a|é\\b", List.of("aé", "0-1", "1-3")),
                Map.entry("\\b{start}\\w+\\b{end}", List.of("!ж", "1-3")),
                Map.entry("\\B", List.of("жж", "2-2")),
                Map.entry("(?-u:\\b)\\B", List.of("aж", "1-1")),
                // × shares its lead with letters; ZWNJ and — share two bytes but not their class.
                Map.entry("a\\b{end-half}", List.of("a× a\u200C a—", "0-1", "9-10")),
                Map.entry("\\b{start-half}a", List.of("×a", "2-3")));
        Map<String, Prog> forwardPrograms = new HashMap<>();
        Map<String, Prog> reversePrograms = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : cases) {
            Prog forward = forwardPrograms.computeIfAbsent(entry.getKey(), pattern -> compileRustProgram(pattern, RustRegexp.Options.defaults(), false));
            Prog reverse = reversePrograms.computeIfAbsent(entry.getKey(), pattern -> compileRustProgram(pattern, RustRegexp.Options.defaults(), true));
            List<String> spans = entry.getValue().subList(1, entry.getValue().size());
            assertDfaSpans(entry.getKey(), forward, reverse, utf8Slice(entry.getValue().get(0)), spans);
        }

        // Malformed bytes are not word characters; a lead whose character never arrives quits the
        // forward DFA, and the search reruns without it.
        Prog words = compileRustProgram("\\b\\w+\\b", RustRegexp.Options.defaults(), false);
        Prog reverseWords = compileRustProgram("\\b\\w+\\b", RustRegexp.Options.defaults(), true);
        assertDfaSpans("\\b\\w+\\b", words, reverseWords, Slices.wrappedBuffer((byte) 0xFF, (byte) 'a', (byte) 0xFF), List.of("1-2"));
        assertDfaSpans("\\b\\w+\\b", words, reverseWords, Slices.wrappedBuffer((byte) 0xB6, (byte) 'a'), List.of("1-2"));
        assertDfaSpans("\\b\\w+\\b", words, reverseWords, Slices.wrappedBuffer((byte) 0xD0, (byte) 0xB6, (byte) 0xB6), List.of("0-2"));
        assertDfaSpans("\\b\\w+\\b", words, reverseWords, utf8Slice("\uFFFDa"), List.of("3-4"));
        assertDfaSpans("\\B", compileRustProgram("\\B", RustRegexp.Options.defaults(), false), compileRustProgram("\\B", RustRegexp.Options.defaults(), true), Slices.wrappedBuffer((byte) 0xFF, (byte) 0xFF), List.of());
        Slice truncated = truncatedLeads("#a");
        assertThat(Dfa.search(words, truncated, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(Dfa.SEARCH_FAILED);
        assertThat(Dfa.countMatches(words, truncated, Prog.MatchKind.FIRST_MATCH)).isEqualTo(Dfa.COUNT_UNSUPPORTED);
        assertThat(spans(RustRegexp.compile(utf8Slice("\\b")), truncated)).containsExactly("1-1", "2-2");
        for (RustRegexp.Options options : List.of(RustRegexp.Options.defaults(), RustRegexp.Options.bytes())) {
            assertThat(RustRegexp.compile(utf8Slice("\\bfoo\\b"), options).count(truncatedLeads("# foo"))).isEqualTo(1);
            // A count over ASCII text long enough to probe the self-loop's exit bytes keeps the DFA.
            Prog boundariesProgram = compileRustProgram("\\bfoo\\b", options, false);
            assertThat(Dfa.countMatches(boundariesProgram, utf8Slice("foo ".repeat(100)), Prog.MatchKind.FIRST_MATCH)).isEqualTo(100);
        }

        // A quit is not cache exhaustion, so the count does not bail, and later searches keep the
        // DFA. Byte 0x7F is ASCII, so \B next to it does not quit.
        assertThat(Dfa.countMatches(words, utf8Slice("foo жж"), Prog.MatchKind.FIRST_MATCH)).isEqualTo(2);
        assertThat(Dfa.search(compileRustProgram("\\B", RustRegexp.Options.defaults(), false), Slices.wrappedBuffer((byte) 0x7F), false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(0);

        // At the least budget at which the counting DFA starts, a long Cyrillic text finds every word.
        Slice cyrillic = utf8Slice("жж ".repeat(1000));
        RustRegexp tight = compileAtSmallestBudget(
                maxMemory -> RustRegexp.compile(utf8Slice("\\b\\w+\\b"), RustRegexp.Options.defaults().setMaxMemory(maxMemory)),
                pattern -> pattern.count(utf8Slice("x ".repeat(100))) == 100 && pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH) != null);
        assertThat(tight.count(cyrillic)).isEqualTo(1000);
        assertThat(spans(tight, cyrillic)).isEqualTo(spans(RustRegexp.compile(utf8Slice("\\b\\w+\\b")), cyrillic));
    }

    @Test
    public void testDfaQuitStreakSkipsDfaForRestOfInput()
    {
        // Each search over the words after a Cyrillic lead with no character quits the forward
        // DFA at its start. After four quits, the matcher's later searches of the input skip the
        // DFA, including those over the ASCII words that follow, and still find every match.
        RustRegexp words = RustRegexp.compile(utf8Slice("\\b\\w+\\b"));
        Slice text = truncatedLeads("#a ".repeat(8) + "foo bar");
        RegexpMatcher matcher = words.matcher(text, 0);
        List<String> spans = new ArrayList<>();
        for (int quit = 0; quit < 4; quit++) {
            assertThat(matcher.find()).isTrue();
            spans.add(matcher.start() + "-" + matcher.end());
            assertThat(matcher.dfaQuitStreak()).isEqualTo(quit + 1);
        }
        Dfa.DfaInstance forward = words.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(forward).isNotNull();
        int stateCount = forward.cacheSnapshot().stateCount();
        while (matcher.find()) {
            spans.add(matcher.start() + "-" + matcher.end());
        }

        List<String> expected = new ArrayList<>();
        for (int word = 0; word < 8; word++) {
            expected.add((3 * word + 1) + "-" + (3 * word + 2));
        }
        expected.addAll(List.of("24-27", "28-31"));
        assertThat(spans).isEqualTo(expected);
        assertThat(matcher.dfaQuitStreak()).isEqualTo(4);
        assertThat(forward.cacheSnapshot().stateCount()).isEqualTo(stateCount);

        // A reset ends the streak, so the DFA runs over the ASCII words again.
        matcher.reset(text, 24, text.length());
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(24);
        assertThat(matcher.end()).isEqualTo(27);
        assertThat(forward.cacheSnapshot().stateCount()).isGreaterThan(stateCount);
    }

    @Test
    public void testLiteralCandidateCursorFollowsDfaQuitStreak()
    {
        // Each search over a word that ends at a Cyrillic lead with no character quits the
        // literal cursor's DFA, which counts once toward the streak. At the limit, later searches
        // skip the cursor until a reset. A matcher settles a candidate whose check quits only
        // where matches have a longest length, so each pattern here contains an unbounded repeat.
        RustRegexp truncatedWords = RustRegexp.compile(utf8Slice("\\bab\\w*\\b"));
        Slice text = truncatedLeads("ab# ".repeat(2 * DFA_QUIT_STREAK_LIMIT) + "x".repeat(200));
        RegexpMatcher matcher = truncatedWords.matcher(text, 0);
        assertThat(matcher.candidateStartCursorSearchesLiteralForDiagnostics()).isTrue();
        for (int word = 0; word < 2 * DFA_QUIT_STREAK_LIMIT; word++) {
            assertThat(matcher.find()).isTrue();
            assertThat(matcher.start()).isEqualTo(4 * word);
            assertThat(matcher.end()).isEqualTo(4 * word + 2);
            assertThat(matcher.dfaQuitStreak()).isEqualTo(Math.min(word + 1, DFA_QUIT_STREAK_LIMIT));
            assertThat(matcher.candidateStartFallbackCountForDiagnostics()).isEqualTo(Math.min(word + 1, DFA_QUIT_STREAK_LIMIT));
        }
        assertThat(matcher.find()).isFalse();
        assertThat(matcher.candidateStartFallbackCountForDiagnostics()).isEqualTo(DFA_QUIT_STREAK_LIMIT);
        matcher.reset(text);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.dfaQuitStreak()).isEqualTo(1);
        assertThat(matcher.candidateStartFallbackCountForDiagnostics()).isEqualTo(DFA_QUIT_STREAK_LIMIT + 1);

        // A failed find that quits retries from the same start, so repeated quits also reach the
        // limit. The input is longer than the pattern's minimum length, so the DFA runs.
        RustRegexp retryingPattern = RustRegexp.compile(utf8Slice("\\bfoo\\w*\\b.{100}.*"));
        RegexpMatcher retrying = retryingPattern.matcher(truncatedLeads("foo# " + "x".repeat(110)), 0);
        assertThat(retrying.candidateStartCursorSearchesLiteralForDiagnostics()).isTrue();
        for (int quit = 0; quit < 6; quit++) {
            assertThat(retrying.find()).isFalse();
            assertThat(retrying.dfaQuitStreak()).isEqualTo(Math.min(quit + 1, DFA_QUIT_STREAK_LIMIT));
            assertThat(retrying.candidateStartFallbackCountForDiagnostics()).isEqualTo(Math.min(quit + 1, DFA_QUIT_STREAK_LIMIT));
        }
        // A shorter input fails on length before the DFA can quit.
        RegexpMatcher tooShort = retryingPattern.matcher(truncatedLeads("foo# " + "x".repeat(70)), 0);
        assertThat(tooShort.find()).isFalse();
        assertThat(tooShort.dfaQuitStreak()).isZero();
        assertThat(tooShort.candidateStartFallbackCountForDiagnostics()).isZero();

        // A match the cursor verifies ends the streak, so quits that alternate with matches never
        // reach the limit.
        RegexpMatcher alternating = RustRegexp.compile(utf8Slice("\\bfoo\\w*\\b")).matcher(truncatedLeads("foo# foo ".repeat(5) + "x".repeat(200)), 0);
        assertThat(alternating.candidateStartCursorSearchesLiteralForDiagnostics()).isTrue();
        for (int search = 0; search < 10; search++) {
            assertThat(alternating.find()).isTrue();
            assertThat(alternating.group().toStringUtf8()).isEqualTo("foo");
            assertThat(alternating.dfaQuitStreak()).isEqualTo(search % 2 == 0 ? 1 : 0);
        }
        assertThat(alternating.candidateStartRouteCountForDiagnostics()).isEqualTo(5);

        // So does a no-match the cursor proves.
        RegexpMatcher exhausted = truncatedWords.matcher(truncatedLeads("ab# xab" + "x".repeat(200)), 0);
        assertThat(exhausted.find()).isTrue();
        assertThat(exhausted.dfaQuitStreak()).isEqualTo(1);
        assertThat(exhausted.find()).isFalse();
        assertThat(exhausted.dfaQuitStreak()).isZero();
    }

    @Test
    public void testRustAssertionsRunOnOnePass()
    {
        // Anchored capture patterns with CRLF anchors, Unicode and ASCII word boundaries, and
        // start, end, and end-half boundaries are one-pass. Over every short text, as a view
        // between bytes outside ASCII, and every range, OnePass reports what BitState reports, for
        // a boolean search, group zero, every group, and, in the last pattern, more groups than a
        // condition's capture bits hold.
        List<String> patterns = List.of(
                "(a+)\\b( *)",
                "\\B(.)",
                "(?-u:\\b{start})(a)",
                "\\b{start}(a+)\\b{end-half}",
                "(a)\\b{end}( )?",
                "\\<(a)( ?)\\>",
                "(?mR)^(a*)$(\\r\\n|\\n)?",
                "(\\b)(a)(\\B)(a?)(\\b{end-half})( ?)");

        for (boolean bytes : new boolean[] {false, true}) {
            RustRegexp.Options options = RustRegexp.Options.defaults().setBytes(bytes);
            for (String pattern : patterns) {
                Prog program = compileRustProgram(pattern, options, false);
                assertThat(program.hasTextDependentAssertions()).as(pattern).isTrue();
                assertThat(program.isOnePass()).as(pattern).isTrue();
                int captureSlotCount = 2 * (RustRegexp.parse(utf8Slice(pattern), options).capturingGroupCount() + 1);
                TestingEngineAgreement.assertOnePassAgreesWithBitState(pattern, program, captureSlotCount, SHORT_TEXTS, "bytes " + bytes);
            }

            // Alternatives that read the same byte under different word assertions are not
            // one-pass, including when one alternative folds case.
            for (Map.Entry<String, String> entry : Map.of("(\\ba|\\B[a-z])", "aa", "(\\bA|\\B(?i:a))", "aA").entrySet()) {
                String pattern = entry.getKey();
                assertThat(compileRustProgram(pattern, options, false).isOnePass()).as(pattern).isFalse();
                int[] groups = new int[4];
                assertThat(RustRegexp.compile(utf8Slice(pattern), options).lookingAtInto(utf8Slice(entry.getValue()), 1, 2, groups)).as(pattern).isTrue();
                assertThat(groups).as(pattern).containsExactly(1, 2, 1, 2);
            }
        }
    }

    // Finds every match of `pattern` in `text`, and checks the forward DFA's end of the first and
    // the reverse DFA's start of it, either of which may quit only on malformed text.
    private static void assertDfaSpans(String pattern, Prog forward, Prog reverse, Slice text, List<String> expected)
    {
        String description = "%s on %s".formatted(pattern, text.toStringUtf8());
        assertThat(spans(RustRegexp.compile(utf8Slice(pattern)), text)).as(description).isEqualTo(expected);
        boolean mayQuit = !TestingEngineAgreement.isUtf8(text.getBytes());
        long end = Dfa.search(forward, text, false, Prog.MatchKind.FIRST_MATCH, true);
        if (end != Dfa.SEARCH_FAILED || !mayQuit) {
            assertThat(end).as(description).isEqualTo(expected.isEmpty() ? Dfa.SEARCH_NO_MATCH : Long.parseLong(expected.get(0).split("-")[1]));
        }
        if (!expected.isEmpty()) {
            int matchEnd = Integer.parseInt(expected.get(0).split("-")[1]);
            long start = Dfa.search(reverse, text, 0, matchEnd, true, Prog.MatchKind.LONGEST_MATCH, true);
            assertThat(start).as(description).isEqualTo(Long.parseLong(expected.get(0).split("-")[0]));
        }
    }

    private static List<String> spans(CompiledPattern pattern, Slice text)
    {
        RegexpMatcher matcher = pattern.matcher(text, 0);
        List<String> spans = new ArrayList<>();
        while (matcher.find()) {
            spans.add(matcher.start() + "-" + matcher.end());
        }
        return spans;
    }

    // The UTF-8 bytes of `text`, with each # replaced by 0xD0, the lead of a Cyrillic letter, with
    // no continuation byte after it.
    private static Slice truncatedLeads(String text)
    {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '#') {
                bytes[index] = (byte) 0xD0;
            }
        }
        return Slices.wrappedBuffer(bytes);
    }

    private static Prog compileRustProgram(String pattern, RustRegexp.Options options, boolean reversed)
    {
        ParseResult parsed = RustRegexpParser.parse(utf8Slice(pattern), options.syntaxFlags(), options.nestLimit());
        Regexp regexp = Simplifier.simplify(parsed.regexp(), parsed.caseFolding());
        return Compiler.compileNormalized(regexp, reversed, 1 << 20, Compiler.Dialect.RE2, parsed.caseFolding());
    }

    private static void assertBoundedEngines(String pattern, String text, int start, int end)
    {
        RustRegexp.Options options = RustRegexp.Options.defaults();
        Prog program = Compiler.compile(RustRegexpParser.parse(utf8Slice(pattern), options.syntaxFlags(), options.nestLimit()).regexp());
        assertThat(program.hasTextDependentAssertions()).isTrue();
        int[] nfa = new int[2];
        int[] bitState = new int[2];
        assertThat(Nfa.search(program, utf8Slice(text), false, Prog.MatchKind.FIRST_MATCH, nfa)).isEqualTo(start >= 0);
        assertThat(BitState.search(program, utf8Slice(text), false, Prog.MatchKind.FIRST_MATCH, bitState)).isEqualTo(start >= 0);
        if (start >= 0) {
            assertThat(nfa).containsExactly(start, end);
            assertThat(bitState).containsExactly(start, end);
        }
    }

    private static List<String> instanceMethods(Class<?> type)
    {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers()))
                .map(TestRustRegexp::signature)
                .sorted()
                .toList();
    }

    private static String signature(Method method)
    {
        return method.getReturnType().getSimpleName() + " " + method.getName() + Arrays.toString(method.getParameterTypes());
    }

    @Test
    public void testDenseFindsSwitchToMatchStartSet()
    {
        // An empty match that ends where the last match ended is skipped, after the switch as
        // before it: here the word boundary at the end of each word.
        Slice words = utf8Slice("ab cd ".repeat(16 * 1024 / 6).strip());
        TestRegexpMatcher.assertMatchStartSet(RustRegexp.compile(utf8Slice("(?-u:\\b)[a-z]*")), words, 0, words.length(), 1);

        // A Unicode \\b beside text outside ASCII is decided from the text, so Cyrillic words switch
        // as ASCII words do.
        Slice cyrillic = utf8Slice("аб cd вг, ".repeat(16 * 1024 / 14));
        TestRegexpMatcher.assertMatchStartSet(RustRegexp.compile(utf8Slice("\\b\\w+\\b")), cyrillic, 0, cyrillic.length(), 1);

        // A Unicode \\b quits on malformed text, so a malformed byte near the end stops the reverse
        // pass, and every find stays on the find-by-find search.
        byte[] malformed = Arrays.copyOf(words.getBytes(), words.length());
        malformed[malformed.length - 10] = (byte) 0xFF;
        Slice input = Slices.wrappedBuffer(malformed);
        TestRegexpMatcher.assertMatchStartSet(RustRegexp.compile(utf8Slice("\\b[a-z]+\\b")), input, 0, input.length(), 0);
    }

    @Test
    public void testNestedDenseOperationsLeaveUnicodeWordSearchesTheLoan()
    {
        // Dense operations running inside one another each hold a match start set charged to the
        // reverse DFA that lent the start-set DFA its memory, so the Unicode \b searches made
        // inside them can use up what is left. Each \b beside Cyrillic text is decided from the
        // decoded characters, so the searches ask for new states and for decoded word cache
        // entries, and which request first finds no room depends on the free memory they start
        // with. The searches start with each free memory from twice the least set an operation
        // holds until they no longer use it up, in steps under the 192 bytes the decoded word
        // caches first grow by, so each growth of those caches is at some step the first request
        // that does not fit. Whichever request it is, the reverse DFA takes the loan back rather
        // than resetting.
        List<Slice> searches = unicodeWordSearches();
        long leastFree = 2 * Dfa.matchStartSetBytes(RegexpMatcher.MATCH_START_SET_MINIMUM_REST_BYTES);
        long step = 128;
        long free = leastFree;
        while (searchesTakeLoanBack(free, searches)) {
            free += step;
        }
        assertThat(free).as("the searches never used up the free memory").isGreaterThan(leastFree);
    }

    // The searches of testNestedDenseOperationsLeaveUnicodeWordSearchesTheLoan: Cyrillic words of
    // 14 to 23 letters а and б, each between two copies of a context: characters outside ASCII
    // of every UTF-8 length, word characters among them, or a line feed.
    private static List<Slice> unicodeWordSearches()
    {
        Random random = new Random(7);
        List<Slice> searches = new ArrayList<>();
        for (int count = 0; count < 6; count++) {
            StringBuilder word = new StringBuilder();
            int length = 14 + random.nextInt(10);
            for (int i = 0; i < length; i++) {
                word.append(random.nextBoolean() ? 'а' : 'б');
            }
            for (String context : List.of("！", "💰", "。", " é", " 中", "\n")) {
                searches.add(utf8Slice(context + word + context));
            }
        }
        return searches;
    }

    // Runs `searches` in order inside dense operations that leave the reverse DFA `free` bytes of
    // free state memory, until one takes the start-set DFA's loan back, which unpublishes that
    // DFA, and returns whether one did. Checks that the reverse DFA did not reset and that the
    // operations returned their memory.
    private static boolean searchesTakeLoanBack(long free, List<Slice> searches)
    {
        // The shape of TestRegexpMatcher.testDenseOperationLeavesLaterSearchesTheirMemory's
        // pattern, in Cyrillic, with a small budget to keep the operations' inputs short.
        RustRegexp pattern = RustRegexp.compile(utf8Slice("\\b(?:[аб]{4}а[аб]*|c+|d+|e+|f+)\\b"), RustRegexp.Options.defaults().setMaxMemory(1 << 20));
        // The first dense operation builds the start-set DFA on its loan.
        assertThat(TestRegexpMatcher.operationSpans(pattern.matcher(utf8Slice(DENSE_CYRILLIC_WORD.repeat(500))))).hasSize(500);
        Prog reverse = pattern.reverseProgramIfComputedForDiagnostics();
        assertThat(reverse.cachedDfaIfPresent(Dfa.DfaInstance.Kind.MANY_MATCH)).isNotNull();
        Dfa.DfaInstance lender = reverse.cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH);
        int resets = lender.resetCount();
        List<RegexpMatcher> operations = new ArrayList<>();
        boolean tookLoanBack = false;
        try {
            holdStartSetsUntilFree(pattern, lender, free, operations);
            for (Slice search : searches) {
                pattern.matcher(search).find();
                if (reverse.cachedDfaIfPresent(Dfa.DfaInstance.Kind.MANY_MATCH) == null) {
                    tookLoanBack = true;
                    break;
                }
            }
        }
        finally {
            operations.reversed().forEach(RegexpMatcher::endAllMatchOperation);
        }
        assertThat(lender.resetCount()).as("resets with %s bytes free", free).isEqualTo(resets);
        assertThat(lender.matchStartSetReservationForDiagnostics()).isZero();
        return tookLoanBack;
    }

    // Starts dense operations of `pattern`, adding each to `operations` for the caller to end,
    // until the start sets they hold leave `lender` `free` bytes of free state memory, or up to 7
    // more, as sets grow in steps of 8 bytes. A set takes at least the least set, the one for the
    // shortest rest that switches, and at most half the free memory. So while more than twice
    // `free` is free, each operation leaves at least `free` and one least set, for the last
    // operation to take the difference.
    private static void holdStartSetsUntilFree(RustRegexp pattern, Dfa.DfaInstance lender, long free, List<RegexpMatcher> operations)
    {
        long leastSet = Dfa.matchStartSetBytes(RegexpMatcher.MATCH_START_SET_MINIMUM_REST_BYTES);
        long available = lender.cacheSnapshot().availableStateMemory();
        while (available - free >= Long.BYTES) {
            long set = available <= 2 * free ? available - free : Math.min(available / 2, available - free - leastSet);
            // The switch at the last of the first finds covers the rest from that find's end: the
            // space after its word and the spaces added, the longest rest whose set fits.
            int rest = RegexpMatcher.MATCH_START_SET_MINIMUM_REST_BYTES;
            while (Dfa.matchStartSetBytes(rest + Long.SIZE) <= set) {
                rest += Long.SIZE;
            }
            String words = DENSE_CYRILLIC_WORD.repeat(RegexpMatcher.MATCH_START_SET_SWITCH_FINDS);
            RegexpMatcher operation = pattern.matcher(utf8Slice(words + " ".repeat(rest - 1)));
            operation.beginAllMatchOperation();
            operations.add(operation);
            long reserved = lender.matchStartSetReservationForDiagnostics();
            for (int find = 0; find < RegexpMatcher.MATCH_START_SET_SWITCH_FINDS; find++) {
                assertThat(operation.find()).isTrue();
            }
            assertThat(lender.matchStartSetReservationForDiagnostics()).isEqualTo(reserved + Dfa.matchStartSetBytes(rest));
            available = lender.cacheSnapshot().availableStateMemory();
        }
        assertThat(available).isGreaterThanOrEqualTo(free);
    }
}
