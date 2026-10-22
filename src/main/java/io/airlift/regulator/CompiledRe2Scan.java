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

/**
 * The RE2 executor class of one RE2 pattern, a {@link Re2} compiled in the RE2 dialect, whose
 * case-sensitive scan plan was compiled to its own hidden class. Each method returns what the
 * {@link TrinoScanPlan} method it names returns, with the same groups. A start-anchored plan
 * implements only {@code match}, and an unanchored plan only {@code search} and, when full-Slice
 * find hands off to the ordinary engine, {@code searchBeforeHandoff}; the others throw
 * {@link UnsupportedOperationException}. A generated class is stateless and never calls back into
 * the pattern; the pattern itself finishes a search that handed off.
 */
interface CompiledRe2Scan
{
    /**
     * The attempt of a start-anchored plan, as {@link TrinoScanPlan#matchEndAnchored} or
     * {@link TrinoScanPlan#matchPartial}.
     */
    boolean match(Slice input, int contextStart, int contextEnd, boolean fullMatch, int[] groups);

    /**
     * The search of an unanchored plan, as {@link TrinoScanPlan#search}.
     */
    int search(Slice input, int contextStart, int contextEnd, int start, boolean anchored, boolean fullMatch, int[] groups);

    /**
     * The capture-free search of all of {@code input} that full-Slice find runs, as
     * {@link TrinoScanPlan#searchBeforeHandoff}.
     */
    int searchBeforeHandoff(Slice input);
}
