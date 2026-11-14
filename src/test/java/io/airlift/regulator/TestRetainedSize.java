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
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

import java.lang.foreign.MemorySegment;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;

public class TestRetainedSize
{
    private static final String WORD_ALTERNATION = "(?i)(sherlock|holmes|watson|irene|adler|moriarty|lestrade)";
    private static final Slice TEXT = randomText(256 << 10);
    private static final Slice SHORT_TEXT = Slices.utf8Slice("Sherlock Holmes met Watson");

    @Test
    public void testSizeGrowsAsSearchesBuildCaches()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice(WORD_ALTERNATION));
        long fresh = pattern.retainedSizeInBytes();
        assertThat(fresh).isPositive();

        pattern.find(SHORT_TEXT);
        long afterShortFind = pattern.retainedSizeInBytes();
        pattern.countMatches(TEXT);
        long afterLongCount = pattern.retainedSizeInBytes();

        assertThat(afterShortFind).isGreaterThan(fresh);
        assertThat(afterLongCount).isGreaterThan(afterShortFind);
        assertThat(afterLongCount).isLessThanOrEqualTo(fresh + pattern.maxMemoryBytes());
    }

    @Test
    public void testMatcherSizeExcludesPattern()
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("(\\w*)(\\w)"));
        RegexpMatcher matcher = pattern.matcher(SHORT_TEXT);
        assertThat(matcher.find()).isTrue();
        long matcherSize = matcher.retainedSizeInBytes();
        long patternSize = pattern.retainedSizeInBytes();

        pattern.countMatches(TEXT);
        assertThat(pattern.retainedSizeInBytes()).isGreaterThan(patternSize);
        assertThat(matcher.retainedSizeInBytes()).isEqualTo(matcherSize);

        TrinoRegexp trinoPattern = TrinoRegexp.compile(Slices.utf8Slice("(\\w*)(\\w)"));
        TrinoRegexpMatcher trinoMatcher = trinoPattern.matcher(SHORT_TEXT);
        assertThat(trinoMatcher.find()).isTrue();
        long trinoMatcherSize = trinoMatcher.retainedSizeInBytes();
        long trinoPatternSize = trinoPattern.retainedSizeInBytes();

        trinoPattern.count(TEXT);
        assertThat(trinoPattern.retainedSizeInBytes()).isGreaterThan(trinoPatternSize);
        assertThat(trinoMatcher.retainedSizeInBytes()).isEqualTo(trinoMatcherSize);
    }

    @Test
    public void testMaxMemoryBytesIsTheOptionsBudget()
    {
        Slice regex = Slices.utf8Slice("a+b");
        long budget = 3 << 20;
        assertThat(Re2.compile(regex).maxMemoryBytes()).isEqualTo(Re2.Options.DEFAULT_MAX_MEMORY);
        assertThat(Re2.compile(regex, Re2.Options.defaults().setMaxMemory(budget)).maxMemoryBytes()).isEqualTo(budget);
        assertThat(JavaRegexp.compile(regex, JavaRegexp.Options.defaults().setMaxMemory(budget)).maxMemoryBytes()).isEqualTo(budget);
        assertThat(RustRegexp.compile(regex, RustRegexp.Options.defaults().setMaxMemory(budget)).maxMemoryBytes()).isEqualTo(budget);
        assertThat(TrinoRegexp.compile(regex, TrinoRegexp.Options.defaults().setMaxMemory(budget)).maxMemoryBytes()).isEqualTo(budget);
    }

    // Reads while other threads build the DFA caches never fall below the fresh size or past
    // what the budget lets the caches grow to.
    @Test
    public void testConcurrentReadsStayWithinBounds()
            throws Exception
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice(WORD_ALTERNATION));
        long fresh = pattern.retainedSizeInBytes();
        long ceiling = fresh + pattern.maxMemoryBytes();
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads + 1);
        AtomicBoolean done = new AtomicBoolean();
        // The searches start once the reader has finished its first read.
        CountDownLatch firstRead = new CountDownLatch(1);
        try {
            List<Future<?>> searches = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                int offset = thread * (TEXT.length() / threads);
                searches.add(executor.submit(() -> {
                    firstRead.await();
                    return pattern.countMatches(TEXT.slice(offset, TEXT.length() - offset));
                }));
            }
            Future<Long> reads = executor.submit(() -> {
                long iterations = 0;
                try {
                    do {
                        assertThat(pattern.retainedSizeInBytes()).isBetween(fresh, ceiling);
                        iterations++;
                        firstRead.countDown();
                    }
                    while (!done.get());
                }
                finally {
                    // A failed read still lets the searches finish, so the failure surfaces.
                    firstRead.countDown();
                }
                return iterations;
            });
            for (Future<?> search : searches) {
                search.get();
            }
            done.set(true);
            assertThat(reads.get()).isPositive();
        }
        finally {
            done.set(true);
            executor.shutdown();
        }
        assertThat(pattern.retainedSizeInBytes()).isBetween(fresh, ceiling);
    }

    // The reported size against the heap graph JOL walks from the same object: never below it,
    // since a missing part shows as a shortfall, and within half again of it.
    @Test
    public void testSizeMatchesHeapGraph()
            throws ReflectiveOperationException
    {
        assertMatchesHeapGraph("fresh pattern", () -> Re2.compile(Slices.utf8Slice("[a-z]+ing\\b")), (_, _) -> {}, Re2::retainedSizeInBytes);
        assertMatchesHeapGraph("pattern after matching", () -> Re2.compile(Slices.utf8Slice(WORD_ALTERNATION)), Re2::countMatches, Re2::retainedSizeInBytes);
        assertMatchesHeapGraph("LIKE pattern after matching", () -> TrinoLikePattern.compile(Slices.utf8Slice("a%b_c%d")), TrinoLikePattern::matches, TrinoLikePattern::retainedSizeInBytes);
        assertMatchesHeapGraph("500 named groups (map sizing)", () -> JavaRegexp.compile(Slices.utf8Slice(namedGroups(500))), (_, _) -> {}, JavaRegexp::retainedSizeInBytes);
        assertMatchesHeapGraph("Rust \\w before reverse compile (shared Unicode ranges)", () -> RustRegexp.compile(Slices.utf8Slice("([\\w!]*)([\\w!])")), (_, _) -> {}, RustRegexp::retainedSizeInBytes);
        assertMatchesHeapGraph("named-group map views (duplicate names, cached views)", () -> Re2.compile(Slices.utf8Slice("(?P<same>a)(?P<same>b)")), (pattern, _) -> {
            assertThat(pattern.capturingGroupNames().keySet()).hasSize(2);
            assertThat(pattern.capturingGroupNames().values()).hasSize(2);
            assertThat(pattern.namedCapturingGroups().keySet()).containsExactly("same");
            assertThat(pattern.namedCapturingGroups().values()).containsExactly(1);
        }, Re2::retainedSizeInBytes);
        assertMatchesHeapGraph("FilteredRe2 shared ByteFrequencies (double count)", TestRetainedSize::sharedFrequencyKeywords, (_, _) -> {}, FilteredRe2::retainedSizeInBytes);
        assertThat(GraphLayout.parseInstance(collidingAtoms()).getClasses())
                .as("atoms whose hashes share a bin turn the prefilter's atom map into a tree")
                .extracting(Class::getName)
                .contains("java.util.HashMap$TreeNode");
        assertMatchesHeapGraph("treeified prefilter map (TreeNode)", TestRetainedSize::collidingAtoms, (_, _) -> {}, FilteredRe2::retainedSizeInBytes);

        Re2 pattern = Re2.compile(Slices.utf8Slice("(\\w*)(\\w)"));
        RegexpMatcher matcher = pattern.matcher(TEXT);
        for (int find = 0; find < 100; find++) {
            assertThat(matcher.find()).isTrue();
        }
        assertThat(matcher.bitStateWorkspaceInitializedForDiagnostics()).isTrue();
        assertWithinHeapGraph("matcher after BitState finds", matcher.retainedSizeInBytes(), matcherHeapBytes(matcher, pattern));

        Re2 namedPattern = Re2.compile(Slices.utf8Slice("(?P<first>a)(?P<second>b)(?P<third>c)(?P<fourth>d)"));
        RegexpMatcher fewerGroups = namedPattern.matcher(TEXT, 3);
        assertWithinHeapGraph("matcher keeping fewer groups (own name map omitted)", fewerGroups.retainedSizeInBytes(), matcherHeapBytes(fewerGroups, namedPattern));
    }

    // What JOL walks from a matcher beyond its pattern and input.
    private static long matcherHeapBytes(RegexpMatcher matcher, CompiledPattern pattern)
    {
        return GraphLayout.parseInstance(matcher, pattern, TEXT).totalSize() - GraphLayout.parseInstance(pattern, TEXT).totalSize();
    }

    private static <T> void assertMatchesHeapGraph(String row, Supplier<T> compile, BiConsumer<T, Slice> settle, ToLongFunction<T> size)
            throws ReflectiveOperationException
    {
        // A second compile, settled the same way on a short input, reaches the static tables the
        // first does, so walking both and taking away the second's walk leaves what the first
        // alone retains. Each walk follows identity, so the collector moving objects between
        // walks is harmless.
        T object = compile.get();
        T reference = compile.get();
        settle.accept(object, TEXT);
        settle.accept(reference, SHORT_TEXT);
        long nativeBytes = 0;
        List<Object> phantomReferents = new ArrayList<>();
        if (object instanceof CompiledPattern pattern) {
            for (Dfa.DfaInstance dfa : dfas(pattern)) {
                MemorySegment segment = dfa.absolutePointerTransitionSegment();
                if (segment != null && segment.isNative()) {
                    nativeBytes += dfa.absolutePointerTransitionMemory();
                }
                phantomReferents.addAll(supersededPointerTables(dfa));
            }
        }
        // JOL walks a phantom reference's referent as it walks any field, but a superseded native
        // pointer table is reachable only that way.
        List<Object> excluded = new ArrayList<>(phantomReferents);
        excluded.add(reference);
        long heapBytes = GraphLayout.parseInstance(object, reference).totalSize() - GraphLayout.parseInstance(excluded.toArray()).totalSize();
        assertWithinHeapGraph(row, size.applyAsLong(object), heapBytes + nativeBytes);
    }

    private static void assertWithinHeapGraph(String row, long reported, long walked)
    {
        assertThat((double) reported / walked)
                .as("%s: reported %s bytes, JOL walked %s", row, reported, walked)
                .isBetween(1.0, 1.5);
    }

    private static List<Dfa.DfaInstance> dfas(CompiledPattern pattern)
    {
        List<Dfa.DfaInstance> dfas = new ArrayList<>();
        for (Prog program : new Prog[] {pattern.forwardProgramForDiagnostics(), pattern.booleanProgramForDiagnostics(), pattern.reverseProgramIfComputedForDiagnostics()}) {
            if (program == null) {
                continue;
            }
            for (Dfa.DfaInstance.Kind kind : Dfa.DfaInstance.Kind.values()) {
                Dfa.DfaInstance dfa = program.cachedDfaIfPresent(kind);
                if (dfa != null && !dfas.contains(dfa)) {
                    dfas.add(dfa);
                }
            }
        }
        return dfas;
    }

    private static List<Object> supersededPointerTables(Dfa.DfaInstance dfa)
            throws ReflectiveOperationException
    {
        Field head = Dfa.DfaInstance.class.getDeclaredField("supersededAbsolutePointerReferences");
        head.setAccessible(true);
        long referentOffset = VM.current().fieldOffset(Reference.class.getDeclaredField("referent"));
        List<Object> referents = new ArrayList<>();
        for (Object reference = head.get(dfa); reference != null; ) {
            Object referent = VM.current().getObject(reference, referentOffset);
            if (referent != null) {
                referents.add(referent);
            }
            Field next = reference.getClass().getDeclaredField("next");
            next.setAccessible(true);
            reference = next.get(reference);
        }
        return referents;
    }

    private static String namedGroups(int count)
    {
        StringBuilder regex = new StringBuilder();
        for (int group = 0; group < count; group++) {
            regex.append("(?<g").append(group).append(">a?)");
        }
        return regex.toString();
    }

    // Fifty keywords compiled with one custom byte distribution, which every pattern keeps.
    private static FilteredRe2 sharedFrequencyKeywords()
    {
        long[] counts = new long[256];
        Arrays.setAll(counts, index -> index + 1);
        Re2.Options options = Re2.Options.defaults().setByteFrequencies(ByteFrequencies.fromCounts(counts));
        FilteredRe2.Builder builder = FilteredRe2.builder();
        for (int keyword = 0; keyword < 50; keyword++) {
            builder.add(Slices.utf8Slice("keyword" + keyword), options);
        }
        return builder.build();
    }

    // Eleven atoms whose hashes fall in one bin of a 64-slot table: the ninth and tenth grow the
    // atom map's table, and the eleventh turns the bin into a tree.
    private static FilteredRe2 collidingAtoms()
    {
        FilteredRe2.Builder builder = FilteredRe2.builder();
        for (String atom : List.of("word16", "word170", "word218", "word385", "word396", "word452", "word482", "word492", "word507", "word508", "word663")) {
            builder.add(Slices.utf8Slice(atom), Re2.Options.defaults());
        }
        return builder.build();
    }

    // Words of letters in either case with the alternation's names among them, so the
    // case-insensitive DFA builds many states.
    private static Slice randomText(int length)
    {
        String[] names = {"Sherlock", "holmes", "WATSON", "Irene", "adler", "Moriarty", "lestrade"};
        Random random = new Random(42);
        StringBuilder text = new StringBuilder(length + 16);
        while (text.length() < length) {
            if (random.nextInt(8) == 0) {
                text.append(names[random.nextInt(names.length)]);
            }
            else {
                int letters = 1 + random.nextInt(9);
                for (int letter = 0; letter < letters; letter++) {
                    char base = random.nextBoolean() ? 'a' : 'A';
                    text.append((char) (base + random.nextInt(26)));
                }
            }
            text.append(' ');
        }
        return Slices.utf8Slice(text.toString());
    }
}
