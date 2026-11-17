# Budget sweep: what a smaller default memory budget costs in matching speed (2026-11-08)

## Question

Regulator compiles every language with a 96 MiB memory budget (`Re2.Options.DEFAULT_MAX_MEMORY`). If the default
dropped to 32, 24 or 16 MiB, which published language-suite rows would get slower, by how much, on which platform,
and would any of them flip against their comparator? Predictions: `../../PREDICTIONS.md`, "Phase 3" (written before
any timing).

## Status

DONE. Launched 2026-11-08 10:47 PST, last host finished 13:42 PST; 3,072 of 3,072 jobs ok, 0 failed. Both r8i hosts
were reclaimed in us-east-2b after 6 minutes and replaced on Spot in us-east-2a (they resumed after their last pulled
job). Actual spend $0.78 of the $8 hold (cost.py: Spot price x hours + volume + IPv4). Teardown verified; a sweep of
all 17 regions found only the four terminated instances (`kit/sweep.txt`, `kit/teardown.log`).

## Commit and knob

- Jar: `ab2d01815` "Let the bulk benchmark set the memory budget" on `user/dain/memory-footprint` (local, not pushed).
- `BenchmarkLanguageBulk` gains `@Param("0") long maxMemoryMiB` (template: the class's own `expectedResult` param).
  0 leaves each language's options untouched (the published path, byte-identical); N > 0 calls
  `Options.setMaxMemory(N << 20)` for RE2, Java, Trino and Rust. JDK and Joni ignore it.
  `TestLanguageBulkBenchmark.testMaxMemoryBudgetsRegulatorLanguages` pins that 1 MiB rejects the compile in all four
  languages and not in the comparators.
- The 96 MiB leg runs with the parameter left at 0, so it is the published default path.

## Matrix (all four languages, native and safe mode; 136 cells, 384 (row, language, mode, budget) pairs)

Native mode = `--enable-native-access=ALL-UNNAMED`; safe mode omits it. Phase-1 evidence:
`../phase1-jvm-2026-11-08/` (SWEEP-jvm.tsv, CLASSIFY.md).

| Row | Budgets (MiB) | Why |
|---|---|---|
| curated/10-bounded-repeat/context | 96 64 48 32 24 16 | fullest DFA (88–99% of 96 MiB); resets at 64, resets then bails at 32 and below |
| wild/url/search | 96 32 24 16 8 | 10–11 MB settled; Trino resets from 24, every language from 16; thrash at 8 |
| dictionary/search/english-15 | 96 24 16 8 | RE2/Java/Rust compile a LiteralAlternation pattern at 16 and 8 (route change); 24 is the unchanged control |
| curated/12-dictionary/single | 96 24 16 8 | same route change as english-15; Trino unchanged |
| unicode/overlapping-words/russian | 96 48 32 | 55.6 MB settled (24.3 MB fixed program) |
| unicode/overlapping-words/english | 96 48 32 | 50 MB settled |
| dictionary/search/english-10 | 96 64 48 | 25 MB fixed program; RE2/Java/Rust reject at 32 |

Controls at 96 and 16 (each settles well under 16 MiB in phase 1, so any move is a side effect):

| Row | Why |
|---|---|
| imported/lh3lh3-reb/email | short-row contains (grep over ~1M lines) |
| curated/10-bounded-repeat/letters-en | count row; same family as the context target |
| captures/contiguous-letters | captures row |
| unicode/codepoints/letters-one | Unicode-class row |
| curated/01-literal/sherlock-en | literal row |
| curated/02-literal-alternate/sherlock-ru | literal alternation; Trino literal-set forms depend on the forward budget |
| curated/03-date/ascii | heaviest settling DFA outside the targets (1.6 MB) |
| wild/bibleref/line | grep-captures over short lines, 1.3 MB DFA |
| curated/05-lexer-veryl/single | count-captures, 0.76 MB DFA |
| unicode/overlapping-words/ascii | same family as the overlapping-words targets, fits every budget |

Dropped: nothing. Local check (setup + one `execute()` per pair from a plain main, no timing): 384 of 384 pairs
compile and return the manifest's expected result in both modes (`kit/check-results.tsv`). english-15 and
12-dictionary/single change class to `LiteralAlternation{Re2,JavaRegexp,RustRegexp}` at 16 and 8 (Trino unchanged);
no control changes class at 16.

Phase-1 discrepancy: SWEEP-jvm.tsv marks overlapping-words english/russian rejected at 32 MiB, but the benchmark
compiles both at 32 in all four languages and rejects them at 24 (matching CLASSIFY.md and the phase-1 README text),
so 32 stays in the matrix. english-10 rejects at 24 for RE2/Java/Rust; Trino accepts it even at 16
(LiteralSetTrinoRegexp).

## Method

- One bundle (`bundles/ab2d01815`: test-classes, classes, 21 dependency jars from a `git archive` export), budget as
  the JMH parameter. JDK Temurin 25.0.4+7. JVM arguments as the report's bulk rows:
  `--add-modules=jdk.incubator.vector -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch` (+ native access in native mode).
- JMH per job: `BenchmarkLanguageBulk.execute -f 1 -wi 5 -w 1s -i 5 -r 1s -bm avgt -tu ns -t 1`, the campaign A/B
  protocol (not the 5-fork steady-state-v1 publication protocol). Score = JMH average time (ns/op).
- 2 rounds. Each host runs every pair once per round; the budget order is rotated per round, host and cell, and odd
  rounds walk the cells in reverse, so every budget runs on every host in a different slot. 768 jobs per host.
- Hosts: 2× r9g.xlarge + 2× r8i.xlarge Spot, cheapest AZ from live Spot prices (`rates.sh`); launched in us-east-2
  (r9g us-east-2c 0.0535 $/h, r8i us-east-2b 0.0615 $/h). A reclaimed host is replaced on Spot in another AZ or region
  and resumes after its last pulled job (never On-Demand).
- Samples: one per (host, round), so 4 per platform per pair. ratio = mean budget time / mean 96 MiB time.
  Material = every budget sample lies outside the 96 MiB min–max range and |ratio − 1| > 3%.
- Flip (estimate across runs): published 2.0 time (`result.candidateNs`) × this sweep's ratio crossing the published
  comparator time (`result.comparatorNs`) in `benchmark-report/data/regulator-858993c3…json.gz`, per platform and mode.

## Commands (kit: `~/AgentScratch/regulator/user/dain/memory-footprint/sweep-kit/`, copied below under `kit/`)

```
cd ~/AgentScratch/regulator/user/dain/memory-footprint/sweep-kit
./rates.sh                      # live Spot prices -> placements.tsv, rates.tsv (read-only)
python3 -I plan.py              # jobs/<host>.tsv, shutdown-<host>.txt, est.txt (2 rounds, -wi 5 -i 5)
./go.sh                         # launch 4 Spot hosts, start mf-sweep-guard.sh (PID in mf-sweep-guard.pid), start runners
./status.sh                     # guard, per-host ok/failed, estimated spend
./pull.sh <ip> <host>           # manual collect (the guard pulls every 2 min)
python3 -I score.py > score.tsv
python3 -I score.py --results ~/AgentNotes/regulator/user/dain/memory-footprint/RESULTS.tsv --summary summary.md > /dev/null
./teardown.sh                   # the guard runs it when every host is done, at the $4 cap or at the deadline
./sweep.sh > sweep.txt          # read-only check of every enabled region
./archive.sh                    # raw output to the archive bucket (ARCHIVE-S3.md), after teardown
kill "$(cat mf-sweep-guard.pid)"   # only if the guard must stop early; never pkill
```

Guard: `mf-sweep-guard.sh`, $4 cap (`cost.py`, Spot price × hours + volume + IPv4), deadline = latest on-host
shutdown + 45 min, on-host `shutdown -h +340/+335` (instances terminate on shutdown), auto-replacement of lost hosts up
to 3 times while under 90% of the cap.

## Cost and time

Actual: 2.95 h wall, $0.78 (6 instances, 11.6 instance-hours). Estimate was: 4.6 h wall, ~$1.2 at the launch AZ prices (worst case $2.95 at the dearest AZ prices). Hold: $8
(SHARED-POOL-LEDGER.md).

## Results

Full per-(row, language, mode, budget, platform) lines: `score.tsv`; per-budget tables of every material row with
flips: `summary.md`; RESULTS.tsv rows `budget-<N>MiB` (496 rows, op = `<model>/<mode>`). Raw output in S3:
`archive/memory-footprint-sweep-20261108.tar.zst` in the regulator archive bucket (ARCHIVE-S3.md), sha256
`6f1cc5109b860514442beeddc5977340949b7497f1d05773ce1e82ea35ced5cf`, verified.

Controls: 0 of 160 control lines (10 rows × 4 languages × 2 modes × 2 platforms, 16 MiB vs 96 MiB) are material;
every ratio lies in 0.954–1.048. Prediction S1 holds.

### Headline (ratio = budget time / 96 MiB time; range over 4 languages × 2 modes; material and flip counts of 8)

Flips are estimates across runs: published 2.0 time × this ratio against the published comparator time.

| Budget | Row | r9g ratio | r8i ratio | Material | Flips vs comparator |
|---|---|---|---|---|---|
| 64 | curated/10-bounded-repeat/context | 1.25–14.83 | 1.22–14.29 | 8/8 | 3/8 (Java safe, Java native, Trino safe) |
| 64 | dictionary/search/english-10 | 0.95–1.04 | 0.99–1.03 | 0/8 | 0 |
| 48 | unicode/overlapping-words/russian | 159–296 | 158–888 | 8/8 | 8/8 |
| 48 | unicode/overlapping-words/english | 131–338 | 133–1259 | 8/8 | 7/8 (all but RE2 native) |
| 48 | curated/10-bounded-repeat/context | 14.8–17.9 | 14.2–20.5 | 8/8 | 8/8 |
| 48 | dictionary/search/english-10 | 0.97–1.04 | 1.00–1.02 | 0/8 | 0 |
| 32 | unicode/overlapping-words/russian | 172–322 | 173–971 | 8/8 | 8/8 |
| 32 | unicode/overlapping-words/english | 194–474 | 192–1680 | 8/8 | 8/8 |
| 32 | curated/10-bounded-repeat/context | 14.8–17.6 | 14.2–20.5 | 8/8 | 8/8 |
| 32 | wild/url/search | 0.99–1.01 | 0.99–1.00 | 0/8 | 0 |
| 24 | curated/10-bounded-repeat/context | 14.9–17.8 | 14.1–20.4 | 8/8 | 8/8 |
| 24 | wild/url/search | 1.00–1.05 | 0.99–1.06 | 1/8 (Trino safe 1.05/1.06) | 0 |
| 24 | dictionary/search/english-15, curated/12-dictionary/single | 0.97–1.02 | 0.99–1.02 | 0/8 each | 0 |
| 16 | curated/10-bounded-repeat/context | 14.8–17.7 | 14.0–20.4 | 8/8 | 8/8 |
| 16 | wild/url/search | 1.05–7.02 | 1.06–7.17 | 8/8 | 2/8 (RE2 safe, Rust safe) |
| 16 | dictionary/search/english-15 | 1.00–1.52 | 0.99–1.24 | 6/8 (RE2/Java/Rust) | 0 |
| 16 | curated/12-dictionary/single | 0.98–1.02 | 1.00–1.02 | 0/8 | 0 |
| 8 | wild/url/search | 1.12–20.5 | 1.14–20.7 | 8/8 | 4/8 (RE2 and Rust, both modes) |
| 8 | curated/12-dictionary/single | 0.97–2.21 | 1.00–3.86 | 7/8 (Trino 0.97 on r9g) | 0 |
| 8 | dictionary/search/english-15 | 0.98–1.53 | 1.00–1.26 | 6/8 (RE2/Java/Rust) | 0 |

### What it means

- **48 MiB already breaks overlapping-words** (prediction S5 was wrong). At 96 MiB the settled DFA makes an op
  ~0.4 ms; at 48 and 32 MiB the DFA no longer fits after the 24 MB fixed program and every op rebuilds states:
  48–80 ms (RE2/Java/Rust), 145–200 ms Trino on r9g and worse on r8i (up to 1,680× there). Every language flips to
  slower than its comparator (RE2 by 1.2–2.0×, the crate by 5.6–14×, JDK and Joni by 7–85×).
- **10-bounded-repeat/context bails at every budget below 64 MiB**: 14–20× slower in all languages and modes, and
  every one flips (est. 1.4–4.4× the comparator's time). At 64 MiB, RE2/Rust are 1.2–1.7× slower (resets) while
  Java/Trino bail in safe mode (14–15×) and are 6.6–7.5× slower native; Java safe/native and Trino safe flip.
- **wild/url/search holds to 24 MiB** (only Trino safe +5–6%); at 16 MiB safe mode is 6–7× slower (RE2 and Rust flip)
  and native 1.3–1.4×; Trino 1.05–1.11×. At 8 MiB safe is ~19–21× and native 11–14×.
- **Dictionary rows**: english-15 at 16 and 8 MiB (LiteralAlternation route) is 1.5× slower on r9g and 1.2× on r8i, no
  flips; 12-dictionary/single holds at 16 but is 2–3.9× slower at 8. english-10 at 64/48 MiB is flat.
- Read against the brief's three candidates: **32, 24 and 16 MiB each cost the context row 14–20× with comparator
  flips in every language**; 32 MiB also breaks overlapping-words; 16 MiB adds the url/search safe-mode collapse and
  the english-15 route cost. No budget below 64 MiB is free on these rows; settling rows (controls) do not move.

### Predictions check (PREDICTIONS.md, Phase 3)

- S1 settling controls flat: held (0/160 material, ±5%).
- S2 context 3–30× at 32/24/16 with flips: held (14–20×, flips in every language); at 64 native 1.2–2×: held for
  RE2/Rust (1.2–1.7×), missed for Java/Trino (6.6–7.5× native, 14–15× safe).
- S3 url/search at 16: safe 1.1–1.5× missed (6–7×); native within 3% missed (1.3–1.4×); Trino at 24 1.05–1.3× held
  for safe (1.05–1.06), native flat; 8 MiB 1.5–3× missed (11–21×).
- S4 dictionary route change within ±20%: english-15 1.2–1.5× slower on both platforms (slightly outside on r9g).
- S5 overlapping-words at 32 and english-10 at 64/48 flat: english-10 held; overlapping-words missed badly (130–1,680×,
  already at 48).
