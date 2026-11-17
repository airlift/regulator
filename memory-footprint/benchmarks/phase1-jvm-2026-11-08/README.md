# Phase 1, JVM engines: per-row memory

## Question

For every published benchmark row, how many bytes does Regulator hold (fixed after compile, peak
while matching, settled at the end), which rows never settle (they fill the DFA budget and reset),
and how do JDK `java.util.regex` (for the Java language) and Joni (airlift-joni 2.1.5.3, for
Trino) compare on the same work? Also: does any row change its result at a smaller budget
(96, 64, 32, 24, 16, 8 MiB), and how does memory scale with threads sharing one compiled pattern?

Byte counts only. Nothing was timed: a wall clock only bounds how long a row runs.

Commit measured: `6435e5d4f` (origin/main, "Match Trino fixed-width regions on the matcher"),
built in classpath mode from the checkout `/Users/dain/.t3/worktrees/regulator/user-dain-memory-footprint`.
JDK 25 (`java -version` on the Mac), G1, `--add-modules jdk.incubator.vector`.

## Files

| File | What |
|---|---|
| `MEMORY-jvm.tsv` | One line per (row, language, engine, mode). Regulator in `safe` and `native`; comparators `jdk`/`joni` with mode `-`; rebar rows `rebar/<name>` for Regulator in native mode. Skipped pairs carry `-` values and a `skipped:` note. |
| `SWEEP-jvm.tsv` | Regulator rows (not LIKE, which has no DFA budget) at 96/64/32/24/16/8 MiB: resets, bails, rejected, and `result_identical` against the default run. |
| `THREADS-jvm.tsv` | About 20 rows: one compiled pattern shared by T = 1, 4, 16 threads, each thread with its own matcher. |
| `harness/` | `MemoryProbe.java`, `probe.sh`, `driver.py`, `rebar_klv.py` (copies of the scratch harness). |

## Method

### What is counted

- **Heap**: an identity-based reflective walk of the object graph, with JOL 0.17 shallow sizes
  (`VM.current().sizeOf`), run with `-Djol.magicFieldOffset=true` (needed for records and hidden
  classes) and `-Djol.skipHotspotSAAttach=true`. The walk follows every non-static reference
  field and object-array element. It skips `Class`, `ClassLoader`, `Thread`, `ThreadGroup`,
  `Module`, `ReferenceQueue`, and the fields declared in `java.lang.ref.Reference` (referents).
  Objects in `java.lang.invoke.*`, `jdk.internal.foreign.*` and `jdk.internal.ref.*` count
  shallow only (method handles, memory segments, cleaners: their targets are JDK state).
- **Shared tables are excluded.** Each row compiles the pattern twice: a reference compile P2
  first (which also loads classes and fills JVM-wide caches), then the measured compile P1. Owned
  bytes are the graph of P1 minus every object also reachable from P2 (static Unicode tables,
  shared singletons) and minus the input graph (sources, haystacks). The excluded shared bytes
  are in the notes as `shared_heap_excluded`.
- **Native**: in native mode the DFA pointer tables live off-heap; `fixed_native`, `peak_native`
  and `settled_native` sum `DfaInstance.absolutePointerTransitionMemory()` over every DFA, plus
  (for peak) `supersededAbsolutePointerMemory()`, the tables replaced by growth that are still
  held until the arena closes. In safe mode the same table is a heap `long[]` and is in the heap
  number. Comparators are pure heap.
- **DFA columns** come from `DfaInstance.cacheSnapshot()`, summed over every DFA instance
  reachable from the pattern: `dfa_budget_bytes` = sum of `stateBudget`, `dfa_peak_used_bytes` =
  the largest sampled `stateBudget - availableStateMemory` (set to the budget when the row reset),
  `resets` = sum of `resetCount`.
- `bails` is **not a count of bailed searches**: the library keeps no such counter. It counts DFA
  instances that are no longer `ok()` or carry the sticky slow-bail flag
  (`countSearchBailedWhenSlow()`). See "Proposed hook".
- `compile_alloc_bytes`: `ThreadMXBean.getThreadAllocatedBytes(currentThread)` just before and
  after the P1 compile (P2 has already loaded classes, so this is the steady compile cost).
- `metaspace_bytes` (generated rows only, `-` elsewhere): delta of used bytes in the `Metaspace`
  plus `Compressed Class Space` pools across the P1 compile; the notes add `hidden_classes`, the
  loaded-class delta. On rows without generation the delta is JIT and MethodData noise and is not
  reported.
- `matcher_heap_bytes`: the walk of the matcher roots right after creation, excluding the pattern
  graph, shared tables and inputs. `dfas_on_matcher` would appear in the notes if any DFA were
  reachable from a matcher but not from the pattern; none was, on any row (DFAs hang off the
  compiled program's atomic references, so one cache per pattern is shared by every matcher and
  thread).
- `fixed_*`: P1 right after compile. `peak_*`: the largest sample at any checkpoint (pattern +
  matchers + per-thread workspaces). `settled_*`: the last checkpoint. `plateau_bytes` = settled
  heap + settled native when the row settles. When a row reset, the peak is bounded from above by
  settled + (budget - used at the end), since the cache may have been full between samples; the
  note says so.

### Operations and op counts

Each row repeats the benchmark's own operation on the benchmark's own inputs, through the
benchmark code where possible (`BenchmarkLanguageComparison.compilePattern` and `createMatcher`,
`BenchmarkLanguageBulk.BenchmarkData.initialize/compilePattern/execute`, the trino-operations,
specialized and LIKE scenario code, and a copy of the rebar runner models). The target op count is
what one benchmark fork runs:

- language lifecycle, language bulk, specialized, trino-like, TPC-H LIKE: 20 s of operations per
  fork (10 x 1 s warm-up + 10 x 1 s measurement), so target = 20 s / fastest published ns/op
  (Regulator's `candidateNs`, the comparator's `comparatorNs` for jdk/joni), capped at 20,000,000;
- trino-operations: 1 s per fork (50 ms windows);
- rebar: min(2,000,000, 1.5 x the larger `iters` of the r9g/r8i rebar CSVs); compile and
  regex-redux models are single-shot;
- compile and single-use rows: one compile plus one operation.

Checkpoints at 1, 10, 100, 1k, 10k, ... operations, at target/2 and target, and once when half the
wall cap has passed. A row stops early ("stable") when the DFA signature (per DFA: budget, used,
states, resets) is unchanged from ops/10 to ops, with ops at least `max(1000, 10 x sources)` for
scalar rows and 100 for whole-haystack rows (bulk, rebar, TPC-H). A lazy DFA that has seen every
input without resetting cannot grow further on the same inputs, so a stable stop is the plateau.
Other stops: `target` and `wall` (60 s). The `stop=` note says which.

Results are folded into a checksum (`c = 31c + hash(result)`) at every checkpoint, which the
sweep compares.

### Budget sweep

Every Regulator row with a DFA budget re-runs with a mirror compile at 32, 24, 16 and 8 MiB.
Rows whose default run used at least 1 MiB of DFA state or reset ("heavy") also run 64 MiB and
repeat the full default op count; other rows cap at 10 operations (whole-haystack) or 100
(scalar), since their DFA state fits every budget. Lifecycle, trino-operations and specialized
rows also run a 96 MiB mirror compile, to check the mirror against the benchmark's own default
compile; for bulk and rebar the 96 MiB column is the default run. Sweep wall cap: 30 s.
`result_identical` compares checksums at the largest checkpoint the sweep run shares with the
default run. A Trino budget can also change the compiled class (literal-set and
literal-alternation forms depend on the forward budget); the notes record any `impl=` change.

### Threads

One shared compiled pattern; each of T threads has its own matcher (and, for LIKE, its own
thread-local workspace) and runs the row's operation for the default run's op count, capped by a
120 s wall. A `Phaser` pauses every worker at each checkpoint while the main thread walks the
pattern plus all matchers. Safe and native mode, default budget.

## Reproduce

```bash
S=~/AgentScratch/regulator/user/dain/memory-footprint/jvm-phase1
R=/Users/dain/.t3/worktrees/regulator/user-dain-memory-footprint   # at 6435e5d4f
(cd $R && ./mvnw -q test-compile && ./mvnw -q dependency:build-classpath -Dmdep.outputFile=$S/test-classpath.txt -Dmdep.includeScope=test)
# harness/classpath.txt = $R/target/test-classes:$R/target/classes:<test-classpath.txt>:$S/lib/jol-core-0.17.jar
# (jol-core 0.17 from Maven Central into $S/lib)
javac -d $S/harness/classes -cp "$(cat $S/harness/classpath.txt)" --add-modules jdk.incubator.vector \
    $S/harness/src/io/airlift/regulator/MemoryProbe.java
(cd $R && python3 tools/re2-benchmark/language/collection.py prepare --manifest-directory $S/language-manifest)
python3 -I $S/harness/rebar_klv.py            # rebar KLVs from ~/work/BurntSushi/rebar/benchmarks
python3 -I $S/harness/driver.py jobs          # work/jobs.json from the report + manifests
python3 -I $S/harness/driver.py run primary --parallel 12
python3 -I $S/harness/driver.py run sweep --parallel 12
python3 -I $S/harness/driver.py run sweep-edge --parallel 4   # 64 and 48 MiB for rows a sweep budget rejected
python3 -I $S/harness/driver.py pick-threads   # then appended 6 representatives by hand (work/thread-jobs.txt)
python3 -I $S/harness/driver.py run threads --parallel 6
python3 -I $S/harness/driver.py aggregate <outdir>
```

Inputs: report `benchmark-report/data/regulator-858993c3…json.gz` (1,132 pairs), bulk manifest
`~/work/airlift/regulator-benchmark-results/release-2-0-candidate-20261029/plans/bulk-manifest`,
lifecycle manifest prepared by the language collection tool into `$S/language-manifest`, rebar results
`~/AgentNotes/regulator/user/dain/rebar-prototype/results/{r9g,r8i}.csv`.

## Results

Coverage: 1,125 of the 1,132 published pairs measured in both modes; 523 of 525 rebar rows
(native). Skipped, each with a `skipped:` line in `MEMORY-jvm.tsv`:

- 7 Regulator pairs that the report marks not-compatible and whose pattern does not parse in that
  language (ruff noqa/shebang/string-quote-prefix in Java and Trino; i13 huge-ascii-nosuffixlit in
  Trino). The other 19 not-compatible pairs parse and are measured, with `report_state=not-compatible`.
- 2 rebar rows the rebar run itself failed on (from `rebar-klv/index.tsv`).
- Comparators: pairs whose comparator is not compatible, and 6 JDK/Joni bulk pairs that did not
  finish in the published run (holmes-coword-watson, quadratic-regex-1x/2x; one operation does not
  complete). Trino LIKE comparators (trino-sql, trino-optimized) are outside the JVM comparator
  scope and have no lines.

### Settling

Every measured Regulator row settles at the default budget: no resets and no bails on any row in
either mode. The DFA budget is more than half full on 20 of 1,272 DFA rows; the fullest is
`language-bulk/curated/10-bounded-repeat/context` in Java and Trino (33.10 MB used of a 33.42 MB
state budget, 99.1%), then RE2/Rust on the same row (88%).

### Bytes (Regulator, per row)

| Set | fixed median | p90 | max | peak median | p90 | max |
|---|---|---|---|---|---|---|
| published, safe | 5,392 | 66,072 | 25,141,208 | 8,680 | 134,744 | 55,578,968 |
| published, native | 5,392 | 66,072 | 25,141,208 | 8,680 | 134,744 | 55,579,080 |
| rebar, native | 7,024 | 341,104 | 71,176,152 | 19,976 | 1,208,640 | 126,816,704 |

Peak is heap plus native. Max rows: fixed, `language-bulk/dictionary/search/english-10` (Rust);
peak, `language-bulk/unicode/overlapping-words/russian` (all four languages within 400 bytes);
rebar, `dictionary/search/english` and `english-tiny`, which the default 96 MiB compile rejects
and the rebar runner compiles at 384 MiB. Native DFA bytes: median 0, p90 10,032, max 9,386,520
(10-bounded-repeat, Java/Trino). `matcher_heap_bytes`: median 0 (safe) to 16 (native), max 912.
`compile_alloc_bytes`: median 32,984, p90 about 440,000, max 365,845,088 (dictionary compile
english-10, Rust) and 890,201,352 for rebar dictionary/search/english at 384 MiB. Generated
classes: `metaspace_bytes` median 52,664, max 235,200, one hidden class per pattern.

Comparators, same rows: JDK fixed median 432 B, peak median 580 B, max 6.27 MB (dictionary
english-10); Joni fixed and peak median 720 B, max 2.36 MB. Regulator peak (heap + native) over
the comparator's peak: median 17.8x JDK (p10 4.6x, p90 147x) over 222 pairs and 8.1x Joni (p10
2.2x, p90 44x safe, 49x native) over 324 pairs. Regulator is never smaller than the comparator
on a row (minimum 1.21x JDK, 1.08x Joni); the largest ratios are DFA-heavy whole-haystack rows,
for example 10-bounded-repeat at about 30,000x and overlapping-words/russian (24 MB of compiled
Unicode program against Joni's 66 KB).

### Budget sweep

All 2,674 compared sweep lines have `result_identical = yes`: no budget changed a result
(3 lines are `n/a`, the rebar rows where every budget rejects). Rejections are compile-time
(`RegexpCompileMemoryLimitException`), not DFA failures:

| Budget | lines rejected | rejected or resetting or bailing |
|---|---|---|
| 96 MiB | 3 (rebar dictionary english, english-tiny, compile/english: need 384 MiB) | 3 |
| 64 MiB | 3 | 7 |
| 48 MiB (edge check) | 0 of 14 tried | - |
| 32 MiB | 19 (+ dictionary english-10, bulk and rebar) | 29 |
| 24 MiB | 39 (+ overlapping-words english/russian, bulk and rebar) | 51 |
| 16 MiB | 39 | 59 |
| 8 MiB | 42 (+ rebar long-words-unicode, 03-date unicode, compile-unicode) | 62 of 2,677 |

DFA resets below the default come from two rows only:

- `curated/10-bounded-repeat/context` (all languages, bulk and rebar): at 32 MiB and below it
  resets twice and the DFA bails (falls off the DFA), every budget. At 64 MiB, Java and Trino in
  native mode reset 115 times in 100 operations without bailing (1.15 resets per operation),
  while safe mode resets 7 times and bails.
- `wild/url/search` (all languages, bulk and rebar): settles at 24 MiB and above except Trino
  (17 resets safe, 2 native at 24 MiB). At 16 MiB safe mode resets about twice per operation
  (199 to 200 in 100 operations) while native mode resets 2 to 4 times and then fits; at 8 MiB
  both thrash without bailing (RE2: 1,604 resets in 58 operations safe before the wall, 618 in
  100 native). At 16 MiB the native run ends with all 4,755 states and a 301 KB pointer table
  (3.26 MB at 24 MiB), so the pointer table appears to be withheld and its charge refunded, while
  the safe run holds 3,836 states; why the modes differ is for the ceilings investigation.

A smaller budget also changes the compiled class on `dictionary/search/english-15` and
`curated/12-dictionary/single`: at 16 and 8 MiB RE2, Java and Rust compile a
`LiteralAlternation*` pattern instead of the general engine (notes `16m impl=...`).

### Threads

20 rows (37 row-mode pairs; rebar rows are native only), one compiled pattern shared by T = 1,
4, 16 threads with their own matchers, default budget. No row reset at any T. Pattern plus all
matchers grows by the matchers only: 0 to 3.6 KB from T = 1 to T = 16 on most rows (about 200 B
per matcher), up to 91 KB on overlapping-words/russian native and 66 KB on bibleref native, where
the difference is superseded native pointer tables awaiting the collector (peak sampling noise).
The DFA state is one cache per pattern, so 16 threads on 10-bounded-repeat hold 33.3 MB, the same
as one thread. Subset: every DFA-heavy representative (10-bounded-repeat in all four languages,
overlapping-words english/russian, url/search, bibleref captures, rebar dictionary/search/english,
rebar hyperscan fixed-length-words-unicode-nosom, rebar overlapping-words/russian grep-captures),
lifecycle reused rows per language, a trino-operations row, a regular-engine and a generated
specialized row, and LIKE wildcard plus generated TPC-H. There were no non-settling rows to add.

## Caveats

- **Safe-mode heap fix.** The first primary pass counted a heap `MemorySegment` shallow, which
  missed the safe-mode DFA pointer table (its `long[]`). The walker now follows a heap segment's
  backing array; the 442 safe-mode primary records with a DFA and all safe-mode thread records were
  rerun with it (old records in `work/superseded/walker-v1-safe.jsonl`). Native-mode and DFA-free
  records were unaffected (checked on 10-bounded-repeat: native heap identical). The sweep reports
  only resets, bails, rejections and checksums, so it was not rerun.
- **The DFA budget charge matches native-mode bytes**: on 10-bounded-repeat the charge (33.10 MB)
  equals heap DFA state plus the native pointer table (23.76 MB + 9.39 MB), and with the fix the
  safe-mode heap (33.30 MB including the program) matches too.
- `bails` counts DFA instances in a failed or sticky-bail state, not bailed searches. **Proposed
  hook**: a per-`DfaInstance` counter of searches that failed over to another engine (and one of
  searches refused because the DFA had already failed), exposed through `cacheSnapshot()`.
- Peak is sampled at checkpoints; with resets it is bounded by settled + (budget - used). No
  default-budget row reset, so this bound applies only to sweep runs, which report no bytes.
- Superseded native pointer tables are counted in `peak_native_bytes` until the collector frees
  them, so native peaks vary by a few hundred KB between runs.
- `metaspace_bytes` is reported only for generated rows; elsewhere the pool delta is JIT and
  MethodData noise.
- LIKE rows count the per-thread `TrinoLikeWildcardMatcher` workspace as matcher state; LIKE has
  no DFA budget and is not in the sweep.
- Lifecycle and bulk comparators run through the benchmark's own JDK and Joni code. For the
  trino-operations and specialized rows, which publish no JVM comparator code path of their own,
  the Joni operation approximates Trino's `JoniRegexpFunctions`: a find loop over the source with a
  new Joni `Matcher` per call (one match for `extract`). The notes say so.
- Rebar KLVs were regenerated from the rebar benchmark definitions with `rebar_klv.py`; they match
  the 464 bulk-manifest KLVs byte for byte.
- The Mac was shared with other heavy jobs (load average 90 to 300). Only the wall caps feel this:
  9 comparator rows stopped at the 60 s wall (notes `stop=wall`), and 16 sweep lines at the 30 s
  wall (notes `hit wall cap`). No Regulator primary row stopped at the wall.

## Scratch kept

`~/AgentScratch/regulator/user/dain/memory-footprint/jvm-phase1/`: `work/` (jobs, raw JSONL
records, chunk files, logs), `rebar-klv/` (1.1 GB of regenerated rebar KLVs), `language-manifest/`,
`harness/` (source, compiled classes, classpath), `analysis/` (summary scripts). Rerun any pass:
the driver skips (job, mode) pairs already in `work/`.
