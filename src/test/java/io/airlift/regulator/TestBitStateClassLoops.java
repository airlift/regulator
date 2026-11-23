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

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Checks the class loops BitState reads in one scan: which lists the program marks as loops, and
/// that a search through them answers, captures, and marks its visited bitmap exactly as the
/// instruction-by-instruction search does.
public class TestBitStateClassLoops
{
    private static final List<String> PATTERNS = List.of(
            "[a-z]*x",
            "[a-z]+x",
            "(\\w+) (\\d+)",
            "(\\w+)\\s+(\\w+)",
            "(\\w*)(\\w*)",
            "(a+)(a+)",
            "(a*)*b",
            "(?:(\\w+),)*(\\w+)",
            "(\\d+):(\\d+)",
            "\\b(\\w+)\\b",
            "(\\w+)$",
            "^(\\w+)",
            "([a-c]*)([b-d]*)",
            "[a-z]*a",
            "\\w*a",
            "(\\w*)a",
            "(?i)([a-c]+)x",
            "[a-z]*?x",
            "(.*)(\\w+)",
            "(.+?)(\\d+)",
            "(\\w+)|(\\d+)",
            "((\\w)+)",
            "(\\w{2,5})(\\w*)",
            "([^,]*),([^,]*)",
            "x*",
            "(x*)*");

    @Test
    public void testGreedyClassRunsAreLoops()
    {
        assertThat(compile("[a-z]*x", Regexp.LATIN1).bitStateClassLoopCount()).isEqualTo(1);
        // Each `+` is a first member and then a loop.
        assertThat(compile("(\\w+) (\\d+)", Regexp.LATIN1).bitStateClassLoopCount()).isEqualTo(2);
        // A member that also starts what follows the loop is pushed, and still loops, beside
        // members of ranges that push nothing.
        assertThat(compile("[a-z]*a", Regexp.LATIN1).bitStateClassLoopCount()).isEqualTo(1);
        assertThat(compile("\\w*a", Regexp.LATIN1).bitStateClassLoopCount()).isEqualTo(1);
        // A UTF-8 class loops on its one-byte members and leaves for the others.
        assertThat(compile("[a-z\u00E9]*x", 0).bitStateClassLoopCount()).isEqualTo(1);
    }

    @Test
    public void testOtherListsAreNotLoops()
    {
        assertThat(compile("abc", Regexp.LATIN1).bitStateClassLoopCount()).isZero();
        assertThat(compile("(a|b)c", Regexp.LATIN1).bitStateClassLoopCount()).isZero();
        // A lazy loop tries what follows before its class.
        assertThat(compile("[a-z]*?x", Regexp.LATIN1).bitStateClassLoopCount()).isZero();
    }

    @Test
    public void testLoopsAnswerAsInstructionsDo()
            throws ReflectiveOperationException
    {
        Random random = new Random(20261122);
        for (int flags : new int[] {Regexp.LATIN1, 0}) {
            for (String pattern : PATTERNS) {
                Prog prog = compile(pattern, flags);
                for (String text : exhaustiveTexts("ab1 ,x", 4)) {
                    assertSameSearch(prog, pattern, Slices.wrappedBuffer(text.getBytes(StandardCharsets.ISO_8859_1)));
                }
                for (int i = 0; i < 200; i++) {
                    assertSameSearch(prog, pattern, randomText(random));
                }
            }
        }
    }

    @Test
    public void testLongRunsCrossBitmapWords()
            throws ReflectiveOperationException
    {
        for (String pattern : List.of("(\\w+) (\\d+)", "(\\w*)(\\w*)x", "(?:(\\w+),)*(\\w+)", "[a-z]*a", "(a+)(a+)$")) {
            Prog prog = compile(pattern, Regexp.LATIN1);
            for (int length : new int[] {62, 63, 64, 65, 127, 128, 129, 200}) {
                assertSameSearch(prog, pattern, Slices.utf8Slice("a".repeat(length)));
                assertSameSearch(prog, pattern, Slices.utf8Slice("a".repeat(length) + " 12"));
                assertSameSearch(prog, pattern, Slices.utf8Slice("a".repeat(length) + "x"));
                assertSameSearch(prog, pattern, Slices.utf8Slice(("a".repeat(length / 4) + ",").repeat(4) + "b"));
            }
        }
    }

    @Test
    public void testUtf8ClassLeavesAndEntersTheLoopAgain()
            throws ReflectiveOperationException
    {
        Random random = new Random(20261123);
        for (String pattern : List.of("[a-z\u00E9]*x", "([a-z\u00E9]+)x", "(\\pL+) (\\pL+)")) {
            Prog prog = compile(pattern, 0);
            assertThat(prog.bitStateClassLoopCount()).as(pattern).isPositive();
            // A two-byte member inside the run, a lead byte with nothing after it, and a run that
            // crosses a bitmap word before its two-byte member.
            assertSameSearch(prog, pattern, Slices.utf8Slice("a\u00E9ax"));
            assertSameSearch(prog, pattern, Slices.wrappedBuffer(new byte[] {'a', (byte) 0xC3, 'x'}));
            assertSameSearch(prog, pattern, Slices.utf8Slice("a".repeat(70) + "\u00E9x"));
            assertSameSearch(prog, pattern, Slices.utf8Slice("\u00E9".repeat(40) + " " + "a\u00E9".repeat(40) + "x"));
            for (int i = 0; i < 300; i++) {
                StringBuilder text = new StringBuilder();
                int length = random.nextInt(120);
                for (int j = 0; j < length; j++) {
                    String alphabet = "aab\u00E9\u00E9 x\u0416";
                    text.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 0 && random.nextInt(3) == 0) {
                    // Cut a character short or leave a stray continuation byte.
                    bytes[random.nextInt(bytes.length)] = (byte) (random.nextBoolean() ? 0xC3 : 0xA9);
                }
                assertSameSearch(prog, pattern, Slices.wrappedBuffer(bytes));
            }
        }
    }

    @Test
    public void testClassLoopsAreChargedToTheProgram()
    {
        Regexp regexp = RegexpParser.parse(Slices.utf8Slice("(\\w+) (\\d+)"), Regexp.LIKE_PERL | Regexp.LATIN1).regexp();
        long maxMemory = 1 << 20;
        Prog prog = Compiler.compile(regexp, false, maxMemory);

        assertThat(prog.bitStateClassLoopCount()).isEqualTo(2);
        assertThat(prog.bitStateClassLoopMemory()).isPositive();
        assertThat(Compiler.estimatedProgramMemory(prog))
                .isEqualTo(Compiler.flattenedProgramMemory(prog.size()) + prog.onePassMemory() + prog.prefixScanMemory() + prog.bitStateClassLoopMemory());
        assertThat(Compiler.estimatedProgramMemory(prog) + prog.dfaMemory()).isEqualTo(maxMemory);

        // An unbudgeted program keeps its loops and charges nothing, and a reversed program,
        // which BitState never searches, looks for none.
        assertThat(Compiler.compile(regexp, false, 0).bitStateClassLoopCount()).isEqualTo(2);
        assertThat(Compiler.compile(regexp, true, maxMemory).bitStateClassLoopCount()).isZero();
    }

    @Test
    public void testClassLoopsYieldToTheBudget()
    {
        Regexp regexp = RegexpParser.parse(Slices.utf8Slice("(\\w+) (\\d+)"), Regexp.LIKE_PERL | Regexp.LATIN1).regexp();
        Prog unbudgeted = Compiler.compile(regexp, false, 0);
        long flattenCharge = Compiler.flattenedProgramMemory(unbudgeted.size());

        int kept = 0;
        int dropped = 0;
        for (long maxMemory = flattenCharge; maxMemory < flattenCharge + (64 << 10); maxMemory += 64) {
            Prog prog;
            try {
                prog = Compiler.compile(regexp, false, maxMemory);
            }
            catch (RegexpCompileMemoryLimitException e) {
                // Too small for the program itself. The loops never cause this: they are
                // decided last, against what the rest left.
                continue;
            }
            assertThat(Compiler.estimatedProgramMemory(prog)).as("budget %s", maxMemory).isLessThanOrEqualTo(maxMemory);
            long classLoopMemory = prog.bitStateClassLoopMemory();
            if (classLoopMemory == 0) {
                assertThat(prog.bitStateClassLoopCount()).isZero();
                assertThat(BitState.search(prog, Slices.utf8Slice("abc 123"), false, Prog.MatchKind.FIRST_MATCH, new int[6])).isTrue();
                dropped++;
                continue;
            }
            kept++;
            for (Dfa.DfaInstance.Kind kind : new Dfa.DfaInstance.Kind[] {Dfa.DfaInstance.Kind.FIRST_MATCH, Dfa.DfaInstance.Kind.LONGEST_MATCH}) {
                // A negative reservation gives the loops' bytes back: a DFA that would
                // initialize without the loops initializes with them.
                assertThat(prog.leavesDfaInitialization(0, kind))
                        .as("budget %s kind %s", maxMemory, kind)
                        .isEqualTo(prog.leavesDfaInitialization(-classLoopMemory, kind));
            }
        }
        assertThat(kept).isPositive();
        assertThat(dropped).isPositive();
    }

    private static void assertSameSearch(Prog prog, String pattern, Slice text)
            throws ReflectiveOperationException
    {
        int slotCount = prog.captureSlotCount();
        for (boolean anchored : new boolean[] {false, true}) {
            for (Prog.MatchKind matchKind : new Prog.MatchKind[] {Prog.MatchKind.FIRST_MATCH, Prog.MatchKind.LONGEST_MATCH, Prog.MatchKind.FULL_MATCH}) {
                for (int start : new int[] {0, Math.min(1, text.length())}) {
                    int[] expected = new int[slotCount];
                    int[] actual = new int[slotCount];
                    BitState.Workspace expectedWorkspace = new BitState.Workspace();
                    BitState.Workspace actualWorkspace = new BitState.Workspace();

                    boolean expectedFound = BitState.searchWithoutClassLoopsForTesting(prog, text, start, text.length(), anchored, matchKind, expected, expectedWorkspace);
                    boolean actualFound = BitState.search(prog, text, start, text.length(), anchored, matchKind, actual, actualWorkspace);

                    String description = "pattern %s text %s anchored %s kind %s start %s".formatted(
                            pattern,
                            Arrays.toString(text.getBytes()),
                            anchored,
                            matchKind,
                            start);
                    assertThat(actualFound).as(description).isEqualTo(expectedFound);
                    assertThat(actual).as(description).isEqualTo(expected);
                    // The same entries, so the same work: a loop marks a position only when the
                    // instructions would enter the list there.
                    assertThat(visited(actualWorkspace)).as(description).isEqualTo(visited(expectedWorkspace));
                }
            }
        }
    }

    private static long[] visited(BitState.Workspace workspace)
            throws ReflectiveOperationException
    {
        Field field = BitState.Workspace.class.getDeclaredField("visited");
        field.setAccessible(true);
        return (long[]) field.get(workspace);
    }

    private static List<String> exhaustiveTexts(String alphabet, int maximumLength)
    {
        List<String> texts = new ArrayList<>();
        texts.add("");
        int begin = 0;
        for (int length = 1; length <= maximumLength; length++) {
            int end = texts.size();
            for (int i = begin; i < end; i++) {
                for (int c = 0; c < alphabet.length(); c++) {
                    texts.add(texts.get(i) + alphabet.charAt(c));
                }
            }
            begin = end;
        }
        return texts;
    }

    private static Slice randomText(Random random)
    {
        String alphabet = "aaabcz019 ,:x_A\n";
        byte[] bytes = new byte[random.nextInt(300)];
        for (int i = 0; i < bytes.length; i++) {
            // Mostly long runs of class members, which is where a loop reads many bytes.
            bytes[i] = (byte) (random.nextInt(8) == 0 ? alphabet.charAt(random.nextInt(alphabet.length())) : 'a' + random.nextInt(3));
        }
        if (bytes.length > 0 && random.nextInt(4) == 0) {
            bytes[random.nextInt(bytes.length)] = (byte) 0xC3;
        }
        return Slices.wrappedBuffer(bytes);
    }

    private static Prog compile(String pattern, int flags)
    {
        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice(pattern), Regexp.LIKE_PERL | flags);
        Prog prog = Compiler.compile(parsed.regexp(), false, 0);
        assertThat(prog).as("compile: %s", pattern).isNotNull();
        assertThat(prog.canBitState()).as("canBitState: %s", pattern).isTrue();
        return prog;
    }
}
