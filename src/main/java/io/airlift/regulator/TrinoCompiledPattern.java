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

/// The matching engine of a Trino pattern. A [TrinoRegexp] holds one when it needs one, rather
/// than extending it: its Trino operations, its generated class, and every specialized subclass
/// but the literal-alternation and literal-set ones run on the engine, whose own operations are
/// the shared engine's rather than Trino's.
final class TrinoCompiledPattern
        extends CompiledPattern
{
    TrinoCompiledPattern(Analysis analysis)
    {
        super(analysis);
    }

    /// Returns `false`. A Trino pattern's generated class belongs to its [TrinoRegexp]; see
    /// [TrinoRegexp#usesGeneratedBytecode()].
    @Override
    public boolean usesGeneratedBytecode()
    {
        return false;
    }
}
