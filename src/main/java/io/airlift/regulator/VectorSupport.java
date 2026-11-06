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

import com.sun.management.HotSpotDiagnosticMXBean;
import jdk.incubator.vector.ByteVector;

import java.lang.management.ManagementFactory;

/// Reports whether the vector scanners can run, and which candidate-mask form their multi-byte
/// scans take ([#SVE_128]). They need the optional `jdk.incubator.vector`
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

    // Whether C2 compiles this JVM's vector code with SVE and a 16-byte preferred byte vector, as
    // on r9g. There, the scans "Candidate Masks on SVE" in REGULATOR_DECISIONS.md names XOR each
    // byte with each probe, join the differences with OR or an unsigned minimum, and compare once
    // with zero, because SVE compares and the AND or OR that joins their masks are predicate
    // operations. NEON, x86, and other SVE widths compare each byte and join the masks. That
    // section records why and the measurements; RE2 run stops take their XOR form on every
    // platform. C2 folds this constant, so each scan compiles one form.
    static final boolean SVE_128 = sve128();

    private VectorSupport() {}

    static boolean isAvailable()
    {
        return AVAILABLE;
    }

    private static boolean sve128()
    {
        if (!AVAILABLE || !"aarch64".equals(System.getProperty("os.arch")) || !PreferredSpecies.hasSixteenLanes()) {
            return false;
        }
        try {
            return UseSveFlag.isSet();
        }
        catch (RuntimeException | LinkageError ignored) {
            // A management failure, such as the IllegalAccessError in a shaded named module that
            // does not read `jdk.management`, falls back to the compare forms rather than failing
            // this class's initialization.
            return false;
        }
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

        static boolean hasSixteenLanes()
        {
            return ByteVector.SPECIES_PREFERRED.length() == 16;
        }
    }

    // Loaded only on aarch64 with 16-lane vectors, so other JVMs never look up the flag.
    private static final class UseSveFlag
    {
        private UseSveFlag() {}

        static boolean isSet()
        {
            HotSpotDiagnosticMXBean diagnostics = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            return Integer.parseInt(diagnostics.getVMOption("UseSVE").getValue()) > 0;
        }
    }
}
