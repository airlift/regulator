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

import io.airlift.regulator.TestingDifferentialFuzzer.CaseInput;
import io.airlift.regulator.TestingDifferentialFuzzer.Generated;
import io.airlift.regulator.TestingDifferentialFuzzer.Language;
import io.airlift.regulator.TestingDifferentialFuzzer.Oracle;
import io.airlift.regulator.TestingDifferentialFuzzer.Trace;
import io.airlift.regulator.TestingDifferentialFuzzer.Variant;
import io.airlift.regulator.TestingPatternTree.AnyCharacter;
import io.airlift.regulator.TestingPatternTree.Concat;
import io.airlift.regulator.TestingPatternTree.Literal;
import io.airlift.regulator.TestingPatternTree.Node;
import io.airlift.regulator.TestingPatternTree.OutsideDialect;
import io.airlift.regulator.TestingPatternTree.Repeat;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;

/// Differential fuzzing of [TrinoLikePattern] against Trino's `LikeMatcher`, compiled by
/// `tools/trino-like-golden` and loaded from `-Dregulator.fuzz.likeMatcherClasspath=PATHS`; see
/// [TestingDifferentialFuzzer]. `LikeMatcher` with optimization disabled is the authority
/// (REGULATOR_DECISIONS.md, Trino LIKE Frontend), and the optimized matcher Trino SQL also runs
/// must agree with it. The options are the escape character plus one, or 0 for none; see
/// [#escape(int)].
public class TestTrinoLikeFuzz
{
    static final Language<TrinoLikePattern, LikeCompiled> LANGUAGE = new LikeLanguage();

    private static final long SEED = 0x4C_49_4B_45_46_55_5AL;
    private static final int PATTERNS = 600;

    private static final String ORACLE_PROPERTY = "regulator.fuzz.likeMatcherClasspath";

    // Literal characters: ASCII, including regex syntax that LIKE reads literally, multibyte and
    // supplementary characters, U+FFFD, which matches only its own bytes, and the wildcards, which
    // a pattern writes escaped.
    private static final List<String> CHARACTERS = List.of(
            "a",
            "b",
            "c",
            "x",
            "A",
            "Z",
            "0",
            "7",
            " ",
            "\n",
            "\t",
            ".",
            "*",
            "[",
            "\\",
            "#",
            "!",
            "$",
            "%",
            "_",
            "é",
            "ж",
            "€",
            "中",
            "�",
            "😀",
            "𝄞");

    // Escape characters Trino can take, each one char and never a surrogate: ASCII punctuation,
    // a letter that also appears as a literal, multibyte characters, and the wildcards
    // themselves.
    private static final List<Integer> ESCAPES = List.of((int) '\\', (int) '#', (int) '!', (int) 'x', (int) 'é', (int) '€', (int) '中', (int) '%', (int) '_');

    // The `%` wildcard: any run of characters.
    private static final Node PERCENT = new Repeat(new AnyCharacter(), 0, -1, true, 0);

    @Test
    public void testFuzz()
    {
        TestingDifferentialFuzzer.fuzz(LANGUAGE, SEED, PATTERNS);
    }

    /// The options of a pattern with `escape` as its escape character.
    static int escape(int escape)
    {
        return escape + 1;
    }

    private static OptionalInt escapeOf(int options)
    {
        return options == 0 ? OptionalInt.empty() : OptionalInt.of(options - 1);
    }

    private static final class LikeLanguage
            implements Language<TrinoLikePattern, LikeCompiled>
    {
        @Override
        public String name()
        {
            return "like";
        }

        @Override
        public String replayName()
        {
            return "TestTrinoLikeFuzz.LANGUAGE";
        }

        @Override
        public List<Variant> variants()
        {
            return Variant.LIKE;
        }

        @Override
        public Variant baseline()
        {
            return Variant.INTERPRETED;
        }

        @Override
        public Variant coverageVariant()
        {
            return Variant.BYTECODE;
        }

        // A pattern is a sequence of literals, `_` as any character, and `%` as an unbounded
        // repetition of it, with long literal runs now and then.
        @Override
        public Generated generate(Random random)
        {
            int options = random.nextBoolean() ? 0 : escape(ESCAPES.get(random.nextInt(ESCAPES.size())));
            if (random.nextInt(6) == 0 && options != escape('%') && options != escape('_')) {
                return new Generated(new Concat(literalGaps(random, options)), options);
            }
            int length = random.nextInt(8) == 0 ? random.nextInt(40) : random.nextInt(9);
            List<Node> items = new ArrayList<>();
            while (items.size() < length) {
                int choice = random.nextInt(8);
                // An escape that is itself a wildcard leaves that wildcard unwritable.
                if (choice < 2 && options != escape('%')) {
                    items.add(PERCENT);
                }
                else if (choice >= 2 && choice < 4 && options != escape('_')) {
                    items.add(new AnyCharacter());
                }
                else if (choice < 7) {
                    items.add(literal(random, options));
                }
                else {
                    // A long run, one character repeated or a short cycle, for the literal kernels.
                    List<Literal> cycle = new ArrayList<>();
                    for (int i = 1 + random.nextInt(3); i > 0; i--) {
                        cycle.add(literal(random, options));
                    }
                    for (int i = 4 + random.nextInt(60); i > 0; i--) {
                        items.add(cycle.get(i % cycle.size()));
                    }
                }
            }
            return new Generated(new Concat(items), options);
        }

        // Literals separated by runs of `_` between two `%`, which random sequences rarely
        // produce: the shape of the literal-gap plan.
        private static List<Node> literalGaps(Random random, int options)
        {
            List<Node> items = new ArrayList<>();
            if (random.nextInt(4) == 0) {
                items.add(literal(random, options));
            }
            items.add(PERCENT);
            for (int i = 1 + random.nextInt(5); i > 0; i--) {
                items.add(literal(random, options));
            }
            for (int gap = 1 + random.nextInt(3); gap > 0; gap--) {
                for (int i = 1 + random.nextInt(3); i > 0; i--) {
                    items.add(new AnyCharacter());
                }
                for (int i = 1 + random.nextInt(4); i > 0; i--) {
                    items.add(literal(random, options));
                }
            }
            items.add(PERCENT);
            if (random.nextInt(4) == 0) {
                items.add(literal(random, options));
            }
            return items;
        }

        // A literal character; style 1, written rarely, puts a stray escape before it, which
        // compile rejects unless the character is a wildcard or the escape.
        private static Literal literal(Random random, int options)
        {
            int codePoint = CHARACTERS.get(random.nextInt(CHARACTERS.size())).codePointAt(0);
            if ((codePoint == '%' || codePoint == '_') && options == 0) {
                codePoint = 'a';
            }
            return new Literal(codePoint, options != 0 && random.nextInt(64) == 0 ? 1 : 0);
        }

        @Override
        public String render(Node tree, int options)
        {
            OptionalInt escape = escapeOf(options);
            // Trino takes the escape as one char, which a surrogate never is in a SQL string.
            if (escape.isPresent() && (!Character.isBmpCodePoint(escape.orElseThrow()) || Character.isSurrogate((char) escape.orElseThrow()))) {
                return null;
            }
            StringBuilder out = new StringBuilder();
            try {
                write(tree, escape, out);
            }
            catch (OutsideDialect _) {
                return null;
            }
            return out.toString();
        }

        private static void write(Node node, OptionalInt escape, StringBuilder out)
        {
            switch (node) {
                case Concat concat -> {
                    for (Node item : concat.items()) {
                        write(item, escape, out);
                    }
                }
                case AnyCharacter _ -> out.append(wildcard('_', escape));
                case Repeat repeat when repeat.body() instanceof AnyCharacter && repeat.min() == 0 && repeat.unbounded() -> out.append(wildcard('%', escape));
                case Literal literal -> {
                    int codePoint = literal.codePoint();
                    boolean special = codePoint == '%' || codePoint == '_' || (escape.isPresent() && codePoint == escape.orElseThrow());
                    if (special || literal.style() == 1) {
                        if (escape.isEmpty()) {
                            throw new OutsideDialect();
                        }
                        out.appendCodePoint(escape.orElseThrow());
                    }
                    out.appendCodePoint(codePoint);
                }
                default -> throw new OutsideDialect();
            }
        }

        // LikeMatcher reads the escape before the wildcards, so a wildcard that is also the
        // escape cannot be written.
        private static char wildcard(char wildcard, OptionalInt escape)
        {
            if (escape.isPresent() && escape.orElseThrow() == wildcard) {
                throw new OutsideDialect();
            }
            return wildcard;
        }

        @Override
        public String describeOptions(int options)
        {
            return options == 0 ? "0" : "TestTrinoLikeFuzz.escape(0x" + Integer.toHexString(options - 1) + ")";
        }

        @Override
        public String sample(Node tree, int options, String pattern, Random random)
        {
            return TestingPatternTree.sample(tree, random, false, CHARACTERS);
        }

        @Override
        public List<String> inputCharacters(Node tree, int options)
        {
            OptionalInt escape = escapeOf(options);
            if (escape.isEmpty() || !Character.isValidCodePoint(escape.orElseThrow())) {
                return CHARACTERS;
            }
            return Stream.concat(CHARACTERS.stream(), Stream.of(Character.toString(escape.orElseThrow()))).toList();
        }

        @Override
        public String filterInput(Node tree, int options, String input)
        {
            return input;
        }

        @Override
        public byte[] malformed(Random random, byte[] input)
        {
            return TestingDifferentialFuzzer.malformedSequence(random, input);
        }

        @Override
        public boolean oracleReadsMalformedInput(int options)
        {
            return true;
        }

        @Override
        public TrinoLikePattern compile(String pattern, int options, Variant variant)
        {
            TrinoLikePattern.Options likeOptions = TrinoLikePattern.Options.defaults().setGenerateBytecode(variant.generateBytecode()).setAarch64ForTesting(variant.aarch64());
            escapeOf(options).ifPresent(likeOptions::setEscape);
            return TrinoLikePattern.compile(utf8Slice(pattern), likeOptions);
        }

        @Override
        public Oracle<LikeCompiled> oracle()
        {
            return LikeMatcherOracle.INSTANCE;
        }

        // Each result is recorded twice, once for each LikeMatcher form.
        @Override
        public void operations(TrinoLikePattern pattern, CaseInput input, Trace trace)
        {
            Slice source = input.source();
            Slice region = source.slice(input.regionStart(), input.regionEnd() - input.regionStart());
            trace.add("matches", () -> pattern.matches(source));
            trace.add("optimized matches", () -> pattern.matches(source));
            trace.add("matches range", () -> pattern.matches(region));
            trace.add("optimized matches range", () -> pattern.matches(region));
        }

        // Every plan reads malformed input by the same maximal-subpart rule
        // (REGULATOR_DECISIONS.md, Malformed LIKE Input).
        @Override
        public boolean variantsAgreeOnMalformedInput()
        {
            return true;
        }

        @Override
        public Set<String> coverage(TrinoLikePattern compiled)
        {
            Set<String> routes = new HashSet<>();
            routes.add(compiled.planForDiagnostics().name());
            if (compiled.usesGeneratedBytecode()) {
                routes.add("generated bytecode");
            }
            return routes;
        }
    }

    /// Trino's two matchers for a pattern, and what decides whether they judge an input: the
    /// size bounds and literal prefix and suffix `LikeMatcher` checks first, whether a wildcard
    /// automaton runs between them, and whether that automaton meets a supplementary literal.
    record LikeCompiled(Object unoptimized, Object optimized, int minimumSize, OptionalInt maximumSize, byte[] prefix, byte[] suffix, boolean automaton, boolean supplementaryLiteral) {}

    /// Trino's `LikeMatcher`, loaded in its own class loader and called reflectively, as
    /// `tools/trino-like-golden` calls it: the pattern decoded to a String and the escape as a
    /// char.
    private static final class LikeMatcherOracle
            implements Oracle<LikeCompiled>
    {
        private static final LikeMatcherOracle INSTANCE = new LikeMatcherOracle(System.getProperty(ORACLE_PROPERTY));

        private final Method compile;
        private final Method match;
        private final Field minimumSize;
        private final Field maximumSize;
        private final Field prefix;
        private final Field suffix;
        private final Field matcher;

        private LikeMatcherOracle(String classpath)
        {
            if (classpath == null || classpath.isEmpty()) {
                compile = null;
                match = null;
                minimumSize = null;
                maximumSize = null;
                prefix = null;
                suffix = null;
                matcher = null;
                return;
            }
            try {
                URL[] urls = Arrays.stream(classpath.split(File.pathSeparator))
                        .map(entry -> {
                            try {
                                return Path.of(entry).toUri().toURL();
                            }
                            catch (MalformedURLException e) {
                                throw new IllegalArgumentException("-D%s holds an entry that is not a path: %s".formatted(ORACLE_PROPERTY, entry), e);
                            }
                        })
                        .toArray(URL[]::new);
                Class<?> likeMatcher = loadLikeMatcher(urls);
                compile = likeMatcher.getMethod("compile", String.class, Optional.class, boolean.class);
                match = likeMatcher.getMethod("match", byte[].class, int.class, int.class);
                minimumSize = accessible(likeMatcher.getDeclaredField("minSize"));
                maximumSize = accessible(likeMatcher.getDeclaredField("maxSize"));
                prefix = accessible(likeMatcher.getDeclaredField("prefix"));
                suffix = accessible(likeMatcher.getDeclaredField("suffix"));
                matcher = accessible(likeMatcher.getDeclaredField("matcher"));
            }
            catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot load LikeMatcher from -D%s=%s".formatted(ORACLE_PROPERTY, classpath), e);
            }
        }

        // The classes are the ones tools/trino-like-golden compiles from Trino's pinned sources,
        // loaded apart from the test classpath so their Guava stays their own. The loader lives
        // as long as the oracle, which is the test JVM's lifetime.
        @SuppressWarnings({"BanClassLoader", "resource"})
        private static Class<?> loadLikeMatcher(URL[] urls)
                throws ClassNotFoundException
        {
            return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).loadClass("io.trino.likematcher.LikeMatcher");
        }

        private static Field accessible(Field field)
        {
            field.setAccessible(true);
            return field;
        }

        @Override
        public boolean available()
        {
            return compile != null;
        }

        @Override
        public LikeCompiled compile(String pattern, int options)
                throws ReflectiveOperationException
        {
            Optional<Character> escape = escapeOf(options).stream().mapToObj(codePoint -> (char) codePoint).findFirst();
            Object unoptimized = invokeCompile(pattern, escape, false);
            Object optimized = invokeCompile(pattern, escape, true);
            byte[] prefixBytes = (byte[]) prefix.get(unoptimized);
            byte[] suffixBytes = (byte[]) suffix.get(unoptimized);
            // A pattern with `_` between its prefix and suffix runs Trino's NFA, or the DFA built
            // from it, which compares literal characters as UTF-16 units, so a supplementary
            // literal there never matches (REGULATOR_DECISIONS.md, Malformed LIKE Input). The
            // escape is never supplementary, so every supplementary character in the pattern is
            // a literal.
            Optional<?> automaton = (Optional<?>) matcher.get(unoptimized);
            boolean usesAutomaton = automaton.isPresent() && !automaton.orElseThrow().getClass().getSimpleName().equals("FjsMatcher");
            long supplementary = pattern.codePoints().filter(codePoint -> !Character.isBmpCodePoint(codePoint)).count();
            long outside = supplementaryCount(prefixBytes) + supplementaryCount(suffixBytes);
            return new LikeCompiled(
                    unoptimized,
                    optimized,
                    minimumSize.getInt(unoptimized),
                    (OptionalInt) maximumSize.get(unoptimized),
                    prefixBytes,
                    suffixBytes,
                    usesAutomaton,
                    usesAutomaton && supplementary > outside);
        }

        private Object invokeCompile(String pattern, Optional<Character> escape, boolean optimized)
                throws ReflectiveOperationException
        {
            try {
                return compile.invoke(null, pattern, escape, optimized);
            }
            catch (InvocationTargetException e) {
                if (e.getCause() instanceof IllegalArgumentException rejection) {
                    throw rejection;
                }
                throw e;
            }
        }

        private static long supplementaryCount(byte[] utf8)
        {
            return new String(utf8, UTF_8).codePoints().filter(codePoint -> !Character.isBmpCodePoint(codePoint)).count();
        }

        @Override
        public void operations(LikeCompiled compiled, CaseInput input, Trace trace, long deadlineNanos)
        {
            byte[] bytes = input.bytes();
            record(compiled, bytes, 0, bytes.length, "", trace);
            record(compiled, bytes, input.regionStart(), input.regionEnd() - input.regionStart(), " range", trace);
        }

        private void record(LikeCompiled compiled, byte[] bytes, int offset, int length, String operationSuffix, Trace trace)
        {
            String skip = unjudged(compiled, bytes, offset, length);
            if (skip != null) {
                trace.skip("matches" + operationSuffix, skip);
                trace.skip("optimized matches" + operationSuffix, skip);
                return;
            }
            trace.add("matches" + operationSuffix, () -> match(compiled.unoptimized(), bytes, offset, length));
            trace.add("optimized matches" + operationSuffix, () -> match(compiled.optimized(), bytes, offset, length));
        }

        // Returns why the oracle cannot judge the input, or null when it can. Trino's automaton
        // misreads a supplementary literal, and on malformed input it takes a character's width
        // from its lead byte and fails on a byte it cannot decode, where Regulator reads each
        // maximal invalid subpart as one character (REGULATOR_DECISIONS.md, Malformed LIKE
        // Input). The oracle still judges where no automaton runs, since Trino then compares
        // raw bytes as Regulator does, and where LikeMatcher decides before running it: on input
        // outside its size bounds, which allow 1 to 4 bytes for each `_`, as Regulator's
        // characters take, or on a prefix or suffix that differs.
        private static String unjudged(LikeCompiled compiled, byte[] bytes, int offset, int length)
        {
            if (!compiled.automaton() || length < compiled.minimumSize() || (compiled.maximumSize().isPresent() && length > compiled.maximumSize().orElseThrow())) {
                return null;
            }
            if (!regionMatches(bytes, offset, compiled.prefix()) || !regionMatches(bytes, offset + length - compiled.suffix().length, compiled.suffix())) {
                return null;
            }
            if (compiled.supplementaryLiteral()) {
                return "supplementary LIKE literal (REGULATOR_DECISIONS.md, Malformed LIKE Input)";
            }
            if (Utf8.firstInvalidOffset(wrappedBuffer(bytes, offset, length)) >= 0) {
                return "malformed LIKE input (REGULATOR_DECISIONS.md, Malformed LIKE Input)";
            }
            return null;
        }

        // Whether `literal` appears in `bytes` at `offset`.
        private static boolean regionMatches(byte[] bytes, int offset, byte[] literal)
        {
            return Arrays.equals(bytes, offset, offset + literal.length, literal, 0, literal.length);
        }

        private boolean match(Object likeMatcher, byte[] bytes, int offset, int length)
        {
            try {
                return (boolean) match.invoke(likeMatcher, bytes, offset, length);
            }
            catch (InvocationTargetException e) {
                throw new IllegalStateException(e.getCause());
            }
            catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
