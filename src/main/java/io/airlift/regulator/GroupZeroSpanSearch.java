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

/// A pattern's search for its group-zero spans, which [RegexpMatcher#find()] runs in place of the
/// program when the matcher retains no capturing groups: an exact literal, a literal alternation,
/// a literal set, a `.*literal.*` pattern, or a fixed-width pattern. Each reports the leftmost-first
/// nonempty span lying entirely in a region `[start, end)` of its input, as the general engine
/// reports it for that region. An exact literal's pattern is its own search,
/// [CompiledPattern#findExactLiteralSpan(io.airlift.slice.Slice,int,int)], so it keeps no extra
/// search object. Its short-row substring search may read past the region but reports no span
/// that ends there; the other span searches read only the region.
///
/// The interface declares no search method. Each implementation has its own region search, as the
/// pattern itself for an exact literal, which [RegexpMatcher] tests by identity, as a final class,
/// as a sealed class whose one subclass overrides no method for [LiteralSetSpanMatcher], as an
/// abstract sealed class for [LiteralAlternationSpanMatcher], whose final search tests the receiver
/// against its two final subclasses and calls that class's search. [RegexpMatcher] tests the
/// receiver against each class and calls that search directly, so each call site binds statically
/// and can inline. One interface call site would see every class in a process whose patterns use
/// them all, as a Trino server's do, and C2 calls a megamorphic site through the interface table
/// without inlining.
sealed interface GroupZeroSpanSearch
        permits CompiledPattern,
                DotStarLiteralSpanMatcher,
                FixedWidthByteSpanMatcher,
                LiteralAlternationSpanMatcher,
                LiteralSetSpanMatcher {}
