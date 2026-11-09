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

import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.airlift.slice.Slices.utf8Slice;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

public class TestInnerLiteralSearch
{
    private static final RustRegexp.Options RUST_BYTES_OPTIONS = RustRegexp.Options.defaults().setBytes(true);
    private static final String FILLER = "-".repeat(InnerLiteralSearch.MINIMUM_SKIPPED_BYTES);
    private static final String DATE = "([0-9][0-9]?)/([0-9][0-9]?)/([0-9][0-9]([0-9][0-9])?)";
    private static final String WORD_DATE = "\\b" + DATE + "\\b";
    private static final String EMAIL = "([^ @]+)@([^ @]+)";
    private static final String NOQA_ASSIGNMENT = "[a-z]+=(?i:noqa)";
    private static final String COLON_THEN_AT = "\\w+:[a-z]+@";

    private static final List<String> PATTERNS = List.of(
            "[\\w\\.+-]+@[\\w\\.-]+\\.[\\w\\.-]+",
            "[a-z]shing",
            "\\b\\w+\\s+Holmes\\s+\\w+\\b",
            "\\w+\\s+Holmes\\s+\\w+",
            "\\w+\\s+Holmes",
            "\\s[a-zA-Z]{0,12}ing\\s",
            "(\\w+)@(\\w+)\\.(\\w+)",
            "\\w+://[^\\s/]+",
            "(?m)^\\w+:\\s",
            "\\w+\\b@\\w",
            "[a-q][^u-z]{3}xy",
            "[a-z]+ing",
            "\\w+\\w+Holmes",
            "[xz]+zx",
            "(?:az|z)xx",
            "(?:a@.*b|c)@",
            "(?:a[^@]*@[^@]*d|c)@",
            "(\\s*)((?:# [Nn][Oo][Qq][Aa])(?::\\s?(([A-Z]+[0-9]+(?:[,\\s]+)?)+))?)",
            "(\\s*)((?i:# noqa)(?::\\s?([A-Z]+[0-9]+))?)",
            "(\\w+)(@(\\w+))",
            "([A-Za-z]awyer|[A-Za-z]inn)\\s",
            "[a-z](?:inn|innk)\\s",
            "[a-z](?:aba|bab)\\s",
            "\\s(?:foo|barbaz)\\d",
            "\\s[a-z]{0,12}(?:ing|ment)\\s",
            "[\"'][^\"']{0,30}[?!.][\"']",
            DATE,
            "\\b\\w+nn\\b",
            ".{0,2}(Tom|Sawyer|Huckleberry|Finn)",
            ".{2,4}(Tom|Sawyer|Huckleberry|Finn)",
            "\\w*nn",
            "\\b\\w+n\\b",
            "[a-z]{2,5}ing",
            "\\B\\w+nn\\b",
            "(?m)^.{0,3}Finn",
            "(?:..@cbb|b)@(?:c|d)",
            "(?i:k)ey = value",
            "\\w+\\s(?i)holmes\\b",
            "\\w+ (?i)sherlock holmes",
            "[a-z]+(?i)error:",
            "\\d+ (?i)password",
            "(?i)\\w+ holmes",
            "\\w+(?i:holmes)",
            "\\w+\\s(?i:ho)",
            "(?:QHO)?(?i:ho)",
            "[a-z](?i: noqa)@x",
            // Outside RE2 the class is empty, so the program can never match.
            "\\s\\*[a&&b]{1,2}",
            // Gates up to four bytes before the literal, and behind an assertion at the prefix's
            // end, which the walk reads as empty.
            "\\s[a-z]+@",
            "\\s[a-z]{3,}@x",
            "\\b[a-z]{2}\\b@x",
            // Gates after the literal that word boundaries set: alone, after a literal ending in a
            // word byte, in a byte that is not one, or in a letter of either case; with the class
            // after them; and none where they rule out every byte of that class.
            "\\w+nn\\B",
            "[a-z]+@\\b",
            "[a-z]+#(?i:noqa)\\b",
            "\\w+\\s+Holmes\\b",
            "\\w+nn\\b\\s",
            "[a-z]+nn\\b[a-z]",
            // Counts that end each match at its literal's end behind a prefix of fixed length,
            // here before a word boundary, and one kept off that route: a run before a boundary,
            // as in `\b\w+nn\b`, whose class holds a byte that is not a word byte, so that a
            // match may run past its first hit.
            "[a-z]{2}ab\\b",
            "[-a-z]+nn\\b");

    @Test
    public void testSelectsLiteralWithProof()
    {
        // The prefix cannot hold the literal.
        assertThat(literal("[\\w\\.+-]+@[\\w\\.-]+\\.[\\w\\.-]+")).isEqualTo("@");
        assertThat(literal("\\w+://[^\\s/]+")).isEqualTo("://");
        // The prefix has a fixed length.
        assertThat(literal("[a-z]shing")).isEqualTo("shing");
        assertThat(literal("[a-q][^u-z]{3}xy")).isEqualTo("xy");
        assertThat(literal("[a-z]" + "xy".repeat(32))).isEqualTo("xy".repeat(32));
        // A required class at an end of the prefix separates the literal from an earlier match.
        assertThat(literal("\\w+\\s+Holmes")).isEqualTo("Holmes");
        assertThat(literal("\\b\\w+\\s+Holmes\\s+\\w+\\b")).isEqualTo("Holmes");
        assertThat(literal("\\s[a-zA-Z]{0,12}ing\\s")).isEqualTo("ing");
        assertThat(literal("\\w+(?:ab)?\\s+ing")).isEqualTo("ing");
        // Capturing groups in the top-level concatenation are looked through, as the crate's
        // top_concat does, so an inner literal may sit inside a group. Case-insensitive ASCII
        // letters extend the literal after its exact head, the exact bytes before the first
        // case-insensitive letter; the literal is shown with those letters lowered.
        assertThat(literal("(\\s*)((?:# [Nn][Oo][Qq][Aa])(?::\\s?(([A-Z]+[0-9]+(?:[,\\s]+)?)+))?)")).isEqualTo("# noqa");
        assertThat(literal("(?P<spaces>\\s*)(?P<noqa>(?i:# noqa)(?::\\s?(?P<codes>([A-Z]+[0-9]+(?:[,\\s]+)?)+))?)")).isEqualTo("# noqa");
        // A folded literal without an exact head, or with a head of one common byte, is searched
        // only by the folded pair kernel, so it needs the Vector API; without it, a later literal
        // decides. RE2's s also folds to the long s, so RE2's literal ends before it. Such a
        // literal also gives way to a later one searched from shorter windows, as `@x` is, but not
        // to a later element no proof admits, as the `x` after `\w+`.
        InnerLiteralSearch commonHeadSearch = Re2.compile(utf8Slice("[a-z](?i: noqa)@x")).innerLiteralSearchForDiagnostics();
        assertThat(literal(commonHeadSearch)).isEqualTo("@x");
        for (Map.Entry<String, String> entry : List.of(
                Map.entry("\\w+\\s(?i)holmes\\b", "holme"),
                Map.entry("\\s+(?i:noqa)", "noqa"),
                Map.entry("\\w+(?i:holmes)", "holme"),
                Map.entry("\\w+\\s(?i:holmes)\\w+x", "holme"))) {
            for (InnerLiteralSearch search : Arrays.asList(Re2.compile(utf8Slice(entry.getKey())).innerLiteralSearchForDiagnostics(), innerLiteralSearch(entry.getKey()))) {
                assertThat(search == null ? null : literal(search)).as(entry.getKey()).isEqualTo(VectorSupport.isAvailable() ? entry.getValue() : null);
            }
        }
        // A group that does not capture joins the top level too.
        assertThat(literal("\\s(?:[a-z]+@)[a-z]+\\s")).isEqualTo("@");
        assertThat(literal("(\\w+)(@(\\w+))")).isEqualTo("@");
        // An alternation of literals is searched as a set. Every language's parser factors the
        // class out of this one's alternatives.
        Slice target = utf8Slice("([A-Za-z]awyer|[A-Za-z]inn)\\s");
        for (Map.Entry<String, ? extends CompiledPattern> compiled : List.of(
                Map.entry("RE2", Re2.compile(target)),
                Map.entry("Java", JavaRegexp.compile(target)),
                Map.entry("Trino", TrinoRegexp.compile(target).re2()),
                Map.entry("Rust", RustRegexp.compile(target)),
                Map.entry("Rust bytes", RustRegexp.compile(target, RUST_BYTES_OPTIONS)))) {
            InnerLiteralSearch search = compiled.getValue().innerLiteralSearchForDiagnostics();
            assertThat(search).as(compiled.getKey()).isNotNull();
            assertThat(literal(search)).as(compiled.getKey()).isEqualTo("awyer|inn");
        }
        // A folded ASCII letter consumes only its own two cases. Every language keeps the folded x
        // and xy as folded literals, since the simplifier rebuilds [Xx] into one, so neither they
        // nor the digit run can consume the space that starts the literal, and every language
        // searches it.
        Slice foldedTarget = utf8Slice("(?i:xy)?\\d+ = value");
        for (Map.Entry<String, ? extends CompiledPattern> compiled : List.of(
                Map.entry("RE2", Re2.compile(foldedTarget)),
                Map.entry("Java", JavaRegexp.compile(foldedTarget)),
                Map.entry("Trino", TrinoRegexp.compile(foldedTarget).re2()),
                Map.entry("Rust", RustRegexp.compile(foldedTarget)),
                Map.entry("Rust bytes", RustRegexp.compile(foldedTarget, RUST_BYTES_OPTIONS)))) {
            InnerLiteralSearch search = compiled.getValue().innerLiteralSearchForDiagnostics();
            assertThat(search).as(compiled.getKey()).isNotNull();
            assertThat(literal(search)).as(compiled.getKey()).isEqualTo(" = value");
        }
        // The orbit of k holds the Kelvin sign, so RE2 and Rust build [Kk\u212A] where Trino keeps
        // a folded k, and the prefix has no fixed length. Trino's folded k still consumes only k
        // and K among ASCII bytes, so it cannot hold the literal.
        InnerLiteralSearch kelvinSearch = TrinoRegexp.compile(utf8Slice("(?i:k)ey = value")).re2().innerLiteralSearchForDiagnostics();
        assertThat(kelvinSearch).isNotNull();
        assertThat(literal(kelvinSearch)).isEqualTo("ey = value");
        // Rust's Unicode \w has no fixed length in bytes, so the fixed-length proof needs RE2's.
        InnerLiteralSearch wordSearch = Re2.compile(utf8Slice("\\w(?:foo|barbaz)\\s")).innerLiteralSearchForDiagnostics();
        assertThat(wordSearch).isNotNull();
        assertThat(literal(wordSearch)).isEqualTo("foo|barbaz");
        assertThat(literal("\\s[a-z]{0,12}(?:ing|ment)\\s")).isEqualTo("ing|ment");
        // A set that cannot be searched leaves a later literal to decide.
        assertThat(literal("[a-z](?:ab|cd)@x")).isEqualTo("@x");
        assertThat(literal("(?:\\w|--)+[?!.][\"']@x")).isEqualTo("@x");
        // A rare pair of small classes is searched as the set of its two-byte members. A quote
        // ends every member, and the prefix holds one only as its one-byte opening quote, so no
        // earlier match spans a later candidate.
        assertThat(literal("[\"'][^\"']{0,30}[?!.][\"']")).isEqualTo("!\"|!'|.\"|.'|?\"|?'");
        // A pair with a side of more than three bytes, or one common in English text, is not.
        assertThat(literal("[ \\t][!#%&][\"']@x")).isEqualTo("@x");
        assertThat(literal("\\s[ae][st]@x")).isEqualTo("@x");
        // The bytes that can end the prefix gate the byte before each hit, as the digits that end
        // a date's day or month do, and a single-byte class after a literal of one length gates
        // the byte after it. A gate that holds the literal's own byte at that edge, as the letters
        // before ing and shing do, is dropped: it borders nearly every hit.
        for (Map.Entry<String, List<String>> entry : List.of(
                Map.entry("\\s[a-zA-Z]{0,12}ing\\s", List.of("", "\t\n\f\r ")),
                Map.entry("\\w+\\s+Holmes\\s+\\w+", List.of("\t\n\f\r ", "\t\n\f\r ")),
                Map.entry("[\\w.+-]+@[\\w.-]+\\.[\\w.-]+", List.of(
                        "+-.0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz",
                        "-.0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz")),
                Map.entry("[a-z]shing[a-z]", List.of("", "")),
                Map.entry("\\w+\\s+Holmes", List.of("\t\n\f\r ", "")),
                Map.entry(DATE, List.of("0123456789", "0123456789")))) {
            assertThat(Re2.compile(utf8Slice(entry.getKey())).innerLiteralSearchForDiagnostics().byteGatesForDiagnostics()).as(entry.getKey()).isEqualTo(entry.getValue());
        }
        // The bytes further before the literal gate a hit too, as far as the reverse prefix
        // program limits them, with assertions read as empty: two bytes before Holmes lies
        // whitespace or a word byte, two to four bytes before the @ of `\s[a-z]{3,}@x` lie two
        // letters and then whitespace or a letter, and two before the @ of `\b[a-z]{2}\b@x`, past
        // the boundary, a letter.
        String letters = "abcdefghijklmnopqrstuvwxyz";
        for (Map.Entry<String, List<String>> entry : List.of(
                Map.entry("\\w+\\s+Holmes", List.of("\t\n\f\r 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_" + letters)),
                Map.entry("\\s[a-z]{3,}@x", List.of(letters, letters, "\t\n\f\r " + letters)),
                Map.entry("\\b[a-z]{2}\\b@x", List.of(letters)),
                Map.entry("\\b\\w+nn\\b", List.<String>of()))) {
            assertThat(Re2.compile(utf8Slice(entry.getKey())).innerLiteralSearchForDiagnostics().deeperPrefixGatesForDiagnostics()).as(entry.getKey()).isEqualTo(entry.getValue());
        }
        // A word boundary after a literal that ends in a word byte gates the byte after it to
        // the ASCII bytes that are not word bytes, and every byte past ASCII, which may begin or
        // continue a word character; the window's end passes, and the context decides.
        String notWordBytes = IntStream.range(0, 0x100)
                .filter(value -> value >= 0x80 || !(Character.isLetterOrDigit(value) || value == '_'))
                .mapToObj(value -> String.valueOf((char) value))
                .collect(joining());
        assertThat(Re2.compile(utf8Slice("\\b\\w+nn\\b")).innerLiteralSearchForDiagnostics().byteGatesForDiagnostics()).isEqualTo(List.of("", notWordBytes));
        // Rust's start, end, and half boundaries judge one side alone, so they give no gate: a
        // space after the @ of `[a-z]+@\b{end-half}` ends a match.
        for (Language language : List.of(Language.RUST, Language.RUST_BYTES, Language.RUST_BYTES_ASCII)) {
            CompiledPattern halfBoundary = language.compiledPattern("[a-z]+@\\b{end-half}");
            assertThat(halfBoundary.innerLiteralSearchForDiagnostics().byteGatesForDiagnostics()).as(language.name()).isEqualTo(List.of(letters, ""));
            assertThat(halfBoundary.count(utf8Slice(".".repeat(200) + "a@ x"))).as(language.name()).isEqualTo(1);
        }
        // The gate before the literal comes from the bytes that can end the prefix, so a prefix
        // that is not one ASCII class gates too: Rust's Unicode \w ends a character of more than
        // one byte with a continuation byte, shown here as its Latin-1 char.
        String continuationBytes = IntStream.rangeClosed(0x80, 0xBF).mapToObj(value -> String.valueOf((char) value)).collect(joining());
        assertThat(RustRegexp.compile(utf8Slice("[\\w.+-]+@[\\w.-]+\\.[\\w.-]+")).innerLiteralSearchForDiagnostics().byteGatesForDiagnostics())
                .isEqualTo(List.of("+-.0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz" + continuationBytes, ""));
        // A prefix that is one class repeated, after leading assertions, also matches the shorter
        // run that ends at a later occurrence inside an earlier match, so the reverse search from
        // that occurrence finds the earlier match's start. The assertions are judged at that same
        // start, so this holds for every language's word characters and boundaries.
        for (Language language : Language.values()) {
            for (Map.Entry<String, String> entry : List.of(
                    Map.entry("\\b\\w+nn\\b", "nn"),
                    Map.entry(".{0,2}(Tom|Sawyer|Huckleberry|Finn)", "Tom|Sawyer|Huckleberry|Finn"),
                    Map.entry("\\B\\w+nn\\b", "nn"))) {
                InnerLiteralSearch search = language.compiledPattern(entry.getKey()).innerLiteralSearchForDiagnostics();
                assertThat(search).as("%s %s", language, entry.getKey()).isNotNull();
                assertThat(literal(search)).as("%s %s", language, entry.getKey()).isEqualTo(entry.getValue());
            }
        }
        // A pattern of the dot-star literal matcher's shape, such as `.*literal.*`, is left to that
        // matcher, and no inner literal search is built for it. Java's dot and the Unicode dot of
        // Rust's bytes API give no such shape, and the pattern takes the search.
        Slice dotStar = utf8Slice(".*coolfunctionname.*");
        for (Map.Entry<String, ? extends CompiledPattern> compiled : List.of(
                Map.entry("RE2", Re2.compile(dotStar)),
                Map.entry("RE2 Latin-1", Re2.compile(dotStar, Re2.Options.latin1())),
                Map.entry("Trino", TrinoRegexp.compile(dotStar).re2()),
                Map.entry("Rust", RustRegexp.compile(dotStar)))) {
            assertThat(compiled.getValue().createDotStarLiteralSpanMatcher()).as(compiled.getKey()).isNotNull();
            assertThat(compiled.getValue().innerLiteralSearchForDiagnostics()).as(compiled.getKey()).isNull();
        }
        for (Map.Entry<String, ? extends CompiledPattern> compiled : List.of(
                Map.entry("Java", JavaRegexp.compile(dotStar)),
                Map.entry("Rust bytes", RustRegexp.compile(dotStar, RUST_BYTES_OPTIONS)))) {
            assertThat(compiled.getValue().createDotStarLiteralSpanMatcher()).as(compiled.getKey()).isNull();
            assertThat(literal(compiled.getValue().innerLiteralSearchForDiagnostics())).as(compiled.getKey()).isEqualTo("coolfunctionname");
        }
        // Rust's multi-line caret is a leading assertion the DFA runs, so a run after it is a class
        // run prefix too.
        assertThat(literal("(?m)^.{0,3}Finn")).isEqualTo("Finn");
        assertThat(literal("[xz]+zx")).isEqualTo("zx");
        assertThat(literal("[a-z]+ing")).isEqualTo("ing");
        assertThat(literal("[a-z]{0,3}xy")).isEqualTo("xy");
        assertThat(literal("[a-z]{2,5}ing")).isEqualTo("ing");
        // A repeated body whose minimum is at most one matches every count in between.
        assertThat(literal("(?:[a-z][a-z]?){2}(?:foo|bar)")).isEqualTo("foo|bar");
        assertThat(literal("\\w+(?:foo|bar)\\s")).isEqualTo("foo|bar");
        assertThat(literal("[0-9]+(?:foo|bar)\\s")).isEqualTo("foo|bar");
        assertThat(literal("\\w+[?!.][\"']@x")).isEqualTo("!\"|!'|.\"|.'|?\"|?'");
        // A run with a minimum of at least two characters needs every character of the class to
        // be one byte: a later start inside a run of multibyte characters need not fall on a
        // character boundary of the earlier match, as in malformed input. RE2's \w and a Latin-1
        // or Rust ASCII bytes dot qualify, and a UTF-8 dot or Rust's Unicode \w does not. \w+\w+
        // simplifies to \w\w+, a run with a minimum of two.
        assertThat(literal(Re2.compile(utf8Slice("\\w{2,5}nn")).innerLiteralSearchForDiagnostics())).isEqualTo("nn");
        assertThat(literal(Re2.compile(utf8Slice("\\w+\\w+Holmes")).innerLiteralSearchForDiagnostics())).isEqualTo("Holmes");
        assertThat(literal(Re2.compile(utf8Slice(".{2,4}(?:Tom|Sawyer)"), Re2.Options.latin1()).innerLiteralSearchForDiagnostics())).isEqualTo("Tom|Sawyer");
        assertThat(literal(Language.RUST_BYTES_ASCII.compiledPattern(".{2,4}(?:Tom|Sawyer)").innerLiteralSearchForDiagnostics())).isEqualTo("Tom|Sawyer");
        assertThat(Re2.compile(utf8Slice(".{2,4}(?:Tom|Sawyer)")).innerLiteralSearchForDiagnostics()).isNull();

        for (String pattern : List.of(
                // No proof rules out an earlier match spanning a later occurrence: in ac@d@, the
                // match ac@d@ spans the occurrence that c@ would confirm first.
                "(?:a[^@]*@[^@]*d|c)@",
                "(?:a@.*b|c)@",
                // The crate's issue #1354: in xzbacbbac, ..acbb|b matches [1, 7) and [2, 3) but
                // not [1, 3), so the candidate at the first a is not the leftmost match. Its
                // literal is one common byte; the second form reaches the proofs.
                "(?:..acbb|b)a(?:c|d)",
                "(?:..@cbb|b)@(?:c|d)",
                // A class run proves nothing with an assertion after it, which a shorter run
                // ending at a later occurrence need not satisfy, with two classes, where the
                // shorter run may end inside the first, or with an alternation.
                "\\w+\\Bnn",
                ".{0,2}\\b(?:Tom|Sawyer)",
                "[a-z]\\w*nn",
                "(?:\\w|--)+nn",
                // Nor does a repeated body of two or more characters, which leaves gaps between its
                // counts: from the first hit in xyzfoozzbar, the search would report yzfoo, where
                // the engine matches the whole of it.
                "(?:[a-z]{2})+(?:foo|bar)",
                // Rust's Unicode \w and dot are multibyte, so a run with a minimum of at least two
                // is declined.
                "\\w{2,5}nn",
                "\\w+\\w+Holmes",
                ".{2,4}(?:Tom|Sawyer)",
                // As in the crate, a set never uses the proof that the prefix cannot consume the
                // literal, since one member can cross the prefix boundary into another.
                "[0-9]+[-.][0-9]+(?:foo|bar)\\s",
                // The separator must exclude every member's bytes.
                "\\s[a-z]{0,12}(?:ing|a b)\\s",
                // The bytes C3 A9 encode é, so neither byte class is disjoint from [a-zé].
                "(?-u:[\\xC3\\xC4])+[a-z\u00E9]*(?-u:[\\xA9\\xAA])ab",
                // A group inside a repetition is not part of the top-level concatenation.
                "\\w+(?:@\\w+)+",
                // Nor are the copies of a counted repetition, which the crate keeps as one element.
                "(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9])\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9])",
                // The proofs count both cases of a folded letter: in QHOho, the match QHOho spans
                // the occurrence HO, though the prefix cannot consume the lowered literal ho.
                "(?:QHO)?(?i:ho)",
                // Anchored, prefix-accelerated, common single-byte, and overlong literals.
                "^\\w+@\\w+",
                "Holmes\\w+@",
                "\\w+ \\w+",
                "[a-z]" + "xy".repeat(32) + "z",
                // Sets with a member under three bytes, more than eight members, or a folded,
                // non-ASCII, or non-literal member.
                "[a-z](?:ab|cde)\\s",
                "[a-z](?:aaa|bbb|ccc|ddd|eee|fff|ggg|hhh|iii)\\s",
                "[a-z](?i:foo|bar)\\s",
                "[a-z](?:foo|b\u00E4r)\\s",
                "[a-z](?:foo|b[ae]r)\\s",
                // A class pair whose prefix neither opens with a single byte nor is a class run.
                "(?:\\w|--)+[?!.][\"']")) {
            assertThat(innerLiteralSearch(pattern)).as(pattern).isNull();
        }
        // In Trino, a folded rune consumes the literal's bytes it folds to, so its repetition can
        // hold the literal and an earlier match could span a later one: k consumes both cases of
        // k, the Kelvin sign consumes k, and @ consumes itself.
        for (String pattern : List.of(
                "(?i:k)+Kk",
                "(?i:\u212A)+kk",
                "(?i:@)+@@")) {
            assertThat(TrinoRegexp.compile(utf8Slice(pattern)).re2().innerLiteralSearchForDiagnostics()).as(pattern).isNull();
        }
    }

    @Test
    public void testLiteralSetFilterTakesOneBitGroups()
    {
        // The set's filter was measured with groups of one bit. Wider groups would check @, A, B,
        // and D at the first byte under the mask 0x07; groups of one bit check the second, third,
        // and fourth bytes.
        InnerLiteralSearch search = innerLiteralSearch("[0-9]+(?:@eee|Aeee|@ttt|Bttt|@   |D   )");
        assertThat(literal(search)).isEqualTo("@eee|Aeee|@ttt|Bttt|@   |D   ");
        StartLiteralFilter filter = search.literalSetFilterForDiagnostics();
        assertThat(List.of(filter.firstOffset(), filter.secondOffset(), filter.thirdOffset())).containsExactly(1, 2, 3);
    }

    @Test
    public void testPredictsLiteralOfFixedWidthPatterns()
    {
        // A fixed-width pattern has no assertion, so its expression predicts, before any program
        // compiles, the length of the literal the compiled search takes, or zero when it takes
        // none.
        for (String pattern : List.of(
                "[a-z]shing",
                "[a-z]{3}shing[0-9]",
                "[0-9a-fA-F]{8}shing[0-9a-fA-F]{12}",
                "[a-z]{8}-[0-9]{4}",
                "\\d{4}-\\d{2}-\\d{2}",
                // A case-insensitive literal counts its whole length, not its exact head's. One
                // without an exact head is searched only with the Vector API, and gives way to a
                // later literal searched from shorter windows; the prediction agrees either way.
                "[a-z]{3}#(?i:noqa)",
                "[a-z]{3}(?i:noqa)[0-9]",
                "[a-z](?i: noqa)@x",
                "[a-z]{3}(?i:noqa)[0-9]#[0-9]",
                // A rare class pair counts its two bytes.
                "[a-z][?!.][\"']x",
                // A common byte, a prefix the program accelerates, a folded run it accelerates
                // behind the Kelvin sign's wider lead, one of three bytes led by a rare letter, and
                // no literal.
                "[a-z] [a-z]",
                "ab[0-9]shing",
                "(?i)k-9 unit[0-9]",
                "(?i)kwat[0-9]",
                "[a-z]{4}")) {
            ParseResult parsed = RegexpParser.parse(utf8Slice(pattern), Regexp.LIKE_PERL);
            int predicted = InnerLiteralSearch.predictMinimumLiteralLength(Simplifier.simplify(parsed.regexp(), parsed.caseFolding()), parsed.caseFolding(), ByteFrequencies.uniform());
            InnerLiteralSearch search = Re2.compile(utf8Slice(pattern)).innerLiteralSearchForDiagnostics();
            assertThat(predicted).as(pattern).isEqualTo(search == null ? 0 : search.minimumLiteralLength());
        }
        // The prediction takes the pattern's distribution: English refuses the run gyz, led by g,
        // and JSON, which rates g at 0.8%, keeps it.
        ParseResult parsed = RegexpParser.parse(utf8Slice("(?i)kgyz[0-9]"), Regexp.LIKE_PERL);
        int predicted = InnerLiteralSearch.predictMinimumLiteralLength(Simplifier.simplify(parsed.regexp(), parsed.caseFolding()), parsed.caseFolding(), ByteFrequencies.json());
        InnerLiteralSearch search = Re2.compile(utf8Slice("(?i)kgyz[0-9]"), Re2.Options.defaults().setByteFrequencies(ByteFrequencies.json())).innerLiteralSearchForDiagnostics();
        assertThat(predicted).isEqualTo(search == null ? 0 : search.minimumLiteralLength());
    }

    @Test
    public void testRetainedSearchUsesReverseBudget()
    {
        Slice pattern = utf8Slice("\\w+\\s+Holmes");
        int flags = JavaRegexp.Options.defaults().parseFlags();
        ParseResult parsed = JavaRegexpParser.parse(pattern, flags);
        long maxMemory = Re2.Options.DEFAULT_MAX_MEMORY;
        Prog control = Compiler.compile(parsed.regexp(), false, maxMemory - maxMemory / 3);
        Re2 compiled = Re2.compileParsed(pattern, parsed, flags, maxMemory);
        InnerLiteralSearch search = compiled.innerLiteralSearchForDiagnostics();
        assertThat(search).isNotNull();
        // The forward DFA gives up only what the required bytes reserve.
        assertThat(compiled.forwardProgramForDiagnostics().dfaMemory()).isEqualTo(control.dfaMemory() - compiled.requiredBytesRetainedSizeForDiagnostics());

        // The full reverse program takes what the inner literal search leaves of the reverse third.
        assertThat(compiled.reverseProgramSize()).isPositive();
        Prog reverse = compiled.reverseProgramIfComputedForDiagnostics();
        assertThat(search.estimatedRetainedSize() + Compiler.estimatedProgramMemory(reverse) + reverse.dfaMemory()).isLessThanOrEqualTo(maxMemory / 3);

        // At small budgets the search leaves the full reverse DFA enough to initialize, as it does
        // without the search, and a search that is kept can run its reverse prefix DFA.
        for (SmallBudget budget : List.of(
                new SmallBudget("[a-z]+@\\p{L}+\\p{N}", 1536L << 10, "ab@cd1", true),
                new SmallBudget("[a-z]+@\\p{L}+\\p{N}", 1424L << 10, "ab@cd1", true),
                new SmallBudget("[a-z]+-[0-9]+", 24L << 10, "ab-12", false))) {
            String description = budget.pattern() + " " + budget.maxMemory();
            Re2 small = Re2.compile(utf8Slice(budget.pattern()), Re2.Options.defaults().setMaxMemory(budget.maxMemory()));
            assertThat(small.reverseProgramSize()).as(description).isPositive();
            assertThat(small.reverseProgramIfComputedForDiagnostics().getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH)).as(description).isNotNull();
            InnerLiteralSearch smallSearch = small.innerLiteralSearchForDiagnostics();
            assertThat(smallSearch != null).as(description).isEqualTo(budget.keepsSearch());
            if (budget.keepsSearch()) {
                Slice text = utf8Slice(".".repeat(100) + budget.match());
                assertThat(smallSearch.search(text, 0, text.length(), 0, text.length())).as(description).isEqualTo((100L << 32) | text.length());
            }
        }

        // A budget with no room for the gates further before the literal keeps the search with
        // the one-byte gates alone, which gives the same answers.
        Re2.Options tight = Re2.Options.defaults().setMaxMemory(64L << 10);
        Re2 holmes = Re2.compile(utf8Slice("\\w+\\s+Holmes"), tight);
        InnerLiteralSearch holmesSearch = holmes.innerLiteralSearchForDiagnostics();
        assertThat(holmesSearch.byteGatesForDiagnostics()).isEqualTo(List.of("\t\n\f\r ", ""));
        assertThat(holmesSearch.deeperPrefixGatesForDiagnostics()).isEmpty();
        Engine actual = regexpEngine(holmes);
        Engine expected = regexpEngine(Re2.compile(utf8Slice("(?:\\w+\\s+Holmes)|\\b\\B"), tight));
        for (Slice text : texts()) {
            String description = text.toStringUtf8();
            assertThat(actual.spans(text)).as(description).isEqualTo(expected.spans(text));
            assertThat(actual.count(text)).as(description).isEqualTo(expected.count(text));
            assertThat(actual.find(text)).as(description).isEqualTo(expected.find(text));
        }
    }

    @Test
    public void testAgreesWithOrdinaryEngine()
    {
        List<Slice> texts = texts();
        for (String pattern : PATTERNS) {
            // An alternative that never matches leaves the top-level concatenation, and with it
            // the inner literal, out of the expected engine.
            String ordinary = "(?:" + pattern + ")|\\b\\B";
            assertThat(innerLiteralSearch(ordinary)).as(ordinary).isNull();
            for (Language language : Language.values()) {
                Engine actual = language.compile(pattern);
                Engine expected = language.compile(ordinary);
                for (Slice text : texts) {
                    String description = "%s %s %s".formatted(language, pattern, text.toStringUtf8());
                    assertThat(actual.spans(text)).as(description).isEqualTo(expected.spans(text));
                    assertThat(actual.count(text)).as(description).isEqualTo(expected.count(text));
                    assertThat(actual.find(text)).as(description).isEqualTo(expected.find(text));
                }
            }
        }
    }

    @Test
    public void testHighBytePrefixGateAgreesWithOrdinaryEngine()
    {
        // Any byte from 0x80 to 0xFF can end this prefix, and hits after any high byte agree with
        // the ordinary engine. The filler reaches the single literal's minimum, so every input
        // runs the search, and keeps it from falling back near its start.
        String pattern = "(?-u:[\\x80-\\xFF])@xy";
        InnerLiteralSearch search = RustRegexp.compile(utf8Slice(pattern), RUST_BYTES_OPTIONS).innerLiteralSearchForDiagnostics();
        String highBytes = IntStream.rangeClosed(0x80, 0xFF).mapToObj(value -> String.valueOf((char) value)).collect(joining());
        assertThat(search.byteGatesForDiagnostics()).isEqualTo(List.of(highBytes, ""));

        Engine actual = Language.RUST_BYTES.compile(pattern);
        Engine expected = Language.RUST_BYTES.compile("(?:" + pattern + ")|\\b\\B");
        byte[] filler = "-".repeat(InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES).getBytes(UTF_8);
        for (byte[] hits : List.of(
                new byte[] {(byte) 0xFF, '@', 'x', 'y'},
                new byte[] {(byte) 0xC3, '@', 'x', 'y'},
                new byte[] {'a', '@', 'x', 'y', ' ', (byte) 0xC3, '@', 'x', 'y', (byte) 0xFF, '@', 'x', 'y'},
                new byte[] {'a', '@', 'x', 'y', (byte) 0x7F, '@', 'x', 'y', (byte) 0xBF, '@', 'x', 'y'})) {
            byte[] bytes = Arrays.copyOf(filler, filler.length + hits.length);
            System.arraycopy(hits, 0, bytes, filler.length, hits.length);
            Slice text = Slices.wrappedBuffer(bytes);
            String description = Arrays.toString(hits);
            assertThat(search.search(text, 0, text.length(), 0, text.length())).as(description).isNotEqualTo(InnerLiteralSearch.FALLBACK);
            assertThat(actual.spans(text)).as(description).isEqualTo(expected.spans(text));
            assertThat(actual.count(text)).as(description).isEqualTo(expected.count(text));
            assertThat(actual.find(text)).as(description).isEqualTo(expected.find(text));
        }
    }

    @Test
    public void testTransparentStartLiteralAgreesWithOrdinaryEngine()
    {
        // Each language's engines agree on a literal behind a leading assertion, which feeds the
        // candidate cursor, or the group-zero NFA's skip where the DFA cannot run the assertion.
        // Every language judges its own line terminators and word characters at each candidate.
        List<String> patterns = List.of(
                "(?m)^Sherlock Holmes|Sherlock Holmes$",
                "(?m)^ab|ab$",
                "(?m)^ab",
                "\\bab",
                "\\Bab",
                "(?m)^Sherlock \\w+",
                "\\bHolmes[a-z]*",
                "(?m)^" + "a".repeat(40) + "ba|" + "a".repeat(40) + "ba$");
        List<Slice> texts = transparentStartLiteralTexts();
        for (Language language : Language.values()) {
            List<String> languagePatterns = new ArrayList<>(patterns);
            switch (language) {
                case JAVA -> languagePatterns.add("(?dm)^ab|ab$");
                case RUST, RUST_BYTES, RUST_BYTES_ASCII -> languagePatterns.add("(?mR)^ab|ab$");
                default -> {}
            }
            for (String pattern : languagePatterns) {
                // An alternative that never matches, but that the literal walk reads as empty, leaves
                // the literal out of the expected engine.
                String ordinary = "(?:" + pattern + ")|\\b\\B";
                assertThat(language.compiledPattern(pattern).forwardProgramForDiagnostics().transparentStartLiteral()).as("%s %s", language, pattern).isNotNull();
                assertThat(language.compiledPattern(ordinary).forwardProgramForDiagnostics().transparentStartLiteral()).as("%s %s", language, ordinary).isNull();
                Engine actual = language.compile(pattern);
                Engine expected = language.compile(ordinary);
                for (Slice text : texts) {
                    String description = "%s %s %s".formatted(language, pattern, text.toStringUtf8());
                    assertThat(actual.spans(text)).as(description).isEqualTo(expected.spans(text));
                    assertThat(actual.count(text)).as(description).isEqualTo(expected.count(text));
                    assertThat(actual.find(text)).as(description).isEqualTo(expected.find(text));
                }
            }
        }
    }

    @Test
    public void testAgreesWithOrdinaryEngineInWindows()
    {
        // A ranged find keeps the whole Slice as assertion context, so its closing \b sees the
        // byte past the window, while a matcher region is its own context. The Slice starts inside
        // its backing array, and the literal lies past the near-start fallback, in a window short
        // enough for the word-at-a-time scan and in one long enough for the vector scan, both long
        // enough for the search.
        String regex = "\\b\\w+\\s+Holmes\\s+\\w+\\b";
        Re2 pattern = Re2.compile(utf8Slice(regex));
        InnerLiteralSearch search = pattern.innerLiteralSearchForDiagnostics();
        Re2 ordinary = Re2.compile(utf8Slice("(?:" + regex + ")|\\b\\B"));
        for (String filler : List.of("-".repeat(InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES), "-".repeat(1100))) {
            String window = filler + "Sherlock Holmes sa";
            int start = 2;
            int end = start + window.length();
            long span = ((long) (start + filler.length()) << 32) | end;
            for (String after : List.of("id", " x")) {
                String description = filler.length() + " " + after;
                byte[] backing = ("abcx " + window + after).getBytes(UTF_8);
                Slice text = Slices.wrappedBuffer(backing, 3, backing.length - 3);
                boolean wordAfter = after.equals("id");
                assertThat(search.search(text, 0, text.length(), start, end)).as(description).isEqualTo(wordAfter ? Dfa.SEARCH_NO_MATCH : span);
                assertThat(search.search(text, start, end, start, end)).as(description).isEqualTo(span);

                int[] actual = new int[2];
                int[] expected = new int[2];
                assertThat(pattern.findInto(text, start, end, actual)).as(description).isEqualTo(!wordAfter);
                assertThat(ordinary.findInto(text, start, end, expected)).as(description).isEqualTo(!wordAfter);
                assertThat(actual).as(description).isEqualTo(expected);

                RegexpMatcher matcher = pattern.matcher(text).reset(text, start, end);
                RegexpMatcher expectedMatcher = ordinary.matcher(text).reset(text, start, end);
                assertThat(matcher.find()).as(description).isTrue();
                assertThat(expectedMatcher.find()).as(description).isTrue();
                assertThat(List.of(matcher.start(), matcher.end())).as(description).isEqualTo(List.of(expectedMatcher.start(), expectedMatcher.end()));
            }
        }
    }

    @Test
    public void testByteGatesAgreeWithOrdinaryEngineInWindows()
    {
        // The gates judge a hit against its window, not the Slice around it, in every language: a
        // byte the prefix must consume two bytes before Holmes fails before the window start, and a
        // word boundary after nn passes at the window end, where a ranged find's context, the
        // whole Slice, sees the word byte past it, and a matcher region, its own context, does not.
        // Nor does a count of the window as a Slice of its own, whose nn ends exactly at its end,
        // with the rest of the array past it.
        String filler = "-".repeat(InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES);
        byte[] backing = ("abcxa Holmes " + filler + " Penn" + "id").getBytes(UTF_8);
        Slice text = Slices.wrappedBuffer(backing, 3, backing.length - 3);
        for (Language language : Language.values()) {
            for (String regex : List.of("\\w+\\s+Holmes", "\\b\\w+nn\\b")) {
                CompiledPattern pattern = language.compiledPattern(regex);
                CompiledPattern ordinary = language.compiledPattern("(?:" + regex + ")|\\b\\B");
                for (int start : new int[] {0, 1, 2, 3}) {
                    for (int end : new int[] {text.length() - 2, text.length()}) {
                        String description = "%s %s [%s, %s)".formatted(language, regex, start, end);
                        int[] actual = new int[2];
                        int[] expected = new int[2];
                        assertThat(pattern.findInto(text, start, end, actual)).as(description).isEqualTo(ordinary.findInto(text, start, end, expected));
                        assertThat(actual).as(description).isEqualTo(expected);
                        RegexpMatcher matcher = pattern.matcher(text).reset(text, start, end);
                        RegexpMatcher expectedMatcher = ordinary.matcher(text).reset(text, start, end);
                        while (expectedMatcher.find()) {
                            assertThat(matcher.find()).as(description).isTrue();
                            assertThat(List.of(matcher.start(), matcher.end())).as(description).isEqualTo(List.of(expectedMatcher.start(), expectedMatcher.end()));
                        }
                        assertThat(matcher.find()).as(description).isFalse();
                        Slice region = text.slice(start, end - start);
                        assertThat(pattern.count(region)).as(description).isEqualTo(ordinary.count(region));
                    }
                }
            }
        }
    }

    @Test
    public void testCountsToLiteralEndWithReverseDfaAlone()
    {
        // Every match of these ends at its literal's end, so a count confirms each hit with the
        // reverse DFA alone and never starts the forward DFA, under each proof: the separator at
        // the prefix's end, where the byte before each Holmes is ASCII; the class run before a
        // word boundary, where the byte after each nn is ASCII but not a word byte; a literal the
        // prefix cannot consume, also behind a case-insensitive letter, which consumes only its two
        // cases; a prefix of fixed length; and a class pair. Each of the count's searches ends
        // where find's ends, as one that resumed inside the first Holmes of a Holmes Holmes would
        // not, counting olmes Holmes too.
        String gap = "-".repeat(InnerLiteralSearch.MINIMUM_SKIPPED_BYTES);
        for (Map.Entry<String, String> entry : List.of(
                Map.entry("\\w+\\s+Holmes", gap + "a Holmes" + gap + "bb \t Holmes Holmes" + gap),
                Map.entry("\\b\\w+nn\\b", gap + "Penn " + gap + "Finn," + gap),
                Map.entry("\\w+@", gap + "ab@" + gap + "c@" + gap),
                Map.entry("(?i:x)?\\d+ = value", gap + "X1 = value" + gap + "x22 = value" + gap),
                Map.entry("[a-z]{2}ab", gap + "xyab" + gap + "zzab" + gap),
                Map.entry("[\"'][^\"']{0,30}[?!.][\"']", gap + "\"Hi!\"" + gap + "'Yes.'" + gap))) {
            String regex = entry.getKey();
            Slice text = utf8Slice(entry.getValue());
            for (Language language : Language.values()) {
                String description = "%s %s".formatted(language, regex);
                CompiledPattern pattern = language.compiledPattern(regex);
                assertThat(pattern.count(text)).as(description).isEqualTo(2);
                assertThat(reversePrefixStateCount(pattern)).as(description).isPositive();
                assertThat(forwardStateCount(pattern)).as(description).isZero();
                assertCountEndsWhereFindEnds(pattern.innerLiteralSearchForDiagnostics(), text, description);
            }
        }

        // Where the bytes beside a hit do not settle where its match ends, or no proof bounds the
        // match end, the count runs the full confirmation and starts the forward DFA: a byte past
        // ASCII before Holmes, which may end a separator character of more than one byte; one
        // after nn, which may begin a word character; and a separator at the prefix's start, from
        // which `\s+\w+Holmes` matches past the first Holmes. A filler of a single literal's
        // count minimum makes the count search, and puts each hit past the near-start fallback.
        String filler = "-".repeat(InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES);
        for (Map.Entry<String, String> entry : List.of(
                Map.entry("[A-Za-z]+[ \\x{A0}]+Holmes", "a\u00A0Holmes"),
                Map.entry("\\b\\w+nn\\b", "Penn\u00A0"),
                Map.entry("\\s+\\w+Holmes", " xHolmesHolmes"))) {
            String description = "%s on %s".formatted(entry.getKey(), entry.getValue());
            CompiledPattern pattern = Language.RE2.compiledPattern(entry.getKey());
            Slice line = utf8Slice(filler + entry.getValue());
            assertThat(pattern.count(line)).as(description).isEqualTo(1);
            assertThat(reversePrefixStateCount(pattern)).as(description).isPositive();
            assertThat(forwardStateCount(pattern)).as(description).isPositive();
            assertCountEndsWhereFindEnds(pattern.innerLiteralSearchForDiagnostics(), line, description);
        }

        // The proofs compare the prefix with exact literal bytes, so a case-insensitive literal
        // takes the full confirmation too, which ends the first match after the whole of #noqa,
        // not its exact head #, from where the second #noqa has no start.
        CompiledPattern noqa = Language.RE2.compiledPattern("[a-z]+#(?i:noqa)");
        assertThat(noqa.count(utf8Slice(filler + "a#noqa#noqa"))).isEqualTo(1);
        assertThat(forwardStateCount(noqa)).isPositive();
    }

    // Steps through a count of `text` as the inner literal count does, and requires each of its
    // searches to end where a find's search from the same position ends, past that position and
    // within the text, or to give the same answer when that finds no match or falls back.
    private static void assertCountEndsWhereFindEnds(InnerLiteralSearch search, Slice text, String description)
    {
        int position = 0;
        while (true) {
            long countSearch = search.searchForCount(text, 0, text.length(), position, text.length());
            long findSearch = search.search(text, 0, text.length(), position, text.length());
            String step = "%s from %s".formatted(description, position);
            if (findSearch < 0) {
                assertThat(countSearch).as(step).isEqualTo(findSearch);
                return;
            }
            int end = (int) findSearch;
            assertThat((int) countSearch).as(step).isEqualTo(end);
            assertThat(end).as(step).isGreaterThan(position).isLessThanOrEqualTo(text.length());
            position = end;
        }
    }

    @Test
    public void testWarmCandidateConfirmsInOnePass()
    {
        // Once both DFAs hold a candidate's states, the single pass over them confirms it and
        // returns what the reverse and forward searches give one at a time.
        Re2 pattern = Re2.compile(utf8Slice("\\w+://[^\\s/]+"));
        InnerLiteralSearch search = pattern.innerLiteralSearchForDiagnostics();
        assertThat(search).isNotNull();
        Prog reversePrefixProgram = search.reversePrefixProgramForDiagnostics();
        Prog forwardProgram = search.forwardProgramForDiagnostics();
        String scheme = "https";
        String url = scheme + "://example.com";
        Slice text = utf8Slice(FILLER + " " + url + " ");
        int matchStart = FILLER.length() + 1;
        int literalStart = matchStart + scheme.length();
        long span = ((long) matchStart << 32) | (matchStart + url.length());
        // The first search adds the candidate's states to both DFAs.
        assertThat(search.search(text, 0, text.length(), 0, text.length())).isEqualTo(span);

        long reverse = Dfa.searchReverseCandidate(reversePrefixProgram, text, 0, text.length(), 0, literalStart, Integer.MAX_VALUE);
        long forward = Dfa.searchAnchoredWithStop(forwardProgram, text, 0, text.length(), (int) reverse, text.length());
        assertThat((reverse << 32) | forward).isEqualTo(span);
        long pair = Dfa.searchReverseThenAnchoredWithStop(reversePrefixProgram, forwardProgram, text, 0, text.length(), 0, literalStart, text.length(), Integer.MAX_VALUE);
        assertThat(pair).isEqualTo(span);
    }

    @Test
    public void testRequiredBytesCoexistWithInnerLiteralSearch()
    {
        // A window shorter than the search's minimum runs the ordinary engine where it holds the
        // literal, and the required bytes, which a pattern with an inner literal search keeps too,
        // reject one without it, so the forward DFA never starts.
        String regex = "(\\s*)((?:# [Nn][Oo][Qq][Aa])(?::\\s?([A-Z]+[0-9]+))?)";
        for (Language language : List.of(Language.RE2, Language.JAVA, Language.RUST, Language.RUST_BYTES)) {
            String description = language + " " + regex;
            CompiledPattern pattern = language.compiledPattern(regex);
            assertThat(pattern.innerLiteralSearchForDiagnostics()).as(description).isNotNull();
            assertThat(pattern.requiredBytesForDiagnostics()).as(description).isNotNull();
            Slice absent = utf8Slice("    return contact.support(value)");
            assertThat(pattern.find(absent)).as(description).isFalse();
            assertThat(pattern.matcher(absent).find()).as(description).isFalse();
            assertThat(pattern.matcher(absent, 0).find()).as(description).isFalse();
            assertThat(forwardStateCount(pattern)).as(description).isZero();
            // A comment with the head of the case-insensitive literal `# noqa` but not the rest
            // of it has no match either.
            Slice headOnly = utf8Slice("    x = 1  # type: ignore[no-any]");
            assertThat(pattern.matcher(headOnly).find()).as(description).isFalse();
            assertThat(forwardStateCount(pattern)).as(description).isZero();

            Slice present = utf8Slice("x = 1  # noqa: E501");
            assertThat(pattern.find(present)).as(description).isTrue();
            assertThat(forwardStateCount(pattern)).as(description).isPositive();
        }
    }

    @Test
    public void testSearchesWindowsFromMinimum()
    {
        // A search window shorter than the search's minimum, a literal set's 256 bytes or 1024
        // behind a class run, 1024 for a case-insensitive literal without an exact head, a longer
        // single literal's 96, or 64 for a one-byte literal or class pair, never runs the inner
        // literal search, except a gated one-byte search, which first rejects a window without a
        // gate-passing hit: a matcher's find and a full-Slice find run the ordinary engine there. A
        // whole-text count keeps the same minimum, except a literal set's not behind a class run,
        // 64 bytes.
        // A window at the minimum runs the search, the only route that starts its reverse prefix
        // DFA, or, where a literal ending a minimum window would still fall back near its start,
        // the shortest window that puts the literal past that fallback. Each window lies inside a
        // longer Slice, as a matcher region, and ends with the literal, inside any required bytes
        // check.
        List<MinimumWindow> windows = new ArrayList<>(List.of(
                new MinimumWindow(".{0,2}(Tom|Sawyer|Huckleberry|Finn)", "Finn", Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES, Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES),
                new MinimumWindow("\\s[a-z]{0,12}(?:ing|ment)\\s", " moment ", InnerLiteralSearch.MINIMUM_LITERAL_SET_SEARCH_BYTES, CompiledPattern.MINIMUM_LONG_SEARCH_BYTES),
                new MinimumWindow("[a-z](?:ing|ment)", "ament", InnerLiteralSearch.MINIMUM_LITERAL_SET_SEARCH_BYTES, CompiledPattern.MINIMUM_LONG_SEARCH_BYTES),
                new MinimumWindow("\\w+\\s+Holmes", "a Holmes", InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES, InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES),
                new MinimumWindow("\\s*(?i:# noqa)", "# NoQA", InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES, InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES),
                new MinimumWindow("\\w+@", "a@", CompiledPattern.MINIMUM_LONG_SEARCH_BYTES, CompiledPattern.MINIMUM_LONG_SEARCH_BYTES),
                new MinimumWindow("\\w+[?!.][\"']", "a?\"", CompiledPattern.MINIMUM_LONG_SEARCH_BYTES, CompiledPattern.MINIMUM_LONG_SEARCH_BYTES)));
        if (VectorSupport.isAvailable()) {
            windows.add(new MinimumWindow("\\s+(?i:noqa)", " NoQa", Prog.FUSED_PREFIX_VECTOR_MINIMUM_BYTES, Prog.FUSED_PREFIX_VECTOR_MINIMUM_BYTES));
            // A literal only the kernel searches gives way to a later one with a shorter minimum,
            // here `@x`. Without the Vector API, the common byte that starts the folded group
            // ends Java's and Rust's analysis, so they have no search there.
            windows.add(new MinimumWindow("[a-z](?i: noqa)@x", "a NoQa@x", InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES, InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES));
        }
        for (MinimumWindow entry : windows) {
            String regex = entry.regex();
            String literal = entry.literal();
            for (Language language : List.of(Language.RE2, Language.JAVA, Language.RUST, Language.RUST_BYTES)) {
                CompiledPattern ordinary = language.compiledPattern("(?:" + regex + ")|\\b\\B");
                InnerLiteralSearch search = language.compiledPattern(regex).innerLiteralSearchForDiagnostics();
                assertThat(search).as("%s %s", language, regex).isNotNull();
                int minimum = search.minimumSearchBytes();
                int countMinimum = search.minimumCountBytes();
                assertThat(minimum).as("%s %s", language, regex).isEqualTo(entry.minimum());
                assertThat(countMinimum).as("%s %s", language, regex).isEqualTo(entry.countMinimum());
                for (int length : IntStream.of(countMinimum - 1, Math.max(countMinimum, InnerLiteralSearch.MINIMUM_SKIPPED_BYTES + literal.length()), minimum - 1, Math.max(minimum, InnerLiteralSearch.MINIMUM_SKIPPED_BYTES + literal.length())).distinct().toArray()) {
                    String description = "%s %s %s".formatted(language, regex, length);
                    boolean searches = length >= minimum;
                    Slice text = utf8Slice("ab" + "-".repeat(length - literal.length()) + literal + "cd");
                    Slice window = text.slice(2, length);

                    CompiledPattern matcherPattern = language.compiledPattern(regex);
                    RegexpMatcher matcher = matcherPattern.matcher(text, 0).reset(text, 2, 2 + length);
                    RegexpMatcher expected = ordinary.matcher(text, 0).reset(text, 2, 2 + length);
                    assertThat(matcher.find()).as(description).isTrue();
                    assertThat(expected.find()).as(description).isTrue();
                    assertThat(List.of(matcher.start(), matcher.end())).as(description).isEqualTo(List.of(expected.start(), expected.end()));
                    assertThat(reversePrefixStateCount(matcherPattern) > 0).as(description).isEqualTo(searches);

                    CompiledPattern findPattern = language.compiledPattern(regex);
                    assertThat(findPattern.find(window)).as(description).isTrue();
                    assertThat(reversePrefixStateCount(findPattern) > 0).as(description).isEqualTo(searches);

                    CompiledPattern countPattern = language.compiledPattern(regex);
                    assertThat(countPattern.count(window)).as(description).isEqualTo(ordinary.count(window));
                    assertThat(reversePrefixStateCount(countPattern) > 0).as(description).isEqualTo(length >= countMinimum);
                }
            }
        }
    }

    private static long reversePrefixStateCount(CompiledPattern pattern)
    {
        Dfa.DfaInstance dfa = pattern.innerLiteralSearchForDiagnostics().reversePrefixProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH);
        return dfa == null ? 0 : dfa.cacheSnapshot().stateCount();
    }

    private static long forwardStateCount(CompiledPattern pattern)
    {
        return stateCount(pattern.forwardProgramForDiagnostics());
    }

    private static long stateCount(Prog program)
    {
        long states = 0;
        for (Dfa.DfaInstance.Kind kind : Dfa.DfaInstance.Kind.values()) {
            Dfa.DfaInstance dfa = program.cachedDfaIfPresent(kind);
            if (dfa != null) {
                states += dfa.cacheSnapshot().stateCount();
            }
        }
        return states;
    }

    @Test
    public void testFallsBackWhenLiteralIsNearStart()
    {
        // Every text is long enough for the search of a literal set.
        String filler = ".".repeat(Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES);
        // A search skips at most the bytes before its first candidate, as in a uuid whose hyphen
        // sits 8 bytes in, so a first candidate closer than the minimum falls back to the ordinary
        // engine. A hit whose neighboring bytes no match can hold is no candidate, so in
        // `--ab-12` the first candidate is the third hyphen: with 58 or 62 dots before `--ab-12`,
        // the third hyphen is 62 or 66 bytes in.
        assertNearStart(
                "[a-z]+-[0-9]+",
                List.of("abcdefgh-1234" + filler, ".".repeat(61) + "ab-12" + filler, ".".repeat(58) + "--ab-12" + filler),
                // A hit ruled out by its neighbors is no candidate, so the first hit that is one
                // decides, wherever it lies.
                List.of(".".repeat(62) + "ab-12" + filler, ".".repeat(62) + "--ab-12" + filler, filler + "ab-12", filler + "ab-....cd-12" + filler));
        // No date ends a day or month in a letter, so the slash of a/1 near the start of a line is
        // no candidate, and the date further on takes the search.
        assertNearStart(
                DATE,
                List.of("1/2/2024" + filler),
                List.of("a/1 " + ".".repeat(70) + "12/25/2024" + filler));
        // A longer literal is near at 63 bytes in, and not at 64 or 68, where the near check's read
        // ends inside it.
        assertNearStart(
                "\\w+\\s+Holmes",
                List.of(".".repeat(61) + "a Holmes" + filler),
                List.of(".".repeat(62) + "a Holmes" + filler, ".".repeat(66) + "a Holmes" + filler));
        // A set is near when its first gate-passing member starts fewer than the minimum bytes in.
        assertNearStart(
                "([A-Za-z]awyer|[A-Za-z]inn)\\s",
                List.of(".".repeat(62) + "xinn " + filler, ".".repeat(62) + "Sawyer " + filler),
                List.of(".".repeat(63) + "xinn " + filler, ".".repeat(63) + "Sawyer " + filler));
    }

    @Test
    public void testGateSkippedHitLeavesReverseBound()
    {
        // A hit whose neighboring bytes no match can hold runs no reverse search, so it does not
        // bound the next one: the first ing of singing is skipped, and the second confirms from
        // the space before the word.
        String filler = ".".repeat(200);
        assertNearStart("\\s[a-zA-Z]{0,12}ing\\s", List.of(), List.of(filler + " singing " + filler));
    }

    @Test
    public void testGateSkippedHitRunsNeitherDfa()
    {
        // Each hit lies far from the start, and the search skips it before either DFA starts: the
        // ing of kingdom is followed by a letter, not the whitespace every match needs there, the
        // byte two before Holmes, a period, is neither whitespace nor a word byte, which only the
        // gate further back rules out, and the nn of Pennsylvania is followed by a word byte,
        // which only the word boundary after it rules out.
        for (Map.Entry<String, String> entry : List.of(
                Map.entry("\\s[a-zA-Z]{0,12}ing\\s", " kingdom "),
                Map.entry("\\w+\\s+Holmes", " Holmes "),
                Map.entry("\\b\\w+nn\\b", " Pennsylvania "))) {
            Re2 pattern = Re2.compile(utf8Slice(entry.getKey()));
            InnerLiteralSearch search = pattern.innerLiteralSearchForDiagnostics();
            Slice text = utf8Slice(".".repeat(200) + entry.getValue() + ".".repeat(200));
            assertThat(search.search(text, 0, text.length(), 0, text.length())).as(entry.getKey()).isEqualTo(Dfa.SEARCH_NO_MATCH);
            assertThat(stateCount(search.reversePrefixProgramForDiagnostics())).as(entry.getKey()).isZero();
            assertThat(stateCount(pattern.forwardProgramForDiagnostics())).as(entry.getKey()).isZero();
        }

        // A gated one-byte search still checks a window shorter than its minimum for a hit that
        // passes its gates. No date ends a day or month in a letter, so a short line whose every
        // slash follows one has no match, and neither find starts the forward DFA.
        Slice line = utf8Slice("and/or a/b");
        for (Language language : Language.values()) {
            CompiledPattern date = language.compiledPattern(DATE);
            assertThat(date.innerLiteralSearchForDiagnostics().minimumSearchBytes()).as(language.name()).isGreaterThan(line.length());
            assertThat(date.find(line)).as(language.name()).isFalse();
            assertThat(date.matcher(line).find()).as(language.name()).isFalse();
            assertThat(forwardStateCount(date)).as(language.name()).isZero();
        }
    }

    @Test
    public void testFindsGatedOneByteLiteralFirst()
    {
        // A gated one-byte search that checks short windows runs first in full-Slice find, ahead
        // of the ordinary engine, and the pattern keeps no required bytes of its byte, which the
        // search's scan checks: the date's `/` and the email's `@` in every language, and Trino's
        // `=`. Elsewhere `[a-z]+=(?i:noqa)` searches for the folded `=noqa` from 96 bytes and
        // keeps `=` as a single required byte, which checks every window shorter than 256 bytes.
        for (String regex : List.of(DATE, EMAIL, NOQA_ASSIGNMENT)) {
            for (Language language : Language.values()) {
                String description = "%s %s".formatted(language, regex);
                CompiledPattern pattern = language.compiledPattern(regex);
                boolean first = !regex.equals(NOQA_ASSIGNMENT) || language == Language.TRINO;
                assertThat(Objects.toString(pattern.requiredBytesForDiagnostics(), null)).as(description).isEqualTo(first ? null : "one of =");
                assertThat(pattern.findsInnerLiteralFirstForDiagnostics()).as(description).isEqualTo(first);
            }
        }
        // A gated one-byte search for a byte other than the required one keeps the required byte
        // and the find that checks it: `\w+:[a-z]+@` checks short windows for `:` but requires
        // `@`, and a line without `@` has no match before any forward DFA starts.
        for (Language language : Language.values()) {
            String description = "%s %s".formatted(language, COLON_THEN_AT);
            CompiledPattern pattern = language.compiledPattern(COLON_THEN_AT);
            assertThat(pattern.innerLiteralSearchForDiagnostics().checksShortWindows()).as(description).isTrue();
            assertThat(pattern.requiredBytesForDiagnostics()).as(description).hasToString("one of @");
            assertThat(pattern.findsInnerLiteralFirstForDiagnostics()).as(description).isFalse();
            Slice line = utf8Slice("word:abc");
            assertThat(pattern.find(line)).as(description).isFalse();
            assertThat(pattern.matcher(line).find()).as(description).isFalse();
            assertThat(forwardStateCount(pattern)).as(description).isZero();
        }
        // The search alone answers a line without a hit that passes the byte gates, and one whose
        // first candidate lies past the near-start bytes, which it confirms on the reverse prefix
        // DFA and the forward first-match DFA; neither starts the earliest-match DFA of the
        // ordinary engine's find. A first candidate near the start falls back to that DFA before
        // any reverse search, and the DFA answers, with a match or, after the `1/2/` before an
        // `x`, without one. Where a word boundary decodes the text, that DFA quits on the é before
        // the dates, and the submatch engines find the date that language's word boundary admits.
        for (Language language : Language.values()) {
            assertFindsFirst(language, DATE, "and/or a/b 1/x", false, false);
            assertFindsFirst(language, DATE, FILLER + "12/25/2024", true, false);
            assertFindsFirst(language, DATE, "12/25/2024" + FILLER, true, true);
            assertFindsFirst(language, DATE, "1/2/x " + FILLER, false, true);
            assertFindsFirst(language, EMAIL, "no address, a @ b", false, false);
            assertFindsFirst(language, EMAIL, FILLER + " a@b", true, false);
            assertFindsFirst(language, EMAIL, "a@b " + FILLER, true, true);
            assertThat(language.compiledPattern(WORD_DATE).forwardProgramForDiagnostics().dfaQuitsOnNonAscii()).as(language.name()).isEqualTo(language != Language.RE2 && language != Language.RUST_BYTES_ASCII);
            assertFindsFirst(language, WORD_DATE, "\u00E912/25/2024 12/25/2024 " + FILLER, true, true);
            // The fallback's edges: no match after a quit, a match at the first byte before a
            // quit, one at the end after a quit, and a line of exactly the minimum length.
            assertFindsFirst(language, WORD_DATE, "\u00E91/2/x", false, true);
            assertFindsFirst(language, DATE, "1/2/34", true, true);
            assertFindsFirst(language, WORD_DATE, "1/2/34\u00A0", true, true);
            assertFindsFirst(language, WORD_DATE, "\u00E9 1/2/34", true, true);
        }
        assertFindsFirst(Language.TRINO, NOQA_ASSIGNMENT, "x = y, x =noqa", false, false);
        assertFindsFirst(Language.TRINO, NOQA_ASSIGNMENT, FILLER + " x=NoQa", true, false);
        assertFindsFirst(Language.TRINO, NOQA_ASSIGNMENT, "x=NOQA " + FILLER, true, true);
    }

    // Finds `line` with a fresh compile of `regex`, so its DFAs start empty, and checks the
    // answer and whether the find reached the ordinary engine's earliest-match DFA, which only a
    // fallback does, and the reverse prefix DFA, which only a candidate past the near-start bytes
    // does. The ordinary engine's own find, on another fresh compile, gives the same answer: after
    // a fallback the find takes the steps the engine takes after its search falls back, and
    // neither may drift from the other.
    private static void assertFindsFirst(Language language, String regex, String line, boolean matches, boolean fallsBack)
    {
        String description = "%s %s on %s".formatted(language, regex, line);
        Slice text = utf8Slice(line);
        CompiledPattern pattern = language.compiledPattern(regex);
        assertThat(pattern.findsInnerLiteralFirstForDiagnostics()).as(description).isTrue();
        assertThat(pattern.find(text)).as(description).isEqualTo(matches);
        assertThat(pattern.forwardProgramForDiagnostics().cachedDfaIfPresent(Dfa.DfaInstance.Kind.LONGEST_MATCH) != null).as(description).isEqualTo(fallsBack);
        assertThat(reversePrefixStateCount(pattern) > 0).as(description).isEqualTo(matches && !fallsBack);
        CompiledPattern ordinary = language.compiledPattern(regex);
        assertThat(ordinary.matchRegionInto(text, 0, text.length(), 0, text.length(), CompiledPattern.Anchor.UNANCHORED, null, null, null, null)).as(description).isEqualTo(matches);
    }

    @Test
    public void testLiteralFirstFindAgreesWithOrdinaryEngine()
    {
        // The full-Slice find of a pattern whose gated one-byte search runs first agrees with the
        // ordinary engine in every language, as do its matcher and count routes: on lines without
        // the byte, with it where its gates fail, alone at the line's start or end, and in a match
        // near the start, where the search falls back, or at the end, at window lengths around the
        // search's minimum, the folded `=noqa` minimum, and the required bytes' limit, beside
        // malformed UTF-8, and in a Slice that starts and ends inside its array between bytes that
        // would match. A case-insensitive pattern runs its search first only where it searches for
        // `=` alone: in Trino for `noqa`, and where `s` and `k` fold to ſ and the Kelvin sign for
        // `skip` and `kilo`. Elsewhere it searches for the folded literal and keeps `=` as a single
        // required byte, a control.
        Set<Language> unicodeFolding = EnumSet.complementOf(EnumSet.of(Language.JAVA, Language.RUST_BYTES_ASCII));
        Map<String, Set<Language>> searchesFirst = Map.of(
                DATE, EnumSet.allOf(Language.class),
                EMAIL, EnumSet.allOf(Language.class),
                NOQA_ASSIGNMENT, EnumSet.of(Language.TRINO),
                "[a-z]+=(?i:skip)", unicodeFolding,
                "[a-z]+=(?i:kilo)", unicodeFolding);
        Map<String, List<String>> pieces = Map.of(
                DATE, List.of("1/2/33", "/", "a/b", "1/x", "12/25/2024", "3/4/"),
                EMAIL, List.of("a@b", "@", " @ ", "a @b", "x@y.z"),
                NOQA_ASSIGNMENT, List.of("x=noqa", "=", "1=noqa", "x=nope", "a=NOQA", "b=NoQa"),
                "[a-z]+=(?i:skip)", List.of("x=skip", "=", "x=SKIP", "x=\u017Fkip", "x=s\u212Aip"),
                "[a-z]+=(?i:kilo)", List.of("x=kilo", "=", "x=\u212Ailo", "x=KILO"));
        Slice[] malformed = {Slices.EMPTY_SLICE, Slices.wrappedBuffer((byte) 0x80), Slices.wrappedBuffer((byte) 0xE2, (byte) 0x80), Slices.wrappedBuffer((byte) 0xFF)};
        for (Map.Entry<String, List<String>> entry : pieces.entrySet()) {
            String regex = entry.getKey();
            // An alternative that never matches leaves the top-level concatenation, and with it
            // the inner literal and the required bytes, out of the expected engine.
            String ordinary = "(?:" + regex + ")|\\b\\B";
            Slice match = utf8Slice(entry.getValue().getFirst());
            List<byte[]> lines = new ArrayList<>();
            for (int length : new int[] {0, 1, 63, 64, 95, 96, 127, 128, 255, 256, 300}) {
                lines.add("x".repeat(length).getBytes(UTF_8));
                for (String piece : entry.getValue()) {
                    for (Slice bytes : malformed) {
                        Slice before = Slices.wrappedBuffer(ByteArrays.concat(bytes, utf8Slice(piece)));
                        Slice after = Slices.wrappedBuffer(ByteArrays.concat(utf8Slice(piece), bytes));
                        for (Slice content : List.of(before, after)) {
                            if (content.length() <= length) {
                                Slice filler = utf8Slice("x".repeat(length - content.length()));
                                lines.add(ByteArrays.concat(content, filler));
                                lines.add(ByteArrays.concat(filler, content));
                            }
                        }
                    }
                }
            }
            for (Language language : Language.values()) {
                String description = "%s %s".formatted(language, regex);
                assertThat(language.compiledPattern(regex).findsInnerLiteralFirstForDiagnostics()).as(description).isEqualTo(searchesFirst.get(regex).contains(language));
                CompiledPattern ordinaryPattern = language.compiledPattern(ordinary);
                assertThat(ordinaryPattern.innerLiteralSearchForDiagnostics()).as(description).isNull();
                assertThat(ordinaryPattern.requiredBytesForDiagnostics()).as(description).isNull();
                Engine actual = language.compile(regex);
                Engine expected = language.compile(ordinary);
                for (byte[] line : lines) {
                    byte[] backing = ByteArrays.concat(match, line, match);
                    for (Slice text : List.of(Slices.wrappedBuffer(line), Slices.wrappedBuffer(backing, match.length(), line.length))) {
                        String lineDescription = "%s on %s".formatted(description, Arrays.toString(line));
                        assertThat(actual.spans(text)).as(lineDescription).isEqualTo(expected.spans(text));
                        assertThat(actual.count(text)).as(lineDescription).isEqualTo(expected.count(text));
                        assertThat(actual.find(text)).as(lineDescription).isEqualTo(expected.find(text));
                    }
                }
            }
        }
    }

    @Test
    public void testVectorSearchCutoff()
    {
        // Both a longer and a one-byte literal take the vector scans from 1024 bytes.
        for (Map.Entry<String, String> entry : List.of(Map.entry("\\w+\\s+Holmes", "Holmes"), Map.entry("[a-z]+@[a-z]+", "@"))) {
            InnerLiteralSearch search = Re2.compile(utf8Slice(entry.getKey())).innerLiteralSearchForDiagnostics();
            assertThat(literal(search)).as(entry.getKey()).isEqualTo(entry.getValue());
            assertThat(search.usesVectorSearch(1_023)).as(entry.getKey()).isFalse();
            assertThat(search.usesVectorSearch(1_024)).as(entry.getKey()).isEqualTo(VectorSupport.isAvailable());
            assertThat(search.usesVectorSearch(1_025)).as(entry.getKey()).isEqualTo(VectorSupport.isAvailable());
        }
        // A case-insensitive literal takes the folded pair kernel from 1024 bytes too, and below
        // that its exact head's scan and folded check.
        InnerLiteralSearch folded = Re2.compile(utf8Slice("(\\s*)((?i:# noqa)(?::\\s?([A-Z]+[0-9]+))?)")).innerLiteralSearchForDiagnostics();
        assertThat(literal(folded)).isEqualTo("# noqa");
        assertThat(folded.usesVectorSearch(1_023)).isFalse();
        assertThat(folded.usesVectorSearch(1_024)).isEqualTo(VectorSupport.isAvailable());
        // A kernel hit is checked again from the literal's first exact letter: `aaNOQA` passes
        // the kernel's test for `Aanoqa`, which accepts either case of `A`, but fails the check,
        // so the search never runs its reverse prefix.
        CompiledPattern exactLetters = Re2.compile(utf8Slice("\\sAa(?i:noqa)"));
        assertThat(exactLetters.find(utf8Slice("-".repeat(1_024) + " aaNOQA"))).isFalse();
        assertThat(reversePrefixStateCount(exactLetters)).isZero();
    }

    @Test
    public void testNearStartCheckAgreesWithSearchPastShorterMember()
    {
        // The near-start check reads far enough to find the longest member starting before the
        // minimum, so a shorter member can start past the minimum inside that read. There the
        // search does not fall back, so the check must not report a near hit. Here a partial awyer
        // straddles the minimum, and inn starts two bytes past it.
        assertNearStart(
                "([A-Za-z]awyer|[A-Za-z]inn)\\s",
                List.of(),
                List.of(".".repeat(61) + "awyxinn " + ".".repeat(Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES)));
    }

    private static void assertNearStart(String regex, List<String> nearTexts, List<String> farTexts)
    {
        Re2 pattern = Re2.compile(utf8Slice(regex));
        InnerLiteralSearch search = pattern.innerLiteralSearchForDiagnostics();
        assertThat(search).as(regex).isNotNull();
        Engine expected = regexpEngine(Re2.compile(utf8Slice("(?:" + regex + ")|\\b\\B")));
        for (String text : nearTexts) {
            assertSearchRoute(pattern, search, expected, utf8Slice(text), true);
        }
        for (String text : farTexts) {
            assertSearchRoute(pattern, search, expected, utf8Slice(text), false);
        }
    }

    private static void assertSearchRoute(CompiledPattern pattern, InnerLiteralSearch search, Engine expected, Slice text, boolean nearStart)
    {
        String description = text.toStringUtf8();
        long span = search.search(text, 0, text.length(), 0, text.length());
        assertThat(span == InnerLiteralSearch.FALLBACK).as(description).isEqualTo(nearStart);
        assertThat(pattern.searchesInnerLiteral(text, 0, text.length())).as(description).isEqualTo(!nearStart);
        // A matcher keeps its candidate cursor, the ordinary engine's route, only for a near hit.
        RegexpMatcher matcher = pattern.groupZeroMatcher(text, null);
        assertThat(matcher.find()).as(description).isTrue();
        assertThat(matcher.candidateStartRouteCountForDiagnostics()).as(description).isEqualTo(nearStart ? 1 : 0);
        assertThat(regexpEngine(pattern).spans(text)).as(description).isEqualTo(expected.spans(text));
        assertThat(pattern.count(text)).as(description).isEqualTo(expected.count(text));
    }

    private static String literal(String pattern)
    {
        InnerLiteralSearch search = innerLiteralSearch(pattern);
        assertThat(search).as(pattern).isNotNull();
        return literal(search);
    }

    // A set's members, in alternation order, or for a class pair in class order, joined with a
    // bar.
    private static String literal(InnerLiteralSearch search)
    {
        assertThat(search).isNotNull();
        return Stream.of(search.literals())
                .map(literal -> new String(literal, UTF_8))
                .collect(joining("|"));
    }

    private static List<Slice> texts()
    {
        List<Slice> texts = new ArrayList<>();
        // Overlapping and adjacent candidates, and failing confirmations that would rescan.
        for (String text : List.of(
                // Literals with and without the neighboring bytes a match needs, at both ends.
                "Holmes Holmes Holmes's Holmes. Holmes\nHolmes, Holmes\tHolmes Holmes Holmes Holmes",
                "singing bringing thing, kingdom things ing\tsingingX ing ing ingest sing ing\n".repeat(4),
                "a@b@c@d@e@f@g@h@i@j@k@l@m@n@o@p@q@r@s@t@u@v@w@x@y@z@a.b@c.d@e.f".repeat(2),
                "xshingshingshing ashing shing Ashing shingle punishing".repeat(2),
                "azxx zxx azx azxxx ".repeat(5),
                "abcdxy aaaaxy uuuuxy axyzxy ".repeat(4),
                "key: value\nother:\tx\n: none\nk:v\n".repeat(4),
                "key = value Key = value \u212Aey = value kkey = value \u212A\u212Aey = value ey = value key=value\n".repeat(4),
                "http://example.com/a ftp://x mailto:y https:// x://y".repeat(2),
                "a@c@b@ a@c@b@ c@ a@b@ a@@b@".repeat(4),
                "ac@d@ ac@d@ c@ ac@@d@",
                // A run of near literals the ordinary count takes over: the next literal lies 63
                // bytes past a match end, which stays near, then 64, which hands back to the inner
                // literal, and the last match ends with the text.
                "ashing" + ".".repeat(62) + "bshing" + ".".repeat(63) + "cshing" + ".".repeat(63) + "dshingeshing",
                "a Holmes" + ".".repeat(61) + "b Holmes" + ".".repeat(62) + "c Holmes",
                // Literal sets: adjacent and overlapping members, one member inside another, and
                // a false filter hit, barb, in texts long enough for the vector scan.
                "Sawyer inn tinn sawyerinn Xawyer\tbinnk\nainnk Jawyerinn abab babab ababa bab\n".repeat(16),
                " foo1 barbaz2 foobarbaz3\tfoo barbell4 barbaz\n ring1 moment2 king ing ment\n".repeat(16),
                // Quotes: apostrophes inside words, a closing quote without the punctuation, a
                // body past 30 bytes, and adjacent quotes, with matches at the start and end of
                // a text long enough for the vector scan, and in one shorter than the minimum skip.
                "\"Go!\" I don't know 'fine' or \"a body that runs on well past thirty bytes.\" \"a.\" \"b?\"'x!' it's \"end.\"".repeat(10),
                "'x!' don't \"a.\" \"b?\" ''\"c!\"",
                // Windows long enough for the vector literal scans: a dense one-byte literal, and
                // pair candidates that fail their verification.
                "a@b.c x@y ".repeat(110),
                "Hollys ".repeat(150) + "a Holmes b c@d.e singing",
                // Dates, and slashes after letters or before a non-digit, near the start and later.
                "a/1 on 1/2/2024 x/y 12/25/24 3/4/ 99/99/9999 1/23/456 /1/2 7//8\n".repeat(4),
                // Literals before a class that is empty outside RE2, in a text long enough that find
                // and spans skip the minimum-length check.
                "x *a y *& z *b * ".repeat(256),
                // Class runs: an earlier match spanning a later occurrence, runs and occurrences at
                // the text start, Unicode word characters around an occurrence, short runs across
                // line breaks, and runs longer than the near-start minimum. The random texts below
                // add malformed bytes.
                "nnxnn annbnn Pennsylvannia nnnn nnn annann\n".repeat(3),
                "nnann nn\n",
                "Tom  Tom xxTom TomFinn HuckleberryFinn Sawyer\nFinn\n\nTom\r\nx\nyFinn ab\nTom",
                // Case-insensitive literals: mixed-case hits, near misses that differ in one
                // letter, the literal's other case right before it, and a hit ending the text.
                "Sherlock HOLMES holmez HoLmEs's xholmes\tholme SHERLOCK HOLMeS a b@ noqa@x NOQA@x\n" +
                        "ERROR: error; xError:y erro: 12 PassWord 3 passwor 4 PASSWORD QHOho QHO hO\tHo QhOHO Holmes",
                "ännä übernn énn 東nn京 nnü ünn Ωnné ännbnn énn",
                // A combining mark after a word, which Java's boundaries read as part of it.
                "Finn\u0301 Penn\u0301x inn\u0300 a\u0301 Holmes\u0301 Holmes",
                // Letters, combining marks, and no-break spaces right beside the literals, which
                // Java's `(?U)` reads as word and space characters.
                "\u00E9 Holmes \u00E9\u00A0Holmes Holmes\u00E9 x\u00A0\u00A0Holmes\u0301 Penn\u00E9 Penn\u00A0 \u00E9nn\u0301 nn\u00A0 a@\u00E9 b@\u0301 c@\u00A0 d@",
                "x".repeat(70) + "nnxnnx nn " + "y".repeat(70) + "änn",
                "x".repeat(70) + "Tom\n" + "é".repeat(3) + "Sawyer xzb@cbb@c xzb@cbb@d",
                // A count resumes at each match end: a word that ends exactly there, a word right
                // after it, long whitespace runs before a literal, and matches that run past the
                // first hit with a start, one across a byte of its run's class that is not a word
                // byte.
                "a Holmes Holmes a HolmesX Holmes\tHolmes" + " ".repeat(150) + "Holmes" + "\t".repeat(70) + "x Holmes",
                "xababab abab zzab xab\u00e9 Pennab nnab singinging",
                "xnn-nn ")) {
            texts.add(utf8Slice(text));
            // A search whose first gate-passing hit is near its start falls back, and a text
            // shorter than the search's minimum never runs it, so filler long enough for a literal
            // set's search keeps the search and its later candidates in play.
            texts.add(utf8Slice("-".repeat(Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES) + text));
        }
        // Windows just under and at each minimum the search needs: without a literal, with one at
        // the start, and with one at the end, past the near-start minimum.
        for (int length : IntStream.of(
                InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES - 1,
                InnerLiteralSearch.MINIMUM_SINGLE_LITERAL_SEARCH_BYTES,
                Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES - 1,
                Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES,
                Prog.FUSED_PREFIX_VECTOR_MINIMUM_BYTES - 1,
                Prog.FUSED_PREFIX_VECTOR_MINIMUM_BYTES).distinct().toArray()) {
            texts.add(utf8Slice("x".repeat(length)));
            for (String literal : List.of(" Holmes", "Tom", "nn", "ing", "@", " hOLMeS", "QHOho", "1 PassWord")) {
                String filler = "x".repeat(length - literal.length());
                texts.add(utf8Slice(literal + filler));
                texts.add(utf8Slice(filler + literal));
            }
        }
        String[] tokens = {"# noqa", "# NoQA:", "# no", "# NOQB", "#  noqa", "# n", "E501", ",", "Holmes", " ", "  ", "\t", "\n", "a", "Z", "_", "9", "@", ".", "-", "+", ":", "//", "x", "y", "b", "c", "ing", "sh", "s", "é", "\u2028", "\uFFFF", "awyer", "inn", "foo", "barbaz", "aba", "bab", "ment", "1", "\"", "'", "?", "!", "/", "nn", "n", "Tom", "Finn", "ä", "\u0301"};
        Random random = new Random(20261031);
        for (int textIndex = 0; textIndex < 400; textIndex++) {
            DynamicSliceOutput output = new DynamicSliceOutput(256);
            if (textIndex % 2 == 1) {
                // Filler as long as a literal set's minimum lets every literal kind's search run
                // on these texts.
                output.writeBytes("-".repeat(Dfa.MIN_SMALL_BYTE_SET_VECTOR_SEARCH_BYTES).getBytes(UTF_8));
            }
            int length = output.size() + random.nextInt(240);
            while (output.size() < length) {
                if (random.nextInt(40) == 0) {
                    // Malformed UTF-8: a bare continuation byte or a truncated lead byte.
                    output.writeByte(random.nextBoolean() ? 0x80 : 0xE2);
                }
                else {
                    output.writeBytes(tokens[random.nextInt(tokens.length)].getBytes(UTF_8));
                }
            }
            texts.add(output.slice());
        }
        return texts;
    }

    private static List<Slice> transparentStartLiteralTexts()
    {
        List<Slice> texts = new ArrayList<>();
        // Each line terminator before and after the literals, which also start and end the text,
        // and a final terminator.
        for (String terminator : List.of("\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")) {
            for (String literal : List.of("ab", "Sherlock Holmes")) {
                String line = literal + " x" + literal + terminator + literal + terminator + "Holmes" + literal + "s " + literal;
                texts.add(utf8Slice(line + FILLER + terminator + line));
                texts.add(utf8Slice(line + terminator + FILLER + line + terminator));
            }
        }
        String[] tokens = {"ab", "Sherlock Holmes", "Sherlock", "Holmes", " ", "\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029", "a", "b", "x", "_", "é"};
        Random random = new Random(20261101);
        for (int textIndex = 0; textIndex < 200; textIndex++) {
            DynamicSliceOutput output = new DynamicSliceOutput(256);
            int length = random.nextInt(240);
            while (output.size() < length) {
                if (random.nextInt(30) == 0) {
                    // Malformed UTF-8: a bare continuation byte or a truncated sequence.
                    output.writeBytes(random.nextBoolean() ? new byte[] {(byte) 0x80} : new byte[] {(byte) 0xE2, (byte) 0x80});
                }
                else {
                    output.writeBytes(tokens[random.nextInt(tokens.length)].getBytes(UTF_8));
                }
            }
            texts.add(output.slice());
        }
        // A run long enough for the vector scan, whose candidates for the long literal each match
        // 40 bytes, so the scan passes the comparison budget and finishes with KMP.
        texts.add(utf8Slice("a".repeat(1100) + "\n" + "a".repeat(40) + "ba\n"));
        return texts;
    }

    private static InnerLiteralSearch innerLiteralSearch(String pattern)
    {
        return RustRegexp.compile(utf8Slice(pattern), RUST_BYTES_OPTIONS).innerLiteralSearchForDiagnostics();
    }

    private record SmallBudget(String pattern, long maxMemory, String match, boolean keepsSearch) {}

    private record MinimumWindow(String regex, String literal, int minimum, int countMinimum) {}

    private enum Language
    {
        // Java's `(?U)` reads `\w`, `\s`, and `\b` by Unicode properties, so a character past
        // ASCII on either side of a gated byte may be a word or space character.
        RE2, JAVA, JAVA_UNICODE, TRINO, RUST, RUST_BYTES, RUST_BYTES_ASCII;

        Engine compile(String pattern)
        {
            return this == TRINO ? trinoEngine(TrinoRegexp.compile(utf8Slice(pattern))) : regexpEngine(compiledPattern(pattern));
        }

        CompiledPattern compiledPattern(String pattern)
        {
            Slice slice = utf8Slice(pattern);
            return switch (this) {
                case RE2 -> Re2.compile(slice);
                case JAVA -> JavaRegexp.compile(slice);
                case JAVA_UNICODE -> JavaRegexp.compile(slice, JavaRegexp.Options.defaults().setUnicodeCharacterClasses(true));
                case TRINO -> TrinoRegexp.compile(slice).re2();
                case RUST -> RustRegexp.compile(slice);
                case RUST_BYTES -> RustRegexp.compile(slice, RUST_BYTES_OPTIONS);
                case RUST_BYTES_ASCII -> RustRegexp.compile(slice, RustRegexp.Options.defaults().setBytes(true).setUnicode(false));
            };
        }
    }

    private interface Engine
    {
        List<String> spans(Slice text);

        long count(Slice text);

        boolean find(Slice text);
    }

    private static Engine regexpEngine(CompiledPattern pattern)
    {
        return new Engine()
        {
            @Override
            public List<String> spans(Slice text)
            {
                List<String> spans = new ArrayList<>();
                RegexpMatcher boundaries = pattern.matcher(text, 0);
                while (boundaries.find()) {
                    spans.add(boundaries.start() + "-" + boundaries.end());
                }
                RegexpMatcher groups = pattern.matcher(text);
                while (groups.find()) {
                    for (int group = 0; group <= groups.groupCount(); group++) {
                        spans.add(group + ":" + groups.start(group) + "-" + groups.end(group));
                    }
                }
                return spans;
            }

            @Override
            public long count(Slice text)
            {
                return pattern.count(text);
            }

            @Override
            public boolean find(Slice text)
            {
                return pattern.find(text);
            }
        };
    }

    private static Engine trinoEngine(TrinoRegexp pattern)
    {
        return new Engine()
        {
            @Override
            public List<String> spans(Slice text)
            {
                List<String> spans = new ArrayList<>();
                TrinoRegexpMatcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    for (int group = 0; group <= matcher.groupCount(); group++) {
                        spans.add(group + ":" + matcher.start(group) + "-" + matcher.end(group));
                    }
                }
                return spans;
            }

            @Override
            public long count(Slice text)
            {
                return pattern.count(text);
            }

            @Override
            public boolean find(Slice text)
            {
                return pattern.contains(text);
            }
        };
    }
}
