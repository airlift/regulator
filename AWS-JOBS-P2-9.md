# AWS timing jobs, simplification sweep phase 2, round 9 (confirmation of the combined removal branch)

Written 2026-11-20 PST on dain-1. **The predictions below were written before anything runs.** Nothing is timed on dain-1.

Dain approved a removal set from PRICED-OPTIONS.md (sha256 `6d279730…0143`). It is built as one branch, `user/dain/sweep-removals`, with 18 commits on main `5fc520136`:
- §1 removals: Q3, Q4, L4F, S8, F4, S3, F3, F5, F7, P2.
- §2 removals: L8, E3F, L9, K2, K1V.
- `Prog.possibleMatchRange`.
- Two cleanups: one constant for the 64-byte short threshold, and the always-on productivity switch.

The size is src/main +864/−6,799 (−5,935 net) and src/test +504/−3,761. The branch passed the answer gate in safe and native mode (6,748 tests) and `verify`.

This round times the combined head against its main base on every row those removals were priced on, plus the three flip rows, ruff-noqa and controls. It checks two things:
- that the individual prices add up as expected;
- that the removals don't interact.

## Method (as rounds 1–8)

- **Same-host A/B:** 3 forks per leg, interleaved ABAB fork by fork.
- **JMH:** `-f 1 -wi 5 -w 1s -i 5 -r 1s`, with `-wi 8` on `baseline/*` rows.
- **Mode:** native. The 39 paired-table rows (sets `C8-paired-tables`) also run in safe mode, on r9g only, because E3F was aarch64-only.
- **Platforms:** r9g and r8i, r9g first.
- **Rows:** published 2.1 report row ids. The flip check uses the published 2.1 comparator ratios, as before.
- **Fork cap:** 15 minutes.
- **Report** as before:
  - the 3 fork scores and the median;
  - B/A with the fork spread;
  - the calibration fields;
  - the flip check;
  - every answer check.

## Legs (full SHAs; pushed). Also in R9-LEGS.tsv

| Leg | Full SHA | Branch |
|---|---|---|
| main (base) | `5fc520136a94bc3d5b5751c6888482c848236f08` | `main` |
| removals | `0fefcbc60e03f00b91bcb5de6a9403f8ea0ba293` | `user/dain/sweep-removals` |

B/A = median(removals) / median(main) on the same host; above 1 means slower without the removed components.

## Rows: R9-ROWS.tsv (562 row-languages)

The rows are the union of the sweep's pricing sets for the removed components, plus the controls those sets already carry:

| Set | Rows | For |
|---|---:|---|
| C1-required-literal | 64 | Q3, Q4; **Q2 stays and shared hooks with Q4**, so its leipzig bounded-strings-ending-z rows are a key check |
| C1b-q4-beside | 8 | Q4 |
| C2-literal-variants | 53 | L4F (ruff-noqa ×4, wild ruff noqa), L8 (lh3lh3 email ×4), L9 specialized rows; L4S/L6M/L6O/L7 controls |
| C6b-s8, C6-start-narrow | 13 + 11 | S8 (awyer-inn, context, shing, Trino name-alt3-casei), S3; S11 control |
| C7-fixed-width | 113 | F4, F3, F5, F7; F2 control |
| C8-paired-tables | 39 | E3F (native + r9g safe), incl. mariomka uri Rust |
| C9-class-run | 99 | K2 (contiguous-greek), K1V (i1095 ascii-search, letters-ru); K4 control |
| C3-e4-backward-object-scan | 46 | L9 (clickbenchHost replace, urlHostPort extract); E4 controls |
| C4-scan-plans | 90 | P2 (zero rows) and the plan code the P2 commit touched (+316/−1,098) |
| C5-matcher-alternation-split | 124 | the threshold merge (literal alternation filter and exact-literal prefix scan gates) |
| added | 1 | Trino `language-bulk/wild/dot-star-capture/rust-src-tools/execute` (L9, diagnostic) |

The sets overlap, so the union is 562 row-languages: 448 everyday, 58 synthetic, 55 constructed and 1 diagnostic.

The `cut_tier` column drives the cut order below. `never` marks the 341 rows that are cut last or never: every removal's target rows, the flip rows, ruff-noqa, Q2 and the controls.

## Predictions (written before the run)

B/A, r9g / r8i. A combined head should show each component's own price; the components touch disjoint rows.

| Rows | Predicted | From |
|---|---|---|
| leipzig awyer-inn ×4 | **0.76–0.82 / 0.78–0.82** (faster) | S8, round 8: 0.772–0.780 / 0.791–0.799 |
| sherlock name-whitespace ×4 | **0.84–0.91 / 0.80–0.89** (faster) | Q4, round 2 |
| both-slashes RE2/Rust | **0.68–0.75 / 0.70–0.76** (faster) | Q4 |
| Trino rust-src-tools | **0.18–0.25 / 0.19–0.25** (faster) | L9 |
| Trino clickbenchHost replace | **0.79–0.86 / 0.78–0.85** | L9 |
| Trino urlHostPort extract (flip row) | r9g **0.80–1.28**, bimodal; r8i 0.83–0.89 | L9, round 8 |
| sherlock word-ending-n ×4 | **1.06–1.13** r9g native and safe; r8i ±3% | E3F (aarch64-only) |
| mariomka uri Rust (flip row) | r9g **1.00–1.05**; r8i ±3% | E3F |
| other paired rows (35) | 0.96–1.04 r9g; ±3% r8i | E3F |
| lh3lh3 email ×4 | **1.03–1.07 / 1.00–1.06** | L8 |
| unicode codepoints contiguous-greek ×4 (incl. the RE2 flip row) | **1.8–2.2 / 1.35–1.65** | K2 |
| i1095 ascii-search ×4 (constructed) | **1.18–1.32 / 2.1–2.6** | K1V |
| letters-ru | ±3% / 1.00–1.05 | K1V |
| ruff-noqa ×4, wild ruff noqa | 0.96–1.06 on both. The headline projection's worst rebar-curated move was ruff-noqa Java r8i, 5.3% | L4F |
| leipzig ing ×4 | 0.96–1.05 | Q3 |
| **leipzig bounded-strings-ending-z ×4 (Q2 kept)** | ±5% | Q2 must still reject; Q4's removal shares its hooks |
| fixed-width set (C7) | 0.94–1.06 | F4; F3/F5/F7 ran on no row |
| scan-plan set (C4) | ±3% | P2 ran on no row |
| matcher-alternation set (C5) | ±3% | the threshold merge changes no route |
| everything else | ±3% | controls |

**Wrong if:**
- **Any answer check fails** (any bulk or LIKE fork's setup verification). That would be an answer change: stop and report.
- **bounded-strings-ending-z is above 1.10** on any language. That would mean Q2's long-window check was broken by removing Q4.
- **A row predicted ±3% moves more than ±6%** on all three forks of either platform.
- **A win → loss flip appears beyond the three named rows** (mariomka uri Rust r9g, contiguous-greek RE2 r8i, Trino urlHostPort r9g).
- **A faster-without row is slower than its range's top** (awyer-inn > 0.85, name-whitespace > 0.93, rust-src-tools > 0.30). That would mean the removals interact.
- **K2/K1V/E3F costs exceed their round-3/round-8 ranges by more than 10%.**

## Size and cost

| | r9g | r8i |
|---|---:|---:|
| Native row-languages | 562 | 562 |
| Safe row-languages (r9g) | 39 | — |
| Forks (× 2 legs × 3) | 3,606 | 3,372 |

- **Time:** at round 8's measured rate (about 0.0034 host-hours per fork, overhead included), about **12.4 host-hours on r9g and 11.6 on r8i**, about 24 in all. With 4 hosts per platform, that is about 3–3.5 hours of wall time.
- **Cost:** at round 8's $0.046 per host-hour, **$1.00–1.30**.
- **Guard cap: $1.75.**
- **Cumulative:** about $4.2–4.5 of the $25 cap ($30 hold).

## Budget and cut order

If projected spend passes $1.50, or Spot capacity runs short, drop rows in this order, first item first:
1. `cut_tier=1` (C5 matcher-alternation rows, 94) on **r8i**, then on r9g.
2. `cut_tier=2` (C4 scan-plan rows, 89) on **r8i**, then on r9g.
3. `cut_tier=3` (C7 constructed fixed-width rows, 38) on **r8i**.
4. The r9g safe-mode paired rows, except word-ending-n ×4 and mariomka uri Rust.

**Never cut** `cut_tier=never` (341 rows) on either platform: every removal's target rows, the three flip rows, ruff-noqa, Q2's rows and the controls. Report any cut with the spend at the moment of the cut.

## Kit

Branch `user/dain/sweep/kit-r9` holds this file, `R9-ROWS.tsv` and `R9-LEGS.tsv`, as an orphan commit.
