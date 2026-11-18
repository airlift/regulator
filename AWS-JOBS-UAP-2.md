# AWS timing jobs, round UAP-2: U1b's fixed head, and the J2 mode-mix retime

Written 2026-11-17 PST on dain-1. **I wrote the grading rule and every prediction below before anything ran.** The coordinator approved J2's grading rule as written.

For the coordinator to run on AWS Spot, inside the **$0.909** left in the uap-core hold.

## Conventions

The same as UAP-1 except where a job says otherwise:
- Temurin 25.0.4+7, native mode only (`--enable-native-access=ALL-UNNAMED`), the JVM flags of UAP-1's template.
- Each class's JMH defaults (warmup 10 × 1 s, measurement 7 × 1 s).
- 2 hosts per platform; legs interleave fork by fork (ABAB) on the same host; the legs' jars sit at paths of equal length.
- For each fork, record its score (the mean of its 7 measurement iterations), not only the row aggregate. The grading below needs per-fork scores.

## J2: one-call vs two-step, r9g only, 24 forks per leg per host

UAP-1's r9g parse forks fell into three modes (~366 / ~480 / ~560 µs on parseOs CRAWLERS), and the legs drew different shares of them. These three pairs tripped or were flagged:

| Pair (one-call / two-step), leg C jar `c46ea3278` | UAP-1 r9g |
|---|---|
| `parseOsOneCall` / `parseOs`, CRAWLERS | 1.289 / 1.297; fastest fork 1.003 / 1.012 |
| `parseBrowserOneCall` / `parseBrowser`, OS_TESTS | ⚑ z1 1.100, z2 1.002 |
| `parseBrowserOneCall` / `parseBrowser`, CRAWLERS | ⚑ z1 0.909, z2 1.001 |

All three use `-p ruleMemoryMiB=32 -p atomSetMemoryMiB=32 -f 24`, in UAP-1's command line template. The jar is the same leg C build that UAP-1 ran; reuse it if it is still on hand.

3 pairs × 2 rows × 24 forks × 2 hosts = **288 forks**.

### Grading rule (written before the run)

For each pair, on each host separately:

1. **Median ratio:** the median fork score of the one-call row divided by the two-step row's.
2. **Fastest-fork ratio:** the lowest fork score of the one-call row divided by the two-step row's.
3. **Modes:** pool the host's 48 fork scores for the pair (both rows) and sort them. Split them into modes wherever two neighbours differ by more than 8% of the lower one. For each mode, report its range and how many of each row's 24 forks fall in it (**mode share per leg**). If one row has at least 4 more forks than the other in some mode, the modes **split the legs**. For such a mode, also report Fisher's exact two-sided p on the 2×2 table (in that mode or not, by row).
4. **Per-mode ratio:** in every mode where each row has at least 3 forks, the ratio of the two rows' median scores within that mode.

The verdict for the pair, using both hosts:

| Verdict | When |
|---|---|
| **Pass** | the median ratio is in 0.97–1.03 on both hosts |
| **Mode artifact** (for the coordinator's ruling) | the median ratio is outside 0.97–1.03 on some host, and on both hosts the fastest-fork ratio and every per-mode ratio are in 0.97–1.03, and the modes split the legs on that host |
| **Real difference** | otherwise: on some host, the fastest-fork ratio or a per-mode ratio is outside 0.97–1.03, or the median is outside with no mode split to explain it |

If a host returns fewer than 20 forks for a row, grade the pair on the other host alone and say so.

### Predictions

| Pair | Median verdict | Fastest-fork ratio | Wrong if |
|---|---|---|---|
| all three | pass, or mode artifact: the API runs the same work as the two-step parse with one call boundary, and UAP-1's fastest forks already agreed (0.995–1.012) | 0.98–1.02 on both hosts | any "real difference" |

24 forks per row make it likelier that both legs draw every mode, which narrows a median gap that came from uneven draws. It does not remove the modes themselves.

## J1: U1b's fixed head, both platforms, 12 forks per leg per host

**Cause, from paired investigators** (Opus and gpt-6.1-sol, same brief; verdict `uap-core-manymatch-r8i.verdict.md`):
- U1b removed `searchMany`'s end-of-text step, and with it the code C2 had compiled after the loop.
- The per-byte loop itself is unchanged: same work, same inlining tree, same descriptors, same states, and on aarch64 the same instruction sequence. It already moves values through the stack on every byte and re-reads `dfa.bytemap` with its bounds check.
- Working hypothesis: on x86 (about 13 usable registers, variable-length instructions) the new register allocation or alignment costs 3.7%. Graviton is flat.

**Fix:** read `dfa.bytemap` into a local before the loops of `searchMany` and `searchManyFull`, as every other hot loop in `Dfa.java` already does. On aarch64 the loop sheds its per-byte store and reload of `dfa` and the `bytemap` field and length loads. The fix is folded into U1b's commit. #279's head is now `8055edf31` (U1b `bfd38a88d`, U2 `8055edf31`), and `TestRe2Set` and `TestFilteredRe2` pass.

**Legs:**
- **A**: `80bccc124`, branch `user/dain/uap-core`. Main `bdf26dc33` plus the bench; the same as UAP-1.
- **C**: `c46ea3278`, branch `user/dain/uap-core-onecall`. U1b before the fix, plus the bench; the same as UAP-1.
- **D**: `fe44865e3`, branch `user/dain/uap-core-fixed`. #279's fixed head plus leg A's three bench commits, cherry-picked. Its bench diff equals A's line for line (index lines aside).

### Rows

| Group | Rows | Legs | Platforms |
|---|---|---|---|
| Guards | `BenchmarkPatternCollections.matchingSetPatternIds` and `.matchesAny`, `sourceLength=32768`, `patternCount` 16 and 128: 4 rows | A, D (both platforms); C joins on r8i only, so D/C shows the fix on the same hosts | r9g, r8i |
| Controls, UA | `BenchmarkUapCore.browserAtomSetMatchingPatternIds` and `osAtomSetMatchingPatternIds` on UA_TESTS; `parseBrowser` and `parseOs` on UA_TESTS; all at `ruleMemoryMiB=32 atomSetMemoryMiB=32`: 4 rows | A, D | r9g, r8i |
| Controls, ATOM_FRAGMENTS | `browserAtomSetMatchingPatternIds` and `osAtomSetMatchingPatternIds` on ATOM_FRAGMENTS at 32 MiB: 2 rows | C, D | r9g, r8i |

The ATOM_FRAGMENTS controls compare D against C, not A. U1b's 50× win there is already measured (UAP-1 C/A 0.018 / 0.017). The question now is whether the fix keeps it, and leg A's resets take about 8 s per op, which would cost a third of the hold.

### D-cell: confirm the cause on r8i (one host, one fork per leg)

The host needs `hsdis-amd64.so` in `$JAVA_HOME/lib/server`, as #275's r8i diagnosis used. For each of A, C and D, in its own jar:
```
java -cp <jar> org.openjdk.jmh.Main \
  -jvmArgs "--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch" \
  '^io.airlift.regulator.BenchmarkPatternCollections\.matchingSetPatternIds$' \
  -p patternCount=16 -p sourceLength=32768 -f 1 \
  -prof 'perfasm:intelSyntax=true;top=3;saveLog=true'
```
Then one more fork per leg with `-prof 'perfnorm:events=cycles,instructions,ld_blocks_partial.address_alias'` in place of perfasm.

Return the hottest region's assembly for each leg. Compare:
- the `[rsp+…]` loads and stores inside the byte loop;
- whether `stateOffset`, `transitions` or `bytemap` live in memory;
- the loop's start address mod 64;
- the loop's instruction count;
- the perfnorm cycles, instructions and alias counts per op.

### Predictions (written before the run)

| Rows | Prediction | Wrong if |
|---|---|---|
| Guard `matchingSetPatternIds`, D/A, r8i | **0.96–1.01**: the hoist takes a load, a store and a bounds check out of every byte | above 1.03 on both hosts |
| Guard `matchingSetPatternIds`, D/C, r8i | **0.94–0.98**: recovers the 3.7% | above 0.99 on both hosts |
| Guard `matchingSetPatternIds`, D/A, r9g | **0.94–1.01**: the loop shrinks from 38 to 33 instructions | above 1.02 on both hosts |
| Guard `matchesAny`, D/A, both | **0.98–1.02**: the fix doesn't touch the generic search it runs on | above 1.03 on both hosts |
| Controls UA atom sets, D/A, both | **0.94–1.01** (UAP-1 C/A was 0.943–1.011) | above 1.03 on both hosts |
| Controls UA parses, D/A, both | **0.97–1.01** | above 1.03 on both hosts |
| Controls ATOM_FRAGMENTS atom sets, D/C, both | **0.95–1.01**: the same loop, hoisted | above 1.02 on both hosts |
| D-cell | C's byte loop has more stack traffic than A's, or starts at a different offset mod 64. D's loop has no per-byte store and no `bytemap` field load | A and C have the same loop, with the same alignment and no extra memory operands. Then the cause is elsewhere: read the alias counts, and if they don't explain it, I take the miss back to investigation |

**Pass for U1b:** every guard and control row meets its prediction, or is at worst inside its "wrong if" bound. If D/A `matchingSetPatternIds` on r8i is still above 1.03 on both hosts, the fix has missed, and I'll bring the D-cell evidence back before proposing anything else.

## Estimate

UAP-1 averaged $0.00051 per fork across 4,100 forks.

| Job | Forks |
|---|---|
| J1 guards: 4 rows × 2 legs × 12 × 4 hosts, plus leg C on r8i: 4 × 12 × 2 | 480 |
| J1 UA controls: 4 rows × 2 legs × 12 × 4 hosts | 384 |
| J1 ATOM_FRAGMENTS controls: 2 rows × 2 legs × 12 × 4 hosts | 192 |
| D-cell: 6 forks, one r8i host | 6 |
| J2: 3 pairs × 2 rows × 24 × 2 r9g hosts | 288 |
| **Total** | **1,350 forks, about $0.69** |

Guard cap **$0.85**, inside the $0.909 left. If the guard nears the cap, drop rows in this order:
1. the UA parse controls;
2. leg C's r8i guard rows (D/A still grades the fix);
3. the r9g UA atom-set controls.

The D-cell, the J1 r8i guard rows and J2 are never dropped.

Priority: the D-cell and J1 guards first, then J2, then the controls.
