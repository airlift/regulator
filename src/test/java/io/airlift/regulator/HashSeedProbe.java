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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static io.airlift.slice.Slices.utf8Slice;
import static java.util.Objects.requireNonNull;

public final class HashSeedProbe
{
    // \p{L}+ puts hundreds of suffixes in the rune cache and merges hundreds of byte-map groups,
    // many of them repeats that the group set finds after the first 32 merges. The Greek,
    // Cyrillic and Han class does the same at a smaller scale. The word alternation never uses
    // the rune cache, but repeats dozens of single-byte groups.
    private static final List<String> PATTERNS = List.of(
            "\\p{L}+",
            "(?i)[\\p{Greek}\\p{Cyrillic}]+\\p{Han}",
            "alpha|bravo|charlie|delta|echo|foxtrot|golf|hotel|india|juliett|kilo|lima|mike|november|oscar|papa|quebec|romeo|sierra|tango|uniform|victor|whiskey|xray|yankee|zulu");

    // Large non-ASCII classes, whose member tables intern hundreds of masks.
    private static final List<String> MEMBER_CLASSES = List.of("\\p{L}", "[^\\p{L}\\x00-\\x7f]");

    private HashSeedProbe() {}

    /// Prints the process's hash seed on the first line, then the compiled programs and member
    /// tables of a fixed set of patterns and classes, none of which may depend on the seed.
    public static void main(String[] arguments)
    {
        System.out.printf("seed %016x%n", SeededHash.seedForTesting());
        for (String pattern : PATTERNS) {
            Regexp regexp = parse(pattern);
            for (boolean reversed : new boolean[] {false, true}) {
                Prog program = Compiler.compile(regexp, reversed, 0);
                System.out.printf("pattern %s reversed=%s%n", pattern, reversed);
                System.out.println(program.dump());
                System.out.println(program.dumpUnanchored());
                System.out.println(program.dumpByteMap());
            }
        }
        for (String pattern : MEMBER_CLASSES) {
            printMemberTable(pattern, requireNonNull(parse(pattern).charClass(), pattern));
        }
        printMemberTable("random", randomClass());
    }

    // A fixed random half of the code points from 0x80 to 0x1FFFF, whose member table interns
    // about two thousand masks.
    private static CharClass randomClass()
    {
        Random random = new Random(1);
        List<RuneRange> ranges = new ArrayList<>();
        int runeCount = 0;
        for (int rune = 0x80; rune <= 0x1FFFF; rune++) {
            if (random.nextBoolean()) {
                RuneRange last = ranges.isEmpty() ? null : ranges.getLast();
                if (last != null && last.high() == rune - 1) {
                    ranges.set(ranges.size() - 1, new RuneRange(last.low(), rune));
                }
                else {
                    ranges.add(new RuneRange(rune, rune));
                }
                runeCount++;
            }
        }
        return new CharClass(false, runeCount, ranges.toArray(RuneRange[]::new));
    }

    private static void printMemberTable(String name, CharClass set)
    {
        Utf8MemberTable table = Utf8MemberTable.of(set);
        System.out.printf("member table %s%n", name);
        System.out.println(Arrays.toString(table.leads()));
        System.out.println(Arrays.toString(table.nodes()));
        System.out.println(Arrays.toString(table.lastBytes()));
    }

    private static Regexp parse(String pattern)
    {
        return RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL).regexp();
    }
}
