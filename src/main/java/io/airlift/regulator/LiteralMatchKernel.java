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

final class LiteralMatchKernel
{
    private LiteralMatchKernel() {}

    static boolean equals(Slice input, Slice literal)
    {
        return input.length() == literal.length() && matchesAt(input, 0, literal);
    }

    static boolean startsWith(Slice input, Slice literal)
    {
        return input.length() >= literal.length() && matchesAt(input, 0, literal);
    }

    static boolean endsWith(Slice input, Slice literal)
    {
        return input.length() >= literal.length() && matchesAt(input, input.length() - literal.length(), literal);
    }

    static boolean contains(Slice input, Slice literal)
    {
        return input.indexOf(literal) >= 0;
    }

    static int find(Slice input, Slice literal, int start)
    {
        return input.indexOf(literal, start);
    }

    static boolean matchesAt(Slice input, int offset, Slice literal)
    {
        int literalLength = literal.length();
        return input.equals(offset, literalLength, literal, 0, literalLength);
    }
}
