# AWS timing jobs, round EG-9: #275 fixed (J1), then #273 (J2) and #276 (J3) when ready

For the coordinator to run on AWS Spot. Written 2026-11-17 PST on dain-1. **I wrote the predictions before anything ran.** Every leg is built on main `11b9291e1`.

**Budget.** EG-8 cost $3.128 for 10,272 forks, about $0.000305 per fork. EG-9 has $3.25. Each job below gives its own fork estimate. This kit: D0 about $0.05, D1 about $0.05, J1 2,784 forks, about $0.85. Total about $0.95.

**Conventions,** as EG-8:
- **12 forks per leg per host.** Use **2 hosts per platform**, on **r9g** and **r8i**.
- Legs interleave fork by fork (ABAB). Both legs of a row run on the same host.
- **Native** mode only. jvmArgs are EG-8's template: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- Use the class's JMH defaults, and put the legs' jars at paths of equal length.
- For each row and platform, report the median B/A across both hosts, with the per-host values. Flag (⚑) any host pair that disagrees by more than 5%.

**Order on each platform:** on the first r8i host, D0 and then D1. Then J1 everywhere, then J2 and J3 if their legs are listed as ready.

## D0 — perfasm diagnostic for #275 (first r8i host, before its timed cases)

**Question.** On x86, does #275 push a register-held parameter of the hot per-find DFA loops (the loop bound `textEnd`) onto the stack, and does the fix give it back?

i22 found that #275 inserted its context parameter at position 3–4 of `searchForwardAbsolutePointers`, `searchForwardScanAcceleration`, the `searchForwardPairs*` family and `searchBackward`. HotSpot x86 passes six Java arguments in registers, aarch64 eight. Evidence: `investigator-i22/` and `codex-engine-gaps-i22-275-r8i/` under `~/AgentScratch/regulator/user/dain/rust-unicode-word-dfa/` on dain-1.

- **Legs:** M main `11b9291e1`, B #275 `03abd97a0`, F fixed #275 `b42f6b6dce606cf01ae8e051f3246062c4bef76c`.
- **Host requirements:** working `perf` permissions (`kernel.perf_event_paranoid` ≤ 1) and an hsdis-amd64 matching the JDK.
- **Rows** (`BenchmarkLanguageBulk.execute`, W4 files from eg8-kit `eg8-w4-workloads.tar.gz`, `rows/`):
  - `curated--03-date--ascii--java.klv`, engine `java`, expected 111817;
  - `imported--sherlock--ing-suffix--re2.klv`, engine `re2`, expected 20547.
- **2 forks per leg per row**, interleaved M B F M B F: 12 forks, about $0.05 with profiler overhead.
- **Command per fork:** the template jvmArgs plus `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<row>-<fork>.xml`, and
  `-f 1 -prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>/<leg>-<row>-<fork>.perfasm'`.
- **Report before the J1 numbers:**
  - For the hottest C2 region of `searchForwardAbsolutePointers` (or its inlined caller), `searchForwardScanAcceleration` and `searchBackward`, on each leg: frame size, stack memory operands (`[rsp+…]`) inside the loop, and whether the loop-bound compare reads a register or a stack slot.
  - Cycles and instructions per op, per leg.
  - Ship the raw `.perfasm` and `.xml` files to S3 with the round.
- **Confirmed if** B's hot loop reads a stack slot that M and F don't, or B executes more instructions per op than M and F. **If instructions are equal and only cycles differ,** the cause is code layout, not registers; say so.

## D1 — perfasm diagnostic for #273 quotedField (the same first r8i host, after D0)

**Question.** Why is #273 quotedField contains faster than main on r8i (EG-8 B/A 0.949) while count is slower (1.032), when the reader's work per row is almost the same in both? i23 found the r9g cause (a byte walk the JIT can't count), but not the r8i one.

- **Legs:** A `2ba9dbbdb` (main plus BenchmarkTrinoPlanCosts, branch `user/dain/bench-trino-plan-costs`), B `eab662aca` (#273 before the fix plus the same class, branch `user/dain/bench-trino-plan-costs-b`). Leg C, fixed #273, joins only if J2 below lists its SHA. Otherwise D1 runs A and B alone, which still explains B on r8i.
- **Rows:** `^io.airlift.regulator.BenchmarkEverydayTrinoRegexp\.(contains|count)$`, `-p workload=quotedField`.
- **Forks:** 2 per leg per op, interleaved A B (C), with the template jvmArgs plus D0's diagnostic flags and `-f 1 -prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>/<leg>-<op>-<fork>.perfasm'`. 8–12 forks, about $0.05.
- **Report:** for each leg and op, the hot region of compiled `matchUnanchoredAt` (or its caller) and the run-reader loop. Give the loop form (counted and unrolled, or a single walk), the instructions and cycles per op, and where contains and count differ. Ship the raw files to S3.

## J1 — #275 fixed: the r8i regression

- **Legs:** A main `11b9291e1`, B fixed #275 `b42f6b6dce606cf01ae8e051f3246062c4bef76c`, branch `user/dain/rust-unicode-word-dfa`.
- **Workloads:** EG-8's W4 files, eg8-kit `eg8-w4-workloads.tar.gz` (sha256 `b5ae68dfd21f21c91cff91bed83b09eb8ffba01fe128d162443815a1f0dc4e81`). Rows, file names and expected counts are as in AWS-JOBS-EG-8.md J1.
- **Rows** (29), `BenchmarkLanguageBulk.execute`:
  1. **No-assertion controls** (16): `rows/curated--03-date--ascii--{java,trino,rust,re2}` (111817), `rows/imported--sherlock--ing-suffix--*` (20547), `rows/imported--sherlock--quotes--*` (14437), `rows/imported--sherlock--before-holmes--*` (4073).
  2. **ASCII twins** (7): `rows/i787-opt-unicode-asciionly--rust` (5674), `rows/imported--sherlock--word-ending-n-asciionly--{java,trino}` (35297), `rows/imported--sherlock--words--{java,trino}` (447639), `rows/unicode--word--boundary-any-english--java` (450561), top `diagnostic--rust-unicode-ascii--word-ending-n` (35297).
  3. **Targets** (5), engine `rust`, top: `curated--08-words--all-russian` (107391), `curated--08-words--long-russian` (5481), `unicode--word--boundary-any-russian` (529194), `unicode--word--boundary-long-russian` (21332), `unicode--word--around-holmes-russian` (44).
  4. **Diagnostic** (1), engine `rust`, top: `diagnostic--greek-words--all-greek` (236000).
- **Forks:** 29 rows × 96 = **2,784 forks, about $0.85**.
- **Predictions** (B/A against main; above 1 is slower):

  | Family | Rows | r9g | r8i | Gate / wrong if |
  |---|---|---|---|---|
  | No-assertion controls | 03-date, ing-suffix, quotes, before-holmes × 4 engines | 0.99–1.02 | **0.99–1.02** (EG-8 1.02–1.054 for 03-date java/rust/re2 and ing-suffix ×4) | **Gate:** any row above 1.02 on both hosts of a platform keeps #275 draft |
  | Rust ASCII twin | i787-opt-unicode-asciionly | 0.98–1.01 | **0.99–1.02** (EG-8 1.054) | **Gate:** above 1.02 on both hosts |
  | Other ASCII twins | word-ending-n-asciionly java/trino, words java/trino, boundary-any-english java, rust-unicode-ascii word-ending-n | 0.99–1.02 | 0.98–1.02 | Wrong if above 1.03 on both hosts |
  | Russian targets | all-russian, boundary-any-russian | 0.26–0.29 | 0.23–0.26 | **Gate:** more than 5% above EG-8 (r9g 0.271 / 0.276, r8i 0.246 / 0.245) |
  | Russian targets | long-russian, boundary-long-russian, around-holmes-russian | 0.024–0.027 | 0.016–0.027 | **Gate:** more than 5% above EG-8 (0.025, 0.025, 0.024 / 0.026, 0.025, 0.017) |
  | Mixed leads | all-greek | 0.43–0.46 | 0.40–0.44 | Wrong if above 0.47 / 0.44 |

- **I am wrong if** the r8i controls stay at EG-8's 1.02–1.05 while D0 shows no stack slot in F. Then the cause is not argument registers. The next suspect is code layout or the start-state setup.
- **Cut order** if the guard nears the cap: the other ASCII twins first, then quotes and before-holmes (which didn't move in EG-8), then Greek.

## J2 — #273 (Trino plans): not ready at this kit's commit
The F1 and F2 fix (a counted ASCII loop in the run reader, and the delimited capture reading its delimiter directly) is being built. If a newer eg9-kit commit adds J2 before launch, run it; otherwise it goes in a later round. Planned size: 28 rows × 96 = 2,688 forks, about $0.82.

## J3 — #276 (Russian literal pair scan): not ready at this kit's commit
The fix is planned: restore main's out-of-line budget call in `findPairMasked` only, which runs on x86 with 64-byte vectors, and keep #276's form in `findPair` for r9g. A D2 perfasm on sherlock-ru (about $0.03) and J3, 6–10 cells at about $0.18–0.29, follow in a later kit commit or a later round.

## Legs

| Job | Leg | SHA | Branch |
|---|---|---|---|
| D0 | M | `11b9291e1c1cc00c18a2662b7588a272961d2ed3` | main |
| D0 | B | `03abd97a04bf4a5b787ece87b5f2be44b164f18a` | user/dain/bench-275-before |
| D0, J1 | F / B | `b42f6b6dce606cf01ae8e051f3246062c4bef76c` | user/dain/rust-unicode-word-dfa |
| J1 | A | `11b9291e1c1cc00c18a2662b7588a272961d2ed3` | main |
| D1 | A | `2ba9dbbdbdbe0ef8569227fb3d3e543d032e8a67` | user/dain/bench-trino-plan-costs |
| D1 | B | `eab662aca5efc4218ed04dcb1aacde6c07269b31` | user/dain/bench-trino-plan-costs-b |
