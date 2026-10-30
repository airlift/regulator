# Baseline reduction and reporting

`aggregate_results.py` validates and summarizes baseline campaign results. It
rejects incomplete or inconsistent evidence. `render_report.py` turns the
summary into a deterministic Markdown report. Both scripts use only the Python
standard library and are frozen with the candidate before measurement.

## Inputs

The reducer requires the frozen `rows.tsv`, `platforms.tsv`, and `shards.tsv`,
the primary `accepted-units.tsv`, and one or more artifact roots or explicit
attempt directories. The required row count and coverage are checked by
`validate-manifest.py` against the frozen manifest.

The reducer refuses a phase root whose `semantic-rejections.tsv` is missing or
lists an attempt that failed its correctness stage, as
[unit dispatch](../aws/README.md#unit-dispatch) describes, whatever the unit's
other attempts show. It reads each accepted measured group of a unit attempt
where the controller retains it, at
`units/<platform>/<unit_id>/replica-<n>/<host_session>/lease-<seq>/group-<g>`
under its phase root, the layout `unit_dispatch.py` owns, and ignores the
controller's staged copies under `unit-results/`. Each group directory must
contain:

- `unit-receipt.tsv`
- `unit-session.tsv`
- `unit-rows.tsv`
- `observed-rows.tsv`
- `unit-evidence.tsv`
- `unit-capacity.txt`

With `--release-version`, for a campaign that measured a published release,
every route of every attempt must also attest that it measured that release's
pinned JAR: its `release-artifact.json`, the `release_version` and
`release_artifact_sha256` in its `run-metadata.txt`, and the JAR under
`release/`. The report importer accepts a reduction that proved the release it
publishes, or, as a candidate build, one reduced without `--release-version`
whose attempts recorded no release metadata.

The ledgers must pass campaign unit acceptance: every group of every platform
and frozen unit of the selected shards, accepted once for each of replicas 1,
2, and 3, and at most one replica-4 confirmation per platform and unit covering
every group. A replica interrupted after some groups gathers its groups from
several attempts, but no two replicas of a unit share an EC2 instance, and the
confirmation runs only on instances none of its replicas used. Accepted
artifact discovery must match the ledgers exactly. Each group is reverified as
unit acceptance does, and its receipt must regenerate from its evidence.

The receipt also binds the candidate commit, engine tree, and deployed source
archive. When `--campaign-artifact` is supplied, its candidate commit must match
every accepted receipt. A mismatch invalidates reduction rather than being
reported as an informational provenance difference.

Confirmation receipts are supplied separately. Confirmation rows are preserved
alongside the three primary rows and identified explicitly; they never replace
a primary attempt.

Every row has exactly one owner unit, whose three primary replicas and
optional confirmation are that row's samples.

Evidence for interrupted and rejected attempts is supplied through event files
and remains separate from accepted measurements.

`unit-capacity.txt` is written in two stages. Acceptance binds the immutable
memory-total and available-before prefix because GNU `time` cannot write wall
time and peak RSS until the measured process exits. Reduction then requires
positive wall time and peak RSS, a zero exit status, valid before and after
available-memory values, and peak RSS no larger than physical memory. The
finalized file is included in the artifact inventory and immutable campaign
archive. `capacity.tsv` preserves these values for every accepted attempt.

### Campaign Events

Each `--events` file is TSV with required `event_type` and `reason` fields.
`event_type` is one of `interruption`, `rejected-session`, or `exclusion`.
The following identity and evidence fields are recognized when available:

```text
campaign_id platform shard_id replica_id instance_id host_epoch
artifact_uri artifact_sha256
```

The controller also records where each launch was placed and why it failed:

```text
region availability_zone placement_score failure_classification
```

These are empty when unknown, such as an availability zone the launch never
chose, a placement score the controller did not supply, or a session that
reported no failure. A reclaimed host, or a launch that failed for capacity,
quota, or another retryable reason, is recorded as an `interruption` whose
`reason` is the controller's classified detail, such as `launch-capacity:...`.
Event files written before these columns existed still reduce, and a resumed
campaign keeps the columns its event file started with.

The controller's preformatted `details` value is preserved. Additional columns
are merged into JSON-object details in sorted-key order. For plain-text details,
they are appended as a canonical `additional={...}` suffix. Events are copied
to `events.tsv` and represented in the gap ledger; they are never treated as
accepted measurements or unresolved accepted results.

### Retrieval Metadata

Each optional `--artifact-index` file must contain the session identity fields
from `unit-session.tsv`. All other columns are passed through to `metadata.tsv`.
These conventional fields are also copied directly into the artifact
inventory:

```text
artifact_uri artifact_sha256 retrieved_at
```

`retrieval_uri`, `archive_sha256`, and `retrieval_timestamp` are accepted as
equivalent names. Independently of external storage metadata, the reducer
computes a deterministic artifact-set digest and inventories every file in the
accepted attempt, including raw timing artifacts.

`--campaign-artifact` accepts the one-row retrieval metadata emitted by
`archive_results.py`:

```text
s3_uri version_id archive_sha256 candidate_commit candidate_ref campaign_id
source_directory file_count source_manifest_sha256
```

The campaign ID must match the primary receipt ledger. The immutable S3 URI,
version, archive checksum, candidate identity, source directory, and file count
are attached to every accepted attempt's metadata and inventory. Optional
per-attempt artifact indexes remain available as more specific provenance.
The adjacent `<campaign-artifact>.source-manifest.tsv` must have the recorded
SHA-256. Every accepted attempt file must appear in that exported embedded
archive manifest with its exact size and digest. This recursively binds routed
metadata, raw manifests and artifacts, comparator provenance, finalized
capacity, and acceptance receipts to the immutable campaign archive.

## Comparator Rules

Ratios are emitted only when the manifest identifies a same-host comparator
relationship. The reducer does not infer relationships from similar benchmark
names or descriptive labels:

- A family whose comparator is Joni, native RE2, JDK `Pattern`, or the `regex`
  crate compares each Regulator row with its same-host `joni`, `native-re2`,
  `jdk`, or `rust-regex` row.
- A Trino LIKE family compares `regulator`, and `regulator-generated` where
  it is measured, separately with each `trino-optimized` and `trino-sql` row
  present.
- Other manifest comparator labels do not create a ratio unless one of these
  concrete paired shapes is present.

Ratios use normalized Java cost divided by comparator cost. Below 1 is faster;
above 1 is slower. Time units are converted before division. Throughput units
are inverted so the direction remains consistent.

## Aggregation

The unit replica is the independent sample, gathered from its accepted
groups. Different units may share a host, but a row's samples come from the
three primary replicas and the confirmation of its owner unit, no two sharing
an instance. Same-host ratios pair a candidate with the comparators measured
in the same group of one unit attempt, which never spans instances. The
reducer preserves all primary and confirmation attempts, then reports for every platform and manifest row:

- primary and all-host medians
- minimum and maximum score
- primary and all-host coefficient of variation
- an explicit note that a useful confidence interval is unsupported with only
  three or four independent hosts
- median allocation
- the number of its samples left out because their host was flagged
- benchmark contract-identity consistency
- identifiable input-size scaling classification
- every unresolved reason

The producer's legacy `result_checksum` column contains a digest of every field
in the row's frozen manifest contract, including expected results, workload,
system and comparator. It is not derived from measured output or route-wide
verification outcomes. Reducer outputs therefore expose it as
`contract_identity`. Replica disagreement means the declared contracts differ;
it does not demonstrate an incorrect measured result. The complete semantic
receipt and classified outcomes remain separate, integrity-checked evidence.
A timeout on another row must not change this row's identity. The campaign and
reducer also enforce pinned source/runtime/comparator provenance. Raw timing-file
checksums belong in the artifact inventory.

Scaling is identified only when a benchmark/system family has at least three
numeric values of a size-, length-, count-, capture-, group-, or repetition-
named parameter while all other parameters are fixed. A log-log slope is
recorded as decreasing, constant, sublinear, linear, or superlinear. Different
classes across accepted hosts are unresolved. Unidentifiable rows are reported
as such rather than assigned an expected class.

A row or comparison remains unresolved when applicable if:

- a flagged host's samples were left out and fewer than three hosts remain
- any contributing measurement exceeds the 5% relative standard-error gate
- host CV exceeds 5%
- same-host candidate/comparator ratio CV exceeds 5%
- hosts disagree in both material directions outside the 0.95-1.05 band
- an allocation-free row reports any allocation
- benchmark contract identities differ
- identifiable scaling classes differ across hosts

A median ratio at or beyond 0.5 or 2.0 is a serious performance outlier. These
rows are preserved in `serious-outliers.tsv` and the gap ledger, but excluded
from `general-classes.tsv`.

`confirmation-jobs.tsv` deduplicates primary instability and flagged hosts to
the exact `platform, shard_id, unit_id` schema consumed by `run-campaign.py`:
instability selects the owner unit of the unstable row. A flagged primary host
selects every unit it measured. Host CV or primary
same-host ratio CV above 5%, precision rejection, material direction
disagreement, contract-identity disagreement, and scaling class disagreement
request confirmation. After confirmation, the all-host ratio CV determines
whether ratio variability remains unresolved. A
deterministic allocation violation or a serious but stable ratio does not. A
platform and unit that already has a replica-4 attempt is omitted because its
one confirmation allowance is spent. Its confirmation is that unit's replica 4
on an instance none of its replicas 1 to 3 used.

### Host Check

The reducer judges every host, one `instance_id` and `host_epoch`, against the
other replicas of the rows it measured, and writes the verdicts to
`host-checks.tsv`. Only primary rows whose three replicas all passed the
precision gate count. On each, a host's deviation is the natural log of its
score less the median log of the row's three replica scores, signed so that
positive is slower for both time and throughput units. A host's `slow` is the
median of its deviations and its `noise` the median of their absolute
distances from `slow`, a median absolute deviation. Its `steal_share` is the
CPU steal ticks over the total ticks summed across every route of every
accepted group it ran, so a route's weight is its length.

A host is flagged when it measured at least 20 such rows and is more than 2%
slow or 3% noisy, or when its steal share is above 1% however few rows it ran.
The [methodology](../../../docs/benchmarks/METHODOLOGY.md#host-check) grounds
these limits.

A flagged host's samples stay in `host-rows.tsv` and the comparison and scaling
tables but are left out of every aggregate, class, and confirmation check. A row
or comparison left with fewer than three hosts is unresolved as
`flagged-host-excluded`, which the report import refuses until confirmed, and
every unit a flagged host measured as a primary replica becomes a confirmation
job. When only one of a row's primaries was flagged and the confirmation host
passes, its replica-4 confirmation, on a fourth instance, restores three
samples: the two remaining primaries and the confirmation. Otherwise the row
stays unresolved.

## Outputs

The reducer writes:

- `host-rows.tsv`: every row of each accepted group, with `unit_id`,
  `unit_attempt_id`, `group_id`, its host's `region` and `market`, and its
  group's CPU `steal_share`
- `host-checks.tsv`: each host's region, market, rows, slowness, noise, steal
  share, and whether and why it was flagged
- `row-aggregates.tsv`: per-platform row statistics and unresolved state
- `host-comparator-ratios.tsv`: every same-host ratio
- `comparison-aggregates.tsv`: per-platform comparison statistics
- `general-classes.tsv`: class summaries without serious outliers
- `serious-outliers.tsv`: serious comparison rows
- `scaling.tsv`: per-attempt scaling signatures
- `gap-ledger.tsv`: unresolved conditions, serious outliers, and events
- `events.tsv`: interruptions, rejected sessions, and exclusions
- `artifact-inventory.tsv`: file and retrieval checksums
- `metadata.tsv`: run and retrieval metadata passthrough
- `capacity.tsv`: finalized wall-time, peak-RSS, and host-memory evidence, from
  `unit-capacity.txt` with `unit_capacity_sha256`
- `confirmation-jobs.tsv`: unique primary-unstable or flagged-host
  `platform`, `shard_id`, `unit_id` rows still eligible for the bounded
  replica-4 wave
- `summary.json`: deterministic campaign counts and identity, with the
  `release_version` every attempt proved it measured, empty without
  `--release-version`
- `output-checksums.tsv`: checksums for every reducer output above

Every per-attempt table adds `unit_id`, `unit_attempt_id`, and `group_id`
after `host_epoch`, and `events.tsv`, whose `group_id` is empty, adds the
`region`, `availability_zone`, `placement_score`, and `failure_classification`
columns.

The published
[interactive benchmark report](https://airlift.github.io/regulator/benchmarks/)
builds its `specialized-patterns` population from the
`trino-specialized-patterns`, `re2-specialized-patterns`,
`java-specialized-patterns`, `rust-specialized-patterns`, and
`trino-like-specialized` suites. Each regex suite's workloads are split between
its shards `-a` and `-b`, so their row IDs begin with the shard, but report row
IDs name the suite. Each Regulator variant's ratio to its same-host comparator
becomes one row.
[`benchmark-report/scripts/baseline_data.py`](../../../benchmark-report/scripts/baseline_data.py)
maps the rows to case IDs: a regular-engine or generated row's case ID adds
`/regular-engine` or `/generated` to the specialized engine's, so the report
can divide one variant's ratio by another's. The rows take their names,
patterns, and descriptions from
[`specialized-patterns.tsv`](specialized-patterns.tsv).

The renderer verifies `output-checksums.tsv`, writes the Markdown report, and
writes a sibling `.sha256` file. It does not use the current time, filesystem
iteration order, or host locale, so identical reducer inputs produce identical
output.

## Commands

```bash
python3 tools/re2-benchmark/baseline/aggregate_results.py \
    --primary-receipts /durable/path/campaign/primary/accepted-units.tsv \
    --confirmation-receipts /durable/path/campaign/confirmation/accepted-units.tsv \
    --artifacts-root /durable/path/campaign/primary \
    --artifacts-root /durable/path/campaign/confirmation \
    --events /durable/path/campaign/campaign-events.tsv \
    --artifact-index /durable/path/campaign/artifact-index.tsv \
    --campaign-artifact /durable/path/campaign/retrieval-metadata.tsv \
    --output-dir /durable/path/campaign/report-data

python3 tools/re2-benchmark/baseline/render_report.py \
    --input-dir /durable/path/campaign/report-data \
    --output /durable/path/campaign/BASELINE_REPORT.md
```

Each phase root keeps `semantic-rejections.tsv` beside its ledger, and the
reducer reads the attempts retained under it as [inputs](#inputs) describes.

The isolated tests use synthetic campaigns:

```bash
python3 -m unittest tools/re2-benchmark/baseline/tests/test_aggregate_results.py -v
```
