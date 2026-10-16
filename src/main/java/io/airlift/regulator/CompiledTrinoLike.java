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
 * One Trino LIKE pattern compiled to its own hidden class. The method delegates to the public
 * static {@code matchesEntry} method of the class, which a caller can also bind directly. A
 * generated class is stateless.
 * <p>
 * The caller rejects a {@code null} input as {@link TrinoLikePattern#matches(Slice)} does.
 */
interface CompiledTrinoLike
{
    boolean matches(Slice input);
}
