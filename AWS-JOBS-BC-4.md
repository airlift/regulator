# AWS timing jobs, round BC-4: what a 24 MiB default costs on the rows nobody has timed at 24

This round is for the coordinator to run on AWS Spot. I wrote it on dain-1 on 2026-11-19 PST. **I wrote every prediction below before anything was timed.** The only inputs were untimed counters:
- the 24-vs-32 census on the #283 head (`../default-24-census-2026-11-19/`);
- this round's smoke and steady-state probes (`SMOKE.tsv`, `STEADY.tsv`).

**The question:** Dain chose one 24 MiB default for every language. The census found five rows that move at 24 MiB and that no round has timed there. This round times each of them at 24 against 32, on one jar.

**Estimate and cap:** 16 legs, 768 forks (12 forks × 2 hosts × 2 platforms per leg), at BC-3's realized $1.120 / 3,840 = $0.000292 a fork. **About $0.22, against a cap of $0.25.** If the run trends over, cut in this order:
1. P3, english-10: Rust first, then RE2;
2. P2, bulk context: Rust, then RE2;
3. P1 last.

## Jar

- **One jar for every leg:** `user/dain/budget-curve-bc4-jar` `8d63e2ccdba2a9a02dddc42801e4d3321ee8abbc`. It is main `e4c8712f4` (#283 merged, the same tree as the census head `d1a47c936`) plus two timing-only commits:
  - `231fd834f`: the bulk `maxMemoryMiB` parameter, BC-2/BC-3's commit cherry-picked;
  - `8d63e2ccd`: `BenchmarkLanguageBulk.rebarCount`, which times the official Rebar runner's count route (`RebarRunner.countMatches`) for RE2.
- **Legs differ only** in `-p maxMemoryMiB` (24 or 32) and the leg's `jvm_extra`.
- **Build:** `./mvnw -Dmaven.gitcommitid.skip=true test-compile`, then `dependency:build-classpath -DincludeScope=test`. `TestLanguageBulkBenchmark` passes, 9 of 9.

**Why `rebarCount`:**
- The census ran the Rebar rows through the Rebar runner. That route counts with the DFA and, when the count bails, falls back to a plain find loop.
- The rebar-context thrash at 24 belongs to that route, and that is the route the published Rebar ranking row runs.
- Users who call the public `count()` see the bulk row instead (P2).
- Timed through `execute()`, the rebar-context legs only repeat the bulk legs (`SMOKE.tsv`).

## Conventions (as BC-3 unless stated)

- **Benchmarks:** `BenchmarkLanguageBulk.execute`, or `BenchmarkLanguageBulk.rebarCount` where LEGS.tsv says `bulk.rebarCount`. Use `-p engine=<language> -p workloadFile=<sha256>.klv -p expectedResult=<n> -p maxMemoryMiB=<leg>` and the class's JMH defaults, with `-f 1` per job. JMH setup verifies the result through `execute()` on every leg.
- **JVM:** `--add-modules=jdk.incubator.vector -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`, plus `--enable-native-access=ALL-UNNAMED` in native mode, plus the leg's `jvm_extra`. The oops-off legs carry `-XX:-UseCompressedOops` on both budgets.
- **Platforms and order:** r9g and r8i, 2 hosts each, 12 forks per leg per host. A cell's 24 and 32 legs run on the same host, interleaved fork by fork. Each fork has a 15-minute timeout.
- **Report:** per cell and platform, give:
  - the median 24/32 ratio across hosts, with each host's value;
  - the 32 leg's ns/op.

  Flag a host pair more than 5% apart.
- **Workloads:** `bc4-workloads.tar.gz` on the kit branch (9,982,716 bytes, sha256 `778572a67223a2aa317ef85f4e16491b34748c7e0d8d2f47bd9ae725af3c203f`). It holds the four `<sha256>.klv` files and a `SHA256SUMS`.
  - The two bulk files are the report's bulk-manifest inputs, matched by sha256.
  - Expected results:
    - rebar context: 53
    - noseyparker: 55
    - bulk context: 53
    - english-10: 690

## Counters behind the predictions (untimed, steady state)

Each count follows JMH-like setup, 5 warm-up ops and a 20-op window, and is given per op.

| Cell | Modes, layout | Forward resets/op 32 → 24 | States built/op 32 → 24 |
|---|---|---|---|
| rebar context RE2, `rebarCount` | safe and native, oops on | 2.25 → 3.40 | ~101k → ~113k |
| rebar context RE2, `rebarCount` | safe, oops off | 3.35 → 5.25 | ~113k → ~128k |
| noseyparker/single Rust | native, oops off | 0 → 1.50 | 0 → ~8.7k |
| bulk context RE2 and Rust | native, oops on | 0.30 → 0.10 | ~13.5k → ~3.6k |
| english-10 search RE2 and Rust | native, oops on | no DFA built | compile gates only |

Notes on these counters:
- **Rebar route:** it also builds a reverse DFA, which holds 3,034 states with 0 resets at both budgets.
- **"States built" is an estimate** made from resets, the states held at the last reset, and the states held at the end.
- **noseyparker** holds all 7,231 states, with 0 resets, from **30 MiB with oops off** and from **22 MiB with oops on**. Resets per op with oops off: 1.00 at 29, 1.10 at 28, 1.20 at 27, 1.30 at 26, 1.35 at 25, 1.50 at 24. Full table: `STEADY.tsv`.

## Predictions (24 / 32 on the same jar, median of hosts)

- **P1, rebar context RE2 (`rebarCount`).**
  - **Prediction:** oops on, safe and native, **1.05–1.15**; oops off, safe, **1.05–1.18**.
  - **Why:** the row thrashes at both budgets. BC-1 found this kind of row's cost linear in the states it rebuilds, and 24 rebuilds 12–13% more per op.
  - **Wrong if** above 1.25 or below 0.98.
- **P1, noseyparker/single Rust, native, oops off.**
  - **Prediction:** **2.0–2.8×**.
  - **Why:** at 32 it rebuilds nothing in steady state; at 24 it rebuilds about 8.7k states per op. BC-1's investigation measured this row at 1.41e-4 (r9g) and 1.83e-4 (r8i) of extra time per state rebuilt per op, which gives 2.2–2.6.
  - **Wrong if** below 1.5 or above 3.5.
  - With oops on, this row is unchanged at 24: 0 resets, not timed.
  - This row comes back to the coordinator after timing, under the decision rule.
- **P2, bulk context RE2 and Rust, native, oops on.**
  - **Prediction:** **1.01–1.03**.
  - **Why:** at 24 the row resets less (0.30 → 0.10 per op) but more of its counts bail. BC-3 timed the same counters the other way round (I21 at 32 against main at 32) at 0.976–0.986, so 24 should give that gain back.
  - **Wrong if** above 1.06 or below 0.95.
  - This is the row `count()` users see.
- **P3, english-10 search RE2 and Rust, native, oops on.**
  - **Prediction:** **1.00 ± 0.01**.
  - **Why:** only the compile-time gates move (reverse-program input, capture-slot limits), and no DFA is built. BC-1 timed Java at 24 at 0.997–0.999.
  - **Wrong if** outside 0.97–1.03.

## Decision rule (the coordinator's)

- If every cell lands within its prediction, or below 1.05, the 24 MiB default change is built, with these costs stated neutrally.
- A cell above 1.15 on both hosts of a platform goes back to the coordinator before the change is built.
- I expect noseyparker oops-off to trip that rule.
- For framing the decision, its alternatives are:
  - 24 MiB: about 2.0–2.8× on that row on large heaps;
  - 30 MiB: the smallest budget at which it holds every state with oops off;
  - 32 MiB: today.
