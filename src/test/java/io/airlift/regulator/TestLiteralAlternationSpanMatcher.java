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

import io.airlift.regulator.LiteralAlternationSpanMatcher.ExactMatcher;
import io.airlift.regulator.LiteralAlternationSpanMatcher.ExtractedLiterals;
import io.airlift.regulator.LiteralAlternationSpanMatcher.FoldedMatcher;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.airlift.regulator.TestingPatternShapes.assertNoProgramSearch;
import static io.airlift.regulator.TestingPatternShapes.compileAtSmallestBudget;
import static io.airlift.regulator.TestingPatternShapes.holdsAt;
import static io.airlift.regulator.TestingPatternShapes.shape;
import static io.airlift.slice.SizeOf.instanceSize;
import static io.airlift.slice.SizeOf.sizeOfByteArray;
import static io.airlift.slice.SizeOf.sizeOfObjectArray;
import static io.airlift.slice.SizeOf.sizeOfShortArray;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.lang.Math.toIntExact;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestLiteralAlternationSpanMatcher
{
    // The DFA's start-scan recheck distance, for which a search keeps the route its sample chose.
    private static final int RECHECK_BYTES = 64 * 1024;
    // Past every memory budget leastValue searches for, and far from overflowing a long.
    private static final long MAXIMUM_LEAST_VALUE = 1L << 40;

    @Test
    public void testPinnedNativeBoundaries()
    {
        LiteralAlternationSpanMatcher scanner = scanner("ERROR|WARN|FATAL");
        Slice input = utf8Slice("info WARN then ERROR and FATAL");

        assertSpan(scanner, input, 0, 5, 9);
        assertSpan(scanner, input, 9, 15, 20);
        assertSpan(scanner, input, 20, 25, 30);
        assertThat(scanner.findSpan(input, 30)).isEqualTo(Dfa.SEARCH_NO_MATCH);

        LiteralAlternationSpanMatcher unicode = scanner("βeta|γamma|alpha");
        assertSpan(unicode, utf8Slice("xxγamma then βeta"), 0, 2, 8);
        assertSpan(unicode, utf8Slice("xxγamma then βeta"), 8, 14, 19);
    }

    @Test
    public void testGeneralEngineAgreement()
    {
        List<String> patterns = List.of(
                "ERROR|WARN|FATAL",
                "foo|far|beta",
                "βeta|γamma|alpha",
                "alpha|βeta|終端",
                // The first alternative wins over a longer later one at the same start.
                "xa|b|xab",
                // A region end that cuts abc leaves the later ab.
                "abc|x|ab");
        List<Slice> inputs = List.of(
                utf8Slice(""),
                utf8Slice("ordinary text"),
                utf8Slice("FATAL then ERROR"),
                utf8Slice("xxfar then foo"),
                utf8Slice("xxβeta終端alpha"),
                utf8Slice("xab xa b xxab ÿþ"),
                utf8Slice("abcab xabc"),
                wrappedBuffer(new byte[] {(byte) 0xFF, 'E', 'R', 'R', 'O', 'R'}),
                wrappedBuffer("\u00FFFATAL\u00CEWARN\u00CE\u00CE\u00B2eta\u00E7\u00B5ERROR\u00FF\u00FE".getBytes(ISO_8859_1)));

        // Boolean find and count of RE2, Java, and Rust patterns run on the matcher and agree
        // with the general engine, including on malformed UTF-8.
        Map<String, CompiledPattern> compiledPatterns = new LinkedHashMap<>();
        for (String expression : patterns) {
            compiledPatterns.putAll(re2JavaAndRustPatterns(expression));
        }
        // A Rust byte-mode escape is a single byte, not a code point.
        compiledPatterns.put("Rust bytes (?-u)ERROR|\\xFF\\xFE", RustRegexp.compile(utf8Slice("(?-u)ERROR|\\xFF\\xFE"), RustRegexp.Options.bytes()));
        for (Map.Entry<String, CompiledPattern> entry : compiledPatterns.entrySet()) {
            String label = entry.getKey();
            CompiledPattern pattern = entry.getValue();
            assertThat(shape(pattern)).as(label).isEqualTo(CompiledPattern.Shape.LITERAL_ALTERNATION);
            // Count answers from the matcher without building the general engine's DFA.
            pattern.count(inputs.getLast());
            assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).as(label).isNull();
            for (Slice input : inputs) {
                long expectedCount = generalEngineCount(pattern, input);
                assertThat(pattern.find(input)).as("%s input %s", label, input.toStringUtf8()).isEqualTo(expectedCount > 0);
                assertThat(pattern.count(input)).as("%s input %s", label, input.toStringUtf8()).isEqualTo(expectedCount);
            }
        }

        for (String expression : patterns) {
            LiteralAlternationSpanMatcher scanner = scanner(expression);
            Re2 pattern = Re2.compile(utf8Slice(expression));
            for (Slice input : inputs) {
                for (int start = 0; start <= input.length(); start++) {
                    RegexpMatcher matcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
                    long span = scanner.findSpan(input, start);
                    if (matcher.find(start)) {
                        assertThat(span)
                                .as("pattern %s input %s start %s", expression, input, start)
                                .isEqualTo(((long) matcher.start() << 32) | (matcher.end() & 0xFFFF_FFFFL));
                    }
                    else {
                        assertThat(span)
                                .as("pattern %s input %s start %s", expression, input, start)
                                .isEqualTo(Dfa.SEARCH_NO_MATCH);
                    }
                    // A region search reports the general engine's first match in the region.
                    for (int end = start; end <= input.length(); end++) {
                        long expected = matcher.reset(input, start, end).find() ? ((long) matcher.start() << 32) | (matcher.end() & 0xFFFF_FFFFL) : Dfa.SEARCH_NO_MATCH;
                        assertThat(scanner.findSpan(input, start, end)).as("pattern %s input %s region %s-%s", expression, input, start, end).isEqualTo(expected);
                    }
                }
            }
        }

        // The matcher reports leftmost-first matches, so a leftmost-longest pattern keeps the
        // general engine.
        assertThat(shape(Re2.compile(utf8Slice("xa|b|xab"), Re2.Options.defaults().setLongestMatch(true)))).isEqualTo(CompiledPattern.Shape.ENGINE);
    }

    @Test
    public void testEligibility()
    {
        assertThat(optionalScanner("ERROR|WARN|FATAL")).isNotNull();
        assertThat(optionalScanner("alpha|βeta|終端")).isNotNull();
        assertThat(optionalScanner("foo|far")).isNotNull();
        assertThat(optionalScanner("foo|foo")).isNull();
        assertThat(optionalScanner("sam|samwise")).isNull();
        assertThat(optionalScanner("foo|")).isNull();
        assertThat(optionalScanner("foo|f[ao]r")).isNull();
        assertThat(optionalScanner("foo|(bar)")).isNull();
        assertThat(optionalScanner("(?i:foo)|bar")).isNull();
        assertThat(optionalScanner("^foo|bar")).isNull();
        assertThat(optionalScanner("foo")).isNull();
        // A literal over 64 bytes would cost its length per byte of a dense near-miss input.
        assertThat(optionalScanner("a".repeat(63) + "b|x")).isNotNull();
        assertThat(optionalScanner("a".repeat(64) + "b|x")).isNull();

        // Above the trie's limit, a literal set also takes the empty alternatives and merged
        // classes the parsers' factoring leaves in a dictionary.
        String dictionary = words(65);
        assertThat(optionalLiteralSet(words(64))).isNull();
        assertThat(optionalLiteralSet(dictionary)).isNotNull();
        assertThat(optionalLiteralSet(dictionary + "|sam|samwise|absentmindedness(?:|'s)|cat[sy]")).isNotNull();
        assertThat(optionalLiteralSet(words(60) + "|sam|samwise")).isNull();
        assertThat(optionalLiteralSet(dictionary + "|")).isNull();
        assertThat(optionalLiteralSet("(?i:" + dictionary + ")")).isNull();
        assertThat(optionalLiteralSet(dictionary + "|(bar)")).isNull();
        assertThat(optionalLiteralSet(dictionary + "|f[a-p]")).isNotNull();
        assertThat(optionalLiteralSet(dictionary + "|f.r")).isNull();
        assertThat(optionalLiteralSet(dictionary + "|f[a-q]")).isNull();

        // A choice of several literals must end its concatenation, since one before more text
        // expands to every combination. A choice of empty literals only adds nothing.
        assertThat(optionalLiteralSet(dictionary + "|f[a-p]r")).isNull();
        assertThat(optionalLiteralSet("(?:" + dictionary + ")z")).isNull();
        assertThat(optionalLiteralSet("[a-p][a-p][a-p]")).isNull();
        assertThat(optionalLiteralSet(dictionary + "|b[xy](?:|)|(?:|)c[xy]")).isNotNull();

        // Where a choice ends the pattern, the extraction skips each literal that equals or
        // extends an earlier one, which a leftmost-first search never reports. Before more text a
        // later alternative can still match, so each row keeps the route it takes with every
        // literal.
        for (List<String> row : List.of(
                List.of("(?:|a)b", "ab", "0-2", "engine"),
                List.of("x(?:|a)b", "xab", "0-3", "engine"),
                List.of("b(?:|a)", "ba", "0-1", "set"),
                List.of("(?:xa|xb|xa)y", "xay", "0-3", "engine"),
                List.of("(?:ab|abc)d", "abcd", "0-4", "engine"))) {
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(dictionary + "|" + row.get(0)));
            TrinoRegexpMatcher matcher = regexp.matcher(utf8Slice(row.get(1)));
            assertThat(matcher.find()).as(row.get(0)).isTrue();
            assertThat(matcher.start() + "-" + matcher.end()).as(row.get(0)).isEqualTo(row.get(2));
            assertThat(regexp.usesLiteralSetMatcherForDiagnostics() ? "set" : "engine").as(row.get(0)).isEqualTo(row.get(3));
        }
        // Under 4 KiB, which leaves the set less still, a large dictionary's extraction passes its
        // memory bound, so the set is spelled literal by literal. A concatenation child's first
        // literal, here the empty one, holds its match while the rest of the child spells, so the
        // literal extending it is dropped as the extraction skips it. Two-, three-, and four-byte
        // runes spell as their UTF-8 bytes.
        String largeDictionary = words(4096);
        for (List<String> row : List.of(
                List.of("xab|xabc", "xabc", "0-3"),
                List.of("z(?:|q)", "zq", "0-1"),
                List.of("é終💰|é終💰é", "xé終💰é", "1-10"))) {
            String expression = largeDictionary + "|" + row.get(0);
            assertThat(LiteralAlternationSpanMatcher.extractLiteralSet(TrinoRegexpParser.parse(utf8Slice(expression), Regexp.LIKE_PERL).regexp(), 4096)).as(row.get(0)).isSameAs(ExtractedLiterals.OVER_BUDGET);
            TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice(expression), TrinoRegexp.Options.defaults().setMaxMemory(4096));
            TrinoRegexpMatcher matcher = regexp.matcher(utf8Slice(row.get(1)));
            assertThat(matcher.find()).as(row.get(0)).isTrue();
            assertThat(matcher.start() + "-" + matcher.end()).as(row.get(0)).isEqualTo(row.get(2));
            assertThat(regexp.usesLiteralSetMatcherForDiagnostics()).as(row.get(0)).isTrue();
        }
        // A Latin-1 program spells its literals and classes as bytes. Numbers of one width extend
        // none of the others, so the extraction lists every one, and a budget that leaves the set
        // more than it retains still leaves the extraction too little. Longest-match search keeps
        // the program, so its forward budget is the one the set was analyzed under.
        String latin1Dictionary = IntStream.range(1000, 5096).mapToObj(index -> "w" + index).collect(Collectors.joining("|")) + "|caf[\\xE9\\xE8]|na\\xEFve";
        long latin1Budget = Re2.compile(utf8Slice(latin1Dictionary), Re2.Options.latin1().setMaxMemory(300_000).setLongestMatch(true)).forwardProgramForDiagnostics().dfaMemory();
        assertThat(LiteralAlternationSpanMatcher.extractLiteralSet(RegexpParser.parse(utf8Slice(latin1Dictionary), Re2.Options.latin1().parseFlags()).regexp(), latin1Budget)).isSameAs(ExtractedLiterals.OVER_BUDGET);
        Re2 latin1Pattern = Re2.compile(utf8Slice(latin1Dictionary), Re2.Options.latin1().setMaxMemory(300_000));
        RegexpMatcher latin1Matcher = latin1Pattern.matcher(wrappedBuffer("caf\u00E8 na\u00EFve w1007".getBytes(ISO_8859_1)));
        assertThat(latin1Matcher.spanSearchForDiagnostics()).isNotNull().isSameAs(latin1Pattern.literalSetSpanMatcherForDiagnostics());
        assertThat(findSpans(latin1Matcher)).containsExactly("0-4", "5-10", "11-16");

        // Extraction stops past a bound on its work, which a run of separately flagged literals
        // or deeply nested choices makes quadratic in the pattern.
        String flagRun = "(?s:a)(?-s:a)".repeat(8_000);
        String nestedChoices = "b";
        for (int depth = 0; depth < 90; depth++) {
            nestedChoices = "a".repeat(1000) + "(?:|" + nestedChoices + ")";
        }
        assertThat(optionalScanner("foo|" + flagRun)).isNull();
        assertThat(optionalLiteralSet(dictionary + "|" + flagRun)).isNull();
        assertThat(optionalLiteralSet(dictionary + "|" + nestedChoices)).isNull();
    }

    @Test
    public void testCollidingLiteralsBuildLiteralByLiteral()
    {
        // Literals whose hashes all land on one slot of the skip's duplicate table make its
        // lookups step past every earlier literal, so the extraction hands the set to the
        // literal-by-literal build, which admits and searches it as the table would.
        List<String> collidingLiterals = new ArrayList<>();
        Random random = new Random(1);
        while (collidingLiterals.size() < 256) {
            // Leading bytes alternate, so the parser factors no prefix out of the alternation.
            byte[] literal = new byte[8];
            literal[0] = (byte) (collidingLiterals.size() % 2 == 0 ? 'a' : 'b');
            int hash = SeededHash.hash(literal[0]);
            for (int index = 1; index < literal.length; index++) {
                literal[index] = (byte) ('a' + random.nextInt(26));
                hash = SeededHash.hash(((long) hash << 8) | literal[index]);
            }
            if ((hash & 1023) == 0) {
                collidingLiterals.add(new String(literal, UTF_8));
            }
        }
        String collidingSet = String.join("|", collidingLiterals);
        ParseResult collidingParse = TrinoRegexpParser.parse(utf8Slice(collidingSet), Regexp.LIKE_PERL);
        assertThat(LiteralAlternationSpanMatcher.extractLiteralSet(collidingParse.regexp(), TrinoRegexp.Options.defaults().maxMemory())).isSameAs(ExtractedLiterals.OVER_BUDGET);
        TrinoRegexp collidingRegexp = TrinoRegexp.compile(utf8Slice(collidingSet));
        assertThat(collidingRegexp.usesLiteralSetMatcherForDiagnostics()).isTrue();
        TrinoRegexpMatcher collidingMatcher = collidingRegexp.matcher(utf8Slice("zz " + collidingLiterals.getLast()));
        assertThat(collidingMatcher.find()).isTrue();
        assertThat(collidingMatcher.start() + "-" + collidingMatcher.end()).isEqualTo("3-11");
    }

    @Test
    public void testLargeAlternationRoutes()
    {
        Slice dictionary = utf8Slice(words(65));
        // A view between sentinel bytes, so spans must be relative to its offset.
        Slice backing = utf8Slice("!prefix word17 and word64, then 12K 7\u212A 3k word3x word2?");
        Slice input = backing.slice(1, backing.length() - 2);
        // Rust's parser folds (?i)k into the class [Kk\x{212A}], which carries no case-folding flag.
        String foldedDictionary = "(?i)" + IntStream.range(0, 65).mapToObj(index -> index + "k").collect(Collectors.joining("|"));
        List<CompiledPattern> patterns = List.of(
                Re2.compile(dictionary),
                JavaRegexp.compile(dictionary),
                RustRegexp.compile(dictionary),
                RustRegexp.compile(dictionary, RustRegexp.Options.defaults().setUnicode(false).setBytes(true)),
                RustRegexp.compile(utf8Slice(foldedDictionary)));
        for (CompiledPattern pattern : patterns) {
            assertLiteralSetFind(pattern, input);
        }
        // A Latin-1 program reads a class's members as bytes, so trie edges reach 0x00 and 0xFF,
        // and the text holds each of those bytes below, between, at, and above a state's edges.
        Slice latin1Backing = wrappedBuffer("!caf\u00E8 word\u0000 cafe caf\u00E9 caf\u0000 word\u00FF caf\u00FF?".getBytes(ISO_8859_1));
        assertLiteralSetFind(
                Re2.compile(utf8Slice(words(65) + "|caf[\\x00\\xE9\\xFF]"), Re2.Options.latin1()),
                latin1Backing.slice(1, latin1Backing.length() - 2));
        // Every byte follows p, so the dense rows read 256 byte classes, with no class shared by
        // the bytes labeling no edge, and a class from 128 up is read unsigned.
        String everyByteAfterP = IntStream.range(0, 256).mapToObj(value -> "p\\x{" + Integer.toHexString(value) + "}z").collect(Collectors.joining("|"));
        assertLiteralSetFind(Re2.compile(utf8Slice(everyByteAfterP), Re2.Options.latin1()), wrappedBuffer(new byte[] {'p', (byte) 0xFF, 'z'}));

        // Longest-match search keeps the program, so its forward budget is the uncharged control.
        Re2 longestMatch = Re2.compile(dictionary, Re2.Options.defaults().setLongestMatch(true));
        assertThat(longestMatch.literalSetSpanMatcherForDiagnostics()).isNull();
        assertThat(patterns.getFirst().forwardProgramForDiagnostics().dfaMemory())
                .isEqualTo(longestMatch.forwardProgramForDiagnostics().dfaMemory() - patterns.getFirst().literalSetSpanMatcherForDiagnostics().estimatedRetainedSize());
        assertThat(Re2.compile(utf8Slice(words(64))).literalSetSpanMatcherForDiagnostics()).isNull();
        assertThat(Re2.compile(utf8Slice("(" + words(65) + ")")).literalSetSpanMatcherForDiagnostics()).isNull();

        // The set answers full-Slice find and count only where the forward DFA each runs cannot
        // initialize beside it. Find's earliest-match search runs the longest-match DFA, which
        // needs more memory than count's first-match DFA, so between the two least budgets find
        // runs on the set and count on the DFA, as for a dictionary of tens of thousands of words
        // under a 96 MiB budget. A budget least for the root's skip shows that the skip, a
        // contiguous range or nibble tables, compiles with the same answers whether it fits or not;
        // without the Vector API no budget builds it. The count is answered without a matcher at
        // every budget, below the first-match DFA's least budget by the set. The program fits at
        // every one of these budgets, so the pattern keeps it.
        Slice wordsInput = utf8Slice("zz ax0 then ax64, ax8x and ax6?");
        for (String leadingBytes : List.of("abcdefgh", "acegikmo")) {
            String expression = leadingByteWords(leadingBytes, 65);
            Prog program = Re2.compile(utf8Slice(expression)).forwardProgramForDiagnostics();
            long dfaMemory = 2 * leastValue(1, budget -> new Dfa.DfaInstance(program, Dfa.DfaInstance.Kind.FIRST_MATCH, budget).ok());
            long countEdge = leastValue(dfaMemory, budget -> compileWithMaxMemory(expression, budget).forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH) != null);
            long findEdge = leastValue(countEdge, budget -> compileWithMaxMemory(expression, budget).forwardProgramForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH) != null);
            long skipEdge = VectorSupport.isAvailable()
                    ? leastValue(findEdge, budget -> compileWithMaxMemory(expression, budget).literalSetSpanMatcherForDiagnostics().rootByteScanSetForDiagnostics() != 0)
                    : findEdge;
            int scanSet = literalSet(expression).rootByteScanSetForDiagnostics();
            for (long budget : List.of(countEdge - 1, countEdge, findEdge - 1, findEdge, skipEdge - 1, skipEdge)) {
                Re2 pattern = compileWithMaxMemory(expression, budget);
                String label = expression.substring(0, 20) + " at " + budget;
                assertThat(pattern.literalSetSpanMatcherForDiagnostics()).as(label).isNotNull();
                assertThat(shape(pattern)).as(label).isEqualTo(budget >= findEdge ? CompiledPattern.Shape.ENGINE : CompiledPattern.Shape.LITERAL_ALTERNATION);
                assertThat(pattern.forwardProgramForDiagnostics().start()).as(label).isNotZero();
                assertThat(pattern.countsOnLiteralSetForDiagnostics()).as(label).isEqualTo(budget < countEdge);
                assertThat(pattern.literalSetSpanMatcherForDiagnostics().rootByteScanSetForDiagnostics()).as(label).isEqualTo(budget >= skipEdge ? scanSet : 0);
                // The dense rows yield to a forward DFA they would cost: at the least budget of
                // either DFA, and at the least budget that keeps the root skip, a DFA initializes
                // only beside the set without them, while one byte below the first-match DFA's,
                // where it initializes beside neither, the set keeps them.
                if (budget == countEdge - 1 || budget == countEdge || budget == findEdge || budget == skipEdge) {
                    assertThat(pattern.literalSetSpanMatcherForDiagnostics().denseDepthForDiagnostics() > 0).as(label).isEqualTo(budget == countEdge - 1);
                }
                assertThat(pattern.find(wordsInput)).as(label).isTrue();
                assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH) != null).as(label).isEqualTo(budget >= findEdge);
                assertThat(pattern.countMatches(wordsInput)).as(label).isEqualTo(3);
                assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH) != null).as(label).isEqualTo(budget >= countEdge);
                assertThat(findSpans(pattern.matcher(wordsInput, 0))).as(label).containsExactly("3-6", "12-16", "18-21");
            }
        }
    }

    @Test
    public void testLiteralSetWithoutProgram()
    {
        // Leftmost-first priority (ab before a), a literal extending an earlier one, which the
        // automaton omits but a full match still reaches (sam then samwise in one order, and word1
        // then word10), the reverse order (bilbo then bil, and samwise then sam in the other), a
        // duplicate, and non-ASCII literals.
        for (String sam : List.of("sam|samwise", "samwise|sam")) {
            String utf8Expression = "ab|a|" + sam + "|bilbo|bil|dup|dup|café|終わり|💰|" + words(65);
            String latin1Expression = "ab|a|" + sam + "|bilbo|bil|dup|dup|caf\\xE9|\\xFF|\\xA4|" + words(65);
            Slice utf8Input = utf8Slice("samwise, samx, bilbo and bil ate abc at a café: 終わり 💰 word10 word1x dup dupe sa");
            Slice malformedInput = wrappedBuffer(ByteArrays.concat(utf8Input, wrappedBuffer(new byte[] {(byte) 0xFF, 's', 'a', 'm', (byte) 0x80, 'a', 'b'})));
            Slice latin1Input = wrappedBuffer("samwise, samx, bilbo and bil ate abc at a café: ÿ ¤ word10 word1x dup dupe sa".getBytes(ISO_8859_1));
            List<String> utf8Probes = List.of("", "a", "ab", "abc", "sam", "samwise", "samwis", "samwisex", "samx", "bil", "bilbo", "bilb", "dup", "café", "caf", "終わり", "💰", "word1", "word10", "word100", "word64", "word65", "xsam");

            for (Map.Entry<String, LongFunction<CompiledPattern>> entry : literalSetCompilers(utf8Expression, latin1Expression).entrySet()) {
                boolean latin1 = entry.getKey().equals("RE2 Latin-1");
                List<Slice> inputs = new ArrayList<>(latin1 ? List.of(latin1Input) : List.of(utf8Input, malformedInput));
                utf8Probes.stream()
                        .map(probe -> latin1 ? wrappedBuffer(probe.replace("終わり", "ÿ").replace("💰", "¤").getBytes(ISO_8859_1)) : utf8Slice(probe))
                        .forEach(inputs::add);
                // The program fits the default budget, so the pattern keeps it and its DFAs; the
                // set routes beside the program answer as the general engine.
                String label = entry.getKey() + " " + sam;
                CompiledPattern withProgram = entry.getValue().apply(Re2.Options.defaults().maxMemory());
                assertThat(shape(withProgram)).as(label).isEqualTo(CompiledPattern.Shape.ENGINE);
                assertThat(withProgram.forwardProgramForDiagnostics().start()).as(label).isNotZero();
                assertSamPriority(withProgram, sam, label);
                // Only below the least budget of the program does the pattern compile without
                // one, down to the least budget of the set and the literals it omits.
                long programEdge = leastValue(1, budget -> holdsAt(entry.getValue(), budget, pattern -> pattern.forwardProgramForDiagnostics().start() != 0));
                long setEdge = leastValue(1, budget -> holdsAt(entry.getValue(), budget, _ -> true));
                assertThat(setEdge).as(label).isLessThan(programEdge);
                assertThatThrownBy(() -> entry.getValue().apply(setEdge - 1)).as(label).isInstanceOf(RegexpCompileMemoryLimitException.class);
                for (long budget : List.of(setEdge, programEdge - 1, programEdge)) {
                    String budgetLabel = label + " at " + budget;
                    boolean withoutProgram = budget < programEdge;
                    CompiledPattern pattern = entry.getValue().apply(budget);
                    assertThat(pattern.forwardProgramForDiagnostics().start() == 0).as(budgetLabel).isEqualTo(withoutProgram);
                    if (withoutProgram) {
                        assertThat(shape(pattern)).as(budgetLabel).isEqualTo(CompiledPattern.Shape.LITERAL_ALTERNATION);
                        assertThat(pattern.literalSetSpanMatcherForDiagnostics()).as(budgetLabel).isNotNull();
                        assertThat(pattern.hasReverseProgramInputForDiagnostics()).as(budgetLabel).isFalse();
                        assertThat(pattern.countsOnLiteralSetForDiagnostics()).as(budgetLabel).isTrue();
                    }
                    assertSamPriority(pattern, sam, budgetLabel);
                    for (Slice input : inputs) {
                        assertAgreesWithProgram(pattern, withProgram, input, budgetLabel);
                        // A view between sentinel bytes, so the routes must read from its offset
                        // and stop at its end.
                        Slice view = wrappedBuffer(ByteArrays.concat(utf8Slice("!"), input, utf8Slice("wise"))).slice(1, input.length());
                        assertAgreesWithProgram(pattern, withProgram, view, budgetLabel + " view");
                    }
                    if (withoutProgram) {
                        assertNoProgramSearch(pattern, budgetLabel);
                    }
                }
            }
        }

        // A capture buffer wider than group zero leaves its other groups unset at each anchor.
        String expression = "bilbo|bil|" + words(65);
        Re2 dictionary = compileAtSmallestBudget(budget -> compileWithMaxMemory(expression, budget), _ -> true);
        assertThat(dictionary.forwardProgramForDiagnostics().start()).isZero();
        Slice text = utf8Slice("xx bilbo");
        int[] groups = new int[4];
        Arrays.fill(groups, 7);
        assertThat(dictionary.findInto(text, 1, 8, groups)).isTrue();
        assertThat(groups).containsExactly(3, 8, -1, -1);
        Arrays.fill(groups, 7);
        assertThat(dictionary.lookingAtInto(text, 3, 8, groups)).isTrue();
        assertThat(groups).containsExactly(3, 8, -1, -1);
        Arrays.fill(groups, 7);
        assertThat(dictionary.matchesInto(text, 3, 8, groups)).isTrue();
        assertThat(groups).containsExactly(3, 8, -1, -1);
    }

    @Test
    public void testLiteralSetWithoutProgramBudget()
    {
        // A dictionary of thousands of words is far smaller as a set than as a program. Below the
        // program's least budget, which the longest-match compile shows, it compiles as the set,
        // within a few times the set's own size; at the set's own size it is rejected.
        String expression = IntStream.range(0, 4096).mapToObj(index -> Integer.toString(index * 7919, 36)).collect(Collectors.joining("|"));
        long setSize = literalSet(expression).withoutSearchTables().withoutRootSkipping().estimatedRetainedSize();
        long budget = 4 * setSize;
        assertThatThrownBy(() -> Re2.compile(utf8Slice(expression), Re2.Options.defaults().setMaxMemory(budget).setLongestMatch(true)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
        for (Map.Entry<String, LongFunction<CompiledPattern>> entry : literalSetCompilers(expression, expression).entrySet()) {
            String label = entry.getKey();
            CompiledPattern pattern = entry.getValue().apply(budget);
            assertThat(shape(pattern)).as(label).isEqualTo(CompiledPattern.Shape.LITERAL_ALTERNATION);
            assertThat(pattern.forwardProgramForDiagnostics().start()).as(label).isZero();
            assertThatThrownBy(() -> entry.getValue().apply(setSize)).as(label).isInstanceOf(RegexpCompileMemoryLimitException.class);
        }

        // Where most literals extend an earlier one, the full-match list is about half the set's
        // literals, and it is charged beside the set: at the least budget at which the pattern
        // compiles, the set, the list, and the placeholder program fit the forward share.
        Random random = new Random(20261108);
        List<String> literals = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            String word = random.ints(24, 'a', 'z' + 1).mapToObj(Character::toString).collect(Collectors.joining());
            literals.add(word);
            literals.add(word + "X");
        }
        String extendingExpression = String.join("|", literals);
        ParseResult parsed = RegexpParser.parse(utf8Slice(extendingExpression), Re2.Options.defaults().parseFlags());
        long listSize = LiteralSetSpanMatcher.ExtendingLiterals.analyze(parsed.regexp(), Re2.Options.defaults().maxMemory()).estimatedRetainedSize();
        long smallestBudget = leastValue(1, maxMemory -> holdsAt(memory -> compileWithMaxMemory(extendingExpression, memory), maxMemory, _ -> true));
        Re2 smallest = compileWithMaxMemory(extendingExpression, smallestBudget);
        assertThat(smallest.forwardProgramForDiagnostics().start()).isZero();
        long charged = smallest.literalSetSpanMatcherForDiagnostics().estimatedRetainedSize() + listSize + Compiler.estimatedProgramMemory(smallest.forwardProgramForDiagnostics());
        assertThat(charged).isLessThanOrEqualTo(smallestBudget - smallestBudget / 3);
    }

    // The languages and modes that compile a large literal set without a program, each from the
    // expression and a budget: `latin1Expression` for RE2's Latin-1 mode, and `utf8Expression`
    // for every other.
    private static Map<String, LongFunction<CompiledPattern>> literalSetCompilers(String utf8Expression, String latin1Expression)
    {
        Map<String, LongFunction<CompiledPattern>> compilers = new LinkedHashMap<>();
        compilers.put("RE2", budget -> Re2.compile(utf8Slice(utf8Expression), Re2.Options.defaults().setMaxMemory(budget)));
        compilers.put("Java", budget -> JavaRegexp.compile(utf8Slice(utf8Expression), JavaRegexp.Options.defaults().setMaxMemory(budget)));
        compilers.put("Rust", budget -> RustRegexp.compile(utf8Slice(utf8Expression), RustRegexp.Options.defaults().setMaxMemory(budget)));
        compilers.put("Rust bytes", budget -> RustRegexp.compile(utf8Slice(utf8Expression), RustRegexp.Options.defaults().setUnicode(false).setBytes(true).setMaxMemory(budget)));
        compilers.put("RE2 Latin-1", budget -> Re2.compile(utf8Slice(latin1Expression), Re2.Options.latin1().setMaxMemory(budget)));
        return compilers;
    }

    // The leftmost-first answers of `sam`, either `sam|samwise` or `samwise|sam`, in the general
    // engine: a search and a prefix take the first alternative that matches, while a full match
    // takes either.
    private static void assertSamPriority(CompiledPattern pattern, String sam, String label)
    {
        String samwise = sam.startsWith("samwise") ? "0-7" : "0-3";
        assertThat(span(pattern.findResult(utf8Slice("samwise")))).as(label).isEqualTo(samwise);
        assertThat(span(pattern.lookingAtResult(utf8Slice("samwise")))).as(label).isEqualTo(samwise);
        assertThat(pattern.matches(utf8Slice("samwise"))).as(label).isTrue();
        assertThat(span(pattern.findResult(utf8Slice("samx")))).as(label).isEqualTo("0-3");
        assertThat(span(pattern.lookingAtResult(utf8Slice("samx")))).as(label).isEqualTo("0-3");
        assertThat(pattern.matches(utf8Slice("samx"))).as(label).isFalse();
        assertThat(span(pattern.matchesResult(utf8Slice("xsamwise"), 1, 8))).as(label).isEqualTo("1-8");
        assertThat(pattern.countMatches(utf8Slice("samwise samx"))).as(label).isEqualTo(2);
    }

    // Every operation of `pattern` agrees with `withProgram`, the same expression compiled where
    // its program answers: boolean, position, and region calls at each anchor, count, and a
    // matcher's finds, matches, and lookingAt over regions.
    private static void assertAgreesWithProgram(CompiledPattern pattern, CompiledPattern withProgram, Slice input, String label)
    {
        String text = label + " input " + input.toStringUtf8();
        assertThat(pattern.find(input)).as(text).isEqualTo(withProgram.find(input));
        assertThat(pattern.lookingAt(input)).as(text).isEqualTo(withProgram.lookingAt(input));
        assertThat(pattern.matches(input)).as(text).isEqualTo(withProgram.matches(input));
        assertThat(pattern.countMatches(input)).as(text).isEqualTo(withProgram.countMatches(input));
        assertThat(findSpans(pattern.matcher(input, 0))).as(text).isEqualTo(findSpans(withProgram.genericGroupZeroMatcherForDiagnostics(input)));
        for (int start = 0; start <= input.length(); start++) {
            for (int end = start; end <= input.length(); end++) {
                String region = text + " region " + start + "-" + end;
                assertThat(span(pattern.findResult(input, start, end))).as(region).isEqualTo(span(withProgram.findResult(input, start, end)));
                assertThat(span(pattern.lookingAtResult(input, start, end))).as(region).isEqualTo(span(withProgram.lookingAtResult(input, start, end)));
                assertThat(span(pattern.matchesResult(input, start, end))).as(region).isEqualTo(span(withProgram.matchesResult(input, start, end)));
                RegexpMatcher matcher = pattern.matcher(input, 0).reset(input, start, end);
                RegexpMatcher expected = withProgram.genericGroupZeroMatcherForDiagnostics(input).reset(input, start, end);
                assertThat(findSpans(matcher)).as(region).isEqualTo(findSpans(expected));
                assertThat(matcher.reset(input, start, end).lookingAt() ? matcher.start() + "-" + matcher.end() : "none").as(region).isEqualTo(expected.reset(input, start, end).lookingAt() ? expected.start() + "-" + expected.end() : "none");
                assertThat(matcher.reset(input, start, end).matches()).as(region).isEqualTo(expected.reset(input, start, end).matches());
            }
        }
    }

    private static String span(MatchResult result)
    {
        return result == null ? "none" : result.start() + "-" + result.end();
    }

    @Test
    public void testLargeAlternationAgainstGeneralEngine()
    {
        String[] tokens = {"a", "b", "ab", "é", "ж", "終", "💰"};
        // Every other set pads its literals to a minimum length at which the search skips ahead,
        // covering two-byte and three-byte blocks and the 16-byte window cap.
        int[] skippingMinimumBytes = {LiteralSetSpanMatcher.SkipTable.MINIMUM_LENGTH, 5, 6, 9, LiteralSetSpanMatcher.SkipTable.MAXIMUM_WINDOW + 1};
        long maxMemory = TrinoRegexp.Options.defaults().maxMemory();
        Random random = new Random(20261031);
        for (int iteration = 0; iteration < 40; iteration++) {
            int minimumBytes = iteration % 2 == 0 ? 0 : skippingMinimumBytes[random.nextInt(skippingMinimumBytes.length)];
            List<String> literals = new ArrayList<>();
            // Repeats of a literal that the factoring places together collapse into one, so the
            // set counts distinct literals.
            int literalCount = 65 + random.nextInt(40);
            while (Set.copyOf(literals).size() < literalCount) {
                String base = literals.isEmpty() ? randomText(random, tokens, 1, 4) : literals.get(random.nextInt(literals.size()));
                String literal = switch (random.nextInt(5)) {
                    case 0 -> base + randomText(random, tokens, 1, 2);
                    case 1 -> randomText(random, tokens, 1, 2) + base;
                    case 2 -> base.substring(0, base.offsetByCodePoints(0, 1 + random.nextInt(base.codePointCount(0, base.length()))));
                    case 3 -> base.substring(base.offsetByCodePoints(0, random.nextInt(base.codePointCount(0, base.length()))));
                    default -> randomText(random, tokens, 1, 5);
                };
                while (literal.getBytes(UTF_8).length < minimumBytes) {
                    literal += tokens[random.nextInt(tokens.length)];
                }
                literals.add(literal);
            }
            String expression = String.join("|", literals);
            Re2 pattern = Re2.compile(utf8Slice(expression));
            assertThat(pattern.literalSetSpanMatcherForDiagnostics()).as(expression).isNotNull();
            boolean skipsAhead = literals.stream().allMatch(literal -> literal.getBytes(UTF_8).length >= LiteralSetSpanMatcher.SkipTable.MINIMUM_LENGTH);
            assertThat(pattern.literalSetSpanMatcherForDiagnostics().skipsAheadForDiagnostics()).as(expression).isEqualTo(skipsAhead);
            // Dense rows for no state, then for every state up to each depth through 3, so failure
            // paths end at the root's row alone or at dense rows up to each depth, each with and
            // without the skip ahead, and skipping ahead with and without any root skip, since a
            // set with the root skip reaches the plain skip ahead only after its scans end.
            List<LiteralSetSpanMatcher> trinoScanners = new ArrayList<>();
            for (int denseDepth = 0; denseDepth <= 3; denseDepth++) {
                trinoScanners.add(optionalLiteralSet(expression, maxMemory, denseDepth, false));
                if (skipsAhead) {
                    LiteralSetSpanMatcher skipping = optionalLiteralSet(expression, maxMemory, denseDepth, true);
                    trinoScanners.add(skipping);
                    if (skipping.rootByteScanSetForDiagnostics() != 0) {
                        trinoScanners.add(skipping.withoutRootSkipping());
                    }
                }
            }

            for (int inputIndex = 0; inputIndex < 5; inputIndex++) {
                Slice input = randomInput(random, literals, tokens);
                assertFindAgreement(pattern, input);
                for (int start = 0; start <= input.length(); start++) {
                    RegexpMatcher generalMatcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
                    long expected = generalMatcher.find(start)
                            ? ((long) generalMatcher.start() << 32) | (generalMatcher.end() & 0xFFFF_FFFFL)
                            : Dfa.SEARCH_NO_MATCH;
                    // A region that ends halfway to the input end cuts the matches that cross it.
                    int end = start + (input.length() - start) / 2;
                    long expectedInRegion = generalMatcher.reset(input, start, end).find()
                            ? ((long) generalMatcher.start() << 32) | (generalMatcher.end() & 0xFFFF_FFFFL)
                            : Dfa.SEARCH_NO_MATCH;
                    for (LiteralSetSpanMatcher trinoScanner : trinoScanners) {
                        String label = "dense depth " + trinoScanner.denseDepthForDiagnostics() +
                                (trinoScanner.skipsAheadForDiagnostics() ? " skipping ahead" : "") +
                                (trinoScanner.rootByteScanSetForDiagnostics() != 0 ? " with root skip" : "");
                        assertThat(trinoScanner.findSpan(input, start, input.length()))
                                .as("pattern %s %s input %s start %s", expression, label, input.toStringUtf8(), start)
                                .isEqualTo(expected);
                        assertThat(trinoScanner.findSpan(input, start, end))
                                .as("pattern %s %s input %s region %s-%s", expression, label, input.toStringUtf8(), start, end)
                                .isEqualTo(expectedInRegion);
                    }
                }
            }
        }
    }

    @Test
    public void testLiteralSetDenseRows()
    {
        // The states up to depth 3 keep dense rows. Under a budget one byte short of the rows at
        // a depth, the build takes the deepest depth whose rows fit, and at the trie's own size
        // none, where the set retains exactly what it does without dense rows.
        String dictionary = leadingByteWords("abcdefgh", 63) + "|abcde|bcd";
        long maxMemory = TrinoRegexp.Options.defaults().maxMemory();
        LiteralSetSpanMatcher literalSet = literalSet(dictionary);
        assertThat(literalSet.denseDepthForDiagnostics()).isEqualTo(3);
        long trieSize = optionalLiteralSet(dictionary, maxMemory, 0, true).withoutRootSkipping().estimatedRetainedSize();
        assertThat(literalSet.withoutSearchTables().withoutRootSkipping().estimatedRetainedSize()).isEqualTo(trieSize);
        for (int denseDepth = 1; denseDepth <= 3; denseDepth++) {
            long size = optionalLiteralSet(dictionary, maxMemory, denseDepth, true).withoutRootSkipping().estimatedRetainedSize();
            assertThat(optionalLiteralSet(dictionary, size).denseDepthForDiagnostics()).as("depth %s", denseDepth).isEqualTo(denseDepth);
            assertThat(optionalLiteralSet(dictionary, size - 1).denseDepthForDiagnostics()).as("depth %s", denseDepth).isEqualTo(denseDepth - 1);
        }
        LiteralSetSpanMatcher trieOnly = optionalLiteralSet(dictionary, trieSize);
        assertThat(trieOnly.denseDepthForDiagnostics()).isZero();
        assertThat(trieOnly.estimatedRetainedSize()).isEqualTo(trieSize);
        // Rows of all 18,279 states up to depth 3 of the four-letter words over 26 letters would
        // take 27 classes of 4 bytes each, almost 2 MB, past the 1 MiB the rows may take, so
        // their states keep rows only up to depth 2.
        String fourLetterWords = IntStream.range(0, 26 * 26 * 26)
                .mapToObj(index -> "" + (char) ('a' + index / 676) + (char) ('a' + index / 26 % 26) + (char) ('a' + index % 26) + "z")
                .collect(Collectors.joining("|"));
        assertThat(literalSet(fourLetterWords).denseDepthForDiagnostics()).isEqualTo(2);

        // A rejection's lower bound on the budget a set needs counts the set without its dense
        // rows or root skip, which the build adds only where they fit, so it never exceeds what
        // the set built with them retains.
        String base36Words = IntStream.range(0, 4096).mapToObj(index -> Integer.toString(index * 7919, 36)).collect(Collectors.joining("|"));
        for (String expression : List.of(dictionary, fourLetterWords, base36Words)) {
            Regexp parsed = TrinoRegexpParser.parse(utf8Slice(expression), Regexp.LIKE_PERL).regexp();
            assertThat(LiteralSetSpanMatcher.leastRetainedSize(parsed, maxMemory)).as(expression.substring(0, 20)).isLessThanOrEqualTo(literalSet(expression).estimatedRetainedSize());
        }
    }

    @Test
    public void testLiteralSetSkipsAhead()
    {
        // Every literal of word0 through word64 has at least five bytes, so the search skips ahead
        // by windows of five bytes, while adding a three-byte literal turns off window skipping.
        String dictionary = words(65);
        long maxMemory = TrinoRegexp.Options.defaults().maxMemory();
        LiteralSetSpanMatcher literalSet = literalSet(dictionary);
        assertThat(literalSet.skipsAheadForDiagnostics()).isTrue();
        assertThat(literalSet(dictionary + "|bcd").skipsAheadForDiagnostics()).isFalse();
        assertThat(optionalLiteralSet(dictionary, maxMemory, 3, false).skipsAheadForDiagnostics()).isFalse();

        // A window of filler ends in a block no literal holds, so the search passes the four
        // bytes before that block's end without a trie walk, up to the window whose block ends a
        // literal's window, where the walk finds the literal.
        Slice input = utf8Slice("z".repeat(1_000) + "word7");
        LiteralSetSpanMatcher.SkipDiagnostics diagnostics = new LiteralSetSpanMatcher.SkipDiagnostics();
        assertThat(literalSet.findSpanForDiagnostics(input, 0, input.length(), diagnostics)).isEqualTo((1_000L << 32) | 1_005);
        assertThat(diagnostics.windowSkipCount()).isEqualTo(1_000 / 4);
        assertThat(diagnostics.automatonHandoffCount()).isZero();

        // Only a search of at least CompiledPattern.MINIMUM_LONG_SEARCH_BYTES skips ahead; a
        // shorter one runs the automaton.
        Slice thresholdInput = utf8Slice("z".repeat(CompiledPattern.MINIMUM_LONG_SEARCH_BYTES - 5) + "word7");
        long thresholdMatch = ((long) (thresholdInput.length() - 5) << 32) | thresholdInput.length();
        for (int start : List.of(0, 1)) {
            LiteralSetSpanMatcher.SkipDiagnostics startDiagnostics = new LiteralSetSpanMatcher.SkipDiagnostics();
            assertThat(literalSet.findSpanForDiagnostics(thresholdInput, start, thresholdInput.length(), startDiagnostics)).isEqualTo(thresholdMatch);
            assertThat(startDiagnostics.windowSkipCount() > 0).as("start %s", start).isEqualTo(start == 0);
        }

        // A set with the root's skip crosses filler that leads no literal with one vector scan
        // rather than a window at a time, and stops scanning once
        // VectorByteSetScanner.SHORT_SCAN_LIMIT scans in a row each land within a few bytes, on
        // the leading byte g in every eleventh position, as the automaton's root skip does. A
        // scan that lands within CompiledPattern.MINIMUM_LONG_SEARCH_BYTES of the end leaves the
        // rest to the automaton without a window step, even on a leading byte, here a, that
        // starts no literal there.
        boolean scans = VectorSupport.isAvailable();
        String rootScanning = IntStream.range(0, 65).mapToObj(index -> "abcdefgh".charAt(index % 8) + "word" + index).collect(Collectors.joining("|"));
        LiteralSetSpanMatcher rootScanningSet = literalSet(rootScanning);
        assertThat(rootScanningSet.skipsAheadForDiagnostics()).isTrue();
        for (String filler : List.of("z".repeat(1_000), "z".repeat(1_000) + "a" + "z".repeat(10), ("z".repeat(10) + "g").repeat(20))) {
            Slice fillerInput = utf8Slice(filler + "aword0");
            long expected = ((long) filler.length() << 32) | (filler.length() + 6);
            LiteralSetSpanMatcher.SkipDiagnostics scanned = new LiteralSetSpanMatcher.SkipDiagnostics();
            assertThat(rootScanningSet.findSpanForDiagnostics(fillerInput, 0, fillerInput.length(), scanned)).as(filler).isEqualTo(expected);
            assertThat(rootScanningSet.count(fillerInput)).as(filler).isOne();
            assertThat(scanned.rootSkipCount()).as(filler).isEqualTo(scans ? (filler.contains("g") ? VectorByteSetScanner.SHORT_SCAN_LIMIT : 1) : 0);
            LiteralSetSpanMatcher.SkipDiagnostics stepped = new LiteralSetSpanMatcher.SkipDiagnostics();
            assertThat(rootScanningSet.withoutRootSkipping().findSpanForDiagnostics(fillerInput, 0, fillerInput.length(), stepped)).as(filler).isEqualTo(expected);
            if (scans) {
                assertThat(scanned.windowSkipCount()).as(filler).isLessThan(stepped.windowSkipCount());
                if (!filler.contains("g")) {
                    assertThat(scanned.windowSkipCount()).as(filler).isZero();
                }
            }
        }

        // The skip table is a retained charge of its own, its object and 1 KiB of shifts, built
        // only where the trie and the table fit the budget.
        long tableSize = optionalLiteralSet(dictionary, maxMemory, 0, true).estimatedRetainedSize();
        long trieSize = optionalLiteralSet(dictionary, maxMemory, 0, false).estimatedRetainedSize();
        assertThat(tableSize - trieSize).isEqualTo(instanceSize(LiteralSetSpanMatcher.SkipTable.class) + sizeOfByteArray(1024));
        assertThat(optionalLiteralSet(dictionary, tableSize).skipsAheadForDiagnostics()).isTrue();
        assertThat(optionalLiteralSet(dictionary, tableSize - 1).skipsAheadForDiagnostics()).isFalse();
        assertThat(literalSet.withoutSearchTables().estimatedRetainedSize()).isEqualTo(trieSize);

        // Literals sharing a long prefix give every window of a run of its first byte a zero
        // skip, and each window's trie walk reads the prefix before failing, so the search runs
        // out of its walk allowance and finishes on the automaton, which finds the late matches
        // and agrees with the general engine at the selected starts. Literals leading with b
        // through h give the set the root skip where the Vector API is available, and its loop
        // hands off the same way.
        String sharedPrefix = IntStream.range(0, 65).mapToObj(index -> "a".repeat(40) + "pad" + index).collect(Collectors.joining("|"));
        String rootScanningPrefix = sharedPrefix + IntStream.range(1, 8)
                .mapToObj(index -> "abcdefgh".charAt(index) + "a".repeat(39) + "pad0")
                .collect(Collectors.joining("|", "|", ""));
        Slice nearMisses = utf8Slice("a".repeat(20_000) + "pad7 " + "a".repeat(41) + "pad64");
        Re2 pattern = Re2.compile(utf8Slice(sharedPrefix));
        assertThat(pattern.literalSetSpanMatcherForDiagnostics().skipsAheadForDiagnostics()).isTrue();
        assertThat(findSpans(pattern.matcher(nearMisses, 0))).containsExactly("19960-20004", "20006-20050");
        for (String nearMissDictionary : List.of(sharedPrefix, rootScanningPrefix)) {
            LiteralSetSpanMatcher.SkipDiagnostics handoff = new LiteralSetSpanMatcher.SkipDiagnostics();
            assertThat(literalSet(nearMissDictionary).findSpanForDiagnostics(nearMisses, 0, nearMisses.length(), handoff)).as(nearMissDictionary).isEqualTo((19_960L << 32) | 20_004);
            assertThat(handoff.automatonHandoffCount()).as(nearMissDictionary).isOne();
        }
        LiteralSetSpanMatcher skipping = literalSet(sharedPrefix);
        RegexpMatcher generalMatcher = pattern.genericGroupZeroMatcherForDiagnostics(nearMisses);
        for (int start : List.of(0, 1, 19_960, 19_961, 20_005, 20_050)) {
            long expected = generalMatcher.find(start) ? ((long) generalMatcher.start() << 32) | (generalMatcher.end() & 0xFFFF_FFFFL) : Dfa.SEARCH_NO_MATCH;
            assertThat(skipping.findSpan(nearMisses, start, nearMisses.length())).as("start %s", start).isEqualTo(expected);
        }
        // A region ending one byte short of the first match cuts it, so the walk from its start
        // reads only the region's 43 bytes of it and finds nothing.
        long expectedInRegion = generalMatcher.reset(nearMisses, 19_939, 20_003).find()
                ? ((long) generalMatcher.start() << 32) | (generalMatcher.end() & 0xFFFF_FFFFL)
                : Dfa.SEARCH_NO_MATCH;
        assertThat(skipping.findSpan(nearMisses, 19_939, 20_003)).isEqualTo(expectedInRegion);

        // So does a region ending one byte short of a literal longer than
        // CompiledPattern.MINIMUM_LONG_SEARCH_BYTES, which the set with the root skip walks from
        // the leading byte its scan finds, before its last 64 bytes, and the set without it from a
        // window start.
        String longPrefix = IntStream.range(0, 65).mapToObj(index -> "a".repeat(80) + "pad" + index + "z").collect(Collectors.joining("|")) +
                IntStream.range(1, 8).mapToObj(index -> "abcdefgh".charAt(index) + "a".repeat(79) + "pad0z").collect(Collectors.joining("|", "|", ""));
        LiteralSetSpanMatcher longPrefixSet = literalSet(longPrefix);
        assertThat(longPrefixSet.skipsAheadForDiagnostics()).isTrue();
        if (scans) {
            assertThat(longPrefixSet).isInstanceOf(LiteralSetSpanMatcher.RootSkippingMatcher.class);
        }
        Slice clipped = utf8Slice("z".repeat(1_000) + "a".repeat(80) + "pad64z");
        for (LiteralSetSpanMatcher set : List.of(longPrefixSet, longPrefixSet.withoutRootSkipping())) {
            assertThat(set.findSpan(clipped, 0, clipped.length() - 1)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        }
    }

    @Test
    public void testLiteralSetSkipsRootBytes()
    {
        // Leading bytes a through h form a contiguous range, and abcde and bcd overlap, so abcdx
        // fails over from one to the other.
        String dictionary = leadingByteWords("abcdefgh", 63) + "|abcde|bcd";
        LiteralSetSpanMatcher literalSet = literalSet(dictionary);
        boolean skips = VectorSupport.isAvailable();
        // The skip selects its scan with the forward DFA's kernel selection: the range scan for a
        // contiguous range, and the nibble tables for a mixed set.
        assertThat(literalSet.rootByteScanSetForDiagnostics()).isEqualTo(skips ? (8 << 24) | ('h' << 8) | 'a' : 0);
        String mixedDictionary = leadingByteWords("acegikmo", 63) + "|abcde|bcd";
        LiteralSetSpanMatcher mixedLiteralSet = literalSet(mixedDictionary);
        assertThat(mixedLiteralSet.rootByteScanSetForDiagnostics()).isEqualTo(skips ? Dfa.MIXED_BYTE_SET_MARKER : 0);
        // A search at the root skips to the next leading byte with at least
        // CompiledPattern.MINIMUM_LONG_SEARCH_BYTES left, and walks the root's row with fewer.
        assertThat(literalSet.skipsRootBytes(63)).isFalse();
        assertThat(literalSet.skipsRootBytes(64)).isEqualTo(skips);
        assertThat(literalSet.skipsRootBytes(65)).isEqualTo(skips);
        // One leading byte is too few for any of the scans, so the set has no root skip.
        assertThat(literalSet(words(65)).rootByteScanSetForDiagnostics()).isZero();
        // Filler that leads no literal is skipped with one vector scan from a search that has at
        // least that many bytes left, and walked byte by byte from one that has fewer. A skip
        // starts only at a second root miss in a row, so text alternating leading and other bytes
        // never skips. Misses in short runs, such as the `. ` between words, start a skip that
        // lands within a few bytes, and VectorByteSetScanner.SHORT_SCAN_LIMIT such skips in a row
        // end skipping for the search, while a long skip starts the run again: each group of
        // SHORT_SCAN_LIMIT `gq. ` skips short one time fewer than the limit, then long.
        String shortSkips = "gq. ".repeat(VectorByteSetScanner.SHORT_SCAN_LIMIT);
        for (Map.Entry<String, Integer> entry : Map.of(
                "z".repeat(1_000), 1,
                "z".repeat(50), 0,
                "az".repeat(300), 0,
                "gq. ".repeat(300), VectorByteSetScanner.SHORT_SCAN_LIMIT,
                (shortSkips + "z".repeat(100)).repeat(20), 20 * VectorByteSetScanner.SHORT_SCAN_LIMIT).entrySet()) {
            String filler = entry.getKey();
            Slice input = utf8Slice(filler + "ax0");
            for (LiteralSetSpanMatcher set : List.of(literalSet, mixedLiteralSet)) {
                LiteralSetSpanMatcher.SkipDiagnostics diagnostics = new LiteralSetSpanMatcher.SkipDiagnostics();
                assertThat(set.findSpanForDiagnostics(input, 0, input.length(), diagnostics)).isEqualTo(((long) filler.length() << 32) | (filler.length() + 3));
                assertThat(diagnostics.rootSkipCount()).as("filler %s", filler).isEqualTo(skips ? entry.getValue() : 0);
            }
        }

        // Once the short-skip limit ends skipping, the plain trie walk takes the rest of the
        // search from the leading byte the last scan found: a literal starting at that byte
        // matches, and a miss run after it, which would start a long skip, is walked byte by
        // byte. Each span is the plain trie walk's from the start.
        String tail = "z".repeat(100);
        for (String text : List.of(shortSkips + "ax0" + tail, shortSkips + "gq" + tail + "ax0" + tail)) {
            Slice input = utf8Slice(text);
            long expected = ((long) text.indexOf("ax0") << 32) | (text.indexOf("ax0") + 3);
            for (LiteralSetSpanMatcher set : List.of(literalSet, mixedLiteralSet)) {
                LiteralSetSpanMatcher.SkipDiagnostics diagnostics = new LiteralSetSpanMatcher.SkipDiagnostics();
                assertThat(set.findSpanForDiagnostics(input, 0, input.length(), diagnostics)).as(text).isEqualTo(expected);
                assertThat(set.withoutRootSkipping().findSpan(input, 0, input.length())).as(text).isEqualTo(expected);
                assertThat(diagnostics.rootSkipCount()).as(text).isEqualTo(skips ? VectorByteSetScanner.SHORT_SCAN_LIMIT : 0);
            }
        }

        // A search picks its walk by its own length, not the input's: a region one byte short of
        // CompiledPattern.MINIMUM_LONG_SEARCH_BYTES in a long input walks every root byte and
        // finds the literal ending at the region end, while one byte more skips its miss run.
        String regionText = tail + "gq. gq. ax0" + tail;
        Slice regionInput = utf8Slice(regionText);
        int regionEnd = regionText.indexOf("ax0") + 3;
        int regionStart = regionEnd - (CompiledPattern.MINIMUM_LONG_SEARCH_BYTES - 1);
        for (LiteralSetSpanMatcher set : List.of(literalSet, mixedLiteralSet)) {
            for (int start : List.of(regionStart, regionStart - 1)) {
                LiteralSetSpanMatcher.SkipDiagnostics diagnostics = new LiteralSetSpanMatcher.SkipDiagnostics();
                assertThat(set.findSpanForDiagnostics(regionInput, start, regionEnd, diagnostics)).as("start %s", start).isEqualTo(((long) (regionEnd - 3) << 32) | regionEnd);
                assertThat(diagnostics.rootSkipCount()).as("start %s", start).isEqualTo(skips && start < regionStart ? 1 : 0);
            }
        }

        // Spans from every start agree with the general engine on long inputs whose filler leads
        // no literal, with a match early, late, through the overlap, and none, and on dense text
        // whose bytes alternate between leading and not, or whose misses come in short runs.
        String filler = "z".repeat(1_000);
        Map<String, Re2> references = Map.of(
                dictionary, Re2.compile(utf8Slice(dictionary)),
                mixedDictionary, Re2.compile(utf8Slice(mixedDictionary)));
        for (String text : List.of(
                "ax0" + filler,
                filler + "gx62",
                filler + "abcdx" + filler + "abcde" + filler,
                "ab" + filler + "bc" + filler + "hx" + filler,
                "az".repeat(300) + "bx9" + "azz".repeat(300) + "abcdx",
                "gq. ".repeat(300) + filler + "ax0")) {
            byte[] backing = ("!" + text + "!").getBytes(UTF_8);
            Slice input = wrappedBuffer(backing, 1, text.length());
            for (String expression : List.of(dictionary, mixedDictionary)) {
                LiteralSetSpanMatcher set = literalSet(expression);
                RegexpMatcher generalMatcher = references.get(expression).genericGroupZeroMatcherForDiagnostics(input);
                for (int start = 0; start <= input.length(); start++) {
                    long expected = generalMatcher.find(start) ? ((long) generalMatcher.start() << 32) | (generalMatcher.end() & 0xFFFF_FFFFL) : Dfa.SEARCH_NO_MATCH;
                    assertThat(set.findSpan(input, start, input.length())).as("%s start %s", expression, start).isEqualTo(expected);
                }
            }
        }

        // The skip and the larger object holding it are a retained charge of their own: the
        // range scan only the object's growth, and the nibble tables their array too. Under a
        // budget one byte short of the matcher with the skip, the build leaves it out, and the set
        // walks every root byte.
        if (skips) {
            int objectGrowth = instanceSize(LiteralSetSpanMatcher.RootSkippingMatcher.class) - instanceSize(LiteralSetSpanMatcher.class);
            byte[] mixedLeadingBytes = new byte[256];
            "abcegikmo".chars().forEach(value -> mixedLeadingBytes[value] = 1);
            for (String expression : List.of(dictionary, mixedDictionary)) {
                long withSkip = literalSet(expression).estimatedRetainedSize();
                LiteralSetSpanMatcher withoutSkip = optionalLiteralSet(expression, withSkip - 1);
                assertThat(withoutSkip).isNotNull();
                assertThat(withoutSkip.rootByteScanSetForDiagnostics()).isZero();
                assertThat(withoutSkip.skipsRootBytes(64)).isFalse();
                long tables = expression.equals(mixedDictionary) ? sizeOfByteArray(VectorByteSetScanner.buildNibbleTables(mixedLeadingBytes).length) : 0;
                assertThat(withoutSkip.estimatedRetainedSize()).as(expression).isEqualTo(withSkip - objectGrowth - tables);
            }
        }
    }

    @Test
    public void testPublicRouteAndMemoryAccounting()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL"));
        LiteralAlternationSpanMatcher matcher = regexp.literalAlternationSpanMatcher();
        assertThat(matcher).isNotNull();
        assertThat(regexp.re2()).isNull();
        long maxMemory = TrinoRegexp.Options.defaults().maxMemory();
        long forwardMemory = maxMemory - Math.max(1, maxMemory / 3);
        assertThat(matcher.estimatedRetainedSize()).isLessThan(forwardMemory);
        // The 14 literal bytes size the trie's arrays, and the filter, built only with the Vector
        // API, adds its own tables.
        StartLiteralFilter filter = matcher.startLiteralFilterForDiagnostics();
        assertThat(filter != null).isEqualTo(VectorSupport.isAvailable());
        long trieSize = instanceSize(ExactMatcher.class) +
                sizeOfShortArray(256) +
                2 * sizeOfShortArray(15) +
                2 * sizeOfShortArray(14) +
                sizeOfByteArray(14);
        // The literals grouped by leading byte for the stop compare, which exist only with the
        // filter: E, W and F lead one literal each.
        long stopLiteralsSize = filter == null ? 0 : sizeOfObjectArray(256) + 3 * sizeOfObjectArray(1) + 2 * sizeOfByteArray(5) + sizeOfByteArray(4);
        assertThat(matcher.estimatedRetainedSize()).isEqualTo(trieSize + stopLiteralsSize + (filter == null ? 0 : filter.estimatedRetainedSize()));
        // A matcher of folded literals, built only with the Vector API, has no trie. Its literals
        // and their case masks are each listed under both cases of their first letter, and each
        // listing is counted.
        LiteralAlternationSpanMatcher folded = TrinoRegexp.compile(utf8Slice("[Xx]ylophone|[Zz]ephyr|[Qq]uartz")).literalAlternationSpanMatcher();
        assertThat(folded != null).isEqualTo(VectorSupport.isAvailable());
        if (folded != null) {
            long byLeadingByteSize = sizeOfObjectArray(256) + 6 * sizeOfObjectArray(1) + 2 * (sizeOfByteArray(9) + sizeOfByteArray(6) + sizeOfByteArray(6));
            long foldedSize = instanceSize(FoldedMatcher.class) +
                    2 * byLeadingByteSize +
                    folded.startLiteralFilterForDiagnostics().estimatedRetainedSize();
            assertThat(folded.estimatedRetainedSize()).isEqualTo(foldedSize);
        }

        assertThat(TrinoRegexp.compile(utf8Slice("foo|(bar)")).literalAlternationSpanMatcher()).isNull();
        assertThat(TrinoRegexp.compile(utf8Slice("(?i:foo)|bar")).literalAlternationSpanMatcher()).isNull();
        assertThat(TrinoRegexp.compile(utf8Slice("foo|")).literalAlternationSpanMatcher()).isNull();
        assertThat(TrinoRegexp.compile(utf8Slice("a".repeat(64) + "b|x")).literalAlternationSpanMatcher()).isNull();

        // The trie's extraction builds many times the trie from single-byte pieces, so it stops
        // only at a literal longer than the trie takes, and such a trie compiles under any
        // budget its arrays fit. Each budget holds the trie, which needs 1,919 and 66,743 bytes,
        // but is below 2,030 and 127,646, the least an 8 times bound on the extraction admits.
        TrinoRegexp flaggedRun = TrinoRegexp.compile(utf8Slice("(?s:a)(?-s:a)".repeat(32) + "|b"), TrinoRegexp.Options.defaults().setMaxMemory(1_920));
        assertThat(flaggedRun.literalAlternationSpanMatcher()).isNotNull();
        TrinoRegexpMatcher flaggedRunMatcher = flaggedRun.matcher(utf8Slice("a".repeat(64) + "b"));
        assertThat(flaggedRunMatcher.find()).isTrue();
        assertThat(flaggedRunMatcher.start() + "-" + flaggedRunMatcher.end()).isEqualTo("0-64");
        String flaggedChains = IntStream.range(0, 64)
                .mapToObj(chain -> ("(?s:" + (char) ('a' + chain % 26) + ")(?-s:" + (char) ('A' + chain / 26) + ")").repeat(32))
                .collect(Collectors.joining("|"));
        assertThat(TrinoRegexp.compile(utf8Slice(flaggedChains), TrinoRegexp.Options.defaults().setMaxMemory(67_584)).literalAlternationSpanMatcher()).isNotNull();

        // With the Vector API, the filter's nibble tables and the stop compare's literals make the
        // matcher retain more than the general engine needs. The budget is half the size without
        // the stop literals, so that it stays too small for both.
        assertThatThrownBy(() -> TrinoRegexp.compile(
                utf8Slice("ERROR|WARN|FATAL"),
                TrinoRegexp.Options.defaults().setMaxMemory((matcher.estimatedRetainedSize() - stopLiteralsSize) / 2)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);

        // A shared prefix expands to megabytes of literals but few states, and an automaton over
        // a small budget is not built, so the compile reports the program's memory limit.
        String sharedPrefix = "a".repeat(60_000) + IntStream.range(0, 65).mapToObj(index -> "pad" + index + "z").collect(Collectors.joining("|", "(?:", ")"));
        assertThat(optionalLiteralSet(sharedPrefix)).isNotNull();
        assertThat(optionalLiteralSet(sharedPrefix, 1024)).isNull();
        assertThatThrownBy(() -> TrinoRegexp.compile(utf8Slice(sharedPrefix), TrinoRegexp.Options.defaults().setMaxMemory(1024)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);

        // A large alternation that fits the forward budget takes Trino's own literal-set route
        // and compiles no program, even under a small budget.
        String dictionary = words(65);
        TrinoRegexp setRoute = TrinoRegexp.compile(utf8Slice(dictionary), TrinoRegexp.Options.defaults().setMaxMemory(4096));
        assertThat(setRoute.usesLiteralSetMatcherForDiagnostics()).isTrue();
        assertThat(setRoute.re2()).isNull();
        // One that does not fit, even without the tables that only speed up its search, cannot
        // compile a program either, which retains more than the set, so Trino never takes the
        // set's literal-alternation shape.
        long setSize = literalSet(dictionary).withoutSearchTables().estimatedRetainedSize();
        assertThatThrownBy(() -> TrinoRegexp.compile(utf8Slice(dictionary), TrinoRegexp.Options.defaults().setMaxMemory(setSize)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);

        // A set without the root's skip, which one leading byte is too few for and eight only
        // fit with more memory, and without dense rows or a skip table retains only its five trie
        // arrays and an object holding them, so Trino takes the set under the smallest budget
        // whose forward share holds that.
        for (String largeDictionary : List.of(words(1024), words(4096), leadingByteWords("abcdefgh", 63) + "|abcde|bcd", leadingByteWords("acegikmo", 1024), leadingByteWords("abcdefgh", 4096))) {
            LiteralSetSpanMatcher literalSet = literalSet(largeDictionary).withoutSearchTables();
            if (literalSet.rootByteScanSetForDiagnostics() != 0) {
                literalSet = optionalLiteralSet(largeDictionary, literalSet.estimatedRetainedSize() - 1);
            }
            assertThat(literalSet.rootByteScanSetForDiagnostics()).isZero();
            assertThat(literalSet.denseDepthForDiagnostics()).isZero();
            assertThat(literalSet.skipsAheadForDiagnostics()).isFalse();
            long tablelessSize = literalSet.estimatedRetainedSize() - instanceSize(literalSet.getClass()) + instanceSize(LiteralSetFields.class);
            long edgeMemory = tablelessSize;
            while (edgeMemory - Math.max(1, edgeMemory / 3) < tablelessSize) {
                edgeMemory++;
            }
            TrinoRegexp edgeRoute = TrinoRegexp.compile(utf8Slice(largeDictionary), TrinoRegexp.Options.defaults().setMaxMemory(edgeMemory));
            assertThat(edgeRoute.usesLiteralSetMatcherForDiagnostics()).isTrue();
        }

        // The root's skip, the dense rows and the skip table never cost a forward DFA. Under the
        // least budget at which a forward DFA initializes beside the set without them, it still
        // does, and a set of long literals has dropped its skip table there. The longest-match
        // compile charges no set, so its program's DFA memory, with its required bytes' charge
        // added back, less the set without its skip or tables, is what the DFA has beside that
        // set.
        String fiveLeadingByteDictionary = IntStream.range(0, 70)
                .mapToObj(index -> "pqrst".charAt(index % 5) + Integer.toString(index, 3) + (index % 2 == 0 ? "q" : ""))
                .collect(Collectors.joining("|"));
        String longFiveLeadingByteDictionary = IntStream.range(0, 70)
                .mapToObj(index -> "pqrst".charAt(index % 5) + Integer.toString(index, 3) + "longsuffix")
                .collect(Collectors.joining("|"));
        assertThat(literalSet(longFiveLeadingByteDictionary).skipsAheadForDiagnostics()).isTrue();
        for (String budgetDictionary : List.of(fiveLeadingByteDictionary, longFiveLeadingByteDictionary)) {
            long tablelessSetSize = literalSet(budgetDictionary).withoutSearchTables().withoutRootSkipping().estimatedRetainedSize();
            Prog program = Re2.compile(utf8Slice(budgetDictionary)).forwardProgramForDiagnostics();
            for (Dfa.DfaInstance.Kind kind : List.of(Dfa.DfaInstance.Kind.FIRST_MATCH, Dfa.DfaInstance.Kind.LONGEST_MATCH)) {
                // A forward DFA's budget is half the program's DFA memory.
                long dfaMemory = 2 * leastValue(1, budget -> new Dfa.DfaInstance(program, kind, budget).ok());
                long edgeMemory = leastValue(dfaMemory, budget -> {
                    Re2 longest = Re2.compile(utf8Slice(budgetDictionary), Re2.Options.defaults().setMaxMemory(budget).setLongestMatch(true));
                    return longest.forwardProgramForDiagnostics().dfaMemory() + longest.requiredBytesRetainedSizeForDiagnostics() - tablelessSetSize >= dfaMemory;
                });
                for (long budget : List.of(edgeMemory - 1, edgeMemory)) {
                    Re2 pattern = Re2.compile(utf8Slice(budgetDictionary), Re2.Options.defaults().setMaxMemory(budget));
                    assertThat(pattern.literalSetSpanMatcherForDiagnostics()).as("%s %s at %s", budgetDictionary, kind, budget).isNotNull();
                    assertThat(pattern.forwardProgramForDiagnostics().getCachedDfa(kind) != null).as("%s %s at %s", budgetDictionary, kind, budget).isEqualTo(budget == edgeMemory);
                    if (budget == edgeMemory) {
                        assertThat(pattern.literalSetSpanMatcherForDiagnostics().skipsAheadForDiagnostics()).as("%s %s at %s", budgetDictionary, kind, budget).isFalse();
                    }
                }
            }
        }
    }

    @Test
    public void testConcurrentReuse()
            throws Exception
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL"));
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int task = 0; task < 32; task++) {
                int index = task;
                results.add(executor.submit(() -> regexp.contains(utf8Slice(index % 2 == 0 ? "prefix WARN suffix" : "ordinary text"))));
            }
            for (int task = 0; task < results.size(); task++) {
                assertThat(results.get(task).get()).isEqualTo(task % 2 == 0);
            }
        }
    }

    @Test
    public void testEverydayWorkloadInventory()
    {
        Set<String> eligibleWorkloads = TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds().stream()
                .filter(workload -> optionalScanner(
                        TestingEverydayTrinoRegexpBenchmarkInputs.create(workload).pattern().toStringUtf8()) != null)
                .collect(Collectors.toSet());
        assertThat(eligibleWorkloads).containsExactly("logLevel");
    }

    @Test
    public void testRepresentativeRebarInventory()
    {
        String english = "Sherlock Holmes|John Watson|Irene Adler|Inspector Lestrade|Professor Moriarty";
        String russian = "Шерлок Холмс|Джон Уотсон|Ирен Адлер|инспектор Лестрейд|профессор Мориарти";
        String chinese = "夏洛克·福尔摩斯|约翰华生|阿德勒|雷斯垂德|莫里亚蒂教授";

        assertThat(optionalScanner(english)).isNotNull();
        assertThat(optionalScanner(russian)).isNotNull();
        assertThat(optionalScanner(chinese)).isNotNull();
        assertThat(optionalScanner("(?i:" + english + ")")).isNull();
        assertThat(optionalScanner("(?i:" + russian + ")")).isNull();

        // Long text skips to the start literal filter's candidates where the Vector API is
        // available; shorter inputs keep the root-table loop.
        for (String pattern : List.of(english, russian, chinese, "Tom|Sawyer|Huckleberry|Finn")) {
            LiteralAlternationSpanMatcher scanner = scanner(pattern);
            assertThat(scanner.usesStartLiteralFilter(63)).as(pattern).isFalse();
            assertThat(scanner.usesStartLiteralFilter(64)).as(pattern).isEqualTo(VectorSupport.isAvailable());
        }
    }

    @Test
    public void testFoldedLiteralRoutes()
    {
        // Rust's bytes mode without Unicode folds only ASCII letters, so the case-insensitive
        // rebar names are folded literals, which the matcher compares at its filter stops, and
        // the DFA is never built. Without the Vector API there is no filter, and the general
        // engine keeps them.
        Random random = new Random(7);
        CompiledPattern.Shape folded = VectorSupport.isAvailable() ? CompiledPattern.Shape.LITERAL_ALTERNATION : CompiledPattern.Shape.ENGINE;
        for (String names : List.of("Sherlock Holmes|John Watson|Irene Adler|Inspector Lestrade|Professor Moriarty", "Tom|Sawyer|Huckleberry|Finn")) {
            CompiledPattern pattern = RustRegexp.compile(utf8Slice(names), RustRegexp.Options.bytes().setUnicode(false).setCaseInsensitive(true));
            assertThat(shape(pattern)).as(names).isEqualTo(folded);
            // Folded and exact literals each take their own matcher class, so the exact trie walk
            // compiles without the folded compares.
            CompiledPattern exact = RustRegexp.compile(utf8Slice(names), RustRegexp.Options.bytes().setUnicode(false));
            assertThat(exact.literalAlternationSpanMatcherForDiagnostics()).as(names).isInstanceOf(ExactMatcher.class);
            if (!VectorSupport.isAvailable()) {
                continue;
            }
            LiteralAlternationSpanMatcher matcher = pattern.literalAlternationSpanMatcherForDiagnostics();
            assertThat(matcher).as(names).isInstanceOf(FoldedMatcher.class);
            Slice input = wrappedBuffer(literalText(random, foldedVariants(names), Dfa.MIN_BYTE_SCAN_PRODUCTIVITY_CHECK_BYTES));
            long count = pattern.count(input);
            assertThat(matcher.filterStopCountForDiagnostics()).as(names).isPositive();
            assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).as(names).isNull();
            assertThat(count).as(names).isEqualTo(generalEngineCount(pattern, input));
        }

        // Each language folds the letters its rules give exactly two ASCII cases. Unicode folding
        // adds non-ASCII cases for s and k in RE2 and Rust, and in Java with (?iu), so these
        // expressions keep the general engine, as do non-ASCII literals and literals too short for
        // a selective filter.
        String rare = "Xylophone|Zephyr|Quartz";
        String names = "Holmes|Watson|Lestrade";
        String kelvin = "Kelvin|Kraken|Kayak";
        String russian = "Шерлок Холмс|Джон Уотсон|Ирен Адлер|инспектор Лестрейд|профессор Мориарти";
        // A filter stop compares the literals that begin with its byte, so those under one leading
        // byte may total 64 bytes; many long literals that begin with q keep the general engine.
        // Their letters differ in the middle, so the parser's factoring leaves no class of them.
        String atBound = "qzxj".repeat(8) + "|" + "qzxj".repeat(7) + "quiz";
        String overBound = atBound + "z";
        String longLiterals = IntStream.range(0, 49)
                .mapToObj(index -> "qzxj".repeat(7) + (char) ('a' + index / 26) + (char) ('a' + index % 26) + "qzxj".repeat(7))
                .collect(Collectors.joining("|"));
        CompiledPattern.Shape engine = CompiledPattern.Shape.ENGINE;

        record Route(String language, String expression, CompiledPattern.Shape shape) {}

        List<Route> routes = List.of(
                new Route("RE2", rare, folded),
                new Route("RE2", names, engine),
                new Route("RE2 Latin-1", names, folded),
                new Route("Java", names, folded),
                new Route("Java", kelvin, folded),
                new Route("Java (?u)", rare, folded),
                new Route("Java (?u)", kelvin, engine),
                new Route("Rust", rare, folded),
                new Route("Rust", names, engine),
                new Route("Rust bytes (?-u)", names, folded),
                new Route("Rust", russian, engine),
                new Route("Rust bytes (?-u)", "ab|cd", engine),
                new Route("Rust bytes (?-u)", atBound, folded),
                new Route("Rust bytes (?-u)", overBound, engine),
                new Route("Rust bytes (?-u)", longLiterals, engine));
        for (Route route : routes) {
            assertThat(shape(foldedPattern(route.language(), route.expression())))
                    .as("%s (?i)%s", route.language(), route.expression())
                    .isEqualTo(route.shape());
        }
        // Trino keeps no engine to hand a dense stretch back to, so the bound holds there too.
        assertThat(TrinoRegexp.compile(utf8Slice(twoCaseClasses(atBound))).literalAlternationSpanMatcher() != null).isEqualTo(VectorSupport.isAvailable());
        assertThat(TrinoRegexp.compile(utf8Slice(twoCaseClasses(overBound))).literalAlternationSpanMatcher()).isNull();
        assertThat(TrinoRegexp.compile(utf8Slice(twoCaseClasses(longLiterals))).literalAlternationSpanMatcher()).isNull();
    }

    @Test
    public void testFoldedLiteralAgreement()
    {
        // Patterns of folded literals, in every language, agree with the general engine on text of
        // the literals in lower, upper, and alternating case, their prefixes, malformed UTF-8 next
        // to them, the long s and Kelvin sign in place of s and k, and a literal at the end.
        Random random = new Random(11);
        List<String> expressions = List.of(
                "Xylophone|Zephyr|Quartz",
                // Of overlapping literals the earliest start wins, as zephyr does in zephyrax.
                "Zephyr|Hyrax|Quartz|Artzy",
                "Holmes|Watson|Kelvin|Holm",
                // Exact and folded literals in one alternation, and a class of a letter's two cases.
                "(?-i:JUMBO)|jump|(?-i:[Jj]unk)");
        for (String expression : expressions) {
            String[] variants = foldedVariants(expression.replace("(?-i:", "").replace(")", "").replace("[Jj]", "j"));
            List<Slice> inputs = new ArrayList<>();
            inputs.add(utf8Slice(""));
            inputs.add(utf8Slice(String.join(" ", variants)));
            inputs.add(utf8Slice("zephyrax QUARTZY hyRAX ArtZy holmeS"));
            for (int trial = 0; trial < 3; trial++) {
                inputs.add(wrappedBuffer(literalText(random, variants, 2_048 + random.nextInt(2_048))));
            }
            for (Map.Entry<String, CompiledPattern> entry : foldedPatterns(expression).entrySet()) {
                String label = entry.getKey() + " (?i)" + expression;
                CompiledPattern pattern = entry.getValue();
                LiteralAlternationSpanMatcher matcher = pattern.literalAlternationSpanMatcherForDiagnostics();
                // With Unicode, RE2 and Rust give s a third case, and Java's (?u) gives k one.
                boolean thirdCase = expression.equals("Holmes|Watson|Kelvin|Holm") && Set.of("RE2", "Rust", "Java (?u)").contains(entry.getKey());
                assertThat(matcher != null).as(label).isEqualTo(VectorSupport.isAvailable() && !thirdCase);
                for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                    Slice input = inputs.get(inputIndex);
                    assertThat(pattern.count(input)).as("%s input %s", label, inputIndex).isEqualTo(generalEngineCount(pattern, input));
                    if (matcher == null) {
                        continue;
                    }
                    RegexpMatcher general = pattern.genericGroupZeroMatcherForDiagnostics(input);
                    RegexpMatcher regionGeneral = pattern.genericGroupZeroMatcherForDiagnostics(input);
                    for (int start = 0; start <= input.length(); start++) {
                        long expected = general.find(start) ? ((long) general.start() << 32) | (general.end() & 0xFFFF_FFFFL) : Dfa.SEARCH_NO_MATCH;
                        assertThat(matcher.findSpan(input, start)).as("%s input %s start %s", label, inputIndex, start).isEqualTo(expected);
                        // A region that ends halfway to the input end cuts the matches that cross it.
                        int end = start + (input.length() - start) / 2;
                        expected = regionGeneral.reset(input, start, end).find() ? ((long) regionGeneral.start() << 32) | (regionGeneral.end() & 0xFFFF_FFFFL) : Dfa.SEARCH_NO_MATCH;
                        assertThat(matcher.findSpan(input, start, end)).as("%s input %s region %s-%s", label, inputIndex, start, end).isEqualTo(expected);
                    }
                }
            }
        }

        // Trino keeps no program for a literal alternation, so its classes of a letter's two cases
        // are served by the folded literals alone.
        String expression = "[Xx]ylophone|[Zz]ephyr|[Qq]uartz";
        TrinoRegexp trino = TrinoRegexp.compile(utf8Slice(expression));
        assertThat(trino.literalAlternationSpanMatcher() != null).isEqualTo(VectorSupport.isAvailable());
        Re2 reference = Re2.compile(utf8Slice(expression));
        for (int trial = 0; trial < 3; trial++) {
            Slice input = wrappedBuffer(literalText(random, foldedVariants("Xylophone|Zephyr|Quartz"), 2_048));
            assertThat(trino.count(input)).as("trial %s", trial).isEqualTo(generalEngineCount(reference, input));
        }
    }

    @Test
    public void testLongInputAgreement()
    {
        Random random = new Random(42);
        List<String> patterns = List.of(
                "Sherlock Holmes|John Watson|Irene Adler|Inspector Lestrade|Professor Moriarty",
                "Шерлок Холмс|Джон Уотсон|Ирен Адлер|инспектор Лестрейд|профессор Мориарти",
                "夏洛克·福尔摩斯|约翰华生|阿德勒|雷斯垂德|莫里亚蒂教授",
                "Tom|Sawyer|Huckleberry|Finn",
                // At a filter stop where several literals match, the first in pattern order wins,
                // whether it is the longer or the shorter one.
                "Holmes|Watson|Holm|Watsonian",
                "ERROR|WARN|FATAL",
                // In abcde, the near miss of abcx hides the start of bcde one byte later.
                "abcx|bcde");
        for (String expression : patterns) {
            LiteralAlternationSpanMatcher scanner = scanner(expression);
            Re2 pattern = Re2.compile(utf8Slice(expression));
            TrinoRegexp trinoPattern = TrinoRegexp.compile(utf8Slice(expression));
            assertThat(shape(pattern)).as(expression).isEqualTo(CompiledPattern.Shape.LITERAL_ALTERNATION);
            assertThat(trinoPattern.literalAlternationSpanMatcher()).as(expression).isNotNull();
            for (int trial = 0; trial < 4; trial++) {
                // A literal ends the text, so a match lands in its last bytes.
                byte[] body = literalText(random, expression.split("\\|"), 2_048 + random.nextInt(4_096));
                int backingOffset = random.nextInt(64);
                byte[] backing = new byte[backingOffset + body.length + random.nextInt(64)];
                random.nextBytes(backing);
                System.arraycopy(body, 0, backing, backingOffset, body.length);
                Slice input = wrappedBuffer(backing, backingOffset, body.length);

                RegexpMatcher matcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
                for (int start = 0; start <= input.length(); start++) {
                    long expected = matcher.find(start)
                            ? ((long) matcher.start() << 32) | (matcher.end() & 0xFFFF_FFFFL)
                            : Dfa.SEARCH_NO_MATCH;
                    assertThat(scanner.findSpan(input, start)).as("pattern %s trial %s start %s", expression, trial, start).isEqualTo(expected);
                }
                long expectedCount = generalEngineCount(pattern, input);
                assertThat(scanner.count(input)).as("pattern %s trial %s", expression, trial).isEqualTo(expectedCount);
                assertThat(pattern.count(input)).as("pattern %s trial %s", expression, trial).isEqualTo(expectedCount);
                assertThat(trinoPattern.count(input)).as("pattern %s trial %s", expression, trial).isEqualTo(expectedCount);
            }
        }

        // Sparse log lines, then near misses with a literal at random places. The log routes hold
        // the filter, and the dense near misses send a later route to the root table. Spans from
        // random starts, every match in turn, and the counts agree with the general engine.
        StringBuilder mixed = new StringBuilder(logLines(2 * RECHECK_BYTES));
        String[] literals = {"ERROR", "WARN", "FATAL"};
        while (mixed.length() < 9 * RECHECK_BYTES / 2) {
            mixed.append(random.nextInt(64) == 0 ? literals[random.nextInt(literals.length)] : "ERxO WAxN FAxA ");
        }
        Slice input = utf8Slice(mixed.toString());
        LiteralAlternationSpanMatcher scanner = scanner("ERROR|WARN|FATAL");
        Re2 pattern = Re2.compile(utf8Slice("ERROR|WARN|FATAL"));
        RegexpMatcher matcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
        for (int trial = 0; trial < 4_096; trial++) {
            int start = random.nextInt(input.length() + 1);
            long expected = matcher.find(start)
                    ? ((long) matcher.start() << 32) | (matcher.end() & 0xFFFF_FFFFL)
                    : Dfa.SEARCH_NO_MATCH;
            assertThat(scanner.findSpan(input, start)).as("start %s", start).isEqualTo(expected);
        }
        // A scanner the random starts did not use counts only the cursor walk's filter stops, and
        // the walk and the count each read the root table in their own diagnostics.
        LiteralAlternationSpanMatcher walkScanner = scanner("ERROR|WARN|FATAL");
        LiteralAlternationSpanMatcher.RouteDiagnostics walkDiagnostics = new LiteralAlternationSpanMatcher.RouteDiagnostics();
        LiteralAlternationSpanMatcher.Cursor cursor = new LiteralAlternationSpanMatcher.Cursor();
        matcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
        long span = walkScanner.findSpanForDiagnostics(input, 0, cursor, walkDiagnostics);
        long expectedCount = 0;
        while (matcher.find()) {
            assertThat(span).isEqualTo(((long) matcher.start() << 32) | (matcher.end() & 0xFFFF_FFFFL));
            expectedCount++;
            span = walkScanner.findSpanForDiagnostics(input, (int) span, cursor, walkDiagnostics);
        }
        assertThat(span).isEqualTo(Dfa.SEARCH_NO_MATCH);
        assertThat(walkScanner.filterStopCountForDiagnostics() > 0).isEqualTo(VectorSupport.isAvailable());
        assertThat(walkDiagnostics.rootTablePositionCount()).isGreaterThan(RECHECK_BYTES / 2);
        LiteralAlternationSpanMatcher.RouteDiagnostics countDiagnostics = new LiteralAlternationSpanMatcher.RouteDiagnostics();
        assertThat(walkScanner.countForDiagnostics(input, countDiagnostics)).isEqualTo(expectedCount);
        assertThat(countDiagnostics.rootTablePositionCount()).isGreaterThan(RECHECK_BYTES / 2);
        assertThat(pattern.count(input)).isEqualTo(expectedCount);
        assertThat(TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL")).count(input)).isEqualTo(expectedCount);
    }

    @Test
    public void testDenseNearMissesReadTheRootTable()
    {
        // The filter checks the first, second, and fourth bytes of ERROR, WARN, and FATAL, so it
        // passes ERxO, WAxN, and FAxA, and the trie rejects each at its third byte. Every fifth
        // byte starts such a near miss, and nothing matches.
        int length = 4 * RECHECK_BYTES;
        byte[] nearMisses = nearMisses(length);
        Slice input = wrappedBuffer(nearMisses);
        LiteralAlternationSpanMatcher scanner = scanner("ERROR|WARN|FATAL");
        StartLiteralFilter filter = scanner.startLiteralFilterForDiagnostics();
        assertThat(filter != null).isEqualTo(VectorSupport.isAvailable());
        if (filter != null) {
            assertThat(List.of(filter.firstOffset(), filter.secondOffset(), filter.thirdOffset())).containsExactly(0, 1, 3);
            for (int position = 0; position + 4 <= length; position += 5) {
                assertThat(filter.accepts(nearMisses, position, length)).as("position %s", position).isTrue();
            }
        }

        // Each of the four 64 KiB routes samples the filter's stops once, finds them dense, and
        // reads the root table, so the vector scan makes no stop. The root table alone reads each
        // position once. Without the Vector API there is no filter to sample.
        LiteralAlternationSpanMatcher.RouteDiagnostics diagnostics = new LiteralAlternationSpanMatcher.RouteDiagnostics();
        assertThat(scanner.countForDiagnostics(input, diagnostics)).isZero();
        assertThat(scanner.filterStopCountForDiagnostics()).isZero();
        assertThat(diagnostics.rootTablePositionCount()).isEqualTo(length);
        assertThat(diagnostics.sampledPositionCount()).isEqualTo(VectorSupport.isAvailable() ? 4L * Dfa.BYTE_SCAN_SAMPLE_SIZE : 0);
        assertThat(scanner.findSpanForDiagnostics(input, 0, null, diagnostics)).isEqualTo(Dfa.SEARCH_NO_MATCH);
        assertThat(scanner.filterStopCountForDiagnostics()).isZero();
        assertThat(diagnostics.rootTablePositionCount()).isEqualTo(2L * length);
    }

    @Test
    public void testMatcherResetForgetsTheRoute()
    {
        // A Trino matcher reset onto a short input forgets the long input's root-table route.
        Slice input = wrappedBuffer("ERxO WAxN FAxA ".repeat(4 * RECHECK_BYTES / 15).getBytes(UTF_8));
        TrinoRegexpMatcher matcher = TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL")).matcher(input);
        assertThat(matcher.find()).isFalse();
        assertThat(matcher.reset(utf8Slice("xxxx")).find()).isFalse();
        assertThat(matcher.reset(utf8Slice("xxWARN")).find()).isTrue();
        assertThat(List.of(matcher.start(), matcher.end())).containsExactly(2, 6);
    }

    @Test
    public void testMatchersOwnTheirCandidateCursors()
    {
        // Every find below, the one after each input's last match included, has more than
        // CompiledPattern.MINIMUM_LONG_SEARCH_BYTES left, so a matcher hands it from the span
        // search to its own DFA candidate cursor. On near misses with an ERROR every 10,007 bytes
        // the cursor's productivity check rejects the start-byte scan and turns the cursor off for
        // the rest of the input, while on log lines of a few KiB it routes the finds. Two matchers
        // of one compiled pattern interleave their finds over the two inputs, so a cursor shared
        // between them would carry one input's verdict into the other's search; each matcher's
        // spans are the general engine's. Then the first matcher, reset onto a shorter input,
        // turns its cursor back on.
        // These near misses pass the start class filter each language's DFA builds for the
        // pattern, whose third offset reads R to T, so its productivity check still rejects the
        // scan; ERxO, WAxN, and FAxA would not pass it.
        byte[] nearMisses = nearMissText("ERRO WASN FASA ", 4 * RECHECK_BYTES).getBytes(UTF_8);
        for (int position = 5_000; position + 5 <= nearMisses.length; position += 10_007) {
            System.arraycopy("ERROR".getBytes(UTF_8), 0, nearMisses, position, 5);
        }
        Slice dense = wrappedBuffer(nearMisses);
        Slice log = utf8Slice(logLines(3_500));
        Slice shorter = utf8Slice("WARN " + " ".repeat(90) + "FATAL ERROR" + " ".repeat(80));
        for (CompiledPattern pattern : re2JavaAndRustPatterns("ERROR|WARN|FATAL").values()) {
            String expression = pattern.pattern().toStringUtf8();
            LiteralAlternationSpanMatcher scanner = pattern.literalAlternationSpanMatcherForDiagnostics();
            RegexpMatcher denseMatcher = pattern.matcher(dense, 0);
            RegexpMatcher logMatcher = pattern.matcher(log, 0);
            assertThat(denseMatcher.spanSearchForDiagnostics()).as(expression).isSameAs(scanner);
            List<String> expectedDense = findSpans(pattern.genericGroupZeroMatcherForDiagnostics(dense));
            List<String> expectedLog = findSpans(pattern.genericGroupZeroMatcherForDiagnostics(log));
            assertThat(expectedDense).as(expression).hasSizeGreaterThan(20);
            assertThat(expectedLog).as(expression).hasSizeGreaterThan(2);
            List<String> denseSpans = new ArrayList<>();
            List<String> logSpans = new ArrayList<>();
            boolean denseFound = true;
            boolean logFound = true;
            while (denseFound || logFound) {
                denseFound = denseFound && denseMatcher.find();
                if (denseFound) {
                    denseSpans.add(denseMatcher.start() + "-" + denseMatcher.end());
                }
                logFound = logFound && logMatcher.find();
                if (logFound) {
                    logSpans.add(logMatcher.start() + "-" + logMatcher.end());
                }
            }
            assertThat(denseSpans).as(expression).isEqualTo(expectedDense);
            assertThat(logSpans).as(expression).isEqualTo(expectedLog);
            assertThat(denseMatcher.candidateStartProductivityRejectionCountForDiagnostics()).as(expression).isPositive();
            assertThat(denseMatcher.candidateStartRouteCountForDiagnostics()).as(expression).isZero();
            assertThat(logMatcher.candidateStartProductivityRejectionCountForDiagnostics()).as(expression).isZero();
            assertThat(logMatcher.candidateStartRouteCountForDiagnostics()).as(expression).isPositive();

            denseMatcher.reset(dense);
            assertThat(denseMatcher.find()).as(expression).isTrue();
            assertThat(findSpans(denseMatcher.reset(shorter))).as(expression).containsExactly("0-4", "95-100", "101-106");
            assertThat(denseMatcher.candidateStartRouteCountForDiagnostics()).as(expression).isPositive();
        }
    }

    @Test
    public void testSparseLogSamplesOnce()
    {
        // Log lines with an occasional WARN or ERROR keep the filter. A count and finds that
        // carry a cursor, as a Trino matcher's do, sample once for their first route; later
        // routes whose stops stayed sparse hold the filter without sampling again.
        Slice logInput = utf8Slice(logLines(2 * RECHECK_BYTES + 1024));
        long expectedCount = generalEngineCount(Re2.compile(utf8Slice("ERROR|WARN|FATAL")), logInput);
        assertThat(expectedCount).isGreaterThan(100);
        long sampledPositions = VectorSupport.isAvailable() ? Dfa.BYTE_SCAN_SAMPLE_SIZE : 0;
        LiteralAlternationSpanMatcher logScanner = scanner("ERROR|WARN|FATAL");
        LiteralAlternationSpanMatcher.RouteDiagnostics countDiagnostics = new LiteralAlternationSpanMatcher.RouteDiagnostics();
        assertThat(logScanner.countForDiagnostics(logInput, countDiagnostics)).isEqualTo(expectedCount);
        assertThat(logScanner.filterStopCountForDiagnostics() > 0).isEqualTo(VectorSupport.isAvailable());
        assertThat(countDiagnostics.rootTablePositionCount() < 64).isEqualTo(VectorSupport.isAvailable());
        assertThat(countDiagnostics.sampledPositionCount()).isEqualTo(sampledPositions);

        LiteralAlternationSpanMatcher.RouteDiagnostics findDiagnostics = new LiteralAlternationSpanMatcher.RouteDiagnostics();
        LiteralAlternationSpanMatcher.Cursor cursor = new LiteralAlternationSpanMatcher.Cursor();
        long finds = 0;
        for (long span = logScanner.findSpanForDiagnostics(logInput, 0, cursor, findDiagnostics); span >= 0; span = logScanner.findSpanForDiagnostics(logInput, (int) span, cursor, findDiagnostics)) {
            finds++;
        }
        assertThat(finds).isEqualTo(expectedCount);
        assertThat(findDiagnostics.sampledPositionCount()).isEqualTo(sampledPositions);

        // Folded literals hold the filter and carry it in the cursor the same way. Without the
        // Vector API there is no folded matcher.
        if (VectorSupport.isAvailable()) {
            LiteralAlternationSpanMatcher folded = Re2.compile(utf8Slice("(?i)ERROR|WARN|FATAL")).literalAlternationSpanMatcherForDiagnostics();
            assertThat(folded).isInstanceOf(FoldedMatcher.class);
            LiteralAlternationSpanMatcher.RouteDiagnostics foldedCountDiagnostics = new LiteralAlternationSpanMatcher.RouteDiagnostics();
            assertThat(folded.countForDiagnostics(logInput, foldedCountDiagnostics)).isEqualTo(expectedCount);
            assertThat(foldedCountDiagnostics.sampledPositionCount()).isEqualTo(Dfa.BYTE_SCAN_SAMPLE_SIZE);
            LiteralAlternationSpanMatcher.RouteDiagnostics foldedFindDiagnostics = new LiteralAlternationSpanMatcher.RouteDiagnostics();
            LiteralAlternationSpanMatcher.Cursor foldedCursor = new LiteralAlternationSpanMatcher.Cursor();
            long foldedFinds = 0;
            for (long span = folded.findSpanForDiagnostics(logInput, 0, foldedCursor, foldedFindDiagnostics); span >= 0; span = folded.findSpanForDiagnostics(logInput, (int) span, foldedCursor, foldedFindDiagnostics)) {
                foldedFinds++;
            }
            assertThat(foldedFinds).isEqualTo(expectedCount);
            assertThat(foldedFindDiagnostics.sampledPositionCount()).isEqualTo(Dfa.BYTE_SCAN_SAMPLE_SIZE);
        }

        TrinoRegexp trinoPattern = TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL"));
        assertThat(trinoPattern.literalAlternationSpanMatcher()).isNotNull();
        TrinoRegexpMatcher matcher = trinoPattern.matcher(logInput);
        long matches = 0;
        while (matcher.find()) {
            matches++;
        }
        assertThat(matches).isEqualTo(expectedCount);
        assertThat(trinoPattern.extractAll(logInput, 0)).hasSize(toIntExact(expectedCount));
    }

    @Test
    public void testMatchAcrossTheRecheck()
    {
        // Spaces keep the filter for the first 64 KiB route, and WARN starts two bytes before its
        // end, so the filter reads past the route end to reach WARN's fourth byte.
        String text = " ".repeat(RECHECK_BYTES - 2) + "WARN" + " ".repeat(RECHECK_BYTES);
        byte[] backing = ("!" + text + "!").getBytes(UTF_8);
        TrinoRegexp trinoPattern = TrinoRegexp.compile(utf8Slice("ERROR|WARN|FATAL"));
        for (Slice input : List.of(utf8Slice(text), wrappedBuffer(backing, 1, text.length()))) {
            LiteralAlternationSpanMatcher scanner = scanner("ERROR|WARN|FATAL");
            assertSpan(scanner, input, 0, RECHECK_BYTES - 2, RECHECK_BYTES + 2);
            assertThat(scanner.filterStopCountForDiagnostics() > 0).isEqualTo(VectorSupport.isAvailable());
            assertThat(scanner.count(input)).isOne();

            TrinoRegexpMatcher matcher = trinoPattern.matcher(input);
            assertThat(matcher.find()).isTrue();
            assertThat(List.of(matcher.start(), matcher.end())).containsExactly(RECHECK_BYTES - 2, RECHECK_BYTES + 2);
            assertThat(matcher.find()).isFalse();
        }

        // Folded literals keep their own copy of the route loop, whose filter reads past the route
        // end the same way. Without the Vector API there is no folded matcher.
        if (VectorSupport.isAvailable()) {
            Slice input = utf8Slice(" ".repeat(RECHECK_BYTES - 2) + "warn" + " ".repeat(RECHECK_BYTES));
            LiteralAlternationSpanMatcher folded = Re2.compile(utf8Slice("(?i)ERROR|WARN|FATAL")).literalAlternationSpanMatcherForDiagnostics();
            assertThat(folded).isInstanceOf(FoldedMatcher.class);
            assertSpan(folded, input, 0, RECHECK_BYTES - 2, RECHECK_BYTES + 2);
            assertThat(folded.count(input)).isOne();
        }
    }

    // Whole log lines of at least `length` bytes, one in 50 at ERROR and one in 20 at WARN.
    private static String logLines(int length)
    {
        StringBuilder log = new StringBuilder();
        for (int line = 0; log.length() < length; line++) {
            String level = line % 50 == 7 ? "ERROR" : line % 20 == 3 ? "WARN" : "INFO";
            log.append("2026-11-01T12:00:00 ").append(level).append(" request served for user ").append(line).append('\n');
        }
        return log.toString();
    }

    @Test
    public void testDenseNearMissesAtTheStartRunTheDfa()
    {
        // RE2 boolean find and count hand a long input whose filter stops are dense from its
        // start to the DFA, which runs as it does without the matcher, so the matcher reads none
        // of it after its sample. A real ERROR every 10,007 bytes gives the DFA matches to find.
        byte[] bytes = nearMisses(4 * RECHECK_BYTES);
        int insertedMatches = 0;
        for (int position = 5_000; position + 5 <= bytes.length; position += 10_007) {
            System.arraycopy("ERROR".getBytes(UTF_8), 0, bytes, position, 5);
            insertedMatches++;
        }
        Slice input = wrappedBuffer(bytes);
        Re2 pattern = Re2.compile(utf8Slice("ERROR|WARN|FATAL"));
        Re2 reference = Re2.compile(utf8Slice("ERROR|WARN|FATAL"));
        LiteralAlternationSpanMatcher scanner = pattern.literalAlternationSpanMatcherForDiagnostics();
        assertThat(shape(pattern)).isEqualTo(CompiledPattern.Shape.LITERAL_ALTERNATION);
        // The reference runs the same count and find on the DFA directly, which also builds the
        // DFA counters the hand-off must reproduce.
        long dfaCount = Dfa.countMatches(reference.forwardProgramForDiagnostics(), input, Prog.MatchKind.FIRST_MATCH);
        assertThat(dfaCount).isEqualTo(insertedMatches);
        assertThat(reference.matchInto(input, CompiledPattern.Anchor.UNANCHORED, null)).isTrue();
        assertThat(pattern.countMatches(input)).isEqualTo(dfaCount);
        assertThat(pattern.find(input)).isTrue();
        assertThat(scanner.filterStopCountForDiagnostics()).isZero();
        if (VectorSupport.isAvailable()) {
            assertThat(dfaScanCounters(pattern)).isNotEmpty().isEqualTo(dfaScanCounters(reference));
        }
        // With the Vector API the count and find the pattern runs each sample the filter's stops
        // once and decline, so the matcher reads no position; without it there is no filter, and
        // the matcher keeps both.
        LiteralAlternationSpanMatcher.RouteDiagnostics diagnostics = new LiteralAlternationSpanMatcher.RouteDiagnostics();
        assertThat(scanner.countOrDeclineForDiagnostics(input, diagnostics)).isEqualTo(VectorSupport.isAvailable() ? LiteralAlternationSpanMatcher.DECLINED : dfaCount);
        assertThat(scanner.findSpanOrDeclineForDiagnostics(input, diagnostics) == LiteralAlternationSpanMatcher.DECLINED).isEqualTo(VectorSupport.isAvailable());
        assertThat(scanner.filterStopCountForDiagnostics()).isZero();
        assertThat(diagnostics.rootTablePositionCount() == 0).isEqualTo(VectorSupport.isAvailable());
        assertThat(diagnostics.sampledPositionCount()).isEqualTo(VectorSupport.isAvailable() ? 2L * Dfa.BYTE_SCAN_SAMPLE_SIZE : 0);

        // Only the check at the start hands off. Here it samples three windows of sparse log lines
        // and one of near misses, so the matcher keeps the input. The sparse log routes keep the
        // filter into the near misses, and the next check, which samples only near misses, reads
        // the root table.
        int logLines = 9_362;
        int nearMissBytes = 3 * RECHECK_BYTES - 4 * 1024;
        Slice mixed = utf8Slice("WARN disk usage above threshold on node 7\n".repeat(logLines) + nearMissText(nearMissBytes));
        List<String> dfaCounters = dfaScanCounters(pattern);
        assertThat(pattern.count(mixed)).isEqualTo(logLines);
        assertThat(dfaScanCounters(pattern)).isEqualTo(dfaCounters);
        assertThat(scanner.filterStopCountForDiagnostics() > 0).isEqualTo(VectorSupport.isAvailable());
        LiteralAlternationSpanMatcher.RouteDiagnostics mixedDiagnostics = new LiteralAlternationSpanMatcher.RouteDiagnostics();
        assertThat(scanner.countOrDeclineForDiagnostics(mixed, mixedDiagnostics)).isEqualTo(logLines);
        assertThat(mixedDiagnostics.rootTablePositionCount()).isGreaterThanOrEqualTo(RECHECK_BYTES);

        // Find follows the same rule: in the same layout without matches, the later dense checks
        // read the root table rather than hand off.
        Slice quiet = utf8Slice("INFO disk usage above threshold on node 7\n".repeat(logLines) + nearMissText(nearMissBytes));
        assertThat(pattern.find(quiet)).isFalse();
        assertThat(dfaScanCounters(pattern)).isEqualTo(dfaCounters);

        // Folded literals keep their own copy of these routes. The folded filter checks offsets 0,
        // 1 and 2, so it rejects ERxO before the literal compare. ERR#R passes the filter and fails
        // the compare. Lower-case log lines exercise case folding. Without the Vector API there is
        // no folded matcher.
        if (VectorSupport.isAvailable()) {
            String foldedExpression = "(?i)ERROR|WARN|FATAL";
            LiteralAlternationSpanMatcher folded = Re2.compile(utf8Slice(foldedExpression)).literalAlternationSpanMatcherForDiagnostics();
            assertThat(folded).isInstanceOf(FoldedMatcher.class);
            Slice foldedNearMisses = utf8Slice(nearMissText("ERR#R ", 4 * RECHECK_BYTES));
            LiteralAlternationSpanMatcher.RouteDiagnostics foldedDiagnostics = new LiteralAlternationSpanMatcher.RouteDiagnostics();
            assertThat(folded.countOrDeclineForDiagnostics(foldedNearMisses, foldedDiagnostics)).isEqualTo(LiteralAlternationSpanMatcher.DECLINED);
            assertThat(folded.findSpanOrDeclineForDiagnostics(foldedNearMisses, foldedDiagnostics)).isEqualTo(LiteralAlternationSpanMatcher.DECLINED);
            assertThat(foldedDiagnostics.rootTablePositionCount()).isZero();
            // The public count and find decline too, each on a pattern of its own, and run the DFA.
            Re2 foldedCountPattern = Re2.compile(utf8Slice(foldedExpression));
            assertThat(foldedCountPattern.count(foldedNearMisses)).isZero();
            assertThat(dfaScanCounters(foldedCountPattern)).isNotEmpty();
            Re2 foldedFindPattern = Re2.compile(utf8Slice(foldedExpression));
            assertThat(foldedFindPattern.find(foldedNearMisses)).isFalse();
            assertThat(dfaScanCounters(foldedFindPattern)).isNotEmpty();
            Slice foldedMixed = utf8Slice("warn disk usage above threshold on node 7\n".repeat(logLines) + nearMissText("ERR#R ", nearMissBytes));
            LiteralAlternationSpanMatcher.RouteDiagnostics foldedMixedDiagnostics = new LiteralAlternationSpanMatcher.RouteDiagnostics();
            assertThat(folded.countOrDeclineForDiagnostics(foldedMixed, foldedMixedDiagnostics)).isEqualTo(logLines);
            assertThat(foldedMixedDiagnostics.rootTablePositionCount()).isGreaterThanOrEqualTo(RECHECK_BYTES);
            // A find without matches reads the root table at the later dense checks, as above.
            Slice foldedQuiet = utf8Slice("info disk usage above threshold on node 7\n".repeat(logLines) + nearMissText("ERR#R ", nearMissBytes));
            LiteralAlternationSpanMatcher.RouteDiagnostics foldedQuietDiagnostics = new LiteralAlternationSpanMatcher.RouteDiagnostics();
            assertThat(folded.findSpanOrDeclineForDiagnostics(foldedQuiet, foldedQuietDiagnostics)).isEqualTo(Dfa.SEARCH_NO_MATCH);
            assertThat(foldedQuietDiagnostics.rootTablePositionCount()).isGreaterThanOrEqualTo(RECHECK_BYTES);
        }
    }

    // Near misses of ERROR|WARN|FATAL that its start literal filter passes, one every five bytes.
    private static String nearMissText(int length)
    {
        return nearMissText("ERxO WAxN FAxA ", length);
    }

    // The near misses repeated to `length` characters.
    private static String nearMissText(String nearMisses, int length)
    {
        return nearMisses.repeat(length / nearMisses.length() + 1).substring(0, length);
    }

    private static byte[] nearMisses(int length)
    {
        return nearMissText(length).getBytes(UTF_8);
    }

    // The scan counters of each DFA the pattern's forward program has built.
    private static List<String> dfaScanCounters(CompiledPattern pattern)
    {
        List<String> counters = new ArrayList<>();
        for (Dfa.DfaInstance.Kind kind : Dfa.DfaInstance.Kind.values()) {
            Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(kind);
            if (dfa != null) {
                counters.add(kind + " fallbacks " + dfa.byteScanFallbackCount() +
                        " filter scans " + dfa.startLiteralFilterScanCount() +
                        " small set scans " + dfa.smallByteSetScanCount() +
                        " mixed set scans " + dfa.mixedByteSetScanCount() +
                        " range scans " + dfa.contiguousRangeScanCount() +
                        " self-loop count scans " + dfa.countSelfLoopScanCount());
            }
        }
        return counters;
    }

    // The literals, prefixes of them, which no literal matches, single bytes and two-byte
    // runs of them that a filter may accept, spaces, and malformed UTF-8, ending with a literal.
    private static byte[] literalText(Random random, String[] literals, int length)
    {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        while (text.size() < length) {
            byte[] literal = literals[random.nextInt(literals.length)].getBytes(UTF_8);
            switch (random.nextInt(12)) {
                case 0 -> text.writeBytes(literal);
                case 1, 2, 3 -> text.write(literal, 0, 1 + random.nextInt(literal.length));
                case 4 -> text.write(' ');
                case 5 -> text.write(new byte[] {(byte) 0xFF, (byte) 0x80, (byte) 0xE5}[random.nextInt(3)]);
                default -> {
                    int index = random.nextInt(literal.length);
                    text.write(literal, index, Math.min(2, literal.length - index));
                }
            }
        }
        text.writeBytes(literals[random.nextInt(literals.length)].getBytes(UTF_8));
        return text.toByteArray();
    }

    private static String words(int count)
    {
        return IntStream.range(0, count).mapToObj(index -> "word" + index).collect(Collectors.joining("|"));
    }

    // Words whose leading bytes cycle through `leadingBytes`, such as ax0, bx1, and so on.
    private static String leadingByteWords(String leadingBytes, int count)
    {
        return IntStream.range(0, count).mapToObj(index -> leadingBytes.charAt(index % leadingBytes.length()) + "x" + index).collect(Collectors.joining("|"));
    }

    private static Re2 compileWithMaxMemory(String expression, long maxMemory)
    {
        return Re2.compile(utf8Slice(expression), Re2.Options.defaults().setMaxMemory(maxMemory));
    }

    // The least value above `low`, which fails `predicate`, from which on `predicate` holds. The
    // search fails the test once `predicate` fails at a value past MAXIMUM_LEAST_VALUE.
    private static long leastValue(long low, LongPredicate predicate)
    {
        long high = low * 2;
        while (!predicate.test(high)) {
            if (high > MAXIMUM_LEAST_VALUE) {
                throw new AssertionError("predicate still fails at " + high + ", past the largest value searched");
            }
            low = high;
            high *= 2;
        }
        while (high - low > 1) {
            long middle = low + (high - low) / 2;
            if (predicate.test(middle)) {
                high = middle;
            }
            else {
                low = middle;
            }
        }
        return high;
    }

    private static String randomText(Random random, String[] tokens, int minimumTokens, int maximumTokens)
    {
        StringBuilder text = new StringBuilder();
        int tokenCount = minimumTokens + random.nextInt(maximumTokens - minimumTokens + 1);
        for (int index = 0; index < tokenCount; index++) {
            text.append(tokens[random.nextInt(tokens.length)]);
        }
        return text.toString();
    }

    // Literals, their fragments, other text, and malformed bytes, which RE2 matches as bytes.
    private static Slice randomInput(Random random, List<String> literals, String[] tokens)
    {
        DynamicSliceOutput output = new DynamicSliceOutput(256);
        while (output.size() < 120) {
            Slice literal = utf8Slice(literals.get(random.nextInt(literals.size())));
            switch (random.nextInt(5)) {
                case 0, 1 -> output.appendBytes(literal);
                case 2 -> output.appendBytes(literal.slice(0, 1 + random.nextInt(literal.length())));
                case 3 -> output.appendBytes(utf8Slice(randomText(random, tokens, 1, 3)));
                default -> output.appendByte(random.nextBoolean() ? 0xFF : 0x80);
            }
        }
        return output.slice();
    }

    // A routed matcher's find runs a large alternation's automaton, leaving the program's DFAs
    // unbuilt, while full-Slice find and count keep the engine shape and run the forward
    // longest-match and first-match DFAs, as they would without the automaton. Its spans and
    // count are the general engine's.
    private static void assertLiteralSetFind(CompiledPattern pattern, Slice input)
    {
        String expression = pattern.pattern().toStringUtf8();
        assertThat(pattern.literalSetSpanMatcherForDiagnostics()).as(expression).isNotNull();
        assertThat(shape(pattern)).as(expression).isEqualTo(CompiledPattern.Shape.ENGINE);
        RegexpMatcher matcher = pattern.matcher(input);
        assertThat(matcher.spanSearchForDiagnostics()).isSameAs(pattern.literalSetSpanMatcherForDiagnostics());
        assertThat(matcher.find()).isTrue();
        assertNoProgramSearch(pattern, expression);
        assertThat(pattern.find(input)).as(expression).isTrue();
        assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH)).as(expression).isNotNull();
        long count = pattern.count(input);
        assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH)).as(expression).isNotNull();
        assertThat(count).as(expression).isEqualTo(generalEngineCount(pattern, input));
        assertThat(pattern.genericGroupZeroMatcherForDiagnostics(input).spanSearchForDiagnostics()).isNull();
        assertFindAgreement(pattern, input);
    }

    // The routed matcher's finds, region finds, and find(int) give the general engine's spans.
    private static void assertFindAgreement(CompiledPattern pattern, Slice input)
    {
        String expression = pattern.pattern().toStringUtf8();
        String text = input.toStringUtf8();
        assertThat(findSpans(pattern.matcher(input, 0)))
                .as("pattern %s input %s", expression, text)
                .isEqualTo(findSpans(pattern.genericGroupZeroMatcherForDiagnostics(input)));
        int regionStart = input.length() / 3;
        int regionEnd = input.length() - input.length() / 4;
        assertThat(findSpans(pattern.matcher(input, 0).reset(input, regionStart, regionEnd)))
                .as("pattern %s input %s region %s-%s", expression, text, regionStart, regionEnd)
                .isEqualTo(findSpans(pattern.genericGroupZeroMatcherForDiagnostics(input).reset(input, regionStart, regionEnd)));
        RegexpMatcher matcher = pattern.matcher(input, 0);
        RegexpMatcher generalMatcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
        for (int start = 0; start <= input.length(); start++) {
            String expected = generalMatcher.find(start) ? generalMatcher.start() + "-" + generalMatcher.end() : "none";
            assertThat(matcher.find(start) ? matcher.start() + "-" + matcher.end() : "none")
                    .as("pattern %s input %s start %s", expression, text, start)
                    .isEqualTo(expected);
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

    // Compiles `expression` as RE2, Java, Rust, and Rust byte mode, keyed by a failure label.
    // Trino's literal-alternation subtype is covered separately.
    private static Map<String, CompiledPattern> re2JavaAndRustPatterns(String expression)
    {
        return Map.of(
                "RE2 " + expression, Re2.compile(utf8Slice(expression)),
                "Java " + expression, JavaRegexp.compile(utf8Slice(expression)),
                "Rust " + expression, RustRegexp.compile(utf8Slice(expression)),
                "Rust bytes (?-u)" + expression, RustRegexp.compile(utf8Slice("(?-u)" + expression), RustRegexp.Options.bytes()));
    }

    // The case-insensitive (?i)`expression` in each language and mode that folds case.
    private static Map<String, CompiledPattern> foldedPatterns(String expression)
    {
        Map<String, CompiledPattern> patterns = new LinkedHashMap<>();
        for (String language : List.of("RE2", "RE2 Latin-1", "Java", "Java (?u)", "Rust", "Rust bytes (?-u)")) {
            patterns.put(language, foldedPattern(language, expression));
        }
        return patterns;
    }

    private static CompiledPattern foldedPattern(String language, String expression)
    {
        Slice folded = utf8Slice("(?i)" + expression);
        return switch (language) {
            case "RE2" -> Re2.compile(folded);
            case "RE2 Latin-1" -> Re2.compile(folded, Re2.Options.latin1());
            case "Java" -> JavaRegexp.compile(folded);
            case "Java (?u)" -> JavaRegexp.compile(utf8Slice("(?iu)" + expression));
            case "Rust" -> RustRegexp.compile(folded);
            case "Rust bytes (?-u)" -> RustRegexp.compile(utf8Slice("(?i-u)" + expression), RustRegexp.Options.bytes());
            default -> throw new IllegalArgumentException(language);
        };
    }

    // `expression` with each lower-case ASCII letter written as a class of its two cases.
    private static String twoCaseClasses(String expression)
    {
        StringBuilder classes = new StringBuilder();
        for (int index = 0; index < expression.length(); index++) {
            char character = expression.charAt(index);
            if (character >= 'a' && character <= 'z') {
                classes.append('[').append(Character.toUpperCase(character)).append(character).append(']');
            }
            else {
                classes.append(character);
            }
        }
        return classes.toString();
    }

    // Each literal of `expression` in lower, upper, and alternating case, and with the long s and
    // the Kelvin sign for its first s and k.
    private static String[] foldedVariants(String expression)
    {
        List<String> variants = new ArrayList<>();
        for (String literal : expression.split("\\|")) {
            String lower = literal.toLowerCase(Locale.ROOT);
            StringBuilder alternating = new StringBuilder();
            for (int index = 0; index < lower.length(); index++) {
                alternating.append(index % 2 == 0 ? Character.toUpperCase(lower.charAt(index)) : lower.charAt(index));
            }
            variants.add(lower);
            variants.add(lower.toUpperCase(Locale.ROOT));
            variants.add(alternating.toString());
            variants.add(lower.replaceFirst("s", "\u017F").replaceFirst("k", "\u212A"));
        }
        return variants.toArray(String[]::new);
    }

    private static long generalEngineCount(CompiledPattern pattern, Slice input)
    {
        RegexpMatcher matcher = pattern.genericGroupZeroMatcherForDiagnostics(input);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static LiteralAlternationSpanMatcher scanner(String pattern)
    {
        LiteralAlternationSpanMatcher scanner = optionalScanner(pattern);
        assertThat(scanner).isNotNull();
        return scanner;
    }

    private static LiteralAlternationSpanMatcher optionalScanner(String pattern)
    {
        ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL);
        return LiteralAlternationSpanMatcher.analyze(parsed.regexp(), parsed.capturingGroupCount());
    }

    private static LiteralSetSpanMatcher literalSet(String pattern)
    {
        LiteralSetSpanMatcher literalSet = optionalLiteralSet(pattern);
        assertThat(literalSet).isNotNull();
        return literalSet;
    }

    private static LiteralSetSpanMatcher optionalLiteralSet(String pattern)
    {
        return optionalLiteralSet(pattern, TrinoRegexp.Options.defaults().maxMemory());
    }

    private static LiteralSetSpanMatcher optionalLiteralSet(String pattern, long maximumRetainedBytes)
    {
        ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL);
        return LiteralSetSpanMatcher.analyze(parsed.regexp(), parsed.capturingGroupCount(), maximumRetainedBytes);
    }

    private static LiteralSetSpanMatcher optionalLiteralSet(String pattern, long maximumRetainedBytes, int maximumDenseDepth, boolean skipsAhead)
    {
        ParseResult parsed = TrinoRegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL);
        return LiteralSetSpanMatcher.analyze(parsed.regexp(), parsed.capturingGroupCount(), maximumRetainedBytes, maximumDenseDepth, skipsAhead);
    }

    // Mirrors the fields of a literal set without the root's skip: its five trie arrays, the
    // dense rows' arrays and counts, and the skip table. The arrays and the skip table are charged
    // separately, so the record's instance size is what such a set retains beyond them.
    private record LiteralSetFields(
            int[] rootStateByByte,
            byte[] classByByte,
            int classCount,
            int denseDepth,
            int denseStateCount,
            int[] denseNext,
            LiteralSetSpanMatcher.SkipTable skipTable,
            int[] firstEdgeByState,
            byte[] edgeByte,
            int[] failureByState,
            int[] matchLengthByState) {}

    private static void assertSpan(
            LiteralAlternationSpanMatcher scanner,
            Slice input,
            int searchStart,
            int expectedStart,
            int expectedEnd)
    {
        long span = scanner.findSpan(input, searchStart);
        assertThat((int) (span >>> 32)).isEqualTo(expectedStart);
        assertThat((int) span).isEqualTo(expectedEnd);
    }
}
