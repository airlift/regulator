# AWS timing jobs, round EG-13: J5, #278 with the fast match-start pass (CF) against main

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.** Both legs are built on main `11b9291e1`. CF passed its EG-12 D5 loop-shape check:
- on r9g its pass loop holds everything in registers;
- on r8i the loop-carried state (position, stateOffset, nextState) stays in registers, and the 7–10 stack operands are reloads of loop invariants with no stores in the loop;
- perfnorm cycles per op, CF/A: **0.704 on r9g and 0.657 on r8i**.

**Budget.** 12 rows × 96 = **1,152 forks**, about $0.37 at EG-9's $0.000323 per fork. With host start-up and the guard margin, **ask $0.50**. If EG-11's hosts are still up, J5 can follow its timed rows on the same hosts.

**Conventions,** as EG-11:
- **Forks:** 12 per leg per host, on **2 hosts per platform**, **r9g** and **r8i**. Legs interleave fork by fork (ABAB), and both legs of a row run on the same host.
- **Mode:** native only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **JMH:** the class's defaults, with the legs' jars at paths of equal length.
- **Report:** for each row and platform, the median CF/A across both hosts, with the per-host values. Flag (⚑) any host pair that disagrees by more than 5%.

**Workloads:** EG-11 kit `eg11-w5-workloads.tar.gz` (sha256 `f59b1c1b622107c4d40e53247bf22a686419422517af7b9c5f262ae0b196d22c`), passed as in AWS-JOBS-EG-11.md.

## Legs
- **A** `d9ce7dd663268d714f33c8aee406bf2881db5dd0`, branch `user/dain/bench-dense-match-starts-a2`. This is main plus the two timing-only classes. Here count-spans is a caller `find()` loop.
- **CF** `2173b2887d41cf5a70efce73b26eaa5f3eb67d11`, branch `user/dain/bench-dense-match-starts-cf`. This is #278's `ad1c8134e` with the same classes (EG-11's leg C `ed8af0008`), plus one `Dfa.java` change: the reverse match-start pass steps the int transition table and marks match bits branch-free in its loop. Here count-spans calls `forEachMatch`.

## J5 rows (12)
1. **Dense targets,** `BenchmarkLanguageBulk.execute` count-spans (5):
   - `curated--03-date--ascii` × `rust`, `re2` (111817);
   - `curated--03-date--unicode` × `rust` (111841), `re2` (111817);
   - `diagnostic--03-date--sherlock-en` × `rust` (184030).
2. **Trino all-match, dense,** `BenchmarkDenseTrinoAllMatch` (3):
   - `extractAll` and `replace` on `curated--03-date--ascii`;
   - `extractAll` on `diagnostic--03-date--sherlock-en`.
3. **No-switch controls** (4):
   - count-spans `rust` on `diagnostic--03-date--log` (12326) and `diagnostic--03-date--mixed-zh-sparse` (27933);
   - `BenchmarkDenseTrinoAllMatch.extractAll` on `diagnostic--03-date--mixed-zh-sparse`;
   - `BenchmarkEverydayTrinoRegexp.extract` on `keyValue`.

## Predictions (CF/A; below 1 is faster)

| Family | Rows | r9g | r8i | Gate / wrong if |
|---|---|---|---|---|
| Dense date, count-spans | 03-date ascii, unicode × rust, re2 | **0.68–0.80** | **0.62–0.76** | **Gate:** above 0.90 on both hosts of a platform keeps #278 draft. Wrong if above 0.85 |
| Dense date over English | sherlock-en rust | 0.72–0.88 | 0.68–0.85 | Wrong if above 0.92 |
| Trino all-match, dense | extractAll ascii, extractAll sherlock-en | 0.72–0.88 | 0.68–0.85 | Wrong if above 0.92. Result building doesn't shrink |
| Trino replace, dense | replace ascii | 0.78–0.92 | 0.74–0.90 | Wrong if above 0.95. The output buffer is larger |
| No-switch controls | log, mixed-zh-sparse (×2), everyday keyValue extract | 1.00 ±2% | 1.00 ±2% | **Gate:** above 1.02 on both hosts of a platform keeps #278 draft |

- **Where the predictions come from:** D5's perfnorm cycles (0.704 / 0.657) for the count-spans row; JMH time should track cycles within a few percent. Unicode and sherlock-en spend more of their time outside the switch, so I give them wider ranges.
- **I am wrong if** the dense count-spans rows land above 0.85 while D5's cycles said 0.66–0.70. That would mean timing and cycles disagree; first check that the switch fires inside timing on the AWS JDK.
- **Cut order:** the everyday control first, then Trino replace, then sherlock-en extractAll. Never cut the four dense count-spans rows or the mixed-zh-sparse controls.
