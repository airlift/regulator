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
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Vector scans for {@link TrinoScanPlan} and {@link TrinoScanPlanRun}. Callers must check
 * {@link VectorSupport#isAvailable()} first and use their scalar loops otherwise.
 */
final class VectorTrinoScanner
{
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_128;

    private VectorTrinoScanner() {}

    // PERFORMANCE-SENSITIVE HOT LOOPS: changes here require target-host assembly
    // and benchmark evidence. Keep Vector API linkage isolated in this class.

    /**
     * Returns the first byte at or after {@code cursor} outside {@code members}, or {@code end}
     * when none remains. {@code needles} holds one to four bytes: the members, or the
     * non-members when {@code inverted}.
     */
    static int findRunStop(byte[] input, int cursor, int end, byte[] needles, boolean inverted, boolean[] members)
    {
        ByteVector first = ByteVector.broadcast(SPECIES, needles[0]);
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            ByteVector bytes = ByteVector.fromArray(SPECIES, input, cursor);
            VectorMask<Byte> matches = bytes.compare(VectorOperators.EQ, first);
            for (int index = 1; index < needles.length; index++) {
                matches = matches.or(bytes.compare(VectorOperators.EQ, needles[index]));
            }
            VectorMask<Byte> stops = inverted ? matches : matches.not();
            if (stops.anyTrue()) {
                return cursor + stops.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            if (!members[input[cursor] & 0xFF]) {
                return cursor;
            }
        }
        return end;
    }

    static int findByte(byte[] bytes, int cursor, int end, byte target)
    {
        ByteVector wanted = ByteVector.broadcast(SPECIES, target);
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, bytes, cursor).compare(VectorOperators.EQ, wanted);
            if (matches.anyTrue()) {
                return cursor + matches.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            if (bytes[cursor] == target) {
                return cursor;
            }
        }
        return -1;
    }

    /**
     * Returns the first byte at or after {@code cursor} that equals {@code folded}, a lowercase
     * ASCII letter, after setting bit {@code 0x20}, or {@code -1} when none remains.
     */
    static int findAsciiFoldedByte(byte[] bytes, int cursor, int end, byte folded)
    {
        ByteVector mask = ByteVector.broadcast(SPECIES, (byte) 0x20);
        ByteVector wanted = ByteVector.broadcast(SPECIES, folded);
        for (; cursor <= end - SPECIES.length(); cursor += SPECIES.length()) {
            VectorMask<Byte> matches = ByteVector.fromArray(SPECIES, bytes, cursor).or(mask).compare(VectorOperators.EQ, wanted);
            if (matches.anyTrue()) {
                return cursor + matches.firstTrue();
            }
        }
        for (; cursor < end; cursor++) {
            if ((bytes[cursor] | 0x20) == folded) {
                return cursor;
            }
        }
        return -1;
    }
}
