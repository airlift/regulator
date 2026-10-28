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

/// Regular-expression and SQL LIKE matching over [io.airlift.slice.Slice] inputs and outputs.
/// [Re2], [TrinoRegexp], [JavaRegexp], and [RustRegexp] compile their languages' patterns into
/// programs for a shared, byte-oriented Java port of RE2, and [TrinoLikePattern] compiles Trino
/// SQL LIKE patterns for its own wildcard matcher.
/// Compiled patterns are thread-safe; matchers are mutable and must not be shared between threads.
package io.airlift.regulator;
