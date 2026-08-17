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

import io.airlift.slice.Slices;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class VectorPrefixScannerProbe
{
    private VectorPrefixScannerProbe() {}

    public static void main(String[] arguments)
    {
        boolean expectedVectorApiAvailable = Boolean.parseBoolean(arguments[0]);
        int iterations = arguments.length > 1 ? Integer.parseInt(arguments[1]) : 1;
        byte[] prefix = "Шерлок Холмс".getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[4_096 + prefix.length];
        System.arraycopy(prefix, 0, data, data.length - prefix.length, prefix.length);

        Prog program = new Prog();
        program.configurePrefixAccel(Slices.wrappedBuffer(prefix), false);

        Prog.PrefixAccelStrategy expectedStrategy = expectedVectorApiAvailable
                ? Prog.PrefixAccelStrategy.FUSED_VECTOR
                : Prog.PrefixAccelStrategy.FUSED_SWAR;
        check(program.prefixAccelStrategy(data.length) == expectedStrategy, "unexpected prefix strategy");
        int checksum = 0;
        for (int iteration = 0; iteration < iterations; iteration++) {
            checksum += program.prefixAccel(data, 0, data.length);
        }
        check(checksum == (data.length - prefix.length) * iterations, "prefix scan failed");

        ParseResult parsed = RegexpParser.parse(Slices.utf8Slice("[xy][0-9]{4}"), Regexp.LIKE_PERL);
        Prog smallSetProgram = Compiler.compile(parsed.regexp(), false, 0);
        Dfa.DfaInstance dfa = smallSetProgram.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] smallSetData = new byte[4_096];
        Arrays.fill(smallSetData, (byte) 'a');
        smallSetData[smallSetData.length - 1] = 'y';
        check(dfa.findStartByteCandidate(smallSetData, 0, smallSetData.length) == smallSetData.length - 1, "small byte set scan failed");
        check((dfa.smallByteSetScanCount() > 0) == expectedVectorApiAvailable, "unexpected small byte set strategy");

        ParseResult mixedSetParsed = RegexpParser.parse(Slices.utf8Slice("(?:[A-Z_a-z]+)-([0-9]+)"), Regexp.LIKE_PERL);
        Prog mixedSetProgram = Compiler.compile(mixedSetParsed.regexp(), false, 0);
        Dfa.DfaInstance mixedSetDfa = mixedSetProgram.getCachedDfa(Dfa.DfaInstance.Kind.FIRST_MATCH);
        byte[] mixedSetData = new byte[4_096];
        Arrays.fill(mixedSetData, (byte) '.');
        byte[] mixedSetMatch = "abc-123".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(mixedSetMatch, 0, mixedSetData, mixedSetData.length - mixedSetMatch.length, mixedSetMatch.length);
        check(Dfa.search(mixedSetProgram, Slices.wrappedBuffer(mixedSetData), false, Prog.MatchKind.FIRST_MATCH, true) == mixedSetData.length,
                "mixed byte set scan failed");
        check((mixedSetDfa.mixedByteSetScanCount() > 0) == expectedVectorApiAvailable, "unexpected mixed byte set strategy");

        String suffix = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(20);
        ParseResult selfLoopParsed = RegexpParser.parse(Slices.utf8Slice("\\C*x" + suffix + "$"), Regexp.LIKE_PERL);
        Prog selfLoopProgram = Compiler.compile(selfLoopParsed.regexp(), false, 0);
        byte[] selfLoopWarmupData = new byte[1024 + suffix.length()];
        Arrays.fill(selfLoopWarmupData, (byte) 'a');
        byte[] selfLoopMatch = ("x" + suffix).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(selfLoopMatch, 0, selfLoopWarmupData, selfLoopWarmupData.length - selfLoopMatch.length, selfLoopMatch.length);
        check(Dfa.search(selfLoopProgram, Slices.wrappedBuffer(selfLoopWarmupData), false, Prog.MatchKind.FIRST_MATCH, true) == selfLoopWarmupData.length,
                "self-loop warmup failed");
        byte[] selfLoopData = new byte[128 * 1024];
        Arrays.fill(selfLoopData, (byte) 'a');
        check(Dfa.search(selfLoopProgram, Slices.wrappedBuffer(selfLoopData), false, Prog.MatchKind.FIRST_MATCH, true) == Dfa.SEARCH_NO_MATCH,
                "self-loop exit scan changed the match result");
        Dfa.DfaInstance selfLoopDfa = selfLoopProgram.getCachedDfa(Dfa.DfaInstance.Kind.LONGEST_MATCH);
        check((selfLoopDfa.selfLoopExitByteScanCount() > 0) == expectedVectorApiAvailable, "unexpected self-loop exit strategy");

        System.out.printf("OK %s%n", expectedVectorApiAvailable ? "vector" : "swar");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
