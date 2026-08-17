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

/**
 * Thrown when matching exceeds the configured regular-expression memory budget.
 */
public final class RegexpMatchMemoryLimitException
        extends RuntimeException
{
    private final long maxMemoryBytes;

    RegexpMatchMemoryLimitException(long maxMemoryBytes)
    {
        super("regular expression matching exceeded the " + maxMemoryBytes + " byte memory limit");
        this.maxMemoryBytes = maxMemoryBytes;
    }

    public long maxMemoryBytes()
    {
        return maxMemoryBytes;
    }
}
