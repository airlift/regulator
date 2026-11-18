# AWS diagnostic jobs, round UAP-6: why #279 loses the r9g OS atom set, and candidates B6 and L6 (untimed)

Written 2026-11-18 ~09:45 PST on dain-1, with L6 and the coordinator's rulings added by 09:55. **Every prediction below was written before anything ran.** The coordinator approved the plan, both candidates, and a $0.10 cap.

For the coordinator to run on AWS Spot inside the uap-core hold (about **$0.40** left): one r9g host and one r8i host. Every result is a counter or a disassembly. µs/op is diagnostic only. Nothing runs on dain-1 or the Mac.

## Why

UAP-5 graded #279 FAIL on one row: `osAtomSetMatchingPatternIds` on UA_TESTS, r9g, H5/A5 1.107. The same row is 0.768 on r8i, and every other K1 row wins on both platforms.

The paired investigation ran untimed counters and JIT logs on dain-1 (verdict pending this round; answers in `~/AgentScratch/regulator/user/dain/uap-core-manymatch/investigator-uap5-os/ANSWER.md` and `~/.codex/delegates/2026-11-18/uap-core-manymatch-uap5-os.gpt-6.1-sol.md`). It found:

- **The counters, per string, all legs:** one `searchMany` call, no retries, resets or computed transitions. H5 adds exactly one `scanManyBytes` call (it always exits at the end of the text), one `int[65]`, and one drain of about 2.3 matched states (OS) or 5.5 (browser). The OS row's own work is small: 51% of strings match nothing, and the `SparseSet` is 371 entries against 945. So K1's fixed cost is a larger share of it.
- **aarch64 gets no per-byte gain.** Main's inline loop already keeps the DFA state in a register there. H5 runs about 15.5 instructions per byte against about 35, but the byte loop is no faster.
- **The fixed cost has three parts** that the two investigators rank differently:
  1. the call: 13 spills and 8 reloads around it, a stack bang, an entry barrier and a return poll;
  2. the zeroing: `int[65]` clears 264 B = 33 words. aarch64 C2 clears inline only below 32 words (`clearArray_imm_reg`, `cnt < BlockZeroingLowLimit >> 3`, default 256 B), so every call runs `Stub::zero_blocks`, which issues DC ZVA after a computed branch on the allocation's alignment. x86 clears the same array inline;
  3. allocation and young-GC pressure (+280 B, +9.2% of the OS row's bytes, +3.7% of the browser row's).
- **H5 is unimodal** because its `searchMany` has no byte back-edges left: it compiles on invocation count (4,230–4,244) and gets one shape every time. A5's compile point races the cold pass (1,558–5,647 invocations), giving two shapes.

This round splits the fixed cost between those parts and tests two fixes. B6 removes part 2. L6 also removes parts 2 and 3 on strings that never match, at the price of one more call on strings that do.

The gap to close is about 115 cycles per string: H5's 739 µs against A5's fork mean of 657 µs, which weighs A5's two modes by their measured share.

## Legs (all contain main `bdf26dc33` and #281's bench)

| Leg | Commit | Branch | What it is |
|---|---|---|---|
| A5 | `b74cd81a5` | `user/dain/uap-core-a5` | main + bench (as in UAP-5) |
| G5 | `63e602f34` | `user/dain/uap-core-g5` | H5 with K1 backed out: `src/main` = #279's `081582448` (U1b + U2) |
| H5 | `39210e764` | `user/dain/uap-core-h5` | #279 head `28734c05e` + bench (as in UAP-5) |
| H5z | `39210e764` | (H5's jar) | H5 run with `-XX:BlockZeroingLowLimit=512` appended: the same code with `int[65]` cleared inline. Isolates part 2 with no code change |
| B6 | `d6e9d53c8` | `user/dain/uap-core-b6` | H5 with `MANY_MATCH_SCAN_STATES` 64 → 60: `int[61]`, 248 B = 31 words cleared, inline. The scanner's bytecode is unchanged; only the constant in `searchMany` moves. The uap-core buffers never hold more than 22 states |
| L6 | `6323883eb` | `user/dain/uap-core-l6` | B6 with lazy allocation. `searchMany` passes no buffer until its own step first enters a matching state. The scanner treats a missing buffer as capacity 0, so it stops before the first match exactly as it does at buffer full. Strings with no match allocate nothing; strings with matches make one more scanner call |

Checked on dain-1 (no timing):
- **B6 tests:** 102 tests across TestRe2Set, TestFilteredRe2, TestHotMethodSize, TestRetainedSize, TestDfaCopyOnResetStress, TestBaselinePublicApiBenchmarks and TestBenchmarkUapCore, with the module checks on; none needed changes.
- **Zeroing, in the final tier-4 `searchMany`** (untimed OS driver, PrintAssembly decoded with objdump):
  - **H5:** `int[65]` clears 33 words (`mov x11,#0x21`) through `bl Stub::zero_blocks`.
  - **B6:** `int[61]` clears with 15 `stp xzr, xzr` and 1 `str xzr`, with no call.
  - **H5 with `-XX:BlockZeroingLowLimit=512`:** no stub call; an inline loop of 8 `stp` plus a `str`.
  - One other stub call remains in both legs, clearing a new `DfaInstance` on a cache-miss branch, not per call.
- **Bytes per string** (`getThreadAllocatedBytes`, one warm pass): H5 3,338.4 and B6 3,322.4 on OS; 7,945.2 and 7,929.2 on browser. That is −16 B per string, as `int[65]` is 280 B and `int[61]` is 264 B.
- **L6 tests:** the same 102 tests pass. The scanner pin in `TestHotMethodSize` moves from 352 to 370 bytecodes, still above `FreqInlineSize` (325).
- **Reset test:** `TestRe2Set`'s reset test still forces a cache reset right after a scan that buffered matches, traced in a throwaway instrumented copy at both 30 and 60 repeats.
- **L6 counters per string** (warm, untimed; checksums equal H5's):

  | | scanner calls | allocations | buffered states | `recordMatches` |
  |---|---|---|---|---|
  | OS | 1.488 (H5 1.000) | 0.491 | 1.813 | 2.304 (H5 2.295) |
  | browser | 1.976 (H5 1.000) | 0.999 | 4.606 | 5.605 (H5 5.537) |

  The extra `recordMatches` calls come from a state that the search's own step records and the next scan appends again: the scan's repeat check starts fresh on each call.

## Conventions

As UAP-4 (`AWS-JOBS-UAP-4.md`):
- Temurin 25.0.4+7, native mode, UAP-1's JVM flags (`--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`).
- `-f 1 -t 1`, with each class's own warmup and measurement.
- Legs interleaved fork by fork and rotated per round (round 1 A5 G5 H5 H5z B6 L6, round 2 starting at G5, round 3 at H5), bundles at paths of equal length, a unique `$OUT` per leg, row, profiler and fork.
- **Perfnorm fork:** `-prof 'perfnorm:events=cycles,instructions,branch-misses,L1-dcache-load-misses' -prof gc`, with `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=$OUT.xml -XX:+PrintAssembly` appended to the JVM arguments (plus `-XX:PrintAssemblyOptions=intel` and `,r0403` in the events on r8i).
- **Perfasm fork:** `-prof "perfasm:events=cycles,instructions;top=3;saveLog=true;saveLogToFile=$OUT.perfasm"` (`intelSyntax=true` on r8i). r9g needs `hsdis-aarch64.so`, as in UAP-4. Without it, skip the r9g perfasm forks, say so, and decode the perfnorm forks' PrintAssembly hex instead (`objdump -D -b binary -m aarch64`).
- Keep every fork's console, JSON, `.perfasm` or `.xml` (zstd). Never pair one fork's assembly with another fork's counts.

## Rows

- **OS** = `^io.airlift.regulator.BenchmarkUapCore\.osAtomSetMatchingPatternIds$`
- **BR** = `^io.airlift.regulator.BenchmarkUapCore\.browserAtomSetMatchingPatternIds$`
- both with `-p corpus=UA_TESTS -p ruleMemoryMiB=32 -p atomSetMemoryMiB=32`. One op is 1,601 strings: **divide every per-op counter by 1,601.**
- **D1** = `^io.airlift.regulator.BenchmarkPatternCollections\.matchingSetPatternIds$`, **D2** = `…\.matchingSetPatternIdsMatchDense$`, both `-p patternCount=16 -p sourceLength=32768` (r8i shape cell only).

## Cell 1: r9g (one r9g.large Spot host)

| Leg | OS | BR |
|---|---|---|
| A5, G5, H5, H5z, B6, L6 | 3 perfnorm + 1 perfasm each | 3 perfnorm + 1 perfasm each |

**48 forks.**

## Cell 2: r8i (one r8i.large Spot host): B6's and L6's loop-shape check, per the standing rule

| Leg | D1 | D2 | OS |
|---|---|---|---|
| H5 | 3 perfnorm | 3 perfnorm | 3 perfnorm |
| B6 | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm | 3 perfnorm |
| L6 | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm | 3 perfnorm |

**43 forks.**

## What to record

### One row per fork

| Column | Source |
|---|---|
| Fork, host, leg, row, profiler, µs/op (diagnostic) | JSON |
| cycles, instructions, branch-misses, L1-dcache-load-misses per op, and **per string** (OS, BR: ÷ 1,601) or per byte (D1, D2: ÷ 32,768); IPC | perfnorm |
| `gc.alloc.rate.norm` (bytes per op) and per string | `-prof gc` |
| `searchMany`'s final standard tier-4 compile: id, `count`, `backedge_count`, nmethod size; the inline decisions for `analyzeStart`, `dfaReference`, `endSearch`, `recordMatches`, `stateData` | the fork's own log |
| **Zeroing at the buffer allocation** in the hot `searchMany` (H5, H5z, B6, L6): `zero_blocks` call or inline `stp zr` stores, and how many | the fork's own assembly |
| **Scanner (H5, H5z, B6, L6):** `inline_fail` at its call site (`hot method too big` in every fork), its `bytes=` (352; L6 370) | same |
| **Call boundary (H5, H5z, B6, L6, r9g):** stack stores before and loads after the scanner call | same |
| r8i D1/D2 (H5, B6, L6): the shape rule below on the scanner's hot loop; instructions per byte; invariant reloads per byte; whether `position` is in a register | the fork's own assembly |

### Shape rule (unchanged from UAP-5)

- **Fail:** V is the value the transitions-array load returns (after any `and 0xbfffffff`) that becomes the next byte's index addend, including V between unrolled copies. A fork fails if V is stored to `[rsp+k]` and that slot is read back into the next index.
- **Pass:** a stack read whose slot holds only values not produced in the iteration; a store that writes back an unchanged value loaded from the same slot; a stored copy read back only by a comparison. Count invariant reloads per byte.

### Summary lines

- **r9g, per leg and row:** the median cycles, instructions and branch misses per string, IPC, and bytes per string. Group A5's forks by `searchMany` nmethod size (it has two shapes) and report both groups.
- **r9g differences, per string, per row:** G5 − A5 (U1b), H5 − G5 (K1), H5z − H5 (part 2: zeroing), B6 − H5 (the fix), B6 − H5z (should be ≈ 0), L6 − B6 (lazy allocation).
- **r8i:** B6's and L6's shape per fork, their D1/D2 cycles per byte against H5's, and their OS cycles per string against H5's.

## Predictions (written before the run)

At r9g's 2.8 GHz, UAP-5's timed medians are about 1,080 (A5 fast) / 1,200 (A5 slow) and 1,290 (H5) cycles per OS string.

### r9g

| Difference (per string) | OS prediction | BR prediction | Wrong if |
|---|---|---|---|
| G5 − A5 | cycles −60 to +40 against A5's matching shape; instructions −20 to −80 (one end-of-text step fewer) | the same, ±60 cycles | G5 above A5's slow group by > 60 cycles: then U1b, not K1, carries part of the loss |
| **H5 − G5** | **cycles +100 to +250**; **instructions −1,200 to −1,700** (≈ 83 B × (35 − 15.5) saved in the loop, ≈ +100 back for the call and drain); IPC down by ~35–45%; branch misses +0.5 to +2.5 | cycles −80 to +80 (more match work saved by the scanner) | OS cycles below +60: K1 is not the cause, and the investigation reopens |
| **H5z − H5** (zeroing) | **cycles −30 to −90**; branch misses −0.3 to −0.8; instructions within ±15 | cycles −30 to −90 | between −15 and +15 cycles on OS: the stub is not a cause, and B6 is moot |
| **B6 − H5** | equal to H5z − H5 within ±20 cycles; bytes per string −16 (280 → 264) | the same | B6 − H5z beyond ±20 cycles on either row |
| **L6 − B6** | **cycles −20 to −70** (no allocation or zeroing on 51% of strings; +0.488 scanner calls per string at ~30–60 cycles each); bytes per string −134 | cycles +20 to +60 (+0.976 scanner calls per string, no allocation saved) | OS above −10 cycles: allocation is not a cause, and the call boundary carries the gap |
| Bytes per string | H5 − G5 = +280, H5z = H5, B6 − G5 = +264, L6 − G5 = +264 × 0.491 ≈ +130 (OS) and +264 × 0.999 ≈ +264 (BR) | the same | not exact |
| A5 shapes | two `searchMany` sizes, the larger with a per-byte stack store/reload off the index chain | one shape | — |
| H5, H5z, B6, L6 shapes | one `searchMany` size per leg in every fork; the scanner never inlined (`hot method too big`) | the same | any fork contradicts |

**The likeliest miss:** the zeroing step may cost less than 30 cycles. DC ZVA on 4–5 cache lines can be cheap when the lines are already in L1, and the computed branch may predict well if TLAB alignment repeats.

### r8i (B6's and L6's shape cell)

| Row | Prediction | Wrong if |
|---|---|---|
| D1, D2 | B6's and L6's scanner shapes equal H5's (UAP-4 K1: state and `position` in registers in every fork); D1 cycles per byte within ±2% of H5, D2 within ±3% | any B6 or L6 fork fails the shape rule, or a median moves by more than 3%. L6's added capacity check is outside the hot loop; if C2 keeps it live across the loop on x86 and the state spills, L6 fails here |
| OS | B6 cycles per string within ±3% of H5 (x86 already clears inline); L6 −3% to +2% of H5 (allocation saved on half the strings, a call added on the other half) | either above H5 by more than 3% |

## Decision (written before the run)

**Projection:** for each candidate, its r9g OS cycles per string ÷ A5's mean cycles per string, with A5's forks weighted by UAP-5's mode share (10/24 fast, 14/24 slow). If this round's three A5 perfnorm forks all land in one shape, take the other shape's cycles from UAP-5's timed ratio between the modes (685.5 / 617.5). The timed kit UAP-7 grades the same way: the mean of fork scores, ≤ 1.03.

1. **Pick the candidate** with the lowest projected r9g OS ratio, against both A5's mean and A5's slow mode × 1.03, among those that:
   - pass the r8i shape rule in every fork;
   - stay within the r8i bands above;
   - stay within +3% of H5 on the r9g browser row (L6 adds a call per matching string there).
   If both B6 and L6 project ≤ 1.03 and clear the other conditions, pick B6, the simpler (the coordinator's ruling).
2. **If the pick projects ≤ 1.03:**
   - fold it into K1's commit;
   - update the decision record's 280 B;
   - run UAP-7, timed: the UA atom-set rows on both platforms, A5 against the pick, with the @64 short row as the first thing to drop.
3. **If the best candidate still projects above 1.03:** UAP-7 is not run. The remaining gap is the call boundary. The remaining options change `searchMany`'s structure: I38, an inline path for short texts; and moving the per-string setup out of the call. Each needs its own r8i shape cell. I take the evidence back to investigation before building anything.
4. **If the zeroing difference (H5z − H5) is under 15 cycles:** B6 is dropped and L6 is judged on its own.
5. **If G5 − A5 carries the loss** (G5 more than 60 cycles above A5's slow group): U1b's r9g cost is investigated before any K1 change.

## Carried forward to the next timed kit (UAP-7)

- **Grading amendment** (the coordinator's ruling, 2026-11-18, approved for every later round): a pair or row whose fastest-fork and per-mode ratios are all in band, and which passes the mode-share rule, is graded **mode artifact**, whatever its median does. UAP-5's J2 pair 3 is the precedent: a 12/12 median straddle, per-mode ratios within 1.004, slow shares 19/48 against 19/48 (p 0.583).
- **Bimodal baseline** (the coordinator's ruling, 2026-11-18): grade the UA atom-set rows per host on mean(candidate) / mean(A5) ≤ 1.03, on both hosts of each platform. The Fisher slow-share test fits pairs that share modes, and a unimodal candidate against a bimodal A5 doesn't. A5's mean is what a user pays across many JVMs.
  - **Guard:** fail if the candidate is more than 3% slower than A5's slow mode on both hosts of a platform.
  - **Report alongside:** each A5 mode's share, the per-mode ratios and the fastest-fork ratio.
  - **UAP-5's H5 under this rule:** 1.125 on the mean, and 1.077 against the slow mode, so it fails both.
- **Choosing between B6 and L6** (the coordinator's ruling): pick the simpler one, B6, if both clear the r9g D-cell. If only L6 does, the PR states the browser give-back and the extra scanner call. Either way, the PR states what the buffer size trades: a smaller batch drains more often on match-dense text.

## Estimate

UAP-4 ran 81 forks of this kind for $0.036.

| Cell | Forks | Cost |
|---|---|---|
| r9g | 48 | ≈ $0.02 |
| r8i | 43 | ≈ $0.02 |
| **Total** | **91** | **≈ $0.04; guard cap $0.10** (approved 2026-11-18) |

About 1–1.5 hours on each host, including five builds on r9g (H5z uses H5's jar) and three on r8i.

If the cap nears, drop in this order:
1. the r9g perfasm forks;
2. the r8i OS perfnorm forks;
3. H5's r8i D1 forks.

These are never dropped: every leg's r9g perfnorm forks, and B6's and L6's r8i perfasm forks.
