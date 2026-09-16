# Language comparison collection

This suite measures ordinary regex operations and pattern lifecycles across
the three regex languages. Run it alongside the baseline, Rebar,
Trino-operation, and LIKE shards, not instead of them.

The current corpus is the 13 workloads in `everyday-trino-workloads.tsv`, with
eight rotating inputs each. Each workload runs through:

| Language | Regulator API | Comparator |
|---|---|---|
| RE2 | `Re2` | Pinned native RE2 |
| Java | `JavaRegexp` | JDK `Pattern` |
| Trino | `TrinoRegexp` | Pinned Airlift Joni |

Regulator runs with native access enabled and disabled. The comparator runs
once per language and host, and supplies the comparison for both modes.
Each engine measures compilation, single-use contains and count, and reused
contains and count. That produces 585 engine observations and 390 paired rows
per architecture. These counts describe this additional suite, not the full
qualification campaign.

## Measurement contracts

JDK patterns and inputs are decoded strictly to `String` before timing.
Regulator, Joni, and native RE2 receive identical UTF-8 bytes. Input rotation
includes nonzero Slice offsets. No encoding conversion is timed.

Single use includes compilation and one public operation. Reused measurements
retain the compiled pattern. RE2 and Java counts use their public `count` methods;
JDK reuses `Matcher.reset`; Trino uses its public `contains` and `count`
operations. Joni constructs a matcher for each byte-array input as its Trino
route does. Compilation does not construct a matcher. Native compilation
includes destruction of the compiled object; Java allocation and amortized GC
are recorded by JMH. These lifetime costs are not assumed to be identical.

Bulk boolean-line operations use `Re2.find` and `JavaRegexp.find`, not a
boundary-producing matcher. Bulk counts use the public count methods. The
separate `BenchmarkLanguageBulk.iterateMatches` diagnostic retains the original
RE2/Java count-by-iteration path; it requires a `count` workload and is not part
of the primary collector. Historical count-by-iteration measurements remain
distinct from public-count measurements even when their input/result is identical.

Verification checks existence, counts, and the ordered bytes of every whole
match, independently of timing. Each of the five timed operations also has its
own isolated preflight and receipt. An iteration timeout does not imply that
compilation or contains timed out. Timed-fork setup checks only the selected
operation, so it cannot introduce a different operation's timeout.
Subgroup captures, match offsets, replacement, and split are not result demands
in this suite. Their existing dedicated
benchmarks remain required. A recorded language translation must preserve this
suite's operation contracts; successful parsing alone is insufficient.

Compilation has no input-byte denominator. Other operations record the mean
input size of the complete round-robin rotation, not a claim about how many
bytes the engine actually scans. Count operations also record the expected
mean match count. Raw fork/iteration samples and allocation remain in the export
so ratios, absolute deltas, uncertainty, and crossover estimates can be calculated
later. Do not flatten all forks into independent observations for confidence
intervals.

## Local verification

For bounded optimization investigations, a source manifest may include
`source_control` with `archive_sha256` and `provenance_sha256`. Supply the frozen
Git archive at `source-control/source.tar.gz` and its candidate receipt at
`source-control/provenance.tsv` beside that manifest. The fleet carries one
checked copy per host batch. This option is off for ordinary collection.

The worker builds both revisions before timing, rejects changed benchmark
contracts, and executes candidate-before, control, and candidate-after on the
same isolated host. Each leg runs its own verification and fresh JVM forks with
the unchanged measurement protocol. `results`, `control-results`, and
`after-results` preserve the three sets of raw samples. `source-bracket.json`
records completion and both source identities; a missing receipt is not a
completed bracket. Ordinary fleet export includes only the first candidate leg;
investigation analysis must examine all three legs. Optional profiles start
after all timed legs finish.

Use a patched Python with `tarfile` extraction filters, such as Python 3.9.17+
or 3.12+. Expanding upstream TOML on the preparation machine additionally
requires Python 3.11+. The workers do not parse TOML.

Build the Java and pinned C++ runners, then prepare an immutable input manifest:

```sh
./mvnw test-compile
./mvnw -q dependency:build-classpath -DincludeScope=test \
  -Dmdep.outputFile=target/language-classpath.txt
RE2_BENCHMARK_TARGETS=language_benchmark tools/re2-benchmark/build.sh
python3 tools/re2-benchmark/language/collection.py prepare \
  --manifest-directory target/language-manifest
```

The build records `language_benchmark.build.json` beside the native executable.
It includes binary and source hashes, dependency revisions, compiler commands,
and the CMake configuration. Rebuilding the C++ target directly requires
refreshing this receipt with `native_build.py`.

Run deterministic verification without collecting timings:

```sh
LANGUAGE_CP="target/test-classes:target/classes:$(<target/language-classpath.txt)"
python3 tools/re2-benchmark/language/collection.py verify \
  --manifest-directory target/language-manifest \
  --results target/language-verification \
  --classpath "$LANGUAGE_CP" \
  --native-runner target/re2-benchmark-build/language_benchmark
```

Preparation and verification refuse to reuse an existing output directory.
The combined match trace and each operation preflight run in separate processes.
A timeout requires two attempts in the same phase at the same frozen limit.
An operation is reported as `did-not-finish` only with its own timeout evidence.
The measurement process has a separate one-hour ceiling for all five forks.
Each fork can include startup, compilation, an operation check, ten warmup
iterations, and ten measurement iterations. A corpus pass that finishes inside
the 30-second execution limit can still make a full measurement take many
minutes. The ceiling does not shorten those iterations or change the single-call
verification limits. Exceeding it is a collection failure, not evidence that an
individual regex operation did not finish. Recovery with a changed ceiling
requires a new frozen manifest and retains the original failed evidence.
Startup failures, process errors, and mismatched results fail collection.
They are not incompatibility or performance outcomes. Record known unsupported
mappings as `not-compatible` in the manifest before verification.

Tests:

```sh
./mvnw -Dtest=TestLanguageBenchmark test
python3 -m unittest discover -s tools/re2-benchmark/language -p 'test_*.py'
LANGUAGE_NATIVE_RUNNER=target/re2-benchmark-build/language_benchmark \
  python3 -m unittest discover -s tools/re2-benchmark/language -p 'test_native_*.py'
```

## Target-host measurement and saved data

On a qualification host, first build the clean candidate with a JVM receipt:

```sh
python3 tools/re2-benchmark/language/jvm_build.py \
  --output-directory target/language-jvm-build
LANGUAGE_CP="$(<target/language-jvm-build/classpath.txt)"
```

The build extracts the exact candidate archive into a new directory and runs
Maven there. It does not clean the original checkout's `target` directory or
remove existing native binaries, verification, or result files. The receipt
records the source tree, selected JDK, ordered classpath hashes, and comparator
pins. The actual Joni jar must match the pinned checksum. A missing receipt,
changed classes, changed classpath order, different source tree, or substituted
comparator blocks measurement. The output directory must not already exist.

AWS workers receive an extracted archive rather than a Git checkout. On those
workers, pass `--source-archive` and `--candidate-provenance` to both
`jvm_build.py` and `collection.py measure`. The provenance is the TSV produced
by `baseline/freeze-candidate.sh`. Archive mode checks the archive checksum,
embedded Git commit, reconstructed Git tree, and extracted file contents and
modes. Extra files outside `target` are rejected. It does not create synthetic
commits or trust a source SHA supplied without its archive. The ordinary Git
checkout path remains available for standalone qualification hosts.

Set `PYTHONDONTWRITEBYTECODE=1` before running any Python tools in an extracted
worker source tree. Otherwise imports create `__pycache__` directories that
the frozen-source check correctly rejects as extra files. Do not exempt caches
from source validation or reuse an already-modified extracted directory.

Run `verify` with this classpath, then run `measure` with the same manifest,
result directory, classpath, and native runner arguments, plus `--platform r9g`,
`r8g`, or `r8i` and
`--jvm-build-receipt target/language-jvm-build/jvm-build.json`.
It refuses non-Linux hosts, a dirty source tree, changed runner binaries, the
wrong pinned JDK, or a mismatched EC2 instance identity. Verification must be
performed on that host with those binaries first.

JMH uses five forks, ten one-second warmups, ten one-second measurement
iterations, an 8 GiB heap, G1, and the GC profiler. Native RE2 uses five Google
Benchmark repetitions with a ten-second warmup and at least one second of
measurement. Native results use elapsed time. Both protocols and timeout limits
are saved in the manifest. An aggregate measurement-process timeout is a
collection failure, not evidence that a single regex call timed out.

The result directory keeps:

- combined and per-operation verification stdout/stderr, phase limits,
  checksums, and timeout attempts
- complete JMH/Google Benchmark JSON and process logs for each observation
- JDK, classpath, JVM/native build receipts, source, CPU, and EC2 provenance
- `language-results.json`, a layout-independent export containing the manifest,
  verification, per-fork samples, allocation, and paired row identities

Archive the entire result directory and its input-manifest directory. The
export is not a substitute for the raw artifact files. `export` can regenerate
the JSON from those files without rerunning any benchmark; it rejects missing
rows, changed samples, and contradictory verification states.

The operation-receipt format is schema version 2. Recreate manifests and
verification from older versions; they cannot establish operation-specific
non-completion or substitute for the clean JVM build receipt.

## Same-host job preparation

Prepare jobs without allocating any resources:

```sh
python3 tools/re2-benchmark/language/fleet.py prepare \
  --manifest-directory target/language-manifest \
  --output-directory target/language-fleet
python3 tools/re2-benchmark/language/fleet.py validate \
  --plan-directory target/language-fleet
```

Each partition contains one logical case and one language, with the comparator
and both Regulator memory modes kept together. The everyday corpus produces
39 partitions and 351 jobs across R9g, R8g, R8i and three independent host
replicas. `--max-concurrent-hosts` records the intended concurrency in the
plan and defaults to 64.
`fleet.py` does not allocate or schedule hosts, so that field is not a capacity
reservation or an enforced scheduler setting.

Verify the partitions on a developer machine, without timing:

```sh
python3 tools/re2-benchmark/language/fleet.py verify \
  --plan-directory target/language-fleet \
  --results target/language-fleet-verification \
  --classpath "$LANGUAGE_CP" \
  --native-runner target/re2-benchmark-build/language_benchmark
```

This also compares semantic traces across language partitions. Verification
must be repeated on the measurement host with that host's build receipts.
The worker entry point `fleet.py run-job` accepts the plan, exact job ID, result
root, classpath, native runner, and JVM receipt. Archive workers also supply the
source archive and candidate provenance. It verifies and measures the complete
partition on that host.

`fleet.py package-job --plan-directory ... --job ... --output-directory ...`
copies only that job's partition and records its parent-plan and manifest
hashes. `fleet.py run-package` executes this package with existing build
receipts; it does not need the other partitions or the full corpus on the
worker. Aggregation still validates against the original complete plan.

`worker.py --package-directory ... --output-directory ...` builds the clean
candidate and appropriate native runner, then verifies both memory modes and
the comparator. Its default output is explicitly verification-only. Add
`--measure` on a qualification host to collect timings. The measured worker
pins verification and timing to one available CPU after building, restores
its original affinity on exit, and records the selected CPU. Archive workers
also pass `--source-archive` and `--candidate-provenance`. The package and
provenance belong outside the extracted source directory.

After collection, `fleet.py aggregate --plan-directory ... --results ...
--output ...` revalidates every raw observation and receipt without rewriting
the source evidence. Missing jobs, conflicting language traces, changed raw
samples, mixed source/JDK/comparator identities, wrong platforms, and replicas
reusing the same instance all block export. A new output filename is required.
Observations retain their job, host, fork, iteration, allocation, and operation
identities. Aggregation does not average away host differences.

## Bulk and adversarial inventory

Build the pinned Rebar tool with `cargo build --release --locked` in its clean
checkout, then expand its complete existing registry without timing:

```sh
python3 tools/re2-benchmark/language/rebar_inventory.py \
  --rebar-root /path/to/pinned/rebar \
  --output-directory target/language-rebar-inventory
```

The inventory contains all 41 curated and 197 extended cases. It records
compressed byte-identical KLV inputs, definitions, flags, expected native
results, input representation checks, and the existing Joni adapter ledger.
It includes 69 count, 108 span-count, 25 compile, 16 grep-capture, 11
capture-count, and 9 grep cases. The inventory is preparation evidence only.
The inventory records the original workloads, not their language compatibility.
The bulk manifest adds translations and exclusions, which must pass correctness
verification before measurement.

The old Joni adapter's compatibility status is a reference, not proof that a
new public Trino-language adapter preserves the same contract. Likewise,
`unicode=false` does not by itself establish an ASCII input or equivalent
UTF-8 semantics. Invalid UTF-8 must not be replacement-decoded for the JDK.
Classify it by operation: an unrepresentable execution input does not by itself
make its pattern unrepresentable for compilation.

### Bulk preflight

`bulk.py` expands that inventory into byte-identical KLV inputs and probes the
six engine mappings without collecting timings:

```sh
python3 tools/re2-benchmark/language/bulk.py prepare \
  --inventory target/language-rebar-inventory \
  --manifest-directory target/language-bulk
python3 tools/re2-benchmark/language/bulk.py probe \
  --manifest-directory target/language-bulk \
  --results target/language-bulk-probe \
  --classpath "$LANGUAGE_CP" \
  --native-runner target/re2-benchmark-build/language_bulk_benchmark
python3 tools/re2-benchmark/language/bulk.py summarize \
  --manifest-directory target/language-bulk \
  --results target/language-bulk-probe
```

Build `language_bulk_benchmark` alongside `language_benchmark`. The Java
adapter is `BenchmarkLanguageBulk`. Their unit tests cover compile, count,
span-count, capture-count, grep, and grep-capture operations. Set
`LANGUAGE_NATIVE_BULK_RUNNER` to include the native bulk tests in Python
discovery.

The probe retains aggregate results, ordered match/capture bytes, process logs,
and trace hashes. `summary.json` identifies scalar disagreements, pairwise and
cross-language trace disagreements, missing observations, process failures,
and timeouts. It always declares itself preparation-only. A probe timeout is
one attempt, not the two-attempt qualification receipt. A trace timeout can
include serialization or repeated extraction work and is not a matching-time
measurement. No failure is automatically relabeled as incompatibility.

Mappings record `identical`, `translated`, or `not-compatible`, with the actual
pattern bytes and a reason. Unchanged source bytes do not establish
cross-language equivalence. The manifest validator checks the exact declared
translation and unchanged flags and haystack. Verification then compares each
language, comparator, and Regulator memory mode with the original native RE2
result and matched-byte trace. Expected counts are never adjusted to make an
adapter pass.
For example, the original Rebar Unicode flag controls byte versus Unicode
matching in RE2, while Java's adapter applies Unicode case and character-class
options. Shorthand classes, line boundaries, and Unicode versions can therefore
need explicit translations even when both engines parse the expression.

The explicit mappings in `mappings.py` cover LF-only Java rules, named-group
syntax, braced Unicode properties and code-point escapes. ASCII-word-boundary
cases without an equivalent Trino assertion are not compatible. Byte-oriented
non-ASCII escape cases are not silently converted into code-point matching.

Two execution workloads expand their Unicode properties to pinned RE2 ranges
for every language and comparator. This preserves source membership despite
different JDK and Joni Unicode versions. These expanded patterns are not
measurements of dynamic Unicode-property compilation. Regenerate or check the
range fixture against the pinned checkout with:

```sh
python3 tools/re2-benchmark/language/unicode_ranges.py \
  --source target/re2-golden-dependencies/re2/re2/unicode_groups.cc --check
```

Omit `--check` to regenerate after an intentional upstream update. Review the
source checksum, membership changes, and mapping results before accepting it.

Run `collection.py verify` with the bulk manifest and native bulk runner to
produce qualification-format correctness receipts for both memory modes.
Unlike `bulk.py probe`, this requires repeatable operation timeouts and fails
on any result disagreement. A completed operation whose trace times out is a
verification failure, never an execution timeout. Raw traces remain on disk;
receipts store their hashes. Measurement and export recheck the original
native reference and every completed trace. `collection.py measure` and
`export`, and the same-host `fleet.py` partition commands, accept both suite
manifests. Bulk cases measure only their original operation, compilation for
compile cases and execution for the other five models.

All adapters split grep inputs before timing, trim a trailing carriage return,
and avoid a phantom final line. This differs from official Rebar timing, which
includes line splitting. Trino span/capture workloads use public
`TrinoRegexp.matcher`, retaining group zero for spans and all groups for captures.
Both Trino and Joni construct a matcher per source inside timing and reuse it
for every match in that source. Neither constructs extracted-value lists or
replacement output. Trino's other public operations keep their specialized
routes. Historical replacement-assisted captures have a separate work contract
and cannot qualify these matcher measurements. Correctness traces include
ordered byte boundaries, unmatched markers, and captured bytes. The JDK
adapter precomputes UTF-8 byte offsets for its String indices and rejects a
boundary inside a supplementary code point instead of inventing a byte span.

## AWS dispatch and recovery

The shared controller accepts repeated `--language-plan` arguments. Run the
existing baseline campaign separately; language plans add coverage and do not
replace its LIKE, Trino-operation, retained-memory, or direct-engine shards.
Keep both source manifests and fleet plans outside Maven's `target` directory
for a final campaign.

```sh
python3 tools/re2-benchmark/baseline/run-campaign.py smoke final-v1 \
  --language-plan /path/to/lifecycle-fleet \
  --language-plan /path/to/bulk-fleet \
  --result-root /path/to/language-smoke
```

Without `--execute`, this only writes the job manifest. Execution also requires
the frozen `--candidate-ref`, `--candidate-archive`, and
`--candidate-provenance`. Run `primary` with the same plans and candidate,
a separate result root, and `--smoke-results /path/to/language-smoke` after
that smoke is accepted. Language smoke samples each language/model/platform
using the full measurement protocol, not abbreviated warmup. Primary requires
three independent host replicas. The baseline-specific `confirmation` and
parser-race recovery commands do not accept language results.

Fleet schema 2 records deterministic host batches. By default each batch contains
one complete language partition, keeping its native/safe/comparator measurements
together. Targeted campaigns can supply `--selection` as a JSON array of
`[case ID, language]` pairs and a `--batch-operation-budget` from 1 through 16.
Selection and batch membership are frozen in the plan and validated on reuse.
For the complete bulk corpus, also pass `--duration-policy PATH` with frozen
per-case/language duration estimates and isolated slow or timeout pairs.
The packer enforces both the operation budget and batch-duration ceiling.
Smoke includes every isolated pair and the longest estimated packed batch.
Historical estimates plan work; smoke durations determine whether it fits.
The operation budget alone is a packing limit, not a time estimate. Slow corpus passes
can take seconds per iteration; check measured pass costs and all forks against
the host deadline before grouping them. Each host builds once,
then runs its partitions sequentially on one pinned CPU. Regenerate older
schema-1 plans. `fleet.py package-batch` and `worker.py --batch-directory`
exercise this boundary without needing the full corpus on the worker.

The controller uses the configured concurrency and shared fleet budget.
Use `--spot-only`, a `--spot-vcpu-reserve` matching the policy, and the same
`--fleet-budget` file for baseline and language collection. Select
`--release-version 1.0` to reproduce measurements of the published artifact.
See the [AWS guide](../aws/README.md#shared-fleet-budget).
Use `--max-concurrent` and `--max-concurrent-per-platform` to impose smaller
total and CPU-family limits. Running retries count toward those limits. The
language safety deadline is derived from batch count, requested concurrency,
per-platform and vCPU waves, the 90-minute per-host limit, one retry wave,
and a capacity-wait allowance. `--phase-timeout-seconds` can set a larger
frozen deadline. This is a worst-case bound,
not an ETA. The candidate, plan hashes, batch membership, heap, concurrency,
and deadlines are persisted; changing them on restart is rejected.

For baseline-only follow-ups, repeat `--shard NAME` on `run-campaign.py` to
collect complete affected shards. Use the same selection with
`validate-campaign-results.py` and `aggregate_results.py`. The full checked-in
row manifest remains unchanged and its digest stays in every receipt; selection
only narrows required shard coverage. Missing replicas, unexpected shards, or
mixed source identities still reject the campaign. A language fleet uses its
own case/language selection instead of this baseline flag.

Workers receive checksummed batch and source archives plus the candidate
provenance. Language acceptance is JSON, separate from baseline TSV receipts.
The controller checks resource cleanup and capacity, regenerates every export
from raw evidence, and verifies exact candidate and batch coverage before
persisting acceptance. Restart repeats these checks and skips accepted work.
An interrupted batch is retried in full, on a fresh host, only after cleanup
is proven. Partial attempts never contribute rows to aggregation.

Primary completion exports one JSON file per suite. It retains every logical
job and raw sample and rejects missing jobs, mixed candidates, language-trace
disagreements, and reused replica hosts. Local transport/controller tests use
synthetic timing samples; they do not qualify performance or replace the AWS
smoke and primary phases.

## Focused source-bracket controls

An investigation manifest can attach `focused_controls` to exact case/language
pairs. Each descriptor names one existing JMH method and explicit parameters.
The benchmark source must be identical in candidate and control archives.
These measurements use five forks, ten one-second warmups, ten one-second
measurements, both memory modes, and the same host/CPU as their enclosing source
bracket. They run before any diagnostic profiling.

Optional lifecycle descriptors carry a UTF-8 pattern and rotating inputs with
expected counts and Slice offsets. The existing language verifier checks them
against JDK or Joni before timing. Direct-engine descriptors use the existing
benchmark states and their correctness tests. Raw JMH results and checksummed
receipts live under each leg's `focused-controls` directory. These are paired
Regulator source controls, not additional comparator or dashboard rows. The
standard language export does not include them; investigation analysis must
validate these receipts and all three source-bracket legs separately.
