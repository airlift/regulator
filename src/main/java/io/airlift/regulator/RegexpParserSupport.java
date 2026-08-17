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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static io.airlift.regulator.Regexp.MAX_REPEAT;

/**
 * Language-neutral mechanics shared by the regexp parsers. A helper belongs here only while every
 * caller intentionally has the same semantics; dialect-specific behavior stays in the owning parser.
 */
final class RegexpParserSupport
{
    private RegexpParserSupport() {}

    static Regexp makeLiteralString(int flags, List<Integer> runes)
    {
        if (runes.size() == 1) {
            return Regexp.literal(flags, runes.getFirst());
        }
        int[] literalRunes = new int[runes.size()];
        for (int i = 0; i < runes.size(); i++) {
            literalRunes[i] = runes.get(i);
        }
        return Regexp.literalString(flags, literalRunes);
    }

    static boolean wouldExceedRepeatLimit(Regexp atom, int min, int max)
    {
        int maxRepetitionCount = (max < 0) ? min : max;
        if (maxRepetitionCount <= 1) {
            return false;
        }
        int budget = MAX_REPEAT / maxRepetitionCount;
        return repetitionBudget(atom, budget) == 0;
    }

    private static int repetitionBudget(Regexp regexp, int budget)
    {
        int minimumBudget = budget;
        Deque<RepetitionFrame> pending = new ArrayDeque<>();
        pending.push(new RepetitionFrame(regexp, budget));
        while (!pending.isEmpty()) {
            RepetitionFrame frame = pending.pop();
            int remainingBudget = frame.budget();
            Regexp current = frame.regexp();
            if (current.op() == RegexpOp.REPEAT) {
                int maxRepetitionCount = (current.max() < 0) ? current.min() : current.max();
                if (maxRepetitionCount > 0) {
                    remainingBudget /= maxRepetitionCount;
                    if (remainingBudget == 0) {
                        return 0;
                    }
                }
            }

            minimumBudget = Math.min(minimumBudget, remainingBudget);
            for (Regexp child : current.children()) {
                pending.push(new RepetitionFrame(child, remainingBudget));
            }
        }
        return minimumBudget;
    }

    private record RepetitionFrame(Regexp regexp, int budget) {}

    static void addGroup(CharClassBuilder targetClassBuilder, CharClass group, int sign, int parseFlags)
    {
        if (sign == 1) {
            targetClassBuilder.addCharClass(group, parseFlags);
            return;
        }

        if ((parseFlags & Regexp.FOLD_CASE) != 0) {
            // Negating a case-folded group requires a two-step approach.
            CharClassBuilder temporaryBuilder = new CharClassBuilder();
            addGroup(temporaryBuilder, group, 1, parseFlags);
            boolean excludeNewline = ((parseFlags & Regexp.CLASS_NEWLINE) == 0) || ((parseFlags & Regexp.NEVER_NEWLINE) != 0);
            if (excludeNewline) {
                temporaryBuilder.addRange('\n', '\n');
            }
            temporaryBuilder.negate();

            CharClass negatedClass = temporaryBuilder.toCharClass();
            for (RuneRange runeRange : negatedClass.ranges()) {
                // Add the already-folded/negated result directly.
                targetClassBuilder.addRange(runeRange.low(), runeRange.high());
            }
            return;
        }

        int next = 0;
        for (RuneRange runeRange : group.ranges()) {
            if (next < runeRange.low()) {
                targetClassBuilder.addRangeFlags(next, runeRange.low() - 1, parseFlags);
            }
            next = runeRange.high() + 1;
        }
        if (next <= Regexp.RUNEMAX) {
            targetClassBuilder.addRangeFlags(next, Regexp.RUNEMAX, parseFlags);
        }
    }

    static int decodeHexDigit(int value)
    {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        return -1;
    }

    static void addFoldedRangeLatin1(CharClassBuilder characterClassBuilder, int low, int high)
    {
        // Only folds ASCII letters A-Z and a-z (Latin-1 mode treats bytes).
        if (low > high) {
            return;
        }

        // Already clamped to Latin-1 in the parser when LATIN1 is set.
        for (int rune = low; rune <= high; rune++) {
            if ('A' <= rune && rune <= 'Z') {
                characterClassBuilder.addRange(rune, rune);
                characterClassBuilder.addRange(rune + ('a' - 'A'), rune + ('a' - 'A'));
            }
            else if ('a' <= rune && rune <= 'z') {
                characterClassBuilder.addRange(rune, rune);
                characterClassBuilder.addRange(rune - ('a' - 'A'), rune - ('a' - 'A'));
            }
            else {
                characterClassBuilder.addRange(rune, rune);
            }
        }
    }
}
