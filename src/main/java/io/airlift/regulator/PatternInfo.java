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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.Map;

import static java.util.Objects.requireNonNull;

/// Capture metadata for a validated pattern: the group count and names that the compiled pattern
/// reports.
public final class PatternInfo
{
    private final int capturingGroupCount;
    private final Map<String, Integer> namedCapturingGroups;
    private final Map<Integer, String> capturingGroupNames;

    PatternInfo(int capturingGroupCount, Map<Integer, String> capturingGroupNames)
    {
        if (capturingGroupCount < 0) {
            throw new IllegalArgumentException("capturingGroupCount is negative: " + capturingGroupCount);
        }
        this.capturingGroupCount = capturingGroupCount;
        this.capturingGroupNames = Map.copyOf(requireNonNull(capturingGroupNames, "capturingGroupNames is null"));
        this.namedCapturingGroups = ParseResult.namedCapturingGroups(this.capturingGroupNames);
    }

    /// Returns the number of capturing groups, excluding group zero (the whole match).
    public int capturingGroupCount()
    {
        return capturingGroupCount;
    }

    /// Returns an immutable mapping from capture names to group numbers. Named groups also
    /// count toward [#capturingGroupCount()].
    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "The returned map is immutable")
    public Map<String, Integer> namedCapturingGroups()
    {
        return namedCapturingGroups;
    }

    /// Returns an immutable mapping from group numbers to capture names.
    public Map<Integer, String> capturingGroupNames()
    {
        return capturingGroupNames;
    }
}
