# Maintaining Regulator

Match the validation effort to the change. A documentation edit needs different
checks from a parser repair, a Unicode update, or a change to a DFA loop where
one extra instruction can affect performance.

## Current phase

Version 1.0 is published as `io.airlift:regulator:1.0`. The
[release benchmark report](benchmarks/RESULTS_1.0.md) records measured coverage,
findings, and remaining limitations. Preserve released API compatibility. Any
production repair belongs to a subsequent version and a separately identified
measurement campaign. Record collector revisions separately from the library
artifact and tagged production source.

Do not start speculative optimization work. A new optimization requires
either a material final-campaign gap or representative
production evidence.

## Protected areas

Treat these as performance-sensitive unless analysis proves otherwise:

- DFA transition, state-cache, and start-state loops
- NFA, OnePass, and BitState execution loops
- candidate-byte and literal scanners
- engine selection and capture-boundary recovery
- Slice range and UTF-8 decoding paths used by matching

Comments in these paths explain where performance depends on generated code.
Do not extract helpers, introduce polymorphism, reorder loads, add logging, or
make naming-only edits inside a protected loop without inspecting generated
code and running the relevant benchmarks.

## Parser frontends

`RegexpParser`, `TrinoRegexpParser`, and `JavaRegexpParser` each handle their
language's grammar, state, and errors. Every parse gets a private parser
instance, so there is no shared mutable parser state to synchronize.

The parsers share small, stateless utilities in `RegexpParserSupport` and
`Utf8` where all calling languages need the same behavior.
Keep these utilities free of dialect flags, callbacks, and per-token dispatch.
If one frontend needs different semantics, move that behavior back into the
frontend rather than obscuring the difference inside the shared helper.

When changing duplicated parser mechanics, assess all three frontends and run
their focused syntax and differential tests. Extract another shared helper only
when it has a clear language-neutral contract and makes each parser easier to
read; duplicated dialect logic is preferable to a parameterized parser core.

## Change classification

### T0: Mechanical

Examples: documentation, comments, test names, cold variable names, and dead
code outside execution paths.

Required:

- focused tests for changed code
- `git diff --check`
- no benchmark unless the change reaches generated code or dispatch

### T1: Semantic but cold

Examples: parser validation, error messages, options, public API behavior,
Unicode generation, and compiler logic outside hot loops.

Required:

- focused unit and differential tests
- complete RE2 test suite
- allocation or compile benchmarks when the change plausibly affects them

### T2: Routing or representation

Examples: engine selection, expression plans, capture workspace ownership,
DFA budgets, and scanner eligibility.

Required:

- a deterministic route or representation test
- affected semantic and allocation tests
- the exact affected benchmark and important protected controls
- focused before/after AWS measurements on R9g, R8g, and R8i

### T3: Protected execution loop

Examples: DFA transitions, NFA queue processing, OnePass actions, BitState
jobs, and vector/SWAR scanning loops.

Required:

- upstream-grounded correctness tests
- direct-engine and public-path benchmarks
- scaling and dense/sparse controls
- generated-code inspection when the mechanism depends on JIT shape
- AWS confirmation on R9g, R8g, and R8i

## Correctness gate

Every behavior change must identify its authority:

- pinned upstream RE2 for RE2 syntax and matching
- Java 25 for the supported Java frontend
- Trino's Joni fork and Trino function tests for `TrinoRegexp`
- Trino LIKE semantics for `TrinoLikePattern`

Before changing behavior, add a failing deterministic test based on the
appropriate authority. Exercise the public or engine path that failed, not just
a helper. For an optimization, verify route selection or data representation
directly rather than asserting a timing threshold. Build and test commands are
in [testing.md](testing.md).

## Performance gate

Use focused measurements during development:

1. pin the exact source revision used as the control
2. measure the exact benchmark the change is intended to affect
3. isolate and explain the intended mechanism
4. include important unaffected and fallback paths as protected controls
5. check allocation and retained memory where relevant
6. inspect scaling, not only one input size
7. confirm a retained T2 or T3 change on R9g, R8g, and R8i

A 2% change calls for investigation, not automatic acceptance or rejection.
Weigh these together for every material difference:

- baseline time, candidate time, ratio or percentage, and absolute delta
- normalized delta per byte, match, capture, state, or other scaling unit
- fixed overhead separately from a change in scaling slope
- the complete public operation, even when an isolated kernel improves
- instruction count, dependent loads, allocation, code size, and the selected
  algorithm or representation
- architecture differences and whether a microbenchmark's branch distribution
  represents changing production inputs
- the complexity and future maintenance cost of the candidate

Explain a regression even when its absolute cost is small. Identify the extra
instructions, loads, allocations, or work. A small fixed cost may be worth a
larger general win, simpler branch-free code, or better integration. A small
percentage can also hide an important per-byte or per-match cost, so check how
the difference grows with input size.

Do not use an aggregate win to excuse a regression that grows with the workload.
Record the reason for each accepted trade-off in a protected path in
`REGULATOR_DECISIONS.md`. Include the measured cause and tested alternatives
when they explain why maintainers should not repeat a rejected experiment.

[`benchmarks/METHODOLOGY.md`](benchmarks/METHODOLOGY.md) defines the measurement
protocol. Broad routing, representation, or execution-loop changes require the
affected qualification shards or complete qualification matrix in addition to
focused controls. Do not commit exploratory outputs. The preliminary 1.0 report
is an explicitly labeled publication snapshot, not an exploratory campaign log.

## Validating a performance change

Use this sequence for a focused repair or optimization. Full release collection
is a separate decision, not the default development loop.

1. Record the baseline and candidate revisions, the
   public operation, pattern/options, input distribution, and expected result.
   Trace the public entry point through planning, matching, capture recovery,
   and output construction. Identify other languages and APIs sharing the
   changed path, including fallback and nonzero Slice-offset cases.
2. Include the exact motivating case, representative
   affected workloads, and unchanged controls. Cover both memory modes and
   R8i, R8g, and R9g for routing or hot-loop changes. Set a round limit and
   machine-hour budget before execution. Use the cost guidance in the
   [AWS guide](../tools/re2-benchmark/aws/README.md#machine-hours-and-cost).
3. Add the deterministic path or behavior test and
   run the checks required by the change classification above. If changing
   collection tools, also run the offline gate in [testing.md](testing.md).
   Prepare pinned comparators and verify equivalent requested work before
   collecting either side's timings.
4. Freeze each measured source revision with the
   [candidate preparation procedure](../tools/re2-benchmark/aws/README.md#freeze-a-candidate).
   Keep archives, manifests, and results outside `target`. Plan without
   `--execute`; inspect the job count, selected cases, batching, host limits,
   and cost allowance before launching. The plan is complete only when every
   affected path and protected control has an identified measurement.
5. Use the language collector's
   [source brackets](../tools/re2-benchmark/language/README.md#local-verification)
   for candidate-before/control/candidate-after on the same host. Preserve all
   three legs; the ordinary export contains only the first candidate leg.
   [Focused controls](../tools/re2-benchmark/language/README.md#focused-source-bracket-controls)
   can add existing direct-engine JMH methods to that bracket. Use the normal
   campaign protocols and run profiles separately from timings.
6. Qualify the affected cases. A language fleet accepts a frozen
   `--selection` of case/language pairs. Baseline follow-ups use repeated
   `--shard` arguments and the same selection during validation and reduction.
   The [collector guide](../tools/re2-benchmark/language/README.md#aws-dispatch-and-recovery)
   documents both. These narrow qualification coverage; they do not replace
   same-host source brackets with comparisons between unrelated runs.
7. Compare absolute and normalized cost, ratios,
   allocation, scaling, and host variation using the performance gate above.
   List wins and losses separately for ordinary workloads and stress cases.
   Record accepted trade-offs in `REGULATOR_DECISIONS.md`, retain raw evidence,
   and verify cloud cleanup. Stop at the agreed decision or round limit.

For example, this plans two baseline shards on all three platforms without
launching machines. Choose shards based on the changed path, not this example:

```bash
python3 tools/re2-benchmark/baseline/run-campaign.py smoke focused-change \
  --shard traditional-search --shard expression-plans-a \
  --result-root /durable/path/focused-change/smoke --max-concurrent 6
```

Execution additionally requires `--execute` and the frozen candidate arguments
from the AWS guide. Primary collection requires the accepted smoke root and
the same selection. Resume an interrupted campaign with its original inputs;
do not change source, heap, selection, or concurrency inside a resumed run.
A new code change starts a new candidate and invalidates its affected results.

## Final qualification

Run the complete frozen publication campaign only after:

- source, API, tests, and documentation are frozen
- the full correctness matrix passes
- the worktree is clean
- comparator revisions and workload manifests are pinned
- the user-facing publication inventory is frozen independently of results
- no additional cleanup is planned

For 1.0, run the R9g, R8g, and R8i publication campaign after release, using
the published artifact for public-API measurements and recording its checksum
and release tag. Measure both native-memory and pure-Java routes. Run internal
diagnostics only when a qualification failure or explicit measurement-quality
question requires them; use the same tagged source and give them a separate
inventory, budget, and private archive. A later change invalidates at least the
affected publication shards; a broad or hard-to-isolate change invalidates the
complete publication campaign.

Commit one final public report and download containing the exact candidate
commit, hosts, JDK, compiler flags, comparator revisions, frozen public workload
identities, final measurements, aggregation rules, and separately listed
limitations. Index the private complete campaign, diagnostics, failed attempts,
and superseded cohorts without adding them to the public download. If the
candidate changes afterward, its affected results are no longer final.
Preliminary results may combine development revisions and targeted single-host
follow-ups, provided the report says so and preserves each row's actual source,
host coverage, and uncertainty. They do not satisfy the final campaign's
replication requirements.

## Release checklist

1. Confirm the intended `main` revision passed the full CI matrix, including
   both native-access settings on the supported JDKs. Check public contracts,
   dependency versions, and the README/report consistency gate. For 1.0,
   retain the explicit preliminary label until released-artifact collection
   is complete.
2. Check the [release workflow](../.github/workflows/release.yml) and
   [Maven publication settings](../.mvn/settings.xml). The workflow requires
   the GPG key/passphrase and Maven Central credentials named there, plus
   permission to push release commits and tags to `main`. Verify credentials
   are configured without copying their values into logs or documentation.
3. An authorized maintainer runs **Release new version** on `main`. It prepares
   and performs the Maven release, validates and publishes through Njord,
   pushes commits and tags, and creates a GitHub release. This is a publication
   operation, not a test run. Agents need explicit authorization to dispatch it.
   The separate snapshot workflow does not produce a final release.
4. Verify the resulting Git tag and release, Maven Central artifact, signatures,
   POM, and next development version. Check that a small downstream project can
   resolve the published coordinates and run the documented public API.
   If the workflow fails after staging or publication, inspect its logs, Njord
   state, repository tags, and Central before choosing a recovery step. Do not
   blindly rerun a potentially completed publication.
5. Run the publication qualification against the released artifact and tagged
   source following [final qualification](#final-qualification). Record the
   artifact checksum. Project the accepted measurements onto the frozen public
   workload source, write the complete capture to the private archive, and
   import only the curated public output using the
   [report instructions](../benchmark-report/README.md). Regenerate the README
   and run `npm run check`. Verify the deployed Pages report and public download
   after those changes are merged. Its refresh is separate from publishing the
   library artifact.

## Benchmark reproduction

The repository keeps benchmark source, frozen public workload inputs, native
build scripts, AWS orchestration, the public release data, and summary tools.
It indexes the private archive that holds exploratory outputs and the complete
campaign evidence.

A benchmark change must preserve:

- byte-identical Java and native inputs
- explicit warmup and measurement settings
- architecture and JDK identity
- native compiler optimization flags
- clean-source and checksum checks
- exact public workload-source identity and checksum
- clear separation between publication, measurement-quality, and diagnostic runs

## Trino adoption evidence

A Trino proposal needs more than throughput numbers. It must include:

- the supported Trino language and explicit unsupported constructs
- function-level differential coverage against Trino's Joni fork
- malformed UTF-8 behavior
- Slice-relative byte offsets and zero-copy capture behavior
- concurrency, memory-budget, and cancellation expectations
- final Intel and Graviton comparisons against Joni
- migration and fallback decisions owned by Trino

See [`integrations/TRINO.md`](integrations/TRINO.md). Wiring this library into
the Trino repository is a separate project.

## Upstream and JDK changes

When updating upstream RE2 or the supported JDK:

1. update the pinned revision or JDK contract explicitly
2. regenerate authoritative fixtures or Unicode data
3. review semantic differences before changing expected results
4. run the complete differential suite
5. rerun affected performance layers if code generation or tables changed

Never accept regenerated output solely because the generator completed.
