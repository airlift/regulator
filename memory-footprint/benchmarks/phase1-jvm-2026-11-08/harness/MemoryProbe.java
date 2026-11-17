package io.airlift.regulator;

import io.airlift.jcodings.specific.NonStrictUTF8Encoding;
import io.airlift.joni.Option;
import io.airlift.joni.Regex;
import io.airlift.joni.Syntax;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.openjdk.jol.vm.VM;
import org.openjdk.jol.vm.VirtualMachine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static io.airlift.slice.SliceUtf8.lengthOfCodePoint;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;

/// Byte-counting memory probe for the Regulator memory-footprint campaign (phase 1, JVM engines).
/// Counts retained bytes only: no timing is taken or reported. The wall clock bounds how long a
/// row runs, nothing else.
///
/// Usage: MemoryProbe <jobs.tsv> <results.jsonl>
/// Each job line is tab-separated key=value pairs (see the Python driver).
public final class MemoryProbe
{
    static final long DEFAULT_BUDGET = Re2.Options.DEFAULT_MAX_MEMORY;
    static final boolean NATIVE = Dfa.nativeAccessEnabled();

    private MemoryProbe() {}

    public static void main(String[] args)
            throws Exception
    {
        List<String> lines = Files.readAllLines(Path.of(args[0]));
        try (BufferedWriter out = Files.newBufferedWriter(Path.of(args[1]), UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                Map<String, String> job = parseJob(line);
                List<String> records;
                try {
                    records = runJob(job);
                }
                catch (Throwable t) {
                    records = List.of(new Json().put("job", job.get("id")).put("mode", NATIVE ? "native" : "safe")
                            .put("error", t.toString() + " @ " + firstFrames(t)).toString());
                }
                for (String record : records) {
                    out.write(record);
                    out.newLine();
                }
                out.flush();
            }
        }
    }

    static String firstFrames(Throwable t)
    {
        StringBuilder b = new StringBuilder();
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause != t) {
            b.append("cause=").append(cause).append(' ');
        }
        StackTraceElement[] trace = cause.getStackTrace();
        for (int i = 0; i < Math.min(4, trace.length); i++) {
            b.append(trace[i]).append(';');
        }
        return b.toString();
    }

    static Map<String, String> parseJob(String line)
    {
        Map<String, String> job = new LinkedHashMap<>();
        for (String item : line.split("\t")) {
            int eq = item.indexOf('=');
            job.put(item.substring(0, eq), item.substring(eq + 1));
        }
        return job;
    }

    // ------------------------------------------------------------------------------------------
    // Job driver

    static List<String> runJob(Map<String, String> job)
            throws Exception
    {
        Row row = Rows.create(job);
        long target = Long.parseLong(job.getOrDefault("target", "1000"));
        double wall = Double.parseDouble(job.getOrDefault("wall", "60"));
        List<String> records = new ArrayList<>();
        if (job.containsKey("threads")) {
            for (String t : job.get("threads").split(",")) {
                records.add(runThreads(job, row, Integer.parseInt(t), target, wall).toString());
            }
            return records;
        }
        // "default" is the benchmark's own compile; a number is a mirror compile with that many MiB.
        for (String token : job.getOrDefault("budgets", "default").split(",")) {
            boolean isDefault = token.equals("default");
            if (!isDefault && !row.hasBudget()) {
                continue;
            }
            long budget = isDefault ? 0 : Long.parseLong(token) << 20;
            records.add(runOne(job, row, budget, isDefault, target, wall).toString());
            row.release();
        }
        return records;
    }

    static Json runOne(Map<String, String> job, Row row, long budget, boolean primary, long target, double wall)
            throws Exception
    {
        Json json = new Json()
                .put("job", job.get("id"))
                .put("mode", NATIVE ? "native" : "safe")
                .put("budget", primary ? DEFAULT_BUDGET : budget)
                .put("primary", primary);
        long compileBudget = budget;

        // Reference compile, which also loads the classes the pattern needs.
        MemorySnapshot first = MemorySnapshot.take();
        Object reference;
        try {
            reference = row.compile(compileBudget);
        }
        catch (RegexpCompileMemoryLimitException e) {
            return json.put("rejected", true).put("error", e.getMessage());
        }
        MemorySnapshot afterFirst = MemorySnapshot.take();

        MemorySnapshot before = MemorySnapshot.take();
        Object pattern = row.compile(compileBudget);
        MemorySnapshot after = MemorySnapshot.take();
        json.put("rejected", false);
        json.put("compile_alloc", after.allocated - before.allocated);
        json.put("metaspace", after.metaspace - before.metaspace);
        json.put("classes", after.classes - before.classes);
        json.put("metaspace_first", afterFirst.metaspace - first.metaspace);
        json.put("classes_first", afterFirst.classes - first.classes);
        json.put("generated", row.generated(pattern));
        json.put("impl", row.describe(pattern));

        Set<Object> inputs = Walker.reach(row.inputs());
        Set<Object> shared = Walker.reach(new Object[] {reference});
        Set<Object> exclude = union(inputs, shared);
        Walker fixed = Walker.walk(new Object[] {pattern}, exclude);
        Walker withShared = Walker.walk(new Object[] {pattern}, inputs);
        json.put("fixed_heap", fixed.bytes).put("fixed_objects", fixed.objects)
                .put("shared_heap", withShared.bytes - fixed.bytes)
                .put("fixed_dfas", fixed.dfas.size());
        DfaStats fixedDfa = DfaStats.of(fixed.dfas);
        json.put("fixed_native", fixedDfa.nativeLive);

        Object[] matchers = row.matchers(pattern);
        Set<Object> patternGraph = Walker.reach(new Object[] {pattern});
        Walker matcherWalk = Walker.walk(row.matcherRoots(matchers), union(exclude, patternGraph));
        json.put("matcher_heap", matcherWalk.bytes).put("matcher_dfas", matcherWalk.dfas.size());

        List<Json> checkpoints = new ArrayList<>();
        String stop;
        long ops = 0;
        long checksum = 1;
        if (row.compileOnly()) {
            ops = 1;
            checkpoints.add(checkpoint(row, pattern, matchers, exclude, ops, checksum));
            stop = "compile";
        }
        else {
            if (row.singleShot()) {
                target = 1;
            }
            TreeSet<Long> marks = new TreeSet<>();
            for (long m = 1; m <= target; m *= 10) {
                marks.add(m);
            }
            marks.add(Math.max(1, target / 2));
            marks.add(target);
            long minStop = row.minStableOps();
            long start = System.nanoTime();
            long wallNanos = (long) (wall * 1e9);
            boolean midTaken = false;
            Map<Long, String> signatures = new java.util.HashMap<>();
            stop = "target";
            while (ops < target) {
                Object result = row.op(pattern, matchers, ops);
                checksum = checksum * 31 + Hashing.hash(result);
                ops++;
                boolean mark = marks.contains(ops);
                long elapsed = System.nanoTime() - start;
                boolean overWall = elapsed > wallNanos;
                boolean mid = !midTaken && elapsed > wallNanos / 2 && ops < target;
                if (mark || overWall || mid) {
                    Json cp = checkpoint(row, pattern, matchers, exclude, ops, checksum);
                    checkpoints.add(cp);
                    if (mid) {
                        midTaken = true;
                    }
                    if (mark) {
                        signatures.put(ops, cp.signature);
                        String earlier = signatures.get(ops / 10);
                        if (ops >= minStop && ops % 10 == 0 && earlier != null && earlier.equals(cp.signature) && ops < target) {
                            stop = "stable";
                            break;
                        }
                    }
                    if (overWall && ops < target) {
                        stop = "wall";
                        break;
                    }
                }
            }
        }
        json.put("ops", ops).put("stop", stop).put("checksum", checksum);
        json.ops = ops;
        json.putRaw("checkpoints", Json.array(checkpoints));
        json.put("note", row.note());
        // Keep both alive until the last checkpoint.
        Reference.reachabilityFence(reference);
        Reference.reachabilityFence(pattern);
        return json;
    }

    static Json checkpoint(Row row, Object pattern, Object[] matchers, Set<Object> exclude, long ops, long checksum)
    {
        List<Object> roots = new ArrayList<>();
        roots.add(pattern);
        roots.addAll(Arrays.asList(row.matcherRoots(matchers)));
        roots.addAll(Arrays.asList(row.extraRoots()));
        Walker walk = Walker.walk(roots.toArray(), exclude);
        DfaStats dfa = DfaStats.of(walk.dfas);
        Json cp = new Json()
                .put("ops", ops)
                .put("heap", walk.bytes)
                .put("native", dfa.nativeLive)
                .put("native_superseded", dfa.nativeSuperseded)
                .put("dfas", walk.dfas.size())
                .put("budget", dfa.budget)
                .put("used", dfa.used)
                .put("states", dfa.states)
                .put("resets", dfa.resets)
                .put("bails", dfa.bails)
                .put("checksum", checksum);
        cp.signature = dfa.signature;
        return cp;
    }

    static Set<Object> union(Set<Object> a, Set<Object> b)
    {
        Set<Object> result = Collections.newSetFromMap(new IdentityHashMap<>(a.size() + b.size()));
        result.addAll(a);
        result.addAll(b);
        return result;
    }

    // ------------------------------------------------------------------------------------------
    // Thread scaling: one compiled pattern, T threads, each with its own matcher state.

    static Json runThreads(Map<String, String> job, Row row, int threads, long target, double wall)
            throws Exception
    {
        Json json = new Json().put("job", job.get("id")).put("mode", NATIVE ? "native" : "safe").put("threads", threads);
        Object reference = row.compile(0);
        Object pattern = row.compile(0);
        Set<Object> inputs = Walker.reach(row.inputs());
        Set<Object> exclude = union(inputs, Walker.reach(new Object[] {reference}));
        Object[][] matchers = new Object[threads][];
        for (int t = 0; t < threads; t++) {
            matchers[t] = row.matchers(pattern);
        }
        TreeSet<Long> marks = new TreeSet<>();
        for (long m = 1; m <= target; m *= 10) {
            marks.add(m);
        }
        marks.add(Math.max(1, target / 2));
        marks.add(target);
        Long[] markArray = marks.toArray(Long[]::new);
        Phaser phaser = new Phaser(threads + 1);
        Object[][] extras = new Object[threads][];
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long deadline = System.nanoTime() + (long) (wall * 1e9);
        java.util.concurrent.atomic.AtomicBoolean stopEarly = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicLongArray done = new java.util.concurrent.atomic.AtomicLongArray(threads);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int index = t;
            Thread worker = new Thread(() -> {
                try {
                    long ops = 0;
                    for (Long mark : markArray) {
                        while (ops < mark && !stopEarly.get()) {
                            row.op(pattern, matchers[index], ops);
                            ops++;
                        }
                        done.set(index, ops);
                        extras[index] = row.extraRoots();
                        phaser.arriveAndAwaitAdvance();
                        phaser.arriveAndAwaitAdvance();
                    }
                }
                catch (Throwable e) {
                    failure.compareAndSet(null, e);
                    phaser.arriveAndDeregister();
                }
            }, "probe-worker-" + t);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        List<Json> checkpoints = new ArrayList<>();
        for (Long mark : markArray) {
            phaser.arriveAndAwaitAdvance();
            if (failure.get() != null) {
                break;
            }
            List<Object> roots = new ArrayList<>();
            roots.add(pattern);
            for (int t = 0; t < threads; t++) {
                roots.addAll(Arrays.asList(row.matcherRoots(matchers[t])));
                if (extras[t] != null) {
                    roots.addAll(Arrays.asList(extras[t]));
                }
            }
            Walker walk = Walker.walk(roots.toArray(), exclude);
            DfaStats dfa = DfaStats.of(walk.dfas);
            long minOps = Long.MAX_VALUE;
            long maxOps = 0;
            for (int t = 0; t < threads; t++) {
                minOps = Math.min(minOps, done.get(t));
                maxOps = Math.max(maxOps, done.get(t));
            }
            checkpoints.add(new Json().put("ops", mark).put("ops_min", minOps).put("ops_max", maxOps).put("heap", walk.bytes).put("native", dfa.nativeLive)
                    .put("native_superseded", dfa.nativeSuperseded).put("budget", dfa.budget).put("used", dfa.used)
                    .put("resets", dfa.resets).put("dfas", walk.dfas.size()).put("stopped", stopEarly.get()));
            if (System.nanoTime() > deadline) {
                stopEarly.set(true);
            }
            phaser.arriveAndAwaitAdvance();
        }
        for (Thread worker : workers) {
            worker.join(10_000);
        }
        if (failure.get() != null) {
            json.put("error", failure.get().toString() + " @ " + firstFrames(failure.get()));
        }
        json.putRaw("checkpoints", Json.array(checkpoints));
        json.put("wall_stopped", stopEarly.get());
        Reference.reachabilityFence(reference);
        return json;
    }

    // ------------------------------------------------------------------------------------------
    // Measurement helpers

    record MemorySnapshot(long allocated, long metaspace, long classes)
    {
        static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        static final ClassLoadingMXBean CLASSES = ManagementFactory.getClassLoadingMXBean();
        static final List<MemoryPoolMXBean> METASPACE = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getName().equals("Metaspace") || pool.getName().equals("Compressed Class Space"))
                .toList();

        static MemorySnapshot take()
        {
            long metaspace = 0;
            for (MemoryPoolMXBean pool : METASPACE) {
                metaspace += pool.getUsage().getUsed();
            }
            return new MemorySnapshot(THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()), metaspace, CLASSES.getTotalLoadedClassCount());
        }
    }

    static final class DfaStats
    {
        long budget;
        long used;
        long states;
        long resets;
        long bails;
        long nativeLive;
        long nativeSuperseded;
        String signature;

        static DfaStats of(List<Dfa.DfaInstance> dfas)
        {
            DfaStats stats = new DfaStats();
            List<String> parts = new ArrayList<>();
            for (Dfa.DfaInstance dfa : dfas) {
                Dfa.CacheSnapshot snapshot = dfa.cacheSnapshot();
                stats.budget += snapshot.stateBudget();
                long used = snapshot.stateBudget() - snapshot.availableStateMemory();
                stats.used += used;
                stats.states += snapshot.stateCount();
                stats.resets += snapshot.resetCount();
                if (!dfa.ok() || dfa.countSearchBailedWhenSlow()) {
                    stats.bails++;
                }
                if (NATIVE) {
                    stats.nativeLive += dfa.absolutePointerTransitionMemory();
                    stats.nativeSuperseded += dfa.supersededAbsolutePointerMemory();
                }
                parts.add(snapshot.stateBudget() + ":" + used + ":" + snapshot.stateCount() + ":" + snapshot.resetCount());
            }
            Collections.sort(parts);
            stats.signature = String.join(",", parts);
            return stats;
        }
    }

    /// Identity-based reachability walk over instance fields. Shallow sizes come from JOL.
    /// Skips Class, ClassLoader, Thread, Module objects and Reference referents; objects in
    /// java.lang.invoke, jdk.internal.foreign and jdk.internal.ref count shallow only, except that
    /// a heap memory segment's backing array is followed.
    static final class Walker
    {
        private static final ClassValue<Field[]> FIELDS = new ClassValue<>()
        {
            @Override
            protected Field[] computeValue(Class<?> type)
            {
                List<Field> fields = new ArrayList<>();
                for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                    for (Field field : c.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                            continue;
                        }
                        if (c == java.lang.ref.Reference.class) {
                            continue;
                        }
                        try {
                            field.setAccessible(true);
                            fields.add(field);
                        }
                        catch (RuntimeException e) {
                            UNREADABLE.add(c.getName() + "." + field.getName());
                        }
                    }
                }
                return fields.toArray(Field[]::new);
            }
        };
        static final Set<String> UNREADABLE = Collections.synchronizedSet(new TreeSet<>());
        private static final VirtualMachine VM_INSTANCE = VM.current();
        private static final Field HEAP_SEGMENT_BASE = heapSegmentBase();

        private static Field heapSegmentBase()
        {
            try {
                Field field = Class.forName("jdk.internal.foreign.HeapMemorySegmentImpl").getDeclaredField("base");
                field.setAccessible(true);
                return field;
            }
            catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("cannot read heap memory segment base", e);
            }
        }

        final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        final Set<Object> exclude;
        final List<Dfa.DfaInstance> dfas = new ArrayList<>();
        long bytes;
        long objects;

        private Walker(Set<Object> exclude)
        {
            this.exclude = exclude;
        }

        static Walker walk(Object[] roots, Set<Object> exclude)
        {
            Walker walker = new Walker(exclude);
            walker.run(roots);
            return walker;
        }

        static Set<Object> reach(Object[] roots)
        {
            Walker walker = new Walker(Set.of());
            walker.run(roots);
            Set<Object> result = Collections.newSetFromMap(new IdentityHashMap<>(walker.seen.size()));
            result.addAll(walker.seen.keySet());
            return result;
        }

        private void run(Object[] roots)
        {
            ArrayDeque<Object> stack = new ArrayDeque<>();
            for (Object root : roots) {
                push(stack, root);
            }
            while (!stack.isEmpty()) {
                Object object = stack.pop();
                bytes += VM_INSTANCE.sizeOf(object);
                objects++;
                if (object instanceof Dfa.DfaInstance dfa) {
                    dfas.add(dfa);
                }
                Class<?> type = object.getClass();
                if (type.isArray()) {
                    if (!type.getComponentType().isPrimitive()) {
                        for (Object element : (Object[]) object) {
                            push(stack, element);
                        }
                    }
                    continue;
                }
                if (leaf(type)) {
                    // A heap memory segment (the safe-mode DFA pointer table) holds its array.
                    if (HEAP_SEGMENT_BASE != null && HEAP_SEGMENT_BASE.getDeclaringClass().isInstance(object)) {
                        try {
                            push(stack, HEAP_SEGMENT_BASE.get(object));
                        }
                        catch (IllegalAccessException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    continue;
                }
                for (Field field : FIELDS.get(type)) {
                    Object value;
                    try {
                        value = field.get(object);
                    }
                    catch (IllegalAccessException e) {
                        continue;
                    }
                    push(stack, value);
                }
            }
        }

        private void push(ArrayDeque<Object> stack, Object value)
        {
            if (value == null || skip(value) || exclude.contains(value) || seen.put(value, Boolean.TRUE) != null) {
                return;
            }
            stack.push(value);
        }

        private static boolean skip(Object value)
        {
            return value instanceof Class<?> || value instanceof ClassLoader || value instanceof Thread
                    || value instanceof ThreadGroup || value instanceof Module || value instanceof java.lang.ref.ReferenceQueue<?>;
        }

        private static boolean leaf(Class<?> type)
        {
            String name = type.getName();
            return name.startsWith("java.lang.invoke.") || name.startsWith("jdk.internal.foreign.") || name.startsWith("jdk.internal.ref.");
        }
    }

    static final class Reference
    {
        private Reference() {}

        static void reachabilityFence(Object object)
        {
            java.lang.ref.Reference.reachabilityFence(object);
        }
    }

    static final class Hashing
    {
        private Hashing() {}

        static long hash(Object value)
        {
            return switch (value) {
                case null -> 0;
                case Boolean b -> b ? 1231 : 1237;
                case Number n -> n.longValue();
                case Slice s -> s.hashCode() * 31L + s.length();
                case String s -> s.hashCode();
                case List<?> list -> {
                    long h = 7;
                    for (Object item : list) {
                        h = h * 31 + hash(item);
                    }
                    yield h;
                }
                case Object[] array -> hash(Arrays.asList(array));
                case Record record -> {
                    long h = 11;
                    for (RecordComponent component : record.getClass().getRecordComponents()) {
                        try {
                            component.getAccessor().setAccessible(true);
                            h = h * 31 + hash(component.getAccessor().invoke(record));
                        }
                        catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    yield h;
                }
                default -> value.toString().hashCode();
            };
        }
    }

    // ------------------------------------------------------------------------------------------
    // Rows

    interface Row
    {
        /// Compiles the row's pattern; `budget` 0 means the benchmark's own default compile.
        Object compile(long budget)
                throws Exception;

        default Object[] matchers(Object pattern)
                throws Exception
        {
            return new Object[0];
        }

        default Object[] matcherRoots(Object[] matchers)
        {
            return matchers;
        }

        Object op(Object pattern, Object[] matchers, long index)
                throws Exception;

        Object[] inputs();

        default Object[] extraRoots()
        {
            return new Object[0];
        }

        default boolean compileOnly()
        {
            return false;
        }

        default boolean singleShot()
        {
            return false;
        }

        /// The fewest operations after which a stable DFA signature may end the run.
        default long minStableOps()
        {
            return 1000;
        }

        default boolean hasBudget()
        {
            return true;
        }

        default boolean generated(Object pattern)
        {
            return false;
        }

        default String describe(Object pattern)
        {
            return pattern.getClass().getSimpleName();
        }

        default String note()
        {
            return "";
        }

        default void release() {}
    }

    static boolean usesGenerated(Object pattern)
    {
        return switch (pattern) {
            case Re2 re2 -> re2.usesGeneratedBytecode();
            case TrinoRegexp trino -> trino.usesGeneratedBytecode();
            case JavaRegexp java -> java.usesGeneratedBytecode();
            case RustRegexp rust -> rust.usesGeneratedBytecode();
            case TrinoLikePattern like -> like.usesGeneratedBytecode();
            default -> false;
        };
    }

    static final class Rows
    {
        private Rows() {}

        static Row create(Map<String, String> job)
                throws Exception
        {
            return switch (job.get("family")) {
                case "lifecycle" -> new LifecycleRow(job.get("engine"), job.get("op"), job.get("file"));
                case "bulk" -> new BulkRow(job.get("engine"), job.get("file"), Long.parseLong(job.getOrDefault("expected", "0")));
                case "everyday" -> new EverydayRow(job.get("engine"), job.get("param"), job.get("op"));
                case "specialized" -> new SpecializedRow(job.get("lang"), job.get("engine"), job.get("variant"), job.get("param"), job.get("op"));
                case "like" -> new LikeRow(job.get("kind"), job.get("param"), Boolean.parseBoolean(job.getOrDefault("generated", "false")));
                case "rebar" -> new RebarRow(job.get("lang"), job.get("file"));
                default -> throw new IllegalArgumentException("unknown family " + job.get("family"));
            };
        }
    }

    // --- regulator option mirrors for the budget sweep ---

    static Re2.Options re2Options(long budget)
    {
        Re2.Options options = Re2.Options.defaults();
        return budget > 0 ? options.setMaxMemory(budget) : options;
    }

    static JavaRegexp.Options javaOptions(long budget)
    {
        JavaRegexp.Options options = JavaRegexp.Options.defaults();
        return budget > 0 ? options.setMaxMemory(budget) : options;
    }

    static TrinoRegexp.Options trinoOptions(long budget)
    {
        TrinoRegexp.Options options = TrinoRegexp.Options.defaults();
        return budget > 0 ? options.setMaxMemory(budget) : options;
    }

    static RustRegexp.Options rustOptions(long budget)
    {
        RustRegexp.Options options = RustRegexp.Options.defaults();
        return budget > 0 ? options.setMaxMemory(budget) : options;
    }

    static Regex joni(Slice pattern, int option)
    {
        return new Regex(pattern.byteArray(), pattern.byteArrayOffset(), pattern.byteArrayOffset() + pattern.length(),
                option, NonStrictUTF8Encoding.INSTANCE, Syntax.Java);
    }

    /// Finds up to `limit` matches with a new Joni matcher, as Trino's byte-array route does.
    static long joniFindAll(Regex regex, Slice source, long limit)
    {
        int base = source.byteArrayOffset();
        io.airlift.joni.Matcher matcher = regex.matcher(source.byteArray(), base, base + source.length());
        long count = 0;
        int next = 0;
        long hash = 0;
        while (count < limit && next <= source.length() && matcher.search(base + next, base + source.length(), Option.DEFAULT) >= 0) {
            count++;
            // Joni reports match positions relative to the matcher's start offset.
            int begin = matcher.getBegin();
            int end = matcher.getEnd();
            hash = hash * 31 + begin * 7L + end;
            next = end;
            if (begin == end) {
                next += end == source.length() ? 1 : lengthOfCodePoint(source, end);
            }
        }
        return hash * 31 + count;
    }

    // --- language-lifecycle ---

    static final class LifecycleRow
            implements Row
    {
        private static final MethodHandle CREATE_MATCHER;

        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(BenchmarkLanguageComparison.class, MethodHandles.lookup());
                CREATE_MATCHER = lookup.findStatic(BenchmarkLanguageComparison.class, "createMatcher",
                        MethodType.methodType(BenchmarkLanguageComparison.Compiled.class, Object.class, TestingLanguageBenchmarkInputs.Source.class));
            }
            catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final String engine;
        private final String operation;
        private final TestingLanguageBenchmarkInputs inputs;
        private final List<TestingLanguageBenchmarkInputs.Source> sources;

        LifecycleRow(String engine, String operation, String file)
                throws IOException
        {
            this.engine = engine;
            this.operation = operation;
            this.inputs = TestingLanguageBenchmarkInputs.read(Path.of(file));
            this.sources = inputs.sources();
        }

        @Override
        public Object compile(long budget)
        {
            if (budget == 0) {
                return BenchmarkLanguageComparison.compilePattern(engine, inputs);
            }
            return switch (engine) {
                case "re2" -> Re2.compile(inputs.pattern(), re2Options(budget));
                case "java" -> JavaRegexp.compile(inputs.pattern(), javaOptions(budget));
                case "trino" -> TrinoRegexp.compile(inputs.pattern(), trinoOptions(budget));
                case "rust" -> RustRegexp.compile(inputs.pattern(), rustOptions(budget));
                default -> throw new IllegalStateException("no budget for " + engine);
            };
        }

        @Override
        public boolean hasBudget()
        {
            return !engine.equals("jdk") && !engine.equals("joni");
        }

        @Override
        public Object[] matchers(Object pattern)
                throws Exception
        {
            if (operation.startsWith("reused")) {
                try {
                    return new Object[] {(BenchmarkLanguageComparison.Compiled) CREATE_MATCHER.invoke(pattern, sources.getFirst())};
                }
                catch (Throwable t) {
                    throw new Exception(t);
                }
            }
            return new Object[0];
        }

        @Override
        public Object op(Object pattern, Object[] matchers, long index)
                throws Exception
        {
            TestingLanguageBenchmarkInputs.Source source = sources.get((int) (index % sources.size()));
            return switch (operation) {
                case "reusedContains" -> ((BenchmarkLanguageComparison.Compiled) matchers[0]).contains(source);
                case "reusedCount" -> ((BenchmarkLanguageComparison.Compiled) matchers[0]).count(source);
                case "singleUseContains" -> switch (pattern) {
                    case Re2 re2 -> re2.find(source.bytes());
                    case JavaRegexp java -> java.find(source.bytes());
                    case TrinoRegexp trino -> trino.contains(source.bytes());
                    case RustRegexp rust -> rust.find(source.bytes());
                    case Pattern jdk -> jdk.matcher(source.text()).find();
                    default -> createMatcher(pattern, source).contains(source);
                };
                case "singleUseCount" -> switch (pattern) {
                    case Re2 re2 -> re2.count(source.bytes());
                    case JavaRegexp java -> java.count(source.bytes());
                    case TrinoRegexp trino -> trino.count(source.bytes());
                    case RustRegexp rust -> rust.count(source.bytes());
                    case Pattern jdk -> {
                        java.util.regex.Matcher matcher = jdk.matcher(source.text());
                        long count = 0;
                        while (matcher.find()) {
                            count++;
                        }
                        yield count;
                    }
                    default -> createMatcher(pattern, source).count(source);
                };
                default -> throw new IllegalStateException("operation " + operation);
            };
        }

        private static BenchmarkLanguageComparison.Compiled createMatcher(Object pattern, TestingLanguageBenchmarkInputs.Source source)
                throws Exception
        {
            try {
                return (BenchmarkLanguageComparison.Compiled) CREATE_MATCHER.invoke(pattern, source);
            }
            catch (Throwable t) {
                throw new Exception(t);
            }
        }

        @Override
        public Object[] inputs()
        {
            return new Object[] {inputs};
        }

        @Override
        public boolean compileOnly()
        {
            return operation.equals("compile");
        }

        @Override
        public boolean singleShot()
        {
            return operation.startsWith("singleUse");
        }

        @Override
        public long minStableOps()
        {
            return Math.max(1000, 10L * sources.size());
        }
    }

    // --- language-bulk: reuses BenchmarkLanguageBulk.BenchmarkData and its execute() ---

    static final class BulkRow
            implements Row
    {
        private static final Map<String, Field> FIELDS = new java.util.HashMap<>();

        static {
            for (String name : List.of("input", "patternBytes", "patternText", "rustBytes", "pattern", "sliceMatcher", "jdkMatcher", "operation", "sources")) {
                try {
                    Field field = BenchmarkLanguageBulk.BenchmarkData.class.getDeclaredField(name);
                    field.setAccessible(true);
                    FIELDS.put(name, field);
                }
                catch (NoSuchFieldException e) {
                    throw new ExceptionInInitializerError(e);
                }
            }
        }

        private final String engine;
        private final BenchmarkLanguageBulk.BenchmarkData base;
        private final RebarRunner.Benchmark input;
        private final String model;
        private final long expected;
        private String note = "";

        BulkRow(String engine, String file, long expected)
                throws Exception
        {
            this.engine = engine;
            this.expected = expected;
            base = new BenchmarkLanguageBulk.BenchmarkData();
            base.engine = engine;
            base.workloadFile = file;
            base.expectedResult = expected;
            base.initialize();
            input = (RebarRunner.Benchmark) get(base, "input");
            model = input.model();
            // The base state's own compile is not measured; drop it so it is not retained.
            FIELDS.get("pattern").set(base, null);
            FIELDS.get("sliceMatcher").set(base, null);
            FIELDS.get("jdkMatcher").set(base, null);
        }

        static Object get(Object target, String name)
        {
            try {
                return FIELDS.get(name).get(target);
            }
            catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        static void set(Object target, String name, Object value)
        {
            try {
                FIELDS.get(name).set(target, value);
            }
            catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Object compile(long budget)
        {
            if (budget == 0) {
                return base.compilePattern();
            }
            Slice patternBytes = (Slice) get(base, "patternBytes");
            boolean rustBytes = (boolean) get(base, "rustBytes");
            return switch (engine) {
                case "re2" -> Re2.compile(input.pattern(), input.options().setMaxMemory(budget));
                case "java" -> JavaRegexp.compile(patternBytes, JavaRegexp.Options.defaults()
                        .setCaseInsensitive(input.caseInsensitive())
                        .setUnicodeCase(input.unicode())
                        .setUnicodeCharacterClasses(input.unicode())
                        .setByteFrequencies(input.byteFrequencies())
                        .setMaxMemory(budget));
                case "trino" -> TrinoRegexp.compile(patternBytes, TrinoRegexp.Options.defaults().setByteFrequencies(input.byteFrequencies()).setMaxMemory(budget));
                case "rust" -> RustRegexp.compile(patternBytes, RustRegexp.Options.defaults()
                        .setCaseInsensitive(input.caseInsensitive())
                        .setUnicode(input.unicode())
                        .setBytes(rustBytes)
                        .setByteFrequencies(input.byteFrequencies())
                        .setMaxMemory(budget));
                default -> throw new IllegalStateException("no budget for " + engine);
            };
        }

        @Override
        public boolean hasBudget()
        {
            return !engine.equals("jdk") && !engine.equals("joni");
        }

        @Override
        public Object[] matchers(Object pattern)
        {
            if (model.equals("compile")) {
                return new Object[0];
            }
            BenchmarkLanguageBulk.BenchmarkData data = new BenchmarkLanguageBulk.BenchmarkData();
            data.engine = engine;
            data.workloadFile = base.workloadFile;
            data.expectedResult = expected;
            for (String name : List.of("input", "patternBytes", "patternText", "rustBytes", "operation", "sources")) {
                set(data, name, get(base, name));
            }
            set(data, "pattern", pattern);
            String operation = ((Enum<?>) get(base, "operation")).name();
            boolean captures = operation.equals("CAPTURES");
            Object sliceMatcher = operation.equals("FIND") ? null : switch (pattern) {
                case Re2 re2 -> captures ? re2.matcher(EMPTY_SLICE) : re2.matcher(EMPTY_SLICE, 0);
                case JavaRegexp java -> captures ? java.matcher(EMPTY_SLICE) : java.matcher(EMPTY_SLICE, 0);
                case RustRegexp rust -> captures ? rust.matcher(EMPTY_SLICE) : rust.matcher(EMPTY_SLICE, 0);
                default -> null;
            };
            set(data, "sliceMatcher", sliceMatcher);
            if (pattern instanceof Pattern jdk) {
                set(data, "jdkMatcher", jdk.matcher(""));
            }
            return new Object[] {data};
        }

        @Override
        public Object[] matcherRoots(Object[] matchers)
        {
            if (matchers.length == 0) {
                return matchers;
            }
            List<Object> roots = new ArrayList<>();
            Object slice = get(matchers[0], "sliceMatcher");
            Object jdk = get(matchers[0], "jdkMatcher");
            if (slice != null) {
                roots.add(slice);
            }
            if (jdk != null) {
                roots.add(jdk);
            }
            return roots.toArray();
        }

        @Override
        public Object op(Object pattern, Object[] matchers, long index)
        {
            long result = ((BenchmarkLanguageBulk.BenchmarkData) matchers[0]).execute();
            if (index == 0 && result != expected) {
                note = "first result " + result + " != expected " + expected;
            }
            return result;
        }

        @Override
        public Object[] inputs()
        {
            return new Object[] {input, get(base, "patternBytes"), get(base, "patternText"), get(base, "sources")};
        }

        @Override
        public boolean compileOnly()
        {
            return model.equals("compile");
        }

        @Override
        public long minStableOps()
        {
            return 100;
        }

        @Override
        public String note()
        {
            return note;
        }
    }

    // --- baseline trino-operations (BenchmarkEverydayTrinoRegexp) and its Joni comparator ---

    record JoniCompiled(Slice pattern, Regex regex) {}

    static final class EverydayRow
            implements Row
    {
        private final String engine;
        private final String operation;
        private final TestingEverydayTrinoRegexpBenchmarkInputs.Input input;
        private final List<TestingEverydayTrinoRegexpBenchmarkInputs.Source> sources;
        private final Slice replacement = utf8Slice("_");
        private final Function<List<Slice>, Slice> lambdaReplacement = _ -> replacement;

        EverydayRow(String engine, String workload, String operation)
        {
            this.engine = engine;
            this.operation = operation;
            this.input = TestingEverydayTrinoRegexpBenchmarkInputs.create(workload);
            this.sources = input.sources();
        }

        @Override
        public Object compile(long budget)
        {
            if (engine.equals("joni")) {
                Slice pattern = input.pattern().copy();
                return new JoniCompiled(pattern, joni(pattern, Option.DEFAULT));
            }
            return budget == 0 ? TrinoRegexp.compile(input.pattern()) : TrinoRegexp.compile(input.pattern(), trinoOptions(budget));
        }

        @Override
        public boolean hasBudget()
        {
            return engine.equals("regulator");
        }

        @Override
        public Object op(Object pattern, Object[] matchers, long index)
        {
            Slice source = sources.get((int) (index & (sources.size() - 1))).value();
            if (pattern instanceof JoniCompiled joni) {
                long limit = switch (operation) {
                    case "contains", "extract" -> 1;
                    case "positionThird" -> 3;
                    default -> Long.MAX_VALUE;
                };
                return joniFindAll(joni.regex(), source, limit);
            }
            TrinoRegexp regexp = (TrinoRegexp) pattern;
            return switch (operation) {
                case "contains" -> regexp.contains(source);
                case "count" -> regexp.count(source);
                case "positionThird" -> regexp.position(source, 1, 3);
                case "extract" -> regexp.extract(source);
                case "extractAll" -> regexp.extractAll(source);
                case "split" -> regexp.split(source);
                case "replace" -> regexp.replace(source, replacement);
                case "replaceLambda" -> regexp.replace(source, lambdaReplacement);
                default -> throw new IllegalStateException(operation);
            };
        }

        @Override
        public Object[] inputs()
        {
            return new Object[] {input, replacement, lambdaReplacement};
        }

        @Override
        public long minStableOps()
        {
            return Math.max(1000, 10L * sources.size());
        }

        @Override
        public String note()
        {
            return engine.equals("joni") ? "joni op approximates Trino JoniRegexpFunctions (new Matcher per call)" : "";
        }
    }

    // --- baseline <lang>-specialized-patterns ---

    static final class SpecializedRow
            implements Row
    {
        private final String language;
        private final String engine;
        private final String variant;
        private final String operation;
        private final TestingSpecializedPatternBenchmarkInputs.Workload workload;
        private final Slice[] sources;
        private final String[] strings;
        private final Slice rewrite;

        SpecializedRow(String language, String engine, String variant, String workloadId, String operation)
        {
            this.language = language;
            this.engine = engine;
            this.variant = variant;
            this.operation = operation;
            this.workload = TestingSpecializedPatternBenchmarkInputs.create(workloadId);
            this.sources = workload.sources().stream().map(TestingEverydayTrinoRegexpBenchmarkInputs.Source::value).toArray(Slice[]::new);
            this.strings = engine.equals("jdk") ? Arrays.stream(sources).map(Slice::toStringUtf8).toArray(String[]::new) : null;
            this.rewrite = utf8Slice("\\" + workload.capturedGroup());
        }

        @Override
        public Object compile(long budget)
        {
            Slice pattern = workload.pattern();
            if (engine.equals("jdk")) {
                return Pattern.compile(pattern.toStringUtf8());
            }
            if (engine.equals("joni")) {
                Slice copy = pattern.copy();
                return new JoniCompiled(copy, joni(copy, Option.DEFAULT));
            }
            boolean generated = variant.equals("generated");
            boolean regular = variant.equals("regular-engine");
            return switch (language) {
                case "re2" -> {
                    Re2.Options options = re2Options(budget).setGenerateBytecode(generated);
                    yield regular ? Re2.compileWithoutScanPlanForTesting(pattern, options) : Re2.compile(pattern, options);
                }
                case "java" -> {
                    JavaRegexp.Options options = javaOptions(budget).setGenerateBytecode(generated);
                    yield regular ? JavaRegexp.compileWithoutScanPlanForTesting(pattern, options) : JavaRegexp.compile(pattern, options);
                }
                case "trino" -> {
                    TrinoRegexp.Options options = trinoOptions(budget).setGenerateBytecode(generated);
                    yield regular ? TrinoRegexp.compileWithoutScanPlanForTesting(pattern, options) : TrinoRegexp.compile(pattern, options);
                }
                case "rust" -> {
                    RustRegexp.Options options = rustOptions(budget).setGenerateBytecode(generated);
                    yield regular ? RustRegexp.compileWithoutScanPlanForTesting(pattern, options) : RustRegexp.compile(pattern, options);
                }
                default -> throw new IllegalStateException(language);
            };
        }

        @Override
        public boolean hasBudget()
        {
            return engine.equals("regulator");
        }

        @Override
        public boolean generated(Object pattern)
        {
            return usesGenerated(pattern);
        }

        @Override
        public Object[] matchers(Object pattern)
        {
            return switch (pattern) {
                case Re2 re2 -> new Object[] {re2.matcher(EMPTY_SLICE)};
                case JavaRegexp java -> new Object[] {java.matcher(EMPTY_SLICE)};
                case RustRegexp rust -> new Object[] {rust.matcher(EMPTY_SLICE)};
                case Pattern jdk -> new Object[] {jdk.matcher("")};
                default -> new Object[0];
            };
        }

        @Override
        public Object op(Object pattern, Object[] matchers, long index)
        {
            int at = (int) (index & (sources.length - 1));
            Slice source = sources[at];
            int group = workload.capturedGroup();
            return switch (pattern) {
                case Re2 re2 -> switch (operation) {
                    case "replace" -> re2.replaceAll(source, rewrite);
                    case "extract" -> re2.extract(source, rewrite);
                    case "extractAll" -> BenchmarkSpecializedRe2.matcherExtractAll((RegexpMatcher) matchers[0], source, workload.group());
                    default -> throw new IllegalStateException(operation);
                };
                case TrinoRegexp trino -> switch (operation) {
                    case "replace" -> trino.replace(source, workload.replacement());
                    case "extract" -> trino.extract(source, workload.group());
                    case "extractAll" -> trino.extractAll(source, workload.group());
                    default -> throw new IllegalStateException(operation);
                };
                case JavaRegexp _, RustRegexp _ -> switch (operation) {
                    case "extract" -> BenchmarkSpecializedJavaRegexp.matcherExtract((RegexpMatcher) matchers[0], source, group);
                    case "extractAll" -> BenchmarkSpecializedRe2.matcherExtractAll((RegexpMatcher) matchers[0], source, group);
                    default -> throw new IllegalStateException(operation);
                };
                case Pattern _ -> {
                    java.util.regex.Matcher matcher = ((java.util.regex.Matcher) matchers[0]).reset(strings[at]);
                    if (operation.equals("extract")) {
                        yield matcher.find() ? matcher.group(group) : null;
                    }
                    List<String> values = new ArrayList<>();
                    while (matcher.find()) {
                        values.add(matcher.group(group));
                    }
                    yield values;
                }
                case JoniCompiled joni -> joniFindAll(joni.regex(), source, operation.equals("extract") ? 1 : Long.MAX_VALUE);
                default -> throw new IllegalStateException(pattern.getClass().getName());
            };
        }

        @Override
        public Object[] inputs()
        {
            return new Object[] {workload, sources, strings, rewrite};
        }

        @Override
        public long minStableOps()
        {
            return Math.max(1000, 10L * sources.length);
        }

        @Override
        public String note()
        {
            return engine.equals("joni") ? "joni op approximates Trino JoniRegexpFunctions (new Matcher per call)" : "";
        }
    }

    // --- Trino LIKE rows (BenchmarkTrinoLike and BenchmarkTrinoLikeTpch, Regulator side) ---

    static final class LikeRow
            implements Row
    {
        private static final Field WORKSPACE;

        static {
            try {
                WORKSPACE = TrinoLikeWildcardMatcher.class.getDeclaredField("WORKSPACE");
                WORKSPACE.setAccessible(true);
            }
            catch (NoSuchFieldException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final String kind;
        private final boolean generatedVariant;
        private final Slice pattern;
        private final Slice input;
        private final Slice[] rows;
        private final Object expected;

        LikeRow(String kind, String param, boolean generatedVariant)
                throws IOException
        {
            this.kind = kind;
            this.generatedVariant = generatedVariant;
            if (kind.equals("tpch")) {
                String[] spec = switch (param) {
                    case "Q02_TYPE_BRASS" -> new String[] {"part-type", "%BRASS"};
                    case "Q09_NAME_GREEN" -> new String[] {"part-name", "%green%"};
                    case "Q13_ORDER_COMMENT" -> new String[] {"orders-comment", "%special%requests%"};
                    case "Q14_TYPE_PROMO" -> new String[] {"part-type", "PROMO%"};
                    default -> throw new IllegalArgumentException(param);
                };
                pattern = utf8Slice(spec[1]);
                rows = loadRows(spec[0]);
                input = null;
                expected = null;
            }
            else {
                Object[] scenario = scenario(param);
                pattern = utf8Slice((String) scenario[0]);
                input = Slices.wrappedBuffer((byte[]) scenario[1]);
                expected = scenario[2];
                rows = null;
            }
        }

        static Object[] scenario(String name)
        {
            return switch (name) {
                case "EXACT_MATCH" -> new Object[] {"needle", bytes("needle"), true};
                case "PREFIX_LARGE" -> new Object[] {"needle%", inject(filled(32_768), bytes("needle"), 0), true};
                case "SUFFIX_LARGE" -> new Object[] {"%needle", inject(filled(32_768), bytes("needle"), 32_762), true};
                case "CONTAINS_ABSENT" -> new Object[] {"%needle%", filled(32_768), false};
                case "CONTAINS_LATE" -> new Object[] {"%needle%", inject(filled(32_768), bytes("needle"), 32_700), true};
                case "ORDERED_LATE" -> new Object[] {"%alpha%omega%", inject(inject(filled(32_768), bytes("alpha"), 31_900), bytes("omega"), 32_700), true};
                case "ORDERED_DENSE_FALSE" -> new Object[] {"%aab%bba%", denseFalse(), false};
                case "ANY_ASCII" -> new Object[] {"_", bytes("x"), true};
                case "ANY_MULTIBYTE" -> new Object[] {"_", bytes("💰"), true};
                case "MIXED_LATE" -> new Object[] {"%alpha_omega%", inject(filled(32_768), bytes("alpha💰omega"), 32_700), true};
                case "MIXED_ABSENT" -> new Object[] {"%alpha_omega%", filled(32_768), false};
                case "WILDCARD_CHAIN" -> new Object[] {"%alpha_bravo_charlie%", inject(filled(1_024), bytes("alpha-bravo-charlie"), 900), true};
                default -> throw new IllegalArgumentException(name);
            };
        }

        static byte[] bytes(String value)
        {
            return value.getBytes(UTF_8);
        }

        static byte[] filled(int length)
        {
            byte[] input = new byte[length];
            Arrays.fill(input, (byte) 'x');
            return input;
        }

        static byte[] inject(byte[] input, byte[] value, int offset)
        {
            System.arraycopy(value, 0, input, offset, value.length);
            return input;
        }

        static byte[] denseFalse()
        {
            byte[] input = filled(32_768);
            byte[] candidate = bytes("aabx");
            for (int offset = 0; offset + candidate.length <= input.length; offset += candidate.length) {
                System.arraycopy(candidate, 0, input, offset, candidate.length);
            }
            return input;
        }

        static Slice[] loadRows(String column)
                throws IOException
        {
            String resource = "/io/airlift/regulator/tpch-like/" + column + ".txt.gzip";
            byte[] lines;
            try (InputStream in = MemoryProbe.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("missing " + resource);
                }
                lines = new GZIPInputStream(in).readAllBytes();
            }
            byte[] backing = new byte[3 + lines.length];
            List<Slice> rows = new ArrayList<>();
            int position = 3;
            int start = 0;
            for (int index = 0; index < lines.length; index++) {
                if (lines[index] == '\n') {
                    int length = index - start;
                    System.arraycopy(lines, start, backing, position, length);
                    rows.add(Slices.wrappedBuffer(backing, position, length));
                    position += length;
                    start = index + 1;
                }
            }
            return rows.toArray(Slice[]::new);
        }

        @Override
        public Object compile(long budget)
        {
            if (generatedVariant) {
                return TrinoLikePattern.compile(pattern, TrinoLikePattern.Options.defaults().setGenerateBytecode(true));
            }
            return TrinoLikePattern.compile(pattern);
        }

        @Override
        public boolean hasBudget()
        {
            return false;
        }

        @Override
        public boolean generated(Object pattern)
        {
            return usesGenerated(pattern);
        }

        @Override
        public Object op(Object compiled, Object[] matchers, long index)
        {
            TrinoLikePattern like = (TrinoLikePattern) compiled;
            if (rows != null) {
                int count = 0;
                for (Slice row : rows) {
                    if (like.matches(row)) {
                        count++;
                    }
                }
                return count;
            }
            boolean result = like.matches(input);
            if (result != (boolean) expected) {
                throw new IllegalStateException("LIKE outcome mismatch");
            }
            return result;
        }

        @Override
        public Object[] extraRoots()
        {
            try {
                // A per-thread workspace that wildcard matching reuses; counted as matcher state.
                Object local = WORKSPACE.get(null);
                return new Object[] {((ThreadLocal<?>) local).get()};
            }
            catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Object[] inputs()
        {
            return new Object[] {pattern, input, rows};
        }

        @Override
        public boolean compileOnly()
        {
            return kind.equals("compile");
        }

        @Override
        public boolean singleShot()
        {
            return kind.equals("single-use");
        }

        @Override
        public long minStableOps()
        {
            return rows != null ? 100 : 1000;
        }

        @Override
        public String note()
        {
            return "settled/peak include the calling thread's LIKE wildcard workspace when allocated";
        }
    }

    // --- rebar rows: a copy of the rebar Regulator runner's models (rebar/engines/regulator Main) ---

    static final class RebarRow
            implements Row
    {
        private static final long LARGE_PATTERN_MAX_MEMORY = 384L << 20;

        private final String syntax;
        private final String model;
        private final List<Slice> patterns = new ArrayList<>();
        private boolean caseInsensitive;
        private boolean unicode;
        private byte[] haystackBytes = new byte[0];
        private Slice haystack = EMPTY_SLICE;
        private long defaultBudget = -1;
        private String note = "";

        RebarRow(String syntax, String file)
                throws IOException
        {
            this.syntax = syntax;
            byte[] raw = Files.readAllBytes(Path.of(file));
            String model = null;
            int at = 0;
            while (at < raw.length) {
                int keyEnd = indexOf(raw, at, (byte) ':');
                String key = new String(raw, at, keyEnd - at, StandardCharsets.US_ASCII);
                int lengthEnd = indexOf(raw, keyEnd + 1, (byte) ':');
                int length = Integer.parseInt(new String(raw, keyEnd + 1, lengthEnd - keyEnd - 1, StandardCharsets.US_ASCII));
                int start = lengthEnd + 1;
                int end = start + length;
                String text = new String(raw, start, length, UTF_8);
                switch (key) {
                    case "model" -> model = text;
                    case "pattern" -> patterns.add(Slices.wrappedBuffer(Arrays.copyOfRange(raw, start, end)));
                    case "case-insensitive" -> caseInsensitive = Boolean.parseBoolean(text);
                    case "unicode" -> unicode = Boolean.parseBoolean(text);
                    case "haystack" -> {
                        haystackBytes = Arrays.copyOfRange(raw, start, end);
                        haystack = Slices.wrappedBuffer(haystackBytes);
                    }
                    default -> {}
                }
                at = end + 1;
            }
            this.model = model;
        }

        private static int indexOf(byte[] raw, int from, byte target)
        {
            for (int i = from; i < raw.length; i++) {
                if (raw[i] == target) {
                    return i;
                }
            }
            return -1;
        }

        Object compileOne(Slice pattern, long maxMemory)
        {
            if (syntax.equals("rust")) {
                RustRegexp.Options options = RustRegexp.Options.bytes().setUnicode(unicode).setCaseInsensitive(caseInsensitive);
                if (maxMemory > 0) {
                    options = options.setMaxMemory(maxMemory);
                }
                return RustRegexp.compile(pattern, options);
            }
            Re2.Options options = unicode ? Re2.Options.defaults() : Re2.Options.latin1();
            options = options.setCaseSensitive(!caseInsensitive);
            if (maxMemory > 0) {
                options = options.setMaxMemory(maxMemory);
            }
            return Re2.compile(pattern, options);
        }

        @Override
        public Object compile(long budget)
        {
            if (model.equals("regex-redux")) {
                List<Object> compiled = new ArrayList<>();
                for (String source : RegexRedux.PATTERNS) {
                    compiled.add(compileOne(utf8Slice(source), budget));
                }
                return compiled.toArray();
            }
            if (budget > 0) {
                return compileOne(patterns.getFirst(), budget);
            }
            // The runner's first, untimed compile decides the budget: the default, or a larger
            // one for patterns the default rejects.
            if (defaultBudget < 0) {
                try {
                    Object compiled = compileOne(patterns.getFirst(), 0);
                    defaultBudget = 0;
                    return compiled;
                }
                catch (RegexpCompileMemoryLimitException e) {
                    defaultBudget = LARGE_PATTERN_MAX_MEMORY;
                    note = "runner budget 384 MiB (default rejects)";
                }
            }
            return compileOne(patterns.getFirst(), defaultBudget);
        }

        @Override
        public Object[] matchers(Object pattern)
        {
            return switch (model) {
                case "count-spans" -> new Object[] {matcher(pattern, true)};
                case "count-captures", "grep-captures" -> new Object[] {matcher(pattern, false)};
                default -> new Object[0];
            };
        }

        private RegexpMatcher matcher(Object pattern, boolean spans)
        {
            return switch (pattern) {
                case Re2 re2 -> spans ? re2.matcher(haystack, 0) : re2.matcher(haystack);
                case RustRegexp rust -> spans ? rust.matcher(haystack, 0) : rust.matcher(haystack);
                default -> throw new IllegalStateException();
            };
        }

        private static long count(Object pattern, Slice haystack)
        {
            return pattern instanceof Re2 re2 ? re2.count(haystack) : ((RustRegexp) pattern).count(haystack);
        }

        private static boolean find(Object pattern, Slice haystack)
        {
            return pattern instanceof Re2 re2 ? re2.find(haystack) : ((RustRegexp) pattern).find(haystack);
        }

        @Override
        public Object op(Object pattern, Object[] matchers, long index)
        {
            switch (model) {
                case "compile", "count":
                    return count(pattern, haystack);
                case "count-spans": {
                    RegexpMatcher m = (RegexpMatcher) matchers[0];
                    m.reset(haystack);
                    long sum = 0;
                    while (m.find()) {
                        sum += m.end() - m.start();
                    }
                    return sum;
                }
                case "count-captures": {
                    RegexpMatcher m = (RegexpMatcher) matchers[0];
                    m.reset(haystack);
                    long count = 0;
                    while (m.find()) {
                        count += participatingGroups(m);
                    }
                    return count;
                }
                case "grep": {
                    long count = 0;
                    int start = 0;
                    while (start < haystackBytes.length) {
                        int end = lineEnd(haystackBytes, start);
                        if (find(pattern, haystack.slice(start, contentEnd(haystackBytes, start, end) - start))) {
                            count++;
                        }
                        start = end;
                    }
                    return count;
                }
                case "grep-captures": {
                    RegexpMatcher m = (RegexpMatcher) matchers[0];
                    long count = 0;
                    int start = 0;
                    while (start < haystackBytes.length) {
                        int end = lineEnd(haystackBytes, start);
                        m.reset(haystack.slice(start, contentEnd(haystackBytes, start, end) - start));
                        while (m.find()) {
                            count += participatingGroups(m);
                        }
                        start = end;
                    }
                    return count;
                }
                case "regex-redux":
                    return RegexRedux.run((Object[]) pattern, haystackBytes);
                default:
                    throw new IllegalArgumentException(model);
            }
        }

        private static int participatingGroups(RegexpMatcher m)
        {
            int count = 0;
            for (int group = 0; group <= m.groupCount(); group++) {
                if (m.matched(group)) {
                    count++;
                }
            }
            return count;
        }

        private static int lineEnd(byte[] bytes, int start)
        {
            for (int i = start; i < bytes.length; i++) {
                if (bytes[i] == '\n') {
                    return i + 1;
                }
            }
            return bytes.length;
        }

        private static int contentEnd(byte[] bytes, int start, int end)
        {
            if (end > start && bytes[end - 1] == '\n') {
                end--;
                if (end > start && bytes[end - 1] == '\r') {
                    end--;
                }
            }
            return end;
        }

        @Override
        public Object[] inputs()
        {
            return new Object[] {haystackBytes, haystack, patterns};
        }

        @Override
        public boolean singleShot()
        {
            return model.equals("compile") || model.equals("regex-redux");
        }

        @Override
        public long minStableOps()
        {
            return 100;
        }

        @Override
        public String note()
        {
            String base = model.equals("compile") ? "compile model: one compile plus one count"
                    : model.equals("regex-redux") ? "regex-redux: 15 patterns compiled up front, one run" : "";
            return String.join("; ", List.of(base, note).stream().filter(s -> !s.isEmpty()).toList());
        }
    }

    /// A port of the rebar runner's regex-redux model over pre-compiled patterns.
    static final class RegexRedux
    {
        static final String[] VARIANTS = {
                "agggtaaa|tttaccct",
                "[cgt]gggtaaa|tttaccc[acg]",
                "a[act]ggtaaa|tttacc[agt]t",
                "ag[act]gtaaa|tttac[agt]ct",
                "agg[act]taaa|ttta[agt]cct",
                "aggg[acg]aaa|ttt[cgt]ccct",
                "agggt[cgt]aa|tt[acg]accct",
                "agggta[cgt]a|t[acg]taccct",
                "agggtaa[cgt]|[acg]ttaccct",
        };
        static final String[] PATTERNS;

        static {
            List<String> all = new ArrayList<>();
            all.add(">[^\\n]*\\n|\\n");
            all.addAll(Arrays.asList(VARIANTS));
            all.addAll(List.of("tHa[Nt]", "aND|caN|Ha[DS]|WaS", "a[NSt]|BY", "<[^>]*>", "\\|[^|][^|]*\\|"));
            PATTERNS = all.toArray(String[]::new);
        }

        private RegexRedux() {}

        static long run(Object[] compiled, byte[] haystackBytes)
        {
            Slice seq = Slices.wrappedBuffer(haystackBytes.clone());
            seq = replaceAll(seq, "", finder(compiled[0]));
            long counts = 0;
            for (int i = 0; i < VARIANTS.length; i++) {
                counts = counts * 31 + count(seq, finder(compiled[1 + i]));
            }
            String[] replacements = {"<4>", "<3>", "<2>", "|", "-"};
            for (int i = 0; i < replacements.length; i++) {
                seq = replaceAll(seq, replacements[i], finder(compiled[1 + VARIANTS.length + i]));
            }
            return counts * 31 + seq.length();
        }

        static RegexpMatcher finder(Object pattern)
        {
            return pattern instanceof Re2 re2 ? re2.matcher(EMPTY_SLICE, 0) : ((RustRegexp) pattern).matcher(EMPTY_SLICE, 0);
        }

        static long count(Slice haystack, RegexpMatcher find)
        {
            long count = 0;
            int at = 0;
            while (find.reset(haystack.slice(at, haystack.length() - at)).find()) {
                at += find.end();
                count++;
            }
            return count;
        }

        static Slice replaceAll(Slice haystack, String replacement, RegexpMatcher find)
        {
            byte[] replacementBytes = replacement.getBytes(StandardCharsets.US_ASCII);
            byte[] bytes = haystack.byteArray();
            int offset = haystack.byteArrayOffset();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(haystack.length());
            int at = 0;
            while (find.reset(haystack.slice(at, haystack.length() - at)).find()) {
                out.write(bytes, offset + at, find.start());
                out.write(replacementBytes, 0, replacementBytes.length);
                at += find.end();
            }
            out.write(bytes, offset + at, haystack.length() - at);
            return Slices.wrappedBuffer(out.toByteArray());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Minimal JSON writer

    static final class Json
    {
        private final StringBuilder body = new StringBuilder();
        long ops;
        String signature;

        Json put(String key, Object value)
        {
            if (!body.isEmpty()) {
                body.append(',');
            }
            body.append(quote(key)).append(':');
            switch (value) {
                case null -> body.append("null");
                case Number n -> body.append(n);
                case Boolean b -> body.append(b);
                default -> body.append(quote(value.toString()));
            }
            return this;
        }

        Json putRaw(String key, String raw)
        {
            if (!body.isEmpty()) {
                body.append(',');
            }
            body.append(quote(key)).append(':').append(raw);
            return this;
        }

        static String array(List<Json> items)
        {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                b.append(items.get(i));
            }
            return b.append(']').toString();
        }

        static String quote(String s)
        {
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            b.append(String.format("\\u%04x", (int) c));
                        }
                        else {
                            b.append(c);
                        }
                    }
                }
            }
            return b.append('"').toString();
        }

        @Override
        public String toString()
        {
            return "{" + body + "}";
        }
    }
}
