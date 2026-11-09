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

/// Reports whether the vector scanners can run, which candidate-mask form their multi-byte
/// scans take ([#SVE_128] and [#X86_WITH_64_BYTE_VECTORS]), and how the start literal filter's
/// nibble scan shifts ([#SHIFT_NIBBLES_AS_SHORTS]). They need the optional `jdk.incubator.vector`
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

    // Whether this JVM runs on x86. C2 folds this constant.
    private static final boolean X86 = isX86(System.getProperty("os.arch"));

    // Whether this JVM runs on x86 with a 64-byte preferred byte vector, the gate of the scans'
    // x86 64-lane forms: the literal prefix scan's masked pair compare. They were measured with
    // AVX-512 on r8i (Granite Rapids), the only x86 host measured in them. Hosts nobody measured
    // keep the form other widths take, so the gate tests for x86: 512-bit SVE, as on A64FX, and
    // other architectures with 64-byte vectors keep compare and AND. x86 hosts with 64-byte
    // vectors other than r8i, such as Ice Lake, AMD Zen 4 and 5, and Cascade Lake, take the
    // forms unmeasured. C2 folds this constant.
    static final boolean X86_WITH_64_BYTE_VECTORS = AVAILABLE && X86 && PreferredSpecies.length() == 64;

    // Whether C2 compiles this JVM's vector code with SVE and a 16-byte preferred byte vector, as
    // on r9g. There, the scans "Candidate Masks on SVE" in REGULATOR_DECISIONS.md names XOR each
    // byte with each probe, join the differences with OR or an unsigned minimum, and compare once
    // with zero, because SVE compares and the AND or OR that joins their masks are predicate
    // operations. NEON, x86, and other SVE widths compare each byte and join the masks, except
    // that under X86_WITH_64_BYTE_VECTORS the literal prefix scan of a single literal compares
    // its second byte under the first byte's mask. That section records why and the
    // measurements; RE2 run stops take their XOR form on every platform. C2 folds this constant,
    // so each scan compiles one form.
    static final boolean SVE_128 = sve128();

    // Whether the start literal filter's nibble scan takes each byte's high nibble with a 16-bit
    // lane shift and an AND of 0x0F, rather than a byte lane shift. Only on x86, which has no
    // byte lane shift: C2 widens, shifts, and narrows a byte shift, six more uops per shift on
    // r8i, where rows the scan serves took 0.58x to 0.90x the byte shift's time in the 16-bit
    // form. aarch64 shifts bytes in one instruction, so there the extra AND only costs: 1.09x to
    // 1.16x on r9g. Other platforms were not measured and keep the byte shift. "Start Literal
    // Filter" in REGULATOR_DECISIONS.md records the measurements. C2 folds this constant, so the
    // scan compiles one form.
    static final boolean SHIFT_NIBBLES_AS_SHORTS = isX86(System.getProperty("os.arch"));

    private VectorSupport() {}

    static boolean isAvailable()
    {
        return AVAILABLE;
    }

    /// Returns the number of lanes in the preferred byte vector. Call it only when
    /// [#isAvailable()]: it loads the Vector API.
    static int preferredByteLanes()
    {
        return PreferredSpecies.length();
    }

    private static boolean isX86(String arch)
    {
        return "amd64".equals(arch) || "x86_64".equals(arch);
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

        static int length()
        {
            return ByteVector.SPECIES_PREFERRED.length();
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
