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

The lifecycle runners also time the native comparators of the baseline's
[specialized-pattern shards](../baseline/SHARD_DISPATCH.md#route-handlers).
`--specialized <inputs> <operation> <group> [benchmark flags]` times one
operation over the rotating sources that `SpecializedPatternNativeInputs`
writes for a workload: `extract`, the group of the first match; `extractAll`,
the group of every match; and, in the RE2 runner only, `replace`, which
replaces every match with the group. The adapter supplies each operation over
per-pattern scratch state allocated before timing.
`--verify-specialized <inputs> <operation> <group>` runs the operation once on
each source without timing and prints the values it returns, one line per
source. Before the shard times a native row, its semantic gate requires those
lines to equal the values every Regulator variant returns.

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

Measurement runs only in [work units](#work-units) on AWS unit workers, which
run the candidate classes and native runners of their platform's
[engine build](../aws/README.md#engine-builds) and compile nothing. Each unit
receives an extracted archive rather than a Git checkout, with the source
archive and the candidate provenance TSV produced by
[freezing the candidate](../aws/README.md#freeze-a-candidate). Before it
measures, the unit checks the archive checksum, embedded Git commit,
reconstructed Git tree, and extracted file contents and modes once, rejecting
extra files outside `target`. It does not create synthetic commits or trust a
source SHA supplied without its archive. Its JVM build receipt, which every
partition's provenance records, names that source, the SHA-256 of the
installed classes archive, the selected JDK and ordered classpath, and the
comparator pins. The classpath's Joni jar must match the pinned checksum. The
worker installed the classes archive only once its whole stream matched the
archive's pinned SHA-256, so that SHA-256 identifies the classes. The
controller rejects an attempt whose `engine-build.txt` records a classes
archive other than its platform's accepted build's, and acceptance rejects a
partition whose receipt names a classes archive other than that one.

Set `PYTHONDONTWRITEBYTECODE=1` before running any Python tools in an extracted
worker source tree. Otherwise imports create `__pycache__` directories that
the frozen-source check correctly rejects as extra files. Do not exempt caches
from source validation or reuse an already-modified extracted directory.

Measurement refuses non-Linux hosts, changed runner binaries, the wrong pinned
JDK, or a mismatched EC2 instance identity. Verification must be performed on
that host with those binaries first.

The opt-in `steady-state-v1` manifest profile uses five forks, thirty
one-second warmups for lifecycle cases or sixty for bulk cases, followed by
twenty one-second measured iterations. Timing runs without a profiler. A
separate one-fork, three-warmup, three-measurement pass records allocation;
its timing samples never enter the timing result. Both raw files and their
hashes are revalidated during export. The original CPU affinity, G1 collector,
heap and operation contracts remain unchanged. This profile is for prospectively
planned recollection after warmup diagnostics, not relabeling old results.
Unit estimates include its longer duration.

`steady-lifecycle-v2` keeps that lifecycle protocol and extends warmup to sixty
one-second iterations. The 1.0 follow-up uses it uniformly for R9g lifecycle
comparisons on `r9g.2xlarge`, with the original one-CPU allowance and 8 GiB heap.
Longer warmup and larger allocation were qualified before the production run;
their combined timing change is not proof of a particular JVM or kernel cause.
The fleet plan records the instance type and allocated vCPU count for every
suite and platform. Dispatch, the unit's host check and acceptance all use that
saved allocation.

`steady-slow-bulk-v2` preserves the bulk timing settings and changes only the
aggregate measurement-process deadline to 12,600 seconds. A corpus pass can
take longer than a nominal one-second JMH iteration. Five forks with sixty
warmups and twenty measured iterations can therefore exceed the ordinary
3,600-second process limit even when every operation passes its 30-second
verification limit. Do not shorten or discard iterations to fit that deadline.
Under the slow profile a comparison's unit estimate covers every measurement
process running to that deadline: each observation and operation receives its
full 12,600-second budget, and each JVM observation another for its allocation
process. A Java or Trino comparison is therefore estimated at 75,600 seconds
per operation and a native RE2 or Rust comparison at 63,000, each operation a
unit alone on On-Demand capacity whose worker timeout covers it.

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

## Plan preparation

Prepare a plan without allocating any resources:

```sh
python3 tools/re2-benchmark/language/fleet.py prepare \
  --manifest-directory target/language-manifest \
  --output-directory target/language-fleet
python3 tools/re2-benchmark/language/fleet.py validate \
  --plan-directory target/language-fleet
```

Each partition contains one logical case and one language, with the comparator
and both Regulator memory modes kept together. The everyday corpus produces
52 partitions, each measured on R9g and R8i in three independent replicas.

The plan keeps the source manifest in `source/` and each partition's manifest
in `partitions/<partition>`. Its `inputs/` store holds each input file once, at
the path the manifests name, and the source and every partition read their
inputs from it, each checked against its recorded SHA-256.

The plan packs its comparisons into work units, written as `units.tsv`
and `unit-rows.tsv` beside `plan.json` in the baseline unit format. An atom is
one comparison under one operation: its comparator and both memory modes,
which stay on one host. Atoms pack into units estimated at no more than 15
minutes. An atom longer than that, which runs on On-Demand capacity, or any atom
of a comparison the duration policy isolates, is a unit alone. A unit's identity
digests the partition manifests and operations it measures. The units belong to
one shard, which defaults to the suite; `--shard-id` names another, so a
separate manifest of the same suite, such as the literal probe, keeps distinct
unit IDs. Each comparison a unit holds is one of its measured groups, which
every replica runs in case-ID and language order. Validation rebuilds the units
from the plan and rejects any table that differs.

### Work units

On a unit worker, `run-unit.sh` runs one measured group of a unit of an
installed plan with `fleet.py run-unit`, and the worker uploads each group as
it finishes. The worker installs the plan of each of its pool's shards from
its bundle, written by `fleet.py bundle` as a deterministic zstd-compressed tar
of the plan under its shard ID and pinned by its SHA-256, under
`target/language-plans/<shard>`, which the clean-source check ignores. The
unit checks that the host matches its platform allocation, with every vCPU
visible and an 8g heap, and checks the extracted source against the source
archive and candidate provenance. Its candidate classes and native runners come
from the platform's engine build. Pinned to one CPU, it verifies and then
measures the group's comparison under `partitions/<partition>`; a lifecycle
comparison of which the unit holds only some operations runs a copy of its
partition's manifest selecting those operations. The manifest and such a copy
both read their inputs from the plan's store.

A semantic verdict ends the unit with status 44, which its controller never
retries: engines or a result that disagree with the expected trace or result,
or a timeout that does not reproduce or changes phase. A measurement process
that outruns the protocol's budget ends it with status 42, which is not
retried either. A runner that never starts or exits with a failure, and any
other failure, is retried.

`fleet.py` then accepts the attempt's group into a baseline
`unit-receipt.tsv`, and its controller checks it again from the returned
directory. It rejects the group unless:

- `unit-evidence.tsv` binds exactly the group's envelope files and raw
  results
- its unit rows are exactly the frozen unit's, whose ID recomputes from the
  plan
- the host is the unit session's host, on the platform's allocated instance
  type and vCPU count, with the protocol heap
- it ran pinned to one CPU
- its `run-metadata.txt` records the host's CPU steal ticks and total ticks
  over its measurements, read from `/proc/stat`
- each partition's export regenerates from its raw results, including the
  trace agreement and two-attempt timeout rules of verification
- each export names the session's instance, the candidate commit, the
  comparator pins, JDK 25.0.4+7, and, when a release is selected, the release's
  frozen manifest
- each native runner's build receipt names its verified binary, the pinned
  source, and a release build (`-O3 -DNDEBUG`)
- every partition ran the same candidate and binaries
- its observations are exactly the group's rows

A language group's CPU steal is recorded as diagnostic evidence; no host check
or report reads it.

Verify the partitions on a developer machine, without timing:

```sh
python3 tools/re2-benchmark/language/fleet.py verify \
  --plan-directory target/language-fleet \
  --results target/language-fleet-verification \
  --classpath "$LANGUAGE_CP" \
  --native-runner target/re2-benchmark-build/language_benchmark \
  --native-rust-runner target/re2-benchmark-build/language_rust_benchmark
```

This also compares semantic traces across language partitions. Each unit
repeats verification on its measurement host with that host's build receipts.

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
native reference and every completed trace. `fleet.py run-unit`,
`collection.py export`, and the other `fleet.py` plan and unit commands accept
both suite manifests. Bulk cases measure only their original operation,
compilation for compile cases and execution for the other five models.

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
--language-plan` accept it unchanged. Prepare its plan with `fleet.py prepare
--shard-id literal-probe` so its units stay distinct from the bulk campaign's.
Its six cases produce 24 partitions. Smoke adds the learned-sample workload to
the usual first workload for each language.

## AWS dispatch and recovery

A language campaign runs its plans' work units on the baseline's unit
workers, as its own controller invocation under the candidate's `lang`
directory and campaign ID `<campaign>.<name>.lang`. `run-campaign.py campaign
--language-plan` runs it beside each candidate's baseline phases, on the same
build; language plans add coverage and do not replace the baseline's LIKE and
Trino-operation shards. Keep both source manifests and fleet
plans outside Maven's `target` directory for a final campaign.

```sh
python3 tools/re2-benchmark/baseline/run-campaign.py smoke final-v1 \
  --candidate release=<commit> \
  --language-plan /path/to/lifecycle-fleet \
  --language-plan /path/to/bulk-fleet \
  --campaign-root /path/to/final-v1
```

Without `--execute`, this only writes `work-items.tsv`, here under
`/path/to/final-v1/release/lang/smoke`. Execution also freezes the candidate
and requires the candidate's accepted build phase, or the one
`--engine-builds` names, which carries the candidate classes and native
runners. Each plan must name a distinct shard.

- **Smoke** measures each plan's smoke units on replica 1. They cover the first
  comparison of each language and model, every isolated or required pair, and
  the longest packed unit.
- **Primary** runs with the same plans and candidate once that smoke is
  accepted.
  It measures every unit on three replicas, and no instance measures two
  replicas of one unit.
- Both phases use the full qualification protocol. A language campaign has no
  confirmation phase.

Each platform's workers use the instance type the plan allocates, so
lifecycle units on R9g run on `.2xlarge` workers. The controller writes each
plan's bundle under the result root's `language-plans/`, named by its shard and
a fingerprint of the plan's files and the tooling that writes the bundle, and
uploads it beside the [engine archives](../aws/README.md#engine-builds). A
restart of the same plans reuses the bundle; its SHA-256 stays its identity.
Leases, retries, interruptions, restarts, and the shared fleet budget follow the
baseline's
[unit dispatch](../aws/README.md#unit-dispatch). A unit estimated past 15
minutes runs only on On-Demand workers unless its platform buys only Spot, and
each pool's worker timeout covers its longest unit.

The controller accepts each group of an attempt into `accepted-units.tsv` only
after the [unit checks](#work-units) pass again on the returned evidence, from a
host
with the campaign's comparator pins and release selection. A semantic failure
is recorded in `semantic-rejections.tsv` and fails the phase. The phase gate
applies the baseline campaign rule to the plans' units: every group of every
unit the phase selects, the smoke units in smoke and every unit in primary, on
both platforms for each replica, on its allocated instance type, with no two
replicas of a unit sharing an instance and one candidate throughout. A replica
interrupted after some groups gathers the rest from a later attempt.

Primary completion exports each plan as `<shard>-results.json`, through
`fleet.py aggregate --plan-directory ... --results <result root> --output ...`.
The export:

- revalidates every accepted group from its retained evidence, without
  rewriting it, and requires the campaign rule again;
- rejects language-trace disagreement across a case's partitions, mixed
  candidate, comparator, or JDK identities, and more than one classes archive
  on a platform;
- keeps each group's receipt with its partition's provenance and every raw
  sample, each row naming its attempt and group, without averaging away host
  differences.

A restart must reproduce each export byte for byte. The release importer reads
`accepted-units.tsv` beside the exports and accepts them only as one complete
campaign of the imported plans, with no semantic rejection.

`--max-concurrent`, `--max-concurrent-per-platform`, `--platform-regions`,
`--purchase`, `--fleet-budget`, and `--release-version` work as they do for the
baseline.
Select `--release-version 2.0` to measure the published 2.0 artifact. See the
[AWS guide](../aws/README.md#shared-fleet-budget) for the fleet budget, release
manifests and remeasuring 1.0. For the
complete bulk corpus, prepare the plan with `--duration-policy PATH`: its
frozen per-comparison estimates raise unit estimates, and each operation of
an isolated pair runs as a unit alone. Local controller tests use synthetic
timing samples; they do not qualify performance or replace the AWS smoke and
primary phases.

A targeted language plan selects its coverage when it is prepared: pass
`fleet.py prepare --selection PATH`, a JSON array of `[case ID, language]`
pairs such as `[["case-0007", "java"], ["case-0012", "rust"]]`. The plan
freezes the selection, and validation rejects a plan whose partitions or units
differ from it. `--shard` selects baseline shards only.

For baseline-only follow-ups, repeat `--shard NAME` on `run-campaign.py` to
collect complete affected shards. Use the same selection with
`aggregate_results.py`. The full checked-in row manifest remains unchanged and
its digest stays in every receipt; selection only narrows required shard
coverage. Missing replicas, unexpected shards, or mixed source identities still
reject the campaign.
