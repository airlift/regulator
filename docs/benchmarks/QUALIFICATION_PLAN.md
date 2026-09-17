# Regulator performance qualification plan

Version 1.0 is released. This plan records the qualification requirements for
measurements of the published `io.airlift:regulator:1.0` artifact. The
[release results](RESULTS_1.0.md) report the completed campaign and its
[measurement-quality follow-up](MEASUREMENT_QUALITY.md). The preliminary
development measurements remain a separate historical capture.

Record the artifact checksum and release tag. Measure the frozen user-facing
workload inventory through public APIs with that artifact. Trino operations are
part of the comparison; integration into Trino is a separate project. Run
internal-engine diagnostics from the same tag only when a qualification result
or explicit measurement-quality question requires them.

For machine-hour and dollar planning, see the
[AWS cost guide](../../tools/re2-benchmark/aws/README.md#machine-hours-and-cost).
The 1.0 campaign uses Oregon Spot only, with no On-Demand fallback. Freeze the
publication job plans and shared admission budget before collection. Give any
conditional diagnostic campaign its own inventory, budget, and archive.

During development, use the focused checks in the
[maintenance guide](../MAINTAINING_REGULATOR.md). Repeating the full matrix
after every edit is expensive and produces results the next change may
invalidate.

## Objectives

The release campaign must answer four user-facing questions:

1. How do reused public Slice operations compare with native RE2, JDK Pattern,
   Joni, and Trino LIKE?
2. What overhead do compilation, first use, and repeated use add?
3. How do the Trino-compatible extraction, split, and replacement operations
   compare with Joni?
4. How does Regulator behave on the selected user-relevant adversarial cases
   across modern Intel and Arm Graviton systems?

The supporting qualification must also establish equivalent work, correctness,
independent-host coverage, measurement uncertainty, and both the native-access
and pure-Java memory routes. It does not need to answer every internal scaling,
compiler, allocation, retained-memory, concurrency, or route-selection question
on every release. Open a separate diagnostic investigation when the public
evidence fails a gate or cannot support a claimed explanation.

Explain each material difference in terms of algorithms, memory access,
allocation, dispatch, or generated code. A ratio alone does not answer these
questions.

## Entry gate

Freeze one candidate commit only after all of the following are true:

- the complete Maven build passes
- review-readiness cleanup and final review repairs are complete
- the RE2 differential, exhaustive, randomized, native-golden, and Trino
  compatibility suites pass
- the public API and benchmark corpus will not change during a comparison run
- the reviewer-facing commit stack and current documentation agree with the
  candidate source
- pinned native RE2 and every Java comparator can be built reproducibly
- benchmark methods consume their results and have smoke tests for every
  parameter value
- unsupported syntax is classified before timing; it is never recorded as a
  performance failure

Any code change made while investigating a result creates a new candidate.
Rerun at least the affected shards; broad or hard-to-isolate changes require a
complete campaign, as defined in
[`../MAINTAINING_REGULATOR.md`](../MAINTAINING_REGULATOR.md#final-qualification).
Every result retains the identity of the candidate actually measured. Results
for changed code cannot qualify the new candidate without a rerun.

## Comparators

Build and record exact revisions for the comparators used by the frozen public
inventory:

1. pinned native RE2 at `972a15cedd008d846f1a39b2e88ce48d7f166cbd`
2. Regulator through the public Slice API
3. Regulator through `TrinoRegexp`
4. the Joni version used by the selected Trino revision
5. Regulator through `JavaRegexp`, compared with the pinned JDK's
   `java.util.regex.Pattern`
6. Regulator through `TrinoLikePattern`, compared with Trino's actual SQL LIKE
   route

Direct engine entry points belong to conditional diagnostics. Pin and record
them when such a diagnostic is required; do not add them to the routine public
release inventory.

Native and Java benchmarks must use identical pattern bytes, input bytes,
search windows, anchors, expected results, and deterministic random seeds. The
native comparator runs as a direct C++ executable. An FFM binding would be a
separate integration design and benchmark; it would not replace the direct
native engine baseline.

The language matrix and representation rules in
[`LANGUAGE_COMPARISON_PLAN.md`](LANGUAGE_COMPARISON_PLAN.md) also apply. In
particular, the JDK comparator receives precomputed `String` values representing
the same logical Unicode text; decoding is not included in its engine timing.
Retain the full public collection independently of the final page layout,
including unsupported mappings, reproducible timeout receipts, raw
per-fork/per-iteration measurements, and normalization denominators.
Verification failures block publication. Preserve campaign-only diagnostics in
the private archive rather than the public download.

The shared everyday and lifecycle collector is documented in
[`tools/re2-benchmark/language`](../../tools/re2-benchmark/language/README.md).
It includes explicit bulk language mappings, original-source result and byte
trace verification, raw-evidence export, same-host batch planning, and
archive-based worker builds. The shared controller schedules these suites with
`--language-plan`; its existing baseline shards remain a separate required
campaign. Both campaigns must measure the same released library artifact and
production source. Record and freeze each suite's collector revision separately;
a collector repair does not change the measured release identity. Local
verification and controller tests establish collection readiness, not completed
AWS qualification or measured performance.

## Host matrix

Use dedicated, non-burstable AWS instances with local execution on:

- one Intel R8i instance
- one Graviton5 R9g instance as the primary current Arm target
- one Graviton4 R8g instance as the production and generational Arm baseline

The exact instance types, AMIs, and JDKs are pinned in
[`platforms.tsv`](../../tools/re2-benchmark/baseline/platforms.tsv).
Ordinary workers use `r8i.large`, `r8g.large`, and `r9g.large`, each with two
vCPUs and 16 GiB RAM. The `lifecycle-shared-cold` shard uses the corresponding
8-vCPU `.2xlarge` type and affinity `0-7`; ordinary work uses affinity `0`.
The measured heap is 8 GiB. Forked JMH launchers use 64–256 MiB, with the
full heap and memory-mode options explicitly passed to the measured fork.
Nonforked measurements retain the full heap in the running JVM.

Review those pins before freezing a new campaign; changing hardware requires
updating the manifest and validating it, not overriding a worker silently.
Prefer equivalent vCPU and memory sizes, avoid burstable families, and use one
NUMA node. Record:

- instance type, region, availability zone, AMI, kernel, and CPU model
- architecture, sockets, cores, threads, cache sizes, NUMA topology, and
  microcode
- JDK vendor and build, JVM flags, heap size, and compressed-oops state
- C++ compiler and version, CMake version, optimization flags, and linked C++
  library versions
- benchmark commit, comparator commits, and corpus checksum

The campaign freezes the effective `BENCHMARK_HEAP_SIZE`, defaulting to `8g`,
before launching work. Resume, later phases, recovered sessions, and reduction
must use the same heap setting. Route metadata and acceptance receipts record
it separately from each benchmark's semantic checksum. Receipts or campaign
state without this setting are not resumable under the current protocol;
start a new campaign instead of mixing old and new evidence.

Joni's Rebar verification and measurement remain bounded by their existing
30-second and 120-second limits. A classified timeout is reported as
`did-not-finish`, with no performance score. Successful siblings remain usable.
If verification classifies every Joni row, no Joni measurement process runs.
Malformed output, unknown errors, and unexpected process failures remain fatal.

Report R8g and R9g independently; do not combine them into one Arm aggregate.
Record the reported CPU frequency, cache topology, memory generation, and
available vector instruction capabilities so differences between Graviton
generations can be attributed rather than treated as run-to-run noise.

Pin single-threaded runs to one logical CPU and verify that no second benchmark
uses its sibling thread. Disable unrelated agents and scheduled work. Use a
fixed CPU power policy where the platform permits it. Reboot or replace the
instance between independent host sessions.

Run three independent host sessions for Intel, R8g, and R9g. A session is a
fresh instance or reboot followed by the complete randomized benchmark order.

## Workload manifest

The populations, comparator applicability, and family-balanced reporting rules
are defined in [`WORKLOAD_ORGANIZATION.md`](WORKLOAD_ORGANIZATION.md). Raw row
count is never a workload weight.

Store the final manifest as data, not duplicated Java and C++ constants. Every
case has a stable identifier and records pattern bytes, input-generation seed,
input size, operation, anchor, encoding, capture count, expected match ranges,
and expected output checksum.

Required dimensions are:

| Dimension | Cases |
|---|---|
| Input size | Empty, 8 B, 64 B, 512 B, 4 KiB, 32 KiB, 256 KiB, 2 MiB, and 16 MiB where practical |
| Match location | No match, beginning, middle, end, and complete input |
| Match density | Zero, one, sparse, dense, and empty matches |
| Captures | None, group zero, one capture, many captures, and unmatched alternatives |
| Pattern family | Literal, character class, alternation, repetition, counted repetition, anchors, dot-star, Unicode, case folding, required prefix, and high DFA-state patterns |
| Input bytes | ASCII UTF-8, multi-byte UTF-8, Latin-1, NUL, and malformed UTF-8 where defined |
| Cache state | Cold DFA, warm DFA, reset/thrash fallback, and shared concurrent cache |
| Compilation | Cold compile, repeated compile, and precompiled matching |

Cover every execution engine, public operation, optimization route, fallback,
and resource boundary represented in the test and benchmark suites. Do not tune
the manifest around the current implementation. Add representative Trino
workload cases from the shared function corpus and, if available, anonymized
production pattern families and value-size distributions.

Pin a Rebar revision separately and retain its curated definitions, inputs, and
expected results unchanged. Classify unsupported syntax and semantic differences
before measurement. Report common-engine intersections instead of assigning a
penalty to engines that intentionally implement different languages or match
reporting contracts.

## Benchmark layers

### Engine layer

Measure parser, simplifier, compiler, DFA, NFA, OnePass, BitState, prefix
scanning, reverse search, capture extraction, DFA cache reset, and set matching.
These measurements diagnose why an end-to-end result differs; they are not the
release score by themselves.

### Public Slice layer

Measure boolean partial/full matching, caller-owned capture buffers,
`MatchResult`, matcher construction, matcher reset, and repeated `find`. Verify
that logical non-zero Slice offsets do not cause copies or slower fallback
paths.

### Trino operation layer

Measure complete behavior for contains, count, first and Nth position, extract,
extract-all, split, template replacement, and lambda-replacement match
iteration. Isolate regex work from an arbitrary SQL lambda body. Include no
match, one match, dense matches, empty matches, null captures, and multi-byte
input.

This layer qualifies the library against representative database operations. It
does not include Trino module wiring, SQL registration, block integration, or
deployment work.

The application matrix rotates through independent short and medium Slice
values for each compiled pattern, including nonzero logical offsets. Report
the fixed-input Trino and final-line diagnostics separately. These application
workloads are designed examples, not a measured distribution of production use.

### Rebar cross-engine layer

Use the long-lived JVM runner for Rebar's KLV protocol to run all 41 workloads
in the pinned native RE2 curated intersection. Include every applicable
supported compile, count, span, capture, and grep model through the public Slice
API. Report an extended compatible non-curated pass separately. Keep these
official measurements separate from the Rebar-derived JMH routes used to
diagnose DFA eligibility and code shape.

Measure pinned Trino Joni for every workload/model identity declared compatible
by the exhaustive applicability ledger. Keep its invocation separate from the
unchanged native-before/Regulator/native-after bracket, and report
not-compatible and bounded-time non-completion states without a score.

### Resource and concurrency layer

Measure:

- allocation per operation for boolean, capture, repeated-match, and result APIs
- retained size of compiled patterns before and after lazy reverse compilation
- cold and warm DFA cache size and reset behavior
- throughput scaling with one compiled pattern shared by 1, 2, 4, 8, and all
  physical cores
- latency distribution while cache resets and fallback occur

## Measurement protocol

### Java

Use JMH with result consumption through `Blackhole` or returned values.
Follow the authoritative
[baseline dispatch protocol](../../tools/re2-benchmark/baseline/SHARD_DISPATCH.md#jmh-protocol)
for baseline smoke, bounded Regulator measurements, Joni forks, and full-protocol
exceptions. Language smoke and primary use the separate frozen
[language protocol](../../tools/re2-benchmark/language/README.md#aws-dispatch-and-recovery);
language smoke is not an abbreviated timing protocol. Preserve heap settings,
executed suite order, and allocation measurements in the receipts and raw data.

Capture JMH JSON, GC-profiler allocation data, JVM compilation logs for a
calibration fork, and JFR or async-profiler recordings for material gaps.
Confirm tier-4 compilation of hot methods. Do not combine cold compilation and
steady-state matching in one score.

### Native

Use Google Benchmark with the same data manifest, result checksums, thread
counts, minimum duration, and at least five repetitions. Record JSON output and
the exact release build flags. Prevent whole-operation elimination by consuming
match ranges or output checksums.

### Statistics

For the original baseline capture, report each system's median, observed host range, coefficient of variation,
throughput or time, bytes per second where meaningful, and allocation per
operation. The [reducer's statistics policy](../../tools/re2-benchmark/baseline/REPORTING.md#aggregation)
does not report confidence intervals from only three or four hosts, and
iterations are not independent hosts. Compare scaling across input sizes as
well as ratios. Leave a result unresolved when host-to-host variation overlaps
the claimed difference.

Investigate any of these before accepting a number:

- either implementation appears more than 5x faster
- expected constant-time behavior scales with input size
- expected linear behavior appears constant
- a ratio changes materially with input size
- coefficient of variation exceeds 5%
- allocation appears in a documented allocation-free path

## Acceptance criteria

- No non-capturing boolean operation allocates per match.
- Caller-owned capture and reusable matcher paths allocate no per-match result.
- Slice and Trino paths do not copy input or convert it to `String`.
- Java and native RE2 have the same scaling class for every common case.
- Every supported native RE2 case is at least as fast through the public Slice
  API, unless an exception is explicitly approved with a quantified cause.
- The Trino adapter is faster than Joni for the agreed common workload. Syntax
  unavailable to RE2 is reported separately.
- Every applicable Rebar curated workload and supported model is reported, with
  individual results retained alongside any geometric summary.
- Evaluate architecture-specific regressions using the quantified tradeoff
  policy in [`../MAINTAINING_REGULATOR.md`](../MAINTAINING_REGULATOR.md#performance-gate).
  Consider both relative and absolute cost, scaling, and the measured cause;
  record any explicitly accepted tradeoff rather than treating every loss as
  an automatic veto.
- Compile budgets, retained memory, and concurrent cache behavior remain within
  their documented contracts.

## Gap investigation

For each failed criterion:

1. reproduce the gap on both architectures and verify input/result equivalence
2. identify whether the difference is algorithmic, API overhead, allocation,
   memory locality, synchronization, or generated code
3. compare scaling and engine selection with pinned native RE2
4. profile the complete operation, then isolate the responsible component
5. inspect C2 and native assembly when profiles do not explain the cycles
6. add a direct code-path test before implementing an optimization
7. rerun the affected microbenchmark and its end-to-end Trino operation
8. rerun the affected qualification shards before accepting the change; broad
   or hard-to-isolate changes require the complete matrix, as specified by the
   entry gate

Keep rejected experiments in local investigation notes while the candidate is
being tuned. Only durable implementation constraints belong in source comments,
`REGULATOR_DECISIONS.md`, or maintainer documentation.

## Required artifacts

Keep one immutable working directory per candidate commit containing:

- environment manifests for every host session
- comparator revisions and build logs
- workload manifest and checksum
- raw JMH and Google Benchmark JSON
- allocation, JFR, profiler, and assembly artifacts used in conclusions
- normalized comparison tables generated from raw data
- a gap ledger with status, root cause, evidence, and disposition
- a final report separating engine, Slice API, and Trino-operation conclusions

Keep any conditional diagnostic campaign in a separate directory with its own
inventory and budget. Do not merge its rows into the publication inventory.

Commit the final decision-focused report, normalized machine-readable tables,
environment manifests, workload checksum, and comparator revisions. Large raw
profiles and benchmark output may be stored externally when the report records
stable checksums and retrieval information.

Keep scripts that create the hosts, build all comparators, run the randomized
matrix, validate result checksums, and generate tables. Manual shell history is
not a reproducible campaign.

## Execution sequence

1. Review and freeze this plan, acceptance thresholds, comparator revisions,
   and workload manifest.
2. Add or update benchmark harnesses and run local smoke tests only.
3. Provision Intel and Graviton hosts and capture environment manifests.
4. Run correctness preflight on both architectures.
5. Run three independent randomized publication sessions per architecture.
6. Normalize results and open a gap ledger before changing code.
7. Investigate and fix gaps one at a time with both correctness and benchmark
   gates.
8. If the candidate changed, recollect the invalidated coverage according to
   the entry gate; otherwise retain the completed qualification.
9. Project the accepted measurements onto the frozen publication inventory,
   archive the complete campaign privately, and commit the decision-focused
   report and required reproduction metadata.

## Measurement-quality follow-up

The original job inventory above remains the reproduction contract for its
archived capture. The final public report applies the mean-cost estimator and
approximate interval rules in [the methodology](METHODOLOGY.md#statistics).
Selected new measurements use the [isolated baseline collector](../../tools/re2-benchmark/baseline/REMEASUREMENT.md)
and frozen language `steady-state-v1` profiles. Keep original, diagnostic and
replacement evidence separately identified.

Before expanding a replacement fleet, require a complete representative
integration cohort across all target CPUs, independently validated source and
release identities, exact workload coverage, all process samples, memory and
CPU bounds, recovery and verified cleanup. Inspect temporal traces as well as
aggregate drift. Opposing slow periods can cancel in a median signed shift.
Native controls must match the affected workload when testing an environmental
hypothesis. Stable tiny controls do not establish stability for large memory
working sets.

Do not classify a noisy valid result as an infrastructure failure or retry it
until its interval narrows. Longer windows, more independent starts or different
host configurations must answer a recorded measurement question. Preserve all
attempts and test the changed protocol before accepting replacement evidence.
