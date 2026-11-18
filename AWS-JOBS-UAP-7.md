# AWS diagnostic jobs, round UAP-7: a many-match search that allocates no scan buffer on short texts (untimed)

Written 2026-11-18 PST on dain-1, after UAP-6's close. **Every prediction below was written before anything ran.** The coordinator approved the plan and a **$0.10 guard cap** inside the uap-core hold, which has about $0.355 left. Every result is a counter or a disassembly; µs/op is diagnostic only. Nothing runs on dain-1 or the Mac.

## Why

UAP-6 (`AWS-JOBS-UAP-6.md`, results on the Mac) found that the cost #279 adds on r9g's short user-agent strings is K1's per-call scan-buffer allocation. It is neither the out-of-line call nor the zeroing. All figures are r9g, per string:

- **#279 against the same code without K1 (H5 − G5):** +164 cycles on the OS atom set, at 1,321 fewer instructions.
- **The zeroing stub is the cheap path.** Clearing the buffer inline instead of through `Stub::zero_blocks` (DC ZVA) costs 90–103 cycles more: B6 and H5z. So the buffer stays `int[65]`.
- **Allocating lazily works.** L6 allocates the buffer at the first match, so the 51% of OS strings that never match allocate nothing. It saved 187 cycles against B6. That makes one allocation about 350–400 cycles with an inline clear, or about 260–320 with the stub.
- **A scanner call is about free on these strings.** On browser, L6 added 0.98 calls per string, saved no allocation, and still came in 16 cycles below B6.
- **The verdict:** `~/.codex/delegates/2026-11-18/uap-core-manymatch-uap5-os.verdict.md`. Allocation is the cause; Sol hit, Opus partial.

Two candidates follow:
- **L5:** L6's lazy allocation on H5's `int[65]`, which keeps the stub clear.
- **N5:** L5, plus a search that records its first 8 matches through its own step and allocates only after them. That leaves 0.066 allocations per OS string and 0.294 per browser string.

The round also counts why one 280 B allocation costs this much when the per-call `SparseSet` (~3 KB, cleared by the same stub) evidently does not (IDEAS I44).

## Legs (all contain main `bdf26dc33` and #281's bench)

| Leg | Commit | Branch | What it is |
|---|---|---|---|
| A5 | `b74cd81a5` | `user/dain/uap-core-a5` | main + bench (as in UAP-5 and UAP-6) |
| G5 | `63e602f34` | `user/dain/uap-core-g5` | H5 without K1 (U1b + U2) |
| H5 | `39210e764` | `user/dain/uap-core-h5` | #279 head `28734c05e` + bench |
| L5 | `9e33474f7` | `user/dain/uap-core-l5` | H5 + lazy allocation: `searchMany` starts with no buffer, and the scanner treats none as capacity 0, so it stops before the first match exactly as at buffer full. `searchMany`'s own step records that match and allocates `int[65]`. Scanner 370 bytecodes, as L6 |
| N5 | `722d5bb1c` | `user/dain/uap-core-n5` | L5 + `MANY_MATCH_UNBUFFERED_STOPS = 8`. The search's own step records a search's first 8 matches with no buffer (the scan stops at each), and the buffer is allocated at the 8th. The scanner is unchanged from L5 |

Checked on dain-1 (no timing):
- **L5's diff against H5:** exactly L6's diff against B6, without the constant change.
- **N5's diff against L5:** the constant, an `int` count beside the buffer, and one condition at L6's allocation site. The scanner is unchanged.
- **Tests:** 102 pass on each leg, with the module checks on. No test changed.
- **The reset test:** traced in throwaway copies at 32 and 64 repeats. On both legs it still forces a cache reset right after a scan that buffered matches.
- **Zeroing (L5, decoded tier-4 `searchMany`):** the lazy site allocates `int[65]` and clears 33 words through `bl Stub::zero_blocks`, the same sequence as H5's.
- **Counters per string,** warm, UA_TESTS; checksums equal H5's:

  | Leg / set | Scanner calls | Allocations | Buffered states | `recordMatches` |
  |---|---|---|---|---|
  | H5 OS | 1.000 | 1.000 | 2.295 | 2.295 |
  | L5 OS | 1.488 | 0.491 | 1.813 | 2.304 |
  | N5 OS | 3.242 | 0.066 | 0.060 | 2.317 |
  | H5 BR | 1.000 | 1.000 | 5.537 | 5.537 |
  | L5 BR | 1.976 | 0.999 | 4.606 | 5.605 |
  | N5 BR | 5.557 | 0.294 | 1.113 | 5.731 |

- **D2 (16 patterns, 32,768 bytes), per op:** L5 makes 43 scanner calls and 1 allocation; N5 makes 50 and 1.

## Conventions

As UAP-6 (`AWS-JOBS-UAP-6.md`), with these changes:
- **r9g counts at most 2 programmable events per fork, with `cycles` on the fixed counter.** Multiplexed counts are not accepted (the coordinator's ruling).
  - **Perfnorm forks:** `-prof 'perfnorm:events=cycles,instructions,branch-misses' -prof gc`.
  - **Mechanism forks:** M1 `cycles,l1d_cache_refill,l2d_cache_refill` and M2 `cycles,stall_backend_mem,l1d_cache_wb`. Use the raw codes `r03`, `r17`, `r4005` and `r15` if the names are missing.
  - If perf still reports a scaling below 100% for any event, record it, drop that fork's counts and say so.
- **r8i keeps UAP-6's set:** `cycles,instructions,branch-misses,r0151,r0403`.
- **JVM arguments, every perfnorm fork:** `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=$OUT.xml -XX:+PrintAssembly`. Mechanism forks need no log.
- **Legs:** interleaved fork by fork, rotated per round. Bundles sit at paths of equal length. Each leg, row, profiler and fork gets a unique `$OUT`.

## Rows

- **OS / BR:** `BenchmarkUapCore.(os|browser)AtomSetMatchingPatternIds`, `-p corpus=UA_TESTS -p ruleMemoryMiB=32 -p atomSetMemoryMiB=32`. Per string = per op ÷ 1,601.
- **D1:** `BenchmarkPatternCollections.matchingSetPatternIds`, `-p patternCount=16 -p sourceLength=32768`. Per byte = per op ÷ 32,768.
- **D2:** `…matchingSetPatternIdsMatchDense`, `-p patternCount=16 -p sourceLength=32768`.
- **S2:** `…matchingSetPatternIdsMatchDense`, `-p patternCount=16 -p sourceLength=128`. About 10 matches per op (`prefix<i>-123 ` cycled), so N5 takes its 8 unbuffered stops and then buffers the rest: this is the row where N5's stop-per-match path is hot. Report per op.

## Cell 1: r9g (one r9g.large Spot host)

| Leg | OS | BR | S2 |
|---|---|---|---|
| A5, G5 | 3 perfnorm + 1 M1 + 1 M2 | 3 perfnorm + 1 M1 + 1 M2 | — |
| H5 | 3 perfnorm + 1 M1 + 1 M2 | 3 perfnorm + 1 M1 + 1 M2 | 3 perfnorm |
| L5, N5 | 3 perfnorm + 1 M1 + 1 M2 + 1 perfasm | 3 perfnorm + 1 M1 + 1 M2 + 1 perfasm | 3 perfnorm |

**63 forks.** r9g perfasm needs `hsdis-aarch64.so`, as in UAP-6.

## Cell 2: r8i (one r8i.large Spot host): L5's and N5's loop-shape check, per the standing rule

| Leg | D1 | D2 | S2 | OS |
|---|---|---|---|---|
| H5 | 3 perfnorm | 3 perfnorm | 3 perfnorm | 3 perfnorm |
| L5 | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm | 3 perfnorm | 3 perfnorm |
| N5 | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm | 4 perfasm + 3 perfnorm | 3 perfnorm |

**53 forks.**

## What to record

- **Per fork:** the same columns as UAP-6's per-fork table. Add M1/M2 counts per string or per op, and S2 per op.
- **Zeroing (L5, N5):** at the lazy allocation site, either `bl Stub::zero_blocks` with 33 words (expected) or inline stores.
- **Scanner (L5, N5):** `hot method too big` at its call site in every fork, and its `bytes=` equal to the pin.
- **Shape rule (unchanged):** applied over the scanner's whole loop nest. On r8i S2 (N5), apply it to the scanner's loop and confirm that neither the state nor `position` is stored and reloaded inside it (the coordinator's ruling).
- **Summary lines, r9g per row:**
  - G5 − A5, H5 − G5, L5 − H5, N5 − L5 and N5 − G5, per string, in cycles, instructions, branch misses and bytes;
  - M1/M2 differences for H5 − G5 (the allocation's cost), L5 − H5 and N5 − H5.
- **Summary lines, r8i:** L5's and N5's shapes, and their medians against H5's on every row.

## Projection basis (the coordinator's ruling)

**For the UAP-8 launch decision**, project against UAP-5's timed A5 modes and shares: a fast-to-slow gap of 1.110 and shares of 10/24 fast and 14/24 slow.

**The anchor is this round's H5,** which UAP-5 timed beside A5 at 1.125 × A5's mean and 1.078 × A5's slow mode. So:
- projected mean ratio = (candidate ÷ H5, this round's cycles per string) × 1.125;
- slow-mode ratio = (candidate ÷ H5) × 1.078.

Anchoring on H5 carries UAP-5's measured modes and shares without estimating a mode split from 3 forks. UAP-6's sensitivity column anchored on A5's one fast fork, and put H5 itself at 1.096 against its timed 1.125.

**Beside it, the UAP-6 basis:** the candidate ÷ this round's A5 forks, weighted 10/24 by the fast shape and 14/24 by the slow shape, with UAP-5's 1.110 gap filling a missing shape.

## Predictions (written before the run)

UAP-6's medians, per string, r9g: A5 1,360.6 (OS) / 2,341.5 (BR); G5 1,344.2 / 2,321.4; H5 1,508.0 / 2,202.9; L6 1,410.6 / 2,283.9.

### r9g

| Difference (per string) | OS prediction | BR prediction | Wrong if |
|---|---|---|---|
| H5 − G5 | +130 to +200 cycles (UAP-6 +164) | −80 to −160 (UAP-6 −119) | it differs from UAP-6 by more than 50 cycles: the host differs, and the round's projections need a caveat |
| **L5 − H5** | **−110 to −170 cycles** (no allocation on 51% of strings, about 0.509 × 260–320, plus 0.49 extra scanner calls at about 0–20 each); bytes −142.6 (280 × 0.509) | −20 to +30 (one extra call per string, no allocation saved) | OS above −60, or BR above +50 |
| **N5 − L5** | **−70 to −140 cycles** (allocations 0.491 → 0.066, saving 0.425 × 260–320; scanner calls +1.75 at about 0–20 each); bytes −119 | **−110 to −230** (allocations 0.999 → 0.294; scanner calls +3.58) | OS above −40 or BR above −60: the per-stop cost is higher than L6's browser result implied |
| N5 − G5 | −20 to −150: with almost no allocation, the out-of-line loop's saving shows (UAP-6: H5 − G5 = +164 against an allocation of about 260–320) | −100 to −320 | OS above +40: something besides the allocation costs K1 on r9g |
| H5 − G5 on M1/M2 (the allocation's cost) | Fresh-line write misses: `l1d_cache_refill` +4 to +6 per string (4–5 lines), `stall_backend_mem` +150 to +300 cycles | the same | refills under +1 per string while backend stalls carry the cost: then the allocation cost is not fresh lines (I44 stays open) |
| L5, N5 on M1/M2 | Refills and backend stalls fall by the share of allocations saved (L5 0.509, N5 0.934) | L5 ≈ 0, N5 0.706 of the allocations saved | — |
| **S2 (per op), N5 vs H5** | +0 to +30% cycles: about 8 stops at about 30–50 cycles each, against one allocation saved | — | above +40% |
| Bytes per string | L5 − G5 = 280 × 0.491 ≈ +137 (OS) and 280 × 0.999 ≈ +280 (BR); N5 − G5 = 280 × 0.066 ≈ +18 (OS) and 280 × 0.294 ≈ +82 (BR) | | not within 2% |

### r8i

| Row | Prediction | Wrong if |
|---|---|---|
| D1, D2 | L5's and N5's scanner shape equals H5's in every fork: state and `position` in registers, as for L6 in UAP-6 (19/19). D1 is within ±2% of H5 and D2 within ±3% | any L5 or N5 fork fails the shape rule, or a median moves by more than 3% |
| S2 | N5's scanner keeps its shape in every fork. Cycles per op are +0 to +30% against H5: x86 clears inline, so its allocation saves less than r9g's | any fork fails the shape rule, or more than +40% |
| OS | L5 −2% to −6% against H5 (L6 was −3.8%). N5 −3% to −9% | either one above H5 by more than 3% |

**Projected for the decision (H5-anchored):**
- **L5:** about 1,370 cycles per OS string, which is 1.020 against A5's mean and 0.978 against A5's slow mode.
- **N5:** about 1,265 cycles, which is 0.944 against A5's mean and 0.904 against A5's slow mode.

## Decision (written before the run)

1. **Candidates for UAP-8** are those that meet all of these:
   - pass the r8i shape rule in every fork (D1, D2, and S2 for N5);
   - stay within the r8i bands above;
   - stay at or below 1.03 × H5 on the r9g browser row;
   - project ≤ 1.03 against A5's mean, **and** ≤ 1.03 against A5's slow mode, on r9g OS (the H5-anchored basis above).
2. **If both L5 and N5 qualify, both go into UAP-8,** and L5 is preferred on a tie (the coordinator's ruling).
   - **Tie:** UAP-8's two candidates within 0.02 of each other on the graded rows.
   - N5 must earn its stops with a margin.
3. **If neither qualifies,** UAP-8 is not run, and the evidence goes back to investigation.
4. **If S2 shows N5 more than 40% slower than H5 on either platform,** N5 is dropped, whatever its UA rows show. A text that matches a few times every few bytes must not pay that much for stops.

## UAP-8 (timed), if it runs

- **Grading:** the coordinator's UAP-7 rules, as written in `AWS-JOBS-UAP-6.md`'s carry-forward section:
  - mean(candidate) / mean(A5) ≤ 1.03 per host, on both hosts of each platform;
  - the slow-mode guard;
  - the browser row ≤ 1.03 × H5;
  - the mode-artifact amendment.
- **Rows:** the UA atom-set rows on both platforms, and the @64 short row (dropped first).
- **Extra row for N5:** if N5 is a candidate, UAP-8 also times S2.

## Estimate

UAP-6 cost $0.044 for 91 forks.

| Cell | Forks | Cost |
|---|---|---|
| r9g | 63 | ≈ $0.03 |
| r8i | 53 | ≈ $0.02 |
| **Total** | **116** | **≈ $0.05; guard cap $0.10** |

If the cap nears, drop in this order:
1. the r9g perfasm forks;
2. the M2 forks;
3. the r8i OS perfnorm forks;
4. H5's r8i D1 forks.

**Never dropped:** every leg's r9g OS and BR perfnorm forks, the M1 forks, L5's and N5's r8i perfasm forks, and every S2 fork.
