# AWS diagnostic jobs, round EG-19: D11, where #278's no-switch cost comes from (r9g, with a placebo)

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.** No timed rows: a retime of EG-13 follows only after this cell names the residual.

**Why this round.**
- In EG-13, #278 (leg CF) won its dense rows, 0.59–0.73. But r9g mixed-zh-sparse (mzs) count-spans, a row where the switch never fires, tripped the 1.02 gate at 1.029 (hosts 1.034 and 1.024).
- EG-11 also showed no-switch costs on r9g: Trino extractAll log 1.025, words all-english count-spans 1.041.
- **i29** counted exact instructions on dain-1 and found three causes:
  - **P2's bookkeeping in `findGeneral`:** the switch dispatch and the find counter cost +4 instructions per find, and +7 per match in Trino operations. Each operation also made 64 out-of-line counter calls, and the matcher grew from 136 to 152 B.
  - **Trino's extractAll loop:** CF's extractAll got an early compiled copy too big to inline, so the benchmark caller stopped inlining it ("already compiled into a big method").
  - **P3 (forEachMatch):** words all-english adds only 312 instructions per op against A, yet cost 4.1%. Instructions don't explain it.

**Remedy R** (folded into #278, head `d94c95b22`):
- **Ordinary finds count nothing.** `findGeneral` keeps only its existing `matchStartSet != null` test.
- **Only all-match operations count.** An operation that may switch (group zero alone, the group-zero cursor, no span search or single-byte table, a region of at least 4 KiB) holds a placeholder start set, `UNDECIDED`, that answers no find. Its finds therefore take the existing start-set branch, which counts them and decides at the 64th with the same rules.
- **Smaller matcher:** 144 B, against CF's 152 and A's 136.
- **Trino helpers:** extractAll's and both replace loops sit in small helpers inside the operation's try/finally. extractAll then inlined into the caller in every run checked on dain-1; replace in 6 of 9.
- **Exact instructions per op on dain-1** (R/A0; CF/A0 in parentheses):
  - Trino extractAll log 1.000 (1.009);
  - mzs count-spans 1.007 (1.011);
  - words all-english: R/CF 0.999 (A0's count is an outlier JVM).
- The dense rows went down against CF.
- **Answers and switching:** identical. The switch probes give the same pass counts, nine test classes (422 tests) pass, and so does `verify`.

**The question for this cell:** what is left of the no-switch cost, and is it logic or layout?
- The **placebo** is A plus a `findGeneral` branch no find takes. It grows `findGeneral` from 1,230 to 1,272 bytecodes (CF is 1,270), and adds one executed test of a field that never holds.
- If the placebo costs what L2 costs, P2's cost is code size and layout. If the placebo is flat and L2 is not, P2's cost is its executed logic.

**Budget.** 5 legs × 3 rows × 6 forks = **90 diagnostic forks** on one r9g host, about $0.03 at $0.000323 per fork. With profiler overhead and host start-up, **ask $0.15**.

**Conventions,** as EG-16:
- **Platform:** **r9g only**, one host. Every leg of a row runs on the same host.
- **Mode:** native only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **JMH:** the class's defaults, `-f 1` per fork, with the legs' jars at paths of equal length.
- **Fork order:** legs interleave fork by fork, with the order rotating (A L2 CF R P, L2 CF R P A, …).
- **perfnorm forks:** `-prof perfnorm` with the default events plus the frontend events the host's `perf list` offers:
  - `L1-icache-load-misses`, `iTLB-load-misses` and `branch-misses`;
  - the Neoverse V2 PMU's `stall_frontend` and `stall_backend`.
  - Name in RUN-EG-19.md any event the host lacks.
- **perfasm forks:**
  - `-prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>;saveLogToFile=<leg>-<row>-<fork>.perfasm'`;
  - plus `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<row>-<fork>.xml`.
- **Host:** `kernel.perf_event_paranoid` ≤ 1, with hsdis from the rust-clear-losses-2 kit. Ship the raw files to S3.
- **Workloads:** EG-11 kit `eg11-w5-workloads.tar.gz` (sha256 `f59b1c1b622107c4d40e53247bf22a686419422517af7b9c5f262ae0b196d22c`), passed as in AWS-JOBS-EG-11.md.

## Legs
| Leg | SHA | Branch | Count-spans loop |
|---|---|---|---|
| A | `d9ce7dd663268d714f33c8aee406bf2881db5dd0` | `user/dain/bench-dense-match-starts-a2` (main + the two dense benchmark classes, as EG-13) | caller `find()` loop |
| L2 | `99a3d165c01743180f5a648e2d12137cacf1ba8f` | `user/dain/bench-dense-match-starts-l2` (A + #278 P1 and P2, i29's L2) | caller `find()` loop |
| CF | `2173b2887d41cf5a70efce73b26eaa5f3eb67d11` | `user/dain/bench-dense-match-starts-cf` (EG-13's CF, #278 before R) | `forEachMatch` |
| R | `00f6c88cbfbeccd9c886b19d4be469fdd8fb4b5c` | `user/dain/bench-dense-match-starts-r` (#278 head `d94c95b22` + CF's benchmark classes) | `forEachMatch` |
| P | `74e0271ae4c2ac2787fee5d3311dd389b813c8c4` | `user/dain/bench-dense-match-starts-placebo` (A + the never-taken branch) | caller `find()` loop |

## Rows (r9g, 5 legs each, 3 perfasm + 3 perfnorm forks per leg)
| Row | Benchmark | Why |
|---|---|---|
| mzs count-spans | `BenchmarkLanguageBulk.execute`, `diagnostic--03-date--mixed-zh-sparse`, engine `rust` (27933) | EG-13's gate trip, 1.029 |
| words count-spans | `BenchmarkLanguageBulk.execute`, `curated--08-words--all-english`, engine `rust` (56691) | EG-11's 1.041, with flat instructions |
| Trino extractAll log | `BenchmarkDenseTrinoAllMatch` with `-p op=extractAll`, workload `diagnostic--03-date--log` (as EG-11) | EG-11's 1.025, the lost caller inlining |

**Report:**
- for every row and leg: instructions, cycles, IPC, branch misses, L1 icache misses, iTLB misses, and frontend and backend stalls per op (median of the perfnorm forks), as ratios to A;
- per leg, the hot methods with their shares and compiled sizes (`findGeneral`, `forEachMatch`, the Trino loop or its helper, and the benchmark's caller method);
- from the compile logs: whether Trino `extractAll` (or R's helper) inlines into the benchmark caller, with the reason when it doesn't, in every fork;
- for the mzs and words rows: the code each leg's hot loop runs per find, and any loop-carried value stored and reloaded inside it.

## Predictions (ratios to A, perfnorm medians)
| Row | Measure | L2 | CF | R | P |
|---|---|---|---|---|---|
| mzs count-spans | instructions | 1.003–1.006 | 1.008–1.013 | 1.004–1.009 | 1.000–1.002 |
| mzs count-spans | cycles | 1.01–1.03 | 1.02–1.04 | **1.00–1.02** | **0.99–1.01** |
| words count-spans | instructions | 1.000 | 1.000–1.002 | 1.000–1.002 | 1.000 |
| words count-spans | cycles | 0.99–1.01 | 1.02–1.05 | 1.02–1.05 | 0.99–1.01 |
| Trino extractAll log | instructions | 1.008–1.012 | 1.008–1.012 | **0.99–1.002** | 1.000–1.002 |
| Trino extractAll log | cycles | 1.01–1.03 | 1.01–1.03 | **0.99–1.01** | 0.99–1.01 |
| Trino extractAll log | inlined into the caller | no, every fork | no, every fork | **yes, every fork** | yes, as A |

- **What the predictions say:**
  - P2's cost on mzs is its executed bookkeeping, not `findGeneral`'s size, so the placebo is flat and R recovers most of L2's cost.
  - words' cost comes with `forEachMatch` (CF and R alike) at equal instructions. It should show as frontend stalls or icache misses in CF and R, not in L2 or P.
  - extractAll log recovers in R through the restored inlining.
- **Confirmed if:**
  - R's mzs and log cycles are at or below 1.02;
  - P's cycles are within 1% of A on every row;
  - the words residual comes with frontend or icache events in CF and R alone.
  - Then a timed retime of EG-13's controls (and EG-11's words and log rows), A against R, follows.
- **I am wrong if:**
  - **P costs as much as L2 on any row:** the cost is `findGeneral`'s size or layout, and R, which keeps `findGeneral` at 1,257 B, can't remove it by logic alone;
  - **R's mzs cycles stay above 1.02 with instructions at or below 1.009:** the residual isn't the bookkeeping;
  - **R's extractAll is not inlined into the caller in some fork:** the helper only moved the compile-order race, as the coder warned;
  - **words costs in L2 or P too:** it isn't `forEachMatch`.
