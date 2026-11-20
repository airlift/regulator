# AWS timing jobs, simplification sweep phase 2, round 8 (stale prices re-timed on current main)

Written 2026-11-19/20 PST on dain-1. **The predictions below were written before anything runs.** Nothing is timed on dain-1.

Rounds 1–7 priced the sweep against main `eef7c0659`. Main has since moved to **`79183ff46`**: the engine-gaps PRs #263–#282, dense match starts (#278), many-match keys (#279), DFA table funding (#280/#283), the 24 MiB default budget (#284), and "Keep set searches going through slow resets". A staleness map and an untimed re-census on `85f1e5a4e` name the prices that may have moved (STALENESS.md, CENSUS-85F1.md). This round re-times only those. Every leg is one commit on `79183ff46`, or on `m-cliffs-base`, which is `79183ff46` plus the test-only `BenchmarkSweepCliffs`.

## Method (as rounds 1–7)

- **Same-host A/B**, 3 forks per leg, interleaved ABAB fork by fork.
- **JMH:** `-f 1 -wi 5 -w 1s -i 5 -r 1s`, with `-wi 8` on `baseline/*` rows. The cliff cells use round 5's settings: `-f 1 -wi 3 -w 1s -i 5 -r 1s -tu ms`.
- **Mode:** native, unless a job says native + safe.
- **Platforms:** r9g and r8i, r9g first, unless a job says r9g only.
- Row ids are published 2.1 report row ids. The comparator ratios for the flip check are the published 2.1 ones, as before.
- **Fork cap:** 15 minutes. Run the 16 MiB cliff cells last on each host.
- **Report** as before: the 3 fork scores and the median; B/A with the fork spread; the calibration fields. For the cliff cells, also each fork's `result` and the fork-1 diagnostic facts.

## Legs (full SHAs; pushed)

| Leg | Full SHA | Branch | What it changes |
|---|---|---|---|
| main | `79183ff46ea6eedb640aae2b67b0b6ecb924d520` | `main` | |
| −S8 | `be3b873e194e8c200cc2d4479c5901ea6e27b6df` | `user/dain/sweep/m-no-s8` | no fixed-distance byte candidates |
| −S4 | `ddec90c096b79e189f26726ab6aeedf764d3d076` | `user/dain/sweep/m-no-s4` | no folded run behind a lead, and no folded start run |
| −L9 | `c1badd7c49bd48a99f90f257d2499a1163579fa0` | `user/dain/sweep/m-no-l9` | no lowered boolean program (final-line `$`) |
| −E3F | `c15f7a6d29a70228a202ed7453f0af07d9d283ec` | `user/dain/sweep/m-no-e3f` | no flat paired table on aarch64 (the object paired form, as on x86) |
| plans-off | `f9df83cdf04c863cca4ef4c794d0375500ea7a21` | `user/dain/sweep/m-plans-off` | no pattern builds a scan plan |
| limit-1 | `f30aac5cc46ab898cd13f5917155b1de0e315f2d` | `user/dain/sweep/m-pointer-limit-1` | the DFA pointer table after 1 short search instead of 16 |
| −E6 | `087c45256225cb844d7c93c1f9417692c41bcbd6` | `user/dain/sweep/m-no-e6` | no candidate-start cursor (on `m-cliffs-base`) |
| −L7 | `9447d8b9ddddea7fd36d99e635561287094a8570` | `user/dain/sweep/m-l7-engine` | no ordered-literal matcher; its callers take the route they would without it |
| cliffs base | `efca732874f870efd62791ffd75732c00cec7556` | `user/dain/sweep/m-cliffs-base` | main + `BenchmarkSweepCliffs` (test files only) |
| −E5tag | `38deeb15fcbe358595fa95d28c3196bd0e36370d` | `user/dain/sweep/m-no-e5-tagged` | no many-match slow-reset skip (on `m-cliffs-base`) |
| −E12 | `d106cb2ba014d3c29c122a19bbe9ac512b521e2b` | `user/dain/sweep/m-no-e12` | no tagged alternation program (on `m-cliffs-base`) |

Notes on the legs:
- **plans-off** turns plans off unless `-Dregulator.sweep.scanPlansEnabled=true` is set; do not set it.
- **m-cliffs-base** has no src/main difference from main, so main is the base for every report-row job. The cliffs base serves only the cliff cells.
- **Every leg passed its targeted tests and the answer gates** (Trino/Java differentials, exhaustive agreement, Rust and upstream suites), in safe and native mode. Any disabled test pins a route or plan.

## Job R8-S8: legs main, −S8

- `language-bulk/curated/10-bounded-repeat/context/execute`: java, re2, rust, trino
- `language-bulk/imported/leipzig/awyer-inn/execute`: java, re2, rust, trino
- `language-bulk/imported/leipzig/shing/execute`: java, re2, rust, trino
- `language-bulk/imported/sherlock/name-alt3-casei/execute`: trino

That is 13 row-languages.

## Job R8-S4: legs main, −S4

- `language-bulk/curated/01-literal/sherlock-casei-en/execute`: trino, java, re2, rust
- `language-bulk/imported/sherlock/name-sherlock-casei/execute`: trino, java, re2, rust
- `language-bulk/imported/sherlock/name-sherlock-holmes-casei/execute`: trino, java, re2, rust

That is 12 row-languages. RE2, Java and Rust are controls.

## Job R8-L9: legs main, −L9

- `language-bulk/wild/dot-star-capture/rust-src-tools/execute`: trino (diagnostic)
- `baseline/trino-specialized-patterns/clickbenchHost/replace/regular-engine`: trino (synthetic)
- `baseline/trino-specialized-patterns/urlHostPort/extract/regular-engine`: trino (synthetic)

That is 3 row-languages.

## Job R8-E3F: legs main, −E3F. **Native + safe, r9g only**

- `language-bulk/imported/leipzig/{ing-whitespace,ing,word-ending-nn}/execute`: java, re2, rust, trino
- `language-bulk/imported/mariomka/{email,uri}/execute`: java, re2, rust, trino
- `language-bulk/imported/sherlock/{before-holmes,ing-suffix-limited-space,ing-suffix,word-ending-n}/execute`: java, re2, rust, trino
- `language-bulk/imported/sherlock/everything-greedy-nl/execute`: java, re2, trino

That is 39 row-languages per mode.

## Job R8-A5: legs main, plans-off

The `*-specialized-patterns` rows are left out, because they refuse to set up without a plan.
- `language-bulk/imported/lh3lh3-reb/uri/execute`: java, trino
- `language-bulk/wild/caddy/caddy/execute`: java, trino
- `language-bulk/wild/ruff/shebang/execute`: re2, rust
- `baseline/trino-operations/everyday/keyValue/{count,extract}`: trino
- `baseline/trino-operations/everyday/quotedField/{count,extract}`: trino
- `language-lifecycle/everyday/keyValue/reusedFind`: java, re2, rust, trino
- `language-lifecycle/everyday/quotedField/reusedFind`: java, re2, rust, trino
- Control: `language-bulk/imported/sherlock/holmes-cochar-watson/execute`: java, re2, rust, trino

That is 22 row-languages.

## Job R8-A4: legs main, limit-1. **Native + safe**

These are the 53 row-languages of R3-A4:
- `baseline/{java,re2,rust}-specialized-patterns/{keyValue/extractAll,queryParameterNames/extractAll,quotedField/extract}/regular-engine`: that language (synthetic)
- `baseline/trino-operations/everyday/{email,ipv4}/{contains,count,extract,extractAll,positionThird,replace,replaceLambda,split}`: trino
- `baseline/trino-specialized-patterns/{clickbenchHost/replace,keyValue/extractAll,queryParameterNames/extractAll,quotedField/extract,urlHostPort/extract}/regular-engine`: trino (synthetic)
- `language-bulk/imported/sherlock/everything-greedy-nl/execute`: java, re2, trino
- `language-bulk/wild/bibleref/short/execute`: java, re2, rust, trino
- `language-lifecycle/everyday/email/reusedFind`: java, re2, rust, trino
- `language-lifecycle/everyday/{isoDate,trinoPhone,uuid,whitespace}/reusedFind`: java, re2, rust

## Job R8-E6: legs main, −E6

These are round 7's rows that moved, plus the Trino casei rows that the re-census moved:
- `language-bulk/imported/sherlock/line-boundary-sherlock-holmes/execute`: java, re2, rust, trino
- `language-bulk/curated/06-cloud-flare-redos/original/execute`: java, re2, rust, trino (constructed)
- `language-bulk/imported/rsc/no-exponential/execute`: java, re2, rust, trino (constructed)
- `language-bulk/imported/sherlock/{name-alt3-casei,name-alt4,name-alt4-casei,name-alt5-casei,quotes,holmes-cochar-watson}/execute`: java, re2, rust, trino
- `language-bulk/imported/sherlock/{name-alt2,name-alt3,name-alt5}/execute`: java, re2, rust
- `baseline/trino-operations/everyday/ipv4/{extract,extractAll,positionThird,replace,replaceLambda,split}`: trino
- `language-lifecycle/everyday/ipv4/reusedFind`: trino

That is 52 row-languages.

## Job R8-L7: legs main, −L7

- `baseline/trino-like/ORDERED_DENSE_FALSE`, `baseline/trino-like/ORDERED_LATE`: like
- `baseline/like-single-use/ORDERED_DENSE_FALSE`, `baseline/like-single-use/ORDERED_LATE`: like (single use, informational)
- `baseline/trino-like-specialized/Q13_ORDER_COMMENT`: like (synthetic)
- `baseline/like-compile/ORDERED_DENSE_FALSE`, `baseline/like-compile/ORDERED_LATE`: like (compile, informational)

That is 7 rows.

## Job R8-E12: legs main, −E12 (report rows)

- `language-bulk/curated/05-lexer-veryl/single/execute`: java, re2, rust, trino
- `language-bulk/unicode/overlapping-words/ascii/execute`: java, re2, rust, trino

That is 8 row-languages. For these rows `m-cliffs-base` is identical to main in src/main, so main is the base.

## Job R8-CLIFFS: E5_TAGGED cliff cells. Legs cliffs base, −E5tag, −E12

`BenchmarkSweepCliffs.run` with `-p workload=E5_TAGGED`, `inputBytes` ∈ {65536, 1048576, 16777216} and `budget` ∈ {25165824, 1048576}. The 24 MiB value is main's new default; round 5 used 32 MiB. That is 6 cells, and each runs three legs: base, −E5tag and −E12.
- Command as in round 5: `org.openjdk.jmh.Main 'io.airlift.regulator.BenchmarkSweepCliffs.run' -p workload=E5_TAGGED -p inputBytes=<N> -p budget=<B> -f 1 -wi 3 -w 1s -i 5 -r 1s -tu ms`, with the same JVM flags.
- `result` must be equal across the three legs on every fork. Report each leg's fork-1 diagnostic line.

## Predictions (written before the round runs)

B/A = leg / main; above 1 means slower without the component.

| Job | Expected | Why | Confidence |
|---|---|---|---|
| R8-S8 | awyer-inn ×4 **0.76–0.84** (faster), as before. Trino name-alt3-casei **±3% on both platforms**. context and shing ±3% | The re-census has S8 off Trino name-alt3-casei, which now runs on the Trino folded-alternation route (A1/R4, `80f8ee208`). That row was −S8's one stated cost | medium |
| R8-S4 | Trino name-sherlock-casei **1.3–1.8** r9g, 1.0–1.2 r8i; name-sherlock-holmes-casei 1.2–1.4 r9g, 0.8–1.0 r8i. Trino sherlock-casei-en **±5%** (it left S4 for A1/R4). Controls ±3% | single folded literals keep S4; the alternation moved | medium |
| R8-L9 | rust-src-tools **0.2–0.5** (faster), the two specialized rows 0.8–0.95 (faster) | as round 2, unless the OnePass full-match line rule (`6d8de8ec6`) took the rows. Then ±5% | low |
| R8-E3F (r9g) | word-ending-n ×4 **1.04–1.12**, others ≤1.06, safe as native | `069ffc1be` rewrote the paired loops; the trade should be the same | medium |
| R8-A5 | caddy **5–10x**, shebang **~2x**, keyValue/quotedField 1.2–2.1x, as before. **lh3lh3 uri 0.8–1.2**, not round 4's 0.33: #270 now rejects short rows before run-led plans. Control ±5% | the plan's pessimization on uri is fixed on main | medium |
| R8-A4 | every row **±3%**, native and safe | it was flat in round 3; the funding rewrite changes table growth, not the first-search counter | medium |
| R8-E6 | as round 7 within about ±20%: line-boundary **15–60x**, name-alt* 1.6–6.6x, cloud-flare 8–10x, rsc 0.5–0.6, r8i ipv4 0.75–0.9. Trino name-alt3/5-casei **±5%** (they left both cursors). Trino line-boundary may shrink, because `286bcfbb3` lets the group-zero cursor serve Trino line anchors | the cursor's job is unchanged | medium |
| R8-L7 | LIKE ORDERED_* matches **1.5–5x** slower; single use 1.2–3x; Q13 1.2–3x; compile ±10% or faster | the engine's LIKE route has no ordered-literal skip | low on size |
| R8-E12 | veryl ×4 **±5%** (it now stays on the DFA; E12 runs on no report row); overlapping-words ±5% (E12 is only admitted there) | `069ffc1be` moved Java/Trino `\b` onto the DFA | medium |
| R8-CLIFFS | **−E5tag:** 24 MiB budget ±5% at every size (the skip never fires there); 1 MiB budget **1.2–2.5x** (round 5: 2.1–2.4x; #279's keys may cut the thrash). **−E12:** at both budgets **1.5–3x** slower: captures without the tagged program fall to BitState or the Pike NFA (record: 2.2–2.6x vs BitState) | E12 is what the E5 tagged skip guards | low |

Wrong if:
- any `result` differs between legs (an answer change);
- −S8 moves Trino name-alt3-casei by more than ±5%;
- −E12 moves veryl by more than ±5%;
- any control moves by more than ±5%.

## Size and cost

**Forks per platform** (rows × 2 legs × 3 forks):

| Job | r9g | r8i |
|---|---:|---:|
| S8 | 78 | 78 |
| S4 | 72 | 72 |
| L9 | 18 | 18 |
| A5 | 132 | 132 |
| E6 | 312 | 312 |
| L7 | 42 | 42 |
| E12 | 48 | 48 |
| A4 (×2 modes) | 636 | 636 |
| E3F (×2 modes, r9g only) | 468 | — |
| cliffs (6 cells × 3 legs × 3) | 54 | 54 |
| **Total** | **~1,860** | **~1,390** |

- **Time:** at round 7's ~0.18 min per fork, plus about 1–2 host-hours for the 16 MiB cliff forks: about **12 host-hours**.
- **Cost:** **$0.40–0.60**, with a ceiling of about **$1.0**.
- **Cumulative:** about **$3.1–3.3** of the $25 cap.

## Budget and cut order

- **Approved by the coordinator (2026-11-20):** estimate $0.40–0.60, **guard cap $1.00**, inside the $30 hold.
- **Cut order:** if projected spend passes $0.85, or Spot capacity runs short, drop work in this order, first item first. Each cut takes the least decision-relevant work left.
  1. R8-A4 limit-1, **safe** mode on both platforms. This is a tuning change; native mode carries the verdict.
  2. R8-E6 on **r8i**. It is a keep on both platforms. r9g re-checks the cost after 286bcfbb3.
  3. R8-E3F **safe** mode (r9g). Native carries the verdict, and round 3's safe ratios tracked native within 0.01–0.02.
  4. R8-A4 limit-1, **native** on r8i.
  5. R8-A5 plans-off on **r8i**.
- **Never cut:** S8, S4, L9, E12, L7 and the cliffs (both platforms), nor E3F native and E6, A5 and A4 native on r9g. These decide buckets.
- **Report** any cut, with the spend at the moment of the cut.
