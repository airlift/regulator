# AWS timing jobs, round EG-10: #273 fixed (J2) and #276 fixed (J3), the EG-9 follow-up

For the coordinator to run on AWS Spot. Written 2026-11-17 PST on dain-1. This is EG-9's follow-up round: the J2 and J3 that kit `0c193c9da` left out. **I wrote the predictions before anything ran.** Every leg is built on main `11b9291e1`. Both fixes have had their adversarial and polish delta reviews, and both converged.

**Budget.** At EG-8's rate (about $0.000305 per fork):
- D1C about $0.02, D2 about $0.03;
- J2 2,688 forks, about $0.82;
- J3 960 forks, about $0.29.

The total is about **$1.16**. That is within the $3.25 left after EG-9's first round, which has a $1.40 cap.

**Conventions,** as EG-9:
- **12 forks per leg per host.** Use **2 hosts per platform**, on **r9g** and **r8i**.
- Legs interleave fork by fork (ABAB). Both legs of a row run on the same host.
- **Native** mode only. jvmArgs are EG-8's template: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- Use the class's JMH defaults, and put the legs' jars at paths of equal length.
- For each row and platform, report the median B/A across both hosts, with the per-host values. Flag (⚑) any host pair that disagrees by more than 5%.

**Diagnostic flags** (D1C, D2), as EG-9 D0: the template jvmArgs plus `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining -XX:+LogCompilation -XX:LogFile=<out>/<name>.xml`, and `-f 1 -prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>/<name>.perfasm'`. The host needs `kernel.perf_event_paranoid` ≤ 1 and an hsdis-amd64 matching the JDK.

**Order:** on the first r8i host, D1C and then D2. Then J2 and J3 everywhere. If the guard nears the cap, J3 goes before J2's cut rows, since it is the smaller job.

## J2 — #273 fixed: quotedField and the delimited capture

i23 found that #273's run reader walked the short ASCII runs of `quotedField` (`"([^"]*)"`, 1–13 bytes) by character, in a loop the JIT can't count: a bounds check, a sign test and a stop test per byte. The fix, a fifth #273 commit `533cf90ee`, has two parts:
- **F1:** the run stops at its stop byte or the first non-ASCII byte in a counted loop with the vector scan's one compare. Only a non-ASCII stop enters the walk by character.
- **F2:** the delimited capture accepts its delimiter from that same scan.

Counters show no walk steps and no width reads on ASCII rows, and the Unicode row is unchanged. The ASCII reader shrank from 115 to 29 + 64 bytecodes.

- **Legs:**
  - **A** `2ba9dbbdb`, branch `user/dain/bench-trino-plan-costs`: main plus `BenchmarkTrinoPlanCosts`, as EG-8 J3.
  - **C** `1d168979f6d01b147921f2437801c7322c666d9a`, branch `user/dain/bench-trino-plan-costs-c`: #273 `533cf90ee2074d77c6b67c215463f790380f0429` plus the same class.
  - C's src/main patch-id against A equals #273's against main (`8e0a029c80f2`).
- **Rows** (28), each as EG-8 J3 names it:
  1. **Witness** (4): `BenchmarkEverydayTrinoRegexp` `contains`, `count`, `extract`, `extractAll` with `-p workload=quotedField`.
  2. **Delimited capture** (8): `BenchmarkTrinoPlanCosts` `contains` and `extract`, `-p pattern=delimited`, inputs `short-ascii`, `long-ascii`, `short-mixed`, `long-mixed`.
  3. **Tail guard** (12): `BenchmarkTrinoPlanCosts` `contains` and `extract`, patterns `dotall-tail` and `line-tail`, inputs `long-mixed`, `short-mixed`, `short-ascii`. They check that the fix doesn't give back the reader remedy. Long mixed tails now pay one extra call per non-ASCII stretch.
  4. **Controls** (4): `BenchmarkTrinoRows.contains` english/low-selective, english/dotstar-low-selective and nginx/mid-common; `BenchmarkEverydayTrinoRegexp.contains` on `logLevel`.
- **Forks:** 28 × 96 = **2,688 forks, about $0.82**.
- **Predictions** (C/A against main):

  | Family | Rows | EG-8 #273 before the fix (r9g / r8i) | r9g | r8i | Gate / wrong if |
  |---|---|---|---|---|---|
  | quotedField | contains | 1.098 / 0.949 | **1.01–1.04** | **0.93–0.98** | **Gate:** above 1.03 on both hosts of a platform keeps #273 draft |
  | quotedField | count | 1.098 / 1.032 | **1.01–1.03** | **0.99–1.03** | **Gate:** as contains |
  | quotedField | extract, extractAll | 1.023–1.028 / 1.010–1.021 | 1.00–1.02 | 1.00–1.02 | **Gate:** above 1.03 on both hosts |
  | Delimited | short-ascii, long-ascii | 1.08–1.19 / 1.10–1.12 | **1.02–1.06** | **1.02–1.06** | Wrong if above 1.08 |
  | Delimited | short-mixed, long-mixed | 1.34–1.52 / 1.37–1.57 | 1.25–1.45 | 1.25–1.50 | Wrong if above EG-8 |
  | Dot-all tail | long-mixed | 50.7, 32.1 / 82.4, 53.3 | as EG-8 ±5% | as EG-8 ±5% | **Gate:** any tail cell more than 5% above EG-8 on both hosts |
  | Dot-all tail | short-mixed, short-ascii | 5.8, 4.2, 2.9, 2.3 / 6.3, 4.4, 3.2, 2.3 | EG-8 or a little lower | same | **Gate:** as above |
  | Line tail | long-mixed, short-mixed, short-ascii | 3.5, 3.4, 2.8, 2.4, 1.48, 1.37 / 6.3, 6.8, 3.1, 2.9, 1.48, 1.42 | EG-8 or a little lower | same | **Gate:** as above |
  | Controls | | 0.99–1.01 | 1.00 ±2% | 1.00 ±2% | **Gate:** above 1.02 on both hosts |

- **I am wrong if** quotedField contains or count stays above 1.06 on r9g. Then the reader is not the cost, and the next suspects are the plan's per-call setup and the attempt's size (C2 span 2976 / 3056 against A's 2824 / 3072).
- **The tails remain Dain's decision.** They are the price of reading every byte under rule (a). W-273-TRADEOFF.md gets the measured C column.
- **Cut order:** first the short-ascii tail cells, then the controls beyond logLevel, then the long-ascii delimited cells. Never cut the quotedField witness.

## D1C — quotedField perfasm, fixed leg (r8i, with J2)

The fixed leg, C, for D1. D1 ran A and B in the first round. Rows and report as EG-9 D1, with the diagnostic flags above: `contains` and `count`, `-p workload=quotedField`, 2 forks per op on one r8i host, about $0.02. Report C's run-reader loop form beside D1's A and B.

## D2 — perfasm diagnostic for #276 sherlock-ru (the first r8i host, after D1C)

**Question.** On x86, did #276's in-line budget comparison change the hot 64-byte masked pair-scan loop, and does the fixed #276 return it to main's?

i24 found that r8i runs `findPairMasked` (x86 with 64-byte vectors only), and r9g runs `findPair`. #276 wrote the budget comparison in line in both. The comparison runs only twice per 1.57 MB Russian input, so the 3% must come from code shape, not work. The fixed #276 puts main's out-of-line call back in `findPairMasked` only, so its bytecode equals main's. Evidence: `investigator-i24/` and `codex-engine-gaps-i24-276-r8i/` under `~/AgentScratch/regulator/user/dain/russian-literals/` on dain-1.

- **Legs:** A main `11b9291e1`, B #276 before the fix `a12d6c399` (branch `user/dain/bench-276-before`), C fixed #276 `e3ca23546f1175c353485a719bf5a258cf1bfe94`.
- **Row:** `BenchmarkLanguageBulk.execute`, engine `rust`, `curated--01-literal--sherlock-ru.klv` from eg8-kit `eg8-w6-workloads.tar.gz` (sha256 `fd616b69d8354d45832253057b11d9692450cb3d6f4372d13b50b536e6baa251`), expected 724.
- **Forks:** 2 per leg, interleaved A B C A B C, so 6 forks, about $0.03 with profiler overhead. Use the diagnostic flags above, with output `<out>/<leg>-sherlock-ru-<fork>.{xml,perfasm}`.
- **Report:** the hot region of compiled `findPairMasked` (or its inlined caller) on each leg:
  - the 64-byte compare-and-mask loop;
  - the unroll factor and loop header alignment;
  - the broadcasts (memory or register), spills and reloads;
  - whether `comparisonBudgetSpent` is a call (A and C) or in line (B);
  - cycles and instructions per op.

  Ship the raw files to S3.
- **Confirmed if** C's hot loop matches A's and B's differs. **If all three loops match,** the 3% is layout or alignment; say so.

## J3 — #276 fixed: the r8i regression

- **Legs:** A main `11b9291e1`, C fixed #276 `e3ca23546f1175c353485a719bf5a258cf1bfe94`, branch `user/dain/pair-scan-inline-budget`.
- **Rows** (10 cells), `BenchmarkLanguageBulk.execute`, engines `rust` and `re2`, files from eg8-kit `eg8-w6-workloads.tar.gz` (as above):
  - target `curated--01-literal--sherlock-ru` (724);
  - controls `curated--01-literal--sherlock-en` (513), `curated--01-literal--sherlock-zh` (30), `curated--02-literal-alternate--sherlock-ru` (899), `curated--02-literal-alternate--sherlock-casei-ru` (971).
- **Forks:** 10 cells × 96 = **960 forks, about $0.29** (allow $0.40).
- **Predictions** (C/A against main):

  | Rows | r9g | r8i | Gate / wrong if |
  |---|---|---|---|
  | 01-literal sherlock-ru, rust and re2 | **0.62–0.66** (EG-8 0.642) | **0.99–1.01** (EG-8 1.029) | **Gate:** r8i back to main (not above 1.02 on both hosts); r9g within 5% of EG-8 (not above 0.674). Either miss keeps #276 draft. |
  | Controls ×4, rust and re2 | 1.00 ±2% | 1.00 ±2% | **Gate:** above 1.02 on both hosts |

- **I am wrong if** r8i sherlock-ru stays above 1.02 while D2 shows C's masked loop matches A's. Then the cost is not in `findPairMasked`, and the next suspect is code layout from the `findPair` change. In that case, #276 becomes a trade-off the coordinator weighs: r9g 0.642 against r8i about 1.03.
- **Cut order:** the 02-literal-alternate controls first, then sherlock-zh. Never cut the target or sherlock-en.

## Legs

| Job | Leg | SHA | Branch |
|---|---|---|---|
| J2 | A | `2ba9dbbdbdbe0ef8569227fb3d3e543d032e8a67` | user/dain/bench-trino-plan-costs |
| J2, D1C | C | `1d168979f6d01b147921f2437801c7322c666d9a` | user/dain/bench-trino-plan-costs-c (#273 `533cf90ee2074d77c6b67c215463f790380f0429` plus BenchmarkTrinoPlanCosts) |
| D2, J3 | A | `11b9291e1c1cc00c18a2662b7588a272961d2ed3` | main |
| D2 | B | `a12d6c3996cc0e5bf436f26d95fd9279073bbb62` | user/dain/bench-276-before |
| D2, J3 | C | `e3ca23546f1175c353485a719bf5a258cf1bfe94` | user/dain/pair-scan-inline-budget (#276) |

Workloads: J2 and D1C use the repo's benchmark classes and need no files. D2 and J3 use eg8-kit `eg8-w6-workloads.tar.gz` (sha256 `fd616b69d8354d45832253057b11d9692450cb3d6f4372d13b50b536e6baa251`).
