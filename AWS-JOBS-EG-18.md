# AWS diagnostic jobs, round EG-18: D10, the profile-independence D-cell for #273's last-block fix

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.** No timed rows: J2' (EG-10 J2's families, A against #273's head) follows only if Z passes here.

**Why this round.** EG-16 (D9) confirmed N's quotedField fix, but found two problems.
- **(a) The inline is a race.** In 4 of 12 N quotedField perfasm forks, C2 left `findRe2LastBlockStop` out of line, at a cost of 3–9%.
- **(b) Extract got worse on r8i.** Delimited short-ascii extract on r8i is 1.059 of A, worse than C.

Investigation i30 (Opus and Sol on one brief, local only) found the cause of (a): the profile of the 30 B wrapper `re2ScanNonAscii` was immature when C2 compiled the matcher. Only non-ASCII stops reached it, about 1 run read in 6, and it needs 1,000 invocations, so its call read count −1 and the 310 B check failed "too big". This is i28's mechanism (#275).

**Candidate Z** (folded into #273 commit 5, head `774e9b71d`):
- **(a)** `re2ScanBlocks` calls the wrapper on every stop, with no branch. The check opens with `if (stop == end || input[stop] >= 0) return stop;`, so the wrapper's profile matures with the stop scan's.
  - The check is 323 B, under FreqInlineSize 325; TestHotMethodSize pins that, and pins the branch-free call.
  - On aarch64, every forced compile inlines it: `-Xbatch`, matcher thresholds ×0.5 and ×2, default, and `ActiveProcessorCount=2`, 6 JVMs. N is out of line under `-Xbatch` and ×0.5.
- **(b)** `re2MatchBefore` masks its ASCII delimiter (`delimiter & 0x7F`). C2 then folds the stop scan's needle and bound: −3.3 instructions per row, and `matchPartial`'s stack loads drop to A's.
  - i30 found no N-over-C excess on aarch64 (N is 14 instructions per row below C). The r8i excess is unexplained by code shape here.
- **Answers:** identical. ReaderDiff seed 273 hashes equal commit 5's on SVE, NEON, MaxVectorSize=8 and vectors off. LastBlockCheck covers 2.58M cases. The 5 test classes pass.

**Budget.** 174 diagnostic forks (87 per platform), about $0.06 at $0.000323 per fork. With profiler overhead and two host start-ups, about $0.15. **Ask $0.20.**

**Conventions,** as EG-16:
- **Mode:** native only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **JMH:** the class's defaults, `-f 1` per fork, with the legs' jars at paths of equal length.
- **Fork order:** legs interleave fork by fork, with the order rotating (A N Z, N Z A, Z A N). Every leg of a cell runs on the same host.
- **perfasm forks:**
  - `-prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>;saveLogToFile=<leg>-<row>-<fork>.perfasm'`;
  - plus `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<row>-<fork>.xml`.
- **perfnorm forks:** `-prof perfnorm`, run separately, without the compile-log flags.
- **Host:** `kernel.perf_event_paranoid` ≤ 1, with hsdis from the rust-clear-losses-2 kit. Ship the raw files to S3.
- **Loop-shape rule** (the coordinator's wording): fail only if the loop-carried state or position is stored and reloaded inside the loop; loop-invariant reloads pass.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| A | `2ba9dbbdbdbe0ef8569227fb3d3e543d032e8a67` | `user/dain/bench-trino-plan-costs` (main + BenchmarkTrinoPlanCosts, as EG-16) |
| N | `de4c8106e91a34e88280a79158fd78c7f2c7d202` | `user/dain/bench-273-r9g` (EG-16's N) |
| Z | `827ca53b32e521f2cae765d622d446a6fc66adb3` | `user/dain/bench-273-z` (#273 head `774e9b71d` + the same class) |

## Cells, the same on r9g and r8i (one host per platform, 87 forks each)
| Row | Benchmark | Legs | Forks per leg |
|---|---|---|---|
| quotedField `contains` | `BenchmarkEverydayTrinoRegexp.contains -p workload=quotedField` | A, N, Z | **4 perfasm** + 3 perfnorm |
| quotedField `count` | `BenchmarkEverydayTrinoRegexp.count -p workload=quotedField` | A, N, Z | **4 perfasm** + 3 perfnorm |
| delimited short-ascii `extract` | `BenchmarkTrinoPlanCosts.extract -p pattern=delimited -p input=short-ascii` | A, N, Z | 3 perfasm + 3 perfnorm |
| delimited short-mixed `extract` | `BenchmarkTrinoPlanCosts.extract`, delimited, short-mixed | A, N, Z | 3 perfnorm |
| delimited short-mixed `contains` | `BenchmarkTrinoPlanCosts.contains`, delimited, short-mixed | A, N, Z | 3 perfnorm |
| line-tail short-mixed `contains` (guard) | `BenchmarkTrinoPlanCosts.contains`, line-tail, short-mixed | A, N, Z | 3 perfnorm |

quotedField gets 4 perfasm forks per leg (8 per platform per leg), because the race showed in 4 of 12 N forks. Z needs enough forks to show the out-of-line mode is gone.

**Report:**
- for every cell: instructions, cycles and branch misses per op (median of the forks), as ratios to A and Z/N;
- per fork for quotedField (perfasm and perfnorm): the score and cycles per op, so a slow mode shows;
- for N and Z, from every quotedField compile log: whether `re2ScanNonAscii` and `findRe2LastBlockStop` are inlined into the final C2 compile of the quotedField search, with the call-site count and the reason;
- for the perfasm rows, per leg: the hot methods and their shares;
- stack operands in the run loops (loop-carried values separated from invariants);
- for extract: the spills and stack loads in `matchPartial` around the run call, A against N against Z.

## Predictions
| Measure | r9g | r8i |
|---|---|---|
| quotedField: `findRe2LastBlockStop` inlined in Z's final compile | **every fork** (8/8) | **every fork** (8/8) |
| quotedField: `findRe2LastBlockStop` inlined in N | most forks; 0–3 of 8 OUT, as in EG-16 | same |
| quotedField contains and count: Z/A instructions | 0.98–1.00 | 0.98–1.01 |
| quotedField contains and count: Z/A cycles | **0.98–1.02**, every fork within 2% of the median | **0.92–0.98** |
| delimited short-ascii extract: Z/N instructions | 0.99–1.00 (−3 per row) | 0.99–1.00 |
| delimited short-ascii extract: Z/A cycles | 1.01–1.04 (N 1.033 in EG-16) | 1.03–1.06 (N 1.059) |
| delimited short-mixed extract: Z/N instructions and cycles | 1.00–1.02 (+1% instructions here) | 0.99–1.02 |
| delimited short-mixed contains, line-tail short-mixed contains: Z/N | 0.99–1.01 | 0.99–1.01 |
| Z's run loops | pass the loop-shape rule | pass the loop-shape rule |

- **Confirmed if:**
  - every Z quotedField compile inlines the check;
  - Z's quotedField cycles are at or below 1.02 of A on r9g and at or below 1.00 on r8i, with no slow mode;
  - Z's extract is at or below N's on both platforms;
  - the mixed rows stay within 2% of N;
  - Z's run loops pass the rule.
  - Then J2' follows.
- **I am wrong if:**
  - any Z quotedField compile leaves the check out of line (the fix does not make the profile mature in time);
  - Z's quotedField count instructions are more than 1% above N's (the unconditional call costs more than i30 counted);
  - or Z's extract is slower than N's on either platform.
- **Known gap:** r8i delimited short-ascii extract is expected to stay 3–6% over A. i30 could not see the r8i excess on aarch64, so this cell's spills and stack loads in `matchPartial` are its first x86 evidence. #273's PR notes, as present on main too, the r8i quotedField contains spill EG-16 saw.
