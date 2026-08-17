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
- Routes with at most 30 JMH rows retain one-second iterations. Larger routes
  use 50 ms iterations across five independent JVM processes. Every exact row
  in that five-process set has 50 measurement samples and is rejected when its
  relative standard error exceeds 5%.
- The first process records GC allocation metrics. The other four processes do
  not run the GC profiler because its in-process instrumentation perturbs the
  primary timing score. All five processes contribute primary timing samples.
- Compiler rows use 300 ms warmup iterations because their C2 transition is
  later than the matching loops. The additional warmup is charged to the host
  duration budget.
- Every route that uses the 50 ms protocol compares checked-in representative
  rows with a full five-fork, ten-by-one-second protocol on every replica. The
  specialized side uses five independent JVMs with the same JMH compiler
  controls for this equivalence check.
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
  row score, and obtains normalized allocation from the profiled process.
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
- one proper-fork startup per selected Joni row and Joni fork
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

Measured-shard normalization requires exactly one process sample group in each
top-level JMH JSON file. Calibration retains JMH's ordinary forked shape: one
qualification JSON file contains five fork groups, all of which are included
in the calibration statistics.

## Comparator Ordering

`JONI_COMPARATOR_ORDER=forward|reverse` controls the accepted measurement
order for `trino-operations`, `trino-final-line`, `retained-memory`, and the
Rebar shards. Forward runs Regulator before Joni; reverse runs Joni before
Regulator. The JMH systems use separate exact invocations and JSON files.
Retained memory uses separate accepted census processes, and Rebar uses
separate exact engine and workload filters, in the same selected order.

Traditional native RE2 and Rebar retain explicit before/after native brackets.
The candidate and comparators always run on the same host and under the same
smoke or qualification protocol.

## Route Handlers

- `jmh` runs the checked-in candidate JMH suites selected by `jmh-suites.tsv`.
- `traditional` runs candidate JMH rows bracketed by pinned native RE2 where
  the route owns native rows.
- `trino-operations` runs exact Regulator and pinned Joni filters separately.
  Its 312 application-shaped rows rotate through changing Slice values; the
  older fixed-haystack public-operation rows remain diagnostic controls.
- `trino-search-edges` runs the 168 Regulator-only search-edge rows through the
  generic JMH handler on both memory routes.
- `trino-final-line` compiles the checked-in harness against the exact pinned
  Trino archive and normalizes its physical method names to stable manifest
  operation IDs.
- `trino-like` runs the Regulator, Trino optimized, and Trino SQL methods as
  separate exact filters.
- The `lifecycle` handler runs the candidate cache, reset, fallback, and
  concurrency JMH suite. Shared cold-cache waves use the separate
  `lifecycle-shared-cold` shard so both memory routes fit on each host within
  the 90-minute limit. The original `lifecycle` shard contains the other rows.
- `memory-census` runs independently normalized retained-memory evidence for
  Regulator and pinned Joni.
- `rebar` prepares the pinned corpus and engines, verifies the exact
  deterministic manifest partition, preserves the native-before/Regulator/
  native-after invocation, and runs applicable Joni rows in a separate ordered
  invocation. The checksummed applicability ledger covers all 238 logical
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
- `calibration.tsv` and, when applicable, `native-bracket.tsv`
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
