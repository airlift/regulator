# AWS diagnostic jobs, round EG-12: loop-shape D-cells for the #275 and #278 fix candidates

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.** This round runs no timed rows. Each candidate changes a hot DFA loop, so, per the coordinator's rule, its r8i loop shape must be read before any timed round. The timed follow-ups (at the end) wait until I've read these.

**Budget.**
- D4 is 36 forks and D5 is 36 forks: **72 forks**, about $0.02 at EG-9's $0.000323 per fork.
- Profiler overhead and host start-up bring that to about $0.08.
- **Ask: $0.12.**

**Conventions.**
- **JVM flags:** native mode only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **Benchmark settings:** the class's JMH defaults, `-f 1` per fork, and the legs' jars at paths of equal length.
- **Fork order:** legs interleave fork by fork, and every leg of a cell runs on the same host.
- **Profilers:**
  - perfasm: `-prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>;saveLogToFile=<leg>-<row>-<fork>.perfasm'`;
  - compile logs: `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<row>-<fork>.xml`;
  - perfnorm: `-prof perfnorm`, in separate forks, without the compile-log flags.
- **Host prerequisites:** `kernel.perf_event_paranoid` ≤ 1, and hsdis-amd64 / hsdis-aarch64 from the rust-clear-losses-2 kit, as EG-11 D3.
- **Raw files:** ship the `.perfasm`, `.xml` and perfnorm output to S3 with the round.

## D4: #275, the r8i word-ending-n loss (i25), on one r8i host

**Question.** Does fixed #275 (F) make the x86 object-row pair loop, inlined into `Dfa.searchForwardForCount` (the cursor overload), carry its state through the stack or extra moves, where the unfixed #275 (B) doesn't? Does R1, which puts the context start last in that method, give the loop back?

**i25's evidence.**
- B and F do identical work per find: 8,367 finds, 16,733 start-cache hits, no computed transitions.
- The aarch64 hot inline trees are identical.
- r8i runs the object-row pair loop and r9g the flat copy, which is why only r8i moved: 1.069 java / 1.064 trino against main, where B was 1.008 / 1.003.

**Legs:**

| Leg | SHA | Branch |
|---|---|---|
| B | `03abd97a04bf4a5b787ece87b5f2be44b164f18a` | `user/dain/bench-275-before` |
| F | `b42f6b6dce606cf01ae8e051f3246062c4bef76c` | `user/dain/rust-unicode-word-dfa` |
| R1 | `4ae4b89c08cd00bde029d3bead0b7e0ed5ceb81a` | `user/dain/bench-275-r1` |

The PR head `e69c326ba` differs from F in tests only.

**Rows,** `BenchmarkLanguageBulk.execute`, using the W4 files from eg8-kit `eg8-w4-workloads.tar.gz` (sha256 `b5ae68dfd21f21c91cff91bed83b09eb8ffba01fe128d162443815a1f0dc4e81`):
- `rows/imported--sherlock--word-ending-n-asciionly--java`, engine `java`, expected 35297 (the target);
- top `diagnostic--rust-unicode-ascii--word-ending-n`, engine `rust`, expected 35297 (control: the same finds, but no pair loop).

**Forks:** for each row, 3 perfasm forks then 3 perfnorm forks per leg, interleaved B F R1. That's 3 legs × 2 rows × 6 = **36 forks**.

**Report, per leg:**
- **The pair loop:** the hottest region of compiled `searchForwardForCount` (the object-row pair loop) with:
  - the instructions per iteration;
  - any stack operands (`(%rsp)`) inside the loop;
  - the register holding the loop bound (`contextEnd`) and the one holding the state reference;
  - spills or reloads around the loop's exits.
- **Per op:** from perfnorm, instructions, cycles, branches and branch misses per op.

**Predictions:**

| Measure | B | F | R1 |
|---|---|---|---|
| Target, instructions/op | baseline | +2–5% over B | B ±1% |
| Target, stack operands in the pair loop | none or as main | at least one that B doesn't have, or an extra move per iteration | as B |
| Control (Rust twin), instructions/op | baseline | B ±1% | B ±1% |

- **Confirmed if** F's loop has a stack operand or an extra instruction per iteration that B lacks, and R1's loop matches B's. R1 then goes into #275, followed by the timed J4 below.
- **I am wrong if** F's instructions/op equal B's (±0.5%) while F's cycles are higher. Then the cost is code layout, R1 won't fix it dependably, and I'll come back with a layout diagnosis instead of a retime.

## D5: #278, why the dense switch doesn't pay (i26), on one r9g host and one r8i host

**Question.** Does the fast pass (CF) keep the reverse match-start pass inside a tight table loop on both platforms, with its state in registers? How much of C's time was the pass?

**i26's evidence.** The switch fires once per operation. C's pass steps object rows, and about 105,668 of its 268k steps per op leave the loop to mark a start. CF steps the int transition table and marks each match bit inside the loop: 0 slow exits per op, with identical answers.

**Legs:**

| Leg | SHA | Branch |
|---|---|---|
| A | `d9ce7dd663268d714f33c8aee406bf2881db5dd0` | `user/dain/bench-dense-match-starts-a2` |
| C | `ed8af0008a4f6006e8e24ecbbc9f936359a11a30` | `user/dain/bench-dense-match-starts-c2` (#278 `ad1c8134e` plus benchmark classes) |
| CF | `2173b2887d41cf5a70efce73b26eaa5f3eb67d11` | `user/dain/bench-dense-match-starts-cf` (C plus the fast pass, `Dfa.java` only) |

**Row:** `BenchmarkLanguageBulk.execute` count-spans, `curated--03-date--ascii`, engine `rust`, expected 111817, from this round's EG-11 kit workloads (`eg11-w5-workloads.tar.gz`, sha256 `f59b1c1b622107c4d40e53247bf22a686419422517af7b9c5f262ae0b196d22c`).

**Forks:** on each host, 3 perfasm forks then 3 perfnorm forks per leg, interleaved A C CF. That's 18 forks per host and **36 forks** in all.

**Report, per leg and host:**
- **Top methods:** the top methods by cycle share.
- **The pass:** the share of `collectMatchStartsBackward` (C and CF), and for CF its loop:
  - instructions per step;
  - any stack operands inside the loop;
  - the register holding `stateOffset`;
  - whether the bitset update is branch-free.
- **The per-match search:** the share of the generic `Dfa.search` plus `searchForwardAbsolutePointers` (C and CF).
- **Per op:** from perfnorm, instructions, cycles and branch misses per op.

**Predictions** (per op; CF/A is the number the timed round would test):

| Measure | C/A | CF/A | CF/C |
|---|---|---|---|
| Instructions | 0.95–1.05 | 0.75–0.90 | 0.75–0.88 |
| Cycles, r9g | 1.02–1.05 (EG-11 1.03) | 0.82–0.95 | 0.80–0.92 |
| Cycles, r8i | 0.97–1.00 (EG-11 0.985) | 0.80–0.93 | 0.82–0.94 |
| Pass share of cycles | C: 30–45% | CF: 15–25% | |

- **Confirmed if** CF's pass loop has no stack operands, keeps `stateOffset` in a register on both hosts, and CF's instructions per op are at least 10% under C's.
- **I am wrong if** CF's instructions per op aren't below C's by at least 5%, or its pass loop spills on r8i. Then the fast pass doesn't carry #278, and I'll recommend withdrawing part 2 (`5039fcdd9`), with the anchored-setup idea logged for later.
- **Watch item:** CF/A cycles above 0.92 on both hosts would mean the generic per-match search setup is the larger remaining cost (i26's second term). That's the next candidate, not a timed round of CF.

## Follow-ups, not in this round

After I read D4 and D5:
- **J4 (#275 retime):** A main against #275 with R1. 7 rows: word-ending-n-asciionly java and trino, 03-date java, ing-suffix re2, the Rust ASCII twin, all-russian, boundary-long-russian. 672 forks, about $0.22.
- **J5 (#278 with the fast pass):** A against CF. Rows: count-spans 03-date ascii and unicode × rust and re2, sherlock-en × rust, Trino extractAll and replace on ascii, plus the mixed-zh-sparse and log controls. About 960 forks, about $0.31.
