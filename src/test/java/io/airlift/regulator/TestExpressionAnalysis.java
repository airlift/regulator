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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static io.airlift.regulator.ExpressionAnalysis.Gap.NONE;
import static io.airlift.regulator.ExpressionAnalysis.Gap.ONE_BYTE;
import static io.airlift.regulator.ExpressionAnalysis.Gap.ONE_NON_NEWLINE_CODE_POINT;
import static io.airlift.regulator.ExpressionAnalysis.Gap.REQUIRES_GENERAL_ENGINE;
import static io.airlift.regulator.ExpressionAnalysis.Gap.ZERO_OR_MORE_BYTES;
import static io.airlift.regulator.ExpressionAnalysis.Gap.ZERO_OR_MORE_CODE_POINTS;
import static io.airlift.regulator.ExpressionAnalysis.Gap.ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS;
import static io.airlift.regulator.Prefilter.PrefilterOp.AND;
import static io.airlift.regulator.Prefilter.PrefilterOp.ATOM;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestExpressionAnalysis
{
    @Test
    public void testLengthAndExpressionFacts()
    {
        assertLength("", 0, 0, true);
        assertLength("abc", 3, 3, false);
        assertLength("a", 1, 1, false);
        assertLength("é", 2, 2, false);
        assertLength("€", 3, 3, false);
        assertLength("💰", 4, 4, false);
        assertLength("a|💰", 1, 4, false);
        assertLength("a*", 0, -1, true);
        assertLength("a{2,4}", 2, 4, false);
        assertLength("[a💰]", 1, 4, false);

        ExpressionAnalysis impossible = analyze("[^\\S\\s]");
        assertThat(impossible.length().canMatch()).isFalse();

        ExpressionAnalysis captured = analyze("(ab)(cd)");
        assertThat(captured.hasCaptures()).isTrue();
        assertThat(captured.literalSequence().exactLiteral()).isEqualTo(utf8Slice("abcd"));

        assertThat(analyze("a*").hasNonGreedyRepetition()).isFalse();
        assertThat(analyze("a*?").hasNonGreedyRepetition()).isTrue();

        ExpressionAnalysis folded = analyze("(?i:abc)");
        assertThat(folded.hasFoldCaseLiteral()).isTrue();
        assertThat(folded.literalSequence()).isNull();

        ExpressionAnalysis expandedFold = analyze("(?i:k)");
        assertThat(expandedFold.hasFoldCaseLiteral()).isFalse();
        assertThat(expandedFold.literalSequence()).isNull();
        assertBounds(expandedFold.length(), 1, 3);

        ExpressionAnalysis latin1 = Re2.compile(wrappedBuffer(new byte[] {(byte) 0xE9}), Re2.Options.latin1()).expressionAnalysisForDiagnostics();
        assertThat(latin1.latin1()).isTrue();
        assertBounds(latin1.length(), 1, 1);
        assertThat(latin1.literalSequence().exactLiteral()).isEqualTo(wrappedBuffer(new byte[] {(byte) 0xE9}));

        ExpressionAnalysis latin1Wildcard = Re2.compile(utf8Slice("."), Re2.Options.latin1()).expressionAnalysisForDiagnostics();
        assertBounds(latin1Wildcard.length(), 1, 1);
        assertThat(latin1Wildcard.literalSequence().leadingGap()).isEqualTo(ONE_NON_NEWLINE_CODE_POINT);

        ExpressionAnalysis latin1Fold = Re2.compile(utf8Slice("a"), Re2.Options.latin1().setCaseSensitive(false)).expressionAnalysisForDiagnostics();
        assertBounds(latin1Fold.length(), 1, 1);
        assertThat(latin1Fold.hasFoldCaseLiteral()).isTrue();
        assertThat(latin1Fold.literalSequence()).isNull();
    }

    @Test
    public void testNormalizedEncodedLengthMatchesRawAnalysisOverCorpora()
    {
        // The compiler stores the encoded match length of the simplified expression, so
        // simplification must preserve every length bound that the raw analysis computes,
        // including the multi-character bounds of Trino full case folding.
        Set<String> upstreamPatterns = new LinkedHashSet<>(readPatterns("io/airlift/regulator/prog/upstream_compile_dump_patterns.txt"));
        Set<String> everydayPatterns = new LinkedHashSet<>();
        for (String workloadId : TestingEverydayTrinoRegexpBenchmarkInputs.workloadIds()) {
            everydayPatterns.add(TestingEverydayTrinoRegexpBenchmarkInputs.create(workloadId).pattern().toStringUtf8());
        }
        Set<String> sharedPatterns = new LinkedHashSet<>(BenchmarkCompileCorpus.SHARED_REGEXP_CORPUS);
        assertThat(upstreamPatterns).hasSizeGreaterThanOrEqualTo(22);
        assertThat(everydayPatterns).hasSizeGreaterThanOrEqualTo(12);
        assertThat(sharedPatterns).hasSizeGreaterThanOrEqualTo(42);

        Set<String> patterns = new LinkedHashSet<>();
        patterns.addAll(upstreamPatterns);
        patterns.addAll(everydayPatterns);
        patterns.addAll(sharedPatterns);
        for (String pattern : patterns) {
            Slice slice = utf8Slice(pattern);
            for (Regexp regexp : Arrays.asList(
                    parseOrNull(() -> RegexpParser.parse(slice, Regexp.LIKE_PERL)),
                    parseOrNull(() -> RegexpParser.parse(slice, Regexp.LIKE_PERL | Regexp.LATIN1)),
                    parseOrNull(() -> RegexpParser.parse(slice, Regexp.LIKE_PERL | Regexp.FOLD_CASE)),
                    parseOrNull(() -> JavaRegexpParser.parse(slice, Regexp.LIKE_PERL)),
                    parseOrNull(() -> JavaRegexpParser.parse(slice, Regexp.LIKE_PERL | Regexp.FOLD_CASE)),
                    parseOrNull(() -> TrinoRegexpParser.parse(slice, Regexp.LIKE_PERL)),
                    parseOrNull(() -> TrinoRegexpParser.parse(slice, Regexp.LIKE_PERL | Regexp.FOLD_CASE)))) {
                if (regexp == null) {
                    continue;
                }
                int raw = MatchLength.analyze(regexp).encoded();
                int normalized = ExpressionAnalysis.analyzeNormalized(Simplifier.simplify(regexp)).length().encoded();
                assertThat(normalized).as("encoded length: %s", pattern).isEqualTo(raw);
            }
        }
    }

    @Test
    public void testEncodedMatchLength()
    {
        for (String pattern : List.of(
                "",
                "abc",
                "a|💰",
                "a*",
                "[a💰]",
                "(?i:K)")) {
            Regexp regexp = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();
            Regexp normalized = Simplifier.simplify(regexp);
            assertThat(ExpressionAnalysis.analyzeNormalized(normalized).length().encoded())
                    .as("encoded length: %s", pattern)
                    .isEqualTo(MatchLength.analyze(regexp).encoded());
        }

        Regexp noMatch = Regexp.noMatch(Regexp.LIKE_PERL);
        assertThat(ExpressionAnalysis.analyzeNormalized(noMatch).length().encoded())
                .isEqualTo(MatchLength.analyze(noMatch).encoded());

        Regexp literal = Regexp.literal(Regexp.LIKE_PERL, 0x1F4B0);
        Regexp saturated = Regexp.repeat(Regexp.LIKE_PERL, literal, Integer.MAX_VALUE, Integer.MAX_VALUE);
        ExpressionAnalysis.Length saturatedLength = ExpressionAnalysis.analyzeNormalized(saturated).length();
        assertThat(saturatedLength.encoded())
                .isEqualTo(MatchLength.analyze(saturated).encoded());
        assertThat(saturatedLength.fixed()).isEqualTo(-1);
    }

    @Test
    public void testLiteralSequence()
    {
        assertSequence("", false, false, List.of(), List.of(NONE));
        assertSequence("abc", false, false, List.of("abc"), List.of(NONE, NONE));
        assertSequence("(ab)(cd)", false, false, List.of("abcd"), List.of(NONE, NONE));
        assertSequence("^foo(?s:.*)bar$", true, true, List.of("foo", "bar"), List.of(NONE, ZERO_OR_MORE_CODE_POINTS, NONE));
        assertSequence("\\Afoo\\C*bar\\z", true, true, List.of("foo", "bar"), List.of(NONE, ZERO_OR_MORE_BYTES, NONE));
        assertSequence("foo.bar", false, false, List.of("foo", "bar"), List.of(NONE, ONE_NON_NEWLINE_CODE_POINT, NONE));
        assertSequence("foo\\Cbar", false, false, List.of("foo", "bar"), List.of(NONE, ONE_BYTE, NONE));
        assertSequence("foo.*bar", false, false, List.of("foo", "bar"), List.of(NONE, ZERO_OR_MORE_NON_NEWLINE_CODE_POINTS, NONE));

        ExpressionAnalysis alternate = analyze("foo|bar");
        assertThat(alternate.literalSequence()).isNull();

        ExpressionAnalysis prefixOnly = analyze("foo[0-9]");
        assertThat(prefixOnly.literalSequence().requiredPrefix()).isEqualTo(utf8Slice("foo"));
        assertThat(prefixOnly.literalSequence().requiredSuffix()).isNull();
        assertThat(prefixOnly.literalSequence().gapAfter(0)).isEqualTo(REQUIRES_GENERAL_ENGINE);

        ExpressionAnalysis suffixOnly = analyze("[0-9]foo");
        assertThat(suffixOnly.literalSequence().requiredPrefix()).isNull();
        assertThat(suffixOnly.literalSequence().requiredSuffix()).isEqualTo(utf8Slice("foo"));
        assertThat(suffixOnly.literalSequence().leadingGap()).isEqualTo(REQUIRES_GENERAL_ENGINE);

        ExpressionAnalysis bothEdges = analyze("foo[0-9]bar");
        assertThat(bothEdges.literalSequence().requiredPrefix()).isEqualTo(utf8Slice("foo"));
        assertThat(bothEdges.literalSequence().requiredSuffix()).isEqualTo(utf8Slice("bar"));
        assertThat(bothEdges.literalSequence().gapAfter(0)).isEqualTo(REQUIRES_GENERAL_ENGINE);

        ExpressionAnalysis lineAssertions = analyze("(?m:^foo$)");
        assertThat(lineAssertions.hasUnsupportedPositionAssertions()).isTrue();
        assertThat(lineAssertions.literalSequence().leadingGap()).isEqualTo(REQUIRES_GENERAL_ENGINE);
        assertThat(lineAssertions.literalSequence().gapAfter(0)).isEqualTo(REQUIRES_GENERAL_ENGINE);
        assertThat(lineAssertions.literalSequence().requiredPrefix()).isNull();
        assertThat(lineAssertions.literalSequence().requiredSuffix()).isNull();

        assertThat(analyze("\\bfoo\\b").hasUnsupportedPositionAssertions()).isTrue();
        assertThat(analyze("\\Afoo\\z").hasUnsupportedPositionAssertions()).isFalse();
    }

    @Test
    public void testEquivalentSpellings()
    {
        assertEquivalentLiteralSequence("foo", "(?:foo)", "(f)(?:o)o", "((foo))");
        assertEquivalentLiteralSequence("foo(?s:.*)bar", "(?:foo)(?s:.*)(bar)");
        assertEquivalentLiteralSequence("foo(?s:.*)bar", "foo((?s:.))*bar");
        assertEquivalentLiteralSequence("foo.*bar", "(?:foo)(?:.*)(bar)");

        assertSequence("(?s:foo.bar)", false, false, List.of("foo", "bar"), List.of(NONE, ExpressionAnalysis.Gap.ONE_CODE_POINT, NONE));
        assertSequence("(?s:foo.*bar)", false, false, List.of("foo", "bar"), List.of(NONE, ZERO_OR_MORE_CODE_POINTS, NONE));
    }

    @Test
    public void testLiteralAccessors()
    {
        ExpressionAnalysis.LiteralSequence exact = analyze("^needle$").literalSequence();
        assertThat(exact.exactLiteral()).isEqualTo(utf8Slice("needle"));
        assertThat(exact.requiredPrefix()).isEqualTo(utf8Slice("needle"));
        assertThat(exact.requiredSuffix()).isEqualTo(utf8Slice("needle"));

        ExpressionAnalysis.LiteralSequence prefix = analyze("^needle(?s:.*)$").literalSequence();
        assertThat(prefix.exactLiteral()).isNull();
        assertThat(prefix.requiredPrefix()).isEqualTo(utf8Slice("needle"));
        assertThat(prefix.requiredSuffix()).isNull();

        ExpressionAnalysis.LiteralSequence suffix = analyze("^(?s:.*)needle$").literalSequence();
        assertThat(suffix.requiredPrefix()).isNull();
        assertThat(suffix.requiredSuffix()).isEqualTo(utf8Slice("needle"));

        ExpressionAnalysis.LiteralSequence contains = analyze("(?s:.*needle.*)").literalSequence();
        assertThat(contains.requiredPrefix()).isNull();
        assertThat(contains.requiredSuffix()).isNull();

        Slice mutableLiteral = exact.literal(0);
        mutableLiteral.setByte(0, 'x');
        assertThat(exact.literal(0)).isEqualTo(utf8Slice("needle"));
    }

    @Test
    public void testOptionInteractions()
    {
        ExpressionAnalysis dotAll = Re2.compile(
                        utf8Slice("foo.bar"),
                        Re2.Options.defaults().setDotMatchesNewline(true))
                .expressionAnalysisForDiagnostics();
        assertThat(dotAll.literalSequence().gapAfter(0)).isEqualTo(ExpressionAnalysis.Gap.ONE_CODE_POINT);

        ExpressionAnalysis neverNewline = Re2.compile(
                        utf8Slice("foo.bar"),
                        Re2.Options.defaults()
                                .setDotMatchesNewline(true)
                                .setNeverNewline(true))
                .expressionAnalysisForDiagnostics();
        assertThat(neverNewline.literalSequence().gapAfter(0)).isEqualTo(ONE_NON_NEWLINE_CODE_POINT);

        ExpressionAnalysis literal = Re2.compile(
                        utf8Slice("foo.*bar"),
                        Re2.Options.defaults().setLiteral(true))
                .expressionAnalysisForDiagnostics();
        assertThat(literal.literalSequence().exactLiteral()).isEqualTo(utf8Slice("foo.*bar"));
    }

    @Test
    public void testCanConsumeLineFeed()
    {
        assertThat(analyze("abc").canConsumeLineFeed()).isFalse();
        assertThat(analyze("[a-z]+").canConsumeLineFeed()).isFalse();
        assertThat(analyze(".+").canConsumeLineFeed()).isFalse();
        assertThat(analyze("\\n").canConsumeLineFeed()).isTrue();
        assertThat(analyze("[\\n]").canConsumeLineFeed()).isTrue();
        assertThat(analyze("a|\\n").canConsumeLineFeed()).isTrue();
        assertThat(analyze("(?s:.)").canConsumeLineFeed()).isTrue();
        assertThat(analyze("\\C").canConsumeLineFeed()).isTrue();
    }

    @Test
    public void testExistingAnalysisEquivalence()
    {
        for (String pattern : List.of(
                "",
                "abc",
                "(abc)",
                "^abc$",
                "a|💰",
                "a*",
                "a{2,4}",
                "[a-z]",
                "(?s:foo.*bar)",
                "(?i:foo)")) {
            Regexp regexp = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();
            Regexp normalized = Simplifier.simplify(regexp);
            MatchLength.Analysis currentLength = MatchLength.analyze(normalized);
            ExpressionAnalysis analysis = ExpressionAnalysis.analyzeNormalized(normalized);

            assertThat(analysis.length().minimum()).as("minimum: %s", pattern).isEqualTo(currentLength.minimum());
            assertThat(analysis.length().fixed()).as("fixed: %s", pattern).isEqualTo(currentLength.fixed());
            assertThat(analysis.canMatchEmpty()).as("nullable: %s", pattern).isEqualTo(Re2.compile(utf8Slice(pattern)).canMatchEmpty());
        }

        for (String pattern : List.of("", "abc", "(abc)", "(ab)(💰)")) {
            Re2 re2 = Re2.compile(utf8Slice(pattern));
            assertThat(re2.expressionAnalysisForDiagnostics().literalSequence().exactLiteral())
                    .as("exact literal: %s", pattern)
                    .isEqualTo(re2.exactLiteralForDiagnostics());
            if (!pattern.isEmpty()) {
                Slice input = utf8Slice("x" + pattern.replace("(", "").replace(")", "") + "x");
                assertThat(re2.forwardProgramForDiagnostics().prefixAccel(input.byteArray(), input.byteArrayOffset(), input.length()))
                        .as("program prefix acceleration: %s", pattern)
                        .isEqualTo(input.byteArrayOffset() + 1);
            }
        }

        for (String pattern : List.of("^abc", "^abc.*", "\\Aabc(?s:.*)")) {
            Re2 re2 = Re2.compile(utf8Slice(pattern));
            Regexp.RequiredPrefixResult currentPrefix = re2.regexp().requiredPrefix();
            assertThat(currentPrefix).as("current prefix: %s", pattern).isNotNull();
            assertThat(re2.expressionAnalysisForDiagnostics().literalSequence().requiredPrefix())
                    .as("shared prefix: %s", pattern)
                    .isEqualTo(currentPrefix.prefix());
        }

        for (String pattern : List.of("abc.*", "abc(?s:.*)", "abc[0-9]")) {
            Re2 re2 = Re2.compile(utf8Slice(pattern));
            Slice sharedPrefix = re2.expressionAnalysisForDiagnostics().literalSequence().requiredPrefix();
            assertThat(sharedPrefix).as("shared prefix: %s", pattern).isEqualTo(utf8Slice("abc"));

            Slice input = utf8Slice("xabc7");
            assertThat(re2.forwardProgramForDiagnostics().prefixAccel(input.byteArray(), input.byteArrayOffset(), input.length()))
                    .as("program prefix acceleration: %s", pattern)
                    .isEqualTo(input.byteArrayOffset() + 1);
        }
    }

    @Test
    public void testMalformedUtf8()
    {
        Slice malformedPattern = wrappedBuffer(new byte[] {'a', (byte) 0xFF, 'b'});
        assertThatThrownBy(() -> Re2.compile(malformedPattern))
                .isInstanceOfSatisfying(RegexpParseException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(RegexpParseErrorCode.BAD_UTF8);
                    assertThat(exception.byteOffset()).isEqualTo(1);
                });

        Re2 wildcard = Re2.compile(utf8Slice("a.b"));
        assertThat(wildcard.find(wrappedBuffer(new byte[] {'a', (byte) 0xFF, 'b'}))).isFalse();
    }

    @Test
    public void testPrefilterLiteralEquivalence()
    {
        Re2 re2 = Re2.compile(utf8Slice("(?s:foo.*bar.*baz)"));
        ExpressionAnalysis.LiteralSequence sequence = re2.expressionAnalysisForDiagnostics().literalSequence();
        assertThat(sequence.literalCount()).isEqualTo(3);

        List<Slice> atoms = new ArrayList<>();
        collectAtoms(Prefilter.fromRe2(re2), atoms);
        assertThat(atoms).containsExactlyInAnyOrder(utf8Slice("foo"), utf8Slice("bar"), utf8Slice("baz"));
        for (int literalIndex = 0; literalIndex < sequence.literalCount(); literalIndex++) {
            assertThat(atoms).contains(sequence.literal(literalIndex));
        }
    }

    private static void assertLength(String pattern, int minimum, int maximum, boolean canMatchEmpty)
    {
        ExpressionAnalysis analysis = analyze(pattern);
        assertBounds(analysis.length(), minimum, maximum);
        assertThat(analysis.canMatchEmpty()).isEqualTo(canMatchEmpty);
    }

    private static void assertBounds(ExpressionAnalysis.Length length, int minimum, int maximum)
    {
        assertThat(length.minimum()).isEqualTo(minimum);
        assertThat(length.maximum()).isEqualTo(maximum);
        assertThat(length.canMatch()).isTrue();
    }

    private static void assertSequence(
            String pattern,
            boolean anchoredAtStart,
            boolean anchoredAtEnd,
            List<String> literals,
            List<ExpressionAnalysis.Gap> gaps)
    {
        ExpressionAnalysis.LiteralSequence sequence = analyze(pattern).literalSequence();
        assertThat(sequence).as(pattern).isNotNull();
        assertThat(sequence.anchoredAtStart()).isEqualTo(anchoredAtStart);
        assertThat(sequence.anchoredAtEnd()).isEqualTo(anchoredAtEnd);
        assertThat(sequence.literalCount()).isEqualTo(literals.size());
        assertThat(sequence.leadingGap()).isEqualTo(gaps.getFirst());
        for (int literalIndex = 0; literalIndex < literals.size(); literalIndex++) {
            assertThat(sequence.literal(literalIndex)).isEqualTo(utf8Slice(literals.get(literalIndex)));
            assertThat(sequence.gapAfter(literalIndex)).isEqualTo(gaps.get(literalIndex + 1));
        }
    }

    private static void assertEquivalentLiteralSequence(String expectedPattern, String... equivalentPatterns)
    {
        ExpressionAnalysis.LiteralSequence expected = analyze(expectedPattern).literalSequence();
        assertThat(expected).isNotNull();
        for (String equivalentPattern : equivalentPatterns) {
            ExpressionAnalysis.LiteralSequence actual = analyze(equivalentPattern).literalSequence();
            assertThat(actual).as(equivalentPattern).isNotNull();
            assertThat(actual.anchoredAtStart()).isEqualTo(expected.anchoredAtStart());
            assertThat(actual.anchoredAtEnd()).isEqualTo(expected.anchoredAtEnd());
            assertThat(actual.literalCount()).isEqualTo(expected.literalCount());
            assertThat(actual.leadingGap()).isEqualTo(expected.leadingGap());
            for (int literalIndex = 0; literalIndex < expected.literalCount(); literalIndex++) {
                assertThat(actual.literal(literalIndex)).isEqualTo(expected.literal(literalIndex));
                assertThat(actual.gapAfter(literalIndex)).isEqualTo(expected.gapAfter(literalIndex));
            }
        }
    }

    private static ExpressionAnalysis analyze(String pattern)
    {
        return Re2.compile(utf8Slice(pattern)).expressionAnalysisForDiagnostics();
    }

    private static void collectAtoms(Prefilter prefilter, List<Slice> atoms)
    {
        assertThat(prefilter).isNotNull();
        if (prefilter.op() == ATOM) {
            atoms.add(prefilter.atom());
            return;
        }
        assertThat(prefilter.op()).isEqualTo(AND);
        for (Prefilter child : prefilter.children()) {
            collectAtoms(child, atoms);
        }
    }

    private static Regexp parseOrNull(Supplier<ParseResult> parser)
    {
        try {
            return parser.get().regexp();
        }
        catch (RegexpParseException e) {
            // Patterns that a frontend rejects have no analysis to compare.
            return null;
        }
    }

    // Reads the upstream pattern list the way TestUpstreamCompileDump does: comments are skipped
    // and the empty pattern is kept.
    private static List<String> readPatterns(String resourcePath)
    {
        InputStream stream = TestExpressionAnalysis.class.getClassLoader().getResourceAsStream(resourcePath);
        assertThat(stream).as(resourcePath).isNotNull();

        List<String> patterns = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) == '#') {
                    continue;
                }
                patterns.add(line);
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return patterns;
    }
}
