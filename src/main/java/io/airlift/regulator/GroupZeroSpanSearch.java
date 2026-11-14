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

/// A capture-free pattern's search for its group-zero spans, which a group-zero
/// [RegexpMatcher#find()] runs in place of the program: a literal alternation, a literal set, a
/// `.*literal.*` pattern, or a fixed-width pattern. Each finds the leftmost-first span lying
/// entirely in a region `[start, end)` of its input, as the general engine reports it for that
/// region, reads nothing outside the region, and never matches empty.
///
/// The interface declares no search method. Each implementation has its own region search, as a
/// final class, as a sealed class whose one subclass overrides no method for
/// [LiteralSetSpanMatcher], as an abstract sealed class for [LiteralAlternationSpanMatcher],
/// whose final search tests the receiver against its two final subclasses and calls that class's
/// search, or as a sealed class for [DotStarLiteralSpanMatcher], whose one subclass overrides its
/// search for dots with stops besides a line feed. [RegexpMatcher] tests the receiver against
/// each class and calls that search directly, so each call site binds statically and can inline,
/// or, for the dot-star literal matcher, sees at most its two classes, which C2 can inline by
/// testing for each, and binds statically while the subclass is not loaded. One interface call
/// site would see every class in a process whose patterns use them all, as a Trino server's do,
/// and C2 calls a megamorphic site through the interface table without inlining.
sealed interface GroupZeroSpanSearch
        permits DotStarLiteralSpanMatcher,
                FixedWidthByteSpanMatcher,
                LiteralAlternationSpanMatcher,
                LiteralSetSpanMatcher {}
