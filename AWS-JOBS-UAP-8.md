# AWS timing jobs, round UAP-8: PR #279 with lazy scan-buffer allocation (L5) against main

Written 2026-11-18 PST on dain-1. **Every prediction, grading rule and acceptance line below was written before anything ran.**

**Approval:** the coordinator approved timing L5 despite its two UAP-7 D-cell misses (2026-11-18), with a **$0.15 guard cap** inside the uap-core hold (about $0.32 left). Nothing runs on dain-1 or the Mac.

## Why

UAP-7's L5 projected 1.033 on r9g OS against the 1.03 gate. That miss is smaller than the noise of the H5 anchor it was projected from (±2.4%), and only timed forks can settle it.

**The coordinator's condition on the r8i `position` draw:** one D1 fork of 20 spilled `position`, and that is accepted as a compile draw. But this round's timed r8i D1 and D2 rows must meet their bands by the mean.

This round is also built to let #279 go ready on its own. It times L5 rebuilt as the code that would merge (#279's rebased head plus the lazy-allocation commit), on current main, together with the rows UAP-5 flagged:
- the J2 CRAWLERS `parseBrowser` pair;
- the r8i matching guard;
- the match-dense guard.

## Legs (both on main `0db30b513`, which carries #281's uap-core bench)

| Leg | Commit | Branch | What it is |
|---|---|---|---|
| A8 | `9202ffd04` | `user/dain/uap-core-a8` | main + the match-dense bench row (`1f54bb1b8`'s diff, with its smoke test) |
| L8 | `5d0f36f9a` | `user/dain/uap-core-l8` | `f50aa7a82` (`user/dain/uap-core-l8-src`) = #279's head `52be46a93` + the lazy-allocation commit (L5's change), plus the one-call parse rows (`39210e764`'s diff, bench only) |

**Checked on dain-1 (no timing):**
- **Production code:** `git diff A8 L8 -- src/main` is byte-identical to `git diff 0db30b513 f50aa7a82 -- src/main`. That is #279's production change plus L5's `Dfa.java` change, and it touches only `Dfa.java` and `FilteredRe2.java`.
- **Benchmark code:** `git diff A8 L8` over `Benchmark*` and `TestingUapCoreParser` shows only the one-call rows and their helpers. `BenchmarkPatternCollections` is identical on both legs.
- **Tests,** module checks on: A8 96, L8-src 104, L8 104, all passing. The scanner is 370 bytecodes, equal to its pin.
- **`BenchmarkList`:** both legs hold the OS, BR, `parseBrowser`, `matchingSetPatternIds` and `matchingSetPatternIdsMatchDense` rows. Only L8 also holds `parseBrowserOneCall`.
- **SH text** (16 patterns @64): 52 dots, then `prefix15-123`. The search returns `[15]`.
- **S2 text** (16 patterns @128): `prefix0-123 … prefix9-123 prefix10`. The search returns `[0..9]`.

## Conventions

As UAP-5 (`AWS-JOBS-UAP-5.md`):
- Temurin 25.0.4+7, native mode, UAP-1's JVM flags.
- Each class's JMH defaults.
- 2 hosts per platform.
- Legs interleaved fork by fork (ABAB) on the same host, jars at paths of equal length.
- A score per fork (the mean of its measurement iterations).
- **`-prof gc` on every collecting row** (†), recording `gc.alloc.rate.norm` per fork.
- No D-cell.

## J1: L8 against A8, 12 forks per leg per host

| Row | Platforms | Count |
|---|---|---|
| **OS:** `BenchmarkUapCore.osAtomSetMatchingPatternIds`†, `corpus=UA_TESTS ruleMemoryMiB=32 atomSetMemoryMiB=32` | r8i, r9g | 1 |
| **BR:** `BenchmarkUapCore.browserAtomSetMatchingPatternIds`†, same parameters | r8i, r9g | 1 |
| **SH:** `BenchmarkPatternCollections.matchingSetPatternIds`†, `patternCount=16 sourceLength=64` | r8i, r9g | 1 |
| **S2:** `BenchmarkPatternCollections.matchingSetPatternIdsMatchDense`†, `patternCount=16 sourceLength=128` | r8i, r9g | 1 |
| **D1:** `matchingSetPatternIds`†, `patternCount=16 sourceLength=32768` | r8i | 1 |
| **D2:** `matchingSetPatternIdsMatchDense`†, `patternCount=16 sourceLength=32768` | r8i | 1 |

r9g: 4 rows × 2 legs × 12 forks × 2 hosts = 192 forks. r8i: 6 rows × 2 × 12 × 2 = 288 forks. **480 forks.**

## J2: one-call against two-step, r9g, leg L8 only

`parseBrowserOneCall` against `parseBrowser`, `-p corpus=CRAWLERS -p ruleMemoryMiB=32 -p atomSetMemoryMiB=32`, 24 forks per row per host, rows interleaved fork by fork, on both r9g hosts. **96 forks.**

## Grading (written before the run)

### J1: the UA atom-set rows (OS, BR), per the coordinator's UAP-7 rules

- **Modes,** per host: pool the host's A8 forks for the row, and split where neighbouring scores differ by more than 8% of the lower (UAP-2's rule). Do the same for L8.
- **Pass:** per host, mean(L8 fork scores) ÷ mean(A8 fork scores) ≤ **1.03**, on both hosts of each platform.
- **Slow-mode guard:** fail if mean(L8) exceeds 1.03 × the mean of A8's slowest mode on both hosts of a platform. With one A8 mode, that is A8's mean.
- **BR:** there is no H5 leg in this round, so BR is graded against A8, by the same lines.
- **Report alongside:** each leg's modes and their shares, per-mode ratios where each leg has at least 3 forks in a mode, the fastest-fork ratio, and the median ratio.

### J1: the other rows

| Row | Pass line (per host, mean ÷ mean, on both hosts of the platform) | Source |
|---|---|---|
| SH (16 @64) | ≤ 1.03 | UAP-5 line 3 |
| S2 (16 @128 match-dense) | ≤ 1.03 | new short dense row, as a regression guard |
| **r8i D1** | ≤ **0.897** | the coordinator's condition. UAP-5's timed H5/A5 0.879 × UAP-7's D1 band of +2% against H5 |
| **r8i D2** | ≤ **0.746** | UAP-5's 0.724 × UAP-7's D2 band of +3% |

- **The D1 and D2 bands assume main's many-match loop didn't move** between `bdf26dc33` and `0db30b513`. None of main's commits since then touch `searchMany`. If A8 differs from UAP-5's A5 in µs/op by more than 5% on the same instance type, report it beside the band.
- **Bytes per op (`gc.alloc.rate.norm`):**

  | Row | L8 − A8 |
  |---|---|
  | OS | +137.5 × 1,601 ≈ **+220,100** |
  | BR | +279.6 × 1,601 ≈ **+447,640** |
  | SH, S2, D1, D2 (each one search that matches) | **+280** |

  Wrong if any row is not within 2%.

### J2

- **Per host:** the median ratio, the fastest-fork ratio, modes (an 8% split), and per-mode ratios.
- **Mode-share rule:** a fork is slow if it lies outside its host's fastest mode. Pool both hosts, and run a one-sided Fisher's exact test of "one-call's slow share > two-step's".
- **The amendment** (the coordinator's ruling, 2026-11-18): if every fastest-fork and per-mode ratio is within 0.98–1.02 and the mode-share rule passes (p ≥ 0.05), the verdict is **mode artifact**, whatever the median does.
- **Real difference:** a fastest-fork or per-mode ratio outside 0.98–1.02 on both hosts, or p < 0.05.

## Predictions (written before the run)

| Row | r8i L8/A8 (mean) | r9g L8/A8 (mean) | Basis |
|---|---|---|---|
| OS | 0.70–0.78 | **0.99–1.07** (center 1.03) | UAP-5 H5/A5 0.768 (r8i) and 1.125 (r9g mean), × UAP-7's L5/H5: 0.953 r8i, 0.918 r9g |
| BR | 0.82–0.92 | 0.92–0.99 | UAP-5 H5/A5 0.864 and 0.944, × L5/H5 ≈ 1.00 (r8i not measured) and 1.014 |
| SH | 0.74–0.86 | 0.90–1.00 | UAP-5 H5/A5 0.760 and 0.908, plus one more scanner call per op: the single match stops the unbuffered scan |
| S2 | 0.55–0.85 | 0.80–1.02 | no timed baseline. UAP-5 @1024 match-dense H5/A5 was 0.605 and 0.806; UAP-7 L5/H5 S2 0.904 (r8i) and 1.019 (r9g) |
| D1 | 0.85–0.90 | — | UAP-5 0.879; L5 within ±2% of H5 |
| D2 | 0.69–0.75 | — | UAP-5 0.724; L5 within ±3% of H5 |
| J2 CRAWLERS | — | pass or mode artifact | UAP-5: fastest-fork 1.003 and 0.990, per-mode 1.003 and 1.004, p 0.583 |

**The likeliest miss is r9g OS.** Its center sits at the gate. If the A8 draw is fast-mode-heavy, the mean ratio fails even though L8 is unchanged.

## Acceptance for #279 (written before the run)

#279 passes UAP-8, and goes to its final ready-check, if all of these hold:
1. OS and BR pass the mean line and the slow-mode guard on both platforms.
2. SH and S2 are ≤ 1.03 on both hosts of each platform.
3. r8i D1 is ≤ 0.897 and D2 ≤ 0.746 on both r8i hosts.
4. Bytes per op are as predicted.
5. J2 is pass or mode artifact.

**If it passes,** L5's change is folded into #279's K1 commit. The final tree must equal L8-src's, and K1's message and the decision record are updated:
- one allocation only on searches that match;
- the buffer-size trade: a larger batch means fewer drains on match-dense text.

**If only r9g OS fails:** the evidence goes to the uap7-alloc investigation pair's ranked candidates (I49 smaller lazy buffer, I45 buffer inside the SparseSet's allocation). #279 stays draft.

## Estimate

UAP-5 cost $0.321 for 1,440 forks, or $0.000223 per fork.

| Job | Forks | Cost |
|---|---|---|
| J1 r9g | 192 | $0.043 |
| J1 r8i | 288 | $0.064 |
| J2 | 96 | $0.021 |
| **Total** | **576** | **≈ $0.13; guard cap $0.15** |

If the guard nears the cap, drop in this order:
1. r8i S2;
2. r9g S2;
3. J2 down to 12 forks per row per host.

**Never dropped:** OS and BR on both platforms, SH, and r8i D1 and D2.
