# AWS timing jobs, round EG-22: #275's per-search setup fix against main

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.**

**Why this round.**
- EG-17 (J4') passed #275. But the r9g 03-date no-assertion controls sat at 1.016 (java) and 1.017 (re2), with both hosts at 1.014 or above, under the 1.02 gate.
- **i31** (exact instructions on dain-1, Opus and Sol) found that the head ran **+0.934%** instructions per op on both rows. All of it is per-search setup in `DfaInstance.analyzeStart`. The search loops were unchanged.
  - **J:** the missing-start lock check sat inside the out-of-line builder, so a shared search's compile kept the builder call.
  - **C:** `wordDecoding` was loaded before the cheap `runForward && textEnd < ctxEnd` tests.
- **The fix (#275 head `9c75d89ab`):** the lock check moves before the builder call, and the cheap tests come first. A TestHotMethodSize pin keeps the layout.
  - Instructions per op against main, by exact single-step counts with 3 JVMs per leg and zero spread: **0.99626 on both rows**, against the old head's 1.00934.
  - Forward setup is back to main exactly. The remaining reverse +2 instructions per call is the stack copy of the new context argument, which needs a loop parameter-list change, ruled out.
  - The pair loops, `searchForwardContinuation` and every loop's parameter list are untouched. Answers are unchanged: 550 tests pass, and so does verify.
- The coordinator asked for this check before ready, because the change moves per-search setup on every DFA row.

**Budget.** 4 rows × 96 = **384 forks**, about $0.12 at $0.000323 per fork. With host start-up and the guard margin, **ask $0.20**.

**Conventions,** as EG-17:
- **12 forks per leg per host,** on **2 hosts per platform**, **r9g** and **r8i**. Legs interleave fork by fork (ABAB), and both legs of a row run on the same host.
- **Native** mode only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- The class's JMH defaults, with the legs' jars at paths of equal length.
- **Report** per row and platform: the median G/A across both hosts, with the per-host values. Flag (⚑) any host pair that disagrees by more than 5%.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| A | `11b9291e1c1cc00c18a2662b7588a272961d2ed3` | main (#275's base, as EG-17) |
| G | `9c75d89abed43bf679c93bf63a7e6e09f6b0f14c` | `user/dain/rust-unicode-word-dfa` (#275 head) |

**Workloads:** EG-8's W4 files, eg8-kit `eg8-w4-workloads.tar.gz` (sha256 `b5ae68dfd21f21c91cff91bed83b09eb8ffba01fe128d162443815a1f0dc4e81`). File names and expected counts are as in AWS-JOBS-EG-17.md. All rows are `BenchmarkLanguageBulk.execute`.

If #275's code changes before this round's first timed fork, the manager tells the runner and the coordinator.

## Rows (4)
1. `rows/curated--03-date--ascii--java` (111817): **the setup target**, an EG-17 control.
2. `rows/curated--03-date--ascii--re2` (111817): **the setup target**, an EG-17 control.
3. `rows/imported--sherlock--word-ending-n-asciionly--java` (35297): EG-17's r8i target.
4. `rows/imported--sherlock--word-ending-n-asciionly--trino` (35297): EG-17's r8i target.

## Predictions (G/A; above 1 is slower)

| Rows | EG-17 (r9g / r8i) | r9g | r8i | Gate / wrong if |
|---|---|---|---|---|
| 03-date java, re2 | 1.016, 1.017 / inside the gate | **1.000–1.012** | **0.99–1.01** | **Gate:** above 1.02 on both hosts of a platform keeps #275 draft |
| word-ending-n-asciionly java, trino | ≈1.0 / **1.029, 1.020** | 0.99–1.02 | **1.00–1.03** | **Gate:** above 1.03 on both r8i hosts keeps #275 draft (EG-17's) |

**Where the predictions come from.**
- **03-date:** instructions fall by 1.3% against the old head, to 0.4% below main. If the 1.6% r9g excess tracked instructions, it drops to about 1.003. i31 couldn't see the rest of it (about 0.7%) in instructions, so I widen the top of the r9g range to 1.012.
- **The targets:** the setup change runs on every DFA search, but the pair-loop step dominates these rows. They should stay near EG-17's values or improve slightly.

**I am wrong if:**
- **r9g 03-date stays at 1.014 or above on both hosts.** Then the remaining cost isn't the setup instructions. The PR body records it as r9g layout on a no-assertion row under the gate, with i31's counts, as the coordinator ruled.
- **Either target lands more than 1% above EG-17 on both r8i hosts.** Then the moved lock check or the reordered test costs the word path, and that cost needs a cause before ready.

**Cut order:** none. The round is small; run all four rows.
