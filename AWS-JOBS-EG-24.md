# AWS diagnostic jobs, round EG-24: #282 rebased, the r8i row 7 cost and the run-reader loop shape

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.** This round has no timed rows. It is a D-cell: compile logs and perfasm only.

**Why this round.**
- **What #282 does:** a failed run returns the complement of where it stopped, and an unanchored attempt charges the budget up to that stop.
  - In EG-21 it passed by letter. Rows 1, 2 and 5 got faster on both platforms.
  - Row 7 (RE2 folded, interpreted) cost about 2% on r8i, and r9g was flat (1.001).
- **i32** (two investigators, both counting exact instructions on aarch64):
  - **Rows 1, 2 and 5:** the speed-up is register allocation, not inlining. On main, the inlined `re2Scan`'s loop-carried scan position is stored and reloaded through a stack slot inside its loops. With #282 it stays in a register. Removing #282's `~stop > cursor` compare brings the spill back.
  - **Row 7** differs by under one instruction per call on aarch64. The inline trees are identical, and `findRe2RunStop`, `re2CharacterWidth` and `matchesAsciiFolded` stay out-of-line calls inside each attempt. Nothing on aarch64 explains r8i's 2%.
- **The rebase onto main `0db30b513`:**
  - Main cut the non-ASCII branch out of `ScanPlanRun.match` and `ScanKernelSupport.runMatch`; they now read ASCII runs alone. The rebase takes main's body and changes only its failure return to `~stop`. Every other reader merged without a conflict and already follows the contract.
  - Main's new block readers (`re2ScanBlocks`, `re2ByteStop`, `re2ScanNonAscii`, `re2WalkNonAscii`) never fail and are unchanged.
  - **Route counts:** on the 7 + 9 + 14 workload sets (284 rows), routes, hand-offs, answers and shapes are identical between main and the rebased head, and the rebased head charges nothing on them.

**Coordinator's decision rule, fixed before this round:**
- **Row 7 blocks #282 only if** F's fork stores and reloads loop-carried state or position inside the loop.
- **If the cell names a cause outside the loop** (alignment, spill and reload around an out-of-line call, layout), #282 goes ready with row 7's ~2% r8i cost stated in the PR as the price of the rows 1, 2 and 5 win.
- **If it names no cause,** the same holds, with "unexplained, not a loop spill" stated.
- **The run-reader rows must pass the loop-shape rule against main** on both platforms.

**Budget.**

| Group | Forks |
|---|---|
| Deciding pair (M, F′): 9 rows × 2 platforms × 3 forks | 108 |
| EG-21 link (A, F): rows 6 and 7, r8i, 3 forks each | 12 |
| **Total** | **120** |

That is about $0.04 at $0.000323 per fork. With perfasm overhead and host start-up, **ask $0.20.**

**Conventions,** as EG-19:
- **Platforms:** r9g and r8i, **one host each.** Every leg of a row runs on the same host.
- **Mode:** native only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **JMH:** the class's defaults, `-f 1` per fork, with the legs' jars at paths of equal length. Legs interleave fork by fork (M F′ M F′ …).
- **Every fork is a perfasm fork:**
  - `-prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>;saveLogToFile=<leg>-<row>-<fork>.perfasm'`;
  - plus `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<row>-<fork>.xml`.
- **Host:** `kernel.perf_event_paranoid` ≤ 1, with hsdis from the rust-clear-losses-2 kit. Ship the raw files to S3.
- **Rows:**
  - Every ScanPlan row uses `-p dotAll=false`; the RE2 and Rust rows also use `-p scanPlan=true`.
  - Rust rows use `-p unicodePerlClasses=false`, as EG-21.
- **Workloads:** the repo's benchmark classes; no files are needed.

## Legs
| Leg | SHA | Branch | Role |
|---|---|---|---|
| M | `0db30b513a5cf79e685be74b1120afdd2c5516a2` | `main` | deciding pair |
| F′ | `3b1646d059570a38bc20f6252cb964ff9e2a8bae` | `user/dain/plan-failed-minimum-budget` (#282 rebased) | deciding pair |
| A | `bdf26dc338b73e087df01c4fe60938cb9842415e` | EG-21's A | link to EG-21, r8i rows 6 and 7 only |
| F | `d03bdc07d39e80f9bda2eaf7a4537be0856264ed` | EG-21's F | link to EG-21, r8i rows 6 and 7 only |

F′ differs from M only in #282's change and its tests. The benchmark classes are unchanged on every leg.

## Rows (EG-21 numbering; M and F′ on both platforms, 3 forks per leg)
| # | Class.method | workload | generateBytecode | Why |
|---|---|---|---|---|
| 7 | `BenchmarkRe2ScanPlan.contains` | `FOLDED_UNANCHORED` | false | **the row in question:** r8i +2%, r9g flat |
| 6 | `BenchmarkTrinoScanPlan.contains` | `FOLDED_UNANCHORED` | false | flat control for row 7: the same executor shape, but its reader inlines with no calls |
| 1 | `BenchmarkRe2ScanPlan.contains` | `UNANCHORED_URL` | false | control: the spill i32 found on aarch64; does x86 lose it too? |
| 2 | `BenchmarkRe2ScanPlan.count` | `UNANCHORED_URL` | false | control: the Step-reload saving |
| 5 | `BenchmarkRustScanPlan.contains` | `UNANCHORED_URL` | false | control, as row 1 |
| 3 | `BenchmarkTrinoScanPlan.contains` | `UNANCHORED_URL` | true | **run reader**, generated, with `chargeFailedRun` (Trino now reads non-ASCII runs through `re2Match`) |
| 10 | `BenchmarkRe2ScanPlan.contains` | `UNANCHORED_SET` | true | **run reader**, generated set reader |
| 8 | `BenchmarkTrinoScanPlan.contains` | `UNANCHORED_SET` | false | **run reader**, interpreted set reader |
| 14 | `BenchmarkEverydayTrinoRegexp.contains` | `-p workload=quotedField` | (class default) | **run reader**: `[^"]*` through main's block readers, the rebase's conflicted `match` |

## What to report
For every row, leg and platform:
1. **The inline tree** of the hot executor from LogCompilation, in every fork. Rows 6 and 7: `matchAsciiFoldedUnanchoredAt`. Rows 1, 2, 5, 8: `matchUnanchoredAt`. Rows 3 and 10: the generated `matchUnanchoredAt`. Row 14: the plan executor under `contains`. Flag any fork whose tree differs from its leg's other forks, and any M/F′ difference.
2. **The loop-shape rule** in the hot loops perfasm shows, with the instructions quoted. Does F′ store and reload loop-carried state or position inside a loop where M does not? The state to check is `cursor`, `operation`, `retries`, the retry stacks, `scanned` and `resumeRunEnd`, plus a run reader's scan position. **Loop-invariant reloads pass.**
3. **Spill and reload around the out-of-line calls** (rows 6 and 7: `findRe2RunStop`, `re2CharacterWidth`, `matchesAsciiFolded`): count the stack stores and reloads per call site, M against F′. Separate call-save traffic from loop recurrence spills.
4. **Code size and alignment:** compiled main-code bytes of the hot executor, the hot loop's size, and its loop-head alignment (address mod 32 and mod 64).
5. **Instructions and cycles per op,** from perfasm's totals, as F′/M. These are diagnostic, not a timing verdict.

For the A/F link (r8i rows 6 and 7): items 1–4, and whether A/F show the same difference as M/F′.

## Predictions (written before anything ran)

| Row | Platform | Inline tree M vs F′ | Loop-carried spill in F′ only | Instructions F′/M |
|---|---|---|---|---|
| 7 | r8i | identical | **no** | 1.000–1.003 |
| 7 | r9g | identical | no | 1.000–1.003 |
| 6 | both | identical | no | 1.000–1.005 |
| 1, 5 | r9g | identical | no: **M** spills the scan position, F′ does not (as i32) | 0.975–0.995 |
| 1, 5 | r8i | identical | no; M's spill may not exist on x86 | 0.98–1.00 |
| 2 | both | identical | no | 0.98–1.00 |
| 3, 10, 8, 14 | both | identical | **no** | 0.995–1.005 |

**My expectation for row 7 on r8i:**
- The 2% is **outside the loop**: either more spill and reload around the out-of-line calls inside each attempt (x86 C2 has no callee-saved registers, so every live value is saved around a call), or a loop-head alignment shift, since F′'s executor is a few dozen bytes larger.
- I expect no loop-carried store and reload in F′ that M lacks. **Under the coordinator's rule, #282 then goes ready with the cost stated.**

**I am wrong if:**
- **Row 7 (r8i), F′ stores and reloads `cursor`, `scanned` or the retry state inside the step loop where M keeps it in a register.** That blocks #282 by the rule. The next step is a one-path change that keeps `~stop` out of the loop's live set, checked locally.
- **Any run-reader row (3, 10, 8, 14) fails the loop-shape rule against M** on either platform. That blocks #282 until the reader is fixed.
- **An inline tree differs between M and F′ in any row.** The rebase then changed a compile decision that the route counts could not see.
- **A/F (r8i) show no difference on row 7 while M/F′ do,** or the reverse. Then main's reader changes moved the cost, and EG-21's row 7 no longer describes the rebased PR.

**Cut order** if the guard nears the cap:
1. The A/F link.
2. Then r9g rows 2 and 5.
3. Never cut row 7 r8i, row 6 r8i, or the four run-reader rows.
