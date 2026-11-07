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
import io.airlift.slice.SizeOf;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;

import static java.util.Objects.requireNonNull;

final class Regexp
{
    // Parse flags (re2/regexp.h). Keep bit values in sync with pinned upstream.
    // Single-bit flags.
    static final int FOLD_CASE = 1 << 0;
    static final int LITERAL = 1 << 1;
    static final int CLASS_NEWLINE = 1 << 2;
    static final int DOT_MATCHES_NEWLINE = 1 << 3;
    static final int ONE_LINE = 1 << 4;
    static final int LATIN1 = 1 << 5;
    static final int NON_GREEDY = 1 << 6;
    static final int PERL_CLASSES = 1 << 7;
    static final int PERL_WORD_BOUNDARY = 1 << 8;
    static final int PERL_EXTENSIONS = 1 << 9;
    static final int UNICODE_GROUPS = 1 << 10;
    static final int NEVER_NEWLINE = 1 << 11;
    static final int NEVER_CAPTURE = 1 << 12;

    // ASCII-only case folding, used by the Java parser and by alternation merging. This is not an
    // upstream RE2 flag.
    static final int ASCII_FOLD_CASE = 1 << 14;
    static final int WAS_DOLLAR = 1 << 15;
    static final int FINAL_LINE_END = 1 << 16;
    static final int UNICODE_WORD_BOUNDARY = 1 << 17;
    static final int FULL_CASE_FOLD = 1 << 18;
    static final int JAVA_UNIX_LINES = 1 << 19;
    static final int JAVA_UNICODE_CASE = 1 << 20;
    static final int JAVA_UNICODE_CHARACTER_CLASS = 1 << 21;
    static final int JAVA_COMMENTS = 1 << 22;
    static final int JAVA_FINAL_END = 1 << 23;
    static final int JAVA_LINE = 1 << 24;
    static final int JAVA_WORD_BOUNDARY = 1 << 25;
    static final int TRINO_LINE = 1 << 26;
    // Pattern-level Rust crate search semantics: a matcher region keeps the whole input as
    // assertion context, an empty match that splits a UTF-8 character is skipped, and iteration
    // skips an empty match ending where the previous match ended. With LATIN1, which is the
    // crate's bytes API, an empty match may split a character.
    static final int RUST_SEARCH = 1 << 27;
    // Rust crate assertions. On BEGIN_LINE and END_LINE, RUST_ASSERTION selects the crate's
    // CRLF multi-line anchors. On WORD_BOUNDARY it selects the crate's start boundary, or with
    // RUST_WORD_END its end boundary; RUST_WORD_HALF checks only that boundary's non-word side.
    // JAVA_UNICODE_CHARACTER_CLASS selects Unicode word characters. With JAVA_WORD_BOUNDARY,
    // RUST_ASSERTION selects the crate's Unicode \b and \B instead. Bit 13 is now the only free
    // parse-flag bit; CompiledPattern uses bit 31 for the compiler dialect.
    static final int RUST_ASSERTION = 1 << 28;
    static final int RUST_WORD_END = 1 << 29;
    static final int RUST_WORD_HALF = 1 << 30;

    // Composite flag groups and masks.
    static final int MATCH_NEWLINE = CLASS_NEWLINE | DOT_MATCHES_NEWLINE;
    static final int LIKE_PERL = CLASS_NEWLINE | ONE_LINE | PERL_CLASSES | PERL_WORD_BOUNDARY | PERL_EXTENSIONS | UNICODE_GROUPS;
    static final int ALL_PARSE_FLAGS = (1 << 31) - 1;

    // util/utf.h Runemax
    static final int RUNEMAX = 0x10FFFF;

    private static final Regexp[] NO_CHILDREN = new Regexp[0];

    private final RegexpOp op;
    private final int parseFlags;
    // Never mutated, and shared with the nodes built from this node's children() view.
    private final Regexp[] children;

    // Op-specific payload
    private final int rune;              // LITERAL
    private final int[] runes;           // LITERAL_STRING
    private final CharClass charClass;   // CHAR_CLASS
    private final int captureIndex;               // CAPTURE
    private final Slice name;        // CAPTURE (optional)
    private final int min;               // REPEAT
    private final int max;               // REPEAT (-1 = unbounded)
    private final int matchId;           // HAVE_MATCH

    record RequiredPrefixResult(Slice prefix, boolean foldCase, Regexp suffix) {}

    record RequiredPrefixForAccelResult(Slice prefix, boolean foldCase) {}

    /// Bytes that every match holds, compared under ASCII case folding, after leading elements
    /// that take from `minimumLead` through `maximumLead` bytes; see [#foldedRunsForAccel].
    record FoldedRun(byte[] bytes, int minimumLead, int maximumLead) {}

    // A folded run holds at least four bytes: shorter runs, such as the le after the fi of
    // Trino's (?i)file, are common enough in text that their scan stops far more often than the
    // DFA's start bytes would. It holds at most the nine bytes of a folded prefix. Its lead may
    // vary by at most eight bytes, which bounds how often a search finds the same run again while
    // the engine steps through the lead.
    private static final int MIN_FOLDED_RUN_BYTES = 4;
    private static final int MAX_FOLDED_RUN_BYTES = Prog.MAX_FOLD_CASE_PREFIX_BYTES;
    private static final int MAX_FOLDED_RUN_LEAD_SLACK = 8;

    private Regexp(
            RegexpOp op,
            int parseFlags,
            List<Regexp> children,
            int rune,
            int[] runes,
            CharClass charClass,
            int captureIndex,
            Slice name,
            int min,
            int max,
            int matchId)
    {
        this.op = requireNonNull(op, "op is null");
        this.parseFlags = parseFlags;
        this.children = childArray(requireNonNull(children, "children is null"));
        this.rune = rune;
        this.runes = runes;
        this.charClass = charClass;
        this.captureIndex = captureIndex;
        this.name = name;
        this.min = min;
        this.max = max;
        this.matchId = matchId;
    }

    RegexpOp op()
    {
        return op;
    }

    int parseFlags()
    {
        return parseFlags;
    }

    /// Determines whether matches must be anchored with a fixed string prefix
    /// (i.e., the regexp is of the form `^literal...` in one-line mode).
    /// If so, returns the prefix (as bytes), whether it is ASCII-case-folded,
    /// and the remaining regexp suffix. A fully case-folded literal is a prefix only when
    /// `caseFolding` keeps its folds within ASCII.
    RequiredPrefixResult requiredPrefix(CaseFolding caseFolding)
    {
        // The regexp must be of the form:
        // 1. some number of BEGIN_TEXT anchors
        // 2. a literal char or string
        // 3. the rest
        if (op != RegexpOp.CONCAT) {
            return null;
        }

        int childIndex = 0;
        while (childIndex < children.length && children[childIndex].op == RegexpOp.BEGIN_TEXT) {
            childIndex++;
        }
        if (childIndex == 0 || childIndex >= children.length) {
            return null;
        }

        Regexp regexp = children[childIndex];
        if (regexp.op != RegexpOp.LITERAL && regexp.op != RegexpOp.LITERAL_STRING) {
            return null;
        }
        if ((regexp.parseFlags & FULL_CASE_FOLD) != 0 && !supportsAsciiFoldPrefix(regexp, caseFolding)) {
            return null;
        }
        childIndex++;

        Regexp suffix;
        if (childIndex < children.length) {
            if (childIndex == children.length - 1) {
                suffix = children[childIndex];
            }
            else {
                suffix = Regexp.concat(parseFlags, children().subList(childIndex, children.length));
            }
        }
        else {
            suffix = Regexp.emptyMatch(parseFlags);
        }

        boolean latin1 = (regexp.parseFlags & LATIN1) != 0;
        byte[] prefixBytes = convertRunesToBytes(latin1, regexp.op == RegexpOp.LITERAL ? new int[] {regexp.rune} : regexp.runes);
        boolean foldCase = (regexp.parseFlags & FOLD_CASE) != 0;
        return new RequiredPrefixResult(Slices.wrappedBuffer(prefixBytes), foldCase, suffix);
    }

    /// Determines whether matches must be unanchored with a fixed string prefix.
    /// This "sees through" capturing groups but does not try to glue multiple
    /// prefix fragments together. A fully case-folded literal is a prefix only when
    /// `caseFolding` keeps its folds within ASCII.
    RequiredPrefixForAccelResult requiredPrefixForAccel(CaseFolding caseFolding)
    {
        // The regexp must either begin with or be a literal char or string.
        Regexp regexp = (op == RegexpOp.CONCAT && children.length != 0) ? children[0] : this;
        while (regexp.op == RegexpOp.CAPTURE) {
            regexp = regexp.children[0];
            if (regexp.op == RegexpOp.CONCAT && regexp.children.length != 0) {
                regexp = regexp.children[0];
            }
        }
        if (regexp.op != RegexpOp.LITERAL && regexp.op != RegexpOp.LITERAL_STRING) {
            return null;
        }
        if ((regexp.parseFlags & FULL_CASE_FOLD) != 0 && !supportsAsciiFoldPrefix(regexp, caseFolding)) {
            return null;
        }

        boolean latin1 = (regexp.parseFlags & LATIN1) != 0;
        byte[] prefixBytes = convertRunesToBytes(latin1, regexp.op == RegexpOp.LITERAL ? new int[] {regexp.rune} : regexp.runes);
        boolean foldCase = (regexp.parseFlags & FOLD_CASE) != 0;
        return new RequiredPrefixForAccelResult(Slices.wrappedBuffer(prefixBytes), foldCase);
    }

    /// Returns the folded runs of four or more bytes in the leading elements of every match, in
    /// order, each with the range of bytes its lead can take. Under Unicode folding, `k` and `s`
    /// also match the Kelvin sign and the long s, which are wider in UTF-8, so no folded prefix
    /// reaches past them, but the ASCII letters between them still form runs at a known range of
    /// distances from the match start.
    ///
    /// The walk sees through captures and concatenations. A run byte is an ASCII byte of a
    /// literal that is a case-folded ASCII letter, which matches its two cases, or a non-letter,
    /// which matches only itself, and a run holds at least one folded ASCII letter: a run of
    /// exact bytes, like a case-sensitive ASCII letter, is what the case-sensitive scans and inner
    /// literal search serve, so the walk stops at a case-sensitive ASCII letter. A run's lead is
    /// every byte before it, earlier runs included. Besides run bytes, a lead holds literal
    /// characters outside ASCII, which take the one byte sequence they match, classes that hold
    /// exactly one fold orbit, which take their members' widths in the program's encoding, and, in
    /// a fully case-folded literal, stretches of characters whose orbits leave ASCII or that a
    /// multi-character fold such as `ss` for `ß` covers, which take the widths their folds take.
    /// The walk stops at any other element and once the leads may differ by more than eight bytes.
    /// In a fully case-folded literal it also stops at the first character outside ASCII, read in
    /// the folded literal, where `ß` continues as `ss`, only when the literal needs a
    /// multi-character fold. Runs longer than nine bytes are split.
    List<FoldedRun> foldedRunsForAccel(CaseFolding caseFolding)
    {
        FoldedRunBuilder builder = new FoldedRunBuilder();
        boolean latin1Program = (parseFlags & LATIN1) != 0;
        Deque<Regexp> stack = new ArrayDeque<>();
        stack.addLast(this);
        while (!stack.isEmpty() && !builder.stopped()) {
            Regexp regexp = stack.removeLast();
            switch (regexp.op) {
                case CAPTURE -> stack.addLast(regexp.children[0]);
                case CONCAT -> pushChildrenReversed(stack, regexp);
                case LITERAL, LITERAL_STRING -> {
                    int[] literalRunes = regexp.op == RegexpOp.LITERAL ? new int[] {regexp.rune} : regexp.runes;
                    if ((regexp.parseFlags & FULL_CASE_FOLD) != 0) {
                        addFullCaseFoldRuns(builder, literalRunes, caseFolding);
                    }
                    else {
                        addLiteralRuns(builder, literalRunes, (regexp.parseFlags & FOLD_CASE) != 0, (regexp.parseFlags & LATIN1) != 0);
                    }
                }
                case CHAR_CLASS -> addFoldOrbitLead(builder, regexp.charClass, caseFolding, latin1Program || (regexp.parseFlags & LATIN1) != 0);
                default -> builder.stop();
            }
        }
        return builder.finish();
    }

    // As the compiler matches a literal without full case folding: an ASCII letter in both cases
    // when the literal folds case, any other ASCII byte as itself, and any other character as its
    // one byte in Latin-1 or its UTF-8 sequence. Folded ASCII letters and ASCII non-letters are
    // run bytes, a case-sensitive ASCII letter stops the walk, and any other character is a lead
    // of its width.
    private static void addLiteralRuns(FoldedRunBuilder builder, int[] literalRunes, boolean foldCase, boolean latin1)
    {
        for (int literalRune : literalRunes) {
            if (literalRune >= 0x80) {
                int width = latin1 ? 1 : Utf8.encodedLength(literalRune);
                builder.lead(width, width);
            }
            else if (!foldCase && isAsciiLetter(literalRune)) {
                builder.stop();
                return;
            }
            else {
                builder.runByte(literalRune);
            }
        }
    }

    // As the compiler matches a fully case-folded literal: each folded character matches its
    // simple fold orbit, and each multi-character fold whose folded characters the literal holds
    // also matches its sources in their place. A character whose orbit stays in ASCII and that no
    // such fold covers matches its ASCII cases at its own place, so it is a run byte; every path
    // passes it, since no fold spans it.
    private static void addFullCaseFoldRuns(FoldedRunBuilder builder, int[] literalRunes, CaseFolding caseFolding)
    {
        boolean multiCharacter = caseFolding.requiresFullCaseFold(literalRunes);
        int[] folded = multiCharacter ? caseFolding.fold(literalRunes) : literalRunes;
        boolean[] variable = new boolean[folded.length];
        for (int index = 0; index < folded.length; index++) {
            if (multiCharacter) {
                for (UnicodeFullCaseFold.FoldToken token : caseFolding.multiCharacterTokensStartingWith(folded[index])) {
                    if (token.matches(folded, index)) {
                        Arrays.fill(variable, index, index + token.foldedRunes().length, true);
                    }
                }
            }
            if (!foldsWithinAscii(folded[index], caseFolding)) {
                variable[index] = true;
            }
        }

        int index = 0;
        while (index < folded.length) {
            if (!variable[index]) {
                builder.runByte(isAsciiLetter(folded[index]) ? folded[index] | 0x20 : folded[index]);
                index++;
                continue;
            }
            int end = index + 1;
            while (end < folded.length && variable[end]) {
                end++;
            }
            int[] stretch = Arrays.copyOfRange(folded, index, end);
            for (int rune : stretch) {
                if (rune >= 0x80) {
                    builder.stop();
                    return;
                }
            }
            UnicodeFullCaseFold.ByteLength width = caseFolding.byteLength(stretch);
            builder.lead(width.minimum(), width.maximum());
            if (builder.stopped()) {
                return;
            }
            index = end;
        }
    }

    private static boolean foldsWithinAscii(int rune, CaseFolding caseFolding)
    {
        int member = rune;
        do {
            if (member >= 0x80) {
                return false;
            }
            member = caseFolding.cycleFoldRune(member);
        }
        while (member != rune);
        return true;
    }

    // A class that holds exactly the fold orbit of two or more characters, as a case-folded
    // character compiles under Unicode folding, takes the widths of its members.
    private static void addFoldOrbitLead(FoldedRunBuilder builder, CharClass charClass, CaseFolding caseFolding, boolean latin1)
    {
        if (charClass.runeCount() < 2) {
            builder.stop();
            return;
        }
        int first = charClass.range(0).low();
        int member = first;
        int memberCount = 0;
        int minimumWidth = Integer.MAX_VALUE;
        int maximumWidth = 0;
        do {
            if (!charClass.contains(member) || memberCount == charClass.runeCount()) {
                builder.stop();
                return;
            }
            memberCount++;
            int width = latin1 ? 1 : Utf8.encodedLength(member);
            minimumWidth = Math.min(minimumWidth, width);
            maximumWidth = Math.max(maximumWidth, width);
            member = caseFolding.cycleFoldRune(member);
        }
        while (member != first);
        if (memberCount != charClass.runeCount()) {
            builder.stop();
            return;
        }
        builder.lead(minimumWidth, maximumWidth);
    }

    private static boolean isAsciiLetter(int rune)
    {
        int lowerCase = rune | 0x20;
        return lowerCase >= 'a' && lowerCase <= 'z';
    }

    private static final class FoldedRunBuilder
    {
        private final List<FoldedRun> runs = new ArrayList<>();
        private final byte[] run = new byte[MAX_FOLDED_RUN_BYTES];
        private int runLength;
        private boolean runHoldsLetter;
        // The range of bytes before the current run.
        private int minimumLead;
        private int maximumLead;
        private boolean stopped;

        boolean stopped()
        {
            return stopped;
        }

        void runByte(int value)
        {
            if (runLength == MAX_FOLDED_RUN_BYTES) {
                closeRun();
            }
            run[runLength++] = (byte) value;
            runHoldsLetter |= isAsciiLetter(value);
        }

        // Stops the walk once the leads of later runs would differ by more than the limit.
        void lead(int minimum, int maximum)
        {
            closeRun();
            minimumLead += minimum;
            maximumLead += maximum;
            if (maximumLead - minimumLead > MAX_FOLDED_RUN_LEAD_SLACK) {
                stopped = true;
            }
        }

        void stop()
        {
            closeRun();
            stopped = true;
        }

        List<FoldedRun> finish()
        {
            closeRun();
            return List.copyOf(runs);
        }

        private void closeRun()
        {
            if (runLength >= MIN_FOLDED_RUN_BYTES && runHoldsLetter) {
                runs.add(new FoldedRun(Arrays.copyOf(run, runLength), minimumLead, maximumLead));
            }
            minimumLead += runLength;
            maximumLead += runLength;
            runLength = 0;
            runHoldsLetter = false;
        }
    }

    private static boolean supportsAsciiFoldPrefix(Regexp regexp, CaseFolding caseFolding)
    {
        return supportsAsciiFold(regexp.op == RegexpOp.LITERAL ? new int[] {regexp.rune} : regexp.runes, caseFolding);
    }

    /// Returns whether ASCII case-insensitive comparison matches exactly the text that
    /// `caseFolding` matches for these literal runes. Call it only behind the literal's
    /// case-fold flag, so the full-fold table is built only for patterns that fold case.
    static boolean supportsAsciiFold(int[] literalRunes, CaseFolding caseFolding)
    {
        for (int literalRune : literalRunes) {
            if (!foldsWithinAscii(literalRune, caseFolding)) {
                return false;
            }
        }
        return !caseFolding.requiresFullCaseFold(literalRunes);
    }

    /// Returns whether ASCII case-insensitive comparison matches exactly the text that the compiler
    /// matches for a case-folded literal with `parseFlags` and these runes. Without full case
    /// folding, the compiler matches each ASCII letter's pair only, even for `k` and
    /// `s`, whose fold orbits leave ASCII, so every ASCII literal qualifies. An ASCII letter
    /// pair such as `[Kk]` simplifies to that form. With full case folding the compiler
    /// matches the whole orbit, which [#supportsAsciiFold] checks.
    static boolean foldsAsAsciiPairs(int parseFlags, int[] literalRunes, CaseFolding caseFolding)
    {
        if ((parseFlags & FULL_CASE_FOLD) != 0) {
            return supportsAsciiFold(literalRunes, caseFolding);
        }
        for (int literalRune : literalRunes) {
            if (literalRune > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /// Returns the complete encoded literal matched by this expression, or `null` when the
    /// expression contains non-literal behavior or case folding. Captures are transparent because
    /// callers use this only when capture offsets are not requested.
    Slice exactLiteral()
    {
        DynamicSliceOutput literal = new DynamicSliceOutput(16);
        Deque<Regexp> stack = new ArrayDeque<>();
        stack.addLast(this);

        while (!stack.isEmpty()) {
            Regexp regexp = stack.removeLast();
            switch (regexp.op) {
                case EMPTY_MATCH -> {}
                case CAPTURE -> stack.addLast(regexp.children[0]);
                case CONCAT -> pushChildrenReversed(stack, regexp);
                case LITERAL -> {
                    if ((regexp.parseFlags & FOLD_CASE) != 0) {
                        return null;
                    }
                    literal.writeBytes(convertRunesToBytes(
                            (regexp.parseFlags & LATIN1) != 0,
                            new int[] {regexp.rune}));
                }
                case LITERAL_STRING -> {
                    if ((regexp.parseFlags & FOLD_CASE) != 0) {
                        return null;
                    }
                    literal.writeBytes(convertRunesToBytes(
                            (regexp.parseFlags & LATIN1) != 0,
                            regexp.runes));
                }
                default -> {
                    return null;
                }
            }
        }
        return literal.slice();
    }

    /// Returns capturing group names keyed by capture number.
    Map<Integer, String> captureNames()
    {
        HashMap<Integer, String> captureNames = null;
        ArrayDeque<Regexp> stack = new ArrayDeque<>();
        stack.addLast(this);

        while (!stack.isEmpty()) {
            Regexp regexp = stack.removeLast();
            if (regexp.op == RegexpOp.CAPTURE && regexp.name != null) {
                if (captureNames == null) {
                    captureNames = new HashMap<>();
                }
                captureNames.put(regexp.captureIndex, regexp.name.toStringUtf8());
            }

            pushChildrenReversed(stack, regexp);
        }

        return (captureNames == null) ? Map.of() : Map.copyOf(captureNames);
    }

    static byte[] convertRunesToBytes(boolean latin1, int[] runes)
    {
        if (latin1) {
            byte[] bytes = new byte[runes.length];
            for (int runeIndex = 0; runeIndex < runes.length; runeIndex++) {
                bytes[runeIndex] = (byte) runes[runeIndex];
            }
            return bytes;
        }

        // UTF-8: allocate worst-case and then shrink.
        byte[] encodedBytes = new byte[runes.length * 4];
        int end = 0;
        for (int rune : runes) {
            end = Utf8.encode(encodedBytes, end, rune);
        }
        if (end == encodedBytes.length) {
            return encodedBytes;
        }
        byte[] bytes = new byte[end];
        System.arraycopy(encodedBytes, 0, bytes, 0, end);
        return bytes;
    }

    int childCount()
    {
        return children.length;
    }

    Regexp child(int childIndex)
    {
        return children[childIndex];
    }

    /// Returns the bytes this expression retains, counting each node, child array, rune array, and
    /// class once however many nodes share it, or a value above `limit` once the count passes it,
    /// so a large expression costs about `limit` bytes of counting. A class counts each of its
    /// ranges, though ranges can be shared with the Unicode tables. A capture name counts its
    /// slice but not the pattern bytes it views.
    long estimatedRetainedSize(long limit)
    {
        Set<Object> counted = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Regexp> pending = new ArrayDeque<>();
        counted.add(this);
        pending.add(this);
        // A node is counted when it is queued, so the queue holds no more nodes than the limit
        // pays for, however many children one node has.
        long size = RetainedSizes.INSTANCE_SIZE;
        while (!pending.isEmpty() && size <= limit) {
            Regexp regexp = pending.removeLast();
            if (regexp.children.length != 0 && counted.add(regexp.children)) {
                size += SizeOf.sizeOf(regexp.children);
            }
            if (regexp.runes != null && counted.add(regexp.runes)) {
                size += SizeOf.sizeOf(regexp.runes);
            }
            if (regexp.charClass != null && counted.add(regexp.charClass)) {
                int rangeCount = regexp.charClass.rangeCount();
                size += RetainedSizes.CHAR_CLASS_INSTANCE_SIZE + SizeOf.sizeOfObjectArray(rangeCount) + (long) rangeCount * RetainedSizes.RUNE_RANGE_INSTANCE_SIZE;
            }
            if (regexp.name != null) {
                size += RetainedSizes.SLICE_INSTANCE_SIZE;
            }
            for (int index = 0; index < regexp.children.length && size <= limit; index++) {
                Regexp child = regexp.children[index];
                if (counted.add(child)) {
                    size += RetainedSizes.INSTANCE_SIZE;
                    pending.add(child);
                }
            }
        }
        return size;
    }

    // Loaded only when a size is estimated: parsing never sizes objects, and TeaVM cannot.
    private static final class RetainedSizes
    {
        private static final int INSTANCE_SIZE = SizeOf.instanceSize(Regexp.class);
        private static final int CHAR_CLASS_INSTANCE_SIZE = SizeOf.instanceSize(CharClass.class);
        private static final int RUNE_RANGE_INSTANCE_SIZE = SizeOf.instanceSize(RuneRange.class);
        private static final int SLICE_INSTANCE_SIZE = SizeOf.instanceSize(Slice.class);

        private RetainedSizes() {}
    }

    // Allocates a view per call; the array is never mutated, so the view is always current.
    List<Regexp> children()
    {
        return new ChildList(children);
    }

    // Pushes the children in reverse so stack pop order visits them left to right.
    private static void pushChildrenReversed(Deque<Regexp> stack, Regexp regexp)
    {
        Regexp[] children = regexp.children;
        for (int childIndex = children.length - 1; childIndex >= 0; childIndex--) {
            stack.addLast(children[childIndex]);
        }
    }

    int rune()
    {
        if (op != RegexpOp.LITERAL) {
            throw new IllegalStateException("not a literal");
        }
        return rune;
    }

    int[] runes()
    {
        if (op != RegexpOp.LITERAL_STRING) {
            throw new IllegalStateException("not a literal string");
        }
        return runes;
    }

    CharClass charClass()
    {
        if (op != RegexpOp.CHAR_CLASS) {
            throw new IllegalStateException("not a char class");
        }
        return charClass;
    }

    int captureIndex()
    {
        if (op != RegexpOp.CAPTURE) {
            throw new IllegalStateException("not a capture");
        }
        return captureIndex;
    }

    Slice name()
    {
        if (op != RegexpOp.CAPTURE) {
            throw new IllegalStateException("not a capture");
        }
        return name;
    }

    int min()
    {
        if (op != RegexpOp.REPEAT) {
            throw new IllegalStateException("not a repeat");
        }
        return min;
    }

    int max()
    {
        if (op != RegexpOp.REPEAT) {
            throw new IllegalStateException("not a repeat");
        }
        return max;
    }

    int matchId()
    {
        if (op != RegexpOp.HAVE_MATCH) {
            throw new IllegalStateException("not have-match");
        }
        return matchId;
    }

    static Regexp noMatch(int parseFlags)
    {
        return new Regexp(RegexpOp.NO_MATCH, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp emptyMatch(int parseFlags)
    {
        return new Regexp(RegexpOp.EMPTY_MATCH, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp anyChar(int parseFlags)
    {
        return new Regexp(RegexpOp.ANY_CHAR, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp anyByte(int parseFlags)
    {
        return new Regexp(RegexpOp.ANY_BYTE, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp beginLine(int parseFlags)
    {
        return new Regexp(RegexpOp.BEGIN_LINE, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp endLine(int parseFlags)
    {
        return new Regexp(RegexpOp.END_LINE, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp wordBoundary(int parseFlags)
    {
        return new Regexp(RegexpOp.WORD_BOUNDARY, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp noWordBoundary(int parseFlags)
    {
        return new Regexp(RegexpOp.NO_WORD_BOUNDARY, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp beginText(int parseFlags)
    {
        return new Regexp(RegexpOp.BEGIN_TEXT, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp endText(int parseFlags)
    {
        return new Regexp(RegexpOp.END_TEXT, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp literal(int parseFlags, int rune)
    {
        return new Regexp(RegexpOp.LITERAL, parseFlags, List.of(), rune, null, null, 0, null, 0, 0, 0);
    }

    static Regexp literalString(int parseFlags, int[] runes)
    {
        requireNonNull(runes, "runes is null");
        return new Regexp(RegexpOp.LITERAL_STRING, parseFlags, List.of(), 0, runes.clone(), null, 0, null, 0, 0, 0);
    }

    static Regexp charClass(int parseFlags, CharClass charClass)
    {
        return new Regexp(RegexpOp.CHAR_CLASS, parseFlags, List.of(), 0, null, requireNonNull(charClass, "charClass is null"), 0, null, 0, 0, 0);
    }

    static Regexp capture(int parseFlags, Regexp child, int captureIndex, Slice name)
    {
        requireNonNull(child, "child is null");
        if (captureIndex <= 0) {
            throw new IllegalArgumentException("captureIndex must be > 0");
        }
        return new Regexp(RegexpOp.CAPTURE, parseFlags, List.of(child), 0, null, null, captureIndex, name, 0, 0, 0);
    }

    static Regexp star(int parseFlags, Regexp child)
    {
        requireNonNull(child, "child is null");
        return starPlusOrQuest(RegexpOp.STAR, parseFlags, child);
    }

    static Regexp plus(int parseFlags, Regexp child)
    {
        requireNonNull(child, "child is null");
        return starPlusOrQuest(RegexpOp.PLUS, parseFlags, child);
    }

    static Regexp quest(int parseFlags, Regexp child)
    {
        requireNonNull(child, "child is null");
        return starPlusOrQuest(RegexpOp.QUEST, parseFlags, child);
    }

    /// Constructs a unary node without applying [#starPlusOrQuest] squashing rules.
    ///
    /// This is needed for fidelity with upstream `simplify.cc`, which builds unary
    /// regexps directly (instead of calling `Regexp::Star/Plus/Quest`) so that
    /// simplification preserves forms like `(?:a+)*` rather than rewriting them to `a*`.
    static Regexp rawUnary(RegexpOp operator, int parseFlags, Regexp child)
    {
        requireNonNull(operator, "operator is null");
        requireNonNull(child, "child is null");
        if (operator != RegexpOp.STAR && operator != RegexpOp.PLUS && operator != RegexpOp.QUEST) {
            throw new IllegalArgumentException("operator must be STAR/PLUS/QUEST");
        }
        return new Regexp(operator, parseFlags, List.of(child), 0, null, null, 0, null, 0, 0, 0);
    }

    private static Regexp starPlusOrQuest(RegexpOp operator, int parseFlags, Regexp child)
    {
        // Squash **, ++ and ??.
        if (operator == child.op() && parseFlags == child.parseFlags()) {
            return child;
        }

        // Squash *+, *?, +*, +?, ?* and ?+. They all squash to *.
        if ((child.op() == RegexpOp.STAR || child.op() == RegexpOp.PLUS || child.op() == RegexpOp.QUEST) &&
                parseFlags == child.parseFlags()) {
            if (child.op() == RegexpOp.STAR) {
                return child;
            }

            // Rewrite child to STAR.
            return new Regexp(RegexpOp.STAR, parseFlags, List.of(child.child(0)), 0, null, null, 0, null, 0, 0, 0);
        }

        return new Regexp(operator, parseFlags, List.of(child), 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp repeat(int parseFlags, Regexp child, int min, int max)
    {
        requireNonNull(child, "child is null");
        if (min < 0) {
            throw new IllegalArgumentException("min must be >= 0");
        }
        if (max != -1 && max < min) {
            throw new IllegalArgumentException("max must be -1 or >= min");
        }
        return new Regexp(RegexpOp.REPEAT, parseFlags, List.of(child), 0, null, null, 0, null, min, max, 0);
    }

    static Regexp concat(int parseFlags, List<Regexp> children)
    {
        requireNonNull(children, "children is null");
        if (children.size() < 2) {
            throw new IllegalArgumentException("concat requires >= 2 children");
        }
        return new Regexp(RegexpOp.CONCAT, parseFlags, children, 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp alternate(int parseFlags, List<Regexp> children)
    {
        requireNonNull(children, "children is null");
        if (children.size() < 2) {
            throw new IllegalArgumentException("alternate requires >= 2 children");
        }
        return new Regexp(RegexpOp.ALTERNATE, parseFlags, children, 0, null, null, 0, null, 0, 0, 0);
    }

    static Regexp haveMatch(int parseFlags, int matchId)
    {
        if (matchId < 0) {
            throw new IllegalArgumentException("matchId must be >= 0");
        }
        return new Regexp(RegexpOp.HAVE_MATCH, parseFlags, List.of(), 0, null, null, 0, null, 0, 0, matchId);
    }

    static final int MAX_REPEAT = 1000;

    static int maxRune(int parseFlags)
    {
        return (parseFlags & LATIN1) == 0 ? RUNEMAX : 0xFF;
    }

    @Override
    public boolean equals(Object other)
    {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Regexp right)) {
            return false;
        }
        if (op != right.op) {
            return false;
        }
        if (equalityFlags() != right.equalityFlags()) {
            return false;
        }

        return switch (op) {
            case NO_MATCH, EMPTY_MATCH, ANY_CHAR, ANY_BYTE, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT -> true;
            case LITERAL -> rune == right.rune;
            case LITERAL_STRING -> Arrays.equals(runes, right.runes);
            case CHAR_CLASS -> Objects.equals(charClass, right.charClass);
            case CAPTURE -> captureIndex == right.captureIndex && Objects.equals(name, right.name) && Objects.equals(child(0), right.child(0));
            case REPEAT -> min == right.min && max == right.max && Objects.equals(child(0), right.child(0));
            case HAVE_MATCH -> matchId == right.matchId;
            case STAR, PLUS, QUEST -> Objects.equals(child(0), right.child(0));
            case CONCAT, ALTERNATE -> Arrays.equals(children, right.children);
        };
    }

    @Override
    public int hashCode()
    {
        int result = 31 * op.hashCode() + equalityFlags();

        return switch (op) {
            case NO_MATCH, EMPTY_MATCH, ANY_CHAR, ANY_BYTE, BEGIN_LINE, END_LINE, WORD_BOUNDARY, NO_WORD_BOUNDARY, BEGIN_TEXT, END_TEXT -> result;
            case LITERAL -> 31 * result + rune;
            case LITERAL_STRING -> 31 * result + Arrays.hashCode(runes);
            case CHAR_CLASS -> 31 * result + Objects.hashCode(charClass);
            case CAPTURE -> {
                result = 31 * result + captureIndex;
                result = 31 * result + Objects.hashCode(name);
                yield 31 * result + Objects.hashCode(child(0));
            }
            case REPEAT -> {
                result = 31 * result + min;
                result = 31 * result + max;
                yield 31 * result + Objects.hashCode(child(0));
            }
            case HAVE_MATCH -> 31 * result + matchId;
            case STAR, PLUS, QUEST -> 31 * result + Objects.hashCode(child(0));
            case CONCAT, ALTERNATE -> 31 * result + Arrays.hashCode(children);
        };
    }

    private int equalityFlags()
    {
        return switch (op) {
            case BEGIN_LINE -> {
                if ((parseFlags & RUST_ASSERTION) != 0) {
                    yield RUST_ASSERTION;
                }
                if ((parseFlags & TRINO_LINE) != 0) {
                    yield TRINO_LINE;
                }
                if ((parseFlags & JAVA_LINE) == 0) {
                    yield 0;
                }
                yield parseFlags & (JAVA_LINE | JAVA_UNIX_LINES);
            }
            case END_LINE -> parseFlags & (JAVA_LINE | RUST_ASSERTION);
            case END_TEXT -> parseFlags & (WAS_DOLLAR | FINAL_LINE_END | JAVA_FINAL_END);
            case WORD_BOUNDARY, NO_WORD_BOUNDARY -> parseFlags &
                    (UNICODE_WORD_BOUNDARY | JAVA_WORD_BOUNDARY | JAVA_UNICODE_CHARACTER_CLASS | RUST_ASSERTION | RUST_WORD_END | RUST_WORD_HALF);
            case LITERAL, LITERAL_STRING -> parseFlags & (FOLD_CASE | FULL_CASE_FOLD | LATIN1);
            // A Rust byte class is not the class of the same code points.
            case CHAR_CLASS -> parseFlags & LATIN1;
            case STAR, PLUS, QUEST, REPEAT -> parseFlags & NON_GREEDY;
            default -> 0;
        };
    }

    private static Regexp[] childArray(List<Regexp> children)
    {
        if (children instanceof ChildList existing) {
            return existing.values;
        }
        if (children.isEmpty()) {
            return NO_CHILDREN;
        }
        Regexp[] values = children.toArray(new Regexp[0]);
        for (Regexp value : values) {
            requireNonNull(value, "child is null");
        }
        return values;
    }

    /// Read-only list view of a node's children. Nodes keep the bare array so the view costs
    /// nothing when retained; building a node from another node's view reuses the array.
    private static final class ChildList
            extends AbstractList<Regexp>
            implements RandomAccess
    {
        private final Regexp[] values;

        private ChildList(Regexp[] values)
        {
            this.values = values;
        }

        @Override
        public Regexp get(int index)
        {
            return values[index];
        }

        @Override
        public int size()
        {
            return values.length;
        }
    }
}
