# AWS timing jobs, round BC-3: what I21's funded growth buys

This round is for the coordinator to run on AWS Spot. I wrote it on dain-1 on 2026-11-19 PST, against the final I21 head, PR #283. **I wrote every prediction below before anything was timed.** The only inputs were untimed counters from the final-head census (`../i21-census-final-2026-11-19/`). The budget-curve hold is $0.703.

**Estimate and cap.** The round is 80 legs and 3,840 forks: 12 forks × 2 hosts × 2 platforms per leg. Forks are priced at BC-2's actual cost, $1.977 / 7,488 = $0.000264 each.

| Through | Legs | Forks | $ (mid) |
|---|---|---|---|
| P1 Trino url/search, url-tld, bounded-repeat context | 42 | 2,016 | 0.53 |
| P2 compressed oops off | 56 | 2,688 | 0.71 |
| P3 other languages' URL rows, side effects, controls | 80 | 3,840 | 1.01 |

**P1 + P2 fits the hold, give or take a cent. The full round needs about $0.35 more:** I ask for a cap of $1.05. If the run trends over, cut in this order:
1. P3's rule-51 legs, then the rest of P3 from the bottom of `LEGS.tsv` up.
2. Then P2's url-tld legs.

`LEGS.tsv` holds one row per leg: priority, group, benchmark, row, language, mode, jar, budget, extra JVM flags, and the counter note behind the prediction. The kit branch `user/dain/budget-curve-bc3-kit` holds this file and `LEGS.tsv`.

## Jars

- **base:** `user/dain/budget-curve-bc3-base` `8ede6b7b0ccd756d5de12eec588363aa3c475879`. It is origin/main `124c4de87` plus BC-2's two timing-only commits: the bulk `maxMemoryMiB` parameter and `executeCollected`, cherry-picked from `92209e797` and `82161752f`.
- **bench:** `user/dain/budget-curve-bc3-bench` `b348b503aa80da23700ca9fba2d8a09a09283d38`. It is base plus PR #283's two commits: count-test fixtures, then I21. Its `src/main` is identical to PR #283's head `273eae804`.
- Build both as BC-2 did: `./mvnw test-compile`, then `dependency:build-classpath -DincludeScope=test`. `TestLanguageBulkBenchmark` passes on both.

## Conventions (as BC-2 unless stated)

- **Bulk legs:** `BenchmarkLanguageBulk.execute` with `-p maxMemoryMiB=<leg>`, and `engine`, `workloadFile` and `expectedResult` as before. The class's JMH defaults apply, with `-f 1` per job.
- **uap-core legs:** `BenchmarkUapCore.rule51FindResult` with `-p corpus=UA_TESTS -p ruleMemoryMiB=<leg> -p atomSetMemoryMiB=64`.
- **JVM:** `--add-modules=jdk.incubator.vector -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`, plus `--enable-native-access=ALL-UNNAMED` in native mode.
- **New in this round:** the `jvm_extra` column, appended to the JVM flags of that leg only. P2 uses it for `-XX:-UseCompressedOops`. With an 8 GB heap the JVM would keep compressed oops on, so the flag forces the large-heap layout. Base and bench legs of a P2 cell both carry it.
- **Platforms, order and timeout:** r9g and r8i, 2 hosts each, 12 forks per leg per host, with base and bench of a row interleaved fork by fork on the same host. Each fork has a 15-minute timeout.
- **Report:** per row, mode and platform, give:
  - the median bench/base ratio at each budget;
  - the median leg/32 ratio for each jar, including within P2, where 32 means 32 MiB with oops off;
  - the 32 leg's ns/op.

  Flag any host pair more than 5% apart.
- **Workloads:** these are BC-2's.
  - Bulk-manifest rows are matched by sha256.
  - The url-tld and twophase workloads come from `user/dain/budget-curve-bc2-workloads` `58f20ec85` (`bc2-workloads.tar.gz`, sha256 `ac31b98d…6ffe`).

## Counters behind the predictions

These come from the final-head census: 9 ops, control op counts, untimed. They show forward resets per 9 ops, main → I21.

| Row | 32 | 24 | 16 | 8 |
|---|---|---|---|---|
| Trino url/search, both modes | 0 → 0 (8,192 → 6,262 rows) | 0 → 0, table released → **held** | **9 → 0**, released both | **71 → 50** |
| url-tld-1of2 | – | identical | 0 → 0, released, 4,096 → 5,552 rows | **58 → 32** |
| url-tld-1of4 | – | identical | identical | **50 → 27** |
| bounded-repeat context, RE2 and Rust | **14 → 36** | 14 → 14 | 14 → 14 | 17 → 17 |
| RE2 url/search | 0 → 0 | 0 → 0 | 0 → 0, released → **held** | **300 → 240** |
| Java url/search | 0 → 0 | 0 → 0 | 0 → 0 | **399 → 299** |

**Compressed oops off**, Trino safe mode (native matches). Resets per op, main / I21:

| Row | 32 | 24 | 16 | 8 |
|---|---|---|---|---|
| url/search | 0 / 0, held | 0 / 0, released both | 1.44 / 1.44 | 13.44 / 11.89 |
| url-tld-1of2 | 0 / 0 | 0 / 0 | **1.00 / 1.11** | 9.00 / 9.00 |
| url-tld-1of4 | 0 / 0 | 0 / 0 | 0 / 0 | 7.22 / 6.89 |

## Predictions (bench / base at the same budget, median of hosts)

**P1, Trino url/search.** BC-2's base for this round is E1, now on main. At 24 / 16 / 8 MiB its leg/32 was:
- safe: 1.33 / 5.99 / 13.96 on r9g, and 1.19 / 6.03 / 14.32 on r8i;
- native: 1.31 / 5.97 / 13.84 on r9g, and 1.37 / 6.95 / 16.58 on r8i.

Predicted bench/base:
- **32 MiB:** 0.97–1.03. The state tables are smaller and the pointer table larger, with the same states.
- **24 MiB:** 0.70–0.90 in both modes. Base gave its table back and runs on object rows; I21 keeps the table with no resets, so its leg/32 should be about 1.0–1.1.
- **16 MiB:** 0.18–0.30 in both modes. Base resets once per op; I21 holds all 4,186 states on object rows, a leg/32 of about 1.2–1.4.
- **8 MiB:** 0.65–0.90. Resets fall by 30%, but each generation now holds more states, so each rebuild costs more.
- **Wrong if:** 24 MiB is above 0.95, 16 MiB is above 0.40, or 8 MiB is above 1.00.

**P1, url-tld.**
- **24 MiB (1of2, 1of4) and 16 MiB (1of4):** 1.00 ± 0.03. The counters are identical.
- **1of2 at 16 MiB:** 0.97–1.05. The states are the same and nothing resets; the tables are bigger.
- **8 MiB:** 0.55–0.85 for both rows, from resets roughly halved.
- **Wrong if:** a 16 MiB cell is above 1.08, or an 8 MiB cell is above 0.95.

**P1, bounded-repeat context, RE2 and Rust, 32 MiB.** 0.90–1.30. This is the uncertain row. Each generation holds about 7% more states, so the first count after a skip window finishes on the DFA. That clears the bail streak, and reopening the window costs two more bailing counts: one more count per cycle on the DFA, for 4 more resets.
- **Wrong if** above 1.30. Then the resets cost more than the DFA counts save, and the count-bail window needs a look before merge.

**P2, compressed oops off.** Both jars carry the flag. I21 should match main, except at 8 MiB.
- **url/search:** 32 is 0.97–1.03, 24 is 0.97–1.05, 16 is 0.95–1.05, and 8 is 0.85–1.00.
- **url-tld-1of2:** 16 is 1.00–1.12, because I21 resets once more in 9 ops. 8 is 0.97–1.05.
- **url-tld-1of4 at 8 MiB:** 0.90–1.02.
- **The number Dain's Trino budget call needs** is each jar's oops-off leg/32 for url/search. Expect about 1.2–1.4 at 24 MiB (object rows), and well above that at 16 MiB (1.44 resets per op).
- **Wrong if:** url-tld-1of2 at 16 MiB is above 1.15, or any url/search cell is above 1.08.

**P3.**
- **RE2 url/search:** 16 MiB is 0.70–0.90, because the table is now held. 8 MiB is 0.75–0.95. Wrong if 16 MiB is above 0.95.
- **Java url/search, 8 MiB safe:** 0.70–0.90.
- **twophase-ab, 16 MiB safe:** 1.00 ± 0.03.
- **Rust 08-words/long-russian, 8 MiB native:** 1.00 ± 0.02. The reverse DFA resets about 2 more times per 100 ops.
- **uap-core rule 51, UA, safe, 16 and 8 MiB:** 0.95–1.05. There are no I21 counters for this row; the prediction comes from the mechanism alone. E1 already gives its table back early, and I21 changes only its growth steps. If it lands outside 0.85–1.10, I run counters before drawing any conclusion.
- **Controls (03-date rust, lh3lh3 email trino, sherlock coword trino, 32 MiB):** 1.00 ± 0.03, with identical counters.
