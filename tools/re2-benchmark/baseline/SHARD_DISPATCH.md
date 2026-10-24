# Baseline shard dispatch

`run-host-session.sh` runs one shard of the frozen candidate on one host:

```bash
tools/re2-benchmark/baseline/run-host-session.sh \
    traditional-search qualification /results/traditional-search
```

It reads the exact route and system ownership from `shard-dispatch.tsv` and
keeps route artifacts under `routes/<route>`. Odd replicas run `native-access`
first, while even replicas run `object-row` first, so route startup and thermal
effects do not systematically favor one implementation. Expensive pinned
Trino/Joni and Rebar/native preparation is shared through
`BASELINE_SHARED_WORK_DIR`; measurements and semantic receipts are never shared
between routes.

`freeze-candidate.sh` creates an immutable benchmark ref and records that
candidate's commit, complete tree, production tree, deterministic source
archive, and benchmark-manifest hashes. The campaign controller validates those
identities directly before execution. The pre-cleanup snapshot remains a
separate regression baseline and is not a prerequisite for freezing a later
candidate.

Each route is executed by `run-shard.sh`. A deferred route produces
`route-session.tsv` but no acceptance receipt. The host wrapper verifies that
all route sessions describe the same host identity, combines the exact route
rows, rejects duplicate or missing rows, and writes the only canonical
`session.tsv` and `acceptance-receipt.tsv` at the host-session root. The union
of dispatched systems must equal every system in `rows.tsv` for the shard.

## JMH Protocol

Starting five JMH forks for every row, each with ten one-second warmup and
measurement iterations, would make larger routes take several hours. The
bounded protocol keeps independent processes, full row coverage, and the same
iteration counts while avoiding a new forked JVM for every row:

- Qualification runs five independent top-level JVM process epochs. Each
  process invokes JMH with `-f 0` over the complete exact filter and executes
  ten warmup plus ten measurement iterations for every selected row. Before
  each process starts, the harness asks JMH to materialize its merged compiler
  controls and installs the resulting command file on the host JVM. This gives
  non-forked measurements the same benchmark-method inlining, JMH-stub
  exclusion, and compiler-blackhole treatment as forked JMH measurements.
- Pinned Joni comparators retain a proper fork boundary (`-f 1`) instead of
  sharing the Regulator host JVM. The three primary replicas therefore provide
  three isolated Joni JVMs on three hosts for every row, each with ten warmup
  and ten measurement iterations. Five forks on every one of the three hosts
  would provide 15 redundant JVMs per row and make the 184-row Trino operations
  comparator spend most of its time starting JVMs rather than measuring rows.
  A Joni row therefore has ten samples on each host, not the 50 samples used by
  the bounded Regulator process set. Rows over the 5% relative standard-error
  ceiling remain explicitly precision-rejected and are candidates for a
  targeted longer-protocol rerun.
- Each row's qualification protocol is frozen in `row-protocol.tsv`: how it
  executes (`jmh-processes`, `jmh-forks`, `native-bracket`, `rebar`, or
  `memory-census`), its process or fork count, warmup and measurement
  iteration counts and windows, and its relative standard-error ceiling.
  `run-shard.sh` reads these fields instead of deriving them, so a row keeps
  its protocol however its route is divided. `generate-row-protocol.py`
  derives the file from the rules hosts applied when each host measured whole
  routes, and `validate-row-protocol.py` regenerates and compares it, in the
  offline gate and in each real run before any JVM starts. A route whose
  candidate and Joni rows number at most 30 keeps one-second iterations.
  Larger routes use 50 ms iterations across five independent JVM processes;
  every row in that five-process set has 50 measurement samples and is
  rejected when its relative standard error exceeds 5%. Smoke keeps its single
  fixed protocol.
- The route count includes only the candidate system and Joni, except in the
  LIKE construction shards, which count every row. `trino-like` measures the
  `regulator` and Trino systems, so it counts none of its 36 rows and all of
  them keep the one-second protocol. Its static estimate also charges none of
  their 3,600 seconds of iterations.
- The first process records GC allocation metrics. The other four processes do
  not run the GC profiler because its in-process instrumentation perturbs the
  primary timing score. All five processes contribute primary timing samples.
- Compiler rows use 300 ms warmup iterations because their C2 transition is
  later than the matching loops. The additional warmup is charged to the host
  duration budget.
- Every representative whose row is frozen with 50 ms windows is compared with
  a full five-fork, ten-by-one-second protocol on every replica. The
  specialized side uses five independent JVMs with the same JMH compiler
  controls for this equivalence check. The `trino-like` entries in the matrix
  never run, because their rows keep one-second windows.
  `protocol-representatives.tsv` covers every bounded route on every replica
  and distributes every benchmark-family, system, and route combination across
  the three primary replicas. Confirmation reuses replica 1's matrix. Each
  representative must provide exactly five groups of ten measurements under
  each protocol, have at
  most 5% relative standard error under each protocol, and differ by at most
  5%; otherwise the host is rejected before its result can be accepted.
- An exact row that fails this equivalence gate is listed in
  `full-protocol-benchmarks.tsv` instead of weakening the gate. Qualification
  excludes that method from the main process set, reruns its other parameters
  with the bounded protocol, and runs the failing parameter with the full
  protocol. The normalizer requires five process groups for every resulting
  row while permitting those disjoint JSON row sets. A selector may list
  multiple comma-separated values for a parameter when the full-protocol rows
  form a Cartesian group; the configured full and bounded selections must be
  disjoint and together cover the method's complete manifest row set. This
  selection follows the benchmark row onto its configured classpath; a Trino
  benchmark on the special pinned classpath must generate the same bounded and
  full side files that the default candidate classpath generates.
- The released-1.0 smoke rejected `searchEasy2DfaBoolean(textSize=32768)`
  with an 18.6% protocol median difference on R8g. A subsequent 4096-byte
  representative differed by 46.1% on R8i's pure-Java route, with full-run
  process medians near 1.28 and 1.90 microseconds. These distributions do not
  establish that shorter iterations caused the different medians. The entire
  `BenchmarkRe2SearchExtra` family now uses full timing in eight
  `traditional-extra-*` shards. Each route has at most 30 JMH rows, and both
  applicable memory routes fit the unchanged 90-minute static host deadline.
  The split preserves every Java/native pair and input size. Original rejected
  measurements remain evidence; they are not accepted final samples.
- Numerical protocol rejection has exit status 42. It leaves the host
  unaccepted and the campaign incomplete, without terminating unrelated jobs
  or automatically retrying the rejected timings. Malformed evidence, semantic
  failures, and resource failures retain the shared fleet stop behavior.
- Fast allocating rows require special scrutiny because a 50 ms window can
  contain zero collections in most iterations and concentrate collection cost
  in one iteration that a median discards. The `countRegulator`
  `captureLateMatch` rows use the full protocol for this reason: they allocate
  208 bytes per operation, while their final C2 instruction stream is identical
  under forked and host-JVM execution.
- Smoke runs one top-level process with one 20 ms warmup and three 20 ms
  measurement iterations per row. It also primes the calibration workload once
  before the recorded before sample. Qualification uses only its recorded full
  before and after calibration runs.
- Calibration uses the `findLiteral` `KIB_LATE` workload. The formerly used
  compact-DFA workload has two stable process-local performance modes on C9g,
  so it cannot distinguish host drift from fresh-process code-layout variance.
- Every process writes a separate JSON file. Normalization requires identical
  row sets in every file, combines all five process groups before computing the
  row score, and obtains normalized allocation from the profiled process. The
  shards that run each workload in its own JVMs, `trino-specialized-patterns-a`,
  `-b`, and `trino-like-specialized`, hold one row per file, so their
  normalization checks each row's process-group count instead of identical row
  sets.
- The semantic gate is derived from the actual successful Surefire test cases
  and differential-verifier receipts. It is not a declaration that tests were
  run.

Top-level process epochs provide independent JVM, heap, JIT, and benchmark
state. `-f 0` prevents JMH from starting another JVM for each benchmark while
retaining JMH's setup, warmup, and measurement behavior inside the epoch. JMH
does not provide a command-line facility to randomize benchmark
enumeration, so the process metadata records
`jmh_row_order=jmh-deterministic-no-cli-randomization` rather than claiming
counterbalancing that did not occur.

Before execution, the dispatcher calculates a conservative static duration
estimate from:

- exact selected JMH row count
- every handler-level JMH invocation
- every independent measured process epoch
- a two-second JVM startup allowance per process
- a two-second startup allowance for each in-process invocation's
  compiler-hints JVM
- one proper-fork startup per selected Joni row and Joni fork
- a two-second startup allowance for each forked invocation's launcher JVM
- both qualification calibration invocations or all three smoke calibration
  invocations, including their parent and forked JVMs
- every representative full-protocol and bounded-protocol comparison, including
  all six top-level JVM process startups
- all warmup and measurement windows
- every selected traditional native-bracket row multiplied by the native
  repetition count and minimum measurement window
- a 900-second cold native RE2 build and process-startup allowance
- every selected Rebar system row multiplied by the protocol's maximum warmup
  and measurement windows
- a 900-second cold Rebar build and process-startup allowance
- every selected Rebar/Joni row multiplied by the fixed 30-second semantic
  verification timeout
- a 900-second pinned Trino preparation, build, and route-harness allowance for
  every host that performs that work
- a 900-second allowance for each retained-memory census process
- a 600-second host bootstrap allowance

These are conservative acceptance-gate allowances rather than predicted wall
times. Rebar measurement is bounded directly by its selected row count and
configured maximum windows; cold build and census work use explicit allowances
because their duration depends on the host and shared-work cache state. Shared
cold-build allowances are charged once across the serial routes. The host is
rejected before measurement if the complete route sum and bootstrap allowance
exceed 90 minutes. `run-metadata.txt` records each route estimate, while
`host-plan.tsv` and `host-plan.properties` record route order and the complete
host bound. Native RE2 and Rebar keep their checked-in specialized protocols
rather than being forced through JMH.

`unit_cost.py` estimates the measurement seconds of any set of rows from their
frozen protocols, with the same per-row, per-selector startup,
representative, and calibration rules. It leaves out the cold-build, pinned
Trino preparation, and host bootstrap allowances, which do not grow with the
rows a host measures, and the Rebar verification allowance, which grows with
the selected Rebar rows. `validate-shard-plans.py` checks the
model against every planned route on every replica. The route estimate
differs from it in three recorded ways: it charges none of `trino-like`'s
3,600 seconds of iterations, it charges the compiler warmup to the 26 native
`traditional-capture` rows that share the compiler benchmark names (325
seconds), and it also charges each full-protocol row the bounded windows that
row never runs (5 seconds each).

Measured-shard normalization requires exactly one process sample group in each
top-level JMH JSON file. Calibration retains JMH's ordinary forked shape: one
qualification JSON file contains five fork groups, all of which are included
in the calibration statistics.

### Engine Builds

A host may install the native RE2 benchmark, the Rebar engines and native
runners, and the pinned Trino harness from its platform's
[engine build](../aws/README.md#engine-builds) instead of building them. The
cold-build allowances above stay unchanged: a host whose native target differs
from the builder's builds the native engines itself, and the bound must hold
for that host too. Each route's `run-metadata.txt` records `native_re2_build`,
`rebar_build`, `rebar_native_build`, and `trino_harness_build` as
`prebuilt:<archive-sha256>`, `local`, or `not-used`; `engine-build.txt` in the
route records the host's native target and CPU identity against the builder's.
Routes from sessions before engine builds carry neither and remain valid.
Engine builds change where an engine binary came from, not what a route
measures or how it is accepted and reduced. The language comparison does not
use them.

## Work Units

A work unit is a set of one shard's rows that a host can measure between two
chained calibrations in about 15 minutes, with every comparison and scaling
fit it feeds complete inside it. `generate-units.py` writes `units.tsv` (one
line per unit with its counts and estimated seconds) and `unit-rows.tsv`
(each unit's owned rows, copied rows, and representatives; one row ID
contains a comma, so row lists are not packed into a column).
`validate-units.py` regenerates both files byte for byte and checks them
independently. `run-unit.sh` measures one unit; hosts do not yet pull units.

- Atoms are the rows that must share a unit: every system of one logical row,
  which keeps both routes, native RE2 brackets, Joni, Trino, and route
  controls beside their candidates; every point of a scaling group; the whole
  `retained-memory` shard; and each Rebar workload with all of its engines.
- A unit's share of a JMH method must stay one exact selector, the method and
  one Cartesian `-p` block for each system and frozen protocol. A method is
  split along at most one parameter, its most varied one outside its scaling
  sizes, so rows sharing that parameter's value are one atom. JMH splits a
  `-p` value list at every comma, so a parameter with a value containing one
  never splits a method, and a method with no other parameter stays whole.
- An atom over budget that holds native RE2 rows is split without its logical
  row links: each route's scaling groups become their own atom, still joined
  to any Joni, Trino, or route-control comparator, and each copies the native
  bracket rows it is compared against. The copy in the unit measuring the
  bracket's own route owns the row's sample; other copies only pair.
- Each shard is packed into `ceil(cost / 788 s)` units, the 900-second unit
  less one chained calibration, by placing atoms largest first, ties by
  smallest row ID, into the unit with the lowest estimate. When a unit holding
  several atoms exceeds the budget, the shard gets one more unit and is packed
  again; `trino-like` needs six rather than five. Costs come from
  `unit_cost.py`, including each unit's own selector startups and, for the
  most demanding of replicas 1 to 3, its representatives. The model counts a
  startup per method even where methods sharing one `-p` block run as one
  selector, a deliberately conservative allowance. Units are indexed by
  their smallest owned row ID, and `lifecycle-shared-cold` units are measured
  on the concurrency instance type.
- Each representative that runs, one whose row is frozen with 50 ms windows,
  belongs to the unit owning its row; replica 4 uses replica 1's. The
  `trino-like` and full-protocol entries never run and are not attached.
- A unit ID is the SHA-256 of the shard, the sorted owned and copied rows, the
  digest of their frozen protocols, the attached representatives, and the
  order rule, under which a unit sequences its rows as its route handler
  sequences a route, comparator order included. Changing any of them changes
  the ID.
- A unit over 900 seconds is a single atom measured alone, and
  `validate-units.py` allows only the unit IDs it lists: the 47-minute
  `retained-memory` shard, each 19-minute text size of
  `lifecycle-shared-cold` with both routes over five worker counts, and each
  17-minute route of a `traditional-extra-*` eight-size scaling group with its
  native bracket.

### Running a Unit

`run-unit.sh <unit-id> <smoke|qualification> <replica-id> <result-directory>`
plans the unit with `unit_session.py` and runs `run-shard.sh` once for each
route its rows need, so every route handler, verifier, and normalizer is the
one a host session runs. `BASELINE_PLAN_ONLY=true` plans without measuring.

- Each route reads the unit's expected rows. Its JMH runners execute the
  unit's selectors instead of the handler's whole filter: one exact method
  list, one Cartesian `-p` block, and the rows' frozen protocol each, so
  qualification runs each row at its own process count and windows, and smoke
  keeps the route's smoke windows. A method holding a value JMH cannot select
  runs whole with the handler's own arguments.
- A unit holding only object-row Java rows of a native-bracketed atom
  measures its copied native rows around them on the object-row route. Copied
  rows are measured and marked pairing-only in the unit's observed rows.
- Odd replicas run native access, then object row, with forward Joni and
  Rebar comparator order; even replicas reverse both, as the unit ID's order
  rule binds. A comparator order set in the environment that disagrees is an
  error.
- The first route calibrates before its rows and the last route after them,
  always in the native-access JVM so the two sides compare across routes. The
  unit's before/after calibration drift must be within 5%. Native bracket
  drift is kept per row; above 5% it leaves only the affected comparisons
  unresolved during reduction.
  Chaining replaces the before side with the previous unit's after.
- The route runs only the unit's representatives for its replica; replica 4
  runs replica 1's. A failed representative exits 42 and rejects only that
  unit attempt.
- Units of one shard on one host share a route's Surefire semantic tests. The
  first unit stores the reports and log under the shared work directory,
  keyed by host epoch, shard, route, handler, tests, and candidate and
  manifest identity; a later unit uses them only when every file matches the
  stored SHA-256 receipt. The native-access probe, preparations, and
  differential verifiers still run for every unit.
- Outside planning, `run-unit.sh` requires the attempt's host session,
  `RE2_CAMPAIGN_HOST_SESSION`, and lease sequence,
  `RE2_CAMPAIGN_LEASE_SEQUENCE`; the region defaults to the availability
  zone's. It runs itself under GNU `time` into `unit-capacity.txt` with the
  host session's capacity gate, combines the routes, and writes
  `unit-session.tsv`, `unit-evidence.tsv`, and, once `validate-unit-results.py`
  accepts the attempt, `unit-receipt.tsv`; see `ACCEPTANCE.md`.
- `validate-unit-plans.py` plans every unit for both protocols and replicas 1
  to 4 in process, and checks that the selectors expand to exactly the unit's
  rows under their frozen protocols, in replica route order, with the unit's
  representatives. It also plans a deterministic sample through `run-unit.sh`,
  one unit per route handler pairing plus `traditional-capture`, a unit with
  copied rows, and a whole-method unit, for qualification replicas 1 and 2 and
  smoke replica 4.

## Comparator Ordering

`JONI_COMPARATOR_ORDER=forward|reverse` controls the accepted measurement
order for `trino-operations`, `trino-specialized-patterns-a` and `-b`,
`trino-final-line`, `retained-memory`, and the Rebar shards. Forward runs
Regulator before Joni; reverse runs Joni before Regulator. The JMH systems use
separate exact invocations and JSON files. Retained memory uses separate
accepted census processes, and Rebar uses separate exact engine and workload
filters, in the same selected order.

Traditional native RE2 and Rebar retain explicit before/after native brackets.
The candidate and comparators always run on the same host and under the same
smoke or qualification protocol.

## Route Handlers

- `jmh` runs the checked-in candidate JMH suites selected by `jmh-suites.tsv`.
- `traditional` runs candidate JMH rows bracketed by pinned native RE2 where
  the route owns native rows.
- `trino-operations` runs exact Regulator and pinned Joni filters separately.
  Its 312 application-shaped rows rotate through changing Slice values; the
  older fixed-haystack public-operation rows remain diagnostic controls. The
  same handler runs `trino-specialized-patterns-a` and `-b`, whose routes each
  measure three Regulator variants: the scan-plan interpreter, the regular
  engine, and generated bytecode. The two shards divide the workloads so each
  host keeps headroom under its time limit; a workload's variants and Joni
  always share a host, and both shards' rows keep the
  `trino-specialized-patterns` suite. Each workload of each system, Joni
  included, runs as its own exact filter and set of JVMs, so every call site
  sees one pattern. Each system's workload results go through one
  normalization. Even replicas reverse the Regulator variants and their
  workloads; Joni's workloads keep their forward order, and
  `JONI_COMPARATOR_ORDER` still places Joni before or after all the variants.
- `trino-search-edges` runs the 168 Regulator-only search-edge rows through the
  generic JMH handler on both memory routes.
- `trino-final-line` compiles the checked-in harness against the exact pinned
  Trino archive and normalizes its physical method names to stable manifest
  operation IDs.
- `trino-like` runs the Regulator, Trino optimized, and Trino SQL methods as
  separate exact filters. For `trino-like-specialized` it runs
  `BenchmarkTrinoLikeTpch`'s interpreted, generated, and Trino SQL methods over
  the shard's TPC-H workloads, after its verifier checks every workload. Each
  workload of each method runs in its own JVMs and reports nanoseconds, and
  even replicas reverse the whole sequence, Trino SQL included.
- The `lifecycle` handler runs the candidate cache, reset, fallback, and
  concurrency JMH suite. Shared cold-cache waves use the separate
  `lifecycle-shared-cold` shard so both memory routes fit on each host within
  the 90-minute limit. The original `lifecycle` shard contains the other rows.
- `memory-census` runs independently normalized retained-memory evidence for
  Regulator and pinned Joni.
- `rebar` prepares the pinned corpus and engines, verifies the exact
  deterministic manifest partition, preserves the native-before/Regulator/
  native-after invocation, and runs applicable Joni rows in a separate ordered
  invocation. Untimed Regulator and Joni verification keeps each engine's heap
  size but disables heap pre-touch: Rebar's deadline includes JVM startup, and
  faulting unused heap pages can exhaust it before verification starts. Timed
  invocations explicitly enable pre-touch and retain their original heap,
  warmup, measurement and timeout settings. Both settings are recorded in the
  route metadata. The checksummed applicability ledger covers 238 logical
  workloads; 225 have Joni rows, while the 13 syntax or semantic
  incompatibilities remain explicit reporting events. A compatible Joni row
  that exceeds the 30-second verification or 120-second measurement limit is
  recorded as `did-not-finish` in `raw/rebar-outcomes.tsv`, with its phase.
  Measurement can time out after verification succeeds; neither outcome is
  reclassified as incompatible. Other curated verification failures are fatal.
  Recognized extended-corpus result
  mismatches are also recorded in `raw/rebar-outcomes.tsv` and excluded from
  performance comparison. Process failures, incomplete output, and other
  unclassified failures remain fatal.

## Evidence And Acceptance

Every successful route contains:

- `expected-rows.tsv`, selected directly from `rows.tsv`
- one raw JSON per JMH process epoch and external raw results under `raw/`
- normalized fragments under `normalized/`
- `semantic-gate.tsv`, written only after semantic checks pass
- exact, sorted `observed-rows.tsv`
- `calibration.tsv`, except in a unit's routes, whose calibrations the unit
  records once (see [Running a Unit](#running-a-unit)), and, when applicable,
  `native-bracket.tsv`
- `raw-artifacts.sha256`, which records raw file hashes separately from result
  identity
- `route-session.tsv` for deferred host execution
- candidate, protocol, and static-runtime provenance in `run-metadata.txt`

The host-session root also contains `host-capacity.txt`, produced by the pinned
GNU `time` package. It records total and before/after available memory, swap
state, OOM-kill counters, elapsed wall time, peak resident memory, command
status, and the required post-run memory headroom. The campaign controller
rejects a successful job if this capacity evidence is missing, malformed, or
fails any capacity gate.

The observed schema is `row_id`, `shard_id`, `system`, `score`, `score_unit`,
`allocation_bytes`, `result_checksum`, and `outcome`. `result_checksum` binds
all fields of the row's frozen manifest contract, not timing bytes or route-wide
verification outcomes. Each normalizer still requires an accepted semantic
receipt. A sibling timeout changes the retained execution evidence, not the
identity of an otherwise unchanged row. The campaign and reducer enforce pinned
source/runtime/comparator identities; `raw-artifacts.sha256` preserves raw
provenance, including the complete semantic receipt and outcome evidence.

Calibration drift above 5% rejects the route. Native before/after drift is
preserved per benchmark; drift above 5% marks only the affected rows and
comparisons unresolved during reduction. Missing `gc.alloc.rate.norm` rejects
a JMH row whose allocation contract is `recorded` or `allocation-free`; a
nonzero allocation-free observation is retained for the gap ledger rather than
treated as host corruption.

`BASELINE_PLAN_ONLY=true` validates manifest selection, route applicability,
protocol choice, and the static runtime bound without building or executing
benchmarks.

## Allocation Evidence

The checked-in Rebar and native RE2 result formats do not report allocation.
Their manifest rows therefore use `allocation_contract=not-measured` rather
than fabricating evidence from timing output. Regulator JMH rows retain their
`recorded` or `allocation-free` contracts and require `gc.alloc.rate.norm`.
