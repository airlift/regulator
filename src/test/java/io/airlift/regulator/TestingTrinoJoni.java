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

import io.airlift.jcodings.specific.NonStrictUTF8Encoding;
import io.airlift.joni.Matcher;
import io.airlift.joni.Option;
import io.airlift.joni.Regex;
import io.airlift.joni.Region;
import io.airlift.joni.Syntax;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.airlift.regulator.TestingPatternGenerator.TEXT_CHARACTERS;
import static io.airlift.slice.SliceUtf8.countCodePoints;
import static io.airlift.slice.SliceUtf8.lengthOfCodePointFromStartByte;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Compares Trino patterns with Trino's pinned Joni, `io.airlift:joni:2.1.5.3`.
final class TestingTrinoJoni
{
    // Trino's pinned Joni leaves these out of [[:punct:]], and Regulator includes them as modern
    // Joni does: TRINO_REGEXP.md, Unicode data and case folding. Inputs of a pattern that uses
    // the bracket punct class leave them out.
    static final List<String> ASCII_SYMBOLS = List.of("$", "+", "<", "=", ">", "^", "`", "|", "~");

    // Characters whose full case folding has more than one code point. A case-insensitive pattern
    // keeps them out of both the pattern and its inputs, so pinned Joni's tokenization artifacts
    // and Regulator's literal-run rule never meet: TRINO_REGEXP.md, Other matching differences
    // from Joni, and REGULATOR_DECISIONS.md, Trino Text-Dependent Assertions And Full Case Folding.
    static final List<String> FULL_FOLD_CHARACTERS = List.of("ß", "ﬁ", "İ", "ŉ", "ΐ");

    // Letters that Joni reads as themselves after a backslash.
    static final List<String> LITERAL_ESCAPE_LETTERS = List.of("C", "E", "H", "R", "V", "X", "h", "q");

    // Joni keeps bits 7 and 0-4 of the whole character after \c, so a non-ASCII text character
    // can write a control character: \c中 is a carriage return, and \cǅ (U+01C5) is U+0085.
    static final Map<Integer, List<String>> NON_ASCII_CONTROL_OPERANDS = nonAsciiControlOperands();

    // Property names TrinoRegexp and pinned Joni both accept with the same members on the text
    // characters. Olck, Sundanese, and Saurashtra are left out: pinned Joni reads them as ASCII
    // \s, \d, and \w, a recorded difference (REGULATOR_DECISIONS.md, Trino Compatibility Adapter).
    static final List<String> PROPERTY_NAMES = List.of(
            "L",
            "Lu",
            "Ll",
            "Lt",
            "Lm",
            "Lo",
            "M",
            "Mn",
            "Mc",
            "N",
            "Nd",
            "Nl",
            "No",
            "P",
            "Pc",
            "Pd",
            "Ps",
            "Pe",
            "Po",
            "S",
            "Sm",
            "Sc",
            "Sk",
            "So",
            "Z",
            "Zs",
            "Zl",
            "C",
            "Cc",
            "Cf",
            "lu",
            "nd",
            "Latin",
            "Greek",
            "Cyrillic",
            "Han",
            "Common",
            "Inherited",
            "Hiragana",
            "Deseret",
            "Arabic",
            "greek",
            "Latn",
            "InBasicLatin",
            "InGreekandCoptic",
            "InCyrillic",
            "InCJKUnifiedIdeographs",
            "InCombiningDiacriticalMarks",
            "InMiscellaneousSymbolsandPictographs",
            "InLetterlikeSymbols",
            "InGeneralPunctuation",
            "InHiragana",
            "InDeseret",
            "InHalfwidthandFullwidthForms",
            "InArabic",
            "Alphabetic",
            "Uppercase",
            "Lowercase",
            "WhiteSpace",
            "Ideographic",
            "Letter",
            "Punctuation",
            "Control",
            "Digit",
            "Assigned",
            "Any",
            "NoncharacterCodePoint",
            "JoinControl",
            "javaLowerCase",
            "javaUpperCase",
            "javaDigit",
            "javaLetter",
            "javaWhitespace",
            "javaAlphabetic",
            "javaTitleCase",
            "javaMirrored",
            "javaLetterOrDigit",
            "javaSpaceChar",
            "javaISOControl",
            "javaJavaIdentifierStart",
            "javaUnicodeIdentifierPart",
            "javaIdentifierIgnorable",
            "javaDefined",
            "javaIdeographic",
            "Alpha",
            "Alnum",
            "Upper",
            "Lower",
            "Space",
            "Punct",
            "Word",
            "XDigit",
            "Blank",
            "Cntrl",
            "Graph",
            "Print",
            "ASCII",
            "HexDigit",
            "alpha",
            "LC",
            "CasedLetter",
            "Qaac",
            "InLatin1Supplement",
            "InGreek",
            "InNoBlock",
            "WSpace",
            "AHex",
            "Dash",
            "Math",
            "XIDContinue",
            "OAlpha",
            "Ideo",
            "Newline",
            "age=1.1",
            "age=3.0",
            "age=6.1",
            "L1",
            "LD",
            "all",
            "perlHorizontalWhiteSpace",
            "perlVerticalWhiteSpace",
            "javaJavaIdentifierPart",
            "javaUnicodeIdentifierStart");

    static final List<String> SHORT_PROPERTY_NAMES = List.of("L", "N", "P", "S", "Z", "M", "C", "l");

    static final List<String> POSIX_BRACKET_NAMES = List.of(
            "alpha",
            "digit",
            "alnum",
            "upper",
            "lower",
            "space",
            "punct",
            "word",
            "xdigit",
            "blank",
            "cntrl",
            "graph",
            "print",
            "ascii");

    private TestingTrinoJoni() {}

    /// Compiles `expression` as Trino compiles a pattern with Joni.
    public static Regex joniPattern(String expression)
    {
        byte[] pattern = expression.getBytes(UTF_8);
        return new Regex(pattern, 0, pattern.length, Option.DEFAULT, NonStrictUTF8Encoding.INSTANCE, Syntax.Java);
    }

    /// Compares every public operation of `regexp` on each source with the matches, as
    /// start/end pairs for each group, that `expectedMatches` returns for it. A failure names
    /// `description` and the source.
    public static void verifyOperations(String description, TrinoRegexp regexp, Function<Slice, List<int[]>> expectedMatches, List<Slice> sources)
    {
        List<String> sourceDescriptions = new ArrayList<>(sources.size());
        for (Slice source : sources) {
            sourceDescriptions.add(description + " on " + (Utf8.firstInvalidOffset(source) < 0 ? source.toStringUtf8() : HexFormat.of().formatHex(source.getBytes())));
        }
        verifyOperations(sourceDescriptions, regexp, expectedMatches, sources);
    }

    /// Compares every public operation of `regexp` on each source with the matches, as
    /// start/end pairs for each group, that `expectedMatches` returns for it. A failure names
    /// the source's entry in `sourceDescriptions`.
    public static void verifyOperations(List<String> sourceDescriptions, TrinoRegexp regexp, Function<Slice, List<int[]>> expectedMatches, List<Slice> sources)
    {
        assertThat(sourceDescriptions).hasSameSizeAs(sources);
        int groupCount = regexp.capturingGroupCount();
        int replacementGroup = groupCount == 0 ? 0 : 1;
        Slice replacementPattern = utf8Slice("$" + replacementGroup);
        TrinoRegexpMatcher reusable = regexp.matcher(EMPTY_SLICE);
        TrinoRegexpMatcher groupZero = regexp.matcher(EMPTY_SLICE, 0);
        for (int index = 0; index < sources.size(); index++) {
            Slice source = sources.get(index);
            String sourceDescription = sourceDescriptions.get(index);
            List<int[]> matches = expectedMatches.apply(source);
            assertThat(regexp.contains(source)).as(sourceDescription).isEqualTo(!matches.isEmpty());
            assertThat(regexp.count(source)).as(sourceDescription).isEqualTo(matches.size());
            for (int occurrence = 1; occurrence <= matches.size() + 1; occurrence++) {
                // As in Trino, a start past the last code point finds nothing, so an empty source
                // has no position even where the pattern matches it.
                long position = occurrence > matches.size() || source.length() == 0 ? -1 : countCodePoints(source, 0, matches.get(occurrence - 1)[0]) + 1L;
                assertThat(regexp.position(source, 1, occurrence)).as("%s, occurrence %s", sourceDescription, occurrence).isEqualTo(position);
            }

            reusable.reset(source);
            groupZero.reset(source);
            for (int[] match : matches) {
                assertThat(reusable.find()).as(sourceDescription).isTrue();
                assertThat(groupZero.find()).as(sourceDescription).isTrue();
                assertThat(groupZero.start()).as(sourceDescription).isEqualTo(match[0]);
                assertThat(groupZero.end()).as(sourceDescription).isEqualTo(match[1]);
                for (int group = 0; group <= groupCount; group++) {
                    assertThat(reusable.start(group)).as(sourceDescription).isEqualTo(match[2 * group]);
                    assertThat(reusable.end(group)).as(sourceDescription).isEqualTo(match[2 * group + 1]);
                    assertThat(reusable.group(group)).as("%s, group %s", sourceDescription, group).isEqualTo(group(source, match, group));
                }
            }
            assertThat(reusable.find()).as(sourceDescription).isFalse();
            assertThatThrownBy(reusable::start).isInstanceOf(IllegalStateException.class);
            assertThat(groupZero.find()).as(sourceDescription).isFalse();

            for (int group = 0; group <= groupCount; group++) {
                Slice first = matches.isEmpty() ? null : group(source, matches.getFirst(), group);
                assertThat(regexp.extract(source, group)).as("%s, group %s", sourceDescription, group).isEqualTo(first);
                List<Slice> all = new ArrayList<>();
                for (int[] match : matches) {
                    all.add(group(source, match, group));
                }
                assertThat(regexp.extractAll(source, group)).as("%s, group %s", sourceDescription, group).isEqualTo(all);
            }

            SplitAndReplace expected = splitAndReplace(source, matches, groupCount);
            assertThat(regexp.split(source)).as(sourceDescription).isEqualTo(expected.parts());
            if (matches.isEmpty()) {
                assertThat(regexp.replace(source, replacementPattern)).as(sourceDescription).isSameAs(source);
            }
            else {
                assertThat(regexp.replace(source, replacementPattern)).as(sourceDescription).isEqualTo(expected.replaced());
            }
            assertThat(regexp.replace(source, TestScanPlan::callbackReplacement)).as(sourceDescription).isEqualTo(expected.replacedByCallback());
        }
    }

    /// Trino's split and replace of a source with `matches`: the parts between the matches, the
    /// source with each match replaced by its first group, or the whole match in a pattern
    /// without groups, and the source with each match replaced by
    /// [TestScanPlan#callbackReplacement].
    record SplitAndReplace(List<Slice> parts, Slice replaced, Slice replacedByCallback) {}

    /// Returns the split and replace results Trino gives for `matches`, start/end pairs for each
    /// of `groupCount` groups and the whole match.
    public static SplitAndReplace splitAndReplace(Slice source, List<int[]> matches, int groupCount)
    {
        int replacementGroup = groupCount == 0 ? 0 : 1;
        List<Slice> parts = new ArrayList<>();
        DynamicSliceOutput replaced = new DynamicSliceOutput(source.length());
        DynamicSliceOutput replacedByCallback = new DynamicSliceOutput(source.length());
        int previousEnd = 0;
        for (int[] match : matches) {
            parts.add(source.slice(previousEnd, match[0] - previousEnd));
            replaced.writeBytes(source, previousEnd, match[0] - previousEnd);
            replacedByCallback.writeBytes(source, previousEnd, match[0] - previousEnd);
            Slice replacementValue = group(source, match, replacementGroup);
            replaced.writeBytes(replacementValue == null ? EMPTY_SLICE : replacementValue);
            List<Slice> groups = new ArrayList<>();
            for (int group = 1; group <= groupCount; group++) {
                groups.add(group(source, match, group));
            }
            replacedByCallback.writeBytes(TestScanPlan.callbackReplacement(groups));
            previousEnd = match[1];
        }
        parts.add(source.slice(previousEnd, source.length() - previousEnd));
        replaced.writeBytes(source, previousEnd, source.length() - previousEnd);
        replacedByCallback.writeBytes(source, previousEnd, source.length() - previousEnd);
        return new SplitAndReplace(parts, replaced.slice(), replacedByCallback.slice());
    }

    /// Returns every non-overlapping Joni match as start/end pairs for each group, searching again
    /// from the previous match end as Trino's Joni functions do.
    public static List<int[]> joniMatches(Regex oracle, Slice source)
    {
        int base = source.byteArrayOffset();
        Matcher matcher = oracle.matcher(source.byteArray(), base, base + source.length());
        List<int[]> matches = new ArrayList<>();
        int next = 0;
        while (next <= source.length() && matcher.search(base + next, base + source.length(), Option.DEFAULT) >= 0) {
            Region region = matcher.getEagerRegion();
            int[] match = new int[2 * (oracle.numberOfCaptures() + 1)];
            for (int group = 0; group <= oracle.numberOfCaptures(); group++) {
                match[2 * group] = region.beg[group];
                match[2 * group + 1] = region.end[group];
            }
            matches.add(match);
            next = matcher.getEnd();
            if (matcher.getBegin() == next) {
                next += next < source.length() ? lengthOfCodePointFromStartByte(source.getByte(next)) : 1;
            }
        }
        return matches;
    }

    public static Slice group(Slice source, int[] match, int group)
    {
        int start = match[2 * group];
        return start < 0 ? null : source.slice(start, match[2 * group + 1] - start);
    }

    private static Map<Integer, List<String>> nonAsciiControlOperands()
    {
        Map<Integer, List<String>> operands = new HashMap<>();
        for (String character : TEXT_CHARACTERS) {
            int codePoint = character.codePointAt(0);
            if (codePoint >= 0x80) {
                operands.computeIfAbsent(codePoint & 0x9F, _ -> new ArrayList<>()).add(character);
            }
        }
        return Map.copyOf(operands);
    }
}
