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
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.airlift.regulator.TestingPatternShapes.shape;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestPrefixProbeAcceleration
{
    private static final List<String> LITERALS = List.of(
            "svc1://",
            "svc5://",
            "content-type:",
            "https://",
            "user=",
            "error: ",
            "aab",
            "abab",
            "ab",
            "ba",
            "http",
            "GET /",
            "café=",
            "Sherlock Holmes",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    private static final ByteFrequencies JSON_RECORDS = TestingByteFrequencies.learnedJsonRecords();
    private static final ByteFrequencies RARE_NON_LETTERS = TestingByteFrequencies.rareNonLetters();

    @Test
    public void testOtherStrategiesKeepFirstByte()
    {
        // Single-byte, folded, and non-ASCII-led prefixes keep their own scans.
        assertThat(configuredProgram("=".getBytes(UTF_8), false).prefixAccelProbeOffset()).isZero();
        assertThat(configuredProgram("svc1://".getBytes(UTF_8), true).prefixAccelProbeOffset()).isZero();
        Prog nonAscii = configuredProgram("év".getBytes(UTF_8), false);
        assertThat(nonAscii.prefixAccelStrategy(4_096)).isNotEqualTo(Prog.PrefixAccelStrategy.REPEATED_BYTE);
        assertThat(nonAscii.prefixAccelProbeOffset()).isZero();
    }

    @Test
    public void testFoldedPrefixProbesRarePair()
    {
        // Latin-1 folds stay in Latin-1, so the folded literal is the prefix, cut to "sherlock ".
        // The default uniform distribution leaves the pair to englishText(), which pairs l with
        // k, where the first and last bytes are s and a space.
        Re2.Options latin1 = Re2.Options.latin1();
        assertFoldedProbePair(Re2.compile(Slices.utf8Slice("(?i)Sherlock Holmes"), latin1), 4, 7);
        // A chosen distribution is used as given; in this one every letter and the space are
        // common and = is rare, so the pair holds the = of "key=value" and its last byte.
        assertFoldedProbePair(Re2.compile(Slices.utf8Slice("(?i)key=value"), latin1.setByteFrequencies(RARE_NON_LETTERS)), 3, 8);
        // Without Unicode, the crate leaves the space unfolded, so the folded prefix ends at
        // "sherlock", whose s and k are less than four times as likely as l and k.
        RustRegexp.Options asciiFolds = RustRegexp.Options.bytes().setUnicode(false).setCaseInsensitive(true);
        assertFoldedProbePair(RustRegexp.compile(Slices.utf8Slice("Sherlock Holmes"), asciiFolds), 0, 7);
        // Java's CASE_INSENSITIVE without UNICODE_CASE also folds ASCII only, so its s and k match
        // no long s or Kelvin sign, and its folded prefix is the same "sherlock".
        assertFoldedProbePair(JavaRegexp.compile(Slices.utf8Slice("Sherlock Holmes"), JavaRegexp.Options.defaults().setCaseInsensitive(true)), 0, 7);
    }

    @Test
    public void testDenseFoldedPairVerifiesInVectorLoop()
    {
        // The folded vector scan verifies candidates inside its vector loop when at least 15% of
        // its vectors are predicted to hold the English probe pair: e and r of "error" in 7.5% of
        // 16-byte vectors but 27% of 64-byte ones, t and e of "the" in 10.4% of 16-byte ones, t
        // and the space of "the " in 16.4% of them, a and h of "at the " in 14.4% of 64-byte
        // ones, and m and u of "timeout" in 2.8% of them.
        assertDenseFoldCaseScan("(?i)error", 16, false);
        assertDenseFoldCaseScan("(?i)error", 64, true);
        assertDenseFoldCaseScan("(?i)the", 16, false);
        assertDenseFoldCaseScan("(?i)the ", 16, true);
        assertDenseFoldCaseScan("(?i)at the ", 64, false);
        assertDenseFoldCaseScan("(?i)timeout", 64, false);
    }

    @Test
    public void testFoldedRunBehindVariableLead()
    {
        // Under Unicode folding, s and k also match the long s and the Kelvin sign, two and three
        // bytes in UTF-8, so no folded prefix reaches past them. The scan instead searches for
        // the run of folded bytes between them with the rarest pair, and steps back by the most
        // bytes its lead can take. In password, ss is s and s or, in Trino, also sharp s.
        List<BiFunction<String, ByteFrequencies, CompiledPattern>> compilers = List.of(
                (pattern, frequencies) -> Re2.compile(Slices.utf8Slice("(?i)" + pattern), Re2.Options.defaults().setByteFrequencies(frequencies)),
                (pattern, frequencies) -> RustRegexp.compile(Slices.utf8Slice(pattern), RustRegexp.Options.defaults().setCaseInsensitive(true).setByteFrequencies(frequencies)),
                (pattern, frequencies) -> JavaRegexp.compile(Slices.utf8Slice(pattern), JavaRegexp.Options.defaults().setCaseInsensitive(true).setUnicodeCase(true).setByteFrequencies(frequencies)),
                (pattern, frequencies) -> TrinoRegexp.compile(Slices.utf8Slice("(?i)" + pattern), TrinoRegexp.Options.defaults().setByteFrequencies(frequencies)).re2());
        for (BiFunction<String, ByteFrequencies, CompiledPattern> compiler : compilers) {
            Function<String, CompiledPattern> language = pattern -> compiler.apply(pattern, ByteFrequencies.uniform());
            assertFoldedRun(language.apply("Sherlock Holmes"), " holme", 8, 11, 1, 4);
            assertFoldedRun(language.apply("password"), "word", 4, 6, 0, 3);
            // A run needs four bytes, or three led by a rare letter, so yez, led by y, is scanned
            // for, and tyz, led by t, is not.
            assertFoldedRun(language.apply("ssssssssyez"), "yez", 8, 16, 0, 2);
            assertThat(language.apply("sssssssstyz").forwardProgramForDiagnostics().canPrefixAccel()).isFalse();
            // The first byte must be rated under 1.5%: English rates y at 1.4% and g at 1.6%, and
            // JSON rates g at 0.8%. It must also be a letter, since English rates _ rare.
            assertThat(language.apply("ssssssssgyz").forwardProgramForDiagnostics().canPrefixAccel()).isFalse();
            assertFoldedRun(compiler.apply("ssssssssgyz", ByteFrequencies.json()), "gyz", 8, 16, 0, 2);
            assertThat(language.apply("ssssssss_yz").forwardProgramForDiagnostics().canPrefixAccel()).isFalse();
            // Leads that may differ by eight bytes still qualify; nine do not.
            assertFoldedRun(language.apply("ssssssssxyzw"), "xyzw", 8, 16, 0, 2);
            assertThat(language.apply("sssssssssxyzw").forwardProgramForDiagnostics().canPrefixAccel()).isFalse();
            // A run longer than nine bytes is split, and the lead of its second part counts the
            // nine bytes of the first.
            assertFoldedRun(language.apply("kabcdefghjqvwx"), "qvwx", 10, 12, 0, 3);
        }
        // A lead of fixed width qualifies when there is no prefix. In Latin-1 each character of the
        // lead, the fold class included, takes one byte.
        assertFoldedRun(Re2.compile(Slices.utf8Slice("(?i)\u00E9clair")), "clair", 2, 2, 0, 4);
        assertFoldedRun(Re2.compile(latin1Slice("[\u00C9\u00E9]\u00C0(?i:xyzw)"), Re2.Options.latin1()), "xyzw", 2, 2, 0, 2);
        // A one-byte folded prefix stops the scan wherever its byte is, so a run with a rarer
        // pair displaces it.
        assertFoldedRun(Re2.compile(Slices.utf8Slice("(?i)es the")), " the", 2, 3, 0, 3);
        // A case-sensitive literal ends the runs, and a run must hold an ASCII letter: inner
        // literal search serves these, as it would without the fold class in front.
        for (String pattern : List.of("(?i:k)ey = value", "[\u00C9\u00E9]: \\d+ ERROR", "(?i:k)1234ERROR")) {
            Re2 compiled = Re2.compile(Slices.utf8Slice(pattern));
            assertThat(compiled.forwardProgramForDiagnostics().canPrefixAccel()).as(pattern).isFalse();
            assertThat(compiled.innerLiteralSearchForDiagnostics()).as(pattern).isNotNull();
        }
        // A class ends the runs unless it holds exactly one fold orbit: [A\x{10000}] lacks the a
        // of the orbit of A, and [Aa\x{10000}] holds a character outside it.
        for (String pattern : List.of("[A\\x{10000}](?i:xyzw)", "[Aa\\x{10000}](?i:xyzw)")) {
            assertThat(Re2.compile(Slices.utf8Slice(pattern)).forwardProgramForDiagnostics().canPrefixAccel()).as(pattern).isFalse();
        }
        // In Trino, the s of (?i)Holmes also matches the long s, so the literal has no folded
        // prefix, and every match starts with its run holme. A three-byte run led by a rare letter
        // is scanned for too, as with the wat of (?i)watson and the wor of (?i)works. With 64-byte
        // vectors these runs are left out and the DFA keeps its start bytes. A three-byte run led
        // by a common letter is not scanned for, as with the thi of (?i)this, the elf of (?i)self,
        // the cla of (?i)class, and the pon of (?i)response.
        CompiledPattern holmes = TrinoRegexp.compile(Slices.utf8Slice("(?i)Holmes")).re2();
        CompiledPattern watson = TrinoRegexp.compile(Slices.utf8Slice("(?i)watson")).re2();
        CompiledPattern works = TrinoRegexp.compile(Slices.utf8Slice("(?i)works")).re2();
        if (!VectorSupport.isAvailable() || VectorSupport.preferredByteLanes() < 64) {
            assertFoldedRun(holmes, "holme", 0, 0, 0, 3);
            assertFoldedRun(watson, "wat", 0, 0, 0, 2);
            assertFoldedRun(works, "wor", 0, 0, 0, 2);
        }
        else {
            for (CompiledPattern compiled : List.of(holmes, watson, works)) {
                assertThat(compiled.forwardProgramForDiagnostics().canPrefixAccel()).as(compiled.pattern().toStringUtf8()).isFalse();
            }
        }
        for (String pattern : List.of("(?i)this", "(?i)self", "(?i)class", "(?i)response")) {
            assertThat(TrinoRegexp.compile(Slices.utf8Slice(pattern)).re2().forwardProgramForDiagnostics().canPrefixAccel()).as(pattern).isFalse();
        }
        // In Trino, ffi is also the ffi ligature, the ff ligature and i, or f and the fi ligature.
        // The first character outside ASCII ends the runs, read in the folded literal, where the
        // Kelvin sign and the long s are k and s and take the leads k and s take.
        assertFoldedRun(TrinoRegexp.compile(Slices.utf8Slice("(?i)affidavit")).re2(), "davit", 4, 5, 0, 2);
        assertThat(TrinoRegexp.compile(Slices.utf8Slice("(?i)\u00E9clair")).re2().forwardProgramForDiagnostics().canPrefixAccel()).isFalse();
        assertFoldedRun(TrinoRegexp.compile(Slices.utf8Slice("(?i)\u212Aworld")).re2(), "world", 1, 3, 0, 4);
        assertFoldedRun(TrinoRegexp.compile(Slices.utf8Slice("(?i)\u017Fworld")).re2(), "world", 1, 2, 0, 4);
    }

    @Test
    public void testFoldedRunBehindVariableLeadFindsEveryMatch()
    {
        // The anchored searches of the reference never scan for the run, so they judge every
        // start the unanchored scan skips.
        List<CompiledPattern> patterns = List.of(
                Re2.compile(Slices.utf8Slice("(?i)Sherlock Holmes")),
                RustRegexp.compile(Slices.utf8Slice("Sherlock Holmes"), RustRegexp.Options.defaults().setCaseInsensitive(true)),
                Re2.compile(Slices.utf8Slice("(?i)kiss the sky")),
                RustRegexp.compile(Slices.utf8Slice("kiss the sky"), RustRegexp.Options.defaults().setCaseInsensitive(true)),
                // Trino literals spelled with the Kelvin sign and the long s, whose run lies
                // behind a lead that takes any spelling of k or s.
                TrinoRegexp.compile(Slices.utf8Slice("(?i)\u212Aworld")).re2(),
                TrinoRegexp.compile(Slices.utf8Slice("(?i)\u017Fworld")).re2());
        List<String> texts = new ArrayList<>();
        texts.addAll(foldedRunTexts(List.of("Sherlock Holmes", "\u017Fherloc\u212A HOLME\u017F", "SHERLOCK holmes"), "Xherlock Holmes"));
        texts.addAll(foldedRunTexts(List.of("kiss the sky", "\u212Ai\u017F\u017F THE \u017F\u212Ay", "KISS the SKY"), "kis the sky"));
        texts.addAll(foldedRunTexts(List.of("\u212AWORLD", "kworld", "\u017Fworld", "SWorld"), "xworld"));
        for (CompiledPattern pattern : patterns) {
            assertThat(pattern.forwardProgramForDiagnostics().prefixAccelMaximumLead()).as(pattern.pattern().toStringUtf8()).isPositive();
            RegexpMatcher anchored = pattern.matcher(Slices.EMPTY_SLICE);
            for (String text : texts) {
                Slice input = Slices.utf8Slice(text);
                List<int[]> expected = new ArrayList<>();
                for (int start = 0; start < input.length(); start++) {
                    if (anchored.reset(input, start, input.length()).lookingAt()) {
                        expected.add(new int[] {start, anchored.end()});
                        start = anchored.end() - 1;
                    }
                }
                RegexpMatcher matcher = pattern.matcher(input);
                for (int[] match : expected) {
                    assertThat(matcher.find()).as("%s in %s", pattern.pattern().toStringUtf8(), text).isTrue();
                    assertThat(new int[] {matcher.start(), matcher.end()}).as("%s in %s", pattern.pattern().toStringUtf8(), text).containsExactly(match);
                }
                assertThat(matcher.find()).as("%s in %s", pattern.pattern().toStringUtf8(), text).isFalse();
                assertThat(pattern.count(input)).as("%s in %s", pattern.pattern().toStringUtf8(), text).isEqualTo(expected.size());
            }
        }
    }

    @Test
    public void testFoldedRunBehindVariableLeadScansOncePerMatch()
    {
        // The scan for ` holme` steps back by the widest lead, three bytes before an ASCII
        // match, and the DFA returns to its start state at each of them. A scan from any of
        // them would find the same run, so a count runs the scan once per match and once more to
        // find no further match.
        String text = "filler text, Sherlock Holmes, ".repeat(100);
        for (CompiledPattern pattern : List.of(
                Re2.compile(Slices.utf8Slice("(?i)Sherlock Holmes")),
                TrinoRegexp.compile(Slices.utf8Slice("(?i)Sherlock Holmes")).re2())) {
            Prog program = pattern.forwardProgramForDiagnostics();
            assertThat(program.prefixAccelMaximumLead()).isGreaterThan(program.prefixAccelMinimumLead());
            assertThat(pattern.count(Slices.utf8Slice(text))).isEqualTo(100);
            assertThat(program.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH).prefixRunScanCount()).isEqualTo(101);
        }
    }

    @Test
    public void testLeadingFoldedRunKeepsFilteredCandidateCursor()
    {
        // Every match of Trino's (?i)think and (?i)Holmes starts with its folded run, and a
        // first-match DFA whose start bytes carry a start literal filter keeps them beside the run.
        // A find loop verifies candidates from them there, as think's do with the Vector API, which
        // yields each match's start without a reverse pass; with 64-byte vectors there is no run
        // and the start bytes serve as before. Holmes's start bytes carry no filter, so its find
        // loop takes the run. The run of (?i)Sherlock Holmes lies behind a lead, so its DFA has no
        // start bytes to verify.
        String filler = "filler text, ".repeat(100);
        Slice text = Slices.utf8Slice(filler + "I THINK " + filler + "Holmes thinks " + filler + "Sherlock HOLMES " + filler);
        assertCandidateCursorFinds(TrinoRegexp.compile(Slices.utf8Slice("(?i)think")).re2(), text, 2, VectorSupport.isAvailable());
        CompiledPattern holmes = TrinoRegexp.compile(Slices.utf8Slice("(?i)Holmes")).re2();
        if (holmes.forwardProgramForDiagnostics().canPrefixAccel()) {
            assertCandidateCursorFinds(holmes, text, 2, false);
        }
        assertCandidateCursorFinds(TrinoRegexp.compile(Slices.utf8Slice("(?i)Sherlock Holmes")).re2(), text, 1, false);

        // Only the first-match DFA whose start bytes carry the filter keeps and charges the start
        // tables; the run takes their place in every other DFA, which would never read them. So
        // think keeps them in its first-match DFA alone, with the Vector API, and Holmes in none.
        // With 64-byte vectors neither word has a run, as above, and the tables serve as before.
        if (!VectorSupport.isAvailable() || VectorSupport.preferredByteLanes() < 64) {
            Map<String, List<Dfa.DfaInstance.Kind>> tableKinds = Map.of(
                    "(?i)think", VectorSupport.isAvailable() ? List.of(Dfa.DfaInstance.Kind.FIRST_MATCH) : List.of(),
                    "(?i)Holmes", List.of());
            for (Map.Entry<String, List<Dfa.DfaInstance.Kind>> entry : tableKinds.entrySet()) {
                Prog program = TrinoRegexp.compile(Slices.utf8Slice(entry.getKey())).re2().forwardProgramForDiagnostics();
                assertThat(program.prefixRunStartsEveryMatch()).as(entry.getKey()).isTrue();
                for (Dfa.DfaInstance.Kind kind : Dfa.DfaInstance.Kind.values()) {
                    assertThat(Dfa.DfaInstance.initializationMemory(program, kind) > Dfa.DfaInstance.minimumMemory(program, kind))
                            .as("%s %s start tables", entry.getKey(), kind)
                            .isEqualTo(entry.getValue().contains(kind));
                }
            }
        }
    }

    @Test
    public void testCompiledProgramsInBothEncodings()
    {
        Re2.Options utf8Json = Re2.Options.defaults().setByteFrequencies(JSON_RECORDS);
        Re2.Options latin1Json = Re2.Options.latin1().setByteFrequencies(JSON_RECORDS);
        Re2 utf8 = Re2.compile(Slices.utf8Slice("svc1://(\\w+)"), utf8Json);
        assertThat(utf8.forwardProgramForDiagnostics().canPrefixAccel()).isTrue();
        assertThat(utf8.forwardProgramForDiagnostics().prefixAccelStrategy(4_096)).isEqualTo(Prog.PrefixAccelStrategy.REPEATED_BYTE);
        assertThat(utf8.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(1);
        Re2 latin1 = Re2.compile(Slices.utf8Slice("svc1://(\\w+)"), latin1Json);
        assertThat(latin1.forwardProgramForDiagnostics().canPrefixAccel()).isTrue();
        assertThat(latin1.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(1);

        // A Latin-1 pattern is Latin-1 text, so ï is one byte there and two in UTF-8. The sample
        // lacks Latin-1 ï and the trailing UTF-8 byte of ï, though the UTF-8 lead byte it shares
        // with é is common, so the probe moves to the last byte of ï, at a different offset.
        Re2 utf8Accent = Re2.compile(Slices.utf8Slice("naïve=(\\w+)"), utf8Json);
        assertThat(utf8Accent.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(3);
        Re2 latin1Accent = Re2.compile(latin1Slice("naïve=(\\w+)"), latin1Json);
        assertThat(latin1Accent.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(2);

        assertThat(Re2.compile(Slices.utf8Slice("https://(\\S+)"), utf8Json).forwardProgramForDiagnostics().prefixAccelProbeOffset()).isZero();

        // Default options keep first-byte scanning.
        for (Re2.Options options : List.of(Re2.Options.defaults(), Re2.Options.latin1())) {
            Re2 service = Re2.compile(Slices.utf8Slice("svc1://(\\w+)"), options);
            assertThat(service.forwardProgramForDiagnostics().canPrefixAccel()).isTrue();
            assertThat(service.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isZero();
        }

        // A distribution in which punctuation is rare probes the colon.
        for (Re2.Options options : List.of(Re2.Options.defaults(), Re2.Options.latin1())) {
            Re2 url = Re2.compile(Slices.utf8Slice("https://(\\S+)"), options.setByteFrequencies(RARE_NON_LETTERS));
            assertThat(url.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(5);
        }
    }

    @Test
    public void testRangeBoundaries()
    {
        for (String value : LITERALS) {
            byte[] literal = value.getBytes(UTF_8);
            Prog program = configuredProgram(literal, false);
            for (int backingOffset : new int[] {0, 3}) {
                // An occurrence at the very start of the range puts the probe byte at
                // offset + probe.
                assertMatchAt(program, literal, 64, backingOffset, 0);
                assertMatchAt(program, literal, 64, backingOffset, 1);
                assertMatchAt(program, literal, 64, backingOffset, 64 - literal.length);
                assertMatchAt(program, literal, 64, backingOffset, 64 - literal.length - 1);
                // A haystack exactly as long as the prefix.
                assertMatchAt(program, literal, literal.length, backingOffset, 0);
                assertNoMatch(program, literal.length, backingOffset);
                assertNoMatch(program, literal.length - 1, backingOffset);
            }
        }
    }

    @Test
    public void testOccurrencesOutsideRange()
    {
        for (String value : LITERALS) {
            byte[] literal = value.getBytes(UTF_8);
            Prog program = configuredProgram(literal, false);
            int probe = program.prefixAccelProbeOffset();
            byte[] data = new byte[64];
            Arrays.fill(data, (byte) '#');
            // An occurrence starting one byte before the range has its probe byte, when that is
            // not the first byte, inside the range; no candidate may start before the range.
            System.arraycopy(literal, 0, data, 7, literal.length);
            assertThat(program.prefixAccel(data, 8, 40)).as(value).isEqualTo(-1);
            // An occurrence ending one byte past the range end.
            assertThat(program.prefixAccel(data, 0, 6 + literal.length)).as(value).isEqualTo(-1);
            assertThat(program.prefixAccel(data, 0, 7 + literal.length)).as(value).isEqualTo(7);
            // A lone probe byte before the range, followed by an occurrence inside it.
            Arrays.fill(data, (byte) '#');
            data[9] = literal[probe];
            System.arraycopy(literal, 0, data, 20, literal.length);
            assertThat(program.prefixAccel(data, 10, 50)).as(value).isEqualTo(20);
        }
    }

    @Test
    public void testOverlappingSelfSimilarLiterals()
    {
        assertThat(search("aab", "aaab", 0)).isEqualTo(1);
        assertThat(search("aab", "aaaaaab", 0)).isEqualTo(4);
        assertThat(search("abab", "ababab", 0)).isZero();
        assertThat(search("abab", "ababab", 1)).isEqualTo(2);
        assertThat(search("abab", "abaabab", 0)).isEqualTo(3);
        assertThat(search("aababc", "aabaababcaababc", 0)).isEqualTo(3);

        // Exact-literal count advances past each match, so overlapping occurrences count once.
        Re2.Options jsonRecords = Re2.Options.defaults().setByteFrequencies(JSON_RECORDS);
        assertThat(Re2.compile(Slices.utf8Slice("abab"), jsonRecords).count(Slices.utf8Slice("abababab"))).isEqualTo(2);
        assertThat(Re2.compile(Slices.utf8Slice("aab"), jsonRecords).count(Slices.utf8Slice("aaabaabxaab"))).isEqualTo(3);
    }

    @Test
    public void testMultipleMatchesInBothEncodings()
    {
        String text = "ts=1 user=alice x user= user=bob user=user=carol svc1://h/ svc1:/ svc1://";
        List<Re2.Options> optionsList = List.of(
                Re2.Options.defaults().setByteFrequencies(JSON_RECORDS),
                Re2.Options.latin1().setByteFrequencies(JSON_RECORDS));
        for (Re2.Options options : optionsList) {
            assertMatches(Re2.compile(Slices.utf8Slice("user=(\\w+)"), options), text, "user=alice", "user=bob", "user=user");
            assertMatches(Re2.compile(Slices.utf8Slice("svc1://"), options), text, "svc1://", "svc1://");
            assertThat(Re2.compile(Slices.utf8Slice("user="), options).count(Slices.utf8Slice(text))).isEqualTo(5);
            assertThat(Re2.compile(Slices.utf8Slice("user=(\\w+)"), options).count(Slices.utf8Slice(text))).isEqualTo(3);
        }

        // The rare-non-letters distribution moves the probe onto é, one byte in Latin-1 and two
        // in UTF-8.
        byte[] latin1Text = "x café=1 cafe=2 café=".getBytes(ISO_8859_1);
        Re2 latin1 = Re2.compile(latin1Slice("café=(\\w*)"), Re2.Options.latin1().setByteFrequencies(RARE_NON_LETTERS));
        assertThat(latin1.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(3);
        assertMatches(latin1, Slices.wrappedBuffer(latin1Text), List.of(new int[] {2, 8}, new int[] {16, 21}));
        byte[] utf8Text = "x café=1 cafe=2 café=".getBytes(UTF_8);
        Re2 utf8 = Re2.compile(Slices.utf8Slice("café=(\\w*)"), Re2.Options.defaults().setByteFrequencies(RARE_NON_LETTERS));
        assertThat(utf8.forwardProgramForDiagnostics().prefixAccelProbeOffset()).isEqualTo(3);
        assertMatches(utf8, Slices.wrappedBuffer(utf8Text), List.of(new int[] {2, 9}, new int[] {17, 23}));
    }

    @Test
    public void testPrefixAccelMatchesReference()
    {
        Random random = new Random(17);
        boolean firstByteProbe = false;
        boolean letterProbe = false;
        boolean asciiNonLetterProbe = false;
        boolean nonAsciiProbe = false;
        for (ByteFrequencies frequencies : List.of(JSON_RECORDS, RARE_NON_LETTERS)) {
            for (String value : LITERALS) {
                byte[] literal = value.getBytes(UTF_8);
                Prog program = configuredProgram(literal, false, frequencies);
                // A first-byte probe and a later probe run separate scan loops.
                int probe = program.prefixAccelProbeOffset();
                firstByteProbe |= probe == 0;
                letterProbe |= probe > 0 && Character.isLetter(literal[probe]);
                asciiNonLetterProbe |= probe > 0 && literal[probe] >= 0 && !Character.isLetter(literal[probe]);
                nonAsciiProbe |= probe > 0 && literal[probe] < 0;
                // Text drawn from the literal's own bytes produces dense partial and overlapping
                // occurrences around the probe.
                byte[] alphabet = (value + "#").getBytes(UTF_8);
                for (int iteration = 0; iteration < 1_000; iteration++) {
                    byte[] data = new byte[random.nextInt(600) + 8];
                    for (int index = 0; index < data.length; index++) {
                        data[index] = random.nextInt(8) == 0 ? (byte) random.nextInt(256) : alphabet[random.nextInt(alphabet.length)];
                    }
                    if (data.length >= literal.length && random.nextBoolean()) {
                        System.arraycopy(literal, 0, data, random.nextInt(data.length - literal.length + 1), literal.length);
                    }
                    int offset = random.nextInt(4);
                    int length = data.length - offset - random.nextInt(4);
                    assertThat(program.prefixAccel(data, offset, length))
                            .as("%s at offset %s length %s", value, offset, length)
                            .isEqualTo(referenceSearch(data, offset, length, literal));
                }
            }
        }
        assertThat(firstByteProbe).isTrue();
        assertThat(letterProbe).isTrue();
        assertThat(asciiNonLetterProbe).isTrue();
        assertThat(nonAsciiProbe).isTrue();
    }

    @Test
    public void testCandidateWorkBound()
    {
        // In a run of the prefix's first byte every position is a candidate that matches half the
        // prefix before it fails, so the first candidate passes LiteralSearchKernel's comparison
        // budget, and KMP finds the match after the run's candidates. The input stays under 1 KiB,
        // where the first-byte scan serves a probe at the prefix's first byte.
        String half = "a".repeat(100);
        assertCandidateWorkBound(half + "b" + half, "a".repeat(822), ByteFrequencies.uniform(), 0);
        // A probe moved to the rare '=' meets a candidate at every other position, in text that
        // alternates 'a' with '='.
        String alternating = "a=".repeat(250);
        assertCandidateWorkBound(alternating + "b=" + alternating, "a=".repeat((1 << 20) / 2), RARE_NON_LETTERS, 1);
    }

    @Test
    @Timeout(30)
    public void testFirstByteScanStaysLinear()
    {
        // In 16 MiB of the prefix's first byte every position is a candidate that matches half of
        // a long prefix, so a first-byte scan that never hands off to KMP compares about 10^12
        // bytes. The handoff diagnostics start in the budgeted continuation, so only the timeout
        // guards the handoff from the first-byte loop of prefixAccel.
        String half = "a".repeat(50_000);
        byte[] prefix = (half + "b" + half).getBytes(UTF_8);
        byte[] data = new byte[(16 << 20) + prefix.length];
        Arrays.fill(data, (byte) 'a');
        System.arraycopy(prefix, 0, data, data.length - prefix.length, prefix.length);
        Prog program = configuredProgram(prefix, false, ByteFrequencies.uniform());

        // With vector scans disabled, such as with eight preferred byte lanes, the first-byte loop
        // runs here.
        Prog.PrefixAccelStrategy strategy = VectorSupport.isAvailable() ? Prog.PrefixAccelStrategy.FUSED_VECTOR : Prog.PrefixAccelStrategy.REPEATED_BYTE;
        assertThat(program.prefixAccelStrategy(data.length)).isEqualTo(strategy);
        assertThat(program.prefixAccelProbeOffset()).isZero();
        assertThat(program.prefixAccel(data, 0, data.length)).isEqualTo(data.length - prefix.length);
    }

    @Test
    public void testPublicFindMatchesReference()
    {
        Random random = new Random(29);
        List<Re2.Options> optionsList = List.of(
                Re2.Options.defaults(),
                Re2.Options.latin1(),
                Re2.Options.defaults().setByteFrequencies(RARE_NON_LETTERS),
                Re2.Options.latin1().setByteFrequencies(RARE_NON_LETTERS));
        boolean asciiNonLetterProbe = false;
        boolean nonAsciiProbe = false;
        boolean trinoProbe = false;
        for (String value : List.of("svc1://", "content-type:", "user=", "error: ", "café=", "aab", "abab", "http")) {
            for (Re2.Options options : optionsList) {
                Re2 pattern = Re2.compile(Slices.utf8Slice(Re2.quote(Slices.utf8Slice(value)).toStringUtf8() + "[0-9]*"), options);
                byte[] literal = value.getBytes(UTF_8);
                int probe = pattern.forwardProgramForDiagnostics().prefixAccelProbeOffset();
                asciiNonLetterProbe |= probe > 0 && literal[probe] >= 0 && !Character.isLetter(literal[probe]);
                nonAsciiProbe |= probe > 0 && literal[probe] < 0;
                // A Trino pattern of the bare literal plans the exact-literal search.
                TrinoRegexp trino = TrinoRegexp.compile(Slices.utf8Slice(value), TrinoRegexp.Options.defaults().setByteFrequencies(options.byteFrequencies()));
                assertThat(trino.re2().hasExactLiteralSpan()).isTrue();
                assertThat(shape(trino.re2())).isEqualTo(CompiledPattern.Shape.EXACT_LITERAL);
                assertThat(trino.re2().forwardProgramForDiagnostics().prefixAccelProbeOffset()).as(value).isEqualTo(probe);
                assertThat(trino.re2().exactLiteralSpanUsesPrefixScan(63)).as(value).isEqualTo(probe > 0);
                assertThat(trino.re2().exactLiteralSpanUsesPrefixScan(64)).as(value).isTrue();
                trinoProbe |= probe > 0;
                byte[] alphabet = (value + "0123").getBytes(UTF_8);
                for (int iteration = 0; iteration < 200; iteration++) {
                    byte[] data = new byte[random.nextInt(300)];
                    for (int index = 0; index < data.length; index++) {
                        data[index] = alphabet[random.nextInt(alphabet.length)];
                    }
                    if (data.length >= literal.length && random.nextBoolean()) {
                        System.arraycopy(literal, 0, data, random.nextInt(data.length - literal.length + 1), literal.length);
                    }
                    Slice input = Slices.wrappedBuffer(data);
                    List<int[]> expected = referenceMatches(data, literal, true);
                    assertMatches(pattern, input, expected);
                    assertThat(pattern.count(input)).isEqualTo(expected.size());
                    List<int[]> literalMatches = referenceMatches(data, literal, false);
                    assertThat(trino.count(input)).isEqualTo(literalMatches.size());
                    assertThat(trino.replace(input, Slices.EMPTY_SLICE)).isEqualTo(withoutMatches(data, literalMatches));
                }
            }
        }
        assertThat(asciiNonLetterProbe).isTrue();
        assertThat(nonAsciiProbe).isTrue();
        assertThat(trinoProbe).isTrue();
    }

    private static void assertFoldedProbePair(CompiledPattern pattern, int firstOffset, int secondOffset)
    {
        Prog program = pattern.forwardProgramForDiagnostics();
        assertThat(program.usesFoldCasePrefixCandidateScan()).isTrue();
        assertThat(program.prefixAccelFoldCaseFirstOffset()).isEqualTo(firstOffset);
        assertThat(program.prefixAccelFoldCaseSecondOffset()).isEqualTo(secondOffset);
        // A folded prefix starts every match.
        assertThat(program.prefixAccelMaximumLead()).isZero();
    }

    private static void assertCandidateCursorFinds(CompiledPattern pattern, Slice text, int matches, boolean candidateCursor)
    {
        String description = pattern.pattern().toStringUtf8();
        RegexpMatcher matcher = pattern.matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        assertThat(found).as(description).isEqualTo(matches);
        assertThat(matcher.candidateStartRouteCountForDiagnostics()).as(description).isEqualTo(candidateCursor ? matches : 0);
    }

    private static void assertDenseFoldCaseScan(String pattern, int lanes, boolean dense)
    {
        Prog program = Re2.compile(Slices.utf8Slice(pattern)).forwardProgramForDiagnostics();
        byte[] folded = program.prefixAccelFoldCaseBytesForDiagnostics();
        ByteFrequencies english = ByteFrequencies.englishText();
        double probability = english.foldedProbePairProbability(folded, english.foldedProbePair(folded, folded.length));
        assertThat(Prog.usesDenseFoldCaseScan(probability, lanes)).as("%s at %s lanes", pattern, lanes).isEqualTo(dense);
        // The compiled program takes the kernel the rule chooses for this JVM's vectors.
        assertThat(program.prefixAccelFoldCaseDense())
                .as(pattern)
                .isEqualTo(VectorSupport.isAvailable() && Prog.usesDenseFoldCaseScan(probability, VectorSupport.preferredByteLanes()));
    }

    private static void assertFoldedRun(CompiledPattern pattern, String run, int minimumLead, int maximumLead, int firstOffset, int secondOffset)
    {
        assertFoldedRun(pattern.pattern().toStringUtf8(), pattern.forwardProgramForDiagnostics(), run, minimumLead, maximumLead, firstOffset, secondOffset);
    }

    private static void assertFoldedRun(String description, Prog program, String run, int minimumLead, int maximumLead, int firstOffset, int secondOffset)
    {
        assertThat(program.usesFoldCasePrefixCandidateScan()).as(description).isTrue();
        assertThat(program.prefixAccelFoldCaseBytesForDiagnostics()).as(description).isEqualTo(run.getBytes(UTF_8));
        assertThat(program.prefixAccelMinimumLead()).as(description).isEqualTo(minimumLead);
        assertThat(program.prefixAccelMaximumLead()).as(description).isEqualTo(maximumLead);
        assertThat(program.prefixAccelFoldCaseFirstOffset()).as(description).isEqualTo(firstOffset);
        assertThat(program.prefixAccelFoldCaseSecondOffset()).as(description).isEqualTo(secondOffset);
    }

    /// Texts that hold each spelling of a case-insensitive literal whose prefix scan searches for
    /// a run behind a lead of varying width, with `nearMiss`, which holds the run behind a lead
    /// no match takes, and the spelling without its first character, which holds the run but no
    /// match: alone, back to back, and at the start, middle, and end of texts long enough for the
    /// vector scan.
    static List<String> foldedRunTexts(List<String> spellings, String nearMiss)
    {
        String filler = "filler text, ".repeat(100);
        List<String> texts = new ArrayList<>();
        for (String spelling : spellings) {
            String shortLead = spelling.substring(spelling.offsetByCodePoints(0, 1));
            texts.add(spelling);
            texts.add(shortLead);
            texts.add(nearMiss + spelling + spelling + shortLead);
            texts.add(shortLead + " " + nearMiss + " " + spelling);
            texts.add(spelling + filler + nearMiss + filler + spelling + filler + shortLead);
            texts.add(shortLead + filler + spelling + nearMiss + spelling);
        }
        return texts;
    }

    private static int search(String literal, String text, int offset)
    {
        byte[] data = text.getBytes(UTF_8);
        return configuredProgram(literal.getBytes(UTF_8), false).prefixAccel(data, offset, data.length - offset);
    }

    private static void assertMatches(Re2 pattern, String text, String... expected)
    {
        RegexpMatcher matcher = pattern.matcher(Slices.utf8Slice(text));
        List<String> actual = new ArrayList<>();
        while (matcher.find()) {
            actual.add(matcher.group().toStringUtf8());
        }
        assertThat(actual).containsExactly(expected);
    }

    private static void assertMatches(Re2 pattern, Slice input, List<int[]> expected)
    {
        RegexpMatcher matcher = pattern.matcher(input);
        for (int[] match : expected) {
            assertThat(matcher.find()).isTrue();
            assertThat(new int[] {matcher.start(), matcher.end()}).containsExactly(match);
        }
        assertThat(matcher.find()).isFalse();
    }

    /// Matches of `literal`, each extended by the longest following run of ASCII digits when
    /// `digitRun` is set, in order and without overlap.
    private static List<int[]> referenceMatches(byte[] data, byte[] literal, boolean digitRun)
    {
        List<int[]> matches = new ArrayList<>();
        int position = 0;
        while ((position = referenceSearch(data, position, data.length - position, literal)) >= 0) {
            int end = position + literal.length;
            while (digitRun && end < data.length && data[end] >= '0' && data[end] <= '9') {
                end++;
            }
            matches.add(new int[] {position, end});
            position = end;
        }
        return matches;
    }

    private static Slice withoutMatches(byte[] data, List<int[]> matches)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int position = 0;
        for (int[] match : matches) {
            output.write(data, position, match[0] - position);
            position = match[1];
        }
        output.write(data, position, data.length - position);
        return Slices.wrappedBuffer(output.toByteArray());
    }

    private static int referenceSearch(byte[] data, int offset, int length, byte[] literal)
    {
        int lastStart = offset + length - literal.length;
        for (int position = offset; position <= lastStart; position++) {
            if (Arrays.equals(data, position, position + literal.length, literal, 0, literal.length)) {
                return position;
            }
        }
        return -1;
    }

    private static void assertCandidateWorkBound(String literal, String fill, ByteFrequencies frequencies, int probeOffset)
    {
        byte[] prefix = literal.getBytes(UTF_8);
        byte[] data = (fill + literal).getBytes(UTF_8);
        Prog program = configuredProgram(prefix, false, frequencies);

        assertThat(program.prefixAccelStrategy(data.length)).isEqualTo(Prog.PrefixAccelStrategy.REPEATED_BYTE);
        assertThat(program.prefixAccelProbeOffset()).isEqualTo(probeOffset);
        assertThat(program.prefixAccelKmpHandoffForDiagnostics(data, 0, data.length)).isEqualTo(1);
        assertThat(program.prefixAccel(data, 0, data.length)).isEqualTo(data.length - prefix.length);
    }

    private static void assertMatchAt(Prog program, byte[] prefix, int length, int backingOffset, int matchPosition)
    {
        byte[] data = new byte[backingOffset + length + 5];
        Arrays.fill(data, (byte) '#');
        System.arraycopy(prefix, 0, data, backingOffset + matchPosition, prefix.length);
        assertThat(program.prefixAccel(data, backingOffset, length)).isEqualTo(backingOffset + matchPosition);
    }

    private static void assertNoMatch(Prog program, int length, int backingOffset)
    {
        byte[] data = new byte[backingOffset + length + 5];
        Arrays.fill(data, (byte) '#');
        assertThat(program.prefixAccel(data, backingOffset, length)).isEqualTo(-1);
    }

    private static Slice latin1Slice(String value)
    {
        return Slices.wrappedBuffer(value.getBytes(ISO_8859_1));
    }

    /// Configures `prefix` under [TestingByteFrequencies#learnedJsonRecords()], which
    /// moves the probes of several [#LITERALS] off their first byte.
    private static Prog configuredProgram(byte[] prefix, boolean foldCase)
    {
        return configuredProgram(prefix, foldCase, JSON_RECORDS);
    }

    private static Prog configuredProgram(byte[] prefix, boolean foldCase, ByteFrequencies frequencies)
    {
        Prog program = new Prog();
        program.configurePrefixAccel(Slices.wrappedBuffer(prefix), foldCase, frequencies);
        return program;
    }
}
