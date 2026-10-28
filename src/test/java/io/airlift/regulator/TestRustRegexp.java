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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

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
    private static final List<byte[]> SHORT_TEXTS = shortTexts();

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

        assertThatThrownBy(() -> RustRegexp.parse(utf8Slice("a{1001}")))
                .isInstanceOf(RegexpParseException.class)
                .hasMessage("repetition size exceeds the supported limit: repetition count above 1000 at byte offset 0");

        assertThatThrownBy(() -> RustRegexp.parse(utf8Slice("(a)"), RustRegexp.Options.defaults().setNestLimit(0)))
                .isInstanceOf(RegexpParseException.class)
                .hasMessage("pattern exceeds the supported parser limit: NestLimitExceeded at byte offset 0");
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
        RustRegexp regexp = RustRegexp.compile(utf8Slice("a*"));
        assertThatThrownBy(() -> regexp.find(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("input is null");
        assertThatThrownBy(() -> regexp.findResult(null, 0, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("input is null");
        assertThatThrownBy(() -> regexp.matchesInto(null, new int[2]))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("input is null");
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
                Re2 compiled = RustRegexp.compile(utf8Slice(pattern), options).re2();
                Prog forward = compiled.forwardProgramForDiagnostics();
                assertThat(forward.hasDfaUnsupportedAssertions()).as(pattern).isFalse();
                assertThat(compiled.findInto(text, new int[2])).as(pattern).isTrue();
                assertThat(forward.cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).as(pattern).isNotNull();
            }

            // A match of variable length takes its start from the reverse program.
            Re2 words = RustRegexp.compile(utf8Slice("\\b{start}\\w+\\b{end}"), options).re2();
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
        // text outside ASCII in a program with a Unicode word assertion.
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
                boolean unicodeWordAssertions = (forward.textDependentAssertions() & EmptyOp.RUST_UNICODE_WORD_ASSERTIONS) != 0;
                for (byte[] text : SHORT_TEXTS) {
                    Slice context = paddedView(text);
                    String description = "%s on %s, bytes %s".formatted(pattern, HexFormat.of().formatHex(text), bytes);
                    boolean mayQuit = unicodeWordAssertions && !isAscii(text);
                    for (int start = 0; start <= text.length; start++) {
                        for (int end = start; end <= text.length; end++) {
                            assertDfaAgreesWithNfa(description, forward, reverse, context, start, end, mayQuit);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void testUnicodeWordAssertionDfaQuitIsLazyAndNotCached()
    {
        // In a program with a Unicode word assertion, the DFA quits only where a state that needs
        // an assertion meets a non-ASCII byte, so a non-ASCII byte before the match does not stop
        // it.
        Prog endBoundary = compileRustProgram("foo\\b", RustRegexp.Options.defaults(), false);
        assertThat(Dfa.search(endBoundary, utf8Slice("é foo"), false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(6);

        // Byte 0x7F is ASCII, so \B next to it does not quit.
        Prog nonBoundary = compileRustProgram("\\B", RustRegexp.Options.defaults(), false);
        assertThat(Dfa.search(nonBoundary, Slices.wrappedBuffer((byte) 0x7F), false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(0);

        Prog boundaries = compileRustProgram("\\bfoo\\b", RustRegexp.Options.defaults(), false);
        Slice nonAscii = utf8Slice("é foo");
        assertThat(Dfa.search(boundaries, nonAscii, false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(Dfa.SEARCH_FAILED);
        assertThat(Dfa.countMatches(boundaries, nonAscii, Prog.MatchKind.FIRST_MATCH)).isEqualTo(Dfa.COUNT_UNSUPPORTED);

        // A quit is not cache exhaustion, so later ASCII searches keep the DFA.
        assertThat(Dfa.countSearchBailedWhenSlow(boundaries, Prog.MatchKind.FIRST_MATCH)).isFalse();
        assertThat(Dfa.countMatches(boundaries, utf8Slice("foo foo"), Prog.MatchKind.FIRST_MATCH)).isEqualTo(2);
        assertThat(Dfa.search(boundaries, utf8Slice("a foo"), false, Prog.MatchKind.FIRST_MATCH, true)).isEqualTo(5);

        Slice text = utf8Slice("aé");
        for (RustRegexp.Options options : List.of(RustRegexp.Options.defaults(), RustRegexp.Options.bytes())) {
            // A count over ASCII text long enough to probe the self-loop's exit bytes keeps the DFA.
            Prog boundariesProgram = compileRustProgram("\\bfoo\\b", options, false);
            assertThat(Dfa.countMatches(boundariesProgram, utf8Slice("foo ".repeat(100)), Prog.MatchKind.FIRST_MATCH)).isEqualTo(100);

            // When the reverse program quits after the forward program found the end, and when a
            // count quits, the search reruns without the DFA.
            Re2 compiled = RustRegexp.compile(utf8Slice("a|é\\b"), options).re2();
            int[] groups = new int[2];
            assertThat(compiled.findInto(text, groups)).isTrue();
            assertThat(groups).containsExactly(0, 1);
            Prog reverse = compiled.reverseProgramIfComputedForDiagnostics();
            assertThat(reverse).isNotNull();
            assertThat(Dfa.search(reverse, text, 0, 1, true, Prog.MatchKind.LONGEST_MATCH, true)).isEqualTo(Dfa.SEARCH_FAILED);
            assertThat(compiled.count(text)).isEqualTo(2);
        }
    }

    @Test
    public void testDfaQuitStreakSkipsDfaForRestOfInput()
    {
        // Each search over the Cyrillic words quits the forward DFA at its start. After four
        // quits, the matcher's later searches of the input skip the DFA, including those over the
        // ASCII words that follow, and still find every match.
        RustRegexp words = RustRegexp.compile(utf8Slice("\\b\\w+\\b"));
        Slice text = utf8Slice("жж ".repeat(8) + "foo bar");
        RegexpMatcher matcher = words.matcher(text, 0);
        List<String> spans = new ArrayList<>();
        for (int quit = 0; quit < 4; quit++) {
            assertThat(matcher.find()).isTrue();
            spans.add(matcher.start() + "-" + matcher.end());
            assertThat(matcher.dfaQuitStreak()).isEqualTo(quit + 1);
        }
        Dfa.DfaInstance forward = words.re2().forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH);
        assertThat(forward).isNotNull();
        int stateCount = forward.cacheSnapshot().stateCount();
        while (matcher.find()) {
            spans.add(matcher.start() + "-" + matcher.end());
        }

        List<String> expected = new ArrayList<>();
        for (int word = 0; word < 8; word++) {
            expected.add((5 * word) + "-" + (5 * word + 4));
        }
        expected.addAll(List.of("40-43", "44-47"));
        assertThat(spans).isEqualTo(expected);
        assertThat(matcher.dfaQuitStreak()).isEqualTo(4);
        assertThat(forward.cacheSnapshot().stateCount()).isEqualTo(stateCount);

        // A reset ends the streak, so the DFA runs over the ASCII words again.
        matcher.reset(text, 40, text.length());
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(40);
        assertThat(matcher.end()).isEqualTo(43);
        assertThat(forward.cacheSnapshot().stateCount()).isGreaterThan(stateCount);

        // A search whose reverse DFA quits after the forward DFA found the end counts toward the
        // streak too, so searches that alternate reverse and forward quits reach the limit.
        RegexpMatcher alternating = RustRegexp.compile(utf8Slice("a|é\\b")).matcher(utf8Slice("aé ".repeat(4)), 0);
        for (int quit = 0; quit < 4; quit++) {
            assertThat(alternating.find()).isTrue();
            assertThat(alternating.dfaQuitStreak()).isEqualTo(quit + 1);
        }
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
                for (byte[] text : SHORT_TEXTS) {
                    Slice context = paddedView(text);
                    String hex = HexFormat.of().formatHex(text);
                    for (int start = 0; start <= text.length; start++) {
                        for (int end = start; end <= text.length; end++) {
                            for (Prog.MatchKind matchKind : List.of(Prog.MatchKind.FIRST_MATCH, Prog.MatchKind.LONGEST_MATCH, Prog.MatchKind.FULL_MATCH)) {
                                for (int slots : new int[] {0, 2, captureSlotCount}) {
                                    int[] onePass = slots == 0 ? null : new int[slots];
                                    int[] bitState = slots == 0 ? null : new int[slots];
                                    boolean expected = BitState.search(program, context, start, end, true, matchKind, bitState);
                                    String description = "%s on %s [%s, %s) %s, %s slots, bytes %s".formatted(pattern, hex, start, end, matchKind, slots, bytes);
                                    assertThat(OnePass.search(program, context, start, end, true, matchKind, onePass)).as(description).isEqualTo(expected);
                                    if (expected && slots != 0) {
                                        assertThat(onePass).as(description).containsExactly(bitState);
                                    }
                                }
                            }
                        }
                    }
                }
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

    private static void assertDfaAgreesWithNfa(String description, Prog forward, Prog reverse, Slice context, int start, int end, boolean mayQuit)
    {
        int[] groups = new int[2];
        long expectedEnd = Nfa.search(forward, context, start, end, false, Prog.MatchKind.FIRST_MATCH, groups) ? groups[1] : Dfa.SEARCH_NO_MATCH;
        long forwardEnd = Dfa.search(forward, context, start, end, false, Prog.MatchKind.FIRST_MATCH, true);
        if (forwardEnd == Dfa.SEARCH_FAILED) {
            assertThat(mayQuit).as("forward quit: %s [%s, %s)", description, start, end).isTrue();
        }
        else {
            assertThat(forwardEnd).as("forward: %s [%s, %s)", description, start, end).isEqualTo(expectedEnd);
        }

        // The reverse program finds the leftmost start of a match that ends at the range end.
        int matchStart = start;
        while (matchStart <= end && !Nfa.search(forward, context, matchStart, end, true, Prog.MatchKind.FULL_MATCH, null)) {
            matchStart++;
        }
        long expectedStart = matchStart <= end ? matchStart - start : Dfa.SEARCH_NO_MATCH;
        long reverseStart = Dfa.search(reverse, context, start, end, true, Prog.MatchKind.LONGEST_MATCH, true);
        if (reverseStart == Dfa.SEARCH_FAILED) {
            assertThat(mayQuit).as("reverse quit: %s [%s, %s)", description, start, end).isTrue();
        }
        else {
            assertThat(reverseStart).as("reverse: %s [%s, %s)", description, start, end).isEqualTo(expectedStart);
        }
    }

    private static List<byte[]> shortTexts()
    {
        byte[] alphabet = {'a', ' ', '\r', '\n', (byte) 0xC3, (byte) 0xA9, (byte) 0xFF};
        List<byte[]> texts = new ArrayList<>();
        addTexts(texts, alphabet, new byte[0], 4);
        return List.copyOf(texts);
    }

    private static void addTexts(List<byte[]> texts, byte[] alphabet, byte[] prefix, int remaining)
    {
        texts.add(prefix);
        if (remaining == 0) {
            return;
        }
        for (byte value : alphabet) {
            byte[] text = Arrays.copyOf(prefix, prefix.length + 1);
            text[prefix.length] = value;
            addTexts(texts, alphabet, text, remaining - 1);
        }
    }

    // A view of `text` between the two bytes of é, so a search that reads past the view decodes a
    // word character.
    private static Slice paddedView(byte[] text)
    {
        byte[] padded = new byte[text.length + 2];
        padded[0] = (byte) 0xC3;
        System.arraycopy(text, 0, padded, 1, text.length);
        padded[padded.length - 1] = (byte) 0xA9;
        return Slices.wrappedBuffer(padded, 1, text.length);
    }

    private static boolean isAscii(byte[] text)
    {
        for (byte value : text) {
            if (value < 0) {
                return false;
            }
        }
        return true;
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
}
