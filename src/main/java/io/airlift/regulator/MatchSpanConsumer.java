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

/// Receives each match of a `forEachMatch` operation of [Re2], [JavaRegexp], [RustRegexp], or
/// [TrinoRegexp], as byte offsets relative to the logical input Slice; the end offset is
/// exclusive.
@FunctionalInterface
public interface MatchSpanConsumer
{
    /// Accepts the match `[start, end)`, and returns whether the operation continues to the next
    /// match; `false` ends it after this one.
    boolean accept(int start, int end);
}
