# AWS diagnostic jobs, round EG-15: D8, the loop-shape D-cell for #275 candidate E (pair exits keep no position live across a call)

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.** There are no timed rows. A retime (J4') follows only if E passes this cell.

**Budget.** 90 diagnostic forks (r8i 66, r9g 24), about $0.03 at $0.000323 per fork. With profiler overhead and two host start-ups, about $0.10. **Ask $0.15.**

**Conventions** (as EG-14):
- **Mode:** native only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **JMH:** the class's defaults, `-f 1` per fork, with the legs' jars at paths of equal length.
- **Fork order:** legs interleave fork by fork with the order rotating (B F C E, F C E B, C E B F, …). Every leg of a cell runs on the same host.
- **perfasm forks:**
  - `-prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>;saveLogToFile=<leg>-<row>-<fork>.perfasm'`;
  - plus `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<row>-<fork>.xml`.
- **perfnorm forks:** `-prof perfnorm`, run separately, without the compile-log flags.
- **Host:** `kernel.perf_event_paranoid` ≤ 1, with hsdis from the rust-clear-losses-2 kit. Ship the raw files to S3.
- **Loop-shape rule (the coordinator's wording):** fail only if the loop-carried state or position is stored and reloaded inside the loop; loop-invariant reloads pass.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| B | `03abd97a04bf4a5b787ece87b5f2be44b164f18a` | `user/dain/bench-275-before` (#275 before its r8i fix) |
| F | `b42f6b6dce606cf01ae8e051f3246062c4bef76c` | `user/dain/rust-unicode-word-dfa` at the timed fix (PR head `e69c326ba` differs in tests only) |
| C | `807527a98eefb50f57282a6194f2d77750e703e6` | `user/dain/bench-275-c28` (F plus the decode in the pair loop) |
| E | `928518f770f6739eb0053c5855a5a3c3d7dbd616` | `user/dain/bench-275-e` (C plus the exit fix) |

**What E changes.** EG-14 found the remaining spill in C's early compiles. The pair loops' fallback to the one-byte search was `searchForward(…, position, …)` followed by `result + (position - resultBegin)`, so the loop's position stayed live across that call. The call is out of line when the miss method's profile is immature (EG-14 C f1/f2: count −1, "too big"), and HotSpot keeps no register across a Java call. E ends every such exit with one call that measures its own result from `resultBegin`, so nothing from the loop is read after any call on an exit. That covers the object and flat copies, their miss methods, and the end-of-text exit. The answers are identical: with paired search, `searchForward` was exactly that continuation call. Sizes: `searchForwardPairs` 276→257 and `searchForwardPairsFlat` 278→259 bytecodes (cap 325); the miss methods 119→100.

**Evidence on dain-1 (aarch64, object rows, forced early compile at 854 invocations):** E's inline decisions match r8i C f1/f2, with the match path and fallback out of line on count −1.
- **C:** 40 instructions per pair step, plus an extra register copy of the position.
- **E:** 39 instructions, no copy and no stack operands, in 3 of 3 runs.
- **Late compile:** both are 39. The flat route is 34 on both.
- aarch64 has more registers, so the spill itself does not reproduce there. 382 targeted tests pass in both row forms, and verify is clean.

**Workloads:** W4 files from eg8-kit `eg8-w4-workloads.tar.gz` (sha256 `b5ae68dfd21f21c91cff91bed83b09eb8ffba01fe128d162443815a1f0dc4e81`). All rows are `BenchmarkLanguageBulk.execute`.

## D8a: r8i, one host (66 forks)
| Row | Engine | Expected | Legs | Forks per leg |
|---|---|---|---|---|
| `rows/imported--sherlock--word-ending-n-asciionly--java` (target) | java | 35297 | B, F, C, E | 3 perfasm + 3 perfnorm |
| `rows/imported--sherlock--word-ending-n-asciionly--trino` | trino | 35297 | B, F, C, E | 3 perfnorm |
| top `diagnostic--rust-unicode-ascii--word-ending-n` (control; no pair loop) | rust | 35297 | B, F, C, E | 3 perfnorm |
| `rows/curated--03-date--ascii--java`, `rows/imported--sherlock--ing-suffix--re2`, top `curated--08-words--all-russian` (controls: #275's other gains) | java, re2, rust | 111817, 20547, 107391 | F, E | 3 perfnorm |

**Report:**
- **For the target, per leg and fork:** the nmethod hosting the pair loop and its invocation count at compile, its code size, instructions per pair step, and the stack operands in the loop (loop-carried values separated from invariants). From the compile log, the inline decisions for `decodePairedTransition`, `searchForwardPairsAfterMiss`, `searchForwardPairsAfterMatch`, `searchForwardContinuation` (E) or `searchForward` (B, F, C), and `recordPairedSearch`.
- **For every cell:** instructions, cycles and branch misses per op.

## D8b: r9g, one host (24 forks)
| Row | Engine | Expected | Legs | Forks per leg |
|---|---|---|---|---|
| `rows/imported--sherlock--word-ending-n-asciionly--java` | java | 35297 | F, E | 3 perfasm + 3 perfnorm |
| top `curated--08-words--all-russian` | rust | 107391 | F, E | 3 perfasm + 3 perfnorm |

On r9g these rows run the flat pair copy, which E also changes. **Report** the flat loop's instructions per step and stack operands, plus instructions and cycles per op.

## Predictions
| Measure | B | F | C | E |
|---|---|---|---|---|
| r8i target: instructions per pair step | 39 (standalone, decode inlined) or 48 (count host) | 44 | 43 in early compiles, 39 in late ones (bimodal) | **38–40 in every fork** |
| r8i target: loop-carried position stored and reloaded in the loop | in the 48 shape | yes (1 store, 5 reloads) | in early compiles | **no, in every fork** |
| r8i target: instructions/op | 22.75M | 23.90M | 22.8–23.8M (by compile timing) | **22.5–22.9M** |
| r8i target: cycles/op | 4.74M | 5.06M | 4.7–5.05M | **4.70–4.85M** |
| r8i trino target: instructions/op | as java | B +4–6% | B to F | **B ±1.5%** |
| r8i Rust twin: instructions/op | baseline | B ±1% | B ±1% | B ±1% |
| r8i controls (03-date java, ing-suffix re2, all-russian): instructions/op | | baseline | | **F ±1%** |
| r9g target and all-russian: instructions/op, cycles/op | | baseline | | **F ±2%** |

- **Confirmed if:** every E fork on the r8i target passes the loop-shape rule at 40 or fewer instructions per pair step and 22.9M instructions/op or less, and the r8i controls and r9g rows are within the ranges above. J4' (the retime of #275's r8i rows) follows.
- **I am wrong if:**
  - an E fork stores and reloads the position inside the loop while its compile log shows no out-of-line exit call that reads the position (the spill has another source);
  - E's loop moves into `searchForwardForCount` with B's 48-instruction shape (the risk the coder named: a different compile host);
  - or E's r9g flat loop gets worse by more than 2%.
