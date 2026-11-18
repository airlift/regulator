# AWS diagnostic jobs, round UAP-3: the r8i loop-shape D-cell for F1 and F3

Written 2026-11-18 00:50 PST on dain-1. **I wrote every prediction below before anything ran.**

For the coordinator to run on AWS Spot after UAP-2 closes: one r8i host, untimed. Every number is a counter or a disassembly; no row is a benchmark score. Nothing in this round runs on dain-1 or the Mac.

## Why

UAP-2's D-cell (`user/dain/uap-core-dcell`, `fb4e99c2e`) and the paired investigation (`uap-core-manymatch-fastmode.verdict.md`) settled two things.
- `searchMany`'s r8i loop shape depends on its tier-4 compile. If the compile is queued at fewer than 40 invocations (A 38, C-fast 31), branch probabilities stay unknown and `stateOffset` stays in a register. At 40 or more (C 45 and 47, D 54), C2 prunes cold branches into traps and spills `stateOffset` to the stack every byte (≈ 115 µs against 72).
- C-fast's "+2 instructions per byte" came from perfnorm forks that were never disassembled. Their instructions per op fall below the loop size × 32,768, so the loop they ran is unknown.

This round tests two candidate fixes. Each fork records the loop it ran and the counts it ran with.

## Legs

| Leg | Commit | Branch | What it is |
|---|---|---|---|
| A | `80bccc124` | `user/dain/uap-core` | main `bdf26dc33` plus the bench; the same as UAP-1 and UAP-2 |
| C′ | `94cf51bf6` | `user/dain/uap-core-dense` | `c46ea3278` (U1b, U2, bench) plus the match-dense row. Its `src/main` is identical to C's |
| C′+F1 | `8883e8144` | `user/dain/uap-core-f1` | C′ plus F1: `searchMany`'s and `searchManyFull`'s per-byte loops first step plain transitions through the existing call-free `stepSelfLoopRearmBytes`, and only match, uncomputed, dead and reset transitions take the full body |
| C′+F3 | `f5042eece` | `user/dain/uap-core-f3` | C′ plus F3: the U1b early return after the loop becomes a guard around the end-of-text step, with one shared return as in A |

Every leg passes `TestRe2Set`, `TestFilteredRe2` and six other DFA test classes, 110 tests, with the module checks on (dain-1).

## Host and conventions

- One r8i.large Spot host. Same image as UAP-2's D-cell: Temurin 25.0.4+7, perf, `hsdis-amd64.so` in `$JAVA_HOME/lib/server`.
- UAP-2's `dcell.sh` (in `fb4e99c2e`) is the template.
- The legs' bundles sit at paths of equal length.
- Interleave legs fork by fork, in the order A, C′, F1, F3, then repeat.
- Every fork uses `-f 1 -t 1` and the class's own warmup and measurement, the template JVM options (native mode), and a unique `$OUT` prefix per leg, profiler and fork:

```
JVM_ARGS='--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch'
```

### Perfasm fork

```
"$JAVA" -Xms64m -Xmx256m -cp "$CP" org.openjdk.jmh.Main "$ROW" \
  -p patternCount=16 -p sourceLength=32768 -t 1 -f 1 -jvmArgs "$JVM_ARGS" \
  -prof "perfasm:intelSyntax=true;events=cycles,instructions;top=3;saveLog=true;saveLogToFile=$OUT.perfasm" \
  -rf json -rff "$OUT.json"
```

### Perfnorm fork, with the same fork's compile log and assembly

```
"$JAVA" -Xms64m -Xmx256m -cp "$CP" org.openjdk.jmh.Main "$ROW" \
  -p patternCount=16 -p sourceLength=32768 -t 1 -f 1 \
  -jvmArgs "$JVM_ARGS -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=$OUT.xml -XX:+PrintAssembly -XX:PrintAssemblyOptions=intel" \
  -prof 'perfnorm:events=cycles,instructions,r0403' \
  -rf json -rff "$OUT.json"
```

Keep every fork's console log, JSON, `.perfasm` or `.xml` (zstd), and its leg, host and JDK metadata, outliers included. Never pair one fork's assembly with another fork's counts.

## D1: loop shape, `matchingSetPatternIds`

`ROW='^io.airlift.regulator.BenchmarkPatternCollections\.matchingSetPatternIds$'`. For each of the 4 legs: **4 perfasm forks and 3 perfnorm forks**, 28 forks.

## D2: F1's risk case, `matchingSetPatternIdsMatchDense`

`ROW='^io.airlift.regulator.BenchmarkPatternCollections\.matchingSetPatternIdsMatchDense$'`.
- The text cycles `prefix<i>-123 ` over the 16 patterns, so the search keeps entering match states.
- For C′ and F1: **2 perfasm forks each**, as asked. I added **2 perfnorm forks each**, because instructions and cycles per byte need a per-op count, and perfasm doesn't give one. That makes 8 forks.

**Exits per byte** depends only on the DFA's transitions on this text, not on the CPU. I counted it on dain-1 with an untimed instrumented copy of F1. At 16 patterns and 32,768 bytes, the inner loop exits **7,944 times per op once warm (0.242 per byte)**, and 7,998 on the first op. On the existing dots text it exits 3 times per op (0.0001 per byte). AWS only needs to report the instructions and cycles per byte that go with it.

## What to record

### One row per fork

| Column | Source |
|---|---|
| Fork, leg, profiler, µs/op (diagnostic only) | JSON |
| **Mode check:** the standard (non-OSR) tier-4 `<task>` for `Dfa searchMany`: compile id, `count` (invocations when queued; compare with 40), `backedge_count` | the fork's own `.xml` or `.perfasm` log |
| Earlier OSR compile ids, and any `make_not_entrant` or `uncommon_trap` that targets the hot compile | same |
| Hot nmethod: compile id, OSR or standard, and the method whose code it is. For F1, say whether `stepSelfLoopRearmBytes` is inlined into `searchMany` or hot in its own nmethod | perfasm region headers; for perfnorm forks, the assembly in the `.xml` matched to the hot loop by address |
| Byte loop: instructions per byte (divide by the bytes per iteration if C2 unrolled it), start address mod 64, `[rsp+…]` loads and stores inside the loop, **whether `stateOffset` stays in a register** | the hot region (perfasm), or the same fork's assembly (perfnorm) |
| Cycles per op, instructions per op, cycles per byte (÷ 32,768), IPC, r0403 per op | perfnorm |
| **Floor check:** instructions per op ≥ loop instructions per byte × 32,768? If not, the disassembled loop is not the one that ran; say so | perfnorm fork's own assembly |

For F1, the loop that matters is the inner helper loop. Also report the cycle share of the outer body, and on D2 the instructions and cycles of the code around each exit.

### Summary lines

- **Mode check:** for each leg, how many forks queued the tier-4 compile below 40 and how many at 40 or above, and which shape each got. Did the shape follow the count in every fork?
- **F1 trigger:** F1 moves the per-byte backedges into the helper, so `searchMany`'s tier-4 compile should be invocation-triggered, at about 4,353 invocations. Report each F1 fork's `count`. Then write **one line saying whether the trigger held (count ≥ 40, near 4,353) in every F1 fork**, naming any fork where it didn't.
- **D2:** for C′ and F1, the median instructions and cycles per byte, the F1/C′ ratio of each, and exits per byte (0.242).

## Predictions (written before the run)

| Leg / job | Prediction | Wrong if |
|---|---|---|
| A, D1 | Fast shape in at least 6 of 7 forks: `stateOffset` in a register, about 42 instructions, 8.3–8.8 cycles per byte. Its compile count sits near the threshold (38 in UAP-2), so a slow A fork is possible and would be news | any A fork spills `stateOffset` with a count below 40, which would mean the threshold isn't the switch |
| C′, D1 | Bimodal: the shape follows the count. Fast forks match A's shape. Slow forks spill `stateOffset` to `[rsp+…]` at about 14 cycles per byte. At least 1 of 7 is slow, since perfasm forks drew slow 2 of 3 times in UAP-2 | any fork's shape contradicts its count |
| C′, D1 fast forks | Instructions per op within 1% of A once the floor check passes. The "+2 per byte" was an artifact of undisassembled forks | C′ fast forks run ≥ 3% more instructions per op than A, with the floor check passing in both |
| F1, D1 | Every one of 7 forks: the hot loop is the inline helper loop, 13–16 instructions per byte, no `[rsp]` operand, `stateOffset` in a register. 6.0–8.5 cycles per byte, at most A's | any F1 fork spills `stateOffset` or has a stack operand in the helper loop, or runs more cycles per byte than A's median × 1.03 |
| F1 trigger | Tier-4 `count` ≥ 40 (about 4,353) in every F1 fork. The shape stays good anyway, because the helper loop carries only `stateOffset` and `position` | any F1 fork compiles below 40, or the shape differs between F1 forks |
| F3, D1 | Bimodal like C′: F3 doesn't change when the compile triggers. Fast forks match A's shape and instructions per op within 1% | F3 shows one shape in every fork, or its fast forks differ from A by ≥ 3% instructions per op |
| D2, F1/C′ | 0.242 exits per byte. Each exit costs F1 a helper return, a repack and the outer body's transition reload, about 10–20 instructions. F1/C′ instructions per byte 1.00–1.15, cycles per byte 0.95–1.15 | F1/C′ cycles per byte above 1.15 |

### What decides

- **F1 wins the shape** if every F1 fork keeps `stateOffset` in a register with no stack operands in the hot loop, at or below A's cycles per byte, and D2 stays inside 1.15. Then, as the coordinator set out, F1 gets the review cycle, then the match-dense guard row and the full timed round.
- **F3** is evidence for the cause, not a fix: if it stays bimodal, the end-of-text control flow isn't the switch.
- If F1 misses, I bring the D-cell back to investigation before proposing anything else.

## Estimate

| Job | Forks |
|---|---|
| D1: 4 legs × (4 perfasm + 3 perfnorm) | 28 |
| D2: 2 legs × (2 perfasm + 2 perfnorm) | 8 |
| **Total** | **36 forks on one r8i.large Spot host** |

- About 20 s of fork time and 30–60 s of perf and assembly processing per fork, so about 45 minutes of host time.
- Add about 15 minutes for host prep and four leg builds: about 1 hour of r8i.large Spot in all.
- **Estimate ≈ $0.06; guard cap $0.15.**

If the cap nears, drop in this order:
1. D2's perfnorm forks (keep its perfasm);
2. the fourth perfasm fork of A and F3.

D1's C′ and F1 forks are never dropped.
