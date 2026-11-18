# AWS diagnostic jobs, round EG-16: D9, the shape D-cell for #273's reworked run reader

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.** No timed rows: a retime of EG-10's J2 families (J2') follows only if the new leg passes here.

**Budget.** 144 diagnostic forks (72 per platform), about $0.05 at $0.000323 per fork. With profiler overhead and two host start-ups, about $0.15. **Ask $0.20.**

**Conventions,** as EG-12 D6 and EG-15:
- **Mode:** native only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **JMH:** the class's defaults, `-f 1` per fork, with the legs' jars at paths of equal length.
- **Fork order:** legs interleave fork by fork with the order rotating (A C N, C N A, N A C). Every leg of a cell runs on the same host.
- **perfasm forks:**
  - `-prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>;saveLogToFile=<leg>-<row>-<fork>.perfasm'`;
  - plus `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<row>-<fork>.xml`.
- **perfnorm forks:** `-prof perfnorm`, run separately, without the compile-log flags.
- **Host:** `kernel.perf_event_paranoid` ≤ 1, with hsdis from the rust-clear-losses-2 kit. Ship the raw files to S3.
- **Loop-shape rule** (the coordinator's wording): fail only if the loop-carried state or position is stored and reloaded inside the loop; loop-invariant reloads pass.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| A | `2ba9dbbdbdbe0ef8569227fb3d3e543d032e8a67` | `user/dain/bench-trino-plan-costs` (main + BenchmarkTrinoPlanCosts, as EG-10 J2) |
| C | `1d168979f6d01b147921f2437801c7322c666d9a` | `user/dain/bench-trino-plan-costs-c` (#273 `533cf90ee` + the same class, as EG-10 J2) |
| N | `de4c8106e91a34e88280a79158fd78c7f2c7d202` | `user/dain/bench-273-r9g` (#273's new head `43f8a5430` + the same class) |

**What N changes** (only commit 5 of #273; investigation i27 and EG-12 D6 found the causes):
1. **No rescan.** A delimited capture continues from the non-ASCII stop that the byte scan found, instead of scanning that block again.
2. **A cheap short non-ASCII tail.**
   - When a run passes ASCII text to a non-ASCII byte in the input's last 16 bytes, one inlined vector check of that block decides the stop. It uses the SVE form of the validating skip on every platform.
   - C instead called an out-of-line walk by character there, with about 9 spills around the call (the `München 東京` row of quotedField).
   - Any lane the check might reject goes to the unchanged walk.
3. **No new ASCII stop scan.** Counting showed that C's scan is not the extract excess.

The answers are identical to 533cf90ee: a 6.5M-case differential on SVE, NEON, MaxVectorSize=8 and vectors off, plus 293 tests.

**Exact instruction counts per row on dain-1** (aarch64 ptrace stepper; N/A):

| Row | A | C | N | N/A |
|---|---|---|---|---|
| quotedField contains | 426.7 | 463.8 | 423.0 | 0.991 |
| quotedField count | 537.4 | 578.6 | 531.2 | 0.988 |
| delimited short-ascii extract | 673.6 | 711.5 | 694.7 | 1.031 |
| delimited short-mixed contains | 522.5 | 687.5 | 659.5 | below C and B |
| line-tail short-mixed contains | 398.9 | 1118.0 | 1098.6 | below C and B |

## Cells, the same on r9g and r8i (one host per platform, 72 forks each)
| Row | Benchmark | Legs | Forks per leg |
|---|---|---|---|
| quotedField `contains` | `BenchmarkEverydayTrinoRegexp.contains -p workload=quotedField` | A, C, N | 3 perfasm + 3 perfnorm |
| quotedField `count` | `BenchmarkEverydayTrinoRegexp.count -p workload=quotedField` | A, C, N | 3 perfnorm |
| delimited short-ascii `extract` | `BenchmarkTrinoPlanCosts.extract -p pattern=delimited -p input=short-ascii` (named as EG-10 J2) | A, C, N | 3 perfasm + 3 perfnorm |
| delimited short-mixed `contains` | `BenchmarkTrinoPlanCosts.contains`, delimited, short-mixed | A, C, N | 3 perfnorm |
| line-tail short-mixed `contains` (guard) | `BenchmarkTrinoPlanCosts.contains`, line-tail, short-mixed | A, C, N | 3 perfnorm |

**Report:**
- for every cell: instructions, cycles and branch misses per op (median of the forks), as ratios to A;
- for the perfasm rows, per leg: the hot methods and their shares;
- for N: whether `re2ScanNonAscii` and `findRe2LastBlockStop` are inlined into the quotedField search, from the compile log;
- stack operands in the run loops (loop-carried values separated from invariants);
- for extract, the spills in `matchPartial` around the run call.

## Predictions
| Measure | r9g | r8i |
|---|---|---|
| quotedField contains and count: N/A instructions | **0.98–1.00** (C 1.08) | 0.98–1.01 |
| quotedField contains and count: N/A cycles | **0.98–1.02** (C 1.12–1.13) | **0.92–0.98** (C 0.96–0.97) |
| quotedField contains: `findRe2LastBlockStop` inlined in N | yes, every fork | yes, every fork |
| delimited short-ascii extract: N/A instructions | 1.02–1.04 (C 1.08) | 1.00–1.03 |
| delimited short-ascii extract: N/A cycles | **1.04–1.08** (C 1.12) | 0.99–1.03 |
| delimited short-mixed contains, line-tail short-mixed contains: N/C instructions and cycles | **0.95–1.00** | 0.95–1.00 |
| N's run loops | pass the loop-shape rule | pass the loop-shape rule |

- **Confirmed if:**
  - r9g quotedField contains and count are at or below 1.03 in N/A cycles;
  - r8i quotedField is at or below 1.00;
  - extract is below C's 1.12 on r9g;
  - the mixed guards are at or below C on both platforms;
  - and N's run loops pass the rule.
  - J2' (the EG-10 J2 families on A against N) follows.
- **I am wrong if:**
  - N's r9g quotedField cycles stay above 1.05 while its instructions are at or below 1.00 (the cost isn't instructions; look at the inlined check's latency);
  - N's r8i quotedField is slower than C's (the SVE-form check is costly on x86);
  - or the compile log shows `findRe2LastBlockStop` out of line in N's quotedField search.
- **Known miss:** delimited short-ascii extract on r9g is expected to stay 4–8% over A. Its remaining +21 instructions per row are spills and branches in `matchPartial` around the run call, not the scan. The D-cell names what a further fix would target.
