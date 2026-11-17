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

import io.airlift.regulator.UapCoreParser.Parse;
import io.airlift.regulator.UapCoreParser.Section;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;

/// The public uap-core user-agent rules run the way an application runs them on Regulator, with
/// [UapCoreParser]: each section's rules in one [FilteredRe2], its atoms in one [Re2Set], and the
/// candidate rules tried in order until one matches. The browser section holds 433 rules and the OS
/// section 204. Every row makes one pass over the selected corpus and returns a checksum of the
/// results, folding each value as `checksum * 31 + value` from `0`:
///
/// - `rule51FindResult`: browser rule 51, the crawler alternation, alone as an [Re2]; folds each
///   match's start and end and group 1's start and end, or `-1` for no match
/// - `browserAtomSetMatchingPatternIds` and `osAtomSetMatchingPatternIds`: the section's atom set
///   over each user agent's canonicalized text, prepared at setup; folds the number of atoms found
///   and their sorted IDs
/// - `parseBrowser` and `parseOs`: the full parse of each user agent; folds the winning rule ID, or
///   `-1`, and the hash of each result field, `0` when absent
/// - `parseBrowserShared` and `parseOsShared`: the same parses with one compiled section that every
///   JMH thread shares; run them with `-t 16`. The other rows compile the sections for each thread,
///   so with the same `-t` the parse rows are their per-thread controls.
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
        /// The browser-test user agents in which rule 51 alone finds a match: the crawlers.
        CRAWLERS,
    }

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param
        Corpus corpus;

        @Param("32")
        int ruleMemoryMiB;

        @Param("32")
        int atomSetMemoryMiB;

        private Re2 crawlerRule;
        private UapCoreParser browserParser;
        private UapCoreParser osParser;
        private Slice[] userAgents;
        private Slice[] browserCanonicalTexts;
        private Slice[] osCanonicalTexts;

        @Setup
        public void setup()
        {
            crawlerRule = compileCrawlerRule(ruleMemoryMiB);
            browserParser = new UapCoreParser(Section.USER_AGENT, mebibytes(ruleMemoryMiB), mebibytes(atomSetMemoryMiB));
            osParser = new UapCoreParser(Section.OS, mebibytes(ruleMemoryMiB), mebibytes(atomSetMemoryMiB));
            userAgents = loadUserAgents(corpus, crawlerRule);
            browserCanonicalTexts = canonicalTexts(browserParser, userAgents);
            osCanonicalTexts = canonicalTexts(osParser, userAgents);
        }
    }

    /// The compiled sections and the corpus, shared by every JMH thread.
    @State(Scope.Benchmark)
    public static class SharedParsers
    {
        @Param
        Corpus corpus;

        @Param("32")
        int ruleMemoryMiB;

        @Param("32")
        int atomSetMemoryMiB;

        private UapCoreParser browserParser;
        private UapCoreParser osParser;
        private Slice[] userAgents;

        @Setup
        public void setup()
        {
            browserParser = new UapCoreParser(Section.USER_AGENT, mebibytes(ruleMemoryMiB), mebibytes(atomSetMemoryMiB));
            osParser = new UapCoreParser(Section.OS, mebibytes(ruleMemoryMiB), mebibytes(atomSetMemoryMiB));
            userAgents = loadUserAgents(corpus, compileCrawlerRule(ruleMemoryMiB));
        }
    }

    @Benchmark
    public long rule51FindResult(BenchmarkData data)
    {
        long checksum = 0;
        for (Slice userAgent : data.userAgents) {
            MatchResult match = data.crawlerRule.findResult(UapCoreParser.cut(userAgent));
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
        return matchingAtomIds(data.browserParser, data.browserCanonicalTexts);
    }

    @Benchmark
    public long osAtomSetMatchingPatternIds(BenchmarkData data)
    {
        return matchingAtomIds(data.osParser, data.osCanonicalTexts);
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

    private static long matchingAtomIds(UapCoreParser parser, Slice[] canonicalTexts)
    {
        long checksum = 0;
        for (Slice canonicalText : canonicalTexts) {
            int[] atomIds = parser.matchingAtomIds(canonicalText);
            // The set returns its IDs in no particular order.
            Arrays.sort(atomIds);
            checksum = fold(checksum, atomIds.length);
            for (int atomId : atomIds) {
                checksum = fold(checksum, atomId);
            }
        }
        return checksum;
    }

    private static long parse(UapCoreParser parser, Slice[] userAgents)
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

    static Re2 compileCrawlerRule(int ruleMemoryMiB)
    {
        String regex = Section.USER_AGENT.rules().get(CRAWLER_RULE_ID).regex();
        return Re2.compile(Slices.utf8Slice(regex), Re2.Options.defaults().setMaxMemory(mebibytes(ruleMemoryMiB)));
    }

    /// Returns the corpus's user agents, in test-file order. The crawlers are the browser-test user
    /// agents in which `crawlerRule` finds a match.
    static Slice[] loadUserAgents(Corpus corpus, Re2 crawlerRule)
    {
        List<Slice> userAgents = (corpus == Corpus.OS_TESTS ? Section.OS : Section.USER_AGENT).testCases().stream()
                .map(testCase -> testCase.getString("user_agent_string"))
                .map(Slices::utf8Slice)
                .toList();
        if (corpus == Corpus.CRAWLERS) {
            userAgents = userAgents.stream()
                    .filter(userAgent -> crawlerRule.find(UapCoreParser.cut(userAgent)))
                    .toList();
        }
        return userAgents.toArray(Slice[]::new);
    }

    private static Slice[] canonicalTexts(UapCoreParser parser, Slice[] userAgents)
    {
        Slice[] canonicalTexts = new Slice[userAgents.length];
        for (int index = 0; index < userAgents.length; index++) {
            canonicalTexts[index] = parser.canonicalizeText(UapCoreParser.cut(userAgents[index]));
        }
        return canonicalTexts;
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkUapCore.class, args);
        new Runner(options).run();
    }
}
