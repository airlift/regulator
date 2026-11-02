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

import jdk.incubator.vector.ByteVector;

/// Reports whether the vector scanners can run. They need the optional `jdk.incubator.vector`
/// module and a preferred byte vector of 16 to 64 lanes. With fewer lanes, the lookup wraps the
/// 16-entry nibble tables; with more, the replicated tables hold only 64 bytes, and
/// `VectorMask.toLong()` throws for masks wider than 64 lanes. Other JVMs use the scalar or SWAR
/// scanners. This one switch conservatively turns off every vector route, including the fixed
/// 128-bit scans that would be correct at 8 lanes.
final class VectorSupport
{
    private static final boolean AVAILABLE = ModuleLayer.boot()
            .findModule("jdk.incubator.vector")
            .isPresent() && PreferredSpecies.hasSupportedLength();

    private VectorSupport() {}

    static boolean isAvailable()
    {
        return AVAILABLE;
    }

    // Loaded only when the module is present, so applications without it load no vector classes.
    private static final class PreferredSpecies
    {
        private PreferredSpecies() {}

        static boolean hasSupportedLength()
        {
            int length = ByteVector.SPECIES_PREFERRED.length();
            return length >= 16 && length <= 64;
        }
    }
}
