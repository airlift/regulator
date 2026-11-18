# RESULTS-EG-8: W4 #275, W6 #276, #273 with the reader remedy

> **Gates and stopping rules tripped. Every one of them trips on r8i only, except #273's quotedField rule, which trips on both platforms.**
> - **#275, ASCII-twin gate:** `i787-opt-unicode-asciionly` rust on r8i is 1.054 (x1 1.045, x2 1.063).
> - **#275, context-argument rule:** 7 no-assertion controls are above 1.02 on both r8i hosts: 03-date java 1.054, rust 1.043, re2 1.041; ing-suffix java 1.025, trino 1.033, rust 1.028, re2 1.021. r9g passes both #275 rules.
> - **#276 stopping rule and "wrong if":** 01-literal sherlock-ru on r8i is 1.029 rust and 1.028 re2, above 1.02 on both hosts. On r9g it is 0.642.
> - **#273 quotedField rule:** contains is 1.098 on r9g; count is 1.098 on r9g and 1.032 on r8i. Each is above 1.03 on both hosts of its platform.
> - **#273 "wrong if":** dot-all short-mixed contains on r8i is 6.27, above 6.

Round: 2026-11-17 15:08–20:13 PST. Legs, hosts, inputs and deviations are in RUN-EG-8.md; per-fork scores are in RESULTS-EG-8.tsv.
- All 214 cells (row × engine/op × platform) have 12/12 forks per leg on 2 hosts. 10,272 forks, 0 FAILED.
- Every fork's JSON `jvmArgs` and log `# VM options:` line equal the spec template, native mode.
- Nothing was dropped. Three Spot reclaims (r8i-y2, r9g-y1, r8i-y2r) were rerun in full on replacement hosts. Their partial forks are in `superseded/` and are not in these results.
- Ratios are B/A; below 1 is faster. Per-host B/A = median(B forks) / median(A forks) on that host, and the platform value is the median of the two hosts. ⚑ marks host pairs that disagree by more than 5%.

## Verdicts

- **#275: FAIL** on r8i, PASS on r9g.
  - ASCII-twin gate tripped on r8i: i787-opt-unicode-asciionly, 245.1 → 259.1 µs.
  - Context-argument rule tripped on r8i: 03-date java, rust and re2, and ing-suffix on all four engines.
  - On r9g every twin is ≤ 1.012 and every control is ≤ 1.017.
  - The targets work: long-russian, boundary-long-russian and around-holmes-russian run at 0.017–0.026. all-russian and boundary-any-russian run at 0.25–0.28, which beats the stopping threshold of 0.3 but misses the predicted range.
- **#276: FAIL**, on r8i.
  - sherlock-ru is 1.029 (rust) and 1.028 (re2) on r8i, with both hosts above 1.02 (33.96 → 34.93 µs).
  - r9g is 0.642, better than predicted (85.1 → 54.6 µs).
  - Every control is within ±1% (largest 1.007).
- **#273: FAIL** on the quotedField witness (r9g contains and count 1.098; r8i count 1.032).
  - Controls pass: 0.999–1.002.
  - The other everyday witnesses pass: keyValue 0.80–1.03 (no row above 1.03 on both hosts), email 0.96, english/ordered-selective 0.74–0.82, chinese/ordered-none 0.88–0.91.
  - Measured tail costs for Dain's trade-off (B/A, r9g / r8i; absolute B in ns/op):

    | Cell | contains | extract | B contains (r9g / r8i) |
    |---|---|---|---|
    | dot-all long-mixed | 50.7 / 82.4 | 32.1 / 53.3 | 448 / 685 |
    | dot-all long-ascii | 14.9 / 13.2 | 10.3 / 8.6 | 140 / 115 |
    | dot-all short-mixed | 5.79 / 6.27 | 4.19 / 4.42 | 55.6 / 56.7 |
    | dot-all short-ascii | 2.93 / 3.17 | 2.35 / 2.31 | 27.8 / 28.7 |
    | line-tail long-mixed | 3.54 / 6.28 | 3.43 / 6.76 | 448 / 655 |
    | line-tail short-mixed | 2.84 / 3.06 | 2.42 / 2.86 | 55.0 / 57.8 |
    | line-tail short-ascii | 1.48 / 1.48 | 1.37 / 1.42 | 27.5 / 28.2 |
    | line-tail long-ascii | 1.10 / 1.03 | 1.10 / 1.23 | 140 / 108 |
    | delimited short/long-mixed | 1.34–1.35 / 1.48–1.57 | 1.38–1.52 / 1.37–1.41 | 32.4–32.6 / 32.0–32.8 |
    | negated-run mixed | 1.39–1.44 / 1.32 | 1.13–1.25 / 1.12–1.21 | 35.5–37.4 / 33.4–34.8 |

  - Against EG-7 (no remedy): dot-all long-mixed fell from 278 / 247 to 50.7 / 82.4, line-tail long-mixed from 19 / 21 to 3.5 / 6.3, and quotedField contains from 1.18 / 1.15 to 1.10 / 0.95.

## J1 — W4 #275: predictions graded

| Family | Prediction | Measured r9g / r8i | Grade |
|---|---|---|---|
| all-russian, boundary-any-russian | 0.05–0.15 (wrong if > 0.3) | 0.271 / 0.246, 0.276 / 0.245 | **miss**: faster as expected, but 2× above the range; below the 0.3 threshold |
| long-russian, boundary-long-russian | 0.02–0.08 | 0.025 / 0.026, 0.025 / 0.025 | **hit** |
| around-holmes-russian | 0.05–0.4 (wrong if > 0.7) | 0.024 / 0.017 | **miss**: better than the range |
| all-greek | 0.1–0.5 (wrong if > 0.7) | 0.444 / 0.421 | **hit** |
| ASCII twins | 0.95–1.03 (gate: > 1.03 on both hosts) | 0.998–1.012 / 0.984–1.054 | **partial**: every twin in range except r8i i787-opt-unicode-asciionly 1.054, which trips the gate |
| Untouched programs | 1.00 ±2% (> 1.02 on both hosts) | 0.974–1.023 / 0.951–1.011 | **partial**: r9g rust word-ending-n median 1.023 (x1 1.036, x2 1.010, so not both hosts). Host-disagreement flags on r9g all-english 0.974 and r8i long-english 0.951 (one host fast). Nothing trips |
| Few-quit movers sherlock word-ending-n java, trino | 0.85–1.00 (wrong if > 1.03) | 0.944, 0.933 / 0.921, 0.911 | **hit** |
| Few-quit movers i787 unicode, opt-unicode | 0.2–0.6 (wrong if > 0.9) | 0.206, 0.480 / 0.214, 0.417 | **hit** |
| grep long-words-unicode (informational) | 0.90–1.03 | 0.762 / 0.384 ⚑ | **miss**: much faster than predicted (r8i hosts 0.395 and 0.372) |
| No-assertion controls | 1.00 ±2% (> 1.02 on both hosts) | 0.983–1.017 / 0.984–1.054 | **miss** on r8i: 7 of 16 controls trip the rule (below). r9g hit |

Gates, stopping rules and "I am wrong if":
- **ASCII-twin gate: TRIPPED (r8i).** `i787-opt-unicode-asciionly` rust is 1.045 and 1.063 on the two r8i hosts (A 245.1 µs, B 259.1 µs). On r9g it is 0.998. Every other twin is ≤ 1.03 on at least one host of each platform.
- **Context-argument rule: TRIPPED (r8i).** Each row below is above 1.02 on both r8i hosts:

  | Row | r8i hosts | median | A → B |
  |---|---|---|---|
  | 03-date java | 1.060, 1.049 | 1.054 | 3.41 → 3.59 ms |
  | 03-date rust | 1.054, 1.032 | 1.043 | 3.43 → 3.58 ms |
  | 03-date re2 | 1.037, 1.045 | 1.041 | 3.40 → 3.56 ms |
  | ing-suffix java | 1.023, 1.027 | 1.025 | 348 → 357 µs |
  | ing-suffix trino | 1.029, 1.037 | 1.033 | 350 → 360 µs |
  | ing-suffix rust | 1.026, 1.030 | 1.028 | 353 → 363 µs |
  | ing-suffix re2 | 1.021, 1.022 | 1.021 | 350 → 357 µs |

  - The re2 rows trip too, so the cost is not specific to decoding programs. That fits the brief's register-pressure explanation for the extra context arguments on x86.
  - quotes and before-holmes pass on both platforms (0.984–1.008). r9g passes everywhere (max 1.017, 03-date trino and re2).
- **Cyrillic target above 0.3:** not tripped. The highest is 0.277 (boundary-any-russian r9g x2).
- **I am wrong if all-greek is below all-russian:** not tripped. Greek is 0.444 / 0.421 and Russian 0.271 / 0.246, so Greek gains less, as predicted.
- The crate and resharp ratios were not measured here; the spec takes them from the Mac's rebar run.

## J2 — W6 #276: predictions graded

| Rows | Prediction r9g / r8i | Measured r9g / r8i | Grade |
|---|---|---|---|
| 01-literal sherlock-ru, rust and re2 | 0.75–0.85 / 0.95–1.00 | 0.642, 0.643 / 1.029, 1.028 | **miss**: r9g is better than the range, and r8i trips its "wrong if" |
| 01-literal en, zh | 1.00 ±2% | 0.998–1.001 / 0.999–1.007 | **hit** |
| 02-literal-alternate ru, casei-ru | 1.00 ±2% | 1.000–1.001 / 0.997–1.001 | **hit** |

- **"Wrong if r9g above 0.92":** not tripped (0.642).
- **"Wrong if r8i above 1.02 on both hosts": TRIPPED.** rust is 1.0287 and 1.0287; re2 is 1.0285 and 1.0271 (A 33.96 µs, B 34.93 µs).
- **Stopping rule (any row above 1.02 on both hosts of a platform): TRIPPED** by r8i sherlock-ru, rust and re2. #276 stays draft.
- The crate ratio was not measured. At 54.6 µs on r9g, the crate comparison is the spec's to make from the published crate times.

## J3 — #273 with the reader remedy: predictions graded

| Family | Prediction | Measured r9g / r8i | Grade |
|---|---|---|---|
| quotedField contains, count | 1.00–1.06 (wrong if > 1.10) | 1.098, 1.098 / 0.949, 1.032 | **partial**: r8i in range (contains below it); r9g at 1.098, just under the 1.10 threshold |
| quotedField extract, extractAll | 1.00–1.05 | 1.023, 1.028 / 1.010, 1.021 | **hit** |
| keyValue contains, count | 0.83–0.95 | 0.928, 0.922 / 0.931, 0.799 | **partial**: r8i count 0.799, just below the range |
| Dot-all long-mixed | 50–70 (wrong if > 120) | 50.7, 32.1 / 82.4, 53.3 | **partial**: r9g contains and r8i extract in range; r9g extract below it; r8i contains 82.4 above it |
| Dot-all long-ascii | 9–15, unchanged | 14.9, 10.3 / 13.2, 8.6 | **hit**: r8i extract 8.6 vs 8.65 in EG-7 |
| Dot-all short-ascii | 2.3–3.2, unchanged | 2.93, 2.35 / 3.17, 2.31 | **hit** |
| Dot-all short-mixed | 3–4.5 (wrong if > 6) | 5.79, 4.19 / 6.27, 4.42 | **miss** for contains (r8i 6.27 trips "wrong if"); extract hit |
| Line-tail long-mixed | 3.5–5 (wrong if > 9) | 3.54, 3.43 / 6.28, 6.76 | **partial**: r9g hit, r8i above the range and below 9 |
| Line-tail short-ascii | 1.35–1.55 | 1.48, 1.37 / 1.48, 1.42 | **hit** |
| Line-tail short-mixed | 1.8–2.6 (wrong if > 3.5) | 2.84, 2.42 / 3.06, 2.86 | **partial**: r9g extract in range; the rest above it and below 3.5 |
| Delimited short-ascii | 1.05–1.20 | 1.130, 1.187 / 1.116, 1.101 | **hit** |
| Delimited mixed | 1.1–1.4 (wrong if > 1.6) | 1.34–1.52 / 1.37–1.57 | **partial**: r9g short-mixed extract 1.515 and r8i contains 1.478 and 1.570 above 1.4; none above 1.6 |
| Negated run ascii | unchanged (0.99–1.08) | 0.975–1.012 / 0.998–1.038 | **hit** |
| Negated run mixed | 1.1–1.4 | 1.08–1.44 / 1.10–1.32 | **partial**: r9g short-mixed contains 1.439 above and long-mixed count 1.082 below |
| Count witnesses | as EG-7 | english/ordered-selective 0.815 / 0.737 (EG-7 0.818 / 0.735); chinese/ordered-none 0.884 / 0.911 (0.849 / 0.919); email 0.962 / 0.957 (0.961 / 0.954) | **hit** |
| Controls | 0.98–1.02 | 1.000–1.002 / 0.999–1.002 | **hit** |

Stopping rules and "I am wrong if":
- **quotedField witness (contains or count above 1.03 on both hosts of a platform): TRIPPED.**
  - r9g contains: 1.099 and 1.096 (A 16.83, B 18.49 ns/op).
  - r9g count: 1.097 and 1.098 (21.30 → 23.38).
  - r8i count: 1.032 and 1.032 (24.26 → 25.05).
  - r8i contains passes at 0.949.
  - #273 stays draft.
- **Control above 1.02, or another everyday witness above 1.03, on both hosts: not tripped.**
  - The highest are keyValue extract on r9g (1.034, 1.018) and quotedField extractAll on r9g (1.021, 1.036). Each has only one host above 1.03.
- **Tail rows:** Dain's decision; the measured B column is in the verdict table above.
- **Wrong if dot-all long-mixed is above 120: not tripped.** The maximum is 82.4 (r8i contains).
- **Wrong if dot-all long-ascii moves by more than 10% from EG-7: not tripped.**
  - contains r9g 14.95 vs 14.96, r8i 13.25 vs 13.34.
  - extract r9g 10.30 vs 11.03 (−6.6%), r8i 8.62 vs 8.65.
  - EG-8 adds `-XX:+UseG1GC -XX:+AlwaysPreTouch`; see RUN-EG-8.md.
- **Additional "wrong if" trip:** dot-all short-mixed contains on r8i is 6.27, above 6 (hosts 6.235 and 6.312).

## Tables



### J1 — W4 #275, Unicode word assertions on the DFA (BenchmarkLanguageBulk.execute)

| family | row | engine/op | platform | host B/A | **B/A** | n | A | B | unit |
|---|---|---|---|---|---|---|---|---|---|
| tgt | curated/08-words/all-russian | rust/execute | r9g | x1 0.270, x2 0.272 | **0.271** | 12/12 | 1.145e+07 | 3.103e+06 | ns/op |
| tgt | curated/08-words/all-russian | rust/execute | r8i | x1 0.248, x2 0.245 | **0.246** | 12/12 | 1.13e+07 | 2.781e+06 | ns/op |
| tgt | curated/08-words/long-russian | rust/execute | r9g | x1 0.025, x2 0.025 | **0.025** | 12/12 | 1.056e+07 | 2.609e+05 | ns/op |
| tgt | curated/08-words/long-russian | rust/execute | r8i | x1 0.026, x2 0.026 | **0.026** | 12/12 | 8.731e+06 | 2.266e+05 | ns/op |
| tgt | unicode/word/boundary-any-russian | rust/execute | r9g | x1 0.275, x2 0.277 | **0.276** | 12/12 | 5.641e+07 | 1.554e+07 | ns/op |
| tgt | unicode/word/boundary-any-russian | rust/execute | r8i | x1 0.245, x2 0.245 | **0.245** | 12/12 | 5.573e+07 | 1.368e+07 | ns/op |
| tgt | unicode/word/boundary-long-russian | rust/execute | r9g | x1 0.025, x2 0.025 | **0.025** | 12/12 | 5.123e+07 | 1.278e+06 | ns/op |
| tgt | unicode/word/boundary-long-russian | rust/execute | r8i | x1 0.025, x2 0.025 | **0.025** | 12/12 | 4.292e+07 | 1.085e+06 | ns/op |
| tgt | unicode/word/around-holmes-russian | rust/execute | r9g | x1 0.024, x2 0.024 | **0.024** | 12/12 | 5.113e+07 | 1.217e+06 | ns/op |
| tgt | unicode/word/around-holmes-russian | rust/execute | r8i | x1 0.017, x2 0.017 | **0.017** | 12/12 | 6.038e+07 | 1.017e+06 | ns/op |
| diag | diagnostic/greek-words/all-greek | rust/execute | r9g | x1 0.444, x2 0.443 | **0.444** | 12/12 | 2.474e+07 | 1.098e+07 | ns/op |
| diag | diagnostic/greek-words/all-greek | rust/execute | r8i | x1 0.421, x2 0.421 | **0.421** | 12/12 | 2.447e+07 | 1.031e+07 | ns/op |
| twin | imported/sherlock/word-ending-n-asciionly | java/execute | r9g | x1 1.009, x2 1.011 | **1.010** | 12/12 | 1.013e+06 | 1.02e+06 | ns/op |
| twin | imported/sherlock/word-ending-n-asciionly | java/execute | r8i | x1 1.007, x2 1.009 | **1.008** | 12/12 | 1.201e+06 | 1.211e+06 | ns/op |
| twin | imported/sherlock/word-ending-n-asciionly | trino/execute | r9g | x1 1.013, x2 1.012 | **1.012** | 12/12 | 1.023e+06 | 1.036e+06 | ns/op |
| twin | imported/sherlock/word-ending-n-asciionly | trino/execute | r8i | x1 1.005, x2 1.001 | **1.003** | 12/12 | 1.212e+06 | 1.216e+06 | ns/op |
| twin | imported/sherlock/words | java/execute | r9g | x1 1.004, x2 1.001 | **1.003** | 12/12 | 2.131e+06 | 2.137e+06 | ns/op |
| twin | imported/sherlock/words | java/execute | r8i | x1 1.013, x2 1.008 | **1.011** | 12/12 | 2.493e+06 | 2.522e+06 | ns/op |
| twin | imported/sherlock/words | trino/execute | r9g | x1 1.001, x2 0.999 | **1.000** | 12/12 | 2.128e+06 | 2.129e+06 | ns/op |
| twin | imported/sherlock/words | trino/execute | r8i | x1 0.954, x2 1.026 | **0.990** ⚑ | 12/12 | 2.599e+06 | 2.559e+06 | ns/op |
| twin | unicode/word/boundary-any-english | java/execute | r9g | x1 1.002, x2 1.009 | **1.005** | 12/12 | 3.809e+06 | 3.818e+06 | ns/op |
| twin | unicode/word/boundary-any-english | java/execute | r8i | x1 0.970, x2 0.999 | **0.984** | 12/12 | 5.277e+06 | 5.27e+06 | ns/op |
| twin | diagnostic/rust-unicode-ascii/word-ending-n | rust/execute | r9g | x1 0.999, x2 1.010 | **1.004** | 12/12 | 1.41e+06 | 1.416e+06 | ns/op |
| twin | diagnostic/rust-unicode-ascii/word-ending-n | rust/execute | r8i | x1 1.007, x2 1.003 | **1.005** | 12/12 | 1.327e+06 | 1.336e+06 | ns/op |
| twin | i787-opt-unicode-asciionly | rust/execute | r9g | x1 0.995, x2 1.000 | **0.998** | 12/12 | 2.648e+05 | 2.644e+05 | ns/op |
| twin | i787-opt-unicode-asciionly | rust/execute | r8i | x1 1.045, x2 1.063 | **1.054** | 12/12 | 2.451e+05 | 2.591e+05 | ns/op |
| untouched | imported/sherlock/word-ending-n-asciionly | re2/execute | r9g | x1 1.011, x2 1.016 | **1.014** | 12/12 | 1.008e+06 | 1.022e+06 | ns/op |
| untouched | imported/sherlock/word-ending-n-asciionly | re2/execute | r8i | x1 1.015, x2 1.007 | **1.011** | 12/12 | 1.201e+06 | 1.217e+06 | ns/op |
| untouched | imported/sherlock/words | re2/execute | r9g | x1 0.998, x2 1.000 | **0.999** | 12/12 | 2.126e+06 | 2.124e+06 | ns/op |
| untouched | imported/sherlock/words | re2/execute | r8i | x1 1.024, x2 0.979 | **1.001** | 12/12 | 2.505e+06 | 2.517e+06 | ns/op |
| untouched | imported/sherlock/word-ending-n | re2/execute | r9g | x1 1.015, x2 1.002 | **1.009** | 12/12 | 1.012e+06 | 1.024e+06 | ns/op |
| untouched | imported/sherlock/word-ending-n | re2/execute | r8i | x1 1.006, x2 1.008 | **1.007** | 12/12 | 1.202e+06 | 1.21e+06 | ns/op |
| untouched | imported/sherlock/word-ending-n-asciionly | rust/execute | r9g | x1 1.000, x2 1.014 | **1.007** | 12/12 | 1.018e+06 | 1.028e+06 | ns/op |
| untouched | imported/sherlock/word-ending-n-asciionly | rust/execute | r8i | x1 1.009, x2 1.005 | **1.007** | 12/12 | 1.204e+06 | 1.214e+06 | ns/op |
| untouched | imported/sherlock/words | rust/execute | r9g | x1 0.998, x2 1.001 | **1.000** | 12/12 | 2.22e+06 | 2.221e+06 | ns/op |
| untouched | imported/sherlock/words | rust/execute | r8i | x1 0.997, x2 1.001 | **0.999** | 12/12 | 2.632e+06 | 2.631e+06 | ns/op |
| untouched | imported/sherlock/word-ending-n | rust/execute | r9g | x1 1.036, x2 1.010 | **1.023** | 12/12 | 1.009e+06 | 1.034e+06 | ns/op |
| untouched | imported/sherlock/word-ending-n | rust/execute | r8i | x1 1.007, x2 1.006 | **1.006** | 12/12 | 1.205e+06 | 1.213e+06 | ns/op |
| untouched | unicode/word/boundary-any-english | re2/execute | r9g | x1 0.994, x2 0.998 | **0.996** | 12/12 | 2.809e+06 | 2.798e+06 | ns/op |
| untouched | unicode/word/boundary-any-english | re2/execute | r8i | x1 1.001, x2 1.001 | **1.001** | 12/12 | 3.898e+06 | 3.902e+06 | ns/op |
| untouched | unicode/word/boundary-any-english | rust/execute | r9g | x1 0.999, x2 1.000 | **1.000** | 12/12 | 2.84e+06 | 2.839e+06 | ns/op |
| untouched | unicode/word/boundary-any-english | rust/execute | r8i | x1 0.996, x2 0.971 | **0.984** | 12/12 | 3.963e+06 | 3.947e+06 | ns/op |
| untouched | unicode/word/boundary-long-english | rust/execute | r9g | x1 1.008, x2 1.007 | **1.008** | 12/12 | 3.047e+05 | 3.067e+05 | ns/op |
| untouched | unicode/word/boundary-long-english | rust/execute | r8i | x1 1.001, x2 0.999 | **1.000** | 12/12 | 4.693e+05 | 4.69e+05 | ns/op |
| untouched | unicode/word/around-holmes-english | rust/execute | r9g | x1 1.000, x2 1.000 | **1.000** | 12/12 | 1.751e+04 | 1.75e+04 | ns/op |
| untouched | unicode/word/around-holmes-english | rust/execute | r8i | x1 1.000, x2 1.000 | **1.000** | 12/12 | 1.044e+04 | 1.044e+04 | ns/op |
| untouched | curated/08-words/all-english | rust/execute | r9g | x1 0.999, x2 0.950 | **0.974** ⚑ | 12/12 | 3.251e+05 | 3.158e+05 | ns/op |
| untouched | curated/08-words/all-english | rust/execute | r8i | x1 1.003, x2 1.001 | **1.002** | 12/12 | 4.468e+05 | 4.479e+05 | ns/op |
| untouched | curated/08-words/long-english | rust/execute | r9g | x1 1.000, x2 1.005 | **1.002** | 12/12 | 2.018e+04 | 2.019e+04 | ns/op |
| untouched | curated/08-words/long-english | rust/execute | r8i | x1 0.994, x2 0.908 | **0.951** ⚑ | 12/12 | 3.639e+04 | 3.329e+04 | ns/op |
| mover | imported/sherlock/word-ending-n | java/execute | r9g | y1 0.945, y2 0.942 | **0.944** | 12/12 | 1.217e+06 | 1.149e+06 | ns/op |
| mover | imported/sherlock/word-ending-n | java/execute | r8i | y1 0.920, y2 0.922 | **0.921** | 12/12 | 1.359e+06 | 1.252e+06 | ns/op |
| mover | imported/sherlock/word-ending-n | trino/execute | r9g | y1 0.932, y2 0.935 | **0.933** | 12/12 | 1.238e+06 | 1.157e+06 | ns/op |
| mover | imported/sherlock/word-ending-n | trino/execute | r8i | y1 0.912, y2 0.910 | **0.911** | 12/12 | 1.38e+06 | 1.257e+06 | ns/op |
| mover | reported/i787-keywords/unicode | rust/execute | r9g | y1 0.206, y2 0.206 | **0.206** | 12/12 | 1.323e+06 | 2.726e+05 | ns/op |
| mover | reported/i787-keywords/unicode | rust/execute | r8i | y1 0.214, y2 0.214 | **0.214** | 12/12 | 1.27e+06 | 2.72e+05 | ns/op |
| mover | reported/i787-keywords/opt-unicode | rust/execute | r9g | y1 0.480, y2 0.480 | **0.480** | 12/12 | 5.678e+05 | 2.723e+05 | ns/op |
| mover | reported/i787-keywords/opt-unicode | rust/execute | r8i | y1 0.419, y2 0.416 | **0.417** | 12/12 | 6.411e+05 | 2.677e+05 | ns/op |
| mover | grep/long-words-unicode | rust/execute | r9g | y1 0.762, y2 0.762 | **0.762** | 12/12 | 1.661e+07 | 1.264e+07 | ns/op |
| mover | grep/long-words-unicode | rust/execute | r8i | y1 0.395, y2 0.372 | **0.384** ⚑ | 12/12 | 2.678e+07 | 1.046e+07 | ns/op |
| ctl | curated/03-date/ascii | java/execute | r9g | y1 1.011, y2 1.014 | **1.013** | 12/12 | 3.061e+06 | 3.1e+06 | ns/op |
| ctl | curated/03-date/ascii | java/execute | r8i | y1 1.060, y2 1.049 | **1.054** | 12/12 | 3.413e+06 | 3.594e+06 | ns/op |
| ctl | curated/03-date/ascii | trino/execute | r9g | y1 1.018, y2 1.016 | **1.017** | 12/12 | 3.071e+06 | 3.126e+06 | ns/op |
| ctl | curated/03-date/ascii | trino/execute | r8i | y1 1.015, y2 1.010 | **1.013** | 12/12 | 3.249e+06 | 3.283e+06 | ns/op |
| ctl | curated/03-date/ascii | rust/execute | r9g | y1 1.013, y2 1.012 | **1.012** | 12/12 | 3.08e+06 | 3.119e+06 | ns/op |
| ctl | curated/03-date/ascii | rust/execute | r8i | y1 1.054, y2r 1.032 | **1.043** | 12/12 | 3.431e+06 | 3.579e+06 | ns/op |
| ctl | curated/03-date/ascii | re2/execute | r9g | y1 1.016, y2 1.019 | **1.017** | 12/12 | 3.048e+06 | 3.104e+06 | ns/op |
| ctl | curated/03-date/ascii | re2/execute | r8i | y1 1.037, y2r 1.045 | **1.041** | 12/12 | 3.403e+06 | 3.555e+06 | ns/op |
| ctl | imported/sherlock/quotes | java/execute | r9g | y1 1.003, y2 1.005 | **1.004** | 12/12 | 2.637e+05 | 2.646e+05 | ns/op |
| ctl | imported/sherlock/quotes | java/execute | r8i | y1 0.993, y2r 0.998 | **0.995** | 12/12 | 2.756e+05 | 2.743e+05 | ns/op |
| ctl | imported/sherlock/quotes | trino/execute | r9g | y1 1.005, y2 1.010 | **1.007** | 12/12 | 2.64e+05 | 2.661e+05 | ns/op |
| ctl | imported/sherlock/quotes | trino/execute | r8i | y1 1.004, y2r 1.001 | **1.002** | 12/12 | 2.745e+05 | 2.755e+05 | ns/op |
| ctl | imported/sherlock/quotes | rust/execute | r9g | y1 1.002, y2 1.009 | **1.006** | 12/12 | 2.64e+05 | 2.654e+05 | ns/op |
| ctl | imported/sherlock/quotes | rust/execute | r8i | y1 0.998, y2r 1.005 | **1.001** | 12/12 | 2.738e+05 | 2.737e+05 | ns/op |
| ctl | imported/sherlock/quotes | re2/execute | r9g | y1 1.007, y2 1.010 | **1.008** | 12/12 | 2.633e+05 | 2.657e+05 | ns/op |
| ctl | imported/sherlock/quotes | re2/execute | r8i | y1 1.000, y2r 0.992 | **0.996** | 12/12 | 2.756e+05 | 2.747e+05 | ns/op |
| ctl | imported/sherlock/ing-suffix | java/execute | r9g | y1 1.007, y2 0.983 | **0.995** | 12/12 | 2.977e+05 | 2.947e+05 | ns/op |
| ctl | imported/sherlock/ing-suffix | java/execute | r8i | y1 1.023, y2r 1.027 | **1.025** | 12/12 | 3.483e+05 | 3.574e+05 | ns/op |
| ctl | imported/sherlock/ing-suffix | trino/execute | r9g | y1r 0.996, y2 1.002 | **0.999** | 12/12 | 3e+05 | 2.992e+05 | ns/op |
| ctl | imported/sherlock/ing-suffix | trino/execute | r8i | y1 1.029, y2r 1.037 | **1.033** | 12/12 | 3.498e+05 | 3.604e+05 | ns/op |
| ctl | imported/sherlock/ing-suffix | rust/execute | r9g | y1r 1.002, y2 1.006 | **1.004** | 12/12 | 3.007e+05 | 3.02e+05 | ns/op |
| ctl | imported/sherlock/ing-suffix | rust/execute | r8i | y1 1.026, y2r 1.030 | **1.028** | 12/12 | 3.526e+05 | 3.628e+05 | ns/op |
| ctl | imported/sherlock/ing-suffix | re2/execute | r9g | y1r 1.010, y2 0.994 | **1.002** | 12/12 | 2.967e+05 | 2.993e+05 | ns/op |
| ctl | imported/sherlock/ing-suffix | re2/execute | r8i | y1 1.021, y2t 1.022 | **1.021** | 12/12 | 3.496e+05 | 3.569e+05 | ns/op |
| ctl | imported/sherlock/before-holmes | java/execute | r9g | y1r 1.004, y2 1.003 | **1.004** | 12/12 | 5.873e+04 | 5.89e+04 | ns/op |
| ctl | imported/sherlock/before-holmes | java/execute | r8i | y1 0.988, y2t 0.988 | **0.988** | 12/12 | 4.779e+04 | 4.703e+04 | ns/op |
| ctl | imported/sherlock/before-holmes | trino/execute | r9g | y1r 0.980, y2 0.985 | **0.983** | 12/12 | 6.008e+04 | 5.907e+04 | ns/op |
| ctl | imported/sherlock/before-holmes | trino/execute | r8i | y1 0.985, y2t 0.984 | **0.984** | 12/12 | 4.783e+04 | 4.719e+04 | ns/op |
| ctl | imported/sherlock/before-holmes | rust/execute | r9g | y1r 1.006, y2 1.004 | **1.005** | 12/12 | 5.87e+04 | 5.898e+04 | ns/op |
| ctl | imported/sherlock/before-holmes | rust/execute | r8i | y1 0.993, y2t 1.007 | **1.000** | 12/12 | 4.797e+04 | 4.765e+04 | ns/op |
| ctl | imported/sherlock/before-holmes | re2/execute | r9g | y1r 1.004, y2 1.005 | **1.005** | 12/12 | 5.858e+04 | 5.885e+04 | ns/op |
| ctl | imported/sherlock/before-holmes | re2/execute | r8i | y1 0.990, y2t 0.988 | **0.989** | 12/12 | 4.787e+04 | 4.719e+04 | ns/op |

### J3 — #273 with the reader remedy

| family | row | engine/op | platform | host B/A | **B/A** | n | A | B | unit |
|---|---|---|---|---|---|---|---|---|---|
| plan-costs | plan-costs/delimited/long-ascii | trino/contains | r9g | z1 1.119, z2 1.119 | **1.119** | 12/12 | 24.45 | 27.37 | ns/op |
| plan-costs | plan-costs/delimited/long-ascii | trino/contains | r8i | z1 1.120, z2 1.120 | **1.120** | 12/12 | 20.66 | 23.14 | ns/op |
| plan-costs | plan-costs/delimited/long-ascii | trino/extract | r9g | z1 1.084, z2 1.083 | **1.083** | 12/12 | 31.11 | 33.7 | ns/op |
| plan-costs | plan-costs/delimited/long-ascii | trino/extract | r8i | z1 1.100, z2 1.099 | **1.100** | 12/12 | 26.1 | 28.7 | ns/op |
| plan-costs | plan-costs/delimited/long-mixed | trino/contains | r9g | z1 1.346, z2 1.348 | **1.347** | 12/12 | 24.04 | 32.4 | ns/op |
| plan-costs | plan-costs/delimited/long-mixed | trino/contains | r8i | z1 1.568, z2 1.571 | **1.570** | 12/12 | 20.87 | 32.76 | ns/op |
| plan-costs | plan-costs/delimited/long-mixed | trino/extract | r9g | z1 1.378, z2 1.377 | **1.378** | 12/12 | 31.14 | 42.89 | ns/op |
| plan-costs | plan-costs/delimited/long-mixed | trino/extract | r8i | z1 1.406, z2 1.406 | **1.406** | 12/12 | 26.19 | 36.84 | ns/op |
| plan-costs | plan-costs/delimited/short-ascii | trino/contains | r9g | z1 1.130, z2 1.131 | **1.130** | 12/12 | 24.28 | 27.45 | ns/op |
| plan-costs | plan-costs/delimited/short-ascii | trino/contains | r8i | z1 1.115, z2 1.118 | **1.116** | 12/12 | 20.78 | 23.21 | ns/op |
| plan-costs | plan-costs/delimited/short-ascii | trino/extract | r9g | z1 1.187, z2 1.186 | **1.187** | 12/12 | 28.35 | 33.63 | ns/op |
| plan-costs | plan-costs/delimited/short-ascii | trino/extract | r8i | z1 1.101, z2 1.102 | **1.101** | 12/12 | 25.97 | 28.6 | ns/op |
| plan-costs | plan-costs/delimited/short-mixed | trino/contains | r9g | z1 1.342, z2 1.337 | **1.339** | 12/12 | 24.35 | 32.58 | ns/op |
| plan-costs | plan-costs/delimited/short-mixed | trino/contains | r8i | z1 1.477, z2 1.480 | **1.478** | 12/12 | 21.66 | 32.01 | ns/op |
| plan-costs | plan-costs/delimited/short-mixed | trino/extract | r9g | z1 1.516, z2 1.515 | **1.515** | 12/12 | 28.38 | 42.99 | ns/op |
| plan-costs | plan-costs/delimited/short-mixed | trino/extract | r8i | z1 1.368, z2 1.370 | **1.369** | 12/12 | 26.87 | 36.8 | ns/op |
| plan-costs | plan-costs/dotall-tail/long-ascii | trino/contains | r9g | z1 15.008, z2 14.888 | **14.948** | 12/12 | 9.405 | 140.1 | ns/op |
| plan-costs | plan-costs/dotall-tail/long-ascii | trino/contains | r8i | z1 13.218, z2 13.278 | **13.248** | 12/12 | 8.656 | 114.6 | ns/op |
| plan-costs | plan-costs/dotall-tail/long-ascii | trino/extract | r9g | z1 10.351, z2 10.246 | **10.299** | 12/12 | 14.09 | 145.2 | ns/op |
| plan-costs | plan-costs/dotall-tail/long-ascii | trino/extract | r8i | z1 8.620, z2 8.625 | **8.622** | 12/12 | 13.39 | 115.6 | ns/op |
| plan-costs | plan-costs/dotall-tail/long-mixed | trino/contains | r9g | z1 50.727, z2 50.575 | **50.651** | 12/12 | 8.852 | 448.3 | ns/op |
| plan-costs | plan-costs/dotall-tail/long-mixed | trino/contains | r8i | z1 82.489, z2 82.395 | **82.442** | 12/12 | 8.318 | 685.4 | ns/op |
| plan-costs | plan-costs/dotall-tail/long-mixed | trino/extract | r9g | z1 32.096, z2 32.171 | **32.133** | 12/12 | 14.03 | 450.9 | ns/op |
| plan-costs | plan-costs/dotall-tail/long-mixed | trino/extract | r8i | z1 53.265, z2 53.390 | **53.327** | 12/12 | 12.95 | 690.6 | ns/op |
| plan-costs | plan-costs/dotall-tail/short-ascii | trino/contains | r9g | z1 2.979, z2 2.879 | **2.929** | 12/12 | 9.592 | 27.8 | ns/op |
| plan-costs | plan-costs/dotall-tail/short-ascii | trino/contains | r8i | z1 3.147, z2 3.190 | **3.168** | 12/12 | 9.01 | 28.74 | ns/op |
| plan-costs | plan-costs/dotall-tail/short-ascii | trino/extract | r9g | z1 2.351, z2 2.346 | **2.348** | 12/12 | 14.21 | 33.35 | ns/op |
| plan-costs | plan-costs/dotall-tail/short-ascii | trino/extract | r8i | z1 2.316, z2 2.295 | **2.306** | 12/12 | 13.48 | 30.96 | ns/op |
| plan-costs | plan-costs/dotall-tail/short-mixed | trino/contains | r9g | z1 5.793, z2 5.786 | **5.789** | 12/12 | 9.611 | 55.63 | ns/op |
| plan-costs | plan-costs/dotall-tail/short-mixed | trino/contains | r8i | z1 6.235, z2 6.312 | **6.273** | 12/12 | 9.01 | 56.7 | ns/op |
| plan-costs | plan-costs/dotall-tail/short-mixed | trino/extract | r9g | z1 4.194, z2 4.188 | **4.191** | 12/12 | 14.16 | 59.37 | ns/op |
| plan-costs | plan-costs/dotall-tail/short-mixed | trino/extract | r8i | z1 4.429, z2 4.415 | **4.422** | 12/12 | 13.46 | 59.47 | ns/op |
| plan-costs | plan-costs/line-tail/long-ascii | trino/contains | r9g | z1 1.088, z2 1.104 | **1.096** | 12/12 | 127.8 | 139.5 | ns/op |
| plan-costs | plan-costs/line-tail/long-ascii | trino/contains | r8i | z1 1.052, z2 1.007 | **1.029** | 12/12 | 105.6 | 107.5 | ns/op |
| plan-costs | plan-costs/line-tail/long-ascii | trino/extract | r9g | z1 1.098, z2 1.094 | **1.096** | 12/12 | 132 | 144.9 | ns/op |
| plan-costs | plan-costs/line-tail/long-ascii | trino/extract | r8i | z1 1.220, z2 1.232 | **1.226** | 12/12 | 93.73 | 115.1 | ns/op |
| plan-costs | plan-costs/line-tail/long-mixed | trino/contains | r9g | z1 3.540, z2 3.542 | **3.541** | 12/12 | 126.4 | 447.5 | ns/op |
| plan-costs | plan-costs/line-tail/long-mixed | trino/contains | r8i | z1 6.280, z2 6.275 | **6.277** | 12/12 | 104.3 | 654.7 | ns/op |
| plan-costs | plan-costs/line-tail/long-mixed | trino/extract | r9g | z1 3.433, z2 3.429 | **3.431** | 12/12 | 131.6 | 451.2 | ns/op |
| plan-costs | plan-costs/line-tail/long-mixed | trino/extract | r8i | z1 6.759, z2 6.751 | **6.755** | 12/12 | 97.61 | 660.5 | ns/op |
| plan-costs | plan-costs/line-tail/short-ascii | trino/contains | r9g | z1 1.447, z2 1.516 | **1.481** | 12/12 | 18.96 | 27.51 | ns/op |
| plan-costs | plan-costs/line-tail/short-ascii | trino/contains | r8i | z1 1.429, z2 1.530 | **1.479** ⚑ | 12/12 | 18.65 | 28.24 | ns/op |
| plan-costs | plan-costs/line-tail/short-ascii | trino/extract | r9g | z1 1.372, z2 1.372 | **1.372** | 12/12 | 23.87 | 32.74 | ns/op |
| plan-costs | plan-costs/line-tail/short-ascii | trino/extract | r8i | z1 1.440, z2 1.402 | **1.421** | 12/12 | 21.23 | 30.55 | ns/op |
| plan-costs | plan-costs/line-tail/short-mixed | trino/contains | r9g | z1 2.846, z2 2.834 | **2.840** | 12/12 | 19.38 | 55.02 | ns/op |
| plan-costs | plan-costs/line-tail/short-mixed | trino/contains | r8i | z1 3.065, z2 3.058 | **3.062** | 12/12 | 18.85 | 57.75 | ns/op |
| plan-costs | plan-costs/line-tail/short-mixed | trino/extract | r9g | z1 2.419, z2 2.422 | **2.420** | 12/12 | 24.24 | 58.66 | ns/op |
| plan-costs | plan-costs/line-tail/short-mixed | trino/extract | r8i | z1 2.857, z2 2.855 | **2.856** | 12/12 | 21.35 | 60.96 | ns/op |
| plan-costs | plan-costs/negated-run/long-ascii | trino/contains | r9g | z1 1.006, z2 1.019 | **1.012** | 12/12 | 25.84 | 26.16 | ns/op |
| plan-costs | plan-costs/negated-run/long-ascii | trino/contains | r8i | z1 1.014, z2 1.013 | **1.013** | 12/12 | 25.53 | 25.96 | ns/op |
| plan-costs | plan-costs/negated-run/long-ascii | trino/count | r9g | z1 1.002, z2 1.000 | **1.001** | 12/12 | 146.8 | 146.9 | ns/op |
| plan-costs | plan-costs/negated-run/long-ascii | trino/count | r8i | z1 0.999, z2 0.998 | **0.999** | 12/12 | 121 | 120.9 | ns/op |
| plan-costs | plan-costs/negated-run/long-ascii | trino/extract | r9g | z1 0.983, z2 0.986 | **0.984** | 12/12 | 52.54 | 51.72 | ns/op |
| plan-costs | plan-costs/negated-run/long-ascii | trino/extract | r8i | z1 1.039, z2 1.038 | **1.038** | 12/12 | 53.49 | 55.47 | ns/op |
| plan-costs | plan-costs/negated-run/long-mixed | trino/contains | r9g | z1 1.375, z2 1.395 | **1.385** | 12/12 | 25.75 | 35.53 | ns/op |
| plan-costs | plan-costs/negated-run/long-mixed | trino/contains | r8i | z1 1.324, z2 1.314 | **1.319** | 12/12 | 25.28 | 33.37 | ns/op |
| plan-costs | plan-costs/negated-run/long-mixed | trino/count | r9g | z1 1.082, z2 1.083 | **1.082** | 12/12 | 145.7 | 157.8 | ns/op |
| plan-costs | plan-costs/negated-run/long-mixed | trino/count | r8i | z1 1.100, z2 1.100 | **1.100** | 12/12 | 121.6 | 133.8 | ns/op |
| plan-costs | plan-costs/negated-run/long-mixed | trino/extract | r9g | z1 1.250, z2 1.252 | **1.251** | 12/12 | 49.81 | 62.29 | ns/op |
| plan-costs | plan-costs/negated-run/long-mixed | trino/extract | r8i | z1 1.215, z2 1.205 | **1.210** | 12/12 | 52.25 | 62.93 | ns/op |
| plan-costs | plan-costs/negated-run/short-ascii | trino/contains | r9g | z1 0.999, z2 0.979 | **0.989** | 12/12 | 25.82 | 25.59 | ns/op |
| plan-costs | plan-costs/negated-run/short-ascii | trino/contains | r8i | z1 0.988, z2 1.013 | **1.001** | 12/12 | 26.08 | 26.06 | ns/op |
| plan-costs | plan-costs/negated-run/short-ascii | trino/count | r9g | z1 0.973, z2 0.976 | **0.975** | 12/12 | 37 | 36.07 | ns/op |
| plan-costs | plan-costs/negated-run/short-ascii | trino/count | r8i | z1 1.002, z2 1.001 | **1.001** | 12/12 | 36.62 | 36.69 | ns/op |
| plan-costs | plan-costs/negated-run/short-ascii | trino/extract | r9g | z1 0.993, z2 0.991 | **0.992** | 12/12 | 90.05 | 89.31 | ns/op |
| plan-costs | plan-costs/negated-run/short-ascii | trino/extract | r8i | z1 0.996, z2 1.000 | **0.998** | 12/12 | 83.96 | 83.71 | ns/op |
| plan-costs | plan-costs/negated-run/short-mixed | trino/contains | r9g | z1 1.440, z2 1.438 | **1.439** | 12/12 | 25.97 | 37.4 | ns/op |
| plan-costs | plan-costs/negated-run/short-mixed | trino/contains | r8i | z1 1.335, z2 1.296 | **1.316** | 12/12 | 26.29 | 34.81 | ns/op |
| plan-costs | plan-costs/negated-run/short-mixed | trino/count | r9g | z1 1.327, z2 1.326 | **1.327** | 12/12 | 37.35 | 49.54 | ns/op |
| plan-costs | plan-costs/negated-run/short-mixed | trino/count | r8i | z1 1.251, z2 1.252 | **1.251** | 12/12 | 36.92 | 46.22 | ns/op |
| plan-costs | plan-costs/negated-run/short-mixed | trino/extract | r9g | z1 1.129, z2 1.131 | **1.130** | 12/12 | 92.69 | 104.9 | ns/op |
| plan-costs | plan-costs/negated-run/short-mixed | trino/extract | r8i | z1 1.118, z2 1.118 | **1.118** | 12/12 | 85.15 | 95.2 | ns/op |
| trino-everyday | trino-everyday/email | trino/count | r9g | y1s 0.962, y2 0.963 | **0.962** | 12/12 | 54.02 | 51.98 | ns/op |
| trino-everyday | trino-everyday/email | trino/count | r8i | y1 0.957, y2s 0.956 | **0.957** | 12/12 | 46.64 | 44.61 | ns/op |
| trino-everyday | trino-everyday/keyValue | trino/contains | r9g | y1s 0.929, y2 0.928 | **0.928** | 12/12 | 26.21 | 24.34 | ns/op |
| trino-everyday | trino-everyday/keyValue | trino/contains | r8i | y1 0.931, y2s 0.931 | **0.931** | 12/12 | 27.72 | 25.81 | ns/op |
| trino-everyday | trino-everyday/keyValue | trino/count | r9g | y1s 0.921, y2 0.923 | **0.922** | 12/12 | 53.65 | 49.52 | ns/op |
| trino-everyday | trino-everyday/keyValue | trino/count | r8i | y1 0.794, y2s 0.805 | **0.799** | 12/12 | 58.71 | 46.96 | ns/op |
| trino-everyday | trino-everyday/keyValue | trino/extract | r9g | y1s 1.034, y2 1.018 | **1.026** | 12/12 | 64.62 | 66.56 | ns/op |
| trino-everyday | trino-everyday/keyValue | trino/extract | r8i | y1 0.927, y2s 0.946 | **0.936** | 12/12 | 68.76 | 64.96 | ns/op |
| trino-everyday | trino-everyday/keyValue | trino/extractAll | r9g | y1s 1.019, y2 0.965 | **0.992** ⚑ | 12/12 | 88.22 | 89.11 | ns/op |
| trino-everyday | trino-everyday/keyValue | trino/extractAll | r8i | y1 0.868, y2s 0.911 | **0.890** | 12/12 | 96.37 | 85.86 | ns/op |
| trino-everyday | trino-everyday/logLevel | trino/contains | r9g | y1s 1.001, y2 1.000 | **1.001** | 12/12 | 14.85 | 14.86 | ns/op |
| trino-everyday | trino-everyday/logLevel | trino/contains | r8i | y1 0.998, y2s 1.000 | **0.999** | 12/12 | 14.78 | 14.77 | ns/op |
| trino-everyday | trino-everyday/quotedField | trino/contains | r9g | y1s 1.099, y2 1.096 | **1.098** | 12/12 | 16.83 | 18.49 | ns/op |
| trino-everyday | trino-everyday/quotedField | trino/contains | r8i | y1 0.954, y2s 0.945 | **0.949** | 12/12 | 18.95 | 17.95 | ns/op |
| trino-everyday | trino-everyday/quotedField | trino/count | r9g | y1s 1.097, y2 1.098 | **1.098** | 12/12 | 21.3 | 23.38 | ns/op |
| trino-everyday | trino-everyday/quotedField | trino/count | r8i | y1 1.032, y2s 1.032 | **1.032** | 12/12 | 24.26 | 25.05 | ns/op |
| trino-everyday | trino-everyday/quotedField | trino/extract | r9g | y1s 1.025, y2 1.021 | **1.023** | 12/12 | 44.96 | 46.08 | ns/op |
| trino-everyday | trino-everyday/quotedField | trino/extract | r8i | y1 1.024, y2s 0.997 | **1.010** | 12/12 | 43.53 | 43.75 | ns/op |
| trino-everyday | trino-everyday/quotedField | trino/extractAll | r9g | y1s 1.021, y2 1.036 | **1.028** | 12/12 | 58.82 | 60.36 | ns/op |
| trino-everyday | trino-everyday/quotedField | trino/extractAll | r8i | y1 1.034, y2s 1.007 | **1.021** | 12/12 | 55.67 | 56.89 | ns/op |
| trino-rows | trino-rows/chinese/ordered-none | trino/count | r9g | y1s 0.879, y2 0.890 | **0.884** | 12/12 | 27.93 | 24.72 | ns/op |
| trino-rows | trino-rows/chinese/ordered-none | trino/count | r8i | y1 0.908, y2s 0.914 | **0.911** | 12/12 | 34.68 | 31.58 | ns/op |
| trino-rows | trino-rows/english/dotstar-low-selective | trino/contains | r9g | y1s 1.000, y2 1.000 | **1.000** | 12/12 | 9.927 | 9.934 | ns/op |
| trino-rows | trino-rows/english/dotstar-low-selective | trino/contains | r8i | y1 1.000, y2s 1.003 | **1.002** | 12/12 | 19.05 | 19.08 | ns/op |
| trino-rows | trino-rows/english/low-selective | trino/contains | r9g | y1s 1.001, y2 1.002 | **1.002** | 12/12 | 10.12 | 10.13 | ns/op |
| trino-rows | trino-rows/english/low-selective | trino/contains | r8i | y1 0.994, y2s 1.009 | **1.001** | 12/12 | 24.55 | 24.54 | ns/op |
| trino-rows | trino-rows/english/ordered-selective | trino/count | r9g | y1s 0.817, y2 0.812 | **0.815** | 12/12 | 27.92 | 22.74 | ns/op |
| trino-rows | trino-rows/english/ordered-selective | trino/count | r8i | y1 0.729, y2s 0.744 | **0.737** | 12/12 | 40.08 | 29.77 | ns/op |
| trino-rows | trino-rows/nginx/mid-common | trino/contains | r9g | y1s 1.001, y2 1.000 | **1.000** | 12/12 | 24.93 | 24.94 | ns/op |
| trino-rows | trino-rows/nginx/mid-common | trino/contains | r8i | y1 1.001, y2s 0.999 | **1.000** | 12/12 | 29.01 | 28.99 | ns/op |

### J2 — W6 #276, Russian literal pair scan (BenchmarkLanguageBulk.execute)

| family | row | engine/op | platform | host B/A | **B/A** | n | A | B | unit |
|---|---|---|---|---|---|---|---|---|---|
| tgt | curated/01-literal/sherlock-ru | rust/execute | r9g | x1 0.642, x2 0.642 | **0.642** | 12/12 | 8.513e+04 | 5.465e+04 | ns/op |
| tgt | curated/01-literal/sherlock-ru | rust/execute | r8i | x1 1.029, x2 1.029 | **1.029** | 12/12 | 3.396e+04 | 3.493e+04 | ns/op |
| tgt | curated/01-literal/sherlock-ru | re2/execute | r9g | x1 0.643, x2 0.642 | **0.643** | 12/12 | 8.514e+04 | 5.471e+04 | ns/op |
| tgt | curated/01-literal/sherlock-ru | re2/execute | r8i | x1 1.028, x2 1.027 | **1.028** | 12/12 | 3.398e+04 | 3.493e+04 | ns/op |
| ctl | curated/01-literal/sherlock-en | rust/execute | r9g | x1 1.001, x2 1.000 | **1.000** | 12/12 | 3.246e+04 | 3.247e+04 | ns/op |
| ctl | curated/01-literal/sherlock-en | rust/execute | r8i | x1 0.999, x2 1.000 | **0.999** | 12/12 | 2.054e+04 | 2.052e+04 | ns/op |
| ctl | curated/01-literal/sherlock-en | re2/execute | r9g | x1 1.001, x2 1.001 | **1.001** | 12/12 | 3.245e+04 | 3.249e+04 | ns/op |
| ctl | curated/01-literal/sherlock-en | re2/execute | r8i | x1 0.999, x2 1.000 | **1.000** | 12/12 | 2.054e+04 | 2.053e+04 | ns/op |
| ctl | curated/01-literal/sherlock-zh | rust/execute | r9g | x1 0.998, x2 0.998 | **0.998** | 12/12 | 2.357e+04 | 2.353e+04 | ns/op |
| ctl | curated/01-literal/sherlock-zh | rust/execute | r8i | x1 1.007, x2 1.007 | **1.007** | 12/12 | 1.165e+04 | 1.173e+04 | ns/op |
| ctl | curated/01-literal/sherlock-zh | re2/execute | r9g | x1 0.997, x2 0.998 | **0.998** | 12/12 | 2.359e+04 | 2.353e+04 | ns/op |
| ctl | curated/01-literal/sherlock-zh | re2/execute | r8i | x1 1.003, x2 1.003 | **1.003** | 12/12 | 1.166e+04 | 1.171e+04 | ns/op |
| ctl | curated/02-literal-alternate/sherlock-ru | rust/execute | r9g | z1 1.001, z2 1.000 | **1.001** | 12/12 | 2.449e+05 | 2.451e+05 | ns/op |
| ctl | curated/02-literal-alternate/sherlock-ru | rust/execute | r8i | z1 0.997, z2 0.998 | **0.997** | 12/12 | 1.672e+05 | 1.669e+05 | ns/op |
| ctl | curated/02-literal-alternate/sherlock-ru | re2/execute | r9g | z1 1.001, z2 1.000 | **1.001** | 12/12 | 2.446e+05 | 2.447e+05 | ns/op |
| ctl | curated/02-literal-alternate/sherlock-ru | re2/execute | r8i | z1 1.001, z2 1.000 | **1.000** | 12/12 | 1.676e+05 | 1.67e+05 | ns/op |
| ctl | curated/02-literal-alternate/sherlock-casei-ru | rust/execute | r9g | z1 0.998, z2 1.002 | **1.000** | 12/12 | 5.365e+05 | 5.356e+05 | ns/op |
| ctl | curated/02-literal-alternate/sherlock-casei-ru | rust/execute | r8i | z1 0.999, z2 1.003 | **1.001** | 12/12 | 5.439e+05 | 5.44e+05 | ns/op |
| ctl | curated/02-literal-alternate/sherlock-casei-ru | re2/execute | r9g | z1 1.001, z2 1.001 | **1.001** | 12/12 | 5.36e+05 | 5.369e+05 | ns/op |
| ctl | curated/02-literal-alternate/sherlock-casei-ru | re2/execute | r8i | z1 1.000, z2 0.994 | **0.997** | 12/12 | 5.439e+05 | 5.431e+05 | ns/op |
