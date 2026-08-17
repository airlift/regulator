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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestBenchmarkLiteralCorpus
{
    private static final Map<BenchmarkLiteralCorpus.Language, CorpusIdentity> CORPUS = Map.of(
            BenchmarkLiteralCorpus.Language.ENGLISH, new CorpusIdentity(899_232, "0d40805f6d02c8fe02bd75945b98911891f707e8ecb939e018446858065d76ea", 513, 522),
            BenchmarkLiteralCorpus.Language.RUSSIAN, new CorpusIdentity(1_570_556, "7ffddb21336a1bfb4a9e2df4bb77eea0305c0010a57c5d3c56e0dfead9e80a90", 724, 746),
            BenchmarkLiteralCorpus.Language.CHINESE, new CorpusIdentity(813_478, "f129e81928c58ecbba0ccbb63b36679355345248df057d1e9ded670d6e9c964b", 30, 30));

    @ParameterizedTest
    @EnumSource(BenchmarkLiteralCorpus.Language.class)
    void testCorpus(BenchmarkLiteralCorpus.Language language)
            throws Exception
    {
        Slice source = BenchmarkLiteralCorpus.loadSource(language);
        CorpusIdentity expected = CORPUS.get(language);

        assertThat(source.length()).isEqualTo(expected.length());
        assertThat(sha256(source)).isEqualTo(expected.sha256());

        BenchmarkLiteralCorpus.BenchmarkData data = new BenchmarkLiteralCorpus.BenchmarkData();
        data.language = language;
        data.setup();
        BenchmarkLiteralCorpus benchmark = new BenchmarkLiteralCorpus();
        assertThat(benchmark.count(data)).isEqualTo(expected.count());
        assertThat(benchmark.countCaseInsensitive(data)).isEqualTo(expected.caseInsensitiveCount());
    }

    private static String sha256(Slice source)
            throws NoSuchAlgorithmException
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes()));
    }

    private record CorpusIdentity(int length, String sha256, long count, long caseInsensitiveCount) {}
}
