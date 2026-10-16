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

import static java.util.Objects.requireNonNull;

/**
 * @param caseFolding the case folding of the pattern language, which every later stage of
 *         compilation applies
 */
record ParseResult(Regexp regexp, int capturingGroupCount, CaseFolding caseFolding)
{
    ParseResult
    {
        requireNonNull(regexp, "regexp is null");
        requireNonNull(caseFolding, "caseFolding is null");
        if (capturingGroupCount < 0) {
            throw new IllegalArgumentException("capturingGroupCount is negative: " + capturingGroupCount);
        }
    }
}
