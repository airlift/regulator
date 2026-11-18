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

import io.airlift.regulator.GoldenJsonl.JsonObject;
import io.airlift.regulator.UapCoreParser.Section;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class TestBenchmarkUapCore
{
    // The uncompressed resources, as tools/re2-benchmark/manifests/uap-core-corpus.tsv pins them.
    private static final Map<String, ResourceIdentity> RESOURCES = Map.of(
            "user-agent-parsers.jsonl.gzip", new ResourceIdentity(39_078, "f54d9494c49ec07c69aa12cbe8902b7d3fe83361eab17ae8e31007bc488446b5"),
            "os-parsers.jsonl.gzip", new ResourceIdentity(20_800, "4e6af6a9a6d1ea67ef9ab4ce06371322447df922b185d47ef05ed9d6dce108b7"),
            "test-ua.jsonl.gzip", new ResourceIdentity(288_501, "4cc06a46c91bcffb7f0e3fae68ccbe3bb0713369669ee1d45dad7f88b6c44de6"),
            "test-os.jsonl.gzip", new ResourceIdentity(94_378, "a113b57ecbf7235c7a663c7e8f4a18354efe120c581e88e149e90fdbc0bbbb73"));

    private static final Map<Section, SectionIdentity> SECTIONS = Map.of(
            Section.USER_AGENT, new SectionIdentity(433, 945),
            Section.OS, new SectionIdentity(204, 371));

    // The SHA-256 of the atom-fragment texts, concatenated in corpus order.
    private static final String ATOM_FRAGMENTS_SHA256 = "d914aca2d5d90e1519783bc61b1f85466b8cfd4982db8a6453ea00f273911485";

    private static final Map<BenchmarkUapCore.Corpus, Checksums> CHECKSUMS = Map.of(
            BenchmarkUapCore.Corpus.UA_TESTS, new Checksums(1_601, -6968283299897589861L, 5186094925483847261L, -5850186793899768871L, -870790577165327940L, -1049401749285213575L),
            BenchmarkUapCore.Corpus.OS_TESTS, new Checksums(483, 8163189598332632071L, -9152656012121220208L, -5351077126828258728L, 2621427302878393309L, -6311847022472504512L),
            BenchmarkUapCore.Corpus.CRAWLERS, new Checksums(228, 6468400012786710832L, -4280382292606217154L, -1094967374711561120L, -6911502379307713138L, 9079774301146019508L),
            BenchmarkUapCore.Corpus.ATOM_FRAGMENTS, new Checksums(20_000, -8717577284317705342L, -920439851015517370L, 2220132525529482269L, -690220669261557312L, -1192170275941812507L));

    @Test
    void testResources()
            throws Exception
    {
        for (Map.Entry<String, ResourceIdentity> entry : RESOURCES.entrySet()) {
            byte[] resource = readResource(entry.getKey());
            assertThat(resource.length).as(entry.getKey()).isEqualTo(entry.getValue().length());
            assertThat(sha256(resource)).as(entry.getKey()).isEqualTo(entry.getValue().sha256());
        }
    }

    @ParameterizedTest
    @EnumSource(Section.class)
    void testSpecification(Section section)
    {
        SectionIdentity expected = SECTIONS.get(section);
        UapCoreParser parser = new UapCoreParser(section, 32 << 20, 32 << 20);
        assertThat(parser.ruleCount()).isEqualTo(expected.ruleCount());
        assertThat(parser.atomCount()).isEqualTo(expected.atomCount());

        for (JsonObject testCase : section.testCases()) {
            String userAgent = testCase.getString("user_agent_string");
            List<String> expectedFields = new ArrayList<>();
            for (String field : section.resultFields()) {
                expectedFields.add(testCase.getString(field));
            }
            assertThat(parser.parse(Slices.utf8Slice(userAgent)).fields()).as(userAgent).isEqualTo(expectedFields);
        }
    }

    @Test
    void testAtomFragments()
            throws Exception
    {
        ByteArrayOutputStream corpus = new ByteArrayOutputStream();
        for (Slice text : BenchmarkUapCore.atomFragments()) {
            assertThat(text.length()).isEqualTo(UapCoreParser.MAX_USER_AGENT_BYTES);
            corpus.write(text.getBytes());
        }
        assertThat(corpus.size()).isEqualTo(20_000 * UapCoreParser.MAX_USER_AGENT_BYTES);
        assertThat(sha256(corpus.toByteArray())).isEqualTo(ATOM_FRAGMENTS_SHA256);
    }

    @ParameterizedTest
    @EnumSource(BenchmarkUapCore.Corpus.class)
    void testChecksums(BenchmarkUapCore.Corpus corpus)
    {
        Checksums expected = CHECKSUMS.get(corpus);
        assertThat(BenchmarkUapCore.loadUserAgents(corpus, BenchmarkUapCore.compileCrawlerRule(32))).hasSize(expected.userAgentCount());

        BenchmarkUapCore benchmark = new BenchmarkUapCore();
        BenchmarkUapCore.BenchmarkData data = new BenchmarkUapCore.BenchmarkData();
        data.corpus = corpus;
        data.ruleMemoryMiB = 32;
        data.atomSetMemoryMiB = 32;
        data.setup();
        assertThat(benchmark.rule51FindResult(data)).isEqualTo(expected.rule51FindResult());
        assertThat(benchmark.browserAtomSetMatchingPatternIds(data)).isEqualTo(expected.browserAtomSetMatchingPatternIds());
        assertThat(benchmark.osAtomSetMatchingPatternIds(data)).isEqualTo(expected.osAtomSetMatchingPatternIds());
        assertThat(benchmark.parseBrowser(data)).isEqualTo(expected.parseBrowser());
        assertThat(benchmark.parseOs(data)).isEqualTo(expected.parseOs());
        assertThat(benchmark.parseBrowserOneCall(data)).isEqualTo(expected.parseBrowser());
        assertThat(benchmark.parseOsOneCall(data)).isEqualTo(expected.parseOs());

        BenchmarkUapCore.SharedParsers parsers = new BenchmarkUapCore.SharedParsers();
        parsers.corpus = corpus;
        parsers.ruleMemoryMiB = 32;
        parsers.atomSetMemoryMiB = 32;
        parsers.setup();
        assertThat(benchmark.parseBrowserShared(parsers)).isEqualTo(expected.parseBrowser());
        assertThat(benchmark.parseOsShared(parsers)).isEqualTo(expected.parseOs());
        assertThat(benchmark.parseBrowserSharedOneCall(parsers)).isEqualTo(expected.parseBrowser());
        assertThat(benchmark.parseOsSharedOneCall(parsers)).isEqualTo(expected.parseOs());
    }

    private static byte[] readResource(String resourceName)
            throws IOException
    {
        String resource = "/io/airlift/regulator/uap-core/" + resourceName;
        try (InputStream input = requireNonNull(TestBenchmarkUapCore.class.getResourceAsStream(resource), resource);
                GZIPInputStream gzipInput = new GZIPInputStream(input)) {
            return gzipInput.readAllBytes();
        }
    }

    private static String sha256(byte[] data)
            throws NoSuchAlgorithmException
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private record ResourceIdentity(int length, String sha256) {}

    private record SectionIdentity(int ruleCount, int atomCount) {}

    private record Checksums(
            int userAgentCount,
            long rule51FindResult,
            long browserAtomSetMatchingPatternIds,
            long osAtomSetMatchingPatternIds,
            long parseBrowser,
            long parseOs) {}
}
