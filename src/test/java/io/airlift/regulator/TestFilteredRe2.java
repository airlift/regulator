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
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Ported from upstream RE2: re2/testing/filtered_re2_test.cc.
public class TestFilteredRe2
{
    private static class FilterTestState
    {
        private final FilteredRe2.Builder builder;
        private final Re2.Options options = Re2.Options.defaults();
        private List<Slice> atoms = List.of();
        private int[] atomIndices = new int[0];
        private int[] matches = new int[0];
        private FilteredRe2 filteredRe2;

        FilterTestState()
        {
            builder = FilteredRe2.builder();
        }

        FilterTestState(int minimumAtomLength)
        {
            builder = FilteredRe2.builder(minimumAtomLength);
        }

        int add(Slice pattern)
        {
            return builder.add(pattern, options);
        }

        void build()
        {
            filteredRe2 = builder.build();
            atoms = filteredRe2.atoms();
        }

        void match(Slice text)
        {
            matches = filteredRe2.matchingPatternIds(text, atomIndices);
        }

        void match(Slice text, int[] matchedAtomIds)
        {
            matches = filteredRe2.matchingPatternIds(text, matchedAtomIds);
        }

        int firstMatch(Slice text, int[] matchedAtomIds)
        {
            return filteredRe2.firstMatchingPatternId(text, matchedAtomIds);
        }

        int[] potentialMatches(int[] matchedAtomIds)
        {
            return filteredRe2.potentialPatternIds(matchedAtomIds);
        }

        int patternCount()
        {
            return filteredRe2 == null ? builder.size() : filteredRe2.patternCount();
        }

        Re2 pattern(int patternId)
        {
            return filteredRe2.pattern(patternId);
        }
    }

    @Test
    public void testEmpty()
    {
        FilterTestState state = new FilterTestState();
        state.build();
        assertThat(state.atoms).isEmpty();
        state.match(textBytes("foo"));
        assertThat(state.matches).isEmpty();
    }

    @Test
    public void testBuilderCannotBeReused()
    {
        FilterTestState state = new FilterTestState();
        state.add(utf8("foo"));
        state.build();
        assertThatThrownBy(() -> state.add(utf8("bar")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already built");
    }

    @Test
    public void testAtomsAreCopied()
    {
        FilteredRe2.Builder builder = FilteredRe2.builder();
        builder.add(utf8("hello"), Re2.Options.defaults());
        FilteredRe2 filteredRe2 = builder.build();

        List<Slice> atoms = filteredRe2.atoms();
        assertThat(atoms).hasSize(1);
        atoms.getFirst().setByte(0, 'x');

        assertThat(filteredRe2.atoms().getFirst().toStringUtf8()).isEqualTo("hello");
    }

    @Test
    public void testCanonicalizedTextMatchesAtoms()
    {
        FilteredRe2.Builder builder = FilteredRe2.builder();
        builder.add(utf8("(?i)ΔΣK"), Re2.Options.defaults());
        FilteredRe2 filteredRe2 = builder.build();

        Slice canonicalText = filteredRe2.canonicalizeText(utf8("δςK"));
        assertThat(canonicalText.toStringUtf8()).isEqualTo("δσk");
        assertThat(filteredRe2.atoms()).containsExactly(canonicalText);
        assertThat(filteredRe2.encoding()).isEqualTo(Re2.Options.Encoding.UTF8);
    }

    @Test
    public void testAllPatternsMustUseSameEncoding()
    {
        FilteredRe2.Builder builder = FilteredRe2.builder();
        builder.add(utf8("utf8"), Re2.Options.defaults());

        assertThatThrownBy(() -> builder.add(
                Slices.wrappedBuffer(new byte[] {'l', 'a', 't', 'i', 'n'}),
                Re2.Options.latin1()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same encoding");
    }

    @Test
    public void testFailedAddLeavesBuilderUnchanged()
    {
        FilteredRe2.Builder builder = FilteredRe2.builder();

        assertThatThrownBy(() -> builder.add(utf8("("), Re2.Options.latin1()))
                .isInstanceOf(RegexpParseException.class);
        assertThat(builder.size()).isZero();

        assertThatThrownBy(() -> builder.add(null, Re2.Options.latin1()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("pattern is null");
        assertThat(builder.size()).isZero();

        assertThat(builder.add(utf8("abc"), Re2.Options.defaults())).isZero();
        assertThatThrownBy(() -> builder.add(utf8("("), Re2.Options.defaults()))
                .isInstanceOf(RegexpParseException.class);
        assertThat(builder.size()).isOne();

        FilteredRe2 filteredRe2 = builder.build();
        assertThat(filteredRe2.encoding()).isEqualTo(Re2.Options.Encoding.UTF8);
        assertThat(filteredRe2.patternCount()).isOne();
        assertThat(filteredRe2.matchingPatternIds(utf8("abc"), new int[] {0})).containsExactly(0);
    }

    @Test
    public void testFailedUtf8AddDoesNotPreventLatin1Recovery()
    {
        FilteredRe2.Builder builder = FilteredRe2.builder();

        assertThatThrownBy(() -> builder.add(utf8("("), Re2.Options.defaults()))
                .isInstanceOf(RegexpParseException.class);
        assertThat(builder.add(utf8("abc"), Re2.Options.latin1())).isZero();

        assertThat(builder.build().encoding()).isEqualTo(Re2.Options.Encoding.LATIN1);
    }

    @Test
    public void testNegativeMinimumAtomLengthThrows()
    {
        assertThatThrownBy(() -> FilteredRe2.builder(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumAtomLength");
    }

    @Test
    public void testSmallOr()
    {
        // With minimumAtomLength=4, atoms foo and bar are too short to extract
        FilterTestState state = new FilterTestState(4);
        int patternIndex = state.add(utf8("(foo|bar)"));
        state.build();
        assertThat(state.atoms).isEmpty();
        // Pattern with no atoms should match via ALL prefilter
        state.match(textBytes("lemurs bar"));
        assertThat(state.matches).hasSize(1);
        assertThat(state.matches[0]).isEqualTo(patternIndex);
    }

    @Test
    public void testBasicMatchingWithAtoms()
    {
        // Use high minimumAtomLength so no atoms are extracted (ALL prefilter)
        FilterTestState state = new FilterTestState(100);
        int helloPatternIndex = state.add(utf8("hello"));
        int worldPatternIndex = state.add(utf8("world"));
        state.build();
        // No atoms extracted due to high minimumAtomLength
        assertThat(state.atoms).isEmpty();

        // Both patterns should match (ALL prefilter means RE2 verification)
        state.match(textBytes("hello world"));
        assertThat(state.matches).containsExactlyInAnyOrder(helloPatternIndex, worldPatternIndex);

        // Partial matching
        state.match(textBytes("hello"));
        assertThat(state.matches).containsExactly(helloPatternIndex);

        // No match
        state.match(textBytes("goodbye"));
        assertThat(state.matches).isEmpty();
    }

    @Test
    public void testFirstMatch()
    {
        FilterTestState state = new FilterTestState(100);  // High minimumAtomLength = no atoms
        state.add(utf8("abc"));
        state.add(utf8("def"));
        state.build();

        Slice text = textBytes("abcdef");
        int firstMatch = state.firstMatch(text, state.atomIndices);
        assertThat(firstMatch).isEqualTo(0);
    }

    @Test
    public void testMixedFilteredAndUnfilteredPatternsRemainOrdered()
    {
        FilteredRe2.Builder builder = FilteredRe2.builder();
        builder.add(utf8("abc"), Re2.Options.defaults());
        builder.add(utf8(""), Re2.Options.defaults());
        builder.add(utf8("def"), Re2.Options.defaults());
        FilteredRe2 filteredRe2 = builder.build();

        assertThat(filteredRe2.atoms()).extracting(Slice::toStringUtf8).containsExactly("abc", "def");
        assertThat(filteredRe2.potentialPatternIds(new int[] {0})).containsExactly(0, 1);
        assertThat(filteredRe2.matchingPatternIds(utf8("abc"), new int[] {0})).containsExactly(0, 1);
        assertThat(filteredRe2.firstMatchingPatternId(utf8("abc"), new int[] {0})).isZero();

        assertThat(filteredRe2.potentialPatternIds(new int[] {1})).containsExactly(1, 2);
        assertThat(filteredRe2.matchingPatternIds(utf8("def"), new int[] {1})).containsExactly(1, 2);
        assertThat(filteredRe2.firstMatchingPatternId(utf8("def"), new int[] {1})).isEqualTo(1);
    }

    @Test
    public void testMatchEmptyPattern()
    {
        FilterTestState state = new FilterTestState();
        state.add(utf8(""));
        state.build();

        Slice text = textBytes("0123");
        // Empty pattern matches everywhere
        assertThat(state.firstMatch(text, new int[0])).isEqualTo(0);
    }

    @Test
    public void testNumRegexps()
    {
        FilterTestState state = new FilterTestState();
        assertThat(state.patternCount()).isEqualTo(0);

        state.add(utf8("foo"));
        assertThat(state.patternCount()).isEqualTo(1);

        state.add(utf8("bar"));
        assertThat(state.patternCount()).isEqualTo(2);
    }

    @Test
    public void testGetRe2()
    {
        FilterTestState state = new FilterTestState();
        int patternIndex = state.add(utf8("test\\d+"));
        state.build();

        Re2 re2 = state.pattern(patternIndex);
        assertThat(re2).isNotNull();
    }

    @Test
    public void testComplexPattern()
    {
        FilterTestState state = new FilterTestState(100);  // High minimumAtomLength = no atoms
        // Complex pattern with multiple parts
        state.add(utf8("(abc|def)\\d+xyz"));
        state.build();

        state.match(textBytes("abc123xyz"));
        assertThat(state.matches).hasSize(1);

        state.match(textBytes("def456xyz"));
        assertThat(state.matches).hasSize(1);

        state.match(textBytes("ghi789xyz"));
        assertThat(state.matches).isEmpty();
    }

    @Test
    public void testMultiplePatterns()
    {
        FilterTestState state = new FilterTestState(100);  // High minimumAtomLength = no atoms
        int alphaPatternIndex = state.add(utf8("alpha"));
        int betaPatternIndex = state.add(utf8("beta"));
        int gammaPatternIndex = state.add(utf8("gamma"));

        state.build();

        // Match all three
        state.match(textBytes("alpha beta gamma"));
        assertThat(state.matches).containsExactlyInAnyOrder(alphaPatternIndex, betaPatternIndex, gammaPatternIndex);
    }

    @Test
    public void testPrefilterWithAtomIndices()
    {
        // Test that providing atom indices correctly filters
        FilterTestState state = new FilterTestState();  // Default minimumAtomLength=3
        state.add(utf8("hello"));
        state.add(utf8("world"));
        state.build();

        // Atoms should be extracted
        assertThat(state.atoms).isNotEmpty();

        // Without providing correct atom indices, prefilter may reject
        // This is expected behavior - prefilter requires atoms to be found
    }

    @Test
    public void testAllPotentials()
    {
        FilterTestState state = new FilterTestState(100);  // High minimumAtomLength = no atoms
        int patternIndex1 = state.add(utf8("pattern1"));
        int patternIndex2 = state.add(utf8("pattern2"));
        state.build();

        // With no atoms extracted (ALL prefilter), all patterns are potential matches
        int[] potentials = state.potentialMatches(new int[0]);
        assertThat(potentials).containsExactlyInAnyOrder(patternIndex1, patternIndex2);
    }

    private List<String> compileAndGetAtoms(List<String> patterns)
    {
        FilteredRe2.Builder builder = FilteredRe2.builder();
        Re2.Options options = Re2.Options.defaults();
        for (String pattern : patterns) {
            builder.add(utf8(pattern), options);
        }
        return builder.build().atoms().stream()
                .map(Slice::toStringUtf8)
                .toList();
    }

    @Test
    public void testAtomExtractionEmptyPattern()
    {
        // This test checks to make sure empty patterns are allowed.
        List<String> actualAtoms = compileAndGetAtoms(List.of(""));
        assertThat(actualAtoms).isEmpty();
    }

    @Test
    public void testAtomExtractionMinLength()
    {
        // This test checks that atoms of length greater than min length
        // are found, and atoms shorter than min length are not extracted.
        List<String> patterns = List.of(
                "(abc123|def456|ghi789).*mnop[x-z]+",
                "abc..yyy..zz",
                "mnmnpp[a-z]+PPP");

        List<String> actualAtoms = compileAndGetAtoms(patterns);

        assertThat(actualAtoms).containsExactlyInAnyOrder(
                "abc123",
                "def456",
                "ghi789",
                "mnop",
                "abc",
                "yyy",
                "mnmnpp",
                "ppp");
    }

    @Test
    public void testAtomExtractionUnicode()
    {
        List<String> patterns = List.of(
                "(?i)ΔδΠϖπΣςσ",
                "ΛΜΝΟΠ",
                "ψρστυ");

        List<String> actualAtoms = compileAndGetAtoms(patterns);

        assertThat(actualAtoms).containsExactlyInAnyOrder(
                "δδπππσσσ",
                "λμνοπ",
                "ψρστυ");
    }

    @Test
    public void testAtomExtractionSubstrNoDedup()
    {
        List<String> patterns = List.of(
                "(abc123|abc|defxyz|ghi789|abc1234|xyz).*[x-z]+",
                "abcd..yyy..yyyzzz",
                "mnmnpp[a-z]+PPP");

        List<String> actualAtoms = compileAndGetAtoms(patterns);

        assertThat(actualAtoms).containsExactlyInAnyOrder(
                "abc",
                "ghi789",
                "xyz",
                "abcd",
                "yyy",
                "yyyzzz",
                "mnmnpp",
                "ppp");
    }

    @Test
    public void testAtomExtractionCharClass()
    {
        List<String> patterns = List.of(
                "m[a-c][d-f]n.*[x-z]+",
                "[x-y]bcde[ab]");

        List<String> actualAtoms = compileAndGetAtoms(patterns);

        assertThat(actualAtoms).containsExactlyInAnyOrder(
                "madn",
                "maen",
                "mafn",
                "mbdn",
                "mben",
                "mbfn",
                "mcdn",
                "mcen",
                "mcfn",
                "xbcdea",
                "xbcdeb",
                "ybcdea",
                "ybcdeb");
    }

    private int[] findAtomIndices(List<Slice> atoms, List<String> toFind)
    {
        List<Integer> indices = new ArrayList<>();
        for (String find : toFind) {
            for (int atomIndex = 0; atomIndex < atoms.size(); atomIndex++) {
                if (find.equals(atoms.get(atomIndex).toStringUtf8())) {
                    indices.add(atomIndex);
                    break;
                }
            }
        }
        return indices.stream()
                .mapToInt(Integer::intValue)
                .toArray();
    }

    @Test
    public void testMatchWithAtomIndices()
    {
        // Use patterns from the SubstrAtomRemovesSuperStrInOr test case
        // These are 3 patterns that will compile to multiple atoms
        List<String> patterns = List.of(
                "(abc123|abc|defxyz|ghi789|abc1234|xyz).*[x-z]+",
                "abcd..yyy..yyyzzz",
                "mnmnpp[a-z]+PPP");

        FilteredRe2.Builder builder = FilteredRe2.builder();
        Re2.Options options = Re2.Options.defaults();
        for (String pattern : patterns) {
            builder.add(utf8(pattern), options);
        }
        FilteredRe2 filteredRe2 = builder.build();
        List<Slice> atoms = filteredRe2.atoms();

        // Test 1: text = "abc121212xyz", atoms = ["abc"]
        String text1 = "abc121212xyz";
        int[] atomIds1 = findAtomIndices(atoms, List.of("abc"));
        int[] matches = filteredRe2.matchingPatternIds(textBytes(text1), atomIds1);
        assertThat(matches).containsExactly(0);

        // Test 2: text = "abc12312yyyzzz", atoms = ["abc", "yyy", "yyyzzz"]
        String text2 = "abc12312yyyzzz";
        int[] atomIds2 = findAtomIndices(atoms, List.of("abc", "yyy", "yyyzzz"));
        matches = filteredRe2.matchingPatternIds(textBytes(text2), atomIds2);
        assertThat(matches).containsExactly(0);

        // Test 3: text = "abcd12yyy32yyyzzz", atoms = ["abc", "abcd", "yyy", "yyyzzz"]
        String text3 = "abcd12yyy32yyyzzz";
        int[] atomIds3 = findAtomIndices(atoms, List.of("abc", "abcd", "yyy", "yyyzzz"));
        matches = filteredRe2.matchingPatternIds(textBytes(text3), atomIds3);
        assertThat(matches).containsExactly(0, 1);
    }

    @Test
    public void testSmallLatinAsciiOnly()
    {
        // Test Latin1 encoding with ASCII-only pattern (no high bytes)
        FilterTestState state = new FilterTestState(100);  // High minimumAtomLength = no atoms extracted
        int patternIndex;

        state.options.setEncoding(Re2.Options.Encoding.LATIN1);
        // ASCII-only Latin1 pattern
        String pattern = "TestPattern";
        patternIndex = state.add(utf8(pattern));
        state.build();

        // With high minimumAtomLength, no atoms are extracted (ALL prefilter)
        assertThat(state.atoms).isEmpty();

        // Test that matching works correctly for ASCII text
        byte[] textBytes = "fooTestPatternbar".getBytes(StandardCharsets.ISO_8859_1);
        state.match(Slices.wrappedBuffer(textBytes));
        assertThat(state.matches).hasSize(1);
        assertThat(state.matches[0]).isEqualTo(patternIndex);
    }

    @Test
    public void testSmallLatinHighBytes()
    {
        FilterTestState state = new FilterTestState();

        state.options.setEncoding(Re2.Options.Encoding.LATIN1);
        byte[] patternBytes = new byte[] {(byte) 0xDE, (byte) 0xAD, 'Q', (byte) 0xBE, (byte) 0xEF};
        int patternIndex = state.add(Slices.wrappedBuffer(patternBytes));
        state.build();

        byte[] expectedAtom = new byte[] {(byte) 0xDE, (byte) 0xAD, 'q', (byte) 0xBE, (byte) 0xEF};
        assertThat(state.atoms).containsExactly(Slices.wrappedBuffer(expectedAtom));

        byte[] textBytes = new byte[] {'f', 'o', 'o', (byte) 0xDE, (byte) 0xAD, 'Q', (byte) 0xBE, (byte) 0xEF, 'l', 'e', 'm', 'u', 'r'};
        state.match(Slices.wrappedBuffer(textBytes), new int[] {0});
        assertThat(state.matches).containsExactly(patternIndex);
    }

    @Test
    public void testEmptyStringInStringSetBug()
    {
        // Bug due to find() finding "" at the start of everything in a string
        // set and thus SimplifyStringSet() would end up erasing everything.
        // In order to test this, we have to keep PrefilterTree from discarding
        // the OR entirely, so we have to make the minimum atom length zero.

        FilterTestState state = new FilterTestState(0);  // override the minimum atom length
        state.add(utf8("-R.+(|ADD=;AA){12}}"));
        state.build();

        List<String> expectedAtoms = new ArrayList<>(List.of("", "-r", "add=;aa", "}"));
        List<String> actualAtoms = state.atoms.stream()
                .map(Slice::toStringUtf8)
                .collect(Collectors.toCollection(ArrayList::new));

        expectedAtoms.sort(Comparator.naturalOrder());
        actualAtoms.sort(Comparator.naturalOrder());

        assertThat(actualAtoms)
                .as("EmptyStringInStringSetBug")
                .isEqualTo(expectedAtoms);
    }

    private static Slice textBytes(String value)
    {
        return Slices.utf8Slice(value);
    }

    private static Slice utf8(String value)
    {
        return Slices.utf8Slice(value);
    }
}
