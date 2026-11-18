# AWS timing jobs, round EG-17: J4', #275 with the pair-loop fixes against main

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.**

**Why this round.**
- In EG-9 J1, #275 passed every gate except one "wrong if": r8i `word-ending-n-asciionly` ran java 1.069 and trino 1.064.
- **The cause** (i25/i28/EG-12/EG-14): on x86, the object-row pair loop kept its position in a stack slot (1 store and 5 reloads per pair step). Two things forced it: an out-of-line decode, and an out-of-line one-byte search on the exits, after which the position was read again.
- **The fix, candidate E:** the pair loops decode their own misses, and each exit ends in one call that measures its own result. It passed the EG-15 loop-shape cell:
  - every r8i fork keeps the position in a register at 39 instructions per step, including C2's early compile with the exits out of line;
  - instructions per op are 22.15M, against B's 22.75M and F's 23.14–23.90M;
  - the controls and r9g are flat.

**Budget.** 13 rows × 96 = **1,248 forks**, about $0.40 at $0.000323 per fork. With host start-up and the guard margin, **ask $0.50**.

**Conventions,** as EG-9 J1:
- 12 forks per leg per host, on **2 hosts per platform**, **r9g** and **r8i**. Legs interleave fork by fork (ABAB), and both legs of a row run on the same host.
- Native mode only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- JMH: the class's defaults, with the legs' jars at paths of equal length.
- **Report** per row and platform: the median E/A across both hosts, with the per-host values. Flag (⚑) any host pair that disagrees by more than 5%.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| A | `11b9291e1c1cc00c18a2662b7588a272961d2ed3` | main |
| E | `928518f770f6739eb0053c5855a5a3c3d7dbd616` | `user/dain/bench-275-e` |

#275's folded head will have exactly E's `src/main` and differ only in tests. The manager confirms its SHA and src/main equality before launch. If the head isn't pushed by then, run E.

**Workloads:** EG-8's W4 files, eg8-kit `eg8-w4-workloads.tar.gz` (sha256 `b5ae68dfd21f21c91cff91bed83b09eb8ffba01fe128d162443815a1f0dc4e81`). File names and expected counts are as in AWS-JOBS-EG-9.md J1. All rows are `BenchmarkLanguageBulk.execute`.

## Rows (13)
1. **ASCII twins (7):**
   - `rows/imported--sherlock--word-ending-n-asciionly--{java,trino}` (35297): **the targets**;
   - `rows/imported--sherlock--words--{java,trino}` (447639);
   - `rows/unicode--word--boundary-any-english--java` (450561);
   - `rows/i787-opt-unicode-asciionly--rust` (5674);
   - top `diagnostic--rust-unicode-ascii--word-ending-n` (35297).
2. **Russian targets (2),** engine `rust`, top: `curated--08-words--all-russian` (107391), `unicode--word--boundary-any-russian` (529194).
3. **No-assertion controls (4):** `rows/curated--03-date--ascii--{java,re2}` (111817), `rows/imported--sherlock--ing-suffix--{java,re2}` (20547).

## Predictions (E/A; above 1 is slower)

| Family | Rows | r9g | r8i | Gate / wrong if |
|---|---|---|---|---|
| **Targets** | word-ending-n-asciionly java, trino | 0.99–1.02 (EG-9 F 1.006 / 1.008) | **1.00–1.03** (EG-9 F 1.069 / 1.064; B 1.008 / 1.003) | **Gate:** above 1.03 on both r8i hosts keeps #275 draft. Wrong if above 1.04 |
| Other ASCII twins | words java/trino, boundary-any-english java, rust-unicode-ascii word-ending-n | 0.99–1.02 | 0.98–1.02 | Wrong if above 1.03 on both hosts |
| Rust ASCII twin | i787-opt-unicode-asciionly | 0.98–1.01 | 0.99–1.02 | **Gate:** above 1.02 on both hosts |
| Russian targets | all-russian, boundary-any-russian | 0.26–0.28 | 0.24–0.26 | **Gate:** more than 5% above EG-9 (r9g 0.267 / 0.273, r8i 0.248 / 0.245) |
| No-assertion controls | 03-date java/re2, ing-suffix java/re2 | 0.99–1.02 | 0.99–1.02 | **Gate:** above 1.02 on both hosts of a platform |

**Where the target range comes from.** EG-15's perfnorm had E at 0.974 of B in instructions but 1.013 in cycles (IPC 4.61 against B's 4.80). With B/A at 1.008 in EG-9, that predicts about 1.02 on r8i.
- **Fail:** if the targets land at 1.03 or above on both r8i hosts while D8 showed the clean loop, the remaining cost is outside the pair loop's register use. The first suspect is layout, given the lower IPC at fewer instructions.

**Cut order** if the guard nears the cap: the other ASCII twins first, then ing-suffix. Never cut the targets, the Rust twin, the Russian targets or the 03-date controls.
