# AWS timing jobs, round UAP-5: PR #279's timed round (K1, U1b, U2)

Written 2026-11-18 PST on dain-1. **I wrote every prediction, grading rule and acceptance line below before anything ran.**

For the coordinator to run on AWS Spot inside the uap-core hold (about **$0.72** left). Nothing runs on dain-1 or the Mac.

## Why

UAP-4 passed K1, the out-of-line many-match scanner, on counters and shape on both platforms. #279 now carries:
- U1b, many-match states keyed by their own matches;
- U2, FilteredRe2's one-call atom search;
- K1;
- the round-1 review fixes: a FilteredRe2 build-budget fix and wording.

This round times #279's head against main on the rows its changes touch, and settles three open items:
- **U1b and K1:** the r8i matching-loop regression that UAP-1 tripped (+3.7%) is gone and turned into a win, as UAP-4's counters predict. Timed forks, not D-cells, decide.
- **I27, U2's mode share:** the coordinator's 2026-11-18 rule, below.
- **I29, K1's allocation:** each collecting search now allocates one `int[65]` (280 B). This round adds short-text rows and `-prof gc` bytes per op, so the cost is measured where it is proportionally largest: short texts and small sets. The scratch buffer cannot reuse existing state. `DfaInstance` is shared across threads, no per-thread search state exists, and `matchingPatternIds` already allocates a `SparseSet` and a result array per call.

## Legs (both contain main `bdf26dc33` and #281's final bench)

| Leg | Commit | Branch | What it is |
|---|---|---|---|
| A5 | `b74cd81a5` | `user/dain/uap-core-a5` | main + #281 (`cbb3ba778`, `671b55f61`) + the match-dense row (`081582448`'s diff, with its smoke test) |
| H5 | `39210e764` | `user/dain/uap-core-h5` | #279's head `28734c05e` + #281's two commits (`62c593a5d`, `67427ebc3`) + the one-call parse rows (`c46ea3278`'s rows ported onto #281's final bench, with an atom search of the atom set's budget) |

Checked on dain-1 (no timing):
- **Bench code:** identical except for the one-call rows, which only H5 has (main has no one-call API). `git diff A5 H5` over the `Benchmark*` classes and `TestingUapCoreParser` shows only those rows and their helpers.
- **Production code:** `git diff A5 H5 -- src/main` is byte-identical to `git diff bdf26dc33 28734c05e -- src/main`.
- **Tests:** `TestBenchmarkUapCore` and `TestBaselinePublicApiBenchmarks` pass on both legs, with the module checks on. The one-call rows' checksums equal their twins' on all four corpora.
- **Benchmarks:** `BenchmarkList` (`./mvnw test-compile`) holds every row below on both legs, and the one-call rows on H5.
- **`-p sourceLength=64`:** valid. At 16 patterns the text is 52 dots then `prefix15-123`, and the search returns `[15]`.

**PR #279 head for this round: `28734c05e`** (CI green on the previous head `64fd5a5ab`; the delta is the polish fixes: comments, docs, tests, and FilteredRe2's over-budget message).

## Conventions

The same as UAP-2 (`AWS-JOBS-UAP-2.md`) except where a job says otherwise:
- Temurin 25.0.4+7, native mode only (`--enable-native-access=ALL-UNNAMED`), UAP-1's JVM flags.
- Each class's JMH defaults (warmup 10 × 1 s, measurement 7 × 1 s).
- 2 hosts per platform.
- Legs interleave fork by fork (ABAB) on the same host, with jars at paths of equal length.
- For each fork, record its score (the mean of its 7 measurement iterations).
- **`-prof gc` on every collecting row** (marked † below). Record `gc.alloc.rate.norm` (bytes per op) per fork. It adds no fork.
- **No D-cell is needed this round.** UAP-4 already proved K1's r8i shape: state in a register in 14 of 14 forks.

### Shape rule (carried forward for any D-cell this round triggers)

- **Fail:** the rule catches the store-to-load chain on the transition index. V is the value the transitions-array load returns (after any `and 0xbfffffff`) that becomes the next byte's index addend, including V between unrolled copies. A fork fails if V is stored to `[rsp+k]` (or `[sp, #k]` on aarch64) and that slot is read back **into the next index**.
- **Pass:**
  - a stack read whose slot holds only values not produced in the iteration (lengths, array references, `end`);
  - a store that writes back an unchanged value loaded from the same slot;
  - **a stored copy read back only by a comparison and never feeding the next index** (K1's dedupe `cmp`, as the coordinator ruled on UAP-4).

  Count invariant reloads per byte and report them.

## J1: #279 against main, both platforms, 12 forks per leg per host

Every row is `H5/A5`, with legs interleaved.

| Group | Rows | Count |
|---|---|---|
| Guards, matching | `BenchmarkPatternCollections.matchingSetPatternIds`† and `.matchingSetPatternIdsMatchDense`†, `sourceLength=32768`, `patternCount` 16 and 128 | 4 |
| Guard, generic search | `BenchmarkPatternCollections.matchesAny`, `sourceLength=32768`, `patternCount=16` (K1 doesn't reach it: a control) | 1 |
| Short rows (I29) | `matchingSetPatternIds`†, `patternCount=16`, `sourceLength` **64** (JMH `-p` override) and 1024; `matchingSetPatternIdsMatchDense`†, `patternCount=16`, `sourceLength=1024` | 3 |
| UA controls | `BenchmarkUapCore.browserAtomSetMatchingPatternIds`† and `osAtomSetMatchingPatternIds`† (short strings, large atom sets), `parseBrowser` and `parseOs`; all on `corpus=UA_TESTS` at `ruleMemoryMiB=32 atomSetMemoryMiB=32` | 4 |

**12 rows × 2 legs × 12 forks × 4 hosts = 1,152 forks.**

### Predictions (written before the run)

| Row(s) | r8i H5/A5 | r9g H5/A5 | Wrong if | Basis |
|---|---|---|---|---|
| `matchingSetPatternIds` 16 @32768 | **0.80–0.90** | **0.87–0.97** | above 1.00 on both hosts of a platform | UAP-4 counters: K1 D1 0.848 (r8i), 0.913 (r9g) |
| `matchingSetPatternIds` 128 @32768 | 0.80–0.95 | 0.87–1.00 | above 1.03 on both hosts | the same loop; more states |
| `matchingSetPatternIdsMatchDense` 16 @32768 | **0.62–0.85** | **0.70–0.88** | above 1.00 on both hosts | UAP-4 K1 D2 0.672 against A′'s median, which included spilled forks. Timed A5 forks may spill less often, hence the wide upper bound |
| `matchingSetPatternIdsMatchDense` 128 @32768 | 0.62–0.90 | 0.70–0.95 | above 1.03 on both hosts | same, untested at 128 |
| `matchesAny` 16 @32768 | 0.98–1.02 | 0.98–1.02 | outside 0.97–1.03 on both hosts | K1 doesn't touch the generic search |
| **Short: `matchingSetPatternIds` 16 @64** | **0.95–1.08** | **0.95–1.08** | above 1.10 on both hosts | ~64 bytes of scan against the per-call setup; +280 B zeroed allocation and one out-of-line call per op, against a faster loop |
| Short: `matchingSetPatternIds` 16 @1024 | 0.85–0.98 | 0.90–1.00 | above 1.03 on both hosts | the loop gain dominates |
| Short: `matchingSetPatternIdsMatchDense` 16 @1024 | 0.65–0.90 | 0.72–0.95 | above 1.03 on both hosts | as at 32768, with a larger setup share |
| UA atom sets (UA_TESTS) | 0.90–1.02 | 0.90–1.02 | above 1.03 on both hosts | UAP-1 C/A 0.943–1.011; K1 helps the loop, the allocation costs a little per string |
| UA parses (UA_TESTS) | 0.95–1.02 | 0.95–1.02 | above 1.03 on both hosts | the atom set is a small share of a parse |

**Bytes per op (`gc.alloc.rate.norm`):**
- **`matchingSetPatternIds`:** H5 − A5 = **+280 ± 8 B per op** at every size (one collecting call per op). At 16 patterns, A5 is about 184 B and H5 about 464 B, measured untimed by the adversarial review on dain-1.
- **UA atom-set rows:** H5 − A5 = 280 B × the number of strings searched per op.
- **Wrong if:** the difference is not a whole multiple of about 280 B per collecting call, or H5 allocates anywhere the scanner doesn't reach (`matchesAny`).

## J2: U2's one-call vs two-step, r9g, 24 forks per row per host (leg H5 only)

The same three pairs as UAP-2, now on H5 (#281's final bench), at `-p ruleMemoryMiB=32 -p atomSetMemoryMiB=32 -f 24`:

| Pair (one-call / two-step) | Corpus |
|---|---|
| `parseOsOneCall` / `parseOs` | CRAWLERS |
| `parseBrowserOneCall` / `parseBrowser` | OS_TESTS |
| `parseBrowserOneCall` / `parseBrowser` | CRAWLERS |

3 pairs × 2 rows × 24 forks × 2 r9g hosts = **288 forks**. Interleave each pair's rows fork by fork.

### Per-pair grading (as in UAP-2, written before the run)

Per host:
- the median ratio and the fastest-fork ratio;
- modes: pool the host's 48 fork scores for the pair and split where neighbours differ by more than 8% of the lower;
- the mode share per row, and per-mode ratios where each row has at least 3 forks.

The verdicts are Pass, Mode artifact and Real difference, exactly as `AWS-JOBS-UAP-2.md` defines them.

### Mode-share rule (the coordinator's 2026-11-18 ruling; written before the run)

For each pair:
1. **Slow forks.** On each host separately, the fastest mode is the mode holding the host's lowest fork score for the pair. A fork is **slow** if it lies outside its host's fastest mode. Modes are found per host because host-to-host offsets of a few percent would otherwise create false modes.
2. **Pool both hosts:** count slow and not-slow forks for the one-call row and for the two-step row (up to 48 each).
3. **Test:** Fisher's exact test, one-sided, alternative "one-call's share of slow forks is greater than two-step's". Report both shares and p.

**Fail U2's mode share if p < 0.05 on two or more of the three pairs.** One pair below 0.05 is reported, not failed.

### Predictions

| Pair | Verdict | Fastest-fork ratio | Mode share | Wrong if |
|---|---|---|---|---|
| all three | pass, or mode artifact | 0.98–1.02 on both hosts | p ≥ 0.05 on at least two pairs: the API runs the same work behind one more call boundary | any real difference, or the mode-share rule fails |

## Acceptance for #279 (written before the run)

#279 passes UAP-5 if all of these hold:
1. **No regressions on the guard and control rows.** No guard, UA control, or `@1024` short row is above **1.03** on both hosts of either platform.
2. **The matching win holds.** `matchingSetPatternIds` 16 @32768 is at or below 1.00 on both hosts of r8i. This closes UAP-1's r8i trip.
3. **I29 short row.** `matchingSetPatternIds` 16 @64 is at or below **1.03** on both hosts of each platform, and bytes per op are as predicted.
   - If only the @64 row fails, #279 does not pass. I then propose the I29 remedy (a smaller buffer, or no buffer below a text-length threshold) with its own D-cell, and leave the rest of the verdict standing.
4. **U2.** J2: no "real difference" on any pair, and the mode-share rule passes.

If everything passes, #279 is ready for its final ready-check and leaves draft.

## Estimate

UAP-1 cost $0.00051 per fork across 4,100 forks. UAP-2 cost $0.291 in all.

| Job | Forks | At $0.0004 per fork |
|---|---|---|
| J1: 12 rows × 2 legs × 12 × 4 hosts | 1,152 | $0.46 |
| J2: 3 pairs × 2 rows × 24 × 2 r9g hosts | 288 | $0.12 |
| **Total** | **1,440** | **≈ $0.58** |

**Guard cap $0.68**, inside the $0.72 left. If the guard nears the cap, drop in this order:
1. the UA parse controls;
2. the `matchingSetPatternIds` 128 @32768 row;
3. the `matchingSetPatternIdsMatchDense` 128 @32768 row.

The 16-pattern guards, the short rows, `matchesAny`, the UA atom sets, and J2 are never dropped.

Priority: J1's short rows and 16-pattern guards first, then J2, then the rest.
