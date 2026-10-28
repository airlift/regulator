# Language comparison collection

This suite measures ordinary regex operations and pattern lifecycles across
the four regex languages. Run it alongside the baseline Trino-operation,
specialized-pattern, and LIKE shards, not instead of them.

The current corpus is the 13 workloads in `everyday-trino-workloads.tsv`, with
eight rotating inputs each. Each workload runs through:

| Language | Regulator API | Comparator |
|---|---|---|
| RE2 | `Re2` | Pinned native RE2 |
| Java | `JavaRegexp` | JDK `Pattern` |
| Trino | `TrinoRegexp` | Pinned Airlift Joni |
| Rust | `RustRegexp` | Pinned Rust `regex` crate |

Regulator runs with native access enabled and disabled. The comparator runs
once per language and host, and supplies the comparison for both modes.
Each engine measures compilation, single-use contains and count, and reused
contains and count. That produces 780 engine observations and 520 paired rows
per architecture. These counts describe this additional suite, not the full
qualification campaign.

## Measurement contracts

JDK patterns and inputs are decoded strictly to `String` before timing.
Regulator, Joni, native RE2, and the `regex` crate receive identical UTF-8
bytes. The Rust comparator validates them as UTF-8 when it loads the workload
and borrows them as `str` during timing. Input rotation includes nonzero Slice
offsets. No encoding conversion is timed.

Single use includes compilation and one public operation. Reused measurements
retain the compiled pattern. RE2, Java, and Rust counts use their public
`count` methods; JDK reuses `Matcher.reset`; Trino uses its public `contains`
and `count` operations. Joni constructs a matcher for each byte-array input as
its Trino route does. Compilation does not construct a matcher. Native
compilation includes destruction of the compiled object; Java allocation and
amortized GC are recorded by JMH. These lifetime costs are not assumed to be
identical.

The Rust comparator is the pinned `regex` crate with its default features,
linked as a static library behind a C interface. It builds each pattern with
`RegexBuilder` as `tools/rust-regex-golden` does, setting only Unicode mode and
case insensitivity, so the crate's default size and nest limits apply. Contains
is `is_match` and count is `find_iter` counting; each makes one call across the
interface per input. Span and capture counts use the crate's own iteration,
with capture locations reused across matches, and the trace checks that loop
against `captures_iter`. A panic aborts the process rather than unwinding into
C++. The release profile is Rebar's `rust/regex` profile: fat LTO, one codegen
unit, and `panic = "abort"`, for the generic CPU with no `RUSTFLAGS`. Rebar's
own `rust/regex` engine instead runs the `regex-automata` meta engine with byte
semantics and `perf-dfa-full`; this comparator measures the public APIs that
`RustRegexp` follows.

A bulk case uses the crate's `str` API unless its source turns Unicode mode
off or its haystack is not UTF-8. Then both sides use byte mode: the crate's
`regex::bytes` API and `RustRegexp` with `setBytes`. The Unicode-off rule
follows RE2, whose runners switch to Latin-1 exactly when the source turns
Unicode off. Each runner derives the mode from the case's own inputs and
chooses the API before timing, so a timed call never dispatches on it. Both
APIs take the pattern as text, so the native runner always checks that it is
UTF-8. Lifecycle workloads are valid UTF-8 and use the `str` API.

Rust iteration skips an empty match that ends where the previous match ended,
so `a*` matches twice in `ab` rather than three times as in RE2. Campaign
expectations stay unchanged: collection checks Rust against the source's
expected result, and cases where Rust iteration gives a different answer are
marked not compatible. Only the runners' unit tests expect the crate's own
counts.

Bulk boolean-line operations use `Re2.find`, `JavaRegexp.find`, and
`RustRegexp.find`, not a boundary-producing matcher. Bulk counts use the public
count methods. The separate `BenchmarkLanguageBulk.iterateMatches` diagnostic
retains the RE2/Java/Rust count-by-iteration path; it requires a `count`
workload and is not part of the primary collector. Historical
count-by-iteration measurements remain distinct from public-count measurements
even when their input/result is identical.

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

Use a patched Python with `tarfile` extraction filters, such as Python 3.9.17+
or 3.12+. Expanding upstream TOML on the preparation machine additionally
requires Python 3.11+. The workers do not parse TOML.

The Rust runners need Cargo and rustc 1.97.0. CMake runs
`cargo build --release --locked` from `rust-regex`, so the lock file is never
rewritten. There, rustup's `cargo` and `rustc` proxies honor the crate's
`rust-toolchain.toml` and select 1.97.0 themselves. To use the pinned binaries
directly instead, put them first on `PATH`; with rustup:

```sh
export PATH="$(dirname "$(rustup which --toolchain 1.97.0 cargo)"):$PATH"
```

Build the Java and native runners, then prepare an immutable input manifest:

```sh
./mvnw test-compile
./mvnw -q dependency:build-classpath -DincludeScope=test \
  -Dmdep.outputFile=target/language-classpath.txt
RE2_BENCHMARK_TARGETS="language_benchmark language_bulk_benchmark language_rust_benchmark language_rust_bulk_benchmark" \
  tools/re2-benchmark/build.sh
python3 tools/re2-benchmark/language/collection.py prepare \
  --manifest-directory target/language-manifest
```

Each native runner writes its own receipt beside its executable, such as
`language_benchmark.build.json` or `language_rust_benchmark.build.json`. A
receipt includes binary and source hashes, dependency revisions, compiler
commands, and the CMake configuration. The source hash covers the engine
adapter and the shared harness it includes. Rebuilding a native target
directly requires refreshing its receipt with `native_build.py`.

The Rust runners' receipts also record the rustc and Cargo versions, the Cargo
lock hash and resolved crates, and the Cargo command and environment. Their
source hash also covers the shim crate. The crate lock must resolve the same
`regex` graph as `tools/rust-regex-golden`, and `regex` must match its row in
`baseline/comparators.tsv`.

Each native runner is a shared harness, `language_harness.h` or
`language_bulk_harness.h`, instantiated with an engine adapter. A comparator
may choose among adapters from the case's inputs before timing, as the Rust
bulk runner does. The harness owns input loading, the command line, phase
markers, and benchmark registration. A lifecycle adapter supplies compile,
contains, count with an optional match trace, and the input check; its harness
rotates the inputs and runs the timed operations. A bulk adapter supplies
compile, the input check, per-search state allocated outside timing, and the
timed loop that runs one Rebar model and optionally writes its trace.

Run deterministic verification without collecting timings:

```sh
LANGUAGE_CP="target/test-classes:target/classes:$(<target/language-classpath.txt)"
python3 tools/re2-benchmark/language/collection.py verify \
  --manifest-directory target/language-manifest \
  --results target/language-verification \
  --classpath "$LANGUAGE_CP" \
  --native-runner target/re2-benchmark-build/language_benchmark \
  --native-rust-runner target/re2-benchmark-build/language_rust_benchmark
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
LANGUAGE_NATIVE_RUST_RUNNER=target/re2-benchmark-build/language_rust_benchmark \
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
result directory, classpath, and native runner arguments, plus `--platform r9g`
or `r8i` and `--jvm-build-receipt target/language-jvm-build/jvm-build.json`.
It refuses non-Linux hosts, a dirty source tree, changed runner binaries, the
wrong pinned JDK, or a mismatched EC2 instance identity. Verification must be
performed on that host with those binaries first.

The opt-in `steady-state-v1` manifest profile uses five forks, thirty
one-second warmups for lifecycle cases or sixty for bulk cases, followed by
twenty one-second measured iterations. Timing runs without a profiler. A
separate one-fork, three-warmup, three-measurement pass records allocation;
its timing samples never enter the timing result. Both raw files and their
hashes are revalidated during export. The original CPU affinity, G1 collector,
heap and operation contracts remain unchanged. This profile is for prospectively
planned recollection after warmup diagnostics, not relabeling old results.
Batch estimates must include its longer duration before launching a fleet.

`steady-lifecycle-v2` keeps that lifecycle protocol and extends warmup to sixty
one-second iterations. The 1.0 follow-up uses it uniformly for R9g lifecycle
comparisons on `r9g.2xlarge`, with the original one-CPU allowance and 8 GiB heap.
Longer warmup and larger allocation were qualified before the production run;
their combined timing change is not proof of a particular JVM or kernel cause.
The fleet plan records the instance type and allocated vCPU count for every
suite and platform. Dispatch, admission and acceptance all use that saved
allocation.

`steady-slow-bulk-v2` preserves the bulk timing settings and changes only the
aggregate measurement-process deadline to 12,600 seconds. A corpus pass can
take longer than a nominal one-second JMH iteration. Five forks with sixty
warmups and twenty measured iterations can therefore exceed the ordinary
3,600-second process limit even when every operation passes its 30-second
verification limit. Do not shorten or discard iterations to fit that deadline.
The frozen slow profile applies to one complete comparison per host. Transport
derives the host deadline from the checksummed manifest: each observation and
operation receives its full 12,600-second process budget, each JVM observation
receives another allocation-process budget, and the host receives 5,400
seconds for setup and verification. The current Java and Trino slow batches
therefore use `TIMEOUT_SECONDS=81000`; a native RE2 or Rust comparison uses
68,400 seconds. Transport rejects a different deadline, auxiliary diagnostic
work, or multiple comparisons in a slow batch. Ordinary batches retain the
5,400-second host deadline. The general campaign controller still plans
90-minute jobs; the slow profile requires direct wrapper scheduling with its
recorded deadline, as in the archived follow-up controller.

The original profile remains supported for reproducing earlier campaigns.
JMH uses five forks, ten one-second warmups, ten one-second measurement
iterations, an 8 GiB heap, G1, and the GC profiler. Native RE2 and Rust use
five Google Benchmark repetitions with a ten-second warmup and at least one
second of measurement. Native results use elapsed time. Both protocols and
timeout limits are saved in the manifest. An aggregate measurement-process
timeout is a collection failure, not evidence that a single regex call timed
out.

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
52 partitions and 312 jobs across R9g and R8i, each with three independent
host replicas. `--max-concurrent-hosts` records the intended concurrency in the
plan and defaults to 64.
`fleet.py` does not allocate or schedule hosts, so that field is not a capacity
reservation or an enforced scheduler setting.

The plan also packs its comparisons into work units, written as `units.tsv`
and `unit-rows.tsv` beside `plan.json` in the baseline unit format. An atom is
one comparison under one operation: its comparator and both memory modes,
which stay on one host. Atoms pack into units estimated at no more than 15
minutes. An atom longer than that, or a comparison the duration policy
isolates, is a unit alone. A unit's identity digests the partition manifests
and operations it measures. The units belong to one shard, which defaults to
the suite; `--shard-id` names another, so a separate manifest of the same
suite, such as the literal probe, keeps distinct unit IDs. Validation rebuilds
the units from the plan and rejects any table that differs.

### Work units

On a unit worker, `run-unit.sh` runs a unit of an installed plan with
`fleet.py run-unit`. The worker installs each plan from its bundle
(`fleet.py bundle`, checked and installed with `fleet.py install-bundle`) under
`target/language-plans/<shard>`, which the clean-source check ignores. The
unit checks that the host matches its platform allocation, with every vCPU
visible and an 8g heap, and builds the candidate once per host from the source
archive and candidate provenance. Its native runners come from the platform's
engine build. Pinned to one CPU, it verifies and then measures each comparison
it holds under `partitions/<partition>`; a lifecycle comparison of which it
holds only some operations runs a copy of its partition selecting those
operations.

A semantic verdict ends the unit with status 44, which its controller never
retries: engines or a result that disagree with the expected trace or result,
or a timeout that does not reproduce or changes phase. A measurement process
that outruns the protocol's budget ends it with status 42, which is not
retried either. A runner that never starts or exits with a failure, and any
other failure, is retried.

`fleet.py` then accepts the attempt into a baseline `unit-receipt.tsv`, and
its controller checks it again from the returned directory. It rejects the
attempt unless:

- `unit-evidence.tsv` binds exactly the attempt's envelope files and raw
  results
- its rows are exactly the frozen unit's, whose ID recomputes from the plan
- the host is the unit session's host, on the platform's allocated instance
  type and vCPU count, with the protocol heap
- it ran pinned to one CPU
- each partition's export regenerates from its raw results, including the
  trace agreement and two-attempt timeout rules of verification
- each export names the session's instance, the candidate commit, the
  comparator pins, JDK 25.0.4+7, and, when a release is selected, the release's
  frozen manifest
- each native runner's build receipt names its verified binary, the pinned
  source, and a release build (`-O3 -DNDEBUG`)
- every partition ran the same candidate and binaries
- its observations are exactly the unit's rows

Verify the partitions on a developer machine, without timing:

```sh
python3 tools/re2-benchmark/language/fleet.py verify \
  --plan-directory target/language-fleet \
  --results target/language-fleet-verification \
  --classpath "$LANGUAGE_CP" \
  --native-runner target/re2-benchmark-build/language_benchmark \
  --native-rust-runner target/re2-benchmark-build/language_rust_benchmark
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
eight engine mappings without collecting timings:

```sh
python3 tools/re2-benchmark/language/bulk.py prepare \
  --inventory target/language-rebar-inventory \
  --manifest-directory target/language-bulk
python3 tools/re2-benchmark/language/bulk.py probe \
  --manifest-directory target/language-bulk \
  --results target/language-bulk-probe \
  --classpath "$LANGUAGE_CP" \
  --native-runner target/re2-benchmark-build/language_bulk_benchmark \
  --native-rust-runner target/re2-benchmark-build/language_rust_bulk_benchmark
python3 tools/re2-benchmark/language/bulk.py summarize \
  --manifest-directory target/language-bulk \
  --results target/language-bulk-probe
```

Build `language_bulk_benchmark` and `language_rust_bulk_benchmark` alongside
the lifecycle runners. The Java adapter is `BenchmarkLanguageBulk`. Their unit
tests cover compile, count, span-count, capture-count, grep, and grep-capture
operations. Set `LANGUAGE_NATIVE_BULK_RUNNER` and
`LANGUAGE_NATIVE_RUST_BULK_RUNNER` to include the native bulk tests in Python
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
syntax, braced Unicode properties and code-point escapes. They also spell out
RE2's ASCII `\d` and `\s` where the target compiles them as Unicode classes:
in bibleref for Rust and Java (archived 1.0 manifests keep Java's syntax-only
translation), and in the lifecycle `trinoPhone` and `whitespace` workloads for
Rust. ASCII-word-boundary cases without an equivalent Trino assertion are not
compatible. Byte-oriented non-ASCII escape cases are not silently converted
into code-point matching; Rust's byte mode runs them unchanged, matching
single bytes as RE2's Latin-1 mode does.

Rust accepts the source's named-capture and property syntax unchanged. Its
byte mode also runs executions over a malformed UTF-8 haystack, which are not
compatible with Java and Trino. Two groups are not compatible in Rust:

- `.*` and `(?s).*` span counts, where Rust's empty-match rule reports
  different matches than the source.
- `\p{L}{256}`, which exceeds the crate's default 10 MiB compiled-size limit.
  `RustRegexp` does not reproduce that estimate.

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

Run `collection.py verify` with the bulk manifest, `--native-runner` set to
`language_bulk_benchmark`, and `--native-rust-runner` set to
`language_rust_bulk_benchmark` to produce qualification-format correctness
receipts for both memory modes.
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

## Literal-probe distributions

`literal_probe.py` prepares a separate bulk manifest showing what a
corpus-matched byte-frequency distribution changes. Each of the three workloads
in `manifests/literal-probe-workloads.tsv` counts one literal over a pinned
corpus twice. The default case compiles Regulator with the uniform distribution,
which probes the literal's first byte. The `/tuned` case compiles with the
distribution its corpus calls for: `englishText()` for the Sherlock text,
`json()` for GH Archive events, or one distribution learned from access logs
for the nginx log. The comparator has no such option and measures identical
work in both cases. The preparer escapes regex metacharacters, so every engine
searches the literal's exact bytes, and checks each expected count against
those bytes.

The learned distribution counts up to 256 KiB of whole lines from the first half
of each of the Apache and nginx logs, split after the first line feed at or past
the middle. The lines are drawn in the order `java.util.Random` with seed
20261009 shuffles them; the second half of the nginx log is the searched
haystack. The preparer selects the lines and checks the sample against its
pinned checksum, and the workload file carries the sample.
`BenchmarkLanguageBulk` builds the distribution during setup, outside timing. A
preset workload names its preset instead.

`manifests/literal-probe-corpus.tsv` pins each corpus by URL, revision, size,
checksum, and license. GH Archive is decompressed and truncated at the last
line feed within 8 MiB. Fetch and verify the corpora outside the repository,
then prepare the manifest:

```sh
python3 tools/re2-benchmark/language/literal_probe.py fetch \
  --corpus-directory /path/to/literal-probe-corpus
python3 tools/re2-benchmark/language/literal_probe.py prepare \
  --corpus-directory /path/to/literal-probe-corpus \
  --manifest-directory /path/to/literal-probe-manifest
```

The manifest uses the `language-bulk` suite and the `steady-state-v1` profile,
so `collection.py verify`, `fleet.py prepare`, and `run-campaign.py
--language-plan` accept it unchanged. Prepare it with `--shard-id
literal-probe` so its units stay distinct from the bulk campaign's. Its six
cases produce 24 partitions and 144 jobs. Smoke adds the learned-sample workload to the usual first workload
for each language.

## AWS dispatch and recovery

The shared controller accepts repeated `--language-plan` arguments. Run the
existing baseline campaign separately; language plans add coverage and do not
replace its LIKE and Trino-operation shards.
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
`--release-version 2.0` to measure the published 2.0 artifact. See the
[AWS guide](../aws/README.md#shared-fleet-budget) for the fleet budget, release
manifests and remeasuring 1.0.
If one platform runs out of Spot capacity, finish the same result root on
On-Demand with `--refill-unresolved`; see
[recovery](../aws/README.md#recovery-and-evidence).
Use `--max-concurrent` and `--max-concurrent-per-platform` to impose smaller
total and CPU-family limits. Running retries count toward those limits. The
language safety deadline is derived from batch count, requested concurrency,
per-platform and vCPU waves, the 90-minute per-host limit, one retry wave,
and a capacity-wait allowance. `--phase-timeout-seconds` can set a larger
frozen deadline. This is a worst-case bound,
not an ETA. The candidate, plan hashes, batch membership, heap, concurrency,
and deadlines are persisted; changing them on restart is rejected, except
that a refill records its own concurrency and deadline.

For baseline-only follow-ups, repeat `--shard NAME` on `run-campaign.py` to
collect complete affected shards. Use the same selection with
`aggregate_results.py`. The full checked-in
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
