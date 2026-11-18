# AWS timing jobs, round EG-20: J2', #273 with its run-reader fixes against main

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.** This retimes EG-10 J2's full row set on #273's current head.

**Why this round.**
- In EG-10 J2, #273 (leg C) failed on r9g:
  - quotedField contains 1.121 and count 1.132;
  - delimited short-ascii extract 1.119;
  - the short-mixed tails 5–6.5% over EG-8;
  - every delimited-mixed cell over EG-8.
- Since then (i27, EG-12 D6, EG-16 D9, i30, EG-18 D10), commit 5 was reworked:
  - **No rescan:** a delimited capture reads on from the byte scan's stop.
  - **A cheap non-ASCII tail:** a stop at non-ASCII text in the input's last 16 bytes takes one inlined vector check of that block, not the walk by character.
  - **Profile-independent inlining:** every stop goes through that check, which returns an ASCII stop at once. The check's profile therefore matures with the stop scan's, and C2 inlines it in every compile (EG-18: 24/24, both platforms). Before, it raced: out of line in 2 of 8 r9g compiles, at +6–7%.
  - **The delimiter is masked as ASCII,** so C2 folds the stop scan's needle and bound.
  - Commit 4 reads mixed-script runs a block at a time.
- **EG-18's cycles against main** (perfnorm, diagnostic):

  | Row | r9g | r8i |
  |---|---|---|
  | quotedField contains | 0.978 | 0.915 |
  | quotedField count | 0.961 | 0.914 |
  | delimited short-ascii extract | 1.028 | 1.060 |

- **Known costs** the PR body will state (the coordinator accepted them after EG-18):
  - On r8i, the quotedField contains and count run loops keep the run position on the stack, as main's do.
  - r8i delimited extract costs about 36 more instructions per row than main, in `matchPartial` around the inlined run.
- **A separate main PR, not part of this leg:** CodeRabbit's work-budget finding on #273 (failed run minimums are not charged to the plan budget) also exists on main in every language. It becomes its own PR against main and is not in this leg.

**Budget.** 28 rows × 96 = **2,688 forks**, about $0.87 at $0.000323 per fork. With host start-up and the guard margin, **ask $1.10**.

**Conventions,** as EG-10 J2:
- **12 forks per leg per host,** on **2 hosts per platform**, **r9g** and **r8i**. Legs interleave fork by fork (ABAB), and both legs of a row run on the same host.
- **Native** mode only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- The class's JMH defaults, with the legs' jars at paths of equal length.
- **Report** per row and platform: the median Z/A across both hosts, with the per-host values. Flag (⚑) any host pair that disagrees by more than 5%.
- **Workloads:** the repo's benchmark classes; no files are needed.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| A | `2ba9dbbdbdbe0ef8569227fb3d3e543d032e8a67` | `user/dain/bench-trino-plan-costs` (main + BenchmarkTrinoPlanCosts, as EG-10 J2 and EG-18) |
| Z | `827ca53b32e521f2cae765d622d446a6fc66adb3` | `user/dain/bench-273-z` (EG-18's Z) |

- Z's code equals #273's current head `23bd9b642cd643fa52be07337060cb2b8c181542`: their src/main differs only in comments (polish of two doc comments). The head also differs from Z's base `774e9b71d` in tests, commit messages and one docs paragraph.
- **No compile-log forks.** EG-18 logged every C2 compile of these exact jars on both platforms, and the check was inlined in 24/24. If #273's code changes before this round's first timed fork, the manager tells the runner and the coordinator.

## Rows (28), named as EG-10 J2
1. **Witness** (4): `BenchmarkEverydayTrinoRegexp` `contains`, `count`, `extract`, `extractAll` with `-p workload=quotedField`.
2. **Delimited capture** (8): `BenchmarkTrinoPlanCosts` `contains` and `extract`, `-p pattern=delimited`, inputs `short-ascii`, `long-ascii`, `short-mixed`, `long-mixed`.
3. **Tail guard** (12): `BenchmarkTrinoPlanCosts` `contains` and `extract`, patterns `dotall-tail` and `line-tail`, inputs `long-mixed`, `short-mixed`, `short-ascii`.
4. **Controls** (4): `BenchmarkTrinoRows.contains` english/low-selective, english/dotstar-low-selective and nginx/mid-common; `BenchmarkEverydayTrinoRegexp.contains` on `logLevel`.

## Predictions (Z/A; above 1 is slower)

| Family | Rows | EG-10 C (r9g / r8i) | r9g | r8i | Gate / wrong if |
|---|---|---|---|---|---|
| quotedField | contains | 1.121 / 0.972 | **0.96–1.01** | **0.90–0.95** | **Gate:** above 1.03 on both hosts of a platform keeps #273 draft |
| quotedField | count | 1.132 / 0.955 | **0.95–1.00** | **0.90–0.95** | **Gate:** as contains |
| quotedField | extract, extractAll | — (EG-8 1.023–1.028 / 1.010–1.021) | 0.98–1.02 | 0.97–1.02 | **Gate:** above 1.03 on both hosts |
| Delimited | short-ascii extract | 1.119 / — | **1.01–1.05** | **1.04–1.08** | Wrong if above 1.08 on r9g |
| Delimited | short-ascii contains | — | 1.00–1.04 | 1.02–1.06 | Wrong if above 1.08 |
| Delimited | long-ascii contains, extract | — (EG-8 1.08–1.19 / 1.10–1.12) | 0.98–1.04 | 0.99–1.05 | Wrong if above 1.08 |
| Delimited | short-mixed contains, extract | over EG-8 | contains 1.25–1.35, extract 1.35–1.45 | contains 1.38–1.48, extract 1.30–1.40 | **Wrong if above EG-8** (1.34–1.52 / 1.37–1.57) |
| Delimited | long-mixed contains, extract | over EG-8 | below EG-8 | below EG-8 | **Wrong if above EG-8** |
| Dot-all tail | long-mixed contains, extract | — (EG-8 50.7, 32.1 / 82.4, 53.3) | EG-8 or lower | EG-8 or lower | **Gate:** any tail cell more than 5% above EG-8 on both hosts |
| Dot-all tail | short-mixed, short-ascii | 5–6.5% over EG-8 (short-mixed) | at or below EG-8 (5.8, 4.2, 2.9, 2.3 r9g) | at or below EG-8 (6.3, 4.4, 3.2, 2.3) | **Gate:** as above |
| Line tail | long-mixed, short-mixed, short-ascii | 5–6.5% over EG-8 (short-mixed) | at or below EG-8 (3.5, 3.4, 2.8, 2.4, 1.48, 1.37) | at or below EG-8 (6.3, 6.8, 3.1, 2.9, 1.48, 1.42) | **Gate:** as above |
| Controls | 4 rows | 0.99–1.01 | 1.00 ±2% | 1.00 ±2% | **Gate:** above 1.02 on both hosts |

**Where the predictions come from.**
- **quotedField and short-ascii extract:** EG-18's perfnorm cycles. JMH time should track cycles within a few percent.
- **Line-tail short-mixed contains:** EG-18 measured Z/A cycles of 2.72 (r9g) and 2.97 (r8i), below EG-8's 2.8 and 3.1.
- **The other mixed rows:** EG-18 measured Z/N within 2.6% of N, and N came from commit 4's block-at-a-time reader, which EG-10 C lacked.
- **Delimited long-ascii:** the vector loop dominates long ASCII runs, so the per-run setup matters little.

**I am wrong if:**
- **quotedField contains or count lands above 1.03 on r9g.** EG-18's cycles said 0.96–0.98, so timing and cycles would disagree. First check the r9g `containsOperation` compile size (EG-18: 2184 B slow, 2288 B fast, in every leg); a run with more slow-compile forks in Z than in A would explain it.
- **Any tail cell lands above EG-8.** Then commit 5's every-stop check costs the long mixed tails, and its share must be found before ready.
- **r8i delimited short-ascii extract lands above 1.08.** Then the known `matchPartial` excess is larger in time than in EG-18's cycles.

**Cut order** if the guard nears the cap: first the short-ascii tail cells, then the controls beyond logLevel, then the long-ascii delimited cells. Never cut the quotedField witness or the delimited short-ascii and short-mixed cells.
