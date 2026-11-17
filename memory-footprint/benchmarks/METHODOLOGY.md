# Memory methodology: Regulator and comparator engines (phase 1, 2026-11-08)

What a per-row memory page cites to explain its numbers. It describes the phase-1 measurements as
recorded in `phase1-jvm-2026-11-08/` and `phase1-native-2026-11-08/` (READMEs and `harness/`) and
the coverage audit `COVERAGE-2026-11-08.md`. Where those are silent, this says "not recorded".
Every number is a byte count; nothing was timed.

## Two counting methods

| Side | Engines | How bytes are counted |
|---|---|---|
| JVM | Regulator (safe, native), JDK `java.util.regex`, Joni | Reachability walk of the object graph from named roots, JOL 0.17 shallow sizes (`VM.current().sizeOf`). Native-mode DFA pointer tables are added from Regulator's own accounting (`absolutePointerTransitionMemory()`), not from an allocator. |
| Native | C++ RE2, Rust `regex` crate (str and bytes API) | Counting allocator: every replaceable C++ `operator new`/`delete` and a Rust `#[global_allocator]` over `System`, each block counted as its `malloc_size()` usable bytes, relative to a baseline taken after the inputs load. Cross-checked against `malloc_zone_statistics` (within 80 B RE2, 112 B Rust). |

The two are not the same measurement. JOL counts live object sizes (headers included); the native
side counts allocator usable sizes in macOS 16 B quanta. Small rows differ by rounding alone.

## Per engine: fixed, peak, settled

| Engine | fixed | peak | settled | "settles" means |
|---|---|---|---|---|
| Regulator safe | Graph of the measured compile right after compile, pattern only (no matcher). DFA pointer table is a heap `long[]`, in the heap number. | Largest sampled walk of pattern + matchers + per-thread workspaces at any checkpoint. If the DFA reset, raised to settled + (budget − used), since the cache may have been full between samples. | Walk at the last checkpoint. | The DFA signature (per DFA: budget, used, states, resets) stopped changing: `stop=stable`, or equal at the half-way checkpoint and the end. Not a check that bytes are flat. |
| Regulator native | Heap walk as safe, plus native pointer tables. | Heap as safe; native = largest sampled live tables plus superseded tables still held until the arena closes. | Heap at the last checkpoint plus live native tables; superseded tables not included. | As safe. |
| JDK | Graph of `Pattern` after compile. | Largest sampled walk of pattern + matcher roots (below). | Last checkpoint. | No DFA, so the signature never changes: every long run stops at the first decade at or past its minimum (1,000 ops lifecycle, specialized; 100 ops bulk) with `settles = yes`. Settled is "the last checkpoint", not a flatness check. |
| Joni | Graph of the `Regex` (and the pattern bytes copy on trino-operations and specialized rows). | As JDK. | As JDK. | As JDK. |
| C++ RE2 | Live bytes after compile. | True allocator high-water from the baseline through the last op, so it includes compile transients and per-search garbage. | Live bytes after the last op, per-search state included, before destroy. | Live bytes identical after every op of the second half. Otherwise `settles = no` and `plateau_bytes` is the second-half maximum. |
| Rust crate | As RE2. | As RE2. | As RE2; pool caches included (owner slot on one thread). | As RE2. |
| Rust crate, bytes API | As Rust. | As Rust. | As Rust. | As Rust. |

Compare `fixed` and `settled` across the two sides, not `peak`: native peak sees transient
garbage, JVM peak never does (it is reachable bytes at sampled checkpoints).

Compile and single-use rows have `settles = n/a`.

## What each count includes and leaves out

### JVM (Regulator, JDK, Joni)

Included: everything reachable from the roots and not excluded. Roots are the pattern (fixed), and
at each checkpoint the pattern plus the matcher roots and extra roots below.

| Row family | Regulator RE2 / Java / Rust | Regulator Trino | JDK | Joni |
|---|---|---|---|---|
| lifecycle `reused*` | its `RegexpMatcher` | none (the benchmark holds only the pattern) | its reused `Matcher` | none (the benchmark holds only the `Regex`) |
| lifecycle `compile`, `singleUse*` | none | none | none | none |
| bulk, non-FIND ops | the slice matcher | none | the `Matcher` | none |
| bulk, FIND | none | none | the `Matcher` | none |
| trino-operations | n/a | none | n/a | none (new `Matcher` per call) |
| specialized | its `RegexpMatcher` | none | its `Matcher` | none (new `Matcher` per call) |
| LIKE | the calling thread's wildcard workspace (thread-local), when allocated | | | |

"None" means the harness walks no matcher; state an operation creates and drops per call is
garbage after the call and is not counted. Whether a "none" engine keeps per-thread state
elsewhere is not recorded. DFA caches hang off the compiled program, so they are in the pattern
graph; no row had a DFA reachable from a matcher but not from the pattern.

Left out:

- **Shared tables**: every object also reachable from a reference compile of the same pattern made
  first (static Unicode tables, shared singletons). Reported in notes as `shared_heap_excluded`.
- **Inputs**: sources, haystacks, pattern text supplied to the engine.
- `Class`, `ClassLoader`, `Thread`, `ThreadGroup`, `Module`, `ReferenceQueue`, and `Reference`
  referents. Objects in `java.lang.invoke.*`, `jdk.internal.foreign.*`, `jdk.internal.ref.*`
  count shallow only (a heap `MemorySegment` is followed to its backing array).
- **Garbage**: compile transients (reported separately as `compile_alloc_bytes`, from
  `ThreadMXBean.getThreadAllocatedBytes`), per-call search state, unreachable superseded heap tables.
- **Code and classes**: JIT code cache, Regulator's classes, metaspace. Generated classes are a
  separate `metaspace_bytes` column on generated rows only, never in fixed, peak or settled.
- **Heap overhead**: GC region slack and free space. Native-mode arena overhead beyond the
  accounted table bytes: not recorded.

### Native (C++ RE2, Rust crate)

Included: every live block allocated through C++ `operator new` or the Rust global allocator after
the baseline. That covers the compiled program(s) (RE2's reverse program is built lazily, on first
need), DFA state caches, one-pass and bit-state data, the Rust pool's caches (owner slot inline in
the boxed pool, other threads' caches on the pool stacks), per-search state the harness builds
(bulk capture slots, specialized scratch), allocator rounding up to the usable size, and lazily
built process statics the first time they appear (RE2 logging, Abseil once-initialized tables;
they show as `residual_after_destroy`, 16 B to 32 KiB on 23 RE2 rows, 16 B on Rust).

Left out: static Unicode tables in rodata, native code (text segments), allocator metadata
outside the usable size, thread stacks, and the few bytes allocated outside `operator new` and the
global allocator (the zone cross-check difference above). Lifecycle rows build no harness state
(the engines keep their search state internally).

## Settings that change the numbers

| Setting | JVM run | Native run |
|---|---|---|
| Regulator commit | `6435e5d4f` | `6435e5d4f` (inputs, manifests, adapters) |
| Report the rows come from | `regulator-858993c3…-137669c8…json.gz` | same |
| Runtime | JDK 25, `java -version` on the Mac; exact build not recorded | Apple clang `-O3 -DNDEBUG`; rustc/cargo 1.97.0 |
| Heap and GC | G1, `-Xms1g`, `-Xmx4g` (`-Xmx12g` for bulk and rebar) | n/a |
| Object layout | No compressed-oops, class-pointer or alignment flags passed; the values in effect were not recorded (JOL's VM details were not logged) | n/a |
| Allocator | Native-mode tables: Regulator arena, bytes from its accounting; allocator not recorded | macOS system malloc (default zone), usable sizes in 16 B quanta |
| Engine versions | Joni: airlift-joni 2.1.5.3; JDK regex: the JDK's own | RE2 `972a15ce` (Abseil `d38452e1`); regex 1.13.1, regex-automata 0.4.18; rebar's engine regex 1.12.4 / regex-automata 0.4.14 |
| Engine memory limits | Regulator 96 MiB default (`DEFAULT_MAX_MEMORY`); rebar dictionary rows the default rejects run at 384 MiB | RE2 `max_mem` default 8 MiB; Rust `nfa_size_limit` 10 MiB, `hybrid_cache_capacity` 2 MiB per lazy DFA; rebar `nfa_size_limit` 100 MiB, `perf-dfa-full` |
| Platform | Apple-silicon Mac | aarch64 macOS (closest to r9g; x86 prefilter choices may differ) |
| Measuring tool | JOL 0.17, `-Djol.magicFieldOffset=true -Djol.skipHotspotSAAttach=true` | counting allocator + read-only RE2 DFA accessor and reset/bail hooks |

The published timings ran Temurin 25.0.4 with an 8 GiB heap on the AWS hosts, and came from
candidate builds 858993c3 and 37072cdb, not `6435e5d4f`. Memory and time on a page from these runs
come from different builds.

## Rows, inputs, operations, threads

**Rows.** The published (row id, language) pairs of the report above: 1,132 pairs. Each language
has one comparator: JDK (Java), Joni (Trino), C++ RE2 (RE2), Rust crate (Rust), Trino SQL LIKE or
Trino DFA (LIKE, not measured). The specialized `/generated` and `/regular-engine` ids share the
base id's comparator measurement, as the report does. Rebar rows were also measured but are not
published.

**Inputs.** The benchmark's own patterns and inputs, through the benchmark code where possible:
the lifecycle and bulk manifests, the trino-operations, specialized and LIKE scenario code, and
rebar KLVs regenerated from the rebar definitions.

**Rust bytes API.** The native bulk runner uses the published adapter unchanged, which picks the
crate's bytes API when the row turns Unicode off or the haystack is not valid UTF-8; the lifecycle
runner always uses the str API. The JVM Regulator Rust bulk rows compile through the benchmark's
own code, which carries the same bytes flag (`rustBytes`). No column marks which rows ran on the
bytes API.

**Operation counts.** Both sides aim at the number of operations one published run keeps a single
compiled pattern alive for, but they derive it differently:

| Row kind | JVM | Native |
|---|---|---|
| lifecycle `reused*`, specialized | target ceil(20 s / fastest published ns), cap 20M; ns is `candidateNs` for Regulator, `comparatorNs` for JDK/Joni | ceil(14 s / ns), the longest warm-up run; ns = smallest published `comparatorNs` over r9g, r8i and both modes |
| bulk | 20 s / ns, cap 20M | max(6, ceil(22 s / ns)), the process lifetime |
| trino-operations | 1 s / ns | no native comparator |
| compile | one compile | one compile |
| single-use | one compile and one op on the **first** source | eight lifetimes, one per source; each column is the **max** over the eight |
| rebar | min(2M, 1.5 × larger published `iters`) | min(1M, ceil(1.5 s / median)) + published `iters` |
| early stop | at a decade checkpoint when the DFA signature equals the one at ops/10, after max(1,000, 10 × sources) ops (100 for whole-haystack rows) | none |
| wall cap | 60 s (9 comparator rows hit it) | none (2 h process timeout) |

The early stop means the two sides can run very different counts on one row:
`everyday/delimiter/reusedCount` ran 1,000 ops for JDK and 114,307,661 for C++ RE2. The `ops`
column records the count on each line.

**Checkpoints.** JVM: walks at 1, 10, 100, … ops, at target/2 and target, and once at half the wall
cap. Native: live bytes read after every op for the settle check; checkpoint lines at 1, 10, 100,
…, the half-way op and the last op (the native README says 1, 2, 5, 10, …; the data has decades).

**Threads.** One compiled pattern shared by T = 1, 4, 16 threads, each with its own matcher or
per-search state, default budget.

| | JVM | Native |
|---|---|---|
| Engines | Regulator safe and native; no JDK or Joni run | C++ RE2, Rust crate |
| Rows | 20 rows, 37 (row, language, mode) pairs; picked by `driver.py pick-threads` plus 6 by hand | 21 rows, 40 (row, engine) pairs, `harness/thread-rows.txt` |
| Ops per thread | the row's primary op count, 120 s wall | min(row ops, max(8, ceil(1 s / ns))), same at every T |
| Reading | all workers paused by a `Phaser` at each checkpoint; walk pattern + all matchers | settled after join (thread objects freed); peak is the allocator high-water over the run |

The subsets were picked separately and share only 4 (row, language) pairs where both Regulator and
its comparator have T = 1/4/16.

## Skip reasons a cell can carry

| Reason | Engines | Pairs | Where recorded |
|---|---|---|---|
| Pattern does not parse in this language (report marks the pair not-compatible) | Regulator | 7 | `skipped:` note, `MEMORY-jvm.tsv` |
| Comparator not compatible (report state) | JDK 10, Joni 12, Rust 4 | 26 | `skipped:` note (JVM: "comparator not compatible"; native: "published state not-compatible") |
| Comparator did not finish in the published run (one op does not complete) | JDK 3, Joni 3 | 6 | `skipped:` note |
| No LIKE comparator measured (Trino SQL LIKE, Trino DFA outside the JVM comparator scope) | LIKE | 48 | JVM README only; no line |
| Rebar run failed / no published rebar measurement | Regulator 2, Rust 2 (rebar, unpublished) | 4 | `skipped:` note |
| Not measured, no reason (specialized `/generated`, `/regular-engine` ids) | JDK 10, Joni 10 | 20 | nowhere; fill by copying the base id's line |

Measured values that need a caveat mark, not a skip: `stop=wall` (9 JDK/Joni rows, 5 to 97 ops);
Joni op approximates Trino's `JoniRegexpFunctions` with a new `Matcher` per call (109 rows);
`settles = no` with a second-half range (12 native rows: 9 RE2 resetting, 3 Rust growing);
`peak bounded by DFA budget (resets)`.

**How a page shows them.** Never a number: `MEMORY.tsv` writes a skip as 0 B with the engine name
filled in, so build from the per-family TSVs. Show the reason as a short label with the full text
on hover, and keep three kinds apart:

- **Not applicable**: does not parse, comparator not compatible, did not finish. The timing table
  shows no comparator for these pairs either.
- **Not measured**: no LIKE comparator; any unexplained gap.
- **Measured, with caveat**: a marker on the value (wall stop, approximated op, not settled).

## Known inconsistencies to fix in the repeatable tool

| Inconsistency today | Fix in the tool |
|---|---|
| Thread subsets picked separately on each side; JDK and Joni have no thread run | One shared thread subset across Regulator, JDK, Joni, RE2 and Rust |
| "Settled" is a DFA-signature check on the JVM (trivially yes for JDK/Joni) and a live-bytes check on native | One settled definition (live bytes flat over the second half) on both sides, or report both |
| JVM runs stop early at a stable signature; native runs the full count | Same op count for every engine on a row, or record both and say so |
| Single-use: first source on the JVM, max over eight sources on native | One definition on both sides |
| `merge.py` writes skips as 0 B and drops the reason | Write `-` and carry the `skipped:` reason into the merged table |
| Merged table keeps only safe-mode settled | Carry safe fixed and peak too |
| 20 specialized JDK/Joni ids have no line | Copy the base id's comparator line, as native already does |
| No column marks Rust bytes-API rows | Record the API each Rust row ran |
| Memory measured at `6435e5d4f`, timings from other builds | Measure the release build the timings come from |
| JDK build, layout (compressed oops, class pointers, alignment), comparator and allocator versions partly unrecorded | Record JDK build and JOL VM details, comparator versions, allocator and platform in the versioned TSV |
| LIKE has no comparator | Decide: mark N/A with the reason on the page, or measure Trino's `LikeMatcher` |

## Budget column

The page may show, per row, the memory budget the row needed under the pill rule:

- A row runs at the default budget unless it fails to compile there or measurably slows from DFA
  resets there.
- Otherwise it runs at the smallest standard step that gives full speed, shown as a pill
  ("32 MiB") next to the row name.
- Comparators with a memory setting follow the same rule: C++ RE2 `max_mem`, the crate's
  `size_limit` and `dfa_size_limit`.

The column comes from the report tooling's pill decision, which uses timings. It does not come from
the memory probe: the probe measures bytes at one budget and its sweep reports only resets, bails,
rejections and result checksums. The pill rule is planned report tooling (PR-4, with the bulk
benchmark's budget parameter from `ab2d01815`); it is not in the report tooling at `3dd4c1e94`. The
set of standard steps and the threshold for "measurably slows" are not recorded yet; the sweeps
used 96, 64, 48, 32, 24, 16 and 8 MiB.
