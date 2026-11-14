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

import com.sun.management.HotSpotDiagnosticMXBean;
import io.airlift.slice.SizeOf;

import java.lang.management.ManagementFactory;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToLongFunction;

/// The sizes of the JDK objects that retained-size estimates count: unmodifiable lists and maps,
/// boxes, strings, collections grown from empty, and the internal layouts of JDK locks, reference
/// queues, and native-memory cleanups. Each owner keeps the sizes of its own classes.
// Loaded only when a size is estimated: parsing never sizes objects, and TeaVM cannot.
final class RetainedSizeSupport
{
    // Each class's instance size, computed once per class, for classes known only at run time,
    // such as the shape classes of every language. A generated class adds no field to the shape
    // class it extends.
    private static final ClassValue<Long> INSTANCE_SIZES = new ClassValue<>()
    {
        @Override
        protected Long computeValue(Class<?> type)
        {
            return (long) SizeOf.instanceSize(type);
        }
    };
    // The two classes of an unmodifiable list from List.of or List.copyOf: one of one or two
    // fields, and one of an array, whose empty instance every empty list shares.
    private static final int SMALL_LIST_INSTANCE_SIZE = SizeOf.instanceSize(List.of(1).getClass());
    private static final int LIST_INSTANCE_SIZE = SizeOf.instanceSize(List.of(1, 2, 3).getClass());
    private static final int STRING_INSTANCE_SIZE = SizeOf.instanceSize(String.class);
    private static final int INTEGER_INSTANCE_SIZE = SizeOf.instanceSize(Integer.class);
    private static final int ARRAY_LIST_INSTANCE_SIZE = SizeOf.instanceSize(ArrayList.class);
    private static final int HASH_MAP_INSTANCE_SIZE = SizeOf.instanceSize(HashMap.class);
    private static final int HASH_MAP_NODE_INSTANCE_SIZE = SizeOf.instanceSize(new HashMap<>(Map.of(0, 0)).entrySet().iterator().next().getClass());
    // HashMap's rules: a bin of more than eight nodes becomes a tree once the table has 64 slots,
    // and grows a smaller table instead; a resize turns a tree half of six nodes or fewer back
    // into a list.
    private static final int TREEIFY_THRESHOLD = 8;
    private static final int MIN_TREEIFY_CAPACITY = 64;
    private static final int UNTREEIFY_THRESHOLD = 6;
    private static final int HASH_MAP_TREE_NODE_INSTANCE_SIZE = SizeOf.instanceSize(treeifiedMap().entrySet().iterator().next().getClass());
    // The key set and value collection an unmodifiable map makes once each is asked for.
    static final long MAP_VIEWS_SIZE = mapViewsSize(Map.of(0, 0));
    static final int ATOMIC_REFERENCE_INSTANCE_SIZE = SizeOf.instanceSize(AtomicReference.class);
    static final int ATOMIC_LONG_ARRAY_INSTANCE_SIZE = SizeOf.instanceSize(AtomicLongArray.class);
    // A ReentrantLock and its synchronizer, which adds no field to the queued synchronizer's.
    // Once two threads have contended for the lock, the synchronizer keeps one queue node as its
    // head for good; a waiter that acquires the lock replaces the head and unlinks the old one, so
    // no more than one stays once contention ends. It is counted whether or not the lock was ever
    // contended.
    static final long LOCK_BYTES = (long) SizeOf.instanceSize(ReentrantLock.class) + SizeOf.instanceSize(AbstractQueuedSynchronizer.class) + SizeOf.instanceSize(LockQueueNodeLayout.class);
    // A ReferenceQueue and its empty lock object.
    static final long REFERENCE_QUEUE_BYTES = (long) SizeOf.instanceSize(ReferenceQueue.class) + SizeOf.instanceSize(Object.class);
    // What an automatic arena keeps to free one native allocation: its resource list and the
    // cleanup that frees the allocation.
    static final long NATIVE_CLEANUP_BYTES = (long) SizeOf.instanceSize(NativeResourceListLayout.class) + SizeOf.instanceSize(NativeCleanupLayout.class);
    // Whether a string of Latin-1 characters stores one byte each, as it does unless the JVM
    // runs with -XX:-CompactStrings. A JVM that cannot say is taken to store two.
    private static final boolean COMPACT_STRINGS = compactStrings();

    private RetainedSizeSupport() {}

    static long instanceSize(Class<?> type)
    {
        return INSTANCE_SIZES.get(type);
    }

    /// The bytes of `list`, an unmodifiable list from `List.of` or `List.copyOf`, without its
    /// elements.
    static long unmodifiableListSize(List<?> list)
    {
        int size = list.size();
        if (size == 0) {
            return 0;
        }
        return size <= 2 ? SMALL_LIST_INSTANCE_SIZE : LIST_INSTANCE_SIZE + SizeOf.sizeOfObjectArray(size);
    }

    /// The bytes of `map`, an unmodifiable map from `Map.of` or `Map.copyOf`, without its keys
    /// and values. A map of one entry holds it in two fields; a larger one probes a table of
    /// twice as many slots as it has keys and values. Every empty map is one shared instance.
    static long unmodifiableMapSize(Map<?, ?> map)
    {
        int size = map.size();
        if (size == 0) {
            return 0;
        }
        long instanceSize = instanceSize(map.getClass());
        return size == 1 ? instanceSize : instanceSize + SizeOf.sizeOfObjectArray(4 * size);
    }

    /// The bytes of `value`'s box, or zero for a value whose box the JDK caches.
    static long boxSize(int value)
    {
        return value >= -128 && value <= 127 ? 0 : INTEGER_INSTANCE_SIZE;
    }

    /// The bytes of `string` and its value array, which holds one byte per character when
    /// every character is Latin-1 and the JVM compacts strings, and two otherwise.
    static long stringSize(String string)
    {
        int length = string.length();
        boolean latin1 = COMPACT_STRINGS;
        for (int index = 0; latin1 && index < length; index++) {
            latin1 = string.charAt(index) <= 0xFF;
        }
        return STRING_INSTANCE_SIZE + SizeOf.sizeOfByteArray(latin1 ? length : 2 * length);
    }

    /// The bytes of `list`, an `ArrayList` filled one element at a time from empty, and the
    /// boxes of its `Integer` elements: its first element allocates ten slots, and each growth
    /// adds half.
    static long arrayListSize(List<?> list)
    {
        long size = ARRAY_LIST_INSTANCE_SIZE;
        if (!list.isEmpty()) {
            int capacity = 10;
            while (capacity < list.size()) {
                capacity += capacity >> 1;
            }
            size += SizeOf.sizeOfObjectArray(capacity);
        }
        for (Object element : list) {
            if (element instanceof Integer value) {
                size += boxSize(value);
            }
        }
        return size;
    }

    /// The bytes of `map`, a `HashMap` filled one entry at a time from empty, its keys as
    /// `keySize` sizes them, and the boxes of its values: its first entry allocates sixteen
    /// slots, which double whenever it is more than three quarters full, and each entry is a node.
    /// Keys whose hashes share a bin can grow the table further, and turn the bin into larger tree
    /// nodes, so the size takes the most the keys' bins allow: the table grows while a bin of the
    /// final keys would overflow it below 64 slots, and once it can reach 64, every node in a bin
    /// of seven or more at the smallest table the map can have counts as a tree node. Each table
    /// that splits a tree's bin is that table's or larger, so it holds every tree node in such
    /// bins. It reads the entries with `forEach`, since a key or entry view would stay on the map.
    static <K> long hashMapSize(Map<K, Integer> map, ToLongFunction<K> keySize)
    {
        long[] size = {HASH_MAP_INSTANCE_SIZE};
        if (!map.isEmpty()) {
            int[] hashes = new int[map.size()];
            int[] count = {0};
            map.forEach((key, _) -> {
                int hash = key.hashCode();
                hashes[count[0]++] = hash ^ (hash >>> 16);
            });
            int loadCapacity = 16;
            while (map.size() > loadCapacity / 4 * 3) {
                loadCapacity <<= 1;
            }
            int collisionCapacity = 16;
            while (collisionCapacity < MIN_TREEIFY_CAPACITY && largestBin(hashes, collisionCapacity) > TREEIFY_THRESHOLD) {
                collisionCapacity <<= 1;
            }
            int capacity = Math.max(loadCapacity, collisionCapacity);
            long treeNodes = capacity < MIN_TREEIFY_CAPACITY ? 0 : treeBinNodes(hashes, loadCapacity);
            size[0] += SizeOf.sizeOfObjectArray(capacity) +
                    (long) map.size() * HASH_MAP_NODE_INSTANCE_SIZE +
                    treeNodes * (HASH_MAP_TREE_NODE_INSTANCE_SIZE - HASH_MAP_NODE_INSTANCE_SIZE);
        }
        map.forEach((key, value) -> size[0] += keySize.applyAsLong(key) + boxSize(value));
        return size[0];
    }

    private static int largestBin(int[] hashes, int capacity)
    {
        int[] bins = new int[capacity];
        int largest = 0;
        for (int hash : hashes) {
            largest = Math.max(largest, ++bins[hash & (capacity - 1)]);
        }
        return largest;
    }

    // The nodes in bins that could hold a tree: those of more nodes than a split leaves as a list.
    private static long treeBinNodes(int[] hashes, int capacity)
    {
        int[] bins = new int[capacity];
        for (int hash : hashes) {
            bins[hash & (capacity - 1)]++;
        }
        long nodes = 0;
        for (int bin : bins) {
            if (bin > UNTREEIFY_THRESHOLD) {
                nodes += bin;
            }
        }
        return nodes;
    }

    // A map whose one bin is a tree: nine keys of one hash in a table of 64 slots. "Aa" and "BB"
    // hash alike, so every string of four such pairs does too.
    private static Map<String, Integer> treeifiedMap()
    {
        Map<String, Integer> map = new HashMap<>(MIN_TREEIFY_CAPACITY);
        for (int key = 0; key <= TREEIFY_THRESHOLD; key++) {
            StringBuilder collidingKey = new StringBuilder();
            for (int pair = 0; pair < 4; pair++) {
                collidingKey.append((key & (1 << pair)) == 0 ? "Aa" : "BB");
            }
            map.put(collidingKey.toString(), key);
        }
        return map;
    }

    private static long mapViewsSize(Map<Integer, Integer> map)
    {
        return (long) SizeOf.instanceSize(map.keySet().getClass()) + SizeOf.instanceSize(map.values().getClass());
    }

    private static boolean compactStrings()
    {
        try {
            HotSpotDiagnosticMXBean diagnostics = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            return Boolean.parseBoolean(diagnostics.getVMOption("CompactStrings").getValue());
        }
        catch (RuntimeException | LinkageError ignored) {
            // A JVM without the option, or a module that does not read `jdk.management`,
            // counts two bytes per character, which is never low.
            return false;
        }
    }

    // The layouts of the JDK records that free a native allocation, which are internal: the
    // session's resource list, one reference, and the cleanup that frees the allocation, a
    // reference, three longs, and a flag.
    private record NativeResourceListLayout(Object first) {}

    private record NativeCleanupLayout(Object next, long allocationBase, long allocationSize, long byteSize, boolean shouldReserve) {}

    // The layout of the queued synchronizer's internal queue node: previous and next nodes, the
    // waiting thread, and a status word.
    private record LockQueueNodeLayout(Object previous, Object next, Object waiter, int status) {}
}
