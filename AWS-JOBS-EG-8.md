# AWS timing jobs, round EG-8: W4 (#275), W6 (#276), #273 with its reader remedy

For the coordinator to run on AWS Spot. Written 2026-11-17 PST on dain-1. **I wrote the predictions before anything ran.** All legs are built on main `11b9291e1`.

**Estimate.** EG-7 cost $2.753 for about 14,000 forks, about $0.0002 per fork. This round is about 10,200 forks (J1 45 rows, 4,320; J2 10 rows, 960; J3 51 rows, 4,896; 96 forks a row), so roughly **$2.0–2.8**, with guard cap **$3.60**. J1's large haystacks (grep 7.4 MB, unicode/word 613 KB) run longer setup than the average fork. If the guard nears the cap, drop rows in this order:
1. J1's no-assertion controls beyond 03-date and quotes.
2. J1's English rebar twins beyond boundary-any-english.
3. J3's `long-ascii` cells.

Conventions are the same as EG-7:
- **12 forks per leg per host.** Use **2 hosts per platform**, on **r9g** and **r8i**.
- Legs interleave fork by fork (ABAB), and both legs of a row run on the same host.
- **Native** mode only (`--enable-native-access=ALL-UNNAMED`).
- Use the class's JMH defaults, and put the legs' jars at paths of equal length.
- For each row and platform, report the median B/A across both hosts, with the per-host values. Flag any host pair that disagrees by more than 5%.

Priority order: J1 (W4) > J3 (#273) > J2 (W6).

## Workload files

`BenchmarkLanguageBulk` rows take a workload file outside the repo: `-p engine=<engine> -p workloadFile=<path> -p expectedResult=<n>`. Every file was checked with a single-shot run (no timing) on main and on the branch it times, and the span count matched on both. The W4 checks ran on #275 before its rebase onto `11b9291e1`; the rebase merged cleanly and changed only comments.

- W4: `/home/ubuntu/AgentScratch/regulator/user/dain/rust-unicode-word-dfa/eg8-workloads/` (top level: `SHA256SUMS`, `manifest.json`; `rows/`: `SHA256SUMS.rows`).
- W6: `/home/ubuntu/AgentScratch/regulator/user/dain/russian-literals/eg-workloads/` (`SHA256SUMS`, `manifest.json`).

One row's command line, as a template:
```
java -cp <jar> org.openjdk.jmh.Main \
  -jvmArgs "--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch" \
  '^io.airlift.regulator.BenchmarkLanguageBulk\.execute$' \
  -p engine=rust -p workloadFile=<dir>/curated--08-words--all-russian.klv -p expectedResult=107391 -f 12
```

## J1 — W4, PR #275: Unicode word assertions on the DFA

The DFA evaluated Unicode word assertions only between ASCII bytes and quit beside any non-ASCII byte. In `\b\w+\b` every state needs the assertion, so on Russian text every find quit, the quit streak skipped the DFA, and the rest of the input ran on the Pike NFA. Now the DFA decides those boundaries itself:
- a lead byte whose characters share one class decides from its byte class;
- other neighbours are decoded from the text and cached against the DFA budget;
- it quits only on malformed text.

Counters (no timing): all-russian and long-russian make 0 quits and 0 Pike runs. On ASCII text the states equal main's, and forward byte classes grow by 4–5 for decoding programs (Java `\b\w+n\b` 4 → 8, Trino 4 → 9, Rust i787 33 → 38).

- **Legs:**
  - **A**: main `11b9291e1`.
  - **B**: #275 `03abd97a0`: the reviewed fix round, rebased onto `11b9291e1`, with comment-only polish.
- **Rows**, all `BenchmarkLanguageBulk.execute`. "Top" files are at the W4 directory's top level, and `rows/` files are under `rows/`.

  1. **Targets**, engine `rust`, top: `curated--08-words--all-russian` (107391), `curated--08-words--long-russian` (5481), `unicode--word--boundary-any-russian` (529194), `unicode--word--boundary-long-russian` (21332), `unicode--word--around-holmes-russian` (44).
  2. **Diagnostic**, engine `rust`, top: `diagnostic--greek-words--all-greek` (236000). Mixed leads CE/CF, so it reads them from the text.
  3. **ASCII twins: the gate.** A decoding word assertion over English or ASCII text:
     - `rows/imported--sherlock--word-ending-n-asciionly--{java,trino}` (35297 each). Java's default `\b` and Trino's `\b` decode the text.
     - `rows/imported--sherlock--words--{java,trino}` (447639 each).
     - `rows/unicode--word--boundary-any-english--java` (450561).
     - top `diagnostic--rust-unicode-ascii--word-ending-n` (35297) and `rows/i787-opt-unicode-asciionly--rust` (5674), engine `rust`. These are pure-ASCII Rust Unicode `\b`.
  4. **Untouched programs**, with no decoding assertion. Rebar runs these Rust rows with `unicode=false`:
     - every `re2` file of groups 3–5: `rows/imported--sherlock--{word-ending-n-asciionly,words,word-ending-n}--re2`, `rows/unicode--word--boundary-any-english--re2`;
     - engine `rust`: `rows/imported--sherlock--{word-ending-n-asciionly,words,word-ending-n}--rust`, `rows/unicode--word--boundary-any-english--rust`, top `unicode--word--boundary-long-english` (3466), `unicode--word--around-holmes-english` (27), `curated--08-words--all-english` (56691) and `curated--08-words--long-english` (839).
  5. **Few-quit movers.** Mostly ASCII text where main quits beside a few non-ASCII bytes:
     - `rows/imported--sherlock--word-ending-n--{java,trino}` (35297 each). Main quits 13 times.
     - top `reported--i787-keywords--unicode` and `reported--i787-keywords--opt-unicode` (5674 each), engine `rust`. Main quits 46 times and runs the NFA over 2.47 MB of windows per round.
     - top `grep--long-words-unicode` (5075), engine `rust`, 7.4 MB.
  6. **No-assertion controls**, for the context-argument stopping rule. In `rows/`, one file per engine for java, trino, rust and re2:
     - `curated--03-date--ascii--*` (111817);
     - `imported--sherlock--quotes--*` (14437);
     - `imported--sherlock--ing-suffix--*` (20547);
     - `imported--sherlock--before-holmes--*` (4073).
- **Predictions** (B/A; above 1 is slower):

  | Family | Rows | r9g and r8i | Wrong if |
  |---|---|---|---|
  | Cyrillic targets | all-russian, boundary-any-russian | **0.05–0.15** | above 0.3 |
  | Cyrillic targets | long-russian, boundary-long-russian | **0.02–0.08** (a DFA scan replaces a whole-input Pike run) | above 0.3 |
  | Cyrillic targets | around-holmes-russian | **0.05–0.4** | above 0.7 |
  | Mixed leads | all-greek | **0.1–0.5** (decoded transitions cost more than cached ones) | above 0.7, or above 1 |
  | ASCII twins, decoding assertion | Java/Trino word-ending-n-asciionly, sherlock words; Java boundary-any-english; Rust unicode-ascii word-ending-n, i787-asciionly | **0.95–1.03**: same states on ASCII, 4–5 more forward classes; English rows with a few non-ASCII bytes save their quits | above 1.03 on both hosts |
  | Untouched programs | every re2 row; Rust `unicode=false` rows | **1.00 ±2%** | above 1.02 on both hosts |
  | Few-quit movers | sherlock word-ending-n java/trino | **0.85–1.00** (13 NFA windows saved) | above 1.03 |
  | Few-quit movers | i787 unicode, opt-unicode | **0.2–0.6** (46 NFA runs over 2.47 MB of windows saved) | above 0.9 |
  | Few-quit movers | grep long-words-unicode | 0.90–1.03 (informational) | |
  | No-assertion controls | 03-date, quotes, ing-suffix, before-holmes | **1.00 ±2%** | above 1.02 on both hosts |

  Against the crate, I expect the Russian rows to flip from about 0.5x to 3–10x ahead. Against resharp, I expect all-russian at 0.5–1.5 of its time and long-russian about level. Those ratios come from the Mac's same-host rebar run, not from EG-8.
- **Stopping rules:**
  - **ASCII twins are the gate.** If a twin with a decoding assertion is above 1.03 on both hosts of a platform, #275 stays draft. Each such row needs a cause in the design note: the wider forward row (classes) or the context arguments.
  - **Context-argument rule.** If a no-assertion control is above 1.02 on both hosts of a platform, #275 stays draft and I restructure. Every forward and reverse loop now passes `bytes, contextBegin, contextEnd, position` to the slow-path transition call, so register pressure is a possible cause on programs with no decoding assertion.
  - A Cyrillic target above 0.3 on either platform means the mechanism isn't working on that row; I investigate before ready.
- **I am wrong if** all-greek's ratio is below all-russian's. Cyrillic decides from cached lead transitions, and Greek reads most of its leads from the text, so Greek should gain less.

## J2 — W6, PR #276: Russian literal pair scan on Graviton

On Russian text the fused pair scan rarely sees a failed candidate. C2 therefore left the 11-byte `comparisonBudgetSpent` call out of line, and that split the broadcast vectors out of registers: 11 against 8 instructions per 16-byte step on aarch64. The comparison is now written in line, and `TestHotMethodSize` pins it.

- **Legs:**
  - **A**: main `11b9291e1`.
  - **B**: #276 `a12d6c399`.
- **Rows**, `BenchmarkLanguageBulk.execute`, engines `rust` and `re2`, files in the W6 directory:
  - `curated--01-literal--sherlock-ru` (724), the target;
  - `curated--01-literal--sherlock-en` (513) and `curated--01-literal--sherlock-zh` (30), controls already in line;
  - `curated--02-literal-alternate--sherlock-ru` (899) and `curated--02-literal-alternate--sherlock-casei-ru` (971), controls on other scans.
- **Predictions:**

  | Rows | r9g | r8i | Wrong if |
  |---|---|---|---|
  | 01-literal sherlock-ru | **0.75–0.85** | 0.95–1.00 | r9g above 0.92, or r8i above 1.02 on both hosts |
  | 01-literal en, zh | 1.00 ±2% | 1.00 ±2% | |
  | 02-literal-alternate ru, casei-ru | 1.00 ±2% | 1.00 ±2% | |

  Against the crate, I expect r9g sherlock-ru to go from 1.19x to 0.90–1.00x.
- **Stopping rule:** any row above 1.02 on both hosts of a platform keeps #276 in draft.

## J3 — #273: Trino plans read characters like the engine, with the reader remedy

EG-7 J3 timed #273 without the remedy. The everyday quotedField witness came in at 1.15–1.18, and the dot-all and line tails at up to 278x. The remedy (#273's last commit) does three things:
- it checks whole 16-byte blocks of non-ASCII text by the engine's lenient UTF-8 rule, out of line, in place of restarting the stop scan at every non-ASCII stretch;
- it keeps the ASCII stop scan inline;
- it holds a run's single stop byte in a field, which trims each call's loads.

Counters (no timing):
- a 4 KiB mixed-script tail goes from about 296 scan restarts and 441 character reads per row to about 2 of each;
- quotedField's restarts go from 0.88 to 0.75 per row, and its hot path broadcasts 2 vectors where main broadcasts 4.

- **Legs:**
  - **A** `2ba9dbbdb`, branch `user/dain/bench-trino-plan-costs`: main `11b9291e1` plus the timing-only `BenchmarkTrinoPlanCosts`.
  - **B** `eab662aca`, branch `user/dain/bench-trino-plan-costs-b`: #273 `010467cd9` plus the same class. Its src/main patch-id against A equals #273's against main (`3099ab7491a5`).
  - On both legs, the class's setup checks pass for all 16 pattern × input cells: the plan runs, and it answers each operation as the plain engine does.
- **Rows:** as EG-7 J3.
  1. **`BenchmarkTrinoPlanCosts`**, 36 cells: ops `contains` and `extract` for every `pattern` × `input`, plus `count` for `negated-run`.
     - patterns `delimited`, `line-tail`, `dotall-tail`, `negated-run`;
     - inputs `short-ascii`, `short-mixed`, `long-ascii`, `long-mixed`.
  2. **Witnesses:**
     - `BenchmarkEverydayTrinoRegexp` `contains`, `count`, `extract` and `extractAll` on `keyValue` and `quotedField`;
     - `BenchmarkTrinoRows.count` on english/ordered-selective and chinese/ordered-none;
     - `BenchmarkEverydayTrinoRegexp.count` on `email`.
  3. **Controls:**
     - `BenchmarkTrinoRows.contains` english/low-selective, english/dotstar-low-selective and nginx/mid-common;
     - `BenchmarkEverydayTrinoRegexp.contains` on `logLevel`.
- **Predictions** (B/A; r8i similar or a little worse than r9g):

  | Family | Rows | EG-7 without remedy (r9g / r8i) | EG-8 prediction | Wrong if |
  |---|---|---|---|---|
  | quotedField | contains, count | 1.18 / 1.15, 1.18 / 1.18 | **1.00–1.06** | above 1.10 |
  | quotedField | extract, extractAll | 1.03 / 1.09, 1.03 / 1.08 | **1.00–1.05** | |
  | keyValue | contains, count | 0.93 / 0.83, 0.90 / 0.84 | unchanged, 0.83–0.95 | |
  | Dot-all tail, long-mixed | contains, extract | 278 / 247, 176 / 158 | **50–70** (validates ~4 KiB at 7–9 B/ns) | above 120 |
  | Dot-all tail, long-ascii | contains, extract | 15 / 13, 11 / 9 | unchanged, 9–15 | |
  | Dot-all tail, short-ascii | contains, extract | 3.1–3.2, 2.4–2.5 | unchanged, 2.3–3.2 | |
  | Dot-all tail, short-mixed | contains, extract | 8.0 / 8.2, 5.8 / 6.1 | **3–4.5** | above 6 |
  | Line tail, long-mixed | contains, extract | 19 / 21, 18 / 21 | **3.5–5** | above 9 |
  | Line tail, short-ascii | contains, extract | 1.55 / 1.46, 1.43 / 1.40 | 1.35–1.55 | |
  | Line tail, short-mixed | contains, extract | 4.0 / 3.9, 3.5 / 4.0 | **1.8–2.6** | above 3.5 |
  | Delimited capture | short-ascii | 1.11–1.25 | 1.05–1.20 | |
  | Delimited capture | mixed | 1.31–1.69 | **1.1–1.4** | above 1.6 |
  | Negated run | ascii | 0.99–1.08 | unchanged | |
  | Negated run | mixed | 1.17–1.51 | **1.1–1.4** | |
  | Count witnesses | english/ordered-selective, chinese/ordered-none, email | as EG-7 | as EG-7 | |
  | Controls | | | 0.98–1.02 | |

- **Stopping rules:**
  - **quotedField witness.** If `quotedField` contains or count is above **1.03** on both hosts of a platform, #273 stays draft. I'll then trace its remaining per-call cost with counters and inlining trees before another round.
  - A control above 1.02, or another everyday witness above 1.03, on both hosts of a platform keeps #273 in draft.
  - **The tail rows remain Dain's decision.** They are the price of reading every byte under rule (a). I'll update W-273-TRADEOFF.md with the measured B column for him.
- **I am wrong if:**
  - dot-all long-mixed is above 120. That would mean the vector skip isn't reached on the public route; check that `re2ScanBlocks` runs on that cell.
  - Or dot-all long-ascii moves by more than 10% from EG-7. The remedy does not touch ASCII-only tails.

## Full SHAs

| job | leg | SHA | branch |
|---|---|---|---|
| J1, J2 | A | `11b9291e1c1cc00c18a2662b7588a272961d2ed3` | main |
| J1 | B (#275) | `03abd97a04bf4a5b787ece87b5f2be44b164f18a` | user/dain/rust-unicode-word-dfa |
| J2 | B (#276) | `a12d6c3996cc0e5bf436f26d95fd9279073bbb62` | user/dain/pair-scan-inline-budget |
| J3 | A | `2ba9dbbdbdbe0ef8569227fb3d3e543d032e8a67` | user/dain/bench-trino-plan-costs |
| J3 | B (#273 + bench) | `eab662aca5efc4218ed04dcb1aacde6c07269b31` | user/dain/bench-trino-plan-costs-b |
