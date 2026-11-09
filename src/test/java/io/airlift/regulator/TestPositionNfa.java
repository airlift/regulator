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

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Function;
import java.util.function.LongFunction;

import static io.airlift.regulator.TestingPatternShapes.compileAtSmallestDfaBudget;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestPositionNfa
{
    private static final String BOUNDED_REPEAT = "[a-q][^u-z]{20,80}[x\\xE0-\\xFF]";
    private static final int EXPRESSIONS = 80;

    // The languages and modes whose group-zero searches the differential tests compare with
    // their capture engines. Each text is a Java string, valid in its charset. RE2 and Rust in
    // UTF-8 promise the ordinary engines' results on malformed UTF-8 too, so their texts also hold
    // a stray continuation byte and a truncated sequence beside a match; Trino's capture route and
    // group-zero route disagree on malformed UTF-8 with or without the automaton.
    private static final List<Language> LANGUAGES = List.of(
            new Language("RE2 Latin-1", ISO_8859_1, false, expression -> Re2.compile(utf8Slice(expression), Re2.Options.latin1())),
            new Language("RE2 Latin-1 longest match", ISO_8859_1, false, expression -> Re2.compile(utf8Slice(expression), Re2.Options.latin1().setLongestMatch(true))),
            new Language("Rust bytes", ISO_8859_1, false, TestPositionNfa::rustBytes),
            new Language("RE2", UTF_8, true, expression -> Re2.compile(utf8Slice(expression))),
            new Language("Rust", UTF_8, true, expression -> RustRegexp.compile(utf8Slice(expression))),
            new Language("Java", UTF_8, false, expression -> JavaRegexp.compile(utf8Slice(expression))),
            new Language("Trino", UTF_8, false, expression -> TrinoRegexp.compile(utf8Slice(expression)).re2()));
    // A stray continuation byte and a truncated three-byte sequence.
    private static final List<byte[]> MALFORMED_UTF_8 = List.of(new byte[] {(byte) 0x80}, new byte[] {(byte) 0xE6, (byte) 0x97});
    // Each text sits this far into its backing array, past any longest match, so a window left in
    // backing-array positions would miss the match it was found for.
    private static final int BACKING_OFFSET = 1024;

    // The atoms of random expressions, each with a text it matches in every language. The
    // two-character literal is a group, so that a quantifier repeats all of it. The text of
    // `(?i:[a-c]é)` folds only its ASCII letter, since Java without Unicode case and the Latin-1
    // languages fold no other. A Latin-1 pattern would read a non-ASCII literal as its UTF-8
    // bytes, so its expressions write each such literal as a byte escape and leave out the last
    // atom, which Latin-1 cannot hold.
    private static final List<Case> ATOMS = List.of(
            new Case("a", "a"),
            new Case("b", "b"),
            new Case("(?:ab)", "ab"),
            new Case("é", "é"),
            new Case("ÿ", "ÿ"),
            new Case("[a-c]", "c"),
            new Case("[^u-z]", "é"),
            new Case(".", "ÿ"),
            new Case("(?i:ab)", "aB"),
            new Case("(?i:[a-c]é)", "Cé"),
            new Case("日", "日"));

    /// A count on a matcher whose DFA bailed runs the automaton, not the Pike NFA.
    /// `TestDfaCountMatches` owns the public count's handoff to such a matcher.
    @Test
    public void testCountAfterDfaBailSkipsPikeNfa()
    {
        // At a 3 MiB budget, a DFA count of this pattern over 256 KiB of short words resets the
        // DFA too often and bails. The text holds an x every 997 bytes and no other byte of u-z
        // or above 0x7F, so each x ends one match.
        RustRegexp pattern = RustRegexp.compile(utf8Slice(BOUNDED_REPEAT), RustRegexp.Options.bytes().setUnicode(false).setMaxMemory(3L << 20));
        assertThat(pattern.forwardProgramForDiagnostics().positionNfa()).isNotNull();
        Slice text = wrappedBuffer(stateExplosionInput(256 << 10));
        assertThat(pattern.countMatches(text)).isEqualTo(Dfa.COUNT_BAILED);

        // The matcher a bailed count falls back on skips the forward DFA. Its finds take the
        // automaton, and BitState finds each span in the automaton's window.
        RegexpMatcher matcher = pattern.matcher(text, 0);
        matcher.markSkipsForwardDfa();
        List<String> spans = spans(matcher, 0);
        assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).isTrue();
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isFalse();

        RustRegexp captured = RustRegexp.compile(utf8Slice("(" + BOUNDED_REPEAT + ")"), RustRegexp.Options.bytes().setUnicode(false).setMaxMemory(3L << 20));
        assertThat(spans).hasSize(262).isEqualTo(spans(captured.matcher(text), 1));
        assertThat(pattern.count(text)).isEqualTo(262);
    }

    /// On a text longer than BitState's limit, BitState searches the automaton's window only when
    /// its visited bitmap is at most the cap, since each find clears it. The first windows of this
    /// pattern's one-byte matches, the narrowest 403 bytes, fit BitState's limit, but their
    /// bitmaps are over the cap, so the Pike NFA searches them. The automaton, of seven words of
    /// state, keeps its state in the matcher's workspace, which shows that it ran.
    @Test
    public void testWideWindowSearchesWithPikeNfa()
            throws ReflectiveOperationException
    {
        String expression = "[a-q]|[r-z0-9 ][^u-z]{20,400}[x\\xE0-\\xFF]";
        CompiledPattern pattern = rustBytes(expression);
        starveDfas(pattern);
        Prog program = pattern.forwardProgramForDiagnostics();
        assertThat(program.positionNfa().maxLength()).isEqualTo(402);
        assertThat(program.positionNfa().usesWorkspace()).isTrue();
        int firstWindow = 1 + program.positionNfa().maxLength();
        assertThat(firstWindow).isLessThanOrEqualTo(program.unanchoredBitStateTextMaxSize());
        assertThat(program.listCount() * (firstWindow + 1L)).isGreaterThan(CompiledPattern.POSITION_NFA_WINDOW_BIT_STATE_MAXIMUM_BYTES * Byte.SIZE);

        // Dense one-byte matches, then a filler longer than BitState's limit, which holds none.
        String filler = "!".repeat(program.unanchoredBitStateTextMaxSize() + 1);
        Slice text = wrappedBuffer(("abcdefghijklmnopq".repeat(60) + filler).getBytes(ISO_8859_1));
        RegexpMatcher matcher = pattern.matcher(text);
        List<String> spans = spans(matcher, 0);
        assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).isFalse();
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isTrue();
        Field workspaceField = RegexpMatcher.class.getDeclaredField("positionNfaWorkspace");
        workspaceField.setAccessible(true);
        assertThat(workspaceField.get(matcher)).isNotNull();
        assertThat(spans).hasSize(1020).isEqualTo(spans(rustBytes("(" + expression + ")").matcher(text), 1));
    }

    @Test
    public void testAdmission()
    {
        // A class is one position however many ranges it holds, so BOUNDED_REPEAT has one
        // position per class, 82. In UTF-8, [^u-z] is seven positions: its sequences take one for
        // each byte, and share the positions of their common continuation bytes.
        assertPositions(rustBytes(BOUNDED_REPEAT), 82, 82);
        assertPositions(Re2.compile(utf8Slice("[a-q][^u-z]{10,30}[x\\xE0-\\xFF]")), 214, 123);
        assertPositions(Re2.compile(utf8Slice("[a-q][a-z ]{20,80}x")), 82, 82);
        // One, two, three and eight words of state.
        assertPositions(rustBytes("a[b-r]{61,62}x"), 64, 64);
        assertPositions(rustBytes("a[b-r]{62,63}x"), 65, 65);
        assertPositions(rustBytes("a[b-r]{125,126}x"), 128, 128);
        assertPositions(rustBytes("a[b-r]{126,127}x"), 129, 129);
        assertPositions(rustBytes("a[b-r]{509,510}x"), 512, 512);

        for (CompiledPattern rejected : List.of(
                // Unbounded matches.
                rustBytes("[a-q][^u-z]+x"),
                // An assertion.
                rustBytes("\\b[a-q]{2,5}"),
                // An empty match.
                rustBytes("[a-q]{0,5}"),
                // A match that must end the text.
                rustBytes("[a-q]{2,5}$"),
                // More than 512 positions, in bytes and in UTF-8.
                rustBytes("a[b-r]{510,511}x"),
                Re2.compile(utf8Slice("[a-q][^u-z]{80}[x\\xE0-\\xFF]")),
                // More than 32 fan-in word tests a byte.
                rustBytes("(?:a[b-r]{1,9}){13}"))) {
            assertThat(rejected.forwardProgramForDiagnostics().positionNfa()).as(rejected.pattern().toStringUtf8()).isNull();
        }
    }

    /// A matcher owns the workspace of a program of more than two words of state and reuses it
    /// across its searches, each of which starts from an empty state. The first search ends on the
    /// last required copy of the class, which steps to an optional copy that would accept the lone
    /// `a` after the match were the next search to start from that state. A search with no live
    /// state skips the `!`, which no match starts at, without stepping it, so only a search that
    /// starts from an empty state finds no match there.
    @Test
    public void testMatcherOwnsAndReusesWorkspace()
            throws ReflectiveOperationException
    {
        CompiledPattern pattern = rustBytes("a[a-r]{126,128}");
        starveDfas(pattern);
        Prog program = pattern.forwardProgramForDiagnostics();
        assertThat(program.positionNfa().positionCountForDiagnostics()).isEqualTo(129);
        String match = "a" + "c".repeat(126);
        String filler = "!".repeat(program.unanchoredBitStateTextMaxSize() + 1);
        Slice input = wrappedBuffer((filler + match + "!a" + filler + match).getBytes(ISO_8859_1));
        int secondStart = 2 * filler.length() + match.length() + 2;
        List<String> expected = List.of(filler.length() + ":" + (filler.length() + match.length()), secondStart + ":" + (secondStart + match.length()));

        RegexpMatcher matcher = pattern.matcher(input);
        Field workspaceField = RegexpMatcher.class.getDeclaredField("positionNfaWorkspace");
        workspaceField.setAccessible(true);
        assertThat(workspaceField.get(matcher)).isNull();
        assertThat(spans(matcher, 0)).isEqualTo(expected);
        assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).isFalse();
        Object workspace = workspaceField.get(matcher);
        assertThat(workspace).isNotNull();
        Field statesField = PositionNfa.Workspace.class.getDeclaredField("states");
        statesField.setAccessible(true);
        Object states = statesField.get(workspace);
        assertThat(states).isNotNull();

        matcher.reset(input);
        assertThat(spans(matcher, 0)).isEqualTo(expected);
        assertThat(workspaceField.get(matcher)).isSameAs(workspace);
        assertThat(statesField.get(workspace)).isSameAs(states);
    }

    /// The automaton yields to the forward DFAs: it is reserved after every route selection makes,
    /// and only where the DFA still initializes, so once a forward DFA initializes at some budget,
    /// it initializes at every larger one. At the least budget at which a DFA initializes, the
    /// automaton's reservation would leave it too little, so the automaton is left out.
    @Test
    public void testYieldsToForwardDfas()
    {
        for (String expression : List.of("a", "[ab]")) {
            LongFunction<CompiledPattern> compiler = maxMemory -> Re2.compile(utf8Slice(expression), Re2.Options.latin1().setMaxMemory(maxMemory));
            for (Dfa.DfaInstance.Kind kind : List.of(Dfa.DfaInstance.Kind.FIRST_MATCH, Dfa.DfaInstance.Kind.LONGEST_MATCH)) {
                String label = expression + " " + kind;
                assertThat(compileAtSmallestDfaBudget(compiler, kind).forwardProgramForDiagnostics().positionNfa()).as(label).isNull();
                long initializedBudget = -1;
                int keptBesideDfa = 0;
                // The range spans both expressions' least DFA budgets of both kinds.
                for (long maxMemory = 10_000; maxMemory <= 16_000; maxMemory += 8) {
                    Prog program = compiler.apply(maxMemory).forwardProgramForDiagnostics();
                    boolean initialized = program.getCachedDfa(kind) != null;
                    if (initializedBudget >= 0) {
                        assertThat(initialized).as("%s at %s, initialized at %s", label, maxMemory, initializedBudget).isTrue();
                    }
                    else if (initialized) {
                        initializedBudget = maxMemory;
                    }
                    if (initialized && program.positionNfa() != null) {
                        keptBesideDfa++;
                    }
                }
                assertThat(initializedBudget).as(label).isGreaterThan(10_000);
                assertThat(keptBesideDfa).as(label).isPositive();
            }
        }
    }

    /// A step's fan-in work stays small. Each copy of a repeated multibyte class in UTF-8 begins
    /// where a few sequences of the copy before it end; those sequences' ends step by their
    /// follow sets, so a bounded repeat tests one fan-in group a byte in UTF-8, as it does in
    /// bytes mode. Fan-in tests, groups times words of state, are capped at 32 a byte.
    @Test
    public void testFanInTestsPerByteAreBounded()
    {
        String repeat = "[a-q][^u-z]{10,30}[x\\xE0-\\xFF]";
        for (CompiledPattern pattern : List.of(
                rustBytes(repeat),
                Re2.compile(utf8Slice(repeat)),
                RustRegexp.compile(utf8Slice(repeat), RustRegexp.Options.bytes().setUnicode(true)))) {
            assertThat(pattern.forwardProgramForDiagnostics().positionNfa().fanInGroupCountForDiagnostics()).as(pattern.pattern().toStringUtf8()).isEqualTo(1);
        }

        // Each copy of the group after the first begins a fan-in group: of the nine copies of
        // [b-r] before it, the ninth steps to it by the shift, and the other eight, exactly the
        // fan-in minimum, make its group. Twelve copies hold 120 positions in two words, so 11
        // groups make 22 tests; thirteen hold 130 in three, so 12 groups would make 36, and
        // testAdmission rejects them.
        PositionNfa twelve = rustBytes("(?:a[b-r]{1,9}){12}").forwardProgramForDiagnostics().positionNfa();
        assertThat(twelve.positionCountForDiagnostics()).isEqualTo(120);
        assertThat(twelve.fanInGroupCountForDiagnostics()).isEqualTo(11);
    }

    /// Each language's group-zero searches, with its DFAs starved so that every search falls
    /// back as it does after a DFA bails, find what the capture engines find for the same pattern
    /// in a capture group.
    @Test
    public void testFallbackAgreesWithCaptureEngines()
    {
        List<Case> shared = List.of(
                new Case("[a-q][a-z ]{20,80}x", "a" + "z".repeat(80) + "x"),
                new Case("[a-q][^u-z]{10,30}x", "a" + "à".repeat(10) + "x"),
                // The earliest-ending match is not the leftmost-first one.
                new Case("a.{5}b|c", "a12c45b"),
                new Case("(?:ab|a)(?:bcd|c){1,3}", "abcdc"),
                new Case("x{2,6}?y|xz", "xxy"),
                // Leftmost-first takes the earlier alternative, leftmost-longest the longer one.
                new Case("a{1,3}|a{2,5}b", "aaaab"),
                new Case("(?i)[a-c]{3,9}z", "AbCz"),
                // These fill one word of state exactly, or step from the last position of a word
                // of state to the first of the next.
                new Case("a[b-r]{61,62}x", "a" + "b".repeat(62) + "x"),
                new Case("a[b-r]{62,63}x", "a" + "r".repeat(63) + "x"),
                new Case("a[b-r]{125,126}x", "a" + "c".repeat(126) + "x"),
                new Case("a[b-r]{126,127}x", "a" + "q".repeat(127) + "x"),
                new Case("a[b-r]{509,510}x", "a" + "b".repeat(510) + "x"),
                // Eleven fan-in groups across two words of state.
                new Case("(?:a[b-r]{1,9}){12}", "abc".repeat(12)));
        List<Case> bytes = new ArrayList<>(shared);
        bytes.addAll(List.of(
                new Case(BOUNDED_REPEAT, "a" + "b".repeat(20) + "x"),
                new Case("[a-q][^u-z]{1,80}[x\\xE0-\\xFF]", "abà"),
                new Case("[a-q][^u-z]{10,30}[x\\xE0-\\xFF]", "a" + "c".repeat(10) + "ÿ"),
                new Case("[a-q][^u-z]{20,160}[x\\xE0-\\xFF]", "q" + "a".repeat(160) + "x"),
                new Case("[a-q].{20,80}x", "a" + " ".repeat(20) + "x")));

        for (Language language : LANGUAGES) {
            Random random = new Random(7);
            for (Case testCase : language.charset() == ISO_8859_1 ? bytes : shared) {
                String label = language.name() + " " + testCase.expression();
                CompiledPattern pattern = language.compiler().apply(testCase.expression());
                assertThat(pattern.forwardProgramForDiagnostics().positionNfa()).as(label).isNotNull();
                starveDfas(pattern);
                // A filler of a byte that begins no match.
                assertFallbackAgrees(label, language, pattern, testCase.match(), '!', 150, random);
            }
        }
    }

    /// The fallback agrees with the capture engines on random expressions of literals, classes,
    /// any character, case-insensitive groups, and alternations, under optional and bounded
    /// repeats, greedy or lazy, whose copies the automaton's shift, fan-in groups, and follow sets
    /// step through, a few with bounds from 20 to 79.
    @Test
    public void testFallbackAgreesOnRandomExpressions()
    {
        for (Language language : LANGUAGES) {
            Random random = new Random(20261109);
            int admitted = 0;
            for (int index = 0; index < EXPRESSIONS; index++) {
                StringBuilder expression = new StringBuilder();
                StringBuilder match = new StringBuilder();
                appendSequence(random, language.charset() == ISO_8859_1, 2, true, expression, match);
                String label = language.name() + " " + expression;
                CompiledPattern pattern = language.compiler().apply(expression.toString());
                // Every expression matches only bounded, non-empty text, but one can hold more
                // positions or fan-in tests than the automaton admits.
                if (pattern.forwardProgramForDiagnostics().positionNfa() == null) {
                    continue;
                }
                admitted++;
                // The DFAs starve before the first search: a DFA that search cached would serve
                // every later one in place of the automaton.
                starveDfas(pattern);
                // The filler is u, which [^u-z] does not match, or, where a run of u holds a
                // match, a line feed, which few expressions match.
                String uFiller = "u".repeat(pattern.forwardProgramForDiagnostics().positionNfa().maxLength());
                char filler = pattern.find(wrappedBuffer(uFiller.getBytes(language.charset()))) ? '\n' : 'u';
                assertFallbackAgrees(label, language, pattern, match.toString(), filler, 12, random);
            }
            assertThat(admitted).as(language.name()).isGreaterThan(EXPRESSIONS / 2);
        }
    }

    /// Checks that the group-zero searches of `pattern`, whose DFAs are starved, agree with the
    /// capture engines on texts of `match`: at the start and the end, adjacent, cut short, beside
    /// fillers longer than BitState's limits, beside malformed UTF-8 where the language allows
    /// it, and in `randomTexts` texts of runs of its characters. Each text is a view at
    /// [#BACKING_OFFSET] into its backing array.
    private static void assertFallbackAgrees(String label, Language language, CompiledPattern pattern, String match, char filler, int randomTexts, Random random)
    {
        CompiledPattern captured = language.compiler().apply("(" + pattern.pattern().toStringUtf8() + ")");
        assertThat(captured.matches(wrappedBuffer(match.getBytes(language.charset())))).as("%s fully matches %s", label, match).isTrue();

        // BitState searches a text as long as its limit itself; the automaton takes longer ones.
        Prog program = pattern.forwardProgramForDiagnostics();
        String unanchoredFiller = String.valueOf(filler).repeat(program.unanchoredBitStateTextMaxSize() + 1);
        String anchoredFiller = String.valueOf(filler).repeat(program.anchoredBitStateTextMaxSize() + 1);
        List<String> texts = new ArrayList<>();
        // Matches at the start and the end, adjacent matches, and a match cut short.
        for (String text : List.of(match, match + match, "zz" + match, match + "zz", match + match + "!" + match, match.substring(1))) {
            texts.addAll(List.of(text, unanchoredFiller + text, text + unanchoredFiller + text, text + anchoredFiller));
        }
        for (int index = 0; index < randomTexts; index++) {
            String text = runs(random, match);
            texts.add(text);
            if (index % 4 == 0) {
                texts.add(text + unanchoredFiller + text);
            }
        }
        List<byte[]> inputs = new ArrayList<>();
        for (String text : texts) {
            inputs.add(text.getBytes(language.charset()));
        }
        if (language.allowsMalformed()) {
            byte[] matchBytes = match.getBytes(language.charset());
            byte[] fillerBytes = unanchoredFiller.getBytes(language.charset());
            for (byte[] malformed : MALFORMED_UTF_8) {
                inputs.add(concat(matchBytes, malformed, matchBytes));
                inputs.add(concat(fillerBytes, malformed, matchBytes, malformed));
                inputs.add(concat(matchBytes, malformed, fillerBytes, matchBytes));
            }
        }
        for (byte[] input : inputs) {
            byte[] backing = new byte[BACKING_OFFSET + input.length];
            System.arraycopy(input, 0, backing, BACKING_OFFSET, input.length);
            assertAgrees(label, pattern, captured, wrappedBuffer(backing, BACKING_OFFSET, input.length));
        }
    }

    private static byte[] concat(byte[]... parts)
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            bytes.writeBytes(part);
        }
        return bytes.toByteArray();
    }

    /// Appends to `expression` a sequence of one to three random terms, and to `match` a text the
    /// sequence matches. A required sequence matches no empty text: its first term is a required
    /// atom or group, repeated at least once.
    private static void appendSequence(Random random, boolean latin1, int depth, boolean required, StringBuilder expression, StringBuilder match)
    {
        int terms = 1 + random.nextInt(3);
        for (int term = 0; term < terms; term++) {
            boolean requiredTerm = required && term == 0;
            StringBuilder termExpression = new StringBuilder();
            StringBuilder termMatch = new StringBuilder();
            int kind = depth == 0 ? 0 : random.nextInt(5);
            boolean atomTerm = kind <= 2;
            if (atomTerm) {
                Case atom = ATOMS.get(random.nextInt(latin1 ? ATOMS.size() - 1 : ATOMS.size()));
                termExpression.append(latin1 ? byteEscapes(atom.expression()) : atom.expression());
                termMatch.append(atom.match());
            }
            else {
                termExpression.append("(?:");
                appendSequence(random, latin1, depth - 1, requiredTerm, termExpression, termMatch);
                if (kind == 4) {
                    termExpression.append('|');
                    StringBuilder alternativeMatch = new StringBuilder();
                    appendSequence(random, latin1, depth - 1, requiredTerm, termExpression, alternativeMatch);
                    if (random.nextBoolean()) {
                        termMatch = alternativeMatch;
                    }
                }
                termExpression.append(')');
            }

            // The term occurs once, optionally, or in a bounded repeat, greedy or lazy. A few
            // single atoms take bounds from 20 to 79, which spread a class's copies across words
            // of state. A required term occurs at least once.
            int minimum = 1;
            int maximum = 1;
            int quantifier = random.nextInt(4);
            if (quantifier == 1 && !requiredTerm) {
                minimum = 0;
                termExpression.append(random.nextBoolean() ? "?" : "??");
            }
            else if (quantifier >= 2) {
                boolean wide = atomTerm && depth == 2 && random.nextInt(5) == 0;
                minimum = wide ? 20 + random.nextInt(41) : (requiredTerm ? 1 : 0) + random.nextInt(3);
                maximum = Math.max(1, minimum + random.nextInt(wide ? 20 : 4));
                termExpression.append('{').append(minimum).append(',').append(maximum).append('}');
                if (quantifier == 3) {
                    termExpression.append('?');
                }
            }
            expression.append(termExpression);
            match.append(termMatch.toString().repeat(minimum + random.nextInt(maximum - minimum + 1)));
        }
    }

    /// Writes each non-ASCII character of `expression` as the byte escape of its Latin-1 byte.
    private static String byteEscapes(String expression)
    {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < expression.length(); index++) {
            char character = expression.charAt(index);
            if (character < 0x80) {
                escaped.append(character);
            }
            else {
                escaped.append(String.format(Locale.ROOT, "\\x%02X", (int) character));
            }
        }
        return escaped.toString();
    }

    private record Case(String expression, String match) {}

    private record Language(String name, Charset charset, boolean allowsMalformed, Function<String, CompiledPattern> compiler) {}

    private static void assertAgrees(String label, CompiledPattern pattern, CompiledPattern captured, Slice input)
    {
        String description = label + " on " + describe(input);
        List<String> expected = spans(captured.matcher(input), 1);
        RegexpMatcher matcher = pattern.matcher(input);
        assertThat(spans(matcher, 0)).as(description).isEqualTo(expected);
        // Where BitState takes the automaton's widest window, on any text, it finds each span in
        // it, so no find steps the Pike NFA.
        Prog program = pattern.forwardProgramForDiagnostics();
        int widestWindow = 2 * program.positionNfa().maxLength();
        if (widestWindow <= program.unanchoredBitStateTextMaxSize() && program.listCount() * (widestWindow + 1L) <= CompiledPattern.POSITION_NFA_WINDOW_BIT_STATE_MAXIMUM_BYTES * Byte.SIZE) {
            assertThat(matcher.nfaWorkspaceInitializedForDiagnostics()).as(description).isFalse();
        }
        // A region that starts past the first byte searches from a nonzero start.
        if (input.length() > 0) {
            List<String> expectedInRegion = spans(captured.matcher(input).reset(input, 1, input.length()), 1);
            assertThat(spans(pattern.matcher(input).reset(input, 1, input.length()), 0)).as("%s in a region from 1", description).isEqualTo(expectedInRegion);
        }
        assertThat(pattern.count(input)).as(description).isEqualTo(expected.size());
        assertThat(pattern.find(input)).as(description).isEqualTo(!expected.isEmpty());

        boolean expectedMatches = captured.matcher(input).matches();
        assertThat(pattern.matches(input)).as(description).isEqualTo(expectedMatches);
        assertThat(pattern.matcher(input).matches()).as(description).isEqualTo(expectedMatches);

        RegexpMatcher expectedLookingAt = captured.matcher(input);
        boolean lookingAt = expectedLookingAt.lookingAt();
        assertThat(pattern.lookingAt(input)).as(description).isEqualTo(lookingAt);
        RegexpMatcher actualLookingAt = pattern.matcher(input);
        assertThat(actualLookingAt.lookingAt()).as(description).isEqualTo(lookingAt);
        if (lookingAt) {
            assertThat(actualLookingAt.end()).as(description).isEqualTo(expectedLookingAt.end());
        }
    }

    /// Starves the forward DFAs before their first search, so that each search finds them unable
    /// to initialize and falls back as it does after a DFA bails.
    private static void starveDfas(CompiledPattern pattern)
    {
        pattern.forwardProgramForDiagnostics().setDfaMemory(0);
        if (pattern.booleanProgramForDiagnostics() != null) {
            pattern.booleanProgramForDiagnostics().setDfaMemory(0);
        }
    }

    /// Returns each find's span of `group`, which is group zero's span in the capture-group form.
    private static List<String> spans(RegexpMatcher matcher, int group)
    {
        List<String> spans = new ArrayList<>();
        while (matcher.find()) {
            spans.add(matcher.start(group) + ":" + matcher.end(group));
        }
        return spans;
    }

    /// Returns `input` as Latin-1 text, with each run of more than 16 of one character written
    /// as the character, `×`, and the run's length.
    private static String describe(Slice input)
    {
        String text = input.toString(ISO_8859_1);
        StringBuilder description = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            int runEnd = index + 1;
            while (runEnd < text.length() && text.charAt(runEnd) == character) {
                runEnd++;
            }
            if (runEnd - index > 16) {
                description.append(character).append('×').append(runEnd - index);
            }
            else {
                description.append(text, index, runEnd);
            }
            index = runEnd;
        }
        return description.toString();
    }

    /// Random text of runs of the characters of `match` and a few others, so that runs of a
    /// repeated class come in lengths around its bounds.
    private static String runs(Random random, String match)
    {
        String alphabet = match + "abcqrxyz \nàÿ";
        StringBuilder text = new StringBuilder();
        int length = random.nextInt(2 * match.length() + 40);
        while (text.length() < length) {
            char character = alphabet.charAt(random.nextInt(alphabet.length()));
            int run = random.nextInt(4) == 0 ? 1 + random.nextInt(match.length() + 1) : 1;
            text.append(String.valueOf(character).repeat(run));
        }
        return text.toString();
    }

    private static void assertPositions(CompiledPattern pattern, int positionCount, int maxLength)
    {
        PositionNfa positionNfa = pattern.forwardProgramForDiagnostics().positionNfa();
        String label = pattern.pattern().toStringUtf8();
        assertThat(positionNfa).as(label).isNotNull();
        assertThat(positionNfa.positionCountForDiagnostics()).as(label).isEqualTo(positionCount);
        assertThat(positionNfa.maxLength()).as(label).isEqualTo(maxLength);
    }

    private static RustRegexp rustBytes(String expression)
    {
        return RustRegexp.compile(utf8Slice(expression), RustRegexp.Options.bytes().setUnicode(false));
    }

    /// Short words of the letters a to t, digits, and punctuation, with an x every 997 bytes.
    private static byte[] stateExplosionInput(int length)
    {
        Random random = new Random(42);
        byte[] alphabet = "abcdefghijklmnopqrst \n,.;0123456789".getBytes(US_ASCII);
        byte[] input = new byte[length];
        for (int index = 0; index < input.length; index++) {
            input[index] = index % 997 == 996 ? (byte) 'x' : alphabet[random.nextInt(alphabet.length)];
        }
        return input;
    }
}
