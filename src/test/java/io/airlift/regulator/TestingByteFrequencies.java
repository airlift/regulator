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

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;

final class TestingByteFrequencies
{
    private TestingByteFrequencies() {}

    /// Returns a supplied distribution in which every byte but ASCII letters and space is rare, so
    /// probes move onto punctuation, digits, and bytes at or above 0x80.
    public static ByteFrequencies rareNonLetters()
    {
        long[] counts = new long[256];
        for (byte value : "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ ".getBytes(UTF_8)) {
            counts[value] = 1_000_000;
        }
        return ByteFrequencies.fromCounts(counts);
    }

    /// Returns a distribution learned from two JSON request records. They contain no `v`,
    /// `k`, or `=`, one `b`, and neither Latin-1 `ï` (0xEF) nor the
    /// trailing UTF-8 byte of `ï` (0xAF), but `a`, `s`, and the UTF-8 lead byte
    /// 0xC3 that `ï` shares with `é` are common, so literals such as
    /// `svc1://`, `user=`, and `aab` move their probes while `https://`
    /// and `error: ` keep the first byte.
    public static ByteFrequencies learnedJsonRecords()
    {
        String records =
                """
                {"id":7310,"time":"2026-10-13T09:15:02Z","method":"GET","path":"/api/orders/7310","status":200,"type":"application/json","user":"amélie@example.com","note":"Merci, ça marche très bien à Montréal."}
                {"id":7311,"time":"2026-10-13T09:15:07Z","method":"POST","path":"/api/orders/7311/items","status":404,"type":"text/plain","user":"zoë@example.org","note":"Café à l'été prochain?"}
                """;
        return ByteFrequencies.builder().add(utf8Slice(records)).build();
    }
}
