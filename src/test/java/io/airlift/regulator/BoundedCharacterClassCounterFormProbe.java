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

import java.lang.reflect.Field;

public final class BoundedCharacterClassCounterFormProbe
{
    private BoundedCharacterClassCounterFormProbe() {}

    /// Checks that `countUtf8` runs the form the argument names, reading LOW_BITS from the field
    /// when it is `true` and the constant when it is `false`, and that Cyrillic and ASCII text
    /// counts as the general matcher does in that form.
    public static void main(String[] arguments)
            throws Exception
    {
        boolean expectedAarch64 = Boolean.parseBoolean(arguments[0]);
        Field aarch64 = BoundedCharacterClassCounter.class.getDeclaredField("AARCH64");
        aarch64.setAccessible(true);
        if (aarch64.getBoolean(null) != expectedAarch64) {
            throw new AssertionError("os.arch %s did not select the %s form".formatted(System.getProperty("os.arch"), expectedAarch64 ? "field" : "constant"));
        }

        TestBoundedCharacterClassCounter.assertTwoByteTextMatchesGeneralMatcherAtEveryPosition();
        System.out.println(expectedAarch64 ? "OK field" : "OK constant");
    }
}
