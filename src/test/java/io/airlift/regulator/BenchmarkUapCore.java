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

import io.airlift.regulator.TestingUapCoreParser.Parse;
import io.airlift.regulator.TestingUapCoreParser.Section;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;

/// The public uap-core user-agent rules run the way an application runs them on Regulator, with
/// [TestingUapCoreParser]: each section's rules in one [FilteredRe2], its atoms in one [Re2Set], and
/// the candidate rules tried in order until one matches. The browser section holds 433 rules and the
/// OS section 204. The benchmark is diagnostic: no row joins the published report
/// (`BenchmarkUapCore` is not in `tools/re2-benchmark/baseline/rows.tsv`).
///
/// Every row makes one pass over the selected corpus, each text cut to
/// [TestingUapCoreParser#MAX_USER_AGENT_BYTES] bytes, a bound that models a calling application's
/// input limit, not uap-core's specification. Each row returns a checksum of the results, folding
/// each value as `checksum * 31 + value` from `0`:
///
/// - `rule51FindResult`: browser rule 51 (zero-based), the crawler alternation, run by itself as an
///   [Re2]; folds each match's start and end and group 1's start and end, or `-1` for no match
/// - `browserAtomSetMatchingPatternIds` and `osAtomSetMatchingPatternIds`: the section's atom set
///   over each text's canonicalized form, prepared at setup; folds the sum of the IDs of the atoms
///   found, which the set returns in no particular order, then their number
/// - `parseBrowser` and `parseOs`: the full parse of each text; folds the winning rule ID, or `-1`,
///   and the hash of each result field, `0` when absent
/// - `parseBrowserShared` and `parseOsShared`: the same parses with one compiled section that every
///   JMH thread shares; run them with `-t 16`, so that many threads contend on each shared section's
///   lazily built DFA caches. The other rows compile the sections for each thread, so with the same
///   `-t`, `parseBrowser` and `parseOs` are their per-thread controls.
///
/// Every thread reads one corpus, loaded and canonicalized once. Absolute numbers measured before
/// the corpus was shared and the atom-set rows stopped sorting their IDs aren't comparable with
/// later ones; compare legs within one round.
///
/// `ruleMemoryMiB` is every rule's memory budget, and `atomSetMemoryMiB` the atom set's.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 7, time = 1)
public class BenchmarkUapCore
{
    private static final int CRAWLER_RULE_ID = 51;

    public enum Corpus
    {
        /// The user agents of uap-core's browser tests.
        UA_TESTS,
        /// The user agents of uap-core's OS tests.
        OS_TESTS,
        /// The browser-test user agents in which browser rule 51 (zero-based), run by itself, finds a
        /// match: the crawlers.
        CRAWLERS,
    }

    /// One thread's compiled crawler rule and sections, over the corpus [SharedParsers] holds.
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        private Re2 crawlerRule;
        private TestingUapCoreParser browserParser;
        private TestingUapCoreParser osParser;
        private Slice[] userAgents;
        private Slice[] canonicalTexts;

        @Setup
        public void setup(SharedParsers shared)
        {
            crawlerRule = compileCrawlerRule(shared.ruleMemoryMiB);
            browserParser = new TestingUapCoreParser(Section.USER_AGENT, mebibytes(shared.ruleMemoryMiB), mebibytes(shared.atomSetMemoryMiB));
            osParser = new TestingUapCoreParser(Section.OS, mebibytes(shared.ruleMemoryMiB), mebibytes(shared.atomSetMemoryMiB));
            userAgents = shared.userAgents;
            canonicalTexts = shared.canonicalTexts;
        }
    }

    /// The compiled sections, the corpus, and its canonicalized texts, shared by every JMH thread.
    @State(Scope.Benchmark)
    public static class SharedParsers
    {
        @Param
        Corpus corpus;

        @Param("32")
        int ruleMemoryMiB;

        @Param("32")
        int atomSetMemoryMiB;

        private TestingUapCoreParser browserParser;
        private TestingUapCoreParser osParser;
        private Slice[] userAgents;
        private Slice[] canonicalTexts;

        @Setup
        public void setup()
        {
            browserParser = new TestingUapCoreParser(Section.USER_AGENT, mebibytes(ruleMemoryMiB), mebibytes(atomSetMemoryMiB));
            osParser = new TestingUapCoreParser(Section.OS, mebibytes(ruleMemoryMiB), mebibytes(atomSetMemoryMiB));
            userAgents = loadUserAgents(corpus, ruleMemoryMiB);
            // Canonicalizing depends only on the encoding, which both sections share, so one array
            // serves both atom sets.
            canonicalTexts = new Slice[userAgents.length];
            for (int index = 0; index < userAgents.length; index++) {
                canonicalTexts[index] = browserParser.canonicalizeText(TestingUapCoreParser.cut(userAgents[index]));
            }
        }

        int userAgentCount()
        {
            return userAgents.length;
        }
    }

    @Benchmark
    public long rule51FindResult(BenchmarkData data)
    {
        long checksum = 0;
        for (Slice userAgent : data.userAgents) {
            MatchResult match = data.crawlerRule.findResult(TestingUapCoreParser.cut(userAgent));
            if (match == null) {
                checksum = fold(checksum, -1);
            }
            else {
                checksum = fold(checksum, match.start());
                checksum = fold(checksum, match.end());
                checksum = fold(checksum, match.start(1));
                checksum = fold(checksum, match.end(1));
            }
        }
        return checksum;
    }

    @Benchmark
    public long browserAtomSetMatchingPatternIds(BenchmarkData data)
    {
        return matchingAtomIds(data.browserParser, data.canonicalTexts);
    }

    @Benchmark
    public long osAtomSetMatchingPatternIds(BenchmarkData data)
    {
        return matchingAtomIds(data.osParser, data.canonicalTexts);
    }

    @Benchmark
    public long parseBrowser(BenchmarkData data)
    {
        return parse(data.browserParser, data.userAgents);
    }

    @Benchmark
    public long parseOs(BenchmarkData data)
    {
        return parse(data.osParser, data.userAgents);
    }

    @Benchmark
    public long parseBrowserShared(SharedParsers parsers)
    {
        return parse(parsers.browserParser, parsers.userAgents);
    }

    @Benchmark
    public long parseOsShared(SharedParsers parsers)
    {
        return parse(parsers.osParser, parsers.userAgents);
    }

    private static long matchingAtomIds(TestingUapCoreParser parser, Slice[] canonicalTexts)
    {
        long checksum = 0;
        for (Slice canonicalText : canonicalTexts) {
            int[] atomIds = parser.matchingAtomIds(canonicalText);
            // The set returns its IDs in no particular order, so their sum, not their sequence, is folded.
            long atomIdSum = 0;
            for (int atomId : atomIds) {
                atomIdSum += atomId;
            }
            checksum = fold(checksum, atomIdSum);
            checksum = fold(checksum, atomIds.length);
        }
        return checksum;
    }

    private static long parse(TestingUapCoreParser parser, Slice[] userAgents)
    {
        long checksum = 0;
        for (Slice userAgent : userAgents) {
            Parse parse = parser.parse(userAgent);
            checksum = fold(checksum, parse.ruleId());
            for (String field : parse.fields()) {
                checksum = fold(checksum, Objects.hashCode(field));
            }
        }
        return checksum;
    }

    private static long fold(long checksum, long value)
    {
        return checksum * 31 + value;
    }

    private static long mebibytes(int mebibytes)
    {
        return (long) mebibytes << 20;
    }

    private static Re2 compileCrawlerRule(int ruleMemoryMiB)
    {
        String regex = Section.USER_AGENT.rules().get(CRAWLER_RULE_ID).regex();
        return Re2.compile(Slices.utf8Slice(regex), Re2.Options.defaults().setMaxMemory(mebibytes(ruleMemoryMiB)));
    }

    /// Returns the corpus's user agents in test-file order: all of them, or the crawlers among them.
    private static Slice[] loadUserAgents(Corpus corpus, int ruleMemoryMiB)
    {
        return switch (corpus) {
            case UA_TESTS -> testUserAgents(Section.USER_AGENT);
            case OS_TESTS -> testUserAgents(Section.OS);
            case CRAWLERS -> {
                Re2 crawlerRule = compileCrawlerRule(ruleMemoryMiB);
                yield Arrays.stream(testUserAgents(Section.USER_AGENT)).filter(userAgent -> crawlerRule.find(TestingUapCoreParser.cut(userAgent))).toArray(Slice[]::new);
            }
        };
    }

    private static Slice[] testUserAgents(Section section)
    {
        return section.testCases().stream()
                .map(testCase -> Slices.utf8Slice(testCase.getString("user_agent_string")))
                .toArray(Slice[]::new);
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkUapCore.class, args);
        new Runner(options).run();
    }
}
