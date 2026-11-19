# AWS timing jobs, round EG-28: #282 with E2H, rows 14 and 2 timed against main

For the coordinator to run on AWS Spot. Written 2026-11-19 PST on dain-1. **I wrote the predictions before anything ran.**

**Why this round.**
- **EG-25** failed #282 (F′) on row 14, Trino `quotedField` contains, at r8i 1.021 (r9g 0.977). Row 2 passed at r8i 1.018.
- **EG-26 (untimed D-cell)** found the cause: C2's loop predication of the step loop's invariant `fullMatch` test is a per-compile coin in every leg, main included.
  - On r8i, M, F′ and E2 each hoisted it in 2 of 3 forks.
  - A fork that keeps the test in the loop stores back two invariants at every back edge (1.2–1.4% of cycles). Main's M-1 has that shape too.
- **E2H** decides `booleanResult = groups == null && !fullMatch` once, before the step loop. A failed run returns `-1` when it failed on its first byte and isn't charged; otherwise it returns `~stop` and is charged.
  - EG-26: hoisted in 3 of 3 forks on both platforms; loop-shape pass on every row; F′'s failure-arm sequence gone on rows 1, 2 and 5; inline trees identical.
  - Row 14 cycles against M: r8i 1.003, r9g 0.992.
  - One r8i fork (E2H-3) keeps a two-slot invariant store-back (the input end and slot 0xac) at 0.25% of cycles. The coordinator passed it under the loop-shape rule.
- **#282 now:** E2H folded into #282's single commit, rebased onto main `124c4de87`. Main has not changed the scan-plan files since `0db30b513`, so M here is the same scan-plan code as EG-25's M.

**The coordinator's gate,** as EG-25:
- **A row fails** if E2H′/M is above 1.02 on both hosts of a platform, taken as the median of the per-host ratios. Mode shares are reported beside it.
- **Gated rows:** 14 and 2. **Controls:** 1, 5 and 7, with the wrong-if lines below.
- **If both gated rows pass:** #282 goes to its ready-check, with row 2's cost stated in the PR.

**Budget.** 5 rows × 2 legs × 12 forks × 4 hosts = **480 forks**. EG-25's realized cost for the same shape was $0.133. **Ask $0.20** (approved).

**Conventions,** as EG-25:
- **Forks:** 12 per leg per host, on **2 hosts per platform**, **r9g** and **r8i**. Legs interleave fork by fork (ABAB), and both legs of a row run on the same host.
- **Mode:** native only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **JMH:** the class's defaults, with the legs' jars at paths of equal length. No profilers and no compile-log flags.
- **Report** per row and platform:
  - the median of the per-host E2H′/M ratios, with each host's value; flag (⚑) any host pair that disagrees by more than 5%;
  - **mode shares:** split each host's forks into a fast and a slow mode at an 8% gap in the sorted scores. Give each leg's slow-mode count per host and both modes' medians. Pool both hosts of a platform and give the one-sided Fisher exact p for "E2H′'s slow share is above M's". The p is reported, not gated.
- **Workloads:** the repo's benchmark classes; no files are needed.
- **Parameters:**
  - ScanPlan rows use `-p dotAll=false -p scanPlan=true`.
  - The Rust row also uses `-p unicodePerlClasses=false`.
  - Row 14 uses `-p workload=quotedField`.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| M | `124c4de87de358b002786372f001c9ba77ab7848` | `main` |
| E2H′ | `956672a84fd1c1a91372c525a6463081939f2923` | `user/dain/plan-failed-minimum-budget` (#282) |

E2H′ differs from M only in #282's change and its tests. The benchmark classes are the same on both legs.

## Rows (EG-21 numbering)
| # | Class.method | workload | generateBytecode | Role |
|---|---|---|---|---|
| 14 | `BenchmarkEverydayTrinoRegexp.contains` | `-p workload=quotedField` | (class default) | **gated:** EG-25's failing row |
| 2 | `BenchmarkRe2ScanPlan.count` | `UNANCHORED_URL` | false | **gated:** EG-25 r8i 1.018 |
| 1 | `BenchmarkRe2ScanPlan.contains` | `UNANCHORED_URL` | false | control |
| 5 | `BenchmarkRustScanPlan.contains` | `UNANCHORED_URL` | false | control |
| 7 | `BenchmarkRe2ScanPlan.contains` | `FOLDED_UNANCHORED` | false | control: only E2H hoists here |

## Predictions (E2H′/M; above 1 is slower)

| Row | r9g | r8i | Gate |
|---|---|---|---|
| 14 quotedField | **0.980–1.000** | **0.990–1.010** | above 1.02 on both hosts of a platform |
| 2 count | **1.005–1.020** | **1.005–1.020** | as above |
| 1 contains | 0.985–1.000 | 0.990–1.005 | control |
| 5 contains | 0.985–1.000 | 0.995–1.005 | control |
| 7 folded | 0.985–1.000 | 0.990–1.005 | control |

**Mode shares:**
- **Row 14:** I expect M to show a slow mode on r8i, from its forks that lose the hoist coin (about 1 in 3), and E2H′ none. The Fisher p tests the other direction and should be near 1.
- **Row 2:** I expect both legs to be bimodal, as EG-25 and EG-26 found.
- **Elsewhere:** no slow mode in either leg.

**Where the predictions come from.**
- **Row 14:** EG-26 cycles were r8i 1.003 and r9g 0.992 against M's 2-of-3 hoist. With 24 forks per platform, M's in-loop forks (about 1.4% slower) should pull M's median up less than its mean. So I expect E2H′ at or slightly under M on both platforms.
- **Row 2, honestly:** EG-26 cycles put E2H at 1.0135 of M on r8i and 1.018 on r9g. E2H keeps F′'s second reload of `operation` in the step loop, and r9g keeps F′'s extra spill accesses. EG-25 timed F′ at r8i 1.018 and r9g 1.012, against EG-24 counter ratios of 1.030 and 1.013. **Row 2 is at real risk on r9g,** where E2H's counter ratio (1.018) is no better than F′'s (1.0187).
- **Rows 1, 5 and 7:** EG-26's cycles were all within 1% of M, below M on r9g. Row 7's in-loop test is gone only in E2H.

**I am wrong if:**
- **Row 14 is above 1.01 on either platform's median:** taking the coin away didn't recover row 14, and EG-25's loss has a second cause.
- **Row 14 shows a slow mode in E2H′ on r8i with p < 0.05:** the hoist-by-construction didn't hold under timing.
- **Row 2 fails** (above 1.02 on both hosts of a platform): the reload E2H kept costs more than the counters show. The next step is the coordinator's no-fix ruling, with the cost stated.
- **Row 1 or 5 moves more than 1.5% above M on both hosts of a platform:** the reader's −1 sentinel cost something on the shared executor that row 2 doesn't isolate.
- **Row 7 is above 1.005 on both hosts of a platform:** hoisting the boolean test out of the folded loop cost more than it saved.

**Cut order** if the guard nears the cap:
1. Row 5 (it duplicates row 1).
2. Then row 7.
3. Never cut rows 14, 2 or 1.
