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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

public class TestNfaNoSubmatchParity
{
    private static final HexFormat HEX_FORMAT = HexFormat.of();
    private static final int FLAGS = Regexp.LIKE_PERL | Regexp.LATIN1;

    @Test
    public void testNoSubmatchParityAcrossRepresentativePatterns()
    {
        String[] patterns = {
                "(fo|foo)",
                "([ab]+)(cd)?e",
                "\\b(foo|bar)\\b",
                "[XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$",
                "^([A-Z]+)$",
        };

        Slice[] texts = {
                latin1(""),
                latin1("foo"),
                latin1("x foobar baz y"),
                randomPrintableText(256, 11),
                randomPrintableText(4096, 12),
        };

        for (String pattern : patterns) {
            Prog prog = compile(pattern);
            for (Slice text : texts) {
                assertParity(prog, text, text, false, false, pattern);
                assertParity(prog, text, text, false, true, pattern);
                assertParity(prog, text, text, true, false, pattern);
                assertParity(prog, text, text, true, true, pattern);
            }
        }
    }

    @Test
    public void testNoSubmatchParityWithAnchorsAndContextSlices()
    {
        Prog beginAndEnd = compile("^a$");

        Slice singleA = latin1("a");
        assertParity(beginAndEnd, singleA, singleA, false, false, "^a$");
        assertParity(beginAndEnd, singleA, singleA, true, false, "^a$");

        Slice context = latin1("xa");
        Slice suffix = Slices.wrappedBuffer(context.byteArray(), context.byteArrayOffset() + 1, 1);
        assertParity(beginAndEnd, suffix, context, false, false, "^a$");
        assertParity(beginAndEnd, suffix, context, true, false, "^a$");

        Prog endOnly = compile("a$");
        Slice ba = latin1("ba");
        Slice ab = latin1("ab");
        assertParity(endOnly, ba, ba, false, false, "a$");
        assertParity(endOnly, ab, ab, false, false, "a$");

        Slice endContext = latin1("baZ");
        Slice endSlice = Slices.wrappedBuffer(endContext.byteArray(), endContext.byteArrayOffset(), 2);
        assertParity(endOnly, endSlice, endContext, false, false, "a$");
    }

    @Test
    public void testNoSubmatchParityOnHardAndParensPatterns()
    {
        Prog hard = compile("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$");
        Prog parens = compile(
                "([ -~])*(A)(B)(C)(D)(E)(F)(G)(H)(I)(J)(K)(L)(M)(N)(O)(P)(Q)(R)(S)(T)(U)(V)(W)(X)(Y)(Z)$");

        Slice text8 = randomPrintableText(8, 1);
        Slice text64 = randomPrintableText(64, 1);
        Slice text256k = randomPrintableText(262_144, 1);

        assertParity(hard, text8, text8, false, false, "hard");
        assertParity(hard, text64, text64, false, false, "hard");
        assertParity(hard, text256k, text256k, false, false, "hard");

        assertParity(parens, text8, text8, false, false, "parens");
        assertParity(parens, text64, text64, false, false, "parens");
        assertParity(parens, text256k, text256k, false, false, "parens");
    }

    @Test
    public void testGroupZeroPrototypePreservesPinnedNativeBoundaries()
    {
        Nfa.Workspace workspace = new Nfa.Workspace();
        Prog workspaceProgram = compile("[a-q][^u-z]{3}x");
        assertGroupZero(workspaceProgram, latin1("--abcdx--"), 0, 9, false, false, 2, 7, workspace);
        assertGroupZero(workspaceProgram, latin1("--abcdx--"), 2, 9, false, false, 0, 5, workspace);
        assertThat(workspace.groupZeroWorkspaceReuseCountForDiagnostics()).isEqualTo(1);

        assertGroupZero("a|aa", latin1("aa"), 0, 2, false, false, 0, 1);
        assertGroupZero("a|aa", latin1("aa"), 0, 2, false, true, 0, 2);
        assertGroupZero(".*x", latin1("x--"), 0, 3, false, false, 0, 1);
        assertGroupZero(".*?", latin1("abc"), 0, 3, false, false, 0, 0);
        assertGroupZero("(ab)(c)", latin1("zabcq"), 1, 4, false, false, 0, 3);

        Prog utf8 = compile("(?:Привет|世界)", Regexp.LIKE_PERL);
        Slice text = Slices.utf8Slice("x世界y");
        int[] groupZero = new int[2];
        assertThat(Nfa.searchGroupZero(
                utf8,
                text,
                0,
                text.length(),
                false,
                Prog.MatchKind.FIRST_MATCH,
                groupZero)).isTrue();
        assertThat(groupZero).containsExactly(1, 7);
    }

    @TestFactory
    public List<DynamicTest> testGroupZeroPrototypeAgainstPinnedNativeCases()
    {
        return GoldenJsonl.readObjects("io/airlift/regulator/upstream_public_match.jsonl").stream()
                .filter(testCase -> Integer.parseInt(testCase.getString("groupCount")) > 0)
                .map(testCase -> DynamicTest.dynamicTest(testCase.getString("id"), () -> assertGroupZeroNativeCase(testCase)))
                .toList();
    }

    private static void assertGroupZeroNativeCase(GoldenJsonl.JsonObject testCase)
    {
        Re2.Options options = switch (testCase.getString("encoding")) {
            case "utf8" -> Re2.Options.defaults();
            case "latin1" -> Re2.Options.latin1();
            default -> throw new IllegalArgumentException("unknown encoding: " + testCase.getString("encoding"));
        };
        options.setLongestMatch(testCase.getBoolean("longest"));

        Prog program = compile(decodeHex(testCase.getString("patternHex")), options.parseFlags());
        Slice context = decodeHex(testCase.getString("textHex"));
        int start = Integer.parseInt(testCase.getString("start"));
        int end = Integer.parseInt(testCase.getString("end"));
        Prog.MatchKind matchKind = switch (testCase.getString("anchor")) {
            case "unanchored", "start" -> testCase.getBoolean("longest")
                    ? Prog.MatchKind.LONGEST_MATCH
                    : Prog.MatchKind.FIRST_MATCH;
            case "both" -> Prog.MatchKind.FULL_MATCH;
            default -> throw new IllegalArgumentException("unknown anchor: " + testCase.getString("anchor"));
        };
        boolean anchored = !testCase.getString("anchor").equals("unanchored");

        int[] groupZero = new int[2];
        boolean matched = Nfa.searchGroupZero(
                program,
                context,
                start,
                end,
                anchored,
                matchKind,
                groupZero);
        assertThat(matched).as("matched").isEqualTo(testCase.getBoolean("matched"));
        if (matched) {
            String[] offsets = testCase.getString("groups").split(",", 2)[0].split(":");
            assertThat(groupZero).containsExactly(
                    Integer.parseInt(offsets[0]) - start,
                    Integer.parseInt(offsets[1]) - start);
        }
    }

    private static void assertGroupZero(
            String pattern,
            Slice context,
            int start,
            int end,
            boolean anchored,
            boolean longest,
            int expectedStart,
            int expectedEnd)
    {
        assertGroupZero(pattern, context, start, end, anchored, longest, expectedStart, expectedEnd, null);
    }

    private static void assertGroupZero(
            String pattern,
            Slice context,
            int start,
            int end,
            boolean anchored,
            boolean longest,
            int expectedStart,
            int expectedEnd,
            Nfa.Workspace workspace)
    {
        assertGroupZero(compile(pattern), context, start, end, anchored, longest, expectedStart, expectedEnd, workspace);
    }

    private static void assertGroupZero(
            Prog program,
            Slice context,
            int start,
            int end,
            boolean anchored,
            boolean longest,
            int expectedStart,
            int expectedEnd,
            Nfa.Workspace workspace)
    {
        int[] groupZero = new int[2];
        assertThat(Nfa.searchGroupZero(
                program,
                context,
                start,
                end,
                anchored,
                longest ? Prog.MatchKind.LONGEST_MATCH : Prog.MatchKind.FIRST_MATCH,
                groupZero,
                workspace)).isTrue();
        assertThat(groupZero).containsExactly(expectedStart, expectedEnd);
    }

    private static Prog compile(String pattern)
    {
        return compile(pattern, FLAGS);
    }

    private static Prog compile(String pattern, int flags)
    {
        return compile(Slices.wrappedBuffer(pattern.getBytes(StandardCharsets.UTF_8)), flags);
    }

    private static Prog compile(Slice pattern, int flags)
    {
        ParseResult parsed = RegexpParser.parse(pattern, flags);

        Prog prog = Compiler.compile(parsed.regexp(), false, 0);
        assertThat(prog).as("compile: %s", pattern).isNotNull();
        return prog;
    }

    private static Slice decodeHex(String value)
    {
        return Slices.wrappedBuffer(value.equals("-") ? new byte[0] : HEX_FORMAT.parseHex(value));
    }

    private static Slice latin1(String value)
    {
        return Slices.wrappedBuffer(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static Slice randomPrintableText(int size, int seed)
    {
        Random random = new Random(seed);
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            int c = random.nextInt(128);
            if (c < 0x20) {
                c = 0x20;
            }
            bytes[i] = (byte) c;
        }
        return Slices.wrappedBuffer(bytes);
    }

    private static void assertParity(Prog prog, Slice text, Slice context, boolean anchored, boolean longest, String pattern)
    {
        int start = text.byteArrayOffset() - context.byteArrayOffset();
        int end = start + text.length();
        boolean noSubmatch = Nfa.search(prog, context, start, end, anchored, (longest ? Prog.MatchKind.LONGEST_MATCH : Prog.MatchKind.FIRST_MATCH), null);
        boolean withSubmatch = Nfa.search(prog, context, start, end, anchored, (longest ? Prog.MatchKind.LONGEST_MATCH : Prog.MatchKind.FIRST_MATCH), new int[8]);

        assertThat(noSubmatch)
                .as(
                        "parity pattern=%s textLen=%s contextLen=%s anchored=%s longest=%s",
                        pattern,
                        text.length(),
                        context.length(),
                        anchored,
                        longest)
                .isEqualTo(withSubmatch);
    }
}
