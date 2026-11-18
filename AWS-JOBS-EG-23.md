# AWS timing jobs, round EG-23: J6, #278 R2' against main

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.** It retimes EG-13's rows and EG-11's words and log rows on #278's current code.

**Why this round.**
- In EG-13, #278 (CF) won every dense row (0.59–0.73). But r9g mixed-zh-sparse count-spans tripped the 1.02 no-switch gate at 1.029.
- In EG-11, r9g words all-english count-spans was 1.041 and Trino extractAll log 1.025.
- **EG-19 (D11) named the causes:**
  - **mzs:** the `forEachMatch` caller loop. With `findGeneral` out of line, the match count `forEachMatch` returned stayed live across every find, plus the matcher: 48 instructions per find with 3 stack loads and 3 stores, against A's 46 with 1 and 1. The placebo ruled out `findGeneral`'s size.
  - **words and log** were bimodal by fork state or compile shape, so 12 forks can't settle 1–2%.
- **R2' (#278 head `921e74970`):**
  - **R:** ordinary finds count nothing, and only all-match operations arm an UNDECIDED start set.
  - **`forEachMatch` returns `void`.**
  - **R's Trino extractAll and replace helpers stay.** Without them extractAll never inlined into the benchmark caller on dain-1 (0 of 7); with them it does in most runs. It is a compile-order race (EG-19 r9g 2 of 3; dain-1 2 of 4 JMH and 3 of 4 harness).
- **dain-1 exact counts (no timing):**
  - **The caller loop is A's 46 instructions per find.** It spills one more value than A, the loop-invariant matcher, because `forEachMatch`'s try/finally reads it in its handler. That passes the loop-shape rule, and the try/finally is the reservation-release contract.
  - **Instructions per op:** words all-english 6.402M, equal to A (R was 6.432M). mzs 11.896M, against A's 11.85–11.87M; the rest is in `findGeneral`. Trino extractAll log 7.235M, against A's 7.307M.
  - **Answers:** the switch probes are identical to R's, and 422 tests pass.

**Budget.**
- 12 rows × 96 = 1,152 forks, plus 3 rows × 192 = 576 forks, for **1,728 forks**: about $0.56 at $0.000323 per fork.
- The log row's forks carry compile logs.
- With host start-up and the guard margin, **ask $0.80**.

**Conventions,** as EG-13:
- **Hosts:** 2 per platform, **r9g** and **r8i**. Legs interleave fork by fork (ABAB), and both legs of a row run on the same host.
- **Forks:** **12 per leg per host,** except the three mode-share rows below, which run **24 per leg per host**.
- **Native** mode only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- The class's JMH defaults, with the legs' jars at paths of equal length.
- **Workloads:** EG-11's kit `eg11-w5-workloads.tar.gz` (sha256 `f59b1c1b622107c4d40e53247bf22a686419422517af7b9c5f262ae0b196d22c`), passed as in AWS-JOBS-EG-11.md.
- **Report** per row and platform: the median R2'/A across both hosts, with the per-host values. Flag (⚑) any host pair that disagrees by more than 5%.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| A | `d9ce7dd663268d714f33c8aee406bf2881db5dd0` | `user/dain/bench-dense-match-starts-a2` (main plus the two timing-only classes; count-spans is a caller `find()` loop) |
| R2' | `c80a6be8e8ba6c0324669df88363e50e50eb25e1` | `user/dain/bench-dense-match-starts-r2p` (#278 head `921e74970` plus the same classes; count-spans calls `forEachMatch`, whose consumer sums the spans) |

If #278's src/main changes before this round's first timed fork, the manager tells the coordinator, then the runner.

## Rows

**Standard rows (12), as EG-13 J5, 12 forks per leg per host:**
1. **Dense count-spans,** `BenchmarkLanguageBulk.execute`:
   - `curated--03-date--ascii` × `rust`, `re2`;
   - `curated--03-date--unicode` × `rust`, `re2`;
   - `diagnostic--03-date--sherlock-en` × `rust`.
2. **Trino all-match, dense,** `BenchmarkDenseTrinoAllMatch`: `extractAll` and `replace` on `curated--03-date--ascii`; `extractAll` on `diagnostic--03-date--sherlock-en`.
3. **No-switch controls:**
   - count-spans `rust` on `diagnostic--03-date--log` and `diagnostic--03-date--mixed-zh-sparse`;
   - `BenchmarkDenseTrinoAllMatch.extractAll` on `diagnostic--03-date--mixed-zh-sparse`;
   - `BenchmarkEverydayTrinoRegexp.extract` on `keyValue`.

**Mode-share rows (3), 24 forks per leg per host:**
13. count-spans `rust` on `curated--08-words--all-english` (56691).
14. count-spans `rust` on `imported--sherlock--words` (447639).
15. `BenchmarkDenseTrinoAllMatch.extractAll` on `diagnostic--03-date--log`.
   - Its forks in both legs add `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<host>-<fork>.xml`.
   - From each log, report:
     - the final C2 instruction bytes of `Dfa::searchAnchoredCandidate` and `Dfa::searchCandidateBoundary`;
     - whether Trino `extractAll` (or R2''s helper) inlined into the benchmark caller.

## Grading the mode-share rows (the coordinator's rule, as U2)
- **Split each host's forks into a fast and a slow mode** at an 8% gap in the sorted scores.
  - For row 15, split by the DFA search compile shape where the logs show distinct shapes. Use the 8% gap otherwise.
- **Mode share:** pool both hosts of a platform. Compare R2''s slow-mode share with A's with a one-sided Fisher exact test. **Fail at p < 0.05.**
- **Fast state:** grade the R2'/A ratio of medians within the shared fast mode against the gate below.
- Report the per-leg slow-mode counts, both modes' medians, and for row 15 the inlining count per leg.

## Predictions (R2'/A; below 1 is faster)

| Family | Rows | EG-13 CF (r9g / r8i) | r9g | r8i | Gate / wrong if |
|---|---|---|---|---|---|
| Dense date count-spans | 03-date ascii, unicode × rust, re2 | 0.696–0.702 / 0.651–0.685 | **0.66–0.74** | **0.60–0.72** | **Gate:** above 0.90 on both hosts. Wrong if above 0.80 |
| Dense over English | sherlock-en rust | 0.707 / 0.594 | 0.66–0.76 | 0.55–0.67 | Wrong if above 0.82 |
| Trino all-match, dense | extractAll ascii, sherlock-en; replace ascii | 0.722–0.730 / 0.655–0.731 | 0.68–0.78 | 0.62–0.77 | Wrong if above 0.85 |
| No-switch, mzs count-spans | mixed-zh-sparse rust | **1.029** / 1.006 | **1.000–1.015** | 0.99–1.01 | **Gate:** above 1.02 on both hosts of a platform keeps #278 draft |
| No-switch, other | log count-spans, mzs Trino extractAll, keyValue extract | 1.003 / 1.007, 0.998 / 1.010, 1.033⚑ / 1.004 | 0.99–1.015 | 0.99–1.015 | **Gate:** as above. keyValue is bimodal (EG-13 fast mode ≈1.007), so report its mode split too |
| Words, mode-share | all-english, sherlock words | EG-11: 1.041 / 1.001, 1.010 / 1.000 | fast state **0.99–1.01**; slow share not above A's | the same | **Gate:** Fisher p < 0.05, or a fast-state ratio above 1.02 |
| Trino log, mode-share | extractAll log | EG-11: 1.025 / 0.992⚑ | fast state 0.98–1.02; slow share not above A's | the same | **Gate:** as words |

**Where the predictions come from.**
- **Dense rows:** R2' changes nothing on the switched path except dropping the count increment, so EG-13's CF values hold, ±0.04.
- **mzs:** EG-19 put R's cost in the caller loop (1.022 cycles at 1.005 instructions). R2' runs A's loop instructions and adds only the invariant matcher reload, and its instructions are 1.002–1.004 of A. I expect most of R's cycle cost gone.
- **words:** instructions are equal to A's. EG-19's fast state already had R at 1.002.
- **log:** instructions are 0.99 of A. Whether extractAll inlines into the caller is a race in both legs.

**I am wrong if:**
- **mzs count-spans stays above 1.02 on both r9g hosts.** Then the invariant matcher spill, or `findGeneral`'s bookkeeping (+23k instructions per op), costs more than counted. The next step is a D-cell with a no-try/finally probe leg.
- **Words' fast state is above 1.01, or its slow share is higher than A's.** Then the `forEachMatch` loop costs something outside instructions.
- **Trino extractAll log shows R2' inlined in clearly fewer forks than A, with a slow share above A's.** Then the helper race costs this row.
- **Any dense row is above its "wrong if."** Then the switch lost something in R or R2'. First check that the start-set passes still fire inside timing.

**Cut order** if the guard nears the cap:
1. keyValue extract first.
2. Then Trino replace ascii.
3. Then sherlock words (row 14).

Never cut the four 03-date count-spans rows, mzs count-spans, words all-english or Trino extractAll log.
