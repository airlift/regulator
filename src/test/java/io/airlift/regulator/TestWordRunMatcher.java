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
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.airlift.regulator.TestScanPlan.disableOrdinaryEngine;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static org.assertj.core.api.Assertions.assertThat;

public class TestWordRunMatcher
{
    @Test
    public void testEligibility()
    {
        for (String pattern : List.of("\\b\\w+\\b", "\\b[a-z]+\\b", "(?U)\\b[a-z]+\\b", "(?i)\\b[a-z]+\\b", "\\b\\w{12,}\\b", "\\b[a-z]{2,4}\\b")) {
            assertThat(analyze(pattern)).as(pattern).isNotNull();
        }
        for (String pattern : List.of("(?U)\\b\\w+\\b", "\\b\\w+?\\b", "\\B\\w+\\b", "\\b(\\w+)\\b", "\\b[a-z!]+\\b", "\\b\\w*\\b", "\\b\\w+", "\\b\\w{0,3}\\b", "\\b\\w{2,}?\\b")) {
            assertThat(analyze(pattern)).as(pattern).isNull();
        }
    }

    @Test
    public void testAsciiBoundaryEligibility()
    {
        for (String pattern : List.of("\\b\\w+\\b", "\\b[a-z]+\\b", "\\b\\w{25,}\\b", "\\b[0-9A-Za-z_]{12,}\\b")) {
            assertThat(analyze(RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL))).as("re2 %s", pattern).isNotNull();
            for (boolean bytes : new boolean[] {false, true}) {
                RustRegexp.Options options = RustRegexp.Options.defaults().setBytes(bytes).setUnicode(false);
                assertThat(analyze(RustRegexpParser.parse(utf8Slice(pattern), options.syntaxFlags(), options.nestLimit()))).as("rust bytes %s %s", bytes, pattern).isNotNull();
            }
        }
        Slice caseInsensitive = utf8Slice("(?i)\\b[a-z]+\\b");
        // In UTF-8, RE2's (?i)[a-z] folds to the Kelvin sign and the long s, so only Rust's ASCII fold is a word run.
        assertThat(analyze(RegexpParser.parse(caseInsensitive, Regexp.LIKE_PERL))).as("re2").isNull();
        assertThat(analyze(RegexpParser.parse(caseInsensitive, Regexp.LIKE_PERL | Regexp.LATIN1))).as("re2 latin1").isNotNull();
        for (boolean bytes : new boolean[] {false, true}) {
            RustRegexp.Options options = RustRegexp.Options.defaults().setBytes(bytes).setUnicode(false);
            assertThat(analyze(RustRegexpParser.parse(caseInsensitive, options.syntaxFlags(), options.nestLimit()))).as("rust bytes %s", bytes).isNotNull();
        }
        for (String pattern : List.of("\\B\\w+\\b", "\\b\\w+?\\b", "\\b(\\w+)\\b", "\\b[a-z!]+\\b")) {
            assertThat(analyze(RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL))).as("re2 %s", pattern).isNull();
        }
        RustRegexp.Options options = RustRegexp.Options.defaults().setBytes(true).setUnicode(false);
        for (String pattern : List.of("\\b{start}\\w+\\b{end}", "\\b{start-half}\\w+\\b{end-half}")) {
            assertThat(analyze(RustRegexpParser.parse(utf8Slice(pattern), options.syntaxFlags(), options.nestLimit()))).as("rust %s", pattern).isNull();
        }
    }

    @Test
    public void testRustUnicodeBoundaryEligibility()
    {
        for (boolean bytes : new boolean[] {false, true}) {
            RustRegexp.Options options = RustRegexp.Options.defaults().setBytes(bytes);
            ParseResult parsed = RustRegexpParser.parse(utf8Slice("\\b[a-z]+\\b"), options.syntaxFlags(), options.nestLimit());
            Prog program = Compiler.compile(parsed.regexp(), false, Re2.Options.DEFAULT_MAX_MEMORY);
            assertThat(WordRunMatcher.analyze(parsed.regexp(), program)).as("bytes %s", bytes).isNotNull();
        }
    }

    @Test
    public void testRetainedPlanUsesForwardBudget()
    {
        Slice pattern = utf8Slice("\\b\\w+\\b");
        int flags = JavaRegexp.Options.defaults().parseFlags();
        ParseResult parsed = JavaRegexpParser.parse(pattern, flags);
        long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
        Prog control = Compiler.compile(parsed.regexp(), false, maxMemory - maxMemory / 3);
        WordRunMatcher plan = WordRunMatcher.analyze(parsed.regexp(), control);
        Re2 compiled = Re2.compileParsed(pattern, parsed, flags, maxMemory);
        assertThat(compiled.forwardProgramForDiagnostics().dfaMemory())
                .isEqualTo(control.dfaMemory() - plan.estimatedRetainedSize());
    }

    @Test
    public void testWindowsAgainstSemanticNfa()
    {
        List<Slice> values = List.of(utf8Slice("éabc def💰ghi"), utf8Slice("foo\u0301 bar"), utf8Slice("ab123cd"), utf8Slice("\u0301abc"), utf8Slice("a bc def ghij"), utf8Slice("abcdefgh_12 xyzw é1234567"), utf8Slice(""));
        for (String pattern : List.of("\\b\\w+\\b", "\\b[a-z]+\\b", "(?U)\\b[a-z]+\\b", "\\b\\w{3,}\\b", "\\b[a-z]{2,3}\\b", "\\b\\w{5,7}\\b")) {
            assertWindowsMatchNfa(pattern, JavaRegexpParser.parse(utf8Slice(pattern), JavaRegexp.Options.defaults().parseFlags()), values);
        }
    }

    @Test
    public void testAsciiBoundaryMatcherIterationTakesPlan()
    {
        // Matcher iteration skips the DFA group-zero cursor, and with the ordinary engine
        // disabled it finds these only through the word-run plan.
        for (String expression : List.of("\\b\\w+\\b", "\\b[a-z]{2,4}\\b")) {
            for (CompiledPattern pattern : List.of(Re2.compile(utf8Slice(expression)), RustRegexp.compile(utf8Slice(expression), RustRegexp.Options.defaults().setUnicode(false)))) {
                String description = pattern.getClass().getSimpleName() + " " + expression;
                assertThat(pattern.createGroupZeroForwardCursor()).as(description).isNull();
                disableOrdinaryEngine(pattern);
                RegexpMatcher matcher = pattern.matcher(utf8Slice("!ab cdef?"));
                assertThat(matcher.find()).as(description).isTrue();
                assertThat(matcher.start()).as(description).isEqualTo(1);
                assertThat(matcher.end()).as(description).isEqualTo(3);
                assertThat(matcher.find()).as(description).isTrue();
                assertThat(matcher.start()).as(description).isEqualTo(4);
                assertThat(matcher.end()).as(description).isEqualTo(8);
                assertThat(matcher.find()).as(description).isFalse();
            }
        }
    }

    @Test
    public void testAsciiBoundaryWindowsAgainstSemanticNfa()
    {
        List<Slice> values = List.of(
                utf8Slice("éabc def💰ghi"),
                utf8Slice("ab_9 Cd\nxy"),
                utf8Slice("a bc def ghij"),
                utf8Slice("abcdefgh_12 xyzw é1234567"),
                wrappedBuffer(new byte[] {'a', (byte) 0xE9, 'b', ' ', (byte) 0xFF, 'c', 'd', (byte) 0x80}),
                utf8Slice(""));
        RustRegexp.Options options = RustRegexp.Options.defaults().setBytes(true).setUnicode(false);
        for (String pattern : List.of("\\b\\w+\\b", "\\b[a-z]+\\b", "\\b\\w{3,}\\b", "\\b[a-z]{2,3}\\b", "\\b\\w{5,7}\\b")) {
            assertWindowsMatchNfa(pattern, RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL), values);
            assertWindowsMatchNfa(pattern, RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL | Regexp.LATIN1), values);
            assertWindowsMatchNfa(pattern, RustRegexpParser.parse(utf8Slice(pattern), options.syntaxFlags(), options.nestLimit()), values);
        }
        // In UTF-8, RE2's (?i)[a-z] is not a word run.
        String caseInsensitive = "(?i)\\b[a-z]+\\b";
        assertWindowsMatchNfa(caseInsensitive, RegexpParser.parse(utf8Slice(caseInsensitive), Regexp.LIKE_PERL | Regexp.LATIN1), values);
        assertWindowsMatchNfa(caseInsensitive, RustRegexpParser.parse(utf8Slice(caseInsensitive), options.syntaxFlags(), options.nestLimit()), values);
    }

    @Test
    public void testRotatingUnicodeInputsAgainstJdk()
    {
        Random random = new Random(39217);
        String[] alphabet = {"a", "z", "A", "9", "_", "é", "δ", "💰", "\u0301", " ", "\n", "!"};
        for (String expression : List.of("\\b\\w+\\b", "\\b[a-z]+\\b", "(?U)\\b[a-z]+\\b", "(?i)\\b[a-z]+\\b", "\\b\\w{2,}\\b", "\\b[a-z]{2,3}\\b", "\\b\\w{4,}\\b", "\\b[a-z9]{3,5}\\b")) {
            Pattern comparator = Pattern.compile(expression);
            JavaRegexp pattern = JavaRegexp.compile(utf8Slice(expression));
            for (int trial = 0; trial < 300; trial++) {
                StringBuilder value = new StringBuilder();
                for (int index = 0; index < 20; index++) {
                    value.append(alphabet[random.nextInt(alphabet.length)]);
                }
                String text = value.toString();
                Slice padded = utf8Slice("xx" + text + "xx");
                RegexpMatcher actual = pattern.matcher(padded).reset(padded, 2, padded.length() - 2);
                Matcher expected = comparator.matcher(text);
                while (expected.find()) {
                    assertThat(actual.find()).isTrue();
                    assertThat(actual.start()).isEqualTo(2 + utf8Slice(text.substring(0, expected.start())).length());
                    assertThat(actual.end()).isEqualTo(2 + utf8Slice(text.substring(0, expected.end())).length());
                }
                assertThat(actual.find()).isFalse();
                assertThat(pattern.matches(utf8Slice(text))).isEqualTo(expected.reset().matches());
                assertThat(pattern.lookingAt(utf8Slice(text))).isEqualTo(expected.reset().lookingAt());
            }
        }
    }

    private static void assertWindowsMatchNfa(String pattern, ParseResult parsed, List<Slice> values)
    {
        Prog program = Compiler.compile(parsed.regexp(), false, Re2.Options.DEFAULT_MAX_MEMORY);
        WordRunMatcher scanner = WordRunMatcher.analyze(parsed.regexp(), program);
        assertThat(scanner).as(pattern).isNotNull();
        for (Slice value : values) {
            String input = HexFormat.of().formatHex(value.getBytes());
            Slice backing = padded(value);
            Slice text = backing.slice(1, backing.length() - 2);
            for (int start = 0; start <= text.length(); start++) {
                for (int end = start; end <= text.length(); end++) {
                    for (CompiledPattern.Anchor anchor : CompiledPattern.Anchor.values()) {
                        int[] offsets = new int[2];
                        boolean matched = Nfa.search(
                                program,
                                text,
                                start,
                                end,
                                anchor != CompiledPattern.Anchor.UNANCHORED,
                                anchor == CompiledPattern.Anchor.ANCHOR_BOTH ? Prog.MatchKind.FULL_MATCH : Prog.MatchKind.FIRST_MATCH,
                                offsets);
                        long span = scanner.search(text, 0, text.length(), start, end, anchor);
                        assertThat(span != Dfa.SEARCH_NO_MATCH).as("%s %s %s:%s %s", pattern, input, start, end, anchor).isEqualTo(matched);
                        if (matched) {
                            // The direct NFA returns offsets relative to its search start.
                            assertThat((int) (span >>> 32)).as("%s %s %s:%s %s", pattern, input, start, end, anchor).isEqualTo(start + offsets[0]);
                            assertThat((int) span).as("%s %s %s:%s %s", pattern, input, start, end, anchor).isEqualTo(start + offsets[1]);
                        }
                    }
                }
            }
        }
    }

    private static Slice padded(Slice value)
    {
        byte[] bytes = new byte[value.length() + 2];
        bytes[0] = 'x';
        value.getBytes(0, bytes, 1, value.length());
        bytes[bytes.length - 1] = 'x';
        return wrappedBuffer(bytes);
    }

    private static WordRunMatcher analyze(String pattern)
    {
        return analyze(JavaRegexpParser.parse(utf8Slice(pattern), JavaRegexp.Options.defaults().parseFlags()));
    }

    private static WordRunMatcher analyze(ParseResult parsed)
    {
        Prog program = Compiler.compile(parsed.regexp(), false, Re2.Options.DEFAULT_MAX_MEMORY);
        return WordRunMatcher.analyze(parsed.regexp(), program);
    }
}
