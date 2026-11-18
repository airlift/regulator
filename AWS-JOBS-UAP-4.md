# AWS diagnostic jobs, round UAP-4: out-of-line many-match scanners K1 and S1 (PR #279, untimed)

Written 2026-11-18 ~02:25 PST on dain-1. **Every prediction below was written before anything ran.** The coordinator approved the plan, including the r9g cell, the K1 reset-ordering test and the size pins (2026-11-18).

For the coordinator to run on AWS Spot after UAP-2's close (the hold has $0.756 left). One r8i host and one r9g host. Every number is a counter or a disassembly. µs/op is diagnostic only, and nothing runs on dain-1 or the Mac.

## Why

UAP-3 and its paired investigation (`uap-core-manymatch-uap3.verdict.md`, both hit) found three things.
- **The register limit.** `Dfa.searchMany`'s byte loop sits at x86's register limit. About 12 values are live across it, and calls in the loop nest force stack homes.
- **Graph sensitivity.** Small differences in C2's graph for the whole method decide whether the loop-carried state is stored to the stack and reloaded every byte. These differences include profile pruning of the once-per-search branches, whether `endSearch` is inlined, and the `SparseSet.createIndex` call on match-dense text. No count decides it.
- **F1's failure.** F1 put the scan in a helper, but the helper (66 bytecodes) was inlined. On match-dense text it spilled the state too.

K1 and S1 move the scan into its own method, which C2 never inlines because it is larger than `FreqInlineSize` (325 bytecodes). Its registers then come from its own few live values.

**What a D-cell measures:** it shows which shapes C2 can reach. It doesn't show how often each shape occurs in timed forks. In UAP-2 the slow C loop appeared only under perfasm (2 of 3 forks), and never in 96 timed forks.

## Legs (all contain main `bdf26dc33`)

| Leg | Commit | Branch | What it is |
|---|---|---|---|
| A | `80bccc124` | `user/dain/uap-core` | main + bench (as in UAP-1–3) |
| A′ | `abcd2705d` | `user/dain/uap-core-aprime` | A + the match-dense row (C′'s bench diff, line for line). This is main's baseline on match-dense text |
| C′ | `94cf51bf6` | `user/dain/uap-core-dense` | U1b + U2 + bench + the match-dense row (as in UAP-3) |
| S1 | `ba0c22030` | `user/dain/uap-core-s1` | C′; `searchMany` first calls `scanPlainManyBytes`, which stops before any match or sentinel transition. The existing body handles the stop. The scanner is unrolled ×6 |
| K1 | `13508335b` | `user/dain/uap-core-k1` | C′; `searchMany` first calls `scanManyBytes`, which steps through match transitions (see below). The scanner is unrolled ×4 |

**K1's matches:**
- The scanner appends each matched state into a 64-state buffer, skipping a state equal to the one it appended last.
- It stops only at a sentinel, at the end, or when the buffer is full.
- The caller records the buffered states from the same tables before handling the stop. One `int[65]` is allocated per call, about 280 bytes per op.
- `TestRe2Set.testMatchingPatternIdsRecordsScannedMatchesBeforeCacheReset` forces a cache reset at the stop right after a scan that buffered matches, including a buffer-full stop. It asserts the match set is exactly {0, 1, 2, 3} and that the reset count rose by one.
- In a throwaway instrumented copy, the test reached 4 buffered returns, 2 buffer-full stops and 1 reset directly after a buffered return.
- With the drain moved after the stop handling, the test fails, because the drain reads stale states after the reset.

**Size pins:** each scanner has one pin in `TestHotMethodSize.testManyMatchByteScanStaysOutOfLineAtMeasuredSize`, in the form of `testPrefixScanStaysOutOfLineAtMeasuredSize` (`isGreaterThan(FREQ_INLINE_SIZE)`, `isLessThanOrEqualTo(measured)`).
- **The margin over 325 was measured on each leg's own build:** `scanPlainManyBytes` 348 bytecodes (S1), `scanManyBytes` 352 (K1).
- ×4 unrolling gave S1 only 258, so S1 is unrolled ×6. Keep the different unroll factors in mind when comparing S1 with K1.

**Tests:** A′ ran 97, S1 104 and K1 105 tests, with the module checks on (dain-1). These covered TestRe2Set, TestFilteredRe2, TestHotMethodSize, TestDfaCopyOnResetStress, TestDfaStartStateFailure, TestRetainedSize and TestBaselinePublicApiBenchmarks.

**Counters (untimed, dain-1, 16 patterns, 32,768 bytes, warm):**

| Leg / text | Scanner calls per op | Calls per byte | Scanned bytes per byte | Bytes handled by the body per op | Buffered states per byte | Buffer-full stops per op |
|---|---|---|---|---|---|---|
| S1 D1 | 3 | 0.00009 | 0.9999 | 3 | – | – |
| S1 D2 | 7,945 | 0.242 | 0.758 | 7,944 | – | – |
| K1 D1 | 1 | 0.00003 | 1.0 | 0 | 0.00003 | 0 |
| K1 D2 | 42 | 0.00128 | 0.9988 | 41 | 0.081 | 41 |

## Rows

- **D1** = `^io.airlift.regulator.BenchmarkPatternCollections\.matchingSetPatternIds$`: dots ending in a match.
- **D2** = `^io.airlift.regulator.BenchmarkPatternCollections\.matchingSetPatternIdsMatchDense$`: `prefix<i>-123 ` cycled over the patterns.

Both use `-p patternCount=16 -p sourceLength=32768`.

## Commands and conventions

The same as UAP-3 (`AWS-JOBS-UAP-3.md`, kit `ac68dda73`):
- Temurin 25.0.4+7 and the template JVM options (native mode);
- `-f 1 -t 1`, with the class's own warmup and measurement;
- legs interleaved fork by fork, bundles at paths of equal length;
- a unique `$OUT` per leg, row, profiler and fork.

The two fork kinds:
- **Perfasm fork:** `-prof "perfasm:intelSyntax=true;events=cycles,instructions;top=3;saveLog=true;saveLogToFile=$OUT.perfasm"`. On r9g, drop `intelSyntax=true`; that host needs `hsdis-aarch64.so`. If hsdis is missing there, skip the r9g perfasm forks and say so.
- **Perfnorm fork:** add `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=$OUT.xml -XX:+PrintAssembly` to the JVM arguments, plus `-XX:PrintAssemblyOptions=intel` on r8i, and use `-prof 'perfnorm:events=cycles,instructions'` (add `,r0403` on r8i).

Keep every fork's console, JSON, and `.perfasm` or `.xml` (zstd). Never pair one fork's assembly with another fork's counts.

## Cell 1: r8i (one r8i.large Spot host)

| Leg | D1 | D2 |
|---|---|---|
| A | 4 perfasm + 3 perfnorm | — |
| A′ | 3 perfnorm (sanity: A′ = A on D1) | 4 perfasm + 3 perfnorm |
| C′ | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm |
| S1 | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm |
| K1 | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm |

**59 forks.**

## Cell 2: r9g (one r9g.large Spot host)

| Leg | D1 | D2 |
|---|---|---|
| A | 3 perfnorm | — |
| A′ | — | 3 perfnorm |
| S1 | 3 perfnorm + 1 perfasm | 3 perfnorm + 1 perfasm |
| K1 | 3 perfnorm + 1 perfasm | 3 perfnorm + 1 perfasm |

**22 forks.**

## What to record

### One row per fork

| Column | Source |
|---|---|
| Fork, host, leg, row, profiler, µs/op (diagnostic) | JSON |
| `searchMany`'s standard tier-4 `<task>`: compile id, `count`, `backedge_count`, and its earlier OSR id | the fork's own log |
| **Graph class:** whether bci 17 carries `prob='always'` and an `unstable_if` trap; the `endSearch` call site's inline decision | same |
| **Scanner call site (S1, K1):** the `inline_fail` reason at `searchMany`'s call to the scanner. It must read `hot method too big` in every fork. Also the scanner's own compiles (ids, OSR or standard, and `bytes=` from its `<method>` tag, which must equal the pin: 348 or 352) | same |
| Hot nmethod: id, standard or OSR, and the method whose code it is (`searchMany` or the scanner) | perfasm region headers, or for perfnorm forks the scanner's last standard compile matched by address |
| Hot byte loop: instructions per byte (÷ bytes per iteration), start mod 64, stack loads and stores, whether the state is in a register (**shape rule below**), and whether `position` is in a register | the fork's own assembly |
| Cycles per op, instructions per op, cycles per byte (÷ 32,768), IPC, and r0403 per op on r8i | perfnorm |
| Floor check: instructions per op ≥ loop instructions per byte × scanned bytes per op (from the counters above) | the perfnorm fork's own assembly |

### Shape rule (written precisely, per the coordinator)

- **Fail:** V is the value the transitions-array load returns (after any `and 0xbfffffff`) that becomes the next byte's index addend, including V between unrolled copies. A fork fails if V is stored to `[rsp+k]` (or `[sp, #k]` on aarch64) and that slot is read back inside the loop.
- **Pass:** a stack read whose slot holds only values not produced in the iteration (lengths, array references, `end`) passes. So does a store that writes back an unchanged value loaded from the same slot. Count these invariant reloads per byte and report them.
- **Report separately:** xmm or vector-register moves, and a spilled `position`. A spilled `position` is a miss for a candidate, but not a fail, since A's fast loop already keeps `position` on the stack.

### Summary lines

- **Per leg, row and host:** the count of forks per shape, the median cycles per byte, and the median instructions per byte.
- **K1 and S1:** whether the scanner call site read `hot method too big` in every fork, and whether the scanner's `bytes=` equals its pin.
- **r8i:** C′'s graph class against its shape, as a check of UAP-3's finding (C′ only).

## Predictions (written before the run)

### r8i

| Leg / row | Prediction | Wrong if |
|---|---|---|
| A, D1 | Fast in 7 of 7 forks, 42 instructions, 8.6–9.0 cycles per byte (as in UAP-3) | any A fork fails the shape rule |
| A′, D1 | The same as A | A′'s shape or cycles per byte differ from A's by more than 2% |
| A′, D2 | Fast in most forks, at about C′'s fast D2 shape (41 instructions), 13–15 cycles per byte. Main has never been measured on this text | fast in fewer than 4 of 7 forks |
| C′, D1 and D2 | Bimodal as in UAP-3. The shape follows the graph class (bci 17 trap, `endSearch` inlining) in every fork | any fork's shape contradicts its graph class |
| **S1, D1** | In 7 of 7 forks the hot loop is the scanner's own nmethod; the call site reads `hot method too big`; state and `position` stay in registers; 12–17 instructions per byte; 0–1 invariant reloads per byte; **6.5–7.6 cycles per byte (A 8.9)** | any fork fails the shape rule, or the scanner is inlined, or the median is above A's median |
| **S1, D2** | The scanner keeps its shape. But 7,945 calls per op, each with a return, an unpack, and the outer body's handling of the match byte, cost about 25–35 instructions and ~30–45 cycles per exit. That gives **13–17 cycles per byte, near A′** | the median is above A′'s median × 1.05 |
| **K1, D1** | As S1 on D1: own nmethod, `hot method too big`, registers, **12–17 instructions per byte, 6.5–7.6 cycles per byte** | as S1 |
| **K1, D2** | The scanner keeps its shape through match transitions: about 0.24 match branches per byte inside the scanner, plus about 0.081 buffered states per byte drained through `recordMatches`. That gives **18–28 instructions per byte, 8–11.5 cycles per byte, below both A′'s median and C′'s fast fork (≈ 13.8)** | any fork fails the shape rule, or the median is ≥ A′'s median, or ≥ 13.8 |

### r9g

| Leg / row | Prediction | Wrong if |
|---|---|---|
| S1 and K1, D1 | 0.85–1.00 of A's cycles per byte. On aarch64 the scan loop already keeps its state in a register, and one call per op costs nothing on 32,768 bytes | above A × 1.03 (the coordinator's acceptance line) |
| K1, D2 | 0.65–0.95 of A′'s cycles per byte: fewer exits, and the match path inside the scanner | above A′ × 1.03 |
| S1, D2 | 0.95–1.05 of A′'s cycles per byte. The calls per exit cost what the inlined body saves. **The likeliest miss of the round** | above A′ × 1.03 |

## Acceptance

A candidate passes only if all of these hold:
1. On r8i, every fork of the candidate on both rows passes the shape rule. The scanner is never inlined (`hot method too big` in every fork), and its `bytes=` equals its pin.
2. On r8i D1, its median cycles per byte is at or below A's median.
3. On r8i D2, its median cycles per byte is below A′'s median and below C′'s fast fork (≈ 13.8, or this round's C′ fast median if lower).
4. On r9g, on both rows, its median cycles per byte is no more than 3% above A's (D1) or A′'s (D2).

**What follows:**
- If **K1** passes, it gets the review cycle, then #279's timed round. That round carries U2's mode-share check, as the coordinator ruled on 2026-11-18: pool both hosts per pair; fail if one-call's share of slow-mode forks exceeds two-step's at p < 0.05 on two or more pairs.
- If only **S1** passes, the same applies to S1.
- If both pass, K1 is preferred for match-dense text, unless its r9g D2 or its 280-byte allocation per op argues otherwise.
- If neither passes, the evidence goes back to investigation.

## Estimate

| Cell | Forks | Cost |
|---|---|---|
| r8i | 59 | ≈ $0.02 (UAP-3: 36 forks for $0.012 on one r8i.large) |
| r9g | 22 | ≈ $0.01 |
| **Total** | **81** | **≈ $0.03; guard cap $0.09** (r8i $0.06 + r9g $0.03) |

About 1 hour on each host, including prep and five builds (four on r9g).

If the cap nears, drop in this order:
1. the r9g perfasm forks;
2. A′'s D1 sanity forks;
3. C′'s fourth perfasm fork on each row.

The candidates' r8i and r9g perfnorm forks, and A′'s D2 forks, are never dropped.
