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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

/// The worst cases of the mechanisms that bound a search on large or adversarial input, which
/// the everyday rows cannot show: the DFA skips after repeated count bails (E5 count), after slow
/// resets of boolean line searches (E5 boolean) and of the tagged alternation's many-match
/// searches (E5 tagged), the position automaton after a count bails (E11), the candidate start
/// cursor over repeated variable-length matches (E6), and the literal set of a large dictionary
/// (A2).
/// Each workload fixes its language, options, pattern, input and operation; `budget` is the
/// pattern's max-memory option and `inputBytes` the exact size of its generated input.
///
/// Setup checks only what holds with each mechanism switched off: the pattern compiled, the
/// result is positive, and, at most [#REFERENCE_MAXIMUM_BYTES] bytes, it equals a reference
/// computed by the JDK or, for a dictionary, by a hash lookup. It prints one line, starting
/// `sweep-cliffs`, with the result and the route facts that show whether the mechanism fired,
/// which a switch-off changes. A workload that does not compile at a budget throws
/// `IllegalArgumentException`.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkSweepCliffs
{
    static final int REFERENCE_MAXIMUM_BYTES = 1 << 20;
    // Two bailing counts open the count skip window, so the third count of the setup is the
    // first that the window skips.
    private static final int COUNT_STREAK_SETUP_RUNS = 3;
    private static final int LINE_LENGTH = 200;
    private static final String BOUNDED_REPEAT = "[a-q][^u-z]{20,80}[x\\xE0-\\xFF]";
    private static final String UTF_8_BOUNDED_REPEAT = "[a-q][^u-z]{10,30}[x\\xE0-\\xFF]";
    // Rebar's `wild/date.txt` at its pinned commit, the pattern of `curated/03-date`.
    private static final String DATE_PATTERN_RESOURCE = "/io/airlift/regulator/rebar-wild-date.txt";

    enum Workload
    {
        /// RE2 count of short words with an `x` every 997 bytes, whose DFA count resets too
        /// often and bails.
        E5_COUNT_STREAK(Operation.COUNT),
        /// RE2 Latin-1 boolean find of each 200-byte line of the same text.
        E5_LINE_GREP(Operation.LINE_FIND),
        /// RE2 count of captures of a tagged alternation over random words of a and b.
        E5_TAGGED(Operation.CAPTURES),
        /// RE2 Latin-1 count of a bounded repeat over the short-words text.
        E11_LATIN1(Operation.COUNT),
        /// RE2 UTF-8 count of a shorter bounded repeat over the short-words text.
        E11_UTF8(Operation.COUNT),
        /// RE2 Latin-1 case-insensitive count-spans of rebar's date pattern over date tokens in
        /// code-like filler.
        E6_DATE(Operation.SPANS),
        /// RE2 count-spans of a dictionary alternation of 1,000, 10,000 and 50,000 words.
        A2_1K(Operation.SPANS),
        A2_10K(Operation.SPANS),
        A2_50K(Operation.SPANS);

        private final Operation operation;

        Workload(Operation operation)
        {
            this.operation = operation;
        }

        Operation operation()
        {
            return operation;
        }
    }

    enum Operation
    {
        COUNT, LINE_FIND, SPANS, CAPTURES
    }

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"E5_COUNT_STREAK", "E5_LINE_GREP", "E5_TAGGED", "E11_LATIN1", "E11_UTF8", "E6_DATE", "A2_1K", "A2_10K", "A2_50K"})
        public String workload;

        @Param({"65536", "1048576", "16777216"})
        public int inputBytes;

        @Param({"33554432", "1048576"})
        public long budget;

        private Workload selected;
        private Re2 pattern;
        private Charset charset;
        private String expression;
        private int jdkFlags;
        private List<String> dictionary;
        private Slice text;
        private Slice[] lines;
        private RegexpMatcher matcher;
        private long result;
        private long reference;
        private final List<Integer> resetsPerRun = new ArrayList<>();
        private String routeFacts;
        private boolean mechanismFired;

        @Setup(Level.Trial)
        public void setup()
        {
            selected = Workload.valueOf(workload);
            if ((selected == Workload.A2_10K || selected == Workload.A2_50K) && budget < (32L << 20)) {
                throw new IllegalArgumentException(workload + " does not compile at a " + budget + "-byte budget; it runs only with budget=33554432");
            }
            Re2.Options options = initialize();
            try {
                pattern = Re2.compile(utf8Slice(expression), options.setMaxMemory(budget));
            }
            catch (RegexpCompileMemoryLimitException e) {
                throw new IllegalArgumentException(workload + " does not compile at a " + budget + "-byte budget", e);
            }
            if (selected.operation() == Operation.SPANS) {
                matcher = pattern.matcher(EMPTY_SLICE, 0);
            }
            else if (selected.operation() == Operation.CAPTURES) {
                matcher = pattern.matcher(EMPTY_SLICE);
            }

            reference = inputBytes <= REFERENCE_MAXIMUM_BYTES ? reference() : -1;
            int runs = selected == Workload.E5_COUNT_STREAK ? COUNT_STREAK_SETUP_RUNS : 1;
            for (int run = 0; run < runs; run++) {
                int resetsBefore = resets(observedDfa());
                long runResult = execute();
                resetsPerRun.add(resets(observedDfa()) - resetsBefore);
                if (run > 0 && runResult != result) {
                    throw new IllegalStateException(workload + " run " + run + " returned " + runResult + ", not " + result);
                }
                result = runResult;
            }
            if (result <= 0) {
                throw new IllegalStateException(workload + " found no matches");
            }
            if (reference >= 0 && result != reference) {
                throw new IllegalStateException(workload + " returned " + result + ", not the reference " + reference);
            }
            recordRouteFacts();
            System.out.println(diagnostic());
        }

        /// Sets the expression, its JDK flags and charset, and the input, and returns the
        /// options the pattern compiles with, without the budget.
        private Re2.Options initialize()
        {
            jdkFlags = 0;
            return switch (selected) {
                case E5_COUNT_STREAK -> {
                    // An outer capture keeps the count on the general DFA route rather than the
                    // capture-free suffix scanner or the fixed-width matcher.
                    expression = "([a-q][^u-z]{23}x)";
                    charset = UTF_8;
                    text = Slices.wrappedBuffer(stateExplosionInput(inputBytes));
                    yield Re2.Options.defaults();
                }
                case E5_LINE_GREP -> {
                    expression = BOUNDED_REPEAT;
                    charset = ISO_8859_1;
                    text = Slices.wrappedBuffer(stateExplosionInput(inputBytes));
                    lines = lines(text);
                    yield Re2.Options.latin1();
                }
                case E5_TAGGED -> {
                    expression = "([ab]*a[ab]{10})|([ab]*b[ab]{10}c)|([ab]+)";
                    charset = UTF_8;
                    text = Slices.wrappedBuffer(abWords(inputBytes));
                    yield Re2.Options.defaults();
                }
                case E11_LATIN1 -> {
                    expression = BOUNDED_REPEAT;
                    charset = ISO_8859_1;
                    text = Slices.wrappedBuffer(stateExplosionInput(inputBytes));
                    yield Re2.Options.latin1();
                }
                case E11_UTF8 -> {
                    expression = UTF_8_BOUNDED_REPEAT;
                    charset = UTF_8;
                    text = Slices.wrappedBuffer(stateExplosionInput(inputBytes));
                    yield Re2.Options.defaults();
                }
                case E6_DATE -> {
                    expression = datePattern();
                    charset = ISO_8859_1;
                    jdkFlags = Pattern.CASE_INSENSITIVE;
                    text = Slices.wrappedBuffer(dateText(inputBytes));
                    yield Re2.Options.latin1().setCaseSensitive(false);
                }
                case A2_1K, A2_10K, A2_50K -> {
                    int words = switch (selected) {
                        case A2_1K -> 1_000;
                        case A2_10K -> 10_000;
                        default -> 50_000;
                    };
                    dictionary = dictionary(words);
                    expression = String.join("|", dictionary);
                    charset = UTF_8;
                    text = Slices.wrappedBuffer(dictionaryText(dictionary, inputBytes));
                    yield Re2.Options.defaults();
                }
            };
        }

        long execute()
        {
            return switch (selected.operation()) {
                case COUNT -> pattern.count(text);
                case LINE_FIND -> {
                    long found = 0;
                    for (Slice line : lines) {
                        found += pattern.find(line) ? 1 : 0;
                    }
                    yield found;
                }
                case SPANS -> {
                    long spans = 0;
                    matcher.reset(text);
                    while (matcher.find()) {
                        spans += matcher.end() - matcher.start();
                    }
                    yield spans;
                }
                case CAPTURES -> {
                    long groups = 0;
                    matcher.reset(text);
                    while (matcher.find()) {
                        for (int group = 0; group <= matcher.groupCount(); group++) {
                            groups += matcher.matched(group) ? 1 : 0;
                        }
                    }
                    yield groups;
                }
            };
        }

        /// The DFA whose resets show the workload's route.
        private Dfa.DfaInstance observedDfa()
        {
            return switch (selected) {
                case E5_LINE_GREP -> pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH);
                case E5_TAGGED -> {
                    Prog tagged = pattern.taggedAlternationProgramForDiagnostics();
                    yield tagged == null ? null : tagged.cachedDfaIfPresent(Dfa.DfaInstance.Kind.MANY_MATCH);
                }
                default -> pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.FIRST_MATCH);
            };
        }

        private void recordRouteFacts()
        {
            Dfa.DfaInstance dfa = observedDfa();
            switch (selected) {
                case E5_COUNT_STREAK -> {
                    // The third count skips the DFA when the window is open, so it resets nothing.
                    routeFacts = "countRunResets=" + joined(resetsPerRun);
                    mechanismFired = resetsPerRun.getFirst() > 0 && resetsPerRun.getLast() == 0;
                }
                case E5_LINE_GREP -> {
                    int skipsRemaining = dfa == null ? 0 : dfa.booleanSearchSkipsRemaining();
                    routeFacts = "resets=" + resetsPerRun.getFirst() + " booleanSkipsRemaining=" + skipsRemaining;
                    mechanismFired = skipsRemaining > 0;
                }
                case E5_TAGGED -> {
                    int skipsRemaining = dfa == null ? 0 : dfa.manyMatchSearchSkipsRemaining();
                    routeFacts = "taggedAlternation=" + pattern.usesTaggedAlternationForDiagnostics() +
                            " resets=" + resetsPerRun.getFirst() + " manyMatchSkipsRemaining=" + skipsRemaining;
                    mechanismFired = skipsRemaining > 0;
                }
                case E11_LATIN1, E11_UTF8 -> {
                    // The count's own steps, so its matcher's workspaces show which engine the finds
                    // after a bailed DFA count ran: the automaton's windows initialize BitState only,
                    // and the Pike NFA over the rest of the text initializes its own workspace.
                    long dfaCount = pattern.countMatches(text);
                    RegexpMatcher countMatcher = pattern.matcher(text, 0);
                    if (dfaCount == Dfa.COUNT_BAILED) {
                        countMatcher.markSkipsForwardDfa();
                    }
                    long count = 0;
                    while (countMatcher.find()) {
                        count++;
                    }
                    if (count != result) {
                        throw new IllegalStateException(workload + " counted " + count + " on its matcher, not " + result);
                    }
                    boolean bailed = dfaCount == Dfa.COUNT_BAILED;
                    boolean positionNfa = pattern.forwardProgramForDiagnostics().positionNfa() != null;
                    boolean pikeNfa = countMatcher.nfaWorkspaceInitializedForDiagnostics();
                    routeFacts = "resets=" + resetsPerRun.getFirst() + " countBailed=" + bailed + " positionNfa=" + positionNfa +
                            " bitState=" + countMatcher.bitStateWorkspaceInitializedForDiagnostics() + " pikeNfa=" + pikeNfa;
                    mechanismFired = bailed && positionNfa && !pikeNfa;
                }
                case E6_DATE -> {
                    long routes = matcher.candidateStartRouteCountForDiagnostics();
                    routeFacts = "resets=" + resetsPerRun.getFirst() + " candidateStartRoutes=" + routes +
                            " candidateStartFallbacks=" + matcher.candidateStartFallbackCountForDiagnostics();
                    mechanismFired = routes > 0;
                }
                case A2_1K, A2_10K, A2_50K -> {
                    // A LiteralSetRe2 is a set whose program did not fit the budget, so it keeps
                    // the set with the program's routes switched off; a LiteralAlternationRe2
                    // compiled its program beside the set.
                    boolean literalSet = pattern.literalSetSpanMatcherForDiagnostics() != null;
                    routeFacts = "resets=" + resetsPerRun.getFirst() + " literalSet=" + literalSet + " patternClass=" + pattern.getClass().getSimpleName();
                    mechanismFired = literalSet;
                }
            }
        }

        String diagnostic()
        {
            return "sweep-cliffs workload=" + workload +
                    " inputBytes=" + inputBytes +
                    " budget=" + budget +
                    " result=" + result +
                    " reference=" + (reference < 0 ? "none" : Long.toString(reference)) +
                    " " + routeFacts;
        }

        long result()
        {
            return result;
        }

        boolean hasReference()
        {
            return reference >= 0;
        }

        boolean mechanismFired()
        {
            return mechanismFired;
        }

        private long reference()
        {
            if (dictionary != null) {
                return dictionarySpans(dictionary, text);
            }
            Matcher jdk = Pattern.compile(expression, jdkFlags).matcher("");
            if (lines != null) {
                long found = 0;
                for (Slice line : lines) {
                    found += jdk.reset(line.toString(charset)).find() ? 1 : 0;
                }
                return found;
            }
            jdk.reset(text.toString(charset));
            long value = 0;
            while (jdk.find()) {
                value += switch (selected.operation()) {
                    case COUNT -> 1;
                    case SPANS -> jdk.end() - jdk.start();
                    case CAPTURES -> {
                        int groups = 0;
                        for (int group = 0; group <= jdk.groupCount(); group++) {
                            groups += jdk.start(group) >= 0 ? 1 : 0;
                        }
                        yield groups;
                    }
                    case LINE_FIND -> throw new IllegalStateException("line search without lines");
                };
            }
            return value;
        }
    }

    @Benchmark
    public long run(BenchmarkData data)
    {
        return data.execute();
    }

    private static int resets(Dfa.DfaInstance dfa)
    {
        return dfa == null ? 0 : dfa.resetCount();
    }

    private static String joined(List<Integer> values)
    {
        StringJoiner joiner = new StringJoiner(",");
        values.forEach(value -> joiner.add(Integer.toString(value)));
        return joiner.toString();
    }

    // The short-words text of the DFA cache and position automaton tests: letters a to t, digits,
    // space, line feed and punctuation, with an `x` every 997 bytes and no other byte of u to z or
    // above 0x7F.
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

    private static Slice[] lines(Slice text)
    {
        Slice[] lines = new Slice[(text.length() + LINE_LENGTH - 1) / LINE_LENGTH];
        for (int index = 0; index < lines.length; index++) {
            int offset = index * LINE_LENGTH;
            lines[index] = text.slice(offset, Math.min(LINE_LENGTH, text.length() - offset));
        }
        return lines;
    }

    // Random words of a and b, 10 to 80 letters each, each followed by a space.
    private static byte[] abWords(int length)
    {
        Random random = new Random(1);
        byte[] input = new byte[length];
        int index = 0;
        while (index < length) {
            for (int letters = 10 + random.nextInt(71); letters > 0 && index < length; letters--) {
                input[index++] = (byte) (random.nextBoolean() ? 'a' : 'b');
            }
            if (index < length) {
                input[index++] = ' ';
            }
        }
        return input;
    }

    private static String datePattern()
    {
        try (InputStream input = BenchmarkSweepCliffs.class.getResourceAsStream(DATE_PATTERN_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("date pattern not found: " + DATE_PATTERN_RESOURCE);
            }
            return new String(input.readAllBytes(), UTF_8).strip();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Short date tokens of varied length, each between spaces, in runs of code-like filler of 60
    // to 140 bytes that hold no byte that can start a match of the date pattern. The candidate
    // start cursor runs only where its productivity check, which samples four 16-byte windows,
    // finds fewer than eight bytes in them that can start a match. Rebar's 03-date haystack, a
    // match every 2.4 bytes, fails that check, and its matches run on the group-zero forward
    // cursor instead; here a token holds at most seven such bytes and the windows rarely meet two.
    private static byte[] dateText(int length)
    {
        String[] dateTokens = {"14", "2010", "Sep", "tue", "June", "9:05", "12:30", "1st", "pm", "03-14", "10am"};
        byte[] filler = "hiqryzHIQRYZ()[]{}<>=;*!?#$%&'\"|~".getBytes(US_ASCII);
        Random random = new Random(3);
        byte[] text = new byte[length];
        int index = 0;
        while (index < length) {
            for (int fill = 60 + random.nextInt(81); fill > 0 && index < length; fill--) {
                text[index++] = filler[random.nextInt(filler.length)];
            }
            byte[] token = (" " + dateTokens[random.nextInt(dateTokens.length)] + " ").getBytes(US_ASCII);
            int copied = Math.min(token.length, length - index);
            System.arraycopy(token, 0, text, index, copied);
            index += copied;
        }
        return text;
    }

    // Distinct random words of 10 to 15 lowercase letters, as the English dictionary of rebar's
    // english-10 holds words of at least ten letters.
    private static List<String> dictionary(int count)
    {
        Random random = new Random(count);
        Set<String> words = new LinkedHashSet<>();
        while (words.size() < count) {
            words.add(randomWord(random, 10 + random.nextInt(6)));
        }
        return List.copyOf(words);
    }

    // Words separated by spaces, one in four from the dictionary and the rest random words of 2
    // to 12 letters.
    private static byte[] dictionaryText(List<String> dictionary, int length)
    {
        Random random = new Random(7);
        StringBuilder builder = new StringBuilder(length + 32);
        while (builder.length() < length) {
            builder.append(random.nextInt(4) == 0 ? dictionary.get(random.nextInt(dictionary.size())) : randomWord(random, 2 + random.nextInt(11)));
            builder.append(' ');
        }
        return Arrays.copyOf(builder.toString().getBytes(US_ASCII), length);
    }

    private static String randomWord(Random random, int letters)
    {
        char[] word = new char[letters];
        for (int index = 0; index < letters; index++) {
            word[index] = (char) ('a' + random.nextInt(26));
        }
        return new String(word);
    }

    // The span lengths of the leftmost-first matches of the alternation of `dictionary`: at each
    // position, the earliest word in alternation order that matches there.
    private static long dictionarySpans(List<String> dictionary, Slice text)
    {
        Map<String, Integer> order = new HashMap<>();
        int shortest = Integer.MAX_VALUE;
        int longest = 0;
        for (int index = 0; index < dictionary.size(); index++) {
            String word = dictionary.get(index);
            order.putIfAbsent(word, index);
            shortest = Math.min(shortest, word.length());
            longest = Math.max(longest, word.length());
        }
        String input = text.toString(US_ASCII);
        long spans = 0;
        int position = 0;
        while (position < input.length()) {
            int best = -1;
            int bestOrder = Integer.MAX_VALUE;
            for (int length = shortest; length <= longest && position + length <= input.length(); length++) {
                Integer wordOrder = order.get(input.substring(position, position + length));
                if (wordOrder != null && wordOrder < bestOrder) {
                    bestOrder = wordOrder;
                    best = length;
                }
            }
            if (best < 0) {
                position++;
            }
            else {
                spans += best;
                position += best;
            }
        }
        return spans;
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkSweepCliffs.class, args);
        new Runner(options).run();
    }
}
