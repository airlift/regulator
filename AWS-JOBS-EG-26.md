# AWS diagnostic jobs, round EG-26: #282's r8i row 14 cost, two one-path candidates (D-cell)

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.** This round is untimed: perfasm, LogCompilation and perfnorm counters only.

**Why this round.**
- **The failure:** EG-25 failed #282 (F′ `3b1646d05`) on row 14, Trino `quotedField` contains, r8i 1.021 (1.020 / 1.022). The same row is 0.977 on r9g.
- **What EG-24 saw:** in F′'s r8i step loop, `resumeRunEnd` (slot 0x4) and `bytes.length` (0xa4) are reloaded and stored back unchanged on every pass, which M doesn't do.
- **i34**, an Opus investigation on dain-2 and a Sol one on dain-3 from the EG-24 r8i extract, both found that **none of #282's new code is compiled in row 14.** Every changed line sits behind a branch C2 never takes, so it is an uncommon trap, and both legs parse the same number of nodes.
- **Opus traced the difference to one profiled loop-predication decision:**
  - M hoists the step loop's invariant `fullMatch` test (and then `booleanTails`) to the loop entry in 3 of 3 r8i forks; F′ does in 0 of 3.
  - With `fullMatch` pinned in its slot for the whole loop, F′'s allocator moves `bytes.length` and splits it and `resumeRunEnd` at the loop head. That is where the store-backs come from.
  - The decision sits on C2's threshold: the run arm runs about once per attempt (1.000–1.029 per invocation). On local aarch64, M hoists in 2 of 5 forks and F′ in 0 of 5.
  - The link from the slot to the split is inferred, not traced.
- **Rows 2, 1 and 5 do compile #282's failure arm:** `not; cmp cursor; jg trap`, with a reread of `cursor`. The extra `operation` and step-reference reloads on row 2 fall in that region.

**Candidates** (both are one code path and keep #282's contract: a failed run that read past its first byte is charged up to where it stopped, in every unanchored arm, interpreted and generated):
- **E2**, the reader-side decision:
  - A failed reader returns `-1` when it failed on its first byte, and `~stop` otherwise. The unbounded minimum ≤ 1 RE2 paths return M's `-1` exactly, since they can fail only there.
  - Each arm charges with `if (stop != -1) scanned = Math.max(scanned, ~stop)`, and the generator does the same.
  - No `cursor` compare and no `~stop` on the hot failure path.
  - Sol's independent "sentinel" candidate is the same reader contract.
- **E2H** = E2 plus `boolean booleanResult = groups == null && !fullMatch;` before both step loops, with the loop's three arms testing `booleanResult && booleanTails[stepIndex]`.
  - The `fullMatch` test then leaves the loop by construction instead of by the predication coin.
  - It's an executor change, so it applies to M's behaviour as well.
- **Local evidence** (aarch64 Neoverse V3, no timing): every candidate passes TestScanPlan (including the budget test #282 added), TestScanPlanRun, TestHotMethodSize and TestPublicMatchCount.

  | | M | F′ | E2 | E2H |
  |---|---|---|---|---|
  | bytecode B: `matchUnanchoredAt` / `re2Match` | 1078 / 126 | 1198 / 131 | 1180 / 137 | 1181 / 137 |
  | row 14 step-loop instructions / stack accesses | 296 / 30 | 286 / 30 | 278 / 30 | **275 / 19** |
  | row 2 step-loop instructions / stack accesses | 672 / 204 | 686 / 214 | 669 / 198 | 669 / 198 |
  | row 1 step-loop instructions / stack accesses | 675 / 144 | 686 / 145 | 681 / 146 | 676 / 144 |

**Budget.**
- 4 legs × 5 rows × 3 forks = 60 perfasm and LogCompilation forks, plus 60 perfnorm forks, on each of r8i and r9g, so 240 forks in all.
- **r9g is included** because F′ wins row 14 there (0.977) and rows 1 and 5 (0.99), and a candidate must not trade that away.
- EG-24's realized rate for the same mix was $0.049 for 240 forks. **Ask $0.10.**

**Conventions,** as EG-24:
- **Platforms:** r9g and r8i, **one host each.** Every leg of a row runs on the same host, with legs interleaved fork by fork (M F′ E2 E2H, rotating).
- **Mode:** native only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **JMH:** the class's defaults, `-f 1` per fork, with the legs' jars at paths of equal length.
- **perfasm forks:**
  - `-prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>;saveLogToFile=<leg>-<row>-<fork>.perfasm'`;
  - plus `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<row>-<fork>.xml`.
- **perfnorm forks:** `events=cycles,instructions`, no compile-log flags (EG-24's deviation 1, now in the spec).
- **Host:** `kernel.perf_event_paranoid` ≤ 1, with hsdis as in EG-24. Ship the raw files to S3.
- **Parameters,** as EG-24:
  - ScanPlan rows use `-p dotAll=false`; the RE2 and Rust rows also use `-p scanPlan=true`, and the Rust row `-p unicodePerlClasses=false`.
  - Row 14 uses `-p workload=quotedField`.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| M | `0db30b513a5cf79e685be74b1120afdd2c5516a2` | main at #282's base |
| F′ | `3b1646d059570a38bc20f6252cb964ff9e2a8bae` | `user/dain/plan-failed-minimum-budget` (#282) |
| E2 | `9a8262e21fadf0b2b4f73b7aa076d782c7feecd2` | `user/dain/bench-282-e2` (F′ plus E2) |
| E2H | `3017eb6ff6c4af108785248f80180a83b472f5c1` | `user/dain/bench-282-e2h` (F′ plus E2H) |

## Rows (EG-21 numbering)
| # | Class.method | workload | Why |
|---|---|---|---|
| 14 | `BenchmarkEverydayTrinoRegexp.contains` | `-p workload=quotedField` | **the failing row** |
| 2 | `BenchmarkRe2ScanPlan.count` | `UNANCHORED_URL`, interpreted | F′'s arm is compiled here; r8i 1.018 |
| 1 | `BenchmarkRe2ScanPlan.contains` | `UNANCHORED_URL`, interpreted | control, and F′'s r9g 0.991 win |
| 5 | `BenchmarkRustScanPlan.contains` | `UNANCHORED_URL`, interpreted | control, and F′'s r9g 0.990 win |
| 7 | `BenchmarkRe2ScanPlan.contains` | `FOLDED_UNANCHORED`, interpreted | control (the call-save loop) |

## What to report
For every row, leg and platform:
1. **Hoisting** (row 14, and the others where the test exists): does each fork's hot step loop test `fullMatch` (and `booleanTails`) inside the loop, or did C2 hoist it to the entry? Give each leg's count out of 3 forks.
2. **The store-backs** (row 14): does the hot step loop reload and store back `resumeRunEnd` and `bytes.length` (or whatever slots they occupy in that compile), as F′ does in EG-24? Quote the instructions.
3. **The loop-shape rule** against M: any loop-carried state or position stored and reloaded inside a loop where M keeps it in a register. Loop-invariant reloads pass.
4. **Rows 2, 1 and 5:** is F′'s arm sequence (`not`, `cmp cursor`, the reread) gone in E2 and E2H? Are `operation` and the step reference reloaded on row 2?
5. **Inline tree:** whether it is identical across legs, with the size changes.
6. **Counters:** instructions and cycles per op from the perfnorm forks, for each candidate against M and against F′.

## Predictions (written before anything ran)
| Row | Platform | E2 | E2H |
|---|---|---|---|
| 14 | r8i | hoists in 0–3 of 3 forks (a coin); if none, F′'s shape and cycles about 1.02 of M | **hoisted in 3 of 3 by construction; no store-backs; cycles 0.99–1.01 of M** |
| 14 | r9g | cycles ≤ 1.00 of M | cycles ≤ 1.00 of M |
| 2 | r8i | arm `not`/`cmp` gone; instructions 1.000–1.005 of M | the same |
| 2 | r9g | instructions ≤ F′'s | the same |
| 1, 5 | r8i | instructions 1.000–1.005 of M | the same |
| 1, 5 | r9g | cycles ≤ 1.00 of M (F′'s 0.99 not lost) | the same |
| 7 | both | instructions within 0.5% of F′ | instructions ≤ F′'s |

**I am wrong if:**
- **E2H's row 14 r8i forks still test `fullMatch` in the loop,** or still store back the two slots. The hoist then doesn't remove the coin, and the cause is something other than predication.
- **E2H's row 14 r9g cycles rise above M's** (losing F′'s 0.977). The hoist would then trade the weighted platform.
- **E2 or E2H on rows 1 and 5 r9g is above M** where F′ is at 0.99. Then the reader-side `-1` gives back i32's old spill win.
- **Any candidate adds a loop-carried store and reload that M lacks.** That fails the rule.

**What follows:**
- **If E2H passes 1–4 on both platforms,** a timed follow-up round retimes rows 14 and 2, with 1, 5 and 7 as controls, M against E2H, at EG-25's 12 forks per leg per host. If E2 alone also hoists in 3 of 3, E2 is the smaller change and goes forward instead.
- **If neither passes,** #282 goes ready under the coordinator's no-fix ruling, with the costs stated.

**Cut order** if the guard nears the cap:
1. The E2 leg on r9g.
2. Then row 7 on r9g.
3. Never cut row 14 or the E2H leg.
