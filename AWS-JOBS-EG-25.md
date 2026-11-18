# AWS timing jobs, round EG-25: #282 rebased, rows 2 and 14 timed against current main

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.**

**Why this round.**
- **The PR:** #282 makes a failed run return the complement of where it stopped, and an unanchored attempt charge the budget up to that stop. It was rebased onto main `0db30b513` as F′ `3b1646d05`.
- **What EG-24 found,** with 3 counter forks per leg, M = main against F′:
  - Row 7 is cleared by the rule; the run readers pass the loop-shape rule.
  - It flagged two rows the rule doesn't cover:
    - **Row 2** (`BenchmarkRe2ScanPlan.count`, `UNANCHORED_URL`): cycles F′/M **1.030 on r8i** (instructions 1.015) and **1.013 on r9g** (instructions 0.977). On r8i, F′'s step loop reloads `operation` a second time and reloads the step reference, where M keeps them in registers. Both slots are already carried in M, so this is not a new carried slot. The forks are bimodal in instructions on both platforms.
    - **Row 14** (Trino `quotedField`, everyday): cycles **1.019 on r8i**. F′'s step loop has two redundant store-backs of invariants (the input end and slot 0x4). F′'s three forks spread 65.4–70.3 cycles per op, against M's 68.1–68.6.
- **Against the old main,** EG-21 timed row 2 faster on both platforms. The rebase onto main's new readers changed what #282 costs, so this round times it against the current main.

**The coordinator's gate,** fixed before this round:
- **A row fails** if F′/M is above 1.02 on both hosts of a platform, taken as the median of the per-host ratios. Mode shares are reported beside it.
- **If row 2 or 14 fails:** a one-path fix for the extra reloads, or a showing that none exists. The cost then goes to the coordinator with the fix that bought it.
- **If both pass:** #282 goes to its ready-check, with row 7's note ("unexplained, not a loop spill").

**Budget.** 5 rows × 2 legs × 12 forks × 4 hosts = **480 forks**. At EG-23's realized $0.00036 per fork that is about $0.17. **Ask $0.22.**
- Rows 3, 8 and 10 are left out. They would add about $0.10, and EG-24's counters had them within ±0.6% instructions on both platforms, with none of the reloads seen in rows 2 and 14.

**Conventions,** as EG-21:
- **Forks:** 12 per leg per host, on **2 hosts per platform**, **r9g** and **r8i**. Legs interleave fork by fork (ABAB), and both legs of a row run on the same host.
- **Mode:** native only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **JMH:** the class's defaults, with the legs' jars at paths of equal length. No profilers and no compile-log flags.
- **Report** per row and platform:
  - the median of the per-host F′/M ratios, with each host's value; flag (⚑) any host pair that disagrees by more than 5%;
  - **mode shares:** split each host's forks into a fast and a slow mode at an 8% gap in the sorted scores. Give each leg's slow-mode count per host and both modes' medians. Pool both hosts of a platform and give the one-sided Fisher exact p for "F′'s slow share is above M's". The p is reported, not gated.
- **Workloads:** the repo's benchmark classes; no files are needed.
- **Parameters:**
  - ScanPlan rows use `-p dotAll=false -p scanPlan=true`.
  - The Rust row also uses `-p unicodePerlClasses=false`.
  - Row 14 uses `-p workload=quotedField`.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| M | `0db30b513a5cf79e685be74b1120afdd2c5516a2` | `main` |
| F′ | `3b1646d059570a38bc20f6252cb964ff9e2a8bae` | `user/dain/plan-failed-minimum-budget` (#282) |

F′ differs from M only in #282's change and its tests. The benchmark classes are the same on both legs.

## Rows (EG-21 numbering)
| # | Class.method | workload | generateBytecode | Why |
|---|---|---|---|---|
| 2 | `BenchmarkRe2ScanPlan.count` | `UNANCHORED_URL` | false | **gated:** EG-24's r8i 1.030 / r9g 1.013 cycles, with the extra reloads named |
| 14 | `BenchmarkEverydayTrinoRegexp.contains` | `-p workload=quotedField` | (class default) | **gated:** an everyday row, EG-24's r8i 1.019 cycles |
| 1 | `BenchmarkRe2ScanPlan.contains` | `UNANCHORED_URL` | false | link to EG-21: the same executor as row 2, through contains |
| 5 | `BenchmarkRustScanPlan.contains` | `UNANCHORED_URL` | false | link to EG-21 |
| 7 | `BenchmarkRe2ScanPlan.contains` | `FOLDED_UNANCHORED` | false | link to EG-21: EG-21's r8i +2%, cleared by EG-24 |

## Predictions (F′/M; above 1 is slower)

| Row | r9g | r8i | Gate |
|---|---|---|---|
| 2 count | **1.000–1.020** | **1.010–1.035** | above 1.02 on both hosts of a platform |
| 14 quotedField | 0.995–1.010 | **1.000–1.025** | as above |
| 1 contains | 0.990–1.000 | 1.000–1.010 | as above |
| 5 contains | 0.990–1.000 | 0.995–1.005 | as above |
| 7 folded | 0.995–1.005 | 1.000–1.008 | as above |

Mode shares: I expect row 2 to show a slow mode in both legs on both platforms (EG-24's instruction counts were bimodal in both). I expect F′'s slow share to be no higher than M's (Fisher p > 0.05) on every row.

**Where the predictions come from.**
- **Timed ratios should track EG-24's cycle ratios,** which matched ns/op within 0.1% on every row, pulled toward 1 by the larger sample.
- **Row 2 r8i:** the two extra reloads sit inside the step loop the count runs for every attempt. Instructions are +1.5%, so I put it at real risk of failing on r8i.
- **Row 2 r9g:** 2.3% fewer instructions but 1.3% more cycles, which points to the extra spill accesses (22 against 14) sitting on the loop's dependency chain. I expect it to land under the gate.
- **Row 14 r8i:** the 1.019 rests on one wide F′ fork spread (65.4–70.3). I expect 12 forks per host to pull it toward 1.01, but it could fail.
- **Rows 1, 5 and 7:** EG-24's cycles were within ±0.7% on both platforms.

**I am wrong if:**
- **Row 2 r8i comes in at or below 1.005 on both hosts.** The reloads EG-24 named would then cost nothing timed, and its counter ratio was fork state.
- **Row 2 r9g fails** (above 1.02 on both hosts). The cost would then be larger than the spill accesses explain, and the r9g mechanism needs its own look.
- **Row 1 or 5 moves more than 1.5% on both hosts of a platform.** They run the same executor and reader as row 2, so a move would mean F′'s cost isn't confined to count's step loop.
- **F′'s slow-mode share on any row is above M's at p < 0.05.** The rebase would then have changed a compile race, not just a loop.

**Cut order** if the guard nears the cap:
1. Row 5 (it duplicates row 1).
2. Then row 7.
3. Never cut rows 2, 14 or 1.
