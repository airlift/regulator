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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestCompilerRuneCache
{
    private static final long MINIMUM_PROGRAM_MEMORY = 1_048;

    @Test
    public void testUnicodeCharacterClassProgram()
    {
        Regexp regexp = unicodeCharacterClass();

        assertThat(Compiler.compile(regexp, false, 0).dump()).isEqualTo(
                """
                3+ byte [c2-c2] 0 -> 19
                4+ byte [c4-c5] 0 -> 19
                5+ byte [c6-c8] 0 -> 19
                6+ byte [c9-c9] 0 -> 21
                7+ byte [cd-cd] 0 -> 22
                8+ byte [ce-cf] 0 -> 19
                9+ byte [d0-d3] 0 -> 19
                10+ byte [d4-d4] 0 -> 23
                11+ byte [d6-d6] 0 -> 24
                12+ byte [d7-d7] 0 -> 19
                13+ byte [d8-db] 0 -> 19
                14+ byte [e0-e0] 0 -> 25
                15+ byte [e3-e3] 0 -> 26
                16+ byte [e4-e4] 0 -> 31
                17+ byte [e5-e9] 0 -> 32
                18. byte [f0-f0] 0 -> 33
                19. byte [80-bf] 0 -> 20
                20. match! 0
                21. byte [80-8f] 0 -> 20
                22. byte [b0-bf] 0 -> 20
                23. byte [80-af] 0 -> 20
                24. byte [90-bf] 0 -> 20
                25. byte [a4-a5] 0 -> 19
                26+ byte [81-81] 0 -> 19
                27+ byte [82-82] 0 -> 29
                28. byte [83-83] 0 -> 19
                29+ nop -> 43
                30. byte [a1-bf] 0 -> 20
                31. byte [b8-bf] 0 -> 19
                32. byte [80-bf] 0 -> 19
                33+ byte [90-90] 0 -> 37
                34+ byte [9f-9f] 0 -> 40
                35+ byte [a0-a9] 0 -> 32
                36. byte [aa-aa] 0 -> 41
                37+ byte [80-83] 0 -> 19
                38+ byte [90-90] 0 -> 19
                39. byte [91-91] 0 -> 21
                40. byte [8c-97] 0 -> 19
                41+ byte [80-9a] 0 -> 19
                42. byte [9b-9b] 0 -> 43
                43. byte [80-9f] 0 -> 20
                """);

        assertThat(Compiler.compile(regexp, true, 0).dump()).isEqualTo(
                """
                3+ byte [80-bf] 1 -> 10
                4+ byte [80-8f] 2 -> 36
                5+ byte [b0-bf] 2 -> 38
                6+ byte [80-af] 1 -> 39
                7+ byte [90-bf] 1 -> 40
                8+ byte [80-9f] 0 -> 41
                9. byte [a1-bf] 0 -> 43
                10+ byte [c2-c2] 0 -> 26
                11+ byte [c4-c5] 0 -> 26
                12+ byte [c6-c8] 0 -> 26
                13+ byte [ce-cf] 0 -> 26
                14+ byte [d0-d3] 0 -> 26
                15+ byte [d7-d7] 0 -> 26
                16+ byte [d8-db] 0 -> 26
                17+ byte [a4-a5] 4 -> 27
                18+ byte [81-81] 3 -> 28
                19+ byte [83-83] 2 -> 28
                20+ byte [b8-bf] 1 -> 29
                21+ byte [80-bf] 1 -> 30
                22+ byte [80-83] 3 -> 33
                23+ byte [90-90] 1 -> 33
                24+ byte [8c-97] 1 -> 34
                25. byte [80-9a] 0 -> 35
                26. match! 0
                27. byte [e0-e0] 0 -> 26
                28. byte [e3-e3] 0 -> 26
                29. byte [e4-e4] 0 -> 26
                30+ byte [e5-e9] 0 -> 26
                31. byte [a0-a9] 0 -> 32
                32. byte [f0-f0] 0 -> 26
                33. byte [90-90] 0 -> 32
                34. byte [9f-9f] 0 -> 32
                35. byte [aa-aa] 0 -> 32
                36+ byte [c9-c9] 0 -> 26
                37. byte [91-91] 0 -> 33
                38. byte [cd-cd] 0 -> 26
                39. byte [d4-d4] 0 -> 26
                40. byte [d6-d6] 0 -> 26
                41+ nop -> 43
                42. byte [9b-9b] 0 -> 35
                43. byte [82-82] 0 -> 28
                """);
    }

    @Test
    public void testUnicodeCharacterClassMemoryBudget()
    {
        Regexp regexp = unicodeCharacterClass();

        for (boolean reversed : new boolean[] {false, true}) {
            assertThatThrownBy(() -> Compiler.compile(regexp, reversed, MINIMUM_PROGRAM_MEMORY - 1))
                    .isInstanceOf(RegexpCompileMemoryLimitException.class);
            assertThat(Compiler.compile(regexp, reversed, MINIMUM_PROGRAM_MEMORY).size()).isEqualTo(44);
        }
    }

    private static Regexp unicodeCharacterClass()
    {
        RuneRange[] ranges = {
                new RuneRange(0x80, 0xBF),
                new RuneRange(0x100, 0x17F),
                new RuneRange(0x180, 0x24F),
                new RuneRange(0x370, 0x3FF),
                new RuneRange(0x400, 0x4FF),
                new RuneRange(0x500, 0x52F),
                new RuneRange(0x590, 0x5FF),
                new RuneRange(0x600, 0x6FF),
                new RuneRange(0x900, 0x97F),
                new RuneRange(0x3040, 0x309F),
                new RuneRange(0x30A1, 0x30FF),
                new RuneRange(0x4E00, 0x9FFF),
                new RuneRange(0x10000, 0x100FF),
                new RuneRange(0x10400, 0x1044F),
                new RuneRange(0x1F300, 0x1F5FF),
                new RuneRange(0x20000, 0x2A6DF),
        };
        int runeCount = 0;
        for (RuneRange range : ranges) {
            runeCount += range.high() - range.low() + 1;
        }
        return Regexp.charClass(Regexp.LIKE_PERL, new CharClass(false, runeCount, ranges));
    }
}
