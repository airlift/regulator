# Baseline shard dispatch

`shard-dispatch.tsv` assigns each shard's rows to routes and systems.
`run-shard.sh` measures one route, and `run-unit.sh` runs it once for each
route a measured group of a [work unit](#work-units) needs, keeping route
artifacts under `routes/<route>`. Odd replicas run `native-access` first, while
even replicas run `object-row` first, so route startup and thermal effects do
not systematically favor one implementation. Expensive pinned Trino/Joni
preparation and the installed native comparator runners are shared through
`BASELINE_SHARED_WORK_DIR`; measurements and semantic receipts are never shared
between routes.

`freeze-candidate.sh` creates an immutable benchmark ref and records that
candidate's commit, complete tree, production tree, deterministic source
archive, and benchmark-manifest hashes. The campaign controller validates those
identities directly before execution. The pre-cleanup snapshot remains a
separate regression baseline and is not a prerequisite for freezing a later
candidate.

A route produces `route-session.tsv` but no acceptance receipt. `run-unit.sh`
verifies that all route sessions describe the same host identity, combines the
exact route rows, rejects duplicate or missing rows, and writes the group's
`unit-session.tsv` and, once accepted, its `unit-receipt.tsv`.

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
  would provide 15 redundant JVMs per row and make the 104-row Trino operations
  comparator spend most of its time starting JVMs rather than measuring rows.
  A Joni row therefore has ten samples on each host, not the 50 samples used by
  the bounded Regulator process set. Rows over the 5% relative standard-error
  ceiling remain explicitly precision-rejected and are candidates for a
  targeted longer-protocol rerun. The JDK comparator of the Java
  specialized-pattern shards has the same fork protocol as Joni: one JMH fork
  per host with ten warmup and ten measurement iterations, on Regulator's
  classpath.
- Native comparators, `native-re2` and `rust-regex`, run under
  `native-repetitions`: one runner process measures one row with Google
  Benchmark, warming it for the total of its ten warmup windows and then timing
  ten repetitions of one measurement window. Google Benchmark sizes the warmup
  and the first repetition with trial runs of growing iteration counts, each
  count aimed 1.4 times past the minimum CPU time, so the final run lasts about
  1.4 times that minimum after trials that together last up to one minimum
  more. Every later repetition reuses the first one's iteration count, so it
  too lasts about 1.4 windows. A native row thus has ten samples per host, as a
  Joni row does, and its score is the median repetition in ns/op. The runner
  records no allocation, so a native row's allocation is `not-measured`.
- Each row's qualification protocol is frozen in `row-protocol.tsv`: how it
  executes (`jmh-processes`, `jmh-forks`, or `native-repetitions`), its process
  or fork count, warmup and measurement iteration counts and windows, and its
  relative standard-error ceiling.
  `run-shard.sh` reads these fields instead of deriving them, so a row keeps
  its protocol however its route is divided. `generate-units.py` derives the
  file from the rules hosts applied when each host measured whole routes. A
  route whose candidate and Joni rows number at most 30 keeps one-second
  iterations.
  Larger routes use 50 ms iterations across five independent JVM processes;
  every row in that five-process set has 50 measurement samples and is
  rejected when its relative standard error exceeds 5%. Smoke keeps its single
  fixed protocol.
- The route count includes only the candidate system and Joni, except in the
  LIKE construction shards, which count every row. `trino-like` measures the
  `regulator` and Trino systems, so it counts none of its 36 rows and all of
  them keep the one-second protocol.
- The first process records GC allocation metrics. The other four processes do
  not run the GC profiler because its in-process instrumentation perturbs the
  primary timing score. All five processes contribute primary timing samples.
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
  5%; otherwise the unit attempt is rejected before its result can be
  accepted.
- Numerical protocol rejection has exit status 42. It leaves the unit
  unresolved and the campaign incomplete, without terminating unrelated work
  or automatically retrying the rejected timings. Malformed evidence and
  resource failures retain the shared fleet stop behavior.
- A failed check of a route's correctness stage has exit status 44: a
  semantic test, the DFA probe, a differential verifier or its count of
  verified cases, or the pinned Trino comparator's verification, including
  one that could not start. The
  campaign leaves the unit unresolved without retrying it, and its phase is
  not accepted.
- Fast allocating rows require special scrutiny because a 50 ms window can
  contain zero collections in most iterations and concentrate collection cost
  in one iteration that a median discards.
- Smoke runs one top-level process with one 20 ms warmup and three 20 ms
  measurement iterations per row.
- Every process writes a separate JSON file. Normalization requires identical
  row sets in every file, combines all five process groups before computing the
  row score, and obtains normalized allocation from the profiled process. The
  specialized-pattern shards run each workload in its own JVMs, so they hold
  one row per file, and their normalization checks each row's process-group
  count instead of identical row sets.
- The semantic gate is derived from the actual successful test cases, which
  the JUnit console launcher reports in Surefire's XML format, and
  differential-verifier receipts. It is not a declaration that tests were run.

Top-level process epochs provide independent JVM, heap, JIT, and benchmark
state. `-f 0` prevents JMH from starting another JVM for each benchmark while
retaining JMH's setup, warmup, and measurement behavior inside the epoch. JMH
does not provide a command-line facility to randomize benchmark
enumeration, so the process metadata records
`jmh_row_order=jmh-deterministic-no-cli-randomization` rather than claiming
counterbalancing that did not occur.

`unit_cost.py` estimates the measurement seconds of any set of rows from their
frozen protocols: every warmup and measurement window, a two-second startup
allowance for each JVM a selector or a Joni or JDK fork starts, and every
representative comparison. For Google Benchmark's sizing runs, it charges a
native row 2.5 times its warmup windows and 1.5 times its measurement windows.
It leaves out the pinned Trino preparation and host bootstrap, which do not
grow with the rows a host measures.

Measured-shard normalization requires exactly one process sample group in each
top-level JMH JSON file.

### Engine Builds

A host installs the candidate's compiled classes and the pinned Trino harness
from its platform's [engine build](../aws/README.md#engine-builds) instead of
building them, and runs no Maven. Each route builds its classpath from the
installed `regulator-classes/classpath.txt` and runs its semantic tests on it
with the JUnit console launcher, from the source root, selecting the test
classes `shard-dispatch.tsv` names as Surefire's `-Dtest` would. Each route's
`run-metadata.txt` records `regulator_classes_build` as
`prebuilt:<archive-sha256>`, and `trino_harness_build` and, for a specialized
shard's native comparator runner, `native_runner_build` as
`prebuilt:<archive-sha256>` or `not-used`; `engine-build.txt` in the route
records the installed build. A route fails, and is retried, when an engine it
needs is missing or the harness fails its identity check. Engine builds change
where the classes and engines came from, not what a route measures or how it
is accepted and reduced. The same build installs the native RE2 and Rust
runners on every unit worker, at `language-native/<runner>` under the shared
work directory, with `engine-build/native.sha256` naming their archive; a
specialized shard's native-access route runs its comparator's runner from
there.
A language unit runs the same runners.

## Work Units

A baseline work unit is a set of one shard's rows that a host can measure in
about 15 minutes, with every comparison and
scaling fit it feeds complete inside it. The build phase's one unit per
platform, `engine-build`, builds that platform's engines instead, and a language
plan packs its comparisons into units of its own; see the
[language guide](../language/README.md#plan-preparation).

`generate-units.py` writes `units.tsv` (one line per unit with its counts,
estimated seconds, and its measured groups' estimated seconds in group order)
and `unit-rows.tsv` (each unit's owned rows and representatives, each with its
measured group; one row ID contains a comma, so row lists are not packed into a
column). The generator refuses to write units that break a measurement
requirement: each row owned by exactly one unit, comparisons, scaling groups,
and logical rows inside one unit, one Cartesian selector block per method share,
no unit over the 15-minute budget, and measured groups that keep the same
requirements. The three tables are not checked in:
`freeze-candidate.sh` generates them into the candidate archive and beside the
baseline tooling, where the controller reads them, and the offline gate
generates them before its tests. `run-unit.sh` measures one unit, and a unit
worker host leases units one at a time (see
[Unit workers](../aws/README.md#unit-workers)).

- Atoms are the rows that must share a unit: every system of one logical row,
  which keeps both routes and the comparator beside their candidates, and every
  point of a scaling group.
- A unit's share of a JMH method must stay one exact selector, the method and
  one Cartesian `-p` block for each system and frozen protocol. A method is
  split along at most one parameter, its most varied one outside its scaling
  sizes, so rows sharing that parameter's value are one atom. JMH splits a
  `-p` value list at every comma, so a parameter with a value containing one
  never splits a method, and a method with no other parameter stays whole.
- Each shard is packed into `ceil(cost / 900 s)` units, the 900-second unit
  budget, by placing atoms largest first, ties by
  smallest row ID, into the unit with the lowest estimate. When a unit holding
  several atoms exceeds the budget, the shard gets one more unit and is packed
  again; `trino-like` needs six rather than five. Costs come from
  `unit_cost.py`, including each unit's own selector startups and, for the
  most demanding of replicas 1 to 3, its representatives. The model counts a
  startup per method even where methods sharing one `-p` block run as one
  selector, a deliberately conservative allowance. Units are indexed by
  their smallest owned row ID.
- Each representative that runs, one whose row is frozen with 50 ms windows,
  belongs to the unit owning its row; replica 4 uses replica 1's. The
  `trino-like` entries never run and are not attached.
- A unit ID is the SHA-256 of the shard, the sorted owned rows, the
  digest of their frozen protocols, the attached representatives, and the
  order rule, under which a unit sequences its rows as its route handler
  sequences a route, comparator order included. Changing any of them changes
  the ID.
- No unit exceeds 900 seconds.
- A unit is measured in groups, each uploaded as soon as it finishes, so a
  reclaimed Spot instance loses only the group it was running. A group is a
  union of atoms that also keeps each JMH launch, the methods sharing one
  selector, whole, so a candidate and its comparators always share a group.
  Groups are taken in order of their smallest row ID, and adjacent ones merge
  until each is estimated at 5 minutes or more, since each group pays its
  routes' correctness stage and JVM startups again; a short last group merges
  into the one before it, so a unit shorter than 5 minutes stays one group. A
  representative belongs to its row's group. Group IDs number the groups from 1
  in that order. The generated tables hold 43 groups for 38 units; most units
  are one group because one `-p` block spans their atoms.

### Running a Unit

`run-unit.sh <unit-id> <smoke|qualification> <replica-id> <group-id> <result-directory>`
plans one measured group of the unit with `unit_session.py` and runs
`run-shard.sh` once for each route the group's rows need, so every route
handler, verifier, and normalizer is the one a shard route runs.
`BASELINE_PLAN_ONLY=true` plans without measuring.
The `engine-build` unit runs the platform's engine build instead, and a unit of
an installed language plan runs with `fleet.py run-unit`, as the
[language guide](../language/README.md#work-units) describes; the rest of this
section covers baseline units.

- Each route reads the group's expected rows. Its JMH runners execute the
  group's selectors instead of the handler's whole filter: one exact method
  list, one Cartesian `-p` block, and the rows' frozen protocol each, so
  qualification runs each row at its own process count and windows, and smoke
  keeps the route's smoke windows. A method holding a value JMH cannot select
  runs whole with the handler's own arguments.
- Odd replicas run the unit's groups in group order, and within each group
  native access, then object row, with forward comparator order; even
  replicas reverse all three, as the unit ID's order rule binds. A worker
  resuming a replica keeps that order for the groups left. A comparator order
  set in the environment that disagrees is an error.
- Each route records the host's CPU steal while it measures. `run-shard.sh`
  reads the aggregate `cpu` line of `/proc/stat` before the route's
  representatives and after its last row, and appends the steal ticks, the
  line's eighth value, and the total ticks of all its values to
  `run-metadata.txt` as `cpu_steal_ticks` and `cpu_total_ticks`. Acceptance
  requires both, and the reducer's host check sums them per host; see
  `REPORTING.md`.
- The route runs only the group's representatives for its replica; replica 4
  runs replica 1's. A failed representative exits 42 and rejects only that
  unit attempt.
- Units of one shard on one host share a route's semantic tests. The
  first unit stores the reports and log under the shared work directory,
  keyed by host epoch, shard, route, handler, tests, candidate and manifest
  identity, and classes archive; a later unit uses them only when every file
  matches the stored SHA-256 receipt. The native-access probe, preparations,
  and differential verifiers still run for every unit.
- Outside planning, `run-unit.sh` requires the attempt's host session,
  `RE2_CAMPAIGN_HOST_SESSION`, and lease sequence,
  `RE2_CAMPAIGN_LEASE_SEQUENCE`; the region defaults to the availability
  zone's. It runs itself under GNU `time` into `unit-capacity.txt` with the
  host session's capacity gate, combines the routes, and writes
  `unit-session.tsv`, `unit-evidence.tsv`, and, once `validate-unit-results.py`
  accepts the attempt, `unit-receipt.tsv`; see `ACCEPTANCE.md`.
- `test_unit_session.py` plans every group of every unit for both protocols
  and replicas 1 to 4 in process, and checks that the selectors expand to
  exactly the group's rows under their frozen protocols, in replica route
  order, with the group's representatives. It also plans a deterministic
  sample through `run-unit.sh`, one group per route handler pairing, a later
  group of a unit, and any whole-method group, for qualification replicas 1
  and 2 and smoke replica 4.

## Comparator Ordering

`JONI_COMPARATOR_ORDER=forward|reverse` controls the accepted measurement
order for `trino-operations` and the specialized-pattern regex shards. It is
named for Joni, the comparator it first ordered, and orders every comparator the
same way: forward runs Regulator before the comparator; reverse runs the
comparator before Regulator. Each system uses separate exact invocations and
result files.

The candidate and comparators always run on the same host and under the same
smoke or qualification protocol.

## Route Handlers

- `trino-operations` runs exact Regulator and pinned Joni filters separately.
  Its 312 application-shaped rows rotate through changing Slice values. The
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
- `specialized-patterns` runs `re2-specialized-patterns-a` and `-b` the same
  way beside native RE2, `java-specialized-patterns-a` and `-b` beside the
  JDK, and `rust-specialized-patterns-a` and `-b` beside the `regex` crate.
  Before measuring a native comparator, it writes each workload's inputs and
  the values every Regulator variant returns for each source, and the native
  runner must print the same values. Unit hosts install no Rust toolchain, so
  the Rust shards need the engine build's runner. Each native row then runs in
  its own runner process at its planned selector's protocol. The JDK's values
  are checked by the shard's semantic tests.
- `trino-like` runs the Regulator, Trino optimized, and Trino SQL methods as
  separate exact filters. For `trino-like-specialized` it runs
  `BenchmarkTrinoLikeTpch`'s interpreted, generated, and Trino SQL methods over
  the shard's TPC-H workloads, after its verifier checks every workload. Each
  workload of each method runs in its own JVMs and reports nanoseconds, and
  even replicas reverse the whole sequence, Trino SQL included.

## Evidence And Acceptance

Every successful route contains:

- `expected-rows.tsv`, selected directly from `rows.tsv`
- one raw JSON per JMH process epoch and external raw results under `raw/`
- normalized fragments under `normalized/`
- `semantic-gate.tsv`, written only after semantic checks pass
- exact, sorted `observed-rows.tsv`
- `raw-artifacts.sha256`, which records raw file hashes separately from result
  identity
- `route-session.tsv`, which the unit combines with its other routes
- candidate, protocol, and static-runtime provenance, and the route's CPU
  steal ticks, in `run-metadata.txt`

The unit directory also contains `unit-capacity.txt`, produced by the pinned
GNU `time` package. It records total and before/after available memory, swap
state, OOM-kill counters, elapsed wall time, peak resident memory, command
status, and the required post-run memory headroom. The campaign controller
rejects a successful unit attempt if this capacity evidence is missing, malformed, or
fails any capacity gate.

The observed schema is `row_id`, `shard_id`, `system`, `score`, `score_unit`,
`allocation_bytes`, `result_checksum`, and `outcome`. `result_checksum` binds
all fields of the row's frozen manifest contract, not timing bytes or route-wide
verification outcomes. Each normalizer still requires an accepted semantic
receipt. A sibling timeout changes the retained execution evidence, not the
identity of an otherwise unchanged row. The campaign and reducer enforce pinned
source/runtime/comparator identities; `raw-artifacts.sha256` preserves raw
provenance, including the complete semantic receipt and outcome evidence.

Missing `gc.alloc.rate.norm` rejects a JMH row whose allocation contract is
`recorded` or `allocation-free`; a nonzero allocation-free observation is
retained for the gap ledger rather than treated as host corruption.

`BASELINE_PLAN_ONLY=true` validates manifest selection, route applicability,
and protocol choice without building or executing benchmarks.
