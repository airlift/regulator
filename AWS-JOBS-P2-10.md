# AWS timing jobs, simplification sweep phase 2, round 10 (owners of round 9's unexplained moves)

Written 2026-11-20 PST on dain-1. **The predictions below were written before anything runs.** Nothing is timed on dain-1.

Round 9 timed the combined removal branch against main and passed its gate. Six steady moves had no prediction. The untimed diagnosis explained two of them by mechanism: Trino keyValue/quotedField got faster, and lh3lh3 email splits by platform. It narrowed three others to a suspect commit or a JIT effect, with no route change. This round finds the owners. Each cell compares two legs that differ by one commit or one JVM flag.

The coordinator approved cells A, B and C, with D as the first cut. Cap $0.40, inside the sweep hold.

## Method (as rounds 1–9)

- **Same-host A/B:** 3 forks per leg, interleaved fork by fork, legs in the order listed for each row.
- **JMH:** `-f 1 -wi 8 -w 1s -i 5 -r 1s` on `baseline/*` rows and `-wi 5` on `language-bulk/*` rows.
- **Mode:** native. Each platform runs only the rows marked for it.
- **Fork cap:** 15 minutes.
- **Report** as before:
  - the fork scores and medians;
  - each named ratio with its fork spread;
  - the calibration fields.
- **Answer checks:** every bulk fork's setup verification, as before.

## Legs: R10-LEGS.tsv

Every leg is a full SHA reachable from `user/dain/sweep-removals` head `94a6bd9a14672ac9484f18823246aa41fccba443`. None names a commit from before the force-push. R10-LEGS.tsv gives each leg's commit subject so you can check it.

| Leg | Commit | jvm_extra |
|---|---|---|
| main | `5fc520136` (origin/main) | – |
| removals | `94a6bd9a1` (head; src/main identical to round 9's `0fefcbc60`) | – |
| removals-inline | `94a6bd9a1` | `-XX:CompileCommand=inline,io.airlift.regulator.BitState::search` |
| main-dontinline | `5fc520136` | `-XX:CompileCommand=dontinline,io.airlift.regulator.BitState::search` |
| f3-parent | `3bcac08cf` (S3) | – |
| f3 | `d9de709f5` (F3) | – |
| f7-parent | `885452cf1` (F5) | – |
| f7 | `2a5b8bb2b` (F7; also P2's parent) | – |
| p2 | `22e0db206` (P2, mid-stack) | – |
| l9-parent | `eda089bdb` (E3F) | – |
| l9 | `96a0730ea` (L9) | – |

**jvm_extra** is appended to the fork JVM's options, as in BC-4. The runner confirms it from each fork's `# VM options:` line and reports any fork whose line lacks it.

`BitState::search` names only the static `search` overloads of `BitState`. The search loop is in `BitStateImpl`, a different class, so the flag forces or forbids exactly the inline the diagnosis named: `BitState.search` into `matchOnOrdinaryEngine`.

**Optional cheap evidence for cell A** (not timed, and not a ratio input): one extra fork per A leg with `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` added. Grep its output for the `io.airlift.regulator.BitState::search` lines under `matchOnOrdinaryEngine`, and report each line's verdict (`inline (hot)`, `force inline by CompileCommand`, `disallowed by CompileCommand`, `hot method too big`, `already compiled into a big method`). Skip it if it costs a separate host start; then the timing alone decides.

## Rows: R10-ROWS.tsv

| Cell | Item | Platform | Rows | Legs | Ratios |
|---|---|---|---|---|---|
| A | 1 urlHostPort | r9g | Trino `urlHostPort/extract/regular-engine` | main, removals, removals-inline, main-dontinline | removals / main; removals-inline / main; main-dontinline / main; removals-inline / removals |
| B | 2 replaceLambda | r9g | Trino `{isoDate,trinoPhone,keyValue}/replaceLambda`; control `isoDate/replace` | f3-parent, f3, f7-parent, f7 (plus main and removals on isoDate replaceLambda) | f3 / f3-parent; f7 / f7-parent; removals / main (isoDate) |
| C1 | 3 sherlock | r8i | Trino `sherlock-en`, Java and Rust `sherlock-zh` (`02-literal-alternate`) | main, p2, removals | p2 / main; removals / p2; removals / main |
| C2 | 6 overlapping-words | r9g and r8i | Trino `unicode/overlapping-words/english` | main, p2, removals, l9-parent, l9 | p2 / main; removals / p2; removals / main; l9 / l9-parent |
| D | 4 keyValue/quotedField | r9g and r8i | Trino `{keyValue,quotedField}/{extract,split,positionThird}` | f7, p2 | p2 / f7 |

**Size:**
- r9g: 39 row-legs, 117 forks.
- r8i: 26 row-legs, 78 forks.
- Total: 195 forks, plus up to 4 untimed evidence forks.
- Nine distinct commits to build per platform.

## Predictions (written before the run)

B/A above 1 is slower in the later leg. r9g / r8i where both run.

**A, item 1, the BitState inline race (r9g)**
- removals / main: **1.12–1.28** (round 9: 1.218, steady).
- If the race is the cause:
  - removals-inline / main: **0.80–1.05**. The branch does less work, so it may beat main once the inline is guaranteed.
  - main-dontinline / main: **1.12–1.30**.
  - removals-inline / removals: **0.65–0.90**.
- **Wrong if (race refuted):** removals-inline / removals is within ±5%, and main-dontinline / main is within ±5%. The PR then states the route change and calls the r9g cost unexplained.
- **Partial:** removals-inline recovers less than half the gap (removals-inline / main still above 1.10). Report it; the race is then one part.
- **Not reproduced:** removals / main below 1.08 on all three forks. Report it as host or fork mode, with every fork listed.

**B, item 2, replaceLambda (r9g)**
- My expected owner is **F7**. It removed a prelude from the replace-with-function method itself. F3 changed only the fixed-width findSpan, which every isoDate operation runs, and the other isoDate operations didn't move.
- f7 / f7-parent:
  - isoDate replaceLambda **1.08–1.20**;
  - trinoPhone and keyValue replaceLambda 1.00–1.10.
- f3 / f3-parent: ±3% on every row.
- removals / main (isoDate replaceLambda): **1.10–1.20**, reproducing round 9's 1.161.
- Control, isoDate replace: ±3% in both pairs.
- **Wrong if:**
  - F3 owns it: f3 / f3-parent ≥ 1.08 on isoDate replaceLambda.
  - **Whole-stack layout effect, no owner:** neither pair reaches 1.06 on isoDate replaceLambda while removals / main still reproduces at ≥ 1.10. The PR then calls it a layout effect with no owning commit.
  - The control moves more than ±5% in either pair. The owner then changes isoDate's path generally, not the function-taking replace.

**C1, item 3, sherlock (r8i)**
- removals / main: **1.04–1.09** on Trino sherlock-en and **1.03–1.06** on Java and Rust sherlock-zh, reproducing round 9.
- Every method these rows enter has identical bytecode on both halves. The threshold merge is in the late half but substitutes 64 for 64. So I predict **no owner**: the move splits between the halves or follows neither, each half ±3%.
- **Wrong if:**
  - One half carries ≥ 75% of the move on all three rows. The owner is then in that half; a further bisection needs a new decision.
  - **Whole-stack layout effect, no owner:** both halves are within ±2.5% while removals / main reproduces at ≥ 1.04. This is the predicted outcome, not a surprise.
  - **Host noise:** removals / main is within ±2% on all three rows, so round 9's move did not reproduce.

**C2, item 6, overlapping-words english (r9g and r8i)**
- removals / main: **1.03–1.07** on both platforms (round 9: 1.051 / 1.059).
- L9 is the only route difference the census found: its two per-matcher checks are gone. I predict l9 / l9-parent **1.02–1.06**, the most likely single owner, and removals / p2 carrying most of the move.
- **Wrong if:**
  - **Whole-stack layout effect, no owner:** l9 / l9-parent is within ±2% and neither half reaches 1.03, while removals / main reproduces.
  - The early half (p2 / main) carries ≥ 75% of the move. That points at the fixed-width or literal removals, which the census shows don't reach this row; a JIT effect is then the only reading.
  - removals / main is within ±2% on both platforms. The move did not reproduce.

**D, item 4, P2's share of the keyValue/quotedField gain (r9g and r8i)**
- p2 / f7: **0.95–0.99** on extract, split and positionThird. P2 removed the folded-executor selector the census counted 12–18 times per pass. L9, later in the stack, removed the rest of the per-call checks.
- **Wrong if:** p2 / f7 is within ±1% (or above) on all six rows of a platform. P2 then isn't a contributor there, and the PR bullet credits L9 and F4 instead.

## Budget, cap and cut order

- **Time:** at round 9's rate (about 0.0034 host-hours per fork, overhead included), about 0.7 host-hours, plus nine leg builds per platform.
- **Estimate:** $0.10–0.25. **Guard cap: $0.40.**
- **Cumulative:** $4.243 so far, of the $25 cap ($30 hold).

If projected spend passes $0.30, or Spot capacity runs short, cut in this order, first item first:
1. `cut_tier=1`: cell D on **r8i**.
2. `cut_tier=2`: cell D on r9g.
3. `cut_tier=3`: cell B's control row.
4. `cut_tier=4`: cell C2 on r8i.

**Never cut** `cut_tier=never`: cell A, cell B's replaceLambda rows, cell C1, and cell C2 on r9g. Report any cut with the spend at the moment of the cut.

## Kit

Branch `user/dain/sweep/kit-r10` holds this file, `R10-ROWS.tsv` and `R10-LEGS.tsv`, as an orphan commit.
