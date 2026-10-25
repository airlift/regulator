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

import java.util.HashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/// @param caseFolding the case folding of the pattern language, which every later stage of
///         compilation applies
record ParseResult(Regexp regexp, int capturingGroupCount, Map<Integer, String> capturingGroupNames, CaseFolding caseFolding)
{
    ParseResult
    {
        requireNonNull(regexp, "regexp is null");
        capturingGroupNames = Map.copyOf(requireNonNull(capturingGroupNames, "capturingGroupNames is null"));
        requireNonNull(caseFolding, "caseFolding is null");
        if (capturingGroupCount < 0) {
            throw new IllegalArgumentException("capturingGroupCount is negative: " + capturingGroupCount);
        }
    }

    /// Inverts group-to-name mappings into an immutable mapping from capture names to group
    /// numbers.
    static Map<String, Integer> namedCapturingGroups(Map<Integer, String> capturingGroupNames)
    {
        if (capturingGroupNames.isEmpty()) {
            return Map.of();
        }
        // A name used by several groups refers to the first of them.
        HashMap<String, Integer> firstGroups = new HashMap<>();
        capturingGroupNames.forEach((group, name) -> firstGroups.merge(name, group, Math::min));
        return Map.copyOf(firstGroups);
    }
}
