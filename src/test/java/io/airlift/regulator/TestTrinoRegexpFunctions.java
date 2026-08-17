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

import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceUtf8;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTrinoRegexpFunctions
{
    private static final String FUNCTION_CASES = "/io/airlift/regulator/trino-function-cases.tsv";

    @Test
    public void testMemoryBudget()
    {
        assertThat(TrinoRegexp.Options.defaults().maxMemory()).isEqualTo(96L << 20);
        assertThatThrownBy(() -> TrinoRegexp.Options.defaults().setMaxMemory(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> TrinoRegexp.compile(
                utf8("abc"),
                TrinoRegexp.Options.defaults().setMaxMemory(1)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("functionCases")
    public void testFunctionCorpus(FunctionCase functionCase)
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8(decode(functionCase.pattern())));
        Slice source = utf8(decode(functionCase.source()));
        switch (functionCase.operation()) {
            case CONTAINS -> assertThat(regexp.contains(source)).isEqualTo(Boolean.parseBoolean(functionCase.expected()));
            case COUNT -> assertThat(regexp.count(source)).isEqualTo(Long.parseLong(functionCase.expected()));
            case POSITION -> {
                String[] arguments = functionCase.argument().split(",", -1);
                assertThat(regexp.position(source, Long.parseLong(arguments[0]), Long.parseLong(arguments[1])))
                        .isEqualTo(Long.parseLong(functionCase.expected()));
            }
            case EXTRACT -> assertThat(string(regexp.extract(source, Integer.parseInt(functionCase.argument()))))
                    .isEqualTo(decodeNullable(functionCase.expected()));
            case EXTRACT_ALL -> assertThat(strings(regexp.extractAll(source, Integer.parseInt(functionCase.argument()))))
                    .containsExactlyElementsOf(expectedList(functionCase.expected()));
            case SPLIT -> assertThat(strings(regexp.split(source)))
                    .containsExactlyElementsOf(expectedList(functionCase.expected()));
            case REPLACE -> assertThat(regexp.replace(source, utf8(decode(functionCase.argument()))).toStringUtf8())
                    .isEqualTo(decode(functionCase.expected()));
        }
    }

    @Test
    public void testLambdaReplacement()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8("(a)|(b)"));

        assertThat(regexp.replace(utf8("ab"), groups -> {
            if (groups.get(0) != null) {
                return utf8("A");
            }
            return utf8("B");
        }).toStringUtf8()).isEqualTo("AB");

        assertThat(regexp.replace(utf8("a"), _ -> null)).isNull();
    }

    @Test
    public void testExactLiteralOperationsUseDirectNativeBoundaries()
    {
        Slice backing = utf8("!zabcabc?");
        Slice source = Slices.wrappedBuffer(backing.byteArray(), backing.byteArrayOffset() + 1, 7);

        assertExactLiteralOperationDoesNotCreateDfa(regexp -> assertThat(regexp.count(source)).isEqualTo(2));
        assertExactLiteralOperationDoesNotCreateDfa(regexp -> assertThat(regexp.position(source, 1, 2)).isEqualTo(5));
        assertExactLiteralOperationDoesNotCreateDfa(regexp -> assertThat(regexp.extract(source)).isEqualTo(utf8("abc")));
        assertExactLiteralOperationDoesNotCreateDfa(regexp -> assertThat(strings(regexp.extractAll(source))).containsExactly("abc", "abc"));
        assertExactLiteralOperationDoesNotCreateDfa(regexp -> assertThat(strings(regexp.split(source))).containsExactly("z", "", ""));
        assertExactLiteralOperationDoesNotCreateDfa(regexp -> assertThat(regexp.replace(source, utf8("_"))).isEqualTo(utf8("z__")));
        assertExactLiteralOperationDoesNotCreateDfa(regexp -> assertThat(regexp.replace(source, _ -> utf8("_"))).isEqualTo(utf8("z__")));
    }

    @Test
    public void testExactLiteralSpanEdgeCases()
    {
        TrinoRegexp empty = TrinoRegexp.compile(utf8(""));
        Slice multibyte = utf8("a💰");
        assertThat(empty.count(multibyte)).isEqualTo(3);
        assertThat(empty.position(multibyte, 2, 2)).isEqualTo(3);
        assertThat(strings(empty.extractAll(multibyte))).containsExactly("", "", "");
        assertThat(strings(empty.split(multibyte))).containsExactly("", "a", "💰", "");
        assertThat(empty.replace(multibyte, utf8("_"))).isEqualTo(utf8("_a_💰_"));
        assertNoCachedDfa(empty);

        byte[] malformedBacking = {'!', 'a', (byte) 0xFF, 'b', '?'};
        Slice malformed = Slices.wrappedBuffer(malformedBacking, 1, 3);
        TrinoRegexp malformedEmpty = TrinoRegexp.compile(utf8(""));
        assertThat(malformedEmpty.count(malformed)).isEqualTo(4);
        assertThat(malformedEmpty.position(malformed, 1, 4)).isEqualTo(4);
        assertNoCachedDfa(malformedEmpty);

        TrinoRegexp unicode = TrinoRegexp.compile(utf8("💰"));
        Slice unicodeBacking = utf8("!a💰💰?");
        Slice unicodeSource = Slices.wrappedBuffer(
                unicodeBacking.byteArray(),
                unicodeBacking.byteArrayOffset() + 1,
                unicodeBacking.length() - 2);
        assertThat(unicode.position(unicodeSource, 1, 2)).isEqualTo(3);
        assertThat(strings(unicode.extractAll(unicodeSource))).containsExactly("💰", "💰");
        assertNoCachedDfa(unicode);

        TrinoRegexp captured = TrinoRegexp.compile(utf8("(abc)"));
        assertThat(captured.extract(utf8("zabc"), 0)).isEqualTo(utf8("abc"));
        assertNoCachedDfa(captured);
        assertThat(captured.extract(utf8("zabc"), 1)).isEqualTo(utf8("abc"));
        assertThat(captured.replace(utf8("zabc"), utf8("$1$0"))).isEqualTo(utf8("zabcabc"));
        assertThat(captured.replace(utf8("zabc"), groups -> groups.getFirst())).isEqualTo(utf8("zabc"));

        TrinoRegexp absent = TrinoRegexp.compile(utf8("abc"));
        Slice source = utf8("zzz");
        assertThat(absent.count(source)).isZero();
        assertThat(absent.position(source)).isEqualTo(-1);
        assertThat(absent.extract(source)).isNull();
        assertThat(absent.extractAll(source)).isEmpty();
        assertThat(strings(absent.split(source))).containsExactly("zzz");
        assertThat(absent.replace(source, utf8("_"))).isSameAs(source);
        assertThat(absent.replace(source, _ -> utf8("_"))).isSameAs(source);
        assertNoCachedDfa(absent);

        Re2 latin1 = Re2.compile(Slices.wrappedBuffer(new byte[] {(byte) 0xE9}), Re2.Options.latin1());
        Slice latin1Source = Slices.wrappedBuffer(new byte[] {'!', 'x', (byte) 0xE9, '?'}, 1, 2);
        long latin1Span = latin1.findExactLiteralSpan(latin1Source, 0);
        assertThat((int) (latin1Span >>> 32)).isEqualTo(1);
        assertThat((int) latin1Span).isEqualTo(2);
        for (Dfa.DfaInstance.Kind kind : Dfa.DfaInstance.Kind.values()) {
            assertThat(latin1.forwardProgramForDiagnostics().cachedDfaIfPresent(kind)).isNull();
        }
    }

    private static void assertExactLiteralOperationDoesNotCreateDfa(Consumer<TrinoRegexp> operation)
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8("abc"));
        operation.accept(regexp);
        assertNoCachedDfa(regexp);
    }

    @Test
    public void testOrderedLiteralContainsUsesSharedMatcher()
    {
        Slice pattern = utf8("(first)(?s:.*)(second)(?s:.*)(third)");
        Slice backing = utf8("!xxfirst\nsecond💰thirdyy?");
        Slice source = Slices.wrappedBuffer(
                backing.byteArray(),
                backing.byteArrayOffset() + 1,
                backing.length() - 2);
        TrinoRegexp regexp = TrinoRegexp.compile(pattern);

        assertThat(regexp.usesOrderedLiteralMatcherForDiagnostics()).isTrue();
        assertThat(regexp.orderedLiteralMatcherRetainedSizeForDiagnostics()).isPositive();
        assertThat(regexp.contains(source)).isTrue();
        assertThat(regexp.contains(utf8("first third second"))).isFalse();
        assertNoCachedDfa(regexp);

        assertThat(regexp.extract(source, 1)).isEqualTo(utf8("first"));
        assertThat(regexp.extract(source, 2)).isEqualTo(utf8("second"));
        assertThat(regexp.extract(source, 3)).isEqualTo(utf8("third"));
    }

    @Test
    public void testOrderedLiteralContainsEligibility()
    {
        for (String pattern : List.of(
                "first(?s:.*)second",
                "(?s:.*)first(?s:.*)second",
                "first(?s:.*)second(?s:.*)")) {
            assertThat(TrinoRegexp.compile(utf8(pattern)).usesOrderedLiteralMatcherForDiagnostics())
                    .as(pattern)
                    .isTrue();
        }

        for (String pattern : List.of(
                "first.*second",
                "^first(?s:.*)second",
                "first(?s:.*)second$",
                "(?i:first)(?s:.*)second",
                "first.{2}second",
                "first\\b(?s:.*)second",
                "first")) {
            assertThat(TrinoRegexp.compile(utf8(pattern)).usesOrderedLiteralMatcherForDiagnostics())
                    .as(pattern)
                    .isFalse();
        }

        assertThat(TrinoRegexp.compile(utf8("first(?s:.*)second")).contains(utf8("first\nsecond"))).isTrue();
        assertThat(TrinoRegexp.compile(utf8("first.*second")).contains(utf8("first\nsecond"))).isFalse();
    }

    @Test
    public void testLiteralGapContainsKeepsDfaRoute()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8("(alpha)(?s:.)(omega)"));

        assertThat(regexp.usesLiteralGapMatcherForDiagnostics()).isFalse();
        assertThat(regexp.contains(utf8("xxalpha💰omegayy"))).isTrue();
        assertThat(regexp.pattern().booleanPartialMatchStrategyForDiagnostics())
                .isEqualTo(Re2.BooleanPartialMatchStrategy.GENERAL);
        assertThat(regexp.pattern().forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH))
                .as("literal-gap contains route initialized the existing general boolean-search DFA")
                .isNotNull();
        assertThat(regexp.contains(utf8("xxalphaomegayy"))).isFalse();
        assertThat(regexp.contains(utf8("xxalpha💰-omegayy"))).isFalse();
        assertThat(regexp.extract(utf8("xxalpha💰omegayy"), 2)).isEqualTo(utf8("omega"));

        assertThat(TrinoRegexp.compile(utf8("alpha.omega")).usesLiteralGapMatcherForDiagnostics()).isFalse();
        assertThat(TrinoRegexp.compile(utf8("^alpha(?s:.)omega")).usesLiteralGapMatcherForDiagnostics()).isFalse();
        assertThat(TrinoRegexp.compile(utf8("alpha(?s:.)omega$")).usesLiteralGapMatcherForDiagnostics()).isFalse();
        assertThat(TrinoRegexp.compile(utf8("(?i:alpha)(?s:.)omega")).usesLiteralGapMatcherForDiagnostics()).isFalse();
    }

    @Test
    public void testOrderedLiteralMatcherMemoryIsReservedFromForwardDfa()
    {
        Slice pattern = utf8("first(?s:.*)second(?s:.*)third");
        ParseResult parsed = TrinoRegexpParser.parse(pattern, Regexp.LIKE_PERL);
        Re2 baseline = Re2.compileParsedForTrino(pattern, parsed, Regexp.LIKE_PERL, 96L << 20);
        TrinoRegexp regexp = TrinoRegexp.compile(pattern);

        long retainedSize = regexp.orderedLiteralMatcherRetainedSizeForDiagnostics();
        assertThat(regexp.usesOrderedLiteralMatcherForDiagnostics()).isTrue();
        assertThat(baseline.forwardProgramForDiagnostics().dfaMemory() - regexp.pattern().forwardProgramForDiagnostics().dfaMemory())
                .isEqualTo(retainedSize);

        TrinoRegexp fallback = null;
        for (long maxMemory = 512; maxMemory <= 64 * 1024; maxMemory += 64) {
            try {
                TrinoRegexp candidate = TrinoRegexp.compile(pattern, TrinoRegexp.Options.defaults().setMaxMemory(maxMemory));
                if (!candidate.usesOrderedLiteralMatcherForDiagnostics()) {
                    fallback = candidate;
                    break;
                }
            }
            catch (RegexpCompileMemoryLimitException ignored) {
                // Continue until the semantic program fits but the optional matcher does not.
            }
        }
        assertThat(fallback).isNotNull();
        assertThat(fallback.contains(utf8("xxfirst-second-thirdyy"))).isTrue();
    }

    @Test
    public void testOrderedLiteralMatcherConcurrentReuse()
            throws Exception
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8("(first)(?s:.*)(second)"));
        Slice source = utf8("xxfirst\nsecondyy");
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int task = 0; task < 100; task++) {
                futures.add(executor.submit(() ->
                        regexp.contains(source) && regexp.extract(source, 2).equals(utf8("second"))));
            }
            for (Future<Boolean> future : futures) {
                assertThat(future.get()).isTrue();
            }
        }
    }

    @Test
    public void testDotStarLiteralOperationsUseDirectNativeBoundaries()
    {
        Slice backing = utf8("!before\nxxcoolfunctionnameyy\nafter\ncoolfunctionname!?");
        Slice source = Slices.wrappedBuffer(backing.byteArray(), backing.byteArrayOffset() + 1, backing.length() - 2);

        assertDotStarLiteralOperationDoesNotCreateDfa(regexp -> assertThat(regexp.count(source)).isEqualTo(2));
        assertDotStarLiteralOperationDoesNotCreateDfa(regexp -> assertThat(regexp.position(source, 1, 2)).isEqualTo(35));
        assertDotStarLiteralOperationDoesNotCreateDfa(regexp -> assertThat(regexp.extract(source)).isEqualTo(utf8("xxcoolfunctionnameyy")));
        assertDotStarLiteralOperationDoesNotCreateDfa(regexp -> assertThat(strings(regexp.extractAll(source)))
                .containsExactly("xxcoolfunctionnameyy", "coolfunctionname!"));
        assertDotStarLiteralOperationDoesNotCreateDfa(regexp -> assertThat(strings(regexp.split(source)))
                .containsExactly("before\n", "\nafter\n", ""));
        assertDotStarLiteralOperationDoesNotCreateDfa(regexp -> assertThat(regexp.replace(source, utf8("_")))
                .isEqualTo(utf8("before\n_\nafter\n_")));
        assertDotStarLiteralOperationDoesNotCreateDfa(regexp -> assertThat(regexp.replace(source, _ -> utf8("_")))
                .isEqualTo(utf8("before\n_\nafter\n_")));
    }

    private static void assertDotStarLiteralOperationDoesNotCreateDfa(Consumer<TrinoRegexp> operation)
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8(".*coolfunctionname.*"));
        operation.accept(regexp);
        assertNoCachedDfa(regexp);
    }

    @Test
    public void testFixedWidthByteOperationsUseDirectNativeBoundaries()
    {
        Slice dateBacking = utf8("!bad 2026/08/10 then 2025-12-09 and 2024-01-31?");
        Slice dates = Slices.wrappedBuffer(
                dateBacking.byteArray(),
                dateBacking.byteArrayOffset() + 1,
                dateBacking.length() - 2);

        assertFixedWidthByteOperationDoesNotCreateDfa("[0-9]{4}-[0-9]{2}-[0-9]{2}", regexp -> assertThat(regexp.count(dates)).isEqualTo(2));
        assertFixedWidthByteOperationDoesNotCreateDfa("[0-9]{4}-[0-9]{2}-[0-9]{2}", regexp -> assertThat(regexp.position(dates, 1, 2)).isEqualTo(36));
        assertFixedWidthByteOperationDoesNotCreateDfa("[0-9]{4}-[0-9]{2}-[0-9]{2}", regexp -> assertThat(regexp.extract(dates)).isEqualTo(utf8("2025-12-09")));
        assertFixedWidthByteOperationDoesNotCreateDfa("[0-9]{4}-[0-9]{2}-[0-9]{2}", regexp -> assertThat(strings(regexp.extractAll(dates)))
                .containsExactly("2025-12-09", "2024-01-31"));
        assertFixedWidthByteOperationDoesNotCreateDfa("[0-9]{4}-[0-9]{2}-[0-9]{2}", regexp -> assertThat(strings(regexp.split(dates)))
                .containsExactly("bad 2026/08/10 then ", " and ", ""));
        assertFixedWidthByteOperationDoesNotCreateDfa("[0-9]{4}-[0-9]{2}-[0-9]{2}", regexp -> assertThat(regexp.replace(dates, utf8("_")))
                .isEqualTo(utf8("bad 2026/08/10 then _ and _")));
        assertFixedWidthByteOperationDoesNotCreateDfa("[0-9]{4}-[0-9]{2}-[0-9]{2}", regexp -> assertThat(regexp.replace(dates, _ -> utf8("_")))
                .isEqualTo(utf8("bad 2026/08/10 then _ and _")));

        Slice phones = utf8("bad 415-555-0100 then 212/555/0101 or 650/555/0142");
        assertFixedWidthByteOperationDoesNotCreateDfa("\\d{3}/\\d{3}/\\d{4}", regexp -> assertThat(strings(regexp.extractAll(phones)))
                .containsExactly("212/555/0101", "650/555/0142"));
    }

    private static void assertFixedWidthByteOperationDoesNotCreateDfa(String pattern, Consumer<TrinoRegexp> operation)
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8(pattern));
        operation.accept(regexp);
        assertNoCachedDfa(regexp);
    }

    @Test
    public void testLiteralAlternationOperationsUseDirectNativeBoundaries()
    {
        Slice backing = utf8("!info WARN then ERROR and FATAL?");
        Slice source = Slices.wrappedBuffer(
                backing.byteArray(),
                backing.byteArrayOffset() + 1,
                backing.length() - 2);

        assertLiteralAlternationOperationDoesNotCreateDfa(regexp -> assertThat(regexp.contains(source)).isTrue());
        assertLiteralAlternationOperationDoesNotCreateDfa(regexp -> assertThat(regexp.count(source)).isEqualTo(3));
        assertLiteralAlternationOperationDoesNotCreateDfa(regexp -> assertThat(regexp.position(source, 1, 2)).isEqualTo(16));
        assertLiteralAlternationOperationDoesNotCreateDfa(regexp -> assertThat(regexp.extract(source)).isEqualTo(utf8("WARN")));
        assertLiteralAlternationOperationDoesNotCreateDfa(regexp -> assertThat(strings(regexp.extractAll(source)))
                .containsExactly("WARN", "ERROR", "FATAL"));
        assertLiteralAlternationOperationDoesNotCreateDfa(regexp -> assertThat(strings(regexp.split(source)))
                .containsExactly("info ", " then ", " and ", ""));
        assertLiteralAlternationOperationDoesNotCreateDfa(regexp -> assertThat(regexp.replace(source, utf8("_")))
                .isEqualTo(utf8("info _ then _ and _")));
        assertLiteralAlternationOperationDoesNotCreateDfa(regexp -> assertThat(regexp.replace(source, _ -> utf8("_")))
                .isEqualTo(utf8("info _ then _ and _")));
    }

    private static void assertLiteralAlternationOperationDoesNotCreateDfa(Consumer<TrinoRegexp> operation)
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8("ERROR|WARN|FATAL"));
        operation.accept(regexp);
        assertThat(regexp.pattern()).isNull();
    }

    @Test
    public void testDotStarLiteralSpanEdgeCases()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8(".*coolfunctionname.*"));
        Slice backing = utf8("!é💰coolfunctionnameж終?");
        Slice source = Slices.wrappedBuffer(backing.byteArray(), backing.byteArrayOffset() + 1, backing.length() - 2);
        assertThat(regexp.extract(source)).isEqualTo(source);
        assertThat(regexp.position(source)).isEqualTo(1);
        assertNoCachedDfa(regexp);

        TrinoRegexp absent = TrinoRegexp.compile(utf8(".*coolfunctionname.*"));
        assertThat(absent.extract(utf8("before\nafter"))).isNull();
        assertThat(absent.replace(utf8("before\nafter"), utf8("_"))).isEqualTo(utf8("before\nafter"));
        assertNoCachedDfa(absent);

        TrinoRegexp dotAll = TrinoRegexp.compile(utf8("(?s:.*coolfunctionname.*)"));
        assertThat(dotAll.extract(utf8("before\ncoolfunctionname\nafter")))
                .isEqualTo(utf8("before\ncoolfunctionname\nafter"));

        TrinoRegexp lazySuffix = TrinoRegexp.compile(utf8(".*coolfunctionname.*?"));
        assertThat(lazySuffix.extract(utf8("prefix-coolfunctionname-suffix")))
                .isEqualTo(utf8("prefix-coolfunctionname"));

        TrinoRegexp captured = TrinoRegexp.compile(utf8("(.*coolfunctionname.*)"));
        assertThat(captured.extract(utf8("before\ncoolfunctionname!\nafter"), 1))
                .isEqualTo(utf8("coolfunctionname!"));
        assertThat(captured.replace(utf8("coolfunctionname!"), utf8("$1")))
                .isEqualTo(utf8("coolfunctionname!"));
        assertThat(captured.replace(utf8("coolfunctionname!"), groups -> groups.getFirst()))
                .isEqualTo(utf8("coolfunctionname!"));
    }

    @Test
    public void testCharacterClassOperationsUseDirectNativeBoundaries()
    {
        Slice backing = utf8("!abéж 12 cdefgh?");
        Slice source = Slices.wrappedBuffer(backing.byteArray(), backing.byteArrayOffset() + 1, backing.length() - 2);

        assertCharacterClassOperationDoesNotCreateDfa("\\p{L}{2,4}", regexp -> assertThat(regexp.count(source)).isEqualTo(3));
        assertCharacterClassOperationDoesNotCreateDfa("\\p{L}{2,4}", regexp -> assertThat(regexp.position(source, 1, 2)).isEqualTo(9));
        assertCharacterClassOperationDoesNotCreateDfa("\\p{L}{2,4}", regexp -> assertThat(regexp.extract(source)).isEqualTo(utf8("abéж")));
        assertCharacterClassOperationDoesNotCreateDfa("\\p{L}{2,4}", regexp -> assertThat(strings(regexp.extractAll(source))).containsExactly("abéж", "cdef", "gh"));
        assertCharacterClassOperationDoesNotCreateDfa("\\p{L}{2,4}", regexp -> assertThat(strings(regexp.split(source))).containsExactly("", " 12 ", "", ""));
        assertCharacterClassOperationDoesNotCreateDfa("\\p{L}{2,4}", regexp -> assertThat(regexp.replace(source, utf8("_"))).isEqualTo(utf8("_ 12 __")));
        assertCharacterClassOperationDoesNotCreateDfa("\\p{L}{2,4}", regexp -> assertThat(regexp.replace(source, _ -> utf8("_"))).isEqualTo(utf8("_ 12 __")));
        assertCharacterClassOperationDoesNotCreateDfa("\\p{L}+", regexp -> assertThat(strings(regexp.extractAll(utf8("éж  cdef")))).containsExactly("éж", "cdef"));
        assertCharacterClassOperationDoesNotCreateDfa("\\s+", regexp -> assertThat(strings(regexp.extractAll(utf8("a \t b")))).containsExactly(" \t "));
    }

    @Test
    public void testLargeUnicodeRepeatUsesCompactProgram()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8("\\p{L}{256}"));
        assertThat(regexp.pattern().usesCompactBoundedCharacterClassForDiagnostics()).isTrue();

        Slice source = utf8("!" + "δ".repeat(300) + "?" + "a".repeat(256));
        assertThat(regexp.contains(source)).isTrue();
        assertThat(regexp.count(source)).isEqualTo(2);
        assertThat(regexp.position(source)).isEqualTo(2);
        assertThat(regexp.extract(source)).isEqualTo(utf8("δ".repeat(256)));
        assertThat(strings(regexp.extractAll(source)))
                .containsExactly("δ".repeat(256), "a".repeat(256));
        assertNoCachedDfa(regexp);
    }

    @Test
    public void testUnboundedUnicodeRepeatUsesCompactProgram()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8("\\p{L}+"));
        assertThat(regexp.pattern().usesCompactBoundedCharacterClassForDiagnostics()).isTrue();

        Slice source = utf8("1δé!abc?");
        assertThat(regexp.contains(source)).isTrue();
        assertThat(regexp.count(source)).isEqualTo(2);
        assertThat(regexp.position(source)).isEqualTo(2);
        assertThat(regexp.extract(source)).isEqualTo(utf8("δé"));
        assertThat(strings(regexp.extractAll(source))).containsExactly("δé", "abc");
        assertNoCachedDfa(regexp);
    }

    private static void assertCharacterClassOperationDoesNotCreateDfa(String pattern, Consumer<TrinoRegexp> operation)
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8(pattern));
        operation.accept(regexp);
        assertNoCachedDfa(regexp);
    }

    private static void assertNoCachedDfa(TrinoRegexp regexp)
    {
        for (Dfa.DfaInstance.Kind kind : Dfa.DfaInstance.Kind.values()) {
            assertThat(regexp.pattern().forwardProgramForDiagnostics().cachedDfaIfPresent(kind))
                    .as("direct boundary operation initialized %s DFA", kind)
                    .isNull();
        }
        assertThat(regexp.pattern().isReverseProgramComputed()).isFalse();
    }

    @Test
    public void testSingleByteRepeatOperations()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8("x*"));
        Slice source = utf8("axxa💰");

        assertThat(regexp.count(source)).isEqualTo(5);
        assertThat(strings(regexp.extractAll(source))).containsExactly("", "xx", "", "", "");
        assertThat(strings(regexp.split(source))).containsExactly("", "a", "", "a", "💰", "");
        assertThat(regexp.replace(source, utf8("_")).toStringUtf8()).isEqualTo("_a__a_💰_");
        assertThat(regexp.replace(source, utf8("$0")).toStringUtf8()).isEqualTo("axxa💰");
        assertThat(regexp.replace(source, groups -> {
            assertThat(groups).isEmpty();
            return utf8("_");
        }).toStringUtf8()).isEqualTo("_a__a_💰_");

        assertThat(regexp.position(source, 1, 1)).isEqualTo(1);
        assertThat(regexp.position(source, 1, 2)).isEqualTo(2);
        assertThat(regexp.position(source, 1, 3)).isEqualTo(4);
        assertThat(regexp.position(source, 1, 4)).isEqualTo(5);
        assertThat(regexp.position(source, 1, 5)).isEqualTo(6);
        assertThat(regexp.position(source, 1, 6)).isEqualTo(-1);
        assertThat(regexp.position(source, Long.MAX_VALUE, 1)).isEqualTo(-1);

        assertThatThrownBy(() -> regexp.replace(source, utf8("$1")))
                .isInstanceOf(TrinoRegexpReplacementException.class)
                .extracting(throwable -> ((TrinoRegexpReplacementException) throwable).byteOffset())
                .isEqualTo(0);
        assertThatThrownBy(() -> regexp.replace(source, utf8("$1")))
                .hasMessageContaining("unknown group");
        assertThatThrownBy(() -> regexp.replace(source, utf8("${missing}")))
                .isInstanceOf(TrinoRegexpReplacementException.class)
                .hasMessageContaining("unknown named group");

        TrinoRegexp captured = TrinoRegexp.compile(utf8("(x*)"));
        assertThat(strings(captured.extractAll(source, 1))).containsExactly("", "xx", "", "", "");
    }

    @Test
    public void testSingleByteRepeatAnalysisEligibility()
    {
        TrinoRegexp nonNullable = TrinoRegexp.compile(utf8("[a-z]+"));
        assertThat(nonNullable.isSingleByteRepeatMatcherComputed()).isFalse();
        assertThat(nonNullable.count(utf8("abc"))).isEqualTo(1);
        assertThat(nonNullable.isSingleByteRepeatMatcherComputed()).isFalse();

        TrinoRegexp nullableRepeat = TrinoRegexp.compile(utf8("x*"));
        assertThat(nullableRepeat.isSingleByteRepeatMatcherComputed()).isFalse();
        assertThat(nullableRepeat.count(utf8("ax"))).isEqualTo(3);
        assertThat(nullableRepeat.isSingleByteRepeatMatcherComputed()).isTrue();
    }

    @Test
    public void testFullCaptureMatcherDoesNotAnalyzeSingleBytePattern()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8("(a)"));
        assertThat(regexp.isSingleByteMatcherComputed()).isFalse();

        assertThat(regexp.extract(utf8("a"), 1)).isEqualTo(utf8("a"));
        assertThat(regexp.isSingleByteMatcherComputed()).isFalse();
    }

    @Test
    public void testContainsInitializesOnlyEligibleSingleByteMatcher()
    {
        TrinoRegexp delimiter = TrinoRegexp.compile(utf8("[,;]"));
        assertThat(delimiter.isSingleByteMatcherComputed()).isFalse();
        assertThat(delimiter.contains(utf8("abc;def"))).isTrue();
        assertThat(delimiter.isSingleByteMatcherComputed()).isTrue();
        assertThat(delimiter.contains(Slices.wrappedBuffer(utf8("xxabc;defyy").getBytes(), 2, 7))).isTrue();
        assertThat(delimiter.contains(utf8("abcdef"))).isFalse();
        assertThatThrownBy(() -> delimiter.contains(null)).isInstanceOf(NullPointerException.class);

        TrinoRegexp captures = TrinoRegexp.compile(utf8("([a-z]+)-([0-9]+)"));
        assertThat(captures.contains(utf8("abc-123"))).isTrue();
        assertThat(captures.isSingleByteMatcherComputed()).isFalse();
        assertThatThrownBy(() -> captures.contains(null)).isInstanceOf(NullPointerException.class);

        for (String pattern : new String[] {"a$", "\\ba\\b"}) {
            TrinoRegexp unsupported = TrinoRegexp.compile(utf8(pattern));
            assertThat(unsupported.contains(utf8("a"))).isTrue();
            assertThat(unsupported.contains(utf8("bbb"))).isFalse();
            assertThat(unsupported.isSingleByteMatcherComputed()).isFalse();
        }
    }

    @Test
    public void testCapturedSingleByteRepeatUsesBoundaryMatcherWhenCapturesAreNotNeeded()
    {
        Slice source = utf8("axx");

        TrinoRegexp position = TrinoRegexp.compile(utf8("(x*)"));
        assertThat(position.isSingleByteRepeatMatcherComputed()).isFalse();
        assertThat(position.position(source, 1, 2)).isEqualTo(2);
        assertThat(position.isSingleByteRepeatMatcherComputed()).isTrue();

        TrinoRegexp groupZero = TrinoRegexp.compile(utf8("(x*)"));
        assertThat(strings(groupZero.extractAll(source, 0))).containsExactly("", "xx", "");
        assertThat(groupZero.isSingleByteRepeatMatcherComputed()).isTrue();

        TrinoRegexp capturedGroup = TrinoRegexp.compile(utf8("(x*)"));
        assertThat(strings(capturedGroup.extractAll(source, 1))).containsExactly("", "xx", "");
        assertThat(capturedGroup.isSingleByteRepeatMatcherComputed()).isFalse();

        TrinoRegexp split = TrinoRegexp.compile(utf8("(x*)"));
        assertThat(strings(split.split(source))).containsExactly("", "a", "", "");
        assertThat(split.isSingleByteRepeatMatcherComputed()).isTrue();

        TrinoRegexp groupZeroReplacement = TrinoRegexp.compile(utf8("(x*)"));
        assertThat(groupZeroReplacement.replace(source, utf8("$0"))).isEqualTo(source);
        assertThat(groupZeroReplacement.isSingleByteRepeatMatcherComputed()).isTrue();

        TrinoRegexp capturedReplacement = TrinoRegexp.compile(utf8("(x*)"));
        assertThat(capturedReplacement.replace(source, utf8("$1"))).isEqualTo(source);
        assertThat(capturedReplacement.isSingleByteRepeatMatcherComputed()).isFalse();

        TrinoRegexp lambdaReplacement = TrinoRegexp.compile(utf8("(x*)"));
        assertThat(lambdaReplacement.replace(source, _ -> utf8("_"))).isEqualTo(utf8("_a__"));
        assertThat(lambdaReplacement.isSingleByteRepeatMatcherComputed()).isFalse();
    }

    @Test
    public void testSingleByteRepeatOperationsWithMalformedUtf8AndSliceOffset()
    {
        byte[] malformedBacking = {
                '!', 'a', (byte) 0xED, (byte) 0xA0, (byte) 0x80, 'x', 'x', (byte) 0xFF, '?',
        };
        assertSingleByteRepeatOperationsMatchGeneralMatcher(
                utf8("x*"),
                Slices.wrappedBuffer(malformedBacking, 1, malformedBacking.length - 2));
    }

    @Test
    public void testReplacementValidation()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8("(?<name>x)"));

        assertThatThrownBy(() -> regexp.replace(utf8("x"), utf8("\\")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regexp.replace(utf8("x"), utf8("$")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regexp.replace(utf8("x"), utf8("${missing}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown named group");
    }

    @Test
    public void testUnknownNamedReplacementOffsetAcrossRoutes()
    {
        Slice source = utf8("a");
        for (String expression : List.of("a", "a+", "(a)", "(?<name>a)")) {
            TrinoRegexp regexp = TrinoRegexp.compile(utf8(expression));
            assertThatThrownBy(() -> regexp.replace(source, utf8("é${missing}")))
                    .isInstanceOfSatisfying(TrinoRegexpReplacementException.class, exception ->
                            assertThat(exception.byteOffset()).isEqualTo(2));
            assertThat(regexp.replace(utf8("z"), utf8("${missing}"))).isEqualTo(utf8("z"));
        }
        assertThat(TrinoRegexp.compile(utf8("(?<name>a)?b")).replace(utf8("b"), utf8("_${name}_")))
                .isEqualTo(utf8("__"));
    }

    @Test
    public void testReplacementCapturingGroupRequirement()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8("(?<name>x)"));

        assertThat(regexp.replacementNeedsCapturingGroups(utf8("_"))).isFalse();
        assertThat(regexp.replacementNeedsCapturingGroups(utf8("$0"))).isFalse();
        assertThat(regexp.replacementNeedsCapturingGroups(utf8("$00"))).isFalse();
        assertThat(regexp.replacementNeedsCapturingGroups(utf8("\\$1"))).isFalse();
        assertThat(regexp.replacementNeedsCapturingGroups(utf8("$1"))).isTrue();
        assertThat(regexp.replacementNeedsCapturingGroups(utf8("$01"))).isTrue();
        assertThat(regexp.replacementNeedsCapturingGroups(utf8("${name}"))).isTrue();
        assertThat(regexp.replacementNeedsCapturingGroups(utf8("$"))).isTrue();
    }

    @Test
    public void testGroupValidationAndNonzeroSliceOffset()
    {
        TrinoRegexp regexp = TrinoRegexp.compile(utf8("(abc)"));
        byte[] bytes = utf8("xxabcxx").getBytes();
        Slice source = Slices.wrappedBuffer(bytes, 2, 3);

        assertThat(regexp.extract(source, 1).toStringUtf8()).isEqualTo("abc");
        assertThatThrownBy(() -> regexp.extract(source, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regexp.extract(source, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testMalformedUtf8IsNotValidated()
    {
        Slice malformed = Slices.wrappedBuffer(new byte[] {'A', (byte) 0xED, (byte) 0xA0, (byte) 0x80, 'B'});

        assertThat(TrinoRegexp.compile(malformed).contains(malformed)).isTrue();
        assertThat(TrinoRegexp.compile(utf8("")).count(malformed)).isGreaterThan(0);
    }

    @Test
    public void testForwardOnlyCountEligibility()
    {
        Re2 delimiter = TrinoRegexp.compile(utf8(",")).pattern();
        assertThat(delimiter.countMatches(utf8("a,b,,c"))).isEqualTo(3);

        Re2 anchored = TrinoRegexp.compile(utf8("^a")).pattern();
        assertThat(anchored.countMatches(utf8("aa"))).isEqualTo(1);

        Re2 nullable = TrinoRegexp.compile(utf8("x*")).pattern();
        assertThat(nullable.countMatches(utf8("xxx"))).isEqualTo(-1);
    }

    @Test
    public void testSingleByteCountEligibility()
    {
        assertThat(countSingleByteMatches("[,;]", utf8("a,b;c;;"))).isEqualTo(4);
        assertThat(countSingleByteMatches("([,;])", utf8("a,b;c;;"))).isEqualTo(4);
        assertThat(countSingleByteMatches("(?i)a", utf8("aAbA"))).isEqualTo(3);
        assertThat(countSingleByteMatches("\\C", Slices.wrappedBuffer(new byte[] {'a', (byte) 0xFF, 'b'}))).isEqualTo(3);

        assertThat(countSingleByteMatches("a+", utf8("aaa"))).isEqualTo(-1);
        assertThat(countSingleByteMatches(".", utf8("a💰"))).isEqualTo(-1);
        assertThat(countSingleByteMatches("^a", utf8("aa"))).isEqualTo(-1);
        assertThat(countSingleByteMatches("^ab[,;]", utf8("ab,xx;"))).isEqualTo(-1);
        assertThat(TrinoRegexp.compile(utf8("^ab[,;]")).count(utf8("ab,xx;"))).isEqualTo(1);

        SingleByteMatcher matcher = Re2.compile(utf8("[,;]")).createSingleByteMatcher();
        Slice slice = Slices.wrappedBuffer(utf8("xxa,b;cyy").getBytes(), 2, 5);
        assertThat(matcher.find(slice, 0)).isEqualTo(1);
        assertThat(matcher.find(slice, 2)).isEqualTo(3);
        assertThat(matcher.find(slice, 4)).isEqualTo(-1);
    }

    @Test
    public void testSingleByteCountAnalysisIsStackSafe()
            throws InterruptedException
    {
        int captureCount = 10_000;
        String pattern = "(".repeat(captureCount) + "a" + ")".repeat(captureCount);
        AtomicReference<Long> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread compilerThread = new Thread(null, () -> {
            try {
                SingleByteMatcher matcher = Re2.compile(utf8(pattern)).createSingleByteMatcher();
                result.set(matcher == null ? -1 : matcher.count(utf8("aba")));
            }
            catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "single-byte-matcher-small-stack", 256 * 1024);

        compilerThread.start();
        compilerThread.join();

        assertThat(failure.get()).isNull();
        assertThat(result.get()).isEqualTo(2);
    }

    private static long countSingleByteMatches(String pattern, Slice source)
    {
        SingleByteMatcher matcher = Re2.compile(utf8(pattern)).createSingleByteMatcher();
        return matcher == null ? -1 : matcher.count(source);
    }

    private static void assertSingleByteRepeatOperationsMatchGeneralMatcher(Slice pattern, Slice source)
    {
        TrinoRegexp regexp = TrinoRegexp.compile(pattern);
        Re2Matcher matcher = regexp.pattern().matcher(source);
        List<MatchBoundary> matches = new ArrayList<>();
        List<Slice> groups = new ArrayList<>();
        while (matcher.find()) {
            matches.add(new MatchBoundary(matcher.start(), matcher.end()));
            groups.add(matcher.group());
        }

        assertThat(regexp.count(source)).isEqualTo(matches.size());
        for (int occurrence = 0; occurrence < matches.size(); occurrence++) {
            assertThat(regexp.position(source, 1, occurrence + 1L))
                    .isEqualTo(SliceUtf8.countCodePoints(source, 0, matches.get(occurrence).start()) + 1L);
        }
        assertThat(regexp.position(source, 1, matches.size() + 1L)).isEqualTo(-1);

        int byteStart = SliceUtf8.offsetOfCodePoint(source, 1);
        Re2Matcher offsetMatcher = regexp.pattern().matcher(source);
        long expectedOffsetPosition = offsetMatcher.find(byteStart)
                ? SliceUtf8.countCodePoints(source, 0, offsetMatcher.start()) + 1L
                : -1;
        assertThat(regexp.position(source, 2, 1)).isEqualTo(expectedOffsetPosition);
        assertThat(regexp.extractAll(source)).containsExactlyElementsOf(groups);

        List<Slice> expectedParts = new ArrayList<>();
        DynamicSliceOutput expectedReplacement = new DynamicSliceOutput(source.length() + matches.size());
        int previousEnd = 0;
        for (MatchBoundary match : matches) {
            expectedParts.add(source.slice(previousEnd, match.start() - previousEnd));
            expectedReplacement.writeBytes(source, previousEnd, match.start() - previousEnd);
            expectedReplacement.writeByte('_');
            previousEnd = match.end();
        }
        expectedParts.add(source.slice(previousEnd, source.length() - previousEnd));
        expectedReplacement.writeBytes(source, previousEnd, source.length() - previousEnd);

        assertThat(regexp.split(source)).containsExactlyElementsOf(expectedParts);
        assertThat(regexp.replace(source, utf8("_"))).isEqualTo(expectedReplacement.slice());
        assertThat(regexp.replace(source, _ -> utf8("_"))).isEqualTo(expectedReplacement.slice());
        assertThat(regexp.isSingleByteRepeatMatcherComputed()).isTrue();
    }

    @Test
    public void testForwardOnlyCountMatchesMatcherIteration()
    {
        List<String> patterns = List.of(
                "a",
                "a+",
                "ab|cd",
                "(a)b",
                "[a-z]+",
                "^a",
                "a$",
                "^a$",
                "a.*?b",
                "(?i)abc",
                "\\bword\\b",
                "世+");
        List<String> sources = List.of(
                "",
                "a",
                "aaabacda",
                "abc ABC abc",
                "word sword word",
                "世界世世");

        for (String pattern : patterns) {
            Re2 re2 = Re2.compile(utf8(pattern));
            assertThat(re2.canMatchEmpty()).as("pattern %s", pattern).isFalse();
            for (String source : sources) {
                Slice input = utf8(source);
                long expectedCount = 0;
                Re2Matcher matcher = re2.matcher(input);
                while (matcher.find()) {
                    expectedCount++;
                }
                assertThat(re2.countMatches(input))
                        .as("pattern %s, source %s", pattern, source)
                        .isEqualTo(expectedCount);
            }
        }
    }

    private static List<FunctionCase> functionCases()
    {
        return TsvTestData.load(FUNCTION_CASES, 5, 109).stream()
                .map(row -> new FunctionCase(
                        Operation.valueOf(row.field(0)),
                        row.field(1),
                        row.field(2),
                        row.field(3),
                        row.field(4)))
                .toList();
    }

    private static List<String> expectedList(String value)
    {
        return Stream.of(value.split("\\|", -1))
                .map(TestTrinoRegexpFunctions::decodeNullable)
                .toList();
    }

    private static List<String> strings(List<Slice> values)
    {
        return values.stream()
                .map(TestTrinoRegexpFunctions::string)
                .toList();
    }

    private static String string(Slice value)
    {
        return value == null ? null : value.toStringUtf8();
    }

    private static String decodeNullable(String value)
    {
        return value.equals("<null>") ? null : decode(value);
    }

    private static String decode(String value)
    {
        if (value.equals("<empty>")) {
            return "";
        }
        return value.replace("<newline>", "\n");
    }

    private static Slice utf8(String value)
    {
        return Slices.utf8Slice(value);
    }

    private enum Operation
    {
        CONTAINS,
        COUNT,
        POSITION,
        EXTRACT,
        EXTRACT_ALL,
        SPLIT,
        REPLACE,
    }

    private record FunctionCase(Operation operation, String pattern, String source, String argument, String expected) {}

    private record MatchBoundary(int start, int end) {}
}
