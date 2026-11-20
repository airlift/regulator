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

import io.airlift.regulator.Dfa.CacheTables;
import io.airlift.regulator.Dfa.DfaInstance;
import io.airlift.regulator.Dfa.StateData;
import io.airlift.regulator.LiteralAlternationSpanMatcher.ExactMatcher;
import io.airlift.regulator.LiteralAlternationSpanMatcher.FoldedMatcher;
import io.airlift.regulator.ScanKernelGenerator.PatternRoutes;
import io.airlift.regulator.ScanKernelGenerator.TrinoRoutes;
import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.airlift.regulator.TestScanKernel.classfile;
import static io.airlift.regulator.TestScanKernel.patternClassfile;
import static io.airlift.regulator.TestScanPlan.URL;
import static io.airlift.slice.Slices.utf8Slice;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

public class TestHotMethodSize
{
    // C2 inlines a hot callee only when its bytecode is at most FreqInlineSize, 325 bytes by
    // default on x86-64, AArch64 and RISC-V. PPC64 and s390x use 175 through JDK 26.
    private static final int FREQ_INLINE_SIZE = 325;
    // C2 inlines a callee of at most MaxInlineSize, 35 bytecode bytes, at a call site that is not
    // hot, unless it judges the site too rarely reached.
    private static final int MAX_INLINE_SIZE = 35;
    // C2 inlines a callee of at most MaxTrivialSize, 6 bytecode bytes, even at a call site it
    // judges too rarely reached to inline anything larger.
    private static final int MAX_TRIVIAL_SIZE = 6;
    // The byte-array overload of the start state lookup every DFA search runs.
    private static final String ANALYZE_START = MethodType.methodType(StateData.class, CacheTables.class, byte[].class, int.class, int.class, int.class, int.class, boolean.class, boolean.class).toMethodDescriptorString();

    /// Keeps each full-Slice find, count, matches, and lookingAt route small enough for C2 to
    /// inline into the one-line override of the pattern's shape class, and that override into
    /// callers such as `Re2.count` and `TrinoRegexp.count`.
    @Test
    public void testRoutesFitInlineSize()
    {
        for (String route : List.of(
                "findOnEngine",
                "findOnExactLiteral",
                "findOnLiteralKernel",
                "findOnLiteralAlternation",
                "findOnFixedWidth",
                "findOnClassRun",
                "findOnByteTable",
                "findOnScanPlan",
                "matchesOnExactLiteral",
                "lookingAtOnExactLiteral",
                "matchesOnLiteralKernel",
                "lookingAtOnLiteralKernel",
                "matchesOnScanPlan",
                "lookingAtOnScanPlan",
                "matchOnScanPlan",
                "countOnEngine",
                "countOnExactLiteral",
                "countOnLiteralKernel",
                "countOnLiteralAlternation",
                "countOnFixedWidth",
                "countOnClassRun",
                "countOnByteTable",
                "countOnScanPlan")) {
            assertThat(codeLength(CompiledPattern.class, route))
                    .as("CompiledPattern.%s bytecode bytes", route)
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        }
    }

    /// Keeps every RegexpMatcher constructor under the C2 hot-method inline limit. Trino builds a
    /// new matcher for each row, so a constructor C2 will not inline puts a call on every row.
    @Test
    public void testMatcherConstructorsFitInlineSize()
    {
        assertThat(codeLengths(RegexpMatcher.class, "<init>"))
                .as("RegexpMatcher constructor bytecode bytes")
                .isNotEmpty()
                .allSatisfy(length -> assertThat(length).isPositive().isLessThanOrEqualTo(FREQ_INLINE_SIZE));
    }

    /// Keeps the matcher's span search dispatch small enough for C2 to inline into
    /// `RegexpMatcher.find`, which runs it on every find of a pattern with a group-zero span
    /// search, such as a literal alternation iterated over a row.
    @Test
    public void testMatcherSpanSearchFitsInlineSize()
    {
        assertThat(codeLength(RegexpMatcher.class, "findSpanSearch"))
                .as("RegexpMatcher.findSpanSearch bytecode bytes")
                .isPositive()
                .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
    }

    /// Keeps the word-run plan's searches and boundary check small enough for C2 to inline into the
    /// pre-engine plan dispatch, which runs once per match when a matcher iterates over dense
    /// `\b\w+\b` matches.
    @Test
    public void testWordRunSearchFitsInlineSize()
    {
        for (String method : List.of("search", "searchLongRuns", "boundaryAt")) {
            assertThat(codeLength(WordRunMatcher.class, method))
                    .as("WordRunMatcher.%s bytecode bytes", method)
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        }
    }

    /// Keeps the gated and ungated inner literal loops within C2's hot-method inline size.
    @Test
    public void testInnerLiteralSearchesFitInlineSize()
    {
        for (String method : List.of("searchGatedFrom", "searchUngated")) {
            assertThat(codeLength(InnerLiteralSearch.class, method))
                    .as("InnerLiteralSearch.%s bytecode bytes", method)
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        }
    }

    /// Keeps the large literal set's trie walks, window searches, and sparse transition small
    /// enough for C2 to inline into the search that picks them and, for the transition, into each
    /// walk's loop.
    @Test
    public void testLiteralSetSearchFitsInlineSize()
    {
        for (String method : List.of("walkTrie", "walkTrieSkippingRootBytes", "walkPrefix", "skipAhead", "skipAheadScanningRootBytes", "sparseTransition")) {
            assertThat(codeLength(LiteralSetSpanMatcher.class, method))
                    .as("LiteralSetSpanMatcher.%s bytecode bytes", method)
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        }
    }

    /// Keeps the fixed-width matcher's searches small enough for C2 to inline into its count and
    /// span entries, which the compiled pattern's fixed-width routes inline in turn: a short row
    /// searches once, and a find over long text once for each match.
    @Test
    public void testFixedWidthSearchesFitInlineSize()
    {
        for (String method : List.of("searchBytes", "searchCandidateByte", "countCandidateBytes", "searchCharacters", "searchLongBytes", "searchLongCharacters")) {
            assertThat(codeLength(FixedWidthByteSpanMatcher.class, method))
                    .as("FixedWidthByteSpanMatcher.%s bytecode bytes", method)
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        }
    }

    /// Keeps each public entry of a generated pattern class small enough for C2 to inline into a
    /// caller that holds the pattern as a constant; the attempt loop and searches stay static
    /// calls. The patterns cover start-anchored and unanchored plans, a search that hands off to
    /// the ordinary engine, retries, and several groups.
    @Test
    public void testGeneratedEntriesFitInlineSize()
    {
        for (String expression : List.of(
                URL,
                "^https?://[^/]+/",
                "[?&]([^=]+)=",
                "([^/]+)/([^!]+)!x",
                "^((a)?(b)?(c)?(d)?)abcd([^/]+)/$",
                "^(a)",
                "x([^/]+)")) {
            TrinoRegexp trino = TrinoRegexp.compile(utf8Slice(expression), TrinoRegexp.Options.defaults().setGenerateBytecode(true));
            assertThat(trino.usesGeneratedBytecode()).as(expression).isTrue();
            assertEntriesFitInlineSize("Trino " + expression, ScanKernelGenerator.trinoClassfileForDiagnostics(TrinoRoutes.of(trino.re2())));
            Re2 re2 = Re2.compile(utf8Slice(expression), Re2.Options.defaults().setGenerateBytecode(true));
            JavaRegexp java = JavaRegexp.compile(utf8Slice(expression), JavaRegexp.Options.defaults().setGenerateBytecode(true));
            RustRegexp rust = RustRegexp.compile(utf8Slice(expression), RustRegexp.Options.defaults().setGenerateBytecode(true));
            for (CompiledPattern pattern : List.of(re2, java, rust)) {
                String description = pattern.getClass().getSimpleName() + " " + expression;
                assertThat(pattern.usesGeneratedBytecode()).as(description).isTrue();
                assertEntriesFitInlineSize(description, patternClassfile(PatternRoutes.of(pattern)));
            }
        }
    }

    /// Keeps the generated `matches` of a LIKE pattern small enough for C2 to inline into a
    /// caller that holds the pattern as a constant, with the longest prefix and suffix that it
    /// compares inline on both sides of a middle.
    @Test
    public void testGeneratedLikeMatchesFitsInlineSize()
    {
        for (String pattern : List.of(
                "",
                "abcdefghijklmnop",
                "abcdefghijklmnopq",
                "abcdefghijklmnop%abcdefghijklmnop",
                "abcdefghijklmnop%needle%abcdefghijklmnop",
                "abcdefghijklmnop%ab_c%abcdefghijklmnop",
                "abcdefghijklmnop%a_%b%abcdefghijklmnop",
                "abcdefghijklmnop_abcdefghijklmnop",
                "abcdefghijklmnopq%abcdefghijklmnopq")) {
            TrinoLikePattern compiled = TrinoLikePattern.compile(utf8Slice(pattern));
            for (boolean aarch64 : new boolean[] {false, true}) {
                assertEntriesFitInlineSize("LIKE " + pattern + " aarch64=" + aarch64, TrinoLikeKernelGenerator.classfileForDiagnostics(compiled.kernelForDiagnostics(), aarch64));
            }
        }
    }

    /// Keeps each literal search small enough for C2 to inline:
    /// `LiteralSearchKernel.findSingleByte` into matchers such as InnerLiteralSearch,
    /// OrderedLiteralMatcher, and LiteralGapMatcher, `skipToContainsCandidate` into `findContains`,
    /// the generated searches of a LIKE pattern into its `matchesMiddle`, and the finish of each
    /// one-byte scan into the scan. Candidate verification is outside this size check.
    @Test
    public void testLiteralSearchesFitInlineSize()
    {
        for (String method : List.of("findSingleByte", "findSingleByteTail", "skipToContainsCandidate")) {
            assertThat(codeLength(LiteralSearchKernel.class, method))
                    .as("LiteralSearchKernel.%s bytecode bytes", method)
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        }
        for (String pattern : List.of("%x%", "%needle%")) {
            TrinoLikePattern compiled = TrinoLikePattern.compile(utf8Slice(pattern));
            for (boolean aarch64 : new boolean[] {false, true}) {
                assertMethodsFitInlineSize(
                        "LIKE " + pattern + " aarch64=" + aarch64,
                        "searches",
                        TrinoLikeKernelGenerator.classfileForDiagnostics(compiled.kernelForDiagnostics(), aarch64),
                        method -> method.methodName().stringValue().startsWith("find"));
            }
        }
    }

    private static void assertEntriesFitInlineSize(String description, byte[] classfile)
    {
        assertMethodsFitInlineSize(
                description,
                "entries",
                classfile,
                method -> method.flags().has(AccessFlag.PUBLIC) && !method.flags().has(AccessFlag.STATIC) && !method.methodName().equalsString("<init>"));
    }

    private static void assertMethodsFitInlineSize(String description, String kind, byte[] classfile, Predicate<MethodModel> filter)
    {
        List<MethodModel> methods = ClassFile.of().parse(classfile).methods().stream()
                .filter(filter)
                .toList();
        assertThat(methods).as("%s %s", description, kind).isNotEmpty();
        for (MethodModel method : methods) {
            assertThat(method.findAttribute(Attributes.code()).map(CodeAttribute::codeLength).orElse(0))
                    .as("%s %s bytecode bytes", description, method.methodName())
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        }
    }

    /// Keeps the class counter's span search small enough for C2 to inline into class-run callers,
    /// such as Trino's class-run extract and position.
    @Test
    public void testClassRunSpanSearchFitsInlineSize()
    {
        assertThat(codeLength(BoundedCharacterClassCounter.class, "findSpan"))
                .as("BoundedCharacterClassCounter.findSpan bytecode bytes")
                .isPositive()
                .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
    }

    /// Keeps the dot-star literal matcher's span searches and the walks behind them, for dots that
    /// stop only at a line feed and for dots with other stops, small enough for C2 to inline into
    /// the literal-kernel searches, the Trino searches, and the group-zero matcher finds that call
    /// them, so the contains search inlines beneath them.
    @Test
    public void testDotStarLiteralSpanSearchFitsInlineSize()
    {
        for (Class<?> type : List.of(DotStarLiteralSpanMatcher.class, DotStarLiteralSpanMatcher.MultipleStops.class)) {
            String walk = type == DotStarLiteralSpanMatcher.class ? "findSpanWithinLine" : "findSpanBetweenStops";
            for (String method : List.of("findSpan", "findSpanForDiagnostics", walk)) {
                assertThat(codeLengths(type, method))
                        .as("%s.%s bytecode bytes", type.getSimpleName(), method)
                        .isNotEmpty()
                        .allSatisfy(length -> assertThat(length).isPositive().isLessThanOrEqualTo(FREQ_INLINE_SIZE));
            }
        }
    }

    /// Keeps each call from a span search to the contains search direct and statically bound:
    /// each findSpan entry of the dot-star literal matcher calls only its own class's private walk,
    /// never another entry, which [DotStarLiteralSpanMatcher.MultipleStops] overrides, and the walk
    /// calls the contains search itself. A JVM that links the override stops inlining a walk
    /// reached through an entry, and each frame between a public one-shot entry and the search's
    /// word reads costs C2 inline depth.
    @Test
    public void testOneShotDotStarLiteralCallsContainsSearchDirectly()
    {
        String findSpan = "findSpan" + MethodType.methodType(long.class, Slice.class, int.class, int.class, DotStarLiteralSpanMatcher.ScanDiagnostics.class).toMethodDescriptorString();
        for (Class<?> type : List.of(DotStarLiteralSpanMatcher.class, DotStarLiteralSpanMatcher.MultipleStops.class)) {
            String walk = type == DotStarLiteralSpanMatcher.class ? "findSpanWithinLine" : "findSpanBetweenStops";
            for (MethodModel entry : ClassFile.of().parse(classfile(type)).methods()) {
                if (!entry.methodName().equalsString("findSpan") && !entry.methodName().equalsString("findSpanForDiagnostics")) {
                    continue;
                }
                Predicate<MethodModel> caller = method -> method.methodName().equals(entry.methodName()) && method.methodType().equals(entry.methodType());
                assertThat(Stream.of(DotStarLiteralSpanMatcher.class, DotStarLiteralSpanMatcher.MultipleStops.class)
                        .flatMap(owner -> invocations(type, caller, owner))
                        .map(invoke -> invoke.name().stringValue()))
                        .as("matcher methods %s.%s%s calls", type.getSimpleName(), entry.methodName(), entry.methodType())
                        .containsExactly(walk);
            }
            assertThat(calledMethods(type, walk, LiteralSearchKernel.class))
                    .as("LiteralSearchKernel methods %s.%s calls", type.getSimpleName(), walk)
                    .contains("findContains");
        }
        for (Class<?> type : List.of(Re2.LiteralKernelRe2.class, JavaRegexp.LiteralKernelJavaRegexp.class, RustRegexp.LiteralKernelRustRegexp.class)) {
            assertThat(calledOverloads(type, method -> method.methodName().equalsString("matchInto"), DotStarLiteralSpanMatcher.class))
                    .as("DotStarLiteralSpanMatcher methods %s.matchInto calls", type.getSimpleName())
                    .containsExactly(findSpan);
        }
    }

    /// Keeps the exact literal's span searches small enough for C2 to inline into the group-zero
    /// matcher find and the one-shot overrides of the RE2, Java, and Rust exact-literal classes
    /// that call them, and each override's call to the region search direct: each frame between a
    /// public one-shot entry and the prefix scan costs C2 inline depth.
    @Test
    public void testExactLiteralSpanSearchFitsInlineSize()
    {
        assertThat(codeLengths(CompiledPattern.class, "findExactLiteralSpan"))
                .as("CompiledPattern.findExactLiteralSpan bytecode bytes")
                .hasSize(2)
                .allSatisfy(length -> assertThat(length).isPositive().isLessThanOrEqualTo(FREQ_INLINE_SIZE));
        String findSpan = "findExactLiteralSpan" + MethodType.methodType(long.class, Slice.class, int.class, int.class).toMethodDescriptorString();
        for (Class<?> type : List.of(Re2.ExactLiteralRe2.class, JavaRegexp.ExactLiteralJavaRegexp.class, RustRegexp.ExactLiteralRustRegexp.class)) {
            assertThat(calledOverloads(type, method -> method.methodName().equalsString("matchInto"), type))
                    .as("%s.matchInto calls", type.getSimpleName())
                    .contains(findSpan);
        }
    }

    /// Keeps the fixed-width matcher's region search and its one-shot checks small enough for C2
    /// to inline into the one-shot overrides of the RE2, Java, and Rust fixed-width classes, and
    /// each override's call to the region search direct: each frame between a public one-shot
    /// entry and the matcher's scan costs C2 inline depth.
    @Test
    public void testOneShotFixedWidthSpanSearchFitsInlineSize()
    {
        assertThat(codeLength(FixedWidthByteSpanMatcher.class, "findSpanOrBail"))
                .as("FixedWidthByteSpanMatcher.findSpanOrBail bytecode bytes")
                .isPositive()
                .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        assertThat(codeLength(CompiledPattern.class, "oneShotFixedWidth"))
                .as("CompiledPattern.oneShotFixedWidth bytecode bytes")
                .isPositive()
                .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        String findSpan = "findSpanOrBail" + MethodType.methodType(long.class, Slice.class, int.class, int.class).toMethodDescriptorString();
        for (Class<?> type : List.of(Re2.FixedWidthRe2.class, JavaRegexp.FixedWidthJavaRegexp.class, RustRegexp.FixedWidthRustRegexp.class)) {
            assertThat(codeLength(type, "matchInto"))
                    .as("%s.matchInto bytecode bytes", type.getSimpleName())
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
            assertThat(calledOverloads(type, method -> method.methodName().equalsString("matchInto"), FixedWidthByteSpanMatcher.class))
                    .as("FixedWidthByteSpanMatcher methods %s.matchInto calls", type.getSimpleName())
                    .containsExactly(findSpan);
        }
    }

    /// Keeps the prefix scan and the folded routes it takes small enough for C2 to inline into the
    /// DFA, NFA, and BitState searches that call it.
    @Test
    public void testPrefixScanRoutesFitInlineSize()
    {
        for (String method : List.of("prefixAccel", "prefixAccelFoldCase", "prefixAccelFoldCaseBehindLead")) {
            assertThat(codeLength(Prog.class, method))
                    .as("Prog.%s bytecode bytes", method)
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        }
    }

    /// Keeps the folded prefix scan for dense probe pairs, which verifies candidates inside its
    /// vector loop, small enough for C2 to inline into the folded prefix scan in `Prog`, as the
    /// folded scan of that shape was measured, in both candidate-mask forms.
    @Test
    public void testDenseFoldedPrefixScanFitsInlineSize()
    {
        for (String method : List.of("findFoldCaseDense", "findFoldCaseDenseSve")) {
            assertThat(codeLength(VectorPrefixScanner.class, method))
                    .as("VectorPrefixScanner.%s bytecode bytes", method)
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        }
    }

    /// Keeps the selectors that pick each folded pair kernel's candidate-mask form within
    /// MaxInlineSize, which C2 inlines at a call site that is not hot, so a selector adds no call
    /// between a folded pair scan and its kernel.
    @Test
    public void testFoldedPairSelectorsFitMaxInlineSize()
    {
        for (String method : List.of("findFoldCasePair", "findFoldCaseDensePair")) {
            assertThat(codeLength(VectorPrefixScanner.class, method))
                    .as("VectorPrefixScanner.%s bytecode bytes", method)
                    .isPositive()
                    .isLessThanOrEqualTo(MAX_INLINE_SIZE);
        }
    }

    /// Keeps every folded pair scan on the selectors, so each takes the candidate-mask form the
    /// platform selects: the folded prefix scan and the folded run behind a start literal. The SVE
    /// forms' own tests call the kernels directly, so off SVE hosts a direct kernel call would go
    /// unnoticed.
    @Test
    public void testFoldedPairScansCallSelectors()
    {
        assertCallsSelectors(Prog.class, "prefixAccelFoldCase");
        assertCallsSelectors(Prog.FoldedStartRun.class, "find");
    }

    /// Keeps the SWAR folded prefix scans small enough for C2 to inline into the folded prefix
    /// scan in `Prog`, with their candidate comparison in their own bodies, as "Folded Prefix
    /// Candidate Scan" in REGULATOR_DECISIONS.md records: a call to a comparison helper in `Prog`
    /// made them smaller but ran about 3x slower once C2 left the helper's case lowering out of
    /// line.
    @Test
    public void testSwarFoldedPrefixScansFitInlineSize()
    {
        ClassModel prog = ClassFile.of().parse(classfile(Prog.class));
        for (String method : List.of("prefixAccelFoldCaseFrontAndBackSWAR", "findFoldCaseSwar")) {
            assertThat(codeLength(Prog.class, method))
                    .as("Prog.%s bytecode bytes", method)
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
            List<String> progCalls = prog.methods().stream()
                    .filter(model -> model.methodName().equalsString(method))
                    .flatMap(model -> model.code().stream())
                    .flatMap(CodeModel::elementStream)
                    .filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast)
                    .filter(invoke -> invoke.owner().equals(prog.thisClass()))
                    .map(invoke -> invoke.name().stringValue())
                    .toList();
            assertThat(progCalls).as("Prog methods Prog.%s calls", method).isEmpty();
        }
    }

    /// Keeps the literal kernel's helpers that the unmasked pair scan calls within
    /// MaxTrivialSize, so C2 inlines them however rarely a failed candidate reaches them. Where
    /// failed candidates are rare, as on Russian text, C2 left the 11-byte budget test out of line
    /// in findPair, and that one call in the scan made it rebuild the probe broadcasts at every
    /// 16-byte step on r9g. The x86 masked scan's one larger call is that 11-byte budget test:
    /// written in line, it made Russian Sherlock about 3% slower on r8i.
    @Test
    public void testPairScanKernelHelperSizes()
    {
        for (String callee : calledMethods(VectorPrefixScanner.class, "findPair", LiteralSearchKernel.class)) {
            assertThat(codeLength(LiteralSearchKernel.class, callee))
                    .as("LiteralSearchKernel.%s bytecode bytes, called by VectorPrefixScanner.findPair", callee)
                    .isPositive()
                    .isLessThanOrEqualTo(MAX_TRIVIAL_SIZE);
        }
        assertThat(calledMethods(VectorPrefixScanner.class, "findPairMasked", LiteralSearchKernel.class).stream()
                .filter(callee -> codeLength(LiteralSearchKernel.class, callee) > MAX_TRIVIAL_SIZE))
                .as("LiteralSearchKernel methods over MaxTrivialSize that VectorPrefixScanner.findPairMasked calls")
                .containsExactly("comparisonBudgetSpent");
        assertThat(codeLength(LiteralSearchKernel.class, "comparisonBudgetSpent"))
                .as("LiteralSearchKernel.comparisonBudgetSpent bytecode bytes")
                .isLessThanOrEqualTo(11);
    }

    /// Keeps the literal and folded prefix scans larger than FreqInlineSize, so every caller calls
    /// their compiled methods rather than inlined copies, as "Literal Prefix Vector Scan" in
    /// REGULATOR_DECISIONS.md records, and caps each at the size it was measured at: growing it
    /// further changes code that was measured only at this size. Keep every vector and mask inside
    /// the method or in helpers C2 inlines at each call site: one returned from a helper that C2
    /// left out of line was boxed on every search. The reverse scan that bounds a long search's
    /// end stays out of line for the same reason, capped at the size it was measured at.
    @Test
    public void testPrefixScanStaysOutOfLineAtMeasuredSize()
    {
        // find only picks one of these, the masked form on x86 with 64-byte vectors, so each form
        // runs out of line, capped at its own measured size.
        assertThat(codeLength(VectorPrefixScanner.class, "findPair"))
                .as("VectorPrefixScanner.findPair bytecode bytes")
                .isGreaterThan(FREQ_INLINE_SIZE)
                .isLessThanOrEqualTo(466);
        assertThat(codeLength(VectorPrefixScanner.class, "findPairMasked"))
                .as("VectorPrefixScanner.findPairMasked bytecode bytes")
                .isGreaterThan(FREQ_INLINE_SIZE)
                .isLessThanOrEqualTo(382);
        // The folded prefix scan has the same counted-loop shape and runs out of line the same way.
        assertThat(codeLength(VectorPrefixScanner.class, "findFoldCase"))
                .as("VectorPrefixScanner.findFoldCase bytecode bytes")
                .isGreaterThan(FREQ_INLINE_SIZE)
                .isLessThanOrEqualTo(405);
        // Its XOR form, which findFoldCasePair takes under SVE, runs out of line the same way.
        assertThat(codeLength(VectorPrefixScanner.class, "findFoldCaseSve"))
                .as("VectorPrefixScanner.findFoldCaseSve bytecode bytes")
                .isGreaterThan(FREQ_INLINE_SIZE)
                .isLessThanOrEqualTo(419);
    }

    /// Keeps the many-match search's byte scan larger than FreqInlineSize, so C2 compiles it on its
    /// own and allocates its registers from its own few live values rather than from the whole
    /// search's graph, as "Many-Match Byte Scan Runs Out Of Line" in REGULATOR_DECISIONS.md
    /// records, and caps it at the size it was measured at.
    @Test
    public void testManyMatchByteScanStaysOutOfLineAtMeasuredSize()
    {
        // Four bytes a step, buffering the states it matches, carry it past the inline size.
        assertThat(codeLength(Dfa.class, "scanManyBytes"))
                .as("Dfa.scanManyBytes bytecode bytes")
                .isGreaterThan(FREQ_INLINE_SIZE)
                .isLessThanOrEqualTo(370);
    }

    /// Keeps the find loop's check of whether a search tries the inner or window literal first,
    /// and the dispatch helpers it calls, BooleanPlans.hasLiteralSearch included, within
    /// MaxInlineSize, so that C2 inlines them into `RegexpMatcher.findGeneral` without needing a
    /// hot call site, and keeps the whole-text count's check the same way. The search methods
    /// the helpers call, such as WindowLiteralSearch.fallsBackNearStart, are not held to it. Caps
    /// the DFA count dispatch at 84 bytes, within which an out-of-line call to the literal counts
    /// keeps it.
    @Test
    public void testLiteralSearchGatesFitMaxInlineSize()
            throws ClassNotFoundException
    {
        for (String method : List.of("searchesInnerLiteral", "searchesInnerLiteralWindow", "literalSearchFallsBackNearStart", "windowLiteralSearchFallsBackNearStart", "searchesInnerLiteralCount")) {
            assertThat(codeLength(CompiledPattern.class, method))
                    .as("CompiledPattern.%s bytecode bytes", method)
                    .isPositive()
                    .isLessThanOrEqualTo(MAX_INLINE_SIZE);
        }
        Class<?> booleanPlans = Class.forName(CompiledPattern.class.getName() + "$BooleanPlans");
        assertThat(codeLength(booleanPlans, "hasLiteralSearch"))
                .as("CompiledPattern.BooleanPlans.hasLiteralSearch bytecode bytes")
                .isPositive()
                .isLessThanOrEqualTo(MAX_INLINE_SIZE);
        assertThat(codeLength(CompiledPattern.class, "countDfaMatches")).as("CompiledPattern.countDfaMatches bytecode bytes").isPositive().isLessThanOrEqualTo(84);
    }

    /// Caps the forward DFA's start scan dispatch, which picks the scan at every return to the
    /// start state, at the size it was measured at with the start class filter, and the literal
    /// filter's dispatch at the size it was measured at with its scalar start check and its choice
    /// of the one-load nibble scan. Raise a limit only after measuring a new scan.
    @Test
    public void testStartScanDispatchStaysAtMeasuredSize()
    {
        assertThat(codeLength(Dfa.class, "findByteSetCandidate")).as("Dfa.findByteSetCandidate bytecode bytes").isPositive().isLessThanOrEqualTo(214);
        assertThat(codeLength(VectorByteSetScanner.class, "findLiteralSet")).as("VectorByteSetScanner.findLiteralSet bytecode bytes").isPositive().isLessThanOrEqualTo(152);
    }

    /// Keeps the DFA search loops' leading parameters, by type and name, as they were before the
    /// DFA decided Unicode word boundaries, with the text context a decoding transition reads
    /// after them. x86-64 passes six Java arguments in registers and AArch64 eight, so a context
    /// parameter inserted among the leading ones pushed a loop bound onto the stack on x86, and
    /// searches that never decode slowed there.
    @Test
    public void testDfaSearchesKeepLeadingParameters()
    {
        for (Map.Entry<String, String> method : List.of(
                Map.entry("searchForward", "DfaInstance dfa, CacheTables tables, byte[] bytes, int textBegin, int textEnd, int ctxEnd, int startOffset, boolean startIsMatch, boolean pairedSearch, boolean endMatch, boolean wantEarliestMatch"),
                Map.entry("searchForwardAbsolutePointers", "DfaInstance dfa, CacheTables tables, byte[] bytes, int resultBegin, int position, int textEnd, int ctxEnd, int startOffset, int initialMatchBoundary, boolean endMatch, boolean wantEarliestMatch"),
                Map.entry("searchForwardContinuation", "DfaInstance dfa, CacheTables tables, byte[] bytes, int resultBegin, int position, int textEnd, int ctxEnd, int startOffset, boolean startIsMatch, int initialMatchBoundary, boolean endMatch, boolean wantEarliestMatch"),
                Map.entry("searchForwardScanAcceleration", "DfaInstance dfa, CacheTables tables, Prog prog, byte[] bytes, int textBegin, int textEnd, int ctxEnd, int startOffset, boolean startIsMatch, boolean endMatch, boolean wantEarliestMatch, byte[] byteCandidates, int byteScanSet, int scanKind"),
                Map.entry("searchForwardPrefixAccelSingleByte", "DfaInstance dfa, CacheTables tables, Prog prog, byte[] bytes, int textBegin, int textEnd, int ctxEnd, int startOffset, boolean startIsMatch, boolean endMatch, boolean wantEarliestMatch"),
                Map.entry("searchForwardAnchoredLongPrefixInt", "DfaInstance dfa, CacheTables tables, byte[] bytes, int textBegin, int textEnd, int ctxEnd, int startOffset, boolean startIsMatch, boolean endMatch, boolean wantEarliestMatch"),
                Map.entry("searchForwardSampledSelfLoops", "DfaInstance dfa, CacheTables tables, byte[] bytes, int textBegin, int textEnd, int ctxEnd, int startOffset, boolean startIsMatch, boolean endMatch, boolean wantEarliestMatch"),
                Map.entry("searchForwardPairs", "DfaInstance dfa, CacheTables tables, byte[] bytes, int resultBegin, int textBegin, int textEnd, int ctxEnd, int startOffset, boolean endMatch, boolean wantEarliestMatch, boolean countRecordsPairedMatch"),
                Map.entry("searchForwardPairsAfterMiss", "DfaInstance dfa, CacheTables tables, byte[] bytes, int resultBegin, int position, int textEnd, int ctxEnd, int stateOffset, long pairedTransition, boolean endMatch, boolean wantEarliestMatch, boolean countRecordsPairedMatch"),
                Map.entry("searchForwardPairsAfterMatch", "DfaInstance dfa, CacheTables tables, byte[] bytes, int resultBegin, int position, int textEnd, int ctxEnd, long pairedTransition, boolean endMatch, boolean wantEarliestMatch"),
                Map.entry("searchForwardPairsFromState", "DfaInstance dfa, CacheTables tables, byte[] bytes, int resultBegin, int position, int textEnd, int ctxEnd, int stateOffset, int lastMatchBoundary, boolean endMatch, boolean wantEarliestMatch"),
                Map.entry("searchForwardPairsFlat", "DfaInstance dfa, CacheTables tables, byte[] bytes, int resultBegin, int textBegin, int textEnd, int ctxEnd, int startOffset, boolean endMatch, boolean wantEarliestMatch, boolean countRecordsPairedMatch"),
                Map.entry("searchForwardPairsFlatAfterMiss", "DfaInstance dfa, CacheTables tables, byte[] bytes, int resultBegin, int position, int textEnd, int ctxEnd, int stateOffset, long pairedTransition, boolean endMatch, boolean wantEarliestMatch, boolean countRecordsPairedMatch"),
                Map.entry("searchForwardPairsFlatAfterMatch", "DfaInstance dfa, CacheTables tables, byte[] bytes, int resultBegin, int position, int textEnd, int ctxEnd, long pairedTransition, boolean endMatch, boolean wantEarliestMatch"),
                Map.entry("searchForwardPairsFlatFromState", "DfaInstance dfa, CacheTables tables, byte[] bytes, int resultBegin, int position, int textEnd, int ctxEnd, int stateOffset, int lastMatchBoundary, boolean endMatch, boolean wantEarliestMatch"),
                Map.entry("searchForwardCountSelfLoopScan", "DfaInstance dfa, CacheTables tables, byte[] bytes, int textBegin, int contextEnd, int startOffset"),
                Map.entry("searchBackward", "DfaInstance dfa, CacheTables tables, byte[] bytes, int textBegin, int textEnd, int ctxBegin, int startOffset, boolean startIsMatch, boolean endMatch, boolean wantEarliestMatch, int maximumBytes"),
                Map.entry("searchAnchoredCandidate", "CandidateStartCursor cursor, CacheTables tables, byte[] bytes, int contextBegin, int contextEnd, int candidate"),
                Map.entry("searchAnchoredWithStop", "DfaInstance dfa, CacheTables tables, byte[] bytes, int contextBegin, int contextEnd, int textBegin, int textEnd"),
                Map.entry("searchReverseThenAnchoredWithStop", "DfaInstance reverseDfa, CacheTables reverseTables, DfaInstance forwardDfa, CacheTables forwardTables, byte[] bytes, int contextOffset, int contextBegin, int contextEnd, int textBegin, int literalBegin, int textEnd, int maximumReverseBytes"))) {
            // The loop is the one method of its name that reads the text's bytes; a Slice
            // facade of the same name only enters it.
            assertThat(parameterLists(Dfa.class, method.getKey()))
                    .as("Dfa.%s parameters", method.getKey())
                    .filteredOn(parameters -> parameters.contains("byte[] bytes"))
                    .singleElement()
                    .satisfies(parameters -> assertThat(parameters.split(", ")).startsWith(method.getValue().split(", ")));
        }
    }

    /// Keeps the pair loops decoding their own misses, where C2 inlines the decode on the loop's
    /// profile. A miss method runs once per search and, once its loop compiles, only inlined
    /// there, so its own profile stays immature; a decode left out of line there can keep the
    /// loop's position in a stack slot on x86.
    @Test
    public void testPairLoopsDecodeTheirOwnMisses()
    {
        for (String loop : List.of("searchForwardPairs", "searchForwardPairsFlat")) {
            assertThat(calledMethods(Dfa.class, loop, Dfa.class))
                    .as("Dfa methods Dfa.%s calls", loop)
                    .contains("decodePairedTransition");
            assertThat(calledMethods(Dfa.class, loop + "AfterMiss", Dfa.class))
                    .as("Dfa methods Dfa.%sAfterMiss calls", loop)
                    .doesNotContain("decodePairedTransition");
        }
    }

    /// Keeps the pair loops and their miss methods ending in the one-byte continuation, which
    /// measures its result from `resultBegin` itself. A position read after an out-of-line
    /// one-byte search, to offset its result, can keep the loop's position in a stack slot on x86.
    @Test
    public void testPairLoopExitsUseContinuation()
    {
        for (String loop : List.of("searchForwardPairs", "searchForwardPairsFlat")) {
            for (String method : List.of(loop, loop + "AfterMiss")) {
                assertThat(calledMethods(Dfa.class, method, Dfa.class))
                        .as("Dfa methods Dfa.%s calls", method)
                        .contains("searchForwardContinuation")
                        .doesNotContain("searchForward");
            }
        }
    }

    /// Keeps the DFA's uncomputed transition below C2's 200-byte limit for inlining a method that
    /// throws often, as the quit and retry exceptions do, with every decoding step in a helper
    /// only a decoding DFA reaches; at 210 bytes a reverse search's fallback stopped inlining it.
    /// Caps the start state lookup every find runs at its measured size, which keeps the decoding
    /// DFA's start setup out of it.
    @Test
    public void testDfaTransitionAndStartStayAtMeasuredSize()
    {
        assertThat(codeLength(DfaInstance.class, "computeTransition")).as("Dfa.DfaInstance.computeTransition bytecode bytes").isPositive().isLessThan(200);
        assertThat(codeLength(DfaInstance.class, "analyzeStart", ANALYZE_START)).as("Dfa.DfaInstance.analyzeStart bytecode bytes").isPositive().isLessThanOrEqualTo(140);
        assertThat(codeLength(DfaInstance.class, "startCache")).as("Dfa.DfaInstance.startCache bytecode bytes").isPositive().isLessThanOrEqualTo(212);
    }

    /// Keeps the start state lookup's lock check before the builder call rather than inside the
    /// builder. A shared search never holds the lock, so with the check before the call its
    /// compile drops the builder call, which C2 leaves out of line.
    @Test
    public void testStartLookupChecksLockBeforeBuilder()
    {
        assertThat(calledMethods(DfaInstance.class, "analyzeStart", ANALYZE_START, ReentrantLock.class))
                .as("ReentrantLock methods Dfa.DfaInstance.analyzeStart calls")
                .contains("isHeldByCurrentThread");
        assertThat(calledMethods(DfaInstance.class, "buildStartState", ReentrantLock.class))
                .as("ReentrantLock methods Dfa.DfaInstance.buildStartState calls")
                .doesNotContain("isHeldByCurrentThread");
    }

    /// Caps the exact literal alternation's root-table loop, which inlines the trie walk, at the
    /// size it was measured at, and keeps the folded matcher's stop compare, byte compare, and
    /// root-table loop within C2's inline size, so each inlines into the route loop that calls it.
    /// A branch to the folded literal compares at the root-table method's entry, before its loop,
    /// grew the exact method to 203 bytes and slowed short exact finds on r8i, as "Start Literal
    /// Filter" in REGULATOR_DECISIONS.md records. Raise the exact limit only after measuring a new
    /// loop.
    @Test
    public void testLiteralAlternationLoopsStayAtMeasuredAndInlineSize()
    {
        assertThat(codeLength(ExactMatcher.class, "findSpanInRootTable"))
                .as("LiteralAlternationSpanMatcher.ExactMatcher.findSpanInRootTable bytecode bytes")
                .isPositive()
                .isLessThanOrEqualTo(184);
        for (String method : List.of("findSpanAtStop", "findSpanInRootTable", "matchesFolded")) {
            assertThat(codeLength(FoldedMatcher.class, method))
                    .as("LiteralAlternationSpanMatcher.FoldedMatcher.%s bytecode bytes", method)
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
        }
        // The route loops count each search's filter stops in their own state, which keeps them
        // just within C2's inline size; their callers inline them.
        for (Class<?> matcher : List.of(ExactMatcher.class, FoldedMatcher.class)) {
            for (String method : List.of("count", "findSpan")) {
                assertThat(codeLength(matcher, method))
                        .as("LiteralAlternationSpanMatcher.%s.%s bytecode bytes", matcher.getSimpleName(), method)
                        .isPositive()
                        .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
            }
        }
    }

    /// Keeps the literal-neighbor matcher's find, which a full-Slice find of its pattern runs on
    /// every input, small enough for C2 to inline into its route, and caps it at its size with the
    /// one-byte literal's density sample. Raise the limit only after measuring the find again.
    @Test
    public void testLiteralNeighborFindStaysAtMeasuredSize()
    {
        assertThat(codeLength(LiteralNeighborMatcher.class, "find")).as("LiteralNeighborMatcher.find bytecode bytes").isPositive().isLessThanOrEqualTo(205);
    }

    /// Keeps the RE2 run reader's ASCII entry, which a short run such as a quoted field's takes on
    /// every row, within MaxInlineSize, and its stop scan at the size it was added at: the scan
    /// stops at the run's stop byte or a non-ASCII byte with one compare per byte, and neither
    /// calls the walk by character, which bounds each block and reads character widths. The entry
    /// takes the stop scan first, and only a stop at a non-ASCII byte enters that walk. The entry
    /// calls `re2ByteStop`, then `re2ScanNonAscii`, without a branch; that wrapper stays within
    /// MaxInlineSize and the last block's check within FreqInlineSize. `re2ScanNonAscii` says why.
    /// The kernel's copies are held the same way.
    @Test
    public void testRe2RunAsciiEntryStaysOffCharacterWalk()
    {
        for (Class<?> type : List.of(ScanPlanRun.class, ScanKernelSupport.class)) {
            String prefix = type == ScanPlanRun.class ? "re2" : "re2Run";
            Class<?> vectors = type == ScanPlanRun.class ? VectorScanner.class : VectorScanKernelSupport.class;
            assertThat(codeLength(type, prefix + "ScanNonAscii"))
                    .as("%s.%sScanNonAscii bytecode bytes", type.getSimpleName(), prefix)
                    .isPositive()
                    .isLessThanOrEqualTo(MAX_INLINE_SIZE);
            assertThat(calledMethods(type, prefix + "ScanNonAscii", vectors))
                    .as("%s methods %s.%sScanNonAscii calls", vectors.getSimpleName(), type.getSimpleName(), prefix)
                    .containsExactly("findRe2LastBlockStop");
            assertThat(codeLength(vectors, "findRe2LastBlockStop"))
                    .as("%s.findRe2LastBlockStop bytecode bytes", vectors.getSimpleName())
                    .isPositive()
                    .isLessThanOrEqualTo(FREQ_INLINE_SIZE);
            assertThat(codeLength(type, prefix + "ScanBlocks"))
                    .as("%s.%sScanBlocks bytecode bytes", type.getSimpleName(), prefix)
                    .isPositive()
                    .isLessThanOrEqualTo(MAX_INLINE_SIZE);
            assertThat(calledMethods(type, prefix + "ScanBlocks", type))
                    .as("%s methods %s.%sScanBlocks calls", type.getSimpleName(), type.getSimpleName(), prefix)
                    .containsExactly(prefix + "ByteStop", prefix + "ScanNonAscii");
            assertThat(branches(type, prefix + "ScanBlocks"))
                    .as("%s.%sScanBlocks branches", type.getSimpleName(), prefix)
                    .isZero();
            assertThat(codeLength(type, prefix + "ByteStop"))
                    .as("%s.%sByteStop bytecode bytes", type.getSimpleName(), prefix)
                    .isPositive()
                    .isLessThanOrEqualTo(64);
            for (String method : List.of(prefix + "ScanBlocks", prefix + "ByteStop")) {
                for (Class<?> owner : List.of(ScanPlanRun.class, ScanKernelSupport.class, Utf8.class, VectorScanner.class, VectorScanKernelSupport.class)) {
                    assertThat(calledMethods(type, method, owner))
                            .as("%s methods %s.%s calls", owner.getSimpleName(), type.getSimpleName(), method)
                            .doesNotContain("re2WalkLimit", "re2CharacterWidth", "skipRe2Run");
                }
            }
        }
    }

    // Asserts that `methodName` of `type` calls both folded pair selectors and no folded pair
    // kernel directly.
    private static void assertCallsSelectors(Class<?> type, String methodName)
    {
        List<String> calls = calledMethods(type, methodName, VectorPrefixScanner.class);
        assertThat(calls)
                .as("VectorPrefixScanner methods %s.%s calls", type.getSimpleName(), methodName)
                .contains("findFoldCasePair", "findFoldCaseDensePair")
                .doesNotContain("findFoldCase", "findFoldCaseDense", "findFoldCaseSve", "findFoldCaseDenseSve");
    }

    // The names of the methods of `owner` that `methodName` of `type` calls.
    private static List<String> calledMethods(Class<?> type, String methodName, Class<?> owner)
    {
        return invocations(type, model -> model.methodName().equalsString(methodName), owner)
                .map(invoke -> invoke.name().stringValue())
                .toList();
    }

    // The names of the methods of `owner` that the method of `type` with `methodName` and
    // `descriptor` calls.
    private static List<String> calledMethods(Class<?> type, String methodName, String descriptor, Class<?> owner)
    {
        return invocations(type, model -> model.methodName().equalsString(methodName) && model.methodType().equalsString(descriptor), owner)
                .map(invoke -> invoke.name().stringValue())
                .toList();
    }

    // The methods of `owner`, each as its name and descriptor, that the methods of `type` that
    // `callers` accepts call.
    private static List<String> calledOverloads(Class<?> type, Predicate<MethodModel> callers, Class<?> owner)
    {
        return invocations(type, callers, owner)
                .map(invoke -> invoke.name().stringValue() + invoke.type().stringValue())
                .toList();
    }

    private static Stream<InvokeInstruction> invocations(Class<?> type, Predicate<MethodModel> callers, Class<?> owner)
    {
        String ownerName = owner.getName().replace('.', '/');
        return ClassFile.of().parse(classfile(type)).methods().stream()
                .filter(callers)
                .flatMap(model -> model.code().stream())
                .flatMap(CodeModel::elementStream)
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast)
                .filter(invoke -> invoke.owner().asInternalName().equals(ownerName));
    }

    private static long branches(Class<?> type, String methodName)
    {
        return ClassFile.of().parse(classfile(type)).methods().stream()
                .filter(method -> method.methodName().equalsString(methodName))
                .flatMap(model -> model.code().stream())
                .flatMap(CodeModel::elementStream)
                .filter(BranchInstruction.class::isInstance)
                .count();
    }

    private static int codeLength(Class<?> type, String methodName)
    {
        List<Integer> lengths = codeLengths(type, methodName);
        assertThat(lengths).as("methods named %s", methodName).hasSize(1);
        return lengths.getFirst();
    }

    private static int codeLength(Class<?> type, String methodName, String descriptor)
    {
        return ClassFile.of().parse(classfile(type)).methods().stream()
                .filter(method -> method.methodName().equalsString(methodName) && method.methodType().equalsString(descriptor))
                .map(method -> method.findAttribute(Attributes.code()).map(CodeAttribute::codeLength).orElse(0))
                .findFirst()
                .orElse(0);
    }

    // Each method of `type` named `methodName` as its parameters, each as its type's simple name
    // and its name, joined as a declaration lists them.
    private static List<String> parameterLists(Class<?> type, String methodName)
    {
        return ClassFile.of().parse(classfile(type)).methods().stream()
                .filter(method -> method.methodName().equalsString(methodName))
                .map(method -> {
                    List<ClassDesc> types = method.methodTypeSymbol().parameterList();
                    List<MethodParameterInfo> names = method.findAttribute(Attributes.methodParameters()).orElseThrow().parameters();
                    return IntStream.range(0, types.size())
                            .mapToObj(index -> {
                                String typeName = types.get(index).displayName();
                                return typeName.substring(typeName.lastIndexOf('$') + 1) + " " + names.get(index).name().orElseThrow().stringValue();
                            })
                            .collect(joining(", "));
                })
                .toList();
    }

    private static List<Integer> codeLengths(Class<?> type, String methodName)
    {
        return ClassFile.of().parse(classfile(type)).methods().stream()
                .filter(method -> method.methodName().equalsString(methodName))
                .map(method -> method.findAttribute(Attributes.code()).map(CodeAttribute::codeLength).orElse(0))
                .toList();
    }
}
