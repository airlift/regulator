# AWS timing jobs, simplification sweep phase 2, round 11 (bisect the r8i sherlock moves)

Written 2026-11-20 PST on dain-1. **The predictions below were written before anything runs.** Nothing is timed on dain-1.

Round 10 split round 9's r8i sherlock moves by half:
- Trino `sherlock-en` moved in the early half, main..p2: **1.069**.
- Java `sherlock-zh` moved in the late half, p2..removals: **1.049**.

This round finds the commit in each half. The coordinator approved r8i only, with a cap of $0.20.

## Method (as rounds 1–10, except the leg order)

- **Straight chain, not ABAB pairs.** All legs of a row run on one host. The forks rotate through the chain in leg order: leg 0, 1, …, N, then again, three rotations. That gives 3 forks per leg with every leg interleaved.
- **Ratios:** each step's ratio is median(leg) / median(its parent, the previous leg). The chain's end / start reproduces round 10's half ratio.
- **JMH:** `-f 1 -wi 5 -w 1s -i 5 -r 1s` (`language-bulk/*` rows). Native mode, r8i only. Fork cap 15 minutes.
- **Report:** fork scores and medians, each step ratio with its fork spread, the end/start ratio, and the calibration fields.
- **Answer checks:** each bulk fork's setup verification, as before.

## Legs: R11-LEGS.tsv

Every leg is a full SHA reachable from `user/dain/sweep-removals` head `57009ca226d75b17c706a8fd2399e101544cd503`, the head after the review-repair fold (pushed). The fold touches only tests and docs: `git diff 94a6bd9a1 <head> -- src/main` is empty. So each leg's src/main is identical to the same-named commit of round 10's stack. R11-LEGS.tsv gives each SHA, its subject and the round 10 SHA whose src/main it matches.

- **Early chain** (Trino sherlock-en): main, q3, q4, l4f, s8, f4, s3, f3, f5, f7, p2. That is 11 legs and 10 steps.
- **Late chain** (Java sherlock-zh): p2, l8, e3f, l9, k2, k1v, pmr, c64, flag. That is 9 legs and 8 steps.
- `flag` is the head. `c64` is the 64-byte threshold merge. `pmr` is the possibleMatchRange removal.

## Rows: R11-ROWS.tsv

| Cell | Row | Language | Chain |
|---|---|---|---|
| E | `language-bulk/curated/02-literal-alternate/sherlock-en/execute` | trino | early |
| L | `language-bulk/curated/02-literal-alternate/sherlock-zh/execute` | java | late |

**Size:** 20 legs × 3 forks = 60 forks. 19 distinct commits to build.

## Predictions (written before the run)

B/A above 1 means slower in the later leg.

**E, Trino sherlock-en, early chain**
- End/start (p2 / main): **1.04–1.09**, reproducing round 10's 1.069.
- Round 9's untimed diagnosis found no changed work on this row and identical bytecode in every method it enters. So I expect **no single owner**: the move spreads over two or more steps, each under 1.03. That reading is a layout or JIT effect.
- If one commit owns it, the likeliest is **P2**, which rewrote shared plan and Trino compiled-pattern code, then **S3**, which removed the prefix literal-set code from the prefix scanner.
- **Wrong if:**
  - one step carries ≥ 75% of the move (step ratio ≥ 1.05) and its spread clears its neighbours'. That commit owns it; an untimed look at why follows.
  - the end/start is within ±2%. The move did not reproduce, so round 10's half split was host noise.

**L, Java sherlock-zh, late chain**
- End/start (removals / p2): **1.03–1.07**, reproducing round 10's 1.049.
- I again expect **no single owner**, each step under 1.03. If one commit owns it, the likeliest is **L8**, whose removal changed find-route selection in `CompiledPattern` that every find passes through, then **L9** (its per-matcher checks are gone), then the threshold merge (no route change by construction).
- **E3F** is aarch64-only by construction, and on r8i its step should be within ±2%. A move there would be layout.
- **Wrong if:**
  - one step ≥ 1.035 carries ≥ 75% of the move. That commit owns it.
  - the end/start is within ±2%. The move did not reproduce.

## Budget and cut order

- **Time:** at round 10's rate (about 0.0034 host-hours per fork), about 0.2 host-hours, plus 19 leg builds.
- **Estimate:** $0.03–0.10. **Guard cap: $0.20.**
- **Cumulative:** $4.280 so far, of the $25 cap ($30 hold).
- **No cuts:** both rows are `cut_tier=never`. If Spot capacity runs short, run cell E first, then cell L.

## Kit

Branch `user/dain/sweep/kit-r11` holds this file, `R11-ROWS.tsv` and `R11-LEGS.tsv`, as an orphan commit.
