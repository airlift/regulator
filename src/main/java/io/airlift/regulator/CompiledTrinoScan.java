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

import java.util.List;
import java.util.function.Function;

/**
 * The Trino operations of one pattern whose scan plan was compiled to its own hidden class. Each
 * method delegates to the public static method of the class named for it with an {@code Entry}
 * suffix, such as {@code containsEntry}, which a caller can also bind directly. A generated class
 * is stateless and implements only the operations whose route reaches the plan; the others throw
 * {@link UnsupportedOperationException}.
 * <p>
 * The caller validates arguments as the matching {@link TrinoRegexp} operation does; only
 * {@code position} checks its start and occurrence itself.
 */
interface CompiledTrinoScan
{
    /**
     * Finds the first match that starts at or after byte offset {@code start}, writing its group
     * offsets to {@code groups}, as a matcher retaining {@code groups.length / 2 - 1} groups does.
     */
    boolean find(Slice input, int start, int[] groups);

    boolean contains(Slice input);

    long count(Slice input);

    long position(Slice input, long start, long occurrence);

    Slice extract(Slice input, int group);

    List<Slice> extractAll(Slice input, int group);

    List<Slice> split(Slice input);

    Slice replace(TrinoRegexp owner, Slice input, Slice replacement);

    Slice replace(Slice input, Function<List<Slice>, Slice> replacement);
}
