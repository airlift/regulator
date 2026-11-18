# AWS timing jobs, round BC-2: what E1 buys at smaller budgets

This round is for the coordinator to run on AWS Spot. I wrote it on dain-1 on 2026-11-17 PST, against the final E1 head. **I wrote the predictions before anything was timed.** The only inputs were untimed counters. Hold: $1.98.

**Estimate and cap.** Q1–Q7 come to 144 legs and **6,912 forks** (12 forks × 2 hosts × 2 platforms per leg). Bulk forks are priced at BC-1's $0.00025 each ($1.517 / 6,048) and uap-core forks at $0.0004. That makes **$1.94 at the midpoint, $1.6–2.3 at ±20%.** Q8 (parseBrowser) brings the total to $2.17 and runs only if money remains. **Cap: $1.98.** If the run trends over, cut from the tail in this order: Q8, then Q7 (keep url-tld-1of64 and two BC-1 controls), then Q6's OS cell, then Q6.

| Through | Legs | Forks | $ (mid) |
|---|---|---|---|
| Q1 Trino url/search | 16 | 768 | 0.19 |
| Q2 two-phase | 24 | 1,152 | 0.29 |
| Q3 uap-core rule 51, UA | 38 | 1,824 | 0.56 |
| Q4 mid-size url | 78 | 3,744 | 1.04 |
| Q5 risk row + grapheme | 100 | 4,800 | 1.30 |
| Q6 rule 51, CRAWLERS + OS | 116 | 5,568 | 1.61 |
| Q7 controls | 144 | 6,912 | 1.94 |
| Q8 parseBrowser (if money remains) | 156 | 7,488 | 2.17 |

Every leg is in `LEGS.tsv`; the kit branch `user/dain/budget-curve-bc2-kit` holds this file, `LEGS.tsv` and `executeCollected-runner.patch`. (one row per leg: priority, benchmark, row, language, mode, jar, budget).

## Jars and engines

- **base:** `user/dain/budget-curve-bc2-base` `82161752f12e09bcebf3bae0ece06f4e82232f6f`. It is uap-core 766ee00b3 (engine = origin/main 11b9291e1), plus the bulk `maxMemoryMiB` param (cherry-picked from 29ae91f85), plus `executeCollected`.
- **bench:** `user/dain/budget-curve-bc2-bench` `db88f7cdfb3aa78401679964b2717e4f35ed15ca`. It is base plus the E1 engine commits, PR #280 head `1dd85f5d7`. Its src/main is identical to 1dd85f5d7.
- Build both as `tools/re2-benchmark/language/README.md` describes: `./mvnw test-compile`, then `dependency:build-classpath -DincludeScope=test`.

## Conventions (as BC-1 unless stated)

- **Bulk legs:** `BenchmarkLanguageBulk.execute`, `-p maxMemoryMiB=<leg>`, `-p engine=… -p workloadFile=… -p expectedResult=…`, with the class's JMH defaults (10 × 1 s warm-up and measurement, avgt), `-f 1` per job.
- **uap-core legs:** `BenchmarkUapCore.rule51FindResult` and `BenchmarkUapCore.parseBrowser`, with `-p corpus=UA_TESTS|OS_TESTS|CRAWLERS -p ruleMemoryMiB=<leg> -p atomSetMemoryMiB=64`, and that class's JMH defaults.
- **JVM:** `--add-modules=jdk.incubator.vector -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`, plus `--enable-native-access=ALL-UNNAMED` in native mode.
- **Platforms and order:** r9g and r8i, 2 hosts each, 12 forks per leg per host. All of a row's legs, base and bench alike, run on the same host, interleaved fork by fork.
- **Per-fork timeout:** 15 min. A timeout is reported as a lower bound and not retried.
- **Report:** per row, mode and platform, give the median **bench/base ratio at each budget**, the median **leg/32 ratio for each jar**, and the 32 leg's ns/op. Flag a host pair more than 5% apart.
- **Workloads:**
  - Bulk-manifest rows are matched by sha256, as in BC-1.
  - The grapheme row is `ca0ec09f…` from BC-1's tarball (`user/dain/budget-curve-bc1-workloads`).
  - The seven new Trino workloads are on the orphan branch **`user/dain/budget-curve-bc2-workloads` `58f20ec85`**, in `bc2-workloads.tar.gz` (10,449,197 B, sha256 `ac31b98d8fc09c4b9446bed9fb40a6e5eb802875c0f0f9ca68f1414b1eaf6ffe`). Its `WORKLOADS.tsv` gives each workload's name, sha256 and expected result per op.

## The two-phase cell and its GC caveat (Q2)

Workload `twophase-ab` is a Trino `(?i)` alternation of 1,000 words (9,676 instructions), searched on text from words 0–499 and then 500–999. The control `twophase-b` runs only the second half. Both run at 16 MiB.

- **Safe mode is the primary leg.** It is deterministic:
  - Base resets twice per op with the table held, rebuilding about 7,700 states.
  - E1 holds all 5,520 states on object rows with no reset.
- **Native mode is the check, and it runs `executeCollected`**, which calls `System.gc()` before each op. Without a collection, base's superseded-segment cap withholds the table after 3–4 ops, base then runs on object rows too, and the cell measures garbage-collection timing instead of the rule. With a collection before each op, base keeps and rebuilds its table as in safe mode.
- **Caveat, to repeat beside the results:** the native leg's base behavior depends on GC. `executeCollected` pins it to the collected case, which is not what an application without frequent collections sees. That case resembles E1, since both run on object rows.
- **Runner change needed:** `bulk.py` (`prepare` ~line 68, `validate_manifest` ~92) requires the manifest's operations to be `["compile", "execute"]`. `collection.py` builds the selector as `^<class>\.<operation>$` (~860) and rejects other benchmark names (~700). `executeCollected-runner.patch` (in this kit) is that change: `execute` stays the manifest operation, and `REGULATOR_BULK_EXECUTE_METHOD=executeCollected`, set for this leg only, makes the selector and the result check use `executeCollected`. The two-phase base and bench jars both carry the method, and its stub is the only new one: `compile`, `execute` and `iterateMatches` generate byte-identical stubs.

## Counters behind the predictions (untimed, dain-1)

**Trino url/search, forward resets per 9 ops** (E1 counters, `../../../budget-curve-dfa-table/benchmarks/e1-counters-2026-11-17/`):

| | 32 | 24 | 16 | 8 |
|---|---|---|---|---|
| base safe | 0 | 12 | 44 | 388 |
| base native | 0 | 4 | 13 | 78 |
| E1 (both modes) | 0 | 0 | 9 | 71 |

**Mid-size url-tld cells** (`e1-counters/SUMMARY.tsv`, `TwoPhaseProbe` over 9 ops). Each cell shows forward resets / states built; checksums are identical in every cell:

| row | 16 safe base → E1 | 16 native base → E1 | 8 safe base → E1 | 8 native base → E1 |
|---|---|---|---|---|
| url-tld-1of16 | 13 / 42,490 → 0 / 3,393 | 3 / 12,192 → 0 / 3,393 | 89 / 139,592 → 27 / 61,805 | 34 / 71,665 → 27 / 61,805 |
| url-tld-1of8 | 18 / 51,213 → 0 / 3,539 | 4 / 14,803 → 0 / 3,539 | 107 / 163,352 → 24 / 62,133 | 31 / 69,778 → 24 / 62,133 |
| url-tld-1of4 | 17 / 55,722 → 0 / 3,820 | 3 / 13,124 → 0 / 3,820 | 215 / 271,046 → 50 / 108,683 | 59 / 116,153 → 50 / 108,683 |
| url-tld-1of2 | 19 / 59,643 → 0 / 4,014 | 3 / 13,240 → 0 / 4,014 | 279 / 337,316 → 58 / 125,936 | 69 / 135,375 → 58 / 125,936 |

The controls show no change between base and E1: url-tld-1of64 at 16 MiB (0 resets, 3,198 states, table held) and url-tld-1of2 at 24 MiB (0 resets, 4,014 states, table held).

## Predictions (bench / base at the same budget, median of hosts)

**Q1, Trino url/search.** BC-1 base leg/32 was 5.9 / 9.8 / 25 in safe mode and 1.31 / 6.0 / 13.8 in native mode, at 24 / 16 / 8 MiB.
- **24 MiB:** safe **0.17–0.25**, native **0.80–1.05**. E1 runs on object rows with no resets, which native base mostly does already.
- **16 MiB:** safe **0.40–0.60**, native **0.65–0.90**.
- **8 MiB:** safe **0.20–0.35**, native **0.85–1.00**.
- **32 MiB:** **1.00 ± 0.03**.
- **Wrong if:** native at 24 MiB is above 1.10, which would mean object rows cost more than a few native resets; or safe at 16 MiB is above 0.75.

**Q2, two-phase (16 MiB).**
- **twophase-ab, safe:** **0.90–1.15**. E1 trades one rebuild of about 7,700 states per op for object-row steps.
- **twophase-ab, native with a collection before each op:** within ±0.05 of the safe ratio.
- **twophase-b, safe:** **1.00 ± 0.03**, with identical counters.
- **Wrong if:** twophase-ab is above 1.40.

**Q3/Q6, uap-core rule 51** (predictions from PLAN.md, made before any build):
- **UA at 16 MiB:** E1 drops the table after the first budget-bound generation, so both modes reset once in total, which is native's behavior today. Safe: bench leg/32 lands within ±10% of base native's 16 MiB leg/32. Native: **1.00 ± 0.05**.
- **8 and 4 MiB** (UA, CRAWLERS, OS 4): still thrashing. Safe improves toward native. Native is **1.00 ± 0.10**.
- **32 MiB:** **1.00 ± 0.03**.

**Q4, mid-size url-tld.**
- **16 MiB:** E1 builds each state once, like the 32 MiB control, so bench leg/32 is **1.0–1.4**, the object-row step cost. Bench/base: safe **0.20–0.50**, native **0.50–0.95**.
- **8 MiB:** safe **0.20–0.45**, native **0.75–1.00**.
- **Wrong if:** any bench/base ratio is above 1.05.

**Q5.**
- **10-bounded-repeat/context (risk row), Trino and RE2, 32 and 16 MiB:** **0.85–1.10**. E1 gives the table back, so forward resets fall from 7–11 to 4 and the remaining steps run on object rows. **Wrong if:** above 1.15.
- **grapheme/codepoints, rust, 4 MiB:** bench leg/32 **1.00 ± 0.05**, because part 1 keeps the table (593 rows fit, 526 needed). Bench/base **0.60–0.75**, against base's 1.58 / 1.41.

**Q7, controls.** **1.00 ± 0.03**, with identical counters.

**Q8, parseBrowser, 8 MiB.** The rule-51 direction, diluted: safe **0.85–1.00**, native **0.95–1.05**.
