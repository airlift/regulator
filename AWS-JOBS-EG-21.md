# AWS timing jobs, round EG-21: #282, failed run minimums charged to the plan budget

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.**

**Why this round.**
- PR #282 fixes a work-budget flaw on main in every language. A run that fell short of its minimum returned `-1`, and the attempt was charged nothing, so a search over blocks just under the minimum was quadratic and never handed off.
- **The fix:** a failed run returns the complement of where it stopped. A failed unanchored attempt charges up to there unless the run failed on its first byte.
- **Hot-path changes:**
  - each reader's failure return (`~stop` or `~cursor` instead of `-1`);
  - one compare and a `max` in each unanchored failure arm, interpreted and generated.
  - Success arms, anchored and partial executors, searches and `re2MatchBefore` are unchanged.
- **Local evidence on dain-1 (exact counts, no timing):**
  - **Routes:** across 284 configurations (the four ScanPlan classes' workloads in every language, interpreted and generated, plus every `BenchmarkEverydayTrinoRegexp` workload), main and the fix make **the same route choices: zero hand-offs on both,** with identical answers.
  - **The charge never fires on these rows:** every failed run on them fails on its first byte. The rows below are the ones with the most failed runs reaching the changed code (63–84 per pass of 504 rows).
  - **Sizes:** of 314 generated methods on these rows, only `matchUnanchoredAt` changed size. UNANCHORED_SET goes 236 → 278 B and quotedField 225 → 246 B, both still at or under FreqInlineSize 325. UNANCHORED_URL (545 → 565) and keyValue (352 → 436) were already over 325 on main.
  - Interpreted `ScanPlan.matchUnanchoredAt` goes 1,090 → 1,210 B and `matchAsciiFoldedUnanchoredAt` 1,153 → 1,273 B; both were already out of line.
  - The generated public entries that `TestHotMethodSize` pins are unchanged.

**Budget.** 14 rows × 96 = **1,344 forks**, about $0.43 at $0.000323 per fork. With host start-up and the guard margin, **ask $0.60**.

**Conventions,** as EG-20:
- **12 forks per leg per host,** on **2 hosts per platform**, **r9g** and **r8i**. Legs interleave fork by fork (ABAB), and both legs of a row run on the same host.
- **Native** mode only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- The class's JMH defaults, with the legs' jars at paths of equal length.
- **Report** per row and platform: the median F/A across both hosts, with the per-host values. Flag (⚑) any host pair that disagrees by more than 5%.
- **Workloads:** the repo's benchmark classes; no files are needed.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| A | `bdf26dc338b73e087df01c4fe60938cb9842415e` | `main` |
| F | `d03bdc07d39e80f9bda2eaf7a4537be0856264ed` | `user/dain/plan-failed-minimum-budget` (#282) |

Both legs contain the benchmark classes unchanged; F differs from A only in the fix and its tests.

## Rows (14)
Every ScanPlan row uses `-p dotAll=false`; the RE2, Java and Rust rows also use `-p scanPlan=true`.
- **Java rows:** `-p javaDollar=false -p javaLineTerminators=false`.
- **Rust rows:** `-p unicodePerlClasses=false`.
- The dain-1 counts show both settings of these two classes' extra parameters give identical routes.

| # | Class.method | workload | generateBytecode | Why |
|---|---|---|---|---|
| 1 | `BenchmarkRe2ScanPlan.contains` | `UNANCHORED_URL` | false | literal-led, interpreted, 64 failed runs per pass |
| 2 | `BenchmarkRe2ScanPlan.count` | `UNANCHORED_URL` | false | the same through count |
| 3 | `BenchmarkTrinoScanPlan.contains` | `UNANCHORED_URL` | true | literal-led, generated |
| 4 | `BenchmarkJavaScanPlan.contains` | `UNANCHORED_URL` | true | literal-led, generated |
| 5 | `BenchmarkRustScanPlan.contains` | `UNANCHORED_URL` | false | literal-led, interpreted |
| 6 | `BenchmarkTrinoScanPlan.contains` | `FOLDED_UNANCHORED` | false | ASCII-folded executor, 84 failed runs per pass |
| 7 | `BenchmarkRe2ScanPlan.contains` | `FOLDED_UNANCHORED` | false | ASCII-folded executor |
| 8 | `BenchmarkTrinoScanPlan.contains` | `UNANCHORED_SET` | false | 63 failed runs per pass |
| 9 | `BenchmarkTrinoScanPlan.count` | `UNANCHORED_SET` | false | the same through count |
| 10 | `BenchmarkRe2ScanPlan.contains` | `UNANCHORED_SET` | true | generated |
| 11 | `BenchmarkJavaScanPlan.contains` | `UNANCHORED_SET` | true | generated |
| 12 | `BenchmarkRustScanPlan.contains` | `UNANCHORED_SET` | false | interpreted |
| 13 | `BenchmarkEverydayTrinoRegexp.contains` | `-p workload=keyValue` | (class default) | the everyday row with failed runs |
| 14 | `BenchmarkEverydayTrinoRegexp.contains` | `-p workload=quotedField` | (class default) | everyday literal-led control, no failed runs |

## Predictions (F/A; above 1 is slower)

| Rows | r9g | r8i | Gate / wrong if |
|---|---|---|---|
| 1–12 (plan rows with failed runs) | **0.99–1.01** | **0.99–1.01** | **Gate:** above 1.02 on both hosts of a platform keeps #282 draft |
| 13 keyValue | 0.99–1.01 | 0.99–1.01 | **Gate:** as above |
| 14 quotedField (control) | 1.00 ±1% | 1.00 ±1% | **Gate:** above 1.02 on both hosts |

**Where the predictions come from.**
- Routes are identical and the new charge never fires on these rows.
- The executed difference per failed run is one compare (`~stop > cursor`) plus the reader's complement, against 63–84 failed runs in a pass of 504 searches.
- That is well under 0.1% of each row's work.
- No inline decision can change: the only methods that grew stay on the same side of 325 B.

**I am wrong if:**
- **Any row moves more than 2% in either direction on both hosts of a platform.** With identical routes and unchanged inline thresholds, a move that size means code layout or a compile-shape change.
  - First check whether the row is one of the generated ones (3, 4, 10, 11) whose `matchUnanchoredAt` grew.
  - If so, a compile-log fork of that row on both legs is the next step.
- **The control (14) moves more than 1% on both hosts:** that is host noise, and the round's other margins shrink to match.

**Cut order** if the guard nears the cap:
1. Row 2, then row 9 (the count duplicates).
2. Then rows 5 and 12 (the interpreted Rust rows).
3. Never cut rows 3, 6, 10, 13 or 14.
