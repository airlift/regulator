# AWS diagnostic jobs, round EG-14: D7, the loop-shape D-cell for #275 candidate C (decode in the pair loop)

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.** No timed rows. The candidate changes the hot pair loops, so its r8i and r9g shape is read before any retime (J4').

**Budget.**
- 87 diagnostic forks (r8i 63, r9g 24), about $0.03 at $0.000323 per fork.
- With profiler overhead and two host start-ups, about $0.10. **Ask $0.15.**

**Conventions,** as EG-12:
- **JVM flags:** native mode only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **Benchmark:** the class's JMH defaults, `-f 1` per fork, with the legs' jars at paths of equal length.
- **Fork order:** legs interleave fork by fork, rotating the order (B F C, F C B, C B F), and every leg of a cell runs on the same host.
- **perfasm forks:**
  - `-prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>;saveLogToFile=<leg>-<row>-<fork>.perfasm'`;
  - plus the compile-log flags: `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<row>-<fork>.xml`.
- **perfnorm forks:** `-prof perfnorm`, run separately, without the compile-log flags.
- **Host setup:** `kernel.perf_event_paranoid` ≤ 1, with hsdis from the rust-clear-losses-2 kit. Ship the raw files to S3.
- **Loop-shape rule** (the coordinator's wording): a loop fails only if its **loop-carried state or position is stored and reloaded inside the loop**. Reloads of loop invariants pass.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| B | `03abd97a04bf4a5b787ece87b5f2be44b164f18a` | `user/dain/bench-275-before` (#275 before its r8i fix) |
| F | `b42f6b6dce606cf01ae8e051f3246062c4bef76c` | `user/dain/rust-unicode-word-dfa` at the timed fix. The PR head `e69c326ba` differs in tests only |
| C | `807527a98eefb50f57282a6194f2d77750e703e6` | `user/dain/bench-275-c28` (PR head `e69c326ba` plus candidate C) |

**What C changes:** the pair loops `searchForwardPairs` and `searchForwardPairsFlat` decode a missed pair themselves (`decodePairedTransition`), not in their once-per-search miss methods.

**Why (investigation i28):** a miss method's profile stays immature once its loop compiles, so C2 left the decode out of line there. That call kept the loop's position in a stack slot on x86 (EG-12 D4: F 44 instructions per step with the position spilled, B 39 when the decode was inlined). On this host the decode inlined in 15 of 15 compiles of C, on every route. 92 tests pass.

**Workloads:** W4 files from eg8-kit `eg8-w4-workloads.tar.gz` (sha256 `b5ae68dfd21f21c91cff91bed83b09eb8ffba01fe128d162443815a1f0dc4e81`). All rows are `BenchmarkLanguageBulk.execute`.

## D7a: r8i, one host (63 forks)
| Row | Engine | Expected | Legs | Forks per leg |
|---|---|---|---|---|
| `rows/imported--sherlock--word-ending-n-asciionly--java` (target) | java | 35297 | B, F, C | 3 perfasm + 3 perfnorm |
| top `diagnostic--rust-unicode-ascii--word-ending-n` (control; no pair loop) | rust | 35297 | B, F, C | 3 perfasm + 3 perfnorm |
| `rows/imported--sherlock--word-ending-n-asciionly--trino` | trino | 35297 | B, F, C | 3 perfnorm |
| `rows/curated--03-date--ascii--java`, `rows/imported--sherlock--ing-suffix--re2`, top `curated--08-words--all-russian` (controls: #275's other gains) | java, re2, rust | 111817, 20547, 107391 | F, C | 3 perfnorm |

**Report:**
- for the target, per leg and fork: the host method of the pair loop, its code size, instructions per pair step, stack operands in the loop (separating loop-carried values from invariants), and the `decodePairedTransition` inline decision from the compile log;
- instructions, cycles and branch misses per op for every cell.

## D7b: r9g, one host (24 forks)
| Row | Engine | Expected | Legs | Forks per leg |
|---|---|---|---|---|
| `rows/imported--sherlock--word-ending-n-asciionly--java` | java | 35297 | F, C | 3 perfasm + 3 perfnorm |
| top `curated--08-words--all-russian` | rust | 107391 | F, C | 3 perfasm + 3 perfnorm |

On r9g these rows run the flat pair copy, which C also changes. **Report** the flat loop's instructions per step and stack operands, plus instructions and cycles per op.

## Predictions
| Measure | B | F | C |
|---|---|---|---|
| r8i target: decode inlined | 1 of 3 forks (bimodal) | 0 of 3 | **3 of 3** |
| r8i target: instructions per pair step | 39 or 48 | 44 | **38–40** |
| r8i target: loop-carried position on the stack | in the out-of-line shape | yes (1 store, 5 reloads) | **no** |
| r8i target: instructions/op | 22.75M | 23.90M | **22.5–22.9M** (4–6% under F) |
| r8i target: cycles/op | 4.74M | 5.06M | **4.70–4.85M** |
| r8i trino target: instructions/op | as java | B +4–6% | B ±1.5% |
| r8i Rust twin: instructions/op | baseline | B ±1% | B ±1% |
| r8i controls (03-date java, ing-suffix re2, all-russian): instructions/op | | baseline | F ±1% |
| r9g target and all-russian: instructions/op, cycles/op | | baseline | **F ±2%** |

- **Confirmed if** every C fork on the r8i target inlines the decode and keeps the position in a register, at 22.9M instructions/op or less, with the r8i controls and r9g rows within the ranges above. J4' (the retime) follows.
- **I am wrong if** C keeps a position spill while its log shows the decode inlined (the spill then has another source), or C's r9g flat loop gets worse by more than 2%.
