# AWS diagnostic jobs, round UAP-9 D: a one-line first scan buffer (I49g) against L8 (untimed)

Written 2026-11-18 PST on dain-1, after UAP-8's close. **Every prediction, model test and gate below was written before anything ran.**

**Approval:** the coordinator approved this cell. The uap-core hold has about $0.307 left after the coordinator raised it by $0.15. Guard cap for this cell: **$0.07**.

Every result is a counter or a disassembly; µs/op is diagnostic only. Nothing runs on dain-1 or the Mac.

## Why

UAP-8 (`benchmarks/aws-uap8/`, $0.163) failed #279 with lazy allocation (L8) on one line: r9g OS, mean(L8)/mean(A8) **1.034 and 1.035** against the 1.03 gate.
- L8 is unimodal at about 674 µs.
- A8 draws a fast level near 612 µs (11 of 24 forks) that L8 never reaches.
- Every other line passed: r8i OS 0.74/0.77, BR 0.87–0.94, SH, S2, r8i D1 0.875 and D2 0.733, bytes, J2.

The cost left on r9g OS is the one `int[65]` (280 B) scan buffer that 49.1% of OS strings allocate, at about 240 cycles each.

Two investigators disagree on what that cost scales with (IDEAS I50):
- **Per object (Opus):** the allocation's `dmb ishst` waits about one memory round trip for the fresh lines' ownership. A smaller buffer saves about nothing (0 to +30 cycles per OS string); I45 (no separate object) wins.
- **Per line / per boundary (Sol):** the cost is in the allocation's boundary lines and cold stores. A 64 B buffer saves about 90 cycles per OS string and 150 per browser string.

**I49g** starts with an `int[12]` buffer (64 B with its header, one cache line) and switches once to `int[65]` when a scan fills it. On aarch64 the `int[12]` clears with 3 inline `stp xzr` plus `dmb ishst`.

This cell decides which model holds, and whether I49g is worth a timed round.

## Legs (all on main `0db30b513`)

| Leg | Commit | Branch | What it is |
|---|---|---|---|
| A8 | `9202ffd04` | `user/dain/uap-core-a8` | main + the match-dense row (UAP-8's A leg). **Anchor check only** |
| L8 | `5d0f36f9a` | `user/dain/uap-core-l8` | #279 `52be46a93` + lazy `int[65]` allocation (`f50aa7a82`) + one-call rows (UAP-8's candidate) |
| I49g | `e4faa8595` | `user/dain/uap-core-i49g` | `0979337b4` (`user/dain/uap-core-i49g-src`) = L8-src + 5 lines in `Dfa.searchMany`: first buffer `int[12]`, grown once to `int[65]` after a drained scan filled it. + the same one-call rows as L8 |

**Checked on dain-1 (no timing):**
- `git diff f50aa7a82 0979337b4` is 5 insertions in `Dfa.java`: one constant and the grow branch. The scanner is unchanged, and its pin stays at 370 bytecodes.
- 104 tests pass with the module checks on. The cache-reset test still reaches a reset right after a scan that buffered a full 64-state batch; no test changed.
- **Counters, warm, UA_TESTS, per string; checksums equal L5's:**

  | Set | Scanner calls | Small allocations | Full allocations |
  |---|---|---|---|
  | OS | 1.488 (L5 1.488) | 0.491 | 0 |
  | BR | 2.077 (L5 1.976) | 0.999 | 0.104 (166 of 1,601 strings) |

- **Match-dense, 16 patterns, per op:**

  | Length | Scanner calls | Small allocations | Full allocations |
  |---|---|---|---|
  | @128 | 2 | 1 | 0 |
  | @1024 | 4 | 1 | 1 |
  | @32768 | 44 (L5: 43) | 1 | 1 |

- **Zeroing:** the decoded tier-4 `searchMany` on aarch64 clears `int[12]` inline with 3 `stp xzr`, then `dmb ishst`, with no `Stub::zero_blocks`. L8's `int[65]` site keeps `bl Stub::zero_blocks`.

## Conventions

As UAP-7 (`AWS-JOBS-UAP-7.md`):
- Temurin 25.0.4+7, native mode, UAP-1's JVM flags.
- Legs interleaved fork by fork and rotated per round. Bundles sit at paths of equal length. Each leg, row, profiler, flag set and fork gets a unique `$OUT`.
- **r9g: at most 2 programmable events per fork, with `cycles` on the fixed counter.** Multiplexed counts are not accepted. If perf reports a scaling below 100% for any event, drop that fork's counts and say so.
  - **Perfnorm forks:** `-prof 'perfnorm:events=cycles,instructions,branch-misses' -prof gc`, with `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=$OUT.xml -XX:+PrintAssembly`.
  - **Mechanism forks (Sol's pairs), no log:**

    | Fork | Events | Raw codes, if the names are missing |
    |---|---|---|
    | M1 | `cycles,l1d_cache_refill_wr,l1d_cache_refill_rd` | `r43`, `r42` |
    | M2 | `cycles,l2d_cache_refill_rd,l2d_cache_allocate` | `r52`, `r20` |
    | M3 | `cycles,stall_backend_mem,l1d_cache_wb` | `r4005`, `r15` |

    On Neoverse V2, a store's ReadUnique counts as an L2 read refill (Sol).
  - **Prefetch-off control:** the perfnorm fork with `-XX:AllocatePrefetchStyle=0` added. Record `-XX:+PrintFlagsFinal`'s `AllocatePrefetchStyle` once per leg to confirm the flag took.
- **r8i:** UAP-6's set, `cycles,instructions,branch-misses,r0151,r0403`. Perfasm forks as in UAP-7.

## Rows

- **OS / BR:** `BenchmarkUapCore.(os|browser)AtomSetMatchingPatternIds`, `-p corpus=UA_TESTS -p ruleMemoryMiB=32 -p atomSetMemoryMiB=32`. Per string = per op ÷ 1,601.
- **D1:** `BenchmarkPatternCollections.matchingSetPatternIds`, `-p patternCount=16 -p sourceLength=32768`.
- **S2 / K2 / D2:** `…matchingSetPatternIdsMatchDense`, `-p patternCount=16`, with `-p sourceLength=128` (S2), `1024` (K2) and `32768` (D2). I49g allocates once on S2 (the small buffer only) and twice on K2 and D2 (small, then full). Report per op.

## Cell 1: r9g (one r9g.large Spot host)

| Leg | OS | BR | S2 | K2 | D2 |
|---|---|---|---|---|---|
| A8 | 3 perfnorm | 3 perfnorm | — | — | — |
| L8 | 3 perfnorm + M1 + M2 + M3 + 3 prefetch-off | 3 perfnorm + M1 + M2 + M3 + 3 prefetch-off | 3 perfnorm | 3 perfnorm | 3 perfnorm |
| I49g | 3 perfnorm + M1 + M2 + M3 + 3 prefetch-off | 3 perfnorm + M1 + M2 + M3 + 3 prefetch-off | 3 perfnorm | 3 perfnorm | 3 perfnorm |

OS 21, BR 21, match-dense 18: **60 forks.** No r9g perfasm; the perfnorm forks' PrintAssembly carries the zeroing check.

## Cell 2: r8i (one r8i.large Spot host): I49g's loop-shape check, per the standing rule

| Leg | D1 | D2 | S2 | OS |
|---|---|---|---|---|
| L8 | 3 perfnorm | 3 perfnorm | 3 perfnorm | 3 perfnorm |
| I49g | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm |

**40 forks.**

## What to record

- **Per fork:** the same columns as UAP-7's per-fork table. Add M1/M2/M3 counts per string or per op, the prefetch-off flag, and `gc.alloc.rate.norm`.
- **Zeroing (r9g PrintAssembly):**
  - I49g's small site: inline `stp xzr` (expected 3), then `dmb ishst`.
  - I49g's grow site and L8's lazy site: `bl Stub::zero_blocks`.
- **Scanner, both legs:** `hot method too big` at its call site in every fork, with `bytes=` equal to the pin of 370.
- **Shape rule, unchanged:** applied over the scanner's whole loop nest in every r8i perfasm fork. A fork fails only if the loop-carried state or `position` is stored and reloaded inside the loop. Also check `searchMany`'s drain/grow path for anything newly inlined into the scanner's loop.
- **Summary lines, r9g:**
  - per row, I49g − L8 per string or per op: cycles, instructions, branch misses, bytes;
  - per row, I49g − L8 on every M1/M2/M3 event;
  - per row, prefetch-off − prefetch-on for each leg, and the I49g − L8 gap under each setting;
  - A8 vs L8 cycles per string, beside UAP-8's timed 1.034/1.035.
- **Summary lines, r8i:** I49g's shape in every perfasm fork, and its perfnorm medians against L8's on every row.

## Model test (written before the run)

UAP-7's L5 (the same code as L8) per string on r9g: OS 1,379.2 cycles, BR 2,228.

Fresh lines fall in both models: on OS, 0.491 allocations per string shrink from about 4.4 lines to 1. **The models differ on whether cycles and backend-memory stalls follow the lines.**

| Measure, I49g − L8 | Per object (Opus) | Per line (Sol) |
|---|---|---|
| **OS cycles per string** | **−20 to +30** | **−60 to −120** (Sol ≈ −90) |
| BR cycles per string | +10 to +70 (0–30, plus 0.104 extra full allocation at about 230, plus 0.101 extra call at about 70) | −110 to −190 (Sol ≈ −150) |
| OS `l1d_cache_refill_wr`, `l2d_cache_allocate` | −0.6 to −1.8 per string | the same |
| OS `stall_backend_mem` | −20 to +20 | −50 to −120, at least half the cycle drop |
| OS `l1d_cache_wb` | −0.3 to −1.7 | the same |
| Bytes per string | OS 216 × 0.491 ≈ −106; BR −216 × 0.999 + 280 × 0.104 ≈ −187 (the same for both models) | |
| Prefetch-off: the I49g − L8 gap on OS | within ±20 of the prefetch-on gap (the barrier wait doesn't depend on prefetch) | widens by at least 20 cycles (L8's extra lines lose their prefetch) |

**Which model holds:**
- **Per line,** if OS cycles fall by at least 50 and `stall_backend_mem` falls by at least half the cycle drop.
- **Per object,** if OS cycles land in −20 to +30 while the write refills or `l2d_cache_allocate` fall by at least 0.6.
- **Neither,** if the drop is between −20 and −50, or the lines don't fall. The verdict file then records both as partial, with the numbers.

The prefetch-off control supports the call; it does not decide it.

**The verdict file** `uap-core-manymatch-uap7-alloc.verdict.md` is written from this cell: the model adopted, then `opus:` and `sol:` hit/partial/miss.

## Match-dense predictions (written before the run)

Per op, I49g − L8. This measures the extra allocation the coordinator asked about.

| Row | Allocations, I49g vs L8 | Per object | Per line | Wrong if |
|---|---|---|---|---|
| **S2 @128** | 1 small vs 1 full; same calls | −20 to +20 cycles | −120 to −220 cycles (≈ −11 to −20% of about 1,100) | above +60 cycles |
| **K2 @1024** | 1 small + 1 full vs 1 full; +1 call | +230 to +330 cycles | +120 to +250 cycles | above +450 cycles, or above +10% of the op |
| **D2 @32768** | 1 small + 1 full vs 1 full; +1 call (44 vs 43) | the same absolute cost as K2, under +0.5% of the op | the same | above +1% of the op |

Bytes per op: S2 −216; K2 and D2 +64.

## r8i predictions (written before the run)

| Row | Prediction | Wrong if |
|---|---|---|
| Shape, every I49g perfasm fork (D1, D2, S2, OS: 16) | equals L8's: the state and `position` stay in registers. The scanner is unchanged, and the grow branch sits in `searchMany` outside its loop | any fork fails the shape rule |
| D1 | within ±2% of L8 (never allocates past the small buffer: one match) | outside ±3% |
| D2 | within ±2% of L8 (one more allocation and call per 32 KB) | above +3% |
| S2 | −2% to −10% against L8 (x86 clears 64 B instead of 280 B, inline with zmm stores either way) | above +3% |
| OS | −0% to −3% against L8 | above +3% |

## Projection and launch gate (the coordinator's rulings)

**Projection,** per r9g host of UAP-8:
- projected I49g/A8 = (I49g ÷ L8, this cell's median cycles per OS string) × UAP-8's timed L8/A8 (**1.034** on x1, **1.035** on x2);
- slow mode: (I49g ÷ L8) × UAP-8's 0.992 against A8's slow mode on x2.

Under the per-object model the projection is 1.034–1.057; under the per-line model it is about 0.95–0.99.

**Launch gate for the timed round (UAP-9 T).** All must hold:
1. I49g projects **≤ 1.025** on r9g OS, on both UAP-8 hosts' factors. That means I49g/L8 ≤ 0.990 in this cell's OS cycles.
2. r9g BR doesn't worsen against L8: I49g/L8 ≤ 1.010 in cycles per string.
3. Every r8i I49g perfasm fork passes the shape rule.
4. The r8i medians stay within the "wrong if" lines above.

**Not a gate, reported:** this cell's A8 vs L8 on OS and BR. A8 is bimodal, and 3 forks can land in either mode, so it is a sanity check on the factors, not a basis.

## Decision (written before the run)

- **The gate passes:** I send the UAP-9 T spec.
  - **J1** in full on r9g (OS, BR, SH, S2), I49g against A8, plus r8i OS, BR and D2.
  - 12 forks per leg per host, 2 hosts per platform: r9g 192 forks, r8i 144, **336 forks, about $0.075**.
  - J2 is skipped. r8i SH, S2 and D1 are skipped unless this cell's r8i shape cell shows a change.
  - Grading is UAP-8's, including the bytes line (I49g − A8: OS +137.5 × 1,601 − 106 × 1,601 ≈ +50,400; others per row).
  - If it passes, I49g-src's commit becomes #279's fourth change, folded into K1 with L5's.
- **The gate fails:**
  - If the per-object model holds, I bring the coordinator I45's cost (a 65-int tail on the SparseSet's dense array, with its capacity kept apart from `dense.length`) and its API cost. I45 stays held unless the per-object model wins clearly.
  - Otherwise I stop and say what remains.

## Estimate

UAP-7 cost $0.034 for 119 forks.

| Cell | Forks | Cost |
|---|---|---|
| r9g | 60 | ≈ $0.018 |
| r8i | 40 | ≈ $0.012 |
| **Total** | **100** | **≈ $0.03; guard cap $0.07** |

If the cap nears, drop in this order:
1. A8's anchor forks;
2. the BR prefetch-off forks;
3. r9g D2;
4. the BR M1/M2/M3 forks.

**Never dropped:** L8's and I49g's r9g OS and BR perfnorm forks, the OS M1/M2/M3 and prefetch-off forks, S2 and K2, and every r8i perfasm fork.
