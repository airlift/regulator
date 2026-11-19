# AWS diagnostic jobs, round UAP-10 D: the scan buffer in the match set's dense tail (I45) against L8 (untimed)

Written 2026-11-18 PST on dain-1, after UAP-9 D's close. **Every prediction, gate and decision below was written before anything ran.**

**Approval:** the coordinator approved I45 and this cell (2026-11-18), with a **$0.04 guard cap** inside the uap-core hold, which has about $0.276 left. UAP-10 T, if it runs, is capped at $0.10.

Every result is a counter or a disassembly; µs/op is diagnostic only. Nothing runs on dain-1 or the Mac.

## Why

UAP-9 D settled the mechanism. On Graviton, the scan buffer's cost is per allocated object, not per cache line (verdict: opus hit, sol miss).
- Shrinking the buffer from 280 B to 64 B saved 2.8 cycles per OS string.
- L8 (#279 with a lazy `int[65]`) still pays about 230 cycles for each matching search's one extra object. That is what puts r9g OS at 1.034/1.035 in UAP-8.

**I45** removes the object instead of shrinking it. The scan buffer is a 65-int tail on the dense array of the match set that `Re2Set.matchingPatternIds` already allocates per call, so a search allocates one object fewer.
- With the buffer always present, the lazy first stop goes too: 1.000 scanner calls per string, against L8's 1.488 on OS.

**The coordinator's conditions:**
- **One path:** I45 replaces the lazy-allocation commit, and nothing allocates a separate buffer.
- **r8i shape is a hard gate.** If it fails, the failing fork and its spill go to the coordinator before any fix.
- **Fallback, decided now:** if this round or UAP-10 T fails, #279 goes ready as L8, with r9g OS 1.034/1.035 stated as the cost, and no further candidates.

## Legs (all on main `0db30b513`)

| Leg | Commit | Branch | What it is |
|---|---|---|---|
| A8 | `9202ffd04` | `user/dain/uap-core-a8` | main + the match-dense row (UAP-8's A leg). **Anchor check only** |
| L8 | `5d0f36f9a` | `user/dain/uap-core-l8` | #279 `52be46a93` + the lazy `int[65]` (`f50aa7a82`) + the one-call rows (UAP-8's candidate) |
| I45 | `c1fbe5d07` | `user/dain/uap-core-i45` | `34b5194a2` (`user/dain/uap-core-i45-src`) = #279 `52be46a93` + "Keep the many-match scan buffer in the match set", + the same one-call rows as L8 |

**Checked on dain-1 (no timing):**
- **The change, against `52be46a93`:**
  - `SparseSet` takes a scratch tail on its dense array, and `maxSize()` reads `sparse.length`.
  - `Re2Set` builds its per-call match set with a tail of `MANY_MATCH_SCAN_STATES + 1`.
  - `searchMany` hands the scanner the dense array and the tail's base, and allocates nothing.
  - The scanner takes the base and keeps a slot and a limit: the same loop-carried pair as before.
  - The loop bytecode is unchanged; only the entry, the append and the exit changed.
  - Pin 352 → `361`. C2 reports `hot method too big` at the call.
- **Tests,** module checks on:
  - Full `verify` (before the field change): 8,026 run, 0 failures. Targeted classes after it: 141 of 141, 0 Checkstyle violations.
  - The cache-reset test still drains a full 64-state scan before the reset.
  - The new layout test fills the match set to its maximum size while a scan fills the 64-state buffer. It fails if the tail starts one slot early.
- **The DFA budget doesn't charge the match set.** It is a plain per-call `new` at `Re2Set.java:106`; the DFA charges only its own overhead, states and tables.
- **Counters, warm, UA_TESTS, per string,** with checksums equal to `52be46a93`'s:

  | Set | Scanner calls (I45) | Scanner calls (L8) | Separate buffers | Dense length |
  |---|---|---|---|---|
  | OS | 1.000 | 1.488 | 0 | 436 (371 + 65) |
  | BR | 1.000 | 1.976 | 0 | 1,010 (945 + 65) |

  Match-dense, 16 patterns, scanner calls per op: @128 1, @1024 2, @32768 42.
- **aarch64, tier 4:**
  - `searchMany` has no allocation site left.
  - The longer dense array clears through `Stub::zero_blocks` plus an inline `stp xzr` tail, like the sparse array.
  - The scanner's main loop is 68 instructions, with the same opcode sequence as `52be46a93`'s and no stack traffic.

## Conventions

As UAP-9 D (`AWS-JOBS-UAP-9D.md`), with no prefetch-off control:
- Temurin 25.0.4+7, native mode, UAP-1's JVM flags.
- Legs interleaved fork by fork and rotated. Bundles sit at paths of equal length, and every `$OUT` is unique.
- **r9g:** at most 2 programmable events per fork, with `cycles` on the fixed counter. Multiplexed forks are dropped and not rerun.
  - **Perfnorm forks:** `-prof 'perfnorm:events=cycles,instructions,branch-misses' -prof gc`, plus `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=$OUT.xml -XX:+PrintAssembly`.
  - **M1:** `cycles,r43,r42` (L1D write and read refills). **M3:** `cycles,stall_backend_mem,l1d_cache_wb`. Neither carries a log.
- **r8i:** `cycles,instructions,branch-misses,r0151,r0403`; perfasm forks as in UAP-7 and UAP-9 D.

## Rows

As UAP-9 D:
- **OS / BR:** `BenchmarkUapCore.(os|browser)AtomSetMatchingPatternIds`, `-p corpus=UA_TESTS -p ruleMemoryMiB=32 -p atomSetMemoryMiB=32`. Per string = per op ÷ 1,601.
- **D1:** `BenchmarkPatternCollections.matchingSetPatternIds`, `-p patternCount=16 -p sourceLength=32768`.
- **S2 / K2 / D2:** `…matchingSetPatternIdsMatchDense`, `-p patternCount=16`, with `sourceLength` 128, 1024 and 32768.

## Cell 1: r9g (one r9g.large Spot host)

| Leg | OS | BR | S2 | K2 | D2 |
|---|---|---|---|---|---|
| A8 | 3 perfnorm | 3 perfnorm | — | — | — |
| L8 | 3 perfnorm + M1 + M3 | 3 perfnorm + M3 | 3 perfnorm | 3 perfnorm | 3 perfnorm |
| I45 | 3 perfnorm + M1 + M3 | 3 perfnorm + M3 | 3 perfnorm | 3 perfnorm | 3 perfnorm |

**42 forks.**

## Cell 2: r8i (one r8i.large Spot host): I45's loop-shape check, a hard gate

| Leg | D1 | D2 | S2 | OS |
|---|---|---|---|---|
| L8 | 3 perfnorm | 3 perfnorm | 3 perfnorm | 3 perfnorm |
| I45 | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm |

**40 forks.**

## What to record

As UAP-9 D's "What to record", with I45 in place of I49g, and these changes:
- **Allocation sites (r9g PrintAssembly):**
  - I45's `searchMany` has no `new_array` site.
  - In I45, record how `matchingPatternIds` (or wherever the dense array is allocated) clears it: expected `bl Stub::zero_blocks`.
  - L8's lazy site keeps `bl Stub::zero_blocks` with 33 words.
- **Scanner, both legs:** `hot method too big` at its call site, and `bytes=` equal to the leg's pin: L8 370, I45 `361`.
- **Shape rule, unchanged, over the scanner's whole loop nest in every r8i perfasm fork.** A fork fails only if the loop-carried state or `position` is stored and reloaded inside the loop. Report the slot and limit registers too: these are what the new `base` argument could displace.
- **If any I45 fork fails the shape rule,** quote its loop with the spill and reload, and the register map. Send it to the uap-core thread and the coordinator before anything else, and stop the round's grading there.

## Predictions (written before the run)

The basis is UAP-9 D's L8 medians per string on r9g: OS 1,444.7 and BR 2,262.6. Each avoided allocation costs about 230 cycles (UAP-7: 240 at H5; per object, by UAP-9 D). Each lazy first stop costs about 60–85 cycles (N5, UAP-7).

### r9g, I45 − L8

| Row | Cycles | Basis | Wrong if |
|---|---|---|---|
| **OS (per string)** | **−90 to −170** (≈ −140) | 0.491 allocations × ≈230, plus 0.488 calls × ≈70, plus a few cycles for 256 B more on an existing array | above −50 |
| **BR (per string)** | **−200 to −330** | 0.999 allocations, plus 0.976 calls | above −100 |
| S2 @128 (per op) | −200 to −350 (−15% to −26% of about 1,326) | 1 allocation, plus 1 call | above −100 |
| K2 @1024 (per op) | −200 to −350 (−2% to −3.5%) | 1 allocation, plus 1 call | above 0 |
| D2 @32768 (per op) | the same absolute cost as K2 (under −0.2% of the op) | 1 allocation, plus 1 call (42 against 43) | above +1% of the op |
| OS `stall_backend_mem` (M3) | falls by at least half of the cycle drop | the per-object model: the stall belongs to the allocation | falls by less than a quarter |
| OS M1 write refills | −0.5 to −2.5 | fewer fresh objects; the larger dense array adds about 4 lines that DC ZVA clears | — |

**Bytes per string or op, against L8 (exact):**
- OS: +256 − 137.5 = **+118.5** per string. The set size (371) is odd, so the dense array grows 256 B.
- BR: +256 − 279.7 = **−23.7** per string.
- SH, S2, K2, D2, D1 (16 patterns): +264 − 280 = **−16** per op.
- Wrong if any row is off by more than 2%.

### r8i, I45 against L8

| Row | Prediction | Wrong if |
|---|---|---|
| **Shape, every I45 perfasm fork (16)** | state and `position` in registers, as for L8 and I49g: the loop is unchanged, and `base` is live only at entry and exit | any fork fails (hard gate) |
| D1 | within ±2% | outside ±3% |
| D2 | within ±2% | above +3% |
| S2 | −3% to −15% (one zmm-cleared allocation and one call fewer per op) | above +3% |
| OS | −2% to −6% | above +3% |

### Anchor (not a gate)

This cell's L8 ÷ A8 on OS, beside UAP-8's timed 1.034/1.035 and UAP-9 D's 1.043.

## Projection and launch gate (as UAP-9 D)

**Projection:** I45/A8 = (I45 ÷ L8, this cell's median OS cycles per string) × UAP-8's timed L8/A8 (1.034 x1, 1.035 x2). The slow mode: × 0.992.

**Gate for UAP-10 T.** All must hold:
1. I45 projects **≤ 1.025** on both factors, so I45 ÷ L8 ≤ 0.990 in OS cycles.
2. r9g BR: I45 ÷ L8 ≤ 1.010.
3. **Every r8i I45 perfasm fork passes the shape rule** (hard gate).
4. The r8i medians stay inside the "wrong if" lines above.

## Decision (written before the run)

- **The gate passes:** I send the UAP-10 T spec.
  - **Rows:** J1 on r9g (OS, BR, SH, S2), I45 against A8, plus r8i OS, BR and D2. Add r8i SH, S2 and D1 if this cell's r8i shape or medians show a change.
  - 12 forks per leg per host, 2 hosts per platform: **336 forks, about $0.075, cap $0.10.** J2 is skipped.
  - Grading as UAP-8, with the bytes line I45 − A8:
    - OS and BR: +256 per string (≈ +409,860 per op);
    - the 16-pattern rows: +264 per op.
  - **If it passes:** I45-src's change folds into #279's K1, and the decision record and PR body are updated, including the memory line. #279 then goes to its final checks and the ready-check.
- **Gate 3 fails:** the failing fork goes to the coordinator before any fix (their ruling).
- **Any other gate fails, or UAP-10 T fails:** the coordinator's fallback. #279 goes ready as L8, with r9g OS 1.034/1.035 stated as the cost: one allocation per matching search on Graviton. No further candidates.

## Estimate

UAP-9 D cost $0.031 for 100 forks.

| Cell | Forks | Cost |
|---|---|---|
| r9g | 42 | ≈ $0.013 |
| r8i | 40 | ≈ $0.012 |
| **Total** | **82** | **≈ $0.026; guard cap $0.04** |

If the cap nears, drop in this order:
1. A8's anchor forks;
2. r9g D2;
3. the BR M3 forks.

**Never dropped:** L8's and I45's r9g OS and BR perfnorm forks, OS M1 and M3, S2 and K2, and every r8i perfasm fork.
