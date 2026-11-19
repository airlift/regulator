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

import io.airlift.slice.SizeOf;

final class SparseSet
{
    private int size;
    private int[] sparse;
    private int[] dense;

    public SparseSet()
    {
        this(0);
    }

    public SparseSet(int maxSize)
    {
        this(maxSize, 0);
    }

    /// Creates a set whose dense array holds `tailSize` scratch ints past `maxSize`, which
    /// [#scratchTail()] lends out.
    SparseSet(int maxSize, int tailSize)
    {
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize must be >= 0: " + maxSize);
        }
        if (tailSize < 0) {
            throw new IllegalArgumentException("tailSize must be >= 0: " + tailSize);
        }
        sparse = new int[maxSize];
        dense = new int[maxSize + tailSize];
    }

    public int size()
    {
        return size;
    }

    long estimatedRetainedSize()
    {
        return RetainedSizes.INSTANCE_SIZE + SizeOf.sizeOf(sparse) + SizeOf.sizeOf(dense);
    }

    public boolean isEmpty()
    {
        return size == 0;
    }

    public int maxSize()
    {
        return sparse.length;
    }

    /// Clears the set in O(1).
    public void clear()
    {
        size = 0;
    }

    public void resize(int newMaxSize)
    {
        if (newMaxSize < 0) {
            throw new IllegalArgumentException("newMaxSize must be >= 0: " + newMaxSize);
        }

        if (newMaxSize > maxSize()) {
            int oldMaxSize = maxSize();
            int tailSize = dense.length - oldMaxSize;
            int[] newSparse = new int[newMaxSize];
            int[] newDense = new int[newMaxSize + tailSize];
            System.arraycopy(sparse, 0, newSparse, 0, oldMaxSize);
            System.arraycopy(dense, 0, newDense, 0, oldMaxSize);
            sparse = newSparse;
            dense = newDense;
        }

        size = Math.min(size, newMaxSize);
    }

    /// Clears the set and returns its dense array, whose contents are unspecified, for use as
    /// scratch until the set is next used. `Prog.markLoopRoots` borrows it from the flattening
    /// pass's reachable set for its search indexes.
    int[] clearAndBorrowDense()
    {
        size = 0;
        return dense;
    }

    /// Returns the dense array, whose ints from [#maxSize()] on are the scratch tail the set was
    /// built with, free for the caller while the set holds at most `maxSize` elements in front of
    /// it. The many-match scan buffer lives in the match set's dense tail, which saves a search
    /// one allocation, so `maxSize` is `sparse.length`, no longer `dense.length`.
    int[] scratchTail()
    {
        return dense;
    }

    public boolean contains(int indexValue)
    {
        if (indexValue < 0 || indexValue >= maxSize()) {
            return false;
        }

        int densePosition = sparse[indexValue];
        return densePosition >= 0 && densePosition < size && dense[densePosition] == indexValue;
    }

    public void insert(int indexValue)
    {
        checkIndex(indexValue);
        if (!contains(indexValue)) {
            createIndex(indexValue);
        }
    }

    /// Like [#insert(int)], but only use this if [#contains(int)] is known to be false.
    public void insertNew(int indexValue)
    {
        checkIndex(indexValue);
        if (contains(indexValue)) {
            throw new IllegalArgumentException("already present: " + indexValue);
        }
        createIndex(indexValue);
    }

    /// Dense list element at the given index, suitable for iteration like:
    ///
    /// ```java
    /// for (int it = 0; it < set.size(); it++) {
    ///     int value = set.denseAt(it);
    ///     ...
    ///     set.insert(...); // safe during iteration
    /// }
    /// ```
    public int denseAt(int index)
    {
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException("index out of range: " + index);
        }
        return dense[index];
    }

    private void createIndex(int indexValue)
    {
        if (size >= maxSize()) {
            throw new IllegalStateException("set is full (maxSize=" + maxSize() + ")");
        }
        sparse[indexValue] = size;
        dense[size] = indexValue;
        size++;
    }

    private void checkIndex(int indexValue)
    {
        if (indexValue < 0 || indexValue >= maxSize()) {
            throw new IllegalArgumentException("illegal index: " + indexValue);
        }
    }

    // Loaded only when a size is estimated: parsing never sizes objects, and TeaVM cannot.
    private static final class RetainedSizes
    {
        private static final int INSTANCE_SIZE = SizeOf.instanceSize(SparseSet.class);

        private RetainedSizes() {}
    }
}
